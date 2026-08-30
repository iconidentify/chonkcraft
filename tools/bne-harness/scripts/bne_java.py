#!/usr/bin/env python3
"""Run the Java engine against sealed BNE campaign fixtures."""

from __future__ import annotations

import argparse
import ast
import copy
import concurrent.futures
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
from typing import Any
import zipfile

from bne_capture_plan import PROFILES as CAPTURE_PROFILES
from bne_evidence_catalog import PROFILE_FAMILIES as PROFILES
from bne_fixture import validate_fixture


ROOT = Path(__file__).resolve().parents[3]
DEFAULT_REMOTE_HOST = os.environ.get("CHONKCRAFT_ORACLE_HOST", "i9beef")
COMPARE = Path(__file__).with_name("bne_compare.py")
INDEX_SCHEMA = 1
SURVEY_SCHEMA = 1
FIRST_DIVERGENCE = re.compile(r"first divergence at cycle (\d+)")
FINDING_LINE = re.compile(r"^\s*cycle (?P<cycle>\d+): (?P<body>.+)$")
UNIT_FINDING = re.compile(
    r"^unit (?P<unit>\d+) \((?P<unit_type>[^)]+)\) "
    r"(?P<field>\S+) (?P<oracle>.+?) vs (?P<java>.+)$"
)
UNIT_ONLY_FINDING = re.compile(
    r"^unit (?P<unit>\d+) \((?P<unit_type>[^)]+)\) only in the "
    r"(?P<side>left|right) trace$"
)
BANK_FINDING = re.compile(
    r"^p(?P<player>\d+) bank (?P<oracle>.+?) vs (?P<java>.+)$"
)
SEED_FINDING = re.compile(
    r"^seed (?P<oracle>\S+) vs (?P<java>\S+)(?: -- (?P<reason>.+))?$"
)
ACTION_ORDER_FINDING = re.compile(
    r"^action-table order first differs at (?P<index>\d+): "
    r"unit (?P<oracle>\S+) vs (?P<java>\S+)$"
)
SCENARIOS = (
    (re.compile(r"Campaign\\Human\\Human(\d{2})\.pud\Z"),
     "campaigns/human/level{mission:02d}h"),
    (re.compile(r"Campaign\\Orc\\Orc(\d{2})\.pud\Z"),
     "campaigns/orc/level{mission:02d}o"),
    (re.compile(r"Campaign\\XHuman\\2XHum(\d{2})\.pud\Z"),
     "campaigns/human-exp/levelx{mission:02d}h"),
    (re.compile(r"Campaign\\XOrc\\2XOrc(\d{2})\.pud\Z"),
     "campaigns/orc-exp/levelx{mission:02d}o"),
)


@dataclass(frozen=True)
class Case:
    case_id: str
    fixture: Path
    fixture_id: str
    scenario: str
    java_map: str
    cycles: int
    seed: int
    state_schema: str


def file_identity(path: Path) -> dict[str, int | str]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            size += len(block)
            digest.update(block)
    return {"bytes": size, "sha256": digest.hexdigest()}


def _finding_value(value: str) -> object:
    """Convert the differ's scalar/tuple rendering into JSON-safe values."""
    try:
        parsed = ast.literal_eval(value)
    except (SyntaxError, ValueError):
        return value
    if isinstance(parsed, (bool, int, float, str, tuple, list)) \
            or parsed is None:
        return list(parsed) if isinstance(parsed, tuple) else parsed
    return value


def parse_comparison_findings(comparison: str) -> list[dict[str, object]]:
    """Structure first-divergence lines while preserving their exact text."""
    findings: list[dict[str, object]] = []
    for line in comparison.splitlines():
        matched_line = FINDING_LINE.fullmatch(line)
        if matched_line is None:
            continue
        cycle = int(matched_line.group("cycle"))
        body = matched_line.group("body")
        finding: dict[str, object] = {
            "cycle": cycle,
            "message": body,
        }
        if matched := UNIT_FINDING.fullmatch(body):
            finding.update({
                "kind": "unit",
                "unit": int(matched.group("unit")),
                "unit_type": matched.group("unit_type"),
                "field": matched.group("field"),
                "oracle": _finding_value(matched.group("oracle")),
                "java": _finding_value(matched.group("java")),
            })
        elif matched := UNIT_ONLY_FINDING.fullmatch(body):
            side = matched.group("side")
            finding.update({
                "kind": "unit_population",
                "unit": int(matched.group("unit")),
                "unit_type": matched.group("unit_type"),
                "oracle": "present" if side == "left" else "absent",
                "java": "absent" if side == "left" else "present",
            })
        elif matched := BANK_FINDING.fullmatch(body):
            finding.update({
                "kind": "player_bank",
                "player": int(matched.group("player")),
                "oracle": _finding_value(matched.group("oracle")),
                "java": _finding_value(matched.group("java")),
            })
        elif matched := SEED_FINDING.fullmatch(body):
            finding.update({
                "kind": "sync_rng",
                "oracle": matched.group("oracle"),
                "java": matched.group("java"),
            })
            if matched.group("reason"):
                finding["reason"] = matched.group("reason")
        elif matched := ACTION_ORDER_FINDING.fullmatch(body):
            finding.update({
                "kind": "action_order",
                "index": int(matched.group("index")),
                "oracle": _finding_value(matched.group("oracle")),
                "java": _finding_value(matched.group("java")),
            })
        else:
            finding["kind"] = "other"
        findings.append(finding)
    return findings


def scenario_to_java_map(scenario: str) -> str:
    for pattern, template in SCENARIOS:
        match = pattern.fullmatch(scenario)
        if match:
            return template.format(mission=int(match.group(1)))
    raise ValueError(f"unsupported BNE campaign scenario: {scenario!r}")


def _fixture_manifest(path: Path) -> dict[str, Any]:
    with zipfile.ZipFile(path) as archive:
        return json.loads(archive.read("manifest.json"))


def inspect_fixture(path: Path, case_id: str | None = None,
        *, trusted_identity: bool = False) -> Case:
    path = path.expanduser().resolve()
    manifest = _fixture_manifest(path)
    run = manifest["run"]
    if trusted_identity:
        # load_index has already matched the archive's bytes and SHA-256 to
        # the sealed index. Reconstructing all 1,800 state checkpoints again
        # here would authenticate the same bytes a second time.
        validation = {
            "fixture_id": manifest["fixture"]["id"],
            "cycles": run["state"]["validation"]["cycles"],
        }
    else:
        validation = validate_fixture(path)
    scenario = run["requested_scenario"]
    cycles = run["cycle_limit"]
    seed = run["initialization_seed"]
    state_schema = run["state"]["validation"]["schema"]
    if state_schema != "1.1":
        raise ValueError(
            f"BNE Java loop requires state schema 1.1, got {state_schema!r}"
        )
    commands = run.get("commands")
    if commands is not None:
        if not isinstance(commands, dict) or commands.get("count", 0) < 1:
            raise ValueError("commanded fixture does not prove an applied command")
        with zipfile.ZipFile(path) as archive:
            if "commands.txt" not in archive.namelist():
                raise ValueError("commanded fixture is missing commands.txt")
    if cycles != validation["cycles"]:
        raise ValueError("fixture manifest and state stream disagree on cycle count")
    if not isinstance(seed, int) or seed < 0 or seed > 0xffffffff:
        raise ValueError(f"fixture has invalid initialization seed {seed!r}")
    return Case(
        case_id=case_id or path.stem,
        fixture=path,
        fixture_id=validation["fixture_id"],
        scenario=scenario,
        java_map=scenario_to_java_map(scenario),
        cycles=cycles,
        seed=seed,
        state_schema=state_schema,
    )


def load_index(path: Path, allow_partial: bool = False) -> list[Case]:
    path = path.expanduser().resolve()
    root = path.parent
    data = json.loads(path.read_text(encoding="utf-8"))
    records = data.get("cases")
    if data.get("schema") != INDEX_SCHEMA or not isinstance(records, list):
        raise ValueError(f"invalid BNE corpus index: {path}")
    if not allow_partial and len(records) != 52:
        raise ValueError(
            f"campaign corpus contains {len(records)} cases; expected all 52 "
            "(pass --allow-partial only for an intentional smoke run)"
        )
    result = []
    seen = set()
    for record in records:
        case_id = record.get("id")
        if not isinstance(case_id, str) or not case_id or case_id in seen:
            raise ValueError(f"invalid or duplicate corpus case id {case_id!r}")
        seen.add(case_id)
        relative = Path(record["fixture"]["path"])
        fixture = (root / relative).resolve()
        if not fixture.is_relative_to(root):
            raise ValueError(f"unsafe fixture path for case {case_id!r}")
        wanted = {key: record["fixture"][key] for key in ("bytes", "sha256")}
        if file_identity(fixture) != wanted:
            raise ValueError(f"indexed fixture identity changed: {fixture}")
        case = inspect_fixture(fixture, case_id, trusted_identity=True)
        expected = {
            "fixture_id": record["fixture_id"],
            "scenario": record["scenario"],
            "cycles": record["cycles"],
            "seed": record["seed"],
            "state_schema": record["state_schema"],
        }
        actual = {
            "fixture_id": case.fixture_id,
            "scenario": case.scenario,
            "cycles": case.cycles,
            "seed": case.seed,
            "state_schema": case.state_schema,
        }
        if actual != expected:
            raise ValueError(
                f"corpus index metadata differs from fixture {case_id!r}: "
                f"{actual!r} != {expected!r}"
            )
        result.append(case)
    return result


def java_classpath() -> str:
    paths = (
        "engine/target/classes",
        "engine/target/test-classes",
        "data/target/classes",
        "assetpack/target/classes",
        "runtime/target/classes",
    )
    return os.pathsep.join(str(ROOT / path) for path in paths)


def java_command(args: argparse.Namespace, case: Case, output: Path,
        commands: Path | None = None) -> list[str]:
    launcher = ([str(args.java_wrapper.resolve()), args.java]
                if args.java_wrapper is not None else [args.java])
    asset_argument = (
        f"-Dchonkcraft.pack={args.asset_pack.resolve()}"
        if args.asset_pack is not None
        else f"-Dwc2.install.dir={args.install_dir.resolve()}"
    )
    through = getattr(args, "through", None)
    cycles = case.cycles if through is None else min(case.cycles, through)
    semantic_v2 = (["-Dchonkcraft.trace.bne.semantic-v2=true"]
                   if getattr(args, "semantic_v2", False) else [])
    semantic_families = getattr(args, "semantic_v2_family", None)
    if semantic_v2 and semantic_families:
        semantic_v2.append(
            "-Dchonkcraft.trace.bne.semantic-v2.families="
            + ",".join(semantic_families))
    scripted = ([f"-Dchonkcraft.trace.commands={commands.resolve()}"]
                if commands is not None else [])
    return [
        *launcher,
        "-cp", java_classpath(),
        asset_argument,
        "-Djava.awt.headless=true",
        f"-Dchonkcraft.trace.seed={case.seed}",
        "-Dchonkcraft.trace.profile=bne",
        *semantic_v2,
        *scripted,
        "net.chonkbase.chonkcraft.engine.parity.EngineTrace",
        case.java_map,
        str(cycles),
        str(output),
    ]


def validate_runtime(args: argparse.Namespace) -> None:
    if args.asset_pack is not None:
        if not args.asset_pack.expanduser().is_file():
            raise ValueError(f"Chonkpack is missing: {args.asset_pack}")
    elif args.install_dir is None or not args.install_dir.expanduser().is_dir():
        raise ValueError(
            "choose an existing Warcraft II source with --asset-pack or "
            "--install-dir"
        )
    if args.java_wrapper is not None and not args.java_wrapper.expanduser().is_file():
        raise ValueError(f"Java wrapper is missing: {args.java_wrapper}")


def compile_java(args: argparse.Namespace) -> float:
    started = time.monotonic()
    if args.skip_build:
        return 0.0
    subprocess.run(
        [args.maven, "-o", "test-compile", "-pl", "engine", "-am"],
        cwd=ROOT, check=True,
    )
    return time.monotonic() - started


def _git_identity() -> dict[str, object]:
    """Fingerprint only the declared engine inputs.

    This used to hash every untracked file in the workspace, so 76 MB of
    `.bne-*` evidence, `goal/` notes and captured traces sat inside the engine
    cache key: a clean source tree reported `dirty`, and writing a diagnostic
    report invalidated proofs it could not possibly change. One implementation
    now answers the question, in `bne_identity`, which is why nothing here
    reads the working tree directly any more.
    """
    from bne_identity import engine_input_identity

    return engine_input_identity(ROOT)


def _workspace_noise() -> dict[str, object]:
    """Report the churn the engine identity deliberately ignores."""
    from bne_identity import workspace_noise

    return workspace_noise(ROOT)


def _cached_engine_identity(args: argparse.Namespace) -> dict[str, object]:
    """Fingerprint the workspace once, before corpus workers start."""
    identity = getattr(args, "engine_identity", None)
    if identity is None:
        identity = _git_identity()
        args.engine_identity = identity
    return identity


def _asset_source_identity(args: argparse.Namespace) -> dict[str, object]:
    if args.asset_pack is not None:
        path = args.asset_pack.expanduser().resolve()
        return {"kind": "chonkpack", "path": str(path), **file_identity(path)}
    return {
        "kind": "installation",
        "path": str(args.install_dir.expanduser().resolve()),
    }


def _cached_asset_source_identity(args: argparse.Namespace) -> dict[str, object]:
    """Hash the shared asset source once, not once per corpus worker."""
    identity = getattr(args, "asset_source_identity", None)
    if identity is None:
        identity = _asset_source_identity(args)
        args.asset_source_identity = identity
    return identity


def _command_identity(command: list[str]) -> dict[str, object]:
    executable = shutil.which(command[0]) or command[0]
    completed = subprocess.run(
        [*command, "-version"], check=False, capture_output=True, text=True,
    )
    return {
        "command": command,
        "executable": str(Path(executable).expanduser().resolve()),
        "returncode": completed.returncode,
        "version": (completed.stdout + completed.stderr).strip(),
    }


def _source_workspace_identity(path: Path | None) -> dict[str, object]:
    if path is None:
        return {
            "kind": "unused-native-pack-runtime",
            "path": None,
        }
    path = path.expanduser().resolve()
    probe = subprocess.run(
        ["git", "-C", str(path), "rev-parse", "--show-toplevel"],
        check=False, capture_output=True, text=True,
    )
    if probe.returncode != 0:
        entry = path / "scripts" / "legacyEngine.legacy-declaration"
        return {
            "path": str(path),
            "kind": "directory",
            "legacy_definition_entry": file_identity(entry),
        }
    root = Path(probe.stdout.strip()).resolve()
    head = subprocess.run(
        ["git", "-C", str(root), "rev-parse", "HEAD"], check=True,
        capture_output=True, text=True,
    ).stdout.strip()
    status = subprocess.run(
        ["git", "-C", str(root), "status", "--porcelain=v1", "-z",
         "--untracked-files=all"],
        check=True, capture_output=True,
    ).stdout
    digest = hashlib.sha256()
    digest.update(b"head\0" + head.encode("ascii") + b"\0")
    digest.update(subprocess.run(
        ["git", "-C", str(root), "diff", "--binary", "HEAD", "--"],
        check=True, capture_output=True,
    ).stdout)
    untracked = subprocess.run(
        ["git", "-C", str(root), "ls-files", "--others",
         "--exclude-standard", "-z"],
        check=True, capture_output=True,
    ).stdout.split(b"\0")
    for raw_name in sorted(name for name in untracked if name):
        untracked_path = root / os.fsdecode(raw_name)
        digest.update(b"untracked\0" + raw_name + b"\0")
        if untracked_path.is_file():
            with untracked_path.open("rb") as source:
                for block in iter(lambda: source.read(1024 * 1024), b""):
                    digest.update(block)
        else:
            digest.update(b"non-file")
    return {
        "path": str(path),
        "git_root": str(root),
        "head": head,
        "dirty": bool(status),
        "workspace_sha256": digest.hexdigest(),
    }


def _compiled_classpath_identity() -> dict[str, object]:
    digest = hashlib.sha256()
    count = 0
    size = 0
    missing = []
    for entry in java_classpath().split(os.pathsep):
        root = Path(entry)
        label = str(root.relative_to(ROOT))
        if not root.is_dir():
            missing.append(label)
            continue
        for path in sorted(item for item in root.rglob("*") if item.is_file()):
            relative = path.relative_to(root)
            digest.update(label.encode("utf-8") + b"\0")
            digest.update(os.fsencode(relative) + b"\0")
            with path.open("rb") as source:
                for block in iter(lambda: source.read(1024 * 1024), b""):
                    size += len(block)
                    digest.update(block)
            count += 1
    return {
        "files": count,
        "bytes": size,
        "sha256": digest.hexdigest(),
        "missing_entries": missing,
    }


def _write_json_atomic(path: Path, data: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
                mode="w", encoding="utf-8", prefix=path.name + ".",
                suffix=".tmp", dir=path.parent, delete=False) as handle:
            temporary = Path(handle.name)
            json.dump(data, handle, indent=2, sort_keys=True)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _write_text_atomic(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
                mode="w", encoding="utf-8", prefix=path.name + ".",
                suffix=".tmp", dir=path.parent, delete=False) as handle:
            temporary = Path(handle.name)
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _translated_fixture_commands(args: argparse.Namespace, case: Case,
        output_dir: Path) -> Path | None:
    """Pair native command slots to Java ids before the commanded run."""
    with zipfile.ZipFile(case.fixture) as archive:
        if "commands.txt" not in archive.namelist():
            return None
        source = archive.read("commands.txt").decode("ascii")
    command_pattern = re.compile(
        r"cycle (\d+) move unit (\d+) x (\d+) y (\d+)\Z")
    commands = []
    for line_number, raw in enumerate(source.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        match = command_pattern.fullmatch(line)
        if match is None:
            raise ValueError(f"unsupported fixture command at line {line_number}")
        commands.append(tuple(int(value) for value in match.groups()))
    if not commands:
        raise ValueError("fixture commands.txt contains no command")

    preliminary = output_dir / f"{case.case_id}.pairing.java.trace.txt"
    command = java_command(args, case, preliminary)
    command[-2] = "1"
    ran = subprocess.run(command, cwd=ROOT, check=False,
                         capture_output=True, text=True)
    if ran.returncode != 0:
        raise RuntimeError("Java command-pairing trace failed: "
                           + (ran.stdout + ran.stderr)[-2000:])
    java_positions: dict[tuple[int, int, int], list[int]] = {}
    unit_line = re.compile(r"u (\d+) \S+ p(\d+) (-?\d+) (-?\d+) hp ")
    for line in preliminary.read_text(errors="replace").splitlines():
        if line.startswith("cycle ") and not line.startswith("cycle 1 "):
            break
        match = unit_line.match(line)
        if match:
            ident, player, x, y = (int(value) for value in match.groups())
            java_positions.setdefault((x, y, player), []).append(ident)

    from bne_semantic_v2 import native_cycles
    native = next(native_cycles(case.fixture))["units"]
    native_positions: dict[int, tuple[int, int, int]] = {
        slot: (int.from_bytes(raw[24:26], "little"),
               int.from_bytes(raw[26:28], "little"), raw[44])
        for slot, raw in native.items()
    }
    translated = ["# bne-java-command-plan-v1"]
    for cycle, slot, x, y in commands:
        position = native_positions.get(slot)
        matches = [] if position is None else java_positions.get(position, [])
        if len(matches) != 1:
            raise ValueError(
                f"native command slot {slot} has {len(matches)} Java pairing "
                f"candidates at cycle one")
        translated.append(
            f"cycle {cycle} move unit {matches[0]} x {x} y {y}")
    destination = output_dir / f"{case.case_id}.java.commands.txt"
    _write_text_atomic(destination, "\n".join(translated) + "\n")
    return destination


def run_case(args: argparse.Namespace, case: Case) -> dict[str, object]:
    from bne_compare import validate_java_trace_cycles

    case_started = time.monotonic()
    output_dir = args.output_dir.expanduser().resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    scripted_commands = _translated_fixture_commands(args, case, output_dir)
    trace = output_dir / f"{case.case_id}.java.trace.txt"
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
                prefix=trace.name + ".", suffix=".tmp",
                dir=output_dir, delete=False) as handle:
            temporary = Path(handle.name)
        command = java_command(args, case, temporary, scripted_commands)
        java_started = time.monotonic()
        java = subprocess.run(
            command, cwd=ROOT, check=False, capture_output=True, text=True,
        )
        java_seconds = time.monotonic() - java_started
        if java.returncode != 0:
            raise RuntimeError(
                f"Java trace failed for {case.case_id} ({java.returncode}): "
                f"{(java.stdout + java.stderr)[-2000:]}"
            )
        through = getattr(args, "through", None)
        compared_cycles = case.cycles if through is None else min(case.cycles, through)
        validate_java_trace_cycles(temporary, compared_cycles)
        os.replace(temporary, trace)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)

    process_output = {}
    for stream_name, content in (("stdout", java.stdout), ("stderr", java.stderr)):
        if not content:
            continue
        stream_path = output_dir / f"{case.case_id}.java.{stream_name}.txt"
        _write_text_atomic(stream_path, content)
        process_output[stream_name] = {
            "path": str(stream_path),
            **file_identity(stream_path),
        }

    compare_command = [
        sys.executable, str(COMPARE), str(case.fixture), str(trace),
    ]
    if args.report_all:
        compare_command.append("--all")
    if getattr(args, "through", None) is not None:
        compare_command.extend(("--through", str(args.through)))
    if getattr(args, "trusted_index", False):
        compare_command.append("--trusted-fixture")
    compare_started = time.monotonic()
    compared = subprocess.run(
        compare_command, cwd=ROOT, check=False, capture_output=True, text=True,
    )
    compare_seconds = time.monotonic() - compare_started
    comparison = (compared.stdout + compared.stderr).strip()
    if compared.returncode == 0:
        state = "clean"
        first_cycle = None
    elif compared.returncode == 1 and FIRST_DIVERGENCE.search(comparison):
        state = "divergent"
        first_cycle = int(FIRST_DIVERGENCE.search(comparison).group(1))
    else:
        state = "failed"
        first_cycle = None
    semantic_v2_result = None
    if getattr(args, "semantic_v2", False):
        from bne_semantic_v2 import compare as compare_semantic_v2

        semantic_v2_result = compare_semantic_v2(
            case.fixture, trace, compared_cycles,
            families=(set(args.semantic_v2_family)
                      if getattr(args, "semantic_v2_family", None) else None))
        semantic_path = output_dir / f"{case.case_id}.semantic-v2.json"
        _write_json_atomic(semantic_path, semantic_v2_result)
        if semantic_v2_result["status"] == "DIVERGED":
            v2_cycle = min(item["cycle"]
                           for item in semantic_v2_result["mismatches"])
            if state == "clean" or first_cycle is None or v2_cycle < first_cycle:
                state = "divergent"
                first_cycle = v2_cycle
        elif semantic_v2_result["status"] != "PASS":
            state = "failed"
            first_cycle = None
    result: dict[str, object] = {
        "id": case.case_id,
        "fixture_id": case.fixture_id,
        "scenario": case.scenario,
        "java_map": case.java_map,
        "cycles": case.cycles,
        "compared_cycles": compared_cycles,
        "seed": case.seed,
        "state_schema": case.state_schema,
        "comparison_tier": ("semantic-v1+semantic-v2"
                            if semantic_v2_result is not None
                            else "semantic-v1"),
        "state": state,
        "first_divergence_cycle": first_cycle,
        "findings": parse_comparison_findings(comparison),
        "java_trace": {"path": str(trace), **file_identity(trace)},
        "java_process_output": process_output,
        "comparison_output": comparison,
        "timings": {
            "java_seconds": round(java_seconds, 6),
            "compare_seconds": round(compare_seconds, 6),
            "total_seconds": round(time.monotonic() - case_started, 6),
        },
    }
    if semantic_v2_result is not None:
        result["semantic_v2"] = semantic_v2_result
    if scripted_commands is not None:
        result["translated_commands"] = {
            "path": str(scripted_commands), **file_identity(scripted_commands)}
    _write_json_atomic(
        output_dir / f"{case.case_id}.java-run.json",
        {"schema": 1, "engine": _cached_engine_identity(args),
         "asset_source": _cached_asset_source_identity(args), **result},
    )
    return result


def case_command(args: argparse.Namespace) -> int:
    validate_runtime(args)
    _cached_asset_source_identity(args)
    _cached_engine_identity(args)
    compile_java(args)
    result = run_case(args, inspect_fixture(args.fixture))
    print(result["comparison_output"])
    return 2 if result["state"] == "failed" else 0


def survey_command(args: argparse.Namespace) -> int:
    survey_started = time.monotonic()
    validate_runtime(args)
    _cached_asset_source_identity(args)
    _cached_engine_identity(args)
    setup_started = time.monotonic()
    cases = getattr(args, "preloaded_cases", None)
    if cases is None:
        cases = load_index(args.index, args.allow_partial)
    args.trusted_index = True
    if args.case:
        wanted = set(args.case)
        cases = [case for case in cases if case.case_id in wanted]
        missing = wanted - {case.case_id for case in cases}
        if missing:
            raise ValueError(f"unknown corpus case ids: {sorted(missing)!r}")
    setup_seconds = time.monotonic() - setup_started
    build_seconds = compile_java(args)
    results: dict[str, dict[str, object]] = {}
    cases_started = time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
        pending = {pool.submit(run_case, args, case): case for case in cases}
        for future in concurrent.futures.as_completed(pending):
            case = pending[future]
            try:
                result = future.result()
            except Exception as error:  # preserve every other case's result
                result = {
                    "id": case.case_id,
                    "fixture_id": case.fixture_id,
                    "scenario": case.scenario,
                    "java_map": case.java_map,
                    "cycles": case.cycles,
                    "seed": case.seed,
                    "state_schema": case.state_schema,
                    "comparison_tier": "semantic-v1",
                    "state": "failed",
                    "first_divergence_cycle": None,
                    "comparison_output": str(error),
                }
            results[case.case_id] = result
            suffix = ("" if result["state"] == "clean" else
                      f" @{result.get('first_divergence_cycle')}"
                      if result["state"] == "divergent" else
                      f": {result['comparison_output'][-300:]}")
            print(f"{result['state']:10} {case.case_id}{suffix}", flush=True)
    cases_seconds = time.monotonic() - cases_started

    ordered = [results[case.case_id] for case in cases]
    counts = {state: sum(item["state"] == state for item in ordered)
              for state in ("clean", "divergent", "failed")}
    survey = {
        "schema": SURVEY_SCHEMA,
        "comparison_tier": "semantic-v1",
        "through": args.through,
        "coverage": {
            "compared": ["cycle", "sync_rng", "player_banks", "unit_core",
                         "coarse_unit_order"],
            "pending_1_1": ["extended_player_state", "projectiles",
                            "mutable_map_state", "decoded_raw_unit_state"],
            "unit_pool_order_compared": False,
        },
        "engine": _cached_engine_identity(args),
        # Reported so an operator can see what the engine key ignored, and
        # kept out of "engine" so reading it can never move a cache key.
        "workspace_noise": _workspace_noise(),
        "asset_source": _cached_asset_source_identity(args),
        "runtime": {
            "mode": "native-pack",
            "source_dir": None,
        },
        "index": str(args.index.expanduser().resolve()),
        "counts": counts,
        "cases": ordered,
        "timings": {
            "setup_seconds": round(setup_seconds, 6),
            "build_seconds": round(build_seconds, 6),
            "cases_wall_seconds": round(cases_seconds, 6),
            "total_seconds": round(time.monotonic() - survey_started, 6),
        },
    }
    destination = args.output_dir.expanduser().resolve() / "bne-java-survey.json"
    _write_json_atomic(destination, survey)
    print(
        f"\nBNE semantic survey: {len(ordered)} cases; "
        f"{counts['clean']} clean, {counts['divergent']} divergent, "
        f"{counts['failed']} failed\n{destination}"
    )
    if counts["failed"]:
        return 2
    if args.baseline_survey:
        baseline_paths = [path.expanduser().resolve()
                          for path in args.baseline_survey]
        baselines = [load_survey(path) for path in baseline_paths]
        gate = evaluate_gate(
            baselines, survey,
            allow_asset_migration=args.allow_asset_migration,
        )
        print("\n" + format_gate(gate))
        if not gate["passed"]:
            return 1
        if getattr(args, "record_gate_acceptance", False):
            receipt = _record_gate_acceptance(
                destination, baseline_paths, survey, gate, args.artifact_root,
            )
            if receipt["promoted"]:
                print(
                    f"Accepted proof pointer promoted to h{receipt['frontier']}.\n"
                    f"Durable gate receipt: {receipt['run']}"
                )
    return 0


def _validate_survey(data: dict[str, Any], source: str) -> dict[str, Any]:
    if data.get("schema") != SURVEY_SCHEMA:
        raise ValueError(f"unsupported BNE Java survey schema in {source}")
    records = data.get("cases")
    if not isinstance(records, list):
        raise ValueError(f"survey has no case records: {source}")
    seen: set[str] = set()
    for record in records:
        if not isinstance(record, dict):
            raise ValueError(f"survey contains a non-object case: {source}")
        case_id = record.get("id")
        if not isinstance(case_id, str) or not case_id or case_id in seen:
            raise ValueError(f"survey has invalid/duplicate case {case_id!r}: {source}")
        seen.add(case_id)
        if record.get("state") not in ("clean", "divergent", "failed"):
            raise ValueError(f"survey case {case_id!r} has invalid state: {source}")
        compared = record.get("compared_cycles")
        if record.get("state") != "failed" \
                and (not isinstance(compared, int) or compared <= 0):
            raise ValueError(
                f"survey case {case_id!r} has invalid compared_cycles: {source}"
            )
    return data


def load_survey(path: Path) -> dict[str, Any]:
    path = path.expanduser().resolve()
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"survey is not a JSON object: {path}")
    return _validate_survey(data, str(path))


def _clean_through(record: dict[str, Any]) -> int | None:
    if record.get("state") == "clean":
        return record.get("compared_cycles")
    if record.get("state") == "divergent":
        cycle = record.get("first_divergence_cycle")
        if not isinstance(cycle, int) or cycle <= 0:
            raise ValueError(
                f"divergent survey case {record.get('id')!r} has no first cycle"
            )
        return cycle - 1
    return None


def _record_findings(record: dict[str, Any]) -> list[dict[str, object]]:
    findings = record.get("findings")
    if isinstance(findings, list):
        return [item for item in findings if isinstance(item, dict)]
    comparison = record.get("comparison_output")
    return parse_comparison_findings(comparison if isinstance(comparison, str) else "")


def build_frontier(surveys: list[dict[str, Any]],
        sources: list[str] | None = None) -> dict[str, Any]:
    """Combine same-engine surveys into explicit per-case proof frontiers."""
    if not surveys:
        raise ValueError("frontier requires at least one survey")
    surveys = [
        _validate_survey(survey, sources[index] if sources else f"survey {index + 1}")
        for index, survey in enumerate(surveys)
    ]
    tiers = {survey.get("comparison_tier") for survey in surveys}
    if len(tiers) != 1:
        raise ValueError(f"cannot combine comparison tiers: {sorted(map(str, tiers))}")
    engines = [survey.get("engine") for survey in surveys]
    if any(engine != engines[0] for engine in engines[1:]):
        raise ValueError(
            "frontier surveys describe different engine workspaces; "
            "use gate to compare revisions"
        )
    assets = [survey.get("asset_source") for survey in surveys]
    if any(asset != assets[0] for asset in assets[1:]):
        raise ValueError("frontier surveys use different asset sources")

    observations: dict[str, list[dict[str, Any]]] = {}
    for survey in surveys:
        for record in survey["cases"]:
            observations.setdefault(record["id"], []).append(record)

    cases: list[dict[str, Any]] = []
    for case_id, records in sorted(observations.items()):
        fixture_ids = {record.get("fixture_id") for record in records
                       if record.get("fixture_id") is not None}
        if len(fixture_ids) > 1:
            raise ValueError(
                f"frontier surveys use different fixtures for {case_id}: "
                f"{sorted(map(str, fixture_ids))}"
            )
        proofs = [proof for record in records
                  if (proof := _clean_through(record)) is not None]
        divergences = [record["first_divergence_cycle"] for record in records
                       if record.get("state") == "divergent"]
        clean_through = max(proofs) if proofs else None
        first_divergence = min(divergences) if divergences else None
        if clean_through is not None and first_divergence is not None \
                and clean_through >= first_divergence:
            raise ValueError(
                f"inconsistent frontier for {case_id}: clean through "
                f"{clean_through}, divergent at {first_divergence}"
            )
        compared = [record.get("compared_cycles") for record in records
                    if isinstance(record.get("compared_cycles"), int)]
        divergent_record = next((record for record in records
                                 if record.get("first_divergence_cycle")
                                 == first_divergence), None)
        cases.append({
            "id": case_id,
            "fixture_id": next(iter(fixture_ids)) if fixture_ids else None,
            "state": ("divergent" if first_divergence is not None else
                      "clean" if clean_through is not None else "failed"),
            "clean_through": clean_through,
            "first_divergence_cycle": first_divergence,
            "probed_through": max(compared) if compared else None,
            "findings": (_record_findings(divergent_record)
                         if divergent_record is not None else []),
            "failed_observations": sum(record.get("state") == "failed"
                                       for record in records),
        })
    proven = [case["clean_through"] for case in cases
              if isinstance(case["clean_through"], int)]
    divergences = [case["first_divergence_cycle"] for case in cases
                   if isinstance(case["first_divergence_cycle"], int)]
    counts = {state: sum(case["state"] == state for case in cases)
              for state in ("clean", "divergent", "failed")}
    from bne_experiments import rank_tied_blockers

    return {
        "schema": 1,
        "comparison_tier": next(iter(tiers)),
        "engine": engines[0],
        "asset_source": assets[0],
        "sources": sources or [],
        "case_count": len(cases),
        "common_clean_through": min(proven) if len(proven) == len(cases) else None,
        "earliest_divergence_cycle": min(divergences) if divergences else None,
        "counts": counts,
        "cases": cases,
        "tied_blockers": rank_tied_blockers(cases),
    }


def _case_names(cases: list[dict[str, Any]]) -> str:
    names = [case["id"] for case in cases]
    return ", ".join(names) if len(names) <= 5 else f"{len(names)} cases"


def format_frontier(frontier: dict[str, Any], show_all: bool = False) -> str:
    engine = frontier.get("engine") or {}
    head = str(engine.get("head", "unknown"))[:12]
    dirty = "dirty" if engine.get("dirty") else "clean"
    lines = [
        "# BNE parity frontier",
        "",
        f"- Engine: `{head}` ({dirty})",
        f"- Cases: **{frontier['case_count']}**",
        f"- Common clean horizon: **{frontier['common_clean_through']}**",
        f"- Earliest divergence: **{frontier['earliest_divergence_cycle']}**",
        "",
        "## Frontier groups",
        "",
        "| Clean through | Next divergence | Cases |",
        "|---:|---:|---|",
    ]
    groups: dict[tuple[object, object], list[dict[str, Any]]] = {}
    for case in frontier["cases"]:
        key = (case["clean_through"], case["first_divergence_cycle"])
        groups.setdefault(key, []).append(case)
    def group_key(item: tuple[tuple[object, object], object]) -> tuple[int, int]:
        clean, divergence = item[0]
        return (clean if isinstance(clean, int) else -1,
                divergence if isinstance(divergence, int) else 1 << 30)
    for (clean, divergence), cases in sorted(groups.items(), key=group_key):
        lines.append(
            f"| {clean if clean is not None else '—'} | "
            f"{divergence if divergence is not None else '—'} | "
            f"{_case_names(cases)} |"
        )
    open_cases = [case for case in frontier["cases"]
                  if case["state"] != "clean"]
    if open_cases:
        lines.extend([
            "",
            "## Open cases",
            "",
            "| Case | Clean through | First divergence | First findings |",
            "|---|---:|---:|---|",
        ])
        for case in open_cases:
            messages = [finding.get("message", "")
                        for finding in case.get("findings", [])[:3]]
            lines.append(
                f"| `{case['id']}` | {case['clean_through']} | "
                f"{case['first_divergence_cycle']} | {'; '.join(messages) or '—'} |"
            )
    tied = frontier.get("tied_blockers", [])
    if len(tied) > 1:
        lines.extend([
            "", "## Earliest tied-blocker investigation order", "",
            "This ordering estimates diagnostic cost only; it does not change "
            "the acceptance priority of equally early blockers.", "",
            "| Rank | Case | Tractability | Next tool | Why |",
            "|---:|---|---|---|---|",
        ])
        for item in tied:
            lines.append(
                f"| {item['rank']} | `{item['case']}` | {item['tractability']} | "
                f"`{item['recommended_tool']}` | {'; '.join(item['reasons'])} |"
            )
    if show_all:
        lines.extend([
            "",
            "## All cases",
            "",
            "| Case | Clean through | First divergence | Probed through |",
            "|---|---:|---:|---:|",
        ])
        for case in frontier["cases"]:
            lines.append(
                f"| `{case['id']}` | {case['clean_through']} | "
                f"{case['first_divergence_cycle'] or '—'} | "
                f"{case['probed_through'] or '—'} |"
            )
    return "\n".join(lines) + "\n"


def frontier_command(args: argparse.Namespace) -> int:
    paths = [path.expanduser().resolve() for path in args.survey]
    frontier = build_frontier(
        [load_survey(path) for path in paths], [str(path) for path in paths]
    )
    markdown = format_frontier(frontier, args.all)
    if args.json_output is not None:
        _write_json_atomic(args.json_output.expanduser().resolve(), frontier)
    if args.markdown_output is not None:
        _write_text_atomic(args.markdown_output.expanduser().resolve(), markdown)
    print(markdown, end="")
    return 2 if frontier["counts"]["failed"] else 0


def _asset_migration_proof(baselines: list[dict[str, Any]],
        candidate: dict[str, Any]) -> dict[str, Any]:
    """Prove a replacement pack produced byte-identical measured traces.

    Asset identity normally belongs to the gate boundary.  A full-media pack
    intentionally changes that identity, so migration is allowed only for a
    single complete baseline whose fixture metadata, semantic comparison and
    trace bytes are identical case for case.  Merely reaching the same frontier
    is insufficient.
    """
    if len(baselines) != 1:
        raise ValueError(
            "asset migration requires exactly one complete baseline survey"
        )
    baseline = baselines[0]
    expected = {record["id"]: record for record in baseline["cases"]}
    actual = {record["id"]: record for record in candidate["cases"]}
    if len(expected) != 52 or set(actual) != set(expected):
        raise ValueError("asset migration requires the same complete 52-case matrix")
    semantic_fields = (
        "fixture_id", "scenario", "java_map", "cycles", "compared_cycles",
        "seed", "state_schema", "comparison_tier", "state",
        "first_divergence_cycle", "findings", "comparison_output",
    )
    traces = []
    for case_id in sorted(expected):
        before = expected[case_id]
        after = actual[case_id]
        changed = [field for field in semantic_fields
                   if before.get(field) != after.get(field)]
        if changed:
            raise ValueError(
                f"asset migration changed {case_id}: {', '.join(changed)}"
            )
        before_trace = before.get("java_trace") or {}
        after_trace = after.get("java_trace") or {}
        before_identity = {key: before_trace.get(key)
                           for key in ("bytes", "sha256")}
        after_identity = {key: after_trace.get(key)
                          for key in ("bytes", "sha256")}
        if before_identity != after_identity \
                or not isinstance(after_identity.get("sha256"), str):
            raise ValueError(f"asset migration changed trace bytes for {case_id}")
        traces.append({"case": case_id, **after_identity})
    return {
        "schema": 1,
        "method": "byte-identical-52-case-traces",
        "from": baseline.get("asset_source"),
        "to": candidate.get("asset_source"),
        "case_count": len(traces),
        "trace_manifest_sha256": _canonical_digest(traces),
    }


def _same_asset_source(left: object, right: object) -> bool:
    """Treat a content-addressed pack as identical after a machine transfer."""
    if left == right:
        return True
    if not isinstance(left, dict) or not isinstance(right, dict):
        return False
    if left.get("kind") != "chonkpack" or right.get("kind") != "chonkpack":
        return False
    return (
        isinstance(left.get("sha256"), str)
        and left.get("sha256") == right.get("sha256")
        and isinstance(left.get("bytes"), int)
        and left.get("bytes") == right.get("bytes")
    )


def evaluate_gate(baselines: list[dict[str, Any]],
        candidate: dict[str, Any], *,
        allow_asset_migration: bool = False) -> dict[str, Any]:
    """Require the candidate to preserve every baseline case proof."""
    baseline = build_frontier(baselines)
    candidate = _validate_survey(candidate, "candidate survey")
    if candidate.get("comparison_tier") != baseline.get("comparison_tier"):
        raise ValueError("candidate and baseline use different comparison tiers")
    asset_migration = None
    if not _same_asset_source(
            candidate.get("asset_source"), baseline.get("asset_source")):
        if not allow_asset_migration:
            raise ValueError("candidate and baseline use different asset sources")
        asset_migration = _asset_migration_proof(baselines, candidate)
    candidate_cases = {record["id"]: record for record in candidate["cases"]}
    issues: list[dict[str, object]] = []
    for expected in baseline["cases"]:
        required = expected["clean_through"]
        record = candidate_cases.get(expected["id"])
        if record is None:
            issues.append({
                "kind": "missing",
                "id": expected["id"],
                "required_clean_through": required,
                "message": "case is absent from the candidate survey",
            })
            continue
        if expected.get("fixture_id") is not None \
                and record.get("fixture_id") != expected.get("fixture_id"):
            issues.append({
                "kind": "fixture_mismatch",
                "id": expected["id"],
                "required_clean_through": required,
                "message": "candidate case uses a different sealed fixture",
            })
            continue
        if record.get("state") == "failed":
            issues.append({
                "kind": "failed",
                "id": expected["id"],
                "required_clean_through": required,
                "message": record.get("comparison_output", "candidate case failed"),
            })
            continue
        actual = _clean_through(record)
        if isinstance(required, int) and (actual is None or actual < required):
            divergence = record.get("first_divergence_cycle")
            kind = ("regression" if isinstance(divergence, int)
                    and divergence <= required else "coverage_gap")
            issues.append({
                "kind": kind,
                "id": expected["id"],
                "required_clean_through": required,
                "actual_clean_through": actual,
                "first_divergence_cycle": divergence,
                "message": (f"candidate proves clean only through {actual}; "
                            f"baseline requires {required}"),
            })
    return {
        "schema": 1,
        "passed": not issues,
        "baseline_common_clean_through": baseline["common_clean_through"],
        "baseline_case_count": baseline["case_count"],
        "candidate_engine": candidate.get("engine"),
        "asset_migration": asset_migration,
        "issues": issues,
    }


def format_gate(gate: dict[str, Any]) -> str:
    result = "PASS" if gate["passed"] else "FAIL"
    lines = [
        f"# BNE regression gate: {result}",
        "",
        f"Baseline cases: **{gate['baseline_case_count']}**; "
        f"common clean horizon: **{gate['baseline_common_clean_through']}**.",
    ]
    if gate["issues"]:
        lines.extend([
            "",
            "| Kind | Case | Required | Actual | Detail |",
            "|---|---|---:|---:|---|",
        ])
        for issue in gate["issues"][:20]:
            lines.append(
                f"| {issue['kind']} | `{issue['id']}` | "
                f"{issue.get('required_clean_through', '—')} | "
                f"{issue.get('actual_clean_through', '—')} | "
                f"{issue['message']} |"
            )
        if len(gate["issues"]) > 20:
            lines.append(
                f"\n{len(gate['issues']) - 20} additional issue(s) omitted; "
                "the JSON report retains every issue."
            )
    return "\n".join(lines) + "\n"


@contextmanager
def _acceptance_lock(artifact_root: Path):
    """Serialize monotonic proof-pointer promotion across concurrent agents."""
    artifact_root.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(
        artifact_root / ".acceptance.lock", os.O_CREAT | os.O_RDWR, 0o600,
    )
    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX)
        yield
    finally:
        fcntl.flock(descriptor, fcntl.LOCK_UN)
        os.close(descriptor)


def _canonical_digest(value: object) -> str:
    encoded = json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=True,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _accepted_pointer_frontier(artifact_root: Path, name: str) -> int | None:
    """Return a pointer's authenticated accepted frontier, if it has one."""
    pointer_path = artifact_root / name
    if not pointer_path.is_file():
        return None
    pointer = json.loads(pointer_path.read_text(encoding="utf-8"))
    relative = pointer.get("manifest")
    identity = pointer.get("manifest_identity")
    if not isinstance(relative, str) or not isinstance(identity, dict):
        return None
    manifest_path = (artifact_root / relative).resolve()
    if not manifest_path.is_relative_to(artifact_root) \
            or not manifest_path.is_file() \
            or file_identity(manifest_path) != identity:
        return None
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("exit_code") != 0 \
            or manifest.get("gate", {}).get("passed") is not True:
        return None
    frontier = manifest.get("frontier", {}).get("common_clean_through")
    return frontier if isinstance(frontier, int) and frontier >= 0 else None


def _record_gate_acceptance(candidate_path: Path, baseline_paths: list[Path],
        candidate: dict[str, Any], gate: dict[str, Any],
        artifact_root: Path) -> dict[str, Any]:
    """Seal a direct full-matrix gate and monotonically promote its proof."""
    from bne_triage import inventory_files, verify_manifest

    candidate_path = candidate_path.expanduser().resolve()
    baseline_paths = [path.expanduser().resolve() for path in baseline_paths]
    artifact_root = artifact_root.expanduser().resolve()
    frontier = build_frontier([candidate], [str(candidate_path)])
    compared = [record.get("compared_cycles") for record in candidate["cases"]
                if isinstance(record.get("compared_cycles"), int)]
    request = {
        "kind": "direct-gate-acceptance",
        "schema": 1,
        "engine": candidate.get("engine", {}),
        "asset_source": candidate.get("asset_source", {}),
        "comparison_tier": candidate.get("comparison_tier"),
        "candidate": file_identity(candidate_path),
        "baselines": [file_identity(path) for path in baseline_paths],
    }
    request_sha256 = _canonical_digest(request)
    runs = artifact_root / "runs"
    run_root = runs / request_sha256
    manifest_path = run_root / "manifest.json"
    eligible = (
        gate.get("passed") is True
        and gate.get("baseline_case_count") == 52
        and frontier.get("case_count") == 52
        and frontier.get("counts", {}).get("failed") == 0
        and isinstance(frontier.get("common_clean_through"), int)
    )

    with _acceptance_lock(artifact_root):
        if manifest_path.is_file():
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            verify_manifest(run_root, manifest, request_sha256)
        else:
            runs.mkdir(parents=True, exist_ok=True)
            staging = Path(tempfile.mkdtemp(
                prefix=".gate-acceptance-", dir=runs,
            ))
            try:
                inputs = staging / "inputs"
                reports = staging / "reports"
                inputs.mkdir()
                reports.mkdir()
                sealed_candidate = inputs / "candidate-survey.json"
                shutil.copy2(candidate_path, sealed_candidate)
                for index, source in enumerate(baseline_paths, 1):
                    destination = inputs / f"baseline-{index}.json"
                    shutil.copy2(source, destination)
                from bne_evidence import retain_blocker_evidence

                evidence = retain_blocker_evidence(
                    candidate, frontier, inputs, repository=ROOT,
                )
                _write_json_atomic(reports / "gate.json", gate)
                _write_text_atomic(reports / "gate.md", format_gate(gate))
                _write_json_atomic(reports / "frontier.json", frontier)
                _write_text_atomic(
                    reports / "frontier.md", format_frontier(frontier),
                )
                manifest = {
                    "schema": 1,
                    "kind": "gate-acceptance",
                    "request_sha256": request_sha256,
                    "created_at": datetime.now(timezone.utc).isoformat(),
                    "request": request,
                    "candidate": {
                        "survey": str(sealed_candidate.relative_to(staging)),
                        "identity": file_identity(sealed_candidate),
                        "counts": frontier["counts"],
                        "through": max(compared) if compared else None,
                    },
                    "gate": gate,
                    "frontier": {
                        "common_clean_through":
                            frontier["common_clean_through"],
                        "earliest_divergence_cycle":
                            frontier["earliest_divergence_cycle"],
                        "counts": frontier["counts"],
                        "tied_blockers": frontier.get("tied_blockers", []),
                    },
                    "clusters": [],
                    "packets": [],
                    # The inputs a packet needs for the cases actually holding
                    # the frontier, retained here because the survey points at
                    # a work directory the next survey overwrites.
                    "blocker_evidence": evidence,
                    "acceptance": {
                        "eligible": eligible,
                        "required_case_count": 52,
                    },
                    "exit_code": 0 if gate.get("passed") else 1,
                    "artifacts": inventory_files(staging, [inputs, reports]),
                }
                _write_json_atomic(staging / "manifest.json", manifest)
                os.replace(staging, run_root)
            finally:
                if staging.exists():
                    shutil.rmtree(staging)

        promoted = False
        if eligible:
            current = max((value for value in (
                _accepted_pointer_frontier(artifact_root, "latest-accepted.json"),
                _accepted_pointer_frontier(artifact_root, "latest.json"),
            ) if value is not None), default=-1)
            frontier_value = int(frontier["common_clean_through"])
            if frontier_value >= current:
                pointer = _triage_pointer(
                    artifact_root, run_root, manifest_path, manifest,
                )
                pointer["kind"] = "gate-acceptance"
                _write_json_atomic(
                    artifact_root / "latest-accepted.json", pointer,
                )
                promoted = True

    return {
        "run": str(run_root),
        "eligible": eligible,
        "promoted": promoted,
        "frontier": frontier.get("common_clean_through"),
        "blocker_evidence": manifest.get("blocker_evidence"),
    }


def gate_command(args: argparse.Namespace) -> int:
    baseline_paths = [path.expanduser().resolve() for path in args.baseline]
    candidate_path = args.candidate.expanduser().resolve()
    baselines = [load_survey(path) for path in baseline_paths]
    candidate = load_survey(candidate_path)
    gate = evaluate_gate(
        baselines, candidate,
        allow_asset_migration=args.allow_asset_migration,
    )
    markdown = format_gate(gate)
    if args.json_output is not None:
        _write_json_atomic(args.json_output.expanduser().resolve(), gate)
    if args.markdown_output is not None:
        _write_text_atomic(args.markdown_output.expanduser().resolve(), markdown)
    print(markdown, end="")
    receipt = _record_gate_acceptance(
        candidate_path, baseline_paths, candidate, gate, args.artifact_root,
    )
    if receipt["promoted"]:
        print(
            f"\nAccepted proof pointer promoted to h{receipt['frontier']}.\n"
            f"Durable gate receipt: {receipt['run']}"
        )
    elif receipt["eligible"]:
        print(
            "\nDurable gate receipt retained; the accepted pointer was not "
            "rolled back.\n" + f"{receipt['run']}"
        )
    else:
        print(
            "\nGate result retained but not promoted: acceptance requires a "
            "passing, failure-free 52-case matrix.\n" + f"{receipt['run']}"
        )
    evidence = receipt.get("blocker_evidence")
    if isinstance(evidence, dict):
        from bne_evidence import describe

        print("\nRetained blocker evidence:\n" + describe(evidence))
        print(
            "\nCompile the current work order with:\n"
            "  python3 tools/bne-harness/scripts/bne_java.py frontier-compile"
        )
    return 0 if gate["passed"] else 1


def packet_command(args: argparse.Namespace) -> int:
    from bne_packet import generate_packet

    packet = generate_packet(
        args.survey, args.case, args.output_dir,
        before=args.before, after=args.after, radius=args.radius,
        extra_units=args.unit, source_dir=args.source_dir,
    )
    print(
        f"BNE divergence packet: {packet['case']['id']} "
        f"@{packet['divergence']['cycle']}\n"
        f"{args.output_dir.expanduser().resolve()}"
    )
    return 0


def _native_trace_mapping(specifications: list[str]) -> dict[str, Path]:
    native_traces = {}
    for specification in specifications:
        if "=" not in specification:
            raise ValueError("--native-trace must be CASE=PATH")
        case, path = specification.split("=", 1)
        if not case or not path or case in native_traces:
            raise ValueError("--native-trace requires one unique CASE=PATH")
        native_traces[case] = Path(path).expanduser()
    return native_traces


def lab_command(args: argparse.Namespace) -> int:
    from bne_lab import build_lab

    native_traces = _native_trace_mapping(args.native_trace)
    status, run_root = build_lab(
        args.triage_run, args.artifact_root,
        executable=args.native_executable,
        native_traces=native_traces,
    )
    print((run_root / "NEXT.md").read_text(encoding="utf-8"), end="")
    print(f"\nDurable parity-lab run: {run_root}")
    return status


def rng_ledger_command(args: argparse.Namespace) -> int:
    from bne_rng_ledger import run_rng_ledger

    status, run_root = run_rng_ledger(
        args.native_trace, args.java_causal, args.artifact_root,
        stream=args.stream, case=args.case,
    )
    print((run_root / "RNG-DIFF.md").read_text(encoding="utf-8"), end="")
    print(f"\nDurable RNG ledger run: {run_root}")
    return status


def capture_plan_command(args: argparse.Namespace) -> int:
    from bne_capture_plan import run_capture_plan

    status, run_root = run_capture_plan(
        args.index, args.artifact_root, case=args.case, profile=args.profile,
        through=args.through, native_unit=args.native_unit, field=args.field,
    )
    print((run_root / "CAPTURE-PLAN.md").read_text(encoding="utf-8"), end="")
    print(f"\nDurable capture plan (dry run): {run_root}")
    return status


def evidence_index_command(args: argparse.Namespace) -> int:
    from bne_evidence_catalog import run_evidence_catalog

    requirement = {
        "case": args.case,
        "profile": args.profile,
        "fixture_id": args.fixture_id,
        "scenario": args.scenario,
        "seed": args.seed,
        "through_cycle": args.through,
        "field": args.field,
    }
    status, run_root = run_evidence_catalog(
        args.repository, args.artifact_root, requirement=requirement,
        roots=args.evidence_root or None,
    )
    print((run_root / "EVIDENCE-CATALOG.md").read_text(encoding="utf-8"),
          end="")
    print(f"\nDurable evidence catalog run: {run_root}")
    return status


def ai_decision_ledger_command(args: argparse.Namespace) -> int:
    from bne_ai_decision_ledger import compare_command

    report = compare_command(args.left, args.right)
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report["identical"] else 1


def ai_conductor_command(args: argparse.Namespace) -> int:
    """Discover or materialize authenticated remote/current-Java AI twins."""
    from bne_ai_conductor import main as conductor_main

    forwarded = [
        "--host", args.host, "--ssh", args.ssh,
        "--remote-root", args.remote_root,
        "--repository", str(args.repository),
        "--artifact-root", str(args.artifact_root),
        "--asset-pack", str(args.asset_pack),
        "--limit", str(args.limit), "--jobs", str(args.jobs),
        "--lease-timeout", str(args.lease_timeout),
    ]
    for case in args.case:
        forwarded.extend(("--case", case))
    for enabled, flag in (
            (args.materialize, "--materialize"),
            (args.all_captures, "--all-captures"),
            (args.skip_build, "--skip-build"),
            (args.gc_dry_run, "--gc-dry-run"),
            (args.validate_store, "--validate-store")):
        if enabled:
            forwarded.append(flag)
    return conductor_main(forwarded)


def projectile_ledger_command(args: argparse.Namespace) -> int:
    from bne_projectile_ledger import run_projectile_ledger

    status, run_root = run_projectile_ledger(
        args.fixture, args.artifact_root, through=args.through,
        case=args.case, java_causal=args.java_causal, survey=args.survey,
    )
    print((run_root / "PROJECTILE-LEDGER.md").read_text(encoding="utf-8"),
          end="")
    print(f"\nDurable projectile ledger run: {run_root}")
    return status


def state_machine_command(args: argparse.Namespace) -> int:
    from bne_state_machine import run_state_machine

    compare = []
    for specification in args.compare:
        if "=" not in specification:
            raise ValueError("--compare must be PACKET=SLOT")
        path, slot = specification.rsplit("=", 1)
        compare.append((Path(path).expanduser(), int(slot)))
    status, run_root = run_state_machine(
        args.packet, args.slot, args.artifact_root,
        java_causal=args.java_causal, java_unit=args.java_unit,
        compare=compare, proposal_path=args.proposed_state,
    )
    print((run_root / "STATE-MACHINE.md").read_text(encoding="utf-8"), end="")
    print(f"\nDurable state-machine run: {run_root}")
    return status


def micro_oracle_command(args: argparse.Namespace) -> int:
    from bne_micro_oracle import BNE_202_SHA256, run_micro_oracle

    # A native snapshot may only come from the pinned executable. The synthetic
    # test functions are not it and say so, rather than the check being one a
    # caller can turn off for a real capture.
    expected = None if args.synthetic else BNE_202_SHA256
    status, run_root = run_micro_oracle(
        args.snapshot, args.artifact_root, outcome_key=args.outcome,
        explore_budget=args.explore_budget,
        instruction_budget=args.instruction_budget,
        expected_executable=expected,
    )
    print((run_root / "NEXT.md").read_text(encoding="utf-8"), end="")
    print(f"\nDurable micro-oracle run: {run_root}")
    return status


def micro_oracle_plan_command(args: argparse.Namespace) -> int:
    from bne_micro_oracle import plan_from_branch_witness, remote_capture_plan

    artifact = json.loads(args.artifact.read_text(encoding="utf-8"))
    plan = plan_from_branch_witness(artifact, case=args.case)
    if not plan["supported"]:
        print(f"micro-oracle: unsupported evidence -- {plan['reason']}")
        print(f"Fallback: {plan['fallback_route']}")
        return 1
    plan["remote_capture"] = remote_capture_plan(
        plan["candidate_entry"], case=plan["case"] or "unknown",
        dry_run=not args.execute)
    print(json.dumps(plan, indent=2, sort_keys=True))
    if args.execute:
        raise ValueError(
            "this command prints the capture plan and never runs it: the "
            "oracle is shared, and the capture agent it names has never been "
            "run against retail. Run the commands above deliberately")
    return 0


def field_parity_command(args: argparse.Namespace) -> int:
    from bne_field_parity import render, run_field_parity

    result = run_field_parity(args.survey, args.cases, args.through,
                              args.cache)
    if args.json_output is not None:
        args.json_output.write_text(
            json.dumps(result, indent=2, sort_keys=True) + "\n",
            encoding="utf-8")
    print(render(result), end="")
    if not result["cases"]:
        raise ValueError(
            f"{args.survey} holds no case this could score. The survey must "
            "have been run with CHONKCRAFT_TRACE_BNE_FIELDS=1, which is what "
            "writes the JBNEFIELD columns into each case's stderr")
    return 0


def semantic_v2_command(args: argparse.Namespace) -> int:
    from bne_semantic_v2 import compare

    result = compare(args.state, args.java_trace, args.through,
                     families=(set(args.family) if args.family else None))
    rendered = json.dumps(result, indent=2, sort_keys=True) + "\n"
    if args.json_output is not None:
        args.json_output.write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    return 0 if result["status"] == "PASS" else 1


def command_matrix_command(args: argparse.Namespace) -> int:
    from bne_command_matrix import write_matrix

    plan = write_matrix(args.fixture, args.output, args.cycles,
                        args.command_cycle, args.distance)
    print(f"commanded movement corpus: {plan}")
    return 0


def ai_rank_command(args: argparse.Namespace) -> int:
    from bne_ai_rank import rank, render

    result = rank(json.loads(args.survey.read_text(encoding="utf-8")))
    if args.json_output is not None:
        args.json_output.write_text(
            json.dumps(result, indent=2, sort_keys=True) + "\n",
            encoding="utf-8")
    print(render(result), end="")
    return 0


def routes_command(args: argparse.Namespace) -> int:
    from bne_routes import render, run_routes

    slots = {int(value) for value in args.slot} if args.slot else None
    status, plans = run_routes(args.state, slots=slots, wood=args.wood)
    if args.json_output is not None:
        args.json_output.write_text(
            json.dumps(plans, indent=2, sort_keys=True) + "\n",
            encoding="utf-8")
    print(render(plans, wood=args.wood), end="")
    noun = "wood approach" if args.wood else "planned route"
    if len(plans) != 1:
        noun += "es" if args.wood else "s"
    print(f"\n{len(plans)} {noun}")
    return status


def micro_oracle_spec_command(args: argparse.Namespace) -> int:
    from bne_snapshot_capture import specification_from_branch_witness

    artifact = json.loads(args.artifact.read_text(encoding="utf-8"))
    draft = specification_from_branch_witness(artifact, case=args.case)
    text = json.dumps(draft, indent=2, sort_keys=True) + "\n"
    if args.out is not None:
        args.out.write_text(text, encoding="utf-8")
        print(f"capture specification draft: {args.out}")
        print("It is a draft. Every line under review_required is a thing "
              "this evidence cannot decide:")
        for item in draft["review_required"]:
            print(f"  - {item}")
        return 0
    print(text, end="")
    return 0


def micro_oracle_capture_command(args: argparse.Namespace) -> int:
    """Import a capture log offline and seal the snapshot it authenticates."""
    from bne_snapshot_capture import seal_capture

    specification = json.loads(args.specification.read_text(encoding="utf-8"))
    snapshot_path, manifest_path = seal_capture(
        specification, args.log, args.out,
        executable=args.executable,
        gdb_version=args.gdb_version,
        expect_pinned_executable=not args.synthetic,
    )
    print(f"sealed snapshot: {snapshot_path}")
    print(f"capture manifest: {manifest_path}")
    print("\nNext: python3 tools/bne-harness/scripts/bne_java.py micro-oracle "
          f"{snapshot_path}")
    return 0


def counterfactual_command(args: argparse.Namespace) -> int:
    from bne_counterfactual import run_counterfactual

    status, run_root = run_counterfactual(
        args.triage_run, args.case, args.artifact_root,
        through=args.through, jobs=args.jobs, maven=args.maven,
        java=args.java, skip_build=args.skip_build,
    )
    print((run_root / "NEXT.md").read_text(encoding="utf-8"), end="")
    print(f"\nDurable counterfactual run: {run_root}")
    return status


def branch_witness_command(args: argparse.Namespace) -> int:
    from bne_branch_witness import run_branch_witness

    status, run_root = run_branch_witness(
        args.triage_run, args.case, args.capture, args.artifact_root,
        control_paths=args.control_capture, source_root=args.source_root,
    )
    print((run_root / "NEXT.md").read_text(encoding="utf-8"), end="")
    print(f"\nDurable branch-witness run: {run_root}")
    return status


def decision_plan_command(args: argparse.Namespace) -> int:
    from bne_decision_miner import run_plan

    status, run_root = run_plan(
        args.witness_plan, args.fixture, args.artifact_root,
        native_slot=args.native_unit, field=args.field,
        rejected_cycle=args.rejected_cycle,
        accepted_cycle=args.accepted_cycle,
        bootstrap_capture_path=args.bootstrap_capture,
        executable=args.native_executable,
        entry_address=args.entry_address,
        focus_register=args.focus_register,
        heldout_cycle=args.heldout_cycle,
        heldout_outcome=args.heldout_outcome, r2=args.r2,
    )
    print((run_root / "NEXT.md").read_text(encoding="utf-8"), end="")
    print(f"\nDurable decision plan: {run_root}")
    return status


def decision_mine_command(args: argparse.Namespace) -> int:
    from bne_decision_miner import run_miner

    status, run_root = run_miner(
        args.plan, args.capture, args.artifact_root,
    )
    print((run_root / "NEXT.md").read_text(encoding="utf-8"), end="")
    print(f"\nDurable decision result: {run_root}")
    return status


def decision_remote_command(args: argparse.Namespace) -> int:
    from bne_decision_remote import run_remote

    status, run_root = run_remote(
        args.plan, args.artifact_root, execute=args.execute,
        host=args.host, remote_root=args.remote_root,
        source_harness=args.source_harness, bootstrap=args.bootstrap,
        phases=args.phase, ssh=args.ssh, scp=args.scp, timeout=args.timeout,
    )
    print((run_root / "REMOTE.txt").read_text(encoding="utf-8"), end="")
    print(f"\nDurable remote decision run: {run_root}")
    return status


def semantic_slice_command(args: argparse.Namespace) -> int:
    from bne_semantic_slice import run_semantic_slice

    if len(args.control_capture) != len(args.control_history):
        raise ValueError(
            "--control-capture and --control-history counts differ"
        )
    status, run_root = run_semantic_slice(
        args.plan, args.capture, args.history, args.artifact_root,
        control_pairs=zip(args.control_capture, args.control_history),
    )
    print((run_root / "NEXT.md").read_text(encoding="utf-8"), end="")
    print(f"\nDurable semantic-slice run: {run_root}")
    return status


def semantic_bridge_command(args: argparse.Namespace) -> int:
    from bne_semantic_bridge import run_bridge

    status, run_root = run_bridge(
        args.semantic_slice, args.java_trace, args.source_root,
        args.atlas, args.artifact_root,
    )
    print((run_root / "NEXT.md").read_text(encoding="utf-8"), end="")
    print(f"\nDurable semantic-bridge run: {run_root}")
    return status


def cadence_command(args: argparse.Namespace) -> int:
    from bne_cadence import run_cadence

    status, run_root = run_cadence(
        args.native_source, args.java_trace, args.artifact_root,
        native_unit=args.native_unit, java_unit=args.java_unit,
        field=args.field,
    )
    print((run_root / "NEXT.md").read_text(encoding="utf-8"), end="")
    print(f"\nDurable cadence run: {run_root}")
    return status


def test_efficacy_command(args: argparse.Namespace) -> int:
    from bne_test_efficacy import run_test_efficacy

    status, run_root = run_test_efficacy(
        args.candidate_root, args.baseline, args.test, args.artifact_root,
        module=args.module, maven=args.maven, timeout=args.timeout,
        asset_pack=args.asset_pack, source_dir=args.source_dir,
    )
    print((run_root / "NEXT.md").read_text(encoding="utf-8"), end="")
    print(f"\nDurable test-efficacy run: {run_root}")
    return status


def doctor_command(args: argparse.Namespace) -> int:
    from bne_doctor import run_doctor

    remote_host = None if args.no_remote else args.remote_host
    status, output_root = run_doctor(
        args.output_root, repository=ROOT, asset_pack=args.asset_pack,
        executable=args.native_executable,
        local_oracle_root=args.local_oracle_root, remote_host=remote_host,
        remote_root=args.remote_root, docker=args.docker, ssh=args.ssh,
        timeout=args.timeout, need=args.need,
    )
    print((output_root / "NEXT.md").read_text(encoding="utf-8"), end="")
    return status


def frontier_compile_command(args: argparse.Namespace) -> int:
    """Compile an accepted receipt into a current, routed diagnostic work order."""
    from bne_frontier import compile_evidence, format_status

    capabilities = {"native_traces": _native_trace_mapping(args.native_trace)}
    capabilities["native_traces"] = {
        case: str(path) for case, path in capabilities["native_traces"].items()
    }
    if args.watch is None:
        status = compile_evidence(
            args.accepted, artifact_root=args.artifact_root,
            output_root=args.output_root, repository=ROOT,
            before=args.before, after=args.after,
            capabilities=capabilities, corpus_index=args.corpus_index,
            asset_pack=args.asset_pack, force=args.force,
        )
        print(format_status(status))
        print(f"\ncache {status['cache']} in {status['elapsed_seconds']:.3f}s")
        print(f"{args.output_root.expanduser().resolve() / 'latest.json'}")
        return 0

    # Watch mode recompiles when the accepted pointer changes. It installs
    # nothing and is never started on this machine's behalf.
    print(f"Watching for a new accepted proof every {args.watch:.0f}s; "
          "Ctrl-C to stop. Nothing is installed.")
    previous = None
    while True:
        try:
            status = compile_evidence(
                args.accepted, artifact_root=args.artifact_root,
                output_root=args.output_root, repository=ROOT,
                before=args.before, after=args.after,
                capabilities=capabilities, corpus_index=args.corpus_index,
                asset_pack=args.asset_pack, force=False,
            )
            if status["request_sha256"] != previous:
                previous = status["request_sha256"]
                print(format_status(status), flush=True)
        except Exception as failure:
            print(f"compile failed, retrying: {failure}", flush=True)
        time.sleep(args.watch)


def identity_command(args: argparse.Namespace) -> int:
    """Show what the engine cache key covers, and what it deliberately ignores."""
    import bne_identity

    scanned = bne_identity.scan(ROOT)
    if args.list:
        for name, staged, working in scanned["inputs"]:
            print(f"{name}\t{staged}\t{working}")
        return 0
    print(json.dumps(
        {"identity": scanned["identity"], "noise": scanned["noise"]},
        indent=2, sort_keys=True,
    ))
    return 0


def capsule_command(args: argparse.Namespace) -> int:
    """Seal, authenticate or replay an accepted proof's source capsule."""
    import bne_capsule

    if args.action == "seal":
        manifest = bne_capsule.seal(ROOT, args.path)
        print(
            f"Sealed capsule {manifest['capsule_sha256']}\n"
            f"base {manifest['base_head']}, "
            f"{len(manifest['untracked'])} untracked engine input(s), "
            f"engine {manifest['engine_identity']['engine_input_sha256']}"
        )
        return 0
    if args.action == "verify":
        manifest = bne_capsule.verify(args.path)
        print(
            f"Capsule {manifest['capsule_sha256']} authenticated; base "
            f"{manifest['base_head']}, "
            f"{len(manifest['untracked'])} sealed untracked input(s)"
        )
        return 0
    result = bne_capsule.replay_identity(args.path, ROOT)
    print(json.dumps(result, indent=2, sort_keys=True))
    if not result["reproduced_exactly"]:
        print("\nThe materialized workspace is a different engine.")
        return 1
    print("\nThe capsule reproduced its sealed engine identity exactly.")
    return 0


def autopilot_command(args: argparse.Namespace) -> int:
    """Run durable triage and immediately compose its verified lab packet."""
    triage_status = _triage_command_core(args)
    return _compose_lab_handoff(args, triage_status)


def _compose_lab_handoff(args: argparse.Namespace, triage_status: int) -> int:
    """Promote the lab pointer for the exact triage request that just ran."""
    from bne_lab import build_lab
    from bne_triage import canonical_digest

    if triage_status == 2:
        return triage_status
    baseline_paths = [path.expanduser().resolve()
                      for path in args.baseline_survey]
    request_sha256 = canonical_digest(_triage_request(args, baseline_paths))
    triage_run = args.artifact_root.expanduser().resolve() \
        / "runs" / request_sha256
    lab_status, run_root = build_lab(
        triage_run,
        getattr(args, "lab_artifact_root", ROOT / ".bne-lab"),
        executable=getattr(args, "native_executable", None),
        native_traces=_native_trace_mapping(
            getattr(args, "native_trace", []),
        ),
    )
    print((run_root / "NEXT.md").read_text(encoding="utf-8"), end="")
    print(f"\nCurrent parity-lab run: {run_root}")
    return triage_status if triage_status != 0 else lab_status


@contextmanager
def _temporary_environment(updates: dict[str, str | None]):
    missing = object()
    previous = {key: os.environ.get(key, missing) for key in updates}
    try:
        for key, value in updates.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
        yield
    finally:
        for key, value in previous.items():
            if value is missing:
                os.environ.pop(key, None)
            else:
                os.environ[key] = str(value)


def _triage_request(args: argparse.Namespace,
        baseline_paths: list[Path]) -> dict[str, object]:
    wrapper = None
    if args.java_wrapper is not None:
        resolved = args.java_wrapper.expanduser().resolve()
        wrapper = {"path": str(resolved), **file_identity(resolved)}
    java_launcher = ([str(args.java_wrapper.resolve()), args.java]
                     if args.java_wrapper is not None else [args.java])
    build = {
        "skip_build": args.skip_build,
        "maven": args.maven,
    }
    if args.skip_build:
        build["compiled_classpath"] = _compiled_classpath_identity()
    else:
        build["maven_runtime"] = _command_identity([args.maven])
    return {
        "schema": 1,
        "engine": _cached_engine_identity(args),
        "asset_source": _cached_asset_source_identity(args),
        "index": {
            "path": str(args.index.expanduser().resolve()),
            **file_identity(args.index.expanduser().resolve()),
        },
        "baselines": [
            {"path": str(path), **file_identity(path)}
            for path in baseline_paths
        ],
        "runtime": {
            "source_workspace": _source_workspace_identity(None),
            "java": _command_identity(java_launcher),
            "java_wrapper": wrapper,
            "build": build,
        },
        "candidate": {
            "through": args.through,
            "cases": sorted(args.case or []),
            "allow_partial": args.allow_partial,
            "report_all": args.report_all,
            "jobs": args.jobs,
        },
        "packet": {
            "limit": args.packet_limit,
            "before": args.before,
            "after": args.after,
            "radius": args.radius,
        },
    }


def _primary_java_unit(packet: dict[str, Any]) -> int | None:
    cycle = str(packet["divergence"]["cycle"])
    focus = packet.get("semantic", {}).get(cycle, {}).get("focus", [])
    identifiers = sorted({item.get("java_id") for item in focus
                          if isinstance(item.get("java_id"), int)})
    return identifiers[0] if identifiers else None


def _triage_pointer(artifact_root: Path, run_root: Path,
        manifest_path: Path, manifest: dict[str, Any]) -> dict[str, object]:
    return {
        "schema": 1,
        "request_sha256": manifest["request_sha256"],
        "run": str(run_root.relative_to(artifact_root)),
        "manifest": str(manifest_path.relative_to(artifact_root)),
        "manifest_identity": file_identity(manifest_path),
        "common_clean_through": manifest["frontier"]["common_clean_through"],
        "earliest_divergence_cycle":
            manifest["frontier"]["earliest_divergence_cycle"],
        "gate_passed": manifest["gate"]["passed"],
    }


def triage_command(args: argparse.Namespace) -> int:
    """Run triage and, for the CLI, keep the richer lab handoff current."""
    triage_status = _triage_command_core(args)
    if getattr(args, "compose_lab", False):
        return _compose_lab_handoff(args, triage_status)
    return triage_status


def _triage_command_core(args: argparse.Namespace) -> int:
    from bne_packet import generate_packet
    from bne_triage import (
        TRIAGE_SCHEMA, canonical_digest, cluster_divergences,
        file_identity as triage_file_identity, format_clusters,
        format_triage_summary, inventory_files, verify_manifest,
    )

    validate_runtime(args)
    args.index = args.index.expanduser().resolve()
    baseline_paths = [path.expanduser().resolve()
                      for path in args.baseline_survey]
    baselines = [load_survey(path) for path in baseline_paths]
    _cached_asset_source_identity(args)
    _cached_engine_identity(args)
    # Authenticate all indexed fixtures before honoring a cached result.
    args.preloaded_cases = load_index(args.index, args.allow_partial)
    request = _triage_request(args, baseline_paths)
    request_sha256 = canonical_digest(request)
    artifact_root = args.artifact_root.expanduser().resolve()
    run_root = artifact_root / "runs" / request_sha256
    manifest_path = run_root / "manifest.json"
    if manifest_path.is_file():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        verify_manifest(run_root, manifest, request_sha256)
        pointer = _triage_pointer(
            artifact_root, run_root, manifest_path, manifest
        )
        _write_json_atomic(artifact_root / "latest.json", pointer)
        if int(manifest["exit_code"]) == 0:
            _write_json_atomic(artifact_root / "latest-accepted.json", pointer)
        print((run_root / "NEXT.md").read_text(encoding="utf-8"), end="")
        print(f"\nCache hit: {run_root}")
        return int(manifest["exit_code"])

    run_root.mkdir(parents=True, exist_ok=True)
    lock_path = run_root / "run.lock"
    try:
        descriptor = os.open(lock_path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError as error:
        raise RuntimeError(
            f"triage request is already running or has a stale lock: {lock_path}"
        ) from error
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as lock:
            json.dump({
                "pid": os.getpid(),
                "created_at": datetime.now(timezone.utc).isoformat(),
            }, lock, sort_keys=True)
            lock.write("\n")
            lock.flush()
            os.fsync(lock.fileno())

        attempts = run_root / "attempts"
        attempts.mkdir(exist_ok=True)
        prefix = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-")
        attempt = Path(tempfile.mkdtemp(prefix=prefix, dir=attempts))
        inputs = attempt / "inputs"
        inputs.mkdir()
        copied_baselines = []
        for index, source in enumerate(baseline_paths, 1):
            destination = inputs / f"baseline-{index}.json"
            shutil.copy2(source, destination)
            copied_baselines.append(destination)
        shutil.copy2(args.index, inputs / "corpus-index.json")
        _write_json_atomic(inputs / "request.json", request)

        candidate_args = copy.copy(args)
        candidate_args.record_gate_acceptance = False
        candidate_args.output_dir = attempt / "survey"
        candidate_args.baseline_survey = copied_baselines
        with _temporary_environment({
                "CHONKCRAFT_TRACE_BNE_PATH": None,
                "CHONKCRAFT_TRACE_BNE_STEP": None,
                "CHONKCRAFT_TRACE_BNE_SUBTILE": None}):
            candidate_status = survey_command(candidate_args)
        candidate_path = candidate_args.output_dir / "bne-java-survey.json"
        candidate = load_survey(candidate_path)
        gate = evaluate_gate(baselines, candidate)
        frontier = build_frontier([candidate], [str(candidate_path)])
        clusters = cluster_divergences(candidate)
        reports = attempt / "reports"
        reports.mkdir()
        _write_json_atomic(reports / "gate.json", gate)
        _write_text_atomic(reports / "gate.md", format_gate(gate))
        _write_json_atomic(reports / "clusters.json", {
            "schema": 1, "clusters": clusters,
        })
        _write_text_atomic(reports / "clusters.md", format_clusters(clusters))
        _write_json_atomic(reports / "frontier.json", frontier)
        _write_text_atomic(reports / "frontier.md", format_frontier(frontier))

        tractability = {item["case"]: item["rank"]
                        for item in frontier.get("tied_blockers", [])}
        divergent = sorted(
            (record for record in candidate["cases"]
             if record.get("state") == "divergent"),
            key=lambda record: (
                record["first_divergence_cycle"],
                tractability.get(record["id"], 1 << 30), record["id"]
            ),
        )
        packets = []
        diagnostic_failed = False
        for record in divergent[:args.packet_limit]:
            case_id = record["id"]
            cycle = record["first_divergence_cycle"]
            preliminary_dir = attempt / "preliminary" / case_id
            preliminary = generate_packet(
                candidate_path, case_id, preliminary_dir,
                before=args.before, after=0, radius=args.radius,
                source_dir=args.source_dir,
            )
            primary_java = _primary_java_unit(preliminary)
            packet_dir = attempt / "packets" / f"{case_id}-c{cycle}"
            packet_dir.parent.mkdir(parents=True, exist_ok=True)
            diagnostic_survey = None
            causal_trace = None
            if primary_java is not None:
                diagnostic_args = copy.copy(args)
                diagnostic_args.output_dir = attempt / "diagnostics" / case_id
                diagnostic_args.case = [case_id]
                diagnostic_args.jobs = 1
                diagnostic_args.through = cycle + args.after
                diagnostic_args.skip_build = True
                diagnostic_args.baseline_survey = None
                trace_unit = str(primary_java)
                causal_trace = diagnostic_args.output_dir / f"{case_id}.causal.jsonl"
                with _temporary_environment({
                        "CHONKCRAFT_TRACE_BNE_PATH": trace_unit,
                        "CHONKCRAFT_TRACE_BNE_STEP": trace_unit,
                        "CHONKCRAFT_TRACE_BNE_SUBTILE": "1",
                        "CHONKCRAFT_TRACE_BNE_CAUSAL": str(causal_trace),
                        "CHONKCRAFT_TRACE_BNE_CAUSAL_UNIT": trace_unit}):
                    diagnostic_status = survey_command(diagnostic_args)
                diagnostic_survey = (
                    diagnostic_args.output_dir / "bne-java-survey.json"
                )
                if diagnostic_status == 2:
                    diagnostic_failed = True
                    os.replace(preliminary_dir, packet_dir)
                    diagnostic_survey = None
                    causal_trace = None
                else:
                    generate_packet(
                        diagnostic_survey, case_id, packet_dir,
                        before=args.before, after=args.after,
                        radius=args.radius, source_dir=args.source_dir,
                    )
            else:
                os.replace(preliminary_dir, packet_dir)
            packets.append({
                "case": case_id,
                "cycle": cycle,
                "primary_java_unit": primary_java,
                "packet": str((packet_dir / "packet.json").relative_to(run_root)),
                "readme": str((packet_dir / "README.md").relative_to(run_root)),
                "diagnostic_survey": (
                    str(diagnostic_survey.relative_to(run_root))
                    if diagnostic_survey is not None else None
                ),
                "java_causal_trace": (
                    str(causal_trace.relative_to(run_root))
                    if causal_trace is not None and causal_trace.is_file()
                    else None
                ),
            })

        # A writer may edit the workspace while a long survey is running.
        # Recompute every request identity before publishing acceptance so a
        # mixed-revision run is retained only as an incomplete attempt.
        fresh_args = copy.copy(args)
        for attribute in ("engine_identity", "asset_source_identity"):
            if hasattr(fresh_args, attribute):
                delattr(fresh_args, attribute)
        fresh_request = _triage_request(fresh_args, baseline_paths)
        if canonical_digest(fresh_request) != request_sha256:
            _write_json_atomic(reports / "input-mutation.json", {
                "schema": 1,
                "started_request": request,
                "finished_request": fresh_request,
            })
            raise RuntimeError(
                "triage inputs changed during the run; the incomplete attempt "
                f"was preserved at {attempt}"
            )

        counts = candidate["counts"]
        if candidate_status == 2 or counts.get("failed") or diagnostic_failed:
            exit_code = 2
        elif not gate["passed"]:
            exit_code = 1
        else:
            exit_code = 0
        manifest: dict[str, Any] = {
            "schema": TRIAGE_SCHEMA,
            "request_sha256": request_sha256,
            "created_at": datetime.now(timezone.utc).isoformat(),
            "request": request,
            "attempt": str(attempt.relative_to(run_root)),
            "candidate": {
                "survey": str(candidate_path.relative_to(run_root)),
                "counts": counts,
                "through": candidate.get("through"),
                "identity": triage_file_identity(candidate_path),
            },
            "gate": gate,
            "frontier": {
                "common_clean_through": frontier["common_clean_through"],
                "earliest_divergence_cycle":
                    frontier["earliest_divergence_cycle"],
                "counts": frontier["counts"],
                "tied_blockers": frontier.get("tied_blockers", []),
            },
            "clusters": clusters,
            "packets": packets,
            "exit_code": exit_code,
            "artifacts": inventory_files(run_root, [attempt]),
        }
        summary_path = run_root / "NEXT.md"
        _write_text_atomic(summary_path, format_triage_summary(manifest))
        manifest["artifacts"][str(summary_path.relative_to(run_root))] = \
            triage_file_identity(summary_path)
        _write_json_atomic(manifest_path, manifest)
        pointer = _triage_pointer(
            artifact_root, run_root, manifest_path, manifest
        )
        _write_json_atomic(artifact_root / "latest.json", pointer)
        if exit_code == 0:
            _write_json_atomic(artifact_root / "latest-accepted.json", pointer)
        print(summary_path.read_text(encoding="utf-8"), end="")
        print(f"\nDurable triage run: {run_root}")
        return exit_code
    finally:
        lock_path.unlink(missing_ok=True)


def _default_path(environment: str, suffix: str) -> Path:
    return Path(os.environ.get(environment, str(Path.home() / suffix)))


def add_runtime_arguments(parser: argparse.ArgumentParser,
        *, include_output: bool = True) -> None:
    default_pack = os.environ.get("CHONKCRAFT_ASSET_PACK")
    parser.add_argument("--asset-pack", type=Path,
                        default=Path(default_pack) if default_pack else None,
                        help="BNE chonkpack used by the Java engine")
    default_install = os.environ.get("WC2_INSTALL_DIR")
    parser.add_argument("--install-dir", type=Path,
                        default=Path(default_install) if default_install else None,
                        help="raw Warcraft II install (fallback to --asset-pack)")
    parser.add_argument(
        "--source-dir", type=Path, default=None,
        help=("deprecated compatibility input; the native-pack Java runtime "
              "does not read ChonkCraft sources"),
    )
    if include_output:
        parser.add_argument("--output-dir", type=Path,
                            default=ROOT / "tools/bne-harness/work/java-corpus")
    default_wrapper = ROOT / "scripts/jbr/with-jbr-25.sh"
    parser.add_argument("--java-wrapper", type=Path,
                        default=default_wrapper if default_wrapper.is_file() else None)
    parser.add_argument("--java", default="java")
    parser.add_argument("--maven", default="mvn")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--report-all", action="store_true")
    parser.add_argument(
        "--semantic-v2", action="store_true",
        help="also enforce player, sub-tile unit, projectile and terrain state")
    parser.add_argument(
        "--semantic-v2-family", action="append",
        choices=("player", "unit", "projectile", "terrain"),
        help="limit semantic-v2 output/comparison to this family (repeatable)")
    parser.add_argument("--through", type=int,
                        help="run and compare only the first N fixture cycles")


def add_triage_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("index", type=Path)
    parser.add_argument("--baseline-survey", type=Path, action="append",
                        required=True,
                        help="saved proof to preserve (repeatable)")
    parser.add_argument(
        "--allow-asset-migration", action="store_true",
        help=("accept a replacement pack only when all 52 measured Java "
              "traces and semantic results are byte-identical to one baseline"),
    )
    parser.add_argument("--artifact-root", type=Path,
                        default=ROOT / ".bne-artifacts")
    parser.add_argument("--allow-partial", action="store_true")
    parser.add_argument("--case", action="append",
                        help="intentional subset; a full baseline gate will fail")
    parser.add_argument("--jobs", type=int, default=4)
    parser.add_argument("--packet-limit", type=int, default=1,
                        help="number of earliest divergent cases to diagnose")
    parser.add_argument("--before", type=int, default=4)
    parser.add_argument("--after", type=int, default=3)
    parser.add_argument("--radius", type=int, default=4)
    add_runtime_arguments(parser, include_output=False)


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    subcommands = result.add_subparsers(dest="command", required=True)

    one = subcommands.add_parser("case", help="trace and compare one fixture")
    one.add_argument("fixture", type=Path)
    add_runtime_arguments(one)
    one.set_defaults(func=case_command)

    survey = subcommands.add_parser(
        "survey", help="trace and compare every fixture in a corpus index")
    survey.add_argument("index", type=Path)
    survey.add_argument("--allow-partial", action="store_true")
    survey.add_argument("--case", action="append",
                        help="run only this indexed case (repeatable)")
    survey.add_argument("--baseline-survey", type=Path, action="append",
                        help="fail if this run regresses a saved survey proof")
    survey.add_argument(
        "--allow-asset-migration", action="store_true",
        help=("accept a replacement pack only when all 52 measured Java "
              "traces and semantic results are byte-identical to one baseline"),
    )
    survey.add_argument("--artifact-root", type=Path,
                        default=ROOT / ".bne-artifacts")
    survey.add_argument("--jobs", type=int, default=4)
    add_runtime_arguments(survey)
    survey.set_defaults(func=survey_command, record_gate_acceptance=True)

    frontier = subcommands.add_parser(
        "frontier", help="combine same-engine surveys into per-case frontiers")
    frontier.add_argument("survey", type=Path, nargs="+")
    frontier.add_argument("--all", action="store_true",
                          help="include every case in the Markdown report")
    frontier.add_argument("--json-output", type=Path)
    frontier.add_argument("--markdown-output", type=Path)
    frontier.set_defaults(func=frontier_command)

    gate = subcommands.add_parser(
        "gate", help="check a saved survey against baseline parity proofs")
    gate.add_argument("candidate", type=Path)
    gate.add_argument("--baseline", type=Path, action="append", required=True)
    gate.add_argument(
        "--allow-asset-migration", action="store_true",
        help=("accept a replacement pack only when all 52 measured Java "
              "traces and semantic results are byte-identical to one baseline"),
    )
    gate.add_argument("--artifact-root", type=Path,
                      default=ROOT / ".bne-artifacts")
    gate.add_argument("--json-output", type=Path)
    gate.add_argument("--markdown-output", type=Path)
    gate.set_defaults(func=gate_command)

    packet = subcommands.add_parser(
        "packet", help="build a forensic packet for one divergent survey case")
    packet.add_argument("survey", type=Path)
    packet.add_argument("--case", required=True)
    packet.add_argument("--output-dir", type=Path, required=True)
    packet.add_argument("--before", type=int, default=4,
                        help="cycles to include before first divergence")
    packet.add_argument("--after", type=int, default=0,
                        help="available traced cycles to include afterward")
    packet.add_argument("--radius", type=int, default=4,
                        help="nearby-unit and native-map radius")
    packet.add_argument("--unit", type=int, action="append", default=[],
                        help="additional native unit slot to include")
    packet.add_argument("--source-dir", type=Path,
                        help="ChonkCraft source path for the recommended Java rerun")
    packet.set_defaults(func=packet_command)

    lab = subcommands.add_parser(
        "lab",
        help="derive causal, experimental, synthesis, and coverage artifacts",
    )
    lab.add_argument("triage_run", type=Path,
                     help="authenticated triage run directory")
    lab.add_argument("--artifact-root", type=Path,
                     default=ROOT / ".bne-lab")
    lab.add_argument("--native-executable", type=Path,
                     help="optional pinned BNE 2.02b executable for static analysis")
    lab.add_argument(
        "--native-trace", action="append", default=[], metavar="CASE=PATH",
        help="authenticated native causal trace to twin with one case (repeatable)",
    )
    lab.set_defaults(func=lab_command)

    rng_ledger = subcommands.add_parser(
        "rng-ledger",
        help="align the native and Java random-number ledgers seed by seed",
    )
    rng_ledger.add_argument(
        "--java-causal", type=Path, required=True,
        help="Java causal JSONL from a CHONKCRAFT_TRACE_BNE_CAUSAL run",
    )
    rng_ledger.add_argument(
        "--native-trace", type=Path,
        help=("authenticated native TRACE.trace.txt; omitted, the report is "
              "the capture command instead of a verdict"),
    )
    rng_ledger.add_argument("--stream", choices=("async", "sync"),
                            default="async")
    rng_ledger.add_argument("--case", help="case name recorded in the report")
    rng_ledger.add_argument("--artifact-root", type=Path,
                            default=ROOT / ".bne-rng-ledger")
    rng_ledger.set_defaults(func=rng_ledger_command)

    capture_plan = subcommands.add_parser(
        "capture-plan",
        help="compile a diagnostic into its exact native capture recipe",
    )
    capture_plan.add_argument("--case", required=True)
    capture_plan.add_argument("--profile", required=True,
                              choices=sorted(CAPTURE_PROFILES))
    capture_plan.add_argument("--through", type=int, required=True)
    capture_plan.add_argument("--native-unit", type=int)
    capture_plan.add_argument("--field")
    capture_plan.add_argument(
        "--index", type=Path, required=True,
        help="corpus index the scenario, seed and fixture are read from")
    capture_plan.add_argument(
        "--artifact-root", type=Path, default=ROOT / ".bne-capture-plan")
    capture_plan.set_defaults(func=capture_plan_command)

    evidence_index = subcommands.add_parser(
        "evidence-index",
        help="index the authenticated native captures already on this machine",
    )
    evidence_index.add_argument(
        "--case", help="case the evidence must have captured")
    evidence_index.add_argument(
        "--profile", choices=sorted(PROFILES),
        help="diagnostic the evidence must be able to answer")
    evidence_index.add_argument("--fixture-id", help="required fixture identity")
    evidence_index.add_argument("--scenario", help="required scenario")
    evidence_index.add_argument("--seed", type=int, help="required seed")
    evidence_index.add_argument(
        "--through", type=int,
        help="cycle the evidence must cover, counting from 1")
    evidence_index.add_argument(
        "--field", help="field or decision the capture must record")
    evidence_index.add_argument(
        "--evidence-root", action="append", default=[], metavar="RELATIVE",
        help=("repository-relative root to walk; repeatable, and defaults to "
              "the configured native evidence roots"))
    evidence_index.add_argument("--repository", type=Path, default=ROOT)
    evidence_index.add_argument(
        "--artifact-root", type=Path, default=ROOT / ".bne-evidence-catalog")
    evidence_index.set_defaults(func=evidence_index_command)

    ai_decision_ledger = subcommands.add_parser(
        "ai-decision-ledger",
        help="compare normalized ai.bin decision ledgers",
    )
    ai_decision_ledger.add_argument("left", type=Path)
    ai_decision_ledger.add_argument("right", type=Path)
    ai_decision_ledger.set_defaults(func=ai_decision_ledger_command)

    ai_conductor = subcommands.add_parser(
        "ai-conductor",
        help=("discover authenticated i9beef AI captures or explicitly build "
              "content-addressed current-Java twins"),
    )
    ai_conductor.add_argument("--host", default=DEFAULT_REMOTE_HOST)
    ai_conductor.add_argument("--ssh", default="ssh")
    ai_conductor.add_argument(
        "--remote-root", default=".local/share/chonkcraft-bne-oracle")
    ai_conductor.add_argument("--repository", type=Path, default=ROOT)
    ai_conductor.add_argument(
        "--artifact-root", type=Path, default=ROOT / ".bne-ai-evidence")
    ai_conductor.add_argument(
        "--asset-pack", type=Path,
        default=(Path.home() / ".chonkcraft" / "packs" /
                 "warcraft-ii-battle-net-edition-usa.chonkpack"))
    ai_conductor.add_argument("--case", action="append", default=[])
    ai_conductor.add_argument("--limit", type=int, default=0)
    ai_conductor.add_argument("--jobs", type=int, choices=(1, 2), default=1)
    ai_conductor.add_argument("--lease-timeout", type=float, default=0.0)
    ai_conductor.add_argument("--materialize", action="store_true")
    ai_conductor.add_argument("--all-captures", action="store_true")
    ai_conductor.add_argument("--skip-build", action="store_true")
    ai_conductor.add_argument("--gc-dry-run", action="store_true")
    ai_conductor.add_argument("--validate-store", action="store_true")
    ai_conductor.set_defaults(func=ai_conductor_command)

    projectile_ledger = subcommands.add_parser(
        "projectile-ledger",
        help="time the native projectile pool slot by slot against Java",
    )
    projectile_ledger.add_argument(
        "--fixture", type=Path, required=True,
        help="sealed .bnefx bundle whose state.bin carries the pool",
    )
    projectile_ledger.add_argument(
        "--through", type=int, required=True,
        help="last cycle to read, counting from 1",
    )
    projectile_ledger.add_argument(
        "--case", help="case name recorded in the report and authenticated "
                       "against the survey when one is given",
    )
    projectile_ledger.add_argument(
        "--survey", type=Path,
        help=("survey whose corpus index must still agree with the fixture "
              "bytes; omitted, only the fixture's own identity is recorded"),
    )
    projectile_ledger.add_argument(
        "--java-causal", type=Path,
        help=("Java causal JSONL from a CHONKCRAFT_TRACE_BNE_CAUSAL run; omitted, "
              "the report states the Java evidence gap instead of a verdict"),
    )
    projectile_ledger.add_argument(
        "--artifact-root", type=Path, default=ROOT / ".bne-projectile-ledger")
    projectile_ledger.set_defaults(func=projectile_ledger_command)

    state_machine = subcommands.add_parser(
        "state-machine",
        help="recover the native state transitions around a divergence",
    )
    state_machine.add_argument("--packet", type=Path, required=True,
                               help="authenticated divergence packet")
    state_machine.add_argument("--slot", type=int, required=True,
                               help="focused native unit slot")
    state_machine.add_argument(
        "--java-causal", type=Path,
        help="Java causal JSONL carrying state.unit events for the pair",
    )
    state_machine.add_argument("--java-unit", type=int,
                               help="Java unit id inside that causal trace")
    state_machine.add_argument(
        "--compare", action="append", default=[], metavar="PACKET=SLOT",
        help="another authenticated window to test the rule against (repeatable)",
    )
    state_machine.add_argument(
        "--proposed-state", type=Path,
        help="JSON description of a Java state field to audit against evidence",
    )
    state_machine.add_argument("--artifact-root", type=Path,
                               default=ROOT / ".bne-state-machine")
    state_machine.set_defaults(func=state_machine_command)

    micro_oracle = subcommands.add_parser(
        "micro-oracle",
        help="replay one captured native decision offline and learn its rule",
    )
    micro_oracle.add_argument("snapshot", type=Path,
                              help="authenticated micro-oracle snapshot JSON")
    micro_oracle.add_argument(
        "--outcome", default="eax",
        help="what a rule predicts: eax, path, or write:0xADDRESS")
    micro_oracle.add_argument("--explore-budget", type=int, default=512)
    micro_oracle.add_argument("--instruction-budget", type=int, default=200_000)
    micro_oracle.add_argument(
        "--synthetic", action="store_true",
        help="the snapshot is a non-proprietary test function, not BNE")
    micro_oracle.add_argument("--artifact-root", type=Path,
                              default=ROOT / ".bne-micro-oracle")
    micro_oracle.set_defaults(func=micro_oracle_command)

    micro_oracle_plan = subcommands.add_parser(
        "micro-oracle-plan",
        help="say what a captured decision would need to be replayed offline",
    )
    micro_oracle_plan.add_argument("artifact", type=Path,
                                   help="completed branch-witness.json")
    micro_oracle_plan.add_argument("--case")
    micro_oracle_plan.add_argument(
        "--execute", action="store_true",
        help="attempt the capture rather than printing the plan (dry run is "
             "the default because the oracle is shared)")
    micro_oracle_plan.set_defaults(func=micro_oracle_plan_command)

    routes = subcommands.add_parser(
        "routes",
        help="read the routes native units decided on from a sealed capture")
    routes.add_argument("state", type=Path, help="a sealed *.state.bin")
    routes.add_argument("--slot", action="append",
                        help="report only this native slot (repeatable)")
    routes.add_argument(
        "--wood", action="store_true",
        help="only routes planned at a forest square, with the eight squares "
             "beside the tree and what each cost to reach")
    routes.add_argument("--json-output", type=Path)
    routes.set_defaults(func=routes_command)

    field_parity = subcommands.add_parser(
        "field-parity",
        help="score a whole survey against the captures, unit-cycle by "
             "unit-cycle")
    field_parity.add_argument(
        "survey", type=Path,
        help="a survey output directory, run with CHONKCRAFT_TRACE_BNE_FIELDS=1")
    field_parity.add_argument(
        "--cases", type=Path, required=True,
        help="the corpus cases directory holding each sealed .bnefx")
    field_parity.add_argument("--through", type=int, default=60)
    field_parity.add_argument(
        "--cache", type=Path,
        default=_default_path("BNE_STATE_CACHE",
                              ".chonkcraft/work/bne-oracle/state-cache"),
        help="where the state streams unpacked from the fixtures are kept")
    field_parity.add_argument("--json-output", type=Path)
    field_parity.set_defaults(func=field_parity_command)

    semantic_v2 = subcommands.add_parser(
        "semantic-v2",
        help="compare player, sub-tile unit, projectile and terrain state")
    semantic_v2.add_argument(
        "state", type=Path,
        help="a schema-1.1 state.bin or sealed .bnefx fixture")
    semantic_v2.add_argument(
        "java_trace", type=Path,
        help="EngineTrace output made with the semantic-v2 trace flag")
    semantic_v2.add_argument("--through", type=int)
    semantic_v2.add_argument("--family", action="append",
                             choices=("player", "unit", "projectile", "terrain"))
    semantic_v2.add_argument("--json-output", type=Path)
    semantic_v2.set_defaults(func=semantic_v2_command)

    command_matrix = subcommands.add_parser(
        "command-matrix",
        help="compile compass and refusal cases from authenticated BNE state")
    command_matrix.add_argument("fixture", type=Path)
    command_matrix.add_argument("output", type=Path)
    command_matrix.add_argument("--cycles", type=int, default=160)
    command_matrix.add_argument("--command-cycle", type=int, default=5)
    command_matrix.add_argument("--distance", type=int, default=4)
    command_matrix.set_defaults(func=command_matrix_command)

    ai_rank = subcommands.add_parser(
        "ai-rank",
        help="separate AI policy divergence from downstream combat fallout")
    ai_rank.add_argument("survey", type=Path)
    ai_rank.add_argument("--json-output", type=Path)
    ai_rank.set_defaults(func=ai_rank_command)

    micro_oracle_spec = subcommands.add_parser(
        "micro-oracle-spec",
        help="draft the capture specification one native decision needs",
    )
    micro_oracle_spec.add_argument("artifact", type=Path,
                                   help="completed branch-witness.json")
    micro_oracle_spec.add_argument("--case")
    micro_oracle_spec.add_argument("--out", type=Path)
    micro_oracle_spec.set_defaults(func=micro_oracle_spec_command)

    micro_oracle_capture = subcommands.add_parser(
        "micro-oracle-capture",
        help="import one capture log and seal the snapshot it authenticates",
    )
    micro_oracle_capture.add_argument("specification", type=Path,
                                      help="reviewed capture specification")
    micro_oracle_capture.add_argument("log", type=Path,
                                      help="the GDB capture log to import")
    micro_oracle_capture.add_argument("--out", type=Path, required=True,
                                      help="directory the sealed run is written to")
    micro_oracle_capture.add_argument(
        "--executable", type=Path,
        help="the pinned executable the capture came from")
    micro_oracle_capture.add_argument("--gdb-version", default="unknown")
    micro_oracle_capture.add_argument(
        "--synthetic", action="store_true",
        help="the capture is of a non-proprietary test function, not BNE")
    micro_oracle_capture.set_defaults(func=micro_oracle_capture_command)

    counterfactual = subcommands.add_parser(
        "counterfactual",
        help="rank bounded alternate futures for one authenticated packet",
    )
    counterfactual.add_argument("triage_run", type=Path,
                                help="authenticated triage run directory")
    counterfactual.add_argument("--case", required=True,
                                help="packet case from the triage run")
    counterfactual.add_argument("--artifact-root", type=Path,
                                default=ROOT / ".bne-counterfactual")
    counterfactual.add_argument("--through", type=int, default=30)
    counterfactual.add_argument("--jobs", type=int, default=4)
    counterfactual.add_argument("--maven", default="mvn")
    counterfactual.add_argument("--java", default="java")
    counterfactual.add_argument("--skip-build", action="store_true")
    counterfactual.set_defaults(func=counterfactual_command)

    branch_witness = subcommands.add_parser(
        "branch-witness",
        help="authenticate native writer/branch evidence for one packet",
    )
    branch_witness.add_argument("triage_run", type=Path,
                                help="authenticated triage run directory")
    branch_witness.add_argument("--case", required=True,
                                help="packet case from the triage run")
    branch_witness.add_argument("--capture", type=Path, required=True,
                                help="native branch capture JSON")
    branch_witness.add_argument(
        "--control-capture", type=Path, action="append", default=[],
        help="compatible clean/control capture for branch contrast (repeatable)",
    )
    branch_witness.add_argument("--artifact-root", type=Path,
                                default=ROOT / ".bne-branch-witness")
    branch_witness.add_argument("--source-root", type=Path, default=ROOT,
                                help="repository root for Java source ranking")
    branch_witness.set_defaults(func=branch_witness_command)

    decision_plan = subcommands.add_parser(
        "decision-plan",
        help="plan accepted/rejected native decision-function captures",
    )
    decision_plan.add_argument(
        "witness_plan", type=Path,
        help="Branch Witness plan for the rejected divergence",
    )
    decision_plan.add_argument("--fixture", type=Path, required=True,
                               help="sealed fixture containing both outcomes")
    decision_plan.add_argument("--native-unit", type=int, required=True)
    decision_plan.add_argument("--field", choices=tuple(sorted(
        ("animation_timer", "x", "y", "hp", "owner", "order",
         "next_order", "route", "movement_path", "route_index",
         "order_x", "order_y", "target")
    )), required=True)
    decision_plan.add_argument("--rejected-cycle", type=int, required=True)
    decision_plan.add_argument("--accepted-cycle", type=int, required=True)
    decision_plan.add_argument(
        "--bootstrap-capture", type=Path,
        help="accepted-write Branch Witness capture generated by the first pass",
    )
    decision_plan.add_argument("--native-executable", type=Path)
    decision_plan.add_argument("--entry-address", type=lambda value: int(value, 0))
    decision_plan.add_argument("--focus-register")
    decision_plan.add_argument("--heldout-cycle", type=int)
    decision_plan.add_argument(
        "--heldout-outcome", choices=("accepted", "rejected"),
    )
    decision_plan.add_argument("--r2", default="r2")
    decision_plan.add_argument("--artifact-root", type=Path,
                               default=ROOT / ".bne-decision-miner")
    decision_plan.set_defaults(func=decision_plan_command)

    decision_mine = subcommands.add_parser(
        "decision-mine",
        help="contrast sealed native decision visits and recover their predicate",
    )
    decision_mine.add_argument("plan", type=Path,
                               help="decision-plan.json or predicate-probe-plan.json")
    decision_mine.add_argument(
        "--capture", type=Path, action="append", required=True,
        help="sealed rejected/accepted/heldout decision capture (repeatable)",
    )
    decision_mine.add_argument("--artifact-root", type=Path,
                               default=ROOT / ".bne-decision-miner")
    decision_mine.set_defaults(func=decision_mine_command)

    decision_remote = subcommands.add_parser(
        "decision-remote",
        help="plan or execute isolated decision captures on the remote oracle",
    )
    decision_remote.add_argument("plan", type=Path)
    decision_remote.add_argument(
        "--bootstrap", action="store_true",
        help="capture the accepted writer from a bootstrap Branch Witness plan",
    )
    decision_remote.add_argument(
        "--phase", action="append", default=[],
        choices=("rejected", "accepted", "heldout"),
        help="decision phase to capture; defaults to every planned phase",
    )
    decision_remote.add_argument(
        "--execute", action="store_true",
        help="perform the isolated SSH/SCP capture; default is a dry run",
    )
    decision_remote.add_argument("--host", default=DEFAULT_REMOTE_HOST)
    decision_remote.add_argument(
        "--remote-root", default=".local/share/chonkcraft-bne-oracle",
    )
    decision_remote.add_argument("--source-harness",
                                 default="harness-branch-witness")
    decision_remote.add_argument("--ssh", default="ssh")
    decision_remote.add_argument("--scp", default="scp")
    decision_remote.add_argument("--timeout", type=float, default=300.0)
    decision_remote.add_argument("--artifact-root", type=Path,
                                 default=ROOT / ".bne-decision-miner")
    decision_remote.set_defaults(func=decision_remote_command)

    semantic_slice = subcommands.add_parser(
        "semantic-slice",
        help="recover named predicate provenance from authenticated BTS history",
    )
    semantic_slice.add_argument("plan", type=Path,
                                help="authenticated branch-witness plan")
    semantic_slice.add_argument("--capture", type=Path, required=True,
                                help="anchor branch-capture JSON")
    semantic_slice.add_argument("--history", type=Path, required=True,
                                help="anchor raw GDB instruction history")
    semantic_slice.add_argument(
        "--control-capture", type=Path, action="append", default=[],
        help="compatible held-out branch capture (repeatable)",
    )
    semantic_slice.add_argument(
        "--control-history", type=Path, action="append", default=[],
        help="raw history paired with --control-capture (repeatable)",
    )
    semantic_slice.add_argument("--artifact-root", type=Path,
                                default=ROOT / ".bne-semantic-slice")
    semantic_slice.set_defaults(func=semantic_slice_command)

    semantic_bridge = subcommands.add_parser(
        "semantic-bridge",
        help="match a proved native predicate to Java decision evidence",
    )
    semantic_bridge.add_argument(
        "semantic_slice", type=Path,
        help="proved semantic-slice.json",
    )
    semantic_bridge.add_argument(
        "--java-trace", type=Path, required=True,
        help="focused Java causal JSONL containing semantic.predicate events",
    )
    semantic_bridge.add_argument(
        "--source-root", type=Path,
        default=ROOT / "engine/src/main/java",
        help="Java source tree to rank for related decisions",
    )
    semantic_bridge.add_argument(
        "--atlas", type=Path,
        default=ROOT / "tools/bne-harness/semantic-bridge-atlas.json",
        help="reviewed cross-engine symbol atlas",
    )
    semantic_bridge.add_argument(
        "--artifact-root", type=Path,
        default=ROOT / ".bne-semantic-bridge",
    )
    semantic_bridge.set_defaults(func=semantic_bridge_command)

    cadence = subcommands.add_parser(
        "cadence",
        help="compare native and Java transition timing for one paired unit",
    )
    cadence.add_argument(
        "native_source", type=Path,
        help="sealed .bnefx fixture (preferred) or native semantic trace",
    )
    cadence.add_argument("--java-trace", type=Path, required=True)
    cadence.add_argument("--native-unit", type=int, required=True)
    cadence.add_argument(
        "--java-unit", type=int,
        help="paired Java ID; inferred from the trace when omitted",
    )
    cadence.add_argument(
        "--field", choices=(
            "position", "x", "y", "hp", "order", "removed",
            "pixel-position", "pixel-x", "pixel-y",
        ),
        default="position",
    )
    cadence.add_argument("--artifact-root", type=Path,
                         default=ROOT / ".bne-cadence")
    cadence.set_defaults(func=cadence_command)

    test_efficacy = subcommands.add_parser(
        "test-efficacy",
        help="require a regression test to fail before and pass after a fix",
    )
    test_efficacy.add_argument("--baseline", required=True,
                               help="pre-fix Git revision")
    test_efficacy.add_argument("--test", required=True,
                               help="Surefire class or class#method selector")
    test_efficacy.add_argument("--candidate-root", type=Path, default=ROOT)
    test_efficacy.add_argument("--module", default="engine")
    test_efficacy.add_argument("--maven", default="mvn")
    test_efficacy.add_argument("--timeout", type=float, default=300.0)
    test_efficacy.add_argument("--asset-pack", type=Path, default=Path(
        os.environ.get("CHONKCRAFT_ASSET_PACK", str(
            Path.home() / ".chonkcraft/work/warcraft-ii-battle-net-edition-usa.pre-full-media-2026-07-30.chonkpack"
        ))
    ))
    test_efficacy.add_argument("--source-dir", type=Path, default=ROOT,
                               help=argparse.SUPPRESS)
    test_efficacy.add_argument("--artifact-root", type=Path,
                               default=ROOT / ".bne-test-efficacy")
    test_efficacy.set_defaults(func=test_efficacy_command)

    doctor = subcommands.add_parser(
        "doctor",
        help="report local and remote parity-lab capabilities and best route",
    )
    doctor.add_argument("--asset-pack", type=Path, default=Path(
        os.environ.get("CHONKCRAFT_ASSET_PACK", str(
            Path.home() / ".chonkcraft/work/warcraft-ii-battle-net-edition-usa.pre-full-media-2026-07-30.chonkpack"
        ))
    ))
    doctor.add_argument("--native-executable", type=Path,
                        default=_default_path(
                            "BNE_NATIVE_EXECUTABLE",
                            ".chonkcraft/work/bne-oracle/Warcraft-II-BNE-2.02b.exe"))
    doctor.add_argument("--local-oracle-root", type=Path,
                        default=(Path(os.environ["BNE_ORACLE_ROOT"])
                                 if os.environ.get("BNE_ORACLE_ROOT") else None))
    doctor.add_argument("--remote-host", default=DEFAULT_REMOTE_HOST)
    doctor.add_argument("--remote-root",
                        default="$HOME/.local/share/chonkcraft-bne-oracle")
    doctor.add_argument("--no-remote", action="store_true")
    doctor.add_argument("--need", choices=("any", "fixture", "capture", "static"),
                        default="any")
    doctor.add_argument("--docker", default="docker")
    doctor.add_argument("--ssh", default="ssh")
    doctor.add_argument("--timeout", type=float, default=5.0)
    doctor.add_argument("--output-root", type=Path, default=ROOT / ".bne-doctor")
    doctor.set_defaults(func=doctor_command)

    triage = subcommands.add_parser(
        "triage",
        help=("survey, gate, packet, and compose the current parity-lab "
              "handoff"),
    )
    add_triage_arguments(triage)
    triage.add_argument("--lab-artifact-root", type=Path,
                        default=ROOT / ".bne-lab")
    triage.add_argument("--native-executable", type=Path,
                        help="pinned BNE 2.02b executable for static analysis")
    triage.add_argument(
        "--native-trace", action="append", default=[], metavar="CASE=PATH",
        help="authenticated native causal trace to twin with one case (repeatable)",
    )
    triage.set_defaults(compose_lab=True)
    triage.set_defaults(func=triage_command)

    autopilot = subcommands.add_parser(
        "autopilot",
        help="run durable triage, then compose the richer parity lab",
    )
    add_triage_arguments(autopilot)
    autopilot.add_argument("--lab-artifact-root", type=Path,
                           default=ROOT / ".bne-lab")
    autopilot.add_argument("--native-executable", type=Path,
                           help="pinned BNE 2.02b executable for static analysis")
    autopilot.add_argument(
        "--native-trace", action="append", default=[], metavar="CASE=PATH",
        help="authenticated native causal trace to twin with one case (repeatable)",
    )
    autopilot.set_defaults(func=autopilot_command)

    frontier_compile = subcommands.add_parser(
        "frontier-compile",
        help=("turn an accepted gate receipt into a current, routed "
              "diagnostic work order"),
    )
    frontier_compile.add_argument(
        "accepted", type=Path, nargs="?",
        help=("accepted pointer or gate manifest; defaults to "
              "ARTIFACT_ROOT/latest-accepted.json"),
    )
    frontier_compile.add_argument("--artifact-root", type=Path,
                                  default=ROOT / ".bne-artifacts")
    frontier_compile.add_argument("--output-root", type=Path,
                                  default=ROOT / ".bne-frontier-evidence")
    frontier_compile.add_argument("--before", type=int, default=4)
    frontier_compile.add_argument("--after", type=int, default=0)
    frontier_compile.add_argument(
        "--corpus-index", type=Path,
        help=("replacement corpus index for a receipt transferred from "
              "another machine; fixture identity is still authenticated"))
    frontier_compile.add_argument(
        "--asset-pack", type=Path,
        help=("replacement asset-pack path for a transferred receipt; its "
              "identity must match the accepted survey"))
    frontier_compile.add_argument(
        "--native-trace", action="append", default=[], metavar="CASE=PATH",
        help="authenticated native trace available for one case (repeatable)")
    frontier_compile.add_argument(
        "--force", action="store_true",
        help="recompile and republish even over a newer published frontier")
    frontier_compile.add_argument(
        "--watch", type=float, metavar="SECONDS",
        help="recompile whenever the accepted proof changes; installs nothing")
    frontier_compile.set_defaults(func=frontier_compile_command)

    identity = subcommands.add_parser(
        "identity",
        help="print the hermetic engine-input identity and the churn it ignores",
    )
    identity.add_argument("--list", action="store_true",
                          help="print the declared engine inputs instead")
    identity.set_defaults(func=identity_command)

    capsule = subcommands.add_parser(
        "capsule",
        help="seal, authenticate or replay the source an accepted proof ran on",
    )
    capsule.add_argument("action", choices=("seal", "verify", "replay"))
    capsule.add_argument("path", type=Path,
                         help="capsule directory to write, verify or replay")
    capsule.set_defaults(func=capsule_command)
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        if getattr(args, "asset_pack", None) is not None:
            args.asset_pack = args.asset_pack.expanduser()
        if getattr(args, "install_dir", None) is not None:
            args.install_dir = args.install_dir.expanduser()
        if getattr(args, "source_dir", None) is not None:
            args.source_dir = args.source_dir.expanduser()
        if getattr(args, "native_executable", None) is not None:
            args.native_executable = args.native_executable.expanduser()
        if getattr(args, "jobs", 1) <= 0:
            raise ValueError("--jobs must be positive")
        if getattr(args, "through", None) is not None and args.through <= 0:
            raise ValueError("--through must be positive")
        if getattr(args, "before", 0) < 0 or getattr(args, "after", 0) < 0:
            raise ValueError("packet cycle margins must be non-negative")
        if getattr(args, "radius", 1) < 0:
            raise ValueError("packet radius must be non-negative")
        if getattr(args, "packet_limit", 1) <= 0:
            raise ValueError("--packet-limit must be positive")
        return args.func(args)
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError,
            zipfile.BadZipFile, json.JSONDecodeError) as error:
        print(f"bne-java: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
