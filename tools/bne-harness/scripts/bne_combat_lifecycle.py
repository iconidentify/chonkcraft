#!/usr/bin/env python3
"""Compile and certify the native/Java combat transaction matrix."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


REQUIREMENTS_SCHEMA = "chonkcraft-bne-combat-lifecycle-requirements-1"
PROOF_SCHEMA = "chonkcraft-bne-combat-lifecycle-proof-1"


def load_requirements(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if value.get("schema") != REQUIREMENTS_SCHEMA:
        raise ValueError("combat lifecycle requirements schema mismatch")
    encounters = value.get("encounters")
    if not isinstance(encounters, list) or not encounters:
        raise ValueError("combat lifecycle matrix has no encounters")
    seen: set[tuple[str, str, str]] = set()
    cells = []
    for encounter in encounters:
        encounter_id = str(encounter.get("id") or "")
        stances = encounter.get("stances") or []
        phases = encounter.get("phases") or []
        if not encounter_id or not stances or not phases:
            raise ValueError(f"incomplete combat encounter {encounter_id!r}")
        for stance in stances:
            for phase in phases:
                key = (encounter_id, str(stance), str(phase))
                if key in seen:
                    raise ValueError(f"duplicate combat lifecycle cell {key}")
                seen.add(key)
                cells.append({
                    "encounter": key[0], "stance": key[1], "phase": key[2],
                    "attacker": encounter.get("attacker"),
                    "defender": encounter.get("defender"),
                })
    return {
        "schema": REQUIREMENTS_SCHEMA,
        "encounters": len(encounters),
        "required_cells": len(cells),
        "cells": cells,
    }


def coverage(requirements: dict[str, Any], proof: dict[str, Any]) \
        -> dict[str, Any]:
    if proof.get("schema") != PROOF_SCHEMA:
        raise ValueError("combat lifecycle proof schema mismatch")
    rows: dict[tuple[str, str, str], dict[str, Any]] = {}
    duplicates = []
    for item in proof.get("rows") or []:
        key = (str(item.get("encounter")), str(item.get("stance")),
               str(item.get("phase")))
        if key in rows:
            duplicates.append({"encounter": key[0], "stance": key[1],
                               "phase": key[2]})
        rows[key] = item
    exact = 0
    debts = []
    for expected in requirements["cells"]:
        key = (expected["encounter"], expected["stance"], expected["phase"])
        item = rows.get(key)
        reasons = []
        if item is None:
            reasons.append("missing-proof")
        else:
            if not item.get("native_observed"):
                reasons.append("native-not-observed")
            if not item.get("java_observed"):
                reasons.append("java-not-observed")
            if not item.get("exact"):
                reasons.append("native-java-divergent")
            if not item.get("causal_order_exact"):
                reasons.append("causal-order-not-exact")
        if reasons:
            debts.append({"encounter": key[0], "stance": key[1],
                          "phase": key[2], "reasons": reasons})
        else:
            exact += 1
    return {
        "complete": not debts and not duplicates
                    and exact == requirements["required_cells"],
        "encounters": requirements["encounters"],
        "exact": exact,
        "required": requirements["required_cells"],
        "debts": debts,
        "duplicates": duplicates,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    inventory = sub.add_parser("inventory")
    inventory.add_argument("requirements", type=Path)
    inventory.add_argument("--output", type=Path)
    check = sub.add_parser("coverage")
    check.add_argument("requirements", type=Path)
    check.add_argument("proof", type=Path)
    args = parser.parse_args(argv)
    required = load_requirements(args.requirements)
    if args.command == "inventory":
        rendered = json.dumps(required, indent=2, sort_keys=True) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(rendered, encoding="utf-8")
        print(rendered, end="")
        return 0
    proof = json.loads(args.proof.read_text(encoding="utf-8"))
    report = coverage(required, proof)
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report["complete"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
