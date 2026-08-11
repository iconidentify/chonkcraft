# Warcraft II BNE oracle harness

This directory contains the Windows side of the original-engine parity
harness. It treats the English retail Warcraft II Battle.net Edition 2.02b
executable as an oracle: the game runs in an isolated Wine prefix and writes
state after BNE's timed unit-update pass in the same vocabulary as the Java
engine trace.

For the repeatable Java engineering loop and the current resumable handoff
checkpoint, start with [`PARITY.md`](PARITY.md). Corpus capture and fixture
format details remain in [`CORPUS.md`](CORPUS.md) and
[`FIXTURE.md`](FIXTURE.md).

For the native oil-tanker state machine, exact dwell cadence, congestion
geometry, automatic platform-builder haul, failure recovery, and its
executable lifecycle gate, read [`OIL_LIFECYCLE.md`](OIL_LIFECYCLE.md).

For the low-context causal/experiment layer and composed one-command loop,
read [`PARITY_LAB.md`](PARITY_LAB.md). Its machine policy is
[`parity-lab-policy.json`](parity-lab-policy.json).

For authenticated native writer localization, clean/failing branch contrast,
and bounded predicate capture, read
[`BRANCH_WITNESS.md`](BRANCH_WITNESS.md).
For accepted-versus-rejected function visits where the rejected side writes
nothing, read [`DECISION_MINER.md`](DECISION_MINER.md).
For a hit-point mismatch that can only be a differently rolled blow, and the
seed-anchored comparison of the two engines' draw ledgers that follows it
upstream, read [`RNG_LEDGER.md`](RNG_LEDGER.md).
For a divergence decided several cycles before anything visible happens, and
the recovery of the native record's transitions across that window, read
[`STATE_MACHINE.md`](STATE_MACHINE.md).
For replaying one bounded native decision offline and measuring what it would
have answered with other inputs, read [`MICRO_ORACLE.md`](MICRO_ORACLE.md).
For which slot a shot was built in, when it came free, and which later shot
took it, read [`PROJECTILE_LEDGER.md`](PROJECTILE_LEDGER.md).
For whether a native capture that would answer the question is already on this
machine, read [`EVIDENCE_CATALOG.md`](EVIDENCE_CATALOG.md).

The harness never contains or publishes game files.  `work/` is ignored and is
populated from a user's own disc image.

## Current milestone

The bootstrap milestone provides:

- deterministic conversion of a single-track `MODE1/2352` BIN/CUE archive to
  ISO without modifying the source media;
- a source manifest containing hashes for the archive, raw track, ISO, and
  every executable found on the disc;
- deterministic extraction and hashing of every full replacement file carried
  by Blizzard's official `War2Patch_202.exe`;
- a 32-bit tracer DLL that validates and hooks the active 2.02b timed unit
  update at `0x00421238` (`call 0x00452110`);
- a 32-bit injector that starts the target suspended, loads the tracer, and
  initializes it before the first instruction of the game runs;
- a cycle trace containing the sync RNG seed, player banks, and the live unit
  roster/type/owner/position/health/order; and
- no-click startup for all 52 retail Tides of Darkness and Beyond the Dark
  Portal campaign PUDs, through BNE's own campaign/new-game path;
- a cycle-indexed command file whose first supported action, `move`, calls
  BNE's own guarded order routine instead of editing unit state; and
- an explicit initialization seed that makes BNE's otherwise wall-clock-
  dependent unit facing and animation delay reproducible; and
- a versioned `.bnefx` fixture containing the trace, commands, manifest, and
  delta-compressed lossless copies of changed 152-byte unit records, 64-byte
  projectile/effect records, mutable map cells/squares, and extended player
  supply/technology state; and
- a smoke host for proving DLL loading independently of Warcraft II.

The live acceptance runs require no menu input. Two independent retail Orc
mission 1 launches, each seeded with 1, produced identical 40-cycle canonical
streams: 120 player-bank records, 480 live-unit records, and SHA-256
`e8a2c23976714791e02d1d10e48c84f68e138540dda93e8b66851570ce5f7e65`.
Both issued a move to the starting peon before cycle 5 through BNE's order
engine and stopped at the requested boundary. The runner rejects a run unless
the scenario, seed application, command count, contiguous cycles, and terminal
markers all match the request. A second independent pair on Human mission 1,
without commands, also matched at 640 records and SHA-256
`928603e06ed7c5ef1a6f8cd90bfcae760a9f16ae22e4fbf12ff9b045095ac770`.

The fixture writer was exercised twice on the Orc command case. Both live
processes produced fixture ID
`102ce8d09ae0445c39a23174d79b2ecf2897a32cc2caf5bad728762bb0101c9e`,
2,068 reconstructable unit deltas, and identical raw-state identities. A
342,124-byte state stream plus trace, command, and manifest compressed to a
19,691-byte `.bnefx` bundle. The binary contract is documented in
[`FIXTURE.md`](FIXTURE.md).

Raw state schema 1.1 was then exercised by two more independent runs of that
same case. Both emitted the same 414,980-byte state stream (SHA-256
`dcb7f6192b8ae99f79f34d6ee8f85e07d5b37e4c60478e1707d02d5428fec0d8`),
including 491 projectile deltas, 1,028 map deltas, 120 extended-player records,
and the original 2,068 unit deltas. All new semantic digests and fixture ID
`b8003a04edafaf8b68e38a77ad4946de1375938977b131aff371b7c9bfe52e80`
matched. The reader remains compatible with unit-only schema 1.0 fixtures.

## Prepare retail media

```sh
python3 scripts/bne_media.py prepare \
  --archive "/path/to/WarCraft II - Battle.net Edition (USA).zip" \
  --work-dir work
```

The command is content-addressed and idempotent.  Its final line is the path to
the prepared source directory.  Extracted files remain below `work/sources/`.

The retail installer is itself an MPQ.  `java/MpqTool.java` inventories or
materializes it read-only with the same JMPQ3 reader used by the launcher.  It
validates every archived path before extraction; no MPQ entry can escape the
chosen destination.

Installation still goes through Blizzard's installer and requires the CD key
from the user's packaging. The harness never stores or logs that key.

After initializing a Wine prefix, map the prepared directory and normalized
ISO as retail CD-ROM drive `I:`:

```sh
python3 scripts/bne_oracle.py configure-media \
  --prefix work/oracle-prefix \
  --cd-dir work/sources/SOURCE_ID/cd \
  --iso work/sources/SOURCE_ID/media/disc.iso
```

The command is idempotent. It refuses to replace either Wine drive link if it
already targets different media. Run the retail installer normally, enter the
key yourself, apply Blizzard's updater, and use `verify` below before tracing.

## Pin the official 2.02b target

Blizzard's legacy download is `War2Patch_202.exe`. The accepted updater is
1,031,826 bytes, SHA-256
`194cdb4ea37aed678b095769ed9fc741d41d4d78f937fbee37734e9d6da5de19`
(published MD5 `1d29f0793e45457548ba35b6b7692dd1`). Its patch archive carries full
replacement files behind a 24-byte Blizzard patch header. Extract and verify
them without executing the updater:

```sh
scripts/prepare_patch.sh /path/to/War2Patch_202.exe work/target-2.02
```

The authoritative executable is 712,704 bytes, reports `Version 2.02b`, and
has SHA-256
`b0e914a9cb7dcc81a205e700a9bb0a1d0649df19d459388051ba170783d2c807`.
`target-manifest.json` records the updater and every extracted file. These
files are for identity analysis; use Blizzard's updater on the retail install
and then verify that installed directory:

```sh
python3 scripts/bne_oracle.py verify --game-dir "/path/to/Warcraft II BNE"
```

## Build the Windows tools

```sh
cmake -S . -B build \
  -DCMAKE_TOOLCHAIN_FILE=cmake/mingw32.cmake \
  -DCMAKE_BUILD_TYPE=Release
cmake --build build
```

All programs are 32-bit because retail BNE is a 32-bit x86 process.  The smoke
test is:

```sh
CHONK_BNE_TRACE='Z:\absolute\path\to\bne-smoke.jsonl' \
  wine build/bne-smoke-host.exe build/bne-trace.dll
```

`bne-inject.exe` accepts a DLL, an executable, and the executable's arguments:

```text
bne-inject.exe Z:\path\bne-trace.dll Z:\path\Warcraft\ II\ BNE.exe
```

No part of the harness connects to Battle.net.  Keep Wine networking disabled
for oracle runs.

After installing and applying the official patch, start an oracle trace with:

```sh
python3 scripts/bne_oracle.py run \
  --game-dir "/path/to/Warcraft II BNE" \
  --prefix work/oracle-prefix \
  --trace work/traces/bne.txt \
  --source-manifest work/sources/SOURCE_ID/source-manifest.json \
  --scenario 'Campaign\Orc\Orc01.pud' \
  --commands work/commands/orc01.txt \
  --seed 1 \
  --cycles 1800
```

The runner refuses any executable or support DLL whose hash differs from the
official 2.02b payload. It also pins the installed `War2Dat.mpq` and
`War2Patch.mpq`, refuses to append to an existing trace, validates contiguous
cycles plus player and unit records, and writes `bne.txt.manifest.json` beside
the result. That manifest fingerprints the source media, oracle payload,
Wine runtime, injector, tracer, and trace itself. It never records the CD key.
Modified third-party executables are useful research references, but
they cannot silently become the oracle.

`--seed` controls BNE's separate scenario-construction RNG and defaults to 1.
It does not replace or reset the synchronized gameplay RNG recorded on every
cycle. The applied unsigned 32-bit value is required in the trace and recorded
in the run manifest.

Each successful `run` also writes `TRACE.state`, `TRACE.manifest.json`, and
`TRACE.bnefx` unless explicit `--state`, `--manifest`, or `--fixture` paths are
provided. A sealed result can be checked without launching the game:

```sh
python3 scripts/bne_oracle.py validate-fixture work/traces/orc01.bnefx
```

For the one-time bulk capture, `scripts/bne_corpus.py` generates a 52-case
campaign plan, runs it sequentially, atomically indexes each validated bundle,
and resumes without regenerating completed evidence. The resulting corpus can
be verified later without Wine or retail files. See [`CORPUS.md`](CORPUS.md)
for the exact workflow, the `scripts/bne_java.py` offline Java survey, and the
replay-ingestion boundary.

Historical multiplayer inputs are handled separately by
`scripts/bne_replay.py`. It strictly validates InSight 1.1 `.wir` checksums,
initial-snapshot boundaries, player metadata, and every opaque network command
record, then produces a reproducible source inventory. The reverse-engineered
format and the remaining BNE dispatch-hook work are documented in
[`REPLAYS.md`](REPLAYS.md).

`--scenario` accepts every numbered retail campaign map: `Human01` through
`Human14`, `Orc01` through `Orc14`, `2XHum01` through `2XHum12`, and `2XOrc01`
through `2XOrc12`, under their original `Campaign` directories. The harness
maps the name to the retail campaign selector and resource ID, asks BNE's main
loop to enter its stock new-game case, and bypasses only the blocking
interlude/objectives UI. The temporary UI flag is cleared immediately before
BNE begins scenario initialization. Arbitrary custom PUD startup is a later
extension because it uses a different Storm file-handle path.

The optional command file is ASCII, cycle-sorted, and intentionally small. Its
current grammar is:

```text
# Apply before BNE updates cycle 5.
cycle 5 move unit 1594 x 30 y 18
```

Unit numbers are the stable pool slots printed by the trace. A command is
rejected unless that slot is live and owned by the local player. Coordinates
are retail BNE tiles (`0..127`). The command is presented to BNE immediately
before the named game tick; BNE still decides when the unit can accept and
execute it. The command file's name, byte length, and SHA-256 are written into
the run manifest.

The startup Tip of the Day is disabled because its DirectDraw overlay cannot
accept input through Wine's macOS driver. The option is UI-only and is cleared
before the first game update.

Wine reports a directory-backed `I:` drive as a CD-ROM, but Storm 1.08 still
rejects `I:\\Install.exe` when passed its physical-CD flag. If and only if that
call fails with `ERROR_INVALID_DRIVE`, the tracer retries the same path and
priority with Storm's ordinary-file flag. The CD identity files inside the
archive are then read by the unmodified game. No asset or simulation data is
substituted.

## Trace protocol

Comment lines begin with `# bne-trace`. Simulation records use the existing
plain-text parity format consumed by `scripts/diff-determinism.py`:

```text
cycle 1 seed 12345678
p 0 gold 2000 wood 1000 oil 1000
u 0 unit-peasant p0 12 18 hp 30 o HARVEST
```

BNE's unit types and orders are translated into the Java trace vocabulary at
the boundary. Hidden units (workers in mines, passengers, and similar state)
are retained with the `removed` marker, matching the Java trace's off-map
semantics.

Every run must retain the source manifest beside its trace. The executable
hash, disc hash, selected scenario path, containing data-MPQ hash, and harness
build hash are part of the identity of an oracle result. The run manifest also
records a canonical SHA-256 over only the `cycle`, `p`, and `u` records. That
digest excludes diagnostic process IDs and Storm handles, so two identical
oracle runs can be compared directly.
