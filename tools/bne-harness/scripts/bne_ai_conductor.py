#!/usr/bin/env python3
"""Catalog and rank authenticated native/current-Java AI decision twins.

The remote oracle keeps the large trace and fixture.  This conductor imports
only the sealed manifest and the normalized AI ledger, then keys those bytes
by content.  A current engine-input identity gets its own Java twin so a later
checkout can never accidentally reuse an older comparison.
"""

from __future__ import annotations

import argparse
import base64
import concurrent.futures
from contextlib import contextmanager
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import subprocess
import sys
import tempfile
import time
from typing import Any, Callable, Iterable

import bne_ai_decision_ledger as decision


ROOT = Path(__file__).resolve().parents[3]
PINNED = decision.PINNED_BNE_EXECUTABLE_SHA256
PINNED_AI_BIN_SHA256 = (
    "407811faadc7bc9093b74a37fa92dd574223e6ad68e0a6bf2823f307fccfe911"
)
PINNED_AI_BIN_BYTES = 22377
CATALOG_SCHEMA = "chonkcraft-bne-ai-evidence-catalog-2"
REPORT_SCHEMA = "chonkcraft-bne-ai-conductor-report-2"
BUILD_RECEIPT_SCHEMA = "chonkcraft-bne-ai-java-build-receipt-1"
TWIN_IDENTITY_SCHEMA = "chonkcraft-bne-ai-java-twin-identity-2"
HEX256 = re.compile(r"[0-9a-f]{64}\Z")
DEFAULT_REMOTE_ROOT = ".local/share/chonkcraft-bne-oracle"
DEFAULT_STORE = ROOT / ".bne-ai-evidence"
DEFAULT_PACK = (Path.home() / ".chonkcraft" / "packs" /
                "warcraft-ii-battle-net-edition-usa.chonkpack")
FLEET_REQUIREMENTS = ROOT / "tools" / "bne-harness" / \
    "ai-fleet-requirements.json"


class EvidenceError(RuntimeError):
    """An authentication, completeness, or infrastructure failure."""


def _canonical(value: object) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=True).encode("utf-8")


def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _identity(data: bytes) -> dict[str, object]:
    return {"bytes": len(data), "sha256": _sha(data)}


def _path_identity(path: Path) -> dict[str, object]:
    """Hash one regular file without loading a pack or app JAR into memory."""
    if path.is_symlink() or not path.is_file():
        raise EvidenceError(f"proof input is not a regular file: {path}")
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            size += len(block)
            digest.update(block)
    return {"bytes": size, "sha256": digest.hexdigest()}


def _json_bytes(value: object) -> bytes:
    return json.dumps(value, indent=2, sort_keys=True).encode("utf-8") + b"\n"


def _strict_json(raw: bytes, label: str) -> dict[str, Any]:
    """Decode one proof member without accepting duplicate object keys."""
    def unique(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        value: dict[str, Any] = {}
        for key, item in pairs:
            if key in value:
                raise EvidenceError(f"duplicate JSON key in {label}: {key}")
            value[key] = item
        return value

    try:
        value = json.loads(raw, object_pairs_hook=unique)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise EvidenceError(f"invalid JSON in {label}: {exc}") from exc
    if not isinstance(value, dict):
        raise EvidenceError(f"proof member is not a JSON object: {label}")
    return value


def _proof_member(path: Path, parent: Path, *, canonical: bool = True) \
        -> tuple[dict[str, Any], bytes]:
    """Read one regular, directly-parented proof member fail closed."""
    if path.parent != parent or path.is_symlink() or not path.is_file():
        raise EvidenceError(f"missing or unsafe retained AI proof member: {path}")
    raw = path.read_bytes()
    value = _strict_json(raw, str(path))
    if canonical and raw != _json_bytes(value):
        raise EvidenceError(f"retained AI proof member is not canonical: {path}")
    return value, raw


def _write_if_changed(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists() or path.read_bytes() != data:
        path.write_bytes(data)


def _write_immutable(path: Path, data: bytes) -> None:
    """Seal one content-addressed member; conflicting bytes are proof debt."""
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        if path.is_symlink() or path.read_bytes() != data:
            raise EvidenceError(
                f"content-addressed AI evidence changed in place: {path}")
        return
    with tempfile.NamedTemporaryFile(
            prefix=path.name + ".", suffix=".tmp", dir=path.parent,
            delete=False) as handle:
        temporary = Path(handle.name)
        handle.write(data)
        handle.flush()
        os.fsync(handle.fileno())
    try:
        # Another identical writer may have won between create and link.
        try:
            os.link(temporary, path)
        except FileExistsError:
            if path.is_symlink() or path.read_bytes() != data:
                raise EvidenceError(
                    f"content-addressed AI evidence raced with different bytes: {path}")
    finally:
        temporary.unlink(missing_ok=True)


def _validated_remote_root(value: str) -> str:
    """Return a safe POSIX oracle root for emitted shell plans."""
    if not value or value.startswith("~") or "\\" in value:
        raise EvidenceError("remote oracle root must be a POSIX path")
    parts = value.removeprefix("/").split("/")
    if any(not part or part in (".", "..") for part in parts):
        raise EvidenceError("remote oracle root contains an unsafe path component")
    if any(re.fullmatch(r"[A-Za-z0-9._-]+", part) is None for part in parts):
        raise EvidenceError("remote oracle root contains shell-unsafe characters")
    return value


@dataclass(frozen=True)
class RemoteArtifact:
    path: str
    manifest_bytes: bytes
    manifest: dict[str, Any]

    @property
    def case_id(self) -> str:
        return Path(self.path).name.removesuffix(".manifest.json")

    @property
    def cycles(self) -> int:
        return int(self.manifest["run"]["cycle_limit"])

    @property
    def fixture_id(self) -> str:
        return str(self.manifest["fixture"]["id"])

    @property
    def scenario(self) -> str:
        # The Win32 loader reports a case-normalized .PUD on some captures;
        # the requested path is the stable corpus key used by the Java map.
        return str(self.manifest["run"]["requested_scenario"])


DISCOVER_SOURCE = r'''
import base64, json, pathlib, sys
root = pathlib.Path(sys.argv[1]).expanduser().resolve()
base = root / "output" / "ai-cycle"
for path in sorted(base.glob("**/*.manifest.json")):
    resolved = path.resolve()
    try:
        resolved.relative_to(base.resolve())
    except ValueError:
        raise ValueError("manifest path escapes the AI-cycle evidence root")
    raw = resolved.read_bytes()
    print(json.dumps({"path": str(resolved),
                      "manifest_b64": base64.b64encode(raw).decode("ascii")},
                     sort_keys=True))
'''


class SshBackend:
    """Read-only SSH transport; remote commands receive one safely quoted string."""

    def __init__(self, host: str, executable: str = "ssh") -> None:
        self.host = host
        self.executable = executable

    def python(self, source: str, *args: str,
               stdin: bytes | None = None) -> bytes:
        remote = shlex.join(["python3", "-c", source, *args])
        completed = subprocess.run(
            [self.executable, "-o", "BatchMode=yes", self.host, remote],
            input=stdin, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            check=False,
        )
        if completed.returncode:
            message = completed.stderr.decode("utf-8", "replace").strip()
            raise EvidenceError(
                f"read-only SSH command failed ({completed.returncode}): {message}")
        return completed.stdout


def validate_manifest(raw: bytes, remote_path: str) -> dict[str, Any]:
    try:
        value = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise EvidenceError(f"invalid manifest JSON at {remote_path}: {exc}") from exc
    if value.get("schema") != 2:
        raise EvidenceError(f"unsupported manifest schema at {remote_path}")
    executable = value.get("oracle", {}).get("executable", {}).get("sha256")
    fixture_executable = value.get("fixture", {}).get("key", {}).get(
        "oracle_executable")
    if executable != PINNED or fixture_executable != PINNED:
        raise EvidenceError(f"manifest is not pinned BNE 2.02b: {remote_path}")
    fixture_id = value.get("fixture", {}).get("id")
    if not isinstance(fixture_id, str) or HEX256.fullmatch(fixture_id) is None:
        raise EvidenceError(f"invalid fixture identity at {remote_path}")
    if _sha(_canonical(value.get("fixture", {}).get("key"))) != fixture_id:
        raise EvidenceError(f"fixture key does not produce its identity: {remote_path}")
    run = value.get("run", {})
    key = value.get("fixture", {}).get("key", {})
    validation = run.get("validation", {})
    try:
        cycles = int(run["cycle_limit"])
        seed = int(run["initialization_seed"])
        trace = run["trace"]
        state = run["state"]
        trace_name = trace["name"]
        trace_bytes = int(trace["bytes"])
        trace_sha = trace["sha256"]
        state_name = state["name"]
        state_bytes = int(state["bytes"])
        state_sha = state["sha256"]
        scenario = validation["scenario"]
    except (KeyError, TypeError, ValueError) as exc:
        raise EvidenceError(f"incomplete manifest at {remote_path}: {exc}") from exc
    requested = run.get("requested_scenario")
    if cycles < 1 or int(validation.get("cycles", -1)) != cycles \
            or int(key.get("cycle_limit", -1)) != cycles:
        raise EvidenceError(f"cycle identity disagreement at {remote_path}")
    if int(validation.get("initialization_seed", -1)) != seed \
            or int(key.get("initialization_seed", -1)) != seed:
        raise EvidenceError(f"seed identity disagreement at {remote_path}")
    scenarios = (scenario, requested, key.get("scenario"))
    if any(not isinstance(item, str) for item in scenarios) \
            or len({str(item).replace("/", "\\").casefold()
                    for item in scenarios}) != 1:
        raise EvidenceError(f"scenario identity disagreement at {remote_path}")
    if not isinstance(trace_name, str) or Path(trace_name).name != trace_name \
            or trace_bytes < 1 or not isinstance(trace_sha, str) \
            or HEX256.fullmatch(trace_sha) is None:
        raise EvidenceError(f"invalid trace identity at {remote_path}")
    state_validation = state.get("validation", {})
    if not isinstance(state_name, str) or Path(state_name).name != state_name \
            or state_bytes < 1 or not isinstance(state_sha, str) \
            or HEX256.fullmatch(state_sha) is None \
            or state_validation.get("schema") != "1.1" \
            or int(state_validation.get("cycles", -1)) != cycles \
            or int(state_validation.get("player_count", -1)) != 16:
        raise EvidenceError(f"invalid state identity at {remote_path}")
    oracle_data = value.get("oracle", {}).get("data", {})
    if key.get("oracle_data") != {
            name: item.get("sha256") for name, item in oracle_data.items()}:
        raise EvidenceError(f"oracle data identity disagreement at {remote_path}")
    if key.get("tracer") != value.get("harness", {}).get(
            "tracer", {}).get("sha256") \
            or key.get("state_schema") != state_validation.get("schema") \
            or key.get("simulation") != validation.get("simulation_sha256"):
        raise EvidenceError(f"harness/simulation identity disagreement at {remote_path}")
    if run.get("commands") is not None or run.get("replay") is not None \
            or key.get("commands") is not None or key.get("replay") is not None \
            or int(validation.get("commands_applied", -1)) != 0 \
            or int(validation.get("commands_rejected", -1)) != 0:
        raise EvidenceError(f"AI fleet evidence is not an idle capture: {remote_path}")
    if value.get("runtime", {}).get("network_disabled") is not True:
        raise EvidenceError(f"AI fleet evidence was not captured offline: {remote_path}")
    return value


def discover_remote(backend: SshBackend,
                    remote_root: str = DEFAULT_REMOTE_ROOT) \
        -> list[RemoteArtifact]:
    remote_root = _validated_remote_root(remote_root)
    output = backend.python(DISCOVER_SOURCE, remote_root)
    artifacts: list[RemoteArtifact] = []
    for number, line in enumerate(output.splitlines(), 1):
        if not line.strip():
            continue
        try:
            item = json.loads(line)
            raw = base64.b64decode(item["manifest_b64"], validate=True)
            path = str(item["path"])
        except (KeyError, ValueError, json.JSONDecodeError) as exc:
            raise EvidenceError(f"bad discovery row {number}: {exc}") from exc
        artifacts.append(RemoteArtifact(
            path=path, manifest_bytes=raw,
            manifest=validate_manifest(raw, path),
        ))
    if not artifacts:
        raise EvidenceError("remote AI-cycle catalog contains no manifests")
    return artifacts


def select_strongest(artifacts: Iterable[RemoteArtifact], *,
                     cases: Iterable[str] = (), limit: int = 0,
                     all_captures: bool = False) -> list[RemoteArtifact]:
    needles = tuple(cases)
    selected = [item for item in artifacts
                if not needles or any(needle in item.case_id for needle in needles)]
    if not all_captures:
        strongest: dict[tuple[str, int], RemoteArtifact] = {}
        for item in selected:
            key = (item.scenario.replace("/", "\\").casefold(),
                   int(item.manifest["run"]["initialization_seed"]))
            old = strongest.get(key)
            if old is None or (item.cycles, item.path) > (old.cycles, old.path):
                strongest[key] = item
        selected = list(strongest.values())
    selected.sort(key=lambda item: (-item.cycles, item.scenario, item.path))
    return selected[:limit] if limit > 0 else selected


def load_fleet_requirements(path: Path = FLEET_REQUIREMENTS) \
        -> list[dict[str, Any]]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if value.get("schema") != "chonkcraft-bne-ai-fleet-requirements-1" \
            or value.get("authority_sha256") != PINNED:
        raise EvidenceError("AI fleet requirements have the wrong schema/authority")
    rows = value.get("required_scenarios")
    if not isinstance(rows, list) or len(rows) != 52:
        raise EvidenceError("AI fleet must define exactly 52 campaign scenarios")
    ids, scenarios = set(), set()
    for row in rows:
        try:
            identity = str(row["id"])
            scenario = str(row["native_scenario"])
            java_map = str(row["java_map"])
            seed, cycles = int(row["seed"]), int(row["cycles"])
        except (KeyError, TypeError, ValueError) as exc:
            raise EvidenceError(f"invalid AI fleet requirement: {exc}") from exc
        if identity in ids or scenario.casefold() in scenarios \
                or seed != 1 or cycles != 1800 or not java_map:
            raise EvidenceError(f"invalid/duplicate AI fleet row: {identity}")
        ids.add(identity)
        scenarios.add(scenario.casefold())
    expected = []
    for family, count, native_dir, native_prefix, java_dir, java_suffix in (
            ("human", 14, "Human", "Human", "human", "h"),
            ("orc", 14, "Orc", "Orc", "orc", "o"),
            ("xhuman", 12, "XHuman", "2XHum", "human-exp", "h"),
            ("xorc", 12, "XOrc", "2XOrc", "orc-exp", "o")):
        for mission in range(1, count + 1):
            expansion = family.startswith("x")
            expected.append({
                "id": f"{family}-{mission:02d}",
                "native_scenario": (
                    f"Campaign\\{native_dir}\\{native_prefix}{mission:02d}.pud"),
                "java_map": (
                    f"campaigns/{java_dir}/level"
                    f"{'x' if expansion else ''}{mission:02d}{java_suffix}"),
                "seed": 1, "cycles": 1800,
            })
    if rows != expected:
        raise EvidenceError(
            "AI fleet requirements differ from the canonical 52-campaign matrix")
    return rows


def _capture_command(requirement: dict[str, Any], *, host: str,
                     remote_root: str) -> str:
    remote_root = _validated_remote_root(remote_root)
    case_id = f"ai-fleet-{requirement['id']}-1800"
    # Every plan row takes the same remote advisory lease. Operators may feed
    # at most two commands to a queue, but only one oracle capture can own the
    # lease and Docker workload at a time.
    root_assignment = (
        f"root={shlex.quote(remote_root)}; " if remote_root.startswith("/")
        else f"root=\"$HOME\"/{shlex.quote(remote_root)}; "
    )
    script = (
        root_assignment +
        "exec 9>\"$root/.ai-cycle-capture.lock\"; "
        "flock -w 7200 9 || exit 75; "
        "python3 \"$root/harness/scripts/bne_headless.py\" run "
        f"--oracle-root \"$root\" --case-id {shlex.quote(case_id)} "
        f"--output {shlex.quote('ai-cycle/' + case_id)} "
        f"--scenario {shlex.quote(str(requirement['native_scenario']))} "
        "--cycles 1800 --seed 1 --trace-ai-build-state"
    )
    return shlex.join(["ssh", host, script])


def fleet_plan(artifacts: Iterable[RemoteArtifact], *, host: str,
               remote_root: str, store: Path | None = None,
               materialized_runs: Iterable[dict[str, Any]] | None = None,
               requirements_path: Path = FLEET_REQUIREMENTS) \
        -> dict[str, Any]:
    requirements = load_fleet_requirements(requirements_path)
    strongest: dict[str, RemoteArtifact] = {}
    for artifact in artifacts:
        key = artifact.scenario.replace("/", "\\").casefold()
        seed = int(artifact.manifest["run"]["initialization_seed"])
        if seed != 1 or artifact.cycles < 1800:
            continue
        old = strongest.get(key)
        if old is None or (artifact.cycles, artifact.path) > (old.cycles, old.path):
            strongest[key] = artifact
    materialized: dict[str, list[int]] = {}
    if materialized_runs is not None:
        for run in materialized_runs:
            if int(run.get("cycles", 0)) >= 1800:
                materialized[str(run["scenario"]).casefold()] = [
                    int(player) for player in run.get("native_players", [])]
    # A detached NEXT.json is never evidence.  Discovery callers that want
    # materialized status must first pass runs through the retained-store
    # validator and provide them explicitly.
    rows = []
    for requirement in requirements:
        key = str(requirement["native_scenario"]).casefold()
        found = strongest.get(key)
        players = materialized.get(key)
        materialized_players = players if players is not None else None
        rows.append({
            **requirement,
            "status": "existing" if found is not None else "missing",
            "remote_manifest": found.path if found is not None else None,
            "fixture_id": found.fixture_id if found is not None else None,
            "active_computer_players": materialized_players,
            "materialized": materialized_players is not None,
            "player_cycle_denominator": (
                len(players) * int(requirement["cycles"])
                if players is not None else None),
            "capture_job": (None if found is not None else _capture_command(
                requirement, host=host, remote_root=remote_root)),
        })
    existing = sum(row["status"] == "existing" for row in rows)
    return {
        "schema": "chonkcraft-bne-ai-fleet-plan-1",
        "authority_sha256": PINNED,
        "required": len(rows), "existing": existing,
        "missing": len(rows) - existing,
        "denominator_contract": "active computer players x cycles 1..1800",
        "execution": {
            "automatic": False, "recommended_parallel_jobs": 1,
            "maximum_parallel_jobs": 2,
            "remote_lease": f"$HOME/{remote_root}/.ai-cycle-capture.lock",
            "unrelated_containers": "never inspected, stopped, or removed",
        },
        "scenarios": rows,
        "capture_jobs": [row["capture_job"] for row in rows
                         if row["capture_job"] is not None],
    }


def render_fleet_plan(plan: dict[str, Any]) -> str:
    lines = [
        "# BNE AI fleet plan", "",
        f"- Required scenarios: **{plan['required']}**",
        f"- Existing authenticated 1,800-cycle captures: **{plan['existing']}**",
        f"- Missing captures: **{plan['missing']}**", "",
        "Denominator: state-declared computer players × cycles 1..1800. The "
        "controller roster is populated only after authenticated state.bin "
        "and the native ledger are materialized together.",
        "", "## Missing capture jobs", "",
    ]
    if not plan["capture_jobs"]:
        lines.append("None.")
    for row in plan["scenarios"]:
        if row["status"] == "missing":
            lines.extend((f"### {row['id']}", "", "```sh",
                          str(row["capture_job"]), "```", ""))
    lines.extend((
        "The commands are plans, not automatically executed. Every command "
        "takes the same remote lease; use one worker normally and never more "
        "than two queued jobs. No command kills or removes containers.", ""))
    return "\n".join(lines)


def _normalizer_source() -> tuple[str, str]:
    path = Path(decision.__file__).resolve()
    raw = path.read_bytes()
    text = raw.decode("utf-8")
    marker = '\nif __name__ == "__main__":'
    if marker not in text:
        raise EvidenceError("AI ledger normalizer has no guarded entry point")
    library = text.split(marker, 1)[0]
    fixture_path = path.with_name("bne_fixture.py")
    fixture_raw = fixture_path.read_bytes()
    fixture_text = fixture_raw.decode("utf-8")
    fixture_library = (fixture_text.split(marker, 1)[0]
                       if marker in fixture_text else fixture_text)
    fixture_library = fixture_library.replace(
        "from __future__ import annotations\n", "")
    tail = r'''
import base64 as _base64, hashlib as _hashlib, io as _io, json as _json
import pathlib as _pathlib, struct as _struct, sys as _sys

def _read_identity(path, expected_sha, expected_bytes, label):
    raw = path.read_bytes()
    if len(raw) != expected_bytes or _hashlib.sha256(raw).hexdigest() != expected_sha:
        raise ValueError("remote " + label + " identity does not match its manifest")
    return raw

def _computer_roster(raw, expected_cycles):
    header = _struct.Struct("<8sHHIIIII")
    chunk = _struct.Struct("<4sI")
    cycle_header = _struct.Struct("<IIII")
    player_record = _struct.Struct("<IIII")
    if len(raw) < header.size:
        raise ValueError("remote state is shorter than its header")
    magic, major, minor, header_bytes, unit_bytes, unit_limit, players, flags = \
        header.unpack_from(raw)
    if (magic, major, minor, header_bytes, unit_bytes, unit_limit, players, flags) != \
            (b"BNESTATE", 1, 1, header.size, 152, 1600, 16, 15):
        raise ValueError("remote state is not the pinned BNESTATE 1.1 layout")
    cursor = header.size
    seen_cycles = []
    rosters = []
    done = None
    while cursor < len(raw):
        if cursor + chunk.size > len(raw):
            raise ValueError("remote state has a truncated chunk header")
        tag, size = chunk.unpack_from(raw, cursor)
        cursor += chunk.size
        end = cursor + size
        if end > len(raw):
            raise ValueError("remote state has a truncated chunk")
        payload = raw[cursor:end]
        cursor = end
        if tag == b"CYCL":
            minimum = cycle_header.size + players * player_record.size
            if len(payload) < minimum:
                raise ValueError("remote state cycle has no controller table")
            cycle = cycle_header.unpack_from(payload)[0]
            controllers = [player_record.unpack_from(
                payload, cycle_header.size + player * player_record.size)[0]
                for player in range(players)]
            seen_cycles.append(cycle)
            # The retail AI state table and tracer cover gameplay slots 0..7.
            rosters.append(tuple(player for player in range(8)
                                 if controllers[player] == 1))
        elif tag == b"DONE":
            if len(payload) != 4:
                raise ValueError("remote state DONE chunk has the wrong size")
            done = _struct.unpack("<I", payload)[0]
        elif tag != b"AUXL":
            raise ValueError("remote state contains an unknown chunk")
    wanted = list(range(1, expected_cycles + 1))
    if seen_cycles != wanted or done != expected_cycles:
        raise ValueError("remote state does not cover the declared cycle window")
    if not rosters or not rosters[0]:
        raise ValueError("remote state declares no active computer players")
    if any(roster != rosters[0] for roster in rosters[1:]):
        raise ValueError("remote computer controller roster changes inside the fixed window")
    return list(rosters[0])

manifest_path = _pathlib.Path(_sys.argv[1]).resolve()
manifest_raw = _read_identity(
    manifest_path, _sys.argv[2], int(_sys.argv[3]), "manifest")
trace_name, trace_sha, trace_bytes = _sys.argv[4], _sys.argv[5], int(_sys.argv[6])
state_name, state_sha, state_bytes = _sys.argv[7], _sys.argv[8], int(_sys.argv[9])
expected_cycles, normalizer_sha = int(_sys.argv[10]), _sys.argv[11]
declared_state_validation = _json.loads(
    _base64.b64decode(_sys.argv[12], validate=True))
if _pathlib.Path(trace_name).name != trace_name:
    raise ValueError("trace name escapes its sealed run")
trace_path = (manifest_path.parent / trace_name).resolve()
if trace_path.parent != manifest_path.parent:
    raise ValueError("trace path escapes its sealed run")
trace_raw = _read_identity(trace_path, trace_sha, trace_bytes, "trace")
if _pathlib.Path(state_name).name != state_name:
    raise ValueError("state name escapes its sealed run")
state_path = (manifest_path.parent / state_name).resolve()
if state_path.parent != manifest_path.parent:
    raise ValueError("state path escapes its sealed run")
state_raw = _read_identity(state_path, state_sha, state_bytes, "state")
actual_state_validation = validate_state_source(
    _io.BytesIO(state_raw), expected_cycles)
if actual_state_validation != declared_state_validation:
    raise ValueError("remote state validation differs from its manifest")
computer_players = _computer_roster(state_raw, expected_cycles)
ai_bytes = _sys.stdin.buffer.read()
if not ai_bytes:
    raise ValueError("authenticated ai.bin bytes were not supplied")
trace_text = trace_raw.decode("utf-8")
base = derive_ai_base(trace_text, ai_bytes)
built = ledger_from_native_trace(trace_text, ai_base=base,
        ai_size=len(ai_bytes), ai_bin=ai_bytes, state_raw=state_raw)
built["ai_bin_sha256"] = _hashlib.sha256(ai_bytes).hexdigest()
built["ai_bin_bytes"] = len(ai_bytes)
print(_json.dumps({"ledger": built,
      "manifest_identity": {"sha256": _hashlib.sha256(manifest_raw).hexdigest(),
                            "bytes": len(manifest_raw)},
      "trace_identity": {"sha256": trace_sha, "bytes": trace_bytes},
      "state_identity": {"sha256": state_sha, "bytes": state_bytes},
      "computer_players": computer_players,
      "normalizer_sha256": normalizer_sha}, sort_keys=True))
'''
    source = library + "\n" + fixture_library + tail
    return source, _sha(raw + b"\0" + fixture_raw)


def normalize_remote(backend: SshBackend, artifact: RemoteArtifact,
                     ai_bin: bytes) -> tuple[dict[str, Any], dict[str, Any]]:
    source, normalizer_sha = _normalizer_source()
    trace = artifact.manifest["run"]["trace"]
    state = artifact.manifest["run"]["state"]
    output = backend.python(
        source, artifact.path,
        _sha(artifact.manifest_bytes), str(len(artifact.manifest_bytes)),
        str(trace["name"]), str(trace["sha256"]), str(trace["bytes"]),
        str(state["name"]), str(state["sha256"]), str(state["bytes"]),
        str(artifact.cycles), normalizer_sha,
        base64.b64encode(_canonical(state["validation"])).decode("ascii"),
        stdin=ai_bin,
    )
    try:
        envelope = json.loads(output)
        ledger = envelope["ledger"]
    except (UnicodeDecodeError, json.JSONDecodeError, KeyError) as exc:
        raise EvidenceError(f"remote normalizer returned invalid JSON: {exc}") from exc
    if ledger.get("schema") != decision.LEDGER_SCHEMA \
            or ledger.get("authority_sha256") != PINNED:
        raise EvidenceError("remote ledger has the wrong schema or authority")
    if ledger.get("ai_bin_sha256") != _sha(ai_bin) \
            or int(ledger.get("ai_bin_bytes", -1)) != len(ai_bin):
        raise EvidenceError("remote ledger used a different ai.bin")
    if envelope.get("normalizer_sha256") != normalizer_sha \
            or envelope.get("manifest_identity") != _identity(
                artifact.manifest_bytes) \
            or envelope.get("trace_identity") != {
                "sha256": trace["sha256"], "bytes": trace["bytes"]} \
            or envelope.get("state_identity") != {
                "sha256": state["sha256"], "bytes": state["bytes"]}:
        raise EvidenceError("remote normalization attestation is inconsistent")
    try:
        players = sorted(int(player) for player in envelope["computer_players"])
    except (KeyError, TypeError, ValueError) as exc:
        raise EvidenceError(f"remote state has no computer roster: {exc}") from exc
    if len(players) != len(set(players)) or any(player < 0 or player > 7
                                                for player in players):
        raise EvidenceError("remote state has an invalid computer roster")
    expected_cycles = list(range(1, artifact.cycles + 1))
    coverage = decision.coverage_report(
        ledger, active_players=players, cycles=expected_cycles)
    expected = {(cycle, player) for cycle in expected_cycles for player in players}
    observed = {(int(row["cycle"]), int(row["player"]))
                for row in ledger.get("rows", [])}
    extras = sorted(observed - expected)
    if not players or not coverage["complete"] or extras:
        missing = coverage["missing_active_player_cycles"][:5]
        raise EvidenceError(
            "native ledger is not state-declared fixed-denominator complete: "
            f"missing={missing}, extras={extras[:5]}")
    return ledger, {"players": players, "cycles": expected_cycles,
                    "coverage": coverage,
                    "roster_source": "authenticated-state-controller-table",
                    "state_identity": envelope["state_identity"],
                    "normalizer_sha256": normalizer_sha}


def _extract_ai_bin(pack: Path, *, require_pinned: bool = True) -> bytes:
    import zipfile
    if not pack.is_file():
        raise EvidenceError(f"authenticated BNE ChonkPack is missing: {pack}")
    try:
        with zipfile.ZipFile(pack) as archive:
            value = archive.read("assets/archives/maindat/0277.bin")
    except (zipfile.BadZipFile, KeyError) as exc:
        raise EvidenceError(f"ChonkPack has no maindat/0277.bin: {pack}") from exc
    if not value:
        raise EvidenceError("authenticated ai.bin is empty")
    if require_pinned and (len(value) != PINNED_AI_BIN_BYTES
                           or _sha(value) != PINNED_AI_BIN_SHA256):
        raise EvidenceError(
            "ChonkPack ai.bin is not the pinned Battle.net Edition program")
    return value


def _engine_identity(repository: Path) -> dict[str, Any]:
    from bne_identity import engine_input_identity
    return engine_input_identity(repository)


def _java_build_inputs(repository: Path) -> dict[str, Any]:
    """The source/build closure omitted by the generic engine survey key."""
    relative = (
        "desktop/src/main/java/net/chonkbase/chonkcraft/desktop/"
        "BneAiDecisionAdapter.java",
        "desktop/pom.xml",
        "scripts/jbr/with-jbr-25.sh",
    )
    return {
        "engine": _engine_identity(repository),
        "files": {name: _path_identity(repository / name) for name in relative},
    }


def scenario_to_java_map(scenario: str) -> str:
    from bne_java import scenario_to_java_map as convert
    return convert(scenario)


def _build_receipt_path(repository: Path) -> Path:
    return repository / "desktop" / "target" / "bne-ai-build-receipt.json"


def _build_app(repository: Path, inputs: dict[str, Any]) \
        -> tuple[Path, dict[str, Any]]:
    subprocess.run(
        [str(repository / "scripts" / "jbr" / "with-jbr-25.sh"),
         "mvn", "-q", "-pl", "desktop", "-am", "-DskipTests", "package"],
        cwd=repository, check=True,
    )
    jar = repository / "desktop" / "target" / \
        "chonkcraft-desktop-0.1.0-SNAPSHOT-app.jar"
    if not jar.is_file():
        raise EvidenceError(f"current-head app jar was not produced: {jar}")
    if _java_build_inputs(repository) != inputs:
        raise EvidenceError("Java proof inputs changed while the app JAR was built")
    receipt = {
        "schema": BUILD_RECEIPT_SCHEMA,
        "build_inputs": inputs,
        "jar": _path_identity(jar),
    }
    _write_if_changed(_build_receipt_path(repository), _json_bytes(receipt))
    return jar, receipt


def _verified_app(repository: Path, *, build: bool) \
        -> tuple[Path, dict[str, Any]]:
    inputs = _java_build_inputs(repository)
    if build:
        return _build_app(repository, inputs)
    jar = repository / "desktop" / "target" / \
        "chonkcraft-desktop-0.1.0-SNAPSHOT-app.jar"
    receipt_path = _build_receipt_path(repository)
    if not jar.is_file() or not receipt_path.is_file():
        raise EvidenceError(
            "--skip-build requires an app JAR and its AI build receipt")
    try:
        receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise EvidenceError(f"invalid AI build receipt: {exc}") from exc
    if receipt.get("schema") != BUILD_RECEIPT_SCHEMA \
            or receipt.get("build_inputs") != inputs:
        raise EvidenceError(
            "--skip-build app JAR is stale for the current Java proof inputs")
    if receipt.get("jar") != _path_identity(jar):
        raise EvidenceError(
            "--skip-build app JAR bytes differ from the verified build receipt")
    return jar, receipt


def _current_java_proof(repository: Path, pack: Path) \
        -> tuple[dict[str, Any], dict[str, Any]]:
    """Rebuild the exact proof namespace from current bytes without building."""
    pack_identity = _path_identity(pack)
    ai_bin = _extract_ai_bin(pack, require_pinned=True)
    if _path_identity(pack) != pack_identity:
        raise EvidenceError("ChonkPack changed while its Java proof was computed")
    _jar, receipt = _verified_app(repository, build=False)
    try:
        engine = receipt["build_inputs"]["engine"]
    except (KeyError, TypeError) as exc:
        raise EvidenceError(f"AI build receipt omits engine identity: {exc}") \
            from exc
    proof = {
        "schema": BUILD_RECEIPT_SCHEMA,
        "build_receipt": receipt,
        "pack": pack_identity,
        "ai_bin": _identity(ai_bin),
    }
    return proof, engine


def _bind_java_ai_bin(value: dict[str, Any], ai_bin: bytes) -> dict[str, Any]:
    """Stamp the pack's pinned ai.bin identity onto a current-head Java ledger."""
    identity = _identity(ai_bin)
    bound = dict(value)
    existing_sha = bound.get("ai_bin_sha256")
    existing_bytes = bound.get("ai_bin_bytes")
    if existing_sha not in (None, identity["sha256"]) \
            or existing_bytes not in (None, identity["bytes"]):
        raise EvidenceError("Java adapter bound a different ai.bin than the pack")
    bound["ai_bin_sha256"] = identity["sha256"]
    bound["ai_bin_bytes"] = identity["bytes"]
    return bound


JavaEmitter = Callable[[RemoteArtifact, Path, list[int]], dict[str, Any]]


def java_emitter(repository: Path, pack: Path, jar: Path) -> JavaEmitter:
    def emit(artifact: RemoteArtifact, output: Path,
             expected_players: list[int]) -> dict[str, Any]:
        java_map = scenario_to_java_map(artifact.scenario)
        command = [
            str(repository / "scripts" / "jbr" / "with-jbr-25.sh"), "java",
            "-cp", str(jar),
            "net.chonkbase.chonkcraft.desktop.BneAiDecisionAdapter",
            "--map", java_map,
            "--seed", str(artifact.manifest["run"]["initialization_seed"]),
            "--cycles", str(artifact.cycles), "--output", str(output),
        ]
        environment = os.environ.copy()
        environment["CHONKCRAFT_ASSET_PACK"] = str(pack)
        completed = subprocess.run(command, cwd=repository, env=environment,
                                   stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, check=False)
        if completed.returncode:
            raise EvidenceError(
                "current-head Java twin failed: "
                + completed.stderr.decode("utf-8", "replace").strip())
        value = decision.load_ledger(output)
        if value.get("schema") != decision.LEDGER_SCHEMA \
                or value.get("authority_sha256") != PINNED:
            raise EvidenceError("Java adapter emitted the wrong ledger authority")
        # The adapter ran against this pack's pinned ai.bin.  Bind that
        # identity here so a retained twin cannot later claim a different
        # program.  The adapter may already have written the same fields.
        value = _bind_java_ai_bin(value, _extract_ai_bin(pack, require_pinned=True))
        try:
            person = int(value["person_player"])
            computers = sorted(int(player)
                               for player in value["computer_players"])
        except (KeyError, TypeError, ValueError) as exc:
            raise EvidenceError(f"Java adapter omitted its player choices: {exc}") \
                from exc
        if person < 0 or person > 7 or person in computers \
                or len(computers) != len(set(computers)) \
                or computers != expected_players \
                or value.get("map") != java_map \
                or int(value.get("seed", -1)) != int(
                    artifact.manifest["run"]["initialization_seed"]) \
                or int(value.get("cycles", -1)) != artifact.cycles:
            raise EvidenceError(
                "Java adapter player/map/window choices disagree with native state")
        return value
    return emit


def _java_choices(value: dict[str, Any], artifact: RemoteArtifact,
                  expected_players: list[int]) -> dict[str, Any]:
    """Authenticate the scenario, seat, roster, and window a Java twin ran."""
    if value.get("schema") != decision.LEDGER_SCHEMA \
            or value.get("authority_sha256") != PINNED:
        raise EvidenceError("Java adapter emitted the wrong ledger authority")
    try:
        person = int(value["person_player"])
        computers = sorted(int(player) for player in value["computer_players"])
        seed = int(value["seed"])
        cycles = int(value["cycles"])
    except (KeyError, TypeError, ValueError) as exc:
        raise EvidenceError(f"Java adapter omitted its player choices: {exc}") from exc
    java_map = scenario_to_java_map(artifact.scenario)
    if person < 0 or person > 7 or person in computers \
            or len(computers) != len(set(computers)) \
            or computers != expected_players \
            or value.get("map") != java_map \
            or seed != int(artifact.manifest["run"]["initialization_seed"]) \
            or cycles != artifact.cycles:
        raise EvidenceError(
            "Java adapter player/map/window choices disagree with native state")
    return {
        "map": java_map,
        "seed": seed,
        "cycles": cycles,
        "person_player": person,
        "computer_players": computers,
    }


def _rows_by_key(ledger: dict[str, Any]) -> dict[tuple[int, int], dict[str, Any]]:
    result: dict[tuple[int, int], dict[str, Any]] = {}
    for row in ledger.get("rows") or []:
        key = (int(row["cycle"]), int(row["player"]))
        if key in result:
            raise EvidenceError(f"duplicate Java row at cycle/player {key}")
        result[key] = row
    return result


def compare_fixed(native: dict[str, Any], java: dict[str, Any], *,
                  players: list[int], cycles: list[int]) -> dict[str, Any]:
    native_rows, java_rows = _rows_by_key(native), _rows_by_key(java)
    expected = [(cycle, player) for cycle in cycles for player in players]
    state_exact = telemetry_exact = 0
    differences: list[dict[str, Any]] = []
    for cycle, player in expected:
        left, right = native_rows.get((cycle, player)), java_rows.get((cycle, player))
        if left is None:
            raise EvidenceError(
                f"authenticated native denominator lost cycle {cycle} player {player}")
        if right is None:
            differences.append({"cycle": cycle, "player": player,
                                "field": "missing-java-row", "kind": "state",
                                "native": True, "java": False})
            continue
        state_field = next((field for field in decision.STATE_FIELDS
                            if left.get(field) != right.get(field)), None)
        if state_field is not None:
            differences.append({"cycle": cycle, "player": player,
                                "field": state_field, "kind": "state",
                                "native": left.get(state_field),
                                "java": right.get(state_field)})
            continue
        state_exact += 1
        telemetry_field = next((field for field in decision.TELEMETRY_FIELDS
                                if left.get(field) != right.get(field)), None)
        if telemetry_field is None:
            telemetry_exact += 1
        else:
            differences.append({"cycle": cycle, "player": player,
                                "field": telemetry_field, "kind": "telemetry",
                                "native": left.get(telemetry_field),
                                "java": right.get(telemetry_field)})
    expected_set = set(expected)
    extras = sorted(key for key in java_rows if key not in expected_set)
    for cycle, player in extras:
        differences.append({"cycle": cycle, "player": player,
                            "field": "extra-java-row", "kind": "state",
                            "native": False, "java": True})
    differences.sort(key=lambda item: (
        int(item["cycle"]), int(item["player"]), str(item["field"])))
    denominator = len(expected)
    return {
        "denominator": denominator,
        "players": players,
        "cycle_span": [cycles[0], cycles[-1]] if cycles else None,
        "state_exact": state_exact,
        "telemetry_exact": telemetry_exact,
        "state_exact_rate": state_exact / denominator if denominator else 0.0,
        "telemetry_exact_rate": telemetry_exact / denominator if denominator else 0.0,
        "java_extra_rows": [
            {"cycle": cycle, "player": player} for cycle, player in extras],
        "differences": differences,
        "first_difference": differences[0] if differences else None,
        "state_identical": state_exact == denominator and not extras,
        "identical": telemetry_exact == denominator and not extras,
    }


def _cause(field: str) -> tuple[str, str, int]:
    causes = {
        "missing-java-row": ("AI lifecycle", "Java did not run a native-active computer", 0),
        "extra-java-row": ("AI lifecycle", "Java ran a computer absent from native", 0),
        "profile": ("campaign personality", "wrong ai.bin profile", 1),
        "pc_offset": ("script control flow", "different ai.bin instruction", 2),
        "list_offset": ("production list", "different ordered build/force list", 3),
        "threshold_offset": ("force thresholds", "different force-size table", 4),
        "wait": ("decision cadence", "computer thinks on a different beat", 5),
        "non_pointer_hex": ("AI state", "build, force, resource, or fallback state differs", 6),
        "predicates": ("predicate census", "decision condition differs", 20),
        "writes": ("state mutation telemetry", "state write hook differs", 21),
        "launches": ("force launch", "attack/force consumption differs", 22),
        "classification": ("scheduler classification", "choice/fallout attribution differs", 23),
    }
    return causes.get(field, ("unknown", "unclassified AI divergence", 10))


def ranked_findings(runs: list[dict[str, Any]]) -> list[dict[str, Any]]:
    findings: list[dict[str, Any]] = []
    for run in runs:
        first = run["comparison"].get("first_difference")
        if first is None:
            continue
        family, explanation, weight = _cause(str(first["field"]))
        findings.append({
            "rank_key": [weight, int(first["cycle"]), run["scenario"]],
            "case_id": run["case_id"], "artifact_id": run["artifact_id"],
            "scenario": run["scenario"], "cycle": first["cycle"],
            "player": first["player"], "field": first["field"],
            "kind": first["kind"], "causal_family": family,
            "explanation": explanation,
            "denominator": run["comparison"]["denominator"],
            "state_exact": run["comparison"]["state_exact"],
        })
    findings.sort(key=lambda item: item["rank_key"])
    for rank, item in enumerate(findings, 1):
        item["rank"] = rank
    return findings


def render_next(report: dict[str, Any]) -> str:
    summary = report["summary"]
    certification = report["certification"]
    lines = [
        "# Next BNE AI parity work", "",
        "Generated from authenticated remote manifests, normalized native AI "
        "ledgers, and current-engine Java twins.", "",
        f"- Evidence objects: **{summary['artifacts']}**",
        f"- Fixed player/cycle denominator: **{summary['denominator']}**",
        f"- Committed state exact: **{summary['state_exact']} / {summary['denominator']}**",
        f"- Full causal telemetry exact: **{summary['telemetry_exact']} / {summary['denominator']}**",
        f"- Engine input: `{report['engine_identity']['engine_input_sha256']}`",
        f"- Java proof: `{report['java_proof_id']}`",
        f"- Fleet materialized: **{certification['materialized_scenarios']} / 52**",
        f"- Fleet state exact: **{certification['state_exact_scenarios']} / 52**",
        f"- Fleet telemetry exact: **{certification['telemetry_exact_scenarios']} / 52**",
        f"- Fleet certification: **{'COMPLETE' if certification['complete'] else 'INCOMPLETE'}**",
        "", "## Ranked causal frontier", "",
    ]
    if not report["next"]:
        lines.append("All imported AI player/cycles are exact.")
    for item in report["next"]:
        lines.extend([
            f"### {item['rank']}. {item['causal_family']} — {item['case_id']}", "",
            f"Cycle {item['cycle']}, player {item['player']}, field "
            f"`{item['field']}`: {item['explanation']}.", "",
            f"Proof: {item['state_exact']} of {item['denominator']} committed "
            "player/cycles exact before/within this retained window.", "",
        ])
    return "\n".join(lines).rstrip() + "\n"


def gc_dry_run(store: Path) -> dict[str, Any]:
    """List unreferenced local content; deletion is deliberately not offered."""
    catalog_path = store / "CATALOG.json"
    catalog = json.loads(catalog_path.read_text(encoding="utf-8")) \
        if catalog_path.is_file() else {"objects": [], "twins": []}
    def retained_path(value: object) -> Path:
        if isinstance(value, dict):
            value = value.get("path")
        return Path(str(value))

    referenced_objects = {retained_path(value).parts[-1]
                          for value in catalog.get("objects", [])}
    referenced_twins = {
        tuple(retained_path(value).parts[-2:])
        for value in catalog.get("twins", [])}
    objects = []
    object_root = store / "objects"
    if object_root.is_dir():
        objects = sorted(path.name for path in object_root.iterdir()
                         if path.is_dir() and path.name not in referenced_objects)
    twins: list[str] = []
    twin_root = store / "twins"
    if twin_root.is_dir():
        for engine in twin_root.iterdir():
            if not engine.is_dir():
                continue
            for artifact in engine.iterdir():
                if artifact.is_dir() and (engine.name, artifact.name) \
                        not in referenced_twins:
                    twins.append(f"{engine.name}/{artifact.name}")
    return {"mode": "dry-run", "would_remove_objects": objects,
            "would_remove_twins": sorted(twins), "removed": []}


@contextmanager
def local_lease(store: Path, timeout: float = 0.0):
    """Serialize materializers locally without touching the remote oracle."""
    import fcntl
    store.mkdir(parents=True, exist_ok=True)
    path = store / ".materialize.lock"
    handle = path.open("a+")
    deadline = time.monotonic() + max(0.0, timeout)
    while True:
        try:
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            break
        except BlockingIOError:
            if time.monotonic() >= deadline:
                handle.close()
                raise EvidenceError(
                    f"another AI conductor owns the local lease: {path}")
            time.sleep(min(0.1, max(0.0, deadline - time.monotonic())))
    try:
        handle.seek(0)
        handle.truncate()
        handle.write(json.dumps({"pid": os.getpid(), "started": time.time()}))
        handle.flush()
        yield
    finally:
        fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
        handle.close()


def _proof_directory(path: Path, parent: Path, label: str) -> None:
    if path.parent != parent or path.is_symlink() or not path.is_dir():
        raise EvidenceError(f"missing or unsafe retained AI {label}: {path}")


def _remote_case_id(value: object) -> str:
    if not isinstance(value, str):
        raise EvidenceError("retained AI SOURCE has no remote manifest path")
    path = PurePosixPath(value)
    if not path.is_absolute() or ".." in path.parts \
            or not path.name.endswith(".manifest.json"):
        raise EvidenceError("retained AI SOURCE has an unsafe remote manifest path")
    case_id = path.name.removesuffix(".manifest.json")
    if not case_id or path.parent.name != case_id:
        raise EvidenceError("retained AI SOURCE remote manifest parent is inconsistent")
    return case_id


def _validate_object_bundle(store: Path, artifact_id: str,
                            ai_bin_identity: dict[str, object]) \
        -> dict[str, Any]:
    """Authenticate one normalized native object and its complete closure."""
    if HEX256.fullmatch(artifact_id) is None:
        raise EvidenceError(f"invalid retained AI artifact identity: {artifact_id}")
    objects = store / "objects"
    _proof_directory(objects, store, "objects root")
    root = objects / artifact_id
    _proof_directory(root, objects, "object directory")
    source, source_bytes = _proof_member(root / "SOURCE.json", root)
    manifest_value, manifest_bytes = _proof_member(
        root / "manifest.json", root, canonical=False)
    native, native_bytes = _proof_member(root / "native-ledger.json", root)
    remote_manifest = source.get("remote_manifest")
    case_id = _remote_case_id(remote_manifest)
    manifest = validate_manifest(manifest_bytes, str(remote_manifest))
    if manifest != manifest_value:
        raise EvidenceError(f"retained manifest decoded inconsistently: {root}")
    if native.get("schema") != decision.LEDGER_SCHEMA \
            or native.get("authority_sha256") != PINNED:
        raise EvidenceError(f"retained native ledger has wrong authority: {root}")
    if {"sha256": native.get("ai_bin_sha256"),
        "bytes": native.get("ai_bin_bytes")} != ai_bin_identity:
        raise EvidenceError(f"retained native ledger used a different AI.BIN: {root}")
    identity = source.get("identity")
    if not isinstance(identity, dict):
        raise EvidenceError(f"retained AI SOURCE has no object identity: {root}")
    try:
        players = sorted(int(player) for player in identity["computer_players"])
    except (KeyError, TypeError, ValueError) as exc:
        raise EvidenceError(f"retained AI SOURCE has no computer roster: {exc}") \
            from exc
    if not players or len(players) != len(set(players)) \
            or any(player < 0 or player > 7 for player in players):
        raise EvidenceError(f"retained AI SOURCE has an invalid computer roster: {root}")
    cycles = int(manifest["run"]["cycle_limit"])
    expected_cycles = list(range(1, cycles + 1))
    expected_keys = {(cycle, player) for cycle in expected_cycles
                     for player in players}
    native_rows = _rows_by_key(native)
    if set(native_rows) != expected_keys:
        raise EvidenceError(
            f"retained native ledger violates its fixed denominator: {root}")
    coverage = decision.coverage_report(
        native, active_players=players, cycles=expected_cycles)
    _source, normalizer_sha256 = _normalizer_source()
    state = manifest["run"]["state"]
    expected_identity = {
        "object_schema": CATALOG_SCHEMA,
        "fixture_id": manifest["fixture"]["id"],
        "manifest": _identity(manifest_bytes),
        "trace": manifest["run"]["trace"],
        "native_ledger": _identity(native_bytes),
        "normalizer_sha256": normalizer_sha256,
        "state_identity": {"sha256": state["sha256"],
                           "bytes": state["bytes"]},
        "computer_players": players,
    }
    if identity != expected_identity \
            or _sha(_canonical(expected_identity)) != artifact_id:
        raise EvidenceError(f"retained AI object identity does not match bytes: {root}")
    expected_source = {
        "schema": CATALOG_SCHEMA, "artifact_id": artifact_id,
        "remote_manifest": remote_manifest, "identity": expected_identity,
        "native_coverage": coverage,
        "native_roster_source": "authenticated-state-controller-table",
        "retained": ["manifest.json", "native-ledger.json"],
        "not_retained": ["trace.txt", "state.bin", "bnefx", "ai.bin"],
    }
    if source != expected_source:
        raise EvidenceError(f"retained AI SOURCE disagrees with its object: {root}")
    return {
        "artifact_id": artifact_id, "root": root, "source": source,
        "source_bytes": source_bytes, "manifest": manifest,
        "manifest_bytes": manifest_bytes, "native": native,
        "native_bytes": native_bytes, "players": players,
        "cycles": expected_cycles, "case_id": case_id,
    }


def _validate_twin_bundle(store: Path, java_proof_id: str,
                          artifact_id: str,
                          ai_bin_identity: dict[str, object]) \
        -> dict[str, Any]:
    """Recompute one Java/native comparison and its RUN from retained bytes."""
    native = _validate_object_bundle(store, artifact_id, ai_bin_identity)
    twins = store / "twins"
    _proof_directory(twins, store, "twins root")
    proof_root = twins / java_proof_id
    _proof_directory(proof_root, twins, "Java proof directory")
    root = proof_root / artifact_id
    _proof_directory(root, proof_root, "twin directory")
    twin, twin_bytes = _proof_member(root / "TWIN.json", root)
    java, java_bytes = _proof_member(root / "java-ledger.json", root)
    comparison, comparison_bytes = _proof_member(root / "comparison.json", root)
    run, _run_bytes = _proof_member(root / "RUN.json", root)
    manifest = native["manifest"]
    if {"sha256": java.get("ai_bin_sha256"),
        "bytes": java.get("ai_bin_bytes")} != ai_bin_identity:
        raise EvidenceError(f"retained Java ledger used a different AI.BIN: {root}")
    artifact = RemoteArtifact(
        str(native["source"]["remote_manifest"]), native["manifest_bytes"],
        manifest)
    choices = _java_choices(java, artifact, native["players"])
    expected_comparison = compare_fixed(
        native["native"], java, players=native["players"],
        cycles=native["cycles"])
    if comparison != expected_comparison \
            or comparison_bytes != _json_bytes(expected_comparison):
        raise EvidenceError(f"retained AI comparison was not recomputed: {root}")
    object_path = f"objects/{artifact_id}"
    twin_path = f"twins/{java_proof_id}/{artifact_id}"
    expected_twin = {
        "schema": TWIN_IDENTITY_SCHEMA,
        "java_proof_id": java_proof_id,
        "artifact_id": artifact_id,
        "fixture_id": artifact.fixture_id,
        "object": object_path,
        "source": _identity(native["source_bytes"]),
        "native_ledger": _identity(native["native_bytes"]),
        "java_choices": choices,
        "java_ledger": _identity(java_bytes),
        "comparison": _identity(comparison_bytes),
    }
    if twin != expected_twin or twin_bytes != _json_bytes(expected_twin):
        raise EvidenceError(f"retained TWIN identity disagrees with its parents: {root}")
    expected_run = {
        "schema": TWIN_IDENTITY_SCHEMA,
        "java_proof_id": java_proof_id,
        "artifact_id": artifact_id,
        "case_id": native["case_id"],
        "fixture_id": artifact.fixture_id,
        "scenario": artifact.scenario,
        "seed": int(manifest["run"]["initialization_seed"]),
        "cycles": artifact.cycles,
        "java_choices": choices,
        "native_players": native["players"],
        "native_roster_source": "authenticated-state-controller-table",
        "object": object_path,
        "twin": twin_path,
        "source": _identity(native["source_bytes"]),
        "native_ledger": _identity(native["native_bytes"]),
        "twin_identity": _identity(twin_bytes),
        "java_ledger": _identity(java_bytes),
        "comparison_identity": _identity(comparison_bytes),
        "comparison": expected_comparison,
    }
    if run != expected_run:
        raise EvidenceError(f"retained RUN disagrees with its proof graph: {root}")
    return run


def _validated_twin_runs(store: Path, java_proof_id: str,
                         ai_bin_identity: dict[str, object]) \
        -> list[dict[str, Any]]:
    if HEX256.fullmatch(java_proof_id) is None:
        raise EvidenceError(f"invalid retained Java proof identity: {java_proof_id}")
    twins = store / "twins"
    if not twins.exists():
        return []
    _proof_directory(twins, store, "twins root")
    root = twins / java_proof_id
    if not root.exists():
        return []
    _proof_directory(root, twins, "Java proof directory")
    runs: list[dict[str, Any]] = []
    for path in sorted(root.iterdir(), key=lambda item: item.name):
        if HEX256.fullmatch(path.name) is None:
            raise EvidenceError(f"unexpected member in retained Java proof: {path}")
        runs.append(_validate_twin_bundle(
            store, java_proof_id, path.name, ai_bin_identity))
    return runs


def _strongest_runs(runs: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    strongest: dict[str, dict[str, Any]] = {}
    for run in runs:
        key = str(run["scenario"]).replace("/", "\\").casefold()
        old = strongest.get(key)
        if old is None or (int(run["cycles"]), str(run["artifact_id"])) > (
                int(old["cycles"]), str(old["artifact_id"])):
            strongest[key] = run
    return sorted(strongest.values(), key=lambda item: (
        str(item["scenario"]).casefold(), str(item["artifact_id"])))


def _load_current_twins(store: Path, java_proof_id: str,
                        ai_bin_identity: dict[str, object]) \
        -> list[dict[str, Any]]:
    """Load strongest runs only after validating every retained companion."""
    return _strongest_runs(_validated_twin_runs(
        store, java_proof_id, ai_bin_identity))


def _certification(runs: Iterable[dict[str, Any]], *,
                   certifiable_proof: bool,
                   requirements_path: Path = FLEET_REQUIREMENTS) \
        -> dict[str, Any]:
    """The only green bar: all 52 scenarios, full state and telemetry."""
    requirements = load_fleet_requirements(requirements_path)
    retained = list(runs)
    by_scenario: dict[str, dict[str, Any]] = {}
    duplicates: list[str] = []
    for run in retained:
        key = str(run["scenario"]).replace("/", "\\").casefold()
        if key in by_scenario:
            duplicates.append(str(run["scenario"]))
        by_scenario[key] = run
    canonical = {
        str(row["native_scenario"]).replace("/", "\\").casefold()
        for row in requirements}
    unexpected = sorted(key for key in by_scenario if key not in canonical)
    rows = []
    for requirement in requirements:
        key = str(requirement["native_scenario"]).replace("/", "\\").casefold()
        run = by_scenario.get(key)
        materialized = run is not None \
            and int(run.get("seed", -1)) == int(requirement["seed"]) \
            and int(run.get("cycles", 0)) == int(requirement["cycles"])
        state_exact = bool(materialized and run["comparison"].get(
            "state_identical"))
        telemetry_exact = bool(materialized and run["comparison"].get(
            "identical"))
        rows.append({
            "id": requirement["id"], "materialized": materialized,
            "state_exact": state_exact, "telemetry_exact": telemetry_exact,
            "artifact_id": run.get("artifact_id") if materialized else None,
        })
    materialized = sum(row["materialized"] for row in rows)
    state_exact = sum(row["state_exact"] for row in rows)
    telemetry_exact = sum(row["telemetry_exact"] for row in rows)
    return {
        "required_scenarios": 52,
        "materialized_scenarios": materialized,
        "state_exact_scenarios": state_exact,
        "telemetry_exact_scenarios": telemetry_exact,
        "proof_certifiable": certifiable_proof,
        "complete": certifiable_proof and materialized == 52
                    and state_exact == 52 and telemetry_exact == 52
                    and len(retained) == 52 and not duplicates
                    and not unexpected,
        "duplicate_scenarios": sorted(duplicates),
        "unexpected_scenarios": unexpected,
        "scenarios": rows,
    }


def _catalog_document(store: Path, java_proof_id: str,
                      runs: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "schema": CATALOG_SCHEMA,
        "java_proof_id": java_proof_id,
        "objects": [{"path": run["object"],
                     "artifact_id": run["artifact_id"],
                     "source": run["source"]} for run in runs],
        "twins": [{"path": run["twin"],
                   "java_proof_id": run["java_proof_id"],
                   "artifact_id": run["artifact_id"],
                   "run": _path_identity(
                       store / run["twin"] / "RUN.json")}
                  for run in runs],
    }


def _validate_report_graph(document: dict[str, Any], *, store: Path,
                           java_proof: dict[str, Any],
                           engine_identity: dict[str, Any],
                           certifiable_proof: bool,
                           requirements_path: Path = FLEET_REQUIREMENTS) \
        -> dict[str, Any]:
    """Validate a report only from its complete retained proof graph."""
    if document.get("schema") != REPORT_SCHEMA \
            or document.get("authority_sha256") != PINNED:
        raise EvidenceError("AI conductor report has the wrong schema or authority")
    if java_proof.get("schema") != BUILD_RECEIPT_SCHEMA:
        raise EvidenceError("current Java proof has the wrong schema")
    java_proof_id = _sha(_canonical(java_proof))
    if document.get("java_proof_id") != java_proof_id \
            or document.get("java_proof") != java_proof:
        raise EvidenceError("AI conductor report is not bound to current Java proof")
    if document.get("engine_identity") != engine_identity:
        raise EvidenceError("AI conductor report is not bound to current engine inputs")
    ai_bin_identity = java_proof.get("ai_bin")
    if not isinstance(ai_bin_identity, dict) \
            or document.get("ai_bin") != ai_bin_identity:
        raise EvidenceError("AI conductor report has the wrong AI.BIN identity")
    all_runs = _validated_twin_runs(store, java_proof_id, ai_bin_identity)
    runs = _strongest_runs(all_runs)
    if document.get("runs") != runs:
        raise EvidenceError("AI conductor report runs differ from retained twins")
    selected = document.get("selected_runs")
    if not isinstance(selected, list):
        raise EvidenceError("AI conductor report has no selected run list")
    by_artifact = {str(run["artifact_id"]): run for run in all_runs}
    selected_ids: set[str] = set()
    for run in selected:
        if not isinstance(run, dict):
            raise EvidenceError("AI conductor selected run is not an object")
        artifact_id = str(run.get("artifact_id") or "")
        if artifact_id in selected_ids or by_artifact.get(artifact_id) != run:
            raise EvidenceError(
                "AI conductor selected runs differ from retained twins")
        selected_ids.add(artifact_id)
    summary = {
        "artifacts": len(runs),
        "denominator": sum(run["comparison"]["denominator"] for run in runs),
        "state_exact": sum(run["comparison"]["state_exact"] for run in runs),
        "telemetry_exact": sum(
            run["comparison"]["telemetry_exact"] for run in runs),
    }
    certification = _certification(
        runs, certifiable_proof=certifiable_proof,
        requirements_path=requirements_path)
    findings = ranked_findings(runs)
    if document.get("summary") != summary \
            or document.get("certification") != certification \
            or document.get("next") != findings:
        raise EvidenceError("AI conductor derived report fields are not reproducible")
    fleet = document.get("fleet")
    if not isinstance(fleet, dict) or set(fleet) != {
            "required", "existing", "missing", "materialized"}:
        raise EvidenceError("AI conductor report has an invalid fleet summary")
    try:
        required = int(fleet["required"])
        existing = int(fleet["existing"])
        missing = int(fleet["missing"])
        materialized = int(fleet["materialized"])
    except (TypeError, ValueError) as exc:
        raise EvidenceError(f"AI conductor fleet summary is not numeric: {exc}") \
            from exc
    if (required, materialized) != (
            52, certification["materialized_scenarios"]) \
            or existing < materialized or existing > 52 or missing < 0 \
            or existing + missing != 52 \
            or (certification["complete"] and (existing, missing) != (52, 0)):
        raise EvidenceError("AI conductor fleet summary contradicts certification")
    remote = document.get("remote")
    if not isinstance(remote, dict) or set(remote) != {"host", "root"} \
            or not isinstance(remote.get("host"), str) or not remote["host"]:
        raise EvidenceError("AI conductor report has an invalid remote identity")
    _validated_remote_root(str(remote["root"]))
    catalog, _catalog_bytes = _proof_member(store / "CATALOG.json", store)
    if catalog != _catalog_document(store, java_proof_id, runs):
        raise EvidenceError("AI conductor catalog differs from retained proof graph")
    expected = {
        "schema": REPORT_SCHEMA, "authority_sha256": PINNED,
        "engine_identity": engine_identity, "java_proof_id": java_proof_id,
        "java_proof": java_proof, "ai_bin": ai_bin_identity,
        "remote": remote, "summary": summary, "fleet": fleet,
        "certification": certification, "selected_runs": selected,
        "runs": runs, "next": findings,
    }
    if document != expected:
        raise EvidenceError("AI conductor report contains unvalidated fields")
    return document


def validate_retained_report(document: dict[str, Any], *, store: Path,
                             repository: Path = ROOT,
                             pack: Path = DEFAULT_PACK,
                             requirements_path: Path = FLEET_REQUIREMENTS) \
        -> dict[str, Any]:
    """Public fail-closed verifier for the next-level certification gate."""
    if store.is_symlink():
        raise EvidenceError(f"retained AI store is a symlink: {store}")
    store = store.resolve()
    if not store.is_dir():
        raise EvidenceError(f"retained AI store is missing: {store}")
    retained, _raw = _proof_member(store / "NEXT.json", store)
    if retained != document:
        raise EvidenceError("detached AI report differs from store/NEXT.json")
    java_proof, engine = _current_java_proof(
        repository.resolve(), pack.expanduser().resolve())
    receipt = java_proof.get("build_receipt")
    if not isinstance(receipt, dict) \
            or receipt.get("schema") != BUILD_RECEIPT_SCHEMA \
            or not isinstance(receipt.get("jar"), dict):
        raise EvidenceError("AI report is not backed by a production Java build")
    validated = _validate_report_graph(
        retained, store=store, java_proof=java_proof,
        engine_identity=engine, certifiable_proof=True,
        requirements_path=requirements_path)
    final_proof, final_engine = _current_java_proof(
        repository.resolve(), pack.expanduser().resolve())
    if final_proof != java_proof or final_engine != engine:
        raise EvidenceError(
            "current Java proof inputs changed while the store was validated")
    return validated


def validate_retained_store(store: Path, *, repository: Path = ROOT,
                            pack: Path = DEFAULT_PACK,
                            requirements_path: Path = FLEET_REQUIREMENTS) \
        -> dict[str, Any]:
    """Load and validate store/NEXT.json plus every referenced proof member."""
    if store.is_symlink():
        raise EvidenceError(f"retained AI store is a symlink: {store}")
    root = store.resolve()
    document, _raw = _proof_member(root / "NEXT.json", root)
    return validate_retained_report(
        document, store=root, repository=repository, pack=pack,
        requirements_path=requirements_path)


def run_conductor(*, repository: Path, store: Path, backend: SshBackend,
                  pack: Path, remote_root: str = DEFAULT_REMOTE_ROOT,
                  cases: Iterable[str] = (), limit: int = 0,
                  all_captures: bool = False, build: bool = True,
                  emitter: JavaEmitter | None = None,
                  jobs: int = 1) \
        -> tuple[int, dict[str, Any]]:
    if jobs not in (1, 2):
        raise EvidenceError("jobs must stay within the conservative 1-2 budget")
    discovered = discover_remote(backend, remote_root)
    artifacts = select_strongest(
        discovered, cases=cases, limit=limit,
        all_captures=all_captures)
    if not artifacts:
        raise EvidenceError("no remote AI evidence matched the requested cases")
    certifiable_proof = emitter is None
    pack_identity = _path_identity(pack)
    ai_bin = _extract_ai_bin(pack, require_pinned=certifiable_proof)
    if _path_identity(pack) != pack_identity:
        raise EvidenceError("ChonkPack changed while the AI twin was materialized")
    engine = _engine_identity(repository)
    if emitter is None:
        jar, receipt = _verified_app(repository, build=build)
        java_proof = {
            "schema": BUILD_RECEIPT_SCHEMA,
            "build_receipt": receipt,
            "pack": pack_identity,
            "ai_bin": _identity(ai_bin),
        }
        emitter = java_emitter(repository, pack, jar)
    else:
        # A deterministic test double exercises storage/comparison, never the
        # release-certification bar because it has no built application JAR.
        java_proof = {
            "schema": BUILD_RECEIPT_SCHEMA,
            "build_receipt": {
                "schema": "injected-test-emitter",
                "build_inputs": {"engine": engine},
                "jar": None,
            },
            "pack": pack_identity,
            "ai_bin": _identity(ai_bin),
        }
    java_proof_id = _sha(_canonical(java_proof))

    def process(artifact: RemoteArtifact) -> dict[str, Any]:
        native, coverage = normalize_remote(backend, artifact, ai_bin)
        native_bytes = _json_bytes(native)
        artifact_key = {
            "object_schema": CATALOG_SCHEMA,
            "fixture_id": artifact.fixture_id,
            "manifest": _identity(artifact.manifest_bytes),
            "trace": artifact.manifest["run"]["trace"],
            "native_ledger": _identity(native_bytes),
            "normalizer_sha256": coverage["normalizer_sha256"],
            "state_identity": coverage["state_identity"],
            "computer_players": coverage["players"],
        }
        artifact_id = _sha(_canonical(artifact_key))
        object_root = store / "objects" / artifact_id
        object_path = str(object_root.relative_to(store))
        source = {
            "schema": CATALOG_SCHEMA, "artifact_id": artifact_id,
            "remote_manifest": artifact.path, "identity": artifact_key,
            "native_coverage": coverage["coverage"],
            "native_roster_source": coverage["roster_source"],
            "retained": ["manifest.json", "native-ledger.json"],
            "not_retained": ["trace.txt", "state.bin", "bnefx", "ai.bin"],
        }
        source_bytes = _json_bytes(source)
        _write_immutable(object_root / "manifest.json", artifact.manifest_bytes)
        _write_immutable(object_root / "native-ledger.json", native_bytes)
        _write_immutable(object_root / "SOURCE.json", source_bytes)
        twin_root = store / "twins" / java_proof_id / artifact_id
        twin_path = str(twin_root.relative_to(store))
        java_path = twin_root / "java-ledger.json"
        java_path.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(
                prefix="java-ledger.", suffix=".json", dir=twin_root,
                delete=False) as handle:
            temporary_java = Path(handle.name)
        try:
            java = emitter(artifact, temporary_java, coverage["players"])
        finally:
            temporary_java.unlink(missing_ok=True)
        choices = _java_choices(java, artifact, coverage["players"])
        _write_immutable(java_path, _json_bytes(java))
        comparison = compare_fixed(native, java,
                                   players=coverage["players"],
                                   cycles=coverage["cycles"])
        comparison_bytes = _json_bytes(comparison)
        twin_identity = {
            "schema": TWIN_IDENTITY_SCHEMA,
            "java_proof_id": java_proof_id,
            "artifact_id": artifact_id,
            "fixture_id": artifact.fixture_id,
            "object": object_path,
            "source": _identity(source_bytes),
            "native_ledger": _identity(native_bytes),
            "java_choices": choices,
            "java_ledger": _identity(_json_bytes(java)),
            "comparison": _identity(comparison_bytes),
        }
        twin_bytes = _json_bytes(twin_identity)
        _write_immutable(twin_root / "comparison.json", comparison_bytes)
        _write_immutable(twin_root / "TWIN.json", twin_bytes)
        run = {
            "schema": TWIN_IDENTITY_SCHEMA,
            "java_proof_id": java_proof_id,
            "artifact_id": artifact_id, "case_id": artifact.case_id,
            "fixture_id": artifact.fixture_id, "scenario": artifact.scenario,
            "seed": int(artifact.manifest["run"]["initialization_seed"]),
            "cycles": artifact.cycles, "java_choices": choices,
            "native_players": coverage["players"],
            "native_roster_source": coverage["roster_source"],
            "object": object_path, "twin": twin_path,
            "source": _identity(source_bytes),
            "native_ledger": _identity(native_bytes),
            "twin_identity": _identity(twin_bytes),
            "java_ledger": _identity(_json_bytes(java)),
            "comparison_identity": _identity(comparison_bytes),
            "comparison": comparison,
        }
        _write_immutable(twin_root / "RUN.json", _json_bytes(run))
        return run
    if jobs == 1:
        runs = [process(artifact) for artifact in artifacts]
    else:
        with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as pool:
            # map preserves evidence selection order, keeping NEXT deterministic.
            runs = list(pool.map(process, artifacts))
    retained_runs = _load_current_twins(
        store, java_proof_id, _identity(ai_bin))
    totals = {
        "artifacts": len(retained_runs),
        "denominator": sum(run["comparison"]["denominator"]
                           for run in retained_runs),
        "state_exact": sum(run["comparison"]["state_exact"]
                           for run in retained_runs),
        "telemetry_exact": sum(run["comparison"]["telemetry_exact"]
                               for run in retained_runs),
    }
    certification = _certification(
        retained_runs, certifiable_proof=certifiable_proof)
    plan = fleet_plan(discovered, host=backend.host, remote_root=remote_root,
                      store=store, materialized_runs=retained_runs)
    report = {
        "schema": REPORT_SCHEMA, "authority_sha256": PINNED,
        "engine_identity": engine, "java_proof_id": java_proof_id,
        "java_proof": java_proof, "ai_bin": _identity(ai_bin),
        "remote": {"host": backend.host, "root": remote_root},
        "summary": totals, "fleet": {
            "required": plan["required"], "existing": plan["existing"],
            "missing": plan["missing"],
            "materialized": certification["materialized_scenarios"]},
        "certification": certification,
        "selected_runs": runs, "runs": retained_runs,
        "next": ranked_findings(retained_runs),
    }
    store.mkdir(parents=True, exist_ok=True)
    _write_if_changed(store / "CATALOG.json", _json_bytes(
        _catalog_document(store, java_proof_id, retained_runs)))
    _write_if_changed(store / "NEXT.json", _json_bytes(report))
    _write_if_changed(store / "NEXT.md", render_next(report).encode("utf-8"))
    _write_if_changed(store / "FLEET.json", _json_bytes(plan))
    _write_if_changed(store / "FLEET.md", render_fleet_plan(plan).encode("utf-8"))
    validated = _validate_report_graph(
        report, store=store, java_proof=java_proof,
        engine_identity=engine, certifiable_proof=certifiable_proof)
    return (0 if certification["complete"] else 1), validated


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Build content-addressed native/current-Java AI evidence")
    parser.add_argument("--host", default=os.environ.get(
        "CHONKCRAFT_ORACLE_HOST", "i9beef"))
    parser.add_argument("--ssh", default="ssh")
    parser.add_argument("--remote-root", default=DEFAULT_REMOTE_ROOT)
    parser.add_argument("--repository", type=Path, default=ROOT)
    parser.add_argument("--artifact-root", type=Path, default=DEFAULT_STORE)
    parser.add_argument("--asset-pack", type=Path, default=DEFAULT_PACK)
    parser.add_argument("--case", action="append", default=[])
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--all-captures", action="store_true")
    parser.add_argument(
        "--materialize", action="store_true",
        help="explicitly normalize selected traces and run current Java twins")
    parser.add_argument(
        "--jobs", type=int, default=1,
        help="concurrent normalization/Java twins (default 1, maximum 2)")
    parser.add_argument("--lease-timeout", type=float, default=0.0)
    parser.add_argument(
        "--gc-dry-run", action="store_true",
        help="report unreferenced local content without deleting anything")
    parser.add_argument(
        "--validate-store", action="store_true",
        help="fail closed unless NEXT.json and its retained proof graph validate")
    parser.add_argument("--skip-build", action="store_true")
    args = parser.parse_args(argv)
    if args.jobs not in (1, 2):
        parser.error("--jobs must be 1 or 2 (the conservative oracle budget)")
    if args.materialize and args.limit < 1:
        parser.error("--materialize requires an explicit positive --limit")
    if args.gc_dry_run:
        print(json.dumps(gc_dry_run(args.artifact_root.expanduser().resolve()),
                         indent=2, sort_keys=True))
        return 0
    if args.validate_store:
        try:
            report = validate_retained_store(
                args.artifact_root.expanduser().resolve(),
                repository=args.repository.resolve(),
                pack=args.asset_pack.expanduser().resolve())
        except (EvidenceError, OSError, ValueError) as exc:
            print(f"AI conductor validation: ERROR: {exc}", file=sys.stderr)
            return 2
        print(json.dumps({
            "valid": True,
            "complete": report["certification"]["complete"],
            "java_proof_id": report["java_proof_id"],
            "materialized_scenarios": report["certification"][
                "materialized_scenarios"],
        }, indent=2, sort_keys=True))
        return 0
    if not args.materialize:
        try:
            available = discover_remote(SshBackend(args.host, args.ssh),
                                        args.remote_root)
            selected = select_strongest(
                available, cases=args.case, limit=args.limit,
                all_captures=args.all_captures)
            plan = fleet_plan(
                available, host=args.host, remote_root=args.remote_root,
                store=args.artifact_root.expanduser().resolve())
        except EvidenceError as exc:
            print(f"AI conductor discovery: ERROR: {exc}", file=sys.stderr)
            return 2
        print(json.dumps({
            "mode": "read-only-discovery", "host": args.host,
            "remote_root": args.remote_root,
            "manifests": len(available), "selected": len(selected),
            "selected_cases": [item.case_id for item in selected],
            "fleet": {"required": plan["required"],
                      "existing": plan["existing"],
                      "missing": plan["missing"],
                      "missing_scenarios": [
                          row["native_scenario"] for row in plan["scenarios"]
                          if row["status"] == "missing"],
                      "capture_jobs": plan["capture_jobs"],
                      "execution": plan["execution"]},
            "next": "rerun with --materialize to import normalized ledgers "
                    "and execute current-head Java twins",
        }, indent=2, sort_keys=True))
        return 0
    try:
        target = args.artifact_root.expanduser().resolve()
        with local_lease(target, args.lease_timeout):
            status, report = run_conductor(
                repository=args.repository.resolve(), store=target,
                backend=SshBackend(args.host, args.ssh),
                pack=args.asset_pack.expanduser().resolve(),
                remote_root=args.remote_root, cases=args.case, limit=args.limit,
                all_captures=args.all_captures, build=not args.skip_build,
                jobs=args.jobs,
            )
    except (EvidenceError, OSError, subprocess.SubprocessError, ValueError) as exc:
        print(f"AI conductor: ERROR: {exc}", file=sys.stderr)
        return 2
    summary = report["summary"]
    print(f"AI conductor: {summary['state_exact']}/{summary['denominator']} "
          f"committed player/cycles exact; {len(report['next'])} ranked frontiers; "
          f"fleet {report['certification']['telemetry_exact_scenarios']}/52 "
          "state+telemetry exact")
    print(args.artifact_root.expanduser().resolve() / "NEXT.md")
    return status


if __name__ == "__main__":
    raise SystemExit(main())
