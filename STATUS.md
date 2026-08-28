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

- **52/52 maps are exact through cycle 260.** The lowest common divergence is
  cycle 261, so the shared proven frontier is cycle 260. Expansion Orc 8 is
  the sole map at that boundary; every other mission reaches at least cycle
  263.
- **27/52 maps are exact through the full 400-cycle window**, with 25 later
  divergences and no execution failures. The sum of all per-map exact
  frontiers, capped at 400, is 18,366 cycles; no map moved backward.
- **8/52 maps are exact through the full 1,800-cycle window**, with 44 later
  divergences and no execution failures. Their all-map exact-frontier sum is
  33,413; no map moved backward. The eight are Human 1, 2, and 9; Orc 1, 2,
  and 6; and expansion Orc 1 and 9.
- The current production-cadence milestone moved three independent campaign
  frontiers: Human 4 from 493 to 545, Orc 4 from 499 to 584, and expansion Orc
  4 from 493 to 648. The cycle-400 and cycle-1,800 fleets retained their exact
  map counts and the h260 regression gate with zero failed cases.

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

The complete suite now contains **2,803 tests**. The canonical authenticated
profile runs 2,776 and intentionally skips exactly 27, while keeping the exact
expected 110-test specification-failure set. The added boundary matrix proves
that completed land, naval and air trainees survive save/resume without being
born twice, while a fully blocked trainee remains unborn until a legal exit
opens. New training completions now retire atomically in the trainee's birth
cycle while schema-2 loading remains compatible with older saved completion
latches. The synchronization hash covers that latch, so peers report any old
boundary disagreement before a duplicate can appear. Hosted run `33158343945`
passed both the data-free and authenticated jobs against revision
`852ff44fd866db8a7b5181378be971a2d4c37a08`.

The next-level readiness inventory passes its present fail-closed executable
checks while correctly remaining open for the incomplete proof lanes below.
Signed engine OTA `2026.0828.91` is the public release, published from revision
`852ff44fd866db8a7b5181378be971a2d4c37a08` with game JAR SHA-256
`f473e33f07cd67492470ce21835a801f9c1df6903fd3f4c1896662764553cb00`.
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
- Physical player transactions: **1/532**. One cell has an exact current-source
  native/Java semantic pair, proven by two independent sealed scenarios. The
  two-unit held-out cell remains red on a real two-cycle terminal difference;
  one retained store now reopens five native capture closures across the Human
  and Orc campaigns and reruns their packaged Java twins with producer evidence
  verified. Three reach a real terminal; the Human three-wide and Orc two-wide
  40-cycle observations remain terminal-incomplete. The other 531 cells remain
  open; detached receipts still cannot certify themselves.
- Replay twin: **0/764,756** dispatcher records in a complete 27-replay paired
  certification.
- AI fleet: **52/52** current-head mission twins are materialized from
  authenticated native captures. Committed state is exact for **45/52**
  missions and **202,290/205,200** player-cycles. Full causal telemetry is
  exact for **45/52** missions and **202,285/205,200** player-cycles. The
  schema-2 ledger derives requested/assigned launch receipts and effective
  behavior-two target coordinates from authenticated native state. Native
  air and naval target selection makes expansion Orc 11 and expansion Orc 8
  fully causal-exact; the one remaining launch mismatch is downstream
  expansion Human 12 fallout. Fleet certification remains incomplete until
  all 52 missions are exact under one proof.
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
