# Next-level BNE gameplay parity

This is the operating contract for the three systemic parity lanes established
on 2026-08-15. It replaces “fix the earliest campaign frame” as the work
selector. Earliest divergence remains a microscope after a family is selected;
it is not the roadmap.

## Honest starting point

The current authenticated **resolved-command matrix** contains 240 generated
cells. Both production adapters have executed and made 206 cells comparable:
203 are exact, three are materially divergent, 34 remain unexecuted, and none
failed because of infrastructure. This is **203/206 comparable**, not 203/240
complete.

That 240-cell matrix begins after a command has already been resolved. It is
deliberately separate from the **532-cell physical gesture transaction**
denominator below, which starts at an actual mouse, minimap, or command-panel
route and follows the transaction through acknowledgement, physical progress,
and settlement. Keyboard dispatch remains explicit hook debt rather than
invented executable coverage. A resolved MOVE row cannot prove that the
player's click, selection fan-out, queue modifier, refusal, or feedback was
faithfully interpreted.

The first real AI-cycle proof is better than the old coarse AI counter: Orc 1
player 1 has exact committed `ai.bin` state and exact recovered wait/predicate
telemetry through 12, 200, and 1,800 cycles. Human 1 player 0 is exact
through 12 cycles: native warmup-1 does not decrement an install WAIT already
longer than a one-step gate. Human 4 player 0 is exact through 200 cycles
after the `+0x0c` builder-scan latch is armed. Combat lifecycle cells and
the remaining computers remain open, so AI parity is not certified.

The campaign catalog is exactly 52 missions and 137 trigger programs: 55
victories, 79 defeats, one delayed victory, one flag, and one diplomacy action.
An old save stored only the armed trigger list. Save schema 4 now also stores
flags and in-flight delay counters and restores them fail-closed. The 137-cell
pinned-native lifecycle proof remains open.

## Lane 1: player transactions

A transaction starts with what a person physically did and ends when every
affected unit reached a terminal result:

```text
mouse/key/minimap gesture
  -> ordered selection
  -> interpreted target shape
  -> ordered wire-command fan-out
  -> acceptance/refusal and acknowledgement
  -> first physical progress
  -> terminal state
```

`PlayerIntentJournal` now assigns one transaction ID across every command in a
group fan-out. `GameScreen` records field, minimap and command-panel origins,
modifiers, screen/tile coordinates, target shape, selection order and wire
bytes. A command hotkey begins a `keyboard` gesture and keeps that same
transaction open through an aimed field click. Build, train, research and
upgrade-to refusals that never reach a wire command now journal the family,
`queued=false`, reason and Notify acknowledgement. Coverage retires those two
required-route debts only when receipts actually contain those observations;
the 532-cell matrix is unchanged. Native layered hooks for the same two
routes, plus target, wire, progress and terminal layers on field/plain/move,
remain open, so certification stays red.
`bne_player_transaction.py` compiles observed facts without silently
turning a per-unit order into proof of a physical transaction. Schema 2 names
all eight layers independently. The older native `DoRightButton` hook proves
the field gesture, ordered selection and installed acceptance/fan-out only. It
does **not** invent a target shape, wire bytes, voice/status acknowledgement,
pixel progress or settlement from those fields.

The required-cell manifest is
`player-transaction-requirements.json`. It currently declares an explicit,
stable 532-cell physical denominator rather than multiplying independently
observed marginal counts. Each cell is expanded from a code-validated UI route:
field/minimap right-click, field aimed action, building placement, critter
dismiss, or command-panel direct action. This includes wall attack-ground,
Shift queue variants and build placement; impossible combinations such as
minimap research, keyboard-without-a-hook, queued-without-Shift, or a Shift
route marked immediate fail manifest validation. Every cell fixes origin,
modifiers, ordered selection size, interpreted target shape, command family,
queued/immediate mode and accepted/rejected result. Completion requires all
eight observed layers for the same transaction
on both sides. Duplicated receipts or duplicated cells do not grow the
numerator. A Java-only receipt is coverage, not native parity; `certify`
requires authenticated native and Java receipts joined by the same sealed
scenario and compares the transaction from physical gesture through terminal
result.

Build, train, research and building-upgrade refusals remain first-class cells.
The latter three are pre-wire UI decisions, so the manifest also carries a
blocking hook debt until Java and native emit the refused family, queue mode,
reason and acknowledgement. A missing wire command is not rounded into an
accepted or rejected production proof.

Import sealed native physical-UI captures without copying their source bytes
into Git:

```sh
python3 tools/bne-harness/scripts/bne_player_transaction.py import-native \
  /path/to/physical-ui/capture-a /path/to/physical-ui/capture-b \
  --output-dir tools/bne-harness/work/player-transactions/native \
  --catalog tools/bne-harness/work/player-transactions/native-catalog.json
python3 tools/bne-harness/scripts/bne_player_transaction.py coverage \
  tools/bne-harness/work/player-transactions/native/*/receipt.json \
  --requirements tools/bne-harness/player-transaction-requirements.json \
  --output tools/bne-harness/work/player-transactions/native-coverage.json
```

Import verifies every trace against its `.bnefx` archive, sealed manifest,
complete fixture/scenario key, pinned 2.02b executable, tracer/injector/source
identities and exact trace bytes, then stores a content-addressed receipt. The
seven currently sealed `i9beef` captures import as seven transactions and 12
ordered unit commands: seven prove gesture, ordered selection and acceptance;
five are true groups; one proves an empty-selection refusal. They correctly
leave target interpretation, serialized wire bytes, acknowledgement, physical
progress and non-refusal terminal settlement open. Coverage emits one recipe
for every missing origin/modifier/family cell. An executable cell carries the
real `bne_oracle.py run` and import argv; an unobservable cell is
`blocked-on-hook` with the exact native hook debt. A GiveOrder fixture can
never satisfy this physical-UI lane.

Pair receipts only after the Java side emits the same lossless event contract:

```sh
python3 tools/bne-harness/scripts/bne_player_transaction.py certify \
  --native tools/bne-harness/work/player-transactions/native/*/receipt.json \
  --java tools/bne-harness/work/player-transactions/java/*/receipt.json \
  --requirements tools/bne-harness/player-transaction-requirements.json \
  --require-complete \
  --output tools/bne-harness/work/player-transactions/certification.json
```

The Java authority is fail-closed on both the hermetic engine identity and the
broader desktop/program identity. The latter binds `GameScreen`, desktop build
inputs, replay/player adapters, JBR wrapper and pack-facing code; a receipt
from unchanged engine code but stale input interpretation cannot certify.
The command currently reports semantic `content_exact` diagnostics only. A
detached receipt can repeat authority fields; it is not the raw producer
proof. `complete` therefore remains false—even at 532/532—until a retained
proof-store validator reopens the native capture closure and Java
execution/build closure. `--require-complete` is the intentional fail-closed
bar, not a claim that detached receipt files can satisfy it.

Current commanded evidence and systemic worklist are regenerated by the main
gate. Do not copy the checked-in historical split report into a new receipt.

## Lane 2: AI, combat and effects

Semantic-v2 player banks are not AI decisions. The decision ledger compares
one row per active computer player per cycle: normalized PC/list/threshold
offsets, wait, all non-pointer state, predicate attempts, writes and force
launches. See `AI_DECISION_LEDGER.md`.

Run a native/Java window:

```sh
scripts/capture-bne-ai-cycle.sh
```

Do not hand-enumerate the fleet. The
[AI evidence conductor](AI_EVIDENCE_CONDUCTOR.md) discovers authenticated
`i9beef` runs, retains only content-addressed manifests/normalized ledgers,
pairs them with hermetically identified current-Java twins, enforces the fixed
player/cycle denominator, and emits the ranked causal `NEXT` queue:

```sh
python3 tools/bne-harness/scripts/bne_java.py ai-conductor
python3 tools/bne-harness/scripts/bne_java.py ai-conductor \
  --materialize --limit 1 --jobs 1
python3 tools/bne-harness/scripts/bne_java.py ai-conductor --validate-store
```

Discovery is read-only. Materialization is explicit, locally leased, and
capped at two workers; it never manages the oracle's Docker containers.
Native capture remains a separate, explicit operation.

Materialization revalidates the remote manifest, trace, and state stream,
derives the computer roster from `state.bin` controller records, builds and
receipts current-head Java, auto-selects the map's actual person slot, and
retains only the manifest and normalized ledgers. The Java proof namespace
binds source/build inputs, JBR wrapper, pack, `ai.bin`, app JAR, person slot,
computer roster, seed, and window. `--skip-build` fails if any receipt input or
JAR byte is stale. Final certification requires 52/52 materialized missions
with both committed state and causal telemetry exact through cycle 1,800.
The retained-store validator must pass before `NEXT.json` is admitted to the
next-level gate: it rejects `RUN`-only evidence and reconstructs the object,
twin, comparison, report, catalog and canonical fleet counts from bytes.

Once a decision diverges, continue through the same causal transaction:
acquire -> chase -> swing -> projectile/effect create -> damage/RNG ->
retaliation -> death/free. Use the existing projectile ledger and visual
lifecycle gate. Do not fix a downstream projectile symptom while an earlier AI
PC, target, order, movement, or RNG field differs.

`combat-lifecycle-requirements.json` makes that obligation executable. Its
185 required cells span melee, ranged, siege, tower, naval, air, direct and
persistent spells, building destruction, the relevant stances, and every
player-visible lifecycle phase. `bne_combat_lifecycle.py` refuses to certify a
cell without native observation, Java observation, exact result and exact
causal order. Existing Java projectile tests are useful coverage but cannot
fill a pinned-native proof row by themselves.

`bne_divergence_compiler.py` routes each normalized mismatch into candidate
cells (or an exact cell when `coverage_target.combat` is supplied) and carries
the current certified numerator beside the 185-cell denominator. It never
promotes heuristic routing to proof. See `DIVERGENCE_COMPILER.md`.

## Lane 3: campaign lifecycle

`bne_campaign_lifecycle.py inventory` compiles a stable proof cell for every
sealed trigger. A proof row is complete only when pinned native and current
Java observed the action and deciding cycle and compare exact. Flag, delay and
diplomacy rows also need an exact continuous-vs-save/resume fork.

The Java save/load bug found while building this lane is closed:

- schema 4 writes armed trigger IDs, native flags and active delay counters;
- reload rejects unknown, duplicate or already-spent delay IDs;
- Orc 2 flag and Human 2 countdown state have focused resume coverage; and
- ordinary older saves remain readable through the schema-3 armed-list path.

Human 8's opening TRUE DIPLOMACY trigger now survives save and resume: schema 4
writes every directed standing, so the siege does not forget the town it was
already attacking. That is one mutable action's Java save/resume fork, not the
137-cell pinned-native proof.

Inventory generation is not proof. Campaign GREEN requires all 137 action
paths and authenticated victory/defeat contracts for all 52 missions through
the released app and ChonkPack.

The compiler also accepts an exact mission/trigger/action target and reports
the current 137-cell campaign debt in every native decision work order. This
keeps a locally convincing fix from silently bypassing the product-level
campaign obligation.

## One scorecard

Run:

```sh
scripts/check-bne-next-level-gate.sh
```

It regenerates the current 240-cell resolved-command matrix and its
dual-adapter execution ledger, ranked worklist, campaign inventory, Python
mutation/identity tests and focused Java gates, then writes
`tools/bne-harness/work/next-level/status.json`. It does not reuse the stale
checked-in split report.

Add retained evidence when available:

```sh
BNE_PLAYER_TRANSACTION_RECEIPTS=/path/native-1.json:/path/java-1.json \
BNE_REPLAY_CORPUS=/path/replay-corpus.json \
BNE_REPLAY_REPORTS=/path/replay-1.json:/path/replay-2.json \
BNE_AI_CONDUCTOR_REPORT=/path/retained-ai/report.json \
BNE_COMBAT_PROOF=/path/to/combat-proof.json \
BNE_CAMPAIGN_PROOF=/path/to/campaign-proof.json \
  scripts/check-bne-next-level-gate.sh
```

Colon-separated player receipts and replay comparison reports are joined to
their fixed semantic manifests, but remain diagnostic until retained
proof-store validators reopen their raw producer evidence. A detached
`BNE_PLAYER_CERTIFICATION` or `BNE_REPLAY_CERTIFICATION` summary is also
diagnostic only and cannot make a lane green. Likewise, raw
`BNE_NATIVE_AI_LEDGER`/`BNE_JAVA_AI_LEDGER` files may aid diagnosis, but AI
GREEN requires a retained `BNE_AI_CONDUCTOR_REPORT` whose complete object,
twin, comparison, catalog, build, and 52-mission proof closure validates from
bytes. Missing producer receipts/reports remain missing evidence; a copied
summary is never accepted as proof.

`--require-certified` fails until all three lanes are genuinely complete. The
status identity hashes HEAD, relevant tracked diff, relevant untracked program
files and the hermetic engine closure. A receipt from a different input is not
current.

## Iteration rules

1. Start from the generated status and worklist; never from a remembered cycle.
2. Pick the highest-volume upstream missing/divergent family.
3. Capture or minimize native/Java causal twins before editing the engine.
4. State one binary-backed rule and its discriminating witnesses.
5. Add efficacy tests that fail without the rule.
6. Make the smallest systemic implementation; no fixture, mission, unit ID or
   fitted-cycle branches.
7. Rerun focused proof, fixed-denominator family proof and global regression.
8. Keep only measured gains. Record failed hypotheses so they are not retried.
9. After two no-gain hypotheses rerank; after three failed implementations
   switch lanes. Never spend the whole run grinding one uncertain case.
10. Continue player -> AI/combat -> campaign rounds until the scorecard is
    certified, not until a context window is tired.
