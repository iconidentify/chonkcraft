# ChonkCraft 0.1.1-beta20 -- Responsive Orders and Smarter Opposition

- Fixed rejected commands erasing a unit's valid movement queue, group waypoint, or attack-move resume state. Units now keep following their prior orders when an incompatible or stale replacement command is refused.
- Restored the ordinary skirmish computer's resource, construction, force-formation, and attack scheduler, closing forty known AI behavior failures.
- Kept retail campaign opponents exclusively controlled by their Battle.net Edition `ai.bin` personalities, including profiles whose bytecode is only partially decoded, so a second generic AI cannot spend their resources or move their units.
- Restored Human mission 6 assault movement when a bounded patrol route reaches an empty prefix, preventing computer attackers from freezing beside valid targets.
- Preserved AI assault behavior, home coordinates, and carried-resource state across saves so loaded opponents and workers resume the job they had before saving.
- Added authenticated player-intent capture and replay-outcome certification across 27 retail multiplayer replays, covering 168,788 commands and 22,518 multi-unit selections.
- Added exact regression coverage for refused command transactions, queued replacements, retail-AI isolation, Human 6 assault recovery, and save/load cargo continuity.
