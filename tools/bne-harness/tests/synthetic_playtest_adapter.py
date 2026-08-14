#!/usr/bin/env python3
"""Deterministic adapter used to prove the differential explorer end to end."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


RESULT_SCHEMA = "chonkcraft-bne-playtest-result-1"


def canonical_bytes(value: object) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--side", choices=("native", "java"), required=True)
    parser.add_argument("--scenario", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--fault", choices=("none", "attack-cadence"), default="none")
    args = parser.parse_args()
    scenario = json.loads(args.scenario.read_text(encoding="utf-8"))
    observations = []
    events = []
    for index, command in enumerate(scenario["commands"]):
        issued = command["issue_cycle"]
        delay = 5
        if (args.side == "java" and args.fault == "attack-cadence"
                and command["kind"] in {"attack", "attack-ground"}):
            delay = 30
        observations.append({
            "command_index": index,
            "accepted": True,
            "first_progress_cycle": issued + delay,
            "terminal_cycle": issued + delay + 10,
            "terminal_reason": "fulfilled",
            "state": {
                "tile_x": command.get("x", 10),
                "tile_y": command.get("y", 10),
                "order": "STILL",
                "alive": True,
                "on_map": True,
                "missile_count": 1 if command["kind"] in {
                    "attack", "attack-ground"} else 0,
            },
        })
        events.append({
            "cycle": issued + delay,
            "kind": "projectile-created" if command["kind"] in {
                "attack", "attack-ground"} else "order-progress",
            "unit_id": command["unit_id"],
        })
    result = {
        "schema": RESULT_SCHEMA,
        "side": args.side,
        "scenario_sha256": scenario["scenario_sha256"],
        "producer": {
            "name": f"synthetic-{args.side}",
            "build_sha256": hashlib.sha256(args.side.encode()).hexdigest(),
            "authority_sha256": (
                "b0e914a9cb7dcc81a205e700a9bb0a1d0649df19d459388051ba170783d2c807"
                if args.side == "native" else None
            ),
        },
        "observations": observations,
        "events": events,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(canonical_bytes(result))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
