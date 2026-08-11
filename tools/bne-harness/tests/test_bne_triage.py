import json
from pathlib import Path
import sys
import tempfile
import unittest


SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_triage


def divergent(case_id, cycle, finding):
    return {
        "id": case_id,
        "state": "divergent",
        "first_divergence_cycle": cycle,
        "findings": [finding],
    }


class BneTriageTest(unittest.TestCase):

    def test_clusters_first_findings_by_semantic_delta(self):
        survey = {"cases": [
            divergent("case-a", 23, {
                "kind": "unit", "unit": 10, "unit_type": "unit-peasant",
                "field": "y", "oracle": 73, "java": 74,
                "message": "unit 10 (unit-peasant) y 73 vs 74",
            }),
            divergent("case-b", 25, {
                "kind": "unit", "unit": 99, "unit_type": "unit-peasant",
                "field": "y", "oracle": 40, "java": 41,
                "message": "unit 99 (unit-peasant) y 40 vs 41",
            }),
            divergent("case-c", 24, {
                "kind": "player_bank", "player": 1,
                "oracle": [100, 50, 0], "java": [90, 50, 0],
                "message": "p1 bank (100, 50, 0) vs (90, 50, 0)",
            }),
            {"id": "case-clean", "state": "clean", "findings": []},
        ]}
        clusters = bne_triage.cluster_divergences(survey)
        self.assertEqual(2, len(clusters))
        movement = next(cluster for cluster in clusters
                        if "unit-peasant" in cluster["signature"])
        self.assertEqual(2, movement["case_count"])
        self.assertEqual(23, movement["earliest_cycle"])
        self.assertEqual(["case-a", "case-b"], movement["cases"])
        self.assertTrue(movement["heuristic"])

    def test_canonical_digest_ignores_dictionary_insertion_order(self):
        self.assertEqual(
            bne_triage.canonical_digest({"a": 1, "b": [2, 3]}),
            bne_triage.canonical_digest({"b": [2, 3], "a": 1}),
        )

    def test_manifest_verifies_every_retained_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "attempts" / "one" / "survey.json"
            artifact.parent.mkdir(parents=True)
            artifact.write_text("proof\n")
            request = {"engine": "abc", "through": 23}
            request_id = bne_triage.canonical_digest(request)
            manifest = {
                "schema": 1,
                "request_sha256": request_id,
                "request": request,
                "artifacts": bne_triage.inventory_files(root, [artifact]),
            }
            bne_triage.verify_manifest(root, manifest, request_id)
            artifact.write_text("changed\n")
            with self.assertRaisesRegex(ValueError, "identity changed"):
                bne_triage.verify_manifest(root, manifest, request_id)

    def test_manifest_rejects_request_tampering(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "NEXT.md"
            artifact.write_text("next\n")
            request = {"through": 23}
            request_id = bne_triage.canonical_digest(request)
            manifest = {
                "schema": 1,
                "request_sha256": request_id,
                "request": {"through": 24},
                "artifacts": bne_triage.inventory_files(root, [artifact]),
            }
            with self.assertRaisesRegex(ValueError, "request identity"):
                bne_triage.verify_manifest(root, manifest, request_id)

    def test_formats_actionable_next_packet_and_cluster(self):
        manifest = {
            "request_sha256": "abc",
            "gate": {"passed": True, "issues": []},
            "frontier": {
                "common_clean_through": 22,
                "earliest_divergence_cycle": 23,
            },
            "candidate": {
                "counts": {"clean": 51, "divergent": 1, "failed": 0},
                "survey": "attempts/one/survey/bne-java-survey.json",
            },
            "packets": [{
                "case": "retail-xorc-12-idle", "cycle": 23,
                "readme": "attempts/one/packets/x/README.md",
                "diagnostic_survey": "attempts/one/diagnostics/x/survey.json",
            }],
            "clusters": [{
                "case_count": 1, "earliest_cycle": 23,
                "signature": "unit|unit-peasant|position-y|delta=-1",
                "cases": ["retail-xorc-12-idle"],
            }],
        }
        rendered = bne_triage.format_triage_summary(manifest)
        self.assertIn("Gate: **PASS**", rendered)
        self.assertIn("retail-xorc-12-idle", rendered)
        self.assertIn("retained Java diagnostics", rendered)


if __name__ == "__main__":
    unittest.main()
