#!/usr/bin/env python3
"""Compile a deterministic commanded-movement corpus from a sealed fixture.

The original campaign corpus observes only autonomous play. This compiler
chooses authenticated retail unit slots from cycle one and emits independent
move cases across every compass heading plus occupied-destination refusals.
Each case is a normal corpus-plan entry, so the existing oracle seals the exact
command bytes and the existing Java runner can compare the result.

No slot, coordinate or terrain rule is hard-coded. The source fixture supplies
the scenario, seed, live unit records and authoritative map-square masks.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys
from typing import Any
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))

from bne_routes import (  # noqa: E402
    SQUARE_MASK_BY_MOVEMENT, STEP, UNIT_MOVEMENT, UNIT_X, UNIT_Y,
    read_state_stream,
)


SCHEMA = 1
UNIT_OWNER = 44
UNIT_FLAGS = 30
UNIT_HIDDEN = 0x08
LOCAL_PLAYER = 0
HEADING = ("n", "ne", "e", "se", "s", "sw", "w", "nw")


def _u16(raw: bytes, offset: int) -> int:
    return int.from_bytes(raw[offset:offset + 2], "little")


def _manifest(fixture: Path) -> dict[str, Any]:
    with zipfile.ZipFile(fixture) as archive:
        return json.loads(archive.read("manifest.json"))


def _first_frame(fixture: Path) -> dict[str, Any]:
    with zipfile.ZipFile(fixture) as archive:
        state = archive.read("state.bin")
    # read_state_stream takes a path so keep extraction isolated beside output
    import tempfile
    with tempfile.NamedTemporaryFile(suffix=".state.bin") as handle:
        handle.write(state)
        handle.flush()
        return next(read_state_stream(Path(handle.name)))


def _clear(frame: dict[str, Any], movement: int, x: int, y: int) -> bool:
    size = frame["map_size"]
    if x < 0 or y < 0 or x >= size or y >= size:
        return False
    mask = SQUARE_MASK_BY_MOVEMENT[movement]
    return (frame["squares"].get(y * size + x, 0) & mask) == 0


def _candidates(frame: dict[str, Any], movement: int) -> list[tuple[int, bytes]]:
    return [(slot, raw) for slot, raw in sorted(frame["units"].items())
            if raw[UNIT_MOVEMENT] == movement
            # The patched command injector calls retail GiveOrder and enforces
            # BNE's local-player global, which is slot zero in its campaign
            # command-line path even when scenario controller bytes differ.
            and raw[UNIT_OWNER] == LOCAL_PLAYER
            and not raw[UNIT_FLAGS] & UNIT_HIDDEN
            # BNE's unit-type table keeps ordinary mobile types below its
            # building/resource range. Heroes use separate high slots, but
            # an ordinary unit is preferred for a reusable command fixture.
            and raw[39] < 58]


def _open_case(frame: dict[str, Any], movement: int, heading: int,
               distance: int) -> tuple[int, int, int] | None:
    dx, dy = STEP[heading]
    for slot, raw in _candidates(frame, movement):
        x, y = _u16(raw, UNIT_X), _u16(raw, UNIT_Y)
        target = (x + dx * distance, y + dy * distance)
        if _clear(frame, movement, *target):
            return slot, *target
    return None


def _occupied_case(frame: dict[str, Any], movement: int) \
        -> tuple[int, int, int] | None:
    candidates = _candidates(frame, movement)
    for slot, raw in candidates:
        x, y = _u16(raw, UNIT_X), _u16(raw, UNIT_Y)
        for other, occupied in candidates:
            if other == slot:
                continue
            ox, oy = _u16(occupied, UNIT_X), _u16(occupied, UNIT_Y)
            if abs(ox - x) <= 8 and abs(oy - y) <= 8:
                return slot, ox, oy
    return None


def compile_matrix(fixture: Path, cycles: int = 160, command_cycle: int = 5,
                   distance: int = 4) -> tuple[dict[str, Any], dict[str, str]]:
    manifest = _manifest(fixture)
    frame = _first_frame(fixture)
    run = manifest["run"]
    cases: list[dict[str, Any]] = []
    commands: dict[str, str] = {}
    scenario_name = re.sub(r"[^a-z0-9]+", "-",
                           run["requested_scenario"].lower()).strip("-")
    movement_names = {0: "ground", 1: "air", 2: "sea"}
    for movement, name in movement_names.items():
        headings = range(8) if movement == 0 else (0, 2, 4, 6)
        for heading in headings:
            selected = _open_case(frame, movement, heading, distance)
            if selected is None:
                continue
            slot, x, y = selected
            case_id = f"command-{scenario_name}-{name}-{HEADING[heading]}"
            filename = f"{case_id}.commands.txt"
            commands[filename] = (
                "# bne-command-matrix-v1\n"
                f"cycle {command_cycle} move unit {slot} x {x} y {y}\n")
            cases.append({"id": case_id, "kind": "campaign",
                          "matrix_key": f"{name}-{HEADING[heading]}",
                          "scenario": run["requested_scenario"],
                          "cycles": cycles,
                          "seed": run["initialization_seed"],
                          "commands": filename})
        occupied = _occupied_case(frame, movement)
        if occupied is not None:
            slot, x, y = occupied
            case_id = f"command-{scenario_name}-{name}-occupied"
            filename = f"{case_id}.commands.txt"
            commands[filename] = (
                "# bne-command-matrix-v1\n"
                f"cycle {command_cycle} move unit {slot} x {x} y {y}\n")
            cases.append({"id": case_id, "kind": "campaign",
                          "matrix_key": f"{name}-occupied",
                          "scenario": run["requested_scenario"],
                          "cycles": cycles,
                          "seed": run["initialization_seed"],
                          "commands": filename})
    if not cases:
        raise ValueError("fixture has no local movable unit for a command case")
    plan = {"schema": SCHEMA,
            "description": "Generated BNE commanded movement/refusal matrix",
            "source_fixture_id": manifest["fixture"]["id"],
            "cases": cases}
    return plan, commands


def compile_corpus(fixtures: list[Path], cycles: int = 160,
                   command_cycle: int = 5, distance: int = 4) \
        -> tuple[dict[str, Any], dict[str, str]]:
    """Choose the first authenticated scenario that supplies each matrix lane."""
    chosen: dict[str, dict[str, Any]] = {}
    commands: dict[str, str] = {}
    fixture_ids: list[str] = []
    for fixture in fixtures:
        try:
            plan, available = compile_matrix(
                fixture, cycles, command_cycle, distance)
        except ValueError:
            continue
        fixture_ids.append(plan["source_fixture_id"])
        for case in plan["cases"]:
            key = case["matrix_key"]
            if key in chosen:
                continue
            chosen[key] = case
            commands[case["commands"]] = available[case["commands"]]
    required = ({f"ground-{heading}" for heading in HEADING}
                | {f"{movement}-{heading}"
                   for movement in ("air", "sea")
                   for heading in ("n", "e", "s", "w")})
    missing = sorted(required - set(chosen))
    if missing:
        raise ValueError("fixture set cannot supply matrix lanes: "
                         + ", ".join(missing))
    # Occupied destinations are useful refusal evidence but not every campaign
    # puts two local units of every movement class within a short radius.
    cases = [chosen[key] for key in sorted(chosen)
             if key in required or key.endswith("-occupied")]
    for case in cases:
        case.pop("matrix_key", None)
    return ({"schema": SCHEMA,
             "description": "Generated BNE commanded movement/refusal matrix",
             "source_fixture_ids": fixture_ids,
             "cases": cases}, commands)


def write_matrix(fixture: Path, output: Path, cycles: int = 160,
                 command_cycle: int = 5, distance: int = 4) -> Path:
    fixtures = (sorted(fixture.glob("*.bnefx")) if fixture.is_dir()
                else [fixture])
    plan, commands = compile_corpus(
        fixtures, cycles, command_cycle, distance)
    output.mkdir(parents=True, exist_ok=True)
    for name, content in commands.items():
        (output / name).write_text(content, encoding="ascii")
    plan_path = output / "corpus-plan.json"
    plan_path.write_text(json.dumps(plan, indent=2, sort_keys=True) + "\n",
                         encoding="utf-8")
    return plan_path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixture", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--cycles", type=int, default=160)
    parser.add_argument("--command-cycle", type=int, default=5)
    parser.add_argument("--distance", type=int, default=4)
    args = parser.parse_args()
    plan = write_matrix(args.fixture, args.output, args.cycles,
                        args.command_cycle, args.distance)
    print(plan)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
