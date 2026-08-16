# Native decision micro-oracle

Branch Witness proves which native instruction wrote a byte. The decision miner
proves which branch one visit took. Neither can answer *what the function would
have done with a different number in that register*, because answering that
means running the native code again -- the remote oracle, a fixture, a capture,
minutes per question. That cost is why a rule like "the branch is taken when
`ecx > eax`" gets read off two visits and written into the port, rather than
measured.

The micro-oracle lifts one bounded native function into an emulator. Given an
authenticated snapshot -- the pinned executable's code, the registers, the
stack, and the memory the function reads -- it reproduces the captured
invocation exactly, then answers thousands of "what if" questions per second
against the same instructions, offline.

## What it is not

A rule learned here predicts this function's outcome on inputs it was tested
against. That is stronger than reading two visits and weaker than understanding
the code. It is not a proof of meaning, it does not name what a register holds,
and it does not authorize a source change. The full regression gate remains the
acceptance authority.

## Commands

```sh
# replay a captured decision, explore it, learn its rule
python3 tools/bne-harness/scripts/bne_java.py micro-oracle SNAPSHOT.json \
  --outcome eax --explore-budget 512

# what a completed Branch Witness result would need to become replayable
python3 tools/bne-harness/scripts/bne_java.py micro-oracle-plan \
  ~/.local/share/chonkcraft-bne-branch-witness/runs/SHA/branch-witness.json

# draft the capture specification that decision needs, then review it
python3 tools/bne-harness/scripts/bne_java.py micro-oracle-spec \
  BRANCH-WITNESS.json --out specification.json

# import a capture log the oracle produced, and seal what it authenticates
python3 tools/bne-harness/scripts/bne_java.py micro-oracle-capture \
  specification.json capture-log.txt --out ./captured \
  --executable "$ORACLE/game/Warcraft II BNE.exe"
```

`--outcome` chooses what a rule is asked to predict: `eax`, `path` (the branch
path), or `write:0xADDRESS` (the bytes written to one address). `--synthetic`
declares that a snapshot is one of the non-proprietary test functions rather
than BNE; without it, a snapshot must carry the pinned executable's hash.

Runs are content-addressed below `.bne-micro-oracle/runs/<request-sha256>/` and
carry `manifest.json`, `snapshot.json`, `concrete-replay.json`,
`exploration.json`, `rule-space.json`, `held-out-validation.json` and `NEXT.md`.
The request includes the analysis files' identities and the backend's pins, so
changing either cannot return a stale answer.

## The backend

`unicorn` 2.1.3 emulates x86-32; `capstone` 5.0.7 decodes the executed
instructions so a branch can be told from a move. Both live in
`tools/bne-harness/.venv-micro-oracle`, built by
`scripts/micro-oracle-venv.sh` from `micro-oracle-requirements.txt`. Nothing is
installed into the machine's Python: a parity harness a fresh clone cannot run
is not reproducible.

Both pins are exact and load-bearing. unicorn 2.1.1 aborts with SIGBUS the
moment an x86-32 context is created on Apple Silicon under Python 3.14, and
capstone 5.0.1 imports `distutils`, which Python 3.14 removed.

Rejected alternatives: **angr** (a large dependency tree and whole-process
symbolic execution, which is more than a bounded function needs), **Triton** (no
clean wheel for this platform), **Qiling** (adds OS emulation, and mapping
anything the snapshot did not authenticate is the one thing this must not do).

The harness imports this module under the machine's Python, which has no
emulator, so trials are batched through a worker process inside the virtual
environment. The worker is kept alive for a whole exploration: a process per
batch costs about forty milliseconds, more than the emulation it wraps.

## Snapshot schema (version 1)

| Field | Meaning |
|---|---|
| `schema` | `1`; anything else is refused |
| `executable_sha256` | must equal the pinned BNE 2.02b hash for a native snapshot |
| `entry` | where the replay starts; must be inside a mapped segment |
| `return_sentinel` | the address a normal return lands on and the replay stops at |
| `registers` | all eight general registers plus `eip`, `esp`, `eflags` |
| `segments` | `address`, `access`, and either inline `hex` or a content-addressed `blob` |
| `inputs` | the reviewed places exploration may vary |
| `expected` | the captured outcome: registers, writes, branch path |
| `stubs` | every external call answered rather than executed, named |
| `provenance` | where the capture came from |

Segments larger than 4 KiB are spilled to `blobs/<sha256>.bin` beside the
snapshot and checked on load.

### Fail-closed

Loading refuses, by name: another executable's hash; a blob whose bytes changed
after capture; overlapping mappings; a missing register; an entry or stack
pointer outside mapped memory; a reviewed input that points nowhere; invalid
hex; an empty segment.

Replay fails, rather than continuing, on: a read or write of memory nobody
captured; an instruction budget exhausted; a wall-clock budget exhausted; an
invalid instruction; an unnamed call into uncaptured code.

Filling an uncaptured read with a zero would turn a reproduction into a
fabrication, which is why none of these is a warning.

### Reviewed inputs

Exploration varies only what a person named. Left to itself a mutator rewrites
return addresses and structure pointers and reports that the function's
behaviour is chaotic -- true, and useless.

## Exploration

Boundary neighbours first, because a comparison changes its mind next to a
constant and almost nowhere else. Then, for any input the neighbours did not
explain, a doubling search outward until the outcome changes, and a bisection
to the exact value. About thirty runs, rather than four billion.

Every candidate is executed. **Nothing here is solved for and nothing claims to
be.** A report says `boundary neighbours and confirmed bisection`; if a future
version adds a solver, it must say so separately, because "the solver proved
it" and "we tried it and it did that" are different claims.

## Rules

Examples feed the parity lab's existing bounded CEGIS search. Its grammar
compares one feature against one constant, which cannot express a relation
between two inputs, so the features are derived: each named input, their
pairwise differences and sums, absolute differences, and `wrap(a+b)-c`.

Sums wrap to one register's width because the hardware does. Mathematics says a
sum past `0x7fffffff` grew; the ALU says it went negative; the branch believes
the ALU. Modelling this with unbounded integers made every candidate rule fail
against the probes near the top of the range, and the search reported that
nothing explained a function whose rule was one comparison.

When one predicate cannot explain the outcomes -- a guard, a comparison and a
default is a common shape -- a bounded two-predicate decision list is tried.

### The verifier is the native code

A candidate is challenged by running invocations it and the evidence could
disagree about, near every threshold it proposes. This is not decoration: the
greedy learner proposed a guard at `counter >= 7` because nothing had ever run
with seven; the instructions answered otherwise; the next round produced the
true guard at eight. **Fitting the training examples and the held-out examples
is not the same as being right**, because both are drawn from the same
exploration and can miss the same value.

### Confidence grades

| Grade | Meaning |
|---|---|
| `predictive-and-unique` | validated on held-out invocations, and one behaviour survives |
| `predictive-but-ambiguous` | validated, but more than one distinct rule fits; a distinguishing experiment is named |
| `fitted-only` | consistent with the training examples and never validated |
| `refuted-on-held-out` | fits training, wrong on invocations it was not fitted to |
| `no-rule` | nothing in the grammar explains the outcomes |

Rules that differ only in wording are one rule: over integers `f < 1`, `f <= 0`,
`f > 0` and `f >= 1` are one predicate in four costumes, and counting them as
four survivors reports an ambiguity no experiment could resolve.

## Capture

The capture agent is `bne_snapshot_capture.py`, and it has the same two phases
the branch recorder has, for the same reason.  A generated GDB command file
stops a paused oracle at one activation of one address, prints the machine,
runs the invocation to its return, and prints the machine again.  A
deterministic importer then rebuilds the snapshot from that log alone, so a
capture can be re-imported and argued with long after the oracle that produced
it has moved on.

New capture plans stop at the return address read from the real entry stack:
the script saves the caller PC at `[esp]`, installs a guarded temporary
breakpoint there, and continues. They do not use GDB `finish`. Wine can present
the attached decision as the debugger's outermost frame, where `finish` fails
even though the machine has an unambiguous return address.

The specification is the reviewed part.  It names the address to stop at, which
activation of it, the guard that says this is the unit the divergence is about,
every region of memory to save, what the reviewed inputs are called, and a stub
for any call the capture cannot contain.  `micro-oracle-spec` drafts one from a
completed Branch Witness run and leaves the inputs empty on purpose: what a
register holds is the one thing that evidence cannot say, and a capture that
guessed would produce a rule about a number nobody has named.

### What the capture proves, and what it cannot

| Recorded | How |
|---|---|
| registers | printed twice, at the decision and at its return |
| stack, code, data | dumped twice, byte for byte, in named regions |
| branch path | the BTS instruction history between the two |
| the bytes the invocation changed | the difference between the two dumps |

The write set is a *memory delta*, not an ordered list of stores: a debugger
watching two moments can prove what memory held before and after and cannot
prove what order the stores happened in.  The reproduction gate folds the
replay's writes onto the captured memory and compares the result, which is what
the evidence can insist on.

EIP and EFLAGS are recorded beside the outcome rather than inside the gate.
EIP is the sentinel by construction -- the capture replaces the return address
on the stack, so a replay stops where the invocation ended instead of running
on into the caller -- and EFLAGS carries bits an emulator outside an operating
system does not model.  The substitution is the one thing an import rewrites,
and the address it replaced is in the snapshot's provenance.

### Fail-closed, again

The import refuses, by name: a region that came back shorter than it said, a
dump with a hole in it, a region dumped at a different address in the two
phases, an invocation never seen to finish, a stack slot that does not hold the
return address the capture reported, a branch history that never reaches the
entry, and -- the one that will fire most often on real evidence -- an
invocation that executed code no region captured.  The last names the addresses
so the specification can be widened and the capture repeated.  A replay of
instructions nobody authenticated is not evidence about the game.

## Remote capture

**Dry run by default.** The oracle is shared; a capture that ran because
somebody forgot a flag is a capture that collided with whatever else was using
it. `micro-oracle-plan` prints the plan and refuses `--execute`.

The plan follows the Branch Witness contract: a content-addressed directory of
its own that no other agent shares, the executable's SHA-256 verified before
anything is hooked, no network while native code runs, the canonical oracle and
corpus untouched, original bytes validated before hooking and restored after,
and everything that comes back authenticated -- hash, blob identities, and a
baseline replay that must reproduce the captured outcome before any mutation is
allowed.

On the oracle the plan runs `bne_headless.py snapshot-capture`, which starts a
disposable networkless container of its own, pauses it before the tick the
decision is in, attaches GDB once, and seals the result against the pinned
executable and that run's own manifest.

A native capture is one closed identity, not merely a snapshot filename. The
reviewed specification, imported snapshot, sealing manifest and retained
oracle-run manifest must agree on case, fixture SHA-256, retail scenario,
initialization seed, cycle, native unit slot and program counter. The run
manifest must cover that cycle and pin the retail executable. Symlinked inputs,
outputs and retained artifacts are refused. These checks prevent a perfectly
real machine state from being attributed to the wrong fixture or decision.

Wine and host GDB run privileged on `i9beef`. Before sealing, the runner
returns the bounded output subtree to the unprivileged operator and checks the
ownership of the actual host inodes. The same recovery runs after failure. A
successful container exit with root-owned evidence is therefore an error, not
a capture nobody can reuse.

## Limitations

- **No retail decision has produced an accepted replay receipt yet.** The
  capture agent is proved end to end offline -- a hand-written capture log of
  a synthetic function becomes a snapshot that loads, reproduces exactly, and
  answers variations. A retail smoke has reached the native entry/return flow;
  sealing a complete BTS history and reproducing it remains the next proof.
- Completed Branch Witness artifacts cannot be replayed from. They record a
  branch history and the writer it controls; they never saved the machine,
  because they had no reason to. `micro-oracle-plan` enumerates exactly what is
  missing: registers, stack, code, data, and the captured outcome.
- The pinned executable is available locally and on `i9beef`, but the bytes in
  each accepted snapshot still come from that snapshot's authenticated capture
  rather than from an ambient checkout.
- New capture plans read the caller PC from the entry stack and stop at a
  guarded temporary breakpoint after ESP has unwound beyond the entry frame.
  Legacy reviewed `finish` plans remain readable, but are not generated; Wine
  may expose the decision as GDB's outermost frame, where `finish` cannot work.
- A code region that starts in the middle of an instruction decodes as
  something else all the way down, so the replay and the capture disagree about
  which addresses are branches. It shows up as a failed reproduction rather
  than as a wrong answer, and the drafted specification says out loud that
  nothing in the evidence knows where the function begins.
- Self-modifying code, thread switches inside the captured window, and calls
  into uncaptured code without a reviewed stub are all refused rather than
  approximated.
- Exploration varies reviewed inputs one at a time. A decision that turns on two
  inputs jointly will be found only where the boundary crosses one axis.
- A snapshot that carries an ordered write set is compared store for store, so
  a function whose stores are reordered between captures reads as divergent. A
  captured snapshot carries a memory delta instead, for the reason above.

## Where it sits in the lab

| Stage | Tool | What it gives the micro-oracle |
|---|---|---|
| divergence | `packet` | the case, focus unit and cycle |
| localization | `branch-witness` | the branch and writer to capture at |
| contrast | `decision-plan` / `decision-mine` | an accepted and a rejected visit |
| specification | `micro-oracle-spec` | the draft of what a capture must save |
| **capture** | **`snapshot-capture`** | the machine at that decision, sealed |
| **replay** | **`micro-oracle`** | the outcome at inputs nobody captured |
| naming | `semantic-slice` / `semantic-bridge` | what the operands are, which promotes a rule to `proved` |
| candidates | `counterfactual` / tournaments | whether a rule, once ported, restores future frames |

The fallback when evidence is insufficient is stated rather than implied:
`micro-oracle-plan` declines with a reason and names `decision-plan` and
`decision-mine` as the route that still works.

## Measured performance

Apple Silicon, Python 3.14, unicorn 2.1.3, synthetic functions.

| Measurement | Result |
|---|---|
| Snapshot load and validate | 0.014 ms |
| Cold baseline replay | 82.7 ms (dominated by starting the backend process) |
| Warm replay, one session | **3,402 invocations/s** (0.294 ms each) |
| Full pipeline, one branch | 0.24 s for 44 invocations, ending `predictive-and-unique` |
| Full exploration, two branches and a call | 0.12 s for 70 invocations, 3 outcomes |
| Artifacts per run | 17.0 KiB |

These are synthetic measurements of small functions. A native function will
execute more instructions per invocation and the rate will fall accordingly;
that number cannot be quoted until a native snapshot exists.

The parity-loop saving is therefore projected, not measured: the exploration
above asked the function 44 questions, and the same 44 questions through the
remote oracle would be 44 captures. No native executions have yet been avoided,
because none have yet been performed.
