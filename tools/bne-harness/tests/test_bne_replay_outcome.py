import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
from types import SimpleNamespace

SCRIPTS = Path(__file__).parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))
import bne_replay
import bne_oracle

SPEC = importlib.util.spec_from_file_location(
    "bne_replay_outcome", SCRIPTS / "bne_replay_outcome.py")
outcome = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(outcome)
PROGRAM_SHA256 = outcome._current_program_input_sha256()

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
        normalized = []
        for value in outcomes:
            item = dict(value)
            item.setdefault("unit_generation", 0)
            phases = dict(item.get("phases") or {})
            phases.setdefault("submitted", {"record": item["record"]})
            phases.setdefault("accepted", True)
            phases.setdefault("progress", None)
            phases.setdefault("terminal", None)
            item["phases"] = phases
            normalized.append(item)
        selected = []
        for record in plan["records"]:
            for command in record["commands"]:
                if command["name"] == "selection":
                    continue
                for local_id in command["selected_unit_ids"]:
                    if local_id not in selected:
                        selected.append(local_id)
        units = [self.initial_unit(
            local_id, ordinal=ordinal, x=10 + ordinal)
            for ordinal, local_id in enumerate(selected)]
        producer = {
            "name": side,
            "build_sha256": (
                outcome.PINNED_BNE_EXECUTABLE_SHA256
                if side == "native" else "a" * 64),
        }
        if side == "java":
            producer["engine_input_sha256"] = "a" * 64
            producer["program_input_sha256"] = PROGRAM_SHA256
        return {
            "schema": outcome.TRACE_SCHEMA,
            "side": side,
            "identity": outcome._trace_identity(plan),
            "producer": producer,
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
            "unit_lifecycle": {
                "schema": outcome.LIFECYCLE_SCHEMA,
                "units": units,
            },
            "outcomes": normalized,
        }

    @staticmethod
    def initial_unit(local_id, generation=0, *, x=10, y=11,
                     owner=0, unit_type="footman", ordinal=0):
        return {
            "local_id": local_id,
            "generation": generation,
            "origin": "initial",
            "owner": owner,
            "type": unit_type,
            "x": x,
            "y": y,
            "ordinal": ordinal,
            "death_record": None,
        }

    def with_lifecycle(self, trace, units):
        trace["unit_lifecycle"] = {
            "schema": outcome.LIFECYCLE_SCHEMA,
            "units": units,
        }
        return trace

    def complete_outcomes(self, plan, overrides=None, *, unit_map=None):
        """One explicit outcome for every selected unit in every command."""
        overrides = overrides or {}
        unit_map = unit_map or {}
        values = []
        for record in plan["records"]:
            for command in record["commands"]:
                if command["name"] == "selection":
                    continue
                for local_id in command["selected_unit_ids"]:
                    key = (record["index"], command["index"], local_id)
                    phases = {
                        "submitted": {"record": record["index"]},
                        "accepted": True,
                        "progress": {"cycle": record["index"] + 1},
                        "terminal": {"cycle": record["index"] + 2},
                    }
                    phases.update(overrides.get(key, {}))
                    values.append({
                        "record": record["index"],
                        "command": command["index"],
                        "unit": unit_map.get(local_id, local_id),
                        "unit_generation": 0,
                        "phases": phases,
                    })
        return values

    def prefix_runner(self, *, native_overrides=None, java_overrides=None):
        """Test double for two genuine executions of each supplied prefix."""
        def run(prefix):
            return (
                self.trace(prefix, "native", self.complete_outcomes(
                    prefix, native_overrides)),
                self.trace(prefix, "java", self.complete_outcomes(
                    prefix, java_overrides)),
            )
        return run

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
        native_outcomes = self.complete_outcomes(plan)
        java_outcomes = self.complete_outcomes(plan, {
            (0, 1, 42): {"progress": None},
        })
        java_outcomes = [item for item in java_outcomes
                         if not (item["record"] == 0
                                 and item["command"] == 1
                                 and item["unit"] == 7)]
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
        values = self.complete_outcomes(plan)
        report = outcome.compare(
            plan, self.trace(plan, "native", values),
            self.trace(plan, "java", values))
        self.assertEqual(0, report["difference_count"])
        self.assertIsNone(report["first_difference"])

    def test_lifecycle_identity_pairs_different_allocator_ids(self):
        plan = self.plan()
        native_values = self.complete_outcomes(plan)
        java_values = self.complete_outcomes(
            plan, unit_map={42: 900, 7: 901})
        native = self.with_lifecycle(
            self.trace(plan, "native", native_values),
            [self.initial_unit(42, ordinal=0, x=10),
             self.initial_unit(7, ordinal=1, x=11)])
        java = self.with_lifecycle(
            self.trace(plan, "java", java_values),
            [self.initial_unit(900, ordinal=0, x=10),
             self.initial_unit(901, ordinal=1, x=11)])
        report = outcome.compare(plan, native, java)
        self.assertEqual(0, report["difference_count"])
        self.assertEqual("lifecycle-v1", report["identity_bridge"]["mode"])
        pair = next(item for item in report["identity_bridge"]["pairs"]
                    if item["native_unit"]["local_id"] == 42)
        self.assertEqual({"local_id": 900, "generation": 0},
                         pair["java_unit"])

    def test_lifecycle_allows_local_id_reuse_but_requires_generation(self):
        plan = self.plan()
        first = self.initial_unit(42, 0)
        first["death_record"] = 1
        producer_unit = self.initial_unit(99, 0, x=20, y=20, ordinal=0)
        producer = outcome._stable_unit_identity(producer_unit)["stable_sha256"]
        units = [first, self.initial_unit(7, 0, x=11, ordinal=1),
                 producer_unit, {
            "local_id": 42, "generation": 1, "origin": "spawn",
            "owner": 0, "type": "footman", "birth_record": 1,
            "producer": producer, "ordinal": 0, "death_record": None,
        }]
        trace = self.with_lifecycle(self.trace(plan, "native", []), units)
        indexed = outcome.lifecycle_index(trace, "native")
        self.assertEqual(4, len(indexed["by_local"]))
        trace["outcomes"] = [{
            "record": 1, "command": 0, "unit": 42,
            "unit_generation": 2,
            "phases": {"submitted": {"record": 1}, "accepted": True,
                       "progress": None, "terminal": None},
        }]
        java = self.with_lifecycle(
            self.trace(plan, "java", []),
            [self.initial_unit(900)])
        with self.assertRaisesRegex(ValueError, "no matching unit lifecycle"):
            outcome.compare(plan, trace, java)

    def test_duplicate_stable_lifecycle_identity_is_rejected(self):
        plan = self.plan()
        trace = self.with_lifecycle(
            self.trace(plan, "native", []),
            [self.initial_unit(42), self.initial_unit(43)])
        with self.assertRaisesRegex(ValueError, "repeats stable identity"):
            outcome.lifecycle_index(trace, "native")

    def test_prefix_bisection_seals_first_divergent_record(self):
        plan = self.plan()
        native_values = self.complete_outcomes(plan)
        java_overrides = {
            (1, 0, 42): {"progress": None},
        }
        java_values = self.complete_outcomes(plan, java_overrides)
        packet = outcome.divergence_packet(
            plan, self.trace(plan, "native", native_values),
            self.trace(plan, "java", java_values),
            self.prefix_runner(java_overrides=java_overrides))
        self.assertFalse(packet["exact"])
        self.assertEqual(2, packet["minimal_prefix_records"])
        self.assertEqual(1, packet["last_exact_prefix_records"])
        self.assertEqual(1, packet["report"]["first_difference"]["record"])
        self.assertEqual(64, len(packet["packet_sha256"]))
        self.assertEqual(
            packet["packet_sha256"],
            outcome.divergence_packet(
                plan, self.trace(plan, "native", native_values),
                self.trace(plan, "java", java_values),
                self.prefix_runner(java_overrides=java_overrides))[
                    "packet_sha256"])
        encoded = outcome.native_schedule_bytes(packet["plan"])
        self.assertEqual(2, outcome.parse_native_schedule(encoded)["record_count"])

    def test_projected_prefix_is_non_certifying_and_fresh_receipts_win(self):
        plan = self.plan()
        changed = {(0, 1, 42): {
            "terminal": {"cycle": 2, "reason": "superseded"},
        }}
        native = self.trace(plan, "native", self.complete_outcomes(plan))
        java = self.trace(plan, "java", self.complete_outcomes(plan, changed))
        prefix = outcome.plan_prefix(plan, 1)
        projected = outcome.trace_prefix(java, prefix)
        self.assertEqual(
            "projected-non-certifying", projected["schedule"]["status"])
        with self.assertRaisesRegex(ValueError, "complete packet schedule"):
            outcome.compare(
                prefix, outcome.trace_prefix(native, prefix), projected)

        calls = []
        def observed(prefix_plan):
            calls.append(len(prefix_plan["records"]))
            prefix_change = changed if len(prefix_plan["records"]) == 2 else {}
            return self.prefix_runner(java_overrides=prefix_change)(prefix_plan)

        packet = outcome.divergence_packet(plan, native, java, observed)
        self.assertEqual(2, packet["minimal_prefix_records"])
        self.assertEqual(1, packet["last_exact_prefix_records"])
        self.assertIn(1, calls)
        self.assertIn(2, calls)

    def test_divergent_bisection_refuses_full_run_projection(self):
        plan = self.plan()
        native = self.trace(plan, "native", self.complete_outcomes(plan))
        java = self.trace(plan, "java", self.complete_outcomes(plan, {
            (0, 1, 42): {"accepted": False},
        }))
        with self.assertRaisesRegex(ValueError, "fresh native and Java"):
            outcome.divergence_packet(plan, native, java)

    def test_content_addressed_packet_is_idempotent(self):
        plan = self.plan()
        native = self.trace(plan, "native", self.complete_outcomes(plan))
        java = self.trace(plan, "java", self.complete_outcomes(plan, {
            (0, 1, 42): {"accepted": False},
        }))
        java_overrides = {(0, 1, 42): {"accepted": False}}
        packet = outcome.divergence_packet(
            plan, native, java,
            self.prefix_runner(java_overrides=java_overrides))
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        first = outcome.write_divergence_packet(Path(temporary.name), packet)
        second = outcome.write_divergence_packet(Path(temporary.name), packet)
        self.assertEqual(first, second)
        self.assertEqual(packet, json.loads(first.read_text(encoding="utf-8")))
        packet["report"]["difference_count"] += 1
        with self.assertRaisesRegex(ValueError, "identity changed"):
            outcome.write_divergence_packet(Path(temporary.name), packet)

    def test_successful_divergence_packet_cli_is_zero_unless_requested(self):
        args = SimpleNamespace(
            plan=Path("plan.json"), native=Path("native.json"),
            java=Path("java.json"), output_dir=Path("packets"),
            native_prefix_command=None, java_prefix_command=None,
            fail_on_divergence=False,
        )
        with mock.patch.object(outcome, "_load_json",
                               side_effect=[{}, {}, {}, {}, {}, {}]), \
                mock.patch.object(outcome, "divergence_packet",
                                  return_value={"exact": False}), \
                mock.patch.object(outcome, "write_divergence_packet",
                                  return_value=Path("packet.json")):
            self.assertEqual(0, outcome.divergence_packet_command(args))
            args.fail_on_divergence = True
            self.assertEqual(2, outcome.divergence_packet_command(args))

    def test_corpus_certification_uses_a_frozen_record_denominator(self):
        plan = self.plan()
        values = self.complete_outcomes(plan)
        report = outcome.compare(
            plan, self.trace(plan, "native", values),
            self.trace(plan, "java", values))
        entry = {
            "path": "commands.wir",
            **report["identity"],
            "record_count": 2,
            "command_count": 3,
        }
        corpus = {
            "schema": outcome.CORPUS_SCHEMA,
            "corpus_sha256": "1" * 64,
            "outcome_corpus_sha256": "2" * 64,
            "replay_count": 1,
            "snapshot_bytes": 1,
            "record_count": 2,
            "command_count": 3,
            "entries": [entry],
        }
        totals = {"replay_count": 1, "snapshot_bytes": 1,
                  "record_count": 2, "command_count": 3}
        with mock.patch.object(outcome, "AUTHENTICATED_CORPUS_SHA256",
                               "1" * 64), \
                mock.patch.object(
                    outcome, "AUTHENTICATED_OUTCOME_CORPUS_SHA256",
                    "2" * 64), \
                mock.patch.object(outcome, "AUTHENTICATED_CORPUS_TOTALS",
                                  totals):
            certified = outcome.certify_corpus(
                corpus, [report],
                current_java_engine_input_sha256="a" * 64,
                current_java_program_input_sha256=PROGRAM_SHA256)
            missing = outcome.certify_corpus(corpus, [])
        self.assertTrue(certified["content_exact"])
        self.assertFalse(certified["complete"])
        self.assertEqual((2, 3), (certified["exact_records"],
                                 certified["exact_commands"]))
        self.assertFalse(missing["complete"])
        self.assertEqual(0, missing["exact_records"])
        self.assertEqual("missing", missing["rows"][0]["status"])

    def test_detached_comparison_summaries_cannot_certify_producers(self):
        plan = self.plan()
        values = self.complete_outcomes(plan)
        report = outcome.compare(
            plan, self.trace(plan, "native", values),
            self.trace(plan, "java", values))
        corpus = {
            "schema": outcome.CORPUS_SCHEMA,
            "corpus_sha256": "1" * 64,
            "outcome_corpus_sha256": "2" * 64,
            "replay_count": 1, "snapshot_bytes": 1,
            "record_count": 2, "command_count": 3,
            "entries": [{"path": "commands.wir", **report["identity"],
                         "record_count": 2, "command_count": 3}],
        }
        totals = {"replay_count": 1, "snapshot_bytes": 1,
                  "record_count": 2, "command_count": 3}
        with mock.patch.object(outcome, "AUTHENTICATED_CORPUS_SHA256",
                               "1" * 64), \
                mock.patch.object(
                    outcome, "AUTHENTICATED_OUTCOME_CORPUS_SHA256",
                    "2" * 64), \
                mock.patch.object(outcome, "AUTHENTICATED_CORPUS_TOTALS",
                                  totals):
            certified = outcome.certify_corpus(
                corpus, [report],
                current_java_engine_input_sha256="a" * 64,
                current_java_program_input_sha256=PROGRAM_SHA256)
        self.assertTrue(certified["content_exact"])
        self.assertFalse(certified["complete"])
        self.assertFalse(certified["producer_reports_verified"])

    def test_corpus_certification_rejects_unpinned_native_receipt(self):
        plan = self.plan()
        values = self.complete_outcomes(plan)
        report = outcome.compare(
            plan, self.trace(plan, "native", values),
            self.trace(plan, "java", values))
        report["producers"]["native"]["build_sha256"] = "b" * 64
        corpus = {
            "schema": outcome.CORPUS_SCHEMA,
            "corpus_sha256": "1" * 64,
            "outcome_corpus_sha256": "2" * 64,
            "replay_count": 1,
            "snapshot_bytes": 1,
            "record_count": 2,
            "command_count": 3,
            "entries": [{"path": "commands.wir", **report["identity"],
                         "record_count": 2, "command_count": 3}],
        }
        totals = {"replay_count": 1, "snapshot_bytes": 1,
                  "record_count": 2, "command_count": 3}
        with mock.patch.object(outcome, "AUTHENTICATED_CORPUS_SHA256",
                               "1" * 64), \
                mock.patch.object(
                    outcome, "AUTHENTICATED_OUTCOME_CORPUS_SHA256",
                    "2" * 64), \
                mock.patch.object(outcome, "AUTHENTICATED_CORPUS_TOTALS",
                                  totals):
            with self.assertRaisesRegex(ValueError, "pinned BNE"):
                outcome.certify_corpus(corpus, [report])

    def test_empty_outcomes_cannot_certify_a_nonempty_replay(self):
        plan = self.plan()
        report = outcome.compare(
            plan, self.trace(plan, "native", []),
            self.trace(plan, "java", []))
        self.assertFalse(report["complete"])
        self.assertFalse(report["exact"])
        self.assertEqual(4, report["required_outcomes"])
        self.assertEqual(4, report["difference_count"])


if __name__ == "__main__":
    unittest.main()
