#!/usr/bin/env python3
"""Run headless BNE gameplay lanes and seal a machine-readable receipt."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
LIBRARY = ROOT / "tools" / "bne-readiness"
sys.path.insert(0, str(LIBRARY))

import bne_playability
import bne_readiness


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lane", action="append", default=[],
                        help="run one system id; repeat for more than one")
    parser.add_argument("--list", action="store_true", help="show lanes without running them")
    parser.add_argument("--force-red", action="store_true",
                        help="run known-red lanes instead of reporting them blocked")
    parser.add_argument("--timeout", type=int, default=180,
                        help="seconds allowed for each lane")
    parser.add_argument("--receipt", type=Path,
                        default=Path("target/bne-playability/receipt.json"))
    parser.add_argument("--repo-root", type=Path, default=ROOT)
    args = parser.parse_args()
    if args.timeout < 1:
        parser.error("--timeout must be positive")

    root = args.repo_root.resolve()
    try:
        data = bne_readiness.load(root / "tools/bne-readiness/readiness.json")
        bne_readiness.validate(data, root)
        systems = bne_playability.select(data, args.lane or None)
    except (bne_readiness.LedgerError, ValueError) as error:
        print(error, file=sys.stderr)
        return 2

    if args.list:
        for system in systems:
            print(f"{system['id']:<28} {system['grade'].upper():<6} {system['name']}")
        return 0

    output = root / "target" / "bne-playability"
    results = []
    for system in systems:
        print(f"[{system['grade'].upper()}] {system['id']}: {system['gate']['driver']}")
        result = bne_playability.run(
            system, root, output, args.timeout, force_red=args.force_red)
        print(f"  {result['status']}")
        results.append(result)

    sealed = bne_playability.receipt(root, results)
    receipt_path = args.receipt if args.receipt.is_absolute() else root / args.receipt
    bne_playability.write_receipt(receipt_path, sealed)
    print(f"receipt: {receipt_path}")
    print("certified" if sealed["certified"] else "not certified")
    return 0 if sealed["certified"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
