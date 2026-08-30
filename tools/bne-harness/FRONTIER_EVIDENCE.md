# Hermetic frontier evidence

An accepted parity proof used to say one thing: the common proven frontier is
h43 and cycle 44 is open. Everything after that -- which two cases are holding
it, what went wrong in each, which tool to reach for, and whether the evidence
on disk is about this frontier or a previous one -- had to be rebuilt by hand,
usually by running the 52-case survey again. The survey takes twelve seconds.
Rebuilding orientation took most of an afternoon.

This is the pipeline that turns an authenticated accepted receipt into a
current, reproducible, routed diagnostic work order. It reads; it never edits
engine source, never merges, and never promotes acceptance.

## The four problems it fixes

**The engine identity hashed the whole workspace.** `_git_identity()` hashed
every untracked file, so `.bne-*` evidence, `goal/` notes and captured traces
sat inside the engine cache key. A clean source tree reported `dirty`, writing
a state-machine report invalidated proofs it could not possibly have changed,
and nineteen commit heads had already produced several workspace identities for
the same engine.

**An accepted proof from a dirty tree could not be rebuilt.** The receipt named
a HEAD and a workspace hash, and the diff that went with them was never sealed.

**A gate receipt carried the survey and nothing else.** Direct gate acceptance
recorded `packets: []`, and everything a packet needed lived in the survey as
an absolute path into a work directory the next survey overwrites.

**The board asked for work that had already been done.** With no packet in the
receipt, the dashboard said `FRAME PENDING` and `Run triage once`, and read its
objective out of PARITY.md -- which still said h29 while the proof stood at h43.

## Commands

```sh
python3 tools/bne-harness/scripts/bne_java.py identity
python3 tools/bne-harness/scripts/bne_java.py identity --list
python3 tools/bne-harness/scripts/bne_java.py capsule seal   DIRECTORY
python3 tools/bne-harness/scripts/bne_java.py capsule verify DIRECTORY
python3 tools/bne-harness/scripts/bne_java.py capsule replay DIRECTORY
python3 tools/bne-harness/scripts/bne_java.py frontier-compile [RECEIPT]
```

`frontier-compile` takes an accepted pointer or a gate manifest and defaults to
`.bne-artifacts/latest-accepted.json`. It writes only beneath
`--output-root`, which defaults to `.bne-frontier-evidence/`.

After moving an accepted receipt to another machine, rehome its external
inputs explicitly:

```sh
python3 tools/bne-harness/scripts/bne_java.py frontier-compile \
  --corpus-index /local/oracle/output/campaign-1800/corpus-index.json \
  --asset-pack /local/packs/warcraft-ii-battle-net-edition-usa.chonkpack
```

The compiler never rewrites the accepted receipt. The replacement index path
and identity become part of the compiled request, packet generation verifies
the selected fixture ID, size and SHA-256, and an asset replacement is accepted
only when its size and SHA-256 match the pack sealed by the survey. Generated
rerun commands use the current repository and these authenticated local paths.

`--watch SECONDS` recompiles whenever the accepted proof changes. It installs
nothing; there is no service and this task did not enable one.

## The engine input identity

`bne_identity.py` is the one implementation. It covers exactly what can move a
number in a survey:

- the reactor `pom.xml` files;
- `engine`, and its build closure `runtime`, `retired-interpreter`, `data` and `assetpack` --
  what `mvn -pl engine -am` actually builds;
- the harness sources under `tools/bne-harness/` that decide what is traced and
  how it is compared, plus the policy files a command reads.

Staged, unstaged and untracked source all count: the record holds each path's
index blob and its working-tree content, so a staged change that has been
reverted on disk is still a different engine. Build output, prose and every
`.bne-*`, `goal/`, work and log path is outside the policy, counted and
reported beside the survey as `workspace_noise`, and never mixed into the hash.

The record declares `schema: 3` and `policy: engine-input-v2`. The reported
commit identifies the checkout but is not mixed into the content hash: a
prose-only commit outside the declared closure cannot invalidate executable
evidence. Producer receipts likewise use the content authority without that
informational commit field, while reports may still show the commit for
diagnosis. An older record
carrying `workspace_sha256` stays readable and is reported as legacy; it can
never be mistaken for a current one, and a future policy cannot alias this one.

## Source capsules

A capsule seals the base commit, the exact staged and unstaged binary patches
over the declared inputs, and every relevant untracked source file with its own
sha256 -- alongside the identity schema, the input policy and the tool identity
needed to check the work.

It reads the index through a copy, so sealing a proof cannot disturb what an
operator has staged. It materializes only into a disposable detached worktree.
It refuses a path that climbs out of itself, a symlinked input, a rewritten
patch, a rewritten manifest, a missing sealed file, and anything over the size
ceiling -- a capsule holds source, never fixtures or captured evidence.

A capsule failure never rejects a valid parity improvement -- the gate still
passes. But a swallowed failure that then reads as "recorded before capsules
existed" would hide a real problem behind history, so it is its own state:
`capsule-failed`, with the reason and a recheck command, printed loudly by the
gate and shown on the dashboard as NOT REPLAYABLE. A receipt that genuinely
predates capsules reports `legacy-no-capsule` instead. Neither is guessed at.

## What acceptance retains now

For the tied earliest blockers only, a gate receipt keeps the Java trace and
process output, a one-case survey pointing at those retained copies, a
reference to the sealed fixture, the source capsule, the finding list and the
first divergence. Every recorded sha256 is checked before a byte is copied; a
trace that was overwritten, a fixture that moved or an index naming a different
capture becomes an explicit `unavailable` state carrying its recovery command.

The forty-nine cases not holding the frontier are not copied and the 13 MB
native capture is referenced rather than duplicated. For the real h43 survey
the whole receipt came to 1.8 MB.

## Two defects found in review, and what they cost

**Retained surveys named the staging directory.** Acceptance writes a receipt
into `.gate-acceptance-*` and then `os.replace`s it into place. The retained
one-case survey stored absolute paths into that staging directory, so every
sealed receipt named a directory that stopped existing at the moment it was
sealed. The bytes were retained correctly; a compile would simply have reported
the frame blocked. The first round of tests missed it because they asserted
against the staging directory itself and never crossed the rename.

Retained paths are receipt-relative now, tagged `path_base: receipt-inputs`,
and `resolve_retained_survey` is the only thing that turns one into a real
path -- after promotion, refusing anything that resolves outside the receipt
and checking every sha256 first. Receipts sealed by the broken version are
still readable: the bytes sit beside the survey that names them, and resolving
by that basename is checked against the identity the survey itself recorded.

**Generated commands did not run.** The recovery instruction read `survey
--index PATH`, but `survey` takes its index positionally, so argparse rejected
it. Sweeping the rest found the same class everywhere: `state-machine --case X`
when it wants `--packet` and `--slot`, `lab --case X` and `counterfactual --case
X --plan-only` when both take a triage run positionally and `--plan-only` does
not exist, `decision-plan --case X --cycle N` missing five required arguments.
Of the 25 commands in a compiled work order, 9 could not be run. An unrunnable
instruction is worse than none, because it reads as though it was checked.

Every command is now built from the real interface, uses real integers and
real paths where the compiler knows them, and declares in `requires_input`
exactly which upper-case placeholders an operator must substitute. The why
chain takes its commands from the route rather than composing a second set --
two places writing commands for one interface is how they drifted. A test
sweeps every command a compiled work order can print through
`bne_java.parser()`.

## Routing

Routing reads the finding, never the case name.

| First finding | Route |
|---|---|
| unit `x`/`y` | packet, cadence, temporal state machine |
| unit `order` | the same, plus a Branch Witness plan for the native write |
| unit `hp` | packet, damage-shape classifier, then the asynchronous RNG ledger **only** when its documented precondition holds; otherwise causal combat and event-order analysis |
| `sync_rng` / `async_rng` | the matching draw ledger |
| anything else | the causal trace |

The hit-point rule is the one that matters. [`RNG_LEDGER.md`](RNG_LEDGER.md)
documents exactly one shape the asynchronous ledger explains: hit points
falling on both sides, on the same cycles, the same number of times, by
different amounts. Escalating on the word `hp` produces a ledger run that
reports the streams agreeing and says nothing about the mismatch.

Every lane that needs the native side is a plan, not a claim. Without an
authenticated native trace it is `blocked` and carries the exact dry-run
capture command. Nothing in a route modifies engine source; the counterfactual
lane is always `--plan-only`.

Tied blockers are routed in estimated-cost order and may be worked in parallel.
That changes packet order, not acceptance: every equally early blocker is still
required before the common frontier moves.

## The why chain

Each link is either supported by evidence in this tree or explicitly unknown,
and an unknown link carries the command that would establish it. For the frozen
h43 receipt the chain reaches:

- **known** first wrong field, semantic correspondence for `unit.x` from the
  authenticated atlas, the regression boundary (clean through 43, wrong at 44),
  the bounded candidate grammar, and the tournament plan;
- **unknown** which native instruction last wrote the field, and the minimal
  native predicate behind it.

No native fact is asserted that a capture has not proved. `NEXT.md` for the
frozen receipt is 8.3 KB, which is small enough for a fresh agent to read
without loading any history.

## What it measured

Against the live parity worktree and its real receipts:

| Measurement | Before | After |
|---|---:|---:|
| Engine identity on the live tree | 0.139 s | 0.067 s |
| Workspace churn inside the cache key | 126,270,219 B over 860 paths | 0 |
| Frozen h43 receipt to a routed work order | a 12 s survey plus manual triage | 0.502 s |
| The same request repeated | recompute | 0.004 s, verified cache hit |
| Older triage receipt at h40 (1 frame) | -- | 0.011 s |
| Older triage receipt at h37 (3 frames) | -- | 0.058 s |
| Synthetic receipt whose traces are gone | -- | 0.020 s, 2 blocked frames with recovery commands |
| Accepted receipt to dashboard-ready status | -- | 2.081 s |
| Compiled evidence root | -- | 788,744 B |
| `NEXT.md` | -- | 8,283 B |

The target was current findings within seconds and a current forensic packet
within thirty seconds without another 52-case survey. The whole path from an
accepted receipt to a dashboard showing the current frame takes two seconds.

The frozen h43 routing proof, which is what the pipeline exists to produce:

    retail-xorc-08-idle  @44 -> position-movement, next lane cadence,
                                then state machine; branch witness blocked
                                on the gryphon rider's order
    retail-human-13-idle @44 -> hit points; measured direction falling,
                                change cycles agree false, change counts
                                agree false -> randomized damage not
                                suspected -> ledger withheld, routed to
                                causal combat and event order

The ogre losing five extra hit points is not a differently rolled blow. The two
engines move that ogre's hit points on different cycles and a different number
of times, which is the one thing the asynchronous ledger is documented not to
explain.

## Rollout

Nothing here changes simulation behaviour, so the order is about evidence
compatibility rather than risk to the game.

1. **Engine identity.** The first survey after this lands writes an identity
   with a new schema, so its request hash differs from every earlier one and
   the first gate after the change is a cache miss. That is the point: the old
   hashes described a workspace, not an engine. No migration is needed; old
   manifests stay readable and report as legacy.
2. **Source capsules and retained blocker evidence.** Receipts written from
   here on carry both. Receipts written before do not, and say so.
3. **The compiler and router.** Purely additive; it reads receipts and writes
   under its own root.
4. **The dashboard.** The exporter needs `--frontier-evidence-root` (or
   `BNE_FRONTIER_EVIDENCE_ROOT`) pointing at `.bne-frontier-evidence`. Without
   it the board behaves exactly as before.

To roll back, stop passing `--frontier-evidence-root` and stop running
`frontier-compile`. `.bne-frontier-evidence/` can be deleted; nothing else
reads it. Reverting the identity commit restores the old cache keys, at the
cost of the false misses it caused.

To run the watcher later, without installing anything:

```sh
python3 tools/bne-harness/scripts/bne_java.py frontier-compile --watch 30
```

## Reading the output

```
.bne-frontier-evidence/
  latest.json                  authenticated pointer, published atomically
  runs/<request-sha>/
    STATUS.json                the machine-readable work order
    NEXT.md                    the same thing for a person
    ROUTES.md                  per-blocker lanes, states and commands
    WHY-CHAIN.md / .json       what is known, what is not, and what would settle it
    manifest.json              every artifact's identity
    blockers/<case>/packet*    the forensic frame
```

`latest.json` never moves backwards: a work order about an older frontier will
not replace one about a newer, unless `--force` says so deliberately.
