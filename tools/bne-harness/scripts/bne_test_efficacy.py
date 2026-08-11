#!/usr/bin/env python3
"""Prove that a regression test fails before a candidate and passes after it."""

from __future__ import annotations

from datetime import datetime, timezone
import fnmatch
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
from typing import Any

from bne_triage import canonical_digest, file_identity, inventory_files


SCHEMA = 3
SAFE_TEST = re.compile(r"^[A-Za-z0-9_.$#,*-]+$")
SAFE_MODULE = re.compile(r"^[A-Za-z0-9_.-]+$")
SUMMARY = re.compile(
    r"Tests run:\s*(?P<tests>\d+),\s*Failures:\s*(?P<failures>\d+),"
    r"\s*Errors:\s*(?P<errors>\d+),\s*Skipped:\s*(?P<skipped>\d+)"
)


def _write(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(
                mode="w", encoding="utf-8", prefix=path.name + ".",
                suffix=".tmp", dir=path.parent, delete=False) as handle:
            temporary = Path(handle.name)
            handle.write(value)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _write_json(path: Path, value: object) -> None:
    _write(path, json.dumps(value, indent=2, sort_keys=True) + "\n")


def workspace_identity(root: Path) -> dict[str, Any]:
    """Identify the candidate workspace by its engine inputs alone.

    The efficacy gate used to hash every untracked file, so an unrelated
    scratch note in the tree produced a fresh request hash and re-ran a
    baseline worktree build that could not have changed. It shares the one
    hermetic implementation now.
    """
    from bne_identity import engine_input_identity

    root = root.expanduser().resolve()
    return {"path": str(root), **engine_input_identity(root)}


def resolve_runtime_inputs(asset_pack: Path | None,
        source_dir: Path | None) -> dict[str, Any]:
    """Resolve and identify optional retail inputs used by a test.

    The candidate runs in the operator's checkout while the baseline runs in
    a detached temporary worktree. Relative source discovery therefore gives
    the two sides different inputs. Pass absolute Maven properties to both and
    include their identities in the content-addressed request.
    """
    resolved: dict[str, Any] = {}
    pack = asset_pack.expanduser().resolve() if asset_pack is not None else None
    if pack is not None and pack.is_file():
        resolved["asset_pack"] = {"path": str(pack), **file_identity(pack)}
    source = source_dir.expanduser().resolve() if source_dir is not None else None
    sentinel = source / "scripts" / "legacyEngine.legacy-declaration" if source is not None else None
    if sentinel is not None and sentinel.is_file():
        resolved["source_dir"] = {
            "path": str(source),
            "sentinel": file_identity(sentinel),
        }
    return resolved


def runtime_maven_properties(inputs: dict[str, Any]) -> list[str]:
    properties = []
    if "asset_pack" in inputs:
        properties.append(f"-Dchonkcraft.pack={inputs['asset_pack']['path']}")
    return properties


def collect_test_overlay(candidate_root: Path, baseline_commit: str,
        module: str, test: str) -> list[dict[str, Any]]:
    """Describe candidate test sources that must run on the baseline code."""
    scope = candidate_root / module / "src" / "test"
    patterns = []
    for selector in test.split(","):
        class_name = selector.split("#", 1)[0].rsplit(".", 1)[-1]
        class_name = class_name.split("$", 1)[0]
        if class_name:
            patterns.append(class_name)
    candidates = sorted(
        (path for path in scope.rglob("*.java")
         if any(fnmatch.fnmatchcase(path.stem, pattern)
                for pattern in patterns)),
        key=str,
    )
    if not candidates:
        raise ValueError(f"no candidate test source matches --test {test!r}")
    overlay = []
    for source in candidates:
        relative = source.relative_to(candidate_root)
        baseline_source = subprocess.run(
            ["git", "-C", str(candidate_root), "show",
             f"{baseline_commit}:{relative}"],
            check=False, capture_output=True,
        )
        if baseline_source.returncode == 0 \
                and baseline_source.stdout == source.read_bytes():
            continue
        overlay.append({
            "action": "copy", "path": str(relative),
            "identity": file_identity(source),
        })
    return overlay


def apply_test_overlay(candidate_root: Path, baseline_root: Path,
        overlay: list[dict[str, Any]]) -> None:
    for entry in overlay:
        relative = Path(entry["path"])
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError(f"unsafe test overlay path: {relative}")
        target = baseline_root / relative
        if entry["action"] == "remove":
            target.unlink(missing_ok=True)
            continue
        if entry["action"] != "copy":
            raise ValueError(f"unsupported test overlay action: {entry['action']}")
        source = candidate_root / relative
        if file_identity(source) != entry["identity"]:
            raise ValueError(f"candidate test changed during efficacy run: {source}")
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.is_symlink():
            target.unlink()
        shutil.copy2(source, target)


def parse_test_run(returncode: int, output: str, seconds: float) \
        -> dict[str, Any]:
    summaries = list(SUMMARY.finditer(output))
    counts = ({key: int(summaries[-1].group(key))
               for key in ("tests", "failures", "errors", "skipped")}
              if summaries else
              {"tests": 0, "failures": 0, "errors": 0, "skipped": 0})
    return {
        "returncode": returncode, "seconds": round(seconds, 6), **counts,
        "executed": counts["tests"] > 0,
        "passed": (returncode == 0 and counts["tests"] > 0
                   and counts["failures"] == 0 and counts["errors"] == 0
                   and counts["skipped"] == 0),
    }


def classify_efficacy(baseline: dict[str, Any],
        candidate: dict[str, Any]) -> dict[str, Any]:
    if not candidate["executed"]:
        classification = "candidate-did-not-execute"
    elif candidate["skipped"] > 0:
        classification = "candidate-skipped"
    elif not candidate["passed"]:
        classification = "candidate-failed"
    elif not baseline["executed"]:
        classification = "baseline-did-not-execute"
    elif baseline["skipped"] > 0:
        classification = "baseline-skipped"
    elif baseline["passed"]:
        classification = "false-guarantee"
    elif baseline["failures"] <= 0 or baseline["errors"] > 0:
        classification = "baseline-infrastructure-error"
    else:
        classification = "effective-regression-test"
    effective = classification == "effective-regression-test"
    return {
        "classification": classification,
        "effective": effective,
        "required": {
            "baseline_executed": True,
            "baseline_assertion_failed": True,
            "candidate_executed": True,
            "candidate_passed": True,
        },
        "observed": {
            "baseline_executed": baseline["executed"],
            "baseline_assertion_failed": (
                baseline["failures"] > 0 and baseline["errors"] == 0),
            "candidate_executed": candidate["executed"],
            "candidate_passed": candidate["passed"],
        },
    }


def _run_test(root: Path, command: list[str], timeout: float) \
        -> tuple[dict[str, Any], str]:
    started = time.monotonic()
    try:
        completed = subprocess.run(
            command, cwd=root, check=False, capture_output=True, text=True,
            timeout=timeout,
        )
        output = completed.stdout + completed.stderr
        return parse_test_run(
            completed.returncode, output, time.monotonic() - started,
        ), output
    except subprocess.TimeoutExpired as error:
        parts = []
        for part in (error.stdout, error.stderr):
            if isinstance(part, bytes):
                parts.append(part.decode("utf-8", errors="replace"))
            elif isinstance(part, str):
                parts.append(part)
        output = "".join(parts)
        result = parse_test_run(124, output, time.monotonic() - started)
        result["timed_out"] = True
        return result, output


def _summary(result: dict[str, Any]) -> str:
    return "\n".join([
        "# Regression-test efficacy", "",
        f"- Test: `{result['test']}`",
        f"- Baseline: `{result['baseline_commit'][:12]}`",
        f"- Baseline result: `{result['baseline']}`",
        f"- Candidate result: `{result['candidate']}`",
        f"- Classification: **{result['efficacy']['classification']}**",
        f"- Acceptance gate: **{'PASS' if result['efficacy']['effective'] else 'FAIL'}**",
        "",
        "A regression test is accepted only when it executes and assertion-fails "
        "on the pre-fix commit, then executes and passes on the candidate.", "",
    ])


def run_test_efficacy(candidate_root: Path, baseline_ref: str,
        test: str, artifact_root: Path, *, module: str = "engine",
        maven: str = "mvn", timeout: float = 300.0,
        asset_pack: Path | None = None,
        source_dir: Path | None = None) -> tuple[int, Path]:
    if not SAFE_TEST.fullmatch(test):
        raise ValueError("--test contains unsupported characters")
    if not SAFE_MODULE.fullmatch(module):
        raise ValueError("--module contains unsupported characters")
    if timeout <= 0:
        raise ValueError("--timeout must be positive")
    candidate_root = candidate_root.expanduser().resolve()
    baseline_commit = subprocess.run(
        ["git", "-C", str(candidate_root), "rev-parse", "--verify",
         f"{baseline_ref}^{{commit}}"],
        check=True, capture_output=True, text=True,
    ).stdout.strip()
    test_overlay = collect_test_overlay(
        candidate_root, baseline_commit, module, test,
    )
    runtime_inputs = resolve_runtime_inputs(asset_pack, source_dir)
    # Always build the selected module's reactor dependencies on both sides.
    # A desktop regression can exercise engine code; running only ``-pl
    # desktop`` silently reused whichever engine JAR happened to be installed
    # in ~/.m2. That made the candidate execute against baseline bytecode after
    # a baseline run and could certify or reject the wrong implementation.
    command = [maven, "-pl", module, "-am", f"-Dtest={test}",
               "-Dsurefire.failIfNoSpecifiedTests=false",
               *runtime_maven_properties(runtime_inputs), "test"]
    maven_path = shutil.which(maven) or maven
    maven_version = subprocess.run(
        [maven_path, "-version"], check=False, capture_output=True, text=True,
    )
    request = {
        "schema": SCHEMA,
        "implementation": file_identity(Path(__file__)),
        "candidate": workspace_identity(candidate_root),
        "baseline_commit": baseline_commit,
        "test_overlay": test_overlay,
        "runtime_inputs": runtime_inputs,
        "test": test, "module": module, "maven": maven,
        "maven_runtime": {
            "path": str(Path(maven_path).expanduser().resolve()),
            "returncode": maven_version.returncode,
            "version": (maven_version.stdout + maven_version.stderr).strip(),
        },
        "timeout": timeout,
    }
    request_sha256 = canonical_digest(request)
    artifact_root = artifact_root.expanduser().resolve()
    run_root = artifact_root / "runs" / request_sha256
    manifest_path = run_root / "manifest.json"
    if manifest_path.is_file():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if manifest.get("schema") != SCHEMA \
                or manifest.get("request_sha256") != request_sha256 \
                or canonical_digest(manifest.get("request")) != request_sha256:
            raise ValueError("cached test-efficacy request identity changed")
        for relative, expected in manifest["artifacts"].items():
            path = run_root / relative
            if not path.is_file() or file_identity(path) != expected:
                raise ValueError(f"test-efficacy artifact changed: {path}")
        _write_json(artifact_root / "latest.json", manifest["pointer"])
        return int(manifest["exit_code"]), run_root

    candidate, candidate_log = _run_test(candidate_root, command, timeout)
    with tempfile.TemporaryDirectory(prefix="bne-test-efficacy-") as temporary:
        baseline_root = Path(temporary) / "baseline"
        subprocess.run(
            ["git", "-C", str(candidate_root), "worktree", "add", "--detach",
             str(baseline_root), baseline_commit],
            check=True, capture_output=True, text=True,
        )
        try:
            apply_test_overlay(candidate_root, baseline_root, test_overlay)
            baseline, baseline_log = _run_test(baseline_root, command, timeout)
        finally:
            subprocess.run(
                ["git", "-C", str(candidate_root), "worktree", "remove",
                 "--force", str(baseline_root)],
                check=True, capture_output=True, text=True,
            )
    efficacy = classify_efficacy(baseline, candidate)
    result = {
        "schema": SCHEMA, "created_at": datetime.now(timezone.utc).isoformat(),
        "test": test, "module": module, "command": command,
        "baseline_commit": baseline_commit, "baseline": baseline,
        "candidate": candidate, "test_overlay": test_overlay,
        "runtime_inputs": runtime_inputs,
        "efficacy": efficacy,
        "source_changed": False,
    }
    run_root.mkdir(parents=True, exist_ok=True)
    result_path = run_root / "test-efficacy.json"
    summary_path = run_root / "NEXT.md"
    baseline_log_path = run_root / "baseline.log"
    candidate_log_path = run_root / "candidate.log"
    _write_json(result_path, result)
    _write(summary_path, _summary(result))
    _write(baseline_log_path, baseline_log)
    _write(candidate_log_path, candidate_log)
    exit_code = 0 if efficacy["effective"] else 1
    pointer = {
        "schema": SCHEMA, "run": str(run_root.relative_to(artifact_root)),
        "request_sha256": request_sha256, "exit_code": exit_code,
        "classification": efficacy["classification"],
        "effective": efficacy["effective"],
    }
    manifest = {
        "schema": SCHEMA, "request_sha256": request_sha256,
        "request": request, "exit_code": exit_code, "pointer": pointer,
        "artifacts": inventory_files(run_root, [
            result_path, summary_path, baseline_log_path, candidate_log_path,
        ]),
    }
    _write_json(manifest_path, manifest)
    _write_json(artifact_root / "latest.json", pointer)
    return exit_code, run_root
