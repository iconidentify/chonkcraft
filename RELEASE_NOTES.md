# ChonkCraft 0.1.1-beta11 — BNE Gameplay and Feedback Update

- Completed the remaining Battle.net Edition movement and refusal edge states, including congestion recovery, large-unit footprints, chase refusal, crowded shoreline unloading, and native one-tile/two-tile transport transitions.
- Completed projectile and combat-effect lifecycles: launch orientation, flight cadence, impact and vanish timing, interrupted-shot cleanup, persistent land mines, Flame Shield, Whirlwind, Runes, Death Coil healing, and the green-cross order marker.
- Implemented the previously inert retail spell callbacks for Eye of Kilrogg, Polymorph, and Unholy Armor using authenticated Warcraft II BNE executable evidence.
- Restored neutral critter voices and certified unit selection, destruction, work-complete, and interaction feedback against the authenticated BNE media pack.
- Hardened harvesting and oil interactions so workers resume valid tree orders and ships no longer oscillate or strand cargo during congested approaches.
- Preserved persistent effects and terrain state through save/load, preventing stale projectile art and black map squares after restoring a game.
- Added deterministic in-game chat with connected-player presence and local muting across direct and online multiplayer sessions.
- Standardized player-facing BNE status messages and corrected several clipped or stale interface states.
- Rebuilt the resource strip as one visually verified group: every icon stays attached to its value and every counter has identical inter-group spacing at all supported UI scales.
- Expanded the authenticated certification gates to 85 movement checks, 54 projectile/feedback checks, 36 spell checks, and 21 sound checks, all passing with zero skipped evidence tests.
