#!/usr/bin/env python3
"""Reproduce the fixed player-command campaign against authenticated BNE captures.

Recipes and per-unit regression frontiers belong in Git. Licensed captures,
packaged Java builds and complete observations belong in a durable private
store. A passing gate preserves measured physical prefixes and command
acceptance; it does not certify raw orders, the entire world or physical UI.
"""

from __future__ import annotations

import argparse
from concurrent.futures import ProcessPoolExecutor
from concurrent.futures.process import BrokenProcessPool
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from typing import Any
import zipfile

import bne_fixture
import bne_corpus
import bne_identity
import bne_playtest_explorer as explorer
import bne_playtest_native_adapter as native
import bne_player_transaction as transaction


ROOT = Path(__file__).resolve().parents[3]
DEFINITION = ROOT / "tools/bne-harness/command-campaign.json"
BASELINE = ROOT / "tools/bne-harness/command-campaign-baseline.json"
SCHEMA = "chonkcraft-bne-command-campaign-1"
RESULT_SCHEMA = "chonkcraft-bne-command-campaign-result-1"
BASELINE_SCHEMA = "chonkcraft-bne-command-campaign-baseline-1"
FIELDS = ("alive", "on_map", "x", "y", "offset_x", "offset_y", "hit_points")
DIAGNOSTIC_FIELDS = ("order", "sequence", "animation_timer", "animation_state")
SAFE_ID = re.compile(r"[a-z0-9][a-z0-9-]*\Z")
SHA256 = re.compile(r"[0-9a-f]{64}\Z")


def file_sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def read_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text())
    if not isinstance(value, dict):
        raise ValueError(f"expected an object: {path}")
    return value


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", dir=path.parent,
                prefix=path.name + ".", delete=False) as stream:
            temporary = Path(stream.name)
            json.dump(value, stream, indent=2, sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def default_store() -> Path:
    configured = os.environ.get("BNE_COMMAND_STORE")
    return Path(configured).expanduser() if configured else (
        Path.home() / ".local/share/chonkcraft-command-parity")


def digest_document(document: dict[str, Any]) -> str:
    return explorer.digest({k: v for k, v in document.items() if k != "sha256"})


def load_definition(path: Path = DEFINITION) -> dict[str, Any]:
    document = read_json(path)
    if document.get("schema") != SCHEMA or document.get("sha256") != digest_document(document):
        raise ValueError("command campaign definition identity changed")
    if document.get("native_executable_sha256") != native.PINNED_BNE_EXECUTABLE_SHA256:
        raise ValueError("campaign does not use pinned BNE 2.02b")
    if not SHA256.fullmatch(str(document.get("asset_pack_sha256", ""))):
        raise ValueError("campaign has no authenticated pack identity")
    rows = document.get("cases")
    if not isinstance(rows, list) or not rows:
        raise ValueError("command campaign has no cases")
    ids = set()
    groups: dict[str, dict[str, int]] = {}
    for row in rows:
        name = row.get("id", "")
        if not SAFE_ID.fullmatch(name) or name in ids:
            raise ValueError("command campaign has an unsafe or duplicate case")
        ids.add(name)
        commands = row.get("commands")
        if not isinstance(commands, list) or not commands:
            raise ValueError(f"{name}: no commands")
        previous = 0
        for command in commands:
            cycle = command.get("issue_cycle")
            if type(cycle) is not int or cycle < previous or cycle < 2:
                raise ValueError(f"{name}: commands must follow the initial frame in cycle order")
            previous = cycle
        horizon = row.get("horizon")
        if type(horizon) is not int or horizon != previous + row.get("settle_cycles", 0):
            raise ValueError(f"{name}: horizon must include the complete final command tail")
        units = row.get("observed_units")
        if not isinstance(units, list) or not units or any(
                type(unit) is not int or not 0 <= unit < 1600 for unit in units):
            raise ValueError(f"{name}: invalid observed unit inventory")
        if units != sorted(set(units)) or not {c["unit_id"] for c in commands} <= set(units):
            raise ValueError(f"{name}: duplicate observers or unobserved commands")
        for key in ("sha256", "fixture_id", "tracer_sha256"):
            if not SHA256.fullmatch(str(row.get("native", {}).get(key, ""))):
                raise ValueError(f"{name}: missing native {key}")
        group = groups.setdefault(row["group"], {"cases": 0, "commands": 0})
        group["cases"] += 1
        group["commands"] += len(commands)
    if document.get("groups") != groups:
        raise ValueError("campaign group denominators do not match its recipes")
    return document


def fixture_path(store: Path, case: dict[str, Any]) -> Path:
    return store / "objects" / (case["native"]["sha256"] + ".bnefx")


def verify_fixture(path: Path, case: dict[str, Any], *, deep: bool) -> None:
    if not path.is_file() or file_sha256(path) != case["native"]["sha256"]:
        raise ValueError(f"{case['id']}: missing or changed native capture: {path}")
    with zipfile.ZipFile(path) as archive:
        manifest = json.loads(archive.read("manifest.json"))
        commands = native.parse_fixture_commands(archive)
    native.fixture_authority(manifest)
    if manifest["fixture"]["id"] != case["native"]["fixture_id"]:
        raise ValueError(f"{case['id']}: native fixture identity changed")
    if manifest["harness"]["tracer"]["sha256"] != case["native"]["tracer_sha256"]:
        raise ValueError(f"{case['id']}: native dispatcher producer changed")
    run = manifest["run"]
    if (run["requested_scenario"], run["initialization_seed"], run["cycle_limit"]) != (
            case["scenario"], case["seed"], case["horizon"]):
        raise ValueError(f"{case['id']}: native map, seed or horizon changed")
    if [native.command_key(c) for c in commands] != [native.command_key(c) for c in case["commands"]]:
        raise ValueError(f"{case['id']}: native command recipe changed")
    if deep:
        bne_fixture.validate_fixture(path)


def materialize_scenario(case: dict[str, Any], fixture: Path) -> dict[str, Any]:
    # Every recipe starts after cycle 1. The commanded fixture already holds
    # that authenticated initial state, so a second idle corpus is unnecessary.
    seed = explorer.enrich_seed_families(
        explorer.seed_from_fixture(fixture, cycles=case["horizon"]), fixture)
    units = sorted({a["id"] for a in seed["actors"] + seed["targets"]})
    if units != case["observed_units"]:
        raise ValueError(f"{case['id']}: reconstructed observer inventory changed")
    scenario = {
        "schema": explorer.SCENARIO_SCHEMA,
        "seed_identity": seed["identity"], "seed_sha256": explorer.digest(seed),
        "setup": seed["setup"], "pattern": case["pattern"],
        "actors": seed["actors"], "targets": seed["targets"],
        "commands": case["commands"], "settle_cycles": case["settle_cycles"],
        "combat_observation": {"unit_ids": units},
    }
    scenario["scenario_sha256"] = explorer.digest(scenario)
    explorer.validate_scenario(scenario)
    return scenario


def physical_events(result: dict[str, Any], case: dict[str, Any]) -> dict[tuple[int, int], dict]:
    """Require one typed observation for every unit on every requested cycle."""
    units = set(case["observed_units"])
    horizon = case["horizon"]
    found = {}
    for event in result.get("events", []):
        if event.get("kind") != "combat-state":
            continue
        cycle, unit = event.get("cycle"), event.get("unit_id")
        if type(cycle) is not int or type(unit) is not int or not 1 <= cycle <= horizon or unit not in units:
            raise ValueError("physical observation is outside the requested inventory")
        key = (cycle, unit)
        if key in found:
            raise ValueError("duplicate physical observation")
        for field in FIELDS:
            expected = bool if field in {"alive", "on_map"} else int
            if type(event.get(field)) is not expected:
                raise ValueError(f"physical observation omits typed field {field}")
        found[key] = event
    if len(found) != horizon * len(units):
        raise ValueError("physical observations omit a unit or cycle")
    return found


def compare_case(case: dict[str, Any], scenario: dict[str, Any],
        native_result: dict[str, Any], java_result: dict[str, Any]) -> dict[str, Any]:
    for side, result in (("native", native_result), ("java", java_result)):
        explorer.validate_result(result, scenario, side)
    if native_result["producer"].get("fixture_sha256") != case["native"]["sha256"]:
        raise ValueError("native result names a different captured fixture")
    n = physical_events(native_result, case)
    j = physical_events(java_result, case)
    frontiers = {str(unit): [case["horizon"]] * len(FIELDS) for unit in case["observed_units"]}
    diagnostic_first = {}
    for key, left in n.items():
        right = j[key]
        for index, field in enumerate(FIELDS):
            if left[field] != right[field]:
                values = frontiers[str(key[1])]
                values[index] = min(values[index], key[0] - 1)
        for field in DIAGNOSTIC_FIELDS:
            if left.get(field) != right.get(field) and (
                    field not in diagnostic_first or key[0] < diagnostic_first[field]["cycle"]):
                diagnostic_first[field] = {
                    "cycle": key[0], "unit": key[1],
                    "native": left.get(field), "java": right.get(field),
                }
    return {
        "id": case["id"], "status": "compared", "horizon": case["horizon"],
        "unit_frontiers": frontiers,
        "native_acceptance": [o["accepted"] for o in native_result["observations"]],
        "java_acceptance": [o["accepted"] for o in java_result["observations"]],
        "diagnostic_first": diagnostic_first,
        "observation_rows_per_side": len(n),
    }


def validate_row(row: dict[str, Any], case: dict[str, Any]) -> None:
    if row.get("status") != "compared" or row.get("horizon") != case["horizon"]:
        raise ValueError(f"{case['id']}: missing, failed or shortened comparison")
    frontiers = row.get("unit_frontiers", {})
    if set(frontiers) != {str(u) for u in case["observed_units"]}:
        raise ValueError(f"{case['id']}: frontier unit inventory changed")
    for values in frontiers.values():
        if not isinstance(values, list) or len(values) != len(FIELDS) or any(
                type(v) is not int or not 0 <= v <= case["horizon"] for v in values):
            raise ValueError(f"{case['id']}: invalid physical frontier")
    for key in ("native_acceptance", "java_acceptance"):
        values = row.get(key)
        if not isinstance(values, list) or len(values) != len(case["commands"]) \
                or any(type(v) is not bool for v in values):
            raise ValueError(f"{case['id']}: incomplete command acceptance")


def validate_rows(rows: list[dict], definition: dict) -> dict[str, dict]:
    if len(rows) != len(definition["cases"]) or len({r["id"] for r in rows}) != len(rows):
        raise ValueError("missing or duplicate campaign cases")
    indexed = {r["id"]: r for r in rows}
    if set(indexed) != {c["id"] for c in definition["cases"]}:
        raise ValueError("campaign case identities changed")
    for case in definition["cases"]:
        validate_row(indexed[case["id"]], case)
    return indexed


def load_baseline(path: Path, definition: dict) -> dict:
    baseline = read_json(path)
    if baseline.get("schema") != BASELINE_SCHEMA or baseline.get("sha256") != digest_document(baseline):
        raise ValueError("command baseline identity changed")
    if baseline.get("campaign_sha256") != definition["sha256"] or baseline.get("fields") != list(FIELDS):
        raise ValueError("baseline belongs to a different campaign or physical field set")
    validate_rows(baseline["cases"], definition)
    return baseline


def regression_gate(definition: dict, baseline: dict, rows: list[dict]) -> dict:
    current = validate_rows(rows, definition)
    previous = validate_rows(baseline["cases"], definition)
    regressions = []
    improvements = 0
    for case in definition["cases"]:
        now, old = current[case["id"]], previous[case["id"]]
        if now["native_acceptance"] != old["native_acceptance"]:
            raise ValueError(f"{case['id']}: the fixed native acceptance changed")
        for unit, values in now["unit_frontiers"].items():
            for field, actual, expected in zip(FIELDS, values, old["unit_frontiers"][unit], strict=True):
                if actual < expected:
                    regressions.append({"case": case["id"], "unit": int(unit), "field": field,
                                        "baseline": expected, "candidate": actual})
                improvements += actual > expected
        for index, (native_ok, java_ok, prior_ok) in enumerate(zip(
                now["native_acceptance"], now["java_acceptance"], old["java_acceptance"], strict=True)):
            if java_ok != native_ok and prior_ok == native_ok:
                regressions.append({"case": case["id"], "command": index, "field": "acceptance"})
            improvements += java_ok == native_ok and prior_ok != native_ok
    return {"passed": not regressions, "regressions": regressions,
            "improved_frontiers_or_acceptances": improvements}


def summarize(definition: dict, rows: list[dict]) -> dict:
    current = validate_rows(rows, definition)
    groups = {}
    for case in definition["cases"]:
        row = current[case["id"]]
        units = {str(c["unit_id"]) for c in case["commands"]}
        prefix = min(v for u in units for v in row["unit_frontiers"][u])
        all_prefix = min(v for values in row["unit_frontiers"].values() for v in values)
        group = groups.setdefault(case["group"], dict.fromkeys(("cases", "commands", "native_accepted",
            "java_accepted", "actor_exact_full", "all_observed_exact_full", "actor_exact_through_100",
            "actor_exact_through_600", "actor_prefix_sum"), 0))
        group["cases"] += 1
        group["commands"] += len(case["commands"])
        group["native_accepted"] += sum(row["native_acceptance"])
        group["java_accepted"] += sum(row["java_acceptance"])
        group["actor_exact_full"] += prefix == case["horizon"]
        group["all_observed_exact_full"] += all_prefix == case["horizon"]
        group["actor_exact_through_100"] += prefix >= 100
        group["actor_exact_through_600"] += prefix >= 600
        group["actor_prefix_sum"] += prefix
    return groups


def import_fixtures(args: argparse.Namespace, definition: dict) -> int:
    wanted = {c["native"]["sha256"]: c for c in definition["cases"]}
    paths = set()
    for ledger in args.ledger:
        paths.update(Path(row["fixture"]).expanduser() for row in read_json(ledger)["rows"])
    for directory in args.directory:
        paths.update(directory.expanduser().rglob("*.bnefx"))
    imported = 0
    for path in sorted(paths):
        sha = file_sha256(path)
        if sha not in wanted:
            continue
        case = wanted[sha]
        verify_fixture(path, case, deep=True)
        destination = fixture_path(args.store, case)
        destination.parent.mkdir(parents=True, exist_ok=True)
        if not destination.exists():
            with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as stream:
                temporary = Path(stream.name)
            try:
                shutil.copyfile(path, temporary)
                if file_sha256(temporary) != sha:
                    raise ValueError("native capture changed during import")
                os.replace(temporary, destination)
            finally:
                temporary.unlink(missing_ok=True)
        verify_fixture(destination, case, deep=False)
        imported += 1
    missing = [c["id"] for c in definition["cases"] if not fixture_path(args.store, c).is_file()]
    for case in definition["cases"]:
        path = fixture_path(args.store, case)
        if path.is_file():
            verify_fixture(path, case, deep=False)
    print(json.dumps({"imported": imported, "required": len(wanted), "missing": missing}, indent=2))
    return int(bool(missing))


def capture_plan(definition: dict, output: Path) -> Path:
    """Emit portable native command files; capturing remains an explicit action."""
    output.mkdir(parents=True, exist_ok=False)
    commands = output / "commands"
    commands.mkdir()
    cases = []
    for case in definition["cases"]:
        scenario = {"schema": explorer.SCENARIO_SCHEMA, "commands": case["commands"]}
        scenario["scenario_sha256"] = explorer.digest(scenario)
        name = f"commands/{case['id']}.txt"
        (output / name).write_text(explorer.native_command_script(scenario))
        cases.append({"id": case["id"], "kind": "campaign", "scenario": case["scenario"],
                      "cycles": case["horizon"], "seed": case["seed"], "commands": name})
    plan = {"schema": bne_corpus.PLAN_SCHEMA,
            "description": "Player command recipes; new captures require separate authentication and baseline review",
            "cases": cases}
    bne_corpus.validate_plan_data(plan, output)
    write_json(output / "plan.json", plan)
    return output / "plan.json"


def baseline_candidate(args: argparse.Namespace, definition: dict, baseline: dict) -> int:
    report = read_json(args.report)
    output = args.report.resolve().parent
    if report.get("schema") != RESULT_SCHEMA or report.get("campaign_sha256") != definition["sha256"]:
        raise ValueError("run belongs to a different command campaign")
    if report.get("fields") != list(FIELDS) or report.get("asset_pack_sha256") != definition["asset_pack_sha256"]:
        raise ValueError("run used different physical fields or game data")
    build = report.get("build", {})
    if transaction._java_build_inputs(ROOT) != build.get("build_inputs"):
        raise ValueError("run is stale for the current program inputs; replay before advancing the baseline")
    if file_sha256(output / "game.jar") != build["jar"]["sha256"]:
        raise ValueError("retained game differs from the tested build")
    indexed = validate_rows(report["cases"], definition)
    rows = []
    for case in definition["cases"]:
        work = output / case["id"]
        row = indexed[case["id"]]
        for name in ("scenario.json", "native.json", "java.json"):
            if file_sha256(work / name) != row.get("artifacts", {}).get(name):
                raise ValueError(f"{case['id']}: retained {name} changed")
        fixture = fixture_path(args.store, case)
        verify_fixture(fixture, case, deep=False)
        scenario = read_json(work / "scenario.json")
        if scenario != materialize_scenario(case, fixture):
            raise ValueError(f"{case['id']}: retained recipe changed")
        result = compare_case(case, scenario, read_json(work / "native.json"), read_json(work / "java.json"))
        if any(result[key] != row[key] for key in result):
            raise ValueError(f"{case['id']}: retained frontier does not match its observations")
        result.pop("diagnostic_first")
        result.pop("observation_rows_per_side")
        rows.append(result)
    gate = regression_gate(definition, baseline, rows)
    if not gate["passed"] or report.get("errors") or not report.get("passed"):
        raise ValueError("a failed or regressing run cannot advance the baseline")
    document = {"schema": BASELINE_SCHEMA, "campaign_sha256": definition["sha256"],
                "fields": list(FIELDS), "cases": rows, "source_engine": report["engine"],
                "game_sha256": build["jar"]["sha256"], "scope": baseline["scope"]}
    document["sha256"] = digest_document(document)
    if args.output.exists():
        raise ValueError("baseline candidate output must be a new file")
    write_json(args.output, document)
    print(f"Reviewable baseline candidate: {args.output}")
    return 0


def run_case(task: tuple) -> dict:
    case, output, store, pack, retained_jar, engine_sha, timeout = task
    work = output / case["id"]
    work.mkdir()
    try:
        fixture = fixture_path(store, case)
        scenario = materialize_scenario(case, fixture)
        scenario_path = work / "scenario.json"
        write_json(scenario_path, scenario)
        n = native.run_from_fixture(scenario, fixture,
            native.PINNED_BNE_EXECUTABLE_SHA256, file_sha256(Path(native.__file__)))
        write_json(work / "native.json", n)
        with (work / "java.log").open("w") as log:
            subprocess.run([str(ROOT / "scripts/jbr/with-jbr-25.sh"), "java",
                "-Djava.awt.headless=true", f"-Dchonkcraft.pack={pack}", "-cp", str(retained_jar),
                "net.chonkbase.chonkcraft.desktop.BnePlaytestAdapter",
                "--scenario", str(scenario_path), "--output", str(work / "java.json"),
                "--build-sha256", engine_sha], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                check=True, timeout=timeout)
        j = read_json(work / "java.json")
        if j["producer"]["build_sha256"] != engine_sha:
            raise ValueError("Java result identifies a different engine")
        row = compare_case(case, scenario, n, j)
        row["artifacts"] = {name: file_sha256(work / name)
                            for name in ("scenario.json", "native.json", "java.json")}
        verify_fixture(fixture, case, deep=False)
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError,
            zipfile.BadZipFile) as error:
        row = {"id": case["id"], "status": "infrastructure-failure", "error": str(error)}
    write_json(work / "comparison.json", row)
    print(f"{row['id']}: {row['status']}", flush=True)
    return row


def run_campaign(args: argparse.Namespace, definition: dict, baseline: dict) -> int:
    pack = args.asset_pack or Path(os.environ.get(
        "CHONKCRAFT_ASSET_PACK", str(transaction.DEFAULT_PACK)))
    pack = pack.expanduser().resolve()
    if not pack.is_file() or file_sha256(pack) != definition["asset_pack_sha256"]:
        raise ValueError(f"campaign requires its pinned authenticated BNE pack: {pack}")
    for case in definition["cases"]:
        verify_fixture(fixture_path(args.store, case), case, deep=False)
    output = args.output or args.store / "runs" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    output = output.expanduser().resolve()
    output.mkdir(parents=True, exist_ok=False)
    run = {"schema": RESULT_SCHEMA, "campaign_sha256": definition["sha256"],
           "baseline_sha256": baseline["sha256"], "fields": list(FIELDS), "passed": False,
           "full_parity": False, "cases": [], "errors": [], "asset_pack_sha256": file_sha256(pack)}
    write_json(output / "report.json", run)
    try:
        # One packaged build, copied before any worker starts. Target/classes
        # may contain unrelated incremental output and is never the classpath.
        jar, build = transaction._verified_app(ROOT, build=not args.skip_build)
        retained_jar = output / "game.jar"
        shutil.copy2(jar, retained_jar)
        if file_sha256(retained_jar) != build["jar"]["sha256"]:
            raise ValueError("packaged game changed while retaining the build")
        run["build"] = build
        run["engine"] = bne_identity.engine_input_identity(ROOT)
        engine_sha = run["engine"]["engine_input_sha256"]
        write_json(output / "build.json", build)

        tasks = [(case, output, args.store, pack, retained_jar, engine_sha, args.timeout)
                 for case in definition["cases"]]
        with ProcessPoolExecutor(max_workers=args.jobs) as pool:
            for row in pool.map(run_case, tasks):
                run["cases"].append(row)
                write_json(output / "report.json", run)
        if transaction._java_build_inputs(ROOT) != build["build_inputs"]:
            raise ValueError("program inputs changed during the campaign")
        if file_sha256(retained_jar) != build["jar"]["sha256"] or file_sha256(pack) != run["asset_pack_sha256"]:
            raise ValueError("game or pack changed during the campaign")
        if load_definition(args.definition)["sha256"] != definition["sha256"] \
                or load_baseline(args.baseline, definition)["sha256"] != baseline["sha256"]:
            raise ValueError("campaign definition or baseline changed during the run")
        run["gate"] = regression_gate(definition, baseline, run["cases"])
        run["groups"] = summarize(definition, run["cases"])
        run["passed"] = run["gate"]["passed"]
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError, BrokenProcessPool) as error:
        run["errors"].append(str(error))
    run["completed_at"] = datetime.now(timezone.utc).isoformat()
    write_json(output / "report.json", run)
    print(json.dumps({k: run[k] for k in ("passed", "full_parity", "errors", "groups") if k in run}, indent=2))
    print(f"Retained command campaign: {output / 'report.json'}")
    return int(not run["passed"])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--definition", type=Path, default=DEFINITION)
    parser.add_argument("--baseline", type=Path, default=BASELINE)
    parser.add_argument("--store", type=Path, default=default_store())
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("inventory", help="Validate the fixed public recipes and baseline; no media needed")
    commands.add_parser("verify", help="Verify all private captures, including their sealed state streams")
    capture = commands.add_parser("capture-plan", help="Write the native recipes without launching BNE")
    capture.add_argument("--output", type=Path, required=True)
    advance = commands.add_parser("baseline-candidate", help="Reopen a current passing run and write a baseline for review")
    advance.add_argument("--report", type=Path, required=True)
    advance.add_argument("--output", type=Path, required=True)
    importer = commands.add_parser("import-fixtures", help="Copy pinned captures to the private object store")
    importer.add_argument("--ledger", action="append", type=Path, default=[])
    importer.add_argument("--from", dest="directory", action="append", type=Path, default=[])
    runner = commands.add_parser("run", help="Build and compare every fixed case; fail on any regression")
    runner.add_argument("--asset-pack", type=Path)
    runner.add_argument("--output", type=Path, help="A new directory; defaults outside the checkout")
    runner.add_argument("--jobs", type=int, default=4)
    runner.add_argument("--timeout", type=int, default=240)
    runner.add_argument("--skip-build", action="store_true", help="Require a current authenticated build receipt")
    args = parser.parse_args()
    args.store = args.store.expanduser().resolve()
    try:
        definition = load_definition(args.definition)
        baseline = load_baseline(args.baseline, definition)
        if args.command == "inventory":
            print(json.dumps({"campaign_sha256": definition["sha256"], "groups": definition["groups"],
                              "baseline": summarize(definition, baseline["cases"])}, indent=2))
            return 0
        if args.command == "import-fixtures":
            return import_fixtures(args, definition)
        if args.command == "capture-plan":
            print(capture_plan(definition, args.output.expanduser().resolve()))
            return 0
        if args.command == "baseline-candidate":
            return baseline_candidate(args, definition, baseline)
        if args.command == "verify":
            for case in definition["cases"]:
                verify_fixture(fixture_path(args.store, case), case, deep=True)
            print(f"Verified all {len(definition['cases'])} pinned native command captures")
            return 0
        if args.jobs < 1 or args.timeout < 1:
            raise ValueError("jobs and timeout must be positive")
        return run_campaign(args, definition, baseline)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
        print(f"command campaign: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
