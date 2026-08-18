#!/usr/bin/env python3
"""Compile and certify the native/Java combat transaction matrix."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from typing import Any
import zipfile

import bne_playtest_explorer as explorer
import bne_playtest_native_adapter as native_adapter
import bne_rng_ledger
from bne_fixture import validate_fixture


REQUIREMENTS_SCHEMA = "chonkcraft-bne-combat-lifecycle-requirements-1"
PROOF_SCHEMA = "chonkcraft-bne-combat-lifecycle-proof-2"


def canonical_bytes(value: object) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False).encode("utf-8")


def digest(value: object) -> str:
    return hashlib.sha256(canonical_bytes(value)).hexdigest()


def file_identity(path: Path) -> dict[str, Any]:
    hashed = hashlib.sha256()
    size = 0
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            size += len(block)
            hashed.update(block)
    return {"bytes": size, "sha256": hashed.hexdigest()}


def seal_proof(rows: list[dict[str, Any]], evidence: dict[str, Any], *,
        diagnosis: dict[str, Any] | None = None) \
        -> dict[str, Any]:
    proof: dict[str, Any] = {
        "schema": PROOF_SCHEMA,
        "evidence": evidence,
        "rows": rows,
    }
    if diagnosis is not None:
        proof["diagnosis"] = diagnosis
    proof["proof_sha256"] = digest(proof)
    return proof


def validate_proof(proof: dict[str, Any]) -> None:
    if proof.get("schema") != PROOF_SCHEMA:
        raise ValueError("combat lifecycle proof schema mismatch")
    claimed = proof.get("proof_sha256")
    body = {key: value for key, value in proof.items()
            if key != "proof_sha256"}
    if not isinstance(claimed, str) or claimed != digest(body):
        raise ValueError("combat lifecycle proof identity changed")
    evidence = proof.get("evidence")
    if not isinstance(evidence, dict) or not evidence.get("fixture") \
            or not evidence.get("native_receipt") \
            or not evidence.get("java_receipt"):
        raise ValueError("combat lifecycle proof has no producer evidence")


def _resolve_evidence(proof_path: Path, locator: object) -> Path:
    if not isinstance(locator, str) or not locator:
        raise ValueError("combat lifecycle evidence locator is missing")
    path = Path(locator).expanduser()
    return path.resolve() if path.is_absolute() else (
        proof_path.parent / path).resolve()


def validate_evidence(proof_path: Path, proof: dict[str, Any]) -> None:
    validate_proof(proof)
    evidence = proof["evidence"]
    for key in ("fixture", "native_receipt", "java_receipt", "scenario"):
        item = evidence.get(key)
        if not isinstance(item, dict):
            raise ValueError(f"combat lifecycle {key} evidence is missing")
        path = _resolve_evidence(proof_path, item.get("path"))
        if not path.is_file() or file_identity(path) != {
                "bytes": item.get("bytes"), "sha256": item.get("sha256")}:
            raise ValueError(f"combat lifecycle {key} evidence changed")
        if key == "fixture":
            validate_fixture(path)
    native = json.loads(_resolve_evidence(
        proof_path, evidence["native_receipt"]["path"]).read_text())
    java = json.loads(_resolve_evidence(
        proof_path, evidence["java_receipt"]["path"]).read_text())
    scenario = json.loads(_resolve_evidence(
        proof_path, evidence["scenario"]["path"]).read_text())
    explorer.validate_scenario(scenario)
    explorer.validate_result(native, scenario, "native")
    explorer.validate_result(java, scenario, "java")
    if native["scenario_sha256"] != java["scenario_sha256"]:
        raise ValueError("combat lifecycle receipts ran different scenarios")
    diagnosis = proof.get("diagnosis")
    if diagnosis is not None:
        if not isinstance(diagnosis, dict) \
                or diagnosis.get("schema") != "combat-damage-rng-consumer-1":
            raise ValueError("combat lifecycle RNG diagnosis is malformed")
        for key in ("native_causal", "java_causal", "rng_diagnosis"):
            item = evidence.get(key)
            if not isinstance(item, dict):
                raise ValueError(f"combat lifecycle {key} evidence is missing")
            path = _resolve_evidence(proof_path, item.get("path"))
            if not path.is_file() or file_identity(path) != {
                    "bytes": item.get("bytes"), "sha256": item.get("sha256")}:
                raise ValueError(f"combat lifecycle {key} evidence changed")
        retained = json.loads(_resolve_evidence(
            proof_path, evidence["rng_diagnosis"]["path"]).read_text())
        if retained != diagnosis:
            raise ValueError("combat lifecycle RNG diagnosis changed")


def load_requirements(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if value.get("schema") != REQUIREMENTS_SCHEMA:
        raise ValueError("combat lifecycle requirements schema mismatch")
    encounters = value.get("encounters")
    if not isinstance(encounters, list) or not encounters:
        raise ValueError("combat lifecycle matrix has no encounters")
    seen: set[tuple[str, str, str]] = set()
    cells = []
    for encounter in encounters:
        encounter_id = str(encounter.get("id") or "")
        stances = encounter.get("stances") or []
        phases = encounter.get("phases") or []
        if not encounter_id or not stances or not phases:
            raise ValueError(f"incomplete combat encounter {encounter_id!r}")
        for stance in stances:
            for phase in phases:
                key = (encounter_id, str(stance), str(phase))
                if key in seen:
                    raise ValueError(f"duplicate combat lifecycle cell {key}")
                seen.add(key)
                cells.append({
                    "encounter": key[0], "stance": key[1], "phase": key[2],
                    "attacker": encounter.get("attacker"),
                    "defender": encounter.get("defender"),
                })
    return {
        "schema": REQUIREMENTS_SCHEMA,
        "encounters": len(encounters),
        "required_cells": len(cells),
        "cells": cells,
    }


def coverage(requirements: dict[str, Any], *proofs: dict[str, Any]) \
        -> dict[str, Any]:
    rows: dict[tuple[str, str, str], dict[str, Any]] = {}
    duplicates = []
    for proof in proofs:
        validate_proof(proof)
        for item in proof.get("rows") or []:
            key = (str(item.get("encounter")), str(item.get("stance")),
                   str(item.get("phase")))
            if key in rows:
                # A later proof may replace a weaker row for the same cell.
                previous = rows[key]
                if previous.get("exact") and previous.get("causal_order_exact") \
                        and previous.get("native_observed") \
                        and previous.get("java_observed"):
                    duplicates.append({"encounter": key[0], "stance": key[1],
                                       "phase": key[2]})
                    continue
            rows[key] = item
    exact = 0
    debts = []
    for expected in requirements["cells"]:
        key = (expected["encounter"], expected["stance"], expected["phase"])
        item = rows.get(key)
        reasons = []
        if item is None:
            reasons.append("missing-proof")
        else:
            if not item.get("native_observed"):
                reasons.append("native-not-observed")
            if not item.get("java_observed"):
                reasons.append("java-not-observed")
            if not item.get("exact"):
                reasons.append("native-java-divergent")
            if not item.get("causal_order_exact"):
                reasons.append("causal-order-not-exact")
        if reasons:
            debts.append({"encounter": key[0], "stance": key[1],
                          "phase": key[2], "reasons": reasons})
        else:
            exact += 1
    return {
        "complete": not debts and not duplicates
                    and exact == requirements["required_cells"],
        "encounters": requirements["encounters"],
        "exact": exact,
        "required": requirements["required_cells"],
        "debts": debts,
        "duplicates": duplicates,
    }


def _lifecycle_events(result: dict[str, Any]) \
        -> dict[int, list[dict[str, Any]]]:
    by_unit: dict[int, list[dict[str, Any]]] = {}
    for event in result.get("events") or []:
        if event.get("kind") != "combat-state" \
                or not isinstance(event.get("cycle"), int) \
                or not isinstance(event.get("unit_id"), int):
            continue
        normalized = {key: event.get(key) for key in (
            "cycle", "kind", "unit_id", "present", "alive", "on_map",
            "x", "y", "offset_x", "offset_y", "order", "hit_points",
            "target_id", "missile_count", "sequence", "animation_timer",
            "animation_state",
        )}
        by_unit.setdefault(event["unit_id"], []).append(normalized)
    for events in by_unit.values():
        events.sort(key=lambda item: item["cycle"])
    return by_unit


def _projectile_events(result: dict[str, Any]) \
        -> dict[object, list[dict[str, Any]]]:
    by_projectile: dict[object, list[dict[str, Any]]] = {}
    for event in result.get("events") or []:
        if event.get("kind") != "combat-projectile" \
                or not isinstance(event.get("cycle"), int) \
                or event.get("projectile_id") is None:
            continue
        normalized = {key: event.get(key) for key in (
            "cycle", "kind", "projectile_id", "present", "source_id",
            "target_id", "type", "type_code", "x", "y", "frame",
            "remaining", "pool_slot",
        )}
        by_projectile.setdefault(event["projectile_id"], []).append(normalized)
    for events in by_projectile.values():
        events.sort(key=lambda item: item["cycle"])
    return by_projectile


def _projectile_phases(result: dict[str, Any], attacker: int,
        issue_cycle: int) -> dict[str, dict[str, Any]]:
    candidates = []
    for events in _projectile_events(result).values():
        created = _first(events, lambda item: bool(item["present"]),
                         after=issue_cycle)
        if created is not None and created.get("source_id") == attacker:
            candidates.append((created["cycle"], events))
    if not candidates:
        return {}
    _, events = min(candidates, key=lambda item: item[0])
    created = _first(events, lambda item: bool(item["present"]),
                     after=issue_cycle)
    assert created is not None
    phases = {"projectile-create": created}
    flight = _first(events, lambda item: (
        bool(item["present"]) and item["cycle"] > created["cycle"]
        and (item.get("x"), item.get("y")) !=
        (created.get("x"), created.get("y"))))
    if flight is not None:
        phases["projectile-flight"] = flight
    removed = _first(events, lambda item: not bool(item["present"]),
                     after=created["cycle"] + 1)
    if removed is not None:
        phases["impact"] = removed
    return phases


def _first(events: list[dict[str, Any]], predicate, *, after: int = 0) \
        -> dict[str, Any] | None:
    for event in events:
        if event["cycle"] >= after and predicate(event):
            return event
    return None


def _phase_events(result: dict[str, Any], attacker: int, defender: int,
        issue_cycle: int) -> dict[str, dict[str, Any]]:
    units = _lifecycle_events(result)
    attack = units.get(attacker, [])
    defend = units.get(defender, [])
    phases: dict[str, dict[str, Any]] = {}
    acquired = _first(attack, lambda item: item["order"] == "ATTACK",
                      after=issue_cycle)
    if acquired is not None:
        phases["acquire"] = acquired
    baseline = next((item for item in reversed(attack)
                     if item["cycle"] < issue_cycle), attack[0] if attack else None)
    if baseline is not None:
        chased = _first(attack, lambda item: (
            item["order"] == "ATTACK" and
            (item["offset_x"], item["offset_y"]) !=
            (baseline["offset_x"], baseline["offset_y"])), after=issue_cycle)
        if chased is not None:
            phases["chase"] = chased
    defender_by_cycle = {item["cycle"]: item for item in defend}
    swung = _first(attack, lambda item: (
        item["animation_state"] == "ATTACK"
        and item["cycle"] in defender_by_cycle
        and max(abs(item["x"] - defender_by_cycle[item["cycle"]]["x"]),
                abs(item["y"] - defender_by_cycle[item["cycle"]]["y"])) <= 1),
        after=issue_cycle)
    if swung is not None:
        phases["swing"] = swung
    previous = None
    for item in defend:
        if item["cycle"] < issue_cycle:
            previous = item
            continue
        if previous is not None and item["hit_points"] < previous["hit_points"]:
            phases.setdefault("damage", item)
        # Retail's DYING order owns the beginning of death. The native unit
        # pool keeps that record allocated (and its live bit set) while the
        # death program runs, whereas Java's isAlive() becomes false at the
        # same semantic boundary. Requiring only the allocator bit hid real
        # native deaths and made this phase appear Java-only.
        if previous is not None and (
                (previous["order"] != "DYING" and item["order"] == "DYING")
                or (previous["alive"] and not item["alive"])):
            phases.setdefault("death", item)
        if previous is not None and previous["present"] and not item["present"]:
            phases.setdefault("free", item)
        if item["order"] == "ATTACK":
            phases.setdefault("retaliation", item)
        previous = item
    phases.update(_projectile_phases(result, attacker, issue_cycle))
    return phases


def _prefix_exact(native: dict[int, list[dict[str, Any]]],
        java: dict[int, list[dict[str, Any]]], through: int) -> bool:
    for unit_id in sorted(set(native) | set(java)):
        comparable = ("cycle", "unit_id", "present", "alive", "on_map",
                      "x", "y", "offset_x", "offset_y", "order",
                      "hit_points", "missile_count")
        left = [{key: item[key] for key in comparable}
                for item in native.get(unit_id, []) if item["cycle"] <= through]
        right = [{key: item[key] for key in comparable}
                 for item in java.get(unit_id, []) if item["cycle"] <= through]
        if left != right:
            return False
    return True


def _canonical_projectile_prefix(result: dict[str, Any], through: int) \
        -> list[dict[str, Any]]:
    """Normalize allocator-local projectile identities by birth order."""
    source = _projectile_events(result)
    ordered = sorted(source.items(), key=lambda item: (
        item[1][0]["cycle"], str(item[0])))
    canonical = []
    for ordinal, (_identity, events) in enumerate(ordered):
        for item in events:
            if item["cycle"] > through:
                continue
            # Native +0x09 is the parabolic table (0/5/10). Java stores the
            # animation row (0/1/2). Phase signatures already omit frame;
            # keeping the two encodings in the causal prefix made Human 13's
            # catapult look like a combat mismatch while remaining, pixels
            # and constructor identity were already exact.
            canonical.append({key: value for key, value in {
                "cycle": item["cycle"], "projectile": ordinal,
                "present": item["present"], "source_id": item["source_id"],
                "target_id": item["target_id"],
                "type_code": item["type_code"], "x": item["x"],
                "y": item["y"], "remaining": item["remaining"],
            }.items()})
    canonical.sort(key=lambda item: (item["cycle"], item["projectile"]))
    return canonical


def _phase_signature(phase: str, item: dict[str, Any] | None) -> object:
    if item is None:
        return None
    fields = {
        "acquire": ("cycle", "order"),
        "chase": ("cycle", "x", "y", "offset_x", "offset_y", "order"),
        "swing": ("cycle", "animation_state", "sequence", "animation_timer"),
        "damage": ("cycle", "hit_points", "alive"),
        "retaliation": ("cycle", "order"),
        "death": ("cycle", "alive", "hit_points"),
        "free": ("cycle", "present", "on_map"),
        "projectile-create": ("cycle", "source_id", "target_id",
                              "type_code", "x", "y", "remaining"),
        "projectile-flight": ("cycle", "source_id", "target_id",
                              "type_code", "x", "y", "remaining"),
        "impact": ("cycle", "source_id", "target_id", "type_code",
                   "present"),
    }.get(phase, tuple(sorted(item)))
    return {key: item.get(key) for key in fields}


def derive_rows(native: dict[str, Any], java: dict[str, Any], *,
        encounter: str, stance: str, attacker: int, defender: int,
        issue_cycle: int, evidence_sha256: str) -> list[dict[str, Any]]:
    native_phases = _phase_events(native, attacker, defender, issue_cycle)
    java_phases = _phase_events(java, attacker, defender, issue_cycle)
    native_stream = _lifecycle_events(native)
    java_stream = _lifecycle_events(java)
    rows = []
    for phase in sorted(set(native_phases) | set(java_phases)):
        left = native_phases.get(phase)
        right = java_phases.get(phase)
        exact = (left is not None and right is not None
                 and _phase_signature(phase, left)
                 == _phase_signature(phase, right))
        through = max(item["cycle"] for item in (left, right)
                      if item is not None)
        rows.append({
            "encounter": encounter,
            "stance": stance,
            "phase": phase,
            "native_observed": left is not None,
            "java_observed": right is not None,
            "exact": exact,
            "causal_order_exact": exact and _prefix_exact(
                native_stream, java_stream, through)
                and _canonical_projectile_prefix(native, through)
                == _canonical_projectile_prefix(java, through),
            "native_cycle": None if left is None else left["cycle"],
            "java_cycle": None if right is None else right["cycle"],
            "evidence_sha256": evidence_sha256,
        })
    return rows


def damage_rng_diagnosis(native_text: str, java_text: str) -> dict[str, Any]:
    """Bind the first damage roll to the consumer that spent its seed.

    The general RNG ledger correctly reports the earliest stream mismatch,
    which can predate a combat transaction during map construction. For a
    combat proof we additionally need the causal question: did the damage
    operation itself consume the same transition? This report answers that
    without skipping or inventing a draw.
    """
    native = bne_rng_ledger.parse_native_draws(native_text, stream="async")
    java = bne_rng_ledger.parse_java_draws(java_text, stream="async")
    # 0x0041834b is the fixed/arrow constructor return (0x004182b0);
    # 0x00418412 is the mobile/melee return (0x00418370). Both spend the
    # same half-plus-remainder debit. Watching only 0x00418412 treated
    # Human 13's first tower arrow as a Java-only damage consumer.
    native_damage = next((draw for draw in native
                          if draw.caller in ("0x00418412", "0x0041834b")), None)
    java_damage = next((draw for draw in java if draw.caller and any(
        token in draw.caller for token in (
            "battleNetMeleeDamage", "battleNetProjectileDamage"))), None)
    report: dict[str, Any] = {
        "schema": "combat-damage-rng-consumer-1",
        "stream": "async",
        "native_draws": len(native),
        "java_draws": len(java),
        "native_damage_observed": native_damage is not None,
        "java_damage_observed": java_damage is not None,
    }
    if native_damage is None or java_damage is None:
        report.update({
            "exact": False,
            "classification": "damage-consumer-evidence-missing",
        })
        return report
    by_java = {draw.transition: draw for draw in java}
    by_native = {draw.transition: draw for draw in native}
    java_at_native_seed = by_java.get(native_damage.transition)
    native_at_java_seed = by_native.get(java_damage.transition)
    exact = native_damage.transition == java_damage.transition
    report.update({
        "exact": exact,
        "classification": ("exact-damage-consumer" if exact
                           else "damage-consumer-order-mismatch"),
        "native_damage": native_damage.as_dict(),
        "java_damage": java_damage.as_dict(),
        "java_consumer_of_native_damage_seed": (
            None if java_at_native_seed is None else java_at_native_seed.as_dict()),
        "native_consumer_of_java_damage_seed": (
            None if native_at_java_seed is None else native_at_java_seed.as_dict()),
    })
    return report


def _write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(canonical_bytes(value) + b"\n")


def observe_commanded(args: argparse.Namespace) -> dict[str, Any]:
    fixture = args.fixture.expanduser().resolve()
    output = args.output.expanduser().resolve()
    fixture_validation = validate_fixture(fixture)
    seed = explorer.seed_from_commanded_fixture(fixture)
    scenario = explorer.scenario_from_commanded_seed(seed)
    observed_units = list(dict.fromkeys([
        args.attacker, args.defender, *(args.observe_unit or []),
    ]))
    paired = {int(item["id"]) for key in ("actors", "targets")
              for item in scenario.get(key, []) if isinstance(item, dict)
              and isinstance(item.get("id"), int)}
    first = native_adapter.load_frames(fixture)[0]
    if any(unit_id not in paired for unit_id in observed_units):
        for unit_id in observed_units:
            if unit_id in paired:
                continue
            raw = first["units"].get(unit_id)
            if raw is None:
                raise ValueError(
                    f"combat ambient unit {unit_id} is absent at cycle one")
            scenario.setdefault("targets", []).append({
                "id": unit_id,
                "player": raw[44],
                "domain": "land",
                "x": int.from_bytes(raw[24:26], "little"),
                "y": int.from_bytes(raw[26:28], "little"),
            })
            paired.add(unit_id)
    # Combat-projectile source/target IDs are native slots. Pair every
    # live first-frame unit so an ambient catapult keeps its slot number
    # on the Java twin -- which is why Human 13's cycle-5 rocks are
    # 1479/1488 on both sides instead of Java null.
    pair_units = []
    for unit_id, raw in first["units"].items():
        if not native_adapter.live(raw):
            continue
        state = native_adapter.snapshot(raw, 0)
        if not state.get("on_map"):
            continue
        pair_units.append({
            "id": int(unit_id),
            "player": raw[44],
            "x": state["tile_x"],
            "y": state["tile_y"],
        })
    if pair_units:
        scenario["pair_units"] = pair_units
    scenario["combat_observation"] = {
        "unit_ids": observed_units,
        "encounter": args.encounter,
        "stance": args.stance,
    }
    scenario["scenario_sha256"] = explorer.digest({
        key: value for key, value in scenario.items()
        if key != "scenario_sha256"
    })
    explorer.validate_scenario(scenario)
    command = next((item for item in scenario["commands"]
                    if item["unit_id"] == args.attacker), None)
    if command is None:
        raise ValueError("combat attacker has no command in fixture")
    if command["kind"] not in {"attack", "attack-move", "attack-ground",
                               "patrol", "stand-ground"}:
        raise ValueError("combat fixture command is not an attack stance")
    evidence_dir = output.parent / (output.stem + ".evidence")
    evidence_dir.mkdir(parents=True, exist_ok=True)
    scenario_path = evidence_dir / "scenario.json"
    native_path = evidence_dir / "native.json"
    java_path = evidence_dir / "java.json"
    native_causal_path = evidence_dir / "native-causal.txt"
    java_causal_path = evidence_dir / "java-causal.jsonl"
    rng_diagnosis_path = evidence_dir / "damage-rng.json"
    _write_json(scenario_path, scenario)
    authority = explorer.PINNED_BNE_EXECUTABLE_SHA256
    native = native_adapter.run_from_fixture(
        scenario, fixture, authority,
        file_identity(Path(native_adapter.__file__))["sha256"])
    explorer.validate_result(native, scenario, "native")
    _write_json(native_path, native)
    with zipfile.ZipFile(fixture) as archive:
        if "trace.txt" not in archive.namelist():
            raise ValueError("combat fixture has no native causal trace")
        native_causal_path.write_bytes(archive.read("trace.txt"))
    if not args.skip_build:
        root = Path(__file__).resolve().parents[3]
        built = subprocess.run([
            str(root / "scripts/jbr/with-jbr-25.sh"), "mvn", "-q",
            "-pl", "desktop", "-am", "-DskipTests", "compile",
        ], cwd=root, check=False, capture_output=True, text=True)
        if built.returncode != 0:
            raise ValueError("combat Java adapter build failed: "
                             + (built.stdout + built.stderr)[-2000:])
    java_script = Path(__file__).resolve().with_name(
        "bne_playtest_java_adapter.py")
    java_causal_path.unlink(missing_ok=True)
    adapter = explorer.Adapter("java", [
        "/usr/bin/env",
        f"CHONKCRAFT_TRACE_BNE_CAUSAL={java_causal_path}",
        sys.executable, str(java_script), "--scenario", "{scenario}",
        "--output", "{output}", "--asset-pack",
        str(args.asset_pack.expanduser().resolve()), "--skip-build",
    ], timeout=args.timeout)
    with tempfile.TemporaryDirectory() as directory:
        java = adapter.run(scenario, Path(directory))
    _write_json(java_path, java)
    if not java_causal_path.is_file():
        raise ValueError("combat Java adapter wrote no causal trace")
    diagnosis = damage_rng_diagnosis(
        native_causal_path.read_text(encoding="utf-8", errors="replace"),
        java_causal_path.read_text(encoding="utf-8", errors="replace"))
    _write_json(rng_diagnosis_path, diagnosis)
    receipt_identity = digest({
        "fixture": file_identity(fixture),
        "scenario": file_identity(scenario_path),
        "native": file_identity(native_path),
        "java": file_identity(java_path),
    })
    rows = derive_rows(native, java, encounter=args.encounter,
                       stance=args.stance, attacker=args.attacker,
                       defender=args.defender,
                       issue_cycle=int(command["issue_cycle"]),
                       evidence_sha256=receipt_identity)
    def evidence_item(path: Path) -> dict[str, Any]:
        path = path.expanduser().resolve()
        try:
            locator = str(path.relative_to(output.parent))
        except ValueError:
            locator = str(path)
        return {"path": locator, **file_identity(path)}
    proof = seal_proof(rows, {
        "receipt_sha256": receipt_identity,
        "fixture_id": fixture_validation["fixture_id"],
        "fixture": evidence_item(fixture),
        "scenario": evidence_item(scenario_path),
        "native_receipt": evidence_item(native_path),
        "java_receipt": evidence_item(java_path),
        "native_causal": evidence_item(native_causal_path),
        "java_causal": evidence_item(java_causal_path),
        "rng_diagnosis": evidence_item(rng_diagnosis_path),
        "engine_input_sha256": java["producer"]["build_sha256"],
    }, diagnosis=diagnosis)
    _write_json(output, proof)
    validate_evidence(output, proof)
    return proof


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    inventory = sub.add_parser("inventory")
    inventory.add_argument("requirements", type=Path)
    inventory.add_argument("--output", type=Path)
    check = sub.add_parser("coverage")
    check.add_argument("requirements", type=Path)
    check.add_argument("proof", type=Path, nargs="+")
    observe = sub.add_parser("observe-commanded")
    observe.add_argument("requirements", type=Path)
    observe.add_argument("fixture", type=Path)
    observe.add_argument("--asset-pack", required=True, type=Path)
    observe.add_argument("--encounter", required=True)
    observe.add_argument("--stance", required=True)
    observe.add_argument("--attacker", required=True, type=int)
    observe.add_argument("--defender", required=True, type=int)
    observe.add_argument("--observe-unit", action="append", type=int,
                         help="additional ambient native slot in the causal prefix")
    observe.add_argument("--output", required=True, type=Path)
    observe.add_argument("--skip-build", action="store_true")
    observe.add_argument("--timeout", type=float, default=180.0)
    args = parser.parse_args(argv)
    required = load_requirements(args.requirements)
    if args.command == "inventory":
        rendered = json.dumps(required, indent=2, sort_keys=True) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(rendered, encoding="utf-8")
        print(rendered, end="")
        return 0
    if args.command == "observe-commanded":
        allowed = {(item["encounter"], item["stance"])
                   for item in required["cells"]}
        if (args.encounter, args.stance) not in allowed:
            raise ValueError("combat encounter/stance is not in requirements")
        proof = observe_commanded(args)
        report = coverage(required, proof)
        print(json.dumps({
            "proof": str(args.output.expanduser().resolve()),
            "derived_rows": len(proof["rows"]),
            "exact": report["exact"],
            "required": report["required"],
            "damage_rng": proof.get("diagnosis"),
        }, indent=2, sort_keys=True))
        return 0
    proofs = []
    for path in args.proof:
        proof = json.loads(path.read_text(encoding="utf-8"))
        validate_evidence(path.expanduser().resolve(), proof)
        proofs.append(proof)
    report = coverage(required, *proofs)
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report["complete"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
