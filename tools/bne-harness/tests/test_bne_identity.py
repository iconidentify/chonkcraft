import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_identity


def git(root, *arguments):
    return subprocess.run(
        ["git", "-C", str(root), *arguments],
        check=True, capture_output=True, text=True,
        env={**os.environ,
             "GIT_AUTHOR_NAME": "identity test",
             "GIT_AUTHOR_EMAIL": "identity@example.invalid",
             "GIT_COMMITTER_NAME": "identity test",
             "GIT_COMMITTER_EMAIL": "identity@example.invalid"},
    ).stdout


def write(root, relative, content):
    path = Path(root) / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return path


class EngineInputIdentityTest(unittest.TestCase):
    """The engine cache key must move with the engine and nothing else."""

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="bne-identity-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        git(self.root, "init", "-q", "-b", "main")
        write(self.root, "pom.xml", "<project><modules/></project>\n")
        write(self.root, "engine/src/main/java/World.java", "class World {}\n")
        write(self.root, "engine/src/main/resources/units.legacy-declaration", "Units = {}\n")
        write(self.root, "data/src/main/java/Pud.java", "class Pud {}\n")
        write(self.root, "retired-interpreter/src/main/java/retired scripting language.java", "class retired scripting language {}\n")
        write(self.root, "runtime/src/main/java/Sdl.java", "class Sdl {}\n")
        write(self.root, "assetpack/src/main/java/Pack.java", "class Pack {}\n")
        write(self.root, "desktop/src/main/java/Screen.java", "class Screen {}\n")
        write(self.root, "tools/bne-harness/scripts/bne_java.py", "print(1)\n")
        write(self.root, "STATUS.md", "current posture\n")
        write(self.root, "engine/fixture-prose.md", "prose\n")
        git(self.root, "add", "-A")
        git(self.root, "commit", "-qm", "base")

    def identity(self):
        return bne_identity.engine_input_identity(self.root)["engine_input_sha256"]

    def test_diagnostic_evidence_never_moves_the_engine_identity(self):
        """Writing a report about the engine is not a change to the engine."""
        before = self.identity()
        write(self.root, ".bne-rng-ledger/latest.json", '{"runs": 1}\n')
        write(self.root, ".bne-state-machine/runs/a/STATE-MACHINE.json", "{}\n")
        write(self.root, ".bne-artifacts/latest-accepted.json", '{"schema": 1}\n')
        write(self.root, ".bne-frontier-evidence/latest.json", "{}\n")
        write(self.root, "goal/scratch/fixture-scratch.md", "notes\n")
        write(self.root, "tools/bne-harness/work/java-corpus/case.trace.txt", "x\n")
        write(self.root, "gate-run.log", "BUILD SUCCESS\n")
        self.assertEqual(
            before, self.identity(),
            "diagnostic evidence changed the engine input hash",
        )
        write(self.root, ".bne-rng-ledger/latest.json", '{"runs": 2}\n')
        self.assertEqual(
            before, self.identity(),
            "rewriting a ledger report changed the engine input hash",
        )

    def test_excluded_evidence_does_not_report_the_engine_as_dirty(self):
        """A clean engine beside 76 MB of evidence is still a clean engine."""
        write(self.root, ".bne-artifacts/runs/a/manifest.json", "{}\n")
        write(self.root, "goal/scratch/fixture-scratch.md", "notes\n")
        scanned = bne_identity.scan(self.root)
        self.assertFalse(
            scanned["identity"]["dirty"],
            "untouched engine source was reported dirty by adjacent evidence",
        )
        self.assertEqual(
            2, scanned["noise"]["excluded_path_count"],
            "the excluded workspace churn was not reported separately",
        )
        self.assertGreater(
            scanned["noise"]["excluded_bytes"], 0,
            "excluded churn was reported without its size",
        )

    def test_editing_tracked_engine_source_moves_the_identity(self):
        before = self.identity()
        write(self.root, "engine/src/main/java/World.java",
              "class World { int hp = 90; }\n")
        self.assertNotEqual(
            before, self.identity(),
            "an edit to tracked engine source did not change the engine input hash",
        )
        self.assertTrue(
            bne_identity.engine_input_identity(self.root)["dirty"],
            "edited engine source was not reported dirty",
        )

    def test_staging_a_source_change_moves_the_identity(self):
        write(self.root, "engine/src/main/java/World.java",
              "class World { int hp = 90; }\n")
        unstaged = self.identity()
        git(self.root, "add", "engine/src/main/java/World.java")
        self.assertNotEqual(
            unstaged, self.identity(),
            "staging a source change left the engine input hash unchanged",
        )

    def test_staged_content_differs_from_the_working_tree(self):
        """A staged edit reverted on disk is still a different engine."""
        base = self.identity()
        path = write(self.root, "engine/src/main/java/World.java",
                     "class World { int hp = 90; }\n")
        git(self.root, "add", "engine/src/main/java/World.java")
        path.write_text("class World {}\n", encoding="utf-8")
        self.assertNotEqual(
            base, self.identity(),
            "an index-only source difference was invisible to the engine identity",
        )

    def test_a_new_untracked_engine_file_moves_the_identity(self):
        before = self.identity()
        write(self.root, "engine/src/main/java/Missile.java", "class Missile {}\n")
        after = self.identity()
        self.assertNotEqual(
            before, after,
            "a new untracked engine source file did not change the engine input hash",
        )
        write(self.root, "engine/src/main/resources/missiles.legacy-declaration", "Missiles = {}\n")
        self.assertNotEqual(
            after, self.identity(),
            "a new untracked engine resource did not change the engine input hash",
        )

    def test_a_new_harness_script_moves_the_identity(self):
        before = self.identity()
        write(self.root, "tools/bne-harness/scripts/bne_frontier.py", "pass\n")
        self.assertNotEqual(
            before, self.identity(),
            "a new harness script did not change the engine input hash",
        )

    def test_unrelated_scratch_does_not_move_the_identity(self):
        before = self.identity()
        write(self.root, "scratch.txt", "anything\n")
        write(self.root, "tools/bne-harness/scratch/probe.py", "print(2)\n")
        write(self.root, "STATUS.md", "updated posture\n")
        write(self.root, "engine/fixture-prose.md", "different prose\n")
        write(self.root, "desktop/src/main/java/Screen.java", "class Screen { }\n")
        self.assertEqual(
            before, self.identity(),
            "unrelated scratch produced a false engine cache miss",
        )

    def test_committing_excluded_prose_does_not_move_the_identity(self):
        before = bne_identity.engine_input_identity(self.root)
        before_authority = bne_identity.engine_input_authority(self.root)
        write(self.root, "STATUS.md", "published posture\n")
        git(self.root, "add", "STATUS.md")
        git(self.root, "commit", "-qm", "publish status")
        after = bne_identity.engine_input_identity(self.root)
        self.assertNotEqual(before["head"], after["head"])
        self.assertEqual(
            before["engine_input_sha256"], after["engine_input_sha256"],
            "an excluded prose-only commit invalidated engine evidence",
        )
        self.assertEqual(
            before_authority, bne_identity.engine_input_authority(self.root),
            "an excluded prose-only commit invalidated artifact authority",
        )
        self.assertNotIn("head", before_authority)

    def test_pathspec_identity_ignores_commits_outside_its_closure(self):
        before = bne_identity.pathspec_input_sha256(
            self.root, ("engine",), policy="test-program-v1")
        write(self.root, "STATUS.md", "published posture\n")
        git(self.root, "add", "STATUS.md")
        git(self.root, "commit", "-qm", "publish status")
        self.assertEqual(before, bne_identity.pathspec_input_sha256(
            self.root, ("engine",), policy="test-program-v1"))
        write(self.root, "engine/src/main/java/World.java",
              "class World { int hp = 90; }\n")
        self.assertNotEqual(before, bne_identity.pathspec_input_sha256(
            self.root, ("engine",), policy="test-program-v1"))

    def test_build_output_inside_a_module_is_not_an_input(self):
        before = self.identity()
        write(self.root, "engine/target/classes/World.class", "\0\0\0\0")
        self.assertEqual(
            before, self.identity(),
            "compiled output was treated as an engine input",
        )

    def test_repeating_the_scan_reproduces_the_identity(self):
        first = bne_identity.engine_input_identity(self.root)
        second = bne_identity.engine_input_identity(self.root)
        self.assertEqual(
            first, second,
            "two scans of one workspace disagreed about the engine identity",
        )

    def test_a_reverted_edit_returns_to_the_original_identity(self):
        before = self.identity()
        path = self.root / "engine/src/main/java/World.java"
        original = path.read_text(encoding="utf-8")
        path.write_text("class World { int hp = 90; }\n", encoding="utf-8")
        self.assertNotEqual(before, self.identity())
        path.write_text(original, encoding="utf-8")
        self.assertEqual(
            before, self.identity(),
            "reverting an edit did not restore the original engine identity",
        )

    def test_a_deleted_engine_file_moves_the_identity(self):
        before = self.identity()
        (self.root / "engine/src/main/java/World.java").unlink()
        self.assertNotEqual(
            before, self.identity(),
            "deleting engine source did not change the engine input hash",
        )


class IdentityPolicyTest(unittest.TestCase):
    """The declared policy is what makes an old proof readable but not current."""

    def test_evidence_and_prose_are_outside_the_declared_inputs(self):
        for relative in (
                ".bne-artifacts/latest.json", "goal/scratch/fixture-scratch.md",
                "tools/bne-harness/work/java-corpus/a.trace.txt",
                "tools/bne-harness/PARITY.md", "engine/README.md",
                "engine/target/classes/A.class", "desktop/src/main/java/A.java",
                "gate.log", "../escape.java", "/absolute.java"):
            self.assertFalse(
                bne_identity.is_engine_input(relative),
                f"{relative} was treated as an engine input",
            )

    def test_simulation_and_harness_sources_are_inside_the_declared_inputs(self):
        for relative in (
                "pom.xml", "engine/pom.xml",
                "engine/src/main/java/net/chonkbase/chonkcraft/engine/World.java",
                "engine/src/test/java/net/chonkbase/chonkcraft/engine/WorldTest.java",
                "data/src/main/java/A.java", "retired-interpreter/src/main/java/A.java",
                "runtime/src/main/java/A.java", "assetpack/src/main/java/A.java",
                "tools/bne-harness/scripts/bne_java.py",
                "tools/bne-harness/parity-lab-policy.json"):
            self.assertTrue(
                bne_identity.is_engine_input(relative),
                f"{relative} was left out of the engine inputs",
            )

    def test_a_legacy_workspace_record_is_never_read_as_current(self):
        legacy = {"head": "abc", "dirty": True, "workspace_sha256": "ff"}
        self.assertTrue(
            bne_identity.is_legacy_identity(legacy),
            "a pre-hermetic engine record was accepted as a current identity",
        )
        self.assertIn("legacy", bne_identity.describe_identity(legacy))

    def test_a_current_record_is_recognized_by_its_declared_policy(self):
        current = {
            "schema": bne_identity.IDENTITY_SCHEMA,
            "policy": bne_identity.INPUT_POLICY,
            "head": "a" * 40, "dirty": False,
            "engine_input_sha256": "b" * 64, "input_count": 3,
        }
        self.assertFalse(
            bne_identity.is_legacy_identity(current),
            "a current engine identity was misread as legacy",
        )
        self.assertNotIn("legacy", bne_identity.describe_identity(current))

    def test_a_future_schema_cannot_alias_this_one(self):
        future = {
            "schema": bne_identity.IDENTITY_SCHEMA + 1,
            "policy": "engine-input-v2",
            "head": "a" * 40, "dirty": False, "engine_input_sha256": "b" * 64,
        }
        self.assertTrue(
            bne_identity.is_legacy_identity(future),
            "a differently declared identity policy was read as this one",
        )


if __name__ == "__main__":
    unittest.main()
