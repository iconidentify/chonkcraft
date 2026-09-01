# BNE 2.02b oracle layout

This file records why the tracer reads each address. It is deliberately tied
to one executable identity; none of these addresses are assumed to apply to a
third-party wrapper, translated retail executable, or any other build.

## Target identity

- Source: Blizzard `War2Patch_202.exe`
- Updater SHA-256: `194cdb4ea37aed678b095769ed9fc741d41d4d78f937fbee37734e9d6da5de19`
- Updater MD5: `1d29f0793e45457548ba35b6b7692dd1`
- Target: `Warcraft II BNE.exe`, 712,704 bytes
- Target SHA-256: `b0e914a9cb7dcc81a205e700a9bb0a1d0649df19d459388051ba170783d2c807`
- PE image base: `0x00400000`; relocations stripped
- PE timestamp: 2001-05-21 22:52:20 UTC
- Version resource: `Version 2.02b`

The official updater is an MPQ self-extractor. `prepatch.lst` launches
`bnupdate.exe`; `patch.lst` names fourteen full replacement payloads. Each is
wrapped in a 24-byte Blizzard patch header: signature `18 00 04 01`, target
length at offset 12, FILETIME at offset 16, then the complete replacement
file. `MpqTool extract-patch` verifies and removes that header and emits a
manifest. No Blizzard file is committed to this repository.

## Timed simulation boundary

The five bytes at `0x00421238` are:

```text
e8 d3 0e 03 00    call 0x00452110
```

`0x00452110` is BNE's fixed-stride full unit pass. The executable also calls it
from initialization paths at `0x00420bc7` and `0x00420ca6`; patching the
routine itself therefore invents cycles while the map is being built. The
tracer instead replaces only the `call rel32` above, which belongs to the
active timed game loop. It checks all five original bytes and the decoded
target before writing anything.

The wrapper applies commands, calls the original unit pass, then snapshots.
Cycle one is consequently the state after the first timed unit update of the
playable map. Later per-cycle global calls remain in BNE's parent loop after
this boundary, so the address—not the vague phrase "end of frame"—defines the
comparison point.

## Synchronization state

The gameplay RNG state is the 32-bit word at `0x004a48dc`. Its routine begins
at `0x004534c0` and is the familiar ANSI LCG:

```text
state = state * 0x41c64e6d + 0x3039
return (state >> 16) & 0x7fff
```

This routine has call sites throughout unit actions and spell logic. It is
distinct from the C runtime per-thread RNG at `0x0047cc4d`; the trace records
the former because it is synchronized gameplay state.

The diagnostic tracer guards the entry bytes `a1 dc 48 4a 00` before replacing
them with a near jump to an exact C reproduction. Each `sync-random` event
records the active cycle, caller, seed before/after, and result. The wrapper is
enabled only after scenario loading; an unknown executable signature is never
patched.

BNE also has a second LCG first observed while constructing units. Its state is
the word at `0x004d40ec`; `0x00479820` advances it with:

```text
state = state * 0x015a4e35 + 1
return (state >> 16) & 0x7fff
```

The PUD unit constructor at `0x00451b50` consumes this generator at
`0x00451d2e` for facing and at `0x00451f48` for the initial animation delay.
The stream remains live after construction: the Still dispatcher, projectile
damage/offset constructors, and projectile motion actions consume it too.
Those values are often cosmetic, but sharing the stream with critter wandering
makes a missing visual draw become a later semantic movement divergence.
Its seed normally comes from master state `0x004d40f0`. New-game code can seed
that master from `time(NULL)` at `0x0041f751`, then copies it through
`0x00479880`/`0x00479810` into the construction RNG. Installed-game state can
skip the conditional `time(NULL)` call, so intercepting that call alone is not
enough.

The oracle therefore accepts an explicit unsigned 32-bit initialization seed
(`--seed`, default 1). It forces the master at the unconditional new-game
wrapper and pins `0x004d40ec` immediately before the guarded scenario-loader
call at `0x0041face` (`e8 1d cc 00 00`, target `0x0042c6f0`). This happens
before any PUD unit constructor runs. The tracer records one
`initialization-seed-applied` marker there, and validation rejects a run if the
marker is absent, duplicated, or different. The synchronized gameplay RNG at
`0x004a48dc` is not replaced.

## Players

The controller byte at `0x004acbac + player` determines active slots; value 3
is nobody. Resource banks are 32-bit arrays. These addresses all have direct
read/write references in the pinned executable; for example, resource debit
and credit paths index them as `base + player * 4`.

| State | Address |
| --- | --- |
| Gold | `0x004abb18` |
| Lumber | `0x004acb6c` |
| Oil | `0x004abbfc` |

Schema 1.1 also captures the following arrays at the same boundary. Counts are
16-bit per player, technology levels are one byte per player, and availability
or learned masks are 32-bit per player.

| State | Address |
| --- | --- |
| Food limit / all units / all buildings | `0x004adc6c` / `0x004adacc` / `0x004ada04` |
| Rescued / lost units / lost buildings | `0x004acc30` / `0x004ad3b8` / `0x004acbbc` |
| Unit kills / building kills | `0x004ad378` / `0x004aced0` |
| Arrows / swords / shields | `0x004abd90` / `0x004acea4` / `0x004abf38` |
| Ship attack / ship armor / catapult damage | `0x004ace94` / `0x004abb80` / `0x004abd68` |
| Ranger / marksmanship / longbow / scouting | `0x004ace2c` / `0x004acc64` / `0x004ab9b8` / `0x004acbdc` |
| Allowed units / upgrades / spells | `0x004acb28` / `0x004acef4` / `0x004acbec` |
| Learned spells | `0x004abf4c` |

The ready-worker callback at `0x00439280` reads three 16-bit counters for the
player. Worker state byte 32 uses bit 1 for assigned-to-gold and bit 2 for
assigned-to-lumber.

| Worker count | Address |
| --- | --- |
| Total | `0x004addcc` |
| Assigned to gold | `0x004b501c` |
| Assigned to lumber | `0x004b503c` |

The native resource-worker finder at `0x004386f0` first filters by terrain
component and resource type. `0x004384c0` selects the nearest depot using the
native distance function, and `0x004393f7` scores a mine as depot distance
plus half the worker distance. This callback does not run ChonkCraft's A*-based
reachable-depot test.

## Units

`0x004aec94` holds the unit-pool pointer and `0x004ae270` its initialized slot
count. Slots are 152 (`0x98`) bytes. The tick routine itself advances through
the pool by `0x98`, so the trace preserves slot order. In particular,
`0x00452110` reads the count at its first instruction and the pointer six bytes
later. Some community mod headers label addresses 80 bytes earlier; those
addresses have no references in this executable and produced loading-state
garbage, so the oracle intentionally does not inherit them.

| Field | Byte offset | Width |
| --- | ---: | ---: |
| Animation (action-to-animation slot) | 8 | 1 |
| Tile X | 24 | 2 |
| Tile Y | 26 | 2 |
| Collision / refuse nibble | 29 | 1 |
| Lifecycle flags | 30 | 1 |
| Hit points | 34 | 2 |
| Type | 39 | 1 |
| Owner | 44 | 1 |
| Order | 46 | 1 |

Free (`0x01`) and dead (`0x04`) slots are omitted. Hidden/off-map (`0x08`)
slots remain and receive the trace's `removed` marker. Type bytes use the PUD
unit enumeration already represented by Java's `PudUnitTypes`; BNE order bytes
are reduced to the same coarse action vocabulary used by the parity differ.

`FUN_0040a9d0`'s person `HitUnit` helper selection uses an inclusive unit-cache
rectangle, not symmetric footprint distance. The naval type flag changes the
band from two to four at `0x0040aa89`--`0x0040aaa2`; the struck unit's top-left
coordinate and type width/height form the four rectangle edges through
`0x0040aaef`. The cache lookup at `FUN_0040a2b0` subtracts three only from the
north search edge (`0x0040a2b4`) and leaves the south edge unchanged. Its
returned rows are filtered by the candidate's top-left X at
`0x0040ab73`--`0x0040ab8e`. Expansion Orc 11 supplies both orientations: around
struck destroyer slot 1506 at `(10,42)`, slot 1525 at `(6,36)` is selected while
slot 1485 at `(8,50)` is not; around slot 1493 at `(12,44)`, the south edge is
49, so slot 1485 at y=50 is again excluded. Treating four empty tiles between
the two hull footprints as inside incorrectly banks Attack at fixture 360 and
promotes it at 361.

`AiHelpMe`'s hidden-attacker naval handoff ranks the eligible roaming warships
before testing whether the winning hull already owns the guard rendezvous. An
identical live order is a no-op; it does not remove that nearest hull and offer
the same guard to the next-nearest candidate. Expansion Human 7 seals both
forms of the rule. Submarine slot 1511 keeps its pointer to destroyer slot 1420
after the repeated fixture-155 hit, and still owns that pointer when the guard
is already dying at fixture 355. The latter hit therefore leaves roaming
destroyer slot 1562's route `[E,SE,E,E,SE,E]` intact. Filtering the assigned
submarine before the distance reduction incorrectly selected slot 1562,
cleared its route tail, and delayed its southeast stride from fixture 414 to
417.

Animation slot **3** is Move and **4** is Attack (BNE action-to-animation
table). Wall-follow soft-clear at `0x4500f0` (`0x4501bc`–`0x4501d3`) clears
map occupancy bit `0x100` only when the type tables allow and either the
animation byte is 3 or lifecycle word high bit `0x40` at `+0x1f` is set, and
**refuses** soft-clear when `unit[0x1d] & 0xf0` is nonzero. Sealed free-scan
records show that high nibble matches the collision counter (1→`0x10`,
2→`0x20`, 4→`0x40`); zero nibble units soft-clear, nonzero stay solid. Map
flag `0x8000` is the pathfinder goal mark written by `0x4508f0` (not the
LegacyEngine `BUILDING` occupancy bit).

The direct route writer and its shortcut view remain movement-layer specific
when that soft-clear applies. Human 12 supplies the positive and negative
witnesses in one mission. At fixture 402, unarmed zeppelin slot 1503 draws
toward `(16,0)` while moving zeppelin slot 1570 occupies the direct ray at
`(24,6)`. Native rejects that air body and wall-writes
`[NW,NW,NW,W,W,W,W,NW]`; treating it as absent writes the open
`[NW,W,NW,NW,W,NW,W,NW,NW,W,NW]` ray and exposes west instead of northwest at
fixture 422. At fixture 2, slot 1559's doubled west ray overlaps moving ground
peons but remains `[W,NW,W,NW,W]`. Re-hardening every softened unit therefore
mistakes cross-layer ground occupancy for an air wall; retaining only softened
occupants on the flyer's own movement layer satisfies both boundaries.

The collision/refuse high nibble also owns route retirement in
`FUN_004379e0`. The function increments `word[unit+0x1c]` by `0x1000` at
`0x00437a0d`, copies the post-increment high nibble to `bp`, writes animation
timer fifteen, and compares `bp` with `0x8000` at `0x00437ab4`. Values below
eight continue the cached route; eight and above call `0x00450ad0`, which
parks route index twenty. Orc 12 peon slot 1507 is the authenticated saturated
witness: fixture 327 advances `0x80` to `0x90` and parks its stale south tail;
fixture 342 queries the parked route, redraws `[SE,E]`, and the common movement
writer at `0x0043798b` commits southeast.

Route-index twenty is a logical cursor park, not an erasure of the twenty-byte
buffer. Orc 8 peasant slot 1494 is the direct-ray witness: its sole south byte
remains at `unit+0x30` while the cursor stays twenty throughout fixtures
304..319 and the eighth-generation Move 15..1 band. Fixture 320 changes only
the cursor, twenty to one, while committing south; there is no route-byte write.
The wake therefore reopens that stored direct byte before any fresh route ask.
This is distinct from the saturated multi-byte stale tail above, whose wake
does query and overwrite the buffer with `[SE,E]`.

Move timer one is itself an exposed action state, not permission to run the
route wake on the callback which decrements timer two to one. Human 14 laden
peon slot 1539 retains route index five, collision byte `0x20`, and its final
south byte through fixtures 404 and 405 while Move changes `2600/2` to
`2600/1`. Fixture 406 is the next action callback: the south square is now
occupied by a collision-bearing returner, so `FUN_004379e0` advances the byte
to `0x30` and parks the cursor at twenty without moving. Fixture 407 draws
`[SE,S]`, commits southeast, and leaves route index one. Free-head controls
enter their callback with timer one already exposed and therefore do act on
that callback: expansion Human 7 slot 1451 consumes northeast at fixture 286,
and Orc 5 slot 1529 consumes southeast at fixture 289. The discriminant is the
Move timer at callback entry, not whether the cached head will be retained or
parked.

The coarse order byte is not sufficient to identify that Move action state.
In the sealed expansion Human 12 fixture-204 branch witness, map occupant
(12,87) resolves through `0x45019b` to slot 1358. Its order byte is 3 (Move),
but its animation/action byte at offset 8 remains 2 through fixtures 203..205;
the `0x4501c8` comparison therefore keeps it solid. The action byte becomes 3
only at fixture 206. By contrast, slot 1363's same-pass Still-to-regroup-Move
promotion at fixture 199 is transiently soft while its two-tick pending-Move
delay is freshly armed. The fixture-204 wall trace rejects that occupied cell
and ultimately stores its optimized sixteen-heading route at `0x4505ed`.

The recurring-regroup soft view belongs only to the fresh route generation.
Expansion Human 12 axethrower 1359 stores `[N,NE,SE,E,E]` at fixture 252 and
raises its collision byte from zero to `0x10` when the north heading refuses.
At fixture 266 its later north blocker, peasant 1385, is Move animation 3 but
already carries collision `0x40`, so the occupancy test keeps it solid. On
fixture 267 the axethrower's raw byte advances `0x10`→`0x20`, its route index
at offset 126 becomes 20, and its animation timer is one; the low refusal
nibble remains zero. The following route query fails and fixture 268 records
action/order byte 2 (Still) with an empty route. The authenticated order branch
trace writes that 2 at `0x00453097` after the failed Move path calls the setter
from `0x0043789a`. Thus a moving worker is not an unconditional regroup
soft-clear: mover and blocker collision generations distinguish the initial
cooperative route from its hard retry.

Behavior-two land Patrol has a separate cooperative worker handoff. Expansion
Human 12 ogre 1356 is settled at (11,86) after draining its old route, while
harvesting peon 1386 owns (10,85) in Move action state 3 with nonzero sub-tile
motion. On fixture 255 the Patrol point writer stores `[NW,NE]` through that
worker. The following live-occupancy visit does not park route index 20:
native retains index zero, writes collision byte `0x10`, and installs the
ogre's Move program at sequence 586 with timer 15. The route and collision
byte remain unchanged while that timer falls to one on fixture 269. Fixture
270 then consumes NW, changes the ogre's tile to (10,85), and advances the
route index to one. This establishes that planner transparency and execution
occupancy are two halves of one refusal protocol; treating the first occupied
heading as a generic refusal loses the cached route and delays the step seven
cycles.

The same two-view protocol survives stale port-only refusal state during a
saturated target replacement. On expansion Human 12 fixture 271, native slots
1479, 1480, 1506, and 1489 are in Move action 3 with raw collision byte zero;
the wall tracer clears their land occupancy even though their Java twins still
carry separate refusal proxies. Native grunt 1492 continues its paid clockwise
face and writes
`E,E,E,SE,SE,SE,E,E,E,NE,NE,NE,SE,S,SE,SE,S,S,SW,SW`. Occupancy is restored
before Move consumes the buffer, so slot 1479 on the first east square refuses
that head. The router's old raw collision byte `0x80` is retired with the guard
tower quarry and the new refusal writes `0x10`, while the route index remains
zero and the animation timer becomes fifteen. Thus the route writer's soft
view does not imply that execution may enter the same square.

Wall-follow step selection at `0x450114`–`0x45020f`: an out-of-bounds
candidate step fails the entire face (`jae 0x450315` at `0x45015c` /
`0x45016a`) without rotating; blocked terrain only rotates the heading until
the first returns. A free cell with map flag `0x2000` also fails the face
(`0x450203`); that bit is **not** LegacyEngine `AIR_UNIT` occupancy in the Java
port (mapping it regressed human-14@8).

## Deterministic campaign bootstrap

The main loop reads its screen state at `0x0042a348` with the guarded bytes
`e8 33 61 ff ff` (`call 0x00420480`). When a requested retail campaign has
been validated, the tracer replaces this one call and returns state 3 once.
Before doing so it sets the same globals used by the stock campaign screens:

| State | Address |
| --- | --- |
| Campaign selector (`0..51`) | `0x004ad350` |
| Campaign resource ID | `0x004abda2` |
| Human/Orc side | `0x004abb7c` |
| Main-loop state | `0x004ae480` |

The 2.02b path table proves that selectors alternate Human/Orc. Selectors
`0..27` map resource IDs `0x52c8..0x52e3` to the 28 original-campaign PUDs;
selectors `28..51` map `0x53c6..0x53dd` to the 24 expansion PUDs. For example,
`Campaign\Orc\Orc01.pud` is selector 1 and resource `0x52c9`.

Campaign state 3 normally opens an interlude and objectives dialog before the
game. The bootstrap temporarily raises the UI-skip flag at `0x004acc2e`. A
second guarded hook at `0x0042a4a1` (`e8 3a 52 ff ff`) clears that flag before
calling the original `0x0041f6e0` new-game routine with its untouched
arguments. BNE then resolves the PUD through Storm, reads every section, builds
the unit pool, and enters its normal gameplay loop. The trace records the
actual PUD opened by Storm and the runner rejects a mismatch with the requested
scenario.

## Scripted command boundary

Commands are applied immediately before the original update that will be
recorded as their named cycle. Both supported actions use the 2.02b routine at
`0x00451070` (`GiveOrder`). The routine's first six bytes (`8b 44 24 04 33 c9`)
and the selected handler's executable mapping are checked at the moment of use.

`move` reads entry 3 of the stock order-function table at `0x00495fcc`. `stop`
reads entry 2, `patrol` entry 5, `attack` and `attack-move` entry 8,
`harvest` entry 23,
`return-goods` entry 24, `repair` entry 27, and `attack-ground` entry 17. Those indices are the same byte the synchronized
`0x13` dispatcher at `0x00475f80` loads as `ORDER_FUNCTIONS[packet[7]]` before
it calls `GiveOrder` at `0x0047617f`. Replay-pack-1 has 88 stop packets (dest
`0,0`, target `-1`), 1,627 patrol packets at index 5, 221 attack packets with
a live target, a harvest special-case at index `0x17` that tests worker type
flags `0x0300`, 382 return-goods packets at index 24 (dest `0,0`, target
`-1`), 225 repair packets at index 27 with a live target, and 28 attack-ground
packets at index 17 (dest xy, target usually `-1`). The one-byte `0x0C` dispatcher only jumps to the UI/speech thunk at
`0x00436ee0` and is not the scripted stop path. Harvest refuses a non-worker
actor with that same flag test so a grunt cannot become a harvester. Index 24
does not use that harvest flag test. Index 27's constructor at `0x00436a20`
installs order 27 when the target type flags carry `0x20` (building) or
`0x0400` (transport) and otherwise falls through to MOVE. The dispatcher does
not special-case that index. Index 17's constructor at `0x004367a0`
clears the unit target and installs order 17, or order 18 when that
action is refused. Index 8's constructor at `0x004366f0` installs the live
target as order 9; a null target takes the dest path at `0x00436714`,
where dest-check `0x00416bc0` installs order 11 or order 10 when the
square is refused. That dest path is the scripted attack-move click.

The harness does not write the order, target, or path fields directly. It
passes BNE the live unit pointer, tile coordinates, null unit target, and BNE's
selected handler. Before that call it requires the trace slot to exist, be live,
and belong to the local player at `0x004abf8c`. BNE therefore retains its own
order-transition delay, animation locks, pathfinder, and movement timing.

## Synchronized multiplayer command boundary

The five bytes at `0x0047800b` are:

```text
e8 90 02 00 00    call 0x004782a0
```

The target consumes `(packet_pointer, packet_length)` and dispatches every
command in that player's synchronized network-turn packet. Immediately before
the call, `0x004a70f0` is the current Battle.net participant index. BNE's
surrounding network-manager loop walks the occupied participants and calls
this boundary once for each packet. The
eight controller bytes at `0x004acbac` are the same slot-status vector stored
in InSight replays.

This was cross-checked against InSight 1.05 RC2's version-2 address table. Its
2.02b row maps recorder IDs as follows:

| InSight ID | BNE 2.02b address | Meaning |
| ---: | --- | --- |
| `0x21` | `0x004a70f0` | current network participant index |
| `0x22` | `0x0048f413` | injected pointer to the dispatch length argument |
| `0x23` | `0x0048f417` | injected pointer to the dispatch packet argument |
| `0x24` | `0x004acbac` | eight game-slot controller/status bytes |

The two `0x0048f4xx` values are InSight scratch variables, not native BNE
state. InSight replaces the guarded call above with a wrapper in its injected
code cave. During playback it writes the next replay packet length into the
live stack argument, copies the packet to its scratch buffer at `0x00481c36`,
writes that buffer address into the pointer argument, and resumes BNE. BNE
then executes the unmodified `0x004782a0` dispatcher. This proves both the
packet format boundary and an injection design that needs no UI timing.

The same dispatcher also carries lobby setup traffic. The replay wrapper does
not consume a schedule record until the guarded game-tick hook has observed a
live unit pool and emitted `match-ready`. Pre-game packets always pass through
unchanged. Without that boundary record zero could be spent on a harmless
lobby packet, making a correct playback fail before the first simulation turn.

## Raw fixture capture

After the original `0x00452110` unit pass returns, the tracer copies every
changed 152-byte slot into the binary state stream. The first timed cycle is a
full 1,600-slot checkpoint; later cycles are byte-for-byte deltas. A slot's
lifetime generation increments whenever it moves from free/dead to live, so
slot reuse cannot merge two units. The capture reads state only and never
writes into the pool.

The same cycle record includes the gameplay RNG and all sixteen controller,
gold, lumber, and oil records. The fixture validator reconstructs the pool and
derives seed, active-bank, and live-unit core digests independently of the text
trace. All three must match before the run can be sealed. The complete binary
layout and its current coverage boundary are in `FIXTURE.md`.

BNE's own projectile constructor at `0x00410000` reads the pool count from
`0x004ae268`, the pool pointer from `0x004aec98`, advances by 64 bytes, and
tests byte 53 bit 0 for a free slot. New-game setup at `0x00420520` selects a
capacity of 200 for single-player or 400 for multiplayer. The timed unit pass
also updates that pool before returning, so the existing snapshot boundary
observes updated units and projectiles together. Schema 1.1 checkpoints and
then delta-encodes the complete pool.

The map size is the 16-bit value at `0x004acc2c`. Pointers at `0x004ad61c` and
`0x004ad610` address row-major 16-bit visual cells and simulation square flags.
The pinned executable indexes both as `base + tile * 2`; schema 1.1 preserves
both values whenever either changes. These addresses and the extended-player
arrays were cross-checked against direct references in the authoritative
binary, rather than transplanted from a shifted third-party data layout.

### DoRightButton dest-spread

`DoRightButton` at `0x0043b870` calls `0x0043e330` once with the clicked tile,
then `0x0043e530` per selected unit before `GiveOrder`. `0x0043e330` first
asks `0x00416bc0(click, 0xc)` -- a square-flags mask of the word at
`0x004ad610` -- and returns without writing flags when that mask is nonzero.
Otherwise it walks the nine-slot selection at `0x004bb728`, records min/max
and sum, and if the click is outside that box it sets:

- flag bit 0 and word `0x004bb724` when `max_x - min_x <= 3`, with
  `x_add = click_x - (sum_x / count)`
- flag bit 1 and word `0x004bb722` when `max_y - min_y <= 3`, with
  `y_add = click_y - (sum_y / count)`

`0x0043e530` then replaces dest on each armed axis with
`unit.tile + add`, clamped to `[0, word 0x004acc2c - 1]`. A click inside the
box, or an axis wider than three tiles, leaves dest as the click. Human 1
footmen at `(21,5)` and `(17,7)` onto `(25,28)` therefore dest-spread to
`(25,27)` and `(25,29)`; adding the third at `(10,13)` makes both spans
greater than three and all three dests stay `(25,28)`.

### Projectile constructor and motion RNG

The fixed-position projectile constructor at `0x0040fdc0` reaches the damage
helper at `0x004182b0`; the arrow branch returns at `0x0041834b`. It performs
no coordinate-jitter draws. The mobile-shot constructor at `0x0040fb10`
reaches its damage arm at `0x00418370`, returns at `0x00418412`, and then draws
the two coordinate offsets at `0x0040fbf7` and `0x0040fc06`. In the Human 13
fixture, fixed/max-damage catapult rocks therefore consume only the two offset
draws, while an axe consumes one damage draw followed by the same two offsets.

The mobile constructor's target-point helper at `0x0040fd50` reads the target
unit's pixel words directly from offsets `+0x00` and `+0x02`, then adds the
type-center table. It does not add the unit's retained residual movement bank.
Expansion Orc 11 fixture 393 is the authenticated witness: target slot 1512 is
at `(320,1280)` with residual `(-1,+1)`, and the following native jitter draws
`(+1,-1)` produce aim `(337,1295)`. Adding the residual a second time instead
produces Java's former `(336,1296)` aim and delays the hit past fixture 402.

The point-motion action at `0x004101f0` consumes one asynchronous RNG draw at
`0x0041025a` on each update return. The parabolic action at `0x00410260`
consumes draws at `0x004102cd` and `0x00410316`. The speed table at
`0x00494e0c` is one byte per projectile type; observed values include 8 for
types 13 and 14 and 12 for types 15 and 16. Remaining distance is the signed
word at projectile offset `+0x20`.

### Projectile remaining distance and Bresenham step

Both constructors finish by calling direction setup `0x00429f10` with the
packed start position, packed aim (`+0x28`/`+0x2a`), and the eight-byte state
at projectile `+0x18`. That setup stores absolute major/minor axis lengths,
half the major axis as the error term (forced to 1 when zero), and flags in
byte `+0x18+7`:

| Flag | Meaning after setup |
| --- | --- |
| `0x80` | absolute delta X was the major axis (step outputs will be swapped) |
| `0x40` | aim X was greater than or equal to start X |
| `0x20` | aim Y was greater than or equal to start Y |

Remaining distance is then `max(|dx|, |dy|)`, raised to
`(table[type] << 5)` when the factor at `0x00494e6c` is larger. Type 15
(arrow) and type 16 (axe) have factor 0; types 13 and 14 have factor 3
(minimum 96 pixels).

Each timed update of `0x004101f0` calls `0x00429fa0` on the `+0x18` state to
produce two signed step components, multiplies each by the type's speed byte,
adds them to the current pixel X/Y, subtracts the speed from remaining, and
detonates when remaining is negative (`jge` keeps a zero remaining shot alive
for one more update). The step routine itself:

1. seeds the outputs as `(outA, outB) = (-1, 0)`;
2. does `error -= minor`; when `error <= 0`, decrements `outB` and
   `error += major`;
3. swaps the outputs when flag `0x80` is set;
4. negates `outB` when flag `0x40` is set and `outA` when flag `0x20` is set;
5. applies `y += speed * outA` and `x += speed * outB`.

Human 13's first tower arrow matches this path exactly for six motion steps
from `(3872,1152)` toward `(3920,1080)` at speed 12 with remaining 72, and
detonates on the seventh step. Java's BNE profile now ports that model behind
`Missile.enableBattleNetMotion`; ordinary ChonkCraft Euclidean flight is unchanged.

### Resource-hit escape restart and RNG ownership

Read statically and confirmed with two local Branch Witness captures against
the pinned executable SHA-256
`b0e914a9cb7dcc81a205e700a9bb0a1d0649df19d459388051ba170783d2c807`.
The resource-hit handler `0x0040a5e0` calls the escape-point constructor
`0x0040a670` at `0x0040a61f`. The constructor reads the retained attacker's
direction byte at `+0x0a`, indexes direction deltas using the worker's movement
class at `+0x1c`, scales each delta by four tiles, and consumes asynchronous
RNG at `0x0040a750` and `0x0040a77f`. It adds `(random & 7) - 2` on each axis,
clamps the point to the map, and calls `GiveOrder` at `0x0040a80c`.

This is also Still's armed non-combatant hit response, not a resource-order-only
path. Still's shared handler `0x0040b010` calls the idle callback `0x0040ad30`
and then `0x0040a5e0`. The latter tests type flags `0x06000300` together with
the armed/mobile bit `0x00080000` before entering `0x0040a670`. Sappers and
dwarven demolition squads have flags `0x0a080001` and take this arm; ordinary
fighters with flags `0x08080001` do not. Orc 11 sapper slot 1573 seals the
standing form: its fixture-417 hit is converted to the constructor's temporary
Move at fixture 418, while untouched sapper slot 1575 remains Still.

When a temporary resource-hit Move settles with a second hit retained, the
worker first visits the common active-order idle callback `0x0040ad30`; its
draw at `0x0040ad53` belongs before the re-entry above. Human 8 peasant native
slot 1536 demonstrates the sequence: the callback returns `0x3290`, the point
constructor returns `0x6ddf` then `0x6d76`, and the order-point writer at
`0x0045140e` stores `(89,60)`. Omitting the callback draw shifts constructor
ownership and instead produces `(82,61)`.

The same callback belongs to the final call of a free temporary Move before
`RestoreOrder`, not only to a retained-hit restart. Human 8 slot 1536 consumes
30517 on fixture 350 before action 24 is restored. If the resumed depot route
is empty, that synchronous retry must not charge the callback again; action
24 then owns the ordinary repeating callbacks at fixtures 353, 356, and 359.
The free-restore controls end at asynchronous seeds `0x535014dc` on fixture
301 and `0xa9ecb6ac` on 316, while the final restore leaves critter slot 1492
its native choice/direction pair on fixture 358 and ends at `0x45df3775`.

## Order dispatch and order attributes

Read statically from the pinned target while diagnosing Human 13's ogre in
pool slot 1519, which native leaves Still at fixture cycle 29 and turns Attack
at 34. Verified against SHA-256
`b0e914a9cb7dcc81a205e700a9bb0a1d0649df19d459388051ba170783d2c807`.

The timed unit update dispatches on the unit's order byte at record offset
`0x2e`:

```text
0x0045256d    xor edx, edx
0x00452570    mov dl, byte [eax + 0x2e]
0x00452573    call dword [edx*4 + 0x495ed8]
```

`0x00495ed8` is therefore a 32-bit order-handler table indexed by the order
byte. The first eighteen entries are:

```text
 0 0x004188b0   1 0x004188c0   2 0x0040b010   3 0x0040b4a0
 4 0x0040b4a0   5 0x0040b5a0   6 0x0040b4f0   7 0x0040b550
 8 0x0040b5d0   9 0x0040b010  10 0x0040b4a0  11 0x0040b010
12 0x0040b010  13 0x0040b010  14 0x0040afb0  15 0x0040b010
16 0x0040b010  17 0x0040b010
```

Still (2) and Attack (12) share `0x0040b010`; the two Patrol orders (3 and 4)
share `0x0040b4a0`. The shared handler re-reads the same order byte and indexes
a 16-bit attribute word table:

```text
0x0040b01d    mov al, byte [esi + 0x2e]
0x0040b020    mov bx, word [eax*2 + 0x496234]
```

`0x00496234` words for orders 0 to 17:

```text
 0 0x0000   1 0x0000   2 0x148a   3 0x0004   4 0x0206   5 0x020e
 6 0x0004   7 0x148e   8 0x0000   9 0x010c  10 0x2005  11 0x012c
12 0x0b4e  13 0x0082  14 0x0002  15 0x0902  16 0x1902  17 0x011c
```

Two bits of that word are consumed immediately. Bit `0x2000` is read only when
the global at `0x004ae26c` is non-zero, and bit `0x0080` gates a call to
`0x0040ad30` behind a second test of `byte [ecx*4 + 0x004cf574]` indexed by the
unit's `0x27` byte. Still's `0x148a` has `0x0080` set and Attack's `0x0b4e`
does not.

`0x0040b010` calls the stationary-combat routine `0x00453130` from two sites,
`0x0040b157` and `0x0040b2c9`. `0x00453130` itself opens by testing the same
`0x004ae26c` global and comparing the unit's `0x8d` byte against `0x3c`.

The authenticated Contrastive Decision Miner capture closes the later order
promotion boundary:

```text
0x00452587    call 0x00452ef0
0x00452eff    mov  0x2f(%esi), %al         ; read next_order
0x00452f02    cmp  $0x3c, %al              ; 60 / no next order
0x00452f04    jne  0x00452f55
...
0x00452f99    mov  0x2f(%esi), %dl         ; retain queued order
0x00452f9c    movb $0x3c, 0x2f(%esi)       ; clear next_order
0x00452fa0    mov  %dl, %al
0x00452fa2    mov  %dl, 0x2e(%esi)         ; promote into order
```

Focus-scoped accepted (34), rejected (29), and held-out rejected (24) visits
all predict the branch with `unit.next_order != 60`. The accepted writer is
reached only from caller `0x00452587` (return `0x0045258c`); the earlier call
at `0x0045248c` is a separate non-promoting visit. This proves that order 12
arrives through `next_order`, rather than as an immediate write.

The upstream producer of that `next_order` is recorded in the next section.
The meanings of `0x004ae26c`, of the `0x004cf574` type-flag table, and of the
individual order-attribute bits remain unresolved, and naming them without a
further contrast would be a guess.

## Auto-acquisition scans a pixel-Y index with a tile-Y key

Proved by the upstream Decision Miner contrast on Human 13's ogre slot 1519 at
fixture cycles 24, 29 and 34, against SHA-256
`b0e914a9cb7dcc81a205e700a9bb0a1d0649df19d459388051ba170783d2c807`.

A Still unit reaches target acquisition through this chain:

```text
0x00452573    call dword [edx*4 + 0x495ed8]   ; order handler, order 2 -> 0x0040b010
0x0040b0ca    push esi
0x0040b0cb    call 0x0040a830                 ; acquisition driver
0x0040a949    call 0x00409ff0                 ; target search, second argument 0
0x0040a953    je 0x0040a9c1                   ; search returned nothing
0x0040a9b0    call 0x004513d0                 ; adopt target, then call the callback
0x00436904    call 0x00453130                 ; callback queues order 12
0x0045324d    mov byte [esi + 0x2f], cl       ; next_order = Attack
```

`0x0040b010` chooses the callback at `0x0040b09d`: `0x004368d0` when
`byte [0x004acbac + player]` is 1, otherwise `0x004368c0`. The callback queues
order 12 only when the unit already carries a target at `+0x88`, which
`0x004513d0` has just written.

`0x00409ff0` computes a square reaction rectangle centred on the unit's tile.
The radius is `byte [0x004cf024 + type]` for a controller-type-1 player and
`byte [0x004cf170 + type]` otherwise; `0x00453480` separately stores the unit's
attack range at `0x004ab9ac`. The rectangle is written to
`0x004ab9b4`/`0x004ab9b0`/`0x004ab9b2`/`0x004ab9ae` (left, top, right, bottom)
and clamped to the map. Human 13's ogre is type 7 on a controller-type-1
player, and its measured radius is 6.

The search then does **not** iterate units by range. `0x0040a2b0`
binary-searches a global unit index for the sub-range whose **tile** Y
(`word [unit + 0x1a]`) lies in `[rectTop - 3, rectBottom]`, and only those
entries are rectangle-tested at `0x0040a1e5` and scored by `0x0040a4b0`:

```text
0x004bf1d8    unit index, an array of unit pointers, NULL terminated
0x004c0adc    index length, recomputed by the sort
0x00453ae0    stable insertion sort, key word [unit + 2] (pixel Y)
0x00452720    the only call to it, after the whole per-cycle unit pass
```

The sort keys removed units (`byte [unit + 0x1e] & 7`) to `0xffff8000` so they
collect at the front, which is what `0x0040a2da` skips past. It swaps only on a
strict inversion, so entries sharing a pixel Y keep the order they already had.

**The index is ordered by pixel Y and the search compares tile Y.** A unit that
has snapped its tile ahead of its pixel position therefore sorts after a unit
whose tile Y already left the band, the binary search stops at that inversion,
and the later unit is invisible to the scan even though it stands inside the
rectangle. Because the sort runs after the unit pass, a scan reads the previous
cycle's ordering.

Human 13's ogre is exactly that case. The knight in slot 1500 stands on
(119,25), Chebyshev 6, from fixture cycle 25 through 40, and the ogre's
rectangle is identical at 24, 29 and 34:

| cycle | knight pixel Y / tile Y | blocking entry | index entries scanned | enemy found |
|---|---|---|---|---|
| 24 | 832 / 26 | none; tile Y 26 is outside the band | 42 | no |
| 29 | 822 / 25 | slot 1505 at pixel Y 803, tile Y 26 | 41 | no |
| 34 | 809 / 25 | none; knight now sorts ahead of 1505 | 42 | yes |

Replaying the sort and the binary search over the sealed fixture reproduces all
three scans exactly -- 42, 41 and 42 entries, every per-entry rectangle verdict
in order, and the scored entry appearing only at cycle 34, at index 41.

Only cycles 24, 29 and 34 were captured, so how often a Still unit reaches the
scan is **not** established here. Replaying the band across 22 to 40 shows the
knight entering the window at cycle 30 and staying, so a scan on every cycle
would have taken it at 30. Native took it at 34, which is consistent with the
five-cycle scan cadence the engine already keeps and rules out a per-cycle
scan. The two rules together are what fix the cycle: 24 and 29 are scan cycles
where the band hides the knight, 30 to 33 are not scan cycles, and 34 is the
first scan cycle where the band shows it.

## Armed-flyer Patrol queues direct Attack behind its committed stride

The sealed expansion Orc 11 raw stream supplies the action boundary for
gryphon rider slot 1589. Fixture 405 settles the prior doubled stride and
reconstructs Patrol's Still cursor at sequence `2233`, timer 3, with current
and next orders `4/60`. After the complete Still body, fixture 413 changes all
of these fields in one unit visit:

- tile `(18,32) -> (16,34)` while pixel position remains `(576,1024)`;
- next order at `+0x2f`, `60 -> 12`, while current order at `+0x2e` stays 4;
- order point at `+0x84/+0x86`, `(2,54) -> (10,40)`;
- target pointer at `+0x88`, null to non-null;
- route index at `+0x7e`, `20 -> 1`, with `[SW,SW,SW,SW]` at `+0x30`; and
- Still sequence `2237/1 ->` Move sequence `2259/1`.

Thus acquisition is part of Patrol's opcode-zero constructor visit, and the
new direct Attack is queued behind the stride that same visit commits. Current
order remains Patrol through fixture 436 while the southwest pixels drain.
Fixture 437 moves pixels `(518,1082) -> (512,1088)`, promotes current/next
orders `4/12 -> 12/60`, and opens the Attack cursor at `2313/3`. A generic
periodic scan during the Move body is therefore observably wrong twice: it
fires too early and produces weak position AttackMove instead of queued direct
Attack. The fixture-cycle boundary corresponds to Java internal cycle plus two
for this mission: fixture event `F` executes at Java internal cycle `F + 2`.

The same stream closes the promoted Attack's next ownership boundary. The
gryphon remains at `(16,34)` with its three retained southwest bytes while
Attack counts `2313/3,2,1` through fixture 439. Fixture 440's opening OP0
enters the body on the same callback and exposes `2317/6`; the body then pays
four six-visit waits at `2317`, `2321`, `2325`, and `2329`. Fixture 463 still
records Attack at `2329/1`. Only the fixture-464 tail goto reaches the next
OP0, releases the retained Patrol route, commits southwest to `(14,36)`, and
exposes Move `2259/1` with two route bytes left. Thus the first out-of-range
Attack selected by an armed-flyer Patrol owns the complete compact Attack body,
not just its three-call constructor, before chase can spend CUnit's route.

## Moving siege can surrender a player-clicked building to the free scan

Authenticated UI captures close the player-control question for both siege
types.  Fixture
`afb1f39311ef857ec3275ae79e07bf06aa6492d44b978ee07de964f869ce0600`
selects Human ballista slot 1488 and right-clicks great hall slot 1436 at cycle
5.  The UI fanout writes Attack (`order=2`, `next_order=9`) and retains the hall
while moving for 228 cycles.  At cycle 233, with the hall still alive, the
moving attack callback replaces it with nearby enemy grunt slot 1505.

The static path is shared combat code, not a ballista special case:

```text
0x004376c0    moving attack callback
0x00437901    call 0x00409ff0              ; ordinary free target scan
0x00437920    call 0x004513d0              ; publish replacement target
0x00437925    or byte [unit + 0x1f], 2     ; mark automatic ownership
```

Orc fixture
`5f9d92f5f3c700ab8818af0cf857908613cbbe92cc4d9d5806f01d76d18d9d3d`
independently selects catapult slot 1599 through the retail UI and right-clicks
building slot 1538.  Retail accepts the same Attack transaction and the
catapult moves while retaining the building target.  The per-type table gives
ballista type 4 and catapult type 5 the identical `00084004` word, and both
dispatch through the callback above.  There is no native branch that preserves
an explicit building click: the reaction scan may steal it from either siege
engine.  ChonkCraft's live-game target guard is therefore a deliberate control
overlay; parity fixtures must leave that overlay disabled.

## A Still unit spends two asynchronous draws on a nearby random point

Read statically from SHA-256
`b0e914a9cb7dcc81a205e700a9bb0a1d0649df19d459388051ba170783d2c807`, to explain
a dynamic fact: the XOrc 8 capture records twelve cycle-0 asynchronous draws
from return addresses `0x00427986` and `0x004279B3`, six from each. Human 7
records one of each and Orc 12 none, which is why their idle streams align with
this engine's and XOrc 8's does not.

Both return addresses sit inside one routine at `0x00427970`:

```text
0x00427970    mov ebx, dword [esp + 8]      ; unit
0x00427975    cmp byte [ebx + 0x2e], 2      ; order == 2 (Still)?
0x00427979    jne 0x00427a0b                ; anything else returns without drawing
0x00427981    call 0x00479820               ; draw one  (returns at 0x00427986)
0x0042799a    div esi                       ; rand % (word [0x004acc2c] / 2)
0x004279a8    sub esi, eax                  ;   less word [0x004acc2c] / 4
0x004279aa    add si, word [ebx + 0x18]     ;   plus unit.x
0x004279ae    call 0x00479820               ; draw two (returns at 0x004279b3)
0x004279c8    sub edx, ecx                  ; the same arithmetic on the other axis
0x004279ca    add dx, word [ebx + 0x1a]     ;   plus unit.y
0x004279ce    test si, si                   ; then both axes are clamped to the map
```

So a unit whose order byte is 2 spends **two** draws, one per axis, on a point
offset from its own tile by a uniform value in `[-g/4, +g/4)` where `g` is the
global word at `0x004acc2c`. A unit in any other order spends none.

`0x00427970` has no direct `call` anywhere in `.text`. It is reached through an
eight-entry function-pointer table in `.data` at `0x0049d91c`, as entry **4**:

```text
[0] 0x00427960 (bare ret)   [4] 0x00427970  <- this routine
[1] 0x00427e20              [5] 0x00427bf0
[2] 0x00427f60              [6] 0x00427a10
[3] 0x00428020              [7] 0x00427960 (bare ret)
```

**Correcting an earlier revision of this section**, which called this a
fourteen-entry table at `0x0049d904` with the routine at entry 10, selected by
a global byte. That was wrong twice over. `0x0049d904` is a *different, adjacent*
table, and the walk that produced "fourteen entries" simply ran through three
neighbouring tables because every slot held a plausible `.text` address. The
only writes to the global `0x004af430` store the immediates 0, 1 and 2, so that
selector cannot reach an entry 10 at all -- which is what exposed the error.
`0x0049d904`, `0x0049d910` and `0x0049d91c` are three separate three-to-eight
entry tables; only the last one dispatches this routine.

The real selector is a **field of the unit**, and the single dispatch site is a
walk over the whole unit array:

```text
0x00427554    (loop head)
0x00427562    test byte [esi + 0x1e], 0xf    ; flags low nibble must be clear
0x00427568    mov al, byte [esi + 0x5e]      ; ai_behavior
0x0042756d    je 0x0042759a                  ; zero selects no handler at all
0x00427571    mov cl, byte [esi + 0x27]      ; type
0x00427574    test dword [ecx*4 + 0x004cf574], ebp
0x0042757e    call 0x00424f70                ; a per-unit predicate; true skips
0x0042758d    mov dl, byte [esi + 0x5e]      ; ai_behavior again, as the index
0x00427590    call dword [edx*4 + 0x0049d91c]
0x0042759a    add esi, 0x98                  ; 152, one unit record
0x004275a1    jne 0x00427554
```

Every offset here matches the pinned layout this harness already uses:
`0x18`/`0x1a` are `UNIT_X`/`UNIT_Y` (24, 26), `0x1e` is `UNIT_FLAGS` (30),
`0x27` is `UNIT_TYPE` (39), `0x2e` is `UNIT_ORDER` (46), the stride `0x98` is
`UNIT_BYTES` (152), and the selector `0x5e` is **`UNIT_AI_BEHAVIOR` (94)**.
That mutual agreement is why the reading is trusted: the listing was decoded
without reference to the layout table and lands on it exactly.

So the rule is: once per cycle native walks every unit, and a unit whose flags
low nibble is clear and whose `ai_behavior` is non-zero runs handler
`ai_behavior`. Handler **4** spends two asynchronous draws, but only when that
unit's order is Still.

The XOrc 8 capture agrees: its traced unit **1560** -- the gryphon rider that
is the cycle-52 blocker -- records `behavior=4` on its `unit-ai-home` event.

Reading `ai_behavior` (offset 94) out of the sealed raw records confirms the
mapping by count, on live units only, at the earliest cycle each state stream
carries:

| Case | live units | `ai_behavior == 4` | draws at `00427986` / `004279B3` |
|---|---:|---:|---:|
| `retail-human-07-idle` | 134 | **1** | **1 / 1** |
| `retail-orc-12-idle` | 183 | **0** | **0 / 0** |
| `retail-xorc-08-idle` | 225 | **6** | **6 / 6** |

The number of units carrying behaviour 4 equals the number of draw pairs, in
all three captures, including the zero. That is what raises this from a
correlation to a mapping. XOrc 8's six are slots 1446 and 1447 (type 40) and
1550, 1560, 1581 and one more (type 42); five of the six are already Still at
cycle 1, and 1560 is the gryphon rider that blocks cycle 52.

Cross-tabulating `ai_behavior` against unit type and owner across the same
three captures shows it is **not** a static property of either:

```text
human-07  behaviour 4 -> type 41, owner 5   (1 unit)
xorc-08   behaviour 4 -> type 40, owner 2   (3 units)
                         type 42, owner 2   (3 units)
xorc-08   behaviour 0 -> type 41, owner 5   (4 units)   <-- same type and owner
                                                            as Human 7's behaviour 4
```

The same type under the same owner carries behaviour 4 in one mission and 0 in
another, so a port cannot derive the field from the unit's identity. XOrc 8 also
shows type 30 carrying behaviour 2 on two units and behaviour 6 on eight, which
rules out type alone within a single mission as well. Whatever assigns
`ai_behavior` is a runtime role decision, and modelling handler 4's draws
therefore depends on first modelling that assignment.

### What assigns `ai_behavior`

Five instructions in `.text` write a unit's byte `0x5e`, and three of them are
the exits of one routine at **`0x004275b0`**, which has **sixteen** call sites.
It is the role assigner. Its exits write four neighbouring fields together:

```text
0x004276c2   mov dword [esi + 0x58], edi   ; ai_home  (88/90)
0x004276cc   mov byte  [esi + 0x5f], dl    ; ai_marker (95)
0x004276ea   mov word  [esi + 0x5c], cx
0x004276ee   mov byte  [esi + 0x5e], dl    ; ai_behavior (94)
```

The behaviour is not computed there -- it arrives as the middle of three
`cdecl` arguments, `(unit, behaviour, 0)`. Each call site picks the constant by
testing bits of a per-unit-type flags word:

```text
0x00427160   mov eax, dword [eax*4 + 0x004cf574]   ; flags, indexed by unit type
0x00427167   test al, 0x1f                          ; none of bits 0..4 -> skip
0x0042716f   test ah, 3                             ; bits 8 or 9 -> skip
0x00427178   test ah, 4                             ; bit 10
0x0042717d   push 0 / push 3 / push edi / call 0x004275b0    ; behaviour 3
0x0042718f   test al, 8                             ; bit 3
0x00427193   push 0 / push 6 / push edi / call 0x004275b0    ; behaviour 6
0x004271a5   test al, 2                             ; bit 1 ...
```

`0x004cf574` is the same table the dispatcher consults before running a
handler (`test dword [ecx*4 + 0x004cf574], ebp` at `0x00427574`).

The bit that selects behaviour 4 is `0x02`, and the tracer already reports the
whole word: `unit-type-data` carries `type-flags`, and the XOrc 8 gryphon rider
(type 42) reads `type-flags=08080082`. Its low byte `0x82` fails `test ah, 4`
and `test al, 8` and takes `test al, 2`, which is the `push 4` arm at
`0x004271a9`.

Reading the behaviour byte out of the sealed records and naming the units says
what that bit is:

```text
behaviour 4:  balloon (40), zeppelin (41), gryphon-rider (42)   -- the air units
behaviour 2:  human-destroyer (30), battleship (32)
behaviour 6:  human-destroyer (30), human-submarine (38)
```

So handler 4 -- the routine that spends two asynchronous draws on a point near
a Still unit -- is the **flying** unit's behaviour, and the naval kinds take 2
and 6. That is recoverable from the unit's own type, which the port already
has.

**What this does not establish.** `0x004cf574` lies beyond the file image, in
`.bss`, so the rest of the word is built at run time from the shipped
unit-type data and cannot be read out of the executable; only type 42's word
has actually been observed. The type does not decide it alone, and what
does has now been captured. A zeppelin in each mission reports the **same**
type flags, `00000082`, and differs only in whether the AI took it on:

```text
xorc-08 unit 1400:  home=0,0    behavior=0  depot=4294967295 (none)  marker=2
human-07 unit 1575: home=76,8   behavior=4  depot=1594 at 67,2       marker=2
```

The assigner writes `ai_home` and `ai_behavior` together, so a unit still at
home `0,0` with no depot was never assigned a role at all -- behaviour 0 is the
dispatcher's "no handler". `ai_marker` is 2 on both and so belongs to some
other path.

Why one player homes them and the other does not is simply what it owns:

```text
human-07 player 5: 37 units including a great-hall, mound, barracks,
                   blacksmith, four pig farms and towers -- a base
xorc-08  player 5: 41 units, every one mobile -- no hall, no depot at all
```

Human 7's zeppelin is homed to that great hall; XOrc 8's player 5 has nothing
to come home to, so its four zeppelins are never adopted. XOrc 8's six
behaviour-4 units belong to player 2, which does have a base.

The chain therefore reads: a flying unit whose player owns a depot is homed to
it, is given behaviour 4, and on every AI pass on which it is Still it spends
two asynchronous draws -- one per axis -- on a point offset from its own tile,
and is sent there.

**The pass is periodic, not per cycle.** Over 220 cycles the behaviour-4 draws
fall on cycles **0, 49, 99, 149 and 199** -- one startup pass and then every
**50** cycles -- and the number drawn each time is exactly the number of
adopted flyers that are Still at that moment: 12, 2, 2, 4, 6 draws, which is
6, 1, 1, 2 and 3 units. At cycle 49 only the gryphon rider 1560 is Still and
the other five are on order 4, and exactly one pair is drawn.

Gryphon rider 1560 goes Still at fixture 38, draws on the cycle-49 pass, and
takes a new order at fixture 52.

### The assigner's sixteen callers, and what each asks for

Decoded from the pinned executable by reading the `cdecl` arguments at every
`call 0x004275b0`. The behaviour is the middle of three:

| Behaviour | Call sites |
|---:|---|
| 1 | `0x004257f2`, `0x00425f34`, `0x004273b8`, `0x00427f23`, `0x0042bbe2` |
| 2 | `0x0040ac20`, `0x00426e8a`, `0x004270c4`, `0x00427fae` |
| 3 | `0x00427182` |
| **4** | `0x004271ae`, `0x00427cd6`, `0x00428012` |
| 6 | `0x00427198` |
| 7 | `0x004282c9` |
| -- | `0x00426ff6` passes a register, not a constant |

Only three of the sixteen hand out behaviour 4, and `0x004271ae` is the one
reached by the type-flags walk described above. The others are entered from
elsewhere in the AI.

One of the behaviour-1 sites has been read. `0x004257f2` sits at the end of a
walk over the unit array:

```text
0x004257bc   cmp byte [eax + 0x2c], bl   ; same owner  (0x2c = UNIT_OWNER, 44)
0x004257c1   mov cl,  byte [eax + 0x27]  ; type        (0x27 = UNIT_TYPE, 39)
0x004257c4   cmp cl, 0x50                ; mage tower
0x004257c9   cmp cl, 0x51
0x004257e3   push <found unit>, push 1, push subject -> assigner
```

So that arm means "behaviour 1, homed to one of this player's mage towers".
It is not the arm the flying angel took: its `ai_home` is `8,58`, which is its
own square, and the assigner has a separate exit that writes the unit's own
`0x18` as the home. So the flying angel reached one of the four remaining
behaviour-1 sites, and which one is not established.

This is what a port has to reproduce, and the flying angel shows why nothing
smaller will do: it and the gryphon rider have identical type flags and both
are homed, so whichever site each reaches is decided before any of that is
looked at.

**What this does not establish.** Which of the sixteen call sites runs in each
case, and when -- that is AI state, and it is what decides why one mission gives a
type-41 unit behaviour 4 and another gives the same type under the same owner
behaviour 0. A capture that records the flags word per type would settle the
first question; the second needs the call sites read in order. Nor is what the chosen point is used
for, nor what `word [0x004acc2c]` holds -- the arithmetic is consistent with a
map dimension but no capture has read it -- nor what `0x00424f70` tests, nor
what the other seven handlers do. The state stream carries no cycle 0, so the
six draw pairs seen there are matched against the cycle-1 record. None of this
may be answered by inference from the listing.

## The per-type flag word, in full

The word at `0x004cf574 + type * 4` decides which arm of the computer's role
assigner a unit takes, and it lives in `.bss`, so it cannot be read out of the
executable. Establishing it for a single type used to cost one capture of a
unit of that type; four wrong eligibility rules were shipped and gated against
each other before that was understood.

The tracer now dumps the whole table on the first traced cycle
(`event=unit-type-flags`), so one capture of any scenario yields all 112
entries. The six that had been obtained the expensive way -- balloon, zeppelin,
gryphon rider, dragon, daemon and flying angel -- all match the dump exactly,
which is the check that it is reading the right memory.

Grouped by word, over the types the PUD enumeration names:

| Flags | Types | Examples |
|---|---:|---|
| `00000000` | 2 | unit-human-start-location (94), unit-orc-start-location (95) |
| `00000020` | 21 | unit-farm (58), unit-pig-farm (59), unit-human-barracks (60), unit-orc-barracks (61), +17 more |
| `00000082` | 3 | unit-balloon (40), unit-zeppelin (41), unit-eye-of-vision (45) |
| `000000a0` | 2 | unit-human-watch-tower (64), unit-orc-watch-tower (65) |
| `00000208` | 2 | unit-human-oil-tanker (26), unit-orc-oil-tanker (27) |
| `00000408` | 2 | unit-human-transport (28), unit-orc-transport (29) |
| `00000820` | 2 | unit-human-oil-platform (86), unit-orc-oil-platform (87) |
| `00001020` | 6 | unit-town-hall (74), unit-great-hall (75), unit-keep (88), unit-stronghold (89), +2 more |
| `00010020` | 2 | unit-human-foundry (78), unit-orc-foundry (79) |
| `00040020` | 2 | unit-elven-lumber-mill (76), unit-troll-lumber-mill (77) |
| `00080008` | 2 | unit-human-destroyer (30), unit-orc-destroyer (31) |
| `000800c8` | 2 | unit-human-submarine (38), unit-orc-submarine (39) |
| `00084004` | 2 | unit-ballista (4), unit-catapult (5) |
| `00084008` | 2 | unit-battleship (32), unit-ogre-juggernaught (33) |
| `001800a0` | 4 | unit-human-guard-tower (96), unit-orc-guard-tower (97), unit-human-cannon-tower (98), unit-orc-cannon-tower (99) |
| `00200020` | 1 | unit-oil-patch (93) |
| `00400020` | 1 | unit-gold-mine (92) |
| `01010020` | 4 | unit-human-shipyard (72), unit-orc-shipyard (73), unit-human-refinery (84), unit-orc-refinery (85) |
| `08000011` | 1 | unit-critter (57) |
| `08080001` | 14 | unit-footman (0), unit-grunt (1), unit-knight (6), unit-ogre (7), +10 more |
| `08080082` | 4 | unit-flying-angel (22), unit-fire-breeze (35), unit-gryphon-rider (42), unit-dragon (43) |
| `08080092` | 1 | unit-daemon (56) |
| `08080101` | 2 | unit-peasant (2), unit-peon (3) |
| `08088011` | 1 | unit-skeleton (55) |
| `080a0001` | 4 | unit-paladin (12), unit-ogre-mage (13), unit-fad-man (23), unit-knight-rider (44) |
| `08880001` | 2 | unit-wise-man (50), unit-sharp-axe (53) |
| `088a0001` | 2 | unit-double-head (49), unit-man-of-light (52) |
| `088a8001` | 1 | unit-ice-bringer (51) |
| `0a080001` | 2 | unit-dwarves (14), unit-goblin-sappers (15) |
| `0c0a0001` | 2 | unit-mage (10), unit-white-mage (24) |
| `0c0a8001` | 2 | unit-death-knight (11), unit-evil-knight (21) |

Four bits are legible straight from that grouping, and are the ones the
assigner's arms test:

- `0x01` -- moves on land. Every ground fighter and worker carries it.
- `0x02` -- flies. Carried by exactly the six flying types and nothing else.
- `0x08` -- swims. Warships, transports and oil tankers.
- `0x20` -- is a building. Carried by every building and by nothing that moves.
- `0x10` -- the summoned and the neutral: critter, skeleton and daemon, and
  those three only. This is the bit that keeps a daemon on behaviour 1 when a
  gryphon rider with otherwise identical flags takes behaviour 4.

The rest are unnamed here on purpose. `0x80` is tempting to read as "flies"
because every aircraft has it, but the watch towers and submarines have it too,
so it is something else and is left alone until a live question needs it.

Note the flying angel, fire breeze, gryphon rider and dragon share one word
exactly. No predicate over this table alone separates them, which is why
behaviour 4 needs the owner's controller byte and the unit's marker as well.

## Resource dropout turns before restarting its next perimeter

The resource dropout writer at `0x004519d0` passes its packed scan dimensions
and goal to the perimeter walker at `0x00443a40`.  After walking the fourth
leg, the walker still applies the signed turn at `0x00443c7b`--`0x00443c81`.
It then subtracts that newly selected direction at
`0x00443c84`--`0x00443c9a` before adding two to both dimensions for the next
pass.  The restart is therefore not a backstep along the fourth leg.  On an
odd-sized rectangle it can change both coordinates' parity between passes.

The placement callback at `0x004512bb`--`0x004512ca` independently rejects an
anchor with either coordinate odd when the exiting type has the doubled-
movement flag.  Human 7 is the direct combined witness: tanker slot 1491
leaves the 3x3 refinery at `(72,72)` toward platform `(79,77)`.  Its first
perimeter has no even/even candidate.  The native fourth-leg turn restarts the
second perimeter at `(76,76)`, whose north leg tests `(76,75)` and then accepts
`(76,74)` at fixture 405.  Backing out along the old east leg instead restarts
at `(75,75)`, never reaches the native anchor, and eventually falls back to
the west side.

## FindDeposit uses component-filtered native distance, not a route cost

The pinned BNE executable's `FindDeposit` is `0x00438770`. Its first type test
at `0x0043877d` selects the naval arm when the worker carries type flag
`0x00000008`; both oil tankers carry `0x00000208`. That arm reads the fixed map
component at the tanker's recorded tile, walks the owner's unit roster from
`0x004be264 + player * 4`, admits naval bases through type flag `0x01000000`,
and calls `0x00416980` to require at least one square of the candidate
footprint to carry the tanker's component word. The linked-list successor is
unit offset `0x68`.

Candidate ordering is not `UnitReachable` or an A* route length. At
`0x00438803` and `0x0043880d`, the naval arm calls `0x00416b10` for the
incumbent and current candidate. The comparison at `0x00438815` keeps the
incumbent only when it is strictly nearer, so an equal-distance candidate
later in the owner roster replaces it. The land arm at `0x00438839` first
compares the component words at the worker and depot origins, then makes the
same two distance calls at `0x004388da` and `0x004388e9`; its branch at
`0x004388fb` also replaces on equality.

`0x00416b10` is the existing footprint-aware Chebyshev distance: buildings
project their nearest footprint coordinate toward the worker, while movable
targets remain points. This is the discriminator between the accepted oil
controls. Expansion Orc 8's refinery is nearer than its shipyard under that
measure and remains selected. Expansion Human 6's tanker slot 1516 is hidden
at platform `(49,67)` when it selects shipyard slot 1519 at `(40,51)`, not
refinery slot 1522 at `(49,47)`; its stored return route therefore opens
north-west on fixture 344. A dynamic route-cost refinement reverses that
second choice even though both depots share the water component.

## Everything that draws from the asynchronous stream

Swept from the retained captures by grouping every `async-random` event by its
return address and looking for periodicity. Over 420 cycles of XOrc 12 -- four
computer players, the busiest AI in the corpus -- the whole list is four
entries:

| Caller | What it is | Cadence |
|---|---|---|
| `0x0040AD58` | the ordinary idle dispatch draw | every cycle, for every eligible unit |
| `0x0040AE30` | the flying/naval idle countdown | per unit, when its countdown expires |
| `0x00451D7F`, `0x00451F4D` | the PUD unit constructor's facing and animation delay | once per unit built (one at cycle 162 here) |
| `0x00427986`, `0x004279B3` | the scout pass's point, one draw per axis | cycle 0, then every 50 -- absent from XOrc 12, which has no aircraft that qualify |

That the list is this short is the useful part. When a divergence is traced to
the asynchronous stream, the candidate causes are now enumerable rather than
open: an idle dispatch that drew when it should not have or vice versa, a
countdown re-armed at the wrong moment, a unit built on the wrong cycle, or the
fifty-cycle scout beat. Nothing else in the computer player's thinking -- its
building, its attacks, its resource decisions -- touches this generator at all,
so a stream divergence is never evidence about those.

The sweep is a dozen lines over any capture: bucket `async-random` by `caller`,
take the distinct cycles, and look at the dominant gap. A caller with a gap of
ten or more repeated three times is a periodic pass worth naming.

## Acceptance evidence

The isolated retail install was patched with the pinned official updater and
verified against the executable, Storm, Battle.snp, `War2Dat.mpq`, and
`War2Patch.mpq` hashes. Two fresh no-click Orc mission 1 processes used seed 1
and the same move command for slot 1594 at cycle 5. Each captured cycles 1
through 40, three active player banks and twelve live units per cycle: 120
player records, 480 unit records, and 640 canonical `cycle`/`p`/`u` records in
total. Both streams are byte-for-byte identical and have SHA-256
`e8a2c23976714791e02d1d10e48c84f68e138540dda93e8b66851570ce5f7e65`.
Both runs contain exactly one seed-application marker, one applied command,
one cycle-limit marker, and one detach marker.

The opposite campaign branch was checked separately: two Human mission 1
processes with seed 1 and no commands also produced identical 640-record
streams, SHA-256
`928603e06ed7c5ef1a6f8cd90bfcae760a9f16ae22e4fbf12ff9b045095ac770`.

Two further fresh Orc mission 1 runs exercised raw schema 1.1. Each produced a
414,980-byte state stream containing 2,068 unit deltas, 491 projectile deltas,
1,028 map deltas, and 120 extended-player records. The two state files were
byte-for-byte identical with SHA-256
`dcb7f6192b8ae99f79f34d6ee8f85e07d5b37e4c60478e1707d02d5428fec0d8`.
Their fixture ID was
`b8003a04edafaf8b68e38a77ad4946de1375938977b131aff371b7c9bfe52e80`;
the projectile, map, and extended-player digests also matched independently.

The synchronized dispatch call now has an opt-in, byte-guarded replay wrapper.
It verifies the participant and controller vector, then injects each recorded
packet into the original retail dispatcher. The replay gate rejects missing,
reordered, incomplete, or mismatched receipts; see
[`REPLAYS.md`](REPLAYS.md). The active replay boundary is reconstructing the
same named map, slots, races, and initial deterministic state on both producers.
Arbitrary custom-PUD handles and
the unnamed parts of BNE's command vocabulary remain separate extensions.
Determinism is measured over the canonical simulation stream; diagnostic trace
comments intentionally retain process-local handles.
