# Differential playtest explorer

`bne_playtest_explorer.py` closes the discovery gap between authenticated BNE
evidence and the parity lab. It explores player intent rather than passively
waiting for a manual save:

1. An authenticated seed declares actors, targets, terrain points, movement
   domains and legal command capabilities. It contains facts, not Java rules.
2. The generator emits single, repeated, replacement, group, congestion,
   refusal and named turn-boundary order sequences around the retail
   15-cycle synchronized turn. Production families include train and
   research; an unpaid bill is a refusal, not a silent pass.
3. Separate native and Java commands execute the exact same content-addressed
   scenario. Each must identify its build, prove the scenario identity and
   report acceptance, first physical progress, terminal outcome and final
   observable state for every order.
4. The explorer prioritizes new command/outcome/event coverage. A mismatch is
   reduced with delta debugging and sealed into a packet containing the exact
   scenario, both results, the first difference and the minimization proof.

The system is fail-closed: empty observations, a changed scenario identity, an
unidentified producer, an invalid capability or an adapter failure cannot be
reported as parity.

## Seed format

```json
{
  "schema": "chonkcraft-bne-playtest-seed-1",
  "identity": {"fixture": "combat-save", "source_sha256": "...", "seed": 1},
  "setup": {"kind": "campaign", "scenario": "Campaign\\Human\\Human01.pud"},
  "start_cycle": 30,
  "settle_cycles": 600,
  "actors": [
    {"id": 100, "player": 0, "domain": "land",
     "capabilities": ["move", "attack", "stop"], "target_ids": [200]}
  ],
  "targets": [
    {"id": 200, "player": 1, "domain": "land", "x": 20, "y": 20}
  ],
  "points": [
    {"x": 20, "y": 20, "kind": "target", "domains": ["land"]}
  ]
}
```

## Commands

Promote an existing sealed native movement fixture into a seed. This reuses
the command matrix's authenticated unit slots, movement domains, compass
destinations and occupied targets, then expands them across timing, repetition
and order replacement:

```sh
python3 tools/bne-harness/scripts/bne_playtest_explorer.py seed-fixture \
  capture.bnefx --output seed.json
```

Each generated movement, stop, patrol, attack, harvest, return-goods,
repair, attack-ground or attack-move scenario can be encoded directly
for the guarded native command injector.
Those families use the same `GiveOrder` entry as the authenticated `0x13`
dispatcher, with table indices 3, 2, 5, 8, 23, 24, 27 and 17. Return-goods packets
carry dest `0,0` and target `-1`. Repair packets carry a live building or
transport target. Attack-move uses the dest-only shape of table 8 (dest xy,
target `-1`); table 17 remains attack-ground. Other command families fail
closed here
and must use the authenticated replay-packet adapter; they are never guessed
into native order-function calls.

`playtest-native-commands.json` is the machine-readable registry. It is built
from the execution ledger plus the pinned encodings above. Dual-adapter
counts come only from commanded fixtures both adapters actually ran.
Generated inventory never writes that file.

```sh
python3 tools/bne-harness/scripts/bne_playtest_explorer.py command-script \
  scenario.json --output commands.txt
```

Generate a reviewable corpus without running either engine:

```sh
python3 tools/bne-harness/scripts/bne_playtest_explorer.py generate seed.json \
  --output scenarios.json
```

Turn a sealed commanded fixture into the exact seed those captured orders
already proved:

```sh
python3 tools/bne-harness/scripts/bne_playtest_explorer.py seed-commanded \
  capture.bnefx --output seed.json
```

Run the closed loop. Adapter commands are parsed as argument lists and never
run through a shell. They must contain literal `{scenario}` and `{output}`
placeholders. The production adapters are:

```sh
python3 tools/bne-harness/scripts/bne_playtest_explorer.py explore seed.json \
  --native-command 'python3 tools/bne-harness/scripts/bne_playtest_native_adapter.py --scenario {scenario} --output {output}' \
  --java-command 'python3 tools/bne-harness/scripts/bne_playtest_java_adapter.py --scenario {scenario} --output {output}' \
  --output work/playtest-explorer
```

The Java adapter issues every order through `CommandApplier` and reports
`PlayerIntentJournal` outcomes. The native adapter reports only from an
authenticated commanded fixture or a live pinned-2.02b capture; empty,
mismatched, truncated, or unauthenticated output is refused.

The stable entry point is `work/playtest-explorer/report.json`. Every retained
failure is content-addressed below `divergences/<packet-sha256>/packet.json`.

`split-report` classifies a ledger without claiming parity from dual-adapter
execution. The counts are generated, executed-native, executed-java,
comparable, exact parity, materially divergent, and infrastructure failure.
`complete` and `parity` stay false on that document until the observations
themselves agree.

```sh
python3 tools/bne-harness/scripts/bne_playtest_explorer.py split-report \
  tools/bne-harness/work/playtest-explorer/execution-ledger.json \
  --generated 240 --output work/playtest-explorer/command-split-report.json
```

`worklist` turns that flat split into the queue an implementer should actually
use. It expands state differences, groups fixtures with the same first
behavioral signature, ranks clusters by player-visible impact and witness
count, reports exactness per family, and makes missing queue/group/native
families explicit. Supplying the ledger from the beginning of a goal freezes
the regression comparison: an exact cell becoming divergent is reported even
when a different case improves.

```sh
python3 tools/bne-harness/scripts/bne_playtest_explorer.py worklist \
  /tmp/current-command-ledger.json \
  --inventory tools/bne-harness/work/playtest-explorer/coverage-inventory.json \
  --baseline /tmp/goal-baseline-command-ledger.json \
  --output /tmp/player-intent-worklist.json \
  --markdown /tmp/player-intent-worklist.txt \
  --fail-on-regression
```

The worklist authenticates the Java producer against the current hermetic
engine-input hash. A ledger created before a source or harness change is
stale, not a baseline result. Its headline deliberately separates generated,
executed, comparable, exact and divergent counts. The current v1 fixtures are
resolved per-unit command evidence: they cannot satisfy selection gesture,
group fan-out, acknowledgement, or queued-command coverage, and the report
says so rather than rounding those cells into parity.

## Adapter contract

An adapter writes `chonkcraft-bne-playtest-result-1` JSON. It must report one
ordered observation per command, including boolean acceptance. The native
producer must also name the pinned BNE 2.02b executable as its authority; a
wrapper's own build hash is retained separately. Physical
progress and terminal cycles are compared relative to the issue cycle so a
different absolute fixture start cannot hide or invent a cadence mismatch.
Optional events cover projectile creation/impact, damage, cargo transfer,
resource settlement, boarding and other externally visible lifecycle changes.

The native adapter remains authoritative and must be backed by the pinned BNE
2.02b capture path. The Java adapter should issue through `CommandApplier` and
use the same outcome semantics as `PlayerIntentJournal`. This explorer does not
relax either authentication boundary; it orchestrates and shrinks their output.
