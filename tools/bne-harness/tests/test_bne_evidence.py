import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_evidence


def identity(path):
    data = Path(path).read_bytes()
    return {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


class RetainedBlockerEvidenceTest(unittest.TestCase):
    """A proof must arrive carrying what the blockers holding it back need."""

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="bne-evidence-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.work = self.root / "work"
        self.work.mkdir()
        self.corpus = self.root / "corpus"
        self.corpus.mkdir()
        (self.corpus / "cases").mkdir()

        self.fixture = self.corpus / "cases" / "orc-08.bnefx"
        self.fixture.write_bytes(b"sealed native capture" * 64)
        self.index = self.corpus / "corpus-index.json"
        self.index.write_text(json.dumps({
            "schema": 1,
            "cases": [{
                "id": "orc-08",
                "fixture_id": "fixture-orc-08",
                "fixture": {"path": "cases/orc-08.bnefx", **identity(self.fixture)},
            }],
        }), encoding="utf-8")

        self.trace = self.work / "orc-08.java.trace.txt"
        self.trace.write_text("cycle 44\nu 1433 x 100 y 86\n", encoding="utf-8")
        self.stdout = self.work / "orc-08.java.stdout.txt"
        self.stdout.write_text("trace complete\n", encoding="utf-8")

        self.candidate = {
            "schema": 1,
            "comparison_tier": "semantic-v1",
            "through": 80,
            "engine": {"schema": 2, "policy": "engine-input-v1",
                       "head": "a" * 40, "dirty": False,
                       "engine_input_sha256": "b" * 64, "input_count": 1},
            "asset_source": {"kind": "chonkpack"},
            "runtime": {"source_dir": str(self.root / "chonkcraft")},
            "index": str(self.index),
            "cases": [{
                "id": "orc-08",
                "fixture_id": "fixture-orc-08",
                "state": "divergent",
                "compared_cycles": 80,
                "first_divergence_cycle": 44,
                "findings": [{
                    "cycle": 44, "kind": "unit", "field": "x", "unit": 1433,
                    "unit_type": "unit-human-submarine", "oracle": 102,
                    "java": 100,
                    "message": "unit 1433 (unit-human-submarine) x 102 vs 100",
                }],
                "java_trace": {"path": str(self.trace), **identity(self.trace)},
                "java_process_output": {
                    "stdout": {"path": str(self.stdout), **identity(self.stdout)},
                },
            }],
        }
        self.frontier = {
            "tied_blockers": [{
                "case": "orc-08", "cycle": 44, "rank": 1,
                "recommended_tool": "cadence",
            }],
        }

    def retain(self, staging_name="inputs"):
        staging = self.root / staging_name
        staging.mkdir(exist_ok=True)
        return bne_evidence.retain_blocker_evidence(
            self.candidate, self.frontier, staging), staging

    def test_a_blocker_arrives_with_the_bytes_its_packet_needs(self):
        evidence, staging = self.retain()
        self.assertEqual(1, evidence["retained_count"],
                         f"the blocker was not retained: {evidence}")
        blocker = evidence["blockers"][0]
        self.assertEqual("retained", blocker["state"])
        for relative in (blocker["java_trace"]["path"],
                         blocker["java_process_output"]["stdout"]["path"],
                         blocker["survey"]):
            self.assertTrue(
                (staging / relative).is_file(),
                f"{relative} was recorded but not retained",
            )
        self.assertEqual(
            self.trace.read_bytes(),
            (staging / blocker["java_trace"]["path"]).read_bytes(),
            "the retained trace is not the trace the survey proved",
        )

    def test_the_retained_survey_names_the_bytes_relative_to_the_receipt(self):
        """A staging path does not survive the rename that seals the receipt.

        This asserted an absolute path under the staging directory, which is
        exactly the path that stops existing when `os.replace` promotes the
        receipt -- so the assertion passed while every sealed receipt pointed
        at a directory that was already gone.
        """
        _evidence, staging = self.retain()
        survey = json.loads(
            (staging / "blockers/orc-08/survey.json").read_text(encoding="utf-8"))
        recorded = survey["cases"][0]["java_trace"]
        self.assertEqual(bne_evidence.PATH_BASE, recorded["path_base"])
        self.assertFalse(
            Path(recorded["path"]).is_absolute(),
            f"the retained survey sealed an absolute path: {recorded['path']}",
        )
        resolved = bne_evidence.resolve_retained_survey(
            staging / "blockers/orc-08/survey.json", staging)
        located = resolved["cases"][0]["java_trace"]["path"]
        self.assertEqual(
            identity(located),
            {key: recorded[key] for key in ("bytes", "sha256")},
            "the retained survey does not authenticate its own trace",
        )

    def test_the_retained_survey_keeps_the_java_source_identity(self):
        _evidence, staging = self.retain()
        survey = json.loads(
            (staging / "blockers/orc-08/survey.json").read_text(
                encoding="utf-8"))
        self.assertEqual(
            self.candidate["runtime"]["source_dir"],
            survey["runtime"]["source_dir"],
            "retention discarded the source tree needed by recovery commands",
        )

    def test_only_the_tied_blockers_are_retained(self):
        """Fifty-two traces do not belong in a receipt about two of them."""
        self.candidate["cases"].append({
            "id": "human-01", "fixture_id": "fixture-human-01",
            "state": "divergent", "compared_cycles": 80,
            "first_divergence_cycle": 61, "findings": [],
            "java_trace": {"path": str(self.trace), **identity(self.trace)},
        })
        _evidence, staging = self.retain()
        retained = sorted(path.name for path in
                          (staging / "blockers").iterdir())
        self.assertEqual(
            ["orc-08"], retained,
            "a case that is not holding the frontier was retained anyway",
        )

    def test_a_sealed_fixture_is_referenced_and_never_copied(self):
        evidence, staging = self.retain()
        fixture = evidence["blockers"][0]["fixture"]
        self.assertFalse(fixture["retained"])
        self.assertEqual(identity(self.fixture)["sha256"], fixture["sha256"])
        self.assertEqual(
            [], [path for path in staging.rglob("*.bnefx")],
            "a sealed native capture was copied into the receipt",
        )

    def test_a_trace_that_changed_since_the_survey_is_not_trusted(self):
        """An absolute work-directory path is a claim, not a fact."""
        self.trace.write_text("cycle 44\nu 1433 x 999 y 999\n", encoding="utf-8")
        evidence, _staging = self.retain()
        blocker = evidence["blockers"][0]
        self.assertEqual("unavailable", blocker["state"])
        self.assertTrue(
            any("changed since the survey" in item
                for item in blocker["missing"]),
            f"a rewritten trace was copied anyway: {blocker}",
        )
        self.assertIn("bne_java.py survey", blocker["recovery"])

    def test_a_trace_the_next_survey_overwrote_becomes_an_evidence_state(self):
        self.trace.unlink()
        evidence, _staging = self.retain()
        blocker = evidence["blockers"][0]
        self.assertEqual("unavailable", blocker["state"])
        self.assertTrue(any("is gone" in item for item in blocker["missing"]))
        self.assertIn("--case orc-08", blocker["recovery"])

    def test_a_missing_fixture_becomes_an_evidence_state(self):
        self.fixture.unlink()
        evidence, _staging = self.retain()
        blocker = evidence["blockers"][0]
        self.assertEqual("unavailable", blocker["state"])
        self.assertTrue(any("fixture" in item for item in blocker["missing"]))

    def test_a_fixture_index_naming_a_different_capture_is_refused(self):
        index = json.loads(self.index.read_text(encoding="utf-8"))
        index["cases"][0]["fixture_id"] = "fixture-somewhere-else"
        self.index.write_text(json.dumps(index), encoding="utf-8")
        evidence, _staging = self.retain()
        self.assertEqual("unavailable", evidence["blockers"][0]["state"])

    def test_a_fixture_path_climbing_out_of_the_corpus_is_refused(self):
        index = json.loads(self.index.read_text(encoding="utf-8"))
        index["cases"][0]["fixture"]["path"] = "../../outside.bnefx"
        self.index.write_text(json.dumps(index), encoding="utf-8")
        evidence, _staging = self.retain()
        blocker = evidence["blockers"][0]
        self.assertEqual("unavailable", blocker["state"])
        self.assertTrue(
            any("outside itself" in item for item in blocker["missing"]),
            f"a fixture path outside the corpus was accepted: {blocker}",
        )

    def test_a_blocker_absent_from_the_survey_is_reported_not_invented(self):
        self.frontier["tied_blockers"].append(
            {"case": "ghost-99", "cycle": 44, "rank": 2})
        evidence, _staging = self.retain()
        ghost = next(item for item in evidence["blockers"]
                     if item["case"] == "ghost-99")
        self.assertEqual("unavailable", ghost["state"])
        self.assertEqual(["survey_record"], ghost["missing"])

    def test_retention_is_reported_in_words_an_operator_can_act_on(self):
        self.trace.unlink()
        evidence, _staging = self.retain()
        described = bne_evidence.describe(evidence)
        self.assertIn("1 unavailable", described)
        self.assertIn("recover with:", described)


if __name__ == "__main__":
    unittest.main()
