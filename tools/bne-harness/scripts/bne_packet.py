#!/usr/bin/env python3
"""Build a compact forensic packet for one BNE/Java first divergence."""

from __future__ import annotations

import hashlib
import io
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import tempfile
from typing import Any, BinaryIO
import zipfile

import bne_compare
from bne_fixture import (
    AUX_HEADER,
    BULLET_BYTES,
    BULLET_DELTA_HEADER,
    BULLET_FLAGS,
    BULLET_FREE,
    CHUNK_HEADER,
    CYCLE_HEADER,
    MAP_DELTA,
    PLAYER_RECORD,
    PLAYER_SIM_RECORD,
    STATE_HEADER,
    UNIT_DELTA_HEADER,
)


DEFAULT_REMOTE_HOST = os.environ.get("CHONKCRAFT_ORACLE_HOST", "oracle-host")


TRACE_CYCLE = re.compile(r"cycle (\d+) seed ([0-9a-fA-F]+)$")
TRACE_PLAYER = re.compile(r"p (\d+) gold (-?\d+) wood (-?\d+) oil (-?\d+)$")
TRACE_UNIT = re.compile(
    r"u (\d+) (\S+) p(\d+) (-?\d+) (-?\d+) hp (-?\d+) "
    r"o (\S+)( removed)?(?: px (-?\d+) (-?\d+))?$"
)
EVENT_CYCLE = re.compile(r"(?:^| )cycle=(\d+)(?: |$)")
EVENT_UNIT = re.compile(r"(?:^| )unit=(\d+)(?: |$)")
JAVA_PATH_EVENT = re.compile(
    r"^JBNEPATH cycle=(\d+) unit=(\d+) from=(\d+),(\d+) "
    r"goal=(\d+),(\d+) stride=(\d+) result=(\S+) path=(\S+)"
)
JAVA_STEP_EVENT = re.compile(
    r"^JBNESTEP cycle=(\d+) unit=(\d+) type=(\S+) "
    r"from=(\d+),(\d+) to=(\d+),(\d+) stride=(\d+)"
)

UNIT_BYTES = 152
UNIT_LIMIT = 1600
UNIT_PIXEL_X = 0
UNIT_PIXEL_Y = 2
UNIT_SEQUENCE = 4
UNIT_SEQUENCE_FLAGS = 6
UNIT_ANIMATION_TIMER = 7
UNIT_ANIMATION = 8
UNIT_FRAME = 9
UNIT_FACE = 10
UNIT_X = 24
UNIT_Y = 26
UNIT_FLAGS = 30
UNIT_HP = 34
UNIT_TYPE = 39
UNIT_OWNER = 44
UNIT_ORDER = 46
UNIT_NEXT_ORDER = 47
UNIT_ROUTE = 48
UNIT_ROUTE_BYTES = 20
UNIT_AI_HOME_X = 88
UNIT_AI_HOME_Y = 90
UNIT_AI_BEHAVIOR = 94
UNIT_AI_MARKER = 95
UNIT_ROUTE_INDEX = 126
UNIT_ORDER_X = 132
UNIT_ORDER_Y = 134
UNIT_TARGET = 136
UNIT_FREE = 0x01
UNIT_DEAD = 0x04
CONTROLLER_NOBODY = 3

DIRECTIONS = ("N", "NE", "E", "SE", "S", "SW", "W", "NW")
PLAYER_SIM_FIELDS = (
    "food_limit", "units", "buildings", "rescued_units", "lost_units",
    "lost_buildings", "unit_kills", "building_kills", "arrows", "swords",
    "shields", "ship_attack", "ship_armor", "catapult_damage",
    "ranger_berserker", "marksmanship", "longbow", "scouting",
    "allowed_units", "allowed_upgrades", "allowed_spells", "learned_spells",
)
FIELD_RANGES = (
    (UNIT_PIXEL_X, 2, "pixel_x"),
    (UNIT_PIXEL_Y, 2, "pixel_y"),
    (UNIT_SEQUENCE, 2, "sequence"),
    (UNIT_SEQUENCE_FLAGS, 1, "sequence_flags"),
    (UNIT_ANIMATION_TIMER, 1, "animation_timer"),
    (UNIT_ANIMATION, 1, "animation"),
    (UNIT_FRAME, 1, "frame"),
    (UNIT_FACE, 1, "face"),
    (UNIT_X, 2, "x"),
    (UNIT_Y, 2, "y"),
    (UNIT_FLAGS, 1, "flags"),
    (UNIT_HP, 2, "hp"),
    (UNIT_TYPE, 1, "type"),
    (UNIT_OWNER, 1, "owner"),
    (UNIT_ORDER, 1, "order"),
    (UNIT_NEXT_ORDER, 1, "next_order"),
    (UNIT_ROUTE, UNIT_ROUTE_BYTES, "route"),
    (UNIT_AI_HOME_X, 2, "ai_home_x"),
    (UNIT_AI_HOME_Y, 2, "ai_home_y"),
    (UNIT_AI_BEHAVIOR, 1, "ai_behavior"),
    (UNIT_AI_MARKER, 1, "ai_marker"),
    (UNIT_ROUTE_INDEX, 1, "route_index"),
    (UNIT_ORDER_X, 2, "order_x"),
    (UNIT_ORDER_Y, 2, "order_y"),
    (UNIT_TARGET, 4, "target_pointer"),
)


def _read_exact(source: BinaryIO, size: int, description: str) -> bytes:
    data = source.read(size)
    if len(data) != size:
        raise ValueError(f"truncated {description}")
    return data


def _u16(raw: bytes | bytearray, offset: int) -> int:
    return int.from_bytes(raw[offset:offset + 2], "little")


def _s16(raw: bytes | bytearray, offset: int) -> int:
    return int.from_bytes(raw[offset:offset + 2], "little", signed=True)


def _u32(raw: bytes | bytearray, offset: int) -> int:
    return int.from_bytes(raw[offset:offset + 4], "little")


def file_identity(path: Path) -> dict[str, int | str]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            size += len(block)
            digest.update(block)
    return {"bytes": size, "sha256": digest.hexdigest()}


def parse_trace(source: BinaryIO | io.TextIOBase) -> dict[int, dict[str, Any]]:
    cycles: dict[int, dict[str, Any]] = {}
    current: dict[str, Any] | None = None
    for raw_line in source:
        line = raw_line.decode("utf-8") if isinstance(raw_line, bytes) else raw_line
        line = line.rstrip("\r\n")
        if matched := TRACE_CYCLE.fullmatch(line):
            current = {
                "seed": matched.group(2).lower(),
                "players": {},
                "units": {},
            }
            cycles[int(matched.group(1))] = current
        elif current is not None and (matched := TRACE_PLAYER.fullmatch(line)):
            current["players"][int(matched.group(1))] = {
                "gold": int(matched.group(2)),
                "wood": int(matched.group(3)),
                "oil": int(matched.group(4)),
            }
        elif current is not None and (matched := TRACE_UNIT.fullmatch(line)):
            unit = {
                "type": matched.group(2),
                "player": int(matched.group(3)),
                "x": int(matched.group(4)),
                "y": int(matched.group(5)),
                "hp": int(matched.group(6)),
                "order": matched.group(7),
                "removed": bool(matched.group(8)),
            }
            if matched.group(9) is not None:
                unit["pixel_x"] = int(matched.group(9))
                unit["pixel_y"] = int(matched.group(10))
            current["units"][int(matched.group(1))] = unit
    return cycles


def augment_trace_pixels(state_source: BinaryIO,
        trace: dict[int, dict[str, Any]]) -> None:
    """Attach authenticated raw IX/IY coordinates to a native trace.

    Schema-1.1 textual traces intentionally expose only tile position. The
    accompanying state stream retains the native signed pixel coordinates at
    unit offsets 0 and 2. Reconstruct its deltas in lockstep and enrich only
    units already present in the semantic trace; this does not change pairing
    or the authoritative semantic-v1 comparison tier.
    """
    if not trace:
        return
    header = STATE_HEADER.unpack(
        _read_exact(state_source, STATE_HEADER.size, "BNE state header")
    )
    (_magic, major, minor, header_bytes, unit_bytes, unit_limit,
     player_count, _flags) = header
    if (_magic != b"BNESTATE" or major != 1 or minor != 1
            or header_bytes != STATE_HEADER.size
            or unit_bytes != UNIT_BYTES or unit_limit != UNIT_LIMIT
            or player_count != 16):
        raise ValueError("sub-tile trace enrichment requires BNE state schema 1.1")
    raw_units = [bytearray(unit_bytes) for _ in range(unit_limit)]
    known_units = [False] * unit_limit
    seen_cycles: set[int] = set()
    maximum = max(trace)
    while True:
        raw_header = state_source.read(CHUNK_HEADER.size)
        if not raw_header:
            break
        tag, payload_bytes = CHUNK_HEADER.unpack(raw_header)
        payload = io.BytesIO(_read_exact(
            state_source, payload_bytes, f"{tag!r} state payload"
        ))
        if tag == b"DONE":
            break
        if tag != b"CYCL":
            continue
        cycle, _seed, pool_count, changed_units = CYCLE_HEADER.unpack(
            _read_exact(payload, CYCLE_HEADER.size, "cycle header")
        )
        payload.read(player_count * PLAYER_RECORD.size)
        for _ in range(changed_units):
            slot, _generation = UNIT_DELTA_HEADER.unpack(
                _read_exact(payload, UNIT_DELTA_HEADER.size,
                            "unit delta header")
            )
            raw_units[slot][:] = _read_exact(payload, unit_bytes, "unit delta")
            known_units[slot] = True
        state = trace.get(cycle)
        if state is None:
            continue
        for slot, unit in state["units"].items():
            if slot >= pool_count or not known_units[slot]:
                raise ValueError(
                    f"native trace cycle {cycle} references unknown unit {slot}"
                )
            raw = raw_units[slot]
            unit["pixel_x"] = _s16(raw, UNIT_PIXEL_X)
            unit["pixel_y"] = _s16(raw, UNIT_PIXEL_Y)
        seen_cycles.add(cycle)
        if cycle >= maximum:
            break
    missing = set(trace) - seen_cycles
    if missing:
        raise ValueError(
            f"BNE state lacks trace cycles needed for pixels: {sorted(missing)}"
        )


def _shape(unit: dict[str, Any]) -> tuple[object, ...]:
    return (unit["type"], unit["player"], unit["x"], unit["y"])


def align_trace_units(native: dict[int, dict[str, Any]],
        java: dict[int, dict[str, Any]]) -> dict[int, dict[int, int]]:
    """Pair native pool slots with Java IDs using the production differ rule."""
    shared = sorted(set(native) & set(java))
    active: dict[int, int] = {}
    inverse: dict[int, int] = {}
    result: dict[int, dict[int, int]] = {}
    previous_native: set[int] = set()
    previous_java: set[int] = set()
    for cycle in shared:
        native_ids = set(native[cycle]["units"])
        java_ids = set(java[cycle]["units"])
        born_native = native_ids - previous_native
        born_java = java_ids - previous_java
        for native_id in born_native:
            java_id = active.pop(native_id, None)
            if java_id is not None:
                inverse.pop(java_id, None)
        for java_id in born_java:
            native_id = inverse.pop(java_id, None)
            if native_id is not None:
                active.pop(native_id, None)
        candidates: dict[tuple[object, ...], list[int]] = {}
        for java_id, unit in java[cycle]["units"].items():
            if java_id in inverse or java_id in previous_java:
                continue
            candidates.setdefault(_shape(unit), []).append(java_id)
        for native_id, unit in native[cycle]["units"].items():
            if native_id in active or native_id in previous_native:
                continue
            for java_id in candidates.get(_shape(unit), []):
                if java_id not in inverse:
                    active[native_id] = java_id
                    inverse[java_id] = native_id
                    break
        result[cycle] = dict(active)
        previous_native = native_ids
        previous_java = java_ids
    return result


def decode_unit(raw: bytes | bytearray) -> dict[str, Any]:
    route = list(raw[UNIT_ROUTE:UNIT_ROUTE + UNIT_ROUTE_BYTES])
    return {
        "raw_hex": bytes(raw).hex(),
        "pixel_x": _s16(raw, UNIT_PIXEL_X),
        "pixel_y": _s16(raw, UNIT_PIXEL_Y),
        "sequence": _u16(raw, UNIT_SEQUENCE),
        "sequence_flags": raw[UNIT_SEQUENCE_FLAGS],
        "animation_timer": raw[UNIT_ANIMATION_TIMER],
        "animation": raw[UNIT_ANIMATION],
        "frame": raw[UNIT_FRAME],
        "face": raw[UNIT_FACE],
        "x": _u16(raw, UNIT_X),
        "y": _u16(raw, UNIT_Y),
        "flags": raw[UNIT_FLAGS],
        "live": (raw[UNIT_FLAGS] & (UNIT_FREE | UNIT_DEAD)) == 0,
        "hp": _u16(raw, UNIT_HP),
        "type": raw[UNIT_TYPE],
        "owner": raw[UNIT_OWNER],
        "order": raw[UNIT_ORDER],
        "next_order": raw[UNIT_NEXT_ORDER],
        "route": route,
        "route_headings": [DIRECTIONS[value] if value < len(DIRECTIONS)
                           else f"0x{value:02x}" for value in route],
        "route_index": raw[UNIT_ROUTE_INDEX],
        "ai_home": [_u16(raw, UNIT_AI_HOME_X), _u16(raw, UNIT_AI_HOME_Y)],
        "ai_behavior": raw[UNIT_AI_BEHAVIOR],
        "ai_marker": raw[UNIT_AI_MARKER],
        "order_point": [_u16(raw, UNIT_ORDER_X), _u16(raw, UNIT_ORDER_Y)],
        "target_pointer": f"0x{_u32(raw, UNIT_TARGET):08x}",
    }


def _field_for_offset(offset: int) -> str | None:
    for start, width, name in FIELD_RANGES:
        if start <= offset < start + width:
            return name
    return None


def raw_changes(before_hex: str, after_hex: str) -> list[dict[str, object]]:
    before = bytes.fromhex(before_hex)
    after = bytes.fromhex(after_hex)
    return [
        {
            "offset": offset,
            "offset_hex": f"0x{offset:02x}",
            "field": _field_for_offset(offset),
            "before": before[offset],
            "after": after[offset],
        }
        for offset in range(min(len(before), len(after)))
        if before[offset] != after[offset]
    ]


def _decode_player_sim(raw: bytes) -> dict[str, int]:
    values = PLAYER_SIM_RECORD.unpack(raw)
    return dict(zip(PLAYER_SIM_FIELDS, values, strict=True))


def _map_window(map_size: int, cells: list[int], squares: list[int],
        center: tuple[int, int], radius: int) -> dict[str, Any] | None:
    center_x, center_y = center
    if not (0 <= center_x < map_size and 0 <= center_y < map_size):
        return None
    left = max(0, center_x - radius)
    right = min(map_size - 1, center_x + radius)
    top = max(0, center_y - radius)
    bottom = min(map_size - 1, center_y + radius)
    rows = []
    for y in range(top, bottom + 1):
        indices = [y * map_size + x for x in range(left, right + 1)]
        rows.append({
            "y": y,
            "cells": [f"{cells[index]:04x}" for index in indices],
            "squares": [f"{squares[index]:04x}" for index in indices],
        })
    return {
        "center": [center_x, center_y],
        "x_range": [left, right],
        "y_range": [top, bottom],
        "rows": rows,
    }


def read_native_snapshots(state_source: BinaryIO, wanted_cycles: set[int],
        focus_slots: set[int], radius: int) -> dict[int, dict[str, Any]]:
    """Reconstruct focused unit/map/projectile state at selected cycles."""
    header = STATE_HEADER.unpack(
        _read_exact(state_source, STATE_HEADER.size, "BNE state header")
    )
    (_magic, major, minor, header_bytes, unit_bytes, unit_limit,
     player_count, _flags) = header
    if (major != 1 or minor != 1 or header_bytes != STATE_HEADER.size
            or unit_bytes != UNIT_BYTES or unit_limit != UNIT_LIMIT
            or player_count != 16):
        raise ValueError("divergence packets require BNE raw state schema 1.1")

    raw_units = [bytearray(unit_bytes) for _ in range(unit_limit)]
    unit_generations = [0] * unit_limit
    raw_bullets = [bytearray(BULLET_BYTES) for _ in range(400)]
    bullet_generations = [0] * 400
    live_bullets = [False] * 400
    map_cells = [0] * (128 * 128)
    map_squares = [0] * (128 * 128)
    snapshots: dict[int, dict[str, Any]] = {}
    pending: dict[str, Any] | None = None
    maximum = max(wanted_cycles)

    while True:
        raw_header = state_source.read(CHUNK_HEADER.size)
        if not raw_header:
            break
        tag, payload_bytes = CHUNK_HEADER.unpack(raw_header)
        payload = io.BytesIO(_read_exact(
            state_source, payload_bytes, f"{tag!r} state payload"
        ))
        if tag == b"CYCL":
            cycle, seed, pool_count, changed_units = CYCLE_HEADER.unpack(
                _read_exact(payload, CYCLE_HEADER.size, "cycle header")
            )
            players = [PLAYER_RECORD.unpack(
                _read_exact(payload, PLAYER_RECORD.size, "player record"))
                for _ in range(player_count)]
            changed_slots: list[int] = []
            for _ in range(changed_units):
                slot, generation = UNIT_DELTA_HEADER.unpack(
                    _read_exact(payload, UNIT_DELTA_HEADER.size,
                                "unit delta header")
                )
                raw = _read_exact(payload, unit_bytes, "unit delta")
                raw_units[slot][:] = raw
                unit_generations[slot] = generation
                changed_slots.append(slot)
            pending = {
                "cycle": cycle,
                "seed": f"{seed:08x}",
                "pool_count": pool_count,
                "players": players,
                "changed_unit_slots": changed_slots,
            }
        elif tag == b"AUXL":
            if pending is None:
                raise ValueError("BNE AUXL has no preceding cycle")
            (cycle, bullet_count, changed_bullets, map_size,
             changed_tiles) = AUX_HEADER.unpack(
                _read_exact(payload, AUX_HEADER.size, "AUXL header")
            )
            if cycle != pending["cycle"]:
                raise ValueError("BNE AUXL cycle does not match CYCL")
            sim_raw = [_read_exact(payload, PLAYER_SIM_RECORD.size,
                                   "player simulation record")
                       for _ in range(player_count)]
            changed_projectiles = []
            for _ in range(changed_bullets):
                slot, generation = BULLET_DELTA_HEADER.unpack(
                    _read_exact(payload, BULLET_DELTA_HEADER.size,
                                "projectile delta header")
                )
                raw = _read_exact(payload, BULLET_BYTES, "projectile delta")
                raw_bullets[slot][:] = raw
                bullet_generations[slot] = generation
                live_bullets[slot] = (raw[BULLET_FLAGS] & BULLET_FREE) == 0
                changed_projectiles.append({
                    "slot": slot,
                    "generation": generation,
                    "live": live_bullets[slot],
                    "raw_hex": raw.hex(),
                })
            changed_map_indices = []
            for _ in range(changed_tiles):
                index, cell, square = MAP_DELTA.unpack(
                    _read_exact(payload, MAP_DELTA.size, "map delta")
                )
                map_cells[index] = cell
                map_squares[index] = square
                changed_map_indices.append(index)
            if cycle in wanted_cycles:
                units = {}
                centers: dict[str, tuple[int, int]] = {}
                for slot in sorted(focus_slots):
                    if slot >= pending["pool_count"]:
                        units[str(slot)] = None
                        continue
                    decoded = decode_unit(raw_units[slot])
                    decoded["generation"] = unit_generations[slot]
                    decoded["changed_this_cycle"] = (
                        slot in pending["changed_unit_slots"]
                    )
                    units[str(slot)] = decoded
                    centers[f"unit-{slot}-position"] = (
                        decoded["x"], decoded["y"]
                    )
                    centers[f"unit-{slot}-order-point"] = tuple(
                        decoded["order_point"]
                    )
                windows = {}
                seen_centers: set[tuple[int, int]] = set()
                for label, center in centers.items():
                    if center in seen_centers:
                        continue
                    window = _map_window(
                        map_size, map_cells, map_squares, center, radius
                    )
                    if window is not None:
                        windows[label] = window
                        seen_centers.add(center)
                active_players = {}
                for player, bank in enumerate(pending["players"]):
                    controller, gold, wood, oil = bank
                    if controller == CONTROLLER_NOBODY:
                        continue
                    active_players[str(player)] = {
                        "controller": controller,
                        "gold": gold,
                        "wood": wood,
                        "oil": oil,
                        "simulation": _decode_player_sim(sim_raw[player]),
                    }
                live_projectiles = [
                    {
                        "slot": slot,
                        "generation": bullet_generations[slot],
                        "raw_sha256": hashlib.sha256(
                            raw_bullets[slot]
                        ).hexdigest(),
                    }
                    for slot in range(bullet_count) if live_bullets[slot]
                ]
                snapshots[cycle] = {
                    "cycle": cycle,
                    "sync_seed": pending["seed"],
                    "pool_count": pending["pool_count"],
                    "changed_unit_slots": pending["changed_unit_slots"],
                    "units": units,
                    "players": active_players,
                    "projectiles": {
                        "pool_count": bullet_count,
                        "live": live_projectiles,
                        "changed": changed_projectiles,
                    },
                    "map": {
                        "size": map_size,
                        "changed_tile_count": changed_tiles,
                        "changed_indices": (changed_map_indices
                                            if changed_tiles <= 256 else []),
                        "windows": windows,
                    },
                }
            pending = None
            if cycle >= maximum:
                break
        elif tag == b"DONE":
            break
        else:
            raise ValueError(f"unexpected BNE state chunk {tag!r}")
    missing = wanted_cycles - set(snapshots)
    if missing:
        raise ValueError(f"BNE state has no requested cycles {sorted(missing)}")
    ordered = [snapshots[cycle] for cycle in sorted(snapshots)]
    for slot in focus_slots:
        previous: dict[str, Any] | None = None
        for snapshot in ordered:
            unit = snapshot["units"].get(str(slot))
            if unit is not None and previous is not None:
                unit["raw_changes_from_previous_packet_cycle"] = raw_changes(
                    previous["raw_hex"], unit["raw_hex"]
                )
            elif unit is not None:
                unit["raw_changes_from_previous_packet_cycle"] = []
            previous = unit
    return snapshots


def _semantic_differences(native: dict[str, Any] | None,
        java: dict[str, Any] | None) -> list[dict[str, object]]:
    if native is None or java is None:
        return [{
            "field": "population",
            "oracle": "present" if native is not None else "absent",
            "java": "present" if java is not None else "absent",
        }]
    return [
        {"field": field, "oracle": native[field], "java": java[field]}
        for field in ("type", "player", "x", "y", "hp", "order", "removed")
        if native[field] != java[field]
    ]


def _subtile_comparison(native: dict[str, Any] | None,
        java: dict[str, Any] | None) -> dict[str, Any]:
    required = ("pixel_x", "pixel_y")
    available = native is not None and java is not None \
        and all(field in native and field in java for field in required)
    if not available:
        return {"available": False, "hidden_mismatch": False}
    tile_equal = native["x"] == java["x"] and native["y"] == java["y"]
    pixel_equal = (native["pixel_x"] == java["pixel_x"]
                   and native["pixel_y"] == java["pixel_y"])
    return {
        "available": True,
        "oracle_pixel": [native["pixel_x"], native["pixel_y"]],
        "java_pixel": [java["pixel_x"], java["pixel_y"]],
        "tile_equal": tile_equal, "pixel_equal": pixel_equal,
        "hidden_mismatch": tile_equal and not pixel_equal,
    }


def _nearby(units: dict[int, dict[str, Any]], center: dict[str, Any] | None,
        radius: int) -> list[dict[str, Any]]:
    if center is None:
        return []
    result = []
    for unit_id, unit in units.items():
        distance = max(abs(unit["x"] - center["x"]),
                       abs(unit["y"] - center["y"]))
        if distance <= radius:
            result.append({"id": unit_id, "distance": distance, **unit})
    return sorted(result, key=lambda unit: (unit["distance"], unit["id"]))


def build_semantic_window(native: dict[int, dict[str, Any]],
        java: dict[int, dict[str, Any]], pairings: dict[int, dict[int, int]],
        cycles: list[int], focus_slots: set[int], radius: int) \
        -> dict[str, Any]:
    result: dict[str, Any] = {}
    for cycle in cycles:
        native_cycle = native[cycle]
        java_cycle = java[cycle]
        focused = []
        for slot in sorted(focus_slots):
            native_unit = native_cycle["units"].get(slot)
            java_id = pairings.get(cycle, {}).get(slot)
            java_unit = (java_cycle["units"].get(java_id)
                         if java_id is not None else None)
            focused.append({
                "native_slot": slot,
                "java_id": java_id,
                "oracle": native_unit,
                "java": java_unit,
                "differences": _semantic_differences(native_unit, java_unit),
                "subtile": _subtile_comparison(native_unit, java_unit),
                "oracle_nearby": _nearby(
                    native_cycle["units"], native_unit, radius
                ),
                "java_nearby": _nearby(java_cycle["units"], java_unit, radius),
            })
        result[str(cycle)] = {
            "cycle": cycle,
            "oracle_seed": native_cycle["seed"],
            "java_seed": java_cycle["seed"],
            "oracle_players": native_cycle["players"],
            "java_players": java_cycle["players"],
            "focus": focused,
        }
    return result


def _format_unit_line(unit_id: int, unit: dict[str, Any]) -> str:
    pixel = (f" px {unit['pixel_x']} {unit['pixel_y']}"
             if "pixel_x" in unit and "pixel_y" in unit else "")
    return (f"u {unit_id} {unit['type']} p{unit['player']} {unit['x']} "
            f"{unit['y']} hp {unit['hp']} o {unit['order']}"
            f"{' removed' if unit['removed'] else ''}{pixel}")


def format_focus_trace(trace: dict[int, dict[str, Any]], cycles: list[int],
        focus_ids: dict[int, set[int]], radius: int) -> str:
    lines = []
    for cycle in cycles:
        state = trace[cycle]
        lines.append(f"cycle {cycle} seed {state['seed']}")
        for player, bank in sorted(state["players"].items()):
            lines.append(
                f"p {player} gold {bank['gold']} wood {bank['wood']} "
                f"oil {bank['oil']}"
            )
        selected: set[int] = set()
        for unit_id in focus_ids.get(cycle, set()):
            focus = state["units"].get(unit_id)
            if focus is None:
                continue
            selected.update(unit["id"] for unit in _nearby(
                state["units"], focus, radius
            ))
        for unit_id in sorted(selected):
            lines.append(_format_unit_line(unit_id, state["units"][unit_id]))
    return "\n".join(lines) + "\n"


def _diagnostic_events(trace_source: BinaryIO, cycles: set[int],
        focus_slots: set[int]) -> list[str]:
    events = []
    for raw_line in trace_source:
        line = raw_line.decode("utf-8").rstrip("\r\n")
        if not line.startswith("# bne-trace"):
            continue
        cycle_match = EVENT_CYCLE.search(line)
        if cycle_match is None or int(cycle_match.group(1)) not in cycles:
            continue
        unit_match = EVENT_UNIT.search(line)
        if unit_match is not None and int(unit_match.group(1)) not in focus_slots:
            continue
        events.append(line)
    return events


def summarize_java_diagnostics(content: str) -> list[str]:
    """Compress large opt-in path lines while retaining causal coordinates."""
    result = []
    for line in content.splitlines():
        if matched := JAVA_PATH_EVENT.match(line):
            internal = int(matched.group(1))
            result.append(
                f"path internal={internal} fixture={internal - 2} "
                f"unit={matched.group(2)} from={matched.group(3)},"
                f"{matched.group(4)} goal={matched.group(5)},"
                f"{matched.group(6)} stride={matched.group(7)} "
                f"result={matched.group(8)} path={matched.group(9)}"
            )
        elif matched := JAVA_STEP_EVENT.match(line):
            internal = int(matched.group(1))
            result.append(
                f"step internal={internal} fixture={internal - 2} "
                f"unit={matched.group(2)} type={matched.group(3)} "
                f"from={matched.group(4)},{matched.group(5)} "
                f"to={matched.group(6)},{matched.group(7)} "
                f"stride={matched.group(8)}"
            )
    return result


def _write_text(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8")


def _shell_command(arguments: list[str], environment: dict[str, str] | None = None) \
        -> str:
    prefix = ""
    if environment:
        prefix = " ".join(
            f"{key}={shlex.quote(value)}" for key, value in environment.items()
        ) + " "
    return prefix + shlex.join(arguments)


def _render_map_window(window: dict[str, Any]) -> list[str]:
    left, right = window["x_range"]
    lines = [
        f"Center `{window['center'][0]},{window['center'][1]}`; "
        f"X `{left}..{right}`.",
        "",
        "```text",
        "      " + " ".join(f"{x:04x}" for x in range(left, right + 1)),
    ]
    for row in window["rows"]:
        lines.append(f"y={row['y']:3d} " + " ".join(row["squares"]))
    lines.extend(["```", ""])
    return lines


def render_readme(packet: dict[str, Any]) -> str:
    lines = [
        f"# BNE divergence packet — {packet['case']['id']}",
        "",
        f"First semantic divergence: fixture cycle "
        f"**{packet['divergence']['cycle']}**. Window: "
        f"**{packet['window']['start']}..{packet['window']['end']}**.",
        "",
        "## Findings",
        "",
    ]
    for finding in packet["divergence"]["findings"]:
        lines.append(f"- Cycle {finding.get('cycle')}: {finding.get('message')}")
    lines.extend([
        "",
        "## Focus pairing",
        "",
        "| Native slot | Java ID at divergence |",
        "|---:|---:|",
    ])
    divergence_semantic = packet["semantic"][str(packet["divergence"]["cycle"])]
    for focus in divergence_semantic["focus"]:
        lines.append(f"| {focus['native_slot']} | {focus['java_id'] or '—'} |")
    lines.extend([
        "",
        "## Native raw-unit timeline",
        "",
        "| Cycle | Slot | Tile / pixel | Order/next | Goal | Sequence/timer | "
        "Animation/frame/face | Route index and bytes |",
        "|---:|---:|---|---|---|---|---|---|",
    ])
    for cycle in range(packet["window"]["start"], packet["window"]["end"] + 1):
        snapshot = packet["native_state"][str(cycle)]
        for slot in packet["focus_native_slots"]:
            unit = snapshot["units"].get(str(slot))
            if unit is None:
                lines.append(f"| {cycle} | {slot} | absent | — | — | — | — | — |")
                continue
            route = " ".join(f"{value:02x}" for value in unit["route"])
            lines.append(
                f"| {cycle} | {slot} | {unit['x']},{unit['y']} / "
                f"{unit['pixel_x']},{unit['pixel_y']} | "
                f"{unit['order']}/{unit['next_order']} | "
                f"{unit['order_point'][0]},{unit['order_point'][1]} | "
                f"{unit['sequence']}/{unit['animation_timer']} | "
                f"{unit['animation']}/{unit['frame']}/{unit['face']} | "
                f"{unit['route_index']}: `{route}` |"
            )
    lines.extend(["", "### Native raw-byte changes", ""])
    for cycle in range(packet["window"]["start"], packet["window"]["end"] + 1):
        snapshot = packet["native_state"][str(cycle)]
        for slot in packet["focus_native_slots"]:
            unit = snapshot["units"].get(str(slot))
            changes = (unit or {}).get("raw_changes_from_previous_packet_cycle", [])
            if not changes:
                continue
            summary = ", ".join(
                f"{change['offset_hex']} "
                f"{change['field'] or 'unknown'} "
                f"{change['before']:02x}→{change['after']:02x}"
                for change in changes
            )
            lines.append(f"- Cycle {cycle}, slot {slot}: {summary}")
    hidden = []
    for cycle in range(packet["window"]["start"], packet["window"]["end"] + 1):
        for focus in packet["semantic"][str(cycle)]["focus"]:
            if focus["subtile"]["hidden_mismatch"]:
                hidden.append((cycle, focus))
    lines.extend(["", "## Hidden sub-tile precursors", ""])
    if hidden:
        lines.extend([
            "Tile positions still agree at these cycles, but authenticated "
            "native IX/IY and Java pixel position do not:", "",
            "| Cycle | Native slot → Java ID | Oracle pixel | Java pixel |",
            "|---:|---|---|---|",
        ])
        for cycle, focus in hidden:
            subtile = focus["subtile"]
            lines.append(
                f"| {cycle} | {focus['native_slot']} → {focus['java_id']} | "
                f"{subtile['oracle_pixel'][0]},{subtile['oracle_pixel'][1]} | "
                f"{subtile['java_pixel'][0]},{subtile['java_pixel'][1]} |"
            )
    else:
        lines.append(
            "No hidden sub-tile mismatch was observed for the focus units in "
            "this packet window, or the Java trace predates pixel metadata."
        )
    lines.extend([
        "",
        "## Semantic timeline",
        "",
        "| Cycle | Native slot → Java ID | Oracle | Java | Differences |",
        "|---:|---|---|---|---|",
    ])
    for cycle in range(packet["window"]["start"], packet["window"]["end"] + 1):
        for focus in packet["semantic"][str(cycle)]["focus"]:
            oracle = focus["oracle"]
            java = focus["java"]
            oracle_text = (f"{oracle['x']},{oracle['y']} {oracle['order']}"
                           if oracle else "absent")
            java_text = (f"{java['x']},{java['y']} {java['order']}"
                         if java else "absent")
            differences = ", ".join(
                f"{item['field']} {item['oracle']}→{item['java']}"
                for item in focus["differences"]
            ) or "—"
            lines.append(
                f"| {cycle} | {focus['native_slot']} → "
                f"{focus['java_id'] or '—'} | {oracle_text} | {java_text} | "
                f"{differences} |"
            )
    if packet.get("java_diagnostic_highlights"):
        lines.extend([
            "",
            "## Retained Java diagnostics",
            "",
            "Java diagnostic cycles are internal cycles; fixture cycle is "
            "`internal - 2`.",
            "",
        ])
        lines.extend(
            f"- `{highlight}`"
            for highlight in packet["java_diagnostic_highlights"]
        )
    divergence_state = packet["native_state"][str(packet["divergence"]["cycle"])]
    if divergence_state["map"]["windows"]:
        lines.extend(["", "## Native map square flags at divergence", ""])
        for label, window in divergence_state["map"]["windows"].items():
            lines.append(f"### {label}")
            lines.append("")
            lines.extend(_render_map_window(window))
    lines.extend([
        "## Included files",
        "",
        "- `packet.json`: complete structured evidence, raw bytes, map windows, "
        "projectile summaries, extended player state, and identities.",
        "- `oracle-focus.trace.txt`: native semantic window plus nearby units.",
        "- `java-focus.trace.txt`: paired Java semantic window plus nearby units.",
    ])
    for stream_name in packet.get("java_process_output", {}):
        lines.append(
            f"- `java-{stream_name}.txt`: retained Java process {stream_name} "
            "from the survey run."
        )
    lines.extend([
        "",
        "## Evidence gaps",
        "",
        "- Schema 1.1 does not seal the asynchronous RNG state/call ledger.",
        "- The Java trace includes diagnostic pixel position but not its route "
        "buffer, sequence cursor, order delay, or collision counters.",
        "- Native target pointers are preserved as raw addresses but cannot be "
        "converted to pool slots without the run's pool base.",
        "",
        "## Targeted reruns",
        "",
        "Java path diagnostic:",
        "",
        "```sh",
        packet["recommended_reruns"]["java_path"],
        "```",
        "",
        "Native unit diagnostic on the oracle host:",
        "",
        "```sh",
        packet["recommended_reruns"]["native_unit"],
        "```",
        "",
    ])
    return "\n".join(lines)


def _focus_units(case_record: dict[str, Any], extra_units: list[int]) -> set[int]:
    units = set(extra_units)
    findings = case_record.get("findings")
    if isinstance(findings, list):
        for finding in findings:
            if isinstance(finding, dict) and isinstance(finding.get("unit"), int):
                units.add(finding["unit"])
    if not units:
        comparison = case_record.get("comparison_output", "")
        units.update(int(value) for value in re.findall(
            r"cycle \d+: unit (\d+)", comparison
        ))
    return units


def _primary_focus_pair(focus_slots: set[int],
        pairings: dict[int, dict[int, int]], cycle: int) \
        -> tuple[int | None, int | None]:
    """Keep the recommended native and Java diagnostics on the same unit."""
    primary_native = min(focus_slots) if focus_slots else None
    primary_java = pairings.get(cycle, {}).get(primary_native) \
        if primary_native is not None else None
    return primary_native, primary_java


def _resolve_fixture(survey: dict[str, Any], case_id: str,
        case_record: dict[str, Any]) -> tuple[Path, dict[str, Any]]:
    index_path = Path(survey["index"]).expanduser().resolve()
    index = json.loads(index_path.read_text(encoding="utf-8"))
    indexed = next((record for record in index.get("cases", [])
                    if record.get("id") == case_id), None)
    if indexed is None:
        raise ValueError(f"case {case_id!r} is absent from {index_path}")
    relative = Path(indexed["fixture"]["path"])
    fixture = (index_path.parent / relative).resolve()
    if not fixture.is_relative_to(index_path.parent):
        raise ValueError(f"unsafe fixture path for {case_id!r}")
    actual = file_identity(fixture)
    expected = {key: indexed["fixture"][key] for key in ("bytes", "sha256")}
    if actual != expected:
        raise ValueError(f"indexed fixture identity changed: {fixture}")
    if indexed.get("fixture_id") != case_record.get("fixture_id"):
        raise ValueError("survey and corpus index name different fixture identities")
    return fixture, indexed


def generate_packet(survey_path: Path, case_id: str, output_dir: Path,
        *, before: int = 4, after: int = 0, radius: int = 4,
        extra_units: list[int] | None = None,
        source_dir: Path | None = None) -> dict[str, Any]:
    survey_path = survey_path.expanduser().resolve()
    output_dir = output_dir.expanduser().resolve()
    survey = json.loads(survey_path.read_text(encoding="utf-8"))
    if survey.get("schema") != 1 or not isinstance(survey.get("cases"), list):
        raise ValueError(f"invalid BNE Java survey: {survey_path}")
    case_record = next((record for record in survey["cases"]
                       if record.get("id") == case_id), None)
    if case_record is None:
        raise ValueError(f"survey has no case {case_id!r}")
    if case_record.get("state") != "divergent":
        raise ValueError(f"case {case_id!r} is not divergent in this survey")
    divergence = case_record.get("first_divergence_cycle")
    compared_cycles = case_record.get("compared_cycles")
    if not isinstance(divergence, int) or not isinstance(compared_cycles, int):
        raise ValueError("divergent case has no valid cycle boundary")
    start = max(1, divergence - before)
    end = min(compared_cycles, divergence + after)
    cycles = list(range(start, end + 1))
    focus_slots = _focus_units(case_record, extra_units or [])
    fixture, indexed = _resolve_fixture(survey, case_id, case_record)
    trace_record = case_record.get("java_trace")
    if not isinstance(trace_record, dict) or not isinstance(
            trace_record.get("path"), str):
        raise ValueError("survey case has no Java trace identity")
    java_trace_path = Path(trace_record["path"]).expanduser().resolve()
    actual_trace = file_identity(java_trace_path)
    expected_trace = {key: trace_record[key] for key in ("bytes", "sha256")}
    if actual_trace != expected_trace:
        raise ValueError(f"Java trace identity changed: {java_trace_path}")
    java_process_text: dict[str, str] = {}
    java_process_identity: dict[str, dict[str, object]] = {}
    for stream_name, stream_record in (
            case_record.get("java_process_output") or {}).items():
        if not isinstance(stream_record, dict) \
                or not isinstance(stream_record.get("path"), str):
            continue
        stream_path = Path(stream_record["path"]).expanduser().resolve()
        actual_stream = file_identity(stream_path)
        expected_stream = {
            key: stream_record[key] for key in ("bytes", "sha256")
        }
        if actual_stream != expected_stream:
            raise ValueError(f"Java {stream_name} identity changed: {stream_path}")
        java_process_text[stream_name] = stream_path.read_text(encoding="utf-8")
        java_process_identity[stream_name] = {
            "path": str(stream_path), **actual_stream,
        }
    if output_dir.exists():
        raise ValueError(f"packet output already exists: {output_dir}")

    with zipfile.ZipFile(fixture) as archive:
        manifest = json.loads(archive.read("manifest.json"))
        if manifest["fixture"]["id"] != case_record["fixture_id"]:
            raise ValueError("fixture manifest identity differs from the survey")
        with tempfile.TemporaryFile() as normalized, \
                archive.open("trace.txt") as trace_source, \
                archive.open("state.bin") as state_source:
            bne_compare.normalize_fixture_trace(
                trace_source, state_source, normalized, end
            )
            normalized.seek(0)
            native_trace = parse_trace(normalized)
        with archive.open("state.bin") as state_source:
            augment_trace_pixels(state_source, native_trace)
        with archive.open("state.bin") as state_source:
            native_snapshots = read_native_snapshots(
                state_source, set(cycles), focus_slots, radius
            )
        with archive.open("trace.txt") as trace_source:
            diagnostic_events = _diagnostic_events(
                trace_source, set(cycles), focus_slots
            )
    with java_trace_path.open("rb") as java_source:
        java_trace = parse_trace(java_source)
    missing_native = set(cycles) - set(native_trace)
    missing_java = set(cycles) - set(java_trace)
    if missing_native or missing_java:
        raise ValueError(
            f"trace window incomplete; native missing {sorted(missing_native)}, "
            f"Java missing {sorted(missing_java)}"
        )
    pairings = align_trace_units(native_trace, java_trace)
    semantic = build_semantic_window(
        native_trace, java_trace, pairings, cycles, focus_slots, radius
    )
    native_focus = {cycle: set(focus_slots) for cycle in cycles}
    java_focus = {
        cycle: {pairings.get(cycle, {}).get(slot) for slot in focus_slots}
               - {None}
        for cycle in cycles
    }
    primary_native, primary_java = _primary_focus_pair(
        focus_slots, pairings, divergence)
    asset = survey.get("asset_source") or {}
    recorded_source = (survey.get("runtime") or {}).get("source_dir")
    rerun_source = (str(source_dir.expanduser().resolve())
                    if source_dir is not None else
                    (str(Path(recorded_source).expanduser().resolve())
                     if isinstance(recorded_source, str)
                     and recorded_source.strip() else "/path/to/chonkcraft"))
    java_arguments = [
        "python3", "tools/bne-harness/scripts/bne_java.py", "survey",
        str(Path(survey["index"]).expanduser().resolve()),
        "--case", case_id,
        "--through", str(end),
        "--source-dir", rerun_source,
        # A durable destination, not /private/tmp. This rerun is the only way
        # to get the sub-tile fields cadence needs, and its output was landing
        # somewhere the operating system is free to delete -- so the rerun got
        # done again next time anyone asked the same question.
        "--output-dir", f".bne-subtile-evidence/{case_id}",
        "--skip-build",
    ]
    if asset.get("kind") == "chonkpack" and asset.get("path"):
        java_arguments.extend(("--asset-pack", str(asset["path"])))
    java_environment = {}
    if primary_java is not None:
        java_environment = {
            "CHONKCRAFT_TRACE_BNE_PATH": str(primary_java),
            "CHONKCRAFT_TRACE_BNE_STEP": str(primary_java),
            "CHONKCRAFT_TRACE_BNE_SUBTILE": "1",
        }
    native_arguments = [
        "python3", "scripts/bne_headless.py", "run",
        "--oracle-root", "~/.local/share/chonkcraft-bne-oracle",
        "--case-id", f"diag-{case_id}-{divergence}",
        "--output", f"output/diag-{case_id}-{divergence}",
        "--scenario", case_record["scenario"],
        "--cycles", str(end),
        "--seed", str(case_record["seed"]),
    ]
    if primary_native is not None:
        native_arguments.extend(("--trace-unit", str(primary_native)))
    native_inner = "cd ~/.local/share/chonkcraft-bne-oracle/harness && " \
        + _shell_command(native_arguments)
    java_highlights = summarize_java_diagnostics(
        java_process_text.get("stderr", "")
    )

    packet = {
        "schema": 1,
        "case": {
            "id": case_id,
            "scenario": case_record.get("scenario"),
            "java_map": case_record.get("java_map"),
            "seed": case_record.get("seed"),
            "fixture_id": case_record.get("fixture_id"),
        },
        "comparison_tier": case_record.get("comparison_tier",
                                            survey.get("comparison_tier")),
        "engine": survey.get("engine"),
        "asset_source": survey.get("asset_source"),
        "identities": {
            "survey": {"path": str(survey_path), **file_identity(survey_path)},
            "corpus_index": str(Path(survey["index"]).expanduser().resolve()),
            "fixture": {"path": str(fixture), **file_identity(fixture)},
            "java_trace": {"path": str(java_trace_path), **actual_trace},
        },
        "window": {"start": start, "end": end, "before": before,
                   "after": after, "radius": radius},
        "divergence": {
            "cycle": divergence,
            "findings": case_record.get("findings", []),
            "comparison_output": case_record.get("comparison_output"),
        },
        "focus_native_slots": sorted(focus_slots),
        "semantic": semantic,
        "native_state": {
            str(cycle): native_snapshots[cycle] for cycle in cycles
        },
        "native_diagnostic_events": diagnostic_events,
        "java_process_output": java_process_identity,
        "java_diagnostic_highlights": java_highlights,
        "recommended_reruns": {
            "java_path": _shell_command(java_arguments, java_environment),
            "native_unit": _shell_command(
                ["ssh", DEFAULT_REMOTE_HOST, native_inner]
            ),
        },
        "indexed_fixture": {
            "bytes": indexed["fixture"]["bytes"],
            "sha256": indexed["fixture"]["sha256"],
        },
    }
    oracle_focus_text = format_focus_trace(
        native_trace, cycles, native_focus, radius
    )
    java_focus_text = format_focus_trace(java_trace, cycles, java_focus, radius)

    output_dir.parent.mkdir(parents=True, exist_ok=True)
    temporary: Path | None = Path(tempfile.mkdtemp(
        prefix=output_dir.name + ".", suffix=".tmp", dir=output_dir.parent
    ))
    try:
        _write_text(
            temporary / "packet.json",
            json.dumps(packet, indent=2, sort_keys=True) + "\n",
        )
        _write_text(temporary / "README.md", render_readme(packet))
        _write_text(temporary / "oracle-focus.trace.txt", oracle_focus_text)
        _write_text(temporary / "java-focus.trace.txt", java_focus_text)
        for stream_name, content in java_process_text.items():
            _write_text(temporary / f"java-{stream_name}.txt", content)
        os.replace(temporary, output_dir)
        temporary = None
    finally:
        if temporary is not None and temporary.exists():
            shutil.rmtree(temporary)
    return packet
