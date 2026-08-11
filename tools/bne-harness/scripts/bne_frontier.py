#!/usr/bin/env python3
"""Turn an accepted gate receipt into today's routed diagnostic work order."""

from __future__ import annotations

from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import tempfile
import time
from typing import Any

import bne_capsule
import bne_identity
import bne_router


FRONTIER_SCHEMA = 1
FRONTIER_KIND = "frontier-evidence"
POINTER_NAME = "latest.json"
STATUS_NAME = "STATUS.json"
NEXT_NAME = "NEXT.md"
ROUTES_NAME = "ROUTES.md"
MANIFEST_NAME = "manifest.json"

HARNESS = "python3 tools/bne-harness/scripts/bne_java.py"

#: The analysis code whose identity the compiled evidence depends on. Edit any
#: of these and the same receipt must recompile rather than return a stale hit.
ANALYSIS_MODULES = (
    "bne_frontier.py", "bne_router.py", "bne_packet.py", "bne_evidence.py",
    "bne_identity.py", "bne_capsule.py", "bne_whychain.py",
)


class FrontierError(RuntimeError):
    """The accepted receipt could not be authenticated or compiled."""


def file_identity(path: Path) -> dict[str, int | str]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
            size += len(block)
    return {"bytes": size, "sha256": digest.hexdigest()}


def canonical_digest(value: object) -> str:
    encoded = json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=True,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def _write_text(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value, encoding="utf-8")


def _publish_atomic(path: Path, value: object) -> None:
    """Replace a pointer in one step, so a reader never sees half of it."""
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary = tempfile.mkstemp(dir=path.parent, prefix=".publish-")
    try:
        with os.fdopen(handle, "w", encoding="utf-8") as stream:
            stream.write(json.dumps(value, indent=2, sort_keys=True) + "\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        temporary = None
    finally:
        if temporary is not None:
            Path(temporary).unlink(missing_ok=True)


@contextmanager
def _compile_lock(output_root: Path):
    """Serialize concurrent compiles of the same evidence root."""
    output_root.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(
        output_root / ".frontier.lock", os.O_CREAT | os.O_RDWR, 0o600)
    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX)
        yield
    finally:
        fcntl.flock(descriptor, fcntl.LOCK_UN)
        os.close(descriptor)


def analysis_identity() -> dict[str, Any]:
    here = Path(__file__).resolve().parent
    identities: dict[str, Any] = {}
    for name in ANALYSIS_MODULES:
        path = here / name
        identities[name] = file_identity(path) if path.is_file() else None
    return identities


def inventory(root: Path) -> dict[str, dict[str, int | str]]:
    root = root.resolve()
    result: dict[str, dict[str, int | str]] = {}
    for path in sorted(root.rglob("*")):
        if path.is_symlink() or not path.is_file():
            continue
        if path.name == MANIFEST_NAME and path.parent == root:
            continue
        result[str(path.relative_to(root))] = file_identity(path)
    return result


def _authenticate_inventory(root: Path, manifest: dict[str, Any]) -> None:
    artifacts = manifest.get("artifacts")
    if not isinstance(artifacts, dict) or not artifacts:
        raise FrontierError(f"compiled evidence has no inventory: {root}")
    for relative, expected in artifacts.items():
        path = (root / relative).resolve()
        if not path.is_relative_to(root.resolve()):
            raise FrontierError(f"unsafe compiled artifact path: {relative}")
        if path.is_symlink() or not path.is_file() \
                or file_identity(path) != expected:
            raise FrontierError(f"compiled artifact identity changed: {relative}")


# --------------------------------------------------------------------------
# Reading the accepted proof


def resolve_receipt(target: Path | None, artifact_root: Path) \
        -> tuple[Path, Path, dict[str, Any]]:
    """Follow an accepted pointer or a manifest to an authenticated receipt."""
    artifact_root = artifact_root.expanduser().resolve()
    if target is None:
        target = artifact_root / "latest-accepted.json"
    target = Path(target).expanduser().resolve()
    if target.is_dir():
        target = target / "latest-accepted.json"
    if not target.is_file():
        raise FrontierError(f"no accepted pointer or manifest at {target}")
    document = json.loads(target.read_text(encoding="utf-8"))

    if "manifest" in document and "manifest_identity" in document:
        relative = document["manifest"]
        identity = document["manifest_identity"]
        if not isinstance(relative, str) or not isinstance(identity, dict):
            raise FrontierError(f"malformed accepted pointer: {target}")
        root = target.parent
        manifest_path = (root / relative).resolve()
        if not manifest_path.is_relative_to(root):
            raise FrontierError(
                f"accepted pointer names a manifest outside its root: {target}")
        if not manifest_path.is_file():
            raise FrontierError(f"the accepted manifest is gone: {manifest_path}")
        if file_identity(manifest_path) != identity:
            raise FrontierError(
                f"the accepted manifest changed since it was promoted: "
                f"{manifest_path}")
    else:
        manifest_path = target

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("kind") not in {"gate-acceptance", "triage"}:
        # Triage receipts written before the kind field exists are the richest
        # evidence in the tree -- they carry packets. They are recognized by
        # the shape they do have, and never by assuming what is missing.
        legacy_triage = (
            manifest.get("kind") is None
            and manifest.get("schema") == 1
            and isinstance(manifest.get("frontier"), dict)
            and isinstance(manifest.get("candidate"), dict)
            and isinstance(manifest.get("artifacts"), dict)
        )
        if not legacy_triage:
            raise FrontierError(
                f"not a gate or triage receipt: {manifest_path} "
                f"(kind {manifest.get('kind')!r})")
        manifest = {**manifest, "kind": "legacy-triage"}
    run_root = manifest_path.parent
    recomputed = canonical_digest(manifest.get("request"))
    if recomputed != manifest.get("request_sha256"):
        raise FrontierError(f"receipt request identity changed: {manifest_path}")
    return manifest_path, run_root, manifest


def _authenticate_receipt_artifacts(run_root: Path,
        manifest: dict[str, Any]) -> list[str]:
    """Check every sealed byte the receipt claims, and report what is gone."""
    damaged: list[str] = []
    for relative, expected in (manifest.get("artifacts") or {}).items():
        path = (run_root / relative).resolve()
        if not path.is_relative_to(run_root.resolve()):
            raise FrontierError(f"receipt names an artifact outside itself: {relative}")
        if path.is_symlink() or not path.is_file():
            damaged.append(f"{relative}: missing")
        elif file_identity(path) != expected:
            damaged.append(f"{relative}: identity changed")
    return damaged


def _candidate_survey(run_root: Path, manifest: dict[str, Any]) -> Path:
    relative = manifest.get("candidate", {}).get("survey")
    if not isinstance(relative, str):
        raise FrontierError("the receipt does not name its candidate survey")
    path = (run_root / relative).resolve()
    if not path.is_relative_to(run_root.resolve()) or not path.is_file():
        raise FrontierError(f"the candidate survey is gone: {relative}")
    expected = manifest.get("candidate", {}).get("identity")
    if isinstance(expected, dict) and file_identity(path) != expected:
        raise FrontierError(
            f"the candidate survey changed since it was accepted: {path}")
    return path


def _capsule_state(run_root: Path, manifest: dict[str, Any]) -> dict[str, Any]:
    evidence = manifest.get("blocker_evidence")
    capsule = evidence.get("source_capsule") if isinstance(evidence, dict) else None
    if isinstance(capsule, dict) and capsule.get("state") == "capsule-failed":
        # Distinct from a legacy receipt on purpose: this proof was recorded
        # by a version that seals capsules, and the seal failed. Reporting it
        # as "predates capsules" would hide a real problem behind history.
        return {**capsule, "replayable": False}
    if not isinstance(capsule, dict):
        state = bne_capsule.legacy_state(
            manifest.get("request", {}).get("engine"))
        if isinstance(evidence, dict) and evidence.get("source_capsule_failure"):
            return {
                "state": "capsule-failed",
                "replayable": False,
                "reason": str(evidence["source_capsule_failure"]),
                "recovery": f"{HARNESS} capsule seal .bne-frontier-evidence/recheck",
            }
        return state
    directory = (run_root / "inputs" / capsule.get("path", "")).resolve()
    if not directory.is_relative_to(run_root.resolve()) or not directory.is_dir():
        return {**bne_capsule.legacy_state(
            manifest.get("request", {}).get("engine")),
            "state": "capsule-missing"}
    try:
        sealed = bne_capsule.verify(directory)
    except bne_capsule.CapsuleError as failure:
        return {"state": "capsule-unauthenticated", "replayable": False,
                "reason": str(failure),
                "recovery": f"{HARNESS} capsule verify {directory}"}
    return {
        "state": "sealed",
        "replayable": True,
        "capsule_sha256": sealed["capsule_sha256"],
        "base_head": sealed["base_head"],
        "path": str(directory),
        "engine_identity": sealed["engine_identity"],
        "replay_command": f"{HARNESS} capsule replay {directory}",
    }


# --------------------------------------------------------------------------
# Building the frame


def _blocker_sources(run_root: Path, manifest: dict[str, Any],
        survey_path: Path) -> list[dict[str, Any]]:
    """Prefer retained evidence; fall back to the sealed survey, and say which."""
    candidate = json.loads(survey_path.read_text(encoding="utf-8"))
    recorded_source = (candidate.get("runtime") or {}).get("source_dir")
    source_dir = recorded_source if isinstance(recorded_source, str) \
        and recorded_source.strip() else None
    frontier = manifest.get("frontier", {})
    tied = frontier.get("tied_blockers") or []
    evidence = manifest.get("blocker_evidence")
    retained = {}
    if isinstance(evidence, dict):
        retained = {str(item.get("case")): item
                    for item in evidence.get("blockers", [])}
    packets = {}
    for entry in manifest.get("packets") or []:
        if isinstance(entry, dict) and isinstance(entry.get("case"), str):
            packets[entry["case"]] = entry
    sources: list[dict[str, Any]] = []
    for blocker in tied:
        case = str(blocker.get("case"))
        record = retained.get(case)
        existing = _authenticated_receipt_packet(
            run_root, manifest, packets.get(case), blocker.get("cycle"))
        if existing is not None:
            # A triage receipt that already built this frame is the cheapest
            # evidence there is; rebuilding it would only re-derive it.
            sources.append({
                **blocker, "case": case, "state": "receipt-packet",
                "packet_path": existing,
                "evidence_source": "receipt-packet",
                "findings": _survey_findings(survey_path, case),
                "first_divergence_cycle": blocker.get("cycle"),
                "source_dir": source_dir,
            })
            continue
        if isinstance(record, dict) and record.get("state") == "retained":
            survey = (run_root / "inputs" / record["survey"]).resolve()
            sources.append({
                **blocker, "case": case, "state": "retained",
                "survey": survey,
                "inputs_root": (run_root / "inputs").resolve(),
                "evidence_source": "retained-blocker-evidence",
                "findings": record.get("findings", []),
                "first_divergence_cycle": record.get("first_divergence_cycle",
                                                     blocker.get("cycle")),
                # Older retained surveys omitted runtime.  The authenticated
                # full candidate survey still carries it, so forward it to the
                # packet builder instead of printing a placeholder command.
                "source_dir": source_dir,
            })
            continue
        if isinstance(record, dict):
            sources.append({
                **blocker, "case": case, "state": "unavailable",
                "evidence_source": "retained-blocker-evidence",
                "missing": record.get("missing", []),
                "recovery": record.get("recovery"),
                "findings": record.get("findings", []),
                "first_divergence_cycle": blocker.get("cycle"),
            })
            continue
        # A receipt recorded before evidence retention existed. The sealed
        # survey still names the traces by sha256, so they are worth trying --
        # and if the work directory has moved on, that is reported, not guessed.
        sources.append({
            **blocker, "case": case, "state": "legacy-survey",
            "survey": survey_path,
            "evidence_source": "sealed-candidate-survey",
            "findings": _survey_findings(survey_path, case),
            "first_divergence_cycle": blocker.get("cycle"),
            "source_dir": source_dir,
        })
    return sources


def _authenticated_receipt_packet(run_root: Path, receipt: dict[str, Any],
        entry: dict[str, Any] | None, cycle: object) -> Path | None:
    """Reuse a frame the receipt already built, if it is this blocker's frame."""
    if not isinstance(entry, dict) or entry.get("cycle") != cycle:
        return None
    relative = entry.get("packet")
    if not isinstance(relative, str):
        return None
    path = (run_root / relative).resolve()
    if not path.is_relative_to(run_root.resolve()) or path.is_symlink() \
            or not path.is_file():
        return None
    expected = (receipt.get("artifacts") or {}).get(relative)
    if expected is not None and file_identity(path) != expected:
        return None
    return path


def _survey_findings(survey_path: Path, case: str) -> list[dict[str, Any]]:
    survey = json.loads(survey_path.read_text(encoding="utf-8"))
    record = next((item for item in survey.get("cases", [])
                   if item.get("id") == case), None)
    if not isinstance(record, dict):
        return []
    return [item for item in record.get("findings", []) if isinstance(item, dict)]


def _build_packet(source: dict[str, Any], destination: Path, *,
        before: int, after: int) -> tuple[dict[str, Any] | None, str | None]:
    """Build the frame, resolving retained paths against the sealed receipt.

    A retained survey names its traces relative to the receipt, because the
    staging directory they were written in is renamed away when the receipt is
    sealed. The resolved copy lives beside the frame it produced, so the survey
    the generator actually read is itself part of the compiled evidence.
    """
    from bne_packet import generate_packet

    survey_path = source["survey"]
    try:
        inputs_root = source.get("inputs_root")
        if inputs_root is not None:
            from bne_evidence import resolve_retained_survey

            resolved = resolve_retained_survey(survey_path, inputs_root)
            survey_path = destination.parent / "resolved-survey.json"
            _write_json(survey_path, resolved)
        packet = generate_packet(
            survey_path, source["case"], destination,
            before=before, after=after,
            source_dir=(Path(source["source_dir"]).expanduser().resolve()
                        if source.get("source_dir") else None),
        )
        return packet, None
    except Exception as failure:
        return None, f"{type(failure).__name__}: {failure}"


def _recovery_for(source: dict[str, Any], failure: str | None) -> str:
    """The command that would recover this frame, runnable exactly as printed.

    `survey` takes its corpus index positionally, and this used to emit a
    `--case` line with a trailing prose comment stapled on, so copying it into
    a shell produced an argparse error rather than a survey.
    """
    if source.get("recovery"):
        return str(source["recovery"])
    index = source.get("index") or "CORPUS_INDEX.json"
    return (
        f"{HARNESS} survey {shlex.quote(str(index))} "
        f"--case {shlex.quote(str(source['case']))} --through 80 "
        "--output-dir tools/bne-harness/work/java-corpus/recover"
    )


# --------------------------------------------------------------------------
# The command


def compile_evidence(target: Path | None, *, artifact_root: Path,
        output_root: Path, repository: Path,
        before: int = 4, after: int = 0,
        capabilities: dict[str, Any] | None = None,
        force: bool = False) -> dict[str, Any]:
    """Compile one accepted receipt into a current, routed work order."""
    started = time.monotonic()
    output_root = Path(output_root).expanduser().resolve()
    manifest_path, run_root, receipt = resolve_receipt(target, artifact_root)
    damaged = _authenticate_receipt_artifacts(run_root, receipt)
    survey_path = _candidate_survey(run_root, receipt)
    capsule = _capsule_state(run_root, receipt)

    request = {
        "kind": "frontier-evidence-compile",
        "schema": FRONTIER_SCHEMA,
        "receipt": {
            "request_sha256": receipt.get("request_sha256"),
            "manifest": file_identity(manifest_path),
        },
        "candidate_survey": file_identity(survey_path),
        "source_capsule": {
            "state": capsule.get("state"),
            "capsule_sha256": capsule.get("capsule_sha256"),
        },
        "analysis": analysis_identity(),
        "window": {"before": before, "after": after},
    }
    request_sha256 = canonical_digest(request)
    runs = output_root / "runs"
    destination = runs / request_sha256
    status_path = destination / STATUS_NAME

    with _compile_lock(output_root):
        if status_path.is_file() and not force:
            manifest = json.loads(
                (destination / MANIFEST_NAME).read_text(encoding="utf-8"))
            if manifest.get("request_sha256") != request_sha256 \
                    or canonical_digest(manifest.get("request")) != request_sha256:
                raise FrontierError(
                    f"compiled evidence request identity changed: {destination}")
            _authenticate_inventory(destination, manifest)
            status = json.loads(status_path.read_text(encoding="utf-8"))
            _publish_pointer(output_root, destination, manifest, status,
                             force=force)
            status = {**status, "cache": "hit",
                      "elapsed_seconds": round(time.monotonic() - started, 6)}
            return status

        runs.mkdir(parents=True, exist_ok=True)
        staging = Path(tempfile.mkdtemp(prefix=".frontier-", dir=runs))
        try:
            status = _compile_into(
                staging, receipt, run_root, manifest_path, survey_path,
                capsule, damaged, request, request_sha256,
                before=before, after=after, capabilities=capabilities,
                repository=repository, destination=destination,
            )
            manifest = {
                "schema": FRONTIER_SCHEMA,
                "kind": FRONTIER_KIND,
                "request_sha256": request_sha256,
                "request": request,
                "created_at": datetime.now(timezone.utc).isoformat(),
                "artifacts": inventory(staging),
            }
            _write_json(staging / MANIFEST_NAME, manifest)
            if destination.exists():
                shutil.rmtree(destination)
            os.replace(staging, destination)
            staging = None
        finally:
            if staging is not None and Path(staging).exists():
                shutil.rmtree(staging)
        _publish_pointer(output_root, destination, manifest, status, force=force)

    status = {**status, "cache": "miss",
              "elapsed_seconds": round(time.monotonic() - started, 6)}
    _write_json(destination / STATUS_NAME, {
        key: value for key, value in status.items()
        if key not in ("cache", "elapsed_seconds")
    })
    return status


def _pointer_frontier(output_root: Path) -> int | None:
    pointer_path = output_root / POINTER_NAME
    if not pointer_path.is_file():
        return None
    try:
        pointer = json.loads(pointer_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return None
    relative, identity = pointer.get("status"), pointer.get("status_identity")
    if not isinstance(relative, str) or not isinstance(identity, dict):
        return None
    status_path = (output_root / relative).resolve()
    if not status_path.is_relative_to(output_root.resolve()) \
            or not status_path.is_file() \
            or file_identity(status_path) != identity:
        return None
    value = pointer.get("common_clean_through")
    return value if isinstance(value, int) else None


def _publish_pointer(output_root: Path, destination: Path,
        manifest: dict[str, Any], status: dict[str, Any], *,
        force: bool) -> None:
    """Never let a proof about an older frontier replace a newer one."""
    current = _pointer_frontier(output_root)
    frontier = status.get("frontier", {}).get("common_clean_through")
    if not force and isinstance(current, int) and isinstance(frontier, int) \
            and frontier < current:
        return
    status_path = destination / STATUS_NAME
    pointer = {
        "schema": FRONTIER_SCHEMA,
        "kind": FRONTIER_KIND,
        "run": str(destination.relative_to(output_root)),
        "status": str(status_path.relative_to(output_root)),
        "status_identity": file_identity(status_path),
        "manifest_identity": file_identity(destination / MANIFEST_NAME),
        "request_sha256": manifest["request_sha256"],
        "common_clean_through": frontier,
        "earliest_divergence_cycle":
            status.get("frontier", {}).get("earliest_divergence_cycle"),
        "created_at": manifest["created_at"],
    }
    _publish_atomic(output_root / POINTER_NAME, pointer)


def _with_evidence_verdicts(capabilities: dict[str, Any] | None,
        sources: list[dict[str, Any]], repository: Path,
        frontier: dict[str, Any]) -> dict[str, Any]:
    """Ask the catalog what native evidence each blocker already has.

    The router used to report every native lane as blocked for want of an
    authenticated trace, which is the same sentence whether nothing was ever
    captured or a capture exists that stops ten cycles short. Those need
    different work, so the verdict is compiled here and handed down.

    A catalog that cannot run is not allowed to fail the compile. Its absence
    leaves the router exactly where it was.
    """
    resolved = dict(capabilities or {})
    verdicts: dict[str, Any] = {}
    try:
        from bne_evidence_catalog import build_catalog
    except ImportError:
        return resolved
    for source in sources:
        case = source.get("case")
        if not case:
            continue
        cycle = source.get("first_divergence_cycle") \
            or frontier.get("earliest_divergence_cycle")
        requirement = {"case": case, "through_cycle": cycle}
        try:
            report = build_catalog(repository, requirement)
        except (ValueError, OSError):
            continue
        verdicts[case] = {
            "verdict": report["verdict"],
            "summary": report["summary"],
            "counts": report["counts"],
        }
    if verdicts:
        resolved["evidence"] = verdicts
    return resolved


def _compile_into(staging: Path, receipt: dict[str, Any], run_root: Path,
        manifest_path: Path, survey_path: Path, capsule: dict[str, Any],
        damaged: list[str], request: dict[str, Any], request_sha256: str, *,
        before: int, after: int, capabilities: dict[str, Any] | None,
        repository: Path, destination: Path) -> dict[str, Any]:
    from bne_experiments import hp_evidence
    from bne_whychain import build_why_chain, format_why_chain

    frontier = receipt.get("frontier", {})
    sources = _blocker_sources(run_root, receipt, survey_path)
    packets: dict[str, str] = {}
    shapes: dict[str, dict[str, Any]] = {}
    contexts: dict[str, dict[str, Any]] = {}
    blockers: list[dict[str, Any]] = []

    for source in sources:
        case = source["case"]
        directory = staging / "blockers" / case
        record: dict[str, Any] = {
            "case": case,
            "cycle": source.get("first_divergence_cycle"),
            "rank": source.get("rank"),
            "findings": source.get("findings", []),
            "evidence_source": source.get("evidence_source"),
        }
        if source["state"] == "unavailable":
            record.update({
                "frame": "blocked",
                "missing": source.get("missing", []),
                "recovery": _recovery_for(source, None),
            })
            blockers.append(record)
            continue
        if source["state"] == "receipt-packet":
            packet = json.loads(
                Path(source["packet_path"]).read_text(encoding="utf-8"))
            failure = None
        else:
            packet, failure = _build_packet(
                source, directory / "packet", before=before, after=after)
        if packet is None:
            record.update({
                "frame": "blocked",
                "missing": [failure or "the forensic frame could not be built"],
                "recovery": _recovery_for(source, failure),
            })
            blockers.append(record)
            continue
        _write_json(directory / "packet.json", packet)
        rendered = directory / "packet"
        packets[case] = str(
            (rendered if rendered.is_dir() else directory / "packet.json")
            .relative_to(staging))
        shape = hp_evidence(packet)
        shapes[case] = shape
        identities = packet.get("identities", {})
        slots = packet.get("focus_native_slots") or []
        contexts[case] = {
            "fixture": (identities.get("fixture") or {}).get("path"),
            "java_trace": (identities.get("java_trace") or {}).get("path"),
            # The concrete path a printed command will carry has to name where
            # the packet ends up, not where it is being written. Compilation
            # builds inside a .frontier-* staging directory that os.replace
            # renames away at the end, so a command resolved against `staging`
            # parses and then cannot run, because the directory it names is
            # gone by the time anyone reads it.
            "packet": str(
                destination / "blockers" / case / "packet.json"),
            "native_unit": slots[0] if slots else None,
        }
        record.update({
            "frame": "complete",
            "packet": packets[case],
            "packet_json": str((directory / "packet.json").relative_to(staging)),
            "evidence_source": source.get("evidence_source"),
            "window": packet.get("window"),
            "damage_shape": shape if shape.get("applicable") else None,
        })
        blockers.append(record)

    capabilities = _with_evidence_verdicts(
        capabilities, sources, repository, frontier)
    routing = bne_router.route_all(
        [{**source, "findings": source.get("findings", []),
          "state": "retained" if source["state"] != "unavailable" else "unavailable"}
         for source in sources],
        packets=packets, hp_evidence=shapes, capabilities=capabilities,
        contexts=contexts,
    )
    _write_text(staging / ROUTES_NAME, bne_router.format_routes(routing))

    why = build_why_chain(blockers, routing, capsule, contexts)
    _write_json(staging / "WHY-CHAIN.json", why)
    _write_text(staging / "WHY-CHAIN.md", format_why_chain(why))

    freshness = _freshness(receipt, blockers)
    status = {
        "schema": FRONTIER_SCHEMA,
        "kind": FRONTIER_KIND,
        "request_sha256": request_sha256,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "receipt": {
            "manifest": str(manifest_path),
            "request_sha256": receipt.get("request_sha256"),
            "created_at": receipt.get("created_at"),
            "artifacts_damaged": damaged,
        },
        "frontier": {
            "common_clean_through": frontier.get("common_clean_through"),
            "earliest_divergence_cycle": frontier.get("earliest_divergence_cycle"),
            "counts": frontier.get("counts"),
        },
        "source_capsule": capsule,
        "blockers": blockers,
        "routing": routing,
        "lanes": routing["lanes"],
        "freshness": freshness,
        "why_chain": why,
        "objective": _objective(frontier, blockers, routing),
        "engine_identity_policy": bne_identity.INPUT_POLICY,
        "reads_only": True,
    }
    _write_json(staging / STATUS_NAME, status)
    _write_text(staging / NEXT_NAME, format_next(status))
    return status


def _freshness(receipt: dict[str, Any],
        blockers: list[dict[str, Any]]) -> dict[str, Any]:
    """State how far each frame is from the frontier, so nothing reads current."""
    frontier = receipt.get("frontier", {})
    open_cycle = frontier.get("earliest_divergence_cycle")
    frames = []
    for blocker in blockers:
        cycle = blocker.get("cycle")
        distance = (open_cycle - cycle
                    if isinstance(open_cycle, int) and isinstance(cycle, int)
                    else None)
        frames.append({
            "case": blocker["case"],
            "cycle": cycle,
            "cycles_behind_frontier": distance,
            "current": distance == 0 and blocker.get("frame") == "complete",
            "state": blocker.get("frame"),
        })
    complete = [frame for frame in frames if frame["state"] == "complete"]
    return {
        "accepted_frontier": frontier.get("common_clean_through"),
        "first_open_cycle": open_cycle,
        "frames": frames,
        "all_frames_current": bool(complete) and all(
            frame["current"] for frame in frames),
        "frame_count": len(frames),
        "current_frame_count": sum(1 for frame in frames if frame["current"]),
    }


def _objective(frontier: dict[str, Any], blockers: list[dict[str, Any]],
        routing: dict[str, Any]) -> dict[str, Any]:
    """The objective the evidence supports, not the one a document remembers."""
    through = frontier.get("common_clean_through")
    open_cycle = frontier.get("earliest_divergence_cycle")
    first = routing["routes"][0] if routing.get("routes") else None
    names = ", ".join(f"{item['case']} @{item['cycle']}" for item in blockers)
    return {
        "common_clean_through": through,
        "first_open_cycle": open_cycle,
        "statement": (
            f"Close cycle {open_cycle} so the common proven frontier moves past "
            f"h{through}. Every tied blocker is required: {names}."
            if blockers else
            f"The common proven frontier is h{through}; no blocker is recorded."
        ),
        "next_case": first["case"] if first else None,
        "next_lane": first["next_lane"] if first else None,
        "derived_from": "the accepted gate receipt this evidence was compiled from",
    }


def format_next(status: dict[str, Any]) -> str:
    frontier = status["frontier"]
    lines = [
        "# Next parity work order",
        "",
        f"Common proven frontier **h{frontier['common_clean_through']}**; "
        f"first open cycle **{frontier['earliest_divergence_cycle']}**.",
        "",
        status["objective"]["statement"],
        "",
        "Every equally early blocker is required before the frontier moves. "
        "The order below is expected diagnostic cost, not acceptance priority.",
        "",
        "## Evidence freshness",
        "",
        "| Case | Cycle | Behind frontier | Frame |",
        "|---|---:|---:|---|",
    ]
    for frame in status["freshness"]["frames"]:
        behind = frame["cycles_behind_frontier"]
        lines.append(
            f"| `{frame['case']}` | {frame['cycle']} | "
            f"{'current' if behind == 0 else behind} | {frame['state']} |")
    lines.extend(["", "## Lanes", "",
                  "| Lane | State |", "|---|---|"])
    for lane, state in status["lanes"].items():
        lines.append(f"| `{lane}` | {state} |")

    for blocker in status["blockers"]:
        lines.extend(["", f"## {blocker['case']} @{blocker['cycle']}", ""])
        for finding in blocker.get("findings", []):
            lines.append(f"- cycle {finding.get('cycle')}: "
                         f"{finding.get('message')}")
        if blocker.get("frame") == "complete":
            lines.extend(["", f"Forensic frame: `{blocker['packet']}`"])
            shape = blocker.get("damage_shape")
            if shape:
                lines.append(
                    f"Hit points {shape.get('direction')}; change cycles agree: "
                    f"{shape.get('cadence_agrees')}; change counts agree: "
                    f"{shape.get('change_count_agrees')}; "
                    f"randomized damage suspected: "
                    f"{shape.get('randomized_damage_suspected')}.")
        else:
            lines.extend([
                "", "**No frame.** " + "; ".join(blocker.get("missing", [])),
                "", "```sh", str(blocker.get("recovery", "")), "```",
            ])
    capsule = status["source_capsule"]
    lines.extend([
        "", "## Reproducing this proof", "",
        f"Source capsule: **{capsule.get('state')}**"
        + (f" (`{capsule.get('capsule_sha256', '')[:12]}`)"
           if capsule.get("capsule_sha256") else ""),
    ])
    if not capsule.get("replayable"):
        lines.extend(["", str(capsule.get("reason", "")), "",
                      "```sh", str(capsule.get("recovery", "")), "```"])
    else:
        lines.extend(["", "```sh", str(capsule.get("replay_command", "")), "```"])
    lines.extend(["", "# Routes", "", bne_router.format_routes(status["routing"])])
    from bne_whychain import format_why_chain

    lines.append(format_why_chain(status["why_chain"]))
    return "\n".join(lines) + "\n"


def read_pointer(output_root: Path) -> dict[str, Any] | None:
    """Read the published pointer only if it still authenticates its status."""
    output_root = Path(output_root).expanduser().resolve()
    pointer_path = output_root / POINTER_NAME
    if not pointer_path.is_file():
        return None
    pointer = json.loads(pointer_path.read_text(encoding="utf-8"))
    relative, identity = pointer.get("status"), pointer.get("status_identity")
    if not isinstance(relative, str) or not isinstance(identity, dict):
        return None
    status_path = (output_root / relative).resolve()
    if not status_path.is_relative_to(output_root) or not status_path.is_file():
        return None
    if file_identity(status_path) != identity:
        return None
    return {"pointer": pointer,
            "status": json.loads(status_path.read_text(encoding="utf-8"))}


def format_status(status: dict[str, Any]) -> str:
    frontier = status["frontier"]
    lines = [
        f"Frontier h{frontier['common_clean_through']}, "
        f"first open cycle {frontier['earliest_divergence_cycle']}.",
    ]
    for blocker in status["blockers"]:
        if blocker.get("frame") == "complete":
            lines.append(
                f"  {blocker['case']} @{blocker['cycle']}: frame complete "
                f"({len(blocker.get('findings', []))} finding(s))")
        else:
            lines.append(
                f"  {blocker['case']} @{blocker['cycle']}: frame blocked -- "
                + "; ".join(str(item) for item in blocker.get("missing", [])))
    for route in status["routing"]["routes"]:
        lines.append(
            f"  route {route['case']} -> {route['family']}, "
            f"next lane {route['next_lane']}"
            + (f", blocked {', '.join(route['blocked_lanes'])}"
               if route["blocked_lanes"] else ""))
    lines.append(
        "  lanes: " + ", ".join(f"{lane}={state}"
                                for lane, state in status["lanes"].items()))
    lines.append(f"  source capsule: {status['source_capsule'].get('state')}")
    return "\n".join(lines)
