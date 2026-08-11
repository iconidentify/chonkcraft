#!/usr/bin/env python3
"""Certify the projectile-facing invariant from BNE state evidence.

This is the native half of the catalog-wide visual lifecycle gate.  It reads
the sealed 52-case projectile pool, never a Java trace, and proves that BNE's
legacy nine-entry direction declaration produces the eight facing values
0..7.  The Java half then launches every sprite family along those eight
vectors and verifies the selected cell and mirror.
"""

from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
from typing import Any

from bne_projectile_ledger import read_native_pool


def audit(root: Path) -> dict[str, Any]:
    states = sorted(root.glob("*.state.bin"))
    if len(states) != 52:
        raise ValueError(
            f"BNE visual evidence needs 52 state streams; found {len(states)} in {root}")

    lifetimes = 0
    aimed = 0
    facings: Counter[int] = Counter()
    all_facings: Counter[int] = Counter()
    types: set[int] = set()
    for state in states:
        with state.open("rb") as source:
            native = read_native_pool(source, through=2_000_000)
        lifetimes += len(native["lifetimes"])
        for lifetime in native["lifetimes"]:
            birth = lifetime["birth"]
            types.add(int(birth["type"]))
            all_facings[int(birth["facing"])] += 1
            # Stationary effects have a zero packed aim.  A non-zero aim is
            # the constructor population for which the facing carries a
            # flight orientation.
            if birth["aim_x"] == 0 and birth["aim_y"] == 0:
                continue
            aimed += 1
            facings[int(birth["facing"])] += 1

    observed = set(all_facings)
    if observed != set(range(8)):
        raise ValueError(
            "BNE projectile facing set changed: "
            f"expected 0..7 exactly, observed {sorted(observed)}")
    if lifetimes < 984 or aimed < 638:
        raise ValueError(
            f"BNE visual evidence is incomplete: {lifetimes} lifetimes, {aimed} aimed births")

    return {
        "state_streams": len(states),
        "projectile_lifetimes": lifetimes,
        "aimed_births": aimed,
        "projectile_types": len(types),
        "facing_counts": {str(key): all_facings[key] for key in sorted(all_facings)},
        "directional_facing_counts": {
            str(key): facings[key] for key in sorted(facings)
        },
        "facing_values": sorted(all_facings),
        "facing_8_observed": all_facings[8] > 0,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("state_cache", type=Path)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    report = audit(args.state_cache.resolve())
    if args.json:
        print(json.dumps(report, sort_keys=True))
    else:
        print(
            "BNE visual evidence: "
            f"streams={report['state_streams']} "
            f"lifetimes={report['projectile_lifetimes']} "
            f"aimed={report['aimed_births']} "
            f"facings={report['facing_values']} "
            f"facing8={report['facing_8_observed']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
