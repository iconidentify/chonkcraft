#!/usr/bin/env python3
"""Read what a native unit has decided to do, not just where it is standing.

A sealed state stream has always carried two things nothing read. The first is
every unit's stored route: twenty bytes at offset 48 of the unit record, one
direction each, `0xff` past the end, zero north and clockwise, with the count
of steps already walked at offset 126. The second is the whole map, delivered
as a complete delta in the `AUXL` chunk of every cycle.

Together they turn "where is this unit" into "where has it decided to go, and
what was in the way when it decided". The wood-face investigation stalled for
want of exactly that, and its route directions were being decoded by hand.

Nothing here is a rule about the game. It is a reader: it says what the capture
recorded, and anything inferred from it belongs in a probe with its own
numbers.
"""

from __future__ import annotations

from collections import deque
import io
import json
from pathlib import Path
from typing import Any, Iterator

from bne_fixture import (
    AUX_HEADER, BULLET_BYTES, BULLET_DELTA_HEADER, CHUNK_HEADER, CYCLE_HEADER,
    MAP_DELTA, PLAYER_RECORD, PLAYER_SIM_RECORD, STATE_HEADER,
    UNIT_DELTA_HEADER,
)


SCHEMA = 1

#: The route, as the unit record holds it.
ROUTE_OFFSET, ROUTE_BYTES, ROUTE_END = 48, 20, 0xFF
ROUTE_INDEX = 126

#: The rest of the record this reader names.
UNIT_TIMER, UNIT_X, UNIT_Y = 7, 24, 26
UNIT_FLAGS, UNIT_ORDER, UNIT_NEXT_ORDER = 30, 46, 47
UNIT_ORDER_X, UNIT_ORDER_Y = 132, 134
UNIT_FREE_OR_DEAD = 0x05

#: Eight headings, clockwise from north, as the route stores them.
STEP = ((0, -1), (1, -1), (1, 0), (1, 1), (0, 1), (-1, 1), (-1, 0), (-1, -1))
HEADING_NAME = ("N", "NE", "E", "SE", "S", "SW", "W", "NW")

#: What stops a ray, read out of the pinned executable rather than inferred.
#: `0x0044fbd0` selects the mask with `word[0x496ca0 + record[0x2a] * 2]` and
#: `0x00450690` stops the ray on `square & mask`. The eight words there are
#: 0x09ce, 0x0200, 0x0903, 0x0901, 0x0100, 0x0200, 0x0100, 0x0100, and record
#: offset 0x2a is 0 for every walker, 1 for every flyer and 2 for every ship
#: across the sealed captures.
#:
#: This used to be `0x08 | 0x80`, inferred from which bits a unit had been seen
#: standing on. That inference cannot see water or a building footprint,
#: because ships stand on the first and buildings on the second, so it left
#: rays running through both. Against 808 sealed routes the inferred mask left
#: 220 whose ray was clear yet whose stored route was something else; the mask
#: the executable states leaves 49, and every one of those 49 is a unit
#: standing on the goal rather than terrain.
SQUARE_UNIT_HERE = 0x100
SQUARE_MASK_BY_MOVEMENT = (0x09CE, 0x0200, 0x0903, 0x0901,
                           0x0100, 0x0200, 0x0100, 0x0100)
UNIT_MOVEMENT = 0x2A
SQUARE_BLOCKS_WALKER = SQUARE_MASK_BY_MOVEMENT[0] & ~SQUARE_UNIT_HERE
SQUARE_FOREST = 0x80


def _u16(raw: bytes, offset: int) -> int:
    return int.from_bytes(raw[offset:offset + 2], "little")


def route_of(raw: bytes) -> list[int] | None:
    """The headings this unit has stored, or None if it holds no route.

    A route is terminated by `0xff`. A record with no terminator anywhere in
    the twenty bytes is not a route -- an uninitialised buffer of zeroes would
    otherwise read as twenty steps due north.
    """
    for offset in range(ROUTE_OFFSET, ROUTE_OFFSET + ROUTE_BYTES):
        if raw[offset] == ROUTE_END:
            return [raw[index] for index in range(ROUTE_OFFSET, offset)]
    return None


def read_state_stream(path: Path) -> Iterator[dict[str, Any]]:
    """Yield one cycle at a time: the live units and the map as it then was.

    The unit pool is carried forward, because a cycle chunk holds only what
    changed. So is the map, for the same reason: only cycle one is complete.
    """
    with Path(path).open("rb") as source:
        header = STATE_HEADER.unpack(source.read(STATE_HEADER.size))
        unit_bytes, players = header[4], header[6]
        live: dict[int, bytes] = {}
        cells: dict[int, int] = {}
        squares: dict[int, int] = {}
        cycle, size = 0, 0
        while True:
            chunk = source.read(CHUNK_HEADER.size)
            if len(chunk) != CHUNK_HEADER.size:
                return
            tag, payload_bytes = CHUNK_HEADER.unpack(chunk)
            payload = io.BytesIO(source.read(payload_bytes))
            if tag == b"CYCL":
                cycle, _seed, _pool, changed = CYCLE_HEADER.unpack(
                    payload.read(CYCLE_HEADER.size))
                payload.read(players * PLAYER_RECORD.size)
                for _ in range(changed):
                    slot, _generation = UNIT_DELTA_HEADER.unpack(
                        payload.read(UNIT_DELTA_HEADER.size))
                    raw = payload.read(unit_bytes)
                    if raw[UNIT_FLAGS] & UNIT_FREE_OR_DEAD:
                        live.pop(slot, None)
                    else:
                        live[slot] = raw
            elif tag == b"AUXL":
                (_aux_cycle, _bullets, changed_bullets, map_size,
                 changed_tiles) = AUX_HEADER.unpack(
                     payload.read(AUX_HEADER.size))
                size = map_size
                payload.read(players * PLAYER_SIM_RECORD.size)
                for _ in range(changed_bullets):
                    payload.read(BULLET_DELTA_HEADER.size + BULLET_BYTES)
                for _ in range(changed_tiles):
                    index, cell, square = MAP_DELTA.unpack(
                        payload.read(MAP_DELTA.size))
                    cells[index] = cell
                    squares[index] = square
                yield {"cycle": cycle, "units": dict(live), "cells": cells,
                       "squares": squares, "map_size": size}


def spent(raw: bytes, steps: list[int]) -> bool:
    """Whether this record's index is a step count at all.

    Twenty is the refuse marker rather than a position in the route -- a unit
    that has been refused carries it with a route of any length -- so a record
    holding it says nothing about how far along the route the unit is, and a
    destination computed from it would be invented.
    """
    return raw[ROUTE_INDEX] > len(steps)


def _plan(cycle: int, slot: int, raw: bytes, steps: list[int]) \
        -> dict[str, Any]:
    """One stored route, described from where it was planned."""
    index = raw[ROUTE_INDEX]
    walked = steps[:index] if index <= len(steps) else []
    here = (_u16(raw, UNIT_X), _u16(raw, UNIT_Y))
    # Where it stood when the route was built, so a route first seen after a
    # step has been taken still names the square it was planned from.
    start = (here[0] - sum(STEP[step][0] for step in walked),
             here[1] - sum(STEP[step][1] for step in walked))
    end = [start[0], start[1]]
    for step in steps:
        end[0] += STEP[step][0]
        end[1] += STEP[step][1]
    return {
        "cycle": cycle, "slot": slot,
        "start": list(start), "at": list(here), "destination": end,
        "steps": [HEADING_NAME[step] for step in steps],
        "walked": len(walked),
        "order": raw[UNIT_ORDER], "next_order": raw[UNIT_NEXT_ORDER],
        "timer": raw[UNIT_TIMER],
        "order_point": [_u16(raw, UNIT_ORDER_X), _u16(raw, UNIT_ORDER_Y)],
    }


def planned_routes(path: Path, slots: set[int] | None = None) \
        -> list[dict[str, Any]]:
    """Every route stored in this capture, once each, as it was stored.

    A route is reported on the cycle its bytes first differ from the cycle
    before, which is the cycle the unit decided on it.
    """
    plans: list[dict[str, Any]] = []
    previous: dict[int, list[int] | None] = {}
    for frame in read_state_stream(path):
        for slot, raw in frame["units"].items():
            if slots is not None and slot not in slots:
                continue
            steps = route_of(raw)
            was = previous.get(slot)
            previous[slot] = steps
            if not steps or steps == was or spent(raw, steps):
                continue
            plans.append(_plan(frame["cycle"], slot, raw, steps))
    return plans


def walk_costs(squares: dict[int, int], size: int, start: tuple[int, int],
        occupied: set[tuple[int, int]], limit: int = 64) \
        -> dict[tuple[int, int], int]:
    """Steps from one tile to every tile, eight-way, all directions equal.

    Equal on purpose. Charging a diagonal more than a cardinal is the ordinary
    shape of a pathfinder this age and it is measurably not this one: over
    twenty-eight sealed wood approaches, every weighting tried picked the
    square retail took in 5 of 21, against 15 of 21 for a uniform cost.
    """
    seen = {start: 0}
    queue = deque([start])
    while queue:
        x, y = queue.popleft()
        step = seen[(x, y)]
        if step >= limit:
            continue
        for dx, dy in STEP:
            tile = (x + dx, y + dy)
            if tile in seen or tile in occupied:
                continue
            if not 0 <= tile[0] < size or not 0 <= tile[1] < size:
                continue
            if squares.get(tile[1] * size + tile[0], 0) & SQUARE_BLOCKS_WALKER:
                continue
            seen[tile] = step + 1
            queue.append(tile)
    return seen


def wood_approaches(path: Path) -> list[dict[str, Any]]:
    """Every route planned at a forest square, with what surrounded it.

    The eight squares beside the tree are reported with the cost of reaching
    each from where the worker planned, so which one it chose can be compared
    against which ones it could have had.
    """
    found: list[dict[str, Any]] = []
    previous: dict[int, list[int] | None] = {}
    for frame in read_state_stream(path):
        size, squares = frame["map_size"], frame["squares"]
        standing = {(_u16(raw, UNIT_X), _u16(raw, UNIT_Y))
                    for raw in frame["units"].values()}
        for slot, raw in frame["units"].items():
            steps = route_of(raw)
            was = previous.get(slot)
            previous[slot] = steps
            if not steps or steps == was or spent(raw, steps):
                continue
            tree = (_u16(raw, UNIT_ORDER_X), _u16(raw, UNIT_ORDER_Y))
            if tree == (0, 0) or max(tree) >= size:
                continue
            if not squares.get(tree[1] * size + tree[0], 0) & SQUARE_FOREST:
                continue
            plan = _plan(frame["cycle"], slot, raw, steps)
            end = tuple(plan["destination"])
            if max(abs(end[0] - tree[0]), abs(end[1] - tree[1])) != 1:
                continue
            start = tuple(plan["start"])
            reach = walk_costs(squares, size, start, standing - {start})
            ring = []
            for index, (dx, dy) in enumerate(STEP):
                tile = (tree[0] + dx, tree[1] + dy)
                ring.append({"face": HEADING_NAME[index],
                             "tile": [tile[0], tile[1]],
                             "steps": reach.get(tile)})
            plan["tree"] = [tree[0], tree[1]]
            plan["face"] = HEADING_NAME[STEP.index(
                (end[0] - tree[0], end[1] - tree[1]))]
            plan["ring"] = ring
            found.append(plan)
    return found


def render(plans: list[dict[str, Any]], *, wood: bool) -> str:
    lines = []
    for plan in plans:
        head = (f"c{plan['cycle']:<4d} slot {plan['slot']:<5d} "
                f"{tuple(plan['start'])} -> {tuple(plan['destination'])}  "
                f"order {plan['order']:<3d} timer {plan['timer']:<3d} "
                f"[{','.join(plan['steps'])}]")
        if not wood:
            lines.append(head)
            continue
        near = [item for item in plan["ring"] if item["steps"] is not None]
        cheapest = min((item["steps"] for item in near), default=None)
        chosen = next(item for item in plan["ring"]
                      if item["face"] == plan["face"])
        lines.append(
            f"c{plan['cycle']:<4d} slot {plan['slot']:<5d} "
            f"{tuple(plan['start'])} tree {tuple(plan['tree'])} "
            f"face {plan['face']:<2s} at {chosen['steps']} steps, "
            f"nearest {cheapest} "
            f"({','.join(item['face'] for item in near if item['steps'] == cheapest)})")
    return "\n".join(lines) + ("\n" if lines else "")


def run_routes(path: Path, *, slots: set[int] | None = None,
        wood: bool = False) -> tuple[int, list[dict[str, Any]]]:
    plans = wood_approaches(path) if wood else planned_routes(path, slots)
    return 0, plans


if __name__ == "__main__":
    import sys

    if len(sys.argv) < 2:
        print(__doc__)
        raise SystemExit(2)
    _status, _plans = run_routes(Path(sys.argv[1]),
                                 wood="--wood" in sys.argv)
    print(json.dumps(_plans, indent=2, sort_keys=True))
