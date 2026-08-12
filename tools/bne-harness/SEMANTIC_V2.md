# Authenticated semantic-v2 parity

Semantic-v1 answers whether the two engines agree on the first coarse gameplay
fact. Semantic-v2 deliberately looks behind that boundary. It compares the
schema-1.1 state already sealed by the retail oracle with additive Java trace
rows; enabling it cannot change the semantic-v1 trace.

The currently proved comparisons are:

- player supply, unit/building totals, kills, razings, and ten upgrade levels;
- unit tile and pixel position, exact authenticated script sequence cursor,
  animation timer/frame, health, owner, mobile facing, and the active order
  point;
- live projectile slot, position, endpoint, animation frame, and facing; and
- the set of mutable terrain squares changed since cycle one.

The comparator converts Java's 256-direction mobile facing to retail's nearest
eight-way direction. It compares order-point coordinates only while both
engines expose an order whose native record actually owns that coordinate:
Attack/AttackMove use the attack goal, Harvest/ReturnGoods use the resource
tile, and Build uses the build goal. This conditional mapping avoids treating
an inactive union member or sentinel as gameplay state.

The sequence cursor is not normalized or heuristically classified. Both
engines execute the authenticated `script.bin`, and both records expose its
byte offset directly. A mismatch therefore pinpoints the first scheduler visit
where Java and retail entered, waited in, or left an action program differently.

Run the complete tier with:

```sh
python3 tools/bne-harness/scripts/bne_java.py survey CORPUS_INDEX \
  --asset-pack PACK --semantic-v2 --through 1800
```

For the AI executive, emit and compare only compact player macro state. This
makes a full 52-by-1,800-cycle run practical without writing unit-sized traces:

```sh
python3 tools/bne-harness/scripts/bne_java.py survey CORPUS_INDEX \
  --asset-pack PACK --semantic-v2 --semantic-v2-family player --through 1800
```

Feed that receipt to `ai-rank`. It suppresses economy differences occurring
after a casualty/count split in the same case, because those are normally
combat fallout rather than an independent AI choice. Research and economy
differences that precede casualties become the actionable executive queue:

```sh
python3 tools/bne-harness/scripts/bne_java.py ai-rank \
  OUTPUT_DIRECTORY/bne-java-survey.json
```

## Controlled movement corpus

The idle campaign corpus cannot ask a particular unit to make a particular
decision. `command-matrix` compiles authenticated move commands from existing
fixtures for all eight ground headings, four air headings, four sea headings,
and any available occupied-destination cases. The retail oracle seals those
commands into ordinary `.bnefx` fixtures. `bne_java.py` then pairs each native
pool slot to the unique Java unit at cycle one and replays the same command.

```sh
python3 tools/bne-harness/scripts/bne_java.py command-matrix \
  CORPUS_CASES OUTPUT_DIRECTORY
```

The generated plan is captured on the remote oracle exactly like any other
corpus plan. Its cases are intentionally partial and must be replayed with
`survey --allow-partial`. A commanded fixture is accepted only when its sealed
manifest proves at least one command and contains the authenticated
`commands.txt`; malformed or ambiguous slot pairing fails closed.

The first 19-case capture on 2026-08-11 proved the value of this method: every
normal ground/air lane exposed the same early Java step family, while sea and
occupied lanes separated order acceptance/refusal behavior. That replaces a
campaign-specific anecdote with a repeatable native decision matrix.

The completed measurement and engine results are recorded in
[`TOP_THREE_CONVERGENCE.md`](TOP_THREE_CONVERGENCE.md).
