# Project status

ChonkCraft is a playable public beta. Campaigns, skirmishes, combat, economy,
construction, fog of war, computer opponents, spells, upgrades, sound, music,
save and load, and lockstep multiplayer are implemented. All 52 campaign
missions load and run from an authenticated ChonkPack built from original game
media supplied by the player.

## Measured Battle.net Edition parity

The current release candidate has been compared with Warcraft II: Battle.net
Edition 2.02b across the complete 52-map authenticated campaign fleet:

- **52/52 maps are exact through cycle 216.** The lowest common divergence is
  cycle 217, so the shared proven frontier is cycle 216.
- Four maps tie at cycle 217: Human 13 (critter order), Orc 11 (worker
  position), XHuman 12 (footman position), and XOrc 11 (destroyer hit points).
- **21/52 maps are exact through the full 400-cycle window**, with 31 later
  divergences and no execution failures. The sum of all per-map exact
  frontiers is 16,376 cycles, up 1,686 from the preceding accepted candidate's
  14,690; no map moved backward.
- **8/52 maps are exact through the full 1,800-cycle window**, with 44 later
  divergences and no execution failures. Their all-map frontier sum is 30,447.
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

The complete suite now contains **2,724 tests**. The no-data profile ran 1,597
and intentionally skipped 1,127, with the exact expected 89-test specification
failure set. The matched authenticated local profile discovered all 2,724 and
kept the exact expected 110-test failure set. It ran three optional local-save
checks that the fixed CI profile intentionally skips, so local skips were 23
while the canonical CI contract remains 26.

Source-boundary, native-runtime, comment-provenance, documentation, and BNE
readiness checks pass. The release changes only game/engine and documentation
paths; no matchmaking protocol, service, or infrastructure path changed, so
this candidate requires an engine OTA but no server rollout.

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

The source of truth is executable evidence: the enforced test profiles, the
17-lane playability receipt, the authenticated retail comparison harness under
`tools/bne-harness/`, packaged-launcher checks, and focused regression tests
beside the behavior they protect.
