#!/usr/bin/env python3
"""Compose causal, experimental, synthesis, coverage, and memory artifacts."""

from __future__ import annotations

from datetime import datetime, timezone
import json
import os
from pathlib import Path
import tempfile
from typing import Any

from bne_atlas import (
    coverage_baseline, dashboard_snapshot, ingest_run, record_coverage,
    record_experiment, similar_failures,
)
from bne_causal import (
    align_events, events_from_packet, format_alignment, parse_causal_jsonl,
    parse_java_trace, parse_native_trace,
)
from bne_coverage import (
    command_variants_from_packet, coverage_report, uncovered_control_transfers,
)
from bne_counterfactual import plan_from_packet as counterfactual_plan_from_packet
from bne_branch_witness import plan_from_packet as branch_witness_plan_from_packet
from bne_experiments import default_investigation_plan, hp_evidence
from bne_function_lab import (
    BNE_202_SHA256, KIND_ADDRESSES, analyze_function, boundary_variants,
    experiment_spec, replay_known_function, sha256,
)
from bne_minimize import minimize_causal_slice, plan_from_packet
from bne_rng_ledger import build_ledger, draws_from_events, format_ledger
from bne_state_machine import (
    analyse as analyse_state_machine, format_report as format_state_report,
    mine, samples_from_packet, signals as state_machine_signals,
)
from bne_tournament import plan_from_rules
from bne_triage import (
    canonical_digest, file_identity, inventory_files, verify_manifest,
)


LAB_SCHEMA = 1
LAB_IMPLEMENTATION = (
    "bne_lab.py", "bne_atlas.py", "bne_causal.py", "bne_coverage.py",
    "bne_branch_witness.py", "bne_counterfactual.py", "bne_experiments.py",
    "bne_function_lab.py",
    "bne_minimize.py", "bne_rng_ledger.py", "bne_state_machine.py",
    "bne_tournament.py", "bne_triage.py",
)


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


def _safe(run_root: Path, relative: str) -> Path:
    path = (run_root / relative).resolve()
    if not path.is_relative_to(run_root.resolve()) or not path.is_file():
        raise ValueError(f"unsafe or missing triage artifact: {relative}")
    return path


def verified_native_trace(path: Path) -> dict[str, Any]:
    """Authenticate an independently captured native causal trace.

    The ordinary triage manifest remains immutable. A richer native rerun can
    be attached only when its sibling oracle manifest authenticates the trace,
    pinned retail executable, scenario, fixture identity, and offline runtime.
    """
    path = path.expanduser().resolve()
    suffix = ".trace.txt"
    if not path.name.endswith(suffix) or not path.is_file():
        raise ValueError(f"native causal trace must end in {suffix}: {path}")
    manifest_path = path.with_name(
        path.name[:-len(suffix)] + ".manifest.json"
    )
    if not manifest_path.is_file():
        raise ValueError(f"missing native trace manifest: {manifest_path}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    trace = manifest.get("run", {}).get("trace", {})
    executable = manifest.get("oracle", {}).get("executable", {})
    runtime = manifest.get("runtime", {})
    if manifest.get("schema") != 2:
        raise ValueError("unsupported native trace manifest schema")
    if trace.get("name") != path.name or file_identity(path) != {
            "bytes": trace.get("bytes"), "sha256": trace.get("sha256")}:
        raise ValueError("native causal trace identity differs from its manifest")
    if executable.get("sha256") != BNE_202_SHA256:
        raise ValueError("native causal trace is not from pinned BNE 2.02b")
    if runtime.get("network_disabled") is not True:
        raise ValueError("native causal trace was not captured offline")
    return {
        "path": str(path), **file_identity(path),
        "manifest": {"path": str(manifest_path), **file_identity(manifest_path)},
        "fixture_id": manifest.get("fixture", {}).get("id"),
        "scenario": manifest.get("run", {}).get("requested_scenario"),
        "cycles": manifest.get("run", {}).get("cycle_limit"),
        "seed": manifest.get("run", {}).get("initialization_seed"),
        "tracer_sha256": manifest.get("harness", {}).get("tracer", {}).get("sha256"),
    }


def _verified_process_text(packet: dict[str, Any], stream: str) -> str:
    record = packet.get("java_process_output", {}).get(stream)
    if not isinstance(record, dict) or not record.get("path"):
        return ""
    path = Path(record["path"])
    if not path.is_file() or file_identity(path) != {
            "bytes": record.get("bytes"), "sha256": record.get("sha256")}:
        return ""
    return path.read_text(encoding="utf-8", errors="replace")


def _highlight_trace(packet: dict[str, Any]) -> str:
    lines = []
    for highlight in packet.get("java_diagnostic_highlights", []):
        if highlight.startswith("path "):
            body = highlight[5:].replace("fixture=", "cycle=")
            lines.append("JBNEPATH " + body)
        elif highlight.startswith("step "):
            body = highlight[5:].replace("fixture=", "cycle=")
            lines.append("JBNESTEP " + body)
    return "\n".join(lines)


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


def _rule_space(finding: dict[str, Any]) -> list[dict[str, Any]]:
    oracle, java = finding.get("oracle"), finding.get("java")
    rules = []
    if isinstance(oracle, (int, float)) and isinstance(java, (int, float)):
        delta = oracle - java
        rules.extend([
            {"family": "offset", "expression": f"java + ({delta})",
             "parameter": delta},
            {"family": "rounding-boundary", "expression": "native integer boundary",
             "observed_delta": delta},
            {"family": "event-order", "expression": "apply one transition earlier/later",
             "observed_delta": delta},
        ])
    elif finding.get("kind") == "sync_rng":
        rules.extend([
            {"family": "missing-draw", "expression": "draw at focused action entry"},
            {"family": "extra-draw", "expression": "remove draw at focused boundary"},
            {"family": "draw-order", "expression": "move draw across transition"},
        ])
    else:
        rules.extend([
            {"family": "transition-guard", "expression": "change one native guard"},
            {"family": "transition-order", "expression": "reorder adjacent events"},
        ])
    return rules


def _rng_ledger(plan: dict[str, Any], packet: dict[str, Any], case_id: str,
        cycle: int, native_causal: list[Any], java_causal: list[Any],
        native_evidence: dict[str, Any] | None,
        causal_trace_path: Path | None) -> dict[str, Any] | None:
    """Follow an HP finding upstream into the two engines' draw ledgers.

    Only when the ranked plan asks for it, which is when the hit points fell
    on the same cycles the same number of times for different amounts. An HP
    mismatch is not automatically an RNG mismatch, and running this on a
    building coming up would put a confident-looking report beside a question
    it cannot answer.
    """
    recommended = plan.get("recommended") or {}
    if recommended.get("id") != "async-rng-ledger":
        return None
    return build_ledger(
        draws_from_events(native_causal, "native"),
        draws_from_events(java_causal, "java"),
        native_evidence=native_evidence, case=case_id,
        java_evidence=({"path": str(causal_trace_path),
                        **file_identity(causal_trace_path)}
                       if causal_trace_path is not None else None),
        capture_hint={
            "scenario": packet["case"].get("scenario"),
            "seed": packet["case"].get("seed"),
            "cycles": cycle + 1,
        },
    )


def _focus_slot(packet: dict[str, Any]) -> int | None:
    """The native slot the first finding is about, or the packet's own focus."""
    for finding in packet.get("divergence", {}).get("findings", []):
        if isinstance(finding.get("unit"), int):
            return finding["unit"]
    slots = packet.get("focus_native_slots") or []
    return slots[0] if slots else None


def _focus_java_unit(packet: dict[str, Any], slot: int | None) -> int | None:
    for cycle_text in sorted(packet.get("semantic", {}), key=int):
        for entry in packet["semantic"][cycle_text].get("focus", []):
            if entry.get("native_slot") == slot \
                    and isinstance(entry.get("java_id"), int):
                return entry["java_id"]
    return None


def _state_machine(packet: dict[str, Any], slot: int | None,
        java_causal: list[Any]) -> tuple[list[dict[str, Any]],
                                         dict[str, Any] | None]:
    """Mine the focused unit's record, but only when its shape asks for it.

    Two returns, because the signals are needed before the plan is ranked and
    the report is only worth writing if they fired. Running this on every
    divergence would attach a confident-looking state report to the many that
    are one wrong number on one cycle.
    """
    if slot is None:
        return [], None
    samples = samples_from_packet(packet, slot)
    if len(samples) < 3:
        return [], None
    fired = state_machine_signals(mine(samples))
    if not fired:
        return [], None
    return fired, analyse_state_machine(
        packet, slot, java_events=java_causal or None,
        java_unit=_focus_java_unit(packet, slot),
    )


def _packet_analysis(packet: dict[str, Any], packet_path: Path,
        output: Path, executable: Path | None,
        causal_trace_path: Path | None = None,
        native_trace_path: Path | None = None,
        coverage_baseline_tokens: set[str] | None = None) -> dict[str, Any]:
    case_id = packet["case"]["id"]
    cycle = int(packet["divergence"]["cycle"])
    first_finding = packet["divergence"].get("findings", [{}])[0]
    event_kind = _kind_for_finding(first_finding)
    native_packet, java_packet = events_from_packet(packet)
    native_diag = parse_native_trace("\n".join(
        str(item) for item in packet.get("native_diagnostic_events", [])
    ))
    native_causal = []
    native_evidence = None
    if native_trace_path is not None:
        native_evidence = verified_native_trace(native_trace_path)
        if native_evidence["scenario"] != packet["case"].get("scenario") \
                or native_evidence["seed"] != packet["case"].get("seed"):
            raise ValueError(f"native causal trace does not match case {case_id}")
        if not isinstance(native_evidence["cycles"], int) \
                or native_evidence["cycles"] < cycle:
            raise ValueError(f"native causal trace ends before {case_id} divergence")
        native_causal = parse_native_trace(
            native_trace_path.read_text(encoding="utf-8", errors="replace"),
            source=str(native_trace_path),
        )
    java_text = _verified_process_text(packet, "stderr") + "\n" \
        + _verified_process_text(packet, "stdout") + "\n" + _highlight_trace(packet)
    java_diag = parse_java_trace(java_text)
    java_causal = []
    if causal_trace_path is not None:
        java_causal = parse_causal_jsonl(
            causal_trace_path.read_text(encoding="utf-8"), expected_side="java",
            source=str(causal_trace_path),
        )
    # Prefer a narrow causal twin for the divergent event family. Full mixed
    # traces would let unrelated initialization noise dominate edit distance.
    native_family = [item for item in native_causal
                     if item.kind == event_kind
                     and (item.cycle is None or item.cycle <= cycle)]
    java_family = [item for item in java_causal
                   if item.kind == event_kind
                   and (item.cycle is None or item.cycle <= cycle)]
    native_kinds = {item.kind for item in native_diag}
    comparable_java = java_causal or java_diag
    comparable_kinds = {item.kind for item in comparable_java}
    if native_family and java_family:
        native_events, java_events = native_family, java_family
        alignment_source = "causal-twin"
    elif native_diag and comparable_java and native_kinds & comparable_kinds:
        native_events, java_events = native_diag, comparable_java
        alignment_source = "diagnostic-events"
    else:
        native_events, java_events = native_packet, java_packet
        alignment_source = "packet-observations"
    alignment = align_events(native_events, java_events)
    alignment["source"] = alignment_source
    causal_minimized = minimize_causal_slice(native_events, java_events)
    focus_slot = _focus_slot(packet)
    fired, state_machine = _state_machine(packet, focus_slot, java_causal)
    evidence = {**hp_evidence(packet), "state_machine_signals": fired}
    plan = default_investigation_plan(
        case_id, cycle, packet["divergence"].get("findings", []),
        str(packet_path), evidence=evidence,
    )
    rng_ledger = _rng_ledger(
        plan, packet, case_id, cycle, native_causal, java_causal,
        native_evidence, causal_trace_path,
    )
    minimization = plan_from_packet(packet)
    try:
        counterfactual = counterfactual_plan_from_packet(packet)
    except ValueError as error:
        # A player-bank divergence names no unit, so the counterfactual planner
        # has nothing to pair and used to raise. That abort killed the whole lab
        # compose and left .bne-lab pointing at an older triage, which is why an
        # unsupported shape now reports itself the way branch witness does.
        counterfactual = {
            "schema": LAB_SCHEMA, "case": case_id, "cycle": cycle,
            "supported": False, "reason": str(error),
            "candidates": [], "candidate_count": 0,
            "automatic_source_changes": False,
        }
    try:
        branch_witness = branch_witness_plan_from_packet(packet)
    except ValueError as error:
        branch_witness = {
            "schema": LAB_SCHEMA, "case": case_id, "supported": False,
            "reason": str(error), "automatic_source_changes": False,
        }
    rules = _rule_space(first_finding)
    tournament = plan_from_rules(rules, case_id=case_id, cycle=cycle)
    function = experiment_spec(event_kind)
    static = []
    if executable is not None:
        for address in KIND_ADDRESSES.get(event_kind, [])[:1]:
            static.append(analyze_function(executable, address))
    replays = []
    variants = []
    if event_kind in ("rng.sync.draw", "rng.async.draw") and native_family:
        before = native_family[0].fields.get("before")
        address = KIND_ADDRESSES[event_kind][0]
        if isinstance(before, int):
            replays.append(replay_known_function(address, {"seed": before}))
            variants = boundary_variants({"seed": before})
    coverage = coverage_report(
        native_diag + native_causal + java_diag + java_causal,
        baseline_tokens=coverage_baseline_tokens or (),
    )
    coverage["command_generation"] = command_variants_from_packet(packet)
    coverage["uncovered_control_transfers"] = [
        item for function_record in static
        for item in uncovered_control_transfers(
            function_record, coverage.get("tokens", [])
        )
    ]
    case_root = output / "cases" / case_id
    _write_json(case_root / "causal-alignment.json", alignment)
    _write_json(case_root / "causal-minimized.json", causal_minimized)
    _write_text(case_root / "CAUSE.md", format_alignment(alignment))
    _write_json(case_root / "experiment-plan.json", plan)
    _write_json(case_root / "minimization-plan.json", minimization)
    _write_json(case_root / "counterfactual-plan.json", counterfactual)
    _write_json(case_root / "branch-witness-plan.json", branch_witness)
    _write_json(case_root / "coverage-plan.json", coverage)
    _write_json(case_root / "rule-space.json", {
        "schema": LAB_SCHEMA, "rules": rules,
        "requires_counterexample_gate": True,
    })
    _write_json(case_root / "tournament-plan.json", tournament)
    if rng_ledger is not None:
        _write_json(case_root / "RNG-DIFF.json", rng_ledger)
        _write_text(case_root / "RNG-DIFF.md", format_ledger(rng_ledger))
    if state_machine is not None:
        _write_json(case_root / "STATE-MACHINE.json", state_machine)
        _write_text(case_root / "STATE-MACHINE.md",
                    format_state_report(state_machine))
    _write_json(case_root / "function-lab.json", {
        **function, "static_analysis": static,
        "concrete_replays": replays,
        "boundary_variants": variants,
    })
    return {
        "case": case_id, "cycle": cycle, "event_kind": event_kind,
        "packet": str(packet_path),
        "alignment": str((case_root / "causal-alignment.json").relative_to(output)),
        "causal_minimized": str(
            (case_root / "causal-minimized.json").relative_to(output)
        ),
        "cause": str((case_root / "CAUSE.md").relative_to(output)),
        "experiment": str((case_root / "experiment-plan.json").relative_to(output)),
        "minimization": str((case_root / "minimization-plan.json").relative_to(output)),
        "counterfactual": str(
            (case_root / "counterfactual-plan.json").relative_to(output)
        ),
        "branch_witness": str(
            (case_root / "branch-witness-plan.json").relative_to(output)
        ),
        "coverage": str((case_root / "coverage-plan.json").relative_to(output)),
        "rules": str((case_root / "rule-space.json").relative_to(output)),
        "tournament": str((case_root / "tournament-plan.json").relative_to(output)),
        "function_lab": str((case_root / "function-lab.json").relative_to(output)),
        "rng_ledger": (str((case_root / "RNG-DIFF.json").relative_to(output))
                       if rng_ledger is not None else None),
        "rng_ledger_report": (str((case_root / "RNG-DIFF.md").relative_to(output))
                              if rng_ledger is not None else None),
        "rng_ledger_classification": (rng_ledger.get("classification")
                                      if rng_ledger is not None else None),
        "state_machine": (
            str((case_root / "STATE-MACHINE.json").relative_to(output))
            if state_machine is not None else None),
        "state_machine_report": (
            str((case_root / "STATE-MACHINE.md").relative_to(output))
            if state_machine is not None else None),
        "state_machine_signals": [signal["signal"] for signal in fired],
        "focus_native_slot": focus_slot,
        "hp_evidence": evidence,
        "first_causal_divergence": alignment.get("first_divergence"),
        "alignment_source": alignment_source,
        "context_reduction_percent": causal_minimized.get("reduction_percent", 0.0),
        "native_evidence": native_evidence,
        "coverage_summary": {
            "events": coverage["event_count"],
            "tokens": coverage["token_count"],
            "novel": coverage["novel_count"],
            "uncovered_control_transfers": len(
                coverage["uncovered_control_transfers"]
            ),
        },
        "function_replay_count": len(replays),
        "tournament_candidate_count": len(tournament.get("candidates", [])),
        "counterfactual_candidate_count": counterfactual["candidate_count"],
        "recommended_experiment": plan.get("recommended", {}).get("id")
            if plan.get("recommended") else None,
        "information_gain": plan.get("recommended", {}).get("information_gain")
            if plan.get("recommended") else None,
    }


def _summary(manifest: dict[str, Any]) -> str:
    lines = [
        "# BNE Parity Lab", "",
        f"- Lab run: `{manifest['request_sha256']}`",
        f"- Triage run: `{manifest['request']['triage_request_sha256']}`",
        f"- Cases prepared: **{len(manifest['cases'])}**",
        f"- Failure-atlas matches: **{sum(len(item.get('similar', [])) for item in manifest['cases'])}**",
        "",
    ]
    for item in manifest["cases"]:
        lines.extend([
            f"## `{item['case']}` @ {item['cycle']}", "",
            f"- Event family: `{item['event_kind']}`",
            f"- Causal report: `{item['cause']}`",
            f"- Alignment: `{item['alignment_source']}`; causal context reduced "
            f"**{item['context_reduction_percent']}%**",
            f"- Best next experiment: `{item['experiment']}`",
            f"- Coverage: **{item['coverage_summary']['tokens']}** tokens, "
            f"**{item['coverage_summary']['novel']}** novel",
            f"- Minimization plan: `{item['minimization']}`",
            f"- Counterfactual replay: `{item['counterfactual']}` "
            f"(**{item['counterfactual_candidate_count']}** bounded candidates)",
            f"- Native writer/branch capture: `{item['branch_witness']}`",
            f"- Candidate tournament: `{item['tournament']}`",
            "",
        ])
        if item.get("rng_ledger"):
            lines.extend([
                f"- Asynchronous RNG ledger: `{item['rng_ledger_report']}` "
                f"(**{item['rng_ledger_classification']}**)",
                "",
            ])
        if item.get("state_machine"):
            lines.extend([
                f"- Native state machine: `{item['state_machine_report']}` "
                f"(slot `{item['focus_native_slot']}`, signals "
                f"`{', '.join(item['state_machine_signals'])}`)",
                "",
            ])
    lines.extend([
        "The lab selects experiments and candidates but never changes or merges engine code.",
        "The authenticated 52-case regression gate remains the acceptance authority.", "",
    ])
    return "\n".join(lines)


def verify_lab_manifest(run_root: Path, manifest: dict[str, Any],
        request_sha256: str) -> None:
    if manifest.get("schema") != LAB_SCHEMA:
        raise ValueError("unsupported parity-lab manifest schema")
    if manifest.get("request_sha256") != request_sha256 \
            or canonical_digest(manifest.get("request")) != request_sha256:
        raise ValueError("parity-lab request identity changed")
    for relative, expected in manifest.get("artifacts", {}).items():
        path = (run_root / relative).resolve()
        if not path.is_relative_to(run_root.resolve()) \
                or not path.is_file() or file_identity(path) != expected:
            raise ValueError(f"parity-lab artifact identity changed: {relative}")


def _lab_pointer(artifact_root: Path, run_root: Path, manifest_path: Path,
        manifest: dict[str, Any]) -> dict[str, Any]:
    return {
        "schema": LAB_SCHEMA,
        "request_sha256": manifest["request_sha256"],
        "run": str(run_root.relative_to(artifact_root)),
        "manifest": str(manifest_path.relative_to(artifact_root)),
        "manifest_identity": file_identity(manifest_path),
        "triage_request_sha256": manifest["request"]["triage_request_sha256"],
        "cases": len(manifest["cases"]),
    }


def build_lab(triage_run: Path, artifact_root: Path, *,
        executable: Path | None = None,
        native_traces: dict[str, Path] | None = None) -> tuple[int, Path]:
    triage_run = triage_run.expanduser().resolve()
    triage_manifest_path = triage_run / "manifest.json"
    triage = json.loads(triage_manifest_path.read_text(encoding="utf-8"))
    verify_manifest(triage_run, triage, triage["request_sha256"])
    executable_record = None
    if executable is not None:
        executable = executable.expanduser().resolve()
        executable_record = {
            "path": str(executable), "sha256": sha256(executable),
            "bytes": executable.stat().st_size,
        }
        if executable_record["sha256"] != BNE_202_SHA256:
            raise ValueError("parity lab refuses an unpinned native executable")
    verified_native_traces = {
        case: verified_native_trace(path)
        for case, path in sorted((native_traces or {}).items())
    }
    request = {
        "schema": LAB_SCHEMA,
        "implementation": {
            name: file_identity(Path(__file__).with_name(name))
            for name in LAB_IMPLEMENTATION
        },
        "triage_request_sha256": triage["request_sha256"],
        "triage_manifest": {
            "path": str(triage_manifest_path),
            **file_identity(triage_manifest_path),
        },
        "native_executable": executable_record,
        "native_traces": verified_native_traces,
    }
    request_sha256 = canonical_digest(request)
    artifact_root = artifact_root.expanduser().resolve()
    run_root = artifact_root / "runs" / request_sha256
    manifest_path = run_root / "manifest.json"
    if manifest_path.is_file():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        verify_lab_manifest(run_root, manifest, request_sha256)
        _write_json(
            artifact_root / "latest.json",
            _lab_pointer(artifact_root, run_root, manifest_path, manifest),
        )
        return 0, run_root
    run_root.mkdir(parents=True, exist_ok=True)
    attempt = Path(tempfile.mkdtemp(
        prefix=datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-"),
        dir=run_root,
    ))
    cases = []
    alignments = {}
    coverage_history = coverage_baseline(artifact_root / "atlas.sqlite")
    for record in triage.get("packets", []):
        packet_path = _safe(triage_run, record["packet"])
        packet = json.loads(packet_path.read_text(encoding="utf-8"))
        causal_path = _safe(triage_run, record["java_causal_trace"]) \
            if record.get("java_causal_trace") else None
        case = _packet_analysis(
            packet, packet_path, attempt, executable,
            causal_trace_path=causal_path,
            native_trace_path=(native_traces or {}).get(record["case"]),
            coverage_baseline_tokens=coverage_history,
        )
        cases.append(case)
        coverage_history.update(json.loads(
            (attempt / case["coverage"]).read_text(encoding="utf-8")
        ).get("tokens", []))
        alignments[case["case"]] = json.loads(
            (attempt / case["alignment"]).read_text(encoding="utf-8")
        )
    atlas_path = artifact_root / "atlas.sqlite"
    ingest = ingest_run(atlas_path, triage_run, alignments)
    coverage_records = {}
    experiment_records = []
    for case in cases:
        report = json.loads(
            (attempt / case["coverage"]).read_text(encoding="utf-8")
        )
        coverage_records[case["case"]] = record_coverage(
            atlas_path, triage["request_sha256"], case["case"], report,
        )
        case["coverage_atlas"] = coverage_records[case["case"]]
        plan = json.loads(
            (attempt / case["experiment"]).read_text(encoding="utf-8")
        )
        recommended = plan.get("recommended") or {}
        experiment = {
            "id": f"{triage['request_sha256']}:{case['case']}:recommended",
            "request_sha256": triage["request_sha256"],
            "case": case["case"], "status": "recommended",
            "hypothesis": recommended.get("id"),
            "information_gain": recommended.get("information_gain"),
            "result": recommended,
        }
        record_experiment(atlas_path, experiment)
        experiment_records.append(experiment["id"])
        function_record = json.loads(
            (attempt / case["function_lab"]).read_text(encoding="utf-8")
        )
        if function_record.get("concrete_replays"):
            replay_experiment = {
                "id": f"{triage['request_sha256']}:{case['case']}:function-replay",
                "request_sha256": triage["request_sha256"],
                "case": case["case"], "status": "verified",
                "hypothesis": "native-leaf-semantics",
                "result": {
                    "replays": function_record["concrete_replays"],
                    "boundary_variant_count": len(
                        function_record.get("boundary_variants", [])
                    ),
                },
            }
            record_experiment(atlas_path, replay_experiment)
            experiment_records.append(replay_experiment["id"])
    for case in cases:
        packet = json.loads(_safe(triage_run, next(
            item["packet"] for item in triage["packets"]
            if item["case"] == case["case"]
        )).read_text(encoding="utf-8"))
        findings = packet.get("divergence", {}).get("findings", [])
        signature = "|".join(str(findings[0].get(key, ""))
                             for key in ("kind", "unit_type", "field")) \
            if findings else "unknown"
        case["similar"] = similar_failures(
            atlas_path, signature, exclude_request=triage["request_sha256"],
        )
    _write_json(attempt / "atlas-snapshot.json", dashboard_snapshot(atlas_path))
    manifest: dict[str, Any] = {
        "schema": LAB_SCHEMA, "request_sha256": request_sha256,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "request": request,
        "attempt": attempt.name,
        "triage": {
            "gate_passed": triage["gate"]["passed"],
            "frontier": triage["frontier"],
            "counts": triage["candidate"]["counts"],
        },
        "atlas": ingest,
        "coverage_atlas": coverage_records,
        "experiments_recorded": experiment_records,
        "cases": cases,
        "artifacts": inventory_files(run_root, [attempt]),
    }
    _write_text(run_root / "NEXT.md", _summary(manifest))
    manifest["artifacts"]["NEXT.md"] = file_identity(run_root / "NEXT.md")
    _write_json(manifest_path, manifest)
    pointer = _lab_pointer(artifact_root, run_root, manifest_path, manifest)
    _write_json(artifact_root / "latest.json", pointer)
    return 0, run_root
