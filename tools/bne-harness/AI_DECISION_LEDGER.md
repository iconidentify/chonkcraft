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

The complete 12-cycle Orc 1 smoke capture is one command. It captures retail
on `i9beef`, returns the sealed evidence to the local machine, derives the
ASLR heap base from authenticated `ai.bin`, builds the current Java app, emits
the Java ledger, and reports the first committed-state and telemetry mismatch:

```sh
scripts/capture-bne-ai-cycle.sh
```

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
map walk when it is zero. Java now arms the latch after install. Human 4
player 0 is exact through 200 cycles; Orc 1 player 1 stays exact.

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
1699 vs native 1649) is combat timing, not a second box rule. That is
still not every computer on every mission. A later independent
choice that is not opcode 3, or a launch the boundary snapshots never
hook, remains telemetry debt. Do not change engine behavior to invent
those events.

`scripts/deploy-bne-tracer.sh` is the only rollout path for the DLL and remote
headless driver. New captures are chowned back to the SSH operator by the
remote driver; a root-owned mode-0600 fixture is an infrastructure failure.
