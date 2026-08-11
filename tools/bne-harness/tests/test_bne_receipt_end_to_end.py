import json
from pathlib import Path
import shlex
import struct
import sys
import tempfile
import unittest
import zipfile

SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_evidence
import bne_fixture
import bne_frontier
import bne_java
import bne_packet
import bne_router


def chunk(tag, payload):
    return bne_packet.CHUNK_HEADER.pack(tag, len(payload)) + payload


def unit(slot, x, y, order=321):
    raw = bytearray(152)
    raw[0:2] = slot.to_bytes(2, "little")
    raw[0x18:0x1a] = x.to_bytes(2, "little")
    raw[0x1a:0x1c] = y.to_bytes(2, "little")
    raw[0x28:0x2a] = (60).to_bytes(2, "little")
    raw[0x4c:0x4e] = order.to_bytes(2, "little")
    return bytes(raw)


def fixture_state():
    players = b"".join(
        bne_fixture.PLAYER_RECORD.pack(
            0 if player == 1 else 3, 1000 if player == 1 else 0,
            500 if player == 1 else 0, 0)
        for player in range(16))
    player_sim = b"".join(
        bne_fixture.PLAYER_SIM_RECORD.pack(*([0] * 22)) for _ in range(16))
    map_deltas = b"".join(
        bne_fixture.MAP_DELTA.pack(index, index, 1) for index in range(16 * 16))
    body = []
    for cycle, x in ((1, 10), (2, 11)):
        body.append(chunk(b"CYCL", b"".join((
            bne_fixture.CYCLE_HEADER.pack(cycle, 1, 1, 1), players,
            bne_fixture.UNIT_DELTA_HEADER.pack(0, 1), unit(0, x, 10)))))
        aux = [bne_fixture.AUX_HEADER.pack(
            cycle, 0, 0, 16, 16 * 16 if cycle == 1 else 0), player_sim]
        if cycle == 1:
            aux.append(map_deltas)
        body.append(chunk(b"AUXL", b"".join(aux)))
    body.append(chunk(b"DONE", bne_fixture.DONE_RECORD.pack(2)))
    return b"".join((
        bne_fixture.STATE_HEADER.pack(
            b"BNESTATE", 1, 1, bne_fixture.STATE_HEADER.size, 152, 1600, 16, 15),
        *body))


NATIVE_TRACE = """\
cycle 1 seed 00000001
p 1 gold 1000 wood 500 oil 0
u 0 unit-grunt p1 10 10 hp 60 o MOVE
cycle 2 seed 00000001
p 1 gold 1000 wood 500 oil 0
u 0 unit-grunt p1 11 10 hp 60 o MOVE
"""

JAVA_TRACE = """\
cycle 1 seed 00000001
p 1 gold 1000 wood 500 oil 0
u 7 unit-grunt p1 10 10 hp 60 o MOVE px 320 320
cycle 2 seed 00000001
p 1 gold 1000 wood 500 oil 0
u 7 unit-grunt p1 10 10 hp 60 o MOVE px 320 320
"""


class ReceiptToForensicFrameTest(unittest.TestCase):
    """Seal a gate receipt, let it be promoted, then compile a real frame.

    Everything here crosses the `os.replace` that renames the staging directory
    into the sealed receipt. The earlier evidence tests asserted against the
    staging directory itself and so never saw that the retained survey pointed
    at a `.gate-acceptance-*` path which stops existing at exactly that moment.
    """

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="bne-end-to-end-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.work = self.root / "work"
        self.work.mkdir()

        self.fixture = self.work / "orc-08.bnefx"
        with zipfile.ZipFile(self.fixture, "w") as archive:
            archive.writestr("manifest.json", json.dumps(
                {"fixture": {"id": "fixture-orc-08"}}))
            archive.writestr("trace.txt", NATIVE_TRACE)
            archive.writestr("state.bin", fixture_state())
        self.index = self.work / "corpus-index.json"
        self.index.write_text(json.dumps({
            "schema": 1,
            "cases": [{
                "id": "orc-08", "fixture_id": "fixture-orc-08",
                "fixture": {"path": self.fixture.name,
                            **bne_packet.file_identity(self.fixture)},
            }],
        }), encoding="utf-8")

        self.trace = self.work / "orc-08.java.trace.txt"
        self.trace.write_text(JAVA_TRACE, encoding="utf-8")
        self.stdout = self.work / "orc-08.java.stdout.txt"
        self.stdout.write_text("trace complete\n", encoding="utf-8")

        self.artifacts = self.root / ".bne-artifacts"
        self.evidence_root = self.root / ".bne-frontier-evidence"

    def survey(self):
        record = {
            "id": "orc-08", "fixture_id": "fixture-orc-08",
            "scenario": r"Campaign\Orc\Orc08.pud",
            "java_map": "campaigns/orc/level08o", "seed": 1,
            "comparison_tier": "semantic-v1", "state": "divergent",
            "cycles": 2, "compared_cycles": 2, "first_divergence_cycle": 2,
            "state_schema": "1.1",
            "findings": [{
                "cycle": 2, "kind": "unit", "unit": 0,
                "unit_type": "unit-grunt", "field": "x",
                "oracle": 11, "java": 10,
                "message": "unit 0 (unit-grunt) x 11 vs 10",
            }],
            "java_trace": {"path": str(self.trace),
                           **bne_packet.file_identity(self.trace)},
            "java_process_output": {
                "stdout": {"path": str(self.stdout),
                           **bne_packet.file_identity(self.stdout)},
            },
            "comparison_output": "first divergence at cycle 2 (1 finding(s))",
        }
        return {
            "schema": 1, "comparison_tier": "semantic-v1", "through": 2,
            "engine": {"schema": 2, "policy": "engine-input-v1",
                       "head": "a" * 40, "dirty": False,
                       "engine_input_sha256": "b" * 64, "input_count": 1},
            "asset_source": {"kind": "test"},
            "index": str(self.index),
            "counts": {"clean": 0, "divergent": 1, "failed": 0},
            "cases": [record],
        }

    def seal_receipt(self):
        """Write a real gate receipt, through the real promotion rename."""
        survey_path = self.root / "candidate-survey.json"
        survey_path.write_text(json.dumps(self.survey()), encoding="utf-8")
        candidate = json.loads(survey_path.read_text(encoding="utf-8"))
        gate = bne_java.evaluate_gate([candidate], candidate)
        receipt = bne_java._record_gate_acceptance(
            survey_path, [survey_path], candidate, gate, self.artifacts)
        return Path(receipt["run"]), receipt

    def test_a_sealed_receipt_still_finds_the_bytes_it_retained(self):
        run_root, _receipt = self.seal_receipt()
        manifest = json.loads(
            (run_root / "manifest.json").read_text(encoding="utf-8"))
        record = manifest["blocker_evidence"]["blockers"][0]
        self.assertEqual("retained", record["state"])
        resolved = bne_evidence.resolve_retained_survey(
            run_root / "inputs" / record["survey"], run_root / "inputs")
        trace = Path(resolved["cases"][0]["java_trace"]["path"])
        self.assertTrue(
            trace.is_file(),
            f"the promoted receipt points at a path that does not exist: {trace}",
        )
        self.assertTrue(
            trace.is_relative_to(run_root),
            f"a retained path escaped the sealed receipt: {trace}",
        )
        self.assertNotIn(
            ".gate-acceptance-", str(trace),
            "the retained survey still names the staging directory",
        )
        self.assertEqual(JAVA_TRACE, trace.read_text(encoding="utf-8"))

    def test_no_retained_path_survives_promotion_as_an_absolute_path(self):
        run_root, _receipt = self.seal_receipt()
        sealed = json.loads((run_root / "inputs" / "blockers" / "orc-08"
                             / "survey.json").read_text(encoding="utf-8"))
        recorded = sealed["cases"][0]["java_trace"]
        self.assertEqual(bne_evidence.PATH_BASE, recorded["path_base"])
        self.assertFalse(
            Path(recorded["path"]).is_absolute(),
            f"the retained survey sealed an absolute path: {recorded['path']}",
        )

    def test_compiling_a_sealed_receipt_produces_a_real_forensic_frame(self):
        """The end-to-end case: seal, promote, compile, get a frame."""
        run_root, _receipt = self.seal_receipt()
        status = bne_frontier.compile_evidence(
            run_root / "manifest.json",
            artifact_root=self.artifacts, output_root=self.evidence_root,
            repository=self.root)
        blocker = status["blockers"][0]
        self.assertEqual(
            "complete", blocker["frame"],
            f"the frame was blocked after promotion: {blocker.get('missing')}",
        )
        self.assertEqual("retained-blocker-evidence", blocker["evidence_source"])
        compiled = self.evidence_root / "runs" / status["request_sha256"]
        readme = compiled / blocker["packet"] / "README.md"
        self.assertTrue(readme.is_file(),
                        "the packet generator wrote no frame")
        self.assertIn(
            "unit 0", (compiled / blocker["packet_json"]).read_text(
                encoding="utf-8"),
            "the compiled frame does not describe the divergent unit",
        )

    def test_the_survey_the_generator_read_is_kept_beside_the_frame(self):
        run_root, _receipt = self.seal_receipt()
        status = bne_frontier.compile_evidence(
            run_root / "manifest.json",
            artifact_root=self.artifacts, output_root=self.evidence_root,
            repository=self.root)
        compiled = self.evidence_root / "runs" / status["request_sha256"]
        resolved = compiled / "blockers" / "orc-08" / "resolved-survey.json"
        self.assertTrue(resolved.is_file(),
                        "the resolved survey was not retained as evidence")
        manifest = json.loads(
            (compiled / "manifest.json").read_text(encoding="utf-8"))
        self.assertIn("blockers/orc-08/resolved-survey.json",
                      manifest["artifacts"])

    def _commands(self, value) -> list[str]:
        """Collect every command string anywhere in a compiled work order."""
        found: list[str] = []
        if isinstance(value, dict):
            for key, item in value.items():
                if key in ("command", "recovery") and isinstance(item, str):
                    found.append(item)
                else:
                    found.extend(self._commands(item))
        elif isinstance(value, list):
            for item in value:
                found.extend(self._commands(item))
        return found

    def _parse(self, command: str):
        arguments = shlex.split(command)
        stripped = [item for item in arguments[2:] if not item.startswith("#")]
        try:
            return bne_java.parser().parse_args(stripped)
        except SystemExit as failure:
            self.fail(f"the parser rejected a compiled command: {command!r} "
                      f"(exit {failure.code})")

    def test_every_compiled_command_still_names_a_file_that_exists(self):
        """Compile for real, then run what it printed against the filesystem.

        A command used to carry the `.frontier-*` staging directory the
        compiler built it in. `os.replace` renames that directory away at the
        end of the compile, so the command parsed cleanly and then could not
        run, because the path it named had never existed under that name once
        anyone was able to read it. Parsing alone would not have caught this,
        which is why this crosses the real publication rename first.
        """
        run_root, _receipt = self.seal_receipt()
        status = bne_frontier.compile_evidence(
            run_root / "manifest.json",
            artifact_root=self.artifacts, output_root=self.evidence_root,
            repository=self.root)
        compiled = self.evidence_root / "runs" / status["request_sha256"]
        published = json.loads(
            (compiled / "STATUS.json").read_text(encoding="utf-8"))

        commands = self._commands(published)
        self.assertGreater(len(commands), 10,
                           f"the compiled work order printed only "
                           f"{len(commands)} commands, so this swept nothing")

        placeholders = set(bne_router.PLACEHOLDERS.values())
        # The compiler resolves its own paths, and on macOS that turns
        # /var/folders into /private/var/folders, so the run root has to be
        # resolved too or this matches nothing and passes vacuously.
        evidence_root = str(self.evidence_root.resolve())
        checked_paths = 0
        for command in commands:
            self.assertNotIn(
                ".frontier-", command,
                f"a published command names the staging directory that the "
                f"publication rename deleted: {command}")
            self._parse(command)
            for token in shlex.split(command):
                if token in placeholders or not token.startswith("/"):
                    continue
                if not token.startswith(evidence_root):
                    continue
                self.assertTrue(
                    Path(token).exists(),
                    f"a published command names a file that does not exist "
                    f"after publication: {token}",
                )
                checked_paths += 1
        self.assertGreater(
            checked_paths, 0,
            "no compiled command carried a concrete path, so the check that "
            "those paths outlive publication proved nothing")

    def test_a_retained_trace_that_was_tampered_with_is_refused(self):
        run_root, _receipt = self.seal_receipt()
        (run_root / "inputs" / "blockers" / "orc-08" / "java.trace.txt") \
            .write_text("rewritten\n", encoding="utf-8")
        with self.assertRaises(bne_evidence.RetainedEvidenceError) as caught:
            bne_evidence.resolve_retained_survey(
                run_root / "inputs" / "blockers" / "orc-08" / "survey.json",
                run_root / "inputs")
        self.assertIn("does not match the identity", str(caught.exception))

    def test_a_retained_path_climbing_out_of_the_receipt_is_refused(self):
        run_root, _receipt = self.seal_receipt()
        survey_path = (run_root / "inputs" / "blockers" / "orc-08"
                       / "survey.json")
        sealed = json.loads(survey_path.read_text(encoding="utf-8"))
        sealed["cases"][0]["java_trace"]["path"] = "../../../../etc/passwd"
        survey_path.write_text(json.dumps(sealed), encoding="utf-8")
        with self.assertRaises(bne_evidence.RetainedEvidenceError):
            bne_evidence.resolve_retained_survey(
                survey_path, run_root / "inputs")

    def test_a_legacy_staging_path_resolves_to_the_bytes_beside_it(self):
        """Receipts sealed by the broken version are still readable."""
        run_root, _receipt = self.seal_receipt()
        survey_path = (run_root / "inputs" / "blockers" / "orc-08"
                       / "survey.json")
        sealed = json.loads(survey_path.read_text(encoding="utf-8"))
        trace = sealed["cases"][0]["java_trace"]
        del trace["path_base"]
        trace["path"] = ("/private/tmp/runs/.gate-acceptance-gone/inputs/"
                         "blockers/orc-08/java.trace.txt")
        stream = sealed["cases"][0]["java_process_output"]["stdout"]
        del stream["path_base"]
        stream["path"] = ("/private/tmp/runs/.gate-acceptance-gone/inputs/"
                          "blockers/orc-08/java.stdout.txt")
        survey_path.write_text(json.dumps(sealed), encoding="utf-8")
        resolved = bne_evidence.resolve_retained_survey(
            survey_path, run_root / "inputs")
        recovered = Path(resolved["cases"][0]["java_trace"]["path"])
        self.assertTrue(recovered.is_file())
        self.assertEqual(JAVA_TRACE, recovered.read_text(encoding="utf-8"))


class GeneratedCommandsParseTest(unittest.TestCase):
    """Every command this pipeline prints must survive the real parser.

    The recovery instruction used to read `survey --index PATH`, but `survey`
    takes its index positionally, so argparse rejected it outright. An
    unrunnable command is worse than none: it reads as though it was checked.
    """

    def parse(self, command):
        arguments = shlex.split(command)
        self.assertEqual(
            "python3", arguments[0], f"not a python invocation: {command}")
        self.assertTrue(
            arguments[1].endswith("bne_java.py"),
            f"not a harness command: {command}")
        stripped = [item for item in arguments[2:]
                    if not item.startswith("#")]
        try:
            return bne_java.parser().parse_args(stripped)
        except SystemExit as failure:
            self.fail(f"the parser rejected a generated command: {command!r} "
                      f"(exit {failure.code})")

    def test_the_evidence_recovery_command_parses(self):
        command = bne_evidence._recovery_command(
            "retail-xorc-08-idle", Path("/tmp/corpus/corpus-index.json"))
        parsed = self.parse(command)
        self.assertEqual("survey", parsed.func.__name__.replace("_command", ""))
        self.assertEqual(["retail-xorc-08-idle"], parsed.case)
        self.assertEqual(Path("/tmp/corpus/corpus-index.json"), parsed.index)

    def test_a_recovery_command_with_an_awkward_path_parses(self):
        command = bne_evidence._recovery_command(
            "case with spaces", Path("/tmp/a b/corpus index.json"))
        parsed = self.parse(command)
        self.assertEqual(Path("/tmp/a b/corpus index.json"), parsed.index)
        self.assertEqual(["case with spaces"], parsed.case)

    def test_every_routed_command_parses(self):
        findings = {
            "position": [{"cycle": 44, "kind": "unit", "field": "x",
                          "unit": 1, "unit_type": "unit-human-submarine"}],
            "order": [{"cycle": 44, "kind": "unit", "field": "order",
                       "unit": 2, "unit_type": "unit-gryphon-rider"}],
            "hp": [{"cycle": 44, "kind": "unit", "field": "hp",
                    "unit": 3, "unit_type": "unit-ogre"}],
            "sync": [{"cycle": 44, "kind": "sync_rng"}],
            "bank": [{"cycle": 44, "kind": "player_bank", "player": 1}],
        }
        shapes = (
            None,
            {"applicable": True, "direction": "falling", "cadence_agrees": True,
             "change_count_agrees": True, "values_differ": True,
             "randomized_damage_suspected": True},
            {"applicable": True, "direction": "mixed", "cadence_agrees": False,
             "change_count_agrees": False, "values_differ": True,
             "randomized_damage_suspected": False},
        )
        checked = 0
        for name, items in findings.items():
            for shape in shapes:
                for capabilities in ({}, {"native_traces": {"c": "/t/c.txt"}}):
                    route = bne_router.route_blocker(
                        {"case": "c", "cycle": 44,
                         "first_divergence_cycle": 44,
                         "findings": items, "state": "retained"},
                        packet_path="blockers/c/packet",
                        hp_evidence=shape, capabilities=capabilities)
                    for step in route["steps"]:
                        for key in ("command", "recovery"):
                            if step.get(key):
                                self.parse(step[key])
                                checked += 1
        self.assertGreater(
            checked, 40,
            f"the routed-command sweep only checked {checked} commands")

    def test_every_command_in_a_compiled_work_order_parses(self):
        """Sweep the real artifact, not just the router that helped build it."""
        import bne_whychain

        blockers = [{
            "case": "c", "cycle": 44, "first_divergence_cycle": 44,
            "state": "retained",
            "findings": [{"cycle": 44, "kind": "unit", "field": "x",
                          "unit": 1433, "unit_type": "unit-human-submarine",
                          "oracle": 102, "java": 100}],
        }, {
            "case": "d", "cycle": 44, "first_divergence_cycle": 44,
            "state": "retained",
            "findings": [{"cycle": 44, "kind": "unit", "field": "hp",
                          "unit": 1482, "unit_type": "unit-ogre",
                          "oracle": 90, "java": 85}],
        }]
        contexts = {"c": {"fixture": "/corpus/c.bnefx",
                          "java_trace": "/work/c.java.trace.txt",
                          "packet": "/evidence/c/packet.json",
                          "native_unit": 1433}}
        routing = bne_router.route_all(
            blockers, packets={"c": "blockers/c/packet"}, contexts=contexts)
        why = bne_whychain.build_why_chain(
            blockers, routing, {"state": "legacy-no-capsule"}, contexts)
        checked = 0
        for route in routing["routes"]:
            for step in route["steps"]:
                for key in ("command", "recovery"):
                    if step.get(key):
                        self.parse(step[key])
                        checked += 1
        for chain in why["chains"]:
            for link in chain["chain"]:
                if link.get("command"):
                    self.parse(link["command"])
                    checked += 1
        self.assertGreater(
            checked, 20,
            f"the work-order sweep only checked {checked} commands")

    def test_a_planned_command_declares_what_must_be_substituted(self):
        route = bne_router.route_blocker(
            {"case": "c", "cycle": 44, "first_divergence_cycle": 44,
             "state": "retained",
             "findings": [{"cycle": 44, "kind": "unit", "field": "x",
                           "unit": 7}]},
            packet_path="blockers/c/packet")
        for step in route["steps"]:
            command = step.get("command")
            if not command:
                continue
            placeholders = [name for name in bne_router.PLACEHOLDERS.values()
                            if name in command]
            self.assertEqual(
                sorted(placeholders), sorted(step.get("requires_input", [])),
                f"lane {step['lane']} does not declare its substitutions: "
                f"{command}",
            )

    def test_the_capsule_and_compile_commands_parse(self):
        for command in (
                f"{bne_frontier.HARNESS} capsule verify /tmp/capsule",
                f"{bne_frontier.HARNESS} capsule replay /tmp/capsule",
                f"{bne_frontier.HARNESS} capsule seal /tmp/capsule",
                f"{bne_frontier.HARNESS} frontier-compile",
                f"{bne_frontier.HARNESS} identity"):
            self.parse(command)

    def test_the_legacy_capsule_recovery_command_parses(self):
        import bne_capsule

        state = bne_capsule.legacy_state({"head": "abc"})
        parsed = self.parse(state["recovery"])
        self.assertEqual(Path("CANDIDATE_SURVEY.json"), parsed.candidate)
        self.assertEqual(
            ["CANDIDATE_SURVEY.json", "BASELINE_SURVEY.json"],
            state["requires_input"],
            "the legacy recovery does not declare what must be substituted",
        )


class CapsuleFailureIsLoudTest(unittest.TestCase):
    """A swallowed capsule failure must never read as a replayable proof."""

    def test_a_failed_capsule_is_reported_as_not_replayable(self):
        evidence = {
            "schema": bne_evidence.EVIDENCE_SCHEMA, "blockers": [],
            "retained_count": 0, "unavailable_count": 0,
            "source_capsule": {
                "state": "capsule-failed", "replayable": False,
                "reason": "refusing to seal a symlink as source: engine/A.java",
                "recovery": "python3 tools/bne-harness/scripts/bne_java.py "
                            "capsule seal .bne-frontier-evidence/capsule-recheck",
            },
            "source_capsule_failure": "refusing to seal a symlink as source",
        }
        described = bne_evidence.describe(evidence)
        self.assertIn("NOT", described)
        self.assertIn("replayable", described)
        self.assertIn("symlink", described)

    def test_a_missing_capsule_is_reported_too(self):
        described = bne_evidence.describe({
            "schema": bne_evidence.EVIDENCE_SCHEMA, "blockers": [],
            "retained_count": 0, "unavailable_count": 0,
            "source_capsule": None, "source_capsule_failure": None,
        })
        self.assertIn("NOT", described)

    def test_the_compiler_does_not_call_a_failed_capsule_legacy(self):
        manifest = {
            "request": {"engine": {"head": "a" * 40}},
            "blocker_evidence": {
                "source_capsule": None,
                "source_capsule_failure": "the declared engine inputs exceed "
                                          "the capsule ceiling",
            },
        }
        with tempfile.TemporaryDirectory() as directory:
            state = bne_frontier._capsule_state(Path(directory), manifest)
        self.assertEqual("capsule-failed", state["state"])
        self.assertFalse(state["replayable"])
        self.assertIn("ceiling", state["reason"])
        self.assertNotIn("predates", state.get("reason", ""))


if __name__ == "__main__":
    unittest.main()
