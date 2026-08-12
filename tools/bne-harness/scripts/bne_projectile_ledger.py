#!/usr/bin/env python3
"""Build a native-versus-Java lifecycle ledger for BNE's projectile pool.

Hand comparison of projectile slot creation, free, and reuse is how the
free-cycle ordering work has been done so far, and it is where the ordering
mistakes came from: the eye reads a raw hex dump in ascending slot order and
quietly turns that into a claim about the order the engine did things. This
ledger replaces that with an exact timeline, and refuses to state an ordering
the sealed evidence does not carry.

A port of no single upstream construct: BNE's projectile pool is observed
through the schema 1.1 `AUXL` chunk described in {@code FIXTURE.md}, whose
field offsets are recorded in {@code LAYOUT.md} and
{@code src/bne_202_layout.h}.
"""

from __future__ import annotations

import io
import json
import os
from pathlib import Path
import tempfile
from typing import Any, BinaryIO, Iterable, Sequence
import zipfile

from bne_fixture import (
    AUX_HEADER,
    BULLET_BYTES,
    BULLET_DELTA_HEADER,
    BULLET_FLAGS,
    BULLET_FREE,
    CHUNK_HEADER,
    CYCLE_HEADER,
    MAP_DELTA,
    PLAYER_SIM_RECORD,
    STATE_HEADER,
)

LEDGER_SCHEMA = 1

IMPLEMENTATION = ("bne_projectile_ledger.py", "bne_fixture.py")

# BNE's unit record stride. Both projectile pointer fields below are exact
# multiples of it away from each other in every sealed fixture examined, which
# is what makes them unit pointers rather than two unrelated dwords.
UNIT_STRIDE = 152

# Offsets inside the 64-byte projectile/effect record.
#
# Only the entries marked "verified" below are given a decoded name in the
# ledger. Everything else stays in `undecoded_hex`, because a field that is
# named but never justified is exactly the defect this repository keeps
# rediscovering.
PROJECTILE_X = 0x00           # verified: steps by the type's speed byte
PROJECTILE_Y = 0x02           # verified: steps by the type's speed byte
PROJECTILE_FRAME = 0x09       # verified in LAYOUT.md: visual animation frame
PROJECTILE_FACING = 0x0A      # verified in LAYOUT.md: launch-facing byte
PROJECTILE_STEP_STATE = 0x18  # verified in LAYOUT.md: 8-byte Bresenham state
PROJECTILE_STEP_MAJOR = 0x18
PROJECTILE_STEP_MINOR = 0x1A
PROJECTILE_STEP_ERROR = 0x1C
PROJECTILE_STEP_FLAGS = 0x1F
PROJECTILE_REMAINING = 0x20   # verified in LAYOUT.md: signed remaining distance
PROJECTILE_AIM_X = 0x28       # verified in LAYOUT.md: packed aim
PROJECTILE_AIM_Y = 0x2A
PROJECTILE_SOURCE_PTR = 0x2C  # derived: congruent mod 152 across the corpus
PROJECTILE_TARGET_PTR = 0x30  # derived: congruent mod 152 across the corpus
PROJECTILE_TYPE = 0x34        # derived: correlates with the 0x00494e6c speed table
PROJECTILE_ACTION = 0x36      # derived: constant per motion action

DECODED_OFFSETS = frozenset(
    list(range(PROJECTILE_X, PROJECTILE_X + 4))
    + [PROJECTILE_FRAME, PROJECTILE_FACING]
    + list(range(PROJECTILE_STEP_STATE, PROJECTILE_STEP_STATE + 8))
    + list(range(PROJECTILE_REMAINING, PROJECTILE_REMAINING + 2))
    + list(range(PROJECTILE_AIM_X, PROJECTILE_AIM_X + 4))
    + list(range(PROJECTILE_SOURCE_PTR, PROJECTILE_SOURCE_PTR + 8))
    + [PROJECTILE_TYPE, BULLET_FLAGS, PROJECTILE_ACTION]
)

# The step flags recorded by direction setup at 0x00429f10.
STEP_FLAG_X_MAJOR = 0x80
STEP_FLAG_X_FORWARD = 0x40
STEP_FLAG_Y_FORWARD = 0x20

# Disagreement classes, most specific first. The order matters: the ledger
# reports the first class whose evidence is present, so that "we cannot tell"
# never outranks a disagreement the evidence actually pins down.
CLASSES = (
    "allocation-order",
    "free-timing",
    "slot-reuse",
    "iteration-order",
    "rng-consumer-order",
    "unknown-correspondence",
)


def _u16(raw: bytes | bytearray, offset: int) -> int:
    return int.from_bytes(raw[offset:offset + 2], "little")


def _s16(raw: bytes | bytearray, offset: int) -> int:
    return int.from_bytes(raw[offset:offset + 2], "little", signed=True)


def _u32(raw: bytes | bytearray, offset: int) -> int:
    return int.from_bytes(raw[offset:offset + 4], "little")


def _read_exact(source: BinaryIO, size: int, description: str) -> bytes:
    data = source.read(size)
    if len(data) != size:
        raise ValueError(f"truncated BNE state stream while reading {description}")
    return data


def decode_projectile(raw: bytes | bytearray) -> dict[str, Any]:
    """Decode the fields of one 64-byte record that the corpus justifies.

    Fields whose offsets are recorded in {@code LAYOUT.md} are named. The two
    unit pointers and the type/action bytes are named as *derived* values and
    carry their own provenance, so a reader can tell a measured field from an
    inferred one. Every byte that is not decoded is preserved verbatim in
    `undecoded_hex` rather than dropped.
    """
    if len(raw) != BULLET_BYTES:
        raise ValueError(f"projectile record is {len(raw)} bytes, expected {BULLET_BYTES}")
    flags = raw[BULLET_FLAGS]
    free = (flags & BULLET_FREE) != 0
    step_flags = raw[PROJECTILE_STEP_FLAGS]
    undecoded = bytes(
        value for offset, value in enumerate(raw) if offset not in DECODED_OFFSETS
    )
    return {
        "free": free,
        "flags": flags,
        "type": raw[PROJECTILE_TYPE],
        "action": raw[PROJECTILE_ACTION],
        "frame": raw[PROJECTILE_FRAME],
        "facing": raw[PROJECTILE_FACING],
        "x": _u16(raw, PROJECTILE_X),
        "y": _u16(raw, PROJECTILE_Y),
        "aim_x": _u16(raw, PROJECTILE_AIM_X),
        "aim_y": _u16(raw, PROJECTILE_AIM_Y),
        "remaining": _s16(raw, PROJECTILE_REMAINING),
        "step": {
            "major": _u16(raw, PROJECTILE_STEP_MAJOR),
            "minor": _u16(raw, PROJECTILE_STEP_MINOR),
            "error": _s16(raw, PROJECTILE_STEP_ERROR),
            "flags": step_flags,
            "x_is_major": bool(step_flags & STEP_FLAG_X_MAJOR),
            "x_forward": bool(step_flags & STEP_FLAG_X_FORWARD),
            "y_forward": bool(step_flags & STEP_FLAG_Y_FORWARD),
        },
        "source_pointer": _u32(raw, PROJECTILE_SOURCE_PTR),
        "target_pointer": _u32(raw, PROJECTILE_TARGET_PTR),
        "undecoded_hex": undecoded.hex(),
        "raw_hex": bytes(raw).hex(),
    }


def unit_pointer_correspondence(pointers: Iterable[int]) -> dict[str, Any]:
    """Describe how far a pointer can honestly be turned into a unit slot.

    BNE stores raw addresses, not slot numbers, and the sealed fixture never
    records where the unit pool starts. Sharing one residue modulo the
    152-byte unit stride proves these dwords index the unit pool; it does not
    reveal the base, because the lowest-addressed unit in the window need not
    be slot 0. So this reports the proven part -- the stride relationship and
    each pointer's index relative to the lowest one seen -- and refuses to name
    an absolute slot, which would be a fabricated mapping.
    """
    seen = sorted({value for value in pointers if value})
    if not seen:
        return {
            "state": "no-pointers",
            "stride": UNIT_STRIDE,
            "absolute_slots_known": False,
        }
    residues = {value % UNIT_STRIDE for value in seen}
    if len(residues) != 1:
        return {
            "state": "not-unit-pointers",
            "stride": UNIT_STRIDE,
            "absolute_slots_known": False,
            "distinct_residues": len(residues),
            "why": (
                "the observed dwords do not share one residue modulo the unit "
                "stride, so they are not all unit-pool pointers"
            ),
        }
    lowest = seen[0]
    return {
        "state": "unit-pointers-relative-only",
        "stride": UNIT_STRIDE,
        "absolute_slots_known": False,
        "distinct_pointers": len(seen),
        "lowest_pointer": lowest,
        "relative_index_span": (seen[-1] - lowest) // UNIT_STRIDE,
        "why": (
            "every pointer is a whole number of unit records from the lowest "
            "one observed, which proves they index the unit pool. The pool "
            "base is not in the fixture, so only relative unit indices are "
            "reported and no absolute slot is claimed"
        ),
    }


def relative_unit_index(pointer: int, correspondence: dict[str, Any]) \
        -> int | None:
    """Return a pointer's unit index relative to the lowest pointer observed."""
    if not pointer or correspondence.get("state") != "unit-pointers-relative-only":
        return None
    return (pointer - correspondence["lowest_pointer"]) // UNIT_STRIDE


class Lifetime:
    """One occupancy of one native pool slot, from birth to reuse."""

    def __init__(self, slot: int, generation: int, cycle: int,
                 decoded: dict[str, Any]) -> None:
        self.slot = slot
        self.generation = generation
        self.creation_cycle = cycle
        self.final_occupied_cycle = cycle
        self.free_cycle: int | None = None
        self.reuse_cycle: int | None = None
        self.birth = decoded
        self.last = decoded
        self.allocation_ordinal: int | None = None
        self.creation_ordinal: int | None = None

    def as_dict(self) -> dict[str, Any]:
        return {
            "slot": self.slot,
            "generation": self.generation,
            "creation_cycle": self.creation_cycle,
            "final_occupied_cycle": self.final_occupied_cycle,
            "free_cycle": self.free_cycle,
            "reuse_cycle": self.reuse_cycle,
            "allocation_ordinal": self.allocation_ordinal,
            "creation_ordinal": self.creation_ordinal,
            "type": self.birth["type"],
            "action": self.birth["action"],
            "birth": self.birth,
            "final": self.last,
        }


def read_native_pool(state_source: BinaryIO, *, through: int) \
        -> dict[str, Any]:
    """Walk one authenticated BNESTATE stream and rebuild the projectile pool.

    This reuses the sealed schema constants rather than re-deriving the layout,
    so a fixture the fixture validator would reject cannot reach the ledger
    through a second, looser parser.
    """
    (magic, major, minor, header_bytes, unit_bytes, unit_limit,
     player_count, flags) = STATE_HEADER.unpack(
        _read_exact(state_source, STATE_HEADER.size, "file header"))
    if magic != b"BNESTATE":
        raise ValueError(f"unsupported BNE state magic {magic!r}")
    if major != 1 or minor != 1:
        raise ValueError(
            f"projectile ledger needs BNE state schema 1.1; got {major}.{minor}")

    live = [False] * 400
    generations = [0] * 400
    open_lifetime: list[Lifetime | None] = [None] * 400
    lifetimes: list[Lifetime] = []
    cycles: list[dict[str, Any]] = []
    pool_count: int | None = None
    pending: tuple[int, int] | None = None
    creation_ordinal = 0
    pointers: list[int] = []
    last_cycle = 0

    while True:
        chunk = state_source.read(CHUNK_HEADER.size)
        if not chunk:
            break
        if len(chunk) != CHUNK_HEADER.size:
            raise ValueError("truncated BNE state chunk header")
        tag, payload_bytes = CHUNK_HEADER.unpack(chunk)
        payload = _read_exact(state_source, payload_bytes, f"{tag!r} payload")
        if tag == b"CYCL":
            cursor = io.BytesIO(payload)
            cycle, seed, _pool, _changed = CYCLE_HEADER.unpack(
                _read_exact(cursor, CYCLE_HEADER.size, "cycle header"))
            pending = (cycle, seed)
        elif tag == b"AUXL":
            if pending is None:
                raise ValueError("BNE state AUXL chunk has no preceding cycle")
            cursor = io.BytesIO(payload)
            (aux_cycle, bullet_count, changed, _map_size,
             changed_tiles) = AUX_HEADER.unpack(
                _read_exact(cursor, AUX_HEADER.size, "AUXL header"))
            if aux_cycle != pending[0]:
                raise ValueError(
                    f"BNE state AUXL cycle {aux_cycle}; expected {pending[0]}")
            for _ in range(player_count):
                _read_exact(cursor, PLAYER_SIM_RECORD.size, "player record")
            pool_count = bullet_count
            births: list[dict[str, Any]] = []
            frees: list[dict[str, Any]] = []
            movements: list[dict[str, Any]] = []
            for _ in range(changed):
                slot, generation = BULLET_DELTA_HEADER.unpack(
                    _read_exact(cursor, BULLET_DELTA_HEADER.size,
                                "projectile delta header"))
                if slot >= 400:
                    raise ValueError(
                        f"BNE state AUXL cycle {aux_cycle} names slot {slot}")
                raw = _read_exact(cursor, BULLET_BYTES, "projectile delta")
                decoded = decode_projectile(raw)
                was_live = live[slot]
                now_live = not decoded["free"]
                for key in ("source_pointer", "target_pointer"):
                    if decoded[key]:
                        pointers.append(decoded[key])
                if now_live and not was_live:
                    previous = open_lifetime[slot]
                    if previous is not None and previous.reuse_cycle is None:
                        previous.reuse_cycle = aux_cycle
                    lifetime = Lifetime(slot, generation, aux_cycle, decoded)
                    lifetime.allocation_ordinal = len(births)
                    lifetime.creation_ordinal = creation_ordinal
                    creation_ordinal += 1
                    lifetimes.append(lifetime)
                    open_lifetime[slot] = lifetime
                    births.append({
                        "slot": slot,
                        "generation": generation,
                        "allocation_ordinal": lifetime.allocation_ordinal,
                        "creation_ordinal": lifetime.creation_ordinal,
                        "type": decoded["type"],
                        "action": decoded["action"],
                        "remaining": decoded["remaining"],
                        "x": decoded["x"],
                        "y": decoded["y"],
                        "aim_x": decoded["aim_x"],
                        "aim_y": decoded["aim_y"],
                        "source_pointer": decoded["source_pointer"],
                        "target_pointer": decoded["target_pointer"],
                    })
                elif was_live and not now_live:
                    lifetime = open_lifetime[slot]
                    if lifetime is not None:
                        lifetime.free_cycle = aux_cycle
                    frees.append({
                        "slot": slot,
                        "generation": generation,
                        "type": decoded["type"],
                        "remaining": decoded["remaining"],
                        "creation_cycle":
                            lifetime.creation_cycle if lifetime else None,
                    })
                elif now_live:
                    lifetime = open_lifetime[slot]
                    if lifetime is not None:
                        lifetime.final_occupied_cycle = aux_cycle
                        lifetime.last = decoded
                    movements.append({
                        "slot": slot,
                        "type": decoded["type"],
                        "action": decoded["action"],
                        "frame": decoded["frame"],
                        "flags": decoded["flags"],
                        "remaining": decoded["remaining"],
                        "x": decoded["x"],
                        "y": decoded["y"],
                    })
                live[slot] = now_live
                generations[slot] = generation
            # The map deltas trail the projectile deltas in the same chunk. The
            # ledger does not read them, but it must still consume them, or the
            # trailing-data check below cannot tell a short read from a corrupt
            # capture.
            for _ in range(changed_tiles):
                _read_exact(cursor, MAP_DELTA.size, "map delta")
            if cursor.read(1):
                raise ValueError(
                    f"BNE state AUXL cycle {aux_cycle} has trailing payload data")
            free_slots = [s for s in range(bullet_count) if not live[s]]
            cycles.append({
                "cycle": aux_cycle,
                "sync_seed": pending[1],
                "pool_count": bullet_count,
                "changed_records": changed,
                "live_after": sum(1 for s in range(bullet_count) if live[s]),
                "lowest_free_slot": free_slots[0] if free_slots else None,
                "births": births,
                "frees": frees,
                "movements": movements,
            })
            last_cycle = aux_cycle
            pending = None
            if aux_cycle >= through:
                break
        elif tag == b"DONE":
            break
        else:
            raise ValueError(f"unexpected BNE state chunk {tag!r}")

    return {
        "pool_count": pool_count,
        "pool_count_evidence": {
            "value": pool_count,
            "measures": (
                "the runtime value of BNE's projectile-pool count global at "
                "0x004ae268, as the tracer read it when it took the snapshot"
            ),
            "allocator_scan_bound_proved": False,
            "why": (
                "the capture records what that global held and how many slot "
                "records the tracer therefore wrote. It does not by itself "
                "show what bound the allocator scans to. LAYOUT.md records, "
                "from a static reading of the pinned executable, that the "
                "constructor at 0x00410000 reads its count from the same "
                "global -- but that is a separate claim from a separate "
                "source, and this ledger does not verify it"
            ),
        },
        "cycles": cycles,
        "lifetimes": [lifetime.as_dict() for lifetime in lifetimes],
        "observed_cycles": last_cycle,
        "unit_pointer_correspondence": unit_pointer_correspondence(pointers),
        "record_order": {
            "meaning": "ascending pool scan",
            "carries_execution_order": False,
            "why": (
                "the tracer walks slot 0..pool_count and emits every changed "
                "record in that order, so record order is scan order and says "
                "nothing about the order BNE updated the slots"
            ),
        },
    }


def allocation_findings(native: dict[str, Any]) -> list[dict[str, Any]]:
    """Report cycles that ended with a low slot free and a new shot placed high.

    What the fixture holds is one snapshot per cycle, so what these findings
    record is a *state* at the cycle boundary: a slot that was occupied is now
    free, and a projectile created during that cycle sits above it.

    That is strong evidence that the allocation ran before the free, because a
    scan for the first free slot would otherwise have taken the lower one --
    but it is not a proof, and the finding says so rather than rounding up.
    Two things end-of-cycle snapshots cannot rule out. The freed slot may have
    been allocated and freed again within the same cycle, which leaves no trace
    at all: the tracer counts a generation on a non-live to live transition
    *between* snapshots, so a whole lifetime opening and closing inside one
    cycle is invisible to it. And the allocator may not scan from slot 0, in
    which case the lower slot was never in the running.

    Settling it needs native call or event evidence ordering the constructor
    against the free within the cycle. None is available locally, so the
    conservative reading is what gets reported.
    """
    findings: list[dict[str, Any]] = []
    for record in native["cycles"]:
        if not record["births"]:
            continue
        freed_here = {entry["slot"] for entry in record["frees"]}
        taken = [entry["slot"] for entry in record["births"]]
        for entry in record["births"]:
            skipped = [
                slot for slot in freed_here
                if slot < entry["slot"] and slot not in taken
            ]
            if skipped:
                findings.append({
                    "cycle": record["cycle"],
                    "slot": entry["slot"],
                    "skipped_slots_freed_this_cycle": sorted(skipped),
                    "observation": (
                        "at the end of this cycle a lower slot that had been "
                        "occupied was free, and a projectile created this "
                        "cycle was in a higher slot"
                    ),
                    "conclusion": (
                        "strong evidence consistent with allocation occurring "
                        "before the observed free, assuming no hidden "
                        "same-cycle lifetime"
                    ),
                    "not_excluded": [
                        "the freed slot was reallocated and freed again inside "
                        "this cycle, which end-of-cycle snapshots cannot see "
                        "and which leaves no generation bump, because a "
                        "generation is only counted across a snapshot boundary",
                        "the allocator does not scan from slot 0, so the "
                        "lower free slot was never a candidate",
                    ],
                })
    return findings


def reuse_findings(native: dict[str, Any]) -> list[dict[str, Any]]:
    """Report every slot that carried more than one lifetime."""
    findings: list[dict[str, Any]] = []
    for lifetime in native["lifetimes"]:
        if lifetime["reuse_cycle"] is None:
            continue
        gap = None
        if lifetime["free_cycle"] is not None:
            gap = lifetime["reuse_cycle"] - lifetime["free_cycle"]
        findings.append({
            "slot": lifetime["slot"],
            "generation": lifetime["generation"],
            "creation_cycle": lifetime["creation_cycle"],
            "free_cycle": lifetime["free_cycle"],
            "reuse_cycle": lifetime["reuse_cycle"],
            "idle_cycles": gap,
        })
    return findings


def build_ledger(native: dict[str, Any], java: dict[str, Any] | None, *,
                 case: str | None, through: int,
                 native_evidence: dict[str, Any] | None = None,
                 java_evidence: dict[str, Any] | None = None) \
        -> dict[str, Any]:
    """Compare the native pool timeline against whatever Java evidence exists."""
    allocations = allocation_findings(native)
    reuses = reuse_findings(native)
    java_lifecycle = (java or {}).get("lifecycle") or []

    gaps: list[dict[str, Any]] = []
    if not java_lifecycle:
        gaps.append({
            "gap": "java-projectile-lifecycle",
            "detail": (
                "no Java evidence names a missile creation, free, or slot. The "
                "engine emits JBNEPEND for the pending-attack flush only, and "
                "bne_causal.py has no Java kind for a projectile at all, so "
                "there is nothing to pair a native slot against."
            ),
            "instrumentation_plan": [
                "emit one opt-in JBNEMISSILE line per missile create and free, "
                "carrying cycle, creation ordinal, type, source and target unit "
                "ids, and remaining distance",
                "the natural emit sites are Missile and "
                "BattleNetProjectileSystem, which are owned by another worktree "
                "right now, so this ledger does not add them",
                "register JBNEMISSILE in bne_causal.py JAVA_KIND as "
                "projectile.create / projectile.free so the existing causal "
                "reader pairs it with the native projectile-created kind",
            ],
            "blocked_on_files": [
                "engine/src/main/java/net/chonkbase/chonkcraft/engine/missile/Missile.java",
                "engine/src/main/java/net/chonkbase/chonkcraft/engine/"
                "BattleNetProjectileSystem.java",
            ],
        })

    first: dict[str, Any] | None = None
    if java_lifecycle:
        first = _first_lifecycle_disagreement(native, java_lifecycle)
        classification = first["class"] if first else "agreed"
    else:
        classification = "unknown-correspondence"

    return {
        "schema": LEDGER_SCHEMA,
        "case": case,
        "through_cycle": through,
        "classification": classification,
        "native": {
            "pool_count": native["pool_count"],
            "pool_count_evidence": native["pool_count_evidence"],
            "observed_cycles": native["observed_cycles"],
            "unit_pointer_correspondence":
                native["unit_pointer_correspondence"],
            "record_order": native["record_order"],
            "lifetime_count": len(native["lifetimes"]),
            "lifetimes": native["lifetimes"],
            "cycles": native["cycles"],
        },
        "java": java or {"lifecycle": [], "source": None},
        "allocation_findings": allocations,
        "reuse_findings": reuses,
        "first_disagreement": first,
        "evidence_gaps": gaps,
        "evidence": {
            "native": native_evidence,
            "java": java_evidence,
        },
    }


def _first_lifecycle_disagreement(native: dict[str, Any],
                                  java_lifecycle: Sequence[dict[str, Any]]) \
        -> dict[str, Any] | None:
    """Return the earliest cycle where the two lifecycles disagree.

    Java has no proved native slot correspondence, so the comparison is by
    creation ordinal within a cycle, never by slot. A slot number is only ever
    reported on the native side.
    """
    java_by_cycle: dict[int, list[dict[str, Any]]] = {}
    for entry in java_lifecycle:
        java_by_cycle.setdefault(int(entry["cycle"]), []).append(entry)

    first_cycle = min(record["cycle"] for record in native["cycles"])
    initial_lifetimes = {
        (life["slot"], life["generation"])
        for life in native["lifetimes"]
        if life["creation_cycle"] == first_cycle
    }
    native_births = {
        record["cycle"]: ([] if record["cycle"] == first_cycle
                          else record["births"])
        for record in native["cycles"]
    }
    native_frees = {
        record["cycle"]: [free for free in record["frees"]
                          if (free["slot"], free["generation"])
                          not in initial_lifetimes]
        for record in native["cycles"]
    }
    cycles = sorted(set(native_births) | set(java_by_cycle))
    for cycle in cycles:
        births = native_births.get(cycle, [])
        frees = native_frees.get(cycle, [])
        java_here = java_by_cycle.get(cycle, [])
        java_creates = [e for e in java_here if e.get("event") == "create"]
        java_frees = [e for e in java_here if e.get("event") == "free"]

        if len(java_creates) != len(births):
            return {
                "class": "allocation-order",
                "cycle": cycle,
                "native_creations": len(births),
                "java_creations": len(java_creates),
                "detail": (
                    f"cycle {cycle} creates {len(births)} projectiles natively "
                    f"and {len(java_creates)} in Java"
                ),
            }
        for index, (native_birth, java_create) in enumerate(
                zip(births, java_creates)):
            java_type = java_create.get("type")
            if isinstance(java_type, int) and java_type >= 0 \
                    and native_birth["type"] != java_type:
                return {
                    "class": "allocation-order",
                    "cycle": cycle,
                    "creation_ordinal": index,
                    "native_slot": native_birth["slot"],
                    "native_type": native_birth["type"],
                    "java_type": java_create.get("type"),
                    "detail": (
                        f"cycle {cycle} creation {index} is type "
                        f"{native_birth['type']} natively and "
                        f"{java_create.get('type')} in Java"
                    ),
                }
        if len(java_frees) != len(frees):
            return {
                "class": "free-timing",
                "cycle": cycle,
                "native_frees": len(frees),
                "java_frees": len(java_frees),
                "detail": (
                    f"cycle {cycle} frees {len(frees)} projectiles natively "
                    f"and {len(java_frees)} in Java"
                ),
            }
    return None


def format_ledger(report: dict[str, Any]) -> str:
    """Render the ledger as the Markdown a person actually reads."""
    native = report["native"]
    lines = [
        "# BNE projectile pool ledger",
        "",
        f"- Case: `{report.get('case') or 'unnamed'}`",
        f"- Cycles: 1 through {report['through_cycle']} "
        f"(observed {native['observed_cycles']})",
        f"- Captured pool-count global: {native['pool_count']} "
        f"(runtime value at 0x004ae268; not a measurement of how far the "
        f"allocator scans)",
        f"- Native lifetimes: {native['lifetime_count']}",
        f"- Classification: **{report['classification']}**",
        "",
        "## What the record order does and does not prove",
        "",
        native["record_order"]["why"] + ".",
        "",
    ]

    correspondence = native.get("unit_pointer_correspondence") or {}
    lines += [
        "## Source and target identity",
        "",
        correspondence.get(
            "why", "No source or target pointers were observed.") + ".",
        "",
    ]

    lines += [
        "## Lifetimes",
        "",
        "| Slot | Gen | Type | Created | Last occupied | Freed | Reused |",
        "| ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for lifetime in native["lifetimes"]:
        lines.append(
            f"| {lifetime['slot']} | {lifetime['generation']} | "
            f"{lifetime['type']} | {lifetime['creation_cycle']} | "
            f"{lifetime['final_occupied_cycle']} | "
            f"{lifetime['free_cycle'] if lifetime['free_cycle'] is not None else '--'} | "
            f"{lifetime['reuse_cycle'] if lifetime['reuse_cycle'] is not None else '--'} |"
        )
    lines.append("")

    if report["allocation_findings"]:
        lines += [
            "## Cycles ending with a low slot free and a new shot placed above it",
            "",
            "| Cycle | Slot taken | Lower slots that ended the cycle free |",
            "| ---: | ---: | --- |",
        ]
        for finding in report["allocation_findings"]:
            skipped = ", ".join(
                str(slot) for slot in finding["skipped_slots_freed_this_cycle"])
            lines.append(
                f"| {finding['cycle']} | {finding['slot']} | {skipped} |")
        lines += [
            "",
            "These rows are the observation, not the conclusion. Each says "
            "that at the cycle boundary a previously occupied slot was free "
            "and a projectile created during that cycle sat above it.",
            "",
            "Read conservatively, that is **strong evidence consistent with "
            "allocation occurring before the observed free, assuming no hidden "
            "same-cycle lifetime**. It does not on its own establish the "
            "ordering, because one snapshot per cycle cannot see a slot that "
            "was freed, reallocated and freed again inside the same cycle -- "
            "such a lifetime never crosses a snapshot boundary and so never "
            "increments a generation. It also assumes the allocator scans from "
            "slot 0; if it does not, the lower slot was never a candidate.",
            "",
            "Settling it needs native call or event evidence ordering the "
            "constructor against the free within a cycle.",
            "",
        ]
    else:
        lines += [
            "## Allocation order",
            "",
            "No cycle in this window ended with a previously occupied lower "
            "slot free while a projectile created that cycle sat above it. "
            "That is consistent with a lowest-free-slot rule, but a run of "
            "cycles agreeing with a rule does not establish it.",
            "",
        ]

    if report["reuse_findings"]:
        lines += [
            "## Slot reuse",
            "",
            "| Slot | Gen | Created | Freed | Reused | Idle cycles |",
            "| ---: | ---: | ---: | ---: | ---: | ---: |",
        ]
        for finding in report["reuse_findings"]:
            lines.append(
                f"| {finding['slot']} | {finding['generation']} | "
                f"{finding['creation_cycle']} | {finding['free_cycle']} | "
                f"{finding['reuse_cycle']} | {finding['idle_cycles']} |")
        lines.append("")

    first = report.get("first_disagreement")
    lines += ["## First lifecycle disagreement", ""]
    if first is None and report["classification"] == "agreed":
        lines.append("None in this window.")
    elif first is None:
        lines.append(
            "Not determined: there is no Java lifecycle evidence to compare "
            "against. See the evidence gaps below.")
    else:
        lines.append(f"**{first['class']}** -- {first['detail']}")
    lines.append("")

    if report["evidence_gaps"]:
        lines += ["## Evidence gaps", ""]
        for gap in report["evidence_gaps"]:
            lines += [f"### {gap['gap']}", "", gap["detail"], ""]
            if gap.get("instrumentation_plan"):
                lines.append("Minimal instrumentation that would close it:")
                lines.append("")
                for step in gap["instrumentation_plan"]:
                    lines.append(f"- {step}")
                lines.append("")
            if gap.get("blocked_on_files"):
                lines.append("Blocked on files owned elsewhere:")
                lines.append("")
                for path in gap["blocked_on_files"]:
                    lines.append(f"- `{path}`")
                lines.append("")
    return "\n".join(lines) + "\n"


def format_next(report: dict[str, Any]) -> str:
    """Name the first unresolved lifecycle mismatch, or say why there is none."""
    first = report.get("first_disagreement")
    case = report.get("case") or "unnamed"
    lines = ["# Next projectile lifecycle question", "", f"- Case: `{case}`", ""]
    if first is not None:
        lines += [
            f"First unresolved mismatch is **{first['class']}** at cycle "
            f"{first['cycle']}.",
            "",
            first["detail"] + ".",
        ]
    elif report["classification"] == "unknown-correspondence":
        lines += [
            "No native-versus-Java mismatch can be named yet, because Java "
            "emits no projectile lifecycle evidence.",
            "",
            "The native timeline in `PROJECTILE-LEDGER.md` is complete for "
            "this window. What is missing is the Java side, and the smallest "
            "change that would supply it is listed under evidence gaps.",
        ]
    else:
        lines.append("Native and Java lifecycles agree across this window.")
    lines.append("")
    return "\n".join(lines)


def exit_code(report: dict[str, Any]) -> int:
    """0 when the lifecycles agree, 1 on a named mismatch, 2 when unprovable."""
    if report["classification"] == "agreed":
        return 0
    if report["classification"] == "unknown-correspondence":
        return 2
    return 1


def _write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(
                mode="w", encoding="utf-8", prefix=path.name + ".",
                suffix=".tmp", dir=path.parent, delete=False) as handle:
            temporary = Path(handle.name)
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def load_java_lifecycle(path: Path) -> dict[str, Any]:
    """Read Java projectile lifecycle evidence, if a run ever produces any.

    The format is deliberately the causal trace's own vocabulary rather than a
    new one: a `projectile.create` or `projectile.free` line carrying a cycle.
    Structured causal JSONL is preferred. The older diagnostic-line parser is
    retained so evidence captured before the recorder shipped remains usable.
    """
    from bne_causal import parse_causal_jsonl, parse_java_trace

    text = path.read_text(encoding="utf-8", errors="replace")
    stripped = text.lstrip()
    events = (parse_causal_jsonl(text, expected_side="java")
              if stripped.startswith("{") else parse_java_trace(text))
    lifecycle: list[dict[str, Any]] = []
    for event in events:
        if event.kind not in ("projectile.create", "projectile.free"):
            continue
        cycle = event.fields.get("fixture_cycle", event.cycle)
        if not isinstance(cycle, int):
            continue
        lifecycle.append({
            "cycle": cycle,
            "event": "create" if event.kind.endswith("create") else "free",
            "ordinal": event.fields.get("creation_ordinal", event.ordinal),
            "type": event.fields.get("type"),
            "type_ident": event.fields.get("type_ident"),
            "source": event.fields.get("source"),
            "target": event.fields.get("target"),
            "pool_slot": event.fields.get("pool_slot"),
            "remaining": event.fields.get("remaining"),
        })
    return {"lifecycle": lifecycle, "source": str(path)}


def open_state(fixture: Path) -> bytes:
    """Return the raw state stream from a sealed fixture bundle."""
    with zipfile.ZipFile(fixture) as archive:
        return archive.read("state.bin")


def run_projectile_ledger(fixture: Path, artifact_root: Path, *,
                          through: int, case: str | None = None,
                          java_causal: Path | None = None,
                          survey: Path | None = None) -> tuple[int, Path]:
    """Produce a durable, content-addressed projectile ledger for one case.

    The fixture is authenticated the same way the forensic packet authenticates
    it -- through the survey and corpus index when one is supplied -- so a
    bundle whose bytes changed since it was sealed cannot quietly become
    evidence here.
    """
    from bne_triage import canonical_digest, file_identity, inventory_files

    fixture = fixture.expanduser().resolve()
    if not fixture.is_file():
        raise ValueError(f"missing fixture bundle: {fixture}")
    native_evidence: dict[str, Any] = {
        "path": str(fixture), **file_identity(fixture),
    }
    if survey is not None:
        native_evidence["survey"] = _authenticate_against_survey(
            survey, fixture, case)

    java_evidence = None
    java = None
    if java_causal is not None:
        java_causal = java_causal.expanduser().resolve()
        if not java_causal.is_file():
            raise ValueError(f"missing Java causal trace: {java_causal}")
        java_evidence = {"path": str(java_causal), **file_identity(java_causal)}
        java = load_java_lifecycle(java_causal)

    request = {
        "schema": LEDGER_SCHEMA,
        "implementation": {
            name: file_identity(Path(__file__).with_name(name))
            for name in IMPLEMENTATION
        },
        "case": case,
        "through": through,
        "native": native_evidence,
        "java": java_evidence,
    }
    request_sha256 = canonical_digest(request)
    artifact_root = artifact_root.expanduser().resolve()
    run_root = artifact_root / "runs" / request_sha256
    manifest_path = run_root / "manifest.json"
    if manifest_path.is_file():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if manifest.get("request_sha256") != request_sha256 \
                or canonical_digest(manifest.get("request")) != request_sha256:
            raise ValueError("cached projectile ledger request identity changed")
        for relative, expected in manifest["artifacts"].items():
            path = run_root / relative
            if not path.is_file() or file_identity(path) != expected:
                raise ValueError(f"projectile ledger artifact changed: {path}")
        _write(artifact_root / "latest.json",
               json.dumps(manifest["pointer"], indent=2, sort_keys=True) + "\n")
        return int(manifest["exit_code"]), run_root

    native = read_native_pool(io.BytesIO(open_state(fixture)), through=through)
    report = build_ledger(
        native, java, case=case, through=through,
        native_evidence=native_evidence, java_evidence=java_evidence)
    status = exit_code(report)

    run_root.mkdir(parents=True, exist_ok=True)
    report_path = run_root / "PROJECTILE-LEDGER.json"
    summary_path = run_root / "PROJECTILE-LEDGER.md"
    next_path = run_root / "NEXT.md"
    _write(report_path, json.dumps(report, indent=2, sort_keys=True) + "\n")
    _write(summary_path, format_ledger(report))
    _write(next_path, format_next(report))
    pointer = {
        "schema": LEDGER_SCHEMA,
        "request_sha256": request_sha256,
        "run": str(run_root.relative_to(artifact_root)),
        "case": case,
        "through": through,
        "classification": report["classification"],
        "exit_code": status,
    }
    manifest = {
        "schema": LEDGER_SCHEMA,
        "request_sha256": request_sha256,
        "request": request,
        "exit_code": status,
        "pointer": pointer,
        "artifacts": inventory_files(
            run_root, [report_path, summary_path, next_path]),
    }
    _write(manifest_path, json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    _write(artifact_root / "latest.json",
           json.dumps(pointer, indent=2, sort_keys=True) + "\n")
    return status, run_root


def _authenticate_against_survey(survey_path: Path, fixture: Path,
                                 case: str | None) -> dict[str, Any]:
    """Check the fixture still matches the identity the corpus index recorded."""
    from bne_triage import file_identity

    survey_path = survey_path.expanduser().resolve()
    survey = json.loads(survey_path.read_text(encoding="utf-8"))
    if survey.get("schema") != 1 or not isinstance(survey.get("cases"), list):
        raise ValueError(f"invalid BNE Java survey: {survey_path}")
    if case is None:
        raise ValueError("a survey needs a case name to authenticate against")
    case_record = next(
        (record for record in survey["cases"] if record.get("id") == case), None)
    if case_record is None:
        raise ValueError(f"survey has no case {case!r}")
    index_path = Path(survey["index"]).expanduser().resolve()
    index = json.loads(index_path.read_text(encoding="utf-8"))
    indexed = next((record for record in index.get("cases", [])
                    if record.get("id") == case), None)
    if indexed is None:
        raise ValueError(f"case {case!r} is absent from {index_path}")
    relative = Path(indexed["fixture"]["path"])
    resolved = (index_path.parent / relative).resolve()
    if not resolved.is_relative_to(index_path.parent):
        raise ValueError(f"unsafe fixture path for {case!r}")
    if resolved != fixture:
        raise ValueError(
            f"case {case!r} is indexed at {resolved}, not {fixture}")
    actual = file_identity(fixture)
    expected = {key: indexed["fixture"][key] for key in ("bytes", "sha256")}
    if actual != expected:
        raise ValueError(f"indexed fixture identity changed: {fixture}")
    if indexed.get("fixture_id") != case_record.get("fixture_id"):
        raise ValueError(
            "survey and corpus index name different fixture identities")
    return {
        "survey": str(survey_path),
        "index": str(index_path),
        "fixture_id": indexed.get("fixture_id"),
    }
