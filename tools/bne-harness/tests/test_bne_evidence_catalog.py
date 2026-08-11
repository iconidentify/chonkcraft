"""Tests for the native evidence catalog.

Every capture here is built in code. The catalog exists so that "blocked" stops
meaning three different things at once, so these tests check that each way a
capture can fail to answer a question produces its own answer -- and that a
capture which does answer it is actually offered.
"""

import contextlib
import hashlib
import io
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_evidence_catalog as catalog
import bne_java
from bne_micro_oracle import BNE_202_SHA256

TRACER = "a5dbca005cac8c23b4decee659cfddfa32248ac5cdd51dec80a038cf5db35682"


def identity(data: bytes) -> dict:
    return {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def native_trace(directory: Path, stem: str, *, scenario: str, seed: int,
                 cycles: int, fixture_id: str = "fid-1",
                 executable: str = BNE_202_SHA256,
                 offline: bool = True, body: bytes = b"native trace\n") -> Path:
    """Write a schema-2 native trace and the manifest that authenticates it."""
    directory.mkdir(parents=True, exist_ok=True)
    trace = directory / f"{stem}.trace.txt"
    trace.write_bytes(body)
    manifest = {
        "schema": 2,
        "fixture": {"id": fixture_id},
        "oracle": {"executable": {"sha256": executable, "bytes": 712704}},
        "harness": {"tracer": {"sha256": TRACER, "bytes": 156896}},
        "runtime": {"network_disabled": offline},
        "run": {
            "requested_scenario": scenario,
            "initialization_seed": seed,
            "cycle_limit": cycles,
            "trace": {"name": trace.name, **identity(body)},
        },
    }
    (directory / f"{stem}.manifest.json").write_text(
        json.dumps(manifest), encoding="utf-8")
    return trace


def capture(directory: Path, stem: str, kind: str, *, case: str, seed: int,
            cycle: int, fixture_id: str = "fid-1", fields=("order",),
            executable: str = BNE_202_SHA256, offline: bool = True,
            scenario: str = "Campaign\\Human\\Hum13.pud",
            body: bytes = b'{"decision": true}') -> Path:
    """Write a schema-1 branch or decision capture and its manifest."""
    directory.mkdir(parents=True, exist_ok=True)
    artifact = directory / f"{stem}.{kind}.json"
    artifact.write_bytes(body)
    manifest = {
        "schema": 1,
        "capture": {"name": artifact.name, **identity(body)},
        "oracle": {"executable": {"sha256": executable, "bytes": 712704},
                   "run_manifest": {"cycles": cycle}},
        "harness": {"tracer": {"sha256": TRACER, "bytes": 161383}},
        "runtime": {"network_disabled": offline},
        "request": {"case": case, "seed": seed, "cycle": cycle,
                    "fixture_id": fixture_id, "scenario": scenario,
                    "fields": list(fields)},
    }
    (directory / f"{stem}.{kind}.manifest.json").write_text(
        json.dumps(manifest), encoding="utf-8")
    return artifact


class CatalogBase(unittest.TestCase):
    def setUp(self):
        self.repository = Path(tempfile.mkdtemp()).resolve()
        self.addCleanup(shutil.rmtree, self.repository, ignore_errors=True)
        self.root = self.repository / ".bne-lab" / "native"
        self.root.mkdir(parents=True)

    def build(self, **requirement):
        return catalog.build_catalog(
            self.repository, requirement, roots=[".bne-lab/native"])


class MatchingEvidenceTest(CatalogBase):
    def test_a_capture_of_the_right_run_and_window_is_offered(self):
        native_trace(self.root / "a", "run", scenario="S.pud", seed=1,
                     cycles=50)
        report = self.build(profile="async-rng", scenario="S.pud", seed=1,
                            through_cycle=50)

        self.assertEqual(report["verdict"], catalog.REUSABLE,
                         "a capture of this scenario, seed and window answers "
                         "the question")
        self.assertEqual(len(report["reusable"]), 1)
        self.assertEqual(catalog.exit_code(report), 0)

    def test_a_capture_one_cycle_short_is_not_offered(self):
        native_trace(self.root / "a", "run", scenario="S.pud", seed=1,
                     cycles=50)
        report = self.build(profile="async-rng", scenario="S.pud", seed=1,
                            through_cycle=51)

        self.assertEqual(report["verdict"], catalog.INSUFFICIENT_COVERAGE,
                         "a capture that stops before the open cycle cannot "
                         "answer what happens at it")
        self.assertIn("covers 50 cycles and this blocker needs 51",
                      report["candidates"][0]["why"])
        self.assertEqual(catalog.exit_code(report), 1,
                         "found-but-unusable is not the same exit as nothing "
                         "found")

    def test_a_capture_of_another_seed_is_not_offered(self):
        native_trace(self.root / "a", "run", scenario="S.pud", seed=2,
                     cycles=50)
        report = self.build(profile="async-rng", scenario="S.pud", seed=1,
                            through_cycle=10)

        self.assertEqual(report["verdict"], catalog.WRONG_IDENTITY)
        self.assertIn("seed is 2", report["candidates"][0]["why"])

    def test_a_capture_of_another_fixture_is_not_offered(self):
        native_trace(self.root / "a", "run", scenario="S.pud", seed=1,
                     cycles=50, fixture_id="fid-other")
        report = self.build(profile="async-rng", fixture_id="fid-1",
                            through_cycle=10)

        self.assertEqual(report["verdict"], catalog.WRONG_IDENTITY)
        self.assertIn("fixture", report["candidates"][0]["why"])

    def test_a_decision_capture_is_not_offered_for_an_rng_question(self):
        """Identity matching is not enough; the capture must answer the ask."""
        capture(self.root / "a", "h13", "decision-capture",
                case="retail-human-13-idle", seed=1, cycle=90)
        report = self.build(case="retail-human-13-idle", profile="async-rng",
                            seed=1, through_cycle=50)

        self.assertEqual(report["verdict"], catalog.WRONG_PURPOSE,
                         "a single recorded decision is not a draw ledger")
        self.assertIn("native-trace", report["candidates"][0]["why"])

    def test_the_wrong_case_is_reported_as_the_case_not_the_window(self):
        """A capture of another mission must not read as merely too short."""
        capture(self.root / "a", "h13", "branch-capture",
                case="retail-human-13-idle", seed=1, cycle=10)
        report = self.build(case="retail-xhuman-10-idle",
                            profile="branch-witness", through_cycle=51)

        self.assertEqual(report["verdict"], catalog.WRONG_IDENTITY,
                         "capturing more cycles of Human 13 would not help "
                         "XHuman 10, so the case is the reason to report")
        self.assertIn("retail-human-13-idle", report["candidates"][0]["why"])


class AuthenticationTest(CatalogBase):
    def test_a_trace_edited_after_capture_is_refused(self):
        trace = native_trace(self.root / "a", "run", scenario="S.pud", seed=1,
                             cycles=50)
        trace.write_bytes(b"tampered\n")
        report = self.build(profile="async-rng", scenario="S.pud",
                            through_cycle=10)

        self.assertEqual(report["verdict"], catalog.UNAUTHENTICATED,
                         "bytes that changed since capture are not evidence")

    def test_a_capture_from_another_executable_is_refused(self):
        capture(self.root / "a", "h13", "branch-capture",
                case="c", seed=1, cycle=90, executable="0" * 64)
        report = self.build(case="c", profile="branch-witness",
                            through_cycle=10)

        self.assertEqual(report["verdict"], catalog.STALE_EXECUTABLE,
                         "a capture from a build that is no longer pinned "
                         "cannot be compared against the port")

    def test_a_capture_that_was_not_offline_is_refused(self):
        capture(self.root / "a", "h13", "branch-capture",
                case="c", seed=1, cycle=90, offline=False)
        report = self.build(case="c", profile="branch-witness",
                            through_cycle=10)

        self.assertEqual(report["verdict"], catalog.UNAUTHENTICATED)

    def test_a_capture_whose_artifact_is_gone_is_refused(self):
        artifact = capture(self.root / "a", "h13", "branch-capture",
                           case="c", seed=1, cycle=90)
        artifact.unlink()
        report = self.build(case="c", profile="branch-witness",
                            through_cycle=10)

        self.assertEqual(report["verdict"], catalog.UNAUTHENTICATED)
        self.assertIn("absent", report["candidates"][0]["why"])

    def test_a_manifest_that_is_not_json_is_reported_malformed(self):
        (self.root / "a").mkdir(parents=True)
        (self.root / "a" / "broken.manifest.json").write_text(
            "{not json", encoding="utf-8")
        report = self.build(profile="async-rng", through_cycle=10)

        self.assertEqual(report["verdict"], catalog.MALFORMED)

    def test_a_trace_with_no_manifest_is_never_read(self):
        """A file is not evidence because its name looks useful."""
        (self.root / "a").mkdir(parents=True)
        (self.root / "a" / "tempting.trace.txt").write_bytes(b"x")
        report = self.build(profile="async-rng", through_cycle=10)

        self.assertEqual(report["verdict"], catalog.MISSING,
                         "an orphan trace leaves the catalog with nothing "
                         "authenticated to offer")
        self.assertEqual(len(report["refused"]), 1)
        self.assertIn("no sibling manifest", report["refused"][0]["why"])

    def test_a_harness_run_manifest_is_not_counted_as_a_rejected_capture(self):
        """A tool's own run manifest never claimed to be evidence."""
        (self.root / "a").mkdir(parents=True)
        (self.root / "a" / "manifest.json").write_text(json.dumps({
            "schema": 1, "request_sha256": "abc", "request": {},
            "artifacts": {}}), encoding="utf-8")
        report = self.build(profile="async-rng", through_cycle=10)

        self.assertEqual(report["verdict"], catalog.MISSING,
                         "a run manifest is not a capture that was rejected")
        self.assertEqual(report["counts"].get(catalog.NOT_A_CAPTURE), 1)


class RootBoundaryTest(CatalogBase):
    def test_a_root_outside_the_repository_is_refused(self):
        with self.assertRaises(ValueError) as raised:
            catalog.build_catalog(self.repository, {}, roots=["../elsewhere"])
        self.assertIn("escapes the repository", str(raised.exception))

    def test_an_absolute_root_outside_the_repository_is_refused(self):
        with self.assertRaises(ValueError):
            catalog.build_catalog(self.repository, {}, roots=["/etc"])

    def test_a_manifest_symlinked_in_from_outside_is_refused(self):
        outside = self.repository.parent / (self.repository.name + "-outside")
        outside.mkdir(exist_ok=True)
        self.addCleanup(shutil.rmtree, outside, ignore_errors=True)
        real = outside / "real.manifest.json"
        real.write_text(json.dumps({"schema": 2, "run": {}}), encoding="utf-8")
        link = self.root / "linked.manifest.json"
        try:
            link.symlink_to(real)
        except (OSError, NotImplementedError):
            self.skipTest("this filesystem does not support symlinks")

        report = self.build(profile="async-rng", through_cycle=10)
        self.assertTrue(
            any(r["state"] == "refused-outside-root" for r in report["refused"]),
            "a manifest that resolves outside its evidence root is refused "
            "before it is read")


class PublicationTest(CatalogBase):
    def setUp(self):
        super().setUp()
        native_trace(self.root / "a", "run", scenario="S.pud", seed=1,
                     cycles=50)
        self.artifacts = self.repository / "out"

    def test_a_run_publishes_its_report_and_an_atomic_pointer(self):
        status, run_root = catalog.run_evidence_catalog(
            self.repository, self.artifacts,
            requirement={"profile": "async-rng", "scenario": "S.pud",
                         "through_cycle": 50},
            roots=[".bne-lab/native"])

        self.assertEqual(status, 0)
        for name in ("EVIDENCE-CATALOG.json", "EVIDENCE-CATALOG.md",
                     "manifest.json"):
            self.assertTrue((run_root / name).is_file(), f"published {name}")
        pointer = json.loads(
            (self.artifacts / "latest.json").read_text(encoding="utf-8"))
        self.assertEqual(pointer["verdict"], catalog.REUSABLE)
        self.assertFalse(list(self.artifacts.rglob("*.tmp")),
                         "publication leaves no staging file behind")

    def test_repeating_a_request_reuses_the_sealed_run(self):
        requirement = {"profile": "async-rng", "scenario": "S.pud",
                       "through_cycle": 50}
        first_status, first = catalog.run_evidence_catalog(
            self.repository, self.artifacts, requirement=requirement,
            roots=[".bne-lab/native"])
        body = (first / "EVIDENCE-CATALOG.json").read_bytes()
        again_status, second = catalog.run_evidence_catalog(
            self.repository, self.artifacts, requirement=requirement,
            roots=[".bne-lab/native"])

        self.assertEqual(first, second, "the same request is the same run")
        self.assertEqual(first_status, again_status)
        self.assertEqual(body, (second / "EVIDENCE-CATALOG.json").read_bytes(),
                         "a cache hit does not rewrite the report")

    def test_a_rewritten_report_is_refused_rather_than_served(self):
        requirement = {"profile": "async-rng", "scenario": "S.pud",
                       "through_cycle": 50}
        _status, run_root = catalog.run_evidence_catalog(
            self.repository, self.artifacts, requirement=requirement,
            roots=[".bne-lab/native"])
        (run_root / "EVIDENCE-CATALOG.md").write_text("edited\n",
                                                      encoding="utf-8")

        with self.assertRaises(ValueError) as raised:
            catalog.run_evidence_catalog(
                self.repository, self.artifacts, requirement=requirement,
                roots=[".bne-lab/native"])
        self.assertIn("changed", str(raised.exception))

    def test_nothing_found_is_a_different_exit_from_found_but_unusable(self):
        empty = self.repository / ".bne-lab" / "empty"
        empty.mkdir(parents=True)
        status, _run = catalog.run_evidence_catalog(
            self.repository, self.artifacts / "b",
            requirement={"profile": "async-rng", "through_cycle": 50},
            roots=[".bne-lab/empty"])
        self.assertEqual(status, 2,
                         "nothing to authenticate is not the same as evidence "
                         "that failed a check")


class CommandSurfaceTest(unittest.TestCase):
    def test_the_parser_accepts_the_documented_command(self):
        parsed = bne_java.parser().parse_args([
            "evidence-index",
            "--case", "retail-xhuman-10-idle",
            "--profile", "async-rng",
            "--through", "51",
            "--evidence-root", ".bne-lab/native",
            "--artifact-root", ".bne-evidence-catalog",
        ])
        self.assertEqual(parsed.func, bne_java.evidence_index_command)
        self.assertEqual(parsed.through, 51)
        self.assertEqual(parsed.evidence_root, [".bne-lab/native"])

    def test_an_unsupported_profile_is_refused_by_the_parser(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                bne_java.parser().parse_args(
                    ["evidence-index", "--profile", "telepathy"])


if __name__ == "__main__":
    unittest.main()
