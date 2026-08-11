# Native evidence catalog

The router reports that no authenticated native trace exists for a case. That
sentence has been covering at least four different situations:

- nothing was ever captured;
- something was captured and never imported here;
- something is sitting in a local root and matches;
- something is sitting in a local root, looks right, and is not -- wrong seed,
  a window that stops before the open cycle, a build that is no longer pinned.

From outside they all read as "blocked", and the usual response is a recapture.
Sometimes that recapture was unnecessary. Sometimes it was necessary for a
reason nobody could see, and it got run with the same wrong parameter again.

This walks the configured evidence roots, authenticates what it finds, and
classifies each candidate against a stated requirement.

## What it will not do

It never imports, copies, recaptures or edits anything. It is a read-only
index.

It never trusts a file because of its name. Discovery is by manifest: a
`.trace.txt` with no sibling manifest is reported as an orphan and not read,
because the manifest is the only thing that says what a capture is. A
filename-matching scan cannot authenticate evidence.

It walks only configured roots, resolved against the repository. A root that
escapes the repository is refused, as is a manifest that symlinks out of the
root it was found under.

## Classification

Reported worst-first, so a capture is described by its most fundamental
failure rather than an incidental one:

| Class | Meaning |
| --- | --- |
| `reusable` | authenticated, and it answers this question |
| `authenticated-but-insufficient-cycle-coverage` | right run, stops too early |
| `authenticated-but-wrong-diagnostic-purpose` | right run, wrong kind of capture |
| `wrong-fixture-case-seed-or-scenario` | a capture of something else |
| `stale-executable` | not the pinned BNE 2.02b build |
| `unauthenticated` | bytes changed, artifact absent, or not captured offline |
| `malformed` | the manifest cannot be understood |
| `not-a-capture` | a harness tool's own run manifest, not evidence at all |
| `missing` | no capture found under the configured roots |

Identity is checked before purpose and before coverage. A capture of Human 13
offered against an XHuman 10 question is reported as the wrong case, not as
one that needs more cycles -- capturing more cycles of Human 13 would not help,
and saying so would send someone to do exactly that.

`not-a-capture` is excluded from the verdict. The evidence roots contain the
content-addressed run manifests of other harness tools, and counting those as
rejected captures would bury the real ones.

Exit status is 0 when something is reusable, 1 when evidence exists but none of
it is usable, and 2 when nothing was found. The 1-versus-2 split is the point:
"found but unusable" carries a reason a recapture must change, and "missing"
does not.

## Running it

```sh
python3 scripts/bne_java.py evidence-index \
  --case retail-xhuman-10-idle \
  --profile async-rng \
  --through 51
```

`--evidence-root` is repeatable and repository-relative; it defaults to
`.bne-lab/native`, `.bne-branch-witness` and `.bne-decision-miner`. Each run
writes `EVIDENCE-CATALOG.json`, `EVIDENCE-CATALOG.md` and a manifest beneath a
content-addressed run root in `.bne-evidence-catalog/`, and re-verifies every
artifact's bytes before serving a repeat request from cache.

## When nothing is reusable: the capture plan

A blocked native lane used to end in `doctor --need capture` and the sentence
"then capture this case around cycle N on the documented remote oracle". That
sentence is where the invocation gets rebuilt from shell history, and where a
capture gets run against a remembered seed.

`capture-plan` compiles the recipe instead:

```sh
python3 scripts/bne_java.py capture-plan \
  --case retail-xhuman-10-idle --profile async-rng --through 51 \
  --index work/corpus/campaign-1800/corpus-index.json
```

The scenario, seed and fixture identity are read from the sealed corpus index,
not supplied by hand. The capture command is `bne_headless.py`, the harness's
own capture entry point, which builds the container invocation itself -- so no
plan contains a hand-written Docker line, an image path or a credential, and a
test asserts that. Every generated command is checked against the real
`bne_headless` parser, and the follow-up `evidence-index` command against the
real `bne_java` parser.

It is a dry run and has no other mode.

Two things that sweep caught, which are worth recording because both read
plausibly and neither works:

- there is no `--trace-random` flag. The tracer installs its sync-RNG and async
  hooks unconditionally at attach, so a plain `run` already carries the draw
  ledger.
- `decision-capture` takes `--phase`, not `--field`, and records one activation
  phase per run. A decision capture is therefore three commands, which is why
  the sealed inventory holds accepted/rejected/heldout triples. Printing one
  would leave two thirds of the evidence uncaptured.

An unsupported profile fails explicitly and says which profiles exist. A
profile that focuses on one unit refuses to run without `--native-unit` rather
than defaulting it to 0, which is what the router used to print.

## What the router does with all this

Before a native lane is called blocked, the frontier compiler asks the catalog
what evidence that case already has and hands the verdict to the router. The
work order now separates:

- reusable evidence found, and the concrete command populated with its path;
- evidence exists but is not usable, with the catalog's precise reason;
- no capture of this case exists in any configured root;

and in the last two cases the recovery is a `capture-plan` invocation rather
than prose. A catalog that cannot run does not fail the compile; it leaves the
router exactly where it was.
