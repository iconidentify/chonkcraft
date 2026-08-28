#!/usr/bin/env python3
"""Normalized AI.BIN decision ledger for BNE 2.02b.

Coarse semantic-v2 player counters are not this ledger. Each row is one
active computer player at one cycle: profile, AI.BIN program-counter /
list / threshold offsets, wait, every non-pointer byte of the native
48-byte AIPlayerState, predicate attempts, state writes, launch
consumption, and whether the row is an independent choice or earlier
movement/combat/economy/identity fallout.

Pointers at state +0x04, +0x23, and +0x27 are AI.BIN file offsets after
normalization. Raw process addresses, out-of-range pointers, a missing
active-player cycle, or two different rows for the same cycle and player
fail closed.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import struct
from typing import Any


LEDGER_SCHEMA = "chonkcraft-bne-ai-decision-ledger-2"
STATE_BYTES = 48
PTR_PC = 0x04
PTR_LIST = 0x23
PTR_THRESHOLD = 0x27
PTR_WIDTH = 4
POINTER_OFFSETS = (PTR_PC, PTR_LIST, PTR_THRESHOLD)
CLASSIFICATIONS = frozenset({"independent-choice", "fallout"})
PINNED_BNE_EXECUTABLE_SHA256 = (
    "b0e914a9cb7dcc81a205e700a9bb0a1d0649df19d459388051ba170783d2c807"
)

# Native FUN_00426ad0 consumes these pending bytes on the recurring 50-cycle
# pass.  The following byte is the number of fighters per group and the next
# byte is the number of groups.  The function passes one effective map point
# to FUN_004275b0 for every assigned unit; it does not retain an enemy pointer.
LAUNCH_DOMAINS = (
    ("ground", 0x09, 0x0d, 0x0e),
    ("naval", 0x0a, 0x0f, 0x10),
    ("air", 0x0b, 0x11, 0x12),
)

STATE_HEADER = struct.Struct("<8sHHIIIII")
CHUNK_HEADER = struct.Struct("<4sI")
CYCLE_HEADER = struct.Struct("<IIII")
PLAYER_RECORD_BYTES = 16
UNIT_DELTA_HEADER = struct.Struct("<II")
NATIVE_UNIT_BYTES = 152
NATIVE_UNIT_LIMIT = 1600
NATIVE_PLAYER_COUNT = 16
UNIT_FLAGS = 30
UNIT_OWNER = 44
UNIT_AI_HOME_X = 88
UNIT_AI_HOME_Y = 90
UNIT_AI_BEHAVIOR = 94
UNIT_FREE_OR_DEAD = 0x05


def _u32(raw: bytes, offset: int) -> int:
    return int.from_bytes(raw[offset:offset + 4], "little")


def _hex_bytes(raw: bytes) -> str:
    return raw.hex()


def normalize_pointer(value: int, ai_base: int, ai_size: int) -> int:
    """Map a native pointer or already-relative offset onto AI.BIN."""
    if value == 0:
        return 0
    if 0 <= value < ai_size:
        return value
    if ai_base <= value < ai_base + ai_size:
        return value - ai_base
    raise ValueError(
        f"AI.BIN pointer {value:#x} is not in [{ai_base:#x}, "
        f"{ai_base + ai_size:#x}) and is not an in-range file offset"
    )


def non_pointer_bytes(raw: bytes) -> bytes:
    if len(raw) != STATE_BYTES:
        raise ValueError(f"AIPlayerState is {len(raw)} bytes, not {STATE_BYTES}")
    kept = bytearray()
    skip = set()
    for start in POINTER_OFFSETS:
        skip.update(range(start, start + PTR_WIDTH))
    for index, byte in enumerate(raw):
        if index not in skip:
            kept.append(byte)
    return bytes(kept)


def normalize_state(raw: bytes, ai_base: int, ai_size: int) -> dict[str, Any]:
    if len(raw) != STATE_BYTES:
        raise ValueError(f"AIPlayerState is {len(raw)} bytes, not {STATE_BYTES}")
    if ai_size <= 0:
        raise ValueError("AI.BIN size must be positive")
    return {
        "wait": _u32(raw, 0),
        "pc_offset": normalize_pointer(_u32(raw, PTR_PC), ai_base, ai_size),
        "list_offset": normalize_pointer(_u32(raw, PTR_LIST), ai_base, ai_size),
        "threshold_offset": normalize_pointer(
            _u32(raw, PTR_THRESHOLD), ai_base, ai_size),
        "non_pointer_hex": _hex_bytes(non_pointer_bytes(raw)),
    }


def normalized_state_bytes(raw: bytes, ai_base: int, ai_size: int) -> bytes:
    """Return the compared 48-byte state with all pointers as file offsets."""
    if len(raw) != STATE_BYTES:
        raise ValueError(f"AIPlayerState is {len(raw)} bytes, not {STATE_BYTES}")
    normalized = bytearray(raw)
    for offset in POINTER_OFFSETS:
        value = normalize_pointer(_u32(raw, offset), ai_base, ai_size)
        normalized[offset:offset + PTR_WIDTH] = value.to_bytes(
            PTR_WIDTH, "little")
    return bytes(normalized)


def row(*, cycle: int, player: int, profile: int, raw_state: bytes,
        ai_base: int, ai_size: int,
        predicates: list[dict[str, Any]] | None = None,
        writes: list[dict[str, Any]] | None = None,
        launches: list[dict[str, Any]] | None = None,
        classification: str) -> dict[str, Any]:
    if classification not in CLASSIFICATIONS:
        raise ValueError(f"classification {classification!r} is not "
                         f"{sorted(CLASSIFICATIONS)}")
    if cycle < 1:
        raise ValueError("cycle is 1-based")
    state = normalize_state(raw_state, ai_base, ai_size)
    return {
        "cycle": cycle,
        "player": player,
        "profile": profile,
        "wait": state["wait"],
        "pc_offset": state["pc_offset"],
        "list_offset": state["list_offset"],
        "threshold_offset": state["threshold_offset"],
        "non_pointer_hex": state["non_pointer_hex"],
        "predicates": list(predicates or ()),
        "writes": list(writes or ()),
        "launches": list(launches or ()),
        "classification": classification,
    }


def build_ledger(rows: list[dict[str, Any]], *,
                 authority_sha256: str = PINNED_BNE_EXECUTABLE_SHA256,
                 active_players: list[int] | None = None,
                 cycles: list[int] | None = None) -> dict[str, Any]:
    if authority_sha256 != PINNED_BNE_EXECUTABLE_SHA256:
        raise ValueError("AI decision ledger authority is not pinned BNE 2.02b")
    ordered = sorted(rows, key=lambda item: (item["cycle"], item["player"]))
    seen: set[tuple[int, int]] = set()
    for item in ordered:
        key = (item["cycle"], item["player"])
        if key in seen:
            raise ValueError(
                f"duplicate AI decision row at cycle {key[0]} player {key[1]}")
        seen.add(key)
    if active_players is not None and cycles is not None:
        for cycle in cycles:
            for player in active_players:
                if (cycle, player) not in seen:
                    raise ValueError(
                        f"missing active-player cycle {cycle} player {player}")
    return {
        "schema": LEDGER_SCHEMA,
        "authority_sha256": authority_sha256,
        "rows": ordered,
    }


def coverage_report(ledger: dict[str, Any], *,
                    active_players: list[int] | None = None,
                    cycles: list[int] | None = None) -> dict[str, Any]:
    """Describe what was actually observed; generation is never execution."""
    rows = ledger.get("rows") or []
    observed = {(int(item["cycle"]), int(item["player"])) for item in rows}
    expected = ({(cycle, player) for cycle in (cycles or ())
                 for player in (active_players or ())}
                if active_players is not None and cycles is not None else set())
    missing = sorted(expected - observed)
    players = sorted({int(item["player"]) for item in rows})
    observed_cycles = sorted({int(item["cycle"]) for item in rows})
    return {
        "complete": bool(rows) and not missing,
        "rows": len(rows),
        "players": players,
        "cycles": observed_cycles,
        "cycle_span": ([observed_cycles[0], observed_cycles[-1]]
                       if observed_cycles else None),
        "independent_choice_rows": sum(
            item.get("classification") == "independent-choice"
            for item in rows),
        "predicate_events": sum(len(item.get("predicates") or ())
                                for item in rows),
        "write_events": sum(len(item.get("writes") or ()) for item in rows),
        "launch_events": sum(len(item.get("launches") or ()) for item in rows),
        "missing_active_player_cycles": [
            {"cycle": cycle, "player": player} for cycle, player in missing
        ],
    }


STATE_FIELDS = (
    "cycle", "player", "profile", "wait", "pc_offset", "list_offset",
    "threshold_offset", "non_pointer_hex",
)
TELEMETRY_FIELDS = ("predicates", "writes", "launches", "classification")


def first_difference_in(left: dict[str, Any], right: dict[str, Any],
                        fields: tuple[str, ...]) -> dict[str, Any] | None:
    if left.get("schema") != right.get("schema"):
        return {"cycle": 0, "player": -1, "field": "schema",
                "left": left.get("schema"), "right": right.get("schema")}
    a_rows = left.get("rows") or []
    b_rows = right.get("rows") or []
    limit = max(len(a_rows), len(b_rows))
    for index in range(limit):
        if index >= len(a_rows) or index >= len(b_rows):
            row = a_rows[index] if index < len(a_rows) else b_rows[index]
            return {"cycle": row["cycle"], "player": row["player"],
                    "field": "row", "left": index < len(a_rows),
                    "right": index < len(b_rows)}
        a, b = a_rows[index], b_rows[index]
        for field in fields:
            if a.get(field) != b.get(field):
                return {"cycle": a["cycle"], "player": a["player"],
                        "field": field, "left": a.get(field),
                        "right": b.get(field)}
    return None


def first_state_difference(left: dict[str, Any], right: dict[str, Any]) \
        -> dict[str, Any] | None:
    """First committed-state mismatch, independent of optional hook telemetry."""
    return first_difference_in(left, right, STATE_FIELDS)


def first_telemetry_difference(left: dict[str, Any], right: dict[str, Any]) \
        -> dict[str, Any] | None:
    """First predicate/write/launch mismatch after state rows are paired."""
    return first_difference_in(left, right, TELEMETRY_FIELDS)


def first_difference(left: dict[str, Any], right: dict[str, Any]) \
        -> dict[str, Any] | None:
    return (first_state_difference(left, right)
            or first_telemetry_difference(left, right))


def ledgers_identical(left: dict[str, Any], right: dict[str, Any]) -> bool:
    return first_difference(left, right) is None


def mutate_pc(ledger: dict[str, Any], cycle: int, player: int,
              delta: int) -> dict[str, Any]:
    return _mutate_int_field(ledger, cycle, player, "pc_offset", delta)


def mutate_predicate_result(ledger: dict[str, Any], cycle: int, player: int,
                            index: int = 0) -> dict[str, Any]:
    clone = json.loads(json.dumps(ledger))
    for item in clone["rows"]:
        if item["cycle"] == cycle and item["player"] == player:
            if index >= len(item["predicates"]):
                raise ValueError("no predicate at that index")
            current = item["predicates"][index]
            flipped = dict(current)
            flipped["result"] = not bool(current.get("result"))
            item["predicates"][index] = flipped
            return clone
    raise ValueError(f"no row at cycle {cycle} player {player}")


def mutate_state_byte(ledger: dict[str, Any], cycle: int, player: int,
                      nibble: int = 0) -> dict[str, Any]:
    clone = json.loads(json.dumps(ledger))
    for item in clone["rows"]:
        if item["cycle"] == cycle and item["player"] == player:
            hex_bytes = item["non_pointer_hex"]
            if nibble * 2 + 2 > len(hex_bytes):
                raise ValueError("state byte is out of range")
            original = int(hex_bytes[nibble * 2:nibble * 2 + 2], 16)
            mutated = (original + 1) & 0xff
            item["non_pointer_hex"] = (
                hex_bytes[:nibble * 2]
                + f"{mutated:02x}"
                + hex_bytes[nibble * 2 + 2:]
            )
            return clone
    raise ValueError(f"no row at cycle {cycle} player {player}")


def _mutate_int_field(ledger: dict[str, Any], cycle: int, player: int,
                      field: str, delta: int) -> dict[str, Any]:
    clone = json.loads(json.dumps(ledger))
    for item in clone["rows"]:
        if item["cycle"] == cycle and item["player"] == player:
            item[field] = int(item[field]) + delta
            return clone
    raise ValueError(f"no row at cycle {cycle} player {player}")


def compare_command(left: Path, right: Path) -> dict[str, Any]:
    a = load_ledger(left)
    b = load_ledger(right)
    state_difference = first_state_difference(a, b)
    telemetry_difference = first_telemetry_difference(a, b)
    return {
        "identical": state_difference is None and telemetry_difference is None,
        "state_identical": state_difference is None,
        "telemetry_identical": telemetry_difference is None,
        "difference": state_difference or telemetry_difference,
        "state_difference": state_difference,
        "telemetry_difference": telemetry_difference,
        "left": str(left),
        "right": str(right),
    }


def load_ledger(path: Path) -> dict[str, Any]:
    text = path.read_text(encoding="utf-8")
    stripped = text.lstrip()
    if stripped.startswith("{") or stripped.startswith("["):
        return json.loads(text)
    raise ValueError(f"AI decision ledger is not JSON: {path}")


def parse_state_hex(text: str) -> bytes:
    parts = [part for part in text.strip().split(",") if part]
    if len(parts) != STATE_BYTES:
        raise ValueError(
            f"AIPlayerState dump is {len(parts)} bytes, not {STATE_BYTES}")
    return bytes(int(part, 16) for part in parts)


def parse_trace_fields(line: str) -> dict[str, str]:
    fields: dict[str, str] = {}
    for token in line.split():
        if "=" not in token:
            continue
        key, value = token.split("=", 1)
        fields[key] = value
    return fields


def derive_ai_base(text: str, ai_bin: bytes) -> int:
    """Derive the ASLR heap base from authenticated profile list pointers."""
    if len(ai_bin) < 83 * 2:
        raise ValueError("AI.BIN is too short for the 83-profile table")
    candidates: set[int] = set()
    for line in text.splitlines():
        if "event=ai-build-boundary" not in line:
            continue
        fields = parse_trace_fields(line)
        if "state" not in fields or "profile" not in fields:
            continue
        profile = int(fields["profile"])
        if profile < 0 or profile >= 83:
            raise ValueError(f"AI profile is out of range: {profile}")
        record = int.from_bytes(ai_bin[profile * 2:profile * 2 + 2], "little")
        if record + 3 >= len(ai_bin):
            raise ValueError(f"AI profile {profile} record is out of range")
        list_offset = int.from_bytes(ai_bin[record:record + 2], "little")
        threshold_offset = int.from_bytes(ai_bin[record + 2:record + 4], "little")
        raw = parse_state_hex(fields["state"])
        live_list = _u32(raw, PTR_LIST)
        live_threshold = _u32(raw, PTR_THRESHOLD)
        if live_list:
            candidates.add(live_list - list_offset)
        if live_threshold and threshold_offset != 0xffff:
            candidates.add(live_threshold - threshold_offset)
    if len(candidates) != 1:
        raise ValueError(f"AI.BIN base is not unique: {sorted(candidates)}")
    base = next(iter(candidates))
    if base <= 0:
        raise ValueError("derived AI.BIN base is not a process address")
    return base


def _state_writes(before: bytes | None, after: bytes) -> list[dict[str, int]]:
    if before is None or len(before) != STATE_BYTES:
        return []
    return [
        {"offset": offset, "before": before[offset], "after": after[offset]}
        for offset in range(STATE_BYTES) if before[offset] != after[offset]
    ]


def _merge_writes(*groups: list[dict[str, int]]) -> list[dict[str, int]]:
    merged: dict[int, dict[str, int]] = {}
    for group in groups:
        for item in group:
            merged[int(item["offset"])] = item
    return [merged[offset] for offset in sorted(merged)]


def _bytecode_events(ai_bin: bytes | None, incoming: bytes | None,
        outgoing: bytes, ai_base: int, ai_size: int) \
        -> tuple[list[dict[str, Any]], list[dict[str, int]]]:
    """Recover one authenticated SET/JUMP path up to its yielding opcode.

    The boundary snapshots normally expose net state writes.  They cannot see
    a launch byte that bytecode sets and the same periodic pass consumes.  A
    path is accepted only when the outgoing PC is one of the exact legal
    results of the reached WAIT or WAIT-UNTIL.  Otherwise both predicates and
    transient SETs fail closed rather than being inferred from nearby bytes.
    """
    if ai_bin is None or incoming is None or _u32(incoming, 0) != 0:
        return [], []
    try:
        pc = normalize_pointer(_u32(incoming, PTR_PC), ai_base, ai_size)
        later = normalize_pointer(_u32(outgoing, PTR_PC), ai_base, ai_size)
    except ValueError:
        return [], []
    writes: list[dict[str, int]] = []
    visited: set[int] = set()
    while 0 <= pc < len(ai_bin) and pc not in visited:
        visited.add(pc)
        opcode = ai_bin[pc]
        if opcode == 0:  # SET offset, value
            if pc + 2 >= len(ai_bin) or ai_bin[pc + 1] >= STATE_BYTES:
                return [], []
            writes.append({"offset": ai_bin[pc + 1],
                           "value": ai_bin[pc + 2]})
            pc += 3
            continue
        if opcode == 1:  # JUMP absolute file offset
            if pc + 2 >= len(ai_bin):
                return [], []
            pc = int.from_bytes(ai_bin[pc + 1:pc + 3], "little")
            continue
        if opcode == 2:  # WAIT dword
            if pc + 4 >= len(ai_bin) or later != pc + 5:
                return [], []
            duration = int.from_bytes(ai_bin[pc + 1:pc + 5], "little")
            if _u32(outgoing, 0) != duration:
                return [], []
            return [], writes
        if opcode != 3 or pc + 1 >= len(ai_bin):
            return [], []
        if later == pc:
            result = False
        elif later == pc + 2:
            result = True
        else:
            return [], []
        return [{"id": ai_bin[pc + 1], "result": result}], writes
    return [], []


def _opcode3_predicates(ai_bin: bytes | None, incoming: bytes | None,
        outgoing: bytes, ai_base: int, ai_size: int) -> list[dict[str, Any]]:
    """Recover WAIT-UNTIL attempts hidden between committed boundaries."""
    predicates, _writes = _bytecode_events(
        ai_bin, incoming, outgoing, ai_base, ai_size)
    return predicates


def _launch_requests(ai_bin: bytes | None, incoming: bytes | None,
        outgoing: bytes, ai_base: int, ai_size: int) \
        -> list[dict[str, Any]]:
    """Describe pending launch edges proved consumed in this native tick."""
    if incoming is None:
        return []
    _predicates, bytecode_writes = _bytecode_events(
        ai_bin, incoming, outgoing, ai_base, ai_size)
    transient = bytearray(incoming)
    for write in bytecode_writes:
        transient[write["offset"]] = write["value"]
    requests = []
    for domain, pending, group_size, group_count in LAUNCH_DOMAINS:
        armed = transient[pending] != 0
        consumed = outgoing[pending] == 0
        if not armed or not consumed:
            continue
        requests.append({
            "domain": domain,
            "requested": transient[group_size] * transient[group_count],
        })
    return requests


def _native_launch_evidence(state_raw: bytes,
        requests: dict[tuple[int, int], list[dict[str, Any]]]) \
        -> dict[tuple[int, int], list[dict[str, Any]]]:
    """Bind one-domain launch requests to their sealed unit-state effects.

    Native 0x426e7c refuses units already carrying behavior two, and 0x4275b0
    writes behavior two plus one effective home point to each unit it accepts.
    Therefore a transition into behavior two on the request cycle is the
    durable assignment receipt.  More than one domain in the same player tick
    is intentionally left without telemetry because the state image alone
    cannot partition those units without inventing a type predicate.
    """
    if not requests:
        return {}
    source = io.BytesIO(state_raw)
    header_raw = source.read(STATE_HEADER.size)
    if len(header_raw) != STATE_HEADER.size:
        raise ValueError("native launch evidence has a truncated state header")
    header = STATE_HEADER.unpack(header_raw)
    if header != (b"BNESTATE", 1, 1, STATE_HEADER.size,
                  NATIVE_UNIT_BYTES, NATIVE_UNIT_LIMIT,
                  NATIVE_PLAYER_COUNT, 15):
        raise ValueError("native launch evidence requires BNESTATE 1.1")
    units: list[bytes | None] = [None] * NATIVE_UNIT_LIMIT
    evidence: dict[tuple[int, int], list[dict[str, Any]]] = {}
    seen_cycles: set[int] = set()
    while True:
        chunk_raw = source.read(CHUNK_HEADER.size)
        if not chunk_raw:
            break
        if len(chunk_raw) != CHUNK_HEADER.size:
            raise ValueError("native launch evidence has a truncated chunk header")
        tag, size = CHUNK_HEADER.unpack(chunk_raw)
        payload_raw = source.read(size)
        if len(payload_raw) != size:
            raise ValueError("native launch evidence has a truncated chunk")
        if tag != b"CYCL":
            continue
        payload = io.BytesIO(payload_raw)
        cycle_raw = payload.read(CYCLE_HEADER.size)
        if len(cycle_raw) != CYCLE_HEADER.size:
            raise ValueError("native launch evidence has a truncated cycle")
        cycle, _seed, _pool_count, changed = CYCLE_HEADER.unpack(cycle_raw)
        seen_cycles.add(cycle)
        players_raw = payload.read(NATIVE_PLAYER_COUNT * PLAYER_RECORD_BYTES)
        if len(players_raw) != NATIVE_PLAYER_COUNT * PLAYER_RECORD_BYTES:
            raise ValueError("native launch evidence has no player table")
        changed_rows: list[tuple[int, bytes | None, bytes]] = []
        changed_slots: set[int] = set()
        for _ in range(changed):
            delta_raw = payload.read(UNIT_DELTA_HEADER.size)
            if len(delta_raw) != UNIT_DELTA_HEADER.size:
                raise ValueError("native launch evidence has a truncated unit delta")
            slot, _generation = UNIT_DELTA_HEADER.unpack(delta_raw)
            raw = payload.read(NATIVE_UNIT_BYTES)
            if len(raw) != NATIVE_UNIT_BYTES or slot >= NATIVE_UNIT_LIMIT:
                raise ValueError("native launch evidence has an invalid unit delta")
            if slot in changed_slots:
                raise ValueError("native launch evidence repeats a unit in one cycle")
            changed_slots.add(slot)
            before = units[slot]
            units[slot] = raw
            changed_rows.append((slot, before, raw))
        for key, tick_requests in requests.items():
            wanted_cycle, player = key
            if wanted_cycle != cycle or len(tick_requests) != 1:
                continue
            candidates: list[tuple[int, bytes]] = []
            for slot, before, after in changed_rows:
                live_after = (after[UNIT_FLAGS] & UNIT_FREE_OR_DEAD) == 0
                before_behavior = (before[UNIT_AI_BEHAVIOR]
                                   if before is not None else None)
                if live_after and after[UNIT_OWNER] == player \
                        and after[UNIT_AI_BEHAVIOR] == 2 \
                        and before_behavior != 2:
                    candidates.append((slot, after))
            candidates.sort(key=lambda item: item[0])
            request = tick_requests[0]
            if len(candidates) > int(request["requested"]):
                continue
            target = None
            if candidates:
                homes = {
                    (
                        int.from_bytes(raw[
                            UNIT_AI_HOME_X:UNIT_AI_HOME_X + 2], "little"),
                        int.from_bytes(raw[
                            UNIT_AI_HOME_Y:UNIT_AI_HOME_Y + 2], "little"),
                    )
                    for _slot, raw in candidates
                }
                if len(homes) != 1:
                    continue
                target = list(next(iter(homes)))
            evidence[key] = [{
                "domain": request["domain"],
                "requested": request["requested"],
                "assigned": len(candidates),
                "target": target,
            }]
    missing_cycles = {cycle for cycle, _player in requests} - seen_cycles
    if missing_cycles:
        raise ValueError("native launch evidence is missing request cycles: "
                         f"{sorted(missing_cycles)}")
    return evidence


def ledger_from_native_trace(text: str, *, ai_base: int, ai_size: int,
        phase: str = "game-after",
        active_players: list[int] | None = None,
        cycles: list[int] | None = None,
        ai_bin: bytes | None = None,
        state_raw: bytes | None = None) -> dict[str, Any]:
    """Build a compared ledger from tracer ai-build-boundary 48-byte dumps.

    Native CHONK_BNE_TRACE_AI_BUILD_STATE writes the live AIPlayerState
    as 48 comma-separated hex bytes. Pointers at +0x04 / +0x23 / +0x27
    are process addresses; they become ai.bin file offsets here.

    The interpreter often runs after the committed game-after snapshot, so
    a same-cycle game-before dump already contains the new wait and hides
    the write. Seed incoming state from the previous committed after
    (including the last warmup-after). Keep intra-tick before/after diffs
    so a write that lands inside the unit tick is still visible.
    """
    rows: list[dict[str, Any]] = []
    launch_requests: dict[tuple[int, int], list[dict[str, Any]]] = {}
    committed: dict[int, bytes] = {}
    tick_before: dict[int, bytes] = {}
    for line in text.splitlines():
        if "event=ai-build-boundary" not in line:
            continue
        fields = parse_trace_fields(line)
        line_phase = fields.get("phase")
        if line_phase in {"game-before", "warmup-before", "warmup-after",
                          phase}:
            if "state" not in fields or "player" not in fields:
                raise ValueError("ai-build-boundary is missing player or state")
        if line_phase in {"game-before", "warmup-before"}:
            tick_before[int(fields["player"])] = parse_state_hex(fields["state"])
            continue
        if line_phase == "warmup-after":
            committed[int(fields["player"])] = parse_state_hex(fields["state"])
            continue
        if line_phase != phase:
            continue
        raw = parse_state_hex(fields["state"])
        player = int(fields["player"])
        incoming = committed.get(player, tick_before.get(player))
        normalized_raw = normalized_state_bytes(raw, ai_base, ai_size)
        normalized_incoming = (normalized_state_bytes(
            incoming, ai_base, ai_size) if incoming is not None else None)
        before_tick = tick_before.get(player)
        normalized_before_tick = (normalized_state_bytes(
            before_tick, ai_base, ai_size) if before_tick is not None else None)
        writes = _merge_writes(
            _state_writes(normalized_incoming, normalized_raw),
            _state_writes(normalized_before_tick, normalized_raw),
        )
        classification = ("independent-choice"
                          if incoming is not None and _u32(incoming, 0) == 0
                          else "fallout")
        cycle = int(fields.get("index") or 0)
        rows.append(row(
            cycle=cycle,
            player=player,
            profile=int(fields.get("profile") or 0),
            raw_state=raw,
            ai_base=ai_base,
            ai_size=ai_size,
            predicates=_opcode3_predicates(
                ai_bin, incoming, raw, ai_base, ai_size),
            writes=writes,
            classification=classification,
        ))
        requests = _launch_requests(
            ai_bin, incoming, raw, ai_base, ai_size)
        if requests:
            launch_requests[(cycle, player)] = requests
        committed[player] = raw
    if not rows:
        raise ValueError("native trace has no ai-build-boundary dumps")
    if state_raw is not None and launch_requests:
        launches = _native_launch_evidence(state_raw, launch_requests)
        for item in rows:
            item["launches"] = launches.get(
                (int(item["cycle"]), int(item["player"])), [])
    return build_ledger(rows, active_players=active_players, cycles=cycles)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Compare normalized BNE 2.02b AI decision ledgers")
    sub = parser.add_subparsers(dest="command", required=True)
    compare = sub.add_parser("compare")
    compare.add_argument("left", type=Path)
    compare.add_argument("right", type=Path)
    from_trace = sub.add_parser("from-trace")
    from_trace.add_argument("trace", type=Path)
    from_trace.add_argument("--ai-bin", type=Path,
                            help="authenticated maindat entry 277; derives heap base and size")
    from_trace.add_argument("--ai-base", type=lambda value: int(value, 0))
    from_trace.add_argument("--ai-size", type=lambda value: int(value, 0))
    from_trace.add_argument("--state", type=Path,
                            help="authenticated BNESTATE 1.1 stream for launch receipts")
    from_trace.add_argument("--output", type=Path, required=True)
    from_trace.add_argument("--phase", default="game-after")
    from_trace.add_argument("--active-player", type=int, action="append")
    from_trace.add_argument("--cycle", type=int, action="append")
    coverage = sub.add_parser("coverage")
    coverage.add_argument("ledger", type=Path)
    coverage.add_argument("--active-player", type=int, action="append")
    coverage.add_argument("--cycle", type=int, action="append")
    args = parser.parse_args(argv)
    if args.command == "compare":
        report = compare_command(args.left, args.right)
        print(json.dumps(report, indent=2, sort_keys=True))
        return 0 if report["identical"] else 1
    if args.command == "from-trace":
        trace_text = args.trace.read_text(encoding="utf-8")
        ai_bytes = args.ai_bin.read_bytes() if args.ai_bin is not None else None
        ai_base = derive_ai_base(trace_text, ai_bytes) if ai_bytes is not None \
            else args.ai_base
        ai_size = len(ai_bytes) if ai_bytes is not None else args.ai_size
        if ai_base is None or ai_size is None:
            parser.error("from-trace requires --ai-bin or both --ai-base and --ai-size")
        built = ledger_from_native_trace(
            trace_text, ai_base=ai_base, ai_size=ai_size, phase=args.phase,
            active_players=args.active_player, cycles=args.cycle,
            ai_bin=ai_bytes,
            state_raw=(args.state.read_bytes() if args.state is not None else None))
        if ai_bytes is not None:
            built["ai_bin_sha256"] = hashlib.sha256(ai_bytes).hexdigest()
            built["ai_bin_bytes"] = len(ai_bytes)
        args.output.write_text(
            json.dumps(built, indent=2, sort_keys=True) + "\n",
            encoding="utf-8")
        print(json.dumps({"rows": len(built["rows"]),
                          "output": str(args.output)}, indent=2))
        return 0
    if args.command == "coverage":
        report = coverage_report(load_ledger(args.ledger),
                                 active_players=args.active_player,
                                 cycles=args.cycle)
        print(json.dumps(report, indent=2, sort_keys=True))
        return 0 if report["complete"] else 1
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
