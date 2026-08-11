import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_atlas
import bne_causal
import bne_coverage
import bne_experiments
import bne_function_lab
import bne_lab
import bne_minimize
import bne_tournament
import bne_triage


class BneLabToolsTest(unittest.TestCase):

    def test_information_gain_prefers_the_decisive_experiment(self):
        hypotheses = [
            {"id": "a", "predictions": {"weak": "same", "split": "left"}},
            {"id": "b", "predictions": {"weak": "same", "split": "right"}},
        ]
        ranked = bne_experiments.rank_experiments(
            hypotheses,
            [{"id": "weak", "cost": 1}, {"id": "split", "cost": 1}],
        )
        self.assertEqual("split", ranked[0]["id"])
        self.assertEqual(1.0, ranked[0]["information_gain"])

    def test_cegis_learns_a_threshold_from_counterexamples(self):
        domain = list(range(10))

        def expected(value):
            return "short" if value <= 4 else "long"

        def verifier(rule):
            for value in domain:
                actual = rule.evaluate({"distance": value})
                if actual != expected(value):
                    return {"input": {"distance": value}, "output": expected(value)}
            return None

        result = bne_experiments.cegis(
            [
                {"input": {"distance": 0}, "output": "short"},
                {"input": {"distance": 9}, "output": "long"},
            ],
            verifier,
            features=["distance"], outputs=["short", "long"],
        )
        self.assertEqual("synthesized", result["status"])
        rule = bne_experiments.Rule(**result["rule"])
        self.assertTrue(all(rule.evaluate({"distance": value}) == expected(value)
                            for value in domain))
        self.assertGreater(len(result["rounds"]), 1)

    def test_ddmin_isolates_the_failure_inducing_element(self):
        minimal, report = bne_minimize.ddmin(
            list(range(10)), lambda values: 7 in values,
        )
        self.assertEqual([7], minimal)
        self.assertEqual(1, report["minimal_count"])
        self.assertLess(report["tests"], 30)

    def test_causal_minimizer_removes_noise_but_preserves_first_cause(self):
        native = [
            bne_causal.CausalEvent("native", 0, "noise", cycle=1),
            bne_causal.CausalEvent("native", 1, "rng.sync.draw", cycle=6,
                                  fields={"result": 16838}),
            bne_causal.CausalEvent("native", 2, "noise", cycle=20),
        ]
        java = [
            bne_causal.CausalEvent("java", 0, "noise", cycle=1),
            bne_causal.CausalEvent("java", 1, "rng.sync.draw", cycle=8,
                                  fields={"result": 0}),
            bne_causal.CausalEvent("java", 2, "noise", cycle=20),
        ]
        result = bne_minimize.minimize_causal_slice(native, java)
        self.assertEqual("minimized", result["status"])
        self.assertLess(sum(result["minimal"].values()), 6)
        self.assertEqual("mismatch", result["alignment"]["first_divergence"]["op"])

    def test_known_function_replay_matches_retail_sync_rng(self):
        replay = bne_function_lab.replay_known_function(
            0x004534C0, {"seed": 1},
        )
        self.assertEqual(1103527590, replay["outputs"]["seed"])
        self.assertEqual(16838, replay["outputs"]["result"])
        variants = bne_function_lab.boundary_variants({"seed": 1})
        self.assertIn({"seed": 0xffffffff}, variants)

    def test_snapshot_validation_rejects_overlapping_memory(self):
        with self.assertRaisesRegex(ValueError, "overlap"):
            bne_function_lab.validate_snapshot({
                "schema": 1, "address": 0x401000,
                "registers": {"eax": 1},
                "memory": [
                    {"address": 0x1000, "hex": "00010203"},
                    {"address": 0x1002, "hex": "0405"},
                ],
            })

    def test_coverage_generates_only_legal_move_commands(self):
        packet = {
            "case": {"id": "case-a"}, "divergence": {"cycle": 5},
            "semantic": {"5": {"focus": [{
                "native_slot": 10, "oracle": {"x": 0, "y": 0},
            }]}},
        }
        plan = bne_coverage.command_variants_from_packet(packet)
        self.assertTrue(plan["variants"])
        self.assertTrue(all(0 <= item["x"] <= 127 and 0 <= item["y"] <= 127
                            for item in plan["variants"]))
        self.assertTrue(all(item["command"].startswith("cycle ")
                            for item in plan["variants"]))

    def test_tournament_selects_only_a_fully_gated_candidate(self):
        outcomes = {
            "fast-bad": {"focused_passed": True, "gate_passed": False,
                         "target_clean_through": 30, "changed_lines": 1},
            "good": {"focused_passed": True, "gate_passed": True,
                     "target_clean_through": 25, "changed_lines": 3},
            "better": {"focused_passed": True, "gate_passed": True,
                       "target_clean_through": 26, "changed_lines": 8},
        }
        result = bne_tournament.run_tournament(
            [{"id": key} for key in outcomes],
            lambda candidate: outcomes[candidate["id"]], jobs=3,
        )
        self.assertEqual("better", result["winner"]["candidate"])
        self.assertFalse(result["policy"]["automatic_merge"])

    def test_git_patch_evaluator_uses_disposable_worktrees(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repository = root / "repo"
            repository.mkdir()
            subprocess.run(["git", "init", "-q"], cwd=repository, check=True)
            subprocess.run(["git", "config", "user.email", "lab@example.invalid"],
                           cwd=repository, check=True)
            subprocess.run(["git", "config", "user.name", "Parity Lab"],
                           cwd=repository, check=True)
            (repository / "value.txt").write_text("1\n", encoding="ascii")
            subprocess.run(["git", "add", "value.txt"], cwd=repository, check=True)
            subprocess.run(["git", "commit", "-qm", "base"],
                           cwd=repository, check=True)
            revision = subprocess.run(
                ["git", "rev-parse", "HEAD"], cwd=repository,
                check=True, capture_output=True, text=True,
            ).stdout.strip()
            patches = []
            for value in (2, 3):
                patch = root / f"candidate-{value}.patch"
                patch.write_text(
                    "--- a/value.txt\n+++ b/value.txt\n@@ -1 +1 @@\n"
                    f"-1\n+{value}\n", encoding="ascii",
                )
                patches.append(patch)
            evaluator_script = (
                "import json,pathlib,sys; "
                "v=int((pathlib.Path(sys.argv[1])/'value.txt').read_text()); "
                "print(json.dumps({'focused_passed':v==2,'gate_passed':v==2,"
                "'regressions':[] if v==2 else ['wrong-value'],"
                "'target_clean_through':24 if v==2 else 0,'changed_lines':1}))"
            )
            evaluator = bne_tournament.GitPatchEvaluator(
                repository, revision,
                [sys.executable, "-c", evaluator_script, "{worktree}"],
            )
            result = bne_tournament.run_tournament(
                [{"id": "good", "patch": str(patches[0])},
                 {"id": "bad", "patch": str(patches[1])}],
                evaluator, jobs=1,
            )
            self.assertEqual("good", result["winner"]["candidate"])
            self.assertEqual([], subprocess.run(
                ["git", "worktree", "list", "--porcelain"], cwd=repository,
                check=True, capture_output=True, text=True,
            ).stdout.strip().split("\n\n")[1:])

    def test_atlas_is_idempotent_and_finds_similar_history(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "run"
            root.mkdir()
            survey = root / "survey.json"
            survey.write_text(json.dumps({"cases": [{
                "id": "case-a", "state": "divergent",
                "first_divergence_cycle": 24,
                "findings": [{
                    "kind": "unit", "unit_type": "unit-peasant",
                    "field": "y", "oracle": 8, "java": 9,
                }],
            }]}) + "\n")
            request = {"engine": {"head": "abc"}}
            request_id = bne_triage.canonical_digest(request)
            manifest = {
                "schema": 1, "request_sha256": request_id,
                "created_at": "2026-08-02T00:00:00+00:00", "request": request,
                "candidate": {"survey": "survey.json", "counts": {
                    "clean": 0, "divergent": 1, "failed": 0}},
                "gate": {"passed": True},
                "frontier": {"common_clean_through": 23,
                             "earliest_divergence_cycle": 24},
                "clusters": [],
                "artifacts": bne_triage.inventory_files(root, [survey]),
            }
            (root / "manifest.json").write_text(
                json.dumps(manifest, indent=2, sort_keys=True) + "\n")
            database = Path(directory) / "atlas.sqlite"
            bne_atlas.ingest_run(database, root)
            bne_atlas.ingest_run(database, root)
            similar = bne_atlas.similar_failures(
                database, "unit|unit-peasant|position-y|delta=-1",
            )
            self.assertEqual(1, len(similar))
            self.assertEqual("case-a", similar[0]["case"])
            self.assertEqual(set(), bne_atlas.coverage_baseline(database))
            recorded = bne_atlas.record_coverage(
                database, request_id, "case-a",
                {"tokens": ["kind:path.route", "address:0x0044fbd0"]},
            )
            self.assertEqual(2, recorded["atlas_tokens"])
            self.assertEqual({"kind:path.route", "address:0x0044fbd0"},
                             bne_atlas.coverage_baseline(database))

    def test_builds_a_sealed_end_to_end_lab_run(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            triage_root = directory / "triage"
            packet_dir = triage_root / "packets" / "case-a"
            packet_dir.mkdir(parents=True)
            packet = {
                "schema": 1,
                "case": {"id": "case-a"},
                "divergence": {"cycle": 24, "findings": [{
                    "kind": "unit", "unit_type": "unit-peasant",
                    "field": "y", "oracle": 8, "java": 9,
                }]},
                "semantic": {"24": {"focus": [{
                    "native_slot": 17, "java_id": 3,
                    "oracle": {"x": 40, "y": 8},
                    "java": {"x": 40, "y": 9},
                }]}},
                "native_state": {}, "native_diagnostic_events": [],
                "java_diagnostic_highlights": [], "java_process_output": {},
            }
            packet_path = packet_dir / "packet.json"
            packet_path.write_text(json.dumps(packet) + "\n")
            survey = triage_root / "survey.json"
            survey.write_text(json.dumps({"cases": [{
                "id": "case-a", "state": "divergent",
                "first_divergence_cycle": 24,
                "findings": packet["divergence"]["findings"],
            }]}) + "\n")
            request = {"engine": {"head": "abc"}}
            request_id = bne_triage.canonical_digest(request)
            triage_manifest = {
                "schema": 1, "request_sha256": request_id,
                "created_at": "2026-08-02T00:00:00+00:00", "request": request,
                "candidate": {"survey": "survey.json", "counts": {
                    "clean": 51, "divergent": 1, "failed": 0}},
                "gate": {"passed": True},
                "frontier": {"common_clean_through": 23,
                             "earliest_divergence_cycle": 24},
                "clusters": [],
                "packets": [{"case": "case-a", "cycle": 24,
                             "packet": "packets/case-a/packet.json"}],
                "artifacts": bne_triage.inventory_files(
                    triage_root, [packet_path, survey]),
            }
            (triage_root / "manifest.json").write_text(
                json.dumps(triage_manifest, indent=2, sort_keys=True) + "\n")
            status, run_root = bne_lab.build_lab(
                triage_root, directory / "lab",
            )
            self.assertEqual(0, status)
            manifest = json.loads((run_root / "manifest.json").read_text())
            bne_lab.verify_lab_manifest(run_root, manifest,
                                        manifest["request_sha256"])
            self.assertEqual("case-a", manifest["cases"][0]["case"])
            self.assertTrue((run_root / "NEXT.md").is_file())
            latest = directory / "lab" / "latest.json"
            self.assertTrue(latest.is_file())
            latest.write_text("{}\n")
            cached_status, cached_root = bne_lab.build_lab(
                triage_root, directory / "lab",
            )
            self.assertEqual(0, cached_status)
            self.assertEqual(run_root, cached_root)
            promoted = json.loads(latest.read_text())
            self.assertEqual(manifest["request_sha256"],
                             promoted["request_sha256"])
            self.assertEqual(1, promoted["cases"])

    def test_a_bankless_divergence_still_composes_its_lab_handoff(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            triage_root = directory / "triage"
            packet_dir = triage_root / "packets" / "case-bank"
            packet_dir.mkdir(parents=True)
            findings = [{
                "cycle": 30, "kind": "player_bank", "player": 6,
                "oracle": [12800, 37456, 500], "java": [13400, 37656, 1000],
                "message": "p6 bank (12800, 37456, 500) vs (13400, 37656, 1000)",
            }]
            packet = {
                "schema": 1,
                "case": {"id": "case-bank"},
                "divergence": {"cycle": 30, "findings": findings},
                "semantic": {"30": {"cycle": 30, "focus": []}},
                "native_state": {}, "native_diagnostic_events": [],
                "java_diagnostic_highlights": [], "java_process_output": {},
            }
            packet_path = packet_dir / "packet.json"
            packet_path.write_text(json.dumps(packet) + "\n")
            survey = triage_root / "survey.json"
            survey.write_text(json.dumps({"cases": [{
                "id": "case-bank", "state": "divergent",
                "first_divergence_cycle": 30, "findings": findings,
            }]}) + "\n")
            request = {"engine": {"head": "bank"}}
            triage_manifest = {
                "schema": 1,
                "request_sha256": bne_triage.canonical_digest(request),
                "created_at": "2026-08-02T00:00:00+00:00", "request": request,
                "candidate": {"survey": "survey.json", "counts": {
                    "clean": 51, "divergent": 1, "failed": 0}},
                "gate": {"passed": True},
                "frontier": {"common_clean_through": 29,
                             "earliest_divergence_cycle": 30},
                "clusters": [],
                "packets": [{"case": "case-bank", "cycle": 30,
                             "packet": "packets/case-bank/packet.json"}],
                "artifacts": bne_triage.inventory_files(
                    triage_root, [packet_path, survey]),
            }
            (triage_root / "manifest.json").write_text(
                json.dumps(triage_manifest, indent=2, sort_keys=True) + "\n")
            status, run_root = bne_lab.build_lab(
                triage_root, directory / "lab",
            )
            self.assertEqual(0, status,
                             "a resource-only divergence must still hand off")
            manifest = json.loads((run_root / "manifest.json").read_text())
            case = next(item for item in manifest["cases"]
                        if item["case"] == "case-bank")
            plan = json.loads(
                (run_root / manifest["attempt"]
                 / case["counterfactual"]).read_text())
            self.assertFalse(plan["supported"],
                             "an unpaired divergence has no replay candidate")
            self.assertEqual(0, plan["candidate_count"])
            self.assertIn("focused unit", plan["reason"])
            promoted = json.loads(
                (directory / "lab" / "latest.json").read_text())
            self.assertEqual(triage_manifest["request_sha256"],
                             promoted["triage_request_sha256"],
                             "the lab pointer must name the triage that ran")


if __name__ == "__main__":
    unittest.main()
