"""Tests for the native capture plan compiler.

The plan exists so a capture stops being retyped from shell history, so these
tests check that the numbers come from the sealed corpus and that the command
it prints is one the real capture harness accepts. A plan that reads well and
does not parse is the failure this replaces.
"""

import contextlib
import hashlib
import io
import json
from pathlib import Path
import shlex
import shutil
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_capture_plan as planner
import bne_headless
import bne_java


class PlanBase(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp()).resolve()
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        self.corpus = self.root / "corpus"
        (self.corpus / "cases").mkdir(parents=True)
        body = b"fixture"
        (self.corpus / "cases" / "c.bnefx").write_bytes(body)
        self.index = self.corpus / "corpus-index.json"
        self.index.write_text(json.dumps({"schema": 1, "cases": [{
            "id": "retail-xhuman-10-idle",
            "scenario": "Campaign\\XHuman\\2XHum10.pud",
            "seed": 1,
            "fixture_id": "fid-1",
            "fixture": {"path": "cases/c.bnefx", "bytes": len(body),
                        "sha256": hashlib.sha256(body).hexdigest()},
        }]}), encoding="utf-8")

    def plan(self, **kwargs):
        record = planner.resolve_case(self.index, "retail-xhuman-10-idle")
        return planner.build_plan(record, **kwargs)


class DerivedIdentityTest(PlanBase):
    def test_the_scenario_and_seed_come_from_the_index_not_the_caller(self):
        """A capture run against a remembered seed is a different experiment."""
        plan = self.plan(profile="async-rng", through=51)

        self.assertEqual(plan["case"]["scenario"], "Campaign\\XHuman\\2XHum10.pud")
        self.assertEqual(plan["case"]["seed"], 1)
        command = plan["capture_commands"][0]
        self.assertIn("--seed 1", command)
        self.assertIn("--cycles 51", command)
        self.assertIn("2XHum10.pud", command)

    def test_a_fixture_edited_since_sealing_is_refused(self):
        (self.corpus / "cases" / "c.bnefx").write_bytes(b"tampered")
        with self.assertRaises(ValueError) as raised:
            planner.resolve_case(self.index, "retail-xhuman-10-idle")
        self.assertIn("identity changed", str(raised.exception))

    def test_an_index_entry_reaching_outside_the_corpus_is_refused(self):
        self.index.write_text(json.dumps({"schema": 1, "cases": [{
            "id": "c", "scenario": "S.pud", "seed": 1, "fixture_id": "f",
            "fixture": {"path": "../../escape.bnefx", "bytes": 1,
                        "sha256": "0" * 64}}]}), encoding="utf-8")
        with self.assertRaises(ValueError) as raised:
            planner.resolve_case(self.index, "c")
        self.assertIn("unsafe fixture path", str(raised.exception))

    def test_an_absent_case_is_refused(self):
        with self.assertRaises(ValueError):
            planner.resolve_case(self.index, "retail-nowhere-idle")


class ProfileTest(PlanBase):
    def test_an_unsupported_profile_fails_explicitly(self):
        with self.assertRaises(planner.UnsupportedProfile) as raised:
            self.plan(profile="telepathy", through=10)
        self.assertIn("telepathy", str(raised.exception))
        self.assertIn("supported profiles are", str(raised.exception),
                      "the refusal says what would have worked")

    def test_a_focused_profile_without_a_unit_fails_rather_than_guessing(self):
        """`--native-unit 0` was a placeholder standing in for not knowing."""
        with self.assertRaises(ValueError) as raised:
            self.plan(profile="branch-witness", through=51)
        self.assertIn("needs --native-unit", str(raised.exception))

    def test_the_plan_never_offers_to_run_the_capture(self):
        plan = self.plan(profile="async-rng", through=51)
        self.assertTrue(plan["dry_run"], "there is no mode that executes")
        text = planner.format_plan(plan)
        self.assertIn("This is a dry run", text)


class GeneratedCommandTest(PlanBase):
    """The capture harness's parser is the authority on what it accepts."""

    def parse_capture(self, plan):
        parsed = []
        for command in plan["capture_commands"]:
            arguments = shlex.split(command)
            self.assertEqual("python3", arguments[0])
            self.assertTrue(arguments[1].endswith("bne_headless.py"),
                            f"not a capture command: {command}")
            try:
                parsed.append(bne_headless.parser().parse_args(arguments[2:]))
            except SystemExit as failure:
                self.fail(f"the capture harness rejected a generated plan: "
                          f"{command!r} (exit {failure.code})")
        return parsed

    def test_every_supported_profile_produces_a_command_that_parses(self):
        checked = 0
        for profile in sorted(planner.PROFILES):
            needs_unit = planner.PROFILES[profile]["needs_unit"]
            plan = self.plan(profile=profile, through=51,
                             native_unit=1502 if needs_unit else None,
                             field="order")
            for parsed in self.parse_capture(plan):
                self.assertEqual(parsed.cycles, 51,
                                 f"{profile} lost its cycle bound")
                self.assertEqual(parsed.seed, 1, f"{profile} lost its seed")
            checked += 1
        self.assertEqual(checked, len(planner.PROFILES),
                         "every profile must be swept, or this proves nothing")

    def test_the_inspect_command_parses_through_the_real_cli(self):
        plan = self.plan(profile="async-rng", through=51)
        arguments = shlex.split(plan["inspect_command"])
        try:
            parsed = bne_java.parser().parse_args(arguments[2:])
        except SystemExit as failure:
            self.fail(f"the inspect command does not parse (exit {failure.code})")
        self.assertEqual(parsed.func, bne_java.evidence_index_command)

    def test_no_plan_embeds_a_hand_written_container_invocation(self):
        for profile in sorted(planner.PROFILES):
            needs_unit = planner.PROFILES[profile]["needs_unit"]
            plan = self.plan(profile=profile, through=51,
                             native_unit=1 if needs_unit else None,
                             field="order")
            for command in plan["capture_commands"]:
                self.assertNotIn("docker", command.lower(),
                                 "the recipe is the capture harness, which "
                                 "builds the container invocation itself")
                self.assertIn("bne_headless.py", command)

    def test_no_plan_embeds_a_credential_or_a_retail_executable_path(self):
        for profile in sorted(planner.PROFILES):
            needs_unit = planner.PROFILES[profile]["needs_unit"]
            plan = self.plan(profile=profile, through=51,
                             native_unit=1 if needs_unit else None,
                             field="order")
            blob = json.dumps(plan).lower()
            for secret in ("password", "token", "cd-key", "cdkey",
                           "warcraft ii bne.exe", "authorization"):
                self.assertNotIn(secret, blob,
                                 f"{profile} embedded {secret!r} in an artifact "
                                 "that gets committed")


class PublicationTest(PlanBase):
    def test_a_plan_publishes_and_repeats_as_a_cache_hit(self):
        artifacts = self.root / "out"
        status, first = planner.run_capture_plan(
            self.index, artifacts, case="retail-xhuman-10-idle",
            profile="async-rng", through=51)
        self.assertEqual(status, 0)
        for name in ("CAPTURE-PLAN.json", "CAPTURE-PLAN.md", "manifest.json"):
            self.assertTrue((first / name).is_file(), f"published {name}")
        body = (first / "CAPTURE-PLAN.json").read_bytes()

        _again, second = planner.run_capture_plan(
            self.index, artifacts, case="retail-xhuman-10-idle",
            profile="async-rng", through=51)
        self.assertEqual(first, second)
        self.assertEqual(body, (second / "CAPTURE-PLAN.json").read_bytes())

    def test_a_different_window_is_a_different_plan(self):
        artifacts = self.root / "out"
        _s, first = planner.run_capture_plan(
            self.index, artifacts, case="retail-xhuman-10-idle",
            profile="async-rng", through=51)
        _s2, second = planner.run_capture_plan(
            self.index, artifacts, case="retail-xhuman-10-idle",
            profile="async-rng", through=80)
        self.assertNotEqual(first, second,
                            "a longer window is a different capture")


class CommandSurfaceTest(unittest.TestCase):
    def test_the_parser_accepts_the_documented_command(self):
        parsed = bne_java.parser().parse_args([
            "capture-plan", "--case", "retail-xhuman-10-idle",
            "--profile", "async-rng", "--through", "51",
            "--index", "work/corpus/campaign-1800/corpus-index.json",
        ])
        self.assertEqual(parsed.func, bne_java.capture_plan_command)

    def test_an_unsupported_profile_is_refused_by_the_parser(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                bne_java.parser().parse_args([
                    "capture-plan", "--case", "c", "--profile", "telepathy",
                    "--through", "5", "--index", "i.json"])


if __name__ == "__main__":
    unittest.main()
