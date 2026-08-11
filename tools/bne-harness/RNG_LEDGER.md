# Asynchronous RNG ledger

BNE and this port draw from two generators. The synchronized one at
`0x004a48dc` steps `state * 0x41c64e6d + 0x3039` and is the seed a fixture
records every cycle. The asynchronous one at `0x004d40ec` steps
`state * 0x015a4e35 + 1` and pays for construction, idle animation, projectile
setup, and the opcode-ten melee damage formula. Nothing writes the second seed
into a fixture, so a wrong asynchronous draw is invisible in the ordinary
comparison until it moves a unit's hit points.

The ledger compares the two engines' asynchronous draws directly and says
which draw the disagreement began at.

## When it runs by itself

The parity lab escalates to the ledger when the first finding is a unit's hit
points and the packet shows those hit points

- falling on both sides, never rising,
- changing on exactly the same cycles,
- changing the same number of times,
- by different amounts.

That is what a differently rolled blow looks like, and nothing else on the
lab's list of hit-point hypotheses looks like it. A building coming up, a
heal, a per-tick rate difference, and a unit that lost hit points on a cycle
the other engine did not all keep their rounding, event-order and rate
hypotheses, and none of them escalates -- a draw ledger run on those would
report the streams agreeing and say nothing about the mismatch.

The escalation does not replace the ranked plan. `randomized-damage` joins
`rounding`, `event-order` and `rate` as a fourth competing hypothesis, and the
ledger is ranked with the others by expected information gain per cost.

## The command

```sh
python3 tools/bne-harness/scripts/bne_java.py rng-ledger \
  --java-causal .bne-artifacts/runs/TRIAGE_SHA/.../CASE.causal.jsonl \
  --native-trace /path/to/CASE.trace.txt \
  --case retail-xhuman-12-idle
```

The Java side is the JSONL a run writes when `CHONKCRAFT_TRACE_BNE_CAUSAL` names a
path. The native side is an oracle trace captured with the asynchronous hook
installed. Both are read; neither is produced here.

Results are content-addressed by the two files' identities and by the
identities of the analysis code, so an edit to either cannot return a stale
hit. Each run writes `RNG-DIFF.json`, its `RNG-DIFF.md`, and a manifest
naming both, below `.bne-rng-ledger/`.

The exit code is `0` when the ledgers agree, `1` when a lead was produced, and
`2` when the evidence cannot be believed.

## Authentication

The native side is accepted only through the same contract the rest of the lab
uses. Beside `TRACE.trace.txt` there must be a `TRACE.manifest.json` that

- is schema 2,
- names the trace by filename, byte count and SHA-256,
- records the pinned BNE 2.02b executable SHA-256,
- records `network_disabled: true`.

Anything else is refused outright. An unauthenticated trace is never quietly
compared with a weaker method: the report becomes the capture command for the
missing side. That is the point -- a Java ledger compared against nothing
would read as agreement.

## Reading an RNG-DIFF

Alignment is on seed transitions, never on cycles. Both generators are LCGs,
so a draw is completely described by the seed it started from, and two engines
consuming the same stream produce identical `before -> after` steps whatever
they were doing at the time. A constant or drifting cycle offset between the
engines is therefore reported as a measurement rather than fought as an
obstacle.

Every recorded draw is checked against the generator it claims to be before
anything is aligned. A transition that is not `seed * multiplier + increment`,
or a returned value that is not the documented cut of that seed, stops the
run: that is evidence about the tracer, not about the game.

| Result | What it means |
|---|---|
| `identical` | Same seeds, same consumers, same order. |
| `java-extra-draw` | Java took a number out of the shared stream that native did not. |
| `native-draw-missing-in-java` | Native took one that Java did not. |
| `consumer-reordered` | The same consumers spent the same seeds in another order. |
| `same-seed-different-consumer` | A seed was spent somewhere it had never been spent. |
| `window-length-difference` | One capture simply covers more of the run than the other. |
| `seed-chain-diverged` | The seeds themselves differ, so something earlier was already wrong. |
| `malformed-lcg-transition` | A recorded draw is not the generator it claims to be. |
| `native-evidence-missing` | No authenticated native ledger; the report is a capture command. |

### Missing and extra draws are not seed differences

Both engines take numbers out of one shared generator, so an extra draw does
not change any later seed -- it changes who spends it. Every draw after the
extra one carries the seed its neighbour used to carry, and the two seed
chains stay identical while the ledgers stop meaning the same thing.

That is why the disagreement the ledger looks for is the first draw whose
consumer stopped being the one it had always been, and why the extra or
missing draw is reported at the shift rather than at the end of the shorter
ledger. It is also why a Java run that merely stopped earlier is classified as
a shorter window: calling that a missing draw would turn a short capture into
a finding about the game.

### Reordering versus a different consumer

Both are reported at the same place, and the difference is what the window
holds. If the draws in the window are spent by the same set of consumers in a
different order, it is `consumer-reordered`. If a draw is spent by a consumer
the window has not paired with that native address before, it is
`same-seed-different-consumer`.

## Limits of native-to-Java attribution

Native records a return address. Java records a class and a method. These are
different kinds of name and this tool never treats them as equal.

Where the aligned evidence makes a pairing look consistent, the report lists
it as an observed correspondence with its support count and the flags
`derived_from_this_evidence: true` and `proved: false`. The pairing that the
first-disagreement line is measured against is learned from the agreeing
prefix of this one pair of ledgers -- the first time an address is seen beside
a method the two are provisionally partners -- and it is discarded with the
run. No address-to-method table is stored, shipped, or assumed.

So a `same-seed-different-consumer` result says the Java consumer of a draw
changed relative to everything else in this window. It does not say what the
native address is, and it does not authorize naming it.

## The Java side

Every draw records its cycle, its seed before and after, the value returned, a
running draw number, the class and method that consumed it, the callers above
that, the source line as a hint beside the name rather than as the name, and
the semantic context when the engine already knows one.

The identity is `Class.method` on purpose. It used to be `method:line`, which
made the same draw change identity whenever a comment was added above it, and
it used to be read from `World` frames alone, which reported every draw taken
inside an extracted `BattleNet*System` as `?`.

The caller chain is what distinguishes a blow struck through
`BattleNetCombatSystem` from the same formula reached another way: the melee
damage roll is taken in `World.battleNetMeleeDamage` whoever asked for it, and
the chain above it is what says who did.

Nothing walks a stack or builds an event when causal tracing is off. Measured
on a 20,000,000-draw loop, an asynchronous draw with tracing disabled costs
0.42 ns; the previous shape, which built the diagnostic event whether or not
anyone was listening, cost 5.03 ns. With tracing enabled a draw costs about
4.2 us, which is why it is opt-in.

## Evidence boundaries

- The ledger reads evidence and never captures it.
- A result is an investigative lead. The full regression gate remains the
  acceptance authority.
- The synchronized stream can be aligned the same way with `--stream sync`,
  with one caveat: native cuts its returned value from the advanced seed and
  this port cuts it from the seed as it stood, so the value is checked per
  side and never compared across the two.
