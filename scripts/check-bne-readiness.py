#!/usr/bin/env python3
"""Validate or regenerate the machine-checkable BNE readiness report."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
LIBRARY = ROOT / "tools" / "bne-readiness"
sys.path.insert(0, str(LIBRARY))

import bne_readiness


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group()
    action.add_argument("--write", action="store_true", help="regenerate the Markdown report")
    action.add_argument("--summary", action="store_true", help="print grade counts")
    parser.add_argument("--repo-root", type=Path, default=ROOT)
    args = parser.parse_args()

    root = args.repo_root.resolve()
    manifest = root / "tools" / "bne-readiness" / "readiness.json"
    report = root / "docs" / "bne-readiness.md"
    try:
        data = bne_readiness.load(manifest)
        warnings = bne_readiness.validate(data, root)
    except bne_readiness.LedgerError as error:
        print(f"BNE readiness ledger invalid: {error}", file=sys.stderr)
        return 1

    if args.summary:
        counts = bne_readiness.summary(data)
        print(f"{sum(counts.values())} BNE gameplay systems")
        for grade in bne_readiness.GRADES:
            print(f"  {counts[grade]:2d}  {grade}")
        return 0

    rendered = bne_readiness.render(data)
    if args.write:
        report.write_text(rendered, encoding="utf-8")
        print(f"wrote {report.relative_to(root)}")
    elif not report.is_file() or report.read_text(encoding="utf-8") != rendered:
        print("docs/bne-readiness.md is stale; run scripts/check-bne-readiness.py --write",
              file=sys.stderr)
        return 1

    for warning in warnings:
        print(f"warning: {warning}")
    print("BNE readiness ledger and report agree")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
