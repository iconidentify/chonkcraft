#!/usr/bin/env python3

from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))

import bne_test_efficacy


def run(*, returncode=0, tests=1, failures=0, errors=0):
    return {
        "returncode": returncode, "seconds": 1.0, "tests": tests,
        "failures": failures, "errors": errors, "skipped": 0,
        "executed": tests > 0,
        "passed": returncode == 0 and tests > 0
                  and failures == 0 and errors == 0,
    }


class TestEfficacyTest(unittest.TestCase):
    def test_accepts_only_a_pre_fix_assertion_failure_and_candidate_pass(self):
        result = bne_test_efficacy.classify_efficacy(
            run(returncode=1, failures=1), run(),
        )
        self.assertTrue(result["effective"])
        self.assertEqual("effective-regression-test", result["classification"])

    def test_rejects_a_test_that_already_passed_before_the_fix(self):
        result = bne_test_efficacy.classify_efficacy(run(), run())
        self.assertFalse(result["effective"])
        self.assertEqual("false-guarantee", result["classification"])

    def test_does_not_mistake_a_baseline_error_for_expected_failure(self):
        result = bne_test_efficacy.classify_efficacy(
            run(returncode=1, errors=1), run(),
        )
        self.assertEqual("baseline-infrastructure-error",
                         result["classification"])

    def test_parses_maven_test_summary(self):
        parsed = bne_test_efficacy.parse_test_run(
            1, "[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0", 2.0,
        )
        self.assertTrue(parsed["executed"])
        self.assertEqual(1, parsed["failures"])
        self.assertFalse(parsed["passed"])

    def test_a_zero_exit_with_skipped_checks_is_not_a_pass(self):
        parsed = bne_test_efficacy.parse_test_run(
            0, "Tests run: 7, Failures: 0, Errors: 0, Skipped: 7", 1.0,
        )
        self.assertTrue(parsed["executed"])
        self.assertFalse(parsed["passed"])
        result = bne_test_efficacy.classify_efficacy(parsed, run())
        self.assertEqual("baseline-skipped", result["classification"])

    def test_runtime_inputs_become_absolute_maven_properties(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            pack = root / "retail.chonkpack"
            pack.write_bytes(b"pack")
            source = root / "chonkcraft"
            sentinel = source / "scripts/legacyEngine.legacy-declaration"
            sentinel.parent.mkdir(parents=True)
            sentinel.write_text("-- retail definitions\n", encoding="utf-8")

            inputs = bne_test_efficacy.resolve_runtime_inputs(pack, source)
            properties = bne_test_efficacy.runtime_maven_properties(inputs)

            self.assertEqual(str(pack.resolve()), inputs["asset_pack"]["path"])
            self.assertEqual(str(source.resolve()), inputs["source_dir"]["path"])
            self.assertIn(f"-Dchonkcraft.pack={pack.resolve()}", properties)
            self.assertEqual([f"-Dchonkcraft.pack={pack.resolve()}"], properties)

    def test_overlays_candidate_test_sources_on_the_baseline(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "candidate"
            target = Path(temporary) / "baseline"
            tests = root / "engine/src/test/java/example"
            tests.mkdir(parents=True)
            (tests / "ChangedTest.java").write_text(
                "class ChangedTest { int value = 1; }\n", encoding="utf-8",
            )
            (tests / "UnrelatedTest.java").write_text(
                "class UnrelatedTest { int value = 1; }\n", encoding="utf-8",
            )
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            subprocess.run(
                ["git", "-C", str(root), "config", "user.email", "test@example.com"],
                check=True,
            )
            subprocess.run(
                ["git", "-C", str(root), "config", "user.name", "Test"],
                check=True,
            )
            subprocess.run(
                ["git", "-C", str(root), "add", "."], check=True,
            )
            subprocess.run(
                ["git", "-C", str(root), "commit", "-qm", "baseline"],
                check=True,
            )
            baseline = subprocess.run(
                ["git", "-C", str(root), "rev-parse", "HEAD"], check=True,
                capture_output=True, text=True,
            ).stdout.strip()

            (tests / "ChangedTest.java").write_text(
                "class ChangedTest { int value = 2; }\n", encoding="utf-8",
            )
            (tests / "UnrelatedTest.java").write_text(
                "class UnrelatedTest { int value = 2; }\n", encoding="utf-8",
            )
            (tests / "NewTest.java").write_text(
                "class NewTest {}\n", encoding="utf-8",
            )
            target_tests = target / "engine/src/test/java/example"
            target_tests.mkdir(parents=True)
            (target_tests / "ChangedTest.java").write_text(
                "class ChangedTest { int value = 1; }\n", encoding="utf-8",
            )
            (target_tests / "UnrelatedTest.java").write_text(
                "class UnrelatedTest { int value = 1; }\n", encoding="utf-8",
            )

            overlay = bne_test_efficacy.collect_test_overlay(
                root, baseline, "engine", "ChangedTest,NewTest",
            )
            bne_test_efficacy.apply_test_overlay(root, target, overlay)

            self.assertIn("value = 2", (
                target_tests / "ChangedTest.java").read_text(encoding="utf-8"))
            self.assertTrue((target_tests / "NewTest.java").is_file())
            self.assertIn("value = 1", (
                target_tests / "UnrelatedTest.java").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
