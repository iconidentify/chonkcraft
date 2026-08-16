#!/usr/bin/env python3
"""Build and run the BNE oracle in a networkless Wine/Xvfb container."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

from bne_branch_capture import (
    gdb_commands, predicate_probe, register_probe, seal_capture,
)
from bne_decision_capture import (
    gdb_commands as decision_gdb_commands,
    seal_capture as seal_decision_capture,
)
from bne_snapshot_capture import (
    gdb_commands as snapshot_gdb_commands,
    load_specification as load_snapshot_specification,
    seal_capture as seal_snapshot_capture,
)


IMAGE = "chonkcraft-bne-oracle:bookworm-wine8"
SAFE_NAME = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9_.-]*$")


def require_directory(root: Path, name: str) -> Path:
    path = (root / name).resolve()
    if not path.is_dir():
        raise ValueError(f"missing {name} directory below oracle root: {path}")
    return path


def build(args: argparse.Namespace) -> int:
    docker_dir = Path(__file__).resolve().parent.parent / "docker"
    command = [
        args.docker,
        "build",
        "--pull",
        "--tag",
        args.image,
        str(docker_dir),
    ]
    return subprocess.run(command, check=False).returncode


def image_id(args: argparse.Namespace) -> str:
    result = subprocess.run(
        [args.docker, "image", "inspect", "--format={{.Id}}", args.image],
        check=True,
        capture_output=True,
        text=True,
    )
    value = result.stdout.strip()
    if not value.startswith("sha256:"):
        raise ValueError(f"Docker returned an invalid image identity: {value!r}")
    return value


def container_command(args: argparse.Namespace, root: Path) -> list[str]:
    command = [
        args.docker,
        "run",
    ]
    if not getattr(args, "keep_container", False):
        command.append("--rm")
    command.extend([
        "--init",
        "--network=none",
        "--security-opt=no-new-privileges",
        "--env",
        "CHONK_BNE_EXECUTION_MODE=headless-xvfb",
        "--env",
        f"CHONK_BNE_CONTAINER_IMAGE={args.image}",
        "--env",
        f"CHONK_BNE_CONTAINER_IMAGE_ID={image_id(args)}",
        "--env",
        "CHONK_BNE_DISPLAY_DEPTH=8",
        "--env",
        "CHONK_BNE_NETWORK_DISABLED=1",
        "--env",
        "CHONK_BNE_AUDIO_DEVICE=none",
        "--volume",
        f"{root}:/oracle",
    ])
    if getattr(args, "diagnostic_ptrace", False):
        command.extend([
            "--pid=host",
            "--cap-add=SYS_PTRACE",
            "--cap-add=PERFMON",
            "--security-opt=seccomp=unconfined",
        ])
    if getattr(args, "detach", False):
        if not SAFE_NAME.fullmatch(args.name):
            raise ValueError("--name must be filesystem-safe")
        command.extend(["--detach", "--name", args.name])
    if getattr(args, "trace_internal_orders", False):
        command.extend(["--env", "CHONK_BNE_TRACE_INTERNAL_ORDERS=1"])
    if getattr(args, "trace_ai_build_state", False):
        command.extend(["--env", "CHONK_BNE_TRACE_AI_BUILD_STATE=1"])
    if getattr(args, "trace_unit", None) is not None:
        command.extend(["--env", f"CHONK_BNE_TRACE_UNIT={args.trace_unit}"])
    command.append(args.image)
    return command


def output_ownership_command(args: argparse.Namespace, root: Path,
                             container_output: Path) -> list[str]:
    """Return captured evidence to the unprivileged oracle operator.

    The game must run as root in the existing Wine prefix, but a fixture is
    sealed mode 0600.  Leaving that inode owned by container root makes a
    successful native capture unusable over SSH and used to fool doctor into
    reporting READY.  Normalize only the already-validated output subtree.
    """
    return [
        args.docker, "run", "--rm", "--network=none",
        "--security-opt=no-new-privileges",
        "--volume", f"{root}:/oracle",
        # Do not depend on the oracle image's normal entrypoint forwarding
        # arbitrary commands.  Ownership recovery is a maintenance operation,
        # not a Wine capture, and must keep working if that entrypoint changes.
        "--entrypoint", "sh",
        args.image,
        "-c",
        'chown -R "$1:$2" "$3" && chmod -R u+rwX,go-rwx "$3"',
        "normalize-output", str(os.getuid()), str(os.getgid()),
        str(container_output),
    ]


def normalize_output_ownership(args: argparse.Namespace, root: Path,
                               container_output: Path) -> int:
    return subprocess.run(
        output_ownership_command(args, root, container_output),
        check=False).returncode


def verify_output_ownership(host_output: Path, *, uid: int | None = None,
                            gid: int | None = None) -> None:
    """Fail closed if a capture was not returned to its operator.

    A successful chown command is not evidence that the bind-mounted inode is
    usable.  Check the actual host tree before a receipt is sealed so a remote
    capture cannot leave a root-owned trace that only *looks* successful.
    """
    uid = os.getuid() if uid is None else uid
    gid = os.getgid() if gid is None else gid
    paths = [host_output]
    if host_output.is_dir():
        paths.extend(host_output.rglob("*"))
    wrong = []
    links = []
    for path in paths:
        # Do not follow an unexpected link outside the already validated
        # output subtree.
        if path.is_symlink():
            links.append(str(path))
            if len(links) == 5:
                break
            continue
        status = path.lstat()
        if status.st_uid != uid or status.st_gid != gid:
            wrong.append(f"{path} ({status.st_uid}:{status.st_gid})")
            if len(wrong) == 5:
                break
    if wrong:
        raise RuntimeError(
            "capture output is not owned by the oracle operator: "
            + ", ".join(wrong))
    if links:
        raise RuntimeError(
            "capture output contains symbolic links: " + ", ".join(links))


def run(args: argparse.Namespace) -> int:
    root = args.oracle_root.expanduser().resolve()
    if not SAFE_NAME.fullmatch(args.harness_name):
        raise ValueError("--harness-name must be filesystem-safe")
    harness = require_directory(root, args.harness_name)
    require_directory(root, "game")
    require_directory(root, "cd")
    output = require_directory(root, "output")
    if not (root / "cd" / "INSTALL.EXE").is_file():
        raise ValueError(f"missing retail INSTALL.EXE below {root / 'cd'}")

    relative_output = args.output.relative_to(Path("/")) \
        if args.output.is_absolute() else args.output
    host_output = (output / relative_output).resolve()
    if not host_output.is_relative_to(output):
        raise ValueError("--output must remain below the oracle output directory")
    host_output.mkdir(parents=True, exist_ok=True)

    container_output = Path("/oracle/output") / relative_output
    trace = container_output / f"{args.case_id}.trace.txt"
    state = container_output / f"{args.case_id}.state.bin"
    manifest = container_output / f"{args.case_id}.manifest.json"
    fixture = container_output / f"{args.case_id}.bnefx"
    source_manifest = Path("/oracle/source-manifest.json")
    if not SAFE_NAME.fullmatch(args.case_id):
        raise ValueError("--case-id must be filesystem-safe")
    command = [
        *container_command(args, root),
        "python3",
        str(Path("/oracle") / harness.name / "scripts/bne_oracle.py"),
        "run",
        "--wine",
        "/usr/bin/wine",
        "--game-dir",
        "/oracle/game",
        "--prefix",
        "/oracle/prefix",
        "--trace",
        str(trace),
        "--state",
        str(state),
        "--manifest",
        str(manifest),
        "--fixture",
        str(fixture),
        "--source-manifest",
        str(source_manifest),
        "--scenario",
        args.scenario,
        "--seed",
        str(args.seed),
        "--cycles",
        str(args.cycles),
    ]
    if getattr(args, "commands", None) is not None:
        host_commands = args.commands.expanduser().resolve()
        if not host_commands.is_relative_to(root):
            raise ValueError("--commands must remain below the oracle root")
        command.extend([
            "--commands",
            str(Path("/oracle") / host_commands.relative_to(root)),
        ])
    if getattr(args, "branch_pause_cycle", None) is not None:
        ready = container_output / f"{args.case_id}.branch-ready"
        resume = container_output / f"{args.case_id}.branch-resume"
        command.extend([
            "--branch-pause-cycle", str(args.branch_pause_cycle),
            "--branch-ready", str(ready),
            "--branch-resume", str(resume),
        ])
    result = subprocess.run(command, check=False)
    normalized = normalize_output_ownership(args, root, container_output)
    if normalized != 0:
        return normalized
    return result.returncode


def _wait_for_file(path: Path, timeout: float) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if path.is_file() and path.stat().st_size > 0:
            return
        time.sleep(0.05)
    raise RuntimeError(f"timed out waiting for branch pause marker: {path}")


def _container_game_pid(args: argparse.Namespace, windows_pid: int) -> int:
    """Resolve Wine's Windows PID to the Linux process GDB must attach to."""
    probe = """
import pathlib
matches = []
for item in pathlib.Path('/proc').iterdir():
    if not item.name.isdigit():
        continue
    try:
        maps = (item / 'maps').read_text(errors='ignore').lower()
        command = (item / 'cmdline').read_bytes().replace(b'\\0', b' ').lower()
    except (OSError, PermissionError):
        continue
    if b'warcraft ii bne.exe' in command and b'bne-inject.exe' not in command:
        matches.append(int(item.name))
    elif 'warcraft ii bne.exe' in maps and '00400000-' in maps:
        matches.append(int(item.name))
print(' '.join(str(value) for value in sorted(set(matches))))
"""
    result = subprocess.run([
        args.docker, "exec", args.name, "python3", "-c", probe,
    ], check=True, capture_output=True, text=True, timeout=10)
    candidates = [int(value) for value in result.stdout.split()
                  if value.isdigit()]
    if windows_pid in candidates:
        return windows_pid
    if len(candidates) != 1:
        raise RuntimeError(
            f"could not uniquely resolve Wine game PID; candidates={candidates}"
        )
    return candidates[0]


def branch_capture(args: argparse.Namespace) -> int:
    """Run a dedicated offline oracle and attach GDB at one pre-tick pause."""
    root = args.oracle_root.expanduser().resolve()
    if not SAFE_NAME.fullmatch(args.harness_name):
        raise ValueError("--harness-name must be filesystem-safe")
    if not SAFE_NAME.fullmatch(args.case_id):
        raise ValueError("--case-id must be filesystem-safe")
    if not SAFE_NAME.fullmatch(args.name):
        raise ValueError("--name must be filesystem-safe")
    harness = require_directory(root, args.harness_name)
    game = require_directory(root, "game")
    require_directory(root, "cd")
    output_root = require_directory(root, "output")
    plan_path = args.plan.expanduser().resolve()
    if not plan_path.is_file():
        raise ValueError(f"missing branch-witness plan: {plan_path}")
    plan = json.loads(plan_path.read_text(encoding="utf-8"))
    if plan.get("case") != args.case_id:
        raise ValueError("--case-id differs from the branch-witness plan")
    cycle = int(plan["divergence_cycle"])
    if args.cycles < cycle:
        raise ValueError("--cycles ends before the witness divergence")
    planned_fields = set(plan["focus"]["fields"]) \
        | set(plan["focus"].get("causal_fields", []))
    if args.field not in planned_fields:
        raise ValueError("--field is not present in the witness plan")
    if args.scenario != plan.get("scenario") or args.seed != plan.get("seed"):
        raise ValueError("scenario or seed differs from the witness plan")
    predicate_values = (
        args.predicate_branch, args.predicate_compare,
        args.predicate_lhs_register, args.predicate_rhs_register,
        args.predicate_condition,
    )
    if any(value is not None for value in (
            *predicate_values, args.predicate_focus_register)) \
            and not all(value is not None for value in predicate_values):
        raise ValueError("predicate probe requires all five predicate arguments")
    predicate = predicate_probe(
        *predicate_values, args.predicate_focus_register,
    ) \
        if all(value is not None for value in predicate_values) else None

    relative_output = args.output.relative_to(Path("/")) \
        if args.output.is_absolute() else args.output
    host_output = (output_root / relative_output).resolve()
    if not host_output.is_relative_to(output_root):
        raise ValueError("--output must remain below the oracle output directory")
    host_output.mkdir(parents=True, exist_ok=True)
    container_output = Path("/oracle/output") / relative_output
    prefix = f"{args.case_id}.{args.field}"
    host_ready = host_output / f"{prefix}.branch-ready"
    host_resume = host_output / f"{prefix}.branch-resume"
    host_history = host_output / f"{prefix}.gdb-history.txt"
    host_script = host_output / f"{prefix}.gdb"
    host_capture = host_output / f"{prefix}.branch-capture.json"
    for path in (host_ready, host_resume, host_history, host_script,
                 host_capture,
                 host_capture.with_name(host_capture.stem + ".manifest.json")):
        if path.exists():
            raise ValueError(f"branch capture output already exists: {path}")
    container_ready = container_output / host_ready.name
    container_resume = container_output / host_resume.name
    container_history = container_output / host_history.name
    container_script = container_output / host_script.name
    registers_at = register_probe(args.registers_at or [])
    host_script.write_text(gdb_commands(
        plan, field=args.field, history_log=container_history,
        resume_marker=container_resume, predicate=predicate,
        registers_at=registers_at,
    ), encoding="utf-8")

    args.detach = True
    args.keep_container = True
    args.diagnostic_ptrace = True
    start = [*container_command(args, root),
        "python3", str(Path("/oracle") / harness.name / "scripts/bne_oracle.py"),
        "run", "--wine", "/usr/bin/wine", "--game-dir", "/oracle/game",
        "--prefix", "/oracle/prefix",
        "--trace", str(container_output / f"{args.case_id}.trace.txt"),
        "--state", str(container_output / f"{args.case_id}.state.bin"),
        "--manifest", str(container_output / f"{args.case_id}.manifest.json"),
        "--fixture", str(container_output / f"{args.case_id}.bnefx"),
        "--source-manifest", "/oracle/source-manifest.json",
        "--scenario", args.scenario, "--seed", str(args.seed),
        "--cycles", str(args.cycles),
        "--branch-pause-cycle", str(cycle),
        "--branch-ready", str(container_ready),
        "--branch-resume", str(container_resume),
    ]
    started = subprocess.run(
        start, check=True, capture_output=True, text=True,
    )
    container_id = started.stdout.strip()
    if not container_id:
        raise RuntimeError("Docker did not return a branch-capture container id")
    try:
        _wait_for_file(host_ready, args.timeout)
        ready = dict(part.split("=", 1)
                     for part in host_ready.read_text().strip().split()
                     if "=" in part)
        windows_pid = int(ready.get("pid", "0"))
        if windows_pid <= 0 or int(ready.get("cycle", "0")) != cycle:
            raise RuntimeError(f"invalid branch pause marker: {ready}")
        pid = _container_game_pid(args, windows_pid)
        if args.host_gdb:
            version_command = [args.sudo, "-n", args.gdb, "--version"]
        else:
            version_command = [
                args.docker, "exec", args.name, "gdb", "--version",
            ]
        version_result = subprocess.run(
            version_command, check=False, capture_output=True, text=True,
            timeout=10,
        )
        version = version_result.stdout.splitlines()
        if args.host_gdb:
            # Regenerate with host-visible shared-volume paths.  Root GDB is
            # narrowly scoped to this disposable networkless oracle process;
            # it works around perf_event restrictions on sibling attachment.
            host_script.write_text(gdb_commands(
                plan, field=args.field, history_log=host_history,
                resume_marker=host_resume, predicate=predicate,
                registers_at=registers_at,
            ), encoding="utf-8")
            gdb_command = [
                args.sudo, "-n", args.gdb, "--batch", "--quiet",
                "--pid", str(pid), "--command", str(host_script),
            ]
        else:
            gdb_command = [
                args.docker, "exec", args.name, "gdb", "--batch", "--quiet",
                "--pid", str(pid), "--command", str(container_script),
            ]
        gdb = subprocess.run(
            gdb_command, check=False, capture_output=True, text=True,
            timeout=args.timeout,
        )
        if gdb.returncode != 0:
            raise RuntimeError(
                "GDB branch recorder failed: "
                + (gdb.stdout + "\n" + gdb.stderr).strip()[-4000:]
            )
        waited = subprocess.run(
            [args.docker, "wait", args.name], check=True,
            capture_output=True, text=True, timeout=args.timeout,
        )
        if waited.stdout.strip() != "0":
            logs = subprocess.run(
                [args.docker, "logs", args.name], check=False,
                capture_output=True, text=True,
            )
            raise RuntimeError("branch oracle failed: " + logs.stderr[-2000:])
        seal_capture(
            plan, host_history, host_capture, field=args.field,
            executable=game / "Warcraft II BNE.exe",
            tracer=harness / "build/bne-trace.dll",
            oracle_run_manifest=host_output / f"{args.case_id}.manifest.json",
            gdb_version=version[0] if version else "unknown",
            network_disabled=True,
            predicate=predicate,
        )
        print(f"sealed branch capture: {host_capture}")
        return 0
    finally:
        subprocess.run(
            [args.docker, "rm", "--force", args.name],
            check=False, capture_output=True, text=True,
        )
        if normalize_output_ownership(args, root, container_output) != 0:
            raise RuntimeError("could not return branch evidence to oracle operator")


def decision_capture(args: argparse.Namespace) -> int:
    """Capture one rejected/accepted focus-scoped decision activation."""
    root = args.oracle_root.expanduser().resolve()
    for label, value in (("--harness-name", args.harness_name),
                         ("--case-id", args.case_id), ("--name", args.name)):
        if not SAFE_NAME.fullmatch(value):
            raise ValueError(f"{label} must be filesystem-safe")
    harness = require_directory(root, args.harness_name)
    game = require_directory(root, "game")
    require_directory(root, "cd")
    output_root = require_directory(root, "output")
    plan_path = args.plan.expanduser().resolve()
    if not plan_path.is_file():
        raise ValueError(f"missing decision-miner plan: {plan_path}")
    plan = json.loads(plan_path.read_text(encoding="utf-8"))
    if plan.get("case") != args.case_id:
        raise ValueError("--case-id differs from the decision plan")
    spec = plan.get("captures", {}).get(args.phase)
    if not isinstance(spec, dict):
        raise ValueError(f"decision plan has no phase {args.phase}")
    cycle = int(spec["cycle"])
    if args.cycles < cycle:
        raise ValueError("--cycles ends before the decision capture")
    if args.scenario != plan.get("scenario") or args.seed != plan.get("seed"):
        raise ValueError("scenario or seed differs from the decision plan")

    relative_output = args.output.relative_to(Path("/")) \
        if args.output.is_absolute() else args.output
    host_output = (output_root / relative_output).resolve()
    if not host_output.is_relative_to(output_root):
        raise ValueError("--output must remain below the oracle output directory")
    host_output.mkdir(parents=True, exist_ok=True)
    container_output = Path("/oracle/output") / relative_output
    field = plan["decision"]["field"]
    prefix = f"{args.case_id}.{field}.{args.phase}"
    host_ready = host_output / f"{prefix}.decision-ready"
    host_resume = host_output / f"{prefix}.decision-resume"
    host_history = host_output / f"{prefix}.gdb-history.txt"
    host_script = host_output / f"{prefix}.gdb"
    host_capture = host_output / f"{prefix}.decision-capture.json"
    for path in (host_ready, host_resume, host_history, host_script,
                 host_capture,
                 host_capture.with_name(host_capture.stem + ".manifest.json")):
        if path.exists():
            raise ValueError(f"decision capture output already exists: {path}")
    container_ready = container_output / host_ready.name
    container_resume = container_output / host_resume.name
    container_history = container_output / host_history.name
    container_script = container_output / host_script.name
    host_script.write_text(decision_gdb_commands(
        plan, args.phase, history_log=container_history,
        resume_marker=container_resume,
    ), encoding="utf-8")

    args.detach = True
    args.keep_container = True
    args.diagnostic_ptrace = True
    start = [*container_command(args, root),
        "python3", str(Path("/oracle") / harness.name / "scripts/bne_oracle.py"),
        "run", "--wine", "/usr/bin/wine", "--game-dir", "/oracle/game",
        "--prefix", "/oracle/prefix",
        "--trace", str(container_output / f"{args.case_id}.trace.txt"),
        "--state", str(container_output / f"{args.case_id}.state.bin"),
        "--manifest", str(container_output / f"{args.case_id}.manifest.json"),
        "--fixture", str(container_output / f"{args.case_id}.bnefx"),
        "--source-manifest", "/oracle/source-manifest.json",
        "--scenario", args.scenario, "--seed", str(args.seed),
        "--cycles", str(args.cycles),
        "--branch-pause-cycle", str(cycle),
        "--branch-ready", str(container_ready),
        "--branch-resume", str(container_resume),
    ]
    started = subprocess.run(start, check=True, capture_output=True, text=True)
    container_id = started.stdout.strip()
    if not container_id:
        raise RuntimeError("Docker did not return a decision-capture container id")
    try:
        _wait_for_file(host_ready, args.timeout)
        ready = dict(part.split("=", 1)
                     for part in host_ready.read_text().strip().split()
                     if "=" in part)
        windows_pid = int(ready.get("pid", "0"))
        if windows_pid <= 0 or int(ready.get("cycle", "0")) != cycle:
            raise RuntimeError(f"invalid decision pause marker: {ready}")
        pid = _container_game_pid(args, windows_pid)
        version_command = ([args.sudo, "-n", args.gdb, "--version"]
                           if args.host_gdb else
                           [args.docker, "exec", args.name, "gdb", "--version"])
        version_result = subprocess.run(
            version_command, check=False, capture_output=True, text=True,
            timeout=10,
        )
        version = version_result.stdout.splitlines()
        if args.host_gdb:
            host_script.write_text(decision_gdb_commands(
                plan, args.phase, history_log=host_history,
                resume_marker=host_resume,
            ), encoding="utf-8")
            gdb_command = [
                args.sudo, "-n", args.gdb, "--batch", "--quiet",
                "--pid", str(pid), "--command", str(host_script),
            ]
        else:
            gdb_command = [
                args.docker, "exec", args.name, "gdb", "--batch", "--quiet",
                "--pid", str(pid), "--command", str(container_script),
            ]
        gdb = subprocess.run(
            gdb_command, check=False, capture_output=True, text=True,
            timeout=args.timeout,
        )
        if gdb.returncode != 0:
            raise RuntimeError(
                "GDB decision recorder failed: "
                + (gdb.stdout + "\n" + gdb.stderr).strip()[-4000:]
            )
        waited = subprocess.run(
            [args.docker, "wait", args.name], check=True,
            capture_output=True, text=True, timeout=args.timeout,
        )
        if waited.stdout.strip() != "0":
            logs = subprocess.run(
                [args.docker, "logs", args.name], check=False,
                capture_output=True, text=True,
            )
            raise RuntimeError("decision oracle failed: " + logs.stderr[-2000:])
        seal_decision_capture(
            plan, args.phase, host_history, host_capture,
            executable=game / "Warcraft II BNE.exe",
            tracer=harness / "build/bne-trace.dll",
            oracle_run_manifest=host_output / f"{args.case_id}.manifest.json",
            gdb_version=version[0] if version else "unknown",
            network_disabled=True,
        )
        print(f"sealed decision capture: {host_capture}")
        return 0
    finally:
        subprocess.run(
            [args.docker, "rm", "--force", args.name],
            check=False, capture_output=True, text=True,
        )
        if normalize_output_ownership(args, root, container_output) != 0:
            raise RuntimeError("could not return decision evidence to oracle operator")


def snapshot_capture(args: argparse.Namespace) -> int:
    """Save the machine at one native decision, so it can be replayed offline.

    The same shape as the branch recorder above, and for the same reasons: a
    disposable networkless oracle of its own, paused before the tick the
    decision is in, attached to once, and sealed against the pinned executable.
    What differs is what is taken away -- the registers, the stack, the code,
    the data and the outcome, which is everything the micro-oracle needs to run
    the invocation again without the game.
    """
    root = args.oracle_root.expanduser().resolve()
    for label, value in (("--harness-name", args.harness_name),
                         ("--case-id", args.case_id), ("--name", args.name)):
        if not SAFE_NAME.fullmatch(value):
            raise ValueError(f"{label} must be filesystem-safe")
    harness = require_directory(root, args.harness_name)
    game = require_directory(root, "game")
    require_directory(root, "cd")
    output_root = require_directory(root, "output")
    specification_path = args.specification.expanduser().resolve()
    if not specification_path.is_file():
        raise ValueError(f"missing capture specification: {specification_path}")
    specification = load_snapshot_specification(
        json.loads(specification_path.read_text(encoding="utf-8")))
    if specification["case"] != args.case_id:
        raise ValueError("--case-id differs from the capture specification")
    cycle = specification["cycle"]
    if not isinstance(cycle, int):
        raise ValueError("a capture specification needs the cycle to pause at")
    if args.cycles < cycle:
        raise ValueError("--cycles ends before the decision to capture")

    relative_output = args.output.relative_to(Path("/")) \
        if args.output.is_absolute() else args.output
    host_output = (output_root / relative_output).resolve()
    if not host_output.is_relative_to(output_root):
        raise ValueError("--output must remain below the oracle output directory")
    host_output.mkdir(parents=True, exist_ok=True)
    container_output = Path("/oracle/output") / relative_output
    prefix = f"{args.case_id}.snapshot"
    host_ready = host_output / f"{prefix}.branch-ready"
    host_resume = host_output / f"{prefix}.branch-resume"
    host_history = host_output / f"{prefix}.capture-log.txt"
    host_script = host_output / f"{prefix}.gdb"
    sealed_root = host_output / "snapshot"
    for path in (host_ready, host_resume, host_history, host_script,
                 sealed_root):
        if path.exists():
            raise ValueError(f"snapshot capture output already exists: {path}")
    container_history = container_output / host_history.name
    container_resume = container_output / host_resume.name
    container_script = container_output / host_script.name
    host_script.write_text(snapshot_gdb_commands(
        specification, history_log=container_history,
        resume_marker=container_resume), encoding="utf-8")

    args.detach = True
    args.keep_container = True
    args.diagnostic_ptrace = True
    start = [*container_command(args, root),
        "python3", str(Path("/oracle") / harness.name / "scripts/bne_oracle.py"),
        "run", "--wine", "/usr/bin/wine", "--game-dir", "/oracle/game",
        "--prefix", "/oracle/prefix",
        "--trace", str(container_output / f"{args.case_id}.trace.txt"),
        "--state", str(container_output / f"{args.case_id}.state.bin"),
        "--manifest", str(container_output / f"{args.case_id}.manifest.json"),
        "--fixture", str(container_output / f"{args.case_id}.bnefx"),
        "--source-manifest", "/oracle/source-manifest.json",
        "--scenario", args.scenario, "--seed", str(args.seed),
        "--cycles", str(args.cycles),
        "--branch-pause-cycle", str(cycle),
        "--branch-ready", str(container_output / host_ready.name),
        "--branch-resume", str(container_resume),
    ]
    started = subprocess.run(start, check=True, capture_output=True, text=True)
    container_id = started.stdout.strip()
    if not container_id:
        raise RuntimeError("Docker did not return a snapshot-capture container id")
    primary_failure: BaseException | None = None
    try:
        _wait_for_file(host_ready, args.timeout)
        ready = dict(part.split("=", 1)
                     for part in host_ready.read_text().strip().split()
                     if "=" in part)
        windows_pid = int(ready.get("pid", "0"))
        if windows_pid <= 0 or int(ready.get("cycle", "0")) != cycle:
            raise RuntimeError(f"invalid branch pause marker: {ready}")
        pid = _container_game_pid(args, windows_pid)
        if args.host_gdb:
            version_command = [args.sudo, "-n", args.gdb, "--version"]
        else:
            version_command = [args.docker, "exec", args.name, "gdb", "--version"]
        version_result = subprocess.run(
            version_command, check=False, capture_output=True, text=True,
            timeout=10,
        )
        version = version_result.stdout.splitlines()
        if args.host_gdb:
            # Regenerated with host-visible shared-volume paths, as the branch
            # recorder does, because root GDB runs outside the container.
            host_script.write_text(snapshot_gdb_commands(
                specification, history_log=host_history,
                resume_marker=host_resume), encoding="utf-8")
            gdb_command = [
                args.sudo, "-n", args.gdb, "--batch", "--quiet",
                "--pid", str(pid), "--command", str(host_script),
            ]
        else:
            gdb_command = [
                args.docker, "exec", args.name, "gdb", "--batch", "--quiet",
                "--pid", str(pid), "--command", str(container_script),
            ]
        gdb = subprocess.run(
            gdb_command, check=False, capture_output=True, text=True,
            timeout=args.timeout,
        )
        if gdb.returncode != 0:
            raise RuntimeError(
                "GDB snapshot capture failed: "
                + (gdb.stdout + "\n" + gdb.stderr).strip()[-4000:])
        waited = subprocess.run(
            [args.docker, "wait", args.name], check=True,
            capture_output=True, text=True, timeout=args.timeout,
        )
        if waited.stdout.strip() != "0":
            logs = subprocess.run(
                [args.docker, "logs", args.name], check=False,
                capture_output=True, text=True,
            )
            raise RuntimeError("snapshot oracle failed: " + logs.stderr[-2000:])
        # GDB and Wine run privileged on the remote worker.  Return every
        # inode before the unprivileged process reads and seals the capture;
        # doing this only in ``finally`` made failed captures recoverable but
        # let successful sealing depend on world-readable root files.
        if normalize_output_ownership(args, root, container_output) != 0:
            raise RuntimeError("could not return snapshot evidence to oracle operator")
        verify_output_ownership(host_output)
        snapshot_path, manifest_path = seal_snapshot_capture(
            specification, host_history, sealed_root,
            executable=game / "Warcraft II BNE.exe",
            oracle_run_manifest=host_output / f"{args.case_id}.manifest.json",
            gdb_version=version[0] if version else "unknown",
            network_disabled=True,
        )
        print(f"sealed native snapshot: {snapshot_path}")
        print(f"snapshot manifest: {manifest_path}")
        return 0
    except BaseException as failure:
        primary_failure = failure
        raise
    finally:
        cleanup_failures: list[BaseException] = []
        try:
            subprocess.run(
                [args.docker, "rm", "--force", args.name],
                check=False, capture_output=True, text=True,
            )
        except BaseException as failure:
            cleanup_failures.append(failure)
        try:
            if normalize_output_ownership(args, root, container_output) != 0:
                cleanup_failures.append(RuntimeError(
                    "could not return snapshot evidence to oracle operator"))
        except BaseException as failure:
            cleanup_failures.append(failure)
        if cleanup_failures:
            if primary_failure is None:
                first, *rest = cleanup_failures
                for failure in rest:
                    first.add_note(f"additional cleanup failure: {failure}")
                raise first
            for failure in cleanup_failures:
                primary_failure.add_note(f"cleanup failure: {failure}")


def corpus(args: argparse.Namespace) -> int:
    root = args.oracle_root.expanduser().resolve()
    if not SAFE_NAME.fullmatch(args.harness_name):
        raise ValueError("--harness-name must be filesystem-safe")
    harness = require_directory(root, args.harness_name)
    require_directory(root, "game")
    require_directory(root, "cd")
    output = require_directory(root, "output")
    plans = require_directory(root, "plans")
    plan = (plans / args.plan).resolve()
    if not plan.is_relative_to(plans) or not plan.is_file():
        raise ValueError(f"--plan must name a file below {plans}")
    relative_output = args.output.relative_to(Path("/")) \
        if args.output.is_absolute() else args.output
    host_output = (output / relative_output).resolve()
    if not host_output.is_relative_to(output):
        raise ValueError("--output must remain below the oracle output directory")
    host_output.mkdir(parents=True, exist_ok=True)

    command = [
        *container_command(args, root),
        "python3",
        str(Path("/oracle") / harness.name / "scripts/bne_corpus.py"),
        "run",
        "--wine",
        "/usr/bin/wine",
        "--game-dir",
        "/oracle/game",
        "--prefix",
        "/oracle/prefix",
        "--plan",
        str(Path("/oracle/plans") / args.plan),
        "--output-dir",
        str(Path("/oracle/output") / relative_output),
        "--source-manifest",
        "/oracle/source-manifest.json",
    ]
    result = subprocess.run(command, check=False)
    container_output = Path("/oracle/output") / relative_output
    normalized = normalize_output_ownership(args, root, container_output)
    return normalized if normalized != 0 else result.returncode


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--docker", default="docker")
    result.add_argument("--image", default=IMAGE)
    subcommands = result.add_subparsers(dest="command", required=True)

    build_parser = subcommands.add_parser("build")
    build_parser.set_defaults(func=build)

    run_parser = subcommands.add_parser("run")
    run_parser.add_argument("--oracle-root", required=True, type=Path)
    run_parser.add_argument("--case-id", required=True)
    run_parser.add_argument("--output", type=Path, default=Path("smoke"))
    run_parser.add_argument("--scenario", required=True)
    run_parser.add_argument("--cycles", required=True, type=int)
    run_parser.add_argument("--seed", default=1, type=int)
    run_parser.add_argument(
        "--harness-name", default="harness",
        help="isolated harness directory below the oracle root",
    )
    run_parser.add_argument(
        "--commands", type=Path,
        help="cycle-sorted command script that must stay below --oracle-root",
    )
    run_parser.add_argument("--trace-internal-orders", action="store_true")
    run_parser.add_argument("--trace-ai-build-state", action="store_true",
                            help="emit all active computer AIPlayerState rows "
                                 "at every committed gameplay cycle")
    run_parser.add_argument("--trace-unit", type=int)
    run_parser.add_argument("--branch-pause-cycle", type=int)
    run_parser.set_defaults(func=run)

    capture_parser = subcommands.add_parser(
        "branch-capture",
        help="record one offline native writer and its GDB BTS branch history",
    )
    capture_parser.add_argument("--oracle-root", required=True, type=Path)
    capture_parser.add_argument("--plan", required=True, type=Path)
    capture_parser.add_argument("--case-id", required=True)
    capture_parser.add_argument("--field", required=True)
    capture_parser.add_argument("--output", type=Path, default=Path("branch-witness"))
    capture_parser.add_argument("--scenario", required=True)
    capture_parser.add_argument("--cycles", required=True, type=int)
    capture_parser.add_argument("--seed", default=1, type=int)
    capture_parser.add_argument("--timeout", default=120.0, type=float)
    capture_parser.add_argument(
        "--host-gdb", action="store_true",
        help="attach host GDB through passwordless sudo for perf BTS access",
    )
    capture_parser.add_argument("--gdb", default="/usr/bin/gdb")
    capture_parser.add_argument("--sudo", default="/usr/bin/sudo")
    capture_parser.add_argument("--predicate-branch", type=lambda value: int(value, 0))
    capture_parser.add_argument("--predicate-compare", type=lambda value: int(value, 0))
    capture_parser.add_argument("--predicate-lhs-register")
    capture_parser.add_argument("--predicate-rhs-register")
    capture_parser.add_argument("--predicate-condition")
    capture_parser.add_argument(
        "--predicate-focus-register",
        help="unit-pointer register; retain only observations for the watched unit",
    )
    capture_parser.add_argument(
        "--registers-at", action="append", type=lambda value: int(value, 0),
        help="print every register each time this address is reached "
             "(repeatable, at most eight)")
    capture_parser.add_argument("--name", default="chonkcraft-bne-branch-witness")
    capture_parser.add_argument(
        "--harness-name", default="harness",
        help="isolated harness directory below the oracle root",
    )
    capture_parser.set_defaults(func=branch_capture)

    decision_parser = subcommands.add_parser(
        "decision-capture",
        help="record one focus-scoped rejected/accepted decision activation",
    )
    decision_parser.add_argument("--oracle-root", required=True, type=Path)
    decision_parser.add_argument("--plan", required=True, type=Path)
    decision_parser.add_argument("--case-id", required=True)
    decision_parser.add_argument(
        "--phase", choices=("rejected", "accepted", "heldout"), required=True,
    )
    decision_parser.add_argument("--output", type=Path,
                                 default=Path("decision-miner"))
    decision_parser.add_argument("--scenario", required=True)
    decision_parser.add_argument("--cycles", required=True, type=int)
    decision_parser.add_argument("--seed", default=1, type=int)
    decision_parser.add_argument("--timeout", default=120.0, type=float)
    decision_parser.add_argument("--host-gdb", action="store_true")
    decision_parser.add_argument("--gdb", default="/usr/bin/gdb")
    decision_parser.add_argument("--sudo", default="/usr/bin/sudo")
    decision_parser.add_argument("--name", default="chonkcraft-bne-decision-miner")
    decision_parser.add_argument("--harness-name", default="harness-decision-miner")
    decision_parser.set_defaults(func=decision_capture)

    snapshot_parser = subcommands.add_parser(
        "snapshot-capture",
        help="save the machine at one native decision for offline replay",
    )
    snapshot_parser.add_argument("--oracle-root", required=True, type=Path)
    snapshot_parser.add_argument(
        "--specification", required=True, type=Path,
        help="reviewed micro-oracle capture specification",
    )
    snapshot_parser.add_argument("--case-id", required=True)
    snapshot_parser.add_argument("--output", type=Path,
                                 default=Path("micro-oracle"))
    snapshot_parser.add_argument("--scenario", required=True)
    snapshot_parser.add_argument("--cycles", required=True, type=int)
    snapshot_parser.add_argument("--seed", default=1, type=int)
    snapshot_parser.add_argument("--timeout", default=120.0, type=float)
    snapshot_parser.add_argument("--host-gdb", action="store_true")
    snapshot_parser.add_argument("--gdb", default="/usr/bin/gdb")
    snapshot_parser.add_argument("--sudo", default="/usr/bin/sudo")
    snapshot_parser.add_argument("--name", default="chonkcraft-bne-micro-oracle")
    snapshot_parser.add_argument("--harness-name", default="harness-micro-oracle")
    snapshot_parser.set_defaults(func=snapshot_capture)

    corpus_parser = subcommands.add_parser("corpus")
    corpus_parser.add_argument("--oracle-root", required=True, type=Path)
    corpus_parser.add_argument("--plan", required=True, type=Path)
    corpus_parser.add_argument("--output", required=True, type=Path)
    corpus_parser.add_argument("--detach", action="store_true")
    corpus_parser.add_argument("--name", default="chonkcraft-bne-corpus")
    corpus_parser.add_argument(
        "--harness-name", default="harness",
        help="isolated harness directory below the oracle root",
    )
    corpus_parser.set_defaults(func=corpus)
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        if getattr(args, "cycles", 1) <= 0:
            raise ValueError("--cycles must be positive")
        if getattr(args, "seed", 0) < 0 or getattr(args, "seed", 0) > 0xffffffff:
            raise ValueError("--seed must be an unsigned 32-bit integer")
        return args.func(args)
    except (OSError, ValueError) as error:
        print(f"bne-headless: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
