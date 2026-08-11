#!/usr/bin/env python3
"""Sweep many maps against upstream and sort the findings into a queue.

The parity loop works one first-divergence at a time, and the survey is how
the next one is chosen: run every map, normalise each map's first findings
into a signature that names the mechanism rather than the unit ids it
happened to land on, and cluster the maps that share one. One transcribed
rule routinely closes a whole cluster -- six of the first ten campaign
missions were clean on first contact because the engine maps had already
paid for their mechanisms -- so the queue is sorted by earliest divergence
and worked cluster by cluster, not map by map.

Usage:
  scripts/parity-survey.py                      all campaigns + engine maps, 900 cycles
  scripts/parity-survey.py --cycles 3600        a longer window
  scripts/parity-survey.py --maps a b c         a chosen subset
  scripts/parity-survey.py --gate FILE          regression gate: every "map cycles"
                                                line in FILE must be clean; anything
                                                else -- divergence, harness failure,
                                                missing trace -- exits nonzero
  scripts/parity-survey.py -j 4                 sweeps in parallel

Every state a map can be in is reported and counted: clean, divergent,
harness-failed (one side wrote nothing -- crashes and briefing walls land
here), and timed-out. A map that cannot be traced is never counted as
clean.
"""

import argparse
import concurrent.futures
import os
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SWEEP = ROOT / "scripts" / "parity-sweep.sh"
DIFFER = ROOT / "scripts" / "diff-determinism.py"

ENGINE_MAPS = [
    "maps/demo/demo01",
    "maps/demo/demo02",
    "maps/demo/demo03",
    "maps/skirmish/(2)2-players",
    "maps/skirmish/(2)x-marks-the-spot",
    "maps/skirmish/(2)cross-the-streams",
    "maps/skirmish/(2)one-way-in-one-way-out",
    "maps/skirmish/(2)mysterious-dragon-isle",
    "maps/skirmish/(3)critter-attack",
]

AGREE = re.compile(r"traces agree over (\d+) shared cycles")
DIVERGE = re.compile(r"(\d+) finding\(s\) across (\d+) cycles, first at cycle (\d+)")
FINDING = re.compile(
    r"cycle \d+: (?:unit (?:\S+ )?\((\S+)\) (\S+) (.*)"
    r"|p(\d+) bank .*"
    r"|seed \S+ vs \S+.*)")


def campaign_maps(data: pathlib.Path) -> list[str]:
    maps = []
    for smp in sorted(data.glob("campaigns/*/level*.smp.gz")):
        maps.append(str(smp.relative_to(data))[: -len(".smp.gz")])
    return maps


def sweep(map_name: str, cycles: int) -> tuple[str, str, str]:
    """Returns (map, state, detail)."""
    try:
        run = subprocess.run(
            [str(SWEEP), map_name, str(cycles)],
            capture_output=True, text=True, timeout=600, cwd=ROOT)
    except subprocess.TimeoutExpired:
        return map_name, "timed-out", ""
    text = (run.stdout + run.stderr).strip()
    if AGREE.search(text):
        return map_name, "clean", ""
    found = DIVERGE.search(text)
    if found:
        return map_name, "divergent", text.split(": ", 1)[-1]
    if "wrote nothing" in text:
        return map_name, "harness-failed", text.split(": ", 1)[-1]
    return map_name, "harness-failed", text[-200:] if text else "no output"


def first_cycle(detail: str) -> int:
    found = DIVERGE.search(detail)
    return int(found.group(3)) if found else 10**9


def signature(map_name: str) -> str:
    """The first findings, with the incidental parts stripped.

    Unit ids differ between engines and between maps; the mechanism shows in
    what kind of field diverged, on what kind of unit, between which orders.
    """
    out_dir = pathlib.Path(os.environ.get("PARITY_DIR", "/tmp/chonkcraft-parity"))
    tag = map_name.replace("/", "_").replace("(", "_").replace(")", "_")
    upstream = out_dir / f"u-{tag}.txt"
    ours = out_dir / f"j-{tag}.txt"
    if not upstream.is_file() or not ours.is_file():
        return "?"
    run = subprocess.run(
        [sys.executable, str(DIFFER), "--all", str(upstream), str(ours)],
        capture_output=True, text=True, cwd=ROOT)
    parts = set()
    for line in (run.stdout + run.stderr).splitlines():
        line = line.strip()
        if not line.startswith("cycle "):
            continue
        body = line.split(": ", 1)[-1]
        if body.startswith("seed "):
            parts.add("seed")
        elif body.startswith("p") and " bank " in body:
            parts.add("bank")
        elif "only in the" in body:
            kind = re.search(r"\((\S+)\)", body)
            parts.add(f"unpaired:{kind.group(1) if kind else '?'}")
        else:
            named = re.match(r"unit \S+ \((\S+)\) (\S+)(.*)", body)
            if named:
                unit_type, field, rest = named.groups()
                if field == "order":
                    orders = rest.strip().replace(" ", "")
                    parts.add(f"order:{unit_type}:{orders}")
                elif field in ("x", "y"):
                    parts.add(f"position:{unit_type}")
                else:
                    parts.add(f"{field}:{unit_type}")
            else:
                parts.add(body[:40])
    return " + ".join(sorted(parts)) if parts else "?"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cycles", type=int, default=900)
    parser.add_argument("--maps", nargs="*", help="explicit map list")
    parser.add_argument("--gate", help="clean-list file; regression gate mode")
    parser.add_argument("-j", "--jobs", type=int, default=4)
    args = parser.parse_args()

    data = pathlib.Path(os.environ.get("CHONKCRAFT_DATA",
                        pathlib.Path.home() / "src" / "chonkcraft-data"))

    if args.gate:
        wanted = []
        for line in pathlib.Path(args.gate).read_text().splitlines():
            line = line.split("#", 1)[0].strip()
            if line:
                name, cycles = line.rsplit(None, 1)
                wanted.append((name, int(cycles)))
        failures = []
        with concurrent.futures.ThreadPoolExecutor(args.jobs) as pool:
            results = list(pool.map(lambda w: sweep(w[0], w[1]), wanted))
        for (name, cycles), (_, state, detail) in zip(wanted, results):
            mark = "ok" if state == "clean" else "REGRESSED"
            print(f"{mark:9} {name} @{cycles}  {detail if state != 'clean' else ''}")
            if state != "clean":
                failures.append(name)
        if failures:
            print(f"\n{len(failures)} of {len(wanted)} gated maps are not clean.")
            return 1
        print(f"\nall {len(wanted)} gated maps clean.")
        return 0

    maps = args.maps if args.maps else campaign_maps(data) + ENGINE_MAPS
    with concurrent.futures.ThreadPoolExecutor(args.jobs) as pool:
        results = list(pool.map(lambda m: sweep(m, args.cycles), maps))

    clean = [r for r in results if r[1] == "clean"]
    divergent = sorted((r for r in results if r[1] == "divergent"),
                       key=lambda r: first_cycle(r[2]))
    broken = [r for r in results if r[1] in ("harness-failed", "timed-out")]

    print(f"\n== survey: {len(results)} maps at {args.cycles} cycles --"
          f" {len(clean)} clean, {len(divergent)} divergent, {len(broken)} untraceable\n")
    for name, _, detail in divergent:
        print(f"  {first_cycle(detail):>6}  {name}  {detail}")
    for name, state, detail in broken:
        print(f"  {'-':>6}  {name}  {state}: {detail}")

    if divergent:
        print("\n== clusters, by normalised first findings\n")
        clusters: dict[str, list[str]] = {}
        for name, _, detail in divergent:
            clusters.setdefault(signature(name), []).append(
                f"{name}@{first_cycle(detail)}")
        for sig, members in sorted(clusters.items(),
                                   key=lambda kv: min(int(m.rsplit('@', 1)[1])
                                                      for m in kv[1])):
            print(f"  [{len(members)}] {sig}")
            for member in members:
                print(f"      {member}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
