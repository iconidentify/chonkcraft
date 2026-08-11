#!/usr/bin/env python3
"""Fail-closed inventory and provenance receipt for a full BNE ChonkPack."""

from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import zipfile


SOURCE_BYTES = 662_253_608
SOURCE_SHA256 = "efe27f4dd510dd5f2efd7dcf6edfbd8dccee35a10b991123d922b2d01ec09bc6"
TRACK_NAMES = [
    *(f"Human Battle {number}" for number in range(1, 7)),
    *(f"Orc Battle {number}" for number in range(1, 7)),
    "Human Briefing",
    "Orc Briefing",
    "Human Victory",
    "Orc Victory",
    "Human Defeat",
    "Orc Defeat",
    "Main Menu",
    "I'm a Medieval Man",
]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def git(*command: str) -> str:
    return subprocess.run(
        ["git", *command], check=True, text=True, capture_output=True
    ).stdout.strip()


def verify(source: Path, pack_path: Path, peer: Path | None) -> dict[str, object]:
    require(source.is_file(), f"source does not exist: {source}")
    require(pack_path.is_file(), f"pack does not exist: {pack_path}")
    source_hash = sha256(source)
    require(source.stat().st_size == SOURCE_BYTES, "retail source size is not the pinned size")
    require(source_hash == SOURCE_SHA256, "retail source SHA-256 is not the pinned identity")

    pack_hash = sha256(pack_path)
    peer_hash = None
    if peer is not None:
        require(peer.is_file(), f"reproducibility peer does not exist: {peer}")
        peer_hash = sha256(peer)
        require(peer.stat().st_size == pack_path.stat().st_size,
                "reproducibility peers have different sizes")
        require(peer_hash == pack_hash, "reproducibility peers are not byte-identical")

    with zipfile.ZipFile(pack_path) as archive:
        manifest = json.loads(archive.read("pack.json"))
        identity = manifest["pack"]
        properties = identity["properties"]
        require(identity["id"] == "wc2-battle-net-edition", "wrong pack edition")
        require(identity["source"] == f"Imported from {source.name}",
                "pack identity retained a staging path")
        require(properties["battleNetEdition"] is True, "BNE flag is absent")
        require(properties["expansionEntries"] is True, "expansion entries flag is absent")
        require(properties["expansionRelease"] is True, "expansion release flag is absent")
        require(properties["sourceOriginalName"] == source.name, "source filename drifted")
        require(properties["sourceOriginalBytes"] == SOURCE_BYTES, "source size provenance drifted")
        require(properties["sourceOriginalSha256"] == SOURCE_SHA256,
                "source hash provenance drifted")

        assets = manifest["assets"]
        by_kind = Counter(asset["kind"] for asset in assets)
        require(len(assets) == 1412, f"expected 1412 logical assets, found {len(assets)}")
        require(by_kind["music"] == 20, f"expected 20 music assets, found {by_kind['music']}")
        require(by_kind["map"] == 237, f"expected 237 map assets, found {by_kind['map']}")
        require(len(manifest["maps"]) == 153,
                f"expected 153 BNE map identities, found {len(manifest['maps'])}")
        require(by_kind["sound"] == 491, f"expected 491 BNE sounds, found {by_kind['sound']}")
        require(by_kind["video"] == 13, f"expected 13 videos, found {by_kind['video']}")
        require(by_kind["sequence"] == 17,
                f"expected 17 sequenced tracks, found {by_kind['sequence']}")
        require(by_kind["sprite"] == 266, f"expected 266 sprites, found {by_kind['sprite']}")

        disc_indices = [index for disc in manifest["discs"] for index in disc["tracks"]]
        require(len(disc_indices) == 20, "disc table does not retain 20 logical tracks")
        tracks = [assets[index] for index in disc_indices]
        require([track["meta"]["name"] for track in tracks] == TRACK_NAMES,
                "BNE logical soundtrack order or names drifted")
        for track in tracks:
            meta = track["meta"]
            require(track["kind"] == "music" and track["codec"] == "opus",
                    f"{track['id']} is not Opus music")
            require(meta["sampleRate"] == 22_050 and meta["decodeSampleRate"] == 48_000,
                    f"{track['id']} has incorrect source/codec rate metadata")
            require(meta["channels"] == 2 and meta["bitsPerSample"] == 16,
                    f"{track['id']} is not declared 16-bit stereo")
            require(meta["bitrateBps"] == 144_000, f"{track['id']} has the wrong bitrate policy")
            require(meta["sampleFrames"] > 0, f"{track['id']} is empty")
            require(meta["sourceOrigin"].startswith("INSTALL.EXE:Music\\"),
                    f"{track['id']} did not come from BNE INSTALL.EXE")

        maindat = next(item for item in manifest["archives"] if item["id"] == 1000)
        require(maindat["slots"][277] >= 0, "maindat entry 277 / ai.bin is absent")
        require(maindat["slots"][278] >= 0, "maindat entry 278 / action timing is absent")
        require(not any(asset["id"] == "sounds/spells/basic_spell_sound" for asset in assets),
                "BNE unexpectedly contains the invalid classic placeholder spell sound")

        # Hash every physical payload once. This catches a manifest whose
        # inventory looks right but whose ZIP bytes were replaced or truncated.
        physical_hashes: dict[str, str] = {}
        for asset in assets:
            filename = asset["file"]
            if filename not in physical_hashes:
                physical_hashes[filename] = hashlib.sha256(archive.read(filename)).hexdigest()
            require(physical_hashes[filename] == asset["sha256"],
                    f"payload hash mismatch: {asset['id']}")

    status = git("status", "--porcelain")
    return {
        "schema": "bne-full-pack-receipt-v1",
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "git": {"commit": git("rev-parse", "HEAD"), "dirty": bool(status)},
        "source": {"path": str(source), "bytes": SOURCE_BYTES, "sha256": source_hash},
        "pack": {
            "path": str(pack_path),
            "bytes": pack_path.stat().st_size,
            "sha256": pack_hash,
            "assets": len(assets),
            "byKind": dict(sorted(by_kind.items())),
            "maps": len(manifest["maps"]),
            "musicTracks": len(tracks),
            "physicalPayloads": len(physical_hashes),
        },
        "reproducibilityPeer": None if peer is None else {
            "path": str(peer), "sha256": peer_hash, "byteIdentical": True
        },
        "skipped": 0,
        "failures": 0,
        "command": " ".join(sys.argv),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--pack", type=Path, required=True)
    parser.add_argument("--reproducibility-peer", type=Path)
    parser.add_argument("--receipt", type=Path)
    args = parser.parse_args()
    try:
        receipt = verify(args.source.resolve(), args.pack.resolve(),
                         None if args.reproducibility_peer is None
                         else args.reproducibility_peer.resolve())
    except (OSError, KeyError, IndexError, ValueError, zipfile.BadZipFile) as error:
        print(f"BNE full-pack verification FAILED: {error}", file=sys.stderr)
        return 1
    encoded = json.dumps(receipt, indent=2, sort_keys=True) + "\n"
    if args.receipt is not None:
        args.receipt.parent.mkdir(parents=True, exist_ok=True)
        temporary = args.receipt.with_suffix(args.receipt.suffix + ".new")
        temporary.write_text(encoded, encoding="utf-8")
        temporary.replace(args.receipt)
    print(encoded, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
