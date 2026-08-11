#!/usr/bin/env python3
"""Recover multi-cycle native state transitions around a causal divergence.

Some divergences are not a wrong number in one cycle. They are a small state
machine running inside the native unit record over several cycles: something
settles, an unnamed byte climbs one per cycle, the early values change nothing,
and at some value the route is thrown away and a timer is armed -- and the unit
replans somewhere else entirely a dozen cycles later. Read one cycle at a time
that is invisible. Read the whole window at once and it is obvious.

Finding it by hand means diffing 152 bytes across a dozen cycles and guessing
which of the changing offsets is the cause. This module does that mechanically:
it reconstructs the record at every cycle in the window, describes every
changing observable as a trajectory, classifies the trajectories by shape --
counters, countdowns, resets, saturation, periods, armed timers, toggles,
pointers, enums -- and reports which one changed just before the consequence.

It names nothing it has not been told. An offset stays an offset, a shape stays
a shape, and the four confidence grades below keep a timing coincidence from
being written down as a fact.
"""

from __future__ import annotations

from dataclasses import dataclass
import json
import os
from pathlib import Path
import re
import tempfile
from typing import Any, Iterable, Sequence


STATE_MACHINE_SCHEMA = 1

#: How wide a plain byte value can be before a wider read is worth reporting.
BYTE_MAX = 0xff

#: Native code and data addresses in the pinned 2.02b image. Used only to say
#: that a four-byte value *looks* like a pointer, never to dereference one.
POINTER_LOW = 0x00400000
POINTER_HIGH = 0x00800000

#: The fields the ordinary parity comparison already reports every cycle. A
#: unit walking east has a position that climbs by one for six cycles, which
#: is a ramp and is not a hidden state machine. This tool exists for the state
#: the comparison cannot see, so the signals that decide whether to run it at
#: all ignore the state it can.
VISIBLE_FIELDS = frozenset({"x", "y", "hp", "pixel_x", "pixel_y"})

CONFIDENCE_GRADES = (
    "proved",
    "strong-temporal-correlation",
    "speculative-candidate",
    "no-java-counterpart",
)


@dataclass(frozen=True)
class Sample:
    """One unit record as it stood at the end of one cycle."""

    cycle: int
    raw: bytes
    #: Whether the engine wrote this record during the cycle. Record-level,
    #: not offset-level: the fixture records which slots were rewritten, not
    #: which bytes, so a value that stayed the same in a rewritten record is
    #: reported as persisting rather than as untouched.
    written: bool | None = None
    generation: int | None = None


@dataclass(frozen=True)
class Trajectory:
    """One observable's values across the window, with no name attached."""

    key: str
    kind: str
    offset: int
    width: int
    field: str | None
    bit: int | None
    cycles: tuple[int, ...]
    values: tuple[int, ...]

    def as_dict(self) -> dict[str, Any]:
        return {
            "key": self.key, "kind": self.kind, "offset": self.offset,
            "offset_hex": f"0x{self.offset:02x}", "width": self.width,
            "field": self.field, "bit": self.bit,
            "cycles": list(self.cycles), "values": list(self.values),
        }


def _read(raw: bytes, offset: int, width: int) -> int:
    return int.from_bytes(raw[offset:offset + width], "little")


def _changes(values: Sequence[int]) -> list[int]:
    """Indices where a value differs from the one before it."""
    return [index for index in range(1, len(values))
            if values[index] != values[index - 1]]


def field_ranges() -> tuple[tuple[int, int, str], ...]:
    """The named offsets the packet decoder already knows, or none of them.

    Imported lazily so this module can be read and tested without the fixture
    reader, which pulls in the whole archive stack.
    """
    try:
        from bne_packet import FIELD_RANGES
    except ImportError:  # pragma: no cover - only when run standalone
        return ()
    return tuple((start, width, name) for start, width, name in FIELD_RANGES)


def field_for_offset(offset: int,
        ranges: Sequence[tuple[int, int, str]] | None = None) -> str | None:
    for start, width, name in (field_ranges() if ranges is None else ranges):
        if start <= offset < start + width:
            return name
    return None


def discover(samples: Sequence[Sample], *,
        ranges: Sequence[tuple[int, int, str]] | None = None) \
        -> list[Trajectory]:
    """Every observable in the record that changed during the window.

    A byte is reported whenever it changes. A wider read is reported only when
    it explains something the bytes alone do not -- a known field of that
    width, or a low byte that wrapped on the same cycle its neighbour moved,
    which is a carry and means the value is really the wider one. Without that
    rule every counter in the record would be reported four times.

    Bits are reported for a byte whose changes only ever touch a few of them,
    which is what a flag word looks like and what a small integer does not.
    """
    if len(samples) < 2:
        return []
    ranges = tuple(field_ranges()) if ranges is None else tuple(ranges)
    known = {(start, width) for start, width, _name in ranges}
    length = min(len(sample.raw) for sample in samples)
    cycles = tuple(sample.cycle for sample in samples)
    byte_values: dict[int, tuple[int, ...]] = {}
    for offset in range(length):
        values = tuple(sample.raw[offset] for sample in samples)
        if len(set(values)) > 1:
            byte_values[offset] = values

    found: list[Trajectory] = []
    for offset, values in sorted(byte_values.items()):
        found.append(Trajectory(
            key=f"byte:0x{offset:02x}", kind="byte", offset=offset, width=1,
            field=field_for_offset(offset, ranges), bit=None,
            cycles=cycles, values=values,
        ))
        mask = 0
        steps = [values[index] - values[index - 1] for index in _changes(values)]
        for index in _changes(values):
            mask |= values[index] ^ values[index - 1]
        # A byte that only ever climbs, or only ever falls, is a number. Every
        # small number touches its low bits, so decomposing one produces four
        # bit trajectories that outrank the counter they came from and bury it.
        monotone = all(step > 0 for step in steps) or all(step < 0 for step in steps)
        if not monotone and 0 < bin(mask).count("1") <= 3 and mask != 0xff:
            for bit in range(8):
                if not mask & (1 << bit):
                    continue
                found.append(Trajectory(
                    key=f"bit:0x{offset:02x}.{bit}", kind="bit",
                    offset=offset, width=1,
                    field=field_for_offset(offset, ranges), bit=bit,
                    cycles=cycles,
                    values=tuple((value >> bit) & 1 for value in values),
                ))

    for width in (2, 4):
        for offset in range(length - width + 1):
            if not _worth_reading_wide(offset, width, byte_values, known):
                continue
            values = tuple(_read(sample.raw, offset, width)
                           for sample in samples)
            if len(set(values)) <= 1:
                continue
            found.append(Trajectory(
                key=f"{'word' if width == 2 else 'dword'}:0x{offset:02x}",
                kind="word" if width == 2 else "dword",
                offset=offset, width=width,
                field=field_for_offset(offset, ranges), bit=None,
                cycles=cycles, values=values,
            ))
    return found


def _worth_reading_wide(offset: int, width: int,
        byte_values: dict[int, tuple[int, ...]],
        known: set[tuple[int, int]]) -> bool:
    if (offset, width) in known:
        return True
    low = byte_values.get(offset)
    if low is None:
        return False
    carried = set()
    for index in _changes(low):
        if abs(low[index] - low[index - 1]) > BYTE_MAX // 2:
            carried.add(index)
    if not carried:
        return False
    return any(index in carried
               for neighbour in range(offset + 1, offset + width)
               for index in _changes(byte_values.get(neighbour, ())))


def _period(values: Sequence[int]) -> int | None:
    """The shortest repeat that covers the window at least twice."""
    length = len(values)
    for period in range(1, length // 2 + 1):
        if all(values[index] == values[index - period]
               for index in range(period, length)):
            return None if period == 1 else period
    return None


def classify(trajectory: Trajectory, samples: Sequence[Sample] | None = None) \
        -> dict[str, Any]:
    """Name the shape of one trajectory without naming what it is.

    A shape is a description of the numbers, and several can be true at once:
    a byte that climbs by one to eight and drops back to zero is a counter, a
    ramp and a reset. Nothing here decides what the byte means.
    """
    values = trajectory.values
    cycles = trajectory.cycles
    changes = _changes(values)
    deltas = [values[index] - values[index - 1] for index in changes]
    shapes: list[str] = []
    detail: dict[str, Any] = {}

    rises = [delta for delta in deltas if delta > 0]
    falls = [delta for delta in deltas if delta < 0]
    if rises and not falls:
        shapes.append("monotonic-increase")
    if falls and not rises:
        shapes.append("monotonic-decrease")
    if rises and all(delta == 1 for delta in rises) and not falls:
        shapes.append("unit-counter")
    if falls and all(delta == -1 for delta in falls) and not rises:
        shapes.append("countdown")

    # A reset is a fall back to the start after a climb. A value that only
    # ever drops -- a route cleared, a target lost -- fell once and did not
    # reset, and calling that a reset invents a counter that was never there.
    resets = [index for index, delta in zip(changes, deltas)
              if delta < 0 and values[index] <= values[0]
              and any(values[earlier + 1] > values[earlier]
                      for earlier in range(index))]
    if resets:
        shapes.append("reset")
        detail["resets"] = [{
            "cycle": cycles[index], "from": values[index - 1],
            "to": values[index],
        } for index in resets]
        detail["reset_threshold"] = max(values[index - 1] for index in resets)

    if changes:
        peak = max(values)
        held = _trailing_run(values, peak)
        if held >= 2 and values[-1] == peak and rises and not falls:
            shapes.append("saturating")
            detail["saturation_value"] = peak
            detail["saturated_cycles"] = held

    armed = _armed_timer(values, cycles)
    if armed is not None:
        shapes.append("timer-armed")
        detail["armed"] = armed

    period = _period(values)
    if period is not None:
        shapes.append("periodic")
        detail["period"] = period

    distinct = sorted(set(values))
    if len(distinct) == 2 and len(changes) >= 2 \
            and all(values[index] != values[index - 1] for index in changes):
        shapes.append("toggle")
    if len(changes) == 1:
        shapes.append("single-transition")
        detail["transition"] = {
            "cycle": cycles[changes[0]], "from": values[changes[0] - 1],
            "to": values[changes[0]],
        }
    if not shapes and len(distinct) <= 5:
        shapes.append("enum-like")
    if trajectory.width == 4 and all(
            value == 0 or POINTER_LOW <= value < POINTER_HIGH
            for value in values):
        shapes.append("pointer-like")
    if not shapes:
        shapes.append("irregular")

    persistence = _persistence(trajectory, samples)
    return {
        **trajectory.as_dict(),
        "shapes": shapes,
        # How long the state stayed away from where it started, which is what
        # "this flag is held for N cycles" has to be measured against.
        "longest_run_away_from_first": _longest_run_away(values),
        "change_cycles": [cycles[index] for index in changes],
        "change_count": len(changes),
        # The cadence of the transitions: how many cycles apart they fall. A
        # counter that moves every cycle and one that moves every fourth are
        # different mechanisms with the same shape.
        "change_gaps": [cycles[changes[index]] - cycles[changes[index - 1]]
                        for index in range(1, len(changes))],
        "deltas": deltas,
        "distinct_values": distinct,
        "minimum": min(values), "maximum": max(values),
        "first": values[0], "last": values[-1],
        **detail,
        **persistence,
    }


def _longest_run_away(values: Sequence[int]) -> int:
    longest = run = 0
    for value in values:
        run = run + 1 if value != values[0] else 0
        longest = max(longest, run)
    return longest


def _trailing_run(values: Sequence[int], value: int) -> int:
    run = 0
    for observed in reversed(values):
        if observed != value:
            break
        run += 1
    return run


def _armed_timer(values: Sequence[int], cycles: Sequence[int]) \
        -> dict[str, Any] | None:
    """A jump up followed by a run of single decrements, which is a countdown.

    Two decrements are required before it is called one. A single step down
    after a step up is two ordinary writes and says nothing.
    """
    for index in range(1, len(values)):
        if values[index] - values[index - 1] < 2:
            continue
        run = 0
        position = index
        while position + 1 < len(values) \
                and values[position + 1] == values[position] - 1:
            run += 1
            position += 1
        if run >= 2:
            return {
                "armed_cycle": cycles[index], "armed_value": values[index],
                "counted_down": run,
                "expires_cycle": cycles[index] + values[index],
            }
    return None


def _persistence(trajectory: Trajectory,
        samples: Sequence[Sample] | None) -> dict[str, Any]:
    """Whether a value that did not change was written again anyway.

    The fixture records which unit records the engine rewrote, not which bytes
    it touched, so this is the honest limit: a value can be shown to have been
    inside a rewritten record while not changing, which is not the same as
    proving the engine wrote that value.
    """
    if not samples:
        return {"record_written_cycles": None, "rewritten_unchanged_cycles": None}
    written = [sample.cycle for sample in samples if sample.written]
    changed = set(trajectory.cycles[index]
                  for index in _changes(trajectory.values))
    return {
        "record_written_cycles": written,
        "rewritten_unchanged_cycles": [cycle for cycle in written
                                       if cycle not in changed],
        "write_granularity": "record",
    }


def thresholds(classified: Sequence[dict[str, Any]]) -> list[dict[str, Any]]:
    """Ramps paired with whatever changed at the top of them.

    This is the shape the whole module exists for: something climbs, and at
    some value something else happens. The climb and the consequence are
    reported as two observables and a delay, and never as cause and effect --
    a ramp that reaches eight on the cycle a route is cleared is a coincidence
    until a counterexample fails to repeat it.
    """
    ramps = [item for item in classified
             if "monotonic-increase" in item["shapes"] and item["change_count"] >= 2]
    results: list[dict[str, Any]] = []
    for ramp in ramps:
        peak_cycle = ramp["cycles"][ramp["values"].index(ramp["maximum"])] \
            if ramp["maximum"] in ramp["values"] else None
        for other in classified:
            if other["key"] == ramp["key"] or not other["change_cycles"]:
                continue
            if _overlapping(ramp, other):
                # The same bytes read two ways. A byte that ramps and the word
                # containing it are one observable, and pairing them would
                # report every counter as the cause of itself.
                continue
            after = [cycle for cycle in other["change_cycles"]
                     if cycle >= ramp["change_cycles"][0]]
            if not after:
                continue
            consequence = after[0]
            value_at = _value_at(ramp, consequence)
            value_before = _value_at(ramp, consequence - 1)
            if value_at is None:
                continue
            results.append({
                "ramp": ramp["key"], "ramp_field": ramp["field"],
                "consequence": other["key"], "consequence_field": other["field"],
                "consequence_cycle": consequence,
                "ramp_value_at_consequence": value_at,
                "ramp_value_before": value_before,
                "ramp_peak": ramp["maximum"],
                "at_ramp_peak": value_at == ramp["maximum"],
                "delay_after_peak": (consequence - peak_cycle
                                     if peak_cycle is not None else None),
                "consequence_shapes": other["shapes"],
            })
    results.sort(key=lambda item: (
        item["consequence_cycle"], not item["at_ramp_peak"],
        item["ramp"], item["consequence"],
    ))
    return results


def _overlapping(left: dict[str, Any], right: dict[str, Any]) -> bool:
    if left["offset"] < 0 or right["offset"] < 0:
        return False
    return (left["offset"] < right["offset"] + max(1, right["width"])
            and right["offset"] < left["offset"] + max(1, left["width"]))


def _value_at(item: dict[str, Any], cycle: int) -> int | None:
    latest = None
    for observed, value in zip(item["cycles"], item["values"]):
        if observed <= cycle:
            latest = value
    return latest


def rank_informative(classified: Sequence[dict[str, Any]]) \
        -> list[dict[str, Any]]:
    """Most informative first, so a report can stop before the noise.

    A named field that ramps says more than an anonymous byte that flickers
    once, and both say more than the sixty bytes of a sprite's animation
    bookkeeping. Dumping the whole record in offset order buries the finding.
    """
    interesting = {
        "unit-counter": 6.0, "countdown": 5.0, "timer-armed": 6.0,
        "reset": 4.0, "saturating": 4.0, "monotonic-increase": 3.0,
        "monotonic-decrease": 3.0, "single-transition": 2.0,
        "toggle": 1.5, "periodic": 1.5, "pointer-like": 1.0,
        "enum-like": 0.75, "irregular": 0.0,
    }

    def score(item: dict[str, Any]) -> float:
        value = sum(interesting.get(shape, 0.0) for shape in item["shapes"])
        if item["field"]:
            value += 1.5
        if item["kind"] == "bit":
            value += 0.5
        if item["change_count"] > 1:
            value += 1.0
        # A byte that changes on every single cycle of the window is usually
        # an animation frame or a sub-tile offset, not a decision.
        if item["change_count"] >= len(item["cycles"]) - 1:
            value -= 1.5
        return value

    return sorted(classified,
                  key=lambda item: (-round(score(item), 6), item["key"]))


def mine(samples: Sequence[Sample], *,
        ranges: Sequence[tuple[int, int, str]] | None = None) \
        -> dict[str, Any]:
    """Trajectories, shapes, ramps and consequences for one unit's window."""
    if len(samples) < 2:
        raise ValueError("a state machine needs at least two cycles")
    cycles = [sample.cycle for sample in samples]
    gaps = [cycles[index] - cycles[index - 1]
            for index in range(1, len(cycles))]
    classified = [classify(trajectory, samples)
                  for trajectory in discover(samples, ranges=ranges)]
    ordered = rank_informative(classified)
    return {
        "schema": STATE_MACHINE_SCHEMA,
        "cycles": cycles,
        "contiguous": all(gap == 1 for gap in gaps),
        "missing_cycles": [cycle for index, gap in enumerate(gaps, 1)
                           if gap != 1
                           for cycle in range(cycles[index - 1] + 1,
                                              cycles[index])],
        "record_bytes": min(len(sample.raw) for sample in samples),
        "changing_observables": len(classified),
        "trajectories": ordered,
        "thresholds": thresholds(classified),
    }


def samples_from_packet(packet: dict[str, Any], slot: int) -> list[Sample]:
    """Reconstruct one unit's record at every cycle the packet retained."""
    native_state = packet.get("native_state") or {}
    samples: list[Sample] = []
    for cycle_text in sorted(native_state, key=int):
        units = native_state[cycle_text].get("units") or {}
        record = units.get(str(slot))
        if not isinstance(record, dict) or not record.get("raw_hex"):
            continue
        samples.append(Sample(
            cycle=int(cycle_text),
            raw=bytes.fromhex(record["raw_hex"]),
            written=record.get("changed_this_cycle"),
            generation=record.get("generation"),
        ))
    return samples


def native_series(samples: Sequence[Sample]) -> dict[int, dict[str, Any]]:
    """The named fields of the record at each cycle, when the decoder is here.

    Used only to pair the two engines by something they both report -- tile
    position and hit points. The mining above never needs a name.
    """
    try:
        from bne_packet import decode_unit
    except ImportError:  # pragma: no cover - only when run standalone
        return {}
    series = {}
    for sample in samples:
        decoded = decode_unit(sample.raw)
        decoded.pop("raw_hex", None)
        series[sample.cycle] = decoded
    return series


def java_series_from_events(events: Iterable[Any], unit_id: int | None = None) \
        -> dict[int, dict[str, Any]]:
    """The followed unit's per-cycle state, out of its causal events."""
    series: dict[int, dict[str, Any]] = {}
    subject = None if unit_id is None else f"unit:{unit_id}"
    for event in events:
        if getattr(event, "kind", None) != "state.unit":
            continue
        if subject is not None and getattr(event, "subject", None) != subject:
            continue
        cycle = getattr(event, "cycle", None)
        if not isinstance(cycle, int):
            continue
        series[cycle] = dict(getattr(event, "fields", {}) or {})
    return series


def java_series_from_packet(packet: dict[str, Any], native_slot: int) \
        -> dict[int, dict[str, Any]]:
    """The paired Java unit's semantic state, out of the packet window.

    Thinner than the causal record -- position, hit points, order -- but it
    needs no rerun, so a packet alone can still be aligned.
    """
    series: dict[int, dict[str, Any]] = {}
    for cycle_text in sorted(packet.get("semantic", {}), key=int):
        for focus in packet["semantic"][cycle_text].get("focus", []):
            if focus.get("native_slot") != native_slot:
                continue
            observed = focus.get("java")
            if isinstance(observed, dict):
                series[int(cycle_text)] = dict(observed)
    return series


def series_trajectories(series: dict[int, dict[str, Any]]) \
        -> list[dict[str, Any]]:
    """Classify each field of a per-cycle record the same way as the bytes.

    Non-numeric fields -- an order name, a boolean -- are given ordinals in
    first-seen order so the same shape vocabulary applies, and the mapping is
    reported beside them so nobody reads the ordinal as a value.
    """
    cycles = sorted(series)
    if len(cycles) < 2:
        return []
    keys = sorted({key for cycle in cycles for key in series[cycle]})
    results = []
    for key in keys:
        raw_values = [series[cycle].get(key) for cycle in cycles]
        if any(value is None for value in raw_values):
            continue
        if all(isinstance(value, bool) for value in raw_values):
            values = [int(value) for value in raw_values]
            encoding = {"false": 0, "true": 1}
        elif all(isinstance(value, int) for value in raw_values):
            values = list(raw_values)
            encoding = None
        elif all(isinstance(value, (str, int, bool)) for value in raw_values):
            order: list[Any] = []
            for value in raw_values:
                if value not in order:
                    order.append(value)
            values = [order.index(value) for value in raw_values]
            encoding = {str(value): index for index, value in enumerate(order)}
        else:
            continue
        if len(set(values)) <= 1:
            continue
        trajectory = Trajectory(
            key=f"java:{key}", kind="java-field", offset=-1, width=0,
            field=key, bit=None, cycles=tuple(cycles), values=tuple(values),
        )
        item = classify(trajectory)
        if encoding is not None:
            item["encoding"] = encoding
            item["encoded_values"] = [str(value) for value in raw_values]
        results.append(item)
    return rank_informative(results)


def estimate_cycle_offset(native: dict[int, dict[str, Any]],
        java: dict[int, dict[str, Any]], *, keys: Sequence[str] = ("x", "y"),
        limit: int = 8) -> dict[str, Any]:
    """How far apart the two engines number the cycles of the same window.

    The two sides count from different places -- a fixture cycle is not an
    internal Java cycle -- and a presentation-ahead frame moves every later
    one. Position is the thing both sides report and neither invents, so the
    offset is whichever shift lines up the most positions. It is reported and
    then used; nothing here assumes it is zero.
    """
    scores: dict[int, int] = {}
    for offset in range(-limit, limit + 1):
        agreed = 0
        compared = 0
        for cycle, values in native.items():
            other = java.get(cycle - offset)
            if other is None:
                continue
            for key in keys:
                if key in values and key in other:
                    compared += 1
                    agreed += int(values[key] == other[key])
        if compared:
            scores[offset] = agreed
    if not scores:
        return {"offset": None, "agreed": 0, "compared": 0,
                "unambiguous": False, "candidates": {}}
    best = max(scores.items(), key=lambda item: (item[1], -abs(item[0])))
    ties = [offset for offset, agreed in scores.items()
            if agreed == best[1] and offset != best[0]]
    return {
        "offset": best[0], "agreed": best[1],
        "unambiguous": not ties and best[1] > 0,
        "ties": sorted(ties),
        "candidates": {str(offset): scores[offset] for offset in sorted(scores)},
    }


def _change_cycles(item: dict[str, Any]) -> tuple[int, ...]:
    return tuple(item["change_cycles"])


def correlate(native_classified: Sequence[dict[str, Any]],
        java_classified: Sequence[dict[str, Any]], offset: int | None, *,
        proved: dict[str, str] | None = None,
        strong_changes: int = 2) -> list[dict[str, Any]]:
    """Grade each native trajectory against the Java fields that moved with it.

    The grades exist because timing is not meaning. Two things that change on
    the same cycles may be the same thing, or may both be downstream of a
    third; the only way to tell from here is that they are the only pair that
    fits, that they carry the same values, and that a counterexample has not
    broken the rule. So an unproved pairing is never called proved, however
    good the correlation looks.
    """
    proved = proved or {}
    results = []
    for item in native_classified:
        native_cycles = _change_cycles(item)
        if not native_cycles:
            continue
        shifted = (tuple(cycle - offset for cycle in native_cycles)
                   if offset is not None else native_cycles)
        exact, partial = [], []
        for other in java_classified:
            java_cycles = _change_cycles(other)
            if not java_cycles:
                continue
            if java_cycles == shifted:
                exact.append({
                    "java_field": other["field"],
                    "values_agree": list(item["values"]) == list(other["values"]),
                    "shapes": other["shapes"],
                })
            elif set(java_cycles) & set(shifted):
                partial.append({
                    "java_field": other["field"],
                    "shared_change_cycles": sorted(
                        set(java_cycles) & set(shifted)),
                    "shapes": other["shapes"],
                })
        combination = _combination(shifted, java_classified) if not exact \
            else None
        named = proved.get(item["key"])
        if named is not None:
            grade, counterpart = "proved", named
            reason = "a mapping supplied as already proved"
        elif exact:
            agreeing = [entry for entry in exact if entry["values_agree"]]
            if len(agreeing) == 1 and len(item["change_cycles"]) >= strong_changes:
                grade = "strong-temporal-correlation"
                counterpart = agreeing[0]["java_field"]
                reason = ("one Java field changed on exactly these cycles and "
                          "carried the same values")
            elif len(exact) == 1:
                grade = "speculative-candidate"
                counterpart = exact[0]["java_field"]
                reason = ("one Java field changed on exactly these cycles but "
                          "does not carry the same values")
            else:
                grade = "speculative-candidate"
                counterpart = None
                reason = (f"{len(exact)} Java fields changed on exactly these "
                          "cycles, so timing cannot choose between them")
        elif combination is not None:
            grade, counterpart = "speculative-candidate", None
            reason = (f"no single Java field fits, but {combination[0]} and "
                      f"{combination[1]} together change on exactly these "
                      "cycles, so this may be a combination rather than one "
                      "field")
        elif partial:
            grade, counterpart = "speculative-candidate", None
            reason = "some Java fields share some of these cycles"
        else:
            grade, counterpart = "no-java-counterpart", None
            reason = "no Java field changed on these cycles"
        results.append({
            "native": item["key"], "native_field": item["field"],
            "native_shapes": item["shapes"],
            "change_cycles": list(native_cycles),
            "confidence": grade, "java_counterpart": counterpart,
            "reason": reason,
            "combination_candidate": list(combination) if combination else None,
            "exact_matches": sorted(
                exact, key=lambda entry: str(entry["java_field"])),
            "partial_matches": sorted(
                partial, key=lambda entry: str(entry["java_field"]))[:4],
        })
    order = {grade: index for index, grade in enumerate(CONFIDENCE_GRADES)}
    results.sort(key=lambda entry: (order[entry["confidence"]],
                                    -len(entry["change_cycles"]),
                                    entry["native"]))
    return results


def _combination(wanted: tuple[int, ...],
        java_classified: Sequence[dict[str, Any]], *, limit: int = 12) \
        -> tuple[str, str] | None:
    """Two port fields that between them move on exactly these cycles.

    A native counter with no single counterpart is sometimes a pair of port
    fields that were never one value here. Reported as a candidate and never
    as an answer: two fields covering the right cycles is a weaker statement
    than one field carrying the right values.
    """
    fields = [item for item in java_classified[:limit]
              if item["change_cycles"]]
    for index, left in enumerate(fields):
        for right in fields[index + 1:]:
            union = tuple(sorted(set(left["change_cycles"])
                                 | set(right["change_cycles"])))
            if union == wanted:
                return (str(left["field"]), str(right["field"]))
    return None


def first_divergence(native: dict[int, dict[str, Any]],
        java: dict[int, dict[str, Any]], offset: int | None, *,
        keys: Sequence[str] = ("x", "y", "hp")) -> dict[str, Any]:
    """The last cycle the two engines agreed about, and the first they did not."""
    if offset is None:
        return {"comparable_cycles": 0, "last_aligned_cycle": None,
                "first_divergent_cycle": None, "differences": {}}
    last_aligned = None
    first_divergent = None
    differences: dict[str, dict[str, Any]] = {}
    compared = 0
    for cycle in sorted(native):
        other = java.get(cycle - offset)
        if other is None:
            continue
        compared += 1
        mismatch = {
            key: {"native": native[cycle][key], "java": other[key]}
            for key in keys
            if key in native[cycle] and key in other
            and native[cycle][key] != other[key]
        }
        if mismatch and first_divergent is None:
            first_divergent = cycle
            differences = mismatch
        elif not mismatch and first_divergent is None:
            last_aligned = cycle
    return {
        "comparable_cycles": compared,
        "last_aligned_cycle": last_aligned,
        "first_divergent_cycle": first_divergent,
        "differences": differences,
    }


def changed_at(classified: Sequence[dict[str, Any]], cycle: int) \
        -> list[dict[str, Any]]:
    """Which observables moved on one cycle, most informative first."""
    return [{"key": item["key"], "field": item["field"],
             "shapes": item["shapes"], "value": _value_at(item, cycle),
             "previous": _value_at(item, cycle - 1)}
            for item in classified if cycle in item["change_cycles"]]


def rule_from_threshold(report: dict[str, Any],
        pairing: dict[str, Any] | None = None) -> dict[str, Any] | None:
    """Write down the focused case's finding in a form other cases can test.

    A rule here is deliberately small: this observable climbs, and at this
    value that observable changes. Anything larger could not be checked
    against another fixture without a human deciding what it meant.
    """
    pairings = report.get("thresholds") or []
    pairing = pairing or (pairings[0] if pairings else None)
    if pairing is None:
        return None
    ramp = next((item for item in report["trajectories"]
                 if item["key"] == pairing["ramp"]), None)
    return {
        "observable": pairing["ramp"],
        "shapes": [shape for shape in (ramp or {}).get("shapes", [])
                   if shape in ("unit-counter", "monotonic-increase",
                                "saturating", "reset")],
        "threshold": pairing["ramp_value_at_consequence"],
        "consequence": pairing["consequence"],
        "consequence_shapes": pairing["consequence_shapes"],
    }


def test_rule(rule: dict[str, Any], report: dict[str, Any]) -> dict[str, Any]:
    """Whether one other window supports the focused case's rule or breaks it.

    Four answers, and "not applicable" is one of them: a window where the
    observable never moves says nothing about a rule for when it does, and
    counting it as support would let a rule look confirmed by fixtures that
    never tested it.
    """
    ramp = next((item for item in report.get("trajectories", [])
                 if item["key"] == rule["observable"]), None)
    consequence = next((item for item in report.get("trajectories", [])
                        if item["key"] == rule["consequence"]), None)
    if ramp is None:
        return {"verdict": "not-applicable",
                "reason": f"{rule['observable']} never moves in this window"}
    missing_shapes = [shape for shape in rule.get("shapes", [])
                      if shape not in ramp["shapes"]]
    if consequence is None:
        return {
            "verdict": "contradicts",
            "reason": (f"{rule['observable']} reaches {ramp['maximum']} and "
                       f"{rule['consequence']} never changes"),
            "observed_peak": ramp["maximum"],
            "observed_threshold": None,
            "missing_shapes": missing_shapes,
        }
    # Read the ramp's value where the consequence fired rather than looking
    # for a ready-made pairing. A window where the ramp moved only once has
    # no pairing to find and is exactly the case worth reporting: the
    # consequence happened at a value the rule does not allow.
    observed = _value_at(ramp, consequence["change_cycles"][0])
    if observed == rule["threshold"] and not missing_shapes:
        return {"verdict": "supports",
                "reason": (f"{rule['observable']} reaches {observed} and "
                           f"{rule['consequence']} changes there too"),
                "observed_threshold": observed,
                "observed_peak": ramp["maximum"]}
    return {
        "verdict": "contradicts",
        "reason": (f"{rule['consequence']} changes at "
                   f"{rule['observable']} = {observed}, not "
                   f"{rule['threshold']}"
                   if observed is not None
                   else f"{rule['consequence']} changes without the ramp"),
        "observed_threshold": observed,
        "observed_peak": ramp["maximum"],
        "missing_shapes": missing_shapes,
    }


def observable_distance(focus: dict[str, Any], other: dict[str, Any]) \
        -> dict[str, Any]:
    """How differently two windows behave, counted in observables.

    A counterexample from a case that behaves almost identically is worth far
    more than one from a case that shares nothing: the first names the one
    condition the rule was missing, and the second only says the two are
    different games.
    """
    left = {item["key"]: frozenset(item["shapes"])
            for item in focus.get("trajectories", [])}
    right = {item["key"]: frozenset(item["shapes"])
             for item in other.get("trajectories", [])}
    shared = sorted(set(left) & set(right))
    differing = [key for key in shared if left[key] != right[key]]
    only_focus = sorted(set(left) - set(right))
    only_other = sorted(set(right) - set(left))
    total = len(set(left) | set(right)) or 1
    return {
        "shared_observables": len(shared),
        "differing_shapes": differing,
        "only_in_focus": only_focus,
        "only_in_other": only_other,
        "distance": round(
            (len(differing) + len(only_focus) + len(only_other)) / total, 6),
    }


def counterexamples(rule: dict[str, Any], focus: dict[str, Any],
        others: Sequence[dict[str, Any]]) -> dict[str, Any]:
    """Test one rule against every other authenticated window, nearest first."""
    results = []
    for entry in others:
        report = entry["report"]
        verdict = test_rule(rule, report)
        distance = observable_distance(focus, report)
        results.append({
            "case": entry.get("case"), "slot": entry.get("slot"),
            **verdict, "distance": distance["distance"],
            "differing_shapes": distance["differing_shapes"][:8],
            "shared_observables": distance["shared_observables"],
        })
    rank = {"contradicts": 0, "supports": 1, "not-applicable": 2}
    results.sort(key=lambda item: (rank[item["verdict"]], item["distance"],
                                   str(item["case"])))
    contradicting = [item for item in results
                     if item["verdict"] == "contradicts"]
    supporting = [item for item in results if item["verdict"] == "supports"]
    thresholds_seen = sorted({item["observed_threshold"] for item in results
                              if item["observed_threshold"] is not None}
                             | {rule["threshold"]}
                             if rule.get("threshold") is not None else set())
    return {
        "rule": rule,
        "cases_tested": len(results),
        "supports": len(supporting),
        "contradicts": len(contradicting),
        "not_applicable": len(results) - len(supporting) - len(contradicting),
        "threshold_stable": len(thresholds_seen) <= 1,
        "thresholds_observed": thresholds_seen,
        "nearest_counterexample": contradicting[0] if contradicting else None,
        "results": results,
    }


#: A name that says which fixture it was found in rather than what it is.
_FIXTURE_NAMED = re.compile(
    r"(?i)(x?(human|orc)[-_ ]?\d+|level[a-z]*\d+|demo\d+|cycle\d+|@\d+)")
_WORKAROUND_NAMED = ("workaround", "hack", "kludge", "fudge", "hotfix",
                     "bandaid", "temporary", "quirk", "special")


def audit_proposed_state(proposal: dict[str, Any],
        findings: dict[str, Any]) -> dict[str, Any]:
    """Ask what native evidence exists for a state the port proposes to keep.

    This never rejects an implementation and never edits one. A port is a port
    of behaviour, not of names, so a Java field called something else is not a
    finding. What is a finding is a flag with no native state behind it, one
    held longer than the native state it claims to be, one that resets on a
    different condition, one named after the fixture it was found in, and one
    a nearby case already contradicts. Each is a warning with the evidence
    that produced it.
    """
    name = str(proposal.get("name") or "")
    java_field = proposal.get("java_field") or name
    correlations = findings.get("correlations") or []
    trajectories = findings.get("trajectories") or []
    warnings: list[dict[str, Any]] = []
    observations: list[str] = []

    matched = [item for item in correlations
               if item.get("java_counterpart") == java_field
               and item["confidence"] in ("proved", "strong-temporal-correlation")]
    weak = [item for item in correlations
            if item.get("java_counterpart") == java_field
            and item["confidence"] == "speculative-candidate"]
    if matched:
        observations.append(
            f"{java_field} moves with {matched[0]['native']} "
            f"({matched[0]['confidence']})")
    elif weak:
        warnings.append({
            "code": "native-correlate-speculative",
            "detail": (f"{java_field} matches {weak[0]['native']} only as a "
                       "speculative candidate, so no native state has been "
                       "shown to be this one"),
        })
    else:
        warnings.append({
            "code": "no-native-correlate",
            "detail": (f"no native observable in this window was shown to "
                       f"move with {java_field}"),
        })

    lifetime = proposal.get("lifetime_cycles")
    native_run = max((item.get("longest_run_away_from_first") or 0
                      for item in trajectories
                      if not matched or item["key"] == matched[0]["native"]),
                     default=0)
    if isinstance(lifetime, int) and native_run and lifetime > native_run:
        warnings.append({
            "code": "outlives-native-state",
            "detail": (f"the proposed state is held for {lifetime} cycles "
                       f"where the native observable stays away from its "
                       f"starting value for {native_run}"),
        })
    elif isinstance(lifetime, int) and native_run:
        observations.append(
            f"held for {lifetime} cycles against a native run of {native_run}")

    reset = str(proposal.get("reset_when") or "")
    native_resets = [item for item in trajectories
                     if "reset" in item["shapes"]
                     and (not matched or item["key"] == matched[0]["native"])]
    if native_resets and not reset:
        warnings.append({
            "code": "reset-condition-unstated",
            "detail": (f"{native_resets[0]['key']} resets from "
                       f"{native_resets[0].get('reset_threshold')} in this "
                       "window and the proposal states no reset"),
        })
    elif native_resets:
        observations.append(
            f"native {native_resets[0]['key']} resets from "
            f"{native_resets[0].get('reset_threshold')}")

    if _FIXTURE_NAMED.search(name):
        warnings.append({
            "code": "named-after-a-fixture",
            "detail": (f"{name!r} names the case it was found in rather than "
                       "the state it holds, which is how a fixture-shaped "
                       "rule survives review"),
        })
    lowered = name.lower()
    if any(word in lowered for word in _WORKAROUND_NAMED):
        warnings.append({
            "code": "named-as-a-workaround",
            "detail": f"{name!r} is named as a workaround rather than a state",
        })

    contradicting = [item for item in
                     (findings.get("counterexamples") or {}).get("results", [])
                     if item.get("verdict") == "contradicts"]
    if contradicting:
        warnings.append({
            "code": "contradicted-by-counterexample",
            "detail": (f"{contradicting[0]['case']} breaks the rule this "
                       f"state encodes: {contradicting[0]['reason']}"),
        })

    if not warnings:
        verdict = "evidence-supports"
    elif any(item["code"] in ("no-native-correlate",
                              "contradicted-by-counterexample")
             for item in warnings):
        verdict = "evidence-contradicts"
    else:
        verdict = "evidence-incomplete"
    return {
        "schema": STATE_MACHINE_SCHEMA,
        "proposal": proposal,
        "verdict": verdict,
        "warnings": warnings,
        "observations": observations,
        "changes_no_source": True,
        "note": ("A warning is a request for evidence, not a rejection. A "
                 "port matches behaviour, not names."),
    }


def state_graph(item: dict[str, Any]) -> dict[str, Any]:
    """One observable as a graph: the values it took and the steps between.

    Small on purpose. A transition diagram of a byte with forty values is not
    a diagram, so this is emitted for the observables the report leads with
    and read as data by whatever wants to draw it.
    """
    edges: dict[tuple[int, int], list[int]] = {}
    for index in range(1, len(item["values"])):
        before, after = item["values"][index - 1], item["values"][index]
        if before != after:
            edges.setdefault((before, after), []).append(item["cycles"][index])
    return {
        "observable": item["key"], "field": item["field"],
        "nodes": sorted(set(item["values"])),
        "edges": [{"from": before, "to": after, "cycles": cycles,
                   "count": len(cycles)}
                  for (before, after), cycles in sorted(edges.items())],
        "entry": item["values"][0], "exit": item["values"][-1],
    }


#: What makes a window worth mining. Each is a shape in the evidence, not a
#: fixture: a counter climbing before something happens, a timer armed after
#: it, a unit standing still while its hidden state moves, a value that
#: repeats until a transition finally takes.
def signals(report: dict[str, Any],
        java: dict[int, dict[str, Any]] | None = None) -> list[dict[str, Any]]:
    """Whether this window looks like a multi-cycle state machine at all.

    The lab asks this before mining, because most divergences are not this
    shape and a confident-looking state report for one of them is noise.
    """
    found: list[dict[str, Any]] = []
    trajectories = report.get("trajectories", [])
    hidden = [item for item in trajectories
              if item["field"] not in VISIBLE_FIELDS]
    for item in hidden:
        if item["change_count"] >= 3 and (
                "unit-counter" in item["shapes"]
                or "monotonic-increase" in item["shapes"]):
            found.append({
                "signal": "accumulation-before-an-action",
                "observable": item["key"], "field": item["field"],
                "evidence": (f"climbs {item['first']} to {item['maximum']} "
                             f"over {item['change_count']} cycles"),
            })
            break
    for item in hidden:
        if "timer-armed" in item["shapes"]:
            found.append({
                "signal": "timer-armed-after-a-transition",
                "observable": item["key"], "field": item["field"],
                "evidence": (f"jumps to {item['armed']['armed_value']} at "
                             f"cycle {item['armed']['armed_cycle']} and "
                             "counts down"),
            })
            break
    moving = {item["field"] for item in trajectories if item["field"]}
    if trajectories and not {"x", "y"} & moving:
        found.append({
            "signal": "same-position-with-changing-hidden-state",
            "observable": trajectories[0]["key"],
            "field": trajectories[0]["field"],
            "evidence": (f"{len(trajectories)} observables move while the "
                         "unit does not"),
        })
    for pairing in report.get("thresholds", [])[:4]:
        if pairing.get("delay_after_peak") and pairing["delay_after_peak"] > 1:
            found.append({
                "signal": "delayed-consequence-after-a-threshold",
                "observable": pairing["ramp"],
                "field": pairing.get("ramp_field"),
                "evidence": (f"{pairing['consequence']} changes "
                             f"{pairing['delay_after_peak']} cycles after "
                             f"{pairing['ramp']} peaked"),
            })
            break
    for item in trajectories:
        if "periodic" in item["shapes"] or (
                "toggle" in item["shapes"] and item["change_count"] >= 3):
            found.append({
                "signal": "repeated-visits-before-a-transition",
                "observable": item["key"], "field": item["field"],
                "evidence": (f"repeats with period {item.get('period')}"
                             if item.get("period")
                             else f"toggles {item['change_count']} times"),
            })
            break
    if java:
        cycles = sorted(java)
        held = [key for key in ("order", "path_length")
                if len({str(java[cycle].get(key)) for cycle in cycles}) == 1]
        if held and len(found) > 0:
            found.append({
                "signal": "port-state-unchanged-while-native-moves",
                "observable": ",".join(held), "field": None,
                "evidence": (f"the port holds {', '.join(held)} for the whole "
                             "window while the native record does not"),
            })
    return found


def analyse(packet: dict[str, Any], slot: int, *,
        java_events: Iterable[Any] | None = None,
        java_unit: int | None = None,
        others: Sequence[dict[str, Any]] | None = None,
        proposal: dict[str, Any] | None = None,
        proved: dict[str, str] | None = None,
        ranges: Sequence[tuple[int, int, str]] | None = None,
        leading: int = 8) -> dict[str, Any]:
    """The whole report for one focused unit in one authenticated packet."""
    case = (packet.get("case") or {}).get("id")
    samples = samples_from_packet(packet, slot)
    if len(samples) < 3:
        return {
            "schema": STATE_MACHINE_SCHEMA, "case": case, "slot": slot,
            "status": "insufficient-window",
            "cycles_available": [sample.cycle for sample in samples],
            "next_experiment": capture_plan(
                (f"only {len(samples)} cycles of slot {slot} are in this "
                 "packet, and a state machine needs at least three"),
                case=case, slot=slot,
                scenario=(packet.get("case") or {}).get("scenario"),
                seed=(packet.get("case") or {}).get("seed"),
            ),
        }
    native = mine(samples, ranges=ranges)
    decoded = native_series(samples)
    java_series = (java_series_from_events(java_events, java_unit)
                   if java_events is not None else {})
    java_source = "causal state.unit events"
    if not java_series:
        java_series = java_series_from_packet(packet, slot)
        java_source = "packet semantic window"
    java_classified = series_trajectories(java_series)
    offset = estimate_cycle_offset(decoded, java_series)
    divergence = first_divergence(decoded, java_series, offset["offset"])
    correlations = correlate(native["trajectories"], java_classified,
                             offset["offset"], proved=proved)
    report: dict[str, Any] = {
        "schema": STATE_MACHINE_SCHEMA,
        "status": "mined",
        "case": case, "slot": slot,
        "window": {
            "cycles": native["cycles"], "contiguous": native["contiguous"],
            "missing_cycles": native["missing_cycles"],
            "record_bytes": native["record_bytes"],
        },
        "changing_observables": native["changing_observables"],
        "trajectories": native["trajectories"],
        "thresholds": native["thresholds"],
        "state_graphs": [state_graph(item)
                         for item in native["trajectories"][:3]],
        "java": {
            "source": java_source,
            "unit": java_unit,
            "cycles": sorted(java_series),
            "trajectories": java_classified,
        },
        "alignment": {
            "cycle_offset": offset,
            **divergence,
            "changed_at_divergence": (
                changed_at(native["trajectories"],
                           divergence["first_divergent_cycle"])
                if divergence["first_divergent_cycle"] is not None else []),
        },
        "correlations": correlations,
        "unknowns": _unknowns(native, correlations, offset, java_source),
        "signals": signals(native, java_series),
        "leading_observables": [item["key"]
                                for item in native["trajectories"][:leading]],
    }
    rule = rule_from_threshold(native)
    if rule is not None and others:
        report["counterexamples"] = counterexamples(rule, native, others)
    elif rule is not None:
        report["rule"] = rule
        report["counterexamples"] = {
            "rule": rule, "cases_tested": 0, "supports": 0, "contradicts": 0,
            "not_applicable": 0, "threshold_stable": None,
            "thresholds_observed": [], "nearest_counterexample": None,
            "results": [],
            "note": ("no other window was supplied, so this rule has been "
                     "tested against nothing"),
        }
    if proposal is not None:
        report["proposed_state_audit"] = audit_proposed_state(proposal, report)
    return report


def _unknowns(native: dict[str, Any], correlations: Sequence[dict[str, Any]],
        offset: dict[str, Any], java_source: str) -> list[str]:
    """What this report does not know, written down rather than left out.

    A report of confident-looking tables with its gaps unstated is read as
    though it had none, so every one of them is a line here.
    """
    unknowns = []
    unnamed = [item["key"] for item in native["trajectories"]
               if item["field"] is None]
    if unnamed:
        unknowns.append(
            f"{len(unnamed)} of the observables that moved have no name in "
            f"the pinned unit layout: {', '.join(unnamed[:6])}")
    orphans = [item["native"] for item in correlations
               if item["confidence"] == "no-java-counterpart"]
    if orphans:
        unknowns.append(
            f"{len(orphans)} native observables have no port counterpart in "
            f"this window: {', '.join(orphans[:6])}")
    if not offset.get("unambiguous"):
        unknowns.append(
            "the cycle offset between the two engines could not be pinned "
            f"down from position alone (best guess {offset.get('offset')}), "
            "so every pairing below it is weaker than it looks")
    if java_source != "causal state.unit events":
        unknowns.append(
            "the port side is the packet's semantic window, which carries "
            "position, hit points and order only; rerun with "
            "CHONKCRAFT_TRACE_BNE_CAUSAL to compare hidden state")
    if not native["contiguous"]:
        unknowns.append(
            f"cycles {native['missing_cycles']} are not in this window, so a "
            "transition inside them was not seen")
    unknowns.append(
        "the fixture records which unit records were rewritten, not which "
        "bytes, so a value that did not change inside a rewritten record is "
        "persisting rather than proven untouched")
    return unknowns


def _compress(values: Sequence[int], limit: int = 12) -> str:
    """A trajectory as a short arrow chain, with repeats folded up."""
    runs: list[tuple[int, int]] = []
    for value in values:
        if runs and runs[-1][0] == value:
            runs[-1] = (value, runs[-1][1] + 1)
        else:
            runs.append((value, 1))
    pieces = [f"{value}" if count == 1 else f"{value}x{count}"
              for value, count in runs]
    if len(pieces) > limit:
        pieces = pieces[:limit] + ["..."]
    return " -> ".join(pieces)


def format_report(report: dict[str, Any], *, leading: int = 8) -> str:
    """The agent-sized report: the informative fields, not the whole record."""
    lines = [
        "# Native state machine", "",
        f"- Case: `{report.get('case') or 'unknown'}`",
        f"- Native slot: `{report.get('slot')}`",
    ]
    if report.get("status") != "mined":
        plan = report.get("next_experiment") or {}
        lines.extend([
            f"- Result: **{report.get('status')}**", "",
            "## Next experiment", "", plan.get("reason", ""), "",
            "```sh", plan.get("capture_command", ""), "```", "",
            "```sh", plan.get("then", ""), "```", "",
        ])
        return "\n".join(lines)
    window = report["window"]
    lines.extend([
        f"- Window: cycles `{window['cycles'][0]}`-`{window['cycles'][-1]}`"
        + ("" if window["contiguous"]
           else f", missing `{window['missing_cycles']}`"),
        f"- Observables that moved: **{report['changing_observables']}** of "
        f"{window['record_bytes']} bytes",
        "",
    ])
    if report.get("signals"):
        lines.extend(["## Why this window was mined", ""])
        for signal in report["signals"]:
            lines.append(f"- `{signal['signal']}`: {signal['evidence']}")
        lines.append("")
    lines.extend([
        "## What moved, most informative first", "",
        "| Observable | Field | Shapes | Trajectory |",
        "|---|---|---|---|",
    ])
    for item in report["trajectories"][:leading]:
        values = item.get("encoded_values") or item["values"]
        lines.append(
            f"| `{item['key']}` | {item['field'] or '--'} | "
            f"{', '.join(item['shapes'])} | `{_compress(values)}` |"
        )
    lines.append("")
    if report.get("thresholds"):
        lines.extend([
            "## Ramp and consequence", "",
            "| Ramp | Value there | Consequence | Cycle | At peak |",
            "|---|---|---|---|---|",
        ])
        for pairing in report["thresholds"][:5]:
            lines.append(
                f"| `{pairing['ramp']}` | {pairing['ramp_value_at_consequence']}"
                f" (was {pairing['ramp_value_before']}) | "
                f"`{pairing['consequence']}` | {pairing['consequence_cycle']} |"
                f" {'yes' if pairing['at_ramp_peak'] else 'no'} |"
            )
        lines.extend([
            "",
            "A ramp and what changed at the top of it are two observables and "
            "a delay. Nothing here says one caused the other.", "",
        ])
    alignment = report["alignment"]
    lines.extend(["## Where the engines part", ""])
    offset = alignment["cycle_offset"]
    lines.append(
        f"- Cycle offset (native minus port): "
        f"**{offset['offset']}**"
        + ("" if offset["unambiguous"] else " (ambiguous)")
    )
    lines.append(f"- Last aligned cycle: `{alignment['last_aligned_cycle']}`")
    lines.append(
        f"- First differing cycle: `{alignment['first_divergent_cycle']}`")
    for key, difference in (alignment.get("differences") or {}).items():
        lines.append(
            f"  - `{key}`: native `{difference['native']}`, "
            f"port `{difference['java']}`")
    if alignment.get("changed_at_divergence"):
        lines.append("- Native observables that moved on that cycle:")
        for item in alignment["changed_at_divergence"][:6]:
            lines.append(
                f"  - `{item['key']}` {item['previous']} -> {item['value']}"
                f" ({', '.join(item['shapes'])})")
    lines.append("")
    lines.extend([
        "## Port counterpart, by confidence", "",
        "| Native | Confidence | Port field | Why |",
        "|---|---|---|---|",
    ])
    for item in report["correlations"][:leading]:
        lines.append(
            f"| `{item['native']}` | {item['confidence']} | "
            f"{item['java_counterpart'] or '--'} | {item['reason']} |"
        )
    lines.extend([
        "",
        "Timing is not meaning. Only `proved` is a mapping; the rest are "
        "things that moved together in this window.", "",
    ])
    if report.get("unknowns"):
        lines.extend(["## What this report does not know", ""])
        for unknown in report["unknowns"]:
            lines.append(f"- {unknown}")
        lines.append("")
    counter = report.get("counterexamples")
    if counter:
        rule = counter["rule"]
        lines.extend([
            "## Rule and counterexamples", "",
            f"- Rule: `{rule['observable']}` reaches **{rule['threshold']}** "
            f"and `{rule['consequence']}` changes",
            f"- Windows tested: **{counter['cases_tested']}** "
            f"({counter['supports']} support, {counter['contradicts']} "
            f"contradict, {counter['not_applicable']} not applicable)",
        ])
        if counter.get("note"):
            lines.append(f"- {counter['note']}")
        if counter.get("thresholds_observed"):
            lines.append(
                f"- Thresholds observed: `{counter['thresholds_observed']}`"
                + ("" if counter["threshold_stable"] else " (not stable)"))
        nearest = counter.get("nearest_counterexample")
        if nearest:
            lines.append(
                f"- Nearest counterexample: `{nearest['case']}` -- "
                f"{nearest['reason']}")
        lines.append("")
    audit = report.get("proposed_state_audit")
    if audit:
        lines.extend([
            "## Proposed state audit", "",
            f"- Proposal: `{audit['proposal'].get('name')}`",
            f"- Verdict: **{audit['verdict']}**",
        ])
        for warning in audit["warnings"]:
            lines.append(f"- `{warning['code']}`: {warning['detail']}")
        for observation in audit["observations"]:
            lines.append(f"- observed: {observation}")
        lines.extend(["", audit["note"], ""])
    lines.extend([
        "This report localizes state. The full regression gate remains the "
        "acceptance authority.", "",
    ])
    return "\n".join(lines)


def _json(value: object) -> str:
    return json.dumps(value, indent=2, sort_keys=True) + "\n"


IMPLEMENTATION = ("bne_state_machine.py", "bne_packet.py")


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


def _authenticated_packet(path: Path) -> tuple[dict[str, Any], dict[str, Any]]:
    """Read a packet and check it still describes the fixture it was built on.

    A packet records the sealed fixture's identity. Re-checking it here is
    what stops a state report being mined from a window somebody edited after
    triage sealed it.
    """
    from bne_triage import file_identity

    path = path.expanduser().resolve()
    if not path.is_file():
        raise ValueError(f"missing divergence packet: {path}")
    packet = json.loads(path.read_text(encoding="utf-8"))
    if packet.get("schema") != 1 or "native_state" not in packet:
        raise ValueError(f"not a divergence packet: {path}")
    fixture = (packet.get("identities") or {}).get("fixture") or {}
    indexed = packet.get("indexed_fixture") or {}
    fixture_path = Path(fixture["path"]) if fixture.get("path") else None
    if fixture_path is not None and fixture_path.is_file():
        actual = file_identity(fixture_path)
        expected = {key: fixture[key] for key in ("bytes", "sha256")
                    if key in fixture}
        if expected and actual != expected:
            raise ValueError(
                f"packet fixture identity changed: {fixture_path}")
    if indexed and fixture and any(
            indexed.get(key) != fixture.get(key)
            for key in ("bytes", "sha256") if key in indexed):
        raise ValueError("packet names two different fixture identities")
    return packet, {
        "path": str(path), **file_identity(path),
        "fixture": fixture, "case": (packet.get("case") or {}).get("id"),
    }


def run_state_machine(packet_path: Path, slot: int, artifact_root: Path, *,
        java_causal: Path | None = None, java_unit: int | None = None,
        compare: Sequence[tuple[Path, int]] = (),
        proposal_path: Path | None = None) -> tuple[int, Path]:
    """Produce a durable, content-addressed state-machine report."""
    from bne_causal import parse_causal_jsonl
    from bne_triage import canonical_digest, file_identity, inventory_files

    packet, packet_identity = _authenticated_packet(packet_path)
    java_events = None
    java_identity = None
    if java_causal is not None:
        java_causal = java_causal.expanduser().resolve()
        if not java_causal.is_file():
            raise ValueError(f"missing Java causal trace: {java_causal}")
        java_events = parse_causal_jsonl(
            java_causal.read_text(encoding="utf-8"), expected_side="java",
            source=str(java_causal),
        )
        java_identity = {"path": str(java_causal), **file_identity(java_causal)}
    others = []
    other_identities = []
    for other_path, other_slot in compare:
        other_packet, other_identity = _authenticated_packet(other_path)
        other_samples = samples_from_packet(other_packet, other_slot)
        if len(other_samples) < 3:
            continue
        others.append({
            "case": (other_packet.get("case") or {}).get("id")
                or Path(other_path).stem,
            "slot": other_slot, "report": mine(other_samples),
        })
        other_identities.append({**other_identity, "slot": other_slot})
    proposal = None
    proposal_identity = None
    if proposal_path is not None:
        proposal_path = proposal_path.expanduser().resolve()
        if not proposal_path.is_file():
            raise ValueError(f"missing proposed state: {proposal_path}")
        proposal = json.loads(proposal_path.read_text(encoding="utf-8"))
        proposal_identity = {"path": str(proposal_path),
                             **file_identity(proposal_path)}
    request = {
        "schema": STATE_MACHINE_SCHEMA,
        "implementation": {
            name: file_identity(Path(__file__).with_name(name))
            for name in IMPLEMENTATION
        },
        "packet": packet_identity, "slot": slot,
        "java_causal": java_identity, "java_unit": java_unit,
        "compare": other_identities, "proposal": proposal_identity,
    }
    request_sha256 = canonical_digest(request)
    artifact_root = artifact_root.expanduser().resolve()
    run_root = artifact_root / "runs" / request_sha256
    manifest_path = run_root / "manifest.json"
    if manifest_path.is_file():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if manifest.get("request_sha256") != request_sha256 \
                or canonical_digest(manifest.get("request")) != request_sha256:
            raise ValueError("cached state-machine request identity changed")
        for relative, expected in manifest["artifacts"].items():
            path = run_root / relative
            if not path.is_file() or file_identity(path) != expected:
                raise ValueError(f"state-machine artifact changed: {path}")
        _write(artifact_root / "latest.json", _json(manifest["pointer"]))
        return int(manifest["exit_code"]), run_root
    report = analyse(
        packet, slot, java_events=java_events, java_unit=java_unit,
        others=others, proposal=proposal,
    )
    status = 1 if report.get("status") == "mined" else 2
    if report.get("status") == "insufficient-window":
        status = 1
    run_root.mkdir(parents=True, exist_ok=True)
    report_path = run_root / "STATE-MACHINE.json"
    summary_path = run_root / "STATE-MACHINE.md"
    _write(report_path, _json(report))
    _write(summary_path, format_report(report))
    pointer = {
        "schema": STATE_MACHINE_SCHEMA, "request_sha256": request_sha256,
        "run": str(run_root.relative_to(artifact_root)),
        "case": report.get("case"), "slot": slot,
        "status": report.get("status"),
        "signals": [signal["signal"] for signal in report.get("signals", [])],
        "exit_code": status,
    }
    manifest = {
        "schema": STATE_MACHINE_SCHEMA, "request_sha256": request_sha256,
        "request": request, "exit_code": status, "pointer": pointer,
        "artifacts": inventory_files(run_root, [report_path, summary_path]),
    }
    _write(manifest_path, _json(manifest))
    _write(artifact_root / "latest.json", _json(pointer))
    return status, run_root


def capture_plan(reason: str, *, case: str | None = None,
        scenario: str | None = None, seed: int | None = None,
        cycles: int | None = None, slot: int | None = None) -> dict[str, Any]:
    """What to capture when the window cannot answer the question.

    A short window, an absent slot, or a fixture that stops before the
    consequence all produce this rather than a confident report over two
    cycles of evidence.
    """
    return {
        "reason": reason,
        "capture_command": (
            "python3 tools/bne-harness/scripts/bne_java.py packet \\\n"
            "  SURVEY.json --case " + (case or "CASE") + " \\\n"
            "  --output-dir work/packets/" + (case or "CASE") + " \\\n"
            "  --before 12 --after 12"
        ),
        "then": (
            "python3 tools/bne-harness/scripts/bne_java.py state-machine \\\n"
            "  --packet work/packets/" + (case or "CASE") + "/packet.json \\\n"
            "  --slot " + (str(slot) if slot is not None else "NATIVE_SLOT")
        ),
        "requires": [
            "an authenticated packet whose window covers the whole sequence",
            "the focused native slot present in that window",
            "at least three cycles of that slot's record",
        ],
        "scenario": scenario, "seed": seed, "cycles": cycles,
    }


def _iter_dicts(values: Iterable[Any]) -> list[dict[str, Any]]:
    return [value for value in values if isinstance(value, dict)]
