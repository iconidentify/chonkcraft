#!/usr/bin/env python3
"""Match an authenticated BNE predicate slice to Java decision evidence.

The bridge is deliberately diagnostic-only.  It consumes an already-proved
native semantic slice, opt-in Java causal JSONL, a reviewed symbol atlas, and
the Java source tree.  It normalizes both sides into a small expression
language, ranks dynamic and static candidates, states any remaining semantic
gap, and emits a boundary-test specification.  It never edits engine source or
claims an unresolved native table has a known Java meaning.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from typing import Any, Iterable

from bne_triage import canonical_digest, file_identity, inventory_files


SCHEMA = 1
ROOT = Path(__file__).resolve().parents[3]
DEFAULT_ATLAS = ROOT / "tools/bne-harness/semantic-bridge-atlas.json"
IMPLEMENTATION = (Path(__file__), Path(__file__).with_name("bne_triage.py"))
CASTS = {"u8", "u16", "s8", "s16", "low8", "low16"}
COMMUTATIVE = {"add", "mul", "max", "min", "and", "or", "xor"}
COMPARISON_INVERSE = {">": "<=", ">=": "<", "<": ">=", "<=": ">",
                      "==": "!=", "!=": "=="}
TOKEN = re.compile(r"-?\d+|[A-Za-z_][A-Za-z0-9_.\[\]*]*|[(),]")
JAVA_COMPARISON = re.compile(r"(?<![<>=!])(?:<=|>=|==|!=|<|>)(?![<>=])")


@dataclass(frozen=True)
class Expression:
    op: str
    args: tuple[Any, ...]

    def render(self) -> str:
        if self.op == "symbol":
            return str(self.args[0])
        if self.op == "const":
            return str(self.args[0])
        return f"{self.op}({','.join(render(item) for item in self.args)})"


def render(value: Any) -> str:
    return value.render() if isinstance(value, Expression) else str(value)


def _json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"expected a JSON object: {path}")
    return value


def _write(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(
                mode="w", encoding="utf-8", prefix=path.name + ".",
                suffix=".tmp", dir=path.parent, delete=False) as handle:
            temporary = Path(handle.name)
            handle.write(value)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _write_json(path: Path, value: object) -> None:
    _write(path, json.dumps(value, indent=2, sort_keys=True) + "\n")


def expression_from_native(value: object) -> Expression:
    if not isinstance(value, dict) or not isinstance(value.get("op"), str):
        raise ValueError("native semantic AST is malformed")
    op = value["op"]
    if op in {"symbol", "unknown", "const"}:
        key = "value"
        if key not in value:
            raise ValueError(f"native {op} has no value")
        return Expression("symbol" if op == "unknown" else op, (value[key],))
    args = value.get("args")
    if not isinstance(args, list):
        raise ValueError(f"native {op} has no argument list")
    converted = tuple(expression_from_native(item) if isinstance(item, dict)
                      else item for item in args)
    if op == "table" and converted:
        address = int(converted[0])
        converted = (Expression("symbol", (f"bne.table_{address:08x}",)),
                     *converted[1:])
    return Expression(op, converted)


class ExpressionParser:
    def __init__(self, text: str):
        self.tokens = TOKEN.findall(text)
        compact = re.sub(r"\s+", "", text)
        if "".join(self.tokens) != compact:
            raise ValueError(f"unsupported Java semantic expression: {text}")
        self.index = 0

    def parse(self) -> Expression:
        result = self._expression()
        if self.index != len(self.tokens):
            raise ValueError("trailing Java semantic expression tokens")
        return result

    def _expression(self) -> Expression:
        if self.index >= len(self.tokens):
            raise ValueError("incomplete Java semantic expression")
        token = self.tokens[self.index]
        self.index += 1
        if re.fullmatch(r"-?\d+", token):
            return Expression("const", (int(token),))
        if self.index < len(self.tokens) and self.tokens[self.index] == "(":
            self.index += 1
            args: list[Expression] = []
            if self.index < len(self.tokens) and self.tokens[self.index] != ")":
                while True:
                    args.append(self._expression())
                    if self.index >= len(self.tokens):
                        raise ValueError("unterminated Java semantic call")
                    separator = self.tokens[self.index]
                    self.index += 1
                    if separator == ")":
                        break
                    if separator != ",":
                        raise ValueError("expected comma in Java semantic call")
            else:
                self.index += 1
            return Expression(token, tuple(args))
        return Expression("symbol", (token,))


def parse_expression(text: str) -> Expression:
    return ExpressionParser(text).parse()


def _atlas_aliases(atlas: dict[str, Any], side: str) -> list[tuple[str, str]]:
    result = []
    for family in atlas.get("symbol_families", []):
        canonical = family.get("canonical")
        names = family.get(side, [])
        if not isinstance(canonical, str) or not isinstance(names, list):
            raise ValueError("semantic atlas symbol family is malformed")
        for name in names:
            if not isinstance(name, str):
                raise ValueError("semantic atlas symbol aliases must be strings")
            result.append((name, canonical))
    return result


def _alias_symbol(name: str, side: str, atlas: dict[str, Any]) -> str:
    for pattern, canonical in _atlas_aliases(atlas, side):
        regex = re.escape(pattern).replace(r"\*", r"\d+")
        if re.fullmatch(regex, name):
            return canonical
    return name


def normalize(expression: Expression, side: str,
        atlas: dict[str, Any]) -> Expression:
    if expression.op == "symbol":
        return Expression("symbol", (_alias_symbol(
            str(expression.args[0]), side, atlas,
        ),))
    if expression.op == "const":
        return expression
    args = tuple(normalize(item, side, atlas)
                 if isinstance(item, Expression) else item
                 for item in expression.args)
    if expression.op in CASTS and len(args) == 1:
        # Casts do not change structural matching. They remain visible in the
        # original expression and unresolved-source report.
        return args[0]
    if expression.op == "abs" and len(args) == 1 \
            and isinstance(args[0], Expression) and args[0].op == "sub":
        left, right = args[0].args
        ordered = tuple(sorted((left, right), key=render))
        return Expression("abs", (Expression("sub", ordered),))
    if expression.op in COMMUTATIVE:
        args = tuple(sorted(args, key=render))
    return Expression(expression.op, args)


def _nodes(expression: Expression) -> set[str]:
    result = {expression.render()}
    for item in expression.args:
        if isinstance(item, Expression):
            result.update(_nodes(item))
    return result


def _symbols(expression: Expression) -> set[str]:
    if expression.op == "symbol":
        return {str(expression.args[0])}
    result: set[str] = set()
    for item in expression.args:
        if isinstance(item, Expression):
            result.update(_symbols(item))
    return result


def _ops(expression: Expression) -> set[str]:
    result = {expression.op}
    for item in expression.args:
        if isinstance(item, Expression):
            result.update(_ops(item))
    return result


def _shape_similarity(left: Expression, right: Expression) -> float:
    """Compare expression structure while treating unequal leaves honestly."""
    if left.op != right.op:
        return 0.0
    if left.op in {"symbol", "const"}:
        return 1.0 if left == right else 0.0
    if len(left.args) != len(right.args):
        return 0.0
    pairs = [(a, b) for a, b in zip(left.args, right.args)
             if isinstance(a, Expression) and isinstance(b, Expression)]
    if len(pairs) != len(left.args):
        return 0.0
    return (1.0 + sum(_shape_similarity(a, b) for a, b in pairs)) \
        / (1.0 + len(pairs))


def _best_subtree_shape(needle: Expression, haystack: Expression) -> float:
    result = _shape_similarity(needle, haystack)
    for item in haystack.args:
        if isinstance(item, Expression):
            result = max(result, _best_subtree_shape(needle, item))
    return result


def _jaccard(left: set[str], right: set[str]) -> float:
    return len(left & right) / len(left | right) if left or right else 1.0


def evaluate(left: int, operator: str, right: int) -> bool:
    return {
        ">": left > right, ">=": left >= right,
        "<": left < right, "<=": left <= right,
        "==": left == right, "!=": left != right,
    }[operator]


def parse_java_trace(path: Path) -> list[dict[str, Any]]:
    result = []
    for line_number, line in enumerate(
            path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError as exception:
            raise ValueError(f"invalid Java JSONL at line {line_number}") from exception
        if event.get("kind") != "semantic.predicate":
            continue
        fields = event.get("fields")
        required = ("predicate_id", "lhs_expression", "lhs", "operator",
                    "rhs_expression", "rhs", "result", "source")
        if not isinstance(fields, dict) or any(key not in fields for key in required):
            raise ValueError(f"semantic predicate at line {line_number} is incomplete")
        operator = str(fields["operator"])
        if operator not in COMPARISON_INVERSE:
            raise ValueError(f"unsupported Java predicate operator: {operator}")
        left, right = int(fields["lhs"]), int(fields["rhs"])
        observed = bool(fields["result"])
        result.append({
            "line": line_number, "cycle": int(event.get("cycle", 0)),
            "subject": event.get("subject"),
            "predicate_id": str(fields["predicate_id"]),
            "lhs": parse_expression(str(fields["lhs_expression"])),
            "rhs": parse_expression(str(fields["rhs_expression"])),
            "lhs_value": left, "rhs_value": right, "operator": operator,
            "result": observed, "self_check": evaluate(left, operator, right) == observed,
            "source": str(fields["source"]),
            "decision": fields.get("decision"),
        })
    if not result:
        raise ValueError("Java trace contains no semantic.predicate events")
    return result


def _operator_relation(native: str, java: str) -> str:
    if native == java:
        return "same"
    if COMPARISON_INVERSE[native] == java:
        return "complement"
    if (native, java) in {(">", "<"), ("<", ">"),
                         (">=", "<="), ("<=", ">=")}:
        return "direction-reversed"
    return "different-boundary"


def rank_dynamic(native: dict[str, Any], events: Iterable[dict[str, Any]],
        atlas: dict[str, Any]) -> list[dict[str, Any]]:
    native_lhs = normalize(expression_from_native(
        native["predicate"]["lhs"]["ast"]), "native", atlas)
    native_rhs = normalize(expression_from_native(
        native["predicate"]["rhs"]["ast"]), "native", atlas)
    native_operator = str(native["branch"]["operator"])
    native_symbols = _symbols(native_lhs) | _symbols(native_rhs)
    native_ops = _ops(native_lhs) | _ops(native_rhs)
    candidates = []
    for event in events:
        java_lhs = normalize(event["lhs"], "java", atlas)
        java_rhs = normalize(event["rhs"], "java", atlas)
        java_symbols = _symbols(java_lhs) | _symbols(java_rhs)
        java_ops = _ops(java_lhs) | _ops(java_rhs)
        lhs_exact = native_lhs == java_lhs
        rhs_exact = native_rhs == java_rhs
        native_component = native_lhs.render() in _nodes(java_lhs)
        subtree_shape = _best_subtree_shape(native_lhs, java_lhs)
        shared_symbols = _jaccard(native_symbols, java_symbols)
        shared_ops = _jaccard(native_ops, java_ops)
        operator_relation = _operator_relation(native_operator, event["operator"])
        values_match = (int(native["observed"]["lhs"]) == event["lhs_value"]
                        and int(native["observed"]["rhs"]) == event["rhs_value"])
        score = (0.32 if lhs_exact else 0.22 if native_component
                 else 0.18 if subtree_shape >= 0.7 else 0.0)
        score += 0.18 * shared_symbols + 0.15 * shared_ops
        score += 0.12 if operator_relation in {"same", "complement"} else 0.03
        score += 0.13 if rhs_exact else 0.0
        score += 0.07 if values_match else 0.0
        score += 0.03 if event["self_check"] else 0.0
        if lhs_exact and rhs_exact and operator_relation == "same":
            relation = "equivalent-expression"
        elif lhs_exact and rhs_exact and operator_relation == "complement":
            relation = "complementary-expression"
        elif native_component:
            relation = "native-component-of-java"
        elif subtree_shape >= 0.7 and shared_symbols > 0:
            relation = "same-coordinate-shape-unresolved-goal"
        elif shared_symbols >= 0.5 and shared_ops >= 0.5:
            relation = "same-domain-different-expression"
        else:
            relation = "investigative-candidate"
        candidates.append({
            "predicate_id": event["predicate_id"], "cycle": event["cycle"],
            "subject": event["subject"], "source": event["source"],
            "decision": event["decision"], "score": round(min(score, 1.0), 6),
            "relation": relation, "operator_relation": operator_relation,
            "native_lhs": native_lhs.render(), "java_lhs": java_lhs.render(),
            "native_rhs": native_rhs.render(), "java_rhs": java_rhs.render(),
            "java_observed": {"lhs": event["lhs_value"],
                              "rhs": event["rhs_value"],
                              "result": event["result"]},
            "self_check": event["self_check"],
            "shared_symbols": sorted(native_symbols & java_symbols),
            "unmatched_native_symbols": sorted(native_symbols - java_symbols),
            "subtree_shape_similarity": round(subtree_shape, 6),
            "values_match": values_match,
        })
    return sorted(candidates, key=lambda item: (
        -item["score"], item["cycle"], item["source"], item["predicate_id"],
    ))


def _source_snapshot(source_root: Path) -> dict[str, Any]:
    digest = hashlib.sha256()
    files = sorted(source_root.rglob("*.java"))
    for path in files:
        relative = str(path.relative_to(source_root))
        digest.update(relative.encode("utf-8") + b"\0")
        with path.open("rb") as source:
            for block in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(block)
    return {"path": str(source_root), "java_files": len(files),
            "sha256": digest.hexdigest()}


def rank_source(native: dict[str, Any], source_root: Path,
        atlas: dict[str, Any], *, limit: int = 12) -> list[dict[str, Any]]:
    lhs = normalize(expression_from_native(
        native["predicate"]["lhs"]["ast"]), "native", atlas)
    expected = set()
    for symbol in _symbols(lhs):
        expected.update(re.findall(r"[a-z]+", symbol.lower()))
    expected.update(op for op in _ops(lhs) if op not in {"symbol", "const"})
    expected.discard("unit")
    operator = str(native["branch"]["operator"])
    results = []
    for path in sorted(source_root.rglob("*.java")):
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        for index, line in enumerate(lines):
            stripped = line.strip()
            if not stripped or stripped.startswith(("//", "/*", "*", "@")):
                continue
            if not JAVA_COMPARISON.search(line):
                continue
            lo, hi = max(0, index - 20), min(len(lines), index + 21)
            window = "\n".join(item for item in lines[lo:hi]
                               if not item.strip().startswith(("//", "/*", "*")))
            collapsed = re.sub(r"[^a-z0-9]+", " ", window.lower())
            tokens = set(collapsed.split())
            hits = sorted(token for token in expected if token in tokens)
            abs_hit = "math" in tokens and "abs" in tokens
            op_hit = operator in line or COMPARISON_INVERSE[operator] in line
            score = (len(hits) / max(1, len(expected))) * 0.7
            score += 0.18 if abs_hit else 0.0
            score += 0.12 if op_hit else 0.0
            if score < 0.24:
                continue
            results.append({
                "score": round(score, 6),
                "path": str(path.relative_to(source_root)), "line": index + 1,
                "statement": line.strip(), "matched_tokens": hits,
                "heuristic": True,
            })
    results.sort(key=lambda item: (-item["score"], item["path"], item["line"]))
    return results[:limit]


def cadence_signature(cycles: Iterable[int]) -> dict[str, Any]:
    """Summarize recurrence without turning one interval into a proved period."""
    ordered = sorted(set(int(cycle) for cycle in cycles))
    gaps = [right - left for left, right in zip(ordered, ordered[1:])]
    stable = len(gaps) >= 2 and len(set(gaps)) == 1
    if stable:
        classification = "stable-period"
    elif len(gaps) == 1:
        classification = "single-gap"
    elif gaps:
        classification = "irregular"
    else:
        classification = "insufficient"
    return {
        "cycles": ordered,
        "gaps": gaps,
        "classification": classification,
        "stable_period": gaps[0] if stable else None,
        "tentative_gap": gaps[0] if len(gaps) == 1 else None,
        "largest_gap": max(gaps) if gaps else None,
    }


def _diagnosis(native: dict[str, Any], ranked: list[dict[str, Any]],
        events: list[dict[str, Any]]) -> dict[str, Any]:
    best = ranked[0]
    repeated = [item for item in ranked
                if item["predicate_id"] == best["predicate_id"]
                and item["java_lhs"] == best["java_lhs"]
                and item["java_rhs"] == best["java_rhs"]
                and item["operator_relation"] == best["operator_relation"]]
    same_subject = [item for item in repeated
                    if item["subject"] == best["subject"]]
    cadence = cadence_signature(item["cycle"] for item in same_subject)
    reasons = []
    if best["relation"] == "native-component-of-java":
        reasons.append("The native one-axis expression is a component of the Java predicate, not the whole predicate.")
    if best["relation"] == "same-coordinate-shape-unresolved-goal":
        reasons.append("The Java decision has the same absolute coordinate-difference shape, but uses a resource approach coordinate where native uses stored order_x; that goal mapping is not yet proved.")
    if best["operator_relation"] == "complement":
        reasons.append("Java names the complementary side of the same boundary; outcomes must be inverted before comparison.")
    if best["unmatched_native_symbols"]:
        reasons.append("Native symbols remain unmatched: "
                       + ", ".join(best["unmatched_native_symbols"]) + ".")
    if best["native_rhs"] != best["java_rhs"]:
        reasons.append("The Java threshold is not authenticated as the meaning of the native lookup table.")
    if not reasons:
        reasons.append("The normalized expressions and boundary direction agree on the available evidence.")
    useful = best["score"] >= 0.35 and best["self_check"] and len(repeated) >= 2
    equivalence = (best["relation"] in {"equivalent-expression",
                                        "complementary-expression"}
                   and not best["unmatched_native_symbols"])
    return {
        "classification": ("equivalent" if equivalence else
                           "related-boundary" if useful else "investigative"),
        "best_dynamic_candidate": best["predicate_id"],
        "exact_java_source": best["source"],
        "held_out_java_observations": len(repeated),
        "java_subject_cadence": cadence,
        "usefulness_gate_passed": useful,
        "semantic_equivalence_proved": equivalence,
        "reasons": reasons,
    }


def analyze_bridge(semantic: dict[str, Any], events: list[dict[str, Any]],
        atlas: dict[str, Any], source_root: Path) -> dict[str, Any]:
    proof = semantic.get("proof", {})
    if semantic.get("schema") != 2 or not proof.get("passed") \
            or not proof.get("focus_identity_proven"):
        raise ValueError(
            "semantic bridge requires a proved schema-2 slice with focus identity"
        )
    if atlas.get("schema") != 1:
        raise ValueError("unsupported semantic bridge atlas schema")
    native = semantic["anchor"]
    ranked = rank_dynamic(native, events, atlas)
    best = ranked[0]
    diagnosis = _diagnosis(native, ranked, events)
    boundary = semantic.get("boundary_experiment", {})
    return {
        "schema": SCHEMA, "case": semantic["case"],
        "created_at": datetime.now(timezone.utc).isoformat(),
        "native": {
            "cycle": native["cycle"],
            "source": f"BNE branch 0x{int(native['branch']['address']):08x}",
            "expression": native["predicate"]["semantic"],
            "observed": native["observed"],
        },
        "diagnosis": diagnosis,
        "temporal_signature": {
            "java_subject": best["subject"],
            "java": diagnosis["java_subject_cadence"],
            "native_capture_cycles": sorted({
                int(native["cycle"]),
                *(int(item["cycle"])
                  for item in semantic.get("held_out", [])),
            }),
            "native_period_compared": False,
            "reason": (
                "Anchor/control captures prove contrasted observations, not a "
                "native recurrence period. Add repeated focus-scoped captures "
                "before comparing phase or cadence."
            ),
        },
        "dynamic_candidates": ranked[:20],
        "static_candidates": rank_source(native, source_root, atlas),
        "boundary_test": {
            "native_not_taken": boundary.get("flip_to_not_taken"),
            "native_taken": boundary.get("flip_to_taken"),
            "java_predicate_id": diagnosis["best_dynamic_candidate"],
            "java_source": diagnosis["exact_java_source"],
            "requirement": ("Exercise both sides of the native boundary and "
                            "assert the selected Java decision plus final state."),
            "generate_patch": False,
        },
        "proof": {
            "native_semantic_proof_passed": True,
            "java_events_self_checked": all(item["self_check"] for item in events),
            "held_out_java_predicate_seen":
                diagnosis["held_out_java_observations"] >= 2,
            "usefulness_gate_passed": diagnosis["usefulness_gate_passed"],
            "semantic_equivalence_proved":
                diagnosis["semantic_equivalence_proved"],
            "engine_behavior_changed": False,
            "oracle_rerun": False,
        },
    }


def _summary(result: dict[str, Any]) -> str:
    diagnosis = result["diagnosis"]
    best = result["dynamic_candidates"][0]
    lines = [
        "# Cross-Engine Semantic Bridge", "",
        f"- Case: `{result['case']}`",
        f"- Native: `{result['native']['expression']}`",
        f"- Best Java decision: `{best['predicate_id']}` at `{best['source']}`",
        f"- Classification: **{diagnosis['classification']}**",
        f"- Usefulness gate: `{diagnosis['usefulness_gate_passed']}`",
        f"- Semantic equivalence proved: `{diagnosis['semantic_equivalence_proved']}`",
        "", "## What the evidence says", "",
    ]
    lines.extend(f"- {reason}" for reason in diagnosis["reasons"])
    lines.extend([
        "", "## Next boundary test", "",
        f"- Native not-taken point: `{result['boundary_test']['native_not_taken']}`",
        f"- Native taken point: `{result['boundary_test']['native_taken']}`",
        f"- Observe Java predicate `{result['boundary_test']['java_predicate_id']}` ",
        f"  at `{result['boundary_test']['java_source']}`.",
        "- Do not generate a source patch until the unmatched native table/argument is resolved.",
        "", "## Temporal signature", "",
        f"- Java subject: `{result['temporal_signature']['java_subject']}`",
        f"- Observation cycles: "
        f"`{result['temporal_signature']['java']['cycles']}`",
        f"- Gaps: `{result['temporal_signature']['java']['gaps']}` "
        f"(`{result['temporal_signature']['java']['classification']}`)",
        "- Native phase is intentionally not inferred from anchor/control contrast captures.",
        "",
    ])
    return "\n".join(lines)


def run_bridge(semantic_path: Path, java_trace_path: Path, source_root: Path,
        atlas_path: Path, artifact_root: Path) -> tuple[int, Path]:
    semantic_path = semantic_path.expanduser().resolve()
    java_trace_path = java_trace_path.expanduser().resolve()
    source_root = source_root.expanduser().resolve()
    atlas_path = atlas_path.expanduser().resolve()
    semantic, atlas = _json(semantic_path), _json(atlas_path)
    events = parse_java_trace(java_trace_path)
    request = {
        "schema": SCHEMA,
        "implementation": {path.name: file_identity(path) for path in IMPLEMENTATION},
        "semantic_slice": {"path": str(semantic_path), **file_identity(semantic_path)},
        "java_trace": {"path": str(java_trace_path), **file_identity(java_trace_path)},
        "atlas": {"path": str(atlas_path), **file_identity(atlas_path)},
        "java_source": _source_snapshot(source_root),
    }
    request_sha256 = canonical_digest(request)
    artifact_root = artifact_root.expanduser().resolve()
    run_root = artifact_root / "runs" / request_sha256
    manifest_path = run_root / "manifest.json"

    def promote(manifest: dict[str, Any]) -> None:
        pointer = {
            "schema": SCHEMA, "request_sha256": request_sha256,
            "run": str(run_root.relative_to(artifact_root)),
            "manifest": str(manifest_path.relative_to(artifact_root)),
            "manifest_identity": file_identity(manifest_path),
            "case": manifest["result"]["case"],
            "classification": manifest["result"]["diagnosis"]["classification"],
            "usefulness_gate_passed":
                manifest["result"]["proof"]["usefulness_gate_passed"],
        }
        _write_json(artifact_root / "latest.json", pointer)
        _write_json(artifact_root / f"latest-{pointer['case']}.json", pointer)

    if manifest_path.is_file():
        manifest = _json(manifest_path)
        if manifest.get("request_sha256") != request_sha256 \
                or canonical_digest(manifest.get("request")) != request_sha256:
            raise ValueError("cached semantic-bridge request identity changed")
        for relative, identity in manifest.get("artifacts", {}).items():
            if file_identity(run_root / relative) != identity:
                raise ValueError(f"semantic-bridge artifact changed: {relative}")
        promote(manifest)
        return (0 if manifest["result"]["proof"]["usefulness_gate_passed"]
                else 1), run_root

    run_root.mkdir(parents=True, exist_ok=True)
    result = analyze_bridge(semantic, events, atlas, source_root)
    _write_json(run_root / "semantic-bridge.json", result)
    _write(run_root / "NEXT.md", _summary(result))
    manifest = {
        "schema": SCHEMA, "request_sha256": request_sha256,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "request": request, "result": result,
        "artifacts": inventory_files(
            run_root, [run_root / "semantic-bridge.json", run_root / "NEXT.md"],
        ),
    }
    _write_json(manifest_path, manifest)
    promote(manifest)
    return (0 if result["proof"]["usefulness_gate_passed"] else 1), run_root


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("semantic_slice", type=Path)
    result.add_argument("--java-trace", type=Path, required=True)
    result.add_argument("--source-root", type=Path,
                        default=ROOT / "engine/src/main/java")
    result.add_argument("--atlas", type=Path, default=DEFAULT_ATLAS)
    result.add_argument("--artifact-root", type=Path,
                        default=ROOT / ".bne-semantic-bridge")
    return result


def main() -> int:
    args = parser().parse_args()
    status, run_root = run_bridge(
        args.semantic_slice, args.java_trace, args.source_root,
        args.atlas, args.artifact_root,
    )
    print((run_root / "NEXT.md").read_text(encoding="utf-8"), end="")
    print(f"\nDurable semantic-bridge run: {run_root}")
    return status


if __name__ == "__main__":
    raise SystemExit(main())
