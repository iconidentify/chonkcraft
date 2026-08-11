#!/usr/bin/env python3
"""Fails the build when the suite failed a different *set* of tests than expected.

Why this exists
---------------
The engine has a documented failure set with all three inputs
configured, and both numbers are stable. That is not a broken build: this is a
parity port whose test suite is partly a specification written ahead of the
code, so a test asserting a behaviour nobody has ported yet is doing its job by
failing. ``scripts/ci/parity-clean.txt`` treats LegacyEngine map traces this way
-- a list that only grows on purpose -- and ``check-test-skips.py`` treats
skips this way. Failures had no equivalent. (That file gates nothing and is a
sanity check rather than a statement about correctness: parity is with retail.)

Comparing test identities instead of totals makes this regression check
automatic and prevents one newly passing test from hiding one newly failing
test.

The invariant
-------------
The set of failing tests must be exactly the set recorded in
``expected-failures.txt``. Two things fail the gate and they are different
problems:

  * **a failure that is not in the file** -- a regression, and the usual red;
  * **a file entry that now passes** -- somebody fixed something, which is good
    news that still fails the build, because a baseline nobody prunes drifts
    into fiction. Run ``--write`` and commit the smaller file.

Unlike ``check-test-skips.py`` this compares identities and not counts. A count
cannot tell one test being fixed and another breaking from nothing happening at
all, and that pair is exactly what a long-running parity port produces.

The columns
-----------
Each line is ``verdict<TAB>cites<TAB>test-id``.

``cites`` is **mechanical** and rewritten on every ``--write``: it records
which evidence the test's own source names -- ``native`` for a test citing the
retail binary (``FUN_00...``, an address, "Battle.net", "retail"),
``legacyEngine`` for one naming the legacy engine, and ``neither`` for one
citing no oracle at all. It is a fact about the file, not a judgement about who
is right.

``verdict`` is **human** and is preserved across ``--write``. New entries
arrive as ``unsorted``. The vocabulary, which follows CONTRIBUTING.md's rule
that parity is with the retail Battle.net Edition and LegacyEngine is a default
any retail evidence overrides:

  ``retail-verified``    asserts something read off the retail binary. This is
                         the count worth watching: it falling is BNE parity
                         advancing.
  ``legacyEngine-default``  asserts a LegacyEngine rule with no retail evidence.
                         Kept for coverage, explicitly not truth, and a
                         candidate for re-derivation when somebody reads that
                         part of the binary.
  ``port-bug``           nothing to do with either oracle -- an ordinary defect.
  ``unsorted``           nobody has looked.

A ``cites`` of ``native`` does not make a verdict ``retail-verified``. The
first says the file mentions the binary; the second says somebody checked.

``cites`` is measured over the **whole test class**, not the failing method,
because a method body is not where a test states its evidence -- the class
Javadoc is. So a class holding one BNE-derived test and nine LegacyEngine-derived
ones reports ``native`` for all ten. ``CombatTest`` is exactly that today. The
column is a cheap sorting aid for the current list of 128 and is not evidence of
anything; ``verdict`` is the column that means something.

Usage
-----
    scripts/ci/check-test-failures.py                  # gate
    scripts/ci/check-test-failures.py --write          # re-baseline
    scripts/ci/check-test-failures.py --summary        # counts by verdict
"""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path

DEFAULT_BASELINE = Path("scripts/ci/expected-failures.txt")

VERDICTS = ("retail-verified", "legacyEngine-default", "port-bug", "unsorted")

# Markers in a test's own source that name the retail binary as its evidence.
NATIVE_MARKERS = (
    "FUN_00", "fcn.00", "0x004", "Battle.net", "BNE", "retail", "native",
)

# Markers that explicitly name the legacy engine and nothing more authoritative.
LEGACY_ENGINE_MARKERS = ("LegacyEngine", "upstream")


def failing_tests(root: Path) -> dict[str, str]:
    """Every failing test in every module's Surefire output, id -> module."""
    found: dict[str, str] = {}
    for report in sorted(root.glob("*/target/surefire-reports/*.xml")):
        module = report.relative_to(root).parts[0]
        try:
            tree = ElementTree.parse(report)
        except ElementTree.ParseError:
            continue
        for case in tree.getroot().iter("testcase"):
            if case.find("failure") is None and case.find("error") is None:
                continue
            found[f"{case.get('classname')}#{case.get('name')}"] = module
    return found


def source_of(root: Path, test_id: str) -> str:
    """The test class's own source, or the empty string if it cannot be found."""
    classname = test_id.split("#", 1)[0]
    relative = classname.replace(".", "/") + ".java"
    for module in sorted(p.name for p in root.iterdir() if p.is_dir()):
        candidate = root / module / "src/test/java" / relative
        if candidate.is_file():
            return candidate.read_text(encoding="utf-8", errors="ignore")
    return ""


def cites_for(source: str) -> str:
    if any(marker in source for marker in NATIVE_MARKERS):
        return "native"
    if any(marker in source for marker in LEGACY_ENGINE_MARKERS):
        return "legacyEngine"
    return "neither"


def read_baseline(path: Path) -> dict[str, tuple[str, str]]:
    """test-id -> (verdict, cites). Missing file reads as empty, not as an error."""
    entries: dict[str, tuple[str, str]] = {}
    if not path.is_file():
        return entries
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) != 3:
            sys.exit(f"{path}:{number}: expected three tab-separated fields")
        verdict, cites, test_id = (part.strip() for part in parts)
        if verdict not in VERDICTS:
            sys.exit(f"{path}:{number}: unknown verdict {verdict!r};"
                     f" expected one of {', '.join(VERDICTS)}")
        entries[test_id] = (verdict, cites)
    return entries


HEADER = """\
# The tests this suite is expected to fail, by name.
#
#   verdict\tcites\ttest-id
#
# Read scripts/ci/check-test-failures.py before editing. In short: `cites` is
# mechanical and rewritten by --write; `verdict` is a human judgement and is
# preserved. A new entry arrives as `unsorted` and stays there until somebody
# establishes which it is.
#
# This file only shrinks on purpose. An entry disappearing because a test was
# fixed is good news; an entry disappearing because somebody deleted a test to
# make a run green is the failure this file exists to prevent, so a shrinking
# diff should say in its commit message which test was fixed and how.
"""


def write_baseline(path: Path, root: Path, failing: dict[str, str],
                   previous: dict[str, tuple[str, str]]) -> None:
    lines = [HEADER]
    for test_id in sorted(failing):
        verdict = previous.get(test_id, ("unsorted", ""))[0]
        cites = cites_for(source_of(root, test_id))
        lines.append(f"{verdict}\t{cites}\t{test_id}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path.cwd())
    parser.add_argument("--baseline", type=Path, default=DEFAULT_BASELINE,
                        help="expected-failure file, relative to the repository root")
    parser.add_argument("--write", action="store_true",
                        help="rewrite the baseline from this run")
    parser.add_argument("--summary", action="store_true",
                        help="print counts by verdict and exit")
    args = parser.parse_args()

    root = args.repo_root.resolve()
    path = args.baseline if args.baseline.is_absolute() else root / args.baseline
    previous = read_baseline(path)

    if args.summary:
        by_verdict: dict[str, int] = {}
        for verdict, _ in previous.values():
            by_verdict[verdict] = by_verdict.get(verdict, 0) + 1
        print(f"{len(previous)} expected failures")
        for verdict in VERDICTS:
            print(f"  {by_verdict.get(verdict, 0):4d}  {verdict}")
        return 0

    failing = failing_tests(root)
    if not failing and not args.write:
        print("no Surefire output found; run the tests first", file=sys.stderr)
        return 2

    if args.write:
        write_baseline(path, root, failing, previous)
        print(f"wrote {len(failing)} expected failures to {path.relative_to(root)}")
        return 0

    unexpected = sorted(set(failing) - set(previous))
    fixed = sorted(set(previous) - set(failing))

    print(f"{len(failing)} failing, {len(previous)} expected")
    if unexpected:
        print(f"\n{len(unexpected)} test(s) failed that were not expected to:")
        for test_id in unexpected:
            print(f"  + {test_id}")
    if fixed:
        print(f"\n{len(fixed)} test(s) in the baseline now pass."
              f" Re-baseline with --write and say which in the commit:")
        for test_id in fixed:
            print(f"  - {test_id}  ({previous[test_id][0]})")
    if not unexpected and not fixed:
        print("ok: the same tests failed as last time.")
        return 0
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
