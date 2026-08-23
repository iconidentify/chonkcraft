# Project status

ChonkCraft is a playable public beta. Campaigns, skirmishes, combat, economy,
construction, fog of war, computer opponents, spells, upgrades, sound, music,
save and load, and lockstep multiplayer are implemented. All 52 campaign
missions load and run from an authenticated ChonkPack built from original game
media supplied by the player.

## Measured Battle.net Edition parity

The current authenticated release candidate has been compared with Warcraft II:
Battle.net Edition 2.02b on all 52 campaign fixtures through cycle 400:

- **52/52 maps are exact through cycle 129.** The first semantic mismatch is
  Human 8 at cycle 130; Human 13 now remains exact through cycle 135.
- **45/52 maps remain exact through cycle 140.** The seven earlier boundaries
  are Human 8, Human 13, XHuman 9, XHuman 10, XHuman 12, XOrc 11, and Orc 11.
- **14/52 maps are exact for the complete 400-cycle measurement window.** The
  other 38 have a later measured first mismatch; none failed to execute.
- "Tested through cycle 400" describes coverage. It does not mean exact parity
  through cycle 400. The shared exact frontier is always the lowest first
  mismatch across all 52 maps, minus one.

These figures come from the sealed native corpus and the same authenticated BNE
asset source. The current candidate preserves all 14 maps that were already
exact through cycle 400 while moving the shared frontier from cycle 120 to 129.
It moves the first mismatch on XHuman 9 from 125 to 132, XHuman 12 from 121 to
132, XOrc 11 from 127 to 134, and Human 13 from 130 to 136 without moving any
map's first boundary backward.

## Measured online playability

The release gate starts two independent game JVMs against the public production
service. It deliberately gives the joiner the wrong retail map, verifies exact
host-map replacement, starts the lobby, renders both BNE battlefields and rejects
an all-black frame, advances 180 lockstep cycles, and requires identical final
world hashes. A separate production protocol smoke test proves room discovery,
two authenticated WSS relay seats, map transfer, lobby start, and bidirectional
game traffic immediately after deployment.

This proves the public path end to end from two local clients through the remote
service. A recorded match between two physically separate player machines is
still a useful field confirmation, not a substitute for the automated gate.

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
