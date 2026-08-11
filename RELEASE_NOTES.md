# ChonkCraft 0.1.1-beta12 — Native Movement Convergence

- Replaced guessed player-command delays with timing derived directly from each unit's Battle.net Edition animation sequence.
- Corrected command completion for ground, naval, and flying units, including occupied destinations, terminal route steps, shoreline targets, and the native doubled movement lattice used by flyers.
- Prevented autonomous target acquisition from stealing the end of an explicit player movement order.
- Recreated native patrol interruption and resumption: a commanded patrol unit completes the player's order, consumes the correct endpoint random draws, chooses a fresh patrol destination, and resumes on the retail cadence.
- Preserved the new command, delay, and interrupted-action state through save/load and included it in multiplayer synchronization hashes.
- Expanded the authenticated movement playability inventory from 85 to 87 checks.
- Raised the controlled command corpus from 0 of 19 to 19 of 19 exact cases through the command horizon, with all ground, naval, and occupied-air subjects remaining exact through the full 160-cycle certification window.
- Passed the 52-campaign regression gate with zero regressions and the complete 17-lane authenticated playability certification.
