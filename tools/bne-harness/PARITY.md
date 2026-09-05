# BNE parity engineering runbook

This is the living handoff document for driving the Java engine toward
Warcraft II Battle.net Edition 2.02b. It describes the repeatable engineering
loop, the standard of evidence, the local/remote setup, and the exact current
checkpoint. Update **Current checkpoint** before handing the work to another
engineer or agent.

The durable corpus workflow is in [CORPUS.md](CORPUS.md), the binary fixture
contract is in [FIXTURE.md](FIXTURE.md), and executable addresses belong in
[LAYOUT.md](LAYOUT.md). This document connects those pieces into the daily
parity process.

The oil economy's native actions, 150-cycle dwell windows, tanker geometry,
builder auto-haul, destruction/depletion behavior, and regression commands are
sealed in [OIL_LIFECYCLE.md](OIL_LIFECYCLE.md).

## Current release checkpoint — 2026-09-05 (a battleship cannon leaves from the ship's pixel centre)

Accepted cycle-1,800 surveys on clean commit `3c34950` keep the shared
proven frontier at cycle 403 and match native battleship muzzle remaining
without moving any case earlier. The fleet remains 14 clean / 38
divergent / 0 failed through cycle 1,800, and the 52-case exact-prefix
sum stays 56,459. Cycle 400 stays 52 clean / 0 divergent / 0 failed.
Expansion Orc 11 remains exact through fixture 576. Its fixture-577
finding is still human destroyer native slot 1558 / Java hp 48 versus 26.
Expansion Orc 8 remains the paused naval patrol/route-publication family
at fixture 404, which is why the shared horizon stops at 403.

The 400-cycle survey is `.bne-surveys/current-3c34950-c400` and the
1,800-cycle survey is `.bne-surveys/current-3c34950-c1800`. Both bind clean
commit `3c34950`, engine-input
`4261e2435437d094ed9735fc67c729acf16932840ca9cb86943e2385ef5a0a91`,
pack SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`,
and corpus `tools/bne-harness/work/corpus/campaign-1800/corpus-index.json`.
The previous fleet-improving long receipt remains
`.bne-artifacts/runs/ae6fada127412a2f5fa71cb7968ca1fe7d161ec072eafb69ea1d400058f6c98b`.

Behavioral delta: mobile projectile constructors measure the muzzle from
the source unit's pixel words plus the type-centre table, not from those
words plus the invisible residual bank. Target aim already omitted that
bank. The rule is constructor geometry against IX/IY; it contains no
mission, map, faction, coordinate, fixture, exact-cycle, route-length, or
unit-ID branch.

Proof delta: expansion Orc 11 battleships 103 and 88 fire at fixture 530
with residual `(+1,+1)` and `(-1,+1)`. Native stores remaining 130 and 126
from muzzles `(336,1424)` and `(336,1296)`. Java used to add the residual
and store 131 and 127. The 577 destroyer hit remains the next unpaused
finding: native type-24 slot 6 is born at fixture 568 with remaining 130
and aim `(340,974)`, and frees at 578.

Efficacy receipt
`.bne-test-efficacy/runs/36bf0ff74877f3233b00436705ff6a5d6f292dcff44c492980c82f00dd77547c`
proves the real-data assertion executes and fails on `40d757a`, then
executes and passes on `3c34950`. Focused 393 aim-jitter, crossing-shell,
leftover-drop, and live-route held-outs pass. Both fixed 52-case gates
pass. Local native capture remains available and no SSH bypass was used.

Expansion Orc 8 fixture 404 remains paused in its naval patrol/route-publication
family. Expansion Human 12 fixture 405 remains the occupancy family that
already failed two evidence-backed hypotheses. The earliest unpaused fleet
finding after that is expansion Orc 11 and expansion Human 5 at 577, Human 8
at 583, expansion Human 2 at 585, and expansion Human 7 at 609.

## Prior release checkpoint — 2026-09-05 (computer shipyards pay the transport 1-in-8 roll)

Accepted cycle-1,800 receipt `ae6fada1` keeps the shared proven frontier at
cycle 403 and improves Orc 13 and Human 12 without moving any other case
earlier. The fleet is 14 clean / 38 divergent / 0 failed through cycle
1,800, and the 52-case exact-prefix sum rises from 54,347 to 56,459.
Cycle 400 stays 52 clean / 0 divergent / 0 failed. Orc 13 is now exact
through cycle 1,800. Human 12 advances from exact through fixture 582 to
exact through fixture 1,469. Its newly exposed fixture-1,470 finding is
peon native slot 1565 / Java x 104 versus 103. Expansion Orc 8 remains the
paused naval patrol/route-publication family at fixture 404, which is why
the shared horizon stops at 403.

The 400-cycle survey is `.bne-surveys/current-206ff96-c400` and the
1,800-cycle survey is `.bne-surveys/current-206ff96-c1800`. Both bind clean
commit `206ff96`, engine-input
`64cad26d474c3f94096651a7dce2acbf8d16a6e3de753fefec15a6f4e8b81e88`,
pack SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`,
and corpus `tools/bne-harness/work/corpus/campaign-1800/corpus-index.json`.
The long receipt is retained at
`.bne-artifacts/runs/ae6fada127412a2f5fa71cb7968ca1fe7d161ec072eafb69ea1d400058f6c98b`.

Behavioral delta: shipyard action-33 train_fn `0x40eef0` consumes
`FUN_00479820` when battleship, sub, or transport want exceeds census,
before the unpaid tanker fallback. Empty-family tanker, destroyer, and
foundry-backed transport arms still skip the roll. The rule is naval want
versus census in `0x40eef0`; it contains no mission, map, faction,
coordinate, fixture, exact-cycle, route-length, or unit-ID branch.

Proof delta: Orc 13 computer shipyard 1509 / Java 91 wants 2 transports
with none and no foundry. Native draws at `0x40f094` on fixture 572 even
when the 1-in-8 misses. Java used to take the tanker fallback without that
draw, so knight 34 stole the seed and critter 1464 / Java 136 missed
wander choice 48 at fixture 576. Human 12's same-family critter 1563
advances with it. XOrc 7's destroyer 1-in-4 pair remains the held-out.

Efficacy receipt
`.bne-test-efficacy/runs/c026a33888da46231cc53b0ff6cbbb9d4445534c3ada040fbde3e709e10c7c40`
proves the real-data assertion executes and fails on `9cfd350`, then
executes and passes on `206ff96`. Focused shipyard-train, leftover-drop,
and live-route held-outs pass. Both fixed 52-case gates pass. Local native
capture remains available and no SSH bypass was used.

Expansion Orc 8 fixture 404 remains paused in its naval patrol/route-publication
family. Expansion Human 12 fixture 405 remains the occupancy family that
already failed two evidence-backed hypotheses. The earliest unpaused fleet
finding after that is expansion Orc 11 and expansion Human 5 at 577, Human 8
at 583, expansion Human 2 at 585, and expansion Human 7 at 609.

## Prior release checkpoint — 2026-09-04 (GiveOrder Still keeps its one-tick constructor)

Accepted cycle-1,800 receipt `b21917a6` keeps the shared proven frontier at
cycle 403 and finishes the spent-leftover give-up without moving any case
earlier. The fleet remains 13 clean / 39 divergent / 0 failed through
cycle 1,800, and the 52-case exact-prefix sum stays 54,347. Cycle 400 stays
52 clean / 0 divergent / 0 failed. Expansion Orc 11 remains exact through
fixture 576. Its fixture-577 finding is now human destroyer native slot
1558 / Java hp 48 versus 26; the previous 48 versus 32 gap was two extra
idle ticks on axethrower 1508. Expansion Orc 8 remains the paused naval
patrol/route-publication family at fixture 404, which is why the shared
horizon stops at 403.

The 400-cycle survey is `.bne-surveys/current-9cfd350-c400` and the
1,800-cycle survey is `.bne-surveys/current-9cfd350-c1800`. Both bind clean
commit `9cfd350`, engine-input
`a8206292032f22b579cb2cd34e6835263b6244f597f58bd35559451f78a95bcd`,
pack SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`,
and corpus `tools/bne-harness/work/corpus/campaign-1800/corpus-index.json`.
The long receipt is retained at
`.bne-artifacts/runs/b21917a68e5869fcd7aaf8b28d1fc69aed0c5f5961096fd728636a503b13a832`.

Behavioral delta: borrowed MOVE under Attack no longer restores Attack after
GiveOrder has already installed Still with a cleared quarry. Occupancy-empty
but terrain-reachable chases still restore. The rule is leftover GiveOrder
Still with a null target; it contains no mission, map, faction, coordinate,
fixture, exact-cycle, route-length, or unit-ID branch.

Proof delta: expansion Orc 11 axethrower 1508 / Java 92 already ended Attack
on fixture 576, but restoring Attack made finishAttackOrder seed timer 3.
Native is Still@825/1. Axethrower 1517's construction-windup drop at
fixture 253 remains the held-out.

Efficacy receipt
`.bne-test-efficacy/runs/e0155ff8c0df5739c88ea4f4974f14b88b39965953a8734620c00300e35bbba7`
proves the real-data assertion executes and fails on `5b359df`, then
executes and passes on `9cfd350`. Focused unreachable-drop, gold leftover,
and live-route held-outs pass. Both fixed 52-case gates pass. Local native
capture remains available and no SSH bypass was used.

Expansion Orc 8 fixture 404 remains paused in its naval patrol/route-publication
family. Expansion Human 12 fixture 405 remains the occupancy family that
already failed two evidence-backed hypotheses. The earliest unpaused fleet
finding after that is Orc 13 at 576, then expansion Orc 11 and expansion
Human 5 at 577, Human 8 and Human 12 at 583, expansion Human 2 at 585, and
expansion Human 7 at 609.

## Prior release checkpoint — 2026-09-04 (spent leftovers drop unreachable quarries)

Accepted cycle-1,800 receipt `5a8e9181` keeps the shared proven frontier at
cycle 403 and improves expansion Orc 11 without moving any other case
earlier. The fleet remains 13 clean / 39 divergent / 0 failed through
cycle 1,800, while the 52-case exact-prefix sum rises from 54,346 to
54,347. Cycle 400 stays 52 clean / 0 divergent / 0 failed. Expansion
Orc 11 advances from exact through fixture 575 to exact through fixture
576. Its newly exposed fixture-577 finding is human destroyer native slot
1558 / Java hp 48 versus 32. Expansion Orc 8 remains the paused naval
patrol/route-publication family at fixture 404, which is why the shared
horizon stops at 403.

The 400-cycle survey is `.bne-surveys/current-5b359df-c400` and the
1,800-cycle survey is `.bne-surveys/current-5b359df-c1800`. Both bind clean
commit `5b359df`, engine-input
`211ccb5c71a35bddd3790484f106efb74cf6e6a80ea2f680a4db0823b7cdc405`,
pack SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`,
and corpus `tools/bne-harness/work/corpus/campaign-1800/corpus-index.json`.
The long receipt is retained at
`.bne-artifacts/runs/5a8e9181340edb906acb9bd9af5079075ae609e73a4768830f18994e9035a6dd`.

Behavioral delta: a spent Attack leftover whose quarry is unreachable over
terrain ends the order instead of paying the empty-route wait. Occupancy-empty
but terrain-reachable chases still retry for two cycles. The rule is land
movement, empty spent route, and terrain reachability; it contains no
mission, map, faction, coordinate, fixture, exact-cycle, route-length, or
unit-ID branch.

Proof delta: expansion Orc 11 axethrower 1508 / Java 92 drains its last NE
residual onto (6,40) while chasing (14,36). Native returns to Still@825 on
fixture 576 and clears the target. Java used to PF_WAIT two cycles and keep
Attack. Axethrower 1517's construction-windup drop at fixture 253 remains
the held-out.

Efficacy receipt
`.bne-test-efficacy/runs/fef3cabe60f67f607448387f8a4b6bd37f51321ea7abeacd109d71a38e5b74ce`
proves the real-data assertion executes and fails on `af53f58`, then
executes and passes on `5b359df`. Focused unreachable-drop, gold leftover,
and live-route held-outs pass. Both fixed 52-case gates pass. Local native
capture remains available and no SSH bypass was used.

Expansion Orc 8 fixture 404 remains paused in its naval patrol/route-publication
family. Expansion Human 12 fixture 405 remains the occupancy family that
already failed two evidence-backed hypotheses. The earliest unpaused fleet
finding after that is Orc 13 at 576, then expansion Orc 11 and expansion
Human 5 at 577, Human 8 and Human 12 at 583, expansion Human 2 at 585, and
expansion Human 7 at 609.

## Prior release checkpoint — 2026-09-04 (gold mixed leftovers pay one Move 15)

Accepted cycle-1,800 receipt `0c331136` keeps the shared proven frontier at
cycle 403 and improves expansion Human 7 without moving any other case
earlier. The fleet remains 13 clean / 39 divergent / 0 failed through
cycle 1,800, while the 52-case exact-prefix sum rises from 54,310 to
54,346. Cycle 400 stays 52 clean / 0 divergent / 0 failed. Expansion
Human 7 advances from exact through fixture 572 to exact through fixture
608. Its newly exposed fixture-609 finding is orc destroyer native slot
1562 / Java x 30 versus 28. Expansion Orc 8 remains the paused naval
patrol/route-publication family at fixture 404, which is why the shared
horizon stops at 403.

The 400-cycle survey is `.bne-surveys/current-af53f58-c400` and the
1,800-cycle survey is `.bne-surveys/current-af53f58-c1800`. Both bind clean
commit `af53f58`, engine-input
`15203e9596b27b3d414bcca7367e2df58d4a994883b2b7e4c613613eb5730957`,
pack SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`,
and corpus `tools/bne-harness/work/corpus/campaign-1800/corpus-index.json`.
The long receipt is retained at
`.bne-artifacts/runs/0c331136eb24118d10070ee76f4f72641ac2d1ef7836bb983db31d34591acef9`.

Behavioral delta: a gold-approach two-byte leftover whose first residual
is a cardinal onto an allied worker, with a different diagonal behind it,
keeps those bytes and pays one Move 15. Same-cardinal first residuals still
wait two fifteen-counts, and later same-cardinal residuals still park route
index 20. The rule is leftover shape, first residual, and allied-worker
occupancy; it contains no mission, map, faction, coordinate, fixture,
exact-cycle, route-length, or unit-ID branch.

Proof delta: expansion Human 7 peon 1446 / Java 154 residual-settles at
(110,106) with leftover W,SW onto allied peon 1458. Native writes
Move-start/15 at fixture 558, keeps the two bytes, and consumes west at
573. Java used to drop the leftover, climb collision through eight, then
start a fourteen-count that spent west at 579. XHuman 7 peon 1458's later
E,E park and XHuman 10 gold residual park remain held-outs.

Efficacy receipt
`.bne-test-efficacy/runs/07e725f62c2d4bb68c5e7b8c1c1e4ae85f42142abe836fdda40e040cb11f08e6`
proves the real-data assertion executes and fails on `7e1e623`, then
executes and passes on `af53f58`. Focused gold residual, vacated-ally,
and harvest-terminal held-outs pass. Both fixed 52-case gates pass. Local
native capture remains available and no SSH bypass was used.

Expansion Orc 8 fixture 404 remains paused in its naval patrol/route-publication
family. Expansion Human 12 fixture 405 remains the occupancy family that
already failed two evidence-backed hypotheses. The earliest unpaused fleet
finding after that is Orc 13 and expansion Orc 11 at 576, then expansion
Human 5 at 577, Human 8 and Human 12 at 583, expansion Human 2 at 585, and
expansion Human 7 at 609.

## Prior release checkpoint — 2026-09-04 (Attack-body waits do not dest-arm)

Accepted cycle-1,800 receipt `37c7cd9d` keeps the shared proven frontier at
cycle 403 and improves expansion Human 10 without moving any other case
earlier. The fleet remains 13 clean / 39 divergent / 0 failed through
cycle 1,800, while the 52-case exact-prefix sum rises from 54,269 to
54,310. Cycle 400 stays 52 clean / 0 divergent / 0 failed. Expansion
Human 10 advances from exact through fixture 561 to exact through fixture
602. Its newly exposed fixture-603 finding is ogre native slot 1538 /
Java x 101 versus 100. Expansion Orc 8 remains the paused naval
patrol/route-publication family at fixture 404, which is why the shared
horizon stops at 403.

The 400-cycle survey is `.bne-surveys/current-7e1e623-c400` and the
1,800-cycle survey is `.bne-surveys/current-7e1e623-c1800`. Both bind clean
commit `7e1e623`, engine-input
`bbb554c41739b23ed5b023b9477e2360c8fdaf89c9e620c1f4b76f035eac39f8`,
pack SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`,
and corpus `tools/bne-harness/work/corpus/campaign-1800/corpus-index.json`.
The long receipt is retained at
`.bne-artifacts/runs/37c7cd9dc1750f18ee73dc4cff609b8c6a5ea886a84ff7d5873b1ed4abb4a14a`.

Behavioral delta: an Attack-body wait past Attack-start still owns
MoveToBetterPos after Java's parallel presentation has become breakable.
Native does not dest-arm until the following OP0. Construction and OP0 at
Attack-start keep their existing sequence-ownership flags, and AttackTarget
still animates first so a pre-OP10 frame can queue its pending shot. The
rule is expressed by sequence wait versus Attack-start and by min-range
dest-arm; it contains no mission, map, faction, coordinate, fixture,
exact-cycle, route-length, or unit-ID branch.

Proof delta: expansion Human 10 catapult 1487 / Java 113 is Attack 540/42
at fixture 562 with a parked empty route. Native stays on (74,89) through
a melee hit. Java's presentation wait hitting zero used to fall through to
MoveToBetterPos, spend three synchronized direction draws, and step
southwest to (73,90). The same catapult still wraps 540/1 onto Attack
construction 503/3 at fixture 203. XHuman 10 axe 51 still queues its
pending shot at offset 892.

Efficacy receipt
`.bne-test-efficacy/runs/a76b90c7c4f2ef4077d31dba222a93e0bc9fc1fef3dcd08cba34f1b08a4c9d13`
proves the real-data assertion executes and fails on `01d069d`, then
executes and passes on `7e1e623`. Focused XHuman 10 timing, live-route
settle, and Human 13 quarry-handoff held-outs pass. Both fixed 52-case
gates pass. Local native capture remains available and no SSH bypass was
used.

Expansion Orc 8 fixture 404 remains paused in its naval patrol/route-publication
family. Expansion Human 12 fixture 405 remains the occupancy family that
already failed two evidence-backed hypotheses. The earliest unpaused fleet
finding after that is expansion Human 7 at 573, then Orc 13 and expansion
Orc 11 at 576, expansion Human 5 at 577, expansion Human 2 at 585, and
expansion Human 10 at 603.

## Prior release checkpoint — 2026-09-04 (first-collision live residuals write Move-start/15)

Accepted cycle-1,800 receipt `79d655d6` advances the shared proven frontier
from cycle 400 to 403 and improves expansion Human 12 without moving any
other case earlier. The fleet remains 13 clean / 39 divergent / 0 failed
through cycle 1,800, while the 52-case exact-prefix sum rises from 54,265
to 54,269. Cycle 400 stays 52 clean / 0 divergent / 0 failed. Expansion
Human 12 advances from exact through fixture 400 to exact through fixture
404. Its newly exposed fixture-405 finding is grunt native slot 1489 / Java
y 39 versus 38. Expansion Orc 8 remains the paused naval
patrol/route-publication family at fixture 404, which is why the shared
horizon stops at 403.

The 400-cycle survey is `.bne-surveys/current-7bc11b9-c400` and the
1,800-cycle survey is `.bne-surveys/current-7bc11b9-c1800`. Both bind clean
commit `7bc11b9`, engine-input
`db173b4f7882331266a05a3193eff8624239d560f1b4207b73b1b438ea82d805`,
pack SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`,
and corpus `tools/bne-harness/work/corpus/campaign-1800/corpus-index.json`.
The long receipt is retained at
`.bne-field-evidence/current-7bc11b9-gate/runs/79d655d6729fd678dada923a8e9200c2a81b42ce01193c25c2607a6fd3343a17`.

Behavioral delta: the first collision generation of a live Attack residual
writes native Move-start/15 on the settle visit. A leftover refusal count is
not that generation. Later collision generations still park Move-start/1 and
refill on the following callback. The rule is expressed by native collision
generation, live route ownership, and the existing full-buffer-tail exception;
it contains no mission, map, faction, coordinate, fixture, exact-cycle,
route-length, or unit-ID branch.

Proof delta: expansion Human 12 grunt 1479 / Java 121 finishes the third
heading of a twenty-byte chase at fixture 386 with seventeen live bytes, two
prior refusals, and collision generation one. Native is already 2482/15 on
that visit and consumes cached SE at fixture 401. Java used the leftover
refusal count to stage Move-start/1 then 15, spending SE at 402. Grunt 1463
at fixture 123 remains the shorter live-residual held-out (five headings,
collision one, immediate 15). Grunt 1494 at fixture 102 remains the later
collision-four held-out that must keep the extra visit so NE spends at 117.

Efficacy receipt
`.bne-test-efficacy/runs/5c241fb05fb8e71e66f0bd266c2abbbbb474d35ab65291008527b2d6f9ef292f`
proves the real-data assertion executes and fails on `01d069d`, then executes
and passes on `7bc11b9`. Focused residual-refill held-outs pass (42 tests),
including collision refill, collided chase refill, settled chase refusal,
grunt 1492 saturated building retarget, cycle-58 route handoff, and the
formation-refusal timer-one probe. Both fixed 52-case gates pass. Local native
capture remains available and no SSH bypass was used.

Expansion Orc 8 fixture 404 remains paused in its naval patrol/route-publication
family. The earliest unpaused fleet finding is now expansion Human 12 at
fixture 405, followed by expansion Human 10 at 562, expansion Human 7 at 573,
Orc 13 and expansion Orc 11 at 576, expansion Human 5 at 577, and expansion
Human 2 at 585.

## Prior release checkpoint — 2026-09-03 (off-target splash victims retain HitUnit responses)

Accepted cycle-1,800 receipt `9706be85` preserves the shared clean horizon at
fixture 332 and improves or preserves every campaign frontier. The fleet
remains 13 clean / 39 divergent / 0 failed, while the directly recomputed sum
of the 52 exact prefixes rises from 54,130 to 54,149. Expansion Orc 11 is the
only changed case, advancing from exact through fixture 556 to exact through
fixture 575. Its newly exposed fixture-576 finding is axethrower native slot
1508 / Java unit 92 Still versus Attack. The long receipt is retained at
`.bne-artifacts/runs/9706be858f59c5cdbfcf5a15bc12c84d92df7eca62624e9a0efd9f4c6520ee8f`.
Its manifest has SHA-256
`91678becdb7d8fa27ee31af193409f6250ebfd6a028dafe1c3d56e3d893d2820`,
and binds clean commit `c18a8e9`, engine-input identity
`efffc563c538b8f8c68e52d0efdd499e0f0d8947a55a9d0c3d1b3a7ab2d3e2db`,
and replayable source capsule
`b8b168890f357a635b157585898789e42d096210bf84def558c6fcc44096335c`
with zero untracked engine inputs.

Behavioral delta: every positive, nonlethal land-person victim accepted by a
splash cache walk now receives the ordinary native `HitUnit` / `AiHelpMe`
spatial response, including an outer victim when the projectile's named
target has moved outside its impact. A victim already carrying its own local
hit-source offer is not reverse-recruited as an idle brother by a later
victim's help rectangle. A ranged first-help chase whose source remains the
native attack incumbent repeats the quiet Attack constructor at its timer-one
rescan instead of falling through to Still. The rules are expressed by native
projectile, offer, order, target-selection, and constructor state; they contain
no mission, map, faction, coordinate, fixture, exact-cycle, route-length, or
unit-ID branch.

Proof delta: expansion Orc 11's gryphon native slot 1589 / Java unit 11 lands
its fixture-555 hammer after its named ogre has moved outside the old impact.
Native `0x00410762` nevertheless calls `HitUnit` for first accepted outer
victim axethrower slot 1507 / Java unit 93, and `0x00418524` enters `AiHelpMe`,
queuing close brothers slot 1500 / Java unit 100 and slot 1508 / Java unit 92.
They promote to Attack at fixture 557. The later splash victim slot 1498 /
Java unit 102 owns the gryphon in its local offer bank; at timer one native
`COrder_Attack` rescans through `0x004513d0`, promotes action 12 in
`0x00452ef0`, and restores timer three at `0x00453023`. Its 887/3,2,1 Attack
constructor therefore repeats instead of ending at fixture 559. The corrected
case agrees through fixture 575.

Efficacy receipt
`.bne-test-efficacy/xorc11-off-target-splash-hitunit/runs/fb8c92013da8711527f48c047379b7c05142cf1f9ad224be4799aa0749fb3189`
proves the real-data assertion executes and fails on `61b8e3c`, then executes
and passes on the candidate. The full expansion Orc 11 real-data class plus
three expansion Human 10 splash/offer held-outs pass (20 tests total). Both
fixed 52-case gates pass: cycle 400 is 50 clean / 2 divergent / 0 failed under
receipt `0e22b501`, and cycle 1,800 is 13 / 39 / 0. The long receipt's source
capsule authenticates and replays exactly. The ordinary executable next-level
gate exits zero after 209 Python checks (four skipped), 99 engine/desktop
checks, and 223 dual-adapter command scenarios; its 11 comparable scenarios
remain 6 exact / 5 divergent with no regression or infrastructure failure.
The optional SSH discovery still refuses the stale `i9beef` host key; local
native capture remains available and no SSH bypass was used.

Expansion Human 12 fixture 333 remains the paused shared-boundary route
frontier, and expansion Orc 8 fixture 356 remains paused in its submarine
route-publication family. The earliest unpaused fleet findings are now
expansion Human 10 at fixture 562, expansion Human 7 at 573, Orc 13 and
expansion Orc 11 at 576, expansion Human 5 at 577, and expansion Human 2 at
585.

## Prior release checkpoint — 2026-09-03 (point-to-point hit missiles finish their impact animation)

Accepted cycle-1,800 receipt `66d9458f` preserves the shared clean horizon at
fixture 332 and improves or preserves every campaign frontier. The fleet
remains 13 clean / 39 divergent / 0 failed, while the directly recomputed sum
of the 52 exact prefixes rises from 54,099 to 54,130. Expansion Human 2 is the
only changed case, advancing from exact through fixture 553 to exact through
fixture 584. Its newly exposed fixture-585 finding is barracks native HP 691
versus Java HP 692. The long receipt is retained at
`.bne-artifacts/runs/66d9458f0f6ca572d797b47356d81083036579537c8940fccbdc0205d57a70be`.
Its manifest has SHA-256
`bde4cb4ebf6f31bc2fc1aef6a6be7b97283a5faabe2f5e1d4c83f57021f96ea7`,
and binds clean commit `32aee74`, engine-input identity
`e6f19055a457504beadbd1204180a83c6978e28b2990bab39071d34f28ee485e`,
and replayable source capsule
`e45080bde1463bb9dee273c1fef6fbf8b131a02933a77505f056299d9c981195`
with zero untracked engine inputs.

Behavioral delta: BNE projectile type 10 reads speed 12 from native table
`0x00494e0c` rather than the generated touch-of-death declaration's speed 16.
The `POINT_TO_POINT_WITH_HIT` motion class keeps row zero throughout flight,
then uses action 6 for its visible hit animation: the six stored rows advance
at a three-projectile-pass cadence, and damage/free occurs on the visit after
the final visible row. The implementation is selected by native projectile
identity and motion class; it contains no mission, map, faction, coordinate,
fixture, exact-cycle, route-length, or unit-ID branch.

Proof delta: expansion Human 2's death knight native slot 1557 / Java unit 43
constructs type-10 projectile slot 3 at fixture 544 against barracks native
slot 1554 / Java unit 46. Native remaining distance is 129 and drains by 12
through 9 at fixture 554, crosses to -3 and enters action 6 at 555, then shows
flattened frames 0, 5, 10, 15, 20, and 25 before freeing at fixture 571. The
barracks therefore remains at 705 HP at fixture 554 and takes the shot's nine
damage only at 571. Java formerly drained by 16 and used a one-pass impact,
applying that damage at fixture 554. The corrected case agrees through fixture
584.

Efficacy receipt
`.bne-test-efficacy/xhuman2-touch-of-death-impact/runs/94accaeb8d21e7f81e8a9f0b17fb809a62f4cc94e68c7e045b6371b245d4e48f`
proves the real-data assertion executes and fails on `c01d964`, then executes
and passes on the candidate. Eleven focused motion, type-table, and expansion
Human 2 real-data checks pass. The wider projectile, missile, and save run has
90 passes and five inherited skips. Both fixed 52-case gates pass: cycle 400
is 50 clean / 2 divergent / 0 failed, and cycle 1,800 is 13 / 39 / 0. The
long receipt's source capsule authenticates and replays exactly. The ordinary
executable next-level gate exits zero after 209 Python checks (four skipped),
99 engine/desktop checks, and 223 dual-adapter command scenarios; its 11
comparable scenarios remain 6 exact / 5 divergent with no regression or
infrastructure failure. The optional SSH discovery still refuses the stale
`i9beef` host key; local native capture remains available and no SSH bypass was
used.

Expansion Human 12 fixture 333 remains the paused shared-boundary route
frontier, and expansion Orc 8 fixture 356 remains paused in its submarine
route-publication family. The earliest unpaused fleet finding is now expansion
Orc 11 at fixture 557, followed by expansion Human 10 at 562, expansion Human
7 at 573, Orc 13 at 576, expansion Human 5 at 577, and expansion Human 2 at
585.

## Prior release checkpoint — 2026-09-02 (same-cycle births stay at the action-table tail)

Accepted cycle-1,800 receipt `be167741` preserves the shared clean horizon at
fixture 332 and improves or preserves every campaign frontier. The fleet
remains 13 clean / 39 divergent / 0 failed, while the directly recomputed sum
of the 52 exact prefixes rises from 54,081 to 54,099. Expansion Orc 11 is the
only changed case, advancing from exact through fixture 538 to exact through
fixture 556. The newly exposed fixture-557 finding is that native axethrowers
in slots 1500 and 1508 are in Attack while their paired Java units are Still.
The long receipt is retained at
`.bne-artifacts/runs/be167741745eb042722e05a95750d3b00c39e352a79732eb1ea38c9f2e780143`.
Its manifest has SHA-256
`e0b3219117a9ee38e4013f347bd6cfc439e20beaf3b7827ea9ed2958f60edf6d`,
and binds clean commit `bf46c81`, engine-input identity
`e83f07873fd7ea7392b21ffa442b8f9cc73c1c6bddecd826b87651c4ce11bf34`,
and replayable source capsule
`beed99e1e03afed567657e75dd3b0583498a30ea27abe473a596e49fb0833520`
with zero untracked engine inputs.

Behavioral delta: a unit born during the active UnitActions pass remains in
the pending-birth tail until the pass closes. Releasing a unit from the active
action table swaps in only the last already-active unit; it cannot pull a
same-cycle newborn forward into the released slot. Pending births append after
all active releases have completed. This is the native table lifecycle and
contains no mission, map, faction, coordinate, fixture, exact-cycle,
route-length, or unit-ID branch.

Proof delta: expansion Orc 11's peasant native slot 1408 / Java unit 194 is
born at fixture 482 while corpse native slot 1525 / Java unit 75 releases in
the same cycle. Native leaves the peasant outside the active table until cycle
close, so at fixture 487 it owns the first idle draw; Java formerly filled the
corpse's middle action-table hole with that pending peasant and processed it
after native slot 1524 / Java unit 76. That reorder swaps the idle result owned
by zeppelin native slot 1502 / Java unit 98 and shifts its later rearm cadence.
At fixture 539 the native gryphon projectile consumes its action-seven motion
draw before damage result 13722, producing 9 damage, while Java formerly used
the preceding result 27974 and produced 13. The corrected ogre juggernaut has
107 HP, not 103, and the case agrees through fixture 556.

Efficacy receipt
`.bne-test-efficacy/xorc11-same-cycle-birth-tail-real-data/runs/db19a3513f4258e4f1b2e2f7c1135f42816e7eff947c2ccca957729137abbb9b`
proves the real-data assertion executes and fails on `4b31f88`, then executes
and passes on the candidate. The focused real-data test passes, as do all 20
`CorpseTest` checks. Both fixed 52-case gates pass, and the long receipt's
source capsule authenticates and replays exactly. The ordinary executable
next-level gate exits zero after 209 Python checks (four skipped), 99
engine/desktop checks, and 223 dual-adapter command scenarios; its 11
comparable scenarios remain 6 exact / 5 divergent with no regression or
infrastructure failure. The optional SSH discovery still refuses the stale
`i9beef` host key; local native capture remains available and no SSH bypass was
used.

Expansion Human 12 fixture 333 remains the paused shared-boundary route
frontier, and expansion Orc 8 fixture 356 remains paused in its submarine
route-publication family. The earliest unpaused fleet finding is now expansion
Human 2 at fixture 554, followed by expansion Orc 11 at 557, expansion Human
10 at 562, expansion Human 7 at 573, Orc 13 at 576, and expansion Human 5 at
577.

## Prior release checkpoint — 2026-09-02 (owned oil-platform builds route to the patch centre)

Accepted cycle-1,800 receipt `ec005e92` preserves the shared clean horizon at
fixture 332 and improves or preserves every campaign frontier. The fleet
remains 13 clean / 39 divergent / 0 failed, and the directly recomputed sum of
the 52 exact prefixes rises from 53,561 in the preceding authenticated survey
to 54,081, a gain of 520 cycles. The preceding prose headline's 53,190 was
arithmetically stale; its retained `d6a55c37` survey itself records 53,561.
Expansion Human 3 advances from exact through fixture 536 to exact through
fixture 893, expansion Human 8 advances from 535 through 634, and Human 14
advances from 561 through 625. The cycle-400 fleet remains 50 clean / 2
divergent / 0 failed under receipt `f1d88fd5`. The long receipt is retained at
`.bne-artifacts/runs/ec005e92fb8ee4057639a6cc6570d38b5d6930490a2fc831136e2dbf46daa74d`.
Its manifest has SHA-256
`9276b2d7cab7838e2668e4dac7004291da5ef8051a7fefdb5474e2a7e74a6096`,
and binds clean commit `25b8ecd`, engine-input identity
`3c85517328c3ec675509b486e81782cd39da6d18f4a7cbcf3eb5e83fbfde7f86`,
and replayable source capsule
`0f09cafd2e3eae62c9612a6e14fe17edca2b7486ada8580866a877912fee1523`
with zero untracked engine inputs.

Behavioral delta: the constructor-ready tanker now considers only oil
platforms on its owner's roster. An allied computer's platform cannot suppress
the tanker's own platform build. Once action 28 is installed, an oil-platform
foundation retains the selected patch's top-left coordinate while its fixed
movement point is the patch centre. All on-top builds now take the same fixed
point walk as the native Build action rather than discarding that point for a
generic walk toward the top-left. The rule is expressed by ownership, building
resource type, on-top construction, and native order state; it contains no
mission, map, faction, coordinate, fixture, exact-cycle, route-length, or unit
ID branch.

Proof delta: expansion Human 8 player three's tanker is ready at fixture 536
while allied computer player seven owns the only reachable platform. Native
`0x439b1f` opens the tanker's owner roster and `0x439b4c` rejects a candidate
whose owner byte differs, so action 28 buys an orc oil platform and debits the
bank to 50 gold / 950 wood. Its patch stays `(41,85)`. Native action handler
`0x436a80` copies that top-left into the movement point and `0x41f670` tests
type flag `0x00000800`, which belongs exactly to the two platform types, then
increments both axes to `(42,86)`. The doubled tanker consequently commits
south-east to `(36,84)` at fixture 539 while the destroyer takes `(36,82)`.
Java formerly routed the tanker east to the destroyer's square. The corrected
case agrees through fixture 634; the newly exposed fixture-635 finding is the
tanker's x coordinate, native 42 versus Java 40. Human 14 and expansion Human
3 independently exercise the same platform-centre path and account for the
other 421 exact cycles gained.

Efficacy receipts
`.bne-test-efficacy/xhuman8-owned-platform/runs/992f6d03320c9d422e580e24d10ea01bea7556ff34b8a9fdcef473daa0c6b26b`
and
`.bne-test-efficacy/xhuman8-oil-platform-goal/runs/8df4ea544e6eeda82aa3a6169ff6267f5d89b95537e7e0e2807d6ca2fb564b8b`
prove the focused real-data checks execute and fail on their respective
pre-fix revisions, then execute and pass on the candidates. All four focused
oil-platform real-data checks pass. The 84-check construction, placement,
builder-permission, and oil-platform selection runs with 82 passes and two
inherited skips. Both fixed 52-case gates pass, and the long receipt's source
capsule authenticates and replays exactly. The ordinary executable next-level
gate exits zero after 209 Python checks (four skipped), 99 engine/desktop
checks, and 223 dual-adapter command scenarios; its 11 comparable scenarios
remain 6 exact / 5 divergent with no regression or infrastructure failure.
The optional SSH discovery still refuses the stale `i9beef` host key; local
native capture remains available and no SSH bypass was used.

Expansion Human 12 fixture 333 remains the paused shared-boundary route
frontier, and expansion Orc 8 fixture 356 remains paused in its submarine
route-publication family. The earliest unpaused fleet finding is now expansion
Orc 11 at fixture 539, followed by expansion Human 2 at 554, expansion Human
10 at 562, expansion Human 7 at 573, Orc 13 at 576, and expansion Human 5 at
577.

## Prior release checkpoint — 2026-09-02 (settled ranged loops leave constructors to opcode ten)

Accepted cycle-1,800 receipt `d6a55c37` preserves the shared clean horizon at
fixture 332 and improves or preserves every campaign frontier. The fleet
remains 13 clean / 39 divergent / 0 failed, while the established 52-case
exact-prefix score moves from 53,020 to 53,190. Retail Human 5 is the only
changed case, advancing from exact through fixture 535 to exact through fixture
705. The cycle-400 fleet remains 50 clean / 2 divergent / 0 failed under
receipt `5ae5ed48`. The long receipt is retained at
`.bne-artifacts/runs/d6a55c37e468ebdf045f160cae37ad337ca8010cd557ccfba95758b495f2f86b`.
Its manifest has SHA-256
`708f3fab695b87d505560c58a47607b1ba06b9fd4499cf0a431c3cacbc5f0d4a`
and binds clean commit `da40193`, engine-input identity
`da4049495a90e5a7a7ed536547b8124fd57b67314993d95f267814f789911e18`,
and replayable source capsule
`ea5c530f910ca5f290e17b322b30af967700cf89214c1f6bddd9ffec90b8f93c`
with zero untracked engine inputs.

Behavioral delta: a settled repeated ranged attack no longer lets the
presentation callback construct its next projectile merely because its timer
has reached the former mid-wait shortcut. Only a presentation-ahead attack
with an open ranged residual may take that constructor path; otherwise opcode
ten owns projectile damage and jitter at the native cadence. The obsolete
timer-collapse path is removed. The rule contains no mission, map, faction,
coordinate, fixture, exact-cycle, route-length, unit-ID, or unit-type-ID
branch.

Proof delta: retail Human 5 axethrower native slot 1534 / Java unit 66 emits
projectiles at fixtures 17, 82, 147, 212, 277, 342, 407, and 472, establishing
the 65-cycle loop and a next native constructor at fixture 537. Java formerly
constructed during the fixture-534 visual callback while its sequence cursor
was 900 and timer was 3, prematurely consuming the projectile damage and two
jitter draws. That shifted asynchronous ownership so native critter slot 1598
received result 5059 and did not wander at fixture 536, while Java's paired
unit received the later result 16902 and wandered. The corrected engine leaves
those draws to opcode ten at fixture 537, including native projectile damage
5, and agrees through fixture 705. The newly exposed fixture-706 finding is
peasant slot 1513 y 99 versus Java y 100.

Efficacy receipt
`.bne-test-efficacy/human5-repeated-ranged-constructor/runs/f7dcf78fb2161e0d73e1f122e59420168a9e9ba12319bfa515fde93ee7c6b8c4`
proves the focused real-data assertion executes and fails on `643a782`, then
executes and passes on the candidate. All 16 focused constructor and
presentation-ahead checks pass, as do all 64 projectile-lifecycle checks. Both
fixed 52-case gates pass. The ordinary executable next-level gate exits zero
after 209 Python checks (four skipped), 99 engine/desktop checks, and 223
dual-adapter command scenarios; its 11 comparable scenarios remain 6 exact /
5 divergent with no regression or infrastructure failure.

Expansion Human 12 fixture 333 remains the paused shared-boundary route
frontier, and expansion Orc 8 fixture 356 remains paused in its submarine
route-publication family. The earliest unpaused fleet finding is now expansion
Human 8 at fixture 536, followed by expansion Human 3 at 537, expansion Orc 11
at 539, and expansion Human 2 at 554.

## Prior release checkpoint — 2026-09-02 (in-range land chasers finish the committed stride)

Accepted cycle-1,800 receipt `5b2e2eda` preserves the shared clean horizon at
fixture 332 and improves or preserves every campaign frontier. The fleet
remains 13 clean / 39 divergent / 0 failed, while the 52 per-case exact
prefixes move from 52,999 to 53,020. Expansion Human 2 is the only changed
case, advancing from exact through fixture 532 to exact through fixture 553.
The cycle-400 fleet remains 50 clean / 2 divergent / 0 failed under receipt
`f3297938`. The long receipt is retained at
`.bne-artifacts/runs/5b2e2eda085526b1e4feb0821b5835790093f0cf355ad5f28506d90126606bdc`.
Its manifest has SHA-256
`312eaad20d57b6cb4c2be5a457608948fca72f551a41bd37d217d0e4ac652f86`
and binds clean commit `1dbe337`, engine-input identity
`d4e17d790328e36693db1b6f8f28dcced2500167215ac0556dca409e453c2a9b`,
and replayable source capsule
`928d5e322a053ae9d28cd535d5e60749f66179d518e2326dedf7835b8ed355af`
with zero untracked engine inputs.

Behavioral delta: when a ranged land chaser's current logical tile has entered
range of a stationary target, the current MoveToTarget visit no longer accepts
another cached route heading. It instead advances the Move presentation once,
pays the authoritative `script.bin` pixel count still owed by the committed
stride, parks the old route, and returns ownership directly to Attack. Moving
quarries retain their visible residual ownership, and naval chasers retain
their separate hit-response and broadside arrival rules. The rule contains no
mission, map, faction, coordinate, fixture, exact-cycle, route-length, unit-ID,
or unit-type-ID branch.

Proof delta: expansion Human 2 death knight native slot 1557 / Java unit 43
chases barracks slot 1554 / Java unit 46. At fixture 532 it is already in range
on `(63,60)`, has pixel debt `(+3,-3)`, and still carries cached `S,S,S` after
the committed southwest stride. Native fixture 533 pays those last three
pixels, stays on `(63,60)`, parks its route cursor, and opens Attack. Java
formerly consumed south and moved logically to `(63,61)`. The corrected route
and attack handoff agree through fixture 553; the newly exposed fixture-554
finding is the barracks HP, native 705 versus Java 696. Expansion Orc 11 is the
negative control: applying the land rule to its battleship made cannon damage
arrive at fixture 91 instead of preserving that mission through fixture 538;
the movement-class guard retains the established naval behavior and c539
frontier.

Efficacy receipt
`.bne-test-efficacy/xhuman2-c533-ranged-arrival/runs/29b541d3e24be8af17ef6fc607de30eb2ae7e5f1b0d42aac5f4bd4d12dd785b3`
proves the focused expansion-Human-2 assertion executes and fails on
`f9aea06`, then executes and passes on the candidate. All 57 selected ranged
arrival, attack-resume, moving-quarry, XOrc-11 naval, and capital-ship controls
pass. Both fixed 52-case gates pass. The ordinary executable next-level gate
exits zero after 209 Python checks (four skipped), 99 engine/desktop checks,
and 223 dual-adapter command scenarios; its 11 comparable scenarios remain
6 exact / 5 divergent with no regression or infrastructure failure.

Expansion Human 12 fixture 333 remains the paused shared-boundary route
frontier, and expansion Orc 8 fixture 356 remains paused in its submarine
route-publication family. The tied earliest unpaused fleet findings are now
retail Human 5 and expansion Human 8 at fixture 536, followed by expansion
Human 3 at 537, expansion Orc 11 at 539, and expansion Human 2 at 554.

## Prior release checkpoint — 2026-09-02 (only fresh terminal gold-skirt steps yield)

Accepted cycle-1,800 receipt `fc440dc7` preserves the shared clean horizon at
fixture 332 and improves or preserves every campaign frontier. The fleet
remains 13 clean / 39 divergent / 0 failed, while the 52 per-case exact
prefixes move from 52,658 to 52,999. Orc 12 is the only changed case,
advancing from exact through fixture 530 to exact through fixture 871. The
cycle-400 fleet remains 50 clean / 2 divergent / 0 failed under receipt
`064f4cad`. The long receipt is retained at
`.bne-artifacts/runs/fc440dc723f4e13df7dd01a3e5617f8838db5485aa1b818067d2aa4bfe496090`.
Its manifest has SHA-256
`f252721253067310e1b2984ff3972abe1325fc5ab6692b99f4285193cda6293f`
and binds clean commit `4cc8598` and engine-input identity
`a8bf3fc465869d0cc71409677e7dfb75324565c2e7c532845bd7d258b90b6eb8`
to replayable source capsule
`a97515186eb0335f0e511d165f2e9de6f74172b7cb2be03877e768f53129f2ee`
with zero untracked engine inputs.

Behavioral delta: the existing marked gold-skirt exception now admits only a
same-pass terminal step whose complete pixel debt still points back along its
last heading. A merely moving, collision-free, route-spent sibling is not
enough. This makes the implementation match its native scheduler contract:
the optimizer may accept a square a sibling has just entered, but an older
stride already draining across that square remains hard to route ordering.
The rule uses movement state and geometry and contains no mission, map,
faction, coordinate, fixture, exact-cycle, route-length, or unit-ID branch.

Proof delta: Orc 12 gold worker native slot 1525 / Java unit 75 is at `(87,41)`
on fixture 530, routing toward mine slot 1529 / Java unit 71. Outbound sibling
slot 1415 / Java unit 185 occupies the marked skirt at `(84,42)` with an empty
spent route and collision zero, but its older west step has already drained
from a complete `(+32,0)` debt to `(+23,0)`. Native therefore stores
`[W,W,W,SW]`; Java formerly treated that body like a newly committed step,
stored `[SW,W,W,W]`, and moved to y 42 on fixture 531. The corrected route
agrees through fixture 871. Orc 11 is the positive control: slot 1490 / Java
unit 110 has just committed south-west onto `(8,122)` with complete
`(+32,-32)` debt before slot 1505 / Java unit 95 routes, so that marked square
still yields and the native six-heading approach is preserved. The newly
exposed Orc 12 fixture-872 finding is player-three's bank.

Efficacy receipt
`.bne-test-efficacy/orc12-c531-partially-drained-skirt/runs/ea7c58de2fb5fb4bce5f74df48a181714a23c7600a0ad44478a5ae376448d9b6`
proves the focused Orc-12 assertion executes and fails on `0c0ad78`, then
executes and passes on the candidate. The three Orc-11/Orc-12 marked-skirt
checks pass, as do all 73 directly affected marked-skirt, pathfinder,
movement-playability, and moving-quarry checks. The broader 36-check synthetic
resource-approach class retains the same seven failures on `0c0ad78` and the
candidate, recorded by baseline-control receipt
`.bne-test-efficacy/orc12-c531-resource-approach-baseline-control/runs/f40cd984a9417630ba794fec0223c3fe2d896ca48bfbaa9c88493ecf116bf01a`;
it is inherited debt rather than an acceptance claim. Both fixed 52-case gates pass. The
ordinary executable next-level gate exits zero after 209 Python checks (four
skipped), 99 engine/desktop checks, and 223 dual-adapter command scenarios;
its 11 comparable scenarios remain 6 exact / 5 divergent with no regression
or infrastructure failure.

Expansion Human 12 fixture 333 remains the paused shared-boundary route
frontier, and expansion Orc 8 fixture 356 remains paused in its submarine
route-publication family. The earliest unpaused fleet finding is now expansion
Human 2 fixture 533, followed by Human 5 and expansion Human 8 at 536,
expansion Human 3 at 537, and expansion Orc 11 at 539.

## Prior release checkpoint — 2026-09-02 (marked target-skirt movers remain soft)

Accepted cycle-1,800 receipt `c8241d4d` preserves the shared clean horizon at
fixture 332 and improves or preserves every campaign frontier. The fleet
remains 13 clean / 39 divergent / 0 failed, while the 52 per-case exact
prefixes move from 52,605 to 52,658. Human 8 is the only changed case,
advancing from exact through fixture 529 to exact through fixture 582. The
cycle-400 fleet remains 50 clean / 2 divergent / 0 failed under receipt
`9202d398`. The long receipt is retained at
`.bne-artifacts/runs/c8241d4d732be9c80dcc9153a3d5b30b86d7720b9e0cd1edeed43771b3775bb5`.
Its manifest has SHA-256
`5a0993cafdb5a7cec46a68f796b5523472804947c7776c04839ebd37a04f50d0`
and binds clean commit `d22306e` and engine-input identity
`dbc5c1466a9b9da1b28481473211ecbc0900b725c0a2520c0da1cbaa8c6f26d4`
to replayable source capsule
`072d39ca411644446e091b7661e36bf0917feb024da8850f802d6b472173efad`
with zero untracked engine inputs.

Behavioral delta: the existing approach-corridor compensation no longer
re-hardens a zero-collision moving ally already inside the marked target
skirt. Native `0x4508f0` writes goal bit `0x8000`, but `0x4500f0` still admits
the Move body to the occupancy clear at `0x4501d3`; wall-follow may therefore
end on that marked square. Java keeps the compensation for one-heading combat
allies farther up the approach. The implementation contains no mission, map,
faction, coordinate, fixture, exact-cycle, route-length, or unit-ID branch.

Proof delta: Human 8 attack-peasant slot 1505 / Java unit 95 begins the paid
route at `(78,61)` toward peasant slot 1519 at `(78,66)`. Moving allied
attack-peasant slot 1526 / Java unit 74 occupies `(78,65)` with collision zero
and a one-byte route. Native soft-clears that body and stores `[S,S,SE,SW]`;
Java formerly re-hardened it and stored `[S,S,SE,S]`, producing the fixture-530
x split (native 78, Java 79). The corrected route agrees through fixture 582;
the newly exposed fixture-583 finding is slot 1520 x, native 77 versus Java 76.

Efficacy receipt
`.bne-test-efficacy/human8-c530-marked-skirt-soft-mover/runs/b98aa259f87ddd476a936c54c84256cb814eabba30d71969815ce1706a19fdc7`
proves the focused Human-8 assertion executes and fails on `28d1b89`, then
executes and passes on the candidate. All 23 moving-quarry real-data checks
pass. Both fixed 52-case gates pass. The movement referee's inherited
`NavalPatrolCoastGoalRealDataTest` failure reproduces identically on `28d1b89`
and the candidate; its script also still expects 116 checks while that baseline
runs 117, so neither issue is attributed to this change. The ordinary
executable next-level gate exits zero after 209 Python checks (four skipped),
99 engine/desktop checks, and 223 dual-adapter command scenarios; its 11
comparable scenarios remain 6 exact / 5 divergent with no regression or
infrastructure failure.

Expansion Human 12 fixture 333 remains the paused shared-boundary route
frontier, and expansion Orc 8 fixture 356 remains paused in its submarine
route-publication family. The earliest unpaused fleet finding is now Orc 12
fixture 531, followed by expansion Human 2 at 533, Human 5 and expansion Human
8 at 536, expansion Human 3 at 537, and expansion Orc 11 at 539.

## Prior release checkpoint — 2026-09-02 (trained units use the native producer-sized perimeter)

Accepted cycle-1,800 receipt `abf9f793` preserves the shared clean horizon at
fixture 332 and improves or preserves every campaign frontier. The fleet
remains 13 clean / 39 divergent / 0 failed, while the 52 per-case exact
prefixes move from 52,243 to 52,605. Expansion Human 5 advances from exact
through fixture 529 to exact through fixture 576. Human 10 advances from 548
to 688, Human 13 from 546 to 643, Human 12 from 545 to 582, Orc 13 from 548 to
575, and Human 14 from 547 to 561. The cycle-400 fleet remains 50 clean / 2
divergent / 0 failed under receipt `a4f27ecc`. The long receipt is retained at
`.bne-artifacts/runs/abf9f793f2c3abac8eed451596ce3b15ace6371cb904015970882de7f77bcee8`.
Its manifest has SHA-256
`8d6e24430889041f5653871ce3be596252cc53bbeba3e099e61f3d5136d5638a`
and binds clean commit `9cae241` and engine-input identity
`6dd540fb53cb5092e4d3ceeccf2e86eabfb4c598b66ae51ebe740e1202d9de3e`
to replayable source capsule
`c9cf6bc09144b10600a0edcda5d365e3fc1d64edc54a67ae578851e5200605ad`
with zero untracked engine inputs.

Behavioral delta: BNE training completion uses the shared `0x00443a40`
first-legal-anchor perimeter walker. Caller `0x0040df48` passes the producer
anchor and producer-type footprint to `0x00451a70`; callback `0x004512a0`
validates the trainee's movement mask and rejects odd x or y anchors for a
doubled-movement unit. Java now reuses its established native perimeter walk
with those distinct producer and trainee roles. The ordinary non-BNE engine
retains its prior generic dropout behavior. The implementation contains no
mission, map, faction, coordinate, fixture, exact-cycle, route-length, or
unit-ID branch.

Proof delta: expansion Human 5's player-three Orc shipyard, native slot 1534 /
Java unit 66 at `(35,105)`, finishes its first tanker on fixture 530. Native
rejects the odd first candidate `(34,105)` and accepts `(34,106)` for tanker
slot 1500. Java formerly incorporated the 2x2 trainee hull into the generic
footprint ring and surfaced unit 100 at `(34,108)`. The corrected anchor and
subsequent orders agree through fixture 576. Expansion Human 3's fixture-536
birth at `(80,32)` and expansion Human 8's fixture-532 birth at `(34,82)` are
held-out already-correct controls.

Efficacy receipt
`.bne-test-efficacy/runs/75ac425bb04813e75f403dc71988beadf4891423cbde256b12389d0869bee455`
proves the focused expansion-Human-5 assertion executes and fails on
`59f402d`, then executes and passes on the candidate. The three focused birth
checks pass, as do 58 executed oil/dropout family checks with three documented
asset-dependent skips. Both fixed 52-case gates pass. The ordinary executable
next-level gate also exits zero after 209 Python checks (four skipped), 99
engine/desktop checks, and 223 dual-adapter command scenarios; its comparable
set remains 6 exact / 5 divergent with no regression or infrastructure
failure. The stricter completion form correctly remains unsatisfied by the
broader program's outstanding certification lanes.

Expansion Human 12 fixture 333 remains the paused shared-boundary route
frontier, and expansion Orc 8 fixture 356 remains paused in its submarine
route-publication family. The earliest unpaused fleet finding is now Human 8
fixture 530, followed by Orc 12 at 531, expansion Human 2 at 533, Human 5 and
expansion Human 8 at 536, expansion Human 3 at 537, and expansion Orc 11 at
539.

## Prior release checkpoint — 2026-09-02 (paid moving-quarry residuals retain collision pressure)

Accepted cycle-1,800 receipt `95972c68` preserves the shared clean horizon at
fixture 332 and improves or preserves every campaign frontier. The fleet
remains 13 clean / 39 divergent / 0 failed, while the 52 per-case exact
prefixes move from 52,240 to 52,243. Human 8 advances from exact through
fixture 526 to exact through fixture 529. The cycle-400 fleet remains 50 clean
/ 2 divergent / 0 failed under receipt `69c2568a`; its cycle-540 lookahead
remains 42 clean / 10 divergent under receipt `7ea78126`. The long receipt is
retained at
`.bne-artifacts/runs/95972c689a94d1faa4ad036769460d4e181d6b5f74ec453210f2526a45f87824`.
Its manifest has SHA-256
`d77b61c1512dff67307938735b83b7fbe39bf779574207934e7be2e0f9cf6d43`
and binds dirty engine-input identity
`49123f22bf926b74be9e7ff2edbfca7ebddae821ce670b96e4f5c89f5f9a86db`
at base revision `32b5508` to authenticated, replayable source capsule
`17a5aa4698c7072da015db15b97795bf48e4eabfb2a95b111e6c4805c90b7ab5`.

Behavioral delta: after a paid Attack refusal-recovery ray grants its first
heading, a blocked residual tail against a live moving non-building quarry
does not immediately return to the active-order Still callback. Native parks
the remaining route bytes, advances the packed collision generation, and
retains Move pressure: generations two through seven are one-count probes and
generation eight opens the full Move 15..1 band. Java now carries that
transaction with the existing moving-quarry residual provenance. Static
targets retain the prior active-order callback, which is the held-out
expansion Human 12 control. The implementation contains no mission, map,
faction, coordinate, fixture, exact-cycle, exact-route-length, or unit-ID
branch.

Proof delta: Human 8 native attack-peasant slot 1513 / Java unit 87 owns a paid
south-east probe with a two-byte tail on fixture 518. Native settles at
`(80,65)` on fixture 519 with collision generation one, remains in Move without
an asynchronous idle draw through generations two to seven, and opens
generation eight with timer 15 on fixture 526. Java formerly rearmed Attack on
fixtures 520, 523, and 526; those extra idle draws made critter slot 1495 /
Java unit 105 wander ten fixtures early at 527. The retained pressure restores
the native RNG seeds, leaves the critter Still at 527, and moves its wander to
fixture 537. The newly exposed Human 8 fixture-530 finding is attack-peasant
slot 1505's x position, native 78 versus Java 79.

Efficacy receipt
`.bne-test-efficacy/runs/f5778ce1a3b14526401692c8c6bba9e5344bd28c391e488f44ed670987fd3a39`
proves the focused residual-pressure assertion executes and fails on
`32b5508`, then executes and passes on the candidate. All 30 selected
moving-quarry and paid-refusal real-data checks pass. The cycle-400,
cycle-540, and cycle-1,800 fixed 52-case gates pass. The ordinary executable
next-level gate also exits zero after 209 Python checks (four skipped), 99
engine/desktop checks, and 223 dual-adapter command scenarios; its comparable
set remains 6 exact / 5 divergent with no regression or infrastructure
failure. Optional SSH AI discovery retains its documented strict host-key
debt, while the corpus and native-oracle evidence used here remain local to
`i9beef`.

Expansion Human 12 fixture 333 remains the paused shared-boundary route
frontier, and expansion Orc 8 fixture 356 remains paused in its submarine
route-publication family. The earliest unpaused fleet findings are now tied at
fixture 530: Human 8 and expansion Human 5. They are followed by Orc 12 at 531,
expansion Human 2 at 533, Human 5 and expansion Human 8 at 536, expansion Human
3 at 537, expansion Orc 11 at 539, and Human 13 at 547.

## Prior release checkpoint — 2026-09-02 (contained depot-ready workers select gold before dropout)

Accepted cycle-1,800 receipt `2b72bca2` preserves the shared clean horizon at
fixture 332 and improves or preserves every campaign frontier. The fleet
remains 13 clean / 39 divergent / 0 failed, while the 52 per-case exact
prefixes move from 52,216 to 52,240. Human 13 advances from exact through
fixture 522 to exact through fixture 546. The cycle-400 fleet remains 50 clean
/ 2 divergent / 0 failed under receipt `f3187ab8`; its cycle-540 lookahead
improves from 41 clean / 11 divergent to 42 clean / 10 divergent. The long
receipt is retained at
`.bne-artifacts/runs/2b72bca24c2a47e0aed503ae951eafab9743d82f6aff92fe821e6150d4c58915`.
Its manifest has SHA-256
`270db1defe1d5486f82e4d3899eebbc8ee137764cfee94138158634b754819c9`
and binds dirty engine-input identity
`630fb2476bdc60a324039d71fe3b8379867445e79e317eda2e8d67db43dda289`
at base revision `0293e02` to authenticated, replayable source capsule
`e2ad9e23e6c84980ac16145cddb35d28859fe1763d38ab4666ea0ae2d47a0db3`.

Behavioral delta: BNE's ready-gold finder is called by both an on-map Still
marker and hidden depot action 26. A healthy worker inside its valid worksite
is removed from occupancy but remains a valid native ready subject. Java's
broad `Unit.isAlive()` deliberately includes `!removed`, so using it as the
finder's sole liveness gate discarded every contained call. The finder now
admits only the explicit contained state -- positive HP, non-dying order, and
a valid worksite -- while still rejecting dead or detached off-map units. The
implementation contains no mission, map, faction, coordinate, fixture,
exact-cycle, route-length, or unit-ID branch.

Proof delta: Human 13 peon slot 1547 / Java unit 53 is contained in fortress
slot 1584 at `(81,2)` through fixture 522. Its hidden action-26 wait expires on
fixture 523. Native calls the ready worker policy before dropout, selects gold
mine slot 1544 at `(75,9)`, writes raw current action 2 / next action 23 and the
mine pointer, then uses that authored goal to traverse the crowded fortress
perimeter and surface at `(85,3)` with timer 25. Java's contained finder
formerly returned before the scan, fell through to the generic west exit at
`(80,6)`, and only selected the same mine on the following on-map idle visit.
The explicit contained-liveness rule restores the mine, queued Harvest, exit
face, and ready hold on fixture 523, retaining agreement through fixture 546.
The newly exposed fixture-547 finding is an Orc tanker population pairing.

Efficacy receipt
`.bne-test-efficacy/runs/1d26b85cfff0496a304226d7946b65bbcfebce69b2ef2503895cd637fc9feb81`
proves the contained-ready assertion executes and fails on `0293e02`, then
executes and passes on the candidate. All 20 selected mine-exit and depot-ready
real-data checks pass. The broader `BattleNetAiWoodTest` diagnostic retains its
identical pre-existing Human-13 wall-route failure on baseline and candidate
under control receipt
`.bne-test-efficacy/runs/441a4984a9500a7292c203b85cbaabf02694b0ac0612314be4c8a9c1109a8d9c`.
Both fixed 52-case gates pass, and the long receipt's source capsule
authenticates and replays its engine identity with zero sealed untracked
inputs.

The ordinary executable next-level gate exits zero after 209 Python checks
(four skipped), 99 engine/desktop checks, and 223 dual-adapter command
scenarios; its comparable set remains 6 exact / 5 divergent with no regression
or infrastructure failure. Optional remote AI discovery retains the documented
strict SSH host-key debt for `i9beef`; no host key was modified, and local
native evidence remains available directly on this machine.

Expansion Human 12 fixture 333 remains the paused shared-boundary route
frontier, and expansion Orc 8 fixture 356 remains paused in its submarine
route-publication family. The earliest unpaused fleet finding is now Human 8
fixture 527, followed by expansion Human 5 at 530, Orc 12 at 531, expansion
Human 2 at 533, expansion Human 8 and Human 5 at 536, expansion Human 3 at 537,
expansion Orc 11 at 539, and Human 13's newly exposed split at 547.

## Prior release checkpoint — 2026-09-02 (pre-opcode-ten tower shots retain their constructor)

Accepted cycle-1,800 receipt `7ae9bc42` preserves the shared clean horizon at
fixture 332 and improves or preserves every campaign frontier. The fleet
remains 13 clean / 39 divergent / 0 failed, while the 52 per-case exact
prefixes move from 52,173 to 52,216. Expansion Human 10 advances from exact
through fixture 518 to exact through fixture 561. The cycle-400 fleet remains
50 clean / 2 divergent / 0 failed under receipt `234256f3`; its cycle-540
lookahead improves from 40 clean / 12 divergent to 41 clean / 11 divergent.
The long receipt is retained at
`.bne-artifacts/runs/7ae9bc42f488f4ddaaf09c893e9de27eecd3d56803d3f173f8d5498b16116150`.
Its manifest has SHA-256
`5d436a886d9d0d52a33729d6d6bda56edd721fe14d11d04d7aa7a0cf0cfcb209`
and binds dirty engine-input identity
`5ad448ab2a3e712da6e921f4899eb0987eff54c23ea73f740aa553613e2a805a`
at base revision `8313977` to authenticated, replayable source capsule
`a7c99dbce218ace043c33cb022dda342acebec7f7d2763a7b0aa502c513bed15`.

Behavioral delta: a mobile ranged weapon's presentation impact may precede
its authoritative Attack opcode ten. The established building-target shortcut
may spend the projectile constructor's damage and two aim draws early only
when the sequence cursor is already parked on opcode ten with a positive wait;
a cursor on a preceding frame opcode retains the pending projectile and lets
the ordinary sequence reach opcode ten. This is a script-state discriminator,
not a timing or mission exception. The implementation contains no mission,
map, faction, coordinate, fixture, exact-cycle, route-length, or unit-ID
branch.

Proof delta: expansion Human 10 axethrower slot 1549 / Java unit 51 reaches its
guard-tower presentation impact at frame-30 offset 892 with timer three.
Native fixture 516 leaves the cursor at 892, decrements the timer to two, and
does not run the projectile constructor; fixture 524 reaches opcode ten at
offset 900 and spends constructor results 19837, 29071, and 25367. Java
formerly spent three results on fixture 516 and collapsed the wait, shifting
later per-unit asynchronous ownership even though the RNG stream remained
continuous. Knight slot 1485 consequently rolled seven rather than native
eight damage against catapult slot 1487 / Java unit 113 at fixture 519,
leaving 85 rather than 84 HP. The opcode discriminator restores both native
boundaries and agreement through fixture 561. The newly exposed fixture-562
finding is that catapult's synchronized-RNG and `(74,89)` versus `(73,90)`
position split.

The fixed-fleet inventory found only four executions of the old broad
building shortcut through cycle 540. The rejected expansion Human 10 event was
the sole pre-opcode-ten cursor at offset 892. Expansion Human 12 unit 127 and
the two established Human 5 building callbacks were already parked on opcode
ten at offset 900; their behavior is retained. Human 5 remains at fixture 536,
expansion Human 12 remains at its paused fixture-333 route frontier, and no
accepted case moves earlier.

Efficacy receipt
`.bne-test-efficacy/runs/711fbabdbcde38761517a1e1d907cb17ef8a7f061b2e87b7735207344b032c88`
proves the real-data constructor-timing assertion executes and fails on
`8313977`, then executes and passes on the candidate. All 55 focused
presentation-ahead and expansion-Human-10 real-data checks pass. Both fixed
52-case gates pass. The projectile lifecycle referee passes 64 flight, impact,
persistent-effect, and click-feedback checks. The long receipt's source
capsule authenticates and replays its engine identity with zero sealed
untracked inputs.

The ordinary executable next-level gate exits zero after 209 Python checks
(four skipped), 99 engine/desktop checks, and 223 dual-adapter command
scenarios; its comparable set remains 6 exact / 5 divergent with no regression
or infrastructure failure. Optional remote AI discovery retains the documented
strict SSH host-key debt for `i9beef`; no host key was modified, and local
native evidence remains available directly on this machine.

Expansion Human 12 fixture 333 remains the paused shared-boundary route
frontier, and expansion Orc 8 fixture 356 remains paused in its submarine
route-publication family. The earliest unpaused fleet finding is now Human 13
fixture 523, followed by Human 8 at 527, expansion Human 5 at 530, Orc 12 at
531, expansion Human 2 at 533, expansion Human 8 and Human 5 at 536, expansion
Human 3 at 537, and expansion Human 10's newly exposed split at 562.

## Prior release checkpoint — 2026-09-02 (ordinary moving-quarry tails release a lagging renderer wait)

Accepted cycle-1,800 receipt `8b9f364b` preserves the shared clean horizon at
fixture 332 and improves or preserves every campaign frontier. The fleet
remains 13 clean / 39 divergent / 0 failed, while the 52 per-case exact
prefixes move from 52,160 to 52,173. Retail Human 8 is the only changed case,
advancing from exact through fixture 513 to exact through fixture 526. The
cycle-400 fleet remains 50 clean / 2 divergent / 0 failed under receipt
`f7a7fd41`. The long receipt is retained at
`.bne-artifacts/runs/8b9f364b41e7253e0bee8f0e81b9ebb8228e3f17da36de37586457ae38811ffd`.
Its manifest has SHA-256
`970091b7007522c736358bfccb85f067e2fbdb23494cdc91960fb8247ebf1515`
and binds dirty engine-input identity
`a6d8981302912d0d2e8309c9b191fadf5dcbb624d0c49c852f4ef17aeefde6e6`
at base revision `e9dbd9a` to authenticated, replayable source capsule
`fd42bb4f1ce878a87fa70924cefd5794d02c7fea36abc2f96aa3d964415724cd`.

Behavioral delta: an ordinary completed land-melee Attack body against a live,
moving harvesting quarry may reach its authoritative OP0 after its paid-wrap,
residual-refill, and collision provenance has already been consumed while
Java's presentation cursor still owes a positive wait. The Attack body owns
that callback: it releases the lagging renderer, refreshes the quarry goal,
writes the new route, and consumes the first route byte immediately. The
existing paid moving-quarry rule remains gated by its explicit
wrap/refill/collision provenance. A presentation already at wait zero follows
ordinary `finishSwing`; hard refusals, live route buffers, in-range or invalid
goals, stationary/non-harvesting targets, ranged/naval units, and non-Attack
markers retain their established paths. The implementation contains no
mission, map, faction, coordinate, fixture, exact-cycle, route-length, or
unit-ID branch.

Proof delta: Human 8 attack-peasant slot 1526 / Java unit 74 agrees at
`(78,65)` through fixture 513 with Attack tail `2686/1`, no executable route
byte, zero collision/refusals, and consumed wrap/refill state. Native fixture
514 refreshes the moving quarry goal from `(78,66)` to `(78,67)`, publishes a
south-east byte, exposes Move `2603/1`, and logically steps to `(79,66)` in the
same callback. Java formerly left the renderer in charge until fixture 515;
it now makes the native transition on 514 and remains exact until the
independent critter slot-1495 Still-versus-Move finding at fixture 527. The
same attack-peasant's earlier fixture-456 presentation is the negative
witness: it has already reached wait zero, ordinary `finishSwing` releases it,
and the established fixture-457 south-east step and later fixture-513 timer
remain unchanged.

Efficacy receipt
`.bne-test-efficacy/human8-c514-ordinary-moving-quarry-tail/runs/877eabb004aab951619e96f822f6c4a59023f91761541f3d938259f952dde8f6`
proves the c514 assertion executes and fails on `e9dbd9a`, then executes and
passes on the candidate. All 51 selected moving-quarry, refusal, retarget,
behavior-one, and presentation controls pass. The legacy 21-test
`MeleeChaseReplanResidualTest` diagnostic retains the identical three
pre-existing failures on baseline and candidate under
`.bne-test-efficacy/human8-c514-melee-residual-baseline-control/runs/edd9ba71b2d847334de182df2cac44de0eb5fd730aad6f42644be916881ba973`.
Both fixed 52-case gates pass. The long receipt's source capsule authenticates
and replays its engine identity exactly with zero sealed untracked inputs.

The ordinary executable next-level gate exits zero after 209 Python checks
(four skipped), 99 engine/desktop checks, and 223 dual-adapter command
scenarios; its comparable set remains 6 exact / 5 divergent with no regression
or infrastructure failure. Optional remote AI discovery retains the documented
strict SSH host-key debt for `i9beef`; no host key was modified, and local
native evidence remains available directly on this machine.

Expansion Human 12 fixture 333 remains the paused shared-boundary route
frontier, and expansion Orc 8 fixture 356 remains paused in its submarine
route-publication family. The earliest unpaused fleet finding is now expansion
Human 10 fixture 519, followed by Human 13 at 523, Human 8 at 527, expansion
Human 5 at 530, Orc 12 at 531, expansion Human 2 at 533, Human 5 and expansion
Human 8 at 536, and expansion Human 3 at 537.

## Prior release checkpoint — 2026-09-01 (coward casters scan only vulnerable idle targets)

Accepted cycle-1,800 receipt `764340e4` preserves the shared clean horizon at
fixture 332 and improves or preserves every campaign frontier. The fleet
remains 13 clean / 39 divergent / 0 failed, while the 52 per-case exact
prefixes move from 52,139 to 52,160. Expansion Human 2 advances from exact
through fixture 511 to exact through fixture 532. The cycle-400 fleet remains
50 clean / 2 divergent / 0 failed under receipt `b0f2642d`. The long receipt
is retained at
`.bne-artifacts/runs/764340e49bd6dae60611af328f397372871b9e86b3ca00ed50bde92d21890e4f`.
Its manifest has SHA-256
`2b8141e20171632679f88ae1818aa2acff9feac9cdde27b4587bfd09bf88402e`
and binds dirty engine-input identity
`301c2a9943c27e74ef6664cefe1b1a345a5c365c477e2735a9a74bda3d46ed8d`
at base revision `1a55632` to authenticated, replayable source capsule
`950e2e01125fc23fb1622d1df3ef21d6e9288f727f8cb4abddb2f86f0ae717be`.

Behavioral delta: Still's native AutoAttack driver explicitly routes PUD
types 10, 11, 21, and 24 -- the four coward spellcasters -- around the
general special-type rejection and through the ordinary target scorer. It
then filters only the selected winner: a target carrying any `0x06000300`
special bit is eligible, as is any target lacking armed/mobile bit
`0x00080000`; an ordinary armed winner rejects the whole visit rather than
falling through to a lower-scoring passive target. A usable direct hit-owned
offer retains priority and still starts the established escape constructor.
Workers, tankers, and demolition units retain their no-scan rule. The
implementation contains no mission, map, faction, coordinate, fixture,
exact-cycle, route-length, or unit-ID branch.

Proof delta: expansion Human 2 death knight slot 1557 / Java unit 43 remains
Still at `(64,59)` through fixture 511. On fixture 512, native type byte 11
jumps at `0x0040a8d2` into `0x00409ff0`, selects enemy human barracks slot
1554 at `(60,63)`, admits its passive-building `0x00000020` flag word, and
queues action 12 through `0x0045324d`. The scheduler promotes the order at
`0x00452fa2`. Java formerly returned at the broad special/coward guard; it now
retains the same target, order, and constructor timing and matches through
fixture 532. The newly exposed fixture-533 finding is the death knight's
southward chase position, native Y 60 versus Java Y 61. Static disassembly and
local Branch Witness receipt
`.bne-branch-witness/runs/43c986f9e6594de0e7c8c73ccb1585b9929aa29acc5a6e570ae11337c1881db9`
agree that this is idle AutoAttack rather than an AI-force or hit-help order.

Efficacy receipt
`.bne-test-efficacy/xhuman2-c512-caster-idle-scan/runs/e4e8a46dc3e0956c1e0ceb5eb6fcea7a40a2b5af2801db4d305851233cb6280f`
proves the focused real-data assertion executes and fails on `1a55632`, then
executes and passes on the candidate. Six focused caster, armed-winner,
demolition, and Orc 11 direct-flee/no-scan controls pass. The idle-targeting
gate passes its retail referee and fail-closed diagnostics: the expanded
`BattleNetIdleAttackTest` inventory is 30 passing / 1 classified, while
`TargetChoiceTest` remains 4 passing / 5 classified, with zero unclassified
failures. Both fixed 52-case gates pass. The scheduler gate passes six
retail/RNG checks and its legacy diagnostic remains 19 passing / 3 classified
/ 0 unclassified. The long receipt's source capsule authenticates and replays
its engine identity exactly with one sealed untracked input, the focused
real-data test.

The ordinary executable next-level gate exits zero after 209 Python checks
(four skipped), 99 engine/desktop checks, and 223 dual-adapter command
scenarios; its comparable set remains 6 exact / 5 divergent with no regression
or infrastructure failure. The retail AI gate retains the pre-existing
`AiCompetenceTest` route-prefix exception outside this change; control receipt
`.bne-test-efficacy/caster-idle-ai-gate-baseline-control/runs/9334a1e7866b3667f6bd3523342c44a58800838e1b4c309b4d35a9aed2b31955`
reproduces the same one executed test / zero failures / one error on both
`1a55632` and the candidate. Optional remote AI discovery retains the
documented strict SSH host-key debt for `i9beef`; no host key was modified,
and local native evidence remains available directly on this machine.

Expansion Human 12 fixture 333 remains the paused shared-boundary route
frontier, and expansion Orc 8 fixture 356 remains paused in its submarine
route-publication family. The earliest unpaused fleet finding is now Human 8
fixture 514, followed by expansion Human 10 at 519, Human 13 at 523, expansion
Human 5 at 530, Orc 12 at 531, expansion Human 2 at 533, expansion Human 8 at
536, and expansion Human 3 at 537.

## Prior release checkpoint — 2026-09-01 (ready scans advance through consumed research milestones)

Accepted cycle-1,800 receipt `ab9f6f1f` preserves the shared clean horizon at
fixture 332 and improves or preserves every campaign frontier. The fleet
remains 13 clean / 39 divergent / 0 failed, while the 52 per-case exact
prefixes move from 51,991 to 52,139. Expansion Human 10 advances from exact
through fixture 511 to exact through fixture 518, and expansion Human 11
advances from exact through fixture 508 to exact through fixture 649. The
cycle-400 fleet remains 50 clean / 2 divergent / 0 failed under receipt
`d398ce40`. The long receipt is retained at
`.bne-artifacts/runs/ab9f6f1f6b47b91e874d7aab20219bec4ce1896cee09ef1c58798f7fe18f5da0`.
Its manifest has SHA-256
`871b494c8333d0ac3bf83119beb324afe8b456ea97f7745875616e131e34c73c`
and binds dirty engine-input identity
`4317075f6b78871a7f3f498a366b3e8bf3d4a20e3b9d6af40fe81f8ddc2af3eb`
at base revision `2d9468c` to authenticated, replayable source capsule
`7ce9c0faeb113c3a3bbe489d15a86639cf53a3d4444ddd3a157fd0aa1500123f`.

Behavioral delta: `FUN_00439740`'s ordered ready-worker scan walks past a high
research byte after its producer has consumed that milestone, exposing the
next unresolved high byte on a later ready boundary. For low unit/building
entries, the native affordability check precedes the shared
`UnitTypeBuilt[type]` pending count: an unaffordable entry stops the scan even
when that type already has a dispatched construction. The action-33 lumber-
mill producer also retains its own constructor cadence, distinct from the
same-profile blacksmith cadence, before consuming the exposed `0x80`
milestone. The implementation contains no mission, map, coordinate, fixture,
exact-cycle, faction, route-length, or unit-ID branch.

Proof delta: expansion Human 11 player 2 has already consumed high byte
`0x86`; peon slot 1538's mine-exit ready scan on fixture 501 walks on and arms
`0x80`. Lumber-mill slot 1540 then queues throwing-axe research on fixture
509, leaving the native 1,350 gold / 900 lumber bank. Expansion Human 10
player 2 follows its own mill cadence and queues the same second milestone on
fixture 512, leaving 4,450 / 4,150. Expansion Human 9 player 6 is the negative
control: its fixture-507 scan reaches zero-row list position four, watch-tower
code `0x40`, with an existing pending tower but only 450 gold. Native
exits there with the `0x80` candidate flag clear and does not spend 300 / 300
at fixture 514. Host GDB execution and pinned-binary static analysis agree
that the `0x40dcd0` resource check runs before the pending building-count
read.

Efficacy receipt
`.bne-test-efficacy/sequential-action33-research-v2/runs/5eeab04622c732a36dd6f4ee8bee9847b4faa8b804465f4075589c14019bccab`
proves all three real-data assertions execute: two fail on `2d9468c`, and all
three pass on the candidate. The selected research/train/upgrade family runs
61 checks with zero failures or errors and nine asset/profile skips. Both
fixed 52-case gates pass. The scheduler gate passes six retail/RNG checks and
the 22-case legacy diagnostic remains 19 passing / 3 classified / 0
unclassified. The ordinary executable next-level gate exits zero after 209
Python checks (four skipped), 99 engine/desktop checks, and 223 dual-adapter
command scenarios; its comparable set remains 6 exact / 5 divergent with no
regression or infrastructure failure.

The retail AI gate retains one pre-existing `AiCompetenceTest` route-prefix
exception outside this change. Baseline-control receipt
`.bne-test-efficacy/sequential-action33-ai-gate-baseline-control/runs/ea99269fbba94b7346e0d2468e04db3f3f6b039f1b8b87fa26118daf105460c6`
reproduces the same one executed test / zero failures / one error on both
`2d9468c` and the candidate. Optional remote AI discovery also retains the
documented strict SSH host-key debt for `i9beef`; no host key was modified,
and local native evidence remains available directly on this machine. The
long receipt's source capsule authenticates and replays its engine identity
exactly with one sealed untracked input, the focused real-data test.

Expansion Human 12 fixture 333 remains the paused shared-boundary route
frontier, and expansion Orc 8 fixture 356 remains paused in its submarine
route-publication family. The earliest unpaused fleet finding is now
expansion Human 2 fixture 512, followed by Human 8 at 514, expansion Human 10
at 519, Human 13 at 523, expansion Human 5 at 530, and Orc 12 at 531.

## Prior release checkpoint — 2026-09-01 (restored capital Patrol owns its complete service leg)

Accepted cycle-1,800 receipt `b8c35423` preserves the shared clean horizon at
fixture 332 and improves or preserves every campaign frontier. The fleet
remains 13 clean / 39 divergent / 0 failed, while the 52 per-case exact
prefixes move from 51,958 to 51,991. Expansion Orc 11 is the only changed
case, advancing from exact through fixture 505 to exact through fixture 538.
The cycle-400 fleet remains 50 clean / 2 divergent / 0 failed under receipt
`fc5fd123`. The long receipt is retained at
`.bne-artifacts/runs/b8c35423c885d1e115fee1f59871f9e1e604e88f2aa530e532349050bffb5041`.
Its manifest has SHA-256
`070538354cfbbdf4e68e3fd945aa096b67d5810bf4de8f3cf994355f70ea581f`
and binds dirty engine-input identity
`203046681b907a5c67724067117b9044faeb867bfd29d2fd1dfcc08b48ada0f0`
at base revision `da33ceb` to authenticated, replayable source capsule
`a4e019447ce843023446e0164047062c2590bde410b57355cf7abe6dd253987c`.

Behavioral delta: a behavior-six capital ship whose paid Attack loses its
hostile quarry restores a separately saved Patrol order. That restored order,
not merely its opening opcode zero, owns the complete service leg and suppresses
spatial free acquisition until a fresh AI or player Patrol constructor replaces
it. The saveable capital-restore provenance now lasts for that order lifetime;
the established Stop, Attack, and Patrol replacement paths clear it. Fresh
capital Patrols, small warships, flyers, and land Patrol handoffs retain their
existing acquisition rules. The implementation contains no mission, map,
coordinate, fixture, exact-cycle, faction, route-length, or unit-ID branch.

Proof delta: expansion Orc 11 battleship slot 1539 / Java unit 61 restores its
saved Patrol toward behavior-six home `(21,34)` on fixture 397 and first-steps
north-east on fixture 400. On fixture 453 its later Move-body OP0 settles the
next southeast stride. Native keeps current order 5, next order 60, and goal
`(21,34)`; Java formerly selected destroyer slot 1521 / unit 79, silently
rewrote the goal to `(8,34)`, and banked Attack while the sealed visible state
still agreed. That latent queue became visible on fixture 506: native lands on
`(14,32)` at pixel `(384,960)`, remains Patrol, and restarts Move at sequence
2963 / timer one, while Java formerly stayed logically on `(12,30)` and
promoted Attack. The candidate matches through fixture 538. Its newly exposed
fixture-539 finding is the independent juggernaught slot-1512 HP split, native
107 versus Java 103.

Efficacy receipt
`.bne-test-efficacy/xorc11-c506-restored-capital-service-leg/runs/ee21e72a77f2f9b78da0c932817f4043f6da7c51678a8aa36ef1c15485e7574e`
proves the focused real-data assertion executes and fails on `da33ceb`, then
executes and passes on the candidate. All 16 focused XOrc 11 Patrol/Attack
checks pass. The 149-check Patrol, sea-occupancy, and save family has 148
passes and one pre-existing Human 7 submarine coast-goal failure; control
receipt
`.bne-test-efficacy/xorc11-c506-naval-patrol-baseline-control/runs/bacf647d2ca7ad2f1a717af3668735a6dbcf69c65199f65fc7240a31a10d0327`
reproduces that same assertion failure on both `da33ceb` and the candidate.
Both fixed 52-case gates pass. The long receipt's source capsule authenticates
and replays its engine identity exactly with zero sealed untracked inputs.
The ordinary executable next-level gate exits zero after 209 Python checks
(four skipped), 99 engine/desktop checks, and 223 dual-adapter command
scenarios; its 11 comparable scenarios remain 6 exact / 5 divergent with no
regression or infrastructure failure. Remote AI discovery retains the
documented strict SSH host-key debt for `i9beef`; no host key was modified,
and local native evidence remains available directly on this machine.

Expansion Human 12 fixture 333 remains the paused shared-boundary route
frontier, and expansion Orc 8 fixture 356 remains paused in its submarine
route-publication family. The earliest unpaused fleet finding is now expansion
Human 11 fixture 509, followed by expansion Human 2 and expansion Human 10 at
512, Human 8 at 514, Human 13 at 523, expansion Human 5 at 530, and expansion
Orc 11 at 539.

## Prior release checkpoint — 2026-09-01 (special types own hit responses but bypass help and idle scans)

Accepted cycle-1,800 receipt `af35d867` preserves the shared clean horizon at
fixture 332 and improves or preserves every campaign frontier. The fleet
remains 13 clean / 39 divergent / 0 failed, while the 52 per-case exact
prefixes move from 51,748 to 51,958. Retail Orc 11 is the only changed case,
advancing from exact through fixture 502 to exact through fixture 712. The
cycle-400 fleet remains 50 clean / 2 divergent / 0 failed under receipt
`fcfb8ee5`. The long receipt is retained at
`.bne-artifacts/runs/af35d8679acfb6c058dd6c11c0008559dbe7391e7726dbe15df52cd188e9118c`.
Its manifest has SHA-256
`b710d89893bc22537d726cf4e179d0e447baf7db455c559677c04cfd1da66eaa`
and binds dirty engine-input identity
`05e0790aa0d67da0e7f0ffe72c671f785b21ef65167ac2eef61902965c2f73cc`
at base revision `316d0d7` to authenticated, replayable source capsule
`34c99fe9badadf77e75ae13648aa0c56c3616e1973bc7dea9353bc07f4ffca30`.

Behavioral delta: HitUnit's native helper selector rejects candidates whose
type word carries any `0x06000300` bit before its armed/mobile test and
GiveOrder writer. Workers, oil tankers, spellcasters, and demolition units
can therefore own their direct hit response but cannot be recruited as a
nearby brother. Still's dispatcher applies the same type mask before ordinary
hostile acquisition: it tries the direct hit-owned escape constructor and
returns whether or not a live offer exists, rather than falling through to
AutoAttack. Ordinary combat brothers remain eligible for hit help and
ordinary combat types retain their idle scan. The implementation reuses the
authenticated ten-type flag set and contains no mission, map, faction,
coordinate, fixture, exact-cycle, route-length, or unit-ID branch.

Proof delta: Orc 11 sapper slot 1573 / Java unit 27 is struck at `(112,22)`
on fixture 417 and retains its direct one-tile northwest escape to `(111,21)`
on fixtures 418..421. Archer slot 1559 / Java unit 41 later hits ogre slot
1581 / Java unit 19 on fixture 499. Java formerly admitted the idle sapper to
the close-hit helper rectangle and promoted that queued Attack on fixture
503; native `FUN_0040a9d0` rejects its type at `0x0040abc5` under the
`0x06000300` test. Once that helper error was removed, Java formerly
auto-acquired knight slot 1548 / Java unit 42 on fixture 703, while native's
`FUN_0040a5e0` type arm kept the unoffered sapper Still. Both paths now match.
The newly exposed fixture-713 finding is a synchronized RNG draw that Java
spends one cycle later through the melee-sync loop, not another sapper order
split.

Efficacy receipts
`.bne-test-efficacy/orc11-c503-c703-special-type-idle/runs/1647138c7f99b038988be8615efd8ca04a5719cf9348a133840477bfcf16ade0`
and
`.bne-test-efficacy/special-type-idle-scan/runs/b43d3998ff284033572ecdf27d09d0f4be197e2a08d848b6e721b09bbdf915ae`
prove the focused real-data and dispatcher assertions each execute and fail on
`316d0d7`, then execute and pass on the candidate. The 117-test selected
hit-help, idle, retarget, damage-timing, and patrol family retains exactly one
pre-existing ranged-retarget failure, reproduced independently on `316d0d7`;
the other 116 checks pass. Both fixed 52-case gates pass. The long receipt's
source capsule authenticates and replays its engine identity exactly with
zero sealed untracked inputs. The ordinary executable next-level gate exits
zero after 209 Python checks (four skipped), 99 engine/desktop checks, and
223 dual-adapter command scenarios; its 11 comparable scenarios remain 6
exact / 5 divergent with no regression or infrastructure failure. Remote AI
discovery retains the documented strict SSH host-key debt for `i9beef`; no
host key was modified, and local native evidence remains available directly
on this machine.

Expansion Human 12 fixture 333 remains the paused shared-boundary route
frontier, and expansion Orc 8 fixture 356 remains paused in its submarine
route-publication family. The earliest unpaused fleet finding is now expansion
Orc 11 fixture 506, followed by expansion Human 11 at 509, expansion Human 2
and expansion Human 10 at 512, Human 8 at 514, Human 13 at 523, expansion
Human 5 at 530, and Human 5 at 536. Retail Orc 11 now follows at fixture 713.

## Prior release checkpoint — 2026-09-01 (fresh empty gold route falls through to claimed-face wood)

Accepted cycle-1,800 receipt `b78ed543` preserves the shared clean horizon at
fixture 332 and improves or preserves every campaign frontier. The fleet
remains 13 clean / 39 divergent / 0 failed, while the 52 per-case exact
prefixes move from 51,719 to 51,748. Retail Human 13 is the only changed case,
advancing from exact through fixture 493 to exact through fixture 522. The
cycle-400 fleet remains 50 clean / 2 divergent / 0 failed under receipt
`714cd996`. The long receipt is retained at
`.bne-artifacts/runs/b78ed5434538dbbf052559516e10b2da5445304db9d3c3ea6346cf6a6ebaf54b`.
Its manifest has SHA-256
`f1724f143d371b98b44b7a85af3b74e6e08a6efca4809f48a725800facf9f4f2`
and binds dirty engine-input identity
`29df39d7c147340906f243154d8859a8c3fbad6f69e931569b72eeaf3d8bc1de`
at base revision `de1cc1d` to authenticated, replayable source capsule
`502ed7160bdc274d9473ae35ba6644b38ca57d39303ec408d8eafda95eb1fbdd`.

Behavioral delta: a fresh action-23 gold constructor whose first route is
empty has no served Move to return through. UnitReady's adjacent terrain-wood
assignment therefore falls through to StartGathering on the same visit,
feeds native's occupied-face replacement callback, claims the replacement,
and spends the synchronized terrain-start draw. A completed gold walk retains
its independently authenticated north-row retry and path gate. Distant wood
fallbacks remain ordinary harvest routes. The implementation contains no
mission, map, faction, coordinate, fixture, exact-cycle, route-length, or
unit-ID branch.

Proof delta: Human 13 peon slot 1393 / Java unit 211 receives a fresh gold
Harvest assignment at `(14,3)` toward mine `(30,5)` with zero initial route,
zero steps taken, and no drained step. Native fixture 494 falls through to
claimed terrain face `(13,4)`, advances the synchronized seed from
`14526d52` to `23d3b823`, and arms terrain work three visits later. Java
formerly converted every failed gold route into the served-walk north-row
context, selected `(14,1)`, and omitted that draw. The fresh-constructor
handoff now matches native through fixture 522; the new fixture-523 finding is
an independent peon position split. Human 5 peon 1567's completed six-step
gold walk remains the negative witness: it keeps the `(104,46)` north-row
retry and stays exact through fixture 130.

Efficacy receipt
`.bne-test-efficacy/human13-c494-fresh-empty-gold-handoff/runs/8cb8241c43fc75b625da0096d8774ee6c3839532d0bcbd477ecf78a397aa37c0`
proves the focused real-data assertion executes and fails on `de1cc1d`, then
executes and passes on the candidate. The expansion Human 12 fresh-regroup
controls pass. The 36-test resource-approach class retains the same seven
pre-existing failures documented in the prior checkpoint, while the 17-test
AI-wood class retains its one unrelated ordinary marked-wall failure; neither
family acquires a candidate regression. Both fixed 52-case gates pass, and
the long receipt's source capsule authenticates and replays its engine
identity exactly with one sealed new test input. The ordinary executable
next-level gate exits zero after 209 Python checks (four skipped), 99
engine/desktop checks, and 223 dual-adapter command scenarios; its 11
comparable scenarios remain 6 exact / 5 divergent with no regression or
infrastructure failure. Remote AI discovery retains the documented strict SSH
host-key debt for `i9beef`; no host key was modified, and local native evidence
remains available directly on this machine.

Expansion Human 12 fixture 333 remains the paused shared-boundary route
frontier, and expansion Orc 8 fixture 356 remains paused in its submarine
route-publication family. The earliest unpaused fleet finding is now retail
Orc 11 fixture 503, followed by expansion Orc 11 at 506, expansion Human 11
at 509, expansion Human 2 and expansion Human 10 at 512, Human 8 at 514,
Human 13 at 523, and expansion Human 5 at 530.

## Prior release checkpoint — 2026-09-01 (doubled platform approach keeps the marked-wall tie)

Accepted cycle-1,800 receipt `5e908c4c` preserves the shared clean horizon at
fixture 332 and improves or preserves every campaign frontier. The fleet
remains 13 clean / 39 divergent / 0 failed, while the 52 per-case exact
prefixes move from 50,961 to 51,719. Retail Human 7 is the only changed case,
advancing from exact through fixture 492 to exact through fixture 1250. The
cycle-400 fleet remains 50 clean / 2 divergent / 0 failed under receipt
`0ca29b20`. The long receipt is retained at
`.bne-artifacts/runs/5e908c4c57370c3a8aab6c02128c8b879e6375e2764a5e2eefdadfd6ad0aacf5`.
Its manifest has SHA-256
`5ad3812b87c42970008c966717a061374bea9a57286cd43d9d32b3c98c12d19e`
and binds dirty engine-input identity
`734df56b619f66deffbe5a8e6c41864f628ba4c5bb84fccc5b39989200e8a048`
at base revision `d7536c8` to authenticated, replayable source capsule
`9a62edfad2741149e160431abbb8c6bdc9d04a410e18697af8463e1cbe3e968e`.

Behavioral delta: a doubled mover approaching a marked resource-building
skirt keeps the wall follower's route when its first heading makes the same
progress as the direct free prefix. Human 7's empty tanker therefore retains
`W,SW,W,W` around the rounded, blocked platform point instead of replacing it
with `W,W,W`. One-tile gold workers retain their independently authenticated
free-prefix convention, and refinery/depot returns stay on their separate
target router. The implementation contains no mission, map, faction,
coordinate, fixture, exact-cycle, route-length, or unit-ID branch.

Proof delta: Human 7 tanker slot 1502 / Java unit 98 asks for platform slot
1494 / Java unit 106 from `(60,82)` toward order point `(53,82)`. Native
`0x450350` publishes raw route `06 05 06 06`, consumes west on fixture 461,
and retains `SW,W,W`. Java's pathfinder already derived that wall route but
formerly selected its equal-gain `W,W,W` free prefix. Native consumes the
second southwest heading on fixture 493 and lands `(56,84)`; Java formerly
landed `(56,82)`. The marked-wall tie now matches that callback and remains
exact until the independent fixture-1251 peon slot 1582 Still-versus-Build
finding.

Efficacy receipt
`.bne-test-efficacy/human7-c493-marked-platform-wall-tie/runs/34a1f6620bf671bae5d265ff6053415a03c3a99dc6cc5d3517aa3ca0c2133cf8`
proves the focused assertion executes and fails on `d7536c8`, then executes
and passes on the candidate. The 29 pathfinder tests, ten oil-platform exit
tests, and five Human 7 oil-route controls all pass. The broader 36-test
resource-approach class retains the same seven pre-existing one-tile gold
failures on baseline and candidate, recorded under
`.bne-test-efficacy/human7-c493-resource-approach-baseline-control/runs/ef3c230b92b3b27784af8caff617eede95c52615d3818b616ee04ec918385da9`.
Both fixed 52-case gates pass, and the long receipt's source capsule
authenticates and replays its engine identity exactly with one sealed new test
input. The ordinary executable next-level gate exits zero after 209 Python
checks (four skipped), 99 engine/desktop checks, and 223 dual-adapter command
scenarios; its 11 comparable scenarios remain 6 exact / 5 divergent with no
regression or infrastructure failure. Remote AI discovery retains the
documented strict SSH host-key debt for `i9beef`; no host key was modified,
and local native evidence remains available directly on this machine.

Expansion Human 12 fixture 333 remains the paused shared-boundary route
frontier, and expansion Orc 8 fixture 356 remains paused in its submarine
route-publication family. The earliest unpaused fleet finding is now retail
Human 13 fixture 494, followed by Orc 11 at 503, expansion Orc 11 at 506,
expansion Human 11 at 509, expansion Human 2 and expansion Human 10 at 512,
Human 8 at 514, and Human 7 at 1251.

## Prior release checkpoint — 2026-09-01 (paid moving-quarry tail releases renderer ownership)

Accepted cycle-1,800 receipt `3a7e1718` preserves the shared clean horizon at
fixture 332 and improves or preserves every campaign frontier. The fleet
remains 13 clean / 39 divergent / 0 failed, while the 52 per-case exact
prefixes move from 50,938 to 50,961. Retail Human 8 is the only changed case,
advancing from exact through fixture 490 to exact through fixture 513. The
cycle-400 fleet remains 50 clean / 2 divergent / 0 failed under receipt
`d30afd70`. The long receipt is retained at
`.bne-artifacts/runs/3a7e1718bafd3bfed20edf556fe4c55dc19401b60ff1fb81f32c0d0605a52f72`.
Its manifest has SHA-256
`4960b55391153431828e413db45fb2868faf17312fe34d5a5546ecb98761a7c3`
and binds dirty engine-input identity
`6f753070019b3d0d16c1e1f4b7b9a4e9194a8b44729d26c607e75c3029ae0719`
at base revision `245f160` to authenticated, replayable source capsule
`8a68a8524319a587b832be99e2374f0aff48e3f53a98a8f3858bb34c742ae067`.

Behavioral delta: a completed Attack body against a live, moving harvesting
quarry can reach its authoritative OP0 while Java's parallel presentation
cursor still exposes an unbreakable tail frame. When the route is the paid
moving-quarry transaction identified by its wrap, collided residual-park
refill, empty settled buffer, and zero hard refusals, the renderer no longer
owns an extra callback. Retail refreshes the quarry goal, writes the next
route, switches to Move, and consumes the first byte on that same OP0 visit.
Ordinary swings, non-harvesting targets, ranged/naval units, uncollided tails,
hard refusals, live route buffers, in-range goals, and non-paid attack markers
retain their established presentation boundary. The implementation contains
no mission, map, faction, coordinate, fixture, exact-cycle, route-length, or
unit-ID branch.

Proof delta: Human 8 attack-peasant slot 1520 / Java unit 80 agrees through
Attack tail `2686/1` at `(78,64)` on fixture 490 while its live harvesting
quarry moves to `(78,66)`. Native fixture 491 refreshes the goal, writes the
south-east route byte, exposes Move `2603/1`, and logically steps to `(79,65)`.
Java formerly advanced only its Attack cursor to `2660/1`, let the stale
unbreakable renderer retain the callback, and took the identical south-east
step on fixture 492. The paid tail now hands the callback directly to Move,
matching native on fixture 491 and remaining exact until the independent
slot-1526 position finding at fixture 514.

Efficacy receipt
`.bne-test-efficacy/human8-c491-paid-moving-quarry-tail-boundary/runs/261ed32134635ea40a7dea2d85588f6823f7af1452280551dda1801352f77001`
proves the c491 assertion executes and fails on `245f160`, then executes and
passes on the candidate. All 20 moving-quarry real-data tests and the other 19
selected refusal/retarget/behavior-one controls pass. The selected 60-test
family remains 57/60 green: the same three
`MeleeChaseReplanResidualTest` assertions fail identically on baseline and
candidate, retained under
`.bne-test-efficacy/human8-c491-melee-residual-baseline-control`. Both fixed
52-case gates pass, and the long receipt's source capsule authenticates and
replays its engine identity exactly with zero sealed untracked inputs. The
ordinary executable next-level gate exits zero after 209 Python checks (four
skipped), 99 engine/desktop checks, and 223 dual-adapter command scenarios;
its 11 comparable scenarios remain 6 exact / 5 divergent with no regression
or infrastructure failure. Remote AI discovery retains the documented strict
SSH host-key debt for `i9beef`; no host key was modified.

Expansion Human 12 fixture 333 remains the paused shared-boundary route
frontier, and expansion Orc 8 fixture 356 remains paused in its submarine
route-publication family. The earliest unpaused fleet finding is now retail
Human 7 fixture 493, followed by Human 13 at 494, Orc 11 at 503, expansion Orc
11 at 506, expansion Human 11 at 509, expansion Human 2 and expansion Human 10
at 512, and Human 8 at 514.

## Prior release checkpoint — 2026-09-01 (paid-wrap replacement residual promotes its queued Attack)

Accepted cycle-1,800 receipt `c09dfb93` preserves the shared clean horizon at
fixture 332 and improves or preserves every campaign frontier. The fleet
remains 13 clean / 39 divergent / 0 failed, while the 52 per-case exact
prefixes move from 50,931 to 50,938. Retail Human 8 is the only changed case,
advancing from exact through fixture 483 to exact through fixture 490. The
cycle-400 fleet remains 50 clean / 2 divergent / 0 failed under receipt
`acb106da`. The long receipt is retained at
`.bne-artifacts/runs/c09dfb9373959ab4a71d9a106dd6b806085c809577cfcd04ec71cbb48f92d029`.
Its manifest has SHA-256
`787d7f457b6c170300f36b090b5997ea74913bff494d7abdea16aef1473a3dd1`
and binds dirty engine-input identity
`285af1ab5a24c07f818f3e088f2292eb57ecc7d942dc5836ce7ec9c83598cd94`
at base revision `b417245` to authenticated, replayable source capsule
`a4f1bb259f2416c4eb09d019d40c633105135df5f3401162616ab009757dca56`.

Behavioral delta: when a completed cold paid-wrap constructor replaces its
dying quarry exactly as a progress square opens, the replacement still
inherits the already-paid Move probe on that callback, but it also owns a
queued Attack behind the first replacement residual. The old route offer and
the distinct live target preserve that ownership while the first step drains.
On settlement, retail exposes a real Attack constructor `3,2,1`, keeps the
cached replacement tail executable, and consumes the prior moving-quarry
timer-one latch before returning ownership to Move. Ordinary retarget
residuals still park their stale route, while direct paid tails, patrol bodies,
naval routes, buildings, ranged units, and same-offer chases retain their
existing construction paths. The implementation contains no mission, map,
faction, coordinate, fixture, exact-cycle, route-length, or unit-ID branch.

Proof delta: Human 8 attack-peasant slot 1513 / Java unit 87 still changes
from dying peasant slot 1536 / Java 64 to returner slot 1519 / Java 81 and
spends south-east on fixture 468 without stealing critter slot 1544's
asynchronous fixture-470 Move. It retains south behind that stride and carries
the replacement's queued Attack owner while the pixels drain. Native and Java
then both hold `(79,63)` on Attack `2657/3,2,1` through fixtures 484..486 and
spend the cached south byte to `(79,64)` on fixture 487. Java formerly spent
south on fixture 484 and lost the position frontier there. The newly exposed
fixture-491 finding belongs to a different attack-peasant, slot 1520, at
native `(79,65)` versus Java `(78,64)`.

Efficacy receipt
`.bne-test-efficacy/human8-c484-cold-paid-wrap-residual-construction/runs/a55eecf53854803b5f922ce4a6c09406f10bc624102031494d186227693a99fd`
proves the focused assertion executes and fails on `b417245`, then executes
and passes on the candidate. All 20 moving-quarry real-data tests pass. The
selected moving-quarry, retarget-residual, refusal, and behavior-one control
set is 57/60 green; the three remaining `MeleeChaseReplanResidualTest`
assertions fail identically on baseline and candidate and are not claimed
green, as retained under
`.bne-test-efficacy/human8-c484-melee-residual-baseline-control`. Both fixed
52-case gates pass, and the long receipt's source capsule authenticates and
replays its engine identity exactly with zero sealed untracked inputs. The
ordinary executable next-level gate exits zero after 209 Python checks (four
skipped), 99 engine/desktop checks, and 223 dual-adapter command scenarios;
its 11 comparable scenarios remain 6 exact / 5 divergent with no regression
or infrastructure failure. Remote AI discovery retains the documented strict
SSH host-key debt for `i9beef`; no host key was modified.

Expansion Human 12 fixture 333 remains the paused shared-boundary route
frontier, and expansion Orc 8 fixture 356 remains paused in its submarine
route-publication family. The earliest unpaused fleet finding is now retail
Human 8 fixture 491, followed by Human 7 at 493, Human 13 at 494, Orc 11 at
503, expansion Orc 11 at 506, and expansion Human 11 at 509.

## Prior release checkpoint — 2026-09-01 (capital Patrol residual serves one paid Move band)

Accepted cycle-1,800 receipt `ae57ea9c` preserves the shared clean horizon at
fixture 332 and improves or preserves every campaign frontier. The fleet
remains 13 clean / 39 divergent / 0 failed, while the 52 per-case exact
prefixes move from 50,835 to 50,931. Expansion Human 7 is the only changed
case, advancing from exact through fixture 476 to exact through fixture 572.
The cycle-400 fleet remains 50 clean / 2 divergent / 0 failed under receipt
`c7ba02b1`. The long receipt is retained at
`.bne-artifacts/runs/ae57ea9c8195b8dc610a9fd582d886eede6c5c54a8aca6e0e3e319328092958d`.
Its manifest has SHA-256
`869eb982cdc4026df1f785ddd2461d03ab8dd56a43798c081db6439d2ba1416e`
and binds dirty engine-input identity
`2cacb07f84a34f192ea480a4e6b5e3697fa1020e854a7eacc9bfd101bbd0e330`
at base revision `263f208` to authenticated, replayable source capsule
`20e2280fbda11de3ea33d3c9e8037bae26a5f4a2aed8e5de2032d06136a454ca`.

Behavioral delta: a behavior-six capital ship whose cached Patrol route has
already spent a step may advance its packed collision generation and retain
the remaining tail for one complete Move refusal band when the next anchor is
still carried by a moving allied sea hull. Its timer-one visit returns through
the native next-path-element boundary; it does not wait for the much longer
sprite movement loop to wrap. Expansion Human 7 juggernaught slot 1573 / Java
27 is the positive witness: on fixture 462 it holds `(30,28)`, retains
`SE,E,SE,E` behind moving tanker slot 1571 / Java 29, advances raw collision
generation three to four, and exposes Move `15..1` through fixture 476. The
tanker has logically vacated `(32,30)` by then, so the fixture-477 wake spends
southeast to `(32,30)` with generation four retained. Java formerly projected
this as generic refusal one and waited for its sprite body until fixture 519.
Fresh capital-Patrol probes retain their established collision rule; settled
or hostile hulls, non-capital ships, land and air movers, and live combat
targets do not acquire this residual carrier rule. The implementation contains
no mission, map, faction, coordinate, fixture, exact-cycle, route-length, or
unit-ID branch.

Efficacy receipt
`.bne-test-efficacy/xhuman7-c477-capital-patrol-residual-band/runs/5ac754af9a6721637b30156ac207df5dc568550c0d3aceafeaa68b7146d18c21`
proves the focused assertion executes and fails on `263f208`, then executes
and passes on the candidate. Thirty-nine of 40 selected capital-ship, map-
patrol, sea-occupancy, coast-goal, and naval-residual checks pass. The remaining
`NavalPatrolCoastGoalRealDataTest` submarine assertion is not claimed green: it
fails identically on baseline and candidate, as retained under
`.bne-test-efficacy/xhuman7-c477-naval-coast-baseline-control`. Both fixed
52-case gates pass, and the long receipt's source capsule authenticates and
replays its engine identity exactly with zero sealed untracked inputs.

Expansion Human 12 fixture 333 remains the paused shared-boundary route
frontier, and expansion Orc 8 fixture 356 remains paused in its submarine
route-publication family. The earliest unpaused fleet finding is now retail
Human 8 fixture 484, followed by Human 7 at 493, Human 13 at 494, Orc 11 at
503, expansion Orc 11 at 506, and expansion Human 11 at 509. Expansion Human
7's newly exposed fixture-573 finding is peon slot 1446 at native x=109 versus
Java x=110.

## Prior release checkpoint — 2026-09-01 (paid residual return honors a moving sibling)

Accepted cycle-1,800 receipt `d5dcc1da` preserves the shared clean horizon at
fixture 332 and improves or preserves every campaign frontier. The fleet
remains 13 clean / 39 divergent / 0 failed, while the 52 per-case exact
prefixes move from 50,715 to 50,835. Retail Orc 8 is the only changed case,
advancing from exact through fixture 474 to exact through fixture 594. The
cycle-400 fleet remains 50 clean / 2 divergent / 0 failed under receipt
`01893f41`. The long receipt is retained at
`.bne-artifacts/runs/d5dcc1da11a6548ffff7962304edf3d5d869d3931b0b8e5989f66007574979f4`.
Its manifest has SHA-256
`315b69b55a518b9b64ffaae9e1007ad1073f9406c0196f9b83c66e06c4923161`
and binds dirty engine-input identity
`649b51e9758bf27bff93d8f75750e2dc399485f8e5f33b5e86ccfbdf17dc1b30`
at base revision `b292b3f` to authenticated, replayable source capsule
`99877434eadf840cd915c0f22388fe5d1996e3e896a85a4d0ee5334f292456db`.

Behavioral delta: a laden land returner whose borrowed Move residual has
already spent a step can own a paid refusal band through the sticky refusal
projection even while Java's collision projection trails. A moving allied
laden returner headed to the same depot remains the cooperative carrier while
it drains its sub-tile stride, even after its own route bytes are spent.
Retail Orc 8 peasant slot 1494 / Java 106 is the positive witness: its residual
south head meets moving sibling slot 1497 at `(123,94)` on fixture 474, advances
the native packed refusal owner from `0x80` to `0x90`, parks at route index 20,
and serves Move `15..1` before publishing `SE,S,S` and spending southeast on
fixture 489. Java formerly replanned and spent that southeast step on fixture
475. Ordinary unpaid residuals, fresh laden returns, non-land movers,
different-depot/alliance blockers, and settled cooperative bodies retain their
existing paths. The implementation contains no mission, map, faction,
coordinate, fixture, exact-cycle, route-length, or unit-ID branch.

Efficacy receipt
`.bne-test-efficacy/orc8-c475-paid-residual-return-sibling/runs/e8d9948fa586b73a1bae0fc3cc9682c494b47fd1945b579c4284fe25c54eb292`
proves the focused assertion executes and fails on `b292b3f`, then executes
and passes on the candidate. All 46 selected mine-exit, depot-overlap,
laden-return, refusal-sleep, convoy-route, and adjacent real-data controls pass
with zero skips. `BattleNetResourceApproachTest` is not claimed green: its
seven failures are the same seven assertions on both `b292b3f` and the
candidate, as retained under
`.bne-test-efficacy/orc8-c475-resource-approach-baseline-control`. Both fixed
52-case gates pass, and the long receipt's source capsule authenticates and
replays its engine identity exactly with zero sealed untracked inputs.

Expansion Human 12 fixture 333 remains the paused shared-boundary route
frontier, and expansion Orc 8 fixture 356 remains paused in its submarine
route-publication family. The earliest unpaused fleet finding is now expansion
Human 7 fixture 477, followed by retail Human 8 at 484, Human 7 at 493, Human
13 at 494, and Orc 11 at 503. Retail Orc 8's newly exposed fixture-595 finding
is peasant slot 1505 at native `(123,96)` versus Java `(122,97)`.

## Prior release checkpoint — 2026-09-01 (completed cold-retry body retires refusal ownership)

Accepted cycle-1,800 receipt `e8737b1a` preserves the shared clean horizon at
fixture 332 and improves or preserves every campaign frontier. The fleet
remains 13 clean / 39 divergent / 0 failed, while the 52 per-case exact
prefixes move from 50,704 to 50,715. Retail Human 8 is the only changed case,
advancing from exact through fixture 472 to exact through fixture 483. The
cycle-400 fleet remains 50 clean / 2 divergent / 0 failed under receipt
`524b488a`. The long receipt is retained at
`.bne-artifacts/runs/e8737b1a84b18dc1dc6751743457d15cb5b485771750087cbf188339dcdf2b74`.
Its manifest has SHA-256
`2444b2a46b000f2628775167c8e88bd7536d5b504ad451e8e0009991f8d49142`
and binds dirty engine-input identity
`e8e83a496739182c8f6e37d8a77d8c027dcc4749fdbbce238b786c9321f2c2b3`
at base revision `93fbb78` to authenticated, replayable source capsule
`54e8d5a34cac4c27df27b7b1b3567e4fe1d6ce28a988dce02bf1d93be1d9e07c`.

Behavioral delta: when a behavior-zero melee chaser reaches a committed OP0
body hold against a replacement while a distinct retired or dying quarry
remains offered, that body consumes both the queued retarget Attack owner and
its direct-refusal generation. A later chase of the replacement is therefore
an ordinary continuation of the open body, not another first residual of the
old cold retry. Retail Human 8 attack-peasant slot 1526 / Java 74 is the
positive witness: dying peasant slot 1533 remains offered while returner slot
1519 enters the body hold, the pursuer spends southeast on fixture 457, then
publishes `SW,S` and spends southwest as soon as that stride settles on
fixture 473. Java formerly retained the two retired owners, bought an extra
Attack `2657/3,2,1`, and delayed the identical route to fixture 476. The
corrected handoff consumes no synchronized RNG draw. Constructor-paying cold
retry legs before a body is committed, live/same offers, behavior-one
formations, and ordinary queued replan residuals remain negative controls.
The implementation contains no mission, map, faction, coordinate, fixture,
exact-cycle, route-length, or unit-ID branch.

Efficacy receipt
`.bne-test-efficacy/human8-c473-paid-body-continuation/runs/41de90c2485e93c23c4f4e55de64a07412d2da8622a5831beaa8274853585e5f`
proves the focused assertion executes and fails on `93fbb78`, then executes
and passes on the candidate. All 95 selected moving-quarry, paid-wrap,
replan-arrival, collision-refill, and damage-timing checks pass with zero
skips. Both fixed 52-case gates pass, and the long receipt's source capsule
authenticates with zero sealed untracked inputs.

Expansion Human 12 fixture 333 remains the paused shared-boundary route
frontier, and expansion Orc 8 fixture 356 remains paused in its submarine
route-publication family. The earliest unpaused fleet finding is now retail
Orc 8 fixture 475, followed by expansion Human 7 at 477 and retail Human 8 at
484. Human 8's new finding is attack-peasant slot 1513's one-row position
split.

## Prior release checkpoint — 2026-09-01 (settled ranged-retarget construction and cadence)

Accepted cycle-1,800 receipt `e6e6b2b5` preserves the shared clean horizon at
fixture 332 and improves or preserves every campaign frontier. The fleet
remains 13 clean / 39 divergent / 0 failed, while the 52 per-case exact
prefixes move from 50,662 to 50,704. Retail Orc 11 is the only changed case,
advancing from exact through fixture 460 to exact through fixture 502. The
cycle-400 fleet remains 50 clean / 2 divergent / 0 failed under receipt
`b5fd77cb`. The long receipt is retained at
`.bne-artifacts/runs/e6e6b2b524b20a1e7859149b308ab4c4e77d82147ff2aaaf79293673c1545050`.
Its manifest has SHA-256
`a75db44466d39cca24f5643bba67629cee92c8b3b215433cee353544ffcbc882`
and binds dirty engine-input identity
`58ce4f7e6b703cf2c83fec4d421149a5cc57f5cec58ce85f31c036e0e0f7ff52`
at base revision `479f899` to authenticated, replayable source capsule
`346499581a507513456ff995510945b281ac1b1e7b7c94864e37b9a1847d7a1a`.

Behavioral delta: when a mobile ranged chase has already drained its stride
and completed the stage-two refusal-recovery Attack constructor, an in-range
replacement selected from the still-live route receives its own Attack
constructor before a fresh ranged cadence. The old route is parked; it is not
replanned through a generic Move teardown and it does not inherit the old
quarry's partially spent cadence. Retail Orc 11 archer slot 1560 / Java 40 is
the positive witness: after settling against the ogre, it selects sapper slot
1573 on fixture 447, exposes Attack `2039/3,2,1`, and enters `2039/63` on
fixture 450. Java formerly exposed Move `1982/1`, entered windup, and created a
phantom fixture-459 arrow whose damage, two jitter draws, and first motion draw
shifted the asynchronous stream by four. The corrected stream leaves critter
slot 1532 Still on fixture 461. Human 13 axethrower slot 1505 is the draining-
stride negative control: its fixture-25 retarget is not completed stage-three
recovery, so it retains the established teardown and spends the replacement
route southwest on fixture 28. The implementation contains no mission, map,
faction, coordinate, fixture, exact-cycle, route-length, or unit-ID branch.

Efficacy receipt
`.bne-test-efficacy/orc11-c461-ranged-retarget-final/runs/7fe17c03e00f6b4fdaf06763e09bdf3b4f570c379cadb8da0a0507eb67d9ee64`
proves the focused assertion executes and fails on `479f899`, then executes
and passes on the candidate. All 35 selected Orc 11, ranged free-scan,
moving-quarry, blocked-tail, and Human 13 draining-stride controls pass with
zero skips. Both fixed 52-case gates pass, and the long receipt's source
capsule authenticates with zero sealed untracked inputs.

Expansion Human 12 fixture 333 remains the paused shared-boundary route
frontier, and expansion Orc 8 fixture 356 remains paused in its submarine
route-publication family. The earliest unpaused fleet finding is now Human 8
fixture 473, followed by Orc 8 at 475, expansion Human 7 at 477, Human 7 at
493, Human 13 at 494, and Orc 11 at 503. Orc 11's new finding is sapper slot
1573 remaining Still in native while Java enters Attack.

## Prior release checkpoint — 2026-09-01 (recurring land-patrol ranged tail handoff)

Accepted cycle-1,800 receipt `f9b86fd5` preserves the shared clean horizon at
fixture 332 and improves or preserves every campaign frontier. The fleet
remains 13 clean / 39 divergent / 0 failed, while the 52 per-case exact
prefixes move from 50,660 to 50,662. Retail Orc 11 is the only changed case,
advancing from exact through fixture 458 to exact through fixture 460. The
cycle-400 fleet remains 50 clean / 2 divergent / 0 failed under receipt
`97126937`. The long receipt is retained at
`.bne-artifacts/runs/f9b86fd5d79d694bf8283f911e39971ae14b1a5c442c27dd9ea0df3ac4501584`.
Its manifest has SHA-256
`b69ce01dd35f4c33d3e59d9c8470104f9ad62aad8840d4a4e143c3cd4724b3d3`
and binds dirty engine-input identity
`70f230fe7ebd8911a277744ac49d64537cc347a8c2096c6cf3816cb78f7260a7`
at base revision `6d42a1b` to authenticated, replayable source capsule
`c1295b562b776bd7ecab9e1312a113faaca32cab0e62ca273e9df7583e0b743c`.

Behavioral delta: a completed mobile-ranged Attack body that is still owned by
a behavior-two land-assault Patrol Move body hands an out-of-range replacement
quarry directly to Move. It does not pay a second Attack constructor delay.
Retail Orc 11 archer slot 1559 / Java 41 is the positive witness: after its
fixture-458 completed body, the fixture-459 scan replaces the sapper with the
moving ogre, publishes `NW,N,NW,NW,N,NW`, and consumes northwest on that same
visit. The same archer's initial Patrol-to-Attack handoff at fixtures 359--362
and ordinary non-assault ranged retargets remain constructor-paying controls.
The implementation contains no mission, map, faction, coordinate, fixture,
exact-cycle, route-length, or unit-ID branch.

Efficacy receipt
`.bne-test-efficacy/runs/7a61b87a3e339dbee4db3a40ffcdc572938175434cdc32dc9cd5594a66137db3`
proves the focused assertion executes and fails on `6d42a1b`, then executes
and passes on the candidate. All 44 selected land-patrol, ranged-retarget,
behavior-one, worker-refusal, and expansion Orc 11 controls pass with zero
skips. Both fixed 52-case gates pass. An additional unfiltered reactor run was
not used as an acceptance gate: it encountered the repository's existing red
research contracts in AI build/shove/harvest, command-plan, and adjacent open
families and was stopped after those failures; it is not claimed green.

Expansion Human 12 fixture 333 remains the paused shared-boundary route
frontier. The earliest unpaused fleet finding is now retail Orc 11 fixture
461: native critter slot 1532 remains Still while Java 68 enters Move. The
baseline and accepted candidate have identical asynchronous draw sequences
across fixtures 456--464, so this is a newly exposed pre-existing idle-choice
disagreement rather than an RNG regression from the ranged-tail fix. Continue
from the retained c461 field packet and causal ledgers before editing behavior.

## Prior release checkpoint — 2026-09-01 (person-offer chase and paid residual wakes)

Accepted cycle-1,800 receipt `92a5b8aa` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 13 clean / 39 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 50,616, an increase of 63. Expansion Human 10 advances from
fixture 448 to 511. The cycle-400 fleet remains 50 clean / 2 divergent / 0
failed under receipt `19b0b09d`. The long receipt is retained at
`.bne-artifacts/runs/92a5b8aaa7340fa5e8918894e909120412673aebf3c13b9685a201cf529c1748`.
Its manifest has SHA-256
`fda977d78e27dcef104016f62efde55b7ff9e890bd0b8a809ace963848198e6b`
and binds dirty engine-input identity
`afe4664cc1fd06c0b0aa057500a3b025e5ff9623a3560e2d436399c669ca5768`
at base revision `0b8b201` to authenticated, replayable source capsule
`55dc4d59757bee635fafd8cfe3bc1d23b3b1f6f882491e87e4b8d2268b29929a`.

Behavioral delta: when a standing person accepts its own direct-splash
HitUnit offer, Attack promotion clears the stale collision generation and an
uncontested first chase may retain the complete open skirt ray to a stationary
mobile quarry. A consumed chase residual blocked by a cooperative person owns
one complete refusal band; after its last retained step settles, the paid
collision owner can redraw and consume the new route head on the same Move
OP0. Likewise, a laden land returner whose consumed residual is blocked after
collision generation eight parks the route, pays the band, and redraws at its
wake. Moving quarries, contested offers, buildings, unladen movement, and
lower collision generations retain their established behavior. The
implementation contains no mission, map, faction, coordinate, fixture,
exact-cycle, route-length, or unit-ID branch.

Proof delta: expansion Human 10 center knight slot 1493 accepts its own
fixture-431 splash offer, clears collision on fixture 435, publishes
`SW,SW,SW,W,W`, consumes southwest on fixture 438, and consumes the second
southwest on fixture 450. Close knight slot 1480 clears its prior collision on
the fixture-431 direct offer, settles southwest with northwest occupied on
fixture 447, retains northwest through Move timers 15--1, consumes it on
fixture 462, and redraws `W,SW,W,SW` while consuming west on fixture 474.
Independently, laden gold peon slot 1438 parks its consumed depot-return
residual at collision generation nine on fixture 452, pays through fixture
466, then redraws `N,NW,N` and consumes north on fixture 467.

Efficacy receipts
`.bne-test-efficacy/xhuman10-center-splash/runs/881bf9837d5a01ed04ee356cd61a731df3c5f59e1f3e42de375d8a955db49a87`,
`.bne-test-efficacy/xhuman10-splash-blocked-tail/runs/ba3f2cb88df5de6574a1e981f214191a6d070f3167ed6cc3c4213bfc3214c3fd`,
and
`.bne-test-efficacy/xhuman10-long-gold-wake/runs/9b75e7163992bddfd8aa195d84fe83f9ebd84ee20f345d82fc76392397dc1175`
prove each focused assertion executes and fails on `0b8b201`, then executes
and passes on the candidate. All 63 selected expansion Human 10 and Human 13
real-data checks, the idle-targeting gate, both fixed 52-case gates, and the
ordinary executable next-level gate pass. That last gate retains 209 passing
Python checks with four skips, 99 passing engine/desktop checks, and 223
dual-adapter scenarios; its certification scorecard remains explicitly open
at 6/240 exact and 11/240 comparable. The resource-approach family remains at
its baseline-identical 36 checks / 7 failures under efficacy receipt
`.bne-test-efficacy/resource-approach-preexisting/runs/b8c979e8fa0561df89a34dc2f2ccdf72c6c4d8bb4b92c92bcdb6384addad41e9`, and the
movement gate's naval-patrol family remains at its baseline-identical 3 checks
/ 1 failure under
`.bne-test-efficacy/naval-patrol-gate-preexisting/runs/2edbee00b198646d1b39dd1672319865d13150671b2459806c5bfc66f01439a4`;
neither is claimed green. The long receipt's source capsule authenticates with
zero sealed untracked inputs. Remote AI discovery still stops at strict SSH
verification of the changed `i9beef` host key, which was not modified.

The shared frontier remains fixture 312: expansion Orc 8's submarine route
publication family is paused pending a new discriminator. Expansion Human
12's fixture-333 wood route is independently paused on global route-buffer
state. The earliest unpaused finding is now Orc 11 at fixture 459, followed by
Human 8 at 473, Orc 8 at 475, expansion Human 7 at 477, Human 7 at 493, Human
13 at 494, expansion Orc 11 at 506, expansion Human 11 at 509, and expansion
Human 2 and expansion Human 10 at 512.

## Prior release checkpoint — 2026-09-01 (depot-ready Still heads own no idle draw)

Accepted cycle-1,800 receipt `c9b852f7` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet moves
from 10 clean / 42 divergent / 0 failed to 13 clean / 39 divergent / 0 failed,
while the 52 per-case exact prefixes sum to 50,553, an increase of 9,030.
Human 4, Orc 3, and expansion Orc 2 become clean through all 1,800 fixtures;
seventeen more cases advance. Expansion Human 7 moves from fixture 447 to
477. The complete movement in exact prefixes is Human 4 `545->1800`, Human 6
`636->1009`, Human 11 `670->1296`, Human 14 `541->547`, Orc 3 `1271->1800`,
Orc 4 `584->1322`, Orc 5 `453->1278`, Orc 7 `823->1229`, Orc 9 `463->1247`,
Orc 10 `485->605`, Orc 12 `468->530`, Orc 14 `469->638`, expansion Human 3
`462->536`, expansion Human 4 `473->818`, expansion Human 7 `446->476`,
expansion Orc 2 `841->1800`, expansion Orc 3 `638->1291`, expansion Orc 5
`448->1304`, expansion Orc 7 `701->740`, and expansion Orc 10 `469->650`.
The cycle-400 fleet remains 50 clean / 2 divergent / 0 failed under receipt
`4e100c12`. The long receipt is retained at
`.bne-artifacts/runs/c9b852f7f812ac3f969eb8b3869b6417417ddfe75697a14b2be6b436d95d80e2`.
Its manifest has SHA-256
`8eeae9a17d851308699dd6e3ee5851504547029d9bf4c2853667093214714b25`
and binds dirty engine-input identity
`d76395cceea9c9472e504101fce1465b266e0794f4a0b728bc625b8b6a1660a0`
at base revision `ba9fd55` to authenticated, replayable source capsule
`e823e450fa7f9ddf847ed3247338b9f1023cae9ec953da713ffe93f75bb82cec`.

Behavioral delta: the 25-count Still head exposed when action 26 returns a
worker from a depot remains owned by the depot-ready continuation. It retains
and counts the native Still sequence, but does not schedule generic
`COrder_Still` and therefore cannot spend a land-idle random draw. The rule
applies systemically to gatherers with queued Harvest, Return Goods, or Build
continuations; directly issued ordinary Still queues retain their existing
behavior. The implementation contains no mission, map, faction, coordinate,
fixture, unit-ID, route-length, or exact-cycle branch.

Proof delta: expansion Human 7 peasant slot 1543 surfaces empty at `(12,35)`
on fixture 442 with raw action 2, next action 23, sequence 2595, and timer 25.
It owns no native `0040AD58` visit on fixture 443. Java formerly spent that
draw and stole the choice that native critter slot 1581 uses to wander
south-west toward `(85,20)` on fixture 447. Expansion Orc 8 peasant slot 1531
independently surfaces at `(55,21)` on fixture 604, counts sequence 2595 from
timer 25 through one on fixtures 604--628 without an idle draw, promotes
action 23 at sequence 2657/timer three on fixture 629, and first moves on
fixture 632. This second witness also rejects a land-only or mission-specific
exception despite changing the later already-divergent XOrc 8 world.

Efficacy receipt
`.bne-test-efficacy/xhuman7-c447-depot-ready-hold-final/runs/b7e41518f72ae27b3652c22a21ace79c397c088f9c75b8e39ce30c5e0bb27648`
proves the focused assertion executes and fails on `ba9fd55`, then executes
and passes on the candidate. Both focused real-data classes, the idle-targeting
gate, both fixed 52-case gates, and the ordinary executable next-level gate
pass. The long receipt's source capsule authenticates with zero sealed
untracked inputs. The broader AI gate still reports the baseline-identical
`AiCompetenceTest` error `not a single-tile step: 0,2`; baseline audit
`.bne-test-efficacy/baseline-audit-ai-competence/runs/0574195428993a9575f11b39d2e0f29138147c4b3b194fc1688743ff3e0cc35f`
confirms it is not introduced here. The XOrc 8 selector-one real-data check now
tests its durable person-owned naval-objective contract instead of freezing a
late exact coordinate after that mission has already diverged. Remote AI
discovery still stops at strict SSH verification of the changed `i9beef` host
key, which was not modified.

The shared frontier remains fixture 312: expansion Orc 8's submarine route
publication family is paused pending a new discriminator. Expansion Human
12's fixture-333 wood route is independently paused on global route-buffer
state. The earliest unpaused finding is now expansion Human 10 at fixture 449,
followed by Orc 11 at 459, Human 8 at 473, Orc 8 at 475, expansion Human 7 at
477, Human 7 at 493, and Human 13 at 494.

## Prior release checkpoint — 2026-09-01 (fixed-cannon pool and direct-splash HitUnit cadence)

Accepted cycle-1,800 receipt `f17c1f5a` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 41,523, an increase of four. Expansion Human 10 advances from
fixture 445 to 449. The cycle-400 fleet remains 50 clean / 2 divergent / 0
failed under receipt `92806ab0`. The long receipt is retained at
`.bne-artifacts/runs/f17c1f5a30f26817a48864fc0f0475d32fb5ddcaf9f00ae775f982c1302ab3e2`.
Its manifest has SHA-256
`e11f4364bb554490f9deee4cf8cdcd8cb0ecee074bb7cf60f557855093eb596a`
and binds dirty engine-input identity
`4fb7228fb58c21247922efb5f130f28a0dc7c9cb80cf5df85a6734616ff10126`
at base revision `0badfee` to authenticated, replayable source capsule
`70c4962a1443d1c2ef45daf4484437327fb2746e9930b3a5719d280707a6e04a`.

Behavioral delta: the fixed type-24 cannon constructor creates only its real
shell; BNE's source-less type-25 companion belongs to mobile cannon fire. A
surviving person at the center of a splash crosses ordinary HitUnit, including
its local source offer and close melee-brother recruitment, before the outer
splash walk. Outer victims receive their own local offers but do not recruit a
second ring. A close brother carrying both the center victim's pending help
and its own direct-splash offer promotes Attack without paying a Still
idle-random draw; the center victim carries only its local offer and retains
the ordinary draw. The implementation contains no mission, map, coordinate,
fixture, exact-cycle, faction, route-length, or unit-ID branch.

Proof delta: the authenticated expansion Human 10 projectile pool opens with
fixed cannon shell slot 3, arrow 4, catapult rock 5, and arrow 6. Expansion Orc
11 independently proves mobile destroyer shells retain type-25 companions in
slot pairs 9/10 and 11/12. On expansion Human 10 fixture 431, the catapult rock
directly hits center knight slot 1493 and queues close knights 1480 and 1485;
the later outer hits add each brother's local source offer. Knights 1480 and
1485 promote on fixtures 432 and 433 without idle draws, while center knight
1493 pays its ordinary draw on fixture 435. The aligned stream gives the guard
tower native damage 59 at fixture 438, 57 at 439 and 444, and 54 at 445. The
case is exact through fixture 448; its fixture-449 knight-1480 position finding
is independent.

Efficacy receipt
`.bne-test-efficacy/xhuman10-c445-primary-splash-hit-help/runs/d9f1c32865e217088175de8aa5f6777a9a40da2676bc7b7c8e8b49b45d30cacf`
proves the focused assertion executes and fails on `0badfee`, then executes
and passes on the candidate. The fixed projectile-pool unit controls, expansion
Orc 11 mobile-cannon control, XHuman 10 splash/damage tests, 64-check projectile
referee, and both fixed 52-case gates pass. The long receipt's source capsule
authenticates with zero sealed untracked inputs. The ordinary executable
next-level gate also exits zero across its Python contracts, engine/desktop
integration coverage, and 223 dual-adapter command scenarios. Remote AI
discovery still stops at strict SSH verification of the changed `i9beef` host
key, which was not modified.

The shared frontier remains fixture 312: expansion Orc 8's submarine route
publication family is paused pending a new discriminator. Expansion Human
12's fixture-333 wood route is independently paused on global route-buffer
state. The earliest unpaused finding is now expansion Human 7 at fixture 447,
followed by expansion Human 10 and expansion Orc 5 at 449, Orc 5 at 454, Orc
11 at 459, expansion Human 3 at 463, Orc 9 at 464, Orc 12 at 469, Orc 14 and
expansion Orc 10 at 470, Human 8 at 473, expansion Human 4 at 474, and Orc 8
at 475.

## Prior release checkpoint — 2026-09-01 (depot-ready queued action constructors)

Accepted cycle-1,800 receipt `2d197a6e` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 41,519, an increase of 1,063. Expansion Human 5 advances from
fixture 486 to 530, expansion Human 6 from 525 to 674, expansion Human 8 from
446 to 536, expansion Human 9 from 515 to 579, expansion Human 11 from 484 to
509, expansion Orc 6 from 521 to 603, expansion Orc 7 from 542 to 702, and
expansion Orc 12 from 444 to 893. The cycle-400 fleet remains 50 clean / 2
divergent / 0 failed under receipt `27ac2fd8`. The long receipt is retained at
`.bne-artifacts/runs/2d197a6e11d6aab2ae8a9359524bedb75e39c421aa8fe04a4db17acdfc5cfd0b`.
Its manifest has SHA-256
`6ff50a00aacfe0660a57e5634877b6653e0357439ad220e32e99f7098f358411`
and binds dirty engine-input identity
`af4d450179a6b9c2f87146fd6a42ca116d15132658d91b7d1c50efacdf14ca4d`
at base revision `cfb148d` to authenticated, replayable source capsule
`90450505966f641845ffe287ec48c7e0f99a3b34c62c46a60ca33a800c5fd81c`.

Behavioral delta: a depot-ready Still head makes its queued action current on
the promotion visit, but the promoted action retains ownership of a
three-visit constructor before it can request or spend a route. A gold worker
outside its mine approach exposes the action-23 attack sequence at timer
three, then counts two and one on its quiet visits. A queued Build retains its
Still sequence across the same three, two, one body. The already-proved tanker
continuation follows the same promotion timing. Directly issued work and gold
workers already standing on their resource approach retain their established
paths. The implementation contains no mission, map, coordinate, fixture,
exact-cycle, faction, route-length, or unit-ID branch.

Proof delta: expansion Orc 12 peon slot 1396 / Java unit 204 exits its castle
at `(30,75)` on fixture 418, pays the complete 25-count Still head, and keeps
gold Harvest queued. Native promotes action 23 on fixture 443 at sequence
`2657/3`, holds the tile at `2657/2,1` through fixtures 444 and 445, and first
steps north-east to `(31,74)` on fixture 446. Java formerly moved on fixture
444. Expansion Human 8 peasant slot 1571 / Java unit 29 independently seals
the Build arm: after its hall exit, native promotes queued Build at `(20,8)`
on fixture 445 with Still timer three, holds for timers two and one, and first
steps north on fixture 448. Java formerly moved on fixture 446.

Efficacy receipts
`.bne-test-efficacy/xorc12-c444-queued-gold-constructor/runs/52fbab6977cbe1e29c0b25f6daaa52bf1e189ff7ae6d7ab9bbcb8606b4ddf4bd`
and
`.bne-test-efficacy/xhuman8-c446-queued-build-constructor/runs/8ba2ba004eba2c27cf77f4e3f9f9e32a0e1512c994da3ab4278fb7e0c3a0522f`
prove both focused assertions execute and fail on `cfb148d`, then execute and
pass on the candidate. The selected mine-exit, construction, oil-exit, tanker,
and resource-approach controls pass, as do both fixed 52-case gates. The long
receipt's source capsule authenticates with zero sealed untracked inputs.
The ordinary executable next-level gate exits zero after 209 Python checks
(four skipped), 99 engine/desktop checks, and 223 dual-adapter command
scenarios. Its 11 comparable scenarios remain 6 exact / 5 divergent with no
regression or infrastructure failure. Remote AI discovery still stops at
strict SSH verification of the changed `i9beef` host key, which was not
modified.

The shared frontier remains fixture 312: expansion Orc 8's submarine route
publication family is paused pending a new discriminator. Expansion Human
12's fixture-333 wood route is independently paused on global route-buffer
state. The earliest unpaused finding is now expansion Human 10 at fixture 445,
followed by expansion Human 7 at 447, expansion Orc 5 at 449, Orc 5 at 454,
Orc 11 at 459, expansion Human 3 at 463, Orc 9 at 464, Orc 12 at 469, Orc 14
and expansion Orc 10 at 470, Human 8 at 473, expansion Human 4 at 474, and Orc
8 at 475.

## Prior release checkpoint — 2026-09-01 (final-approach tanker queue handoff)

Accepted cycle-1,800 receipt `ec9ee0b3` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 40,456, an increase of 33. Orc 8 advances from fixture 442 to
475. The cycle-400 fleet remains 50 clean / 2 divergent / 0 failed under
receipt `81915320`. The long receipt is retained at
`.bne-artifacts/runs/ec9ee0b33d5114226a7f265f97d288345f4a95c44b9525c5138fa19d9e95dcf0`.
Its manifest has SHA-256
`32bb66023eaea21cf31f85c9759e596c4720bae3b9e21056b31658f5c03e2a63`
and binds dirty engine-input identity
`d0278b583d764df6a014f0ff395f2ee19765deb66d96155d4843547a5d946e0d`
at base revision `7a6ebb8` to authenticated, replayable source capsule
`b80d402e375711328bf64230960ce052a87f66c4806770e7afabaac788467d7e`.

Behavioral delta: when a loaded tanker has spent a cached return-route byte
into a stationary loaded tanker at the same depot, and that queue head is on
the timer-one visit of its projected native action 25, the follower parks the
occupied tail at route index twenty with collision generation one. It does
not buy the generic fifteen-count naval-refusal band. Native visits the
higher pool-slot queue head first on the following pass, removes it into the
depot, and lets the follower redraw around the newly vacant hull immediately.
Moving returners, unstaged tankers, pressured queue heads, and swept diagonal
side-hull collisions retain their established full refusal bands. The
implementation contains no mission, map, coordinate, fixture, exact-cycle,
faction, route-length, or unit-ID branch.

Proof delta: Orc 8 follower tanker slot 1479 / Java unit 121 carries 100 oil
to the same refinery as leader slot 1482 / Java unit 118. Through fixture 440
the follower's cached north tail has settled it at pixel `(2688,2882)`, while
the leader is stationary in final approach. On fixture 441 the follower's
north byte reaches pixel `(2688,2880)`, finds the leader's occupied tail,
parks the route, changes raw collision `00` to `10`, and remains at Move
timer one. On fixture 442 the leader changes from action 25 to removed action
26 before the follower is visited; the follower publishes `[NW,W]`, commits
north-west to `(82,88)`, and retains west. Java formerly classified the
occupied byte as a generic cached-naval refusal and did not commit its stale
north step until fixture 456. The corrected case is exact through fixture
474; its fixture-475 peasant position finding is independent.

Efficacy receipt
`.bne-test-efficacy/orc8-c442-final-approach-queue/runs/4ba1adac9bd0f125a5a45844141bd5870f7c6d289b453631b73040dbffebba82`
proves the focused assertion executes and fails on `7a6ebb8`, then executes
and passes on the candidate. The focused oil lifecycle/exit/pathfinder/save
gate and the two fixed 52-case gates pass; the long receipt's source capsule
authenticates with zero sealed untracked inputs. The ordinary executable
next-level gate exits zero after 209 Python checks (four skipped), 99
engine/desktop checks, and 223 dual-adapter command scenarios. Its 11
comparable scenarios remain 6 exact / 5 divergent with no regression or
infrastructure failure. `--require-certified` remains incomplete on the
documented producer lanes, and remote AI discovery still stops at strict SSH
verification of the changed `i9beef` host key, which was not modified.

The shared frontier remains fixture 312: expansion Orc 8's submarine route
publication family is paused pending a new discriminator. Expansion Human
12's fixture-333 wood route is independently paused on global route-buffer
state. The earliest unpaused finding is now expansion Orc 12 at fixture 444,
followed by expansion Human 10 at 445, expansion Human 8 at 446, expansion
Human 7 at 447, expansion Orc 5 at 449, Orc 5 at 454, Orc 11 at 459,
expansion Human 3 at 463, Orc 9 at 464, Orc 12 at 469, Orc 14 and expansion
Orc 10 at 470, Human 8 at 473, expansion Human 4 at 474, and Orc 8 at 475.

## Prior release checkpoint — 2026-09-01 (armed-flyer Patrol Attack-body ownership)

Accepted cycle-1,800 receipt `f75271c1` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 40,423, an increase of 68. Expansion Orc 11 advances from
fixture 438 to 506. The cycle-400 fleet remains 50 clean / 2 divergent / 0
failed under receipt `3d6edc68`. The long receipt is retained at
`.bne-artifacts/runs/f75271c1456090202be5c08d8e636efdfef300897d90148aa72f47b3a56dcff7`.
Its manifest has SHA-256
`8ce646f7c55a899351df78c7cbf0e12601e6673468c11fcdf4a6387b77a4f384`
and binds dirty engine-input identity
`6e9e9fa89e1c4e638ecc7c20d3b4432c7d78e91993dd7db02d7bfc829e96f92a`
at base revision `8279185` to authenticated, replayable source capsule
`fbfdc9496535164b72bc0d160846f3361c653df27ea2e0c1276b87ff94ee171a`.

Behavioral delta: an out-of-range direct Attack selected at an armed flyer's
Patrol opcode-zero marker retains the Patrol route but does not chase as soon
as its ordinary three-call Attack constructor expires. BNE gives the complete
compact Attack body callback ownership, including the same-visit entrance
behind the opening OP0, and releases the retained route only at the body-tail
OP0. If the quarry enters range during the body, the ordinary sequence owner
resumes so OP10 and projectile state remain authoritative. The transient
ownership is save/resume state and clears at every attack/order replacement.
The implementation contains no mission, map, coordinate, fixture, exact-cycle,
faction, route-length, or unit-ID branch.

Proof delta: expansion Orc 11 gryphon slot 1589 / Java unit 11 promotes its
queued direct Attack on fixture 437 at `(16,34)`, Attack `2313/3`, with three
southwest route bytes retained. Native holds that tile and route through
Attack `2313/2,1`, enters body `2317/6` on fixture 440, pays the four six-visit
waits through `2329/1` on fixture 463, and only then commits southwest to
`(14,36)` on fixture 464 at Move `2259/1`. Java formerly began chase on
fixture 438. The corrected case is exact through fixture 505; its fixture-506
battleship Patrol/order-position finding is a different mechanism.

Efficacy receipt
`.bne-test-efficacy/xorc11-c438-armed-flyer-attack-body/runs/2e8f070bc305a52ca6067a47dc7d432b8b184df6b98b2672c193a2f57d763a4c`
proves the focused assertion executes and fails on `8279185`, then executes
and passes on the candidate. All 92 selected armed-flyer, naval/land Patrol,
expansion Orc 11 combat, and save/resume checks pass. Both fixed 52-case gates
pass, and the long receipt's source capsule authenticates with zero sealed
untracked inputs. The ordinary executable next-level gate exits zero after 209
Python checks (four skipped), 99 engine/desktop checks, and 223 dual-adapter
command scenarios. Its 11 comparable scenarios remain 6 exact / 5 divergent
with no regression or infrastructure failure. `--require-certified` remains
incomplete on the documented producer lanes, and remote AI discovery still
stops at strict SSH verification of the changed `i9beef` host key, which was
not modified.

The shared frontier remains fixture 312: expansion Orc 8's submarine route
publication family is paused pending a new discriminator. Expansion Human
12's fixture-333 wood route is independently paused on global route-buffer
state. The earliest unpaused finding is now Orc 8 at fixture 442, followed by
expansion Orc 12 at 444, expansion Human 10 at 445, expansion Human 8 at 446,
expansion Human 7 at 447, expansion Orc 5 at 449, Orc 5 at 454, Orc 11 at 459,
expansion Human 3 at 463, Orc 9 at 464, Orc 12 at 469, and Orc 14 and
expansion Orc 10 at 470.

## Prior release checkpoint — 2026-09-01 (hidden tanker-ready depot exit ownership)

Accepted cycle-1,800 receipt `613708b4` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 40,355, an increase of 51. Expansion Human 5 advances from
fixture 435 to 486. The cycle-400 fleet remains 50 clean / 2 divergent / 0
failed under receipt `8262cd77`. The long receipt is retained at
`.bne-artifacts/runs/613708b4fff57c8c814810c89d54dc5d511f60d8275176651a6a2460fce46601`.
Its manifest has SHA-256
`614899f4254dbde38f9f1bb929560f9f31f91c395b96d850682e57629e69e83e`
and binds dirty engine-input identity
`494e4f6e852c0e682706a33196f0307f6d6a5af795002af36c6605477a4de062`
at base revision `89f5111` to authenticated, replayable source capsule
`a92cc2ff6c32e01cfca77707db0a01b94203e9d8b15c078f88355b88f9d76755`.

Behavioral delta: BNE's depot-ready action-26 callback may select an oil
platform while a live empty tanker is still contained in its shipyard. That
selected platform owns both the face-first absolute-even depot exit and the
queued action 23 behind the exit's 25-cycle Still head. The existing callback
continues to use the proved refinery-weighted platform choice; ordinary land
worker and construction-ready dispatch retain their established behavior.
The implementation contains no mission, map, coordinate, fixture, exact-cycle,
faction, route-length, or unit-ID branch.

Proof delta: expansion Human 5 tanker slot 1557 / Java unit 43 remains hidden
in shipyard slot 1559 / Java unit 41 through fixture 434 after finishing its
return with no remembered platform. Native invokes the ready callback before
dropout and stores platform slot 1558 / Java unit 42. On fixture 435 it
surfaces Still at the platform-owned east face `(92,60)`, keeps action 23
queued behind delay 25, promotes the action on fixture 460, and commits east
to `(94,60)` on fixture 463. Java formerly used the generic south exit at
`(89,62)` and selected the platform only after surfacing. The corrected case
is exact through fixture 485; its fixture-486 peon position finding is
independent.

Efficacy receipt
`.bne-test-efficacy/xhuman5-c435-hidden-ready-platform/runs/112c19312940ce992a3dcfb2c458cd5b85fa9da7d7f2acd8e45a508e69e7d236`
proves the focused assertion executes and fails on `89f5111`, then executes
and passes on the candidate. The 53 selected oil-exit, resource-approach,
lifecycle, save, sprite, and pathfinder checks record 50 passes and three
asset-dependent skips. Both fixed 52-case gates pass. The long receipt's
source capsule authenticates with zero sealed untracked inputs. The ordinary
executable next-level gate exits zero after 209 Python checks (four skipped),
98 engine/desktop checks, and 223 dual-adapter command scenarios. Its 11
comparable scenarios remain 6 exact / 5 divergent with no regression or
infrastructure failure. `--require-certified` remains incomplete on the
documented producer lanes, and remote AI discovery still stops at strict SSH
verification of the changed `i9beef` host key, which was not modified.

The shared frontier remains fixture 312: expansion Orc 8's submarine route
publication family is paused pending a new discriminator. Expansion Human
12's fixture-333 wood route is independently paused on global route-buffer
state. The earliest unpaused finding is now expansion Orc 11 at fixture 438,
followed by Orc 8 at 442, expansion Orc 12 at 444, expansion Human 10 at 445,
expansion Human 8 at 446, expansion Human 7 at 447, expansion Orc 5 at 449,
Orc 5 at 454, Orc 11 at 459, expansion Human 3 at 463, Orc 9 at 464, Orc 12
at 469, and Orc 14 and expansion Orc 10 at 470.

## Prior release checkpoint — 2026-09-01 (person HitUnit first-chase goal-axis ownership)

Accepted cycle-1,800 receipt `005859c9` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 40,304, an increase of 13. Expansion Human 10 advances from
fixture 432 to 445. The cycle-400 fleet remains 50 clean / 2 divergent / 0
failed under receipt `fe576026`. The long receipt is retained at
`.bne-artifacts/runs/005859c9a6d2a4465ebcc556b2defd9c2725b520bed7509e996777a9e36ac000`.
Its manifest has SHA-256
`8ddc8c515e9ed00dabcb155317a0c6aac956e19a3efecbcd673594767d94ef26`
and binds dirty engine-input identity
`b97af84c50fab37a3dad973ce445f7c05ad5a0c9425bfe44a29b68be0e2ab860`
at base revision `2ed0c3e` to authenticated, replayable source capsule
`3bcfe93a281fda1f61e85db1441ad65ee445169671a54868b67fe25b2307d463`.

Behavioral delta: positive nonlethal splash on a person land unit installs
HitUnit's local source offer for that struck unit without recruiting nearby
melee brothers. When the person's Still scan promotes that HitUnit offer,
its first chase retains the same provenance already proved for person spatial
help across the NewActionAttack boundary. The route writer prefers an
equal-cost diagonal which reduces the target's secondary axis over either a
cold cardinal head or an inherited diagonal face which points away from that
axis. Naval offers retain their independently captured doubled-compass
handoff. The implementation contains no mission, map, coordinate, fixture,
exact-cycle, faction, route-length, or unit-ID branch.

Proof delta: expansion Human 10 catapult slot 1487 / Java unit 113 splashes
three person knights on fixture 431. Slots 1480/1485/1493 (Java
120/115/107) fall from 52/85/51 HP to 44/74/10 and each receives the
catapult's native `+0x54` offer. They promote Attack on their independent
Still markers at fixtures 432, 433, and 435. Slot 1485's native first route is
`SW,SW,W,W,W`; Java formerly retained the cold `W,SW,W,W,W` head and stayed
on row 88 at fixture 436. Slot 1493's native route is `SW,SW,SW,W,W`; Java
formerly inherited its stale north-west combat face and moved to row 86 at
fixture 438. The corrected knights move south-west to `(78,89)` and
`(78,88)` respectively. The case is exact through fixture 444; its
fixture-445 guard-tower HP finding is independent.

Efficacy receipt
`.bne-test-efficacy/xhuman10-c436-c438-direct-hit-first-chase-final/runs/7c3dbe046591f2fff3a6bff388e018a761240b23653bfe0c428bc757d46dad54`
proves the final focused assertion executes and fails on `2ed0c3e`, then
executes and passes on the candidate. The preceding local-offer efficacy
receipt is retained under
`.bne-test-efficacy/xhuman10-c432-person-nonlethal-splash-offer/runs/5be69f02245662f763c7eb3de85eae99cf369457ffbeb3dcad7dc69341927748`.
The 91 selected XHuman 10, splash, person-help, XOrc 11, and combat controls
record 89 passes and one asset-dependent skip; the sole failure is the
unrelated ranged-retarget timing assertion which fails identically on
`2ed0c3e`. Both fixed 52-case gates pass. The long receipt's source capsule
authenticates with zero sealed untracked inputs. The previously accepted
executable next-level gate remains the current milestone proof;
`--require-certified` remains incomplete on the documented producer lanes,
and remote AI discovery still stops at strict SSH verification of the changed
`i9beef` host key, which was not modified.

The shared frontier remains fixture 312: expansion Orc 8's submarine route
publication family is paused pending a new discriminator. Expansion Human
12's fixture-333 wood route is independently paused on global route-buffer
state. The earliest unpaused finding is now expansion Human 5 at fixture 435,
followed by expansion Orc 11 at 438, Orc 8 at 442, expansion Orc 12 at 444,
expansion Human 10 at 445, expansion Human 8 at 446, expansion Human 7 at 447,
expansion Orc 5 at 449, Orc 5 at 454, and Orc 11 at 459.

## Prior release checkpoint — 2026-09-01 (collision-owned laden-return paid band)

Accepted cycle-1,800 receipt `4ecfe28d` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 40,291, an increase of 2. Expansion Human 10 advances from
fixture 430 to 432. The cycle-400 fleet remains 50 clean / 2 divergent / 0
failed under receipt `944c706b`. The long receipt is retained at
`.bne-artifacts/runs/4ecfe28d4c13b88041707a1d0c487372afc8315d04c68db3998b1eef000f8c91`.
Its manifest has SHA-256
`bfe73142bcadca452ba9a48d5ec7983f68b0ceb4f9a07f5ff7744c4e9906122e`
and binds dirty engine-input identity
`aef23d0218e53041cf4ede5efb4a245dcf6b624088dfcd26031ebf03261b01b9`
at base revision `b8f060b` to authenticated, replayable source capsule
`47fc2c78490d38bacc38e5fdcd2dbc513b030100d8effbf411223d42790e27d9`.

Behavioral delta: a laden land return farther than the depot skirt takes its
paid refusal band from native's packed collision generation as well as Java's
separate refusal projection. Generations eight through fourteen own Move
15..1; generation fifteen remains the wrap visit. This lets a fresh
multi-heading return route pay on collision eight when the refusal projection
trails by one, while aligned direct and queued-return ladders retain their
established behavior. The implementation contains no mission, map,
coordinate, fixture, exact-cycle, faction, route-length, or unit-ID branch.

Proof delta: expansion Human 10 peon slot 1438 / Java unit 162 is carrying
gold back to its depot with the cached route `NE,NE,NW,W`. Native and Java are
both at collision seven / Move timer one on fixture 414. On fixture 415 native
advances the packed generation to eight while Java's refusal projection is
still seven, and immediately buys Move 15..1. The corrected Java path does the
same. Both sides retain the identical route until its timer-one wake and
consume north-east onto `(15,115)` on fixture 430. The new fixture-432 knight
order finding is independent.

Efficacy receipt
`.bne-test-efficacy/xhuman10-c430-collision-owned-laden-return/runs/0e9c076cb28e2b48925ccdd13c3cb7057a900189dc4ad84b249614358a71c739`
proves the focused assertion executes and fails on `b8f060b`, then executes
and passes on the candidate. All 42 selected expansion Human 10 and laden-
return controls pass, as do both fixed 52-case gates. The long receipt's source
capsule authenticates with zero sealed untracked inputs. The previously
accepted executable next-level gate remains the current milestone proof;
`--require-certified` remains incomplete on the documented producer lanes,
and remote AI discovery still stops at strict SSH verification of the changed
`i9beef` host key, which was not modified.

The shared frontier remains fixture 312: expansion Orc 8's submarine route
publication family is paused pending a new discriminator. Expansion Human
12's fixture-333 wood route is independently paused on global route-buffer
state. The earliest unpaused finding is now expansion Human 10 at fixture 432,
followed by expansion Human 5 at 435, expansion Orc 11 at 438, Orc 8 at 442,
expansion Orc 12 at 444, expansion Human 8 at 446, expansion Human 7 at 447,
expansion Orc 5 at 449, Orc 5 at 454, and Orc 11 at 459.

## Prior release checkpoint — 2026-09-01 (cold paid-wrap quarry handoff)

Accepted cycle-1,800 receipt `feea635e` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 40,289, an increase of 46. Human 8 advances from fixture 427
to 473. The cycle-400 fleet remains 50 clean / 2 divergent / 0 failed under
receipt `2bc650e8`. The long receipt is retained at
`.bne-artifacts/runs/feea635e293de73d0151dcf8d49e6e36058d1dade36ae610e6cc5df47105d066`.
Its manifest has SHA-256
`088cf6e01e1fabf35d264683d72a9cf375f11ca3c0328ad0ed6580d26c3a5b78`
and binds dirty engine-input identity
`0e23719d659ddbcb7f7fbff99bfe23e2776075c512032de7a9e8bf2c7cf8c410`
at base revision `02242ce` to authenticated, replayable source capsule
`24473d0e8d54d416194bbfea0059a258f366310f6edc0b2a051e99421997680d`.

Behavioral delta: a behavior-zero mobile replacement owns the first paid
Attack-wrap constructor across the route park/refill which follows its refused
cached byte; behavior-one formation guards remain on their distinct refill
transaction. A cold paid-wrap wake which finds a fresh adjacent mobile quarry
parks the stale route and constructs a new Attack body instead of spending the
old wrap token on an immediate hit. If that held quarry moves away and the
repeated boxed constructor's target then dies as a strictly closer free square
opens, the replacement inherits the paid stage-six Move probe without another
asynchronous draw. The implementation contains no mission, map, coordinate,
fixture, exact-cycle, faction, route-length, or unit-ID branch.

Proof delta: Human 8 attack-peasant slot 1520 / Java unit 80 pays Attack
3,2,1 on fixtures 421..423, probes east on 425, and enters the Attack body on
441 before reaching OP10 on 451. Attack-peasant slot 1513 / Java unit 87 parks
its old quarry route and selects adjacent returner slot 1519 / Java unit 81 on
fixture 415, constructs a fresh Attack body, and does not damage the departing
returner on fixture 427. It returns to boxed peasant slot 1536 / Java unit 64,
repeats the constructor until that quarry dies, then inherits the open
south-east progress square on fixture 468. Native and Java are both at
`(79,63)`, while critter slot 1544 / Java unit 56 retains the fixture-470 Move
which proves the handoff did not steal its asynchronous ordinal. Human 8 is
exact through fixture 472; its fixture-473 peasant-position finding is
independent.

Efficacy receipt
`.bne-test-efficacy/c468-human8-cold-paid-wrap-dying-release-final/runs/857cf2ff708895109a985cc168e5f52964c85504112438a59d3eebd77895b2f0`
proves the focused assertion executes and fails on `02242ce`, then executes
and passes on the candidate. All 113 selected moving-quarry, refusal, and
behavior-one controls pass, as do both fixed 52-case gates. The long receipt's
source capsule authenticates with zero sealed untracked inputs. The ordinary
executable next-level gate exits zero after 209 Python checks (four skipped),
98 engine/desktop checks, and 223 dual-adapter command scenarios. Its 11
comparable scenarios remain 6 exact / 5 divergent with no regression or
infrastructure failure. `--require-certified` remains incomplete on the
documented producer lanes, and remote AI discovery still stops at strict SSH
verification of the changed `i9beef` host key, which was not modified.

The shared frontier remains fixture 312: expansion Orc 8's submarine route
publication family is paused pending a new discriminator. Expansion Human
12's fixture-333 wood route is independently paused on global route-buffer
state. The earliest unpaused finding is now expansion Human 10 at fixture 430,
followed by expansion Human 5 at 435, expansion Orc 11 at 438, Orc 8 at 442,
expansion Orc 12 at 444, expansion Human 8 at 446, expansion Human 7 at 447,
expansion Orc 5 at 449, Orc 5 at 454, and Orc 11 at 459.

## Prior release checkpoint — 2026-09-01 (movement-layer flyer direct ray)

Accepted cycle-1,800 receipt `d9e971a7` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 40,243, an increase of 124. Human 12 advances from fixture 422
to 546. The cycle-400 fleet remains 50 clean / 2 divergent / 0 failed under
receipt `fb3e845e`. The long receipt is retained at
`.bne-artifacts/runs/d9e971a71936ad3ac20cdd473a8f3473e5423516afe159efc8782206d9732f2e`.
Its manifest has SHA-256
`969b368514db141113940fabefdfcbef7304d57ceb58bf05f83c24743cf9ff57`
and binds dirty engine-input identity
`4f51d439a7e99b3fdcf7f8cdf8e40aa2d8293625f4a343efee57fa6ac1d190a1`
at base revision `e2606c9` to authenticated, replayable source capsule
`c2e5ccaec520320c7eaa1a0ccdafc2fdebacb19851b936fcc5bbc47f5d61c0ff`.

Behavioral delta: the temporary soft-clear view used to choose a direct route
or shortcut is movement-layer specific. A doubled air Patrol direct ray keeps
moving air bodies as geometric blockers regardless of attack capability;
later wall tracing may still soften those bodies under its established rules.
Ground movers remain transparent to the air route view, just as air movers
remain transparent to ground routing. The implementation contains no mission,
map, coordinate, fixture, exact-cycle, faction, route-length, or unit-ID
branch.

Proof delta: Human 12 zeppelin slot 1503 / Java unit 97 routes toward `(16,0)`
while allied moving zeppelin slot 1570 / Java unit 30 occupies its direct ray
at `(24,6)`. Native publishes `[NW,NW,NW,W,W,W,W,NW]`: fixture 402 consumes
the first northwest step to `(36,12)`, and fixture 422 consumes the second to
`(34,10)`. Java formerly selected the open
`[NW,W,NW,NW,W,NW,W,NW,NW,W,NW]` ray and moved west at fixture 422. As a
cross-layer control, Human 12 unarmed zeppelin slot 1559 / Java unit 41 has a
west ray through moving ground peons at fixture 2 and still moves west while
remaining at y 14. The corrected case is exact through fixture 545; its
fixture-546 tanker population/identity finding is independent.

Efficacy receipt
`.bne-test-efficacy/c422-human12-layered-air-direct-ray/runs/83179f05dfb80f2437c8355fec882b8d78d82167b9bcfb551239677f1abe1d54`
proves the new assertion executes and fails on `e2606c9`, then executes and
passes on the candidate. All 77 focused pathfinder, flyer, movement, and sea
occupancy checks pass, as do all 30 `bne_java.py` adapter tests and both fixed
52-case gates. The long receipt's source capsule authenticates with zero
sealed untracked inputs. The ordinary executable next-level gate exits zero
after 209 Python checks (four skipped), 98 engine/desktop checks, and 223
dual-adapter command scenarios. Its 11 comparable scenarios remain 6 exact /
5 divergent with no regression or infrastructure failure.
`--require-certified` remains incomplete on the documented producer lanes,
and remote AI discovery still stops at strict SSH verification of the changed
`i9beef` host key, which was not modified.

The shared frontier remains fixture 312: expansion Orc 8's submarine route
publication family is paused pending a new discriminator. Expansion Human
12's fixture-333 wood route is independently paused on global route-buffer
state. The earliest unpaused finding is now Human 8 at fixture 427, followed
by expansion Human 10 at 430, expansion Human 5 at 435, expansion Orc 11 at
438, Orc 8 at 442, expansion Orc 12 at 444, expansion Human 8 at 446,
expansion Human 7 at 447, expansion Orc 5 at 449, Orc 5 at 454, and Orc 11 at
459.

## Prior release checkpoint — 2026-09-01 (standing-hit escape and recurring chase refusal)

Accepted cycle-1,800 receipt `c26baeac` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 40,119, an increase of 41. Orc 11 advances from fixture 418 to
459. The cycle-400 fleet remains 50 clean / 2 divergent / 0 failed under
receipt `5c444b55`. The long receipt is retained at
`.bne-artifacts/runs/c26baeac575fcf33fb8856e14324caf6cff82d3a7639708048c7460260d8f704`.
Its manifest has SHA-256
`321c9dc55847a4ff9fc7a2f478c4af3788f363657fa2686c2e172358d6dfcf3c`
and binds dirty engine-input identity
`e4509dc2f32c9f15866abb36943bd14f13f23f34346906f3c56d71ae9b1570e8`
at base revision `ef8b679` to authenticated, replayable source capsule
`d1ffddad294c380b749ff87f2d8be58c0e5d039c8d43511dfd2f5f26f4d04b0f`.

Behavioral delta: Still's post-idle type dispatch sends armed workers,
spellcasters, and demolition units through the same one-tile asynchronous-RNG
escape constructor already proved for resource-hit workers. Oil tankers remain
excluded by the native attack-capability gate. The person hit-help cache uses
the same nominal band rectangle and three-row north skirt already proved for
naval hit help. Separately, a behaviour-two recurring Patrol chase does not
spend the free cardinal component of a refused diagonal when that component
does not close Chebyshev distance to its quarry. Its ensuing hard park owns the
replacement route's first cooperative refusal even though collision history
survives. Behaviour-one formation chases keep their established soft-clear
rule. The implementation contains no mission, map, coordinate, fixture,
exact-cycle, faction, route-length, or unit-ID branch.

Proof delta: Orc 11 sapper slot 1573 / Java unit 27 is struck at fixture 417
while Still. Native fixture 418 constructs Move toward `(111,21)` with timer
three, fixture 421 commits northwest, and fixture 435 restores Still; the
untouched sapper control remains Still. The same hit banks action 12 for ogre
slot 1581 / Java unit 19 four rows north of the sapper, and that helper promotes
to Attack at fixture 422. Java formerly neither fled nor admitted the helper.
In the independent chase witness, knight slot 1558 / Java unit 42 refuses its
northwest route at fixture 407, retains Move timer one and collision one, pays
the full cooperative band at 408, remains at `(117,27)` through fixture 422,
and commits west at 423. Java formerly spent the sideways north component and
moved one cycle early. The corrected case is exact through fixture 458; its
fixture-459 archer-position finding is independent.

Efficacy receipt
`.bne-test-efficacy/c418-orc11-standing-hit-and-patrol-refusal/runs/18ffa956a0517f7f1cc5783a5a79e15a123c2c574be0d2b68b81e7e345caf26c`
proves both new assertions execute and fail on `ef8b679`, then all six class
tests execute and pass on the candidate. The 45-test focused Orc11, pure-move,
and expansion Human 12 residual-route gate passes, as do all 30 `bne_java.py`
adapter tests and both fixed 52-case gates. The long receipt's source capsule
authenticates with zero sealed untracked inputs. The ordinary executable
next-level gate exits zero after 209 Python checks (four skipped), 98
engine/desktop checks, and 223 dual-adapter command scenarios. Its 11
comparable scenarios remain 6 exact / 5 divergent with no regression or
infrastructure failure. `--require-certified` remains incomplete on the
documented producer lanes, and remote AI discovery still stops at strict SSH
verification of the changed `i9beef` host key, which was not modified.

The shared frontier remains fixture 312: expansion Orc 8's submarine route
publication family is paused pending a new discriminator. Expansion Human
12's fixture-333 wood route is independently paused on global route-buffer
state. The earliest unpaused finding is now Human 12 at fixture 422, followed
by Human 8 at 427, expansion Human 10 at 430, expansion Human 5 at 435,
expansion Orc 11 at 438, Orc 8 at 442, expansion Orc 12 at 444, expansion Human
8 at 446, expansion Human 7 at 447, expansion Orc 5 at 449, Orc 5 at 454, and
Orc 11 at 459.

## Prior release checkpoint — 2026-09-01 (single-owner naval guard help)

Accepted cycle-1,800 receipt `bcefea2b` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 40,078, an increase of 33. Expansion Human 7 advances from
fixture 414 to 447. The cycle-400 fleet remains 50 clean / 2 divergent / 0
failed under receipt `f743dab4`. The long receipt is retained at
`.bne-artifacts/runs/bcefea2b5e23c2c0252d27ff3ce1b246fc5fb7f67c6375c93ff3ef92e05ebf1a`.
Its manifest has SHA-256
`9e550627034bb9193e395006e5d604ba7e3bbf2efe5db9f2bf95568fdf12fb53`
and binds dirty engine-input identity
`9449c5cc832cb822317ea8a0c6affd5337596c118ac6a86d1bcb48c8432e9582`
at base revision `71fcdd9` to authenticated, replayable source capsule
`5bd9f3bf003b804584af0352fc6a05b9326c779d0dd7faad77d2bf48fdca17bc`.

Behavioral delta: the hidden-attacker naval-help pass selects the closest
eligible roaming hull before deciding whether the rendezvous is new. When that
winner already owns the struck guard through an active pointer, a pending
pointer, or the identical position order, the request is a no-op. Filtering
the assigned winner before distance ranking incorrectly enlists the
next-nearest ship. The implementation contains no mission, map, coordinate,
fixture, exact-cycle, faction, route-length, or unit-ID branch.

Proof delta: expansion Human 7 submarine slot 1511 / Java unit 89 already owns
the live rendezvous pointer to guarded destroyer slot 1420 / Java unit 180.
The guard enters Die at fixture 355, where another hit by the hidden attacker
reaches `AiHelpMe`. Native reselects slot 1511 and performs no new handoff.
Java formerly excluded it, selected roaming destroyer slot 1562 / Java unit
38, cleared that ship's valid `[E,SE,E,E,SE,E]` Patrol tail, and replaced its
goal. Native retains route index four through the fixture-384 refusal and
fixture-399 collision band, then consumes the southeast byte at fixture 414;
Java's replacement route formerly made that stride at 417. The corrected case
is exact through fixture 446; its fixture-447 critter-order finding is
independent.

Efficacy receipt
`.bne-test-efficacy/c414-xhuman7-single-naval-helper/runs/28f97cd8289cb916a432baee4b374302388ff22cc4d7c7ff3fd5279fa2f76ce7`
proves the focused assertion executes and fails on `71fcdd9`, then executes
and passes on the candidate. The new post-death repeat-hit assertion and 17
focused naval patrol, coast-goal, small-warship, capital-ship, and juggernaut
checks pass. Both fixed 52-case gates pass, and the long receipt's source
capsule verifies with zero sealed untracked inputs. The ordinary executable
next-level gate exits zero after 209 Python checks (four skipped), 98
engine/desktop checks, and 223 dual-adapter command scenarios. Its 11
comparable scenarios remain 6 exact / 5 divergent with no regression or
infrastructure failure.
`--require-certified` remains incomplete on the documented producer lanes,
and remote AI discovery still stops at strict SSH verification of the changed
`i9beef` host key, which was not modified.

The shared frontier remains fixture 312: expansion Orc 8's submarine route
publication family is paused pending a new discriminator. Expansion Human
12's fixture-333 wood route is independently paused on global route-buffer
state. The earliest unpaused finding is now Orc 11 at fixture 418, followed by
Human 12 at 422, Human 8 at 427, expansion Human 10 at 430, expansion Human 5
at 435, expansion Orc 11 at 438, Orc 8 at 442, expansion Orc 12 at 444,
expansion Human 8 at 446, and expansion Human 7 at 447.

## Prior release checkpoint — 2026-09-01 (laden-return timer-one wake)

Accepted cycle-1,800 receipt `9270ffac` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 40,045, an increase of 136. Human 14 advances from fixture 406
to 542. The cycle-400 fleet remains 50 clean / 2 divergent / 0 failed under
receipt `caf222b2`. The long receipt is retained at
`.bne-artifacts/runs/9270ffacf746d53d58378d9f6f5f681b145fc5d7e13d6fc7576e54f2fe367fa3`.
Its manifest has SHA-256
`70c0ebfee3f8ddb97a36dc83d062d1078476455cb8acd61c09078a624eef54c5`
and binds dirty engine-input identity
`21c40353007b273ebac7f5e2fef2f8531418f60ed682988d41e4f28a08786e9c`
at base revision `b5dd271` to authenticated, replayable source capsule
`9c38af13d18ac71d27b13ca1492820caa93c3381ca589248e3cd5284533ae0c6`.

Behavioral delta: a retained laden-return route's Move timer one is an exposed
action state. A callback which changes timer two to one does not also execute
the route wake; the following action callback owns the cached step or
`FUN_004379e0` route park. Java's surrogate order delay could expire while it
was decrementing Move two to one and incorrectly perform both operations in
one visit. The corrected bridge retains one logical callback only when the
parked return's native Move timer is still above one. A wake which enters with
timer one already exposed remains immediate. The implementation contains no
mission, map, coordinate, fixture, exact-cycle, faction, route-length, or
unit-ID branch.

Proof delta: Human 14 native laden peon slot 1539 / Java unit 61 retains its
consumed south tail behind a clean convoy and advances collision generation
one to two at fixture 391, opening Move `2600/15`. Native still carries route
index five, collision `0x20`, and the south byte at fixture 405 while Move is
`2600/1`. Fixture 406 refuses that head, advances collision to `0x30`, and
parks route index twenty without moving. Fixture 407 redraws `[SE,S]`, commits
southeast to `(57,58)`, and leaves route index one. Java formerly parked the
tail on fixture 405 and therefore redrew and moved a cycle early at 406. The
corrected case is exact through fixture 541; its fixture-542 critter-order
finding is independent. Expansion Human 7 slot 1451 and Orc 5 slot 1529 are
held-out free-head controls: both enter their wake with timer one already
exposed and retain their fixture-286 northeast and fixture-289 southeast
steps.

Efficacy receipt
`.bne-test-efficacy/c406-human14-return-timer-one-park/runs/f43738c512d41f65d47388c0f15e1f962efc5af80680a62c877e46becfae3516`
proves the focused assertion executes and fails on `b5dd271`, then executes
and passes on the candidate. All 48 focused laden-return, convoy-route,
mine-exit, tanker-return, and real-data timing checks pass. Both fixed 52-case
gates pass, and the long receipt's source capsule verifies with zero sealed
untracked inputs. The ordinary executable next-level gate exits zero after
209 Python checks (four skipped), 98 engine/desktop checks, and 223
dual-adapter command scenarios. Its 11 comparable scenarios remain 6 exact /
5 divergent with no regression or infrastructure failure.
`--require-certified` remains incomplete on the documented producer lanes,
and remote AI discovery still stops at strict SSH verification of the changed
`i9beef` host key, which was not modified.

The shared frontier remains fixture 312: expansion Orc 8's submarine route
publication family is paused pending a new discriminator. Expansion Human
12's fixture-333 wood route is independently paused on global route-buffer
state. The earliest unpaused finding is expansion Human 7 at fixture 414,
followed by Orc 11 at 418, Human 12 at 422, Human 8 at 427, expansion Human 10
at 430, expansion Human 5 at 435, and expansion Orc 11 at 438.

## Prior release checkpoint — 2026-09-01 (resource-dropout fourth-leg turn)

Accepted cycle-1,800 receipt `31d10f2c` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 39,909, an increase of 761. Human 7 advances from fixture 405
to 493, Orc 3 from 625 to 1272, and expansion Human 9 from 489 to 515. The
cycle-400 fleet remains 50 clean / 2 divergent / 0 failed under receipt
`74b795a0`. The long receipt is retained at
`.bne-artifacts/runs/31d10f2c49ff0e1872734680d14c791b65552ab1ff2de881ab8dd5ab51524e6f`.
Its manifest has SHA-256
`dc4e6aa97ac7b25003f7614e6a9059dab3daf809254162b2b838faf486a4b9c1`
and binds dirty engine-input identity
`a83f6a0c13a1052f1e4adb362b022de4843f06dce055a22874fa68f1fc8a057e`
at base revision `ee577b4` to authenticated, replayable source capsule
`f2e74e0e26bca5a0d3a72016e80c19759146af4ad9be816c282ee979bef5d8ab`.

Behavioral delta: BNE's `0x00443a40` perimeter walker applies its signed turn
after every leg, including the fourth. It then backs out along that newly
selected direction before enlarging both traversal dimensions for the next
pass. Java omitted the fourth turn and backed out along the completed leg.
That is not equivalent for an odd-sized container: native's restart can
change both coordinates' parity, while Java repeatedly walked the wrong
lattice. Separately, a fresh Return Goods order's empty mine pointer does not
by itself invoke `AiGetSuitableDepot`; only the authenticated long-trip or
depot-congestion triggers do. The ordinary resource search therefore remains
centred on the current depot. The implementation contains no mission, map,
coordinate, fixture, exact-cycle, faction, route-length, or unit-ID branch.

Proof delta: Human 7 native tanker slot 1491 / Java unit 109 is contained in
the 3x3 refinery at `(72,72)` through fixture 404. The visit is neither longer
than 500 movement cycles nor congested above fifteen relevant references, and
the fresh order has no remembered platform. Native selects eastern platform
`(79,77)`. Every candidate in the first dropout perimeter fails the tanker's
even/even movement-grid callback. After the east fourth leg, native turns
north and restarts the second perimeter at `(76,76)`, rejects `(76,75)`, then
accepts `(76,74)` at fixture 405. Java formerly searched from the alternative
refinery, then backed out along the old east leg and ultimately surfaced west
at `(70,72)`. The corrected case is exact through fixture 492; its new
fixture-493 tanker-Y finding is independent. Orc 7's authenticated 3x3 exit is
the held-out negative control and retains its fixture-596 `(52,34)` anchor.

Efficacy receipt
`.bne-test-efficacy/c405-human7-resource-spiral-restart/runs/42e115f98685e792632b2c090bd36356124286bb83ed756ece0631060f38e353`
proves the focused assertion executes and fails on `ee577b4`, then executes
and passes on the candidate. All 29 focused oil-lifecycle, tanker-round-trip,
dropout, and Human 7 real-data checks pass. Both fixed 52-case gates pass, and
the long receipt's source capsule verifies with zero sealed untracked inputs.
The ordinary executable next-level gate exits zero after 209 Python checks
(four skipped), 98 engine/desktop checks, and 223 dual-adapter command
scenarios. Its 11 comparable scenarios remain 6 exact / 5 divergent with no
regression or infrastructure failure. `--require-certified` remains
incomplete on the documented producer lanes, and remote AI discovery still
stops at strict SSH verification of the changed `i9beef` host key, which was
not modified.

The shared frontier remains fixture 312: expansion Orc 8's submarine route
publication family is paused pending a new discriminator. Expansion Human
12's fixture-333 wood route is independently paused on global route-buffer
state. The earliest unpaused finding is Human 14 at fixture 406, followed by
expansion Human 7 at 414, Orc 11 at 418, Human 12 at 422, Human 8 at 427,
expansion Human 10 at 430, expansion Human 5 at 435, and expansion Orc 11 at
438.

## Prior release checkpoint — 2026-09-01 (mobile-shot target residual exclusion)

Accepted cycle-1,800 receipt `86eefab8` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 39,148, an increase of thirty-six. Expansion Orc 11 advances
from fixture 402 to 438. The cycle-400 fleet remains 50 clean / 2 divergent /
0 failed under receipt `e6bde2c3`. The long receipt is retained at
`.bne-artifacts/runs/86eefab85ae702c69e5378e7f0ecf40665e9497fe17a697a5d292fa8d7c8cea1`.
Its manifest has SHA-256
`8d288cd06545cf55afeb4fc8745eacf68431dda1be67c77acab6338265c27f9b`
and binds dirty engine-input identity
`4f981a38116ae4eb0d204853ab72039222662b061b43cd1aeac3673508a4cd43`
at base revision `649faeb` to authenticated, replayable source capsule
`b2a286a8060fb7178a08fc81685ac1e3fe04ec1f65ebcc851faca6a41f0a956c`.

Behavioral delta: BNE's mobile-shot constructor rebuilds a live target's aim
from the target record's pixel words plus the type-center table, then applies
its two random coordinate offsets. It does not add the target's retained
residual movement bank. Java's pixel coordinate is the authenticated
counterpart of those words; adding residual again displaced the aim despite an
otherwise exactly aligned asynchronous RNG stream. Source muzzle construction
continues to include the shooter's own residual. The implementation contains
no mission, map, coordinate, fixture, exact-cycle, faction, route-length, or
unit-ID branch.

Proof delta: expansion Orc 11 native destroyer slot 1531 / Java unit 69 fires
at juggernaught slot 1512 / Java unit 88 at fixture 393. The target's pixel
position is `(320,1280)` and its retained residual is `(-1,+1)`. Both engines
enter the constructor with RNG seed `711326973`, consume results 18468 and
3090, and leave seed `2350014027`, giving offsets `(+1,-1)`. Native helper
`0x0040fd50` reads target words `+0x00` and `+0x02` and produces aim
`(337,1295)`; Java formerly reapplied residual and produced `(336,1296)`. The
correct 127-pixel flight impacts at fixture 402 for nineteen HP. Expansion Orc
11 is now exact through fixture 437; its fixture-438 gryphon position finding
is independent.

Efficacy receipt
`.bne-test-efficacy/c402-xorc11-target-residual-constructor/runs/a63e3309f203a0b1b894e32d9a48bb37fcd881a3d366a886176c1c41343ad7fa`
proves the focused assertion executes and fails on `649faeb`, then executes
and passes on the candidate. All 39 focused XOrc 11, projectile-presentation,
and missile-motion tests pass. Both fixed 52-case gates pass, and the long
receipt's source capsule verifies with zero sealed untracked inputs. The
ordinary executable next-level gate exits zero after 209 Python checks (four
skipped), 98 engine/desktop checks, and 223 dual-adapter command scenarios.
Its 11 comparable scenarios remain 6 exact / 5 divergent with no regression
or infrastructure failure. `--require-certified` remains incomplete on the
documented producer lanes, and remote AI discovery still stops at strict SSH
verification of the changed `i9beef` host key, which was not modified.

The shared frontier remains fixture 312: expansion Orc 8's submarine route
publication family is paused pending a new discriminator. Expansion Human
12's fixture-333 wood route is independently paused on global route-buffer
state. The earliest unpaused finding is Human 7 at fixture 405, followed by
Human 14 at 406, expansion Human 7 at 414, Orc 11 at 418, Human 12 at 422,
Human 8 at 427, expansion Human 10 at 430, and expansion Orc 11 at 438.

## Prior release checkpoint — 2026-09-01 (neutral-quarry capital Patrol restore)

Accepted cycle-1,800 receipt `b6402fc5` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 39,112, an increase of five. Expansion Orc 11 advances from
fixture 397 to 402. The cycle-400 fleet improves from 49 clean / 3 divergent
to 50 clean / 2 divergent / 0 failed under receipt `f456c934`. The long
receipt is retained at
`.bne-artifacts/runs/b6402fc566decc78dc10c2b773f2e184c119494a1ad800f2f28e7b78b43562e6`.
Its manifest has SHA-256
`493ab9db7aa7aa80a99f24be6053c246029106880f45f12762c5ca6d34f6188f`
and binds dirty engine-input identity
`c6c7b70388fd401068e6e3002e9c1fcb8453412cd4afd2dd21789c3747630319`
at base revision `3229701` to authenticated, replayable source capsule
`38f338237cdaebcd1636660bfec562afda4b17ae6d1a5579227df6429ccb4dad`.

Behavioral delta: when a behavior-six capital ship's already-paid Attack body
retains a quarry that ceases to be an enemy, retail lets the paid body finish
but gives the saved Patrol ownership of the tail before the generic spatial
free scan. RestoreOrder clears the dead attack pointer, resumes toward the
ship's AI home with a fresh three-visit Still constructor, and lets the first
restored Patrol OP0 consume the already-owned return stride instead of
immediately acquiring another enemy. A one-shot, saveable arming bit names
that order boundary and is cleared by the restored OP0 or any replacement
Stop, Attack, or Patrol. The implementation contains no mission, map,
coordinate, fixture, exact-cycle, faction, route-length, or unit-ID branch.

Proof delta: expansion Orc 11 native battleship slot 1539 / Java unit 61 is
at `(8,30)` on Attack with saved Patrol and quarry slot 1525 / Java unit 75
at fixture 381. The quarry hands ownership from player five to neutral at
fixture 382, but the ship retains the paid Attack through fixture 396 at
sequence 3101 / timer one. At fixture 397 retail clears the fight, restores
Patrol toward behavior-six home `(21,34)`, and starts Still sequence 2955 at
timer three. It owns two more quiet visits and first-steps north-east to
`(10,28)` at fixture 400. Java formerly performed the generic tail scan,
selected live destroyer 1521 / unit 79, and stayed on Attack.

The new fixture-402 hit-point finding is independent rather than fallout from
the restored Patrol. Native small-cannon projectile slot 3 is constructed at
fixture 393 from `(464,1232)` toward `(337,1295)`; its source and target
pointers differ by exactly nineteen 152-byte unit records, pairing native
destroyer 1531 with juggernaught 1512. It impacts at fixture 402 for nineteen
HP. Java's paired unit 69 constructs the same shot at fixture 393, before the
fixed order boundary, toward `(336,1296)`; it reaches the exact sixteen-pixel
flight boundary a visit later and impacts at fixture 403 for eleven HP. Both
the accepted baseline and candidate have that identical Java HP timeline.

Efficacy receipt
`.bne-test-efficacy/c397-xorc11-neutralized-quarry-patrol-restore/runs/ea9f2c82e46b6cd23d140020f970f9f50a26354dfe5e2b79623d63d9a06ce051`
proves the focused assertion executes and fails on `3229701`, then executes
and passes on the candidate. All 108 focused XOrc 11, capital/small-warship
Patrol, sea-occupancy, and save/resume tests pass. Both fixed 52-case gates
pass, and the long receipt's source capsule verifies with zero sealed untracked
inputs. The ordinary executable next-level gate exits zero after 209 Python
checks (four skipped), 98 engine/desktop checks, and 223 dual-adapter command
scenarios. Its 11 comparable scenarios remain 6 exact / 5 divergent with no
regression or infrastructure failure. `--require-certified` remains incomplete
on the documented producer lanes, and remote AI discovery still stops at
strict SSH verification of the changed `i9beef` host key, which was not
modified.

The shared frontier remains fixture 312: expansion Orc 8's submarine route
publication family is paused pending a new discriminator. Expansion Human
12's fixture-333 wood route is independently paused on global route-buffer
state. The earliest unpaused finding is expansion Orc 11's small-cannon
aim/flight/damage family at fixture 402, followed by Human 7 at 405, Human 14
at 406, expansion Human 7 at 414, Orc 11 at 418, Human 12 at 422, Human 8 at
427, and expansion Human 10 at 430.

## Prior release checkpoint — 2026-09-01 (dying naval-guard rendezvous release)

Accepted cycle-1,800 receipt `8491182d` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 39,107, an increase of nineteen. Expansion Human 7 advances
from fixture 395 to 414. The cycle-400 fleet improves from 48 clean / 4
divergent to 49 clean / 3 divergent / 0 failed under receipt `d1ee8abc`.
The long receipt is retained at
`.bne-artifacts/runs/8491182d8738c88ff68abb68abc5b2d69f71a9a9bca7267fb39221d71b26c3b7`.
Its manifest has SHA-256
`123bae69164f26dc3e2f7eb44cd3dc957ebfd36df3176ca3938f198a8ee9f941`
and binds dirty engine-input identity
`84a710d12d2ad9fc6314a1d1542af08568a26cd812a1630f342f59a2833798a9`
at base revision `0ac8605` to authenticated, replayable source capsule
`fd54582ec8f377591af4e0ff80716b124b0fb923d5a5356818953aa685cdd559`.

Behavioral delta: a behavior-six small warship answering a unit-position naval
help order retains the guarded unit pointer separately from its position
Patrol and retains the map-authored patrol origin separately from its rewritten
service-base home. A guard entering its death action does not immediately
cancel pixels already committed by the helper. At the helper's next residual
action boundary retail releases the pointer, restores action five and the
authored reverse endpoint, coast-rewrites the home from the settled hull, and
restarts the armed Patrol constructor. The implementation contains no mission,
map, coordinate, fixture, exact-cycle, faction, route-length, or unit-ID branch.

Proof delta: expansion Human 7 native submarine slot 1511 / Java unit 89
answers friendly destroyer slot 1420 / Java unit 180 at `(86,120)`. The help
order is issued at fixture 55, promotes at 91, and first moves south-east at
94. The destroyer enters its death action at fixture 355, but retail retains
the unit pointer and live route through the submarine's residual movement. At
fixture 394 the helper remains on order four with goal `(86,120)` and pixels
`(832,2110)`. At fixture 395 it settles at `(26,66)`, clears the pointer,
restores action five with patrol origin `(18,54)`, rewrites the blocked home to
goal `(24,42)`, and starts Still sequence 3464 at timer three without consuming
the cached south byte. Its first returning step is north-west at fixture 398.
A repeated hit at fixture 155 does not requeue help or replace the live route,
and the ordinary small-warship Patrol family remains the held-out control.

Efficacy receipt
`.bne-test-efficacy/c395-xhuman7-dying-guard-rendezvous-release/runs/5f6edea5b324bc78c8867c1e9b62ca32008f8e2a1cb3446bd4f451ca05346bb1`
proves the focused assertion executes and fails on `0ac8605`, then executes
and passes on the candidate. All 77 focused naval-patrol, small-warship,
sea-occupancy, and save/resume tests pass. Both fixed 52-case gates pass, and
the source capsule verifies with zero sealed untracked inputs. The ordinary
executable next-level gate exits zero after 209 Python checks (four skipped),
98 engine/desktop checks, and 223 dual-adapter command scenarios. Its 11
comparable scenarios remain 6 exact / 5 divergent with no regression or
infrastructure failure. `--require-certified` remains incomplete on the
documented producer lanes, and remote AI discovery still stops at strict SSH
verification of the changed `i9beef` host key, which was not modified.

The shared frontier remains fixture 312: expansion Orc 8's submarine route
publication family is paused pending a new discriminator. Expansion Human
12's fixture-333 wood route is independently paused on global route-buffer
state. The earliest unpaused finding is expansion Orc 11 at fixture 397,
followed by Human 7 at 405, Human 14 at 406, expansion Human 7 at 414, Orc 11
at 418, Human 12 at 422, Human 8 at 427, and expansion Human 10 at 430.

## Prior release checkpoint — 2026-09-01 (moving-quarry cold-retry retirement)

Accepted cycle-1,800 receipt `eb51b8f4` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 39,088, an increase of thirty-seven. Human 8 advances from
fixture 390 to 427. The cycle-400 fleet improves from 47 clean / 5 divergent
to 48 clean / 4 divergent / 0 failed under receipt `7956abd2`. The long
receipt is retained at
`.bne-artifacts/runs/eb51b8f44c5233d8218c753abad230e1b6f0d2167aacd224619d3b87230c3eb7`.
Its manifest has SHA-256
`862707bb7189f91063490f496a561bc0d731bd5fc44b18b900365a195eb14f3a`
and binds dirty engine-input identity
`3c9364351812d1763cf8845d112ae2464ef0801479c6503af235d0878ff6e51b`
at base revision `c68f1e2` to authenticated, replayable source capsule
`14b9346ce07b83d480e89140b1611d3843074c578bbf2ed14fa068966f330a51`.

Behavioral delta: when a boxed melee attacker replaces its dead quarry with a
moving target, retail gives the replacement one additional paid cold Attack
constructor, then consumes the cold-loop latch and releases the wall detour.
Java previously kept the latch and bought constructors forever. A stationary
replacement continues reopening the cold constructor, so the rule is about
the replacement's movement state rather than a particular unit or scenario.
The implementation contains no mission, map, coordinate, fixture, cycle,
faction, route-length, or unit-ID branch.

Proof delta: Human 8 native peasant slot 1526 / Java unit 74 attacks from
`(77,62)`. At fixture 384 the dying quarry, native slot 1533 / Java unit 67,
is replaced by the moving returner slot 1536 / Java unit 64 at `(79,60)`.
Retail pays one more Attack 3,2,1 sequence and at fixture 390 commits the
non-progressing south-east wall face to `(78,63)`, retaining north-east and
north from raw route `03 01 00`. Java's permanently latched cold loop formerly
rearmed Attack at that boundary. The Java observations occur at internal
cycles 386 and 392, preserving the measured two-cycle fixture offset.
Expansion Human 12's stationary footman slot 1477 / Java unit 151 at `(26,59)`
is the held-out negative control: it continues reopening the cold constructor
and that case remains exact through fixture 252. Human 8 is now exact through
fixture 426; its fixture-427 finding is an independent peasant hit-point split,
native 25 versus Java 20.

Efficacy receipt
`.bne-test-efficacy/c390-human8-moving-replacement-cold-retry/runs/fd711d8cf5dee04d3cd2d80fef7096b68aad0bb10ddc7620f2aaacff3b2f2f86`
proves the focused assertion executes and fails on `c68f1e2`, then executes
and passes on the candidate. All 64 focused moving-quarry, stationary-control,
collision-refill, and attack-resume tests pass. A broader audit also exercised
five known-red assertions in the in-place-first-take and residual-replan
classes; each fails identically on `c68f1e2` and the candidate. Both fixed
52-case gates pass, and the source capsule verifies with zero sealed untracked
inputs. The ordinary executable next-level gate exits zero after 209 Python
checks (four skipped), 97 engine/desktop checks, and 223 dual-adapter command
scenarios. Its 11 comparable scenarios remain 6 exact / 5 divergent with no
regression or infrastructure failure. `--require-certified` remains incomplete
on the documented producer lanes, and remote AI discovery still stops at
strict SSH verification of the changed `i9beef` host key, which was not
modified.

The shared frontier remains fixture 312: expansion Orc 8's submarine route
publication family is paused pending a new discriminator. Expansion Human
12's fixture-333 wood route is independently paused on global route-buffer
state. The earliest unpaused finding is expansion Human 7 at fixture 395,
followed by expansion Orc 11 at 397, Human 7 at 405, Human 14 at 406, Orc 11
at 418, Human 12 at 422, Human 8 at 427, and expansion Human 10 at 430.

## Prior release checkpoint — 2026-09-01 (paid-generation return-tail park)

Accepted cycle-1,800 receipt `501c20cc` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 39,051, an increase of forty. Expansion Human 10 advances
from fixture 390 to 430. The cycle-400 fleet improves from 46 clean / 6
divergent to 47 clean / 5 divergent / 0 failed under receipt `ca6e7c74`.
The long receipt is retained at
`.bne-artifacts/runs/501c20cc59295ef18c679f9caee6073287668433b7dcebd2eb64a47dfbdcc974`.
Its manifest has SHA-256
`d0e45e6e0f7965b999bc1002e8ed48e7c5cbfdd2f5353fe15d80ac46df76f919`
and binds dirty engine-input identity `416faae5` at base revision `5444da0`
to authenticated, replayable source capsule
`9b3cf53b28221208a120caf2e6f4d2849385f0d9dc0ed230924975837fbca03b`.

Behavioral delta: after a laden returner has paid collision generations and
consumed a cached return tail, retail can park that tail behind a moving,
collision-marked allied returner for the same depot. Parking advances the
mover's existing collision generation, clears the consumed route, and lets
the next resource action redraw around the convoy body. It does not reset an
already-paid mover to generation one. Exact same-depot, carried-resource,
moving-blocker, refusal/collision-projection, path-progress, and allied-unit
predicates keep the exception inside laden convoy return traffic. A clean
convoy without that projection still retains its full refusal band, and
saturated fresh-route and unrelated movement controls keep their prior
behavior. The implementation contains no mission, map, coordinate, fixture,
cycle, faction, route-length, or unit-ID branch.

Proof delta: expansion Human 10 native slot 1588 / Java unit 12 reaches
`(56,6)` behind native slot 1584 / Java unit 16 at `(55,7)`. At fixture 389
the mover's consumed south-west tail changes from route index one to twenty
and raw `unit+0x1d` from `0x30` to `0x40`; the blocker remains a moving,
collision-one laden returner for the same depot. At fixture 390 retail redraws
`S,SW`, commits south to `(56,7)`, and retains generation four. Previously
Java kept the stale south-west tail and paid another complete refusal band.
Expansion Human 12 slots 1552/1561 at fixtures 302/303 independently prove
the generation-zero form of the same transaction. Expansion Human 10's clean
convoy, Human 14's clean convoy, and saturated fresh returns remain negative
controls. Expansion Human 10 is now exact through fixture 429; its new
fixture-430 finding is an independent peon y-position split, native 115 versus
Java 116.

Efficacy receipt
`.bne-test-efficacy/c389-xhuman10-paid-generation-return-tail-park/runs/3714ad3aceac42e6f8122bd910dfc3b9a0761a7ec9cdcf0412cef6832b0e4931`
proves the focused assertion executes and fails on `5444da0`, then executes
and passes on the candidate. All seven focused laden-return wake tests and all
68 relevant movement controls pass. Both fixed 52-case gates pass, and the
source capsule verifies with zero sealed untracked inputs. The ordinary
executable next-level gate exits zero after 209 Python checks (four skipped),
97 engine/desktop checks, and 223 dual-adapter command scenarios. Its 11
comparable scenarios remain 6 exact / 5 divergent with no regression or
infrastructure failure. `--require-certified` remains incomplete on the
documented producer lanes, and remote AI discovery still stops at strict SSH
verification of the changed `i9beef` host key, which was not modified.

The shared frontier remains fixture 312: expansion Orc 8's submarine route
publication family is paused pending a new discriminator. Expansion Human
12's fixture-333 wood route is independently paused on global route-buffer
state. The earliest unpaused finding is Human 8 at fixture 390, followed by
expansion Human 7 at 395, expansion Orc 11 at 397, Human 7 at 405, Human 14
at 406, Orc 11 at 418, Human 12 at 422, and expansion Human 10 at 430.

## Current release checkpoint — 2026-09-01 (refusal-marked depot entry overlap)

Accepted cycle-1,800 receipt `cddd8842` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 39,011, an increase of eighty-six. Orc 12 advances from
fixture 382 to 468. The cycle-400 fleet improves from 45 clean / 7 divergent
to 46 clean / 6 divergent / 0 failed under receipt `3148d56f`. The long
receipt is retained at
`.bne-artifacts/runs/cddd8842698ef2749806d3f080915322f3806ff8055f5bd1639c340c204b8889`.
Its manifest has SHA-256
`b054a781f572b1f2d258229e1e0c613cb92a79d0ef64b2215ffbff5803b5bb28`
and binds dirty engine-input identity `c792af4c` at base revision `a9758ba` to
authenticated, replayable source capsule
`ced019c3bd868aea67e3b5c6e4a77b2e01cf9a9e8feac2990d531ef26b4104af`.

Behavioral delta: the resource order's final action-25 dest-arm may commit
onto a depot entry point already occupied by an allied laden returner whose
own staged final-entry route is spent but whose pixels are still draining.
That transaction is distinct from ordinary Move-body soft-clear: the draining
returner remains eligible when its native refusal-generation nibble is
nonzero. Exact same-depot, HARVEST, carried-resource, staged-approach,
spent-route, moving-residual, land-footprint, and alliance predicates keep the
exception inside the final depot-entry transaction. Pre-stage returners,
stationary entrants, ordinary movers, collision-elevated Java bodies, and
unrelated occupancy retain their existing behavior. The implementation
contains no mission, map, coordinate, fixture, cycle, faction, route-length,
or unit-ID branch.

Proof delta: Orc 12 peon native slot 1502 / Java unit 98 is staged south of
the town hall at fixture 382 while peon slot 1507 / Java unit 93 still drains
its spent action-25 pixels across the same entry point. The blocker is laden,
returning to the same depot, moving with route index twenty, and carries raw
`unit+0x1d == 0x90` (refusal generation nine) from fixtures 378 through 384.
Retail nevertheless dest-arms slot 1502 south onto `(58,51)` at fixture 383,
briefly giving both peons the same logical tile. Ordinary Java soft-clear
correctly rejects unit 93; only the strict final-entry transaction admits the
overlap. Independent expansion Orc 12 slots 1394/1396 at fixture 264 and
expansion Human 7 slots 1458/1446 at fixture 330 remain positive controls.
Expansion Human 8's unstaged queue and a forced stationary entry body remain
negative controls. Orc 12 is now exact through fixture 468; its new fixture-469
finding is an independent critter Still-versus-Move split on slot 1530.

Efficacy receipt
`.bne-test-efficacy/c383-orc12-refusal-marked-depot-entry/runs/831af84a45b15b4219d5aac3a29443f200309841fc87bef9df1f6e234c703324`
proves the focused Orc 12 assertion executes and fails on `a9758ba`, then
executes and passes on the candidate. All five depot-entry overlap tests pass,
the focused case is exact through fixture 463, both fixed 52-case gates pass,
and the source capsule verifies with zero sealed untracked inputs. The
ordinary executable next-level gate exits zero after 209 Python checks (four
skipped) and 97 engine/desktop checks. Its command worklist remains 11
comparable scenarios (6 exact / 5 divergent), with 223 dual-adapter scenarios
executed and no regression or infrastructure failure. `--require-certified`
remains incomplete on the documented producer lanes, and remote AI discovery
still stops at strict SSH verification of the changed `i9beef` host key, which
was not modified.

The shared frontier remains fixture 312: expansion Orc 8 human submarine slot
1432 is at x 88 natively versus x 90 in Java, and its route-publication family
remains paused pending a new discriminator. Expansion Human 12's fixture-333
wood route is independently paused pending a discriminator for its global
route-buffer state. The earliest unpaused fleet findings are Human 8 and
expansion Human 10 at fixture 390, followed by expansion Human 7 at 395,
expansion Orc 11 at 397, and Human 7 at 405.

## Current release checkpoint — 2026-09-01 (naval body compact decay program)

Accepted cycle-1,800 receipt `33574c41` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 38,925, an increase of fifteen. Expansion Orc 11 advances
from fixture 382 to 397. The cycle-400 fleet remains 45 clean / 7 divergent /
0 failed under receipt `d040a613`. The long receipt is retained at
`.bne-artifacts/runs/33574c41bb1ca29a46f96df7343c1da8d6e64c37cf877f29f1e147eb859764c3`.
Its manifest has SHA-256
`3f8ac95a785385f0ec750819c81dbf2a9c6d914636959ed4312f5cff8de9650a`
and binds dirty engine-input identity `f6d91c3a` at base revision `8966041` to
authenticated, replayable source capsule
`325f5cd7d8e03899c37c57f10adfd04e37af4fe97cd193f1e174f6e2b09bdcf7`.

Behavioral delta: a vanishing mobile body installed from a sea unit runs the
retail type-105 slot-three compact decay program. Its first 100-cycle hold
retains the living owner, the next instruction boundary hands the body to
neutral, and the second 100-cycle hold ends at the program's Die marker. The
catalog presentation may continue advancing but does not own this naval-body
lifetime. Land bodies, rubble, revealers, and types without corpses retain
their existing lifecycle. The implementation contains no mission, map,
coordinate, fixture, cycle, faction, route-length, or unit-ID branch.

Proof delta: expansion Orc 11 destroyer native slot 1525 / Java unit 75 is
living at fixture 281, becomes a type-105 body owned by player 5 at fixture
282, hands ownership to neutral at fixture 382, and leaves the map at fixture
482. Independent destroyer native slot 1506 / Java unit 94 becomes a body at
fixture 349, hands ownership to neutral at 449, and leaves the map at 549. The
measured fixture-to-Java offset is two internal cycles. Authenticated
`script.bin` decoding identifies type 105's slot-three starts at offset 3839:
the opening tick selects 3843 with timer 100, the first boundary selects 3846
with timer 100, and the next boundary reaches its terminal action marker.
Applying that program to every vanishing body was rejected by the fixed
cycle-400 fleet because it moved land-body or rubble ownership earlier in
expansion Human 2, 4, 10, and 12. Restricting the rule to the destroyed unit's
naval provenance retains all four negative witnesses. Expansion Orc 11 is now
exact through fixture 396; its new fixture-397 finding is an independent
battleship Patrol-versus-Attack order split.

Efficacy receipt
`.bne-test-efficacy/c382-xorc11-naval-compact-corpse-decay/runs/a51518ee155aa731c9bf40265adbbcdcafa2d7aadca21754d2afe2a44474ac7e`
proves the first lifecycle assertion executes and fails on `8966041`, then
executes and passes on the candidate. All 35 selected XOrc 11, corpse,
container-death, death-vision, dying-tail, and type-path controls pass. A
separate audit proves the unrelated
`CorpseTest#aDieInstructionKeepsActionTableMutationOrder` failure exists on
both the baseline and candidate. Both fixed 52-case gates pass, and the source
capsule verifies with zero sealed untracked inputs. The ordinary executable
next-level gate exits zero after 209 Python checks (four skipped) and 97
engine/desktop checks; its command worklist remains 11 comparable scenarios
(6 exact / 5 divergent) without regression or infrastructure failure.
`--require-certified` remains incomplete on the documented producer lanes,
and remote AI discovery still stops at strict SSH verification of the changed
`i9beef` host key, which was not modified.

The shared frontier remains fixture 312: expansion Orc 8 human submarine slot
1432 is at x 88 natively versus x 90 in Java, and its route-publication family
remains paused pending a new discriminator. Expansion Human 12's fixture-333
wood route is independently paused pending a discriminator for its global
route-buffer state. The earliest unpaused fleet finding is Orc 12 at fixture
383, followed by Human 8 and expansion Human 10 at 390, expansion Human 7 at
395, and expansion Orc 11 at 397.

## Current release checkpoint — 2026-09-01 (cycle-390 paid-wrap first-collision refill)

Accepted cycle-1,800 receipt `2a2969a2` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 38,910, an increase of fifteen. Expansion Human 10 advances
from fixture 375 to 390. The cycle-400 fleet remains 45 clean / 7 divergent /
0 failed under receipt `d474b1e3`. The long receipt is retained at
`.bne-artifacts/runs/2a2969a2cbea88a05b0bf9f928e4436c16994cce3eeb37fcf7e646a192a6d065`.
Its manifest has SHA-256
`5f00bfce9e6147e13676f96792c03828cfedb6ddae5988551d4cdc531275e227`
and binds dirty engine-input identity `7bb776e4` at base revision `fc14a4d` to
authenticated, replayable source capsule
`4d5704eeb3661bfce7653f303e0577253360d88806b4a335ed816c3703b9ff5e`.

Behavioral delta: a first collision generation carried through already-paid
melee Attack-wrap ownership does not expose a separate route-index-twenty park
when its clean final pixels settle. That callback writes the replacement route
in the same cooperative collision view and may commit its free head
immediately. A collision-one route without paid-wrap ownership retains the
established one-visit park, as do hard-refusal generations and saturated or
later collision bands. The implementation contains no mission, map,
coordinate, fixture, cycle, faction, initial-route-length, or unit-ID branch.

Proof delta: expansion Human 10 knight native slot 1493 / Java unit 107 chases
axethrower slot 1496 / Java 104 at `(78,87)`. The measured fixture-to-Java
offset is two internal cycles. Its paid `[SW,NW,N]` route retains collision one
and zero hard refusals; the last north byte commits at fixture 363 and its
pixels drain through fixture 374. On fixture 375 native settles at `(80,87)`,
writes `[W,W]`, and first-steps west to `(79,87)` in the same action visit.
Java formerly parked the exhausted route and performed that identical writer
and step one callback later. Expansion Human 12 grunt native slot 1503 / Java
97 is the negative witness: its collision-one route has no paid-wrap owner,
parks on fixture 55, and only refills its occupied south head on fixture 56.
Expansion Human 10 is now exact through fixture 389; its new fixture-390
finding is an independent peon position split for native slot 1588.

Efficacy receipt
`.bne-test-efficacy/c375-xhuman10-paid-wrap-first-collision/runs/37b030e4206a0b498633617195ad476429c878771e1387b02cfbaf9b9ae95a5f`
proves the focused assertion executes and fails on `fc14a4d`, then executes
and passes on the candidate. All 79 selected Human 10 damage/cadence,
collision-refill, retarget, wrap-destination-arm, and dying-tail real-data
controls pass. Both fixed 52-case gates pass, and the source capsule verifies
with zero sealed untracked inputs. The ordinary executable next-level gate
exits zero after 209 Python checks (four skipped) and 97 engine/desktop checks;
its command worklist remains 11 comparable scenarios (6 exact / 5 divergent)
without regression or infrastructure failure. `--require-certified` remains
incomplete on the documented producer lanes, and remote AI discovery still
stops at strict SSH verification of the changed `i9beef` host key, which was
not modified.

The shared frontier remains fixture 312: expansion Orc 8 human submarine slot
1432 is at x 88 natively versus x 90 in Java, and its route-publication family
remains paused pending a new discriminator. Expansion Human 12's fixture-333
wood route is independently paused pending a discriminator for its global
route-buffer state. The earliest unpaused fleet finding is expansion Orc 11 at
fixture 382, followed by Orc 12 at 383, Human 8 and expansion Human 10 at 390,
and expansion Human 7 at 395.

## Current release checkpoint — 2026-09-01 (cycle-444 fresh depot queue-head redraw)

Accepted cycle-1,800 receipt `8a0cde67` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 38,895, an increase of seventy. Expansion Orc 12 advances from
fixture 374 to 444. The cycle-400 fleet improves from 44 clean / 8 divergent
to 45 clean / 7 divergent / 0 failed under receipt `40ecaff4`. The long receipt
is retained at
`.bne-artifacts/runs/8a0cde678d1c39574041fb3b5a69508d696d6dcea85d433556c69496d2779779`.
Its manifest has SHA-256
`f8725c6d09b132a2664c9451f17a0799164b0c4e2739703c2ebdff9fdce70807`
and binds dirty engine-input identity `83fd8588` at base revision `53adece` to
authenticated, replayable source capsule
`4847ac38c0f692b9c0d846b5c16f59abc50c7b3ca9ece44dbc5da647f5ec15ef`.

Behavioral delta: when a laden land returner drains a consumed route with one
cardinal byte aimed through a newly shifted depot edge, an allied loaded
worker for the same depot can have entered fresh action 25 on that pass. If
that zero-collision queue head owns the exact intermediate cell and its native
two-visit staging delay, the follower refreshes its depot edge, parks the
consumed byte with collision one, and redraws around the leader on its next
visit. Ordinary one-byte depot rays, duplicate tails, saturated refusal bands,
and final-entry overlaps retain their established rules. The implementation
contains no mission, map, coordinate, fixture, cycle, faction, initial-route-
length, or unit-ID branch.

Proof delta: expansion Orc 12 peasant native slot 1342 / Java unit 258 is laden
at `(59,104)` with the last north byte of a consumed six-step return route.
Native lower slot 1337 / Java unit 263 is directly ahead at `(59,103)` with a
spent route still draining toward the same castle. The measured fixture-to-
Java offset is two internal cycles. On fixture 373 the leader enters action 25
before the follower's decision; native changes the follower's depot edge from
`(60,102)` to `(59,102)`, writes route index twenty and collision one, then on
fixture 374 redraws north-west and first-steps to `(58,103)`. Java formerly
restored the cached north byte through the near-depot refusal path and waited
for the leader to vacate. The case is now exact through fixture 443; its new
fixture-444 finding is an independent peasant position split for native slot
1396.

Efficacy receipt
`.bne-test-efficacy/c374-xorc12-fresh-depot-queue-head/runs/0cf23b7cacf459f555994182dd924553b0a9cb3e5fb294c348811d191eee5652`
proves the focused assertion executes and fails on `53adece`, then executes
and passes on the candidate. All 34 selected depot-tail, depot-entry,
mine-exit, movement-loop, and approach-damage controls pass. Both fixed
52-case gates pass. The ordinary executable next-level gate exits zero after
209 Python checks (four skipped) and 97 engine/desktop checks; its command
worklist remains 11 comparable scenarios (6 exact / 5 divergent) without
regression or infrastructure failure. `--require-certified` remains incomplete
on the documented producer lanes, and remote AI discovery still stops at
strict SSH verification of the changed `i9beef` host key, which was not
modified.

The shared frontier remains fixture 312: expansion Orc 8 human submarine slot
1432 is at x 88 natively versus x 90 in Java, and its route-publication family
remains paused pending a new discriminator. Expansion Human 12's fixture-333
wood route is independently paused pending a discriminator for its global
route-buffer state. The earliest unpaused fleet finding is expansion Human 10
at fixture 375, followed by expansion Orc 11 at 382, Orc 12 at 383, Human 8 at
390, and expansion Human 7 at 395.

## Current release checkpoint — 2026-09-01 (cycle-382 armed-flyer Patrol acquisition)

Accepted cycle-1,800 receipt `4eee82d1` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 38,825, an increase of eleven. Expansion Orc 11 advances from
fixture 371 to 382. The cycle-400 fleet remains 44 clean / 8 divergent / 0
failed under receipt `e66435cc`. The long receipt is retained at
`.bne-artifacts/runs/4eee82d1ccba202264b0e82d9b15a9d7261feee240c79cc0d23ee660039245df`.
Its manifest has SHA-256
`878f461f0bdd55a9a0a6b2db4715368830f60325dc9a637f7c6fdbbd996433a7`
and binds dirty engine-input identity `ce46b39e` at base revision `594d316` to
authenticated, replayable source capsule
`0bf6286b1c88293fd96d8c4119070a45b772c0b73c9eaf5008bbc407ef66655d`.

Behavioral delta: an armed doubled flyer's Patrol action scans for a hostile
only on the Still constructor's opcode-zero visit. A successful scan writes a
strong unit Attack as the queued order while Patrol authors and commits its
next doubled stride. Patrol remains current for the complete committed Move
body, then promotes direct Attack on the same visit its last pixels settle.
The generic fifteen-cycle presentation auto-scan no longer interrupts armed-
flyer Patrol bodies or substitutes weak position AttackMove. The rule contains
no mission, map, coordinate, fixture, cycle, faction, route-length, or unit-ID
branch.

Proof delta: expansion Orc 11 gryphon native slot 1589 / Java unit 11 is exact
through fixture 370 at `(20,30)` under Patrol; Java formerly promoted weak
AttackMove at 371 while native continued its southwest flight. The measured
fixture-to-Java offset is two internal cycles. Native first-steps southwest to
`(18,32)` at fixture 381, reconstructs Patrol Still `2233/3` at 405, and
reaches constructor opcode zero at 413. That visit changes raw next order
`60 -> 12`, publishes hostile goal `(10,40)` and route `[SW,SW,SW,SW]`, and
first-steps to `(16,34)` without changing current Patrol. Native remains Patrol
through 436; fixture 437 settles the final pixels and changes current/next
`4/12 -> 12/60` at Attack sequence `2313/3`. Java now reproduces those action
boundaries. The new case frontier at fixture 382 is independent dead-body
ownership: native slot 1525 is neutral player 15 while Java retains player 5.

Efficacy receipt
`.bne-test-efficacy/c371-xorc11-armed-flyer-patrol-op0/runs/7cb616f53bb28d2fbbe28bb252301503100d07285de2b2f8da0c75c52ea90b26`
proves the focused assertion executes and fails on `594d316`, then executes
and passes on the candidate. All 75 selected armed-flyer, land, capital-ship,
small-warship, Patrol, and attack-move real-data controls pass. The broader
synthetic `AttackMoveTest` remains independently red with the identical 24 of
67 assertions failing on baseline and candidate, so it is not counted as
green evidence. Both fixed 52-case gates pass. The ordinary executable next-
level gate exits zero after 209 Python checks (four skipped) and 97
engine/desktop checks; its command worklist remains 11 comparable scenarios
(6 exact / 5 divergent) without regression or infrastructure failure.
`--require-certified` remains incomplete on the documented producer lanes,
and remote AI discovery still stops at strict SSH verification of the changed
`i9beef` host key, which was not modified.

The shared frontier remains fixture 312: expansion Orc 8 human submarine slot
1432 is at x 88 natively versus x 90 in Java, and its route-publication family
remains paused pending a new discriminator. Expansion Human 12's fixture-333
wood route is independently paused pending a discriminator for its global
route-buffer state. The earliest unpaused fleet finding is now expansion Orc
12 at fixture 374, followed by expansion Human 10 at 375, expansion Orc 11 at
382, Orc 12 at 383, Human 8 at 390, and expansion Human 7 at 395.

## Current release checkpoint — 2026-09-01 (cycle-442 parked direct-return byte)

Accepted cycle-1,800 receipt `d7aceac1` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 38,814, an increase of 78. Orc 8 advances from fixture 364 to
442. The cycle-400 fleet improves from 43 clean / 9 divergent to 44 clean / 8
divergent / 0 failed under receipt `09a8d4c2`. The long receipt is retained at
`.bne-artifacts/runs/d7aceac16f6cc966c7cf297bc1d06c8598009b06eb52204649fd5493794498a2`.
Its manifest has SHA-256
`0bd438ce37f52b6bb5d4b366a316e1f193318b1def8825cc96e13f5225c7b0c1`
and binds dirty engine-input identity `dec13868` at base revision `8750318` to
replayable source capsule
`6b3a865a82d777a03cb27b77457834f78fb5da6bc088dc11fc25e8d6dd00c99a`.

Behavioral delta: when an allied worker occupies a laden land hauler's direct
next square, native can retain the synthesized direct return byte beneath
logical route index twenty. The eighth refusal parks the cursor without
erasing that byte, serves its complete fourteen-visit Move band, and reopens
the stored byte on the timer-one resource-action wake before asking for a new
route. Java now carries explicit, serialized provenance only for that
occupied-empty-depot fallback. Ordinary pathfinder-authored direct routes still
park and redraw normally. The rule contains no mission, map, coordinate,
fixture, cycle, faction, route-length, or unit-ID branch.

Proof delta: Orc 8 peasant native slot 1494 / Java 106 is laden at `(123,86)`
with depot goal `(123,97)`. Native route index twenty retains south byte `04`
at `unit+0x30` from fixtures 304 through 319 while refusal generation eight
counts Move 15..1. Fixture 320 changes the cursor from twenty to one and
commits south without any route-buffer write. Only after that byte settles
does fixture 342 publish fresh `[SW,S,S,SE]`; the visible positions are
`(123,87)`, `(122,88)`, and `(122,89)`. Java formerly replanned
`[S,SW,SW,SE,E]` at the wake and reached x 121 on the third step, producing the
fixture-364 mismatch. Orc 12 is the held-out discriminator: its ordinary
pathfinder-authored one-byte return route must redraw after the refusal band;
gating preserves that case's accepted fixture-383 frontier.

Efficacy receipt
`.bne-test-efficacy/c364-orc8-direct-return-parked-byte/runs/167697a075477f51b65bac4aa3b12c6a8bf1e608d2422fa8cfae766f9e8d971d`
proves the focused assertion executes and fails on `8750318`, then executes
and passes on the candidate. All 84 selected mine-exit, resource-return,
convoy, tanker, and route-tail controls pass, as do all 47 `SaveGameTest`
checks. Both fixed 52-case gates pass. The ordinary executable next-level gate
exits zero after 209 Python checks (four skipped) and 97 engine/desktop checks;
its command worklist remains 11 comparable scenarios (6 exact / 5 divergent)
without regression or infrastructure failure. `--require-certified` remains
incomplete on the documented producer lanes, and remote AI discovery still
stops at strict SSH verification of the changed `i9beef` host key, which was
not modified.

The shared frontier remains fixture 312: expansion Orc 8 human submarine slot
1432 is at x 88 natively versus x 90 in Java, and its route-publication family
remains paused pending a new discriminator. Expansion Human 12's fixture-333
wood route is independently paused pending a discriminator for its global
route-buffer state. The earliest unpaused fleet finding is now expansion Orc
11 at fixture 371, followed by expansion Orc 12 at 374, expansion Human 10 at
375, Orc 12 at 383, Human 8 at 390, and expansion Human 7 at 395. Orc 8's new
fixture-442 finding is human oil tanker slot 1479 at `(82,88)` natively versus
`(84,90)` in Java.

## Current release checkpoint — 2026-09-01 (cycle-371 naval HitUnit south edge)

Accepted cycle-1,800 receipt `fcd13a5c` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 38,736, an increase of ten. Expansion Orc 11 advances from
fixture 361 to 371. The cycle-400 fleet remains 43 clean / 9 divergent / 0
failed under receipt `a10e7ab1`. The long receipt is retained at
`.bne-artifacts/runs/fcd13a5ca5656fbfcaec1d038687e1c8eb292e183d620563291e2eebb7215ae9`.
Its manifest has SHA-256
`d17d32c63840ada7f090d8fb8aa360797026cec9e88198a90fbaf07e71ce28f6`
and binds dirty engine-input identity `1a709626` at base revision `63a493b` to
replayable source capsule
`f429f9eef19b12be7b17557cdf745302355d2cbd543a3fdbb5cfd40d947bbba7`.

Behavioral delta: a person naval `HitUnit` response selects idle brothers with
the native unit-cache rectangle, not symmetric distance between ship
footprints. The struck hull's top-left coordinate and type dimensions form an
inclusive four-tile rectangle. The cache lookup extends its north search edge
by three rows but does not extend the south edge, and candidate top-left
coordinates determine membership. The rule contains no mission, map,
coordinate, fixture, cycle, faction, or unit-ID branch.

Proof delta: on expansion Orc 11 fixture 360, human destroyer slot 1519 / Java
81 shells orc destroyer slot 1493 / Java 107 at `(12,44)`, reducing it from 100
to 77 HP. The native helper rectangle spans y=37..49, so idle destroyer slot
1485 / Java 115 at `(8,50)` receives no next Attack and remains raw Still
`2/60` through its fixture-361 action marker. Java formerly priced four empty
tiles between hull footprints, banked Java 81, and promoted Attack at 361.
Disassembly of pinned executable SHA-256 `b0e914a9` anchors rectangle formation
at `0x0040aaa2`--`0x0040aaef`, the north-only cache extension at `0x0040a2b4`,
and candidate-X filtering at `0x0040ab73`--`0x0040ab8e`. The earlier fixture-132
impact supplies both held-out controls: slot 1525 at `(6,36)` is selected around
struck slot 1506 at `(10,42)`, while slot 1485 at `(8,50)` remains outside.

Efficacy receipt
`.bne-test-efficacy/c361-xorc11-naval-hit-south-edge/runs/5bd28304d4e1b4293043a4e8b609804c436cd3f2b5430824da1b79b0ea46d7bb`
proves the focused assertion executes and fails on `63a493b`, then executes and
passes on the candidate. All 93 selected direct-hit, help-response, naval, and
real-data controls pass. One exploratory ranged-retarget test is independently
red on both baseline and candidate and is not green evidence. Both fixed
52-case gates pass. The ordinary executable next-level gate exits zero after
209 Python checks (four skipped) and 96 engine/desktop checks; its command
worklist remains 11 comparable scenarios (6 exact / 5 divergent) without
regression or infrastructure failure. `--require-certified` remains incomplete
on the documented producer lanes, and remote AI discovery still stops at
strict SSH verification of the changed `i9beef` host key, which was not
modified.

The shared frontier remains fixture 312: expansion Orc 8 human submarine slot
1432 is at x 88 natively versus x 90 in Java, and its route-publication family
remains paused pending a new discriminator. Expansion Human 12's fixture-333
wood route is independently paused pending a discriminator for its global
route-buffer state. The earliest unpaused fleet finding is now Orc 8 at fixture
364, followed by expansion Orc 11 at 371, expansion Orc 12 at 374, expansion
Human 10 at 375, Orc 12 at 383, and Human 8 at 390. Expansion Orc 11's new
finding is gryphon rider slot 1589 raw Patrol natively versus Attack in Java.

## Prior release checkpoint — 2026-09-01 (cycle-390 resource-hit restore idle ownership)

Accepted cycle-1,800 receipt `d253a1b7` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 38,726, an increase of 32. Human 8 advances from fixture 358
to 390. The cycle-400 fleet remains 43 clean / 9 divergent / 0 failed under
receipt `893ad4b8`. The long receipt is retained at
`.bne-artifacts/runs/d253a1b768d95f41829379a13c9b67e5a1d96d07baaf42f0c4d12ecf532c11e3`.
Its manifest has SHA-256
`6ec39180537a4492a5351851ca2400df283e8dc5d02300c567a2eddcbcb1b49f`
and binds dirty engine-input identity `6810afb8` at base revision `edd866d` to
replayable source capsule
`c6898527e789353806e51cef5c441ee259ab1a052d7ede897307755472ba3a50`.

Behavioral delta: the last call of a temporary resource-hit Move still owns
the common active-order idle callback before `RestoreOrder`. It pays that
worker's asynchronous idle draw before restoring the saved resource action,
whether the resumed depot route will be empty or nonempty. A synchronous
empty-route retry receives that paid ownership and does not draw twice. The
restored empty action-24 cursor retains explicit, serialized provenance while
it repeats its three-call idle band; that provenance keeps it out of the
ordinary allied-blocker refusal constructor until a route succeeds. Fresh
empty return routes still construct and serve their normal refusal ladder.
The rule contains no mission, map, coordinate, fixture, cycle, faction, or
unit-ID branch.

Proof delta: Human 8 peasant native slot 1536 / Java unit 64 finishes the
retained resource-hit body on fixture 350. Native's final common-idle callback
consumes result 30517 from asynchronous seed `0x026dfd01`, after which the
restored action 24 repeats its empty-route idle callback on fixtures 353, 356,
and 359. Java formerly restored first and handed 30517 to unit 55. With the
callback attributed to slot 1536, fixture 358 exactly matches native's seven
unit-owned draws: critter slot 1492 / Java 108 consumes choice 30247 and
direction 14474, authors `(38,84)`, and the stream ends at seed `0x45df3775`.
Independent free-restore controls preserve native end seeds `0x535014dc` at
fixture 301 and `0xa9ecb6ac` at 316, proving the resumed empty route is not
double charged. The ordinary Orc 8 peasant-1504 mine queue remains the
negative control and serves its paid refusal hold through fixture 254.

Efficacy receipt
`.bne-test-efficacy/c350-human8-resource-hit-restore-marker/runs/57353c70cbe2caf59f83d340a67682aa54c0288c8174d6f065011ac501b6e292`
proves the focused assertion executes and fails on `edd866d`, then executes
and passes on the candidate. All 39 selected resource-hit, return-route,
movement, and mine-queue controls pass. Both fixed 52-case gates pass. The
ordinary executable next-level gate exits zero after 209 Python checks (four
skipped) and 96 engine/desktop checks; its command worklist remains 11
comparable scenarios (6 exact / 5 divergent) without regression or
infrastructure failure. `--require-certified` remains incomplete on the
documented producer lanes, and remote AI discovery still stops at strict SSH
verification of the changed `i9beef` host key, which was not modified.

The shared frontier remains fixture 312: expansion Orc 8 human submarine slot
1432 is at x 88 natively versus x 90 in Java, and its route-publication family
remains paused pending a new discriminator. Expansion Human 12's fixture-333
wood route is independently paused pending a discriminator for its global
route-buffer state. The earliest unpaused fleet finding is now expansion Orc
11 at fixture 361, followed by Orc 8 at 364, expansion Orc 12 at 374,
expansion Human 10 at 375, Orc 12 at 383, and Human 8 at 390. Human 8's new
finding is attack peasant slot 1526 at `(78,63)` natively versus `(77,62)` in
Java.

## Prior release checkpoint — 2026-08-31 (cycle-512 active-chase fresh route ownership)

Accepted cycle-1,800 receipt `76b22cee` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 38,694, an increase of 160. Expansion Human 2 advances from
fixture 352 to 512. The cycle-400 fleet improves from 42 clean / 10 divergent
to 43 clean / 9 divergent / 0 failed under receipt `49ae8c95`. The long
receipt is retained at
`.bne-artifacts/runs/76b22ceec21fcb6a1230578f3c75aeb12d51aff65ae5cfbcf88e61c08038c028`.
Its manifest has SHA-256
`5a2c2155be7ae6a13cd959ae3fac0f4ce65fbe75c2148f975eeb5743675abbd3`
and binds dirty engine-input identity `50160af7` at base revision `5b33f3c` to
replayable source capsule
`8b2c0ab2cbb8292329979d25399071c1911040170f9797345906969af04f8ed4`.

Behavioral delta: the equal-cost current-face preference belongs to standing
offered-hit acquisition and queued-Attack promotion. When a unit is already
chasing the same live offered target and its prior route is exhausted, native
`NewPath` owns the first byte of the fresh route; the spent combat face does
not overwrite it merely because it has equal Chebyshev cost. Destination-arm,
naval-hit, standing acquisition, queued-promotion, cached-tail, and
collision-refusal behavior retain their established paths. The implementation
contains no mission, map, coordinate, fixture, cycle, faction, unit-ID, or
route-length branch.

Proof delta: expansion Human 2 ogre native slot 1549 / Java unit 51 exhausts
its nine-byte offered-target route at `(60,66)` before fixture 352. Native
publishes `SW,SW,S` toward the guard tower and consumes south-west, reaching
`(59,67)`; Java formerly replaced that fresh head with the old west combat
face because both headings have equal tile-distance cost. Retained Java path,
movement, and causal traces prove the planner returned `SW,SW,S` immediately
before the face helper changed the committed heading. The corrected case is
exact through fixture 511; its new fixture-512 split is an independent death
knight slot 1557 raw Attack natively versus Still in Java.

Efficacy receipt
`.bne-test-efficacy/c352-xhuman2-fresh-route-head/runs/d7f7d7b982a76c86a2fe34bd0e977b590f0054dbabd6fecfebfc4816298d482e`
proves the focused assertion fails on `5b33f3c` and passes on the candidate.
All 28 selected route-refill, face-ownership, destination-arm, tail-wrap, and
real-data controls pass. Four exploratory chase-residual assertions fail with
identical expected/actual values on baseline and candidate and are therefore
baseline-equivalent, not green evidence. Both fixed 52-case gates pass. The
ordinary executable next-level gate exits zero after 209 Python checks (four
skipped) and 96 engine/desktop checks; its command worklist remains 11
comparable scenarios (6 exact / 5 divergent) without regression or
infrastructure failure. `--require-certified` remains incomplete on the
documented producer lanes, and remote AI discovery still stops at strict SSH
verification of the changed `i9beef` host key, which was not modified.

The shared frontier remains fixture 312: expansion Orc 8 human submarine slot
1432 is at x 88 natively versus x 90 in Java, and its route-publication family
remains paused pending a new discriminator. Expansion Human 12's fixture-333
wood route is independently paused pending a discriminator for its global
route-buffer state. The earliest unpaused fleet finding is now Human 8 at
fixture 358, followed by expansion Orc 11 at 361, Orc 8 at 364, expansion Orc
12 at 374, expansion Human 10 at 375, Orc 12 at 383, and expansion Human 7 at
395. Expansion Human 2's new finding is death knight slot 1557 raw Attack
natively versus Still in Java at fixture 512.

## Prior release checkpoint — 2026-08-31 (cycle-474 paid Attack-tail hostile settle)

Accepted cycle-1,800 receipt `a097f059` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 38,534, an increase of 124. Expansion Human 4 advances from
fixture 350 to 474. The cycle-400 fleet improves from 41 clean / 11 divergent
to 42 clean / 10 divergent / 0 failed under receipt `f6a6d84e`. The long
receipt is retained at
`.bne-artifacts/runs/a097f05940162f58d555eeec31159750dbf11d0abe4163f0b70b3ccbe0df6ec8`.
Its manifest has SHA-256
`acfc16dd356ab366702e0ee52e6e4f3172b454faf0f36399a5304cc446b3cf65`
and binds dirty engine-input identity `95fa8856` at base revision `320bd98` to
replayable source capsule
`2ab6e7dc719673c4cddc87b2c3e5ce513280501b63370be9ed69ee6249f782b6`.

Behavioral delta: when a paid land-melee Attack residual settles with one
cached heading left, the old mobile quarry remains out of range, and the
preferred adjacent hostile occupies that blocked heading, the callback belongs
to the hostile target decision rather than generic old-goal replanning. The
engine parks the consumed route, installs the adjacent replacement, opens its
fresh Attack constructor, and charges exactly one synchronized table-0x27
debit. Collision-owned, building, ranged, allied-blocker, and unoccupied tails
retain their existing paths. The implementation contains no mission, map,
coordinate, fixture, cycle, faction, unit-ID, or original-route-length branch.

Proof delta: expansion Human 4 footman native slot 1484 / Java unit 116 owns
the retained `SE,NE` tail. Its south-east residual lands on fixture 350 while
the preferred south axethrower occupies the cached north-east cell. Native
changes the order target, parks route index twenty, exposes Attack `2539/3`,
and advances synchronized seed `8f3615c1 -> 48ee4166` in that callback; Java
formerly kept the north axethrower, replanned `NE,N`, and paid the same draw
two fixtures later. Authenticated local Branch Witness capture
`5afefad9fb2e75fe665ec3251ad4c401f5308ae04e6fa144b6c0e5d86f6e9a96`
binds the slot-1484 state byte to writer `0x004234db`, with register probes
proving the watched unit base and caller `FUN_004234b0`'s draw attribution.

Efficacy receipt
`.bne-test-efficacy/c350-xhuman4-paid-tail-hostile-settle/runs/0a4f0956b3565d9d63c8e0310bf9676019933576b81f17886f621ec4b05c072f`
proves the focused assertion fails on `320bd98` and passes on the candidate.
The complete expansion-Human-4 real-data and melee-sync-loop test families
pass. The ordinary executable next-level gate exits zero after 209 Python
checks (four skipped) and 96 engine/desktop checks; its command worklist
remains 11 comparable scenarios (6 exact / 5 divergent) without regression or
infrastructure failure. `--require-certified` remains incomplete on the
documented producer lanes, and remote AI discovery still stops at strict SSH
verification of the changed `i9beef` host key, which was not modified.

The shared frontier remains fixture 312: expansion Orc 8 human submarine slot
1432 is at x 88 natively versus x 90 in Java, and its route-publication family
remains paused pending a new discriminator. Expansion Human 12's fixture-333
wood route is independently paused pending a discriminator for its global
route-buffer state. The earliest unpaused fleet finding is expansion Human 2
at fixture 352, followed by Human 8 at 358, expansion Orc 11 at 361, Orc 8 at
364, expansion Orc 12 at 374, expansion Human 10 at 375, and expansion Human 6
at 525. Expansion Human 4's next finding is critter slot 1593 raw Move natively
versus Still in Java at fixture 474.

## Prior release checkpoint — 2026-08-31 (cycle-358 retained resource-hit Move restart)

Accepted cycle-1,800 receipt `c46fb992` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 38,410, an increase of 11. Human 8 advances from fixture 347
to 358. The cycle-400 fleet remains 41 clean / 11 divergent / 0 failed under
receipt `534a1ed9`. The long receipt is retained at
`.bne-artifacts/runs/c46fb992f7b86d019b5c2fab7fa605a541533ee27dd4726148a79bf6085e9e57`.
Its manifest has SHA-256
`927a50b258d31f4839ff6b0dc3a624c4ed30f5f18393c464ef3485dcbe1d44fc`
and binds dirty engine-input identity `7dc7d69b` at base revision `e5db4fc` to
replayable source capsule
`6128005dcf59c8fbaca9f5ca09f2d5c86272783e10e6cafd30f2cdf6a835fa20`.

Behavioral delta: when a non-aggressive resource worker's temporary hit-flee
Move reaches its point while another live offered hit remains, the saved
Harvest order still owns the reaction. Native runs that worker's common
active-order idle callback, then re-enters the resource-hit constructor rather
than clearing the offer and restoring Harvest immediately. The restart keeps
raw Move, consumes the callback draw plus the two escape-point draws, and
restores the saved order after the new three-call animation body. Ordinary
Move completion and the first resource-hit reaction retain their existing
paths. The implementation contains no mission, map, coordinate, fixture,
cycle, faction, or unit-ID branch.

Proof delta: Human 8 peasant native slot 1536 / Java 64 retains the fixture-331
blow while its first escape stride drains. At fixture 346 it is Move with a
saved Harvest order and a live offered attacker. Native fixture 347 remains
Move, authors point `(89,60)`, opens Still sequence 2595 with timer 3, and
restores Harvest at fixture 350; Java formerly took generic Move completion
and became Still. Static analysis anchors the active-order callback draw at
`0x0040ad53`, restart call at `0x0040a61f`, point draws at `0x0040a750` and
`0x0040a77f`, and `GiveOrder` at `0x0040a80c`. Two authenticated local Branch
Witness captures prove the callback returns `0x3290`, the constructor then
draws `0x6ddf` / `0x6d76`, and writer `0x0045140e` stores the resulting point.
The earlier Java path was exactly one asynchronous draw behind and authored
`(82,61)`.

Efficacy receipt
`.bne-test-efficacy/c347-human8-retained-hit-restart/runs/6508a673ec1dee554c560d163b940343bc5c5888059c419db5e7730a548a33ec`
proves the focused assertion fails on `e5db4fc` and passes on the candidate.
All 32 resource-hit and moving-quarry family tests pass. The Human 1
stand-and-fight control fails identically on baseline and candidate and is
therefore baseline-equivalent, not green evidence. Both fixed 52-case gates
pass. The ordinary executable next-level gate exits zero after 209 Python
checks (four skipped) and 96 engine/desktop checks; its command worklist
remains 11 comparable scenarios (6 exact / 5 divergent) without regression or
infrastructure failure. `--require-certified` remains incomplete on the
documented producer lanes, and remote AI discovery still stops at strict SSH
verification of the changed `i9beef` host key, which was not modified.

The shared frontier remains fixture 312: expansion Orc 8 human submarine slot
1432 is at x 88 natively versus x 90 in Java, and its route-publication family
remains paused pending a new discriminator. Expansion Human 12's fixture-333
wood route is independently paused pending a discriminator for its global
route-buffer state. The earliest unpaused fleet finding is now expansion Human
4 at fixture 350, followed by expansion Human 2 at 352, Human 8 at 358,
expansion Orc 11 at 361, Orc 8 at 364, expansion Orc 12 at 374, expansion
Human 10 at 375, Orc 12 at 383, and expansion Human 6 at 525. Human 8's new
finding is critter slot 1492 raw Move natively versus Still in Java.

## Prior release checkpoint — 2026-08-31 (cycle-525 component-distance FindDeposit)

Accepted cycle-1,800 receipt `102fc793` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 38,399, an increase of 181. Expansion Human 6 advances from
fixture 344 to 525. The cycle-400 fleet improves from 40 clean / 12 divergent
to 41 clean / 11 divergent / 0 failed under receipt `c3ab85cd`. The long
receipt is retained at
`.bne-artifacts/runs/102fc79317201ae5da6fb6ff51b2957b14d026483f696d2b2c0b5ce92637ffe2`.
It binds dirty engine-input identity `d1534b11` at base revision `dcc1a95` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and replayable source capsule
`f8b84ad1f2c7c6f14c8253dc0c89ea60a7333042712230c1934b25a26e752153`.

Behavioral delta: `FindDeposit` at `0x00438770` filters completed friendly
depots by the worker's fixed terrain component, then walks the owner's unit
roster and minimizes native footprint-aware Chebyshev distance
`0x00416b10`, replacing the incumbent on equality. A tanker takes the naval
type-flag arm at `0x0043877d`; candidate footprint/component membership is
tested by `0x00416980`. The selector does not call `UnitReachable`, compare A*
route length, or use ChonkCraft's Euclidean footprint distance. Land workers
use the same native distance after comparing component words at the worker and
depot origins. The implementation uses ownership, resource storage,
construction/alive state, movement class, fixed terrain connectivity, native
distance, and owner-roster order; it contains no mission, map, coordinate,
fixture, cycle, faction, unit-ID, or route-length branch.

Proof delta: expansion Human 6 tanker slot 1516 / Java 84 finishes its first
load inside oil platform `(49,67)`. Native selects shipyard slot 1519 at
`(40,51)` rather than refinery slot 1522 at `(49,47)`, carries that weak goal
through the synchronized ready window, and commits north-west from `(48,68)`
to `(46,66)` on fixture 344. Java formerly refined by walked route cost,
selected refinery Java 78, and committed north to `(48,66)`. Static analysis
anchors the naval roster walk at `0x004387c2`, component-footprint call at
`0x004387eb`, distance calls at `0x00438803` / `0x0043880d`, and strict-
incumbent comparison at `0x00438815`. Expansion Orc 8 remains the positive
refinery control because its refinery is genuinely nearer under
`0x00416b10`; Human 7 independently retains its eastern refinery. The
corrected case is exact through fixture 524; its new fixture-525 split is an
independent peon slot 1568 position mismatch, native `(20,15)` versus Java
`(19,14)`.

Efficacy receipt
`.bne-test-efficacy/c525-xhuman6-native-find-deposit/runs/ef876c9009910c759ca5177737b1bcc982f68f955bb100993b6d50f14f383479`
proves the focused platform-exit regression assertion-fails on `dcc1a95` and
passes on the candidate. All 56 in-scope depot, return-goods, harvest, oil,
and save-boundary family tests pass. An exploratory broader selection found
two unrelated `DropOutTest` failures which reproduce identically on
`dcc1a95`; they are baseline-equivalent, not accepted as green evidence. Both
fixed 52-case gates pass. The ordinary executable next-level gate exits zero
after 209 Python checks (four skipped) and 96 engine/desktop checks; its
command worklist remains 11 comparable scenarios (6 exact / 5 divergent)
without regression or infrastructure failure. `--require-certified` remains
incomplete on the documented producer lanes, and remote AI discovery still
stops at strict SSH verification of the changed `i9beef` host key, which was
not modified.

The shared frontier remains fixture 312: expansion Orc 8 human submarine slot
1432 is at x 88 natively versus x 90 in Java, and its route-publication family
remains paused pending a new discriminator. Expansion Human 12's fixture-333
wood route is independently paused pending a discriminator for its global
route-buffer state. The earliest unpaused fleet finding is now Human 8 at
fixture 347, followed by expansion Human 4 at 350, expansion Human 2 at 352,
expansion Orc 11 at 361, Orc 8 at 364, expansion Orc 12 at 374, expansion
Human 10 at 375, Orc 12 at 383, and expansion Human 6 at 525.

## Current release checkpoint — 2026-08-31 (cycle-383 saturated depot-tail route retirement)

Accepted cycle-1,800 receipt `6b34baa6` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 38,218, an increase of forty-one. Orc 12 advances from fixture
342 to 383. The cycle-400 fleet remains 40 clean / 12 divergent / 0 failed
under receipt `d30c534a`. The long receipt is retained at
`.bne-artifacts/runs/6b34baa61d29fdcbdbd4ee32787af48f5a96a98f9392db9baac6db3982bfc955`.
It binds dirty engine-input identity `639d51c0` at base revision `004a72c` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and replayable source capsule
`d14ec230e2a68ab69dbced1cb758832127ed83d4ee488ef133b4298a1e1dd31b`.

Behavioral delta: `FUN_004379e0` increments the packed collision/refusal high
nibble before testing its saturation boundary. A laden land return on the
depot skirt may therefore restore a cached nonduplicate route only while the
post-increment generation remains below eight. At generation eight and above,
retail parks route index twenty and pays the complete refusal band; the wake
must query a fresh route instead of reinstalling the rejected suffix. A direct
ray may redraw the same visible heading, but the route ownership is still new.
Farther depot returns, unsaturated near returns, consumed duplicate-cardinal
tails, non-laden movement, sea movement, and unrelated refusal callers retain
their established handling. The rule uses return state, carried resources,
movement class, depot distance, route state, and the native refusal generation;
it contains no mission, map, coordinate, fixture, cycle, faction, unit-ID, or
route-length branch.

Proof delta: Orc 12 peon slot 1507 / Java 93 settles at `(58,49)` on fixture
326 after consuming four elements of its five-heading return route, leaving
south at the head with refusal eight. On fixture 327 native advances the raw
high nibble `0x80` to `0x90`, parks the cursor at index twenty, and leaves no
logical route while the full band runs through fixture 341. Java formerly
restored south on that visit and consumed it on fixture 342, reaching
`(58,50)`. Native's wake sees the parked cursor, writes `[SE,E]`, and commits
southeast through the common movement writer at `0x0043798b`, reaching
`(59,50)` with east retained. Static analysis anchors the increment at
`0x00437a0d`, the post-increment comparison with `0x8000` at `0x00437ab4`,
and the route park through `0x00450ad0`; the authenticated Branch Witness
localizes the visible fixture-342 write. The corrected case is exact through
fixture 382; its new fixture-383 split is an independent peon slot 1502 Y
position mismatch, native 51 versus Java 50.

Efficacy receipt
`.bne-test-efficacy/c383-orc12-saturated-depot-tail/runs/d62a36784a391c74829a31775aab4d572c8e1ae05cc15717a5a62d711d64359b`
proves the focused fixture-326..342 regression assertion-fails on `004a72c`
and passes on the candidate. All 74 broader real-data loaded-return and refusal
tests pass. The 36-test synthetic `BattleNetResourceApproachTest` has the same
29 passes and seven known failures on both `004a72c` and the candidate, so it
is recorded as baseline-equivalent evidence rather than a green acceptance
claim. Both fixed 52-case gates pass. The ordinary executable next-level gate
exits zero after 209 Python checks (four skipped) and 96 engine/desktop checks;
its command worklist remains 11 comparable scenarios (6 exact / 5 divergent)
without regression or infrastructure failure. `--require-certified` remains
incomplete on the documented producer lanes, and remote AI discovery still
stops at strict SSH verification of the changed `i9beef` host key, which was
not modified.

The shared frontier remains fixture 312: expansion Orc 8 human submarine slot
1432 is at x 88 natively versus x 90 in Java, and its route-publication family
remains paused pending a new discriminator. Expansion Human 12's fixture-333
wood route is independently paused pending a discriminator for its global
route-buffer state. The earliest unpaused fleet finding is expansion Human 6
at fixture 344, followed by Human 8 at 347, expansion Human 4 at 350,
expansion Human 2 at 352, expansion Orc 11 at 361, Orc 8 at 364, expansion
Orc 12 at 374, expansion Human 10 at 375, and Orc 12 at 383.

## Prior release checkpoint — 2026-08-31 (cycle-361 naval HitUnit arrival rescan)

Accepted cycle-1,800 receipt `380e37dc` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 38,177, an increase of twenty-four. Expansion Orc 11 advances
from fixture 337 to 361. The cycle-400 fleet remains 40 clean / 12 divergent /
0 failed under receipt `74576ae6`. The long receipt is retained at
`.bne-artifacts/runs/380e37dc59147106f19ca84fa4ac4e3631f448c29fd1b52c0d567f5f0bbeeb93`.
It binds dirty engine-input identity `d66cbdba` at base revision `0431081` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and replayable source capsule
`a446643a67b789124abd5ac57a67c1261ba1d9af3445f0338986cf83543c8457`.

Behavioral delta: a person-controlled naval `HitUnit` response retains its
offered source while its committed route remains outside weapon range. When
the final residual settles in range, retail performs a fresh, null-seeded
hostile scan before installing the cold Attack constructor; equal-score ships
therefore resolve by persistent screen-Y order instead of incumbent ownership.
Damage which lands while an Attack OP0 resume hold is already active does not
arm another hold, so the paid broadside period expires normally. Ordinary
land responses, non-person naval units, out-of-range arrivals, cold attacks
without `HitUnit` provenance, and damage before the first committed OP0 hold
retain their established behavior. The implementation uses response
provenance, movement and missile class, person ownership, ordinary hostile
ranking, live range, and active hold state; it contains no mission, map,
coordinate, fixture, cycle, faction, or unit-ID branch.

Proof delta: expansion Orc 11 destroyer slot 1521 / Java 79 keeps its offered
destroyer slot 1542 / Java 58 through the final Move residual on fixture 205.
On fixture 206 at `(8,34)`, native rescans two equal-score destroyers at
`(6,30)` and `(10,30)`, chooses slot 1558 / Java 42 first in screen-Y order,
and opens Attack 3266 with construction timers 3,2,1. Its 118-count OP0 hold
begins on fixture 209. A crossing cannon pulse reduces the responder to 86 HP
on fixture 248 but does not extend that already-paid hold; windup follows on
fixture 327, the responder constructs its broadside on 328, and the shot lands
for twenty-six damage on Java 42 at fixture 337. Java formerly retained Java
58 until a late fixture-327 scan and produced no shot. The corrected case is
exact through fixture 360; its new fixture-361 split is an independent
destroyer order mismatch, native Still versus Java Attack.

Efficacy receipt
`.bne-test-efficacy/c361-naval-hit-arrival-rescan/runs/01fecf17bb6bd6c1533b22e660ed8806e30fe72eaa1ecc847f487374f1c9c039`
proves the focused fixture-205..337 regression assertion-fails on `0431081`
and passes on the candidate. All 35 broader naval response, ranged hold,
moving-quarry, and melee synchronization family tests pass, as do both fixed
52-case gates. The ordinary executable next-level gate exits zero after 209
Python checks and 96 engine/desktop checks; its command worklist remains 11
comparable scenarios (6 exact / 5 divergent) without regression or
infrastructure failure. `--require-certified` remains incomplete on the
documented producer lanes, and remote AI discovery still stops at strict SSH
verification of the changed `i9beef` host key, which was not modified.

The shared frontier remains fixture 312: expansion Orc 8 human submarine slot
1432 is at x 88 natively versus x 90 in Java, and its route-publication family
remains paused pending a new discriminator. Expansion Human 12's fixture-333
wood route is independently paused pending a discriminator for its global
route-buffer state. The earliest unpaused fleet finding is Orc 12 at fixture
342, followed by expansion Human 6 at 344, Human 8 at 347, expansion Human 4
at 350, expansion Human 2 at 352, expansion Orc 11 at 361, Orc 8 at 364, and
expansion Orc 12 at 374.

## Prior release checkpoint — 2026-08-31 (cycle-350 paid Attack-tail collision generations)

Accepted cycle-1,800 receipt `4f674ca5` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 38,153, an increase of thirteen. Expansion Human 4 advances
from fixture 337 to 350. The cycle-400 fleet remains 40 clean / 12 divergent /
0 failed under receipt `fc92b70a`. The long receipt is retained at
`.bne-artifacts/runs/4f674ca50648a1322e2fbd7280a6056c3f25a7f761983945b6bac4b71f420cd2`.
It binds dirty engine-input identity `68bad264` at base revision `995b0cf` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and replayable source capsule
`5952f0c5bbc162aa25a701ff291074d9404a00342fecc449550ddd82b5cab114`.

Behavioral delta: a melee Attack tail now keeps its route-selected quarry in
explicit route provenance instead of projecting it through COrder_Attack's
offered-target slot. A cached suffix which completes its first collision band
advances the packed collision generation, parks at route index twenty, and
continues the rejected wall face with every hostile formation body still hard.
After that continued wall commits its first heading, a newly blocked suffix
advances the next generation and parks again without spending the diagonal's
free cardinal component. That later generation has exhausted the retained
wall face, so its next cold direct writer stops at the first free square which
restores weapon range instead of retaining a target-tile suffix. Ordinary live
offers, first-generation wall continuations, farther cold probes, ranged
chasers, buildings, and unrelated target routes retain their established
behavior. The rule uses Attack-tail provenance, target identity, collision and
refusal generation, route progress, movement/combat class, occupancy, and
weapon range; it contains no mission, map, coordinate, fixture, cycle, faction,
unit-ID, or route-length branch.

Proof delta: expansion Human 4 footman slot 1518 / Java 82 retains its paid
north-east byte after committing east onto `(74,61)` on fixture 281. Native
holds that byte through the complete Move 15..1 band, advances collision one
to two, and parks route index twenty on fixture 312. Fixture 313 keeps the real
quarry point `(76,61)`, writes exact route `N,NE,E,E,SE,SW`, and consumes north
onto `(74,60)`; the hard hostile-body wall view, not a shifted goal, is what
produces all six bytes. After the north stride settles, fixture 329 advances
collision two to three and parks the blocked north-east suffix without moving.
Fixture 330 writes only south-east, consumes it onto `(75,61)`, and leaves no
logical suffix. Java formerly stepped north on fixture 329. The corrected case
is exact through fixture 349; its new fixture-350 split is an independent
synchronized-RNG draw mismatch. Expansion Human 10's paid wake, all expansion
Human 12 collision-refill witnesses, and Human 13's offered-target/dying-tail
witnesses are held-out negatives.

Efficacy receipt
`.bne-test-efficacy/c350-paid-tail-generation-park/runs/db8797bee717a0d6ac92177fe8d611de5c19f072591a9ce54a0838a546a6d9eb`
proves the focused fixture-281..330 regression assertion-fails on `995b0cf`
and passes on the candidate. All 39 broader moving-quarry and paid-tail family
tests pass, as do both fixed 52-case gates. The ordinary executable next-level
gate exits zero after 209 Python checks and 96 engine/desktop checks; its
command worklist remains 11 comparable scenarios (6 exact / 5 divergent)
without regression or infrastructure failure. `--require-certified` remains
incomplete on the documented producer lanes, and remote AI discovery still
stops at strict SSH verification of the changed `i9beef` host key, which was
not modified.

The shared frontier remains fixture 312: expansion Orc 8 human submarine slot
1432 is at x 88 natively versus x 90 in Java, and its route-publication family
remains paused pending a new discriminator. Expansion Human 12's fixture-333
wood route is independently paused pending a discriminator for its global
route-buffer state. The earliest unpaused fleet finding is expansion Orc 11
at fixture 337, followed by Orc 12 at 342, expansion Human 6 at 344, Human 8
at 347, expansion Human 4 at 350, and expansion Human 2 at 352.

## Prior release checkpoint — 2026-08-31 (cycle-331 first-collision occupied-tail construction)

Accepted cycle-1,800 receipt `b17f5029` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 38,140, an increase of nineteen. Human 8 advances from fixture
328 to 347. The cycle-400 fleet remains 40 clean / 12 divergent / 0 failed
under receipt `a539fb59`. The long receipt is retained at
`.bne-artifacts/runs/b17f502905e27ad54a2652ff8a3431588f15b538fe923ba75b6396deeb3bcda8`.
It binds dirty engine-input identity `b57c1bda` at base revision `fb4b1bd` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and replayable source capsule
`df4fae70aac3861ac4110c6b821466e2c3ff872d8d6ba0f110cfa9a38ce48cba`.

Behavioral delta: a progressed melee land chase retains its live old quarry
through one Attack constructor when its one-heading residual route owns
exactly one collision, no refusals, and the preferred adjacent replacement is
standing on that cached heading. At timer one the ordinary hostile scan must
still select that occupant; only then does the engine park the residual route,
install the replacement and start its own Attack constructor. Collision-free
tails continue consuming their cached heading immediately, and unrelated
collision generations, ranged chases, buildings, dead targets and
non-occupying replacements retain their existing behavior. The rule uses
movement/combat class, route progress, collision/refusal generation, live
target state, attack range, occupancy and normal target ranking; it contains
no mission, map, coordinate, fixture, cycle, faction or unit-ID branch.

Proof delta: Human 8 attack-peasant slot 1513 / Java 87 is at `(78,62)` on
fixture 291, still targeting peasant Java 67 with a cached northeast heading,
one collision and no refusals. Peasant Java 64 is adjacent on that heading and
is now the preferred quarry. Native fixtures 292..294 keep Java 67 plus the
northeast route through Attack construction 3,2,1. Fixture 295 installs Java
64, parks the route and opens that replacement's Attack constructor; fixture
298 enters the committed body hold and the blow lands on fixture 331. Java
formerly replaced the quarry on fixture 292 and landed on fixture 328. The
corrected case is exact through fixture 346. The same attack-peasant's earlier
fixture-188 march is the negative witness: with no collision it consumes its
cached head immediately. The new fixture-347 split is independent peasant slot
1536 / Java 64, native order Move versus Java Still.

Efficacy receipt
`.bne-test-efficacy/c331-first-collision-occupied-tail/runs/43c565cd8f6fe088baac73588482a92fc1a41775c0f8ebba6880789b3ca0c693`
proves the focused fixture-291..331 regression assertion-fails on `fb4b1bd`
and passes on the candidate. All 17 moving-quarry regressions pass, as do both
fixed 52-case gates. The ordinary executable next-level gate exits zero after
209 Python checks and 96 engine/desktop checks; its command worklist remains
11 comparable scenarios (6 exact / 5 divergent) without regression or
infrastructure failure. `--require-certified` remains incomplete on the
documented producer lanes, and remote AI discovery still stops at strict SSH
verification of the changed `i9beef` host key, which was not modified.

The shared frontier remains fixture 312: expansion Orc 8 human submarine slot
1432 is at x 88 natively versus x 90 in Java, and its route-publication family
remains paused pending a new discriminator. The earliest unpaused fleet
finding is now expansion Human 12 peon slot 1364 at fixture 333, followed by
expansion Human 4 and expansion Orc 11 at 337, Orc 12 at 342, expansion Human
6 at 344, and Human 8 at 347.

Current frontier failure history: expansion Human 12 slot 1364's fixture-332
state proves a paid north-led wood tail advances collision generation one to
two and parks route index twenty with its five stale bytes intact; fixture 333
then replaces it with native route `NE,N,NE,SE,E,SE`. Three implementations
are rejected. Parking without an action-23 redraw starts another full refusal
band. Redrawing toward the intermediate wood-order point commits the correct
visible north-east step but loses the native five-byte tail. Redrawing toward
the original tree produces a one-byte east prefix. Against the exact live
fixture-333 map, retaining the rejected north face returns an empty wall for
both rotations; all eight shared/reversed/retained wall-buffer modes return
east, and hardening each soft-cleared ally independently does not change that
result. Retained evidence is under
`.bne-field-evidence/xhuman12-c333-frontier-packet` and
`.bne-causal-evidence/xhuman12-c333-candidate{1,2,3}`. Do not retry this route
family without a new native discriminator for the global route-buffer state or
action-23 copy boundary.

## Prior release checkpoint — 2026-08-31 (cycle-333 offered-building front-rank ownership)

Accepted cycle-1,800 receipt `0775cabe` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 38,121, an increase of six. Expansion Human 12 advances from
fixture 327 to 333. The cycle-400 fleet remains 40 clean / 12 divergent / 0
failed under receipt `559e3ee8`. The long receipt is retained at
`.bne-artifacts/runs/0775cabebe7b3497a87bd5e423eb9710035b87fa47310c7f2d9d50e993082724`.
It binds dirty engine-input identity `cca00827` at base revision `80d1a35` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and replayable source capsule
`b3589687fd435cd75ad363bde5a778e48d55c2ee320fa83138b6d8f84947f32a`.

Behavioral delta: a first-generation collision/refusal refill which still
carries its prior building in COrder_Attack's offered slot keeps the clean
advancing front rank of its new mobile quarry hard. The retained ally must be
a collision-free melee land chaser already consuming a long route to that
same quarry, closer to it than the router and standing on the router's first
direct-ray square. Same-target allies behind the router, short-route allies,
unrelated quarries, non-building offers and ordinary target routes retain
their existing soft-clear behavior. The rule uses offered-target provenance,
collision/refusal generation, target identity, movement/combat class, live
route progress and relative geometry; it contains no mission, map, coordinate,
fixture, cycle, faction or unit-ID branch.

Proof delta: expansion Human 12 grunt slot 1489 / Java 111 empties its route at
`(37,38)` on fixture 326 while chasing footman Java 123 and retaining its old
guard-tower offer. It owns collision/refusal generation one. Front-rank grunt
1501 / Java 99 is already one step into a collision-free nineteen-heading
route to the same footman and occupies the direct southwest opening at
`(36,39)`. Native keeps that body in the wall view and fixture 327 publishes
and consumes `S,SW`, landing at `(37,39)` with southwest cached. Java formerly
soft-cleared it, published a long wall beginning southeast and landed at
`(38,39)`. The corrected route is byte-exact and the case is exact through
fixture 332. The equally long same-target grunt Java 104 is behind the router,
and the other nearer same-target brothers have short residual routes; they
remain held-out soft-clear witnesses. The new fixture-333 split is independent
peon slot 1364 / Java 236, native `(12,88)` versus Java `(11,89)`.

Efficacy receipt
`.bne-test-efficacy/c333-offered-building-front-rank-wall/runs/59d7302b19e48b712ebef20dda359798450342ed939656844e834ff22aa4b728`
proves the focused fixture-326/327 regression assertion-fails on `80d1a35`
and passes on the candidate. All 47 focused BNE pathfinder, behavior-one
retarget, offered-collision and residual-traffic tests pass, as do both fixed
52-case gates. The ordinary executable next-level gate exits zero after 209
Python checks and 96 engine/desktop checks; its command worklist remains 11
comparable scenarios (6 exact / 5 divergent) without regression or
infrastructure failure. `--require-certified` remains incomplete on the
documented producer lanes, and remote AI discovery still stops at strict SSH
verification of the changed `i9beef` host key, which was not modified.

The shared frontier remains fixture 312: expansion Orc 8 human submarine slot
1432 is at x 88 natively versus x 90 in Java, and its route-publication family
remains paused pending a new discriminator. The earliest unpaused fleet
finding is now Human 8 at fixture 328, followed by expansion Human 12 at 333
and expansion Human 4 plus expansion Orc 11 at 337. Expansion Human 12's next
finding is the peon slot 1364 harvest-route split described above.

## Prior release checkpoint — 2026-08-31 (cycle-327 offered-building wall rejoin)

Accepted cycle-1,800 receipt `b98ba023` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 38,115, an increase of two. Expansion Human 12 advances from
fixture 325 to 327. The cycle-400 fleet remains 40 clean / 12 divergent / 0
failed under receipt `a02b0016`. The long receipt is retained at
`.bne-artifacts/runs/b98ba023fe82def529739a4055afa68dca90cb956343d6f44e4755e0f5dbf704`.
It binds dirty engine-input identity `12455dc3` at base revision `c1935f4` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and replayable source capsule
`746c6fb598f492bf9303ba1454de1f11a9021f18cb107ce5902fc668b0c708d1`.

Behavioral delta: a settled melee replacement whose old building remains in
COrder_Attack's offered slot retains the already-reached axis of its new
mobile target's skirt. On that route family, the direct writer keeps moving
allies hard while the opposite wall face may cross them softly. When the wall
face rejoins the axis, the remaining headings of the original direct ray stay
in the same native route buffer instead of ending the route at the join. A
free direct ray already reaches its goal and gains no suffix. Other target,
point, resource and non-BNE routes retain their existing occupancy and wall-
face conventions. The rule uses offered-target provenance, old/new target
class, settled residual state, axis geometry, live occupancy and the bounded
route buffer; it contains no mission, map, coordinate, fixture, cycle, faction
or unit-ID branch.

Proof delta: expansion Human 12 grunt slot 1520 / Java 80 settles its old west
residual at `(38,44)` while replacing a building quarry with the footman at
`(32,43)`. The offered-building seam preserves skirt row 44 as the path goal.
Moving allied grunt 1508 / Java 92 at `(35,44)` blocks the direct writer but is
soft to wall-follow, so native fixture 325 stores and consumes
`NW,W,W,SW,W,W`, landing first at `(37,43)`. Java formerly softened the ally
for the direct writer too, stored six west bytes and landed at `(37,44)`. The
corrected route is byte-exact, and the case is exact through fixture 326; its
new fixture-327 split is independent grunt slot 1489, native x 37 versus Java
x 38. Grunt 1508's earlier unobstructed offered-axis replacement at fixture
287 remains the negative witness: it retains its six-west direct ray without
inventing a detour or suffix.

Efficacy receipt
`.bne-test-efficacy/c327-offered-axis-wall-rejoin/runs/15ccbe467e76770f17ee85740b14ae4756a8d6c21c7f120965282beafe1610b2`
proves the focused fixture-324/325 regression assertion-fails on `c1935f4`
and passes on the candidate. All 46 focused BNE pathfinder, behavior-one
retarget, offered-collision and residual-traffic tests pass, as do both fixed
52-case gates. The ordinary executable next-level gate exits zero after 209
Python checks and 96 engine/desktop checks; its command worklist remains 11
comparable scenarios (6 exact / 5 divergent) without regression or
infrastructure failure. `--require-certified` remains incomplete on the
documented producer lanes, and remote AI discovery still stops at strict SSH
verification of the changed `i9beef` host key, which was not modified.

The shared frontier remains fixture 312: expansion Orc 8 human submarine slot
1432 is at x 88 natively versus x 90 in Java, and its route-publication family
remains paused pending a new discriminator. The earliest unpaused fleet
finding is now expansion Human 12 grunt slot 1489 at fixture 327, native x 37
versus Java x 38, followed by Human 8 at 328 and expansion Human 4 plus
expansion Orc 11 at 337.

## Prior release checkpoint — 2026-08-31 (cycle-470 small-warship off-lattice endpoint completion)

Accepted cycle-1,800 receipt `ffa9837a` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 38,113, an increase of 161. Expansion Orc 10 advances from
fixture 325 to 470 and expansion Human 5 advances from fixture 419 to 435.
The cycle-400 fleet improves from 39 clean / 13 divergent to 40 clean / 12
divergent / 0 failed under receipt `fe6ff6e4`. The long receipt is retained at
`.bne-artifacts/runs/ffa9837a385fadb3cd85998ac373b443614a8821b4034827c2f75eef5b91c051`.
It binds dirty engine-input identity `19f8f00f` at base revision `7420ece` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and replayable source capsule
`efd7c49219ae375438ad90afa9dd4acb29df1ecd493aa7c77458c95c9c166a61`.

Behavioral delta: an armed non-capital small warship on BNE's doubled movement
lattice can finish beside an odd Patrol endpoint with one generated heading
still cached. When the hull is settled one Chebyshev tile from the goal after
consuming at least one byte of a multi-heading route, native action 5 completes
the endpoint and parks that final overshoot byte at route index twenty before
exchanging the Patrol endpoints. Literal endpoint completion and the existing
single-heading refusal path are unchanged, as are capital ships, flyers,
ordinary land Patrol and non-BNE behavior. The rule uses movement class,
small-warship armament/classification, settled motion, live endpoint distance
and route-consumption state; it contains no mission, map, coordinate, fixture,
cycle, faction or unit-ID branch.

Proof delta: expansion Orc 10 destroyer slot 1484 / Java 116 reaches
`(100,78)` beside odd goal `(99,79)` on fixture 324 after consuming ten bytes
of its eleven-heading route. Native fixture 325 parks the final west byte,
clears the route and turns toward `(120,72)`; Java formerly consumed it to
`(98,78)`. Independently, expansion Human 5 destroyer slot 1553 / Java 47
reaches `(100,88)` beside odd platform-edge goal `(101,87)` on fixture 418.
Native fixture 419 parks its final north byte and turns toward `(102,98)`;
Java formerly overshot to y 86. Expansion Orc 10 is now exact through fixture
469, where an independent critter becomes first, and expansion Human 5 is exact
through fixture 434, where an independent tanker position becomes first.
Existing exact-endpoint, single-heading refusal, capital-ship, flyer and land
Patrol cases remain negative witnesses.

Efficacy receipt
`.bne-test-efficacy/c325-small-warship-off-lattice-overshoot/runs/16819d43af80a8d50cee0df85529966a8c2ef09f31fdc14eb857417fc5c98d3c`
proves the focused expansion Orc 10 fixture-324/325 regression
assertion-fails on `7420ece` and passes on the candidate. All 50 focused naval
Patrol, sea-occupancy, refusal, capital-ship and flyer tests pass, as do both
fixed 52-case gates. The ordinary executable next-level gate exits zero after
209 Python checks and 96 engine/desktop checks; its command worklist remains
11 comparable scenarios (6 exact / 5 divergent) without regression or
infrastructure failure. `--require-certified` remains incomplete on the
documented producer lanes, and remote AI discovery still stops at strict SSH
verification of the changed `i9beef` host key, which was not modified.

The shared frontier remains fixture 312: expansion Orc 8 human submarine slot
1432 is at x 88 natively versus x 90 in Java, and its route-publication family
remains paused pending a new discriminator. The earliest unpaused fleet
finding is expansion Human 12 grunt slot 1520 at fixture 325, native y 43
versus Java y 44, followed by Human 8 at 328 and expansion Human 4 plus
expansion Orc 11 at 337. Expansion Human 5's tanker split at 435, Orc 11's
sapper split at 418 and expansion Orc 10's critter split at 470 remain
available as independent later lanes.

## Prior release checkpoint — 2026-08-31 (cycle-419 naval far-endpoint publication)

Accepted cycle-1,800 receipt `c214e767` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 37,952, an increase of 96. Expansion Human 5 advances from
fixture 323 to 419. The cycle-400 fleet improves from 38 clean / 14 divergent
to 39 clean / 13 divergent / 0 failed under receipt `d60c0f66`. The long
receipt is retained at
`.bne-artifacts/runs/c214e767ee1dc45e01df9c74c8e1850ea828480090451a6aac9fba00bd88626f`.
It binds dirty engine-input identity `ba8b1491` at base revision `a549084` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and replayable source capsule
`3bf5b04abcfd4e6a3891e67ef79e1c8c60ab8680d875f9dc2df8447753293410`.

Behavioral delta: retail publishes the naval action-5 footprint rewrite each
time a stored endpoint becomes the live goal, not only when the ready pass
promotes the opening point. A non-capital ship turning from its near endpoint
toward a coast or building point now runs the established goal-to-hull ray and
uses its last blocked square before open water. Capital ships retain authored
endpoints and ordinary non-BNE Patrol is unchanged. The rule uses the BNE
runtime profile, movement class, capital-ship classification, live terrain and
endpoint-swap lifecycle; it contains no mission, map, coordinate, fixture,
cycle, faction or unit-ID branch.

Proof delta: expansion Human 5 destroyer slot 1553 / Java 47 reaches its near
endpoint `(102,98)` and promotes the return Patrol on fixture 253. Its stored
far point is oil-platform top-left `(101,85)`, but native publishes the south
footprint edge `(101,87)` on fixture 256. That goal constructs the six-heading
`N,N,NW,N,N,N` route on fixture 259, so the third heading lands northwest at
`(100,92)` on fixture 323. Java formerly fed the stored top-left directly to
NewPath, constructed `N,N,N,NW,N,N,N`, and remained at x 102. The corrected
case is exact through fixture 418; its new fixture-419 split is the same hull's
off-lattice endpoint completion, where native parks the final north byte at
`(100,88)` and turns home while Java consumes it to y 86. XHuman 8's existing
off-lattice destroyer turnaround, capital-ship endpoint swaps, blocked
shipyard-footprint failure and small-warship refusal cases remain negative
witnesses.

Efficacy receipt
`.bne-test-efficacy/c419-naval-far-endpoint-footprint/runs/97f9c12679b8d35f8b28988fcefb0b897a77fa5eb15f24a9c6b615da01e79c0c`
proves the focused fixture-256/323 regression assertion-fails on `a549084`
and passes on the candidate. All 38 destroyer-turnaround, sea-occupancy,
small-warship, naval-refusal and capital-ship focused tests pass, as do both
fixed 52-case gates. The ordinary executable next-level gate exits zero after
209 Python checks and 96 engine/desktop checks; its command worklist remains
11 comparable scenarios (6 exact / 5 divergent) without regression or
infrastructure failure. `--require-certified` remains incomplete on the
documented producer lanes, and remote AI discovery still stops at strict SSH
verification of the changed `i9beef` host key, which was not modified.

The shared frontier remains fixture 312: expansion Orc 8 human submarine slot
1432 is at x 88 natively versus x 90 in Java, and its route-publication family
remains paused pending a new discriminator. The earliest unpaused fleet
findings are now expansion Human 12 and expansion Orc 10 at fixture 325, then
Human 8 at 328. Expansion Human 5's new off-lattice naval completion at 419
and Orc 11's independent sapper split at 418 remain available as later lanes.

## Prior release checkpoint — 2026-08-31 (cycle-418 land-Patrol Move-body authority)

Accepted cycle-1,800 receipt `cdbbab7f` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 37,856, an increase of 92. Orc 11 advances from fixture 326
to 418. The cycle-400 fleet improves from 37 clean / 15 divergent to 38 clean
/ 14 divergent / 0 failed under receipt `bc036709`. The long receipt is
retained at
`.bne-artifacts/runs/cdbbab7f8871b37cdb4e796c146d35bea583e7ea039411766bf02b6e70d2ea9b`.
It binds dirty engine-input identity `17303c17` at base revision `7b9e20c` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and replayable source capsule
`f37a9655f1930caea23f8f1472de25d393e9c96619abd8677fa61e02b75d538f`.

Behavioral delta: a freshly constructed behavior-two land Patrol owns an
explicit, persisted native Move-body provenance. Its first committed stride
drains to opcode zero before generic presentation acquisition may intervene;
that opcode scans once and, when it finds a target, banks direct Attack while
Patrol consumes the next cached byte. Attack promotion retains a free route
head on its timer-one handoff, while the established absent/refused-head path
still parks and redraws. The provenance survives Java's borrowed Attack-to-
Move call seam, selects `script.bin` pixel cadence, drains the real Attack
3,2,1 constructor after a retarget residual, and exempts that still-live route
from the generic ranged-retarget tail park. The rule uses movement class, AI
behavior, action-constructor state, queued-order provenance, route state and
live occupancy; it contains no mission, map, coordinate, fixture, cycle,
faction or unit-ID branch.

Proof delta: Orc 11 archer slot 1560 / Java 40 exposes Patrol Still 1977/3 on
fixture 307, commits its first north stride on 310, and spends the first three
pixels on 311. Its fixture-326 Move-body opcode zero settles at `(120,30)`,
banks direct Attack and consumes the next north byte under Patrol; Attack pops
when those pixels settle on fixture 342. Independently, archer slot 1559 /
Java 41 consumes the free route head on Attack's fixture-327 timer-one
handoff, retargets and commits northwest on 343, then drains the native Move
cadence to `(3776,896)` on fixture 359. The resulting Attack cursor is
2039/3,2,1 on fixtures 359..361 and hands the retained northwest byte directly
back to Move on 362 at `(117,27)`. Java formerly parked or delayed those
boundaries. Orc 11 is now exact through fixture 417; its new fixture-418 split
is independent goblin-sapper slot 1573, native Move versus Java Still. The
ordinary multi-heading knight Patrol at fixture 242 and XHuman 12's
absent/refused land-Patrol handoffs remain negative witnesses.

Efficacy receipt
`.bne-test-efficacy/c418-land-patrol-attack-move-body-v2/runs/411e8da788ad21f53f67b4cc333ed1836da76e7bea004eb509399aa30fbb2626`
proves the expanded fixture-327/343/359/362 regression assertion-fails on
`7b9e20c` and passes on the candidate. All ten recurring-patrol,
moving-land-launch, worker-refusal and patrol-liveness focused tests pass, as
do all 46 save-game tests and both fixed 52-case gates. The ordinary
executable next-level gate exits zero after 209 Python checks and 96
engine/desktop checks; its command worklist remains 11 comparable scenarios
(6 exact / 5 divergent) without regression or infrastructure failure.
`--require-certified` remains incomplete on the documented producer lanes,
and remote AI discovery still stops at strict SSH verification of the changed
`i9beef` host key, which was not modified.

The shared frontier remains fixture 312: expansion Orc 8 human submarine slot
1432 is at x 88 natively versus x 90 in Java. Its route-publication family
remains paused until a new discriminator is available. The earliest unpaused
fleet findings are expansion Human 5 destroyer slot 1553 at fixture 323,
expansion Human 12 and expansion Orc 10 at fixture 325, then Human 8 at 328.
Orc 11's new independent sapper finding at fixture 418 remains available as a
later campaign-local lane.

## Prior release checkpoint — 2026-08-31 (cycle-324 recurring land-Patrol authority)

Accepted cycle-1,800 receipt `16b36ca8` preserves the shared clean horizon at
fixture 311 and improves or preserves every campaign frontier. The fleet
remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 37,764, an increase of two. Orc 11 advances from fixture 324
to 326. The cycle-400 fleet remains 37 clean / 15 divergent / 0 failed under
receipt `07c0956c`. The long receipt is retained at
`.bne-artifacts/runs/16b36ca83f59a6ba1c379e4bdca3b29f0d097e5310ae9db8285e664fefd77d99`.
It binds dirty engine-input identity `b4e69e41` at base revision `341aab7` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and replayable source capsule
`a33045c55f4b7bfe6ce82ec8b1cc2b7f3de09e1150740cd4463e02a690901b6e`.

Behavioral delta: a recurring behavior-two land Patrol replacement can be
promoted while Patrol is already current. Its native action constructor still
rewinds to the unit's `script.bin` Still head for the three-count opening and
scans on the first opcode zero. Java's generic periodic scan remains only as a
fallback when that native marker finds no target; a successful marker banks a
direct Attack behind the Patrol stride. The Move program then owns the
committed residual's pixel cadence until the queued Attack pops. The rule uses
movement class, AI behavior, current/promoted order, native sequence state and
queued-order provenance; it contains no mission, map, coordinate, fixture,
cycle, faction or unit-ID branch.

Proof delta: Orc 11 archer slot 1559 / Java 41 receives the recurring Patrol
replacement on fixture 299 while its previous north stride is still moving.
The final pixels settle and promote the replacement as Still sequence 1977 /
timer 3 on fixture 305; the constructor counts through 2 and 1 before its
fixture-308 opcode zero selects the hostile, writes a northwest attack route,
banks direct order 12 and still takes the first stride under Patrol. Native
and Java remain pixel-exact at `(3831,983)` through fixture 313, take the
script-owned two-pixel beat to `(3829,981)` on 314, and settle at `(3808,960)`
under Attack on fixture 324. Java formerly kept sequence `-1`, used the
presentation movement cadence, and exposed Attack on fixture 325. The next
Orc 11 mismatch is independent archer slot 1560 / Java 40 at fixture 326,
native y 30 versus Java y 31.

Efficacy receipt
`.bne-test-efficacy/c324-recurring-land-patrol-attack/runs/62113fcd4f212d4d7912e85ca8aaa94c839aa9660a83f66ce616c91a9f521664`
proves the focused fixture-305/314/324 regression assertion-fails on
`341aab7` and passes on the candidate. All Orc 11 recurring-patrol, XHuman 12
moving-land-launch and worker-refusal controls pass. Both fixed 52-case gates
pass. The ordinary executable next-level gate exits zero after 209 Python
checks and 95 engine/desktop checks; its command worklist remains 11
comparable scenarios (6 exact / 5 divergent) without regression or
infrastructure failure. `--require-certified` remains incomplete on the
documented producer lanes, and remote AI discovery still stops at strict SSH
verification of the changed `i9beef` host key, which was not modified.

The shared frontier remains fixture 312: expansion Orc 8 human submarine slot
1432 is at x 88 natively versus x 90 in Java. Its route-publication family
remains paused after the retained failed variants and requires a new static or
native discriminator. The earliest unpaused fleet findings are expansion
Human 5 destroyer slot 1553 at fixture 323, then expansion Human 12 and
expansion Orc 10 at fixture 325; the same AI/combat lane continues locally at
Orc 11 archer slot 1560 on fixture 326.

## Prior release checkpoint — 2026-08-31 (cycle-320 live depot-tail authority)

Accepted cycle-1,800 receipt `9dbeb3f7` preserves the shared clean horizon at
fixture 311 and improves every campaign frontier. The fleet remains 10 clean /
42 divergent / 0 failed, while the 52 per-case exact prefixes sum to 37,762,
an increase of 164. Expansion Human 11 advances from fixture 320 to 484. The
cycle-400 fleet improves from 36 clean / 16 divergent to 37 clean / 15
divergent / 0 failed under receipt `fad99efb`. The long receipt is retained at
`.bne-artifacts/runs/9dbeb3f7ec22e7f04eba149fcfeb8bcc4c1aff65030682f57feb034f336f8bd4`.
It binds dirty engine-input identity `afd62952` at base revision `7c099ea` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and replayable source capsule
`2826f546eab6f83f8918d46f69c6cb90d61f49e0e43f3ecd4c7b13975fedef1e`.

Behavioral delta: when a laden land worker's northeast residual crosses a
depot's lateral midpoint, the resource action refreshes the closest depot edge
even if a cached tail remains. Only a stale northwest tail, or the established
north-then-northwest form, parks under collision one; a live duplicate north
tail remains authoritative and may commit on the settle visit. The narrowed
rule uses carrying/return state, movement stride, residual phase, collision
generation, route shape and a measured depot-edge transition. It contains no
mission, map, coordinate, fixture, cycle, faction or unit-ID branch.

Proof delta: expansion Human 11 peon slot 1495 / Java 105 carries gold toward
the great hall on the six-heading `N,N,N,NE,N,N` route. Its northeast stride
leaves the final duplicate north tail and drains through fixture 319 at
`(19,87)` toward depot edge `(18,84)`. On fixture 320 native refreshes that
edge to `(20,84)`, consumes north, advances route index four to five and
anchors at `(19,86)` while preserving the exact pixel `(608,2784)`. Java
formerly treated every two-byte north-headed tail as stale, parked it at
route-index twenty with collision one, and committed north on fixture 321.
The corrected shape keeps the edge refresh but consumes the live tail, makes
the case exact through fixture 400, and moves its first divergence to 484.

Efficacy receipt
`.bne-test-efficacy/c320-lateral-depot-tail-isolated/runs/d8d7189916f2a665f1408d4d77a7ef94fbb6fc8d534570cc753bd8d0f7eb47dd`
proves the focused fixture-320 assertion fails on `7c099ea` and passes on the
candidate. All 18 consumed-tail, laden-return and XHuman 12 movement controls
pass, including the stale one-byte northwest-tail negative witness. Both fixed
52-case gates pass. The ordinary executable next-level gate exits zero after
209 Python checks and 95 engine/desktop checks; its command worklist remains
11 comparable scenarios (6 exact / 5 divergent) without regression or
infrastructure failure. `--require-certified` still reports the documented
incomplete producer lanes, and remote AI discovery still stops at strict SSH
verification of the changed `i9beef` host key, which was not modified.

The shared frontier remains fixture 312. Expansion Orc 8 human submarine slot
1432 is at x 88 natively versus x 90 in Java. Frontier compilation under
`.bne-field-evidence/cycle311-post-c320-worker-frontier-compile` authenticates
the frame. Cadence and temporal analysis prove a low-refusal consumed tail
returns on the next visit, but cold redraw, both continued wall faces, and a
parked-occupancy redraw all fail to produce native's west-leading route. Those
route-publication hypotheses are retained only as private evidence; returning
to this family requires a new native/static discriminator rather than another
fitted route variant.

## Prior release checkpoint — 2026-08-31 (cycle-311 long patrol-route authority)

Accepted cycle-1,800 receipt `85d6f4b5` advances the shared clean horizon from
fixture 310 through 311 and preserves or improves every campaign frontier. The
fleet remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 37,598, an increase of one. The cycle-400 fleet remains 36
clean / 16 divergent / 0 failed under receipt `855e6439`. The long receipt is
retained at
`.bne-artifacts/runs/85d6f4b5059a65b7170cf3a88f5e8317aff727323006f89c11947762d9b0a0b7`.
It binds dirty engine-input identity `59215f3e` at base revision `a35d0e0` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and replayable source capsule
`5438af580007ec849983504114cd725966523f7e61c90f4e07f6a48c5f83e777`.

Behavioral delta: the naval Patrol residual free-closer correction belongs to
a short wall-follow generation of at most four cached headings. A longer paid
route remains authoritative after its first stride settles even when another
free compass heading is closer to the Patrol point. The narrowed rule uses
movement class, Patrol provenance, route-generation length, route cursor and
residual-settle phase; it contains no mission, map, coordinate, fixture,
cycle, faction or unit-ID branch.

Proof delta: expansion Orc 8 human destroyer slot 1431 / Java 169 draws the
seven-heading `SW,W,W,NW,N,N,NE` paid route from `(94,80)` toward point
`(88,73)`. Its southwest stride leaves six cached headings and settles through
fixture 310 at `(92,82)` with west at the cursor. On fixture 311 native consumes
that west byte, advances route index one to two and anchors at `(90,82)`.
Java formerly applied the four-heading wall-follow correction, discarded the
six-byte tail and chose free north because it was closer to the Patrol point,
anchoring at `(92,80)`. The corrected route authority moves expansion Orc 8's
first divergence from fixture 311 to 312.

Efficacy receipt
`.bne-test-efficacy/c311-long-destroyer-route/runs/bcba026bc78b72d7240feb48a4e60d3091f9d3404dd011973b310561871eb268`
proves the focused fixture-311 regression assertion-fails on `a35d0e0` and
passes on the candidate. All 62 focused small-warship, pathfinder,
stride-destination and occupancy controls pass, as do both fixed 52-case
gates. The ordinary executable next-level gate exits zero after 209 Python
checks and 95 engine/desktop checks; its command worklist remains 11 comparable
scenarios (6 exact / 5 divergent) without regression or infrastructure
failure. The broader certified producer lanes remain incomplete, and remote AI
discovery still stops at strict SSH verification of the changed `i9beef` host
key, which was not modified.

The shared frontier is now fixture 312. Expansion Orc 8 human submarine slot
1432 is at x 88 natively versus x 90 in Java. Frontier compilation under
`.bne-field-evidence/cycle311-frontier-compile` authenticates the frame and
routes the position/movement mismatch to cadence and temporal state-machine
analysis next.

## Prior release checkpoint — 2026-08-31 (cycle-310 odd-point flyer completion)

Accepted cycle-1,800 receipt `7cb1c8e4` advances the shared clean horizon from
fixture 303 through 310 and preserves or improves every campaign frontier. The
fleet remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 37,597, an increase of seven. The cycle-400 fleet remains 36
clean / 16 divergent / 0 failed under receipt `090af527`. The long receipt is
retained at
`.bne-artifacts/runs/7cb1c8e4c4917b2e38d867325ab6d50911c0b43fadf3e44d8fc9e7ba4e3cf530`.
It binds dirty engine-input identity `8b8fca16` at base revision `b87fa7a` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and replayable source capsule
`0f744a3edd3481707c2cf101cfa3ffd18e9e258aa98660a07ec619b3e47eacde`.

Behavioral delta: an odd behavior-four scout point is outside an armed
flyer's doubled movement lattice. When its committed stride settles on the
adjacent even anchor with only the overshoot byte left, Java now parks that
cached byte and completes the one-shot scout instead of consuming the byte in
the same visit. A multi-step scout generation reconstructs the native
three-call Still head. The rule uses behavior, scout lifecycle, doubled
movement, endpoint parity, route cursor and residual-settle phase; it contains
no mission, map, coordinate, fixture, cycle, faction or unit-ID branch.

Proof delta: expansion Orc 8 gryphon-rider slot 1560 / Java 40 follows the
native `N,NE,N,NE,N` route toward odd point `(4,7)`. Through fixture 303 both
engines have consumed four headings, retain the final north byte and are
finishing their northeast residual on anchor `(4,8)`. On fixture 304 native
settles at pixel `(128,256)`, parks route index 4 at 20 and reconstructs
Still@2233/3. Java formerly consumed the parked north byte and reported Patrol
at `(4,6)`. The corrected lifecycle keeps `(4,8)` Still and moves expansion
Orc 8's first divergence from fixture 304 to 311.

Efficacy receipt
`.bne-test-efficacy/c304-armed-flyer-odd-point/runs/0102eba3d45e042c27ddb658748295dd0b3f10b3f2d4893568be84ae7db973f1`
proves the focused fixture-304 regression assertion-fails on `b87fa7a` and
passes on the candidate. All 63 focused flyer, pathfinder, stride-destination
and occupancy controls pass, as do both fixed 52-case gates. The ordinary
executable next-level gate exits zero after 209 Python checks and 95
engine/desktop checks; its command worklist remains 11 comparable scenarios
(6 exact / 5 divergent) without regression or infrastructure failure.
`--require-certified` still reports the documented incomplete producer lanes;
remote AI discovery also stops at strict SSH verification of the changed
`i9beef` host key, which was not modified.

The shared frontier is now fixture 311. Expansion Orc 8 human destroyer slot
1431 is at `(90,82)` natively versus `(92,80)` in Java. Frontier compilation
under `.bne-field-evidence/cycle310-frontier-compile` authenticates the frame
and routes the position/movement mismatch to cadence next.

## Prior release checkpoint — 2026-08-31 (cycle-303 retained naval refusal routes)

Accepted cycle-1,800 receipt `cf53f6d8` advances the shared clean horizon from
fixture 302 through 303 and preserves or improves every campaign frontier. The
fleet remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 37,590, an increase of 93. The cycle-400 fleet remains 36 clean
/ 16 divergent / 0 failed under receipt `ce1644e3`. The long receipt is
retained at
`.bne-artifacts/runs/cf53f6d8d8417fea0230951643c496f5fa7d87e01580d45179d43c7bbb94ec03`.
It binds dirty engine-input identity `4ca819bc` at base revision `fb319db` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and replayable source capsule
`0b167e14722e60c5c88d67b37e4479b87823ca64a045278a7fe58bce077d589c`.

Behavioral delta: behavior-six capital Patrol keeps a collision-marked route
authoritative across repeated occupied timer-one wakes and after the head
finally commits; each refused wake advances the common packed collision
generation and rearms the complete Move band without parking the route.
Separately, a small warship whose previous stride settles onto a newly exposed
occupied cached head records the next ordinary naval refusal before the paid
wake is allowed to redraw. Only a terminal one-byte paid tail preserves the
pass-start hull view and continues its old wall face; a longer parked tail
redraws cold after its band. These rules use order, movement phase, route
cursor, collision/refusal provenance, unit class and live occupancy, with no
mission, map, coordinate, fixture, cycle, faction or unit-ID branch.

Proof delta: expansion Human 7 juggernaught slot 1573 / Java 27 retains
`E,SE,E,SE,E,SE,E` at `(24,26)` while destroyer 1570 occupies its east head.
Native advances packed collision generations one, two and three on fixtures
258, 273 and 288, then commits east and retains the six-byte tail on fixture
303; Java formerly parked the route on the second wake and did not move until
324. Expansion Orc 8 destroyer slot 1435 / Java 165 settles its southeast
residual at `(90,76)` on fixture 303 with cached east occupied, advances sticky
refusal nine to ten and parks the route for Move 15..1, then cold-redraws and
commits southeast on fixture 318. Java formerly redrew and committed southeast
on the settle visit. The two cases now first diverge at fixtures 395 and 304
respectively.

Efficacy receipts
`.bne-test-efficacy/c303-capital-retained-collision-route/runs/461280c9ad0ce625364c5f9c1e5937cb505cf4fbef7d9a3d71d4fb9478630c8e`
and
`.bne-test-efficacy/c303-small-warship-residual-head-refusal/runs/f2201f857c4703cd535ba59eba9bbc7b2a983e28782703cf94c67b493317d7af`
prove both focused assertions fail on `fb319db` and pass on the candidate.
All 35 focused capital, small-warship, sea-occupancy and residual controls
pass, as do both fixed 52-case gates. The ordinary executable next-level gate
exits zero after 209 Python checks and 95 engine/desktop checks; its command
worklist remains 11 comparable scenarios (6 exact / 5 divergent) without
regression or infrastructure failure. `--require-certified` still reports the
documented broader evidence debt after those local checks pass: strict SSH
verification rejects the changed `i9beef` host key, which was not modified.

The shared frontier is now fixture 304. Expansion Orc 8 gryphon-rider slot
1560 is natively Still at y 8 while Java is Patrol at y 6. Frontier compilation
under `.bne-field-evidence/cycle303-frontier-compile` authenticates the frame
and routes the position/movement mismatch to cadence next.

## Prior release checkpoint — 2026-08-31 (cycle-302 scout route-generation prefix)

Accepted cycle-1,800 receipt `72c18a62` advances the shared clean horizon from
fixture 301 through 302 and preserves or improves every campaign frontier. The
fleet remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 37,497, an increase of one. The cycle-400 fleet remains 36
clean / 16 divergent / 0 failed under receipt `e454b347`. The long receipt is
retained at
`.bne-artifacts/runs/72c18a62b7c76a07c655267cea69b0e5778f04be8c728fa30d9fc40c16da2e53`.
It binds dirty engine-input identity `ee7794e1` at base revision `f62610f` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and replayable source capsule
`1da34e9d97c601342ab213b74f8f893c870f4182f2d5ac15e90aeba610a92552`.

Behavioral delta: a behavior-four aircraft's saturated cardinal route refill
remains part of the same one-shot scout generation. Java may redraw the actual
replacement headings after the old prefix is exhausted, but it now carries the
already-consumed route-buffer prefix into that redraw. The eventual landing can
therefore distinguish a multi-step generation, which reconstructs the native
three-call Still head, from a genuine one-step detour, which reconstructs at
timer one. The rule uses scout role, route saturation, cardinal geometry,
movement completion, and route-cursor provenance; it contains no mission, map,
coordinate, fixture, cycle, faction, or unit-ID branch. Ordinary one-step
detours and behavior-two launched flyers retain their distinct seams.

Proof delta: expansion Orc 8 gryphon-rider slot 1550 / Java 50 consumes the
three-heading south prefix of its recurring scout ray, lands at `(2,16)`, and
refills with the final west stride toward `(0,16)` on fixture 230. Native keeps
that S,S,S -> W cursor as one generation and, when its pixels settle on fixture
254, installs Still@2233/3. Java formerly treated west as a fresh one-heading
route and installed Still@2233/1. Its entire later idle program was therefore
two visits early: the fixture-299 air pass queued Patrol at the same point and
with the same RNG pair, but Java promoted it at fixture 302 and moved northeast
at 310; native remains Still@2252/2 at 302, promotes Patrol at 304, and moves at
312. The corrected route provenance advances expansion Orc 8's exact prefix
301 -> 302.

Efficacy receipt
`.bne-test-efficacy/xorc8-scout-route-generation-prefix/runs/161f08476634d40521ac0938e95b938d36270114f85a1d9b903ba0b57d5e4a40`
proves the fixture-254 timer assertion fails on `f62610f` and passes on the
candidate. Eight armed-flyer real-data checks pass, as do 27 sea-occupancy and
capital-patrol controls. `MeleeChaseReplanResidualTest` retains the same three
pre-existing failures on baseline and candidate under audit receipt
`.bne-test-efficacy/audit-xorc8-route-prefix-melee-controls/runs/cc740fbb41eccd0269acf3d282e12cea1e2e4fd91708328002e8276872ac6376`;
it is not claimed as passing candidate coverage. Both fixed 52-case cycle-400
and cycle-1,800 gates pass. The executable next-level gate exits zero after 209
Python checks and 95 focused engine/desktop checks; its command worklist remains
11 comparable scenarios (6 exact / 5 divergent) without regression or
infrastructure failure. Strict SSH verification still rejects the changed
`i9beef` host key, so remote AI discovery and the broader certification lanes
remain intentionally incomplete.

The shared frontier is now tied at fixture 303. Expansion Human 7 juggernaught
slot 1573 is at x 26 natively versus 24 in Java, while expansion Orc 8 human
destroyer slot 1435 is at `(90,76)` natively versus `(92,78)` in Java. Both
authenticated packets route first to cadence and temporal state-machine
analysis under frontier compilation `92fe7172`.

## Prior release checkpoint — 2026-08-31 (cycle-301 unreachable Attack idle dispatch)

Accepted cycle-1,800 receipt `33feb0a0` advances the shared clean horizon from
fixture 299 through 301 and preserves or improves every campaign frontier. The
fleet remains 10 clean / 42 divergent / 0 failed, while the 52 per-case exact
prefixes sum to 37,496, an increase of 37. The cycle-400 fleet remains 36 clean
/ 16 divergent / 0 failed under receipt `19ba2559`. The long receipt is retained
at
`.bne-artifacts/runs/33feb0a0798d544cd9ed06f1115db62564481960f0d4e6e8b9427d5b65f352d2`.
It binds dirty engine-input identity `335d5f94` at base revision `bde0cb3` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and replayable source capsule
`70b703e178b81e00bec97abdd5703d59c978e6184b692843b0f01df8c895b5b4`.

Behavioral delta: when a marked Attack target is unreachable by the attacker's
terrain class, the native GiveOrder epilogue installs the fresh Still cursor
and immediately pays that active-order Still callback on the same construction
visit. The cursor remains at the fresh marker, so its first opcode executes on
the following visit. Java formerly installed the right Still marker and timer
but deferred its idle draw by one visit, assigning all subsequent asynchronous
draws to the wrong semantic consumers. The rule uses terrain reachability,
target validity, order construction, and the existing active-order dispatcher;
it contains no mission, map, coordinate, fixture, cycle, faction, or unit-ID
branch. Reachable compact routes do not enter the seam, and siege units retain
their existing exclusion from land-idle random selection.

Proof delta: expansion Orc 11 axethrower slot 1517 / Java 83 acquires its enemy
row on fixture 248 and winds Attack@887 with timers 3,2,1 on fixtures 250..252.
On fixture 253 native clears the unreachable target, installs Still@825/1,
calls `0x0040AD58` with result 14829, and retains the fresh cursor; fixture 254
then executes OP0 and installs Still@4983/1. Java formerly skipped slot 1517's
same-visit draw, so the result was consumed by the next eligible unit instead.
That semantic shift eventually gave the cycle-291 cannon shell from native
slot 1493 / Java 107 different aim jitter and moved its impact from native
fixture 300 to Java fixture 301. Paying the callback at the native boundary
aligns the chain and advances expansion Orc 11's exact prefix 299 -> 336.

Efficacy receipt
`.bne-test-efficacy/xorc11-unreachable-attack-idle-dispatch/runs/e93db43152130f817e1f65492e6472bbd4eeeda6f779e963554cf4237fca4368`
proves the real-data fixture-253 seed assertion fails on `bde0cb3` and passes on
the candidate. The new regression plus expansion Human 4 acquisition and
expansion Orc 11 patrol/attack controls pass 15 tests. Both fixed 52-case
cycle-400 and cycle-1,800 gates pass. The executable next-level gate exits zero
after 209 Python checks and 95 focused engine/desktop checks; its command
worklist remains 11 comparable scenarios (6 exact / 5 divergent) without
regression or infrastructure failure. A broader synthetic idle-attack method,
`rangedChaseRetargetHoldsThreeVisitsBeforeTheNextStep`, fails identically on
the baseline and candidate and is therefore recorded as pre-existing test debt,
not accepted as candidate coverage. Strict SSH verification still rejects the
changed `i9beef` host key, so remote AI discovery and the broader certification
lanes remain intentionally incomplete.

The sole shared frontier blocker is now expansion Orc 8 at fixture 302: native
gryphon-rider slot 1550 is Still while the paired Java unit is Patrol. The
accepted frontier packet routes the split to cadence and state-machine evidence;
expansion Human 7 follows at fixture 303.

## Prior release checkpoint — 2026-08-31 (cycle-299 critter wander generations)

Accepted cycle-1,800 receipt `3c80d2f0` preserves the shared clean horizon
through fixture 299 while removing one of the two tied cycle-300 blockers. The
fleet improves from 8 clean / 44 divergent / 0 failed to 10 clean / 42
divergent / 0 failed, and the 52 per-case exact prefixes now sum to 37,459, an
increase of 1,346. The cycle-400 fleet improves from 34 clean / 18 divergent
to 36 clean / 16 divergent / 0 failed under receipt `17ac80bb`. The long
receipt is retained at
`.bne-artifacts/runs/3c80d2f076265d10eea57f47fc55c56308620540646740e5577a6923c9815d05`.
It binds dirty engine-input identity `9c9d0b73` at base revision `9ee9d5c` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and replayable source capsule
`8a90a44960c260a6052eefb6c91adfefc16bf93ce493098d7ca76394b009ce2e`.

Behavioral delta: a critter's completed-wander pause and packed refusal count
belong to that wander generation. When an idle action marker successfully
installs a replacement wander target, Java now retires the old pause and
refusal count before the new target starts its order delay. Refusals against
one unchanged target remain sticky and still open the native fifteen-cycle
Move band on the eighth refusal. The rule is confined to the BNE critter idle
marker and uses accepted order construction plus target replacement; it
contains no mission, map, coordinate, fixture, cycle, faction, or unit-ID
branch.

Proof delta: Human 11 native critter slot 1498 / Java 102 receives east target
`(75,50)` on fixture 257, counts refusals one through eight on fixtures
260-267, and pays that generation's Move band through 281. The fixture-282
idle marker replaces the target with northeast `(75,49)`; native changes the
packed collision byte from `0x80` to `0x00`, while Java formerly carried eight.
The fresh target then counts one through eight on fixtures 285-292, remains
Move at the old fleet blocker on 300, and commits northeast on 307. Human 11's
exact prefix advances 299 -> 670. The same systemic change advances Human 10
341 -> 548 and makes Human 3 and expansion Human 1 exact through the full
1,800-cycle horizon (from 1,539 and 1,293 respectively). Orc 13 and Human 14's
occupied-wander controls still preserve their refusal generations.

Efficacy receipt
`.bne-test-efficacy/runs/1cf064e8aa2eeee395e9903de4a77084a0778107a3d4f904cfe11e1546d2dedc`
proves the new real-data regression assertion-fails on `9ee9d5c` at the
fixture-282 reset and passes on the candidate. Five focused replacement,
occupied-wander, and critter-wait checks pass. Both fixed 52-case cycle-400
and cycle-1,800 gates pass. The executable next-level gate exits zero after
209 Python checks and 95 focused engine/desktop checks; its command worklist
remains 11 comparable scenarios (6 exact / 5 divergent) with no regression or
infrastructure failure. Strict SSH verification still rejects the changed
`i9beef` host key, so remote AI discovery and the broader certification lanes
remain intentionally incomplete.

The sole shared frontier blocker is now expansion Orc 11 at fixture 300:
native battleship slot 1511 has 105 HP while its paired Java unit has 117 HP.
The accepted damage-shape classifier finds different change cycles and counts,
so randomized damage is not suspected and the work order routes to causal
combat/event ordering. Expansion Orc 8 follows at fixture 302 and expansion
Human 7 at fixture 303.

## Prior release checkpoint — 2026-08-31 (cycle-299 collision-lifecycle consolidation)

Accepted cycle-1,800 receipt `8858bbe1` advances the shared clean horizon from
296 to 299 (two tied earliest divergences at 300), preserves or improves every
campaign frontier, and retains the fleet totals of 8 clean / 44 divergent / 0
failed. The 52 per-case exact prefixes now sum to 36,113, an increase of 149.
The cycle-400 fleet improves from 33 clean / 19 divergent to 34 clean / 18
divergent / 0 failed under receipt `42463ada`. The long receipt is retained at
`.bne-artifacts/runs/8858bbe1ee3b6a902dba93de595d33ac57c2f78882e4f54a81d7c34c152eac85`.
It binds dirty engine-input identity `ca0f5b7b` at base revision `88aab36` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and replayable source capsule
`3bcb35f88bf9ce3a8a6e8be2ef1ba9e3abb00baf412b1fa6fdda5f481718652e`.

Behavioral delta: packed collision ownership now survives the native
active-order seams which actually retain it, and retires at the seams which
construct a fresh order. A settled combat replacement can preserve a
half-spent formation wall, probe a blocked post-construction route on its
timer-one handoff, keep an offered-building skirt axis, and keep only a mover
which has both collision-one and the matching offered target hard. A consumed
laden-return tail redraws around collision-marked convoy traffic instead of
mistaking it for the clean cooperative mover which earned the original wait.
For terrain harvest, a new resource order clears an unmatched stale owner; a
collision-saturated full prefix returns through action 23, counts eight naked
refusals, retains collision eight through the opened guide-route redraw, and
advances that generation to nine when its repeated cached diagonal remains
occupied. These rules use order provenance, route lifecycle, collision state,
live footprints, and target relationships; they contain no map, fixture,
coordinate, cycle, faction, or unit-ID branch.

Proof delta: Orc 12 advances 319 -> 341, Orc 14 advances 370 -> 469, and
expansion Human 12 advances 296 -> 324. On the last case, the offered-target
controls at fixture 257 distinguish collision-plus-offer, collision-only, and
offer-only moving chasers. Native slot 1512 consequently retains
`NE,SE,SE,SE,...` through its fixture-323 collision. Peon 1385 independently
starts its fresh construction generation at one on fixture 285, reaches eight
on 292, merges `NE,NE,SE,S` and commits NE on 307 without clearing the packed
counter, then retains the second NE and opens collision nine / Move 15 on 323.
The native packet extends through the second paid band: fixture 338 retires
that route into action 23 and fixture 341 begins its fresh east route. Ordinary
cooperative routes, collision-only/offer-only chasers, free shared-wall
detours, and unsaturated wood prefixes remain negative controls.

Three new efficacy receipts prove the latest distinct mechanisms fail before
and pass after the candidate:
`.bne-test-efficacy/h323-offered-building-residual-axis-skirt/runs/9417965811941838f55319b74aef8dbfe769a2755b7b312cb3deb3e4048d8f26`,
`.bne-test-efficacy/h323-offered-collision-formation-wall/runs/2374dea35728464d9d362afb4ca27ec0190eea535d71afdadd9496a8fdd409ec`,
and
`.bne-test-efficacy/h324-saturated-construction-redraw-route/runs/26a5b7077a5ee28ede982f53cf0f2fc75b740433695df0cca62e6da24b0f512e`.
The eight changed/new focused real-data classes pass 55 tests. Both fixed
52-case cycle-400 and cycle-1,800 gates pass, and the executable next-level
gate exits zero after 209 Python checks and 95 focused engine/desktop checks;
its command worklist remains 11 comparable scenarios (6 exact / 5 divergent)
without regression or infrastructure failure. Strict SSH verification still
rejects the changed `i9beef` host key, so remote AI discovery and the broader
certification lanes remain intentionally incomplete.

The new shared frontier is tied at fixture 300. Human 11 first differs when
native slot 1498's critter remains MOVE while the paired Java unit is STILL;
the accepted compiler routes this missing order transition to Branch Witness.
Expansion Orc 11 first differs when native battleship slot 1511 has 105 HP and
the paired Java unit has 117 HP; it is routed to the causal combat lab.
Expansion Human 12's newly exposed blocker follows at fixture 325 on grunt
slot 1520 (`y` 43 native versus 44 Java).

## Prior release checkpoint — 2026-08-31 (cycle-296 blocked wood-order replacement)

Accepted cycle-1,800 receipt `0196efcc` advances the shared clean horizon from
295 to 296 (earliest divergence 297), preserves every campaign frontier, and
retains the fleet totals of 8 clean / 44 divergent / 0 failed. The 52 per-case
exact prefixes sum to 35,964, one more than the preceding accepted survey. The
receipt is retained under
`.bne-artifacts/runs/0196efcc0f4861e0b7e7d3b79c84635070c485019ac28ec011c0896605f8ad99`.
It binds dirty engine-input identity `4c190f71` at base revision `e0f63df` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and replayable source capsule
`7ff2260ea9b801329606c0daef34bc427e09f9a51a570c39d84c3ebd5b903816`.

Behavioral delta: the reverse tree ray's last static blocker remains the wood
order point even when its first step is cardinal and the direct tree route
would begin diagonally. When that short blocked-face prefix settles after
another active worker has claimed its tree, action 23 uses the existing
fifteen-square claimed-tree replacement search and writes the replacement into
both the resource target and wood order point on its first construction visit.
It retains route index twenty and pays the native three-call construction band
before redrawing. Open reverse-free order points, unclaimed targets, and
ordinary adjacent-tree settlement keep their existing behavior. The rule uses
static terrain, route lifecycle, active claim ownership, and the shared local
replacement search; it contains no map, fixture, coordinate, cycle, faction,
or unit-ID branch.

Proof delta: expansion Human 12 peon 1376 / Java 224 approaches tree `(14,89)`
from `(11,88)`. Native stores blocked square `(13,88)` as `orderXY`, commits the
single northeast byte on fixture 280, and drains its pixel residual through
fixture 295. Peon 1387 claims the old tree before settlement. On fixture 296,
native centers 1376 at `(12,87)`, keeps route index twenty, opens action 23 at
timer three, and immediately replaces both the resource target and `orderXY`
with unclaimed tree `(15,89)`. Java formerly discarded the static blocker in
favor of a direct `NE,E,E,SE` tree route, then consumed its stale east byte at
fixture 296. The corrected test intentionally stops at that authenticated
common frontier: a different unit changes occupancy on fixture 297, so native's
later four-byte redraw is not used as evidence for this fix. Retail Human 8's
open reverse-free cases around fixtures 1510-1511 and the existing ordinary
short-prefix cases remain negative controls.

Efficacy receipt
`.bne-test-efficacy/h296-blocked-order-claimed-replacement/runs/ddec319c9e763cd872605db14d53e56908165a093ae8b021156ce485f0cca802`
proves the new real-data regression fails on `e0f63df` and passes on the
candidate. The focused wood-order, residual-settlement, harvest destination,
terminal-refusal, saturated-route, and collision-ladder tests pass. The seven
broader gold-approach failures and the Human 13 wood-wall failure reproduce
unchanged on `e0f63df` and are pre-existing. The 52-case cycle-400 survey
reports 33 clean / 19 divergent / 0 failed, and receipts `842d7724` and
`0196efcc` prove the cycle-400 and cycle-1,800 accepted regression gates pass.
The executable next-level gate exits zero after 209 Python checks and 95
focused engine/desktop checks; its command worklist remains 11 comparable
scenarios (6 exact / 5 divergent) without regression or infrastructure
failure. Strict SSH host-key verification leaves AI discovery unavailable;
the broader certification lanes remain intentionally incomplete.

The newly exposed blocker remains expansion Human 12, now at fixture 297:
native peon 1385 is at `(12,88)` while Java 215 is at `(13,87)`. The accepted
compiler retains the paired position frame and routes the blocker to cadence
analysis.

## Prior release checkpoint — 2026-08-30 (cycle-295 saturated progressive settlement)

Accepted cycle-1,800 receipt `ee9944e8` advances the shared clean horizon from
289 to 295 (earliest divergence 296), preserves every campaign frontier, and
retains the fleet totals of 8 clean / 44 divergent / 0 failed. The 52 per-case
exact prefixes sum to 35,963, six more than the preceding accepted survey. The
receipt is retained under
`.bne-artifacts/runs/ee9944e8b7d2bb9c1a15f1d0853a9a9e313e47d5f782ff5453d7e163b6c2ec57`.
It binds dirty engine-input identity `39782c11` at base revision `fba0491` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and replayable source capsule
`93b439fc09e4ed93868f103911ee5ece141f3b66d07332644331457a6d38a91d`.

Behavioral delta: the first refusal after a melee chaser consumes a saturated
twenty-byte route can select a direct one-byte progressive refill while the
unit is still draining the committed step's pixel residual. That refill now
retains ownership through residual settlement. When its paid head then refuses,
Java retires the refill's path and collision generation and enters the native
active-order Still cadence: Attack `3,2,1`, repeatedly rearmed every three
cycles while no strictly closer free neighbor exists. Ordinary one-step
diagonal refills without this saturated-first-refusal provenance retain their
existing completed Move band. The rule uses path saturation, refill provenance,
residual lifecycle, refusal state, and live local geometry; it contains no map,
fixture, coordinate, cycle, faction, or unit-ID branch.

Proof delta: expansion Human 12 grunt 1481 / Java 119 at `(30,41)` consumes a
southwest one-byte refill on fixture 268 and drains its final pixels through
fixture 283. Native settles on fixture 284 with no path, collision, or refusal
generation and opens Attack `2539/3`; the cadence drains on 285 and 286, then
reopens at timer three on fixtures 287 and 290. Java formerly promoted the
generic completed Move `2482/15` and omitted those asynchronous idle draws.
Restoring the fixture-284, -287, and -290 consumers aligns all three fixture-290
melee rolls: native/Java damage becomes 8, 8, and 7, leaving grunt 1441 at 34
HP, footman 1449 at 47 HP, and grunt 1495 at 28 HP. Grunt 1495 / Java 105 at
fixture 174 is the held-out negative: its visually similar diagonal settlement
lacks saturated-progressive ownership and correctly remains in Move `2482/15`.

Efficacy receipt
`.bne-test-efficacy/h290-first-saturated-progressive-settle/runs/236eea10d33969abdd05ee0404d54ba42fc0914c77552c25517269761f8af541`
proves the expanded Human 12 regression fails on `fba0491` and passes on the
candidate. The five focused collision/refill, saturated construction,
three-cardinal terminator, retarget, and move/damage real-data classes pass,
along with all 30 `bne_java.py` tests. The 52-case cycle-400 survey reports
33 clean / 19 divergent / 0 failed, and both it and the cycle-1,800 survey pass
their accepted regression gates. The executable next-level gate exits zero
after 209 Python checks and 95 focused engine/desktop checks; its command
worklist remains 11 comparable scenarios (6 exact / 5 divergent) without
regression or infrastructure failure. The broader certification lanes remain
intentionally incomplete.

The newly exposed blocker remains expansion Human 12, now at fixture 296:
native peon 1376 is at x=12 while Java is at x=13. The accepted compiler
retains the focused paired position frame and routes the blocker to cadence
analysis.

## Prior release checkpoint — 2026-08-30 (cycle-289 loaded tanker swept-corner refusal)

Accepted cycle-1,800 receipt `2272220f` advances the shared clean horizon from
288 to 289 (earliest divergence 290), preserves every campaign frontier, and
retains the fleet totals of 8 clean / 44 divergent / 0 failed. The 52 per-case
exact prefixes sum to 35,957, seventy-five more than the preceding accepted
survey because retail Orc 8 advances from fixture 289 to 364. The receipt is
retained under
`.bne-artifacts/runs/2272220fc03b97e1d2d9d5901d4a8a878830097d206b638dd42ffa609d656563`.
It binds dirty engine-input identity `885fddd6` at base revision `6783fac` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and replayable source capsule
`0d277721cf1007c7fe6c238a4ecd3bc98a740dcb0c729d309deb3ff99b9bba3c`.

Behavioral delta: a loaded doubled tanker returning to an oil depot cannot
commit a cached diagonal through two distinct occupied cardinal side anchors,
even when the doubled-grid destination anchor itself is free. When both sides
are friendly naval hulls, Java now enters the shared native refusal primitive:
it increments the sticky refusal generation, parks the route, pays the
appropriate Move band, and replans after the queue changes. Other large ships,
fresh tanker routes, cardinal headings, and diagonals without the proved
two-hull squeeze retain their existing anchor-grid behavior. The predicate
uses resource state, route lifecycle, heading geometry, and live allied naval
occupancy; it contains no map, fixture, coordinate, cycle, faction, or unit-ID
branch.

Proof delta: retail Orc 8 human tanker 1478 / Java 122 commits north from
`(84,106)` to `(84,104)` on fixture 257, retaining `NW,NE` and sticky refusal
eleven. Its north residual drains pixel-exactly through fixture 288. The cached
northwest destination is free, but returning tanker 1483 / Java 117 occupies
the north side `(84,102)` and destroyer 1477 / Java 123 occupies the west side
`(82,104)`. Native therefore remains centered at `(84,104)` on fixture 289,
raises refusal eleven to twelve, parks route index twenty, and pays Move
`15..1` through fixture 303 before replanning and committing north on 304.
Java formerly committed northwest to `(82,102)` on 289. The corrected sealed
case remains exact through fixture 363.

Efficacy receipt
`.bne-test-efficacy/h289-loaded-tanker-squeezed-diagonal/runs/e8b5478e51ec321ec936dab47dc7234853d3472346497f333aead120054ebd68`
proves the expanded Orc 8 regression fails on `6783fac` and passes on the
candidate. The focused oil/tanker suite and all 30 `bne_java.py` tests pass.
The 52-case cycle-400 survey reports 33 clean / 19 divergent / 0 failed, and
both it and the cycle-1,800 survey pass their accepted regression gates. The
executable next-level gate exits zero after 209 Python checks and 95 focused
engine/desktop checks; its command worklist remains 11 comparable scenarios
(6 exact / 5 divergent) without regression or infrastructure failure. The
broader certification lanes remain intentionally incomplete.

The newly exposed blocker returns to expansion Human 12 at fixture 290: native
grunt 1441 has 34 HP versus Java 38, native footman 1449 has 47 versus Java 49,
and native grunt 1495 has 28 versus Java 31. The accepted compiler retains the
complete three-unit damage frame and routes the blocker to parity-lab causal
analysis.

## Prior release checkpoint — 2026-08-30 (cycle-288 collided long-tail building redraw)

Accepted cycle-1,800 receipt `9392b251` advances the shared clean horizon from
286 to 288 (earliest divergence 289), preserves every campaign frontier, and
retains the fleet totals of 8 clean / 44 divergent / 0 failed. The 52 per-case
exact prefixes sum to 35,882, three more than the preceding accepted survey.
The receipt is retained under
`.bne-artifacts/runs/9392b251259a4c768bd041db6bb11bdfa226ea0e3e02fb49b8b7fae054a2bd4e`.
It binds dirty engine-input identity `f8aaed1a` at base revision `7642407` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and replayable source capsule
`ba775091c7fed2febea9396e651311a8f05864205ad732c160134a80201376d9`.

Behavioral delta: a melee chaser which settles a blocked long cached tail in
its second collision generation, with no outstanding refusal, has completed
the cooperative Move band before replacing a mobile quarry with a building.
Its replacement path now keeps moving allies with nonzero collision state as
hard formation bodies, while collision-zero movers remain soft. Compact and
expired completed-band building handoffs retain their existing soft route.
The positive predicate uses the completed refusal lifecycle, retained native
buffer depth, collision generation, blocked residual, and target mobility; it
contains no map, fixture, coordinate, cycle, faction, or unit-ID branch.

Proof delta: expansion Human 12 grunt 1503 / Java 97 changes from footman 123
to guard tower 1483 on fixture 252 after retaining fourteen old-route bytes.
Native draws `E,SE,E,SE,S,S,SW,SW,W,W,W,NW,NE`: collided formation grunts
1510 and 1516 remain walls, while collision-zero grunt 1520 is soft. Java
formerly softened all three and drew `E,SE,SE,S,S,S,SW,W,W,W,NW,NE`.
Both routes commit the first east byte immediately, hiding the error until
fixture 287; the corrected third byte then advances east to `(42,39)` exactly,
and the sealed case remains exact through fixture 289.

Efficacy receipt
`.bne-test-efficacy/h287-completed-long-tail-building-retarget/runs/90938964c7c3ce5854baf58895265719ba8b354965bdf2092958cbd41920333a`
proves the expanded route/frontier regression fails on `7642407` and passes on
the candidate. All 11 focused real-data tests and all 30 `bne_java.py` tests
pass. The 52-case cycle-400 survey reports 33 clean / 19 divergent / 0 failed,
and both it and the cycle-1,800 survey pass their accepted regression gates.
The executable next-level gate exits zero after 209 Python checks and 95
focused engine/desktop checks; its command worklist remains 11 comparable
scenarios (6 exact / 5 divergent) without regression or infrastructure
failure. The broader certification lanes remain intentionally incomplete.

The newly exposed blocker is retail Orc 8 at fixture 289: native human oil
tanker 1478 is at `(84,104)` while Java is at `(82,102)`. The accepted
compiler retains the complete paired position frame and routes the blocker to
cadence analysis.

## Prior release checkpoint — 2026-08-30 (cycle-286 saturated retarget queued Attack)

Accepted cycle-1,800 receipt `277fb87f` advances the shared clean horizon from
285 to 286 (earliest divergence 287), preserves every campaign frontier, and
retains the fleet totals of 8 clean / 44 divergent / 0 failed. The 52 per-case
exact prefixes sum to 35,879, one more than the preceding accepted survey. The
receipt is retained under
`.bne-artifacts/runs/277fb87fb3bfea0fffb237ee4b9be6133359e9ebad06ce805b50057aed6a9e9e`.
It binds dirty engine-input identity `955b827f` at base revision `7f14a7d` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and replayable source capsule
`5a118c11039969c02b18d4228334bacf48844ef2abef5a8d89e943605ea121d7`.

Behavioral delta: a settled saturated building-to-mobile chase retarget which
refused the head of its fresh complete buffer has already paid that buffer's
Move band while retaining the old residual's queued Attack. When the band
finishes, Java now promotes the queued Attack before consuming the replacement
head, clears the retired building-route collision generation, drains Attack
timers `3,2,1`, and only then releases the complete mobile route. The positive
predicate uses the saturated retarget lifecycle, residual ownership, fixed
native buffer saturation, refusal generation, and a live on-map mobile quarry;
it contains no map, fixture, coordinate, cycle, faction, or unit-ID branch.

Proof delta: expansion Human 12 grunt 1492 / Java 108 switches from the guard
tower to a knight and refuses the east head of a fresh twenty-byte replacement
at `(28,37)`. The replacement pays Move through timer one on fixture 285.
Native then holds position and promotes Attack `2539/3` on fixture 286 while
raw collision state clears, drains timers two and one on fixtures 287 and 288,
and consumes east to `(29,37)` on fixture 289 with nineteen bytes retained.
Java formerly consumed east immediately on fixture 286. The focused causal
twin now matches that entire handoff through the fixture-289 move.

Efficacy receipt
`.bne-test-efficacy/full-saturated-retarget-queued-attack/runs/1b3f0e7292b2a1eb024c5571444354d4e27f5f4b2cca0fd3a1e57f495da155b2`
proves the new lifecycle regression assertion fails on `7f14a7d` and passes on
the candidate. The focused real-data test and all 30 `bne_java.py` tests pass.
The 52-case cycle-400 survey reports 33 clean / 19 divergent / 0 failed, and
both it and the cycle-1,800 survey pass their accepted regression gates. The
executable next-level gate exits zero after 209 Python checks and 95 focused
engine/desktop checks; its command worklist remains 11 comparable scenarios
(6 exact / 5 divergent) without regression or infrastructure failure. The
broader certification lanes remain intentionally incomplete.

The newly exposed blocker remains expansion Human 12, now at fixture 287:
native grunt 1503 is at x=42 while Java is at x=41. The accepted compiler
retains a complete paired position frame and routes the blocker to cadence
analysis.

## Prior release checkpoint — 2026-08-30 (cycle-285 paid mobile wall buffer)

Accepted cycle-1,800 receipt `a39fb431` advances the shared clean horizon from
284 to 285 (earliest divergence 286), preserves every campaign frontier, and
retains the fleet totals of 8 clean / 44 divergent / 0 failed. The 52 per-case
exact prefixes sum to 35,878, one more than the preceding accepted survey. The
receipt is retained under
`.bne-artifacts/runs/a39fb431d703acf5032fae26386cecae16fce68ad68c75805dd77bea0a931047`.
It binds dirty engine-input identity `cc0b86c9` at base revision `5dadf73` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and replayable source capsule
`c160bf8514b3f1e4e18ef10b9ef2778502d0588feff4d1d804515de9d920a1db`.

Behavioral delta: a compact melee route which hard-refused before its residual
park has already paid the first wall writer when its paid-band replacement is
a mobile quarry. When a cardinal committed leg left a diagonal tail, Java now
keeps that writer's complete first wall buffer rather than selecting the
shorter opposite face. After the consumed diagonal opening settles, its
structural route state—one step consumed from the fixed native buffer, a
counterclockwise cardinal head, prior hard refusals, and a live mobile
quarry—also enters the real blocked-chase Attack constructor. It drains
`3,2,1` and transfers the retained heading on the timer-one callback. A
diagonal committed leg with its same-face diagonal tail remains on the
optimized short face. The rule uses route lifecycle, refusal ownership,
heading geometry, target mobility, and the native buffer bound; it contains no
map, fixture, coordinate, cycle, faction, or unit-ID branch.

Proof delta: expansion Human 12 grunt 1506 / Java 94 switches quarry on
fixture 266, writes `SW,S,SE,...`, consumes `SW`, and retains nineteen bytes
with `S` at the cursor. Its residual settles on fixture 282 at Attack
`2539/3`, drains timers two and one on 283 and 284, then consumes `S` to
`(33,39)` on fixture 285 with eighteen bytes and `SE` retained. Java formerly
wrote the optimized three-byte tail and remained at `(33,38)`. Grunt 1479 /
Java 121 is the held-out negative: its diagonal committed leg retains the
ordinary optimized face and no longer regresses at fixture 284.

Efficacy receipt
`.bne-test-efficacy/paid-mobile-first-wall-buffer/runs/fc78564b26bcf21168e9ba6e9ad12f54bf2d2cc09e1e3d7a765ca1fd119133b7`
proves the route/construction regression assertion fails on `5dadf73` and
passes on the candidate. All three focused real-data tests and all 30
`bne_java.py` tests pass. The 52-case cycle-400 survey reports 33 clean / 19
divergent / 0 failed, and both it and the cycle-1,800 survey pass their
accepted regression gates. The executable next-level gate exits zero after
209 Python checks and 95 focused engine/desktop checks; its command worklist
remains 11 comparable scenarios (6 exact / 5 divergent) without regression or
infrastructure failure. The broader certification lanes remain intentionally
incomplete. An exploratory unfiltered engine-module sweep is not an acceptance
gate and remains red at 94 failures among 1,928 tests; this checkpoint does not
claim or consume that separate project-test debt.

The newly exposed blocker is expansion Human 12 at fixture 286: native grunt
1492 is at x=28, while Java is at x=29. The accepted compiler retains a
complete paired position frame and routes the blocker to cadence analysis.

## Prior release checkpoint — 2026-08-30 (cycle-284 aligned chase face)

Accepted cycle-1,800 receipt `886cf418` advances the shared clean horizon from
283 to 284 (earliest divergence 285), preserves every campaign frontier, and
retains the fleet totals of 8 clean / 44 divergent / 0 failed. The 52 per-case
exact prefixes sum to 35,877, 53 more than the preceding accepted survey. The
receipt is retained under
`.bne-artifacts/runs/886cf418598b533e0ccd4acc1f57924f074fac0a28a3f6453b3a9d97f8b253ed`.
It binds dirty engine-input identity `6b2df45c` at base revision `72d073c` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and replayable source capsule
`30238cb511f31f665d6a198c0d9ddfe8cf299a965d236ce541056fb85dffe37e`.

Behavioral delta: a clean paid melee tail which retargets exactly one tile
beyond the melee skirt reuses its completed wrap's reverse wall face only when
the replacement lies off both of the attacker's axes. A replacement aligned
on either axis retains the ordinary wall face. This refines the preceding
chase-return mechanism in target geometry, route lifecycle, and order
provenance, with no map, unit-ID, cycle, coordinate, faction, or arbitrary
route-length exception. The longer Human 13 chase remains a held-out negative.

Proof delta: the earlier off-axis expansion Human 4 footman 1518 / Java 82
still consumes east and retains northeast on fixture 281. The newly matched
aligned footman 1510 / Java 90 is parked at (73,60) with `[SE,SE]` on fixture
280, retargets with Attack timer three on fixture 281, and on fixture 284
draws `[NE,SE]`, consumes northeast to (74,59), retains southeast, and exposes
sequence `2485/1`. Java previously chose the reverse face, found southeast
occupied, and remained at (73,60).

Efficacy receipt
`.bne-test-efficacy/aligned-parked-retarget-wall-face/runs/80a251431bae3b9f947fc2d22a02107329cbfee161b3f2628e7f9e3b69529096`
proves the new aligned assertion fails on `72d073c` and passes on the
candidate. The seven changed real-data tests, all 29
`BattleNetPathFinderTest` tests, and all 30 `bne_java.py` tests pass. The
52-case cycle-400 survey reports 33 clean / 19 divergent / 0 failed and moves
expansion Human 4's next local divergence to fixture 337. The cycle-1,800
survey and accepted regression gate report no regression. The executable
next-level gate exits zero after 209 Python checks and 95 focused
engine/desktop checks. Its command worklist has 11 comparable scenarios (6
exact / 5 divergent) with no regression or infrastructure failure; retained
physical, replay, AI, combat, and campaign certification lanes remain
intentionally incomplete. Remote AI discovery failed closed because the
`i9beef` SSH host key changed; no SSH trust state was modified.

The newly exposed blocker is expansion Human 12 at fixture 285: native grunt
1506 is at y=39, while Java is at y=38. The accepted compiler has a complete
paired position frame and retained Java trace, and routes the blocker to
cadence analysis.

## Prior release checkpoint — 2026-08-30 (cycle-283 cold-chase returns)

Accepted cycle-1,800 receipt `30b01eb3` advances the shared clean horizon from
280 to 283 (earliest divergence 284), preserves every campaign frontier, and
retains the fleet totals of 8 clean / 44 divergent / 0 failed. The 52 per-case
exact prefixes sum to 35,824, 216 more than the preceding accepted survey. The
receipt is retained under
`.bne-artifacts/runs/30b01eb363a2be8524a9472c72035f37b7ef08c6105e210c723ec29113f361b1`.
It binds dirty engine-input identity `689d75d2` at base revision `3bad5ac` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and replayable source capsule `52f2ce56`.

Behavioral delta: a behavior-one plain Move returning to its AI home now keeps
the native packed collision ladder when an allied body owns its cached head.
The cold, out-of-range chase handoff parks the cursor and increments the
collision generation; if its replacement route is occupied at collision three,
it retains that route and pays the full fifteen-count Move band. Separately, a
clean paid melee tail which retargets exactly one tile beyond the melee skirt
reuses the reverse wall face selected by its completed wrap instead of drawing
Java's cold forward prefix. A longer Human 13 chase is a held-out negative for
that geometry rule. Both mechanisms are expressed in order provenance, AI home,
chase state, collision ownership, target geometry, and route lifecycle, with no
map, unit-ID, cycle, coordinate, faction, or arbitrary route-length exception.

Proof delta: Human 13 native ogre 1511 / Java 89 stays at (122,28) on fixture
280, parks its route cursor, and raises packed collision to two. On fixture 281
it retains `[NW,NE]`, raises collision to three, and exposes Move `586/15`
without moving. Expansion Human 4 native footman 1518 / Java 82 finishes its
paid melee retarget on fixture 281 by consuming east to (74,61), retaining
northeast, and exposing sequence `2485/1`; Java previously drew northwest and
moved to (72,60). Human 13 is exact through fixture 493 and expansion Human 4
is exact through fixture 283.

Efficacy receipts
`.bne-test-efficacy/plain-move-home-collision-band/runs/40599f0229e18037fa1b51b116f3b8b0d3063060ea3be1fadad3f170dad7dee8`
and
`.bne-test-efficacy/clean-wrap-reverse-face/runs/4012b5a19cb90e97e05623db805e0dad5ba137a7adc5a2befe63eacc929d85fe`
prove the new focused assertions fail on `3bad5ac` and pass on the candidate.
Both changed real-data classes, `BattleNetPathFinderTest`, and all 30
`bne_java.py` tests pass. The 52-case cycle-400 and cycle-1,800 surveys report
no regression, and the accepted regression gate passes. The executable
next-level gate exits zero after 209 Python checks and 95 focused
engine/desktop checks. Its command worklist has 11 comparable scenarios (6
exact / 5 divergent) with no regression or infrastructure failure; retained
physical, replay, AI, combat, and campaign certification lanes remain
intentionally incomplete. Remote AI discovery failed closed because the
`i9beef` SSH host key changed; no SSH trust state was modified.

The newly exposed blocker is expansion Human 4 at fixture 284: native footman
1510 is at (74,59), while Java is at (73,60). The accepted compiler has a
complete paired position frame and retained Java trace, and routes the blocker
to cadence analysis.

## Prior release checkpoint — 2026-08-30 (cycle-280 paid-wrap handoff)

Accepted cycle-1,800 receipt `9db0c4cd` advances the shared clean horizon from
274 to 280 (earliest divergence 281), preserves every campaign frontier, and
retains the fleet totals of 8 clean / 44 divergent / 0 failed. The 52 per-case
exact prefixes sum to 35,608, 37 more than the preceding accepted survey. The
receipt is retained under
`.bne-artifacts/runs/9db0c4cd98e1ab428741095dbbb2b97f2298874a61a2995c90a720ac887d02ae`.
It binds dirty engine-input identity `89cb7750` at base revision `3358394` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and replayable source capsule `0a1565c1`.

Behavioral delta: native collision ownership now survives the complete family
of saturated chase handoffs exposed from fixtures 75 through 280. A one-byte
near-building footprint park records the origin of its collision generation,
so a later behavior-one regroup order releases only that retired generation;
generic Human 13 collision surrogates remain intact. Patrol -> Attack preserves
its packed collision generation through the first chase refill and releases it
when the committed residual hands control back to Attack. A saturated mobile
retarget promotes its retained wall face into the route head and pays the full
Move band before waking. Finally, a compact completed-wrap tail which parks
after its committed stride returns to the paid wall writer: collision-marked
moving formation bodies remain hard, and a successful offered-target wake may
retain collision one. The nearer saturated cardinal park enters its
active-order Attack callback instead of waiting for Java's generic decision
gate. These rules use order provenance, route lifecycle, collision generation,
target kind, offer identity, and moving-body state; production branches contain
no map, unit-ID, cycle, coordinate, faction, or arbitrary route exception.

Proof delta: expansion Human 12 native grunt 1496 / Java 104 now stays at
(36,39) through fixture 275 after its continued route raises collision three
and owns Move 15..1. The earlier fixture-199 regroup collision, fixture-75
Patrol collision, and fixture-88 Attack handback witnesses also match. On
fixture 279 native slot 1504 / Java 96 parks at (30,40), while native slot 1517
/ Java 83 parks at (31,38); offered native slot 1512 / Java 88 retains
collision one. On fixture 280 slot 1504 enters Attack `2539/3` without moving,
and slot 1517 consumes southeast to (32,39) while retaining nineteen route
headings. Expansion Human 12 is consequently exact through fixture 284, with
its next local divergence at 285.

Four efficacy receipts prove each added assertion fails on `3358394` and passes
on the candidate:
`.bne-test-efficacy/saturated-retarget-residual-park/runs/93e809e7bc151ef18022211084939496816c4e42e5efeb921ca3cfcd17574817`,
`.bne-test-efficacy/regroup-order-collision-release/runs/b1c1ae0114b3aae330f61f08a024bf882683f89bd647090bbd65b1fd05db05a4`,
`.bne-test-efficacy/patrol-attack-collision-handoff/runs/4496cc96aacb700f8360cbbd001529eb81b0760e26f09015481d91dfd1abd055`,
and
`.bne-test-efficacy/paid-wrap-collision-wall/runs/2eb041510a106e2d62a5cd7f11892caf8a7fbec51e802658b3f65882236afcdc`.
All changed real-data classes, `BattleNetPathFinderTest`, and all 30
`bne_java.py` tests pass. The executable next-level gate also passes its 209
Python checks and 95 focused engine/desktop checks; its retained physical,
replay, AI, combat, and campaign proof lanes remain intentionally incomplete.
The broad engine suite reports 1,924 tests with the same 94 known synthetic
failures and 196 skips; none belong to a changed focused class.

The accepted compiler retains two tied blockers at fixture 281. Human 13 native
ogre 1511 is at (122,28), while Java is at (123,27); expansion Human 4 native
footman 1518 is at (74,61), while Java is at (72,60). Both have complete paired
frames and retained Java traces, and both route next to cadence analysis.

## Prior release checkpoint — 2026-08-30 (cycle-271 chase handoffs)

Accepted cycle-1,800 receipt `d71aefea` advances the shared clean horizon from
270 to 274 (earliest divergence 275), preserves every other campaign frontier,
and retains the fleet totals of 8 clean / 44 divergent / 0 failed. The 52
per-case exact prefixes sum to 35,571, four more than the preceding accepted
survey. The receipt is retained under
`.bne-artifacts/runs/d71aefeab9a6ad01883c8c94b0f2c8dbbcfa0730cb4104371d54b82363431df5`.
It binds dirty engine-input identity `64248823` at base revision `b309e43` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and replayable source capsule `c262f1e4`.

Behavioral delta: two independent chase handoffs now distinguish route
ownership from head occupancy. First, a completed Attack constructor transfers
a retained building route directly to Move when its cached head is free; only
an occupied head parks and redraws the route-index-twenty replay. Second, a
saturated melee chase which changes from a building quarry to a mobile quarry
as its residual settles continues the already-paid clockwise wall face rather
than drawing a cold Bresenham prefix. That continuation uses native's split
occupancy view: a Move-action ally with a live route and raw collision zero is
soft to wall tracing even when Java's separate refusal proxy remains, but stays
hard to optimization and to the later movement probe. Replacing the quarry
retires the old collision generation before that probe. Both rules are
expressed in route ownership, target kind, residual settlement, movement action,
collision generation, and live occupancy, with no map, unit-ID, cycle,
coordinate, faction, or arbitrary route-length exception.

Proof delta: expansion Human 12 native grunt 1503 / Java 97 retains southeast
behind its fixture-252 east leg. On fixture 271 the square is free, so the
timer-one handoff consumes southeast, commits (40,38)→(41,39), and leaves ten
route bytes without opening a collision generation. Independently, native
grunt 1492 / Java 108 settles at (28,37), replaces guard tower 1483 with the
knight at (30,43), and writes the complete route
`E,E,E,SE,SE,SE,E,E,E,NE,NE,NE,SE,S,SE,SE,S,S,SW,SW`. The occupied east head
is retained, the new collision generation becomes one, and Move starts at
timer fifteen without changing tile. Efficacy receipts
`.bne-test-efficacy/building-free-retained-head/runs/47a5e8cf3f7e41054fd86f360da2f72a98b532a78db14ad7c7f6a81ab6bcb0a8`
and
`.bne-test-efficacy/saturated-building-mobile-wall-continuation/runs/b50b1edbd4e62964b2d93ae5c30c68b3d8328e3d123600207e3cb774aa366ada`
prove the focused assertions fail on `b309e43` and pass on the candidate. The
two focused real-data classes, `BattleNetPathFinderTest`, and all 30
`bne_java.py` tests pass.

The newly exposed blocker remains expansion Human 12, now at fixture 275:
native grunt 1496 is at (36,39), while Java is at (37,38). The accepted
compiler routes this position transition to cadence analysis.

## Prior release checkpoint — 2026-08-30 (assault-patrol worker refusal)

Accepted cycle-1,800 receipt `d93dd71c` advances the shared clean horizon from
269 to 270 (earliest divergence 271), preserves every other campaign frontier,
and retains the fleet totals of 8 clean / 44 divergent / 0 failed. The 52
per-case exact prefixes sum to 35,567, one more than the preceding accepted
survey. The receipt is retained under
`.bne-artifacts/runs/d93dd71c6dec904332d50783c71030c3d1ddc80f2d4e0445add05d9a48840425`.
It binds dirty engine-input identity `5db0d938` at base revision `2e6cb19` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and replayable source capsule `fd555406`.

Behavioral delta: a behavior-two land Patrol may draw a fresh point route
through an allied harvester whose Move body is mid-stride. If restored live
occupancy still owns the first cached heading, retail retains the complete
route, raises the patrol's collision generation from zero to one, and pays one
fifteen-count Move band rather than clearing and redrawing the route on every
visit. Java's borrowed `Patrol -> Move -> Patrol` call seam now preserves that
native Move cursor, and the Patrol delay counts it down in lockstep. The rule
is expressed entirely in order provenance, AI behavior, movement layer,
alliance, worker action, route generation, collision state, and sub-tile
motion; it contains no map, unit-ID, cycle, coordinate, or faction exception.

Proof delta: expansion Human 12 ogre 1356 finishes its prior east route on
fixture 240 and constructs Patrol through fixture 254. On fixture 255 it
stores `[NW,NE]` toward its behavior-two home while allied harvesting peon
1386 owns (10,85) mid-stride. Native retains route index zero, writes collision
byte `0x10`, and exposes Move `586/15`; the timer falls to one on fixture 269.
On fixture 270 the worker has drained far enough for the ogre to consume NW,
reach (10,85), and retain NE behind route index one. Java unit 244 now follows
that exact route, collision, cursor, timer, and position sequence. Efficacy
receipt
`.bne-test-efficacy/assault-patrol-worker/runs/ae409b95f00a7e95b5033b7102cdf859013a419da0fce1104099e3d28a574dfb`
proves `XHuman12PatrolWorkerRefusalRealDataTest` assertion-fails on `2e6cb19`
and passes on the candidate. The full Patrol-focused test family and all 30
`bne_java.py` tests pass.

The newly exposed blocker remains expansion Human 12, now at fixture 271:
native grunt 1492 is one tile north of Java, while native grunt 1503 is one
tile southeast of Java. The accepted compiler routes the complete three-field
position frame to cadence analysis.

## Prior release checkpoint — 2026-08-30 (collided regroup-worker refusal)

Accepted cycle-1,800 receipt `839b6bf9` advances the shared clean horizon from
267 to 269 (earliest divergence 270), preserves every other campaign frontier,
and retains the fleet totals of 8 clean / 44 divergent / 0 failed. The 52
per-case exact prefixes sum to 35,566, two more than the authenticated baseline
survey. The receipt is retained under
`.bne-artifacts/runs/839b6bf9c9bfc2f3c84e5a7d6c6cb6d2985040bd9adf49fe4269230606e4ff3b`.
It binds dirty engine-input identity `971ce88b` at base revision `7cb55a0` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and replayable source capsule `8cf4be6f`.

Behavioral delta: the fresh route of a recurring behavior-one land regroup may
plan cooperatively through a moving harvest worker only before the mover owns a
collision generation. Its first occupied head retains the cooperative route
and raises collision zero to one. If that cached route later wakes behind a
moving worker which already owns a collision generation, retail parks the
route at cursor 20, advances the mover's packed collision generation without
entering the fifteen-refusal band, and leaves a one-count Move body. The next
route query keeps the collided worker solid; an empty replacement ends the
plain Move. The rule is expressed entirely in native order, AI behavior, route,
movement-layer, ownership, and collision state, with no map, unit-ID, cycle,
coordinate, faction, or arbitrary route-length exception.

Proof delta: expansion Human 12 axethrower 1359 stores `N,NE,SE,E,E` at
fixture 252, refuses its occupied north head, and carries raw collision byte
`0x10`. At fixture 266 peasant 1385 occupies (12,88) with collision `0x40`.
The fixture-267 retry leaves Move visible, writes the axethrower's collision
byte as `0x20`, parks its route cursor at 20, and holds animation timer one;
fixture 268 then has an empty route and both native action and order Still.
The authenticated branch trace identifies the final order writer at
`0x00453097`, reached from the failed Move path at `0x0043789a`. Java unit 241
now follows all of those transitions, eliminating the last cycle-268 finding.
The focused real-data test passes, and efficacy receipt
`.bne-test-efficacy/collided-regroup-worker/runs/808ff729bde90a6d8a60ef55a70d647c1e12d3ad6f9e29f407f90a85bf26b5e5`
proves its extended assertions fail on `7cb55a0` and pass on the candidate.
All 30 `bne_java.py` tests pass. A 122-test synthetic movement comparison keeps
the same 113 passes and nine pre-existing failures on `7cb55a0` and the
candidate; the fixed 52-case semantic gate is the authoritative regression
proof.

The newly exposed blocker is expansion Human 12 native ogre 1356 at fixture
270: native is at (10,85), while Java is at (11,86). The accepted compiler
routes this position transition to cadence analysis.

## Prior release checkpoint — 2026-08-30 (promotion-pass regroup occupancy)

Accepted cycle-1,800 receipt `6aa99cad` preserves the shared clean horizon at
267 (earliest divergence 268), every individual campaign frontier, and the
cycle-1,800 totals of 8 clean / 44 divergent / 0 failed. Aggregate exact cycles
rise from 35,564 to 35,608. The receipt is retained under
`.bne-artifacts/runs/6aa99cad72a6aae3cec5f0d244da57d6fa0487f8d1f0471d29af21c9f79587ce`.
It binds dirty engine-input identity `5e3dc64c` at base revision `8370ac3` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`
and includes a replayable source capsule.

Behavioral delta: a behavior-one land ally promoted from Still to a regroup
Move is soft to wall following on the promotion pass, while its two-tick
pending-Move delay is freshly armed. After that delay falls to one, the coarse
order can already be Move while the unit is still executing native action
state 2 (Still); it remains solid until the Move sequence actually begins.
Normal moving-body, collision-nibble, lifecycle, and refusal soft-clear rules
are unchanged. The rule is structural and contains no map, unit, cycle,
coordinate, faction, or arbitrary route-length exception.

Proof delta: at expansion Human 12 fixture 204, native's candidate cell
(12,87) resolves to slot 1358. The grunt has coarse order Move but action byte
2, so `0x4501c8` keeps occupancy flag `0x100` set. Native therefore rejects
(12,85), (12,86), (12,87), (11,87), and (10,87) before accepting (10,86).
Its raw wall trace
`[6,6,6,5,3,6,5,3,2,2,1,0,2,2,1,0,0,0]` optimizes to the sealed sixteen-byte
route `[6,6,6,5,5,4,3,2,2,1,1,2,1,0,0,0]`; worker 1386 consumes its first
west heading on that fixture. Slot 1363 independently authenticates the
positive same-promotion-pass soft arm at fixture 199. The focused 44-test
movement family passes, and efficacy receipt
`.bne-test-efficacy/fresh-regroup-occupancy/runs/4a5b595e6204bb60209260a3c1233b3b4de4a5675ec2cff4b51a7ea68cf68a61`
proves the new assertion fails on `8370ac3` and passes on the candidate.
Expansion Human 12's cycle-268 findings fall from three to one: both worker
position fields are exact, leaving only native slot 1359's Still-versus-Move
handoff. The previously rejected broad axethrower-cluster hypothesis remains
excluded; the next investigation should isolate this exact order transition
with a branch witness.

## Prior release checkpoint — 2026-08-30 (saturated chase first-refusal refill)

Accepted follow-on receipt `98601a77` preserves the shared clean horizon at
267 (earliest divergence 268), all individual campaign frontiers, and the
cycle-1,800 totals of 8 clean / 44 divergent / 0 failed and 35,564 aggregate
exact cycles. It reduces expansion Human 12's cycle-268 findings from four to
three by eliminating the native-slot-1481 grunt's north/south position split.
The receipt is retained under
`.bne-candidate-evidence/first-saturated-progressive-52x1800/gate-artifacts/runs/98601a77d55f35485ec85db6e454ab4759cb09a9d5b4a79b0aac741d7f9f1d5c`.
It binds dirty engine-input identity `b196455c` at base revision `2763222` to
the 220,273,648-byte pack with SHA-256
`3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`.

Behavioral delta: when a collision-free melee chase has consumed at least
three headings from a saturated twenty-byte route and hard-refuses its next
heading for the first time, retail gives the ensuing target-route refill one
direct progressive face. Java now replaces a multi-byte cold plan only when
its first heading makes no progress, the direct Bresenham heading is free, and
that heading is strictly closer to the live mobile quarry. The rule is native
route-buffer and refusal provenance; it contains no map, unit, cycle,
coordinate, faction, or arbitrary route-length exception.

Proof delta: expansion Human 12 native slot 1481 and Java unit 119 are exact at
(31,40) through fixture 267 after three headings from a saturated route. Their
blocked northeast tail owns collision/refusal 1/1 and an empty parked cursor.
On fixture 268 native stores and consumes the complete one-byte southwest
refill, reaching (30,41); Java previously cold-searched a northwest-led wall
route and reached (30,39). The focused 22-test collision/refill class passes,
and efficacy receipt
`.bne-test-efficacy/first-saturated-progressive/runs/8ca7724e3df996c5679aae2fbaa7a20cce6f16b2994796b00cbbff8fc4344636`
proves the new assertion fails on `2763222` and passes on the candidate. All
30 `bne_java.py` tests pass. The gate also treats identical content-addressed
packs as the same input after a machine transfer when their absolute paths
differ, while retaining hash-and-byte fail-closed checks.

The broad reactor test was attempted once under `CHONKCRAFT_ASSET_PACK`; that
mixed BNE media into synthetic default-profile tests and produced 94 failures,
50 errors, and 196 skips, including sandbox-denied UDP cases. It is not a
valid acceptance profile for this change. The focused BNE class and fixed
52-case semantic gate above are green. The prior tanker checkpoint below
remains the latest complete release-scale player-contract, lockstep, pack,
provenance, and BNE-media validation bundle.

The remaining shared-boundary findings are expansion Human 12 native slot
1359's Still-versus-Move handoff and native slot 1386's harvest-route position
split at cycle 268. The prior axethrower cluster hypothesis is rejected; the
next actionable family is the worker's earlier harvest path choice.

## Prior release checkpoint — 2026-08-30 (tanker anchor spread)

Accepted cycle-1,800 run `85cc4484` preserves the shared clean horizon at 267
(earliest divergence 268), with 8 clean / 44 divergent / 0 failed. Durable
cycle-400 run `38cf0658` raises the short-fleet count to 32 clean / 20
divergent / 0 failed. Aggregate per-map frontiers are 19,175 at cycle 400 and
35,564 at cycle 1,800. Human 7 advances from exact through cycle 281 to 404;
every other map frontier is unchanged. The receipts are retained under
`.bne-artifacts/runs/38cf06586e6ccc1f8fca01fec2387dbf74431033161f7379ca9841847d0ddcb4`
and
`.bne-artifacts/runs/85cc44848855866b6496f747323cc1f67c2152ba1e19cd616ae1f9f9414c8659`.
Both bind dirty engine-input identity `5b700bb4` at base revision `f9552529`
to replayable source capsule `500a66e4`.

Behavioral delta: the resource-order SpreadUnit search for a loaded oil tanker
tests route-grid anchors. It does not reject an anchor because the tanker's
rendered second hull cell touches coast. The later MoveToDepot handoff can
therefore classify the shoreline correctly, replace that spread point with
the refinery edge, and retain the native return route. The rule is structural
and contains no map, unit, cycle, coordinate, or faction exception.

Proof delta: Human 7 tanker 1524 accepts anchor (68,69), stores spread point
(69,69), then replaces it with refinery edge (72,72) and writes
`[SE,SE,SE,S,SE]`. It moves south at fixture 282 instead of stopping early at
(68,68). Expansion Orc 8 independently authenticates the ordinary refinery
handoff from spread point (97,65) to edge (89,71). Orc 10 and expansion Human
6 retain their vertical shoreline rule, normalizing the even coordinate while
preserving the odd one. The complete short fleet gains 119 aggregate cycles
and one clean map; the long fleet gains 123 aggregate cycles with zero
regression.

Milestone acceptance repeated all 18 player-contract lanes, all 39 clean and
adverse lockstep cases through cycle 1,800, and a real two-process 180-cycle
match with terminal hash `1b9dd8ad7b9a1f51`. Pack parity passed all eight
checks, including all 20 music tracks with a worst decoded comparison of 22.2
dB. The matched BNE-media suite accounts for 2,835 tests: 2,805 ran, 30
release-format skips matched exactly, and the exact 110 expected specification
failures were unchanged. The separately measured data-free profile ran 1,622,
skipped 1,213, and retained its exact 89 expected failures. The pinned
662,253,608-byte source archive and signed pack passed the full 1,412-asset and
1,411-payload provenance inventory.

The next shared-boundary blocker remains expansion Human 12 at cycle 268.
Human 13 and expansion Human 4 follow at 281, Orc 8 at 289, and Human 11 and
expansion Orc 11 at 300.

## Prior release checkpoint — 2026-08-30 (saturated fresh laden-return route)

Accepted cycle-1,800 run `eb371304` preserves the shared clean horizon at 267
(earliest divergence 268), with 8 clean / 44 divergent / 0 failed. Durable
cycle-400 run `3f545407` raises the short-fleet count to 31 clean / 21
divergent / 0 failed. Aggregate per-map frontiers are 19,056 at cycle 400 and
35,441 at cycle 1,800. Expansion Orc 6 advances from exact through cycle 284
to 520; every other map frontier is unchanged. The receipts are retained
under
`.bne-artifacts/runs/3f5454073df84d15d81d305f438c8d10430a7ac088b7e8b854ee224b76e60e6f`
and
`.bne-artifacts/runs/eb3713045a55f8d83174a300af781d4b28417657441e90519289197191151c6d`.
Both bind dirty engine-input identity `1fc94920` at base revision `5c5b5d5a`
to replayable source capsule `1d797910`.

Behavioral delta: a loaded land worker that has reached at least eight sticky
refusals may generate a fresh return route whose first step is occupied by a
cooperating moving ally. Retail parks that unconsumed route at cursor 20,
increments the sticky refusal, and pays Move 15..1 before replanning. Java now
preserves the same empty-route Move sequence across the borrowed harvest walk
and gives every saturated fresh return route the full hard-refusal band. The
rule is structural and contains no map, unit, cycle, coordinate, or faction
exception.

Proof delta: expansion Orc 6's loaded peon 1516 independently generates
`[W,W,NW,N,NE,E,SE]` at fixture 270 and `[N,N,N,N,NW]` at fixture 285. Their
first headings are occupied by cooperating moving allies; native parks both at
cursor 20 with refusals 9 and 10 respectively and Move timer 15, then steps
north when the second blocker opens at fixture 300. Expansion Human 12's
loaded peon 1550 separately parks fresh `[N,N,N,N,NW,N]` at fixture 253 with
refusal 10 and Move timer 15 behind an ordinary moving ally. Expansion Human
10 peon 1588 and Human 14 peon 1539 retain and retry consumed residual return
routes, while expansion Orc 6's preceding queued-return convoy route remains
intact. The focused expansion Orc 6 trace is exact through all 400 shared
cycles. The complete short fleet gains 116 aggregate cycles and one clean map;
the long fleet gains 236 aggregate cycles with zero regression.

Milestone acceptance repeated all 18 player-contract lanes, all 39 clean and
adverse lockstep cases through cycle 1,800, and a real two-process 180-cycle
match with terminal hash `1b9dd8ad7b9a1f51`. Pack parity passed all eight
checks, including all 20 music tracks with a worst decoded comparison of 22.2
dB. The matched BNE-media suite accounts for 2,834 tests: 2,804 ran, 30
release-format skips matched exactly, and the exact 110 expected specification
failures were unchanged. The pinned 662,253,608-byte source archive and signed
pack also passed the full 1,412-asset provenance and payload inventory.

The next shared-boundary blocker remains expansion Human 12 at cycle 268.
Human 13 and expansion Human 4 follow at 281, Human 7 at 282, Orc 8 at 289,
Human 11 at 300, and expansion Orc 11 at 300.

## Prior release checkpoint — 2026-08-30 (unreachable-attack Still handoff)

Accepted cycle-1,800 run `950fd9d2` preserves the shared clean horizon at 267
(earliest divergence 268), with 8 clean / 44 divergent / 0 failed. Durable
cycle-400 run `02a525ea` retains 30 clean / 22 divergent / 0 failed. Aggregate
per-map frontiers are 18,940 at cycle 400 and 35,205 at cycle 1,800. Expansion
Orc 11 advances from exact through cycle 283 to 299; every other map frontier
is unchanged. The receipts are retained under
`.bne-artifacts/runs/02a525eabaecc1cd131d38f07de72435cbe2c5b8be59dfbe99ed5f6b4df50b9f`
and
`.bne-artifacts/runs/950fd9d2aeee838ab58dfc69ea60138fa446bd8e3be87213ff62d4e9baabab9f`.
Both bind dirty engine-input identity `f2c8cfc9` at base revision `ec6273da`
to replayable source capsule `ce0786ee`.

Behavioral delta: when a land attacker's route request returns empty and the
quarry is unreachable over terrain, retail's GiveOrder exit installs the
unit's Still program with timer one. Java now preserves that direct handoff
instead of rebuilding Still with the generic three-tick order constructor.
The first ordinary idle opcode therefore runs on the next action visit and the
shared random stream remains aligned. The rule is structural and contains no
map, unit, cycle, coordinate, or faction exception.

Proof delta: expansion Orc 11 axethrower 1517 winds Attack 3,2,1 through
fixtures 250..252, proves the empty terrain-unreachable route on 253, and
records Still@825/1, OP0@4983/1, then 4985/4. Shoreline axethrowers 1507 and
1498 independently authenticate the same timer-one GiveOrder handoff at
fixtures 155 and 156. Expansion Human 4's reachable packed axethrower retains
Attack and refills its route; Human 13 and expansion Human 4 ordinary attack
ends retain their three-tick Still construction; expansion Human 10's boxed
defender keeps its separate same-visit retry/release behavior. The focused
case moves from first divergence 284 to 300, and both complete fleets gain
sixteen aggregate cycles with zero regression.

Milestone acceptance repeated all 18 player-contract lanes, all 39 clean and
adverse lockstep cases through cycle 1,800, and a real two-process 180-cycle
match with terminal hash `1b9dd8ad7b9a1f51`. Pack parity passed all eight
checks, including all 20 music tracks with a worst decoded comparison of 22.2
dB. The matched BNE-media suite accounts for 2,832 tests: 2,802 ran, 30
release-format skips matched exactly, and the exact 110 expected specification
failures were unchanged.

The next shared-boundary blocker remains expansion Human 12 at cycle 268.
Human 13 and expansion Human 4 follow at 281, Human 7 at 282, expansion Orc 6
at 285, Orc 8 at 289, and Human 11 and expansion Orc 11 at 300.

## Prior release checkpoint — 2026-08-30 (queued-return convoy route)

Accepted cycle-1,800 run `3fbd5a8c` preserves the shared clean horizon at 267
(earliest divergence 268), with 8 clean / 44 divergent / 0 failed. Durable
cycle-400 run `bd3e9f5e` retains 30 clean / 22 divergent / 0 failed. Aggregate
per-map frontiers are 18,924 at cycle 400 and 35,189 at cycle 1,800. Expansion
Orc 6 advances from exact through cycle 273 to 284; every other map frontier is
unchanged. The receipts are retained under
`.bne-artifacts/runs/bd3e9f5ebb4acfe1de15a39ee07cb615ba96fd3db41eff29da4f9f0011701925`
and
`.bne-artifacts/runs/3fbd5a8c0dd80ba5260fee49363fb4fb744fb0fd143376383294bde513b14386`.
Both bind dirty engine-input identity `897305ed` at base revision `0b6eb4ee`
to replayable source capsule `628f6044`.

Behavioral delta: on a laden land worker's direct depot ray, a collision-free
queued same-depot gold returner is absent from both native path views when the
preceding ray square already contains a stopped, collision-bearing same-depot
returner. A queued worker that is the first obstruction, or one reached later
on an otherwise clear ray, remains solid. The rule is structural and contains
no map, unit, cycle, coordinate, or faction exception.

Proof delta: expansion Orc 6 slots 1515/1516/1517 write `[NW,NE]` at fixture
252 and consume the northeast tail at 274; expansion Human 12 slots
1554/1550/1552 independently write `[NE,NW]` at fixture 225. The latter also
authenticates native tail invalidation and the opposite hall-edge retarget at
247, followed by the north redraw at 248. Expansion Human 10's direct first
queued blocker, expansion Orc 12's later clear-ray blocker, and the existing
laden paid-wake cases are held-out negatives. The complete fleets gain eleven
aggregate cycles with zero regression.

Milestone acceptance repeated all 18 player-contract lanes, all 39 clean and
adverse lockstep cases through cycle 1,800, and a real two-process 180-cycle
match with terminal hash `1b9dd8ad7b9a1f51`. Pack parity passed all eight
checks, including all 20 music tracks with a worst decoded comparison of 22.2
dB. The matched BNE-media suite accounts for 2,832 tests: 2,802 ran, 30
release-format skips matched exactly, and the exact 110 expected specification
failures were unchanged.

The next shared-boundary blocker remains expansion Human 12 at cycle 268.
Human 13 and expansion Human 4 follow at 281, Human 7 at 282, expansion Orc 11
at 284, expansion Orc 6 at 285, and Orc 8 at 289.

## Prior release checkpoint — 2026-08-30 (plain-Move refusal replacement)

Accepted cycle-1,800 run `152de1e7` preserves the shared clean horizon at 267
(earliest divergence 268), with 8 clean / 44 divergent / 0 failed. Durable
cycle-400 run `592e8de8` retains 30 clean / 22 divergent / 0 failed. Aggregate
per-map frontiers are 18,913 at cycle 400 and 35,178 at cycle 1,800. Human 13
advances from exact through 277 to 280; every other map is unchanged. The
receipts are retained under
`.bne-artifacts/runs/592e8de8fbcdf07e7c2fb991e0782ed034f801c27029b6d2ffe20b32fc6fab8b`
and
`.bne-artifacts/runs/152de1e7236d7f4e33e43a681901b15b8847059c7d174e268c5ff71b9035b5ed`.
Both bind dirty engine-input identity `b9bfdd8a` at base revision `a9c6a91c`
to replayable source capsule `f53764f9`.

Behavioral delta: after a terrain-only plain-Move buffer refuses a later byte,
its immediate replacement route temporarily removes an allied, non-building
plain-Move body from both path views only while that ally is actively moving,
still has a route, and has collision zero. Settled or collided allies remain
solid, and the one-shot provenance prevents the replacement from re-entering
the initial terrain-only line writer. The rule is structural and contains no
map, unit, cycle, coordinate, or faction exception.

Proof delta: Human 13's eastern ogre stores eleven north bytes, refuses the
second, and replaces them with `[NW,NE]`; its southern ogre independently
refuses the second byte of a nine-byte line and replaces it with
`[NW,NE,NW,W]`. The actively walking allied grunt is absent from the replacement
view while the settled collision-bearing ogre remains a wall. The witnesses'
initial full terrain lines and the sibling ogre's occupied-first-face
`[N,NW,W]` detour remain held-out negatives, and the existing non-cooperative
residual free-compass referee is green. The focused comparison advances Human
13's next divergence from 278 to 281; both complete fleets gain three aggregate
cycles with zero regression.

Milestone acceptance repeated all 18 player-contract lanes, all 39 clean and
adverse lockstep cases through cycle 1,800, and a real two-process 180-cycle
match with terminal hash `1b9dd8ad7b9a1f51`. Pack parity passed all eight
checks, including all 20 music tracks with a worst decoded comparison of 22.2
dB. The matched BNE-media suite accounts for 2,830 tests: 2,800 ran, 30
release-format skips matched exactly, and the exact 110 expected specification
failures were unchanged.

The next shared-boundary blocker remains expansion Human 12 at cycle 268.
Expansion Orc 6 follows at 274; Human 13 and expansion Human 4 are tied at 281,
then Human 7 at 282, expansion Orc 11 at 284, and Orc 8 at 289.

## Prior release checkpoint — 2026-08-30 (laden-return timer-one)

Accepted cycle-1,800 run `8a25ffb7` preserves the shared clean horizon at 267
(earliest divergence 268), with 8 clean / 44 divergent / 0 failed. Durable
cycle-400 run `eafece73` raises the clean count to 30 / 22 divergent / 0 failed.
Aggregate per-map frontiers are 18,910 at cycle 400 and 35,175 at cycle 1,800.
Expansion Human 7 advances from exact through 285 to 302 and Orc 5 from 288 to
453; every other map is unchanged. The receipts are retained under
`.bne-artifacts/runs/eafece73a7ad91931a83a58d30267f2ca31117527a7ffdad4f5ed4cc570297df`
and `.bne-artifacts/runs/8a25ffb7de0725742baf7736a2b27355c4d166274b1e502d39beab0c2d04b76a`.
Both bind dirty engine-input identity `16015d02` at base revision `2d600406` to
replayable source capsule `4aeffc43`.

Behavioral delta: when a laden land worker's paid cooperative return wait
reaches timer one, the retry is that same action visit. If the cached next
square has opened, the worker consumes the retained heading immediately while
preserving its collision generation. A still-present laden convoy renews the
full native Move 15..1 band, while any other remaining obstruction advances
the collision and parks the route for a next-visit replan. The rule is
structural and contains no map, unit, cycle, coordinate, or faction exception.

Proof delta: expansion Human 7 peon 1451 retains `[E,NE,E]` at route index 1
and collision 1 through timer one, then takes NE on fixture 286. Orc 5 peasant
1529 independently retains `[SE,SE,E]` and takes SE on fixture 289 without a
new collision. Held-out expansion Orc 6 advances its uninterrupted cached
route on the prior fixture-274 cadence. Existing expansion Human 8 proves a
still-blocked timer-one wake parks and replans, while expansion Human 10 proves
a continuing laden convoy renews the paid band. The focused three-test referee
and both held-out negative referees are green; the complete fleets gain 129
and 182 aggregate cycles with zero regression.

Milestone acceptance repeated all 18 player-contract lanes, all 39 clean and
adverse lockstep cases through cycle 1,800, and a real two-process 180-cycle
match with terminal hash `1b9dd8ad7b9a1f51`. Pack parity passed all eight
checks. The matched BNE-media suite accounts for 2,830 tests: 2,800 ran, 30
release-format skips matched exactly, and the exact 110 expected specification
failures were unchanged. The data-free profile separately ran 1,622, skipped
the exact 1,208 authenticated checks, and retained its exact 89 failures.

The next shared-boundary blocker remains expansion Human 12 at cycle 268.
Expansion Orc 6 follows at 274, Human 13 at 278, expansion Human 4 at 281,
Human 7 at 282, expansion Orc 11 at 284, and Orc 8 at 289.

## Prior release checkpoint — 2026-08-30 (corpse ownership)

Accepted cycle-1,800 run `3fc6c609` preserves the shared clean horizon at 267
(earliest divergence 268), with 8 clean / 44 divergent / 0 failed. Durable
cycle-400 run `34910153` likewise keeps 29 clean / 23 divergent / 0 failed.
Aggregate per-map frontiers are 18,781 at cycle 400 and 34,993 at cycle 1,800.
Expansion Human 2 advances from exact through 349 to 351; every other map is
unchanged. The receipts are retained under
`.bne-artifacts/runs/34910153594b07a318723c575a6155ef455728147089b86779a8aa3a2c3dbdea`
and `.bne-artifacts/runs/3fc6c609253af1b97345fd4c091857e9d07add9f80017a76ab0605ef837d54e4`.
Both bind dirty engine-input identity `aebf63ec` at base revision `8dc34926` to
replayable source capsule `7f1eb2d1`.

Behavioral delta: a newly installed corpse or building-rubble record retains
the living unit's owner through its first held decay frame, then becomes
player 15 on the first decay-frame transition. The owner has already paid the
unit-roster removal, so only the fading record changes owner. Revealers are
timed vision records rather than scenery and remain owned; balloon and
zeppelin deaths that install no corpse still disappear directly. The rule is
structural and contains no map, unit, cycle, coordinate, or faction exception.

Proof delta: all 76 mobile-body owner handoffs observable inside the sealed
cycle-1,800 fleet and all 13 building-rubble handoffs occur on that first frame
transition across ten campaigns. All 77 mobile-body creations initially keep
the living owner, and all 70 bodies that expire within the capture are neutral
before release. No unrelated live type transition hands ownership to neutral.
Focused tests hold the first-frame boundary and revealer exclusion. The two
complete fleets each gain two aggregate cycles with zero regression.

Milestone acceptance repeated all 18 player-contract lanes, all 39 clean and
adverse lockstep cases through cycle 1,800, and a real two-process 180-cycle
match with terminal hash `1b9dd8ad7b9a1f51`. Pack parity passed all eight
checks. The matched BNE-media suite accounts for 2,827 tests: 2,797 ran, 30
release-format skips matched exactly, and the exact 110 expected specification
failures were unchanged.

The next shared-boundary blocker remains expansion Human 12 at cycle 268. Its
three simultaneous mismatches have distinct causes. A bounded branch witness
at native `0x453050`, called by the Still handler, proves the slot-1359
axethrower's raw Move-to-Still write at `0x453097` is gated by the alignment
branch at `0x45308a`; this is the already rejected behavior-one home-Move
give-up cluster, so no second production edit was attempted there. Expansion
Orc 6 follows at 274, Human 13 at 278, expansion Orc 11 at 282, expansion Orc 8
at 302, and expansion Human 11 at 320.

## Prior release checkpoint — 2026-08-30 (unified corpses)

Accepted cycle-1,800 run `41c1d87e` preserves the shared clean horizon at 267
(earliest divergence 268), with 8 clean / 44 divergent / 0 failed. The
cycle-400 fleet remains 29 clean / 23 divergent / 0 failed. Aggregate per-map
frontiers are 18,779 at cycle 400 and 34,991 at cycle 1,800. Expansion Orc 11
advances from exact through 281 to 283; every other map is unchanged from the
preceding accepted fleet. The durable cycle-400 and accepted cycle-1,800
receipts are retained under `.bne-artifacts/runs/83be37f2405ab9f8cff923f576a90bcc501313fff7800575ef79dceba352b367`
and `.bne-artifacts/runs/41c1d87e4d12071574455d94cc12834a41b198a346d3f152529a7974fe6a6451`.
The accepted receipt binds engine-input identity `b5287494` to replayable
source capsule `157f3535`, rooted at source revision `a4b0ee72`.

Behavioral delta: generated unit definitions now normalize the legacy
`unit-dead-sea-body` corpse alias onto `unit-human-dead-body`, just as the
existing orc corpse alias already did. All 14 authenticated vessel deaths in
the sealed campaign fleet become native raw type 105, across both factions and
four combat-vessel classes. The 77 authenticated land deaths use the same raw
type. Destroyed buildings retain their sized rubble definitions, balloons and
zeppelins retain no corpse, and the rule contains no map, unit, cycle, or
coordinate exception.

Proof delta: expansion Human 7 supplies one orc destroyer death, expansion Orc
8 supplies human and orc destroyers, and expansion Orc 11 supplies both
destroyer factions plus a battleship and ogre juggernaught. Focused generated-
definition referees cover those four vessel classes and hold 2-by-2 and 3-by-3
building rubble plus no-corpse air units outside the rule. The three-test
focused selection is green, and both complete fleets show the same two-cycle
aggregate gain with no regression.

Milestone acceptance repeated the complete 18-lane player-contract gate with
18 passes and zero skips, failures, blocks, or timeouts. The dedicated network
gate kept all 39 lockstep cases through cycle 1,800 and completed real
two-process startup, map transfer, 180 rendered cycles, and terminal hash
`1b9dd8ad7b9a1f51`. The matched exact-BNE-media suite accounted for all 2,825
tests; the named BNE profile with three private playtest-save referees ran
2,795 with 30 exact release-format skips and retained the exact 110 expected
specification failures. Pack parity passed all eight structural checks against
the source archive whose SHA-256 is sealed in the signed pack, including all 20
music tracks with a worst decoded comparison of 22.2 dB.

The next shared-boundary blocker is expansion Human 12 at 268. Expansion Orc 6
follows at 274, Human 13 at 278, expansion Orc 11 at 282, expansion Orc 8 at
302, and expansion Human 11 at 320. Bounded rejected candidates
remain reverted: blanket and soft visibility variants for Human 12 failed
their native route or held-out negatives; Human 11's wood-stall timing changed
only its target map; the broad all-tree occupancy candidate regressed Human 5;
Human 13's moving-regroup softening advanced that map by one cycle but did not
improve the shared horizon or a meaningful aggregate frontier. A recurring
behavior-one home-Move give-up changed one expansion Human 12 symptom but made
no fleet-level gain and was reverted. The subsequent three-position expansion
Human 12 diagnostic exposed different causes rather than one systemic rule, so
the work switched clusters before production code was changed. A queued
same-depot bypass moved expansion Orc 6 from 274 to 285 but regressed expansion
Human 10 from 347 to 269, so it was rejected at cycle 400 and immediately
reverted.

## Prior release checkpoint — 2026-08-26

Accepted pointer: run `03d44b64` (common clean 254, earliest divergence 255,
gate PASS, clean engine input `a40cd311` at commit `ee974f26`). The full
cycle-1,800 fleet keeps 8 clean / 44 divergent / 0 failed with zero
regressions against `master`. The cycle-400 view remains 27 clean / 25
divergent / 0 failed. Human 8 advances 255 -> 328 and XHuman 12 advances
255 -> 257; XHuman 7's juggernaught patrol handoff keeps the shared horizon
at 254. Engine failure identities remain unchanged (110=110).

Closed this stretch: Orc 8 mine-exit refusal hold (253->289), XOrc 11
walled-quarry drop (253->282), naval beat reissue through the patrol target
chain (XHuman 7 253->255, XHuman 5 256->288).

### Next-ranked blockers beyond 255

- `retail-xorc-08-idle` @261: submarine 1433 (unit-human-submarine) on
  patrol order 5 with seven-NW route parked at ri=6. At fixture 260 native
  arms seq 3469/t15 -- a fifteen-band -- and holds position through 261+.
  Java moves instead. Same band-protocol family as Human 8; the naval
  variant may need its own seq-station mapping.
- `retail-xorc-12-idle` @264: peasant 1394 arriving at depot ring. Order
  promotes 24->25 at f261 with seq 2600/t3 constructor and route parked at
  ri=20 (`[SW,SW,S]` consumed two, one left). Native steps SW onto (29,76)
  at f264 -- the parked heading consumed after constructor. Java x/y off
  by one: either the constructor timing differs or the parked-heading
  consume fires differently. This is the same depot-ring arrival pattern
  as existing `depotRingAction25` code; likely a narrow fix.
  TRACED: Java drains residual one cycle LONGER than native. At internal
  263 Java still shows `moving=1 spent=1 drained=0` while native's
  constructor already started at f261. Java's wake lands at internal 266/267
  with `path=0` then `path=1`, one visit late for the SW step onto (29,76).
  The fix is in the residual-drain arithmetic on the final approach leg:
  compare Java's offsetX/offsetY pixel series against native's pixel column
  `(965,2395)->(962,2398)->(962,2398)` across f258..264; the drain rate or
  step-prime amount differs by exactly one cycle's pixels.
  Drain mechanism located: `walkPixels` (BattleNetMovementSystem:7855)
  sets `setStepDrained(true)` when `reached || (rawX==0 && rawY==0 &&
  !animation().unbreakable())`. The off-by-one is in this arithmetic.

### Harvested cycle-255 work

- `retail-human-08-idle` is closed from 255 through 327. Attack-peasant 1513
  now follows the sealed two Move-15 bands, Attack-3 constructor, and one
  fresh direct heading. The earlier broad cold-park marker remains rejected:
  it regressed the same unit's fixture-188 residual march.
- `retail-xhuman-12-idle` no longer fails on ogre 1356 at 255. Authenticated
  capture proved selector zero chooses guard tower 1429 at (15,67), then the
  native free-square writer normalizes the shared force home to (13,66).
  The next failure is now grunt 1496's movement at 257.
- `retail-xhuman-07-idle` remains the sole cycle-255 blocker: juggernaught
  1573 is Still in Java when retail promotes it to Patrol.

The systemic movement rule retained here is one decision per completed band:
mobile occupants are invisible while choosing the direct heading and become
authoritative only when the physical step accepts or refuses it. The full
fleet gate is required for every extension because the same cold-park shape
also appears in ordinary residual marches where multi-heading wall planning
is still correct.

### Closed this pass (Orc 8 mine-exit refusal hold, 253 -> 289)

Peasant 1504 stalls at (123,86) behind ally 1501 on the only south square.
Native answers each blocked retry with a refusal generation -- Move-start
timer one with route cursor parked at twenty for seven quiet visits
(fixtures 233..239), then the fifteen-count cooperative band armed at 240,
served to expiry even though the blocker steps away at 253; the hauler takes
the square at 255. The port held nothing: the planner returned an empty
route while the face was occupied, installed it silently, and stepped the
same cycle the square opened. Fix in `walkTowards`: when a laden land
returner's fresh plan is empty and the direct face is allied-occupied,
install the one-heading face route so the authenticated refusal ladder owns
the outcome. Regression: `Orc08MineExitRefuseHoldRealDataTest` (checked to
fail without the fix).

### Negative result (do not retry as written)

An empty chase probe is NOT a safe drop condition for auto-acquired attacks.
XHuman 4 axethrowers 1506/1516 park route index twenty through their opening
windup exactly like XOrc 11's 1517, but retail keeps their order alive and
hands them a real one-heading chase route at windup end (fixture 6); Java's
planner answers empty on that visit and only succeeds two visits later, so
the current promote-on-empty plus retry loop is what matches XHuman 4.
Gating a Still drop on `pathLength() == 0` regressed XHuman 4 to cycle 3
even with an order-delay guard. The true XOrc 11 discriminator is whatever
makes retail's Attack handler call the GiveOrder(STILL) epilogue --
writer proved by decision-miner bootstrap capture: instruction `0x00453097`
`mov %bl,0x2e(%esi)` inside `0x452110`'s activation, preceded by
`mov byte [esi+0x2f],0x3c`, and the STILL branch clears the target pointer
at `+0x88`. Next step there is reading the caller chain of `0x00453050`
inside the attack handler, not another watchpoint.

### Open at the shared boundary

- `retail-xorc-11-idle` @253 -- CLOSED this pass, frontier 253 -> 282.
  The give-up keys on terrain-only unreachability of the quarry. A
  terrain-only reachability ask (mobile occupancy stripped, footprint-skirt
  goal) fails for (4,37) -> (10,30) at every probe visit while XHuman 4's
  packed row stays five steps over open ground, so the new arm in stepAttack
  drops only when the post-delay chase probe answers empty AND that question
  says no route exists over any terrain. One measurement lesson is worth
  keeping: BattleNetPathFinder encodes "no route" as FOUND with an empty
  buffer, so the first version of this verdict read `result() == FOUND` and
  silently inverted -- reachability claims must check the heading count,
  never the result code alone. The GiveOrder epilogue at 0x00453097 proved
  by capture stands as the retail mechanism being reproduced. Regression:
  `Xorc11UnreachableQuarryDropRealDataTest` (checked to fail without the
  fix); witness plan and bootstrap capture remain durable under
  `.bne-decision-miner/plans/2da7f89d...` and `.bne-decision-miner/remote/
  b2bc3ae8...`.
- `retail-xhuman-07-idle` @253: destroyer 1562. Native's fifty-cycle naval
  beat reissues Patrol and rewrites BOTH endpoint pairs ((26,28) self,
  (39,33) closest owned oil platform) while reusing behavior six; Java's
  `fireBattleNetNavalPatrolPass` requeues only the stale ai-home point
  (22,27), whose promotion decays to Still. Regression trap: the northern
  destroyer's fixture-106 home rewrite witness lives on this same map, so a
  blanket switch to full-chain recomputation must be proven against both
  hulls; any fallback that can pay the chain's RNG draws when no far
  endpoint exists must stay gated until its native draw cadence is known.
  Sharper still, measured from the sealed record: native leaves 1562 standing
  through the cycle-99, 149 and 199 beats and accepts a reissue only at 249,
  although the hull is Still beside the same rewritten home throughout -- so
  an endpoint-swap arm gated only on "within one stride of the rewrite"
  fires four beats early and moves this map backward. The missing gate is
  whatever makes the beat accept THIS hull at 249; contrast native's
  acceptance at 249 against its silence at 199 (same position, same order,
  same endpoints) before writing any arm. After the swap the sealed hull
  doubles east onto (28,28) around fixture 256-258 with goal (39,33), which
  is the post-fix shape to match.
  Narrowed further this session, all from sealed bytes plus one throwaway
  probe: p6's oil tanker 1571 reaches the platform (39,33) and turns removed
  at exactly fixture 199; Java's `battleNetNavalPatrolTarget` already
  answers target=self, back=platform from fixture 199 onward and
  target=shipyard before, so routing the recurring pass through the existing
  chain reproduces the swap ANSWER but fires it at the 199 beat where native
  decays. No unit-record byte and no world object distinguishes the 199 and
  249 pre-beat states except animation phase -- the accepting gate is
  therefore not in the sealed 152-byte records at all.
  Decisive new fact: BOTH behavior-six destroyers swap at the same beat --
  northern 1570 writes home:=self (24,24) at queue time 249 and starts its
  real east leg onto goal (39,33) at fixture 255, exactly mirroring
  southern 1562 -- so the accepting gate is player-level, not per-hull.
  p6's banks stay zero throughout (no delivery trigger), and no unit or
  world object changes in 200..248 beyond movers' positions. The gate is
  therefore native per-player AI state -- most plausibly an oil-logistics
  flag set when the tanker entered the platform, observed by the first beat
  after some internal delay -- which lives beside the AI.BIN interpreter,
  outside both the sealed unit records and the fixture player rows. Next
  session: capture the writer of ai_home offset 0x58 at fixture 249 (plan
  must watch 0x58 directly; `--field order` plans will not see it), or mine
  the AI player state around the 199->249 window with the ai-decision-ledger
  tooling, then port the same condition into
  `fireBattleNetNavalPatrolPass`'s behavior-six arm using
  `battleNetNavalPatrolTarget`, which already computes the right endpoints.

## Prior release checkpoint — 2026-08-22

The authenticated semantic-v1 campaign gate currently reports:

- 52/52 fixtures executed and compared through fixture cycle 400;
- a shared exact frontier of cycle 120, with the first mismatch in
  `retail-xhuman-12-idle` at cycle 121;
- 14 cases exact through the entire 400-cycle window, 38 later-divergent, and
  zero failed; and
- a passing retained-baseline gate using the same BNE ChonkPack identity.

Keep the nouns separate in status reports. A **run horizon** says how many
cycles were inspected. A **per-case frontier** is the last exact cycle for one
fixture. The **shared frontier** is the minimum per-case frontier across all 52.
Thus "52 maps run through 400" is coverage, not "all maps exact through 400."

The release candidate deliberately retains the cycle-120 production engine. A
cycle-123 candidate is preserved at branch
`codex/bne-all52-h123-release-candidate` (commit `41a04aa5`), but it is not a
release: the broader playability suite caught regressions in a late XHuman 10
AI assault, XHuman 12 Zeppelin movement, and packed melee-chase behavior. A
subsequent XHuman12-local experiment is preserved at branch
`codex/xhuman12-c200-research` (commit `f786bae9`), but it regressed Human 8,
Human 13, XHuman 4, XHuman 8, XHuman 9, and XHuman 10. Do not promote either
state until it passes both the all-map retained-boundary gate and the broad
playability/failure-identity gate.

## Target and boundary

The Java engine is a single simulation: retail BNE rules only. There is no
maintained ChonkCraft/LegacyEngine alternate profile in the load path or tick loop.

The only authoritative target is the English retail Warcraft II Battle.net
Edition 2.02b executable extracted from Blizzard's official updater:

```text
Warcraft II BNE.exe
bytes   712704
sha256  b0e914a9cb7dcc81a205e700a9bb0a1d0649df19d459388051ba170783d2c807
```

Modified third-party builds, DOS Warcraft II, LegacyEngine, and ChonkCraft are useful research
references, but none may silently substitute for this oracle. No proprietary
game file belongs in Git.

The comparison boundary is BNE's timed call to `0x00452110`, hooked only at
call site `0x00421238`. Fixture cycle one is the state after the first timed
unit update. Java performs two hidden startup ticks before it emits fixture
cycle one, so while reading `CHONKCRAFT_TRACE_BNE_IDLE` output:

```text
Java internal cycle = fixture cycle + 2
```

Always state which numbering system a note uses.

## Current checkpoint — 2026-08-15 systemic parity program

Branch `master`. The pinned BNE 2.02b executable, local pack, Branch Witness,
and the remote fixture oracle on `i9beef` are READY. The remote campaign index
and all 52 referenced fixtures have been opened and SHA-256 authenticated as
the SSH user. `doctor --need fixture` now tests readability and every referenced
fixture rather than mistaking root-owned, mode-0600 evidence for usable input.

The current commanded fleet is 240 generated scenarios, 134 executed by both
adapters, 134 comparable, **129 exact and 5 materially divergent**, with zero
infrastructure failures. This is a substantial improvement over the prior
57/131 exact checkpoint, but it is not 129/240 parity: the remaining 106 rows
have no dual-engine execution. Exactness is also concentrated in movement;
repair, return-goods, patrol, attack-ground, queued commands, true group
transactions, stand-ground, and production remain thin or open. Attack-move
now has a dest-path injector and commanded dual-adapter cells that match
first-progress and terminal.

Use `bne_playtest_explorer.py worklist` to turn the flat ledger into ranked
systemic clusters, fixed-family counts, current-source authentication, and a
frozen-baseline regression report. The v1 commanded rows enter below the UI
gesture boundary. They prove resolved per-unit lifecycle only; they cannot
prove selection order, one-click group fan-out, acknowledgements, map/minimap
interpretation, or Shift queueing.

The three systemic lanes, unified fail-closed scorecard and long-running agent
handoff are in [NEXT_LEVEL_PARITY.md](NEXT_LEVEL_PARITY.md). The lab policy is
schema 2. Fleet equality and required player-intent cells are
the acceptance signal. The globally earliest divergence is diagnostic only;
it must not select work or justify fixture-, mission-, unit-, or fitted-cycle
branches. An accepted rule needs a family gain, no loss of an exact required
cell, two independent positive witnesses plus a held-out/negative witness (or
an unconditional transcribed binary rule), and a receipt matching the exact
engine input being committed.

Fleet-wide native-exact AI remains explicitly unproved. Semantic-v2's
historical "AI executive" lane observes coarse player state, not the native
48-byte AI.BIN interpreter state or its per-cycle program counter. A normalized
decision ledger now covers PC/list/table offsets, waits, state bytes, predicate
attempts/results, writes, and launch consumption on both engines. Its first
end-to-end Orc 1 smoke run found committed state exact through 12 cycles. The
same capture now also compares wait writes and opcode-3 WAIT-UNTIL telemetry
after `from-trace` stopped letting a same-cycle `game-before` dump hide the
previous committed write. Orc 1 player 1 stays exact through 1,800 cycles on
that recovered ledger. That is still one player and one mission -- not the
multi-player scorecard. Until that grows across missions and
effect outcomes, a green coarse AI playability test is a floor, not a claim
that the enemy chose what retail BNE chose.

The XOrc 9 identity correction, world-local UGRD overlays, and replay prefix
through dispatcher record 3,935 remain valid accomplishments from the prior
checkpoint. Historical frontier pointers and ignored local checkpoint files
are not fresh-context handoffs unless their producer identity matches the
current source.

## Current integration checkpoint — 2026-08-08 visual/spatial round

The retired scripting language-free playable product is certified on `codex/bne-retired-interpreter-free-pack` through
commit `6af48739`. The player-visible fixes in this round are systemic BNE rules,
not map exceptions:

- projectile facing is the constructor value stored at native projectile
  `+0x0a`; point action `0x004101f0` and parabolic action `0x00410260` never
  rewrite it. Java now preserves launch facing while the parabolic animation
  row continues to change;
- transports may anchor on `COAST_ALLOWED`; destroyers and tankers remain
  water-only. A Human 5 referee checks the raw visual tile and simulation flags
  under every commanded ship anchor;
- Human 5's four authored base guards remain posted while its separate five-unit
  field squad engages, matching the authenticated 1,800-cycle native capture;
- Command-Shift-E on macOS (Ctrl-Shift-E elsewhere) captures a screenshot,
  resumable save, and forensic JSON without changing ordinary saves; and
- the full-media pack contains all 20 logical Opus music tracks and boots with
  the game JAR as the only other runtime file.

The complete 18-lane playability receipt is certified with zero skips. The
52-case candidate survey passes the retained acceptance floor with 13 clean,
39 later-divergent, zero failed, and common clean h40. That is a regression
floor, not completion of the separate exact-fidelity track. The central parity
worktree/dashboard may have a later accepted frontier; never move it backward
to this integration worktree's pointer. The authenticated route scorer remains
743/808 exact, so 65 nonexact routes stay explicitly open.

## System shape

The intended loop is deliberately one-way:

```text
retail BNE 2.02b under Wine
        |
        | one-time capture
        v
sealed schema-1.1 .bnefx fixture
        |
        | immutable, offline input
        v
Java EngineTrace -> bne_compare.py -> first semantic divergence
```

Wine is not part of the ordinary Java test loop. Regenerate a fixture only
when the experiment itself changes: scenario, seed, command stream, capture
schema, or a proven tracer defect. Never regenerate an oracle fixture merely
to make a Java change pass.

Schema 1.1 retains:

- all 152 bytes of every initialized BNE unit slot, delta encoded;
- all 64 bytes of every projectile/effect slot, with slot generations;
- mutable map cells and simulation-square flags;
- extended player supply, research, availability, and score state;
- the semantic text stream used by the current Java comparator; and
- the complete run identity and terminal markers.

The semantic-v1 comparator covers cycle, synchronized RNG, player banks, unit
identity/core state, and coarse order. The opt-in
[semantic-v2 tier](SEMANTIC_V2.md) now also compares proved extended-player,
sub-tile unit, projectile, and mutable-terrain state. Its family filter makes a
full 52-case AI macro pass practical, and its commanded corpus deliberately
exercises movement/refusal decisions absent from idle campaigns. Unsupported
raw fields remain named as uncovered instead of being guessed into a pass.

## Reproducible environment

Configure machine-local paths and the maintainer-managed SSH alias outside the
repository:

```sh
export CHONKCRAFT_REPO=/path/to/chonkcraft
export CHONKCRAFT_ASSET_PACK="$HOME/.chonkcraft/packs/warcraft-ii-bne.chonkpack"
export CHONKCRAFT_ORACLE_HOST=i9beef
```

The headless oracle and complete 52-case corpus live on that remote host:

```text
~/.local/share/chonkcraft-bne-oracle
~/.local/share/chonkcraft-bne-oracle/output/campaign-1800/corpus-index.json
```

The corpus is user-owned evidence and is intentionally outside Git. Confirm
these paths rather than assuming they survived a machine cleanup:

```sh
git -C "$CHONKCRAFT_REPO" status --short
git -C "$CHONKCRAFT_REPO" rev-parse HEAD
test -f "$CHONKCRAFT_ASSET_PACK"
ssh "$CHONKCRAFT_ORACLE_HOST" \
  'test -f ~/.local/share/chonkcraft-bne-oracle/output/campaign-1800/corpus-index.json'
```

Remote addresses, host keys, and recovery procedures are private maintainer
configuration and must never be committed. `doctor --need capture` is the
authoritative readiness check; an unreachable optional remote does not prevent
offline fixture comparison.

The authoritative executable used for local static analysis may be copied to
`/private/tmp/Warcraft-II-BNE-2.02b.exe`. Hash it before every analysis
session. Its absence is not a blocker for offline fixture comparison.

## Before modelling a behaviour, check whether it is already modelled

[`PORT_MECHANISMS.md`](PORT_MECHANISMS.md) indexes the retail behaviours this
engine has already been taught, keyed by the mission whose units proved them --
45 missions and 640 references, generated from the comments themselves.

It exists because the alternative was paid for once. A fix was written here for
an XOrc 8 gryphon rider's patrol timing, complete with a selection rule, a
release delay and the arithmetic behind both, and the engine had modelled all
of it already through a different path; the duplicate broke what was there and
took longer than the real defect, which turned out to be a missing fifty-cycle
pass elsewhere. Reading the index for the mission in front of you costs a
minute.

## Reading a Java trace against a fixture cycle

Get this wrong and the conclusion inverts. It has, three times, in one case.

A fixture snapshot is taken at the **end** of an internal engine cycle, and the
two numbering schemes differ by a per-case warm-up. Anything printed by an
engine probe carries `world.cycle`, which is the internal number; anything in
the `.java.trace.txt` or in the sealed native records carries the fixture
number. They are not the same, the offset is not always two, and it must be
measured rather than assumed.

**Anchor it on an event both sides record.** A hit-point change is ideal: it
appears in the fixture trace at cycle F and in a damage probe at internal cycle
I, and the offset is `I - F` for effects applied during a cycle. On Human 13
that anchor gave `internal = fixture + 2` from two independent blows.

**Then mind what a probe prints.** A value printed on *entry* to internal cycle
`i` is the state at the *end* of internal cycle `i - 1`, which is fixture
`i - 1 - offset`. Comparing an entry-state probe against retail's end-of-cycle
snapshot without that correction shifts everything by one and reads as a
one-cycle engine lag that is not there. That is exactly what happened on Human
13's ogres: it produced a "compensating pair" of defects, an "entry a cycle
late", and a cycle-four origin, and all three were withdrawn once the mapping
was anchored properly.

The cheapest guard is to state the offset and how it was anchored in the note
before drawing any conclusion from it.

## What supersedes the loop below, and why

The loop in this section was built around "establish one exact first
divergence, on one fixture, in a short window". That is the right way to
*diagnose* a case and the wrong way to *choose* what to work on, and the
difference cost a long session before it was measured.

**The earliest divergence is a bad search signal.** It is a minimum over cases
of a first failure, so it is decided by whichever unit breaks earliest and it
yields one bit per four-minute run. Optimising it is what produced arms in
`BattleNetMovementSystem` gated on `pathLength() == 6` and named after
individual grunts. Measured: across fifty-two cases the two engines agree on
where a unit is standing on **410,677 of 410,880** paired unit-cycles, so
"earliest divergence 53" is made of two hundred and three of them.

**And sixty cycles flatters the port.** At sixty, 27 of 52 cases are clean. At
two hundred, **four** are. A patch that survives the window is not the same as
a behaviour that is right.

So the loop is now:

### A. Measure the fleet before choosing the work

Run the survey with the field dump on and score every case together, rather
than reading one number off the worst one:

```sh
CHONKCRAFT_TRACE_BNE_FIELDS=1 python3 tools/bne-harness/scripts/bne_java.py survey \
  <corpus-index.json> --asset-pack <pack> --source-dir <chonkcraft> \
  --jobs 8 --through 200 --skip-build --output-dir /tmp/fleet
```

Then score the fifty-two runs together:

```sh
python3 tools/bne-harness/scripts/bne_java.py field-parity /tmp/fleet \
  --cases <corpus>/campaign-1800/cases --through 200 \
  --json-output /tmp/fleet-dense.json
```

Each fixture seals its own `state.bin`, so the join needs nothing the survey
does not already produce, and it takes five seconds. It pairs native slots to
Java ids **by cycle-one position**, never by pool order -- the two engines list
the pool in opposite directions, and pairing by order matches one unit per
cycle by luck.

Two numbers come out and the first is the gate. `in place` counts paired
unit-cycles standing on the same square, over a denominator that does not move
with the result; `decisions` is the histogram below, compared only up to and
including the visit on which a unit parted. Through 60 the tree at `d89fdb5`
scores **410,448 of 410,880** in place and 400,105 of 403,573 decisions.

### B. Rank the disagreements by kind, not by which case shouts loudest

Position agreement is a lagging indicator: a wrong decision on cycle 19 shows
as a wrong position on 36, by which time the unit's state is incomparable and
the trail is cold. Compare the decisions instead -- did it step and which way,
did it lay a fresh route, does the route it holds start with the same heading
-- on the prefix where both engines still have the unit in the same place.

That histogram reordered this project's whole queue: route content and route
presence together are **thirteen times** the step divergences and **nine
times** the refuse-and-replan cadence, which is what a full session had gone
into.

### C. Sort the ranking by dependency, then take the most upstream one

Bucket size is not the whole order. A correct change measured *through* a wrong
upstream one reads as a regression. The proof, measured both ways in one run:
correcting the cooperative-blocker answer cut route-presence disagreement by
179 and cost 194 unit-cycles of position, while `route's next heading differs`
did not move at all -- 1,797 before and after. The route generator is wrong on
two routes in five, so every decision newly got right simply sends more work
through it.

**Rank by size, sort by dependency, fix the most upstream.**

### D. Gate on the dense score; track the horizon, do not obey it

- **Gate:** total agreement over the fleet must go up.
- **Diagnostic:** the decision histogram, to say which bucket moved.
- **Tracked, not gated:** the earliest divergence. Expect it to fall while
  upstream work lands, and to recover afterwards.

State the pass/fail bar *before* running. Doing that once in this thread is
what allowed "the premise needs amending" instead of a rationalisation.

### E. Read it; do not fit it

Every quantity read out of the executable in this thread was right first time:
the movement mask, the alliance matrix, the goal skirt's footprint, the
per-order flag words, the action-state assignment, the give-up. Every quantity
fitted from captures was wrong at least once: the three-by-three skirt,
`0x4000` being decisive, byte 8 meaning "is moving", the give-up being a clear,
"within two tiles" being a distance rule when it is a bit in the record.

Knowledge here comes in three grades and they take different gates:

| grade | example | gate |
|---|---|---|
| read from the binary | `0x00450ad0` is `routeIndex = 20` | lands unconditionally |
| mapped onto this port's state | byte 8 of 3 is "in the Move body" -- which is `isMoving()`? the animation? neither | must be measured |
| no equivalent state exists here | the route cursor; the stuck-unit machine at `0x8d`-`0x96` | build the state first; this is the engineering |

Every failure in this thread was in the second grade: the rule was read
correctly and mapped wrongly.

### F. Price a hypothesis without changing behaviour

Dump the candidate signal and evaluate the predicate offline. The refusal
nibble was tracked and dumped while nothing read it, so the runs were
position-identical and only the measurement moved -- turning a four-minute
survey per hypothesis into five seconds per hypothesis over 410,880 samples.
Prefer this to an engine edit until the next move is unambiguous.

### G. Then use the single-case loop below

Once the bucket and the rule are chosen, the first-divergence loop that follows
is the right tool for finding *which* unit and *which* cycle. It is a
diagnostic, not a scoreboard.

## Standing optimisations worth keeping

- **The corpus carries squares but not unit records**, so a route-content
  change cannot be scored offline and needs a survey run. Exporting the records
  would put the largest bucket on a seconds-long loop.
- **Feed any route probe the squares from the cycle *before* the route was
  planned.** A capture records the map once a cycle, after every unit in the
  pass has moved, so a unit that steps after the planner is recorded where the
  planner never saw it. Worth 94 routes of 842.
- **Every arm should carry its price.** Most case-tuned arms in the movement
  system carry prose and no number, so nothing can be re-priced when the ground
  under it changes. The two that were priced this session cost 135 unit-cycles
  of 410,880 between them -- and seventeen cycles of horizon.
- **`read_state_stream` yields the same carried-forward dictionary every
  cycle.** Copy it to remember a frame; `list()` collapses every frame onto the
  last, silently.

## The engineering loop

### 0. Discover the available evidence routes

Every fresh agent starts with:

```sh
python3 tools/bne-harness/scripts/bne_java.py doctor
```

Before declaring native capture unavailable, run `doctor --need capture`. It
checks the configured remote oracle and Branch Witness image as well as local
Docker and the authenticated executable. See
[`ACCELERATION_GATES.md`](ACCELERATION_GATES.md).

### 1. Establish one exact first divergence

Work on one fixture and a short window. A long trace creates noise without
adding evidence.

```sh
python3 tools/bne-harness/scripts/bne_java.py case \
  /path/to/case.bnefx \
  --asset-pack "$HOME/.chonkcraft/work/warcraft-ii-battle-net-edition-usa.pre-full-media-2026-07-30.chonkpack" \
  --source-dir "$HOME/Documents/source/chonkcraft" \
  --output-dir /private/tmp/bne-one-case \
  --through 20 \
  --report-all
```

`bne_java.py` validates the fixture, compiles unless `--skip-build` is given,
runs `EngineTrace`, checks a contiguous cycle window, and invokes the
first-divergence comparator. Keep the output directory named for the
hypothesis being tested; do not overwrite the previous result.

The comparator reports native pool slots. Java unit IDs are different. Pair a
unit using type, owner, initial coordinates, and stable surrounding units; do
not equate numeric IDs by position in a list.

### 2. Ask the fixture before asking the screen

UI observations are useful leads, not authoritative state. Check `trace.txt`
and schema-1.1 raw records first. `bne_fixture.py` validates and reconstructs
every delta without launching BNE:

```sh
python3 tools/bne-harness/scripts/bne_oracle.py validate-fixture /path/to/case.bnefx
```

If the required field is not decoded by the comparator, write a temporary
read-only decoder for `state.bin` or add a narrowly scoped diagnostic to the
tracer. Preserve the raw bytes and record offsets in [LAYOUT.md](LAYOUT.md)
once verified.

For the normal iteration, prefer the automatic durable loop. It runs the
candidate survey and gate, clusters all divergences, reruns the earliest case
with paired Java path/step diagnostics, and builds its final packet:

```sh
python3 tools/bne-harness/scripts/bne_java.py triage \
  tools/bne-harness/work/corpus/campaign-1800/corpus-index.json \
  --baseline-survey /path/to/last-proof.json \
  --asset-pack /path/to/bne.chonkpack \
  --source-dir /path/to/chonkcraft \
  --through 30 --jobs 4
```

Read `.bne-artifacts/latest.json`, then the selected run's `NEXT.md`. The run
manifest SHA-verifies its retained inputs, traces, reports, diagnostics, and
packets. Exact repeated requests are safe cache hits; older and incomplete
attempts are never silently overwritten. Treat the generated clusters as
heuristic leads only and preserve globally earliest-first acceptance.

Acceptance also has its own monotonic pointer at
`.bne-artifacts/latest-accepted.json`. A passing, failure-free 52-case direct
`gate`, or a direct `survey --baseline-survey`, now seals a content-addressed
receipt and promotes that pointer automatically. Partial gates are retained as
evidence but cannot promote it, and an older proof cannot roll it backward.
This keeps dashboard publication current even when an agent uses the faster
direct survey/gate route instead of full triage.

For the richer low-context loop, use `bne_java.py autopilot` with the same
arguments. It composes the verified triage run into `.bne-lab/`, where native
and Java causal events, minimized evidence, information-gain experiments,
failure history, coverage gaps, function analysis, synthesis candidates, and
isolated tournament plans are durable artifacts. Start with
[`PARITY_LAB.md`](PARITY_LAB.md); do not load this full history unless the
selected case needs it.

If a later native visit accepts a transition that an earlier visit rejected
without writing the watched field, use the contrastive decision pipeline rather
than trying to watch the non-write. `bne_java.py decision-plan` authenticates
both fixture outcomes, bootstraps from the accepted writer, and produces
focus-scoped entry-to-return captures for `decision-mine`. The remote runner is
dry-run by default and deploys only to a content-addressed diagnostic harness.
See [`DECISION_MINER.md`](DECISION_MINER.md).

For a divergent survey that already exists, generate the standard evidence
packet manually before adding temporary logging:

```sh
python3 tools/bne-harness/scripts/bne_java.py packet \
  /tmp/bne-survey-h22/bne-java-survey.json \
  --case retail-xhuman-12-idle \
  --output-dir /tmp/bne-packet-xhuman12-c22 \
  --before 5 --radius 4
```

The packet authenticates the survey, Java trace, corpus index, and fixture,
then pairs native slots with Java IDs using the production differ's lifetime
rule. It writes a Markdown summary, structured JSON, and focused semantic
traces. The JSON reconstructs the native unit's full raw record across the
window, labels byte transitions, decodes route/order/animation/AI fields,
includes extended player and projectile state, and extracts mutable-map
windows around the unit and its order point. Existing native diagnostic events
and retained Java stdout/stderr are copied when present. The output directory
must be new so an older packet cannot be silently overwritten.

### 3. Capture a targeted dynamic trace only when needed

The remote headless runner can trace a single unit without taking over the
desktop or playing sound. Example:

```sh
ssh "$CHONKCRAFT_ORACLE_HOST" '
  cd ~/.local/share/chonkcraft-bne-oracle/harness &&
  python3 scripts/bne_headless.py run \
    --oracle-root ~/.local/share/chonkcraft-bne-oracle \
    --case-id diag-human13-unit1404 \
    --output output/diagnostics-move \
    --scenario "Campaign\\Human\\Human13.pud" \
    --cycles 30 --seed 1 --trace-unit 1404 \
    --commands commands/human13-attack.txt \
    --require-commands-applied 1 --require-commands-rejected 0
'
```

When a diagnostic is intended to exercise an accepted command, the two
`--require-commands-*` assertions are mandatory. A sealed fixture can
legitimately contain a rejected command (refusal parity needs those fixtures),
so sealing alone does not prove that the requested attack, move, or harvest
actually ran. The assertions inspect the sealed manifest and fail the command
after capture if its exact applied/rejected counts differ. This prevents a
valid native idle trace from being mistaken for evidence about a command that
never reached a unit.

Add a hook only at a guarded address in the pinned executable. Validate the
original bytes before patching, log the return address/caller and before/after
state, and keep diagnostic hooks behind an explicit option. A trace should
answer one question; avoid logging the entire game when a unit slot, RNG call
site, or constructor boundary will do.

### 4. Use static analysis to explain the dynamic event

On this machine `radare2` is available. A typical read-only disassembly is:

```sh
shasum -a 256 /private/tmp/Warcraft-II-BNE-2.02b.exe
r2 -q -e io.cache=true \
  -c 'aaa; s 0x004101f0; pd 100' \
  /private/tmp/Warcraft-II-BNE-2.02b.exe
```

Treat addresses, branch conditions, data-table values, and RNG return sites
as facts only after checking them against the pinned executable. Put lasting
layout discoveries in [LAYOUT.md](LAYOUT.md), not only in a chat transcript.

### 5. Implement the smallest BNE-profile change

BNE behavior belongs behind `World.setBattleNetProfile(true)` unless the same
behavior is proven correct for the ordinary ChonkCraft profile too. Prefer an
explicit native concept—sequence cursor, ready callback, native distance,
projectile constructor boundary—over a map- or mission-specific exception.

Add a focused regression test that witnesses the native rule without loading
a campaign. A useful test states why the old behavior was wrong and locks the
number/order of RNG draws or state transitions, not merely the final screen.

Run focused tests first:

```sh
mvn -q -o -pl engine -am \
  -Dtest=BattleNetAiWoodTest,BattleNetSequenceTest,BattleNetIdleAttackTest,BattleNetPathFinderTest,BattleNetResourceApproachTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Then rerun the exact same fixture/window into a new output directory. A fix is
accepted only when it moves the first divergence later for an explained
reason and does not move another previously clean case earlier.

### 6. Detect compensating errors

This is the most important discipline in the BNE loop. Two wrong omitted or
extra RNG calls can cancel, leaving semantic state clean for several cycles.
Whenever a change touches BNE's asynchronous LCG, compare the exact call chain
at the same native boundary, not only the first visible unit movement.

The two relevant streams are distinct:

| Stream | State | Step | Role |
| --- | --- | --- | --- |
| synchronized gameplay | `0x004a48dc` | `state * 0x41c64e6d + 0x3039` | recorded fixture seed |
| asynchronous/native action | `0x004d40ec` | `state * 0x015a4e35 + 1` | construction, idle animation, projectile setup/update, some damage calculations |

The semantic trace exposes only the first, because only the first is written
into a fixture every cycle. Both are now recorded as causal events on both
sides: with `CHONKCRAFT_TRACE_BNE_CAUSAL` set, Java emits `rng.async.draw` for
every asynchronous draw with its seed before and after, the value, a running
draw number, and the class and method that consumed it, beside the native
tracer's `async-random`. Align the two with `bne_java.py rng-ledger` rather
than by hand; see [`RNG_LEDGER.md`](RNG_LEDGER.md). `CHONKCRAFT_TRACE_BNE_IDLE=1`
remains useful for the idle-marker narrative. Record caller return sites; the
resulting value alone cannot say why it was drawn.

### 7. Widen only after the focused case is explained

Run the 52-case survey at the same short horizon before increasing it:

```sh
python3 tools/bne-harness/scripts/bne_java.py survey \
  /path/to/corpus-index.json \
  --asset-pack /path/to/bne.chonkpack \
  --source-dir /path/to/chonkcraft \
  --jobs 4 \
  --through 5 \
  --output-dir /private/tmp/bne-survey-horizon-5
```

Advance the common horizon monotonically: 1, then 5, 10, 20, and so on.
Record `counts`, the earliest divergence, and any case that regressed. Full
1,800-cycle runs are valuable only after the short common frontier is clean.

Keep the common horizon as the acceptance floor, but do not use it as the
entire work queue. Same-workspace lookahead surveys may probe currently clean
cases farther and expose clusters behind the earliest case. Combine those
reports into explicit per-case frontiers:

```sh
python3 tools/bne-harness/scripts/bne_java.py frontier \
  /tmp/bne-survey-h21/bne-java-survey.json \
  /tmp/bne-survey-h22/bne-java-survey.json \
  --markdown-output /tmp/bne-frontier.markdown
```

A lookahead result does not raise the acceptance floor. Before accepting a
candidate change, gate it against the last saved proof; the gate rejects an
earlier divergence, missing case, failed case, or shorter run:

```sh
python3 tools/bne-harness/scripts/bne_java.py survey \
  /path/to/corpus-index.json \
  --asset-pack /path/to/bne.chonkpack \
  --source-dir /path/to/chonkcraft \
  --through 22 --jobs 4 \
  --baseline-survey /tmp/bne-survey-h21/bne-java-survey.json \
  --output-dir /tmp/bne-candidate-h22
```

Survey JSON includes a full dirty-workspace fingerprint, structured first-
divergence findings, and phase timings. `frontier` combines only identical
engine workspaces and asset sources; `gate` is the cross-revision operation.

### 8. Leave a resumable checkpoint

Before handing off:

1. update the dated checkpoint below;
2. record the exact fixture, command, output directory, and first divergence;
3. separate proven facts from the next hypothesis;
4. list tests actually run after the final edit;
5. leave diagnostic artifacts outside Git and source/test changes inside it;
6. do not reset, clean, commit, or push unrelated dirty work; and
7. say whether the latest code is a completed fix or an in-progress probe.

An agent receiving the task should begin by reading this file, checking
`git status --short`, and reproducing the last command. An agent handing it
back should update this same section. That makes the repository, rather than
conversation memory, the handoff boundary.

## Current checkpoint — 2026-08-02 (cold-commit accepted through h29)

### Accepted floor (do not regress)

| Horizon | Clean | Divergent |
|--------:|------:|----------:|
| 14–33 | **52** | **0** |
| 60 (lookahead) | **9** | **43** |

The **accepted** common clean frontier is now **33**; accepted earliest
divergence **34** (`retail-xorc-08-idle` destroyer). Prior earliest also
xhuman-12 grunt (advanced 34→35). Survey artifact:
`tools/bne-harness/work/java-corpus/campaign-1800-coop-replan17-survey60/bne-java-survey.json`.

### What closed the h28→h29 advance

Cold-commit walk model plus arrival rules that residual drain must not skip:

1. **Sub-tile cold-commit** (walk old element before decide; cold Move pickup).
2. **Chase OP0** pre-walk + type-50 Attack hold without re-arm / settled walk.
3. **Wood range-one** drain without empty-route PF_WAIT; settle fallthrough
   arms free-prefix action-23 delay on the same cycle residual clears
   (XHuman 7 peon 1545 E replan at fixture 22).
4. **Gold free-prefix near approach** (XHuman 9 peon 1550): clear wrong
   leftover on land, drain-only in `walkTowards`, stage action 25 NE after
   residual (fixture clean through 40+).
5. **Gold on approach while residual / Move anim live** (XOrc 12 peasant 1396):
   `atResource` without `!isMoving`/`!isStepping` when on approach empty path.
6. **Gold free-prefix forest re-aim** (Orc 7 peon 1567): re-aim without
   `!isStepping`, on settle fallthrough before PF_WAIT (claim SyncRand @24).
7. **Build on site while residual drains** (XOrc 10 peasant 1573): drain-only
   on footprint, fall through to `battleNetPointReached` StartBuilding same
   visit (farm founds @22; first div moved to 33).
8. Removed obsolete 3px projectile aim-lead.

Regression test:
`BattleNetResourceApproachTest.nearApproachGoldFreePrefixStagesApproachInsteadOfWrongLeftover`.

### Closed on this floor (1482 replan residual hold)

Melee chase retarget that tears up a live multi-step route now flags the
chaser; when the first new-path residual settles, order delay 2 holds three
fixture cycles before the next heading (native ogre 1482: residual on
124,32 at c31-33, SW at 34). Delay-only -- arming Attack-sequence markers
while out of weapon range debited SyncRand and dropped knight 1490 by 50 HP.
Continuous free-approach multi-step paths never set the flag (ogre 1511).
Gate on residual settle (`actionMoveWalked && !isMoving`), not
`atMoveBoundary` (mid-Move residual is the usual case).

Regression: `MeleeChaseReplanResidualTest` (efficacy: fails without delay
arm, passes with it). 52-case gate vs h30 **PASS** (common clean 30,
earliest still 31). Ogre 1482 tile timing is no longer a first finding.

### Closed this pass (catapult-rock action-6 wait 5)

`missile-catapult-rock` action 6 holds five projectile passes after remaining
goes negative before free+splash (Human 13 slot 3: rem -5 at fixture 30,
knight 1490 HP 77 through 34, free and 77→6 at 35). Point-to-point and other
parabolic types (small-cannon) keep wait 1 -- a blanket five-pass hold delayed
XHuman 10 splash four cycles (grunt HP @14). Knight 1490 HP now matches
native through the rock impact; human-13 first div moves 31→36 (seed only).
xhuman-12 advances 31→32. Gate vs h30 **PASS**; common clean still 30 on
human-05 seed alone.

Regression: `BattleNetMissileMotionTest.aCatapultRockHoldsFiveActionSixVisitsBeforeFree`
and `aParabolicSmallCannonFreesOnTheNextActionSixVisit`.

### Closed this pass (table-0x27 attack-loop SyncRand, h30→h31)

`FUN_004234b0` re-seeds unit+0xb every attack animation loop (twenty-five
cycles), not once per order. Sealed Human 5: standing grunt 1531 draws at
fixture 6 then 31 (`0xb` 00→29); chasers 1528/1532 draw at 22 then 47. Java
cleared the pending flag after the first debit and left seed `2781e494` at
fixture 31 while native advanced to `c46b9b3d`. After each debit arm a
25-cycle countdown on the Attack sequence (same period as wood opcode 2660);
when it expires in range, re-seed and re-arm.

Regression: `MeleeAttackSyncLoopTest` (efficacy: fails without the loop tick,
passes with it). human-05 seed matches through c39; first div moves 31→32
(barracks HP 784 vs 789 + critter order). Common clean **31**; earliest **32**.
Gate vs h30 **PASS** and advances the floor.

### Closed this pass (chase melee OP10 / Move-body leftover)

Human 5 barracks at fixture 32: native 792→784 (−8), Java was 792→789
(axe only). Chase grunts 1528/1532 stayed on Move sequence offsets (2482+)
after the approach step while presentation Attack fired `hit()`; opcode 10
never ran, so deferred melee pending never resolved. Standing 1531 stayed on
Attack (2539+) and landed OP10.

Rules:
1. In-range, not chasing: if sequence offset is on the Move body, re-enter
   Attack start.
2. First in-range SyncRand debit also arms Attack start.
3. When presentation fires while Attack wait is still >1, resolve the
   deferred blow immediately (chasers were three timer ticks behind OP10).

human-05 first div **32→35** (peasant 1512 tile). Barracks HP matches
through the c32 double-melee. Gate vs h31 **PASS**; common clean still **31**
(xhuman-12 tower HP sole earliest at 32).

Regression: `MeleeAttackSyncLoopTest` chase-arrival case; fixture window
proves c32 HP.

### Closed this pass (building-target projectile constructor debit, h31→h32)

Mobile presentation can fire an axe while Attack wait is still above one
(XHuman 12 internal 33, timer 3). Native FUN_0040fb10 spends the three
async constructor draws (damage + two aim jitters) on that presentation
frame; Java waited until OP10 three waits later and left the next melee
rem three draws short (tower 1370 dmg 3 vs native 2 at fixture 32).

Rule:
1. When a mobile weapon presentation-hits a **building** with Attack timer
   > 1, queue the three constructor draws for end-of-unit-loop debit.
2. Do not enable battleNetMotion yet -- OP10 still arms flight.
3. Unit targets keep OP10 constructor timing so Human 13 critter 1576
   (still vs MOVE at fixture 34) is not reordered.

xh12 first div **32→34** (grunt tile). human-13 stays @36; human-05 @35.
Common clean **31→32**. Gate vs h31 **PASS**. New earliest xorc-10@33.

Regression: `PresentationAheadProjectilePrepareTest`. Survey:
`tools/bne-harness/work/java-corpus/campaign-1800-building-debit-survey60/bne-java-survey.json`.

### Closed this pass (BNE construction HP boost cadence, h32→h33)

XOrc 10 farm 1426: foundation one-tenth HP (40 of 400) held through no-op
Built visits, then sparse climbs 40→43@33, 43→47@45, 47→50@57. LegacyEngine
every-cycle progress left Java at 40 on c33. BNE boost rule:

1. Keep the existing ten-cycle foundation hold (first action on the cycle
   after place).
2. Each boost: `pool += full - foundation`; `gain = pool / buildTime`;
   `pool %= buildTime`; add gain to HP.
3. Sleep eleven more Built visits (twelve-cycle period) before the next
   boost. Progress advances one time unit per boost for roof timing.

xorc-10 **clean through 60**. Bonus: xhuman-06 49→clean, orc-09 48→56.
Common clean **32→33**. Gate vs h32 **PASS**.

Regression: `ConstructionTest.bneFarmClimbsThreeHitPointsOnItsFirstConstructionBoost`.
Survey:
`tools/bne-harness/work/java-corpus/campaign-1800-construct-boost-survey60/bne-java-survey.json`.

### Closed this pass (gold free-prefix mid-journey replan)

Orc 12 peon 1525 free-prefix SW,NW onto 85,41 while mine approach is 83,41
(cheb 2). routeSpent residual settle used to arm PF_WAIT 10; native
continued west without the ten. Free-prefix paths (end short of approach,
length &lt; 20) now replan immediately on spent settle when still far from
the mine; full buffer segments still wait.

orc-12 **34→39**; human-08 **37→50**. Gate vs h33 **PASS** (common clean
still 33). Regression:
`BattleNetResourceApproachTest.midJourneyGoldFreePrefixReplansWithoutTheTenCycleWait`.
Survey:
`tools/bne-harness/work/java-corpus/campaign-1800-gold-freeprefix-survey60/bne-java-survey.json`.

### Closed this pass (build free-prefix mid-journey replan)

Same free-prefix rule for BUILD (`walkToSite`): drain spent residual, then
replan without PF_WAIT 10 when still short of the site. XHuman 10 peon
1551 free SE tip onto 20,56 (goal 40,79).

xhuman-10 **34→38**; xhuman-05 **51→clean**. Gate **PASS**. Survey:
`tools/bne-harness/work/java-corpus/campaign-1800-build-freeprefix-survey60/bne-java-survey.json`.

### Closed this pass (melee replan + cooperative refuse + Attack-four)

XHuman 12 grunt 1495 residual-settles with replan and a cooperative ally
on the next tile. FUN_004379e0's fourteen quiet visits alone stepped at
fixture 34; native also pays Attack-four (three more) before the first
new heading at 37. Soft delay **17** when replan residual hold is live;
ordinary cooperative refuse stays 14.

xh12 first div **34→35**. Gate **PASS**. Sole earliest: xorc-08@34.
Regression:
`MeleeChaseReplanResidualTest.aReplanResidualCooperativeRefuseWaitsSeventeenVisitsNotFourteen`.
Survey:
`tools/bne-harness/work/java-corpus/campaign-1800-coop-replan17-survey60/bne-java-survey.json`.

### Closed this pass (naval shore-base order point, no distance gate)

`FUN_004381d0` rewrites a non-capital sea unit's non-open-water patrol goal
to the last blocked cell on the ray toward the ship for **every** distance,
not only Chebyshev ≤ 6. XOrc 8 destroyer 1430: refinery 87,71 → **88,73**,
path `70077700077`, step 96,92→**96,90** pure N. Far shipyard goals whose
first ray step is already open water still return themselves.

xorc-08 first div **34→38**. Common clean **33→34**. Gate **PASS**.
Regression:
`BattleNetSeaOccupancyTest.farDestroyerRewritesShoreBasePatrolOntoFootprintEdge`.
Survey:
`tools/bne-harness/work/java-corpus/campaign-1800-naval-orderpoint-survey60/bne-java-survey.json`.

### Closed this pass (Human 5 wood farm free-prefix)

Native wood orderXY for peasant 1512 is farm cell **31,106**, free route
only `[SW,SW]` onto **32,107**, then spent-route hold before the tree
segment. Java ordered the tree and packed `5556`, cold-committing SW at
fixture 35. Reverse-line building orderXY + building-goal diagonal tip
pack (max diagonals within range 1) + tip re-aim to tree.

human-05 first div **35→52**. Gate **PASS**. Regression:
`BattleNetAiWoodTest.humanFiveWoodFreePrefixStepsSouthwestTwiceTowardFarmOrderPoint`.
Survey:
`tools/bne-harness/work/java-corpus/campaign-1800-wood-farm-order-survey60/bne-java-survey.json`.

### Closed this pass (Human 14 temple raise-dead)

Profile 27 arms action-33 code `0x93` on the death-knight temple. Sealed
bank drops **1500g** at fixture c35 for `upgrade-raise-dead`. Temple was
not on the action-33 Still/research path. Wire temple/mage-tower to
action 33, research selector for 0x93-0x97, freeze 3 for c35 timing.

human-14 first div **35→51**. Gate **PASS**. Regression:
`BattleNetTrainWorkerTest.humanFourteenDeathKnightTempleSpendsFifteenHundredGoldOnRaiseDead`.
Survey:
`tools/bne-harness/work/java-corpus/campaign-1800-temple-raise-survey60/bne-java-survey.json`.

### Closed this pass (Orc 10 build free-prefix tip cheb 1)

Build free-prefix replan used `cheb > max(1, maxRange)`. Peon 1583 free
tip **43,3** → goal **44,4** is cheb 1 with maxRange 0, so the tip paid
PF_WAIT 10 while native residual-settled SE. Skip empty wait on
`cheb > maxRange`.

orc-10 first div **35→41**. Gate **PASS** (0 REG vs temple-raise).
Regression:
`BattleNetResourceApproachTest.buildFreePrefixTipOneChebyshevFromSiteReplansWithoutTheTenCycleWait`.
Survey:
`tools/bne-harness/work/java-corpus/campaign-1800-build-tip-cheb1-survey60/bne-java-survey.json`.

### Closed this pass (Orc 14 flyer train gated on wantFlyers >= 4)

Aviary action-33 used a rich-bank first-flyer bridge for wants 0-3.
Orc 14 p6 wantFlyers=0 debited 2500g at c35; XOrc 11 p6 want=3 would
regress at c15 under a want>0-only gate. Train only when wantFlyers >= 4
(XHuman 7 / XOrc 6 full flight).

orc-14 first div **35→37**. Gate **PASS** (0 REG vs build-tip-cheb1).
Regression:
`BattleNetTrainWorkerTest.aRichBankBelowFourWantedFlyersDoesNotTrainAGryphonOnTheAviaryPulse`.
Survey:
`tools/bne-harness/work/java-corpus/campaign-1800-flyer-want4-survey60/bne-java-survey.json`.

### Closed this pass (in-range combat buildings share target score 0x20000)

`battleNetTargetScore` used to add `0x30000` for every in-range
`canAttack` building. XHuman 2 catapult 45 then preferred guard-tower 54
over footman 52; native rock from 56,63 aims the footman (rem 157 at c10,
free at fixture 35). Air keeps `0x30000`; ground combatants -- mobile or
combat-building -- share `0x20000`; passive buildings stay priority-
distance only.

Both Java rocks now aim footman 52. Gate vs flyer-want4 **PASS** (0 REG,
0 IMP; earliest still multi-way @35). c35 HP still under-damaged until
center-primary splash order (next closed pass).

Regression:
`BattleNetIdleAttackTest.inRangeCombatBuildingDoesNotOutrankAGroundFighter`
(test-efficacy: effective-regression-test vs 14d793f). Survey:
`tools/bne-harness/work/java-corpus/campaign-1800-target-score-survey60/bne-java-survey.json`.

### Closed this pass (splash impact-tile victim before northern neighbours)

`resolveBattleNetSplash` sorted by ascending tileY. Native
`FUN_00410520` hits the **impact tile first**, then the 7x7. XHuman 2
slot-6 free at c35: footman on impact (60,68) vs ogre north (61,66).
Same two async rolls (7938, 16402) yield native footman 57 / ogre 12
center-primary, or Java footman 41 / ogre 8 under y-sort. Stored 80,
armor 2/4, outer when metric > 0x1ff.

xhuman-02 first div **35→39**. Gate vs target-score **PASS** (0 REG).
Regression:
`BattleNetIdleAttackTest.rockSplashRollsImpactTileVictimBeforeNorthernNeighbour`
(test-efficacy: effective-regression-test vs 1aac720). Survey:
`tools/bne-harness/work/java-corpus/campaign-1800-center-splash-survey60/bne-java-survey.json`.

### Closed this pass (free-prefix tip ally-blocked leftover by path length)

Free-prefix tip residual with pathLength==1 and ally-blocked leftover at
cheb 2 used to PF_WAIT 10 with spent false. Short free-prefix (marked
length under 4) discards leftover and replans after a settle visit
(XORc 12 → NE @35). Longer free-prefix soft-holds the progressive
leftover until free (XORc 6 → SE @58). Both clean through 60.

Gate vs center-splash **PASS**. Regression:
`BattleNetResourceApproachTest.shortFreePrefixTipBlockedLeftoverReplansWithoutTheTenCycleWait`.
Survey:
`tools/bne-harness/work/java-corpus/campaign-1800-fp-tip-len-survey60/bne-java-survey.json`.

### Closed this pass (constructor-boundary re-aim for pending mobiles)

Presentation can allocate a mobile projectile one Attack frame before OP10
with then-current pixels; FUN_0040fb10 measures remaining from live
muzzle/aim at the constructor boundary. XHuman 12 archer→grunt 152 walked
east: rem 134 vs 131, free fixture 36 vs 35. Refresh pixels in
`debitBattleNetProjectileConstructor` before the two aim jitters.

xh12 findings at 35: grunt arm closed (only tower rock roll remains).
Gate **PASS** (common clean still **34**). Survey:
`tools/bne-harness/work/java-corpus/campaign-1800-ctor-reaim-survey60/bne-java-survey.json`.

### Closed this pass (building-target presentation collapses Attack wait)

Building-target mid-wait presentation debited constructor draws but left
Attack timer at 3, so OP10 flight armed two visits late. XHuman 12 axe
127→tower: missing PTP draws before rock free → tower splash 39 vs 36.
Set timer to 1 (like presentation-ahead melee) so OP10 is next.

xh12 **35→36**. Common clean **34→35**. Gate **PASS**. Survey:
`tools/bne-harness/work/java-corpus/campaign-1800-bldg-timer1-survey60/bne-java-survey.json`.

### Closed this pass (empty-route chase free-detour)

Exhausted-route combat replan that soft-cleared a moving ally and opened
with a solid first step no longer waits fourteen: first free compass
neighbour is taken (XHuman 12 grunt 1507 N@36). Mid-route leftovers keep
the fourteen-visit hold.

xh12 **36→37**. Gate **PASS** (common clean still **35**).

### Closed this pass (residual-settle table-0x27 SyncRand)

After chase `walkPixels` drain, consume pending melee SyncRand in the same
visit. Human 13 wise-man 1496 and grunt 1507 both settle into Attack and
debit at fixture 36; waiting for the next top-of-stepAttack left only one
of the two draws in c36.

human-13 **36→37**. Common clean **35→36**. Gate **PASS**. Survey:
`tools/bne-harness/work/java-corpus/campaign-1800-postwalk-sync-survey80/bne-java-survey.json`.

Native sealed pair at c36 is wise-man + grunt, not knight 1500. Knight
settles Attack timer 3 with no SyncRand; seed next advances at c40.

### Closed this pass (cavalry hit-response defers settle SyncRand)

When `actionMoveWalked` marks a residual-settle visit, knight/ogre units
that still carry an offered hit-response open Attack without debiting
FUN_004234b0. Standing fighters keep the ordinary settle debit (catapult
112 at f34).

human-13 seed at 37 closed; critter order + knight hp remain. Gate **PASS**
(common clean still **36**). Survey:
`tools/bne-harness/work/java-corpus/campaign-1800-cavalry-defer-survey80/bne-java-survey.json`.

### Closed this pass (flyer self-patrol residual-first second double-step)

Self-patrol combat flyers keep the scout destination as both endpoints and
drain residual before the free-visit consult (return while pixels remain so
walkTowards does not double-drain). Residual-zero reopens Move boundary and
cold-commits the next double-step same visit. XOrc 11 gryphon 1589
42,4→42,6→42,8 at fixture 37 (was stuck on 42,6 or one cycle late).

xorc-11 **37→40**. Gate **PASS** (common clean still **36**, 0 REG). Survey:
`tools/bne-harness/work/java-corpus/campaign-1800-flyer-scout-survey80/bne-java-survey.json`.

### Closed this pass (multi-leftover residual post-OP0 Attack open)

Contact-melee chase that discards pathLength >= 2 leftovers once residual
settles opens Attack at post-OP0 (tick attackStart with timer 1 → 644/1 for
ogre script) instead of cold 643/3. Sequence OP10 then lands the named
in-range target when presentation has not pended yet; presentation is
blocked for that swing so the blow is not doubled. Human 13 ogre 1510/90
OP10+damage at fixture 37 (was fixture 40); knight 1500 hp finding closed.

human-13 findings **2→1** (critter only). Gate **PASS** (common clean still
**36**, 0 REG). Survey:
`tools/bne-harness/work/java-corpus/campaign-1800-multileftover-survey80/bne-java-survey.json`.

### Corrected this pass (oil-tanker stationary-hull wall follow)

The earlier scoped 3-by-2 half-grid rule reproduced only Orc 14 tanker 1566's
second step. Retained raw route bytes prove retail stores `01 01 03` --
NE,NE,SE -- not the approximation's NE,NE,E. Native draws the ordinary
doubled-delta NE,E,NE line, sees stationary tanker 1576 at the second anchor,
and wall-follows around it. Only allies whose internal action byte is Move (3)
are soft-cleared at `0x004507b5`; a HARVEST order alone is not transparent.

Java now keeps stationary harvesting hulls solid and removes the half-grid
exception. Tanker 1566 matches through entry on cycle 104 while nearby tanker
1576 retains its valid NE step and cycle-41 entry. The 52-case 600-cycle gate
passes with zero regressions and XHuman 5 improves 108→158. Regression:
`BattleNetPathFinderTest.orcFourteenTankerRoutesAroundStationaryTanker`.
Full evidence and the lifecycle gate are in
[`OIL_LIFECYCLE.md`](OIL_LIFECYCLE.md).

### Closed this pass (Move-sequence soft-clear for 0x4500f0)

Soft-clear wall-trace allies only when residual is live **and** the sequence
offset sits in the Move program body `[moveStart, attackStart)`. Plain
`isMoving` also soft-cleared Attack-sequence residual (offsets near the
Attack table while still on the Move walk loop edge). Matches native
`0x4500f0` Move-animation occupancy. Survey movesoft-survey80 gate **PASS**
(0 REG). XHuman 12 replan still `777...` -- nearby allies at residual replan
still sit inside the Move body, so hard Attack occupancy alone is not enough.

### Rejected this pass (Attack soft-clear gates for xh12 replan)

XHuman 12 grunt 1500 residual replan 35,39→30,44: Java path `777...` (NW)
vs native `02 02...` (E). No soft-clear recovers first step east. Hard-
blocking Attack on all or combat-only routes REGs early formation (grunt
1507 @4). cheb>1 / cheb>2 / in-range gates REG @5-7 or leave path 777.
Move-sequence soft-clear is correct but not sufficient for this replan.

### Earliest open (do not stop)

| Cycle | Case | Symptom |
|---:|---|---|
| 38 | several | orc-14 tanker tile; xh12/orc-05/07 peon tiles; ... |

**Accepted engine floor** **37**. Clean count **7** through 80.
Survey: `tools/bne-harness/work/java-corpus/campaign-1800-o14-transport-survey80/`.
orc-14 first div **37→38** (oil tanker). human-13 stays @44. human-04 **43→50**.

**Closed this pass (Orc 14 transport AE30 shoreline):** Post-harvest Still on
startup transports used to re-draw fly-idle AE30 at world 21. Native order-32
Still never re-draws after warmup. Mark `battleNetTransportFlyDrawn` when the
hull reaches shoreline Still (and on harvest begin), then re-arm the timer
without drawing. Gate **PASS** vs path1-defer; zero REGs. Floor **36→37**.

**Prior close (H13 pathLen-1 + hold suppress):** H13 seed@41 closed; H13@44.

**Next action:** Earliest @38 cluster (orc-14 tanker; xh12/orc land tiles).
Then H13 ogre hp@44.

#### Implementation map (`World.java`)

- `stepMove`: cold-commit; gold near-approach wrong-leftover clear.
- `walkTowards` / `walkToWood`: drain-only + settle fallthrough; forest re-aim.
- `stepHarvest` gold approach arrival while residual/Move live.
- `stepWalkToSite`: drain-only on footprint; StartBuilding on settle.
- Chase OP0 / type-50; transport-to-hall.

Triage run:
`.bne-artifacts/runs/7a299c42623240e133eb6b50290db5c09cc015c10d2c9e6a57cd004cf69cffce`.
Lab packets: orc-14 @30, human-05 @31.

### Closed this session

- **Axethrower 1505 (Human 13) @28.** A ranged chaser's retarget hold is what
  tearing up a live route costs, and native pays it before the replacement
  exists: slot 1505 keeps its heading bytes at fixture 25 and moves only its
  cursor to 20, then writes `[SW,S,S,SW,S]` and spends the first heading in the
  single visit at 28. The port laid the new route at the same moment it armed
  the hold, so the second retarget bought a second hold and the diagonal landed
  at 31. Slot 1505 now matches native every cycle through 80. Frontier 27 → 28.
  `RangedChaseRetargetTest` is classified `effective-regression-test` by
  `test-efficacy --baseline b9726ae`.
- **Zeppelin 1541 (Human 5) @29 → @31.** A waiting flyer's bob banks a pixel;
  the step prime spent it, owing 65 for a 64-pixel leg, so the flight ended a
  pixel short, `Moving` never cleared at the consult, the Move animation
  restarted and the next leg went a full ten-cycle lap late. Native legs
  9/29/49/69; the port flew 9/39/59/79 and now matches through 80. `cadence`
  reports this shape directly. **Its focused Java test is owed, not skipped** --
  the synthetic flyer written for it passed with the fix reverted because it
  banked no pixel and took no double stride; covered by focused tests.
- **Lab handoff repair.** A player-bank divergence names no unit, so the
  counterfactual planner raised and aborted the whole lab compose before the
  pointer was promoted, leaving `.bne-lab` three frontiers stale while triage
  advanced. It now reports `supported: false` the way branch witness already
  did.

### Next: Human 13 ogre slot 1519 @29, the sole earliest blocker

`frontier` ranks it the only tied blocker, tractability **medium**, recommended
tool **branch-witness**. `doctor --need capture` reports READY with
`remote-branch-witness` on the configured oracle host.

Established, all from the sealed fixture: the ogre declines knight slot 1500 at
fixture 29 and takes the same knight at 34. The knight sits on (119,25),
Chebyshev 6, at 84 hit points from fixture 25 through 40 in both engines, so the
candidate never changes. The ogre's own 152-byte record moves nothing but its
animation timer between the two visits. Its Still loop is five cycles -- offset
4983 for one cycle, then 4985 counting 4,3,2,1 -- so its scan visits are 4, 9,
14, 19, 24, 29, 34. `cadence` reports native `[34]` against Java `[29]` and
diagnoses `different-transition-sequence`, so this is a one-shot decision and
not a phase offset.

Three hypotheses were tested against the fixture and all three are refuted: a
per-cycle acquisition budget (native turns four units Attack on fixture 4
alone), a slot or cycle modulus (nineteen acquisitions in three hundred cycles
fit none of five or ten), and scanning every other Still loop (three other units
acquire on their fifth, fifth and sixth opcode visit where this one takes its
seventh).

The Contrastive Native Decision Miner now handles the no-write/later-write
shape end to end. It authenticated the cycle-34 writer, proved the exact caller
and focus argument, captured accepted 34 plus rejected 29 and held-out 24, and
recovered `unit[*].next_order != 60` at native branch `0x00452f04`. All three
outcomes predict correctly. The result and schema-2 handoff are durable below
`.bne-decision-miner/runs/0e9a2d63c8cd27caa8daa6c185ab48f0882e8e3c74856c65bc41b9439707cba1/`.

That predicate is the order-promotion boundary, not the acquisition gate: it
explains why the already queued replacement becomes current, but not why native
queued Attack at 34 and left `next_order` at 60 on 29.

**The upstream contrast is now complete and the acquisition rule is proved.**
Decision Miner learned to scope an upstream activation -- one reached through
the order dispatch's indirect call and returning before the write that labels
it -- and captured shared handler `0x0040b010` for slot 1519 at 34, 29 and 24.
The captures are durable below
`.bne-decision-miner/remote/9cd034803e42d0869479dd48964643b2e0252e6195492e8bf708073257595510/`
and the mined contrast below
`.bne-decision-miner/runs/1420df3f3e2b5d33353d266c12db6edee38e1f0b5fa82950f62071827edb58d7/`.

What they prove: the scan reaches `0x00409ff0`, whose ranked branch
`0x0040a953` flips -- the search returns nothing at 29 and 24 and the knight at
34. The search does not iterate units by range. It binary-searches a global
unit index on **tile** Y for the band `[rectTop - 3, rectBottom]`, and that
index is kept sorted on **pixel** Y by `0x00453ae0`, called once per cycle
after the whole unit pass. A unit whose tile has snapped ahead of its pixel
position sorts behind a unit whose tile Y already left the band; the search
stops at that inversion and never sees it. At cycle 29 slot 1505 (pixel Y 803,
tile Y 26) hides the knight (pixel Y 822, tile Y 25); by 34 the knight has
fallen to pixel Y 809 and sorts ahead of it. [LAYOUT.md](LAYOUT.md) records the
addresses, the table and the replay.

Replaying that index and search over the sealed fixture reproduces all three
native scans exactly: 42, 41 and 42 entries, every per-entry rectangle verdict
in order, and a scored candidate only at 34.

**Ported, gate-clean, and not yet enough.** `battleNetBandWindow` now scopes
the scan to that window and `ReactionBandOrderTest` witnesses the rule
(`test-efficacy --baseline 8944c80` classifies it `effective-regression-test`).
The 52-case gate passes at h60 with no earlier divergence and no regression,
run `e0129e820b95ffc838e6d56f84f51ab999c1a581be1d0240f5f85353f32f38d2`, but the
frontier is still 28: Human 13's ogre still turns Attack at 29.

**The narrowed blocker is a one-cycle sub-tile lead, not the scan.**
`CHONKCRAFT_TRACE_BNE_BAND=1` shows the port computing band `10..25` and window
`57..98` at fixture 29 with knight (Java unit 100) at index 98 -- inside it.
Native's window there is `57..97` with the knight at 99.

The two pixel series are the same series, one cycle apart:

| fixture | 25 | 26 | 27 | 28 | 29 | 30 | 31 | 32 | 33 | 34 | 35 | 36 |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| native slot 1500 | 832 | 829 | 826 | 822 | 822 | 819 | 816 | 813 | 809 | 809 | 806 | 803 |
| Java unit 100 | 829 | 826 | 822 | 822 | 819 | 816 | 813 | 809 | 809 | 806 | 803 | 800 |

`java[c] == native[c + 1]` at every cycle, so the port already has the whole
shape right -- the `-3, -3, -4, 0` beat and both pauses. It simply starts a
cycle early. Native's leg opens on 832, a full 32 above the destination tile:
on the cycle its tile snaps to the next square it spends **no** pixels. The
port snaps the tile and spends its first three-pixel step in the same cycle.
Native's axethrower slot 1505 leads by the same one cycle, which is why it
sorts at index 94 ahead of the three units at pixel Y 800 where the port's
pair sorts at 97 behind them.

No sub-tile position is compared by the semantic comparator, which is why this
drifted unnoticed while every tile transition matched -- `cadence` on slot 1500
reports native `[25]` against Java `[25]` and diagnoses nothing. The next work
is the step prime on the cycle a tile snaps, in the same territory as the
zeppelin bob that banked a pixel into its prime.

#### The commit-cycle invariant, measured

Every native tile snap in `retail-human-13-idle`, `retail-orc-07-idle`,
`retail-xhuman-12-idle` and `retail-human-05-idle` through cycle 200: the drawn
offset after a commit is exactly `-posd * 32 * stride` in **896 of 896**
single-tile steps, **413** of them carrying a non-zero offset into the cycle.
The unit ends a commit cycle standing on the origin of the square it just left.
The spend beat does not stutter across a commit -- peon 1462 walks the same
eight-cycle `0,2,3,2,3,0,3,3` through its snaps at fixture 18 and 34 -- so the
animation advances on that cycle and none of its pixels reach the new element.

Decisive: **native commits while it still owes pixels.** Peon 1462 ends fixture
33 owing two and snaps at 34. The port's `mayDecide` is
`!walkHolding && atMoveBoundary` and `walkHolding` only falls when the drain
reaches nought, so the port cannot reproduce that step by any reordering that
reads the offsets before the walk. It currently hits retail's commit cycle by
compensation: walking the new step in the same cycle drains every leg one step
early, which drops `walkHolding` one cycle early, which is what its late gate
needs. Both halves must move together.

Rejected implementations, all against `retail-human-13-idle` (baseline
divergence 29). Do not retry as written:

| Change | First divergence | Why |
|---|--:|---|
| Assignment prime, no commit-cycle walk | **18** | Peon 1462 hits the wrap with `iy=-3` owed; `moving=1` blocks the consult and it waits a whole animation period |
| `walkPixels` at the top of `stepMove`, no walk after the commit | **2** | `advanceMoveAnimation` calls `switchTo(move)`, putting a standing unit on the Move animation before the gate, destroying `atMoveBoundary`'s "not the move script" escape |
| Gate on the wrap alone (drop `!walkHolding`) | **19** | Units consult mid-leg; `sync_rng` diverges and ogre 1482 moves |
| **Hybrid**: wrap read before the walk, walk only for a unit already on the Move script, `!walkHolding` read after it, assignment prime, no second walk | **19** | Same signature as above. But the commit cycle itself is now **exactly right** -- see below |

The hybrid is the closest any of the four comes and it is worth resuming from,
not discarding. Its shape:

```java
boolean atWrapBeforeWalk = atMoveBoundary(unit);   // before anything advances
boolean walkedThisCycle = onMoveAnimation(unit);   // has a leg to finish
if (walkedThisCycle) { walkPixels(unit); }         // old element, may clear Moving
...
boolean mayDecide = !unit.walkHolding() && atWrapBeforeWalk;
...
unit.setOffset(-dx * TILE * stride, -dy * TILE * stride);   // assigned, not +=
...
if (stepped && !walkedThisCycle) { advanceMoveAnimation(unit); }  // cold leg only
```

It fixes exactly what it was aimed at. Peon 1462 snaps at fixture 18 as native
does **and** reads native's pixel 1728 on that cycle where the port reads 1731,
and 1723/1731/1734 at fixtures 16/20/21 all land on native's value. The
commit-cycle invariant is satisfied; a standing unit keeps its "not the move
script" escape; `!walkHolding` still blocks mid-leg consults.

What it does not fix is a **second, independent error**: between commits the
port's move animation is still one advance ahead. Peon 1462's spend pattern is
`[2,3,2,3,0,3,3,0]` against native's `[0,2,3,2,3,0,3,3]` -- the same period-8
cycle rotated one place -- and that lead predates fixture 14, so it is not
produced by the commit at 18. With the commit-cycle pixel corrected the port now
runs up to three pixels *behind* native between commits instead of one step
ahead, which is why human-13 lands on 19 rather than on 29.

**Cold-commit switch-only is refuted.** Making the cold-commit arm pick up the
Move script without advancing it (`switchTo` alone instead of
`advanceMoveAnimation`) takes human-13 to **18** -- worse. A unit setting out
does advance its script on that first cycle.

#### Correction: the lead is created at the first step, not inherited

An earlier revision of this section said the animation phase error was
independent of the commit and already present before fixture 14. **That was
wrong**, and reading peon 1462 from fixture 1 rather than from 14 is what shows
it. At fixture 1 both engines sit on (55,53) at pixel 1696. At fixture 2 both
snap to (55,54) -- and native stays on 1696 while the port moves to 1699. From
fixture 3 onward `java_pixel[c] == native_pixel[c + 1]` without exception. The
lead is manufactured by the very first commit and then carried forever; nothing
precedes it. What the fixture-15 table below shows is that carried lead, not a
separate fault:

| fixture | 15 | 16 | 17 | 18 | 19 |
|---|--:|--:|--:|--:|--:|
| native spend | 0 | 2 | 3 | 2 | 3 |
| port spend | 2 | 3 | 2 | 3 | 0 |

`port_spend[c] == native_spend[c + 1]` throughout, which is precisely and
sufficiently what makes `java_pixel[c] == native_pixel[c + 1]` for every
walking unit on every map. The commit-cycle prime is downstream of this, not
beside it: the port's leg has to start a step early because its animation is
already a step early.

#### Where the hybrid gets to, exactly

Rerun with the hybrid applied, peon 1462 from fixture 1: the cold commit at
fixture 2 now lands on native's 1696 with offset exactly -32, so **the
systemic pixel lead at the commit is gone**. The peon also still snaps at 18,
which the switch-only variant does not. What remains is that from fixture 3 the
port's animation is one element early again -- `java[c] == native[c + 1]`
resumes -- because the port's cold-commit advance *executes* the script and
returns 3, while native's returns 0 and leaves the script at its first
instruction.

That is now the whole remaining gap, and it is a single question:

> On the cycle a unit picks up the Move script, native's advance yields **0**
> and the next cycle yields the script's **first** move. The port's yields the
> first move immediately.

The port's runner cannot express that in one advance, which is the useful
constraint. `AnimationState.switchTo` sets `index = 0, wait = 0`
(`AnimationState.java:132-138`), and `AnimationRunner.step` either burns a wait
returning 0 *and bumps the index* (`:223-229`) or executes from the index and
returns the accumulated move (`:241-...`). Neither yields "return 0, keep
position". So one of these is true and the next session should decide which:

1. Native switches onto the script **after** the cycle's walk, so the first
   Move advance happens on the following cycle. Careful: this predicts the same
   advance count as switch-only, which snapped the peon at 19 rather than 18 --
   so it only holds if native's Move script differs in length from the port's.
2. Native's switch leaves a leftover wait so the first call burns a cycle
   without consuming an instruction -- a runner convention the port does not
   have.
3. ~~The port's parsed Move script for a peon does not begin where native's
   does.~~ **Refuted -- the script is right.** `animations-peon`'s `Move`
   (`chonkcraft/scripts/orc/anim.legacy-declaration:74-80`) is twelve `move`/`wait` groups. Hand
   running `AnimationRunner.step` over it -- a `wait 1` group returns its move
   in one call, a `wait 2` group returns `move` then `0` -- yields

   ```text
   3,0,3,3,0,2,3,2,3,0,3,3,0,2,3,2
   ```

   sixteen calls summing to 32 pixels, which is one tile in sixteen cycles and
   matches peon 1462's observed leg length. That sequence is *exactly* native's
   from fixture 3 onward. Native's whole difference is a single `0` in front of
   it. So the runner and the data are both correct and the fault is only in
   when the script's first call happens.

Counts to check any candidate against: native spends
`0,3,0,3,3,0,2,3,2,3,0,3,3,0,2,3,2` at fixtures 2 through 18, seventeen values,
and snaps at 18.

#### Solved for the mover: the exact shape that reproduces native

Combining the pieces gives a variant whose peon 1462 matches native's pixel on
**every cycle from 1 to 20**, both commit cycles included -- no lead, no lag,
no jitter. The sub-tile phase error is solved:

```java
boolean walkedThisCycle = onMoveAnimation(unit);   // has a leg to finish
if (walkedThisCycle) { walkPixels(unit); }         // old element, before the gate
...
boolean mayDecide = !unit.walkHolding() && atMoveBoundary(unit);  // both read AFTER
...
unit.setOffset(-dx * TILE * stride, -dy * TILE * stride);         // assigned
unit.setResidual(0, 0);
...
if (stepped && !walkedThisCycle) { unit.animation().switchTo(coldMove); }  // pick up, do not run
```

The three rules it encodes, each measured rather than assumed:

1. The walk runs **before** the gate, on the element the unit is already on --
   which is why native can commit at fixture 18 while still owing two pixels.
2. The walk is **guarded** on already being on the Move script, which is what
   keeps `atMoveBoundary`'s "not the move script at all" escape alive for a
   standing unit. Guarding is the whole difference between this and the
   rejected walk-first attempt.
3. A cold commit **picks the script up without running it**, so the first
   move arrives the next cycle. Retail pays nought on the cycle a unit sets
   off; this port paid the script's first pace.

It is **not landable as it stands**: `retail-human-13-idle` still breaks at
**19**, on `sync_rng` plus ogre 1482's position -- a different unit and a
different fault, no longer anything to do with sub-tile phase. Baseline is 29,
so this must not be committed until that is understood. The likely culprit is
rule 1's reach: reading `atMoveBoundary` after the walk means a unit whose
advance wraps this cycle consults this cycle rather than next. That is right
for the peon and appears wrong for the ogre, so the wrap test probably needs
to distinguish "this advance wrapped it" from "the previous advance left it at
the wrap". `CHONKCRAFT_TRACE_MOVING=118` (ogre 1482's Java id) under the candidate
shows what is actually happening, and it is **not** the wrap test:

```text
internal 20  moving=1 anim=31 ix=5  iy=5   dir=224   (mid-leg, diagonal)
internal 21  moving=1 anim=34 ix=2  iy=2   dir=224
internal 22  moving=1 anim=0  ix=0  iy=32  dir=0     (commits, primes north)
```

The ogre commits at internal 22 -- fixture 20 -- where native has already
reached tile x 124 at fixture 19. Its leg is one cycle too long. The peon's
legs are gated by the animation wrap (32 pixels in exactly the script's sixteen
calls), so dropping the commit cycle's spend costs it nothing. The ogre's
diagonal leg is gated by **pixel arrival**, so the assignment prime -- which
discards whatever the previous drain banked past nought -- makes each leg up to
a pixel longer and eventually a whole cycle late.

That is the tension to resolve next, and it is sharp: the 896/896 measurement
says native's post-commit offset is exactly `-posd * 32` with nothing carried,
yet the ogre behaves as though native carries something. Either native never
overshoots -- its animation always lands the drain exactly, which would make
assignment and `+=` indistinguishable at every snap measured and the port's
overshoot a separate bug -- or the discard is wrong for arrival-gated legs.
Measure the pre-commit residual on a native diagonal leg before choosing;
`probe_prime.py` already collects the pre-snap offsets and needs only the
residual split out.

**Correction: the ogre is not a cycle late at all.** The paragraphs below read
the ogre from internal cycle 20 and concluded its leg ran long. Reading it from
fixture 1 instead -- the same correction that overturned the earlier wrong
answer in this document -- shows the opposite. Under the candidate the ogre's
pixels match native **exactly on every cycle through 20**, the commit at
fixture 7 included, where both engines snap (126,34) to (125,33) with the pixel
standing still at 4032,1088. The motion is right. What differs is the *heading
chosen* at fixture 19:

| fixture | native tile | native px | Java tile | Java px |
|---|---|---|---|---|
| 18 | 125,33 | 4002,1058 | 125,33 | 4002,1058 |
| 19 | **124,32** | 4000,1056 | **125,32** | 4000,1056 |
| 20 | 124,32 | 3997,1053 | 125,32 | 4000,1053 |

Both commit on fixture 19 and both land on pixel 4000,1056. Native took the
diagonal north-west step and left itself offset `+32,+32` from (124,32); the
port took a pure north step and left itself `0,+32` from (125,32). Same cycle,
same pixel, different element -- so this is a route or target choice, not
timing and not sub-tile, and it arrives with the `sync_rng` divergence on the
same cycle (one engine drew and the other did not).

That reclassifies the whole remaining gap. The candidate's movement model is
**correct for both units measured**; what it perturbs is which heading the
consult hands back on the cycle it now runs at. The likely mechanism is that a
post-walk gate consults with the offsets already drained, so whatever re-scan
or `setAutoTarget` runs at that boundary sees a different unit state and picks
a different element -- which is exactly the family of equal-score retarget
rules recorded further down this document. Start by diffing the ogre's stored
route and target at fixture 19 between baseline and candidate; do **not** treat
it as a pixel problem.

**The prime style is not the ogre's cause either.** Rerunning the candidate
with the original `+=` prime and its residual fold left in, changing nothing
else, gives the identical failure -- fixture 19, same `sync_rng` draw, same
ogre position. So assignment versus `+=` is not what costs the ogre its cycle;
the missing commit-cycle spend is. Both prime styles are still consistent with
every snap measured, and the choice between them remains open and untested.

The ogre's leg is arrival-gated and one cycle long under the candidate, so the
next question is narrow: **which commit started that leg, and was it cold?** A
warm commit still walks under the candidate (the guarded walk runs), so it
should not have lost a pace; only a cold one drops it. If the ogre's leg opened
from a cold commit then the switch-only rule is too broad -- correct for a peon
setting off from a standstill and wrong for a unit that is already moving when
it picks the script up -- and the guard needs to distinguish those two, not
merely "on the Move script or not". Trace the ogre from cycle 1 rather than
from 20 to see it; reading the peon from fixture 1 instead of 14 is what
corrected the last wrong conclusion in this document, and the same mistake is
available here.

Do not chase the band scan again; it is ported and correct.

Probe sources used and discarded (throwaway, not committed):
`probe_pixels.py` (paired native/Java tile and pixel series),
`probe_snaps.py` (classify snap spend), `probe_prime.py` (the 896/896 count).

### Closed at the accepted @24 floor (prior session)

- Terrain range-one + forest claim → h19 52
- Projectile centre + cooperative refusal + gold enter + type-50 + person offer
- Build foundation 10% HP + wood segment terminator
- Gold routeSpent blocked-cardinal → mine-centre diagonal (xh12 peon 1550 BOARD SW)
- Wood blocked-goal free-ray diagonal repack (xh08 peon 1510 second step NE)
- Point-goal pure major-axis free prefix when both wall faces fail (xh12
  grunt 1358 EE → (12,90); was fallbackEscape path 201 → (11,89))
- **Gold long-approach action-25 pure-cardinal stages mine-centre diagonal**
  (xorc-12 peasant 1394 NE onto (33,73); was pure east onto approach (33,74)).
- **Gold free-prefix that settles beside forest re-aims to the tree and chops**
  (`retail-orc-07-idle` peon 1567 / Java 33).
- **Gold resource free-prefix keeps pure-major WW** (`retail-orc-07-idle` peon
  1582 / Java 18).
- **A new foundation holds one-tenth HP through retail's no-op Built cycles**
  (`retail-xorc-10-idle` farm 1426).

### Closed this pass (ranged chase step-timing + gold ally staging @25)

Path-ledger on the opposite-sign axethrower pair classified **same-plan /
step-timing** (not cached-route):

1. **Ranged leftover refuse after a drained step clears and replans** instead
   of PF_WAIT 10. XHuman 4 axethrower 1521 (Java 79) took W onto (77,59) with
   multi-step cache `665666`, refused the next W at the Move wrap, and slept
   ten cycles while native set route_index 20 and stepped SW at fixture 25.
   `retail-xhuman-04-idle` is clean through 30. **Exception:** a cooperative
   mid-route ranged ally keeps the leftover under FUN_004379e0's fourteen-
   visit hold (XHuman 12 axethrower 1523 SE onto ally axe 76, timer 15 then
   S@40). Pathless still allies and melee blockers still hard-replan (XHuman
   4 1516; XHuman 12 1522).
2. **Ranged retarget at Move boundary arms order delay 2** (native attack
   animation four timer 3: this quiet visit + two delayed visits). Human 13
   axethrower 1505 (Java 95) retargeted 122,29→120,29 and stepped SW on
   fixture 25 while native held through 27 and stepped at 28. First
   divergence on that map is now **@28** (knight 1493).
3. **Gold action-25 stages when a wrong leftover is occupied by a mobile
   unit.** XHuman 5 peon 1536 at (50,104) held leftover N onto (50,103)
   under ally peon 65; free-only staging left Java on PF_WAIT while native
   promoted order 25 and stepped NE onto (51,103). Building-blocked
   leftovers still use walkTowards (Orc 12 peon 1511). `retail-xhuman-05-idle`
   is clean through 30.

Regressions:
`BattleNetIdleAttackTest.drainedRangedChaseReplansWhenLeftoverHeadingIsRefused`,
`BattleNetIdleAttackTest.rangedChaseRetargetHoldsThreeVisitsBeforeTheNextStep`.
Sealed `retail-xhuman-05-idle` through 30. Melee and undrained first-heading
refuses keep ordinary PF_WAIT.

4. **Spatial help promotes at most one brother per player per cycle.** XHuman
   12 ogres 1381/1394 both sit at react+1 of the same aggressor; promoting
   every Still visit made both Attack on fixture 25 while native staggers
   them (1381@25, 1394@26). Solo help (grunt 1481 delay 3) is unchanged.
   First remaining finding on that map is now grunt 1470 position @25.

5. **Equal-score building retarget keeps a multi-step prefix when the next
   heading still closes Chebyshev.** XHuman 12 grunt 1470 (Java 130) at
   (20,45) had leftover `E,...` toward tower 136 (24,50). Unseeded rescan
   picked equal-score tower 117 (25,42) and `setAutoTarget` wiped the cache
   before the keep check (pathn always 0), so the replan stepped N to
   (20,44) while native kept E to (21,45). Keep is evaluated **before**
   `setAutoTarget`, only when both quarries are buildings with equal retail
   scores and the next leftover strictly closes. Mobile equal-score retarget
   still wipes (Human 13 ogre 1482 N→wise-man must replan NW). Strict score
   upgrades also wipe (raw Chebyshev-only keep regressed grunts 1489/1492
   @22).
6. **Melee attack-chase hard-refuses a multi-step first heading with replan,
   not PF_WAIT 10.** XHuman 12 grunt 1496 (Java 104) planned SE,S,S,S at
   cycle 11, soft-waited on SE, then hard-refused and slept ten while native
   set route_index 20 and first-stepped S onto (30,39) at fixture 25. Same
   arm also closed grunts 1514 and axethrower 1522 on that map. Ranged first-
   approach refuses still use ordinary PF_WAIT; drained ranged leftovers
   replan separately.
7. **Near gold free-window retry uses delay 6, not a second fourteen.**
   XHuman 12 peon 1553 (Java 47) at (6,26) soft-refuses SW onto (5,27) with
   cached SW,SE (non-progressive). First cooperative refuse keeps delay 14;
   the free-window retry (collision counter > 1, cheb <= 2) arms delay 6 to
   match native route_index 20 + anim timer 6→1 through fixtures 19..24, then
   steps SW at 25 after blocker 1550 leaves (5,27) at fixture 22. A blanket
   near delay 4 stepped at fixture 23 (gate clean only through 22); a second
   fourteen slept past the free window. Position clamp was sufficiency only.
   `retail-xhuman-12-idle` is clean through 30.
8. **Far gold jam detours to a free closer neighbour on the second refuse.**
   XOrc 2 peon 1563 (Java 37) at (86,35) held leftover S into standing ally
   1561 on (86,36) with hall blocking SE; free SW onto (85,36) closes
   Chebyshev to approach (93,50). Soft-waiting fourteen left Java on (86,35)
   through 30. Far non-cooperative allied refuse now scans free closer steps;
   first refuse matches native route_index 20 (no step), second takes the
   detour. Immediate detour failed h24 (stepped fixture 24). Position clamp
   was sufficiency only. `retail-xorc-02-idle` is clean through 30.
9. **Naval double-step patrol keeps action 5 while a multi-step route remains.**
   XOrc 11 destroyer 1542 (Java 58) at (10,24) with leftover SE headings stayed
   native order 5 (Patrol) through fixtures 21..28; mid-route autoAttack
   promoted ATTACK_MOVE at 25. Suppress acquisition for double-step sea units
   with pathLength > 0 (same family as the first-step battleship hold).
   Counterfactual reported-order was sufficiency for the label. Map is clean
   through 30.
10. **Chase headings after the first step wait for retail Move OP0, not the
    ChonkCraft Move wait total.** XHuman 9 skeleton 1431 held SW,SW,S; first SW
    shared, second SW native fixture 26 vs Java 27. script.bin Move slot 3
    (skeleton 1130) opens frame+OP0 then a 20-call body; ChonkCraft skeleton Move
    is 21 waits. After the first chase step, arm the Move body past OP0 and
    take further headings only on OP0 (even if ChonkCraft Moving still holds).
    `setOrder(MOVE)` during stepMoveTowardsTarget no longer wipes the sequence
    cursor. `retail-xhuman-09-idle` is clean through 30.

Regressions:
`BattleNetResourceApproachTest.aNearGoldSoftRefuseUsesTheShortCollisionWaitSoTheFreedTileIsTaken`,
`BattleNetResourceApproachTest.aFirstNearGoldSoftRefuseStillUsesTheFullCooperativeWait`,
`BattleNetResourceApproachTest.aGoldPeonJammedByAStandingAllyTakesAFreeCloserDetour`,
`BattleNetSeaOccupancyTest.aDestroyerKeepsPatrolWhileAMultiStepNavalRouteRemains`,
`ChasePaceTest.aSkeletonChaseTakesTheNextHeadingOnTheRetailMoveOp0`.

11. **Action-33 shipyard tanker wants come from live ai.bin state for every
    profile, not a sealed 53/61/47 allow-list.** Human 14 p5 profile 29 writes
    +0x18=1 (limit-4 train at fixture ~c27); Human 12 / Orc 12 write 3/4.
    Zeroing non-sealed wants left those banks untouched (−400g/−200w). Oil
    census counts every living tanker including Data-marked guards so XHuman 8
    p7's preplaced tanker satisfies want 1 (opening wants alone over-spent
    that seat at c12). Shipyard train_fn is not gated on UNIT.Data (Orc 12 p1
    data-0 yard still debits). `retail-human-14-idle`, `retail-human-12-idle`,
    and `retail-orc-12-idle` are clean through 30.

Regressions:
`BattleNetTrainWorkerTest.humanFourteenBlackArmsATankerWantFromRetailAiBinAndSpendsFourHundredGoldAfterTheShipyardCounterExceedsFour`,
`BattleNetTrainWorkerTest.aDataMarkedPreplacedTankerSatisfiesTheShipyardOilWantSoTheYardDoesNotReSpend`.

12. **Mobile projectile constructor applies −3..4 aim jitter, not only the
    draws.** `FUN_0040fb10` offsets both aim axes before remaining distance is
    measured. Burning the two async draws without moving the aim left pure
    tile-centre flights one speed-step long when max-axis length was divisible
    by 12 (XHuman 10 axe → farm 1536: native free fixture 27, Java 28, HP 394
    vs 400). `retail-xhuman-10-idle` is clean through 30.

Regression:
`BattleNetMissileMotionTest.mobileAimJitterOfMinusOneOnTheMajorAxisShortensRemainingByOnePixel`.


### Open after wood harvest SyncRand

- Closed Human 13 **knight free-scan @28**: after the attack order-delay window,
  melee units with a live offered target re-run `0x409ff0` (null seed) before
  the first cold path; acquisition faces the goal so equal-cost first steps keep
  the delay face (1500 NW onto 119,25 after free-scan to ogre 120,24; 1493 SE
  onto 121,30). Native Branch Witness: order_x writer `0x4513fc`, free-scan arm
  via `0x40a953`. Remaining on that map: axethrower 1505 late SW step @28 (Java
  @31). Regression:
  `BattleNetIdleAttackTest.aChaseFreeScansAfterTheOrderDelayAndKeepsAnEqualCostFaceStep`.
- Closed orc-07 / xorc-12 **sync_rng @27**: terrain-harvest `0x423550` now
  re-seeds on every 25-cycle work loop and on free-prefix claim+3 staging.
  Both cases clean through 30.
- h30 gate **PASS**: **49 clean / 3 divergent / 0 failed**; common clean
  horizon **27** (was 26). h24–h26 floor preserved.
- Earliest remaining: human-13 **axethrower** 1505 position @28 (knight closed);
  human-05 zeppelin @29; orc-14 bank @30.
- Closed earlier @27: farm HP (aim jitter), bank cluster (tanker wants).
- Durable triage: `.bne-artifacts/runs/036410bca49fa87866632e864f1095943a8e927c548505de556ff9f06427e505`.
- Advance earliest-first 28 → 30 → 1800 against the h24–h27 floor.

Verification: `BattleNetResourceApproachTest` wood SyncRand suite green;
seed chain orc-07 c6/24/27/31/40/43 and xorc-12 c5/9/19/30/34/44 match
native. Branch `codex/bne-parity-h22-checkpoint`.

### Note

- human-13 wise-man: 3-cycle early second step (KnightMove unbreakable is 11;
  native holds ~14 before next heading). Post-step delay experiments regressed
  h20; need native move program for knight-class, not a generic delay.
- Do **not** keep pure-major prefixes when wall-follow *succeeds* -- that
  rewrote peon 1364 @4 on the same map. Only the `best == null` arm.

### Repository state

- Worktree: `$CHONKCRAFT_REPO`
- Branch: `codex/bne-parity-h22-checkpoint`
- Engine parity: h24 **52/52**. Nothing has been pushed. Do not push without
  authorization.

### Architecture note — single engine (BNE only)

The Java simulation is **retail BNE only**. There is no supported ChonkCraft /
LegacyEngine dual profile:

- `World.battleNetProfile` is gone. It was `private final boolean ... = true`,
  a JLS constant variable, so javac had already folded all 114 of its source
  references; the source now says what the bytecode always meant. There is no
  profile field, getter or setter left to consult.
- `GameData.loadMission` always applies BNE unit stats, player table, sequence
  data, AI build profiles, and `fireBattleNetReadyForAll`. Its deprecated
  four-argument overload, whose boolean was ignored, is gone.
- `Player.from` is the BNE player table (neutral bank retained; extra person
  seats become nobody). `Player.forSoloGame` remains skirmish-only (extra
  persons become computers).
- `EngineTrace` always runs the BNE initialization ticks and BNE dump rules.
- `AnimationRunner` defaults random animation instructions off.

`World` is now the state owner and the cycle orchestrator only. The rules live
in eight package-private collaborators beside it -- `BattleNetIdleSystem`,
`BattleNetMovementSystem`, `BattleNetCombatSystem`, `BattleNetTargetSelection`,
`BattleNetProjectileSystem`, `BattleNetHarvestSystem`,
`BattleNetConstructionSystem` and `BattleNetBuildingPlacement` -- which took
`World.java` from 24,329 lines to 11,227. When the corpus reports a divergence,
open the system that owns the question: a step taken a cycle early is
movement, an attack where retail moved is combat, a tanker that never entered
its platform is harvest. `docs/architecture.md` states the boundaries and the
rules that keep them honest.

Do not reintroduce a second engine path. A previous attempt to mass-delete the
profile gates corrupted methods, so the removal that finally landed carried its
own proof: because the field was constant-folded, a correct rewrite has to
leave every method's bytecode unchanged apart from the removed field, its
`putfield` in the constructor, and the two accessors. `javap -c -p` over
`World*.class` before and after is that check, and the 52-case survey confirmed
it -- all 52 full 1800-cycle Java traces kept their SHA-256.

### Last broad result

Horizon 5–12: **52 clean** (prior). Horizon 14–16: **52 clean**. Horizon
17–18: **49 clean / 3 divergent** (HP only: xhuman-02/10/12). Horizon 20:
**39 clean / 13 divergent**. Earliest first divergence cycle **17**.

**Closed this pass (human-05 @15 melee opcode-10):**

Mobile melee visual hit deferred until `BattleNetSequence` opcode 10; damage
uses `FUN_00418370` async half-band (basic-armor floor 0). Barracks 1529 stays
800 HP at c15 and seed stays `41c67ea6`.

**Closed this pass (h16 banks / producers):**

- `BattleNetAiBytecode` install + bootstrap past worker gates; land wants from
  AI state; AI-accounted family census excludes PUD Data-marked mobiles.
- Barracks selector full arm order (basic/cavalry/ranged/siege); XHuman 3 ogre,
  XHuman 9 ogre, XHuman 11 grunt, XOrc 11 footman.
- Hall peon: wantedWorkers when larger than small-base cap (XHuman 12 peon).
- Help chase delay 3 so 1481 steps on fixture c17 not c16.
- Comparator: raw order 37 (production work) → STILL.

**Closed this pass (XHuman 12 grunt 1481 Attack @14):**

Spatial help for brothers at exactly `react + 1` of the aggressor (1481 dist 7
vs tower with react 6). Deferred pending → `orderAttack` + `battleNetOrderDelay`
3 so the chase step lands on native cycle 17.

**Closed this pass (XOrc 8 c15 bank -800g/-450w):**

Lumber mill and watch tower join action-33 (freeze 6, limit 1). First high-byte
milestone only; watch gated on 0x80/0x81; one tower per player per cycle.

**Closed this pass (XHuman 7 / XOrc 6 c15 dragon/gryphon -2500g):**

Aviary/roost action-33 (`0x40fa00`) trains one flyer when gold bank is rich
(>= 10000) and no flyer exists yet.

### Current focused case

```text
h14-h18: 52 clean
h19: 46/6  h20: 42/10
retail-human-13-idle @19   seed + ogre path; seed cluster; xh09/xh12
```

**Closed this pass (XOrc 6 second gryphon @18):** free roost timer compress
to 2 while sibling producing + force-sibling OP0 when want flyers ≥ 4.

**Closed this pass (c17–c18 full clean, h18=52):**

1. Missile aim uses target raw pixel + centre (not settled tile centre).
2. Mid-walk aim lead of −3 (Java drains on step-commit; native primes only).
3. Splash outer band is metric-only (no bystander rule).
4. Splash constructor stores weapon max without target armor (barracks 28 HP).
5. Hall action-33 respects low bytecode wantedWorkers (Human 5 p5 −400g).
6. Wood reverse-line accepts building-occupied squares (Human 5 peasant SW).

**Closed this pass (c14 critter empty-route / first-constructor gate):**

Later impassable critter wanders fall through to pathfind empty-FOUND;
`FUN_004376c0` promotes Still and dispatches the replacement Still handler
in the same visit without advancing the fresh animation cursor. First-
constructor rock still early-refuses. Closes Human 12, XOrc 10, XHuman 4
through 14.

**Closed this pass (XHuman 10 splash HP / projectile stream):**

1. Attack-marker idle draw skipped when `chasing && pathLength > 0`
   (XHuman 10 unit 1496 live chase route).
2. Parabolic motion takes 1 or 2 `battleNetRand` draws per `FUN_00410260`
   (`arcProgress + speed < 5 * arcStride`).
3. Slot-atomic projectile dispatch: motion RNG, step, and impact resolve
   complete per missile before the next slot (splash damage rolls before
   later motion draws).

Native HP vector 1490/1495/1482/1486 = 50/53/53/52 matches. XHuman 10 clean
through 14.

**Closed earlier this loop:** town-hall peon action-33, profile 61 tanker,
gryphon self-patrol, entry-277 thresholds, blacksmith axe1, splash membership.

### Next work

1. Earliest c17 HP-only: XHuman 10 grunt 1500 (early cannon ~37 HP), XHuman 12
   grunt 1509, XHuman 2 barracks 1554. Lead: cannon flight / second-wave impact.
2. XOrc 6 c18 second gryphon (want flyers 4; free second aviary pulse) without
   dual-start on c15 or third on XOrc 11.
3. Raise 17→20→30→… toward 1800; keep h14–h16 at 52.

```text
retail-xhuman-10-idle @14    cannon splash / splash HP (Codex owns native)
```

**Closed this pass (XHuman 12 peon 1554 path — gold refuse / progressive next):**

Native peon 1554 at (5,26) harvests mine approach (5,28) with route `04 03`
(S then SE) while ally 1550 occupies (5,27) in Move animation. Soft-clear
plans through 1550; solid step refuses. ChonkCraft PF_WAIT 10 slept past fixture
12; free-neighbor detours took SW early; clear+replan every refuse climbed
`resourceWalkWaited` / AiCanNotMove and burned the seed. Fix: gold-mine
allied-occupancy refuse arm in `stepMove` -- collision counter, try only the
immediate next stored heading when it targets a different progressive cell
(S refused → SE free at c12 when 1549 leaves 6,27), replan with
`battleNetOrderDelay` 0 near approach (cheb <= 2) or 15 otherwise so wood
and far gold walks (1511, XHuman 7/11) stay put. Sealed xhuman-12 clean
through 12. h12 51→52. Surveys: `bne-survey-h5/h10/h12-1554b`.

**Closed this pass (XHuman 5 p3 -400/-200 — shipyard tanker action-33):**

Native shipyard 1534 at cycle 12 is order 33/37 with production timer 0x64 and
type byte 0x1b (orc oil tanker). Cost 400 gold + 200 wood. Same action-33
limit-1 pulse as barracks; train_fn `0x40eef0` gates on AIPlayerState+0x18.
Interim arm: personality **53** only (XHuman 5 p3). Regression:
`aComputerShipyardSpendsFourHundredGoldAndTwoHundredWoodOnATanker...`.
h12 50→51.

**Closed this pass (barracks -600 — action-33 limit 1 + profile arm):**

Native action 33 (`0x418bb0`) increments unit+0x6e each counted Still OP0 and
calls the type's train_fn when `old > limit`. Barracks use limit 1 (third OP0)
and train_fn `0x40eb70` for footman/grunt (600 gold). A later full-corpus raw
state audit established that active hall worker profiles also train with
`old=2`; the opening great-hall constructor marker is not a counted pulse.
PUD UNIT.Data non-zero is required (Human 13 data 0 never spends). AI soldier
want (AIPlayerState+0x14) is the spend gate inside train_fn; sealed corpus
only debits at cycle 12 for personalities **40** (XOrc 11 p6) and **44**
(XHuman 2 p7). Arming every list that contains milestone 0x81 over-spent
XHuman 10 / Orc 14 / XOrc 8. Interim: only profiles 40 and 44 open the want
until the full +0x14 writer is modeled. Barracks/shipyard Still cadence is
WAIT 4 only (no constructor pair) with one extra freeze tick so OP0s land on
fixture c2/c7/c12. Regressions: barracks footman train tests. h12 48→50.

**Closed this pass (XHuman 8 peon 1575 — stack on mine approach):**

Native peons 1571/1575 both harvest the 3x3 gold mine at (15,9) and stand on
approach (17,10). Java left the second peon HARVEST-idle at (18,9) because
`canEnterBattleNetResourceTarget` soft-cleared only the mine; clearing the
ally peon then re-stamped the mine building flags. Fix: when the step is onto
the approach point or resource footprint, soft-clear same-resource allies on
that tile and re-drop all cleared flags after the batch so none re-mark.
Corridor steps stay solid. Regression:
`aSecondPeonMayStackOnAMineApproachTileOccupiedByAnAlly`. Sealed xhuman-08
clean through 12.

**Closed this pass (XHuman 8 p6 peon overspend — gold floor 500):**

Hall action-33 peon train requires gold >= 500 (ready-path poor-bank floor).
XHuman 8 p6 has exactly 400 gold; native never debits.

**Closed this pass (XHuman 8 destroyer 1480 — naval patrol far endpoint):**

Path trace showed Java goal `67,55` (enemy oil platform) with first heading
NE to `36,80`, while native `o84=41,85` and route `02 03 02` lands on
`36,82`. Fixture loads `41,85` as `unit-oil-patch` (type 93 / p15), not a
platform. Far-endpoint order was any platform before all patches, so the
distant p7 platform outranked the closer patch. Fix in
`battleNetNavalPatrolTarget`: (1) closest owned oil platform, (2) closest
oil patch, (3) closest any platform, else RNG. Focused regression
`destroyerFarPrefersOilPatchOverForeignPlatform`.

**Closed this pass (peon train — hall action-33 counter, not global lattice):**

Native `0x439000` has no cycle gate; it only enforces reserved-train quota
`(workers-1)/2+1`. Ready-worker calls it to reserve a slot, not to debit gold.
The paid peon train starts from computer hall action 33 (`0x418bb0`), covering
both race lines. The opening great-hall constructor marker leaves unit+0x6e at
zero; the counted c2/c7/c12 pulses record 1, 2, reset for an active limit-1
profile. A full 52-campaign state audit found 98 paid hall orders: every
ordinary train began with the previous counter at 2, while suppressed
limit-100 profiles began with 101. It also found 64/64 action-37 returns with
counter 0, animation timer 3 and sequence 4982 in the trainee's birth cycle.
`BattleNetIdleSystem.stepBattleNetHallStill` now preserves that constructor
and return state instead of compensating with an unrecorded pulse. The
end-of-tick global train pulse and ready-path `orderTrain` spend remain absent.

**Closed this pass (Orc 10 @12 — 1510/1513 coast free-empty stretch):**

Native 1510 free-empty coast at fixture 6 stays Still through 16 (Still-loop
OP0s miss the wander band). Native 1513 empty@8 re-wanders@9 to 55,60 and
again@12 to 53,59. Java used to fire 1510's first Still-loop OP0 at fixture
12 (choice 25 wander) and steal the async draw 1513 needs for 53,59.

Cause: after coast first-constructor empty, OP0 leaves sequence timer 1 and
the next visit installs WAIT immediately. Native's first loop OP0 is one
fixture cycle later. Fix: on coast free-empty first-constructor no-wander,
arm `battleNetCoastEmptyExtraWait`; the following no-wander OP0 bumps timer
1→2 so WAIT starts one quiet visit later -- no extra async draw (an extra
OP0 at fixture 8 desynced 1526). Sealed `retail-orc-10-idle` clean through
14. h12 44→45.

**Closed this pass (cycle 11 — free-empty cap + projectile action-6 + armor floor):**

1. Free-empty re-wander restarts at most once after the empty same-visit OP0
   (`noWander < 2`). Unlimited free restart kept Human 3 1589 drawing until it
   stole 1582's cycle-11 wander; cap leaves Orc 11 1597 Still@8-9 Move@10.
   Human 3 clean through 14; Human 13 clean through 14.

2. `Missile` BNE motion arms **action 6** when remaining goes negative and
   applies damage only on the next projectile pass (no motion RNG that tick).
   XHuman 2 arrow: remaining -4 / HP 90 at cycle 10, free / HP 83 at cycle 11.

3. Projectile constructor damage floors `basic - armor` at **0** (not 1) so
   equal-armor tower vs ogre is piercing-only maximum 12; half-band stores
   native 7 from seed result 8100. `missile-small-cannon` speed **16** (was
   ChonkCraft 22) moves XHuman 10 first div from 11 to 14.

Regressions: `BattleNetMissileMotionTest` action-6 beat;
`BattleNetIdleAttackTest` tower damage/jitter (with basic/piercing/armor set).

**Closed this pass (Orc 12 @9 1461 + Orc 10 through 11):**

Native raw for 1461: c6-8 Move goal 76,93 pathn=20 route zeros; c9 Move
**goal 78,91** route 20×ff (empty + same-visit re-wander). 1510: c6 Still
after empty, no same-visit re-wander.

First-constructor empty-FOUND now: Still-arm timer 1 + same-visit OP0;
OP0 choice **substitutes** for that visit's burn draw when burns due; on
no-wander restore constructor cursor + timer 1 (next-visit OP0 for 1513);
on re-wander leave Move and skip outer resume idle. Coast pathfind
fall-through, timer 4, and OP0-before-burn retained. h10 47→48; earliest
9→10.

**Closed this pass (XHuman 12 @9 — grunt 1470 NE first step):**

Combat pathfinder goal-marker ray vs wall-follow used to keep a free SE
ray when its first-step Chebyshev gain beat wall-follow, even if that ray
died outside the marked tower skirt (path `333` → 20,47). Native wall-
follows NE (route starts `01 02...`, 19,46→20,45) around wall column 24
and face 23,49-51. For `preserveEmptyFailure` combat targets, prefer the
higher-gain ray only when its endpoint already sits on the marked skirt
(keeps 1476 NE onto 23,43); a dead-end SE prefix yields to wall-follow.
Resource rays (`!preserveEmptyFailure`) keep the plain gain rule (peon
wood SW). `retail-xhuman-12-idle` clean through 10; h10 45→46.
Regression: `BattleNetPathFinderTest.deadRayOutsideMarkedSkirtYieldsToWallFollow`
plus sealed xhuman-12 through 10.

**Closed this pass (XHuman 12 @9 — archer 1450 action 16):**

Person surface units with UNIT.Data still run the idle hostile scan, but
only for candidates already inside weapon range (not full person reaction
range). That opens XHuman 12 archer 1450 against the footman at 24,60
(range 4) as native action 16 while keeping XHuman 4 ballista Still until
the target is in weapon range. Person auto-orders and surface-vs-air
auto-orders mark `battleNetStationaryAttack` so out-of-range drops to Still
without chase (Human 9 balloon); computer land-vs-land auto-orders remain
chase action 12 (XHuman 12 grunts 1441/1495). Regressions:
`BattleNetIdleAttackTest.personArcherWithDataAcquiresStationaryAttack`,
`BattleNetIdleAttackTest.personBallistaWithDataIgnoresOutOfWeaponRangeHostile`.

**Closed this pass (Human 12 @9 — transport 1522 double-step NE):**

Action-30 transport double-step no longer requires the hall bottom-right to
be even. Ship-on-even-lattice, neither shore delta equal to 1, and either
Chebyshev >= 4 or pure-axis (`min delta == 0`). Human 12 fortress BR y=17 is
odd; the old OR cleared double-step so the transport first-stepped (69,33)
while native double-stepped to (70,32). Orc 5 (3,3) stays single; Human 5
(7,5) and XHuman 5 (2,0) still double. h10 44→45. `retail-human-12-idle`
clean through 12. Regression:
`BattleNetTransportStartupTest.humanTwelveTransportDoubleStepsTowardOddHallShore`.

**Closed this pass (XHuman 8 @9 — destroyer 1480 Patrol):**

Destroyers and capital ships share FUN_00427a10 behaviour-6 endpoints: near
stays the ship's tile when there is no tanker service base; far is the
closest owned oil platform, else any live oil platform (campaign unbuilt
platforms sit on neutral owner 15 -- XHuman 8 far = 41,85, XOrc 10 far =
99,79), else oil patch, else RNG. The destroyer-only open-water wiggle
inverted the endpoints and failed blocked near goals into Still at cycle 9.
With near = self the action-5 at-endpoint swap keeps Patrol, then the ship
walks toward the far oil square. Stride-2 wall-follow that first-steps pure
north (gain 0) while free major-axis west improves Chebyshev is overridden
so XOrc 10 destroyer 1483 first-steps west to 122,74 without dirtied land
paths (override is stride-2 only). h10 43→44. Regressions:
`BattleNetSeaOccupancyTest.destroyerPatrolsTowardNeutralOilPlatform`,
`BattleNetPathFinderTest.xorcTenDestroyerOilApproachOpensWestNotNorth`.

**Closed this pass (XOrc 8 @7 — destroyer 1426 SE):**

Profile 35's type-two sea assault group takes **three** unmarked surface
naval attackers (not two). Sealed fixture assigns behavior 2 + home (98,122)
to native slots 1404/1424/1426. Taking only two left destroyer 1426 on
shore-base Patrol toward the refinery (87,71), so its first double-step was
NE (62,98) while native stepped SE toward the juggernaught home (62,102).
`retail-xorc-08-idle` is clean through 10. Regression:
`BattleNetSeaOccupancyTest.profileThirtyFiveAssaultGroupTakesThreeSurfaceShips`.

**Closed this pass (XOrc 11 @7 — destroyer 1519 Still):**

Non-capital naval action-5 goals within Chebyshev 6 that are not open water
are rewritten by the `FUN_004381d0` ray (last blocked cell toward the ship;
21,34 → 22,36). On the first free visit, an empty FOUND route surfaces Still
without the far-endpoint swap and without inventing a wall-follow approach.
Open-water one-stride wiggle delay 5 is gated to real open-water goals so the
fail lands on fixture cycle 7. Capital ships and far destroyers keep their
authored goals (distant 4,18→21,34 stays unrewritten). Regression:
`BattleNetSeaOccupancyTest.destroyerStillsWhenShipyardPatrolGoalIsBlocked`.

**Closed this pass (Orc 12 @8 — peon 1511 mine approach NE):** peon at
(58,47) harvests gold mine (58,44). Approach point is SW corner (58,46);
forcing pure-north onto that blocked cell was the c8 divergence. When the
one-tile approach delta is a pure cardinal into a blocked footprint cell,
bias the forced heading toward the resource centre so the first step is
north-east onto (59,46) like native. `retail-orc-12-idle` first remaining
div is critter@9. Cycle-8 frontier empty. Regression:
`BattleNetResourceApproachTest.orcTwelvePeonSouthOfMineLeftColumnStepsNortheastNotNorth`.

**Closed this pass (Orc 11 @8 — knight 1558 farm assault home):** AI profile
18 queues a four-fighter type-two land assault (unmarked reverse-roster land
attackers) with behavior 2 and home on the free square beside the person's
first farm (native 106,7 west of pig-farm 107,6). Ready pass Patrols that
shared home instead of nearest-enemy free squares (which aimed at the
alchemist 117,21 and stepped NE). `retail-orc-11-idle` first remaining div is
critter@10. Regression:
`BattleNetAiHomeTest.profileEighteenLandAssaultPatrolsFarmCorridorNotNearestEnemy`.

**Closed this pass (XOrc 11 @8 — destroyer 1558 E corridor):** pathfinder
passability is 1×1; SE (6,20) is under oil-platform BUILDING while E (6,18)
is free water. Wall sides both rejoin at (8,22) with rem 12; east scores
dist 16 (len 4) and south dist 15 (len 3). Taking the one-shorter south face
first-stepped to (4,20) while native steps east (route `02 03`). For
**stride 2 only**, when the shorter side wins by a single point and the
longer side's first step is pure major-axis toward the goal, keep the
major-axis side. Ungated (stride 1) regressed XHuman 6 peon 1483@2. Soft-
clearing platforms opened pure SE and broke h5. `retail-xorc-11-idle` clean
through 10. h10 42→43. Regression:
`BattleNetPathFinderTest.xorcElevenPlatformCorridorOpensEastNotSouth`.

**Closed this pass (Human 3 Still@8 — critter 1587):** phase-2 occupied-goal
re-aim runs only while `battleNetOrderDelay > 0` (Human 13 1572: 3,4→5,5
during the queued Move). Re-aiming again on the first free path visit burned
that visit without pathing, so 1587 stayed MOVE one cycle after native
empty-FOUND Still. With delay-only re-aim, Still@8-9 match; first remaining
div is re-wander@10 (Java STILL while native MOVE). Native raw for empty-
FOUND Still keeps seq 4718 timer 1 with no same-visit OP0; suppressing
same-visit idle to match that cursor regresses XOrc 2@8 and Human 13@5
(async interleave + free-visit re-aim) and is not shipped.

**Closed prior pass (XHuman 12 @7 — both 1503 and 1482):**

1. **Spatial order (1503):** `battleNetSpatialUnits` persistent screen-Y list
   (`FUN_00453c00` insert-before-equal, `FUN_00453ae0` end-tick stable sort).
   `findBattleNetHostile` walks that list with strict `score > best` so equal
   scores keep the first spatial hit. Grunt 1503 now targets the newer equal-
   score footman at 32,43 (not 29,43) and stays put. Regression:
   `BattleNetIdleAttackTest.equalScoreTargetsUseNativeSpatialOrder`.

2. **Wall-side progress (1482):** when `progressFrom` rejects a wall rejoin
   but the optimised first step still closes Chebyshev distance, keep that
   side. North wall for 22,42→25,42 now yields NE onto 23,41 instead of SE
   through the ally at 23,43. Regression:
   `BattleNetPathFinderTest.xhumanTwelveMovingAllyTowerApproachOpensNortheast`.

**Closed this pass (XHuman 12 peon 1497 @8):** gold-mine approach point
`(74,40)` sits on the blocked footprint; wall-follow invented pure south while
native stepped south-west onto `(75,39)`. `BattleNetPathFinder` now, when the
blocked cell is the exact goal and a free ray prefix exists, keeps that
prefix when its first-step Chebyshev gain beats wall-follow (same comparison
shape as the marked-goal ray/wall pick). Global `preserveBlockedGoalPrefix`
on all resource paths dirtied h5 and was rejected. `retail-xhuman-12-idle`
first remaining div is now @9. Regression:
`BattleNetAiWoodTest.xhumanTwelvePeon1497StepsSouthwestNotSouth` plus
pathfinder blocked-goal SW tests.

**Closed this pass (XOrc 2 @8 — critter 1580 re-wander):** empty-FOUND Still
arms timer **1** (native c7 timer 1), not 3. Still@7 holds; Move re-issues at
c8 toward 30,23. `retail-xorc-02-idle` clean through 12; Human 13@5 stays
green. h10 41→42.

**Closed prior pass (XOrc 2 Still@7):** building-footprint wander goals no
longer take the phase-2 occupied-tile re-aim early-return; they fall through
to empty-FOUND Still on the third delayed visit.

**Closed prior pass (architecture):** dual ChonkCraft/BNE profile selection
removed from the public load/trace surface; engine is always BNE.

**Closed prior pass (Human 9 @7 + XHuman 4 @7):** destroyers no longer chase
out-of-range air targets (order-16 mode). After the attack delay they drop
to Still and re-arm the Still constructor with timer 3 so the next marker
re-issues Attack three cycles later (native 3+3 cadence on the balloon).
h10 39→41. Regression:
`BattleNetSeaOccupancyTest.destroyerDoesNotChaseOutOfRangeBalloon`.

**Closed prior pass (XOrc 10 @6):** peasant 1573 under order 28 now walks
110,5→110,4 and stays BUILD through at least cycle 12. Soft-clearing the
builder during the BNE farm lattice accepted site 109,5 under the peasant's
feet so the foundation went down immediately (Java farm unmatched + peasant
removed). Leaving the builder as occupancy forces the spiral onto free
109,3 (native order point 110,4). Regression:
`BattleNetNoBuildTest.farmLatticeDoesNotFoundUnderTheStandingPeasant`.
Map is clean through 10+; h10 37→39.

**Closed prior pass (XHuman 12 @6):** grunt 1476 at 22,44 under order 12 now
steps NE to 23,43 with native. The marked-target ray took one free NE onto
23,43 then hit the tower-wall skirt at 24,43; wall-follow used to invent a
long south face (22,45). When the obstacle is a marked non-goal skirt tile,
the pathfinder still wall-follows but prefers the optimised clear ray when
its first step closes more Chebyshev distance than wall-follow's (tie keeps
wall-follow so neighbours 1463/1468 still open SE). First remaining div on
that map is now @7 (grunts 1482/1503). Regression:
`BattleNetPathFinderTest.xhumanTwelveTowerWallKeepsNortheastPrefix`.

**Closed prior pass (XHuman 4 @6):** axethrowers 1506/1516 now step SW/S with
native at fixture cycle 6. Empty marked-target FOUND paths used to set
`routeSpent` → PF_WAIT(10), so the chase slept through the cycle the
blocking grunt vacated 77,62. Short wait of 2 keeps the action-marker idle
draws Human 13 needs and replans in time. First remaining div on that map
is now @7 (other axethrowers 1435/1437). Regression:
`BattleNetIdleAttackTest.blockedAttackChaseDoesNotSleepTenCyclesOnEmptyRoute`.

**Closed prior pass:** `retail-orc-04-idle` transport 1561 (was 16,38 vs
native 17,39 at @6). Clean through at least fixture cycle 10.

**Action-30 transport order rewrite (ported):** GiveOrder stores the hall
top-left; `0x4381d0` walks the hall→ship Bresenham ray and keeps the last
square before open water (fixture mask treats coast-edge `0x0482` as blocked
even though transports may later sit on `COAST_ALLOWED`). Orc 4: 18,40 with
hall 5,11 → orderXY **17,37**, first step NW to 17,39. Free test must be
open-water only -- `canEnter` stops on coast and aimed Human 4 at the wrong
even-grid double step. Stride still keys off hall **bottom-right** parity
plus `|delta|!=1` to the shore goal (Human 5 double NW; Orc 4 single because
`|dx|==1`; Orc 5 single because hall BR is odd). Regression:
`BattleNetTransportStartupTest.startupTransportUsesHallToShipShoreRewrite`.

**Closed prior passes:**

- `retail-xhuman-08-idle` peon wood long-walk NE first step (was @6).
- `retail-xhuman-07-idle` person submarine Attack (was STILL @6).

**XHuman 8 long-walk wood order point (ported):** when no one-step approach
exists, reverse-free first-free on the resource ray unless its first step is
pure cardinal while the tree's is diagonal -- then keep the tree.
Regressions: `BattleNetAiWoodTest` peon 1510 / 1511 cases.

**Person permanent-cloak auto-attack (ported):** skip only when
`readySuppressed && person && !permanentCloak()`.

XOrc 8 tanker remains first-div cycle 7 after the cover-wait fix.

Previously closed at this horizon:

```text
retail-human-09-idle  clean through 10+ (no air-chase order-16 cadence; was @7)
retail-xhuman-04-idle clean through 10+ (cleared with air-chase fix; was @7)
retail-xorc-10-idle   clean through 10+ (farm lattice keeps builder occupancy; was @6)
retail-xhuman-12-idle clean through 6  (tower-wall NE prefix; was @6, now @7)
retail-orc-04-idle    clean through 10+ (transport shore rewrite; was @6)
retail-xhuman-08-idle clean through 10+ (wood long-walk NE first step; was @6)
retail-xhuman-07-idle clean through 10+ (person sub Attack; was STILL @6)
retail-orc-07-idle   clean through 5  (was critter 1512/1515 MOVE/STILL swap)
retail-orc-10-idle   clean through 5  (was critter MOVE/STILL)
retail-human-03-idle clean through 5  (must stay clean under critter gate)
```

### Proven changes in the current focused work

1. Ready gold mine selection without A* depot test (Human 13 cycle 8→11).
2. Worker assignment bits and counters at `0x004addcc` / `0x004b501c` /
   `0x004b503c`.
3. Projectile motion async RNG debits after the unit pass.
4. Constructor damage byte (fixed vs mobile constructors).
5. BNE integer projectile flight (`Missile.enableBattleNetMotion`, native
   speed 12 / min-flight 96, Bresenham `0x00429fa0`). Human 13 cycle 14→15.
6. Computer peon train: `AiPlayer.battleNetTryTrainWorker` ports the
   `0x439000` reserved-train quota `(workers-1)/2+1`, pulsed once per timed
   update for computer AIs. Cadence gate: Java cycle `>= 17` and
   `(cycle-17)%4==0`. Training halls stay Still under BNE. Human 13 15→19.
   Regression: `BattleNetTrainWorkerTest`.
7. BNE wood approach selection (`battleNetWoodOrderPoint`): among free
   neighbours of the tree that the worker can step onto in one tile, reject
   squares hard against a building (Chebyshev clearance &lt; 2) when a clearer
   option exists, then prefer the pure Bresenham first step toward the tree.
   Regressions: `BattleNetAiWoodTest` xorc-12 / xhuman-2 cases.
8. Distant oil-tanker enter gate: enter only when already
   `distanceTo(resource) <= 1` before the walk call. XOrc 8 tanker stays
   HARVEST-visible at (114,52) through cycle 5.
   Regressions: `BattleNetResourceApproachTest`.
9. Double-step refuse replan: `battleNetDoubleStep` ships clear the path on a
   refused heading instead of PF_WAIT(10). XHuman 7 destroyer west step at
   fixture cycle 5 once the tanker has left. Regression:
   `BattleNetSeaOccupancyTest.destroyerReplansWestAfterTankerLeaves`.
10. Open-water naval patrol endpoint: when a destroyer/sub has no oil
    tanker+platform+shore base, aim one double-step west of start instead of
    self/self (which left `stepPatrol` stuck on the at-endpoint delay swap).
    Short wiggle (0 &lt; chebyshev ≤ stride) uses order delay 5 so the step
    lands on fixture cycle 5 after the two init ticks; chebyshev 0 (behaviour
    six near=self) keeps delay 2 so XHuman 8's juggernaught still takes its
    east step on time. XOrc 10 clean through 5. Regression:
    `BattleNetSeaOccupancyTest.openWaterDestroyerPatrolsWestNotSelf`.
11. Combat-flyer ready Patrol: `AiPlayer.battleNetUnitReady` issues a
    self-endpoint Patrol for computer combat flyers that are not
    `onReadyExplores` and not `battleNetReadySuppressed` (UNIT.Data). Fires
    at the per-unit idle marker (staggered timers), draws no async RNG.
    XOrc 8 gryphons clean through 5; XOrc 7 suppressed gryphons stay Still.
    Regression:
    `BattleNetSeaOccupancyTest.unsuppressedCombatFlyerPatrolsOnReadyMarker`.
12. BNE double-step patrol defers `autoAttack` until a path exists so the
    first even-grid step stays under Patrol (XOrc 11 no longer flips to
    AttackMove before stepping).
13. Capital-ship wall-follow detour cardinal (XOrc 11 battleship 1511): when
    a battleship/juggernaught's stored first heading is a diagonal adjacent
    to the open even-snapped Bresenham first heading, the pure cardinal ray
    is free, and the non-pure component of that diagonal moves *away* from
    the goal on its own axis, replace the first heading with that detour
    cardinal. Native 20,40→18,40 (W) rather than Java's former 18,38 (NW).
    Gates avoid XOrc 8 SE (both axes closer) and XHuman 7 destroyer west
    replan (pure ray blocked). Soft-clear of non-moving allied ships was
    tried and **reverted** (made pure-N worse). Regression:
    `BattleNetSeaOccupancyTest.battleshipPrefersDetourCardinalWhenPureRayIsFree`.
14. BNE rescue admits flying prisoners. `rescueBattleNetUnit` used to return
    early on `Movement.FLY`, so XOrc 12's fire-breeze at (120,8) stayed on
    rescue-passive p0 while native handed it to person p5 on cycle 5 (adjacent
    p5 axethrower). Rescuers remain non-fly. Ownership finding closed.
15. BNE-profile `syncRand` uses retail LCG `state * 0x41c64e6d + 0x3039`
    (address `0x004a48dc`). LegacyEngine mult `0x48d159e1` remains for the
    ordinary profile. XOrc 12's fixture seed `1 → 0x41c67ea6` is exactly one
    retail step; LegacyEngine would produce `0x48d159e2`.
16. Impassable first-constructor critter wander (phase 0 only): after the
    first Still OP0 issues a one-tile Move whose dest fails
    `battleNetTerrainPassable`, keep the post-marker sequence offset with
    timer 5 and burn two async draws at issue+3/+4 so later critters stay on
    the restart-path stream. Passable first wanders and any later Still-loop
    wander still restart at Still-start with timer 3. Applying resume on a
    phase-1 impassable (Human 3 unit 11) shifted the shared async stream and
    was gated out. Clears orc-07 1512/1515 and orc-10. Unit fields:
    `battleNetConstructorStreamBurns` / `battleNetConstructorBurnAfterCycle`.
17. One-tile gold approach: when a BNE harvester is Chebyshev-1 from
    `battleNetApproachPoint` and not yet on it, force a single-heading path
    onto that square with order delay 2 (action 25 staging) even though the
    approach point sits on the blocked mine footprint. Clearing the whole
    mine during path search fixed XOrc 12 peasant 1396 (32,75→33,74) but
    rewrote longer approaches (48,10→48,14) from wall-follow SE (`path=344`)
    to pure S. Forced one-step only. Regression:
    `BattleNetResourceApproachTest.adjacentGoldPeasantStepsOntoApproachPoint`.
18. Terrain-harvest `0x423550` SyncRand under BNE:
    - Standing first swing: one draw when `gatherClockStarted` flips (work
      opcode 2660). Gold never draws.
    - Gold free-prefix forest re-aim (Orc 7 peon 1567): claim draw at
      StartGathering (2657) plus work-swing draw three cycles later (2660).
      Armed via `battleNetWoodWalkClaim` on the re-aim path.
    - Every later Harvest_wood animation loop (25 cycles, not
      `WaitAtResource` 24) re-draws at work 2660. Orc 7 peon 1576: fixture
      6 then 31; peon 1567: 24, 27, then 52.
    A period-wrap on the take clock fired one cycle early and desynced
    XOrc 12. Closed orc-07/xorc-12 sync_rng @27. Regressions:
    `firstWoodChopDrawsOneSyncRand`,
    `standingWoodcutterReseedsEveryAnimationLoop`,
    `walkClaimWoodStartDrawsWorkSwingThreeLater`.
19. Melee table `0x27` in-range Attack marker draws one `syncRand`
    (`0x4234b0`). Pending flag cleared without draw if the unit chases first.
    Closed human-05@6 seed.
20. Distant oil tanker cover wait: non-adjacent-start tankers wait 30
    HARVEST-visible cycles on first platform cover, then order-delay 3, then
    enter. Fixture-grounded on XOrc 8 (c5 cover → c37 BOARD → c40 enter).
    Adjacent-start tankers unchanged.
21. Person permanent-cloak idle auto-attack: `readySuppressed && person`
    no longer blocks `battleNetAutoAttack` when the type is
    `permanentCloak()` (subs). Closed XHuman 7 @6; h10 33→34 clean.
22. BNE long-walk wood order point: reverse-free first-free on the resource
    ray, unless that clip's first step is pure cardinal while the tree's is
    diagonal -- then keep the tree. Closed XHuman 8 @6; h10 34→35 clean.
    One-step clearance+Bresenham selection unchanged (XOrc 12 / XHuman 2).
23. Action-30 transport hall→ship order rewrite: open-water free test on the
    Bresenham ray (not `canEnter`/coast); stride from hall BR parity with
    `|delta|!=1` to shore goal. Closed Orc 4 @6; h10 35→37 clean. Holds
    Human 4/5, Orc 5/14, XHuman 5 through 10.
24. Empty marked-target attack route wait: two cycles (not ten) so XHuman 4
    axethrowers 1506/1516 replan when allies leave the approach. Closed that
    map's cycle-6 position cluster; first div now @7. Human 13/5 still clean.
25. Marked non-goal skirt ray vs wall-follow first-step gain: when the
    Bresenham ray is stopped by a `0x4508f0`-marked tile that is not the goal
    point, compare the optimised clear-ray first step with wall-follow's and
    keep the one that closes more Chebyshev distance (tie keeps wall-follow).
    Closed XHuman 12 grunt 1476 @6 (NE onto 23,43); neighbours 1463/1468 still
    open SE. First div on that map now @7. Regression:
    `BattleNetPathFinderTest.xhumanTwelveTowerWallKeepsNortheastPrefix`.
26. BNE farm lattice keeps the builder as occupancy: `canPlaceBattleNetBuilding`
    uses `canPlaceBuilding(null, …)` so the peasant's tile blocks. Soft-clear
    accepted XOrc 10 site 109,5 underfoot; native walks to 110,4 for site
    109,3. Closed sole c6 case; h10 37→39. Regression:
    `BattleNetNoBuildTest.farmLatticeDoesNotFoundUnderTheStandingPeasant`.
27. Out-of-range air auto-attack does not chase (order-16 cadence): after the
    attack delay, finish the order and re-arm Still with timer 3. Closed
    Human 9 destroyers vs balloon; XHuman 4 also cleaned at h10. h10 39→41.
    Regression:
    `BattleNetSeaOccupancyTest.destroyerDoesNotChaseOutOfRangeBalloon`.
28. Persistent BNE screen-Y spatial list for target selection
    (`battleNetSpatialUnits`, insert-before-equal, end-tick stable sort).
    Equal scores retain the first spatial hit. Closed XHuman 12 grunt 1503
    @7. Regression:
    `BattleNetIdleAttackTest.equalScoreTargetsUseNativeSpatialOrder`.
29. Wall-side accepted when `progressFrom` is zero but the optimised first
    step still closes Chebyshev distance. Closed XHuman 12 grunt 1482 @7
    (NE onto 23,41). Regression:
    `BattleNetPathFinderTest.xhumanTwelveMovingAllyTowerApproachOpensNortheast`.
30. Critter phase-2 re-aim skips building-footprint goals so empty-FOUND Still
    lands on the third delayed visit (XOrc 2 1580 Still@7). Occupied walkable
    tiles still re-aim (Human 13 constructor marker).

### Closed this pass (committed combat work is paid once)

Three related delays were symptoms of the same native rule: once an Attack
program commits work, changing range or losing the quarry does not make that
work disappear or charge it a second time.

- A committed melee opcode-10 visit still consumes its async damage roll when
  the named target has entered DYING; retail discards the result instead of
  damaging another target. Human 13 supplies three independent post-mortem
  swing witnesses. Java now consumes and discards the same roll.
- A ranged unit whose 63-cycle approach hold expires after the quarry has
  walked out of range chases immediately and remembers that the hold was
  paid. It does not walk the remaining empty Attack body or charge a second
  63-cycle hold after the chase step. Human 13 axethrower 1505 now creates its
  projectile at fixture 120 on both engines.
- A melee Attack-tail wrap pays construction 3,2,1 before dest-arming the
  leftover route. When that route lands in range, Move completion returns
  through the already-open Attack OP0 in the same scheduler visit. Human 13
  ogre 1511 is state-exact through the route and arrival: native and Java are
  both Attack@644/1 on fixture 130 and consume seed 4005032846 for the damage
  roll at fixture 137 in the same unit-pass position.

The fixed-denominator 52-case score through cycle 200 moved from 1,344,470 to
**1,344,591 of 1,369,366** paired unit-cycles in place (+121), and decision
agreements moved from 1,325,538 to **1,325,609 of 1,331,620** (+71). Differing
steps did not increase. The 52x1,800 semantic-v1 gate remained 8 clean / 44
divergent with no earlier divergence; the final same-visit OP0 correction only
touches Human 13, whose first semantic divergence remains cycle 39. The combat
RNG/callsite ledger is now exact through fixture 160; the next independent
causal mismatch is knight 1493's OP0 retarget/hold transition at fixture 161.

Focused regressions:
`BattleNetAttackResumeHoldTest`, `MeleeAttackTailWrapRetargetTest`,
`Human13Ogre1511WrapDestArmRealDataTest`,
`Human13OgreDestArmHoldRealDataTest`, and
`RangedOp0FreeScanRetargetHoldTest`.

### In-progress/provisional code

- Train cadence constants 17 and 4 are grounded in Human 13 observation, not
  yet proven as a general native timer.
- `battleNetProjectileDamage` still uses `missile.splashes()` as proxy for
  the fixed/max-damage table at `0x00494e2c`.
- BNE parabolic position/frame stepping is transcribed from `0x00410260`, and
  launch facing is now separately preserved from constructor field `+0x0a`.
- Wood approach clearance &lt; 2 is empirical from XOrc 12 / XHuman 2 fixtures.
- Long-walk reverse-free-vs-tree diagonal preference is fixture-grounded
  (XHuman 8 peons 1510/1511); not yet a full `0x4381d0` map-flag port.
- Transport rewrite open-water free test and hall-BR stride are fixture-
  grounded (Orc 4/5, Human 4/5, XHuman 5); Human 12 transport still @9.
- Open-water west-one-stride and delay 5 are fixture-grounded (XOrc 10).
- Critter impassable-first-wander resume is fixture-grounded (Orc 7/10); the
  two stream burns replace the restart path's non-wander draws.
- Capital-ship detour cardinal is fixture-grounded (XOrc 11).
- Oil cover wait of 30 is fixture-grounded (XOrc 8); not yet a full native
  timer port.
- Permanent-cloak person auto-attack exception is fixture-grounded (XHuman 7);
  surface person Data still suppresses acquisition.
- Marked-skirt first-step gain tie-break is fixture-grounded (XHuman 12
  grunts 1476/1463/1468); not yet a full native branch proof of `0x44fbd0`.
- Builder-blocks lattice placement is fixture-grounded (XOrc 10 farm);
  ordinary `orderBuild` still soft-clears the worker when the foundation is
  placed.

### Next task

1. Keep h5 at 52 after every change.
2. Earliest **cycle-10**: human-03 1587/1589; orc-11 1597; xhuman-02/10.
3. Orc-10 @12 1510/1513 swap once h10 is clean.
4. Re-survey h10 until clean:52; raise 10→20→…; full acceptance 52×1800.

Focused tests (green):

```text
mvn -q -o -pl engine -am \
  -Dtest=BattleNetTrainWorkerTest,BattleNetMissileMotionTest,BattleNetAiWoodTest,BattleNetSequenceTest,BattleNetIdleAttackTest,BattleNetPathFinderTest,BattleNetResourceApproachTest,BattleNetSeaOccupancyTest,BattleNetTransportStartupTest,BattleNetNoBuildTest,SecondPersonSlotTest,BattleNetAiHomeTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

### Useful diagnostic artifacts (this pass)

```text
scratch implementer dir:
  bne-survey-h5-1461 (52/0)
  bne-survey-h10-1461 (48/4)
  orc12-1461-raw.txt / orc10-1510-raw.txt / orc10-1513-raw.txt
  1461-t1/ (orc-12 clean@12; orc-10 clean@11)
  bne-focused-coast.log
  bne-orc10-idle.stderr (1526 OP0 choices)
handoff:
  bne-grok-handoff-index (temporary Markdown artifact)
  bne-native-empty-route-sidecar (temporary Markdown artifact)
  bne-native-cycle7-sidecar (temporary Markdown artifact)
```

### Useful diagnostic artifacts

```text
scratch (private implementer dir):
  bne-survey-h5-nav (52/0) -- authoritative h5 after air-chase cancel
  bne-survey-h10-nav (41/11) -- H9 clean; c7 has 4 cases
  bne-h09-fix3 -- human-09 clean through 14
  bne-survey-h5-xorc10 / h10-xorc10 (52/0, 39/13)
  CHONKCRAFT_TRACE_BNE_PATH / CHONKCRAFT_TRACE_AIBUILD / CHONKCRAFT_TRACE_BNE_IDLE
  local corpus: tools/bne-harness/work/corpus/campaign-1800/
/private/tmp/retail-human-13-idle-1800.bnefx
/private/tmp/Warcraft-II-BNE-2.02b.exe  (sha256 b0e914a9…d2c807)
```
