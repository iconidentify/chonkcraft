# Long-running Battle.net Edition parity goal

This file is the durable prompt for a persistent Codex goal. It defines how to
continue ChonkCraft's evidence-backed parity work against Warcraft II
Battle.net Edition 2.02b without relying on chat history. The repository is the
handoff boundary.

## Objective

Persistently improve the native Java engine's behavioral parity with the
authenticated Warcraft II Battle.net Edition 2.02b reference. Deliver small,
systemic, evidence-backed fixes that measurably improve the fixed corpus and do
not regress already accepted behavior. Continue across goal turns and context
compactions until the completion conditions below are genuinely satisfied or
progress is genuinely blocked under the blocking rules below.

The latest durable handoff frontier as of 2026-09-02 is:

- the accepted candidate is based on commit `e9dbd9a` and is authenticated by
  replayable dirty-source capsule
  `fd42bb4f1ce878a87fa70924cefd5794d02c7fea36abc2f96aa3d964415724cd`;
- all 52 campaign fixtures exact through cycle 332;
- expansion Human 12 is the first shared-boundary divergence at cycle 333; and
- the accepted 52-case receipt is
  `8b9f364b41e7253e0bee8f0e81b9ebb8228e3f17da36de37586457ae38811ffd`.

Expansion Human 12 cycle 333 remains a real frontier, but its route family is
paused after three rejected implementations. Native Branch Witness confirms
that the replacement route is emitted by ordinary `NewPath` global scratch
and copied into the unit route buffer at `0x004505ed`; it has not yet exposed a
safe systemic discriminator. Resume that family only with new evidence about
the global route-scratch lifecycle or path decision. Expansion Orc 8's
cycle-356 submarine route-publication family is independently paused. The
fleet remains 13 clean / 39 divergent / 0 failed through cycle 1,800, with a
52-case exact-prefix sum of 52,173. Retail Human 8 is accepted through cycle
526 after an ordinary completed moving-quarry Attack body released a lagging
renderer wait and consumed its fresh south-east route on fixture 514. The
earliest unpaused fleet finding is expansion Human 10 cycle 519, followed by
Human 13 at 523, Human 8 at 527, expansion Human 5 at 530, Orc 12 at 531, and
expansion Human 2 at 533.

This snapshot is orientation, not an instruction to overwrite newer results.
On every resumption, derive the actual frontier from the current repository and
authenticated accepted receipt.

## Authoritative sources, in order

1. Read the topmost **Current release checkpoint** in
   `tools/bne-harness/PARITY.md`. Its newer evidence supersedes older historical
   sections and the stale ignored file `goal/scratch/checkpoint-live.md`.
2. Read `.bne-artifacts/latest-accepted.json` and the retained receipt it
   names, when present. Authenticate inputs rather than trusting a detached
   summary.
3. Read the relevant operating contracts before using their lane or tool:
   `PARITY_LAB.md`, `ACCELERATION_GATES.md`, `FRONTIER_EVIDENCE.md`,
   `PORT_MECHANISMS.md`, `CORPUS.md`, `FIXTURE.md`, `LAYOUT.md`, and the
   specialized ledger/bridge document selected by the evidence router.
4. Use `NEXT_LEVEL_PARITY.md` and its generated scorecard for the systemic
   player-transaction, AI/combat, and campaign-lifecycle program. Do not revive
   old count-only headlines or treat coverage as authenticated parity.
5. Use current source, tests, and authenticated raw/retained evidence. Chat
   recollections, screenshots, stale scratch notes, copied summaries, and
   unauthenticated generated output are not proof.

Keep context focused. Do not repeatedly load all historical checkpoints in
`PARITY.md`; use the current checkpoint, operating sections, mechanism index,
and only the history relevant to the selected behavior.

## Resume procedure

At the start of each fresh goal turn or after compaction:

1. Read this file and the topmost current checkpoint in `PARITY.md`.
2. Run `git status --short --branch` and inspect recent commits. Preserve all
   user or unrelated work; never reset, clean, or overwrite it.
3. Query the active goal state when useful, but do not create a second goal.
4. Run the read-only capability doctor:

   ```sh
   python3 tools/bne-harness/scripts/bne_java.py doctor
   python3 tools/bne-harness/scripts/bne_java.py doctor --need capture
   ```

5. Locate and authenticate the current accepted receipt. Prefer
   `frontier-compile`, `triage`, `autopilot`, and the evidence router over
   manually rebuilding orientation already encoded by the lab.
6. State the current acceptance floor, chosen disagreement family, hypothesis,
   discriminating witnesses, and pass/fail bar before editing behavior.

Machine-local facts confirmed after transfer:

- Codex is running directly on `i9beef`; this machine is the native-oracle
  host, so local native capture does not require SSH;
- the authenticated asset pack is available under
  `/home/chrisk/.chonkcraft/packs/`;
- the local oracle tree and 52-fixture corpus are under
  `/home/chrisk/.local/share/wargus-bne-oracle`;
- the pinned BNE executable and both local Docker oracle images are present;
- local static analysis, native capture, and local Branch Witness are usable.

Rediscover these facts through the doctor rather than assuming paths remain
valid. An unavailable remote does not block offline fixture comparison.

## Work selection

The shared frontier is an acceptance invariant and a diagnostic entry point,
not permission to fit a special case.

1. Compile the accepted proof and inspect all tied earliest blockers.
2. Measure the fleet and rank disagreement families by volume and dependency.
   Prefer the most upstream causal family whose correction can explain several
   observations.
3. Use the earliest exact divergence within that family as a microscope.
4. Price hypotheses with diagnostics or offline predicates before changing
   behavior whenever possible.
5. After two evidence-backed hypotheses produce no gain, rerank. After three
   failed implementations in one family, switch to another systemic lane and
   return only with new evidence.
6. Continue rotating productive player-transaction, AI/combat, and campaign
   lifecycle work rather than spending the run indefinitely on one uncertain
   unit.

The next work selection should normally start from the earliest unpaused fleet
finding, currently expansion Human 10 cycle 519, while retaining expansion
Human 12 cycle 333 as the shared-boundary frontier. Return to either paused
route family only when a new native discriminator or a more upstream
authenticated finding can price a systemic hypothesis.

## Evidence and implementation loop

For each candidate behavior:

1. Establish the exact native/Java split on an authenticated fixture and short
   window. Pair units by stable identity, not pool order or coincidental IDs.
2. Anchor fixture-cycle versus internal-cycle offsets on an event both sides
   record. State the measured offset before interpreting probe timing.
3. Ask sealed fixture state and existing retained evidence before the screen or
   a new capture. Check `PORT_MECHANISMS.md` before implementing a mechanism
   that may already exist.
4. Use the cheapest sufficient evidence route: packet/cadence/semantic bridge,
   causal or RNG ledger, static analysis, micro-oracle, Branch Witness, and only
   then a targeted new native capture. A native trace should answer one
   question.
5. Derive a binary- or fixture-backed behavioral rule and identify at least one
   positive witness and meaningful negative/held-out witnesses.
6. Add a focused efficacy regression that fails on the pre-fix implementation
   for the intended reason and passes with the fix. Use `test-efficacy` when
   practical; reject tests that pass both versions.
7. Implement the smallest systemic rule. BNE-specific behavior belongs behind
   the Battle.net profile unless independently proved correct for the ordinary
   engine.
8. Rerun the focused proof, the exact fixture/window, the relevant fixed-
   denominator family proof, and the global regression appropriate to the
   change.
9. Keep the change only when the mechanism is explained, measured agreement
   improves, and no accepted case moves earlier. Revert only the goal's own
   failed experiment; never revert unrelated work.

Never add mission, map, faction, unit-ID, coordinate, route-length, exact-cycle,
or fixture-specific branches to make a trace pass. Prefer explicit native
concepts and state. Do not infer a general timer or rule from one fitted
interval. Do not accept compensating RNG errors: align synchronized and
asynchronous call chains at the same native boundary whenever RNG is touched.

## Acceptance ladder

Scale verification in proportion to the change so feedback remains fast:

1. focused unit/real-data tests and the selected short case;
2. efficacy proof against the pre-fix implementation;
3. relevant family/contract gates;
4. a 52-case survey at the inherited short horizon, gated against the last
   authenticated acceptance;
5. longer lookahead only after the short proof is explained; and
6. the full cycle-400 and cycle-1,800 fleet plus release/contract gates at a
   coherent milestone.

Acceptance is fail-closed. A missing case, infrastructure failure, shorter run,
changed unbound input, earlier first divergence, dense-score loss, or detached
summary is not a pass. Do not promote partial or dirty evidence as a clean
release checkpoint. Use source capsules and engine-input identities exactly as
described in `FRONTIER_EVIDENCE.md`.

At milestone scale, preserve the repository's established gates, including
the player-contract lanes, clean and adverse lockstep cases, real two-process
match, pack/provenance checks, and matched BNE-media suite when applicable.
Use the commands and expected counts from the current checkpoint and scripts,
not hard-coded historical counts in this prompt.

## Checkpoints and commits

Make the work durable as it progresses:

- Keep diagnostic traces, proprietary fixtures/assets, build output, and
  content-addressed run artifacts in their documented ignored locations. Never
  commit or redistribute retail game bytes.
- Put lasting binary layout facts in `LAYOUT.md`, rejected hypotheses in the
  lab's durable failure history, and reusable mechanism knowledge in the
  appropriate tracked document or source comment.
- After each accepted coherent fix, update the topmost current release
  checkpoint in `PARITY.md` with the behavior, witnesses, exact before/after
  frontier, receipt identities, tests actually run, and next blocker.
- Update other user-facing status/release documentation when the established
  repository workflow requires it.
- Review the diff and commit coherent accepted source, tests, and handoff
  documentation locally. Follow the repository's existing concise commit style.
- Do not push, publish, open a PR, rewrite public history, or alter remotes by
  default. A push is allowed only when the user explicitly requests that exact
  external action; verify the destination and preserve unrelated work first.
- Do not amend an existing commit unless the user explicitly requests it.
- Leave unrelated dirty work untouched and out of goal commits.

Do not claim progress solely from added diagnostics, broader coverage, test
count changes, or a later visible symptom. Report authenticated behavioral
movement and its fixed denominator.

## Safety and external-state boundaries

- Native BNE assets and captures are user-owned evidence. Never copy them into
  Git, expose their contents unnecessarily, or regenerate a fixture merely to
  make Java pass.
- Native binary inspection and diagnostic execution must remain read-only or
  use the documented guarded harness. Validate the pinned executable hash.
- Do not modify `~/.ssh/known_hosts`, disable strict host-key checking, or
  accept changed remote keys without explicit user verification. Native-oracle
  work on local `i9beef` does not require SSH.
- Do not install system packages, change machine-wide configuration, publish
  artifacts, or perform destructive Git/filesystem operations without explicit
  user authority.
- Use local native capture, static analysis, Docker/Branch Witness, and the
  sealed local corpus on `i9beef`.

## Persistence and blocking

Keep making useful in-scope progress through failed hypotheses, long tests,
context compactions, and optional remote outages. A hard investigation is not
a blocker. While one route is unavailable, use retained fixtures, static
analysis, local oracle tooling, other disagreement families, documentation,
or verification work that advances the objective.

Only mark the persistent goal blocked when the same external condition has
prevented all meaningful in-scope progress for at least three consecutive goal
turns and no safe alternative remains. Record the exact attempted commands,
evidence, and the smallest user action needed. Do not invent an SSH dependency
for native evidence that is already available locally on `i9beef`.

## Completion conditions

Do not mark this goal complete after one fix, one frontier advance, a clean
short horizon, or an exhausted context window. Completion requires all of the
following on the then-current committed tree:

1. the authenticated 52-campaign fleet is exact through the full 1,800-cycle
   corpus with zero failed or regressed cases;
2. `scripts/check-bne-next-level-gate.sh --require-certified` succeeds from
   retained producer evidence for player transactions, AI/combat/effects, and
   campaign lifecycle;
3. all current project, multiplayer/lockstep, pack/provenance, and BNE-media
   milestone gates required by the repository pass;
4. efficacy tests and durable evidence explain the systemic rules that closed
   the remaining divergences; and
5. the top checkpoint, status documentation, and commits form a clean,
   reproducible handoff, with no proprietary evidence committed and external
   publishing performed only when explicitly requested by the user.

If the user explicitly changes the target or stops the run, follow that newer
instruction. Otherwise, continue advancing measured parity.
