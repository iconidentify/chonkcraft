# Project status

ChonkCraft is a playable public beta. Campaigns, skirmishes, combat, economy,
construction, fog of war, computer opponents, spells, upgrades, sound, music,
save and load, and lockstep multiplayer are implemented. All 52 campaign
missions load and run from an authenticated ChonkPack built from original game
media supplied by the player.

Every multiplayer client passively keeps a bounded local flight record under
`~/.chonkcraft/recordings`. Current schema-2 bundles seal the exact lobby map,
cycle-zero save and accepted command stream by byte count and SHA-256, retain
the complete 16-slot controller/race table, name the synchronization-hash
schema, and bind an OTA-launched match to its installed game JAR and source
revision. They record no chat, upload nothing, and add no replay viewer. The
strict referee reconstructs the saved world from authenticated game data and
replays every accepted batch through the ordinary command and tick boundaries.

Legacy schema-1 bundles remain readable diagnostics but can never certify: they
did not seal their artifacts or retain the player table, producing JAR, source
revision, or synchronization-hash schema. The held-out 13-hour Forsaken Isles
recording (282,670 network cycles, 1,413,346 world cycles and 621 commands)
authenticates structurally, now reconstructs its initial island world exactly,
and replays through network cycle 256 before the current referee rejects a hash
difference at cycle 257. That bounded prefix is useful evidence, but the missing
legacy identities prevent attributing the later difference to either its older
`2026.0827.77` engine or current source.

## Measured Battle.net Edition parity

The current public release has been compared with Warcraft II: Battle.net
Edition 2.02b across the complete 52-map authenticated campaign fleet:

- **52/52 maps are exact through cycle 263.** The lowest common divergence is
  cycle 264, so the shared proven frontier is cycle 263. Expansion Human 12
  is now the only map at that boundary.
- **27/52 maps are exact through the full 400-cycle window**, with 25 later
  divergences and no execution failures. The sum of all per-map exact
  frontiers, capped at 400, is 18,382 cycles; no map moved backward.
- **8/52 maps are exact through the full 1,800-cycle window**, with 44 later
  divergences and no execution failures. Their all-map exact-frontier sum is
  33,702; no map moved backward. The eight are Human 1, 2, and 9; Orc 1, 2,
  and 6; and expansion Orc 1 and 9.
- The terminal-route naval milestone moves expansion Orc 8 from cycle 264 to
  268. A paid small-warship congestion wake may replace a blocked route while
  several headings remain, but retains its final consumed heading for the
  ordinary cooperative hold. Two native-backed final-tail cases and held-out
  fresh-route, coast, attack and multi-heading controls protect the systemic
  distinction. All other 51 maps are unchanged across both complete fleets;
  the cycle-400 and cycle-1,800 counts remain 27 and 8 with zero failed cases.
  Clean accepted receipt `1c162ee068cd64ef21279aa7e2dcda729470fd979b4668a7a8f48aa9ccf37bf0`
  binds source `7ae3de6f1826fe928bf2dd9faaa8961dc2c57002` to engine-input identity
  `0ec4cb11a50eae80dfa0c981f7fc9538f6a5f80ad340fd0bdc929333c3c20a71`.
- The crowded-depot milestone moves expansion Orc 12 from cycle 264 to 273 by
  letting a staged laden worker commit its final entry behind an allied worker
  whose own entry pixels are still draining. Expansion Human 7 independently
  proves the same overlap, while pre-stage and stationary-worker controls stay
  blocked. The other 51 maps are unchanged across both complete fleets.
- Computer oil tankers now preserve native action 23 behind the 25-cycle
  depot-ready Still head. Two independent campaigns advance without a fleet
  regression: Orc 7 is exact through 823 instead of 596, and Orc 10 through
  485 instead of 439.

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

The complete suite now contains **2,815 tests**. The canonical authenticated
profile runs 2,788 and intentionally skips exactly 27, while keeping the exact
expected 110-test specification-failure set. The added boundary matrix proves
that completed land, naval and air trainees survive save/resume without being
born twice, while a fully blocked trainee remains unborn until a legal exit
opens. New training completions now retire atomically in the trainee's birth
cycle while schema-2 loading remains compatible with older saved completion
latches. The synchronization hash covers that latch, so peers report any old
boundary disagreement before a duplicate can appear. The physical transaction
adapter now has a retail-backed referee for a sealed null target over occupied
ground; it adds one intentional data-free skip and runs in the authenticated
profile. Exact-boundary research
and in-place hall-upgrade referees now prove that resume neither charges again
for nor revokes completed research, and never duplicates, moves, or resurrects
a transformed building. Those two retail-roster referees deliberately add two
data-free skips. A matched-input full run against revision
`ff10970dfb03c63c5551b4e5bab3279235fd9239` exercised all 2,788 authenticated
tests with the exact expected failure identity.

The next-level readiness inventory passes its present fail-closed executable
checks while correctly remaining open for the incomplete proof lanes below.
Signed engine OTA `2026.0828.98` is the public release, published from revision
`ff10970dfb03c63c5551b4e5bab3279235fd9239` with game JAR SHA-256
`5b964a28def4a49e2f83cb17361c0a588ea56b2bf2239a5a8ef57e4d2f5e6fe4`.
Its workflow proved both local installation and a fresh launcher install from
the public endpoint. No matchmaking protocol, service, or infrastructure path
changed, so a server rollout was not required.

## Broader fidelity frontier

These denominators are deliberately strict and remain open work rather than
being inferred from the playable campaign fleet:

- Resolved-command cells: **6/240 current generated cells are identity-joined**:
  Human 1 Patrol, Orc 1 Move, and Orc 1 turn-boundary Attack are exact. Human 1
  turn-boundary Attack, expansion Human 12 Harvest, and expansion Human 12
  turn-boundary Attack are materially divergent; 234 cells remain uncaptured
  and none failed because of infrastructure. The expansion Attack exposes a
  real order-resolution split (native accepts before the unit becomes
  unavailable; Java rejects and leaves Harvest installed), while the Human 1
  Attack ends at a different tile and hit-point value. The prior 206
  dual-adapter executions plus two first-pass movement captures remain
  historical/unmatched diagnostics, so a count cannot fill the denominator.
  Every cell binds map, initialization seed, complete command content
  (including production type), and terminal observation cycle. The gate
  regenerates all 240 cells from three hash-pinned Human, Orc, and expansion
  seeds, then reopens the inventory and execution ledger before accepting a
  numerator.
- Physical player transactions: **2/532** current-source paired certifications.
  A retained store reopens thirteen native capture closures across the Human
  and Orc campaigns and reruns their packaged Java twins with producer evidence
  verified. The single-unit, field/plain Move cells for open and occupied ground
  are each exact across two independent scenarios. The two-unit open- and
  occupied-ground held-outs remain red; they are retained as movement debt
  rather than hidden by normalization or a scenario-specific exception.
- Replay twin: **0/764,756** dispatcher records in a complete 27-replay paired
  certification.
- AI fleet: **52/52** current-head mission twins are materialized under one
  validated conductor report; **45/52** are committed-state and causal-
  telemetry exact through 1,800 cycles. The fixed denominator is 205,200
  computer-player cycles: 202,290 committed-state rows and 202,285 telemetry
  rows are exact. Seven ranked frontiers remain, so fleet certification is
  incomplete. The retained conductor proof is validated fail-closed against
  the current clean source, engine, Java adapter, app JAR, ChonkPack and
  retail `ai.bin` identities.
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
