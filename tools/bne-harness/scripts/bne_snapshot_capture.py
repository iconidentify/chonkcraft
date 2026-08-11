#!/usr/bin/env python3
"""Save the machine at one native decision so it can be replayed offline.

The micro-oracle can replay a bounded native function thousands of times a
second, and until now it had nothing native to replay: Branch Witness records
which instruction wrote a byte and which branch controlled it, and it never
saved the registers, the stack, the code or the data, because answering its own
question never needed them.  `micro-oracle-plan` names those five missing parts.
This module collects them.

Two phases, for the same reason the branch recorder has two.  A generated GDB
command file stops the paused oracle at one activation of one address, prints
the machine in a format nobody has to guess at, runs the invocation to its
return, and prints the machine again.  This importer then rebuilds a
micro-oracle snapshot from that log alone, so a raw capture can be re-imported,
audited and compared without the game running anywhere.

Everything here fails closed.  A region that came back short, an activation
that executed code nobody captured, a return address that is not where the
capture said it was: each refuses the import by name.  A snapshot that
half-loads produces a replay that half-reproduces, and a report that half
reproduces is worse than none, because it looks like evidence.
"""

from __future__ import annotations

from datetime import datetime, timezone
import json
from pathlib import Path
import re
import shlex
import tempfile
from typing import Any, Iterable, Sequence

# The branch recorder's reader, on purpose.  A GDB instruction history means
# the same thing in both tools, and a second transcription of "which of these
# is a conditional jump and did it go there" is a second thing to be wrong.
from bne_branch_capture import (
    _assembly, _branch_target, parse_instruction_history,
)
from bne_branch_witness import (
    BNE_202_SHA256, BNE_TEXT_END, BNE_TEXT_START, UNIT_BYTES,
    UNIT_POOL_POINTER,
)
from bne_micro_oracle import (
    GENERAL_REGISTERS, MASK32, RETURN_SENTINEL, SNAPSHOT_REGISTERS, Segment,
    Snapshot, load_snapshot, snapshot_document,
)
from bne_triage import canonical_digest, file_identity, inventory_files


SCHEMA = 1

#: One region of a capture, and all of them together.  A capture is a bounded
#: piece of evidence about one decision; a specification asking for a megabyte
#: of the process is asking for a core dump, which is a different tool.
MAXIMUM_REGION_BYTES = 64 * 1024
MAXIMUM_CAPTURE_BYTES = 256 * 1024

#: How much instruction history one invocation may carry.  A decision that
#: executes more than this is not the bounded function this tool replays.
MAXIMUM_INSTRUCTIONS = 65536

SAFE_LABEL = re.compile(r"^[A-Za-z0-9_.-]{1,48}$")
REGISTER = re.compile(r"^e(?:ax|bx|cx|dx|si|di|bp|sp)$")

ENTRY_MARKER = re.compile(
    r"BNESNAPSHOT phase=entry entry=(?P<entry>0x[0-9a-fA-F]+) "
    r"hit=(?P<hit>\d+)"
)
RETURN_MARKER = re.compile(
    r"BNESNAPSHOTRET address=(?P<address>0x[0-9a-fA-F]+) "
    r"esp=(?P<esp>0x[0-9a-fA-F]+)"
)
REGISTER_MARKER = re.compile(
    r"BNESNAPSHOTREG phase=(?P<phase>entry|return) "
    r"name=(?P<name>[a-z]{2,7}) value=(?P<value>0x[0-9a-fA-F]+)"
)
MEMORY_MARKER = re.compile(
    r"BNESNAPSHOTMEM phase=(?P<phase>entry|return) "
    r"label=(?P<label>[A-Za-z0-9_.-]+) address=(?P<address>0x[0-9a-fA-F]+) "
    r"bytes=(?P<bytes>\d+)"
)
MEMORY_LINE = re.compile(
    r"^(?P<address>0x[0-9a-fA-F]+)(?:\s*<[^>]*>)?:\s*"
    r"(?P<bytes>(?:0x[0-9a-fA-F]{2}[\s]*)+)$"
)


class CaptureError(ValueError):
    """The capture cannot be believed.  Never downgraded to a warning."""


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
        temporary.replace(path)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _write_json(path: Path, value: object) -> None:
    _write_text(path, json.dumps(value, indent=2, sort_keys=True) + "\n")


# --------------------------------------------------------------------------
# The specification: what a person reviewed before the oracle ran
# --------------------------------------------------------------------------


def _region(record: object, index: int) -> dict[str, Any]:
    if not isinstance(record, dict):
        raise CaptureError("a capture region must be a JSON object")
    label = record.get("label")
    if not isinstance(label, str) or not SAFE_LABEL.fullmatch(label):
        raise CaptureError(f"capture region {index} needs a plain label")
    size = record.get("bytes")
    if not isinstance(size, int) or size <= 0 or size % 4 != 0:
        raise CaptureError(
            f"capture region {label!r} needs a positive multiple of four bytes")
    if size > MAXIMUM_REGION_BYTES:
        raise CaptureError(
            f"capture region {label!r} asks for {size} bytes, more than the "
            f"{MAXIMUM_REGION_BYTES} one region may carry")
    access = record.get("access", "rw")
    if access not in ("r", "rw", "rx"):
        raise CaptureError(f"capture region {label!r} has unsupported access")
    address, register = record.get("address"), record.get("register")
    offset = record.get("offset", 0)
    if not isinstance(offset, int):
        raise CaptureError(f"capture region {label!r} needs an integer offset")
    if isinstance(address, int):
        if register is not None:
            raise CaptureError(
                f"capture region {label!r} names both an address and a register")
        if address < 0 or address > MASK32:
            raise CaptureError(f"capture region {label!r} is not 32-bit")
    elif isinstance(register, str):
        if not REGISTER.fullmatch(register):
            raise CaptureError(
                f"capture region {label!r} names no 32-bit register")
    else:
        raise CaptureError(
            f"capture region {label!r} needs an address or a register to follow")
    return {"label": label, "bytes": size, "access": access,
            "address": address if isinstance(address, int) else None,
            "register": register if isinstance(register, str) else None,
            "offset": offset}


def load_specification(document: dict[str, Any]) -> dict[str, Any]:
    """Read and check a capture specification, refusing anything vague.

    The specification is the reviewed part.  Exploration later varies only what
    it names, and the capture reads only what it names, because a tool that
    decides for itself which memory matters is a tool that reports a decision
    is chaotic -- true, and useless.
    """
    if document.get("schema") != SCHEMA:
        raise CaptureError("unsupported snapshot capture schema")
    entry = document.get("entry")
    if not isinstance(entry, int):
        raise CaptureError("a capture needs an integer entry address")
    executable = document.get("executable_sha256", BNE_202_SHA256)
    native = executable == BNE_202_SHA256
    if native and not BNE_TEXT_START <= entry < BNE_TEXT_END:
        raise CaptureError(
            f"capture entry 0x{entry:08x} lies outside the pinned BNE text")
    hit = document.get("hit", 1)
    if not isinstance(hit, int) or hit < 1:
        raise CaptureError("a capture activation index starts at one")
    case = document.get("case")
    if not isinstance(case, str) or not SAFE_LABEL.fullmatch(case):
        raise CaptureError("a capture needs a filesystem-safe case name")
    regions = [_region(record, index)
               for index, record in enumerate(document.get("regions", []))]
    if not regions:
        raise CaptureError("a capture that reads no memory replays nothing")
    labels = [region["label"] for region in regions]
    if len(set(labels)) != len(labels):
        raise CaptureError("two capture regions share one label")
    total = sum(region["bytes"] for region in regions)
    if total > MAXIMUM_CAPTURE_BYTES:
        raise CaptureError(
            f"the capture asks for {total} bytes, more than the "
            f"{MAXIMUM_CAPTURE_BYTES} a bounded decision needs")
    if not any(region["access"] == "rx" for region in regions):
        raise CaptureError("a capture with no executable region has no code")
    if not any(region["register"] == "esp" for region in regions):
        raise CaptureError(
            "a capture with no stack region cannot replay a call or a return")
    focus = document.get("focus") or {}
    if focus:
        slot = focus.get("native_slot")
        register = focus.get("register")
        if not isinstance(slot, int) or slot < 0:
            raise CaptureError("a focus guard needs the native unit slot")
        if not isinstance(register, str) or not REGISTER.fullmatch(register):
            raise CaptureError("a focus guard needs the register holding the unit")
    ret = document.get("return") or {"mode": "finish"}
    mode = ret.get("mode")
    if mode == "address":
        if not isinstance(ret.get("address"), int):
            raise CaptureError("an address return needs the caller address")
    elif mode != "finish":
        raise CaptureError(f"unsupported capture return mode: {mode!r}")
    for stub in document.get("stubs", []) or []:
        if not isinstance(stub, dict) or not isinstance(stub.get("address"), int):
            raise CaptureError("a stub needs the address it answers for")
        if not isinstance(stub.get("name"), str):
            raise CaptureError("an unnamed stub is an unexplained answer")
        covers = stub.get("covers")
        if covers is not None and not (
                isinstance(covers, dict) and isinstance(covers.get("start"), int)
                and isinstance(covers.get("end"), int)
                and covers["start"] < covers["end"]):
            raise CaptureError(f"stub {stub['name']!r} covers no address range")
    return {
        "schema": SCHEMA,
        "case": case,
        "cycle": document.get("cycle"),
        "entry": entry,
        "hit": hit,
        "focus": dict(focus) if focus else {},
        "regions": regions,
        "return": {"mode": mode, "address": ret.get("address")},
        "inputs": list(document.get("inputs", []) or []),
        "stubs": list(document.get("stubs", []) or []),
        "executable_sha256": executable,
        "instruction_limit": min(
            int(document.get("instruction_limit", MAXIMUM_INSTRUCTIONS)),
            MAXIMUM_INSTRUCTIONS),
        "provenance": dict(document.get("provenance", {}) or {}),
    }


def specification_from_branch_witness(artifact: dict[str, Any], *,
        case: str | None = None, code_bytes: int = 4096,
        stack_below: int = 512, stack_above: int = 2048,
        unit_bytes: int = UNIT_BYTES) -> dict[str, Any]:
    """Draft a capture specification from a completed Branch Witness run.

    A draft, not a specification: the reviewed inputs are left empty on
    purpose.  What a register holds is the one thing this evidence cannot say,
    and a capture that guesses at it produces a rule about a number nobody has
    named.
    """
    branch = artifact.get("top_branch") or {}
    focus = artifact.get("focus") or {}
    entry = branch.get("address")
    if not isinstance(entry, int):
        raise CaptureError(
            "this Branch Witness run localizes no branch, so there is nothing "
            "to capture at")
    slot = focus.get("native_slot")
    start = max(BNE_TEXT_START, entry - code_bytes // 2)
    regions = [
        {"label": "code", "access": "rx", "bytes": code_bytes,
         "address": start},
        {"label": "stack", "access": "rw", "bytes": stack_below + stack_above,
         "register": "esp", "offset": -stack_below},
    ]
    review = [
        "name the reviewed inputs: which register or address the decision "
        "turns on, and what each one is called",
        "widen the regions until a replay stops failing on an uncaptured "
        "read, rather than filling one with a zero",
        "name a stub for every call the capture cannot contain",
        f"check that the code region starts at an instruction boundary; "
        f"0x{start:08x} is half a window before the branch and nothing here "
        f"knows where the function begins",
    ]
    if isinstance(slot, int):
        # The unit pool moves between runs, so the record this decision is
        # about cannot be pinned to an address in a draft. Whoever reviews
        # this adds it as a region following the register that holds it.
        review.append(
            f"add the region holding native slot {slot}: {unit_bytes} bytes "
            f"followed from whichever register carries the unit record")
    return {
        "schema": SCHEMA,
        "case": case or artifact.get("case"),
        "cycle": artifact.get("cycle"),
        "entry": entry,
        "hit": 1,
        "focus": {},
        "regions": regions,
        "return": {"mode": "finish"},
        "inputs": [],
        "stubs": [],
        "executable_sha256": BNE_202_SHA256,
        "review_required": review,
    }


# --------------------------------------------------------------------------
# The GDB side: one activation, printed in a format nobody has to guess at
# --------------------------------------------------------------------------


def _safe_shell_path(path: Path) -> str:
    value = str(path)
    if any(character in value for character in "\n\r\0"):
        raise CaptureError("capture marker path contains control characters")
    return shlex.quote(value)


def _region_expression(region: dict[str, Any]) -> str:
    if region["address"] is not None:
        return f"(unsigned int)0x{region['address']:08x}"
    sign = "+" if region["offset"] >= 0 else "-"
    return (f"(unsigned int)$" + region["register"]
            + f" {sign} {abs(region['offset'])}")


def _dump_commands(regions: Sequence[dict[str, Any]], phase: str) -> list[str]:
    commands = []
    for index, region in enumerate(regions):
        variable = f"$bne_region{index}"
        commands.append(
            f"printf \"BNESNAPSHOTMEM phase={phase} label={region['label']} "
            f"address=0x%08x bytes={region['bytes']}\\n\", {variable}")
        commands.append(f"x/{region['bytes']}xb {variable}")
    return commands


def _register_commands(phase: str) -> list[str]:
    commands = []
    for name in GENERAL_REGISTERS:
        commands.append(
            f"printf \"BNESNAPSHOTREG phase={phase} name={name} "
            f"value=0x%08x\\n\", (unsigned int)${name}")
    commands.append(
        f"printf \"BNESNAPSHOTREG phase={phase} name=eip value=0x%08x\\n\", "
        "(unsigned int)$pc")
    commands.append(
        f"printf \"BNESNAPSHOTREG phase={phase} name=eflags value=0x%08x\\n\", "
        "(unsigned int)$eflags")
    return commands


def gdb_commands(specification: dict[str, Any], *, history_log: Path,
        resume_marker: Path) -> str:
    """Return a batch-safe GDB command file for an already paused process.

    The oracle pauses before the tick the divergence is in and this attaches to
    it, exactly as the branch recorder does.  What is new is that the machine
    is printed twice -- once at the decision and once at its return -- so what
    the invocation did can be checked against what a replay does, rather than
    trusted.
    """
    specification = load_specification(specification)
    regions = specification["regions"]
    focus = specification["focus"]
    entry = specification["entry"]
    commands = [
        "set pagination off",
        "set confirm off",
        "set print thread-events off",
        f"set logging file {history_log}",
        "set logging overwrite on",
        "set logging redirect on",
        "set logging enabled on",
    ]
    if focus:
        commands.extend([
            f"set $bne_pool = *(unsigned int*)0x{UNIT_POOL_POINTER:08x}",
            f"set $bne_focus = $bne_pool + {focus['native_slot']} * {UNIT_BYTES}",
            f"break *0x{entry:08x} if (unsigned int)${focus['register']} "
            "== (unsigned int)$bne_focus",
        ])
    else:
        commands.append(f"break *0x{entry:08x}")
    if specification["hit"] > 1:
        commands.append(f"ignore $bpnum {specification['hit'] - 1}")
    commands.extend([
        f"shell touch {_safe_shell_path(resume_marker)}",
        "continue",
        f"printf \"BNESNAPSHOT phase=entry entry=0x%08x hit={specification['hit']}"
        "\\n\", (unsigned int)$pc",
        "set $bne_entry_esp = (unsigned int)$esp",
    ])
    for index, region in enumerate(regions):
        commands.append(
            f"set $bne_region{index} = {_region_expression(region)}")
    commands.extend(_register_commands("entry"))
    commands.extend(_dump_commands(regions, "entry"))
    commands.extend([
        "set record btrace bts buffer-size 1048576",
        "record btrace bts",
    ])
    if specification["return"]["mode"] == "address":
        commands.extend([
            f"tbreak *0x{specification['return']['address']:08x} if "
            "(unsigned int)$esp > $bne_entry_esp",
            "continue",
        ])
    else:
        commands.append("finish")
    commands.append(
        "printf \"BNESNAPSHOTRET address=0x%08x esp=0x%08x\\n\", "
        "(unsigned int)$pc, (unsigned int)$esp")
    commands.extend(_register_commands("return"))
    commands.extend(_dump_commands(regions, "return"))
    commands.extend([
        f"set record instruction-history-size {specification['instruction_limit']}",
        "record instruction-history",
        "set logging enabled off",
        "record stop",
        "detach",
        "quit",
        "",
    ])
    return "\n".join(commands)


# --------------------------------------------------------------------------
# The import: a snapshot rebuilt from the log and nothing else
# --------------------------------------------------------------------------


def _parse_regions(text: str) -> dict[tuple[str, str], dict[str, Any]]:
    """Collect every dumped region, checking each came back whole.

    GDB prints what it can and says so when it cannot.  A region that came back
    short, or with a hole in the middle, is refused here rather than padded,
    because a padded byte is a number the game never held.
    """
    regions: dict[tuple[str, str], dict[str, Any]] = {}
    current: dict[str, Any] | None = None
    for line in text.splitlines():
        marker = MEMORY_MARKER.search(line)
        if marker is not None:
            key = (marker.group("phase"), marker.group("label"))
            if key in regions:
                raise CaptureError(
                    f"region {marker.group('label')!r} was dumped twice in the "
                    f"{marker.group('phase')} phase")
            current = {
                "phase": marker.group("phase"), "label": marker.group("label"),
                "address": int(marker.group("address"), 16),
                "bytes": int(marker.group("bytes")), "data": bytearray(),
                "next": int(marker.group("address"), 16),
            }
            regions[key] = current
            continue
        if current is None:
            continue
        body = MEMORY_LINE.match(line.strip())
        if body is None:
            if "Cannot access memory" in line:
                raise CaptureError(
                    f"region {current['label']!r} could not be read at "
                    f"0x{current['next']:08x}")
            if line.strip():
                current = None
            continue
        address = int(body.group("address"), 16)
        if address != current["next"]:
            raise CaptureError(
                f"region {current['label']!r} has a hole: expected "
                f"0x{current['next']:08x}, read 0x{address:08x}")
        values = [int(item, 16) for item in body.group("bytes").split()]
        current["data"].extend(values)
        current["next"] = address + len(values)
    for region in regions.values():
        if len(region["data"]) != region["bytes"]:
            raise CaptureError(
                f"region {region['label']!r} came back with "
                f"{len(region['data'])} of {region['bytes']} bytes")
        region["data"] = bytes(region["data"])
    return regions


def _parse_registers(text: str) -> dict[str, dict[str, int]]:
    phases: dict[str, dict[str, int]] = {"entry": {}, "return": {}}
    for match in REGISTER_MARKER.finditer(text):
        phases[match.group("phase")][match.group("name")] = \
            int(match.group("value"), 16)
    for phase, values in phases.items():
        missing = [name for name in SNAPSHOT_REGISTERS if name not in values]
        if missing:
            raise CaptureError(
                f"the {phase} machine is missing registers: "
                + ", ".join(missing))
    return phases


def _merge_regions(regions: Sequence[dict[str, Any]]) -> list[dict[str, Any]]:
    """Turn the dumped regions into non-overlapping mappings.

    Two regions may name the same bytes -- a stack window and a structure the
    stack points into overlap often enough -- and the loader refuses
    overlapping mappings, because which bytes the function reads would then
    depend on load order.  Same-access neighbours are merged; a code range and
    a data range that overlap are a specification fault and are named.

    Both phases are merged together, so the before and the after of one
    mapping always describe the same addresses and their difference is the
    write set of the invocation.
    """
    ordered = sorted(regions, key=lambda item: (item["address"],
                                                len(item["entry"])))
    merged: list[dict[str, Any]] = []
    for region in ordered:
        if not merged:
            merged.append(dict(region))
            continue
        previous = merged[-1]
        end = previous["address"] + len(previous["entry"])
        if region["address"] > end:
            merged.append(dict(region))
            continue
        if region["access"] != previous["access"]:
            if region["address"] == end:
                merged.append(dict(region))
                continue
            raise CaptureError(
                f"regions {previous['label']!r} and {region['label']!r} "
                f"overlap and disagree about whether the bytes are code")
        overlap = min(end - region["address"], len(region["entry"]))
        if overlap > 0:
            at = region["address"] - previous["address"]
            for phase in ("entry", "return"):
                if previous[phase][at:at + overlap] != region[phase][:overlap]:
                    raise CaptureError(
                        f"regions {previous['label']!r} and "
                        f"{region['label']!r} disagree about the bytes they "
                        f"share, so one of the two dumps is not of this "
                        f"machine")
        if len(region["entry"]) > overlap:
            for phase in ("entry", "return"):
                previous[phase] = previous[phase] + region[phase][overlap:]
            previous["label"] = f"{previous['label']}+{region['label']}"
    return merged


def _coalesce(before: bytes, after: bytes, base: int) -> list[dict[str, Any]]:
    changes: list[dict[str, Any]] = []
    index = 0
    while index < len(before):
        if before[index] == after[index]:
            index += 1
            continue
        start = index
        while index < len(before) and before[index] != after[index]:
            index += 1
        changes.append({
            "address": base + start,
            "before_hex": before[start:index].hex(),
            "after_hex": after[start:index].hex(),
        })
    return changes


def _stub_ranges(specification: dict[str, Any]) -> list[tuple[int, int]]:
    ranges = []
    for stub in specification["stubs"]:
        covers = stub.get("covers")
        if isinstance(covers, dict):
            ranges.append((int(covers["start"]), int(covers["end"])))
    return ranges


def _in_ranges(address: int, ranges: Iterable[tuple[int, int]]) -> bool:
    return any(start <= address < end for start, end in ranges)


def snapshot_from_gdb_log(specification: dict[str, Any], text: str) \
        -> dict[str, Any]:
    """Rebuild one replayable invocation from a capture log and nothing else.

    Deterministic on purpose: the same log always produces the same snapshot,
    so a capture can be re-imported and argued with long after the oracle that
    produced it has moved on.
    """
    specification = load_specification(specification)
    marker = ENTRY_MARKER.search(text)
    if marker is None:
        raise CaptureError(
            "the capture log has no entry marker, so the breakpoint never hit "
            "with the focus the specification asked for")
    entry = int(marker.group("entry"), 16)
    if entry != specification["entry"]:
        raise CaptureError(
            f"the capture stopped at 0x{entry:08x}, not the specified "
            f"0x{specification['entry']:08x}")
    if int(marker.group("hit")) != specification["hit"]:
        raise CaptureError("the capture recorded a different activation index")
    returned = RETURN_MARKER.search(text)
    if returned is None:
        raise CaptureError(
            "the capture log has no return marker, so the invocation was "
            "never seen to finish and there is no outcome to check against")
    return_address = int(returned.group("address"), 16)
    return_esp = int(returned.group("esp"), 16)
    registers = _parse_registers(text)
    dumped = _parse_regions(text)

    regions: list[dict[str, Any]] = []
    for region in specification["regions"]:
        label = region["label"]
        first = dumped.get(("entry", label))
        second = dumped.get(("return", label))
        if first is None or second is None:
            raise CaptureError(f"region {label!r} was not dumped in both phases")
        if first["address"] != second["address"] \
                or first["bytes"] != second["bytes"]:
            raise CaptureError(
                f"region {label!r} moved between the two dumps, so its before "
                f"and after cannot be compared")
        if first["bytes"] != region["bytes"]:
            raise CaptureError(
                f"region {label!r} was dumped at {first['bytes']} bytes and "
                f"specified at {region['bytes']}")
        regions.append({"label": label, "address": first["address"],
                        "access": region["access"], "entry": first["data"],
                        "return": second["data"]})

    mappings = _merge_regions(regions)

    def _mapping_for(address: int, width: int) -> dict[str, Any] | None:
        for mapping in mappings:
            if mapping["address"] <= address \
                    and address + width <= mapping["address"] \
                    + len(mapping["entry"]):
                return mapping
        return None

    if _mapping_for(entry, 1) is None:
        raise CaptureError("no captured region contains the entry instruction")
    stack = registers["entry"]["esp"]
    if _mapping_for(stack, 4) is None:
        raise CaptureError("no captured region contains the stack pointer")

    # The return address is replaced by the replay sentinel, so a return lands
    # somewhere recognisable instead of running on into the caller.  This is
    # the one thing a capture rewrites, and it is written down twice: here and
    # in the snapshot's provenance.
    slot = (return_esp - 4) & MASK32
    holder = _mapping_for(slot, 4)
    if holder is None:
        raise CaptureError(
            f"the return address slot 0x{slot:08x} lies outside every captured "
            f"region, so the invocation cannot be stopped where it returned")
    offset = slot - holder["address"]
    held = int.from_bytes(holder["entry"][offset:offset + 4], "little")
    if held != return_address:
        raise CaptureError(
            f"the stack slot below the returning ESP holds 0x{held:08x}, not "
            f"the return address 0x{return_address:08x}")
    if holder["return"][offset:offset + 4] != holder["entry"][offset:offset + 4]:
        raise CaptureError(
            "the invocation wrote over its own return address, which the "
            "sentinel substitution would hide")
    deltas: list[dict[str, Any]] = []
    for mapping in mappings:
        deltas.extend(_coalesce(mapping["entry"], mapping["return"],
                                mapping["address"]))
    patched = bytearray(holder["entry"])
    patched[offset:offset + 4] = RETURN_SENTINEL.to_bytes(4, "little")
    holder["entry"] = bytes(patched)

    instructions = parse_instruction_history(text)
    if not instructions:
        raise CaptureError("the capture log has no instruction history")
    start = next((index for index, item in enumerate(instructions)
                  if item["address"] == entry), None)
    if start is None:
        raise CaptureError(
            "the recorded instruction history never reaches the entry "
            "address, so it is not a history of this invocation")
    window = instructions[start:]
    # GDB stops at the return address, and whether the caller's first
    # instruction is in the history is GDB's business rather than this
    # invocation's. The window ends where the invocation did.
    ended = next((index for index, item in enumerate(window)
                  if index > 0 and item["address"] == return_address), None)
    if ended is not None:
        window = window[:ended]
    if not window:
        raise CaptureError(
            "the recorded history holds no instruction of this invocation")
    if len(window) > specification["instruction_limit"]:
        raise CaptureError(
            f"the invocation executed {len(window)} instructions, more than "
            f"the {specification['instruction_limit']} a bounded decision may")
    stubs = _stub_ranges(specification)

    def _executable_at(address: int) -> bool:
        mapping = _mapping_for(address, 1)
        return mapping is not None and "x" in mapping["access"]

    uncovered = sorted({item["address"] for item in window
                        if not _executable_at(item["address"])
                        and not _in_ranges(item["address"], stubs)})
    if uncovered:
        raise CaptureError(
            "the invocation executed code no region captured, so a replay "
            "would be running instructions nobody authenticated: "
            + ", ".join(f"0x{address:08x}" for address in uncovered[:8])
            + (f" and {len(uncovered) - 8} more" if len(uncovered) > 8 else ""))

    branches = []
    for index, instruction in enumerate(window[:-1]):
        assembly = _assembly(instruction["instruction"])
        target = _branch_target(assembly)
        if target is None or _in_ranges(instruction["address"], stubs):
            continue
        branches.append({
            "address": instruction["address"],
            "taken": window[index + 1]["address"] == target,
        })

    snapshot = Snapshot(
        entry=entry,
        registers={name: registers["entry"][name] for name in SNAPSHOT_REGISTERS},
        segments=tuple(Segment(address=mapping["address"],
                               data=mapping["entry"],
                               access=mapping["access"], label=mapping["label"])
                       for mapping in mappings),
        inputs=(),
        executable_sha256=specification["executable_sha256"],
        return_sentinel=RETURN_SENTINEL,
        expected={
            # The eight general registers, the branch path and the memory the
            # invocation changed are what a capture can prove.  EIP is the
            # sentinel by construction once the return address is replaced, and
            # EFLAGS carries bits an emulator outside an operating system does
            # not model, so both are recorded beside the outcome rather than
            # inside the gate that must reproduce.
            "registers": {name: registers["return"][name]
                          for name in GENERAL_REGISTERS},
            "branches": branches,
            "memory_delta": deltas,
            "observed": {
                "eip": registers["return"]["eip"],
                "eflags_entry": registers["entry"]["eflags"],
                "eflags_return": registers["return"]["eflags"],
                "instructions": len(window),
            },
        },
        stubs=tuple(specification["stubs"]),
        provenance={
            "kind": "native-gdb-capture",
            "case": specification["case"],
            "cycle": specification["cycle"],
            "entry": entry,
            "hit": specification["hit"],
            "focus": specification["focus"],
            "return_address": return_address,
            "return_esp": return_esp,
            "return_slot": slot,
            "return_address_replaced_by_sentinel": True,
            "specification_sha256": canonical_digest(specification),
            **specification["provenance"],
        },
    )
    document = snapshot_document(snapshot)
    document["inputs"] = list(specification["inputs"])
    # The reviewed inputs come from the specification rather than the log, so
    # they are the one part of the document the import did not derive. Run the
    # whole thing back through the loader that the replay will use, so an
    # input naming an address nothing captured is refused here.
    load_snapshot(document, expected_executable=None)
    return document


# --------------------------------------------------------------------------
# Sealing
# --------------------------------------------------------------------------


def seal_capture(specification: dict[str, Any], history_log: Path,
        output_root: Path, *, executable: Path | None = None,
        oracle_run_manifest: Path | None = None,
        gdb_version: str = "unknown", network_disabled: bool = True,
        expect_pinned_executable: bool = True) -> tuple[Path, Path]:
    """Import one capture log and write the snapshot it authenticates.

    Nothing is sealed that does not load.  The last thing this does before
    writing the manifest is read the snapshot back through the micro-oracle's
    own fail-closed loader, so a capture that produced something unloadable
    fails here rather than three commands later.
    """
    specification = load_specification(specification)
    history_log = history_log.expanduser().resolve()
    output_root = output_root.expanduser().resolve()
    if not network_disabled:
        raise CaptureError("snapshot capture requires a network-disabled runtime")
    if expect_pinned_executable:
        if executable is None:
            raise CaptureError(
                "sealing a native snapshot needs the executable it came from")
        identity = file_identity(executable)
        if identity["sha256"] != specification["executable_sha256"]:
            raise CaptureError(
                "snapshot sealing refuses an executable that is not the one "
                "the specification pinned")
    if oracle_run_manifest is not None:
        run = json.loads(oracle_run_manifest.read_text(encoding="utf-8"))
        if run.get("oracle", {}).get("executable", {}).get("sha256") \
                != specification["executable_sha256"]:
            raise CaptureError("oracle run manifest does not pin the executable")
        if run.get("runtime", {}).get("network_disabled") is not True:
            raise CaptureError("oracle run manifest lacks the offline run")
    document = snapshot_from_gdb_log(
        specification, history_log.read_text(encoding="utf-8", errors="replace"))
    output_root.mkdir(parents=True, exist_ok=True)
    blobs = output_root / "blobs"
    snapshot = load_snapshot(
        document, blob_root=blobs,
        expected_executable=specification["executable_sha256"]
        if expect_pinned_executable else None)
    document = snapshot_document(snapshot, blob_root=blobs)
    document["inputs"] = list(specification["inputs"])
    snapshot_path = output_root / "snapshot.json"
    _write_json(snapshot_path, document)
    # Read it back the way the micro-oracle will, from the file, so a blob
    # that did not reach the disk is found now and not by a replay.
    load_snapshot(json.loads(snapshot_path.read_text(encoding="utf-8")),
                  blob_root=blobs,
                  expected_executable=specification["executable_sha256"]
                  if expect_pinned_executable else None)
    specification_path = output_root / "specification.json"
    _write_json(specification_path, specification)
    log_copy = output_root / "capture-log.txt"
    _write_text(log_copy, history_log.read_text(encoding="utf-8",
                                                errors="replace"))
    artifacts = [snapshot_path, specification_path, log_copy]
    if blobs.is_dir():
        artifacts.append(blobs)
    manifest = {
        "schema": SCHEMA,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "case": specification["case"],
        "cycle": specification["cycle"],
        "entry": specification["entry"],
        "specification_sha256": canonical_digest(specification),
        "snapshot_sha256": canonical_digest(document),
        "capture": {
            "gdb_version": gdb_version,
            "network_disabled": True,
            "importer": {"path": str(Path(__file__).resolve()),
                         **file_identity(Path(__file__).resolve())},
            "log": {"path": str(history_log), **file_identity(history_log)},
        },
        "executable": {"sha256": specification["executable_sha256"]},
        "outcome": {
            "branches": len(document["expected"]["branches"]),
            "changed_regions": len(document["expected"]["memory_delta"]),
            "instructions": document["expected"]["observed"]["instructions"],
        },
        "artifacts": inventory_files(output_root, artifacts),
    }
    manifest_path = output_root / "manifest.json"
    _write_json(manifest_path, manifest)
    return snapshot_path, manifest_path


if __name__ == "__main__":
    print(__doc__)
