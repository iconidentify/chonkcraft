# ChonkCraft 0.1.1-beta19 -- Reliable Siege and Naval Fire

- Fixed ballistae and other ranged units leaving phantom projectiles behind when they move, stop, retarget, die, or resume from an older save.
- Kept pre-launch fireballs, cannonballs, arrows, and axes invisible until Battle.net Edition's authoritative firing moment, eliminating missiles that appeared stuck on their owner before launch.
- Prevented duplicate presentation callbacks and legacy save data from manufacturing multiple live shots for one attack.
- Removed abandoned ship cannonballs when their target dies instead of leaving a projectile stranded in the water or reviving it during a later attack.
- Fixed battleships acknowledging an attack on a coastal building but remaining idle when the final shoreline route step was permanently blocked.
- Added an in-range water approach for capital ships attacking coastal buildings when no reachable one-tile target skirt exists.
- Added exact-save regression coverage for the Human 6 ballista and Human expansion 6 battleship reports, plus expanded projectile and movement lifecycle gates.
