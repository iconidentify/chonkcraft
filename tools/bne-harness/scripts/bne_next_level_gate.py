#!/usr/bin/env python3
"""One fail-closed scorecard for the three next-level BNE parity lanes."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
from typing import Any

import bne_ai_decision_ledger as ai
import bne_ai_conductor as conductor
import bne_campaign_lifecycle as campaign
import bne_combat_lifecycle as combat
import bne_divergence_compiler as divergence
import bne_player_transaction as player
import bne_playtest_explorer as explorer
import bne_replay_outcome as replay
from bne_identity import engine_input_identity


SCHEMA = "chonkcraft-bne-next-level-gate-2"
COMMAND_SCHEMA = "chonkcraft-bne-command-split-report-2"
EXPECTED_COMMAND_CELLS = 240
EXPECTED_PHYSICAL_CELLS = 532
WORK_ORDER_SCHEMA = "chonkcraft-bne-divergence-work-order-1"
COMMAND_SEEDS = {
    "retail-human-01-idle.bnefx":
        "bfdc64dfb4c72e9d6fde172ece2480a5a00789e2a613fd7e1df703e83e7ba847",
    "retail-orc-01-idle.bnefx":
        "cd14b7cacc82e48f19397b154d148be3d62772f5a247198c932d8db5553d941c",
    "retail-xhuman-12-idle.bnefx":
        "6a04e95fa9653bc59261a57ce76756f1af7342a4a6f4f7640298431b9dc3dc71",
}


def load(path: Path | None) -> dict[str, Any] | None:
    if path is None:
        return None
    return json.loads(path.read_text(encoding="utf-8"))


PROGRAM_PATHS = (
    "desktop", "engine", "tools/bne-harness/scripts",
    "tools/bne-harness/src", "tools/bne-harness/tests",
    "tools/bne-harness/ghidra_scripts",
    "tools/bne-harness/player-transaction-requirements.json",
    "tools/bne-harness/ai-fleet-requirements.json",
    "tools/bne-harness/combat-lifecycle-requirements.json",
    "engine/src/main/resources/chonkcraft/missions.tsv",
    "scripts/check-bne-next-level-gate.sh",
    "scripts/capture-bne-ai-cycle.sh", "scripts/deploy-bne-tracer.sh",
    "scripts/jbr",
)


def _file_identity(path: Path) -> dict[str, Any]:
    raw = path.read_bytes()
    return {"path": str(path), "bytes": len(raw),
            "sha256": hashlib.sha256(raw).hexdigest()}


def identity(root: Path, evidence_paths: list[Path] | None = None) \
        -> dict[str, Any]:
    head = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    status = subprocess.check_output(
        ["git", "status", "--porcelain=v1"], cwd=root, text=True)
    diff = subprocess.check_output(
        ["git", "diff", "--binary", "HEAD", "--", *PROGRAM_PATHS],
        cwd=root)
    program = hashlib.sha256()
    program.update(b"next-level-program-v1\0" + head.encode() + b"\0" + diff)
    untracked = subprocess.check_output(
        ["git", "ls-files", "--others", "--exclude-standard", "-z", "--",
         *PROGRAM_PATHS], cwd=root).split(b"\0")
    for raw in sorted(item for item in untracked if item):
        path = root / raw.decode("utf-8", "surrogateescape")
        program.update(b"path\0" + raw + b"\0")
        program.update(path.read_bytes())
    evidence = []
    for path in sorted(set(
            item.expanduser().resolve() for item in (evidence_paths or []))):
        if not path.is_file() or path.is_symlink():
            raise ValueError(f"scorecard evidence is missing or unsafe: {path}")
        evidence.append(_file_identity(path))
    return {
        "head": head,
        "dirty": bool(status),
        "status": status.splitlines(),
        "tracked_diff_sha256": hashlib.sha256(diff).hexdigest(),
        "program_input_sha256": program.hexdigest(),
        "engine": engine_input_identity(root),
        "evidence_inputs": evidence,
    }


def _command_lane(report: dict[str, Any] | None, *,
                  producer_evidence_verified: bool = False) -> dict[str, Any]:
    if report is None:
        return {"complete": False, "debt": "no current 240-cell command report"}
    if report.get("schema") != COMMAND_SCHEMA:
        raise ValueError("command report has the wrong schema")
    values = {name: int(report.get(name, -1)) for name in (
        "generated", "executed_native", "executed_java", "comparable",
        "exact_parity", "materially_divergent", "infrastructure_failure",
        "unmatched_executed", "missing_cells")}
    complete = bool(
        producer_evidence_verified
        and report.get("complete") is True
        and report.get("identity_bound") is True
        and values["generated"] == EXPECTED_COMMAND_CELLS
        and values["executed_native"] == EXPECTED_COMMAND_CELLS
        and values["executed_java"] == EXPECTED_COMMAND_CELLS
        and values["comparable"] == EXPECTED_COMMAND_CELLS
        and values["exact_parity"] == EXPECTED_COMMAND_CELLS
        and values["materially_divergent"] == 0
        and values["infrastructure_failure"] == 0
        and values["missing_cells"] == 0
    )
    result = {
        "complete": complete,
        "producer_evidence_verified": producer_evidence_verified,
        **values,
        "report": report,
    }
    if not producer_evidence_verified:
        result["debt"] = (
            "command summary is diagnostic until its canonical generated-cell "
            "inventory and dual-adapter ledger are reopened")
    return result


def _validated_command_lane(root: Path, report: dict[str, Any] | None,
                            ledger: dict[str, Any] | None,
                            inventory: dict[str, Any] | None) -> dict[str, Any]:
    if report is None:
        return _command_lane(None)
    if ledger is None or inventory is None:
        return _command_lane(report)
    seed_root = (root.resolve() /
                 "tools/bne-harness/work/corpus/campaign-1800/cases")
    seeds: list[dict[str, Any]] = []
    for name, expected in COMMAND_SEEDS.items():
        path = seed_root / name
        if not path.is_file() or path.is_symlink():
            raise ValueError(f"canonical command seed is missing or unsafe: {path}")
        actual = _file_identity(path)["sha256"]
        if actual != expected:
            raise ValueError(f"canonical command seed changed: {path}")
        seeds.append(explorer.seed_from_idle_fixture(path))
    canonical_inventory = explorer.coverage_inventory(
        seeds, max_scenarios=80)
    if inventory != canonical_inventory:
        raise ValueError(
            "command inventory is not the canonical current 240-cell generation")
    recomputed = explorer.split_command_report(ledger, inventory=inventory)
    if report != recomputed:
        raise ValueError(
            "command report does not reproduce from its inventory and ledger")
    return _command_lane(report, producer_evidence_verified=True)


def _canonical_player_requirements(root: Path, requested: Path) \
        -> dict[str, Any]:
    resolved_root = root.resolve()
    canonical = (
        resolved_root /
        "tools/bne-harness/player-transaction-requirements.json"
    ).resolve()
    candidate = (requested if requested.is_absolute()
                 else resolved_root / requested).resolve()
    if candidate != canonical:
        raise ValueError(
            "physical player requirements must be the canonical checked-in manifest")
    requirements = load(canonical)
    required = int((requirements or {}).get("fixed_cell_count", 0))
    if required != EXPECTED_PHYSICAL_CELLS:
        raise ValueError(
            f"physical player transaction manifest must contain exactly "
            f"{EXPECTED_PHYSICAL_CELLS} cells")
    # Re-expand every route here so a hand-edited cell list or omitted hook
    # debt fails before it can become the certification denominator.
    cells = player._requirements_cells(requirements)
    if len(cells) != EXPECTED_PHYSICAL_CELLS \
            or not (requirements.get("required_uninstrumented_routes") or []):
        raise ValueError(
            "canonical physical manifest lost cells or required hook debt")
    return requirements


def _player_certification(document: dict[str, Any] | None,
                          current_engine: str,
                          current_program: str,
                          required_cells: int = 240) -> dict[str, Any]:
    if document is None:
        return {"complete": False, "paired_transactions": 0,
                "required": required_cells,
                "debt": "no paired authenticated native/Java physical transaction certification"}
    if document.get("schema") != player.CERTIFICATION_SCHEMA:
        raise ValueError("player certification has the wrong schema")
    authority = document.get("authority") or {}
    engine = authority.get("java_engine_input_sha256")
    program = authority.get("java_program_input_sha256")
    current = engine == current_engine and program == current_program
    # A detached summary cannot prove the producer receipts from which it was
    # derived.  It remains useful diagnostics, but completion is recomputed in
    # build() from the authenticated native and Java receipts themselves.
    complete = False
    result = dict(document)
    result["complete"] = complete
    result["current_engine"] = current
    result["producer_receipts_verified"] = False
    if not current:
        result["debt"] = (
            "player certification is not bound to the current Java engine "
            "and desktop input program")
    else:
        result["debt"] = (
            "detached player summary is diagnostic; provide its native and "
            "Java producer receipts for recertification")
    return result


def _retained_player_certification(path: Path, requirements: dict[str, Any],
                                   *, repository: Path, pack: Path) \
        -> dict[str, Any]:
    try:
        result = player.validate_proof_store(
            path, requirements, repository=repository, pack=pack)
    except player.ProofError as error:
        raise ValueError(
            f"retained player proof store failed validation: {error}") from error
    result["current_engine"] = True
    return result


def _replay_certification(document: dict[str, Any] | None,
                          current_engine: str,
                          current_program: str) -> dict[str, Any]:
    if document is None:
        return {"complete": False, "exact_records": 0,
                "required_records": replay.AUTHENTICATED_CORPUS_TOTALS[
                    "record_count"],
                "debt": "no full 27-replay native/Java corpus certification"}
    if document.get("schema") != replay.CORPUS_CERTIFICATION_SCHEMA:
        raise ValueError("replay certification has the wrong schema")
    authority = document.get("authority") or {}
    engine = authority.get("java_engine_input_sha256")
    program = authority.get("java_program_input_sha256")
    current = engine == current_engine and program == current_program
    # As with physical transactions, a detached corpus summary is not the
    # evidence.  build() certifies the frozen corpus from all 27 comparison
    # receipts; this fallback can never turn the gate green.
    complete = False
    result = dict(document)
    result["complete"] = complete
    result["current_engine"] = current
    result["producer_reports_verified"] = False
    if not current:
        result["debt"] = (
            "replay certification is not bound to the current Java engine "
            "and replay/input program")
    else:
        result["debt"] = (
            "detached replay summary is diagnostic; provide the frozen "
            "corpus and all producer comparison receipts")
    return result


def _ai_conductor_coverage(document: dict[str, Any] | None,
                           current_engine: str,
                           discovery: dict[str, Any] | None = None,
                           *, report_path: Path | None = None,
                           repository: Path | None = None,
                           pack: Path | None = None) \
        -> dict[str, Any]:
    discovered_fleet = (discovery or {}).get("fleet") or {}
    if discovery is not None and discovery.get("mode") != "read-only-discovery":
        raise ValueError("AI discovery report has the wrong mode")
    if document is None:
        return {"complete": False, "materialized_scenarios": 0,
                "required_scenarios": 52,
                "available_native_captures": int(
                    discovered_fleet.get("existing", 0)),
                "missing_native_captures": int(
                    discovered_fleet.get("missing", 52)),
                "debt": "no current-head 52-scenario AI conductor report"}
    if report_path is None or repository is None or pack is None:
        return {
            "complete": False,
            "current_engine": False,
            "materialized_scenarios": 0,
            "required_scenarios": 52,
            "available_native_captures": int(
                discovered_fleet.get("existing", 0)),
            "missing_native_captures": int(
                discovered_fleet.get("missing", 52)),
            "debt": (
                "detached AI conductor summary is diagnostic only; provide "
                "its retained store for proof-graph validation"),
        }
    try:
        document = conductor.validate_retained_report(
            document, store=report_path.resolve().parent,
            repository=repository.resolve(), pack=pack.resolve())
    except (conductor.EvidenceError, OSError, ValueError) as exc:
        raise ValueError(
            f"AI conductor retained proof failed validation: {exc}") from exc
    if document.get("schema") != conductor.REPORT_SCHEMA \
            or document.get("authority_sha256") != conductor.PINNED:
        raise ValueError("AI conductor report has the wrong schema or authority")
    engine = (document.get("engine_identity") or {}).get(
        "engine_input_sha256")
    current = engine == current_engine
    runs = document.get("runs") or []
    scenarios = {str(run.get("scenario") or "").casefold() for run in runs}
    fleet = document.get("fleet") or {}
    summary = document.get("summary") or {}
    certification = document.get("certification") or {}
    denominator = int(summary.get("denominator", 0))
    state_exact = int(summary.get("state_exact", -1))
    telemetry_exact = int(summary.get("telemetry_exact", -1))
    complete = bool(
        current and int(fleet.get("required", -1)) == 52
        and int(fleet.get("existing", -1)) == 52
        and int(fleet.get("missing", -1)) == 0
        and int(fleet.get("materialized", -1)) == 52
        and len(scenarios) == 52 and len(runs) == 52
        and denominator > 0 and state_exact == denominator
        and telemetry_exact == denominator
        and all((run.get("comparison") or {}).get("identical") is True
                for run in runs)
        and certification.get("complete") is True
    )
    return {
        "complete": complete, "current_engine": current,
        "required_scenarios": 52, "materialized_scenarios": len(scenarios),
        "available_native_captures": int(
            discovered_fleet.get("existing", fleet.get("existing", 0))),
        "missing_native_captures": int(
            discovered_fleet.get("missing", fleet.get("missing", 52))),
        "fleet": fleet, "summary": summary,
        "certification": certification,
        "discovery": discovered_fleet,
        "next": document.get("next") or [],
        "debt": None if complete else (
            "materialize and compare all 52 AI scenarios with exact state and telemetry"),
    }


def _open_work_order(document: dict[str, Any] | None) -> dict[str, Any]:
    if document is None:
        return {"state": "absent", "engine_edit_allowed": False}
    if document.get("schema") != WORK_ORDER_SCHEMA:
        raise ValueError("divergence work order has the wrong schema")
    acceptance = document.get("acceptance") or {}
    return {
        "state": str(document.get("state") or "invalid"),
        "engine_edit_allowed": acceptance.get("engine_edit_allowed") is True,
        "request_sha256": document.get("request_sha256"),
        "mismatch": document.get("mismatch"),
        "native": document.get("native"),
        "static_slice": document.get("static_slice"),
        "micro_oracle": document.get("micro_oracle"),
        "witness_gate": document.get("witness_gate"),
    }


def _resolved_work_order(pointer: Path | None,
                         legacy_document: dict[str, Any] | None) \
        -> dict[str, Any]:
    if pointer is not None:
        work, run = divergence.resolve_pointer(pointer.parent, pointer)
        result = _open_work_order(work)
        result["authenticated_pointer"] = True
        result["run"] = str(run)
        return result
    if legacy_document is not None:
        result = _open_work_order(legacy_document)
        result["state"] = "legacy-unverified"
        result["engine_edit_allowed"] = False
        result["authenticated_pointer"] = False
        return result
    return {"state": "absent", "engine_edit_allowed": False,
            "authenticated_pointer": False}


def build(args: argparse.Namespace) -> dict[str, Any]:
    evidence_paths: list[Path] = []
    for name in (
            "command_report", "command_ledger", "command_inventory",
            "player_certification", "replay_certification",
            "replay_corpus",
            "ai_conductor_report", "ai_discovery", "native_ai", "java_ai",
            "campaign_proof", "divergence_work_order", "divergence_pointer"):
        value = getattr(args, name, None)
        if value is not None:
            evidence_paths.append(value)
    if getattr(args, "command_report", None) is not None:
        command_seed_root = (
            args.root.resolve() /
            "tools/bne-harness/work/corpus/campaign-1800/cases")
        evidence_paths.extend(
            command_seed_root / name for name in COMMAND_SEEDS)
    evidence_paths.extend(getattr(args, "player_transaction", []) or [])
    evidence_paths.extend(getattr(args, "replay_report", []) or [])
    evidence_paths.extend(getattr(args, "combat_proof", []) or [])
    gate_identity = identity(args.root, evidence_paths)
    current_engine = str(gate_identity["engine"]["engine_input_sha256"])
    current_program = str(gate_identity["program_input_sha256"])

    requirements = _canonical_player_requirements(
        args.root, args.player_requirements)
    physical_required = int(requirements.get("fixed_cell_count", 0))
    player_receipts = [load(path) for path in args.player_transaction]
    native_player_receipts = [receipt for receipt in player_receipts
                              if (receipt.get("authority") or {}).get("side")
                              == "native"]
    java_player_receipts = [receipt for receipt in player_receipts
                            if (receipt.get("authority") or {}).get("side")
                            == "java"]
    diagnostic_coverage = (player.coverage(player_receipts, requirements)
                           if player_receipts else {
                               "complete": False, "transactions": 0,
                               "debt": "no physical transaction receipts",
                           })
    command = _validated_command_lane(
        args.root, load(args.command_report),
        load(getattr(args, "command_ledger", None)),
        load(getattr(args, "command_inventory", None)))
    player_store = getattr(args, "player_proof_store", None)
    if player_store is not None:
        paired = _retained_player_certification(
            player_store, requirements, repository=args.root,
            pack=args.asset_pack)
    elif native_player_receipts and java_player_receipts:
        paired = player.certify(
            native_player_receipts, java_player_receipts, requirements,
            current_java_engine_input_sha256=current_engine,
            current_java_program_input_sha256=current_program)
        paired["current_engine"] = True
    else:
        paired = _player_certification(load(args.player_certification),
                                       current_engine, current_program,
                                       physical_required)
    replay_reports = [load(path) for path in args.replay_report]
    replay_corpus = load(args.replay_corpus)
    if replay_corpus is not None and replay_reports:
        replay_coverage = replay.certify_corpus(
            replay_corpus, replay_reports,
            current_java_engine_input_sha256=current_engine,
            current_java_program_input_sha256=current_program)
        replay_coverage["current_engine"] = True
    else:
        replay_coverage = _replay_certification(
            load(args.replay_certification), current_engine, current_program)
    player_lane = {
        "complete": bool(command["complete"] and paired["complete"]
                         and replay_coverage["complete"]),
        "resolved_command_matrix": command,
        "physical_transactions": paired,
        "unpaired_receipt_diagnostics": diagnostic_coverage,
        "replay_twin": replay_coverage,
    }

    ai_fleet = _ai_conductor_coverage(
        load(args.ai_conductor_report), current_engine,
        load(args.ai_discovery), report_path=args.ai_conductor_report,
        repository=args.root, pack=args.asset_pack)
    native = load(args.native_ai)
    java = load(args.java_ai)
    ai_state_difference = (ai.first_state_difference(native, java)
                           if native and java else None)
    ai_telemetry_difference = (ai.first_telemetry_difference(native, java)
                               if native and java else None)
    native_coverage = ai.coverage_report(native) if native else {
        "complete": False, "debt": "no authenticated per-cycle native AI ledger"}
    java_coverage = ai.coverage_report(java) if java else {
        "complete": False, "debt": "no current-head per-cycle Java AI ledger"}
    combat_requirements = combat.load_requirements(args.combat_requirements)
    combat_proofs = [loaded for path in (args.combat_proof or [])
                     if (loaded := load(path))]
    combat_coverage = (combat.coverage(combat_requirements, *combat_proofs)
                       if combat_proofs else {
                           "complete": False,
                           "exact": 0,
                           "required": combat_requirements["required_cells"],
                           "debt": "no pinned-native combat lifecycle proof receipt",
                       })
    combat_authority = (combat_proofs[0] if combat_proofs else {}).get(
        "authority") or {}
    combat_current = bool(
        combat_proofs
        and combat_authority.get("native_executable_sha256")
        == conductor.PINNED
        and combat_authority.get("java_engine_input_sha256") == current_engine
        and combat_authority.get("requirements_sha256")
        == _file_identity(args.combat_requirements.resolve())["sha256"]
    )
    combat_coverage["current_authority"] = combat_current
    combat_coverage["complete"] = bool(
        combat_coverage.get("complete") and combat_current)
    ai_complete = bool(ai_fleet["complete"] and combat_coverage["complete"])
    ai_lane = {
        "complete": ai_complete,
        "fleet": ai_fleet,
        "legacy_window_diagnostics_only": {
        "native": native_coverage,
        "java": java_coverage,
        "state_exact": bool(native and java and ai_state_difference is None),
        "telemetry_exact": bool(
            native and java and ai_telemetry_difference is None),
        "first_state_difference": ai_state_difference,
        "first_telemetry_difference": ai_telemetry_difference,
        },
        "combat_lifecycle": combat_coverage,
    }

    inventory = campaign.inventory(args.catalog)
    proof = load(args.campaign_proof)
    if proof is None:
        campaign_coverage = {
            "complete": False, "exact": 0, "required": inventory["triggers"],
            "debt": "no pinned-native lifecycle proof receipt",
        }
    else:
        campaign_coverage = campaign.coverage(inventory, proof)
    campaign_authority = (proof or {}).get("authority") or {}
    campaign_current = bool(
        proof
        and campaign_authority.get("native_executable_sha256")
        == conductor.PINNED
        and campaign_authority.get("java_engine_input_sha256") == current_engine
        and campaign_authority.get("catalog_sha256")
        == _file_identity(args.catalog.resolve())["sha256"]
    )
    campaign_coverage["current_authority"] = campaign_current
    campaign_coverage["complete"] = bool(
        campaign_coverage.get("complete") and campaign_current)
    campaign_lane = {
        "complete": campaign_coverage["complete"],
        "inventory": {key: inventory[key] for key in (
            "missions", "triggers", "action_counts")},
        "coverage": campaign_coverage,
        "save_schema": 4,
        "save_resume_state": ["armed triggers", "native flags", "delay counters"],
    }

    work_order = _resolved_work_order(
        args.divergence_pointer, load(args.divergence_work_order))
    complete = all(lane["complete"] for lane in (
        player_lane, ai_lane, campaign_lane))
    next_item = next_work(player_lane, ai_lane, campaign_lane, work_order)
    return {
        "schema": SCHEMA,
        "complete": complete,
        "identity": gate_identity,
        "lanes": {
            "player-transactions": player_lane,
            "ai-combat-decisions": ai_lane,
            "campaign-lifecycle": campaign_lane,
        },
        "automation": {"divergence_work_order": work_order},
        "next": next_item,
    }


def next_work(player_lane: dict[str, Any], ai_lane: dict[str, Any],
              campaign_lane: dict[str, Any],
              work_order: dict[str, Any] | None = None) -> dict[str, Any]:
    queue: list[dict[str, Any]] = []
    command = player_lane["resolved_command_matrix"]
    physical = player_lane["physical_transactions"]
    if not physical["complete"]:
        physical_required = physical.get(
            "minimum_paired_transactions", physical.get("required", 240))
        physical_debt = physical.get("debt")
        queue.append({
            "lane": "player-transactions", "stage": "physical-capture",
            "reason": (f"{physical.get('paired_transactions', 0)}/"
                       f"{physical_required} paired "
                       "gesture-to-terminal transactions exact"
                       + (f"; {physical_debt}" if physical_debt else "")),
            "command": ("python3 tools/bne-harness/scripts/"
                        "bne_player_transaction.py coverage --help"),
        })
    replay_lane = player_lane["replay_twin"]
    if not replay_lane["complete"]:
        replay_debt = replay_lane.get("debt")
        queue.append({
            "lane": "replay-twin", "stage": "full-corpus-execution",
            "reason": (f"{replay_lane.get('exact_records', 0)}/"
                       f"{replay_lane.get('required_records', 764756)} "
                       "dispatcher records exact"
                       + (f"; {replay_debt}" if replay_debt else "")),
            "command": ("python3 tools/bne-harness/scripts/"
                        "bne_replay_outcome.py certify-corpus --help"),
        })
    fleet = ai_lane["fleet"]
    if not fleet["complete"]:
        certification = fleet.get("certification") or {}
        exact_scenarios = min(
            int(certification.get("state_exact_scenarios", 0)),
            int(certification.get("telemetry_exact_scenarios", 0)))
        queue.append({
            "lane": "ai-fleet", "stage": "capture-or-compare",
            "reason": (f"{fleet.get('materialized_scenarios', 0)}/52 "
                       "mission AI twins materialized; "
                       f"{exact_scenarios}/52 state+telemetry exact; "
                       f"{fleet.get('available_native_captures', 0)} native "
                       "captures currently discoverable"),
            "command": "python3 tools/bne-harness/scripts/bne_java.py ai-conductor",
        })
    if not command["complete"]:
        queue.append({
            "lane": "player-command-matrix", "stage": "dual-engine-command",
            "reason": (f"{command.get('exact_parity', 0)}/240 exact; "
                       f"{command.get('comparable', 0)}/240 comparable"),
            "command": "scripts/check-bne-next-level-gate.sh",
        })
    combat_lane = ai_lane["combat_lifecycle"]
    if not combat_lane["complete"]:
        queue.append({
            "lane": "combat-lifecycle", "stage": "causal-twin",
            "reason": (f"{combat_lane.get('exact', 0)}/"
                       f"{combat_lane.get('required', 185)} cells certified"),
            "command": ("python3 tools/bne-harness/scripts/"
                        "bne_combat_lifecycle.py coverage --help"),
        })
    campaign_coverage = campaign_lane["coverage"]
    if not campaign_lane["complete"]:
        queue.append({
            "lane": "campaign-lifecycle", "stage": "trigger-twin",
            "reason": (f"{campaign_coverage.get('exact', 0)}/"
                       f"{campaign_coverage.get('required', 137)} triggers certified"),
            "command": ("python3 tools/bne-harness/scripts/"
                        "bne_campaign_lifecycle.py coverage --help"),
        })
    if work_order and work_order.get("state") not in ("absent", "complete"):
        queue.insert(0, {
            "lane": "decision-lab", "stage": work_order.get("state"),
            "reason": "the current causal divergence work order is not proof-ready",
            "command": "open the retained divergence work-order NEXT.md",
        })
    if not queue:
        queue.append({"lane": "complete", "stage": "certified",
                      "reason": "all fixed denominators are exact and current",
                      "command": "scripts/check-bne-next-level-gate.sh --require-certified"})
    return {"current": queue[0], "queue": queue}


def render_next(result: dict[str, Any]) -> str:
    current = result["next"]["current"]
    lines = [
        "# BNE parity autopilot", "",
        f"- Overall certification: **{'PASS' if result['complete'] else 'OPEN'}**",
        f"- Current engine input: `{result['identity']['engine']['engine_input_sha256']}`",
        f"- Next lane: **{current['lane']} / {current['stage']}**", "",
        current["reason"], "", "```sh", current["command"], "```", "",
        "## Fixed-denominator queue", "",
    ]
    for index, item in enumerate(result["next"]["queue"], 1):
        lines.append(
            f"{index}. **{item['lane']} / {item['stage']}** — {item['reason']}")
    lines.extend(("", "A work order becoming proof-ready authorizes a narrowly "
                  "scoped experiment. It does not certify parity or permit a "
                  "fixture-specific engine rule.", ""))
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--command-report", type=Path)
    parser.add_argument("--command-ledger", type=Path)
    parser.add_argument("--command-inventory", type=Path)
    parser.add_argument("--player-transaction", type=Path, action="append", default=[])
    parser.add_argument("--player-proof-store", type=Path)
    parser.add_argument("--player-certification", type=Path)
    parser.add_argument("--replay-certification", type=Path)
    parser.add_argument("--replay-corpus", type=Path)
    parser.add_argument("--replay-report", type=Path, action="append", default=[])
    parser.add_argument("--player-requirements", type=Path,
                        default=Path("tools/bne-harness/player-transaction-requirements.json"))
    parser.add_argument("--native-ai", type=Path)
    parser.add_argument("--java-ai", type=Path)
    parser.add_argument("--ai-conductor-report", type=Path)
    parser.add_argument("--ai-discovery", type=Path)
    parser.add_argument("--asset-pack", type=Path,
                        default=conductor.DEFAULT_PACK)
    parser.add_argument("--combat-requirements", type=Path,
                        default=Path("tools/bne-harness/combat-lifecycle-requirements.json"))
    parser.add_argument("--combat-proof", type=Path, action="append",
                        default=[])
    parser.add_argument("--catalog", type=Path,
                        default=Path("engine/src/main/resources/chonkcraft/missions.tsv"))
    parser.add_argument("--campaign-proof", type=Path)
    parser.add_argument("--divergence-work-order", type=Path)
    parser.add_argument("--divergence-pointer", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--markdown-output", type=Path)
    parser.add_argument("--require-certified", action="store_true")
    args = parser.parse_args(argv)
    result = build(args)
    rendered = json.dumps(result, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    if args.markdown_output:
        args.markdown_output.parent.mkdir(parents=True, exist_ok=True)
        args.markdown_output.write_text(render_next(result), encoding="utf-8")
    print(rendered, end="")
    return 1 if args.require_certified and not result["complete"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
