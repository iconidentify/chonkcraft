#!/usr/bin/env python3
"""Score a whole survey against the sealed captures, unit-cycle by unit-cycle.

The survey reports one number per case and it is decided by whichever unit
fails earliest, so a four-minute run yields one bit of signal. Every fixture
also seals a `state.bin` holding all 152 bytes of every native unit record on
every cycle, and the engine dumps its own candidate columns when
`CHONKCRAFT_TRACE_BNE_FIELDS` is set. Joining the two scores four hundred thousand
paired samples out of the same run, in five seconds.

Native slots are paired to port ids by where each unit stood on cycle one,
never by pool order: the two engines walk the pool in opposite directions, so
pairing by order matches about one unit per cycle by luck. A square holding
more than one unit on cycle one is left unpaired rather than guessed at.

Two numbers come out. **In place** is how many paired unit-cycles put the unit
on the same square in both engines, over a denominator that does not move with
the result -- an earlier version of this scorer stopped counting a unit at its
first disagreement, which made every candidate look better by measuring less of
the run. **Decisions** are compared only on the prefix where both engines still
have the unit in the same place, because past that its state is incomparable:
a wrong decision on cycle 19 shows up as a wrong position on cycle 36, by which
time the trail is cold.

Nothing here is a rule about the game. It reports what the capture and the
engine each recorded; durable conclusions belong in focused regression tests.
"""

from __future__ import annotations

import argparse
import os
import json
import re
import sys
import zipfile
from collections import Counter
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from bne_routes import (  # noqa: E402
    ROUTE_INDEX, UNIT_ORDER, UNIT_TIMER, UNIT_X, UNIT_Y, read_state_stream,
    route_of,
)


SCHEMA = 2
UNIVERSE_SCHEMA = "chonkcraft-bne-field-parity-universe-1"

#: One `JBNEFIELD` column, as `World.battleNetFieldParity` prints them.
COLUMN = re.compile(r"(\S+?)=(\S*)")

#: The refusal count `fcn.004379e0` keeps, in the high nibble of the word at
#: record offset 0x1c. It is bumped once per refusal and cleared at fifteen.
UNIT_REFUSAL_WORD = 0x1C
UNIT_ACTION_STATE = 8


def _u16(raw: bytes, offset: int) -> int:
    return int.from_bytes(raw[offset:offset + 2], "little")


def native_cycles(path: Path, through: int) -> dict[int, dict[int, dict]]:
    """Per fixture cycle, per native slot, the record columns this reads."""
    cycles: dict[int, dict[int, dict]] = {}
    for frame in read_state_stream(path):
        cycle = frame["cycle"]
        if cycle > through:
            break
        units = {}
        for slot, raw in frame["units"].items():
            steps = route_of(raw)
            index = raw[ROUTE_INDEX]
            units[slot] = {
                "x": _u16(raw, UNIT_X), "y": _u16(raw, UNIT_Y),
                "steps": steps,
                # Twenty is the refuse marker, one past the twenty heading
                # bytes, so a parked route slices to nothing and the unit
                # reads as holding no route -- which is what it is.
                "held": [] if steps is None else steps[index:],
                "index": index, "order": raw[UNIT_ORDER],
                "timer": raw[UNIT_TIMER], "state": raw[UNIT_ACTION_STATE],
                "refusals": (_u16(raw, UNIT_REFUSAL_WORD) & 0xF000) >> 12,
            }
        cycles[cycle] = units
    return cycles


def java_cycles(path: Path, through: int) -> dict[int, dict[int, dict]]:
    """Per fixture cycle, per port unit id, the columns the probe dumped."""
    cycles: dict[int, dict[int, dict]] = {}
    for line in path.read_text(errors="replace").splitlines():
        if not line.startswith("JBNEFIELD "):
            continue
        row = dict(COLUMN.findall(line[len("JBNEFIELD "):]))
        cycle = int(row["cycle"])
        if cycle > through:
            continue
        route = row.get("route", "-")
        cycles.setdefault(cycle, {})[int(row["unit"])] = {
            "x": int(row["x"]), "y": int(row["y"]),
            "held": [] if route == "-" else [int(step) for step in route],
            "refusals": int(row["coll"]), "wait": int(row["wait"]),
            "delay": int(row["delay"]), "order": row["order"],
            "moveanim": int(row["moveanim"]), "moving": int(row["moving"]),
        }
    return cycles


def pair_by_first_cycle(native: dict[int, dict[int, dict]],
                        java: dict[int, dict[int, dict]]) -> dict[int, int]:
    """Native slot to port unit id, by where each stood on cycle one."""
    if 1 not in native or 1 not in java:
        return {}
    slots: dict[tuple[int, int], list[int]] = {}
    for slot, row in native[1].items():
        slots.setdefault((row["x"], row["y"]), []).append(slot)
    ids: dict[tuple[int, int], list[int]] = {}
    for ident, row in java[1].items():
        ids.setdefault((row["x"], row["y"]), []).append(ident)
    return {slots[square][0]: ids[square][0]
            for square in slots
            if len(slots[square]) == 1 and len(ids.get(square, ())) == 1}


def _decision(before: tuple[dict, dict], now: tuple[dict, dict]) -> str:
    """What the two engines did on this visit, named as the histogram does."""
    was_native, was_java = before
    native, java = now
    stepped_native = (native["x"], native["y"]) \
        != (was_native["x"], was_native["y"])
    stepped_java = (java["x"], java["y"]) != (was_java["x"], was_java["y"])
    if stepped_native != stepped_java:
        return "a differing step"
    laid_native = native["steps"] is not None \
        and native["steps"] != was_native["steps"]
    laid_java = len(java["held"]) > len(was_java["held"])
    if laid_native and not laid_java:
        return "native laid a fresh route, this port did not"
    if laid_java and not laid_native:
        return "this port laid one, native did not"
    if native["held"] and not java["held"]:
        return "native holds a route, this port has none"
    if java["held"] and not native["held"]:
        return "this port holds a route, native has none"
    if native["held"] and java["held"] \
            and native["held"][0] != java["held"][0]:
        return "the route's next heading differs"
    return "agreed"


def score_case(state: Path, stderr: Path, through: int,
               frozen_pairs: dict[int, int] | None = None) -> dict[str, Any]:
    """One case's paired unit-cycles and decision histogram."""
    native = native_cycles(state, through)
    java = java_cycles(stderr, through)
    pairs = frozen_pairs if frozen_pairs is not None \
        else pair_by_first_cycle(native, java)
    paired = in_place = 0
    missing_samples = 0
    decisions: Counter = Counter()
    for slot, ident in pairs.items():
        before = None
        parted = False
        for cycle in range(1, through + 1):
            here = native.get(cycle, {}).get(slot)
            there = java.get(cycle, {}).get(ident)
            # Native presence defines the frozen sample universe. A Java unit
            # that disappears early is a disagreement, not permission to make
            # the denominator smaller. Once retail removes its unit there is
            # no longer a native position sample to compare.
            if here is None:
                before = None
                continue
            paired += 1
            if there is None:
                missing_samples += 1
                parted = True
                before = None
                continue
            agrees = (here["x"], here["y"]) == (there["x"], there["y"])
            if agrees:
                in_place += 1
            # The visit that produced a divergence is the informative one, so
            # it is compared and the ones after it are not: the unit was in
            # the same place in both engines when it was decided.
            if not parted and before is not None:
                decisions[_decision(before, (here, there))] += 1
            if not agrees:
                parted = True
            before = (here, there)
    return {"paired": paired, "in_place": in_place, "pairs": len(pairs),
            "pair_map": dict(sorted(pairs.items())),
            "missing_samples": missing_samples, "decisions": decisions}


def sealed_state(sealed: Path, cache: Path) -> Path:
    """The `state.bin` inside a sealed fixture, unpacked once and kept."""
    cache.mkdir(parents=True, exist_ok=True)
    unpacked = cache / f"{sealed.stem}.state.bin"
    if not unpacked.exists():
        with zipfile.ZipFile(sealed) as archive:
            unpacked.write_bytes(archive.read("state.bin"))
    return unpacked


def run_field_parity(survey: Path, cases: Path, through: int,
                     cache: Path, universe: dict[str, Any] | None = None) \
        -> dict[str, Any]:
    """Score every case in a survey output directory against its capture."""
    scored: dict[str, dict[str, Any]] = {}
    paired = in_place = 0
    decisions: Counter = Counter()
    missing: list[str] = []
    for stderr in sorted(survey.glob("*.java.stderr.txt")):
        case = stderr.name[:-len(".java.stderr.txt")]
        sealed = cases / f"{case}.bnefx"
        if not sealed.exists():
            missing.append(case)
            continue
        frozen = None
        if universe is not None:
            raw = (universe.get("cases") or {}).get(case)
            if raw is None:
                missing.append(case + " (not in frozen universe)")
                continue
            frozen = {int(slot): int(ident) for slot, ident in raw.items()}
        got = score_case(sealed_state(sealed, cache), stderr, through, frozen)
        scored[case] = {"paired": got["paired"], "in_place": got["in_place"],
                        "pairs": got["pairs"],
                        "missing_samples": got["missing_samples"],
                        "pair_map": got["pair_map"]}
        paired += got["paired"]
        in_place += got["in_place"]
        decisions.update(got["decisions"])
    return {"schema": SCHEMA, "through": through, "paired": paired,
            "in_place": in_place, "cases": scored, "missing": missing,
            "decisions": dict(decisions)}


def freeze_universe(result: dict[str, Any]) -> dict[str, Any]:
    return {
        "schema": UNIVERSE_SCHEMA,
        "through": result["through"],
        "cases": {case: {str(slot): ident for slot, ident in got["pair_map"].items()}
                  for case, got in sorted(result["cases"].items())},
    }


def render(result: dict[str, Any]) -> str:
    """The scoreboard, worst case first."""
    lines = []
    for case in sorted(result["cases"],
                       key=lambda name: result["cases"][name]["in_place"]
                       - result["cases"][name]["paired"]):
        got = result["cases"][case]
        short = got["paired"] - got["in_place"]
        if short:
            lines.append(f"  {case:28s} {got['in_place']:7d} /"
                         f" {got['paired']:7d}  short {short}"
                         + (f" missing {got.get('missing_samples', 0)}"
                            if got.get("missing_samples") else ""))
    for case in result["missing"]:
        lines.append(f"  {case:28s} no sealed capture")
    paired = max(result["paired"], 1)
    lines.append("")
    lines.append(f"in place {result['in_place']} of {result['paired']}"
                 f" paired unit-cycles"
                 f" ({100.0 * result['in_place'] / paired:.3f}%)")
    decisions = result["decisions"]
    every = max(sum(decisions.values()), 1)
    agreed = decisions.get("agreed", 0)
    lines.append("")
    lines.append(f"decisions compared {sum(decisions.values())},"
                 f" agreeing {agreed} ({100.0 * agreed / every:.3f}%)")
    for what, count in sorted(decisions.items(), key=lambda row: -row[1]):
        if what != "agreed":
            lines.append(f"  {what:48s} {count}")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("survey", type=Path,
                        help="a survey output directory, run with "
                             "CHONKCRAFT_TRACE_BNE_FIELDS=1")
    parser.add_argument("--cases", type=Path, required=True,
                        help="the corpus cases directory holding each .bnefx")
    parser.add_argument("--through", type=int, default=60)
    parser.add_argument("--cache", type=Path,
                        default=Path(os.environ.get(
                            "BNE_STATE_CACHE",
                            str(Path.home()
                                / ".chonkcraft/work/bne-oracle/state-cache"))))
    parser.add_argument("--json-output", type=Path)
    parser.add_argument("--universe", type=Path,
                        help="frozen baseline native-slot to Java-id pairing")
    parser.add_argument("--freeze-universe", type=Path,
                        help="write the current pairing once for later candidates")
    args = parser.parse_args(argv)

    universe = None
    if args.universe is not None:
        universe = json.loads(args.universe.read_text(encoding="utf-8"))
        if universe.get("schema") != UNIVERSE_SCHEMA:
            raise ValueError("unsupported field-parity universe")
        if int(universe.get("through", 0)) < args.through:
            raise ValueError("frozen field-parity universe is shorter than the score")
    result = run_field_parity(args.survey, args.cases, args.through,
                              args.cache, universe)
    if args.freeze_universe is not None:
        args.freeze_universe.parent.mkdir(parents=True, exist_ok=True)
        args.freeze_universe.write_text(
            json.dumps(freeze_universe(result), indent=2, sort_keys=True) + "\n",
            encoding="utf-8")
    if args.json_output is not None:
        args.json_output.write_text(
            json.dumps(result, indent=2, sort_keys=True) + "\n",
            encoding="utf-8")
    print(render(result), end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
