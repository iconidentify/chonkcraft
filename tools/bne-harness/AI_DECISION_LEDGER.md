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

```sh
python3 tools/bne-harness/scripts/bne_java.py ai-decision-ledger \
  native-a.json native-b.json
```

Two identical native captures must compare equal after normalization.
Mutation tests shift one PC transition, one predicate result, and one
state byte and fail at that cycle and field. A retail micro-oracle replay
is only required after the ledger localizes an unresolved decision.

Java emit packs the live 48-byte `AIPlayerState` with file-offset
pointers at `+0x04` / `+0x23` / `+0x27` (`AiDecisionLedger`). Those
offsets already compare equal to native process pointers after
normalization. Dual identical Java ticks write the same JSON. This
document still does not claim Java and native AI decisions are exact.
