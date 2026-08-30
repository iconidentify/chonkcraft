import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest import mock

SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_frontier


def identity(path):
    data = Path(path).read_bytes()
    return {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def write_json(path, value):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(json.dumps(value, indent=2, sort_keys=True) + "\n",
                          encoding="utf-8")


class ReceiptFixture:
    """A gate receipt on disk, built in code, with no committed fixture."""

    def __init__(self, root, *, retain=True, through=43, open_cycle=44):
        self.root = Path(root)
        self.artifacts = self.root / ".bne-artifacts"
        self.runs = self.artifacts / "runs"
        self.through = through
        self.open_cycle = open_cycle
        self.retain = retain
        self.build()

    def survey_record(self):
        return {
            "id": "orc-08", "fixture_id": "fixture-orc-08", "state": "divergent",
            "compared_cycles": 80, "first_divergence_cycle": self.open_cycle,
            "findings": [{
                "cycle": self.open_cycle, "kind": "unit", "field": "x",
                "unit": 1433, "unit_type": "unit-human-submarine",
                "oracle": 102, "java": 100,
                "message": "unit 1433 (unit-human-submarine) x 102 vs 100",
            }],
            "java_trace": {"path": str(self.trace), **identity(self.trace)},
            "java_process_output": {},
        }

    def build(self):
        self.work = self.root / "work"
        self.work.mkdir(parents=True, exist_ok=True)
        self.trace = self.work / "orc-08.java.trace.txt"
        self.trace.write_text("cycle 44\n", encoding="utf-8")
        self.index = self.work / "corpus-index.json"
        write_json(self.index, {"schema": 1, "cases": []})
        self.pack = self.work / "bne.chonkpack"
        self.pack.write_bytes(b"authenticated test pack\n")

        survey = {
            "schema": 1, "comparison_tier": "semantic-v1", "through": 80,
            "engine": {"head": "a" * 40, "dirty": True,
                       "workspace_sha256": "b" * 64},
            "asset_source": {
                "kind": "chonkpack", "path": str(self.pack),
                **identity(self.pack),
            },
            "runtime": {"source_dir": str(self.root / "chonkcraft")},
            "index": str(self.index),
            "cases": [self.survey_record()],
        }
        self.request_sha = "0" * 64
        self.run_root = self.runs / self.request_sha
        inputs = self.run_root / "inputs"
        write_json(inputs / "candidate-survey.json", survey)
        blockers = [{
            "case": "orc-08", "cycle": self.open_cycle, "rank": 1,
            "recommended_tool": "cadence",
        }]
        manifest = {
            "schema": 1, "kind": "gate-acceptance",
            "request_sha256": None,
            "created_at": "2026-08-04T00:00:00+00:00",
            "request": {"kind": "direct-gate-acceptance", "schema": 1,
                        "engine": survey["engine"]},
            "candidate": {
                "survey": "inputs/candidate-survey.json",
                "identity": identity(inputs / "candidate-survey.json"),
            },
            "frontier": {
                "common_clean_through": self.through,
                "earliest_divergence_cycle": self.open_cycle,
                "counts": {"clean": 1, "divergent": 1, "failed": 0},
                "tied_blockers": blockers,
            },
            "packets": [], "clusters": [],
            "artifacts": {
                "inputs/candidate-survey.json":
                    identity(inputs / "candidate-survey.json"),
            },
        }
        if self.retain:
            retained_dir = inputs / "blockers" / "orc-08"
            shutil.copyfile(self.trace, retained_dir_file := (
                retained_dir / "java.trace.txt")) if retained_dir.mkdir(
                parents=True, exist_ok=True) is None else None
            retained_survey = dict(survey)
            # Model a receipt made before retained surveys preserved runtime.
            # The compiler must recover it from the authenticated full survey.
            retained_survey.pop("runtime")
            record = dict(self.survey_record())
            record["java_trace"] = {
                "path": str(retained_dir_file.resolve()),
                **identity(retained_dir_file),
            }
            retained_survey["cases"] = [record]
            write_json(retained_dir / "survey.json", retained_survey)
            manifest["blocker_evidence"] = {
                "schema": 1,
                "blockers": [{
                    "case": "orc-08", "state": "retained",
                    "first_divergence_cycle": self.open_cycle,
                    "findings": record["findings"],
                    "survey": "blockers/orc-08/survey.json",
                }],
                "retained_count": 1, "unavailable_count": 0,
                "source_capsule": None,
            }
            manifest["artifacts"].update({
                "inputs/blockers/orc-08/java.trace.txt":
                    identity(retained_dir_file),
                "inputs/blockers/orc-08/survey.json":
                    identity(retained_dir / "survey.json"),
            })
        manifest["request_sha256"] = bne_frontier.canonical_digest(
            manifest["request"])
        self.manifest_path = self.run_root / "manifest.json"
        write_json(self.manifest_path, manifest)
        pointer = {
            "schema": 1, "kind": "gate-acceptance",
            "manifest": f"runs/{self.request_sha}/manifest.json",
            "manifest_identity": identity(self.manifest_path),
            "common_clean_through": self.through,
            "earliest_divergence_cycle": self.open_cycle,
        }
        write_json(self.artifacts / "latest-accepted.json", pointer)
        self.pointer_path = self.artifacts / "latest-accepted.json"


FAKE_PACKET = {
    "schema": 1, "case": "orc-08", "window": {"start": 40, "end": 44},
    "divergence": {"cycle": 44, "findings": [{
        "cycle": 44, "kind": "unit", "field": "x", "unit": 1433,
        "unit_type": "unit-human-submarine", "oracle": 102, "java": 100,
        "message": "unit 1433 (unit-human-submarine) x 102 vs 100"}]},
    "semantic": {},
}


def fake_generate_packet(survey_path, case_id, output_dir, **kwargs):
    """Stand in for the real generator, keeping its authentication contract.

    The real one refuses to read a trace whose sha256 has moved, and the
    compiler's behaviour when that happens is the thing under test, so the
    stand-in has to refuse for the same reason rather than always succeed.
    """
    survey = json.loads(Path(survey_path).read_text(encoding="utf-8"))
    record = next(item for item in survey["cases"] if item["id"] == case_id)
    trace = Path(record["java_trace"]["path"])
    if not trace.is_file():
        raise ValueError(f"Java trace is missing: {trace}")
    if identity(trace) != {key: record["java_trace"][key]
                           for key in ("bytes", "sha256")}:
        raise ValueError(f"Java trace identity changed: {trace}")
    Path(output_dir).mkdir(parents=True, exist_ok=True)
    (Path(output_dir) / "README.md").write_text("frame\n", encoding="utf-8")
    return dict(FAKE_PACKET)


class FrontierCompilerTest(unittest.TestCase):
    """One accepted proof in, one current routed work order out."""

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="bne-frontier-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.receipt = ReceiptFixture(self.root)
        self.output = self.root / ".bne-frontier-evidence"
        patch = mock.patch("bne_packet.generate_packet", fake_generate_packet)
        patch.start()
        self.addCleanup(patch.stop)

    def compile(self, target=None, **kwargs):
        return bne_frontier.compile_evidence(
            target if target is not None else self.receipt.pointer_path,
            artifact_root=self.receipt.artifacts,
            output_root=self.output, repository=self.root, **kwargs)

    def test_an_accepted_receipt_becomes_a_current_work_order(self):
        status = self.compile()
        self.assertEqual(43, status["frontier"]["common_clean_through"])
        blocker = status["blockers"][0]
        self.assertEqual("complete", blocker["frame"],
                         f"no forensic frame was produced: {blocker}")
        self.assertEqual("retained-blocker-evidence", blocker["evidence_source"])
        self.assertTrue(status["freshness"]["all_frames_current"],
                        "a frame at the first open cycle was not called current")

    def test_an_old_retained_survey_gets_the_recorded_source_directory(self):
        manifest = json.loads(
            self.receipt.manifest_path.read_text(encoding="utf-8"))
        candidate = self.receipt.run_root / manifest["candidate"]["survey"]
        source = bne_frontier._blocker_sources(
            self.receipt.run_root, manifest, candidate)[0]
        destination = self.root / "frame"
        with mock.patch("bne_packet.generate_packet",
                        side_effect=fake_generate_packet) as generator:
            packet, failure = bne_frontier._build_packet(
                source, destination, before=4, after=0)
        self.assertIsNone(failure)
        self.assertIsNotNone(packet)
        passed = generator.call_args.kwargs.get("source_dir")
        self.assertEqual(
            (self.root / "chonkcraft").resolve(), passed,
            "the compiler replaced a recorded source tree with a placeholder",
        )

    def test_a_transferred_receipt_can_use_an_authenticated_local_corpus(self):
        manifest = json.loads(
            self.receipt.manifest_path.read_text(encoding="utf-8"))
        candidate = self.receipt.run_root / manifest["candidate"]["survey"]
        source = bne_frontier._blocker_sources(
            self.receipt.run_root, manifest, candidate)[0]
        local_index = self.root / "transferred" / "corpus-index.json"
        write_json(local_index, {"schema": 1, "cases": []})
        local_pack = self.root / "transferred" / "bne.chonkpack"
        local_pack.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(self.receipt.pack, local_pack)
        source["corpus_index"] = local_index
        source["asset_pack"] = local_pack
        destination = self.root / "frame"

        def assert_rehomed(survey_path, case_id, output_dir, **kwargs):
            survey = json.loads(Path(survey_path).read_text(encoding="utf-8"))
            self.assertEqual(str(local_index.resolve()), survey["index"])
            self.assertEqual(
                str(local_pack.resolve()), survey["asset_source"]["path"])
            return fake_generate_packet(
                survey_path, case_id, output_dir, **kwargs)

        with mock.patch("bne_packet.generate_packet",
                        side_effect=assert_rehomed):
            packet, failure = bne_frontier._build_packet(
                source, destination, before=4, after=0)
        self.assertIsNone(failure)
        self.assertIsNotNone(packet)

    def test_the_corpus_rehome_is_part_of_the_compile_identity(self):
        status = self.compile(
            corpus_index=self.receipt.index, asset_pack=self.receipt.pack)
        run = self.output / "runs" / status["request_sha256"]
        manifest = json.loads(
            (run / "manifest.json").read_text(encoding="utf-8"))
        recorded = manifest["request"]["corpus_index"]
        self.assertEqual(str(self.receipt.index.resolve()), recorded["path"])
        self.assertEqual(identity(self.receipt.index), {
            key: recorded[key] for key in ("bytes", "sha256")
        })
        recorded_pack = manifest["request"]["asset_pack"]
        self.assertEqual(str(self.receipt.pack.resolve()),
                         recorded_pack["path"])
        self.assertEqual(identity(self.receipt.pack), {
            key: recorded_pack[key] for key in ("bytes", "sha256")
        })

    def test_a_missing_corpus_rehome_is_rejected(self):
        missing = self.root / "missing-corpus-index.json"
        with self.assertRaises(bne_frontier.FrontierError) as caught:
            self.compile(corpus_index=missing)
        self.assertIn("replacement corpus index is gone", str(caught.exception))

    def test_an_asset_rehome_with_the_wrong_identity_is_rejected(self):
        wrong = self.root / "wrong.chonkpack"
        wrong.write_bytes(b"not the accepted pack\n")
        with self.assertRaises(bne_frontier.FrontierError) as caught:
            self.compile(asset_pack=wrong)
        self.assertIn("does not match the accepted survey", str(caught.exception))

    def test_the_promised_artifacts_are_all_written(self):
        status = self.compile()
        run = self.output / "runs" / status["request_sha256"]
        for name in ("STATUS.json", "NEXT.md", "ROUTES.md", "WHY-CHAIN.md",
                     "WHY-CHAIN.json", "manifest.json"):
            self.assertTrue((run / name).is_file(), f"{name} was not written")

    def test_the_work_order_is_written_only_beneath_its_own_root(self):
        status = self.compile()
        run = self.output / "runs" / status["request_sha256"]
        for path in run.rglob("*"):
            self.assertTrue(
                path.resolve().is_relative_to(self.output.resolve()),
                f"{path} was written outside the evidence root")
        self.assertFalse(
            (self.receipt.artifacts / "latest.json").exists(),
            "the compiler wrote into the accepted proof root")

    def test_the_pointer_is_published_and_authenticates_its_status(self):
        self.compile()
        published = bne_frontier.read_pointer(self.output)
        self.assertIsNotNone(published, "no authenticated pointer was published")
        self.assertEqual(43, published["status"]["frontier"]["common_clean_through"])

    def test_repeating_an_identical_request_is_a_verified_cache_hit(self):
        first = self.compile()
        second = self.compile()
        self.assertEqual("miss", first["cache"])
        self.assertEqual("hit", second["cache"])
        self.assertEqual(first["request_sha256"], second["request_sha256"])

    def test_a_changed_analysis_does_not_return_a_stale_hit(self):
        first = self.compile()
        with mock.patch.object(bne_frontier, "analysis_identity",
                               lambda: {"bne_router.py": {"bytes": 1,
                                                          "sha256": "f" * 64}}):
            second = self.compile()
        self.assertNotEqual(first["request_sha256"], second["request_sha256"],
                            "an edited analysis returned the old work order")

    def test_a_receipt_changed_after_promotion_is_refused(self):
        """The pointer proves the manifest; a rewritten manifest is not it."""
        manifest = json.loads(
            self.receipt.manifest_path.read_text(encoding="utf-8"))
        manifest["frontier"]["common_clean_through"] = 99
        write_json(self.receipt.manifest_path, manifest)
        with self.assertRaises(bne_frontier.FrontierError) as caught:
            self.compile()
        self.assertIn("changed since it was promoted", str(caught.exception))

    def test_a_survey_changed_after_acceptance_is_refused(self):
        survey = self.receipt.run_root / "inputs" / "candidate-survey.json"
        survey.write_text(survey.read_text(encoding="utf-8").replace(
            '"java": 100', '"java": 999'), encoding="utf-8")
        with self.assertRaises(bne_frontier.FrontierError) as caught:
            self.compile()
        self.assertIn("changed since it was accepted", str(caught.exception))

    def test_a_receipt_naming_an_artifact_outside_itself_is_refused(self):
        manifest = json.loads(
            self.receipt.manifest_path.read_text(encoding="utf-8"))
        manifest["artifacts"]["../../escape.txt"] = {"bytes": 1, "sha256": "0" * 64}
        write_json(self.receipt.manifest_path, manifest)
        pointer = json.loads(self.receipt.pointer_path.read_text(encoding="utf-8"))
        pointer["manifest_identity"] = identity(self.receipt.manifest_path)
        write_json(self.receipt.pointer_path, pointer)
        with self.assertRaises(bne_frontier.FrontierError) as caught:
            self.compile()
        self.assertIn("outside itself", str(caught.exception))

    def test_a_pointer_naming_a_manifest_outside_its_root_is_refused(self):
        outside = self.root / "elsewhere" / "manifest.json"
        write_json(outside, {"kind": "gate-acceptance"})
        pointer = json.loads(self.receipt.pointer_path.read_text(encoding="utf-8"))
        pointer["manifest"] = "../elsewhere/manifest.json"
        pointer["manifest_identity"] = identity(outside)
        write_json(self.receipt.pointer_path, pointer)
        with self.assertRaises(bne_frontier.FrontierError) as caught:
            self.compile()
        self.assertIn("outside its root", str(caught.exception))

    def test_a_malformed_pointer_is_refused(self):
        write_json(self.receipt.pointer_path, {"manifest": 42,
                                               "manifest_identity": "nope"})
        with self.assertRaises(bne_frontier.FrontierError):
            self.compile()

    def test_something_that_is_not_a_receipt_is_refused(self):
        stray = self.root / "notes.json"
        write_json(stray, {"kind": "shopping-list"})
        with self.assertRaises(bne_frontier.FrontierError) as caught:
            self.compile(stray)
        self.assertIn("not a gate or triage receipt", str(caught.exception))

    def test_a_damaged_receipt_artifact_is_reported_not_ignored(self):
        (self.receipt.run_root / "inputs" / "blockers" / "orc-08"
         / "java.trace.txt").write_text("rewritten\n", encoding="utf-8")
        status = self.compile()
        self.assertTrue(status["receipt"]["artifacts_damaged"],
                        "a rewritten sealed artifact was not reported")

    def test_a_legacy_receipt_reports_no_capsule_and_never_guesses(self):
        legacy_root = self.root / "legacy"
        legacy = ReceiptFixture(legacy_root, retain=False)
        status = bne_frontier.compile_evidence(
            legacy.pointer_path, artifact_root=legacy.artifacts,
            output_root=legacy_root / "evidence", repository=self.root)
        self.assertEqual("legacy-no-capsule", status["source_capsule"]["state"])
        self.assertFalse(status["source_capsule"]["replayable"])
        self.assertIn("gate", status["source_capsule"]["recovery"])
        self.assertEqual("sealed-candidate-survey",
                         status["blockers"][0]["evidence_source"],
                         "a receipt without retained evidence lost its frame")

    def test_a_missing_trace_becomes_an_evidence_state_with_a_recovery(self):
        self.receipt.trace.unlink()
        retained = (self.receipt.run_root / "inputs" / "blockers" / "orc-08")
        (retained / "java.trace.txt").unlink()
        status = self.compile()
        blocker = status["blockers"][0]
        self.assertEqual("blocked", blocker["frame"])
        self.assertTrue(blocker["missing"])
        self.assertIn("bne_java.py survey", blocker["recovery"])
        self.assertFalse(status["freshness"]["all_frames_current"],
                         "a blocked frame was reported as current evidence")

    def test_a_stale_pointer_never_replaces_a_newer_one(self):
        self.compile()
        older_root = self.root / "older"
        older = ReceiptFixture(older_root, through=29, open_cycle=30)
        bne_frontier.compile_evidence(
            older.pointer_path, artifact_root=older.artifacts,
            output_root=self.output, repository=self.root)
        published = bne_frontier.read_pointer(self.output)
        self.assertEqual(
            43, published["status"]["frontier"]["common_clean_through"],
            "an h29 work order rolled the published pointer backwards")

    def test_a_stale_pointer_can_be_republished_deliberately(self):
        self.compile()
        older_root = self.root / "older"
        older = ReceiptFixture(older_root, through=29, open_cycle=30)
        bne_frontier.compile_evidence(
            older.pointer_path, artifact_root=older.artifacts,
            output_root=self.output, repository=self.root, force=True)
        published = bne_frontier.read_pointer(self.output)
        self.assertEqual(29, published["status"]["frontier"]["common_clean_through"])

    def test_an_interrupted_publication_leaves_the_old_pointer_readable(self):
        self.compile()
        before = (self.output / "latest.json").read_text(encoding="utf-8")
        with mock.patch("os.replace", side_effect=OSError("interrupted")):
            with self.assertRaises(OSError):
                bne_frontier._publish_atomic(
                    self.output / "latest.json", {"schema": 1})
        self.assertEqual(
            before, (self.output / "latest.json").read_text(encoding="utf-8"),
            "an interrupted publication corrupted the pointer")
        self.assertEqual(
            [], [path for path in self.output.glob(".publish-*")],
            "an interrupted publication left its temporary file behind")

    def test_a_half_written_pointer_is_not_believed(self):
        self.compile()
        (self.output / "latest.json").write_text('{"schema": 1', encoding="utf-8")
        self.assertIsNone(bne_frontier._pointer_frontier(self.output))

    def test_a_pointer_whose_status_changed_is_not_believed(self):
        status = self.compile()
        run = self.output / "runs" / status["request_sha256"]
        (run / "STATUS.json").write_text("{}\n", encoding="utf-8")
        self.assertIsNone(bne_frontier.read_pointer(self.output),
                          "a rewritten status was still served as published")

    def test_concurrent_compilers_agree_on_one_run(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            results = [future.result() for future in
                       [pool.submit(self.compile) for _ in range(4)]]
        shas = {result["request_sha256"] for result in results}
        self.assertEqual(1, len(shas),
                         "concurrent compilers produced different work orders")
        runs = list((self.output / "runs").iterdir())
        self.assertEqual(1, len(runs), f"concurrent compilers left {runs}")

    def test_irrelevant_scratch_beside_the_proof_is_not_a_cache_miss(self):
        first = self.compile()
        (self.root / ".bne-state-machine").mkdir()
        (self.root / ".bne-state-machine" / "latest.json").write_text(
            "{}\n", encoding="utf-8")
        (self.root / "goal").mkdir()
        (self.root / "goal" / "fixture-scratch.md").write_text("memory\n", encoding="utf-8")
        second = self.compile()
        self.assertEqual("hit", second["cache"],
                         "an unrelated diagnostic report caused a recompile")
        self.assertEqual(first["request_sha256"], second["request_sha256"])

    def test_the_compiler_never_touches_the_accepted_proof(self):
        before = {str(path): identity(path)
                  for path in self.receipt.artifacts.rglob("*")
                  if path.is_file()}
        self.compile()
        after = {str(path): identity(path)
                 for path in self.receipt.artifacts.rglob("*")
                 if path.is_file()}
        self.assertEqual(before, after,
                         "compiling evidence modified the accepted proof")

    def test_the_objective_comes_from_the_evidence_not_a_document(self):
        status = self.compile()
        objective = status["objective"]
        self.assertEqual(43, objective["common_clean_through"])
        self.assertEqual(44, objective["first_open_cycle"])
        self.assertIn("orc-08 @44", objective["statement"])
        self.assertEqual("cadence", objective["next_lane"])

    def test_the_why_chain_leaves_unproved_native_links_unknown(self):
        status = self.compile()
        chain = status["why_chain"]["chains"][0]
        self.assertIn("native-last-writer", chain["unknown_links"])
        self.assertIn("native-predicate", chain["unknown_links"])
        self.assertIn("first-wrong-field", chain["known_links"])
        self.assertIn("regression-boundary", chain["known_links"])
        boundary = next(item for item in chain["chain"]
                        if item["link"] == "regression-boundary")
        self.assertEqual(43, boundary["clean_through"])
        self.assertEqual(44, boundary["first_wrong"])

    def test_the_why_chain_only_names_an_authenticated_symbol_family(self):
        status = self.compile()
        chain = status["why_chain"]["chains"][0]
        correspondence = next(item for item in chain["chain"]
                              if item["link"] == "semantic-correspondence")
        self.assertEqual("known", correspondence["state"])
        self.assertIn("unit.x", correspondence["java"])

    def test_a_compiled_artifact_swapped_for_a_symlink_is_not_a_cache_hit(self):
        status = self.compile()
        run = self.output / "runs" / status["request_sha256"]
        elsewhere = self.root / "fixture-elsewhere.md"
        elsewhere.write_text("someone else's work order\n", encoding="utf-8")
        (run / "NEXT.md").unlink()
        (run / "NEXT.md").symlink_to(elsewhere)
        with self.assertRaises(bne_frontier.FrontierError) as caught:
            self.compile()
        self.assertIn("unsafe compiled artifact path", str(caught.exception),
                      "a symlink out of the evidence root was followed")

    def test_a_deleted_compiled_artifact_is_not_a_cache_hit(self):
        status = self.compile()
        run = self.output / "runs" / status["request_sha256"]
        (run / "ROUTES.md").unlink()
        with self.assertRaises(bne_frontier.FrontierError):
            self.compile()

    def test_the_handoff_stays_small_enough_to_read(self):
        status = self.compile()
        run = self.output / "runs" / status["request_sha256"]
        self.assertLess(
            (run / "NEXT.md").stat().st_size, 64 * 1024,
            "the work order is too large for a fresh agent to consume")


if __name__ == "__main__":
    unittest.main()
