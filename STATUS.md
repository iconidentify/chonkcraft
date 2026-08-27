# Project status

ChonkCraft is a playable public beta. Campaigns, skirmishes, combat, economy,
construction, fog of war, computer opponents, spells, upgrades, sound, music,
save and load, and lockstep multiplayer are implemented. All 52 campaign
missions load and run from an authenticated ChonkPack built from original game
media supplied by the player.

Every multiplayer client passively keeps a bounded local flight record under
`~/.chonkcraft/recordings`: the exact lobby map, cycle-zero save, accepted
command stream, and sync hashes. It records no chat, uploads nothing, and adds
no replay viewer; the bundle exists only to harvest a troublesome real match
after play.

## Measured Battle.net Edition parity

The current public release has been compared with Warcraft II: Battle.net
Edition 2.02b across the complete 52-map authenticated campaign fleet:

- **52/52 maps are exact through cycle 260.** The lowest common divergence is
  cycle 261, so the shared proven frontier is cycle 260. Expansion Orc 8 is
  the sole map at that boundary; every other mission reaches at least cycle
  263.
- **27/52 maps are exact through the full 400-cycle window**, with 25 later
  divergences and no execution failures. The sum of all per-map exact
  frontiers, capped at 400, is 18,366 cycles; no map moved backward.
- **8/52 maps are exact through the full 1,800-cycle window**, with 44 later
  divergences and no execution failures. Their all-map exact-frontier sum is
  33,121; no map moved backward. The eight are Human 1, 2, and 9; Orc 1, 2,
  and 6; and expansion Orc 1 and 9.
- The current AI-ledger milestone changes causal evidence, not simulation
  behavior: the cycle-400 and cycle-1,800 fleets retained these exact counts
  and the h260 regression gate with zero failed cases.

"Tested through cycle 400" or "through cycle 1,800" describes coverage, not a
claim that every map is exact for that whole window. The common frontier is
always one cycle before the earliest authenticated mismatch across the fleet.

## Playability and release gates

The current player-contract receipt certifies **18/18 playability lanes** with
zero failures, skips, blocks, or timeouts, including the read-only end-to-end
control-liveness referee. The gate
covers authenticated boot and assets, deterministic scheduling, movement,
orders, economy, construction, combat, projectiles, naval oil, spells, retail
AI, campaign triggers, save/load, rendering and input, sound, control liveness,
and clean/adverse network lockstep.

The complete suite now contains **2,790 tests**. The canonical authenticated
profile runs 2,764 and intentionally skips exactly 26, while keeping the exact
expected 110-test specification-failure set. Hosted run `33121122477` passed
both the data-free and authenticated jobs against revision
`f4e7b95212b58183340bcd72526fccfc2082f423`.

The next-level readiness inventory passes its present fail-closed executable
checks while correctly remaining open for the incomplete proof lanes below.
Signed engine OTA `2026.0827.86` is the public release, published from revision
`f4e7b95212b58183340bcd72526fccfc2082f423` with game JAR SHA-256
`9b6d1cfc033f10325a2d47b521dd376dfeb82192b4b15d881c07d5b9756036f5`.
Its workflow proved both local installation and a fresh launcher install from
the public endpoint. No matchmaking protocol, service, or infrastructure path
changed, so a server rollout was not required.

## Broader fidelity frontier

These denominators are deliberately strict and remain open work rather than
being inferred from the playable campaign fleet:

- Commanded scenarios: **206/240 exact and comparable**, zero materially
  divergent, zero infrastructure failures, and 34 not yet comparable.
- Physical player transactions: **0/532** paired native/Java certifications.
- Replay twin: **0/764,756** dispatcher records in a complete 27-replay paired
  certification.
- AI fleet: **45/52** current-head mission twins are materialized from
  authenticated native captures; seven captures are missing. Committed state
  is exact for **36/52** missions and **163,796/167,400** player-cycles. Full
  causal telemetry is exact for **32/52** missions and **163,784/167,400**
  player-cycles. Fleet certification remains incomplete until all 52 missions
  are materialized and exact under one proof.
- Combat lifecycle: **0/185** accepted cells. Campaign lifecycle: **0/137**
  accepted trigger twins.

The zero numerators do not mean those game systems are absent. The 52-map
`semantic-v1` survey and the playability lanes do not emit these proof types.
Each lane accepts only a complete native/Java twin report bound to the current
engine and program identity, and it fails closed rather than carrying forward
partial, detached, legacy, or stale receipts.

The source of truth is executable evidence: the enforced test profiles, the
18-lane playability receipt, the authenticated retail comparison harness under
`tools/bne-harness/`, packaged-launcher checks, and focused regression tests
beside the behavior they protect.
