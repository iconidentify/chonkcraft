import importlib.util
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).parents[1] / "scripts" / "bne_oracle.py"
SPEC = importlib.util.spec_from_file_location("bne_oracle", SCRIPT)
bne_oracle = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(bne_oracle)


class OracleIdentityTest(unittest.TestCase):

    def test_rejects_an_unrecognized_executable(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / bne_oracle.TARGET_EXE).write_bytes(b"MZ-not-bne")
            with self.assertRaisesRegex(ValueError, "unsupported executable hash"):
                bne_oracle.verify(root, require_data=False)

    def test_wine_path_uses_the_z_drive(self):
        path = Path("/tmp/Warcraft II/trace.txt")
        translated = bne_oracle.wine_path(path)
        self.assertTrue(translated.startswith("Z:\\"))
        self.assertTrue(translated.endswith(r"Warcraft II\trace.txt"))
        self.assertNotIn("/", translated)

    def test_canonicalizes_all_retail_campaign_families(self):
        self.assertEqual(
            r"Campaign\Human\Human01.pud",
            bne_oracle.canonical_campaign_scenario("campaign/human/human1.pud"),
        )
        self.assertEqual(
            r"Campaign\Orc\Orc14.pud",
            bne_oracle.canonical_campaign_scenario(r"CAMPAIGN\ORC\ORC14.PUD"),
        )
        self.assertEqual(
            r"Campaign\XHuman\2XHum12.pud",
            bne_oracle.canonical_campaign_scenario(r"Campaign\XHuman\2XHum12.pud"),
        )
        self.assertEqual(
            r"Campaign\XOrc\2XOrc01.pud",
            bne_oracle.canonical_campaign_scenario(r"Campaign\XOrc\2XOrc1.pud"),
        )

    def test_rejects_out_of_range_campaign_mission(self):
        with self.assertRaisesRegex(ValueError, "between 1 and 12"):
            bne_oracle.canonical_campaign_scenario(r"Campaign\XOrc\2XOrc13.pud")

    def test_parses_a_sorted_move_command_script(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "commands.txt"
            path.write_text(
                "# deterministic input\n"
                "cycle 5 move unit 1594 x 30 y 18\n"
                "cycle 5 move unit 1592 x 28 y 20\n"
                "cycle 20 move unit 1594 x 31 y 18\n",
                encoding="ascii",
            )
            commands = bne_oracle.parse_command_script(path)
        self.assertEqual(3, len(commands))
        self.assertEqual("move", commands[0]["action"])
        self.assertEqual(1594, commands[0]["unit"])
        self.assertEqual(20, commands[-1]["cycle"])

    def test_parses_stop_and_stand_ground_command_scripts(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "commands.txt"
            path.write_text(
                "cycle 5 stop unit 1594\n"
                "cycle 10 stand-ground unit 1594\n",
                encoding="ascii",
            )
            commands = bne_oracle.parse_command_script(path)
        self.assertEqual(["stop", "stand-ground"],
                         [command["action"] for command in commands])
        self.assertEqual(1594, commands[0]["unit"])

    def test_rejects_an_unsorted_command_script(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "commands.txt"
            path.write_text(
                "cycle 10 move unit 1 x 2 y 3\n"
                "cycle 9 move unit 1 x 3 y 4\n",
                encoding="ascii",
            )
            with self.assertRaisesRegex(ValueError, "not cycle-sorted"):
                bne_oracle.parse_command_script(path)

    def test_validates_a_complete_gameplay_trace(self):
        trace_text = """\
# bne-trace event=storm-open-file archive=00000000 path="Campaign\\\\Orc\\\\Orc01.pud" scope=1 result=1 handle=1 error=0
# bne-trace event=initialization-seed-applied seed=7
# bne-trace event=match-ready slots=1600
cycle 1 seed 00000001
p 0 gold 1000 wood 1000 oil 0
u 1599 unit-grunt p0 10 10 hp 60 o STILL
cycle 2 seed 00000001
p 0 gold 1000 wood 1000 oil 0
u 1599 unit-grunt p0 10 10 hp 60 o STILL
# bne-trace event=cycle-limit cycle=2
# bne-trace protocol=2 event=detach cycles=2 screens=0
"""
        with tempfile.TemporaryDirectory() as directory:
            trace = Path(directory) / "trace.txt"
            trace.write_text(trace_text)
            result = bne_oracle.validate_trace(
                trace, 2, expected_initialization_seed=7)
        self.assertEqual(2, result["cycles"])
        self.assertEqual(2, result["player_records"])
        self.assertEqual(2, result["unit_records"])
        self.assertEqual(r"Campaign\Orc\Orc01.pud", result["scenario"])
        self.assertEqual(6, result["simulation_records"])
        self.assertEqual(7, result["initialization_seed"])
        self.assertRegex(result["simulation_sha256"], r"^[0-9a-f]{64}$")

    def test_rejects_an_unexpected_initialization_seed(self):
        trace_text = """\
# bne-trace event=storm-open-file archive=00000000 path="Campaign\\\\Orc\\\\Orc01.pud" scope=1 result=1 handle=1 error=0
# bne-trace event=initialization-seed-applied seed=9
# bne-trace event=match-ready slots=1600
cycle 1 seed 00000001
p 0 gold 1000 wood 1000 oil 0
u 1599 unit-grunt p0 10 10 hp 60 o STILL
# bne-trace event=cycle-limit cycle=1
# bne-trace protocol=2 event=detach cycles=1 screens=0
"""
        with tempfile.TemporaryDirectory() as directory:
            trace = Path(directory) / "trace.txt"
            trace.write_text(trace_text)
            with self.assertRaisesRegex(ValueError, "initialization seeds"):
                bne_oracle.validate_trace(
                    trace, 1, expected_initialization_seed=7)

    def test_rejects_a_different_scenario_than_requested(self):
        trace_text = """\
# bne-trace event=storm-open-file archive=00000000 path="Campaign\\\\Human\\\\Human01.pud" scope=1 result=1 handle=1 error=0
# bne-trace event=match-ready slots=1600
cycle 1 seed 00000001
p 0 gold 1000 wood 1000 oil 0
u 1 unit-footman p0 10 10 hp 60 o STILL
# bne-trace event=cycle-limit cycle=1
# bne-trace protocol=2 event=detach cycles=1 screens=0
"""
        with tempfile.TemporaryDirectory() as directory:
            trace = Path(directory) / "trace.txt"
            trace.write_text(trace_text)
            with self.assertRaisesRegex(ValueError, "expected"):
                bne_oracle.validate_trace(
                    trace, 1, r"Campaign\Orc\Orc01.pud")

    def test_rejects_loading_calls_as_a_trace(self):
        trace_text = """\
cycle 1 seed 00000001
p 0 gold 0 wood 0 oil 0
# bne-trace event=cycle-limit cycle=1
# bne-trace protocol=2 event=detach cycles=1 screens=0
"""
        with tempfile.TemporaryDirectory() as directory:
            trace = Path(directory) / "trace.txt"
            trace.write_text(trace_text)
            with self.assertRaisesRegex(ValueError, "match-ready"):
                bne_oracle.validate_trace(trace, 1)

    def test_drive_link_is_idempotent_but_never_retargeted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first = root / "first"
            second = root / "second"
            first.mkdir()
            second.mkdir()
            link = root / "i:"
            bne_oracle.ensure_symlink(link, first)
            bne_oracle.ensure_symlink(link, first)
            with self.assertRaisesRegex(ValueError, "refusing to replace"):
                bne_oracle.ensure_symlink(link, second)


if __name__ == "__main__":
    unittest.main()
