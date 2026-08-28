# Next-level BNE gameplay parity

This is the operating contract for the three systemic parity lanes established
on 2026-08-15. It replaces “fix the earliest campaign frame” as the work
selector. Earliest divergence remains a microscope after a family is selected;
it is not the roadmap.

## Honest starting point

The current authenticated **resolved-command matrix** contains 240 explicit
generated cells and **0/240 are identity-joined to retained dual-adapter
executions**. The older fleet still contains 206 exact dual-adapter executions,
but all 206 are historical/unmatched diagnostics rather than members of the
current fixed set. The old 206/240 headline compared counts, so a differently
generated map, command, or observation window could fill its numerator. That
is now rejected. A cell identity binds requested map, initialization seed,
full command content (including production type), and terminal observation
cycle. The separate physical-gesture layer below also remains RED.

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
the 532-cell matrix is unchanged. Native `DoRightButton` now also hooks
public GiveOrder at `0x00451070`. After retail target lookup it emits
`player-gesture` with the observed target shape, reconstructs the eight-byte
`0x13` wire from the GiveOrder arguments, records the one-voice group
acknowledgement, and follows signed pixel `IX`/`IY` through first walk
progress and Still-after-progress settlement. Keyboard and command-panel
native hooks remain open. Those new layers do not count until a sealed
field/plain/move recapture contains them.
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

The two producers deliberately retain their actual transport bytes: retail's
eight-byte replay/GiveOrder command and ChonkCraft's 17-byte deterministic
`GameCommand` envelope. Certification does not compare those unrelated
envelopes as opaque strings. It decodes both, rejects any disagreement between
the bytes and the journaled family/player/unit/destination/target/type/queue
fields, resolves target lifetimes, and compares the shared authenticated
GiveOrder contract. The current decoder covers every replay-grounded `0x13`
family (move, stop, patrol, attack/attack-move, attack-ground, harvest,
return-goods and repair); unsupported packet families remain producer-specific
and therefore cannot become exact accidentally.

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
identities and exact trace bytes, then stores a content-addressed receipt. A
sealed Human 1 field/plain/single-footman/open-ground/move recapture now
proves all eight layers: retail target shape `open-ground`, reconstructed
eight-byte `0x13` wire `1319001c00ffff03`, the one-voice acknowledgement,
first pixel progress at cycle 10, and Still-after-progress settlement at
cycle 393 on `(25,28)` with 46 hit points. A two-wide click at the same
square dest-spreads to `(25,27)` and `(25,29)` with wires `1319001b00ffff03`
and `1319001d00ffff03`; the first selected footman keeps the voice and the
second is `group-suppressed`. Those two cells fill the 532-cell matrix.
The older `i9beef` captures cannot fill the terminal denominator. Two retained
40-cycle closures add Human three-wide and Orc two-wide cross-map observations
through first progress, but the proof compiler now rejects their early
`window-complete` flushes as command terminals. Coverage emits one recipe for
every missing origin/modifier/family cell. An executable cell carries the real
`bne_oracle.py run` and import argv; an unobservable cell is
`blocked-on-hook` with the exact native hook debt. A GiveOrder fixture can
never satisfy this physical-UI lane.

For producer-authenticated pairing, retain the raw native closure outside Git,
build the packaged game with the pinned JBR, and materialize current-source
Java twins from the sealed command scripts:

```sh
python3 tools/bne-harness/scripts/bne_player_transaction.py materialize-store \
  /path/to/human01-single-2512 /path/to/human01-single-2528 \
  /path/to/human01-two-wide-2528 /path/to/human01-three-wide-observation \
  /path/to/orc01-two-wide-observation \
  --asset-pack "$CHONKCRAFT_ASSET_PACK" \
  --store tools/bne-harness/work/player-transactions/proof-store
python3 tools/bne-harness/scripts/bne_player_transaction.py validate-store \
  --store tools/bne-harness/work/player-transactions/proof-store \
  --asset-pack "$CHONKCRAFT_ASSET_PACK" \
  --requirements tools/bne-harness/player-transaction-requirements.json \
  --output tools/bne-harness/work/player-transactions/producer-certification.json
BNE_PLAYER_PROOF_STORE=tools/bne-harness/work/player-transactions/proof-store \
  CHONKCRAFT_ASSET_PACK="$CHONKCRAFT_ASSET_PACK" \
  scripts/check-bne-next-level-gate.sh
```

The store keeps each manifest, trace and `.bnefx` under its native receipt
identity; these proprietary bytes remain ignored. Validation rejects unsafe or
unexpected bundle members, reopens every native receipt from those bytes,
checks the complete Maven source/JBR/pack/JAR closure, derives the Java
scenario only from the sealed select/right-click script and native unit
identities, reruns `BnePhysicalAdapter`, and compares both the evidence and
cycle log byte-for-byte. Unsupported native command routes fail rather than
being translated heuristically. The two single-footman positives, terminal
two-wide held-out, and the two early-window cross-map observations now pass
this producer-proof validator together. A semantic ending is terminal
immediately; `window-complete` and `acknowledged-no-progress` become terminal
only after the shared 600-cycle outcome window, never merely because a shorter
capture ended. The retained result stays honest: the size-1 cell is exact, the
size-2 terminal cell is mismatched, two observations remain incomplete, and
the 532-cell certification remains incomplete.

`BnePhysicalAdapter` now drives `GameScreen.fieldRightClickForTest` -- the
same gesture plus `commandSelected` path the mouse handler uses -- and writes
a compile-ready player receipt plus a cycle-level unit log. A Human 1 field
click of the starting footman fills the same size-1 cell on the Java side.
First progress 10 vs 9 was observation: both engines pop the tile field to
`(22,6)` at cycle 9 while pixels stay `672,160` until cycle 10. The journal
now records first progress from pixel, so both receipts say 10. After the
first dest-arm, a spent 20-byte route that has not reached dest dest-arms
the next leftover on the same visit. Native `0x44fab0` fails when
`unit+0x7e >= 20`; `0x437c80` then calls `0x44fbd0` immediately. Java used
to pay PF_WAIT 10 there, which is why the Human 1 walk sat 27 cycles on
`23,13` and the second footman sat 27 on `19,12`. Both physical twins now
keep the 16-cycle leftover through those tiles (`137->153` and `76->92`).
The shared held-out walk to `(25,12)` settles at cycle 137 on both sides.
Native first takes
damage at 281 (`26,22`, 53 hp). Later HP and Still-after-progress
settlement remain open; they are not this leftover timer.

Two independent native walks plus a held-out closed the cycle-153 special
case before the refill was implemented. Human 1 footman 1597 from `(17,7)`
onto `(25,28)` (sealed `human01-1597-2528-20260816T234813Z`, dest wire
`1319001c00ffff03`) and the starting footman onto `(25,12)` (sealed
`human01-1598-2512-20260816T234813Z`, dest wire `1319000c00ffff03`) are
the positive and negative witnesses. A two-wide open-ground click dest-spreads on both sides:
`DoRightButton` `0x43e330`/`0x43e530` write each soldier's dest as its tile
plus `click - mean` on any axis whose selection span is at most three, and
Java's field right-click now does the same, so Human 1 footmen at `(21,5)`
and `(17,7)` onto `(25,28)` both name `(25,27)` and `(25,29)`. A three-wide
click of those two plus `(10,13)` keeps `(25,28)` because both spans exceed
three. GiveOrder 3 from Still writes dest and `next_order` MOVE through
`0x453130` and keeps the current Still program: Human 1 1598 is Still with
next=MOVE at cycle 5 and MOVE at 6, dest-arm at 9; 1597 stays Still through
the shared 4985 body until 9 and dest-arms at 12. Java used to install MOVE
on the issue visit. Computer Still scan is the unit's own Still marker
(`0x40b010` / `0x40a830`). Human 1 grunt 1591 stays Still when the walk
enters react at dest-arm 217 and acquires at 220, dest-arming the chase
at 223; the first blow is dest-arm 281 on 26,22. Java's neighbour dest-arm
helper used to acquire at 217. The later person-only version has now been
removed as well: authenticated expansion orders show no arrival rescan.
XHuman 4 ballista 1488 stays Still at fixture 6 and Attacks on its own marker
at 11; XHuman 10 archer 1470 stays Still at 25 and Attacks on its own marker
at 26. A dest-path Move queued through the
Still 4985 body promotes from the expired wait without the following Still
OP0 (`0x452ef0` installs Move 2477; no `0040AD58`). That extra draw used
to steal `FUN_00418370`'s first remainder, so the 281 blow was 5 (60 to
55) instead of native 7 (60 to 53) and settle 393 was 47 instead of 46.
A finished dest walk does not keep `HitUnit`'s offer: native 1598's
first post-settle Still OP0 at 396 stays Still (1591 at dist 2) and
Attacks in place on 25,28 when that grunt dest-arms adjacent. The walk's
offer used to make that OP0 chase onto 25,27. After leftover spent and
the Attack 2562 wait expires, `0x437c80` dest-arms the first leftover
the same visit `0x44fbd0` answers: native 1591 dest-arms 26,22 at 321
and 25,27 at 401. Java rebuilt path=2 at 321 and dest-armed at 322
because the swing-end visit skipped `DoActionMove`. Current-source Java twins
now make the size-1 field/plain/open-ground/move cell exact across the sealed
`(25,12)` and `(25,28)` scenarios, so semantic pairing is **1/532**. The
independent two-wide held-out keeps the next cell red: its second footman
settles at 479 rather than native 477. The first coarse route difference is
cycle 173 (Java column 25 versus native column 26) and later reconverges; it is
retained as movement/pathfinding debt rather than hidden by wire normalization
or a one-map timing special case. The leftover-27 walk is closed.

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
The detached command reports semantic `content_exact` diagnostics only. A
detached receipt can repeat authority fields; it is not the raw producer
proof, so detached `certify` remains false even at 532/532. Only the retained
store validator may set `producer_receipts_verified=true`, after reopening the
native capture closure and Java execution/build closure. Even then `complete`
requires all 532 cells to be exact. `--require-complete` is the intentional
fail-closed bar, not a claim that partial or detached evidence can satisfy it.

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

The first complete native/Java transaction is now certified: Orc 1 grunt 1592
attacking footman 1595 is exact at acquire c9, chase c13, retaliation c160,
swing c204, damage c214, death c439 and free c1345, including the exact melee
damage RNG consumer.

The first ranged attack transaction is now certified on the same bar. In
authenticated Human 13, axethrower 1494 attacking knight 1493 is exact at
acquire c5, projectile-create c18, projectile-flight c19, impact/damage c25,
retaliation c30 and swing c33, including the first constructor-damage
consumer (`0x0041834b` / `battleNetProjectileDamage`). After that first
swing, dest-arm leftover residual used to walk Attack start into opcode
10: ambient axe 1505 constructed at c38 and added a third live shot.
Native keeps construction timer 3 then the start wait 63 on 887, so that
axe never throws through fixture 42. The next chip was melee: ogre 1491
dest-arms leftover onto 118,27, free-scans onto knight 1493, and native
parks Attack@643 with construction 3 then bodyWaitSum-1 (23) so the
first blow is fixture 76. Java used to walk that OP0 and land eight
damage at 53. The next live-shot gap was dest-arm axe 1483: native names
the wise-man on the Attack@887/3 open and throws at fixture 99. Java
waited for the later OP0 fire visit, re-armed construction, and threw
at 102. After that construct, dest-arm leftover residual that already
stands in melee range used to pay the out-of-range replan Attack-four
delay and re-arm construction: Human 13 grunt 1485 residual-lands
beside wise-man 1496 at fixture 41 and native opens Attack@2539/3,
keeps the leftover heading through 3,2,1, and first chips at 54.
Java used to land that blow at 57. Dest-arm leftover from a standing
offered acquire is dest-arm plus one more heading: knight 1490 dest-arms
SE,S onto 125,31. A full pathfind leftover residual-opened past OP0 and
chipped ogre 1482 at 51 instead of Attack start 1922/3 then 54. Death
is still 214 vs 223 and free is 1520 vs 1530, so those two ranged cells
stay open. Chase is not observed -- the thrower is already in range.

The same Attack-loop boundary now covers a dying melee quarry. Human 13
ogre 1511 finishes its swing loop after knight 1500 begins dying, scans the
next live knight while wrapping onto Attack construction 3, holds the full
3,2,1 countdown, and then hands the retained SW,S route directly to movement.
The first heading commits on that route-selection visit, putting the ogre on
119,27 at fixture 118 with one heading left, exactly as retail does. This is
an action-state rule: it is not keyed to a mission, unit identifier, or tile,
and the pending loop-to-movement handoff is durable across save/load.

Human 13 catapult 1479's opening still-stance rock is also certified at
acquire c5, projectile-create c5, projectile-flight c6 and impact c35.
Knight 1490 dest-arms south-east onto 125,31 around the ogre sitting two
tiles down the acquired axe's column; retargeting first dest-armed due
south. Splash has no second observed HP drop on the impact cycle.

Human 7 catapult 1519's eastern Attack Ground click is certified at acquire
c9, projectile-create c13, projectile-flight c14 and impact c34. Type 21 is
live from occupancy until FREE: remaining is 0 for the whole hold, and flag
`0x04` is not the live bit -- Human 13 sets it two cycles after birth, and
Human 7 never sets it. Counting only remaining-distance or `0x04` hid that
sprite. Splash has no second observed HP drop on the impact cycle. That is
**21/185 certified**, not a claim that combat as a whole is complete.

Native projectile receipts now carry the constructor cycle, source slot and
fixed-pool slot. The adapter combines that authenticated source pointer with
the 152-byte unit stride to resolve the target pointer in `AUXL`; Java emits
the same create/flight/removal lifecycle with stable creation identities. This
also corrected an earlier ledger label reversal: projectile `+0x30` is source
and `+0x2c` is target at the captured constructor boundary.

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

It regenerates the current 240-cell resolved-command matrix from three
hash-pinned Human, Orc and expansion fixtures and its dual-adapter execution
ledger, ranked worklist, campaign inventory, Python mutation/identity tests and
focused Java gates, then writes
`tools/bne-harness/work/next-level/status.json`. It does not reuse the stale
checked-in split report. The scorecard reopens the canonical generated-cell
inventory and ledger and byte-recomputes their split; a detached green summary,
an old cell, or a capture with a shorter outcome horizon cannot certify.

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
