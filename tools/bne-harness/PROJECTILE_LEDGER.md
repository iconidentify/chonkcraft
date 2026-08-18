# Projectile pool ledger

BNE keeps its projectiles and effects in one fixed table. Every entry is 64
bytes, and the fixture's schema 1.1 `AUXL` chunk preserves the whole table once
per cycle. So which slot a shot occupies at each cycle boundary, when it stops
being occupied, and which later shot appears there are all recorded facts.

`LAYOUT.md` records, from a static reading of the pinned executable, that the
allocator at `0x00410000` scans the table for the first entry whose byte 53
bit 0 is set. That is a claim from a different source than the capture, and
this ledger does not verify it -- which matters, because several readings
below would be conclusions rather than observations if it were assumed.

Until now they were read by eye out of a hex dump, and that is where the
free-cycle ordering mistakes came from. This ledger reads the same sealed
bundle and prints the timeline.

## What it will not tell you

The tracer walks the pool from slot 0 upward and writes out every entry whose
bytes changed. The order of records in the capture is therefore the order it
scanned, and it says nothing about the order BNE updated the slots. The ledger
states this in every report rather than letting ascending slot order be
mistaken for an execution order.

The `AUXL` snapshot alone cannot name source and target. Both are raw addresses
into the unit pool, and the state stream does not record that pool's base. A
capture with the projectile-constructor hook can: the hook records the source
unit slot and projectile slot at the exact constructor boundary. Its source
pointer establishes the base, after which the 152-byte unit stride resolves
the target pointer fail-closed. A live Human 13 capture proved the field names:
projectile `+0x30` is source and `+0x2c` is target. Without that hook evidence,
the ledger continues to report only relative pointer relationships.

## Fields it decodes

`LAYOUT.md` establishes the remaining distance at `+0x20`, the eight-byte
Bresenham state at `+0x18`, and the packed aim at `+0x28`/`+0x2a` against the
pinned executable. The position at `+0x00`/`+0x02` is confirmed by watching a
shot step by exactly its type's speed byte. The type at `+0x34` is derived: it
indexes the speed table at `0x00494e6c`, and type 15 moving 12 pixels a cycle
is what identifies it as an arrow. The action at `+0x36` is derived the same
way. Everything else is kept verbatim in `undecoded_hex`, because a field that
is named but never justified is the defect this repository keeps rediscovering.

## Running it

```sh
python3 tools/bne-harness/scripts/bne_java.py projectile-ledger \
  --fixture tools/bne-harness/work/corpus/campaign-1800/cases/retail-xhuman-10-idle.bnefx \
  --through 50 \
  --case retail-xhuman-10-idle
```

Adding `--survey` checks the bundle still has the bytes the corpus index
sealed, and refuses it otherwise. Adding `--java-causal` supplies the Java
side. Each run writes `PROJECTILE-LEDGER.json`, `PROJECTILE-LEDGER.md`,
`NEXT.md`, and a manifest authenticating every input and output, beneath a
content-addressed run root in `.bne-projectile-ledger/`.

Exit status is 0 when the two lifecycles agree, 1 on a named mismatch, and 2
when the comparison cannot be made at all.

## What it found

Run against the sealed corpus, two things came out that were being assumed
otherwise. Both are stated below at the strength the evidence carries, which
is less than the first draft of this document claimed.

**The captured pool-count global is 400, in every case examined.** The tracer
reads BNE's projectile-pool count from `0x004ae268` when it takes a snapshot,
and that global held 400 in `retail-xhuman-10-idle`,
`retail-xhuman-12-idle` and `retail-human-13-idle`. New-game setup at
`0x00420520` is documented as choosing 200 for single-player and 400 for
multiplayer, and the single-player figure had been carried into the parity
work; these captures did not take it.

What that does *not* establish is that the allocator scans 400 entries.
`LAYOUT.md` records the constructor reading its count from the same global,
which if correct would connect the two -- but that is a static reading from
another source, and nothing in the capture measures the allocator's scan
bound. Treat 400 as a captured runtime value until someone verifies the
allocator against the pinned executable.

**No cycle examined ended with a previously occupied lower slot free while a
projectile created that cycle sat above it -- except these.** In
`retail-xhuman-10-idle` it happens at cycles 14 and 42; in
`retail-xhuman-12-idle` at 13, 24, 31 and 35, with two lower slots involved at
cycle 35.

The conservative reading is **strong evidence consistent with allocation
occurring before the observed free, assuming no hidden same-cycle lifetime.**
It is not proof. The fixture holds one snapshot per cycle, so a slot that was
freed, reallocated and freed again inside a single cycle would look identical
to one that was simply freed: that lifetime never crosses a snapshot boundary,
and the tracer only counts a generation across a boundary, so it leaves no
mark. The reading also assumes the allocator scans from slot 0.

Strengthening it to "the free runs after the allocation" needs native call or
event evidence ordering the constructor against the free within a cycle. No
such evidence is available locally, so the ledger reports the observation and
the qualified conclusion separately, and does not merge them.

Neither result should be read as a universal rule. They are what these sealed
captures show across the windows examined, and the ledger prints the window it
read so a wider claim has to be made against a wider run.

## The Java side

### Interrupted presentation shots

The engine can allocate a presentation placeholder before attack opcode ten,
but that object is not yet a retail projectile: it has no constructor debit,
no start cycle, and no motion. It is owned by the attack order. Destroying the
firer before opcode ten now cancels the placeholder and releases its fixed
pool slot; if the constructor was already debited, interruption arms the real
shot instead. Load rejects the legacy impossible combination of a pending
placeholder with no source unit.

The regression pair distinguishes the two contracts. A killed troll before
opcode ten leaves no missile or occupied pool slot. A live troll firing at a
2x2 destroyer produces an axe which crosses pixels, keeps its launch facing
for the entire flight, lands, and damages the ship. This prevents a sound-only
attack from being accepted as visual lifecycle coverage.

The opt-in playtest adapter now emits one semantic row for every constructed
missile on every cycle and one final `present=false` row when it leaves the
pool. Each row carries cycle, stable creation identity, type, source, target,
position, frame and remaining distance. Pending presentation placeholders are
excluded until the retail constructor boundary is actually crossed.

Native uses fixed slot plus generation as its local identity; Java uses a
creation ordinal. The combat compiler canonicalizes both by birth order only
after source/target/type are authenticated, so allocator-local numbers never
become a false parity requirement. Human 13 now certifies an axethrower shot
at create c18, first flight c19 and impact/damage c25 on both engines, with
exact native/Java observation, result and causal order. Older
`projectile-created` lines omit the pool slot; those births pair to newly
occupied constructor types in slot order, and a hooked birth's `+0x30`
establishes the unit-pool base that names later unhooked tower arrows.

Type 21, the catapult/ballista impact sprite, is live from slot occupancy
until FREE. Its remaining distance is 0 for the whole hold. Flag `0x04` is
not the live bit: Human 13 sets it two cycles after birth, and Human 7 never
sets it. Persistent occupants of types 19, 20 and 28 stay allocated from
cycle 1 with remaining 0 and the same `0x00`/`0x02` flags; they are not live
shots. Counting only remaining-distance or `0x04` hid Human 7's impact and
made the sealed pool look empty at fixture 34 while Java still held the
constructed sprite.
