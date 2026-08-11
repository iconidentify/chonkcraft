#!/usr/bin/env python3
"""Plan or execute isolated decision-miner captures on a remote oracle."""

from __future__ import annotations

from datetime import datetime, timezone
import json
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import subprocess
import tempfile
from typing import Any, Iterable

from bne_branch_witness import load_verified_capture as load_writer_capture
from bne_decision_capture import load_verified_capture as load_decision_capture
from bne_triage import canonical_digest, file_identity, inventory_files


SCHEMA = 1
ROOT = Path(__file__).resolve().parents[3]
DEFAULT_REMOTE_HOST = os.environ.get("CHONKCRAFT_ORACLE_HOST", "oracle-host")
SAFE_HOST = re.compile(r"^[A-Za-z0-9_.-]+$")
SAFE_NAME = re.compile(r"^[A-Za-z0-9_.-]+$")
DEPLOY_SCRIPTS = tuple(Path(__file__).with_name(name) for name in (
    "bne_headless.py", "bne_decision_capture.py", "bne_branch_capture.py",
    "bne_branch_witness.py", "bne_triage.py", "bne_function_lab.py",
))


def _json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"expected JSON object: {path}")
    return value


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


def _remote_root(value: str) -> PurePosixPath:
    path = PurePosixPath(value)
    if path.is_absolute() or not path.parts or any(
            part in {"", ".", ".."} for part in path.parts):
        raise ValueError("remote oracle root must be a safe home-relative path")
    return path


def _run(command: list[str], *, timeout: float) -> subprocess.CompletedProcess[str]:
    completed = subprocess.run(
        command, check=False, capture_output=True, text=True, timeout=timeout,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            f"remote decision command failed ({completed.returncode}): "
            + (completed.stdout + "\n" + completed.stderr).strip()[-4000:]
        )
    return completed


def _ssh_command(ssh: str, host: str, arguments: list[str]) -> list[str]:
    return [ssh, host, shlex.join(arguments)]


def deployment_identity() -> dict[str, Any]:
    return {path.name: file_identity(path) for path in DEPLOY_SCRIPTS}


def build_remote_plan(plan_path: Path, *, host: str = DEFAULT_REMOTE_HOST,
        remote_root: str = ".local/share/chonkcraft-bne-oracle",
        source_harness: str = "harness-branch-witness",
        bootstrap: bool = False, phases: Iterable[str] = ()) -> dict[str, Any]:
    if not SAFE_HOST.fullmatch(host):
        raise ValueError("remote host contains unsupported characters")
    if not SAFE_NAME.fullmatch(source_harness):
        raise ValueError("source harness name is unsafe")
    root = _remote_root(remote_root)
    plan_path = plan_path.expanduser().resolve()
    plan = _json(plan_path)
    plan_sha = canonical_digest(plan)
    selected = list(phases)
    commands = []
    deploy = deployment_identity()
    deploy_sha = canonical_digest(deploy)
    harness_name = f"harness-decision-miner-{deploy_sha[:12]}"
    plan_remote = root / "plans" / "decision-miner" / f"{plan_sha}.json"
    deployment = {
        "source_harness": source_harness, "harness_name": harness_name,
        "sha256": deploy_sha, "scripts": deploy,
    }
    if bootstrap:
        case = str(plan["case"])
        cycle = int(plan["divergence_cycle"])
        field = str(plan["focus"]["fields"][0])
        if not SAFE_NAME.fullmatch(case) or not SAFE_NAME.fullmatch(field):
            raise ValueError("bootstrap case or field is unsafe")
        selected = ["accepted"]
        output = (PurePosixPath("decision-miner") / "bootstrap"
                  / plan_sha[:16] / deploy_sha[:12])
        command = [
            "python3", str(root / harness_name / "scripts/bne_headless.py"),
            "branch-capture", "--oracle-root", str(root),
            "--harness-name", harness_name, "--plan", str(plan_remote),
            "--case-id", case, "--field", field, "--output", str(output),
            "--scenario", str(plan["scenario"]), "--seed", str(plan["seed"]),
            "--cycles", str(cycle), "--host-gdb",
            "--name", f"bne-dm-{plan_sha[:8]}-bootstrap",
        ]
        prefix = f"{case}.{field}"
        remote_files = {
            "capture": root / "output" / output / f"{prefix}.branch-capture.json",
            "manifest": root / "output" / output
                / f"{prefix}.branch-capture.manifest.json",
            "history": root / "output" / output / f"{prefix}.gdb-history.txt",
        }
        commands.append({"phase": "accepted", "argv": command,
                         "remote_files": {key: str(value)
                                          for key, value in remote_files.items()}})
    else:
        if plan.get("schema") != 1 or not isinstance(plan.get("decision"), dict):
            raise ValueError("decision remote requires decision-plan.json")
        case, field = str(plan["case"]), str(plan["decision"]["field"])
        if not SAFE_NAME.fullmatch(case) or not SAFE_NAME.fullmatch(field):
            raise ValueError("decision case or field is unsafe")
        available = list(plan["captures"])
        selected = selected or available
        if any(phase not in available for phase in selected):
            raise ValueError("requested phase is absent from decision plan")
        output = (PurePosixPath("decision-miner") / plan["decision_id"]
                  / plan_sha[:12] / deploy_sha[:12])
        for phase in selected:
            cycle = int(plan["captures"][phase]["cycle"])
            command = [
                "python3", str(root / harness_name / "scripts/bne_headless.py"),
                "decision-capture", "--oracle-root", str(root),
                "--harness-name", harness_name, "--plan", str(plan_remote),
                "--case-id", case, "--phase", phase,
                "--output", str(output / phase),
                "--scenario", str(plan["scenario"]),
                "--seed", str(plan["seed"]), "--cycles", str(cycle),
                "--host-gdb", "--name", f"bne-dm-{plan_sha[:8]}-{phase}",
            ]
            prefix = f"{case}.{field}.{phase}"
            phase_root = root / "output" / output / phase
            remote_files = {
                "capture": phase_root / f"{prefix}.decision-capture.json",
                "manifest": phase_root / f"{prefix}.decision-capture.manifest.json",
                "history": phase_root / f"{prefix}.gdb-history.txt",
            }
            commands.append({"phase": phase, "argv": command,
                             "remote_files": {key: str(value)
                                              for key, value in remote_files.items()}})
    return {
        "schema": SCHEMA, "mode": "bootstrap" if bootstrap else "decision",
        "host": host, "remote_root": str(root),
        "plan": {"path": str(plan_path), **file_identity(plan_path),
                 "sha256": plan_sha, "remote_path": str(plan_remote)},
        "deployment": deployment, "phases": selected, "commands": commands,
    }


def _render(remote: dict[str, Any]) -> str:
    lines = [
        "# Remote decision capture", "",
        f"- Mode: `{remote['mode']}`",
        f"- Host: `{remote['host']}`",
        f"- Plan: `{remote['plan']['sha256']}`",
        f"- Phases: `{remote['phases']}`", "",
    ]
    if remote.get("deployment"):
        lines.extend([
            "The decision runner is deployed to a content-addressed copy of "
            "the Branch Witness harness. Existing oracle harnesses are not modified.", "",
        ])
    lines.append("## Capture commands")
    lines.append("")
    for item in remote["commands"]:
        lines.extend([f"### {item['phase']}", "", "```sh",
                      shlex.join(item["argv"]), "```", ""])
    lines.append("Captures are networkless and source-diagnostic only.")
    lines.append("")
    return "\n".join(lines)


def execute_remote(remote: dict[str, Any], output_root: Path, *,
        ssh: str = "ssh", scp: str = "scp", timeout: float = 300.0) \
        -> list[Path]:
    host = remote["host"]
    root = PurePosixPath(remote["remote_root"])
    plan_remote = PurePosixPath(remote["plan"]["remote_path"])
    mkdir_paths = [str(plan_remote.parent)]
    deployment = remote.get("deployment")
    if deployment:
        harness = root / deployment["harness_name"]
        source = root / deployment["source_harness"]
        mkdir_paths.append(str(harness.parent))
        _run(_ssh_command(ssh, host, [
            "sh", "-c",
            f"test -d {shlex.quote(str(source))} && "
            f"(test -d {shlex.quote(str(harness))} || "
            f"cp -a {shlex.quote(str(source))} {shlex.quote(str(harness))})",
        ]), timeout=timeout)
        for script in DEPLOY_SCRIPTS:
            _run([scp, str(script),
                  f"{host}:{harness / 'scripts' / script.name}"], timeout=timeout)
    _run(_ssh_command(ssh, host, ["mkdir", "-p", *mkdir_paths]), timeout=timeout)
    _run([scp, remote["plan"]["path"], f"{host}:{plan_remote}"], timeout=timeout)
    downloaded = []
    for item in remote["commands"]:
        _run(_ssh_command(ssh, host, item["argv"]), timeout=timeout)
        phase_root = output_root / item["phase"]
        phase_root.mkdir(parents=True, exist_ok=True)
        for kind, remote_path in item["remote_files"].items():
            destination = phase_root / PurePosixPath(remote_path).name
            _run([scp, f"{host}:{remote_path}", str(destination)], timeout=timeout)
            downloaded.append(destination)
    return downloaded


def run_remote(plan_path: Path, artifact_root: Path, *, execute: bool = False,
        host: str = DEFAULT_REMOTE_HOST,
        remote_root: str = ".local/share/chonkcraft-bne-oracle",
        source_harness: str = "harness-branch-witness",
        bootstrap: bool = False, phases: Iterable[str] = (),
        ssh: str = "ssh", scp: str = "scp", timeout: float = 300.0) \
        -> tuple[int, Path]:
    remote = build_remote_plan(
        plan_path, host=host, remote_root=remote_root,
        source_harness=source_harness, bootstrap=bootstrap, phases=phases,
    )
    request = {
        "schema": SCHEMA, "remote": remote,
        "implementation": {path.name: file_identity(path)
                           for path in (Path(__file__), *DEPLOY_SCRIPTS)},
        "execute": execute, "ssh": ssh, "scp": scp, "timeout": timeout,
    }
    request_sha = canonical_digest(request)
    artifact_root = artifact_root.expanduser().resolve()
    run_root = artifact_root / "remote" / request_sha
    manifest_path = run_root / "manifest.json"
    if manifest_path.is_file():
        manifest = _json(manifest_path)
        if manifest.get("request_sha256") != request_sha \
                or canonical_digest(manifest.get("request")) != request_sha:
            raise ValueError("cached remote decision request changed")
        for relative, identity in manifest["artifacts"].items():
            if file_identity(run_root / relative) != identity:
                raise ValueError(f"remote decision artifact changed: {relative}")
        _write_json(artifact_root / "latest-remote.json", manifest["pointer"])
        return manifest["exit_code"], run_root
    run_root.mkdir(parents=True, exist_ok=True)
    remote_path = run_root / "remote-plan.json"
    next_path = run_root / "REMOTE.txt"
    _write_json(remote_path, remote)
    _write(next_path, _render(remote))
    artifacts = [remote_path, next_path]
    downloaded = []
    if execute:
        downloaded = execute_remote(
            remote, run_root / "captures", ssh=ssh, scp=scp, timeout=timeout,
        )
        artifacts.extend(downloaded)
        plan = _json(Path(remote["plan"]["path"]))
        capture_files = [path for path in downloaded
                         if path.name.endswith("capture.json")]
        for capture in capture_files:
            if bootstrap:
                load_writer_capture(capture, plan=plan)
            else:
                load_decision_capture(capture, plan)
    pointer = {
        "schema": SCHEMA, "request_sha256": request_sha,
        "run": str(run_root.relative_to(artifact_root)),
        "mode": remote["mode"], "executed": execute,
        "phases": remote["phases"], "downloaded": len(downloaded),
    }
    manifest = {
        "schema": SCHEMA, "created_at": datetime.now(timezone.utc).isoformat(),
        "request_sha256": request_sha, "request": request,
        "exit_code": 0, "pointer": pointer,
        "artifacts": inventory_files(run_root, artifacts),
    }
    _write_json(manifest_path, manifest)
    _write_json(artifact_root / "latest-remote.json", pointer)
    return 0, run_root
