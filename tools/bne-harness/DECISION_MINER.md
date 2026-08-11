# Contrastive Native Decision Miner

Decision Miner closes the write-watchpoint blind spot in Branch Witness. A
watchpoint can explain why native accepted an order, but it cannot stop on the
earlier visit that deliberately wrote nothing. Decision Miner bootstraps from
the accepted writer, then records the complete focus-unit function activation
at both the accepted and rejected visits.

It is diagnostic only. It never patches Java, changes a fixture, or replaces
the authenticated full parity gate.

## Evidence pipeline

```text
sealed fixture + Branch Witness plan
        |
        | prove target field unchanged/rejected and changed/accepted
        v
accepted writer bootstrap
        |
        | derive containing native function + focus-unit register
        v
focus-scoped entry-to-return BTS captures
        |
        | align rejected and accepted branch histories
        v
ranked branch-outcome flips
        |
        | automatic register/immediate/memory operand pass
        v
held-out predicate validation -> semantic bridge handoff
```

Every native capture remains pinned to retail BNE 2.02b, networkless, bounded
to 65,536 instructions, and sealed with the exact plan, raw GDB history,
importer, tracer, executable, and oracle-run manifest.

Decision phases are selected contrasts, not an exhaustive entry trace. Every
mined result records a `temporal_scope` that refuses cadence claims from those
samples, even when accepted, rejected, and held-out cycles have equal gaps.
They prove what differed at the captured visits, not how often the function
ran between them. Use a contiguous entry capture or
[`cadence`](SUBTILE_EVIDENCE.md#frequency-evidence) for frequency claims.

## 1. Prove the accepted/rejected fixture contrast

Start from the Branch Witness plan emitted for the rejected divergence:

```sh
python3 tools/bne-harness/scripts/bne_java.py decision-plan \
  BRANCH_WITNESS_PLAN.json \
  --fixture CASE.bnefx --native-unit 1519 --field order \
  --rejected-cycle 29 --accepted-cycle 34
```

The planner reconstructs raw schema-1.1 state. It refuses to proceed unless
the selected field stays unchanged on the rejected cycle and changes on the
accepted cycle. The first pass emits
`bootstrap-branch-witness-plan.json`, adjusted to watch the accepted write.

## 2. Bootstrap the decision function

Generate an auditable remote plan without changing the oracle:

```sh
python3 tools/bne-harness/scripts/bne_java.py decision-remote \
  bootstrap-branch-witness-plan.json --bootstrap
```

Set `CHONKCRAFT_ORACLE_HOST` to the maintainer-managed SSH alias and add
`--execute` to run the isolated capture. Then repeat
`decision-plan` with the downloaded capture and the pinned executable:

```sh
python3 tools/bne-harness/scripts/bne_java.py decision-plan \
  BRANCH_WITNESS_PLAN.json \
  --fixture CASE.bnefx --native-unit 1519 --field order \
  --rejected-cycle 29 --accepted-cycle 34 \
  --bootstrap-capture ACCEPTED.branch-capture.json \
  --native-executable 'Warcraft II BNE.exe'
```

The accepted writer identifies the focus register from the exact field-memory
operand. Static analysis of the pinned executable locates the function that
contains the writer and proves where its prologue loads that register from the
entry stack. The entry breakpoint authenticates that argument before the
prologue changes registers. The accepted raw history also identifies the exact
caller that reaches the writer, preventing another call to the same function
from being mistaken for the decision. Explicit `--entry-address` and
`--focus-register` remain available when either derivation is ambiguous, but
the pinned executable is still required to prove the entry focus source.

## 3. Capture both decisions

```sh
python3 tools/bne-harness/scripts/bne_java.py decision-remote \
  decision-plan.json --execute
```

Remote execution, including the accepted-writer bootstrap, creates a
content-addressed copy of the diagnostic harness and uploads the exact local
capture scripts into it. It does not modify `harness-branch-witness` or the
ordinary corpus harness, and it does not depend on either one's command set
remaining current. Remote output paths include both the evidence-plan and
capture-implementation identities, so a tool upgrade never overwrites or
collides with earlier evidence.
Rejected, accepted, and optional held-out phases receive distinct containers
and output directories.

The GDB recorder stops only when the configured function is entered with its
unit-pointer register equal to the exact watched pool-slot address. It begins
BTS at that entry and stops at the activation's dynamic return address. Thus a
rejected decision is captured even when it has no writer.

## 4. Mine and probe the branch boundary

```sh
python3 tools/bne-harness/scripts/bne_java.py decision-mine \
  decision-plan.json \
  --capture REJECTED.decision-capture.json \
  --capture ACCEPTED.decision-capture.json
```

The miner aligns complete branch sequences, ranks concrete outcome flips, and
emits `predicate-probe-plan.json` for the strongest supported flag producer.
The bounded operand grammar supports:

- register and immediate operands;
- focus-relative and static memory operands;
- direct focus-field loads into a compared register, recovered by bounded
  backward def-use;
- base/index/scale effective addresses; and
- `cmp`, `test`, `sub`, `and`, and `or` flag producers separated from the
  branch only by flag-preserving instructions.

Unknown operands stay explicitly unresolved. Unsupported flag construction
produces no predicate rather than a guessed rule.

Repeat `decision-remote` and `decision-mine` with the predicate plan. Operand
observations are accepted only when the focus register again equals the exact
watched unit address.

## 5. Require held-out prediction

Add a third known outcome when planning:

```text
--heldout-cycle CYCLE --heldout-outcome accepted|rejected
```

Two samples localize a branch but do not prove a reusable predicate. A direct
schema-2 `semantic-slice.json` is emitted only when:

1. rejected and accepted outcomes both match the sealed fixture;
2. the same branch predicate predicts both outcomes;
3. a distinct held-out capture predicts correctly;
4. every observation proves focus identity; and
5. at least one operand is named directly from the pinned 152-byte unit
   layout.

That output can be passed to `bne_java.py semantic-bridge`. It is still only a
boundary-test lead; the regression test must fail on the pre-fix engine via
`test-efficacy`, and the full 52-case gate remains authoritative.

If an `order` divergence resolves only to `unit[*].next_order`, the result is
labeled `order-promotion-boundary`. That proves when a queued replacement is
promoted, not why it was queued. The generated handoff remains valid evidence,
but the next native contrast must move upstream to the producer before an
engine change is considered.

## 6. Scoping an upstream activation

The producer usually sits in a function that has already returned by the time
the watched field is written. Name it explicitly:

```sh
python3 tools/bne-harness/scripts/bne_java.py decision-plan \
  BRANCH_WITNESS_PLAN.json \
  --fixture CASE.bnefx --native-unit 1519 --field order \
  --rejected-cycle 29 --accepted-cycle 34 \
  --heldout-cycle 24 --heldout-outcome rejected \
  --bootstrap-capture ACCEPTED.branch-capture.json \
  --native-executable 'Warcraft II BNE.exe' \
  --entry-address 0x0040b010 --focus-register esi
```

An explicit `--entry-address` requires an explicit `--focus-register`, because
the accepted writer only names the register its own function used. Two things
then change automatically.

The caller is proved from the recorded history rather than from a call
immediate: a `call` whose next recorded instruction is the entry address
reached that entry. That covers the order dispatch's
`call dword [edx*4 + 0x495ed8]`, whose target is a runtime table lookup. A
direct call must still agree with its own immediate.

The activation is then classified. Counting entry and resume addresses tells
the planner whether the accepted write happens inside the activation or after
it returns, and it records `activation_scope` and `outcome_source` in the plan:

| scope | outcome source | the capture asserts |
|---|---|---|
| `writer-containing-activation` | `activation-field-delta` | entry and exit values both match the fixture |
| `upstream-activation` | `fixture-cycle-outcome` | only the entry value; the sealed fixture cycle labels the visit |

An upstream visit leaves the watched field exactly as it found it, so its own
delta cannot say whether it accepted. The sealed fixture cycle does, and the
miner refuses a capture whose recorded outcome source differs from its plan's.

## Human 13 benchmark

The sealed `retail-human-13-idle` fixture proves the intended contrast without
launching BNE: slot 1519's order byte remains `2` at fixture cycle 29 and
changes to attack order `12` at cycle 34. The generated bootstrap plan watches
the cycle-34 write. No remote capture is required merely to validate or cache
that plan.
