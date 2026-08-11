# Temporal native state machine

Some divergences are not a wrong number on one cycle. They are a small state
machine running inside the native unit record over several cycles: something
settles, an unnamed byte climbs one per cycle changing nothing anyone can see,
and at some value a route is thrown away and a timer is armed -- and the unit
replans somewhere else entirely a dozen cycles later.

Read one cycle at a time that is invisible. The ordinary comparison sees a unit
standing in the same square with the same hit points and the same order, and
reports nothing at all until the consequence lands. Finding the cause by hand
means diffing 152 bytes across a dozen cycles and guessing which of the
changing offsets matters.

This tool does that mechanically.

## The command

```sh
python3 tools/bne-harness/scripts/bne_java.py state-machine \
  --packet .bne-artifacts/runs/TRIAGE_SHA/.../packet.json \
  --slot 1448 \
  --java-causal .bne-artifacts/runs/TRIAGE_SHA/.../CASE.causal.jsonl \
  --java-unit 152 \
  --compare work/packets/other-case/packet.json=1553 \
  --proposed-state work/proposals/keep-route.json
```

Only `--packet` and `--slot` are required. Results are content-addressed by the
packet, the trace, every compared window and the analysis code, and each run
writes `STATE-MACHINE.json`, its `STATE-MACHINE.md` and a manifest naming both,
below `.bne-state-machine/`.

The exit code is `1` when a report was produced and `2` when the evidence could
not be read.

## Required evidence

| Side | What it needs | Where it comes from |
|---|---|---|
| Native | at least three cycles of the focused slot's whole record | the divergence packet, which carries `native_state` reconstructed from the sealed fixture |
| Port | the paired unit's per-cycle state | `state.unit` causal events, or the packet's semantic window |
| Counterexamples | other authenticated packets and their slots | `--compare PACKET=SLOT` |

The packet is re-authenticated on the way in: if the sealed fixture it names has
been edited since triage built it, or the two fixture identities it carries
disagree, the run is refused rather than mined.

A window with fewer than three cycles of that slot produces the exact command
that would widen it. It does not produce a confident report over two points.

### The port side

The port writes `state.unit` once per cycle for the unit named by
`CHONKCRAFT_TRACE_BNE_CAUSAL_UNIT`, carrying its order, position, sub-tile residual,
heading, route length, wait, collision counter, order delay, animation and its
wait, the Battle.net timers and sequence, target, hit points, its next queued
order and what it is carrying. It reads state
the engine already keeps and changes none of it, and an ordinary game -- which
names no trace file and no unit -- returns before doing anything.

Without a rerun the packet's semantic window still supplies position, hit points
and order, so a packet alone is enough for a report. It is a thinner port side
and the confidence grades reflect that.

## Reading a report

The Markdown leads with the observables that moved, most informative first, and
stops before the rest. A named field that ramps outranks an anonymous byte that
flickered once, and a byte that changes on every cycle of the window is ranked
down because that is usually an animation frame rather than a decision.

### Shapes

A shape describes the numbers and nothing else. Several are true at once: a byte
that climbs by one to eight and drops back to zero is a counter, a ramp and a
reset.

| Shape | What was observed |
|---|---|
| `unit-counter` | every rise is exactly one |
| `monotonic-increase` / `monotonic-decrease` | it only ever climbs, or only ever falls |
| `countdown` | every fall is exactly one |
| `timer-armed` | a jump up of two or more, then at least two single decrements |
| `reset` | a fall back to where it started, after a climb |
| `saturating` | it climbed to a value and held there |
| `periodic` | the window repeats with a period |
| `toggle` | two values, alternating |
| `single-transition` | one change, then it stayed |
| `pointer-like` | four bytes wide, always zero or inside the pinned image |
| `enum-like` | a few values with no order to them |

### Widths and bits

A byte is reported whenever it changes. A wider read is reported only where it
explains something the bytes do not -- a known field of that width, or a low
byte that wrapped on the cycle its neighbour moved, which is a carry and means
the value really is the wider one. A byte is followed bit by bit only when it is
used as flags: every small number touches its low bits, so decomposing a counter
would bury it under four trajectories of its own bits.

### Thresholds and resets

A ramp is paired with whatever changed at the top of it, and the pair is
reported as two observables, the ramp's value where the other one moved, and the
delay. **That is not cause and effect.** A counter reaching eight on the cycle a
route is cleared is a coincidence until some other window fails to repeat it,
which is what `--compare` is for.

A reset is a fall back to the starting value *after a climb*. A value that only
ever drops -- a route cleared, a target lost -- fell once and did not reset;
calling that a reset would invent a counter that was never there.

### Writes and persistence

The fixture records which unit records the engine rewrote, not which bytes it
touched. So a value can be shown to have sat inside a rewritten record without
changing, which is reported as persisting, and that is not the same as proving
the engine wrote that value. Every trajectory carries
`write_granularity: record` to say so.

## Confidence grades

The report puts a native offset beside a port field and grades the pairing. The
grades never collapse into each other.

| Grade | What it means |
|---|---|
| `proved` | a mapping supplied to the tool from a real proof. Never derived here. |
| `strong-temporal-correlation` | exactly one port field changed on exactly those cycles and carried exactly those values |
| `speculative-candidate` | the cycles line up but the values do not, or more than one port field fits equally |
| `no-java-counterpart` | nothing in the port moved on those cycles |

A native observable that no single port field fits, but that two port fields
together change on exactly the cycles of, is reported with a
`combination_candidate` and stays a speculative candidate. Two fields covering
the right cycles is a weaker statement than one field carrying the right
values, and it is offered because an invented flag and a pair of real fields
look identical until somebody looks.

### Why temporal correlation is not semantic proof

Two things that change on the same cycles may be the same thing, or may both be
downstream of a third. From timing alone there is no way to tell, and the
history of this port has examples of both. A `strong-temporal-correlation` says
the pairing is the only one that fits *this window*; it does not say the native
offset holds that quantity, and it never authorizes naming it in source. What
promotes a pairing is a proof from elsewhere -- a branch witness, a semantic
slice -- fed back in as `proved`.

The two engines also number their cycles from different places, so the offset
between them is measured from the positions they both report and then used. When
the offset is ambiguous the report says so, and every grade below it should be
read as weaker still.

## Counterexamples

The focused finding is written down as a rule small enough for another window to
answer: this observable climbs, and at this value that one changes. Every window
supplied with `--compare` returns one of three answers.

| Verdict | Meaning |
|---|---|
| `supports` | the ramp reached the same value and the consequence changed there |
| `contradicts` | the consequence fired at a different value, or never fired, or fired without the ramp |
| `not-applicable` | the observable never moved, so this window never tested the rule |

`not-applicable` exists because counting an untested window as support is how an
overbroad rule looks confirmed by fixtures that had nothing to say about it.

Counterexamples are ranked by how few observables they behave differently in. A
case that behaves almost identically names the one condition the rule was
missing; a case that shares nothing only says the two are different games.

## Proposed-state audit

`--proposed-state FILE` takes a small JSON description of a Java state field:

```json
{
  "name": "battleNetSettleCounter",
  "java_field": "collision",
  "lifetime_cycles": 5,
  "reset_when": "route cleared"
}
```

and reports what native evidence exists for it. It never rejects an
implementation and never edits one.

A different name is not a finding. A port matches behaviour, not names, and a
Java field called something the disassembly never called it is ordinary. What
produces a warning:

| Code | Raised when |
|---|---|
| `no-native-correlate` | nothing in the native record was shown to move with it |
| `native-correlate-speculative` | the only match is a speculative candidate |
| `outlives-native-state` | it is held longer than the native observable stays away from its starting value |
| `reset-condition-unstated` | the native observable resets in this window and the proposal states no reset |
| `named-after-a-fixture` | the name carries a mission, a level or a cycle number |
| `named-as-a-workaround` | the name says workaround, hack or special |
| `contradicted-by-counterexample` | a compared window already breaks the rule the state encodes |

The audit does not detect a proposed field that stands for a *combination* of
native fields; the correlation table's `combination_candidate` is the only
hint of that shape, and it is a hint.

### Limitations

The audit sees one window. A flag with no correlate here may have one two
cycles outside it, and `no-native-correlate` means *not shown*, not *absent*. It
matches a proposal to a port field by name, so a proposal that names a field the
port does not have is graded as having no correlate for that reason alone. The
naming heuristics are shallow by design -- they catch a mission number, not a
subtly symptom-shaped name -- and a clean audit is not approval. The full
regression gate remains the acceptance authority.

## When the lab runs this by itself

The parity lab mines the focused unit's window when its shape asks for it, and
otherwise leaves the case alone:

- something accumulating for three or more cycles before an action;
- a timer armed after a transition;
- a consequence landing more than one cycle after a ramp peaked;
- a value repeating or toggling until a transition finally takes;
- the unit standing still while its hidden state moves;
- the port holding its order and route length unchanged across a window in
  which the native record does not.

Those signals ignore position and hit points on purpose: a unit walking east has
a position that climbs by one for six cycles, and this tool exists for the state
the ordinary comparison cannot see. When they fire, the case receives
`STATE-MACHINE.json` and `STATE-MACHINE.md` and the ranked plan gains a
`hidden-state-machine` hypothesis. When they do not, it receives neither.
