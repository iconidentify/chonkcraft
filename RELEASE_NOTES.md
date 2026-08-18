# Faithful Combat Timing and Target Handoffs

- Preserved committed melee attacks when their original target begins dying, keeping later combat randomness aligned with the original game.
- Made ranged units resume pursuit immediately after a completed attack hold instead of waiting through a second unnecessary pause.
- Kept melee attack progress across route completion and target changes, eliminating an extra construction delay when a unit reaches its new opponent.
- Added native damage ownership and unit-action tracing so future combat differences can be tied to the exact attacker, target and random draw.
- Improved exact fleet measurements by 121 unit positions and 71 movement or combat decisions without introducing an earlier campaign divergence.
