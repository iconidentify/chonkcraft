# BNE Semantic Predicate Slice

Semantic Predicate Slice closes the gap between a native register predicate
and a testable game-state rule. It consumes only sealed Branch Witness evidence:
the plan, anchor and control capture JSON, and the raw GDB instruction histories
authenticated by their sibling manifests.

## Contract

- Execute entirely offline; do not rerun or modify the oracle.
- Reconstruct the exact dynamic function activation that reached the compare.
- Name only fields in the pinned 152-byte BNE unit layout.
- Require a focus-scoped predicate probe that proves the unit-pointer register
  equals the watched unit address in both anchor and control captures.
- Keep unknown native tables, globals, and arguments explicit.
- Require at least one held-out clean/control capture.
- Generate no source patch and retain the full 52-case gate as authority.

## Command

```sh
python3 tools/bne-harness/scripts/bne_java.py semantic-slice PLAN.json \
  --capture ANCHOR.branch-capture.json \
  --history ANCHOR.gdb-history.txt \
  --control-capture CONTROL.branch-capture.json \
  --control-history CONTROL.gdb-history.txt
```

Identical evidence is an authenticated cache hit below
`.bne-semantic-slice/runs/`. Read `latest.json`, the selected manifest, and
`NEXT.md`; open `semantic-slice.json` only when the full instruction evidence or
boundary experiment is needed.

## Proof standard

A semantic proof passes when:

1. the recovered formula predicts the anchor's concrete branch outcome;
2. every held-out capture independently recovers the identical formula;
3. the formula predicts every held-out branch outcome;
4. every capture authenticates the predicate's unit pointer against the exact
   watched unit address;
5. the resulting formula contains at least one field of that proved unit;
6. all input identities and generated artifacts remain unchanged; and
7. unresolved semantics remain visibly unresolved rather than guessed.

Predicate recovery and focus relevance are separate gates. A packet may prove
what a branch computed yet still fail the semantic proof if that dynamic branch
belonged to another unit.

## Superseded XHuman 12 packet

The original XHuman 12 slice correctly recovered the `ecx > eax` calculation
and its held-out outcome, but it assigned callee argument one to native slot
1553 without authenticating that pointer. Replaying the immutable history with
caller provenance exposed a load through global address `0x004ab894`; the
history does not prove that global pointed at slot 1553 for the selected branch
observation. Schema 2 therefore rejects the old packet as investigative.

Future captures must provide `--predicate-focus-register REGISTER` (for this
function, the statically inspected unit register is `esi`). The recorder keeps
only observations whose pointer equals `unit_pool + native_slot * 152`, records
both addresses, and correlates the dynamic hit number to the exact BTS branch
occurrence. Only then may the slicer emit a name such as `unit[1553].x`.
