#!/usr/bin/env python3
"""Generate, import, and seal GDB BTS captures for BNE Branch Witness.

The recorder deliberately has two phases.  A generated GDB script records a
small instruction window and stops on the exact unit-field hardware watchpoint.
This importer then reconstructs the executed conditional branches from that
history and seals the result.  Keeping import deterministic makes a raw GDB log
replayable and auditable without rerunning the game.
"""

from __future__ import annotations

import argparse
import copy
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
from typing import Any, Iterable

from bne_branch_witness import (
    BNE_202_SHA256, BNE_TEXT_END, BNE_TEXT_START, CAPTURE_MANIFEST_SCHEMA,
    CAPTURE_SCHEMA, _function_hint,
)
from bne_triage import canonical_digest, file_identity


SCHEMA = 1
INSTRUCTION = re.compile(
    r"^\s*(?:=>\s*)?(?P<index>\d+)\s+"
    r"(?P<address>0x[0-9a-fA-F]+):?\s+(?P<body>.+?)\s*$"
)
MARKER = re.compile(
    r"BNEWITNESS\s+watch=(?P<watch>0x[0-9a-fA-F]+)\s+"
    r"before=(?P<before>\d+)\s+after=(?P<after>\d+)"
)
PREDICATE_MARKER = re.compile(
    r"BNEPREDICATE\s+branch=(?P<branch>0x[0-9a-fA-F]+)\s+"
    r"compare=(?P<compare>0x[0-9a-fA-F]+)\s+hit=(?P<hit>\d+)\s+"
    r"lhs=(?P<lhs>-?\d+)\s+rhs=(?P<rhs>-?\d+)"
    r"(?:\s+focus=(?P<focus>0x[0-9a-fA-F]+)"
    r"\s+expected=(?P<expected>0x[0-9a-fA-F]+)"
    r"\s+focus_match=(?P<focus_match>[01]))?"
)
TARGET = re.compile(r"(?:\*?)(0x[0-9a-fA-F]+)")
CONDITIONAL_JUMP = re.compile(
    r"^(?:lock\s+)?j(?!mp\b)(?P<condition>[a-z0-9]+)\b",
    re.IGNORECASE,
)
REGISTER = re.compile(r"^e(?:ax|bx|cx|dx|si|di|bp|sp)$", re.IGNORECASE)
REGISTER_COMPARE = re.compile(
    r"^cmp\w*\s+%(?P<src>e(?:ax|bx|cx|dx|si|di|bp|sp))\s*,\s*"
    r"%(?P<dst>e(?:ax|bx|cx|dx|si|di|bp|sp))\s*$",
    re.IGNORECASE,
)


def _json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"expected a JSON object: {path}")
    return value


def _write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(
                mode="w", encoding="utf-8", prefix=path.name + ".",
                suffix=".tmp", dir=path.parent, delete=False) as handle:
            temporary = Path(handle.name)
            json.dump(value, handle, indent=2, sort_keys=True)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _write_text(path: Path, value: str) -> None:
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


def _safe_shell_path(path: Path) -> str:
    value = str(path)
    if "\n" in value or "\r" in value or "\0" in value:
        raise ValueError("GDB marker path contains unsafe control characters")
    return shlex.quote(value)


def _watch_type(byte_count: int) -> str:
    types = {1: "unsigned char", 2: "unsigned short", 4: "unsigned int"}
    if byte_count not in types:
        raise ValueError(f"unsupported hardware watch size: {byte_count}")
    return types[byte_count]


def predicate_probe(branch: int, compare: int, lhs_register: str,
        rhs_register: str, condition: str,
        focus_register: str | None = None) -> dict[str, Any]:
    """Validate a narrow register/register predicate observation request."""
    lhs, rhs = lhs_register.lower(), rhs_register.lower()
    if not REGISTER.fullmatch(lhs) or not REGISTER.fullmatch(rhs):
        raise ValueError("predicate operands must be 32-bit x86 register names")
    if not BNE_TEXT_START <= compare < branch < BNE_TEXT_END \
            or branch - compare > 15:
        raise ValueError("predicate compare/branch addresses are not adjacent BNE text")
    condition = condition.lower()
    if not re.fullmatch(r"[a-z]{1,3}", condition):
        raise ValueError("predicate condition must be an x86 jump suffix")
    signed = condition in {"l", "le", "g", "ge", "nl", "ng", "nle", "nge"}
    result = {
        "schema": SCHEMA,
        "branch": branch,
        "compare": compare,
        "condition": condition,
        "lhs": {"name": lhs, "gdb": f"${lhs}"},
        "rhs": {"name": rhs, "gdb": f"${rhs}"},
        "encoding": "signed-int32" if signed else "unsigned-int32",
        "scope": "last dynamic observation before watched writer",
    }
    if focus_register is not None:
        focus = focus_register.lower()
        if not REGISTER.fullmatch(focus):
            raise ValueError("predicate focus must be a 32-bit x86 register")
        result["focus"] = {"name": focus, "gdb": f"${focus}"}
        result["scope"] = (
            "last focus-matched dynamic observation before watched writer"
        )
    return result


def register_probe(addresses: Iterable[int]) -> list[int]:
    """Validate a request to print the machine each time a loop comes round.

    The predicate probe answers "what were these two registers when this
    comparison ran", which needs to know beforehand which registers matter.
    A loop nobody has read yet does not offer that: what is wanted is every
    register at each pass, so which of them is walking over map squares can be
    read off afterwards rather than guessed at first.
    """
    wanted = []
    for address in addresses:
        if not BNE_TEXT_START <= address < BNE_TEXT_END:
            raise ValueError(
                f"register probe 0x{address:08x} is outside the pinned BNE text")
        if address in wanted:
            raise ValueError(f"register probe 0x{address:08x} is repeated")
        wanted.append(address)
    if len(wanted) > 8:
        raise ValueError("a register probe covers at most eight addresses")
    return wanted


def _register_probe_commands(addresses: Iterable[int]) -> list[str]:
    commands = []
    for address in addresses:
        commands.extend([
            f"break *0x{address:08x}",
            "commands",
            "silent",
            f"printf \"BNEREGS at=0x{address:08x} eax=0x%08x ebx=0x%08x "
            "ecx=0x%08x edx=0x%08x esi=0x%08x edi=0x%08x ebp=0x%08x\\n\", "
            "(unsigned int)$eax, (unsigned int)$ebx, (unsigned int)$ecx, "
            "(unsigned int)$edx, (unsigned int)$esi, (unsigned int)$edi, "
            "(unsigned int)$ebp",
            "continue",
            "end",
        ])
    return commands


def _predicate_commands(probe: dict[str, Any]) -> list[str]:
    signed = probe["encoding"] == "signed-int32"
    formatter = "%d" if signed else "%u"
    cast = "int" if signed else "unsigned int"
    marker = (
        f"printf \"BNEPREDICATE branch=0x{probe['branch']:08x} "
        f"compare=0x{probe['compare']:08x} hit=%u lhs={formatter} "
        f"rhs={formatter}"
    )
    marker_arguments = (
        f"$bne_probe_hits, ({cast}){probe['lhs']['gdb']}, "
        f"({cast}){probe['rhs']['gdb']}"
    )
    focus = probe.get("focus")
    if isinstance(focus, dict):
        marker += " focus=0x%x expected=0x%x focus_match=1"
        marker_arguments += (
            f", (unsigned int){focus['gdb']}, (unsigned int)$bne_focus"
        )
    marker += f"\\n\", {marker_arguments}"
    commands = [
        "set $bne_probe_hits = 0",
        f"break *0x{probe['compare']:08x}",
        "commands",
        "silent",
        "set $bne_probe_hits = $bne_probe_hits + 1",
    ]
    if isinstance(focus, dict):
        commands.extend([
            f"if (unsigned int){focus['gdb']} == (unsigned int)$bne_focus",
            marker,
            "end",
        ])
    else:
        commands.append(marker)
    commands.extend(["continue", "end"])
    return commands


def gdb_commands(plan: dict[str, Any], *, field: str,
        history_log: Path, resume_marker: Path,
        predicate: dict[str, Any] | None = None,
        registers_at: Iterable[int] = ()) -> str:
    """Return a batch-safe GDB command file for an already paused process."""
    watch = next((item for item in plan["native_layout"]["watches"]
                  if item["field"] == field), None)
    if watch is None:
        raise ValueError(f"witness plan does not watch field {field}")
    slot = int(plan["focus"]["native_slot"])
    pool_pointer = int(plan["native_layout"]["unit_pool_pointer"])
    unit_bytes = int(plan["native_layout"]["unit_bytes"])
    size = int(watch["bytes"])
    type_name = _watch_type(size)
    commands = [
        "set pagination off",
        "set confirm off",
        "set print thread-events off",
        f"set logging file {history_log}",
        "set logging overwrite on",
        "set logging redirect on",
        "set logging enabled on",
        f"set $bne_pool = *(unsigned int*)0x{pool_pointer:08x}",
        f"set $bne_focus = $bne_pool + {slot} * {unit_bytes}",
        f"set $bne_watch = $bne_pool + {slot} * {unit_bytes} + {watch['offset']}",
        f"set $bne_before = *({type_name}*)$bne_watch",
        f"watch -l *({type_name}*)$bne_watch",
    ]
    if predicate is not None:
        commands.extend(_predicate_commands(predicate))
    registers_at = list(registers_at)
    if registers_at:
        commands.extend(_register_probe_commands(registers_at))
    commands.extend([
        "set record btrace bts buffer-size 1048576",
        "record btrace bts",
        f"shell touch {_safe_shell_path(resume_marker)}",
        "continue",
        f"set $bne_after = *({type_name}*)$bne_watch",
        "printf \"BNEWITNESS watch=0x%x before=%u after=%u\\n\", "
        "$bne_watch, $bne_before, $bne_after",
        "info registers",
        f"set record instruction-history-size "
        f"{plan['capture_window']['maximum_instructions']}",
        "record instruction-history",
        "set logging enabled off",
        "record stop",
        "detach",
        "quit",
        "",
    ])
    return "\n".join(commands)


def parse_instruction_history(text: str) -> list[dict[str, Any]]:
    """Parse GDB's stable numbered instruction-history table."""
    instructions = []
    for line in text.splitlines():
        match = INSTRUCTION.match(line)
        if match is None:
            continue
        body = match.group("body").strip()
        # GDB may include a symbol column before the actual disassembly.
        if "\t" in body:
            body = body.rsplit("\t", 1)[-1].strip()
        instructions.append({
            "index": int(match.group("index")),
            "address": int(match.group("address"), 16),
            "instruction": body,
        })
    instructions.sort(key=lambda item: item["index"])
    deduplicated = []
    for item in instructions:
        if deduplicated and item["index"] == deduplicated[-1]["index"]:
            deduplicated[-1] = item
        else:
            deduplicated.append(item)
    return deduplicated


def _assembly(body: str) -> str:
    # Strip common "<symbol+offset>:" and encoded-byte columns.
    value = re.sub(r"^<[^>]+>:\s*", "", body).strip()
    value = re.sub(r"^(?:[0-9a-fA-F]{2}\s+){1,15}", "", value).strip()
    return value


def _branch_target(assembly: str) -> int | None:
    match = CONDITIONAL_JUMP.match(assembly)
    if match is None:
        return None
    suffix = assembly[match.end():]
    target = TARGET.search(suffix)
    return int(target.group(1), 16) if target else None


def _automatic_predicate_probe(instructions: list[dict[str, Any]], index: int,
        branch: int, condition: str) -> dict[str, Any] | None:
    if index <= 0:
        return None
    compare = instructions[index - 1]
    match = REGISTER_COMPARE.match(_assembly(compare["instruction"]))
    if match is None:
        return None
    try:
        # AT&T cmp source,destination sets flags for destination - source.
        return predicate_probe(
            branch, int(compare["address"]), match.group("dst"),
            match.group("src"), condition,
        )
    except ValueError:
        return None


def capture_from_gdb_log(plan: dict[str, Any], field: str, text: str,
        *, predicate: dict[str, Any] | None = None) \
        -> dict[str, Any]:
    instructions = parse_instruction_history(text)
    if len(instructions) < 2:
        raise ValueError("GDB log has too little instruction history")
    marker = MARKER.search(text)
    if marker is None:
        raise ValueError("GDB log has no completed watchpoint marker")
    watch = next((item for item in plan["native_layout"]["watches"]
                  if item["field"] == field), None)
    if watch is None:
        raise ValueError(f"witness plan does not watch field {field}")
    cycle = int(plan["divergence_cycle"])
    candidates = [int(address) for address in
                  plan["native_layout"].get("candidate_functions", [])]
    text_range = plan["native_layout"]["executable_text"]
    text_start, text_end = int(text_range["start"]), int(text_range["end"])
    events = []
    seq = 0
    for index, instruction in enumerate(instructions[:-1]):
        assembly = _assembly(instruction["instruction"])
        target = _branch_target(assembly)
        if target is None or not text_start <= instruction["address"] < text_end:
            continue
        next_address = instructions[index + 1]["address"]
        function = _function_hint(candidates, instruction["address"])
        seq += 1
        condition = CONDITIONAL_JUMP.match(assembly)
        condition_name = condition.group("condition") if condition else None
        automatic_probe = _automatic_predicate_probe(
            instructions, index, instruction["address"], condition_name or "",
        )
        events.append({
            "seq": seq, "type": "branch", "cycle": cycle,
            "address": instruction["address"], "target": target,
            "taken": next_address == target, "conditional": True,
            "condition": condition_name,
            "instruction": assembly,
            "function": function,
            "predicate_probe_plan": automatic_probe,
        })
    last = instructions[-1]
    if not text_start <= last["address"] < text_end:
        raise ValueError(
            f"watchpoint writer lies outside pinned BNE text: "
            f"0x{last['address']:08x}"
        )
    seq += 1
    events.append({
        "seq": seq, "type": "write", "cycle": cycle,
        "address": int(marker.group("watch"), 16),
        "instruction": last["address"],
        "instruction_text": _assembly(last["instruction"]),
        "function": _function_hint(candidates, last["address"]),
        "native_slot": int(plan["focus"]["native_slot"]),
        "field": field, "offset": int(watch["offset"]),
        "before": int(marker.group("before")),
        "after": int(marker.group("after")),
    })
    predicate_markers = list(PREDICATE_MARKER.finditer(text))
    if predicate is not None:
        predicate_markers = [item for item in predicate_markers
                             if int(item.group("branch"), 16)
                             == int(predicate["branch"])]
        if not predicate_markers:
            raise ValueError("GDB log has no completed predicate observation")
    if predicate_markers:
        observed = predicate_markers[-1]
        branch_address = int(observed.group("branch"), 16)
        branch_events = [event for event in events
                         if event.get("type") == "branch"
                         and event.get("address") == branch_address]
        if not branch_events:
            raise ValueError("predicate observation has no matching branch history")
        hit = int(observed.group("hit"))
        if hit <= 0 or hit > len(branch_events):
            raise ValueError(
                "predicate hit cannot be correlated with the recorded branch history"
            )
        branch_event = branch_events[hit - 1]
        branch_event["operands"] = {
            "lhs": {
                "name": predicate["lhs"]["name"] if predicate else "lhs",
                "value": int(observed.group("lhs")),
            },
            "rhs": {
                "name": predicate["rhs"]["name"] if predicate else "rhs",
                "value": int(observed.group("rhs")),
            },
        }
        focus = observed.group("focus")
        expected = observed.group("expected")
        focus_match = observed.group("focus_match")
        focus_requested = isinstance(predicate, dict) \
            and isinstance(predicate.get("focus"), dict)
        if focus_requested and None in (focus, expected, focus_match):
            raise ValueError("focus-scoped predicate marker is incomplete")
        focus_identity = {
            "required": focus_requested,
            "proved": False,
            "reason": "predicate probe did not authenticate a focus register",
        }
        if focus_requested:
            observed_focus = int(focus, 16)
            expected_focus = int(expected, 16)
            matched = focus_match == "1" and observed_focus == expected_focus
            if not matched:
                raise ValueError("predicate focus identity did not match")
            focus_identity = {
                "required": True,
                "proved": True,
                "register": predicate["focus"]["name"],
                "observed": observed_focus,
                "expected": expected_focus,
                "matched": True,
            }
        branch_event["predicate_probe"] = {
            "compare": int(observed.group("compare"), 16),
            "hit": hit,
            "encoding": predicate.get("encoding") if predicate else None,
            "focus_identity": focus_identity,
        }
    return {
        "schema": CAPTURE_SCHEMA,
        "backend": "gdb-bts-plus-hardware-watchpoint",
        "case": plan["case"],
        "cycle": cycle,
        "field": field,
        "instruction_count": len(instructions),
        "events": events,
    }


def seal_capture(plan: dict[str, Any], history_log: Path, output: Path, *,
        field: str, executable: Path, tracer: Path,
        oracle_run_manifest: Path,
        gdb_version: str = "unknown", network_disabled: bool = True,
        predicate: dict[str, Any] | None = None) \
        -> tuple[Path, Path]:
    executable = executable.expanduser().resolve()
    tracer = tracer.expanduser().resolve()
    history_log = history_log.expanduser().resolve()
    output = output.expanduser().resolve()
    oracle_run_manifest = oracle_run_manifest.expanduser().resolve()
    if file_identity(executable)["sha256"] != BNE_202_SHA256:
        raise ValueError("capture sealing refuses an unpinned BNE executable")
    if not tracer.is_file():
        raise ValueError(f"capture tracer is missing: {tracer}")
    if not network_disabled:
        raise ValueError("capture sealing requires a network-disabled runtime")
    oracle_run = _json(oracle_run_manifest)
    run = oracle_run.get("run", {})
    validation = run.get("validation", {})
    runtime = oracle_run.get("runtime", {})
    if oracle_run.get("oracle", {}).get("executable", {}).get("sha256") \
            != BNE_202_SHA256:
        raise ValueError("oracle run manifest does not pin BNE 2.02b")
    if runtime.get("network_disabled") is not True \
            or runtime.get("branch_witness_pause_cycle") \
            != int(plan["divergence_cycle"]):
        raise ValueError("oracle run manifest lacks the offline witness pause")
    expected_run = {
        "scenario": plan.get("scenario"),
        "initialization_seed": plan.get("seed"),
    }
    if any(validation.get(key) != value for key, value in expected_run.items()) \
            or validation.get("cycles", 0) < int(plan["divergence_cycle"]):
        raise ValueError("oracle run manifest differs from the witness plan")
    capture = capture_from_gdb_log(
        plan, field, history_log.read_text(encoding="utf-8", errors="replace"),
        predicate=predicate,
    )
    _write_json(output, capture)
    manifest_path = output.with_name(output.stem + ".manifest.json")
    manifest = {
        "schema": CAPTURE_MANIFEST_SCHEMA,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "capture": {"name": output.name, **file_identity(output)},
        "oracle": {"executable": {
            "path": str(executable), **file_identity(executable),
        }, "run_manifest": {
            "path": str(oracle_run_manifest), **file_identity(oracle_run_manifest),
            "scenario": validation.get("scenario"),
            "seed": validation.get("initialization_seed"),
            "cycles": validation.get("cycles"),
            "branch_pause_cycle": runtime.get("branch_witness_pause_cycle"),
        }},
        "harness": {"tracer": {
            "path": str(tracer), **file_identity(tracer),
        }, "capture_importer": {
            "path": str(Path(__file__).resolve()),
            **file_identity(Path(__file__).resolve()),
        }},
        "runtime": {"network_disabled": True},
        "backend": {
            "name": "gdb-bts-plus-hardware-watchpoint",
            "gdb_version": gdb_version,
            "branch_history": True,
            "writer_watchpoint": True,
            "predicate_probe": predicate is not None,
            "predicate": predicate,
            "raw_history": {
                "path": str(history_log), **file_identity(history_log),
            },
        },
        "request": {
            "case": plan["case"], "cycle": plan["divergence_cycle"],
            "native_slot": plan["focus"]["native_slot"],
            "fields": [field], "scenario": plan.get("scenario"),
            "seed": plan.get("seed"), "fixture_id": plan.get("fixture_id"),
            "plan_sha256": canonical_digest(plan),
        },
    }
    _write_json(manifest_path, manifest)
    return output, manifest_path


def control_plan(plan: dict[str, Any], *, cycle: int, field: str,
        before: int, after: int) -> dict[str, Any]:
    """Derive an auditable earlier-cycle contrast from a witness plan."""
    if cycle <= 0 or cycle >= int(plan["divergence_cycle"]):
        raise ValueError("control cycle must precede the divergence")
    result = copy.deepcopy(plan)
    watch = next((item for item in result["native_layout"]["watches"]
                  if item["field"] == field), None)
    if watch is None:
        raise ValueError(f"witness plan does not watch field {field}")
    result["role"] = "clean-earlier-cycle-control"
    result["anchor_divergence_cycle"] = plan["divergence_cycle"]
    result["divergence_cycle"] = cycle
    result["capture_window"] = {
        **result["capture_window"],
        "start_cycle": max(1, cycle - 1), "end_cycle": cycle,
    }
    watch["before"] = before
    watch["oracle"] = after
    watch["purpose"] = "clean-control-transition"
    return result


def script_command(args: argparse.Namespace) -> int:
    plan = _json(args.plan)
    probe = _predicate_from_args(args)
    text = gdb_commands(
        plan, field=args.field, history_log=args.history_log,
        resume_marker=args.resume_marker, predicate=probe,
    )
    _write_text(args.output.expanduser().resolve(), text)
    return 0


def import_command(args: argparse.Namespace) -> int:
    plan = _json(args.plan)
    probe = _predicate_from_args(args)
    version = subprocess.run(
        [args.gdb, "--version"], check=False, capture_output=True, text=True,
    ).stdout.splitlines()
    seal_capture(
        plan, args.history_log, args.output, field=args.field,
        executable=args.executable, tracer=args.tracer,
        oracle_run_manifest=args.oracle_run_manifest,
        gdb_version=version[0] if version else "unknown",
        network_disabled=True,
        predicate=probe,
    )
    return 0


def control_plan_command(args: argparse.Namespace) -> int:
    derived = control_plan(
        _json(args.plan), cycle=args.cycle, field=args.field,
        before=args.before, after=args.after,
    )
    _write_json(args.output.expanduser().resolve(), derived)
    return 0


def _address(value: str) -> int:
    return int(value, 0)


def _add_predicate_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--predicate-branch", type=_address)
    parser.add_argument("--predicate-compare", type=_address)
    parser.add_argument("--predicate-lhs-register")
    parser.add_argument("--predicate-rhs-register")
    parser.add_argument("--predicate-condition")
    parser.add_argument(
        "--predicate-focus-register",
        help="unit-pointer register; only observations matching the watched unit are retained",
    )


def _predicate_from_args(args: argparse.Namespace) -> dict[str, Any] | None:
    values = (
        args.predicate_branch, args.predicate_compare,
        args.predicate_lhs_register, args.predicate_rhs_register,
        args.predicate_condition,
    )
    if not any(value is not None for value in (
            *values, args.predicate_focus_register)):
        return None
    if not all(value is not None for value in values):
        raise ValueError("predicate probe requires all five predicate arguments")
    return predicate_probe(*values, args.predicate_focus_register)


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    subcommands = result.add_subparsers(dest="command", required=True)
    script = subcommands.add_parser(
        "gdb-script", help="generate the batch GDB recorder command file",
    )
    script.add_argument("plan", type=Path)
    script.add_argument("--field", required=True)
    script.add_argument("--history-log", type=Path, required=True)
    script.add_argument("--resume-marker", type=Path, required=True)
    script.add_argument("--output", type=Path, required=True)
    _add_predicate_arguments(script)
    script.set_defaults(func=script_command)
    importer = subcommands.add_parser(
        "import-gdb", help="import a raw GDB log and seal a branch capture",
    )
    importer.add_argument("plan", type=Path)
    importer.add_argument("--field", required=True)
    importer.add_argument("--history-log", type=Path, required=True)
    importer.add_argument("--output", type=Path, required=True)
    importer.add_argument("--executable", type=Path, required=True)
    importer.add_argument("--tracer", type=Path, required=True)
    importer.add_argument("--oracle-run-manifest", type=Path, required=True)
    importer.add_argument("--gdb", default="gdb")
    _add_predicate_arguments(importer)
    importer.set_defaults(func=import_command)
    control = subcommands.add_parser(
        "control-plan",
        help="derive an earlier-cycle clean contrast for the same native field",
    )
    control.add_argument("plan", type=Path)
    control.add_argument("--cycle", type=int, required=True)
    control.add_argument("--field", required=True)
    control.add_argument("--before", type=int, required=True)
    control.add_argument("--after", type=int, required=True)
    control.add_argument("--output", type=Path, required=True)
    control.set_defaults(func=control_plan_command)
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        return args.func(args)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"bne-branch-capture: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
