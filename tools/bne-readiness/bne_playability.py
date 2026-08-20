#!/usr/bin/env python3
"""Run the automated player/referee commands declared by the BNE ledger."""

from __future__ import annotations

from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import time
from typing import Any


DEFAULT_BNE_PACK = (
    Path.home()
    / ".chonkcraft/packs/warcraft-ii-battle-net-edition-usa-full.chonkpack"
)

MAVEN_TEST_SUMMARY = re.compile(
    r"Tests run:\s*(\d+),\s*Failures:\s*(\d+),\s*Errors:\s*(\d+),\s*Skipped:\s*(\d+)"
)


def select(data: dict[str, Any], requested: list[str] | None) -> list[dict[str, Any]]:
    systems = {system["id"]: system for system in data["systems"]}
    if not requested:
        return list(data["systems"])
    unknown = set(requested) - systems.keys()
    if unknown:
        raise ValueError(f"unknown playability lanes: {', '.join(sorted(unknown))}")
    return [systems[system_id] for system_id in requested]


def _git(repository: Path, *arguments: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(repository), *arguments], check=True,
        capture_output=True, text=True)
    return result.stdout.strip()


def runtime_environment(repository: Path) -> dict[str, str]:
    """Resolve the authenticated native-pack input used by the shipped game."""
    environment = dict(os.environ)
    if not environment.get("CHONKCRAFT_ASSET_PACK") and DEFAULT_BNE_PACK.is_file():
        environment["CHONKCRAFT_ASSET_PACK"] = str(DEFAULT_BNE_PACK)
    return environment


def _configured_inputs(repository: Path) -> dict[str, bool]:
    environment = runtime_environment(repository)
    install = environment.get("WC2_INSTALL_DIR")
    pack = environment.get("CHONKCRAFT_ASSET_PACK")
    vectors = environment.get("OPUS_TESTVECTORS")
    return {
        "wc2_install": bool(install and Path(install).expanduser().is_dir()),
        "asset_pack": bool(pack and Path(pack).expanduser().is_file()),
        "opus_vectors": bool(vectors and Path(vectors).expanduser().exists()),
    }


def run(system: dict[str, Any], repository: Path, output: Path,
        timeout_seconds: int, *, force_red: bool = False) -> dict[str, Any]:
    """Run one lane, keeping its full Maven output outside the JSON receipt."""
    started = datetime.now(timezone.utc)
    result: dict[str, Any] = {
        "id": system["id"],
        "name": system["name"],
        "grade_before": system["grade"],
        "driver": system["gate"]["driver"],
        "success": system["gate"]["success"],
        "command": system["gate"]["command"],
        "started_at": started.isoformat(),
    }
    if system["grade"] == "red" and not force_red:
        result.update({
            "status": "blocked",
            "blockers": system["blockers"],
            "duration_seconds": 0.0,
        })
        return result

    environment = runtime_environment(repository)
    configured = _configured_inputs(repository)
    required = system["gate"].get("required_inputs", [])
    missing = [name for name in required if not configured.get(name, False)]
    if missing:
        result.update({
            "status": "blocked",
            "blockers": ["missing required input: " + name for name in missing],
            "duration_seconds": 0.0,
        })
        return result

    output.mkdir(parents=True, exist_ok=True)
    log = output / f"{system['id']}.log"
    command = [str(repository / system["gate"]["command"][0]),
               *system["gate"]["command"][1:]]
    # JAVA_TOOL_OPTIONS above reaches both Maven and the Surefire JVM, including
    # when a lane driver invokes run-tests.sh indirectly.
    result["executed_command"] = command
    before = time.monotonic()
    with log.open("w", encoding="utf-8") as stream:
        process = subprocess.Popen(
            command, cwd=repository, stdout=stream, stderr=subprocess.STDOUT,
            text=True, env=environment, start_new_session=(os.name != "nt"))
        try:
            returncode = process.wait(timeout=timeout_seconds)
            status = "passed" if returncode == 0 else "failed"
        except subprocess.TimeoutExpired:
            if os.name != "nt":
                os.killpg(process.pid, signal.SIGTERM)
            else:
                process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                if os.name != "nt":
                    os.killpg(process.pid, signal.SIGKILL)
                else:
                    process.kill()
                process.wait()
            returncode = None
            status = "timed-out"

    failing_summaries: list[dict[str, int]] = []
    skipped_summaries: list[dict[str, int]] = []
    if log.is_file():
        contents = log.read_text(encoding="utf-8", errors="replace")
        for match in MAVEN_TEST_SUMMARY.finditer(contents):
            tests, failures, errors, skipped = (int(value) for value in match.groups())
            summary = {
                "tests": tests,
                "failures": failures,
                "errors": errors,
                "skipped": skipped,
            }
            if failures or errors:
                failing_summaries.append(summary)
            if skipped:
                skipped_summaries.append(summary)
    if status == "passed" and (failing_summaries or skipped_summaries):
        status = "failed"

    result.update({
        "status": status,
        "returncode": returncode,
        "duration_seconds": round(time.monotonic() - before, 3),
        "log": str(log.relative_to(repository)),
    })
    if failing_summaries:
        result["failing_test_summaries"] = failing_summaries
    if skipped_summaries:
        result["skipped_test_summaries"] = skipped_summaries
    if failing_summaries and skipped_summaries:
        result["failure_reason"] = (
            "test gate reported failures, errors, or skipped checks")
    elif failing_summaries:
        result["failure_reason"] = "test gate reported failures or errors"
    elif skipped_summaries:
        result["failure_reason"] = "test gate reported skipped checks"
    return result


def receipt(repository: Path, results: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "schema": 1,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "engine": {
            "commit": _git(repository, "rev-parse", "HEAD"),
            "branch": _git(repository, "branch", "--show-current"),
            "dirty": bool(_git(repository, "status", "--porcelain")),
        },
        "inputs": _configured_inputs(repository),
        "counts": {
            status: sum(result["status"] == status for result in results)
            for status in ("passed", "failed", "timed-out", "blocked")
        },
        "certified": all(result["status"] == "passed" for result in results),
        "lanes": results,
    }


def write_receipt(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")
