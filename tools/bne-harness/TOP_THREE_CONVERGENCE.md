# Top-three BNE convergence checkpoint

Date: 2026-08-11

Second-wave base control: `33b9318d`

Implementation branch: `codex/bne-top-three-convergence-2`

This checkpoint completes the three highest-leverage parity efforts selected
by the audit: deeper authenticated state comparison, a controlled native
decision corpus, and an AI-executive differential queue. It also applies and
proves the first systemic engine correction exposed by each behavioral lane.

## 1. Semantic-v2 whole-state comparison

The oracle's sealed schema-1.1 state is now compared against additive Java
trace rows for player economy/research, sub-tile units and animation state,
projectile lifecycles, and changed terrain. Family filtering keeps large runs
practical. Unit facing and raw order-point coordinates are deliberately marked
uncovered until their unlike representations have an authenticated mapping.

The compact player lane completed all 52 fixtures through 1,800 cycles. The
AI ranker separates casualty fallout from independent economy or research
decisions, replacing manual inspection of 93,600 fixture cycles with a short,
ordered policy queue.

## 2. Controlled native decision corpus

`command-matrix` generated and remotely captured 19 authenticated move cases:
all eight ground headings, four air headings, four sea headings, and three
occupied-destination cases. The exact command and its native pool slot are
sealed in every fixture; ambiguous or unauthenticated cases fail closed.

The corpus exposed one shared player-command boundary. Ordinary internal move
orders keep their existing cadence, while serialized player/network commands
now read the interrupted animation program to find its real action marker and
read the unit's cold Still program to find the replacement marker. This
replaces both guessed clocks with the scripts retail executes.

The same corpus then exposed and closed four downstream rules: terminal routes
complete without an invented pathfinder wait; an occupied empty route executes
its replacement Still marker in the same visit; every flyer uses retail's
doubled movement lattice; and a doubled mover skirts an occupied point and
accepts the occupied stride-neighbour selected by the native wall follower.
Autonomous scouts interrupted by a point command re-enter the global scout
callback before the next per-unit idle walk, preserving both the fresh patrol
point and ownership of the two asynchronous draws.

Results:

- through cycle 20: **0/19 clean before, 19/19 clean after**;
- through cycle 160: the commanded subject is exact in **15/19** cases;
- the remaining four subjects are exact through cycle 133 and first differ at
  134, after their commanded moves completed and a renewed autonomous patrol
  had already run for more than eighty cycles;
- every commanded ground unit and ship is exact through 160, including all
  three occupied-destination cases; and
- the occupied Human 13 daemon now stores retail's `NE,NE` route, lands at
  `86,2`, and becomes Still on retail's cycle 62 rather than sleeping eleven
  extra cycles.

The ordinary whole-fixture survey is 7/19 clean through 160 because these
campaign maps also contain uncontrolled critters and combatants. Subject-level
scoring separates those ambient divergences from the authenticated command the
corpus was built to adjudicate.

The engine rule is shared by local commands, network commands, and the parity
driver. AI/internal movement remains on its separately measured path.

## 3. AI executive differential queue

The player-only 52-by-1,800 run originally ranked one independent research
policy mismatch first: Orc 7 spent 300 gold and 300 lumber on ranged research
although retail kept both the bank and tier unchanged through cycle 1,800.

The native profile distinction is now modeled at the profile-list boundary.
Pure construction-milestone lists wait for a live ready-worker scan; a high
milestone reached after a low-byte construction prefix is installed by that
bootstrap scan; spell blocks remain profile-installed because they do not
require a worker. This fixes the Orc 7 over-spend without regressing XHuman 10
profile 67, XOrc 8 profile 35, or Human 14's spell milestone.

Measured after the fix:

- Orc 7 player state: **108,000/108,000 comparisons exact** through 1,800;
- independent research-policy cases: **1 to 0**;
- exact player-policy cases: **12 to 13**; and
- Human 7's premature 300/300 spend also disappears, moving its coarse first
  divergence from cycle 60 to cycle 72.

The next independent AI item is now XHuman 6's extra building at cycle 311.

## Regression and certification evidence

- 34 focused Python harness tests pass.
- `EngineTraceCommandPlanTest` and `BattleNetTrainWorkerTest` pass.
- The 52-case cycle-80 candidate has **zero regressions** against both released
  master `cb51738d` and the authenticated pre-command convergence survey. The
  regression receipt passes across all 52 cases.
- The 17-lane playability gate's stale-report, test-count, signed-catalog, and
  environment-path issues were corrected or supplied during this checkpoint;
  every individual lane passes with its authenticated inputs.
- Player-command ownership, both queue clocks, and the interrupted current
  action now survive save/load and participate in the multiplayer sync hash.

## Second-wave closure: movement, combat causality, and the AI queue

The next audit reused the authenticated command corpus and player-state ranker
to close the first concrete finding in each of the three highest-impact lanes.

### Movement and refusal lifecycle

The remaining four air-command subjects were not four unrelated patrol bugs.
They shared two lifecycle errors: a doubled flyer finishing its route invented
an extra wait, and a spent point route did not preserve retail's occupied-step
refusal. The same investigation found that a unit with a non-zero native
refusal count must remain solid to the planner's soft-clear view.

After the general correction, all four north/east/south/west air subjects are
exact for position, hit points, order and heading through all 160 measured
cycles. Across the full dense 52-case field corpus, paired positions improve
by 511 and decision mismatches fall from 6,588 to 5,922, a 10.1% reduction,
with no earlier semantic regression.

The Human mission 5 save captured during this work was also replayed directly.
Its former barracks occupied tiles 46–48 by 92–94. The loaded world contains
no stale building or unit flag on any of those nine tiles, and the selected
archer at 45,94 accepts the real player command and crosses the footprint to
49,94. A focused lifecycle test now requires a killed building to become
non-solid rubble immediately and requires a ground unit to traverse the whole
former footprint while that rubble is still visible.

### Combat causal pipeline

XHuman 10's cycle-42 casualty mismatch was not a projectile or damage error:
native footman 1492 and Java unit 108 enter Die on the same cycle at the same
position and hit points. The mismatch came from score and kill accounting
living in a desktop-side corpse observer. Headless and multiplayer simulation
therefore omitted state which retail commits at the lethal hit.

Kill ownership, points, kills and razings now commit synchronously in
`World.kill`; the desktop no longer mutates deterministic player state, and
the trace excludes a unit from the live roster on the cycle it enters Die.
XHuman 10 is exact for all 9,000 player-state comparisons through cycle 100,
and XHuman 2's formerly identical cycle-43 casualty mismatch disappears. The
next combat item is now a real damage/event-order mismatch: Human 13 at cycle
97, rather than another accounting artifact.

### Independent AI-executive queue

The player-state ranker's next item, XHuman 6's extra building at cycle 311,
was also downstream rather than a new AI policy. Retail's refused ogre and the
Java ogre now share the same movement transition; that exposed a worker which
was allowed to found a building one tile before reaching its fixed BNE
footprint point. Inside builders now require that exact stored point rather
than the broad footprint-range predicate.

XHuman 6 is exact for all 35,700 player-state comparisons through cycle 340,
and its coarse first divergence moves from cycle 103 to 162. The next genuinely
independent executive item is Orc 9's cycle-1,236 supply decision.

### Second-wave regression proof

- 60 focused movement, construction, combat and scoring tests pass.
- The full 52-case semantic-v1 survey through cycle 200 remains 4 clean,
  48 divergent and 0 failed, matching the clean baseline with no regression.
- XHuman 6 and XHuman 10 have separate exact player-state proofs; neither
  result is inferred from the aggregate survey.

## Next queue

1. Mine Human 13's true cycle-97 damage/event-order mismatch from the exact
   projectile, attack-marker and RNG ledgers; the casualty accounting layer is
   now closed and must not be reopened.
2. Use the AI ranker's Orc 9 cycle-1,236 supply decision to identify the next
   independent executive branch; XHuman 6's construction timing is closed.
3. Map unit-facing and order-point representations into semantic-v2 only after
   native evidence proves their conversion; do not manufacture equality.
