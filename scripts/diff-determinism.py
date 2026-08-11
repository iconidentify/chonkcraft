#!/usr/bin/env python3
"""Reports the first cycle two engine traces disagree on, and what disagreed.

Both engines are deterministic, so the same map with nobody at the controls
should produce the same simulation in both -- and every disagreement is a
parity bug, found mechanically. One side is upstream LegacyEngine built with
``tools/legacyEngine-trace.patch``; the other is
``engine/.../parity/EngineTrace.java``. Both write the same shape:

    cycle N seed XXXXXXXX
    p I gold G wood W oil O
    u ID type-ident pP X Y hp H o ORDER [removed]

What is compared, in order of how loud it should be:

* **population** -- a unit present in one trace and absent from the other.
  Units are paired by id; both engines number map units in placement order,
  and the pairing is checked at the first cycle by type and position, so a
  numbering drift is reported as itself rather than as a hundred position
  mismatches.
* **position, health, owner** -- compared exactly.
* **action-table order** -- the order of the paired live lifetimes. LegacyEngine
  walks this vector every cycle, so two equal rosters in a different order are
  latent state: the next shared target, random draw, birth or release can make
  the difference visible hundreds of cycles later.
* **banks** -- compared exactly.
* **seed** -- compared exactly. A seed divergence with everything else equal
  means one engine made a draw the other did not, which is usually the
  first symptom and points at the cycle it happened.
* **order** -- compared only through the coarse table below, because the two
  engines enumerate orders differently and this port's names are its own.

The report stops at the first divergent cycle by default: one bug at a
time is the loop. ``--all`` keeps going and summarises instead.
"""

from __future__ import annotations

import argparse
import re
import sys

# The port's order names against upstream's UnitAction numbers, coarsely:
# both sides map into a small shared vocabulary and only that is compared.
# Upstream numbers are from src/include/actions.h (UnitAction enum order).
# Read off the enum itself (src/include/actions.h), in its own order. The
# first cut of this table was written from memory and dropped Defend, which
# shifted every name from index four on: it decoded upstream's Attack as
# "attackground" and its Die as "spellcast", and reported a fight as a
# disagreement about ground-targeting. A translation table that is quietly
# wrong is worse than no translation at all, because the traces still look
# compared.
UPSTREAM_ORDERS = {
    0: "none", 1: "still", 2: "standground", 3: "follow", 4: "defend",
    5: "move", 6: "attack", 7: "attackground", 8: "die", 9: "spellcast",
    10: "train", 11: "upgradeto", 12: "research", 13: "built", 14: "board",
    15: "unload", 16: "patrol", 17: "build", 18: "explore", 19: "repair",
    20: "resource", 21: "transforminto",
}

# This port's own enum, which is its own list rather than upstream's.
PORT_ORDERS = {
    "STILL": "still", "STAND_GROUND": "standground", "FOLLOW": "follow",
    "MOVE": "move", "ATTACK": "attack", "ATTACK_MOVE": "attack",
    "ATTACK_GROUND": "attackground", "DYING": "die", "SPELL_CAST": "spellcast",
    "UNDER_CONSTRUCTION": "built", "BUILD": "build", "EXPLORE": "explore",
    "HARVEST": "resource", "RETURN_GOODS": "resource", "PATROL": "patrol",
    "BOARD": "board", "REPAIR": "repair",
    # Building-state the trace reports under upstream's own names: the port
    # carries a working building's job on a still order, and EngineTrace
    # translates before writing.
    "TRAIN": "train", "RESEARCH": "research", "UPGRADE_TO": "upgradeto",
}

CYCLE = re.compile(r"cycle (\d+) seed ([0-9a-f]+)")
PLAYER = re.compile(r"p (\d+) gold (-?\d+) wood (-?\d+) oil (-?\d+)")
UNIT = re.compile(r"u (\d+) (\S+) p(\d+) (-?\d+) (-?\d+) hp (-?\d+) o (\S+)( removed)?")


def parse(path):
    cycles = {}
    current = None
    with open(path) as handle:
        for line in handle:
            m = CYCLE.match(line)
            if m:
                current = {"seed": m.group(2), "players": {}, "units": {}}
                cycles[int(m.group(1))] = current
                continue
            if current is None:
                continue
            m = PLAYER.match(line)
            if m:
                current["players"][int(m.group(1))] = tuple(
                    int(m.group(i)) for i in (2, 3, 4))
                continue
            m = UNIT.match(line)
            if m:
                current["units"][int(m.group(1))] = {
                    "type": m.group(2), "player": int(m.group(3)),
                    "x": int(m.group(4)), "y": int(m.group(5)),
                    "hp": int(m.group(6)), "order": m.group(7),
                    "removed": bool(m.group(8)),
                }
    return cycles


def coarse_order(raw):
    if raw.isdigit():
        return UPSTREAM_ORDERS.get(int(raw), "?" + raw)
    return PORT_ORDERS.get(raw, "?" + raw)


def shape(unit):
    """What a unit is and where it stands, which is how the two are matched."""
    return (unit["type"], unit["player"], unit["x"], unit["y"])


def align(left, right, shared):
    """Pairs each lifetime of the two engines' unit ids.

    Upstream numbers its units from nought and this port from one, and
    neither promises the other's numbering -- so comparing by raw id
    reports one bug as a hundred. The map's own units are placed in the
    same order by both, so pairing them at the first cycle on
    (type, owner, position) gives a translation that holds for the whole
    run.

    Units born later are paired the same way, on the cycle they appear.
    Upstream's UnitNumber is a reusable slot, though, whereas this port's id
    is monotonic: after an upstream unit disappears, a later corpse marker
    can be born under the same integer. Pair identities per uninterrupted
    lifetime rather than storing one mapping for the whole trace. Matching
    only on the birth cycle, rather than on any cycle whose shapes happen to
    coincide, still prevents two long-lived units from swapping identities
    halfway through. Anything that cannot be paired is reported as itself,
    which is what a genuine population difference looks like.
    """
    active = {}
    inverse = {}
    translated = {}
    paired = 0
    previous_left = set()
    previous_right = set()
    for cycle in shared:
        left_ids = set(left[cycle]["units"])
        right_ids = set(right[cycle]["units"])
        born_left = left_ids - previous_left
        born_right = right_ids - previous_right

        # A number returning after an absent cycle is a new lifetime. Release
        # whichever old identity owned that number before considering births.
        for lid in born_left:
            rid = active.pop(lid, None)
            if rid is not None:
                inverse.pop(rid, None)
        for rid in born_right:
            lid = inverse.pop(rid, None)
            if lid is not None:
                active.pop(lid, None)

        born_right = {}
        for rid, unit in right[cycle]["units"].items():
            if rid in inverse or rid in previous_right:
                continue
            born_right.setdefault(shape(unit), []).append(rid)
        for lid, unit in left[cycle]["units"].items():
            if lid in active or lid in previous_left:
                continue
            for candidate in born_right.get(shape(unit), []):
                if candidate not in inverse:
                    active[lid] = candidate
                    inverse[candidate] = lid
                    paired += 1
                    break
        translated[cycle] = {
            inverse.get(rid, ("unmatched", rid)): unit
            for rid, unit in right[cycle]["units"].items()
        }
        previous_left = left_ids
        previous_right = right_ids
    return translated, paired


def translate(cycle, mapping):
    """The right trace's units, renumbered into the left's ids."""
    inverse = {right: left for left, right in mapping.items()}
    return {inverse.get(rid, ("unmatched", rid)): unit
            for rid, unit in cycle["units"].items()}


def diff_cycle(n, a, b, problems, compare_action_order=True):
    if a["seed"] != b["seed"]:
        problems.append(f"cycle {n}: seed {a['seed']} vs {b['seed']}"
                        " -- one engine drew and the other did not")
    for player in sorted(set(a["players"]) | set(b["players"])):
        left = a["players"].get(player)
        right = b["players"].get(player)
        if left != right:
            problems.append(f"cycle {n}: p{player} bank {left} vs {right}")
    left_units = a["units"]
    right_units = b.get("translated", b["units"])
    left_order = [unit for unit in left_units if unit in right_units]
    right_order = [unit for unit in right_units if unit in left_units]
    if compare_action_order and left_order != right_order:
        mismatch = next((i for i, pair in enumerate(zip(left_order, right_order))
                         if pair[0] != pair[1]),
                        min(len(left_order), len(right_order)))
        left_at = left_order[mismatch] if mismatch < len(left_order) else "end"
        right_at = right_order[mismatch] if mismatch < len(right_order) else "end"
        problems.append(f"cycle {n}: action-table order first differs at"
                        f" {mismatch}: unit {left_at} vs {right_at}")
    keys = sorted(set(left_units) | set(right_units), key=str)
    for unit in keys:
        left = left_units.get(unit)
        right = right_units.get(unit)
        if left is None or right is None:
            where = "left" if right is None else "right"
            have = left or right
            problems.append(f"cycle {n}: unit {unit} ({have['type']}) only in"
                            f" the {where} trace")
            continue
        for key in ("type", "player", "x", "y", "hp", "removed"):
            if left[key] != right[key]:
                problems.append(
                    f"cycle {n}: unit {unit} ({left['type']}) {key}"
                    f" {left[key]} vs {right[key]}")
        lo = coarse_order(left["order"])
        ro = coarse_order(right["order"])
        if lo != ro and "?" not in lo + ro:
            problems.append(f"cycle {n}: unit {unit} ({left['type']})"
                            f" order {lo} vs {ro}")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("left", help="usually the upstream trace")
    parser.add_argument("right", help="usually the Java trace")
    parser.add_argument("--all", action="store_true",
                        help="report every divergent cycle, not just the first")
    parser.add_argument("--ignore-action-order", action="store_true",
                        help="do not interpret trace line order as the simulation's"
                             " action-table order")
    args = parser.parse_args()

    left = parse(args.left)
    right = parse(args.right)
    shared = sorted(set(left) & set(right))
    if not shared:
        print(f"no shared cycles: left has {len(left)}, right has {len(right)}")
        return 2

    translations, paired = align(left, right, shared)
    print(f"paired {paired} of {len(left[shared[0]]['units'])} units"
          f" at cycle {shared[0]}, and everything either engine founded after")
    for n in shared:
        right[n]["translated"] = translations[n]

    first_only = None
    total = 0
    for n in shared:
        problems = []
        diff_cycle(n, left[n], right[n], problems,
                   compare_action_order=not args.ignore_action_order)
        if problems:
            total += len(problems)
            if first_only is None:
                first_only = n
                print(f"first divergence at cycle {n} "
                      f"({len(problems)} finding(s)):")
                for problem in problems[:20]:
                    print("  " + problem)
                if not args.all:
                    return 1
    if total == 0:
        print(f"traces agree over {len(shared)} shared cycles")
        return 0
    print(f"{total} finding(s) across {len(shared)} cycles,"
          f" first at cycle {first_only}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
