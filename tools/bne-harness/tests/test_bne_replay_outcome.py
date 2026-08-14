import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))
import bne_replay
import bne_oracle

SPEC = importlib.util.spec_from_file_location(
    "bne_replay_outcome", SCRIPTS / "bne_replay_outcome.py")
outcome = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(outcome)

REPLAY_TEST = Path(__file__).with_name("test_bne_replay.py")
REPLAY_SPEC = importlib.util.spec_from_file_location("replay_test", REPLAY_TEST)
replay_test = importlib.util.module_from_spec(REPLAY_SPEC)
assert REPLAY_SPEC.loader is not None
REPLAY_SPEC.loader.exec_module(replay_test)


class ReplayOutcomeTest(unittest.TestCase):

    def plan(self):
        records = [
            (bytes((0, 3, 3, 3, 3, 3, 3, 3)), 0,
             bytes.fromhex("1801020308022a0007001012001000ffff")),
            (bytes((0, 3, 3, 3, 3, 3, 3, 3)), 0,
             bytes.fromhex("18020203137d001c00ffff08")),
        ]
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        path = Path(temporary.name) / "commands.wir"
        path.write_bytes(replay_test.replay_bytes(records))
        plan = outcome.compile_plan(bne_replay.parse_replay(path))
        plan["replay"]["startup"]["map_asset"] = {
            "status": "verified", "asset_sha256": "c" * 64,
        }
        plan["startup_sha256"] = outcome._sha256(outcome._json_bytes(
            plan["replay"]["startup"]))
        return plan

    def trace(self, plan, side, outcomes):
        return {
            "schema": outcome.TRACE_SCHEMA,
            "side": side,
            "identity": outcome._trace_identity(plan),
            "producer": {
                "name": side,
                "build_sha256": (
                    outcome.PINNED_BNE_EXECUTABLE_SHA256
                    if side == "native" else "a" * 64),
            },
            "initial_state": {
                "status": "verified",
                "consumed_sha256": plan["replay"]["snapshot_sha256"],
                "map_asset_sha256": plan["replay"]["startup"][
                    "map_asset"]["asset_sha256"],
            },
            "schedule": {
                "status": "complete",
                "consumed_records": len(plan["records"]),
                "consumed_sha256": plan["schedule_sha256"],
            },
            "outcomes": outcomes,
        }

    def test_plan_preserves_packet_order_and_ordered_selection(self):
        plan = self.plan()
        self.assertEqual(outcome.PLAN_SCHEMA, plan["schema"])
        self.assertEqual(2, plan["replay"]["record_count"])
        self.assertEqual(3, plan["replay"]["command_count"])
        move = plan["records"][0]["commands"][1]
        self.assertEqual("move", move["name"])
        self.assertEqual([42, 7], move["selected_unit_ids"])
        self.assertEqual(64, len(plan["schedule_sha256"]))
        self.assertEqual(
            outcome.PINNED_BNE_EXECUTABLE_SHA256,
            plan["native_dispatch_contract"]["executable_sha256"],
        )
        startup = plan["replay"]["startup"]
        self.assertEqual("test", startup["map_slug"])
        self.assertEqual(2, startup["player_count"])
        self.assertEqual("human", startup["slots"][0]["occupant"])
        self.assertEqual({
            "gold": 10000,
            "wood": 5000,
            "oil": 5000,
            "name": "high",
            "status": "verified",
            "native_function": "0x004338d0",
        }, startup["resource_bank"])
        self.assertIn("not a mid-game save", startup["meaning"])

    def test_retail_resource_presets_and_map_default_are_explicit(self):
        replay = bne_replay.parse_replay(self._replay_with_resources(3))
        bank = outcome.startup_recipe(replay)["resource_bank"]
        self.assertEqual((5000, 2000, 2000),
                         (bank["gold"], bank["wood"], bank["oil"]))
        replay = bne_replay.parse_replay(self._replay_with_resources(1))
        bank = outcome.startup_recipe(replay)["resource_bank"]
        self.assertEqual("map-default", bank["status"])
        self.assertEqual((2100, 1100, 1000), (
            bank["minimum_gold"], bank["minimum_wood"], bank["minimum_oil"]))

    def _replay_with_resources(self, resources):
        import struct
        import zlib
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        path = Path(temporary.name) / "resources.wir"
        raw = bytearray(zlib.decompress(replay_test.replay_bytes()))
        raw[bne_replay.RESOURCES_OFFSET] = resources
        raw[bne_replay.CHECKSUM_OFFSET:bne_replay.CHECKSUM_OFFSET + 4] = bytes(4)
        struct.pack_into("<I", raw, bne_replay.CHECKSUM_OFFSET,
                         (~zlib.crc32(raw)) & 0xffffffff)
        path.write_bytes(zlib.compress(raw))
        return path

    def test_native_schedule_is_exact_and_rejects_trailing_data(self):
        plan = self.plan()
        encoded = outcome.native_schedule_bytes(plan)
        decoded = outcome.parse_native_schedule(encoded)
        self.assertEqual(plan["schedule_sha256"], decoded["schedule_sha256"])
        self.assertEqual(plan["replay"]["snapshot_sha256"],
                         decoded["snapshot_sha256"])
        self.assertEqual(2, decoded["record_count"])
        self.assertEqual(plan["records"][1]["packet"],
                         decoded["records"][1]["packet"])
        with self.assertRaisesRegex(ValueError, "trailing"):
            outcome.parse_native_schedule(encoded + b"x")

    def test_oracle_binds_schedule_bytes_to_the_plan(self):
        plan = self.plan()
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        plan_path = Path(temporary.name) / "plan.json"
        schedule_path = Path(temporary.name) / "schedule.bin"
        plan_path.write_text(json.dumps(plan), encoding="utf-8")
        schedule_path.write_bytes(outcome.native_schedule_bytes(plan))
        self.assertEqual(plan, bne_oracle.validate_replay_inputs(
            plan_path, schedule_path))
        schedule_path.write_bytes(schedule_path.read_bytes() + b"x")
        with self.assertRaisesRegex(ValueError, "does not match"):
            bne_oracle.validate_replay_inputs(plan_path, schedule_path)

    def test_native_trace_from_another_executable_is_rejected(self):
        plan = self.plan()
        native = self.trace(plan, "native", [])
        native["producer"]["build_sha256"] = "b" * 64
        with self.assertRaisesRegex(ValueError, "pinned BNE"):
            outcome.compare(plan, native, self.trace(plan, "java", []))

    def test_native_dispatch_trace_requires_every_record_and_clean_close(self):
        plan = self.plan()
        lines = [
            "# bne-trace event=replay-schedule-loaded records=2 "
            f"schedule-sha256={plan['schedule_sha256']} "
            f"snapshot-sha256={plan['replay']['snapshot_sha256']}",
            "# bne-trace event=replay-dispatch-injected record=0 player=0 "
            f"bytes={len(bytes.fromhex(plan['records'][0]['packet']))} "
            "trace-cycle=0",
            "# bne-trace event=replay-dispatch-injected record=1 player=0 "
            f"bytes={len(bytes.fromhex(plan['records'][1]['packet']))} "
            "trace-cycle=1",
            "# bne-trace event=replay-schedule-complete records=2",
            "# bne-trace event=replay-schedule-closed complete=true "
            "valid=true consumed=2 records=2",
        ]
        proof = outcome.verify_native_dispatch_trace(plan, lines)
        self.assertEqual("verified", proof["status"])
        self.assertEqual(2, proof["injected_records"])
        with self.assertRaisesRegex(ValueError, "every replay dispatch"):
            outcome.verify_native_dispatch_trace(plan, lines[0:2] + lines[-2:])

    def test_native_dispatch_mismatch_fails_closed(self):
        plan = self.plan()
        lines = [
            "# bne-trace event=replay-schedule-loaded records=2 "
            f"schedule-sha256={plan['schedule_sha256']} "
            f"snapshot-sha256={plan['replay']['snapshot_sha256']}",
            "# bne-trace event=replay-dispatch-mismatch record=0 "
            "reason=unexpected-dispatch player=0 bytes=1",
        ]
        with self.assertRaisesRegex(ValueError, "dispatch mismatch"):
            outcome.verify_native_dispatch_trace(plan, lines)

    def test_comparison_fails_when_initial_state_is_not_proved(self):
        plan = self.plan()
        native = self.trace(plan, "native", [])
        java = self.trace(plan, "java", [])
        java["initial_state"]["status"] = "pending"
        with self.assertRaisesRegex(ValueError, "initial state"):
            outcome.compare(plan, native, java)

    def test_comparison_fails_when_one_packet_was_not_consumed(self):
        plan = self.plan()
        native = self.trace(plan, "native", [])
        java = self.trace(plan, "java", [])
        native["schedule"]["consumed_records"] -= 1
        with self.assertRaisesRegex(ValueError, "complete packet schedule"):
            outcome.compare(plan, native, java)

    def test_group_fanout_and_no_progress_are_clustered(self):
        plan = self.plan()
        native_outcomes = [
            {"record": 0, "command": 1, "unit": 42, "phases": {
                "accepted": True, "progress": {"cycle": 3, "x": 11, "y": 10},
                "terminal": {"cycle": 20, "x": 18, "y": 16}}},
            {"record": 0, "command": 1, "unit": 7, "phases": {
                "accepted": True, "progress": {"cycle": 4, "x": 10, "y": 11},
                "terminal": {"cycle": 22, "x": 18, "y": 16}}},
        ]
        java_outcomes = [
            {"record": 0, "command": 1, "unit": 42, "phases": {
                "accepted": True, "progress": None,
                "terminal": {"cycle": 20, "x": 10, "y": 10}}},
        ]
        report = outcome.compare(
            plan,
            self.trace(plan, "native", native_outcomes),
            self.trace(plan, "java", java_outcomes),
        )
        self.assertEqual(2, report["difference_count"])
        self.assertEqual(
            ["acknowledged-no-progress", "group-fanout"],
            sorted(cluster["name"] for cluster in report["clusters"]),
        )
        self.assertEqual("presence", report["first_difference"]["phase"])

    def test_identical_outcomes_pass(self):
        plan = self.plan()
        values = [{"record": 1, "command": 0, "unit": 42, "phases": {
            "accepted": True, "progress": {"cycle": 2, "order": "ATTACK"},
            "terminal": {"cycle": 8, "target_hp": 0}}}]
        report = outcome.compare(
            plan, self.trace(plan, "native", values),
            self.trace(plan, "java", values))
        self.assertEqual(0, report["difference_count"])
        self.assertIsNone(report["first_difference"])


if __name__ == "__main__":
    unittest.main()
