# Top-three BNE convergence checkpoint

Date: 2026-08-11

Third-wave base control: `5e393cd8`

Implementation branch: `codex/bne-top-three-convergence-2`

This checkpoint completes the three highest-leverage parity efforts selected
by the audit: deeper authenticated state comparison, a controlled native
decision corpus, and an AI-executive differential queue. It also applies and
proves the first systemic engine correction exposed by each behavioral lane.

## 1. Semantic-v2 whole-state comparison

The oracle's sealed schema-1.1 state is now compared against additive Java
trace rows for player economy/research, sub-tile units and animation state,
projectile lifecycles, and changed terrain. Family filtering keeps large runs
practical. Unit facing and active order-point coordinates now use the
authenticated conditional mapping described below.

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

## Third-wave closure: causal combat, Orc 9 supply, and decision state

### Causal projectile and damage pipeline

Projectile creation, constructor RNG, movement, impact and destruction now
carry stable causal ordinals in both the Java trace and projectile ledger. The
implementation preserves retail's fixed-pool same-cycle iteration: an impact
may occupy a freed slot and execute later in that same missile pass. Retail's
impact animation is modeled as a six-frame, three-tick cadence rather than an
arbitrary desktop effect lifetime.

Attack OP10 now owns deterministic damage and projectile creation even when a
presentation callback is absent. Repeated ranged attacks suppress the later
presentation duplicate, while stand-ground shots are born during the unit
visit and defer only their constructor/RNG debit until the cycle-end boundary.
That distinction prevents an early constructor from stealing asynchronous RNG
from units later in the same retail pool walk.

Human 13 player-state parity improves from **8,952/9,000** to
**8,994/9,000** comparisons through cycle 100. Its false cycle-81 projectile
constructor is eliminated and every damage event through cycle 96 agrees. The
remaining six comparisons are the live-unit and casualty counters at cycles
97–99: retail's ogre began its initial continuous chase two cycles earlier and
kills at 97, while Java kills at 100. That startup acquisition offset remains
explicitly open; it was not hidden with a fixture-specific timer adjustment.

### Orc 9 AI supply decision

The cycle-1,236 Orc 9 difference was not a new build policy. Retail reaches the
farm's full construction counter on cycle 1,236 but keeps it in Built state
until the final twelve-cycle construction pulse at 1,248; only then does it
grant supply. Java had treated a full counter as roof-on completion and granted
supply immediately.

Construction now separates a full progress counter from the final completion
pulse. Orc 9 is exact for **58,500/58,500 player-state comparisons** through
cycle 1,300, including bank, supply, totals, kills and research.

### Authenticated decision-state comparison

Semantic-v2 now compares the decision fields that formerly appeared only as
diagnostics. Java's 256-direction mobile facing is normalized to retail's
nearest eight-way direction. Order-point comparison is conditional on the
active order and maps Attack/AttackMove, Harvest/ReturnGoods and Build to the
native record member that owns the point. Invalid or inactive union members
remain excluded rather than being manufactured into equality.

The Human 13 unit run performs **218,554 real unit-field comparisons**. Its
remaining disagreements are visible facing, frame and timer differences—not
missing coverage—so future movement work can rank concrete decision-state
evidence.

### Third-wave regression proof

- The full 52-case semantic-v1 survey through cycle 200 remains **4 clean,
  48 divergent and 0 failed**, with zero regressions against `5e393cd8`.
- Human 5 improves from first divergence cycle 57 to 61.
- XHuman 4 remains at its pre-change cycle-54 boundary, proving that the
  stand-ground constructor boundary did not reorder its critter RNG stream.
- Focused Java and Python tests cover construction completion, OP10 ownership,
  cycle-end constructor debit, impact cadence, causal projectile ledgers, and
  conditional semantic mappings.

## Fourth-wave foundation: authenticated action scheduling

Semantic-v2 now authenticates the native unit action cursor at record offset
4 against Java's `seqoff`. This compares the exact instruction stream each
unit is executing, rather than inferring its scheduler state from order names,
sprites, or eventual positions. The comparison exposed one dominant shared
family: units whose Attack OP0 approach was refused by cooperative traffic
kept the Attack cursor in Java, while retail immediately yielded to the
type-specific Move program with timer 15 and kept Attack only as the owning
order.

The engine now transfers that action ownership at the cooperative-refusal
boundary. It does not transfer generic hard refusals, and it does not restart
a unit already executing Move. The latter distinction is required by XHuman
12: restarting its Move program swapped the cycle-39 traffic order of grunt
1503 and axethrower 1523. With cursor ownership as the predicate, XHuman 4's
grunt 1505 and axethrower 1490 match their native Move starts and full 15-to-1
timers, while XHuman 12 preserves its accepted traffic ordering.

Measured across all 52 fixtures through cycle 20, modeled mobile action-cursor
mismatches fall from **101 to 2**, a **98.0% reduction**. The two remaining
mismatches are critter cadence, not combat refusal. XHuman 4 is exact through
cycle 45; XHuman 12 advances from the rejected cycle-39 candidate to cycle 42,
beyond its accepted cycle-41 proof.

The full 52-case semantic-v1 survey through cycle 200 remains **4 clean,
48 divergent and 0 failed**. The regression gate passes at common horizon 40
against the authenticated pre-scheduler baseline; no regressed candidate was
accepted.

## Next measured queue

1. Extend authenticated cursor ownership beyond mobile units, beginning with
   the two remaining critter cadence mismatches.
2. Close the remaining Human 13 initial acquisition/chase-entry offset without
   changing already-exact damage, projectile, or RNG ownership.
3. Rank the next independent AI-executive difference from the new exact Orc 9
   baseline.
