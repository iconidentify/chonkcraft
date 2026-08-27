#!/usr/bin/env python3
"""Validate and render the Battle.net Edition subsystem readiness ledger."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any


GRADES = ("green", "yellow", "orange", "red")
TRACK_STATUSES = ("certified", "in-progress")
RETAIL_INPUTS = {"asset_pack", "opus_vectors", "wc2_install"}
REQUIRED_SYSTEMS = {
    "boot-data-assets",
    "scheduler-rng",
    "movement-pathfinding",
    "orders-attack-move",
    "idle-targeting",
    "harvest-economy",
    "construction-production",
    "combat-damage",
    "projectiles-feedback",
    "naval-oil",
    "spells",
    "retail-ai",
    "campaign-triggers",
    "save-load",
    "rendering-ui-input",
    "sound",
    "control-liveness",
    "network-lockstep",
}


class LedgerError(ValueError):
    """The readiness ledger makes a claim it cannot mechanically support."""


def load(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise LedgerError(f"cannot read {path}: {error}") from error
    if not isinstance(data, dict):
        raise LedgerError("the ledger root must be an object")
    return data


def _strings(value: Any, field: str, system: str, *, nonempty: bool = False) -> list[str]:
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        raise LedgerError(f"{system}.{field} must be a list of strings")
    if nonempty and not value:
        raise LedgerError(f"{system}.{field} must not be empty")
    return value


def validate(data: dict[str, Any], repository: Path) -> list[str]:
    """Return warnings after rejecting malformed or unsupported claims."""
    if data.get("schema") != 1:
        raise LedgerError("schema must be 1")
    if data.get("authority") != "Warcraft II Battle.net Edition 2.02b retail":
        raise LedgerError("authority must name the pinned retail BNE oracle")

    tracks = data.get("certification_tracks")
    if not isinstance(tracks, dict) or set(tracks) != {"playability", "exact_fidelity"}:
        raise LedgerError(
            "certification_tracks must contain playability and exact_fidelity")
    for track_id, track in tracks.items():
        if not isinstance(track, dict):
            raise LedgerError(f"certification_tracks.{track_id} must be an object")
        if track.get("status") not in TRACK_STATUSES:
            raise LedgerError(
                f"certification_tracks.{track_id}.status must be one of "
                + ", ".join(TRACK_STATUSES))
        for field in ("meaning", "finish"):
            if not isinstance(track.get(field), str) or not track[field]:
                raise LedgerError(
                    f"certification_tracks.{track_id}.{field} must be non-empty")
    if tracks["playability"]["status"] != "certified":
        raise LedgerError("the shipped playability ledger must state its actual status")
    if tracks["exact_fidelity"]["status"] == "certified":
        raise LedgerError(
            "exact fidelity cannot be certified by subsystem playability grades")

    systems = data.get("systems")
    if not isinstance(systems, list):
        raise LedgerError("systems must be a list")

    seen: set[str] = set()
    warnings: list[str] = []
    for system in systems:
        if not isinstance(system, dict):
            raise LedgerError("every system must be an object")
        system_id = system.get("id")
        if not isinstance(system_id, str) or not system_id:
            raise LedgerError("every system needs a non-empty id")
        if system_id in seen:
            raise LedgerError(f"duplicate system id {system_id}")
        seen.add(system_id)

        if not isinstance(system.get("name"), str) or not system["name"]:
            raise LedgerError(f"{system_id}.name must be non-empty")
        grade = system.get("grade")
        if grade not in GRADES:
            raise LedgerError(f"{system_id}.grade must be one of {', '.join(GRADES)}")

        implementation = _strings(system.get("implementation"), "implementation", system_id,
                                  nonempty=True)
        checks = _strings(system.get("checks"), "checks", system_id, nonempty=True)
        evidence = _strings(system.get("retail_evidence"), "retail_evidence", system_id)
        blockers = _strings(system.get("blockers"), "blockers", system_id)

        for field, paths in (("implementation", implementation), ("checks", checks)):
            for relative in paths:
                path = repository / relative
                if not path.exists():
                    raise LedgerError(f"{system_id}.{field} path does not exist: {relative}")

        if grade == "green" and not evidence:
            raise LedgerError(f"{system_id} cannot be green without retail evidence")
        if grade == "green" and blockers:
            raise LedgerError(f"{system_id} cannot be green with a named blocker")
        if grade == "red" and not blockers:
            raise LedgerError(f"{system_id} cannot be red without a named blocker")
        if grade in ("orange", "red") and not blockers:
            warnings.append(f"{system_id} has no named route out of {grade}")

        gate = system.get("gate")
        if not isinstance(gate, dict):
            raise LedgerError(f"{system_id}.gate must be an object")
        if not isinstance(gate.get("driver"), str) or not gate["driver"]:
            raise LedgerError(f"{system_id}.gate.driver must be non-empty")
        if not isinstance(gate.get("success"), str) or not gate["success"]:
            raise LedgerError(f"{system_id}.gate.success must be non-empty")
        command = gate.get("command")
        if not isinstance(command, list) or not command or any(
                not isinstance(argument, str) or not argument for argument in command):
            raise LedgerError(f"{system_id}.gate.command must be a non-empty argument array")
        executable = repository / command[0]
        if not executable.is_file():
            raise LedgerError(f"{system_id}.gate executable does not exist: {command[0]}")
        required_inputs = gate.get("required_inputs", [])
        required_inputs = _strings(
            required_inputs, "gate.required_inputs", system_id)
        unknown_inputs = set(required_inputs) - RETAIL_INPUTS
        if unknown_inputs:
            raise LedgerError(
                f"{system_id}.gate.required_inputs contains unknown inputs: "
                + ", ".join(sorted(unknown_inputs)))
        if len(required_inputs) != len(set(required_inputs)):
            raise LedgerError(
                f"{system_id}.gate.required_inputs must not contain duplicates")

    missing = REQUIRED_SYSTEMS - seen
    extra = seen - REQUIRED_SYSTEMS
    if missing:
        raise LedgerError(f"missing required systems: {', '.join(sorted(missing))}")
    if extra:
        raise LedgerError(f"unknown systems: {', '.join(sorted(extra))}")

    return warnings


def summary(data: dict[str, Any]) -> dict[str, int]:
    counts = {grade: 0 for grade in GRADES}
    for system in data["systems"]:
        counts[system["grade"]] += 1
    return counts


def render(data: dict[str, Any]) -> str:
    counts = summary(data)
    tracks = data["certification_tracks"]
    lines = [
        "# BNE gameplay readiness",
        "",
        "Generated from `tools/bne-readiness/readiness.json` by",
        "`scripts/check-bne-readiness.py`. Do not edit this report directly.",
        "",
        f"Authority: **{data['authority']}**.",
        "",
        "This report distinguishes two finish lines. A green subsystem says its complete",
        "player-visible loop is certified and playable from the retail pack; it does not",
        "say every internal cycle and route already matches the Windows binary exactly.",
        "The exact-fidelity frontier remains a separate, stricter proof.",
        "",
        "## Two certification tracks",
        "",
        "| Track | Status | What it answers | Finish condition |",
        "|---|---|---|---|",
        f"| Playability | {tracks['playability']['status'].upper()} | "
        f"{tracks['playability']['meaning']} | {tracks['playability']['finish']} |",
        f"| Exact BNE fidelity | {tracks['exact_fidelity']['status'].upper()} | "
        f"{tracks['exact_fidelity']['meaning']} | {tracks['exact_fidelity']['finish']} |",
        "",
        "## Summary",
        "",
        "| Grade | Systems | Meaning |",
        "|---|---:|---|",
        f"| Green | {counts['green']} | Retail behavior understood and the differential proof passes. |",
        f"| Yellow | {counts['yellow']} | Mapped and exercised, with known discrepancies or missing proof. |",
        f"| Orange | {counts['orange']} | Implemented primarily from LegacyEngine and not established against retail. |",
        f"| Red | {counts['red']} | Disabled, absent, or unable to certify a playable loop. |",
        "",
        "## System matrix",
        "",
        "| System | Grade | Automated player/referee | Blocking fact |",
        "|---|---|---|---|",
    ]
    for system in data["systems"]:
        blocker = system["blockers"][0] if system["blockers"] else "No blocking fact recorded."
        lines.append(
            f"| {system['name']} | {system['grade'].upper()} | "
            f"{system['gate']['driver']} | {blocker} |"
        )

    for system in data["systems"]:
        lines.extend([
            "",
            f"## {system['name']}",
            "",
            f"Grade: **{system['grade'].upper()}**.",
            "",
            f"Automated driver: {system['gate']['driver']}",
            "",
            f"Success means: {system['gate']['success']}",
            "",
            "Implementation:",
            "",
        ])
        lines.extend(f"- `{path}`" for path in system["implementation"])
        lines.extend(["", "Automated checks:", ""])
        lines.extend(f"- `{path}`" for path in system["checks"])
        lines.extend(["", "Retail evidence:", ""])
        if system["retail_evidence"]:
            lines.extend(f"- {item}" for item in system["retail_evidence"])
        else:
            lines.append("- No retail authority has been established yet.")
        lines.extend(["", "Known blockers:", ""])
        if system["blockers"]:
            lines.extend(f"- {item}" for item in system["blockers"])
        else:
            lines.append("- None recorded.")
        lines.extend(["", "Recheck command:", "", "```text"])
        lines.append(" ".join(system["gate"]["command"]))
        lines.append("```")

    return "\n".join(lines) + "\n"
