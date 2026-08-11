#!/usr/bin/env python3
"""One hermetic identity for the inputs that can change a Java parity result."""

from __future__ import annotations

import hashlib
import os
import subprocess
from pathlib import Path, PurePosixPath
from typing import Any, Iterable


IDENTITY_SCHEMA = 2
INPUT_POLICY = "engine-input-v1"

#: Everything the engine trace and its comparison are actually built from.
#: ``mvn -pl engine -am`` builds ``engine`` plus its reactor dependencies
#: ``runtime``, ``retired-interpreter``, ``data`` and (through ``data``) ``assetpack``; the
#: harness scripts decide what is traced and how it is compared. Nothing else
#: in the tree can move a number in a survey.
ENGINE_INPUT_PATHSPECS: tuple[str, ...] = (
    "pom.xml",
    "assetpack",
    "runtime",
    "retired-interpreter",
    "data",
    "engine",
    "tools/bne-harness/CMakeLists.txt",
    "tools/bne-harness/cmake",
    "tools/bne-harness/java",
    "tools/bne-harness/micro-oracle-requirements.txt",
    "tools/bne-harness/parity-lab-policy.json",
    "tools/bne-harness/scripts",
    "tools/bne-harness/semantic-bridge-atlas.json",
    "tools/bne-harness/src",
)

#: Build output and editor droppings that live inside an included prefix.
EXCLUDED_SEGMENTS = frozenset({
    "target", "__pycache__", ".pytest_cache", ".mypy_cache", "node_modules",
})

#: Prose never reaches the simulation, wherever it is filed.
EXCLUDED_SUFFIXES = (".md",)


def is_engine_input(relative: str) -> bool:
    """Answer whether a repository-relative path can change a survey number."""
    if not relative or relative.startswith("/") or ".." in relative.split("/"):
        return False
    parts = PurePosixPath(relative).parts
    if any(part in EXCLUDED_SEGMENTS for part in parts):
        return False
    if relative.endswith(EXCLUDED_SUFFIXES):
        return False
    return any(relative == spec or relative.startswith(f"{spec}/")
               for spec in ENGINE_INPUT_PATHSPECS)


def _git(root: Path, *arguments: str) -> bytes:
    return subprocess.run(
        ["git", "-C", str(root), *arguments],
        check=True, capture_output=True,
    ).stdout


def _git_text(root: Path, *arguments: str) -> str:
    return _git(root, *arguments).decode("utf-8", "surrogateescape").strip()


def _split_z(raw: bytes) -> list[bytes]:
    return [chunk for chunk in raw.split(b"\0") if chunk]


def _index_entries(root: Path) -> dict[str, str]:
    """Map each indexed path to ``mode blob``, which is the staged content."""
    entries: dict[str, str] = {}
    for chunk in _split_z(_git(root, "ls-files", "-s", "-z")):
        meta, _, name = chunk.partition(b"\t")
        mode, blob, _stage = meta.split()
        entries[os.fsdecode(name)] = f"{mode.decode()} {blob.decode()}"
    return entries


def _head_entries(root: Path) -> dict[str, str]:
    entries: dict[str, str] = {}
    for chunk in _split_z(_git(root, "ls-tree", "-r", "-z", "HEAD")):
        meta, _, name = chunk.partition(b"\t")
        mode, _kind, blob = meta.split()
        entries[os.fsdecode(name)] = f"{mode.decode()} {blob.decode()}"
    return entries


def _status_paths(root: Path) -> tuple[set[str], set[str]]:
    """Return every path git reports as changed, and the untracked subset."""
    changed: set[str] = set()
    untracked: set[str] = set()
    fields = _git(
        root, "status", "--porcelain=v1", "-z", "--untracked-files=all",
    ).split(b"\0")
    index = 0
    while index < len(fields):
        entry = fields[index]
        index += 1
        if not entry:
            continue
        code, name = entry[:2].decode(), os.fsdecode(entry[3:])
        if code[0] in {"R", "C"}:
            # A rename reports the destination first and the source next.
            if index < len(fields):
                changed.add(os.fsdecode(fields[index]))
                index += 1
        changed.add(name)
        if code == "??":
            untracked.add(name)
    return changed, untracked


def _worktree_sha256(path: Path) -> str | None:
    if path.is_symlink() or not path.is_file():
        return None
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _file_bytes(path: Path) -> int:
    try:
        return path.stat().st_size if path.is_file() and not path.is_symlink() else 0
    except OSError:
        return 0


def scan(root: Path) -> dict[str, Any]:
    """Fingerprint the declared engine inputs and count everything else."""
    root = Path(root).expanduser().resolve()
    head = _git_text(root, "rev-parse", "HEAD")
    index = _index_entries(root)
    committed = _head_entries(root)
    changed, untracked = _status_paths(root)

    relevant = {name for name in index if is_engine_input(name)}
    relevant.update(name for name in untracked if is_engine_input(name))

    records: list[tuple[str, str, str]] = []
    dirty = False
    for name in sorted(relevant):
        staged = index.get(name, "-")
        if name in changed or name not in index:
            # Only a path git already reports as touched needs to be read;
            # an unmodified tracked file is its index blob by definition.
            content = _worktree_sha256(root / name) or "-"
            working = f"sha256 {content}" if content != "-" else "absent"
        else:
            working = f"index {staged}"
        if staged != committed.get(name, "-") or name in changed:
            dirty = True
        records.append((name, staged, working))

    digest = hashlib.sha256()
    digest.update(f"policy\0{INPUT_POLICY}\0schema\0{IDENTITY_SCHEMA}\0".encode())
    digest.update(b"head\0" + head.encode("ascii") + b"\0")
    for name, staged, working in records:
        digest.update(b"path\0" + os.fsencode(name) + b"\0")
        digest.update(b"index\0" + staged.encode("ascii") + b"\0")
        digest.update(b"worktree\0" + working.encode("ascii") + b"\0")

    excluded = sorted(name for name in changed if not is_engine_input(name))
    noise_bytes = sum(_file_bytes(root / name) for name in excluded)
    return {
        "identity": {
            "schema": IDENTITY_SCHEMA,
            "policy": INPUT_POLICY,
            "head": head,
            "dirty": dirty,
            "engine_input_sha256": digest.hexdigest(),
            "input_count": len(records),
        },
        "noise": {
            "policy": INPUT_POLICY,
            "excluded_path_count": len(excluded),
            "excluded_bytes": noise_bytes,
            "excluded_sample": excluded[:20],
        },
        "inputs": records,
    }


def engine_input_identity(root: Path) -> dict[str, Any]:
    """Return only the fields a cache key may depend on."""
    return scan(root)["identity"]


def workspace_noise(root: Path) -> dict[str, Any]:
    """Return the excluded churn, which is reported and never hashed."""
    return scan(root)["noise"]


def is_legacy_identity(identity: object) -> bool:
    """Recognize a pre-hermetic engine record so it is never read as current."""
    if not isinstance(identity, dict):
        return True
    return identity.get("schema") != IDENTITY_SCHEMA \
        or identity.get("policy") != INPUT_POLICY \
        or "engine_input_sha256" not in identity


def describe_identity(identity: object) -> str:
    if is_legacy_identity(identity):
        legacy = identity if isinstance(identity, dict) else {}
        workspace = str(legacy.get("workspace_sha256", "unknown"))
        return (f"legacy workspace identity head={legacy.get('head', 'unknown')} "
                f"workspace={workspace[:12]}")
    assert isinstance(identity, dict)
    state = "dirty" if identity.get("dirty") else "clean"
    return (f"{INPUT_POLICY} head={identity['head'][:12]} {state} "
            f"engine={str(identity['engine_input_sha256'])[:12]} "
            f"inputs={identity.get('input_count')}")


def relevant_paths(root: Path) -> list[str]:
    """List the declared engine inputs, for capsule sealing and diagnostics."""
    return [name for name, _staged, _working in scan(root)["inputs"]]


def main(argv: Iterable[str] | None = None) -> int:
    import argparse
    import json

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[3])
    parser.add_argument("--list", action="store_true",
                        help="print the declared engine inputs instead of JSON")
    arguments = parser.parse_args(list(argv) if argv is not None else None)
    result = scan(arguments.repo)
    if arguments.list:
        for name, staged, working in result["inputs"]:
            print(f"{name}\t{staged}\t{working}")
        return 0
    print(json.dumps(
        {"identity": result["identity"], "noise": result["noise"]},
        indent=2, sort_keys=True,
    ))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
