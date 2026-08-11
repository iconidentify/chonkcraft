#!/usr/bin/env python3
"""Persistent, queryable failure memory for BNE parity runs."""

from __future__ import annotations

from datetime import datetime, timezone
import json
from pathlib import Path
import re
import sqlite3
from typing import Any

from bne_triage import case_signature, file_identity, verify_manifest


ATLAS_SCHEMA = 1
_TOKEN = re.compile(r"[a-z0-9]+")


DDL = """
CREATE TABLE IF NOT EXISTS metadata (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS runs (
  request_sha256 TEXT PRIMARY KEY,
  created_at TEXT NOT NULL,
  manifest_path TEXT NOT NULL,
  manifest_sha256 TEXT NOT NULL,
  engine_head TEXT,
  common_clean_through INTEGER,
  earliest_divergence_cycle INTEGER,
  gate_passed INTEGER NOT NULL,
  clean_count INTEGER NOT NULL,
  divergent_count INTEGER NOT NULL,
  failed_count INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS cases (
  request_sha256 TEXT NOT NULL REFERENCES runs(request_sha256),
  case_id TEXT NOT NULL,
  state TEXT NOT NULL,
  first_cycle INTEGER,
  signature TEXT,
  findings_json TEXT NOT NULL,
  PRIMARY KEY (request_sha256, case_id)
);
CREATE INDEX IF NOT EXISTS cases_signature ON cases(signature);
CREATE INDEX IF NOT EXISTS cases_cycle ON cases(first_cycle);
CREATE TABLE IF NOT EXISTS clusters (
  request_sha256 TEXT NOT NULL REFERENCES runs(request_sha256),
  cluster_id TEXT NOT NULL,
  signature TEXT NOT NULL,
  earliest_cycle INTEGER NOT NULL,
  case_count INTEGER NOT NULL,
  members_json TEXT NOT NULL,
  PRIMARY KEY (request_sha256, cluster_id)
);
CREATE TABLE IF NOT EXISTS causal_findings (
  request_sha256 TEXT NOT NULL REFERENCES runs(request_sha256),
  case_id TEXT NOT NULL,
  event_kind TEXT NOT NULL,
  cycle INTEGER,
  operation TEXT NOT NULL,
  details_json TEXT NOT NULL,
  PRIMARY KEY (request_sha256, case_id, event_kind, cycle, operation)
);
CREATE TABLE IF NOT EXISTS experiments (
  experiment_id TEXT PRIMARY KEY,
  request_sha256 TEXT,
  case_id TEXT,
  status TEXT NOT NULL,
  hypothesis TEXT,
  information_gain REAL,
  result_json TEXT NOT NULL,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS coverage_tokens (
  request_sha256 TEXT NOT NULL,
  case_id TEXT NOT NULL,
  token TEXT NOT NULL,
  PRIMARY KEY (request_sha256, case_id, token)
);
CREATE INDEX IF NOT EXISTS coverage_token ON coverage_tokens(token);
"""


def connect(path: Path) -> sqlite3.Connection:
    path.parent.mkdir(parents=True, exist_ok=True)
    database = sqlite3.connect(path)
    database.row_factory = sqlite3.Row
    database.executescript(DDL)
    database.execute(
        "INSERT OR REPLACE INTO metadata(key, value) VALUES('schema', ?)",
        (str(ATLAS_SCHEMA),),
    )
    return database


def _survey_path(run_root: Path, manifest: dict[str, Any]) -> Path:
    path = (run_root / manifest["candidate"]["survey"]).resolve()
    if not path.is_relative_to(run_root.resolve()) or not path.is_file():
        raise ValueError(f"unsafe or missing candidate survey: {path}")
    return path


def ingest_run(database_path: Path, run_root: Path,
        causal: dict[str, dict[str, Any]] | None = None) -> dict[str, Any]:
    run_root = run_root.resolve()
    manifest_path = run_root / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    request_sha256 = manifest["request_sha256"]
    verify_manifest(run_root, manifest, request_sha256)
    survey = json.loads(_survey_path(run_root, manifest).read_text(encoding="utf-8"))
    counts = manifest["candidate"]["counts"]
    engine_head = manifest.get("request", {}).get("engine", {}).get("head")

    with connect(database_path) as database:
        database.execute("""
            INSERT OR REPLACE INTO runs VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, (
            request_sha256, manifest["created_at"], str(manifest_path),
            file_identity(manifest_path)["sha256"], engine_head,
            manifest["frontier"].get("common_clean_through"),
            manifest["frontier"].get("earliest_divergence_cycle"),
            int(bool(manifest["gate"]["passed"])),
            counts.get("clean", 0), counts.get("divergent", 0),
            counts.get("failed", 0),
        ))
        database.execute("DELETE FROM cases WHERE request_sha256 = ?",
                         (request_sha256,))
        for record in survey.get("cases", []):
            signature = case_signature(record) \
                if record.get("state") == "divergent" else None
            database.execute("""
                INSERT INTO cases VALUES (?, ?, ?, ?, ?, ?)
            """, (
                request_sha256, record["id"], record.get("state", "failed"),
                record.get("first_divergence_cycle"), signature,
                json.dumps(record.get("findings", []), sort_keys=True),
            ))
        database.execute("DELETE FROM clusters WHERE request_sha256 = ?",
                         (request_sha256,))
        for cluster in manifest.get("clusters", []):
            database.execute("INSERT INTO clusters VALUES (?, ?, ?, ?, ?, ?)", (
                request_sha256, cluster["id"], cluster["signature"],
                cluster["earliest_cycle"], cluster["case_count"],
                json.dumps(cluster["cases"], sort_keys=True),
            ))
        if causal:
            database.execute(
                "DELETE FROM causal_findings WHERE request_sha256 = ?",
                (request_sha256,),
            )
            for case_id, alignment in sorted(causal.items()):
                first = alignment.get("first_divergence")
                if not first:
                    continue
                event = first.get("native") or first.get("java") or {}
                database.execute("""
                    INSERT OR REPLACE INTO causal_findings VALUES (?, ?, ?, ?, ?, ?)
                """, (
                    request_sha256, case_id, event.get("kind", "unknown"),
                    event.get("cycle"), first.get("op", "unknown"),
                    json.dumps(first, sort_keys=True),
                ))
    return {
        "schema": ATLAS_SCHEMA,
        "request_sha256": request_sha256,
        "database": str(database_path.resolve()),
        "cases": len(survey.get("cases", [])),
        "clusters": len(manifest.get("clusters", [])),
    }


def _tokens(signature: str) -> set[str]:
    return set(_TOKEN.findall(signature.lower()))


def _similarity(left: str, right: str) -> float:
    a, b = _tokens(left), _tokens(right)
    return len(a & b) / len(a | b) if a or b else 1.0


def similar_failures(database_path: Path, signature: str, *, limit: int = 5,
        exclude_request: str | None = None) -> list[dict[str, Any]]:
    if limit <= 0:
        raise ValueError("limit must be positive")
    with connect(database_path) as database:
        rows = database.execute("""
            SELECT c.*, r.engine_head, r.created_at
            FROM cases c JOIN runs r USING(request_sha256)
            WHERE c.signature IS NOT NULL
        """).fetchall()
    candidates = []
    for row in rows:
        if exclude_request and row["request_sha256"] == exclude_request:
            continue
        score = _similarity(signature, row["signature"])
        candidates.append({
            "score": round(score, 6),
            "request_sha256": row["request_sha256"],
            "case": row["case_id"],
            "cycle": row["first_cycle"],
            "signature": row["signature"],
            "engine_head": row["engine_head"],
            "created_at": row["created_at"],
            "findings": json.loads(row["findings_json"]),
        })
    candidates.sort(key=lambda item: (
        -item["score"], item["cycle"] if item["cycle"] is not None else 1 << 30,
        item["case"], item["request_sha256"],
    ))
    return candidates[:limit]


def record_experiment(database_path: Path, experiment: dict[str, Any]) -> None:
    required = ("id", "status")
    if any(not experiment.get(key) for key in required):
        raise ValueError("experiment requires id and status")
    with connect(database_path) as database:
        database.execute("""
            INSERT OR REPLACE INTO experiments VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, (
            experiment["id"], experiment.get("request_sha256"),
            experiment.get("case"), experiment["status"],
            experiment.get("hypothesis"), experiment.get("information_gain"),
            json.dumps(experiment.get("result", {}), sort_keys=True),
            experiment.get("created_at")
            or datetime.now(timezone.utc).isoformat(),
        ))


def coverage_baseline(database_path: Path) -> set[str]:
    with connect(database_path) as database:
        return {row["token"] for row in database.execute(
            "SELECT DISTINCT token FROM coverage_tokens"
        ).fetchall()}


def record_coverage(database_path: Path, request_sha256: str,
        case_id: str, report: dict[str, Any]) -> dict[str, Any]:
    tokens = sorted(set(report.get("tokens", [])))
    if not request_sha256 or not case_id \
            or any(not isinstance(token, str) for token in tokens):
        raise ValueError("coverage requires request, case, and string tokens")
    with connect(database_path) as database:
        database.execute(
            "DELETE FROM coverage_tokens WHERE request_sha256 = ? AND case_id = ?",
            (request_sha256, case_id),
        )
        database.executemany(
            "INSERT INTO coverage_tokens VALUES (?, ?, ?)",
            ((request_sha256, case_id, token) for token in tokens),
        )
        total = database.execute(
            "SELECT COUNT(DISTINCT token) AS count FROM coverage_tokens"
        ).fetchone()["count"]
    return {"case_tokens": len(tokens), "atlas_tokens": total}


def dashboard_snapshot(database_path: Path, *, limit: int = 12) \
        -> dict[str, Any]:
    with connect(database_path) as database:
        runs = [dict(row) for row in database.execute("""
            SELECT * FROM runs ORDER BY created_at DESC LIMIT ?
        """, (limit,)).fetchall()]
        causal = [dict(row) for row in database.execute("""
            SELECT * FROM causal_findings ORDER BY rowid DESC LIMIT ?
        """, (limit,)).fetchall()]
        experiments = [dict(row) for row in database.execute("""
            SELECT * FROM experiments ORDER BY created_at DESC LIMIT ?
        """, (limit,)).fetchall()]
        coverage = [dict(row) for row in database.execute("""
            SELECT token, COUNT(DISTINCT request_sha256 || ':' || case_id) AS cases
            FROM coverage_tokens GROUP BY token
            ORDER BY cases DESC, token LIMIT ?
        """, (limit,)).fetchall()]
        coverage_total = database.execute(
            "SELECT COUNT(DISTINCT token) AS count FROM coverage_tokens"
        ).fetchone()["count"]
    for item in causal:
        item["details"] = json.loads(item.pop("details_json"))
    for item in experiments:
        item["result"] = json.loads(item.pop("result_json"))
    return {
        "schema": ATLAS_SCHEMA,
        "runs": runs,
        "causal": causal,
        "experiments": experiments,
        "coverage": {"token_count": coverage_total, "top_tokens": coverage},
    }
