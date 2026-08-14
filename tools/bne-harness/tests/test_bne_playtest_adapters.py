import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile


SCRIPTS = Path(__file__).parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))
EXPLORER_SPEC = importlib.util.spec_from_file_location(
    "bne_playtest_explorer", SCRIPTS / "bne_playtest_explorer.py")
explorer = importlib.util.module_from_spec(EXPLORER_SPEC)
assert EXPLORER_SPEC.loader is not None
EXPLORER_SPEC.loader.exec_module(explorer)
NATIVE_SPEC = importlib.util.spec_from_file_location(
    "bne_playtest_native_adapter", SCRIPTS / "bne_playtest_native_adapter.py")
native = importlib.util.module_from_spec(NATIVE_SPEC)
assert NATIVE_SPEC.loader is not None
NATIVE_SPEC.loader.exec_module(native)
JAVA_SPEC = importlib.util.spec_from_file_location(
    "bne_playtest_java_adapter", SCRIPTS / "bne_playtest_java_adapter.py")
java = importlib.util.module_from_spec(JAVA_SPEC)
assert JAVA_SPEC.loader is not None
JAVA_SPEC.loader.exec_module(java)
MATRIX_TEST = Path(__file__).with_name("test_bne_command_matrix.py")
MATRIX_SPEC = importlib.util.spec_from_file_location(
    "command_matrix_test", MATRIX_TEST)
matrix_test = importlib.util.module_from_spec(MATRIX_SPEC)
assert MATRIX_SPEC.loader is not None
MATRIX_SPEC.loader.exec_module(matrix_test)

COMMANDED = (
    Path(__file__).resolve().parents[1]
    / "work/traces/bne-fixture-v1.1-orc-01.txt.bnefx"
)
PINNED = explorer.PINNED_BNE_EXECUTABLE_SHA256


class PlaytestAdapterTest(unittest.TestCase):

    def test_commanded_fixture_becomes_an_exact_move_seed(self):
        self.assertTrue(COMMANDED.is_file(), "authenticated Orc 1 move fixture")
        seed = explorer.seed_from_commanded_fixture(COMMANDED)
        self.assertEqual(PINNED, seed["identity"]["authority_sha256"])
        self.assertEqual(1, len(seed["actors"]))
        self.assertEqual(1594, seed["actors"][0]["id"])
        self.assertEqual((25, 18), (seed["actors"][0]["x"], seed["actors"][0]["y"]))
        self.assertEqual({"move"}, set(seed["actors"][0]["capabilities"]))
        scenarios = explorer.generate_scenarios(seed, max_scenarios=1)
        self.assertEqual(1, len(scenarios))
        command = scenarios[0]["commands"][0]
        self.assertEqual("move", command["kind"])
        self.assertEqual(1594, command["unit_id"])
        self.assertEqual((30, 18), (command["x"], command["y"]))
        self.assertEqual(5, command["issue_cycle"])

    def test_native_adapter_reports_physical_progress_from_sealed_fixture(self):
        self.assertTrue(COMMANDED.is_file(), "authenticated Orc 1 move fixture")
        seed = explorer.seed_from_commanded_fixture(COMMANDED)
        scenario = explorer.generate_scenarios(seed, max_scenarios=1)[0]
        result = native.run_from_fixture(
            scenario, COMMANDED, PINNED, "a" * 64)
        explorer.validate_result(result, scenario, "native")
        self.assertEqual(PINNED, result["producer"]["authority_sha256"])
        observation = result["observations"][0]
        self.assertTrue(observation["accepted"],
                        "the peon accepted the commanded move")
        self.assertEqual(8, observation["first_progress_cycle"],
                         "the peon first changes tile on retail cycle 8")
        self.assertEqual("settled", observation["terminal_reason"])
        self.assertEqual(27, observation["state"]["tile_x"])
        self.assertEqual(17, observation["state"]["tile_y"])
        self.assertEqual("STILL", observation["state"]["order"])

    def test_native_adapter_refuses_a_mismatched_command_stream(self):
        self.assertTrue(COMMANDED.is_file(), "authenticated Orc 1 move fixture")
        seed = explorer.seed_from_commanded_fixture(COMMANDED)
        scenario = explorer.generate_scenarios(seed, max_scenarios=1)[0]
        scenario["commands"][0]["x"] = 12
        scenario["scenario_sha256"] = explorer.digest({
            key: value for key, value in scenario.items()
            if key != "scenario_sha256"
        })
        with self.assertRaisesRegex(ValueError, "do not match"):
            native.run_from_fixture(scenario, COMMANDED, PINNED, "a" * 64)

    def test_native_adapter_refuses_an_unpinned_fixture(self):
        fixture = matrix_test.fixture()
        self.addCleanup(fixture.unlink, missing_ok=True)
        with zipfile.ZipFile(fixture, "a") as archive:
            archive.writestr(
                "commands.txt",
                "cycle 5 move unit 0 x 12 y 8\n")
        seed = {
            "schema": explorer.SEED_SCHEMA,
            "identity": {"fixture": "unpinned"},
            "setup": {"kind": "sealed-fixture", "fixture": str(fixture),
                      "scenario": r"Campaign\Orc\Orc01.pud"},
            "start_cycle": 5,
            "settle_cycles": 10,
            "actors": [{"id": 0, "player": 0, "domain": "land",
                        "capabilities": ["move"], "x": 8, "y": 8}],
            "targets": [],
            "points": [{"x": 12, "y": 8, "kind": "open", "domains": ["land"]}],
        }
        scenario = explorer.generate_scenarios(seed, max_scenarios=1)[0]
        with self.assertRaisesRegex(ValueError, "pinned BNE"):
            native.run_from_fixture(scenario, fixture, PINNED, "a" * 64)

    def test_native_adapter_refuses_an_unauthenticated_local_executable(self):
        with tempfile.TemporaryDirectory() as directory:
            fake = Path(directory) / "Warcraft II BNE.exe"
            fake.write_bytes(b"not-bne")
            with self.assertRaisesRegex(ValueError, "pinned BNE"):
                native.authenticate_executable(fake)

    def test_empty_native_state_is_refused(self):
        self.assertTrue(COMMANDED.is_file(), "authenticated Orc 1 move fixture")
        seed = explorer.seed_from_commanded_fixture(COMMANDED)
        scenario = explorer.generate_scenarios(seed, max_scenarios=1)[0]
        with self.assertRaisesRegex(ValueError, "empty"):
            native.observe_commands(scenario, [])

    def test_java_adapter_source_issues_through_command_applier(self):
        source = Path(__file__).resolve().parents[3] / (
            "desktop/src/main/java/net/chonkbase/chonkcraft/desktop/"
            "BnePlaytestAdapter.java")
        text = source.read_text(encoding="utf-8")
        self.assertIn("CommandApplier", text)
        self.assertIn("PlayerIntentJournal", text)
        self.assertIn("issueAccepted", text)
        self.assertNotIn("world.orderCommandMove", text)

    def test_java_adapter_refuses_a_missing_pack(self):
        with tempfile.TemporaryDirectory() as directory:
            missing = Path(directory) / "missing.chonkpack"
            with self.assertRaisesRegex(ValueError, "missing"):
                java.resolve_pack(missing)


if __name__ == "__main__":
    unittest.main()
