#!/usr/bin/env python3
"""Authenticate the spell evidence against the pinned retail PE image."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import struct


IMAGE_BASE = 0x00400000


class PeImage:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.data = path.read_bytes()
        pe = struct.unpack_from("<I", self.data, 0x3C)[0]
        if self.data[pe:pe + 4] != b"PE\0\0":
            raise ValueError(f"not a PE image: {path}")
        section_count = struct.unpack_from("<H", self.data, pe + 6)[0]
        optional_size = struct.unpack_from("<H", self.data, pe + 20)[0]
        section_table = pe + 24 + optional_size
        self.sections: list[tuple[int, int, int, int]] = []
        for index in range(section_count):
            offset = section_table + index * 40
            virtual_size, virtual_address, raw_size, raw_pointer = \
                struct.unpack_from("<IIII", self.data, offset + 8)
            self.sections.append(
                (virtual_address, max(virtual_size, raw_size), raw_pointer, raw_size)
            )

    def read(self, virtual_address: int, size: int) -> bytes:
        rva = virtual_address - IMAGE_BASE
        for section_rva, extent, raw_pointer, raw_size in self.sections:
            if section_rva <= rva and rva + size <= section_rva + extent:
                within = rva - section_rva
                if within + size > raw_size:
                    raise ValueError(f"virtual-only PE bytes requested at {virtual_address:#x}")
                return self.data[raw_pointer + within:raw_pointer + within + size]
        raise ValueError(f"unmapped PE address {virtual_address:#x}")


def integer(value: int | str) -> int:
    return value if isinstance(value, int) else int(value, 0)


def direct_calls(code: bytes, start: int) -> list[tuple[int, int]]:
    """Return (instruction offset, target) for x86 E8 rel32 calls.

    The evidence also authenticates the complete slice hash, so this small
    decoder is deliberately restricted to the one instruction needed by the
    semantic assertions rather than pretending to be a general disassembler.
    """
    calls = []
    for offset in range(len(code) - 4):
        if code[offset] != 0xE8:
            continue
        displacement = struct.unpack_from("<i", code, offset + 1)[0]
        calls.append((offset, start + offset + 5 + displacement))
    return calls


def verify(executable: Path, evidence_path: Path) -> None:
    evidence = json.loads(evidence_path.read_text())
    if evidence.get("schema") != 1:
        raise ValueError("spell evidence must be schema 1")
    image = PeImage(executable)
    digest = hashlib.sha256(image.data).hexdigest()
    expected_digest = evidence["retail_executable"]["sha256"]
    if digest != expected_digest:
        raise ValueError(f"retail executable hash mismatch: {digest}")

    table = evidence["dispatch_table"]
    entries = table["entries"]
    first = table["first_order"]
    if [entry["order"] for entry in entries] != list(range(first, first + len(entries))):
        raise ValueError("spell dispatch orders are not contiguous")
    actual_handlers = struct.unpack(
        f"<{len(entries)}I",
        image.read(integer(table["virtual_address"]), len(entries) * 4),
    )
    expected_handlers = tuple(integer(entry["handler"]) for entry in entries)
    if actual_handlers != expected_handlers:
        raise ValueError("spell dispatch table differs from authenticated evidence")

    for item in evidence["code_slices"]:
        start = integer(item["start"])
        code = image.read(start, item["size"])
        actual_digest = hashlib.sha256(code).hexdigest()
        if actual_digest != item["sha256"]:
            raise ValueError(f"{item['name']} code slice hash mismatch")
        calls = direct_calls(code, start)
        for required in item.get("required_calls", []):
            target = integer(required["target"])
            matched = [offset for offset, destination in calls if destination == target]
            if len(matched) != required["count"]:
                raise ValueError(
                    f"{item['name']} calls {target:#010x} {len(matched)} times, "
                    f"expected {required['count']}"
                )
            if "pushed_argument" in required:
                argument = required["pushed_argument"]
                # These handlers push the field count, then ESI, then call.
                if any(offset < 3 or code[offset - 3:offset - 1] != bytes((0x6A, argument))
                       for offset in matched):
                    raise ValueError(
                        f"{item['name']} does not push {argument} for every {target:#x} call"
                    )

    if not evidence.get("established_semantics"):
        raise ValueError("spell evidence contains no semantic conclusions")
    print(
        f"authenticated {len(entries)} retail spell dispatch entries, "
        f"{len(evidence['code_slices'])} code slices: {digest[:12]}"
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--exe", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    try:
        verify(args.exe.expanduser().resolve(), args.evidence.expanduser().resolve())
    except (OSError, ValueError, KeyError, TypeError, struct.error) as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
