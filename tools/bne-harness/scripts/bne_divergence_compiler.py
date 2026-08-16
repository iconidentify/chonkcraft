#!/usr/bin/env python3
"""Compile one normalized mismatch into a fail-closed native decision lab.

This is the bridge between "the traces differ" and work an unattended agent
can safely perform.  It never turns missing native evidence into a theory.  It
seals the shortest causal time prefix, authenticates supplied captures, emits
the implicated static slice, reproduces a real micro-oracle snapshot when one
exists, routes the finding into the global combat/campaign obligations, and
prints commands that can prove or reject the eventual engine change.
"""

from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import shlex
import shutil
import sys
import tempfile
from typing import Any, Iterable

import bne_campaign_lifecycle as campaign
import bne_combat_lifecycle as combat
import bne_micro_oracle as micro
import bne_static_analysis as static
from bne_branch_witness import BNE_202_SHA256, BNE_TEXT_END, BNE_TEXT_START
from bne_snapshot_capture import (
    CaptureError, load_evidence_identity, load_specification,
    normalize_scenario,
    specification_from_branch_witness,
)


SCHEMA = "chonkcraft-bne-normalized-mismatch-1"
WORK_ORDER_SCHEMA = "chonkcraft-bne-divergence-work-order-1"
POINTER_SCHEMA = "chonkcraft-bne-divergence-pointer-1"
FOCUSED_PROOF_SCHEMA = "chonkcraft-bne-focused-proof-1"
FOCUSED_PROOF_MANIFEST_SCHEMA = "chonkcraft-bne-focused-proof-manifest-1"
SAFE_LABEL = __import__("re").compile(r"^[A-Za-z0-9_.-]{1,96}$")


class CompilerError(ValueError):
    pass


def canonical_digest(value: object) -> str:
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"),
                         ensure_ascii=True).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def file_identity(path: Path) -> dict[str, Any]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            size += len(block)
            digest.update(block)
    return {"bytes": size, "sha256": digest.hexdigest()}


def _write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n",
                    encoding="utf-8")


def _write_text(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value, encoding="utf-8")


def _inventory(root: Path) -> dict[str, Any]:
    return {str(path.relative_to(root)): file_identity(path)
            for path in sorted(root.rglob("*"))
            if path.is_file() and path != root / "manifest.json"}


@contextmanager
def _lock(root: Path):
    root.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(root / ".compile.lock", os.O_CREAT | os.O_RDWR, 0o600)
    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX)
        yield
    finally:
        fcntl.flock(descriptor, fcntl.LOCK_UN)
        os.close(descriptor)


def _normalize(document: dict[str, Any]) -> dict[str, Any]:
    if document.get("schema") != SCHEMA:
        raise CompilerError(f"normalized mismatch must use schema {SCHEMA!r}")
    case = document.get("case")
    cycle = document.get("cycle")
    if not isinstance(case, str) or not SAFE_LABEL.fullmatch(case):
        raise CompilerError("normalized mismatch needs a filesystem-safe case")
    if not isinstance(cycle, int) or cycle < 0:
        raise CompilerError("normalized mismatch needs a non-negative cycle")
    finding = document.get("finding")
    if not isinstance(finding, dict) or not finding:
        raise CompilerError("normalized mismatch needs one structured finding")
    first = document.get("clean_through", cycle - 1)
    if first != cycle - 1:
        raise CompilerError(
            "normalized mismatch must prove the prefix immediately before cycle")
    try:
        evidence_identity = load_evidence_identity(document.get("identity"))
    except CaptureError as failure:
        raise CompilerError(str(failure)) from failure
    if evidence_identity["case"] != case \
            or evidence_identity["cycle"] != cycle:
        raise CompilerError(
            "normalized mismatch identity disagrees with its case/cycle")
    finding_subject = finding.get(
        "native_slot", finding.get("unit", finding.get("slot")))
    if finding_subject is not None \
            and finding_subject != evidence_identity["subject"]["native_slot"]:
        raise CompilerError(
            "normalized mismatch finding disagrees with its identity subject")
    return {**document, "case": case, "cycle": cycle,
            "clean_through": first, "finding": finding,
            "identity": evidence_identity}


def _safe_bundle_file(root: Path, relative: str) -> Path:
    if not isinstance(relative, str) or not relative \
            or Path(relative).is_absolute():
        raise CompilerError(f"unsafe evidence artifact path: {relative!r}")
    root = root.resolve()
    candidate = root / relative
    current = root
    for component in Path(relative).parts:
        if component in ("", ".", ".."):
            raise CompilerError(f"unsafe evidence artifact path: {relative!r}")
        current = current / component
        if current.is_symlink():
            raise CompilerError(
                f"evidence artifact is a symbolic link: {current}")
    resolved = candidate.resolve()
    if not resolved.is_relative_to(root) or not resolved.is_file():
        raise CompilerError(f"evidence artifact is missing or escapes: {relative}")
    return resolved


def _manifest_artifacts(path: Path, value: object) -> dict[str, Path]:
    if not isinstance(value, dict) or not isinstance(value.get("artifacts"), dict):
        return {}
    result = {}
    for relative, expected in sorted(value["artifacts"].items()):
        artifact = _safe_bundle_file(path.parent, relative)
        if not isinstance(expected, dict) or file_identity(artifact) != expected:
            raise CompilerError(
                f"sealed evidence artifact changed: {path.parent / relative}")
        result[relative] = artifact
    return result


def _runtime_identity() -> dict[str, Any]:
    packages = {}
    for name in ("capstone", "unicorn"):
        try:
            packages[name] = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            packages[name] = None
    executable = Path(sys.executable).resolve()
    return {
        "python_version": sys.version,
        "implementation": platform.python_implementation(),
        "platform": platform.platform(),
        "executable": {"path": str(executable), **file_identity(executable)},
        "packages": packages,
    }


def _identity_inputs(document: dict[str, Any], repository: Path,
                     native_executable: Path | None,
                     analyzer: dict[str, Any]) -> dict[str, Any]:
    identities: dict[str, Any] = {}
    evidence = document.get("evidence") or {}
    if not isinstance(evidence, dict):
        raise CompilerError("evidence must be a name-to-path object")
    for name, raw in sorted(evidence.items()):
        if not isinstance(name, str) or not SAFE_LABEL.fullmatch(name) \
                or not isinstance(raw, str):
            raise CompilerError("evidence needs filesystem-safe names and paths")
        candidate = Path(raw).expanduser()
        if candidate.is_symlink():
            raise CompilerError(f"evidence is missing or unsafe: {candidate}")
        path = candidate.resolve()
        if not path.is_file():
            raise CompilerError(f"evidence is missing or unsafe: {path}")
        identities[name] = {"path": str(path), **file_identity(path)}
        if path.suffix.lower() == ".json":
            try:
                value = json.loads(path.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                value = None
            for relative, artifact in _manifest_artifacts(path, value).items():
                identities[f"{name}:artifact:{relative}"] = {
                    "path": str(artifact), **file_identity(artifact)}
            for segment in value.get("segments", []) \
                    if isinstance(value, dict) else []:
                blob = segment.get("blob") if isinstance(segment, dict) else None
                digest = blob.get("sha256") if isinstance(blob, dict) else None
                if not isinstance(digest, str) or len(digest) != 64:
                    continue
                blob_path = _safe_bundle_file(
                    path.parent, f"blobs/{digest}.bin")
                blob_identity = file_identity(blob_path)
                if blob_identity["sha256"] != digest \
                        or blob_identity["bytes"] != blob.get("bytes"):
                    raise CompilerError(f"snapshot blob identity changed: {blob_path}")
                identities[f"{name}:blob:{digest}"] = {
                    "path": str(blob_path), **blob_identity}
    required = {
        "combat_requirements": repository / "tools/bne-harness/combat-lifecycle-requirements.json",
        "campaign_catalog": repository / "engine/src/main/resources/chonkcraft/missions.tsv",
    }
    for name, path in required.items():
        if not path.is_file():
            raise CompilerError(f"required parity inventory is missing: {path}")
        identities[name] = {"path": str(path), **file_identity(path)}
    if native_executable is not None:
        raw_executable = native_executable.expanduser()
        if raw_executable.is_symlink():
            raise CompilerError("native executable must not be a symbolic link")
        native_executable = raw_executable.resolve()
        identity = file_identity(native_executable)
        if identity["sha256"] != BNE_202_SHA256:
            raise CompilerError("native executable is not pinned BNE 2.02b")
        identities["native_executable"] = {
            "path": str(native_executable), **identity}
    here = Path(__file__).resolve().parent
    for name in ("bne_divergence_compiler.py", "bne_static_analysis.py",
                 "bne_snapshot_capture.py", "bne_micro_oracle.py",
                 "bne_branch_witness.py", "bne_branch_capture.py",
                 "bne_triage.py", "bne_combat_lifecycle.py",
                 "bne_campaign_lifecycle.py"):
        identities[f"analysis:{name}"] = file_identity(here / name)
    ghidra_exporter = here.parent / "ghidra_scripts" / "ExportFunctionSlice.java"
    if not ghidra_exporter.is_file():
        raise CompilerError(f"static-analysis exporter is missing: {ghidra_exporter}")
    identities["analysis:ExportFunctionSlice.java"] = file_identity(
        ghidra_exporter)
    identities["analysis:toolchain"] = analyzer
    identities["analysis:runtime"] = _runtime_identity()
    return identities


def _copy_evidence(document: dict[str, Any], destination: Path) \
        -> tuple[dict[str, Path], dict[str, Any]]:
    copied: dict[str, Path] = {}
    parsed: dict[str, Any] = {}
    for name, raw in sorted((document.get("evidence") or {}).items()):
        candidate = Path(raw).expanduser()
        if candidate.is_symlink():
            raise CompilerError(f"evidence is unsafe: {candidate}")
        source = candidate.resolve()
        target = destination / "inputs" / f"{name}{source.suffix}"
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        copied[name] = target
        if source.suffix.lower() == ".json":
            try:
                parsed[name] = json.loads(source.read_text(encoding="utf-8"))
            except json.JSONDecodeError as failure:
                raise CompilerError(f"evidence {name!r} is invalid JSON: {failure}")
            for relative, artifact in _manifest_artifacts(
                    source, parsed[name]).items():
                bundle_target = destination / "inputs" / "bundles" / name / relative
                bundle_target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(artifact, bundle_target)
            for segment in parsed[name].get("segments", []) \
                    if isinstance(parsed[name], dict) else []:
                blob = segment.get("blob") if isinstance(segment, dict) else None
                digest = blob.get("sha256") if isinstance(blob, dict) else None
                if isinstance(digest, str):
                    blob_source = _safe_bundle_file(
                        source.parent, f"blobs/{digest}.bin")
                    blob_target = destination / "inputs" / "blobs" / f"{digest}.bin"
                    blob_target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(blob_source, blob_target)
    return copied, parsed


def _same_identity(raw: object, expected: dict[str, Any]) -> bool:
    try:
        actual = load_evidence_identity(raw)
    except CaptureError:
        return False
    return actual == expected or (
        {**actual, "scenario": normalize_scenario(actual["scenario"])}
        == {**expected, "scenario": normalize_scenario(expected["scenario"])}
    )


def _addresses(value: object) -> set[int]:
    found = set()
    for record in _walk_dicts(value):
        for field in ("address", "pc", "writer_pc"):
            address = record.get(field)
            if isinstance(address, str):
                try:
                    address = int(address, 0)
                except ValueError:
                    continue
            if isinstance(address, int) and BNE_TEXT_START <= address < BNE_TEXT_END:
                found.add(address)
    return found


def _covers_cycle(value: object, cycle: int) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) \
        and value >= cycle


def _snapshot_manifest_matches(source: Path, snapshot: dict[str, Any],
        manifest_source: Path, manifest: dict[str, Any],
        expected: dict[str, Any]) -> bool:
    if manifest.get("snapshot_sha256") != canonical_digest(snapshot) \
            or not _same_identity(manifest.get("identity"), expected) \
            or not _same_identity(
                snapshot.get("provenance", {}).get("identity"), expected):
        return False
    if manifest.get("executable", {}).get("sha256") != BNE_202_SHA256 \
            or manifest.get("capture", {}).get("network_disabled") is not True:
        return False
    artifacts = manifest.get("artifacts") or {}
    recorded = artifacts.get(source.name) or artifacts.get("snapshot.json")
    if recorded != file_identity(source):
        return False
    try:
        specification_path = _safe_bundle_file(
            manifest_source.parent, "specification.json")
        run_path = _safe_bundle_file(
            manifest_source.parent, "oracle-run-manifest.json")
        specification = load_specification(json.loads(
            specification_path.read_text(encoding="utf-8")))
        run = json.loads(run_path.read_text(encoding="utf-8"))
    except (CompilerError, CaptureError, json.JSONDecodeError):
        return False
    if artifacts.get("specification.json") != file_identity(specification_path) \
            or artifacts.get("oracle-run-manifest.json") != file_identity(run_path) \
            or manifest.get("specification_sha256") \
            != canonical_digest(specification) \
            or snapshot.get("provenance", {}).get("specification_sha256") \
            != canonical_digest(specification) \
            or specification.get("identity") != expected:
        return False
    validation = run.get("run", {}).get("validation", {})
    runtime = run.get("runtime", {})
    fixture = run.get("fixture", {})
    if run.get("oracle", {}).get("executable", {}).get("sha256") \
            != BNE_202_SHA256 or runtime.get("network_disabled") is not True:
        return False
    if fixture.get("id") != expected["fixture_id"] \
            or normalize_scenario(str(validation.get("scenario"))) \
            != normalize_scenario(expected["scenario"]) \
            or validation.get("initialization_seed") != expected["seed"] \
            or runtime.get("branch_witness_pause_cycle") != expected["cycle"] \
            or not _covers_cycle(validation.get("cycles"), expected["cycle"]):
        return False
    importer = manifest.get("capture", {}).get("importer", {})
    recorded_run = manifest.get("capture", {}).get("oracle_run_manifest", {})
    return (isinstance(importer.get("bytes"), int)
            and isinstance(importer.get("sha256"), str)
            and recorded_run.get("sha256") == file_identity(run_path)["sha256"]
            and recorded_run.get("bytes") == file_identity(run_path)["bytes"])


def _event_manifest_matches(source: Path, capture: dict[str, Any],
        manifest: dict[str, Any], expected: dict[str, Any]) -> bool:
    record = manifest.get("capture") or {}
    request = manifest.get("request") or {}
    oracle = manifest.get("oracle", {}).get("executable", {})
    run = manifest.get("oracle", {}).get("run_manifest", {})
    backend = manifest.get("backend", {})
    importer = manifest.get("harness", {}).get("capture_importer", {})
    tracer = manifest.get("harness", {}).get("tracer", {})
    subject = expected["subject"]["native_slot"]
    identity_matches = (
        request.get("case") == expected["case"]
        and request.get("fixture_id") == expected["fixture_id"]
        and normalize_scenario(str(request.get("scenario")))
            == normalize_scenario(expected["scenario"])
        and request.get("seed") == expected["seed"]
        and request.get("cycle") == expected["cycle"]
        and request.get("native_slot") == subject
        and run.get("scenario") is not None
        and normalize_scenario(str(run.get("scenario")))
            == normalize_scenario(expected["scenario"])
        and run.get("seed") == expected["seed"]
        and _covers_cycle(run.get("cycles"), expected["cycle"])
        and run.get("branch_pause_cycle") == expected["cycle"]
    )
    implementation_bound = all(
        isinstance(item.get("bytes"), int)
        and isinstance(item.get("sha256"), str)
        and len(item["sha256"]) == 64
        for item in (importer, tracer))
    return (record.get("name") == source.name
            and {"bytes": record.get("bytes"), "sha256": record.get("sha256")}
            == file_identity(source)
            and oracle.get("sha256") == BNE_202_SHA256
            and manifest.get("runtime", {}).get("network_disabled") is True
            and backend.get("branch_history") is True
            and backend.get("writer_watchpoint") is True
            and identity_matches and implementation_bound
            and expected["pc"] in _addresses(capture))


def _focused_proof_matches(source: Path, proof: dict[str, Any],
        manifest: dict[str, Any], expected: dict[str, Any]) -> bool:
    result = proof.get("result") or {}
    command = proof.get("command")
    artifacts = manifest.get("artifacts") or {}
    recorded = artifacts.get(source.name) or artifacts.get("focused-proof.json")
    producer = manifest.get("producer") or {}
    return (proof.get("schema") == FOCUSED_PROOF_SCHEMA
            and manifest.get("schema") == FOCUSED_PROOF_MANIFEST_SCHEMA
            and _same_identity(proof.get("identity"), expected)
            and _same_identity(manifest.get("identity"), expected)
            and manifest.get("proof_sha256") == canonical_digest(proof)
            and recorded == file_identity(source)
            and isinstance(command, list) and bool(command)
            and all(isinstance(item, str) and item for item in command)
            and result.get("status") == "pass"
            and result.get("exit_code") == 0
            and isinstance(producer.get("sha256"), str)
            and len(producer["sha256"]) == 64
            and isinstance(producer.get("bytes"), int))


def _authenticate_evidence(document: dict[str, Any], parsed: dict[str, Any]) \
        -> dict[str, dict[str, Any]]:
    """Authenticate only evidence bound to the mismatch's complete tuple."""
    result: dict[str, dict[str, Any]] = {}
    expected = document["identity"]
    manifests = [(name, value) for name, value in parsed.items()
                 if isinstance(value, dict)]
    for name, value in parsed.items():
        verdict = {"authenticated": False, "reason":
                   "no matching sealed manifest bound to the mismatch identity"}
        if not isinstance(value, dict):
            result[name] = verdict
            continue
        source = Path(document["evidence"][name]).expanduser().resolve()
        is_snapshot = isinstance(value.get("segments"), list) \
            and isinstance(value.get("registers"), dict)
        for manifest_name, manifest in manifests:
            manifest_source = Path(
                document["evidence"][manifest_name]).expanduser().resolve()
            if is_snapshot and _snapshot_manifest_matches(
                    source, value, manifest_source, manifest, expected):
                verdict = {"authenticated": True, "kind": "snapshot",
                           "manifest": manifest_name,
                           "identity": expected, "pc": expected["pc"]}
                break
            if isinstance(value.get("events"), list) \
                    and _event_manifest_matches(source, value, manifest, expected):
                verdict = {"authenticated": True,
                           "kind": "branch-or-decision",
                           "manifest": manifest_name,
                           "identity": expected, "pc": expected["pc"]}
                break
            if value.get("schema") == FOCUSED_PROOF_SCHEMA \
                    and _focused_proof_matches(
                        source, value, manifest, expected):
                verdict = {"authenticated": True, "kind": "focused-proof",
                           "manifest": manifest_name,
                           "identity": expected}
                break
        result[name] = verdict
    return result


def _event_cycle(event: object) -> int | None:
    if not isinstance(event, dict):
        return None
    for name in ("cycle", "issue_cycle", "world_cycle"):
        value = event.get(name)
        if isinstance(value, int):
            return value
    return None


def _causal_prefix(document: dict[str, Any]) -> dict[str, Any]:
    cycle = document["cycle"]
    scenario = document.get("scenario") or {}
    if not isinstance(scenario, dict):
        raise CompilerError("scenario must be an object")
    events = scenario.get("commands", scenario.get("events", []))
    if not isinstance(events, list):
        raise CompilerError("scenario commands/events must be a list")
    kept, future = [], []
    for event in events:
        at = _event_cycle(event)
        (kept if at is None or at <= cycle else future).append(event)
    return {
        "case": document["case"], "through_cycle": cycle,
        "clean_through": document["clean_through"],
        "first_mismatch_cycle": cycle,
        "scenario": {**scenario,
                     "commands" if "commands" in scenario else "events": kept},
        "removed_future_events": len(future),
        "minimality": "exact temporal prefix; entity reduction requires its own receipt",
        "proof": "the normalized input certifies equality through cycle-1",
    }


def _walk_dicts(value: object) -> Iterable[dict[str, Any]]:
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from _walk_dicts(child)
    elif isinstance(value, list):
        for child in value:
            yield from _walk_dicts(child)


def _native_pc(document: dict[str, Any], parsed: dict[str, Any],
               authentication: dict[str, Any]) -> int | None:
    """Return the identity PC only when authenticated evidence contains it."""
    expected = document["identity"]["pc"]
    for name, evidence in parsed.items():
        verdict = authentication.get(name, {})
        if not verdict.get("authenticated") \
                or verdict.get("kind") not in ("snapshot", "branch-or-decision"):
            continue
        if verdict.get("pc") == expected and expected in _addresses(evidence):
            return expected
        if verdict.get("kind") == "snapshot" \
                and evidence.get("entry") == expected:
            return expected
    return None


def _native_summary(document: dict[str, Any], parsed: dict[str, Any],
                    copied: dict[str, Path], authentication: dict[str, Any],
                    run_root: Path) -> dict[str, Any]:
    branches, memory, pcs = [], [], []
    for name, value in parsed.items():
        verdict = authentication.get(name, {})
        if not verdict.get("authenticated") \
                or verdict.get("kind") not in ("snapshot", "branch-or-decision"):
            continue
        top = value.get("top_branch") if isinstance(value, dict) else None
        for record in _walk_dicts(value):
            address = record.get("address")
            if isinstance(address, int) and BNE_TEXT_START <= address < BNE_TEXT_END:
                pcs.append(address)
            if ("taken" in record and isinstance(address, int)) \
                    or record is top:
                branches.append({"source": name, **record})
            if "before_hex" in record and "after_hex" in record:
                memory.append({"source": name, **record})
    captured = any(item.get("authenticated") and item.get("kind") in (
        "snapshot", "branch-or-decision") for item in authentication.values())
    return {
        "state": "captured" if captured else "missing",
        "files": {name: {"path": str(path.relative_to(run_root)),
                         **file_identity(path)} for name, path in copied.items()},
        "pc_candidates": sorted(set(pcs)),
        "branches": branches[:256],
        "memory_delta": memory[:256],
        "authentication": authentication,
        "missing": [] if captured else [
            "no authenticated native branch/decision/snapshot evidence supplied"],
    }


def _static_slice(executable: Path | None, pc: int | None,
                  destination: Path, analyzer: dict[str, Any]) -> dict[str, Any]:
    if pc is None:
        return {"state": "blocked", "reason": "no native decision/writer PC"}
    if executable is None:
        return {"state": "planned", "pc": pc,
                "reason": "pinned executable not supplied to compiler"}
    try:
        report = static.analyze(
            executable, pc, span=1024,
            backend=str(analyzer["backend"]), analyzer=analyzer)
    except (OSError, static.StaticAnalysisError) as failure:
        return {"state": "blocked", "pc": pc,
                "reason": f"{type(failure).__name__}: {failure}"}
    path = destination / "static-slice.json"
    _write_json(path, report)
    return {"state": "complete", "pc": pc,
            "backend": report["backend"], "instructions": len(report["instructions"]),
            "branches": len(report["branches"]), "calls": len(report["calls"]),
            "analyzer": report["analyzer"],
            "artifact": path.name}


def _snapshot_candidate(parsed: dict[str, Any], authentication: dict[str, Any]) \
        -> tuple[str, dict[str, Any]] | None:
    for name, document in parsed.items():
        if authentication.get(name, {}).get("authenticated") \
                and isinstance(document, dict) \
                and isinstance(document.get("segments"), list) \
                and isinstance(document.get("registers"), dict):
            return name, document
    return None


def _micro_oracle(document: dict[str, Any], parsed: dict[str, Any],
                  copied: dict[str, Path], authentication: dict[str, Any],
                  destination: Path) \
        -> dict[str, Any]:
    candidate = _snapshot_candidate(parsed, authentication)
    if candidate is not None:
        name, snapshot_document = candidate
        try:
            snapshot = micro.load_snapshot(
                snapshot_document,
                blob_root=copied[name].parent / "blobs",
                expected_executable=BNE_202_SHA256)
            result = micro.reproduce(snapshot)
        except Exception as failure:  # backend/import failures are evidence state
            return {"state": "blocked", "source": name,
                    "reason": f"{type(failure).__name__}: {failure}"}
        _write_json(destination / "micro-oracle-result.json", result)
        return {"state": "complete" if result.get("status") == "exact" else "failed",
                "source": name, "reproduction": result.get("status"),
                "mismatches": result.get("mismatches", []),
                "artifact": "micro-oracle-result.json"}

    # Branch Witness already contains exactly the localization from which the
    # reviewed snapshot plan is derived.  A draft is valuable, but remains
    # PLANNED until a person names the register/memory regions it could not
    # infer; it is never passed off as a replay result.
    for name, evidence in parsed.items():
        if authentication.get(name, {}).get("authenticated") \
                and isinstance(evidence, dict) and evidence.get("top_branch"):
            try:
                draft = specification_from_branch_witness(
                    evidence, case=document["case"])
            except Exception:
                continue
            _write_json(destination / "snapshot-spec.draft.json", draft)
            return {"state": "planned", "source": name,
                    "artifact": "snapshot-spec.draft.json",
                    "review_required": draft.get("review_required", [])}
    return {"state": "blocked", "reason":
            "no replayable native snapshot or localized branch witness"}


def _combat_phase(document: dict[str, Any]) -> str | None:
    target = document.get("coverage_target") or {}
    explicit = target.get("combat") if isinstance(target, dict) else None
    if isinstance(explicit, dict) and isinstance(explicit.get("phase"), str):
        return explicit["phase"]
    text = " ".join(str(value).lower() for value in (
        document.get("family"), document["finding"].get("kind"),
        document["finding"].get("field"), document["finding"].get("message")))
    for token, phase in (("projectile", "projectile-flight"),
                         ("damage", "damage"), ("hit point", "damage"),
                         ("death", "death"), ("free", "free"),
                         ("target", "acquire"), ("attack", "swing"),
                         ("chase", "chase")):
        if token in text:
            return phase
    return None


def _coverage(document: dict[str, Any], repository: Path,
              parsed: dict[str, Any]) -> dict[str, Any]:
    combat_requirements = combat.load_requirements(
        repository / "tools/bne-harness/combat-lifecycle-requirements.json")
    campaign_inventory = campaign.inventory(
        repository / "engine/src/main/resources/chonkcraft/missions.tsv")
    proofs = document.get("coverage_proofs") or {}
    combat_report = {"complete": False, "exact": 0,
                     "required": combat_requirements["required_cells"],
                     "debt": "no pinned-native combat lifecycle proof receipt"}
    campaign_report = {"complete": False, "exact": 0,
                       "required": campaign_inventory["triggers"],
                       "debt": "no pinned-native campaign lifecycle proof receipt"}
    if isinstance(proofs, dict):
        for lane, function, expected in (
            ("combat", lambda proof: combat.coverage(combat_requirements, proof),
             combat.PROOF_SCHEMA),
            ("campaign", lambda proof: campaign.coverage(campaign_inventory, proof),
             campaign.PROOF_SCHEMA)):
            raw = proofs.get(lane)
            proof = parsed.get(raw) if isinstance(raw, str) else None
            if isinstance(proof, dict) and proof.get("schema") == expected:
                if lane == "combat":
                    combat_report = function(proof)
                else:
                    campaign_report = function(proof)

    phase = _combat_phase(document)
    target = document.get("coverage_target") or {}
    explicit_combat = target.get("combat") if isinstance(target, dict) else None
    candidates = combat_requirements["cells"]
    if isinstance(explicit_combat, dict):
        candidates = [row for row in candidates if all(
            explicit_combat.get(key) in (None, row[key])
            for key in ("encounter", "stance", "phase"))]
    elif phase is not None:
        candidates = [row for row in candidates if row["phase"] == phase]
    else:
        candidates = []

    campaign_target = target.get("campaign") if isinstance(target, dict) else None
    campaign_candidates = []
    if isinstance(campaign_target, dict):
        for row in campaign_inventory["rows"]:
            if campaign_target.get("mission") not in (None, row["mission"]):
                continue
            if campaign_target.get("trigger") not in (None, row["trigger"]):
                continue
            if campaign_target.get("action_kind") not in (None, row["action_kind"]):
                continue
            campaign_candidates.append(row)
    return {
        "combat": {"coverage": combat_report,
                   "routed_cells": candidates[:32],
                   "route_is_exact": isinstance(explicit_combat, dict)},
        "campaign": {"coverage": campaign_report,
                     "routed_cells": campaign_candidates[:32],
                     "route_is_exact": isinstance(campaign_target, dict)},
    }


def _witness_gate(document: dict[str, Any], static_slice: dict[str, Any],
                  oracle: dict[str, Any]) -> dict[str, Any]:
    witnesses = document.get("witnesses") or []
    if not isinstance(witnesses, list):
        raise CompilerError("witnesses must be a list")
    positives = sum(1 for item in witnesses if isinstance(item, dict)
                    and item.get("kind") == "positive")
    controls = sum(1 for item in witnesses if isinstance(item, dict)
                   and item.get("kind") in ("negative", "heldout", "control"))
    native_rule = static_slice.get("state") == "complete" \
        and oracle.get("state") == "complete" \
        and oracle.get("reproduction") == "exact"
    return {
        "complete": native_rule or (positives >= 2 and controls >= 1),
        "method": "replayed-native-rule" if native_rule else "witness-family",
        "positive": positives, "negative_or_heldout": controls,
        "required": "exact native rule, or two positive and one negative/heldout witness",
        "witnesses": witnesses,
    }


def _focused_proof(document: dict[str, Any], parsed: dict[str, Any],
                   authentication: dict[str, Any]) -> dict[str, Any]:
    name = document.get("focused_proof")
    if not isinstance(name, str):
        return {"state": "missing", "reason":
                "no sealed focused-proof evidence key supplied"}
    proof = parsed.get(name)
    verdict = authentication.get(name, {})
    if not isinstance(proof, dict) or not verdict.get("authenticated") \
            or verdict.get("kind") != "focused-proof":
        return {"state": "blocked", "source": name, "reason":
                "focused proof is not sealed and bound to this mismatch"}
    return {
        "state": "complete", "source": name,
        "command": list(proof["command"]),
        "result": proof["result"],
        "manifest": verdict["manifest"],
    }


def _commands(document: dict[str, Any], run_root: Path,
              executable: Path | None, pc: int | None,
              micro_result: dict[str, Any],
              focused_proof: dict[str, Any]) -> dict[str, list[str]]:
    q = shlex.quote
    result = {
        "recompile": [
            "python3 tools/bne-harness/scripts/bne_divergence_compiler.py "
            f"{q(str(run_root / 'inputs' / 'mismatch.json'))} "
            f"--artifact-root {q(str(run_root.parent.parent))}"
            + (f" --native-executable {q(str(executable))}" if executable else "")],
        "focused": [],
        "global": [
            "python3 scripts/run-bne-playability-gate.py",
            "scripts/check-bne-next-level-gate.sh --require-certified",
        ],
    }
    if focused_proof.get("state") == "complete":
        result["focused"].append(shlex.join(focused_proof["command"]))
    if executable is not None and pc is not None:
        result["focused"].append(
            "python3 tools/bne-harness/scripts/bne_static_analysis.py "
            f"{q(str(executable))} 0x{pc:08x} --output "
            f"{q(str(run_root / 'static-slice.recheck.json'))}")
    if micro_result.get("source"):
        source = run_root / "inputs" / Path(
            document["evidence"][micro_result["source"]]).name
        # The copied filename is keyed by evidence name, not necessarily the
        # source basename. Find it without inserting a shell placeholder.
        matches = sorted((run_root / "inputs").glob(
            micro_result["source"] + ".*"))
        if matches and micro_result.get("reproduction"):
            result["focused"].append(
                "python3 tools/bne-harness/scripts/bne_java.py micro-oracle "
                f"{q(str(matches[0]))} --artifact-root "
                f"{q(str(run_root / 'micro-oracle-recheck'))}")
    return result


def _format_next(work: dict[str, Any]) -> str:
    lines = [
        "# Native decision work order", "",
        f"Case **{work['mismatch']['case']}**, first mismatch cycle "
        f"**{work['mismatch']['cycle']}**; clean through "
        f"**{work['mismatch']['clean_through']}**.", "",
        f"Overall state: **{work['state'].upper()}**.", "",
        "## Evidence", "",
        f"- Native capture: **{work['native']['state']}**",
        f"- Static slice: **{work['static_slice']['state']}**",
        f"- Micro-oracle: **{work['micro_oracle']['state']}**",
        f"- Witness gate: **{'PASS' if work['witness_gate']['complete'] else 'OPEN'}**",
        f"- Focused proof: **{work['focused_proof']['state']}**",
        "", "## Coverage obligations", "",
        f"- Combat: **{work['coverage']['combat']['coverage']['exact']} / "
        f"{work['coverage']['combat']['coverage']['required']}** certified",
        f"- Campaign: **{work['coverage']['campaign']['coverage']['exact']} / "
        f"{work['coverage']['campaign']['coverage']['required']}** certified",
        "", "## Proof commands", "",
    ]
    for lane in ("focused", "global"):
        lines.extend([f"### {lane.title()}", "", "```sh"])
        lines.extend(work["proof_commands"][lane] or ["# no focused proof command supplied"])
        lines.extend(["```", ""])
    lines.extend([
        "A fix is not accepted because this work order exists. The focused "
        "native witness, held-out witnesses, playability gate, certified "
        "185-cell combat matrix and certified 137-cell campaign matrix must "
        "all remain green.", "",
    ])
    return "\n".join(lines)


def _pointer(work: dict[str, Any], destination: Path,
             artifact_root: Path) -> dict[str, Any]:
    return {
        "schema": POINTER_SCHEMA,
        "run": str(destination.relative_to(artifact_root)),
        "request_sha256": work["request_sha256"],
        "manifest_identity": file_identity(destination / "manifest.json"),
        "work_order_identity": file_identity(destination / "work-order.json"),
        "case": work["mismatch"]["case"],
        "cycle": work["mismatch"]["cycle"],
        "identity": work["identity"],
    }


def _publish_pointer(artifact_root: Path, pointer: dict[str, Any]) -> None:
    temporary = artifact_root / ".latest.tmp"
    _write_json(temporary, pointer)
    os.replace(temporary, artifact_root / "latest.json")


def _verify_run(destination: Path, *, expected_request: str | None = None) \
        -> tuple[dict[str, Any], dict[str, Any]]:
    if destination.is_symlink() or not destination.is_dir():
        raise CompilerError(f"decision-lab run is missing or unsafe: {destination}")
    manifest_path = destination / "manifest.json"
    work_path = destination / "work-order.json"
    if manifest_path.is_symlink() or work_path.is_symlink() \
            or not manifest_path.is_file() or not work_path.is_file():
        raise CompilerError("decision-lab run lacks safe manifest/work-order files")
    linked = next((path for path in destination.rglob("*")
                   if path.is_symlink()), None)
    if linked is not None:
        raise CompilerError(
            f"content-addressed work-order contains a symbolic link: {linked}")
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        work = json.loads(work_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as failure:
        raise CompilerError(f"decision-lab run contains invalid JSON: {failure}")
    request_sha = manifest.get("request_sha256")
    if manifest.get("schema") != WORK_ORDER_SCHEMA \
            or manifest.get("kind") != "native-decision-work-order" \
            or not isinstance(request_sha, str) \
            or canonical_digest(manifest.get("request")) != request_sha \
            or destination.name != request_sha \
            or expected_request not in (None, request_sha):
        raise CompilerError("content-addressed work-order request identity changed")
    actual_inventory = _inventory(destination)
    if manifest.get("artifacts") != actual_inventory:
        raise CompilerError("content-addressed work-order inventory changed")
    if work.get("schema") != WORK_ORDER_SCHEMA \
            or work.get("request_sha256") != request_sha:
        raise CompilerError("work-order does not belong to its manifest")
    return work, manifest


def resolve_pointer(artifact_root: Path, pointer_path: Path | None = None) \
        -> tuple[dict[str, Any], Path]:
    """Resolve pointer→manifest→work-order and authenticate the full closure."""
    artifact_root = artifact_root.expanduser().resolve()
    pointer_path = (pointer_path or artifact_root / "latest.json").expanduser()
    if pointer_path.is_symlink() or not pointer_path.is_file() \
            or pointer_path.resolve().parent != artifact_root:
        raise CompilerError(f"decision-lab pointer is missing or unsafe: {pointer_path}")
    try:
        pointer = json.loads(pointer_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as failure:
        raise CompilerError(
            f"decision-lab pointer contains invalid JSON: {failure}") from failure
    relative = pointer.get("run")
    if pointer.get("schema") != POINTER_SCHEMA or not isinstance(relative, str):
        raise CompilerError("decision-lab pointer schema is invalid")
    candidate = artifact_root / relative
    if candidate.is_symlink():
        raise CompilerError("decision-lab pointer names a symbolic link")
    destination = candidate.resolve()
    runs_path = artifact_root / "runs"
    if runs_path.is_symlink():
        raise CompilerError("decision-lab runs directory is a symbolic link")
    runs = runs_path.resolve()
    if not destination.is_relative_to(runs) or destination.parent != runs:
        raise CompilerError("decision-lab pointer escapes its runs directory")
    work, _ = _verify_run(
        destination, expected_request=pointer.get("request_sha256"))
    if pointer.get("manifest_identity") != file_identity(
            destination / "manifest.json") \
            or pointer.get("work_order_identity") != file_identity(
                destination / "work-order.json") \
            or pointer.get("case") != work["mismatch"]["case"] \
            or pointer.get("cycle") != work["mismatch"]["cycle"] \
            or pointer.get("identity") != work.get("identity"):
        raise CompilerError("decision-lab pointer closure changed")
    return work, destination


def compile_mismatch(mismatch_path: Path, *, artifact_root: Path,
                     repository: Path, native_executable: Path | None = None) \
        -> tuple[dict[str, Any], Path]:
    repository = repository.expanduser().resolve()
    raw_artifact_root = artifact_root.expanduser()
    if raw_artifact_root.is_symlink():
        raise CompilerError("decision-lab artifact root is a symbolic link")
    artifact_root = raw_artifact_root.resolve()
    mismatch_path = mismatch_path.expanduser().resolve()
    document = _normalize(json.loads(mismatch_path.read_text(encoding="utf-8")))
    try:
        analyzer = static.analyzer_identity(backend="auto")
    except static.StaticAnalysisError as failure:
        raise CompilerError(str(failure)) from failure
    identities = _identity_inputs(
        document, repository, native_executable, analyzer)
    request = {"schema": WORK_ORDER_SCHEMA, "mismatch": document,
               "inputs": identities}
    request_sha256 = canonical_digest(request)
    destination = artifact_root / "runs" / request_sha256
    with _lock(artifact_root):
        manifest_path = destination / "manifest.json"
        if destination.exists():
            work, _ = _verify_run(
                destination, expected_request=request_sha256)
            _publish_pointer(artifact_root, _pointer(
                work, destination, artifact_root))
            return work, destination

        runs = artifact_root / "runs"
        if runs.is_symlink():
            raise CompilerError("decision-lab runs directory is a symbolic link")
        runs.mkdir(parents=True, exist_ok=True)
        staging = Path(tempfile.mkdtemp(prefix=".decision-lab-", dir=runs))
        try:
            inputs = staging / "inputs"
            inputs.mkdir()
            _write_json(inputs / "mismatch.json", document)
            copied, parsed = _copy_evidence(document, staging)
            authentication = _authenticate_evidence(document, parsed)
            prefix = _causal_prefix(document)
            _write_json(staging / "causal-prefix.json", prefix)
            pc = _native_pc(document, parsed, authentication)
            native = _native_summary(
                document, parsed, copied, authentication, staging)
            executable = native_executable.expanduser().resolve() \
                if native_executable is not None else None
            static_slice = _static_slice(executable, pc, staging, analyzer)
            oracle = _micro_oracle(
                document, parsed, copied, authentication, staging)
            coverage = _coverage(document, repository, parsed)
            witnesses = _witness_gate(document, static_slice, oracle)
            focused = _focused_proof(document, parsed, authentication)
            commands = _commands(
                document, destination, executable, pc, oracle, focused)
            state = "ready" if (
                native["state"] == "captured"
                and static_slice["state"] == "complete"
                and oracle["state"] == "complete"
                and witnesses["complete"]
                and focused["state"] == "complete"
            ) else "evidence-open"
            work = {
                "schema": WORK_ORDER_SCHEMA,
                "request_sha256": request_sha256,
                "created_at": datetime.now(timezone.utc).isoformat(),
                "identity": document["identity"],
                "state": state,
                "mismatch": {key: document.get(key) for key in (
                    "case", "cycle", "clean_through", "family", "finding")},
                "causal_prefix": {"artifact": "causal-prefix.json",
                                  **{key: prefix[key] for key in (
                                      "through_cycle", "removed_future_events",
                                      "minimality")}},
                "native": native, "static_slice": static_slice,
                "micro_oracle": oracle, "witness_gate": witnesses,
                "focused_proof": focused,
                "coverage": coverage, "proof_commands": commands,
                "acceptance": {
                    "engine_edit_allowed": state == "ready",
                    "focused_regression_sealed": focused["state"] == "complete",
                    "requires": [
                        "native PC/branch/memory evidence",
                        "static function slice from pinned executable",
                        "exact micro-oracle reproduction",
                        "sealed focused regression proof bound to this identity",
                        "global playability gate",
                        "185/185 combat and 137/137 campaign certification for release GREEN",
                    ],
                },
            }
            _write_json(staging / "work-order.json", work)
            _write_text(staging / "NEXT.md", _format_next(work))
            manifest = {
                "schema": WORK_ORDER_SCHEMA, "kind": "native-decision-work-order",
                "request_sha256": request_sha256, "request": request,
                "artifacts": _inventory(staging),
            }
            _write_json(staging / "manifest.json", manifest)
            if destination.exists():
                raise CompilerError(
                    "immutable decision-lab destination appeared during compile")
            os.replace(staging, destination)
            staging = None
        finally:
            if staging is not None and staging.exists():
                shutil.rmtree(staging)
        work, _ = _verify_run(destination, expected_request=request_sha256)
        _publish_pointer(artifact_root, _pointer(
            work, destination, artifact_root))
    return work, destination


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mismatch", type=Path)
    parser.add_argument("--artifact-root", type=Path,
                        default=Path("tools/bne-harness/work/decision-lab"))
    parser.add_argument("--repository", type=Path,
                        default=Path(__file__).resolve().parents[3])
    parser.add_argument("--native-executable", type=Path)
    args = parser.parse_args(argv)
    work, run_root = compile_mismatch(
        args.mismatch, artifact_root=args.artifact_root,
        repository=args.repository, native_executable=args.native_executable)
    print((run_root / "NEXT.md").read_text(encoding="utf-8"), end="")
    print(f"Durable native decision work order: {run_root}")
    return 0 if work["state"] == "ready" else 2


if __name__ == "__main__":
    raise SystemExit(main())
