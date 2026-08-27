# AI decision ledger

Semantic-v2's player family counts supply, units, and upgrades. It does
not say whether a computer player ran the same `ai.bin` instruction that
retail BNE 2.02b ran. This ledger does.

Each row is one active computer player at one gameplay cycle:

- player and `ai.bin` profile
- normalized program-counter, ordered-list, and threshold-table offsets
- wait
- every non-pointer byte of the native 48-byte `AIPlayerState`
- predicate attempts and results
- state writes
- launch/order consumption
- `independent-choice` or `fallout`

Pointers at state `+0x04`, `+0x23`, and `+0x27` become `ai.bin` file
offsets. A raw process address, an out-of-range pointer, a missing
active-player cycle, or two different rows for the same cycle and player
fails closed.

Write telemetry uses that same normalized state. Native process-pointer
bytes are converted to `ai.bin` offsets before cycle differences are
derived, so ASLR cannot make an otherwise identical Java write compare
different. Java also merges the periodic build-box refresh into the
current cycle's sorted net writes; these annotations explain already
committed state and do not alter simulation behavior.

The complete 12-cycle Orc 1 smoke capture is one command. It captures retail
on `i9beef`, returns the sealed evidence to the local machine, derives the
ASLR heap base from authenticated `ai.bin`, builds the current Java app, emits
the Java ledger, and reports the first committed-state and telemetry mismatch:

```sh
scripts/capture-bne-ai-cycle.sh
```

Fleet work uses the content-addressed
[AI evidence conductor](AI_EVIDENCE_CONDUCTOR.md). Its default is read-only
remote discovery; `--materialize` imports only authenticated manifests and
normalized ledgers, executes current-engine Java twins, enforces a fixed
player/cycle denominator, and writes ranked `NEXT.json` / `NEXT.md` results:

```sh
python3 tools/bne-harness/scripts/bne_java.py ai-conductor
python3 tools/bne-harness/scripts/bne_java.py ai-conductor \
  --materialize --case ai-orc01-1800 --limit 1 --jobs 1
python3 tools/bne-harness/scripts/bne_java.py ai-conductor --validate-store
```

The conductor does not copy the remote trace or manage Docker containers.
Use this path for repeated campaign-wide work; keep the shell script as the
explicit capture producer and one-case diagnostic.

The fixed denominator comes from the independently authenticated `state.bin`
controller table—not from whichever AI rows happened to be traced. The Java
adapter automatically uses the map's real `PERSON` slot and records that seat
plus every computer slot in its ledger. Fleet certification is deliberately
stricter than one exact window: all 52 missions must be materialized under the
same current build/pack proof and both state and telemetry must be exact
through cycle 1,800.
The validator recomputes the current proof and every retained object/twin
relationship; a detached `RUN.json` or summary is never certification.

Change the window without editing the script:

```sh
BNE_AI_CYCLES=200 BNE_AI_CYCLE_OUT=/tmp/orc1-ai \
  scripts/capture-bne-ai-cycle.sh
```

The lower-level commands are:

```sh
python3 tools/bne-harness/scripts/bne_ai_decision_ledger.py from-trace \
  trace.txt --ai-bin ai.bin --active-player 1 \
  --cycle 1 --cycle 2 --output native.json

python3 tools/bne-harness/scripts/bne_ai_decision_ledger.py compare \
  native.json java.json
```

`from-trace` reads tracer `ai-build-boundary` 48-byte dumps
(`CHONK_BNE_TRACE_AI_BUILD_STATE=1`). The trace records a pre- and post-tick
state for every gameplay cycle. Pointers at `+0x04` / `+0x23` /
`+0x27` become `ai.bin` file offsets. Two identical native dumps must
compare equal after normalization.
Mutation tests shift one PC transition, one predicate result, and one
state byte and fail at that cycle and field. A retail micro-oracle replay
is only required after the ledger localizes an unresolved decision.

Java emit (`BneAiDecisionAdapter`) packs the live 48-byte `AIPlayerState` with file-offset
pointers at `+0x04` / `+0x23` / `+0x27` (`AiDecisionLedger`). Those
offsets already compare equal to native process pointers after
normalization. Dual identical Java ticks write the same JSON.

Comparison deliberately reports two answers. `state_identical` covers the
committed program state and is the gameplay parity signal. `telemetry_identical`
covers predicate, write, launch, and independent-choice annotations and is the
causal-explanation signal. An absent native hook must not disguise exact state,
and exact state must not erase instrumentation debt.

The first live end-to-end run on 2026-08-15 proved all committed fields exact
for player 1 through the 12-cycle Orc 1 window: profile 0, PC `0x1ba6`, list
`0x2370`, threshold `0x23c0`, wait, and all 36 non-pointer bytes.

The interpreter often runs after the committed `game-after` snapshot, so a
same-cycle `game-before` dump already holds the new wait and hides the write.
`from-trace` now seeds incoming state from the previous committed after-state
(including the last warmup-after) and recovers opcode-3 WAIT-UNTIL attempts
from authenticated `ai.bin` when the incoming wait is zero. The same Orc 1
player-1 capture now compares `state_identical` and `telemetry_identical`
through 12, 200, and 1,800 cycles: PC stays `0x1ba6`, wait oscillates 0/1,
predicate 3 (worker count) fails every independent choice, and no launch is
consumed.

Human 1 and Human 4 computers SET `+0x0c=0` during install. Native
`0x428160` already reads that byte as the builder-scan latch and skips the
map walk when it is zero. Java now arms the latch after install. The current
head conductor store materializes all 14 classic Human missions plus Orc 1
through 1,800 cycles under one proof: Human 1, 2, 3, 6, 9, 10 and Orc 1
are state and telemetry exact; Human 7 and 12 are state exact with
telemetry-only first misses. The ranked state frontier is Human 13 player 0
at 466, then Human 4 player 0 at 1,414: both are opcode-3 predicate 3
succeeding so the PC advances two bytes while native still fails the
wait-until. That is extra completed family-word workers, not a second
predicate rule -- do not invert pred 3. Human 8 player 0 at 149 already has
the `3e1656` box rewrite while native still holds `3e164f`. Human 5 player 5
is still telemetry-only at cycle 2.

The 0x4273e0 pad after the land walk is 8-bit wrapping minus 5 / plus 8,
then a signed clamp. A 128-tile computer whose signed min never moves
used to pad 128 down to 0 in wider integer arithmetic; retail wraps
`0x80-5` to 123 and can leave a wrapped max of 134. Human 5 player 0,
Orc 12 player 1, and Human 13 (all three computers) now match that box.

Post-placement bootstrap must not drain a wait-until yield. Human 5
player 5 used to succeed predicate 3 there and run WAIT 6000 before
fixture cycle 1; retail still holds wait 1 through warmup-2 and is
wait 0 at PC 561 on game-after 1. A long opening WAIT still gets one
cycle-zero decrement so Human 1 / Orc 2 stay 65532. Human 5 is now
state-identical through 200 (cycle-2 player-5 telemetry still omits
the native PC-pointer write).

Native 0x44c260 decrements word 0x4be130 and, on zero, stores 50 and
calls 0x4273e0 -- the same 49 / 99 / 149 beat as launch consume.
Human 8 player 0 rewrites `3e164f` to `3e1656` on that beat; Human 5
player 0 rewrites `7b3675` to `7b3175`. Java now walks again there.
A counted building that dies a beat later in Java (Human 5 seed 1 at
1699 vs native 1649) is combat timing, not a second box rule.

0x439ce0 only accepts a hall on the newest unit's 0x4ad650 map
component (sea and air heads skip that test). A hall on another
island used to open the box; Human 7 player 5 and Human 9 player 1
keep the inverted 96-tile rectangle, while Human 7 player 2 still
expands. The list head is native IsAlive (`!Destroyed && action !=
Die`), so a peon inside a mine or a tanker inside a platform stays
the head; 0x438510 still compares that unit's stored tile. Skipping
Removed heads used to invert XHuman 3 player 3 at 699 and player 0
at 1049. Human 9 and Human 12 are exact through 200; Human 7 is
state-identical (cycle-49 telemetry still records native's box
rewrite bytes).

Opcode-3 predicate 3 reads the per-player word at `0x4addcc`, which
`0x417700` increments for a completed peasant, peon, attack-peasant
or attack-peon. A live gatherer walk used to count tankers that
retail's family word never accepted. Hall peon trains use the same
word for the `(workers-1)/2+1` reserved-train quota.

Opcode-3 predicates 4/5/6 are assigned-force counters, not a live
soldier census. Counting units used to pass `0x0d*0x0e` when the
multiplier was still 0, so Orc 5 player 1 walked to PC 7091 while
retail stays on WAIT-UNTIL 4 at 7089 (99 native fails, 0 successes
through 200). Orc 5 is now state-identical on both computers.
That is still not every computer on every mission. A later independent
choice that is not opcode 3, or a launch the boundary snapshots never
hook, remains telemetry debt. Do not change engine behavior to invent
those events.

`scripts/deploy-bne-tracer.sh` is the only rollout path for the DLL and remote
headless driver. New captures are chowned back to the SSH operator by the
remote driver; a root-owned mode-0600 fixture is an infrastructure failure.
