#!/usr/bin/env python3
"""Chain a wrong field to the rule that would explain it, and stop where proof does."""

from __future__ import annotations

import json
from pathlib import Path
import shlex
from typing import Any


WHY_CHAIN_SCHEMA = 1

ATLAS = Path(__file__).resolve().parents[1] / "semantic-bridge-atlas.json"

HARNESS = "python3 tools/bne-harness/scripts/bne_java.py"

JAVA_TRACE_PLACEHOLDER = "CASE.java.trace.txt"

#: The links, in order. A link is either supported by evidence in this tree or
#: explicitly unknown; there is no third state, because a plausible native fact
#: written as a fact is exactly how a week gets spent on the wrong engine.
LINKS = (
    "first-wrong-field",
    "native-last-writer",
    "native-predicate",
    "semantic-correspondence",
    "java-producer",
    "regression-boundary",
    "candidate-rule-grammar",
    "tournament-plan",
)


def _atlas_families() -> list[dict[str, Any]]:
    if not ATLAS.is_file():
        return []
    return json.loads(ATLAS.read_text(encoding="utf-8")).get("symbol_families", [])


def _canonical_field(finding: dict[str, Any]) -> str | None:
    kind, field = finding.get("kind"), finding.get("field")
    return f"{kind}.{field}" if kind and field else None


def _route_command(route: dict[str, Any], lane: str) -> str | None:
    """Take the command from the route rather than writing a second one.

    The chain used to compose its own instructions -- `lab --case X`,
    `counterfactual --case X --plan-only`, `decision-plan --case X --cycle N`
    -- none of which the argument parser accepts. Two places writing commands
    for one interface is how they drift, so there is one place now.
    """
    for step in route.get("steps", []):
        if step.get("lane") == lane and step.get("command"):
            return str(step["command"])
    return None


def _known(link: str, statement: str, evidence: str, **extra: Any) -> dict[str, Any]:
    return {"link": link, "state": "known", "statement": statement,
            "evidence": evidence, **extra}


def _unknown(link: str, statement: str, needs: str,
        command: str | None = None, **extra: Any) -> dict[str, Any]:
    record = {"link": link, "state": "unknown", "statement": statement,
              "needs": needs, **extra}
    if command:
        record["command"] = command
    return record


def _candidate_grammar(family: str) -> dict[str, Any]:
    """Bound the rules worth testing by the family, and call them candidates."""
    if family in {"position-movement", "order-acquisition"}:
        return {
            "bounded_by": "the movement and order transition vocabulary",
            "candidates": [
                "the engines cached different routes",
                "the route agrees but a step fires on another cycle",
                "arrival or re-aim changes the next action",
                "occupancy or terrain rejects a step on one side only",
            ],
            "predicates": ["step-cycle", "route-plan", "arrival-transition",
                           "occupancy-free-set"],
        }
    if family == "hit-points":
        return {
            "bounded_by": "the hit-point movement vocabulary",
            "candidates": [
                "rounding differs on one blow",
                "combat and build events are ordered differently",
                "a per-tick rate differs",
                "the damage roll itself differs",
            ],
            "predicates": ["change-cycles", "change-count", "delta-size",
                           "event-order"],
        }
    if family in {"synchronized-rng", "asynchronous-rng"}:
        return {
            "bounded_by": "the draw vocabulary",
            "candidates": ["a draw is missing", "a draw is extra",
                           "the draws are reordered",
                           "several wrong draws partly cancel"],
            "predicates": ["draw-count", "draw-order", "seed-transition"],
        }
    return {
        "bounded_by": "not established for this family",
        "candidates": [],
        "predicates": [],
    }


def build_blocker_chain(blocker: dict[str, Any], route: dict[str, Any],
        capsule: dict[str, Any],
        context: dict[str, Any] | None = None) -> dict[str, Any]:
    """Build the strongest chain this tree's evidence actually supports."""
    case = blocker["case"]
    cycle = blocker.get("cycle")
    findings = blocker.get("findings", [])
    first = findings[0] if findings else {}
    family = route.get("family", "unclassified")
    canonical = _canonical_field(first)
    context = context or {}
    java_trace = context.get("java_trace") or JAVA_TRACE_PLACEHOLDER
    chain: list[dict[str, Any]] = []

    if first:
        chain.append(_known(
            "first-wrong-field",
            f"unit {first.get('unit')} ({first.get('unit_type')}) "
            f"{first.get('field')} is {first.get('oracle')} natively and "
            f"{first.get('java')} here, at cycle {cycle}",
            "the accepted survey's first divergent frame for this case",
            field=first.get("field"), unit=first.get("unit"),
            unit_type=first.get("unit_type"), cycle=cycle,
            oracle=first.get("oracle"), java=first.get("java"),
        ))
    else:
        chain.append(_unknown(
            "first-wrong-field",
            "no structured finding was recorded for this case",
            "a survey record with parsed findings",
            f"{HARNESS} survey --case {case} --through 80",
        ))

    native_lane = next((step for step in route.get("steps", [])
                        if step["lane"] == "branch-witness"), None)
    if native_lane and native_lane.get("native_trace"):
        chain.append(_unknown(
            "native-last-writer",
            "a native trace is available but has not been witnessed yet",
            "a Branch Witness run over the authenticated native trace",
            native_lane.get("command"),
        ))
    else:
        chain.append(_unknown(
            "native-last-writer",
            f"which native instruction last wrote {first.get('field', 'the field')} "
            f"for unit {first.get('unit')} is not established",
            "an authenticated native capture around the divergent cycle",
            _route_command(route, "branch-witness")
            or f"{HARNESS} doctor --need capture",
        ))
    chain.append(_unknown(
        "native-predicate",
        "the minimal native predicate or state transition behind that write is "
        "not established",
        "a Decision Miner or micro-oracle run over the same capture",
        _route_command(route, "decision-miner")
        or f"{HARNESS} doctor --need capture",
    ))

    families = _atlas_families()
    match = next((item for item in families
                  if item.get("canonical") == canonical), None)
    if match is not None:
        chain.append(_known(
            "semantic-correspondence",
            f"{canonical} is the same field on both sides",
            match.get("evidence", "the semantic bridge atlas"),
            native=match.get("native"), java=match.get("java"),
        ))
    else:
        chain.append(_unknown(
            "semantic-correspondence",
            f"no authenticated symbol family names {canonical or 'this field'} "
            "on both sides",
            "a semantic bridge entry proved independently, not assumed",
            f"{HARNESS} semantic-bridge SEMANTIC_SLICE.json "
            f"--java-trace {shlex.quote(str(java_trace))}",
            requires_input=["SEMANTIC_SLICE.json"] + (
                [] if context.get("java_trace") else [JAVA_TRACE_PLACEHOLDER]),
        ))

    chain.append(_unknown(
        "java-producer",
        f"which Java statement produced {first.get('field', 'the value')} at "
        f"cycle {cycle} is not localized",
        "a causal trace of this case around the divergent cycle",
        _route_command(route, "causal") or _route_command(route, "cadence"),
    ))

    proven_through = cycle - 1 if isinstance(cycle, int) else None
    chain.append(_known(
        "regression-boundary",
        f"{case} is proven clean through cycle {proven_through} and wrong at "
        f"{cycle}; a fix must keep every cycle up to {proven_through} and make "
        f"{cycle} agree",
        "the accepted gate receipt's per-case frontier",
        clean_through=proven_through, first_wrong=cycle,
    ))

    grammar = _candidate_grammar(family)
    chain.append(_known(
        "candidate-rule-grammar",
        f"candidate rules are bounded by {grammar['bounded_by']}",
        "the family of the first finding; these are candidates, not findings",
        **grammar,
    ))
    chain.append(_known(
        "tournament-plan",
        "candidates are ranked in an isolated counterfactual tournament; "
        "nothing in this pipeline edits engine source",
        "the counterfactual planner",
        command=_route_command(route, "counterfactual"),
    ))

    known = [item for item in chain if item["state"] == "known"]
    return {
        "schema": WHY_CHAIN_SCHEMA,
        "case": case,
        "cycle": cycle,
        "family": family,
        "chain": chain,
        "known_links": [item["link"] for item in known],
        "unknown_links": [item["link"] for item in chain
                          if item["state"] == "unknown"],
        "reaches": known[-1]["link"] if known else None,
        "replayable_source": bool(capsule.get("replayable")),
    }


def build_why_chain(blockers: list[dict[str, Any]], routing: dict[str, Any],
        capsule: dict[str, Any],
        contexts: dict[str, dict[str, Any]] | None = None) -> dict[str, Any]:
    routes = {route["case"]: route for route in routing.get("routes", [])}
    contexts = contexts or {}
    chains = [
        build_blocker_chain(blocker, routes.get(blocker["case"], {}), capsule,
                            contexts.get(blocker["case"]))
        for blocker in blockers
    ]
    return {
        "schema": WHY_CHAIN_SCHEMA,
        "links": list(LINKS),
        "chains": chains,
        "no_unsupported_native_facts": True,
    }


def format_why_chain(why: dict[str, Any]) -> str:
    lines = [
        "# Why chain",
        "",
        "Each link is either supported by evidence in this tree or explicitly "
        "unknown. An unknown link carries the command that would establish it. "
        "No native fact is asserted here that a capture has not proved.",
    ]
    for chain in why["chains"]:
        lines.extend([
            "", f"## {chain['case']} @{chain['cycle']} -- {chain['family']}", "",
            "| Link | State | Statement |", "|---|---|---|",
        ])
        for item in chain["chain"]:
            lines.append(
                f"| `{item['link']}` | {item['state']} | {item['statement']} |")
        commands = [item["command"] for item in chain["chain"]
                    if item.get("command")]
        if commands:
            lines.extend(["", "```sh", *dict.fromkeys(commands), "```"])
    return "\n".join(lines) + "\n"
