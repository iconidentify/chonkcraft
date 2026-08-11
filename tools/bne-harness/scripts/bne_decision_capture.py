#!/usr/bin/env python3
"""Capture one complete, focus-scoped native decision-function activation.

Branch Witness stops on a write.  That is ideal for an accepted transition but
cannot observe a decision that deliberately writes nothing.  This module uses
the accepted writer only to identify the function and unit-pointer register,
then records entry-to-return BTS histories for both accepted and rejected
visits.  Captures remain offline, bounded, pinned to BNE 2.02b, and sealed with
their raw history and exact plan identity.
"""

from __future__ import annotations

from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shlex
import tempfile
from typing import Any

from bne_branch_capture import (
    CONDITIONAL_JUMP, _assembly, _branch_target, parse_instruction_history,
)
from bne_branch_witness import (
    BNE_202_SHA256, BNE_TEXT_END, BNE_TEXT_START, FIELD_LAYOUT,
    UNIT_BYTES, UNIT_POOL_POINTER,
)
from bne_triage import canonical_digest, file_identity


SCHEMA = 1
CAPTURE_SCHEMA = 1
MANIFEST_SCHEMA = 1
ACTIVATION_FIELD_DELTA = "activation-field-delta"
FIXTURE_CYCLE_OUTCOME = "fixture-cycle-outcome"
REGISTER_WIDTHS = {
    "eax": 32, "ebx": 32, "ecx": 32, "edx": 32,
    "esi": 32, "edi": 32, "ebp": 32, "esp": 32,
    "ax": 16, "bx": 16, "cx": 16, "dx": 16,
    "si": 16, "di": 16, "bp": 16, "sp": 16,
    "al": 8, "ah": 8, "bl": 8, "bh": 8,
    "cl": 8, "ch": 8, "dl": 8, "dh": 8,
}
REGISTER_FAMILY = {
    name: family for family, names in {
        "eax": ("eax", "ax", "al", "ah"),
        "ebx": ("ebx", "bx", "bl", "bh"),
        "ecx": ("ecx", "cx", "cl", "ch"),
        "edx": ("edx", "dx", "dl", "dh"),
        "esi": ("esi", "si"), "edi": ("edi", "di"),
        "ebp": ("ebp", "bp"), "esp": ("esp", "sp"),
    }.items() for name in names
}
REGISTER = re.compile(r"^%(?P<name>[a-z][a-z0-9]*)$", re.I)
IMMEDIATE = re.compile(r"^\$(?P<value>-?(?:0x[0-9a-f]+|\d+))$", re.I)
MEMORY = re.compile(
    r"^(?P<disp>-?(?:0x[0-9a-f]+|\d+))?"
    r"(?:\((?P<base>%[a-z0-9]+)?(?:,(?P<index>%[a-z0-9]+)?"
    r"(?:,(?P<scale>\d+))?)?\))?$", re.I,
)
DECISION_START = re.compile(
    r"BNEDECISION\s+entry=(?P<entry>0x[0-9a-f]+)\s+"
    r"focus=(?P<focus>0x[0-9a-f]+)\s+"
    r"expected=(?P<expected>0x[0-9a-f]+)\s+"
    r"return=(?P<return>0x[0-9a-f]+)\s+"
    r"before=(?P<before>\d+)", re.I,
)
DECISION_END = re.compile(
    r"BNEDECISIONEND\s+entry=(?P<entry>0x[0-9a-f]+)\s+"
    r"after=(?P<after>\d+)", re.I,
)
PREDICATE_MARKER = re.compile(
    r"BNEDECISIONPRED\s+branch=(?P<branch>0x[0-9a-f]+)\s+"
    r"flag=(?P<flag>0x[0-9a-f]+)\s+hit=(?P<hit>\d+)\s+"
    r"lhs=(?P<lhs>-?\d+)\s+rhs=(?P<rhs>-?\d+)\s+"
    r"lhs_addr=(?P<lhs_addr>0x[0-9a-f]+)\s+"
    r"rhs_addr=(?P<rhs_addr>0x[0-9a-f]+)\s+"
    r"focus=(?P<focus>0x[0-9a-f]+)\s+"
    r"expected=(?P<expected>0x[0-9a-f]+)", re.I,
)
FLAG_MNEMONICS = {"cmp": "compare", "test": "test",
                  "sub": "subtract", "and": "and", "or": "or"}
FLAG_PRESERVING = {"mov", "movb", "movw", "movl", "lea", "leal",
                   "nop", "push", "pushl", "pop", "popl"}
FIELD_BY_OFFSET = {
    int(value["offset"]): name for name, value in FIELD_LAYOUT.items()
}


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


def _json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"expected JSON object: {path}")
    return value


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


def _base_mnemonic(mnemonic: str) -> str:
    for prefix in FLAG_MNEMONICS:
        if mnemonic == prefix or mnemonic in {
                prefix + "b", prefix + "w", prefix + "l"}:
            return prefix
    return mnemonic


def _width(mnemonic: str, destination: str) -> int:
    match = REGISTER.fullmatch(destination.lower())
    if match and match.group("name") in REGISTER_WIDTHS:
        return REGISTER_WIDTHS[match.group("name")]
    return 8 if mnemonic.endswith("b") else 16 if mnemonic.endswith("w") else 32


def _address_expression(base: str | None, index: str | None,
        scale: int, displacement: int) -> str:
    terms = []
    if base:
        terms.append(f"${base}")
    if index:
        terms.append(f"${index} * {scale}")
    if displacement or not terms:
        terms.append(str(displacement))
    return " + ".join(terms)


def _memory_semantic(base: str | None, index: str | None,
        displacement: int, focus_register: str,
        width: int, scale: int) -> tuple[str, dict[str, Any]]:
    if base == focus_register and index is None \
            and displacement in FIELD_BY_OFFSET:
        field = FIELD_BY_OFFSET[displacement]
        if int(FIELD_LAYOUT[field]["bytes"]) * 8 == width:
            name = f"unit[*].{field}"
            return name, {"op": "symbol", "value": name}
    if base is None and index is None:
        name = f"bne.global_{displacement & 0xffffffff:08x}"
        return name, {"op": "unknown", "value": name}
    expression = "+".join(part for part in (
        base, f"{index}*{scale}" if index else None,
        str(displacement) if displacement else None,
    ) if part)
    name = f"mem[{expression}]"
    return name, {"op": "unknown", "value": name}


def parse_operand(token: str, *, width: int,
        focus_register: str) -> dict[str, Any] | None:
    value = token.strip().lower()
    if matched := REGISTER.fullmatch(value):
        register = matched.group("name")
        if register not in REGISTER_WIDTHS:
            return None
        return {
            "kind": "register", "name": register,
            "width": REGISTER_WIDTHS[register], "gdb": f"${register}",
            "address_gdb": "0",
            "semantic_ast": {
                "op": "unknown", "value": f"native.register.{register}",
            },
        }
    if matched := IMMEDIATE.fullmatch(value):
        integer = int(matched.group("value"), 0)
        return {
            "kind": "immediate", "name": str(integer), "value": integer,
            "width": width, "gdb": str(integer), "address_gdb": "0",
            "semantic_ast": {"op": "const", "value": integer},
        }
    if value.startswith("*"):
        value = value[1:]
    matched = MEMORY.fullmatch(value)
    if matched is None or not any(matched.group(name)
                                  for name in ("disp", "base", "index")):
        return None
    displacement = int(matched.group("disp"), 0) \
        if matched.group("disp") else 0
    base = matched.group("base")
    index = matched.group("index")
    base = base[1:].lower() if base else None
    index = index[1:].lower() if index else None
    scale = int(matched.group("scale") or 1)
    if base and base not in REGISTER_WIDTHS:
        return None
    if index and index not in REGISTER_WIDTHS:
        return None
    address = _address_expression(base, index, scale, displacement)
    type_name = {8: "unsigned char", 16: "unsigned short",
                 32: "unsigned int"}[width]
    name, semantic = _memory_semantic(
        base, index, displacement, focus_register, width, scale,
    )
    return {
        "kind": "memory", "name": name, "width": width,
        "base": base, "index": index, "scale": scale,
        "displacement": displacement,
        "gdb": f"*({type_name}*)({address})",
        "address_gdb": address, "semantic_ast": semantic,
    }


def resolve_register_source(instructions: list[dict[str, Any]],
        before_index: int, operand: dict[str, Any], focus_register: str,
        *, maximum_backtrack: int = 4) -> dict[str, Any]:
    """Resolve one bounded, direct load into a compared register."""
    if operand.get("kind") != "register":
        return operand
    register = str(operand["name"])
    family = REGISTER_FAMILY[register]
    lower = max(0, before_index - maximum_backtrack)
    for index in range(before_index - 1, lower - 1, -1):
        assembly = _assembly(instructions[index]["instruction"])
        mnemonic, operands = _split_operands(assembly)
        if mnemonic.startswith(("call", "ret", "j")):
            break
        destination = operands[-1] if operands else ""
        destination_match = REGISTER.fullmatch(destination.lower())
        destination_family = (REGISTER_FAMILY.get(destination_match.group("name"))
                              if destination_match else None)
        if destination_family != family:
            continue
        if mnemonic not in {"mov", "movb", "movw", "movl"} \
                or len(operands) != 2:
            break
        source = parse_operand(
            operands[0], width=int(operand["width"]),
            focus_register=focus_register,
        )
        if source is None or source.get("kind") == "register":
            break
        return {
            **source,
            "resolved_from": {
                "register": register,
                "instruction": int(instructions[index]["address"]),
                "assembly": assembly,
                "bounded_backtrack": before_index - index,
            },
        }
    return operand


def predicate_probe(instructions: list[dict[str, Any]], branch_index: int,
        branch: int, condition: str, focus_register: str,
        *, maximum_backtrack: int = 4) -> dict[str, Any] | None:
    """Recover the nearest bounded flag-producing instruction and operands."""
    lower = max(0, branch_index - maximum_backtrack)
    for index in range(branch_index - 1, lower - 1, -1):
        assembly = _assembly(instructions[index]["instruction"])
        mnemonic, operands = _split_operands(assembly)
        base = _base_mnemonic(mnemonic)
        if base in FLAG_MNEMONICS and len(operands) == 2:
            width = _width(mnemonic, operands[1])
            source = parse_operand(
                operands[0], width=width, focus_register=focus_register,
            )
            destination = parse_operand(
                operands[1], width=width, focus_register=focus_register,
            )
            if source is None or destination is None:
                return None
            source = resolve_register_source(
                instructions, index, source, focus_register,
            )
            destination = resolve_register_source(
                instructions, index, destination, focus_register,
            )
            address = int(instructions[index]["address"])
            if not BNE_TEXT_START <= address < branch < BNE_TEXT_END \
                    or branch - address > 64:
                return None
            return {
                "schema": SCHEMA, "branch": branch,
                "flag_instruction": address,
                "flag_operation": FLAG_MNEMONICS[base],
                "condition": condition.lower(), "width": width,
                # AT&T syntax is source,destination; flags describe dest op src.
                "lhs": destination, "rhs": source,
                "focus_register": focus_register.lower(),
                "skipped_instructions": branch_index - index - 1,
                "scope": "one focus-scoped decision-function activation",
            }
        if mnemonic.startswith(("j", "call", "ret")):
            break
        if mnemonic not in FLAG_PRESERVING:
            break
    return None


def _safe_path(path: Path) -> str:
    value = str(path)
    if any(character in value for character in ("\n", "\r", "\0")):
        raise ValueError("unsafe GDB marker path")
    return shlex.quote(value)


def _probe_commands(probe: dict[str, Any]) -> list[str]:
    lhs, rhs = probe["lhs"], probe["rhs"]
    focus = probe["focus_register"]
    marker = (
        f"printf \"BNEDECISIONPRED branch=0x{probe['branch']:08x} "
        f"flag=0x{probe['flag_instruction']:08x} hit=%u lhs=%d rhs=%d "
        "lhs_addr=0x%x rhs_addr=0x%x focus=0x%x expected=0x%x\\n\", "
        f"$bne_probe_hits, (int)({lhs['gdb']}), (int)({rhs['gdb']}), "
        f"(unsigned int)({lhs['address_gdb']}), "
        f"(unsigned int)({rhs['address_gdb']}), "
        f"(unsigned int)${focus}, (unsigned int)$bne_focus"
    )
    return [
        "set $bne_probe_hits = 0",
        f"break *0x{probe['flag_instruction']:08x}",
        "commands", "silent",
        f"if $bne_in_decision == 1 && "
        f"(unsigned int)${focus} == (unsigned int)$bne_focus",
        "set $bne_probe_hits = $bne_probe_hits + 1",
        marker, "end", "continue", "end",
    ]


def gdb_commands(plan: dict[str, Any], phase: str, *, history_log: Path,
        resume_marker: Path) -> str:
    spec = plan["captures"].get(phase)
    if not isinstance(spec, dict):
        raise ValueError(f"decision plan has no phase {phase}")
    decision = plan["decision"]
    field = decision["field"]
    layout = FIELD_LAYOUT[field]
    size = int(layout["bytes"])
    type_name = {1: "unsigned char", 2: "unsigned short",
                 4: "unsigned int"}[size]
    entry = int(decision["entry_address"])
    expected_return = decision.get("entry_return_address")
    focus_register = str(decision["focus_register"]).lower()
    entry_focus = decision.get("entry_focus")
    if isinstance(entry_focus, dict) \
            and entry_focus.get("kind") == "entry-stack-pointer":
        entry_focus_expression = (
            f"*(unsigned int*)($esp + {int(entry_focus['offset'])})"
        )
    else:
        # Compatibility for synthetic and previously generated schema-1 plans.
        entry_focus_expression = f"${focus_register}"
    slot = int(plan["focus"]["native_slot"])
    commands = [
        "set pagination off", "set confirm off",
        "set print thread-events off",
        f"set logging file {history_log}",
        "set logging overwrite on", "set logging redirect on",
        "set logging enabled on",
        f"set $bne_pool = *(unsigned int*)0x{UNIT_POOL_POINTER:08x}",
        f"set $bne_focus = $bne_pool + {slot} * {UNIT_BYTES}",
        f"set $bne_watch = $bne_focus + {layout['offset']}",
        "set $bne_in_decision = 0",
    ]
    probe = plan.get("predicate_probe")
    if isinstance(probe, dict):
        commands.extend(_probe_commands(probe))
    commands.extend([
        "python", "class BneDecisionEntry(gdb.Breakpoint):",
        "    def stop(self):",
        "        return_address = int(gdb.parse_and_eval('*(unsigned int*)$esp')) & 0xffffffff",
        *(([
            f"        if return_address != 0x{int(expected_return):08x}:",
            "            return False",
        ]) if expected_return is not None else []),
        f"        focus = int(gdb.parse_and_eval('"
        f"{entry_focus_expression}')) & 0xffffffff",
        "        expected = int(gdb.parse_and_eval('$bne_focus')) & 0xffffffff",
        "        if focus != expected:", "            return False",
        "        self.enabled = False",
        f"        before = int(gdb.parse_and_eval('*({type_name}*)$bne_watch'))",
        "        gdb.set_convenience_variable('bne_before', before)",
        "        gdb.set_convenience_variable('bne_return', return_address)",
        "        gdb.set_convenience_variable('bne_in_decision', 1)",
        f"        gdb.write('BNEDECISION entry=0x{entry:08x} focus=0x%x "
        "expected=0x%x return=0x%x before=%u\\n' % "
        "(focus, expected, return_address, before))",
        "        gdb.execute('set record btrace bts buffer-size 1048576')",
        "        gdb.execute('record btrace bts')",
        "        gdb.Breakpoint('*0x%08x' % return_address, temporary=True)",
        "        return False",
        f"BneDecisionEntry('*0x{entry:08x}')", "end",
        f"shell touch {_safe_path(resume_marker)}", "continue",
        f"set $bne_after = *({type_name}*)$bne_watch",
        f"printf \"BNEDECISIONEND entry=0x{entry:08x} after=%u\\n\", "
        "$bne_after",
        f"set record instruction-history-size "
        f"{plan['capture_window']['maximum_instructions']}",
        "record instruction-history", "set logging enabled off",
        "record stop", "detach", "quit", "",
    ])
    return "\n".join(commands)


def parse_capture(plan: dict[str, Any], phase: str, text: str) \
        -> dict[str, Any]:
    spec = plan["captures"].get(phase)
    if not isinstance(spec, dict):
        raise ValueError(f"decision plan has no phase {phase}")
    start, end = DECISION_START.search(text), DECISION_END.search(text)
    if start is None or end is None:
        raise ValueError("decision capture lacks completed entry/return markers")
    decision = plan["decision"]
    entry = int(decision["entry_address"])
    expected_return = decision.get("entry_return_address")
    observed_entry = int(start.group("entry"), 16)
    observed_end_entry = int(end.group("entry"), 16)
    focus = int(start.group("focus"), 16)
    expected = int(start.group("expected"), 16)
    if observed_entry != entry or observed_end_entry != entry \
            or focus != expected:
        raise ValueError("decision capture did not authenticate focus identity")
    instructions = parse_instruction_history(text)
    if len(instructions) < 2:
        raise ValueError("decision capture has too little instruction history")
    if len(instructions) > int(plan["capture_window"]["maximum_instructions"]):
        raise ValueError("decision capture exceeds its bounded history window")
    events = []
    for index, instruction in enumerate(instructions[:-1]):
        assembly = _assembly(instruction["instruction"])
        target = _branch_target(assembly)
        address = int(instruction["address"])
        if target is None or not BNE_TEXT_START <= address < BNE_TEXT_END:
            continue
        condition_match = CONDITIONAL_JUMP.match(assembly)
        condition = condition_match.group("condition") if condition_match else ""
        events.append({
            "seq": len(events) + 1, "type": "branch",
            "cycle": int(spec["cycle"]), "address": address,
            "target": target,
            "taken": int(instructions[index + 1]["address"]) == target,
            "condition": condition, "instruction": assembly,
            "predicate_probe_plan": predicate_probe(
                instructions, index, address, condition,
                str(decision["focus_register"]),
            ),
        })
    if not events:
        raise ValueError("decision capture contains no conditional branches")
    probe = plan.get("predicate_probe")
    markers = list(PREDICATE_MARKER.finditer(text))
    if isinstance(probe, dict) and not markers:
        raise ValueError("decision predicate pass contains no operand marker")
    for marker in markers:
        address = int(marker.group("branch"), 16)
        flag_address = int(marker.group("flag"), 16)
        if isinstance(probe, dict) and (
                address != int(probe["branch"])
                or flag_address != int(probe["flag_instruction"])):
            raise ValueError("predicate marker differs from its probe plan")
        occurrences = [event for event in events if event["address"] == address]
        hit = int(marker.group("hit"))
        if hit <= 0 or hit > len(occurrences):
            raise ValueError("predicate marker cannot be matched to branch history")
        event = occurrences[hit - 1]
        observed_focus = int(marker.group("focus"), 16)
        expected_focus = int(marker.group("expected"), 16)
        if observed_focus != expected_focus:
            raise ValueError("predicate marker focus identity differs")
        selected_probe = probe if isinstance(probe, dict) \
            else event.get("predicate_probe_plan")
        if not isinstance(selected_probe, dict):
            raise ValueError("predicate marker has no authenticated probe plan")
        event["operands"] = {
            "lhs": {**selected_probe["lhs"],
                    "value": int(marker.group("lhs")),
                    "runtime_address": int(marker.group("lhs_addr"), 16)},
            "rhs": {**selected_probe["rhs"],
                    "value": int(marker.group("rhs")),
                    "runtime_address": int(marker.group("rhs_addr"), 16)},
        }
        event["predicate_probe"] = {
            "flag_instruction": flag_address,
            "hit": hit, "focus_identity": {
                "required": True, "proved": True,
                "register": selected_probe["focus_register"],
                "observed": observed_focus, "expected": expected_focus,
            },
        }
    before, after = int(start.group("before")), int(end.group("after"))
    observed_return = int(start.group("return"), 16)
    outcome_source = decision.get("outcome_source", ACTIVATION_FIELD_DELTA)
    if expected_return is not None and observed_return != int(expected_return):
        raise ValueError("decision capture differs from its accepted caller")
    if outcome_source == ACTIVATION_FIELD_DELTA:
        if before != spec["before"] or after != spec["after"] \
                or (before != after) != bool(spec["changed"]):
            raise ValueError("decision field outcome differs from the sealed plan")
    elif outcome_source == FIXTURE_CYCLE_OUTCOME:
        # An upstream activation returns before the write it causes, so only
        # the value it entered on is a sealed fact; the value it left behind
        # is recorded as an observation and never asserted.
        if before != spec["before"]:
            raise ValueError("decision capture entered on the wrong field state")
    else:
        raise ValueError(f"unsupported decision outcome source: {outcome_source}")
    return {
        "schema": CAPTURE_SCHEMA, "backend": "gdb-bts-decision-visit",
        "case": plan["case"], "decision_id": plan["decision_id"],
        "phase": phase, "cycle": int(spec["cycle"]),
        "field": decision["field"], "instruction_count": len(instructions),
        "decision": {
            "entry_address": entry,
            "return_address": observed_return,
            "focus_register": decision["focus_register"],
            "native_slot": plan["focus"]["native_slot"],
            "focus_address": focus, "before": before, "after": after,
            "changed": before != after,
            "outcome_source": outcome_source,
            "expected_outcome": spec["expected_outcome"],
        },
        "events": events,
    }


def validate_capture(capture: dict[str, Any]) -> None:
    if capture.get("schema") != CAPTURE_SCHEMA \
            or capture.get("backend") != "gdb-bts-decision-visit":
        raise ValueError("unsupported decision capture schema or backend")
    if not isinstance(capture.get("events"), list) or not capture["events"]:
        raise ValueError("decision capture contains no events")
    previous = 0
    for event in capture["events"]:
        if event.get("type") != "branch" \
                or not isinstance(event.get("seq"), int) \
                or event["seq"] <= previous \
                or not isinstance(event.get("address"), int) \
                or not isinstance(event.get("taken"), bool):
            raise ValueError("malformed decision branch event")
        previous = event["seq"]


def seal_capture(plan: dict[str, Any], phase: str, history_log: Path,
        output: Path, *, executable: Path, tracer: Path,
        oracle_run_manifest: Path, gdb_version: str = "unknown",
        network_disabled: bool = True) -> tuple[Path, Path]:
    executable = executable.expanduser().resolve()
    tracer = tracer.expanduser().resolve()
    history_log = history_log.expanduser().resolve()
    oracle_run_manifest = oracle_run_manifest.expanduser().resolve()
    output = output.expanduser().resolve()
    if file_identity(executable)["sha256"] != BNE_202_SHA256:
        raise ValueError("decision capture refuses an unpinned BNE executable")
    if not tracer.is_file() or not history_log.is_file():
        raise ValueError("decision capture tracer or history is missing")
    if not network_disabled:
        raise ValueError("decision capture requires a network-disabled runtime")
    oracle = _json(oracle_run_manifest)
    spec = plan["captures"][phase]
    validation = oracle.get("run", {}).get("validation", {})
    runtime = oracle.get("runtime", {})
    if oracle.get("oracle", {}).get("executable", {}).get("sha256") \
            != BNE_202_SHA256 \
            or runtime.get("network_disabled") is not True \
            or runtime.get("branch_witness_pause_cycle") != int(spec["cycle"]):
        raise ValueError("oracle manifest lacks the pinned offline decision pause")
    if validation.get("scenario") != plan.get("scenario") \
            or validation.get("initialization_seed") != plan.get("seed") \
            or validation.get("cycles", 0) < int(spec["cycle"]):
        raise ValueError("oracle run differs from the decision plan")
    capture = parse_capture(
        plan, phase,
        history_log.read_text(encoding="utf-8", errors="replace"),
    )
    _write_json(output, capture)
    manifest_path = output.with_name(output.stem + ".manifest.json")
    request = {
        "case": plan["case"], "decision_id": plan["decision_id"],
        "plan_sha256": canonical_digest(plan), "phase": phase,
        "cycle": int(spec["cycle"]),
        "native_slot": plan["focus"]["native_slot"],
        "field": plan["decision"]["field"],
        "scenario": plan.get("scenario"), "seed": plan.get("seed"),
    }
    manifest = {
        "schema": MANIFEST_SCHEMA,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "capture": {"name": output.name, **file_identity(output)},
        "oracle": {
            "executable": {"path": str(executable), **file_identity(executable)},
            "run_manifest": {
                "path": str(oracle_run_manifest),
                **file_identity(oracle_run_manifest),
                "scenario": validation.get("scenario"),
                "seed": validation.get("initialization_seed"),
                "cycles": validation.get("cycles"),
                "branch_pause_cycle": runtime.get("branch_witness_pause_cycle"),
            },
        },
        "harness": {
            "tracer": {"path": str(tracer), **file_identity(tracer)},
            "capture_importer": {
                "path": str(Path(__file__).resolve()),
                **file_identity(Path(__file__).resolve()),
            },
        },
        "backend": {
            "name": "gdb-bts-decision-visit", "branch_history": True,
            "decision_visit": True, "raw_history": {
                "path": str(history_log), **file_identity(history_log),
            }, "gdb_version": gdb_version,
            "predicate_probe": isinstance(plan.get("predicate_probe"), dict),
        },
        "runtime": {"network_disabled": True}, "request": request,
    }
    _write_json(manifest_path, manifest)
    return output, manifest_path


def _manifest_path(path: Path) -> Path:
    return path.with_name(path.stem + ".manifest.json")


def load_verified_capture(path: Path, plan: dict[str, Any]) \
        -> tuple[dict[str, Any], dict[str, Any]]:
    path = path.expanduser().resolve()
    manifest_path = _manifest_path(path)
    if not path.is_file() or not manifest_path.is_file():
        raise ValueError(f"decision capture or manifest is missing: {path}")
    capture, manifest = _json(path), _json(manifest_path)
    validate_capture(capture)
    record = manifest.get("capture", {})
    request = manifest.get("request", {})
    backend = manifest.get("backend", {})
    importer = manifest.get("harness", {}).get("capture_importer", {})
    if manifest.get("schema") != MANIFEST_SCHEMA \
            or record.get("name") != path.name \
            or file_identity(path) != {
                "bytes": record.get("bytes"), "sha256": record.get("sha256"),
            }:
        raise ValueError("decision capture identity differs from its manifest")
    if manifest.get("oracle", {}).get("executable", {}).get("sha256") \
            != BNE_202_SHA256 \
            or manifest.get("runtime", {}).get("network_disabled") is not True \
            or backend.get("branch_history") is not True \
            or backend.get("decision_visit") is not True:
        raise ValueError("decision capture lacks pinned offline decision evidence")
    if not isinstance(importer.get("bytes"), int) \
            or not isinstance(importer.get("sha256"), str) \
            or len(importer["sha256"]) != 64:
        raise ValueError("decision capture lacks importer implementation identity")
    expected = {
        "case": plan["case"], "decision_id": plan["decision_id"],
        "plan_sha256": canonical_digest(plan),
        "native_slot": plan["focus"]["native_slot"],
        "field": plan["decision"]["field"],
        "scenario": plan.get("scenario"), "seed": plan.get("seed"),
    }
    if any(request.get(key) != value for key, value in expected.items()):
        raise ValueError("decision capture request differs from its plan")
    phase = request.get("phase")
    if phase not in plan["captures"] \
            or request.get("cycle") != plan["captures"][phase]["cycle"] \
            or capture.get("phase") != phase:
        raise ValueError("decision capture phase or cycle differs from plan")
    history = backend.get("raw_history", {})
    if not isinstance(history.get("bytes"), int) \
            or not isinstance(history.get("sha256"), str) \
            or len(history["sha256"]) != 64:
        raise ValueError("decision capture lacks raw history identity")
    evidence = {
        "path": str(path), **file_identity(path),
        "manifest": {"path": str(manifest_path), **file_identity(manifest_path)},
        "raw_history": history, "phase": phase,
    }
    return capture, evidence
