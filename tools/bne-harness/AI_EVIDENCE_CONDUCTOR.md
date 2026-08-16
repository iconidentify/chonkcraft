# AI evidence conductor

The AI conductor turns the remote `i9beef` capture lake into a durable,
content-addressed comparison queue. It does not choose work from prose or a
remembered campaign cycle. It authenticates the pinned native run, normalizes
the native `AIPlayerState` stream beside the remote trace, runs the same map
and seed through the current Java app, and ranks the first causal mismatch.

## Safe operating modes

Discovery is the default and is read-only:

```sh
python3 tools/bne-harness/scripts/bne_java.py ai-conductor
```

It validates every discovered manifest, selects the longest capture for each
scenario/seed, and prints the candidates. It does not start or stop a container,
copy a trace, build Java, or write an evidence store. `capture-bne-ai-cycle.sh`
remains the explicit producer when a new native capture is actually needed.

The checked-in `ai-fleet-requirements.json` is the deterministic 52-mission
contract: 14 Human, 14 Orc, 12 Beyond the Dark Portal Human, and 12 Beyond the
Dark Portal Orc scenarios, each at seed 1 through cycle 1,800. Discovery also
prints existing/missing scenario coverage and an exact command for every
missing capture. Those commands are a plan only; they all take the same remote
`flock` lease, recommend one worker, cap an operator at two queued jobs, and do
not manage unrelated containers. After materialization, `FLEET.json` and
`FLEET.md` add the active computer players independently decoded from every
cycle's authenticated `state.bin` controller table. AI trace rows never define
their own denominator.

Materialization must be requested. Start with one case and one worker:

```sh
python3 tools/bne-harness/scripts/bne_java.py ai-conductor \
  --materialize --case ai-orc01-1800 --limit 1 --jobs 1
```

`--jobs` is capped at two. A local advisory lease prevents two materializers
from using the same store at once. SSH work is read-only: Python validates and
normalizes the trace in the remote process, receiving authenticated `ai.bin`
bytes on standard input and returning JSON on standard output. It never writes
the oracle, changes Docker state, or kills unrelated containers.

The Java adapter selects the map's actual `PERSON` slot (`GameData.personIn`),
including expansion missions where that slot is not zero. Its output binds the
selected person, every computer player, map, seed, and cycle limit. The
conductor then stamps the pack's pinned `ai.bin` identity onto that ledger
before the twin is retained, so a later checkout cannot claim a different
program. A native state roster and Java roster disagreement fails before field
comparison.

`--skip-build` is fail-closed. A successful conductor build writes a receipt
beside the app JAR. Reuse is allowed only while the generic engine closure,
desktop adapter source, desktop POM, JBR wrapper, and JAR bytes still match that
receipt. A stale target JAR is an infrastructure error, never parity evidence.

Cleanup is intentionally report-only:

```sh
python3 tools/bne-harness/scripts/bne_java.py ai-conductor --gc-dry-run
```

There is no delete mode. Review any reported content before removing it by an
explicit, separately authorized operation.

## Authentication and retention

Every imported object must prove all of the following before a byte is kept:

- manifest schema 2 and the pinned BNE 2.02b executable identity;
- matching fixture, requested scenario, observed scenario, seed, and cycle
  limit (Windows path case is normalized, not silently changed);
- the fixture ID recomputed from its canonical key, offline runtime, idle
  command/replay contract, oracle data, tracer, simulation, and state schema;
- manifest, trace, and state basenames, byte counts, and SHA-256 identities
  reverified together on `i9beef` immediately before normalization;
- strict `BNESTATE` 1.1 validation plus one unchanged controller-derived
  computer roster for the whole sealed window;
- normalized ledger schema and authority identity;
- the exact retail BNE `ai.bin` identity used by the normalizer
  (`407811fa…e911`, 22,377 bytes); and
- exactly every cycle from 1 through the sealed limit for every state-declared
  computer player, with no missing or extra native row.

The store retains only:

```text
.bne-ai-evidence/
  objects/<native-content-sha>/
    manifest.json
    native-ledger.json
    SOURCE.json
  twins/<java-proof-sha>/<native-content-sha>/
    java-ledger.json
    comparison.json
    TWIN.json
    RUN.json
  CATALOG.json
  FLEET.json
  FLEET.md
  NEXT.json
  NEXT.md
```

It does **not** retain the trace, fixture, state image, `ai.bin`, or other large
remote evidence. Native content is shared across engine versions. The Java
proof identity covers the engine closure, desktop AI adapter, desktop POM, JBR
wrapper, whole ChonkPack bytes, extracted `ai.bin`, built app JAR, and verified
build receipt. Each child twin then binds that proof to its scenario, person
slot, computer roster, seed, and cycle limit. An older checkout, stale JAR,
different pack, or wrong campaign seat cannot masquerade as current evidence.

Retained evidence is never trusted from `RUN.json` or `NEXT.json` alone. Verify
the complete store before handing its report to the next-level gate:

```sh
python3 tools/bne-harness/scripts/bne_java.py ai-conductor --validate-store
```

Validation is read-only and fail-closed. It recomputes the current source/build
inputs, JAR, whole-pack and pinned `ai.bin` proof namespace, then walks every
current-proof twin directory. For each run it requires the manifest, `SOURCE`,
native ledger, `TWIN`, Java ledger, comparison and `RUN` to be regular files in
their exact content-addressed parents. It recomputes all byte identities, the
native fixed denominator, Java seat/roster choices, the comparison, the full
`RUN`, `CATALOG`, summary, ranked findings and canonical 52-scenario
certification. Missing companions, symlink/path substitution, a stale JAR,
tampered ledger/comparison, a forged green count or a detached report is an
infrastructure error. The callable gate surface is
`bne_ai_conductor.validate_retained_report(...)` (or
`validate_retained_store(...)` when loading `NEXT.json` from disk).

## Fixed-denominator acceptance

For one capture the denominator is:

```text
state-declared computer players × every sealed cycle
```

The Java side cannot improve its score by omitting a player or a hard cycle.
A missing Java row is `AI lifecycle` debt. An extra Java computer row is also
material debt even though it is outside the native denominator. Each paired
row compares profile, wait, normalized PC/list/threshold pointers and every
non-pointer state byte. Predicate, write, launch and independent-choice hooks
are reported separately as causal telemetry.

An imported window is committed-state exact only when:

1. native coverage is complete for its fixed denominator;
2. Java has exactly the same player/cycle key set;
3. every committed state field is exact; and
4. the Java twin's full build/pack/player proof identity is current.

Full causal exactness additionally requires exact predicate, write, launch,
and classification telemetry. The conductor itself returns GREEN only when
all **52/52** required scenarios are materialized under one current certifiable
Java proof and all 52 are both committed-state and causal-telemetry exact
through exactly cycle 1,800. Duplicate or non-canonical campaign scenarios do
not count toward that bar. A one-case smoke may be 100% exact and still
correctly return nonzero because it is not fleet certification.

`NEXT.json` and `NEXT.md` rank lifecycle, campaign
profile, script control-flow, production list, force threshold, cadence, state,
predicate, mutation, and force-launch debt in that upstream order. Grok should
start from the first ranked causal frontier, not from the first visual symptom.
