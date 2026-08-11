#!/usr/bin/env python3
"""Compile a diagnostic into the exact native capture recipe, without running it.

Every time a native capture is needed, the invocation gets reconstructed from
shell history. That is slow, and it is how a capture ends up run against the
wrong seed or a window that stops before the cycle in question -- the numbers
are retyped from memory rather than derived from the corpus.

This derives them. Given a case and a diagnostic profile it resolves the
authenticated fixture, scenario and seed from the corpus index, names the
supported capture command with every argument filled in, and states what the
run must produce and how it will be authenticated afterwards.

It never executes anything. There is no mode in which it does.

The recipe is `bne_headless.py`, which is the harness's own capture entry point
and builds the container invocation itself. Nothing here embeds a hand-written
Docker line, an image path or a credential, because nothing here needs to.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
import shlex
import tempfile
from typing import Any

PLAN_SCHEMA = 1

HEADLESS = "python3 tools/bne-harness/scripts/bne_headless.py"
HARNESS = "python3 tools/bne-harness/scripts/bne_java.py"

# What each profile needs from a capture, and which supported subcommand
# produces it. A profile absent from here fails explicitly rather than being
# approximated with the nearest command that happens to run.
PROFILES: dict[str, dict[str, Any]] = {
    # The tracer installs its sync-rng and async hooks unconditionally at
    # attach, so a plain run already carries the draw ledger and there is no
    # flag to ask for it. An earlier version of this table invented
    # --trace-random, which the real parser rejected.
    "async-rng": {
        "subcommand": "run",
        "needs_unit": False,
        "trace_flags": (),
        "outputs": ("TRACE.trace.txt", "TRACE.manifest.json", "STATE.state.bin"),
        "answers": "the asynchronous draw ledger, seed by seed",
        "follow_up": "rng-ledger",
    },
    "sync-rng": {
        "subcommand": "run",
        "needs_unit": False,
        "trace_flags": (),
        "outputs": ("TRACE.trace.txt", "TRACE.manifest.json", "STATE.state.bin"),
        "answers": "the synchronized draw ledger, seed by seed",
        "follow_up": "rng-ledger",
    },
    "idle-dispatch": {
        "subcommand": "run",
        "needs_unit": True,
        "trace_flags": ("--trace-internal-orders",),
        "outputs": ("TRACE.trace.txt", "TRACE.manifest.json"),
        "answers": "which idle dispatch arm a unit took on each cycle",
        "follow_up": "state-machine",
    },
    "branch-witness": {
        "subcommand": "branch-capture",
        "needs_unit": True,
        "trace_flags": (),
        "outputs": ("CASE.FIELD.branch-capture.json",
                    "CASE.FIELD.branch-capture.manifest.json"),
        "answers": "which branch the native code took at a decision",
        "follow_up": "branch-witness",
    },
    "decision-capture": {
        "subcommand": "decision-capture",
        "needs_unit": True,
        "trace_flags": (),
        "outputs": ("CASE.FIELD.decision-capture.json",
                    "CASE.FIELD.decision-capture.manifest.json"),
        "phases": ("rejected", "accepted", "heldout"),
        "answers": "the accepted and rejected sides of one native decision",
        "follow_up": "decision-plan",
    },
}


class UnsupportedProfile(ValueError):
    """Raised when no supported capture answers the requested diagnostic."""


def _write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(
                mode="w", encoding="utf-8", prefix=path.name + ".",
                suffix=".tmp", dir=path.parent, delete=False) as handle:
            temporary = Path(handle.name)
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def resolve_case(index_path: Path, case: str) -> dict[str, Any]:
    """Read one case's authenticated identity out of the corpus index.

    The scenario, seed and fixture identity come from the index rather than
    from the caller, which is the whole point: a capture run against a
    remembered seed is a capture of a different experiment.
    """
    from bne_triage import file_identity

    index_path = index_path.expanduser().resolve()
    index = json.loads(index_path.read_text(encoding="utf-8"))
    record = next((entry for entry in index.get("cases", [])
                   if entry.get("id") == case), None)
    if record is None:
        raise ValueError(f"case {case!r} is absent from {index_path}")
    relative = Path(record["fixture"]["path"])
    fixture = (index_path.parent / relative).resolve()
    if not fixture.is_relative_to(index_path.parent):
        raise ValueError(f"unsafe fixture path for {case!r}")
    resolved = {
        "case": case,
        "scenario": record.get("scenario"),
        "seed": record.get("seed"),
        "fixture_id": record.get("fixture_id"),
        "fixture_path": str(fixture),
        "fixture_indexed_identity": {
            key: record["fixture"][key] for key in ("bytes", "sha256")
        },
        "index": str(index_path),
    }
    if fixture.is_file():
        actual = file_identity(fixture)
        resolved["fixture_present"] = True
        resolved["fixture_identity_matches"] = (
            actual == resolved["fixture_indexed_identity"])
        if not resolved["fixture_identity_matches"]:
            raise ValueError(
                f"indexed fixture identity changed since sealing: {fixture}")
    else:
        resolved["fixture_present"] = False
        resolved["fixture_identity_matches"] = None
    return resolved


def _quote(value: object) -> str:
    return shlex.quote(str(value))


def build_plan(case_record: dict[str, Any], *, profile: str, through: int,
               native_unit: int | None = None, field: str | None = None,
               oracle_root: str = "$ORACLE_ROOT",
               route: dict[str, Any] | None = None) -> dict[str, Any]:
    """Compile one capture recipe. Dry run only; nothing here executes."""
    specification = PROFILES.get(profile)
    if specification is None:
        raise UnsupportedProfile(
            f"no supported capture answers the profile {profile!r}; "
            f"supported profiles are {', '.join(sorted(PROFILES))}")
    if specification["needs_unit"] and native_unit is None:
        raise ValueError(
            f"the {profile} profile needs --native-unit; it captures one "
            "unit's decisions and cannot be pointed at the whole game")

    case = case_record["case"]
    scenario = case_record["scenario"]
    seed = case_record["seed"]
    if scenario is None or seed is None:
        raise ValueError(
            f"the corpus index records no scenario or seed for {case!r}")

    base = [
        HEADLESS, specification["subcommand"],
        "--oracle-root", _quote(oracle_root),
        "--case-id", _quote(case),
        "--scenario", _quote(scenario),
        "--cycles", str(through),
        "--seed", str(seed),
    ]
    commands: list[str] = []
    if specification["subcommand"] == "run":
        arguments = list(base) + list(specification["trace_flags"])
        if native_unit is not None:
            arguments += ["--trace-unit", str(native_unit)]
        commands.append(" ".join(arguments))
    elif specification["subcommand"] == "branch-capture":
        commands.append(" ".join(
            base + ["--plan", _quote("PLAN.json"),
                    "--field", _quote(field or "order")]))
    else:
        # A decision capture records one activation phase per run, which is why
        # the sealed inventory holds accepted/rejected/heldout triples rather
        # than single files. Printing one command would leave two thirds of the
        # evidence uncaptured and the miner unable to compare the sides.
        for phase in specification["phases"]:
            commands.append(" ".join(
                base + ["--plan", _quote("PLAN.json"), "--phase", phase]))

    requires_input = []
    if oracle_root.startswith("$"):
        requires_input.append(oracle_root)
    if specification["subcommand"] != "run":
        requires_input.append("PLAN.json")

    return {
        "schema": PLAN_SCHEMA,
        "dry_run": True,
        "profile": profile,
        "answers": specification["answers"],
        "case": case_record,
        "window": {"through_cycle": through},
        "native_unit": native_unit,
        "field": field,
        "route": route,
        "capture_commands": commands,
        "requires_input": requires_input or None,
        "runtime_requirements": [
            "the container must run with networking disabled; a capture that "
            "was not offline is refused by every reader in this harness",
            "the executable must be the pinned BNE 2.02b build, and its "
            "SHA-256 is recorded into the capture manifest",
            "the tracer identity is recorded too, so a capture from a rebuilt "
            "tracer is distinguishable from one that is current",
        ],
        "expected_outputs": list(specification["outputs"]),
        "authentication_after_capture": [
            "every artifact's bytes must match the SHA-256 its manifest "
            "records",
            "the manifest must name the pinned executable",
            "the manifest must record the offline runtime",
            f"the capture must cover at least {through} cycles",
        ],
        "inspect_command": (
            f"{HARNESS} evidence-index --case {_quote(case)} "
            f"--profile {profile} --through {through}"),
        "follow_up_lane": specification["follow_up"],
    }


def format_plan(plan: dict[str, Any]) -> str:
    """Render the recipe as the Markdown someone runs from."""
    case = plan["case"]
    lines = [
        "# Native capture plan",
        "",
        "This is a dry run. Nothing here has been executed.",
        "",
        f"- Case: `{case['case']}`",
        f"- Profile: `{plan['profile']}` -- {plan['answers']}",
        f"- Through cycle: {plan['window']['through_cycle']}",
    ]
    if plan.get("native_unit") is not None:
        lines.append(f"- Native unit: {plan['native_unit']}")
    lines += [
        "",
        "## Identity, derived from the corpus index",
        "",
        f"- Scenario: `{case['scenario']}`",
        f"- Seed: `{case['seed']}`",
        f"- Fixture identity: `{case['fixture_id']}`",
        f"- Index: `{case['index']}`",
        "",
        "These are read from the sealed index, not supplied by hand. A capture "
        "run against a remembered seed is a capture of a different experiment.",
        "",
    ]
    if plan.get("route"):
        route = plan["route"]
        lines += [
            "## Route",
            "",
            f"- `{route.get('id')}` -- {route.get('description')}",
            "",
        ]
    lines += [
        "## Capture",
        "",
        "```sh",
        *plan["capture_commands"],
        "```",
        "",
    ]
    if plan.get("requires_input"):
        lines += ["Still to be supplied:", ""]
        for name in plan["requires_input"]:
            lines.append(f"- `{name}`")
        lines.append("")
    lines += ["## The run must satisfy", ""]
    for item in plan["runtime_requirements"]:
        lines.append(f"- {item}")
    lines += ["", "## It should produce", ""]
    for item in plan["expected_outputs"]:
        lines.append(f"- `{item}`")
    lines += ["", "## Then check it before believing it", ""]
    for item in plan["authentication_after_capture"]:
        lines.append(f"- {item}")
    lines += [
        "",
        "```sh",
        plan["inspect_command"],
        "```",
        "",
        "That reports `reusable` only when every identity and the cycle "
        "coverage match. Until it does, the capture has not answered the "
        "question it was run for.",
        "",
    ]
    return "\n".join(lines)


def run_capture_plan(index_path: Path, artifact_root: Path, *, case: str,
                     profile: str, through: int,
                     native_unit: int | None = None,
                     field: str | None = None,
                     oracle_root: str = "$ORACLE_ROOT",
                     route: dict[str, Any] | None = None) -> tuple[int, Path]:
    """Publish a content-addressed capture plan. Never runs the capture."""
    from bne_triage import canonical_digest, file_identity, inventory_files

    record = resolve_case(index_path, case)
    plan = build_plan(record, profile=profile, through=through,
                      native_unit=native_unit, field=field,
                      oracle_root=oracle_root, route=route)
    request = {
        "schema": PLAN_SCHEMA,
        "implementation": {
            "bne_capture_plan.py": file_identity(Path(__file__)),
        },
        "case": case,
        "profile": profile,
        "through": through,
        "native_unit": native_unit,
        "field": field,
        "fixture_id": record.get("fixture_id"),
    }
    request_sha256 = canonical_digest(request)
    artifact_root = artifact_root.expanduser().resolve()
    run_root = artifact_root / "runs" / request_sha256
    manifest_path = run_root / "manifest.json"

    if manifest_path.is_file():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if manifest.get("request_sha256") != request_sha256 \
                or canonical_digest(manifest.get("request")) != request_sha256:
            raise ValueError("cached capture plan request identity changed")
        for relative, expected in manifest["artifacts"].items():
            path = run_root / relative
            if not path.is_file() or file_identity(path) != expected:
                raise ValueError(f"capture plan artifact changed: {path}")
        _write(artifact_root / "latest.json",
               json.dumps(manifest["pointer"], indent=2, sort_keys=True) + "\n")
        return 0, run_root

    run_root.mkdir(parents=True, exist_ok=True)
    plan_path = run_root / "CAPTURE-PLAN.json"
    summary_path = run_root / "CAPTURE-PLAN.md"
    _write(plan_path, json.dumps(plan, indent=2, sort_keys=True) + "\n")
    _write(summary_path, format_plan(plan))
    pointer = {
        "schema": PLAN_SCHEMA,
        "request_sha256": request_sha256,
        "run": str(run_root.relative_to(artifact_root)),
        "case": case,
        "profile": profile,
        "dry_run": True,
    }
    manifest = {
        "schema": PLAN_SCHEMA,
        "request_sha256": request_sha256,
        "request": request,
        "exit_code": 0,
        "pointer": pointer,
        "artifacts": inventory_files(run_root, [plan_path, summary_path]),
    }
    _write(manifest_path, json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    _write(artifact_root / "latest.json",
           json.dumps(pointer, indent=2, sort_keys=True) + "\n")
    return 0, run_root
