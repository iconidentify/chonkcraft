# BNE Parity Lab

Parity Lab is the evidence and experiment layer above the durable `triage`
loop. It does not change engine code. It converts an authenticated divergence
packet into a small causal lead, the best next experiment, historical matches,
coverage gaps, constrained rule candidates, and a tournament plan.

The governing machine-readable contract is
[`parity-lab-policy.json`](parity-lab-policy.json). The full historical record
and evidence standard remain in [`PARITY.md`](PARITY.md).

## One-command loop

```sh
python3 tools/bne-harness/scripts/bne_java.py autopilot \
  tools/bne-harness/work/corpus/campaign-1800/corpus-index.json \
  --baseline-survey /path/to/last-proof.json \
  --asset-pack /path/to/bne.chonkpack \
  --source-dir /path/to/chonkcraft \
  --through 30 --jobs 4 --packet-limit 2
```

`autopilot` first runs the existing content-addressed survey, full regression
gate, clustering, Java diagnostic, and packet builder. Unless that stage has a
runtime/evidence failure, it immediately composes `.bne-lab/` from the verified
triage manifest. Exact requests are cache hits.

The `triage` CLI now composes the same lab handoff by default. This makes the
freshness guarantee structural: a successful CLI triage cannot advance
`.bne-artifacts/latest.json` while silently leaving `.bne-lab/latest.json`
behind. `autopilot` remains the descriptive name for the complete loop and
accepts the same optional native evidence.

Read only these files at the start of an agent turn:

1. `.bne-lab/latest.json`
2. the selected run's `manifest.json`
3. the selected run's `NEXT.md`
4. the chosen case's `causal-minimized.json`, `experiment-plan.json`,
   `counterfactual-plan.json`, and `branch-witness-plan.json`

That is the low-context handoff. Open the large packet, raw traces, or
`PARITY.md` only when the chosen experiment requires them.

## Causal twin

Java emits normalized JSONL events only when `CHONKCRAFT_TRACE_BNE_CAUSAL` is set.
The native tracer can emit the same event vocabulary. For synchronized RNG it
guards the retail bytes at `0x004534c0`, replaces the entry with an exact
reproduction, and records cycle, caller, seed before/after, and result. An
unknown executable signature is refused. Both engines emit the same shape for
the asynchronous stream as well.

A Java draw records the class and method that consumed it, the callers above
that, its seed before and after, a running draw number, and the source line as
a hint rather than as its identity. Nothing walks a stack when tracing is off.

Attach an independently captured native trace without modifying the triage
packet:

```sh
python3 tools/bne-harness/scripts/bne_java.py lab \
  .bne-artifacts/runs/TRIAGE_SHA \
  --native-executable /path/to/pinned/Warcraft\ II\ BNE.exe \
  --native-trace retail-orc-07-idle=/path/to/case.trace.txt
```

The sibling oracle manifest must authenticate the exact trace, pinned retail
executable, matching scenario and seed, sufficient cycle range, fixture ID,
tracer identity, and network-disabled runtime. Otherwise the lab refuses it.

## Asynchronous RNG ledger

A hit-point mismatch whose blows land on the same cycles, the same number of
times, for different amounts can only be a differently rolled blow, and the
lab escalates that shape by itself: `randomized-damage` joins the rounding,
event-order and rate hypotheses, and the seed-anchored draw ledger is ranked
against the rest by information gain per cost. Construction, healing, a rate
difference, and a cadence mismatch keep their own hypotheses and do not
escalate.

When authenticated native evidence is already attached, the case receives
`RNG-DIFF.json` and its `RNG-DIFF.md`. When it is not, the same files carry
the exact capture command for the missing side rather than a verdict reached
by comparing one ledger with nothing. It can also be run directly:

```sh
python3 tools/bne-harness/scripts/bne_java.py rng-ledger \
  --java-causal .bne-artifacts/runs/TRIAGE_SHA/.../CASE.causal.jsonl \
  --native-trace /path/to/CASE.trace.txt --case retail-xhuman-12-idle
```

Alignment is on seed transitions rather than on cycles, so a presentation
offset between the engines is measured instead of fought. Both engines draw
from one shared generator, so an extra or missing draw leaves every later seed
where it was and moves who spent it -- the ledger therefore reports the first
draw whose consumer stopped being the one it had always been. See
[`RNG_LEDGER.md`](RNG_LEDGER.md) for the authentication contract, the full
result vocabulary, and the limits of matching a native return address to a
Java method name.

## Temporal native state machine

Some divergences are decided several cycles before anything visible happens: a
byte in the native record climbs one per cycle changing nothing, and at some
value a route is cleared and a timer armed. The lab mines the focused unit's
whole record across the window when its shape asks for that -- something
accumulating before an action, a timer armed after one, a value repeating until
a transition takes, or the unit standing still while its hidden state moves --
and otherwise leaves the case alone.

The case then receives `STATE-MACHINE.json` and its `STATE-MACHINE.md`, and the
ranked plan gains a `hidden-state-machine` hypothesis the others cannot be told
apart from without one. Run it directly, or against other windows, with:

```sh
python3 tools/bne-harness/scripts/bne_java.py state-machine \
  --packet .bne-artifacts/runs/TRIAGE_SHA/.../packet.json --slot 1448 \
  --java-causal .bne-artifacts/runs/TRIAGE_SHA/.../CASE.causal.jsonl \
  --compare work/packets/other-case/packet.json=1553
```

A ramp and what changed at the top of it are reported as two observables and a
delay, never as cause and effect, and a native offset beside a port field is
graded `proved`, `strong-temporal-correlation`, `speculative-candidate` or
`no-java-counterpart` -- only the first is a mapping. The same command audits a
proposed Java state field against the evidence with `--proposed-state`. See
[`STATE_MACHINE.md`](STATE_MACHINE.md) for the shape vocabulary, the
counterexample verdicts, and the audit's limits.

## What a native unit decided, not where it stands

A sealed state stream carries every unit's stored route -- twenty bytes at
offset 48 of the record, one heading each, `0xff` past the end, zero north and
clockwise, with the steps already walked at 126 -- and the whole map, as a
complete delta in every `AUXL` chunk. Neither was read, so investigations that
turned on where a unit had decided to go were reading positions and inferring.

```sh
python3 tools/bne-harness/scripts/bne_java.py routes CASE.state.bin --slot 1490
python3 tools/bne-harness/scripts/bne_java.py routes CASE.state.bin --wood
```

The first reports each route on the cycle it was decided, with where it was
planned from and where it ends. The second keeps only routes planned at a
forest square and adds the eight squares beside the tree with the cost of
reaching each, so which face a worker chose can be compared with the ones it
could have had.

Distances are eight-way with every direction equal. That is measured rather
than assumed: charging a diagonal more than a cardinal, the ordinary shape of
a pathfinder of this age, picks the square retail took in 5 of 21 sealed wood
approaches against 15 of 21 for a uniform cost. Units are solid, which is also
measured -- walking through them fits worse.

## Native decision micro-oracle

Branch Witness says which instruction decided something; it cannot say what the
decision would have been with a different number in that register, because
asking costs a remote capture. The micro-oracle replays one bounded native
function in a project-local emulator from an authenticated snapshot, reproduces
the captured invocation or refuses, and then answers thousands of variations a
second offline.

```sh
python3 tools/bne-harness/scripts/bne_java.py micro-oracle SNAPSHOT.json
python3 tools/bne-harness/scripts/bne_java.py micro-oracle-plan BRANCH-WITNESS.json
python3 tools/bne-harness/scripts/bne_java.py micro-oracle-spec BRANCH-WITNESS.json \
  --out specification.json
python3 tools/bne-harness/scripts/bne_java.py micro-oracle-capture \
  specification.json capture-log.txt --out ./captured
```

Boundaries are found by doubling outward and bisecting, every candidate is
executed rather than solved for, and the rule learned is challenged by the
native code itself before it is reported. Remote capture is dry-run by default.

A snapshot comes from a capture, and a capture comes from a reviewed
specification: which address to stop at, which activation of it, which memory
to save, and what the inputs are called. `micro-oracle-spec` drafts one from a
completed Branch Witness run and leaves the inputs empty, because that evidence
cannot say what a register holds. No native decision has been captured yet --
the agent is proved offline against a synthetic function and has never been run
against the retail oracle. See [`MICRO_ORACLE.md`](MICRO_ORACLE.md).

## Oracle-guided counterfactual replay

For a packet whose first mismatch is a paired unit position, order, or hit
point, the lab emits a bounded `counterfactual-plan.json`. Run it with:

```sh
python3 tools/bne-harness/scripts/bne_java.py counterfactual \
  .bne-artifacts/runs/TRIAGE_SHA \
  --case retail-xorc-11-idle --through 30 --jobs 4
```

The runner tries a small explainable set of timing, transition, replan, and
state-clamp interventions in parallel. It scores every future cycle against
the sealed fixture and labels the result `frontier-advanced`, `causal-lead`,
`surface-only`, `no-effect`, or `harmful`. A one-frame cosmetic match cannot
outrank an intervention that restores several future frames.

Interventions exist only in the test trace executable. Production engine
classes are unchanged, and the ordinary parity path retains a separate loop
with no per-cycle counterfactual check. A winning intervention is a causal
lead, never an accepted fix; state clamps in particular prove downstream
sufficiency without proving the hidden native rule.

## Native Branch Witness

When counterfactual replay proves that a missed native transition matters but
does not reveal its rule, Branch Witness follows the exact native byte write.
If the divergent field was never written, it follows a changing raw-record
precursor and labels it as such. A bounded GDB BTS history is contrasted with
an earlier clean transition; a second pass can capture the selected branch's
actual register operands and infer a small integer predicate.

The capture runs only in a separate, disposable, networkless diagnostic
oracle. It does not add a production-engine hook and does not alter the normal
corpus path. See [`BRANCH_WITNESS.md`](BRANCH_WITNESS.md) for the workflow,
evidence contract, privileges, and proved retail result.

## Semantic predicate slice

Once Branch Witness has concrete operands and a clean contrast, the offline
semantic slicer follows both operands backward through the already authenticated
GDB BTS instruction history. It labels only offsets present in the pinned
152-byte unit layout and leaves unknown function arguments and native lookup
tables explicit.

```sh
python3 tools/bne-harness/scripts/bne_java.py semantic-slice PLAN.json \
  --capture ANCHOR.branch-capture.json --history ANCHOR.gdb-history.txt \
  --control-capture CONTROL.branch-capture.json \
  --control-history CONTROL.gdb-history.txt
```

The content-addressed result lives below `.bne-semantic-slice/`. A proof passes
only when the anchor predicts its concrete branch outcome and a held-out clean
capture independently recovers the same named formula and correct outcome.
Both captures must also prove that the predicate's unit-pointer register equals
the exact watched-unit address. Formula recovery without focus identity remains
an investigative localization result, not a semantic proof.
The generated boundary experiment is a diagnostic lead, never an accepted
source change.

## Cross-engine semantic bridge

A proved schema-2 native slice can be matched against opt-in Java decision
evidence with `bne_java.py semantic-bridge`. The bridge normalizes a small
reviewed expression language, ranks runtime and source candidates, links the
exact Java decision line, and emits the smallest next boundary experiment. It
refuses unproved focus identity and never generates a patch. See
[`SEMANTIC_BRIDGE.md`](SEMANTIC_BRIDGE.md) for its evidence grades and command.

## Contrastive decisions with no rejected write

When native rejects a decision on one visit and accepts it on a later visit,
the rejected visit cannot be captured by a field watchpoint because it writes
nothing. `bne_java.py decision-plan` validates the two outcomes against the
sealed raw fixture, bootstraps the containing function from the accepted
writer, and creates focus-scoped entry-to-return captures for both visits.
`decision-mine` aligns their branch histories, ranks concrete outcome flips,
captures register or memory operands, and requires a held-out prediction before
emitting a schema-2 semantic-bridge handoff. See
[`DECISION_MINER.md`](DECISION_MINER.md).

## Acceleration gates

Fresh agents should run the capability doctor before choosing a diagnostic
route. Position divergences receive paired transition-cadence analysis before
heavier tracing, proposed regression tests must fail at the pre-fix commit, and
equally early frontier blockers are investigated in estimated cost order. See
[`ACCELERATION_GATES.md`](ACCELERATION_GATES.md) for the commands and safety
contracts.

## What each case receives

| Artifact | Purpose |
|---|---|
| `CAUSE.md` | Human-sized first-cause report |
| `causal-alignment.json` | Deterministic weighted native/Java event alignment |
| `causal-minimized.json` | Delta-debugged minimum slice preserving the same cause |
| `experiment-plan.json` | Competing hypotheses ranked by information gain/cost |
| `counterfactual-plan.json` | Bounded alternate futures for oracle-guided replay |
| `branch-witness-plan.json` | Exact native byte, capture window, and safe writer/branch contract |
| `minimization-plan.json` | Safe scenario-reduction order; canonical fixture stays immutable |
| `coverage-plan.json` | Event/address novelty, legal command variants, uncovered CFG transfers |
| `function-lab.json` | Pinned static function record, concrete leaf replay, boundary variants |
| `rule-space.json` | Small explainable candidate grammar, never arbitrary generated code |
| `tournament-plan.json` | Independent candidate lanes and full-gate selection policy |
| `RNG-DIFF.json` | Seed-anchored native/Java draw ledger, when a damage roll is the leading hypothesis |
| `STATE-MACHINE.json` | Native record trajectories, thresholds and graded port counterparts, when the window shows a multi-cycle state |

The SQLite failure atlas stores authenticated runs, signatures, causal leads,
experiment status, and cumulative coverage tokens. Similarity is a planning
hint, not proof that two cases share a root cause.

## Candidate tournaments

`bne_tournament.py` applies each proposed patch to a disposable detached Git
worktree. Lanes may execute in parallel. A winner must pass its focused replay,
the exact 52-case baseline gate, and report no regression. The tournament only
selects; it never commits, merges, pushes, or edits the working branch.

## Evidence boundaries

- The retail fixture and original triage run are immutable.
- A reduced scenario or command variant receives a new fixture identity.
- Static analysis is accepted only for the pinned BNE 2.02b executable SHA.
- A causal alignment is an investigative lead. The full regression gate is
  still the acceptance authority.
- All lab outputs are content-addressed by inputs and the identities of the lab
  implementation files, so an analysis-code change cannot return a stale hit.

## Proved reference run (2026-08-02)

An isolated native Orc 7 capture installed the guarded sync-RNG hook and
recorded draws at cycles 6, 24, and 27. Through the cycle-24 divergence, native
had two draws; Java had one at cycle 8 from `chopInPlace`. The causal twin
identified the first timing/value mismatch and retained the native-only
cycle-24 draw. The unchanged engine still passed the 52-case baseline gate:
50 clean, the same 2 divergent, 0 failed.

The function lab replayed the byte-verified native leaf from seed `1` to seed
`1103527590`, result `16838`. Harness tests also prove delta debugging,
information-gain ranking, bounded counterexample-guided synthesis, cumulative
coverage, atlas idempotency, and gated patch evaluation in a disposable Git
worktree.

An isolated counterfactual proof then replayed two live cycle-25 blockers
through cycle 30. For XOrc 11, changing only the destroyer's reported queued
action after cycle 25 restored all six future frames, while forcing Patrol
before the tick had no effect. That separates transition/report timing from
movement or patrol-goal logic. For XOrc 2, clamping the peon to the oracle tile
restored all six frames while delay and replan candidates had no effect. That
proves the single missed spatial transition is sufficient for the observed
future, while correctly leaving its underlying rule unresolved.

An isolated XHuman 12 Branch Witness proof then handled the harder
"missing native write" shape. Native slot 1553 stayed at `(6,26)` while Java
moved, so it followed the timer transition `3 -> 2` instead of inventing an
X/Y writer. It reduced 65,466 instructions to 20 branch candidates, located
the precursor writer at `0x00402451`, contrasted branch `0x00437646`, and
measured `ecx > eax`: failing `2 > 1` took the branch, while clean `1 > 1` did
not. These are authenticated localization samples, not named source semantics
or an accepted fix.

Reanalysis of those same immutable histories exposed an important evidence
boundary: the selected branch loaded its unit pointer through global address
`0x004ab894`, while the old slicer had assumed callee argument one was native
slot 1553. The calculation and held-out outcome remain useful localization,
but focus relevance was not proved. Semantic-slice schema 2 rejects that legacy
packet and requires a focus-scoped predicate capture before a `unit[1553]`
formula can cross into the Java bridge.
