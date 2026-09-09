from copy import deepcopy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))
import bne_command_campaign as campaign


def recipe():
    return {
        "id": "two-footmen", "group": "controls", "horizon": 4,
        "settle_cycles": 2, "observed_units": [10, 11],
        "commands": [
            {"kind": "move", "unit_id": 10, "issue_cycle": 2},
            {"kind": "move", "unit_id": 11, "issue_cycle": 2},
        ],
        "native": {"sha256": "a" * 64},
    }


def scenario(case):
    value = {"schema": campaign.explorer.SCENARIO_SCHEMA, "commands": case["commands"]}
    value["scenario_sha256"] = campaign.explorer.digest(value)
    return value


def result(case, side):
    return {
        "schema": campaign.explorer.RESULT_SCHEMA, "side": side,
        "scenario_sha256": scenario(case)["scenario_sha256"],
        "producer": {"name": "test adapter", "build_sha256": "b" * 64,
            "authority_sha256": campaign.native.PINNED_BNE_EXECUTABLE_SHA256,
            "fixture_sha256": case["native"]["sha256"]},
        "observations": [{"command_index": index, "accepted": True} for index in range(2)],
        "events": [{"kind": "combat-state", "cycle": cycle, "unit_id": unit,
            "alive": True, "on_map": True, "x": cycle, "y": unit,
            "offset_x": cycle * 32, "offset_y": unit * 32, "hit_points": 60,
            "sequence": 3, "order": "MOVE", "animation_timer": 1, "animation_state": "move"}
            for cycle in range(1, 5) for unit in case["observed_units"]],
    }


class CommandCampaignTest(unittest.TestCase):
    def setUp(self):
        self.case = recipe()
        self.native = result(self.case, "native")
        self.java = result(self.case, "java")

    def compare(self):
        return campaign.compare_case(self.case, scenario(self.case), self.native, self.java)

    def gate(self, baseline, current):
        return campaign.regression_gate({"cases": [self.case]}, {"cases": [baseline]}, [current])

    def test_the_fixed_campaign_retains_all_maps_commands_and_observers(self):
        definition = campaign.load_definition()
        baseline = campaign.load_baseline(campaign.BASELINE, definition)
        summary = campaign.summarize(definition, baseline["cases"])
        self.assertEqual(121, len(definition["cases"]), "the original matrices and eligibility cases must remain")
        self.assertEqual((52, 1251), (summary["campaign"]["cases"], summary["campaign"]["commands"]))
        self.assertEqual((65, 111), (summary["human1"]["cases"], summary["human1"]["commands"]))
        self.assertEqual((4, 12), (summary["eligibility"]["cases"], summary["eligibility"]["commands"]))
        self.assertEqual(7202, sum(len(c["observed_units"]) for c in definition["cases"]))
        for actual, minimum in zip((summary["campaign"]["actor_exact_through_100"],
                summary["campaign"]["actor_exact_full"], summary["human1"]["actor_exact_full"]),
                (43, 3, 59), strict=True):
            self.assertGreaterEqual(actual, minimum, "reviewed improvements must preserve the release floor")

    def test_same_missing_cycle_in_both_engines_cannot_pass(self):
        for output in [self.native, self.java]:
            output["events"] = [e for e in output["events"] if e["cycle"] != 3]
        with self.assertRaisesRegex(ValueError, "omit a unit or cycle"):
            self.compare()

    def test_duplicate_cannot_replace_a_missing_observation(self):
        self.java["events"][-1] = deepcopy(self.java["events"][0])
        with self.assertRaisesRegex(ValueError, "duplicate physical"):
            self.compare()

    def test_empty_or_missing_unit_observations_cannot_pass(self):
        for events in ([], [e for e in self.java["events"] if e["unit_id"] == 10]):
            with self.subTest(events=len(events)):
                self.java["events"] = events
                with self.assertRaisesRegex(ValueError, "omit a unit or cycle"):
                    self.compare()

    def test_missing_fields_in_both_engines_are_not_equal_observations(self):
        for output in [self.native, self.java]:
            del output["events"][0]["hit_points"]
        with self.assertRaisesRegex(ValueError, "typed field hit_points"):
            self.compare()

    def test_extra_cycles_do_not_replace_missing_requested_cycles(self):
        self.java["events"][0]["cycle"] = 5
        with self.assertRaisesRegex(ValueError, "outside the requested"):
            self.compare()

    def test_the_wrong_native_capture_cannot_supply_a_case(self):
        self.native["producer"]["fixture_sha256"] = "c" * 64
        with self.assertRaisesRegex(ValueError, "different captured fixture"):
            self.compare()

    def test_each_units_prefix_is_preserved_even_when_the_map_minimum_is_unchanged(self):
        baseline = self.compare()
        baseline["unit_frontiers"]["10"][2] = 1
        current = deepcopy(baseline)
        current["unit_frontiers"]["11"][3] = 2
        gate = self.gate(baseline, current)
        self.assertFalse(gate["passed"], "one footman's old divergence cannot hide the other regressing")
        self.assertEqual(11, gate["regressions"][0]["unit"])

    def test_later_physical_divergence_is_an_improvement(self):
        baseline = self.compare()
        baseline["unit_frontiers"]["10"][2] = 1
        gate = self.gate(baseline, self.compare())
        self.assertTrue(gate["passed"])
        self.assertEqual(1, gate["improved_frontiers_or_acceptances"])

    def test_raw_sequence_difference_remains_visible_without_claiming_physical_regression(self):
        baseline = self.compare()
        self.java["events"][0]["sequence"] = 90
        current = self.compare()
        self.assertEqual(1, current["diagnostic_first"]["sequence"]["cycle"])
        self.assertTrue(self.gate(baseline, current)["passed"])

    def test_acceptance_totals_cannot_hide_a_different_rejected_command(self):
        baseline = self.compare()
        baseline["java_acceptance"] = [False, True]
        current = deepcopy(baseline)
        current["java_acceptance"] = [True, False]
        gate = self.gate(baseline, current)
        self.assertFalse(gate["passed"])
        self.assertEqual(1, gate["regressions"][0]["command"])

    def test_matching_a_previously_mishandled_refusal_is_allowed(self):
        baseline = self.compare()
        baseline["native_acceptance"] = [False, True]
        current = deepcopy(baseline)
        current["java_acceptance"] = [False, True]
        self.assertTrue(self.gate(baseline, current)["passed"])

    def test_failed_shortened_missing_or_substituted_cases_fail_closed(self):
        original = self.compare()
        mutations = [dict(original, status="infrastructure-failure"), dict(original, horizon=3),
                     dict(original, id="different-map"), dict(original, unit_frontiers={})]
        for row in mutations:
            with self.subTest(row=row):
                with self.assertRaises(ValueError):
                    self.gate(original, row)
        with self.assertRaises(ValueError):
            campaign.regression_gate({"cases": [self.case]}, {"cases": [original]}, [])

    def test_the_repository_command_returns_failure_when_private_captures_are_missing(self):
        with tempfile.TemporaryDirectory() as directory:
            process = subprocess.run([sys.executable, str(SCRIPTS / "bne_command_campaign.py"),
                "--store", directory, "verify"], capture_output=True, text=True)
        self.assertEqual(1, process.returncode)
        self.assertIn("missing or changed native capture", process.stderr)

    def test_a_changed_campaign_cannot_reuse_the_previous_baseline(self):
        definition = campaign.load_definition()
        definition["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "different campaign"):
            campaign.load_baseline(campaign.BASELINE, definition)

    def test_an_import_cannot_leave_a_corrupted_existing_capture_unnoticed(self):
        with tempfile.TemporaryDirectory() as directory:
            store = Path(directory)
            path = campaign.fixture_path(store, self.case)
            path.parent.mkdir()
            path.write_bytes(b"corrupt existing capture")
            args = SimpleNamespace(store=store, ledger=[], directory=[])
            with self.assertRaisesRegex(ValueError, "missing or changed native capture"):
                campaign.import_fixtures(args, {"cases": [self.case]})

    def test_capture_recipes_round_trip_through_the_existing_native_corpus_runner(self):
        definition = campaign.load_definition()
        with tempfile.TemporaryDirectory() as directory:
            path = campaign.capture_plan(definition, Path(directory) / "capture")
            _raw, cases = campaign.bne_corpus.load_plan(path)
            self.assertEqual(121, len(cases))
            for recipe, captured in zip(definition["cases"], cases, strict=True):
                self.assertEqual(recipe["horizon"], captured["cycles"], "the last command gets its full tail")
                script = (path.parent / f"commands/{recipe['id']}.txt").read_text()
                decoded = campaign.explorer.parse_injector_script(script)
                self.assertEqual([campaign.native.command_key(c) for c in recipe["commands"]],
                    [campaign.native.command_key(c) for c in decoded], "capture must preserve every command byte field")

    def test_a_unit_that_is_both_an_actor_and_a_target_is_observed_once(self):
        seed = {"identity": {"fixture": "shared-target"}, "setup": {},
                "actors": [{"id": 10}, {"id": 11}], "targets": [{"id": 11}]}
        self.case["pattern"] = "replacement"
        with patch.object(campaign.explorer, "seed_from_fixture", return_value=seed), \
                patch.object(campaign.explorer, "enrich_seed_families", return_value=seed):
            result = campaign.materialize_scenario(self.case, Path("unused.bnefx"))
        self.assertEqual([10, 11], result["combat_observation"]["unit_ids"],
                         "a friendly target is still the same native pool unit")

    def test_baseline_promotion_requires_the_current_program(self):
        definition = campaign.load_definition()
        report = {"schema": campaign.RESULT_SCHEMA, "campaign_sha256": definition["sha256"],
                  "fields": list(campaign.FIELDS), "asset_pack_sha256": definition["asset_pack_sha256"],
                  "build": {"build_inputs": {"source": "old"}}}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            campaign.write_json(root / "report.json", report)
            args = SimpleNamespace(report=root / "report.json", output=root / "candidate.json")
            with patch.object(campaign.transaction, "_java_build_inputs", return_value={"source": "current"}):
                with self.assertRaisesRegex(ValueError, "run is stale"):
                    campaign.baseline_candidate(args, definition, {})
            self.assertFalse(args.output.exists())

    def test_baseline_promotion_reopens_the_observations_instead_of_trusting_the_report(self):
        definition = {"sha256": "a" * 64, "asset_pack_sha256": "b" * 64, "cases": [self.case]}
        row = self.compare()
        row["artifacts"] = {"scenario.json": "c" * 64}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "game.jar").write_bytes(b"test packaged game")
            campaign.write_json(root / self.case["id"] / "scenario.json", {"tampered": True})
            report = {"schema": campaign.RESULT_SCHEMA, "campaign_sha256": definition["sha256"],
                      "fields": list(campaign.FIELDS), "asset_pack_sha256": definition["asset_pack_sha256"],
                      "passed": True, "cases": [row],
                      "build": {"build_inputs": {"source": "current"},
                                "jar": {"sha256": campaign.file_sha256(root / "game.jar")}}}
            campaign.write_json(root / "report.json", report)
            args = SimpleNamespace(report=root / "report.json", output=root / "candidate.json")
            with patch.object(campaign.transaction, "_java_build_inputs", return_value={"source": "current"}):
                with self.assertRaisesRegex(ValueError, "retained scenario.json changed"):
                    campaign.baseline_candidate(args, definition, {})
            self.assertFalse(args.output.exists())


if __name__ == "__main__":
    unittest.main()
