#!/usr/bin/env python3
"""Mine the predicate separating accepted and rejected native decisions.

The workflow has two authenticated passes:

1. use the accepted field write to bootstrap the containing function and the
   focus-unit register;
2. capture that complete function visit at a rejected and accepted cycle,
   contrast branch outcomes, and (when needed) emit an operand-probe plan.

No stage edits Java or treats a diagnostic predicate as an accepted fix.
"""

from __future__ import annotations

import copy
from datetime import datetime, timezone
from difflib import SequenceMatcher
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from typing import Any, Iterable
import zipfile

from bne_branch_capture import _assembly, parse_instruction_history
from bne_branch_witness import (
    BNE_202_SHA256, BNE_TEXT_END, BNE_TEXT_START, FIELD_LAYOUT,
    load_verified_capture as load_verified_writer_capture,
)
from bne_decision_capture import load_verified_capture
from bne_fixture import validate_fixture
from bne_packet import read_native_snapshots
from bne_triage import canonical_digest, file_identity, inventory_files


SCHEMA = 1
PLAN_SCHEMA = 1
# A decision activation either contains the accepted write itself, or sits
# upstream of it. Only the first can measure its own outcome; the second is
# labelled accepted or rejected by the sealed fixture cycle it belongs to.
ACTIVATION_WITH_WRITER = "writer-containing-activation"
ACTIVATION_UPSTREAM = "upstream-activation"
OUTCOME_SOURCE = {
    ACTIVATION_WITH_WRITER: "activation-field-delta",
    ACTIVATION_UPSTREAM: "fixture-cycle-outcome",
}
ACTIVATION_SCOPE_TEXT = {
    ACTIVATION_WITH_WRITER:
        "matching focus-unit function entry through its return",
    ACTIVATION_UPSTREAM:
        "matching focus-unit upstream activation entry through its return",
}
ROOT = Path(__file__).resolve().parents[3]
IMPLEMENTATION = tuple(Path(__file__).with_name(name) for name in (
    "bne_decision_miner.py", "bne_decision_capture.py", "bne_branch_capture.py",
    "bne_branch_witness.py", "bne_fixture.py", "bne_packet.py", "bne_triage.py",
))
MEMORY_DESTINATION = re.compile(
    r"(?P<disp>-?(?:0x[0-9a-f]+|\d+))?\(%(?P<base>e(?:ax|bx|cx|dx|si|di|bp|sp))"
    r"(?:,[^)]*)?\)$", re.I,
)
CONDITION_OPERATOR = {
    "e": "==", "z": "==", "ne": "!=", "nz": "!=",
    "l": "<", "nge": "<", "b": "<", "c": "<", "nae": "<",
    "le": "<=", "ng": "<=", "be": "<=", "na": "<=",
    "g": ">", "nle": ">", "a": ">", "nbe": ">",
    "ge": ">=", "nl": ">=", "ae": ">=", "nb": ">=", "nc": ">=",
}
STACK_LOAD = re.compile(
    r"^mov\s+(?P<destination>e(?:ax|bx|cx|dx|si|di|bp|sp)),\s*"
    r"(?:dword\s+)?\[(?P<base>esp|ebp)"
    r"(?:\s*(?P<sign>[+-])\s*(?P<displacement>0x[0-9a-f]+|\d+))?\]$",
    re.I,
)
STACK_ADJUST = re.compile(
    r"^(?P<operation>add|sub)\s+esp,\s*(?P<amount>0x[0-9a-f]+|\d+)$",
    re.I,
)
DIRECT_CALL = re.compile(r"^call\s+(?P<target>0x[0-9a-f]+)$", re.I)
ANY_CALL = re.compile(r"^call\s+(?P<operand>\S.*)$", re.I)


def _json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"expected JSON object: {path}")
    return value


def _write(path: Path, value: str) -> None:
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


def _write_json(path: Path, value: object) -> None:
    _write(path, json.dumps(value, indent=2, sort_keys=True) + "\n")


def _field_value(unit: dict[str, Any], field: str) -> object:
    return unit["target_pointer" if field == "target" else field]


def fixture_contrast(fixture: Path, *, native_slot: int, field: str,
        rejected_cycle: int, accepted_cycle: int,
        heldout_cycle: int | None = None,
        heldout_outcome: str | None = None) -> dict[str, Any]:
    if field not in FIELD_LAYOUT:
        raise ValueError(f"unsupported decision field: {field}")
    if rejected_cycle <= 1 or accepted_cycle <= rejected_cycle:
        raise ValueError("decision contrast requires 1 < rejected < accepted")
    if heldout_cycle is not None and heldout_outcome not in {"accepted", "rejected"}:
        raise ValueError("held-out cycle requires --heldout-outcome")
    fixture = fixture.expanduser().resolve()
    validation = validate_fixture(fixture)
    cycles = {rejected_cycle - 1, rejected_cycle,
              accepted_cycle - 1, accepted_cycle}
    if heldout_cycle is not None:
        if heldout_cycle <= 1:
            raise ValueError("held-out cycle must exceed one")
        cycles.update({heldout_cycle - 1, heldout_cycle})
    with zipfile.ZipFile(fixture) as archive:
        manifest = json.loads(archive.read("manifest.json"))
        with archive.open("state.bin") as state:
            snapshots = read_native_snapshots(
                state, cycles, {native_slot}, radius=0,
            )
    observations = {}
    specifications = [
        ("rejected", rejected_cycle, "rejected"),
        ("accepted", accepted_cycle, "accepted"),
    ]
    if heldout_cycle is not None:
        specifications.append(("heldout", heldout_cycle, heldout_outcome))
    for phase, cycle, expected in specifications:
        before_unit = snapshots[cycle - 1]["units"].get(str(native_slot))
        after_unit = snapshots[cycle]["units"].get(str(native_slot))
        if before_unit is None or after_unit is None:
            raise ValueError(f"native slot {native_slot} is absent at cycle {cycle}")
        before, after = (_field_value(before_unit, field),
                         _field_value(after_unit, field))
        changed = before != after
        if (expected == "accepted") != changed:
            raise ValueError(
                f"fixture does not show {expected} {field} outcome at cycle {cycle}"
            )
        observations[phase] = {
            "cycle": cycle, "expected_outcome": expected,
            "before": before, "after": after, "changed": changed,
        }
    return {
        "schema": SCHEMA,
        "fixture": {"path": str(fixture), **file_identity(fixture),
                    "fixture_id": validation["fixture_id"],
                    "scenario": manifest["run"]["validation"]["scenario"],
                    "seed": manifest["run"]["validation"]["initialization_seed"]},
        "native_slot": native_slot, "field": field,
        "observations": observations,
    }


def bootstrap_writer_plan(base: dict[str, Any],
        contrast: dict[str, Any]) -> dict[str, Any]:
    if base.get("case") is None or base.get("schema") != 1:
        raise ValueError("unsupported Branch Witness plan")
    if base.get("fixture_id") != contrast["fixture"]["fixture_id"] \
            or base.get("focus", {}).get("native_slot") \
            != contrast["native_slot"]:
        raise ValueError("fixture contrast differs from Branch Witness focus")
    if base.get("scenario") != contrast["fixture"]["scenario"] \
            or base.get("seed") != contrast["fixture"]["seed"]:
        raise ValueError("fixture scenario or seed differs from Branch Witness plan")
    field = contrast["field"]
    accepted = contrast["observations"]["accepted"]
    plan = copy.deepcopy(base)
    plan["divergence_cycle"] = accepted["cycle"]
    plan["capture_window"].update({
        "start_cycle": accepted["cycle"] - 1,
        "end_cycle": accepted["cycle"],
    })
    plan["focus"]["fields"] = [field]
    plan["focus"]["causal_fields"] = []
    watches = [item for item in plan["native_layout"]["watches"]
               if item["field"] == field]
    if not watches:
        layout = FIELD_LAYOUT[field]
        watches = [{
            "field": field, "offset": layout["offset"],
            "bytes": layout["bytes"], "encoding": layout["encoding"],
        }]
    watches[0].update({
        "before": accepted["before"], "oracle": accepted["after"],
        "purpose": "decision-miner-accepted-write-bootstrap",
    })
    plan["native_layout"]["watches"] = watches
    plan["role"] = "decision-miner-accepted-write-bootstrap"
    return plan


def _writer(capture: dict[str, Any], field: str,
        native_slot: int) -> dict[str, Any]:
    matches = [event for event in capture.get("events", [])
               if event.get("type") == "write"
               and event.get("field") == field
               and event.get("native_slot") == native_slot
               and event.get("before") != event.get("after")]
    if not matches:
        raise ValueError("bootstrap capture contains no accepted field writer")
    return matches[-1]


def focus_register_from_writer(writer: dict[str, Any], field: str) -> str:
    instruction = str(writer.get("instruction_text", ""))
    pieces = instruction.split(None, 1)
    operands = pieces[1].split(",") if len(pieces) == 2 else []
    offset = int(FIELD_LAYOUT[field]["offset"])
    for operand in reversed(operands):
        matched = MEMORY_DESTINATION.search(operand.strip())
        if matched is None:
            continue
        displacement = int(matched.group("disp"), 0) \
            if matched.group("disp") else 0
        if displacement == offset:
            return matched.group("base").lower()
    raise ValueError("could not derive focus register from accepted writer")


def discover_function(executable: Path, address: int, *, r2: str = "r2") -> int:
    executable = executable.expanduser().resolve()
    if file_identity(executable)["sha256"] != BNE_202_SHA256:
        raise ValueError("function discovery refuses an unpinned BNE executable")
    completed = subprocess.run([
        r2, "-2", "-q", "-N", "-e", "scr.color=0",
        "-c", f"aaa;afij @ 0x{address:08x}", str(executable),
    ], check=False, capture_output=True, text=True, timeout=120)
    if completed.returncode != 0:
        raise ValueError("radare2 function discovery failed")
    start = completed.stdout.find("[")
    try:
        functions = json.loads(completed.stdout[start:]) if start >= 0 else []
    except json.JSONDecodeError as error:
        raise ValueError("radare2 returned malformed function JSON") from error
    candidates = []
    for item in functions:
        if not isinstance(item, dict):
            continue
        start_address = item.get("offset", item.get("addr"))
        if isinstance(start_address, int) \
                and start_address <= address \
                and address < start_address + int(item.get("size", 0)):
            candidates.append(start_address)
    if not candidates:
        raise ValueError(f"no static function contains writer 0x{address:08x}")
    return max(candidates)


def entry_focus_from_instructions(instructions: list[dict[str, Any]],
        focus_register: str) -> dict[str, Any]:
    """Translate a proved prologue register load back to the entry stack."""
    focus_register = focus_register.lower()
    esp_offset = 0
    ebp_offset: int | None = None
    for instruction in instructions[:16]:
        opcode = str(instruction.get("opcode", "")).strip().lower()
        matched = STACK_LOAD.fullmatch(opcode)
        if matched and matched.group("destination") == focus_register:
            base_offset = (esp_offset if matched.group("base") == "esp"
                           else ebp_offset)
            if base_offset is None:
                break
            displacement = int(matched.group("displacement") or "0", 0)
            if matched.group("sign") == "-":
                displacement = -displacement
            entry_offset = base_offset + displacement
            if entry_offset < 4 or entry_offset > 64 \
                    or entry_offset % 4 != 0:
                break
            return {
                "kind": "entry-stack-pointer", "offset": entry_offset,
                "proved_by": {
                    "address": int(instruction["addr"]), "opcode": opcode,
                    "stack_offset_before_instruction": base_offset,
                },
            }
        if opcode.startswith("push "):
            esp_offset -= 4
        elif opcode.startswith("pop "):
            esp_offset += 4
        elif opcode == "mov ebp, esp":
            ebp_offset = esp_offset
        elif adjusted := STACK_ADJUST.fullmatch(opcode):
            amount = int(adjusted.group("amount"), 0)
            esp_offset += amount if adjusted.group("operation") == "add" else -amount
        elif opcode.startswith(("ret", "jmp ")):
            break
    raise ValueError(
        f"could not prove entry focus source for {focus_register} from prologue"
    )


def derive_entry_focus(executable: Path, entry_address: int,
        focus_register: str, *, r2: str = "r2") -> dict[str, Any]:
    executable = executable.expanduser().resolve()
    if file_identity(executable)["sha256"] != BNE_202_SHA256:
        raise ValueError("entry-focus discovery refuses an unpinned BNE executable")
    completed = subprocess.run([
        r2, "-2", "-q", "-N", "-e", "scr.color=0",
        "-c", f"pdj 16 @ 0x{entry_address:08x}", str(executable),
    ], check=False, capture_output=True, text=True, timeout=30)
    if completed.returncode != 0:
        raise ValueError("radare2 entry-focus discovery failed")
    start = completed.stdout.find("[")
    try:
        instructions = json.loads(completed.stdout[start:]) if start >= 0 else []
    except json.JSONDecodeError as error:
        raise ValueError("radare2 returned malformed prologue JSON") from error
    if not isinstance(instructions, list):
        raise ValueError("radare2 returned no prologue instructions")
    return entry_focus_from_instructions(instructions, focus_register)


def verified_bootstrap_history(capture_path: Path) \
        -> tuple[Path, dict[str, Any]]:
    capture_path = capture_path.expanduser().resolve()
    suffix = ".branch-capture.json"
    if not capture_path.name.endswith(suffix):
        raise ValueError("bootstrap capture has no standard history sibling")
    history = capture_path.with_name(
        capture_path.name[:-len(suffix)] + ".gdb-history.txt"
    )
    manifest_path = capture_path.with_name(capture_path.stem + ".manifest.json")
    manifest = _json(manifest_path)
    expected = manifest.get("backend", {}).get("raw_history", {})
    if not history.is_file() or file_identity(history) != {
            "bytes": expected.get("bytes"), "sha256": expected.get("sha256")}:
        raise ValueError("bootstrap raw history differs from its sealed identity")
    return history, {"path": str(history), **file_identity(history)}


def accepted_activation_from_history(history: Path, *, writer_address: int,
        entry_address: int) -> dict[str, Any]:
    """Prove which call reached the entry, and whether it contains the writer.

    A recorded instruction history proves the transfer directly: a `call`
    whose immediately following instruction is the entry address reached that
    entry. That holds for the order-dispatch table's indirect call as well as
    for a direct one, so an upstream shared handler can be scoped without
    decoding a runtime function-pointer operand. A direct call must still
    agree with its own immediate, otherwise the history is not trustworthy.
    """
    instructions = parse_instruction_history(
        history.read_text(encoding="utf-8", errors="replace")
    )
    writer_indices = [index for index, instruction in enumerate(instructions)
                      if int(instruction["address"]) == writer_address]
    if not writer_indices:
        raise ValueError("bootstrap history does not reach its accepted writer")
    writer_index = writer_indices[-1]
    for index in range(writer_index - 1, -1, -1):
        assembly = _assembly(instructions[index]["instruction"])
        matched = ANY_CALL.fullmatch(assembly)
        if matched is None or index + 1 > writer_index:
            continue
        if int(instructions[index + 1]["address"]) != entry_address:
            continue
        direct = DIRECT_CALL.fullmatch(assembly)
        if direct is not None and int(direct.group("target"), 16) != entry_address:
            raise ValueError(
                "bootstrap history call immediate differs from its taken entry"
            )
        return {
            "callsite": int(instructions[index]["address"]),
            "call_kind": "direct" if direct is not None else "indirect",
            "instruction": assembly,
            "proved_by": "recorded-successor-instruction",
            "entry_index": index + 1, "writer_index": writer_index,
        }
    raise ValueError("bootstrap history does not prove the accepted function caller")


def activation_contains_writer(history: Path, activation: dict[str, Any], *,
        entry_address: int, entry_return_address: int) -> bool:
    """Report whether the accepted write happens inside that same activation.

    The write that bootstraps a decision may sit downstream of the captured
    activation rather than inside it, which is exactly the shape of an
    upstream order handler that only queues a replacement. Nesting is counted
    on the entry and its proved resume address, so a re-entrant handler does
    not close the outer activation early.
    """
    instructions = parse_instruction_history(
        history.read_text(encoding="utf-8", errors="replace")
    )
    depth = 1
    for index in range(int(activation["entry_index"]) + 1, len(instructions)):
        address = int(instructions[index]["address"])
        if address == entry_address:
            depth += 1
        elif address == entry_return_address:
            depth -= 1
            if depth == 0:
                return index > int(activation["writer_index"])
    raise ValueError("bootstrap history does not contain the activation return")


def accepted_callsite_from_history(history: Path, *, writer_address: int,
        entry_address: int) -> int:
    return int(accepted_activation_from_history(
        history, writer_address=writer_address, entry_address=entry_address,
    )["callsite"])


def instruction_size(executable: Path, address: int, *, r2: str = "r2") -> int:
    completed = subprocess.run([
        r2, "-2", "-q", "-N", "-e", "scr.color=0",
        "-c", f"pdj 1 @ 0x{address:08x}", str(executable),
    ], check=False, capture_output=True, text=True, timeout=30)
    start = completed.stdout.find("[")
    try:
        instructions = json.loads(completed.stdout[start:]) if start >= 0 else []
    except json.JSONDecodeError as error:
        raise ValueError("radare2 returned malformed callsite JSON") from error
    if completed.returncode != 0 or len(instructions) != 1 \
            or not isinstance(instructions[0].get("size"), int):
        raise ValueError("could not prove accepted call instruction size")
    return int(instructions[0]["size"])


def build_decision_plan(base: dict[str, Any], contrast: dict[str, Any],
        *, entry_address: int, focus_register: str,
        entry_focus: dict[str, Any], entry_callsite: int,
        entry_return_address: int, entry_call: dict[str, Any] | None = None,
        activation_scope: str = ACTIVATION_WITH_WRITER,
        bootstrap: dict[str, Any] | None = None) -> dict[str, Any]:
    if not BNE_TEXT_START <= entry_address < BNE_TEXT_END:
        raise ValueError("decision entry lies outside pinned BNE text")
    focus_register = focus_register.lower()
    if not re.fullmatch(r"e(?:ax|bx|cx|dx|si|di|bp|sp)", focus_register):
        raise ValueError("decision focus must be a 32-bit x86 register")
    if activation_scope not in OUTCOME_SOURCE:
        raise ValueError(f"unsupported decision activation scope: {activation_scope}")
    outcome_source = OUTCOME_SOURCE[activation_scope]
    signature = {
        "case": base["case"], "fixture_id": base["fixture_id"],
        "native_slot": contrast["native_slot"], "field": contrast["field"],
        "entry_address": entry_address, "focus_register": focus_register,
        "entry_focus": entry_focus,
        "entry_callsite": entry_callsite,
        "entry_return_address": entry_return_address,
        "activation_scope": activation_scope,
        "outcome_source": outcome_source,
        "captures": contrast["observations"],
    }
    decision_id = "decision-" + canonical_digest(signature)[:20]
    plan = {
        "schema": PLAN_SCHEMA, "decision_id": decision_id,
        "case": base["case"], "fixture_id": base["fixture_id"],
        "scenario": base.get("scenario"), "seed": base.get("seed"),
        "focus": {
            "native_slot": contrast["native_slot"],
            "java_id": base.get("focus", {}).get("java_id"),
        },
        "decision": {
            "entry_address": entry_address, "focus_register": focus_register,
            "entry_focus": copy.deepcopy(entry_focus),
            "entry_callsite": entry_callsite,
            "entry_return_address": entry_return_address,
            "entry_call": copy.deepcopy(entry_call),
            "activation_scope": activation_scope,
            "outcome_source": outcome_source,
            "field": contrast["field"],
            "field_offset": FIELD_LAYOUT[contrast["field"]]["offset"],
            "field_bytes": FIELD_LAYOUT[contrast["field"]]["bytes"],
        },
        "captures": copy.deepcopy(contrast["observations"]),
        "capture_window": {
            "maximum_instructions": min(
                65536, int(base.get("capture_window", {})
                           .get("maximum_instructions", 65536))),
            "maximum_ranked_branches": 24,
            "scope": ACTIVATION_SCOPE_TEXT[activation_scope],
        },
        "native_layout": {
            "executable_sha256": BNE_202_SHA256,
            "executable_text": {"start": BNE_TEXT_START,
                                "end": BNE_TEXT_END},
        },
        "provenance": {
            "base_witness_plan_sha256": canonical_digest(base),
            "fixture": contrast["fixture"],
            "bootstrap": bootstrap,
        },
        "policy": {
            "automatic_source_changes": False,
            "production_engine_hooks": False,
            "network_disabled_capture": True,
            "heldout_required_for_semantic_proof": True,
            "acceptance_authority": "authenticated full regression gate",
        },
    }
    return plan


def plan_from_inputs(base_plan_path: Path, fixture: Path, *,
        native_slot: int, field: str, rejected_cycle: int,
        accepted_cycle: int, bootstrap_capture_path: Path | None = None,
        executable: Path | None = None, entry_address: int | None = None,
        focus_register: str | None = None, heldout_cycle: int | None = None,
        heldout_outcome: str | None = None, r2: str = "r2") \
        -> tuple[dict[str, Any] | None, dict[str, Any], dict[str, Any]]:
    base_plan_path = base_plan_path.expanduser().resolve()
    base = _json(base_plan_path)
    contrast = fixture_contrast(
        fixture, native_slot=native_slot, field=field,
        rejected_cycle=rejected_cycle, accepted_cycle=accepted_cycle,
        heldout_cycle=heldout_cycle, heldout_outcome=heldout_outcome,
    )
    bootstrap_plan = bootstrap_writer_plan(base, contrast)
    bootstrap_evidence = None
    writer = None
    explicit_entry = entry_address is not None
    if explicit_entry and focus_register is None:
        raise ValueError(
            "an explicit --entry-address also requires --focus-register, "
            "because the accepted writer only names its own function's register"
        )
    if bootstrap_capture_path is not None:
        capture, evidence = load_verified_writer_capture(
            bootstrap_capture_path, plan=bootstrap_plan,
            require_plan_match=False,
        )
        accepted_cycle = contrast["observations"]["accepted"]["cycle"]
        if capture.get("cycle") != accepted_cycle \
                or capture.get("field") != field:
            raise ValueError("bootstrap capture is not the accepted field visit")
        writer = _writer(capture, field, native_slot)
        bootstrap_evidence = {"capture": evidence, "writer": writer}
        if focus_register is None:
            focus_register = focus_register_from_writer(writer, field)
        if entry_address is None:
            if executable is None:
                raise ValueError(
                    "--native-executable or --entry-address is required after bootstrap"
                )
            entry_address = discover_function(
                executable, int(writer["instruction"]), r2=r2,
            )
    if entry_address is None or focus_register is None:
        return None, bootstrap_plan, {
            "base_plan": {"path": str(base_plan_path), **file_identity(base_plan_path)},
            "contrast": contrast, "bootstrap": bootstrap_evidence,
        }
    if executable is None:
        raise ValueError(
            "--native-executable is required to prove the entry focus source"
        )
    entry_focus = derive_entry_focus(
        executable, entry_address, focus_register, r2=r2,
    )
    if bootstrap_capture_path is None or writer is None:
        raise ValueError(
            "accepted bootstrap capture is required to prove the decision caller"
        )
    history, history_evidence = verified_bootstrap_history(bootstrap_capture_path)
    activation = accepted_activation_from_history(
        history, writer_address=int(writer["instruction"]),
        entry_address=entry_address,
    )
    entry_callsite = int(activation["callsite"])
    entry_return_address = entry_callsite + instruction_size(
        executable, entry_callsite, r2=r2,
    )
    contains_writer = activation_contains_writer(
        history, activation, entry_address=entry_address,
        entry_return_address=entry_return_address,
    )
    activation_scope = (ACTIVATION_WITH_WRITER if contains_writer
                        else ACTIVATION_UPSTREAM)
    entry_call = {
        "callsite": entry_callsite, "kind": activation["call_kind"],
        "instruction": activation["instruction"],
        "proved_by": activation["proved_by"],
        "contains_accepted_writer": contains_writer,
    }
    bootstrap_evidence["raw_history"] = history_evidence
    bootstrap_evidence["accepted_caller"] = {
        "callsite": entry_callsite, "return_address": entry_return_address,
        "kind": activation["call_kind"],
        "contains_accepted_writer": contains_writer,
    }
    decision = build_decision_plan(
        base, contrast, entry_address=entry_address,
        focus_register=focus_register, entry_focus=entry_focus,
        entry_callsite=entry_callsite,
        entry_return_address=entry_return_address,
        entry_call=entry_call, activation_scope=activation_scope,
        bootstrap=bootstrap_evidence,
    )
    return decision, bootstrap_plan, {
        "base_plan": {"path": str(base_plan_path), **file_identity(base_plan_path)},
        "contrast": contrast, "bootstrap": bootstrap_evidence,
    }


def _branch_events(capture: dict[str, Any]) -> list[dict[str, Any]]:
    return [event for event in capture.get("events", [])
            if event.get("type") == "branch"]


def _occurrences(capture: dict[str, Any]) -> dict[int, list[dict[str, Any]]]:
    grouped: dict[int, list[dict[str, Any]]] = {}
    for event in _branch_events(capture):
        grouped.setdefault(int(event["address"]), []).append(event)
    return grouped


def _first_sequence_divergence(rejected: list[dict[str, Any]],
        accepted: list[dict[str, Any]]) -> dict[str, Any]:
    left = [(event["address"], event["taken"]) for event in rejected]
    right = [(event["address"], event["taken"]) for event in accepted]
    for index, (lvalue, rvalue) in enumerate(zip(left, right)):
        if lvalue != rvalue:
            return {"rejected_index": index, "accepted_index": index,
                    "rejected": lvalue, "accepted": rvalue}
    index = min(len(left), len(right))
    return {"rejected_index": index, "accepted_index": index,
            "rejected": left[index] if index < len(left) else None,
            "accepted": right[index] if index < len(right) else None}


def rank_branch_contrasts(rejected: dict[str, Any],
        accepted: dict[str, Any], heldout: dict[str, Any] | None = None,
        *, limit: int = 24) -> list[dict[str, Any]]:
    rejected_events, accepted_events = (_branch_events(rejected),
                                         _branch_events(accepted))
    rejected_by, accepted_by = (_occurrences(rejected), _occurrences(accepted))
    heldout_by = _occurrences(heldout) if heldout is not None else {}
    first = _first_sequence_divergence(rejected_events, accepted_events)
    matcher = SequenceMatcher(
        a=[item["address"] for item in rejected_events],
        b=[item["address"] for item in accepted_events], autojunk=False,
    )
    aligned_addresses = {
        rejected_events[i + offset]["address"]
        for i, j, size in matcher.get_matching_blocks()
        for offset in range(size)
        if rejected_events[i + offset]["address"]
           == accepted_events[j + offset]["address"]
    }
    results = []
    for address in sorted(set(rejected_by) & set(accepted_by)):
        left, right = rejected_by[address], accepted_by[address]
        for ordinal, (rejected_event, accepted_event) \
                in enumerate(zip(left, right), 1):
            if rejected_event["taken"] == accepted_event["taken"]:
                continue
            probe = accepted_event.get("predicate_probe_plan") \
                or rejected_event.get("predicate_probe_plan")
            unique = len(left) == len(right) == 1
            relative = min(
                rejected_event["seq"] / max(1, len(rejected_events)),
                accepted_event["seq"] / max(1, len(accepted_events)),
            )
            heldout_event = None
            if heldout_by.get(address):
                candidates = heldout_by[address]
                heldout_event = candidates[min(ordinal - 1, len(candidates) - 1)]
            expected = (heldout or {}).get("decision", {}).get("expected_outcome")
            heldout_consistent = None
            if heldout_event is not None and expected in {"accepted", "rejected"}:
                expected_taken = (accepted_event["taken"]
                                  if expected == "accepted"
                                  else rejected_event["taken"])
                heldout_consistent = heldout_event["taken"] == expected_taken
            breakdown = {
                "outcome_flip": 10.0,
                "automatic_operand_probe": 4.0 if probe else 0.0,
                "single_occurrence": 2.5 if unique else 0.0,
                "sequence_aligned": 2.0 if address in aligned_addresses else 0.0,
                "early_causal_split": round(2.0 * (1.0 - relative), 4),
                "heldout_outcome": 4.0 if heldout_consistent is True else
                    -4.0 if heldout_consistent is False else 0.0,
                "same_condition": 1.0 if rejected_event.get("condition")
                    == accepted_event.get("condition") else 0.0,
            }
            results.append({
                "address": address, "ordinal": ordinal,
                "condition": accepted_event.get("condition"),
                "rejected_taken": rejected_event["taken"],
                "accepted_taken": accepted_event["taken"],
                "rejected_occurrences": len(left),
                "accepted_occurrences": len(right),
                "heldout_taken": heldout_event.get("taken")
                    if heldout_event else None,
                "heldout_consistent": heldout_consistent,
                "predicate_probe_plan": probe,
                "rejected_operands": rejected_event.get("operands"),
                "accepted_operands": accepted_event.get("operands"),
                "heldout_operands": heldout_event.get("operands")
                    if heldout_event else None,
                "score_breakdown": breakdown,
                "score": round(sum(breakdown.values()), 4),
            })
    results.sort(key=lambda item: (-item["score"], item["address"],
                                   item["ordinal"]))
    return results[:limit]


def _as_signed(value: int, width: int) -> int:
    mask = (1 << width) - 1
    value &= mask
    sign = 1 << (width - 1)
    return value - (1 << width) if value & sign else value


def _condition_result(condition: str, lhs: int, rhs: int,
        width: int, operation: str) -> bool | None:
    mask = (1 << width) - 1
    unsigned_lhs, unsigned_rhs = lhs & mask, rhs & mask
    if operation in {"test", "and", "or"}:
        result = ((unsigned_lhs & unsigned_rhs) if operation in {"test", "and"}
                  else (unsigned_lhs | unsigned_rhs)) & mask
        if condition in {"e", "z"}:
            return result == 0
        if condition in {"ne", "nz"}:
            return result != 0
        return None
    if condition in {"e", "z"}:
        return unsigned_lhs == unsigned_rhs
    if condition in {"ne", "nz"}:
        return unsigned_lhs != unsigned_rhs
    if condition in {"b", "c", "nae"}:
        return unsigned_lhs < unsigned_rhs
    if condition in {"be", "na"}:
        return unsigned_lhs <= unsigned_rhs
    if condition in {"a", "nbe"}:
        return unsigned_lhs > unsigned_rhs
    if condition in {"ae", "nb", "nc"}:
        return unsigned_lhs >= unsigned_rhs
    signed_lhs, signed_rhs = (_as_signed(lhs, width), _as_signed(rhs, width))
    if condition in {"l", "nge"}:
        return signed_lhs < signed_rhs
    if condition in {"le", "ng"}:
        return signed_lhs <= signed_rhs
    if condition in {"g", "nle"}:
        return signed_lhs > signed_rhs
    if condition in {"ge", "nl"}:
        return signed_lhs >= signed_rhs
    return None


def _operand_sources(ast: dict[str, Any]) -> list[str]:
    if ast.get("op") in {"symbol", "unknown"}:
        return [str(ast.get("value"))]
    result = []
    for child in ast.get("args", []):
        if isinstance(child, dict):
            result.extend(_operand_sources(child))
    return result


def _predicate_semantics(candidate: dict[str, Any],
        captures: dict[str, dict[str, Any]]) -> dict[str, Any] | None:
    probe = candidate.get("predicate_probe_plan")
    if not isinstance(probe, dict):
        return None
    observations = []
    for phase in ("accepted", "rejected", "heldout"):
        capture = captures.get(phase)
        if capture is None:
            continue
        events = [event for event in _branch_events(capture)
                  if event["address"] == candidate["address"]]
        if not events:
            continue
        event = events[min(candidate["ordinal"] - 1, len(events) - 1)]
        operands = event.get("operands")
        if not isinstance(operands, dict):
            continue
        lhs, rhs = operands["lhs"], operands["rhs"]
        predicted = _condition_result(
            str(probe["condition"]), int(lhs["value"]), int(rhs["value"]),
            int(probe["width"]), str(probe["flag_operation"]),
        )
        observations.append({
            "phase": phase, "cycle": capture["cycle"],
            "lhs": int(lhs["value"]), "rhs": int(rhs["value"]),
            "taken": event["taken"], "predicted_taken": predicted,
            "prediction_correct": predicted == event["taken"],
            "focus_identity_proven": event.get("predicate_probe", {})
                .get("focus_identity", {}).get("proved") is True,
            "expected_outcome": capture["decision"]["expected_outcome"],
        })
    if len(observations) < 2:
        return None
    operation, condition = (probe["flag_operation"], probe["condition"])
    lhs_name, rhs_name = probe["lhs"]["name"], probe["rhs"]["name"]
    operator = CONDITION_OPERATOR.get(str(condition))
    if operation in {"test", "and", "or"} and condition in {"e", "z", "ne", "nz"}:
        operator = "==" if condition in {"e", "z"} else "!="
        combine = "and" if operation in {"test", "and"} else "or"
        lhs_ast = {"op": combine, "args": [probe["lhs"]["semantic_ast"],
                                            probe["rhs"]["semantic_ast"]]}
        rhs_ast = {"op": "const", "value": 0}
        semantic = f"{combine}({lhs_name},{rhs_name}) {operator} 0"
    else:
        lhs_ast, rhs_ast = (probe["lhs"]["semantic_ast"],
                            probe["rhs"]["semantic_ast"])
        semantic = f"{lhs_name} {operator or condition} {rhs_name}"
    heldout = next((item for item in observations if item["phase"] == "heldout"),
                   None)
    focus_proven = all(item["focus_identity_proven"] for item in observations)
    prediction_proven = all(item["prediction_correct"] for item in observations)
    outcome_flip = len({item["taken"] for item in observations
                        if item["phase"] in {"accepted", "rejected"}}) == 2
    outcome_directions = {
        item["phase"]: item["taken"] for item in observations
        if item["phase"] in {"accepted", "rejected"}
    }
    outcome_classification = all(
        item["taken"] == outcome_directions.get(item["expected_outcome"])
        for item in observations
    )
    direct_named = any(source.startswith("unit[") for source in (
        _operand_sources(lhs_ast) + _operand_sources(rhs_ast)
    ))
    return {
        "machine": f"{lhs_name} {condition} {rhs_name}",
        "semantic": semantic, "operator": operator,
        "lhs_ast": lhs_ast, "rhs_ast": rhs_ast,
        "observations": observations,
        "proof": {
            "focus_identity_proven": focus_proven,
            "prediction_recovered": prediction_proven,
            "accepted_rejected_outcomes_flip": outcome_flip,
            "outcome_classification_correct": outcome_classification,
            "heldout_prediction_passed": heldout is not None
                and heldout["prediction_correct"],
            "direct_unit_source_named": direct_named,
            "passed": focus_proven and prediction_proven and outcome_flip
                and outcome_classification
                and heldout is not None and heldout["prediction_correct"]
                and direct_named and operator is not None,
        },
    }


def _boundary(operator: str | None, lhs: int, rhs: int) -> dict[str, Any]:
    if operator == ">":
        return {"flip_to_not_taken": {"lhs": rhs, "rhs": rhs,
                                      "prediction": False},
                "flip_to_taken": {"lhs": rhs + 1, "rhs": rhs,
                                   "prediction": True}}
    if operator == ">=":
        return {"flip_to_not_taken": {"lhs": rhs - 1, "rhs": rhs,
                                      "prediction": False},
                "flip_to_taken": {"lhs": rhs, "rhs": rhs,
                                   "prediction": True}}
    if operator == "<":
        return {"flip_to_not_taken": {"lhs": rhs, "rhs": rhs,
                                      "prediction": False},
                "flip_to_taken": {"lhs": rhs - 1, "rhs": rhs,
                                   "prediction": True}}
    if operator == "<=":
        return {"flip_to_not_taken": {"lhs": rhs + 1, "rhs": rhs,
                                      "prediction": False},
                "flip_to_taken": {"lhs": rhs, "rhs": rhs,
                                   "prediction": True}}
    if operator == "==":
        return {"flip_to_not_taken": {"lhs": rhs + 1, "rhs": rhs,
                                      "prediction": False},
                "flip_to_taken": {"lhs": rhs, "rhs": rhs,
                                   "prediction": True}}
    if operator == "!=":
        return {"flip_to_not_taken": {"lhs": rhs, "rhs": rhs,
                                      "prediction": False},
                "flip_to_taken": {"lhs": rhs + 1, "rhs": rhs,
                                   "prediction": True}}
    return {"supported": False, "reason": "unsupported boundary operator"}


def semantic_handoff(plan: dict[str, Any], candidate: dict[str, Any],
        predicate: dict[str, Any]) -> dict[str, Any] | None:
    if not predicate["proof"]["passed"]:
        return None
    anchor = next(item for item in predicate["observations"]
                  if item["phase"] == "accepted")
    held_out = [item for item in predicate["observations"]
                if item["phase"] != "accepted"]
    return {
        "schema": 2, "case": plan["case"],
        "created_at": datetime.now(timezone.utc).isoformat(),
        "anchor": {
            "cycle": anchor["cycle"],
            "branch": {"address": candidate["address"],
                       "operator": predicate["operator"],
                       "condition": candidate["condition"],
                       "compare": candidate["predicate_probe_plan"]
                           ["flag_instruction"]},
            "observed": {"lhs": anchor["lhs"], "rhs": anchor["rhs"],
                         "taken": anchor["taken"],
                         "predicted_taken": anchor["predicted_taken"]},
            "predicate": {
                "machine": predicate["machine"],
                "semantic": predicate["semantic"],
                "lhs": {"ast": predicate["lhs_ast"],
                        "sources": _operand_sources(predicate["lhs_ast"])},
                "rhs": {"ast": predicate["rhs_ast"],
                        "sources": _operand_sources(predicate["rhs_ast"])},
            },
            "focus_identity": {"proved": True,
                               "register": plan["decision"]["focus_register"]},
        },
        "held_out": [{
            "cycle": item["cycle"], "same_formula": True,
            "semantic_expression": predicate["semantic"],
            "observed": {"lhs": item["lhs"], "rhs": item["rhs"],
                         "taken": item["taken"],
                         "predicted_taken": item["predicted_taken"]},
            "prediction_correct": item["prediction_correct"],
            "focus_identity_proven": item["focus_identity_proven"],
            "passed": item["prediction_correct"]
                and item["focus_identity_proven"],
        } for item in held_out],
        "boundary_experiment": _boundary(
            predicate["operator"], anchor["lhs"], anchor["rhs"],
        ),
        "confidence": {
            "grade": "high-partial", "direct_decision_visit": True,
            "does_not_guess_unknown_tables": True,
            "held_out_validation_passed": True,
            "focus_identity_proven": True,
        },
        "proof": {
            "offline": True, "engine_source_changed": False,
            "oracle_source_changed": False,
            "predicate_recovery_passed": True,
            "focus_identity_proven": True, "passed": True,
        },
    }


def predicate_role(plan: dict[str, Any],
        predicate: dict[str, Any] | None) -> str:
    if predicate is None:
        return "unresolved"
    sources = (_operand_sources(predicate["lhs_ast"])
               + _operand_sources(predicate["rhs_ast"]))
    if plan["decision"]["field"] == "order" \
            and "unit[*].next_order" in sources:
        return "order-promotion-boundary"
    return "decision-predicate"


def selected_capture_temporal_scope(
        captures: dict[str, dict[str, Any]]) -> dict[str, Any]:
    """State exactly what selected decision visits can prove about time.

    Decision phases are chosen because their outcomes contrast. They are not
    an exhaustive entry log, so equal spacing between them cannot establish a
    scheduler period or that the function ran on unobserved cycles.
    """
    observations = sorted(
        ({"phase": phase, "cycle": int(capture["cycle"])}
         for phase, capture in captures.items()),
        key=lambda item: (item["cycle"], item["phase"]),
    )
    cycles = [item["cycle"] for item in observations]
    unique = sorted(set(cycles))
    gaps = [right - left for left, right in zip(unique, unique[1:])]
    contiguous = bool(unique) and unique == list(range(unique[0], unique[-1] + 1))
    return {
        "sampling": "explicit-selected-visits",
        "observations": observations,
        "cycles": unique, "gaps": gaps,
        "contiguous_cycles_captured": contiguous,
        "cadence_claim_supported": False,
        "unsupported_claims": [
            "function executes every cycle",
            "equal sample gaps are the execution period",
            "function did not execute on unobserved cycles",
        ],
        "reason": (
            "contrastive phases are selected outcome samples, not an "
            "exhaustive entry census; use a contiguous entry trace or cadence "
            "profiler before making frequency claims"
        ),
    }


def analyze_decisions(plan: dict[str, Any],
        captures: dict[str, dict[str, Any]]) -> dict[str, Any]:
    if set(captures) < {"rejected", "accepted"}:
        raise ValueError("decision mining requires rejected and accepted captures")
    rejected, accepted = captures["rejected"], captures["accepted"]
    expected_source = plan["decision"].get(
        "outcome_source", OUTCOME_SOURCE[ACTIVATION_WITH_WRITER],
    )
    for phase, capture in captures.items():
        observed = capture["decision"].get(
            "outcome_source", OUTCOME_SOURCE[ACTIVATION_WITH_WRITER],
        )
        if observed != expected_source:
            raise ValueError(
                f"{phase} decision capture was outcome-sourced as {observed}"
            )
    if expected_source == OUTCOME_SOURCE[ACTIVATION_WITH_WRITER]:
        if rejected["decision"]["changed"] \
                or not accepted["decision"]["changed"]:
            raise ValueError(
                "decision captures do not prove refused/accepted outcomes"
            )
        heldout = captures.get("heldout")
        if heldout is not None:
            expected_change = heldout["decision"]["expected_outcome"] == "accepted"
            if heldout["decision"]["changed"] != expected_change:
                raise ValueError("held-out decision outcome differs from its plan")
    else:
        # An upstream activation returns before the write it causes, so the
        # sealed fixture cycle, not the activation's own delta, says whether
        # this visit accepted. Every phase still has to be the planned one.
        for phase, capture in captures.items():
            if capture["decision"]["expected_outcome"] \
                    != plan["captures"][phase]["expected_outcome"]:
                raise ValueError(
                    f"{phase} decision capture is not its planned outcome"
                )
        if rejected["decision"]["expected_outcome"] != "rejected" \
                or accepted["decision"]["expected_outcome"] != "accepted":
            raise ValueError(
                "decision captures do not carry refused/accepted fixture outcomes"
            )
        heldout = captures.get("heldout")
    ranked = rank_branch_contrasts(rejected, accepted, heldout)
    top = ranked[0] if ranked else None
    predicate = _predicate_semantics(top, captures) if top else None
    semantic = semantic_handoff(plan, top, predicate) \
        if top and predicate else None
    role = predicate_role(plan, predicate)
    probe_plan = None
    if top and predicate is None and top.get("predicate_probe_plan"):
        probe_plan = copy.deepcopy(plan)
        probe_plan["predicate_probe"] = top["predicate_probe_plan"]
        probe_plan["parent_plan_sha256"] = canonical_digest(plan)
    return {
        "schema": SCHEMA, "case": plan["case"],
        "decision_id": plan["decision_id"],
        "created_at": datetime.now(timezone.utc).isoformat(),
        "focus": plan["focus"], "decision": plan["decision"],
        "outcomes": {
            phase: capture["decision"] for phase, capture in captures.items()
        },
        "first_sequence_divergence": _first_sequence_divergence(
            _branch_events(rejected), _branch_events(accepted),
        ),
        "ranked_branches": ranked, "top_branch": top,
        "predicate": predicate, "predicate_role": role,
        "temporal_scope": selected_capture_temporal_scope(captures),
        "predicate_probe_plan": probe_plan,
        "semantic_bridge_handoff": semantic,
        "proof": {
            "refused_visit_captured": True,
            "accepted_visit_captured": True,
            "branch_outcome_flip_located": top is not None,
            "operand_probe_ready": probe_plan is not None,
            "predicate_recovered": predicate is not None,
            "heldout_prediction_passed": bool(predicate and predicate["proof"]
                                              ["heldout_prediction_passed"]),
            "semantic_handoff_ready": semantic is not None,
            "source_changed": False,
        },
        "policy": plan["policy"],
    }


def _plan_summary(plan: dict[str, Any] | None,
        bootstrap: dict[str, Any], run_root: Path) -> str:
    if plan is None:
        return "\n".join([
            "# Contrastive native decision miner", "",
            "- Stage: **accepted-write bootstrap required**",
            f"- Case: `{bootstrap['case']}`",
            f"- Accepted cycle: **{bootstrap['divergence_cycle']}**",
            f"- Native slot: **{bootstrap['focus']['native_slot']}**",
            f"- Field: `{bootstrap['focus']['fields'][0]}`", "",
            "Capture `bootstrap-branch-witness-plan.json` with the existing "
            "Branch Witness runner, then repeat `decision-plan` with "
            "`--bootstrap-capture` and the pinned executable. The rejected "
            "cycle is deliberately not watched for a write.", "",
            f"Durable plan directory: `{run_root}`", "",
        ])
    captures = ", ".join(
        f"{name}={spec['cycle']}" for name, spec in plan["captures"].items()
    )
    return "\n".join([
        "# Contrastive native decision miner", "",
        "- Stage: **decision visits ready to capture**",
        f"- Case: `{plan['case']}`",
        f"- Decision: `{plan['decision_id']}`",
        f"- Entry: `0x{plan['decision']['entry_address']:08x}`",
        f"- Focus: `{plan['decision']['focus_register']}` = slot "
        f"**{plan['focus']['native_slot']}**",
        f"- Captures: {captures}", "",
        "Run the rejected and accepted focus-scoped decision captures. "
        "A held-out capture is required before exporting a proved semantic "
        "bridge handoff.", "",
        f"Durable plan directory: `{run_root}`", "",
    ])


def run_plan(base_plan: Path, fixture: Path, artifact_root: Path, **kwargs: Any) \
        -> tuple[int, Path]:
    decision, bootstrap, evidence = plan_from_inputs(
        base_plan, fixture, **kwargs,
    )
    request = {
        "schema": SCHEMA,
        "implementation": {path.name: file_identity(path)
                           for path in IMPLEMENTATION},
        "evidence": evidence,
        "arguments": {
            key: (str(value.expanduser().resolve()) if isinstance(value, Path)
                  else value) for key, value in kwargs.items()
        },
    }
    request_sha256 = canonical_digest(request)
    artifact_root = artifact_root.expanduser().resolve()
    run_root = artifact_root / "plans" / request_sha256
    manifest_path = run_root / "manifest.json"
    if manifest_path.is_file():
        manifest = _json(manifest_path)
        if manifest.get("request_sha256") != request_sha256 \
                or canonical_digest(manifest.get("request")) != request_sha256:
            raise ValueError("cached decision plan identity changed")
        for relative, identity in manifest["artifacts"].items():
            if file_identity(run_root / relative) != identity:
                raise ValueError(f"decision plan artifact changed: {relative}")
        _write_json(artifact_root / "latest-plan.json", manifest["pointer"])
        return manifest["exit_code"], run_root
    run_root.mkdir(parents=True, exist_ok=True)
    bootstrap_path = run_root / "bootstrap-branch-witness-plan.json"
    _write_json(bootstrap_path, bootstrap)
    paths = [bootstrap_path]
    if decision is not None:
        plan_path = run_root / "decision-plan.json"
        _write_json(plan_path, decision)
        paths.append(plan_path)
    summary_path = run_root / "NEXT.md"
    _write(summary_path, _plan_summary(decision, bootstrap, run_root))
    paths.append(summary_path)
    exit_code = 0 if decision is not None else 2
    pointer = {
        "schema": SCHEMA, "request_sha256": request_sha256,
        "run": str(run_root.relative_to(artifact_root)),
        "stage": "decision-captures-ready" if decision else "bootstrap-required",
        "case": bootstrap["case"], "exit_code": exit_code,
    }
    manifest = {
        "schema": SCHEMA, "created_at": datetime.now(timezone.utc).isoformat(),
        "request_sha256": request_sha256, "request": request,
        "exit_code": exit_code, "pointer": pointer,
        "artifacts": inventory_files(run_root, paths),
    }
    _write_json(manifest_path, manifest)
    _write_json(artifact_root / "latest-plan.json", pointer)
    return exit_code, run_root


def _analysis_summary(result: dict[str, Any], run_root: Path) -> str:
    branch = result.get("top_branch") or {}
    predicate = result.get("predicate") or {}
    proof = result["proof"]
    lines = [
        "# Contrastive native decision result", "",
        f"- Case: `{result['case']}`",
        f"- Decision: `{result['decision_id']}`",
        f"- Top flipped branch: " + (
            f"`0x{branch['address']:08x}` (score {branch['score']})"
            if branch else "not located"),
        f"- Native predicate: `{predicate.get('semantic', 'operand pass required')}`",
        f"- Predicate role: `{result['predicate_role']}`",
        f"- Held-out prediction passed: "
        f"`{proof['heldout_prediction_passed']}`",
        f"- Semantic bridge handoff ready: "
        f"`{proof['semantic_handoff_ready']}`", "",
    ]
    if proof["operand_probe_ready"]:
        lines.extend([
            "## Automatic operand pass", "",
            "Repeat all decision captures with `predicate-probe-plan.json`. "
            "The selected probe supports register, immediate, focus-relative "
            "memory, static memory, and bounded test/sub/and/or flag producers.", "",
        ])
    if proof["semantic_handoff_ready"]:
        lines.extend([
            "## Cross-engine handoff", "",
            "`semantic-slice.json` is a proved schema-2 input for "
            "`bne_java.py semantic-bridge`. It remains diagnostic and cannot "
            "authorize a source edit.", "",
        ])
    temporal = result["temporal_scope"]
    lines.extend([
        "## Temporal evidence boundary", "",
        f"Captured fixture cycles: `{temporal['cycles']}`. These are explicit "
        "contrast samples, not an exhaustive entry log. They prove branch and "
        "predicate differences at those visits, but **cannot establish how "
        "often the function runs**. Use a contiguous entry trace or `cadence` "
        "before making a frequency claim.", "",
    ])
    if result["predicate_role"] == "order-promotion-boundary":
        lines.extend([
            "## Upstream decision still required", "",
            "This predicate proves when native promotes `next_order` into "
            "`order`; it does not yet explain which earlier branch queued the "
            "replacement. Contrast the upstream order-handler activation that "
            "produces `next_order` before changing Java.", "",
        ])
    lines.append(f"Durable result: `{run_root}`")
    lines.append("")
    return "\n".join(lines)


def run_miner(plan_path: Path, capture_paths: Iterable[Path],
        artifact_root: Path) -> tuple[int, Path]:
    plan_path = plan_path.expanduser().resolve()
    plan = _json(plan_path)
    captures, evidence = {}, []
    for path in capture_paths:
        capture, record = load_verified_capture(path, plan)
        phase = capture["phase"]
        if phase in captures:
            raise ValueError(f"duplicate decision capture phase: {phase}")
        captures[phase] = capture
        evidence.append(record)
    request = {
        "schema": SCHEMA,
        "implementation": {path.name: file_identity(path)
                           for path in IMPLEMENTATION},
        "plan": {"path": str(plan_path), **file_identity(plan_path)},
        "captures": evidence,
    }
    request_sha256 = canonical_digest(request)
    artifact_root = artifact_root.expanduser().resolve()
    run_root = artifact_root / "runs" / request_sha256
    manifest_path = run_root / "manifest.json"
    if manifest_path.is_file():
        manifest = _json(manifest_path)
        if manifest.get("request_sha256") != request_sha256 \
                or canonical_digest(manifest.get("request")) != request_sha256:
            raise ValueError("cached decision result identity changed")
        for relative, identity in manifest["artifacts"].items():
            if file_identity(run_root / relative) != identity:
                raise ValueError(f"decision result artifact changed: {relative}")
        _write_json(artifact_root / "latest.json", manifest["pointer"])
        return manifest["exit_code"], run_root
    result = analyze_decisions(plan, captures)
    run_root.mkdir(parents=True, exist_ok=True)
    result_path = run_root / "decision-miner.json"
    _write_json(result_path, result)
    paths = [result_path]
    if result["predicate_probe_plan"] is not None:
        probe_path = run_root / "predicate-probe-plan.json"
        _write_json(probe_path, result["predicate_probe_plan"])
        paths.append(probe_path)
    if result["semantic_bridge_handoff"] is not None:
        semantic_path = run_root / "semantic-slice.json"
        _write_json(semantic_path, result["semantic_bridge_handoff"])
        paths.append(semantic_path)
    summary_path = run_root / "NEXT.md"
    _write(summary_path, _analysis_summary(result, run_root))
    paths.append(summary_path)
    exit_code = 0 if result["proof"]["branch_outcome_flip_located"] else 1
    stage = ("semantic-handoff-ready" if result["proof"]["semantic_handoff_ready"]
             else "operand-pass-ready" if result["proof"]["operand_probe_ready"]
             else "predicate-recovered" if result["proof"]["predicate_recovered"]
             else "no-contrast")
    pointer = {
        "schema": SCHEMA, "request_sha256": request_sha256,
        "run": str(run_root.relative_to(artifact_root)),
        "case": result["case"], "decision_id": result["decision_id"],
        "stage": stage, "exit_code": exit_code,
        "top_branch": result["top_branch"]["address"]
            if result["top_branch"] else None,
    }
    manifest = {
        "schema": SCHEMA, "created_at": datetime.now(timezone.utc).isoformat(),
        "request_sha256": request_sha256, "request": request,
        "exit_code": exit_code, "pointer": pointer,
        "artifacts": inventory_files(run_root, paths),
    }
    _write_json(manifest_path, manifest)
    _write_json(artifact_root / "latest.json", pointer)
    _write_json(artifact_root / f"latest-{result['case']}.json", pointer)
    return exit_code, run_root
