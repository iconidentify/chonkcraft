from pathlib import Path
import json
import sys
import tempfile
import unittest


SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_java
import bne_micro_oracle as mo
import bne_triage

from synthetic_decision import (
    CODE_BASE, DATA_BASE, OUTCOME_ADDRESS, decision_code, nested_code,
    snapshot_document,
)


def load(**kwargs):
    return mo.load_snapshot(snapshot_document(**kwargs), expected_executable=None)


class BneMicroOracleSnapshotTest(unittest.TestCase):
    """The evidence contract: what a snapshot must carry to be believed."""

    def test_a_snapshot_from_another_executable_is_refused(self):
        document = snapshot_document()
        document["executable_sha256"] = "0" * 64
        with self.assertRaises(mo.SnapshotError) as raised:
            mo.load_snapshot(document)
        self.assertIn("pinned executable", str(raised.exception),
                      "a snapshot claiming another executable was loaded, so "
                      "a replay could report the behaviour of a different "
                      "build as the game's")

    def test_a_blob_whose_bytes_changed_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            blobs = directory / "blobs"
            blobs.mkdir()
            payload = bytes(0x2000)
            digest = mo.sha256_bytes(payload)
            (blobs / f"{digest}.bin").write_bytes(payload)
            document = snapshot_document()
            document["segments"].append({
                "address": 0x00400000, "access": "r",
                "blob": {"sha256": digest, "bytes": len(payload)},
            })
            mo.load_snapshot(document, blob_root=blobs,
                             expected_executable=None)
            (blobs / f"{digest}.bin").write_bytes(payload + b"edited")
            with self.assertRaises(mo.SnapshotError) as raised:
                mo.load_snapshot(document, blob_root=blobs,
                                 expected_executable=None)
            self.assertIn("identity changed", str(raised.exception),
                          "a memory blob was edited after capture and the "
                          "snapshot still loaded")

    def test_overlapping_memory_is_refused(self):
        document = snapshot_document()
        document["segments"].append(
            {"address": DATA_BASE + 0x10, "hex": "00" * 16, "access": "rw"})
        with self.assertRaises(mo.SnapshotError) as raised:
            mo.load_snapshot(document, expected_executable=None)
        self.assertIn("overlap", str(raised.exception),
                      "two mappings of the same address were accepted, so "
                      "which bytes the function reads depends on load order")

    def test_a_snapshot_missing_a_register_is_refused(self):
        document = snapshot_document()
        del document["registers"]["esi"]
        with self.assertRaises(mo.SnapshotError) as raised:
            mo.load_snapshot(document, expected_executable=None)
        self.assertIn("missing registers", str(raised.exception))

    def test_an_entry_outside_mapped_memory_is_refused(self):
        document = snapshot_document()
        document["entry"] = 0x00900000
        with self.assertRaises(mo.SnapshotError) as raised:
            mo.load_snapshot(document, expected_executable=None)
        self.assertIn("entry address", str(raised.exception))

    def test_a_stack_pointer_outside_mapped_memory_is_refused(self):
        document = snapshot_document()
        document["registers"]["esp"] = 0x00900000
        with self.assertRaises(mo.SnapshotError) as raised:
            mo.load_snapshot(document, expected_executable=None)
        self.assertIn("stack pointer", str(raised.exception),
                      "a snapshot whose stack was never captured loaded, and "
                      "the first push would have written into nothing")

    def test_a_reviewed_input_outside_mapped_memory_is_refused(self):
        document = snapshot_document()
        document["inputs"].append(
            {"name": "stray", "kind": "memory", "address": 0x00900000,
             "width": 4})
        with self.assertRaises(mo.SnapshotError) as raised:
            mo.load_snapshot(document, expected_executable=None)
        self.assertIn("outside mapped memory", str(raised.exception))


class BneMicroOracleReplayTest(unittest.TestCase):
    """Phase 1 and 2: the captured invocation runs again, exactly."""

    def test_the_unchanged_snapshot_reproduces_what_was_captured(self):
        snapshot = load(counter=3, limit=9, reach=4)
        report = mo.reproduce(snapshot)
        self.assertEqual("exact", report["status"],
                         f"the captured invocation did not reproduce: "
                         f"{report['mismatches']}")
        self.assertEqual(0, report["replay"]["registers"]["eax"])
        self.assertEqual([], report["mismatches"])

    def test_a_snapshot_whose_recorded_outcome_disagrees_is_divergent(self):
        document = snapshot_document(counter=3, limit=9, reach=4)
        document["expected"] = {"registers": {"eax": 1}}
        report = mo.reproduce(mo.load_snapshot(document,
                                               expected_executable=None))
        self.assertEqual("divergent", report["status"],
                         "a replay that disagreed with the captured outcome "
                         "was reported as a reproduction, which is how an "
                         "emulator bug becomes a finding about the game")
        self.assertEqual("register", report["mismatches"][0]["kind"])

    def test_the_recorded_outcome_is_checked_against_writes_and_branches(self):
        snapshot = load(counter=6, limit=9, reach=4)
        result = mo.reproduce(snapshot)["replay"]
        self.assertEqual([{"address": OUTCOME_ADDRESS, "size": 4,
                           "hex": "01000000"}], result["writes"],
                         "the memory the function wrote was not recorded")
        self.assertEqual([{"address": CODE_BASE + 0x0A, "taken": False}],
                         result["branches"],
                         "which way the conditional went was not recorded")
        self.assertGreater(result["read_count"], 0,
                           "the memory the function read was not tracked")

    def test_a_read_of_memory_nobody_captured_fails_closed(self):
        document = snapshot_document()
        # Drop the data mapping the function reads its input from.
        document["segments"] = [segment for segment in document["segments"]
                                if segment["address"] != DATA_BASE]
        document["inputs"] = []
        report = mo.reproduce(mo.load_snapshot(document,
                                               expected_executable=None))
        self.assertEqual("failed", report["status"],
                         "a function read memory the snapshot never captured "
                         "and the replay carried on, which turns a "
                         "reproduction into a fabrication")
        self.assertIn("unmapped", report["replay"]["error"])

    def test_a_function_that_never_returns_exhausts_its_budget(self):
        document = snapshot_document(code=bytes([0xEB, 0xFE]))  # jmp $
        report = mo.reproduce(mo.load_snapshot(document,
                                               expected_executable=None),
                              instruction_budget=500)
        self.assertEqual("failed", report["status"],
                         "an endless function ran without limit")
        self.assertIn("budget", report["replay"]["error"])

    def test_a_nested_call_replays_through_its_helper(self):
        for counter, expected, branches in ((9, 2, 1), (3, 0, 2), (6, 1, 2)):
            snapshot = load(counter=counter, limit=9, reach=4,
                            code=nested_code())
            report = mo.reproduce(snapshot)
            self.assertEqual("exact", report["status"],
                             f"the nested function failed at ecx={counter}: "
                             f"{report['replay']['error']}")
            self.assertEqual(expected, report["replay"]["registers"]["eax"],
                             f"the helper reached by a real call and ret "
                             f"returned the wrong value at ecx={counter}")
            self.assertEqual(branches, len(report["replay"]["branches"]),
                             "the branch path through the nested function was "
                             "not recorded")

    def test_an_unsupported_instruction_is_reported_rather_than_skipped(self):
        document = snapshot_document(code=bytes([0x0F, 0x0B]))  # ud2
        report = mo.reproduce(mo.load_snapshot(document,
                                               expected_executable=None))
        self.assertEqual("failed", report["status"],
                         "an invalid instruction was stepped over")


class BneMicroOracleExplorationTest(unittest.TestCase):
    """Phase 1: the alternate outcome is discovered, not supplied."""

    def test_an_alternate_outcome_is_found_without_being_told_where(self):
        exploration = mo.explore(load(counter=3, limit=9, reach=4))
        self.assertTrue(exploration["alternate_outcome_found"],
                        "the explorer never found an input that changes the "
                        "function's answer, which is the one thing it is for")
        self.assertEqual(2, exploration["distinct_outcomes"])

    def test_every_boundary_is_the_exact_value_the_answer_changes_at(self):
        exploration = mo.explore(load(counter=3, limit=9, reach=4))
        boundaries = {item["input"]: (item["holds_through"], item["changes_at"])
                      for item in exploration["boundaries"]}
        self.assertEqual({"counter": (5, 6), "limit": (7, 6), "reach": (6, 7)},
                         boundaries,
                         "the thresholds found are not the ones the function "
                         "actually turns on")
        for item in exploration["boundaries"]:
            self.assertTrue(item["confirmed_by_replay"],
                            "a boundary was reported without running the "
                            "native code at it")

    def test_a_threshold_beyond_the_probe_set_is_still_found(self):
        # The captured value is far from the boundary, so neighbours of it
        # find nothing and only the doubling search can.
        exploration = mo.explore(load(counter=0, limit=4000, reach=0))
        found = {item["input"] for item in exploration["boundaries"]}
        self.assertIn("counter", found,
                      "a threshold four thousand away from the captured value "
                      "was never found, so any function whose capture sits "
                      "far from its boundary would report no decision at all")

    def test_exploration_is_deterministic(self):
        snapshot = load(counter=3, limit=9, reach=4)
        first = mo.explore(snapshot)
        second = mo.explore(snapshot)
        self.assertEqual(
            json.dumps(first["boundaries"], sort_keys=True),
            json.dumps(second["boundaries"], sort_keys=True),
            "two explorations of one snapshot disagreed, so no run of this "
            "tool could be reproduced")
        self.assertEqual([example["label"] for example in first["examples"]],
                         [example["label"] for example in second["examples"]])


class BneMicroOracleRuleTest(unittest.TestCase):
    """Phase 1 and 2: a rule that predicts, or an honest refusal."""

    def test_the_rule_recovered_is_the_one_the_function_computes(self):
        snapshot = load(counter=3, limit=9, reach=4)
        rule = mo.synthesize_rule(snapshot, mo.explore(snapshot))
        self.assertEqual("synthesized", rule["status"])
        self.assertEqual("wrap(counter+reach)-limit", rule["rule"]["feature"],
                         f"the rule found is over the wrong quantity: "
                         f"{rule['readable']}")
        self.assertTrue(rule["held_out_validation"]["passed"],
                        "the rule failed on invocations it was not fitted to")
        self.assertEqual("predictive-and-unique", rule["confidence"])

    def test_rules_that_differ_only_in_wording_are_one_rule(self):
        snapshot = load(counter=3, limit=9, reach=4)
        rule = mo.synthesize_rule(snapshot, mo.explore(snapshot))
        self.assertGreater(rule["equivalent_forms"], 1,
                           "this fixture is meant to have several equivalent "
                           "spellings, so the grouping below proves nothing")
        self.assertEqual(1, rule["distinct_behaviours"])
        self.assertFalse(rule["ambiguous"],
                         "four spellings of one predicate were reported as an "
                         "ambiguity no experiment could resolve")

    def test_a_three_outcome_function_gets_a_two_predicate_rule(self):
        snapshot = load(counter=3, limit=9, reach=4, code=nested_code())
        rule = mo.synthesize_rule(snapshot, mo.explore(snapshot))
        self.assertEqual("synthesized-decision-list", rule["status"])
        self.assertEqual("counter", rule["decision_list"][0]["feature"])
        self.assertEqual(8, rule["decision_list"][0]["threshold"],
                         f"the guard recovered is not the one the function "
                         f"has: {rule['readable']}")
        self.assertTrue(rule["held_out_validation"]["passed"])

    def test_a_rule_that_fits_the_examples_is_refuted_by_the_native_code(self):
        snapshot = load(counter=3, limit=9, reach=4, code=nested_code())
        rule = mo.synthesize_rule(snapshot, mo.explore(snapshot))
        refutations = [item for item in rule["counterexamples"]
                       if item.get("source") == "decision-list probe"]
        self.assertTrue(refutations,
                        "the greedy learner's first guard fits every example "
                        "and is still wrong, so a run that recorded no "
                        "refutation never asked the function about it")
        self.assertNotEqual(refutations[0]["predicted"],
                            refutations[0]["native"])

    def test_a_function_with_one_outcome_reports_no_decision(self):
        document = snapshot_document(code=bytes([0xB8, 0x07, 0x00, 0x00, 0x00,
                                                 0xC3]))  # mov eax,7; ret
        snapshot = mo.load_snapshot(document, expected_executable=None)
        rule = mo.synthesize_rule(snapshot, mo.explore(snapshot))
        self.assertEqual("single-outcome", rule["status"],
                         "a function that always answers the same thing was "
                         "given a rule explaining a decision it never makes")
        self.assertIsNone(rule["rule"])


class BneMicroOracleRunTest(unittest.TestCase):
    """The durable run: addressed by its inputs, checked on the way back."""

    def write(self, directory: Path, **kwargs) -> Path:
        path = directory / "snapshot.json"
        path.write_text(json.dumps(snapshot_document(**kwargs), indent=2,
                                   sort_keys=True) + "\n")
        return path

    def test_a_run_is_content_addressed_and_reused(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            path = self.write(directory)
            status, run_root = mo.run_micro_oracle(
                path, directory / "artifacts", expected_executable=None)
            self.assertEqual(0, status)
            manifest = json.loads((run_root / "manifest.json").read_text())
            for relative, expected in manifest["artifacts"].items():
                self.assertEqual(expected,
                                 bne_triage.file_identity(run_root / relative),
                                 f"the manifest does not describe {relative}")
            repeated = mo.run_micro_oracle(
                path, directory / "artifacts", expected_executable=None)
            self.assertEqual((status, run_root), repeated,
                             "the same snapshot produced a second run rather "
                             "than the same content-addressed one")

    def test_a_changed_analysis_does_not_return_a_stale_run(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            path = self.write(directory)
            _status, first = mo.run_micro_oracle(
                path, directory / "artifacts", expected_executable=None)
            _status, second = mo.run_micro_oracle(
                path, directory / "artifacts", outcome_key="path",
                expected_executable=None)
            self.assertNotEqual(first, second,
                                "asking a different question returned the "
                                "answer to the previous one")

    def test_every_promised_artifact_is_written(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            _status, run_root = mo.run_micro_oracle(
                self.write(directory), directory / "artifacts",
                expected_executable=None)
            for name in ("manifest.json", "concrete-replay.json",
                         "exploration.json", "rule-space.json",
                         "held-out-validation.json", "snapshot.json",
                         "NEXT.md"):
                self.assertTrue((run_root / name).is_file(),
                                f"the run wrote no {name}")

    def test_a_snapshot_that_cannot_reproduce_exits_as_evidence_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            document = snapshot_document()
            document["expected"] = {"registers": {"eax": 999}}
            path = directory / "snapshot.json"
            path.write_text(json.dumps(document, indent=2, sort_keys=True) + "\n")
            status, run_root = mo.run_micro_oracle(
                path, directory / "artifacts", expected_executable=None)
            self.assertEqual(2, status,
                             "a snapshot that did not reproduce exited as "
                             "though its rule could be believed")
            self.assertIn("did not reproduce",
                          (run_root / "NEXT.md").read_text())

    def test_the_report_never_calls_a_measurement_a_proof(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            _status, run_root = mo.run_micro_oracle(
                self.write(directory), directory / "artifacts",
                expected_executable=None)
            report = (run_root / "NEXT.md").read_text()
            self.assertIn("not a proof of what the function means", report)
            self.assertIn("regression gate remains the acceptance authority",
                          report)

    def test_the_parser_offers_the_micro_oracle_as_a_supported_command(self):
        arguments = bne_java.parser().parse_args(
            ["micro-oracle", "snapshot.json", "--synthetic"])
        self.assertEqual(bne_java.micro_oracle_command, arguments.func)
        self.assertEqual("eax", arguments.outcome)
        self.assertTrue(arguments.synthetic)


class BneMicroOracleIsolationTest(unittest.TestCase):
    """What this tool must never do to the engine or the parity frontier."""

    def test_the_backend_is_project_local_rather_than_installed(self):
        requirements = Path(__file__).parents[1] / "micro-oracle-requirements.txt"
        self.assertTrue(requirements.is_file(),
                        "the emulator's pins are not written down, so a fresh "
                        "clone cannot rebuild the backend")
        text = requirements.read_text()
        self.assertIn("unicorn==", text)
        self.assertIn("capstone==", text)

    def test_no_production_engine_file_is_touched_by_this_tool(self):
        source = (Path(__file__).parents[1] / "scripts"
                  / "bne_micro_oracle.py").read_text()
        for forbidden in ("engine/src/main", "World.java", ".bne-artifacts",
                          "latest-accepted"):
            self.assertNotIn(forbidden, source,
                             f"the micro-oracle names {forbidden}, and it has "
                             f"no business reading or writing it")


if __name__ == "__main__":
    unittest.main()


class BneMicroOracleCapturePlanTest(unittest.TestCase):
    """Phase 3: what a historical decision would need to become replayable."""

    ARTIFACT = {
        "case": "retail-example-idle", "cycle": 23,
        "focus": {"native_slot": 1553, "java_id": 47},
        "top_branch": {"address": 0x00437646, "instruction": "jg 0x437663",
                       "taken": True, "operands": None},
        "writer": {"address": 0x00402451, "field": "animation_timer",
                   "before": 3, "after": 2,
                   "instruction_text": "mov %cl,0x7(%esi)"},
    }

    def test_a_branch_witness_result_names_the_decision_to_capture(self):
        plan = mo.plan_from_branch_witness(self.ARTIFACT)
        self.assertTrue(plan["supported"])
        self.assertEqual("0x00437646", plan["candidate_entry_hex"],
                         "the branch the historical run localized is not the "
                         "one the capture plan asks for")

    def test_the_plan_says_exactly_what_the_evidence_lacks(self):
        plan = mo.plan_from_branch_witness(self.ARTIFACT)
        self.assertEqual(["registers", "stack", "code", "data", "outcome"],
                         [item["part"] for item in plan["evidence_missing"]],
                         "the plan does not enumerate what a snapshot needs, "
                         "so a reader cannot tell whether the gap is small")
        for item in plan["evidence_missing"]:
            self.assertTrue(item["why"],
                            f"{item['part']} is listed as missing with no "
                            f"account of why it is needed")

    def test_evidence_without_a_branch_is_declined_with_a_fallback(self):
        plan = mo.plan_from_branch_witness({"case": "x", "top_branch": {}})
        self.assertFalse(plan["supported"],
                         "an artifact that localized nothing produced a "
                         "capture plan anyway")
        self.assertIn("decision-plan", plan["fallback_route"],
                      "an unsupported case was left with no route at all")

    def test_no_snapshot_is_fabricated_from_incomplete_evidence(self):
        plan = mo.plan_from_branch_witness(self.ARTIFACT)
        self.assertNotIn("registers", plan.get("snapshot", {}),
                         "a snapshot was invented from evidence that carries "
                         "no registers, which would replay something that "
                         "never ran")
        self.assertIsNone(plan.get("snapshot"))

    def test_remote_capture_is_dry_run_and_isolated_by_default(self):
        capture = mo.remote_capture_plan(0x00437646, case="retail-example-idle")
        self.assertTrue(capture["dry_run"],
                        "a capture against the shared oracle would have run "
                        "without anybody asking for it")
        self.assertFalse(capture["isolation"]["shared_with_other_agents"])
        self.assertTrue(capture["isolation"][
            "network_disabled_during_native_execution"])
        self.assertTrue(capture["isolation"]["original_bytes_validated_before_hook"])
        self.assertEqual(mo.BNE_202_SHA256, capture["executable_sha256"],
                         "the capture plan does not pin the executable it "
                         "would attach to")

    def test_the_remote_directory_is_content_addressed_and_unshared(self):
        first = mo.remote_capture_plan(0x00437646, case="case-a")
        second = mo.remote_capture_plan(0x00437646, case="case-b")
        self.assertNotEqual(first["output_directory"],
                            second["output_directory"],
                            "two captures would have written to one "
                            "directory, which is how a shared oracle loses a "
                            "result to whoever finished second")
        self.assertIn("chonkcraft-bne-micro-oracle", first["output_directory"])

    def test_the_capture_verifies_what_comes_back(self):
        capture = mo.remote_capture_plan(0x00437646, case="case-a")
        joined = " ".join(capture["verification_on_return"])
        self.assertIn("pinned", joined)
        self.assertIn("sha256", joined)
        self.assertIn("reproduces the captured outcome", joined)

    def test_the_planner_is_a_supported_command(self):
        arguments = bne_java.parser().parse_args(
            ["micro-oracle-plan", "branch-witness.json"])
        self.assertEqual(bne_java.micro_oracle_plan_command, arguments.func)
        self.assertFalse(arguments.execute,
                         "the planner defaults to executing a capture against "
                         "the shared oracle")
