# Frozen Combat Path Recovery

- Fixed a broad combat-pathing bug that could turn an invisible death revealer
  into a phantom solid wall and leave an attacker frozen beside a corpse.
- Matched native Battle.net Edition occupancy: non-solid, vanishing vision
  markers remain passable across every temporary path-planning unmark/restore.
- Kept native BNE's fresh diagonal route when a melee attack tail selects a new
  quarry, instead of inheriting a stale facing from the completed fight.
- Advanced authenticated XHuman 10 from cycle 132 to 133 while preserving every
  other map's prior frontier. All 52 campaigns remain exact through cycle 131;
  14 remain exact through the complete 400-cycle window, with no failures.
- Re-certified the public two-client multiplayer path: exact host-map transfer,
  two rendered non-black views, 180 production-relayed lockstep cycles, and
  matching final world hash `fa7c04a20d8e0d4a`.
