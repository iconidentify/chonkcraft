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
import bne_campaign_lifecycle as campaign
import bne_combat_lifecycle as combat
import bne_player_transaction as player
from bne_identity import engine_input_identity


SCHEMA = "chonkcraft-bne-next-level-gate-1"


def load(path: Path | None) -> dict[str, Any] | None:
    if path is None:
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def identity(root: Path) -> dict[str, Any]:
    head = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    status = subprocess.check_output(
        ["git", "status", "--porcelain=v1"], cwd=root, text=True)
    diff = subprocess.check_output(
        ["git", "diff", "--binary", "HEAD", "--", "desktop", "engine",
         "tools/bne-harness/scripts", "tools/bne-harness/src",
         "tools/bne-harness/tests", "tools/bne-harness/player-transaction-requirements.json",
         "tools/bne-harness/combat-lifecycle-requirements.json",
         "scripts/check-bne-next-level-gate.sh",
         "scripts/capture-bne-ai-cycle.sh",
         "scripts/deploy-bne-tracer.sh"], cwd=root)
    program = hashlib.sha256()
    program.update(b"next-level-program-v1\0" + head.encode() + b"\0" + diff)
    untracked = subprocess.check_output(
        ["git", "ls-files", "--others", "--exclude-standard", "-z", "--",
         "desktop", "engine", "tools/bne-harness/scripts", "tools/bne-harness/src",
         "tools/bne-harness/tests", "tools/bne-harness/player-transaction-requirements.json",
         "tools/bne-harness/combat-lifecycle-requirements.json",
         "scripts/check-bne-next-level-gate.sh",
         "scripts/capture-bne-ai-cycle.sh",
         "scripts/deploy-bne-tracer.sh"], cwd=root).split(b"\0")
    for raw in sorted(item for item in untracked if item):
        path = root / raw.decode("utf-8", "surrogateescape")
        program.update(b"path\0" + raw + b"\0")
        program.update(path.read_bytes())
    return {
        "head": head,
        "dirty": bool(status),
        "status": status.splitlines(),
        "tracked_diff_sha256": hashlib.sha256(diff).hexdigest(),
        "program_input_sha256": program.hexdigest(),
        "engine": engine_input_identity(root),
    }


def build(args: argparse.Namespace) -> dict[str, Any]:
    requirements = load(args.player_requirements)
    player_receipts = [load(path) for path in args.player_transaction]
    transaction_coverage = (player.coverage(player_receipts, requirements)
                            if player_receipts else {
                                "complete": False,
                                "transactions": 0,
                                "debt": "no authenticated physical transaction receipts",
                            })
    command = load(args.command_report)
    command_complete = bool(command and command.get("complete")
                            and command.get("exact_parity")
                            == command.get("generated"))
    player_lane = {
        "complete": command_complete and transaction_coverage["complete"],
        "resolved_command_matrix": command,
        "physical_transactions": transaction_coverage,
    }

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
    combat_proof = load(args.combat_proof)
    combat_coverage = (combat.coverage(combat_requirements, combat_proof)
                       if combat_proof else {
                           "complete": False,
                           "exact": 0,
                           "required": combat_requirements["required_cells"],
                           "debt": "no pinned-native combat lifecycle proof receipt",
                       })
    ai_complete = bool(native and java and native_coverage["complete"]
                       and java_coverage["complete"]
                       and ai_state_difference is None
                       and ai_telemetry_difference is None
                       and combat_coverage["complete"])
    ai_lane = {
        "complete": ai_complete,
        "native": native_coverage,
        "java": java_coverage,
        "state_exact": bool(native and java and ai_state_difference is None),
        "telemetry_exact": bool(
            native and java and ai_telemetry_difference is None),
        "first_state_difference": ai_state_difference,
        "first_telemetry_difference": ai_telemetry_difference,
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
    campaign_lane = {
        "complete": campaign_coverage["complete"],
        "inventory": {key: inventory[key] for key in (
            "missions", "triggers", "action_counts")},
        "coverage": campaign_coverage,
        "save_schema": 4,
        "save_resume_state": ["armed triggers", "native flags", "delay counters"],
    }

    complete = all(lane["complete"] for lane in (
        player_lane, ai_lane, campaign_lane))
    return {
        "schema": SCHEMA,
        "complete": complete,
        "identity": identity(args.root),
        "lanes": {
            "player-transactions": player_lane,
            "ai-combat-decisions": ai_lane,
            "campaign-lifecycle": campaign_lane,
        },
        "next": next_work(player_lane, ai_lane, campaign_lane),
    }


def next_work(player_lane: dict[str, Any], ai_lane: dict[str, Any],
              campaign_lane: dict[str, Any]) -> list[str]:
    work: list[str] = []
    if not player_lane["physical_transactions"]["complete"]:
        work.append("capture and compare the next missing physical transaction cell")
    if not player_lane["complete"]:
        work.append("close the highest-volume non-exact command family without a fixture arm")
    if not ai_lane["complete"]:
        work.append("close the first AI or combat-lifecycle causal proof debt")
    if not campaign_lane["complete"]:
        work.append("authenticate the next unproved mission trigger and its save/resume fork")
    return work


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--command-report", type=Path)
    parser.add_argument("--player-transaction", type=Path, action="append", default=[])
    parser.add_argument("--player-requirements", type=Path,
                        default=Path("tools/bne-harness/player-transaction-requirements.json"))
    parser.add_argument("--native-ai", type=Path)
    parser.add_argument("--java-ai", type=Path)
    parser.add_argument("--combat-requirements", type=Path,
                        default=Path("tools/bne-harness/combat-lifecycle-requirements.json"))
    parser.add_argument("--combat-proof", type=Path)
    parser.add_argument("--catalog", type=Path,
                        default=Path("engine/src/main/resources/chonkcraft/missions.tsv"))
    parser.add_argument("--campaign-proof", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--require-certified", action="store_true")
    args = parser.parse_args(argv)
    result = build(args)
    rendered = json.dumps(result, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    return 1 if args.require_certified and not result["complete"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
