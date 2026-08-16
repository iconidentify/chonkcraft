from pathlib import Path
import json
import sys
import tempfile
import unittest


HARNESS = Path(__file__).parents[1]
REPOSITORY = HARNESS.parents[1]
SCRIPTS = HARNESS / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_divergence_compiler as compiler


class DivergenceCompilerTest(unittest.TestCase):

    IDENTITY = {
        "case": "retail-human-06-commanded",
        "fixture_id": "a" * 64,
        "scenario": "Campaign\\Human\\Human06.pud",
        "seed": 1,
        "cycle": 41,
        "subject": {"native_slot": 1490},
        "pc": 0x004379E0,
    }

    def mismatch(self, root: Path) -> Path:
        path = root / "mismatch.json"
        path.write_text(json.dumps({
            "schema": compiler.SCHEMA,
            "case": "retail-human-06-commanded",
            "cycle": 41,
            "clean_through": 40,
            "identity": self.IDENTITY,
            "family": "combat",
            "finding": {
                "kind": "unit", "unit": 1490, "unit_type": "unit-knight",
                "field": "target", "oracle": 1500, "java": -1,
            },
            "scenario": {"commands": [
                {"cycle": 5, "command": "move", "unit": 1490},
                {"cycle": 41, "command": "attack", "unit": 1490},
                {"cycle": 99, "command": "stop", "unit": 1490},
            ]},
            "witnesses": [],
        }), encoding="utf-8")
        return path

    def test_one_mismatch_becomes_an_authenticated_fail_closed_work_order(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            work, run = compiler.compile_mismatch(
                self.mismatch(root), artifact_root=root / "lab",
                repository=REPOSITORY)
            self.assertEqual("evidence-open", work["state"])
            self.assertFalse(work["acceptance"]["engine_edit_allowed"])
            self.assertEqual("missing", work["native"]["state"])
            self.assertEqual("blocked", work["micro_oracle"]["state"])
            self.assertEqual(185,
                work["coverage"]["combat"]["coverage"]["required"])
            self.assertEqual(137,
                work["coverage"]["campaign"]["coverage"]["required"])
            prefix = json.loads((run / "causal-prefix.json").read_text())
            self.assertEqual(2, len(prefix["scenario"]["commands"]))
            self.assertEqual(1, prefix["removed_future_events"])
            self.assertTrue((run / "manifest.json").is_file())
            self.assertTrue((root / "lab" / "latest.json").is_file())
            self.assertEqual(self.IDENTITY, work["identity"])
            self.assertEqual("missing", work["focused_proof"]["state"])
            request_inputs = json.loads(
                (run / "manifest.json").read_text())["request"]["inputs"]
            self.assertIn("analysis:toolchain", request_inputs)
            self.assertIn("analysis:runtime", request_inputs)
            self.assertIn("analysis:ExportFunctionSlice.java", request_inputs)

    def test_the_same_inputs_resolve_to_the_same_immutable_run(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            mismatch = self.mismatch(root)
            first, first_run = compiler.compile_mismatch(
                mismatch, artifact_root=root / "lab", repository=REPOSITORY)
            second, second_run = compiler.compile_mismatch(
                mismatch, artifact_root=root / "lab", repository=REPOSITORY)
            self.assertEqual(first_run, second_run)
            self.assertEqual(first["request_sha256"], second["request_sha256"])
            resolved, resolved_root = compiler.resolve_pointer(root / "lab")
            self.assertEqual(first_run, resolved_root)
            self.assertEqual(first["request_sha256"], resolved["request_sha256"])

    def test_a_gap_before_the_claimed_first_mismatch_is_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = self.mismatch(root)
            document = json.loads(path.read_text())
            document["clean_through"] = 12
            path.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaises(compiler.CompilerError):
                compiler.compile_mismatch(
                    path, artifact_root=root / "lab", repository=REPOSITORY)

    def test_complete_identity_tuple_is_mandatory(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = self.mismatch(root)
            document = json.loads(path.read_text())
            del document["identity"]["fixture_id"]
            path.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaisesRegex(compiler.CompilerError, "fixture"):
                compiler.compile_mismatch(
                    path, artifact_root=root / "lab", repository=REPOSITORY)

    def test_finding_subject_must_match_the_authenticated_tuple(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = self.mismatch(root)
            document = json.loads(path.read_text())
            document["finding"]["unit"] = 1491
            path.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaisesRegex(compiler.CompilerError, "subject"):
                compiler.compile_mismatch(
                    path, artifact_root=root / "lab", repository=REPOSITORY)

    def test_unsealed_json_cannot_supply_the_static_pc(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            loose = root / "loose.json"
            loose.write_text(json.dumps({"address": self.IDENTITY["pc"]}),
                             encoding="utf-8")
            path = self.mismatch(root)
            document = json.loads(path.read_text())
            document["evidence"] = {"loose": str(loose)}
            document["proof_commands"] = ["true"]
            path.write_text(json.dumps(document), encoding="utf-8")
            work, _ = compiler.compile_mismatch(
                path, artifact_root=root / "lab", repository=REPOSITORY)
            self.assertEqual("blocked", work["static_slice"]["state"])
            self.assertFalse(work["acceptance"]["engine_edit_allowed"])
            self.assertFalse(work["acceptance"]["focused_regression_sealed"])
            self.assertIsNone(compiler._native_pc(
                document, {"loose": {"address": self.IDENTITY["pc"]}},
                {"loose": {"authenticated": False}}))

    def test_mutating_manifest_inventory_invalidates_the_cache(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            mismatch = self.mismatch(root)
            _, run = compiler.compile_mismatch(
                mismatch, artifact_root=root / "lab", repository=REPOSITORY)
            manifest_path = run / "manifest.json"
            manifest = json.loads(manifest_path.read_text())
            manifest["artifacts"].pop("NEXT.md")
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaisesRegex(compiler.CompilerError, "inventory"):
                compiler.compile_mismatch(
                    mismatch, artifact_root=root / "lab", repository=REPOSITORY)

    def test_mutating_sealed_request_invalidates_the_cache(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            mismatch = self.mismatch(root)
            _, run = compiler.compile_mismatch(
                mismatch, artifact_root=root / "lab", repository=REPOSITORY)
            manifest_path = run / "manifest.json"
            manifest = json.loads(manifest_path.read_text())
            manifest["request"]["mismatch"]["cycle"] = 42
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaisesRegex(compiler.CompilerError, "request identity"):
                compiler.compile_mismatch(
                    mismatch, artifact_root=root / "lab", repository=REPOSITORY)

    def test_focused_proof_is_bound_to_the_exact_authenticated_tuple(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            proof_path = root / "focused-proof.json"
            proof = {
                "schema": compiler.FOCUSED_PROOF_SCHEMA,
                "identity": self.IDENTITY,
                "command": ["mvn", "-q", "-Dtest=ExactWitnessTest", "test"],
                "result": {"status": "pass", "exit_code": 0},
            }
            proof_path.write_text(json.dumps(proof), encoding="utf-8")
            manifest = {
                "schema": compiler.FOCUSED_PROOF_MANIFEST_SCHEMA,
                "identity": self.IDENTITY,
                "proof_sha256": compiler.canonical_digest(proof),
                "artifacts": {
                    proof_path.name: compiler.file_identity(proof_path),
                },
                "producer": {"bytes": 123, "sha256": "b" * 64},
            }
            self.assertTrue(compiler._focused_proof_matches(
                proof_path, proof, manifest, self.IDENTITY))
            other = json.loads(json.dumps(self.IDENTITY))
            other["subject"]["native_slot"] += 1
            self.assertFalse(compiler._focused_proof_matches(
                proof_path, proof, manifest, other))

    def test_snapshot_authentication_closes_specification_and_oracle_run(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            specification = compiler.load_specification({
                "schema": 1,
                "case": self.IDENTITY["case"],
                "cycle": self.IDENTITY["cycle"],
                "entry": self.IDENTITY["pc"],
                "identity": self.IDENTITY,
                "focus": {"native_slot": 1490, "register": "esi"},
                "regions": [
                    {"label": "code", "bytes": 4, "access": "rx",
                     "address": self.IDENTITY["pc"]},
                    {"label": "stack", "bytes": 4, "access": "rw",
                     "register": "esp"},
                ],
            })
            spec_path = root / "specification.json"
            spec_path.write_text(json.dumps(specification), encoding="utf-8")
            run = {
                "oracle": {"executable": {"sha256": compiler.BNE_202_SHA256}},
                "fixture": {"id": self.IDENTITY["fixture_id"]},
                "runtime": {"network_disabled": True,
                            "branch_witness_pause_cycle": self.IDENTITY["cycle"]},
                "run": {"validation": {
                    "scenario": self.IDENTITY["scenario"],
                    "initialization_seed": self.IDENTITY["seed"],
                    "cycles": self.IDENTITY["cycle"],
                }},
            }
            run_path = root / "oracle-run-manifest.json"
            run_path.write_text(json.dumps(run), encoding="utf-8")
            snapshot = {
                "segments": [], "registers": {},
                "provenance": {
                    "identity": self.IDENTITY,
                    "specification_sha256": compiler.canonical_digest(
                        specification),
                },
            }
            snapshot_path = root / "snapshot.json"
            snapshot_path.write_text(json.dumps(snapshot), encoding="utf-8")
            manifest = {
                "identity": self.IDENTITY,
                "snapshot_sha256": compiler.canonical_digest(snapshot),
                "specification_sha256": compiler.canonical_digest(specification),
                "executable": {"sha256": compiler.BNE_202_SHA256},
                "capture": {
                    "network_disabled": True,
                    "importer": {"bytes": 123, "sha256": "c" * 64},
                    "oracle_run_manifest": compiler.file_identity(run_path),
                },
                "artifacts": {
                    snapshot_path.name: compiler.file_identity(snapshot_path),
                    spec_path.name: compiler.file_identity(spec_path),
                    run_path.name: compiler.file_identity(run_path),
                },
            }
            manifest_path = root / "manifest.json"
            self.assertTrue(compiler._snapshot_manifest_matches(
                snapshot_path, snapshot, manifest_path, manifest, self.IDENTITY))
            run["runtime"]["branch_witness_pause_cycle"] += 1
            run_path.write_text(json.dumps(run), encoding="utf-8")
            manifest["artifacts"][run_path.name] = compiler.file_identity(run_path)
            manifest["capture"]["oracle_run_manifest"] = \
                compiler.file_identity(run_path)
            self.assertFalse(compiler._snapshot_manifest_matches(
                snapshot_path, snapshot, manifest_path, manifest, self.IDENTITY))

    def test_pointer_closure_refuses_a_changed_work_order_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            compiler.compile_mismatch(
                self.mismatch(root), artifact_root=root / "lab",
                repository=REPOSITORY)
            pointer_path = root / "lab" / "latest.json"
            pointer = json.loads(pointer_path.read_text())
            pointer["work_order_identity"]["sha256"] = "0" * 64
            pointer_path.write_text(json.dumps(pointer), encoding="utf-8")
            with self.assertRaisesRegex(compiler.CompilerError, "closure"):
                compiler.resolve_pointer(root / "lab")

    def test_content_addressed_run_refuses_symbolic_link_artifacts(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            mismatch = self.mismatch(root)
            _, run = compiler.compile_mismatch(
                mismatch, artifact_root=root / "lab", repository=REPOSITORY)
            (run / "outside-link").symlink_to(mismatch)
            with self.assertRaisesRegex(compiler.CompilerError, "symbolic link"):
                compiler.compile_mismatch(
                    mismatch, artifact_root=root / "lab", repository=REPOSITORY)


if __name__ == "__main__":
    unittest.main()
