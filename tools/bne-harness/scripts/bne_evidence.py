#!/usr/bin/env python3
"""Retain the inputs a tied earliest blocker needs, at the moment it is proved."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
from typing import Any


EVIDENCE_SCHEMA = 2

#: One survey per blocker, each pointing at the traces retained beside it, so
#: an analysis can run months later without the corpus work directory.
BLOCKER_DIRECTORY = "blockers"
CAPSULE_DIRECTORY = "source-capsule"

#: Retained surveys name their traces relative to the receipt's `inputs`
#: directory. They used to hold absolute paths, which were absolute into the
#: `.gate-acceptance-*` staging directory that `os.replace` renames away the
#: instant the receipt is sealed -- so every retained survey pointed at a
#: directory that no longer existed, and a compile reported the frame blocked
#: while the bytes sat beside it. Nothing writes an absolute path here now, and
#: `resolve_retained_survey` is the only thing that turns one into a real path.
PATH_BASE = "receipt-inputs"


def file_identity(path: Path) -> dict[str, int | str]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
            size += len(block)
    return {"bytes": size, "sha256": digest.hexdigest()}


def _write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8",
    )


def _authenticated_copy(recorded: dict[str, Any], destination: Path) \
        -> tuple[dict[str, Any] | None, str | None]:
    """Copy a recorded artifact only after its identity still checks out.

    A survey names absolute paths inside the corpus work directory, and the
    next survey overwrites them. Trusting the path is how a receipt ends up
    describing a trace that no longer exists, which is why the sha256 is
    checked before a single byte is copied.
    """
    location = recorded.get("path")
    if not isinstance(location, str):
        return None, "the survey recorded no path for this artifact"
    source = Path(location).expanduser()
    if not source.is_file():
        return None, f"the recorded artifact is gone: {source}"
    expected = {key: recorded[key] for key in ("bytes", "sha256")
                if key in recorded}
    actual = file_identity(source)
    if expected and actual != expected:
        return None, (
            f"the recorded artifact changed since the survey: {source} "
            f"is {actual['sha256'][:12]}, the survey proved "
            f"{str(expected.get('sha256'))[:12]}"
        )
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)
    return {"origin": str(source), **actual}, None


def _fixture_reference(index_path: Path, case_id: str,
        fixture_id: str | None) -> tuple[dict[str, Any] | None, str | None]:
    """Name the sealed fixture without copying a native capture into a receipt."""
    if not index_path.is_file():
        return None, f"the corpus index is gone: {index_path}"
    index = json.loads(index_path.read_text(encoding="utf-8"))
    indexed = next((record for record in index.get("cases", [])
                    if record.get("id") == case_id), None)
    if indexed is None:
        return None, f"the corpus index no longer lists {case_id}"
    relative = Path(indexed["fixture"]["path"])
    fixture = (index_path.parent / relative).resolve()
    if not fixture.is_relative_to(index_path.parent.resolve()):
        return None, f"the corpus index names a fixture outside itself: {case_id}"
    if not fixture.is_file():
        return None, f"the sealed fixture is gone: {fixture}"
    actual = file_identity(fixture)
    expected = {key: indexed["fixture"][key] for key in ("bytes", "sha256")}
    if actual != expected:
        return None, f"the sealed fixture changed on disk: {fixture}"
    if fixture_id is not None and indexed.get("fixture_id") != fixture_id:
        return None, (
            "the corpus index and the survey name different fixture identities "
            f"for {case_id}"
        )
    return {
        "id": indexed.get("fixture_id"),
        "path": str(fixture),
        "index": str(index_path),
        "retained": False,
        "reason": "a sealed native capture is referenced, never copied",
        **actual,
    }, None


def _recovery_command(case_id: str, index_path: Path) -> str:
    """The command that regenerates this blocker's evidence, as typed.

    `survey` takes its corpus index positionally. This used to emit
    `--index PATH`, which argparse rejects outright, so every recovery
    instruction the pipeline printed was unrunnable -- which is worse than
    printing none, because it reads like it was checked.
    """
    return (
        "python3 tools/bne-harness/scripts/bne_java.py survey "
        f"{shlex.quote(str(index_path))} --case {shlex.quote(case_id)} "
        "--through 80 --output-dir tools/bne-harness/work/java-corpus/recover"
    )


def retain_blocker_evidence(candidate: dict[str, Any], frontier: dict[str, Any],
        staging: Path, *, repository: Path | None = None) -> dict[str, Any]:
    """Seal the minimum authenticated inputs for every tied earliest blocker.

    A direct gate receipt used to copy the 52-case survey and nothing else, so
    the accepted frontier arrived with `packets: []` and the only usable
    forensic packet in the tree was three cycles stale. Everything a packet
    needs for the blockers that are actually holding the frontier is retained
    here instead, and nothing for the forty-nine cases that are not.
    """
    staging = Path(staging)
    blockers = frontier.get("tied_blockers") or []
    records = {record.get("id"): record for record in candidate.get("cases", [])
               if isinstance(record, dict)}
    index_path = Path(str(candidate.get("index", ""))).expanduser()
    retained: list[dict[str, Any]] = []

    for blocker in blockers:
        case_id = str(blocker.get("case"))
        record = records.get(case_id)
        directory = staging / BLOCKER_DIRECTORY / case_id
        missing: list[str] = []
        evidence: dict[str, Any] = {
            "schema": EVIDENCE_SCHEMA,
            "case": case_id,
            "rank": blocker.get("rank"),
            "first_divergence_cycle": blocker.get("cycle"),
            "recommended_tool": blocker.get("recommended_tool"),
        }
        if record is None:
            evidence.update({
                "state": "unavailable",
                "missing": ["survey_record"],
                "recovery": _recovery_command(case_id, index_path),
            })
            retained.append(evidence)
            continue

        evidence["findings"] = [item for item in record.get("findings", [])
                                if isinstance(item, dict)]
        evidence["compared_cycles"] = record.get("compared_cycles")
        evidence["fixture_id"] = record.get("fixture_id")

        trace, failure = _authenticated_copy(
            record.get("java_trace") or {}, directory / "java.trace.txt",
        )
        if trace is None:
            missing.append(f"java_trace: {failure}")
        else:
            evidence["java_trace"] = {
                "path": str(
                    (directory / "java.trace.txt").relative_to(staging)),
                **trace,
            }

        process: dict[str, Any] = {}
        for stream, recorded in (record.get("java_process_output") or {}).items():
            copied, failure = _authenticated_copy(
                recorded if isinstance(recorded, dict) else {},
                directory / f"java.{stream}.txt",
            )
            if copied is None:
                missing.append(f"java_{stream}: {failure}")
            else:
                process[stream] = {
                    "path": str(
                        (directory / f"java.{stream}.txt").relative_to(staging)),
                    **copied,
                }
        evidence["java_process_output"] = process

        fixture, failure = _fixture_reference(
            index_path, case_id, record.get("fixture_id"),
        )
        if fixture is None:
            missing.append(f"fixture: {failure}")
        else:
            evidence["fixture"] = fixture

        if missing:
            evidence.update({
                "state": "unavailable",
                "missing": missing,
                "recovery": _recovery_command(case_id, index_path),
            })
            retained.append(evidence)
            continue

        # A one-case survey whose paths point at the copies beside it, so the
        # packet generator authenticates the retained bytes and never reaches
        # back into a work directory the next survey will overwrite.
        packet_record = dict(record)
        packet_record["java_trace"] = {
            "path": str((directory / "java.trace.txt").relative_to(staging)),
            "path_base": PATH_BASE,
            **{key: trace[key] for key in ("bytes", "sha256")},
        }
        packet_record["java_process_output"] = {
            stream: {
                "path": str(
                    (directory / f"java.{stream}.txt").relative_to(staging)),
                "path_base": PATH_BASE,
                **{key: value[key] for key in ("bytes", "sha256")},
            }
            for stream, value in process.items()
        }
        blocker_survey = {
            "path_base": PATH_BASE,
            "schema": candidate.get("schema", 1),
            "comparison_tier": candidate.get("comparison_tier"),
            "through": candidate.get("through"),
            "coverage": candidate.get("coverage"),
            "engine": candidate.get("engine"),
            "asset_source": candidate.get("asset_source"),
            # Packet recovery commands need the exact ChonkCraft source tree used
            # by the survey.  Omitting runtime here made a sealed blocker lose
            # that identity and fall back to the unrunnable /path/to/chonkcraft.
            "runtime": candidate.get("runtime"),
            "index": str(index_path),
            "counts": {"clean": 0, "divergent": 1, "failed": 0},
            "cases": [packet_record],
            "retained_from": "tied-earliest-blocker",
        }
        survey_path = directory / "survey.json"
        _write_json(survey_path, blocker_survey)
        evidence.update({
            "state": "retained",
            "survey": str(survey_path.relative_to(staging)),
            "survey_identity": file_identity(survey_path),
        })
        retained.append(evidence)

    capsule: dict[str, Any] | None = None
    capsule_failure: str | None = None
    if repository is not None:
        import bne_capsule

        try:
            manifest = bne_capsule.seal(
                repository, staging / CAPSULE_DIRECTORY)
            capsule = {
                "path": CAPSULE_DIRECTORY,
                "capsule_sha256": manifest["capsule_sha256"],
                "base_head": manifest["base_head"],
                "untracked_inputs": len(manifest["untracked"]),
                "engine_identity": manifest["engine_identity"],
                "replayable": True,
            }
        except Exception as failure:
            # A capsule problem must never reject a valid parity improvement,
            # so the gate still passes -- but a swallowed failure that then
            # reads as "recorded before capsules existed" is how a proof gets
            # believed to be replayable when it is not. It says so instead.
            capsule_failure = str(failure)
            capsule = {
                "state": "capsule-failed",
                "replayable": False,
                "reason": capsule_failure,
                "recovery": (
                    "python3 tools/bne-harness/scripts/bne_java.py capsule "
                    "seal .bne-frontier-evidence/capsule-recheck"
                ),
            }

    return {
        "schema": EVIDENCE_SCHEMA,
        "blockers": retained,
        "retained_count": sum(1 for item in retained
                              if item.get("state") == "retained"),
        "unavailable_count": sum(1 for item in retained
                                 if item.get("state") != "retained"),
        "source_capsule": capsule,
        "source_capsule_failure": capsule_failure,
    }


class RetainedEvidenceError(RuntimeError):
    """A retained survey could not be resolved against its sealed receipt."""


def _resolve_one(record: dict[str, Any], inputs_root: Path,
        survey_directory: Path, label: str) -> dict[str, Any]:
    """Turn one retained artifact reference into a verified absolute path."""
    recorded = record.get("path")
    if not isinstance(recorded, str) or not recorded:
        raise RetainedEvidenceError(f"{label} has no retained path")
    base = record.get("path_base")
    if base == PATH_BASE:
        root = inputs_root
    elif base is None:
        # Sealed before receipt-relative paths existed, so `path` is absolute
        # into a staging directory that no longer exists. The bytes are beside
        # the survey that names them; resolving by that basename is checked
        # against the sha256 the survey itself recorded, so nothing is guessed.
        root = survey_directory
        recorded = Path(recorded).name
    else:
        raise RetainedEvidenceError(
            f"{label} declares an unknown path base {base!r}")
    resolved = (root / recorded).resolve()
    if not resolved.is_relative_to(inputs_root.resolve()):
        raise RetainedEvidenceError(
            f"{label} resolves outside the receipt: {recorded}")
    if resolved.is_symlink() or not resolved.is_file():
        raise RetainedEvidenceError(f"{label} is missing from the receipt: {recorded}")
    expected = {key: record[key] for key in ("bytes", "sha256") if key in record}
    actual = file_identity(resolved)
    if expected and actual != expected:
        raise RetainedEvidenceError(
            f"{label} does not match the identity the receipt sealed: {recorded}")
    return {**{key: value for key, value in record.items()
               if key != "path_base"},
            "path": str(resolved)}


def resolve_retained_survey(survey_path: Path, inputs_root: Path) \
        -> dict[str, Any]:
    """Read a retained survey and point it at the bytes sealed beside it.

    Resolution happens here and only here, after the receipt has been promoted
    into place, because the staging directory the evidence was written in is
    renamed away the moment the receipt is sealed.
    """
    survey_path = Path(survey_path).resolve()
    inputs_root = Path(inputs_root).resolve()
    if not survey_path.is_relative_to(inputs_root):
        raise RetainedEvidenceError(
            f"the retained survey lies outside the receipt: {survey_path}")
    survey = json.loads(survey_path.read_text(encoding="utf-8"))
    directory = survey_path.parent
    resolved_cases = []
    for record in survey.get("cases", []):
        case = str(record.get("id"))
        trace = record.get("java_trace")
        if not isinstance(trace, dict):
            raise RetainedEvidenceError(f"{case} retained no Java trace")
        streams = {}
        for name, stream in (record.get("java_process_output") or {}).items():
            streams[name] = _resolve_one(
                stream, inputs_root, directory, f"{case} java {name}")
        resolved_cases.append({
            **record,
            "java_trace": _resolve_one(
                trace, inputs_root, directory, f"{case} java trace"),
            "java_process_output": streams,
        })
    return {**{key: value for key, value in survey.items()
               if key != "path_base"},
            "cases": resolved_cases}


def describe(evidence: dict[str, Any]) -> str:
    lines = [
        f"{evidence['retained_count']} blocker(s) retained, "
        f"{evidence['unavailable_count']} unavailable"
    ]
    capsule = evidence.get("source_capsule")
    if isinstance(capsule, dict) and capsule.get("state") == "capsule-failed":
        lines.append(
            "  WARNING: the source capsule failed, so this proof is NOT "
            "replayable -- " + str(capsule.get("reason"))
        )
        lines.append(f"    recheck with: {capsule.get('recovery')}")
    elif capsule is None:
        lines.append(
            "  WARNING: no source capsule was sealed, so this proof is NOT "
            "replayable"
        )
    for blocker in evidence.get("blockers", []):
        if blocker.get("state") == "retained":
            lines.append(
                f"  {blocker['case']} @{blocker['first_divergence_cycle']}: "
                f"{len(blocker.get('findings', []))} finding(s) retained"
            )
        else:
            lines.append(
                f"  {blocker['case']}: unavailable -- "
                + "; ".join(blocker.get("missing", []))
            )
            lines.append(f"    recover with: {blocker.get('recovery')}")
    return "\n".join(lines)
