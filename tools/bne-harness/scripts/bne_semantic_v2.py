#!/usr/bin/env python3
"""Compare the broad Java simulation snapshot with sealed retail BNE state.

Semantic-v1 intentionally compares only banks, the gameplay seed, unit tile,
health and a coarse order.  This tier reads the state already present in every
schema-1.1 fixture and checks the common, proved representation of four more
families: player simulation counters, sub-tile unit state, live projectiles and
terrain mutations.  Fields with no proved cross-engine representation are
counted as uncovered; they are never guessed into an apparent pass.

The Java input is produced by EngineTrace with
CHONKCRAFT_TRACE_BNE_SEMANTIC_V2=1. Existing semantic-v1 rows remain unchanged.
"""

from __future__ import annotations

import argparse
import io
import json
from pathlib import Path
import sys
from typing import Any, BinaryIO, Iterator
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))

from bne_fixture import (  # noqa: E402
    AUX_HEADER, BULLET_BYTES, BULLET_DELTA_HEADER, CHUNK_HEADER, CYCLE_HEADER,
    MAP_DELTA, PLAYER_RECORD, PLAYER_SIM_RECORD, STATE_HEADER,
    UNIT_DELTA_HEADER,
)
from bne_packet import PLAYER_SIM_FIELDS  # noqa: E402


SCHEMA = 1
UNIT_FLAGS = 30
UNIT_FREE_OR_DEAD = 0x05
UNIT_FACE = 10
UNIT_ORDER_X = 132
UNIT_ORDER_Y = 134
BULLET_FLAGS = 53
BULLET_FREE = 0x01
CONTROLLER_NOBODY = 3

UNIT_FIELDS = {
    "px": (0, 2), "py": (2, 2), "timer": (7, 1), "frame": (9, 1),
    "x": (24, 2), "y": (26, 2), "hp": (34, 2), "player": (44, 1),
}
SEQUENCE_FIELDS = {"sequence": (4, 2, "seqoff")}
FACE_FIELDS = {"face": (UNIT_FACE, 1)}
ORDER_POINT_FIELDS = {
    "orderx": (UNIT_ORDER_X, 2), "ordery": (UNIT_ORDER_Y, 2),
}
BULLET_FIELDS = {
    "x": (0, 2), "y": (2, 2), "frame": (9, 1), "face": (10, 1),
    "tox": (40, 2), "toy": (42, 2),
}
PLAYER_FIELDS = {
    "supply": "food_limit", "units": "units", "buildings": "buildings",
    "kills": "unit_kills", "razings": "building_kills",
    "arrows": "arrows", "swords": "swords", "shields": "shields",
    "ship_attack": "ship_attack", "ship_armor": "ship_armor",
    "catapult_damage": "catapult_damage",
    "ranger_berserker": "ranger_berserker",
    "marksmanship": "marksmanship", "longbow": "longbow",
    "scouting": "scouting",
}


def _uint(raw: bytes, offset: int, size: int) -> int:
    return int.from_bytes(raw[offset:offset + size], "little")


def _rows(path: Path) -> dict[int, dict[str, Any]]:
    cycles: dict[int, dict[str, Any]] = {}
    for line in path.read_text(errors="replace").splitlines():
        if not line.startswith(("v2w ", "v2p ", "v2u ", "v2m ", "v2t ")):
            continue
        prefix, rest = line.split(" ", 1)
        row: dict[str, str] = {}
        for token in rest.split():
            if "=" in token:
                key, value = token.split("=", 1)
                row[key] = value
        if "cycle" not in row:
            raise ValueError(f"{prefix} row has no cycle: {line}")
        cycle = int(row["cycle"])
        bucket = cycles.setdefault(cycle, {
            "world": None, "players": {}, "units": {}, "missiles": {},
            "terrain": {},
        })
        if prefix == "v2w":
            bucket["world"] = row
        elif prefix == "v2p":
            bucket["players"][int(row["player"])] = row
        elif prefix == "v2u":
            bucket["units"][int(row["unit"])] = row
        elif prefix == "v2m":
            bucket["missiles"][int(row["slot"])] = row
        else:
            bucket["terrain"][(int(row["x"]), int(row["y"]))] = row
    return cycles


def _state_source(path: Path) -> tuple[BinaryIO, zipfile.ZipFile | None]:
    if zipfile.is_zipfile(path):
        archive = zipfile.ZipFile(path)
        return archive.open("state.bin"), archive
    return path.open("rb"), None


def native_cycles(path: Path) -> Iterator[dict[str, Any]]:
    """Decode the common semantic fields, carrying delta state forward."""
    source, archive = _state_source(path)
    try:
        header = STATE_HEADER.unpack(source.read(STATE_HEADER.size))
        major, minor, unit_bytes, players = header[1], header[2], header[4], header[6]
        if (major, minor) != (1, 1):
            raise ValueError("semantic-v2 requires a schema-1.1 native state stream")
        live_units: dict[int, bytes] = {}
        live_bullets: dict[int, bytes] = {}
        cells: dict[int, int] = {}
        squares: dict[int, int] = {}
        current: dict[str, Any] | None = None
        while True:
            packed = source.read(CHUNK_HEADER.size)
            if not packed:
                return
            tag, size = CHUNK_HEADER.unpack(packed)
            payload = io.BytesIO(source.read(size))
            if tag == b"CYCL":
                cycle, seed, pool, changed = CYCLE_HEADER.unpack(
                    payload.read(CYCLE_HEADER.size))
                banks = [PLAYER_RECORD.unpack(payload.read(PLAYER_RECORD.size))
                         for _ in range(players)]
                for _ in range(changed):
                    slot, _generation = UNIT_DELTA_HEADER.unpack(
                        payload.read(UNIT_DELTA_HEADER.size))
                    raw = payload.read(unit_bytes)
                    if raw[UNIT_FLAGS] & UNIT_FREE_OR_DEAD:
                        live_units.pop(slot, None)
                    else:
                        live_units[slot] = raw
                for slot in tuple(live_units):
                    if slot >= pool:
                        live_units.pop(slot)
                current = {"cycle": cycle, "seed": seed, "banks": banks}
            elif tag == b"AUXL":
                if current is None:
                    raise ValueError("native AUXL has no preceding cycle")
                cycle, bullets, changed_bullets, map_size, changed_tiles = \
                    AUX_HEADER.unpack(payload.read(AUX_HEADER.size))
                if cycle != current["cycle"]:
                    raise ValueError("native CYCL/AUXL cycle mismatch")
                player_sim = []
                for _ in range(players):
                    values = PLAYER_SIM_RECORD.unpack(
                        payload.read(PLAYER_SIM_RECORD.size))
                    player_sim.append(dict(zip(PLAYER_SIM_FIELDS, values)))
                for _ in range(changed_bullets):
                    slot, _generation = BULLET_DELTA_HEADER.unpack(
                        payload.read(BULLET_DELTA_HEADER.size))
                    raw = payload.read(BULLET_BYTES)
                    if raw[BULLET_FLAGS] & BULLET_FREE:
                        live_bullets.pop(slot, None)
                    else:
                        live_bullets[slot] = raw
                for slot in tuple(live_bullets):
                    if slot >= bullets:
                        live_bullets.pop(slot)
                for _ in range(changed_tiles):
                    index, cell, square = MAP_DELTA.unpack(
                        payload.read(MAP_DELTA.size))
                    cells[index] = cell
                    squares[index] = square
                yield {
                    **current, "player_sim": player_sim,
                    "units": dict(live_units), "missiles": dict(live_bullets),
                    "cells": dict(cells), "squares": dict(squares),
                    "map_size": map_size,
                }
                current = None
    finally:
        source.close()
        if archive is not None:
            archive.close()


def _pair_units(native: dict[int, bytes], java: dict[int, dict[str, str]]) \
        -> dict[int, int]:
    left: dict[tuple[int, int, int], list[int]] = {}
    right: dict[tuple[int, int, int], list[int]] = {}
    for slot, raw in native.items():
        key = (_uint(raw, 24, 2), _uint(raw, 26, 2), raw[44])
        left.setdefault(key, []).append(slot)
    for ident, row in java.items():
        key = (int(row["x"]), int(row["y"]), int(row["player"]))
        right.setdefault(key, []).append(ident)
    return {slots[0]: right[key][0] for key, slots in left.items()
            if len(slots) == 1 and len(right.get(key, ())) == 1}


def _native_order_point(raw: bytes, map_size: int) -> tuple[int, int] | None:
    """Decode the point arm only when both coordinates are valid map tiles."""
    x = _uint(raw, UNIT_ORDER_X, 2)
    y = _uint(raw, UNIT_ORDER_Y, 2)
    return (x, y) if 0 <= x < map_size and 0 <= y < map_size else None


def compare(state: Path, java_trace: Path, through: int | None = None,
            mismatch_limit: int = 100,
            families: set[str] | None = None) -> dict[str, Any]:
    families = set(families or {"player", "unit", "projectile", "terrain"})
    allowed = {"player", "unit", "projectile", "terrain"}
    if not families or not allowed.issuperset(families):
        raise ValueError("semantic-v2 families must be player, unit, projectile or terrain")
    java = _rows(java_trace)
    native = list(native_cycles(state))
    if through is not None:
        native = [row for row in native if row["cycle"] <= through]
    if not native:
        raise ValueError("native state has no cycles to compare")
    if not java:
        raise ValueError("Java trace has no semantic-v2 rows (enable its trace flag)")
    first_cycle = native[0]["cycle"]
    first_java = java.get(first_cycle)
    if first_java is None or first_java["world"] is None:
        raise ValueError(f"Java trace has no complete semantic-v2 cycle {first_cycle}")
    pairs = _pair_units(native[0]["units"], first_java["units"])
    first_cells = native[0]["cells"]
    java_terrain_baseline = set(first_java["terrain"])
    comparisons = {family: {"compared": 0, "matched": 0}
                   for family in ("player", "unit", "projectile", "terrain")}
    mismatches: list[dict[str, Any]] = []

    def check(family: str, cycle: int, subject: str, field: str,
              retail: Any, port: Any) -> None:
        bucket = comparisons[family]
        bucket["compared"] += 1
        if retail == port:
            bucket["matched"] += 1
        elif len(mismatches) < mismatch_limit:
            mismatches.append({"cycle": cycle, "family": family,
                               "subject": subject, "field": field,
                               "retail": retail, "java": port})

    for frame in native:
        cycle = frame["cycle"]
        port = java.get(cycle)
        if port is None or port["world"] is None:
            mismatches.append({"cycle": cycle, "family": "coverage",
                               "subject": "cycle", "field": "present",
                               "retail": True, "java": False})
            continue
        if "player" in families:
            for player, bank in enumerate(frame["banks"]):
                if bank[0] == CONTROLLER_NOBODY:
                    continue
                prow = port["players"].get(player)
                if prow is None:
                    check("player", cycle, str(player), "present", True, False)
                    continue
                native_player = frame["player_sim"][player]
                for java_name, native_name in PLAYER_FIELDS.items():
                    check("player", cycle, str(player), java_name,
                          native_player[native_name], int(prow[java_name]))
        if "unit" in families:
            for slot, ident in pairs.items():
                raw = frame["units"].get(slot)
                urow = port["units"].get(ident)
                if raw is None or urow is None:
                    check("unit", cycle, f"slot:{slot}/unit:{ident}",
                          "present", raw is not None, urow is not None)
                    continue
                for name, (offset, size) in UNIT_FIELDS.items():
                    check("unit", cycle, f"slot:{slot}/unit:{ident}", name,
                          _uint(raw, offset, size), int(urow[name]))
                # Both engines execute the same authenticated script.bin, so
                # this byte offset is a direct scheduler proof rather than an
                # animation-name or order heuristic.
                for name, (offset, size, java_name) in SEQUENCE_FIELDS.items():
                    check("unit", cycle, f"slot:{slot}/unit:{ident}", name,
                          _uint(raw, offset, size), int(urow[java_name]))
                # BNE stores one of eight headings, while Java stores a full
                # 0..255 turn angle. Rendering rounds that angle to the nearest
                # of the same eight compass sectors. Only genuinely mobile
                # units participate: buildings and scenery can legally carry
                # direction-shaped bytes which have no facing semantics.
                java_face = int(urow["face"])
                if int(urow.get("mobile", 0)) == 1:
                    check("unit", cycle, f"slot:{slot}/unit:{ident}",
                          "face", _uint(raw, UNIT_FACE, 1),
                          ((java_face + 16) // 32) & 7)
                # Off-map values are pointer/sentinel union arms, not points.
                # Compare only the proved point representation.
                native_point = _native_order_point(raw, frame["map_size"])
                java_point = (int(urow["orderx"]), int(urow["ordery"]))
                if native_point is not None and java_point[0] >= 0 \
                        and java_point[1] >= 0:
                    check("unit", cycle, f"slot:{slot}/unit:{ident}",
                          "orderx", native_point[0], java_point[0])
                    check("unit", cycle, f"slot:{slot}/unit:{ident}",
                          "ordery", native_point[1], java_point[1])
        all_slots = (set(frame["missiles"]) | set(port["missiles"])) \
                if "projectile" in families else set()
        for slot in all_slots:
            raw = frame["missiles"].get(slot)
            mrow = port["missiles"].get(slot)
            if raw is None or mrow is None:
                check("projectile", cycle, f"slot:{slot}", "present",
                      raw is not None, mrow is not None)
                continue
            for name, (offset, size) in BULLET_FIELDS.items():
                check("projectile", cycle, f"slot:{slot}", name,
                      _uint(raw, offset, size), int(mrow[name]))
        native_changed = {
            (index % frame["map_size"], index // frame["map_size"])
            for index, value in frame["cells"].items()
            if first_cells.get(index) != value
        }
        java_changed = set(port["terrain"]) - java_terrain_baseline
        if "terrain" in families:
            check("terrain", cycle, "map", "changed_squares",
                  sorted(native_changed), sorted(java_changed))

    coverage = {
        "player": {"proved_fields": sorted(PLAYER_FIELDS),
                   "native_fields": len(PLAYER_SIM_FIELDS)},
        "unit": {"proved_fields": sorted(
                    set(UNIT_FIELDS) | set(SEQUENCE_FIELDS)
                    | set(FACE_FIELDS) | set(ORDER_POINT_FIELDS)),
                 "native_bytes": 152,
                 "conditional_fields": {
                     "face": "mobile units; Java angle rounded to an eight-way heading",
                     "orderx,ordery": "both native and Java carry a valid map point",
                 }},
        "projectile": {"proved_fields": sorted(BULLET_FIELDS),
                       "native_bytes": BULLET_BYTES},
        "terrain": {"proved_fields": ["changed_squares"],
                    "native_fields": ["cell", "square"]},
        "unit_pairs": len(pairs),
    }
    required = all(comparisons[name]["compared"] > 0 for name in families)
    status = "PASS" if required and not mismatches else \
             "INCOMPLETE" if not required else "DIVERGED"
    return {"schema": SCHEMA, "tier": "semantic-v2", "status": status,
            "cycles": len(native), "families": sorted(families),
            "coverage": coverage,
            "comparisons": comparisons, "mismatches": mismatches}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("state", type=Path,
                        help="state.bin or sealed .bnefx fixture")
    parser.add_argument("java_trace", type=Path)
    parser.add_argument("--through", type=int)
    parser.add_argument("--family", action="append",
                        choices=("player", "unit", "projectile", "terrain"))
    parser.add_argument("--json", type=Path)
    args = parser.parse_args()
    result = compare(args.state, args.java_trace, args.through,
                     families=set(args.family) if args.family else None)
    rendered = json.dumps(result, indent=2, sort_keys=True)
    if args.json:
        args.json.write_text(rendered + "\n")
    print(rendered)
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
