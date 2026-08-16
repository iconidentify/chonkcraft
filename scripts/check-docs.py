#!/usr/bin/env python3
"""Keep the current documentation honest.

The gate checks links, filenames mentioned in prose, and skip-count claims
against the executable baselines that own those numbers. It intentionally does
not maintain a historical issue ledger or generated defect index.

    scripts/check-docs.py
    scripts/check-docs.py --check

Both forms are read-only and exit non-zero on any problem, naming the file and
line. The optional flag remains for CI and command-line compatibility.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent

# Markdown links, skipping images and anything with a scheme or a pure anchor.
LINK = re.compile(r"(?<!\!)\[[^\]]*\]\(([^)]+)\)")


def ignored_path(path: pathlib.Path) -> bool:
    parts = path.relative_to(REPO).parts
    return any(part in {"target", ".git", "node_modules", ".venv-micro-oracle"}
               or part.startswith(".bne-") for part in parts)


def markdown_files() -> list[pathlib.Path]:
    """Every tracked-looking markdown file, ignoring build output and checkouts."""
    out = []
    for path in REPO.rglob("*.md"):
        if ignored_path(path):
            continue
        out.append(path)
    return sorted(out)


def check_links(problems: list[str]) -> None:
    for path in markdown_files():
        rel = path.relative_to(REPO)
        for number, line in enumerate(path.read_text().splitlines(), 1):
            for target in LINK.findall(line):
                target = target.strip()
                if re.match(r"^[a-z][a-z0-9+.-]*:", target) or target.startswith("#"):
                    continue
                file_part = target.split("#", 1)[0]
                if not file_part:
                    continue
                resolved = (path.parent / file_part).resolve()
                if not resolved.exists():
                    problems.append(f"{rel}:{number}: link to missing {target}")


# A markdown filename that does not continue into another word. The trailing
# lookahead is load-bearing: without it, a Java field access such as the Opus
# encoder's reference to its MDCT instance matches as far as the extension and
# gets reported as a missing document.
MENTION = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]*\.md(?![A-Za-z0-9_-])")

# Named on purpose and correctly absent from this repository.
EXTERNAL_DOCS = {
    "README.md",
    # Generated into content-addressed parity-lab/triage runs. A clean clone or
    # isolated Git worktree correctly has none of these until its first run.
    "CAUSE.md",
    "NEXT.md",
    "RNG-DIFF.md",
    "STATE-MACHINE.md",
    "PROJECTILE-LEDGER.md",
    "EVIDENCE-CATALOG.md",
    "CAPTURE-PLAN.md",
    "gate.md",
    "clusters.md",
    "frontier.md",
    "ROUTES.md",
    "WHY-CHAIN.md",
    "FLEET.md",
    # Invented inside the harness tests to prove that prose is not an engine
    # input and cannot travel inside a source capsule. Nothing writes them.
    "fixture-prose.md",
    "fixture-scratch.md",
    "fixture-elsewhere.md",
    # Written into every asset pack by the extractor, not a file in the tree.
    "dictionary.md",
    # Upstream's audio design notes, in seven-days-to-tomorrow. runtime/README.md
    # names it to say it is deliberately not copied here.
    "audio-streaming.md",
}


def check_bare_mentions(problems: list[str]) -> None:
    """Catch a document named in prose or comments that no longer exists."""
    known = {path.name for path in markdown_files()} | EXTERNAL_DOCS
    scanned = []
    for pattern in ("*.md", "*.java", "*.sh", "*.py", "*.yml", "*.yaml"):
        scanned += [p for p in REPO.rglob(pattern) if not ignored_path(p)]
    for path in sorted(set(scanned)):
        rel = path.relative_to(REPO)
        try:
            text = path.read_text()
        except UnicodeDecodeError:
            continue
        for number, line in enumerate(text.splitlines(), 1):
            for name in MENTION.findall(line):
                if name not in known:
                    problems.append(
                        f"{rel}:{number}: names {name}, which no longer exists"
                        " (renamed or deleted?)")


SKIP_GATE = REPO / "scripts" / "ci" / "check-test-skips.py"
# `--profile full          # expects 14 skipped` and the like: a profile named
# alongside the number it is expected to produce.
PROFILE_CLAIM = re.compile(r"--profile\s+([a-z-]+)\D{0,40}?(\d{1,4})")


def profile_totals() -> dict[str, int]:
    """Each profile's total expected skips, read from the CI skip gate.

    `scripts/ci/check-test-skips.py` holds the per-module baselines CI asserts
    against, so it is the only honest source for these. Documents quoted them by
    hand and four went stale at once: the workflow said 352 where the gate wanted
    558, and the setup guide said 500 and 518 where it wanted 558 and 576.
    """
    if not SKIP_GATE.exists():
        raise LookupError(f"{SKIP_GATE} is missing; the skip-count check cannot run")
    source = SKIP_GATE.read_text()
    # The declaration is annotated -- `PROFILES: dict[...] = {` -- so a literal
    # search for "PROFILES = {" finds nothing and the check silently passes.
    # That happened, and this check was vacuous until it was tested.
    opening = re.search(r"^PROFILES\s*(?::[^=]+)?=\s*\{", source, re.M)
    if not opening:
        raise LookupError(f"{SKIP_GATE.name}: no PROFILES table found")
    depth, index = 0, source.index("{", opening.start())
    for end in range(index, len(source)):
        depth += (source[end] == "{") - (source[end] == "}")
        if depth == 0:
            break
    namespace: dict = {}
    exec("PROFILES = " + source[index:end + 1], namespace)  # noqa: S102 - our own file
    profiles = namespace["PROFILES"]
    if not profiles:
        raise LookupError(f"{SKIP_GATE.name}: PROFILES is empty")
    return {name: sum(s for _, s in mods.values()) for name, mods in profiles.items()}


def check_profile_claims(problems: list[str]) -> None:
    """Catch a skip count documented beside a profile that the gate disagrees with.

    Deliberately narrow. An earlier version scanned for any number near the word
    "skip" and produced three false positives for every real find -- a line
    number, an illustrative quote, and a figure the text already labelled as
    historical. A number written next to `--profile X` is unambiguously a claim
    about what that profile does, so only those are judged.
    """
    expected = profile_totals()
    for path in markdown_files() + [REPO / ".github" / "workflows" / "tests.yml"]:
        if not path.exists():
            continue
        rel = path.relative_to(REPO)
        for number, line in enumerate(path.read_text().splitlines(), 1):
            # `--profile` is not unique to the skip gate any more: the capture
            # planner and evidence catalog both take one, and `--profile
            # async-rng --through 51` reads as a claim that async-rng expects
            # 51 skips. Judge only lines that are talking about the skip gate,
            # which is what this check was always narrowly about.
            if "check-test-skips" not in line:
                continue
            for profile, claimed in PROFILE_CLAIM.findall(line):
                if profile not in expected:
                    problems.append(f"{rel}:{number}: unknown profile {profile!r};"
                                    f" the gate defines {sorted(expected)}")
                elif int(claimed) != expected[profile]:
                    problems.append(
                        f"{rel}:{number}: says --profile {profile} expects"
                        f" {claimed}; the gate's baselines total"
                        f" {expected[profile]}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                        help="verify only; fail if the index is out of date")
    args = parser.parse_args()

    problems: list[str] = []

    check_links(problems)
    check_bare_mentions(problems)
    check_profile_claims(problems)

    if problems:
        for problem in problems:
            print(problem, file=sys.stderr)
        print(f"\n{len(problems)} documentation problem(s).", file=sys.stderr)
        return 1
    print(f"Documentation checks passed: {len(markdown_files())} markdown files,"
          " all links resolve.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
