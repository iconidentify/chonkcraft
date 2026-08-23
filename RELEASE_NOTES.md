# Battle.net Combat and Movement Recovery

- Raised the authenticated all-map Battle.net Edition frontier from cycle 120
  to cycle 129. All 52 campaign maps are exact through that boundary, 45 remain
  exact through cycle 140, and 14 remain exact for the complete 400-cycle
  measurement window.
- Fixed broad chase and formation handoffs that could leave attackers parked on
  stale collision or refusal state long after their route changed.
- Matched Battle.net Edition's paid attack-construction and residual-route
  ownership, so packed melee units resume their approach instead of freezing at
  an attack animation boundary.
- Corrected patrol, naval occupancy, resource approach, projectile ownership,
  AI building bounds, and save/load state needed by longer live matches.
- Retained the public multiplayer release gate: two rendered clients exchange
  the host's exact map through the production service, reject black frames, run
  180 lockstep cycles, and must finish with identical world hashes.
