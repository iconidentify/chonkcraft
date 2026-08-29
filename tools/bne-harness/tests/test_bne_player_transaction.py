"""Tests for physical-input-to-outcome transaction receipts."""

from pathlib import Path
import hashlib
import json
import sys
import tempfile
import unittest
from unittest import mock
import zipfile


SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_player_transaction as transaction


JAVA_ENGINE_SHA256 = "a" * 64
JAVA_PROGRAM_SHA256 = "b" * 64
SCENARIO_SHA256 = "f" * 64


def authority(side: str) -> dict:
    common = {
        "side": side,
        "producer": f"test-{side}",
        "authenticated": True,
        "fixture_id": SCENARIO_SHA256,
        "scenario_id": SCENARIO_SHA256,
    }
    if side == "native":
        return {
            **common,
            "build_sha256": transaction.PINNED_BNE_EXECUTABLE_SHA256,
            "manifest_sha256": "1" * 64,
            "fixture_archive_sha256": "2" * 64,
            "tracer_sha256": "3" * 64,
            "injector_sha256": "4" * 64,
            "source_manifest_sha256": "5" * 64,
        }
    return {
        **common,
        "build_sha256": JAVA_ENGINE_SHA256,
        "engine_input_sha256": JAVA_ENGINE_SHA256,
        "program_input_sha256": JAVA_PROGRAM_SHA256,
    }


def production_requirements() -> dict:
    path = Path(__file__).parents[1] / "player-transaction-requirements.json"
    return json.loads(path.read_text(encoding="utf-8"))


def move_wire(side: str, unit_id: int) -> str:
    if side == "native":
        # Retail 0x13: x=12, y=13, no target, GiveOrder function 3.
        return "130c000d00ffff03"
    return bytes((1, 0)).hex() + (
        unit_id.to_bytes(4, "big", signed=True)
        + (12).to_bytes(2, "big", signed=True)
        + (13).to_bytes(2, "big", signed=True)
        + (0).to_bytes(4, "big", signed=True)
        + (0).to_bytes(2, "big", signed=True)
        + bytes((1,))
    ).hex()


def native_manifest(payload: bytes) -> tuple[dict, dict]:
    closure = {
        "oracle_executable": transaction.PINNED_BNE_EXECUTABLE_SHA256,
        "oracle_data": {"data": "6" * 64},
        "tracer": "3" * 64,
        "scenario": "physical-ui-test",
        "cycle_limit": 80,
        "initialization_seed": 123,
        "commands": None,
        "replay": None,
        "simulation": "7" * 64,
        "state_schema": 1,
        "schema": 1,
    }
    fixture_id = transaction._digest(closure)
    manifest = {
        "schema": 2,
        "fixture": {"id": fixture_id, "key": closure},
        "oracle": {
            "executable": {
                "sha256": transaction.PINNED_BNE_EXECUTABLE_SHA256},
            "data": {"data": {"sha256": "6" * 64}},
        },
        "harness": {
            "tracer": {"sha256": "3" * 64},
            "injector": {"sha256": "4" * 64},
            "state_schema": 1,
        },
        "source": {"manifest": {"sha256": "5" * 64}},
        "run": {
            "requested_scenario": "physical-ui-test",
            "cycle_limit": 80,
            "initialization_seed": 123,
            "commands": None,
            "replay": None,
            "validation": {"simulation_sha256": "7" * 64},
            "trace": {
                "sha256": hashlib.sha256(payload).hexdigest(),
                "bytes": len(payload),
            },
        },
    }
    return manifest, {"fixture_id": fixture_id, "sha256": "8" * 64}


def evidence(*, gesture: bool = True, terminal: bool = True,
             second_unit: bool = True, feedback: bool = True,
             side: str = "java"):
    intents = []
    if gesture:
        intents.append({
            "intent_id": 1,
            "transaction_id": 1,
            "cycle": 10,
            "event": "gesture",
            "selected_unit_ids": [7, 9] if second_unit else [7],
            "gesture": {
                "origin": "field",
                "detail": "right-click",
                "screen_x": 320,
                "screen_y": 240,
                "tile_x": 12,
                "tile_y": 13,
                "modifiers": "shift",
                "target_id": None,
                "target_shape": "open-ground",
            },
        })
    ids = [7, 9] if second_unit else [7]
    for offset, unit_id in enumerate(ids, 2):
        intents.append({
            "intent_id": offset,
            "transaction_id": 1,
            "cycle": 10,
            "event": "order",
            "selected_unit_ids": ids,
            "accepted": True,
            "command": {
                "fanout_ordinal": offset - 2,
                "kind": "MOVE",
                "player": 0,
                "unit_id": unit_id,
                "x": 12,
                "y": 13,
                "target_id": None if side == "native" else 0,
                "type_index": 0,
                "queued": True,
                "wire_hex": move_wire(side, unit_id),
            },
        })
    outcomes = []
    for offset, unit_id in enumerate(ids, 2):
        outcomes.append({
            "intent_id": offset,
            "transaction_id": 1,
            "submitted_cycle": 10,
            "unit_id": unit_id,
            "command": "MOVE",
            "accepted": True,
            "first_progress_cycle": 12,
            "terminal_cycle": 30 if terminal else None,
            "terminal_reason": "settled" if terminal else None,
            "tile_x": 12,
            "tile_y": 13,
            "offset_x": 0,
            "offset_y": 0,
            "order": "STILL",
            "target_id": None,
            "hit_points": 60,
            "carried": 0,
            "alive": True,
            "on_map": True,
            "missile_count": 0,
        })
    player_feedback = []
    if feedback:
        for offset, _unit_id in enumerate(ids, 2):
            player_feedback.append({
                "intent_id": offset,
                "transaction_id": 1,
                "cycle": 10,
                "acknowledged": True,
                "mode": "voice",
                "detail": "unit-acknowledgement",
            })
    return {
        "authority": authority(side),
        "map_path": "maps/test.pud",
        "campaign": None,
        "mission": 0,
        "player_intents": intents,
        "player_outcomes": outcomes,
        "player_feedback": player_feedback,
        "unit_identities": {
            "schema": transaction.UNIT_IDENTITY_SCHEMA,
            "units": [{
                "local_id": unit_id,
                "generation": 0,
                "identity": {
                    "origin": "initial", "owner": 0,
                    "type": "footman", "x": 10 + ordinal,
                    "y": 11, "ordinal": ordinal,
                },
            } for ordinal, unit_id in enumerate(ids)],
        },
    }


def store_evidence(side: str) -> dict:
    value = evidence(second_unit=False, side=side)
    value["player_intents"][0]["gesture"]["modifiers"] = "plain"
    command = value["player_intents"][1]["command"]
    command["queued"] = False
    if side == "java":
        command["wire_hex"] = command["wire_hex"][:-2] + "00"
    value["player_decisions"] = [{
        "transaction_id": 1, "cycle": 10, "accepted": True,
        "family": "move", "queued": False, "reason": "give-order",
    }]
    return value


def store_requirements() -> tuple[dict, dict]:
    requirements = {
        "minimum_transactions": 1,
        "minimum_paired_transactions": 1,
        "minimum_group_transactions": 0,
        "origins": ["field"], "modifiers": ["plain"],
        "selection_sizes": [1], "target_shapes": ["open-ground"],
        "families": ["move"], "queueable_families": [],
        "rejection_families": [], "feedback_modes": ["voice"],
        "required_layers": list(transaction.TRANSACTION_LAYERS),
    }
    cell = {
        "id": "ptx-store", "origin": "field", "modifiers": "plain",
        "selection_size": 1, "target_shape": "open-ground",
        "family": "move", "queued": False, "accepted": True,
    }
    return requirements, cell


class PlayerTransactionTest(unittest.TestCase):

    def test_group_fanout_stays_one_transaction(self):
        compiled = transaction.compile_evidence(evidence(), source="test")
        self.assertEqual(1, len(compiled["transactions"]))
        item = compiled["transactions"][0]
        self.assertTrue(item["coverage"]["physical_gesture"])
        self.assertTrue(item["coverage"]["group_fanout"])
        self.assertTrue(item["coverage"]["queued"])
        self.assertTrue(item["coverage"]["terminal"])
        self.assertEqual([7, 9],
                         [command["unit_id"] for command in item["commands"]])

    def test_gesture_difference_is_the_first_causal_difference(self):
        left = transaction.compile_evidence(evidence(), source="left")
        changed = evidence()
        changed["player_intents"][0]["gesture"]["tile_x"] = 14
        right = transaction.compile_evidence(changed, source="right")
        difference = transaction.first_difference(left, right)
        self.assertEqual(0, difference["transaction"])
        self.assertEqual("gesture", difference["field"])

    def test_gestureless_command_is_explicit_coverage_debt(self):
        compiled = transaction.compile_evidence(
            evidence(gesture=False, second_unit=False), source="test")
        requirements = production_requirements()
        report = transaction.coverage([compiled], requirements)
        self.assertFalse(report["complete"])
        self.assertEqual([1], report["gestureless_transactions"])
        self.assertEqual(
            ["command-panel", "field", "minimap"],
            report["missing"]["origins"])

    def test_unfinished_command_prevents_certification(self):
        compiled = transaction.compile_evidence(
            evidence(terminal=False, second_unit=False), source="test")
        requirements = production_requirements()
        report = transaction.coverage([compiled], requirements)
        self.assertFalse(report["complete"])
        self.assertEqual([1], report["incomplete_transactions"])

    def test_short_observation_flush_is_not_a_command_terminal(self):
        for reason, first_progress in (
                ("window-complete", 12),
                ("acknowledged-no-progress", None)):
            with self.subTest(reason=reason):
                raw = evidence(second_unit=False)
                outcome = raw["player_outcomes"][0]
                outcome["first_progress_cycle"] = first_progress
                outcome["terminal_cycle"] = 40
                outcome["terminal_reason"] = reason
                compiled = transaction.compile_evidence(raw, source="short")
                item = compiled["transactions"][0]
                self.assertFalse(item["coverage"]["terminal"])
                self.assertFalse(
                    item["coverage"]["layers"]["terminal-outcome"])

    def test_complete_observation_window_is_an_honest_terminal(self):
        for reason, first_progress in (
                ("window-complete", 12),
                ("acknowledged-no-progress", None)):
            with self.subTest(reason=reason):
                raw = evidence(second_unit=False)
                outcome = raw["player_outcomes"][0]
                outcome["first_progress_cycle"] = first_progress
                outcome["terminal_cycle"] = (
                    outcome["submitted_cycle"]
                    + transaction.PLAYER_TRANSACTION_OUTCOME_WINDOW)
                outcome["terminal_reason"] = reason
                compiled = transaction.compile_evidence(raw, source="window")
                self.assertTrue(
                    compiled["transactions"][0]["coverage"]["terminal"])

    def test_unknown_or_timeless_terminal_reason_fails_closed(self):
        for reason, submitted_cycle, terminal_cycle in (
                ("capture-ended", 10, 40),
                ("settled", 10, None),
                ("settled", None, 40)):
            with self.subTest(reason=reason, submitted_cycle=submitted_cycle,
                              terminal_cycle=terminal_cycle):
                raw = evidence(second_unit=False)
                outcome = raw["player_outcomes"][0]
                outcome["submitted_cycle"] = submitted_cycle
                outcome["terminal_cycle"] = terminal_cycle
                outcome["terminal_reason"] = reason
                compiled = transaction.compile_evidence(raw, source="invalid")
                self.assertFalse(
                    compiled["transactions"][0]["coverage"]["terminal"])

    def test_prewire_production_refusal_has_a_real_fixed_cell_key(self):
        raw = evidence(second_unit=False)
        gesture = raw["player_intents"][0]
        gesture["gesture"].update({
            "origin": "command-panel", "detail": "train-unit",
            "target_shape": "building", "modifiers": "plain",
        })
        raw["player_intents"] = [gesture]
        raw["player_outcomes"] = []
        raw["player_feedback"] = [{
            "intent_id": 1, "transaction_id": 1, "cycle": 10,
            "acknowledged": False, "mode": "silent",
            "detail": "not-enough-resources",
        }]
        raw["player_decisions"] = [{
            "transaction_id": 1, "accepted": False,
            "family": "train", "queued": False,
            "reason": "not-enough-resources", "cycle": 10,
        }]
        compiled = transaction.compile_evidence(raw, source="test")
        item = compiled["transactions"][0]
        self.assertTrue(all(item["coverage"]["layers"].values()))
        self.assertEqual(
            ("command-panel", "plain", 1, "building", "train", False, False),
            transaction._transaction_cell_key(item))

    def test_missing_acknowledgement_is_a_named_layer_debt(self):
        compiled = transaction.compile_evidence(
            evidence(feedback=False, second_unit=False), source="test")
        requirements = production_requirements()
        requirements["required_layers"] = ["acknowledgement"]
        report = transaction.coverage([compiled], requirements)
        self.assertFalse(report["complete"])
        self.assertEqual(["acknowledgement"], report["missing"]["layers"])
        self.assertEqual(0, report["layer_counts"]["acknowledgement"])

    def test_native_and_java_wires_share_a_validated_give_order_contract(self):
        native = transaction.compile_evidence(
            evidence(second_unit=False, side="native"), source="native")
        java = transaction.compile_evidence(
            evidence(second_unit=False, side="java"), source="java")
        native_command = native["transactions"][0]["commands"][0]
        java_command = java["transactions"][0]["commands"][0]
        self.assertNotEqual(native_command["wire_hex"], java_command["wire_hex"],
                            "the producer transports must remain distinct")
        native_canonical = transaction.canonical_transaction(
            native["transactions"][0], transaction._unit_identity_index(native),
            require_stable=True, side="native")
        java_canonical = transaction.canonical_transaction(
            java["transactions"][0], transaction._unit_identity_index(java),
            require_stable=True, side="java")
        self.assertEqual(native_canonical, java_canonical)
        self.assertEqual(
            "bne-give-order-0x13",
            native_canonical["commands"][0]["wire"]["protocol"])

    def test_canonical_outcomes_follow_completion_time_not_snapshot_order(self):
        native_raw = evidence(side="native")
        java_raw = evidence(side="java")
        for raw in (native_raw, java_raw):
            raw["player_outcomes"][0]["terminal_cycle"] = 40
            raw["player_outcomes"][1]["terminal_cycle"] = 30
        native_raw["player_outcomes"].reverse()
        native = transaction.compile_evidence(native_raw, source="native")
        java = transaction.compile_evidence(java_raw, source="java")

        native_canonical = transaction.canonical_transaction(
            native["transactions"][0], transaction._unit_identity_index(native),
            require_stable=True, side="native")
        java_canonical = transaction.canonical_transaction(
            java["transactions"][0], transaction._unit_identity_index(java),
            require_stable=True, side="java")

        self.assertEqual(native_canonical, java_canonical)
        self.assertEqual(
            [30, 40],
            [item["terminal_cycle"] for item in native_canonical["outcomes"]])

    def test_wire_normalization_rejects_disagreement_on_either_side(self):
        native_raw = evidence(second_unit=False, side="native")
        native_raw["player_intents"][1]["command"]["wire_hex"] = \
            "130c000d00ffff05"
        native = transaction.compile_evidence(native_raw, source="native")
        self.assertTrue(any(
            "wire contract" in error and "not 'move'" in error
            for error in transaction._receipt_errors(
                native, expected_side="native")))

        java_raw = evidence(second_unit=False, side="java")
        wire = bytearray.fromhex(
            java_raw["player_intents"][1]["command"]["wire_hex"])
        wire[7] += 1
        java_raw["player_intents"][1]["command"]["wire_hex"] = wire.hex()
        java = transaction.compile_evidence(java_raw, source="java")
        self.assertTrue(any(
            "wire contract" in error and "journaled command" in error
            for error in transaction._receipt_errors(
                java, expected_side="java",
                current_java_engine_input_sha256=JAVA_ENGINE_SHA256,
                current_java_program_input_sha256=JAVA_PROGRAM_SHA256)))

    def test_certification_requires_complete_exact_native_and_java_layers(self):
        native = transaction.compile_evidence(
            evidence(second_unit=False, side="native"), source="native")
        java = transaction.compile_evidence(
            evidence(second_unit=False, side="java"), source="java")
        native_extra_evidence = evidence(second_unit=False, side="native")
        native_extra_evidence["mission"] = 1
        native_extra = transaction.compile_evidence(
            native_extra_evidence, source="native-extra")
        java_extra_evidence = evidence(second_unit=False, side="java")
        java_extra_evidence["mission"] = 1
        java_extra = transaction.compile_evidence(
            java_extra_evidence, source="java-extra")
        requirements = {
            "minimum_transactions": 1,
            "minimum_paired_transactions": 1,
            "minimum_group_transactions": 0,
            "origins": ["field"],
            "modifiers": ["shift"],
            "selection_sizes": [1],
            "target_shapes": ["open-ground"],
            "families": ["move"],
            "queueable_families": ["move"],
            "rejection_families": [],
            "feedback_modes": ["voice"],
            "required_layers": list(transaction.TRANSACTION_LAYERS),
        }
        cell = {
            "id": "ptx-test",
            "origin": "field", "modifiers": "shift", "selection_size": 1,
            "target_shape": "open-ground", "family": "move",
            "queued": True, "accepted": True,
        }
        with mock.patch.object(
                transaction, "_requirements_cells",
                return_value={"ptx-test": cell}):
            report = transaction.certify(
                [native, native_extra], [java, java_extra], requirements,
                current_java_engine_input_sha256=JAVA_ENGINE_SHA256,
                current_java_program_input_sha256=JAVA_PROGRAM_SHA256)
        self.assertTrue(report["content_exact"])
        self.assertFalse(report["complete"])
        self.assertEqual(1, report["paired_transactions"])
        self.assertEqual(0, report["difference_count"])
        self.assertEqual(4, len(
            report["authority"]["paired_receipt_sha256"]))

    def test_detached_receipts_can_never_certify_their_own_producers(self):
        native = transaction.compile_evidence(
            evidence(second_unit=False, side="native"), source="native")
        java = transaction.compile_evidence(
            evidence(second_unit=False, side="java"), source="java")
        requirements = {"required_layers": list(transaction.TRANSACTION_LAYERS)}
        cell = {
            "id": "ptx-test", "origin": "field", "modifiers": "shift",
            "selection_size": 1, "target_shape": "open-ground",
            "family": "move", "queued": True, "accepted": True,
        }
        with mock.patch.object(
                transaction, "_requirements_cells",
                return_value={"ptx-test": cell}):
            report = transaction.certify(
                native, java, requirements,
                current_java_engine_input_sha256=JAVA_ENGINE_SHA256,
                current_java_program_input_sha256=JAVA_PROGRAM_SHA256)
        self.assertFalse(report["complete"])
        self.assertTrue(report["content_exact"])
        self.assertFalse(report["producer_receipts_verified"])

    def test_retained_store_reopens_both_producers_and_rejects_tampering(self):
        native = transaction.compile_evidence(
            store_evidence("native"), source="trace.txt")
        java_evidence = store_evidence("java")
        requirements, cell = store_requirements()
        java_proof = {
            "schema": transaction.BUILD_RECEIPT_SCHEMA,
            "build_receipt": {
                "schema": transaction.BUILD_RECEIPT_SCHEMA,
                "build_inputs": {
                    "engine": {"engine_input_sha256": JAVA_ENGINE_SHA256},
                    "program_input_sha256": JAVA_PROGRAM_SHA256,
                },
                "jar": {"bytes": 3, "sha256": "c" * 64},
            },
            "pack": {"bytes": 4, "sha256": "d" * 64},
        }

        def emit(_repository, _scenario, _jar, _pack, output, cycles,
                 _engine, _program, _fixture):
            output.write_text(json.dumps(java_evidence), encoding="utf-8")
            cycles.write_text('{"cycle":1}\n', encoding="utf-8")

        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            capture = root / "capture"
            capture.mkdir()
            commands = (b"cycle 10 select unit 7\n"
                        b"cycle 10 ui-right-click x 12 y 13\n")
            manifest = {
                "run": {
                    "requested_scenario": "Campaign\\Human\\Human01.pud",
                    "initialization_seed": 1, "cycle_limit": 40,
                    "commands": {"count": 2,
                                 "file": transaction._bytes_identity(commands)},
                }
            }
            manifest_bytes = json.dumps(manifest).encode("utf-8")
            (capture / "capture.trace.txt").write_text("trace", encoding="utf-8")
            (capture / "capture.manifest.json").write_bytes(manifest_bytes)
            with zipfile.ZipFile(capture / "capture.bnefx", "w") as archive:
                archive.writestr("manifest.json", manifest_bytes)
                archive.writestr("commands.txt", commands)
            pack = root / "pack.chonkpack"
            pack.write_bytes(b"pack")
            jar = root / "app.jar"
            jar.write_bytes(b"jar")
            store = root / "store"
            current = mock.Mock(return_value=(jar, java_proof))
            with mock.patch.object(transaction, "_current_java_proof", current), \
                    mock.patch.object(transaction.bne_fixture, "validate_fixture",
                                      return_value={"fixture_id": SCENARIO_SHA256,
                                                    "sha256": "8" * 64}), \
                    mock.patch.object(transaction, "authority_from_native_manifest",
                                      return_value=authority("native")), \
                    mock.patch.object(transaction, "compile_ui_trace",
                                      return_value=native), \
                    mock.patch.object(transaction, "_requirements_cells",
                                      return_value={cell["id"]: cell}):
                transaction.materialize_proof_store(
                    [capture], store, pack, repository=root,
                    build=False, emitter=emit)
                report = transaction.validate_proof_store(
                    store, requirements, repository=root, pack=pack,
                    emitter=emit)
                self.assertTrue(report["complete"], report)
                self.assertTrue(report["producer_receipts_verified"])
                self.assertEqual(1, report["paired_transactions"])

                java_root = next((store / "java").glob("*/*"))
                (java_root / "cycles.ndjson").write_text(
                    '{"cycle":2}\n', encoding="utf-8")
                with self.assertRaisesRegex(
                        transaction.ProofError, "cycle log"):
                    transaction.validate_proof_store(
                        store, requirements, repository=root, pack=pack,
                        emitter=emit)

    def test_retained_store_rejects_stale_java_build_before_execution(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            store = root / "store"
            store.mkdir()
            current = {
                "schema": transaction.BUILD_RECEIPT_SCHEMA,
                "build_receipt": {"build_inputs": {}},
                "pack": {"bytes": 1, "sha256": "a" * 64},
            }
            (store / "BUILD.json").write_bytes(transaction._json_bytes(current))
            catalog = {
                "schema": transaction.PROOF_STORE_SCHEMA,
                "java_proof_id": "a" * 64,
                "build": transaction._bytes_identity(
                    transaction._json_bytes(current)),
                "native": [], "twins": [],
            }
            catalog["catalog_sha256"] = transaction._digest(catalog)
            (store / "CATALOG.json").write_bytes(transaction._json_bytes(catalog))
            jar = root / "app.jar"
            jar.write_bytes(b"jar")
            stale = {**current, "pack": {"bytes": 2, "sha256": "b" * 64}}
            with mock.patch.object(
                    transaction, "_current_java_proof",
                    return_value=(jar, stale)):
                with self.assertRaisesRegex(
                        transaction.ProofError, "stale for current"):
                    transaction.validate_proof_store(
                        store, {}, repository=root, pack=root / "pack")

    def test_scenario_derivation_rejects_unsupported_native_commands(self):
        native = transaction.compile_evidence(
            store_evidence("native"), source="trace.txt")
        value = {
            "manifest": {"run": {
                "requested_scenario": "Campaign\\Human\\Human01.pud",
                "initialization_seed": 1, "cycle_limit": 40,
                "commands": {"count": 2},
            }},
            "receipt": native,
            "commands_bytes": (b"cycle 10 select unit 7\n"
                               b"cycle 10 attack unit 9\n"),
        }
        with self.assertRaisesRegex(transaction.ProofError, "unsupported"):
            transaction._derive_java_scenario(value)

    def test_scenario_derivation_preserves_a_null_native_ui_target(self):
        native = transaction.compile_evidence(
            store_evidence("native"), source="trace.txt")
        value = {
            "manifest": {"run": {
                "requested_scenario": "Campaign\\Human\\Human01.pud",
                "initialization_seed": 1, "cycle_limit": 40,
                "commands": {"count": 2},
            }},
            "receipt": native,
            "commands_bytes": (b"cycle 10 select unit 7\n"
                               b"cycle 10 ui-right-click x 12 y 13\n"),
        }
        scenario = transaction._derive_java_scenario(value)
        self.assertIn("target_native_id", scenario["gesture"])
        self.assertIsNone(scenario["gesture"]["target_native_id"],
                          "a native null target must not become Java tile lookup")

    def test_scenario_derivation_preserves_a_sealed_native_ui_target(self):
        native = transaction.compile_evidence(
            store_evidence("native"), source="trace.txt")
        item = native["transactions"][0]
        item["gesture"]["tile_x"] = 14
        item["gesture"]["tile_y"] = 15
        item["gesture"]["target_shape"] = "unit"
        item["commands"][0]["target_id"] = 9
        native["unit_identities"]["units"].append({
            "local_id": 9,
            "generation": 0,
            "identity": {
                "origin": "initial", "owner": 0,
                "type": "footman", "x": 14, "y": 15, "ordinal": 0,
            },
        })
        value = {
            "manifest": {"run": {
                "requested_scenario": "Campaign\\Human\\Human01.pud",
                "initialization_seed": 1, "cycle_limit": 40,
                "commands": {"count": 2},
            }},
            "receipt": native,
            "commands_bytes": (b"cycle 10 select unit 7\n"
                               b"cycle 10 ui-right-click x 14 y 15 target 9\n"),
        }
        scenario = transaction._derive_java_scenario(value)
        self.assertEqual(9, scenario["gesture"]["target_native_id"])
        self.assertEqual({
            "native_id": 9, "player": 0, "x": 14, "y": 15,
        }, scenario["target"])

    def test_scenario_derivation_rejects_a_target_without_identity(self):
        native = transaction.compile_evidence(
            store_evidence("native"), source="trace.txt")
        item = native["transactions"][0]
        item["gesture"]["tile_x"] = 14
        item["gesture"]["tile_y"] = 15
        item["commands"][0]["target_id"] = 9
        value = {
            "manifest": {"run": {
                "requested_scenario": "Campaign\\Human\\Human01.pud",
                "initialization_seed": 1, "cycle_limit": 40,
                "commands": {"count": 2},
            }},
            "receipt": native,
            "commands_bytes": (b"cycle 10 select unit 7\n"
                               b"cycle 10 ui-right-click x 14 y 15 target 9\n"),
        }
        with self.assertRaisesRegex(transaction.ProofError,
                                    "omits its native unit identity"):
            transaction._derive_java_scenario(value)

    def test_native_do_right_button_trace_is_one_group_transaction(self):
        compiled = transaction.compile_ui_trace(
            "\n".join([
                "# bne-trace event=ui-right-click cycle=5 x=25 y=28 "
                "ui-player=1 selected=1598,1597",
                "# bne-trace event=ui-fanout cycle=5 unit=1598 "
                "order=3 next-order=60 order-x=25 order-y=28",
                "# bne-trace event=ui-fanout cycle=5 unit=1597 "
                "order=3 next-order=60 order-x=25 order-y=28",
                "# bne-trace event=command-unit-state cycle=80 unit=1598 "
                "sequence=0 sequence-flags=0 animation-timer=0 animation=0 "
                "frame=0 face=0 order=1 next-order=60 order-x=25 order-y=28 "
                "path-head=0",
                "# bne-trace event=command-unit-state cycle=80 unit=1597 "
                "sequence=0 sequence-flags=0 animation-timer=0 animation=0 "
                "frame=0 face=0 order=1 next-order=60 order-x=25 order-y=28 "
                "path-head=0",
            ]),
            source="test-trace")
        self.assertEqual(1, len(compiled["transactions"]))
        item = compiled["transactions"][0]
        self.assertTrue(item["coverage"]["physical_gesture"],
                        "DoRightButton must keep the field click as the gesture")
        self.assertTrue(item["coverage"]["group_fanout"],
                        "two selected footmen must stay one transaction")
        self.assertEqual([1598, 1597],
                         [command["unit_id"] for command in item["commands"]])
        self.assertEqual([(25, 28), (25, 28)],
                         [(command["x"], command["y"]) for command in item["commands"]],
                         "fan-out dests belong on the commands, not only the click")
        self.assertEqual("field", item["gesture"]["origin"])
        self.assertEqual(25, item["gesture"]["tile_x"])
        self.assertEqual(28, item["gesture"]["tile_y"])
        self.assertFalse(item["coverage"]["layers"]["target-interpretation"])
        self.assertFalse(item["coverage"]["layers"]["wire-fanout"])
        self.assertFalse(item["coverage"]["layers"]["acknowledgement"])
        self.assertFalse(item["coverage"]["layers"]["first-progress"])

    def test_layered_trace_proves_every_transaction_layer(self):
        compiled = transaction.compile_ui_trace(
            "\n".join([
                "# bne-trace event=player-gesture transaction=1 intent=1 "
                "cycle=5 origin=field detail=right-click screen-x=320 "
                "screen-y=240 tile-x=25 tile-y=28 modifiers=plain "
                "target-id=none target-shape=open-ground selected=1598,1597",
                "# bne-trace event=player-order transaction=1 intent=2 "
                "ordinal=0 cycle=5 family=move player=1 unit=1598 x=25 y=28 "
                "target-id=none type-index=0 queued=false wire=0319001c00 "
                "accepted=true selected=1598,1597",
                "# bne-trace event=player-order transaction=1 intent=3 "
                "ordinal=1 cycle=5 family=move player=1 unit=1597 x=25 y=28 "
                "target-id=none type-index=0 queued=false wire=0319001c00 "
                "accepted=true selected=1598,1597",
                "# bne-trace event=player-feedback transaction=1 intent=2 "
                "cycle=5 acknowledged=true mode=voice detail=ack",
                "# bne-trace event=player-feedback transaction=1 intent=3 "
                "cycle=5 acknowledged=true mode=silent detail=group-suppressed",
                "# bne-trace event=player-outcome transaction=1 intent=2 "
                "submitted-cycle=5 unit=1598 family=move accepted=true "
                "first-progress-cycle=9 terminal-cycle=45 terminal-reason=settled "
                "tile-x=25 tile-y=28 offset-x=0 offset-y=0 order=STILL "
                "target-id=none hit-points=60 carried=0 alive=true on-map=true "
                "missile-count=0",
                "# bne-trace event=player-outcome transaction=1 intent=3 "
                "submitted-cycle=5 unit=1597 family=move accepted=true "
                "first-progress-cycle=10 terminal-cycle=47 terminal-reason=settled "
                "tile-x=25 tile-y=28 offset-x=0 offset-y=0 order=STILL "
                "target-id=none hit-points=60 carried=0 alive=true on-map=true "
                "missile-count=0",
            ]),
            source="layered", authority={
                "side": "native", "producer": "test",
                "authenticated": True,
            })
        item = compiled["transactions"][0]
        self.assertTrue(all(item["coverage"]["layers"].values()))
        self.assertEqual([0, 1], [
            command["fanout_ordinal"] for command in item["commands"]])
        self.assertEqual(["voice", "silent"], [
            command["feedback"]["mode"] for command in item["commands"]])

    def test_tracer_hooks_public_give_order_for_layered_field_move(self):
        source = (Path(__file__).parents[1] / "src" / "tracer.c").read_text(
            encoding="utf-8")
        layout = (Path(__file__).parents[1] / "src" /
                  "bne_202_layout.h").read_text(encoding="utf-8")
        self.assertIn("traced_give_order", source,
                      "DoRightButton must be observed at public GiveOrder")
        self.assertIn("install_give_order_hook", source,
                      "the 0x00451070 trampoline has to be installed")
        self.assertIn("event=player-gesture", source)
        self.assertIn("event=player-order", source)
        self.assertIn("event=player-feedback", source)
        self.assertIn("event=player-outcome", source)
        self.assertIn("0x13", source)
        self.assertIn("group-suppressed", source,
                      "a group keeps one voice; the rest stay silent")
        self.assertIn("BNE_UNIT_PIXEL_X", source)
        self.assertIn("BNE_SQUARE_FOREST", source)
        self.assertIn("BNE_SQUARE_COAST", source)
        self.assertIn("return \"shore\"", source)
        self.assertIn("BNE_SQUARE_WATER", source)
        self.assertIn("return \"water\"", source)
        self.assertIn("#define BNE_SQUARE_COAST 0x0002", layout)
        self.assertIn("#define BNE_SQUARE_WATER 0x0040", layout)

    def test_reconstructed_0x13_field_move_compiles_all_eight_layers(self):
        compiled = transaction.compile_ui_trace(
            "\n".join([
                "# bne-trace event=player-gesture transaction=1 intent=1 "
                "cycle=5 origin=field detail=right-click screen-x=none "
                "screen-y=none tile-x=25 tile-y=28 modifiers=plain "
                "target-id=none target-shape=open-ground selected=1598",
                "# bne-trace event=player-order transaction=1 intent=2 "
                "ordinal=0 cycle=5 family=move player=1 unit=1598 x=25 y=28 "
                "target-id=none type-index=0 queued=false "
                "wire=1319001c00ffff03 accepted=true selected=1598",
                "# bne-trace event=player-feedback transaction=1 intent=2 "
                "cycle=5 acknowledged=true mode=voice detail=ack",
                "# bne-trace event=player-outcome transaction=1 intent=2 "
                "submitted-cycle=5 unit=1598 family=move accepted=true "
                "first-progress-cycle=9 terminal-cycle=45 "
                "terminal-reason=settled tile-x=25 tile-y=28 offset-x=0 "
                "offset-y=0 order=STILL target-id=none hit-points=60 "
                "carried=0 alive=true on-map=true missile-count=0",
            ]),
            source="native-0x13-field-move")
        item = compiled["transactions"][0]
        self.assertTrue(all(item["coverage"]["layers"].values()),
                        "an observed field/plain/move 0x13 packet must prove "
                        "target, wire, acknowledgement, progress and terminal")
        self.assertEqual("1319001c00ffff03", item["commands"][0]["wire_hex"])
        self.assertEqual("open-ground", item["gesture"]["target_shape"])
        self.assertEqual("voice", item["commands"][0]["feedback"]["mode"])
        self.assertEqual(9, item["outcomes"][0]["first_progress_cycle"])
        self.assertEqual("settled", item["outcomes"][0]["terminal_reason"])

    def test_native_unit_identity_event_closes_the_lifecycle(self):
        compiled = transaction.compile_ui_trace(
            "\n".join([
                "# bne-trace event=player-gesture transaction=1 intent=1 "
                "cycle=5 origin=field detail=right-click screen-x=none "
                "screen-y=none tile-x=25 tile-y=28 modifiers=plain "
                "target-id=none target-shape=open-ground selected=1598",
                "# bne-trace event=player-unit-identity local-id=1598 "
                "generation=0 origin=initial owner=1 type=unit-footman "
                "x=21 y=5 ordinal=0",
                "# bne-trace event=player-order transaction=1 intent=2 "
                "ordinal=0 cycle=5 family=move player=1 unit=1598 x=25 y=28 "
                "target-id=none type-index=0 queued=false "
                "wire=1319001c00ffff03 accepted=true selected=1598",
                "# bne-trace event=player-feedback transaction=1 intent=2 "
                "cycle=5 acknowledged=true mode=voice detail=ack",
                "# bne-trace event=player-outcome transaction=1 intent=2 "
                "submitted-cycle=5 unit=1598 family=move accepted=true "
                "first-progress-cycle=10 terminal-cycle=45 "
                "terminal-reason=settled tile-x=25 tile-y=28 offset-x=0 "
                "offset-y=0 order=STILL target-id=none hit-points=60 "
                "carried=0 alive=true on-map=true missile-count=0",
            ]),
            source="native-identity")
        identities = compiled["unit_identities"]["units"]
        self.assertEqual(1, len(identities),
                         "GiveOrder must name the selected footman's lifetime")
        self.assertEqual(1598, identities[0]["local_id"])
        self.assertEqual("unit-footman", identities[0]["identity"]["type"])
        canonical = transaction.canonical_transaction(
            compiled["transactions"][0],
            transaction._unit_identity_index(compiled),
            require_stable=True)
        self.assertEqual(identities[0]["stable_sha256"],
                         canonical["commands"][0]["unit_identity"])

    def test_native_manifest_authenticates_exact_trace_bytes(self):
        payload = b"# trace\n"
        manifest, validation = native_manifest(payload)
        authority = transaction.authority_from_native_manifest(
            manifest, payload, manifest_source="test",
            fixture_validation=validation)
        self.assertTrue(authority["authenticated"])
        with self.assertRaisesRegex(ValueError, "does not match"):
            transaction.authority_from_native_manifest(
                manifest, payload + b"x", manifest_source="test",
                fixture_validation=validation)

    def test_requirements_are_an_explicit_stable_physical_cell_matrix(self):
        requirements = production_requirements()
        cells = transaction._requirements_cells(requirements)
        self.assertEqual(requirements["fixed_cell_count"], len(cells))
        self.assertGreaterEqual(len(cells), 240)
        self.assertIn("build", requirements["families"])
        self.assertIn("dismiss", requirements["families"])
        self.assertIn("wall", requirements["target_shapes"])
        self.assertEqual(list(cells), list(
            transaction._requirements_cells(requirements)))
        self.assertNotIn("keyboard", requirements["origins"])
        self.assertTrue(all(
            item["queued"] == ("shift" in item["modifiers"].split("+"))
            for item in cells.values()
            if item["origin"] in {"field", "minimap"}))
        changed = dict(requirements)
        changed["cells"] = requirements["cells"][:-1]
        with self.assertRaisesRegex(ValueError, "explicit >=240"):
            transaction._requirements_cells(changed)

    def test_every_fixed_cell_is_constructible_by_a_physical_ui_route(self):
        requirements = production_requirements()
        cells = transaction._requirements_cells(requirements)
        declared = transaction._declared_route_cells(requirements)
        self.assertEqual({
            tuple(item[field] for field in transaction.CELL_FIELDS)
            for item in cells.values()
        }, declared)

        keyboard = json.loads(json.dumps(requirements))
        keyboard["route_capabilities"][0]["origins"].append("keyboard")
        with self.assertRaisesRegex(ValueError, "uninstrumented origin"):
            transaction._requirements_cells(keyboard)

        minimap_research = json.loads(json.dumps(requirements))
        variant = minimap_research["route_capabilities"][0]["variants"][0]
        variant["family"] = "research"
        with self.assertRaisesRegex(ValueError, "right-click cannot emit"):
            transaction._requirements_cells(minimap_research)

        shift_immediate = json.loads(json.dumps(requirements))
        offset = next(index for index, value in enumerate(shift_immediate["cells"])
                      if "|shift|" in value and "|queued|" in value)
        shift_immediate["cells"][offset] = shift_immediate["cells"][offset].replace(
            "|queued|", "|immediate|")
        with self.assertRaisesRegex(ValueError, "physical route declarations"):
            transaction._requirements_cells(shift_immediate)

    def test_missing_cells_emit_real_recipe_or_exact_hook_debt(self):
        raw = evidence(second_unit=False)
        raw["player_intents"][1]["command"]["wire_hex"] = ""
        compiled = transaction.compile_evidence(
            raw, source="test")
        requirements = production_requirements()
        report = transaction.coverage([compiled], requirements)
        recipes = report["capture_plan"]["recipes"]
        control = next(item for item in recipes
                       if item["cell"] == {
                           "dimension": "modifiers", "value": "control"})
        self.assertEqual("blocked-on-hook", control["status"])
        self.assertIn("modifier", control["hook_debt"])
        self.assertIsNone(control["native_command"])
        attack = next(item for item in recipes
                      if item["cell"] == {
                          "dimension": "families", "value": "attack"})
        self.assertIn("GiveOrder injection is not", attack["hook_debt"])
        self.assertTrue(any(item["layer"] == "wire-fanout"
                            for item in report["capture_plan"]["layer_hook_debt"]))
        fixed = report["capture_plan"]["fixed_cell_debt"]
        self.assertEqual(requirements["fixed_cell_count"], len(fixed))
        self.assertEqual(
            "keyboard",
            report["capture_plan"]["required_route_hook_debt"][0]["origin"])
        legacy = next(item for item in fixed
                      if item["cell"]["origin"] == "field"
                      and item["cell"]["modifiers"] == "plain"
                      and item["cell"]["family"] == "move")
        self.assertEqual("partial-executable", legacy["status"])
        self.assertIsNotNone(legacy["native_command"])
        blocked = next(item for item in fixed
                       if item["cell"]["origin"] == "minimap")
        self.assertEqual("blocked-on-hook", blocked["status"])
        self.assertTrue(any("minimap" in debt for debt in blocked["hook_debt"]))

    def test_required_route_debt_clears_only_when_the_hooks_are_observed(self):
        requirements = production_requirements()
        empty = transaction.coverage(
            [transaction.compile_evidence(evidence(second_unit=False), source="java")],
            requirements)
        self.assertEqual(
            ["keyboard-command-hotkeys", "production-prewire-refusal-decision"],
            [item["id"] for item in empty["capture_plan"]["required_route_hook_debt"]],
            "invented coverage must not drop the required keyboard or pre-wire hooks")
        self.assertEqual(532, empty["fixed_cell_count"])

        keyboard = evidence(second_unit=False)
        keyboard["player_intents"][0]["gesture"]["origin"] = "keyboard"
        keyboard["player_intents"][0]["gesture"]["detail"] = "hotkey-s"
        keyboard_report = transaction.coverage(
            [transaction.compile_evidence(keyboard, source="java")], requirements)
        self.assertEqual(
            ["production-prewire-refusal-decision"],
            [item["id"] for item in
             keyboard_report["capture_plan"]["required_route_hook_debt"]],
            "a keyboard-origin gesture is the hotkey hook, not the pre-wire refusal")

        refusal = {
            "authority": authority("java"),
            "map_path": "maps/test.pud",
            "campaign": None,
            "mission": 0,
            "player_intents": [{
                "intent_id": 1,
                "transaction_id": 1,
                "cycle": 10,
                "event": "gesture",
                "selected_unit_ids": [7],
                "gesture": {
                    "origin": "command-panel",
                    "detail": "train-unit",
                    "screen_x": 600,
                    "screen_y": 400,
                    "tile_x": -1,
                    "tile_y": -1,
                    "modifiers": "plain",
                    "target_id": 7,
                    "target_shape": "building",
                },
            }],
            "player_decisions": [{
                "transaction_id": 1,
                "accepted": False,
                "family": "train",
                "queued": False,
                "reason": "Not enough gold...mine more gold.",
                "cycle": 10,
            }],
            "player_feedback": [{
                "intent_id": 1,
                "transaction_id": 1,
                "cycle": 10,
                "acknowledged": True,
                "mode": "voice",
                "detail": "Not enough gold...mine more gold.",
            }],
            "player_outcomes": [],
            "unit_identities": {
                "schema": transaction.UNIT_IDENTITY_SCHEMA,
                "units": [{
                    "local_id": 7,
                    "generation": 0,
                    "identity": {
                        "origin": "initial", "owner": 0,
                        "type": "town-hall", "x": 10,
                        "y": 11, "ordinal": 0,
                    },
                }],
            },
        }
        both = evidence(second_unit=False)
        both["player_intents"][0]["gesture"]["origin"] = "keyboard"
        both_receipts = [
            transaction.compile_evidence(both, source="java"),
            transaction.compile_evidence(refusal, source="java"),
        ]
        closed = transaction.coverage(both_receipts, requirements)
        self.assertEqual(
            [],
            closed["capture_plan"]["required_route_hook_debt"],
            "observed keyboard dispatch and a pre-wire production refusal must "
            "retire those two hook debts without shrinking the 532-cell matrix")
        self.assertEqual(532, closed["fixed_cell_count"])

    def test_native_capture_import_is_content_addressed(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        capture = Path(temporary.name) / "capture"
        output = Path(temporary.name) / "receipts"
        capture.mkdir()
        trace = "\n".join([
            "# bne-trace event=ui-right-click cycle=5 x=25 y=28 "
            "ui-player=1 selected=1598",
            "# bne-trace event=ui-fanout cycle=5 unit=1598 order=2 "
            "next-order=3 order-x=25 order-y=28",
            "# bne-trace event=command-unit-state cycle=80 unit=1598 "
            "sequence=0 sequence-flags=0 animation-timer=0 animation=0 "
            "frame=0 face=0 order=1 next-order=60 order-x=25 order-y=28 "
            "path-head=0",
        ]) + "\n"
        trace_path = capture / "capture.trace.txt"
        trace_path.write_text(trace, encoding="utf-8")
        manifest, validation = native_manifest(trace.encode())
        manifest_bytes = json.dumps(manifest).encode()
        (capture / "capture.manifest.json").write_bytes(manifest_bytes)
        with zipfile.ZipFile(capture / "capture.bnefx", "w") as archive:
            archive.writestr("manifest.json", manifest_bytes)
        with mock.patch.object(
                transaction.bne_fixture, "validate_fixture",
                return_value=validation):
            first = transaction.import_native_captures([capture], output)
            second = transaction.import_native_captures([capture], output)
        self.assertEqual(first["catalog_sha256"], second["catalog_sha256"])
        self.assertEqual(1, first["captures"])
        receipt = output / first["entries"][0]["receipt"]
        self.assertTrue(receipt.is_file())
        self.assertEqual(receipt.parent.name,
                         first["entries"][0]["receipt_sha256"])


if __name__ == "__main__":
    unittest.main()
