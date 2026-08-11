#!/usr/bin/env python3
"""Compare native and Java transition cadence for one paired unit.

The profiler consumes ordinary semantic-v1 traces.  A sealed .bnefx source is
preferred because its archive and trace are authenticated before analysis.
The output describes observations and phase; it never proposes an engine edit.
"""

from __future__ import annotations

from datetime import datetime, timezone
import io
import json
import os
from pathlib import Path
import tempfile
from typing import Any, Iterable
import zipfile

import bne_compare
from bne_fixture import validate_fixture
from bne_packet import align_trace_units, augment_trace_pixels, parse_trace
from bne_triage import canonical_digest, file_identity, inventory_files


SCHEMA = 1
IMPLEMENTATION = tuple(Path(__file__).with_name(name) for name in (
    "bne_cadence.py", "bne_compare.py", "bne_fixture.py", "bne_packet.py",
    "bne_triage.py",
))
FIELDS = {
    "position", "x", "y", "hp", "order", "removed",
    "pixel-position", "pixel-x", "pixel-y",
}
PIXEL_FIELDS = {
    "pixel-position": ("pixel_x", "pixel_y"),
    "pixel-x": ("pixel_x",),
    "pixel-y": ("pixel_y",),
}


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


def cadence_signature(cycles: Iterable[int]) -> dict[str, Any]:
    ordered = sorted(set(int(cycle) for cycle in cycles))
    gaps = [right - left for left, right in zip(ordered, ordered[1:])]
    stable = len(gaps) >= 2 and len(set(gaps)) == 1
    tail_period = None
    tail_length = 0
    if gaps:
        candidate = gaps[-1]
        for gap in reversed(gaps):
            if gap != candidate:
                break
            tail_length += 1
        if tail_length >= 2:
            tail_period = candidate
    return {
        "cycles": ordered,
        "gaps": gaps,
        "observation_count": len(ordered),
        "frequency_claim_supported": stable or tail_period is not None,
        "frequency_claim_scope": (
            "full-observed-window" if stable else
            "settled-tail-only" if tail_period is not None else None
        ),
        "frequency_evidence": (
            "at least two equal observed gaps" if stable else
            "at least two equal observed gaps in the settled tail"
            if tail_period is not None else
            "one interval cannot establish recurrence" if len(gaps) == 1 else
            "fewer than two equal observed gaps"
        ),
        "classification": (
            "stable-period" if stable else
            "single-gap" if len(gaps) == 1 else
            "settled-tail" if tail_period is not None else
            "irregular" if gaps else "insufficient"
        ),
        "stable_period": gaps[0] if stable else None,
        "settled_tail_period": tail_period,
        "settled_tail_gaps": tail_length,
        "largest_gap": max(gaps) if gaps else None,
    }


def _value(unit: dict[str, Any] | None, field: str) -> object:
    if unit is None:
        return None
    if field == "position":
        return [unit["x"], unit["y"]]
    if field in PIXEL_FIELDS:
        names = PIXEL_FIELDS[field]
        if any(name not in unit for name in names):
            raise ValueError(
                "selected trace lacks pixel coordinates; rerun it with the "
                "current EngineTrace and CHONKCRAFT_TRACE_BNE_SUBTILE=1 before "
                "requesting a sub-tile field"
            )
        values = [unit[name] for name in names]
        return values if len(values) > 1 else values[0]
    return unit[field]


def subtile_precursor(native: dict[int, dict[str, Any]],
        java: dict[int, dict[str, Any]], *, native_unit: int,
        java_unit: int) -> dict[str, Any]:
    """Locate pixel drift hidden behind equal semantic tile positions."""
    observations = []
    comparable = 0
    first_coarse = None
    for cycle in sorted(set(native) & set(java)):
        left = native[cycle]["units"].get(native_unit)
        right = java[cycle]["units"].get(java_unit)
        if left is None or right is None:
            if first_coarse is None and left != right:
                first_coarse = cycle
            continue
        coarse_equal = all(left.get(field) == right.get(field) for field in (
            "type", "player", "x", "y", "hp", "order", "removed",
        ))
        if first_coarse is None and not coarse_equal:
            first_coarse = cycle
        if not all(field in left and field in right
                   for field in ("pixel_x", "pixel_y")):
            continue
        comparable += 1
        pixel_equal = (left["pixel_x"] == right["pixel_x"]
                       and left["pixel_y"] == right["pixel_y"])
        tile_equal = left["x"] == right["x"] and left["y"] == right["y"]
        if tile_equal and not pixel_equal:
            observations.append({
                "cycle": cycle,
                "oracle_tile": [left["x"], left["y"]],
                "java_tile": [right["x"], right["y"]],
                "oracle_pixel": [left["pixel_x"], left["pixel_y"]],
                "java_pixel": [right["pixel_x"], right["pixel_y"]],
                "delta": [right["pixel_x"] - left["pixel_x"],
                          right["pixel_y"] - left["pixel_y"]],
            })
    earliest = observations[0]["cycle"] if observations else None
    return {
        "available": comparable > 0,
        "comparable_cycles": comparable,
        "hidden_mismatch": bool(observations),
        "earliest_hidden_mismatch_cycle": earliest,
        "first_coarse_mismatch_cycle": first_coarse,
        "lead_cycles": (first_coarse - earliest
                        if earliest is not None and first_coarse is not None
                        and earliest < first_coarse else None),
        "mismatch_cycles": [item["cycle"] for item in observations],
        "first_observations": observations[:12],
        "authority": "diagnostic-only; semantic-v1 acceptance is unchanged",
    }


def transitions(trace: dict[int, dict[str, Any]], unit_id: int,
        field: str) -> list[dict[str, Any]]:
    if field not in FIELDS:
        raise ValueError(f"unsupported cadence field: {field}")
    previous = object()
    result = []
    initialized = False
    for cycle in sorted(trace):
        unit = trace[cycle]["units"].get(unit_id)
        value = _value(unit, field)
        if not initialized:
            previous = value
            initialized = True
            continue
        if value != previous:
            result.append({"cycle": cycle, "before": previous, "after": value})
            previous = value
    return result


def _phase_diagnosis(native: list[dict[str, Any]],
        java: list[dict[str, Any]]) -> dict[str, Any]:
    paired = []
    for ordinal, (left, right) in enumerate(zip(native, java), 1):
        paired.append({
            "ordinal": ordinal,
            "native_cycle": left["cycle"], "java_cycle": right["cycle"],
            "phase_offset": right["cycle"] - left["cycle"],
            "native_after": left["after"], "java_after": right["after"],
            "value_match": left["after"] == right["after"],
        })
    offsets = [item["phase_offset"] for item in paired]
    matching_values = bool(paired) and all(item["value_match"] for item in paired)
    classification = "insufficient"
    delay = None
    introduced = None
    if len(paired) >= 2 and matching_values:
        if all(offset == 0 for offset in offsets):
            classification = "aligned"
        elif len(set(offsets)) == 1:
            classification = "steady-phase-offset"
            delay = offsets[0]
        else:
            for index in range(1, len(offsets)):
                tail = offsets[index:]
                if offsets[index - 1] != tail[0] and len(set(tail)) == 1:
                    classification = "one-time-delay"
                    delay = tail[0] - offsets[index - 1]
                    introduced = paired[index]
                    break
            if classification == "insufficient":
                classification = "changing-phase"
    elif paired and not matching_values:
        classification = "different-transition-sequence"
    return {
        "classification": classification,
        "paired_transitions": paired,
        "phase_offsets": offsets,
        "estimated_extra_wait": delay,
        "introduced_at": introduced,
        "transition_values_match": matching_values,
        "unpaired_native_transitions": max(0, len(native) - len(paired)),
        "unpaired_java_transitions": max(0, len(java) - len(paired)),
    }


def _infer_java_unit(native: dict[int, dict[str, Any]],
        java: dict[int, dict[str, Any]], native_unit: int) -> int:
    counts: dict[int, int] = {}
    for pairing in align_trace_units(native, java).values():
        java_unit = pairing.get(native_unit)
        if java_unit is not None:
            counts[java_unit] = counts.get(java_unit, 0) + 1
    if not counts:
        raise ValueError(f"could not pair native unit {native_unit} to Java")
    ranked = sorted(counts.items(), key=lambda item: (-item[1], item[0]))
    if len(ranked) > 1 and ranked[0][1] == ranked[1][1]:
        raise ValueError(f"native unit {native_unit} has an ambiguous Java pairing")
    return ranked[0][0]


def analyze_cadence(native: dict[int, dict[str, Any]],
        java: dict[int, dict[str, Any]], *, native_unit: int,
        java_unit: int | None = None, field: str = "position") \
        -> dict[str, Any]:
    if not native or not java:
        raise ValueError("cadence analysis requires two non-empty traces")
    shared_start = max(min(native), min(java))
    shared_end = min(max(native), max(java))
    native = {cycle: state for cycle, state in native.items()
              if shared_start <= cycle <= shared_end}
    java = {cycle: state for cycle, state in java.items()
            if shared_start <= cycle <= shared_end}
    if not native or not java:
        raise ValueError("cadence traces have no shared cycle window")
    selected_java = java_unit if java_unit is not None \
        else _infer_java_unit(native, java, native_unit)
    native_transitions = transitions(native, native_unit, field)
    java_transitions = transitions(java, selected_java, field)
    phase = _phase_diagnosis(native_transitions, java_transitions)
    precursor = subtile_precursor(
        native, java, native_unit=native_unit, java_unit=selected_java,
    )
    return {
        "schema": SCHEMA, "field": field,
        "shared_window": {"start": shared_start, "end": shared_end},
        "native_unit": native_unit, "java_unit": selected_java,
        "native": {
            "transitions": native_transitions,
            "cadence": cadence_signature(
                item["cycle"] for item in native_transitions),
        },
        "java": {
            "transitions": java_transitions,
            "cadence": cadence_signature(
                item["cycle"] for item in java_transitions),
        },
        "phase": phase,
        "subtile_precursor": precursor,
        "useful": ((len(native_transitions) >= 2
                    and len(java_transitions) >= 2)
                   or precursor["hidden_mismatch"]),
    }


def _native_source(path: Path) -> tuple[dict[int, dict[str, Any]], dict[str, Any]]:
    path = path.expanduser().resolve()
    identity = {"path": str(path), **file_identity(path)}
    if zipfile.is_zipfile(path):
        validation = validate_fixture(path)
        with zipfile.ZipFile(path) as archive:
            manifest = json.loads(archive.read("manifest.json"))
            trace_name = manifest["run"]["trace"]["name"]
            normalized = io.BytesIO()
            with archive.open("trace.txt") as trace_source, \
                    archive.open("state.bin") as state_source:
                bne_compare.normalize_fixture_trace(
                    trace_source, state_source, normalized,
                    int(validation["cycles"]),
                )
            normalized.seek(0)
            trace = parse_trace(normalized)
            with archive.open("state.bin") as state_source:
                augment_trace_pixels(state_source, trace)
        return trace, {
            **identity, "kind": "sealed-fixture", "authenticated": True,
            "fixture_id": validation["fixture_id"],
            "trace_name": trace_name, "trace_member": "trace.txt",
        }
    with path.open("r", encoding="utf-8") as source:
        trace = parse_trace(source)
    return trace, {**identity, "kind": "trace", "authenticated": False}


def _java_source(path: Path) -> tuple[dict[int, dict[str, Any]], dict[str, Any]]:
    path = path.expanduser().resolve()
    identity: dict[str, Any] = {
        "path": str(path), **file_identity(path), "authenticated": False,
    }
    suffix = ".java.trace.txt"
    if path.name.endswith(suffix):
        run_path = path.with_name(path.name[:-len(suffix)] + ".java-run.json")
        if run_path.is_file():
            run = json.loads(run_path.read_text(encoding="utf-8"))
            recorded = run.get("java_trace", {})
            if recorded.get("sha256") == identity["sha256"] \
                    and recorded.get("bytes") == identity["bytes"]:
                identity["authenticated"] = True
                identity["run_manifest"] = {
                    "path": str(run_path), **file_identity(run_path),
                }
                identity["engine"] = run.get("engine")
                identity["case"] = run.get("id")
    with path.open("r", encoding="utf-8") as source:
        trace = parse_trace(source)
    return trace, identity


def _summary(result: dict[str, Any]) -> str:
    phase = result["phase"]
    precursor = result["subtile_precursor"]
    lines = [
        "# Paired transition cadence", "",
        f"- Case: `{result.get('case', 'unknown')}`",
        f"- Field: `{result['field']}`",
        f"- Pair: native `{result['native_unit']}` → Java `{result['java_unit']}`",
        f"- Native cycles: `{result['native']['cadence']['cycles']}`",
        f"- Java cycles: `{result['java']['cadence']['cycles']}`",
        f"- Native gaps: `{result['native']['cadence']['gaps']}`",
        f"- Java gaps: `{result['java']['cadence']['gaps']}`",
        f"- Phase diagnosis: **{phase['classification']}**",
    ]
    if phase["estimated_extra_wait"] is not None:
        lines.append(
            f"- Estimated one-time added wait: "
            f"**{phase['estimated_extra_wait']} cycles**"
        )
    if precursor["hidden_mismatch"]:
        coarse = precursor["first_coarse_mismatch_cycle"]
        lines.extend([
            f"- Hidden sub-tile mismatch begins: fixture cycle "
            f"**{precursor['earliest_hidden_mismatch_cycle']}**",
            f"- First coarse mismatch for this unit: "
            f"`{coarse if coarse is not None else 'none in shared window'}`",
        ])
    elif precursor["available"]:
        lines.append("- Hidden sub-tile mismatch: `none in the shared window`")
    else:
        lines.append(
            "- Hidden sub-tile mismatch: `unavailable in this trace`. The "
            "sub-tile fields exist only when the Java trace ran with "
            "`CHONKCRAFT_TRACE_BNE_SUBTILE=1`, and acceptance keeps the ordinary "
            "blocker trace. The forensic packet for this blocker carries the "
            "exact rerun under `java_diagnostic`, with the paired unit, "
            "window and asset already filled in, writing to "
            "`.bne-subtile-evidence/CASE`; run that rather than reconstructing "
            "one."
        )
    lines.extend([
        "", "This report localizes timing. It does not authorize a source edit.", "",
    ])
    return "\n".join(lines)


def run_cadence(native_source: Path, java_trace: Path, artifact_root: Path,
        *, native_unit: int, java_unit: int | None = None,
        field: str = "position") -> tuple[int, Path]:
    native, native_identity = _native_source(native_source)
    java, java_identity = _java_source(java_trace)
    request = {
        "schema": SCHEMA,
        "implementation": {path.name: file_identity(path)
                           for path in IMPLEMENTATION},
        "native": native_identity, "java": java_identity,
        "native_unit": native_unit, "java_unit": java_unit, "field": field,
    }
    request_sha256 = canonical_digest(request)
    artifact_root = artifact_root.expanduser().resolve()
    run_root = artifact_root / "runs" / request_sha256
    manifest_path = run_root / "manifest.json"
    if manifest_path.is_file():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if manifest.get("schema") != SCHEMA \
                or manifest.get("request_sha256") != request_sha256 \
                or canonical_digest(manifest.get("request")) != request_sha256:
            raise ValueError("cached cadence request identity changed")
        for relative, expected in manifest["artifacts"].items():
            path = run_root / relative
            if not path.is_file() or file_identity(path) != expected:
                raise ValueError(f"cadence artifact changed: {path}")
        _write_json(artifact_root / "latest.json", manifest["pointer"])
        return int(manifest["exit_code"]), run_root
    result = analyze_cadence(
        native, java, native_unit=native_unit, java_unit=java_unit, field=field,
    )
    result.update({
        "case": java_identity.get("case") or Path(native_source).stem,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "inputs": {"native": native_identity, "java": java_identity},
        "proof": {
            "native_authenticated": native_identity["authenticated"],
            "java_authenticated": java_identity["authenticated"],
            "source_changed": False,
        },
    })
    run_root.mkdir(parents=True, exist_ok=True)
    result_path = run_root / "cadence.json"
    summary_path = run_root / "NEXT.md"
    _write_json(result_path, result)
    _write(summary_path, _summary(result))
    exit_code = 0 if result["useful"] else 1
    pointer = {
        "schema": SCHEMA, "run": str(run_root.relative_to(artifact_root)),
        "request_sha256": request_sha256, "exit_code": exit_code,
        "classification": result["phase"]["classification"],
        "case": result["case"],
        "estimated_extra_wait": result["phase"]["estimated_extra_wait"],
    }
    manifest = {
        "schema": SCHEMA, "request_sha256": request_sha256,
        "request": request, "exit_code": exit_code, "pointer": pointer,
        "artifacts": inventory_files(run_root, [result_path, summary_path]),
    }
    _write_json(manifest_path, manifest)
    _write_json(artifact_root / "latest.json", pointer)
    return exit_code, run_root
