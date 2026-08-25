#!/usr/bin/env python3
"""Fails the build when the test suite skipped a different set of tests than expected.

Why this exists
---------------
This suite does not fail when its external inputs are missing. Tests that need
the Warcraft II data, an asset pack, or the Opus test vectors call JUnit
``Assumptions.assumeTrue(...)`` and skip,
and Maven reports BUILD SUCCESS either way. Measured on one commit, on one
machine, the difference is:

    authenticated inputs       2751 tests,   26 skipped
    no external input          2751 tests, 1153 skipped

Both can be green.

There are **three** inputs. ``full`` means an installation, an asset pack built
from it, and the official Opus test vectors. Without the pack,
``PackParityTest``'s eight skip and the
extractor's real-data tests skip, and the pack path -- which is what a player
actually uses -- goes unexercised; build one with
``scripts/build-asset-pack.sh`` and set ``CHONKCRAFT_ASSET_PACK``. Without the
vectors, 21 of the pure-Java Opus codec's conformance tests skip, and the
claim the codec rests on -- every pure-CELT packet in the official vectors
decodes bit-exact -- goes unchecked; fetch them and set
``-Dopus.testvectors``, as ``assetpack/.../opus/OpusTestVectors.java``
describes. So a CI job that trusts Maven's exit code can verify almost nothing
and still report success, which is worse than having no CI at all: it converts
"nobody is checking" into "something is checking", without either being true.

This script is the thing that makes the exit code mean something. It reads the
Surefire XML rather than Maven's console output, because the XML records what
each module actually ran. Grepping the log cannot tell "the engine module
skipped 226 tests" from "the engine module never ran", and those must not look
alike.

The invariant
-------------
Per module, and in total:

  * skipped must be **exactly** the number this file records
  * tests run must be **at least** the number this file records

The asymmetry is deliberate. Adding a test that always runs raises the run
count and leaves skips alone, and needs no change here. Adding a test that
skips without game data raises the skip count and turns CI red until somebody
comes to this file and writes the new number down. That second case is the one
worth catching: it is how hundreds of tests came to be skippable in the first place,
one at a time, with nothing objecting.

Updating the numbers
--------------------
When they legitimately change, re-measure both profiles rather than
patching the one that failed, and update ``docs/development-setup.md``,
``README.md`` and ``scripts/check-setup.sh`` to match. Measuring is:

    scripts/run-tests.sh -Dwc2.install.dir=/nonexistent
    scripts/ci/check-test-skips.py --profile data-free --write

and for ``full``, all three inputs at once:

    scripts/run-tests.sh -Dwc2.install.dir="$WC2_INSTALL_DIR" \
        -Dchonkcraft.pack="$CHONKCRAFT_ASSET_PACK" \
        -Dopus.testvectors="$OPUS_TESTVECTORS"
    scripts/ci/check-test-skips.py --profile full --write

Usage
-----
    scripts/ci/check-test-skips.py --profile full --repo-root /path/to/repo
    scripts/ci/check-test-skips.py --profile data-free --write   # re-baseline
"""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path

# Every module that must have run. A module missing from the Surefire output
# entirely is a failure, not a zero: it is what a compilation error in a test
# source, or a reactor ordering mistake, looks like from the outside.
#
# There is no `game` module. It was removed from the reactor; it had no sources
# and published an empty jar. See docs/architecture.md.
MODULES = (
    "assetpack", "runtime", "data", "extractor", "launcher", "matchmaking",
    "engine", "desktop", "matchmaker-server",
)

# Expected (tests run, skipped) per module, per profile.
#
# Measured on the authenticated CI runner with JBR 25.0.2 and the pinned
# authenticated retail installation and derived pack.
#
# Twenty-six skip on the current `full` runner. One is release-dependent and
# is described at the `full` profile below.
#
# Seven need a **display**: AppWindowTest's four, and three of
# PlatformFullscreenTest's six. The root pom forces -Djava.awt.headless=true
# into the Surefire argLine and those tests skip on
# GraphicsEnvironment.isHeadless(). They are expected skips forever, unless the
# job runs under xvfb with an empty -Dsurefire.argLine=.
#
# Four fixture-sensitive behaviours are absent from this installation: two in
# the engine and two in the desktop module.
#
# Five need an **optional input this project does not ask anyone to have**: a
# directory of 16-bit WAV files, named by -Dopus.music, which
# assetpack/.../opus/CeltEncoderTest uses as its music fixture. They are the
# encoder's determinism, misalignment, real-music, ffmpeg-interop and
# achieved-bitrate tests. A rip of the red book audio is a heavier ask than the
# other three inputs and a poor default; the encoder is still covered without it
# by the 21 conformance tests the vectors buy, so this is recorded as an
# expected skip rather than promoted to a profile.
PROFILES: dict[str, dict[str, tuple[int, int]]] = {
    # None of the external inputs. The floor: what a newcomer gets by running
    # scripts/run-tests.sh with nothing configured. assetpack's 26 are the 21
    # Opus conformance tests that need the official vectors plus the 5 that
    # need a music fixture. The engine and desktop additions cover BNE-backed
    # movement, projectiles, spells, sounds and rendered feedback. Their
    # authenticated assertions deliberately skip when the retail installation
    # or derived graphics pack is absent.
    "data-free": {
        "assetpack": (256, 26),
        "runtime": (99, 3),
        "data": (139, 26),
        "extractor": (9, 3),
        "launcher": (49, 0),
        "matchmaking": (2, 0),
        # ShoreBuildingTest's accepted-order referee and the authenticated
        # attack-program lifecycle referees deliberately need the retail data
        # or derived pack they drive. They skip here and run in the full
        # profile. The later BNE parity batches deliberately add authenticated
        # movement, combat, AI, resource, save and rendered-client referees;
        # they run in `full` and join this profile's skip inventory.
        # New authenticated handoff referees cover crowded combat, resource,
        # tanker and naval-patrol boundaries. They run with the
        # pack and deliberately join this profile's skip inventory. The
        # production service smoke is opt-in because an ordinary suite run
        # must not mutate or depend on the live room directory.
        "engine": (1847, 831),
        # Seven authenticated multiplayer presentation referees cover shared
        # minimap sight, allied fog seams, restrained ping feedback, the retail
        # five-worker wood-click fan-out, team game-over presentation, and the
        # gold-mine collapse sound at the post-depletion fog boundary.
        "desktop": (345, 263),
        "matchmaker-server": (5, 1),
    },
    # Everything configured. What a developer with the game data should see on
    # the macOS development machine.
    #
    # Every profile here assumes ffmpeg and flac are on PATH. They are not an
    # "input" in the sense the other three are -- nothing is configured to point
    # at them -- but without them FlacInteropTest's seven tests and OggTest's
    # two skip, and those nine are the only checks in the tree that compare
    # this project's FLAC and Ogg writers against the reference decoders. Both
    # CI jobs install them; see .github/workflows/tests.yml.
    #
    # Re-measured 24 August 2026 against the authenticated retail installation
    # and its derived pack. The twenty-six that skip are: five
    # CELT encoder tests wanting a music fixture
    # nobody is asked to have (-Dopus.music), seven window and fullscreen
    # tests in runtime and desktop, four fixture-sensitive checks, five custom-
    # map referees whose maps are absent from the retail pack, three private
    # playtest-save referees, one opt-in production multiplayer smoke, and one
    # release-dependent test described below.
    #
    # ONE OF THE TWENTY-SIX DEPENDS ON WHICH RELEASE THE INSTALLATION IS, and it
    # is the reason to read this note before believing a red gate.
    # SmackerVideoTest.battleNetStereoAudioUsesTheRightByteOrder asks
    # `videos.source().isBattleNetEdition()` and skips on anything else. This
    # baseline is measured on the mounted classic source, so that test skips. A
    # Battle.net Edition source runs it and reports one fewer skip.
    # That is a correct release-dependent difference, not a regression. Nothing here can tell
    # the two apart, because the profile is a pair of numbers per module and
    # the release is not an input the profile knows about.
    "full": {
        "assetpack": (256, 5),
        "runtime": (99, 3),
        "data": (139, 3),
        "extractor": (9, 0),
        "launcher": (49, 0),
        "matchmaking": (2, 0),
        # Three exact-save regressions need the operator's local playtest
        # saves; the other fixture skips name custom maps absent from the
        # retail pack. The production service smoke runs in the deploy
        # workflow instead.
        "engine": (1847, 7),
        "desktop": (345, 7),
        "matchmaker-server": (5, 1),
    },
}

# Reasons a test may state for skipping, and the input that would satisfy it.
# Used only to explain a mismatch; nothing branches on it.
SKIP_HINTS = (
    (re.compile(r"[Ww]arcraft II installation|wc2\.install\.dir|WC2_INSTALL_DIR"),
     "needs the 1995 game data (-Dwc2.install.dir)"),
    (re.compile(r"chonkcraft\.pack|CHONKCRAFT_ASSET_PACK|asset pack"),
     "needs an asset pack (-Dchonkcraft.pack)"),
    (re.compile(r"opus\.testvectors|OPUS_TESTVECTORS"),
     "needs the Opus test vectors (-Dopus.testvectors)"),
    (re.compile(r"opus\.music|OPUS_MUSIC"),
     "needs a music fixture (-Dopus.music)"),
    (re.compile(r"[Hh]eadless|GraphicsEnvironment"),
     "needs a display"),
)


def has_source(repo_root: Path, module: str, class_name: str) -> bool:
    """True if this reported test class still exists as a source file.

    Surefire never deletes a report, and `mvn test` without `clean` does not
    either, so target/surefire-reports accumulates the XML of every test class
    that has ever run in that working copy. This project deletes probe tests
    once they have proved their point -- CONTRIBUTING.md asks for them -- and
    twelve such reports were found lingering on the development machine,
    inflating the total by fifteen tests.

    Counting those would be the repository's own recurring bug: reading an
    artefact that is produced, looks authoritative, and is stale. A test class
    always has a source file, so requiring one is exact rather than a heuristic.
    """
    relative = class_name.split("$")[0].replace(".", "/") + ".java"
    return (repo_root / module / "src" / "test" / "java" / relative).is_file()


def read_reports(repo_root: Path) -> tuple[dict[str, dict[str, tuple[int, int]]], list[str]]:
    """Return {module: {test class: (tests, skipped)}} and the stale reports ignored."""
    found: dict[str, dict[str, tuple[int, int]]] = {}
    stale: list[str] = []
    for module in MODULES:
        directory = repo_root / module / "target" / "surefire-reports"
        if not directory.is_dir():
            continue
        classes: dict[str, tuple[int, int]] = {}
        for report in sorted(directory.glob("TEST-*.xml")):
            try:
                root = ElementTree.parse(report).getroot()
            except ElementTree.ParseError as error:
                raise SystemExit(f"unreadable Surefire report {report}: {error}")
            name = root.get("name", report.stem)
            if not has_source(repo_root, module, name):
                stale.append(f"{module}: {name}")
                continue
            classes[name] = (int(root.get("tests", "0")), int(root.get("skipped", "0")))
        if classes:
            found[module] = classes
    return found, stale


def totals(classes: dict[str, tuple[int, int]]) -> tuple[int, int]:
    return (sum(t for t, _ in classes.values()), sum(s for _, s in classes.values()))


def skip_reasons(repo_root: Path, module: str) -> dict[str, int]:
    """Count skip messages by category, to explain a mismatch to a human."""
    counts: dict[str, int] = {}
    directory = repo_root / module / "target" / "surefire-reports"
    for report in sorted(directory.glob("TEST-*.xml")):
        try:
            root = ElementTree.parse(report).getroot()
        except ElementTree.ParseError:
            continue
        if not has_source(repo_root, module, root.get("name", "")):
            continue
        for case in root.iter("testcase"):
            skipped = case.find("skipped")
            if skipped is None:
                continue
            message = (skipped.get("message") or "") + (skipped.text or "")
            label = "unexplained skip"
            for pattern, description in SKIP_HINTS:
                if pattern.search(message):
                    label = description
                    break
            counts[label] = counts.get(label, 0) + 1
    return counts


def rewrite_profile(script: Path, profile: str, measured: dict[str, tuple[int, int]]) -> None:
    """Rewrite one profile's numbers in this file, for --write."""
    source = script.read_text(encoding="utf-8")
    start = source.index(f'    "{profile}": {{')
    end = source.index("    },", start) + len("    },")
    body = "\n".join(
        f'        "{module}": ({measured[module][0]}, {measured[module][1]}),'
        for module in MODULES if module in measured
    )
    replacement = f'    "{profile}": {{\n{body}\n    }},'
    script.write_text(source[:start] + replacement + source[end:], encoding="utf-8")
    print(f"rewrote profile {profile!r} in {script}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--profile", required=True, choices=sorted(PROFILES),
                        help="which external inputs the run was given")
    parser.add_argument("--repo-root", default=None, type=Path,
                        help="repository root; defaults to two levels above this script")
    parser.add_argument("--write", action="store_true",
                        help="overwrite the expected numbers with what was measured")
    arguments = parser.parse_args()

    script = Path(__file__).resolve()
    repo_root = (arguments.repo_root or script.parent.parent.parent).resolve()
    expected = PROFILES[arguments.profile]
    found, stale = read_reports(repo_root)

    if not found:
        print(f"no Surefire reports under {repo_root}", file=sys.stderr)
        print("The suite did not run. Run scripts/run-tests.sh first.", file=sys.stderr)
        return 2

    if stale:
        print(f"ignoring {len(stale)} stale report(s) whose test source no longer exists;")
        print("run `mvn clean` to clear them:")
        for entry in stale:
            print(f"  {entry}")
        print()

    measured = {module: totals(classes) for module, classes in found.items()}

    if arguments.write:
        rewrite_profile(script, arguments.profile, measured)
        return 0

    problems: list[str] = []
    print(f"profile: {arguments.profile}")
    print(f"{'module':<10}{'tests':>8}{'skipped':>10}   {'expected':>16}")
    for module in MODULES:
        want_tests, want_skipped = expected[module]
        if module not in measured:
            print(f"{module:<10}{'-':>8}{'-':>10}   {want_tests:>7} /{want_skipped:>7}")
            problems.append(
                f"{module}: no Surefire reports at all. The module did not run its tests."
            )
            continue
        got_tests, got_skipped = measured[module]
        flag = "" if (got_skipped == want_skipped and got_tests >= want_tests) else "  <-- MISMATCH"
        print(f"{module:<10}{got_tests:>8}{got_skipped:>10}   "
              f"{want_tests:>7} /{want_skipped:>7}{flag}")
        if got_tests < want_tests:
            problems.append(
                f"{module}: ran {got_tests} tests, expected at least {want_tests}. "
                f"{want_tests - got_tests} test(s) disappeared."
            )
        if got_skipped != want_skipped:
            direction = "more" if got_skipped > want_skipped else "fewer"
            problems.append(
                f"{module}: skipped {got_skipped}, expected exactly {want_skipped} "
                f"({abs(got_skipped - want_skipped)} {direction})."
            )

    got_tests, got_skipped = totals(
        {f"{m}.{c}": v for m, cls in found.items() for c, v in cls.items()})
    want_tests = sum(t for t, _ in expected.values())
    want_skipped = sum(s for _, s in expected.values())
    print(f"{'TOTAL':<10}{got_tests:>8}{got_skipped:>10}   "
          f"{want_tests:>7} /{want_skipped:>7}")

    if not problems:
        print(f"\nok: {got_tests - got_skipped} tests actually ran.")
        return 0

    print("\nThe test run did not exercise what this profile says it should.\n",
          file=sys.stderr)
    for problem in problems:
        print(f"  {problem}", file=sys.stderr)

    print("\nSkip reasons, by module, as the tests themselves reported them:",
          file=sys.stderr)
    for module in MODULES:
        if module not in found:
            continue
        reasons = skip_reasons(repo_root, module)
        if reasons:
            summary = ", ".join(f"{count} {label}" for label, count in sorted(reasons.items()))
            print(f"  {module}: {summary}", file=sys.stderr)

    print(
        "\nIf a skip count went UP, either an external input was not configured for\n"
        "this run, or a new test skips without it. The first is a CI setup fault;\n"
        "the second is a decision that belongs in this file, written down on\n"
        "purpose. Do not raise the number to make the build green -- raise it\n"
        "because the new skip is one the project accepts.\n"
        "\n"
        "If a skip count went DOWN, that is usually good news: say so here, and\n"
        "in docs/development-setup.md, so the next run holds the new floor.",
        file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
