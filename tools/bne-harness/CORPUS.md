# BNE corpus workflow

The corpus is the one-time, durable oracle output used by the Java parity
loop. Wine and the retail game are capture dependencies, not test-suite
dependencies. Once a `.bnefx` is sealed and indexed, ordinary engineering can
reconstruct and compare it without launching BNE again.

## Baseline plan

Generate a schema-1 plan covering all 52 built-in campaign maps:

```sh
python3 scripts/bne_corpus.py make-campaign-plan \
  --output work/corpus/campaign-1800-plan.json \
  --cycles 1800 \
  --seed 1
```

Every case has a stable filesystem-safe ID and an explicit scenario, cycle
bound, construction seed, and optional command file. The plan contains no game
files or CD key. Editing any byte changes its recorded identity, so a resumed
index cannot silently drift to a different experiment.

## Capture and resume

```sh
python3 scripts/bne_corpus.py run \
  --plan work/corpus/campaign-1800-plan.json \
  --output-dir work/corpus/campaign-1800 \
  --game-dir "/path/to/Warcraft II BNE" \
  --prefix work/oracle-prefix \
  --source-manifest work/sources/SOURCE_ID/source-manifest.json
```

The runner invokes the strict single-case oracle sequentially. After each
case, it reopens the bundle, verifies every member and state chunk, checks the
fixture's scenario/cycles/seed/commands against the plan, and atomically
updates `corpus-index.json`. An interrupted run resumes at the first case
without a valid sealed fixture. It adopts an already valid bundle, but refuses
partial unsealed outputs rather than deleting or guessing about them.

### Headless capture worker

For long captures, use the Wine/Xvfb container in `docker/` rather than a
desktop Wine session. BNE requires a real 640x480, 8-bit display mode; the
container provides that mode on an unexported X server, has no sound device,
and runs with Docker networking disabled. Its image identity and execution
properties are recorded in every run manifest.

An oracle worker directory contains `harness/`, `game/`, `cd/`, `plans/`,
`output/`, `prefix/`, and `source-manifest.json`. The game and CD directories
come from the user's own retail media and are never copied into the repository
or container image. Build and launch a resumable batch with:

```sh
python3 harness/scripts/bne_headless.py build
python3 harness/scripts/bne_headless.py corpus \
  --oracle-root "$HOME/.local/share/chonkcraft-bne-oracle" \
  --plan campaign-1800-plan.json \
  --output campaign-1800 \
  --detach \
  --name chonkcraft-bne-campaign-1800
```

`docker logs -f chonkcraft-bne-campaign-1800` reports the current case. Completed
fixtures are sealed and indexed before the next BNE process starts, so a host
restart loses at most the active case.

The initial baseline is intentionally idle input. Campaign AI, construction,
resource flow, combat, projectiles, spells, deaths, and map changes still
exercise BNE. Scripted command variants should be separate cases with separate
IDs; their exact command files are embedded in their fixtures.

## Offline validation

```sh
python3 scripts/bne_corpus.py validate \
  --index work/corpus/campaign-1800/corpus-index.json
```

This checks the indexed byte identity, bundle identity, manifest, raw state,
and terminal cycle count for every fixture. It launches nothing and needs no
retail content outside the bundles.

## Java engineering loop

The commands below are the adapter entry points. The evidence standard,
debugging sequence, regression policy, and current handoff checkpoint are in
[`PARITY.md`](PARITY.md); read it before changing engine behavior.

The corpus-aware Java adapter maps each retail BNE campaign scenario to its
ChonkCraft mission, pins the fixture's construction seed and BNE player profile,
produces exactly the fixture's cycle window, and runs the existing
first-divergence comparator. Point it at a BNE-derived chonkpack so campaign
scripts and game data both have explicit identities:

```sh
python3 scripts/bne_java.py survey \
  work/corpus/campaign-1800/corpus-index.json \
  --asset-pack /path/to/bne.chonkpack \
  --source-dir /path/to/chonkcraft \
  --jobs 4
```

For a single fixture:

```sh
python3 scripts/bne_java.py case \
  work/corpus/campaign-1800/cases/retail-human-01-idle.bnefx \
  --asset-pack /path/to/bne.chonkpack \
  --source-dir /path/to/chonkcraft
```

The survey records the Java commit/dirty state and the chonkpack's byte length
and SHA-256. It writes one Java trace and result per case plus
`bne-java-survey.json`. A partial index is refused by default; `--allow-partial`
exists only for deliberate smoke runs.

Each new survey also fingerprints the complete Git workspace (tracked diff plus
untracked files), records build/case wall timings, and stores first-divergence
findings as structured JSON. Same-workspace surveys at different horizons can
be combined into a per-case frontier report:

```sh
python3 scripts/bne_java.py frontier \
  /tmp/bne-survey-h21/bne-java-survey.json \
  /tmp/bne-survey-h22/bne-java-survey.json \
  --json-output /tmp/bne-frontier.json \
  --markdown-output /tmp/bne-frontier.markdown
```

`frontier` refuses to combine unlike engine workspaces or asset sources. Use
the regression gate when comparing a candidate revision against an older
baseline:

```sh
python3 scripts/bne_java.py gate \
  /tmp/bne-candidate/bne-java-survey.json \
  --baseline /tmp/bne-baseline/bne-java-survey.json
```

The same check can run immediately after a survey with
`--baseline-survey /tmp/bne-baseline/bne-java-survey.json`. A candidate passes
when it proves every baseline case clean through at least the same cycle; a
divergence on the following cycle is not a regression. Missing, failed,
shorter, or earlier-divergent cases fail the gate.

Turn one divergent survey record into a self-contained forensic directory
with `packet`:

```sh
python3 scripts/bne_java.py packet \
  /tmp/bne-candidate/bne-java-survey.json \
  --case retail-xhuman-12-idle \
  --output-dir /tmp/bne-packet-xhuman12-c22 \
  --before 5 --radius 4
```

This is an offline operation. It verifies every referenced identity, pairs the
native pool slot with the Java unit ID, reconstructs raw schema-1.1 state over
the cycle window, and emits `README.md`, `packet.json`, plus focused oracle and
Java traces. Successful Java runs retain non-empty stdout/stderr beside their
trace, allowing opt-in path/step diagnostics to be included in a later packet
instead of disappearing into the survey subprocess.

For the normal parity loop, `triage` composes the survey, regression gate,
frontier report, divergence clustering, targeted Java diagnostic rerun, and
final packet into one durable operation:

```sh
python3 scripts/bne_java.py triage \
  work/corpus/campaign-1800/corpus-index.json \
  --baseline-survey /path/to/last-proof.json \
  --asset-pack /path/to/bne.chonkpack \
  --source-dir /path/to/chonkcraft \
  --through 30 --jobs 4
```

The default store is the ignored `.bne-artifacts/` directory at the repository
root. Every request is addressed by a SHA-256 over the engine workspace,
compiled classes when `--skip-build` is used, JVM and external ChonkCraft source,
assets, corpus index, baseline proofs, horizon, and packet settings.
The immutable run directory retains copied baseline/index inputs, all Java
traces, the gate and frontier reports, deterministic heuristic clusters, a
diagnostic rerun of the earliest divergent case, and its evidence packet.
`NEXT.md` is the short agent handoff; `manifest.json` authenticates every
retained file. `latest.json` and `latest-accepted.json` are atomic pointers.

An exact repeated request first reauthenticates the sealed corpus and then
returns the verified cached run. It never overwrites an older attempt. Failed
or interrupted attempts remain available under `attempts/`, while a lock
prevents concurrent writers from racing on the same request. Exit status 0
means the baseline gate passed, 1 means a regression/coverage gate failed, and
2 means a fixture, engine run, diagnostic, or artifact failed.
Every input fingerprint is recomputed before publishing the manifest; if the
engine, compiled classes, assets, baseline, index, JVM, or external ChonkCraft
workspace changes during a run, the mixed attempt is preserved but cannot
become an accepted proof.

Clusters use the first structured mismatch's kind, unit type, field, and
numeric delta. They are planning hints for finding fixes that may clear several
cases, not parity evidence; globally earliest-first work and the full gate
remain mandatory. Increase `--packet-limit` to prepare more than the first
divergent case in the same durable run.

This first adapter tier compares cycle number, synchronized RNG, player banks,
and the semantic unit core. BNE serializes live units by pool slot, which is
not evidence of tick execution order, so pool order is not compared. Schema
1.1's extended player state, projectiles, mutable map state, and decoded raw
unit state remain explicit pending fields in the survey rather than being
silently treated as covered.

An already-produced Java determinism trace can still be compared directly:

```sh
python3 scripts/bne_compare.py \
  work/corpus/campaign-1800/cases/retail-human-01-idle.bnefx \
  /tmp/java-human01.trace.txt \
  --all
```

Both commands validate the complete fixture first. The direct comparator
streams its `trace.txt` to a temporary file and does not retain a second corpus
copy. This makes the BNE side of the loop immutable: Java can be rebuilt and
retraced repeatedly while the authoritative input stays byte-identical.

`bne_java.py autopilot` adds the Parity Lab composition step after this exact
triage operation. It does not weaken or replace the corpus gate. See
[`PARITY_LAB.md`](PARITY_LAB.md) for causal twins, evidence minimization,
coverage memory, function experiments, and candidate tournaments.

## Replay track

The InSight 1.1 `.wir` container is decoded and strictly validated by
`scripts/bne_replay.py`. It preserves the initial BNE snapshot, eight-slot
status vector, network participant index, and every opaque command packet.
All 27 matches in War2.ru Replay Pack 1 parse exactly through their declared
record count and final byte. See [`REPLAYS.md`](REPLAYS.md) for the binary
contract and reproducible inventory workflow.

Replay cases remain disabled in the plan runner until the tracer captures and
injects BNE's internal synchronized-command queue. Replay bytes, their
original source identity, map identity, player metadata, and every raw
dispatch packet must be inputs to the fixture ID. A UI macro or lossy video
must never be mistaken for an authoritative multiplayer oracle.
