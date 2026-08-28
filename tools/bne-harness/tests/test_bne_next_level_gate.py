from pathlib import Path
import argparse
import sys
import tempfile
import unittest
from unittest import mock


SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_next_level_gate as gate


class NextLevelGateTest(unittest.TestCase):

    def test_shell_is_cwd_independent_and_never_reuses_stale_discovery(self):
        shell = (Path(__file__).parents[3] / "scripts" /
                 "check-bne-next-level-gate.sh").read_text(encoding="utf-8")
        self.assertLess(shell.index('cd "$ROOT"'), shell.index('test -f "$PACK"'))
        self.assertIn("AI_DISCOVERY_CURRENT=0", shell)
        self.assertIn('if [[ "$AI_DISCOVERY_CURRENT" == "1" ]]', shell)
        self.assertNotIn('if [[ -f "$AI_DISCOVERY" ]]', shell)
        self.assertIn('BNE_PLAYER_PROOF_STORE', shell)
        self.assertIn('--player-proof-store "$BNE_PLAYER_PROOF_STORE"', shell)
        self.assertIn("unknown next-level gate argument", shell)

    def test_ai_capture_uses_the_maps_person_slot(self):
        capture = (Path(__file__).parents[3] / "scripts" /
                   "capture-bne-ai-cycle.sh").read_text(encoding="utf-8")
        self.assertIn("BneAiDecisionAdapter", capture)
        self.assertNotIn("--player 0", capture)

    def test_next_work_names_all_open_lanes(self):
        player = {
            "complete": False,
            "resolved_command_matrix": {"complete": False, "exact_parity": 203,
                                         "comparable": 206},
            "physical_transactions": {"complete": False,
                                      "paired_transactions": 0},
            "replay_twin": {"complete": False, "exact_records": 0,
                            "required_records": 764756},
        }
        ai = {
            "complete": False,
            "fleet": {
                "complete": False, "materialized_scenarios": 52,
                "available_native_captures": 52,
                "certification": {
                    "state_exact_scenarios": 45,
                    "telemetry_exact_scenarios": 45,
                },
            },
            "combat_lifecycle": {"complete": False, "exact": 0,
                                 "required": 185},
        }
        campaign = {"complete": False,
                    "coverage": {"complete": False, "exact": 0,
                                 "required": 137}}
        work = gate.next_work(player, ai, campaign)
        self.assertEqual(6, len(work["queue"]))
        self.assertEqual("player-transactions", work["current"]["lane"])
        self.assertTrue(any(item["lane"] == "player-transactions"
                            for item in work["queue"]))
        self.assertTrue(any(item["lane"] == "ai-fleet"
                            for item in work["queue"]))
        ai_work = next(item for item in work["queue"]
                       if item["lane"] == "ai-fleet")
        self.assertIn("52/52 mission AI twins materialized", ai_work["reason"])
        self.assertIn("45/52 state+telemetry exact", ai_work["reason"])
        self.assertTrue(any(item["lane"] == "campaign-lifecycle"
                            for item in work["queue"]))

    def test_command_report_cannot_shrink_the_240_cell_denominator(self):
        report = {
            "schema": gate.COMMAND_SCHEMA, "complete": True,
            "generated": 1, "executed_native": 1, "executed_java": 1,
            "comparable": 1, "exact_parity": 1,
            "materially_divergent": 0, "infrastructure_failure": 0,
        }
        self.assertFalse(gate._command_lane(report)["complete"])

    def test_command_report_requires_an_explicit_identity_join(self):
        report = {
            "schema": gate.COMMAND_SCHEMA, "complete": True,
            "identity_bound": False,
            "generated": 240, "executed_native": 240,
            "executed_java": 240, "comparable": 240,
            "exact_parity": 240, "materially_divergent": 0,
            "infrastructure_failure": 0, "unmatched_executed": 0,
            "missing_cells": 0,
        }
        self.assertFalse(gate._command_lane(report)["complete"])
        report["identity_bound"] = True
        self.assertFalse(gate._command_lane(report)["complete"])
        self.assertTrue(gate._command_lane(
            report, producer_evidence_verified=True)["complete"])

    def test_physical_manifest_override_cannot_replace_canonical_532_cells(self):
        root = Path(__file__).parents[3]
        canonical = (root / "tools/bne-harness" /
                     "player-transaction-requirements.json")
        requirements = gate._canonical_player_requirements(root, canonical)
        self.assertEqual(532, requirements["fixed_cell_count"])
        with self.assertRaisesRegex(ValueError, "canonical checked-in"):
            gate._canonical_player_requirements(
                root, Path("/tmp/not-the-canonical-manifest.json"))

    def test_ai_fleet_requires_all_52_and_exact_telemetry(self):
        run = {"scenario": "map", "comparison": {"identical": True}}
        base = {
            "schema": gate.conductor.REPORT_SCHEMA,
            "authority_sha256": gate.conductor.PINNED,
            "engine_identity": {"engine_input_sha256": "e" * 64},
            "summary": {"denominator": 1, "state_exact": 1,
                        "telemetry_exact": 1},
            "fleet": {"required": 52, "existing": 52, "missing": 0,
                      "materialized": 1},
            "certification": {"complete": False},
            "runs": [run], "next": [],
        }
        self.assertFalse(gate._ai_conductor_coverage(
            base, "e" * 64)["complete"])
        full = dict(base)
        full["runs"] = [
            {"scenario": f"map-{index}",
             "comparison": {"identical": True}}
            for index in range(52)]
        full["summary"] = {"denominator": 52, "state_exact": 52,
                           "telemetry_exact": 51}
        full["fleet"] = {"required": 52, "existing": 52, "missing": 0,
                         "materialized": 52}
        full["certification"] = {"complete": True}
        self.assertFalse(gate._ai_conductor_coverage(
            full, "e" * 64)["complete"])
        full["summary"]["telemetry_exact"] = 52
        # A detached green-looking summary is never evidence.  Only the
        # conductor's retained proof-graph validator may admit it.
        self.assertFalse(gate._ai_conductor_coverage(
            full, "e" * 64)["complete"])
        with tempfile.TemporaryDirectory() as raw:
            report = Path(raw) / "NEXT.json"
            report.write_text("{}", encoding="utf-8")
            with mock.patch.object(
                    gate.conductor, "validate_retained_report",
                    return_value=full) as validate:
                certified = gate._ai_conductor_coverage(
                    full, "e" * 64, report_path=report,
                    repository=Path(raw), pack=report)
        self.assertTrue(certified["complete"])
        validate.assert_called_once()

    def test_tampered_ai_store_is_rejected_not_rounded_down(self):
        document = {
            "schema": gate.conductor.REPORT_SCHEMA,
            "authority_sha256": gate.conductor.PINNED,
        }
        with tempfile.TemporaryDirectory() as raw:
            report = Path(raw) / "NEXT.json"
            report.write_text("{}", encoding="utf-8")
            with mock.patch.object(
                    gate.conductor, "validate_retained_report",
                    side_effect=gate.conductor.EvidenceError("tampered")):
                with self.assertRaisesRegex(ValueError, "failed validation"):
                    gate._ai_conductor_coverage(
                        document, "e" * 64, report_path=report,
                        repository=Path(raw), pack=report)

    def test_player_certification_without_current_engine_binding_is_red(self):
        document = {"schema": gate.player.CERTIFICATION_SCHEMA,
                    "complete": True, "paired_transactions": 240}
        report = gate._player_certification(document, "e" * 64, "p" * 64)
        self.assertFalse(report["complete"])
        self.assertFalse(report["current_engine"])

    def test_detached_green_summaries_cannot_certify(self):
        engine = "e" * 64
        program = "p" * 64
        player_document = {
            "schema": gate.player.CERTIFICATION_SCHEMA,
            "complete": True,
            "paired_transactions": 240,
            "authority": {
                "java_engine_input_sha256": engine,
                "java_program_input_sha256": program,
            },
        }
        replay_document = {
            "schema": gate.replay.CORPUS_CERTIFICATION_SCHEMA,
            "complete": True,
            "exact_records": 764756,
            "authority": {
                "java_engine_input_sha256": engine,
                "java_program_input_sha256": program,
            },
        }
        physical = gate._player_certification(
            player_document, engine, program)
        replay = gate._replay_certification(
            replay_document, engine, program)
        self.assertFalse(physical["complete"])
        self.assertFalse(physical["producer_receipts_verified"])
        self.assertFalse(replay["complete"])
        self.assertFalse(replay["producer_reports_verified"])

    def test_retained_player_store_is_validated_not_trusted_as_a_summary(self):
        validated = {
            "schema": gate.player.CERTIFICATION_SCHEMA,
            "complete": False, "paired_transactions": 1,
            "producer_receipts_verified": True,
        }
        with tempfile.TemporaryDirectory() as raw, mock.patch.object(
                gate.player, "validate_proof_store",
                return_value=validated) as verify:
            store = Path(raw) / "store"
            result = gate._retained_player_certification(
                store, {"fixed_cell_count": 532},
                repository=Path(raw), pack=Path(raw) / "pack")
        self.assertTrue(result["producer_receipts_verified"])
        self.assertTrue(result["current_engine"])
        verify.assert_called_once()

    def test_tampered_player_store_is_rejected_not_rounded_down(self):
        with tempfile.TemporaryDirectory() as raw, mock.patch.object(
                gate.player, "validate_proof_store",
                side_effect=gate.player.ProofError("tampered")):
            with self.assertRaisesRegex(ValueError, "failed validation"):
                gate._retained_player_certification(
                    Path(raw) / "store", {}, repository=Path(raw),
                    pack=Path(raw) / "pack")

    def test_loose_work_order_can_never_authorize_an_engine_edit(self):
        document = {
            "schema": gate.WORK_ORDER_SCHEMA, "state": "ready",
            "request_sha256": "a" * 64, "mismatch": {},
            "acceptance": {"engine_edit_allowed": True},
        }
        result = gate._resolved_work_order(None, document)
        self.assertEqual("legacy-unverified", result["state"])
        self.assertFalse(result["engine_edit_allowed"])
        self.assertFalse(result["authenticated_pointer"])


if __name__ == "__main__":
    unittest.main()
