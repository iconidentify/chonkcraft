# Project status

ChonkCraft is a playable public beta. Campaigns, skirmishes, combat, economy,
construction, fog of war, computer opponents, spells, upgrades, sound, music,
save and load, and lockstep multiplayer are implemented. All 52 campaign
missions load and run from an authenticated ChonkPack built from original game
media supplied by the player.

Every multiplayer client passively keeps a bounded local flight record under
`~/.chonkcraft/recordings`. Current schema-2 bundles seal the exact lobby map,
cycle-zero save and accepted command stream by byte count and SHA-256, retain
the complete 16-slot controller/race table, name the synchronization-hash
schema, and bind an OTA-launched match to its installed game JAR and source
revision. They record no chat, upload nothing, and add no replay viewer. The
strict referee reconstructs the saved world from authenticated game data and
replays every accepted batch through the ordinary command and tick boundaries.

Legacy schema-1 bundles remain readable diagnostics but can never certify: they
did not seal their artifacts or retain the player table, producing JAR, source
revision, or synchronization-hash schema. The held-out 13-hour Forsaken Isles
recording (282,670 network cycles, 1,413,346 world cycles and 621 commands)
authenticates structurally, now reconstructs its initial island world exactly,
and replays through network cycle 256 before the current referee rejects a hash
difference at cycle 257. That bounded prefix is useful evidence, but the missing
legacy identities prevent attributing the later difference to either its older
`2026.0827.77` engine or current source.

## Measured Battle.net Edition parity

The current accepted source has been compared with Warcraft II: Battle.net
Edition 2.02b across the complete 52-map authenticated campaign fleet:

- **52/52 maps are exact through cycle 267.** The lowest common divergence is
  cycle 268, so the shared proven frontier is cycle 267. Expansion Human 12
  now defines that boundary; expansion Human 11 is exact through cycle 319.
- **30/52 maps are exact through the full 400-cycle window**, with 22 later
  divergences and no execution failures. The sum of all per-map exact
  frontiers, capped at 400, is 18,924 cycles; no map moved backward.
- **8/52 maps are exact through the full 1,800-cycle window**, with 44 later
  divergences and no execution failures. Their all-map exact-frontier sum is
  35,189; no map moved backward. The eight are Human 1, 2, and 9; Orc 1, 2,
  and 6; and expansion Orc 1 and 9.
- The queued-return convoy milestone advances expansion Orc 6 from exact
  through cycle 273 to 284. A collision-free queued gold returner immediately
  behind a stopped collision-bearing same-depot returner on the direct depot
  ray is transparent to both route traversal and wall optimization. Expansion
  Orc 6 and expansion Human 12 independently authenticate the resulting
  two-diagonal routes with opposite opening headings. A queued returner as the
  first obstruction, a later queued returner on an otherwise clear ray, and
  blocked or vacated paid-wake cases retain their prior behavior. Expansion
  Human 12 also proves the retained tail is invalidated when its nearest hall
  edge changes, preserving the native cycle-247 retarget and cycle-248 redraw.
  All other 51 fleet frontiers are unchanged, so both aggregates gain eleven
  cycles without changing the shared horizon or clean-map counts. Durable
  cycle-400 run `bd3e9f5e` and accepted cycle-1,800 run `3fbd5a8c` bind engine
  input `897305ed` and replayable source capsule `628f6044`. The rule has no
  map, unit, cycle, coordinate, or faction exception.
- The plain-Move refusal-replacement milestone advances Human 13 from exact
  through cycle 277 to 280. When a terrain-only plain-Move buffer refuses a
  later byte, its next route generation plans through allied plain-Move bodies
  that are actively draining a collision-free route, while settled or collided
  allies remain solid. That replacement generation cannot invoke the initial
  terrain-line writer again. Human 13's eastern and southern ogres independently
  authenticate `[NW,NE]` and `[NW,NE,NW,W]` replacement routes after different
  refused buffers. Their initial full terrain lines, a sibling's occupied-face
  detour, and the existing non-cooperative residual rule remain unchanged. All
  other 51 maps are unchanged, so both aggregate fleets gain three cycles with
  no change to the shared horizon or clean-map counts. Durable cycle-400 run
  `592e8de8` and accepted cycle-1,800 run `152de1e7` bind engine input
  `b9bfdd8a` and replayable source capsule `f53764f9`. The rule has no map,
  unit, cycle, coordinate, or faction exception.
- The laden-return timer-one milestone advances expansion Human 7 from exact
  through cycle 285 to 302 and Orc 5 from 288 to 453. When a worker's paid
  cooperative return wait reaches timer one and its cached next square has
  opened, retail consumes that retained heading in the same visit without
  adding another collision or parking the route; Java now does the same.
  Expansion Human 7 and Orc 5 independently authenticate different diagonal
  headings and vacated blockers. Expansion Orc 6 holds the uninterrupted-route
  boundary, while expansion Human 8 still parks and replans when the head
  remains blocked and expansion Human 10 renews the full band behind a laden
  convoy. All other 50 maps are unchanged. The cycle-400 aggregate gains 129
  cycles and one clean map; the cycle-1,800 aggregate gains 182 cycles without
  changing its clean count or the shared horizon. Durable cycle-400 run
  `eafece73` and accepted cycle-1,800 run `8a25ffb7` bind engine input
  `16015d02` and replayable source capsule `4aeffc43`. The rule has no map,
  unit, cycle, coordinate, or faction exception.
- The corpse-owner handoff milestone advances expansion Human 2 from exact
  through cycle 349 to exact through 351. Retail leaves a newly installed
  corpse or rubble record with the living unit's owner through its first held
  decay frame, then transfers the fading scenery to neutral on the next frame
  transition; Java now does the same. The sealed fleet contains 76 observable
  mobile-body handoffs and 13 building-rubble handoffs across ten campaigns,
  while 77 body creations retain their living owner. All 70 bodies that expire
  inside the capture are neutral before release. Revealers, corpseless balloon
  and zeppelin deaths, and unrelated live type changes remain outside the rule.
  Every other map is unchanged, so both aggregate fleets gain two cycles with
  no change to the shared horizon or clean-map counts. Durable cycle-400 run
  `34910153` and accepted cycle-1,800 run `3fc6c609` bind engine input
  `aebf63ec` and replayable source capsule `7f1eb2d1`. The rule has no map,
  unit, cycle, coordinate, or faction exception.
- The unified-corpse milestone advances expansion Orc 11 from exact through
  cycle 281 to exact through 283. All 14 authenticated naval deaths in the
  sealed fleet become native raw type 105, covering human and orc destroyers,
  a battleship, and an ogre juggernaught across three campaigns; the existing
  77 authenticated land deaths use the same raw type. Java now normalizes the
  legacy sea-body alias onto the same generated corpse definition as the
  existing orc-body alias. Destroyed buildings keep their sized rubble,
  balloons and zeppelins still leave no corpse, and all other 51 maps remain
  unchanged. Both aggregate fleets gain two cycles without changing the shared
  horizon or clean-map counts. Durable cycle-400 receipt `83be37f2` and accepted
  cycle-1,800 receipt `41c1d87e` bind engine input `b5287494` and replayable
  source capsule `157f3535`. The rule has no map, unit, cycle, or coordinate
  exception.
- The consumed depot-tail milestone advances expansion Human 5 from exact
  through cycle 287 to exact through 322 and expansion Human 11 from 310 to
  319. When a laden land worker has already consumed a route and reaches a
  refused two-byte tail containing the same cardinal heading twice near its
  depot, native discards that stale tail and replans on the next return visit;
  Java now does the same instead of restoring and retrying the wrong cardinal
  after traffic clears. The two campaigns independently authenticate different
  cardinal pairs and replacement headings. Fresh duplicate routes, one-byte
  routes, non-duplicate consumed tails, duplicate diagonal tails, direct
  one-byte refusal, and shared-blocker traffic remain outside the rule. All
  other 50 maps are unchanged, so both aggregate fleets gain 44 cycles without
  changing clean-map counts or the shared horizon. Durable cycle-400 run
  `efd79afa` and cycle-1,800 run `ba7c7b36` bind the final engine input;
  accepted receipt `b0c7fe14` seals replayable source capsule `054ac971`.
  The rule has no map, unit, cycle, or coordinate exception.
- The paid naval patrol-redraw milestone advances expansion Orc 8 from exact
  through cycle 278 to exact through 301. A targetless behavior-six small
  warship with a consumed cached tail now parks behind a collision-pressured
  allied hull. A one-step consumed tail pays the native Move 15..1 band before
  redrawing by continuing its refused wall face against beginning-of-pass
  occupancy; longer consumed histories redraw immediately. Two submarines
  independently authenticate longer parks behind different pressured
  destroyers, and a destroyer authenticates the paid one-step form. The same
  destroyer earlier keeps its tail behind an unpressured submarine and commits
  after that hull vacates; a fresh route is a second negative control. All
  other 51 maps are unchanged, so both aggregate fleets gain 23 cycles without
  changing clean-map counts or the shared horizon. Durable cycle-400 run
  `65b7a2e8` and accepted cycle-1,800 run `cbca1296` bind the candidate source
  capsule. The rule has no map, unit, cycle, or coordinate exception.
- The shared-depot return milestone advances expansion Orc 12 from exact
  through cycle 273 to exact through 374. Loaded workers now retain a native
  direct route byte through a loaded same-depot sibling that is already queued
  to vacate it; a later direct byte replaces only a two-byte detour with the
  same endpoint, and the immediate queued sibling qualifies longer cached
  routes for the native refusal-eight Move 15..1 band. Expansion Orc 12 and
  expansion Human 12 independently authenticate the two forms. Existing
  direct-ray, occupied-route, and negative-Move controls retain their prior
  behavior. All other 51 maps are unchanged, so both aggregate fleets gain
  101 cycles without changing clean-map counts or the shared horizon. Durable
  cycle-400 run `18b00e69` and accepted cycle-1,800 run `3cc105b0` bind the
  clean source revision and engine input. The rule has no map, unit, cycle, or
  coordinate exception.
- The occupied-critter refusal milestone makes an empty route to a square still
  owned by a moving animal enter the native sticky collision ladder instead of
  receiving a free retry and standing down as soon as traffic clears. Orc 13
  and Human 14 independently authenticate the occupied branch, while earlier
  completed wanders by the same two animals preserve the unoccupied terminal
  branch. The rule contains no map, unit, cycle, or coordinate exception.
  Human 11 advances from exact through 281 to 299; Human 14 from 398 to 400 in
  the short fleet and to 405 at long horizon; Orc 13 from 292 to 400 and 548;
  and expansion Human 1 from 554 to 1,293. The cycle-400 aggregate gains 128
  cycles and two clean maps, while the cycle-1,800 aggregate gains 1,020 cycles
  with no regression. Durable cycle-400 run `182cd53f` and accepted
  cycle-1,800 run `e8309fec` bind the surveys and structural source capsules.
- The claimed-tree replacement milestone advances expansion Human 11 from
  exact through cycle 266 to exact through 310. When another woodcutter has
  claimed the current tree, the native replacement search accepts a candidate
  only when its connected 3-by-3 neighborhood has an approach face free of
  the live blocking-body mask. Seven authenticated fleet choices require the
  full native mask, while 36 held target choices preserve the existing result.
  Ordinary and AI tree acquisition retain their separate pre-search state.
  All other 51 maps are unchanged in both complete fleets; cycle-400 and
  cycle-1,800 aggregates each gain 44 cycles, while clean-map counts remain 27
  and 8. Accepted run `98bfa977` binds the 1,800-cycle survey and structural
  source capsule; run `713adea3` records the same fleet result through cycle
  400.
- The paid-tail handoff milestone advances expansion Human 12 from exact
  through cycle 264 to exact through 267, raising the shared fleet frontier by
  two cycles. Terrain-wood workers now keep a committed two-byte route behind
  an allied worker for the native Move refusal band instead of redrawing early,
  and combatants with a repeatedly refused long route commit the bounded
  replacement prefix in the same target-handoff visit. Independent native
  positives cover different route shapes and refusal generations; short
  construction routes, quiet shortcut candidates, fresh collision visits,
  compact combat pressure, dying targets, and saturated first steps remain
  outside the rules. The cycle-400 and cycle-1,800 aggregate frontiers each
  gain three cycles, clean-map counts remain 27 and 8, and none of the other
  51 maps regresses. Accepted run `81972961` binds the 1,800-cycle survey and
  its structural source capsule; run `d983ebc0` records the same fleet result
  through cycle 400.
- The plain-Move route-writer milestone moves Human 13 from exact through
  cycle 264 to exact through 277. After refusal recovery, native writes the
  whole bounded terrain line while ignoring mobile occupants during planning;
  it refuses only when a stored byte is later consumed, then redraws on the
  next visit. Human 13 ogres 1519 and 1510 independently authenticate the
  behavior with different line buffers and refusal cycles. Sibling 1501 keeps
  its ordinary detour when the first line square is already occupied, while
  fresh acquisitions and borrowed attack-chase routes remain outside the
  rule. All other 51 maps are unchanged in both complete fleets. Durable
  cycle-400 run `cc6a3470` and accepted cycle-1,800 run `e0dfea7c` record the
  thirteen-cycle gain and bind their structural source capsules.
- The complete naval-route milestone moves expansion Human 7 from exact
  through cycle 265 to exact through 285. A full twenty-byte route remains
  authoritative after its first headings are spent instead of being rewritten
  onto a free but geometrically closer patrol corridor. Expansion Human 7's
  submarine and Orc 13's tanker independently authenticate different retained
  headings; expansion Orc 11's short wall-follow residual and a capital-ship
  detour preserve the free-closer and detour boundaries. All other 51 maps are
  unchanged in both complete fleets. Accepted run `4f638ea2` binds the
  cycle-1,800 survey and structural source capsule; cycle-400 run `f76fe858`
  records the same twenty-cycle gain.
- The repeated laden-convoy return milestone moves Human 14's exact frontier
  from cycle 391 to 398. When a paid return-route refusal band expires and the
  same clean moving laden convoy still blocks its cached heading, the worker
  retries that byte for another native Move 15 band. Human 14 and expansion
  Human 10 independently authenticate the behavior; a collision-marked
  returner and a vacated blocker authenticate the park-and-redraw boundaries.
  All other 51 maps are unchanged in both complete fleets. Accepted run
  `10c982f2` binds the cycle-1,800 survey and structural source capsule.
- The pressured-hull route-redraw milestone moves expansion Orc 8 from cycle
  268 to 279. A targetless naval router with multi-step route provenance parks
  its cached route when the allied mobile hull ahead has entered its own
  collision ladder, then redraws on the next visit. Two independent native
  submarines authenticate different route shapes behind different pressured
  destroyers. At that checkpoint the one-step form remained a held-out
  boundary; the current paid patrol-redraw milestone above closes its pressured
  form while preserving the unpressured hold. All other 51 maps are unchanged
  across both complete fleets; the cycle-400 and cycle-1,800 counts remain 27
  and 8 with zero failed cases. Accepted cycle-1,800 run `196c84ca` binds survey
  `9af422e2d064fa81e65b9991db8f1cf9c44d13ea92678dd4ba497c803515f3c8`
  to engine-input identity
  `035cfeba01a33b1fd47b806e2d1b9c16ce2e5c0a42679ba97aa7e3e7ca73c043`.
- The crowded-depot milestone moves expansion Orc 12 from cycle 264 to 273 by
  letting a staged laden worker commit its final entry behind an allied worker
  whose own entry pixels are still draining. Expansion Human 7 independently
  proves the same overlap, while pre-stage and stationary-worker controls stay
  blocked. The other 51 maps are unchanged across both complete fleets.
- Computer oil tankers now preserve native action 23 behind the 25-cycle
  depot-ready Still head. Two independent campaigns advance without a fleet
  regression: Orc 7 is exact through 823 instead of 596, and Orc 10 through
  485 instead of 439.

"Tested through cycle 400" or "through cycle 1,800" describes coverage, not a
claim that every map is exact for that whole window. The common frontier is
always one cycle before the earliest authenticated mismatch across the fleet.

## Playability and release gates

The current player-contract receipt certifies **18/18 playability lanes** with
zero failures, skips, blocks, or timeouts, including the read-only end-to-end
control-liveness referee. The gate
covers authenticated boot and assets, deterministic scheduling, movement,
orders, economy, construction, combat, projectiles, naval oil, spells, retail
AI, campaign triggers, save/load, rendering and input, sound, control liveness,
and clean/adverse network lockstep.

The complete suite now contains **2,832 tests**. The canonical hosted
authenticated profile runs 2,805 and intentionally skips exactly 27. A local
classic-install profile with the three private playtest-save referees installed
runs 2,808 and skips exactly 24. The exact BNE-source profile used for the
current milestone runs 2,802 and skips exactly 30; its release-format-specific
skip identities are recorded separately rather than weakening either classic
profile. All retain the exact expected 110-test specification-failure set.
With no licensed inputs, 1,622 tests run, 1,210 skip,
and the exact 89-test data-free failure set remains unchanged. The added
boundary matrix proves that completed land, naval and air trainees survive
save/resume without being born twice, while a fully blocked trainee remains
unborn until a legal exit opens. New training completions now retire atomically in the trainee's birth
cycle while schema-2 loading remains compatible with older saved completion
latches. The synchronization hash covers that latch, so peers report any old
boundary disagreement before a duplicate can appear. The physical transaction
adapter now has retail-backed referees for both a sealed null target over
occupied ground and a sealed explicit friendly-unit target; they add two
intentional data-free skips and run in the authenticated profile. Exact-boundary research
and in-place hall-upgrade referees now prove that resume neither charges again
for nor revokes completed research, and never duplicates, moves, or resurrects
a transformed building. Those two retail-roster referees deliberately add two
data-free skips. The paid naval predecessor exercised all 2,796 then-available
locally authenticated tests with the exact expected failure identity before
its receipt was promoted. Its patrol assertions extend
the authenticated small-warship referee without changing the suite or skip
inventory; the complete preceding movement inventory remains green. The same
candidate passed all 18 playability lanes, all 39 clean/adverse lockstep cases
through cycle 1,800, and a real two-process 180-cycle match with terminal hash
`1b9dd8ad7b9a1f51`. The consumed depot-tail candidate repeated the complete
matched-input profile at 2,799 run and exactly 24 skips, including its three
new authenticated sequence referees, with the same exact 110 expected failure
identities. It then repeated all 18 playability lanes and the clean/adverse
network checks without a regression. The unified-corpse candidate repeated
that acceptance sequence: 18/18 playability lanes, 39/39 clean/adverse lockstep
cases through cycle 1,800, a real two-process 180-cycle match at hash
`1b9dd8ad7b9a1f51`, 8/8 exact BNE pack-parity checks, all 2,825 suite tests
accounted for under the 30-skip BNE profile, and the same 110 expected failure
identities.
The corpse-owner handoff candidate repeated the same acceptance sequence:
18/18 playability lanes, 39/39 clean/adverse lockstep cases through cycle
1,800, the real two-process match at hash `1b9dd8ad7b9a1f51`, 8/8 pack-parity
checks, all 2,827 suite tests accounted for with 2,797 run and 30 exact skips,
and the same 110 expected failure identities.
The laden-return timer-one candidate repeated that acceptance sequence:
18/18 playability lanes, 39/39 clean/adverse lockstep cases through cycle
1,800, the real two-process match at hash `1b9dd8ad7b9a1f51`, 8/8 pack-parity
checks, all 2,830 suite tests accounted for with 2,800 run and 30 exact BNE
skips, and the same 110 expected failure identities. The separately measured
data-free profile ran 1,622 tests, intentionally skipped 1,208, and retained
the same 89 expected failure identities.
The plain-Move refusal-replacement candidate repeated the complete applicable
sequence: 18/18 playability lanes, 39/39 clean/adverse lockstep cases through
cycle 1,800, the real two-process match at hash `1b9dd8ad7b9a1f51`, 8/8 pack-
parity checks, all 2,830 suite tests accounted for with 2,800 run and 30 exact
BNE skips, and the same 110 expected failure identities.
The queued-return convoy candidate repeated that complete sequence on its final
code: 18/18 playability lanes, 39/39 clean/adverse lockstep cases through cycle
1,800, the real two-process match at hash `1b9dd8ad7b9a1f51`, 8/8 pack-parity
checks, all 2,832 suite tests accounted for with 2,802 run and 30 exact BNE
skips, and the same 110 expected failure identities.

The next-level readiness inventory passes its present fail-closed executable
checks while correctly remaining open for the incomplete proof lanes below.
Signed engine OTA `2026.0830.115` is the public release, published from revision
`cb98855979c04aa707d27ad808c92d748d42905a` with game JAR SHA-256
`a133db59509d6961b1c1cdcf6e9d91fb59e73e24052431b6c8e7e15875c2ddeb`.
Its workflow proved both local installation and a fresh launcher install from
the public endpoint. No matchmaking protocol, service, or infrastructure path
changed, so a server rollout was not required.

## Broader fidelity frontier

These denominators are deliberately strict and remain open work rather than
being inferred from the playable campaign fleet:

- Resolved-command cells: **11/240 current generated cells are identity-joined**
  and **6/240 are exact**. The original Human 1 Patrol, Orc 1 Move, and Orc 1
  turn-boundary Attack cells remain exact. A breadth-first native capture pass
  adds exact Human 1 and Orc 1 group-Patrol cells plus an exact Orc 1 refused
  Train cell. The five explicit divergences are Human 1 turn-boundary Attack,
  expansion Human 12 Harvest and turn-boundary Attack, Orc 1 occupied-ground
  Attack Move, and expansion Human 12 refused Train; 229 cells remain
  uncaptured and none failed because of infrastructure. Four additional
  authenticated Stand Ground/Stop, Attack Move, and Return Goods/Repair
  executions do not match a current generated-cell identity and remain
  diagnostic rather than inflating coverage. The expansion Attack exposes a
  real order-resolution split (native accepts before the unit becomes
  unavailable; Java rejects and leaves Harvest installed), while the Human 1
  Attack ends at a different tile and hit-point value. The other prior
  dual-adapter executions remain historical/unmatched diagnostics, so a count
  cannot fill the denominator. Every cell binds map, initialization seed,
  complete command content (including production type), and terminal
  observation cycle. The gate regenerates all 240 cells from three hash-pinned
  Human, Orc, and expansion seeds, then reopens the inventory and execution
  ledger before accepting a numerator. Current clean scorecard
  `0805a47817d4991639c94d7296519937ab0aa2f3a0af1c202d662b341564ceec`
  binds command ledger
  `43b114deaefb346c57b36e54397b5a89ce660a3c688ee975deb0452135007a7b`
  and split report
  `c204a868fc1b6fca3c83b09e6ca0e0446a99938f7c7d0602b7e963d85d260a30`
  to the current engine identity.
- Physical player transactions: **3/532** current-source paired certifications.
  A retained store reopens twenty-one native capture closures across the Human
  and Orc campaigns and reruns their packaged Java twins with producer evidence
  verified. Eight fixed cells are observed on both producers. The current
  proof-store identity is
  `9a116fdfa0f12b502a7cb79c39afbcc9606e0d5a5ce63b66ba846b833c6a661a`.
  The single-unit, field/plain Move cells for open and occupied ground are each
  exact across two independent scenarios. The two-unit occupied-ground cell is
  also exact across independent Human and Orc scenarios after canonical
  receipts order terminal outcomes by their recorded completion cycle. The
  two-unit open-ground held-out remains red by two cycles and stays explicit
  movement debt rather than receiving a scenario-specific exception. Four new
  sealed captures authenticate friendly unit and building targets independently
  on Human 1 and Orc 1, expanding observed target shapes from open/occupied
  ground to unit/building and reducing the executable capture debt from fifteen
  to thirteen cells. They deliberately do not increase the numerator: retail
  serializes both targeted right-clicks as Move-with-target, Java selects Follow
  for a friendly unit, and the building approaches settle at different tiles or
  cycles. No gameplay change follows from that bounded cross-map diagnostic.
- Replay twin: **0/764,756** dispatcher records in a complete 27-replay paired
  certification.
- AI fleet: **52/52** current-head mission twins are materialized under one
  validated conductor report; **45/52** are committed-state and causal-
  telemetry exact through 1,800 cycles. The fixed denominator is 205,200
  computer-player cycles: 202,290 committed-state rows and 202,285 telemetry
  rows are exact. Seven ranked frontiers remain, so fleet certification is
  incomplete. The clean rematerialized Java proof is
  `8f93939c67708e94367307cac90d139d89cf4161d334cddf01d04469b61e410e`;
  its retained report SHA-256 is
  `bc22c7a54fb5ea1e0526bebbd41f13aeb6a2519163510bbd83c6851a96d4d8fa`.
  The conductor proof is validated fail-closed against the current clean
  source, engine, Java adapter, app JAR, ChonkPack and retail `ai.bin`
  identities.
- Combat lifecycle: **21/185** accepted cells across four independently
  retained melee, ranged and siege proofs. Every proof is reopened from its
  producer evidence and bound to the pinned native executable, current clean
  Java engine, and current requirements; stale or mixed-authority proof sets
  contribute no borrowed credit and cannot certify the lane. Campaign
  lifecycle: **0/137** accepted trigger twins.

The remaining zero numerators do not mean those game systems are absent. The
52-map `semantic-v1` survey and the playability lanes do not emit these proof
types. Each lane accepts only a complete native/Java twin report bound to the
current engine and program identity, and it fails closed rather than carrying
forward partial, detached, legacy, or stale receipts.

The source of truth is executable evidence: the enforced test profiles, the
18-lane playability receipt, the authenticated retail comparison harness under
`tools/bne-harness/`, packaged-launcher checks, and focused regression tests
beside the behavior they protect.
