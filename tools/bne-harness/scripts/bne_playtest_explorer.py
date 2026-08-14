#!/usr/bin/env python3
"""Coverage-guided differential player-intent explorer for BNE parity.

The explorer consumes an engine-neutral capability snapshot, synthesizes legal
player-order sequences, runs the same sequence through authenticated native and
Java adapters, and retains only new behavior or a cross-engine difference. A
difference is automatically reduced and sealed as a content-addressed forensic
packet. The adapters are commands rather than imports so the explorer cannot
accidentally use Java implementation details as its behavioral authority.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
from typing import Any, Iterable
import zipfile

import bne_minimize
import bne_command_matrix


SCENARIO_SCHEMA = "chonkcraft-bne-playtest-scenario-1"
RESULT_SCHEMA = "chonkcraft-bne-playtest-result-1"
REPORT_SCHEMA = "chonkcraft-bne-playtest-exploration-1"
PACKET_SCHEMA = "chonkcraft-bne-playtest-divergence-1"
SEED_SCHEMA = "chonkcraft-bne-playtest-seed-1"
PINNED_BNE_EXECUTABLE_SHA256 = (
    "b0e914a9cb7dcc81a205e700a9bb0a1d0649df19d459388051ba170783d2c807"
)
SIDES = ("native", "java")
TIMING_OFFSETS = (0, 1, 4, 5, 9, 10, 14, 15)
TURN_BOUNDARY_OFFSETS = {14, 15}
COMMAND_FAMILIES = {
    "move", "attack", "attack-ground", "attack-move", "stop",
    "stand-ground", "patrol", "follow", "harvest", "return-goods",
    "board", "unload", "repair", "build", "cast", "train", "research",
}
POINT_CONGESTION_FAMILIES = {
    "move", "attack-move", "patrol", "attack-ground", "build", "unload",
}
TARGET_CONGESTION_FAMILIES = {
    "attack", "follow", "harvest", "board", "repair",
}
TYPE_CONGESTION_FAMILIES = {"train", "research"}
# Replace is n-squared. Emit rare production and stance orders first so a
# large move/patrol point cloud cannot starve train, research or stop.
REPLACE_FAMILY_RANK = {
    "train": 0, "research": 0, "stop": 1, "stand-ground": 1,
    "return-goods": 1, "unload": 2, "follow": 2, "repair": 2, "board": 2,
    "harvest": 3, "cast": 3, "build": 4,
}
INJECTOR_MOVE = re.compile(
    r"cycle (\d+) move unit (\d+) x (\d+) y (\d+)\Z")
MOVEMENT_DOMAIN = {0: "land", 1: "air", 2: "water"}
REFUSED_POINTS = {"occupied", "blocked", "unaffordable"}


def canonical_bytes(value: object) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":"),
                       ensure_ascii=False) + "\n").encode("utf-8")


def digest(value: object) -> str:
    return hashlib.sha256(canonical_bytes(value)).hexdigest()


def _valid_sha256(value: object) -> bool:
    return isinstance(value, str) and len(value) == 64 and all(
        character in "0123456789abcdef" for character in value)


def load_json(path: Path, label: str) -> dict[str, Any]:
    try:
        value = json.loads(path.expanduser().resolve().read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise ValueError(f"{label} is not valid JSON: {path}") from error
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be one JSON object: {path}")
    return value


def write_json(path: Path, value: object) -> None:
    destination = path.expanduser().resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(destination.name + ".tmp")
    temporary.write_bytes(canonical_bytes(value))
    temporary.replace(destination)


def validate_seed(seed: dict[str, Any]) -> None:
    if seed.get("schema") != SEED_SCHEMA:
        raise ValueError(f"unsupported playtest seed schema: {seed.get('schema')!r}")
    identity = seed.get("identity")
    actors = seed.get("actors")
    if not isinstance(identity, dict) or not identity:
        raise ValueError("playtest seed has no authenticated identity")
    if not isinstance(actors, list) or not actors:
        raise ValueError("playtest seed has no actors")
    ids: set[int] = set()
    for actor in actors:
        if not isinstance(actor, dict) or not isinstance(actor.get("id"), int):
            raise ValueError("every actor needs an integer id")
        if actor["id"] in ids:
            raise ValueError(f"actor id {actor['id']} is repeated")
        ids.add(actor["id"])
        capabilities = actor.get("capabilities")
        if not isinstance(capabilities, list) or not capabilities:
            raise ValueError(f"actor {actor['id']} has no declared capabilities")
        unknown = set(capabilities) - COMMAND_FAMILIES
        if unknown:
            raise ValueError(f"actor {actor['id']} has unknown capabilities {unknown}")
    points = seed.get("points", [])
    if not isinstance(points, list):
        raise ValueError("playtest seed points must be a list")
    for point in points:
        if not isinstance(point, dict) or not all(
                isinstance(point.get(key), int) for key in ("x", "y")):
            raise ValueError("every playtest point needs integer x and y")


def file_sha256(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            hasher.update(block)
    return hasher.hexdigest()


def seed_from_fixture(fixture: Path, *, cycles: int = 160,
        command_cycle: int = 5, distance: int = 4) -> dict[str, Any]:
    """Promote the authenticated movement matrix into an exploration seed."""
    source = fixture.expanduser().resolve()
    plan, scripts = bne_command_matrix.compile_matrix(
        source, cycles=cycles, command_cycle=command_cycle, distance=distance)
    actors: dict[int, dict[str, Any]] = {}
    points: dict[tuple[int, int, str], dict[str, Any]] = {}
    lanes = []
    for case in plan["cases"]:
        text = scripts[case["commands"]]
        line = next(value for value in text.splitlines()
                    if value and not value.startswith("#"))
        fields = line.split()
        if len(fields) != 9:
            raise ValueError(f"matrix case {case['id']} has an invalid command")
        cycle, unit_id, x, y = (int(fields[index]) for index in (1, 4, 6, 8))
        if cycle != command_cycle:
            raise ValueError(f"matrix case {case['id']} changed its issue cycle")
        case_id = case["id"]
        domain = next((value for value in ("ground", "air", "sea")
                       if f"-{value}-" in case_id), None)
        if domain is None:
            raise ValueError(f"matrix case {case_id} has no movement domain")
        actors.setdefault(unit_id, {
            "id": unit_id,
            "player": 0,
            "domain": {"ground": "land", "air": "air", "sea": "water"}[domain],
            "capabilities": ["move"],
            "target_ids": [],
        })
        kind = "occupied" if case_id.endswith("-occupied") else "open"
        key = (x, y, kind)
        point = points.setdefault(key, {
            "x": x, "y": y, "kind": kind, "domains": [],
        })
        actor_domain = actors[unit_id]["domain"]
        if actor_domain not in point["domains"]:
            point["domains"].append(actor_domain)
            point["domains"].sort()
        lanes.append({
            "case": case_id,
            "unit_id": unit_id,
            "x": x,
            "y": y,
            "domain": actor_domain,
            "kind": kind,
        })
    if not actors or not points:
        raise ValueError("authenticated command matrix produced an empty seed")
    seed: dict[str, Any] = {
        "schema": SEED_SCHEMA,
        "identity": {
            "fixture_id": plan["source_fixture_id"],
            "fixture_sha256": file_sha256(source),
            "compiler": "bne-command-matrix-v1",
        },
        "setup": {
            "kind": "sealed-fixture",
            "fixture": str(source),
            "fixture_id": plan["source_fixture_id"],
            "matrix_lanes": lanes,
        },
        "start_cycle": command_cycle,
        "settle_cycles": cycles - command_cycle,
        "actors": [actors[key] for key in sorted(actors)],
        "targets": [],
        "points": [points[key] for key in sorted(points)],
    }
    validate_seed(seed)
    return seed


# BNE 2.02b unit-type names at record byte 39 -- bne_202_layout.h.
BNE_UNIT_TYPE_NAMES = (
    "unit-footman", "unit-grunt", "unit-peasant", "unit-peon",
    "unit-ballista", "unit-catapult", "unit-knight", "unit-ogre",
    "unit-archer", "unit-axethrower", "unit-mage", "unit-death-knight",
    "unit-paladin", "unit-ogre-mage", "unit-dwarves", "unit-goblin-sappers",
    "unit-attack-peasant", "unit-attack-peon", "unit-ranger", "unit-berserker",
    "unit-female-hero", "unit-evil-knight", "unit-flying-angel", "unit-fad-man",
    "unit-white-mage", "unit-beast-cry", "unit-human-oil-tanker",
    "unit-orc-oil-tanker", "unit-human-transport", "unit-orc-transport",
    "unit-human-destroyer", "unit-orc-destroyer", "unit-battleship",
    "unit-ogre-juggernaught", "unit-unused-34", "unit-fire-breeze",
    "unit-unused-36", "unit-unused-37", "unit-human-submarine",
    "unit-orc-submarine", "unit-balloon", "unit-zeppelin",
    "unit-gryphon-rider", "unit-dragon", "unit-knight-rider",
    "unit-eye-of-vision", "unit-arthor-literios", "unit-quick-blade",
    "unit-unused-48", "unit-double-head", "unit-wise-man", "unit-ice-bringer",
    "unit-man-of-light", "unit-sharp-axe", "unit-unused-54", "unit-skeleton",
    "unit-daemon", "unit-critter", "unit-farm", "unit-pig-farm",
    "unit-human-barracks", "unit-orc-barracks", "unit-church",
    "unit-altar-of-storms", "unit-human-watch-tower", "unit-orc-watch-tower",
    "unit-stables", "unit-ogre-mound", "unit-inventor", "unit-alchemist",
    "unit-gryphon-aviary", "unit-dragon-roost", "unit-human-shipyard",
    "unit-orc-shipyard", "unit-town-hall", "unit-great-hall",
    "unit-elven-lumber-mill", "unit-troll-lumber-mill", "unit-human-foundry",
    "unit-orc-foundry", "unit-mage-tower", "unit-temple-of-the-damned",
    "unit-human-blacksmith", "unit-orc-blacksmith", "unit-human-refinery",
    "unit-orc-refinery", "unit-human-oil-platform", "unit-orc-oil-platform",
    "unit-keep", "unit-stronghold", "unit-castle", "unit-fortress",
    "unit-gold-mine", "unit-oil-patch", "unit-human-start-location",
    "unit-orc-start-location", "unit-human-guard-tower", "unit-orc-guard-tower",
    "unit-human-cannon-tower", "unit-orc-cannon-tower", "unit-circle-of-power",
    "unit-dark-portal", "unit-runestone", "unit-human-wall", "unit-orc-wall",
)
BUTTON_ACTION_FAMILY = {
    "move": "move",
    "stop": "stop",
    "attack": "attack",
    "attack-ground": "attack-ground",
    "stand-ground": "stand-ground",
    "patrol": "patrol",
    "harvest": "harvest",
    "return-goods": "return-goods",
    "repair": "repair",
    "build": "build",
    "train-unit": "train",
    "research": "research",
    "unload": "unload",
    "cast-spell": "cast",
}
RESOURCE_TYPE_IDENTS = {"unit-gold-mine", "unit-oil-patch", "unit-oil-platform",
                        "unit-human-oil-platform", "unit-orc-oil-platform"}
GENERATED_BUTTONS = (
    Path(__file__).resolve().parents[3]
    / "engine/src/main/java/net/chonkbase/chonkcraft/engine/generated"
    / "GeneratedButtons.java"
)
_BUTTON_ROW = re.compile(
    r'new Row\(\s*\d+\s*,\s*\d+\s*,\s*"[^"]+"\s*,\s*"(?P<action>[^"]+)"'
    r'.*java\.util\.List\.of\((?P<units>[^)]*)\)\s*\)\s*,?\s*$'
)
_UNIT_IDENT = re.compile(r'"(unit-[a-z0-9-]+)"')


def bne_type_ident(type_id: int) -> str:
    if 0 <= type_id < len(BNE_UNIT_TYPE_NAMES):
        return BNE_UNIT_TYPE_NAMES[type_id]
    return "unit-unknown"


def load_typed_command_capabilities(path: Path | None = None) -> dict[str, set[str]]:
    """Map unit ident -> command families from the typed button catalog."""
    source = path or GENERATED_BUTTONS
    text = source.read_text(encoding="utf-8")
    capabilities: dict[str, set[str]] = {}
    for line in text.splitlines():
        match = _BUTTON_ROW.search(line.strip())
        if match is None:
            continue
        family = BUTTON_ACTION_FAMILY.get(match.group("action"))
        if family is None:
            continue
        for ident in _UNIT_IDENT.findall(match.group("units")):
            capabilities.setdefault(ident, set()).add(family)
    if not capabilities:
        raise ValueError(f"typed button catalog produced no capabilities: {source}")
    return capabilities


def typed_capabilities_for_type(type_id: int,
        catalog: dict[str, set[str]] | None = None) -> set[str]:
    table = catalog if catalog is not None else load_typed_command_capabilities()
    return set(table.get(bne_type_ident(type_id), ()))


def _frame_units(fixture: Path) -> list[tuple[int, bytes]]:
    frame = bne_command_matrix._first_frame(fixture)
    return sorted(frame["units"].items())


def enrich_seed_families(seed: dict[str, Any], fixture: Path) -> dict[str, Any]:
    """Attach only the command families the typed button catalog names.

    Cycle-one records supply slot, owner and type id. Harvest, train and
    research come from GeneratedButtons for that ident -- not from owner
    or a type-id range. A grunt never harvests. A farm never trains.
    """
    validate_seed(seed)
    catalog = load_typed_command_capabilities()
    records = _frame_units(fixture)
    by_slot = dict(records)
    actors = {actor["id"]: actor for actor in seed["actors"]}
    targets = {target["id"]: target for target in seed.get("targets", [])}
    hostiles: list[int] = []
    resources: list[int] = []
    trainers: list[int] = []
    harvesters: list[int] = []
    for slot, raw in records:
        if raw[bne_command_matrix.UNIT_FLAGS] & bne_command_matrix.UNIT_HIDDEN:
            continue
        ident = bne_type_ident(raw[39])
        owner = raw[bne_command_matrix.UNIT_OWNER]
        caps = typed_capabilities_for_type(raw[39], catalog)
        if owner != 0 and ident not in RESOURCE_TYPE_IDENTS:
            hostiles.append(slot)
        if ident in RESOURCE_TYPE_IDENTS:
            resources.append(slot)
        if owner == 0 and ({"train", "research"} & caps):
            trainers.append(slot)
        if owner == 0 and "harvest" in caps:
            harvesters.append(slot)
        if slot in actors:
            allowed = set(actors[slot]["capabilities"]) | caps
            # Attack on ground is attack-move only for types that can both
            # walk and attack. That is the retail control rule, not a range.
            if "attack" in allowed and "move" in allowed:
                allowed.add("attack-move")
            actors[slot]["capabilities"] = sorted(allowed)
            actors[slot]["type_ident"] = ident
    for slot in hostiles + resources:
        raw = by_slot.get(slot)
        if raw is None:
            continue
        targets.setdefault(slot, {
            "id": slot,
            "player": raw[bne_command_matrix.UNIT_OWNER],
            "domain": "land",
            "x": bne_command_matrix._u16(raw, bne_command_matrix.UNIT_X),
            "y": bne_command_matrix._u16(raw, bne_command_matrix.UNIT_Y),
            "type_ident": bne_type_ident(raw[39]),
        })
    for actor in actors.values():
        caps = set(actor["capabilities"])
        ids = list(actor.get("target_ids", []))
        if "attack" in caps:
            ids.extend(hostiles)
        if "harvest" in caps:
            ids.extend(resources)
        actor["target_ids"] = sorted(set(ids))
    for slot in harvesters[:2]:
        if slot in actors:
            continue
        raw = by_slot[slot]
        caps = typed_capabilities_for_type(raw[39], catalog)
        movement = raw[bne_command_matrix.UNIT_MOVEMENT]
        allowed = set(caps)
        if "attack" in allowed and "move" in allowed:
            allowed.add("attack-move")
        actors[slot] = {
            "id": slot,
            "player": 0,
            "domain": "land" if movement == 0
            else "air" if movement == 1 else "water",
            "capabilities": sorted(allowed),
            "target_ids": list(resources) if "harvest" in allowed else [],
            "type_ident": bne_type_ident(raw[39]),
        }
    for slot in trainers[:2]:
        if slot in actors:
            continue
        raw = by_slot[slot]
        caps = typed_capabilities_for_type(raw[39], catalog)
        production = sorted(caps & {"train", "research"})
        if not production:
            continue
        actors[slot] = {
            "id": slot,
            "player": 0,
            "domain": "land",
            "capabilities": production,
            "target_ids": [],
            "type_index": 0,
            "afford": False,
            "type_ident": bne_type_ident(raw[39]),
        }
    seed["actors"] = [actors[key] for key in sorted(actors)]
    seed["targets"] = [targets[key] for key in sorted(targets)]
    validate_seed(seed)
    return seed


def seed_from_idle_fixture(fixture: Path, **kwargs: Any) -> dict[str, Any]:
    """Movement-matrix seed plus typed families for those same actors."""
    seed = seed_from_fixture(fixture, **kwargs)
    return enrich_seed_families(seed, fixture)


def coverage_inventory(seeds: list[dict[str, Any]], *,
        max_scenarios: int = 256) -> dict[str, Any]:
    """Generate without executing. Counts families, patterns and tokens.

    Generated rows never satisfy the 100-scenario dual-adapter requirement.
    ``dual_adapter_executed_scenarios`` stays 0 here; only a run that both
    production adapters actually execute can raise that count.
    """
    if len(seeds) < 1:
        raise ValueError("coverage inventory needs at least one seed")
    families: set[str] = set()
    patterns: set[str] = set()
    generated = 0
    per_seed: list[dict[str, Any]] = []
    for seed in seeds:
        validate_seed(seed)
        scenarios = generate_scenarios(seed, max_scenarios=max_scenarios)
        seed_families = {
            command["kind"] for item in scenarios for command in item["commands"]
        }
        seed_patterns = {item["pattern"] for item in scenarios}
        families.update(seed_families)
        patterns.update(seed_patterns)
        generated += len(scenarios)
        per_seed.append({
            "identity": seed.get("identity"),
            "generated_scenarios": len(scenarios),
            "families": sorted(seed_families),
            "patterns": sorted(seed_patterns),
        })
    return {
        "schema": "chonkcraft-bne-playtest-coverage-inventory-1",
        "seed_count": len(seeds),
        "generated_scenarios": generated,
        "dual_adapter_executed_scenarios": 0,
        "command_family_count": len(families),
        "families": sorted(families),
        "patterns": sorted(patterns),
        "seeds": per_seed,
        "complete": False,
    }


def parse_injector_script(text: str) -> list[dict[str, Any]]:
    """Parse the guarded native move-injector script used by commanded fixtures."""
    commands: list[dict[str, Any]] = []
    for line_number, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        match = INJECTOR_MOVE.fullmatch(line)
        if match is None:
            raise ValueError(f"unsupported injector command at line {line_number}")
        cycle, unit_id, x, y = (int(value) for value in match.groups())
        commands.append({
            "kind": "move",
            "unit_id": unit_id,
            "x": x,
            "y": y,
            "queued": False,
            "issue_cycle": cycle,
        })
    if not commands:
        raise ValueError("injector script contains no command")
    return commands


def seed_from_commanded_fixture(fixture: Path) -> dict[str, Any]:
    """Promote an authenticated commanded capture into an exact playtest seed."""
    source = fixture.expanduser().resolve()
    with zipfile.ZipFile(source) as archive:
        names = archive.namelist()
        if "commands.txt" not in names or "manifest.json" not in names:
            raise ValueError("fixture is not a commanded BNE capture")
        script = archive.read("commands.txt").decode("ascii")
        manifest = json.loads(archive.read("manifest.json"))
    oracle = ((manifest.get("oracle") or {}).get("executable") or {})
    if oracle.get("sha256") != PINNED_BNE_EXECUTABLE_SHA256:
        raise ValueError("commanded fixture is not backed by pinned BNE 2.02b")
    commands = parse_injector_script(script)
    frame = bne_command_matrix._first_frame(source)
    run = manifest.get("run") or {}
    actors: dict[int, dict[str, Any]] = {}
    points: dict[tuple[int, int], dict[str, Any]] = {}
    for command in commands:
        slot = command["unit_id"]
        raw = frame["units"].get(slot)
        if raw is None:
            raise ValueError(f"commanded fixture has no live unit {slot} at cycle one")
        domain = MOVEMENT_DOMAIN.get(raw[bne_command_matrix.UNIT_MOVEMENT], "land")
        actor = actors.setdefault(slot, {
            "id": slot,
            "player": raw[bne_command_matrix.UNIT_OWNER],
            "domain": domain,
            "capabilities": [],
            "target_ids": [],
            "x": bne_command_matrix._u16(raw, bne_command_matrix.UNIT_X),
            "y": bne_command_matrix._u16(raw, bne_command_matrix.UNIT_Y),
        })
        if command["kind"] not in actor["capabilities"]:
            actor["capabilities"].append(command["kind"])
            actor["capabilities"].sort()
        point = points.setdefault((command["x"], command["y"]), {
            "x": command["x"], "y": command["y"], "kind": "open",
            "domains": [],
        })
        if domain not in point["domains"]:
            point["domains"].append(domain)
            point["domains"].sort()
    if not actors or not points:
        raise ValueError("commanded fixture produced an empty seed")
    start_cycle = min(command["issue_cycle"] for command in commands)
    cycle_limit = int(run.get("cycle_limit") or 160)
    seed: dict[str, Any] = {
        "schema": SEED_SCHEMA,
        "identity": {
            "fixture_id": (manifest.get("fixture") or {}).get("id"),
            "fixture_sha256": file_sha256(source),
            "compiler": "bne-commanded-fixture-v1",
            "authority_sha256": PINNED_BNE_EXECUTABLE_SHA256,
        },
        "setup": {
            "kind": "sealed-fixture",
            "fixture": str(source),
            "fixture_id": (manifest.get("fixture") or {}).get("id"),
            "scenario": run.get("requested_scenario"),
            "seed": run.get("initialization_seed", 1),
            "cycle_limit": cycle_limit,
        },
        "start_cycle": start_cycle,
        "settle_cycles": max(1, cycle_limit - start_cycle),
        "actors": [actors[key] for key in sorted(actors)],
        "targets": [],
        "points": [points[key] for key in sorted(points)],
        "authenticated_commands": commands,
    }
    validate_seed(seed)
    return seed


def _target(seed: dict[str, Any], target_id: int) -> dict[str, Any] | None:
    for actor in seed["actors"]:
        if actor["id"] == target_id:
            return actor
    for target in seed.get("targets", []):
        if target.get("id") == target_id:
            return target
    return None


def _point_compatible(actor: dict[str, Any], point: dict[str, Any]) -> bool:
    domain = actor.get("domain", "land")
    domains = point.get("domains")
    return not isinstance(domains, list) or domain in domains


def legal_commands(seed: dict[str, Any]) -> list[dict[str, Any]]:
    """Return a stable, nonempty order grammar from declared capabilities."""
    validate_seed(seed)
    commands: list[dict[str, Any]] = []
    points = sorted(seed.get("points", []), key=lambda p: (
        str(p.get("kind", "")), p["y"], p["x"]))
    for actor in sorted(seed["actors"], key=lambda item: item["id"]):
        actor_id = actor["id"]
        capabilities = set(actor["capabilities"])
        target_ids = sorted(set(actor.get("target_ids", [])))
        for family in sorted(capabilities):
            base = {"kind": family, "unit_id": actor_id, "queued": False}
            if family in {"stop", "stand-ground", "return-goods", "unload"}:
                commands.append(base)
                continue
            if family in {"train", "research"}:
                command = {**base, "type_index": int(actor.get("type_index") or 0)}
                if actor.get("afford") is False:
                    command["point_kind"] = "unaffordable"
                commands.append(command)
                continue
            if family in {"move", "attack-move", "patrol", "attack-ground",
                          "build", "cast"}:
                for point in points:
                    if not _point_compatible(actor, point):
                        continue
                    if family == "build" and point.get("kind") not in {
                            "build", "shore", "resource"}:
                        continue
                    command = {**base, "x": point["x"], "y": point["y"],
                               "point_kind": point.get("kind", "open")}
                    if family in {"build", "cast"} and actor.get("type_index") is not None:
                        command["type_index"] = actor["type_index"]
                    if family in {"build", "cast"} and actor.get("afford") is False:
                        command["point_kind"] = "unaffordable"
                    commands.append(command)
                continue
            if family in {"attack", "follow", "harvest", "board", "repair"}:
                for target_id in target_ids:
                    target = _target(seed, target_id)
                    if target is None:
                        continue
                    if family == "attack" and target.get("player") == actor.get("player"):
                        continue
                    if family in {"follow", "board", "repair"} \
                            and target.get("player") != actor.get("player"):
                        continue
                    commands.append({**base, "target_id": target_id})
    unique = {digest(command): command for command in commands}
    result = [unique[key] for key in sorted(unique)]
    if not result:
        raise ValueError("capability snapshot generated no legal commands")
    return result


def _scheduled(command: dict[str, Any], cycle: int) -> dict[str, Any]:
    return {"issue_cycle": cycle, **copy.deepcopy(command)}


def generate_scenarios(seed: dict[str, Any], *, max_scenarios: int = 256,
        timing_offsets: Iterable[int] = TIMING_OFFSETS) -> list[dict[str, Any]]:
    if max_scenarios <= 0:
        raise ValueError("max_scenarios must be positive")
    commands = legal_commands(seed)
    start = int(seed.get("start_cycle", 0))
    offsets = sorted(set(int(value) for value in timing_offsets if int(value) >= 0))
    if not offsets:
        raise ValueError("at least one nonnegative timing offset is required")
    candidates: list[tuple[str, list[dict[str, Any]]]] = []

    # Single orders establish ordinary semantics at cadence boundaries.
    # Occupied, blocked or unaffordable destinations are the refusal surface.
    # Offsets 14 and 15 sit on the retail 15-cycle turn edge; those singles
    # are named turn-boundary so a coverage sweep cannot hide an empty set.
    for command in commands:
        base_pattern = (
            "refuse" if command.get("point_kind") in REFUSED_POINTS else "single")
        for offset in offsets:
            pattern = (
                "turn-boundary" if base_pattern == "single"
                and offset in TURN_BOUNDARY_OFFSETS else base_pattern)
            candidates.append((pattern, [_scheduled(command, start + offset)]))

    # Repeating an order exposes cooldown, duplicate projectile and stale-order bugs.
    for command in commands:
        for delay in (1, 5, 15):
            candidates.append(("repeat", [
                _scheduled(command, start), _scheduled(command, start + delay),
            ]))

    # A group order is the same family issued to every capable actor together.
    by_family: dict[str, dict[int, dict[str, Any]]] = {}
    for command in commands:
        by_family.setdefault(command["kind"], {}).setdefault(
            command["unit_id"], command)
    for family_commands in by_family.values():
        if len(family_commands) < 2:
            continue
        chosen = [family_commands[unit_id] for unit_id in sorted(family_commands)]
        for offset in offsets:
            candidates.append(("group", [
                _scheduled(command, start + offset) for command in chosen
            ]))

    # Congestion is two actors told to occupy the same square, or to work
    # the same live target, on one turn. Harvest, board and attack share
    # that surface; move is only the land-walk case.
    movers_by_goal: dict[tuple[object, ...], dict[int, dict[str, Any]]] = {}
    for command in commands:
        if command["kind"] in POINT_CONGESTION_FAMILIES \
                and "x" in command and "y" in command:
            key: tuple[object, ...] = (
                command["kind"], "point", command["x"], command["y"])
        elif command["kind"] in TARGET_CONGESTION_FAMILIES \
                and command.get("target_id") is not None:
            key = (command["kind"], "target", command["target_id"])
        elif command["kind"] in TYPE_CONGESTION_FAMILIES:
            key = (command["kind"], "type", command.get("type_index"))
        else:
            continue
        movers_by_goal.setdefault(key, {}).setdefault(command["unit_id"], command)
    for goal_commands in movers_by_goal.values():
        if len(goal_commands) < 2:
            continue
        chosen = [goal_commands[unit_id] for unit_id in sorted(goal_commands)]
        for delay in (0, 1, 15):
            candidates.append(("congestion", [
                _scheduled(chosen[0], start),
                _scheduled(chosen[1], start + delay),
            ]))

    # Replacing an active order is the most common real-play race: move/attack,
    # attack/move, harvest/move, board/move and stop/reissue. Generated last
    # so the group and congestion surfaces are not starved by the n-squared
    # replacement fan-out.
    by_actor: dict[int, list[dict[str, Any]]] = {}
    for command in commands:
        by_actor.setdefault(command["unit_id"], []).append(command)
    for actor_commands in by_actor.values():
        actor_commands.sort(key=lambda command: (
            REPLACE_FAMILY_RANK.get(command["kind"], 9),
            command["kind"], command.get("x", -1), command.get("y", -1),
            command.get("target_id", -1), command.get("type_index", -1),
        ))
        for first in actor_commands:
            for second in actor_commands:
                if first["kind"] == second["kind"] and first == second:
                    continue
                for delay in (1, 5, 15):
                    candidates.append(("replace", [
                        _scheduled(first, start),
                        _scheduled(second, start + delay),
                    ]))

    scenarios: list[dict[str, Any]] = []
    seen: set[str] = set()
    by_pattern: dict[str, list[list[dict[str, Any]]]] = {}
    for pattern, scheduled in candidates:
        by_pattern.setdefault(pattern, []).append(scheduled)
    # Singles used to fill the cap before group, replace and congestion
    # were reached. Round-robin so each pattern the seed can emit is
    # present in a bounded inventory.
    queues = [(pattern, list(items)) for pattern, items in by_pattern.items()]
    while queues and len(scenarios) < max_scenarios:
        pattern, items = queues.pop(0)
        if not items:
            continue
        scheduled = items.pop(0)
        scenario: dict[str, Any] = {
            "schema": SCENARIO_SCHEMA,
            "seed_identity": seed["identity"],
            "seed_sha256": digest(seed),
            "setup": copy.deepcopy(seed.get("setup", {})),
            "actors": copy.deepcopy(seed["actors"]),
            "targets": copy.deepcopy(seed.get("targets", [])),
            "pattern": pattern,
            "settle_cycles": int(seed.get("settle_cycles", 600)),
            "commands": scheduled,
        }
        scenario["scenario_sha256"] = digest({
            key: value for key, value in scenario.items()
            if key != "scenario_sha256"
        })
        if scenario["scenario_sha256"] not in seen:
            seen.add(scenario["scenario_sha256"])
            scenarios.append(scenario)
        if items:
            queues.append((pattern, items))
    if not scenarios:
        raise ValueError("playtest generation produced no scenarios")
    return scenarios


def validate_scenario(scenario: dict[str, Any]) -> None:
    if scenario.get("schema") != SCENARIO_SCHEMA:
        raise ValueError("adapter scenario has the wrong schema")
    commands = scenario.get("commands")
    if not isinstance(commands, list) or not commands:
        raise ValueError("adapter scenario has no commands")
    previous = -1
    for command in commands:
        if not isinstance(command, dict) or command.get("kind") not in COMMAND_FAMILIES:
            raise ValueError("adapter scenario contains an invalid command")
        cycle = command.get("issue_cycle")
        if not isinstance(cycle, int) or cycle < previous:
            raise ValueError("adapter commands are not in cycle order")
        previous = cycle
    claimed = scenario.get("scenario_sha256")
    actual = digest({key: value for key, value in scenario.items()
                     if key != "scenario_sha256"})
    if claimed != actual:
        raise ValueError("adapter scenario identity changed")


def native_command_script(scenario: dict[str, Any]) -> str:
    """Encode the explorer's currently proved native command-injector surface."""
    validate_scenario(scenario)
    lines = [
        "# bne-playtest-explorer-v1",
        f"# scenario-sha256 {scenario['scenario_sha256']}",
    ]
    for command in scenario["commands"]:
        if command["kind"] != "move":
            raise ValueError(
                "native direct command injector currently proves only move; "
                "use the authenticated replay-packet adapter for other commands")
        if not all(isinstance(command.get(key), int) for key in ("x", "y")):
            raise ValueError("move command has no integer destination")
        lines.append(
            f"cycle {command['issue_cycle']} move unit {command['unit_id']} "
            f"x {command['x']} y {command['y']}")
    return "\n".join(lines) + "\n"


def validate_result(result: dict[str, Any], scenario: dict[str, Any], side: str) -> None:
    if result.get("schema") != RESULT_SCHEMA or result.get("side") != side:
        raise ValueError(f"{side} adapter returned the wrong schema or side")
    if result.get("scenario_sha256") != scenario["scenario_sha256"]:
        raise ValueError(f"{side} adapter ran a different scenario")
    producer = result.get("producer")
    if not isinstance(producer, dict) or not producer.get("name") \
            or not _valid_sha256(producer.get("build_sha256")):
        raise ValueError(f"{side} adapter did not identify its producer")
    if side == "native" and producer.get(
            "authority_sha256") != PINNED_BNE_EXECUTABLE_SHA256:
        raise ValueError("native adapter is not backed by pinned BNE 2.02b")
    observations = result.get("observations")
    if not isinstance(observations, list) or not observations:
        raise ValueError(f"{side} adapter produced no command observations")
    if len(observations) != len(scenario["commands"]):
        raise ValueError(f"{side} adapter did not observe every command")
    for index, observation in enumerate(observations):
        if observation.get("command_index") != index:
            raise ValueError(f"{side} adapter observations are not ordered")
        if not isinstance(observation.get("accepted"), bool):
            raise ValueError(f"{side} adapter omitted command acceptance")


def _delay(value: object, issued: int) -> int | None:
    return None if value is None else int(value) - issued


def normalize_result(result: dict[str, Any], scenario: dict[str, Any]) -> dict[str, Any]:
    observations = []
    for command, observation in zip(
            scenario["commands"], result["observations"], strict=True):
        issued = int(command["issue_cycle"])
        state = observation.get("state") or {}
        observations.append({
            "command_index": observation["command_index"],
            "unit_id": command["unit_id"],
            "kind": command["kind"],
            "accepted": observation["accepted"],
            "progress_delay": _delay(observation.get("first_progress_cycle"), issued),
            "terminal_delay": _delay(observation.get("terminal_cycle"), issued),
            "terminal_reason": observation.get("terminal_reason"),
            "state": {
                key: state.get(key) for key in (
                    "tile_x", "tile_y", "offset_x", "offset_y", "order",
                    "target_id", "hit_points", "carried", "alive", "on_map",
                    "missile_count", "cargo_count",
                ) if key in state
            },
        })
    events = []
    for event in result.get("events", []):
        if not isinstance(event, dict):
            continue
        events.append({key: event.get(key) for key in (
            "cycle", "kind", "unit_id", "target_id", "x", "y", "value",
        ) if key in event})
    return {"observations": observations, "events": events}


def compare_results(native: dict[str, Any], java: dict[str, Any],
        scenario: dict[str, Any]) -> dict[str, Any]:
    validate_scenario(scenario)
    validate_result(native, scenario, "native")
    validate_result(java, scenario, "java")
    left = normalize_result(native, scenario)
    right = normalize_result(java, scenario)
    differences = []
    for index, (native_item, java_item) in enumerate(zip(
            left["observations"], right["observations"], strict=True)):
        fields = sorted(key for key in native_item
                        if native_item.get(key) != java_item.get(key))
        if fields:
            differences.append({
                "command_index": index,
                "kind": native_item["kind"],
                "fields": fields,
                "native": native_item,
                "java": java_item,
            })
    if left["events"] != right["events"]:
        differences.append({
            "command_index": None,
            "kind": "event-stream",
            "fields": ["events"],
            "native": left["events"],
            "java": right["events"],
        })
    return {
        "difference_count": len(differences),
        "first_difference": differences[0] if differences else None,
        "differences": differences,
    }


def coverage_tokens(result: dict[str, Any], scenario: dict[str, Any]) -> list[str]:
    normalized = normalize_result(result, scenario)
    tokens: set[str] = set()
    tokens.add(f"pattern:{scenario['pattern']}")
    if any(int(command["issue_cycle"]) % 15 in (0, 14)
           for command in scenario["commands"]):
        tokens.add("timing:turn-boundary")
    for command, observation in zip(
            scenario["commands"], normalized["observations"], strict=True):
        kind = command["kind"]
        tokens.add(f"command:{kind}")
        tokens.add(f"accept:{kind}:{str(observation['accepted']).lower()}")
        progress = observation["progress_delay"]
        bucket = "none" if progress is None else (
            "same" if progress == 0 else "fast" if progress <= 5
            else "turn" if progress <= 15 else "late")
        tokens.add(f"progress:{kind}:{bucket}")
        tokens.add(f"terminal:{kind}:{observation['terminal_reason']}")
        order = observation["state"].get("order")
        if order is not None:
            tokens.add(f"order:{kind}:{order}")
    for event in normalized["events"]:
        tokens.add(f"event:{event.get('kind')}")
    return sorted(tokens)


class Adapter:
    """Runs one fail-closed engine adapter without invoking a shell."""

    def __init__(self, side: str, command: list[str], *, timeout: float = 180.0):
        if side not in SIDES:
            raise ValueError(f"unknown adapter side {side!r}")
        if not command or not any("{scenario}" in token for token in command) \
                or not any("{output}" in token for token in command):
            raise ValueError("adapter command needs {scenario} and {output} placeholders")
        if timeout <= 0:
            raise ValueError("adapter timeout must be positive")
        self.side = side
        self.command = list(command)
        self.timeout = timeout

    def run(self, scenario: dict[str, Any], root: Path) -> dict[str, Any]:
        validate_scenario(scenario)
        directory = root / self.side
        directory.mkdir(parents=True, exist_ok=True)
        scenario_path = directory / "scenario.json"
        output_path = directory / "result.json"
        output_path.unlink(missing_ok=True)
        write_json(scenario_path, scenario)
        command = [token.replace("{scenario}", str(scenario_path))
                   .replace("{output}", str(output_path)) for token in self.command]
        completed = subprocess.run(
            command, capture_output=True, text=True, timeout=self.timeout,
            check=False,
        )
        (directory / "stdout.txt").write_text(completed.stdout, encoding="utf-8")
        (directory / "stderr.txt").write_text(completed.stderr, encoding="utf-8")
        if completed.returncode != 0:
            raise ValueError(
                f"{self.side} adapter failed with exit {completed.returncode}; "
                f"see {directory / 'stderr.txt'}")
        if not output_path.is_file():
            raise ValueError(f"{self.side} adapter did not write {output_path}")
        result = load_json(output_path, f"{self.side} adapter result")
        validate_result(result, scenario, self.side)
        return result


def _scenario_with_commands(scenario: dict[str, Any],
        commands: list[dict[str, Any]]) -> dict[str, Any]:
    candidate = copy.deepcopy(scenario)
    candidate["commands"] = sorted(commands, key=lambda item: item["issue_cycle"])
    candidate["scenario_sha256"] = digest({
        key: value for key, value in candidate.items() if key != "scenario_sha256"
    })
    return candidate


def minimize_difference(scenario: dict[str, Any], native_adapter: Adapter,
        java_adapter: Adapter, root: Path, *, max_tests: int = 64) \
        -> tuple[dict[str, Any], dict[str, Any], dict[str, Any], dict[str, Any]]:
    attempts = 0

    def preserves(commands: list[dict[str, Any]]) -> bool:
        nonlocal attempts
        if not commands:
            return False
        attempts += 1
        candidate = _scenario_with_commands(scenario, commands)
        candidate_root = root / f"attempt-{attempts:03d}"
        native = native_adapter.run(candidate, candidate_root)
        java = java_adapter.run(candidate, candidate_root)
        return compare_results(native, java, candidate)["difference_count"] > 0

    commands, proof = bne_minimize.ddmin(
        scenario["commands"], preserves, max_tests=max_tests)
    minimal = _scenario_with_commands(scenario, commands)
    final_root = root / "minimal"
    native = native_adapter.run(minimal, final_root)
    java = java_adapter.run(minimal, final_root)
    comparison = compare_results(native, java, minimal)
    if comparison["difference_count"] == 0:
        raise ValueError("minimized scenario no longer reproduces the difference")
    proof["adapter_runs"] = attempts + 2
    return minimal, native, java, {"proof": proof, "comparison": comparison}


def seal_packet(output_root: Path, scenario: dict[str, Any],
        native: dict[str, Any], java: dict[str, Any], comparison: dict[str, Any],
        coverage: list[str], minimization: dict[str, Any] | None = None) -> Path:
    first = comparison.get("first_difference") or {}
    fields = set(first.get("fields") or [])
    route = (
        "order-acceptance" if "accepted" in fields
        else "command-cadence" if fields & {"progress_delay", "terminal_delay"}
        else "outcome-liveness" if "terminal_reason" in fields
        else "effect-lifecycle" if first.get("kind") == "event-stream"
        else "state-machine"
    )
    body: dict[str, Any] = {
        "schema": PACKET_SCHEMA,
        "scenario": scenario,
        "native": native,
        "java": java,
        "comparison": comparison,
        "coverage": coverage,
        "minimization": minimization,
        "handoff": {
            "route": route,
            "first_command_index": first.get("command_index"),
            "next_step": (
                "Run the existing causal/branch witness tools around the first "
                "differing command and its issue-to-progress window."
            ),
        },
    }
    packet_sha = digest(body)
    body["packet_sha256"] = packet_sha
    directory = output_root / "divergences" / packet_sha
    directory.mkdir(parents=True, exist_ok=True)
    write_json(directory / "packet.json", body)
    write_json(directory / "scenario.json", scenario)
    write_json(directory / "native.json", native)
    write_json(directory / "java.json", java)
    return directory


def explore(seed: dict[str, Any], native_adapter: Adapter, java_adapter: Adapter,
        output_root: Path, *, max_scenarios: int = 256,
        max_differences: int = 8, minimize: bool = True,
        minimize_tests: int = 64) -> dict[str, Any]:
    root = output_root.expanduser().resolve()
    root.mkdir(parents=True, exist_ok=True)
    scenarios = generate_scenarios(seed, max_scenarios=max_scenarios)
    global_coverage: set[str] = set()
    retained = 0
    differences = []
    executed = 0
    for scenario in scenarios:
        run_root = root / "runs" / scenario["scenario_sha256"]
        native = native_adapter.run(scenario, run_root)
        java = java_adapter.run(scenario, run_root)
        executed += 1
        comparison = compare_results(native, java, scenario)
        tokens = set(coverage_tokens(native, scenario)) | set(
            coverage_tokens(java, scenario))
        new_tokens = sorted(tokens - global_coverage)
        global_coverage.update(tokens)
        if new_tokens:
            retained += 1
            write_json(run_root / "coverage.json", {
                "new": new_tokens, "all": sorted(tokens),
            })
        if comparison["difference_count"] == 0:
            continue
        minimal = scenario
        minimal_native = native
        minimal_java = java
        minimization = None
        if minimize and len(scenario["commands"]) > 1:
            minimal, minimal_native, minimal_java, minimization = minimize_difference(
                scenario, native_adapter, java_adapter,
                root / "minimize" / scenario["scenario_sha256"],
                max_tests=minimize_tests)
            comparison = minimization["comparison"]
        packet = seal_packet(
            root, minimal, minimal_native, minimal_java, comparison,
            sorted(tokens), minimization)
        differences.append({
            "scenario_sha256": minimal["scenario_sha256"],
            "packet": str(packet / "packet.json"),
            "first_difference": comparison["first_difference"],
            "original_command_count": len(scenario["commands"]),
            "minimal_command_count": len(minimal["commands"]),
        })
        if len(differences) >= max_differences:
            break
    report: dict[str, Any] = {
        "schema": REPORT_SCHEMA,
        "seed_sha256": digest(seed),
        "generated_scenarios": len(scenarios),
        "executed_scenarios": executed,
        "retained_novel_scenarios": retained,
        "coverage_token_count": len(global_coverage),
        "coverage": sorted(global_coverage),
        "difference_count": len(differences),
        "differences": differences,
    }
    report["report_sha256"] = digest(report)
    write_json(root / "report.json", report)
    return report


def _command_tokens(value: str) -> list[str]:
    tokens = shlex.split(value)
    if not tokens:
        raise ValueError("adapter command is empty")
    return tokens


def coverage_inventory_command(args: argparse.Namespace) -> int:
    seeds = [seed_from_idle_fixture(path) for path in args.fixtures]
    report = coverage_inventory(seeds, max_scenarios=args.max_scenarios)
    write_json(args.output, report)
    print(json.dumps({
        "seed_count": report["seed_count"],
        "generated_scenarios": report["generated_scenarios"],
        "dual_adapter_executed_scenarios": report["dual_adapter_executed_scenarios"],
        "complete": report["complete"],
        "command_family_count": report["command_family_count"],
        "families": report["families"],
        "patterns": report["patterns"],
    }, indent=2, sort_keys=True))
    return 0


def generate_command(args: argparse.Namespace) -> int:
    seed = load_json(args.seed, "playtest seed")
    scenarios = generate_scenarios(
        seed, max_scenarios=args.max_scenarios,
        timing_offsets=args.timing_offsets)
    output = {
        "schema": "chonkcraft-bne-playtest-corpus-1",
        "seed_sha256": digest(seed),
        "scenario_count": len(scenarios),
        "scenarios": scenarios,
    }
    write_json(args.output, output)
    print(args.output.expanduser().resolve())
    return 0


def seed_fixture_command(args: argparse.Namespace) -> int:
    seed = seed_from_fixture(
        args.fixture, cycles=args.cycles,
        command_cycle=args.command_cycle, distance=args.distance)
    write_json(args.output, seed)
    print(args.output.expanduser().resolve())
    return 0


def seed_commanded_command(args: argparse.Namespace) -> int:
    seed = seed_from_commanded_fixture(args.fixture)
    write_json(args.output, seed)
    print(args.output.expanduser().resolve())
    return 0


def command_script_command(args: argparse.Namespace) -> int:
    scenario = load_json(args.scenario, "scenario")
    destination = args.output.expanduser().resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(destination.name + ".tmp")
    temporary.write_text(native_command_script(scenario), encoding="ascii")
    temporary.replace(destination)
    print(destination)
    return 0


def compare_command(args: argparse.Namespace) -> int:
    scenario = load_json(args.scenario, "scenario")
    native = load_json(args.native, "native result")
    java = load_json(args.java, "Java result")
    comparison = compare_results(native, java, scenario)
    write_json(args.output, comparison)
    print(args.output.expanduser().resolve())
    return 0 if comparison["difference_count"] == 0 else 2


def explore_command(args: argparse.Namespace) -> int:
    seed = load_json(args.seed, "playtest seed")
    native = Adapter("native", _command_tokens(args.native_command),
                     timeout=args.timeout)
    java = Adapter("java", _command_tokens(args.java_command),
                   timeout=args.timeout)
    report = explore(
        seed, native, java, args.output,
        max_scenarios=args.max_scenarios,
        max_differences=args.max_differences,
        minimize=not args.no_minimize,
        minimize_tests=args.minimize_tests,
    )
    print(args.output.expanduser().resolve() / "report.json")
    return 0 if report["difference_count"] == 0 else 2


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    commands = result.add_subparsers(dest="command", required=True)

    generate = commands.add_parser(
        "generate", help="compile a capability snapshot into player-order scenarios")
    generate.add_argument("seed", type=Path)
    generate.add_argument("--output", required=True, type=Path)
    generate.add_argument("--max-scenarios", type=int, default=256)
    generate.add_argument("--timing-offsets", type=int, nargs="+",
                          default=list(TIMING_OFFSETS))
    generate.set_defaults(func=generate_command)

    inventory = commands.add_parser(
        "coverage-inventory",
        help="generate from one or more idle fixtures without executing engines")
    inventory.add_argument("fixtures", nargs="+", type=Path)
    inventory.add_argument("--output", required=True, type=Path)
    inventory.add_argument("--max-scenarios", type=int, default=1200)
    inventory.set_defaults(func=coverage_inventory_command)

    fixture = commands.add_parser(
        "seed-fixture",
        help="turn a sealed native movement fixture into an exploration seed")
    fixture.add_argument("fixture", type=Path)
    fixture.add_argument("--output", required=True, type=Path)
    fixture.add_argument("--cycles", type=int, default=160)
    fixture.add_argument("--command-cycle", type=int, default=5)
    fixture.add_argument("--distance", type=int, default=4)
    fixture.set_defaults(func=seed_fixture_command)

    commanded = commands.add_parser(
        "seed-commanded",
        help="turn an authenticated commanded fixture into an exact playtest seed")
    commanded.add_argument("fixture", type=Path)
    commanded.add_argument("--output", required=True, type=Path)
    commanded.set_defaults(func=seed_commanded_command)

    script = commands.add_parser(
        "command-script",
        help="encode a move scenario for the guarded native command injector")
    script.add_argument("scenario", type=Path)
    script.add_argument("--output", required=True, type=Path)
    script.set_defaults(func=command_script_command)

    compare_parser = commands.add_parser(
        "compare", help="compare two already-produced scenario outcomes")
    compare_parser.add_argument("scenario", type=Path)
    compare_parser.add_argument("--native", required=True, type=Path)
    compare_parser.add_argument("--java", required=True, type=Path)
    compare_parser.add_argument("--output", required=True, type=Path)
    compare_parser.set_defaults(func=compare_command)

    run = commands.add_parser(
        "explore", help="generate, execute, prioritize, reduce and packetize")
    run.add_argument("seed", type=Path)
    run.add_argument("--native-command", required=True)
    run.add_argument("--java-command", required=True)
    run.add_argument("--output", required=True, type=Path)
    run.add_argument("--max-scenarios", type=int, default=256)
    run.add_argument("--max-differences", type=int, default=8)
    run.add_argument("--minimize-tests", type=int, default=64)
    run.add_argument("--timeout", type=float, default=180.0)
    run.add_argument("--no-minimize", action="store_true")
    run.set_defaults(func=explore_command)
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        return args.func(args)
    except (OSError, ValueError, KeyError, TypeError,
            subprocess.TimeoutExpired) as error:
        print(f"bne-playtest-explorer: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
