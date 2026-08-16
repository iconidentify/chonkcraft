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
                "target_id": 0,
                "type_index": 0,
                "queued": True,
                "wire_hex": "0100",
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
