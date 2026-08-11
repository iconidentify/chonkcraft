# Sub-tile precursor evidence

The semantic-v1 parity gate intentionally compares stable gameplay concepts:
tile position, hit points, owner, coarse order, player banks, and synchronized
RNG. A unit can nevertheless occupy the correct tile while its native pixel
coordinates (`IX`/`IY`) and Java pixel coordinates disagree. That hidden phase
can later change spatial-index order, projectile geometry, collision, or the
cycle on which an animation finishes.

With `CHONKCRAFT_TRACE_BNE_SUBTILE=1`, Java parity traces append diagnostic pixel
metadata to each unit record:

```text
u 100 unit-knight p1 119 25 hp 84 o ATTACK px 3827 819
```

Ordinary full-corpus surveys retain the compact format. Triage automatically
enables this metadata only for its focused diagnostic rerun, so divergence
packets receive it without adding I/O to every lookahead case. Existing traces
without `px` remain readable. The determinism differ and the
authoritative semantic-v1 gate continue to ignore the suffix, so this richer
diagnostic cannot lower or silently redefine the accepted frontier.

For a sealed fixture, `cadence` reconstructs native signed pixel X/Y directly
from schema-1.1 `state.bin` offsets 0 and 2. It authenticates the fixture and
Java trace as usual, pairs the unit lifetime, and compares the complete shared
cycle window:

```sh
CHONKCRAFT_TRACE_BNE_SUBTILE=1 \
python3 tools/bne-harness/scripts/bne_java.py case CASE.bnefx \
  --asset-pack /path/to/bne.chonkpack --source-dir /path/to/chonkcraft \
  --output-dir /private/tmp/bne-subtile-case --through N

python3 tools/bne-harness/scripts/bne_java.py cadence CASE.bnefx \
  --java-trace CASE.java.trace.txt --native-unit NATIVE_SLOT \
  --field pixel-position
```

The result reports:

- native and Java pixel-transition cycles and gaps;
- phase offsets and one-time waits;
- the earliest cycle whose tiles still match but pixels do not;
- the native and Java pixels plus their delta; and
- whether a later coarse mismatch exists and how many cycles of warning the
  sub-tile precursor provided.

Divergence packets also attach native raw pixel coordinates and, when the
paired Java trace is current, a `subtile` comparison for every focus unit and
cycle in the packet window. Hidden mismatches receive their own README section.

This tier is diagnostic. It can localize the earlier cause of a semantic
failure, but it does not itself authorize an engine edit or advance the common
frontier.

## Frequency evidence

Cadence is a property of an exhaustive cycle trace, not of arbitrary samples.
The cadence profiler claims a stable period only after at least two equal gaps
(three observed transitions). It may describe a settled tail only after two
equal tail gaps, and labels the scope accordingly. One gap is always
`single-gap`, never a period.

Contrastive Decision Miner captures are different: accepted, rejected, and
held-out cycles are selected because their outcomes are informative. They are
not a census of every function entry. Decision Miner therefore records
`temporal_scope.cadence_claim_supported = false` even when its selected cycles
are evenly spaced. Use a contiguous entry trace or the cadence profiler before
claiming that a native function runs every cycle or on a fixed period.
