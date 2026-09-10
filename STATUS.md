# Project status

ChonkCraft is a playable public beta. Campaigns, skirmishes, combat, economy,
construction, fog of war, computer opponents, spells, upgrades, sound, music,
save/load and lockstep multiplayer are implemented. All 52 campaign missions
load from an authenticated ChonkPack built from the player's original media.

Player orders retain committed native movement before their replacements
start. Attack and Move replacements also wait for the native attack callback;
explicit Attack keeps the clicked quarry through automatic scans. A replacement
received on the firing visit cancels the old attack's unlaunched shot; projectiles
already in flight still land. The dragon referees verify the replacement,
subsequent movement, first-hit timing and cancellation across save/load against
captured BNE cycles. The current controls include the issue-12 stall fixes, consumed and
superseded Stop handling, Patrol combat and return timing, Repair approach
construction, verified Attack target-retention cases and Patrol save/load state.
Follow redirects and repeated clicks retain their native movement and waiting
state across save/load. Melee pursuit resumes on the verified attack boundary;
wall clicks and mixed-selection Return Goods/Attack Ground enforce the native
player command rules. Multiplayer shares the host's speed and reports the
world cycle and peer involved in a desync.
They are integrated with the current fog, native walking animation, scrolling,
menu rendering, graphics-memory limits and multiplayer lobby fixes.

Ships and flyers retain their idle countdown and transport idle state through
save/load. Fourteen retail types complete five 800-cycle replay checks with
matching movement, both random streams and subsequent commanded siege damage.

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
state in 61 cases; all observed units also match in 61. Raw order/sequence differences remain in both matrices.
The maintained 121-case command gate protects every observed unit's seven
physical field prefixes and all 1,374 acceptance decisions, including nine
refusals. The current evidence and reproduction workflow are in
[the parity runbook](tools/bne-harness/PARITY.md).

## Playability and release verification

All 18 playability lanes pass with their required authenticated inputs,
including the 61-test control gate, 117 movement checks, 65 projectile
checks, 42 clean/adverse lockstep cases and real two-process startup.
The original 392-sequence freeze reproduction and the 45-type,
11,340-sequence roster complete without a stuck final withdrawal.
The 168 roster sequences containing invalid submarine-versus-land attacks
retain their explicit rejection classification; their final Move is still
checked. Each submarine also passes 252 sequences against legal naval targets.
These liveness sweeps are separate from exact native parity.

The integrated pack-plus-Opus suite contains 3,058 tests, with 90 existing
specification failures, no errors and 318 skips. Its data-free counterpart
has the same 88 expected failure identities and exactly 1,403 skips.
Expected failures still execute; an inventory pass is not an all-tests-pass
claim. The canonical authenticated CI job supplies the matching raw media
and requires its separate 27-skip profile. Coverage rules are documented in
[the development setup](docs/development-setup.md) and [CI guide](docs/ci.md).

Both demolition squads accept targeted commands, approach the selected point
or unit, and apply the BNE blast. Native captures cover interrupted approaches,
rapid retargeting, blast damage and forest clearing. The control gate also
checks Move/Stop for 52 mobile types, Attack/retarget for the 43 types with
native Attack buttons, and effects for all 50 unit/ability combinations.
These checks establish command liveness; complete per-unit timing remains
precision work.

Workers keep their completed cargo when redirected between mines and trees.
Unfinished chopping is cleared by a new harvesting job, and the gold loop
retains the native depot-exit pause.
Production game updates are published from `master`. The
[signed update catalog](https://updates.chonkbase.net/latest.properties) is
the authority for the current public version. Publication verifies installation
through the production launcher against both the built catalog and the public
endpoint. The source checkpoint is retained in Git for comparison.

## Remaining fidelity work

- Stop and point-order startup still need the wider native timing corrections.
  That candidate remains withheld because it regresses command-campaign
  comparisons; the released Attack/Move fix does not establish complete
  command parity.
- Saving during active combat can still change later projectile behavior and
  damage rolls. The lost naval/flying idle countdown is fixed; the command and
  cancellation referees preserve their observed handoffs and first-hit timing;
  they do not establish complete combat-state continuation.
- Exact everyday controls need broader coverage: group Follow redirects,
  queued orders, modifiers, congestion and visible-target attacks. Eight new
  authenticated handler pairs cover Follow and combat, but the 532-cell
  transaction matrix remains incomplete. Verified capture provenance is not
  content equality.
- Explicit Attack retains its quarry but later route refills still differ.
  Worker coverage must follow containment through resource return and credit;
  approach agreement alone does not certify the economic loop. Return Goods
  eligibility and the player gold-loop depot exit now agree with BNE. Six
  fresh 1,500-cycle cases cover repeated credit and standing or moving allies
  obstructing the route. Wood, oil, depleted resources and denser congestion
  still need the same complete-loop coverage. Loaded resource clicks and
  interrupted chopping now have native witnesses; complete wood-loop timing
  and blocked forest destinations still differ.
- Native handler probes do not establish operating-system hit-testing or
  actual sound playback. A distant siege-target probe requires visibility
  validation before it can represent an ordinary player click.
- Multiplayer flight records retain map, cycle-zero save, accepted commands,
  controller/race state and build identities. Legacy recordings lack some of
  those seals and remain diagnostics, not complete fidelity certificates.

A passing playability gate and preserved campaign prefixes do not mean the
whole game is an exact recreation. Further changes must preserve those gates
while closing independently measured native command and lifecycle differences.
