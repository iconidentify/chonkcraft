#!/usr/bin/env python3
"""Seal the 52 ChonkCraft mission wrappers into Chonkcraft's native mission DSL.

The input scripts are a development-time provenance source only. The emitted
resource contains final allow tables plus postfix condition programs and typed
actions; the player runtime never reads or evaluates retired scripting language/SMS.
"""

from __future__ import annotations

import argparse
import base64
import re
from dataclasses import dataclass
from pathlib import Path


def b64(value: str) -> str:
    return base64.urlsafe_b64encode(value.encode()).decode().rstrip("=")


def unb64(value: str) -> str:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4)).decode()


TOKEN = re.compile(
    r"\s*(?:(>=|<=|==|~=|>|<|\+|\(|\)|\{|\}|,)|"
    r"([A-Za-z_][A-Za-z0-9_]*)|(-?\d+)|"
    r"\"((?:\\.|[^\"\\])*)\"|'((?:\\.|[^'\\])*)')",
    re.S,
)


def tokens(source: str) -> list[tuple[str, object]]:
    result: list[tuple[str, object]] = []
    at = 0
    while at < len(source):
        match = TOKEN.match(source, at)
        if not match:
            if source[at:].strip() == "":
                break
            raise ValueError(f"cannot tokenize {source[at:at + 80]!r} in {source!r}")
        op, ident, number, double, single = match.groups()
        if op:
            result.append((op, op))
        elif ident:
            result.append(("IDENT", ident))
        elif number:
            result.append(("NUMBER", int(number)))
        else:
            raw = double if double is not None else single
            result.append(("STRING", bytes(raw, "utf-8").decode("unicode_escape")))
        at = match.end()
    result.append(("EOF", None))
    return result


@dataclass(frozen=True)
class Node:
    kind: str
    value: object = None
    children: tuple["Node", ...] = ()


class Parser:
    PRECEDENCE = {"or": 1, "and": 2, "==": 3, "~=": 3, ">=": 3,
                  ">": 3, "<=": 3, "<": 3, "+": 4}

    def __init__(self, source: str):
        self.source = source
        self.tokens = tokens(source)
        self.at = 0

    def peek(self) -> tuple[str, object]:
        return self.tokens[self.at]

    def take(self, kind: str | None = None) -> tuple[str, object]:
        token = self.peek()
        if kind is not None and token[0] != kind:
            raise ValueError(f"expected {kind}, got {token} in {self.source!r}")
        self.at += 1
        return token

    def parse(self) -> Node:
        result = self.expression(0)
        self.take("EOF")
        return result

    def expression(self, minimum: int) -> Node:
        left = self.primary()
        while True:
            kind, value = self.peek()
            op = value if kind == "IDENT" and value in ("and", "or") else kind
            precedence = self.PRECEDENCE.get(op, -1)
            if precedence < minimum:
                break
            self.take()
            right = self.expression(precedence + 1)
            left = Node("binary", op, (left, right))
        return left

    def primary(self) -> Node:
        kind, value = self.take()
        if kind == "NUMBER":
            return Node("number", value)
        if kind == "STRING":
            return Node("string", value)
        if kind == "(":
            value = self.expression(0)
            self.take(")")
            return value
        if kind == "{":
            values: list[Node] = []
            if self.peek()[0] != "}":
                while True:
                    values.append(self.expression(0))
                    if self.peek()[0] != ",":
                        break
                    self.take(",")
            self.take("}")
            return Node("table", children=tuple(values))
        if kind != "IDENT":
            raise ValueError(f"unexpected {kind} in {self.source!r}")
        if value == "true":
            return Node("boolean", True)
        if value == "nil":
            return Node("nil")
        if self.peek()[0] != "(":
            return Node("variable", value)
        self.take("(")
        args: list[Node] = []
        if self.peek()[0] != ")":
            while True:
                args.append(self.expression(0))
                if self.peek()[0] != ",":
                    break
                self.take(",")
        self.take(")")
        return Node("call", value, tuple(args))


def scalar(node: Node) -> list[str]:
    if node.kind == "number":
        return [f"N{node.value}"]
    if node.kind == "string":
        return ["Q" + b64(str(node.value))]
    if node.kind == "boolean":
        return ["TRUE" if node.value else "FALSE"]
    if node.kind == "nil":
        return ["NIL"]
    if node.kind == "variable":
        return ["F" + b64(str(node.value))]
    if node.kind == "call" and node.value == "GetThisPlayer":
        return ["THIS"]
    raise ValueError(f"not a scalar: {node}")


def program(node: Node) -> list[str]:
    if node.kind in {"number", "string", "boolean", "nil", "variable"}:
        return scalar(node)
    if node.kind == "binary":
        names = {"and": "AND", "or": "OR", "+": "ADD", "==": "EQ",
                 "~=": "NE", ">=": "GE", ">": "GT", "<=": "LE", "<": "LT"}
        return program(node.children[0]) + program(node.children[1]) + [names[str(node.value)]]
    if node.kind != "call":
        raise ValueError(f"unsupported expression: {node}")
    name = str(node.value)
    args = node.children
    if name == "GetThisPlayer":
        return ["THIS"]
    if name == "GetPlayerData":
        what = args[1].value if len(args) > 1 and args[1].kind == "string" else None
        if what == "TotalNumUnits":
            return scalar(args[0]) + ["TOTAL"]
        if what == "UnitTypesCount" and len(args) == 3:
            return scalar(args[0]) + scalar(args[2]) + ["UNIT_COUNT"]
        raise ValueError(f"unsupported GetPlayerData: {node}")
    if name == "GetNumOpponents":
        return scalar(args[0]) + ["OPPONENTS"]
    if name == "GetNumUnitsAt":
        if len(args) != 4 or args[2].kind != "table" or args[3].kind != "table":
            raise ValueError(f"unsupported GetNumUnitsAt: {node}")
        flat = scalar(args[0]) + scalar(args[1])
        for corner in (args[2], args[3]):
            if len(corner.children) != 2:
                raise ValueError(f"invalid corner: {corner}")
            flat += scalar(corner.children[0]) + scalar(corner.children[1])
        return flat + ["UNITS_AT"]
    if name in {"IfNearUnit", "IfRescuedNearUnit"}:
        if len(args) != 5:
            raise ValueError(f"unsupported near call: {node}")
        flat: list[str] = []
        for arg in args:
            flat += scalar(arg)
        return flat + (["RESCUED_NEAR"] if name == "IfRescuedNearUnit" else ["NEAR"])
    raise ValueError(f"unsupported call {name}: {node}")


def trigger_blocks(source: str) -> list[tuple[str, str]]:
    result: list[tuple[str, str]] = []
    at = 0
    while True:
        start = source.find("AddTrigger(", at)
        if start < 0:
            break
        depth = 0
        quote = None
        escaped = False
        end = start
        while end < len(source):
            char = source[end]
            if quote:
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == quote:
                    quote = None
            elif char in "\"'":
                quote = char
            elif char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
                if depth == 0:
                    end += 1
                    break
            end += 1
        block = source[start:end]
        found = re.match(
            r"AddTrigger\(\s*function\(\)\s*return\s+(.*?)\s+end\s*,\s*function\(\)(.*)end\s*\)\s*$",
            block, re.S,
        )
        if not found:
            raise ValueError(f"cannot split trigger block: {block}")
        result.append((found.group(1).strip(), found.group(2).strip()))
        at = end
    return result


def action(path: str, index: int, body: str) -> str:
    if path == "campaigns/human/level02h" and index == 0:
        return "DELAYED_VICTORY 120 Q" + b64("rescue (human)")
    if path == "campaigns/human/level08h" and index == 0:
        calls = re.findall(r'SetDiplomacy\((\d+),\s*"([^"]+)",\s*(\d+)\)', body)
        if len(calls) != 4:
            raise ValueError(f"unexpected diplomacy action: {body}")
        parts = ["DIPLOMACY"]
        for player, stance, opponent in calls:
            parts += [player, "Q" + b64(stance), opponent]
        return " ".join(parts)
    if path == "campaigns/orc/level02o" and index == 1:
        if "HaveUnitSharpAxe=true" not in body.replace(" ", ""):
            raise ValueError(f"unexpected flag action: {body}")
        return "SET_FLAG Q" + b64("HaveUnitSharpAxe")
    compact = " ".join(body.split())
    if "ActionVictory" in compact and compact in {
            "return ActionVictory()", "ActionVictory() return false"}:
        return "VICTORY"
    if "ActionDefeat" in compact and compact in {
            "return ActionDefeat()", "ActionDefeat() return false"}:
        return "DEFEAT"
    if "ActionDraw" in compact:
        return "DRAW"
    raise ValueError(f"unclassified action {path} #{index}: {compact}")


def build_triggers(root: Path) -> dict[str, list[tuple[str, str]]]:
    result: dict[str, list[tuple[str, str]]] = {}
    for file in sorted(root.rglob("*_c.sms")):
        relative = file.relative_to(root).as_posix()
        path = "campaigns/" + relative.removesuffix("_c.sms")
        rows = []
        for index, (condition, body) in enumerate(trigger_blocks(
                file.read_text(encoding="latin1"))):
            rows.append((" ".join(program(Parser(condition).parse())),
                         action(path, index, body)))
        result[path] = rows
    if len(result) != 52 or sum(map(len, result.values())) != 137:
        raise ValueError(f"expected 52 missions / 137 triggers, got "
                         f"{len(result)} / {sum(map(len, result.values()))}")
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--snapshot", type=Path, required=True)
    parser.add_argument("--campaigns", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    triggers = build_triggers(args.campaigns)

    output: list[str] = []
    current = None
    seen: set[str] = set()
    for line in args.snapshot.read_text(encoding="utf-8").splitlines():
        fields = line.split("\t")
        if fields[0] == "M":
            current = unb64(fields[1])
            seen.add(current)
        if fields[0] == "E":
            if current is None or current not in triggers:
                raise ValueError(f"snapshot mission lacks triggers: {current}")
            for condition, effect in triggers[current]:
                output.append("T\t" + b64(condition) + "\t" + b64(effect))
            current = None
        output.append(line)
    missing = set(triggers) - seen
    if missing:
        raise ValueError(f"trigger scripts absent from snapshot: {sorted(missing)}")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(output) + "\n", encoding="utf-8")
    print(f"wrote {args.output}: 52 missions, 137 triggers, {len(output)} lines")


if __name__ == "__main__":
    main()
