# Stop Corpse-Chase Overshoot

- Fixed a combat transition that could make a melee unit take another cached
  route step—and visibly glide—after its quarry had already started dying.
- Matched native Battle.net Edition's retained-route lifecycle: Attack
  construction counts 3, 2, 1 in place, validates the expired target, clears
  the route, and opens Still before movement can consume another heading.
- Advanced authenticated XHuman 9's first mismatch from cycle 132 to 188: 56
  additional exact BNE cycles without moving any campaign frontier backward.
- Preserved the all-map proof: all 52 campaigns remain exact through cycle 131,
  14 remain exact through the complete 400-cycle window, and none failed.
- Re-certified the public two-client multiplayer path with the release source:
  exact host-map transfer, two rendered non-black views, 180 production-relayed
  lockstep cycles, and matching final world hash `fa7c04a20d8e0d4a`.
