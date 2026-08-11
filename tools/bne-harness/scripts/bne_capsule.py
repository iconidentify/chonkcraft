#!/usr/bin/env python3
"""Seal the uncommitted source an accepted proof was actually built from."""

from __future__ import annotations

from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tempfile
from typing import Any, Iterator

import bne_identity


CAPSULE_SCHEMA = 1
CAPSULE_KIND = "engine-source-capsule"

#: A capsule holds source, never fixtures, packs or captured evidence. These
#: ceilings exist so a stray large file is refused loudly instead of quietly
#: turning a proof receipt into an archive.
MAXIMUM_FILE_BYTES = 4 * 1024 * 1024
MAXIMUM_TOTAL_BYTES = 32 * 1024 * 1024

MANIFEST_NAME = "capsule.json"
STAGED_PATCH_NAME = "staged.patch"
WORKTREE_PATCH_NAME = "worktree.patch"
UNTRACKED_DIRECTORY = "untracked"


class CapsuleError(RuntimeError):
    """A capsule could not be sealed, authenticated or materialized."""


def _git(root: Path, *arguments: str, index: Path | None = None) -> bytes:
    environment = dict(os.environ)
    if index is not None:
        environment["GIT_INDEX_FILE"] = str(index)
    completed = subprocess.run(
        ["git", "-C", str(root), *arguments],
        check=False, capture_output=True, env=environment,
    )
    if completed.returncode != 0:
        message = completed.stderr.decode("utf-8", "replace").strip()
        raise CapsuleError(f"git {' '.join(arguments)} failed: {message}")
    return completed.stdout


def file_identity(path: Path) -> dict[str, int | str]:
    data = path.read_bytes()
    return {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def canonical_digest(value: object) -> str:
    encoded = json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=True,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def tool_identity() -> dict[str, Any]:
    """Name the code a reader needs in order to trust and replay a capsule."""
    here = Path(__file__).resolve()
    version = subprocess.run(
        ["git", "--version"], check=False, capture_output=True, text=True,
    )
    return {
        "capsule": file_identity(here),
        "identity": file_identity(here.with_name("bne_identity.py")),
        "identity_schema": bne_identity.IDENTITY_SCHEMA,
        "input_policy": bne_identity.INPUT_POLICY,
        "git": (version.stdout + version.stderr).strip(),
    }


def _safe_relative(name: str) -> PurePosixPath:
    """Refuse anything that could write outside the directory it names."""
    candidate = PurePosixPath(name)
    if candidate.is_absolute() or not name or name != str(candidate):
        raise CapsuleError(f"unsafe capsule path: {name!r}")
    if any(part in ("", ".", "..") for part in candidate.parts):
        raise CapsuleError(f"unsafe capsule path: {name!r}")
    return candidate


@contextmanager
def _detached_index(root: Path) -> Iterator[Path]:
    """Read the index through a copy, so sealing never touches the operator's."""
    location = _git(root, "rev-parse", "--git-path", "index").decode().strip()
    original = (root / location).resolve() if not Path(location).is_absolute() \
        else Path(location)
    with tempfile.TemporaryDirectory(prefix="bne-capsule-index-") as temporary:
        copy = Path(temporary) / "index"
        if original.is_file():
            shutil.copy2(original, copy)
        yield copy


def seal(root: Path, destination: Path) -> dict[str, Any]:
    """Write a content-addressed capsule of the declared engine inputs."""
    root = Path(root).expanduser().resolve()
    destination = Path(destination).expanduser().resolve()
    if destination.exists() and any(destination.iterdir()):
        raise CapsuleError(f"capsule destination is not empty: {destination}")
    scanned = bne_identity.scan(root)
    identity = scanned["identity"]
    pathspecs = [f":(top){spec}" for spec in bne_identity.ENGINE_INPUT_PATHSPECS]

    with _detached_index(root) as index:
        staged = _git(root, "diff", "--binary", "--cached", "HEAD", "--",
                      *pathspecs, index=index)
        worktree = _git(root, "diff", "--binary", "HEAD", "--",
                        *pathspecs, index=index)

    untracked: list[str] = []
    for name, staged_entry, _working in scanned["inputs"]:
        if staged_entry == "-":
            untracked.append(name)

    destination.mkdir(parents=True, exist_ok=True)
    (destination / STAGED_PATCH_NAME).write_bytes(staged)
    (destination / WORKTREE_PATCH_NAME).write_bytes(worktree)

    total = len(staged) + len(worktree)
    sealed_untracked: list[dict[str, Any]] = []
    for name in sorted(untracked):
        relative = _safe_relative(name)
        source = root / relative
        if source.is_symlink():
            raise CapsuleError(f"refusing to seal a symlink as source: {name}")
        if not source.is_file():
            continue
        size = source.stat().st_size
        if size > MAXIMUM_FILE_BYTES:
            raise CapsuleError(
                f"untracked engine input is too large to seal ({size} bytes): "
                f"{name}"
            )
        total += size
        if total > MAXIMUM_TOTAL_BYTES:
            raise CapsuleError(
                "the declared engine inputs exceed the capsule ceiling "
                f"({total} bytes); this is source only, not evidence"
            )
        target = destination / UNTRACKED_DIRECTORY / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        sealed_untracked.append({
            "path": str(relative),
            "mode": "100755" if os.access(source, os.X_OK) else "100644",
            **file_identity(target),
        })

    manifest = {
        "schema": CAPSULE_SCHEMA,
        "kind": CAPSULE_KIND,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "base_head": identity["head"],
        "engine_identity": identity,
        "input_policy": bne_identity.INPUT_POLICY,
        "declared_pathspecs": list(bne_identity.ENGINE_INPUT_PATHSPECS),
        "excluded_segments": sorted(bne_identity.EXCLUDED_SEGMENTS),
        "excluded_suffixes": list(bne_identity.EXCLUDED_SUFFIXES),
        "tool": tool_identity(),
        "patches": {
            STAGED_PATCH_NAME: file_identity(destination / STAGED_PATCH_NAME),
            WORKTREE_PATCH_NAME: file_identity(
                destination / WORKTREE_PATCH_NAME),
        },
        "untracked": sealed_untracked,
        "replayable": True,
    }
    manifest["capsule_sha256"] = canonical_digest(
        {key: value for key, value in manifest.items()
         if key not in ("created_at",)}
    )
    (destination / MANIFEST_NAME).write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8",
    )
    return manifest


def load(capsule: Path) -> dict[str, Any]:
    capsule = Path(capsule).expanduser().resolve()
    manifest_path = capsule / MANIFEST_NAME
    if not manifest_path.is_file():
        raise CapsuleError(f"capsule has no {MANIFEST_NAME}: {capsule}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("schema") != CAPSULE_SCHEMA \
            or manifest.get("kind") != CAPSULE_KIND:
        raise CapsuleError(f"unsupported source capsule: {capsule}")
    return manifest


def verify(capsule: Path) -> dict[str, Any]:
    """Authenticate every sealed byte without materializing anything."""
    capsule = Path(capsule).expanduser().resolve()
    manifest = load(capsule)
    recomputed = canonical_digest(
        {key: value for key, value in manifest.items()
         if key not in ("created_at", "capsule_sha256")}
    )
    if recomputed != manifest.get("capsule_sha256"):
        raise CapsuleError(f"capsule manifest identity changed: {capsule}")
    for name, expected in manifest["patches"].items():
        path = (capsule / _safe_relative(name)).resolve()
        if not path.is_relative_to(capsule) or not path.is_file() \
                or path.is_symlink() or file_identity(path) != expected:
            raise CapsuleError(f"capsule patch identity changed: {name}")
    for record in manifest["untracked"]:
        relative = _safe_relative(record["path"])
        path = (capsule / UNTRACKED_DIRECTORY / relative).resolve()
        root = (capsule / UNTRACKED_DIRECTORY).resolve()
        if not path.is_relative_to(root):
            raise CapsuleError(f"capsule escapes its own root: {record['path']}")
        if path.is_symlink() or not path.is_file():
            raise CapsuleError(f"capsule input is missing: {record['path']}")
        actual = file_identity(path)
        if actual != {key: record[key] for key in ("bytes", "sha256")}:
            raise CapsuleError(
                f"capsule input identity changed: {record['path']}"
            )
    return manifest


@contextmanager
def materialize(capsule: Path, repository: Path) -> Iterator[tuple[Path, dict]]:
    """Rebuild the sealed workspace in a disposable detached worktree."""
    capsule = Path(capsule).expanduser().resolve()
    repository = Path(repository).expanduser().resolve()
    manifest = verify(capsule)
    head = manifest["base_head"]
    known = subprocess.run(
        ["git", "-C", str(repository), "cat-file", "-e", f"{head}^{{commit}}"],
        check=False, capture_output=True,
    )
    if known.returncode != 0:
        raise CapsuleError(
            f"the capsule's base commit {head} is absent from {repository}"
        )
    with tempfile.TemporaryDirectory(prefix="bne-capsule-replay-") as temporary:
        replay = Path(temporary) / "workspace"
        _git(repository, "worktree", "add", "--detach", str(replay), head)
        try:
            worktree_patch = capsule / WORKTREE_PATCH_NAME
            staged_patch = capsule / STAGED_PATCH_NAME
            if worktree_patch.stat().st_size:
                _git(replay, "apply", "--binary", "--whitespace=nowarn",
                     str(worktree_patch))
            if staged_patch.stat().st_size:
                _git(replay, "apply", "--cached", "--binary",
                     "--whitespace=nowarn", str(staged_patch))
            for record in manifest["untracked"]:
                relative = _safe_relative(record["path"])
                target = (replay / relative).resolve()
                if not target.is_relative_to(replay.resolve()):
                    raise CapsuleError(
                        f"capsule input escapes the replay root: {record['path']}"
                    )
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(
                    capsule / UNTRACKED_DIRECTORY / relative, target,
                )
                if record.get("mode") == "100755":
                    target.chmod(0o755)
            yield replay, manifest
        finally:
            subprocess.run(
                ["git", "-C", str(repository), "worktree", "remove",
                 "--force", str(replay)],
                check=False, capture_output=True,
            )


def replay_identity(capsule: Path, repository: Path) -> dict[str, Any]:
    """Materialize a capsule and report whether it reproduces its identity."""
    with materialize(capsule, repository) as (replay, manifest):
        reproduced = bne_identity.engine_input_identity(replay)
    sealed = manifest["engine_identity"]
    return {
        "sealed": sealed,
        "reproduced": reproduced,
        "reproduced_exactly": reproduced == sealed,
        "capsule_sha256": manifest["capsule_sha256"],
    }


def legacy_state(engine_identity: object) -> dict[str, Any]:
    """Describe a receipt that predates capsules, without guessing at it."""
    return {
        "state": "legacy-no-capsule",
        "replayable": False,
        "engine_identity": bne_identity.describe_identity(engine_identity),
        "reason": (
            "this proof was recorded before source capsules existed, so the "
            "uncommitted source it ran on was never sealed and cannot be "
            "reconstructed"
        ),
        "recovery": (
            "python3 tools/bne-harness/scripts/bne_java.py gate "
            "CANDIDATE_SURVEY.json --baseline BASELINE_SURVEY.json"
        ),
        "recovery_note": (
            "re-run the gate on the current workspace and the new receipt "
            "will carry a capsule"
        ),
        "requires_input": ["CANDIDATE_SURVEY.json", "BASELINE_SURVEY.json"],
    }


def main(argv: list[str] | None = None) -> int:
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest="action", required=True)
    seal_parser = actions.add_parser("seal", help="seal the current workspace")
    seal_parser.add_argument("destination", type=Path)
    seal_parser.add_argument(
        "--repo", type=Path, default=Path(__file__).resolve().parents[3])
    verify_parser = actions.add_parser("verify", help="authenticate a capsule")
    verify_parser.add_argument("capsule", type=Path)
    replay_parser = actions.add_parser(
        "replay", help="materialize a capsule and check its engine identity")
    replay_parser.add_argument("capsule", type=Path)
    replay_parser.add_argument(
        "--repo", type=Path, default=Path(__file__).resolve().parents[3])
    arguments = parser.parse_args(argv)

    if arguments.action == "seal":
        manifest = seal(arguments.repo, arguments.destination)
        print(json.dumps({
            "capsule_sha256": manifest["capsule_sha256"],
            "base_head": manifest["base_head"],
            "untracked_inputs": len(manifest["untracked"]),
            "engine_input_sha256":
                manifest["engine_identity"]["engine_input_sha256"],
        }, indent=2, sort_keys=True))
        return 0
    if arguments.action == "verify":
        manifest = verify(arguments.capsule)
        print(f"capsule {manifest['capsule_sha256']} authenticated; "
              f"base {manifest['base_head']}, "
              f"{len(manifest['untracked'])} sealed untracked input(s)")
        return 0
    result = replay_identity(arguments.capsule, arguments.repo)
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if result["reproduced_exactly"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
