# Project status

ChonkCraft is a playable public beta. Campaigns, skirmishes, combat, economy,
construction, fog of war, computer opponents, spells, upgrades, sound, music,
save and load, and lockstep multiplayer are implemented. All 52 campaign
missions load and run from an authenticated ChonkPack built from original game
media supplied by the player.

The current source of truth is executable evidence:

- the automated test suites and their enforced skip baselines;
- the authenticated playability gate;
- the retail comparison harness under `tools/bne-harness/`;
- packaged-launcher smoke tests for macOS, Windows, and Linux; and
- focused regression tests beside the behavior they protect.

This file intentionally records only the present release posture. Historical
investigation notes and a permanent narrative defect ledger are not part of the
project documentation. New actionable defects should receive a focused failing
test and be fixed against authenticated retail behavior.
