# Multiplayer Teams and Reliable Worker Orders

- Added a Battle.net Edition-style **Top vs Bottom** lobby mode. Players in
  the same fixed starting area begin as mutual allies with shared vision.
- The host can now move any player—including themselves—to an open colour and
  starting slot, making team and colour setup explicit before the match.
- Fixed the lost-worker failure where a farm order released as a peasant or
  peon entered a mine could strand that worker off-map forever while it still
  consumed food. The worker now emerges and resumes the acknowledged build.
- Bumped the deterministic lobby protocol so old and new team-capable clients
  cannot accidentally enter the same lockstep game.
