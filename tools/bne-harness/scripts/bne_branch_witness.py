#!/usr/bin/env python3
"""Turn a native state mismatch into an authenticated writer/branch witness.

The parity lab already says *what* differs.  This module defines the narrow
native-capture contract that says which byte to watch, authenticates a capture,
locates the instruction that wrote the byte, and ranks the branches that led
to that write.  It is an investigative aid: it never edits engine source and
never acts as an acceptance gate.
"""

from __future__ import annotations

from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import tempfile
from typing import Any, Iterable

from bne_function_lab import BNE_202_SHA256, KIND_ADDRESSES
from bne_triage import (
    canonical_digest, file_identity, inventory_files, verify_manifest,
)


SCHEMA = 1
CAPTURE_SCHEMA = 1
CAPTURE_MANIFEST_SCHEMA = 1
ROOT = Path(__file__).resolve().parents[3]
IMPLEMENTATION = (Path(__file__),)

UNIT_POOL_POINTER = 0x004AEC94
UNIT_BYTES = 152
BNE_TEXT_START = 0x00401000
BNE_TEXT_END = 0x0048F34E
FIELD_LAYOUT: dict[str, dict[str, Any]] = {
    "animation_timer": {"offset": 7, "bytes": 1, "encoding": "unsigned"},
    "x": {"offset": 24, "bytes": 2, "encoding": "unsigned-le"},
    "y": {"offset": 26, "bytes": 2, "encoding": "unsigned-le"},
    "hp": {"offset": 34, "bytes": 2, "encoding": "unsigned-le"},
    "owner": {"offset": 44, "bytes": 1, "encoding": "unsigned"},
    "order": {"offset": 46, "bytes": 1, "encoding": "unsigned"},
    "next_order": {"offset": 47, "bytes": 1, "encoding": "unsigned"},
    "route": {"offset": 48, "bytes": 1, "encoding": "unsigned"},
    "movement_path": {"offset": 49, "bytes": 1, "encoding": "unsigned"},
    "route_index": {"offset": 126, "bytes": 1, "encoding": "unsigned"},
    "order_x": {"offset": 132, "bytes": 2, "encoding": "unsigned-le"},
    "order_y": {"offset": 134, "bytes": 2, "encoding": "unsigned-le"},
    "target": {"offset": 136, "bytes": 4, "encoding": "pointer-le"},
}

SOURCE_HINTS = {
    "movement.step": {
        "files": ("World.java", "unit/Unit.java", "pathfinder/BattleNetPathFinder.java"),
        "tokens": ("waitCycles", "path", "route", "move", "setTile"),
    },
    "order.transition": {
        "files": ("World.java", "unit/Unit.java"),
        "tokens": ("order", "nextOrder", "setOrder", "finish"),
    },
    "build.hp": {
        "files": ("World.java", "unit/Unit.java"),
        "tokens": ("hitPoints", "hp", "construction", "build"),
    },
    "player.bank": {
        "files": ("Player.java", "World.java", "ai/AiPlayer.java"),
        "tokens": ("gold", "wood", "oil", "resource"),
    },
    "state.observation": {
        "files": ("World.java", "unit/Unit.java", "Player.java"),
        "tokens": ("update", "tick", "cycle"),
    },
}


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


def _kind_for_finding(finding: dict[str, Any]) -> str:
    kind, field = finding.get("kind"), finding.get("field")
    if kind == "sync_rng":
        return "rng.sync.draw"
    if kind == "unit" and field in ("x", "y"):
        return "movement.step"
    if kind == "unit" and field == "hp":
        return "build.hp"
    if kind == "unit" and field == "order":
        return "order.transition"
    if kind == "player_bank":
        return "player.bank"
    return "state.observation"


def _primary_focus(packet: dict[str, Any]) -> dict[str, Any]:
    cycle = int(packet["divergence"]["cycle"])
    focus = packet.get("semantic", {}).get(str(cycle), {}).get("focus", [])
    if not focus:
        raise ValueError("branch witness requires a paired focused unit")
    findings = packet["divergence"].get("findings", [])
    native_unit = next((item.get("unit") for item in findings
                        if isinstance(item.get("unit"), int)), None)
    selected = next((item for item in focus
                     if item.get("native_slot") == native_unit), focus[0])
    if not isinstance(selected.get("native_slot"), int) \
            or not isinstance(selected.get("java_id"), int):
        raise ValueError("branch witness focus has no stable native/Java pairing")
    return selected


def plan_from_packet(packet: dict[str, Any]) -> dict[str, Any]:
    """Create the exact, bounded native observation plan for one packet."""
    cycle = int(packet["divergence"]["cycle"])
    focus = _primary_focus(packet)
    findings = [item for item in packet["divergence"].get("findings", [])
                if item.get("unit") in (None, focus["native_slot"])]
    supported = []
    for finding in findings:
        field = finding.get("field")
        if field not in FIELD_LAYOUT or field in supported:
            continue
        supported.append(field)
    if not supported:
        raise ValueError("branch witness has no supported divergent unit field")
    primary = next(item for item in findings
                   if item.get("field") == supported[0])
    event_kind = _kind_for_finding(primary)
    watches = []
    for field in supported:
        layout = FIELD_LAYOUT[field]
        finding = next(item for item in findings if item.get("field") == field)
        watches.append({
            "field": field,
            "offset": layout["offset"],
            "bytes": layout["bytes"],
            "encoding": layout["encoding"],
            "oracle": finding.get("oracle"),
            "java": finding.get("java"),
            "runtime_address": {
                "expression": (
                    f"*(uint32_t*)0x{UNIT_POOL_POINTER:08x} + "
                    f"{focus['native_slot']} * {UNIT_BYTES} + {layout['offset']}"
                ),
                "requires_runtime_pool_resolution": True,
            },
        })
    causal_fields = []
    native_unit = packet.get("native_state", {}).get(str(cycle), {}) \
        .get("units", {}).get(str(focus["native_slot"]), {})
    for change in native_unit.get("raw_changes_from_previous_packet_cycle", []):
        field = change.get("field")
        if field not in FIELD_LAYOUT or field in supported or field in causal_fields:
            continue
        layout = FIELD_LAYOUT[field]
        causal_fields.append(field)
        watches.append({
            "field": field,
            "offset": layout["offset"],
            "bytes": layout["bytes"],
            "encoding": layout["encoding"],
            "oracle": change.get("after"),
            "before": change.get("before"),
            "java": None,
            "purpose": "native-causal-precursor",
            "runtime_address": {
                "expression": (
                    f"*(uint32_t*)0x{UNIT_POOL_POINTER:08x} + "
                    f"{focus['native_slot']} * {UNIT_BYTES} + {layout['offset']}"
                ),
                "requires_runtime_pool_resolution": True,
            },
        })
    addresses = KIND_ADDRESSES.get(event_kind) \
        or KIND_ADDRESSES["state.observation"]
    case = packet.get("case", {})
    return {
        "schema": SCHEMA,
        "case": case.get("id"),
        "fixture_id": case.get("fixture_id"),
        "scenario": case.get("scenario"),
        "seed": case.get("seed"),
        "divergence_cycle": cycle,
        "capture_window": {
            "start_cycle": max(1, cycle - 1),
            "end_cycle": cycle,
            "maximum_instructions": 65536,
            "maximum_ranked_branches": 20,
        },
        "focus": {
            "native_slot": focus["native_slot"],
            "java_id": focus["java_id"],
            "event_kind": event_kind,
            "fields": supported,
            "causal_fields": causal_fields,
            "oracle": focus.get("oracle"),
            "java": focus.get("java"),
        },
        "native_layout": {
            "executable_sha256": BNE_202_SHA256,
            "unit_pool_pointer": UNIT_POOL_POINTER,
            "unit_bytes": UNIT_BYTES,
            "executable_text": {"start": BNE_TEXT_START, "end": BNE_TEXT_END},
            "tick_hook_call_site": 0x00421238,
            "tick_hook_target": 0x00452110,
            "candidate_functions": addresses,
            "watches": watches,
        },
        "capture_contract": {
            "backend": "gdb-bts-plus-hardware-watchpoint",
            "network_disabled": True,
            "fixed_base_required": True,
            "original_fixture_immutable": True,
            "begin_after_previous_tick": True,
            "stop_on_first_matching_write": True,
            "record_branch_history": True,
            "record_predicate_operands": True,
        },
        "policy": {
            "automatic_source_changes": False,
            "production_engine_hooks": False,
            "acceptance_authority": "authenticated full regression gate",
            "purpose": "source localization and bounded rule inference only",
        },
    }


def _capture_manifest_path(path: Path) -> Path:
    if path.suffix != ".json":
        raise ValueError(f"branch capture must be JSON: {path}")
    return path.with_name(path.stem + ".manifest.json")


def _validate_event(event: object, previous_seq: int) -> int:
    if not isinstance(event, dict):
        raise ValueError("branch capture events must be JSON objects")
    seq = event.get("seq")
    if not isinstance(seq, int) or seq <= previous_seq:
        raise ValueError("branch capture event sequence must strictly increase")
    event_type = event.get("type")
    if event_type not in ("branch", "write", "snapshot"):
        raise ValueError(f"unsupported branch capture event type: {event_type}")
    if event_type in ("branch", "write") \
            and not isinstance(event.get("address"), int):
        raise ValueError(f"{event_type} event requires an integer address")
    if event_type == "branch":
        if not isinstance(event.get("target"), int) \
                or not isinstance(event.get("taken"), bool):
            raise ValueError("branch event requires target and taken")
    if event_type == "write":
        required = ("instruction", "native_slot", "field", "before", "after")
        if not isinstance(event.get("instruction"), int) \
                or not isinstance(event.get("native_slot"), int) \
                or any(key not in event for key in required):
            raise ValueError("write event is missing its writer or field transition")
    return seq


def validate_capture(capture: dict[str, Any]) -> None:
    if capture.get("schema") != CAPTURE_SCHEMA:
        raise ValueError("unsupported branch capture schema")
    events = capture.get("events")
    if not isinstance(events, list) or not events:
        raise ValueError("branch capture contains no events")
    previous = -1
    for event in events:
        previous = _validate_event(event, previous)


def load_verified_capture(path: Path, *, plan: dict[str, Any] | None = None,
        require_plan_match: bool = True) -> tuple[dict[str, Any], dict[str, Any]]:
    """Load a capture only when a sibling manifest authenticates its origin."""
    path = path.expanduser().resolve()
    manifest_path = _capture_manifest_path(path)
    if not path.is_file() or not manifest_path.is_file():
        raise ValueError(f"capture or sibling manifest is missing: {path}")
    capture = _json(path)
    validate_capture(capture)
    manifest = _json(manifest_path)
    record = manifest.get("capture", {})
    executable = manifest.get("oracle", {}).get("executable", {})
    oracle_run = manifest.get("oracle", {}).get("run_manifest", {})
    runtime = manifest.get("runtime", {})
    request = manifest.get("request", {})
    if manifest.get("schema") != CAPTURE_MANIFEST_SCHEMA:
        raise ValueError("unsupported branch capture manifest schema")
    if record.get("name") != path.name or file_identity(path) != {
            "bytes": record.get("bytes"), "sha256": record.get("sha256")}:
        raise ValueError("branch capture identity differs from its manifest")
    if executable.get("sha256") != BNE_202_SHA256:
        raise ValueError("branch capture is not from pinned BNE 2.02b")
    request_cycle = request.get("cycle")
    if not isinstance(request_cycle, int) \
            or not isinstance(oracle_run.get("bytes"), int) \
            or not isinstance(oracle_run.get("sha256"), str) \
            or len(oracle_run["sha256"]) != 64 \
            or oracle_run.get("scenario") != request.get("scenario") \
            or oracle_run.get("seed") != request.get("seed") \
            or oracle_run.get("branch_pause_cycle") != request_cycle \
            or not isinstance(oracle_run.get("cycles"), int) \
            or oracle_run["cycles"] < request_cycle:
        raise ValueError("branch capture lacks a matching oracle run identity")
    if capture.get("case") != request.get("case") \
            or capture.get("cycle") != request.get("cycle") \
            or capture.get("field") not in request.get("fields", []):
        raise ValueError("branch capture content differs from its request")
    if runtime.get("network_disabled") is not True:
        raise ValueError("branch capture was not captured offline")
    backend = manifest.get("backend", {})
    importer = manifest.get("harness", {}).get("capture_importer", {})
    if backend.get("branch_history") is not True \
            or backend.get("writer_watchpoint") is not True:
        raise ValueError("branch capture lacks branch history or writer proof")
    if not isinstance(importer.get("bytes"), int) \
            or not isinstance(importer.get("sha256"), str) \
            or len(importer["sha256"]) != 64:
        raise ValueError("branch capture lacks importer implementation identity")
    if plan is not None:
        captured_fields = set(request.get("fields", []))
        planned_fields = set(plan["focus"]["fields"]) \
            | set(plan["focus"].get("causal_fields", []))
        if not captured_fields or not captured_fields.issubset(planned_fields):
            raise ValueError("branch capture field is outside its witness plan")
    if plan is not None and require_plan_match:
        expected = {
            "case": plan["case"],
            "cycle": plan["divergence_cycle"],
            "native_slot": plan["focus"]["native_slot"],
        }
        if any(request.get(key) != value for key, value in expected.items()):
            raise ValueError("branch capture does not match its witness plan")
        if request.get("plan_sha256") != canonical_digest(plan):
            raise ValueError("branch capture plan identity changed")
    elif plan is not None:
        expected = {
            "case": plan["case"],
            "native_slot": plan["focus"]["native_slot"],
            "scenario": plan.get("scenario"),
            "seed": plan.get("seed"),
            "fixture_id": plan.get("fixture_id"),
        }
        if any(request.get(key) != value for key, value in expected.items()):
            raise ValueError("control capture is not compatible with witness plan")
        control_cycle = request.get("cycle")
        if not isinstance(control_cycle, int) \
                or control_cycle > int(plan["divergence_cycle"]):
            raise ValueError("control capture cycle exceeds witness divergence")
    evidence = {
        "path": str(path), **file_identity(path),
        "manifest": {"path": str(manifest_path), **file_identity(manifest_path)},
        "backend": backend.get("name"),
        "request": request,
    }
    return capture, evidence


def _writer_for(plan: dict[str, Any], events: list[dict[str, Any]]) \
        -> dict[str, Any] | None:
    focus = plan["focus"]
    cycle = plan["divergence_cycle"]
    candidates = [event for event in events
                  if event.get("type") == "write"
                  and event.get("native_slot") == focus["native_slot"]
                  and event.get("field") in (
                      set(focus["fields"]) | set(focus.get("causal_fields", [])))
                  and event.get("before") != event.get("after")
                  and event.get("cycle", cycle) in (cycle - 1, cycle)]
    if not candidates:
        return None

    def key(event: dict[str, Any]) -> tuple[int, int, int]:
        field = event["field"]
        watch = next(item for item in plan["native_layout"]["watches"]
                     if item["field"] == field)
        oracle_match = int(event.get("after") == watch.get("oracle"))
        divergence_tick = int(event.get("cycle", cycle) == cycle)
        return (-oracle_match, -divergence_tick, int(event["seq"]))

    return sorted(candidates, key=key)[0]


def _branch_samples(capture: dict[str, Any], address: int) \
        -> list[dict[str, Any]]:
    return [event for event in capture.get("events", [])
            if event.get("type") == "branch" and event.get("address") == address]


def _operand_value(operand: Any) -> tuple[str | None, int | float | None]:
    if isinstance(operand, dict):
        name, value = operand.get("name"), operand.get("value")
        return (name if isinstance(name, str) else None,
                value if isinstance(value, (int, float))
                and not isinstance(value, bool) else None)
    if isinstance(operand, (int, float)) and not isinstance(operand, bool):
        return None, operand
    return None, None


def _predicate_candidates(event: dict[str, Any], controls: list[dict[str, Any]]) \
        -> list[dict[str, Any]]:
    operands = event.get("operands")
    if not isinstance(operands, dict):
        return []
    lhs_name, lhs = _operand_value(operands.get("lhs"))
    rhs_name, rhs = _operand_value(operands.get("rhs"))
    if lhs is None or rhs is None:
        return []
    samples = [(lhs, rhs, bool(event["taken"]), "failing")]
    for index, capture in enumerate(controls):
        candidates = _branch_samples(capture, int(event["address"]))
        if not candidates:
            continue
        candidate = candidates[-1]
        other = candidate.get("operands")
        if not isinstance(other, dict):
            continue
        _, left = _operand_value(other.get("lhs"))
        _, right = _operand_value(other.get("rhs"))
        if left is not None and right is not None:
            samples.append((left, right, bool(candidate["taken"]),
                            f"control-{index + 1}"))
    if len(samples) < 2 or len({sample[2] for sample in samples}) < 2:
        return []
    operators = {
        "==": lambda left, right: left == right,
        "!=": lambda left, right: left != right,
        "<": lambda left, right: left < right,
        "<=": lambda left, right: left <= right,
        ">": lambda left, right: left > right,
        ">=": lambda left, right: left >= right,
    }
    condition_operator = {
        "e": "==", "z": "==", "==": "==",
        "ne": "!=", "nz": "!=", "!=": "!=",
        "l": "<", "nge": "<", "b": "<", "c": "<", "nae": "<",
        "<": "<",
        "le": "<=", "ng": "<=", "be": "<=", "na": "<=", "<=": "<=",
        "g": ">", "nle": ">", "a": ">", "nbe": ">", ">": ">",
        "ge": ">=", "nl": ">=", "ae": ">=", "nb": ">=", "nc": ">=",
        ">=": ">=",
    }.get(str(event.get("condition", "")).lower())
    left_label = lhs_name or "lhs"
    right_label = rhs_name or str(rhs)
    ranked = []
    for operator, evaluate in operators.items():
        correct = sum(evaluate(left, right) == taken
                      for left, right, taken, _ in samples)
        inverted = sum((not evaluate(left, right)) == taken
                       for left, right, taken, _ in samples)
        invert = inverted > correct
        best = max(correct, inverted)
        if best != len(samples):
            continue
        expression = f"{left_label} {operator} {right_label}"
        if invert:
            expression = f"not ({expression})"
        ranked.append({
            "expression": expression,
            "matches_branch_condition": operator == condition_operator,
            "samples": len(samples),
            "accuracy": round(best / len(samples), 4),
            "observations": [
                {"lhs": left, "rhs": right, "taken": taken, "source": source}
                for left, right, taken, source in samples
            ],
            "bounded_grammar": "integer-comparison-v1",
        })
    return sorted(ranked, key=lambda item: (
        not item["matches_branch_condition"], item["expression"],
    ))


def _function_hint(candidates: list[int], address: int) -> int | None:
    """Attach a known function label only inside a conservative local span."""
    candidate = max((item for item in candidates if item <= address), default=None)
    return candidate if candidate is not None and address - candidate <= 0x1000 \
        else None


def _rank_branches(writer: dict[str, Any], capture: dict[str, Any],
        controls: list[dict[str, Any]], maximum: int) -> list[dict[str, Any]]:
    history = [event for event in capture["events"]
               if event.get("type") == "branch" and event["seq"] < writer["seq"]]
    history = history[-512:]
    last_by_address: dict[int, tuple[int, dict[str, Any]]] = {}
    for index, event in enumerate(history):
        last_by_address[int(event["address"])] = (index, event)
    results = []
    for address, (index, event) in last_by_address.items():
        distance = len(history) - 1 - index
        recency = 4.0 / (1.0 + math.log2(distance + 1.0))
        # Compare the same dynamic suffix: the final execution of this branch
        # before each watched writer.  Pool loops execute one static branch
        # hundreds of times; mixing every unit's outcome destroys contrast.
        control_events = []
        for control in controls:
            matches = _branch_samples(control, address)
            if matches:
                control_events.append(matches[-1])
        outcomes = {bool(item["taken"]) for item in control_events}
        contrast = 0.0
        if control_events and bool(event["taken"]) not in outcomes:
            contrast = 6.0
        elif len(outcomes) > 1:
            contrast = 1.0
        same_function = event.get("function") == writer.get("function") \
            and event.get("function") is not None
        breakdown = {
            "recency": round(recency, 4),
            "contrast": contrast,
            "same_function": 2.0 if same_function else 0.0,
            "predicate_operands": 1.5 if isinstance(event.get("operands"), dict) else 0.0,
            "controls_writer": 2.5 if event.get("controls_writer") is True else 0.0,
            "conditional": 1.0 if event.get("conditional", True) else 0.0,
        }
        predicates = _predicate_candidates(event, controls)
        probe_plan = event.get("predicate_probe_plan")
        if probe_plan is None and isinstance(event.get("predicate_probe"), dict) \
                and isinstance(event.get("operands"), dict):
            probe_plan = {
                "schema": SCHEMA,
                "branch": address,
                "compare": event["predicate_probe"].get("compare"),
                "condition": event.get("condition"),
                "lhs": {"name": event["operands"].get("lhs", {}).get("name")},
                "rhs": {"name": event["operands"].get("rhs", {}).get("name")},
                "encoding": event["predicate_probe"].get("encoding"),
                "scope": "last dynamic observation before watched writer",
            }
        score = sum(breakdown.values()) + (2.0 if predicates else 0.0)
        results.append({
            "address": address,
            "target": event["target"],
            "taken": event["taken"],
            "function": event.get("function"),
            "distance_from_writer_branches": distance,
            "instruction": event.get("instruction"),
            "condition": event.get("condition"),
            "operands": event.get("operands"),
            "control_observations": len(control_events),
            "control_outcomes": sorted(outcomes),
            "score": round(score, 4),
            "score_breakdown": breakdown,
            "predicate_candidates": predicates,
            "predicate_probe_plan": probe_plan,
        })
    return sorted(results, key=lambda item: (-item["score"],
                                              item["distance_from_writer_branches"],
                                              item["address"]))[:maximum]


def _source_candidates(source_root: Path | None, event_kind: str,
        branch: dict[str, Any] | None) -> list[dict[str, Any]]:
    if source_root is None:
        return []
    source_root = source_root.expanduser().resolve()
    base = source_root / "engine/src/main/java/net/chonkbase/chonkcraft/engine"
    if not base.is_dir():
        return []
    hints = SOURCE_HINTS.get(event_kind, SOURCE_HINTS["state.observation"])
    operand_names = []
    if branch is not None and isinstance(branch.get("operands"), dict):
        for operand in branch["operands"].values():
            name, _ = _operand_value(operand)
            if name:
                operand_names.append(name)
    tokens = tuple(dict.fromkeys((*operand_names, *hints["tokens"])))
    candidates = []
    for relative in hints["files"]:
        path = base / relative
        if not path.is_file():
            continue
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        matches = []
        matched_line_count = 0
        unique_tokens = set()
        for line_number, line in enumerate(lines, 1):
            lowered = line.lower()
            matched = [token for token in tokens if token.lower() in lowered]
            if matched:
                matched_line_count += 1
                unique_tokens.update(token.lower() for token in matched)
                if len(matches) < 4:
                    matches.append({
                        "line": line_number,
                        "tokens": matched,
                        "text": line.strip()[:180],
                    })
        # Keep the score bounded: a large orchestration file should not win
        # merely by repeating a generic word hundreds of times.
        score = (3.0 + min(5.0, math.log2(matched_line_count + 1.0))
                 + min(4.0, 0.75 * len(unique_tokens)))
        candidates.append({
            "path": str(path.relative_to(source_root)),
            "score": round(score, 4),
            "semantic_tokens": list(tokens),
            "matched_line_count": matched_line_count,
            "unique_token_count": len(unique_tokens),
            "matches": matches,
            "heuristic": True,
        })
    return sorted(candidates, key=lambda item: (-item["score"], item["path"]))


def _source_evidence(source_root: Path, event_kind: str) -> dict[str, Any]:
    base = source_root / "engine/src/main/java/net/chonkbase/chonkcraft/engine"
    hints = SOURCE_HINTS.get(event_kind, SOURCE_HINTS["state.observation"])
    files = {}
    for relative in hints["files"]:
        path = base / relative
        if path.is_file():
            files[str(path.relative_to(source_root))] = file_identity(path)
    return {"path": str(source_root), "files": files}


def analyze_capture(plan: dict[str, Any], capture: dict[str, Any], *,
        controls: Iterable[dict[str, Any]] = (),
        source_root: Path | None = ROOT) -> dict[str, Any]:
    """Locate the watched writer and return a bounded dynamic branch slice."""
    validate_capture(capture)
    control_list = list(controls)
    for control in control_list:
        validate_capture(control)
    writer = _writer_for(plan, capture["events"])
    maximum = int(plan["capture_window"]["maximum_ranked_branches"])
    branches = _rank_branches(writer, capture, control_list, maximum) \
        if writer is not None else []
    top = branches[0] if branches else None
    return {
        "schema": SCHEMA,
        "case": plan["case"],
        "cycle": plan["divergence_cycle"],
        "focus": plan["focus"],
        "writer": writer,
        "writer_located": writer is not None,
        "writer_role": (
            "divergent-field" if writer is not None
            and writer.get("field") in plan["focus"]["fields"]
            else "native-causal-precursor" if writer is not None else None
        ),
        "ranked_branches": branches,
        "dynamic_slice_size": len(branches),
        "top_branch": top,
        "top_predicate": top["predicate_candidates"][0]
            if top and top.get("predicate_candidates") else None,
        "predicate_probe_plan": top.get("predicate_probe_plan") if top else None,
        "java_source_candidates": _source_candidates(
            source_root, plan["focus"]["event_kind"], top,
        ),
        "contrast_capture_count": len(control_list),
        "proof": {
            "exact_native_writer": writer is not None
                and writer.get("field") in plan["focus"]["fields"],
            "exact_native_precursor_writer": writer is not None
                and writer.get("field") in plan["focus"].get("causal_fields", []),
            "branch_history": bool(branches),
            "contrasted_branch": any(
                branch["score_breakdown"]["contrast"] >= 6.0
                for branch in branches
            ),
            "predicate_inferred": bool(top and top.get("predicate_candidates")),
            "predicate_probe_ready": bool(
                top and top.get("predicate_probe_plan")
            ),
            "semantic_slice_ready": bool(
                top and top.get("predicate_candidates") and control_list
            ),
            "java_candidates_are_heuristic": True,
        },
        "policy": plan["policy"],
    }


def _safe(root: Path, relative: str) -> Path:
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()) or not path.is_file():
        raise ValueError(f"unsafe or missing triage artifact: {relative}")
    return path


def _packet_for_case(triage_run: Path, triage: dict[str, Any], case: str) \
        -> tuple[dict[str, Any], Path]:
    record = next((item for item in triage.get("packets", [])
                   if item.get("case") == case), None)
    if record is None:
        raise ValueError(f"triage run has no authenticated packet for {case}")
    path = _safe(triage_run, record["packet"])
    return _json(path), path


def _summary(result: dict[str, Any], request_sha256: str) -> str:
    writer = result.get("writer") or {}
    branch = result.get("top_branch") or {}
    predicate = result.get("top_predicate") or {}
    probe = result.get("predicate_probe_plan") or {}
    lines = [
        "# BNE Branch Witness", "",
        f"- Run: `{request_sha256}`",
        f"- Case: `{result['case']}` @ cycle **{result['cycle']}**",
        f"- Watched native slot: **{result['focus']['native_slot']}**",
        f"- " + (
            "Exact divergent-field writer" if result.get("writer_role")
            == "divergent-field" else "Native causal-precursor writer"
        ) + ": " + (
            f"`0x{writer['instruction']:08x}`" if writer else "not located"
        ),
        f"- Dynamic branch slice: **{result['dynamic_slice_size']}** candidates",
        f"- Top controlling branch: " + (
            f"`0x{branch['address']:08x}` (score {branch['score']})"
            if branch else "not located"
        ),
        f"- Bounded predicate: `{predicate.get('expression', 'not inferred')}`",
        f"- Ranked Java files: **{len(result['java_source_candidates'])}**", "",
        "This is authenticated localization evidence, not an automatic patch or "
        "acceptance result. Confirm any source change with the full parity gate.", "",
    ]
    if probe and not predicate:
        lines.extend([
            "## Ready predicate pass", "",
            "Repeat the isolated native capture with:", "",
            "```text",
            f"--predicate-branch 0x{probe['branch']:08x} "
            f"--predicate-compare 0x{probe['compare']:08x} "
            f"--predicate-lhs-register {probe['lhs']['name']} "
            f"--predicate-rhs-register {probe['rhs']['name']} "
            f"--predicate-condition {probe['condition']}",
            "```", "",
        ])
    if predicate and result["proof"].get("semantic_slice_ready"):
        lines.extend([
            "## Ready semantic slice", "",
            "The predicate and clean contrast are ready for the offline "
            "`bne_java.py semantic-slice` pass. Supply each capture's raw "
            "GDB history; its sibling manifest authenticates the exact bytes. "
            "Unknown native tables remain explicit rather than guessed.", "",
        ])
    return "\n".join(lines)


def verify_witness_manifest(run_root: Path, manifest: dict[str, Any],
        request_sha256: str) -> None:
    if manifest.get("schema") != SCHEMA:
        raise ValueError("unsupported branch-witness manifest schema")
    if manifest.get("request_sha256") != request_sha256 \
            or canonical_digest(manifest.get("request")) != request_sha256:
        raise ValueError("branch-witness request identity changed")
    for relative, expected in manifest.get("artifacts", {}).items():
        path = (run_root / relative).resolve()
        if not path.is_relative_to(run_root.resolve()) \
                or not path.is_file() or file_identity(path) != expected:
            raise ValueError(f"branch-witness artifact identity changed: {relative}")


def run_branch_witness(triage_run: Path, case: str, capture_path: Path,
        artifact_root: Path, *, control_paths: Iterable[Path] = (),
        source_root: Path | None = ROOT) -> tuple[int, Path]:
    triage_run = triage_run.expanduser().resolve()
    triage_manifest_path = triage_run / "manifest.json"
    triage = _json(triage_manifest_path)
    verify_manifest(triage_run, triage, triage["request_sha256"])
    packet, packet_path = _packet_for_case(triage_run, triage, case)
    plan = plan_from_packet(packet)
    capture, evidence = load_verified_capture(capture_path, plan=plan)
    controls = []
    control_evidence = []
    for path in control_paths:
        control, record = load_verified_capture(
            path, plan=plan, require_plan_match=False,
        )
        controls.append(control)
        control_evidence.append(record)
    source_record = None
    if source_root is not None:
        source_root = source_root.expanduser().resolve()
        source_record = _source_evidence(
            source_root, plan["focus"]["event_kind"],
        )
    request = {
        "schema": SCHEMA,
        "implementation": {
            str(path.relative_to(ROOT)): file_identity(path)
            for path in IMPLEMENTATION
        },
        "triage_request_sha256": triage["request_sha256"],
        "triage_manifest": {
            "path": str(triage_manifest_path), **file_identity(triage_manifest_path),
        },
        "packet": {"path": str(packet_path), **file_identity(packet_path)},
        "case": case,
        "plan_sha256": canonical_digest(plan),
        "capture": evidence,
        "controls": control_evidence,
        "source_root": source_record,
    }
    request_sha256 = canonical_digest(request)
    artifact_root = artifact_root.expanduser().resolve()
    run_root = artifact_root / "runs" / request_sha256
    manifest_path = run_root / "manifest.json"
    if manifest_path.is_file():
        manifest = _json(manifest_path)
        verify_witness_manifest(run_root, manifest, request_sha256)
    else:
        run_root.mkdir(parents=True, exist_ok=True)
        result = analyze_capture(
            plan, capture, controls=controls, source_root=source_root,
        )
        _write_json(run_root / "branch-witness-plan.json", plan)
        _write_json(run_root / "branch-witness.json", result)
        _write_text(run_root / "NEXT.md", _summary(result, request_sha256))
        manifest = {
            "schema": SCHEMA,
            "request_sha256": request_sha256,
            "created_at": datetime.now(timezone.utc).isoformat(),
            "request": request,
            "result": {
                "case": case,
                "cycle": result["cycle"],
                "writer_located": result["writer_located"],
                "writer_role": result["writer_role"],
                "dynamic_slice_size": result["dynamic_slice_size"],
                "top_branch_address": result["top_branch"]["address"]
                    if result["top_branch"] else None,
                "predicate_inferred": result["proof"]["predicate_inferred"],
            },
            "artifacts": inventory_files(run_root, [
                run_root / "branch-witness-plan.json",
                run_root / "branch-witness.json", run_root / "NEXT.md",
            ]),
        }
        _write_json(manifest_path, manifest)
    pointer = {
        "schema": SCHEMA,
        "request_sha256": request_sha256,
        "run": str(run_root.relative_to(artifact_root)),
        "manifest": str(manifest_path.relative_to(artifact_root)),
        "manifest_identity": file_identity(manifest_path),
        "case": case,
        **manifest["result"],
    }
    _write_json(artifact_root / "latest.json", pointer)
    _write_json(artifact_root / f"latest-{case}.json", pointer)
    return (0 if manifest["result"]["writer_located"] else 1), run_root
