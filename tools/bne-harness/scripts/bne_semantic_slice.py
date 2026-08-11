#!/usr/bin/env python3
"""Recover named native predicate provenance from a Branch Witness history.

The Branch Witness capture already authenticates an executed GDB BTS history
and the concrete operands at one contrasted native branch.  This module keeps
that evidence offline, follows the two compare operands backward through the
executed x86 instructions, and labels only offsets present in the pinned BNE
unit layout.  Unknown tables and arguments remain explicit instead of being
assigned guessed game meanings.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import tempfile
import time
from typing import Any, Iterable

from bne_branch_capture import _assembly, parse_instruction_history
from bne_branch_witness import FIELD_LAYOUT, load_verified_capture
from bne_triage import canonical_digest, file_identity, inventory_files


SCHEMA = 2
ROOT = Path(__file__).resolve().parents[3]
IMPLEMENTATION = tuple(Path(__file__).with_name(name) for name in (
    "bne_semantic_slice.py", "bne_branch_capture.py",
    "bne_branch_witness.py", "bne_triage.py",
))


@dataclass(frozen=True)
class Expr:
    op: str
    args: tuple[Any, ...]

    def render(self) -> str:
        if self.op in {"symbol", "unknown"}:
            return str(self.args[0])
        if self.op == "const":
            return str(self.args[0])
        if self.op == "sub":
            return f"({render(self.args[0])} - {render(self.args[1])})"
        if self.op == "add":
            return f"({render(self.args[0])} + {render(self.args[1])})"
        if self.op == "neg":
            return f"(-{render(self.args[0])})"
        if self.op == "abs":
            return f"abs({render(self.args[0])})"
        if self.op in {"u8", "u16", "s8", "s16", "low8", "low16"}:
            return f"{self.op}({render(self.args[0])})"
        if self.op == "table":
            return f"bne.table_{int(self.args[0]):08x}[{render(self.args[1])}]"
        if self.op == "memory":
            return f"mem{self.args[0]}[{render(self.args[1])}]"
        if self.op == "pack16":
            return f"pack16({render(self.args[0])}, {render(self.args[1])})"
        if self.op == "merge16":
            return f"merge16({render(self.args[0])}, {render(self.args[1])})"
        if self.op == "xor":
            return f"({render(self.args[0])} xor {render(self.args[1])})"
        return f"{self.op}({', '.join(render(item) for item in self.args)})"

    def to_json(self) -> object:
        if self.op in {"symbol", "unknown", "const"}:
            return {"op": self.op, "value": self.args[0]}
        return {
            "op": self.op,
            "args": [item.to_json() if isinstance(item, Expr) else item
                     for item in self.args],
        }


def render(value: Any) -> str:
    return value.render() if isinstance(value, Expr) else str(value)


def symbol(name: str) -> Expr:
    return Expr("symbol", (name,))


def unknown(name: str) -> Expr:
    return Expr("unknown", (name,))


def const(value: int) -> Expr:
    return Expr("const", (value,))


def operation(name: str, *args: Expr) -> Expr:
    if name == "low16":
        value = args[0]
        if value.op == "pack16":
            return value.args[0]
        if value.op == "merge16":
            return operation("low16", value.args[1])
        if value.op in {"u8", "u16", "s8", "s16", "low8", "low16", "abs"}:
            return value
        if value.op == "neg":
            return operation("neg", operation("low16", value.args[0]))
    if name == "low8":
        value = args[0]
        if value.op in {"u8", "s8", "low8"}:
            return value
        if value.op == "merge_bits_0_8":
            return value.args[1]
    if name == "u16":
        value = operation("low16", args[0])
        if value.op == "low16":
            value = value.args[0]
        if value.op in {"u8", "u16"}:
            return value
        return Expr("u16", (value,))
    if name == "u8":
        value = operation("low8", args[0])
        if value.op == "low8":
            value = value.args[0]
        if value.op == "u8":
            return value
        return Expr("u8", (value,))
    if name == "s16":
        value = operation("low16", args[0])
        if value.op == "abs":
            return value
        return Expr("s16", (value,))
    if name == "neg" and args[0].op == "neg":
        return args[0].args[0]
    return Expr(name, tuple(args))


REGISTERS: dict[str, tuple[str, int, int]] = {}
for _full, _stem in (("eax", "a"), ("ebx", "b"), ("ecx", "c"),
                     ("edx", "d")):
    REGISTERS[_full] = (_full, 0, 32)
    REGISTERS[f"{_stem}x"] = (_full, 0, 16)
    REGISTERS[f"{_stem}l"] = (_full, 0, 8)
    REGISTERS[f"{_stem}h"] = (_full, 8, 8)
for _full, _short in (("esi", "si"), ("edi", "di"),
                      ("ebp", "bp"), ("esp", "sp")):
    REGISTERS[_full] = (_full, 0, 32)
    REGISTERS[_short] = (_full, 0, 16)

REGISTER_TOKEN = re.compile(r"^%(?P<name>[a-z][a-z0-9]*)$")
IMMEDIATE = re.compile(r"^\$(?P<value>-?(?:0x[0-9a-f]+|\d+))$", re.I)
MEMORY = re.compile(
    r"^(?P<disp>-?(?:0x[0-9a-f]+|\d+))?"
    r"(?:\((?P<base>%[a-z0-9]+)?(?:,(?P<index>%[a-z0-9]+)?"
    r"(?:,(?P<scale>\d+))?)?\))?$",
    re.I,
)
DIRECT_CALL = re.compile(r"^call\w*\s+(?P<target>0x[0-9a-f]+)", re.I)
DIRECT_JUMP = re.compile(r"^j(?P<condition>[a-z]+)\s+(?P<target>0x[0-9a-f]+)", re.I)


def _split_operands(text: str) -> tuple[str, list[str]]:
    pieces = text.strip().split(None, 1)
    mnemonic = pieces[0].lower() if pieces else ""
    if len(pieces) == 1:
        return mnemonic, []
    operands, current, depth = [], [], 0
    for character in pieces[1]:
        if character == "(":
            depth += 1
        elif character == ")":
            depth -= 1
        if character == "," and depth == 0:
            operands.append("".join(current).strip())
            current = []
        else:
            current.append(character)
    operands.append("".join(current).strip())
    return mnemonic, operands


def _register(token: str) -> tuple[str, int, int] | None:
    match = REGISTER_TOKEN.fullmatch(token.strip().lower())
    return REGISTERS.get(match.group("name")) if match else None


def _register_name(token: str) -> str | None:
    match = REGISTER_TOKEN.fullmatch(token.strip().lower())
    return match.group("name") if match and match.group("name") in REGISTERS else None


def _integer(text: str) -> int:
    return int(text, 0)


def _memory(token: str) -> tuple[int, str | None, str | None, int] | None:
    value = token.strip().lower()
    if value.startswith("*"):
        value = value[1:]
    match = MEMORY.fullmatch(value)
    if match is None or not (match.group("disp") or match.group("base")
                             or match.group("index")):
        return None
    base = match.group("base")
    index = match.group("index")
    return (
        _integer(match.group("disp")) if match.group("disp") else 0,
        base[1:] if base else None,
        index[1:] if index else None,
        int(match.group("scale") or 1),
    )


def _operand_width(mnemonic: str, destination: str | None) -> int:
    register = _register(destination or "")
    if register:
        return register[2]
    if mnemonic.endswith("b"):
        return 8
    if mnemonic.endswith("w"):
        return 16
    return 32


def _condition_operator(condition: str) -> str | None:
    return {
        "e": "==", "z": "==", "ne": "!=", "nz": "!=",
        "l": "<", "nge": "<", "b": "<", "c": "<", "nae": "<",
        "le": "<=", "ng": "<=", "be": "<=", "na": "<=",
        "g": ">", "nle": ">", "a": ">", "nbe": ">",
        "ge": ">=", "nl": ">=", "ae": ">=", "nb": ">=",
    }.get(condition.lower())


def _evaluate(left: int, right: int, operator: str) -> bool:
    return {
        "==": left == right, "!=": left != right,
        "<": left < right, "<=": left <= right,
        ">": left > right, ">=": left >= right,
    }[operator]


def _collect_symbols(expression: Expr) -> list[str]:
    result = set()

    def visit(item: Expr) -> None:
        if item.op in {"symbol", "unknown"}:
            result.add(str(item.args[0]))
        for child in item.args:
            if isinstance(child, Expr):
                visit(child)

    visit(expression)
    return sorted(result)


class DynamicSlice:
    """Small concrete-path x86 provenance engine for Branch Witness traces."""

    def __init__(self, plan: dict[str, Any], instructions: list[dict[str, Any]],
            compare_index: int,
            focus_pointer_sources: set[str] | None = None):
        self.plan = plan
        self.instructions = instructions
        self.compare_index = compare_index
        self.activation = self._activations()
        selected = self.activation[compare_index]
        self.frame = [index for index in range(compare_index + 1)
                      if self.activation[index] == selected]
        self.position = {index: position for position, index in enumerate(self.frame)}
        self.compare_position = self.position[compare_index]
        self.sp_before = self._stack_offsets()
        self.evidence: set[int] = {compare_index}
        self._memo: dict[tuple[str, int], Expr] = {}
        self._active: set[tuple[str, int]] = set()
        self._caller_argument_cache: dict[int, Expr] | None = None
        self.focus_pointer_sources = focus_pointer_sources \
            if focus_pointer_sources is not None else set()

    def bind_focus_source(self, expression: Expr) -> None:
        """Trust a pointer source only after the capture proves its address."""
        self.focus_pointer_sources.add(render(expression))
        # Caller arguments may have been recovered while discovering the
        # focus pointer. Recompute them with the newly authenticated alias.
        self._caller_argument_cache = None
        self._memo.clear()

    def _activations(self) -> list[int]:
        activations, stack = [], [0]
        pending: tuple[int, int] | None = None
        next_id = 1
        for instruction in self.instructions:
            if pending is not None and instruction["address"] == pending[0]:
                stack.append(pending[1])
            pending = None
            activations.append(stack[-1])
            assembly = _assembly(instruction["instruction"])
            call = DIRECT_CALL.match(assembly)
            if call:
                pending = (int(call.group("target"), 16), next_id)
                next_id += 1
            if assembly.split(None, 1)[0].lower().startswith("ret") \
                    and len(stack) > 1:
                stack.pop()
        return activations

    def _stack_offsets(self) -> list[int]:
        result, delta = [], 0
        for full_index in self.frame:
            result.append(delta)
            mnemonic, operands = _split_operands(
                _assembly(self.instructions[full_index]["instruction"])
            )
            if mnemonic.startswith("push"):
                delta -= 4
            elif mnemonic.startswith("pop"):
                delta += 4
            elif len(operands) == 2 and operands[1].lower() == "%esp":
                immediate = IMMEDIATE.fullmatch(operands[0])
                if immediate and mnemonic.startswith("sub"):
                    delta -= _integer(immediate.group("value"))
                elif immediate and mnemonic.startswith("add"):
                    delta += _integer(immediate.group("value"))
        return result

    def _instruction(self, position: int) -> tuple[int, str, list[str]]:
        full_index = self.frame[position]
        instruction = self.instructions[full_index]
        mnemonic, operands = _split_operands(_assembly(instruction["instruction"]))
        return int(instruction["address"]), mnemonic, operands

    def _writes_register(self, mnemonic: str, operands: list[str],
            requested: str) -> str | None:
        if not operands or mnemonic.startswith(("cmp", "test", "push", "call", "j")):
            return None
        destination = _register_name(operands[-1])
        if destination is None:
            return None
        wanted = REGISTERS[requested]
        written = REGISTERS[destination]
        if wanted[0] != written[0]:
            return None
        wanted_range = range(wanted[1], wanted[1] + wanted[2])
        written_range = range(written[1], written[1] + written[2])
        return destination if set(wanted_range).intersection(written_range) else None

    def explain(self, register: str, before: int | None = None) -> Expr:
        register = register.lower()
        before = self.compare_position if before is None else before
        key = (register, before)
        if key in self._memo:
            return self._memo[key]
        if key in self._active or register not in REGISTERS:
            return unknown(f"{register}@dynamic-entry")
        self._active.add(key)
        result = unknown(f"{register}@function-entry")
        for position in range(before - 1, -1, -1):
            address, mnemonic, operands = self._instruction(position)
            destination = self._writes_register(mnemonic, operands, register)
            if destination is None:
                continue
            self.evidence.add(self.frame[position])
            written = self._write_expression(
                mnemonic, operands, destination, position,
            )
            result = self._project_write(register, destination, written, position)
            break
        self._active.remove(key)
        self._memo[key] = result
        return result

    def _project_write(self, requested: str, destination: str,
            written: Expr, position: int) -> Expr:
        wanted = REGISTERS[requested]
        actual = REGISTERS[destination]
        if actual[1] <= wanted[1] \
                and actual[1] + actual[2] >= wanted[1] + wanted[2]:
            relative = wanted[1] - actual[1]
            if relative == 0 and wanted[2] == actual[2]:
                return written
            if relative == 0 and wanted[2] == 16:
                return operation("low16", written)
            if relative == 0 and wanted[2] == 8:
                return operation("low8", written)
            return Expr(f"bits{relative}_{wanted[2]}", (written,))
        previous = self.explain(requested, position)
        if actual[1] == 0 and actual[2] == 16 \
                and wanted[1] == 0 and wanted[2] == 32:
            return Expr("merge16", (previous, written))
        return Expr(
            f"merge_bits_{actual[1]}_{actual[2]}", (previous, written),
        )

    def _write_expression(self, mnemonic: str, operands: list[str],
            destination: str, position: int) -> Expr:
        width = REGISTERS[destination][2]
        if mnemonic.startswith("movz") and len(operands) == 2:
            source_width = _operand_width(mnemonic[:-1], operands[0])
            source = self._source(operands[0], position, source_width)
            return operation("u8" if source_width == 8 else "u16", source)
        if mnemonic.startswith("movs") and len(operands) == 2:
            source_width = 16 if "w" in mnemonic[:-1] else 8
            source = self._source(operands[0], position, source_width)
            return operation("s16" if source_width == 16 else "s8", source)
        if mnemonic.startswith("mov") and len(operands) == 2:
            return self._source(operands[0], position, width)
        if mnemonic.startswith("lea") and len(operands) == 2:
            return self._address_expression(operands[0], position)
        previous = self.explain(destination, position)
        if mnemonic.startswith("xor") and len(operands) == 2 \
                and operands[0].lower() == operands[1].lower():
            return const(0)
        if mnemonic.startswith("and") and len(operands) == 2:
            mask = self._source(operands[0], position, width)
            if mask.op == "const" and mask.args[0] in (0xff, 0xffff):
                return operation("u8" if mask.args[0] == 0xff else "u16",
                                 previous)
            return Expr("and", (previous, mask))
        if mnemonic.startswith("sub") and len(operands) == 2:
            return operation("sub", previous,
                             self._source(operands[0], position, width))
        if mnemonic.startswith("add") and len(operands) == 2:
            return operation("add", previous,
                             self._source(operands[0], position, width))
        if mnemonic.startswith("neg"):
            absolute = self._absolute_value_idiom(destination, position)
            return absolute or operation("neg", previous)
        if mnemonic.startswith("inc"):
            return operation("add", previous, const(1))
        if mnemonic.startswith("dec"):
            return operation("sub", previous, const(1))
        if mnemonic.startswith("pop"):
            return self._read_stack(self.sp_before[position], position, width)
        return unknown(f"{destination}@0x{self._instruction(position)[0]:08x}")

    def _absolute_value_idiom(self, destination: str,
            position: int) -> Expr | None:
        full = REGISTERS[destination][0]
        low16 = next(name for name, layout in REGISTERS.items()
                     if layout == (full, 0, 16))
        saw_nonnegative_guard = False
        for candidate in range(position - 1, max(-1, position - 8), -1):
            address, mnemonic, operands = self._instruction(candidate)
            jump = DIRECT_JUMP.match(
                " ".join((mnemonic, *operands)) if operands else mnemonic
            )
            if jump and jump.group("condition").lower() in {"ns", "ge", "nl"}:
                saw_nonnegative_guard = True
                self.evidence.add(self.frame[candidate])
                continue
            if saw_nonnegative_guard and mnemonic.startswith("sub") \
                    and len(operands) == 2 \
                    and _register_name(operands[1]) == low16:
                self.evidence.add(self.frame[candidate])
                before = self.explain(low16, candidate)
                source = self._source(operands[0], candidate, 16)
                return operation("abs", operation("sub", before, source))
        return None

    def _source(self, token: str, position: int, width: int) -> Expr:
        token = token.strip().lower()
        immediate = IMMEDIATE.fullmatch(token)
        if immediate:
            return const(_integer(immediate.group("value")))
        register = _register_name(token)
        if register:
            return self.explain(register, position)
        memory = _memory(token)
        if memory:
            return self._read_memory(memory, position, width)
        return unknown(token)

    def _address_expression(self, token: str, position: int) -> Expr:
        memory = _memory(token)
        if memory is None:
            return unknown(f"address({token})")
        displacement, base, index, scale = memory
        pieces = []
        if displacement:
            pieces.append(const(displacement))
        if base:
            pieces.append(self.explain(base, position))
        if index:
            indexed = self.explain(index, position)
            pieces.append(indexed if scale == 1 else Expr("mul", (indexed, const(scale))))
        result = pieces[0] if pieces else const(0)
        for piece in pieces[1:]:
            result = operation("add", result, piece)
        return result

    def _read_memory(self, memory: tuple[int, str | None, str | None, int],
            position: int, width: int) -> Expr:
        displacement, base, index, scale = memory
        if base == "esp" and index is None:
            return self._read_stack(
                self.sp_before[position] + displacement, position, width,
            )
        base_expression = self.explain(base, position) if base else None
        index_expression = self.explain(index, position) if index else None
        focus = f"unit[{self.plan['focus']['native_slot']}]"
        if base_expression and render(base_expression) \
                in self.focus_pointer_sources:
            return self._unit_field(focus, displacement, width)
        if base_expression and render(base_expression) == f"{focus}.target":
            return self._unit_field(f"{focus}.target", displacement, width)
        if displacement >= 0x00400000 and base_expression is None \
                and index_expression is None:
            # This is an address identity, not a guessed game-state name.
            return symbol(f"bne.global_{displacement:08x}")
        if displacement >= 0x00400000 and base_expression is not None \
                and index_expression is None:
            return Expr("table", (displacement, base_expression))
        address = const(displacement)
        if base_expression is not None:
            address = operation("add", address, base_expression)
        if index_expression is not None:
            indexed = index_expression if scale == 1 \
                else Expr("mul", (index_expression, const(scale)))
            address = operation("add", address, indexed)
        return Expr("memory", (width, address))

    def _unit_field(self, prefix: str, offset: int, width: int) -> Expr:
        exact = [(name, record) for name, record in FIELD_LAYOUT.items()
                 if int(record["offset"]) == offset]
        if exact:
            name, record = exact[0]
            expression = symbol(f"{prefix}.{name}")
            if width == int(record["bytes"]) * 8:
                return expression
            if width == 32 and int(record["bytes"]) == 2:
                following = next((other for other, layout in FIELD_LAYOUT.items()
                                  if int(layout["offset"]) == offset + 2
                                  and int(layout["bytes"]) == 2), None)
                if following:
                    return Expr("pack16", (
                        expression, symbol(f"{prefix}.{following}"),
                    ))
        return symbol(f"{prefix}.field_0x{offset:02x}_{width}")

    def _read_stack(self, slot: int, position: int, width: int) -> Expr:
        for candidate in range(position - 1, -1, -1):
            address, mnemonic, operands = self._instruction(candidate)
            if not mnemonic.startswith("mov") or len(operands) != 2:
                continue
            destination = _memory(operands[1])
            if destination is None or destination[1:] != ("esp", None, 1):
                continue
            candidate_slot = self.sp_before[candidate] + destination[0]
            if candidate_slot == slot:
                self.evidence.add(self.frame[candidate])
                return self._source(operands[0], candidate, width)
        caller = self._caller_arguments().get(slot)
        if caller is not None:
            return caller
        return symbol(f"stack_arg_{slot}") if slot > 0 \
            else unknown(f"stack_local_{slot}")

    def _caller_arguments(self) -> dict[int, Expr]:
        """Recover direct-call arguments from the executed caller when present."""
        if self._caller_argument_cache is not None:
            return self._caller_argument_cache
        self._caller_argument_cache = {}
        entry = self.frame[0]
        if entry == 0:
            return self._caller_argument_cache
        call_index = entry - 1
        call = DIRECT_CALL.match(_assembly(
            self.instructions[call_index]["instruction"]
        ))
        if call is None or int(call.group("target"), 16) \
                != int(self.instructions[entry]["address"]):
            return self._caller_argument_cache
        pushes: list[tuple[int, str]] = []
        for candidate in range(call_index - 1, max(-1, call_index - 12), -1):
            mnemonic, operands = _split_operands(
                _assembly(self.instructions[candidate]["instruction"])
            )
            if mnemonic.startswith("push") and len(operands) == 1:
                pushes.append((candidate, operands[0]))
                if len(pushes) == 4:
                    break
                continue
            if pushes and mnemonic.startswith(("call", "ret", "j")):
                break
        for argument, (push_index, token) in enumerate(pushes, 1):
            caller_slice = DynamicSlice(
                self.plan, self.instructions, push_index,
                focus_pointer_sources=self.focus_pointer_sources,
            )
            expression = caller_slice._source(
                token, caller_slice.compare_position, 32,
            )
            self.evidence.add(call_index)
            self.evidence.add(push_index)
            self.evidence.update(caller_slice.evidence)
            self._caller_argument_cache[argument * 4] = expression
        return self._caller_argument_cache

    def evidence_records(self) -> list[dict[str, Any]]:
        records = []
        for index, instruction in enumerate(self.instructions):
            if index in self.evidence:
                records.append({
                    "index": instruction["index"],
                    "address": instruction["address"],
                    "instruction": _assembly(instruction["instruction"]),
                })
        return records


def _json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"expected a JSON object: {path}")
    return value


def _write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(
                mode="w", encoding="utf-8", prefix=path.name + ".",
                suffix=".tmp", dir=path.parent, delete=False) as handle:
            temporary = Path(handle.name)
            json.dump(value, handle, indent=2, sort_keys=True)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _write_text(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(
                mode="w", encoding="utf-8", prefix=path.name + ".",
                suffix=".tmp", dir=path.parent, delete=False) as handle:
            temporary = Path(handle.name)
            handle.write(value)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _capture_manifest(path: Path) -> dict[str, Any]:
    manifest = path.with_name(path.stem + ".manifest.json")
    if not manifest.is_file():
        raise ValueError(f"capture manifest is missing: {manifest}")
    return _json(manifest)


def _verified_history(capture_path: Path, history_path: Path) -> dict[str, Any]:
    expected = _capture_manifest(capture_path).get("backend", {}).get(
        "raw_history", {}
    )
    history_path = history_path.expanduser().resolve()
    actual = file_identity(history_path)
    if actual != {"bytes": expected.get("bytes"), "sha256": expected.get("sha256")}:
        raise ValueError("raw GDB history differs from its capture manifest")
    return {"path": str(history_path), **actual}


def _predicate_event(capture: dict[str, Any]) -> dict[str, Any]:
    candidates = [event for event in capture.get("events", [])
                  if event.get("type") == "branch"
                  and isinstance(event.get("operands"), dict)
                  and isinstance(event.get("predicate_probe"), dict)]
    if not candidates:
        raise ValueError("capture has no concrete Branch Witness predicate")
    return candidates[-1]


def analyze_history(plan: dict[str, Any], capture: dict[str, Any],
        history_text: str) -> dict[str, Any]:
    started = time.monotonic()
    event = _predicate_event(capture)
    compare = int(event["predicate_probe"]["compare"])
    instructions = parse_instruction_history(history_text)
    occurrences = [index for index, instruction in enumerate(instructions)
                   if int(instruction["address"]) == compare]
    if not occurrences:
        raise ValueError(f"history does not execute compare 0x{compare:08x}")
    compare_index = occurrences[-1]
    assembly = _assembly(instructions[compare_index]["instruction"])
    mnemonic, operands = _split_operands(assembly)
    if not mnemonic.startswith("cmp") or len(operands) != 2:
        raise ValueError("predicate probe does not point at an x86 compare")
    # AT&T cmp source,destination sets flags for destination - source.
    lhs_register = _register_name(operands[1])
    rhs_register = _register_name(operands[0])
    if lhs_register is None or rhs_register is None:
        raise ValueError("semantic slicing currently requires register operands")
    expected_lhs = event["operands"]["lhs"].get("name")
    expected_rhs = event["operands"]["rhs"].get("name")
    if (lhs_register, rhs_register) != (expected_lhs, expected_rhs):
        raise ValueError("history compare operands differ from predicate capture")
    slicer = DynamicSlice(plan, instructions, compare_index)
    focus_record = event["predicate_probe"].get("focus_identity")
    focus_identity = {
        "proved": False,
        "reason": "capture did not authenticate the predicate unit pointer",
    }
    if isinstance(focus_record, dict) and focus_record.get("proved") is True:
        focus_register = str(focus_record.get("register", "")).lower()
        if focus_register not in REGISTERS:
            raise ValueError("predicate focus identity has an invalid register")
        if int(focus_record.get("observed", -1)) \
                != int(focus_record.get("expected", -2)):
            raise ValueError("predicate focus identity addresses differ")
        pointer_source = slicer.explain(focus_register)
        slicer.bind_focus_source(pointer_source)
        focus_identity = {
            "proved": True,
            "register": focus_register,
            "pointer_source": pointer_source.render(),
            "observed": int(focus_record["observed"]),
            "expected": int(focus_record["expected"]),
            "native_slot": int(plan["focus"]["native_slot"]),
        }
    lhs = slicer.explain(lhs_register)
    rhs = slicer.explain(rhs_register)
    operator = _condition_operator(str(event.get("condition", "")))
    if operator is None:
        raise ValueError("unsupported x86 predicate condition")
    left_value = int(event["operands"]["lhs"]["value"])
    right_value = int(event["operands"]["rhs"]["value"])
    predicted = _evaluate(left_value, right_value, operator)
    expression = f"{lhs.render()} {operator} {rhs.render()}"
    return {
        "cycle": capture["cycle"],
        "branch": {
            "address": event["address"], "compare": compare,
            "condition": event.get("condition"), "operator": operator,
        },
        "observed": {
            "lhs": left_value, "rhs": right_value,
            "taken": event["taken"], "predicted_taken": predicted,
        },
        "predicate": {
            "machine": f"{lhs_register} {operator} {rhs_register}",
            "semantic": expression,
            "lhs": {"expression": lhs.render(), "ast": lhs.to_json(),
                    "sources": _collect_symbols(lhs)},
            "rhs": {"expression": rhs.render(), "ast": rhs.to_json(),
                    "sources": _collect_symbols(rhs)},
        },
        "slice": slicer.evidence_records(),
        "instruction_count": len(instructions),
        "analysis_seconds": round(time.monotonic() - started, 6),
        "self_check": predicted == bool(event["taken"]),
        "focus_identity": focus_identity,
    }


def _boundary_experiment(anchor: dict[str, Any]) -> dict[str, Any]:
    observed = anchor["observed"]
    operator = anchor["branch"]["operator"]
    left, right = int(observed["lhs"]), int(observed["rhs"])
    if operator == ">":
        return {
            "hold_constant": "semantic RHS and all non-slice state",
            "flip_to_not_taken": {"lhs": right, "rhs": right,
                                  "prediction": False},
            "flip_to_taken": {"lhs": right + 1, "rhs": right,
                               "prediction": True},
            "anchor_delta_to_boundary": abs(left - right),
        }
    if operator == ">=":
        return {
            "hold_constant": "semantic RHS and all non-slice state",
            "flip_to_not_taken": {"lhs": right - 1, "rhs": right,
                                  "prediction": False},
            "flip_to_taken": {"lhs": right, "rhs": right,
                               "prediction": True},
            "anchor_delta_to_boundary": abs(left - right),
        }
    return {"supported": False, "reason": f"no boundary template for {operator}"}


def analyze_semantic_slice(plan: dict[str, Any], capture: dict[str, Any],
        history_text: str, controls: Iterable[tuple[dict[str, Any], str]]) \
        -> dict[str, Any]:
    anchor = analyze_history(plan, capture, history_text)
    held_out = []
    for control, control_history in controls:
        recovered = analyze_history(plan, control, control_history)
        same_formula = recovered["predicate"]["semantic"] \
            == anchor["predicate"]["semantic"]
        held_out.append({
            "cycle": recovered["cycle"],
            "semantic_expression": recovered["predicate"]["semantic"],
            "same_formula": same_formula,
            "observed": recovered["observed"],
            "prediction_correct": recovered["self_check"],
            "focus_identity_proven": recovered["focus_identity"]["proved"],
            "passed": same_formula and recovered["self_check"],
        })
    named = [source for side in ("lhs", "rhs")
             for source in anchor["predicate"][side]["sources"]
             if source.startswith("unit[")]
    unresolved = [source for side in ("lhs", "rhs")
                  for source in anchor["predicate"][side]["sources"]
                  if not source.startswith("unit[")]
    predicate_recovery_passed = anchor["self_check"] and bool(held_out) \
        and all(item["passed"] for item in held_out)
    focus_identity_proven = anchor["focus_identity"]["proved"] \
        and all(item["focus_identity_proven"] for item in held_out)
    passed = predicate_recovery_passed and focus_identity_proven and bool(named)
    return {
        "schema": SCHEMA, "case": capture["case"],
        "created_at": datetime.now(timezone.utc).isoformat(),
        "anchor": anchor, "held_out": held_out,
        "boundary_experiment": _boundary_experiment(anchor),
        "confidence": {
            "grade": "high-partial" if passed else "investigative",
            "named_sources": sorted(set(named)),
            "unresolved_sources": sorted(set(unresolved)),
            "does_not_guess_unknown_tables": True,
            "held_out_validation_passed": predicate_recovery_passed,
            "focus_identity_proven": focus_identity_proven,
        },
        "proof": {
            "offline": True, "engine_source_changed": False,
            "oracle_source_changed": False,
            "repeatable_inputs_required": True,
            "predicate_recovery_passed": predicate_recovery_passed,
            "focus_identity_proven": focus_identity_proven,
            "passed": passed,
        },
    }


def _summary(result: dict[str, Any]) -> str:
    anchor = result["anchor"]
    lines = [
        "# BNE Semantic Predicate Slice", "",
        f"- Case: `{result['case']}` at cycle {anchor['cycle']}",
        f"- Native branch: `0x{anchor['branch']['address']:08x}`",
        f"- Machine predicate: `{anchor['predicate']['machine']}`",
        f"- Recovered predicate: `{anchor['predicate']['semantic']}`",
        f"- Anchor observation: `{anchor['observed']['lhs']} "
        f"{anchor['branch']['operator']} {anchor['observed']['rhs']}` -> "
        f"`{anchor['observed']['taken']}`", "",
        "## Evidence boundary", "",
        "Known unit offsets are named from the pinned 152-byte BNE layout. "
        "A unit[N] name is emitted only when the predicate probe proves that "
        "its runtime pointer equals the watched unit. Unknown arguments, "
        "globals, and native tables remain explicit. This packet is "
        "a diagnostic lead; the authenticated full regression gate remains "
        "the acceptance authority.", "",
        "## Held-out validation", "",
    ]
    for control in result["held_out"]:
        lines.append(
            f"- Cycle {control['cycle']}: same formula "
            f"`{control['same_formula']}`, prediction correct "
            f"`{control['prediction_correct']}`, focus identity "
            f"`{control['focus_identity_proven']}`"
        )
    lines.extend([
        "",
        f"Predicate recovery passed: "
        f"`{result['proof']['predicate_recovery_passed']}`",
        f"Focus identity proved: "
        f"`{result['proof']['focus_identity_proven']}`",
        f"Proof passed: `{result['proof']['passed']}`", "",
    ])
    return "\n".join(lines)


def run_semantic_slice(plan_path: Path, capture_path: Path, history_path: Path,
        artifact_root: Path, *, control_pairs: Iterable[tuple[Path, Path]]) \
        -> tuple[int, Path]:
    plan_path = plan_path.expanduser().resolve()
    capture_path = capture_path.expanduser().resolve()
    history_path = history_path.expanduser().resolve()
    plan = _json(plan_path)
    capture, capture_evidence = load_verified_capture(capture_path, plan=plan)
    history_evidence = _verified_history(capture_path, history_path)
    controls, control_evidence = [], []
    for control_path, control_history_path in control_pairs:
        control_path = control_path.expanduser().resolve()
        control_history_path = control_history_path.expanduser().resolve()
        control, evidence = load_verified_capture(
            control_path, plan=plan, require_plan_match=False,
        )
        controls.append((control, control_history_path.read_text(
            encoding="utf-8", errors="replace",
        )))
        control_evidence.append({
            "capture": evidence,
            "history": _verified_history(control_path, control_history_path),
        })
    if not controls:
        raise ValueError("semantic slicing requires at least one held-out control")
    request = {
        "schema": SCHEMA,
        "implementation": {
            path.name: file_identity(path) for path in IMPLEMENTATION
        },
        "plan": {"path": str(plan_path), **file_identity(plan_path)},
        "capture": capture_evidence, "history": history_evidence,
        "controls": control_evidence,
    }
    request_sha256 = canonical_digest(request)
    artifact_root = artifact_root.expanduser().resolve()
    run_root = artifact_root / "runs" / request_sha256
    manifest_path = run_root / "manifest.json"
    def promote(manifest: dict[str, Any]) -> None:
        pointer = {
            "schema": SCHEMA, "request_sha256": request_sha256,
            "run": str(run_root.relative_to(artifact_root)),
            "manifest": str(manifest_path.relative_to(artifact_root)),
            "manifest_identity": file_identity(manifest_path),
            "case": manifest["result"]["case"],
            "proof_passed": manifest["result"]["proof"]["passed"],
        }
        _write_json(artifact_root / "latest.json", pointer)
        _write_json(
            artifact_root / f"latest-{manifest['result']['case']}.json", pointer,
        )

    if manifest_path.is_file():
        manifest = _json(manifest_path)
        if manifest.get("request_sha256") != request_sha256 \
                or canonical_digest(manifest.get("request")) != request_sha256:
            raise ValueError("cached semantic-slice request identity changed")
        for relative, identity in manifest.get("artifacts", {}).items():
            if file_identity(run_root / relative) != identity:
                raise ValueError(f"semantic-slice artifact changed: {relative}")
        promote(manifest)
        return 0 if manifest["result"]["proof"]["passed"] else 1, run_root
    run_root.mkdir(parents=True, exist_ok=True)
    result = analyze_semantic_slice(
        plan, capture,
        history_path.read_text(encoding="utf-8", errors="replace"), controls,
    )
    _write_json(run_root / "semantic-slice.json", result)
    _write_text(run_root / "NEXT.md", _summary(result))
    manifest = {
        "schema": SCHEMA, "request_sha256": request_sha256,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "request": request, "result": result,
        "artifacts": inventory_files(
            run_root, [run_root / "semantic-slice.json", run_root / "NEXT.md"],
        ),
    }
    _write_json(manifest_path, manifest)
    promote(manifest)
    return 0 if result["proof"]["passed"] else 1, run_root


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("plan", type=Path)
    result.add_argument("--capture", type=Path, required=True)
    result.add_argument("--history", type=Path, required=True)
    result.add_argument("--control-capture", type=Path, action="append", default=[])
    result.add_argument("--control-history", type=Path, action="append", default=[])
    result.add_argument("--artifact-root", type=Path,
                        default=ROOT / ".bne-semantic-slice")
    return result


def main() -> int:
    args = parser().parse_args()
    if len(args.control_capture) != len(args.control_history):
        raise SystemExit("--control-capture and --control-history counts differ")
    status, run_root = run_semantic_slice(
        args.plan, args.capture, args.history, args.artifact_root,
        control_pairs=zip(args.control_capture, args.control_history),
    )
    print((run_root / "NEXT.md").read_text(encoding="utf-8"), end="")
    print(f"\nDurable semantic-slice run: {run_root}")
    return status


if __name__ == "__main__":
    raise SystemExit(main())
