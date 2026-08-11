# Top-three BNE convergence checkpoint

Date: 2026-08-11

Released-base control: `cb51738d`

Implementation branch: `codex/bne-top-three-convergence`

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
now preserve three native quiet visits and wait out the interrupted animation's
remaining timer. Results:

- through cycle 20: **0/19 clean before, 16/19 clean after**;
- ordinary air cases now first differ at cycle 48 instead of cycle 7;
- ordinary ground cases now first differ at cycles 64-73 instead of cycle 7;
- ordinary sea cases now first differ at cycles 73-74 instead of cycle 5; and
- the remaining early cases are isolated to air-occupied, ground-NW, and
  ground-occupied behavior at cycle 5.

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
- The 52-case cycle-80 candidate has **zero regressions** against a freshly
  generated control from released master `cb51738d`.
- The older accepted pointer still claims a common floor of 52, but released
  master itself reproduces XHuman 10 at cycle 41 with both the old and current
  packs. That stale pointer is historical debt, not a regression in this work.
- The 17-lane playability gate's stale-report, test-count, signed-catalog, and
  environment-path issues were corrected or supplied during this checkpoint;
  every individual lane passes with its authenticated inputs.

## Next queue

1. Mine the three cycle-5 occupied/NW command cases as one refusal/heading
   cluster before touching later movement cadence.
2. Use the AI ranker's XHuman 6 cycle-311 building mismatch to identify the
   next independent executive branch.
3. Map unit-facing and order-point representations into semantic-v2 only after
   native evidence proves their conversion; do not manufacture equality.
