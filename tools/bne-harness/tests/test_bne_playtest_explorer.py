import copy
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest


SCRIPTS = Path(__file__).parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))
SPEC = importlib.util.spec_from_file_location(
    "bne_playtest_explorer", SCRIPTS / "bne_playtest_explorer.py")
explorer = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(explorer)
ADAPTER = Path(__file__).with_name("synthetic_playtest_adapter.py")
MATRIX_TEST = Path(__file__).with_name("test_bne_command_matrix.py")
MATRIX_SPEC = importlib.util.spec_from_file_location(
    "command_matrix_test", MATRIX_TEST)
matrix_test = importlib.util.module_from_spec(MATRIX_SPEC)
assert MATRIX_SPEC.loader is not None
MATRIX_SPEC.loader.exec_module(matrix_test)


class PlaytestExplorerTest(unittest.TestCase):

    def seed(self):
        return {
            "schema": explorer.SEED_SCHEMA,
            "identity": {
                "fixture": "synthetic-combat",
                "source_sha256": "a" * 64,
                "seed": 14598366,
            },
            "start_cycle": 30,
            "settle_cycles": 120,
            "setup": {"kind": "campaign", "scenario": "synthetic.pud"},
            "actors": [
                {
                    "id": 100, "player": 0, "domain": "land",
                    "capabilities": ["move", "attack", "attack-ground", "stop"],
                    "target_ids": [200],
                },
                {
                    "id": 110, "player": 0, "domain": "land",
                    "capabilities": ["follow"], "target_ids": [100],
                },
            ],
            "targets": [
                {"id": 200, "player": 1, "domain": "land", "x": 20, "y": 20},
            ],
            "points": [
                {"x": 12, "y": 10, "kind": "open", "domains": ["land"]},
                {"x": 20, "y": 20, "kind": "target", "domains": ["land"]},
                {"x": 9, "y": 10, "kind": "blocked", "domains": ["land"]},
            ],
        }

    def result(self, scenario, side, *, delay=5):
        observations = []
        for index, command in enumerate(scenario["commands"]):
            issued = command["issue_cycle"]
            observations.append({
                "command_index": index,
                "accepted": True,
                "first_progress_cycle": issued + delay,
                "terminal_cycle": issued + delay + 10,
                "terminal_reason": "fulfilled",
                "state": {"tile_x": 12, "tile_y": 10, "order": "STILL",
                          "alive": True, "on_map": True},
            })
        return {
            "schema": explorer.RESULT_SCHEMA,
            "side": side,
            "scenario_sha256": scenario["scenario_sha256"],
            "producer": {
                "name": side,
                "build_sha256": "a" * 64 if side == "native" else "b" * 64,
                "authority_sha256": (
                    explorer.PINNED_BNE_EXECUTABLE_SHA256
                    if side == "native" else None),
            },
            "observations": observations,
            "events": [],
        }

    def adapter(self, side, fault="none"):
        return explorer.Adapter(side, [
            sys.executable, str(ADAPTER), "--side", side,
            "--scenario", "{scenario}", "--output", "{output}",
            "--fault", fault,
        ])

    def test_generates_legal_timing_repeat_and_replacement_scenarios(self):
        scenarios = explorer.generate_scenarios(self.seed(), max_scenarios=500)
        self.assertGreater(len(scenarios), 40)
        self.assertEqual({"single", "repeat", "replace"},
                         {item["pattern"] for item in scenarios})
        attack_cycles = {
            item["commands"][0]["issue_cycle"]
            for item in scenarios
            if item["pattern"] == "single"
            and item["commands"][0]["kind"] == "attack"
        }
        self.assertEqual({30, 31, 34, 35, 39, 40, 44, 45}, attack_cycles)
        for scenario in scenarios:
            explorer.validate_scenario(scenario)
            self.assertEqual("synthetic.pud", scenario["setup"]["scenario"])
            self.assertEqual(2, len(scenario["actors"]))

    def test_capabilities_prevent_inventing_illegal_orders(self):
        seed = self.seed()
        seed["actors"] = [{
            "id": 7, "player": 0, "domain": "water",
            "capabilities": ["move"],
        }]
        seed["points"].append({
            "x": 24, "y": 24, "kind": "open", "domains": ["water"],
        })
        commands = explorer.legal_commands(seed)
        self.assertTrue(commands)
        self.assertEqual({"move"}, {item["kind"] for item in commands})
        self.assertTrue(all(item["x"] == 24 for item in commands))

    def test_authenticated_fixture_becomes_a_timing_and_replacement_seed(self):
        fixture = matrix_test.fixture()
        self.addCleanup(fixture.unlink, missing_ok=True)
        seed = explorer.seed_from_fixture(fixture)
        self.assertEqual("fixture-test", seed["identity"]["fixture_id"])
        self.assertEqual(64, len(seed["identity"]["fixture_sha256"]))
        self.assertEqual({"land", "air", "water"},
                         {actor["domain"] for actor in seed["actors"]})
        self.assertTrue(any(point["kind"] == "occupied"
                            for point in seed["points"]))
        scenarios = explorer.generate_scenarios(seed, max_scenarios=500)
        self.assertTrue(any(item["pattern"] == "repeat" for item in scenarios))
        self.assertTrue(any(item["pattern"] == "replace" for item in scenarios))
        repeated = next(item for item in scenarios if item["pattern"] == "repeat")
        script = explorer.native_command_script(repeated)
        self.assertEqual(2, sum(line.startswith("cycle ")
                                for line in script.splitlines()))
        self.assertIn(repeated["scenario_sha256"], script)

    def test_native_direct_injector_refuses_an_unproved_command_family(self):
        scenario = next(item for item in explorer.generate_scenarios(
            self.seed(), max_scenarios=500)
            if item["commands"][0]["kind"] == "attack")
        with self.assertRaisesRegex(ValueError, "only move"):
            explorer.native_command_script(scenario)

    def test_comparison_uses_relative_cadence_and_observable_state(self):
        scenario = explorer.generate_scenarios(
            self.seed(), max_scenarios=1)[0]
        native = self.result(scenario, "native", delay=5)
        java = self.result(scenario, "java", delay=5)
        self.assertEqual(0, explorer.compare_results(
            native, java, scenario)["difference_count"])
        java["observations"][0]["first_progress_cycle"] += 25
        report = explorer.compare_results(native, java, scenario)
        self.assertEqual("progress_delay",
                         report["first_difference"]["fields"][0])

    def test_result_identity_mismatch_is_refused(self):
        scenario = explorer.generate_scenarios(self.seed(), max_scenarios=1)[0]
        native = self.result(scenario, "native")
        java = self.result(scenario, "java")
        java["scenario_sha256"] = "f" * 64
        with self.assertRaisesRegex(ValueError, "different scenario"):
            explorer.compare_results(native, java, scenario)

    def test_an_unpinned_native_result_is_refused(self):
        scenario = explorer.generate_scenarios(self.seed(), max_scenarios=1)[0]
        native = self.result(scenario, "native")
        java = self.result(scenario, "java")
        native["producer"]["authority_sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "pinned BNE"):
            explorer.compare_results(native, java, scenario)

    def test_end_to_end_rediscovers_reduces_and_seals_seeded_fault(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        report = explorer.explore(
            self.seed(), self.adapter("native"),
            self.adapter("java", "attack-cadence"), Path(temporary.name),
            max_scenarios=160, max_differences=1, minimize_tests=32)
        self.assertEqual(1, report["difference_count"])
        self.assertGreater(report["coverage_token_count"], 5)
        finding = report["differences"][0]
        packet_path = Path(finding["packet"])
        self.assertTrue(packet_path.is_file())
        packet = json.loads(packet_path.read_text(encoding="utf-8"))
        self.assertEqual(explorer.PACKET_SCHEMA, packet["schema"])
        self.assertEqual("progress_delay",
                         packet["comparison"]["first_difference"]["fields"][0])
        self.assertEqual("command-cadence", packet["handoff"]["route"])
        self.assertEqual(1, finding["minimal_command_count"])
        self.assertEqual(64, len(packet["packet_sha256"]))

    def test_reducer_removes_an_unrelated_order_and_keeps_the_fault(self):
        scenarios = explorer.generate_scenarios(self.seed(), max_scenarios=500)
        scenario = next(item for item in scenarios
                        if item["pattern"] == "replace"
                        and item["commands"][0]["kind"] == "move"
                        and item["commands"][1]["kind"] == "attack")
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        minimal, native, java, report = explorer.minimize_difference(
            scenario, self.adapter("native"),
            self.adapter("java", "attack-cadence"), Path(temporary.name),
            max_tests=32)
        self.assertEqual(1, len(minimal["commands"]))
        self.assertEqual("attack", minimal["commands"][0]["kind"])
        self.assertGreater(report["proof"]["tests"], 1)
        self.assertGreater(explorer.compare_results(
            native, java, minimal)["difference_count"], 0)

    def test_empty_or_tampered_measurements_never_pass(self):
        scenario = explorer.generate_scenarios(self.seed(), max_scenarios=1)[0]
        native = self.result(scenario, "native")
        java = self.result(scenario, "java")
        native["observations"] = []
        with self.assertRaisesRegex(ValueError, "no command observations"):
            explorer.compare_results(native, java, scenario)
        tampered = copy.deepcopy(scenario)
        tampered["commands"][0]["issue_cycle"] += 1
        with self.assertRaisesRegex(ValueError, "identity changed"):
            explorer.compare_results(self.result(scenario, "native"),
                                     self.result(scenario, "java"), tampered)


if __name__ == "__main__":
    unittest.main()
