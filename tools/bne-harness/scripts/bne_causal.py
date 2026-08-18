#!/usr/bin/env python3
"""Normalized causal events and deterministic native/Java trace alignment.

The ordinary parity comparator finds the first *visible* state mismatch.  This
module gives native diagnostics, Java diagnostics, and packet observations one
small event ABI so the lab can instead identify the earliest unmatched cause.
It is deliberately dependency-free and offline: traces remain evidence, while
alignment is a reproducible report over authenticated files.
"""

from __future__ import annotations

from dataclasses import dataclass, field
import json
import re
import shlex
from typing import Any, Iterable


CAUSAL_SCHEMA = 1
_INTEGER = re.compile(r"^-?(?:0x[0-9a-fA-F]+|\d+)$")
_JAVA_PREFIX = re.compile(r"^(J[A-Z][A-Z0-9_]*)\s*(.*)$")
_RAND_LINE = re.compile(r"^(\d+)\s+(-?\d+)\s+(\S+)(?:\s+(.*))?$")


NATIVE_KIND = {
    "async-random": "rng.async.draw",
    "sync-random": "rng.sync.draw",
    "master-seed-call": "rng.sync.seed",
    "async-seed": "rng.async.seed",
    "unit-route": "path.route",
    "unit-map-squares": "path.map-window",
    "unit-map-components": "path.components",
    "unit-components": "path.components",
    "internal-order": "order.transition",
    "command-applied": "command.apply",
    "command-rejected": "command.reject",
    "command-unit-state": "state.command-unit",
    "idle-dispatch": "animation.idle",
    "projectile-created": "projectile.create",
    "mobile-damage": "combat.damage",
    "unit-build-candidate": "build.candidate",
    "unit-find-ai-wood": "resource.wood-search",
    "unit-ai-home-enter": "ai.home.enter",
    "unit-ai-home-exit": "ai.home.exit",
    "unit-ai-home-search": "ai.home.search",
    "unit-ai-behavior": "ai.behavior",
    "unit-scheduler": "scheduler.unit",
    "ai-build-state": "ai.build-state",
}


JAVA_KIND = {
    "JBNEPATH": "path.route",
    "JBNESTEP": "movement.step",
    "JBNEOCC": "path.occupancy",
    "JBNEIDLE": "animation.idle",
    "JBNEATTACKSEQ": "animation.attack",
    "JBNEATTACKMARKER": "animation.attack-marker",
    "JBNETARGET": "combat.target",
    "JBNEAUTO": "combat.auto-target",
    "JBNEREADY": "ai.ready",
    "JBNEREADYGOLD": "resource.gold-ready",
    "JBNEREADYOIL": "resource.oil-ready",
    "JBNEREADYCENSUS": "ai.ready-census",
    "JBNEBURN": "build.burn",
    "JBNETRANSPORT": "transport.transition",
    "JBNENAVAL": "ai.naval",
    "JBNEPATROL": "ai.patrol",
    "JBNEHOME": "ai.home",
    "JBNEGROUP": "ai.group",
}


def _value(text: str) -> Any:
    lowered = text.lower()
    if lowered in ("true", "false"):
        return lowered == "true"
    if _INTEGER.fullmatch(text):
        try:
            return int(text, 16 if text.lower().startswith(("0x", "-0x")) else 10)
        except ValueError:
            pass
    if "," in text:
        pieces = text.split(",")
        if all(_INTEGER.fullmatch(piece) for piece in pieces):
            return [int(piece, 16 if piece.lower().startswith(("0x", "-0x"))
                        else 10) for piece in pieces]
    return text


def parse_fields(text: str) -> dict[str, Any]:
    """Parse the harness's shell-like ``key=value`` diagnostics."""
    fields: dict[str, Any] = {}
    try:
        tokens = shlex.split(text, comments=False, posix=True)
    except ValueError:
        tokens = text.split()
    for token in tokens:
        if "=" not in token:
            continue
        key, value = token.split("=", 1)
        if key:
            if key in {"caller", "address", "site", "target"} \
                    and re.fullmatch(r"[0-9a-fA-F]{8}", value):
                fields[key] = "0x" + value.lower()
            else:
                fields[key] = _value(value)
    return fields


def _subject(fields: dict[str, Any]) -> str | None:
    for key in ("unit", "worker", "attacker", "source", "tower", "ship",
                "player"):
        value = fields.get(key)
        if isinstance(value, (int, str)):
            return f"{key}:{value}"
    return None


@dataclass(frozen=True)
class CausalEvent:
    side: str
    ordinal: int
    kind: str
    cycle: int | None = None
    subject: str | None = None
    fields: dict[str, Any] = field(default_factory=dict)
    source: str | None = None
    raw: str | None = None

    def as_dict(self) -> dict[str, Any]:
        return {
            "side": self.side,
            "ordinal": self.ordinal,
            "kind": self.kind,
            "cycle": self.cycle,
            "subject": self.subject,
            "fields": self.fields,
            "source": self.source,
            "raw": self.raw,
        }


def parse_native_trace(text: str, source: str = "native-trace") \
        -> list[CausalEvent]:
    events: list[CausalEvent] = []
    last_cycle: int | None = None
    for line in text.splitlines():
        marker = "# bne-trace "
        if marker not in line:
            continue
        body = line.split(marker, 1)[1]
        fields = parse_fields(body)
        native_name = fields.pop("event", None)
        if not isinstance(native_name, str):
            continue
        cycle = fields.get("cycle")
        if isinstance(cycle, int):
            last_cycle = cycle
        elif last_cycle is not None:
            # Older projectile-created lines omitted cycle=. They sit
            # between that tick's async-random draws, so the preceding
            # numbered event is the constructor cycle.
            cycle = last_cycle
        events.append(CausalEvent(
            side="native", ordinal=len(events),
            kind=NATIVE_KIND.get(native_name, "native." + native_name),
            cycle=cycle if isinstance(cycle, int) else None,
            subject=_subject(fields), fields=fields, source=source, raw=line,
        ))
    return events


def parse_java_trace(text: str, source: str = "java-diagnostic") \
        -> list[CausalEvent]:
    events: list[CausalEvent] = []
    for line in text.splitlines():
        match = _JAVA_PREFIX.match(line.strip())
        if match is None:
            continue
        prefix, body = match.groups()
        fields = parse_fields(body)
        cycle = fields.get("cycle")
        events.append(CausalEvent(
            side="java", ordinal=len(events),
            kind=JAVA_KIND.get(prefix, "java." + prefix[1:].lower()),
            cycle=cycle if isinstance(cycle, int) else None,
            subject=_subject(fields), fields=fields, source=source, raw=line,
        ))
    return events


def parse_java_random_trace(text: str, source: str = "java-random") \
        -> list[CausalEvent]:
    events: list[CausalEvent] = []
    for line in text.splitlines():
        match = _RAND_LINE.match(line.strip())
        if match is None:
            continue
        cycle, value, caller, context = match.groups()
        fields: dict[str, Any] = {"value": int(value), "caller": caller}
        if context:
            fields["context"] = context
        events.append(CausalEvent(
            side="java", ordinal=len(events), kind="rng.draw",
            cycle=int(cycle), fields=fields, source=source, raw=line,
        ))
    return events


def parse_causal_jsonl(text: str, *, expected_side: str | None = None,
        source: str = "causal-jsonl") -> list[CausalEvent]:
    events = []
    for line_number, line in enumerate(text.splitlines(), 1):
        if not line.strip():
            continue
        try:
            record = json.loads(line)
        except json.JSONDecodeError as error:
            raise ValueError(f"invalid causal JSONL at line {line_number}") from error
        if record.get("schema") != CAUSAL_SCHEMA:
            raise ValueError(f"unsupported causal event schema at line {line_number}")
        side = record.get("side")
        if side not in ("native", "java") or expected_side not in (None, side):
            raise ValueError(f"unexpected causal event side at line {line_number}")
        if not isinstance(record.get("kind"), str) \
                or not isinstance(record.get("fields", {}), dict):
            raise ValueError(f"malformed causal event at line {line_number}")
        events.append(CausalEvent(
            side=side, ordinal=int(record.get("ordinal", len(events))),
            kind=record["kind"],
            cycle=record.get("cycle") if isinstance(record.get("cycle"), int) else None,
            subject=record.get("subject"), fields=record.get("fields", {}),
            source=source, raw=line,
        ))
    return events


def events_from_packet(packet: dict[str, Any]) \
        -> tuple[list[CausalEvent], list[CausalEvent]]:
    """Adapt paired semantic observations in a divergence packet."""
    native: list[CausalEvent] = []
    java: list[CausalEvent] = []
    semantic = packet.get("semantic", {})
    for cycle_text in sorted(semantic, key=lambda value: int(value)):
        cycle = int(cycle_text)
        focus = semantic[cycle_text].get("focus", [])
        for item in focus:
            native_slot = item.get("native_slot")
            java_id = item.get("java_id")
            pair = f"pair:{native_slot}:{java_id}"
            for side, destination, key in (
                    ("native", native, "oracle"), ("java", java, "java")):
                observed = item.get(key)
                if not isinstance(observed, dict):
                    continue
                destination.append(CausalEvent(
                    side=side, ordinal=len(destination),
                    kind="state.observation", cycle=cycle, subject=pair,
                    fields=observed, source="packet.semantic",
                ))
    return native, java


def _comparable_fields(event: CausalEvent) -> dict[str, Any]:
    ignored = {"cycle", "caller", "address", "return", "native-slot",
               "java-id"}
    return {key: value for key, value in event.fields.items()
            if key not in ignored}


def _field_differences(native: CausalEvent, java: CausalEvent) \
        -> dict[str, dict[str, Any]]:
    left = _comparable_fields(native)
    right = _comparable_fields(java)
    result = {}
    for key in sorted(set(left) | set(right)):
        if left.get(key) != right.get(key):
            result[key] = {"native": left.get(key), "java": right.get(key)}
    return result


def _substitution_cost(native: CausalEvent, java: CausalEvent) -> float:
    if native.kind != java.kind:
        return 1.25
    cost = 0.0
    if native.cycle is not None and java.cycle is not None:
        cost += min(abs(native.cycle - java.cycle) * 0.15, 0.75)
    if native.subject and java.subject and native.subject != java.subject:
        cost += 0.4
    if _field_differences(native, java):
        cost += 0.35
    return cost


def align_events(native: Iterable[CausalEvent], java: Iterable[CausalEvent]) \
        -> dict[str, Any]:
    """Globally align two event streams with deterministic edit distance."""
    left = list(native)
    right = list(java)
    rows, columns = len(left) + 1, len(right) + 1
    costs = [[0.0] * columns for _ in range(rows)]
    moves = [[""] * columns for _ in range(rows)]
    for i in range(1, rows):
        costs[i][0], moves[i][0] = float(i), "native-only"
    for j in range(1, columns):
        costs[0][j], moves[0][j] = float(j), "java-only"
    priority = {"pair": 0, "native-only": 1, "java-only": 2}
    for i in range(1, rows):
        for j in range(1, columns):
            choices = [
                (costs[i - 1][j - 1] + _substitution_cost(
                    left[i - 1], right[j - 1]), "pair"),
                (costs[i - 1][j] + 1.0, "native-only"),
                (costs[i][j - 1] + 1.0, "java-only"),
            ]
            costs[i][j], moves[i][j] = min(
                choices, key=lambda item: (round(item[0], 9), priority[item[1]])
            )

    operations: list[dict[str, Any]] = []
    i, j = len(left), len(right)
    while i or j:
        move = moves[i][j]
        if move == "pair":
            native_event, java_event = left[i - 1], right[j - 1]
            differences = _field_differences(native_event, java_event)
            equal = native_event.kind == java_event.kind \
                and native_event.cycle == java_event.cycle \
                and (not native_event.subject or not java_event.subject
                     or native_event.subject == java_event.subject) \
                and not differences
            operations.append({
                "op": "match" if equal else "mismatch",
                "native": native_event.as_dict(),
                "java": java_event.as_dict(),
                "differences": differences,
            })
            i -= 1
            j -= 1
        elif move == "native-only":
            operations.append({"op": move, "native": left[i - 1].as_dict()})
            i -= 1
        else:
            operations.append({"op": "java-only", "java": right[j - 1].as_dict()})
            j -= 1
    operations.reverse()
    first_index = next((index for index, item in enumerate(operations)
                        if item["op"] != "match"), None)
    first = operations[first_index] if first_index is not None else None
    return {
        "schema": CAUSAL_SCHEMA,
        "native_event_count": len(left),
        "java_event_count": len(right),
        "edit_cost": round(costs[-1][-1], 6),
        "matched": sum(item["op"] == "match" for item in operations),
        "first_divergence_index": first_index,
        "first_divergence": first,
        "operations": operations,
    }


def format_alignment(alignment: dict[str, Any]) -> str:
    lines = [
        "# BNE causal trace alignment", "",
        f"- Native events: **{alignment['native_event_count']}**",
        f"- Java events: **{alignment['java_event_count']}**",
        f"- Exact matches: **{alignment['matched']}**",
        f"- Edit cost: **{alignment['edit_cost']}**", "",
    ]
    first = alignment.get("first_divergence")
    if first is None:
        lines.extend(["## Result", "", "The normalized event streams match.", ""])
        return "\n".join(lines)
    lines.extend(["## First causal divergence", ""])
    native = first.get("native")
    java = first.get("java")
    if native:
        lines.append(
            f"- Native: `{native['kind']}` cycle `{native.get('cycle')}` "
            f"subject `{native.get('subject')}`"
        )
    if java:
        lines.append(
            f"- Java: `{java['kind']}` cycle `{java.get('cycle')}` "
            f"subject `{java.get('subject')}`"
        )
    for key, difference in first.get("differences", {}).items():
        lines.append(
            f"- `{key}`: native `{json.dumps(difference['native'])}`, "
            f"Java `{json.dumps(difference['java'])}`"
        )
    lines.extend(["", "This is an investigative lead; the regression gate remains proof.", ""])
    return "\n".join(lines)
