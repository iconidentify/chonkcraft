# Project status

ChonkCraft is a playable public beta. Campaigns, skirmishes, combat, economy,
construction, fog of war, computer opponents, spells, upgrades, sound, music,
save/load and lockstep multiplayer are implemented. All 52 campaign missions
load from an authenticated ChonkPack built from the player's original media.

Player orders now retain committed native movement before their replacements
start. The current controls include the issue-12 stall fixes, consumed and
superseded Stop handling, Patrol combat and return timing, Repair approach
construction, explicit Attack target retention and Patrol save/load state.
They are integrated with the current fog, native walking animation, scrolling,
menu rendering, graphics-memory limits and multiplayer lobby fixes.

## Measured Battle.net Edition parity

The 52-map campaign survey uses the authenticated BNE 2.02b executable and
retail pack. Its semantic-v1 comparison covers cycle, synchronized RNG,
player banks, unit core and coarse orders:

- All 52 maps match through cycle 400, with no execution failures.
- Through cycle 1,800, 14 maps match and 38 have later divergences, with no
  execution failures. Every accepted per-map prefix is preserved.
- The shared exact frontier is 403; the sum of per-map exact prefixes,
  capped at 1,800, is 56,468.

This comparison excludes raw sequence, extended player state, projectiles
and mutable terrain. It does not establish complete mission fidelity.

A separate native player-command matrix covers 1,251 commands across all
52 maps, 139 actors and 25 unit types. Both engines accept all commands.
Commanded-unit physical state matches through cycle 100 on 43 maps and
through cycle 600 on three maps. Physical state means life/on-map state,
tile, absolute pixel anchor and HP; it excludes other world state and raw
order/sequence. A further 65 Human 1 scenarios match commanded-unit physical
state in 59 cases. Raw order/sequence differences remain in both matrices.
The current evidence and reproduction workflow are in
[the parity runbook](tools/bne-harness/PARITY.md).

## Playability and release verification

All 18 playability lanes pass with their required authenticated inputs,
including the 17-test control gate, 117 movement checks, 65 projectile
checks, 42 clean/adverse lockstep cases and real two-process startup.
The original 392-sequence freeze reproduction and the 45-type,
11,340-sequence roster complete without a stuck final withdrawal.
The 168 roster sequences containing invalid submarine-versus-land attacks
retain their explicit rejection classification; their final Move is still
checked. Each submarine also passes 252 sequences against legal naval targets.
These liveness sweeps are separate from exact native parity.

The integrated pack-plus-Opus suite contains 3,020 tests, with 93 existing
specification failures, no errors and 322 skips. Its data-free counterpart
has the same 88 expected failure identities and exactly 1,366 skips.
Expected failures still execute; an inventory pass is not an all-tests-pass
claim. The canonical authenticated CI job supplies the matching raw media
and requires its separate 27-skip profile. Coverage rules are documented in
[the development setup](docs/development-setup.md) and [CI guide](docs/ci.md).

Production game updates are published from `master`. The
[signed update catalog](https://updates.chonkbase.net/latest.properties) is
the authority for the current public version. Publication verifies installation
through the production launcher against both the built catalog and the public
endpoint. The source checkpoint branch is retained for comparison.

## Remaining fidelity work

- Exact everyday controls need broader coverage: group Follow redirects,
  queued orders, modifiers, congestion and visible-target attacks. The three
  retained right-click handler pairs satisfy only one of the 532 fixed
  transaction cells. Verified capture provenance is not content equality.
- Explicit Attack retains its quarry but later route refills still differ.
  Worker coverage must follow containment through resource return and credit;
  approach agreement alone does not certify the economic loop. Java accepts
  three empty-handed Return Goods requests that BNE ignores; actual UI
  reachability remains to be established.
- Native handler probes do not establish operating-system hit-testing or
  actual sound playback. A distant siege-target probe requires visibility
  validation before it can represent an ordinary player click.
- Multiplayer flight records retain map, cycle-zero save, accepted commands,
  controller/race state and build identities. Legacy recordings lack some of
  those seals and remain diagnostics, not complete fidelity certificates.

A passing playability gate and preserved campaign prefixes do not mean the
whole game is an exact recreation. Further changes must preserve those gates
while closing independently measured native command and lifecycle differences.
