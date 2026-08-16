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
import copy
import hashlib
import json
from pathlib import Path
import shlex
import struct
import subprocess
import sys
import tempfile
from typing import Any, Callable
import zipfile

import bne_identity
import bne_player_transaction
import bne_replay


PLAN_SCHEMA = "chonkcraft-bne-replay-plan-1"
TRACE_SCHEMA = "chonkcraft-bne-replay-outcome-trace-1"
REPORT_SCHEMA = "chonkcraft-bne-replay-outcome-report-1"
CORPUS_SCHEMA = "chonkcraft-bne-replay-outcome-corpus-1"
CORPUS_CERTIFICATION_SCHEMA = (
    "chonkcraft-bne-replay-outcome-corpus-certification-2"
)
LIFECYCLE_SCHEMA = "chonkcraft-bne-replay-unit-lifecycle-1"
PACKET_SCHEMA = "chonkcraft-bne-replay-divergence-packet-1"
NATIVE_SCHEDULE_MAGIC = b"BNERPLN1"
NATIVE_SCHEDULE_VERSION = 1
NATIVE_SCHEDULE_HEADER = struct.Struct("<8sII32s32s")
NATIVE_SCHEDULE_RECORD = struct.Struct("<II8sI")
PINNED_BNE_EXECUTABLE_SHA256 = (
    "b0e914a9cb7dcc81a205e700a9bb0a1d0649df19d459388051ba170783d2c807"
)
AUTHENTICATED_CORPUS_SHA256 = (
    "306f7de5d8675d828f8a086fad3494e2dc2f25d0605df5175fc75010fc773673"
)
AUTHENTICATED_OUTCOME_CORPUS_SHA256 = (
    "d809845e539e1a660d928105b51e09878af3233dbbed04f87a120830b639d123"
)
AUTHENTICATED_CORPUS_TOTALS = {
    "replay_count": 27,
    "snapshot_bytes": 1_025_260,
    "record_count": 764_756,
    "command_count": 168_788,
}
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


def _current_engine_input_sha256() -> str:
    root = Path(__file__).resolve().parents[3]
    value = bne_identity.engine_input_identity(root).get("engine_input_sha256")
    if not _valid_sha256(value):
        raise ValueError("current Java engine has no hermetic input identity")
    return str(value)


def _current_program_input_sha256() -> str:
    return bne_player_transaction.current_program_input_sha256()


def _stable_unit_identity(item: dict[str, Any]) -> dict[str, Any]:
    """Return the engine-neutral identity of one replay unit lifetime.

    Native pool slots and Java ids are allocation details.  Initial units are
    instead identified by retail-visible owner/type/start square plus a
    declared duplicate ordinal.  Later births add the dispatcher record,
    producer identity and producer-local birth ordinal.  Generation remains
    in the receipt to reject id reuse, but is deliberately not used to join
    engines whose allocators count differently.
    """
    origin = str(item.get("origin") or "")
    owner = int(item.get("owner", -1))
    unit_type = str(item.get("type") or "")
    ordinal = int(item.get("ordinal", -1))
    if origin not in {"initial", "spawn"} or owner < 0 or not unit_type \
            or ordinal < 0:
        raise ValueError("unit lifecycle entry has no stable origin/owner/type/ordinal")
    if origin == "initial":
        x = int(item.get("x", -1))
        y = int(item.get("y", -1))
        if x < 0 or y < 0:
            raise ValueError("initial unit lifecycle entry has no start square")
        value = {
            "origin": origin, "owner": owner, "type": unit_type,
            "x": x, "y": y, "ordinal": ordinal,
        }
    else:
        birth_record = int(item.get("birth_record", -1))
        producer = item.get("producer")
        if birth_record < 0 or not _valid_sha256(producer):
            raise ValueError("spawn lifecycle entry has no record or producer identity")
        value = {
            "origin": origin, "owner": owner, "type": unit_type,
            "birth_record": birth_record, "producer": producer,
            "ordinal": ordinal,
        }
    value["stable_sha256"] = _sha256(_json_bytes(value))
    return value


def lifecycle_index(trace: dict[str, Any], side: str,
        record_count: int | None = None) -> dict[str, Any]:
    """Validate one side's id-reuse-safe unit lifecycle table."""
    raw = trace.get("unit_lifecycle")
    if raw is None:
        raise ValueError(f"{side} replay trace omits required lifecycle identity")
    if not isinstance(raw, dict) or raw.get("schema") != LIFECYCLE_SCHEMA \
            or not isinstance(raw.get("units"), list):
        raise ValueError(f"{side} trace has an invalid unit lifecycle table")
    # Pool ids may be reused after a unit dies.  A local lifetime is therefore
    # (id, generation), never the integer id alone.
    by_local: dict[tuple[int, int], str] = {}
    by_stable: dict[str, tuple[int, int]] = {}
    entries = []
    for item in raw["units"]:
        if not isinstance(item, dict):
            raise ValueError(f"{side} unit lifecycle entry is not an object")
        local_id = int(item.get("local_id", -1))
        generation = int(item.get("generation", -1))
        local_key = (local_id, generation)
        if local_id < 0 or generation < 0 or local_key in by_local:
            raise ValueError(f"{side} unit lifecycle repeats or omits a lifetime")
        stable = _stable_unit_identity(item)
        stable_sha = stable["stable_sha256"]
        if stable_sha in by_stable:
            raise ValueError(f"{side} unit lifecycle repeats stable identity {stable_sha}")
        by_local[local_key] = stable_sha
        by_stable[stable_sha] = local_key
        birth_record = (0 if stable["origin"] == "initial"
                        else int(stable["birth_record"]))
        death_raw = item.get("death_record")
        death_record = None if death_raw is None else int(death_raw)
        if record_count is not None and (
                birth_record < 0 or birth_record >= max(record_count, 1)
                or (death_record is not None and (
                    death_record <= birth_record or death_record > record_count))):
            raise ValueError(f"{side} unit lifecycle has impossible record bounds")
        entries.append({
            "local_id": local_id,
            "generation": generation,
            "birth_record": birth_record,
            "death_record": death_record,
            **stable,
        })
    by_id: dict[int, list[dict[str, Any]]] = {}
    for item in entries:
        by_id.setdefault(item["local_id"], []).append(item)
    for local_id, lifetimes in by_id.items():
        lifetimes.sort(key=lambda item: item["generation"])
        generations = [item["generation"] for item in lifetimes]
        if generations != list(range(len(generations))):
            raise ValueError(
                f"{side} unit {local_id} lifecycle generations are not contiguous")
        for previous, current in zip(lifetimes, lifetimes[1:]):
            if previous["death_record"] is None \
                    or previous["death_record"] > current["birth_record"]:
                raise ValueError(
                    f"{side} unit {local_id} lifecycle generations overlap")
    stable_keys = set(by_stable)
    for item in entries:
        if item["origin"] == "spawn" and item["producer"] not in stable_keys:
            raise ValueError(
                f"{side} spawned unit names an unknown producer identity")
        if item["origin"] == "spawn":
            producer_local = by_stable[item["producer"]]
            producer = next(value for value in entries if (
                value["local_id"], value["generation"]) == producer_local)
            if producer["birth_record"] > item["birth_record"] \
                    or (producer["death_record"] is not None
                        and producer["death_record"] <= item["birth_record"]):
                raise ValueError(
                    f"{side} spawned unit names a producer not alive at birth")
    return {
        "present": True, "side": side, "by_local": by_local,
        "by_stable": by_stable, "entries": entries,
    }


def lifecycle_bridge(native_trace: dict[str, Any],
        java_trace: dict[str, Any], record_count: int | None = None) \
        -> dict[str, Any]:
    """Join native slots to Java ids without assuming allocator order."""
    native = lifecycle_index(native_trace, "native", record_count)
    java = lifecycle_index(java_trace, "java", record_count)
    native_keys = set(native["by_stable"])
    java_keys = set(java["by_stable"])
    pairs = [{
        "stable_sha256": key,
        "native_unit": {
            "local_id": native["by_stable"][key][0],
            "generation": native["by_stable"][key][1],
        },
        "java_unit": {
            "local_id": java["by_stable"][key][0],
            "generation": java["by_stable"][key][1],
        },
    } for key in sorted(native_keys & java_keys)]
    return {
        "mode": "lifecycle-v1",
        "complete": native_keys == java_keys,
        "pairs": pairs,
        "unmatched_native": sorted(native_keys - java_keys),
        "unmatched_java": sorted(java_keys - native_keys),
        "native_by_local": native["by_local"],
        "java_by_local": java["by_local"],
    }


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


def certify_corpus(corpus: dict[str, Any], reports: list[dict[str, Any]], *,
        current_java_engine_input_sha256: str | None = None,
        current_java_program_input_sha256: str | None = None) -> dict[str, Any]:
    """Join all authenticated replay comparisons to one frozen denominator."""
    if corpus.get("schema") != CORPUS_SCHEMA:
        raise ValueError("replay corpus certification received the wrong schema")
    if corpus.get("corpus_sha256") != AUTHENTICATED_CORPUS_SHA256 \
            or corpus.get("outcome_corpus_sha256") \
            != AUTHENTICATED_OUTCOME_CORPUS_SHA256:
        raise ValueError("replay corpus is not the authenticated 27-file corpus")
    for field, expected in AUTHENTICATED_CORPUS_TOTALS.items():
        if int(corpus.get(field, -1)) != expected:
            raise ValueError(
                f"replay corpus {field}={corpus.get(field)!r}; expected {expected}")
    entries = corpus.get("entries")
    required_replays = AUTHENTICATED_CORPUS_TOTALS["replay_count"]
    if not isinstance(entries, list) or len(entries) != required_replays:
        raise ValueError(
            f"replay corpus must contain exactly {required_replays} entries")

    def key(value: dict[str, Any]) -> tuple[str, ...]:
        names = ("compressed_sha256", "snapshot_sha256",
                 "command_stream_sha256", "schedule_sha256", "startup_sha256")
        result = tuple(str(value.get(name) or "") for name in names)
        if any(len(item) != 64 for item in result):
            raise ValueError("replay comparison identity is incomplete")
        return result

    required: dict[tuple[str, ...], dict[str, Any]] = {}
    for entry in entries:
        identity = key(entry)
        if identity in required:
            raise ValueError("replay corpus repeats an outcome identity")
        required[identity] = entry

    observed: dict[tuple[str, ...], dict[str, Any]] = {}
    java_builds: set[str] = set()
    java_engines: set[str] = set()
    java_programs: set[str] = set()
    report_identities: list[str] = []
    current_engine = (current_java_engine_input_sha256
                      or _current_engine_input_sha256())
    current_program = (current_java_program_input_sha256
                       or _current_program_input_sha256())
    for report in reports:
        if report.get("schema") != REPORT_SCHEMA:
            raise ValueError("replay corpus contains a non-comparison receipt")
        identity = key(report.get("identity") or {})
        if identity not in required:
            raise ValueError("replay comparison is not part of the frozen corpus")
        if identity in observed:
            raise ValueError("replay corpus repeats a comparison receipt")
        producers = report.get("producers") or {}
        native = producers.get("native") or {}
        java = producers.get("java") or {}
        if native.get("build_sha256") != PINNED_BNE_EXECUTABLE_SHA256:
            raise ValueError("replay comparison was not produced by pinned BNE")
        java_build = str(java.get("build_sha256") or "")
        if len(java_build) != 64 or any(
                character not in "0123456789abcdef" for character in java_build):
            raise ValueError("replay comparison has no Java build identity")
        java_builds.add(java_build)
        java_engine = str(java.get("engine_input_sha256") or "")
        if not _valid_sha256(java_engine) or java_engine != java_build \
                or java_engine != current_engine:
            raise ValueError(
                "replay comparison is not bound to current Java engine inputs")
        java_engines.add(java_engine)
        java_program = str(java.get("program_input_sha256") or "")
        if not _valid_sha256(java_program) or java_program != current_program:
            raise ValueError(
                "replay comparison is not bound to current Java program inputs")
        java_programs.add(java_program)
        differences = report.get("differences")
        if not isinstance(differences, list) \
                or int(report.get("difference_count", -1)) != len(differences):
            raise ValueError("replay comparison difference count is inconsistent")
        if report.get("complete") is not True \
                or int(report.get("required_outcomes", -1)) <= 0:
            raise ValueError("replay comparison did not close its fixed denominator")
        report_identities.append(_sha256(_json_bytes(report)))
        observed[identity] = report
    if len(java_builds) > 1 or len(java_engines) > 1 \
            or len(java_programs) > 1:
        raise ValueError("replay corpus mixes Java builds")

    rows = []
    exact_records = exact_commands = exact_replays = 0
    for identity, entry in required.items():
        report = observed.get(identity)
        exact = bool(report is not None and report.get("exact") is True
                     and report["difference_count"] == 0)
        if exact:
            exact_replays += 1
            exact_records += int(entry["record_count"])
            exact_commands += int(entry["command_count"])
        rows.append({
            "path": entry["path"],
            "record_count": int(entry["record_count"]),
            "command_count": int(entry["command_count"]),
            "status": "exact" if exact else (
                "divergent" if report is not None else "missing"),
            "difference_count": (
                int(report["difference_count"]) if report is not None else None),
            "first_difference": (
                report.get("first_difference") if report is not None else None),
        })
    rows.sort(key=lambda item: str(item["path"]))
    content_exact = (
        exact_replays == AUTHENTICATED_CORPUS_TOTALS["replay_count"]
        and exact_records == AUTHENTICATED_CORPUS_TOTALS["record_count"]
        and exact_commands == AUTHENTICATED_CORPUS_TOTALS["command_count"]
        and len(observed) == AUTHENTICATED_CORPUS_TOTALS["replay_count"]
    )
    # Comparison JSON is not producer evidence.  Full certification requires
    # a retained proof-store validator to reopen every plan plus both native
    # and Java traces and recompute compare().  The current CLI intentionally
    # leaves this false rather than accepting self-asserted summaries.
    producer_reports_verified = False
    complete = False
    return {
        "schema": CORPUS_CERTIFICATION_SCHEMA,
        "complete": complete,
        "content_exact": content_exact,
        "producer_reports_verified": producer_reports_verified,
        "debt": (None if producer_reports_verified else
                 "detached replay comparisons cannot certify producer traces"),
        "authority": {
            "corpus_sha256": AUTHENTICATED_CORPUS_SHA256,
            "outcome_corpus_sha256": AUTHENTICATED_OUTCOME_CORPUS_SHA256,
            "native_executable_sha256": PINNED_BNE_EXECUTABLE_SHA256,
            "java_build_sha256": next(iter(java_builds), None),
            "java_engine_input_sha256": next(iter(java_engines), None),
            "java_program_input_sha256": next(iter(java_programs), None),
            "comparison_receipt_sha256": sorted(report_identities),
        },
        "required_replays": AUTHENTICATED_CORPUS_TOTALS["replay_count"],
        "compared_replays": len(observed),
        "exact_replays": exact_replays,
        "required_records": AUTHENTICATED_CORPUS_TOTALS["record_count"],
        "exact_records": exact_records,
        "required_commands": AUTHENTICATED_CORPUS_TOTALS["command_count"],
        "exact_commands": exact_commands,
        "rows": rows,
    }


def certify_corpus_command(args: argparse.Namespace) -> int:
    corpus = _load_json(args.corpus, "replay corpus")
    reports = [_load_json(path, "replay comparison") for path in args.reports]
    result = certify_corpus(corpus, reports)
    _write_json(args.output, result)
    return 0 if result["complete"] or not args.require_complete else 2


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
    if args.side == "java" and (
            not _valid_sha256(args.engine_input_sha256)
            or args.engine_input_sha256 != args.build_sha256):
        raise ValueError(
            "Java trace skeleton requires its hermetic engine-input identity")
    producer = {
        "name": args.producer,
        "build_sha256": args.build_sha256,
    }
    if args.side == "java":
        producer["engine_input_sha256"] = args.engine_input_sha256
        if not _valid_sha256(args.program_input_sha256):
            raise ValueError(
                "Java trace skeleton requires its program-input identity")
        producer["program_input_sha256"] = args.program_input_sha256
    trace = {
        "schema": TRACE_SCHEMA,
        "side": args.side,
        "identity": _trace_identity(plan),
        "producer": producer,
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
        "unit_lifecycle": {"schema": LIFECYCLE_SCHEMA, "units": []},
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


def _validate_trace(plan: dict[str, Any], trace: dict[str, Any], side: str,
        current_java_program_input_sha256: str | None = None) \
        -> dict[tuple[int, int, int, int], dict[str, Any]]:
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
    if side == "java" and (
            not _valid_sha256(producer.get("engine_input_sha256"))
            or producer.get("engine_input_sha256") != producer.get("build_sha256")):
        raise ValueError(
            "Java replay trace is not bound to a hermetic engine-input identity")
    if side == "java" and (
            not _valid_sha256(producer.get("program_input_sha256"))
            or (current_java_program_input_sha256 is not None
                and producer.get("program_input_sha256")
                != current_java_program_input_sha256)):
        raise ValueError(
            "Java replay trace is not bound to current program inputs")
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
    indexed: dict[tuple[int, int, int, int], dict[str, Any]] = {}
    for outcome in trace.get("outcomes", ()):
        if not isinstance(outcome, dict):
            raise ValueError(f"{side} trace contains a non-object outcome")
        record = int(outcome.get("record", -1))
        command = int(outcome.get("command", -1))
        unit = int(outcome.get("unit", -1))
        if "unit_generation" not in outcome:
            raise ValueError(
                f"{side} trace outcome omits id-reuse-safe unit generation")
        generation = int(outcome["unit_generation"])
        if (record, command) not in valid_commands or unit < 0:
            raise ValueError(f"{side} trace outcome names an unknown command or unit")
        if generation < 0:
            raise ValueError(f"{side} trace outcome has a negative unit generation")
        key = (record, command, unit, generation)
        if key in indexed:
            raise ValueError(f"{side} trace repeats outcome {key}")
        phases = outcome.get("phases")
        if not isinstance(phases, dict) or set(phases) != set(PHASES):
            raise ValueError(f"{side} trace outcome {key} omits lifecycle phases")
        unknown = set(phases) - set(PHASES)
        if unknown:
            raise ValueError(f"{side} trace outcome {key} has unknown phases {unknown}")
        if not isinstance(phases.get("accepted"), bool):
            raise ValueError(f"{side} trace outcome {key} has no boolean acceptance")
        if not isinstance(phases.get("submitted"), dict):
            raise ValueError(f"{side} trace outcome {key} has no submission receipt")
        for phase in ("progress", "terminal"):
            if phases.get(phase) is not None and not isinstance(
                    phases.get(phase), dict):
                raise ValueError(
                    f"{side} trace outcome {key} has invalid {phase} evidence")
        indexed[key] = outcome
    return indexed


def _lifetime_at_record(index: dict[str, Any], local_id: int,
        record: int, side: str) -> tuple[int, str]:
    candidates = [item for item in index["entries"]
                  if item["local_id"] == local_id
                  and item["birth_record"] <= record
                  and (item["death_record"] is None
                       or record < item["death_record"])]
    if len(candidates) != 1:
        raise ValueError(
            f"{side} lifecycle cannot identify selected unit {local_id} "
            f"at replay record {record}")
    return candidates[0]["generation"], candidates[0]["stable_sha256"]


def _required_outcome_keys(plan: dict[str, Any],
        native_lifecycle: dict[str, Any]) -> set[tuple[int, int, str]]:
    required: set[tuple[int, int, str]] = set()
    for record in plan["records"]:
        record_index = int(record["index"])
        for command in record["commands"]:
            if command["name"] == "selection":
                continue
            command_index = int(command["index"])
            selected = command.get("selected_unit_ids")
            if not isinstance(selected, list) or not selected:
                raise ValueError(
                    "replay command has no fixed selected-unit denominator: "
                    f"record={record_index} command={command_index}")
            if len(selected) != len(set(selected)):
                raise ValueError("replay command repeats a selected unit")
            for local_id in selected:
                _generation, stable = _lifetime_at_record(
                    native_lifecycle, int(local_id), record_index, "native")
                key = (record_index, command_index, stable)
                if key in required:
                    raise ValueError("replay denominator repeats a selected lifetime")
                required.add(key)
    return required


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


def _normalized_outcomes(
        indexed: dict[tuple[int, int, int, int], dict[str, Any]],
        local_to_stable: dict[tuple[int, int], str] | None) \
        -> dict[tuple[int, int, str], tuple[dict[str, int], dict[str, Any]]]:
    normalized: dict[
        tuple[int, int, str], tuple[dict[str, int], dict[str, Any]]
    ] = {}
    for (record, command, local_id, generation), value in indexed.items():
        if local_to_stable is None:
            # Preserve the old numeric unit ordering in legacy reports while
            # keeping the representation unambiguous.
            stable = f"local:{local_id:010d}:{generation:010d}"
        else:
            stable = local_to_stable.get((local_id, generation))
            if stable is None:
                raise ValueError(
                    "replay outcome has no matching unit lifecycle entry: "
                    f"unit={local_id} generation={generation}")
        key = (record, command, stable)
        if key in normalized:
            raise ValueError(f"replay outcome repeats stable unit command {key}")
        normalized[key] = ({
            "local_id": local_id, "generation": generation,
        }, value)
    return normalized


def compare(plan: dict[str, Any], native_trace: dict[str, Any],
        java_trace: dict[str, Any], *,
        current_java_program_input_sha256: str | None = None) -> dict[str, Any]:
    _validate_plan(plan)
    current_program = (current_java_program_input_sha256
                       or _current_program_input_sha256())
    native_local = _validate_trace(plan, native_trace, "native")
    java_local = _validate_trace(
        plan, java_trace, "java", current_program)
    record_count = len(plan["records"])
    bridge = lifecycle_bridge(native_trace, java_trace, record_count)
    native_lifecycle = lifecycle_index(native_trace, "native", record_count)
    required = _required_outcome_keys(plan, native_lifecycle)
    native = _normalized_outcomes(
        native_local, bridge["native_by_local"])
    java = _normalized_outcomes(
        java_local, bridge["java_by_local"])
    differences = []
    clusters: Counter[str] = Counter()
    native_by_stable = {
        stable: local for local, stable in bridge["native_by_local"].items()
    }
    java_by_stable = {
        stable: local for local, stable in bridge["java_by_local"].items()
    }
    for stable in bridge["unmatched_native"]:
        local = native_by_stable[stable]
        clusters["unit-identity-unresolved"] += 1
        differences.append({
            "record": 0, "command": -1, "unit_identity": stable,
            "native_unit": {
                "local_id": local[0], "generation": local[1],
            },
            "java_unit": None, "name": "unit-lifecycle",
            "phase": "identity", "cluster": "unit-identity-unresolved",
            "native": "present", "java": None,
        })
    for stable in bridge["unmatched_java"]:
        local = java_by_stable[stable]
        clusters["unit-identity-unresolved"] += 1
        differences.append({
            "record": 0, "command": -1, "unit_identity": stable,
            "native_unit": None,
            "java_unit": {
                "local_id": local[0], "generation": local[1],
            },
            "name": "unit-lifecycle", "phase": "identity",
            "cluster": "unit-identity-unresolved",
            "native": None, "java": "present",
        })
    keys = sorted(required | set(native) | set(java))
    for key in keys:
        native_pair = native.get(key)
        java_pair = java.get(key)
        native_outcome = native_pair[1] if native_pair is not None else None
        java_outcome = java_pair[1] if java_pair is not None else None
        phase = None
        if key not in required:
            clusters["unexpected-outcome"] += 1
            differences.append({
                "record": key[0], "command": key[1],
                "unit_identity": key[2],
                "native_unit": (
                    native_pair[0] if native_pair is not None else None),
                "java_unit": java_pair[0] if java_pair is not None else None,
                "name": _command(plan, key[0], key[1])["name"],
                "phase": "denominator", "cluster": "unexpected-outcome",
                "native": native_outcome, "java": java_outcome,
            })
            continue
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
            "unit_identity": key[2],
            "native_unit": native_pair[0] if native_pair is not None else None,
            "java_unit": java_pair[0] if java_pair is not None else None,
            "name": command["name"],
            "phase": phase or "presence",
            "cluster": cluster,
            "native": native_outcome,
            "java": java_outcome,
        })
    denominator_complete = (
        bridge["complete"] and set(native) == required and set(java) == required)
    return {
        "schema": REPORT_SCHEMA,
        "identity": _trace_identity(plan),
        "producers": {
            "native": native_trace["producer"],
            "java": java_trace["producer"],
        },
        "identity_bridge": {
            key: value for key, value in bridge.items()
            if key not in {"native_by_local", "java_by_local"}
        },
        "complete": denominator_complete,
        "exact": denominator_complete and not differences,
        "required_outcomes": len(required),
        "native_outcomes": len(native),
        "java_outcomes": len(java),
        "compared_outcomes": len(required),
        "matching_outcomes": len(required) - sum(
            1 for item in differences
            if (item["record"], item["command"], item["unit_identity"])
            in required),
        "difference_count": len(differences),
        "clusters": [
            {"name": name, "count": count}
            for name, count in sorted(
                clusters.items(), key=lambda item: (-item[1], item[0]))
        ],
        "first_difference": differences[0] if differences else None,
        "differences": differences,
    }


def plan_prefix(plan: dict[str, Any], record_count: int) -> dict[str, Any]:
    """Return an authenticated replay plan containing its first N records."""
    _validate_plan(plan)
    total = len(plan["records"])
    if record_count < 0 or record_count > total:
        raise ValueError(f"replay prefix {record_count} is outside 0..{total}")
    result = copy.deepcopy(plan)
    result["records"] = result["records"][:record_count]
    result["replay"]["record_count"] = record_count
    result["replay"]["command_count"] = sum(
        len(record["commands"]) for record in result["records"])
    result["prefix"] = {
        "parent_schedule_sha256": plan["schedule_sha256"],
        "parent_record_count": total,
        "record_count": record_count,
    }
    result["schedule_sha256"] = _sha256(_json_bytes(result["records"]))
    _validate_plan(result)
    return result


def trace_prefix(trace: dict[str, Any], prefix_plan: dict[str, Any]) \
        -> dict[str, Any]:
    """Project a full trace for inspection, never for certification.

    Later replay commands can supersede an earlier command and therefore
    change its terminal result.  Filtering a full-run receipt cannot establish
    what the earlier prefix did when executed alone.  The explicit non-
    certifying status makes accidental use fail in _validate_trace.
    """
    _validate_plan(prefix_plan)
    record_count = len(prefix_plan["records"])
    result = copy.deepcopy(trace)
    result["identity"] = _trace_identity(prefix_plan)
    result["schedule"] = {
        "status": "projected-non-certifying",
        "consumed_records": record_count,
        "consumed_sha256": prefix_plan["schedule_sha256"],
    }
    result["outcomes"] = [
        item for item in result.get("outcomes", ())
        if int(item.get("record", -1)) < record_count
    ]
    lifecycle = result.get("unit_lifecycle")
    if isinstance(lifecycle, dict) and isinstance(lifecycle.get("units"), list):
        units = []
        for item in lifecycle["units"]:
            if item.get("origin") == "spawn" \
                    and int(item.get("birth_record", -1)) >= record_count:
                continue
            retained = copy.deepcopy(item)
            death_record = retained.get("death_record")
            if death_record is not None and int(death_record) >= record_count:
                retained["death_record"] = None
            units.append(retained)
        lifecycle["units"] = units
    result["prefix"] = {
        "parent_schedule_sha256": trace.get("identity", {}).get(
            "schedule_sha256"),
        "record_count": record_count,
    }
    return result


PrefixRunner = Callable[
    [dict[str, Any]], tuple[dict[str, Any], dict[str, Any]]
]


def bisect_first_divergent_prefix(plan: dict[str, Any],
        native_trace: dict[str, Any], java_trace: dict[str, Any],
        prefix_runner: PrefixRunner | None = None) \
        -> dict[str, Any]:
    """Find the first divergent prefix using fresh two-engine executions."""
    full = compare(plan, native_trace, java_trace)
    if full["difference_count"] == 0:
        return {
            "exact": True,
            "minimal_prefix_records": None,
            "last_exact_prefix_records": len(plan["records"]),
            "steps": [],
            "report": full,
        }
    if prefix_runner is None:
        raise ValueError(
            "divergent replay bisection requires fresh native and Java prefix "
            "receipts; a projection of the full run is non-certifying")

    def inspect(count: int) -> tuple[dict[str, Any], dict[str, Any],
            dict[str, Any], dict[str, Any]]:
        prefix = plan_prefix(plan, count)
        native_prefix, java_prefix = prefix_runner(prefix)
        if not isinstance(native_prefix, dict) or not isinstance(java_prefix, dict):
            raise ValueError("prefix runner did not return two outcome receipts")
        return prefix, native_prefix, java_prefix, compare(
            prefix, native_prefix, java_prefix)

    steps = []
    zero = inspect(0)
    steps.append({"record_count": 0,
                  "difference_count": zero[3]["difference_count"]})
    if zero[3]["difference_count"]:
        minimal = zero
        prior = None
    else:
        low = 0
        high = len(plan["records"])
        while high - low > 1:
            middle = (low + high) // 2
            probe = inspect(middle)
            difference_count = probe[3]["difference_count"]
            steps.append({"record_count": middle,
                          "difference_count": difference_count})
            if difference_count:
                high = middle
            else:
                low = middle
        minimal = inspect(high)
        steps.append({"record_count": high,
                      "difference_count": minimal[3]["difference_count"]})
        prior = inspect(low)
        if prior[3]["difference_count"]:
            raise ValueError("replay prefix bisection lost its exact lower bound")
    return {
        "exact": False,
        "minimal_prefix_records": len(minimal[0]["records"]),
        "last_exact_prefix_records": (
            None if prior is None else len(prior[0]["records"])),
        "steps": steps,
        "plan": minimal[0],
        "native": minimal[1],
        "java": minimal[2],
        "report": minimal[3],
    }


def divergence_packet(plan: dict[str, Any], native_trace: dict[str, Any],
        java_trace: dict[str, Any], prefix_runner: PrefixRunner | None = None) \
        -> dict[str, Any]:
    """Build a deterministic, content-addressable minimal-prefix packet."""
    result = bisect_first_divergent_prefix(
        plan, native_trace, java_trace, prefix_runner)
    packet: dict[str, Any] = {
        "schema": PACKET_SCHEMA,
        "source_identity": _trace_identity(plan),
        **result,
    }
    packet["packet_sha256"] = _divergence_packet_identity(packet)
    return packet


def _divergence_packet_identity(packet: dict[str, Any]) -> str:
    """Hash packet content without trusting its self-declared identity."""
    payload = dict(packet)
    payload.pop("packet_sha256", None)
    return _sha256(_json_bytes(payload))


def write_divergence_packet(root: Path, packet: dict[str, Any]) -> Path:
    identity = packet.get("packet_sha256")
    if not _valid_sha256(identity):
        raise ValueError("divergence packet has no content identity")
    if identity != _divergence_packet_identity(packet):
        raise ValueError("divergence packet content identity changed")
    destination = root.expanduser().resolve() / identity / "packet.json"
    encoded = json.dumps(
        packet, indent=2, sort_keys=True, ensure_ascii=False) + "\n"
    if destination.exists():
        if destination.read_text(encoding="utf-8") != encoded:
            raise ValueError("content-addressed replay packet path changed")
        return destination
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(destination.name + ".tmp")
    temporary.write_text(encoded, encoding="utf-8")
    temporary.replace(destination)
    return destination


def compare_command(args: argparse.Namespace) -> int:
    plan = _load_json(args.plan, "replay plan")
    native = _load_json(args.native, "native outcome trace")
    java = _load_json(args.java, "Java outcome trace")
    report = compare(plan, native, java)
    _write_json(args.output, report)
    return 0 if report["exact"] else 2


def prefix_plan_command(args: argparse.Namespace) -> int:
    plan = _load_json(args.plan, "replay plan")
    _write_json(args.output, plan_prefix(plan, args.records))
    return 0


def _command_prefix_runner(native_command: str,
        java_command: str) -> PrefixRunner:
    """Create a safe argv-based fresh-prefix producer for the CLI.

    Each template must contain {plan} and {output}; {records} is optional.
    No shell is involved.  The producer's trace is subsequently authenticated
    against the exact prefix plan by compare().
    """
    templates = {"native": native_command, "java": java_command}
    for side, template in templates.items():
        if "{plan}" not in template or "{output}" not in template:
            raise ValueError(
                f"{side} prefix command must contain {{plan}} and {{output}}")

    def run(prefix: dict[str, Any]) \
            -> tuple[dict[str, Any], dict[str, Any]]:
        with tempfile.TemporaryDirectory(prefix="bne-replay-prefix-") as raw:
            root = Path(raw)
            plan_path = root / "plan.json"
            _write_json(plan_path, prefix)
            produced: dict[str, dict[str, Any]] = {}
            for side, template in templates.items():
                output = root / f"{side}.json"
                replacements = {
                    "{plan}": str(plan_path),
                    "{output}": str(output),
                    "{records}": str(len(prefix["records"])),
                }
                argv = shlex.split(template)
                for old, new in replacements.items():
                    argv = [item.replace(old, new) for item in argv]
                if not argv:
                    raise ValueError(f"{side} prefix command is empty")
                try:
                    subprocess.run(argv, check=True)
                except subprocess.CalledProcessError as error:
                    raise ValueError(
                        f"{side} prefix command failed with exit "
                        f"{error.returncode}") from error
                produced[side] = _load_json(
                    output, f"fresh {side} prefix outcome trace")
            return produced["native"], produced["java"]

    return run


def divergence_packet_command(args: argparse.Namespace) -> int:
    plan = _load_json(args.plan, "replay plan")
    native = _load_json(args.native, "native outcome trace")
    java = _load_json(args.java, "Java outcome trace")
    commands = (args.native_prefix_command, args.java_prefix_command)
    if (commands[0] is None) != (commands[1] is None):
        raise ValueError(
            "both --native-prefix-command and --java-prefix-command are required")
    runner = (_command_prefix_runner(*commands)
              if commands[0] is not None else None)
    packet = divergence_packet(plan, native, java, runner)
    destination = write_divergence_packet(args.output_dir, packet)
    print(destination)
    return 2 if args.fail_on_divergence and not packet["exact"] else 0


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

    certify = subcommands.add_parser(
        "certify-corpus",
        help="join all 27 native/Java comparisons to the frozen denominator")
    certify.add_argument("corpus", type=Path)
    certify.add_argument("reports", nargs="+", type=Path)
    certify.add_argument("--output", type=Path)
    certify.add_argument("--require-complete", action="store_true")
    certify.set_defaults(func=certify_corpus_command)

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
    skeleton.add_argument("--engine-input-sha256")
    skeleton.add_argument("--program-input-sha256")
    skeleton.add_argument("--output", type=Path)
    skeleton.set_defaults(func=skeleton_command)

    compare_parser = subcommands.add_parser(
        "compare", help="compare authenticated native and Java outcomes")
    compare_parser.add_argument("plan", type=Path)
    compare_parser.add_argument("--native", required=True, type=Path)
    compare_parser.add_argument("--java", required=True, type=Path)
    compare_parser.add_argument("--output", type=Path)
    compare_parser.set_defaults(func=compare_command)

    prefix = subcommands.add_parser(
        "prefix-plan", help="seal the first N replay records as a new plan")
    prefix.add_argument("plan", type=Path)
    prefix.add_argument("--records", required=True, type=int)
    prefix.add_argument("--output", required=True, type=Path)
    prefix.set_defaults(func=prefix_plan_command)

    packet = subcommands.add_parser(
        "divergence-packet",
        help="bisect and seal the first divergent replay prefix")
    packet.add_argument("plan", type=Path)
    packet.add_argument("--native", required=True, type=Path)
    packet.add_argument("--java", required=True, type=Path)
    packet.add_argument("--output-dir", required=True, type=Path)
    packet.add_argument(
        "--native-prefix-command",
        help="argv template that freshly executes native; requires {plan}/{output}")
    packet.add_argument(
        "--java-prefix-command",
        help="argv template that freshly executes Java; requires {plan}/{output}")
    packet.add_argument(
        "--fail-on-divergence", action="store_true",
        help="return 2 after successfully writing a divergent packet")
    packet.set_defaults(func=divergence_packet_command)
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
