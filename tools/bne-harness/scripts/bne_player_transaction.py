#!/usr/bin/env python3
"""Compile and compare end-to-end BNE player transaction receipts.

A resolved unit order is not a player transaction.  A transaction begins at
the physical input, retains the ordered selection and target interpretation,
then follows every fanned-out wire command through acceptance, first physical
progress, and its terminal result.  Missing layers stay visible as coverage
debt rather than being rounded into an exact per-unit command score.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shlex
import subprocess
import zipfile
from pathlib import Path
from typing import Any

import bne_fixture
import bne_identity


SCHEMA = "chonkcraft-bne-player-transactions-2"
CERTIFICATION_SCHEMA = "chonkcraft-bne-player-transaction-certification-2"
CATALOG_SCHEMA = "chonkcraft-bne-player-transaction-catalog-2"
REQUIREMENTS_SCHEMA = "chonkcraft-bne-player-transaction-requirements-2"
UNIT_IDENTITY_SCHEMA = "chonkcraft-bne-player-unit-identities-1"
PINNED_BNE_EXECUTABLE_SHA256 = (
    "b0e914a9cb7dcc81a205e700a9bb0a1d0649df19d459388051ba170783d2c807"
)

# A receipt is only as strong as the layers it actually observed.  In
# particular, an order installed through GiveOrder is not proof of a mouse
# gesture, group fan-out, player feedback, or target interpretation.  Keep the
# names stable: they are also the columns in the requirements manifest.
TRANSACTION_LAYERS = (
    "gesture",
    "ordered-selection",
    "target-interpretation",
    "wire-fanout",
    "acceptance",
    "acknowledgement",
    "first-progress",
    "terminal-outcome",
)

# Keep this byte-for-byte aligned with the next-level gate.  Physical input is
# interpreted in desktop/GameScreen before the engine sees a command, so the
# narrower engine survey identity cannot authenticate a player transaction by
# itself.
PROGRAM_INPUT_PATHS = (
    "desktop", "engine", "tools/bne-harness/scripts",
    "tools/bne-harness/src", "tools/bne-harness/tests",
    "tools/bne-harness/ghidra_scripts",
    "tools/bne-harness/player-transaction-requirements.json",
    "tools/bne-harness/ai-fleet-requirements.json",
    "tools/bne-harness/combat-lifecycle-requirements.json",
    "engine/src/main/resources/chonkcraft/missions.tsv",
    "scripts/check-bne-next-level-gate.sh",
    "scripts/capture-bne-ai-cycle.sh", "scripts/deploy-bne-tracer.sh",
    "scripts/jbr",
)

# GameCommand is ChonkCraft's deterministic multiplayer envelope.  Retail's
# physical UI records the smaller replay command that fed GiveOrder instead.
# These are different transport formats, so certification decodes each one
# and compares the authenticated GiveOrder fields they have in common.  Keep
# this tuple in the exact ordinal order of GameCommand.Kind.
JAVA_WIRE_FAMILIES = (
    "none", "move", "attack", "stop", "harvest", "build", "train",
    "research", "cast", "patrol", "repair", "explore", "return-goods",
    "stand-ground", "attack-ground", "unload", "unload-one", "board",
    "ping", "quit", "autocast", "follow", "upgrade-to", "cancel-train",
    "cancel-research", "cancel-upgrade-to", "cancel-build", "rally-point",
    "dismiss", "attack-move", "defend",
)

# 0x00475f80 consumes retail's eight-byte 0x13 command as x, y, target slot,
# then an ORDER_FUNCTIONS index.  These indices are independently grounded by
# the pinned replay corpus and the native tracer's GiveOrder hook.
BNE_GIVE_ORDER_FUNCTIONS = {
    "stop": 2,
    "move": 3,
    "patrol": 5,
    "attack": 8,
    "attack-move": 8,
    "attack-ground": 17,
    "harvest": 23,
    "return-goods": 24,
    "repair": 27,
}


def _canonical_family(value: str | None) -> str:
    return str(value or "unknown").strip().lower().replace("_", "-")


def _digest(value: Any) -> str:
    packed = json.dumps(value, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(packed.encode("utf-8")).hexdigest()


def _valid_sha256(value: object) -> bool:
    return (isinstance(value, str) and len(value) == 64
            and all(character in "0123456789abcdef" for character in value))


def _current_engine_input_sha256() -> str:
    root = Path(__file__).resolve().parents[3]
    value = bne_identity.engine_input_identity(root).get("engine_input_sha256")
    if not _valid_sha256(value):
        raise ValueError("current Java engine has no hermetic input identity")
    return str(value)


def current_program_input_sha256() -> str:
    """Hash the current desktop+engine+adapter source/build closure."""
    root = Path(__file__).resolve().parents[3]
    head = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    diff = subprocess.check_output(
        ["git", "diff", "--binary", "HEAD", "--", *PROGRAM_INPUT_PATHS],
        cwd=root)
    digest = hashlib.sha256()
    digest.update(b"next-level-program-v1\0" + head.encode() + b"\0" + diff)
    untracked = subprocess.check_output(
        ["git", "ls-files", "--others", "--exclude-standard", "-z", "--",
         *PROGRAM_INPUT_PATHS], cwd=root).split(b"\0")
    for raw in sorted(item for item in untracked if item):
        path = root / raw.decode("utf-8", "surrogateescape")
        digest.update(b"path\0" + raw + b"\0")
        digest.update(path.read_bytes())
    return digest.hexdigest()


def _load(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"receipt is not an object: {path}")
    return value


def _normalize_unit_identities(raw: object) -> dict[str, Any] | None:
    if raw is None:
        return None
    if not isinstance(raw, dict) or raw.get("schema") != UNIT_IDENTITY_SCHEMA \
            or not isinstance(raw.get("units"), list):
        raise ValueError("player evidence has an invalid unit identity table")
    seen_local: set[tuple[int, int]] = set()
    seen_stable: set[str] = set()
    units = []
    for item in raw["units"]:
        if not isinstance(item, dict) or not isinstance(item.get("identity"), dict):
            raise ValueError("player unit identity is not a complete object")
        local_id = int(item.get("local_id", -1))
        generation = int(item.get("generation", -1))
        key = (local_id, generation)
        stable = _digest(item["identity"])
        if local_id < 0 or generation < 0 or key in seen_local \
                or stable in seen_stable:
            raise ValueError("player unit identity repeats or omits a lifetime")
        seen_local.add(key)
        seen_stable.add(stable)
        units.append({
            "local_id": local_id,
            "generation": generation,
            "identity": item["identity"],
            "stable_sha256": stable,
        })
    units.sort(key=lambda item: (item["local_id"], item["generation"]))
    generations: dict[int, list[int]] = {}
    for item in units:
        generations.setdefault(item["local_id"], []).append(item["generation"])
    if any(values != list(range(len(values)))
           for values in generations.values()):
        raise ValueError("player unit identity generations must start at zero")
    return {"schema": UNIT_IDENTITY_SCHEMA, "units": units}


def authority_from_native_manifest(manifest: dict[str, Any], trace: bytes,
        *, manifest_source: str,
        fixture_validation: dict[str, Any]) -> dict[str, Any]:
    """Bind a native UI receipt to the complete sealed oracle closure.

    A pinned executable hash written into arbitrary JSON is not evidence that
    the executable ran.  The fixture archive, canonical fixture key, trace,
    scenario, command stream, tracer and retail-source identities must all
    close before a receipt may call itself authenticated.
    """
    if manifest.get("schema") != 2:
        raise ValueError("native UI manifest has the wrong schema")
    executable_sha = ((manifest.get("oracle") or {}).get("executable") or {}).get(
        "sha256")
    run = manifest.get("run") or {}
    trace_record = run.get("trace") or {}
    trace_sha = trace_record.get("sha256")
    fixture = manifest.get("fixture") or {}
    fixture_key = fixture.get("key")
    fixture_id = fixture.get("id")
    actual_trace_sha = hashlib.sha256(trace).hexdigest()
    if executable_sha != PINNED_BNE_EXECUTABLE_SHA256:
        raise ValueError("native UI manifest is not pinned BNE 2.02b")
    if trace_sha != actual_trace_sha or trace_record.get("bytes") != len(trace):
        raise ValueError("native UI trace does not match its sealed manifest")
    if not isinstance(fixture_key, dict) or not _valid_sha256(fixture_id) \
            or _digest(fixture_key) != fixture_id:
        raise ValueError("native UI manifest has no fixture identity")
    if fixture_validation.get("fixture_id") != fixture_id \
            or not _valid_sha256(fixture_validation.get("sha256")):
        raise ValueError("native UI fixture archive does not close its manifest")
    harness = manifest.get("harness") or {}
    tracer_sha = (harness.get("tracer") or {}).get("sha256")
    injector_sha = (harness.get("injector") or {}).get("sha256")
    oracle_data = manifest.get("oracle", {}).get("data") or {}
    data_key = {name: value.get("sha256") for name, value in oracle_data.items()
                if isinstance(value, dict)}
    commands = run.get("commands")
    commands_sha = None if commands is None else (
        (commands.get("file") or {}).get("sha256"))
    validation = run.get("validation") or {}
    replay = run.get("replay")
    replay_key = None if replay is None else {
        "startup_sha256": replay.get("startup_sha256"),
        "packet_schedule_sha256": replay.get("packet_schedule_sha256"),
    }
    closure = {
        "oracle_executable": executable_sha,
        "oracle_data": data_key,
        "tracer": tracer_sha,
        "scenario": run.get("requested_scenario"),
        "cycle_limit": run.get("cycle_limit"),
        "initialization_seed": run.get("initialization_seed"),
        "commands": commands_sha,
        "replay": replay_key,
        "simulation": validation.get("simulation_sha256"),
        "state_schema": (harness.get("state_schema")),
        "schema": fixture_key.get("schema"),
    }
    if fixture_key != closure:
        raise ValueError("native UI fixture key does not match its run closure")
    if not _valid_sha256(tracer_sha) or not _valid_sha256(injector_sha):
        raise ValueError("native UI manifest omits harness identities")
    source_manifest_sha = (((manifest.get("source") or {}).get("manifest") or {})
                           .get("sha256"))
    if not _valid_sha256(source_manifest_sha):
        raise ValueError("native UI manifest omits retail source identity")
    return {
        "side": "native",
        "producer": "pinned-bne-2.02b-ui-handler-trace",
        "authenticated": True,
        "build_sha256": executable_sha,
        "trace_sha256": actual_trace_sha,
        "fixture_id": fixture_id,
        "scenario_id": fixture_id,
        "scenario": run.get("requested_scenario"),
        "manifest_sha256": _digest(manifest),
        "fixture_archive_sha256": fixture_validation["sha256"],
        "tracer_sha256": tracer_sha,
        "injector_sha256": injector_sha,
        "source_manifest_sha256": source_manifest_sha,
        "manifest_source": manifest_source,
    }


_UI_RIGHT_CLICK = re.compile(
    r"event=ui-right-click cycle=(?P<cycle>-?\d+) x=(?P<x>\d+) "
    r"y=(?P<y>\d+) ui-player=(?P<player>\d+) selected=(?P<selected>\S*)")
_UI_FANOUT = re.compile(
    r"event=ui-fanout cycle=(?P<cycle>-?\d+) unit=(?P<unit>\d+) "
    r"order=(?P<order>\d+) next-order=(?P<next_order>\d+) "
    r"order-x=(?P<order_x>\d+) order-y=(?P<order_y>\d+)")
_UNIT_STATE = re.compile(
    r"event=command-unit-state cycle=(?P<cycle>-?\d+) unit=(?P<unit>\d+) "
    r".* order=(?P<order>\d+) next-order=(?P<next_order>\d+) "
    r"order-x=(?P<order_x>\d+) order-y=(?P<order_y>\d+)")


_ORDER_NAME = {
    1: "STILL",
    2: "STAND_GROUND",
    3: "MOVE",
    5: "PATROL",
    8: "ATTACK",
    9: "ATTACK",
    10: "ATTACK_MOVE",
    11: "ATTACK_MOVE",
}


def _trace_fields(line: str) -> dict[str, str]:
    result: dict[str, str] = {}
    for token in shlex.split(line):
        if "=" in token:
            key, value = token.split("=", 1)
            result[key] = value
    return result


def _optional_int(value: str | None) -> int | None:
    if value in (None, "", "none", "null", "-"):
        return None
    return int(value)


def _bool(value: str | None) -> bool | None:
    if value in ("true", "1"):
        return True
    if value in ("false", "0"):
        return False
    return None


def _valid_wire_hex(value: object) -> bool:
    if not isinstance(value, str) or not value or len(value) % 2:
        return False
    try:
        return bytes.fromhex(value).hex() == value.casefold()
    except ValueError:
        return False


def _layered_trace_evidence(trace: str, map_path: str | None,
        authority: dict[str, Any]) -> dict[str, Any] | None:
    """Parse the lossless cross-engine player-transaction trace contract.

    The older DoRightButton hook below remains useful evidence, but it cannot
    prove the wire bytes, acknowledgement, or a terminal result.  Producers
    that emit this contract can prove every layer without the compiler
    inventing any of them.
    """
    gestures = []
    orders = []
    feedback = []
    decisions = []
    outcomes = []
    identities = []
    for line in trace.splitlines():
        fields = _trace_fields(line)
        event = fields.get("event")
        if event == "player-gesture":
            transaction_id = int(fields["transaction"])
            selected = [int(value) for value in fields.get(
                "selected", "").split(",") if value]
            gestures.append({
                "intent_id": int(fields.get("intent", transaction_id)),
                "transaction_id": transaction_id,
                "cycle": int(fields["cycle"]),
                "event": "gesture",
                "selected_unit_ids": selected,
                "gesture": {
                    "origin": fields.get("origin"),
                    "detail": fields.get("detail"),
                    "screen_x": _optional_int(fields.get("screen-x")),
                    "screen_y": _optional_int(fields.get("screen-y")),
                    "tile_x": _optional_int(fields.get("tile-x")),
                    "tile_y": _optional_int(fields.get("tile-y")),
                    "modifiers": fields.get("modifiers"),
                    "target_id": _optional_int(fields.get("target-id")),
                    "target_shape": fields.get("target-shape"),
                },
            })
        elif event == "player-order":
            transaction_id = int(fields["transaction"])
            intent_id = int(fields["intent"])
            selected = [int(value) for value in fields.get(
                "selected", "").split(",") if value]
            orders.append({
                "intent_id": intent_id,
                "transaction_id": transaction_id,
                "cycle": int(fields["cycle"]),
                "event": "order",
                "selected_unit_ids": selected,
                "accepted": _bool(fields.get("accepted")),
                "command": {
                    "fanout_ordinal": int(fields["ordinal"]),
                    "kind": fields["family"],
                    "player": int(fields["player"]),
                    "unit_id": int(fields["unit"]),
                    "x": _optional_int(fields.get("x")),
                    "y": _optional_int(fields.get("y")),
                    "target_id": _optional_int(fields.get("target-id")),
                    "type_index": _optional_int(fields.get("type-index")),
                    "queued": _bool(fields.get("queued")),
                    "wire_hex": fields.get("wire"),
                },
            })
        elif event == "player-feedback":
            feedback.append({
                "intent_id": int(fields["intent"]),
                "transaction_id": int(fields["transaction"]),
                "cycle": _optional_int(fields.get("cycle")),
                "acknowledged": _bool(fields.get("acknowledged")),
                "mode": fields.get("mode"),
                "detail": fields.get("detail"),
            })
        elif event == "player-decision":
            decisions.append({
                "transaction_id": int(fields["transaction"]),
                "accepted": _bool(fields.get("accepted")),
                "family": fields.get("family"),
                "queued": _bool(fields.get("queued")),
                "reason": fields.get("reason"),
                "cycle": _optional_int(fields.get("cycle")),
            })
        elif event == "player-unit-identity":
            identities.append({
                "local_id": int(fields["local-id"]),
                "generation": int(fields.get("generation", 0)),
                "identity": {
                    "origin": fields.get("origin", "initial"),
                    "owner": int(fields["owner"]),
                    "type": fields["type"],
                    "x": int(fields["x"]),
                    "y": int(fields["y"]),
                    "ordinal": int(fields.get("ordinal", 0)),
                },
            })
        elif event == "player-outcome":
            outcomes.append({
                "intent_id": int(fields["intent"]),
                "transaction_id": int(fields["transaction"]),
                "submitted_cycle": _optional_int(fields.get("submitted-cycle")),
                "unit_id": int(fields["unit"]),
                "command": fields["family"],
                "accepted": _bool(fields.get("accepted")),
                "first_progress_cycle": _optional_int(
                    fields.get("first-progress-cycle")),
                "terminal_cycle": _optional_int(fields.get("terminal-cycle")),
                "terminal_reason": fields.get("terminal-reason"),
                "tile_x": _optional_int(fields.get("tile-x")),
                "tile_y": _optional_int(fields.get("tile-y")),
                "offset_x": _optional_int(fields.get("offset-x")),
                "offset_y": _optional_int(fields.get("offset-y")),
                "order": fields.get("order"),
                "target_id": _optional_int(fields.get("target-id")),
                "hit_points": _optional_int(fields.get("hit-points")),
                "carried": _optional_int(fields.get("carried")),
                "alive": _bool(fields.get("alive")),
                "on_map": _bool(fields.get("on-map")),
                "missile_count": _optional_int(fields.get("missile-count")),
            })
    if not gestures and not orders and not outcomes:
        return None
    return {
        "map_path": map_path,
        "authority": authority,
        "player_intents": gestures + orders,
        "player_decisions": decisions,
        "player_feedback": feedback,
        "player_outcomes": outcomes,
        "unit_identities": ({
            "schema": UNIT_IDENTITY_SCHEMA,
            "units": identities,
        } if identities else None),
    }


def compile_ui_trace(trace: str, *, source: str, map_path: str | None = None,
        settle_cycle: int | None = None,
        authority: dict[str, Any] | None = None) -> dict[str, Any]:
    """Turn a native DoRightButton trace into a player-intent evidence packet."""
    trace_authority = authority or {
        "side": "native",
        "producer": "unmanifested-bne-ui-handler-trace",
        "authenticated": False,
    }
    layered = _layered_trace_evidence(trace, map_path, trace_authority)
    if layered is not None:
        return compile_evidence(layered, source=source)
    gesture = None
    fanouts: list[dict[str, Any]] = []
    states: dict[int, list[tuple[int, dict[str, int]]]] = {}
    for line in trace.splitlines():
        match = _UI_RIGHT_CLICK.search(line)
        if match:
            selected = [int(item) for item in match.group("selected").split(",")
                        if item]
            gesture = {
                "cycle": int(match.group("cycle")),
                "player": int(match.group("player")),
                "x": int(match.group("x")),
                "y": int(match.group("y")),
                "selected": selected,
            }
            continue
        match = _UI_FANOUT.search(line)
        if match:
            installed = int(match.group("order"))
            queued = int(match.group("next_order"))
            fanouts.append({
                "cycle": int(match.group("cycle")),
                "unit": int(match.group("unit")),
                "order": queued if queued not in (0, 60) else installed,
                "order_x": int(match.group("order_x")),
                "order_y": int(match.group("order_y")),
            })
            continue
        match = _UNIT_STATE.search(line)
        if match:
            unit = int(match.group("unit"))
            states.setdefault(unit, []).append((int(match.group("cycle")), {
                "order": int(match.group("order")),
                "order_x": int(match.group("order_x")),
                "order_y": int(match.group("order_y")),
            }))
    if gesture is None:
        raise ValueError("native UI trace has no ui-right-click event")
    if not fanouts and gesture["selected"]:
        raise ValueError("native UI trace has no ui-fanout from the selection")
    intents: list[dict[str, Any]] = [{
        "intent_id": 1,
        "transaction_id": 1,
        "cycle": gesture["cycle"],
        "event": "gesture",
        "selected_unit_ids": list(gesture["selected"]),
            "gesture": {
                "origin": "field",
                "detail": "right-click",
            "screen_x": None,
            "screen_y": None,
            "tile_x": gesture["x"],
            "tile_y": gesture["y"],
                "modifiers": "plain",
                "target_id": None,
                # DoRightButton's (x,y) hook precedes the retail target lookup.
                # Do not silently promote a coordinate to an open-ground proof.
                "target_shape": None,
            },
    }]
    outcomes: list[dict[str, Any]] = []
    for offset, fanout in enumerate(fanouts, start=2):
        family = _ORDER_NAME.get(fanout["order"], "move").lower().replace("_", "-")
        if family.startswith("attack-move"):
            family = "move"
        intents.append({
            "intent_id": offset,
            "transaction_id": 1,
            "cycle": fanout["cycle"],
            "event": "order",
            "selected_unit_ids": list(gesture["selected"]),
            "accepted": True,
            "command": {
                "kind": family.upper().replace("-", "_"),
                "player": gesture["player"],
                "unit_id": fanout["unit"],
                "x": fanout["order_x"],
                "y": fanout["order_y"],
                "target_id": 0,
                "type_index": 0,
                "queued": False,
                "wire_hex": "",
                "fanout_ordinal": offset - 2,
            },
        })
        history = states.get(fanout["unit"]) or []
        # The legacy hook records installed order fields but no pixel position.
        # It cannot prove visible progress; the richer contract above can.
        first_progress = None
        latest = {
            "order": fanout["order"],
            "order_x": fanout["order_x"],
            "order_y": fanout["order_y"],
        }
        for cycle, row in history:
            if cycle < fanout["cycle"]:
                continue
            latest = row
        terminal_cycle = settle_cycle
        if history:
            terminal_cycle = history[-1][0]
        outcomes.append({
            "intent_id": offset,
            "transaction_id": 1,
            "submitted_cycle": fanout["cycle"],
            "unit_id": fanout["unit"],
            "command": family,
            "accepted": True,
            "first_progress_cycle": first_progress,
            "terminal_cycle": terminal_cycle,
            "terminal_reason": "settled" if latest["order"] == 1 else None,
            "tile_x": latest["order_x"],
            "tile_y": latest["order_y"],
            "offset_x": 0,
            "offset_y": 0,
            "order": _ORDER_NAME.get(latest["order"], "STILL"),
            "target_id": None,
            "hit_points": None,
            "carried": 0,
            "alive": True,
            "on_map": True,
            "missile_count": 0,
        })
    return compile_evidence({
        "map_path": map_path,
        "authority": trace_authority,
        "player_intents": intents,
        "player_decisions": ([{
            "transaction_id": 1,
            "accepted": False,
            "reason": "empty-selection",
            "cycle": gesture["cycle"],
        }] if not fanouts else []),
        "player_outcomes": outcomes,
    }, source=source)


def compile_evidence(evidence: dict[str, Any], *, source: str) -> dict[str, Any]:
    """Turn one desktop evidence packet into ordered causal transactions."""
    entries = evidence.get("player_intents")
    outcomes = evidence.get("player_outcomes")
    if not isinstance(entries, list) or not isinstance(outcomes, list):
        raise ValueError("evidence lacks player_intents or player_outcomes")

    feedback_by_intent: dict[int, dict[str, Any]] = {}
    for feedback in evidence.get("player_feedback") or ():
        if not isinstance(feedback, dict):
            raise ValueError("player feedback is not an object")
        intent_id = int(feedback.get("intent_id", 0))
        if intent_id <= 0 or intent_id in feedback_by_intent:
            raise ValueError(f"invalid or duplicate player feedback {intent_id}")
        acknowledged = feedback.get("acknowledged")
        if not isinstance(acknowledged, bool):
            raise ValueError(f"player feedback {intent_id} omits acknowledgement")
        feedback_by_intent[intent_id] = {
            "cycle": feedback.get("cycle"),
            "acknowledged": acknowledged,
            "mode": feedback.get("mode"),
            "detail": feedback.get("detail"),
        }

    decisions: dict[int, dict[str, Any]] = {}
    for decision in evidence.get("player_decisions") or ():
        if not isinstance(decision, dict):
            raise ValueError("player decision is not an object")
        transaction_id = int(decision.get("transaction_id", 0))
        accepted = decision.get("accepted")
        if transaction_id <= 0 or transaction_id in decisions \
                or not isinstance(accepted, bool):
            raise ValueError("invalid or duplicate player transaction decision")
        decisions[transaction_id] = {
            "accepted": accepted,
            "family": (_canonical_family(decision.get("family"))
                       if decision.get("family") is not None else None),
            "queued": decision.get("queued"),
            "reason": decision.get("reason"),
            "cycle": decision.get("cycle"),
        }

    by_transaction: dict[int, dict[str, Any]] = {}
    intent_transaction: dict[int, int] = {}
    seen_intents: set[int] = set()
    for entry in entries:
        if not isinstance(entry, dict):
            raise ValueError("player intent is not an object")
        intent_id = int(entry.get("intent_id", 0))
        transaction_id = int(entry.get("transaction_id", intent_id))
        if intent_id <= 0 or transaction_id <= 0:
            raise ValueError("intent and transaction ids must be positive")
        if intent_id in seen_intents:
            raise ValueError(f"duplicate player intent {intent_id}")
        seen_intents.add(intent_id)
        intent_transaction[intent_id] = transaction_id
        transaction = by_transaction.setdefault(transaction_id, {
            "transaction_id": transaction_id,
            "gesture": None,
            "decision": decisions.get(transaction_id),
            "feedback": None,
            "selection_updates": [],
            "commands": [],
            "outcomes": [],
        })
        event = entry.get("event")
        if event == "gesture":
            if transaction["gesture"] is not None:
                raise ValueError(f"duplicate gesture for transaction {transaction_id}")
            gesture = entry.get("gesture")
            if not isinstance(gesture, dict):
                raise ValueError(f"gesture {intent_id} has no physical input")
            selection_observed = (
                "selected_unit_ids" in entry
                and isinstance(entry.get("selected_unit_ids"), list))
            generations_observed = (
                "selected_unit_generations" in entry
                and isinstance(entry.get("selected_unit_generations"), list))
            transaction["gesture"] = {
                "origin": gesture.get("origin"),
                "detail": gesture.get("detail"),
                "screen_x": gesture.get("screen_x"),
                "screen_y": gesture.get("screen_y"),
                "tile_x": gesture.get("tile_x"),
                "tile_y": gesture.get("tile_y"),
                "modifiers": gesture.get("modifiers"),
                "target_id": gesture.get("target_id"),
                "target_generation": gesture.get("target_generation"),
                "target_shape": gesture.get("target_shape"),
                "selected_unit_ids": list(entry.get("selected_unit_ids") or ()),
                "selected_unit_generations": (
                    list(entry.get("selected_unit_generations") or ())
                    if generations_observed else None),
                "selection_observed": selection_observed,
            }
            transaction["feedback"] = feedback_by_intent.get(intent_id)
        elif event == "selection":
            transaction["selection_updates"].append(
                list(entry.get("selected_unit_ids") or ()))
        elif event == "order":
            command = entry.get("command")
            if not isinstance(command, dict):
                raise ValueError(f"order {intent_id} has no command")
            transaction["commands"].append({
                "intent_id": intent_id,
                "fanout_ordinal": command.get("fanout_ordinal"),
                "family": _canonical_family(command.get("kind")),
                "player": command.get("player"),
                "unit_id": command.get("unit_id"),
                "unit_generation": command.get("unit_generation"),
                "x": command.get("x"),
                "y": command.get("y"),
                "target_id": command.get("target_id"),
                "target_generation": command.get("target_generation"),
                "type_index": command.get("type_index"),
                "queued": bool(command.get("queued")),
                "wire_hex": command.get("wire_hex"),
                "accepted": entry.get("accepted"),
                "feedback": feedback_by_intent.get(intent_id),
                "selected_unit_ids": list(entry.get("selected_unit_ids") or ()),
                "selected_unit_generations": (
                    list(entry.get("selected_unit_generations") or ())
                    if isinstance(entry.get("selected_unit_generations"), list)
                    else None),
            })

    seen_outcomes: set[int] = set()
    for outcome in outcomes:
        if not isinstance(outcome, dict):
            raise ValueError("player outcome is not an object")
        intent_id = int(outcome.get("intent_id", 0))
        transaction_id = int(outcome.get(
            "transaction_id", intent_transaction.get(intent_id, intent_id)))
        if intent_id in seen_outcomes:
            raise ValueError(f"duplicate player outcome {intent_id}")
        seen_outcomes.add(intent_id)
        transaction = by_transaction.get(transaction_id)
        if transaction is None:
            raise ValueError(f"outcome {intent_id} has no transaction")
        transaction["outcomes"].append({
            "intent_id": intent_id,
            "unit_id": outcome.get("unit_id"),
            "unit_generation": outcome.get("unit_generation"),
            "command": _canonical_family(outcome.get("command")),
            "accepted": outcome.get("accepted"),
            "submitted_cycle": outcome.get("submitted_cycle"),
            "first_progress_cycle": outcome.get("first_progress_cycle"),
            "terminal_cycle": outcome.get("terminal_cycle"),
            "terminal_reason": outcome.get("terminal_reason"),
            "tile_x": outcome.get("tile_x"),
            "tile_y": outcome.get("tile_y"),
            "offset_x": outcome.get("offset_x"),
            "offset_y": outcome.get("offset_y"),
            "order": outcome.get("order"),
            "target_id": outcome.get("target_id"),
            "target_generation": outcome.get("target_generation"),
            "hit_points": outcome.get("hit_points"),
            "carried": outcome.get("carried"),
            "alive": outcome.get("alive"),
            "on_map": outcome.get("on_map"),
            "missile_count": outcome.get("missile_count"),
        })

    transactions = []
    for transaction_id in sorted(by_transaction):
        transaction = by_transaction[transaction_id]
        command_intents = {item["intent_id"] for item in transaction["commands"]}
        outcome_intents = {item["intent_id"] for item in transaction["outcomes"]}
        gesture = transaction["gesture"]
        decision = transaction.get("decision")
        commands = transaction["commands"]
        outcomes = transaction["outcomes"]
        ordinals = [item.get("fanout_ordinal") for item in commands]
        selected = list(gesture.get("selected_unit_ids") or ()) \
            if isinstance(gesture, dict) else []
        selected_generations = gesture.get("selected_unit_generations") \
            if isinstance(gesture, dict) else None
        ordered_selection = bool(
            isinstance(gesture, dict) and gesture.get("selection_observed") is True
            and isinstance(gesture.get("selected_unit_ids"), list)
            and (selected_generations is None or (
                isinstance(selected_generations, list)
                and len(selected_generations) == len(selected))))
        ordered_fanout = bool(commands) and ordinals == list(range(len(commands))) \
            and [item.get("unit_id") for item in commands] == selected \
            and len(set(selected)) == len(selected)
        command_intents = {item["intent_id"] for item in commands}
        outcome_intents = {item["intent_id"] for item in outcomes}
        explicit_refusal = bool(
            isinstance(decision, dict) and decision.get("accepted") is False
            and not commands)
        exact_outcomes = bool(commands) and command_intents == outcome_intents \
            and len(commands) == len(outcomes)
        outcomes_by_intent = {item["intent_id"]: item for item in outcomes}
        acceptance_consistent = exact_outcomes and all(
            isinstance(command.get("accepted"), bool)
            and isinstance(outcomes_by_intent[command["intent_id"]].get(
                "accepted"), bool)
            and command["accepted"] == outcomes_by_intent[
                command["intent_id"]]["accepted"]
            for command in commands)
        if isinstance(decision, dict) and commands:
            acceptance_consistent = acceptance_consistent and all(
                command["accepted"] == decision.get("accepted")
                for command in commands)
        terminal = explicit_refusal or (
            exact_outcomes and all(
                item.get("terminal_reason") is not None for item in outcomes))
        accepted = explicit_refusal or acceptance_consistent
        feedback = (
            isinstance(transaction.get("feedback"), dict)
            and isinstance(transaction["feedback"].get("acknowledged"), bool)
        ) if not commands else all(
            isinstance(item.get("feedback"), dict)
            and isinstance(item["feedback"].get("acknowledged"), bool)
            for item in commands)
        progress = explicit_refusal or (exact_outcomes and all(
            item.get("first_progress_cycle") is not None
            or item.get("accepted") is False
            or item.get("terminal_reason") in {
                "rejected", "blocked-goal", "unit-unavailable",
                "target-unavailable",
            }
            for item in outcomes))
        layers = {
            "gesture": gesture is not None,
            "ordered-selection": ordered_selection,
            "target-interpretation": bool(gesture is not None
                                           and gesture.get("target_shape") is not None),
            "wire-fanout": explicit_refusal or (
                ordered_fanout and all(
                    _valid_wire_hex(item.get("wire_hex")) for item in commands)),
            "acceptance": accepted,
            "acknowledgement": feedback,
            "first-progress": progress,
            "terminal-outcome": terminal,
        }
        transaction["coverage"] = {
            "physical_gesture": transaction["gesture"] is not None,
            "command_count": len(commands),
            "outcome_count": len(outcomes),
            "group_fanout": ordered_fanout and len(commands) > 1,
            "queued": any(item["queued"] for item in commands),
            "terminal": terminal,
            "layers": layers,
        }
        transaction["canonical_sha256"] = _digest(canonical_transaction(transaction))
        transactions.append(transaction)

    receipt = {
        "schema": SCHEMA,
        "source": source,
        "authority": evidence.get("authority") or {
            "side": "unknown",
            "producer": "unidentified",
        },
        "map_path": evidence.get("map_path"),
        "campaign": evidence.get("campaign"),
        "mission": evidence.get("mission"),
        "unit_identities": _normalize_unit_identities(
            evidence.get("unit_identities")),
        "transactions": transactions,
    }
    receipt["receipt_sha256"] = _receipt_identity(receipt)
    return receipt


def _unit_identity_index(receipt: dict[str, Any]) \
        -> dict[tuple[int, int], str]:
    raw = receipt.get("unit_identities")
    if not isinstance(raw, dict) or raw.get("schema") != UNIT_IDENTITY_SCHEMA \
            or not isinstance(raw.get("units"), list):
        return {}
    result: dict[tuple[int, int], str] = {}
    seen_stable: set[str] = set()
    generations: dict[int, list[int]] = {}
    for item in raw["units"]:
        if not isinstance(item, dict):
            raise ValueError("receipt unit identity is not an object")
        local_id = int(item.get("local_id", -1))
        generation = int(item.get("generation", -1))
        identity = item.get("identity")
        stable = item.get("stable_sha256")
        if local_id < 0 or generation < 0 or not isinstance(identity, dict) \
                or stable != _digest(identity) or not _valid_sha256(stable) \
                or (local_id, generation) in result or stable in seen_stable:
            raise ValueError("receipt unit identity closure is invalid")
        result[(local_id, generation)] = stable
        seen_stable.add(stable)
        generations.setdefault(local_id, []).append(generation)
    if any(sorted(values) != list(range(len(values)))
           for values in generations.values()):
        raise ValueError("receipt unit identity generations must start at zero")
    return result


def _stable_unit(index: dict[tuple[int, int], str], local_id: object,
        generation: object, *, required: bool) -> str | None:
    if local_id is None:
        return None
    local = int(local_id)
    if generation is None:
        candidates = [stable for (unit, _), stable in index.items()
                      if unit == local]
        stable = candidates[0] if len(candidates) == 1 else None
    else:
        stable = index.get((local, int(generation)))
    if stable is None and required:
        raise ValueError(
            f"player transaction local unit {local_id}/{generation} has no "
            "stable lifecycle identity")
    return stable


def _wire_bytes(command: dict[str, Any]) -> bytes:
    value = command.get("wire_hex")
    if not _valid_wire_hex(value):
        raise ValueError("wire is not non-empty even-length hexadecimal")
    return bytes.fromhex(str(value))


def _wire_target_identity(index: dict[tuple[int, int], str],
        local_id: int | None, generation: object, *, required: bool) \
        -> str | None:
    if local_id is None:
        return None
    return _stable_unit(index, local_id, generation, required=required)


def _give_order_wire(command: dict[str, Any],
        identities: dict[tuple[int, int], str], *, side: str,
        require_stable: bool) -> dict[str, Any]:
    """Decode and validate one producer wire into retail GiveOrder fields.

    Native receipts retain the exact eight-byte 0x13 replay command.  Java
    receipts retain the exact 17-byte GameCommand lockstep envelope.  Raw byte
    equality between those protocols is meaningless; equality of the decoded
    family, destination, target lifetime and native function index is the
    physical-command contract.  Every redundant field is checked before the
    normalized value is returned, so normalization cannot hide a malformed or
    semantically different wire.
    """
    wire = _wire_bytes(command)
    recorded_family = _canonical_family(command.get("family"))
    expected_function = BNE_GIVE_ORDER_FUNCTIONS.get(recorded_family)

    if side == "native":
        if expected_function is None:
            return {"protocol": "native", "wire_hex": wire.hex()}
        if len(wire) != 8 or wire[0] != 0x13:
            raise ValueError(
                f"native {recorded_family} wire is not an eight-byte 0x13 command")
        x = int.from_bytes(wire[1:3], "little", signed=True)
        y = int.from_bytes(wire[3:5], "little", signed=True)
        raw_target = int.from_bytes(wire[5:7], "little")
        target_id = None if raw_target == 0xffff else raw_target
        function = wire[7]
        decoded_family = (
            "attack" if function == 8 and target_id is not None
            else "attack-move" if function == 8
            else next((family for family, index in BNE_GIVE_ORDER_FUNCTIONS.items()
                       if index == function and family != "attack-move"), None)
        )
        if decoded_family != recorded_family:
            raise ValueError(
                f"native wire decodes as {decoded_family!r}, not {recorded_family!r}")
        recorded_target = command.get("target_id")
        if recorded_target is not None:
            recorded_target = int(recorded_target)
        if (x, y, target_id) != (
                int(command.get("x")), int(command.get("y")), recorded_target):
            raise ValueError("native wire fields disagree with the observed GiveOrder")
        if function != expected_function:
            raise ValueError("native wire has the wrong GiveOrder function index")
        target_generation = command.get("target_generation")
    elif side == "java":
        if len(wire) != 17:
            raise ValueError("Java wire is not a 17-byte GameCommand")
        kind = wire[0]
        if kind >= len(JAVA_WIRE_FAMILIES):
            raise ValueError(f"Java wire has unknown command kind {kind}")
        decoded_family = JAVA_WIRE_FAMILIES[kind]
        player = wire[1]
        unit_id = int.from_bytes(wire[2:6], "big", signed=True)
        x = int.from_bytes(wire[6:8], "big", signed=True)
        y = int.from_bytes(wire[8:10], "big", signed=True)
        raw_target = int.from_bytes(wire[10:14], "big", signed=True)
        target_id = None if raw_target == 0 else raw_target
        type_index = int.from_bytes(wire[14:16], "big", signed=True)
        if wire[16] not in (0, 1):
            raise ValueError("Java wire has a non-boolean queued byte")
        queued = wire[16] == 1
        recorded_target = command.get("target_id")
        recorded_target = None if recorded_target in (None, 0) \
            else int(recorded_target)
        if decoded_family != recorded_family:
            raise ValueError(
                f"Java wire decodes as {decoded_family!r}, not {recorded_family!r}")
        if (player, unit_id, x, y, target_id, type_index, queued) != (
                int(command.get("player")), int(command.get("unit_id")),
                int(command.get("x")), int(command.get("y")), recorded_target,
                int(command.get("type_index")), bool(command.get("queued"))):
            raise ValueError("Java wire fields disagree with the journaled command")
        if expected_function is None:
            return {"protocol": "java-lockstep", "wire_hex": wire.hex()}
        function = expected_function
        target_generation = command.get("target_generation")
    else:
        return {"protocol": str(side or "unknown"), "wire_hex": wire.hex()}

    return {
        "protocol": "bne-give-order-0x13",
        "family": recorded_family,
        "function_index": function,
        "x": x,
        "y": y,
        "target_identity": _wire_target_identity(
            identities, target_id, target_generation, required=require_stable),
    }


def canonical_transaction(transaction: dict[str, Any],
        identities: dict[tuple[int, int], str] | None = None,
        *, require_stable: bool = False, side: str | None = None) -> dict[str, Any]:
    """Retain ordering and behavior, optionally replacing all allocator ids."""
    index = identities or {}
    gesture = transaction.get("gesture")
    canonical_gesture = None
    if isinstance(gesture, dict):
        canonical_gesture = {key: gesture.get(key) for key in (
            "origin", "detail", "screen_x", "screen_y", "tile_x", "tile_y",
            "modifiers", "target_shape", "selection_observed",
        )}
        selected_ids = list(gesture.get("selected_unit_ids") or ())
        selected_generations = gesture.get("selected_unit_generations")
        canonical_gesture["selected_unit_identities"] = [
            _stable_unit(
                index, local_id,
                (selected_generations[offset]
                 if isinstance(selected_generations, list)
                 and offset < len(selected_generations) else None),
                required=require_stable)
            for offset, local_id in enumerate(selected_ids)
        ]
        canonical_gesture["target_identity"] = _stable_unit(
            index, gesture.get("target_id"), gesture.get("target_generation"),
            required=require_stable and gesture.get("target_id") is not None)
    commands = []
    for command in transaction.get("commands") or ():
        row = {key: command.get(key) for key in (
            "fanout_ordinal", "family", "player", "x", "y", "type_index",
            "queued", "accepted", "feedback",
        )}
        if side is None:
            row["wire_hex"] = command.get("wire_hex")
        else:
            row["wire"] = _give_order_wire(
                command, index, side=side, require_stable=require_stable)
        row["unit_identity"] = _stable_unit(
            index, command.get("unit_id"), command.get("unit_generation"),
            required=require_stable)
        row["target_identity"] = _stable_unit(
            index, command.get("target_id"), command.get("target_generation"),
            required=require_stable and command.get("target_id") not in (None, 0))
        selected_ids = list(command.get("selected_unit_ids") or ())
        selected_generations = command.get("selected_unit_generations")
        row["selected_unit_identities"] = [
            _stable_unit(
                index, local_id,
                (selected_generations[offset]
                 if isinstance(selected_generations, list)
                 and offset < len(selected_generations) else None),
                required=require_stable)
            for offset, local_id in enumerate(selected_ids)
        ]
        commands.append(row)
    outcomes = []
    for outcome in transaction.get("outcomes") or ():
        row = {key: outcome.get(key) for key in (
            "command", "accepted", "first_progress_cycle",
            "terminal_cycle", "terminal_reason", "tile_x", "tile_y",
            "offset_x", "offset_y", "order", "hit_points",
            "carried", "alive", "on_map", "missile_count",
        )}
        row["unit_identity"] = _stable_unit(
            index, outcome.get("unit_id"), outcome.get("unit_generation"),
            required=require_stable)
        row["target_identity"] = _stable_unit(
            index, outcome.get("target_id"), outcome.get("target_generation"),
            required=require_stable and outcome.get("target_id") not in (None, 0))
        outcomes.append(row)
    selection_updates = []
    for update in transaction.get("selection_updates") or ():
        selection_updates.append([
            _stable_unit(index, local_id, None, required=require_stable)
            for local_id in update
        ])
    return {
        "gesture": canonical_gesture,
        "decision": transaction.get("decision"),
        "feedback": transaction.get("feedback"),
        "selection_updates": selection_updates,
        "commands": commands,
        "outcomes": outcomes,
    }


def _receipt_identity(receipt: dict[str, Any]) -> str:
    authority = dict(receipt.get("authority") or {})
    authority.pop("manifest_source", None)
    return _digest({
        "schema": receipt.get("schema"),
        "authority": authority,
        "map_path": receipt.get("map_path"),
        "campaign": receipt.get("campaign"),
        "mission": receipt.get("mission"),
        "unit_identities": receipt.get("unit_identities"),
        "transactions": [
            {key: value for key, value in item.items()
             if key != "canonical_sha256"}
            for item in receipt.get("transactions") or ()
        ],
    })


def _receipt_errors(receipt: dict[str, Any], *, expected_side: str | None = None,
        current_java_engine_input_sha256: str | None = None,
        current_java_program_input_sha256: str | None = None) -> list[str]:
    errors = []
    if receipt.get("schema") != SCHEMA:
        errors.append("wrong receipt schema")
        return errors
    recorded = receipt.get("receipt_sha256")
    if recorded != _receipt_identity(receipt):
        errors.append("receipt content identity changed")
    authority = receipt.get("authority") or {}
    side = authority.get("side")
    if expected_side is not None and side != expected_side:
        errors.append(f"expected {expected_side} authority; found {side!r}")
    if authority.get("authenticated") is not True:
        errors.append("authority is not authenticated")
    if not _valid_sha256(authority.get("build_sha256")):
        errors.append("authority has no build identity")
    if not _valid_sha256(authority.get("fixture_id")) \
            or authority.get("scenario_id") != authority.get("fixture_id"):
        errors.append("authority has no closed scenario identity")
    if side == "native":
        if authority.get("build_sha256") != PINNED_BNE_EXECUTABLE_SHA256:
            errors.append("native authority is not pinned BNE 2.02b")
        for name in ("manifest_sha256", "fixture_archive_sha256",
                     "tracer_sha256", "injector_sha256",
                     "source_manifest_sha256"):
            if not _valid_sha256(authority.get(name)):
                errors.append(f"native authority omits {name}")
    if side == "java":
        engine = authority.get("engine_input_sha256")
        if not _valid_sha256(engine) or authority.get("build_sha256") != engine:
            errors.append("Java authority has no hermetic engine-input identity")
        if current_java_engine_input_sha256 is not None \
                and engine != current_java_engine_input_sha256:
            errors.append("Java receipt was not produced by the current engine inputs")
        program = authority.get("program_input_sha256")
        if not _valid_sha256(program):
            errors.append("Java authority has no desktop/program input identity")
        if current_java_program_input_sha256 is not None \
                and program != current_java_program_input_sha256:
            errors.append("Java receipt was not produced by current program inputs")
    try:
        identities = _unit_identity_index(receipt)
    except ValueError as error:
        errors.append(str(error))
        identities = {}
    if side in {"native", "java"}:
        for transaction in receipt.get("transactions") or ():
            for command in transaction.get("commands") or ():
                try:
                    _give_order_wire(
                        command, identities, side=side, require_stable=True)
                except (TypeError, ValueError) as error:
                    errors.append(
                        f"transaction {transaction.get('transaction_id')} "
                        f"command {command.get('intent_id')} wire contract: {error}")
    return errors


def import_native_captures(capture_dirs: list[Path], output_dir: Path) \
        -> dict[str, Any]:
    """Import sealed physical-UI captures into content-addressed receipts."""
    entries = []
    destination = output_dir.expanduser().resolve()
    for capture_dir in capture_dirs:
        directory = capture_dir.expanduser().resolve()
        traces = sorted(directory.glob("*.trace.txt"))
        manifests = sorted(directory.glob("*.manifest.json"))
        fixtures = sorted(directory.glob("*.bnefx"))
        if len(traces) != 1 or len(manifests) != 1 or len(fixtures) != 1:
            raise ValueError(
                "physical UI capture needs one trace, manifest and fixture: "
                f"{directory}")
        trace_path = traces[0]
        manifest_path = manifests[0]
        fixture_path = fixtures[0]
        trace_bytes = trace_path.read_bytes()
        manifest_bytes = manifest_path.read_bytes()
        with zipfile.ZipFile(fixture_path) as archive:
            if archive.read("manifest.json") != manifest_bytes:
                raise ValueError("physical UI fixture and sidecar manifest differ")
        fixture_validation = bne_fixture.validate_fixture(fixture_path)
        authority = authority_from_native_manifest(
            json.loads(manifest_bytes), trace_bytes,
            manifest_source=manifest_path.name,
            fixture_validation=fixture_validation)
        receipt = compile_ui_trace(
            trace_bytes.decode("utf-8", errors="replace"),
            source=trace_path.name, authority=authority)
        identity = _receipt_identity(receipt)
        if receipt.get("receipt_sha256") != identity:
            raise ValueError("compiled native receipt did not close its content")
        path = destination / identity / "receipt.json"
        encoded = json.dumps(receipt, indent=2, sort_keys=True) + "\n"
        if path.exists() and path.read_text(encoding="utf-8") != encoded:
            raise ValueError("content-addressed player receipt path changed")
        if not path.exists():
            path.parent.mkdir(parents=True, exist_ok=True)
            temporary = path.with_name(path.name + ".tmp")
            temporary.write_text(encoded, encoding="utf-8")
            temporary.replace(path)
        entries.append({
            "capture": directory.name,
            "fixture_id": authority["fixture_id"],
            "receipt_sha256": identity,
            "receipt": f"{identity}/receipt.json",
            "transactions": len(receipt["transactions"]),
            "commands": sum(len(item["commands"])
                            for item in receipt["transactions"]),
            "layer_counts": {
                layer: sum(bool(item["coverage"]["layers"].get(layer))
                           for item in receipt["transactions"])
                for layer in TRANSACTION_LAYERS
            },
        })
    catalog: dict[str, Any] = {
        "schema": CATALOG_SCHEMA,
        "captures": len(entries),
        "transactions": sum(item["transactions"] for item in entries),
        "commands": sum(item["commands"] for item in entries),
        "entries": entries,
    }
    catalog["catalog_sha256"] = _digest({
        **{key: value for key, value in catalog.items() if key != "entries"},
        "entries": [
            {key: value for key, value in item.items()
             if key not in {"capture", "receipt"}}
            for item in entries
        ],
    })
    return catalog


def first_difference(left: dict[str, Any], right: dict[str, Any]) \
        -> dict[str, Any] | None:
    left_rows = left.get("transactions") or []
    right_rows = right.get("transactions") or []
    left_identities = _unit_identity_index(left)
    right_identities = _unit_identity_index(right)
    left_side = (left.get("authority") or {}).get("side")
    right_side = (right.get("authority") or {}).get("side")
    for index in range(max(len(left_rows), len(right_rows))):
        if index >= len(left_rows) or index >= len(right_rows):
            return {"transaction": index, "field": "transaction-count",
                    "left": len(left_rows), "right": len(right_rows)}
        a = canonical_transaction(
            left_rows[index], left_identities, side=left_side)
        b = canonical_transaction(
            right_rows[index], right_identities, side=right_side)
        if a != b:
            for field in (
                    "gesture", "decision", "feedback", "selection_updates",
                    "commands", "outcomes"):
                if a[field] != b[field]:
                    return {"transaction": index, "field": field,
                            "left": a[field], "right": b[field]}
    return None


def _capture_recipe(dimension: str, value: Any) -> dict[str, Any]:
    """Return an honest executable recipe or the exact observation debt."""
    supported = (
        (dimension == "origins" and value == "field")
        or (dimension == "modifiers" and value == "plain")
        or (dimension == "families" and value == "move")
    )
    hook_debt = {
        "origins": {
            "minimap": "hook minimap right-click before target projection",
            "command-panel": "hook command-panel button dispatch",
            "keyboard": "hook gameplay key dispatch before command construction",
        },
        "modifiers": {
            "shift": "hook modifier state and queued wire serialization",
            "control": "hook modifier state at selection/gesture dispatch",
            "alt": "hook modifier state at selection/gesture dispatch",
            "control+alt": "hook combined modifier state at gesture dispatch",
        },
        "families": {},
    }
    if supported:
        debt = (
            "legacy physical hook proves field/plain/move gesture, ordered "
            "selection and installed fan-out only; add post-target, wire, "
            "feedback, pixel-progress and terminal hooks for certification"
        )
        status = "partial-executable"
    else:
        debt = hook_debt.get(dimension, {}).get(value)
        if debt is None and dimension == "families":
            debt = (
                f"hook physical {value} UI construction and ordered wire dispatch; "
                "GiveOrder injection is not a physical transaction"
            )
        if debt is None:
            debt = f"add a native observation recipe for {dimension}={value}"
        status = "blocked-on-hook"
    return {
        "cell": {"dimension": dimension, "value": value},
        "status": status,
        "hook_debt": debt,
        "command_script": [
            "cycle 5 select unit {unit_slot}",
            "cycle 5 ui-right-click x {tile_x} y {tile_y}",
        ] if supported else None,
        "native_command": ([
            "python3", "tools/bne-harness/scripts/bne_oracle.py", "run",
            "--game-dir", "{game_dir}",
            "--prefix", "{wine_prefix}",
            "--trace", "{trace_path}",
            "--manifest", "{manifest_path}",
            "--source-manifest", "{source_manifest}",
            "--scenario", "Campaign\\Human\\Human01.pud",
            "--cycles", "180", "--commands", "{command_script}",
        ] if supported else None),
        "compile_command": ([
            "python3", "tools/bne-harness/scripts/bne_player_transaction.py",
            "from-ui-trace", "{trace_path}", "--manifest",
            "{manifest_path}", "--fixture", "{fixture_path}",
            "--output", "{receipt_path}",
        ] if supported else None),
        "requires_input": ([
            "game_dir", "wine_prefix", "source_manifest", "trace_path",
            "manifest_path", "fixture_path", "command_script", "receipt_path",
            "unit_slot", "tile_x", "tile_y",
        ] if supported else []),
    }


def capture_plan(missing: dict[str, list[Any]]) -> dict[str, Any]:
    recipes = [
        _capture_recipe(dimension, value)
        for dimension in ("origins", "modifiers", "families")
        for value in missing.get(dimension, ())
    ]
    layer_hooks = {
        "gesture": "hook physical input dispatch",
        "ordered-selection": "hook ordered selection at physical dispatch",
        "target-interpretation": "hook target lookup after retail resolves the click",
        "wire-fanout": "hook serialized packet bytes and fan-out ordinal",
        "acceptance": "hook command acceptance/refusal decision",
        "acknowledgement": "hook voice/status acknowledgement dispatch",
        "first-progress": "trace unit pixel position or other physical effect per cycle",
        "terminal-outcome": "retain state through settle/reject/death/delivery completion",
    }

    def fixed_recipe(cell: dict[str, Any]) -> dict[str, Any]:
        dimension_debt = []
        for dimension, key in (
                ("origins", "origin"), ("modifiers", "modifiers"),
                ("families", "family")):
            recipe = _capture_recipe(dimension, cell[key])
            if recipe["status"] == "blocked-on-hook":
                dimension_debt.append(recipe["hook_debt"])
        dimension_debt.extend([
            f"observe ordered selection fan-out of exactly "
            f"{cell['selection_size']} units",
            f"observe retail target interpretation as {cell['target_shape']}",
            ("observe queued packet serialization"
             if cell["queued"] else "observe immediate packet serialization"),
            ("observe accepted command acknowledgement"
             if cell["accepted"] else "observe explicit refusal and feedback"),
            layer_hooks["first-progress"],
            layer_hooks["terminal-outcome"],
        ])
        legacy_executable = (
            cell["origin"] == "field" and cell["modifiers"] == "plain"
            and cell["family"] == "move")
        base = _capture_recipe("origins", "field") if legacy_executable else {}
        return {
            "cell": cell,
            "status": ("partial-executable" if legacy_executable
                       else "blocked-on-hook"),
            "hook_debt": dimension_debt,
            "command_script": base.get("command_script"),
            "native_command": base.get("native_command"),
            "compile_command": base.get("compile_command"),
            "requires_input": base.get("requires_input", []),
        }

    return {
        "schema": "chonkcraft-bne-player-transaction-capture-plan-1",
        "complete": not any(missing.values()),
        "recipes": recipes,
        "layer_hook_debt": [
            {"layer": layer, "required_hook": layer_hooks[layer]}
            for layer in missing.get("layers", ())
        ],
        "fixed_cell_debt": [fixed_recipe(item)
                            for item in missing.get("cell_records", ())],
        "required_route_hook_debt": list(
            missing.get("required_routes", ())),
    }


CELL_FIELDS = (
    "origin", "modifiers", "selection_size", "target_shape", "family",
    "queued", "accepted",
)


# The requirement matrix is not a Cartesian-product wish list.  These are the
# physical routes that GameScreen currently records with beginPlayerGesture.
# Keeping the contract here makes a typo such as "minimap research" or a
# keyboard origin without a gesture hook fail before it can inflate coverage.
_STANDARD_RIGHT_CLICK_PAIRS = {
    *(('move', shape) for shape in (
        "open-ground", "occupied-ground", "shore", "water", "unit", "building")),
    ("attack", "unit"), ("attack", "building"),
    ("harvest", "trees"), ("harvest", "oil-patch"),
    ("repair", "unit"), ("repair", "building"),
    ("return-goods", "building"), ("board", "transport"),
    ("follow", "unit"), ("attack-ground", "wall"),
}
_CONTROL_RIGHT_CLICK_PAIRS = {
    ("follow", "unit"), ("move", "building"),
    *(('attack-move', shape) for shape in (
        "open-ground", "occupied-ground", "shore", "water", "trees")),
}
_ALT_RIGHT_CLICK_PAIRS = {("defend", "unit"), ("defend", "building")}
_GROUND_ATTACK_SHAPES = {
    "open-ground", "occupied-ground", "unit", "building", "wall", "trees",
    "shore", "water", "oil-patch", "transport",
}
_CONTROL_ALT_RIGHT_CLICK_PAIRS = {
    ("attack-ground", shape) for shape in _GROUND_ATTACK_SHAPES
}
_RIGHT_CLICK_PAIRS = {
    "plain": _STANDARD_RIGHT_CLICK_PAIRS,
    "shift": _STANDARD_RIGHT_CLICK_PAIRS,
    "control": _CONTROL_RIGHT_CLICK_PAIRS,
    "shift+control": _CONTROL_RIGHT_CLICK_PAIRS,
    "alt": _ALT_RIGHT_CLICK_PAIRS,
    "shift+alt": _ALT_RIGHT_CLICK_PAIRS,
    "control+alt": _CONTROL_ALT_RIGHT_CLICK_PAIRS,
    "shift+control+alt": _CONTROL_ALT_RIGHT_CLICK_PAIRS,
}
_AIMED_PAIRS = {
    "plain": {
        ("patrol", "open-ground"), ("attack-ground", "open-ground"),
        ("cast", "unit"), ("cast", "building"), ("unload", "shore"),
    },
    "shift": {
        ("patrol", "open-ground"), ("attack-ground", "open-ground"),
        ("cast", "unit"), ("cast", "building"),
    },
}
_PANEL_GROUP_FAMILIES = {
    "stop", "stand-ground", "explore", "return-goods", "cast",
}
_PANEL_BUILDING_FAMILIES = {
    "train", "research", "upgrade-to", "cancel-train",
    "cancel-research", "cancel-upgrade-to", "cancel-build",
}


def _physical_variant_error(route: dict[str, Any], variant: dict[str, Any],
        sizes: list[int]) -> str | None:
    """Return why one declared route cannot be emitted by the current UI."""
    route_id = route.get("id")
    origins = route.get("origins")
    gesture = route.get("gesture")
    queue_rule = route.get("queue_rule")
    modifier = variant.get("modifiers")
    pair = (variant.get("family"), variant.get("target_shape"))
    if route_id == "world-right-click":
        if origins != ["field", "minimap"] or gesture != "right-click" \
                or queue_rule != "shift-modifier":
            return "world-right-click route does not match the physical UI"
        if pair not in _RIGHT_CLICK_PAIRS.get(modifier, set()):
            return f"right-click cannot emit {modifier}/{pair[0]}/{pair[1]}"
        if any(size not in {1, 2, 9} for size in sizes):
            return "right-click selection size is outside the witnessed matrix"
        return None
    if route_id == "field-aimed-command":
        if origins != ["field"] or gesture != "aim-command" \
                or queue_rule != "shift-modifier":
            return "field-aimed-command route does not match the physical UI"
        if pair not in _AIMED_PAIRS.get(modifier, set()):
            return f"aimed command cannot emit {modifier}/{pair[0]}/{pair[1]}"
        if any(size not in {1, 2, 9} for size in sizes):
            return "aimed-command selection size is outside the witnessed matrix"
        return None
    if route_id == "field-building-placement":
        if origins != ["field"] or gesture != "place-building" \
                or queue_rule != "shift-modifier":
            return "field-building-placement route does not match the physical UI"
        if modifier not in {"plain", "shift"} or pair not in {
                ("build", "open-ground"), ("build", "shore"),
                ("build", "oil-patch")} or sizes != [1]:
            return f"building placement cannot emit {modifier}/{pair}/{sizes}"
        return None
    if route_id == "field-critter-dismiss":
        if origins != ["field"] or gesture != "select" \
                or queue_rule != "never" or modifier != "plain" \
                or pair != ("dismiss", "unit") or sizes != [1]:
            return "critter dismiss route does not match the physical UI"
        return None
    if route_id == "command-panel-direct":
        if origins != ["command-panel"] or gesture != "button-or-slot" \
                or queue_rule != "never":
            return "command-panel route does not match the physical UI"
        family, shape = pair
        if modifier == "control" and family == "autocast" \
                and shape == "unit" and all(size in {1, 2, 9} for size in sizes):
            return None
        if modifier != "plain":
            return f"command-panel cannot emit {modifier}/{family}"
        if family in _PANEL_GROUP_FAMILIES and shape == "unit" \
                and all(size in {1, 2, 9} for size in sizes):
            return None
        if family in _PANEL_BUILDING_FAMILIES and shape == "building" \
                and sizes == [1]:
            return None
        if family == "unload" and shape == "transport" and sizes == [1]:
            return None
        if family == "unload-one" and shape == "unit" and sizes == [1]:
            return None
        return f"command-panel cannot emit {modifier}/{family}/{shape}/{sizes}"
    return f"unknown or uninstrumented physical route {route_id!r}"


def _declared_route_cells(requirements: dict[str, Any]) \
        -> set[tuple[Any, ...]]:
    routes = requirements.get("route_capabilities")
    if not isinstance(routes, list) or not routes:
        raise ValueError("player requirements have no physical route declarations")
    declared: set[tuple[Any, ...]] = set()
    route_ids: set[str] = set()
    for route in routes:
        if not isinstance(route, dict) or not isinstance(route.get("id"), str) \
                or route["id"] in route_ids:
            raise ValueError("player physical route id is missing or repeated")
        route_ids.add(route["id"])
        origins = route.get("origins")
        base_sizes = route.get("selection_sizes")
        queue_rule = route.get("queue_rule")
        variants = route.get("variants")
        if not isinstance(origins, list) or not origins \
                or not all(isinstance(item, str) for item in origins) \
                or "keyboard" in origins:
            raise ValueError("player physical route has an uninstrumented origin")
        if not isinstance(base_sizes, list) or not base_sizes \
                or not all(isinstance(item, int) and item > 0 for item in base_sizes):
            raise ValueError("player physical route has invalid selection sizes")
        if queue_rule not in {"shift-modifier", "never"}:
            raise ValueError("player physical route has no executable queue rule")
        if not isinstance(variants, list) or not variants:
            raise ValueError("player physical route has no command variants")
        for variant in variants:
            if not isinstance(variant, dict):
                raise ValueError("player physical route variant is not an object")
            modifier = variant.get("modifiers")
            shape = variant.get("target_shape")
            family = variant.get("family")
            dispositions = variant.get("dispositions")
            sizes = variant.get("selection_sizes", base_sizes)
            if not all(isinstance(value, str) and value
                       for value in (modifier, shape, family)) \
                    or not isinstance(sizes, list) or not sizes \
                    or not all(isinstance(item, int) and item > 0 for item in sizes) \
                    or not isinstance(dispositions, list) or not dispositions \
                    or any(item not in {"accepted", "rejected"}
                           for item in dispositions):
                raise ValueError("player physical route variant is incomplete")
            error = _physical_variant_error(route, variant, sizes)
            if error is not None:
                raise ValueError(error)
            queued = queue_rule == "shift-modifier" \
                and "shift" in modifier.split("+")
            for origin in origins:
                for size in sizes:
                    for disposition in dispositions:
                        key = (origin, modifier, size, shape, family, queued,
                               disposition == "accepted")
                        if key in declared:
                            raise ValueError(
                                "player physical routes repeat an exact transaction cell")
                        declared.add(key)
    return declared


def _requirements_cells(requirements: dict[str, Any]) \
        -> dict[str, dict[str, Any]]:
    if requirements.get("schema") != REQUIREMENTS_SCHEMA:
        raise ValueError("player requirements do not declare the fixed-cell schema")
    raw = requirements.get("cells")
    expected = int(requirements.get("fixed_cell_count", -1))
    if not isinstance(raw, list) or expected < 240 or len(raw) != expected:
        raise ValueError(
            "player requirements must contain their explicit >=240 fixed cells")
    declared = _declared_route_cells(requirements)
    cells: dict[str, dict[str, Any]] = {}
    predicates: set[tuple[Any, ...]] = set()
    for item in raw:
        if not isinstance(item, str):
            raise ValueError("player requirement cell is not a stable string")
        values = item.split("|")
        if len(values) != len(CELL_FIELDS):
            raise ValueError("player requirement cell has the wrong arity")
        predicate = dict(zip(CELL_FIELDS, (
            values[0], values[1], int(values[2]), values[3], values[4],
            values[5] == "queued", values[6] == "accepted",
        )))
        if values[5] not in {"queued", "immediate"} \
                or values[6] not in {"accepted", "rejected"}:
            raise ValueError("player requirement cell has invalid disposition")
        if not isinstance(predicate["origin"], str) \
                or not isinstance(predicate["modifiers"], str) \
                or not isinstance(predicate["selection_size"], int) \
                or not isinstance(predicate["target_shape"], str) \
                or not isinstance(predicate["family"], str) \
                or not isinstance(predicate["queued"], bool) \
                or not isinstance(predicate["accepted"], bool):
            raise ValueError("player requirement cell has incomplete dimensions")
        cell_id = f"ptx-{_digest(predicate)[:20]}"
        if cell_id in cells:
            raise ValueError("player requirement cell identity is unstable or repeated")
        key = tuple(predicate[field] for field in CELL_FIELDS)
        if key in predicates:
            raise ValueError("player requirements repeat an exact transaction cell")
        predicates.add(key)
        cells[cell_id] = {"id": cell_id, **predicate}
    if predicates != declared:
        missing = len(declared - predicates)
        extra = len(predicates - declared)
        raise ValueError(
            "player fixed cells do not equal the physical route declarations "
            f"(missing {missing}, extra {extra})")
    projections = {
        "origins": {item[0] for item in predicates},
        "modifiers": {item[1] for item in predicates},
        "selection_sizes": {item[2] for item in predicates},
        "target_shapes": {item[3] for item in predicates},
        "families": {item[4] for item in predicates},
        "queueable_families": {item[4] for item in predicates if item[5]},
        "rejection_families": {item[4] for item in predicates if not item[6]},
    }
    for name, values in projections.items():
        declared_values = requirements.get(name)
        if not isinstance(declared_values, list) or set(declared_values) != values \
                or len(declared_values) != len(values):
            raise ValueError(
                f"player requirements {name} do not match the fixed cells")
    return cells


_PREWIRE_REFUSAL_FAMILIES = frozenset({
    "build", "train", "research", "upgrade-to",
})


def _required_route_observed(route: dict[str, Any],
        transactions: list[dict[str, Any]],
        gestures: list[dict[str, Any]]) -> bool:
    """Return whether receipts already prove one required observation hook."""
    route_id = route.get("id")
    if route_id == "keyboard-command-hotkeys":
        return any(item.get("origin") == "keyboard" for item in gestures)
    if route_id == "production-prewire-refusal-decision":
        for transaction in transactions:
            decision = transaction.get("decision")
            if not isinstance(decision, dict):
                continue
            reason = decision.get("reason")
            feedback = transaction.get("feedback")
            acknowledged = isinstance(feedback, dict) \
                and feedback.get("acknowledged") is True
            if decision.get("accepted") is False \
                    and decision.get("queued") is False \
                    and decision.get("family") in _PREWIRE_REFUSAL_FAMILIES \
                    and isinstance(reason, str) and reason \
                    and acknowledged \
                    and not (transaction.get("commands") or ()):
                return True
        return False
    return False


def _transaction_cell_key(transaction: dict[str, Any]) \
        -> tuple[Any, ...] | None:
    gesture = transaction.get("gesture")
    commands = transaction.get("commands") or []
    if not isinstance(gesture, dict):
        return None
    if not commands:
        decision = transaction.get("decision")
        if not isinstance(decision, dict) \
                or decision.get("accepted") is not False \
                or not isinstance(decision.get("family"), str) \
                or not isinstance(decision.get("queued"), bool):
            return None
        return (
            gesture.get("origin"), gesture.get("modifiers"),
            len(gesture.get("selected_unit_ids") or ()),
            gesture.get("target_shape"), decision["family"],
            decision["queued"], False,
        )
    families = {item.get("family") for item in commands}
    queued = {item.get("queued") for item in commands}
    accepted = {item.get("accepted") for item in commands}
    if len(families) != 1 or len(queued) != 1 or len(accepted) != 1 \
            or not all(isinstance(value, bool) for value in accepted):
        return None
    return (
        gesture.get("origin"), gesture.get("modifiers"),
        len(gesture.get("selected_unit_ids") or ()),
        gesture.get("target_shape"), next(iter(families)),
        next(iter(queued)), next(iter(accepted)),
    )


def _validated_receipts(receipts: list[dict[str, Any]], *,
        expected_side: str | None = None,
        current_java_engine_input_sha256: str | None = None,
        current_java_program_input_sha256: str | None = None) \
        -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[str]]:
    valid = []
    invalid = []
    duplicates = []
    seen: set[str] = set()
    for receipt in receipts:
        identity = receipt.get("receipt_sha256")
        if isinstance(identity, str) and identity in seen:
            duplicates.append(identity)
            continue
        if isinstance(identity, str):
            seen.add(identity)
        errors = _receipt_errors(
            receipt, expected_side=expected_side,
            current_java_engine_input_sha256=current_java_engine_input_sha256,
            current_java_program_input_sha256=current_java_program_input_sha256)
        if errors:
            invalid.append({"receipt_sha256": identity, "errors": errors})
        else:
            valid.append(receipt)
    return valid, invalid, sorted(set(duplicates))


def coverage(receipts: list[dict[str, Any]], requirements: dict[str, Any], *,
        expected_side: str | None = None,
        current_java_engine_input_sha256: str | None = None,
        current_java_program_input_sha256: str | None = None) \
        -> dict[str, Any]:
    cells = _requirements_cells(requirements)
    valid_receipts, invalid_receipts, duplicate_receipts = _validated_receipts(
        receipts, expected_side=expected_side,
        current_java_engine_input_sha256=current_java_engine_input_sha256,
        current_java_program_input_sha256=current_java_program_input_sha256)
    transactions = [transaction for receipt in valid_receipts
                    for transaction in receipt.get("transactions") or ()]
    gestures = [item["gesture"] for item in transactions if item.get("gesture")]
    commands = [command for item in transactions for command in item.get("commands") or ()]
    outcomes = [outcome for item in transactions for outcome in item.get("outcomes") or ()]
    observed = {
        "origins": sorted({item.get("origin") for item in gestures if item.get("origin")}),
        "modifiers": sorted({item.get("modifiers") for item in gestures
                             if item.get("modifiers")}),
        "selection_sizes": sorted({len(item.get("selected_unit_ids") or ())
                                   for item in gestures}),
        "target_shapes": sorted({item.get("target_shape") for item in gestures
                                 if item.get("target_shape")}),
        "families": sorted({item.get("family") for item in commands
                            if item.get("family")}),
    }
    missing: dict[str, list[Any]] = {}
    for dimension in ("origins", "modifiers", "selection_sizes",
                      "target_shapes", "families"):
        wanted = requirements.get(dimension) or []
        missing[dimension] = [value for value in wanted
                              if value not in observed[dimension]]
    queueable = set(requirements.get("queueable_families") or ())
    queued = {item.get("family") for item in commands if item.get("queued")}
    missing["queued_families"] = sorted(queueable - queued)
    accepted = {item.get("family") for item in commands
                if item.get("accepted") is True}
    rejected = {item.get("family") for item in commands
                if item.get("accepted") is False}
    rejection_required = set(requirements.get("rejection_families") or ())
    missing["accepted_families"] = sorted(set(requirements.get("families") or ())
                                          - accepted)
    missing["rejected_families"] = sorted(rejection_required - rejected)
    required_layers = list(requirements.get("required_layers") or ())
    unknown_layers = sorted(set(required_layers) - set(TRANSACTION_LAYERS))
    if unknown_layers:
        raise ValueError(f"unknown required transaction layers: {unknown_layers}")
    layer_counts = {
        layer: sum(1 for item in transactions
                   if (item.get("coverage") or {}).get("layers", {}).get(layer))
        for layer in TRANSACTION_LAYERS
    }
    missing["layers"] = [
        layer for layer in required_layers
        if not transactions or layer_counts.get(layer, 0) != len(transactions)
    ]
    feedback_modes = {
        str(command["feedback"].get("mode"))
        for command in commands if isinstance(command.get("feedback"), dict)
        and command["feedback"].get("mode") is not None
    }
    feedback_modes.update(
        str(item["feedback"].get("mode"))
        for item in transactions if isinstance(item.get("feedback"), dict)
        and item["feedback"].get("mode") is not None)
    missing["feedback_modes"] = [
        value for value in requirements.get("feedback_modes") or ()
        if value not in feedback_modes
    ]
    required_route_debt = requirements.get("required_uninstrumented_routes") or []
    if not isinstance(required_route_debt, list) or any(
            not isinstance(item, dict)
            or not isinstance(item.get("id"), str)
            or not isinstance(item.get("origin"), str)
            or not isinstance(item.get("required_hook"), str)
            for item in required_route_debt):
        raise ValueError("player requirements have invalid required route debt")
    # These routes stay off the 532-cell matrix so a keyboard row cannot
    # invent coverage.  They still have to be observed: a receipt that never
    # began a hotkey transaction, or never journaled a pre-wire production
    # refusal, leaves the matching debt in place.
    missing["required_routes"] = [
        item for item in required_route_debt
        if not _required_route_observed(item, transactions, gestures)
    ]
    incomplete = [item["transaction_id"] for item in transactions
                  if item.get("commands") and not item["coverage"]["terminal"]]
    gestureless = [item["transaction_id"] for item in transactions
                   if item.get("commands") and not item["coverage"]["physical_gesture"]]
    group_count = sum(1 for item in transactions if item["coverage"]["group_fanout"])
    by_key = {
        tuple(cell[field] for field in CELL_FIELDS): cell_id
        for cell_id, cell in cells.items()
    }
    filled: dict[str, list[dict[str, Any]]] = {}
    identity_debt = []
    transaction_receipt: dict[int, dict[str, Any]] = {
        id(transaction): receipt for receipt in valid_receipts
        for transaction in receipt.get("transactions") or ()
    }
    for transaction in transactions:
        key = _transaction_cell_key(transaction)
        cell_id = by_key.get(key) if key is not None else None
        if cell_id is None:
            continue
        if not all((transaction.get("coverage") or {}).get(
                "layers", {}).get(layer) for layer in required_layers):
            continue
        receipt = transaction_receipt[id(transaction)]
        identities = _unit_identity_index(receipt)
        try:
            canonical = canonical_transaction(
                transaction, identities, require_stable=True,
                side=(receipt.get("authority") or {}).get("side"))
        except ValueError as error:
            identity_debt.append({
                "receipt_sha256": receipt["receipt_sha256"],
                "transaction_id": transaction.get("transaction_id"),
                "reason": str(error),
            })
            continue
        filled.setdefault(cell_id, []).append({
            "receipt_sha256": receipt["receipt_sha256"],
            "scenario_id": (receipt.get("authority") or {}).get("scenario_id"),
            "transaction_id": transaction.get("transaction_id"),
            "canonical_sha256": _digest(canonical),
        })
    missing_cells = sorted(set(cells) - set(filled))
    complete = (not invalid_receipts and not duplicate_receipts
                and not incomplete and not gestureless and not identity_debt
                and not any(missing.values())
                and group_count >= int(
                    requirements.get("minimum_group_transactions", 0))
                and not missing_cells and len(filled) == len(cells))
    report = {
        "schema": "chonkcraft-bne-player-transaction-coverage-1",
        "complete": complete,
        "requirements_sha256": _digest(requirements),
        "fixed_cell_count": len(cells),
        "filled_cell_count": len(filled),
        "missing_cells": missing_cells,
        "filled_cells": [
            {"id": cell_id, "proofs": filled[cell_id]}
            for cell_id in sorted(filled)
        ],
        "receipts": len(valid_receipts),
        "receipt_identities": sorted(
            receipt["receipt_sha256"] for receipt in valid_receipts),
        "invalid_receipts": invalid_receipts,
        "duplicate_receipts": duplicate_receipts,
        "identity_debt": identity_debt,
        "transactions": len(transactions),
        "gestures": len(gestures),
        "commands": len(commands),
        "outcomes": len(outcomes),
        "group_transactions": group_count,
        "queued_commands": sum(1 for item in commands if item.get("queued")),
        "layer_counts": layer_counts,
        "feedback_modes": sorted(feedback_modes),
        "gestureless_transactions": gestureless,
        "incomplete_transactions": incomplete,
        "observed": observed,
        "missing": missing,
    }
    report["capture_plan"] = capture_plan({
        **missing,
        "cells": missing_cells,
        "cell_records": [cells[cell_id] for cell_id in missing_cells],
    })
    return report


def certify(native: dict[str, Any] | list[dict[str, Any]],
        java: dict[str, Any] | list[dict[str, Any]],
        requirements: dict[str, Any], *,
        current_java_engine_input_sha256: str | None = None,
        current_java_program_input_sha256: str | None = None) -> dict[str, Any]:
    """Certify paired native/Java physical transactions fail-closed.

    Coverage on one producer is not parity.  This joins the two authenticated
    sides, requires every declared layer on both, then compares the canonical
    transaction from gesture through terminal result in order.
    """
    native_receipts = [native] if isinstance(native, dict) else list(native)
    java_receipts = [java] if isinstance(java, dict) else list(java)
    current_engine = (current_java_engine_input_sha256
                      or _current_engine_input_sha256())
    current_program = (current_java_program_input_sha256
                       or current_program_input_sha256())
    cells = _requirements_cells(requirements)
    native_coverage = coverage(
        native_receipts, requirements, expected_side="native")
    java_coverage = coverage(
        java_receipts, requirements, expected_side="java",
        current_java_engine_input_sha256=current_engine,
        current_java_program_input_sha256=current_program)

    def candidates(receipts: list[dict[str, Any]], side: str) \
            -> dict[str, list[dict[str, Any]]]:
        valid, invalid, duplicates = _validated_receipts(
            receipts, expected_side=side,
            current_java_engine_input_sha256=(
                current_engine if side == "java" else None),
            current_java_program_input_sha256=(
                current_program if side == "java" else None))
        if invalid or duplicates:
            return {}
        by_key = {
            tuple(cell[field] for field in CELL_FIELDS): cell_id
            for cell_id, cell in cells.items()
        }
        result: dict[str, list[dict[str, Any]]] = {}
        required_layers = list(requirements.get("required_layers") or ())
        for receipt in valid:
            identities = _unit_identity_index(receipt)
            scenario = (receipt.get("authority") or {}).get("scenario_id")
            for transaction in receipt.get("transactions") or ():
                cell_id = by_key.get(_transaction_cell_key(transaction))
                if cell_id is None or not all(
                        (transaction.get("coverage") or {}).get(
                            "layers", {}).get(layer)
                        for layer in required_layers):
                    continue
                try:
                    canonical = canonical_transaction(
                        transaction, identities, require_stable=True,
                        side=side)
                except ValueError:
                    continue
                result.setdefault(cell_id, []).append({
                    "scenario_id": scenario,
                    "receipt_sha256": receipt["receipt_sha256"],
                    "transaction_id": transaction.get("transaction_id"),
                    "canonical": canonical,
                    "canonical_sha256": _digest(canonical),
                })
        return result

    native_candidates = candidates(native_receipts, "native")
    java_candidates = candidates(java_receipts, "java")
    differences = []
    paired_rows = []
    exact_cells = 0
    paired_receipts: set[str] = set()
    for cell_id in sorted(cells):
        native_rows = native_candidates.get(cell_id, [])
        java_rows = java_candidates.get(cell_id, [])
        scenarios = sorted(
            {item["scenario_id"] for item in native_rows}
            & {item["scenario_id"] for item in java_rows})
        scenario_results = []
        for scenario in scenarios:
            native_values = sorted(
                item["canonical_sha256"] for item in native_rows
                if item["scenario_id"] == scenario)
            java_values = sorted(
                item["canonical_sha256"] for item in java_rows
                if item["scenario_id"] == scenario)
            scenario_results.append({
                "scenario_id": scenario,
                "exact": native_values == java_values,
                "native": native_values,
                "java": java_values,
            })
        exact = bool(scenario_results) and all(
            item["exact"] for item in scenario_results)
        if exact:
            exact_cells += 1
            for item in native_rows + java_rows:
                if item["scenario_id"] in scenarios:
                    paired_receipts.add(item["receipt_sha256"])
        else:
            differences.append({
                "cell_id": cell_id,
                "cell": cells[cell_id],
                "reason": ("no-shared-authenticated-scenario"
                           if not scenarios else "canonical-transaction-mismatch"),
                "scenarios": scenario_results,
            })
        paired_rows.append({
            "cell_id": cell_id, "exact": exact,
            "shared_scenarios": scenarios,
        })
    minimum_paired = len(cells)
    # Receipt JSON is a normalized comparison surface, not its own producer
    # proof.  A caller must first reopen the native capture closure and the
    # Java execution/build closure.  Until a retained-store validator supplies
    # that fact, even 532/532 matching detached receipts remain diagnostic.
    content_exact = (
        native_coverage["complete"] and java_coverage["complete"]
        and exact_cells == minimum_paired and not differences)
    producer_receipts_verified = False
    complete = False
    return {
        "schema": CERTIFICATION_SCHEMA,
        "complete": complete,
        "content_exact": content_exact,
        "producer_receipts_verified": producer_receipts_verified,
        "debt": (None if producer_receipts_verified else
                 "detached receipt JSON cannot certify its producer evidence"),
        "authority": {
            "java_engine_input_sha256": current_engine,
            "java_program_input_sha256": current_program,
            "native_executable_sha256": PINNED_BNE_EXECUTABLE_SHA256,
            "requirements_sha256": _digest(requirements),
            "native_receipt_sha256": sorted(
                receipt["receipt_sha256"] for receipt in native_receipts
                if receipt.get("receipt_sha256")),
            "java_receipt_sha256": sorted(
                receipt["receipt_sha256"] for receipt in java_receipts
                if receipt.get("receipt_sha256")),
            "paired_receipt_sha256": sorted(paired_receipts),
        },
        "paired_transactions": exact_cells,
        "minimum_paired_transactions": minimum_paired,
        "native": native_coverage,
        "java": java_coverage,
        "difference_count": len(differences),
        "first_difference": differences[0] if differences else None,
        "differences": differences,
        "cells": paired_rows,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Compile and certify BNE player transaction receipts")
    sub = parser.add_subparsers(dest="command", required=True)
    compile_parser = sub.add_parser("compile-evidence")
    compile_parser.add_argument("evidence", type=Path)
    compile_parser.add_argument("--output", required=True, type=Path)
    trace_parser = sub.add_parser("from-ui-trace")
    trace_parser.add_argument("trace", type=Path)
    trace_parser.add_argument("--output", required=True, type=Path)
    trace_parser.add_argument("--map-path", type=str, default=None)
    trace_parser.add_argument(
        "--manifest", type=Path,
        help="sealed oracle manifest that authenticates the native trace")
    trace_parser.add_argument(
        "--fixture", type=Path,
        help="sealed .bnefx whose bytes close --manifest and the native trace")
    import_parser = sub.add_parser(
        "import-native", help="import sealed native physical-UI capture directories")
    import_parser.add_argument("capture_dirs", nargs="+", type=Path)
    import_parser.add_argument("--output-dir", required=True, type=Path)
    import_parser.add_argument("--catalog", type=Path)
    compare_parser = sub.add_parser("compare")
    compare_parser.add_argument("left", type=Path)
    compare_parser.add_argument("right", type=Path)
    coverage_parser = sub.add_parser("coverage")
    coverage_parser.add_argument("receipts", nargs="+", type=Path)
    coverage_parser.add_argument("--requirements", required=True, type=Path)
    coverage_parser.add_argument("--output", type=Path)
    coverage_parser.add_argument("--require-complete", action="store_true")
    coverage_parser.add_argument("--side", choices=("native", "java"))
    certify_parser = sub.add_parser("certify")
    certify_parser.add_argument("--native", required=True, nargs="+", type=Path)
    certify_parser.add_argument("--java", required=True, nargs="+", type=Path)
    certify_parser.add_argument("--requirements", required=True, type=Path)
    certify_parser.add_argument("--output", type=Path)
    certify_parser.add_argument("--require-complete", action="store_true")
    args = parser.parse_args(argv)

    if args.command == "compile-evidence":
        compiled = compile_evidence(_load(args.evidence), source=str(args.evidence))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(compiled, indent=2, sort_keys=True) + "\n",
                               encoding="utf-8")
        print(json.dumps({"transactions": len(compiled["transactions"]),
                          "output": str(args.output)}, indent=2))
        return 0
    if args.command == "from-ui-trace":
        trace_bytes = args.trace.read_bytes()
        authority = None
        if args.manifest is not None:
            if args.fixture is None:
                raise ValueError("--manifest requires the closing --fixture archive")
            manifest_bytes = args.manifest.read_bytes()
            with zipfile.ZipFile(args.fixture) as archive:
                if archive.read("manifest.json") != manifest_bytes:
                    raise ValueError("native fixture and sidecar manifest differ")
            authority = authority_from_native_manifest(
                json.loads(manifest_bytes), trace_bytes,
                manifest_source=str(args.manifest),
                fixture_validation=bne_fixture.validate_fixture(args.fixture))
        elif args.fixture is not None:
            raise ValueError("--fixture cannot authenticate without --manifest")
        compiled = compile_ui_trace(
            trace_bytes.decode("utf-8", errors="replace"),
            source=str(args.trace), map_path=args.map_path,
            authority=authority)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(compiled, indent=2, sort_keys=True) + "\n",
                               encoding="utf-8")
        print(json.dumps({"transactions": len(compiled["transactions"]),
                          "output": str(args.output)}, indent=2))
        return 0
    if args.command == "import-native":
        catalog = import_native_captures(args.capture_dirs, args.output_dir)
        rendered = json.dumps(catalog, indent=2, sort_keys=True) + "\n"
        if args.catalog is not None:
            args.catalog.parent.mkdir(parents=True, exist_ok=True)
            args.catalog.write_text(rendered, encoding="utf-8")
        print(rendered, end="")
        return 0
    if args.command == "certify":
        report = certify([_load(path) for path in args.native],
                         [_load(path) for path in args.java],
                         _load(args.requirements))
        rendered = json.dumps(report, indent=2, sort_keys=True) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(rendered, encoding="utf-8")
        print(rendered, end="")
        return 0 if report["complete"] or not args.require_complete else 1
    if args.command == "compare":
        difference = first_difference(_load(args.left), _load(args.right))
        print(json.dumps({"identical": difference is None,
                          "difference": difference}, indent=2, sort_keys=True))
        return 0 if difference is None else 1
    if args.command == "coverage":
        report = coverage([_load(path) for path in args.receipts],
                          _load(args.requirements), expected_side=args.side,
                          current_java_engine_input_sha256=(
                              _current_engine_input_sha256()
                              if args.side == "java" else None),
                          current_java_program_input_sha256=(
                              current_program_input_sha256()
                              if args.side == "java" else None))
        rendered = json.dumps(report, indent=2, sort_keys=True) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(rendered, encoding="utf-8")
        print(rendered, end="")
        return 1 if args.require_complete and not report["complete"] else 0
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
