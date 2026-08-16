#!/usr/bin/env python3
"""Produce an authenticated, headless static slice of the pinned BNE binary.

The parity lab used to make an agent open an interactive reverse-engineering
session after every new writer PC.  This adapter deliberately has a small,
stable JSON surface: objdump is available on the Mac and on ``i9beef`` today;
Ghidra may be supplied for richer function boundaries without becoming a
prerequisite.  Both backends emit the same instruction/call/branch inventory.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from typing import Any, Sequence

from bne_branch_witness import BNE_202_SHA256, BNE_TEXT_END, BNE_TEXT_START


SCHEMA = "chonkcraft-bne-static-slice-1"
INSTRUCTION = re.compile(
    r"^\s*(?P<address>[0-9a-fA-F]+):\s+"
    r"(?P<bytes>(?:[0-9a-fA-F]{2}(?:\s+|$))+)(?P<text>.*)$")
TARGET = re.compile(r"(?:^|[\s,*])0x(?P<target>[0-9a-fA-F]+)(?:\s|$|<)")


class StaticAnalysisError(ValueError):
    pass


def file_identity(path: Path) -> dict[str, Any]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            size += len(block)
            digest.update(block)
    return {"path": str(path.resolve()), "bytes": size,
            "sha256": digest.hexdigest()}


def analyzer_identity(*, backend: str = "auto", objdump: str = "objdump",
                      analyze_headless: str = "analyzeHeadless") \
        -> dict[str, Any]:
    """Resolve and identify the analyzer before it enters a cache key.

    ``auto`` used to make the same content-addressed request mean objdump on
    one host and Ghidra on another.  The chosen executable and its version are
    evidence inputs, not ambient implementation details.
    """
    if backend == "auto":
        backend = "ghidra" if shutil.which(analyze_headless) else "objdump"
    requested = analyze_headless if backend == "ghidra" else objdump
    resolved = shutil.which(requested)
    if resolved is None:
        candidate = Path(requested).expanduser()
        if not candidate.is_file():
            raise StaticAnalysisError(
                f"{backend} analyzer is unavailable: {requested}")
        resolved = str(candidate.resolve())
    executable = Path(resolved).resolve()
    if not executable.is_file() or executable.is_symlink():
        raise StaticAnalysisError(f"unsafe analyzer executable: {executable}")
    version = subprocess.run(
        [str(executable), "--version"], check=False, capture_output=True,
        text=True, timeout=30,
    )
    exporter = Path(__file__).resolve().parent.parent / \
        "ghidra_scripts" / "ExportFunctionSlice.java"
    result: dict[str, Any] = {
        "backend": backend,
        "executable": file_identity(executable),
        "version_returncode": version.returncode,
        "version": (version.stdout + version.stderr).strip(),
        "python": {
            "version": sys.version,
            "executable": file_identity(Path(sys.executable).resolve()),
        },
    }
    if backend == "ghidra":
        if not exporter.is_file() or exporter.is_symlink():
            raise StaticAnalysisError(
                f"Ghidra exporter is missing or unsafe: {exporter}")
        result["exporter"] = file_identity(exporter)
    return result


def _classify(mnemonic: str) -> str:
    lower = mnemonic.lower()
    if lower.startswith("call"):
        return "call"
    if lower.startswith("ret"):
        return "return"
    if lower.startswith("j") or lower.startswith("loop"):
        return "branch"
    return "instruction"


def parse_objdump(text: str) -> list[dict[str, Any]]:
    instructions = []
    for line in text.splitlines():
        match = INSTRUCTION.match(line)
        if match is None:
            continue
        rendered = match.group("text").strip()
        if not rendered:
            continue
        parts = rendered.split(None, 1)
        mnemonic = parts[0]
        operands = parts[1] if len(parts) == 2 else ""
        target = TARGET.search(operands)
        instructions.append({
            "address": int(match.group("address"), 16),
            "bytes_hex": "".join(match.group("bytes").split()).lower(),
            "mnemonic": mnemonic,
            "operands": operands,
            "kind": _classify(mnemonic),
            "target": int(target.group("target"), 16) if target else None,
        })
    if not instructions:
        raise StaticAnalysisError("objdump produced no parseable instructions")
    return instructions


def parse_ghidra_tsv(text: str) -> list[dict[str, Any]]:
    instructions = []
    for number, line in enumerate(text.splitlines(), 1):
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) != 5:
            raise StaticAnalysisError(
                f"Ghidra export line {number} has {len(fields)} fields")
        address, encoded, mnemonic, operands, target = fields
        instructions.append({
            "address": int(address, 16), "bytes_hex": encoded.lower(),
            "mnemonic": mnemonic, "operands": operands,
            "kind": _classify(mnemonic),
            "target": None if target == "-" else int(target, 16),
        })
    if not instructions:
        raise StaticAnalysisError("Ghidra produced no parseable instructions")
    return instructions


def _report(executable: Path, address: int, span: int, analyzer: dict[str, Any],
            instructions: list[dict[str, Any]], command: Sequence[str]) \
        -> dict[str, Any]:
    contained = [item for item in instructions
                 if address <= item["address"] < address + span]
    if not contained or contained[0]["address"] != address:
        raise StaticAnalysisError(
            f"static slice does not begin at requested PC 0x{address:08x}")
    return {
        "schema": SCHEMA,
        "backend": analyzer["backend"],
        "analyzer": analyzer,
        "executable": file_identity(executable),
        "requested": {"address": address, "span": span,
                      "stop_address": address + span},
        "instructions": contained,
        "calls": [item for item in contained if item["kind"] == "call"],
        "branches": [item for item in contained if item["kind"] == "branch"],
        "returns": [item for item in contained if item["kind"] == "return"],
        "command": list(command),
    }


def analyze_objdump(executable: Path, address: int, *, span: int = 1024,
                    objdump: str = "objdump",
                    analyzer: dict[str, Any] | None = None,
                    expected_sha256: str | None = BNE_202_SHA256) \
        -> dict[str, Any]:
    identity = file_identity(executable)
    if expected_sha256 is not None and identity["sha256"] != expected_sha256:
        raise StaticAnalysisError("static analysis executable is not pinned BNE 2.02")
    analyzer = analyzer or analyzer_identity(backend="objdump", objdump=objdump)
    if analyzer.get("backend") != "objdump":
        raise StaticAnalysisError("objdump analysis received another backend")
    tool = str(analyzer["executable"]["path"])
    command = [tool, "-d", f"--start-address=0x{address:08x}",
               f"--stop-address=0x{address + span:08x}", str(executable)]
    result = subprocess.run(command, check=False, capture_output=True, text=True)
    if result.returncode != 0:
        raise StaticAnalysisError(
            "objdump failed: " + (result.stderr or result.stdout).strip()[-2000:])
    return _report(executable, address, span, analyzer,
                   parse_objdump(result.stdout), command)


def analyze_ghidra(executable: Path, address: int, *, span: int = 1024,
                   analyze_headless: str = "analyzeHeadless",
                   analyzer: dict[str, Any] | None = None,
                   expected_sha256: str | None = BNE_202_SHA256) \
        -> dict[str, Any]:
    identity = file_identity(executable)
    if expected_sha256 is not None and identity["sha256"] != expected_sha256:
        raise StaticAnalysisError("static analysis executable is not pinned BNE 2.02")
    analyzer = analyzer or analyzer_identity(
        backend="ghidra", analyze_headless=analyze_headless)
    if analyzer.get("backend") != "ghidra":
        raise StaticAnalysisError("Ghidra analysis received another backend")
    script_root = Path(__file__).resolve().parent.parent / "ghidra_scripts"
    with tempfile.TemporaryDirectory(prefix="bne-ghidra-") as temporary:
        temporary_root = Path(temporary)
        exported = temporary_root / "slice.tsv"
        command = [str(analyzer["executable"]["path"]),
                   str(temporary_root), "bne-slice",
                   "-import", str(executable), "-scriptPath", str(script_root),
                   "-postScript", "ExportFunctionSlice.java",
                   f"0x{address:08x}", str(span), str(exported),
                   "-deleteProject"]
        result = subprocess.run(command, check=False, capture_output=True,
                                text=True, timeout=300)
        if result.returncode != 0 or not exported.is_file():
            raise StaticAnalysisError(
                "Ghidra headless failed: "
                + (result.stderr or result.stdout).strip()[-2000:])
        return _report(executable, address, span, analyzer,
                       parse_ghidra_tsv(exported.read_text(encoding="utf-8")),
                       command)


def analyze(executable: Path, address: int, *, span: int = 1024,
            backend: str = "auto", objdump: str = "objdump",
            analyze_headless: str = "analyzeHeadless",
            analyzer: dict[str, Any] | None = None,
            expected_sha256: str | None = BNE_202_SHA256) -> dict[str, Any]:
    if not BNE_TEXT_START <= address < BNE_TEXT_END:
        raise StaticAnalysisError(f"PC 0x{address:08x} is outside pinned BNE text")
    if span <= 0 or span > 64 * 1024:
        raise StaticAnalysisError("static slice span must be 1..65536 bytes")
    analyzer = analyzer or analyzer_identity(
        backend=backend, objdump=objdump, analyze_headless=analyze_headless)
    backend = str(analyzer.get("backend"))
    if backend == "objdump":
        return analyze_objdump(executable, address, span=span, objdump=objdump,
                               analyzer=analyzer,
                               expected_sha256=expected_sha256)
    if backend == "ghidra":
        return analyze_ghidra(
            executable, address, span=span, analyze_headless=analyze_headless,
            analyzer=analyzer,
            expected_sha256=expected_sha256)
    raise StaticAnalysisError(f"unsupported static-analysis backend: {backend}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("executable", type=Path)
    parser.add_argument("address", type=lambda value: int(value, 0))
    parser.add_argument("--span", type=int, default=1024)
    parser.add_argument("--backend", choices=("auto", "objdump", "ghidra"),
                        default="auto")
    parser.add_argument("--objdump", default="objdump")
    parser.add_argument("--analyze-headless", default="analyzeHeadless")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    report = analyze(args.executable, args.address, span=args.span,
                     backend=args.backend, objdump=args.objdump,
                     analyze_headless=args.analyze_headless)
    rendered = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    else:
        print(rendered, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
