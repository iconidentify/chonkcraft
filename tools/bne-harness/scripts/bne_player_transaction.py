#!/usr/bin/env python3
"""Compile and compare end-to-end BNE player transaction receipts.

A resolved unit order is not a player transaction.  A transaction begins at
the physical input, retains the ordered selection and target interpretation,
then follows every fanned-out wire command through acceptance, first physical
progress, and its terminal result.  Missing layers stay visible as coverage
debt rather than being rounded into an exact per-unit command score.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any


SCHEMA = "chonkcraft-bne-player-transactions-1"


def _canonical_family(value: str | None) -> str:
    return str(value or "unknown").strip().lower().replace("_", "-")


def _digest(value: Any) -> str:
    packed = json.dumps(value, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(packed.encode("utf-8")).hexdigest()


def _load(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"receipt is not an object: {path}")
    return value


def compile_evidence(evidence: dict[str, Any], *, source: str) -> dict[str, Any]:
    """Turn one desktop evidence packet into ordered causal transactions."""
    entries = evidence.get("player_intents")
    outcomes = evidence.get("player_outcomes")
    if not isinstance(entries, list) or not isinstance(outcomes, list):
        raise ValueError("evidence lacks player_intents or player_outcomes")

    by_transaction: dict[int, dict[str, Any]] = {}
    intent_transaction: dict[int, int] = {}
    seen_intents: set[int] = set()
    for entry in entries:
        if not isinstance(entry, dict):
            raise ValueError("player intent is not an object")
        intent_id = int(entry.get("intent_id", 0))
        transaction_id = int(entry.get("transaction_id", intent_id))
        if intent_id <= 0 or transaction_id <= 0:
            raise ValueError("intent and transaction ids must be positive")
        if intent_id in seen_intents:
            raise ValueError(f"duplicate player intent {intent_id}")
        seen_intents.add(intent_id)
        intent_transaction[intent_id] = transaction_id
        transaction = by_transaction.setdefault(transaction_id, {
            "transaction_id": transaction_id,
            "gesture": None,
            "selection_updates": [],
            "commands": [],
            "outcomes": [],
        })
        event = entry.get("event")
        if event == "gesture":
            if transaction["gesture"] is not None:
                raise ValueError(f"duplicate gesture for transaction {transaction_id}")
            gesture = entry.get("gesture")
            if not isinstance(gesture, dict):
                raise ValueError(f"gesture {intent_id} has no physical input")
            transaction["gesture"] = {
                "origin": gesture.get("origin"),
                "detail": gesture.get("detail"),
                "screen_x": gesture.get("screen_x"),
                "screen_y": gesture.get("screen_y"),
                "tile_x": gesture.get("tile_x"),
                "tile_y": gesture.get("tile_y"),
                "modifiers": gesture.get("modifiers"),
                "target_id": gesture.get("target_id"),
                "target_shape": gesture.get("target_shape"),
                "selected_unit_ids": list(entry.get("selected_unit_ids") or ()),
            }
        elif event == "selection":
            transaction["selection_updates"].append(
                list(entry.get("selected_unit_ids") or ()))
        elif event == "order":
            command = entry.get("command")
            if not isinstance(command, dict):
                raise ValueError(f"order {intent_id} has no command")
            transaction["commands"].append({
                "intent_id": intent_id,
                "family": _canonical_family(command.get("kind")),
                "player": command.get("player"),
                "unit_id": command.get("unit_id"),
                "x": command.get("x"),
                "y": command.get("y"),
                "target_id": command.get("target_id"),
                "type_index": command.get("type_index"),
                "queued": bool(command.get("queued")),
                "wire_hex": command.get("wire_hex"),
                "accepted": entry.get("accepted"),
                "selected_unit_ids": list(entry.get("selected_unit_ids") or ()),
            })

    seen_outcomes: set[int] = set()
    for outcome in outcomes:
        if not isinstance(outcome, dict):
            raise ValueError("player outcome is not an object")
        intent_id = int(outcome.get("intent_id", 0))
        transaction_id = int(outcome.get(
            "transaction_id", intent_transaction.get(intent_id, intent_id)))
        if intent_id in seen_outcomes:
            raise ValueError(f"duplicate player outcome {intent_id}")
        seen_outcomes.add(intent_id)
        transaction = by_transaction.get(transaction_id)
        if transaction is None:
            raise ValueError(f"outcome {intent_id} has no transaction")
        transaction["outcomes"].append({
            "intent_id": intent_id,
            "unit_id": outcome.get("unit_id"),
            "command": _canonical_family(outcome.get("command")),
            "accepted": outcome.get("accepted"),
            "submitted_cycle": outcome.get("submitted_cycle"),
            "first_progress_cycle": outcome.get("first_progress_cycle"),
            "terminal_cycle": outcome.get("terminal_cycle"),
            "terminal_reason": outcome.get("terminal_reason"),
            "tile_x": outcome.get("tile_x"),
            "tile_y": outcome.get("tile_y"),
            "offset_x": outcome.get("offset_x"),
            "offset_y": outcome.get("offset_y"),
            "order": outcome.get("order"),
            "target_id": outcome.get("target_id"),
            "hit_points": outcome.get("hit_points"),
            "carried": outcome.get("carried"),
            "alive": outcome.get("alive"),
            "on_map": outcome.get("on_map"),
            "missile_count": outcome.get("missile_count"),
        })

    transactions = []
    for transaction_id in sorted(by_transaction):
        transaction = by_transaction[transaction_id]
        command_intents = {item["intent_id"] for item in transaction["commands"]}
        outcome_intents = {item["intent_id"] for item in transaction["outcomes"]}
        transaction["coverage"] = {
            "physical_gesture": transaction["gesture"] is not None,
            "command_count": len(transaction["commands"]),
            "outcome_count": len(transaction["outcomes"]),
            "group_fanout": len(transaction["commands"]) > 1,
            "queued": any(item["queued"] for item in transaction["commands"]),
            "terminal": command_intents == outcome_intents and all(
                item.get("terminal_reason") is not None
                for item in transaction["outcomes"]),
        }
        transaction["canonical_sha256"] = _digest(canonical_transaction(transaction))
        transactions.append(transaction)

    return {
        "schema": SCHEMA,
        "source": source,
        "map_path": evidence.get("map_path"),
        "campaign": evidence.get("campaign"),
        "mission": evidence.get("mission"),
        "transactions": transactions,
    }


def canonical_transaction(transaction: dict[str, Any]) -> dict[str, Any]:
    """Remove local ids while retaining ordering and player-visible behavior."""
    gesture = transaction.get("gesture")
    commands = []
    for command in transaction.get("commands") or ():
        commands.append({key: command.get(key) for key in (
            "family", "player", "unit_id", "x", "y", "target_id",
            "type_index", "queued", "accepted", "selected_unit_ids",
        )})
    outcomes = []
    for outcome in transaction.get("outcomes") or ():
        outcomes.append({key: outcome.get(key) for key in (
            "unit_id", "command", "accepted", "first_progress_cycle",
            "terminal_cycle", "terminal_reason", "tile_x", "tile_y",
            "offset_x", "offset_y", "order", "target_id", "hit_points",
            "carried", "alive", "on_map", "missile_count",
        )})
    return {
        "gesture": gesture,
        "selection_updates": transaction.get("selection_updates") or [],
        "commands": commands,
        "outcomes": outcomes,
    }


def first_difference(left: dict[str, Any], right: dict[str, Any]) \
        -> dict[str, Any] | None:
    left_rows = left.get("transactions") or []
    right_rows = right.get("transactions") or []
    for index in range(max(len(left_rows), len(right_rows))):
        if index >= len(left_rows) or index >= len(right_rows):
            return {"transaction": index, "field": "transaction-count",
                    "left": len(left_rows), "right": len(right_rows)}
        a = canonical_transaction(left_rows[index])
        b = canonical_transaction(right_rows[index])
        if a != b:
            for field in ("gesture", "selection_updates", "commands", "outcomes"):
                if a[field] != b[field]:
                    return {"transaction": index, "field": field,
                            "left": a[field], "right": b[field]}
    return None


def coverage(receipts: list[dict[str, Any]], requirements: dict[str, Any]) \
        -> dict[str, Any]:
    transactions = [transaction for receipt in receipts
                    for transaction in receipt.get("transactions") or ()]
    gestures = [item["gesture"] for item in transactions if item.get("gesture")]
    commands = [command for item in transactions for command in item.get("commands") or ()]
    outcomes = [outcome for item in transactions for outcome in item.get("outcomes") or ()]
    observed = {
        "origins": sorted({item.get("origin") for item in gestures if item.get("origin")}),
        "modifiers": sorted({item.get("modifiers") for item in gestures
                             if item.get("modifiers")}),
        "selection_sizes": sorted({len(item.get("selected_unit_ids") or ())
                                   for item in gestures}),
        "target_shapes": sorted({item.get("target_shape") for item in gestures
                                 if item.get("target_shape")}),
        "families": sorted({item.get("family") for item in commands
                            if item.get("family")}),
    }
    missing: dict[str, list[Any]] = {}
    for dimension in ("origins", "modifiers", "selection_sizes",
                      "target_shapes", "families"):
        wanted = requirements.get(dimension) or []
        missing[dimension] = [value for value in wanted
                              if value not in observed[dimension]]
    queueable = set(requirements.get("queueable_families") or ())
    queued = {item.get("family") for item in commands if item.get("queued")}
    missing["queued_families"] = sorted(queueable - queued)
    accepted = {item.get("family") for item in commands
                if item.get("accepted") is not False}
    rejected = {item.get("family") for item in commands
                if item.get("accepted") is False}
    rejection_required = set(requirements.get("rejection_families") or ())
    missing["accepted_families"] = sorted(set(requirements.get("families") or ())
                                          - accepted)
    missing["rejected_families"] = sorted(rejection_required - rejected)
    incomplete = [item["transaction_id"] for item in transactions
                  if item.get("commands") and not item["coverage"]["terminal"]]
    gestureless = [item["transaction_id"] for item in transactions
                   if item.get("commands") and not item["coverage"]["physical_gesture"]]
    group_count = sum(1 for item in transactions if item["coverage"]["group_fanout"])
    complete = (not any(missing.values()) and not incomplete and not gestureless
                and group_count >= int(requirements.get("minimum_group_transactions", 0))
                and len(transactions) >= int(requirements.get("minimum_transactions", 0)))
    return {
        "schema": "chonkcraft-bne-player-transaction-coverage-1",
        "complete": complete,
        "transactions": len(transactions),
        "gestures": len(gestures),
        "commands": len(commands),
        "outcomes": len(outcomes),
        "group_transactions": group_count,
        "queued_commands": sum(1 for item in commands if item.get("queued")),
        "gestureless_transactions": gestureless,
        "incomplete_transactions": incomplete,
        "observed": observed,
        "missing": missing,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Compile and certify BNE player transaction receipts")
    sub = parser.add_subparsers(dest="command", required=True)
    compile_parser = sub.add_parser("compile-evidence")
    compile_parser.add_argument("evidence", type=Path)
    compile_parser.add_argument("--output", required=True, type=Path)
    compare_parser = sub.add_parser("compare")
    compare_parser.add_argument("left", type=Path)
    compare_parser.add_argument("right", type=Path)
    coverage_parser = sub.add_parser("coverage")
    coverage_parser.add_argument("receipts", nargs="+", type=Path)
    coverage_parser.add_argument("--requirements", required=True, type=Path)
    coverage_parser.add_argument("--output", type=Path)
    coverage_parser.add_argument("--require-complete", action="store_true")
    args = parser.parse_args(argv)

    if args.command == "compile-evidence":
        compiled = compile_evidence(_load(args.evidence), source=str(args.evidence))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(compiled, indent=2, sort_keys=True) + "\n",
                               encoding="utf-8")
        print(json.dumps({"transactions": len(compiled["transactions"]),
                          "output": str(args.output)}, indent=2))
        return 0
    if args.command == "compare":
        difference = first_difference(_load(args.left), _load(args.right))
        print(json.dumps({"identical": difference is None,
                          "difference": difference}, indent=2, sort_keys=True))
        return 0 if difference is None else 1
    if args.command == "coverage":
        report = coverage([_load(path) for path in args.receipts],
                          _load(args.requirements))
        rendered = json.dumps(report, indent=2, sort_keys=True) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(rendered, encoding="utf-8")
        print(rendered, end="")
        return 1 if args.require_complete and not report["complete"] else 0
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
