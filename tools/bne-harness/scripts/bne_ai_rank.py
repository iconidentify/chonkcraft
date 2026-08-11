#!/usr/bin/env python3
"""Rank player-macro divergences without mistaking combat fallout for AI policy."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


CASUALTY_FIELDS = {"units", "kills", "razings"}
ECONOMY_FIELDS = {"supply", "buildings"}
RESEARCH_FIELDS = {
    "arrows", "swords", "shields", "ship_attack", "ship_armor",
    "catapult_damage", "ranger_berserker", "marksmanship", "longbow",
    "scouting",
}


def rank(survey: dict[str, Any]) -> dict[str, Any]:
    cases = []
    for case in survey.get("cases", []):
        semantic = case.get("semantic_v2") or {}
        if semantic.get("families") != ["player"]:
            raise ValueError(
                f"{case.get('id', 'case')} is not a player-only semantic-v2 run")
        mismatches = [row for row in semantic.get("mismatches", [])
                      if row.get("family") == "player"]
        casualty_cycle = min(
            (row["cycle"] for row in mismatches
             if row.get("field") in CASUALTY_FIELDS), default=None)
        executive = [row for row in mismatches
                     if row.get("field") not in CASUALTY_FIELDS
                     and (casualty_cycle is None
                          or row["cycle"] < casualty_cycle)]
        first = min(executive, key=lambda row: row["cycle"], default=None)
        if first is None:
            category = "casualty-cascade" if casualty_cycle is not None else "exact"
            cycle = casualty_cycle
        elif first["field"] in RESEARCH_FIELDS:
            category, cycle = "research-policy", first["cycle"]
        elif first["field"] in ECONOMY_FIELDS:
            category, cycle = "economy-policy", first["cycle"]
        else:
            category, cycle = "other-policy", first["cycle"]
        cases.append({"id": case["id"], "category": category,
                      "cycle": cycle, "first_executive_mismatch": first,
                      "first_casualty_cycle": casualty_cycle})
    priority = {"research-policy": 0, "economy-policy": 1,
                "other-policy": 2, "casualty-cascade": 3, "exact": 4}
    cases.sort(key=lambda row: (priority[row["category"]],
                                row["cycle"] if row["cycle"] is not None else 10**9,
                                row["id"]))
    counts: dict[str, int] = {}
    for case in cases:
        counts[case["category"]] = counts.get(case["category"], 0) + 1
    return {"schema": 1, "cases": cases, "counts": counts,
            "next": next((case for case in cases
                          if case["category"].endswith("policy")), None)}


def render(result: dict[str, Any]) -> str:
    lines = ["# BNE AI executive ranking", "",
             "Combat-caused unit/kill drift is separated from independent "
             "economy and research policy.", "",
             "| Class | Cases |", "|---|---:|"]
    for category, count in sorted(result["counts"].items()):
        lines.append(f"| {category} | {count} |")
    lines.extend(["", "| Priority | Case | Cycle | Field | Retail | Java |",
                  "|---:|---|---:|---|---:|---:|"])
    policies = [case for case in result["cases"]
                if case["category"].endswith("policy")]
    for index, case in enumerate(policies, 1):
        row = case["first_executive_mismatch"]
        lines.append(f"| {index} | `{case['id']}` | {case['cycle']} | "
                     f"{row['field']} | {row['retail']} | {row['java']} |")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("survey", type=Path)
    parser.add_argument("--json-output", type=Path)
    args = parser.parse_args()
    result = rank(json.loads(args.survey.read_text(encoding="utf-8")))
    if args.json_output:
        args.json_output.write_text(
            json.dumps(result, indent=2, sort_keys=True) + "\n",
            encoding="utf-8")
    print(render(result), end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
