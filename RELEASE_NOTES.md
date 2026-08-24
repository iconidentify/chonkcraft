# Multiplayer Teams, Shared Vision, and BNE Interaction Fixes

- Fixed the Teams-mode Start gate for the ordinary cooperative setup. If every
  occupied slot still has one team number, Start now keeps all humans together
  and makes every computer the opposing team automatically. Two humans versus
  one AI and one human versus five AIs no longer require manual team repair;
  any explicit multi-team assignments remain authoritative.
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
- Allied exploration now combines cleanly on both the main field and minimap.
  Teammates reveal the same territory and units without black triangular seams
  where their fog boundaries first meet.
- Option-click now sends teammates a synchronized map and minimap ping with a
  restrained positional chime and anti-spam cooldown.
- Wood crews keep their harvest intent when a group order spreads workers
  around a crowded forest, instead of leaving displaced peasants idle.
- A gryphon commanded to Move during a committed attack now finishes that
  unbreakable BNE animation and then obeys the replacement order. A following
  Attack also resumes normally without requiring a manual Stop first.
- Multiplayer team elimination now displays Game Over when the last hostile
  team loses its real units. The result is based on the whole alliance, so an
  eliminated player does not lose while a teammate survives. Players may Quit
  Game or Keep Playing to explore the synchronized map.
- Gold mines depleted by the last working load now reliably play their retail
  BNE collapse sound when watched, including the fog transition caused by the
  neutral mine disappearing and busy scenes with a full sound-event queue.
