#!/usr/bin/env python3
"""Run bounded Java state interventions and rank their BNE futures.

This is an investigative tool, never an acceptance gate.  It uses the sealed
fixture as an oracle, applies one explainable test-harness intervention around
the first divergence, and measures whether later frames move toward or away
from the oracle.  No production engine class contains an intervention hook.
"""

from __future__ import annotations

import argparse
import concurrent.futures
from datetime import datetime, timezone
import fcntl
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
from typing import Any
import zipfile

import bne_compare
import bne_java
from bne_packet import align_trace_units, parse_trace
from bne_triage import canonical_digest, inventory_files, verify_manifest


SCHEMA = 1
ROOT = Path(__file__).resolve().parents[3]
IMPLEMENTATION = (
    Path(__file__),
    ROOT / "engine/src/test/java/net/chonkbase/chonkcraft/engine/parity/EngineTrace.java",
)

UPSTREAM_ORDERS = {
    0: "none", 1: "still", 2: "standground", 3: "follow", 4: "defend",
    5: "move", 6: "attack", 7: "attackground", 8: "die",
    9: "spellcast", 10: "train", 11: "upgradeto", 12: "research",
    13: "built", 14: "board", 15: "unload", 16: "patrol",
    17: "build", 18: "explore", 19: "repair", 20: "resource",
    21: "transforminto",
}
PORT_ORDERS = {
    "STILL": "still", "STAND_GROUND": "standground", "FOLLOW": "follow",
    "MOVE": "move", "ATTACK": "attack", "ATTACK_MOVE": "attack",
    "ATTACK_GROUND": "attackground", "DYING": "die",
    "SPELL_CAST": "spellcast", "UNDER_CONSTRUCTION": "built",
    "BUILD": "build", "EXPLORE": "explore", "HARVEST": "resource",
    "RETURN_GOODS": "resource", "PATROL": "patrol", "BOARD": "board",
    "UNLOAD": "unload", "REPAIR": "repair", "TRAIN": "train",
    "RESEARCH": "research", "UPGRADE_TO": "upgradeto",
}


def _json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"expected a JSON object: {path}")
    return value


def _write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", prefix=path.name + ".",
            suffix=".tmp", dir=path.parent, delete=False) as handle:
        temporary = Path(handle.name)
        json.dump(value, handle, indent=2, sort_keys=True)
        handle.write("\n")
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, path)


def _write_text(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", prefix=path.name + ".",
            suffix=".tmp", dir=path.parent, delete=False) as handle:
        temporary = Path(handle.name)
        handle.write(value)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, path)


def _safe(root: Path, relative: str) -> Path:
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()):
        raise ValueError(f"artifact escapes its run: {relative}")
    return path


def _primary_focus(packet: dict[str, Any]) -> dict[str, Any]:
    cycle = int(packet["divergence"]["cycle"])
    focus = packet.get("semantic", {}).get(str(cycle), {}).get("focus", [])
    if not focus:
        raise ValueError("counterfactual replay requires a paired focused unit")
    findings = packet["divergence"].get("findings", [])
    native_unit = next((item.get("unit") for item in findings
                        if isinstance(item.get("unit"), int)), None)
    selected = next((item for item in focus
                     if item.get("native_slot") == native_unit), focus[0])
    if not isinstance(selected.get("native_slot"), int) \
            or not isinstance(selected.get("java_id"), int):
        raise ValueError("counterfactual focus has no stable native/Java pairing")
    return selected


def _candidate(identifier: str, description: str, family: str,
        interventions: list[dict[str, Any]]) -> dict[str, Any]:
    invasiveness = {
        "control": 0,
        "surface-label": 1,
        "timing": 2,
        "route-choice": 2,
        "transition-timing": 2,
        "transition-result": 2,
        "state-clamp": 3,
    }.get(family, 3)
    return {
        "id": identifier,
        "description": description,
        "family": family,
        "invasiveness": invasiveness,
        "interventions": interventions,
    }


def generate_candidates(packet: dict[str, Any]) -> list[dict[str, Any]]:
    """Create a small, explicit search space from the first paired mismatch."""
    cycle = int(packet["divergence"]["cycle"])
    focus = _primary_focus(packet)
    java_id = int(focus["java_id"])
    oracle = focus.get("oracle") or {}
    findings = [item for item in packet["divergence"].get("findings", [])
                if item.get("unit") in (None, focus["native_slot"])]
    fields = {item.get("field") for item in findings}
    candidates = [_candidate(
        "baseline", "Unmodified Java replay", "control", [],
    )]

    if fields & {"x", "y"} and all(isinstance(oracle.get(key), int)
                                      for key in ("x", "y")):
        candidates.extend([
            _candidate(
                "pre-delay-one-cycle",
                "Give the focused unit one wait cycle before the divergent tick",
                "timing",
                [{"phase": "pre", "cycle": cycle, "unit": java_id,
                  "operation": "set-wait", "value": "1"}],
            ),
            _candidate(
                "pre-force-replan",
                "Discard the focused route before the divergent tick",
                "route-choice",
                [{"phase": "pre", "cycle": cycle, "unit": java_id,
                  "operation": "clear-path", "value": ""}],
            ),
            _candidate(
                "post-oracle-position-clamp",
                "Clamp the focused unit to the oracle tile after the divergent tick",
                "state-clamp",
                [{"phase": "post", "cycle": cycle, "unit": java_id,
                  "operation": "set-tile",
                  "value": f"{oracle['x']},{oracle['y']}"}],
            ),
        ])

    if "order" in fields and isinstance(oracle.get("order"), str):
        wanted = oracle["order"].upper()
        candidates.extend([
            _candidate(
                "pre-oracle-order",
                "Enter the divergent tick under the oracle order",
                "transition-timing",
                [{"phase": "pre", "cycle": cycle, "unit": java_id,
                  "operation": "set-order", "value": wanted}],
            ),
            _candidate(
                "post-oracle-order",
                "Continue after the divergent tick under the oracle order",
                "transition-result",
                [{"phase": "post", "cycle": cycle, "unit": java_id,
                  "operation": "set-order", "value": wanted}],
            ),
            _candidate(
                "post-oracle-reported-order",
                "Change only the reported current action at the divergent boundary",
                "surface-label",
                [{"phase": "post", "cycle": cycle, "unit": java_id,
                  "operation": "set-reported-order", "value": wanted}],
            ),
        ])

    if "hp" in fields and isinstance(oracle.get("hp"), int):
        candidates.append(_candidate(
            "post-oracle-hp-clamp",
            "Clamp focused hit points to the oracle after the divergent tick",
            "state-clamp",
            [{"phase": "post", "cycle": cycle, "unit": java_id,
              "operation": "set-hp", "value": str(oracle["hp"])}],
        ))
    return candidates


def plan_from_packet(packet: dict[str, Any]) -> dict[str, Any]:
    candidates = generate_candidates(packet)
    return {
        "schema": SCHEMA,
        "case": packet["case"]["id"],
        "cycle": int(packet["divergence"]["cycle"]),
        "candidates": candidates,
        "candidate_count": len(candidates),
        "policy": {
            "bounded_operations_only": True,
            "production_engine_hooks": False,
            "automatic_source_changes": False,
            "acceptance_authority": "authenticated full regression gate",
            "warning": (
                "A winning intervention is a causal lead, not a source fix. "
                "State clamps can prove downstream sufficiency without proving "
                "the hidden Battle.net rule."
            ),
        },
    }


def _coarse_order(raw: Any) -> str:
    text = str(raw)
    if text.isdigit():
        return UPSTREAM_ORDERS.get(int(text), "?" + text)
    return PORT_ORDERS.get(text.upper(), "?" + text)


def _cycle_mismatches(oracle: dict[str, Any], java: dict[str, Any],
        pairing: dict[int, int]) -> list[dict[str, Any]]:
    mismatches: list[dict[str, Any]] = []
    if oracle["seed"] != java["seed"]:
        mismatches.append({"kind": "seed", "oracle": oracle["seed"],
                           "java": java["seed"]})
    for player in sorted(set(oracle["players"]) | set(java["players"])):
        left, right = oracle["players"].get(player), java["players"].get(player)
        if left != right:
            mismatches.append({"kind": "bank", "player": player,
                               "oracle": left, "java": right})
    paired_java = set()
    for native_id in sorted(oracle["units"]):
        left = oracle["units"][native_id]
        java_id = pairing.get(native_id)
        right = java["units"].get(java_id) if java_id is not None else None
        if right is None:
            mismatches.append({"kind": "population", "native_unit": native_id,
                               "java_unit": java_id, "side": "oracle"})
            continue
        paired_java.add(java_id)
        for field in ("type", "player", "x", "y", "hp", "removed"):
            if left[field] != right[field]:
                mismatches.append({"kind": "unit", "native_unit": native_id,
                                   "java_unit": java_id, "field": field,
                                   "oracle": left[field], "java": right[field]})
        native_order = _coarse_order(left["order"])
        java_order = _coarse_order(right["order"])
        if native_order != java_order and "?" not in native_order + java_order:
            mismatches.append({"kind": "unit", "native_unit": native_id,
                               "java_unit": java_id, "field": "order",
                               "oracle": native_order, "java": java_order})
    for java_id in sorted(set(java["units"]) - paired_java):
        mismatches.append({"kind": "population", "java_unit": java_id,
                           "side": "java"})
    return mismatches


def score_traces(oracle: dict[int, dict[str, Any]],
        java: dict[int, dict[str, Any]], *, divergence_cycle: int,
        native_unit: int, java_unit: int,
        focus_fields: set[str]) -> dict[str, Any]:
    shared = sorted(set(oracle) & set(java))
    if not shared:
        raise ValueError("counterfactual replay produced no shared cycles")
    pairings = align_trace_units(oracle, java)
    per_cycle = []
    first_divergence = None
    total = 0
    future_exact = 0
    future_focus_exact = 0
    future_focus_error = 0
    for cycle in shared:
        mismatches = _cycle_mismatches(
            oracle[cycle], java[cycle], pairings.get(cycle, {}))
        if mismatches and first_divergence is None:
            first_divergence = cycle
        total += len(mismatches)
        focus = [item for item in mismatches
                 if item.get("native_unit") == native_unit
                 and item.get("java_unit") in (None, java_unit)
                 and (not focus_fields or item.get("field") in focus_fields)]
        if cycle >= divergence_cycle:
            future_exact += not mismatches
            future_focus_exact += not focus
            future_focus_error += len(focus)
        per_cycle.append({
            "cycle": cycle,
            "mismatch_count": len(mismatches),
            "focus_mismatch_count": len(focus),
            "mismatches": mismatches[:12],
        })
    through = shared[-1]
    clean_through = through if first_divergence is None else first_divergence - 1
    return {
        "through": through,
        "first_divergence_cycle": first_divergence,
        "clean_through": clean_through,
        "future_cycles": through - divergence_cycle + 1,
        "future_exact_cycles": int(future_exact),
        "future_focus_exact_cycles": int(future_focus_exact),
        "future_focus_mismatches": future_focus_error,
        "total_mismatches": total,
        "per_cycle": per_cycle,
    }


def rank_results(results: list[dict[str, Any]]) -> list[dict[str, Any]]:
    baseline = next(item for item in results if item["candidate"]["id"] == "baseline")
    base = baseline["score"]
    for item in results:
        score = item["score"]
        score["delta"] = {
            "clean_through": score["clean_through"] - base["clean_through"],
            "future_exact_cycles": (score["future_exact_cycles"]
                                     - base["future_exact_cycles"]),
            "future_focus_exact_cycles": (score["future_focus_exact_cycles"]
                                           - base["future_focus_exact_cycles"]),
            "total_mismatches": score["total_mismatches"]
                                - base["total_mismatches"],
        }
        if item is baseline:
            classification, confidence = "baseline", "control"
        elif score["clean_through"] > base["clean_through"]:
            classification = "frontier-advanced"
            confidence = ("high" if score["future_focus_exact_cycles"]
                          >= min(3, score["future_cycles"]) else "medium")
        elif score["future_focus_exact_cycles"] \
                >= base["future_focus_exact_cycles"] + 2 \
                and score["total_mismatches"] < base["total_mismatches"]:
            classification, confidence = "causal-lead", "medium"
        elif score["future_focus_exact_cycles"] \
                == base["future_focus_exact_cycles"] + 1 \
                and item["candidate"]["family"] in ("state-clamp", "surface-label"):
            classification, confidence = "surface-only", "low"
        elif score["clean_through"] < base["clean_through"] \
                or score["total_mismatches"] > base["total_mismatches"]:
            classification, confidence = "harmful", "high"
        else:
            classification, confidence = "no-effect", "high"
        item["classification"] = classification
        item["confidence"] = confidence

    def key(item: dict[str, Any]) -> tuple[int, int, int, int, int, int]:
        score = item["score"]
        return (score["clean_through"], score["future_focus_exact_cycles"],
                score["future_exact_cycles"], -score["total_mismatches"],
                -int(item["candidate"].get("invasiveness", 3)),
                item["candidate"]["id"] != "baseline")

    ranked = sorted(results, key=key, reverse=True)
    for rank, item in enumerate(ranked, 1):
        item["rank"] = rank
    return ranked


def _spec_text(candidate: dict[str, Any]) -> str:
    lines = ["# bne-counterfactual-v1"]
    for item in candidate["interventions"]:
        values = [str(item[key]) for key in
                  ("phase", "cycle", "unit", "operation", "value")]
        if any("\t" in value or "\n" in value for value in values):
            raise ValueError("counterfactual values cannot contain tabs or newlines")
        lines.append("\t".join(values))
    return "\n".join(lines) + "\n"


def _java_command(case: bne_java.Case, trace: Path, *, through: int,
        asset_pack: Path, source_dir: Path, java_wrapper: Path | None,
        java: str, spec: Path | None) -> list[str]:
    launcher = ([str(java_wrapper), java] if java_wrapper is not None else [java])
    command = [
        *launcher,
        "-cp", bne_java.java_classpath(),
        f"-Dchonkcraft.pack={asset_pack}",
        "-Djava.awt.headless=true",
        f"-Dchonkcraft.trace.seed={case.seed}",
        "-Dchonkcraft.trace.profile=bne",
    ]
    if spec is not None:
        command.append(f"-Dchonkcraft.trace.counterfactual={spec}")
    command.extend((
        "net.chonkbase.chonkcraft.engine.parity.EngineTrace",
        case.java_map, str(min(case.cycles, through)), str(trace),
    ))
    return command


def _run_candidate(candidate: dict[str, Any], case: bne_java.Case,
        output: Path, *, through: int, asset_pack: Path, source_dir: Path,
        java_wrapper: Path | None, java: str,
        oracle: dict[int, dict[str, Any]], packet: dict[str, Any]) \
        -> dict[str, Any]:
    root = output / "candidates" / candidate["id"]
    root.mkdir(parents=True, exist_ok=True)
    spec = None
    if candidate["interventions"]:
        spec = root / "interventions.tsv"
        _write_text(spec, _spec_text(candidate))
    trace = root / "java.trace.txt"
    started = time.monotonic()
    completed = subprocess.run(
        _java_command(case, trace, through=through, asset_pack=asset_pack,
                      source_dir=source_dir, java_wrapper=java_wrapper,
                      java=java, spec=spec),
        cwd=ROOT, check=False, capture_output=True, text=True,
    )
    _write_text(root / "stdout.txt", completed.stdout)
    _write_text(root / "stderr.txt", completed.stderr)
    if completed.returncode != 0:
        raise RuntimeError(
            f"candidate {candidate['id']} failed ({completed.returncode}): "
            f"{(completed.stdout + completed.stderr)[-2000:]}"
        )
    bne_compare.validate_java_trace_cycles(trace, min(case.cycles, through))
    with trace.open("rb") as source:
        java_trace = parse_trace(source)
    focus = _primary_focus(packet)
    findings = packet["divergence"].get("findings", [])
    focus_fields = {str(item["field"]) for item in findings
                    if item.get("unit") == focus["native_slot"]
                    and item.get("field")}
    score = score_traces(
        oracle, java_trace,
        divergence_cycle=int(packet["divergence"]["cycle"]),
        native_unit=int(focus["native_slot"]),
        java_unit=int(focus["java_id"]), focus_fields=focus_fields,
    )
    _write_json(root / "score.json", score)
    return {
        "candidate": candidate,
        "score": score,
        "seconds": round(time.monotonic() - started, 6),
        "trace": {"path": str(trace.relative_to(output)),
                  **bne_java.file_identity(trace)},
        "spec": ({"path": str(spec.relative_to(output)),
                  **bne_java.file_identity(spec)} if spec is not None else None),
        "process": {
            "stdout": str((root / "stdout.txt").relative_to(output)),
            "stderr": str((root / "stderr.txt").relative_to(output)),
        },
    }


def _normalize_oracle(case: bne_java.Case, through: int, path: Path) \
        -> dict[int, dict[str, Any]]:
    expected = min(case.cycles, through)
    with zipfile.ZipFile(case.fixture) as archive, \
            archive.open("trace.txt") as trace_source, \
            archive.open("state.bin") as state_source, \
            path.open("wb") as destination:
        bne_compare.normalize_fixture_trace(
            trace_source, state_source, destination, expected
        )
    with path.open("rb") as source:
        return parse_trace(source)


def _summary(manifest: dict[str, Any]) -> str:
    lines = [
        "# BNE counterfactual replay", "",
        f"- Case: `{manifest['case']}`",
        f"- Divergence: cycle **{manifest['divergence_cycle']}**",
        f"- Replayed through: **{manifest['through']}**",
        f"- Candidates: **{len(manifest['results'])}**",
        "- Production engine hooks: **none**", "",
        "| Rank | Candidate | Classification | Clean through | "
        "Focus frames matched | Total mismatches |",
        "|---:|---|---|---:|---:|---:|",
    ]
    for item in manifest["results"]:
        score = item["score"]
        lines.append(
            f"| {item['rank']} | `{item['candidate']['id']}` | "
            f"{item['classification']} ({item['confidence']}) | "
            f"{score['clean_through']} | "
            f"{score['future_focus_exact_cycles']}/{score['future_cycles']} | "
            f"{score['total_mismatches']} |"
        )
    lines.extend(["", "## Interpretation", ""])
    winner = manifest.get("lead")
    if winner is None:
        lines.append("No intervention improved on the control replay.")
    else:
        lines.append(
            f"`{winner['candidate']['id']}` is the strongest causal lead: "
            f"**{winner['classification']}** with "
            f"**{winner['confidence']}** confidence."
        )
        lines.append("")
        lines.append(winner["candidate"]["description"] + ".")
    lines.extend([
        "", "A counterfactual lead is not an accepted engine fix. It still "
        "requires a source-level explanation, focused regression, and the "
        "authenticated 52-case gate.", "",
    ])
    return "\n".join(lines)


def _run_counterfactual(triage_run: Path, case_id: str, artifact_root: Path,
        *, through: int = 30, jobs: int = 4, maven: str = "mvn",
        java: str = "java", skip_build: bool = False) -> tuple[int, Path]:
    triage_run = triage_run.expanduser().resolve()
    triage_manifest_path = triage_run / "manifest.json"
    triage = _json(triage_manifest_path)
    verify_manifest(triage_run, triage, triage["request_sha256"])
    packet_record = next((item for item in triage.get("packets", [])
                          if item.get("case") == case_id), None)
    if packet_record is None:
        raise ValueError(f"triage run has no packet for {case_id}")
    packet_path = _safe(triage_run, packet_record["packet"])
    packet = _json(packet_path)
    divergence = int(packet["divergence"]["cycle"])
    if through < divergence:
        raise ValueError(
            f"--through {through} ends before divergence cycle {divergence}"
        )
    request_record = triage["request"]
    index = Path(request_record["index"]["path"]).resolve()
    asset = request_record["asset_source"]
    if asset.get("kind") != "chonkpack":
        raise ValueError("counterfactual replay currently requires a chonkpack")
    asset_pack = Path(asset["path"]).resolve()
    if bne_java.file_identity(asset_pack) != {
            "bytes": asset["bytes"], "sha256": asset["sha256"]}:
        raise ValueError("triage chonkpack identity changed")
    source_dir = Path(
        request_record["runtime"]["source_workspace"]["path"]
    ).resolve()
    wrapper_record = request_record["runtime"].get("java_wrapper")
    java_wrapper = (Path(wrapper_record["path"]).resolve()
                    if isinstance(wrapper_record, dict) else None)
    cases = bne_java.load_index(index)
    case = next((item for item in cases if item.case_id == case_id), None)
    if case is None:
        raise ValueError(f"corpus index has no case {case_id}")
    if case.fixture_id != packet["case"].get("fixture_id"):
        raise ValueError("packet and corpus fixture identities differ")
    candidates = generate_candidates(packet)
    if len(candidates) == 1:
        raise ValueError("packet has no supported bounded counterfactual candidates")

    if not skip_build:
        subprocess.run(
            [maven, "-o", "test-compile", "-pl", "engine", "-am"],
            cwd=ROOT, check=True,
        )
    request = {
        "schema": SCHEMA,
        "triage_manifest": {"path": str(triage_manifest_path),
                            **bne_java.file_identity(triage_manifest_path)},
        "packet": {"path": str(packet_path),
                   **bne_java.file_identity(packet_path)},
        "engine": bne_java._git_identity(),
        "compiled_classpath": bne_java._compiled_classpath_identity(),
        "implementation": {
            str(path.relative_to(ROOT)): bne_java.file_identity(path)
            for path in IMPLEMENTATION
        },
        "case": case_id,
        "fixture": {"path": str(case.fixture),
                    **bne_java.file_identity(case.fixture)},
        "asset_source": asset,
        "source_dir": str(source_dir),
        "through": min(case.cycles, through),
        "candidates": candidates,
    }
    request_sha256 = canonical_digest(request)
    artifact_root = artifact_root.expanduser().resolve()
    run_root = artifact_root / "runs" / request_sha256
    manifest_path = run_root / "manifest.json"
    if manifest_path.is_file():
        manifest = _json(manifest_path)
        for relative, identity in manifest.get("artifacts", {}).items():
            path = _safe(run_root, relative)
            if not path.is_file() or bne_java.file_identity(path) != identity:
                raise ValueError(f"counterfactual artifact identity changed: {relative}")
        pointer = {
            "schema": SCHEMA, "request_sha256": request_sha256,
            "case": case_id, "run": str(run_root.relative_to(artifact_root)),
            "manifest": str(manifest_path.relative_to(artifact_root)),
            "manifest_identity": bne_java.file_identity(manifest_path),
            "lead": manifest.get("lead", {}).get("candidate", {}).get("id"),
        }
        _write_json(artifact_root / "latest.json", pointer)
        _write_json(artifact_root / f"latest-{case_id}.json", pointer)
        return 0, run_root

    run_root.mkdir(parents=True, exist_ok=True)
    oracle_path = run_root / "oracle.trace.txt"
    oracle = _normalize_oracle(case, through, oracle_path)
    started = time.monotonic()
    results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as pool:
        pending = {
            pool.submit(
                _run_candidate, candidate, case, run_root,
                through=min(case.cycles, through), asset_pack=asset_pack,
                source_dir=source_dir, java_wrapper=java_wrapper, java=java,
                oracle=oracle, packet=packet,
            ): candidate for candidate in candidates
        }
        for future in concurrent.futures.as_completed(pending):
            results.append(future.result())
    ranked = rank_results(results)
    lead = next((item for item in ranked
                 if item["candidate"]["id"] != "baseline"
                 and item["classification"] in
                 ("frontier-advanced", "causal-lead")), None)
    manifest: dict[str, Any] = {
        "schema": SCHEMA,
        "request_sha256": request_sha256,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "request": request,
        "case": case_id,
        "divergence_cycle": divergence,
        "through": min(case.cycles, through),
        "seconds": round(time.monotonic() - started, 6),
        "results": ranked,
        "lead": lead,
        "policy": plan_from_packet(packet)["policy"],
    }
    _write_text(run_root / "NEXT.md", _summary(manifest))
    manifest["artifacts"] = inventory_files(
        run_root,
        [path for path in run_root.rglob("*") if path.is_file()],
    )
    _write_json(manifest_path, manifest)
    pointer = {
        "schema": SCHEMA, "request_sha256": request_sha256,
        "case": case_id, "run": str(run_root.relative_to(artifact_root)),
        "manifest": str(manifest_path.relative_to(artifact_root)),
        "manifest_identity": bne_java.file_identity(manifest_path),
        "lead": lead["candidate"]["id"] if lead else None,
    }
    _write_json(artifact_root / "latest.json", pointer)
    _write_json(artifact_root / f"latest-{case_id}.json", pointer)
    return 0, run_root


def run_counterfactual(triage_run: Path, case_id: str, artifact_root: Path,
        *, through: int = 30, jobs: int = 4, maven: str = "mvn",
        java: str = "java", skip_build: bool = False) -> tuple[int, Path]:
    """Serialize publishers so two agents cannot compose the same run."""
    artifact_root = artifact_root.expanduser().resolve()
    artifact_root.mkdir(parents=True, exist_ok=True)
    lock_path = artifact_root / ".compose.lock"
    with lock_path.open("a+", encoding="ascii") as lock:
        try:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise RuntimeError(
                "another counterfactual publisher is already active"
            ) from error
        return _run_counterfactual(
            triage_run, case_id, artifact_root, through=through, jobs=jobs,
            maven=maven, java=java, skip_build=skip_build,
        )


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("triage_run", type=Path,
                        help="authenticated triage run directory")
    result.add_argument("--case", required=True,
                        help="packet case from the triage run")
    result.add_argument("--artifact-root", type=Path,
                        default=ROOT / ".bne-counterfactual")
    result.add_argument("--through", type=int, default=30)
    result.add_argument("--jobs", type=int, default=4)
    result.add_argument("--maven", default="mvn")
    result.add_argument("--java", default="java")
    result.add_argument("--skip-build", action="store_true")
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        if args.through <= 0 or args.jobs <= 0:
            raise ValueError("--through and --jobs must be positive")
        status, run_root = run_counterfactual(
            args.triage_run, args.case, args.artifact_root,
            through=args.through, jobs=args.jobs, maven=args.maven,
            java=args.java, skip_build=args.skip_build,
        )
        print((run_root / "NEXT.md").read_text(encoding="utf-8"), end="")
        print(f"\nDurable counterfactual run: {run_root}")
        return status
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError,
            zipfile.BadZipFile, json.JSONDecodeError) as error:
        print(f"bne-counterfactual: {error}", file=os.sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
