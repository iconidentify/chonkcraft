#!/usr/bin/env python3
"""Index the native captures already on this machine, and say which are usable.

The router keeps reporting that no authenticated native trace exists for a
case. Sometimes that is true. Sometimes a capture is sitting in a local
evidence root and nobody looked, and sometimes one is sitting there that looks
right and is not -- wrong seed, short window, an executable that is no longer
the pinned one. All three read the same way from outside: "blocked". The cost
is a recapture that was not needed, or worse, a diagnostic run against evidence
that does not match the question.

This walks the configured roots, authenticates what it finds with the code that
already does that job, and classifies each candidate against a stated
requirement. It never imports, copies, recaptures or edits anything, and it
never trusts a file because its name looks useful -- discovery is by manifest,
and a trace with no manifest is not evidence.

A port of no upstream construct: the manifest shapes are this harness's own,
described in {@code FIXTURE.md} and {@code BRANCH_WITNESS.md}.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile
from typing import Any, Iterable

CATALOG_SCHEMA = 1

IMPLEMENTATION = ("bne_evidence_catalog.py", "bne_lab.py")

# The roots this is allowed to walk, relative to the repository. Anything
# outside them is not evidence, however well-formed it looks. Scanning the home
# directory for plausible files is exactly the guessing this replaces.
DEFAULT_ROOTS = (
    ".bne-lab/native",
    ".bne-branch-witness",
    ".bne-decision-miner",
)

# Classification, worst first. A candidate is reported at the first class it
# fails, so "insufficient coverage" is never used to describe something that
# also failed authentication.
MALFORMED = "malformed"
UNAUTHENTICATED = "unauthenticated"
STALE_EXECUTABLE = "stale-executable"
STALE_TRACER = "stale-tracer"
WRONG_IDENTITY = "wrong-fixture-case-seed-or-scenario"
INSUFFICIENT_COVERAGE = "authenticated-but-insufficient-cycle-coverage"
WRONG_PURPOSE = "authenticated-but-wrong-diagnostic-purpose"
REUSABLE = "reusable"
MISSING = "missing"
# Not a verdict about evidence: a file in an evidence root that never claimed
# to be a capture. Kept out of the "found but not usable" table so it cannot be
# mistaken for a rejected capture.
NOT_A_CAPTURE = "not-a-capture"

CLASSES = (
    REUSABLE,
    INSUFFICIENT_COVERAGE,
    WRONG_PURPOSE,
    WRONG_IDENTITY,
    STALE_EXECUTABLE,
    STALE_TRACER,
    UNAUTHENTICATED,
    MALFORMED,
    NOT_A_CAPTURE,
    MISSING,
)

# What each manifest family can answer. A branch or decision capture records one
# focused decision, not a cycle-by-cycle draw ledger, so offering one to the RNG
# lane would be a category error even when every identity matches.
PROFILE_FAMILIES = {
    "async-rng": ("native-trace",),
    "sync-rng": ("native-trace",),
    "idle-dispatch": ("native-trace",),
    "branch-witness": ("branch-capture",),
    "decision-capture": ("decision-capture",),
}


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


def resolve_roots(repository: Path, roots: Iterable[str] | None) -> list[Path]:
    """Resolve the configured roots, refusing anything outside the repository.

    A root is a place the operator has said evidence lives. Accepting one that
    escapes the repository would turn this into an arbitrary filesystem walk,
    which is what the "never guess by filename" rule exists to prevent.
    """
    repository = repository.expanduser().resolve()
    resolved: list[Path] = []
    for name in (roots if roots is not None else DEFAULT_ROOTS):
        candidate = (repository / name).resolve()
        if not candidate.is_relative_to(repository):
            raise ValueError(f"evidence root escapes the repository: {name}")
        resolved.append(candidate)
    return resolved


def _safe_within(root: Path, path: Path) -> bool:
    """True when path is really inside root, following every symlink first."""
    try:
        real_root = root.resolve(strict=True)
        real_path = path.resolve(strict=True)
    except (OSError, RuntimeError):
        return False
    return real_path.is_relative_to(real_root)


def discover(roots: Iterable[Path]) -> list[dict[str, Any]]:
    """Find every manifest under the configured roots.

    Discovery is by manifest, never by trace name. A `.trace.txt` whose sibling
    manifest is absent is reported as an orphan rather than read, because the
    manifest is the only thing that says what a capture is.
    """
    found: list[dict[str, Any]] = []
    for root in roots:
        if not root.is_dir():
            found.append({"root": str(root), "state": "root-absent"})
            continue
        for manifest_path in sorted(root.rglob("*.json")):
            if not manifest_path.name.endswith("manifest.json"):
                continue
            if manifest_path.is_symlink() or not _safe_within(root, manifest_path):
                found.append({
                    "root": str(root),
                    "path": str(manifest_path),
                    "state": "refused-outside-root",
                    "why": ("the manifest is a symlink or resolves outside the "
                            "evidence root it was found under"),
                })
                continue
            found.append({
                "root": str(root),
                "path": str(manifest_path),
                "state": "manifest",
            })
        for trace in sorted(root.rglob("*.trace.txt")):
            sibling = trace.with_name(
                trace.name[:-len(".trace.txt")] + ".manifest.json")
            if not sibling.is_file():
                found.append({
                    "root": str(root),
                    "path": str(trace),
                    "state": "orphan-trace",
                    "why": ("a trace with no sibling manifest is not evidence; "
                            "nothing says what it captured"),
                })
    return found


def _family(manifest: dict[str, Any], path: Path) -> str | None:
    """Name the manifest family, or None when it is not one this understands.

    Returns the sentinel "tool-run" for a harness tool's own content-addressed
    run manifest. Those sit in the same roots and are not captures at all, so
    calling them malformed would fill the report with noise about files that
    were never claiming to be evidence.
    """
    if manifest.get("schema") == 2 and "run" in manifest:
        return "native-trace"
    if "request_sha256" in manifest and "artifacts" in manifest:
        return "tool-run"
    if manifest.get("schema") == 1 and "request" in manifest:
        name = path.name
        if ".branch-capture." in name:
            return "branch-capture"
        if ".decision-capture." in name:
            return "decision-capture"
    return None


def inspect(record: dict[str, Any]) -> dict[str, Any]:
    """Authenticate one discovered manifest and describe what it holds.

    Everything that decides authenticity is delegated: schema-2 traces go
    through `bne_lab.verified_native_trace`, which already checks the trace
    bytes against the manifest, the pinned 2.02b executable, and that the run
    was offline. The capture families are checked against the same pinned
    executable identity here, because no existing helper covers them.
    """
    from bne_lab import verified_native_trace
    from bne_micro_oracle import BNE_202_SHA256
    from bne_triage import file_identity

    path = Path(record["path"])
    entry: dict[str, Any] = {
        "manifest_path": str(path),
        "source_root": record["root"],
    }
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as failure:
        return {**entry, "classification": MALFORMED,
                "why": f"the manifest could not be read: {failure}"}
    if not isinstance(manifest, dict):
        return {**entry, "classification": MALFORMED,
                "why": "the manifest is not a JSON object"}

    family = _family(manifest, path)
    entry["family"] = family
    entry["manifest_schema"] = manifest.get("schema")
    if family == "tool-run":
        return {**entry, "classification": NOT_A_CAPTURE,
                "why": ("this is a harness tool's own run manifest, not a "
                        "native capture")}
    if family is None:
        return {**entry, "classification": MALFORMED,
                "why": ("the manifest is not a recognised native-trace, "
                        "branch-capture or decision-capture manifest")}

    if family == "native-trace":
        trace = path.with_name(
            path.name[:-len(".manifest.json")] + ".trace.txt")
        try:
            verified = verified_native_trace(trace)
        except ValueError as failure:
            return {**entry, "classification": UNAUTHENTICATED,
                    "artifact_path": str(trace),
                    "why": str(failure)}
        run = manifest.get("run", {})
        entry.update({
            "artifact_path": str(trace),
            "artifact_identity": {"bytes": verified["bytes"],
                                  "sha256": verified["sha256"]},
            "case": manifest.get("source", {}).get("case"),
            "fixture_id": verified.get("fixture_id"),
            "scenario": verified.get("scenario"),
            "seed": verified.get("seed"),
            "cycles": verified.get("cycles"),
            "executable_sha256": manifest.get("oracle", {})
                                         .get("executable", {}).get("sha256"),
            "tracer_sha256": verified.get("tracer_sha256"),
            "event_families": sorted(run.get("event_families", []))
                              or None,
        })
        return {**entry, "classification": REUSABLE,
                "why": "authenticated against the pinned executable and "
                       "captured offline"}

    request = manifest.get("request", {})
    oracle = manifest.get("oracle", {})
    capture = manifest.get("capture", {})
    artifact = path.with_name(capture.get("name", "")) if capture.get("name") \
        else None
    entry.update({
        "artifact_path": str(artifact) if artifact else None,
        "case": request.get("case"),
        "fixture_id": request.get("fixture_id"),
        "scenario": request.get("scenario"),
        "seed": request.get("seed"),
        "cycles": request.get("cycle")
                  or oracle.get("run_manifest", {}).get("cycles"),
        "fields": request.get("fields") or (
            [request.get("field")] if request.get("field") else None),
        "native_slot": request.get("native_slot"),
        "executable_sha256": oracle.get("executable", {}).get("sha256"),
        "tracer_sha256": manifest.get("harness", {})
                                 .get("tracer", {}).get("sha256"),
    })

    if manifest.get("runtime", {}).get("network_disabled") is not True:
        return {**entry, "classification": UNAUTHENTICATED,
                "why": "the capture manifest does not record an offline run"}
    if entry["executable_sha256"] != BNE_202_SHA256:
        return {**entry, "classification": STALE_EXECUTABLE,
                "why": ("the capture names an executable that is not the "
                        "pinned BNE 2.02b build")}
    if artifact is None:
        return {**entry, "classification": MALFORMED,
                "why": "the capture manifest names no artifact"}
    if not artifact.is_file():
        return {**entry, "classification": UNAUTHENTICATED,
                "why": f"the artifact the manifest names is absent: {artifact}"}
    actual = file_identity(artifact)
    expected = {"bytes": capture.get("bytes"), "sha256": capture.get("sha256")}
    if actual != expected:
        return {**entry, "classification": UNAUTHENTICATED,
                "artifact_identity": actual,
                "why": "the artifact's bytes differ from the manifest"}
    entry["artifact_identity"] = actual
    return {**entry, "classification": REUSABLE,
            "why": "authenticated against the pinned executable and "
                   "captured offline"}


def match(entry: dict[str, Any], requirement: dict[str, Any]) -> dict[str, Any]:
    """Judge one authenticated candidate against what the blocker needs.

    Only a candidate that already authenticated can be judged here. The order
    is deliberate: identity before coverage, so a capture of a different case is
    never described as merely too short.
    """
    if entry["classification"] != REUSABLE:
        return entry

    # Identity first. A capture of a different mission is not usefully
    # described as "wrong diagnostic" or "too short" -- the case is the reason,
    # and reporting the secondary failure would send someone off to capture
    # more cycles of the wrong thing.
    for key, label in (("case", "case"), ("fixture_id", "fixture"),
                       ("seed", "seed"), ("scenario", "scenario")):
        wanted = requirement.get(key)
        if wanted is None:
            continue
        held = entry.get(key)
        if held is None:
            return {**entry, "classification": WRONG_IDENTITY,
                    "why": (f"this manifest records no {label}, so it cannot "
                            f"be matched to one; it identifies its run by "
                            f"scenario {entry.get('scenario')!r} and fixture "
                            f"{entry.get('fixture_id')!r}")}
        if held != wanted:
            return {**entry, "classification": WRONG_IDENTITY,
                    "why": (f"the capture's {label} is {held!r}, and this "
                            f"blocker needs {wanted!r}")}

    profile = requirement.get("profile")
    if profile is not None:
        allowed = PROFILE_FAMILIES.get(profile)
        if allowed is None:
            return {**entry, "classification": WRONG_PURPOSE,
                    "why": f"no capture family serves the profile {profile!r}"}
        if entry.get("family") not in allowed:
            return {**entry, "classification": WRONG_PURPOSE,
                    "why": (f"a {entry.get('family')} cannot answer a "
                            f"{profile} question; that needs one of "
                            f"{', '.join(allowed)}")}

    through = requirement.get("through_cycle")
    if through is not None:
        covered = entry.get("cycles")
        if covered is None:
            return {**entry, "classification": INSUFFICIENT_COVERAGE,
                    "why": "the manifest records no cycle coverage"}
        if covered < through:
            return {**entry, "classification": INSUFFICIENT_COVERAGE,
                    "why": (f"the capture covers {covered} cycles and this "
                            f"blocker needs {through}")}

    field = requirement.get("field")
    if field is not None and entry.get("fields") is not None:
        if field not in entry["fields"]:
            return {**entry, "classification": WRONG_PURPOSE,
                    "why": (f"the capture records {entry['fields']}, not "
                            f"{field!r}")}
    return entry


def build_catalog(repository: Path, requirement: dict[str, Any], *,
                  roots: Iterable[str] | None = None) -> dict[str, Any]:
    """Walk the roots, authenticate everything, and judge it against one need."""
    resolved = resolve_roots(repository, roots)
    discovered = discover(resolved)
    entries: list[dict[str, Any]] = []
    refusals: list[dict[str, Any]] = []
    for record in discovered:
        if record["state"] == "manifest":
            entries.append(match(inspect(record), requirement))
        elif record["state"] in ("orphan-trace", "refused-outside-root"):
            refusals.append(record)

    reusable = [entry for entry in entries
                if entry["classification"] == REUSABLE]
    order = {name: index for index, name in enumerate(CLASSES)}
    entries.sort(key=lambda entry: (order.get(entry["classification"], 99),
                                    str(entry.get("manifest_path"))))
    # A tool's own run manifest is not a capture that was rejected, so it does
    # not get a say in the verdict.
    captures = [entry for entry in entries
                if entry["classification"] != NOT_A_CAPTURE]

    if reusable:
        verdict = REUSABLE
        summary = (f"{len(reusable)} authenticated capture(s) match this "
                   f"blocker")
    elif captures:
        best = captures[0]["classification"]
        verdict = best
        summary = (f"evidence exists but none is usable; the closest is "
                   f"{best}")
    else:
        verdict = MISSING
        summary = "no native capture was found under the configured roots"

    return {
        "schema": CATALOG_SCHEMA,
        "requirement": requirement,
        "roots": [str(path) for path in resolved],
        "verdict": verdict,
        "summary": summary,
        "reusable": reusable,
        "candidates": entries,
        "refused": refusals,
        "counts": {name: sum(1 for entry in entries
                             if entry["classification"] == name)
                   for name in CLASSES
                   if any(entry["classification"] == name for entry in entries)},
    }


def format_catalog(report: dict[str, Any]) -> str:
    """Render the catalog as the Markdown an agent reads before recapturing."""
    requirement = report["requirement"]
    lines = [
        "# Native evidence catalog",
        "",
        f"- Verdict: **{report['verdict']}**",
        f"- {report['summary']}.",
        "",
        "## What was asked for",
        "",
    ]
    for key in ("case", "profile", "fixture_id", "scenario", "seed",
                "through_cycle", "field"):
        if requirement.get(key) is not None:
            lines.append(f"- {key.replace('_', ' ')}: `{requirement[key]}`")
    lines += ["", "## Roots walked", ""]
    for root in report["roots"]:
        lines.append(f"- `{root}`")
    lines.append("")

    if report["reusable"]:
        lines += ["## Reusable", ""]
        for entry in report["reusable"]:
            lines += [
                f"- `{entry.get('artifact_path')}`",
                f"  - case `{entry.get('case')}`, seed `{entry.get('seed')}`, "
                f"cycles `{entry.get('cycles')}`",
                f"  - {entry['why']}.",
            ]
        lines.append("")

    other = [entry for entry in report["candidates"]
             if entry["classification"] not in (REUSABLE, NOT_A_CAPTURE)]
    if other:
        lines += [
            "## Found but not usable",
            "",
            "| Classification | Case | Cycles | Why |",
            "| --- | --- | ---: | --- |",
        ]
        for entry in other:
            lines.append(
                f"| {entry['classification']} | {entry.get('case') or '--'} | "
                f"{entry.get('cycles') if entry.get('cycles') is not None else '--'} | "
                f"{entry['why']} |")
        lines += [
            "",
            "None of these is missing evidence. Each one exists and was "
            "rejected for the stated reason, which is the reason a recapture "
            "would have to change.",
            "",
        ]

    if report["refused"]:
        lines += ["## Refused before reading", ""]
        for record in report["refused"]:
            lines.append(f"- `{record.get('path')}` -- {record.get('why')}")
        lines.append("")

    if report["verdict"] == MISSING:
        lines += [
            "## Nothing found",
            "",
            "No manifest exists under the configured roots. This is different "
            "from a capture that exists and failed a check: there is nothing "
            "here to authenticate, so a fresh capture is the only route.",
            "",
        ]
    return "\n".join(lines) + "\n"


def exit_code(report: dict[str, Any]) -> int:
    """0 when something is reusable, 1 when found-but-not, 2 when nothing is."""
    if report["verdict"] == REUSABLE:
        return 0
    if report["verdict"] == MISSING:
        return 2
    return 1


def run_evidence_catalog(repository: Path, artifact_root: Path, *,
                         requirement: dict[str, Any],
                         roots: Iterable[str] | None = None) \
        -> tuple[int, Path]:
    """Publish a content-addressed catalog for one requirement."""
    from bne_triage import canonical_digest, file_identity, inventory_files

    repository = repository.expanduser().resolve()
    resolved_roots = list(roots) if roots is not None else list(DEFAULT_ROOTS)
    request = {
        "schema": CATALOG_SCHEMA,
        "implementation": {
            name: file_identity(Path(__file__).with_name(name))
            for name in IMPLEMENTATION
        },
        "requirement": requirement,
        "roots": resolved_roots,
    }
    request_sha256 = canonical_digest(request)
    artifact_root = artifact_root.expanduser().resolve()
    run_root = artifact_root / "runs" / request_sha256
    manifest_path = run_root / "manifest.json"

    if manifest_path.is_file():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if manifest.get("request_sha256") != request_sha256 \
                or canonical_digest(manifest.get("request")) != request_sha256:
            raise ValueError("cached evidence catalog request identity changed")
        for relative, expected in manifest["artifacts"].items():
            path = run_root / relative
            if not path.is_file() or file_identity(path) != expected:
                raise ValueError(f"evidence catalog artifact changed: {path}")
        _write(artifact_root / "latest.json",
               json.dumps(manifest["pointer"], indent=2, sort_keys=True) + "\n")
        return int(manifest["exit_code"]), run_root

    report = build_catalog(repository, requirement, roots=resolved_roots)
    status = exit_code(report)
    run_root.mkdir(parents=True, exist_ok=True)
    report_path = run_root / "EVIDENCE-CATALOG.json"
    summary_path = run_root / "EVIDENCE-CATALOG.md"
    _write(report_path, json.dumps(report, indent=2, sort_keys=True) + "\n")
    _write(summary_path, format_catalog(report))
    pointer = {
        "schema": CATALOG_SCHEMA,
        "request_sha256": request_sha256,
        "run": str(run_root.relative_to(artifact_root)),
        "case": requirement.get("case"),
        "profile": requirement.get("profile"),
        "verdict": report["verdict"],
        "exit_code": status,
    }
    manifest = {
        "schema": CATALOG_SCHEMA,
        "request_sha256": request_sha256,
        "request": request,
        "exit_code": status,
        "pointer": pointer,
        "artifacts": inventory_files(run_root, [report_path, summary_path]),
    }
    _write(manifest_path, json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    _write(artifact_root / "latest.json",
           json.dumps(pointer, indent=2, sort_keys=True) + "\n")
    return status, run_root
