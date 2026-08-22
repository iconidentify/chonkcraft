#!/usr/bin/env python3
"""Seed-anchored alignment of the native and Java random-number ledgers.

An HP mismatch whose hit cadence and hit count already agree is a mismatch in
what the two engines rolled, and the only way to say which roll went wrong is
to put the two draw sequences side by side. Cycles cannot anchor that: the two
engines number their cycles from different places and a presentation-ahead
frame shifts every later one. The seeds can. Both streams are LCGs, so a draw
is completely described by the seed it started from, and two engines consuming
the same stream produce byte-identical `before -> after` transitions whatever
they were doing at the time.

So this module aligns on seed transitions, validates that each recorded
transition is really the LCG it claims to be, and then reports the last draw
the engines agreed on and the first one they did not -- as a missing draw, an
extra draw, a diverged seed, a reordering of the same consumers, or the same
seed spent by a different consumer.

Native records a return address and Java records a class and a method. Those
are not the same kind of name, and this module never claims they are. Where
the aligned evidence makes a pairing look consistent it is reported as an
observed correspondence with its support count, labelled derived rather than
proved.
"""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, field
import difflib
import json
import os
from pathlib import Path
import tempfile
from typing import Any, Iterable, Sequence

from bne_causal import CausalEvent, parse_causal_jsonl, parse_native_trace


LEDGER_SCHEMA = 1
MASK = 0xffffffff


@dataclass(frozen=True)
class Stream:
    """One of BNE's two generators, named by what a fixture calls it."""

    name: str
    kind: str
    multiplier: int
    increment: int
    #: Which seed the returned number is cut from, per side, and its mask.
    result_from: dict[str, tuple[str, int]] = field(default_factory=dict)

    def advance(self, before: int) -> int:
        return (before * self.multiplier + self.increment) & MASK


#: The asynchronous stream is the one both engines cut the same way, which is
#: why it is the default. The synchronized stream is here because the same
#: alignment works on it, but native takes its number from the advanced seed
#: while this port takes it from the seed as it stood, so the returned value is
#: checked per side and never compared across the two.
STREAMS = {
    "async": Stream(
        name="async", kind="rng.async.draw",
        multiplier=0x015a4e35, increment=1,
        result_from={"native": ("after", 0x7fff), "java": ("after", 0x7fff)},
    ),
    "sync": Stream(
        name="sync", kind="rng.sync.draw",
        multiplier=0x41c64e6d, increment=0x3039,
        result_from={"native": ("after", 0x7fff), "java": ("before", 0xffff)},
    ),
}


@dataclass(frozen=True)
class Draw:
    """One recorded number, with everything known about who asked for it."""

    side: str
    index: int
    before: int
    after: int
    result: int
    cycle: int | None = None
    ordinal: int | None = None
    caller: str | None = None
    caller_chain: str | None = None
    caller_line: int | None = None
    context: str | None = None
    source: str | None = None

    @property
    def transition(self) -> tuple[int, int]:
        """What alignment matches on: the seed step, and only the seed step.

        The returned number is a pure function of the seed, so it adds nothing
        here -- and on the synchronized stream the two engines cut it from
        opposite ends of the same step, so matching on it reported four
        identical draws as four diverged seeds. It is still checked, per side,
        against the rule that side is supposed to follow.
        """
        return (self.before, self.after)

    def as_dict(self) -> dict[str, Any]:
        return {
            "side": self.side, "index": self.index, "cycle": self.cycle,
            "ordinal": self.ordinal, "before": self.before,
            "after": self.after, "result": self.result,
            "caller": self.caller, "caller_chain": self.caller_chain,
            "caller_line": self.caller_line, "context": self.context,
        }


def _integer(value: Any) -> int | None:
    if isinstance(value, bool) or not isinstance(value, int):
        return None
    return value & MASK if value < 0 else value


def draws_from_events(events: Iterable[CausalEvent], side: str, *,
        stream: str = "async") -> list[Draw]:
    """Take the draws of one stream out of a normalized causal event list.

    Events missing a seed transition are dropped rather than guessed at: an
    incomplete record is evidence about the tracer, not about the game.
    """
    if stream not in STREAMS:
        raise ValueError(f"unknown random stream: {stream}")
    kind = STREAMS[stream].kind
    draws: list[Draw] = []
    for event in events:
        if event.kind != kind or event.side != side:
            continue
        fields = event.fields
        before = _integer(fields.get("before"))
        after = _integer(fields.get("after"))
        result = _integer(fields.get("result"))
        if before is None or after is None or result is None:
            continue
        caller = fields.get("caller")
        if isinstance(caller, int) and not isinstance(caller, bool):
            # A return address the field parser read as a number. Addresses
            # are read as addresses here, so one ledger cannot report
            # 0x00418370 and its neighbour 4293488 for the same call site.
            caller = f"0x{caller & MASK:08x}"
        draws.append(Draw(
            side=side, index=len(draws), before=before, after=after,
            result=result, cycle=event.cycle,
            ordinal=fields.get("draw") if isinstance(fields.get("draw"), int)
                else None,
            caller=str(caller) if caller is not None else None,
            caller_chain=(str(fields["caller_chain"])
                          if fields.get("caller_chain") else None),
            caller_line=(fields["caller_line"]
                         if isinstance(fields.get("caller_line"), int)
                         and fields["caller_line"] > 0 else None),
            context=(str(fields["context"]) if fields.get("context") else None),
            source=event.source,
        ))
    return draws


def parse_native_draws(text: str, *, stream: str = "async",
        source: str = "native-trace") -> list[Draw]:
    return draws_from_events(
        parse_native_trace(text, source=source), "native", stream=stream,
    )


def parse_java_draws(text: str, *, stream: str = "async",
        source: str = "java-causal") -> list[Draw]:
    return draws_from_events(
        parse_causal_jsonl(text, expected_side="java", source=source),
        "java", stream=stream,
    )


def invalid_transitions(draws: Sequence[Draw], stream: Stream) \
        -> list[dict[str, Any]]:
    """Every draw whose own record is not the generator it claims to be."""
    broken = []
    for draw in draws:
        expected_after = stream.advance(draw.before)
        problems = []
        if expected_after != draw.after:
            problems.append("seed transition")
        seed_name, mask = stream.result_from.get(draw.side, ("after", 0x7fff))
        seed = draw.after if seed_name == "after" else draw.before
        if (seed >> 16) & mask != draw.result:
            problems.append("returned value")
        if problems:
            broken.append({
                "draw": draw.as_dict(), "expected_after": expected_after,
                "problems": problems,
            })
    return broken


def _operations(native: Sequence[Draw], java: Sequence[Draw]) \
        -> list[dict[str, Any]]:
    """Align two draw sequences on their seed transitions alone."""
    left = [draw.transition for draw in native]
    right = [draw.transition for draw in java]
    matcher = difflib.SequenceMatcher(None, left, right, autojunk=False)
    operations: list[dict[str, Any]] = []
    for tag, i1, i2, j1, j2 in matcher.get_opcodes():
        if tag == "equal":
            for offset in range(i2 - i1):
                operations.append({
                    "op": "match",
                    "native": native[i1 + offset].as_dict(),
                    "java": java[j1 + offset].as_dict(),
                })
        elif tag == "delete":
            for index in range(i1, i2):
                operations.append({
                    "op": "native-only", "native": native[index].as_dict(),
                })
        elif tag == "insert":
            for index in range(j1, j2):
                operations.append({
                    "op": "java-only", "java": java[index].as_dict(),
                })
        else:
            operations.append({
                "op": "seed-diverged",
                "native": [native[index].as_dict() for index in range(i1, i2)],
                "java": [java[index].as_dict() for index in range(j1, j2)],
            })
    return operations


def _shared_capture_window(native: Sequence[Draw], java: Sequence[Draw]) \
        -> tuple[Sequence[Draw], Sequence[Draw], dict[str, int]]:
    """Remove only the unrecorded prefix before both hooks share a seed.

    Native campaign traces begin at ``scenario-loaded`` after the executable
    has constructed the map's unit pool. Java causal tracing also records
    those construction draws. Comparing both arrays from element zero turns
    that observation-boundary difference into hundreds of invented Java-only
    draws and prevents the ledger from reaching gameplay.

    The asynchronous LCG is a permutation, so one transition identifies one
    point in its chain. The earliest transition present on both sides is the
    first comparable draw; everything before it exists outside one capture's
    window and cannot support a parity claim. Interior and trailing draws are
    untouched and remain available to the ordinary mismatch classifier.
    """
    if not native or not java:
        return native, java, {"native": 0, "java": 0}
    java_positions: dict[tuple[int, int], int] = {}
    for index, draw in enumerate(java):
        java_positions.setdefault(draw.transition, index)
    common: tuple[int, int] | None = None
    for native_index, draw in enumerate(native):
        java_index = java_positions.get(draw.transition)
        if java_index is None:
            continue
        candidate = (native_index, java_index)
        if common is None or sum(candidate) < sum(common):
            common = candidate
    if common is None:
        return native, java, {"native": 0, "java": 0}
    native_prefix, java_prefix = common
    return (native[native_prefix:], java[java_prefix:],
            {"native": native_prefix, "java": java_prefix})


def _consumer_correspondence(matches: Sequence[dict[str, Any]]) \
        -> list[dict[str, Any]]:
    """What each native address looked like on the Java side, and how often.

    This is co-occurrence in one aligned pair of ledgers. It says nothing
    about what the native address is, which is why every record here carries
    ``proved: false``.
    """
    seen: dict[str, Counter] = {}
    for pair in matches:
        native_caller = pair["native"].get("caller")
        java_caller = pair["java"].get("caller")
        if native_caller is None or java_caller is None:
            continue
        seen.setdefault(str(native_caller), Counter())[str(java_caller)] += 1
    correspondence = []
    for native_caller in sorted(seen):
        counts = seen[native_caller]
        best = min(counts.items(), key=lambda item: (-item[1], item[0]))
        correspondence.append({
            "native_caller": native_caller,
            "java_caller": best[0],
            "support": best[1],
            "alternatives": {name: counts[name]
                             for name in sorted(counts) if name != best[0]},
            "derived_from_this_evidence": True,
            "proved": False,
        })
    return correspondence


def _java_consumer_family(caller: str) -> str:
    """Return the native-equivalent RNG consumer represented by Java code.

    BNE rolls physical projectile and melee damage through the same native
    routine.  The port keeps those entry points separate because projectiles
    resolve later, but that Java class boundary is not a random-stream
    boundary and must not manufacture a consumer change in the ledger.
    """
    if caller in {
        "BattleNetProjectileSystem.battleNetProjectileDamage",
        "World.battleNetMeleeDamage",
    }:
        return "battle-net-physical-damage"
    return caller


def _consumer_divergence(matches: Sequence[dict[str, Any]]) \
        -> dict[str, Any] | None:
    """The first draw whose consumer stopped being the one it had always been.

    Both engines take numbers out of one shared generator, so an extra or a
    missing draw does not change the seeds -- it changes who spends them.
    Every draw after the extra one carries the seed its neighbour used to
    carry, and the seed chains stay identical while the ledgers stop meaning
    the same thing. What moves is the pairing, so that is what is watched.

    The pairing is learned from the agreeing prefix rather than assumed:
    the first time a native address is seen beside a Java method the two are
    provisionally partners, and the disagreement is the first draw that
    contradicts the Java consumer previously observed for that native call
    site.  This is intentionally one-way: a port may consolidate several
    native return addresses (for example projectile X/Y jitter) into one Java
    helper without changing which subsystem consumed the random numbers.
    """
    forward: dict[str, str] = {}
    expected: list[str] = []
    actual: list[str] = []
    usable: list[dict[str, Any]] = []
    contradiction: int | None = None
    for pair in matches:
        native_caller = pair["native"].get("caller")
        java_caller = pair["java"].get("caller")
        if native_caller is None or java_caller is None:
            continue
        native_caller, java_caller = str(native_caller), str(java_caller)
        java_consumer = _java_consumer_family(java_caller)
        index = len(usable)
        usable.append(pair)
        actual.append(java_consumer)
        if native_caller in forward:
            expected.append(forward[native_caller])
        else:
            expected.append(java_consumer)
            forward[native_caller] = java_consumer
        if contradiction is None and expected[index] != actual[index]:
            contradiction = index
    if contradiction is None:
        return None
    reordered = sorted(expected) == sorted(actual)
    return {
        "at_match_index": contradiction,
        "native": usable[contradiction]["native"],
        "java": usable[contradiction]["java"],
        "expected_java_caller": expected[contradiction],
        "observed_java_caller": actual[contradiction],
        "observed_java_method": usable[contradiction]["java"].get("caller"),
        "window_consumers_preserved": reordered,
    }


def _cycle_offset_break(matches: Sequence[dict[str, Any]]) -> int | None:
    """The first matched draw that stopped keeping the window's own offset.

    Not used to align anything. When the engines number their cycles a
    constant distance apart, the draw where that distance changes is the draw
    where one of them started spending numbers the other spent elsewhere.
    """
    baseline = None
    for index, pair in enumerate(matches):
        native_cycle = pair["native"].get("cycle")
        java_cycle = pair["java"].get("cycle")
        if not isinstance(native_cycle, int) or not isinstance(java_cycle, int):
            continue
        offset = native_cycle - java_cycle
        if baseline is None:
            baseline = offset
        elif offset != baseline:
            return index
    return None


def _context(draws: Sequence[Draw], index: int, span: int) \
        -> list[dict[str, Any]]:
    low = max(0, index - span)
    high = min(len(draws), index + span + 1)
    return [draws[position].as_dict() for position in range(low, high)]


def _mismatch_index(operation: dict[str, Any], side: str) -> int | None:
    value = operation.get(side)
    if isinstance(value, dict):
        return value["index"]
    if isinstance(value, list) and value:
        return value[0]["index"]
    return None


def _cycle_offsets(matches: Sequence[dict[str, Any]]) -> dict[str, Any]:
    """How far apart the two engines number the cycles they agree about.

    Reported, never used: alignment is by seed, so a constant or drifting
    offset is a finding rather than an obstacle.
    """
    offsets = Counter()
    for pair in matches:
        native_cycle = pair["native"].get("cycle")
        java_cycle = pair["java"].get("cycle")
        if isinstance(native_cycle, int) and isinstance(java_cycle, int):
            offsets[native_cycle - java_cycle] += 1
    if not offsets:
        return {"samples": 0, "modal": None, "stable": None, "counts": {}}
    modal = min(offsets.items(), key=lambda item: (-item[1], item[0]))[0]
    return {
        "samples": sum(offsets.values()), "modal": modal,
        "stable": len(offsets) == 1,
        "counts": {str(offset): offsets[offset] for offset in sorted(offsets)},
    }


def capture_plan(evidence: dict[str, Any] | None = None) -> dict[str, Any]:
    """The exact next command when no authenticated native ledger exists.

    Silently comparing a Java ledger against nothing would read as agreement,
    so the absent side produces a command instead of a conclusion.
    """
    evidence = evidence or {}
    scenario = evidence.get("scenario") or "Campaign\\Orc\\Orc01.pud"
    cycles = evidence.get("cycles") or 60
    seed = evidence.get("seed") if evidence.get("seed") is not None else 1
    case = evidence.get("case") or "CASE"
    command = (
        "python3 tools/bne-harness/scripts/bne_oracle.py run \\\n"
        "  --game-dir \"/path/to/Warcraft II BNE\" \\\n"
        "  --prefix work/oracle-prefix \\\n"
        f"  --trace work/traces/{case}.trace.txt \\\n"
        "  --source-manifest work/sources/SOURCE_ID/source-manifest.json \\\n"
        f"  --scenario '{scenario}' \\\n"
        f"  --seed {seed} --cycles {cycles}"
    )
    return {
        "reason": "no authenticated native asynchronous ledger was supplied",
        "capture_command": command,
        "then": (
            "python3 tools/bne-harness/scripts/bne_java.py rng-ledger \\\n"
            f"  --native-trace work/traces/{case}.trace.txt \\\n"
            "  --java-causal .bne-artifacts/runs/TRIAGE_SHA/.../CASE.causal.jsonl"
        ),
        "requires": [
            "the sibling TRACE.manifest.json the oracle writes beside the trace",
            "the pinned BNE 2.02b executable hash",
            "a network-disabled Wine prefix",
        ],
    }


def build_ledger(native_draws: Sequence[Draw] | None,
        java_draws: Sequence[Draw] | None, *, stream: str = "async",
        native_evidence: dict[str, Any] | None = None,
        java_evidence: dict[str, Any] | None = None,
        case: str | None = None, context: int = 3,
        capture_hint: dict[str, Any] | None = None) -> dict[str, Any]:
    """Align two authenticated ledgers and name the first disagreement."""
    if stream not in STREAMS:
        raise ValueError(f"unknown random stream: {stream}")
    definition = STREAMS[stream]
    report: dict[str, Any] = {
        "schema": LEDGER_SCHEMA,
        "stream": stream,
        "case": case,
        "native": {
            "draw_count": len(native_draws) if native_draws is not None else 0,
            "evidence": native_evidence,
            "authenticated": bool(native_evidence),
        },
        "java": {
            "draw_count": len(java_draws) if java_draws is not None else 0,
            "evidence": java_evidence,
        },
    }
    if not native_draws or native_evidence is None:
        report.update({
            "status": "native-evidence-missing",
            "classification": "native-evidence-missing",
            "next_experiment": capture_plan({
                **(capture_hint or {}), **(native_evidence or {}),
                "case": case,
            }),
            "operations": [],
        })
        if native_draws and native_evidence is None:
            report["next_experiment"]["reason"] = (
                "a native asynchronous ledger was supplied without the "
                "oracle manifest that authenticates it"
            )
        return report
    if not java_draws:
        report.update({
            "status": "java-evidence-missing",
            "classification": "java-evidence-missing",
            "next_experiment": {
                "reason": ("the Java run recorded no draw on this stream; "
                           "rerun it with CHONKCRAFT_TRACE_BNE_CAUSAL set"),
            },
            "operations": [],
        })
        return report

    native_invalid = invalid_transitions(native_draws, definition)
    java_invalid = invalid_transitions(java_draws, definition)
    report["validation"] = {
        "stream_multiplier": definition.multiplier,
        "stream_increment": definition.increment,
        "native_invalid": native_invalid,
        "java_invalid": java_invalid,
    }
    if native_invalid or java_invalid:
        report.update({
            "status": "malformed-evidence",
            "classification": "malformed-lcg-transition",
            "operations": [],
            "first_mismatch": (native_invalid or java_invalid)[0],
        })
        return report

    native_draws, java_draws, capture_prefix = _shared_capture_window(
        native_draws, java_draws)
    report["capture_prefix_excluded"] = capture_prefix
    report["native"]["compared_draw_count"] = len(native_draws)
    report["java"]["compared_draw_count"] = len(java_draws)
    operations = _operations(native_draws, java_draws)
    matches = [item for item in operations if item["op"] == "match"]
    report["cycle_offset"] = _cycle_offsets(matches)
    report["consumer_correspondence"] = _consumer_correspondence(matches)
    report["matched"] = len(matches)
    report["operations"] = operations

    surplus = len(java_draws) - len(native_draws)
    consumer = _consumer_divergence(matches)
    report["cycle_offset_break_at_match"] = _cycle_offset_break(matches)
    interior = next(
        (index for index, item in enumerate(operations)
         if item["op"] != "match" and index < len(operations) - 1
         and operations[index + 1]["op"] == "match"), None,
    )
    if interior is None:
        interior = next(
            (index for index, item in enumerate(operations)
             if item["op"] == "seed-diverged"), None,
        )
    if interior is not None:
        # The seed chains themselves parted company, which is a stronger
        # statement than a shifted consumer and comes first.
        operation = operations[interior]
        classification = {
            "java-only": "java-extra-draw",
            "native-only": "native-draw-missing-in-java",
            "seed-diverged": "seed-chain-diverged",
        }[operation["op"]]
        native_index = _mismatch_index(operation, "native")
        java_index = _mismatch_index(operation, "java")
        report.update({
            "status": "divergent", "classification": classification,
            "last_match": operations[interior - 1] if interior > 0 else None,
            "first_mismatch": operation,
            "context": {
                "native": _context(
                    native_draws,
                    native_index if native_index is not None
                    else len(native_draws) - 1, context),
                "java": _context(
                    java_draws,
                    java_index if java_index is not None
                    else len(java_draws) - 1, context),
            },
        })
        return report

    if consumer is not None:
        at = consumer["at_match_index"]
        if surplus > 0:
            classification = "java-extra-draw"
        elif surplus < 0:
            classification = "native-draw-missing-in-java"
        elif consumer["window_consumers_preserved"]:
            classification = "consumer-reordered"
        else:
            classification = "same-seed-different-consumer"
        report.update({
            "status": "divergent" if surplus else "consumer-divergent",
            "classification": classification,
            "surplus_java_draws": surplus,
            "last_match": matches[at - 1] if at > 0 else None,
            "first_mismatch": consumer,
            "context": {
                "native": _context(
                    native_draws, consumer["native"]["index"], context),
                "java": _context(
                    java_draws, consumer["java"]["index"], context),
            },
        })
        return report

    if surplus:
        # One ledger simply runs longer than the other and nothing inside the
        # shared window disagrees. Calling that a missing draw would turn a
        # short capture into a finding about the game.
        trailing = next(item for item in reversed(operations)
                        if item["op"] != "match")
        report.update({
            "status": "identical-prefix",
            "classification": "window-length-difference",
            "surplus_java_draws": surplus,
            "last_match": matches[-1] if matches else None,
            "first_mismatch": trailing,
        })
        return report

    report.update({
        "status": "identical", "classification": "identical",
        "last_match": matches[-1] if matches else None,
        "first_mismatch": None,
    })
    return report


_HEADLINE = {
    "identical": "The two ledgers spend the same seeds on the same consumers.",
    "consumer-reordered": "The same seeds are spent in a different order.",
    "same-seed-different-consumer":
        "The same seed is spent by a different consumer.",
    "java-extra-draw": "Java takes a draw native does not.",
    "native-draw-missing-in-java": "Native takes a draw Java does not.",
    "seed-chain-diverged":
        "The seeds themselves differ, so an earlier draw was already wrong.",
    "window-length-difference":
        "One ledger simply covers more of the run than the other.",
    "malformed-lcg-transition":
        "A recorded draw is not the generator it claims to be.",
    "native-evidence-missing": "There is no authenticated native ledger to align.",
    "java-evidence-missing": "The Java run recorded no draw on this stream.",
}


def _draw_line(draw: dict[str, Any] | None) -> str:
    if not draw:
        return "`none`"
    caller = draw.get("caller") or "?"
    # Native records no draw number, so its position in the ledger stands in
    # for one -- counted from one, like the engine's own, so the two lines of
    # the same pair do not read as two different draws.
    ordinal = draw.get("ordinal")
    label = ordinal if ordinal is not None else draw.get("index", 0) + 1
    return (f"draw `{label}` cycle `{draw.get('cycle')}` "
            f"seed `{draw['before']}` -> `{draw['after']}` result "
            f"`{draw['result']}` caller `{caller}`")


def format_ledger(report: dict[str, Any]) -> str:
    """The human-sized RNG-DIFF the agent reads before the JSON."""
    classification = report.get("classification", "unknown")
    lines = [
        "# BNE asynchronous RNG ledger" if report.get("stream") == "async"
        else f"# BNE {report.get('stream')} RNG ledger", "",
        f"- Case: `{report.get('case') or 'unknown'}`",
        f"- Native draws: **{report['native']['draw_count']}** "
        f"(authenticated: {str(report['native']['authenticated']).lower()})",
        f"- Java draws: **{report['java']['draw_count']}**",
        f"- Matched draws: **{report.get('matched', 0)}**",
        f"- Result: **{classification}**",
        "",
        _HEADLINE.get(classification, "Unclassified result."), "",
    ]
    offset = report.get("cycle_offset")
    if offset and offset.get("samples"):
        lines.extend([
            f"- Cycle offset (native minus Java): **{offset['modal']}**, "
            f"{'constant' if offset['stable'] else 'drifting'} over "
            f"{offset['samples']} matched draws",
            "",
        ])
    plan = report.get("next_experiment")
    if plan:
        lines.extend(["## Next experiment", "", plan["reason"], ""])
        if plan.get("capture_command"):
            lines.extend(["```sh", plan["capture_command"], "```", "",
                          "```sh", plan["then"], "```", ""])
        return "\n".join(lines)
    last = report.get("last_match")
    if last:
        lines.extend([
            "## Last agreed draw", "",
            f"- Native: {_draw_line(last.get('native'))}",
            f"- Java: {_draw_line(last.get('java'))}", "",
        ])
    first = report.get("first_mismatch")
    if first is None:
        lines.append("")
        return "\n".join(lines)
    lines.extend(["## First disagreement", ""])
    if classification in ("consumer-reordered", "same-seed-different-consumer"):
        lines.extend([
            f"- Native: {_draw_line(first.get('native'))}",
            f"- Java: {_draw_line(first.get('java'))}",
            f"- Java consumer expected from this evidence: "
            f"`{first['expected_java_caller']}`, observed "
            f"`{first['observed_java_caller']}`",
        ])
    elif classification == "malformed-lcg-transition":
        lines.extend([
            f"- {_draw_line(first.get('draw'))}",
            f"- Problems: `{', '.join(first.get('problems', []))}`",
            f"- Expected next seed: `{first.get('expected_after')}`",
        ])
    else:
        native = first.get("native")
        java = first.get("java")
        if isinstance(native, list):
            native = native[0] if native else None
        if isinstance(java, list):
            java = java[0] if java else None
        lines.extend([
            f"- Native: {_draw_line(native)}",
            f"- Java: {_draw_line(java)}",
        ])
    lines.extend([
        "",
        "A native return address and a Java method name are different kinds of "
        "name. Any correspondence below was observed in this pair of ledgers "
        "and is not a proved mapping.",
        "",
    ])
    correspondence = report.get("consumer_correspondence") or []
    if correspondence:
        lines.extend([
            "| Native caller | Java caller (observed) | Support |",
            "|---|---|---|",
        ])
        for item in correspondence:
            lines.append(
                f"| `{item['native_caller']}` | `{item['java_caller']}` | "
                f"{item['support']} |"
            )
        lines.append("")
    lines.extend([
        "This is an investigative lead. The full regression gate remains the "
        "acceptance authority.", "",
    ])
    return "\n".join(lines)


def ledger_from_text(native_text: str | None, java_text: str | None, *,
        stream: str = "async", native_evidence: dict[str, Any] | None = None,
        java_evidence: dict[str, Any] | None = None,
        case: str | None = None, context: int = 3) -> dict[str, Any]:
    """Convenience wrapper for callers that already hold both file bodies."""
    native = (parse_native_draws(native_text, stream=stream)
              if native_text else None)
    java = parse_java_draws(java_text, stream=stream) if java_text else None
    return build_ledger(
        native, java, stream=stream, native_evidence=native_evidence,
        java_evidence=java_evidence, case=case, context=context,
    )


def exit_code(report: dict[str, Any]) -> int:
    """0 agreed, 1 a lead was produced, 2 the evidence cannot be believed."""
    status = report.get("status")
    if status == "identical":
        return 0
    if status == "malformed-evidence":
        return 2
    return 1


IMPLEMENTATION = ("bne_rng_ledger.py", "bne_causal.py")


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


def run_rng_ledger(native_trace: Path | None, java_causal: Path,
        artifact_root: Path, *, stream: str = "async",
        case: str | None = None) -> tuple[int, Path]:
    """Produce a durable, content-addressed RNG-DIFF for one pair of ledgers.

    The native side is accepted only through the same oracle-manifest
    authentication the rest of the lab uses. An unauthenticated trace is not
    downgraded to a weaker comparison; it is refused, and the report says what
    to capture instead.
    """
    from bne_lab import verified_native_trace
    from bne_triage import canonical_digest, file_identity, inventory_files

    java_causal = java_causal.expanduser().resolve()
    if not java_causal.is_file():
        raise ValueError(f"missing Java causal trace: {java_causal}")
    java_evidence = {"path": str(java_causal), **file_identity(java_causal)}
    native_evidence = None
    native_text = None
    if native_trace is not None:
        native_trace = native_trace.expanduser().resolve()
        native_evidence = verified_native_trace(native_trace)
        native_text = native_trace.read_text(encoding="utf-8", errors="replace")
    request = {
        "schema": LEDGER_SCHEMA,
        "implementation": {
            name: file_identity(Path(__file__).with_name(name))
            for name in IMPLEMENTATION
        },
        "stream": stream,
        "case": case,
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
            raise ValueError("cached RNG ledger request identity changed")
        for relative, expected in manifest["artifacts"].items():
            path = run_root / relative
            if not path.is_file() or file_identity(path) != expected:
                raise ValueError(f"RNG ledger artifact changed: {path}")
        _write(artifact_root / "latest.json",
               json.dumps(manifest["pointer"], indent=2, sort_keys=True) + "\n")
        return int(manifest["exit_code"]), run_root
    report = ledger_from_text(
        native_text, java_causal.read_text(encoding="utf-8"),
        stream=stream, native_evidence=native_evidence,
        java_evidence=java_evidence, case=case,
    )
    status = exit_code(report)
    run_root.mkdir(parents=True, exist_ok=True)
    report_path = run_root / "RNG-DIFF.json"
    summary_path = run_root / "RNG-DIFF.md"
    _write(report_path, json.dumps(report, indent=2, sort_keys=True) + "\n")
    _write(summary_path, format_ledger(report))
    pointer = {
        "schema": LEDGER_SCHEMA, "request_sha256": request_sha256,
        "run": str(run_root.relative_to(artifact_root)),
        "case": case, "stream": stream,
        "classification": report.get("classification"),
        "exit_code": status,
    }
    manifest = {
        "schema": LEDGER_SCHEMA, "request_sha256": request_sha256,
        "request": request, "exit_code": status, "pointer": pointer,
        "artifacts": inventory_files(run_root, [report_path, summary_path]),
    }
    _write(manifest_path, json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    _write(artifact_root / "latest.json",
           json.dumps(pointer, indent=2, sort_keys=True) + "\n")
    return status, run_root
