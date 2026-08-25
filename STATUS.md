# Project status

ChonkCraft is a playable public beta. Campaigns, skirmishes, combat, economy,
construction, fog of war, computer opponents, spells, upgrades, sound, music,
save and load, and lockstep multiplayer are implemented. All 52 campaign
missions load and run from an authenticated ChonkPack built from original game
media supplied by the player.

## Measured Battle.net Edition parity

The current release candidate has been compared with Warcraft II: Battle.net
Edition 2.02b across the complete 52-map authenticated campaign fleet:

- **52/52 maps are exact through cycle 252.** The lowest common divergence is
  cycle 253, so the shared proven frontier is cycle 252. The three maps at that
  boundary are Orc 8, expansion Human 7, and expansion Orc 11.
- **27/52 maps are exact through the full 400-cycle window**, with 25 later
  divergences and no execution failures. The sum of all per-map exact
  frontiers, capped at 400, is 18,164 cycles; no map moved backward.
- **8/52 maps are exact through the full 1,800-cycle window**, with 44 later
  divergences and no execution failures. Their all-map exact-frontier sum is
  32,826; no map moved backward. The eight are Human 1, 2, and 9; Orc 1, 2,
  and 6; and expansion Orc 1 and 9.
- In the latest full-fleet pass, Orc 10 advanced from cycle 255 to 440 and
  expansion Orc 7 from cycle 252 to 542. Both are now exact through the entire
  400-cycle window; the other 50 maps retained their prior boundaries.

"Tested through cycle 400" or "through cycle 1,800" describes coverage, not a
claim that every map is exact for that whole window. The common frontier is
always one cycle before the earliest authenticated mismatch across the fleet.

## Playability and release gates

The last completed player-contract receipt certifies **17/17 playability
lanes** with zero failures, skips, blocks, or timeouts. The cycle-252 candidate
re-ran 13 lanes successfully with no failures before the checkpoint was
stopped; its remaining four-lane receipt is intentionally pending. The gate
covers authenticated boot and assets, deterministic scheduling, movement,
orders, economy, construction, combat, projectiles, naval oil, spells, retail
AI, campaign triggers, save/load, rendering and input, sound, and clean/adverse
network lockstep.

The complete suite now contains **2,760 tests**. The canonical authenticated
profile ran 2,734 and intentionally skipped exactly 26, while keeping the exact
expected 110-test specification-failure set. Three optional private
playtest-save checks also ran and passed on the development workstation before
the CI-equivalent skip receipt was produced.

The next-level readiness gate also passes its present fail-closed contract.
Signed engine OTA `2026.0825.68` remains the public release, published from
revision `979c4a21613b40405fad03a0158b1c80d0a31e84`; its workflow proved both
local installation and a fresh launcher install from the public endpoint. The
cycle-252 candidate is fleet-verified but not yet published. It changes
only engine behavior and authenticated referees: no matchmaking protocol,
service, or infrastructure path changed, so a server rollout is not required.

## Broader fidelity frontier

These denominators are deliberately strict and remain open work rather than
being inferred from the playable campaign fleet:

- Commanded scenarios: **206/240 exact and comparable**, zero materially
  divergent, zero infrastructure failures, and 34 not yet comparable.
- Physical player transactions: **0/532** paired native/Java certifications.
- Replay twin: **0/764,756** dispatcher records in a complete 27-replay paired
  certification.
- AI fleet: **0/52** current-head mission twins. Forty-five authenticated
  native captures are discoverable, seven are missing, and none has yet been
  materialized into a complete current-head conductor receipt.
- Combat lifecycle: **0/185** accepted cells. Campaign lifecycle: **0/137**
  accepted trigger twins.

The zero numerators do not mean those game systems are absent. The 52-map
`semantic-v1` survey and the playability lanes do not emit these proof types.
Each lane accepts only a complete native/Java twin report bound to the current
engine and program identity, and it fails closed rather than carrying forward
partial, detached, legacy, or stale receipts.

The source of truth is executable evidence: the enforced test profiles, the
17-lane playability receipt, the authenticated retail comparison harness under
`tools/bne-harness/`, packaged-launcher checks, and focused regression tests
beside the behavior they protect.
