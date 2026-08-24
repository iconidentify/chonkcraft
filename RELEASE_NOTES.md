# Explicit Multiplayer Teams, Walls, and Reliable Worker Orders

- Replaced **Top vs Bottom** with explicit numbered teams. The host assigns each
  human or computer to Team 1–8 independently of colour and starting position;
  moving somebody to a new colour preserves their team.
- Teams mode starts whenever at least two occupied team numbers are present, so
  two humans on Team 1 can play one or more computers on Team 2. The same team
  assignment controls mutual alliances, shared sight, and private Team chat.
- Restored Battle.net Edition's multiplayer-only wall buttons for both human
  and orc workers. Completed wall sites now become connected, destructible wall
  terrain, and terrain changes participate in the multiplayer synchronization
  hash so peers cannot silently disagree about a wall.
- Strengthened the production multiplayer referee around the reported family
  setup: two separate game processes must place both humans on the same team,
  put one computer on the opposing team, attach its retail `ai.bin` profile,
  share human vision in both directions, and stay synchronized for 180 cycles.
- Fixed the lost-worker failure where a farm order released as a peasant or
  peon entered a mine could strand that worker off-map forever while it still
  consumed food. The worker now emerges and resumes the acknowledged build.
