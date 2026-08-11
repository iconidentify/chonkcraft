# BNE Branch Witness

Branch Witness turns one authenticated parity packet into a bounded native
control-flow explanation. It answers three progressively narrower questions:

1. Which pinned BNE instruction wrote the divergent field—or, when BNE never
   wrote that field, which changing native precursor should be followed?
2. Which conditional branches immediately preceded that write, and which one
   changed outcome in a clean control?
3. What concrete register predicate separated the failing tick from the clean
   tick?

It is diagnostic evidence only. It never edits Java, never runs in the
production engine, and never replaces the authenticated full regression gate.

## Why the precursor path matters

A position divergence does not guarantee a native X/Y write. BNE may correctly
leave a unit still while Java moves it. The packet's raw 152-byte unit history
is therefore inspected for native-only transitions. For the proved XHuman 12
case, X/Y did not change; the animation timer changed from `3` to `2`. Branch
Witness watched that byte as a **native causal precursor** and did not claim it
had found a nonexistent position writer.

## Generated plan

Every supported Parity Lab case receives `branch-witness-plan.json`. The plan
pins:

- the retail BNE 2.02b SHA-256 and fixed `.text` range;
- native pool slot, 152-byte record layout, field offset, and field width;
- the divergence tick and a maximum 65,536-instruction capture;
- at most 20 reported branch candidates; and
- an offline, immutable, no-source-change policy.

Unsupported packet shapes receive an explicit `supported: false` plan instead
of silently fabricating an address.

## Native capture

Copy the plan to the isolated oracle host and use the dedicated diagnostic
harness/image. The ordinary corpus harness and image do not need to change.

```sh
python3 scripts/bne_headless.py \
  --image chonkcraft-bne-oracle:branch-witness-v1 \
  branch-capture \
  --oracle-root ~/.local/share/chonkcraft-bne-oracle \
  --harness-name harness-branch-witness \
  --plan ~/.local/share/chonkcraft-bne-oracle/plans/CASE.json \
  --case-id CASE \
  --field animation_timer \
  --output branch-witness/CASE-anchor \
  --scenario 'Campaign\XHuman\2XHum12.pud' \
  --seed 1 --cycles 23 --host-gdb
```

The tracer pauses before exactly one requested tick. The diagnostic runner
attaches GDB, arms an x86 hardware watchpoint, starts a bounded BTS history,
and resumes the game. A 120-second tracer timeout prevents an abandoned
capture from hanging indefinitely. The runner always removes its named
container, including on failure.

`branch-capture` alone enables the host PID namespace, ptrace/perf capability,
and relaxed seccomp needed by BTS. The container remains networkless with
`no-new-privileges`; normal oracle runs retain their original restrictions.
`--host-gdb` requires an explicitly configured passwordless sudo path because
Linux perf policy can reject BTS from a sibling container process.

The sealed capture's sibling manifest authenticates the capture, pinned
executable, validated oracle run manifest, tracer, importer implementation,
raw GDB history, backend, exact plan identity, request, and offline runtime.

## Clean contrast and analysis

Derive a control plan without mutating the anchor:

```sh
python3 scripts/bne_branch_capture.py control-plan CASE.json \
  --cycle 22 --field animation_timer --before 4 --after 3 \
  --output CASE-control.json
```

Capture that transition, then compose both sealed captures:

```sh
python3 scripts/bne_java.py branch-witness \
  .bne-artifacts/runs/TRIAGE_SHA \
  --case CASE \
  --capture /path/to/anchor.branch-capture.json \
  --control-capture /path/to/control.branch-capture.json
```

The content-addressed result is stored below `.bne-branch-witness/runs/` with
`latest.json`, a per-case latest pointer, `branch-witness.json`, the exact plan,
and `NEXT.md`. Repeating an identical request is an authenticated cache hit.

The analyzer compares the final dynamic occurrence of each branch before the
watched write. This avoids mixing hundreds of outcomes from a unit-pool loop.
Its Java file ranking is explicitly heuristic and bounded so a large file
cannot win merely by repeating a generic token.

## Predicate pass

When the selected branch immediately follows a register/register `cmp`, the
first capture emits a ready second-pass probe. `NEXT.md` prints the exact five
validated arguments, for example:

```text
--predicate-branch 0x00437646 --predicate-compare 0x00437644 \
--predicate-lhs-register ecx --predicate-rhs-register eax \
--predicate-condition g
```

Repeat the anchor and control captures with those arguments. The breakpoint
records every dynamic operand observation, while the importer retains the
last observation before the exact watched write. The analyzer tests only a
small integer-comparison grammar and prefers the operator encoded by the x86
jump condition. It reports all samples and never generates a source patch.

Automatic operand capture currently accepts only 32-bit register/register
comparisons. Memory operands and multi-instruction flag construction remain
explicit future extensions rather than unsafe guessed expressions.

## Offline semantic pass

When the predicate pass has a clean contrast, run `bne_java.py semantic-slice`
with the capture JSONs and their raw GDB histories. The slicer authenticates
each history against the existing sibling capture manifest, reconstructs the
actual dynamic function activation, and follows the compare operands backward.
Only offsets in the pinned unit layout receive semantic names; static tables
and function arguments stay visibly unresolved. A semantic unit name also
requires a focus-scoped predicate probe: `--predicate-focus-register` identifies
the statically inspected unit-pointer register, and the recorder proves its
runtime value equals the exact watched-unit address.

The pass emits `.bne-semantic-slice/latest.json`, a content-addressed
`semantic-slice.json`, a 17-instruction-style minimum provenance slice, a
held-out prediction, and a boundary experiment. It remains diagnostic and
cannot edit or accept engine source. Formula recovery, held-out prediction, and
focus identity are reported as independent gates.

## Proved retail result (2026-08-02)

On `retail-xhuman-12-idle` at cycle 23, native slot 1553 stayed at `(6,26)`
while Java unit 47 moved to `(5,27)`. The native raw record changed only its
animation timer from `3` to `2`.

The authenticated capture reduced 65,466 executed instructions and 6,175
conditional branches to a 20-branch slice. It located the precursor writer at
`0x00402451` (`mov %cl,0x7(%esi)`) and a clean/failing branch flip at
`0x00437646`. The operand pass observed:

| Tick | `ecx` | `eax` | `jg` outcome |
|---|---:|---:|---|
| failing cycle 23 | 2 | 1 | taken |
| clean cycle 22 | 1 | 1 | not taken |

The bounded inferred predicate is therefore `ecx > eax` with two of two
authenticated observations. Register meanings still require semantic mapping;
the proof does not overstate them as named Java variables.
