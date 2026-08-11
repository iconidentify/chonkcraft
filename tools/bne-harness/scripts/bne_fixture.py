#!/usr/bin/env python3
"""Read, validate, and seal BNE oracle state fixtures."""

from __future__ import annotations

import hashlib
import io
import json
import os
from pathlib import Path
import struct
import tempfile
from typing import BinaryIO
import zipfile

STATE_MAGIC = b"BNESTATE"
STATE_MAJOR = 1
STATE_MINOR = 1
STATE_FLAG_UNIT_DELTAS = 1
STATE_FLAG_BULLET_DELTAS = 2
STATE_FLAG_PLAYER_SIM = 4
STATE_FLAG_MAP_DELTAS = 8
STATE_FLAGS = (STATE_FLAG_UNIT_DELTAS | STATE_FLAG_BULLET_DELTAS
               | STATE_FLAG_PLAYER_SIM | STATE_FLAG_MAP_DELTAS)
STATE_HEADER = struct.Struct("<8sHHIIIII")
CHUNK_HEADER = struct.Struct("<4sI")
CYCLE_HEADER = struct.Struct("<IIII")
PLAYER_RECORD = struct.Struct("<IIII")
UNIT_DELTA_HEADER = struct.Struct("<II")
AUX_HEADER = struct.Struct("<IIIII")
PLAYER_SIM_RECORD = struct.Struct("<8H10B2x4I")
BULLET_DELTA_HEADER = struct.Struct("<II")
MAP_DELTA = struct.Struct("<IHH")
DONE_RECORD = struct.Struct("<I")

UNIT_X = 24
UNIT_Y = 26
UNIT_FLAGS = 30
UNIT_HP = 34
UNIT_OWNER = 44
UNIT_FREE = 0x01
UNIT_DEAD = 0x04
UNIT_HIDDEN = 0x08
CONTROLLER_NOBODY = 3
BULLET_BYTES = 64
BULLET_LIMIT = 400
BULLET_FLAGS = 53
BULLET_FREE = 0x01
MAP_LIMIT = 128
MAX_CHUNK_BYTES = 32 * 1024 * 1024


def _read_exact(source: BinaryIO, size: int, description: str) -> bytes:
    data = source.read(size)
    if len(data) != size:
        raise ValueError(f"truncated BNE state stream while reading {description}")
    return data


def _word(raw: bytes | bytearray, offset: int) -> int:
    return int.from_bytes(raw[offset:offset + 2], "little")


def _identity_path(path: Path) -> dict[str, int | str]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            size += len(block)
            digest.update(block)
    return {"bytes": size, "sha256": digest.hexdigest()}


def validate_state_source(source: BinaryIO,
        expected_cycles: int | None = None) -> dict[str, int | str]:
    """Validate one raw BNESTATE stream and reconstruct every state delta."""
    header_values = STATE_HEADER.unpack(
        _read_exact(source, STATE_HEADER.size, "file header"))
    (magic, major, minor, header_bytes, unit_bytes, unit_limit,
     player_count, flags) = header_values
    if magic != STATE_MAGIC:
        raise ValueError(f"unsupported BNE state magic {magic!r}")
    if major != STATE_MAJOR or minor not in (0, STATE_MINOR):
        raise ValueError(
            f"unsupported BNE state schema {major}.{minor}; "
            f"expected {STATE_MAJOR}.0 or {STATE_MAJOR}.{STATE_MINOR}"
        )
    if header_bytes != STATE_HEADER.size:
        raise ValueError(f"unexpected BNE state header size {header_bytes}")
    if unit_bytes != 152 or unit_limit != 1600 or player_count != 16:
        raise ValueError(
            "BNE state layout does not match the pinned 2.02b unit/player layout"
        )
    expected_flags = STATE_FLAG_UNIT_DELTAS if minor == 0 else STATE_FLAGS
    if flags != expected_flags:
        raise ValueError(f"unsupported BNE state flags 0x{flags:08x}")
    has_aux = minor == STATE_MINOR

    raw_units = [bytearray(unit_bytes) for _ in range(unit_limit)]
    known_units = [False] * unit_limit
    live_units = [False] * unit_limit
    generations = [0] * unit_limit
    cycles: list[int] = []
    delta_records = 0
    active_player_records = 0
    live_unit_records = 0
    seed_digest = hashlib.sha256()
    player_digest = hashlib.sha256()
    unit_digest = hashlib.sha256()
    raw_bullets = [bytearray(BULLET_BYTES) for _ in range(BULLET_LIMIT)]
    known_bullets = [False] * BULLET_LIMIT
    live_bullets = [False] * BULLET_LIMIT
    bullet_generations = [0] * BULLET_LIMIT
    map_cells = [0] * (MAP_LIMIT * MAP_LIMIT)
    map_squares = [0] * (MAP_LIMIT * MAP_LIMIT)
    known_map_tiles = [False] * (MAP_LIMIT * MAP_LIMIT)
    current_map_size = 0
    bullet_delta_records = 0
    live_bullet_records = 0
    map_delta_records = 0
    player_sim_records = 0
    player_sim_digest = hashlib.sha256()
    bullet_digest = hashlib.sha256()
    map_digest = hashlib.sha256()
    awaiting_aux_cycle: int | None = None
    aux_players: list[tuple[int, int, int, int]] | None = None
    done_cycles: int | None = None

    while True:
        chunk_bytes = source.read(CHUNK_HEADER.size)
        if not chunk_bytes:
            break
        if len(chunk_bytes) != CHUNK_HEADER.size:
            raise ValueError("truncated BNE state chunk header")
        tag, payload_bytes = CHUNK_HEADER.unpack(chunk_bytes)
        if payload_bytes > MAX_CHUNK_BYTES:
            raise ValueError(f"BNE state chunk is implausibly large: {payload_bytes}")
        payload = _read_exact(source, payload_bytes, f"{tag!r} payload")
        if done_cycles is not None:
            raise ValueError("BNE state stream has data after its DONE chunk")
        if tag == b"AUXL":
            if not has_aux:
                raise ValueError("BNE state schema 1.0 cannot contain AUXL")
            if awaiting_aux_cycle is None or aux_players is None:
                raise ValueError("BNE state AUXL chunk has no preceding cycle")
            minimum = AUX_HEADER.size + player_count * PLAYER_SIM_RECORD.size
            if payload_bytes < minimum:
                raise ValueError("BNE state AUXL chunk is shorter than fixed records")
            cursor = io.BytesIO(payload)
            (aux_cycle, bullet_count, changed_bullets, map_size,
             changed_tiles) = AUX_HEADER.unpack(
                _read_exact(cursor, AUX_HEADER.size, "AUXL header"))
            if aux_cycle != awaiting_aux_cycle:
                raise ValueError(
                    f"BNE state AUXL cycle {aux_cycle}; "
                    f"expected {awaiting_aux_cycle}"
                )
            if bullet_count > BULLET_LIMIT:
                raise ValueError(
                    f"BNE state bullet count {bullet_count} exceeds {BULLET_LIMIT}"
                )
            if map_size == 0 or map_size > MAP_LIMIT:
                raise ValueError(f"BNE state map size {map_size} is invalid")
            map_tile_count = map_size * map_size
            expected_payload = (minimum
                + changed_bullets * (BULLET_DELTA_HEADER.size + BULLET_BYTES)
                + changed_tiles * MAP_DELTA.size)
            if payload_bytes != expected_payload:
                raise ValueError(
                    f"BNE state AUXL cycle {aux_cycle} has payload size "
                    f"{payload_bytes}; expected {expected_payload}"
                )

            sim_records = [
                _read_exact(cursor, PLAYER_SIM_RECORD.size,
                            "player simulation record")
                for _ in range(player_count)
            ]
            changed_bullet_slots: set[int] = set()
            for _ in range(changed_bullets):
                slot, generation = BULLET_DELTA_HEADER.unpack(
                    _read_exact(cursor, BULLET_DELTA_HEADER.size,
                                "bullet delta header"))
                raw = _read_exact(cursor, BULLET_BYTES, "raw bullet delta")
                if slot >= bullet_count:
                    raise ValueError(
                        f"BNE state AUXL cycle {aux_cycle} changes "
                        f"out-of-range bullet slot {slot}"
                    )
                if slot in changed_bullet_slots:
                    raise ValueError(
                        f"BNE state AUXL cycle {aux_cycle} changes "
                        f"bullet slot {slot} twice"
                    )
                now_live = (raw[BULLET_FLAGS] & BULLET_FREE) == 0
                expected_generation = bullet_generations[slot]
                if now_live and not live_bullets[slot]:
                    expected_generation += 1
                if generation != expected_generation:
                    raise ValueError(
                        f"BNE state AUXL cycle {aux_cycle} bullet slot {slot} "
                        f"generation {generation}; expected {expected_generation}"
                    )
                bullet_generations[slot] = generation
                live_bullets[slot] = now_live
                raw_bullets[slot][:] = raw
                known_bullets[slot] = True
                changed_bullet_slots.add(slot)
                bullet_delta_records += 1
            for slot in range(bullet_count, BULLET_LIMIT):
                live_bullets[slot] = False

            if current_map_size != map_size:
                known_map_tiles[:map_tile_count] = [False] * map_tile_count
            changed_map_indices: set[int] = set()
            for _ in range(changed_tiles):
                index, cell, square = MAP_DELTA.unpack(
                    _read_exact(cursor, MAP_DELTA.size, "map delta"))
                if index >= map_tile_count:
                    raise ValueError(
                        f"BNE state AUXL cycle {aux_cycle} changes "
                        f"out-of-range map tile {index}"
                    )
                if index in changed_map_indices:
                    raise ValueError(
                        f"BNE state AUXL cycle {aux_cycle} changes "
                        f"map tile {index} twice"
                    )
                map_cells[index] = cell
                map_squares[index] = square
                known_map_tiles[index] = True
                changed_map_indices.add(index)
                map_delta_records += 1
            if cursor.read(1):
                raise ValueError(
                    f"BNE state AUXL cycle {aux_cycle} has trailing payload data"
                )

            if aux_cycle == 1:
                if (changed_bullets != bullet_count
                        or changed_bullet_slots != set(range(bullet_count))):
                    raise ValueError(
                        "the first BNE AUXL cycle is not a complete bullet checkpoint"
                    )
            elif not all(known_bullets[:bullet_count]):
                raise ValueError(
                    f"BNE state AUXL cycle {aux_cycle} references unknown bullets"
                )
            if current_map_size != map_size:
                if (changed_tiles != map_tile_count
                        or changed_map_indices != set(range(map_tile_count))):
                    raise ValueError(
                        f"BNE state AUXL cycle {aux_cycle} is not a complete "
                        "checkpoint for its map size"
                    )
            elif not all(known_map_tiles[:map_tile_count]):
                raise ValueError(
                    f"BNE state AUXL cycle {aux_cycle} references unknown map tiles"
                )
            current_map_size = map_size

            for player, raw in enumerate(sim_records):
                if aux_players[player][0] == CONTROLLER_NOBODY:
                    continue
                player_sim_digest.update(struct.pack("<II", aux_cycle, player))
                player_sim_digest.update(raw)
                player_sim_records += 1
            for slot in range(bullet_count):
                if not live_bullets[slot]:
                    continue
                bullet_digest.update(struct.pack(
                    "<III", aux_cycle, slot, bullet_generations[slot]))
                bullet_digest.update(raw_bullets[slot])
                live_bullet_records += 1
            map_digest.update(struct.pack("<II", aux_cycle, map_size))
            for index in range(map_tile_count):
                map_digest.update(struct.pack(
                    "<HH", map_cells[index], map_squares[index]))
            awaiting_aux_cycle = None
            aux_players = None
            continue
        if awaiting_aux_cycle is not None:
            raise ValueError(
                f"BNE state cycle {awaiting_aux_cycle} has no following AUXL chunk"
            )
        if tag == b"DONE":
            if payload_bytes != DONE_RECORD.size:
                raise ValueError("BNE state DONE chunk has the wrong size")
            done_cycles = DONE_RECORD.unpack(payload)[0]
            continue
        if tag != b"CYCL":
            raise ValueError(f"unknown BNE state chunk {tag!r}")
        minimum = CYCLE_HEADER.size + player_count * PLAYER_RECORD.size
        if payload_bytes < minimum:
            raise ValueError("BNE state cycle chunk is shorter than its fixed records")

        cursor = io.BytesIO(payload)
        cycle, gameplay_seed, pool_count, changed_units = CYCLE_HEADER.unpack(
            _read_exact(cursor, CYCLE_HEADER.size, "cycle header"))
        if pool_count > unit_limit:
            raise ValueError(f"BNE state pool count {pool_count} exceeds {unit_limit}")
        expected_payload = minimum + changed_units * (
            UNIT_DELTA_HEADER.size + unit_bytes)
        if payload_bytes != expected_payload:
            raise ValueError(
                f"BNE state cycle {cycle} has payload size {payload_bytes}; "
                f"expected {expected_payload}"
            )
        if cycle != len(cycles) + 1:
            raise ValueError(
                f"BNE state cycles are not contiguous at {cycle}; "
                f"expected {len(cycles) + 1}"
            )
        cycles.append(cycle)
        seed_digest.update(struct.pack("<II", cycle, gameplay_seed))

        players = [PLAYER_RECORD.unpack(
            _read_exact(cursor, PLAYER_RECORD.size, "player record"))
            for _ in range(player_count)]

        changed_slots: set[int] = set()
        for _ in range(changed_units):
            slot, generation = UNIT_DELTA_HEADER.unpack(
                _read_exact(cursor, UNIT_DELTA_HEADER.size,
                            "unit delta header"))
            raw = _read_exact(cursor, unit_bytes, "raw unit delta")
            if slot >= pool_count:
                raise ValueError(
                    f"BNE state cycle {cycle} changes out-of-range slot {slot}"
                )
            if slot in changed_slots:
                raise ValueError(
                    f"BNE state cycle {cycle} changes slot {slot} twice"
                )
            now_live = (raw[UNIT_FLAGS] & (UNIT_FREE | UNIT_DEAD)) == 0
            expected_generation = generations[slot]
            if now_live and not live_units[slot]:
                expected_generation += 1
            if generation != expected_generation:
                raise ValueError(
                    f"BNE state cycle {cycle} slot {slot} generation "
                    f"{generation}; expected {expected_generation}"
                )
            generations[slot] = generation
            live_units[slot] = now_live
            raw_units[slot][:] = raw
            known_units[slot] = True
            changed_slots.add(slot)
            delta_records += 1
        if cursor.read(1):
            raise ValueError(f"BNE state cycle {cycle} has trailing payload data")
        if cycle == 1:
            if changed_units != pool_count or changed_slots != set(range(pool_count)):
                raise ValueError(
                    "the first BNE state cycle is not a complete unit checkpoint"
                )
        elif not all(known_units[:pool_count]):
            raise ValueError(f"BNE state cycle {cycle} references unknown unit slots")

        for player, (controller, gold, lumber, oil) in enumerate(players):
            if controller == CONTROLLER_NOBODY:
                continue
            player_digest.update(struct.pack(
                "<IIIII", cycle, player, gold, lumber, oil))
            active_player_records += 1
        for slot in range(pool_count):
            raw = raw_units[slot]
            flags_value = raw[UNIT_FLAGS]
            if (flags_value & (UNIT_FREE | UNIT_DEAD)) != 0:
                continue
            unit_digest.update(struct.pack(
                "<IIIIIII", cycle, slot, raw[UNIT_OWNER],
                _word(raw, UNIT_X), _word(raw, UNIT_Y), _word(raw, UNIT_HP),
                1 if (flags_value & UNIT_HIDDEN) else 0,
            ))
            live_unit_records += 1
        if has_aux:
            awaiting_aux_cycle = cycle
            aux_players = players

    if awaiting_aux_cycle is not None:
        raise ValueError(
            f"BNE state cycle {awaiting_aux_cycle} has no following AUXL chunk"
        )
    if done_cycles is None:
        raise ValueError("BNE state stream has no DONE chunk")
    if done_cycles != len(cycles):
        raise ValueError(
            f"BNE state DONE count is {done_cycles}; decoded {len(cycles)} cycles"
        )
    if expected_cycles is not None and cycles != list(range(1, expected_cycles + 1)):
        raise ValueError(
            f"BNE state has cycles {cycles!r}; expected 1..{expected_cycles}"
        )
    result: dict[str, int | str] = {
        "schema": f"{major}.{minor}",
        "cycles": len(cycles),
        "unit_bytes": unit_bytes,
        "unit_limit": unit_limit,
        "player_count": player_count,
        "unit_delta_records": delta_records,
        "active_player_records": active_player_records,
        "live_unit_records": live_unit_records,
        "cycle_seed_sha256": seed_digest.hexdigest(),
        "player_bank_sha256": player_digest.hexdigest(),
        "unit_core_sha256": unit_digest.hexdigest(),
    }
    if has_aux:
        result.update({
            "bullet_bytes": BULLET_BYTES,
            "bullet_limit": BULLET_LIMIT,
            "player_sim_bytes": PLAYER_SIM_RECORD.size,
            "map_size": current_map_size,
            "bullet_delta_records": bullet_delta_records,
            "map_delta_records": map_delta_records,
            "player_sim_records": player_sim_records,
            "live_bullet_records": live_bullet_records,
            "player_sim_sha256": player_sim_digest.hexdigest(),
            "bullet_state_sha256": bullet_digest.hexdigest(),
            "map_state_sha256": map_digest.hexdigest(),
        })
    return result


def validate_state_stream(path: Path,
        expected_cycles: int | None = None) -> dict[str, int | str]:
    with path.open("rb") as source:
        return validate_state_source(source, expected_cycles)


def _member_identity(archive: zipfile.ZipFile, name: str) -> dict[str, int | str]:
    digest = hashlib.sha256()
    size = 0
    with archive.open(name) as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            size += len(block)
            digest.update(block)
    return {"bytes": size, "sha256": digest.hexdigest()}


def _write_member(archive: zipfile.ZipFile, name: str, data: bytes) -> None:
    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    archive.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED,
                     compresslevel=9)


def _write_path_member(archive: zipfile.ZipFile, name: str, path: Path) -> None:
    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    with path.open("rb") as source, archive.open(
            info, "w", force_zip64=True) as destination:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            destination.write(block)


def seal_fixture(output: Path, manifest_path: Path, trace_path: Path,
        state_path: Path, commands_path: Path | None = None) -> dict[str, int | str]:
    """Create one deterministic-layout .bnefx ZIP from validated components."""
    if output.exists():
        raise ValueError(f"fixture already exists: {output}")
    manifest_bytes = manifest_path.read_bytes()
    manifest = json.loads(manifest_bytes)
    components = {
        "trace.txt": trace_path,
        "state.bin": state_path,
    }
    if commands_path is not None:
        components["commands.txt"] = commands_path
    if _identity_path(components["trace.txt"]) != {
            key: manifest["run"]["trace"][key] for key in ("bytes", "sha256")}:
        raise ValueError("trace identity changed before fixture sealing")
    if _identity_path(components["state.bin"]) != {
            key: manifest["run"]["state"][key] for key in ("bytes", "sha256")}:
        raise ValueError("state identity changed before fixture sealing")
    if commands_path is not None and _identity_path(components["commands.txt"]) != {
            key: manifest["run"]["commands"]["file"][key]
            for key in ("bytes", "sha256")}:
        raise ValueError("command identity changed before fixture sealing")

    output.parent.mkdir(parents=True, exist_ok=True)
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
                prefix=output.name + ".", suffix=".tmp",
                dir=output.parent, delete=False) as handle:
            temporary = Path(handle.name)
        with zipfile.ZipFile(temporary, "w", allowZip64=True) as archive:
            _write_member(archive, "manifest.json", manifest_bytes)
            for name in sorted(components):
                _write_path_member(archive, name, components[name])
        os.replace(temporary, output)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    return validate_fixture(output)


def validate_fixture(path: Path) -> dict[str, int | str]:
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("fixture contains duplicate member names")
        if "manifest.json" not in names:
            raise ValueError("fixture has no manifest.json")
        if any(name.startswith(("/", "\\")) or ".." in Path(name).parts
               for name in names):
            raise ValueError("fixture contains an unsafe member name")
        manifest = json.loads(archive.read("manifest.json"))
        expected = {"manifest.json", "trace.txt", "state.bin"}
        commands = manifest["run"].get("commands")
        if commands is not None:
            expected.add("commands.txt")
        if set(names) != expected:
            raise ValueError(
                f"fixture members are {sorted(names)!r}; expected {sorted(expected)!r}"
            )
        for member, identity in (
                ("trace.txt", manifest["run"]["trace"]),
                ("state.bin", manifest["run"]["state"]),
                *(([("commands.txt", commands["file"])]) if commands else []),
        ):
            actual = _member_identity(archive, member)
            wanted = {key: identity[key] for key in ("bytes", "sha256")}
            if actual != wanted:
                raise ValueError(
                    f"fixture member {member} identity {actual}; expected {wanted}"
                )
        with archive.open("state.bin") as state_source:
            state_validation = validate_state_source(
                state_source, manifest["run"]["cycle_limit"])
        wanted_state = manifest["run"]["state"]["validation"]
        if state_validation != wanted_state:
            raise ValueError("fixture state validation differs from its manifest")
        return {
            "fixture_id": manifest["fixture"]["id"],
            "members": len(names),
            "cycles": state_validation["cycles"],
            "bytes": path.stat().st_size,
            "sha256": _identity_path(path)["sha256"],
        }
