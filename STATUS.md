# Project status

ChonkCraft is a playable public beta. Campaigns, skirmishes, combat, economy,
construction, fog of war, computer opponents, spells, upgrades, sound, music,
save and load, and lockstep multiplayer are implemented. All 52 campaign
missions load and run from an authenticated ChonkPack built from original game
media supplied by the player.

## Measured Battle.net Edition parity

The current release candidate has been compared with Warcraft II: Battle.net
Edition 2.02b across the complete 52-map authenticated campaign fleet:

- **52/52 maps are exact through cycle 245.** The lowest common divergence is
  cycle 246, so the shared proven frontier is cycle 245.
- Expansion Human 11 and 12 tie at cycle 246 through two independent seams: a
  resource worker's claimed-tree reassignment and a grunt's crowded combat
  route choice.
- **24/52 maps are exact through the full 400-cycle window**, with 28 later
  divergences and no execution failures. The sum of all per-map exact
  frontiers, capped at 400, is 17,542 cycles, up 110 from the preceding
  accepted candidate's 17,432; no map moved backward.
- **8/52 maps are exact through the full 1,800-cycle window**, with 44 later
  divergences and no execution failures. Their all-map exact-frontier sum is
  31,828, up 110 from the preceding accepted candidate's 31,718.
  The eight are Human 1, 2, and 9; Orc 1, 2, and 6; and expansion Orc 1 and 9.

"Tested through cycle 400" or "through cycle 1,800" describes coverage, not a
claim that every map is exact for that whole window. The common frontier is
always one cycle before the earliest authenticated mismatch across the fleet.

## Playability and release gates

The current player-contract receipt certifies **17/17 playability lanes** with
zero failures, skips, blocks, or timeouts. It covers authenticated boot and
assets, deterministic scheduling, movement, orders, economy, construction,
combat, projectiles, naval oil, spells, retail AI, campaign triggers,
save/load, rendering and input, sound, and clean/adverse network lockstep.

The complete suite now contains **2,751 tests**. The no-data profile ran 1,598
and intentionally skipped 1,153, with the exact expected 89-test specification
failure set. The canonical authenticated contract discovers all 2,751 with 26
intentional skips; current-head certification is held open until the private
authenticated CI runner completes.

Source-boundary, native-runtime, comment-provenance, documentation, and BNE
readiness checks pass. Signed engine OTA `2026.0825.66` remains the public
release while this candidate completes certification. No matchmaking protocol,
service, or infrastructure path changed, so no server rollout is planned.

## Broader fidelity frontier

These denominators are deliberately strict and remain open work rather than
being inferred from the playable campaign fleet:

- Commanded scenarios: **198/240 exact**, **206/240 comparable**, eight
  materially divergent, zero infrastructure failures, and 34 not yet
  comparable.
- Physical player transactions: **0/532** paired native/Java certifications.
- Replay twin: **0/764,756** dispatcher records in a complete 27-replay paired
  certification.
- AI fleet: **0/52** current-head mission twins; a retained legacy diagnostic
  window records 12 differences across 93,600 scenario-cycles but is not a
  current complete-fleet proof.
- Combat lifecycle: **0/185** accepted cells. Campaign lifecycle: **0/137**
  accepted trigger twins.
- Field parity has no accepted paired report yet.

The zero numerators do not mean those game systems are absent. The 52-map
`semantic-v1` survey and the playability lanes do not emit these proof types.
Each lane accepts only a complete native/Java twin report bound to the current
engine and program identity, and it fails closed rather than carrying forward
partial, detached, legacy, or stale receipts.

The source of truth is executable evidence: the enforced test profiles, the
17-lane playability receipt, the authenticated retail comparison harness under
`tools/bne-harness/`, packaged-launcher checks, and focused regression tests
beside the behavior they protect.
