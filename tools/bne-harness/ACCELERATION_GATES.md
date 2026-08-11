# Parity Acceleration Gates

These commands remove four recurring manual investigations without weakening
the full regression gate. They are diagnostic and preserve source by default.

## 1. Start every fresh agent with a capability report

```sh
python3 tools/bne-harness/scripts/bne_java.py doctor
python3 tools/bne-harness/scripts/bne_java.py doctor --need capture
```

The doctor checks the repository, asset pack, ChonkCraft source, pinned executable,
local Docker/oracle state, the configured remote corpus and Branch Witness image, and the
actual `branch-capture` subcommand in `harness-branch-witness`. It then
recommends the cheapest route that supplies the requested capability.
This prevents an agent from declaring native capture unavailable before it has
checked the documented remote oracle.

## 2. Profile a movement divergence before tracing internals

```sh
python3 tools/bne-harness/scripts/bne_java.py cadence CASE.bnefx \
  --java-trace CASE.java.trace.txt --native-unit NATIVE_SLOT
```

The profiler authenticates the sealed fixture, infers the paired Java unit when
unambiguous, extracts state transitions, and reports cycles, gaps, settled-tail
periods, phase offsets, and one-time added waits. It does not infer source
semantics or produce a patch.

The Human 5 zeppelin proof reports native `9,29,49,69`, Java `9,39,59,79`,
phase offsets `0,10,10,10`, and a one-time ten-cycle delay.

When tile positions agree but a later decision depends on movement phase, use
the authenticated sub-tile tier:

```sh
python3 tools/bne-harness/scripts/bne_java.py cadence CASE.bnefx \
  --java-trace CASE.java.trace.txt --native-unit NATIVE_SLOT \
  --field pixel-position
```

Triage automatically enables `CHONKCRAFT_TRACE_BNE_SUBTILE=1` for its focused
diagnostic rerun; set it explicitly when producing a manual Java trace.
Semantic-v1 continues to ignore the metadata for acceptance. The profiler
reconstructs native `IX`/`IY` from sealed raw state and reports the earliest
hidden pixel mismatch. One interval never becomes a period claim; at least two
equal observed gaps are required. See
[`SUBTILE_EVIDENCE.md`](SUBTILE_EVIDENCE.md).

## 3. Reject tests that pass both before and after a fix

```sh
python3 tools/bne-harness/scripts/bne_java.py test-efficacy \
  --baseline PRE_FIX_COMMIT --test TestClassName
```

The gate creates a temporary detached worktree for the pre-fix commit, overlays
the selected candidate test source (but no production or unrelated test
sources), and runs the same Surefire selector there and in the candidate. This
ensures a newly added test is evaluated against both implementations without
letting later, unrelated test helpers contaminate the baseline. Acceptance
requires:

1. the baseline test executes;
2. the baseline has an assertion failure, not a compilation/infrastructure
   error;
3. the candidate test executes; and
4. the candidate passes.

Passing both ways is classified `false-guarantee`. Temporary worktrees are
removed after the run; source files are never reverted in place.

## 4. Investigate tied frontier blockers by cost

`frontier`, `triage`, and `autopilot` now score only cases tied at the earliest
divergence. Existing position transitions and periodic air movement rank ahead
of hidden order acquisition that needs native capture. This changes packet
generation order, not acceptance priority: every equally early blocker remains
required before the common frontier advances.

For position mismatches, experiment planning now starts with the cadence
profiler before route logging or Branch Witness. Cadence evidence can then hand
off to the semantic bridge for exact Java decision localization.

## 5. Compile an accepted proof into the next diagnostic

```sh
python3 tools/bne-harness/scripts/bne_java.py frontier-compile
```

Reaching an accepted frontier used to tell an agent that one cycle was open and
nothing else, so orientation was rebuilt by running the 52-case survey again --
which is most of the elapsed time between two frontiers, against twelve seconds
of measurement. This authenticates the accepted receipt, builds a forensic
frame for every tied earliest blocker from the evidence the receipt retained,
routes each one by its finding, and writes the work order beneath
`.bne-frontier-evidence/`. On the h43 receipt that is half a second cold and
four milliseconds repeated.

It reads. It never edits engine source, merges, or promotes acceptance.

The engine cache key it is addressed by covers the engine and its build
closure and nothing else, so writing a diagnostic report no longer looks like a
different engine. See [`FRONTIER_EVIDENCE.md`](FRONTIER_EVIDENCE.md).
