# Cross-Engine Semantic Bridge

The bridge turns a proved native predicate and opt-in Java decision evidence
into a ranked, source-linked diagnosis. It is diagnostic only: it never patches
the engine and it never treats a structural similarity as semantic equivalence.

## Inputs

- a schema-2 `semantic-slice.json` whose formula, held-out behavior, and focus
  unit identity all passed;
- Java `semantic.predicate` JSONL emitted only when
  `CHONKCRAFT_TRACE_BNE_CAUSAL` is enabled;
- the reviewed `semantic-bridge-atlas.json`; and
- an immutable snapshot identity for the Java source searched.

The bridge refuses legacy semantic slices, unproved focus identity, malformed
events, unsupported operators, and unreviewed symbol aliases.

## Command

```sh
python3 tools/bne-harness/scripts/bne_java.py semantic-bridge \
  SEMANTIC_SLICE.json --java-trace JAVA_CAUSAL.jsonl
```

The content-addressed result is written below `.bne-semantic-bridge/`. Repeating
identical inputs is an authenticated cache hit. `latest.json` and `NEXT.md`
contain the compact handoff; `semantic-bridge.json` retains ranked dynamic and
static candidates, exact Java source locations, unresolved symbols, and the
next boundary-test specification. It also reports per-subject Java observation
cycles and gaps. One gap is labeled tentative; a stable period requires at
least two equal gaps. Native anchor/control contrasts are never misreported as
a recurrence series.

## Evidence grades

- `equivalent`: normalized expressions and boundary direction agree, with no
  unmatched native symbol.
- `related-boundary`: enough repeated Java evidence exists to prioritize the
  source location, but an axis, goal, threshold, or complementary condition is
  still unresolved.
- `investigative`: the evidence is too weak for a focused experiment.

The usefulness gate requires a self-consistent best candidate observed at least
twice. The equivalence gate is deliberately stricter. Neither grade authorizes
a source change; the generated boundary experiment and the full regression gate
remain the acceptance path.

## XHuman 12 safety finding

The first bridge trial found the same absolute coordinate-distance shape in
BNE and Java, but the schema-2 replay then exposed that the native predicate's
unit pointer had not been tied to watched slot 1553. The bridge now rejects that
legacy packet before ranking Java. A new focus-scoped anchor and control capture
is required, using the statically inspected unit register (`esi` for native
function `0x004374a0`). This is intentional false-lead prevention.
