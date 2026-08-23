#!/usr/bin/env python3
"""Compare one sealed BNE fixture directly with a Java engine trace."""

from __future__ import annotations

import argparse
import io
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile
import zipfile

from bne_fixture import (
    CHUNK_HEADER,
    CYCLE_HEADER,
    PLAYER_RECORD,
    STATE_HEADER,
    UNIT_DELTA_HEADER,
    validate_fixture,
)


CYCLE = re.compile(r"cycle (\d+) seed [0-9a-fA-F]+$")
UNIT = re.compile(
    rb"u (\d+) (\S+) .* o ([A-Z_]+)(?: removed)?\r?\n\Z"
)
BNE_UNIT_BYTES = 152
BNE_UNIT_FLAGS = 30
BNE_UNIT_TYPE = 39
BNE_UNIT_ORDER = 46
BNE_UNIT_FREE_OR_DEAD = 0x05

# Corpse/placeholders live after the 105 map/PUD types and were omitted from
# the schema-1.1 tracer's name table.  These two are authenticated by direct
# transitions in the sealed corpus: a 2x2 guard tower becomes 107 and a 3x3
# barracks becomes 108.  Keep the correction beside the raw-state normalizer
# so old fixtures gain the proved name without being resealed.
BNE_POST_PUD_TYPE_NAMES = {
    107: b"unit-destroyed-2x2-place",
    108: b"unit-destroyed-3x3-place",
}


def normalize_fixture_trace(trace_source, state_source, output,
        expected_cycles: int) -> None:
    """Write the fixture trace with legacy raw-action labels corrected.

    The first corpus was sealed before several raw actions were identified,
    including armed-tower idle 14, direct attack 12, stationary attack 16,
    and builder walk-to-site 28. Read the raw schema-1.1 state in lockstep so
    only those proven legacy labels are changed. New fixtures emitted by the
    corrected tracer pass through.
    """
    header_bytes = state_source.read(STATE_HEADER.size)
    if len(header_bytes) != STATE_HEADER.size:
        raise ValueError("truncated BNE state header")
    header = STATE_HEADER.unpack(header_bytes)
    unit_bytes = header[4]
    player_count = header[6]
    if unit_bytes != BNE_UNIT_BYTES:
        raise ValueError(f"unexpected BNE unit size {unit_bytes}")

    raw_orders: dict[int, int] = {}
    raw_types: dict[int, int] = {}
    current_cycle = 0

    def advance_state(wanted_cycle: int) -> None:
        nonlocal current_cycle
        while current_cycle < wanted_cycle:
            chunk_header = state_source.read(CHUNK_HEADER.size)
            if len(chunk_header) != CHUNK_HEADER.size:
                raise ValueError("BNE state ended before its textual trace")
            tag, payload_bytes = CHUNK_HEADER.unpack(chunk_header)
            raw_payload = state_source.read(payload_bytes)
            if len(raw_payload) != payload_bytes:
                raise ValueError("truncated BNE state chunk")
            payload = io.BytesIO(raw_payload)
            if tag != b"CYCL":
                continue
            cycle, _seed, pool_count, changed_units = CYCLE_HEADER.unpack(
                payload.read(CYCLE_HEADER.size)
            )
            payload.read(player_count * PLAYER_RECORD.size)
            for _ in range(changed_units):
                slot, _generation = UNIT_DELTA_HEADER.unpack(
                    payload.read(UNIT_DELTA_HEADER.size)
                )
                raw = payload.read(unit_bytes)
                if len(raw) != unit_bytes:
                    raise ValueError("truncated BNE unit state")
                if (raw[BNE_UNIT_FLAGS] & BNE_UNIT_FREE_OR_DEAD) == 0:
                    raw_orders[slot] = raw[BNE_UNIT_ORDER]
                    raw_types[slot] = raw[BNE_UNIT_TYPE]
                else:
                    raw_orders.pop(slot, None)
                    raw_types.pop(slot, None)
            for slot in tuple(raw_orders):
                if slot >= pool_count:
                    raw_orders.pop(slot)
                    raw_types.pop(slot, None)
            current_cycle = cycle
        if current_cycle != wanted_cycle:
            raise ValueError(
                f"BNE state cycle {current_cycle}; expected {wanted_cycle}"
            )

    for raw_line in trace_source:
        line = raw_line.decode("utf-8")
        cycle_match = CYCLE.fullmatch(line.rstrip("\r\n"))
        if cycle_match:
            cycle = int(cycle_match.group(1))
            if cycle > expected_cycles:
                break
            advance_state(cycle)
        elif current_cycle > 0:
            unit_match = UNIT.fullmatch(raw_line)
            if unit_match:
                raw_order = raw_orders.get(int(unit_match.group(1)))
                unit_type = unit_match.group(2)
                order_name = unit_match.group(3)
                if raw_order == 14 and order_name == b"ATTACK":
                    raw_line = raw_line.replace(b" o ATTACK", b" o STILL", 1)
                elif raw_order == 12 \
                        and order_name == b"STAND_GROUND":
                    raw_line = raw_line.replace(
                        b" o STAND_GROUND", b" o ATTACK", 1
                    )
                elif raw_order == 16 and order_name == b"STILL":
                    # Action 16 is the stationary firing substate: its native
                    # records use attack animation four and retain both the
                    # target pointer and target square.  The sealed corpus's
                    # old STILL label hid valid ranged and naval attacks.
                    raw_line = raw_line.replace(
                        b" o STILL", b" o ATTACK", 1
                    )
                elif raw_order == 28 \
                        and order_name == b"UNDER_CONSTRUCTION":
                    raw_line = raw_line.replace(
                        b" o UNDER_CONSTRUCTION", b" o BUILD", 1
                    )
                elif raw_order == 25 and order_name == b"BOARD":
                    # Schema 1.1 froze the then-unidentified action 25 as
                    # BOARD.  Raw-state coverage now proves it is the final
                    # resource-approach substate: all observed owners are
                    # peasants, peons, or oil tankers and retain their live
                    # resource goal.
                    raw_line = raw_line.replace(
                        b" o BOARD", b" o HARVEST", 1
                    )
                elif raw_order == 26 and order_name == b"UNLOAD":
                    # Action 26 is the following hidden-inside-resource
                    # substate, not a transport unloading passengers.
                    raw_line = raw_line.replace(
                        b" o UNLOAD", b" o HARVEST", 1
                    )
                elif raw_order in (3, 59) \
                        and unit_type == b"unit-circle-of-power" \
                        and order_name == b"MOVE":
                    # Three retail campaign circles retain raw action 3 from
                    # scenario setup. The type has no movement capability,
                    # so BNE never executes the order and the circles remain
                    # inert forever. Java's canonical STILL is the same
                    # semantic state; do not turn the stale byte into a fake
                    # movement divergence.
                    raw_line = raw_line.replace(b" o MOVE", b" o STILL", 1)
                elif raw_order == 37 and order_name == b"BUILD":
                    # Action 37 is the post-OP0 production/research work
                    # state for a computer building that already started a
                    # job under action 33. The sealed corpus labeled it
                    # BUILD; Java keeps the hall/barracks/shipyard on Still
                    # while {@code producing}/{@code researching} is set
                    # (Human 13 peon train, XOrc 11 footman train). Coarse
                    # semantic compare treats both as idle-with-job.
                    raw_line = raw_line.replace(b" o BUILD", b" o STILL", 1)
                # The old textual tracer stopped its type-name table at the
                # two wall types (104), but state byte 39 retained the real
                # post-PUD corpse type.  XHuman 12 guard tower 1370 is 107 on
                # fixture 175, exactly when Java becomes its 2x2 destroyed
                # place; Human 5 barracks 1529 likewise proves 108 at 1608.
                # Correct only raw ids with sealed witnesses, leaving every
                # genuinely unknown extension visible as unknown.
                if unit_type == b"unit-unknown":
                    corrected_type = BNE_POST_PUD_TYPE_NAMES.get(
                        raw_types.get(int(unit_match.group(1)))
                    )
                    if corrected_type is not None:
                        raw_line = raw_line.replace(
                            b" unit-unknown ", b" " + corrected_type + b" ", 1
                        )
        output.write(raw_line)

    if current_cycle != expected_cycles:
        raise ValueError(
            f"BNE textual trace ended at cycle {current_cycle}; "
            f"expected {expected_cycles}"
        )


def validate_java_trace_cycles(path: Path, expected_cycles: int) -> None:
    """Require one ordered Java record for every fixture cycle."""
    found = []
    with path.open(encoding="utf-8") as source:
        for line in source:
            match = CYCLE.fullmatch(line.rstrip("\n"))
            if match:
                found.append(int(match.group(1)))
    expected = list(range(1, expected_cycles + 1))
    if found != expected:
        if not found:
            detail = "no cycle records"
        else:
            mismatch = next((index for index, pair in enumerate(zip(found, expected))
                             if pair[0] != pair[1]), None)
            if mismatch is None:
                detail = f"contains {len(found)} cycle records"
            else:
                detail = f"cycle record {mismatch + 1} is {found[mismatch]}"
        raise ValueError(
            f"Java trace {path} is incomplete or non-contiguous: {detail}; "
            f"expected exactly 1..{expected_cycles}"
        )


def compare(fixture: Path, java_trace: Path, differ: Path,
        report_all: bool = False, compare_unit_order: bool = False,
        through: int | None = None, trusted_fixture: bool = False) -> int:
    fixture = fixture.resolve()
    java_trace = java_trace.resolve()
    differ = differ.resolve()
    if trusted_fixture:
        # The corpus runner has already authenticated this archive against its
        # sealed index. Read only the signed-in-practice manifest metadata;
        # replaying the state validator here is duplicate O(cycles * map).
        with zipfile.ZipFile(fixture) as archive:
            manifest = json.loads(archive.read("manifest.json"))
        validation = {
            "cycles": manifest["run"]["state"]["validation"]["cycles"],
        }
    else:
        validation = validate_fixture(fixture)
    if not java_trace.is_file():
        raise ValueError(f"Java trace does not exist: {java_trace}")
    if not differ.is_file():
        raise ValueError(f"determinism differ does not exist: {differ}")
    fixture_cycles = int(validation["cycles"])
    expected_cycles = fixture_cycles if through is None else min(through, fixture_cycles)
    if expected_cycles <= 0:
        raise ValueError("--through must be positive")
    validate_java_trace_cycles(java_trace, expected_cycles)
    with tempfile.NamedTemporaryFile(
            prefix="bne-fixture-trace-", suffix=".txt") as extracted:
        with zipfile.ZipFile(fixture) as archive, \
                archive.open("trace.txt") as trace_source, \
                archive.open("state.bin") as state_source:
            normalize_fixture_trace(
                trace_source, state_source, extracted, expected_cycles
            )
        extracted.flush()
        command = [sys.executable, str(differ)]
        if report_all:
            command.append("--all")
        if not compare_unit_order:
            # The BNE tracer currently emits live unit-pool slots in numeric
            # order. That is a durable identity order, but it has not yet been
            # proven to be BNE's per-tick execution order. The ChonkCraft differ's
            # action-table check must therefore remain opt-in for BNE rather
            # than manufacturing a cycle-one finding from unlike containers.
            command.append("--ignore-action-order")
        command.extend((extracted.name, str(java_trace)))
        return subprocess.run(command, check=False).returncode


def main() -> int:
    default_differ = Path(__file__).resolve().parents[3] / (
        "scripts/diff-determinism.py"
    )
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixture", type=Path)
    parser.add_argument("java_trace", type=Path)
    parser.add_argument("--differ", type=Path, default=default_differ)
    parser.add_argument("--all", action="store_true")
    parser.add_argument("--compare-unit-order", action="store_true",
                        help="treat BNE unit-pool order as action-table order")
    parser.add_argument("--through", type=int,
                        help="compare only the first N fixture cycles")
    parser.add_argument("--trusted-fixture", action="store_true",
                        help="fixture identity was verified by a sealed index")
    args = parser.parse_args()
    try:
        return compare(args.fixture, args.java_trace, args.differ, args.all,
                       args.compare_unit_order, args.through,
                       args.trusted_fixture)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        parser.error(str(error))


if __name__ == "__main__":
    raise SystemExit(main())
