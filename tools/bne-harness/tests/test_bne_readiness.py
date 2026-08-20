#!/usr/bin/env python3

import copy
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[3]
LIBRARY = ROOT / "tools" / "bne-readiness"
sys.path.insert(0, str(LIBRARY))

import bne_playability
import bne_readiness


class ReadinessTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data = bne_readiness.load(LIBRARY / "readiness.json")

    def test_the_real_manifest_names_every_required_system_and_existing_path(self):
        warnings = bne_readiness.validate(self.data, ROOT)

        self.assertEqual([], warnings,
                         "the shipped manifest must support every claim without warnings")
        self.assertEqual(bne_readiness.REQUIRED_SYSTEMS,
                         {system["id"] for system in self.data["systems"]})

    def test_green_requires_retail_evidence(self):
        changed = copy.deepcopy(self.data)
        changed["systems"][0]["grade"] = "green"
        changed["systems"][0]["retail_evidence"] = []

        with self.assertRaisesRegex(bne_readiness.LedgerError, "retail evidence"):
            bne_readiness.validate(changed, ROOT)

    def test_green_refuses_a_named_blocker(self):
        changed = copy.deepcopy(self.data)
        system = changed["systems"][0]
        system["grade"] = "green"
        system["blockers"] = ["still materially incomplete"]

        with self.assertRaisesRegex(bne_readiness.LedgerError, "named blocker"):
            bne_readiness.validate(changed, ROOT)

    def test_red_requires_a_named_blocker(self):
        changed = copy.deepcopy(self.data)
        system = next(item for item in changed["systems"]
                      if item["id"] == "retail-ai")
        system["grade"] = "red"
        system["blockers"] = []

        with self.assertRaisesRegex(bne_readiness.LedgerError, "named blocker"):
            bne_readiness.validate(changed, ROOT)

    def test_renderer_does_not_turn_missing_evidence_into_proof(self):
        changed = copy.deepcopy(self.data)
        system = next(item for item in changed["systems"]
                      if item["id"] == "retail-ai")
        system["grade"] = "red"
        system["retail_evidence"] = []
        report = bne_readiness.render(changed)

        self.assertIn("No retail authority has been established yet.", report)
        self.assertIn("Retail ai.bin computer player", report)
        self.assertIn("Grade: **RED**", report)

    def test_report_keeps_playability_separate_from_exact_fidelity(self):
        report = bne_readiness.render(self.data)

        self.assertIn("Playability | CERTIFIED", report)
        self.assertIn("Exact BNE fidelity | IN-PROGRESS", report)
        self.assertIn("does not", report)
        self.assertNotEqual(
            "certified",
            self.data["certification_tracks"]["exact_fidelity"]["status"])

    def test_required_retail_inputs_are_a_closed_vocabulary(self):
        changed = copy.deepcopy(self.data)
        system = next(item for item in changed["systems"]
                      if item["id"] == "retail-ai")
        system["gate"]["required_inputs"] = ["imaginary_oracle"]

        with self.assertRaisesRegex(bne_readiness.LedgerError, "unknown inputs"):
            bne_readiness.validate(changed, ROOT)

    def test_lane_selection_preserves_the_requested_order(self):
        selected = bne_playability.select(
            self.data, ["retail-ai", "movement-pathfinding"])

        self.assertEqual(["retail-ai", "movement-pathfinding"],
                         [system["id"] for system in selected])

    def test_red_lane_is_blocked_without_launching_a_process(self):
        system = copy.deepcopy(next(item for item in self.data["systems"]
                                    if item["id"] == "retail-ai"))
        system["grade"] = "red"
        system["blockers"] = ["synthetic red blocker"]
        with tempfile.TemporaryDirectory() as temporary:
            result = bne_playability.run(
                system, ROOT, Path(temporary), 1, force_red=False)

        self.assertEqual("blocked", result["status"])
        self.assertEqual(["synthetic red blocker"], result["blockers"])

    def test_required_retail_input_blocks_even_a_forced_red_lane(self):
        system = copy.deepcopy(next(item for item in self.data["systems"]
                                    if item["id"] == "retail-ai"))
        system["gate"]["required_inputs"] = ["asset_pack"]
        with tempfile.TemporaryDirectory() as temporary, \
                mock.patch.dict("os.environ", {}, clear=True), \
                mock.patch.object(bne_playability, "DEFAULT_BNE_PACK",
                                  Path(temporary) / "missing.chonkpack"):
            result = bne_playability.run(
                system, ROOT, Path(temporary), 1, force_red=True)

        self.assertEqual("blocked", result["status"])
        self.assertEqual(["missing required input: asset_pack"], result["blockers"])

    def test_a_zero_exit_with_skipped_checks_fails_closed(self):
        system = copy.deepcopy(self.data["systems"][0])
        system["grade"] = "yellow"
        system["gate"]["required_inputs"] = []
        system["gate"]["command"] = ["fake-gate"]
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)
            gate = repository / "fake-gate"
            gate.write_text(
                "#!/bin/sh\n"
                "echo 'Tests run: 4, Failures: 0, Errors: 0, Skipped: 1'\n",
                encoding="utf-8")
            gate.chmod(0o755)
            result = bne_playability.run(
                system, repository, repository / "output", 5)

        self.assertEqual("failed", result["status"])
        self.assertEqual("test gate reported skipped checks", result["failure_reason"])
        self.assertEqual(1, result["skipped_test_summaries"][0]["skipped"])

    def test_a_zero_exit_with_test_failures_fails_closed(self):
        system = copy.deepcopy(self.data["systems"][0])
        system["grade"] = "yellow"
        system["gate"]["required_inputs"] = []
        system["gate"]["command"] = ["fake-gate"]
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)
            gate = repository / "fake-gate"
            gate.write_text(
                "#!/bin/sh\n"
                "echo 'Tests run: 4, Failures: 1, Errors: 0, Skipped: 0'\n",
                encoding="utf-8")
            gate.chmod(0o755)
            result = bne_playability.run(
                system, repository, repository / "output", 5)

        self.assertEqual("failed", result["status"])
        self.assertEqual("test gate reported failures or errors",
                         result["failure_reason"])
        self.assertEqual(1, result["failing_test_summaries"][0]["failures"])

    def test_a_zero_exit_with_test_errors_fails_closed(self):
        system = copy.deepcopy(self.data["systems"][0])
        system["grade"] = "yellow"
        system["gate"]["required_inputs"] = []
        system["gate"]["command"] = ["fake-gate"]
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)
            gate = repository / "fake-gate"
            gate.write_text(
                "#!/bin/sh\n"
                "echo 'Tests run: 4, Failures: 0, Errors: 1, Skipped: 0'\n",
                encoding="utf-8")
            gate.chmod(0o755)
            result = bne_playability.run(
                system, repository, repository / "output", 5)

        self.assertEqual("failed", result["status"])
        self.assertEqual(1, result["failing_test_summaries"][0]["errors"])

    def test_runtime_resolves_the_authenticated_pack(self):
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            repository = parent / "engine"
            repository.mkdir()
            pack = parent / "retail.chonkpack"
            pack.write_bytes(b"pack")
            with mock.patch.dict("os.environ", {}, clear=True), \
                    mock.patch.object(bne_playability, "DEFAULT_BNE_PACK", pack):
                environment = bne_playability.runtime_environment(repository)

        self.assertEqual(str(pack), environment["CHONKCRAFT_ASSET_PACK"])


if __name__ == "__main__":
    unittest.main()
