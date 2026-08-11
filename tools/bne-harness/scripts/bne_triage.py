#!/usr/bin/env python3
"""Pure reporting and artifact-integrity helpers for BNE triage runs."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
from typing import Any, Iterable


TRIAGE_SCHEMA = 1
_NUMBER = re.compile(r"-?\d+")


def file_identity(path: Path) -> dict[str, int | str]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            size += len(block)
            digest.update(block)
    return {"bytes": size, "sha256": digest.hexdigest()}


def canonical_digest(value: object) -> str:
    encoded = json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=True,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _numeric_delta(oracle: object, java: object) -> object | None:
    if isinstance(oracle, bool) or isinstance(java, bool):
        return None
    if isinstance(oracle, (int, float)) and isinstance(java, (int, float)):
        return oracle - java
    if isinstance(oracle, list) and isinstance(java, list) \
            and len(oracle) == len(java):
        result = []
        for left, right in zip(oracle, java):
            if isinstance(left, bool) or isinstance(right, bool) \
                    or not isinstance(left, (int, float)) \
                    or not isinstance(right, (int, float)):
                return None
            result.append(left - right)
        return result
    return None


def finding_signature(finding: dict[str, Any]) -> str:
    """Return a stable, deliberately heuristic root-cause signature."""
    kind = str(finding.get("kind", "other"))
    unit_type = str(finding.get("unit_type", ""))
    field = str(finding.get("field", ""))
    if field in ("x", "y"):
        field = "position-" + field
    delta = _numeric_delta(finding.get("oracle"), finding.get("java"))
    if delta is not None:
        difference = f"delta={json.dumps(delta, separators=(',', ':'))}"
    elif kind == "unit_population":
        difference = f"{finding.get('oracle')}->{finding.get('java')}"
    elif kind == "sync_rng":
        difference = str(finding.get("reason", "state-mismatch"))
    elif kind == "other":
        message = str(finding.get("message", ""))
        difference = _NUMBER.sub("#", message)
    else:
        difference = "value-mismatch"
    return "|".join(part for part in (kind, unit_type, field, difference)
                    if part)


def case_signature(record: dict[str, Any]) -> str:
    findings = record.get("findings")
    if not isinstance(findings, list) or not findings:
        return "unstructured-divergence"
    # The production differ orders first-cycle findings deterministically.
    # The first mismatch is the strongest cheap clustering signal; retaining
    # every message below keeps this heuristic auditable rather than factual.
    first = next((item for item in findings if isinstance(item, dict)), None)
    return finding_signature(first) if first is not None \
        else "unstructured-divergence"


def cluster_divergences(survey: dict[str, Any]) -> list[dict[str, Any]]:
    grouped: dict[str, list[dict[str, Any]]] = {}
    for record in survey.get("cases", []):
        if record.get("state") != "divergent":
            continue
        grouped.setdefault(case_signature(record), []).append(record)
    clusters = []
    for signature, records in grouped.items():
        records.sort(key=lambda item: (
            item.get("first_divergence_cycle", 1 << 30), item.get("id", "")
        ))
        messages = []
        for record in records:
            for finding in record.get("findings", []):
                message = finding.get("message") if isinstance(finding, dict) else None
                if isinstance(message, str) and message not in messages:
                    messages.append(message)
                if len(messages) == 3:
                    break
            if len(messages) == 3:
                break
        clusters.append({
            "id": "cluster-" + canonical_digest(signature)[:10],
            "signature": signature,
            "heuristic": True,
            "case_count": len(records),
            "earliest_cycle": min(
                record["first_divergence_cycle"] for record in records
            ),
            "cases": [record["id"] for record in records],
            "examples": messages,
        })
    return sorted(clusters, key=lambda item: (
        item["earliest_cycle"], -item["case_count"], item["id"]
    ))


def inventory_files(root: Path, paths: Iterable[Path]) \
        -> dict[str, dict[str, int | str]]:
    root = root.resolve()
    files: list[Path] = []
    for path in paths:
        path = path.resolve()
        if not path.is_relative_to(root):
            raise ValueError(f"artifact lies outside run root: {path}")
        if path.is_file():
            files.append(path)
        elif path.is_dir():
            files.extend(item for item in path.rglob("*") if item.is_file())
        else:
            raise ValueError(f"artifact is missing: {path}")
    result = {}
    for path in sorted(set(files)):
        result[str(path.relative_to(root))] = file_identity(path)
    return result


def verify_manifest(run_root: Path, manifest: dict[str, Any],
        request_sha256: str) -> None:
    if manifest.get("schema") != TRIAGE_SCHEMA:
        raise ValueError(f"unsupported triage manifest schema: {run_root}")
    if manifest.get("request_sha256") != request_sha256:
        raise ValueError(f"triage request hash collision at {run_root}")
    if canonical_digest(manifest.get("request")) != request_sha256:
        raise ValueError(f"triage manifest request identity changed: {run_root}")
    artifacts = manifest.get("artifacts")
    if not isinstance(artifacts, dict) or not artifacts:
        raise ValueError(f"triage manifest has no artifact inventory: {run_root}")
    for relative, expected in artifacts.items():
        path = (run_root / relative).resolve()
        if not path.is_relative_to(run_root.resolve()):
            raise ValueError(f"unsafe triage artifact path: {relative}")
        if not path.is_file() or file_identity(path) != expected:
            raise ValueError(f"triage artifact identity changed: {path}")


def format_clusters(clusters: list[dict[str, Any]]) -> str:
    lines = [
        "# BNE divergence clusters",
        "",
        "These are deterministic heuristics for investigation planning, not "
        "proof that cases share a root cause.",
        "",
        "| Earliest | Cases | Signature | Members |",
        "|---:|---:|---|---|",
    ]
    if not clusters:
        lines.append("| — | 0 | No divergent cases | — |")
    for cluster in clusters:
        members = ", ".join(f"`{case}`" for case in cluster["cases"])
        lines.append(
            f"| {cluster['earliest_cycle']} | {cluster['case_count']} | "
            f"`{cluster['signature']}` | {members} |"
        )
    return "\n".join(lines) + "\n"


def format_triage_summary(manifest: dict[str, Any]) -> str:
    gate = manifest["gate"]
    frontier = manifest["frontier"]
    counts = manifest["candidate"]["counts"]
    lines = [
        "# BNE automatic triage",
        "",
        f"- Run: `{manifest['request_sha256']}`",
        f"- Gate: **{'PASS' if gate['passed'] else 'FAIL'}**",
        f"- Candidate: **{counts.get('clean', 0)} clean**, "
        f"**{counts.get('divergent', 0)} divergent**, "
        f"**{counts.get('failed', 0)} failed**",
        f"- Common clean horizon: "
        f"**{frontier.get('common_clean_through')}**",
        f"- Earliest divergence: "
        f"**{frontier.get('earliest_divergence_cycle')}**",
        f"- Candidate survey: `{manifest['candidate']['survey']}`",
        "",
    ]
    if gate.get("issues"):
        lines.extend(["## Gate issues", ""])
        for issue in gate["issues"][:10]:
            lines.append(
                f"- `{issue['id']}` {issue['kind']}: {issue['message']}"
            )
        lines.append("")
    tied = frontier.get("tied_blockers", [])
    if len(tied) > 1:
        lines.extend(["## Earliest tied-blocker order", ""])
        for item in tied:
            lines.append(
                f"- #{item['rank']} `{item['case']}`: "
                f"{item['tractability']} tractability; start with "
                f"`{item['recommended_tool']}`."
            )
        lines.append("")
    packets = manifest.get("packets", [])
    if packets:
        lines.extend(["## Next evidence packets", ""])
        for packet in packets:
            diagnostic = " with retained Java diagnostics" \
                if packet.get("diagnostic_survey") else ""
            lines.append(
                f"- `{packet['case']}` @**{packet['cycle']}**{diagnostic}: "
                f"`{packet['readme']}`"
            )
        lines.append("")
    lines.extend(["## Probable root-cause clusters", ""])
    clusters = manifest.get("clusters", [])
    if not clusters:
        lines.append("No divergent cases to cluster.")
    for cluster in clusters:
        members = ", ".join(cluster["cases"])
        lines.append(
            f"- **{cluster['case_count']} case(s)**, earliest "
            f"@{cluster['earliest_cycle']}: `{cluster['signature']}` — {members}"
        )
    lines.extend([
        "",
        "Clusters are heuristics. Preserve earliest-first acceptance and use "
        "the regression gate as proof.",
        "",
    ])
    return "\n".join(lines)
