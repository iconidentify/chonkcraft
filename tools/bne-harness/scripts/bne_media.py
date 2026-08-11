#!/usr/bin/env python3
"""Prepare and fingerprint user-owned Warcraft II BNE retail media."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

RAW_SECTOR_BYTES = 2352
MODE1_DATA_OFFSET = 16
MODE1_DATA_BYTES = 2048
CD_SYNC = b"\x00" + b"\xff" * 10 + b"\x00"
CUE_TRACK = re.compile(r"^\s*TRACK\s+\d+\s+(MODE1/(?:2352|2048))\s*$", re.I | re.M)


def hash_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def find_members(archive: zipfile.ZipFile) -> tuple[str, str, str]:
    files = [entry.filename for entry in archive.infolist() if not entry.is_dir()]
    cue_members = [name for name in files if name.lower().endswith(".cue")]
    bin_members = [name for name in files if name.lower().endswith(".bin")]
    if len(cue_members) != 1 or len(bin_members) != 1:
        raise ValueError("expected exactly one CUE and one BIN member")
    cue_text = archive.read(cue_members[0]).decode("ascii")
    match = CUE_TRACK.search(cue_text)
    if match is None:
        raise ValueError("CUE does not describe a supported MODE1 data track")
    if len(CUE_TRACK.findall(cue_text)) != 1:
        raise ValueError("only a single data track is supported")
    return cue_members[0], bin_members[0], match.group(1).upper()


def convert_mode1(source, destination: Path, layout: str) -> tuple[str, str, int]:
    raw_digest = hashlib.sha256()
    iso_digest = hashlib.sha256()
    sectors = 0
    sector_bytes = MODE1_DATA_BYTES if layout == "MODE1/2048" else RAW_SECTOR_BYTES

    with destination.open("wb") as output:
        while True:
            sector = source.read(sector_bytes)
            if not sector:
                break
            if len(sector) != sector_bytes:
                raise ValueError(f"short final sector: {len(sector)} of {sector_bytes} bytes")
            raw_digest.update(sector)
            if layout == "MODE1/2352":
                if sector[:12] != CD_SYNC or sector[15] != 1:
                    raise ValueError(f"sector {sectors} is not a Mode-1 raw sector")
                data = sector[MODE1_DATA_OFFSET:MODE1_DATA_OFFSET + MODE1_DATA_BYTES]
            else:
                data = sector
            output.write(data)
            iso_digest.update(data)
            sectors += 1
    return raw_digest.hexdigest(), iso_digest.hexdigest(), sectors


def executable_manifest(root: Path) -> list[dict[str, object]]:
    executables = []
    for path in sorted(root.rglob("*"), key=lambda value: value.as_posix().lower()):
        if path.is_file() and path.suffix.lower() in {".exe", ".dll"}:
            executables.append({
                "path": path.relative_to(root).as_posix(),
                "bytes": path.stat().st_size,
                "sha256": hash_file(path),
            })
    return executables


def extract_iso(iso: Path, destination: Path) -> None:
    seven_zip = shutil.which("7z")
    if seven_zip is None:
        raise RuntimeError("7z is required to extract the prepared ISO")
    destination.mkdir(parents=True, exist_ok=False)
    subprocess.run(
        [seven_zip, "x", "-y", f"-o{destination}", str(iso)],
        check=True,
        stdout=subprocess.DEVNULL,
    )


def prepare(archive_path: Path, work_dir: Path) -> Path:
    archive_path = archive_path.resolve()
    work_dir = work_dir.resolve()
    archive_sha = hash_file(archive_path)
    source_dir = work_dir / "sources" / archive_sha
    manifest_path = source_dir / "source-manifest.json"
    if manifest_path.is_file():
        print(source_dir)
        return source_dir

    source_parent = source_dir.parent
    source_parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=f".{archive_sha[:12]}-", dir=source_parent))
    try:
        media_dir = staging / "media"
        media_dir.mkdir()
        iso_path = media_dir / "disc.iso"
        with zipfile.ZipFile(archive_path) as archive:
            cue_member, bin_member, layout = find_members(archive)
            cue_bytes = archive.read(cue_member)
            (media_dir / "disc.cue").write_bytes(cue_bytes)
            with archive.open(bin_member) as raw_track:
                raw_sha, iso_sha, sectors = convert_mode1(raw_track, iso_path, layout)

        cd_dir = staging / "cd"
        extract_iso(iso_path, cd_dir)
        manifest = {
            "schema": 1,
            "identity": "warcraft-ii-bne-retail-oracle-source",
            "authority": "Blizzard retail Battle.net Edition; patch level verified after install",
            "archive": {
                "name": archive_path.name,
                "bytes": archive_path.stat().st_size,
                "sha256": archive_sha,
            },
            "track": {
                "cue_member": cue_member,
                "bin_member": bin_member,
                "layout": layout,
                "sectors": sectors,
                "raw_sha256": raw_sha,
            },
            "iso": {
                "bytes": iso_path.stat().st_size,
                "sha256": iso_sha,
            },
            "executables": executable_manifest(cd_dir),
        }
        manifest_path_staging = staging / "source-manifest.json"
        manifest_path_staging.write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        os.replace(staging, source_dir)
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise
    print(source_dir)
    return source_dir


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    subcommands = result.add_subparsers(dest="command", required=True)
    prepare_parser = subcommands.add_parser("prepare", help="prepare a retail BIN/CUE ZIP")
    prepare_parser.add_argument("--archive", required=True, type=Path)
    prepare_parser.add_argument("--work-dir", required=True, type=Path)
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        if args.command == "prepare":
            prepare(args.archive, args.work_dir)
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        print(f"bne-media: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
