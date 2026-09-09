# Responsive Unit Orders and Smoother Rendering

- Fixed units becoming stuck when Move, Attack or Attack-Move interrupted an
  unfinished step. Units now finish the committed movement and follow the
  replacement order without requiring another Stop command.
- Fixed old Stop requests interrupting later movement, and restored the
  original timing when redirecting units or replacing a newly issued Move.
- Improved Patrol combat and return behavior, Repair approach timing, and
  explicit attacks retaining the enemy you selected.
- Preserved active Patrol movement, combat and return destinations when
  saving and loading during an encounter.
- Includes the recent fog-edge flicker and walking-animation fixes, smooth
  arrow-key scrolling, and reduced rendering memory use in menus and matches.
- Retains the multiplayer lobby fix that prevents repeated Start clicks from
  opening multiple copies of a match.
