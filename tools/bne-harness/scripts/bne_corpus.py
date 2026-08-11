#!/usr/bin/env python3
"""Build, run, resume, and validate a durable BNE oracle corpus."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from typing import Any
import zipfile

from bne_fixture import validate_fixture


PLAN_SCHEMA = 1
INDEX_SCHEMA = 1
CASE_ID = re.compile(r"[a-z0-9][a-z0-9._-]{0,79}\Z")
CAMPAIGN_FAMILIES = (
    ("human", r"Campaign\Human\Human", 14),
    ("orc", r"Campaign\Orc\Orc", 14),
    ("xhuman", r"Campaign\XHuman\2XHum", 12),
    ("xorc", r"Campaign\XOrc\2XOrc", 12),
)


def file_identity(path: Path) -> dict[str, int | str]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            size += len(block)
            digest.update(block)
    return {"bytes": size, "sha256": digest.hexdigest()}


def campaign_cases(cycles: int, seed: int) -> list[dict[str, Any]]:
    if cycles <= 0:
        raise ValueError("campaign corpus cycles must be positive")
    if seed < 0 or seed > 0xffffffff:
        raise ValueError("campaign corpus seed must fit an unsigned 32-bit value")
    return [
        {
            "id": f"retail-{family}-{mission:02d}-idle",
            "kind": "campaign",
            "scenario": f"{prefix}{mission:02d}.pud",
            "cycles": cycles,
            "seed": seed,
            "commands": None,
        }
        for family, prefix, count in CAMPAIGN_FAMILIES
        for mission in range(1, count + 1)
    ]


def campaign_plan(cycles: int, seed: int) -> dict[str, Any]:
    return {
        "schema": PLAN_SCHEMA,
        "description": (
            "Authoritative English retail BNE 2.02b baseline across all 52 "
            "built-in campaign maps"
        ),
        "cases": campaign_cases(cycles, seed),
    }


def _campaign_scenarios() -> set[str]:
    return {case["scenario"] for case in campaign_cases(1, 0)}


KNOWN_CAMPAIGN_SCENARIOS = _campaign_scenarios()


def validate_plan_data(data: Any, plan_dir: Path) -> list[dict[str, Any]]:
    if not isinstance(data, dict) or data.get("schema") != PLAN_SCHEMA:
        raise ValueError(f"corpus plan must use schema {PLAN_SCHEMA}")
    raw_cases = data.get("cases")
    if not isinstance(raw_cases, list) or not raw_cases:
        raise ValueError("corpus plan must contain at least one case")
    cases: list[dict[str, Any]] = []
    seen: set[str] = set()
    for index, raw in enumerate(raw_cases):
        if not isinstance(raw, dict):
            raise ValueError(f"corpus case {index} is not an object")
        case_id = raw.get("id")
        if not isinstance(case_id, str) or CASE_ID.fullmatch(case_id) is None:
            raise ValueError(f"corpus case {index} has an unsafe id")
        if case_id in seen:
            raise ValueError(f"duplicate corpus case id {case_id!r}")
        seen.add(case_id)
        if raw.get("kind") != "campaign":
            raise ValueError(
                f"corpus case {case_id!r} has unsupported kind {raw.get('kind')!r}"
            )
        scenario = raw.get("scenario")
        if scenario not in KNOWN_CAMPAIGN_SCENARIOS:
            raise ValueError(
                f"corpus case {case_id!r} is not a built-in campaign scenario"
            )
        cycles = raw.get("cycles")
        seed = raw.get("seed")
        if not isinstance(cycles, int) or isinstance(cycles, bool) or cycles <= 0:
            raise ValueError(f"corpus case {case_id!r} has invalid cycles")
        if (not isinstance(seed, int) or isinstance(seed, bool)
                or seed < 0 or seed > 0xffffffff):
            raise ValueError(f"corpus case {case_id!r} has invalid seed")
        command_value = raw.get("commands")
        command_path: Path | None = None
        if command_value is not None:
            if not isinstance(command_value, str) or not command_value:
                raise ValueError(f"corpus case {case_id!r} has invalid commands")
            command_path = (plan_dir / command_value).resolve()
            if not command_path.is_file():
                raise ValueError(
                    f"corpus case {case_id!r} command file is missing: "
                    f"{command_path}"
                )
        cases.append({
            "id": case_id,
            "kind": "campaign",
            "scenario": scenario,
            "cycles": cycles,
            "seed": seed,
            "commands": command_path,
        })
    return cases


def load_plan(path: Path) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    path = path.resolve()
    data = json.loads(path.read_text(encoding="utf-8"))
    return data, validate_plan_data(data, path.parent)


def _read_fixture_manifest(path: Path) -> dict[str, Any]:
    with zipfile.ZipFile(path) as archive:
        return json.loads(archive.read("manifest.json"))


def _expected_command_identity(case: dict[str, Any]) -> dict[str, int | str] | None:
    commands = case["commands"]
    return None if commands is None else file_identity(commands)


def inspect_case_fixture(path: Path, case: dict[str, Any]) -> dict[str, Any]:
    validation = validate_fixture(path)
    manifest = _read_fixture_manifest(path)
    run = manifest["run"]
    expected = {
        "scenario": case["scenario"],
        "cycles": case["cycles"],
        "seed": case["seed"],
    }
    actual = {
        "scenario": run["requested_scenario"],
        "cycles": run["cycle_limit"],
        "seed": run["initialization_seed"],
    }
    if actual != expected:
        raise ValueError(
            f"fixture for {case['id']!r} has run identity {actual}; "
            f"expected {expected}"
        )
    expected_commands = _expected_command_identity(case)
    recorded_commands = run.get("commands")
    if expected_commands is None:
        if recorded_commands is not None:
            raise ValueError(f"fixture for {case['id']!r} has unexpected commands")
    else:
        if recorded_commands is None:
            raise ValueError(f"fixture for {case['id']!r} omitted its commands")
        actual_commands = {
            key: recorded_commands["file"][key] for key in ("bytes", "sha256")
        }
        if actual_commands != expected_commands:
            raise ValueError(
                f"fixture for {case['id']!r} has a different command file"
            )
    if validation["fixture_id"] != manifest["fixture"]["id"]:
        raise ValueError(f"fixture for {case['id']!r} has inconsistent identity")
    return {
        "id": case["id"],
        "kind": case["kind"],
        "scenario": case["scenario"],
        "cycles": case["cycles"],
        "seed": case["seed"],
        "fixture_id": validation["fixture_id"],
        "fixture": file_identity(path),
        "state_schema": manifest["run"]["state"]["validation"]["schema"],
        "state_validation": manifest["run"]["state"]["validation"],
        "simulation_validation": manifest["run"]["validation"],
    }


def _write_json_atomic(path: Path, data: dict[str, Any]) -> None:
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


def _new_index(plan_path: Path) -> dict[str, Any]:
    return {
        "schema": INDEX_SCHEMA,
        "plan": {"name": plan_path.name, **file_identity(plan_path)},
        "cases": [],
    }


def _load_or_create_index(index_path: Path, plan_path: Path) -> dict[str, Any]:
    expected_plan = {"name": plan_path.name, **file_identity(plan_path)}
    if not index_path.exists():
        return _new_index(plan_path)
    data = json.loads(index_path.read_text(encoding="utf-8"))
    if data.get("schema") != INDEX_SCHEMA:
        raise ValueError(f"corpus index must use schema {INDEX_SCHEMA}")
    if data.get("plan") != expected_plan:
        raise ValueError("corpus index belongs to a different plan identity")
    if not isinstance(data.get("cases"), list):
        raise ValueError("corpus index cases must be a list")
    return data


def _case_paths(case_dir: Path, case_id: str) -> dict[str, Path]:
    return {
        "trace": case_dir / f"{case_id}.trace.txt",
        "state": case_dir / f"{case_id}.state.bin",
        "manifest": case_dir / f"{case_id}.manifest.json",
        "fixture": case_dir / f"{case_id}.bnefx",
    }


def _run_one(args: argparse.Namespace, case: dict[str, Any],
        paths: dict[str, Path]) -> None:
    oracle = Path(__file__).with_name("bne_oracle.py")
    command = [
        sys.executable, str(oracle), "run",
        "--game-dir", str(args.game_dir.resolve()),
        "--prefix", str(args.prefix.resolve()),
        "--trace", str(paths["trace"]),
        "--state", str(paths["state"]),
        "--manifest", str(paths["manifest"]),
        "--fixture", str(paths["fixture"]),
        "--source-manifest", str(args.source_manifest.resolve()),
        "--scenario", case["scenario"],
        "--seed", str(case["seed"]),
        "--cycles", str(case["cycles"]),
    ]
    if case["commands"] is not None:
        command.extend(("--commands", str(case["commands"])))
    if args.wine is not None:
        command.extend(("--wine", str(args.wine.resolve())))
    subprocess.run(command, check=True)


def make_campaign_plan(args: argparse.Namespace) -> int:
    output = args.output.resolve()
    if output.exists():
        raise ValueError(f"refusing to replace existing corpus plan: {output}")
    _write_json_atomic(output, campaign_plan(args.cycles, args.seed))
    print(f"wrote 52-case campaign plan: {output}")
    return 0


def run_corpus(args: argparse.Namespace) -> int:
    plan_path = args.plan.resolve()
    _, cases = load_plan(plan_path)
    output_dir = args.output_dir.resolve()
    case_dir = output_dir / "cases"
    case_dir.mkdir(parents=True, exist_ok=True)
    index_path = output_dir / "corpus-index.json"
    index = _load_or_create_index(index_path, plan_path)
    indexed = {record["id"]: record for record in index["cases"]}

    for ordinal, case in enumerate(cases, 1):
        paths = _case_paths(case_dir, case["id"])
        fixture = paths["fixture"]
        if fixture.exists():
            result = inspect_case_fixture(fixture, case)
            action = "validated"
        else:
            partial = [path for path in paths.values() if path.exists()]
            if partial:
                raise ValueError(
                    f"case {case['id']!r} has partial outputs and no sealed "
                    f"fixture: {[str(path) for path in partial]!r}"
                )
            print(f"[{ordinal}/{len(cases)}] running {case['id']}", flush=True)
            _run_one(args, case, paths)
            result = inspect_case_fixture(fixture, case)
            action = "captured"
        result["fixture"]["path"] = str(fixture.relative_to(output_dir))
        previous = indexed.get(case["id"])
        if previous is not None:
            previous_identity = {
                key: previous["fixture"][key] for key in ("bytes", "sha256")
            }
            current_identity = {
                key: result["fixture"][key] for key in ("bytes", "sha256")
            }
            if (previous.get("fixture_id") != result["fixture_id"]
                    or previous_identity != current_identity):
                raise ValueError(
                    f"sealed fixture for indexed case {case['id']!r} changed; "
                    "start a new corpus directory to replace frozen evidence"
                )
        indexed[case["id"]] = result
        index["cases"] = [indexed[item["id"]] for item in cases
                          if item["id"] in indexed]
        _write_json_atomic(index_path, index)
        print(
            f"[{ordinal}/{len(cases)}] {action} {case['id']} "
            f"fixture={result['fixture_id']}", flush=True
        )
    print(f"complete corpus index: {index_path}")
    return 0


def validate_index(args: argparse.Namespace) -> int:
    index_path = args.index.resolve()
    root = index_path.parent
    data = json.loads(index_path.read_text(encoding="utf-8"))
    if data.get("schema") != INDEX_SCHEMA or not isinstance(data.get("cases"), list):
        raise ValueError(f"invalid corpus index schema in {index_path}")
    fixture_ids: set[str] = set()
    total_cycles = 0
    total_bytes = 0
    for record in data["cases"]:
        relative = Path(record["fixture"]["path"])
        fixture = (root / relative).resolve()
        if not fixture.is_relative_to(root):
            raise ValueError(f"unsafe fixture path in corpus index: {relative}")
        actual_identity = file_identity(fixture)
        expected_identity = {
            key: record["fixture"][key] for key in ("bytes", "sha256")
        }
        if actual_identity != expected_identity:
            raise ValueError(f"corpus fixture identity changed: {fixture}")
        validation = validate_fixture(fixture)
        if validation["fixture_id"] != record["fixture_id"]:
            raise ValueError(f"corpus fixture ID changed: {fixture}")
        fixture_ids.add(validation["fixture_id"])
        total_cycles += validation["cycles"]
        total_bytes += actual_identity["bytes"]
    result = {
        "cases": len(data["cases"]),
        "fixture_ids": len(fixture_ids),
        "cycles": total_cycles,
        "bytes": total_bytes,
        "index": str(index_path),
    }
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    make = subparsers.add_parser(
        "make-campaign-plan", help="write a plan for all 52 retail campaigns")
    make.add_argument("--output", type=Path, required=True)
    make.add_argument("--cycles", type=int, default=1800)
    make.add_argument("--seed", type=int, default=1)
    make.set_defaults(func=make_campaign_plan)

    run = subparsers.add_parser(
        "run", help="capture or resume every case in a corpus plan")
    run.add_argument("--plan", type=Path, required=True)
    run.add_argument("--output-dir", type=Path, required=True)
    run.add_argument("--game-dir", type=Path, required=True)
    run.add_argument("--prefix", type=Path, required=True)
    run.add_argument("--source-manifest", type=Path, required=True)
    run.add_argument("--wine", type=Path)
    run.set_defaults(func=run_corpus)

    validate = subparsers.add_parser(
        "validate", help="validate every sealed fixture in a corpus index")
    validate.add_argument("--index", type=Path, required=True)
    validate.set_defaults(func=validate_index)
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    try:
        return args.func(args)
    except (OSError, ValueError, subprocess.CalledProcessError,
            zipfile.BadZipFile, json.JSONDecodeError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    raise SystemExit(main())
