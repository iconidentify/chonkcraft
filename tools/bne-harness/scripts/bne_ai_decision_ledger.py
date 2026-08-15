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
import json
from pathlib import Path
from typing import Any


LEDGER_SCHEMA = "chonkcraft-bne-ai-decision-ledger-1"
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


def first_difference(left: dict[str, Any], right: dict[str, Any]) \
        -> dict[str, Any] | None:
    if left.get("schema") != right.get("schema"):
        return {"cycle": 0, "player": -1, "field": "schema",
                "left": left.get("schema"), "right": right.get("schema")}
    a_rows = left.get("rows") or []
    b_rows = right.get("rows") or []
    limit = max(len(a_rows), len(b_rows))
    fields = (
        "cycle", "player", "profile", "wait", "pc_offset", "list_offset",
        "threshold_offset", "non_pointer_hex", "predicates", "writes",
        "launches", "classification",
    )
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
    difference = first_difference(a, b)
    return {
        "identical": difference is None,
        "difference": difference,
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


def ledger_from_native_trace(text: str, *, ai_base: int, ai_size: int,
        phase: str = "game-before") -> dict[str, Any]:
    """Build a compared ledger from tracer ai-build-boundary 48-byte dumps.

    Native CHONK_BNE_TRACE_AI_BUILD_STATE writes the live AIPlayerState
    as 48 comma-separated hex bytes. Pointers at +0x04 / +0x23 / +0x27
    are process addresses; they become ai.bin file offsets here.
    """
    rows: list[dict[str, Any]] = []
    for line in text.splitlines():
        if "event=ai-build-boundary" not in line:
            continue
        fields = parse_trace_fields(line)
        if fields.get("phase") != phase:
            continue
        if "state" not in fields or "player" not in fields:
            raise ValueError("ai-build-boundary is missing player or state")
        raw = parse_state_hex(fields["state"])
        rows.append(row(
            cycle=int(fields.get("index") or 0),
            player=int(fields["player"]),
            profile=int(fields.get("profile") or 0),
            raw_state=raw,
            ai_base=ai_base,
            ai_size=ai_size,
            classification="fallout",
        ))
    if not rows:
        raise ValueError("native trace has no ai-build-boundary dumps")
    return build_ledger(rows)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Compare normalized BNE 2.02b AI decision ledgers")
    sub = parser.add_subparsers(dest="command", required=True)
    compare = sub.add_parser("compare")
    compare.add_argument("left", type=Path)
    compare.add_argument("right", type=Path)
    from_trace = sub.add_parser("from-trace")
    from_trace.add_argument("trace", type=Path)
    from_trace.add_argument("--ai-base", type=lambda value: int(value, 0),
                            required=True)
    from_trace.add_argument("--ai-size", type=lambda value: int(value, 0),
                            required=True)
    from_trace.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    if args.command == "compare":
        report = compare_command(args.left, args.right)
        print(json.dumps(report, indent=2, sort_keys=True))
        return 0 if report["identical"] else 1
    if args.command == "from-trace":
        built = ledger_from_native_trace(
            args.trace.read_text(encoding="utf-8"),
            ai_base=args.ai_base, ai_size=args.ai_size)
        args.output.write_text(
            json.dumps(built, indent=2, sort_keys=True) + "\n",
            encoding="utf-8")
        print(json.dumps({"rows": len(built["rows"]),
                          "output": str(args.output)}, indent=2))
        return 0
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
