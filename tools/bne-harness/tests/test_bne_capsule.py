import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_capsule
import bne_identity


ENVIRONMENT = {
    "GIT_AUTHOR_NAME": "capsule test",
    "GIT_AUTHOR_EMAIL": "capsule@example.invalid",
    "GIT_COMMITTER_NAME": "capsule test",
    "GIT_COMMITTER_EMAIL": "capsule@example.invalid",
}


def git(root, *arguments):
    return subprocess.run(
        ["git", "-C", str(root), *arguments], check=True,
        capture_output=True, text=True, env={**os.environ, **ENVIRONMENT},
    ).stdout


def write(root, relative, content):
    path = Path(root) / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return path


class SourceCapsuleTest(unittest.TestCase):
    """An accepted proof must be replayable from the source it really ran on."""

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="bne-capsule-test-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name) / "repo"
        self.root.mkdir()
        git(self.root, "init", "-q", "-b", "main")
        write(self.root, "pom.xml", "<project/>\n")
        write(self.root, "engine/src/main/java/World.java", "class World {}\n")
        write(self.root, "data/src/main/java/Pud.java", "class Pud {}\n")
        write(self.root, "tools/bne-harness/scripts/bne_java.py", "print(1)\n")
        git(self.root, "add", "-A")
        git(self.root, "commit", "-qm", "base")
        self.capsules = Path(self.directory.name) / "capsules"
        self.capsules.mkdir()

    def dirty_workspace(self):
        """An unstaged edit, a staged edit and a new untracked source file."""
        write(self.root, "engine/src/main/java/World.java",
              "class World { int hp = 90; }\n")
        write(self.root, "data/src/main/java/Pud.java",
              "class Pud { int tiles = 128; }\n")
        git(self.root, "add", "data/src/main/java/Pud.java")
        write(self.root, "engine/src/main/java/Missile.java",
              "class Missile { int splash = 1; }\n")
        write(self.root, ".bne-artifacts/runs/a/manifest.json", '{"schema": 1}\n')
        write(self.root, "goal/scratch/fixture-scratch.md", "operator memory\n")
        write(self.root, "gate.log", "BUILD SUCCESS\n")

    def seal(self, name="capsule"):
        return bne_capsule.seal(self.root, self.capsules / name)

    def test_a_dirty_source_state_replays_its_engine_identity_exactly(self):
        """The whole point: an accepted dirty proof can be rebuilt elsewhere."""
        self.dirty_workspace()
        sealed = bne_identity.engine_input_identity(self.root)
        self.seal()
        result = bne_capsule.replay_identity(self.capsules / "capsule", self.root)
        self.assertTrue(
            result["reproduced_exactly"],
            f"the replayed workspace is a different engine: {result}",
        )
        self.assertEqual(
            sealed["engine_input_sha256"],
            result["reproduced"]["engine_input_sha256"],
            "the materialized capsule did not reproduce the sealed identity",
        )

    def test_a_clean_workspace_replays_too(self):
        self.seal()
        result = bne_capsule.replay_identity(self.capsules / "capsule", self.root)
        self.assertTrue(
            result["reproduced_exactly"],
            "a committed workspace failed to replay its own identity",
        )

    def test_sealing_leaves_the_operator_index_alone(self):
        self.dirty_workspace()
        before = git(self.root, "status", "--porcelain")
        index = Path(git(self.root, "rev-parse", "--absolute-git-dir").strip()) / "index"
        index_before = index.read_bytes()
        self.seal()
        self.assertEqual(
            before, git(self.root, "status", "--porcelain"),
            "sealing a capsule changed what git reports as staged",
        )
        self.assertEqual(
            index_before, index.read_bytes(),
            "sealing a capsule rewrote the operator's index",
        )

    def test_evidence_and_operator_memory_never_enter_the_capsule(self):
        self.dirty_workspace()
        self.seal()
        sealed = {record["path"]
                  for record in bne_capsule.load(self.capsules / "capsule")["untracked"]}
        self.assertEqual(
            {"engine/src/main/java/Missile.java"}, sealed,
            "the capsule sealed something other than engine source",
        )
        present = {str(path.relative_to(self.capsules / "capsule"))
                   for path in (self.capsules / "capsule").rglob("*")
                   if path.is_file()}
        for unwanted in (".bne-artifacts", "goal/", "gate.log"):
            self.assertFalse(
                any(unwanted in name for name in present),
                f"{unwanted} travelled inside the source capsule",
            )

    def test_an_untracked_source_file_that_is_omitted_is_caught(self):
        """A capsule that lost a relevant input must fail, not replay wrong."""
        self.dirty_workspace()
        self.seal()
        capsule = self.capsules / "capsule"
        (capsule / "untracked/engine/src/main/java/Missile.java").unlink()
        with self.assertRaises(bne_capsule.CapsuleError) as caught:
            bne_capsule.verify(capsule)
        self.assertIn("missing", str(caught.exception))

    def test_evidence_changed_after_sealing_is_refused(self):
        self.dirty_workspace()
        self.seal()
        capsule = self.capsules / "capsule"
        target = capsule / "untracked/engine/src/main/java/Missile.java"
        target.write_text("class Missile { int splash = 2; }\n", encoding="utf-8")
        with self.assertRaises(bne_capsule.CapsuleError) as caught:
            bne_capsule.verify(capsule)
        self.assertIn("identity changed", str(caught.exception))

    def test_a_rewritten_patch_is_refused(self):
        self.dirty_workspace()
        self.seal()
        capsule = self.capsules / "capsule"
        (capsule / bne_capsule.WORKTREE_PATCH_NAME).write_bytes(b"not a patch\n")
        with self.assertRaises(bne_capsule.CapsuleError) as caught:
            bne_capsule.verify(capsule)
        self.assertIn("patch identity changed", str(caught.exception))

    def test_a_rewritten_manifest_is_refused(self):
        self.dirty_workspace()
        self.seal()
        capsule = self.capsules / "capsule"
        manifest = json.loads((capsule / "capsule.json").read_text())
        manifest["base_head"] = "0" * 40
        (capsule / "capsule.json").write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaises(bne_capsule.CapsuleError) as caught:
            bne_capsule.verify(capsule)
        self.assertIn("manifest identity changed", str(caught.exception))

    def test_a_capsule_cannot_write_outside_itself(self):
        """A hostile manifest must not place a file above the replay root."""
        self.dirty_workspace()
        self.seal()
        capsule = self.capsules / "capsule"
        manifest = json.loads((capsule / "capsule.json").read_text())
        for hostile in ("../escape.java", "/etc/passwd",
                        "engine/../../escape.java"):
            manifest["untracked"] = [
                {"path": hostile, "mode": "100644", "bytes": 1, "sha256": "0" * 64},
            ]
            manifest["capsule_sha256"] = bne_capsule.canonical_digest(
                {key: value for key, value in manifest.items()
                 if key not in ("created_at", "capsule_sha256")}
            )
            (capsule / "capsule.json").write_text(
                json.dumps(manifest), encoding="utf-8")
            with self.assertRaises(bne_capsule.CapsuleError, msg=hostile):
                bne_capsule.verify(capsule)

    def test_a_symlinked_capsule_input_is_refused(self):
        self.dirty_workspace()
        self.seal()
        capsule = self.capsules / "capsule"
        target = capsule / "untracked/engine/src/main/java/Missile.java"
        outside = Path(self.directory.name) / "outside.java"
        outside.write_text("class Missile { int splash = 3; }\n", encoding="utf-8")
        target.unlink()
        target.symlink_to(outside)
        with self.assertRaises(bne_capsule.CapsuleError):
            bne_capsule.verify(capsule)

    def test_a_symlinked_source_file_is_never_sealed_as_source(self):
        outside = Path(self.directory.name) / "outside.java"
        outside.write_text("class Elsewhere {}\n", encoding="utf-8")
        (self.root / "engine/src/main/java/Linked.java").symlink_to(outside)
        with self.assertRaises(bne_capsule.CapsuleError) as caught:
            self.seal()
        self.assertIn("symlink", str(caught.exception))

    def test_a_capsule_whose_base_commit_is_unknown_is_refused(self):
        self.seal()
        capsule = self.capsules / "capsule"
        elsewhere = Path(self.directory.name) / "other"
        elsewhere.mkdir()
        git(elsewhere, "init", "-q", "-b", "main")
        write(elsewhere, "pom.xml", "<project/>\n")
        git(elsewhere, "add", "-A")
        git(elsewhere, "commit", "-qm", "unrelated")
        with self.assertRaises(bne_capsule.CapsuleError) as caught:
            bne_capsule.replay_identity(capsule, elsewhere)
        self.assertIn("absent", str(caught.exception))

    def test_an_oversized_input_is_refused_rather_than_sealed(self):
        write(self.root, "engine/src/main/resources/huge.bin",
              "x" * (bne_capsule.MAXIMUM_FILE_BYTES + 1))
        with self.assertRaises(bne_capsule.CapsuleError) as caught:
            self.seal()
        self.assertIn("too large", str(caught.exception))

    def test_sealing_twice_into_the_same_place_is_refused(self):
        self.seal()
        with self.assertRaises(bne_capsule.CapsuleError):
            self.seal()

    def test_two_seals_of_one_workspace_agree(self):
        self.dirty_workspace()
        first = self.seal("one")
        second = self.seal("two")
        self.assertEqual(
            first["capsule_sha256"], second["capsule_sha256"],
            "sealing the same workspace twice produced two capsule identities",
        )

    def test_a_changed_workspace_produces_a_different_capsule(self):
        first = self.seal("one")
        self.dirty_workspace()
        second = self.seal("two")
        self.assertNotEqual(
            first["capsule_sha256"], second["capsule_sha256"],
            "a changed workspace sealed to the same capsule identity",
        )

    def test_the_replay_worktree_is_disposed_of(self):
        self.dirty_workspace()
        self.seal()
        with bne_capsule.materialize(self.capsules / "capsule", self.root) as (
                replay, _manifest):
            self.assertTrue(replay.is_dir())
            location = replay
        self.assertFalse(
            location.exists(), "the disposable replay worktree was left behind")
        self.assertNotIn(
            str(location), git(self.root, "worktree", "list"),
            "the replay worktree stayed registered with the repository",
        )


class LegacyReceiptTest(unittest.TestCase):
    """A proof recorded before capsules must say so rather than guess."""

    def test_a_legacy_engine_record_is_reported_as_non_replayable(self):
        state = bne_capsule.legacy_state(
            {"head": "abc", "dirty": True, "workspace_sha256": "ff"})
        self.assertFalse(state["replayable"])
        self.assertEqual("legacy-no-capsule", state["state"])
        self.assertIn("legacy", state["engine_identity"])
        self.assertIn("bne_java.py gate", state["recovery"])


if __name__ == "__main__":
    unittest.main()
