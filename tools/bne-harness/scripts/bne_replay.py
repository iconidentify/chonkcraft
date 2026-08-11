#!/usr/bin/env python3
"""Validate and inventory War2BNE InSight ``.wir`` replay files.

The parser intentionally preserves the BNE command packets as opaque bytes.
Those bytes are the authoritative multiplayer input; assigning gameplay
meaning to individual opcodes belongs at the engine comparison boundary.
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
        if getattr(args, "records", 0) < 0:
            raise ValueError("--records cannot be negative")
        return args.func(args)
    except (OSError, ValueError, zlib.error) as error:
        print(f"bne-replay: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
