# Fresh-context Grok goal

Copy the block below into Grok from
`/Users/chrisk/Documents/source/wargus-java`.

```text
/goal Make ChonkCraft the most faithful, responsive and genuinely playable recreation of Warcraft II Battle.net Edition 2.02b. Work continuously in evidence-driven rounds until ALL THREE authenticated next-level lanes are certified: (1) physical player transactions, selection/group fan-out and command outcomes; (2) native AI decisions through combat/projectile/effect outcomes; and (3) all 52 campaign lifecycles / 137 trigger programs including save-resume. Do not stop merely because one slice is difficult, a hypothesis fails, a context checkpoint is due, or a useful commit lands. Rerank and continue. The task is complete only when scripts/check-bne-next-level-gate.sh --require-certified passes from the exact final engine input, the full relevant regression/playability/determinism gates pass with zero unexplained skips, durable docs are current, and clean local commits contain the work. Do not push.

Authority and scope:
- Work only in /Users/chrisk/Documents/source/wargus-java on the current branch and preserve pre-existing changes. Inspect status, HEAD and diff before editing. Never reset or discard another agent's work.
- The sole behavior authority is the pinned English retail Warcraft II Battle.net Edition 2.02b executable, 712704 bytes, SHA-256 b0e914a9cb7dcc81a205e700a9bb0a1d0649df19d459388051ba170783d2c807.
- Use the authenticated ChonkPack at $HOME/.chonkcraft/packs/warcraft-ii-battle-net-edition-usa.chonkpack and i9beef for native capture. Begin with `python3 tools/bne-harness/scripts/bne_java.py doctor --need capture`; a root-owned/unreadable fixture is NOT READY.
- Read completely before work: tools/bne-harness/NEXT_LEVEL_PARITY.md, PARITY.md current checkpoint/method, parity-lab-policy.json, AI_DECISION_LEDGER.md, player-transaction-requirements.json, combat-lifecycle-requirements.json, and the latest generated status plus player worklist after running the gate.

Startup proof:
1. Record git status/HEAD and run doctor.
2. Run `scripts/check-bne-next-level-gate.sh`. This regenerates evidence; do not use the historical checked-in command split as current proof.
3. Run `scripts/capture-bne-ai-cycle.sh` and feed its native.json/java.json into the unified gate. Verify `state_identical` separately from telemetry; never change engine behavior to fix an instrumentation-only mismatch.
4. Read the five current substantive command debts and all missing generated/native cells. Treat 126/131 comparable as the starting measurement, not 126/240 completion.

Perpetual three-lane round:
A. PLAYER TRANSACTIONS — close the highest-volume upstream missing or divergent cell from physical gesture -> ordered selection -> target interpretation -> exact wire fan-out -> acknowledgement/refusal -> first progress -> terminal outcome. Extend native UI-handler capture and the 27 InSight replay bridge. Prioritize authenticated group selections (1/3/9 mixed units), Shift queues, minimap/shore/tree/water/building target shapes, attack-move, stand-ground and production. Advance replay execution beyond its first unresolved identity instead of claiming the indexed tail ran. A generated or Java-only row is not native parity.
B. AI/COMBAT — compare per-cycle normalized AI.BIN state using scripts/capture-bne-ai-cycle.sh. Expand 12 -> 200 -> 1800 cycles and across all active computer players/missions. Add native predicate/write/launch hooks where telemetry is missing. Fix the first independent causal difference, then prove the full acquire/chase/swing/projectile/damage/RNG/retaliation/death transaction across melee, ranged, siege, tower, naval, air and stance matrices. Use the projectile/visual lifecycle tools; never patch a downstream visual symptom while an earlier decision/order/movement field differs.
C. CAMPAIGN — turn the 52-mission/137-trigger inventory into pinned-native lifecycle witnesses. Compare predicate transition, action, deciding cycle, armed set, flags, delays, diplomacy and outcome. For SET_FLAG, DELAYED_VICTORY and DIPLOMACY fork continuous vs save/reload at multiple cycles. Build authenticated victory and defeat journeys for every mission through the released app/pack. Generated inventory is not proof.

Evidence and implementation rules:
- Choose work by fixed-denominator fleet gain, player visibility, frequency and upstream dependency. Earliest divergence diagnoses a chosen family only; it never chooses the global task.
- Before an engine edit, write one falsifiable BNE rule and identify two independent positive witnesses plus one held-out/negative witness, unless unconditionally transcribed from the pinned binary.
- No fixture IDs, mission names, unit IDs, profile-ID symptoms, fitted waits/cycles, or branches that merely move one case later. No speculative RNG burns or state clamps.
- Add efficacy tests that fail without the candidate. Run focused proof first, then the affected fixed-denominator lane, then the full regression/no-loss gate. Never regenerate sealed evidence to make Java pass.
- Missing native execution, stale identity, skipped oracle, truncated replay, absent player-cycle, reduced denominator, ambiguous unit pairing and adapter failure are visible debt, not exactness.
- Preserve a frozen native/sample universe: missing or earlier-disappearing expected units count as disagreement/coverage loss; never let the denominator shrink.
- After two consecutive no-gain hypotheses rerank. After three failed implementations switch to another lane and return with new evidence. Keep useful diagnostics/tooling; revert ineffective engine experiments. Do not sit idle waiting for user input when another safe in-scope cell exists.
- At each accepted systemic slice: update the retained receipt/worklist, add a concise durable finding including failed hypotheses, run docs check, and make a focused local commit. Do not push.

Continue rounds indefinitely until the explicit completion condition is proved. If context becomes tight, write a precise current-head checkpoint with measurements, commands, evidence identities, failed experiments and the next executable command, compact context, and immediately continue; a checkpoint is not completion.
```
