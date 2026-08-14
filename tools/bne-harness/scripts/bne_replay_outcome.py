#!/usr/bin/env python3
"""Compile authenticated BNE replays and compare command fulfillment traces.

The replay plan is deliberately engine-neutral.  It preserves the exact InSight
record order, packet bytes, controller vector, ordered selection, immutable
initial-state/stream identities, and the exact ChonkPack map asset. Outcome
traces produced by native BNE and Java are accepted only when those identities
and the compiled schedule match.

An InSight initial-state block is a deterministic-playback reference, not a PUD
or mid-game save. The plan therefore records an explicit map/lobby startup
recipe and requires each adapter to prove that state before comparison.
"""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import struct
import sys
from typing import Any
import zipfile

import bne_replay


PLAN_SCHEMA = "chonkcraft-bne-replay-plan-1"
TRACE_SCHEMA = "chonkcraft-bne-replay-outcome-trace-1"
REPORT_SCHEMA = "chonkcraft-bne-replay-outcome-report-1"
CORPUS_SCHEMA = "chonkcraft-bne-replay-outcome-corpus-1"
NATIVE_SCHEDULE_MAGIC = b"BNERPLN1"
NATIVE_SCHEDULE_VERSION = 1
NATIVE_SCHEDULE_HEADER = struct.Struct("<8sII32s32s")
NATIVE_SCHEDULE_RECORD = struct.Struct("<II8sI")
PINNED_BNE_EXECUTABLE_SHA256 = (
    "b0e914a9cb7dcc81a205e700a9bb0a1d0649df19d459388051ba170783d2c807"
)
NATIVE_DISPATCH_CONTRACT = {
    "executable_sha256": PINNED_BNE_EXECUTABLE_SHA256,
    "callsite": "0x0047800b",
    "callsite_bytes": "e890020000",
    "target": "0x004782a0",
    "target_bytes": "81ec8000000053555657",
}

# Retail BNE 2.02b fcn.004338d0 masks the lobby resource choice and fills the
# eight player banks at 0x004abb18 (gold), 0x004acb6c (lumber), and
# 0x004abbfc (oil). InSight stores the corresponding one-based lobby choice.
RESOURCE_PRESETS = {
    2: {"gold": 2100, "wood": 1100, "oil": 1000, "name": "low"},
    3: {"gold": 5000, "wood": 2000, "oil": 2000, "name": "medium"},
    4: {"gold": 10000, "wood": 5000, "oil": 5000, "name": "high"},
}
SIDES = ("native", "java")
PHASES = ("submitted", "accepted", "progress", "terminal")
REPLAY_TRACE_EVENT = "# bne-trace event="


def _json_bytes(value: object) -> bytes:
    return (json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=False
    ) + "\n").encode("utf-8")


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def compile_plan(replay: bne_replay.Replay, asset_pack: Path | None = None) \
        -> dict[str, Any]:
    """Return a stable dispatch schedule without inventing simulation cycles."""

    commands = bne_replay.decode_commands(replay)
    by_record: dict[int, list[bne_replay.ReplayCommand]] = {}
    for command in commands:
        by_record.setdefault(command.record_index, []).append(command)

    records = []
    command_count = 0
    for index, record in enumerate(replay.records):
        record_commands = []
        for command_index, command in enumerate(by_record.get(index, ())):
            record_commands.append({
                "index": command_index,
                "packet_offset": command.packet_offset,
                "opcode": command.opcode,
                "name": command.name,
                "raw": command.raw.hex(),
                "selected_unit_ids": list(command.selected_unit_ids),
            })
            command_count += 1
        records.append({
            "index": index,
            "network_player": record.network_player,
            "slot_status": record.slot_status.hex(),
            "packet": record.packet.hex(),
            "packet_sha256": _sha256(record.packet),
            # Bytes 1..3 are the opaque synchronized-turn header for an 0x18
            # packet.  They are retained for diagnosis, never interpreted as
            # an engine cycle without a proved retail rule.
            "turn_header": (
                record.packet[1:4].hex()
                if len(record.packet) >= 4 and record.packet[0] == 0x18
                else None
            ),
            "commands": record_commands,
        })

    summary = bne_replay.replay_summary(replay)
    startup = startup_recipe(replay)
    startup["fixed_order_slots"] = fixed_order_slots(records)
    if asset_pack is not None:
        startup["map_asset"] = resolve_map_asset(startup["map_name"], asset_pack)
    plan: dict[str, Any] = {
        "schema": PLAN_SCHEMA,
        "replay": {
            "file": replay.source.name,
            "compressed_sha256": summary["compressed_sha256"],
            "decoded_sha256": summary["decoded_sha256"],
            "snapshot_sha256": summary["snapshot_sha256"],
            "command_stream_sha256": summary["command_stream_sha256"],
            "snapshot_bytes": len(replay.snapshot),
            "record_count": len(replay.records),
            "command_count": command_count,
            "map": replay.metadata["map"],
            "players": replay.metadata["players"],
            "startup": startup,
        },
        "native_dispatch_contract": NATIVE_DISPATCH_CONTRACT,
        "records": records,
    }
    plan["startup_sha256"] = _sha256(_json_bytes(startup))
    plan["schedule_sha256"] = _sha256(_json_bytes(plan["records"]))
    return plan


def fixed_order_slots(records: list[dict[str, Any]]) -> dict[str, Any]:
    """Recover network-seat to map-slot identity from retail player state.

    Opcode 0x0A carries the game player at byte five.  In fixed-order games
    that value is not generally the network sender: it is the PUD/player-color
    slot the lobby assigned that sender.  Applying replay orders to network
    indices puts the right click in the wrong base while still looking like a
    successful move, so the mapping is part of authenticated startup state.
    """

    mapping: dict[int, int] = {}
    for record in records:
        network_player = int(record["network_player"])
        for command in record["commands"]:
            if int(command["opcode"]) != 0x0A:
                continue
            raw = bytes.fromhex(command["raw"])
            if len(raw) != 6:
                raise ValueError("retail player-state packet is not six bytes")
            game_player = raw[5]
            previous = mapping.setdefault(network_player, game_player)
            if previous != game_player:
                raise ValueError(
                    f"network player {network_player} changed game slot "
                    f"from {previous} to {game_player}")
    return {
        "status": "complete" if len(mapping) == 8 else "partial",
        "entries": [
            {"network_player": network, "game_player": game}
            for network, game in sorted(mapping.items())
        ],
    }


def _slug(value: str) -> str:
    result = []
    separator = False
    for character in value.casefold():
        if character.isalnum():
            result.append(character)
            separator = False
        elif not separator:
            result.append("-")
            separator = True
    return "".join(result).strip("-")


def startup_recipe(replay: bne_replay.Replay) -> dict[str, Any]:
    """Describe the initial lobby state InSight requires before playback."""

    metadata = replay.metadata
    slots = []
    for slot in metadata["participant_slots"]:
        controller = int(slot["controller"])
        slots.append({
            "slot": int(slot["slot"]),
            "name": str(slot["name"]),
            "race": int(slot["race"]),
            "controller": controller,
            "occupant": {0: "human", 1: "computer", 3: "closed"}.get(
                controller, "unknown"),
        })
    resources = int(metadata["resources"])
    bank = RESOURCE_PRESETS.get(resources)
    resource_bank = ({
        **bank,
        "status": "verified",
        "native_function": "0x004338d0",
    } if bank is not None else {
        "status": "map-default",
        "name": "map-default",
        "minimum_gold": 2100,
        "minimum_wood": 1100,
        "minimum_oil": 1000,
        "native_function": "0x004338d0",
    })
    return {
        "schema": "chonkcraft-bne-replay-startup-1",
        "map_name": metadata["map"],
        "map_slug": _slug(Path(str(metadata["map"])).stem),
        "game_type": metadata["game_type"],
        "resources": resources,
        "resource_bank": resource_bank,
        "game_speed": metadata["game_speed"],
        "starting_units": metadata["starting_units"],
        "fixed_order": metadata["fixed_order"],
        "player_count": metadata["player_count"],
        "slots": slots,
        "initial_state_bytes": len(replay.snapshot),
        "initial_state_sha256": _sha256(replay.snapshot),
        "map_asset": {"status": "unresolved"},
        "meaning": (
            "InSight startup/reference state used to verify the same map, "
            "lobby settings and deterministic game state; not a mid-game save"
        ),
    }


def resolve_map_asset(map_name: str, asset_pack: Path) -> dict[str, Any]:
    """Authenticate the exact ChonkPack map named by an InSight replay."""

    pack_path = asset_pack.expanduser().resolve()
    with zipfile.ZipFile(pack_path) as archive:
        manifest_bytes = archive.read("pack.json")
        manifest = json.loads(manifest_bytes)
        candidates = []
        wanted = Path(map_name.replace("\\", "/")).name.casefold()
        for asset in manifest.get("assets", ()):
            if asset.get("kind") != "map":
                continue
            meta_name = str((asset.get("meta") or {}).get("name", ""))
            names = {
                Path(meta_name.replace("\\", "/")).name.casefold(),
                Path(str(asset.get("file", ""))).name.casefold(),
            }
            if wanted in names:
                candidates.append(asset)
        if len(candidates) != 1:
            raise ValueError(
                f"replay map {map_name!r} resolves to {len(candidates)} "
                "ChonkPack assets; expected exactly one")
        asset = candidates[0]
        map_bytes = archive.read(asset["file"])
        actual = _sha256(map_bytes)
        if actual != asset.get("sha256") or len(map_bytes) != asset.get("bytes"):
            raise ValueError(f"ChonkPack map asset changed: {asset['file']}")
        pack = manifest.get("pack") or {}
        properties = pack.get("properties") or {}
        return {
            "status": "verified",
            "pack_id": pack.get("id"),
            "pack_manifest_sha256": _sha256(manifest_bytes),
            "source_sha256": properties.get("sourceOriginalSha256"),
            "asset_id": asset.get("id"),
            "asset_file": asset.get("file"),
            "asset_sha256": actual,
            "asset_bytes": len(map_bytes),
        }


def _write_json(path: Path | None, value: object) -> None:
    encoded = json.dumps(
        value, indent=2, sort_keys=True, ensure_ascii=False
    ) + "\n"
    if path is None:
        print(encoded, end="")
        return
    destination = path.expanduser().resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(destination.name + ".tmp")
    temporary.write_text(encoded, encoding="utf-8")
    temporary.replace(destination)
    print(destination)


def plan_command(args: argparse.Namespace) -> int:
    replay = bne_replay.parse_replay(args.replay)
    _write_json(args.output, compile_plan(replay, args.asset_pack))
    return 0


def corpus_plan(sources: list[Path], asset_pack: Path | None = None) \
        -> dict[str, Any]:
    entries = []
    identity = hashlib.sha256()
    totals: Counter[str] = Counter()
    for logical_path, path in bne_replay._replay_paths(sources):
        plan = compile_plan(bne_replay.parse_replay(path), asset_pack)
        replay = plan["replay"]
        entries.append({
            "path": logical_path,
            "compressed_sha256": replay["compressed_sha256"],
            "snapshot_sha256": replay["snapshot_sha256"],
            "command_stream_sha256": replay["command_stream_sha256"],
            "schedule_sha256": plan["schedule_sha256"],
            "startup_sha256": plan["startup_sha256"],
            "snapshot_bytes": replay["snapshot_bytes"],
            "record_count": replay["record_count"],
            "command_count": replay["command_count"],
            "map_asset_sha256": replay["startup"]["map_asset"].get(
                "asset_sha256"),
        })
        identity.update(logical_path.encode("utf-8"))
        identity.update(b"\0")
        identity.update(bytes.fromhex(replay["compressed_sha256"]))
        totals.update({
            "snapshot_bytes": replay["snapshot_bytes"],
            "record_count": replay["record_count"],
            "command_count": replay["command_count"],
        })
    result: dict[str, Any] = {
        "schema": CORPUS_SCHEMA,
        "corpus_sha256": identity.hexdigest(),
        "replay_count": len(entries),
        "snapshot_bytes": totals["snapshot_bytes"],
        "record_count": totals["record_count"],
        "command_count": totals["command_count"],
        "entries": entries,
    }
    result["outcome_corpus_sha256"] = _sha256(_json_bytes(result))
    return result


def corpus_command(args: argparse.Namespace) -> int:
    result = corpus_plan(args.sources, args.asset_pack)
    if args.expect_corpus_sha256:
        expected = args.expect_corpus_sha256.lower()
        if result["corpus_sha256"] != expected:
            raise ValueError(
                f"replay corpus is {result['corpus_sha256']}; expected {expected}")
    _write_json(args.output, result)
    return 0


def _load_json(path: Path, label: str) -> dict[str, Any]:
    try:
        value = json.loads(path.expanduser().resolve().read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise ValueError(f"{label} is not valid JSON: {path}") from error
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be one JSON object: {path}")
    return value


def _validate_plan(plan: dict[str, Any], require_startup: bool = False) -> None:
    if plan.get("schema") != PLAN_SCHEMA:
        raise ValueError(f"unsupported replay plan schema: {plan.get('schema')!r}")
    records = plan.get("records")
    replay = plan.get("replay")
    if not isinstance(records, list) or not isinstance(replay, dict):
        raise ValueError("replay plan omits records or replay identity")
    if replay.get("record_count") != len(records):
        raise ValueError("replay plan record count does not match its schedule")
    if plan.get("native_dispatch_contract") != NATIVE_DISPATCH_CONTRACT:
        raise ValueError("replay plan does not name the pinned native dispatcher")
    startup = replay.get("startup") or {}
    if plan.get("startup_sha256") != _sha256(_json_bytes(startup)):
        raise ValueError("replay plan startup identity does not match its recipe")
    expected = _sha256(_json_bytes(records))
    if plan.get("schedule_sha256") != expected:
        raise ValueError("replay plan schedule identity does not match its records")
    for expected_index, record in enumerate(records):
        if not isinstance(record, dict) or record.get("index") != expected_index:
            raise ValueError("replay plan records are not contiguous and ordered")
        packet = bytes.fromhex(str(record.get("packet", "")))
        if record.get("packet_sha256") != _sha256(packet):
            raise ValueError(f"replay plan record {expected_index} packet changed")
    if require_startup:
        map_asset = startup.get("map_asset") or {}
        if map_asset.get("status") != "verified" or not _valid_sha256(
                map_asset.get("asset_sha256")):
            raise ValueError("replay plan has no authenticated startup map")


def _valid_sha256(value: object) -> bool:
    return isinstance(value, str) and len(value) == 64 and all(
        character in "0123456789abcdef" for character in value)


def native_schedule_bytes(plan: dict[str, Any]) -> bytes:
    """Encode the exact records for the guarded native dispatcher wrapper."""

    _validate_plan(plan)
    payload = bytearray(NATIVE_SCHEDULE_HEADER.pack(
        NATIVE_SCHEDULE_MAGIC,
        NATIVE_SCHEDULE_VERSION,
        len(plan["records"]),
        bytes.fromhex(plan["schedule_sha256"]),
        bytes.fromhex(plan["replay"]["snapshot_sha256"]),
    ))
    for record in plan["records"]:
        packet = bytes.fromhex(record["packet"])
        status = bytes.fromhex(record["slot_status"])
        if len(status) != 8:
            raise ValueError(f"record {record['index']} slot status is not 8 bytes")
        payload.extend(NATIVE_SCHEDULE_RECORD.pack(
            record["index"], record["network_player"], status, len(packet)))
        payload.extend(packet)
    return bytes(payload)


def parse_native_schedule(data: bytes) -> dict[str, Any]:
    if len(data) < NATIVE_SCHEDULE_HEADER.size:
        raise ValueError("native schedule is truncated before its header")
    magic, version, count, schedule_sha, snapshot_sha = \
        NATIVE_SCHEDULE_HEADER.unpack_from(data)
    if magic != NATIVE_SCHEDULE_MAGIC or version != NATIVE_SCHEDULE_VERSION:
        raise ValueError("native schedule has an unsupported identity or version")
    offset = NATIVE_SCHEDULE_HEADER.size
    records = []
    for expected_index in range(count):
        if offset + NATIVE_SCHEDULE_RECORD.size > len(data):
            raise ValueError("native schedule is truncated before a record")
        index, player, status, length = NATIVE_SCHEDULE_RECORD.unpack_from(
            data, offset)
        offset += NATIVE_SCHEDULE_RECORD.size
        if index != expected_index or offset + length > len(data):
            raise ValueError("native schedule record order or length is invalid")
        packet = data[offset:offset + length]
        offset += length
        records.append({
            "index": index,
            "network_player": player,
            "slot_status": status.hex(),
            "packet": packet.hex(),
        })
    if offset != len(data):
        raise ValueError("native schedule has trailing bytes")
    return {
        "record_count": count,
        "schedule_sha256": schedule_sha.hex(),
        "snapshot_sha256": snapshot_sha.hex(),
        "records": records,
    }


def native_schedule_command(args: argparse.Namespace) -> int:
    plan = _load_json(args.plan, "replay plan")
    encoded = native_schedule_bytes(plan)
    destination = args.output.expanduser().resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(destination.name + ".tmp")
    temporary.write_bytes(encoded)
    temporary.replace(destination)
    print(destination)
    return 0


def verify_native_dispatch_trace(plan: dict[str, Any], lines: list[str]) \
        -> dict[str, Any]:
    """Prove a trace consumed the full plan at the guarded retail callsite."""

    _validate_plan(plan)
    loaded = []
    verified: list[tuple[int, int, int]] = []
    completed = []
    mismatches = []
    closed = []
    for line in lines:
        text = line.strip()
        if "event=replay-schedule-loaded " in text:
            loaded.append(text)
        elif "event=replay-dispatch-injected " in text:
            fields = _trace_fields(text)
            verified.append((
                int(fields["record"]), int(fields["player"]),
                int(fields["bytes"])))
        elif "event=replay-dispatch-mismatch " in text:
            mismatches.append(text)
        elif "event=replay-schedule-complete " in text:
            completed.append(text)
        elif "event=replay-schedule-closed " in text:
            closed.append(text)
    if len(loaded) != 1:
        raise ValueError("native trace does not have one schedule load receipt")
    loaded_fields = _trace_fields(loaded[0])
    if (int(loaded_fields.get("records", -1)) != len(plan["records"])
            or loaded_fields.get("schedule-sha256") != plan["schedule_sha256"]
            or loaded_fields.get("snapshot-sha256")
                != plan["replay"]["snapshot_sha256"]):
        raise ValueError("native trace loaded a different replay schedule")
    if mismatches:
        raise ValueError("native trace reports a replay dispatch mismatch")
    if len(verified) != len(plan["records"]):
        raise ValueError("native trace did not verify every replay dispatch")
    for record, observed in zip(plan["records"], verified, strict=True):
        wanted = (
            record["index"], record["network_player"],
            len(bytes.fromhex(record["packet"])))
        if observed != wanted:
            raise ValueError("native trace dispatcher receipt is out of order")
    if len(completed) != 1 or len(closed) != 1:
        raise ValueError("native trace has no unique completion receipt")
    closed_fields = _trace_fields(closed[0])
    if (closed_fields.get("complete") != "true"
            or closed_fields.get("valid") != "true"
            or int(closed_fields.get("consumed", -1)) != len(plan["records"])):
        raise ValueError("native trace closed before the replay completed")
    return {
        "schema": "chonkcraft-bne-replay-native-dispatch-proof-1",
        "identity": _trace_identity(plan),
        "executable_sha256": PINNED_BNE_EXECUTABLE_SHA256,
        "dispatch_contract": NATIVE_DISPATCH_CONTRACT,
        "injected_records": len(verified),
        "status": "verified",
    }


def _trace_fields(line: str) -> dict[str, str]:
    fields: dict[str, str] = {}
    for token in line.split():
        if "=" not in token:
            continue
        key, value = token.split("=", 1)
        fields[key] = value.strip('"')
    return fields


def verify_native_command(args: argparse.Namespace) -> int:
    plan = _load_json(args.plan, "replay plan")
    lines = args.trace.expanduser().resolve().read_text(
        encoding="utf-8", errors="replace").splitlines()
    _write_json(args.output, verify_native_dispatch_trace(plan, lines))
    return 0


def _trace_identity(plan: dict[str, Any]) -> dict[str, Any]:
    replay = plan["replay"]
    return {
        "compressed_sha256": replay["compressed_sha256"],
        "snapshot_sha256": replay["snapshot_sha256"],
        "command_stream_sha256": replay["command_stream_sha256"],
        "schedule_sha256": plan["schedule_sha256"],
        "startup_sha256": plan["startup_sha256"],
    }


def skeleton_command(args: argparse.Namespace) -> int:
    plan = _load_json(args.plan, "replay plan")
    _validate_plan(plan)
    if not _valid_sha256(args.build_sha256):
        raise ValueError("--build-sha256 must be 64 lowercase hexadecimal digits")
    if args.side == "native" \
            and args.build_sha256 != PINNED_BNE_EXECUTABLE_SHA256:
        raise ValueError("native trace producer is not the pinned BNE executable")
    trace = {
        "schema": TRACE_SCHEMA,
        "side": args.side,
        "identity": _trace_identity(plan),
        "producer": {
            "name": args.producer,
            "build_sha256": args.build_sha256,
        },
        "initial_state": {
            "status": "pending",
            "consumed_sha256": None,
            "map_asset_sha256": None,
        },
        "schedule": {
            "status": "pending",
            "consumed_records": 0,
            "consumed_sha256": None,
        },
        "outcomes": [],
    }
    _write_json(args.output, trace)
    return 0


def _command_keys(plan: dict[str, Any]) -> set[tuple[int, int]]:
    return {
        (int(record["index"]), int(command["index"]))
        for record in plan["records"]
        for command in record["commands"]
        if command["name"] != "selection"
    }


def _validate_trace(plan: dict[str, Any], trace: dict[str, Any], side: str) \
        -> dict[tuple[int, int, int], dict[str, Any]]:
    _validate_plan(plan, require_startup=True)
    if trace.get("schema") != TRACE_SCHEMA or trace.get("side") != side:
        raise ValueError(f"{side} trace has the wrong schema or side")
    if trace.get("identity") != _trace_identity(plan):
        raise ValueError(f"{side} trace does not name this replay plan")
    producer = trace.get("producer") or {}
    if not producer.get("name") or not _valid_sha256(
            producer.get("build_sha256")):
        raise ValueError(f"{side} trace does not name an authenticated producer")
    if side == "native" and producer.get(
            "build_sha256") != PINNED_BNE_EXECUTABLE_SHA256:
        raise ValueError(f"{side} trace was not produced by pinned BNE 2.02b")
    restore = trace.get("initial_state") or {}
    map_asset_sha256 = plan["replay"]["startup"]["map_asset"].get(
        "asset_sha256")
    if restore.get("status") != "verified" or restore.get(
            "consumed_sha256") != plan["replay"]["snapshot_sha256"] \
            or restore.get("map_asset_sha256") != map_asset_sha256:
        raise ValueError(f"{side} trace did not verify the replay initial state")
    schedule = trace.get("schedule") or {}
    if (schedule.get("status") != "complete"
            or schedule.get("consumed_records") != len(plan["records"])
            or schedule.get("consumed_sha256") != plan["schedule_sha256"]):
        raise ValueError(f"{side} trace did not consume the complete packet schedule")

    valid_commands = _command_keys(plan)
    indexed: dict[tuple[int, int, int], dict[str, Any]] = {}
    for outcome in trace.get("outcomes", ()):
        if not isinstance(outcome, dict):
            raise ValueError(f"{side} trace contains a non-object outcome")
        record = int(outcome.get("record", -1))
        command = int(outcome.get("command", -1))
        unit = int(outcome.get("unit", -1))
        if (record, command) not in valid_commands or unit < 0:
            raise ValueError(f"{side} trace outcome names an unknown command or unit")
        key = (record, command, unit)
        if key in indexed:
            raise ValueError(f"{side} trace repeats outcome {key}")
        phases = outcome.get("phases")
        if not isinstance(phases, dict) or "accepted" not in phases:
            raise ValueError(f"{side} trace outcome {key} omits acceptance")
        unknown = set(phases) - set(PHASES)
        if unknown:
            raise ValueError(f"{side} trace outcome {key} has unknown phases {unknown}")
        indexed[key] = outcome
    return indexed


def _command(plan: dict[str, Any], record: int, command: int) -> dict[str, Any]:
    return plan["records"][record]["commands"][command]


def _classify(command: dict[str, Any], native: dict[str, Any] | None,
        java: dict[str, Any] | None) -> str:
    if native is None or java is None:
        return "group-fanout"
    native_phases = native["phases"]
    java_phases = java["phases"]
    if native_phases.get("accepted") != java_phases.get("accepted"):
        return "order-acceptance"
    if native_phases.get("progress") is not None \
            and java_phases.get("progress") is None:
        return "acknowledged-no-progress"
    if native_phases.get("progress") != java_phases.get("progress"):
        return "command-cadence"
    name = command["name"]
    if name == "move":
        return "destination-projection-or-congestion"
    if name == "attack":
        return "attack-acquisition-or-chase"
    if name in ("follow", "board"):
        return "follow-or-boarding-approach"
    if name in ("harvest", "return-goods"):
        return "resource-approach-or-delivery"
    if name == "build":
        return "building-placement-or-approach"
    return f"{name}-outcome"


def _phase_difference(native: dict[str, Any], java: dict[str, Any]) -> str | None:
    for phase in PHASES:
        if native["phases"].get(phase) != java["phases"].get(phase):
            return phase
    return None


def compare(plan: dict[str, Any], native_trace: dict[str, Any],
        java_trace: dict[str, Any]) -> dict[str, Any]:
    _validate_plan(plan)
    native = _validate_trace(plan, native_trace, "native")
    java = _validate_trace(plan, java_trace, "java")
    differences = []
    clusters: Counter[str] = Counter()
    keys = sorted(set(native) | set(java))
    for key in keys:
        native_outcome = native.get(key)
        java_outcome = java.get(key)
        phase = None
        if native_outcome is not None and java_outcome is not None:
            phase = _phase_difference(native_outcome, java_outcome)
            if phase is None:
                continue
        command = _command(plan, key[0], key[1])
        cluster = _classify(command, native_outcome, java_outcome)
        clusters[cluster] += 1
        differences.append({
            "record": key[0],
            "command": key[1],
            "unit": key[2],
            "name": command["name"],
            "phase": phase or "presence",
            "cluster": cluster,
            "native": native_outcome,
            "java": java_outcome,
        })
    return {
        "schema": REPORT_SCHEMA,
        "identity": _trace_identity(plan),
        "producers": {
            "native": native_trace["producer"],
            "java": java_trace["producer"],
        },
        "compared_outcomes": len(keys),
        "matching_outcomes": len(keys) - len(differences),
        "difference_count": len(differences),
        "clusters": [
            {"name": name, "count": count}
            for name, count in sorted(
                clusters.items(), key=lambda item: (-item[1], item[0]))
        ],
        "first_difference": differences[0] if differences else None,
        "differences": differences,
    }


def compare_command(args: argparse.Namespace) -> int:
    plan = _load_json(args.plan, "replay plan")
    native = _load_json(args.native, "native outcome trace")
    java = _load_json(args.java, "Java outcome trace")
    report = compare(plan, native, java)
    _write_json(args.output, report)
    return 0 if report["difference_count"] == 0 else 2


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    subcommands = result.add_subparsers(dest="command", required=True)

    plan = subcommands.add_parser(
        "plan", help="compile one authenticated replay into an exact schedule")
    plan.add_argument("replay", type=Path)
    plan.add_argument("--asset-pack", type=Path)
    plan.add_argument("--output", type=Path)
    plan.set_defaults(func=plan_command)

    corpus = subcommands.add_parser(
        "corpus", help="compile stable outcome-plan identities for a collection")
    corpus.add_argument("sources", nargs="+", type=Path)
    corpus.add_argument("--expect-corpus-sha256")
    corpus.add_argument("--asset-pack", type=Path)
    corpus.add_argument("--output", type=Path)
    corpus.set_defaults(func=corpus_command)

    schedule = subcommands.add_parser(
        "native-schedule",
        help="encode a plan for the guarded native packet-dispatch wrapper")
    schedule.add_argument("plan", type=Path)
    schedule.add_argument("--output", required=True, type=Path)
    schedule.set_defaults(func=native_schedule_command)

    verify_native = subcommands.add_parser(
        "verify-native",
        help="verify a retail trace consumed every planned dispatcher record")
    verify_native.add_argument("plan", type=Path)
    verify_native.add_argument("--trace", required=True, type=Path)
    verify_native.add_argument("--output", type=Path)
    verify_native.set_defaults(func=verify_native_command)

    skeleton = subcommands.add_parser(
        "trace-skeleton", help="write a fail-closed producer trace template")
    skeleton.add_argument("plan", type=Path)
    skeleton.add_argument("--side", choices=SIDES, required=True)
    skeleton.add_argument("--producer", required=True)
    skeleton.add_argument("--build-sha256", required=True)
    skeleton.add_argument("--output", type=Path)
    skeleton.set_defaults(func=skeleton_command)

    compare_parser = subcommands.add_parser(
        "compare", help="compare authenticated native and Java outcomes")
    compare_parser.add_argument("plan", type=Path)
    compare_parser.add_argument("--native", required=True, type=Path)
    compare_parser.add_argument("--java", required=True, type=Path)
    compare_parser.add_argument("--output", type=Path)
    compare_parser.set_defaults(func=compare_command)
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        return args.func(args)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"bne-replay-outcome: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
