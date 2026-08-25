# Battle.net Fleet Fidelity and Crowded Combat Fixes

- Advanced the authenticated campaign fleet substantially: all 52 maps now
  match Battle.net Edition through at least cycle 216, 21 remain exact through
  cycle 400, and eight remain exact through cycle 1,800 without regressing any
  map's previous boundary.
- Corrected attack and chase handoffs around moving, dying, and replacement
  targets. Units now preserve Battle.net Edition's paid attack cadence, cached
  route ownership, collision waits, and same-visit retargets in crowded fights.
- Restored artillery safety and timing. Ballistae, catapults, battleships, and
  juggernauts avoid unsafe splash shots through friendly formations, mobile
  siege retargets correctly, and cannon flashes and impacts keep retail pool
  order without changing damage timing.
- Improved formation movement and patrol recovery for land, naval, and air
  units. Obstructed routes keep the correct endpoint, refusal band, and cached
  tail instead of stepping early, rotating around the wrong body, or idling.
- Fixed crowded resource traffic. Miners, lumber crews, and oil tankers keep
  their chosen depot and return point through mine or platform exits, blocked
  approaches, and route refills, including loaded workers converging on the
  same hall.
- Aligned computer-player hall placement and several construction, idle,
  projectile, and unit-removal scheduling seams with authenticated retail
  behavior.
- Save/load now preserves the parked movement and refusal state needed to
  resume the same combat and resource route after loading.
- Expanded the authenticated referee suite to 2,724 tests and certified all 17
  player-facing playability lanes, including clean and adverse lockstep play.
