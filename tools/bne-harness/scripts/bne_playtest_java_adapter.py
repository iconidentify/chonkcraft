#!/usr/bin/env python3
"""Java playtest adapter: every order goes through CommandApplier.

The explorer talks to this command rather than importing engine internals.
The Java main loads the authenticated campaign, pairs native slots to the
unique unit at the sealed first-frame square, issues through
CommandApplier wrapped by PlayerIntentJournal, and writes the explorer
result schema.
"""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import sys

import bne_identity
import bne_playtest_explorer as explorer


ROOT = Path(__file__).resolve().parents[3]
ADAPTER_CLASS = "net.chonkbase.chonkcraft.desktop.BnePlaytestAdapter"
DEFAULT_PACK = Path.home() / (
    ".chonkcraft/packs/warcraft-ii-battle-net-edition-usa.chonkpack")
MODULE_CLASSES = (
    "desktop/target/classes",
    "engine/target/classes",
    "data/target/classes",
    "assetpack/target/classes",
    "runtime/target/classes",
)
FAT_JAR = ROOT / "desktop/target/chonkcraft-desktop-0.1.0-SNAPSHOT-app.jar"
JBR = ROOT / "scripts/jbr/with-jbr-25.sh"


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def engine_build_sha256() -> str:
    identity = bne_identity.engine_input_identity(ROOT)
    digest = identity.get("engine_input_sha256")
    if not isinstance(digest, str) or len(digest) != 64:
        raise ValueError("Java adapter could not identify the engine build")
    return digest


def resolve_pack(explicit: Path | None) -> Path:
    if explicit is not None:
        path = explicit.expanduser().resolve()
    else:
        env = os.environ.get("CHONKCRAFT_ASSET_PACK")
        path = Path(env).expanduser().resolve() if env else DEFAULT_PACK
    if not path.is_file():
        raise ValueError(f"authenticated BNE pack is missing: {path}")
    return path


def classpath() -> str:
    class_dirs = [ROOT / relative for relative in MODULE_CLASSES]
    if all(path.is_dir() for path in class_dirs):
        return os.pathsep.join(str(path) for path in class_dirs)
    if FAT_JAR.is_file():
        return str(FAT_JAR)
    raise ValueError(
        "Java adapter has no compiled desktop/engine classes; "
        "run scripts/jbr/with-jbr-25.sh mvn -pl desktop -am -DskipTests compile")


def java_launcher() -> list[str]:
    if JBR.is_file():
        return [str(JBR), "java"]
    return ["java"]


def compile_if_needed() -> None:
    marker = ROOT / "desktop/target/classes" / ADAPTER_CLASS.replace(".", "/")
    if marker.with_suffix(".class").is_file():
        return
    command = [
        *java_launcher()[:-1],
        str(ROOT / "scripts/jbr/with-jbr-25.sh"),
        "mvn", "-q", "-pl", "desktop", "-am", "-DskipTests", "compile",
    ]
    if not JBR.is_file():
        command = ["mvn", "-q", "-pl", "desktop", "-am", "-DskipTests", "compile"]
    completed = subprocess.run(command, cwd=ROOT, check=False, capture_output=True,
                               text=True)
    if completed.returncode != 0:
        raise ValueError(
            "Java adapter compile failed: "
            + (completed.stdout + completed.stderr)[-2000:])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--asset-pack", type=Path)
    parser.add_argument("--skip-build", action="store_true")
    args = parser.parse_args()
    scenario = explorer.load_json(args.scenario, "playtest scenario")
    explorer.validate_scenario(scenario)
    try:
        if not args.skip_build:
            compile_if_needed()
        pack = resolve_pack(args.asset_pack)
        build = engine_build_sha256()
        command = [
            *java_launcher(),
            "-cp", classpath(),
            f"-Dchonkcraft.pack={pack}",
            "-Djava.awt.headless=true",
            ADAPTER_CLASS,
            "--scenario", str(args.scenario.expanduser().resolve()),
            "--output", str(args.output.expanduser().resolve()),
            "--build-sha256", build,
        ]
        completed = subprocess.run(command, cwd=ROOT, check=False,
                                   capture_output=True, text=True)
        if completed.returncode != 0:
            raise ValueError(
                f"Java adapter failed ({completed.returncode}): "
                + (completed.stdout + completed.stderr)[-2000:])
        result = explorer.load_json(args.output, "Java adapter result")
        explorer.validate_result(result, scenario, "java")
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"bne-playtest-java-adapter: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
