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

## Current release checkpoint — 2026-08-31 (cycle-327 offered-building wall rejoin)

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
