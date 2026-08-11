#!/usr/bin/env python3
"""Fail closed if the player runtime regains an executable script dependency."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path


SCRIPT_LANGUAGE = "l" + "ua"
SCRIPT_EXTENSION = "." + SCRIPT_LANGUAGE
FORBIDDEN_JAVA = (
    f"net.chonkbase.chonkcraft.{SCRIPT_LANGUAGE}",
    f"import net.chonkbase.chonkcraft.{SCRIPT_LANGUAGE}",
    "new Interpreter(",
    "DefinitionTable",
    "DefinitionFunction",
    "Coerce.",
)
PLAYER_PATHS = (
    "engine/src/main",
    "desktop/src/main",
    "launcher/src/main",
    "data/src/main",
    "runtime/src/main",
    "assetpack/src/main",
    "extractor/src/main",
)
LAUNCH_FILES = (
    "scripts/run-game.sh",
    "scripts/run-launcher.sh",
    "scripts/release",
    "launcher/src/main",
    "desktop/src/main",
)


def files(root: Path, relative: str, suffix: str | None = None):
    base = root / relative
    if base.is_file():
        yield base
    elif base.is_dir():
        for path in sorted(base.rglob("*")):
            if path.is_file() and (suffix is None or path.suffix == suffix):
                yield path


def executable_java(text: str) -> str:
    """Discard comments so historical provenance remains legal evidence."""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//.*", "", text)


def inspect_jar(path: Path, problems: list[str]) -> dict:
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
    forbidden = [name for name in names if name.lower().endswith((SCRIPT_EXTENSION, ".sms"))]
    classes = [name for name in names if f"/{SCRIPT_LANGUAGE}/" in name.lower()
               or name.endswith(("Interpreter.class", "DefinitionTable.class", "DefinitionFunction.class"))]
    if forbidden:
        problems.append(f"{path}: runtime script entries: {forbidden[:10]}")
    if classes:
        problems.append(f"{path}: interpreter classes: {classes[:10]}")
    return {"path": str(path), "entries": len(names), "forbidden": forbidden,
            "interpreter_classes": classes}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--artifact", action="append", type=Path, default=[])
    parser.add_argument("--receipt", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    problems: list[str] = []

    pom = (root / "pom.xml").read_text()
    if f"<module>{SCRIPT_LANGUAGE}</module>" in pom or f"chonkcraft-{SCRIPT_LANGUAGE}" in pom:
        problems.append("pom.xml: retired interpreter module remains in the Maven reactor")
    if any((root / SCRIPT_LANGUAGE).glob("src/**/*.java")):
        problems.append("retired interpreter source files still exist")

    production_sources = 0
    for relative in PLAYER_PATHS:
        for path in files(root, relative, ".java"):
            production_sources += 1
            code = executable_java(path.read_text(errors="replace"))
            for marker in FORBIDDEN_JAVA:
                if marker in code:
                    problems.append(f"{path.relative_to(root)}: executable reference {marker!r}")
            if re.search(
                    rf'(readString|newInputStream|resolve)\s*\([^\n]*(?:{re.escape(SCRIPT_EXTENSION)}|\.sms)',
                    code):
                problems.append(f"{path.relative_to(root)}: reads a runtime script path")

    for relative in LAUNCH_FILES:
        for path in files(root, relative):
            if path.suffix not in {".java", ".sh", ".py", ".xml"}:
                continue
            text = executable_java(path.read_text(errors="replace")) if path.suffix == ".java" \
                else path.read_text(errors="replace")
            for marker in ("chonkcraft-content.zip", "bootstrap-content.zip"):
                if marker in text:
                    problems.append(f"{path.relative_to(root)}: launcher/release reference {marker}")

    resources = []
    for relative in PLAYER_PATHS:
        for path in files(root, relative):
            if path.suffix.lower() in {SCRIPT_EXTENSION, ".sms"}:
                resources.append(str(path.relative_to(root)))
    if resources:
        problems.append(f"runtime resources contain scripts: {resources[:10]}")

    artifacts = []
    for artifact in args.artifact:
        if not artifact.is_file():
            problems.append(f"missing runtime artifact: {artifact}")
        else:
            try:
                artifacts.append(inspect_jar(artifact, problems))
            except zipfile.BadZipFile:
                problems.append(f"{artifact}: not a readable JAR/ZIP")

    status = "PASS" if not problems else "FAIL"
    receipt = {
        "schema": "chonkcraft-native-runtime-v2",
        "status": status,
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "git_commit": subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=root, text=True,
            capture_output=True, check=True).stdout.strip(),
        "dirty": bool(subprocess.run(
            ["git", "status", "--porcelain"], cwd=root, text=True,
            capture_output=True, check=True).stdout),
        "production_java_files": production_sources,
        "runtime_script_resources": resources,
        "artifacts": artifacts,
        "problems": problems,
    }
    if args.receipt:
        args.receipt.parent.mkdir(parents=True, exist_ok=True)
        args.receipt.write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps(receipt, indent=2))
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main())
