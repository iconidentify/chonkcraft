# BNE fixture contract

The `.bnefx` bundle is the durable output of an oracle run. Day-to-day parity
work consumes this bundle; it does not need Wine, the retail executable, or
the Warcraft II data archives.

This document describes fixture schema 1 and raw state schema 1.1. Both are
versioned independently. The reader remains compatible with the unit-only raw
state schema 1.0, but writers always emit 1.1. Readers reject a version or flag
set they do not understand rather than guessing at a layout.

## Bundle

A fixture is a ZIP64-capable archive with fixed member names and timestamps:

| Member | Required | Contents |
| --- | --- | --- |
| `manifest.json` | yes | Oracle, source, harness, input, and output identities |
| `trace.txt` | yes | Human-readable normalized state and diagnostics |
| `state.bin` | yes | Lossless unit, projectile, map, and player state deltas |
| `commands.txt` | when used | Exact scripted input supplied to BNE |

Every payload's byte length and SHA-256 is recorded in the manifest and
checked when the archive is reopened. Archive creation streams large files;
it does not hold a long session's state stream in memory.

The fixture ID is a SHA-256 over canonical JSON containing the schema, pinned
BNE executable and data identities, tracer identity, scenario, cycle bound,
initialization seed, command identity, and normalized simulation digest. It
intentionally excludes output filenames and process-local diagnostics. Two
independent runs of the same case therefore receive the same ID only after
their normalized simulation output agrees.

## Raw state stream

All integers are unsigned little-endian. The 32-byte file header is:

| Field | Width | Schema 1.1 value |
| --- | ---: | --- |
| Magic | 8 | `BNESTATE` |
| Major, minor | 2 + 2 | `1`, `1` |
| Header bytes | 4 | `32` |
| Unit bytes | 4 | `152` |
| Unit limit | 4 | `1600` |
| Player count | 4 | `16` |
| Flags | 4 | bit 0: unit deltas; bit 1: projectile deltas; bit 2: extended player state; bit 3: map deltas |

The header is followed by chunks. Every chunk begins with a four-byte ASCII
tag and a four-byte payload length.

### `CYCL`

The fixed cycle header contains four 32-bit values: cycle number, synchronized
gameplay RNG state, initialized unit-pool count, and changed-unit count. It is
followed by exactly sixteen player records, each containing four 32-bit
values: controller, gold, lumber, and oil.

Each changed-unit record contains a 32-bit pool slot, a 32-bit lifetime
generation, and the slot's complete 152-byte BNE unit record. Cycle 1 is a
full checkpoint containing every initialized slot. Later cycles contain a
record when any raw byte changes or a new lifetime begins in a reused slot.
A generation increments on the transition from a non-live slot to a live
slot, preventing one numeric slot from silently identifying multiple units.

### `AUXL`

Schema 1.1 requires exactly one `AUXL` chunk immediately after every `CYCL`.
Its fixed header is five 32-bit values: matching cycle number, projectile-pool
count, changed-projectile count, square map size, and changed-map-tile count.

The header is followed by sixteen 44-byte player simulation records. Each
record contains eight 16-bit counters (food limit, current units, current
buildings, rescued units, lost units, lost buildings, unit kills, and building
kills), ten one-byte technology levels (arrows, swords, shields, ship attack,
ship armor, catapult damage, ranger/berserker, marksmanship, longbow, and
scouting), two reserved zero bytes, then four 32-bit masks (allowed units,
allowed upgrades, allowed spells, and learned spells).

Each changed-projectile record contains a 32-bit pool slot, 32-bit lifetime
generation, and all 64 bytes of BNE's projectile/effect record. BNE allocates
200 slots in single-player or 400 in multiplayer. Byte 53 bit 0 identifies a
free slot. Cycle 1 checkpoints the full configured pool; later records are
raw-byte deltas. Generations use the same lifetime rule as units.

Each changed-map record contains a 32-bit row-major tile index, its 16-bit
visual cell, and its 16-bit simulation square flags. Cycle 1 checkpoints every
tile. Later records preserve terrain destruction, wall and forest changes,
mines/runes, occupancy, and other changes visible through either authoritative
map buffer. A map-size transition requires another complete checkpoint.

### `DONE`

The final chunk contains one 32-bit cycle count. A missing, duplicate, or
misplaced terminator makes the state stream incomplete.

## Required validation

The reader reconstructs the unit pool, projectile pool, and both mutable map
buffers. It validates contiguous paired chunks, unique in-range deltas,
generation transitions, complete initial checkpoints, exact payload sizes,
and the terminal count. It independently derives three digests from raw state
and requires them to match the text trace:

- cycle plus synchronized RNG state;
- active player banks; and
- every live unit's slot, owner, tile, health, and hidden state.

This makes the text view and raw forensic stream two encodings of the same
simulation boundary rather than unrelated files placed in one archive.

Schema 1.1 additionally derives exact extended-player, live-projectile, and
full-map digests. These do not yet have a Java-side text equivalent; they are
the frozen oracle values that later adapters must reproduce. The validator
also reports every delta and live-record count so corpus tooling can detect a
suspiciously empty capture.

## Present boundary and next schema work

Schema 1.1 preserves every byte in every initialized BNE unit slot, including
order, target, path, animation, and pointer fields that have not yet been
decoded. It also preserves BNE's complete projectile records, mutable map
cells/squares, controller and resource banks, supply/count statistics,
technology levels, availability masks, and learned spells.

It does not yet claim to preserve opaque campaign-trigger internals or the
network command queue before/after dispatch. The exact input command file and
the tracer's applied/rejected diagnostics remain in the bundle, but future
replay work needs a compatible raw dispatch chunk. Unknown or misplaced chunk
types are rejected so missing capture cannot be mistaken for a complete
fixture.
