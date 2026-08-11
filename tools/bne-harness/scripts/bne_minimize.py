#!/usr/bin/env python3
"""Delta-debugging primitives and packet-derived BNE minimization plans."""

from __future__ import annotations

import copy
import json
from pathlib import Path
import subprocess
import tempfile
from typing import Any, Callable, TypeVar

from bne_causal import CausalEvent, align_events


MINIMIZE_SCHEMA = 1
T = TypeVar("T")


def ddmin(items: list[T], preserves_failure: Callable[[list[T]], bool], *,
        max_tests: int = 512) -> tuple[list[T], dict[str, Any]]:
    """Return a one-minimal failure-inducing subset using classic ddmin."""
    if max_tests <= 0:
        raise ValueError("max_tests must be positive")
    tests = 0

    def check(candidate: list[T]) -> bool:
        nonlocal tests
        if tests >= max_tests:
            return False
        tests += 1
        return preserves_failure(candidate)

    current = list(items)
    if not check(current):
        raise ValueError("the original input does not preserve the failure")
    granularity = 2
    history = [{"size": len(current), "result": "original-preserves"}]
    while len(current) >= 2 and tests < max_tests:
        chunk_size = (len(current) + granularity - 1) // granularity
        chunks = [current[index:index + chunk_size]
                  for index in range(0, len(current), chunk_size)]
        reduced = False
        offset = 0
        for chunk in chunks:
            complement = current[:offset] + current[offset + len(chunk):]
            offset += len(chunk)
            if check(complement):
                history.append({
                    "size": len(complement), "result": "complement-preserves",
                    "removed": len(chunk),
                })
                current = complement
                granularity = max(2, granularity - 1)
                reduced = True
                break
        if reduced:
            continue
        for chunk in chunks:
            if check(list(chunk)):
                history.append({
                    "size": len(chunk), "result": "subset-preserves",
                    "removed": len(current) - len(chunk),
                })
                current = list(chunk)
                granularity = 2
                reduced = True
                break
        if reduced:
            continue
        if granularity >= len(current):
            break
        granularity = min(len(current), granularity * 2)
    return current, {
        "schema": MINIMIZE_SCHEMA,
        "original_count": len(items), "minimal_count": len(current),
        "tests": tests, "max_tests": max_tests, "history": history,
        "budget_exhausted": tests >= max_tests,
    }


def _divergence_signature(alignment: dict[str, Any]) -> dict[str, Any] | None:
    first = alignment.get("first_divergence")
    if first is None:
        return None
    native = first.get("native") or {}
    java = first.get("java") or {}
    return {
        "op": first.get("op"),
        "native_kind": native.get("kind"),
        "java_kind": java.get("kind"),
        "difference_fields": sorted(first.get("differences", {})),
    }


def minimize_causal_slice(native: list[CausalEvent], java: list[CausalEvent], *,
        max_tests: int = 256) -> dict[str, Any]:
    """Minimize two event streams while preserving their first-cause shape."""
    original = align_events(native, java)
    signature = _divergence_signature(original)
    if signature is None:
        return {
            "schema": MINIMIZE_SCHEMA, "status": "already-aligned",
            "native": [item.as_dict() for item in native],
            "java": [item.as_dict() for item in java],
            "tests": 0,
        }
    tagged = [("native", item) for item in native] \
        + [("java", item) for item in java]

    def preserves(candidate: list[tuple[str, CausalEvent]]) -> bool:
        candidate_native = [item for side, item in candidate if side == "native"]
        candidate_java = [item for side, item in candidate if side == "java"]
        if not candidate_native and not candidate_java:
            return False
        return _divergence_signature(
            align_events(candidate_native, candidate_java)
        ) == signature

    minimal, report = ddmin(tagged, preserves, max_tests=max_tests)
    selected_native = [item for side, item in minimal if side == "native"]
    selected_java = [item for side, item in minimal if side == "java"]
    return {
        "schema": MINIMIZE_SCHEMA,
        "status": "minimized",
        "preserved_signature": signature,
        "original": {"native": len(native), "java": len(java)},
        "minimal": {"native": len(selected_native), "java": len(selected_java)},
        "reduction_percent": round(
            100 * (1 - len(minimal) / len(tagged)), 2
        ) if tagged else 0.0,
        "native": [item.as_dict() for item in selected_native],
        "java": [item.as_dict() for item in selected_java],
        "alignment": align_events(selected_native, selected_java),
        "proof": report,
    }


def minimize_scenario(scenario: dict[str, Any],
        preserves_failure: Callable[[dict[str, Any]], bool], *,
        categories: tuple[str, ...] = ("commands", "units", "terrain"),
        max_tests: int = 512) -> tuple[dict[str, Any], dict[str, Any]]:
    current = copy.deepcopy(scenario)
    reports = {}
    remaining = max_tests
    for category in categories:
        values = current.get(category)
        if not isinstance(values, list) or not values or remaining <= 0:
            continue

        def predicate(candidate: list[Any]) -> bool:
            trial = copy.deepcopy(current)
            trial[category] = candidate
            return preserves_failure(trial)

        minimized, report = ddmin(values, predicate, max_tests=remaining)
        current[category] = minimized
        reports[category] = report
        remaining -= report["tests"]
    return current, {
        "schema": MINIMIZE_SCHEMA,
        "categories": reports,
        "tests": max_tests - remaining,
        "original": {key: len(scenario.get(key, []))
                     for key in categories if isinstance(scenario.get(key), list)},
        "minimal": {key: len(current.get(key, []))
                    for key in categories if isinstance(current.get(key), list)},
    }


class CommandPredicate:
    """Safe subprocess adapter: exit zero means the divergence remains."""

    def __init__(self, command: list[str], *, timeout: float = 120.0):
        if not command or not any("{candidate}" in item for item in command):
            raise ValueError("predicate command requires a {candidate} placeholder")
        if timeout <= 0:
            raise ValueError("predicate timeout must be positive")
        self.command = list(command)
        self.timeout = timeout

    def __call__(self, candidate: dict[str, Any]) -> bool:
        with tempfile.TemporaryDirectory(prefix="bne-minimize-") as directory:
            path = Path(directory) / "candidate.json"
            path.write_text(json.dumps(candidate, indent=2, sort_keys=True) + "\n",
                            encoding="utf-8")
            command = [item.replace("{candidate}", str(path))
                       for item in self.command]
            completed = subprocess.run(
                command, capture_output=True, text=True, timeout=self.timeout,
                check=False,
            )
            return completed.returncode == 0


def plan_from_packet(packet: dict[str, Any]) -> dict[str, Any]:
    """Create a conservative reducer seed without mutating a sealed fixture."""
    case_id = packet.get("case", {}).get("id")
    cycle = packet.get("divergence", {}).get("cycle")
    semantic = packet.get("semantic", {})
    focus_units = set()
    neighbour_units = set()
    for cycle_record in semantic.values():
        for item in cycle_record.get("focus", []):
            if isinstance(item.get("native_slot"), int):
                focus_units.add(item["native_slot"])
        for item in cycle_record.get("nearby", []):
            if isinstance(item.get("native_slot"), int):
                neighbour_units.add(item["native_slot"])
    windows = []
    for state in packet.get("native_state", {}).values():
        for name, window in state.get("map", {}).get("windows", {}).items():
            if not isinstance(window, dict):
                continue
            bounds = {key: window.get(key) for key in
                      ("left", "top", "right", "bottom")}
            windows.append({"name": name, **bounds})
    unique_windows = {json.dumps(item, sort_keys=True): item for item in windows}
    return {
        "schema": MINIMIZE_SCHEMA,
        "case": case_id,
        "divergence_cycle": cycle,
        "immutable_source": True,
        "requires_new_fixture_capture": True,
        "must_retain_units": sorted(focus_units),
        "initial_neighbour_units": sorted(neighbour_units - focus_units),
        "map_windows": [unique_windows[key] for key in sorted(unique_windows)],
        "reduction_order": [
            "commands after the divergence",
            "units outside focused map windows",
            "unreferenced players and AI requests",
            "terrain outside the causal corridor",
            "commands before the first causal event",
        ],
        "acceptance": [
            "capture a new fixture identity for every candidate scenario",
            "preserve the same normalized causal divergence",
            "never modify or replace the original sealed fixture",
        ],
    }
