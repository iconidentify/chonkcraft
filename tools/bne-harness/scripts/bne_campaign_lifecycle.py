#!/usr/bin/env python3
"""Compile and certify the 52-mission BNE campaign lifecycle surface.

The old smoke gate proved only that untouched missions did not end during the
first 30 seconds.  This inventory gives every one of the 137 sealed trigger
programs a stable proof cell.  A proof is complete only when the pinned native
game and Java agree on the action and deciding cycle; mutable flags, countdowns
and diplomacy additionally require a continuous-vs-save/resume witness.
"""

from __future__ import annotations

import argparse
import base64
import json
from collections import Counter
from pathlib import Path
from typing import Any


SCHEMA = "chonkcraft-bne-campaign-lifecycle-1"
PROOF_SCHEMA = "chonkcraft-bne-campaign-lifecycle-proof-1"
MUTABLE_ACTIONS = frozenset({"SET_FLAG", "DELAYED_VICTORY", "DIPLOMACY"})


def decode(value: str) -> str:
    return base64.urlsafe_b64decode(
        value + "=" * ((4 - len(value) % 4) % 4)).decode("utf-8")


def inventory(catalog: Path) -> dict[str, Any]:
    rows: list[dict[str, Any]] = []
    current: str | None = None
    trigger = 0
    missions: list[str] = []
    lines = catalog.read_text(encoding="utf-8").splitlines()
    if not lines or lines[0] != "CHONKCRAFT-MISSIONS\t1":
        raise ValueError("unsupported or missing sealed mission catalog")
    for number, line in enumerate(lines[1:], 2):
        if not line:
            continue
        fields = line.split("\t")
        if fields[0] == "M":
            if current is not None:
                raise ValueError(f"nested mission at line {number}")
            current = decode(fields[1])
            missions.append(current)
            trigger = 0
        elif fields[0] == "T":
            if current is None or len(fields) != 3:
                raise ValueError(f"orphan trigger at line {number}")
            condition = decode(fields[1])
            action = decode(fields[2])
            kind = action.split()[0]
            rows.append({
                "mission": current,
                "trigger": trigger,
                "condition": condition,
                "action": action,
                "action_kind": kind,
                "requires_save_resume": kind in MUTABLE_ACTIONS,
            })
            trigger += 1
        elif fields[0] == "E":
            if current is None:
                raise ValueError(f"orphan mission end at line {number}")
            current = None
    if current is not None:
        raise ValueError("unterminated mission catalog")
    counts = Counter(row["action_kind"] for row in rows)
    if len(missions) != 52 or len(rows) != 137:
        raise ValueError(
            f"catalog is {len(missions)} missions/{len(rows)} triggers, expected 52/137")
    return {
        "schema": SCHEMA,
        "catalog": str(catalog),
        "missions": len(missions),
        "triggers": len(rows),
        "action_counts": dict(sorted(counts.items())),
        "rows": rows,
    }


def coverage(compiled: dict[str, Any], proof: dict[str, Any]) -> dict[str, Any]:
    if compiled.get("schema") != SCHEMA or proof.get("schema") != PROOF_SCHEMA:
        raise ValueError("campaign lifecycle schema mismatch")
    actual: dict[tuple[str, int], dict[str, Any]] = {}
    duplicates: list[dict[str, Any]] = []
    for item in proof.get("rows") or []:
        key = (str(item.get("mission")), int(item.get("trigger", -1)))
        if key in actual:
            duplicates.append({"mission": key[0], "trigger": key[1]})
        actual[key] = item
    debts: list[dict[str, Any]] = []
    exact = 0
    for expected in compiled["rows"]:
        key = (expected["mission"], expected["trigger"])
        item = actual.get(key)
        reasons: list[str] = []
        if item is None:
            reasons.append("missing-proof")
        else:
            if not item.get("native_observed"):
                reasons.append("native-not-observed")
            if not item.get("java_observed"):
                reasons.append("java-not-observed")
            if not item.get("exact"):
                reasons.append("native-java-divergent")
            if expected["requires_save_resume"] and not item.get("save_resume_exact"):
                reasons.append("save-resume-not-exact")
        if reasons:
            debts.append({"mission": key[0], "trigger": key[1],
                          "action_kind": expected["action_kind"],
                          "reasons": reasons})
        else:
            exact += 1
    return {
        "complete": not debts and not duplicates and exact == len(compiled["rows"]),
        "exact": exact,
        "required": len(compiled["rows"]),
        "missions": compiled["missions"],
        "debts": debts,
        "duplicates": duplicates,
    }


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n",
                    encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    inv = sub.add_parser("inventory")
    inv.add_argument("catalog", type=Path)
    inv.add_argument("--output", type=Path)
    check = sub.add_parser("coverage")
    check.add_argument("inventory", type=Path)
    check.add_argument("proof", type=Path)
    args = parser.parse_args(argv)
    if args.command == "inventory":
        result = inventory(args.catalog)
        if args.output:
            write_json(args.output, result)
        print(json.dumps(result if not args.output else {
            "missions": result["missions"], "triggers": result["triggers"],
            "action_counts": result["action_counts"], "output": str(args.output),
        }, indent=2, sort_keys=True))
        return 0
    compiled = json.loads(args.inventory.read_text(encoding="utf-8"))
    proof = json.loads(args.proof.read_text(encoding="utf-8"))
    result = coverage(compiled, proof)
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if result["complete"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
