#!/usr/bin/env python3
"""Coverage accounting and legal differential-experiment generation."""

from __future__ import annotations

import hashlib
import json
import re
from typing import Any, Iterable

from bne_causal import CausalEvent


COVERAGE_SCHEMA = 1
_ADDRESS = re.compile(r"^(?:0x)?[0-9a-fA-F]{6,16}$")


def _address(value: Any) -> int | None:
    if isinstance(value, int):
        return value
    if isinstance(value, str) and _ADDRESS.fullmatch(value):
        try:
            return int(value, 16)
        except ValueError:
            return None
    return None


def event_tokens(event: CausalEvent) -> set[str]:
    tokens = {"kind:" + event.kind}
    if event.subject:
        tokens.add("subject:" + event.subject.split(":", 1)[0])
    for key in ("caller", "target", "site", "address", "block", "jump"):
        address = _address(event.fields.get(key))
        if address is not None:
            tokens.add(f"address:0x{address:08x}")
    if event.kind.startswith("path."):
        result = event.fields.get("result")
        if result is not None:
            tokens.add("path-result:" + str(result))
    return tokens


def coverage_report(events: Iterable[CausalEvent],
        baseline_tokens: Iterable[str] = ()) -> dict[str, Any]:
    baseline = set(baseline_tokens)
    all_tokens = set()
    by_kind: dict[str, int] = {}
    for event in events:
        all_tokens.update(event_tokens(event))
        by_kind[event.kind] = by_kind.get(event.kind, 0) + 1
    novel = all_tokens - baseline
    return {
        "schema": COVERAGE_SCHEMA,
        "event_count": sum(by_kind.values()),
        "token_count": len(all_tokens),
        "novel_count": len(novel),
        "tokens": sorted(all_tokens),
        "novel_tokens": sorted(novel),
        "by_kind": dict(sorted(by_kind.items())),
        "digest": hashlib.sha256(
            json.dumps(sorted(all_tokens), separators=(",", ":")).encode("utf-8")
        ).hexdigest(),
    }


def merge_coverage(reports: Iterable[dict[str, Any]]) -> dict[str, Any]:
    tokens = set()
    by_kind: dict[str, int] = {}
    event_count = 0
    for report in reports:
        tokens.update(report.get("tokens", []))
        event_count += int(report.get("event_count", 0))
        for kind, count in report.get("by_kind", {}).items():
            by_kind[kind] = by_kind.get(kind, 0) + int(count)
    return {
        "schema": COVERAGE_SCHEMA, "event_count": event_count,
        "token_count": len(tokens), "tokens": sorted(tokens),
        "by_kind": dict(sorted(by_kind.items())),
        "digest": hashlib.sha256(
            json.dumps(sorted(tokens), separators=(",", ":")).encode("utf-8")
        ).hexdigest(),
    }


def uncovered_control_transfers(function: dict[str, Any],
        coverage_tokens: Iterable[str]) -> list[dict[str, Any]]:
    covered = set(coverage_tokens)
    result = []
    for operation in function.get("control_transfers", []):
        for role in ("addr", "jump", "fail"):
            address = operation.get(role)
            if not isinstance(address, int):
                continue
            token = f"address:0x{address:08x}"
            if token not in covered:
                result.append({
                    "address": address, "role": role,
                    "instruction": operation.get("opcode"), "token": token,
                })
    unique = {item["token"] + ":" + item["role"]: item for item in result}
    return [unique[key] for key in sorted(unique)]


def command_variants_from_packet(packet: dict[str, Any], *,
        maximum: int = 64) -> dict[str, Any]:
    """Generate legal move-command candidates near a packet's focused unit."""
    if maximum <= 0:
        raise ValueError("maximum must be positive")
    cycle = int(packet["divergence"]["cycle"])
    focus = []
    for cycle_record in packet.get("semantic", {}).values():
        focus.extend(cycle_record.get("focus", []))
    seeds = {}
    for item in focus:
        slot = item.get("native_slot")
        native = item.get("oracle")
        if not isinstance(slot, int) or not isinstance(native, dict):
            continue
        x, y = native.get("x"), native.get("y")
        if isinstance(x, int) and isinstance(y, int):
            seeds[slot] = (x, y)
    variants = []
    offsets = [
        (-1, -1), (0, -1), (1, -1), (-1, 0),
        (1, 0), (-1, 1), (0, 1), (1, 1),
        (-2, 0), (2, 0), (0, -2), (0, 2),
    ]
    for slot, (x, y) in sorted(seeds.items()):
        for command_cycle in sorted({max(1, cycle - 2), max(1, cycle - 1), cycle}):
            for dx, dy in offsets:
                target_x, target_y = x + dx, y + dy
                if not (0 <= target_x <= 127 and 0 <= target_y <= 127):
                    continue
                text = (f"cycle {command_cycle} move unit {slot} "
                        f"x {target_x} y {target_y}")
                digest = hashlib.sha256(text.encode("ascii")).hexdigest()[:12]
                variants.append({
                    "id": "move-" + digest, "command": text,
                    "cycle": command_cycle, "unit": slot,
                    "x": target_x, "y": target_y,
                    "priority": abs(dx) + abs(dy),
                })
    variants.sort(key=lambda item: (
        item["priority"], item["cycle"], item["unit"], item["y"], item["x"]
    ))
    return {
        "schema": COVERAGE_SCHEMA,
        "case": packet.get("case", {}).get("id"),
        "source_fixture_immutable": True,
        "variant_count": min(maximum, len(variants)),
        "variants": variants[:maximum],
        "capture_requirements": [
            "each command stream receives a new fixture identity",
            "retain native causal events and basic-block coverage",
            "prioritize novel coverage before semantic divergence severity",
            "seal useful variants before Java comparison",
        ],
    }
