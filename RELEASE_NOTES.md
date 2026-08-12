# ChonkCraft 0.1.1-beta16 -- Reliable Shoreline Construction

- Fixed accepted shipyard orders that sent a peasant to the coast, acknowledged the command, and then waited forever without laying a foundation.
- Restored Battle.net Edition's ranged construction arrival rule: a land worker raising a shore building finishes beside its legal footprint instead of being required to enter water.
- Applied the correction to the shared construction state machine so shipyards, foundries, refineries, and future ranged construction use one coherent rule rather than building-specific exceptions.
- Added an end-to-end behavioral referee that starts with an accepted green shoreline placement, advances the worker through the normal order stream, and requires the shipyard foundation to appear.
- Passed 48 focused construction, production, tanker, and oil-platform checks with zero failures and zero skips.
- Verified all 52 authenticated campaign fixtures through 200 cycles with zero failures and zero regressions against the accepted h40 frontier.
