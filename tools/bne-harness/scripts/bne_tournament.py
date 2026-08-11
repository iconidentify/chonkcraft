#!/usr/bin/env python3
"""Isolated candidate tournaments; evaluation can select but never merge."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
from typing import Any, Callable

from bne_triage import file_identity


TOURNAMENT_SCHEMA = 1


def result_score(result: dict[str, Any]) -> tuple[Any, ...]:
    eligible = bool(result.get("focused_passed")) \
        and bool(result.get("gate_passed")) \
        and not result.get("regressions")
    return (
        0 if eligible else 1,
        -int(result.get("target_clean_through") or -1),
        int(result.get("changed_lines") or 1 << 20),
        float(result.get("wall_seconds") or float("inf")),
        str(result.get("candidate", "")),
    )


def run_tournament(candidates: list[dict[str, Any]],
        evaluator: Callable[[dict[str, Any]], dict[str, Any]], *,
        jobs: int = 2) -> dict[str, Any]:
    if jobs <= 0:
        raise ValueError("jobs must be positive")
    identifiers = [candidate.get("id") for candidate in candidates]
    if any(not value for value in identifiers) or len(set(identifiers)) != len(identifiers):
        raise ValueError("tournament candidates require unique ids")
    results = []
    with ThreadPoolExecutor(max_workers=jobs) as executor:
        futures = {executor.submit(evaluator, candidate): candidate
                   for candidate in candidates}
        for future in as_completed(futures):
            candidate = futures[future]
            try:
                result = future.result()
                result = {"candidate": candidate["id"], **result}
            except Exception as error:  # Retain a failed lane instead of racing onward.
                result = {
                    "candidate": candidate["id"], "status": "error",
                    "focused_passed": False, "gate_passed": False,
                    "error": f"{type(error).__name__}: {error}",
                }
            results.append(result)
    ranked = sorted(results, key=result_score)
    winner = next((item for item in ranked
                   if item.get("focused_passed") and item.get("gate_passed")
                   and not item.get("regressions")), None)
    return {
        "schema": TOURNAMENT_SCHEMA,
        "candidate_count": len(candidates),
        "winner": winner,
        "ranked": ranked,
        "policy": {
            "select_only": True,
            "automatic_merge": False,
            "requires_full_gate": True,
            "requires_focused_test": True,
        },
    }


def _last_json(text: str) -> dict[str, Any]:
    for line in reversed(text.splitlines()):
        line = line.strip()
        if line.startswith("{"):
            return json.loads(line)
    raise ValueError("evaluator emitted no JSON result")


class GitPatchEvaluator:
    """Evaluate one patch in a disposable detached worktree."""

    def __init__(self, repository: Path, revision: str,
            command: list[str], *, timeout: float = 900.0):
        self.repository = repository.resolve()
        self.revision = revision
        self.command = list(command)
        self.timeout = timeout
        if not self.command or not any("{worktree}" in item for item in self.command):
            raise ValueError("evaluator command requires {worktree}")

    def __call__(self, candidate: dict[str, Any]) -> dict[str, Any]:
        patch = Path(candidate["patch"]).resolve()
        patch_identity = file_identity(patch)
        with tempfile.TemporaryDirectory(prefix="bne-tournament-") as parent:
            worktree = Path(parent) / "worktree"
            add = subprocess.run(
                ["git", "-C", str(self.repository), "worktree", "add",
                 "--detach", str(worktree), self.revision],
                capture_output=True, text=True, check=False,
            )
            if add.returncode != 0:
                raise RuntimeError(add.stderr.strip() or add.stdout.strip())
            try:
                applied = subprocess.run(
                    ["git", "-C", str(worktree), "apply", "--check", str(patch)],
                    capture_output=True, text=True, check=False,
                )
                if applied.returncode != 0:
                    return {
                        "status": "patch-rejected", "focused_passed": False,
                        "gate_passed": False, "patch": patch_identity,
                        "error": applied.stderr.strip(),
                    }
                subprocess.run(
                    ["git", "-C", str(worktree), "apply", str(patch)],
                    capture_output=True, text=True, check=True,
                )
                command = [item.replace("{worktree}", str(worktree))
                           for item in self.command]
                completed = subprocess.run(
                    command, capture_output=True, text=True, check=False,
                    timeout=self.timeout,
                )
                result = _last_json(completed.stdout)
                result.setdefault("status", "evaluated" if completed.returncode == 0
                                  else "evaluator-failed")
                result["patch"] = patch_identity
                result["evaluator_returncode"] = completed.returncode
                return result
            finally:
                subprocess.run(
                    ["git", "-C", str(self.repository), "worktree", "remove",
                     "--force", str(worktree)],
                    capture_output=True, text=True, check=False,
                )


def plan_from_rules(rules: list[dict[str, Any]], *,
        case_id: str, cycle: int) -> dict[str, Any]:
    candidates = []
    for rule in rules:
        encoded = json.dumps(rule, sort_keys=True, separators=(",", ":"))
        digest = hashlib.sha256(encoded.encode("utf-8")).hexdigest()[:12]
        candidates.append({
            "id": "rule-" + digest,
            "rule": rule,
            "status": "needs-source-adapter",
        })
    return {
        "schema": TOURNAMENT_SCHEMA,
        "case": case_id, "cycle": cycle,
        "candidates": candidates,
        "execution": [
            "materialize each rule as a minimal patch",
            "run focused tests and exact-case replay in isolated worktrees",
            "run the full baseline gate only for surviving candidates",
            "select but never merge the strongest fully gated result",
        ],
    }
