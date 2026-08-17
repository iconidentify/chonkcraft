#!/usr/bin/env python3
"""Authenticated native adapter for the differential playtest explorer.

The adapter never invents BNE outcomes. It reports a playtest result only
when a sealed commanded fixture, already authenticated against the pinned
2.02b executable, contains the exact scenario commands. Live capture is
attempted only when an oracle route is actually present; otherwise the
adapter fails closed.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
from typing import Any
import io
import zipfile

import bne_playtest_explorer as explorer
from bne_fixture import (
    AUX_HEADER, BULLET_BYTES, BULLET_DELTA_HEADER, BULLET_FLAGS, BULLET_FREE,
    CHUNK_HEADER, CYCLE_HEADER, PLAYER_SIM_RECORD, STATE_HEADER,
)
from bne_routes import UNIT_FLAGS, UNIT_FREE_OR_DEAD, UNIT_ORDER, read_state_stream


PINNED_BNE_EXECUTABLE_SHA256 = explorer.PINNED_BNE_EXECUTABLE_SHA256
RESULT_SCHEMA = explorer.RESULT_SCHEMA
DEFAULT_EXECUTABLES = (
    Path(os.environ["BNE_NATIVE_EXECUTABLE"]).expanduser()
    if os.environ.get("BNE_NATIVE_EXECUTABLE") else None,
    Path("/private/tmp/Warcraft-II-BNE-2.02b.exe"),
    Path(__file__).resolve().parents[1] / "work/target-2.02/Warcraft II BNE.exe",
    Path.home() / ".chonkcraft/work/bne-oracle/Warcraft-II-BNE-2.02b.exe",
)

# Coarse order names copied from the pinned layout in bne_202_layout.h.
# They describe what the sealed record already stored; they are not Java rules.
ORDER_NAMES = {
    1: "DYING",
    2: "STILL", 3: "MOVE", 4: "PATROL", 5: "PATROL", 6: "FOLLOW", 7: "FOLLOW",
    # 8-12 are the attack-family dest and chase bytes named ATTACK in
    # bne_202_layout.h. 11 is the dest-accepted ground click from table 8.
    8: "ATTACK", 9: "ATTACK", 10: "ATTACK", 11: "ATTACK", 12: "ATTACK",
    # 13 is the hold-still after 0x4368b0's three-tick order-15 opening.
    # Flag word 0x0082 has no 0x1000, so a person does not chase.
    13: "STAND_GROUND", 14: "STILL", 15: "STAND_GROUND", 16: "ATTACK",
    17: "ATTACK_GROUND", 18: "ATTACK_MOVE",
    22: "BUILD", 23: "HARVEST", 24: "RETURN_GOODS", 25: "HARVEST",
    26: "HARVEST", 27: "REPAIR", 28: "BUILD", 29: "UNLOAD",
    30: "HARVEST", 31: "HARVEST", 32: "STILL", 33: "STILL", 34: "BOARD",
    35: "UNLOAD", 36: "MOVE", 37: "BUILD", 58: "STILL", 59: "MOVE", 60: "STILL",
}


def _uint(raw: bytes, offset: int, size: int = 2) -> int:
    return int.from_bytes(raw[offset:offset + size], "little")


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def canonical_bytes(value: object) -> bytes:
    return explorer.canonical_bytes(value)


def resolve_executable(explicit: Path | None) -> Path | None:
    candidates = [explicit, *DEFAULT_EXECUTABLES]
    for candidate in candidates:
        if candidate is None:
            continue
        path = Path(candidate).expanduser()
        if path.is_file():
            return path.resolve()
    return None


def authenticate_executable(path: Path | None) -> str:
    if path is None:
        return PINNED_BNE_EXECUTABLE_SHA256
    digest = file_sha256(path)
    if digest != PINNED_BNE_EXECUTABLE_SHA256:
        raise ValueError(
            "native adapter executable is not pinned BNE 2.02b: "
            f"{path} sha256 {digest}")
    return digest


def order_name(code: int) -> str:
    if 8 <= code <= 12 or 19 <= code <= 21:
        return "ATTACK"
    if 38 <= code <= 57:
        return "SPELL_CAST"
    return ORDER_NAMES.get(code, "BNE_UNKNOWN")


def live(raw: bytes | None) -> bool:
    return raw is not None and (raw[UNIT_FLAGS] & UNIT_FREE_OR_DEAD) == 0


def snapshot(raw: bytes | None, missiles: int) -> dict[str, Any]:
    if not live(raw):
        return {
            "tile_x": -1, "tile_y": -1, "offset_x": 0, "offset_y": 0,
            "order": None, "hit_points": 0, "carried": 0,
            "alive": False, "on_map": False, "missile_count": missiles,
            "cargo_count": 0,
        }
    return {
        "tile_x": _uint(raw, 24),
        "tile_y": _uint(raw, 26),
        "offset_x": _uint(raw, 0) % 32,
        "offset_y": _uint(raw, 2) % 32,
        "order": order_name(raw[UNIT_ORDER]),
        "hit_points": _uint(raw, 34),
        "carried": 0,
        "alive": True,
        "on_map": (raw[UNIT_FLAGS] & 0x08) == 0,
        "missile_count": missiles,
        "cargo_count": 0,
        "px": _uint(raw, 0),
        "py": _uint(raw, 2),
        "order_code": raw[UNIT_ORDER],
    }


def progressed(before: dict[str, Any], now: dict[str, Any], kind: str) -> bool:
    if not before.get("alive") or not now.get("alive"):
        return before.get("alive") != now.get("alive")
    # Still leftover bobs on the standing square. The first walk pixel is
    # progress; the idle bob is not. Counting it made every attack and
    # patrol look several cycles earlier than the sealed fixture. The route
    # executor also reserves its next tile one visit before IX/IY changes.
    # That reservation is not visible movement either: batch-1/01 changes
    # 20,31 -> 21,30 at cycle 8 while its physical position remains
    # 640,992 in both engines. Java's journal compares that same physical
    # position, so the native side must not call the reservation progress.
    moved = (
        now.get("order") != "STILL"
        and (before.get("px") != now.get("px")
             or before.get("py") != now.get("py"))
    )
    if kind in {"move", "attack-move", "patrol", "follow"}:
        return moved
    if kind in {"stop", "stand-ground"}:
        return before.get("order") != now.get("order")
    return moved or before.get("order") != now.get("order") or (
        before.get("hit_points") != now.get("hit_points"))


def standing_at_move_goal(command: dict[str, Any], now: dict[str, Any]) -> bool:
    """A still unit already on or beside the click has settled the order."""
    if command.get("kind") not in {"move", "attack-move", "patrol", "follow"}:
        return False
    dest_x = command.get("x")
    dest_y = command.get("y")
    tile_x = now.get("tile_x")
    tile_y = now.get("tile_y")
    if dest_x is None or dest_y is None or tile_x is None or tile_y is None:
        return False
    return max(abs(int(tile_x) - int(dest_x)), abs(int(tile_y) - int(dest_y))) <= 1


def load_json(path: Path) -> dict[str, Any]:
    return explorer.load_json(path, "playtest scenario")


def fixture_authority(manifest: dict[str, Any]) -> str:
    digest = ((manifest.get("oracle") or {}).get("executable") or {}).get("sha256")
    if digest != PINNED_BNE_EXECUTABLE_SHA256:
        raise ValueError("native fixture is not backed by pinned BNE 2.02b")
    return digest


def parse_fixture_commands(archive: zipfile.ZipFile) -> list[dict[str, Any]]:
    if "commands.txt" not in archive.namelist():
        raise ValueError("native fixture has no commanded input")
    return explorer.parse_injector_script(archive.read("commands.txt").decode("ascii"))


def command_key(command: dict[str, Any]) -> tuple[Any, ...]:
    return (
        command["kind"],
        command["unit_id"],
        command.get("x"),
        command.get("y"),
        command.get("target_id"),
        command.get("type_index"),
        command["issue_cycle"],
    )


def commands_match(scenario: dict[str, Any], fixture_commands: list[dict[str, Any]]) -> bool:
    wanted = [command_key(command) for command in scenario["commands"]]
    have = [command_key(command) for command in fixture_commands]
    return wanted == have


def resolve_fixture(scenario: dict[str, Any], explicit: Path | None) -> Path:
    setup = scenario.get("setup") or {}
    candidates = []
    if explicit is not None:
        candidates.append(explicit)
    if setup.get("fixture"):
        candidates.append(Path(str(setup["fixture"])))
    env = os.environ.get("CHONKCRAFT_PLAYTEST_FIXTURE")
    if env:
        candidates.append(Path(env))
    for candidate in candidates:
        path = Path(candidate).expanduser()
        if path.is_file():
            return path.resolve()
    raise ValueError(
        "native adapter has no authenticated commanded fixture for this scenario")


def active_projectile_count(raw: bytes) -> bool:
    """Whether this 64-byte slot is an in-flight shot or impact sprite.

    Remaining distance lives at projectile +0x20. Flag 0x04 is set on a
    detonating rock and on type-21 impact. Three Human 13 pool occupants
    stay allocated from cycle 1 with remaining 0 and flags 0x00/0x02;
    they are not live shots. Counting them made the sealed pool look
    like five missiles at fixture 40 while Java held the two shots
    native still has in slots 4 and 5.
    """
    if raw[BULLET_FLAGS] & BULLET_FREE:
        return False
    remaining = int.from_bytes(raw[0x20:0x22], "little", signed=True)
    return remaining != 0 or (raw[BULLET_FLAGS] & 0x04) != 0


def projectile_counts_by_cycle(fixture: Path) -> dict[int, int]:
    """Live in-flight/impact count for each AUXL cycle in a sealed fixture."""
    with zipfile.ZipFile(fixture) as archive:
        payload = archive.read("state.bin")
    cursor = io.BytesIO(payload)
    header = STATE_HEADER.unpack(cursor.read(STATE_HEADER.size))
    players = header[6]
    live: dict[int, bytes] = {}
    cycle = 0
    counts: dict[int, int] = {}
    player_sim = PLAYER_SIM_RECORD.size
    while True:
        header = cursor.read(CHUNK_HEADER.size)
        if len(header) != CHUNK_HEADER.size:
            break
        tag, payload_bytes = CHUNK_HEADER.unpack(header)
        chunk = cursor.read(payload_bytes)
        body = io.BytesIO(chunk)
        if tag == b"CYCL":
            cycle = CYCLE_HEADER.unpack(body.read(CYCLE_HEADER.size))[0]
        elif tag == b"AUXL":
            _aux, bullet_count, changed, _map, _tiles = AUX_HEADER.unpack(
                body.read(AUX_HEADER.size))
            body.read(players * player_sim)
            for _ in range(changed):
                slot, _generation = BULLET_DELTA_HEADER.unpack(
                    body.read(BULLET_DELTA_HEADER.size))
                live[slot] = body.read(BULLET_BYTES)
            active = 0
            for slot, raw in live.items():
                if slot < bullet_count and active_projectile_count(raw):
                    active += 1
            counts[cycle] = active
    return counts


def load_frames(fixture: Path) -> list[dict[str, Any]]:
    with zipfile.ZipFile(fixture) as archive:
        payload = archive.read("state.bin")
    with tempfile.NamedTemporaryFile(suffix=".state.bin", delete=False) as handle:
        handle.write(payload)
        temporary = Path(handle.name)
    try:
        frames = list(read_state_stream(temporary))
    finally:
        temporary.unlink(missing_ok=True)
    if not frames:
        raise ValueError("native fixture state stream is empty")
    counts = projectile_counts_by_cycle(fixture)
    for frame in frames:
        frame["missile_count"] = counts.get(int(frame["cycle"]), 0)
    return frames


def fixture_command_events(archive: zipfile.ZipFile) -> tuple[list[str], list[str]]:
    if "trace.txt" not in archive.namelist():
        return [], []
    applied: list[str] = []
    rejected: list[str] = []
    for line in archive.read("trace.txt").decode("utf-8", "replace").splitlines():
        if "event=command-applied" in line:
            applied.append(line)
        elif "event=command-rejected" in line:
            rejected.append(line)
    return applied, rejected


def event_names_command(line: str, command: dict[str, Any]) -> bool:
    return (
        f"cycle={command['issue_cycle']}" in line
        and f"unit={command['unit_id']}" in line
        and f"action={command['kind']}" in line
    )


def observe_commands(scenario: dict[str, Any], frames: list[dict[str, Any]],
        applied_events: list[str] | None = None,
        rejected_events: list[str] | None = None) \
        -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    by_cycle = {int(frame["cycle"]): frame for frame in frames}
    last_cycle = max(by_cycle)
    observations = []
    events: list[dict[str, Any]] = []
    for index, command in enumerate(scenario["commands"]):
        issued = int(command["issue_cycle"])
        slot = int(command["unit_id"])
        kind = command["kind"]
        laden_return = kind == "return-goods" and any(
            prior.get("kind") == "harvest"
            and int(prior.get("unit_id") or -1) == slot
            for prior in scenario["commands"][:index]
        )
        # settle_cycles is the tail after the scenario's final command, not a
        # separate tail added to every command. Every earlier command remains
        # observable through that same sealed run horizon. Using issued+tail
        # ended move+stand-ground's first observation at 65 while the paired
        # Java run and the authenticated fixture both end at 80.
        window = last_cycle
        pre = by_cycle.get(issued - 1) or by_cycle.get(min(by_cycle))
        if issued not in by_cycle:
            raise ValueError(
                f"native fixture is truncated before issue cycle {issued}")
        baseline = snapshot(
            None if pre is None else pre["units"].get(slot),
            int((pre or {}).get("missile_count") or 0))
        first_progress = None
        terminal_cycle = None
        terminal_reason = None
        latest = baseline
        accepted = False
        if any(event_names_command(line, command)
               for line in (applied_events or ())):
            accepted = True
        rejected = any(event_names_command(line, command)
                       for line in (rejected_events or ()))
        if rejected:
            issue_frame = by_cycle.get(issued)
            now = snapshot(
                None if issue_frame is None else issue_frame["units"].get(slot),
                0)
            observations.append({
                "command_index": index,
                "accepted": False,
                "first_progress_cycle": None,
                "terminal_cycle": issued,
                "terminal_reason": "rejected",
                "state": {
                    key: now[key] for key in (
                        "tile_x", "tile_y", "offset_x", "offset_y", "order",
                        "hit_points", "carried", "alive", "on_map",
                        "missile_count", "cargo_count",
                    )
                },
            })
            continue
        for cycle in range(issued, min(last_cycle, window) + 1):
            frame = by_cycle.get(cycle)
            if frame is None:
                raise ValueError(f"native fixture skipped cycle {cycle}")
            now = snapshot(frame["units"].get(slot),
                           int(frame.get("missile_count") or 0))
            latest = now
            if cycle == issued and now.get("order") not in {None, baseline.get("order")}:
                accepted = True
            if first_progress is None and progressed(baseline, now, kind):
                first_progress = cycle
                accepted = True
            # Judge successful objectives that deliberately remove the actor
            # before the generic liveness terminal. A worker inside its depot
            # is unavailable to field commands, but a Return Goods command
            # whose cargo reached zero was fulfilled, exactly as the paired
            # Java journal classifies it.
            if (laden_return and first_progress is not None
                    and not now.get("on_map")):
                terminal_cycle = cycle
                terminal_reason = "fulfilled"
                break
            if not now.get("alive") or not now.get("on_map"):
                terminal_cycle = cycle
                terminal_reason = "unit-unavailable"
                break
            if now.get("order") == "STILL" and (
                    first_progress is not None
                    or standing_at_move_goal(command, now)):
                terminal_cycle = cycle
                terminal_reason = (
                    "settled" if kind in {
                        "move", "attack-move", "patrol", "follow"}
                    else "fulfilled")
                break
            if kind == "stand-ground" and now.get("order") == "STAND_GROUND":
                terminal_cycle = cycle
                terminal_reason = "fulfilled"
                break
            if cycle >= window:
                terminal_cycle = cycle
                terminal_reason = (
                    "acknowledged-no-progress" if first_progress is None
                    else "window-complete")
                break
        if terminal_reason is None:
            terminal_cycle = last_cycle
            terminal_reason = (
                "acknowledged-no-progress" if first_progress is None
                else "window-complete")
        if not accepted and not latest.get("alive"):
            terminal_reason = "rejected"
        observations.append({
            "command_index": index,
            "accepted": accepted or first_progress is not None,
            "first_progress_cycle": first_progress,
            "terminal_cycle": terminal_cycle,
            "terminal_reason": terminal_reason,
            "state": {
                key: latest[key] for key in (
                    "tile_x", "tile_y", "offset_x", "offset_y", "order",
                    "hit_points", "carried", "alive", "on_map",
                    "missile_count", "cargo_count",
                )
            },
        })
    return observations, events


def run_from_fixture(scenario: dict[str, Any], fixture: Path,
        authority: str, build_sha256: str) -> dict[str, Any]:
    with zipfile.ZipFile(fixture) as archive:
        manifest = json.loads(archive.read("manifest.json"))
        fixture_commands = parse_fixture_commands(archive)
        applied_events, rejected_events = fixture_command_events(archive)
    fixture_authority(manifest)
    if not commands_match(scenario, fixture_commands):
        raise ValueError(
            "native fixture commands do not match the playtest scenario")
    requested = (scenario.get("setup") or {}).get("scenario")
    captured = (manifest.get("run") or {}).get("requested_scenario")
    if requested and captured and requested != captured:
        raise ValueError("native fixture ran a different scenario")
    frames = load_frames(fixture)
    observations, events = observe_commands(
        scenario, frames, applied_events, rejected_events)
    return {
        "schema": RESULT_SCHEMA,
        "side": "native",
        "scenario_sha256": scenario["scenario_sha256"],
        "producer": {
            "name": "bne-playtest-native-fixture",
            "build_sha256": build_sha256,
            "authority_sha256": authority,
            "fixture": str(fixture),
            "fixture_sha256": file_sha256(fixture),
        },
        "observations": observations,
        "events": events,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--fixture", type=Path)
    parser.add_argument("--executable", type=Path)
    args = parser.parse_args()
    scenario = load_json(args.scenario)
    explorer.validate_scenario(scenario)
    executable = resolve_executable(args.executable)
    try:
        authority = authenticate_executable(executable)
        if executable is None:
            # A missing local copy is acceptable only when the fixture itself
            # already names the pinned 2.02b digest. The adapter still refuses
            # to emit a result unless that fixture matches the scenario.
            authority = PINNED_BNE_EXECUTABLE_SHA256
        fixture = resolve_fixture(scenario, args.fixture)
        build = file_sha256(Path(__file__).resolve())
        result = run_from_fixture(scenario, fixture, authority, build)
        explorer.validate_result(result, scenario, "native")
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
        print(f"bne-playtest-native-adapter: {error}", file=sys.stderr)
        return 1
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(canonical_bytes(result))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
