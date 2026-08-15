#!/usr/bin/env python3
"""Report the cheapest currently available BNE parity diagnostic routes."""

from __future__ import annotations

from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile
from typing import Any, Callable

from bne_branch_witness import BNE_202_SHA256
from bne_triage import file_identity


SCHEMA = 1
SAFE_HOST = re.compile(r"^[A-Za-z0-9_.-]+$")
SAFE_REMOTE_PATH = re.compile(r"^[A-Za-z0-9_./~$-]+$")
BRANCH_IMAGE = "chonkcraft-bne-oracle:branch-witness-v1"
ORACLE_IMAGE = "chonkcraft-bne-oracle:bookworm-wine8"


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


def command_probe(command: list[str], timeout: float) -> dict[str, Any]:
    try:
        completed = subprocess.run(
            command, check=False, capture_output=True, text=True,
            timeout=timeout,
        )
        return {
            "available": completed.returncode == 0,
            "returncode": completed.returncode,
            "output": (completed.stdout + completed.stderr).strip()[-4000:],
        }
    except (OSError, subprocess.TimeoutExpired) as error:
        return {"available": False, "returncode": None, "output": str(error)}


def _file(path: Path, expected_sha256: str | None = None) -> dict[str, Any]:
    path = path.expanduser().resolve()
    if not path.is_file():
        return {"path": str(path), "available": False}
    identity = file_identity(path)
    return {
        "path": str(path), "available": True, **identity,
        "authenticated": (expected_sha256 is None
                          or identity["sha256"] == expected_sha256),
    }


def _directory(path: Path, required: Path | None = None) -> dict[str, Any]:
    path = path.expanduser().resolve()
    required_path = path / required if required is not None else path
    return {
        "path": str(path), "available": path.is_dir() and required_path.is_file(),
        "required": str(required_path),
    }


def build_report(*, repository: Path, asset_pack: Path,
        executable: Path, local_oracle_root: Path | None,
        remote_host: str | None, remote_root: str,
        docker: str = "docker", ssh: str = "ssh", timeout: float = 5.0,
        need: str = "any",
        probe: Callable[[list[str], float], dict[str, Any]] = command_probe) \
        -> dict[str, Any]:
    if need not in {"any", "fixture", "capture", "static"}:
        raise ValueError("unsupported doctor need")
    if remote_host is not None and not SAFE_HOST.fullmatch(remote_host):
        raise ValueError("remote host contains unsupported characters")
    if not SAFE_REMOTE_PATH.fullmatch(remote_root):
        raise ValueError("remote root contains unsupported characters")
    repository = repository.expanduser().resolve()
    local = {
        "repository": _directory(repository, Path("tools/bne-harness/PARITY.md")),
        "asset_pack": _file(asset_pack),
        "pinned_executable": _file(executable, BNE_202_SHA256),
    }
    docker_path = shutil.which(docker)
    local_docker = {
        "executable": docker_path,
        "daemon": {"available": False, "returncode": None, "output": "missing"},
        "branch_witness_image": {"available": False},
        "oracle_image": {"available": False},
    }
    if docker_path:
        local_docker["daemon"] = probe([
            docker_path, "info", "--format", "{{.ServerVersion}}",
        ], timeout)
        if local_docker["daemon"]["available"]:
            local_docker["branch_witness_image"] = probe([
                docker_path, "image", "inspect", BRANCH_IMAGE,
            ], timeout)
            local_docker["oracle_image"] = probe([
                docker_path, "image", "inspect", ORACLE_IMAGE,
            ], timeout)
    local["docker"] = local_docker
    if local_oracle_root is not None:
        root = local_oracle_root.expanduser().resolve()
        local["oracle_root"] = {
            "path": str(root), "available": root.is_dir(),
            "corpus": (root / "output/campaign-1800/corpus-index.json").is_file(),
        }
    else:
        local["oracle_root"] = {"path": None, "available": False,
                                "corpus": False}

    remote: dict[str, Any] = {
        "host": remote_host, "reachable": False, "root": False,
        "corpus_exists": False, "corpus_readable": False,
        "corpus_cases": 0, "corpus": False,
        "docker": False, "branch_witness_image": False,
        "branch_capture_command": False,
        "output": "remote probe disabled",
    }
    if remote_host is not None:
        if remote_root.startswith("~/"):
            root = '"$HOME"/' + shlex.quote(remote_root[2:])
        elif remote_root.startswith("$HOME/"):
            root = '"$HOME"/' + shlex.quote(remote_root[6:])
        else:
            root = shlex.quote(remote_root)
        remote_command = "\n".join([
            f"parity_root={root}",
            'test -d "$parity_root" && echo root=1 || echo root=0',
            'corpus_index="$parity_root/output/campaign-1800/corpus-index.json"',
            'test -f "$corpus_index" '
            '&& echo corpus_exists=1 || echo corpus_exists=0',
            # Existence is not usability. A root-owned mode-600 corpus once
            # made doctor advertise a fixture route that failed as soon as an
            # operator tried to read its first byte. Parse the index and open
            # every declared fixture as the SSH user before publishing it.
            'python3 - "$parity_root" "$corpus_index" <<\'PY\'\n'
            'import json, os, sys\n'
            'try:\n'
            '    root, index = sys.argv[1:]\n'
            '    with open(index, "r", encoding="utf-8") as source:\n'
            '        data = json.load(source)\n'
            '    cases = data.get("cases") if data.get("schema") == 1 else None\n'
            '    if not isinstance(cases, list) or len(cases) != 52:\n'
            '        raise ValueError("campaign corpus must contain 52 cases")\n'
            '    base = os.path.join(root, "output", "campaign-1800")\n'
            '    for case in cases:\n'
            '        relative = case["fixture"]["path"]\n'
            '        with open(os.path.join(base, relative), "rb") as fixture:\n'
            '            fixture.read(1)\n'
            '    print("corpus_readable=1")\n'
            '    print(f"corpus_cases={len(cases)}")\n'
            'except Exception as error:\n'
            '    print("corpus_readable=0")\n'
            '    print(f"corpus_error={type(error).__name__}")\n'
            'PY',
            'docker info >/dev/null 2>&1 && echo docker=1 || echo docker=0',
            f'docker image inspect {shlex.quote(BRANCH_IMAGE)} >/dev/null 2>&1 '
            '&& echo branch_image=1 || echo branch_image=0',
            'capture_script="$parity_root/harness-branch-witness/scripts/'
            'bne_headless.py"',
            'test -f "$capture_script" '
            '&& python3 "$capture_script" --help 2>&1 '
            '| grep -q "branch-capture" '
            '&& echo branch_capture=1 || echo branch_capture=0',
        ])
        ssh_path = shutil.which(ssh) or ssh
        response = probe([
            ssh_path, "-o", "BatchMode=yes", "-o",
            f"ConnectTimeout={max(1, int(timeout))}", remote_host,
            remote_command,
        ], timeout + 2)
        output = response.get("output", "")
        case_match = re.search(r"(?:^|\n)corpus_cases=(\d+)(?:\n|$)", output)
        corpus_exists = "corpus_exists=1" in output
        corpus_readable = "corpus_readable=1" in output
        remote.update({
            "reachable": response.get("returncode") == 0,
            "root": "root=1" in output,
            "corpus_exists": corpus_exists,
            "corpus_readable": corpus_readable,
            "corpus_cases": int(case_match.group(1)) if case_match else 0,
            # Keep the public field's old name, but strengthen its contract:
            # corpus now means that the complete index and fixtures can
            # actually be consumed by this SSH identity.
            "corpus": corpus_exists and corpus_readable,
            "docker": "docker=1" in output,
            "branch_witness_image": "branch_image=1" in output,
            "branch_capture_command": "branch_capture=1" in output,
            "output": output,
        })

    routes = []
    if remote["reachable"] and remote["root"] and remote["corpus"]:
        routes.append({
            "id": "remote-fixture-oracle", "ready": True, "cost": 1.0,
            "capability": "fixture",
            "description": f"Use the complete sealed corpus on {remote_host}.",
        })
    if remote["reachable"] and remote["root"] \
            and remote["branch_witness_image"] \
            and remote["branch_capture_command"]:
        routes.append({
            "id": "remote-branch-witness", "ready": True, "cost": 2.0,
            "capability": "capture",
            "description": f"Run native writer/branch capture on {remote_host}.",
        })
    if local["oracle_root"]["available"] \
            and local_docker["branch_witness_image"]["available"]:
        routes.append({
            "id": "local-branch-witness", "ready": True, "cost": 1.8,
            "capability": "capture",
            "description": "Run the native diagnostic oracle locally.",
        })
    if local["pinned_executable"].get("authenticated"):
        routes.append({
            "id": "local-static-analysis", "ready": True, "cost": 0.8,
            "capability": "static",
            "description": "Use the authenticated BNE executable for static analysis.",
        })
    routes.sort(key=lambda item: (item["cost"], item["id"]))
    eligible = routes if need == "any" else [
        route for route in routes if route["capability"] == need
    ]
    prerequisites = local["repository"]["available"] \
        and local["asset_pack"]["available"]
    return {
        "schema": SCHEMA, "created_at": datetime.now(timezone.utc).isoformat(),
        "need": need, "local": local, "remote": remote, "routes": routes,
        "recommended": eligible[0] if eligible else None,
        "ready": prerequisites and bool(eligible),
        "proof": {"read_only": True, "source_changed": False},
    }


def _summary(report: dict[str, Any]) -> str:
    lines = [
        "# Parity-lab capability doctor", "",
        f"- Overall: **{'READY' if report['ready'] else 'INCOMPLETE'}**",
        f"- Requested capability: `{report['need']}`",
        f"- Repository: `{report['local']['repository']['available']}`",
        f"- Asset pack: `{report['local']['asset_pack']['available']}`",
        "- Runtime model: `native Java`",
        f"- Pinned executable: "
        f"`{report['local']['pinned_executable'].get('authenticated', False)}`",
        f"- Remote `{report['remote']['host']}` reachable: "
        f"`{report['remote']['reachable']}`",
        f"- Remote corpus readable (52 fixtures): "
        f"`{report['remote']['corpus']}`",
        f"- Remote Branch Witness command runnable: "
        f"`{report['remote']['branch_capture_command']}`",
        "", "## Available routes", "",
    ]
    if not report["routes"]:
        lines.append("- No complete diagnostic route is currently available.")
    for route in report["routes"]:
        lines.append(f"- **{route['id']}** (cost {route['cost']}): {route['description']}")
    if report["recommended"]:
        lines.extend([
            "", f"Recommended first route: **{report['recommended']['id']}**", "",
        ])
    return "\n".join(lines)


def run_doctor(output_root: Path, **kwargs: Any) -> tuple[int, Path]:
    report = build_report(**kwargs)
    output_root = output_root.expanduser().resolve()
    output_root.mkdir(parents=True, exist_ok=True)
    _write_json(output_root / "latest.json", report)
    _write(output_root / "NEXT.md", _summary(report))
    return (0 if report["ready"] else 1), output_root
