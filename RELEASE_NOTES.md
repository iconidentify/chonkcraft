# ChonkCraft 0.1.1-beta13 — Construction, Combat, and Movement Fidelity

- Corrected builders that entered a construction footprint too early: they now finish the exact Battle.net Edition approach to the stored build point before founding the structure.
- Recreated the occupied-destination refusal sequence used when a unit's route ends beside a moving blocker, including the native retry cadence and solid-to-soft congestion transition.
- Preserved long terminal flight routes so air units no longer invent an extra pause before their final diagonal step.
- Moved kill scores, kill counts, and razing counts into the deterministic simulation event where the lethal hit occurs. Headless games and multiplayer now receive the same immediate result as the visible desktop game.
- Removed the old render-loop score sidecar and aligned parity traces with Battle.net Edition by excluding units already in their death lifecycle from live-unit totals.
- Added regression coverage for exact construction arrival, occupied point refusals, long flyer routes, synchronous scoring, and traversal across a destroyed building's former footprint.
- Investigated the reported Human Mission 5 blocked-barracks area from the saved game: the destroyed footprint is clear in the saved world and ground units cross it under the real player-command path; the lifecycle is now protected by an automated regression test.
- Completed a clean build of every Java module and passed the focused gameplay suite, documentation gate, and 52-campaign semantic regression survey with zero failed fixtures.
