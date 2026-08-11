#!/usr/bin/env python3
"""Information-gain planning and constrained counterexample-guided synthesis."""

from __future__ import annotations

from dataclasses import dataclass
import hashlib
import itertools
import json
import math
from typing import Any, Callable, Iterable


EXPERIMENT_SCHEMA = 1


def _entropy(probabilities: Iterable[float]) -> float:
    return -sum(value * math.log2(value) for value in probabilities if value > 0)


def rank_experiments(hypotheses: list[dict[str, Any]],
        experiments: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Rank experiments by expected information gain per unit cost."""
    if not hypotheses:
        return []
    total = sum(float(item.get("prior", 1.0)) for item in hypotheses)
    if total <= 0:
        raise ValueError("hypothesis priors must have positive mass")
    priors = {item["id"]: float(item.get("prior", 1.0)) / total
              for item in hypotheses}
    prior_entropy = _entropy(priors.values())
    ranked = []
    for experiment in experiments:
        experiment_id = experiment["id"]
        outcomes: dict[str, list[tuple[str, float]]] = {}
        unknown = False
        for hypothesis in hypotheses:
            prediction = hypothesis.get("predictions", {}).get(experiment_id)
            if prediction is None:
                unknown = True
                prediction = "unknown"
            outcomes.setdefault(json.dumps(prediction, sort_keys=True), []).append(
                (hypothesis["id"], priors[hypothesis["id"]])
            )
        expected = 0.0
        distribution = {}
        for outcome, members in outcomes.items():
            mass = sum(value for _, value in members)
            posterior = [value / mass for _, value in members]
            expected += mass * _entropy(posterior)
            distribution[outcome] = round(mass, 8)
        gain = prior_entropy - expected
        cost = float(experiment.get("cost", 1.0))
        if cost <= 0:
            raise ValueError(f"experiment cost must be positive: {experiment_id}")
        ranked.append({
            **experiment,
            "prior_entropy": round(prior_entropy, 8),
            "expected_entropy": round(expected, 8),
            "information_gain": round(gain, 8),
            "score": round(gain / cost, 8),
            "outcome_distribution": distribution,
            "has_unknown_predictions": unknown,
        })
    return sorted(ranked, key=lambda item: (-item["score"], item["id"]))


def _blocker_profile(case: dict[str, Any],
        cadence: dict[str, Any] | None = None) -> dict[str, Any]:
    """Score investigation cost, never likelihood or gameplay importance."""
    findings = [item for item in case.get("findings", [])
                if isinstance(item, dict)]
    first = findings[0] if findings else {}
    kind, field = first.get("kind"), first.get("field")
    unit_type = str(first.get("unit_type", ""))
    score = 1.0
    reasons = []
    next_tool = "lab"
    if kind == "unit" and field in {"x", "y"}:
        score += 4.0
        reasons.append("position transitions are observable in existing paired traces")
        next_tool = "cadence"
        if any(token in unit_type.lower() for token in
               ("zeppelin", "dragon", "gryphon", "flyer")):
            score += 0.75
            reasons.append("air movement avoids most ground-occupancy ambiguity")
    elif kind == "sync_rng":
        score += 2.5
        reasons.append("the synchronized RNG caller ledger is already instrumented")
        next_tool = "lab"
    elif kind == "unit" and field == "order":
        score += 1.5
        reasons.append("order transitions are visible but a missing native write may require capture")
        next_tool = "branch-witness"
    elif kind == "unit" and field == "hp":
        score += 1.25
        reasons.append("HP is observable but may depend on external combat/build event order")
        next_tool = "lab"
    elif kind == "player_bank":
        score += 1.0
        reasons.append("bank changes are visible but often downstream of another unit")
        next_tool = "lab"
    else:
        reasons.append("the first finding lacks a cheap dedicated transition probe")
    if len(findings) == 1:
        score += 0.5
        reasons.append("the first divergent frame contains one focused finding")
    if cadence is not None:
        classification = cadence.get("phase", {}).get("classification")
        if classification in {"one-time-delay", "steady-phase-offset", "aligned"}:
            score += 3.0
            reasons.append(f"paired cadence already classifies the phase as {classification}")
            next_tool = "semantic-bridge" if classification != "aligned" else "gate"
    return {
        "case": case.get("id"),
        "cycle": case.get("first_divergence_cycle"),
        "score": round(score, 3),
        "tractability": "high" if score >= 5 else "medium" if score >= 2.5 else "low",
        "recommended_tool": next_tool,
        "reasons": reasons,
        "heuristic": True,
        "does_not_change_acceptance_priority": True,
    }


def rank_tied_blockers(cases: list[dict[str, Any]],
        cadence_by_case: dict[str, dict[str, Any]] | None = None) \
        -> list[dict[str, Any]]:
    """Rank only the earliest tied blockers by expected diagnostic cost."""
    open_cases = [case for case in cases
                  if isinstance(case.get("first_divergence_cycle"), int)]
    if not open_cases:
        return []
    earliest = min(case["first_divergence_cycle"] for case in open_cases)
    tied = [case for case in open_cases
            if case["first_divergence_cycle"] == earliest]
    cadence_by_case = cadence_by_case or {}
    ranked = sorted(
        (_blocker_profile(case, cadence_by_case.get(str(case.get("id"))))
         for case in tied),
        key=lambda item: (-item["score"], str(item["case"])),
    )
    for rank, item in enumerate(ranked, 1):
        item["rank"] = rank
    return ranked


def hp_evidence(packet: dict[str, Any]) -> dict[str, Any]:
    """What the divergent unit's hit points did on each side of the window.

    Hit points move for several unrelated reasons, and the reason decides the
    experiment. A building coming up, a healer, and a blow struck all show as
    the same one-field mismatch, so the planner used to offer rounding, event
    order and rate for every one of them -- and never reached the randomized
    damage that a divergence with matching hit cycles and matching hit counts
    almost has to be.

    Nothing here decides anything on its own. It reports which way the hit
    points moved, on which cycles each engine moved them, and whether those
    cycles agree, and leaves the planner to weigh that against the rest.
    """
    divergence = packet.get("divergence", {})
    findings = divergence.get("findings", [])
    first = findings[0] if findings else {}
    if first.get("kind") != "unit" or first.get("field") != "hp":
        return {"applicable": False}
    slot = first.get("unit")
    series: dict[str, list[tuple[int, int]]] = {"native": [], "java": []}
    java_id = None
    for cycle_text in sorted(packet.get("semantic", {}), key=int):
        cycle = int(cycle_text)
        for focus in packet["semantic"][cycle_text].get("focus", []):
            if focus.get("native_slot") != slot:
                continue
            java_id = focus.get("java_id", java_id)
            for side, key in (("native", "oracle"), ("java", "java")):
                observed = focus.get(key)
                if isinstance(observed, dict) \
                        and isinstance(observed.get("hp"), int):
                    series[side].append((cycle, observed["hp"]))

    def transitions(points: list[tuple[int, int]]) -> list[tuple[int, int]]:
        return [(cycle, value - points[index - 1][1])
                for index, (cycle, value) in enumerate(points)
                if index and value != points[index - 1][1]]

    native_changes = transitions(series["native"])
    java_changes = transitions(series["java"])
    deltas = [delta for _, delta in native_changes + java_changes]
    if not deltas:
        direction = "flat"
    elif all(delta < 0 for delta in deltas):
        direction = "falling"
    elif all(delta > 0 for delta in deltas):
        direction = "rising"
    else:
        direction = "mixed"
    native_cycles = [cycle for cycle, _ in native_changes]
    java_cycles = [cycle for cycle, _ in java_changes]
    cadence_agrees = bool(native_cycles) and native_cycles == java_cycles
    values_differ = ([delta for _, delta in native_changes]
                     != [delta for _, delta in java_changes])
    return {
        "applicable": True,
        "native_slot": slot, "java_id": java_id,
        "native_hit_points": series["native"], "java_hit_points": series["java"],
        "native_change_cycles": native_cycles,
        "java_change_cycles": java_cycles,
        "direction": direction,
        "cadence_agrees": cadence_agrees,
        "change_count_agrees": len(native_changes) == len(java_changes),
        "values_differ": values_differ,
        # Falling together, on the same cycles, the same number of times, by
        # different amounts is what a differently rolled damage looks like and
        # what nothing else on the list looks like.
        "randomized_damage_suspected": (
            direction == "falling" and cadence_agrees
            and len(native_changes) == len(java_changes) and values_differ
        ),
    }


def default_investigation_plan(case_id: str, cycle: int,
        findings: list[dict[str, Any]], packet: str | None = None,
        evidence: dict[str, Any] | None = None) -> dict[str, Any]:
    """Produce explicit competing hypotheses from a structured mismatch."""
    first = findings[0] if findings else {}
    kind = first.get("kind", "other")
    field = first.get("field")
    basis = f"{case_id}:{cycle}:{kind}:{field}"
    hypotheses: list[dict[str, Any]]
    experiments: list[dict[str, Any]]
    if kind == "sync_rng":
        hypotheses = [
            {"id": "missing-draw", "description": "Java omitted a synchronized draw",
             "predictions": {"caller-ledger": "native-only", "state-boundary": "before"}},
            {"id": "extra-draw", "description": "Java drew at an extra boundary",
             "predictions": {"caller-ledger": "java-only", "state-boundary": "after"}},
            {"id": "draw-order", "description": "Both draw but in a different order",
             "predictions": {"caller-ledger": "reordered", "state-boundary": "same-count"}},
            {"id": "compensating-draws", "description": "Multiple wrong draws partially cancel",
             "predictions": {"caller-ledger": "multiple", "state-boundary": "unstable"}},
        ]
        experiments = [
            {"id": "caller-ledger", "description": "Align native and Java RNG caller ledgers",
             "cost": 1.0, "capture": ["rng.sync.draw", "order.transition"]},
            {"id": "state-boundary", "description": "Trace seed before and after the focused action",
             "cost": 1.4, "capture": ["rng.sync.seed", "state.observation"]},
        ]
    elif kind == "unit" and field in ("x", "y"):
        hypotheses = [
            {"id": "cached-route", "description": "The engines cached different routes",
             "predictions": {"transition-cadence": "different-values", "path-ledger": "different-plan", "arrival-boundary": "same"}},
            {"id": "step-timing", "description": "The route agrees but a step fires on another cycle",
             "predictions": {"transition-cadence": "phase-shift", "path-ledger": "same-plan", "arrival-boundary": "different-step"}},
            {"id": "arrival-transition", "description": "Arrival or re-aim changes the next action",
             "predictions": {"transition-cadence": "post-transition-shift", "path-ledger": "same-prefix", "arrival-boundary": "different-transition"}},
            {"id": "occupancy", "description": "Different occupancy/terrain interpretation rejects a step",
             "predictions": {"transition-cadence": "missing-transition", "path-ledger": "different-free-set", "arrival-boundary": "blocked"}},
        ]
        experiments = [
            {"id": "transition-cadence", "description": "Compare transition cycles, gaps, and phase offsets",
             "cost": 0.4, "capture": ["state.position"]},
            {"id": "path-ledger", "description": "Align route construction and consumed headings",
             "cost": 1.0, "capture": ["path.route", "movement.step", "path.occupancy"]},
            {"id": "arrival-boundary", "description": "Trace the action before and after route exhaustion",
             "cost": 1.2, "capture": ["order.transition", "resource.wood-search", "rng.sync.draw"]},
        ]
    elif kind == "unit" and field == "hp":
        hypotheses = [
            {"id": "rounding", "description": "The engines round the same HP fraction differently",
             "predictions": {"hp-boundaries": "fraction-pattern", "event-order": "same"}},
            {"id": "event-order", "description": "Construction or damage updates happen in another order",
             "predictions": {"hp-boundaries": "one-cycle", "event-order": "different"}},
            {"id": "rate", "description": "The native per-tick increment differs",
             "predictions": {"hp-boundaries": "linear-delta", "event-order": "same"}},
        ]
        experiments = [
            {"id": "hp-boundaries", "description": "Collect HP transitions around integer boundaries",
             "cost": 1.0, "capture": ["build.hp", "state.observation"]},
            {"id": "event-order", "description": "Align build/damage callbacks in the divergent cycle",
             "cost": 1.2, "capture": ["build.progress", "combat.damage"]},
        ]
        if (evidence or {}).get("randomized_damage_suspected"):
            # Hit points falling on the same cycles the same number of times
            # by different amounts is a differently rolled blow, and none of
            # the three above can be it. Rounding, order and rate stay on the
            # list, because a construction, a heal or a per-tick rate would
            # not have got here.
            hypotheses.insert(0, {
                "id": "randomized-damage",
                "description": "The same blows land for differently rolled damage",
                "predictions": {"async-rng-ledger": "consumer-mismatch",
                                "hp-boundaries": "same-cycles",
                                "event-order": "same"},
            })
            for hypothesis in hypotheses[1:]:
                hypothesis["predictions"]["async-rng-ledger"] = (
                    "consumer-reordered" if hypothesis["id"] == "event-order"
                    else "identical"
                )
            experiments.insert(0, {
                "id": "async-rng-ledger",
                "description": ("Align the native and Java asynchronous RNG "
                                "ledgers on their seed transitions"),
                "cost": 0.6, "capture": ["rng.async.draw"],
                "command": "bne_java.py rng-ledger",
            })
    else:
        hypotheses = [
            {"id": "state-transition", "description": "A semantic transition is missing or reordered",
             "predictions": {"causal-ledger": "event-difference"}},
            {"id": "initial-state", "description": "The action begins from different hidden state",
             "predictions": {"causal-ledger": "state-difference"}},
        ]
        experiments = [
            {"id": "causal-ledger", "description": "Capture the focused event family and state writes",
             "cost": 1.0, "capture": [str(kind)]},
        ]
    signals = (evidence or {}).get("state_machine_signals") or []
    if signals:
        # Something in the native record accumulated, armed, repeated or held
        # still across this window. None of the hypotheses above is about a
        # state that takes several cycles to decide, so they cannot be
        # distinguished from one that does without looking.
        for hypothesis in hypotheses:
            hypothesis.setdefault("predictions", {})["native-state-machine"] = \
                "no-hidden-state"
        hypotheses.append({
            "id": "hidden-state-machine",
            "description": ("A multi-cycle native state decides this before "
                            "anything visible happens"),
            "predictions": {"native-state-machine": "threshold-transition"},
        })
        experiments.append({
            "id": "native-state-machine",
            "description": ("Recover the native unit record's transitions "
                            "across the window"),
            "cost": 0.5, "capture": ["state.unit"],
            "command": "bne_java.py state-machine",
            "signals": [signal["signal"] for signal in signals],
        })
    ranked = rank_experiments(hypotheses, experiments)
    digest = hashlib.sha256(basis.encode("utf-8")).hexdigest()[:12]
    return {
        "schema": EXPERIMENT_SCHEMA,
        "id": "experiment-plan-" + digest,
        "case": case_id,
        "cycle": cycle,
        "finding": first,
        "packet": packet,
        "hypotheses": hypotheses,
        "experiments": ranked,
        "recommended": ranked[0] if ranked else None,
    }


@dataclass(frozen=True)
class Rule:
    feature: str | None
    operator: str | None
    threshold: Any
    when_true: Any
    when_false: Any

    def evaluate(self, inputs: dict[str, Any]) -> Any:
        if self.feature is None:
            return self.when_true
        value = inputs[self.feature]
        operations = {
            "<": lambda: value < self.threshold,
            "<=": lambda: value <= self.threshold,
            "==": lambda: value == self.threshold,
            ">=": lambda: value >= self.threshold,
            ">": lambda: value > self.threshold,
        }
        if self.operator not in operations:
            raise ValueError(f"unsupported rule operator: {self.operator}")
        return self.when_true if operations[self.operator]() else self.when_false

    def as_dict(self) -> dict[str, Any]:
        return {
            "feature": self.feature, "operator": self.operator,
            "threshold": self.threshold, "when_true": self.when_true,
            "when_false": self.when_false,
        }


def enumerate_rules(examples: list[dict[str, Any]], *,
        features: list[str] | None = None,
        outputs: list[Any] | None = None) -> list[Rule]:
    if not examples:
        raise ValueError("rule synthesis requires examples")
    if any("input" not in example or "output" not in example for example in examples):
        raise ValueError("each example requires input and output")
    selected = features or sorted(examples[0]["input"])
    possible_outputs = outputs or sorted(
        {json.dumps(example["output"], sort_keys=True) for example in examples}
    )
    if outputs is None:
        decoded_outputs = [json.loads(value) for value in possible_outputs]
    else:
        decoded_outputs = possible_outputs
    candidates = [Rule(None, None, None, output, output)
                  for output in decoded_outputs]
    for feature in selected:
        values = sorted({example["input"][feature] for example in examples},
                        key=lambda value: (str(type(value)), value))
        for operator, threshold, yes, no in itertools.product(
                ("<", "<=", "==", ">=", ">"), values,
                decoded_outputs, decoded_outputs):
            candidates.append(Rule(feature, operator, threshold, yes, no))
    unique = {json.dumps(rule.as_dict(), sort_keys=True): rule for rule in candidates}
    return [unique[key] for key in sorted(unique)]


def consistent_rules(examples: list[dict[str, Any]], **kwargs: Any) -> list[Rule]:
    return [rule for rule in enumerate_rules(examples, **kwargs)
            if all(rule.evaluate(example["input"]) == example["output"]
                   for example in examples)]


def cegis(initial_examples: list[dict[str, Any]],
        verifier: Callable[[Rule], dict[str, Any] | None], *,
        max_rounds: int = 32, features: list[str] | None = None,
        outputs: list[Any] | None = None) -> dict[str, Any]:
    """Run a bounded CEGIS loop over explainable one-predicate rules.

    ``verifier`` returns a counterexample or ``None`` when the rule is valid.
    The search never emits arbitrary source code; its complete grammar is the
    :class:`Rule` record above.
    """
    examples = list(initial_examples)
    rounds = []
    seen = {json.dumps(item, sort_keys=True) for item in examples}
    for index in range(max_rounds):
        candidates = consistent_rules(examples, features=features, outputs=outputs)
        if not candidates:
            return {
                "schema": EXPERIMENT_SCHEMA, "status": "unsatisfiable",
                "rounds": rounds, "examples": examples,
            }
        candidate = candidates[0]
        counterexample = verifier(candidate)
        rounds.append({
            "round": index + 1, "candidate": candidate.as_dict(),
            "candidate_count": len(candidates),
            "counterexample": counterexample,
        })
        if counterexample is None:
            return {
                "schema": EXPERIMENT_SCHEMA, "status": "synthesized",
                "rule": candidate.as_dict(), "rounds": rounds,
                "examples": examples,
            }
        encoded = json.dumps(counterexample, sort_keys=True)
        if encoded in seen:
            return {
                "schema": EXPERIMENT_SCHEMA, "status": "stalled",
                "rule": candidate.as_dict(), "rounds": rounds,
                "examples": examples,
            }
        seen.add(encoded)
        examples.append(counterexample)
    return {
        "schema": EXPERIMENT_SCHEMA, "status": "round-limit",
        "rounds": rounds, "examples": examples,
    }
