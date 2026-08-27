# ChonkCraft

A native Java recreation of Warcraft II: Battle.net Edition, targeting the
observable behavior of the original Windows release.

The port plays. Campaigns, skirmish maps, combat, economy, construction, fog of
war, computer opponents, spells, upgrades, sound, music, save and load, and
lockstep multiplayer all run from an authenticated asset pack built from the
player's own game. All 52 campaign missions load and run.

[STATUS.md](STATUS.md) gives the current release posture. Compatibility is
certified by executable tests, the authenticated playability gate, and retail
comparison evidence rather than by a permanent narrative defect ledger.

## Start here

| If you want to | Read |
|---|---|
| Get it building, testing and running | **[docs/development-setup.md](docs/development-setup.md)** |
| Change any of it | **[CONTRIBUTING.md](CONTRIBUTING.md)** |
| Know how it is put together, and why | [docs/architecture.md](docs/architecture.md) |
| See the current release posture | [STATUS.md](STATUS.md) |
| Understand CI, or fix a red build | [docs/ci.md](docs/ci.md) |
| Add to or repaint the game's assets | [docs/asset-pack-format.md](docs/asset-pack-format.md) |
| Understand online, direct-IP and LAN multiplayer | [docs/multiplayer-matchmaking.md](docs/multiplayer-matchmaking.md) |

**Read CONTRIBUTING.md before writing code.** This repository has strong
conventions that are not obvious from the source: behavior is tied to durable
evidence, tests assert behavior rather than implementation details, findings
are proved with a headless probe against authenticated game data before engine
changes land, and the simulation must stay deterministic across machines.

## Provenance, attribution, and license

ChonkCraft descends from the GPL-licensed **Wargus** game project and the
**Stratagus** engine. Their developers created the open-source foundation from
which this Java codebase evolved, and this project gratefully acknowledges that
work. ChonkCraft is distributed under the **GNU General Public License,
version 2 only**; see [LICENSE](LICENSE).

Development uses Warcraft II: Battle.net Edition 2.02b as the behavioral
north star. The original retail executable is treated as an oracle: observable
state, timing, rendering, audio, pathing, combat, AI, and campaign behavior are
captured and compared with the Java engine. This is clean behavioral research;
the proprietary executable and game assets are not distributed. Historical
semantics inherited through Wargus and Stratagus remain useful evidence, but
where they disagree, authenticated retail behavior controls.

## Quick start

Linux and macOS take the same commands. You need Maven 3.9+ on `PATH`; the
pinned JDK is fetched for you.

```bash
export CHONKCRAFT_ASSET_PACK=/path/to/warcraft-ii-bne.chonkpack

scripts/check-setup.sh      # what this machine has, and what a test run would exercise
scripts/run-tests.sh        # the full reactor test run
scripts/run-launcher.sh     # prepare a graphics pack, update, and play
```

`scripts/run-game.sh ALAMO.PUD` remains the developer shortcut that goes
straight into a map instead of the launcher.

### Massive battle showcase

For video capture or a repeatable stress run, the total-war showcase restores
the original giant central Garden of War collision and turns the formerly
empty outer perimeter into an ocean for opposing fleets. The camera opens on
the enormous flat-map land melee as synchronized land and naval charges begin.
Footmen, grunts, knights, ogres, archers,
axethrowers, ballistae, catapults, demolition teams, workers, mages, death
knights, gryphons, dragons, battleships, juggernauts, destroyers, submarines,
transports and tankers are all present. Mages open with blizzards and fireballs;
death knights answer with death and decay, whirlwinds and death coils. It uses
the normal BNE movement, targeting, spell, projectile, animation, sound and
death paths; it is not a pre-rendered scene and never modifies the source map
or graphics pack.

```bash
scripts/run-battle-showcase.sh                 # visual, 720 combatants
scripts/run-battle-showcase.sh 1600            # visual, maximum chaos
scripts/run-battle-showcase.sh --benchmark     # 720 units, 1,800 headless cycles
scripts/run-battle-showcase.sh --benchmark 1600 900
```

Land, sea, siege, air and magic all run at once. The showcase disables fog of
war and reveals cloaked naval units for recording; normal games are unaffected.
The benchmark reports simulation throughput,
casualties, damaged survivors and peak projectiles, and fails if any combat
domain is absent, the armies never engage, or the map cannot hold at least 75%
of the requested force. Once the
opening formations cross, a small showcase director periodically gives every
disengaged survivor a normal attack command against its nearest compatible
enemy. It does not move units or deal damage itself; it prevents congestion or
an exhausted attack-move from looking like a silent ceasefire merely because
there is no human issuing the next command. The status line reports the
remaining forces and clearly names the winner when one side is eliminated.

The number is the combined force size and is clamped to 80–1,600. For a clean
capture, close any other running ChonkCraft game first, then launch the visual
mode and record the window. Press Escape and choose **End Scenario**, or close
the window, when the capture is finished. The deterministic benchmark is the
quick verification path: the same pack, map, deployment and combat simulation
run without drawing frames or playing audio.

### Two things that will otherwise cost you a day

**The JDK bootstrap is not macOS-only.** `scripts/jbr/with-jbr-25.sh` resolves
a pinned JetBrains Runtime 25 SDK for Linux x64, Linux aarch64, macOS on either
architecture, and Windows x64, downloading and checksum-verifying it once into
`~/.chonk/jdks`. Every script in `scripts/` goes through it.

**A green test run may have tested almost nothing.** Tests that need retail
assets use JUnit `Assumptions` and *skip* when no pack is configured; the build
still reports `BUILD SUCCESS`. Run `scripts/check-setup.sh` first and inspect
the skip count. The 18-lane BNE playability gate fails if any selected referee
skips, and deliberately runs without any retired source-tree dependency.
See [docs/development-setup.md](docs/development-setup.md#reading-a-test-run-skips-are-not-passes).

## Modules

Module order is dependency order.

```text
assetpack/ The asset pack format: the manifest, the zip container, and the
          codecs a pack's payloads are stored in (indexed PNG, FLAC, WAV).
          Depends on NOTHING, by rule. The game reads packs and the extractor
          writes them; the format is the only thing they share.
runtime/  net.chonkbase.runtime, vendored from seven-days-to-tomorrow.
          Fixed-step timing, Java2D pipeline selection, fullscreen, SDL
          input, PCM bus mixer. Kept source-identical to upstream, so
          changes belong upstream and sync in, not here.
data/     Native archive, graphics, tileset, audio, PUD, CD-image and XMI
          reading, graphics, tilesets, audio, PUD maps, CD images, XMI.
extractor/ Turns a Warcraft II installation into one pack. A build-time tool,
          not part of the game: it depends on assetpack and data and on
          nothing in engine or desktop, and IsolationTest fails the build if
          that ever changes.
launcher/ The first screen. Imports original directories, discs and archive
          images; creates and selects graphics packs; automatically maintains
          one current game; then starts that game and the active pack together.
matchmaking/ Shared room records, HTTPS client, human game codes, and the
          reconnecting WebSocket datagram transport. It contains no game rules.
engine/   Deterministic Warcraft II simulation, rules, AI, rendering and audio.
          The generated native game definitions live here too.
desktop/  The replaceable game entry point and its screens.
matchmaker-server/ Bootable room directory and opaque packet relay. Public and
          private lobbies are short-lived and the host remains authoritative.
```

## Packaging

Native packages bundle a trimmed JetBrains Runtime, so a player needs no Java.
They still need their own Warcraft II data, which is never included.

```bash
scripts/release/build-macos-app.sh --dmg        # on macOS
scripts/release/install-pinned-appimagetool.sh /tmp/appimagetool
scripts/release/build-linux-appimage.sh /tmp/appimagetool  # on Linux
scripts/release/build-windows-package.sh --msi  # on Windows
```

Each must run on its own platform: jpackage builds for the platform it runs on.
The `Release Builds` workflow always produces and proves the complete platform
set: a Developer ID signed, notarized and stapled Apple-silicon DMG; an Azure
Trusted Signed Windows x64 MSI; and a portable Linux x64 AppImage. It publishes all three to
the GitHub release and to `https://updates.chonkbase.net/downloads/` only after
every lane passes. Immutable versioned installers land before stable download
names and the no-cache `latest.json` catalog, which keeps the public
`https://chonkbase.net/chonkcraft` page current without rebuilding it.
Warcraft II data is never placed in the workflow or artifact.

To check a built macOS bundle actually starts:

```bash
CHONKCRAFT_ASSET_PACK=/path/to/chonkcraft.chonkpack \
  scripts/release/verify-macos-app.sh "desktop/target/dist/macos/ChonkCraft.app"
```

The installed application always enters through the durable launcher. Its
bundled game JAR is the offline fallback; game fixes arrive independently as a
replaceable child JAR, so users do not reinstall the DMG for each engine build.
`publish-game-update.yml` automatically builds and publishes changed game code
from `master` to `https://updates.chonkbase.net`. The launcher accepts a JAR
only when its size and SHA-256 are authorized by the launcher's embedded
Ed25519 public key, installs it atomically, and restores the previous version
if the new child process cannot stay alive. Players see one automatically
maintained game—there is no version picker. ChonkPacks are stored separately
and are never rewritten by a code update. See [game updates](docs/game-updates.md).

## Game data

Warcraft II data is not distributed with this repository and never will be. The
port reads your own copy of the game, and extracted assets are never committed.

There are two ways for it to read that copy, and the game does not know which
it got.

**An asset pack**, which is what a player has. One file holding everything the
game draws, plays and reads, in a modern encoding: sprites as palette-indexed
PNGs an artist can open and repaint, music and effects as Opus or FLAC
whichever is smaller for that clip, cutscenes as they were authored. Build one
from your own installation:

```bash
scripts/build-asset-pack.sh                       # writes chonkcraft.chonkpack
CHONKCRAFT_ASSET_PACK=/path/to/chonkcraft.chonkpack scripts/run-game.sh
```

Measured against a DOS installation with both discs: **1,091,487,952 bytes of
1995 files become a 164,650,362 byte pack -- 157.0 MB, 84.9% smaller**. Against
everything that has to be on disk for the game to run, disc images included,
it is 88.8% smaller. All 1,355 assets are verified during the build: 1,187
decode to exactly what the installation decodes to, and the 168 stored lossily
are checked for rate, channels, length and signal-to-noise instead. The format,
and what an artist has to produce to add to it, is specified in
[docs/asset-pack-format.md](docs/asset-pack-format.md).

The launcher creates that pack directly from an installed directory, mounted
physical CD, ZIP, ISO or Toast image, BIN/CUE or IMG/CCD image, and StuffIt,
7z, RAR or tar archive. Cooked ISO9660, raw
2352-byte sectors and classic Mac HFS data tracks are read without mounting
them. A completed pack is verified before it appears in the selector, and the
original media is never uploaded. The small Play window opens a separate
ChonkPack manager for drag-and-drop import, file selection, export and
deletion. New packs retain the detected source release and layout plus the
original filename, size and SHA-256 when the source was a file, so similar
editions remain distinguishable.
For Battle.net Edition, the importer also reads the MPQ carried inside
`INSTALL.EXE` and its nested `War2Dat.mpq`; that is where the edition keeps its
unit voices, campaign speech, 153 multiplayer maps, recorded soundtrack and
cutscenes rather than in the four visible `TOME` archives. Those are real
edition-specific upgrades: effects and narration are 16-bit, the soundtrack is
22.05 kHz stereo PCM, and the main cutscenes are 320x288 with 16-bit stereo
audio instead of the older release's 320x144/320x200, 8-bit mono versions.

**The raw installation**, which is what the tests use and what the extractor
reads. Set `WC2_INSTALL_DIR` and nothing else. This path is unchanged and is
not going away.

## Repository map

```text
README.md                    this file
CONTRIBUTING.md              how the codebase thinks; read before changing anything
STATUS.md                    the current release posture
docs/development-setup.md    the environment, in full
docs/architecture.md         modules, upstream mapping, and the decisions
docs/ci.md                   both workflows, the skip gate, the self-hosted runner
docs/asset-pack-format.md    the asset pack, specified; what an artist must produce
runtime/README.md            the vendored module, and the rule for changing it
scripts/                     build, test, run, audit and release tooling
scripts/check-docs.py        keeps the docs above honest; CI gates on it
tools/                       one-off extraction helpers
```

Seven documents, and that is meant to be the whole of it. **CONTRIBUTING.md**
says how to change the code, **STATUS.md** states the present release posture,
and **docs/development-setup.md** says how to build and run it; the rest support
those three.

`scripts/check-docs.py` fails the build on a link to a moved file, a document
named in prose that no longer exists, or a test count that disagrees with the
skip gate. Documentation rot in this repository is a build failure, not a
matter of remembering.
