# Multiplayer Teams and Reliable Worker Orders

- Added a Battle.net Edition-style **Top vs Bottom** lobby mode. Players in
  the same fixed starting area begin as mutual allies with shared vision.
- Fixed the team lobby on maps whose colour slots alternate between map
  areas. Top and Bottom are now visibly grouped and labelled from the verified
  map, so two adjacent-looking rows can no longer start on opposing teams.
- The host can now move any player—including themselves—to an open colour and
  starting slot, and can move computer seats the same way. Team, colour, and
  shared-sight setup are explicit before the match.
- Multiplayer sync checks now cover alliances and shared vision at cycle zero,
  catching a missing team setup immediately instead of after combat diverges.
- Fixed the lost-worker failure where a farm order released as a peasant or
  peon entered a mine could strand that worker off-map forever while it still
  consumed food. The worker now emerges and resumes the acknowledged build.
- Bumped the deterministic lobby protocol so old and new team-capable clients
  cannot accidentally enter the same lockstep game.
