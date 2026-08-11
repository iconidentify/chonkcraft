#!/usr/bin/env python3
"""Decide which diagnostic a blocker goes to, from the finding it actually has."""

from __future__ import annotations

import shlex
from typing import Any


ROUTER_SCHEMA = 1

#: The lanes the dashboard reports. Order is the order work is attempted.
LANES = (
    "packet", "cadence", "state-machine", "rng", "branch-witness",
    "decision-miner", "micro-oracle", "counterfactual",
)

QUEUED = "queued"
RUNNING = "running"
COMPLETE = "complete"
BLOCKED = "blocked"

HARNESS = "python3 tools/bne-harness/scripts/bne_java.py"


def _finding_family(findings: list[dict[str, Any]]) -> tuple[str, dict[str, Any]]:
    """Name the family from the first finding, the way acceptance orders them."""
    first = findings[0] if findings else {}
    kind, field = first.get("kind"), first.get("field")
    if kind == "unit" and field in {"x", "y"}:
        return "position-movement", first
    if kind == "unit" and field == "order":
        return "order-acquisition", first
    if kind == "unit" and field == "hp":
        return "hit-points", first
    if kind == "sync_rng":
        return "synchronized-rng", first
    if kind == "async_rng":
        return "asynchronous-rng", first
    if kind == "player_bank":
        return "player-bank", first
    return "unclassified", first


#: Substitutions an operator must make before a planned command will run.
#: They are upper-case so they are obvious, and they are chosen to parse, so a
#: command is only ever wrong in the values it names -- never in its shape.
PLACEHOLDERS = {
    "triage_run": "TRIAGE_RUN_DIRECTORY",
    "capture": "NATIVE_CAPTURE_DIRECTORY",
    "native_trace": "NATIVE_TRACE.txt",
    "causal": "JAVA_CAUSAL.jsonl",
    "snapshot": "MICRO_ORACLE_SNAPSHOT.json",
    "witness_plan": "BRANCH_WITNESS_PLAN.json",
    "decision_plan": "DECISION_CAPTURE_PLAN.json",
    "fixture": "CASE.bnefx",
    "java_trace": "CASE.java.trace.txt",
    "packet": "PACKET.json",
    "index": "CORPUS_INDEX.json",
}


def _quote(value: object) -> str:
    return shlex.quote(str(value))


def _step(lane: str, state: str, why: str, command: str | None = None,
        *, requires_input: list[str] | None = None, **extra: Any) \
        -> dict[str, Any]:
    """Record one lane, and never hand out a command that will not run.

    Every command here is checked against the real argument parser by
    `test_bne_receipt_end_to_end`. Generated instructions used to be written
    from memory of the interface rather than from the interface -- `survey
    --index PATH` when the index is positional, `state-machine --case X` when
    it wants `--packet` and `--slot` -- so the pipeline printed recovery steps
    that could not be run, which reads as though they had been checked.
    """
    step: dict[str, Any] = {"lane": lane, "state": state, "reason": why}
    if command is not None:
        step["command"] = command
        if requires_input:
            step["requires_input"] = requires_input
    step.update(extra)
    return step


def _native_capture_plan(case: str, cycle: int, lane: str, why: str,
        capabilities: dict[str, Any],
        context: dict[str, Any] | None = None) -> dict[str, Any]:
    """Plan work that needs the native side, without running a remote capture."""
    context = context or {}
    trace = (capabilities.get("native_traces") or {}).get(case)
    unit = context.get("native_unit")
    fixture = context.get("fixture") or PLACEHOLDERS["fixture"]
    packet = context.get("packet") or PLACEHOLDERS["packet"]
    substitutions: list[str] = []
    if context.get("fixture") is None:
        substitutions.append(PLACEHOLDERS["fixture"])
    if context.get("packet") is None:
        substitutions.append(PLACEHOLDERS["packet"])

    if lane == "rng":
        command = (f"{HARNESS} rng-ledger "
                   f"--java-causal {PLACEHOLDERS['causal']} "
                   f"--native-trace {trace or PLACEHOLDERS['native_trace']} "
                   f"--case {_quote(case)}")
        needed = [PLACEHOLDERS["causal"]] + (
            [] if trace else [PLACEHOLDERS["native_trace"]])
    elif lane == "branch-witness":
        command = (f"{HARNESS} branch-witness {PLACEHOLDERS['triage_run']} "
                   f"--case {_quote(case)} "
                   f"--capture {trace or PLACEHOLDERS['capture']}")
        needed = [PLACEHOLDERS["triage_run"]] + (
            [] if trace else [PLACEHOLDERS["capture"]])
    elif lane == "decision-miner":
        command = (f"{HARNESS} decision-plan {PLACEHOLDERS['witness_plan']} "
                   f"--fixture {_quote(fixture)} "
                   f"--native-unit {int(unit) if unit is not None else 0} "
                   "--field order "
                   f"--rejected-cycle {int(cycle) - 1} "
                   f"--accepted-cycle {int(cycle)}")
        needed = [PLACEHOLDERS["witness_plan"], *substitutions[:1]]
    elif lane == "micro-oracle":
        command = (f"{HARNESS} micro-oracle-plan {_quote(packet)} "
                   f"--case {_quote(case)}")
        needed = substitutions[1:]
    else:
        command = f"{HARNESS} doctor --need capture"
        needed = []

    if trace and lane in {"rng", "branch-witness"}:
        return _step(lane, QUEUED, why, command,
                     requires_input=needed or None,
                     requires="authenticated native trace", native_trace=trace)

    # Before calling this blocked, say which kind of blocked it is. The catalog
    # verdict for this case was compiled upstream and passed in; without it,
    # "no authenticated native trace" covers a case nobody ever captured and a
    # case captured twenty cycles short, and those need different work.
    verdict = ((capabilities.get("evidence") or {}).get(case) or {})
    state = verdict.get("verdict")
    detail = verdict.get("summary")
    if state and state != "missing":
        reason = (f"{why}; native evidence for {case} exists but is not usable: "
                  f"{detail}")
    elif state == "missing":
        reason = (f"{why}; no native capture of {case} exists in any configured "
                  "evidence root, so there is nothing local to authenticate")
    else:
        reason = (f"{why}; no authenticated native trace for {case} is "
                  "available locally")

    plan_command = verdict.get("capture_plan_command") or (
        f"{HARNESS} capture-plan --case {_quote(case)} "
        f"--profile {_capture_profile(lane)} --through {int(cycle)} "
        f"--index {PLACEHOLDERS['index']}")
    return _step(
        lane, BLOCKED, reason,
        command, requires_input=needed or None,
        requires="authenticated native trace",
        recovery=plan_command,
        recovery_note=(
            "that compiles the exact capture recipe for this case, with the "
            "scenario and seed read from the corpus index rather than "
            "remembered"
        ),
        evidence_verdict=state,
    )


def _capture_profile(lane: str) -> str:
    """Name the capture profile a blocked lane would need."""
    return {
        "rng": "async-rng",
        "branch-witness": "branch-witness",
        "decision-miner": "decision-capture",
        "micro-oracle": "decision-capture",
    }.get(lane, "async-rng")


def route_blocker(blocker: dict[str, Any], *,
        packet_path: str | None = None,
        hp_evidence: dict[str, Any] | None = None,
        capabilities: dict[str, Any] | None = None,
        context: dict[str, Any] | None = None) -> dict[str, Any]:
    """Turn one tied blocker into an ordered, stated diagnostic plan.

    Routing reads the finding, never the case name. A position mismatch goes to
    the cadence profiler because the transitions are already in the paired
    traces; hit points go to the damage-shape classifier first, and only reach
    the asynchronous draw ledger when the shape the ledger documents -- falling
    on both sides, on the same cycles, the same number of times, by different
    amounts -- is what the packet actually shows. Escalating on the word "hp"
    alone produces a ledger run that reports the streams agreeing and says
    nothing about the mismatch.
    """
    capabilities = capabilities or {}
    context = context or {}
    case = str(blocker.get("case"))
    cycle = blocker.get("first_divergence_cycle", blocker.get("cycle"))
    findings = [item for item in blocker.get("findings", [])
                if isinstance(item, dict)]
    family, first = _finding_family(findings)
    fields = {(item.get("kind"), item.get("field")) for item in findings}
    reasons: list[str] = []
    steps: list[dict[str, Any]] = []

    packet_state = COMPLETE if packet_path else (
        QUEUED if blocker.get("state") == "retained" else BLOCKED)
    packet_reason = (
        "the forensic frame is built from the retained blocker evidence"
        if packet_state != BLOCKED else
        "the retained blocker evidence is incomplete, so no frame can be built"
    )
    steps.append(_step(
        "packet", packet_state, packet_reason,
        None if packet_path else f"{HARNESS} frontier-compile",
        artifact=packet_path,
    ))

    if family in {"position-movement", "order-acquisition"}:
        reasons.append(
            "the first finding is a unit position or order, and both engines' "
            "transitions are already in the paired traces"
        )
        fixture = context.get("fixture") or PLACEHOLDERS["fixture"]
        java_trace = context.get("java_trace") or PLACEHOLDERS["java_trace"]
        slot = context.get("native_unit")
        if slot is None:
            slot = first.get("unit")
        steps.append(_step(
            "cadence", QUEUED,
            "transition cycles, gaps and phase offsets are readable without "
            "any new capture",
            f"{HARNESS} cadence {_quote(fixture)} "
            f"--java-trace {_quote(java_trace)} "
            f"--native-unit {int(slot) if slot is not None else 0}",
            requires_input=[name for name, value in (
                (PLACEHOLDERS["fixture"], context.get("fixture")),
                (PLACEHOLDERS["java_trace"], context.get("java_trace")),
            ) if value is None] or None,
        ))
        steps.append(_step(
            "state-machine", QUEUED,
            "a phase offset is a temporal claim, so the state machine decides "
            "which transition moved rather than which cycle differs",
            f"{HARNESS} state-machine "
            f"--packet {_quote(context.get('packet') or PLACEHOLDERS['packet'])} "
            f"--slot {int(slot) if slot is not None else 0}",
            requires_input=(None if context.get("packet")
                            else [PLACEHOLDERS["packet"]]),
        ))
        if ("unit", "order") in fields:
            reasons.append(
                "an order field also differs, which is a native write this "
                "side cannot see without capture"
            )
            steps.append(_native_capture_plan(
                case, cycle, "branch-witness",
                "an order the native engine wrote and this one did not is a "
                "field-write question",
                capabilities, context,
            ))
    elif family == "hit-points":
        reasons.append(
            "the first finding is a unit's hit points, which move for several "
            "unrelated reasons, so the damage shape decides the next step"
        )
        shape = hp_evidence or {}
        applicable = bool(shape.get("applicable"))
        suspected = bool(shape.get("randomized_damage_suspected"))
        steps.append(_step(
            "damage-shape", COMPLETE if applicable else BLOCKED,
            ("the packet reports direction, change cycles and change counts "
             "on both sides"
             if applicable else
             "no forensic frame yet, so the hit-point shape is unknown"),
            None if applicable else f"{HARNESS} frontier-compile",
            direction=shape.get("direction"),
            cadence_agrees=shape.get("cadence_agrees"),
            change_count_agrees=shape.get("change_count_agrees"),
            values_differ=shape.get("values_differ"),
            randomized_damage_suspected=suspected,
        ))
        if suspected:
            reasons.append(
                "hit points fall on both sides, on the same cycles, the same "
                "number of times, by different amounts -- the one shape the "
                "asynchronous draw ledger documents"
            )
            steps.append(_native_capture_plan(
                case, cycle, "rng",
                "the asynchronous ledger's documented precondition is met",
                capabilities, context,
            ))
        else:
            reasons.append(
                "the documented asynchronous-ledger precondition is not met, "
                "so a ledger run would report the streams agreeing and say "
                "nothing about this mismatch"
            )
            steps.append(_step(
                "rng", BLOCKED,
                "escalation withheld: "
                + (f"direction {shape.get('direction')}, "
                   f"same change cycles {shape.get('cadence_agrees')}, "
                   f"same change count {shape.get('change_count_agrees')}"
                   if applicable else "no damage shape measured yet"),
                None,
                withheld=True,
            ))
            steps.append(_step(
                "causal", QUEUED,
                "combat and build events are ordered evidence, so the causal "
                "trace decides which event moved the hit points first",
                f"{HARNESS} lab {PLACEHOLDERS['triage_run']}",
                requires_input=[PLACEHOLDERS["triage_run"]],
            ))
    elif family in {"synchronized-rng", "asynchronous-rng"}:
        generator = ("synchronized" if family == "synchronized-rng"
                     else "asynchronous")
        reasons.append(f"the first finding is a {generator} seed mismatch")
        steps.append(_native_capture_plan(
            case, cycle, "rng",
            f"a {generator} seed mismatch is a draw-ledger question by "
            "definition",
            capabilities,
        ))
    else:
        reasons.append(
            f"the first finding is {family}, which has no cheap dedicated "
            "transition probe"
        )
        steps.append(_step(
            "causal", QUEUED,
            "the causal trace is the general-purpose next step",
            f"{HARNESS} lab {PLACEHOLDERS['triage_run']}",
            requires_input=[PLACEHOLDERS["triage_run"]],
        ))

    # Native-side lanes below are always plans. Nothing here claims a native
    # fact; each states the evidence it would need and the command that would
    # produce it.
    steps.append(_native_capture_plan(
        case, cycle, "decision-miner",
        "a native decision that is rejected or writes nothing leaves no field "
        "to compare, so it is mined rather than witnessed",
        capabilities,
    ))
    steps.append(_native_capture_plan(
        case, cycle, "micro-oracle",
        "a bounded native leaf decision can be replayed offline once its "
        "snapshot exists",
        capabilities,
    ))
    steps.append(_step(
        "counterfactual", QUEUED,
        "candidate rules are ranked in an isolated tournament; nothing here "
        "edits engine source",
        f"{HARNESS} counterfactual {PLACEHOLDERS['triage_run']} "
        f"--case {_quote(case)}",
        requires_input=[PLACEHOLDERS["triage_run"]],
        never_modifies_source=True,
    ))

    return {
        "schema": ROUTER_SCHEMA,
        "case": case,
        "cycle": cycle,
        "family": family,
        "first_finding": first,
        "finding_count": len(findings),
        "reasons": reasons,
        "steps": steps,
        "next_lane": next((step["lane"] for step in steps
                           if step["state"] == QUEUED), None),
        "blocked_lanes": [step["lane"] for step in steps
                          if step["state"] == BLOCKED],
        "estimated_cost": blocker.get("estimated_cost"),
    }


def route_all(blockers: list[dict[str, Any]], *,
        packets: dict[str, str] | None = None,
        hp_evidence: dict[str, dict[str, Any]] | None = None,
        capabilities: dict[str, Any] | None = None,
        contexts: dict[str, dict[str, Any]] | None = None) -> dict[str, Any]:
    """Route every tied blocker, cheapest first, without reordering acceptance."""
    packets = packets or {}
    hp_evidence = hp_evidence or {}
    contexts = contexts or {}
    ordered = sorted(
        blockers,
        key=lambda item: (item.get("rank") if isinstance(item.get("rank"), int)
                          else 1_000, str(item.get("case"))),
    )
    routes = [
        route_blocker(
            blocker,
            packet_path=packets.get(str(blocker.get("case"))),
            hp_evidence=hp_evidence.get(str(blocker.get("case"))),
            capabilities=capabilities,
            context=contexts.get(str(blocker.get("case"))),
        )
        for blocker in ordered
    ]
    lanes: dict[str, str] = {}
    for lane in LANES:
        states = [step["state"] for route in routes for step in route["steps"]
                  if step["lane"] == lane]
        if not states:
            lanes[lane] = "absent"
        elif COMPLETE in states:
            lanes[lane] = COMPLETE
        elif QUEUED in states:
            lanes[lane] = QUEUED
        elif RUNNING in states:
            lanes[lane] = RUNNING
        else:
            lanes[lane] = BLOCKED
    return {
        "schema": ROUTER_SCHEMA,
        "routes": routes,
        "lanes": lanes,
        # Diagnostics may run side by side; acceptance still requires every
        # equally early blocker before the common frontier moves.
        "parallel_diagnostics_permitted": True,
        "acceptance_order_unchanged": True,
        "order": [route["case"] for route in routes],
    }


def format_routes(routing: dict[str, Any]) -> str:
    lines = []
    for route in routing["routes"]:
        lines.append(f"## {route['case']} @{route['cycle']} -- {route['family']}")
        lines.append("")
        for reason in route["reasons"]:
            lines.append(f"- {reason}")
        lines.append("")
        lines.append("| Lane | State | Why |")
        lines.append("|---|---|---|")
        for step in route["steps"]:
            lines.append(
                f"| `{step['lane']}` | {step['state']} | {step['reason']} |")
        commands = list(dict.fromkeys(step["command"] for step in route["steps"]
                                      if step.get("command")))
        if commands:
            lines.append("")
            lines.append("```sh")
            lines.extend(commands)
            lines.append("```")
        lines.append("")
    return "\n".join(lines)
