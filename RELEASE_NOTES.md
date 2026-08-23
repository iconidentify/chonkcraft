# Explicit Multiplayer Teams and Reliable Worker Orders

- Made **Top vs Bottom** literal: every lobby row names its map-defined team,
  the summary names each occupied colour on both sides, and it says when shared
  sight is on. Two adjacent-looking colour slots can no longer masquerade as a
  team on maps whose starting positions alternate between north and south.
- Top vs Bottom will not start with everybody on one side. The host can move
  people, computers, or themselves between open colour/start slots, and the
  battlefield opens by naming the human ally whose sight is actually shared.
- Strengthened the production multiplayer referee around the reported family
  setup: two separate game processes must place both humans on the same team,
  put one computer on the opposing team, attach its retail `ai.bin` profile,
  share human vision in both directions, and stay synchronized for 180 cycles.
- Fixed the lost-worker failure where a farm order released as a peasant or
  peon entered a mine could strand that worker off-map forever while it still
  consumed food. The worker now emerges and resumes the acknowledged build.
