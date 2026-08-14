#!/usr/bin/env python3
"""Validate, inventory and decode War2BNE InSight ``.wir`` replay files.

The outer recorder packets and every embedded command are retained byte for
byte.  Only command shapes proved against the pinned Battle.net Edition
dispatcher receive names; unknown commands remain named by opcode instead of
being guessed.  This makes the output useful as player-intent evidence without
silently turning an incomplete protocol transcription into game rules.
"""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import struct
import sys
from typing import NamedTuple
import zlib


HEADER_BYTES = 527
MAGIC = b"War2BNE InSight replay"
VERSION_MAJOR = 1
VERSION_MINOR = 1
CHECKSUM_OFFSET = 0x119
CHECKSUM_IGNORED_OFFSET = 0x19
CHECKSUM_IGNORED_BYTES = 0x100
MAP_NAME_OFFSET = 0x13D
PLAYER_COUNT_OFFSET = 0x161
PLAYER_NAMES_OFFSET = 0x162
PLAYER_NAME_BYTES = 16
PLAYER_LIMIT = 8
PLAYER_RACES_OFFSET = 0x1E2
GAME_TYPE_OFFSET = 0x1EA
PLAYER_CONTROLLERS_OFFSET = 0x1EB
RESOURCES_OFFSET = 0x1F3
GAME_SPEED_OFFSET = 0x1F4
STARTING_UNITS_OFFSET = 0x1F5
FIXED_ORDER_OFFSET = 0x1F6
RECORD_COUNT_OFFSET = 0x1F7
SNAPSHOT_OFFSET_OFFSET = 0x1FB
COMMAND_STREAM_OFFSET_OFFSET = 0x1FF
RECORD_PREFIX = struct.Struct("<8sII")
MAX_COMPRESSED_BYTES = 64 * 1024 * 1024
MAX_DECOMPRESSED_BYTES = 256 * 1024 * 1024
MAX_RECORDS = 10_000_000
MAX_PACKET_BYTES = 1024 * 1024


class ReplayRecord(NamedTuple):
    offset: int
    slot_status: bytes
    network_player: int
    packet: bytes


class Replay(NamedTuple):
    source: Path
    compressed: bytes
    decoded: bytes
    header: bytes
    snapshot: bytes
    command_stream: bytes
    records: tuple[ReplayRecord, ...]
    metadata: dict[str, object]


class ReplayCommand(NamedTuple):
    """One command inside a recorded 0x18 turn packet."""

    record_index: int
    network_player: int
    packet_offset: int
    opcode: int
    name: str
    raw: bytes
    selected_unit_ids: tuple[int, ...]


# Sizes include the opcode.  The table is the direct transcription of the
# retail dispatcher used by the recorded command stream.  Opcodes 0x06 and
# 0x07 are nul-terminated and 0x08 carries a count followed by that many words.
EMBEDDED_FIXED_BYTES = {
    0x05: 1,
    0x09: 6,
    0x0A: 6,
    0x0B: 2,
    0x0C: 1,
    0x0D: 1,
    0x0E: 5,
    0x0F: 3,
    0x10: 7,
    0x11: 1,
    0x12: 1,
    0x13: 8,
    0x14: 1,
    0x15: 3,
    0x16: 1,
    0x17: 3,
    0x18: 4,
    0x2D: 2,
}
EMBEDDED_NAMES = {
    0x08: "selection",
    0x09: "build",
    0x0A: "player-state",
    0x0C: "stop",
    0x0D: "stand-ground",
    0x10: "move",
    # Construction preflight.  Retail 0x00475dd0 toggles the selected
    # builder's 0x0800/0x1000 state, and the 0x09 dispatcher repeats the same
    # transition before installing the authoritative building order.
    0x12: "build-preflight",
    0x13: "attack",
    # Retail's synchronized production packet. Byte one names either a unit,
    # technology, or building transformation; byte two selects the matching
    # native table (0=train, 1/2=research, 3=transform).
    0x15: "production",
}
SELECTION_LIMIT = 9


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _u32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def _fixed_string(data: bytes, offset: int, size: int) -> str:
    raw = data[offset:offset + size].split(b"\0", 1)[0]
    return raw.decode("cp1252", "replace")


def _decompress(compressed: bytes) -> bytes:
    decoder = zlib.decompressobj()
    decoded = bytearray()
    for start in range(0, len(compressed), 64 * 1024):
        pending = compressed[start:start + 64 * 1024]
        while pending:
            remaining = MAX_DECOMPRESSED_BYTES + 1 - len(decoded)
            if remaining <= 0:
                raise ValueError("InSight replay expands beyond the safety limit")
            decoded.extend(decoder.decompress(pending, remaining))
            if len(decoded) > MAX_DECOMPRESSED_BYTES:
                raise ValueError("InSight replay expands beyond the safety limit")
            pending = decoder.unconsumed_tail
    decoded.extend(decoder.flush())
    if len(decoded) > MAX_DECOMPRESSED_BYTES:
        raise ValueError("InSight replay expands beyond the safety limit")
    if not decoder.eof:
        raise ValueError("truncated or invalid InSight zlib stream")
    if decoder.unused_data:
        raise ValueError("InSight replay has bytes after its zlib stream")
    return bytes(decoded)


def parse_replay(path: Path) -> Replay:
    path = path.expanduser().resolve()
    size = path.stat().st_size
    if size > MAX_COMPRESSED_BYTES:
        raise ValueError(f"compressed InSight replay is implausibly large: {size}")
    compressed = path.read_bytes()
    decoded = _decompress(compressed)
    if len(decoded) < HEADER_BYTES:
        raise ValueError("InSight replay is shorter than its 527-byte header")

    header = decoded[:HEADER_BYTES]
    magic_bytes = header[0]
    if magic_bytes != len(MAGIC) or header[1:1 + magic_bytes] != MAGIC:
        raise ValueError("unsupported InSight replay magic")
    major, minor = header[1 + magic_bytes:3 + magic_bytes]
    if (major, minor) != (VERSION_MAJOR, VERSION_MINOR):
        raise ValueError(
            f"unsupported InSight replay schema {major}.{minor}; "
            f"expected {VERSION_MAJOR}.{VERSION_MINOR}"
        )

    stored_checksum = _u32(header, CHECKSUM_OFFSET)
    checksum_input = bytearray(decoded)
    checksum_input[
        CHECKSUM_IGNORED_OFFSET:
        CHECKSUM_IGNORED_OFFSET + CHECKSUM_IGNORED_BYTES
    ] = bytes(CHECKSUM_IGNORED_BYTES)
    checksum_input[CHECKSUM_OFFSET:CHECKSUM_OFFSET + 4] = bytes(4)
    calculated_checksum = (~zlib.crc32(checksum_input)) & 0xFFFFFFFF
    if stored_checksum != calculated_checksum:
        raise ValueError(
            f"InSight replay checksum is 0x{stored_checksum:08x}; "
            f"expected 0x{calculated_checksum:08x}"
        )

    player_count = header[PLAYER_COUNT_OFFSET]
    if player_count == 0 or player_count > PLAYER_LIMIT:
        raise ValueError(f"InSight replay player count {player_count} is invalid")
    record_count = _u32(header, RECORD_COUNT_OFFSET)
    if record_count > MAX_RECORDS:
        raise ValueError(f"InSight replay record count {record_count} is invalid")
    snapshot_offset = _u32(header, SNAPSHOT_OFFSET_OFFSET)
    command_offset = _u32(header, COMMAND_STREAM_OFFSET_OFFSET)
    stream_bytes = len(decoded) - HEADER_BYTES
    if snapshot_offset > command_offset or command_offset > stream_bytes:
        raise ValueError(
            "InSight replay snapshot/command stream offsets are out of range"
        )

    absolute_snapshot = HEADER_BYTES + snapshot_offset
    absolute_commands = HEADER_BYTES + command_offset
    position = absolute_commands
    records: list[ReplayRecord] = []
    for index in range(record_count):
        if position + RECORD_PREFIX.size > len(decoded):
            raise ValueError(
                f"InSight replay is truncated before command record {index}"
            )
        slot_status, network_player, packet_bytes = RECORD_PREFIX.unpack_from(
            decoded, position)
        record_offset = position
        position += RECORD_PREFIX.size
        if network_player >= PLAYER_LIMIT:
            raise ValueError(
                f"InSight replay record {index} has invalid network player "
                f"{network_player}"
            )
        if packet_bytes > MAX_PACKET_BYTES:
            raise ValueError(
                f"InSight replay record {index} packet is implausibly large: "
                f"{packet_bytes}"
            )
        packet_end = position + packet_bytes
        if packet_end > len(decoded):
            raise ValueError(
                f"InSight replay is truncated in command record {index}"
            )
        records.append(ReplayRecord(
            offset=record_offset,
            slot_status=slot_status,
            network_player=network_player,
            packet=decoded[position:packet_end],
        ))
        position = packet_end
    if position != len(decoded):
        raise ValueError(
            f"InSight replay has {len(decoded) - position} trailing command bytes"
        )

    participant_slots = []
    for index in range(PLAYER_LIMIT):
        participant_slots.append({
            "slot": index,
            "name": _fixed_string(
                header,
                PLAYER_NAMES_OFFSET + index * PLAYER_NAME_BYTES,
                PLAYER_NAME_BYTES,
            ),
            "race": header[PLAYER_RACES_OFFSET + index],
            "controller": header[PLAYER_CONTROLLERS_OFFSET + index],
        })
    active_slots = [
        slot for slot in participant_slots if slot["controller"] != 3
    ]
    if len(active_slots) != player_count:
        raise ValueError(
            f"InSight replay declares {player_count} players but has "
            f"{len(active_slots)} active header slots"
        )
    metadata: dict[str, object] = {
        "schema": f"{major}.{minor}",
        "map": _fixed_string(header, MAP_NAME_OFFSET, 32),
        "player_count": player_count,
        "players": [slot["name"] for slot in active_slots],
        "participant_slots": participant_slots,
        "game_type": header[GAME_TYPE_OFFSET],
        "resources": header[RESOURCES_OFFSET],
        "game_speed": header[GAME_SPEED_OFFSET],
        "starting_units": header[STARTING_UNITS_OFFSET],
        "fixed_order": header[FIXED_ORDER_OFFSET],
        "record_count": record_count,
        "snapshot_offset": snapshot_offset,
        "snapshot_bytes": command_offset - snapshot_offset,
        "command_stream_offset": command_offset,
        "command_stream_bytes": len(decoded) - absolute_commands,
        "checksum": f"{stored_checksum:08x}",
    }
    return Replay(
        source=path,
        compressed=compressed,
        decoded=decoded,
        header=header,
        snapshot=decoded[absolute_snapshot:absolute_commands],
        command_stream=decoded[absolute_commands:],
        records=tuple(records),
        metadata=metadata,
    )


def replay_summary(replay: Replay) -> dict[str, object]:
    player_records = Counter(record.network_player for record in replay.records)
    packet_sizes = Counter(len(record.packet) for record in replay.records)
    slot_transitions = 0
    previous_status: bytes | None = None
    payload_bytes = 0
    for record in replay.records:
        if previous_status is not None and record.slot_status != previous_status:
            slot_transitions += 1
        previous_status = record.slot_status
        payload_bytes += len(record.packet)
    result = dict(replay.metadata)
    result.update({
        "compressed_bytes": len(replay.compressed),
        "decoded_bytes": len(replay.decoded),
        "packet_payload_bytes": payload_bytes,
        "maximum_packet_bytes": max(packet_sizes, default=0),
        "slot_status_transitions": slot_transitions,
        "records_by_network_player": {
            str(player): player_records[player]
            for player in sorted(player_records)
        },
        "compressed_sha256": _sha256(replay.compressed),
        "decoded_sha256": _sha256(replay.decoded),
        "header_sha256": _sha256(replay.header),
        "snapshot_sha256": _sha256(replay.snapshot),
        "command_stream_sha256": _sha256(replay.command_stream),
    })
    return result


def _embedded_length(body: bytes, position: int) -> int:
    opcode = body[position]
    if opcode in (0x06, 0x07):
        end = body.find(b"\0", position + 1)
        if end < 0:
            raise ValueError(
                f"embedded opcode 0x{opcode:02x} has no string terminator")
        return end - position + 1
    if opcode == 0x08:
        if position + 2 > len(body):
            raise ValueError("truncated embedded selection count")
        count = body[position + 1]
        if count > SELECTION_LIMIT:
            raise ValueError(
                f"embedded selection has {count} units; retail limit is "
                f"{SELECTION_LIMIT}")
        return 2 + count * 2
    size = EMBEDDED_FIXED_BYTES.get(opcode)
    if size is None:
        raise ValueError(f"unsupported embedded opcode 0x{opcode:02x}")
    return size


def decode_commands(replay: Replay) -> tuple[ReplayCommand, ...]:
    """Decode command boundaries and attach each player's ordered selection."""

    selections: dict[int, tuple[int, ...]] = {}
    decoded: list[ReplayCommand] = []
    for record_index, record in enumerate(replay.records):
        if record.packet == b"\x05":
            continue
        if len(record.packet) < 4 or record.packet[0] != 0x18:
            raise ValueError(
                f"record {record_index} has unsupported outer packet "
                f"0x{record.packet[0]:02x}")
        body = record.packet[4:]
        position = 0
        while position < len(body):
            size = _embedded_length(body, position)
            end = position + size
            if end > len(body):
                raise ValueError(
                    f"record {record_index} truncates embedded opcode "
                    f"0x{body[position]:02x}")
            opcode = body[position]
            raw = body[position:end]
            selected = selections.get(record.network_player, ())
            if opcode == 0x08:
                selected = tuple(
                    struct.unpack_from("<H", raw, 2 + index * 2)[0]
                    for index in range(raw[1])
                )
                selections[record.network_player] = selected
            decoded.append(ReplayCommand(
                record_index=record_index,
                network_player=record.network_player,
                packet_offset=position + 4,
                opcode=opcode,
                name=EMBEDDED_NAMES.get(opcode, f"opcode-{opcode:02x}"),
                raw=raw,
                selected_unit_ids=selected,
            ))
            position = end
    return tuple(decoded)


def command_summary(replay: Replay) -> dict[str, object]:
    commands = decode_commands(replay)
    by_opcode = Counter(command.opcode for command in commands)
    selection_sizes = Counter(
        len(command.selected_unit_ids)
        for command in commands if command.opcode == 0x08)
    commands_with_group = sum(
        1 for command in commands
        if command.opcode != 0x08 and len(command.selected_unit_ids) > 1
    )
    return {
        "embedded_command_count": len(commands),
        "commands_by_opcode": {
            f"{opcode:02x}": by_opcode[opcode] for opcode in sorted(by_opcode)
        },
        "selection_sizes": {
            str(size): selection_sizes[size] for size in sorted(selection_sizes)
        },
        "commands_with_multi_unit_selection": commands_with_group,
    }


def _json_bytes(value: object) -> bytes:
    return (json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=False
    ) + "\n").encode("utf-8")


def inspect_command(args: argparse.Namespace) -> int:
    replay = parse_replay(args.replay)
    result = replay_summary(replay)
    result["file"] = args.replay.name
    if args.records:
        result["records"] = [
            {
                "index": index,
                "offset": record.offset,
                "slot_status": list(record.slot_status),
                "network_player": record.network_player,
                "packet": record.packet.hex(),
            }
            for index, record in enumerate(replay.records[:args.records])
        ]
    print(json.dumps(result, indent=2, sort_keys=True, ensure_ascii=False))
    return 0


def commands_command(args: argparse.Namespace) -> int:
    replay = parse_replay(args.replay)
    commands = decode_commands(replay)
    result = replay_summary(replay)
    result.update(command_summary(replay))
    result["file"] = args.replay.name
    if args.limit:
        result["commands"] = [
            {
                "record": command.record_index,
                "network_player": command.network_player,
                "packet_offset": command.packet_offset,
                "opcode": f"{command.opcode:02x}",
                "name": command.name,
                "raw": command.raw.hex(),
                "selected_unit_ids": list(command.selected_unit_ids),
            }
            for command in commands[:args.limit]
        ]
    print(json.dumps(result, indent=2, sort_keys=True, ensure_ascii=False))
    return 0


def corpus_command(args: argparse.Namespace) -> int:
    entries = []
    by_opcode: Counter[int] = Counter()
    selection_sizes: Counter[int] = Counter()
    embedded_commands = 0
    multi_selection_commands = 0
    identity = hashlib.sha256()
    for logical_path, path in _replay_paths(args.sources):
        replay = parse_replay(path)
        summary = command_summary(replay)
        entries.append({
            "path": logical_path,
            "compressed_sha256": _sha256(replay.compressed),
            **summary,
        })
        identity.update(logical_path.encode("utf-8"))
        identity.update(b"\0")
        identity.update(hashlib.sha256(replay.compressed).digest())
        embedded_commands += int(summary["embedded_command_count"])
        multi_selection_commands += int(
            summary["commands_with_multi_unit_selection"])
        for opcode, count in summary["commands_by_opcode"].items():
            by_opcode[int(opcode, 16)] += int(count)
        for size, count in summary["selection_sizes"].items():
            selection_sizes[int(size)] += int(count)
    result = {
        "schema": "chonkcraft-bne-replay-commands-1",
        "corpus_sha256": identity.hexdigest(),
        "replay_count": len(entries),
        "embedded_command_count": embedded_commands,
        "commands_with_multi_unit_selection": multi_selection_commands,
        "commands_by_opcode": {
            f"{opcode:02x}": by_opcode[opcode] for opcode in sorted(by_opcode)
        },
        "selection_sizes": {
            str(size): selection_sizes[size] for size in sorted(selection_sizes)
        },
        "entries": entries,
    }
    if args.expect_corpus_sha256:
        expected = args.expect_corpus_sha256.lower()
        if result["corpus_sha256"] != expected:
            raise ValueError(
                f"replay corpus is {result['corpus_sha256']}; expected {expected}")
    print(json.dumps(result, indent=2, sort_keys=True, ensure_ascii=False))
    return 0


def _replay_paths(sources: list[Path]) -> list[tuple[str, Path]]:
    paths: list[tuple[str, Path]] = []
    for source in sources:
        resolved = source.expanduser().resolve()
        if resolved.is_file():
            if resolved.suffix.lower() != ".wir":
                raise ValueError(f"replay source is not a .wir file: {source}")
            paths.append((resolved.name, resolved))
        elif resolved.is_dir():
            for path in resolved.rglob("*.wir"):
                paths.append((path.relative_to(resolved).as_posix(), path))
        else:
            raise ValueError(f"replay source does not exist: {source}")
    paths.sort(key=lambda item: (item[0].casefold(), item[0]))
    if not paths:
        raise ValueError("no .wir replay files found")
    logical_names = [name for name, _ in paths]
    if len(logical_names) != len(set(logical_names)):
        raise ValueError("replay sources contain duplicate logical paths")
    return paths


def inventory_command(args: argparse.Namespace) -> int:
    entries = []
    for logical_path, path in _replay_paths(args.sources):
        replay = parse_replay(path)
        summary = replay_summary(replay)
        summary["path"] = logical_path
        entries.append(summary)
    provenance: dict[str, object] = {"collection_id": args.collection_id}
    if args.source_url:
        provenance["source_url"] = args.source_url
    if args.archive_sha256:
        digest = args.archive_sha256.lower()
        if len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
            raise ValueError("--archive-sha256 must be 64 lowercase hex digits")
        provenance["archive_sha256"] = digest
    result: dict[str, object] = {
        "schema": "chonkcraft-bne-replay-inventory-1",
        "provenance": provenance,
        "replay_count": len(entries),
        "entries": entries,
    }
    result["inventory_sha256"] = _sha256(_json_bytes(result))
    encoded = (json.dumps(
        result, indent=2, sort_keys=True, ensure_ascii=False
    ) + "\n").encode("utf-8")
    if args.output:
        destination = args.output.expanduser().resolve()
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_name(destination.name + ".tmp")
        temporary.write_bytes(encoded)
        temporary.replace(destination)
        print(destination)
    else:
        sys.stdout.buffer.write(encoded)
    return 0


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    subcommands = result.add_subparsers(dest="command", required=True)

    inspect_parser = subcommands.add_parser(
        "inspect", help="validate and describe one .wir replay")
    inspect_parser.add_argument("replay", type=Path)
    inspect_parser.add_argument(
        "--records", type=int, default=0,
        help="include the first N opaque command records")
    inspect_parser.set_defaults(func=inspect_command)

    commands_parser = subcommands.add_parser(
        "commands",
        help="decode retail command boundaries and ordered selection context")
    commands_parser.add_argument("replay", type=Path)
    commands_parser.add_argument(
        "--limit", type=int, default=0,
        help="include the first N decoded commands")
    commands_parser.set_defaults(func=commands_command)

    corpus_parser = subcommands.add_parser(
        "corpus", help="decode and aggregate an authenticated replay collection")
    corpus_parser.add_argument("sources", nargs="+", type=Path)
    corpus_parser.add_argument("--expect-corpus-sha256")
    corpus_parser.set_defaults(func=corpus_command)

    inventory_parser = subcommands.add_parser(
        "inventory", help="validate a replay collection and write stable metadata")
    inventory_parser.add_argument("sources", nargs="+", type=Path)
    inventory_parser.add_argument("--collection-id", required=True)
    inventory_parser.add_argument("--source-url")
    inventory_parser.add_argument("--archive-sha256")
    inventory_parser.add_argument("--output", type=Path)
    inventory_parser.set_defaults(func=inventory_command)
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        if getattr(args, "records", 0) < 0 or getattr(args, "limit", 0) < 0:
            raise ValueError("record/command limits cannot be negative")
        return args.func(args)
    except (OSError, ValueError, zlib.error) as error:
        print(f"bne-replay: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
