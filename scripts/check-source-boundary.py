#!/usr/bin/env python3
"""Keep product source native while preserving attribution in README.md."""

from __future__ import annotations

import subprocess
import sys
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ATTRIBUTION = Path("README.md")
PROJECT_IDENTITIES = (
    "war" + "gus",
    "strata" + "gus",
)
SCRIPT_IDENTITY = re.compile(
    r"(?i)(?<![a-z])" + ("l" + "ua") + r"(?![a-z])|to" + ("l" + "ua")
)


def repository_files() -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=ROOT,
        check=True,
        capture_output=True,
    )
    return [ROOT / item.decode() for item in result.stdout.split(b"\0") if item]


def main() -> int:
    failures: list[str] = []
    for path in repository_files():
        if not path.exists() or path.is_dir():
            continue
        relative = path.relative_to(ROOT)
        lowered_path = str(relative).lower()
        if relative != ATTRIBUTION:
            for marker in PROJECT_IDENTITIES:
                if marker in lowered_path:
                    failures.append(f"forbidden path: {relative}")
            if SCRIPT_IDENTITY.search(str(relative)):
                failures.append(f"forbidden path: {relative}")
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        if relative == ATTRIBUTION:
            continue
        lowered = text.lower()
        for marker in PROJECT_IDENTITIES:
            if marker in lowered:
                for number, line in enumerate(text.splitlines(), 1):
                    if marker in line.lower():
                        failures.append(f"{relative}:{number}: forbidden source identity")
        for number, line in enumerate(text.splitlines(), 1):
            if SCRIPT_IDENTITY.search(line):
                failures.append(f"{relative}:{number}: forbidden source identity")

    if failures:
        print("Source-boundary check failed:", file=sys.stderr)
        for failure in failures:
            print(f"  {failure}", file=sys.stderr)
        return 1
    print("Source boundary PASS: attribution is confined to README.md; native source is clean.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
