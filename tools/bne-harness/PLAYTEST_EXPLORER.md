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

## Fixed command campaign

The maintained checkpoint campaign is
[`command-campaign.json`](command-campaign.json). It contains portable command
recipes for 52 maps / 1,251 commands, 65 Human 1 cases / 111 commands, and
four player-eligibility cases / 12 commands: 121 cases and 1,374 commands.
[`command-campaign-baseline.json`](command-campaign-baseline.json) records the
reviewed per-unit physical prefixes and per-command acceptance, advanced from
release commit `48bddf4` by the player-eligibility correction. These files contain recipes, identities and comparison
summaries; native fixture bytes stay outside Git.

Validate the public inventory without game media:

```sh
python3 tools/bne-harness/scripts/bne_command_campaign.py inventory
python3 -m unittest discover -s tools/bne-harness/tests -p test_bne_command_campaign.py
```

With the private inputs installed, run the complete regression gate from the
repository root:

```sh
scripts/check-bne-command-campaign.sh
```

The default private store is
`$HOME/.local/share/chonkcraft-command-parity`; override it with
`BNE_COMMAND_STORE`. The pack defaults to
`$HOME/.chonkcraft/packs/warcraft-ii-battle-net-edition-usa.chonkpack`;
override it with `CHONKCRAFT_ASSET_PACK` or `--asset-pack`. Its required SHA-256
is `3db9c8f472aebed34426cbca474b37f83dd10eaaeefda16b68dbc03a0b66db75`.
The authenticated classic-media CI pack is a different input and cannot
substitute for this BNE pack.

The gate builds once with pinned JBR 25 and runs the packaged JAR retained in
a new `runs/TIMESTAMP/` directory in the private store. It does not execute
incremental class directories. `--jobs 4` controls concurrency; `--output`
must name a new directory. `--skip-build` requires the existing authenticated
build receipt to match current engine, desktop, harness and runtime inputs.

Every case must supply every requested unit on every cycle. The gate rejects
missing or duplicate cases, omitted fields, shortened observation windows,
adapter failures, changed recipes, changed native producers and stale Java
builds. It preserves **each unit's prefix in each of seven fields**, so one
unit's existing divergence cannot conceal another unit regressing:
life, on-map state, tile x/y, absolute pixel x/y and HP. The adapter fields
`offset_x` and `offset_y` contain absolute pixel positions in this comparison.
Every command whose acceptance agreed with BNE must continue to agree;
correcting an existing refusal mismatch is an improvement.

The 52-map cases run through cycle 600. Human 1 cases include the full tail
after the last command: 26 end at 400, 26 at 401 and 13 at 414. The previous
ad hoc headline capped their prefix sum at 400 even though the adapters
observed the longer tails. The release's complete-window prefix sum was 24,607;
the player-eligibility correction raises it to 25,391, with 61/65 commanded-unit
and 61/65 all-observed-unit exact cases after the combat-tail correction
(previously 59 commanded and 57 all-observed).
The campaign-map prefix sum remains 6,631, with 43/52 exact through 100 and
3/52 through 600.

The four added cases cover empty-worker and soldier Return Goods refusal,
a loaded worker's repeated Return Goods, non-artillery Attack Ground refusal,
and a legal catapult shot alongside refused ogre/worker requests. All 1,374
acceptance decisions now agree with BNE, including nine refusals. Three of the
four new cases match the commanded units throughout; the loaded worker first
differs at cycle 545 on a later trip. Its first 100-gold deposit at cycle 394
is checked separately against the native bank trace. These captures execute
individual recipients through the player dispatcher; pack-backed desktop
tests separately exercise mixed selections and the actual button handlers.

`report.json` records the build, input hashes, complete case inventory,
regressions, improvements and group totals. Each case retains its reconstructed
scenario, both adapter results, first raw-order diagnostics and per-unit
physical frontiers. `passed` means no loss of those measured prefixes or
acceptance agreements. `full_parity` remains false: this gate excludes raw
order/sequence, other world fields, economic outcomes and physical UI layers.
Continue running the idle-map and 18-lane playability gates alongside it.

### Private store and i9beef handoff

On i9beef the 121 pinned captures are retained as
`$HOME/.local/share/chonkcraft-command-parity/objects/SHA256.bnefx`.
Historical integration evidence is separately preserved below
`$HOME/.local/share/chonkcraft-command-parity/checkpoints/48bddf4/`.
Historical receipts keep their original path and build seals; they are not
rewritten to appear freshly executed. New runs reconstruct scenarios from
the commanded fixture's first frame and need no files below `target/issue12`
or a separate idle-map corpus.

To prepare another machine, restore the private object directory and install
the matching BNE pack. Alternatively import captures from a private directory:

```sh
python3 tools/bne-harness/scripts/bne_command_campaign.py import-fixtures \
  --from /private/native-command-captures
python3 tools/bne-harness/scripts/bne_command_campaign.py verify
```

Import copies only captures pinned by the campaign definition and checks their
sealed state streams. It never deletes source captures. An incomplete import
or a missing/changed object exits unsuccessfully. Legacy capture ledgers can
also be imported with repeated `--ledger /private/capture-ledger.json` options.
Archive the object directory and new run directories outside build-cleanup
paths. A fresh clone alone does not contain these private inputs.

### Capture and advance the campaign

Generate all native command scripts and a standard corpus plan without
running BNE:

```sh
python3 tools/bne-harness/scripts/bne_command_campaign.py capture-plan \
  --output "$HOME/.local/share/chonkcraft-bne-oracle/plans/player-commands"
```

The output is a new directory. Its `plan.json` and relative `commands/`
files are accepted by the existing [corpus runner](CORPUS.md). A configured
oracle can execute that plan with `bne_headless.py corpus`, using
`--plan player-commands/plan.json` and a fresh `--output` directory.
Review the deployed tracer before capture: scripted `0x13` orders must enter
the player dispatcher at `0x475f80`. Restore the original pinned captures to
replay the existing baseline. Fresh captures have new byte/producer identities
and require explicit authentication and recipe/baseline review before adoption.
They do not silently replace existing objects.

After a gameplay improvement passes the full campaign, produce a baseline
candidate for review:

```sh
python3 tools/bne-harness/scripts/bne_command_campaign.py baseline-candidate \
  --report "$HOME/.local/share/chonkcraft-command-parity/runs/RUN/report.json" \
  --output /private/review/command-baseline.json
```

This reopens the retained observations, checks their hashes and recipes,
recomputes every frontier and refuses stale, failed or regressing runs.
Review and adopt that candidate with the gameplay change; the runner never
automatically rewrites the repository baseline. Changing the case denominator
requires a reviewed definition and matching baseline, not dropping failed cases.

The [CI guide](../../docs/ci.md#native-command-campaign) explains the private
workflow inputs. Public CI runs the inventory and gate rejection tests without
licensed media.

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

This entry point uses the same typed-family enrichment as
`coverage-inventory`, so `generate --max-scenarios N` materializes the exact
cells in that inventory rather than a movement-only lookalike corpus.

Each generated movement, stop, patrol, attack, harvest, return-goods,
repair, attack-ground, attack-move, stand-ground or train scenario can be
encoded directly for the guarded native command injector.
Those `0x13` families enter the player selection dispatcher at `0x475f80`
with table indices 3, 2, 5, 8, 23, 24, 27 and 17. That dispatcher applies
the player's capability, saved-order, destination and hit-offer rules before
calling GiveOrder. Earlier bare GiveOrder captures are internal diagnostics,
not interchangeable player-command evidence. Stand-ground calls the
order-15 installer at `0x4368b0`.
Train calls the `0x15` inner apply at `0x40e2a0` as
`cycle N train unit SLOT type T` (mode 0). Other families fail closed
and must use the authenticated replay-packet adapter.

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
comparable, exact parity, materially divergent, unmatched historical, missing
current cells, and infrastructure failure. `complete` and `parity` stay false
until an explicit generated-cell inventory is identity-joined and every current
observation agrees. A count-only report is diagnostic and can never complete.

```sh
python3 tools/bne-harness/scripts/bne_playtest_explorer.py split-report \
  tools/bne-harness/work/playtest-explorer/execution-ledger.json \
  --inventory tools/bne-harness/work/playtest-explorer/coverage-inventory.json \
  --output work/playtest-explorer/command-split-report.json
```

Each cell binds the requested map, initialization seed, full command content
(including a train/research type), and terminal observation cycle. Thus an
80-cycle historical capture cannot satisfy a 160-cycle generated outcome, and
the same slots or coordinates on another map cannot collide. The next-level
scorecard additionally regenerates the 240-cell inventory from its three
hash-pinned seeds and reopens the ledger before accepting the split.

Generator pattern and the separately derived family list remain descriptive
inventory labels rather than cell identity; each command's family is still
bound inside the full command content. In particular, a sealed one-command
script retains the exact issue cycle but not the generator's `turn-boundary`
label. The join therefore recomputes and compares only the map, seed,
command-content and terminal fields bound by the cell digest; it still rejects
a relabeled map, command, seed, or horizon and duplicate rows cannot replace
missing cells.

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
