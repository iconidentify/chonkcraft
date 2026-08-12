# ChonkCraft 0.1.1-beta15 -- Natural Shoreline and Terrain Movement

- Recreated Battle.net Edition's widening move-order behavior: ships ordered onto shoreline now sail to the closest legal water instead of acknowledging the command and remaining stationary.
- Ground units ordered toward water, trees, or other incompatible terrain now approach the nearest reachable edge, including orders issued through the minimap.
- Preserved the native distinction for genuinely disconnected destinations, which remain safely bounded and eventually stop without crossing illegal terrain.
- Restricted an autonomous-critter terrain guard that had accidentally cancelled ordinary player movement before the pathfinder could apply the retail rule.
- Strengthened the authenticated movement referee from 87 to 88 checks, with explicit behavioral proof for ships approaching land, soldiers approaching water, and soldiers approaching dense forest.
- Verified all 52 authenticated campaign fixtures with zero failures and zero regressions against the accepted h40 frontier.
- Verified the complete engine failure set is identical before and after the change, while the new player-facing movement tests pass.
