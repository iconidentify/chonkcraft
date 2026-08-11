#!/usr/bin/env python3
"""Pinned-executable function analysis and replay experiment specifications."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import subprocess
from typing import Any


FUNCTION_LAB_SCHEMA = 1
BNE_202_SHA256 = "b0e914a9cb7dcc81a205e700a9bb0a1d0649df19d459388051ba170783d2c807"


KIND_ADDRESSES = {
    "rng.async.draw": [0x00479820],
    "rng.sync.draw": [0x004534C0, 0x004234B0, 0x00423550],
    "path.route": [0x0044FBD0],
    "movement.step": [0x0044FBD0],
    "resource.wood-search": [0x00423550],
    "build.hp": [0x00452110],
    "state.observation": [0x00452110],
}


def replay_known_function(address: int, inputs: dict[str, int]) -> dict[str, Any]:
    """Concrete, side-effect-free replay for byte-verified leaf routines."""
    if "seed" not in inputs or not isinstance(inputs["seed"], int):
        raise ValueError("known RNG replay requires an integer seed")
    before = inputs["seed"] & 0xffffffff
    if address == 0x004534C0:
        after = (before * 0x41c64e6d + 0x3039) & 0xffffffff
    elif address == 0x00479820:
        after = (before * 0x015a4e35 + 1) & 0xffffffff
    else:
        raise ValueError(f"no concrete replay adapter for 0x{address:08x}")
    return {
        "schema": FUNCTION_LAB_SCHEMA, "backend": "concrete-known-leaf",
        "address": address, "inputs": {"seed": before},
        "outputs": {"seed": after, "result": (after >> 16) & 0x7fff},
    }


def boundary_variants(inputs: dict[str, int], *, maximum: int = 64) \
        -> list[dict[str, int]]:
    if maximum <= 0:
        raise ValueError("maximum must be positive")
    variants = []
    seen = set()
    for field, value in sorted(inputs.items()):
        if not isinstance(value, int):
            raise ValueError("boundary inputs must be integers")
        for candidate in (0, 1, max(0, value - 1), value, value + 1,
                          0x7fffffff, 0x80000000, 0xffffffff):
            trial = dict(inputs)
            trial[field] = candidate & 0xffffffff
            encoded = json.dumps(trial, sort_keys=True)
            if encoded not in seen:
                seen.add(encoded)
                variants.append(trial)
    return variants[:maximum]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def validate_snapshot(snapshot: dict[str, Any]) -> None:
    if snapshot.get("schema") != FUNCTION_LAB_SCHEMA:
        raise ValueError("unsupported function snapshot schema")
    if not isinstance(snapshot.get("address"), int):
        raise ValueError("function snapshot requires an integer address")
    registers = snapshot.get("registers")
    if not isinstance(registers, dict) or any(
            not isinstance(key, str) or not isinstance(value, int)
            for key, value in registers.items()):
        raise ValueError("function snapshot registers must map names to integers")
    ranges = []
    for segment in snapshot.get("memory", []):
        address = segment.get("address")
        data = segment.get("hex")
        if not isinstance(address, int) or not isinstance(data, str) \
                or len(data) % 2:
            raise ValueError("snapshot memory requires address and even hex data")
        try:
            length = len(bytes.fromhex(data))
        except ValueError as error:
            raise ValueError("snapshot memory contains invalid hex") from error
        ranges.append((address, address + length))
    ranges.sort()
    if any(left[1] > right[0] for left, right in zip(ranges, ranges[1:])):
        raise ValueError("snapshot memory ranges overlap")


def _last_json_line(text: str) -> dict[str, Any]:
    for line in reversed(text.splitlines()):
        line = line.strip()
        if line.startswith("{"):
            return json.loads(line)
    raise ValueError("radare2 returned no JSON function record")


def analyze_function(executable: Path, address: int, *, r2: str = "r2",
        expected_sha256: str = BNE_202_SHA256) -> dict[str, Any]:
    executable = executable.resolve()
    actual_sha256 = sha256(executable)
    if actual_sha256 != expected_sha256:
        raise ValueError(
            f"native executable identity mismatch: {actual_sha256}"
        )
    command = [
        r2, "-q", "-e", "scr.color=false", "-e", "bin.relocs.apply=true",
        "-c", f"aa; s 0x{address:08x}; af; pdfj; q", str(executable),
    ]
    completed = subprocess.run(
        command, capture_output=True, text=True, check=False, timeout=60,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            f"radare2 failed for 0x{address:08x}: {completed.stderr.strip()}"
        )
    record = _last_json_line(completed.stdout)
    operations = []
    branches = []
    data_references = set()
    for operation in record.get("ops", []):
        item = {
            key: operation[key] for key in
            ("addr", "size", "opcode", "type", "jump", "fail")
            if key in operation
        }
        refs = []
        for reference in operation.get("refs", []):
            compact = {key: reference.get(key) for key in ("addr", "type", "perm")}
            refs.append(compact)
            if reference.get("type") == "DATA" and isinstance(reference.get("addr"), int):
                data_references.add(reference["addr"])
        if refs:
            item["refs"] = refs
        if operation.get("type") in ("cjmp", "jmp", "call"):
            branches.append(item)
        operations.append(item)
    return {
        "schema": FUNCTION_LAB_SCHEMA,
        "executable": {
            "path": str(executable), "sha256": actual_sha256,
            "bytes": executable.stat().st_size,
        },
        "address": address,
        "name": record.get("name"),
        "size": record.get("size"),
        "operation_count": len(operations),
        "operations": operations,
        "control_transfers": branches,
        "data_references": sorted(data_references),
    }


def experiment_spec(event_kind: str, *, address: int | None = None,
        focus_fields: list[str] | None = None) -> dict[str, Any]:
    addresses = [address] if address is not None \
        else KIND_ADDRESSES.get(event_kind, [])
    fields = focus_fields or {
        "rng.sync.draw": ["seed", "caller", "unit", "order"],
        "rng.async.draw": ["seed", "caller", "unit", "phase"],
        "path.route": ["x", "y", "goal-x", "goal-y", "flags", "path"],
        "movement.step": ["x", "y", "heading", "route-spent", "delay"],
        "build.hp": ["hp", "max-hp", "progress", "cycle"],
    }.get(event_kind, ["unit", "cycle", "order"])
    return {
        "schema": FUNCTION_LAB_SCHEMA,
        "event_kind": event_kind,
        "addresses": addresses,
        "capture": {
            "registers": ["eax", "ebx", "ecx", "edx", "esi", "edi", "esp", "ebp"],
            "stack_bytes": 256,
            "focus_fields": fields,
            "call_stack_depth": 8,
            "record_return": True,
            "record_written_memory": True,
        },
        "mutations": [
            {"field": field, "strategy": "boundary-neighbours"}
            for field in fields
        ],
        "backends": [
            {"name": "concrete", "purpose": "replay captured register/memory state"},
            {"name": "symbolic", "purpose": "find inputs reaching alternate branches"},
        ],
        "acceptance": [
            "pinned executable SHA-256 matches",
            "unmodified snapshot reproduces the captured result",
            "each inferred branch is confirmed by a concrete replay",
            "the inferred rule passes the Java 52-case gate",
        ],
    }
