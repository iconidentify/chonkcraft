# Game transitions keep the right battle and music

- Starting a game shuts down the previous battle, its sounds and pending
  result screen, preventing an old mission's defeat from covering a healthy game.
- Repeated launch input starts one load. A failed save load leaves the menu
  available for another choice.
- Music selection follows the campaign and screen. Imports with unidentified
  numbered recordings use the appropriate synthesized themes for the menu,
  Human and Orc battles, briefings and results.
- Switching music sources preserves the current theme, and cleanup from an
  old screen no longer silences the next screen's music.
