# Immediate Combat Retargeting

- Fixed a Battle.net Edition combat transition that made a melee attacker pause
  after its target entered a mine, even though a new hostile target was ready.
- Matched native BNE's same-update handoff: the attacker now picks its
  replacement, creates the chase route, and takes its first step immediately.
- Advanced the authenticated Human 8 frontier from cycle 130 to 156 without
  moving any of the other 51 campaign frontiers backward.
- Improved the all-map proof: all 52 campaigns are exact through cycle 131, 14
  remain exact through the complete 400-cycle window, and no fixture failed.
- Retained the public two-client multiplayer gate, including exact host-map
  transfer, rendered non-black views, 180 lockstep cycles, and matching final
  world hashes.
