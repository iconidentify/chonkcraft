# BNE gameplay readiness

Generated from `tools/bne-readiness/readiness.json` by
`scripts/check-bne-readiness.py`. Do not edit this report directly.

Authority: **Warcraft II Battle.net Edition 2.02b retail**.

This report distinguishes two finish lines. A green subsystem says its complete
player-visible loop is certified and playable from the retail pack; it does not
say every internal cycle and route already matches the Windows binary exactly.
The exact-fidelity frontier remains a separate, stricter proof.

## Two certification tracks

| Track | Status | What it answers | Finish condition |
|---|---|---|---|
| Playability | CERTIFIED | Can a player complete the real game loops with retail data and no retired scripting language/ChonkCraft runtime? | Every subsystem player/referee gate passes from the authenticated Chonkpack with zero skipped checks. |
| Exact BNE fidelity | IN-PROGRESS | Does every observed scheduler, route, state and pixel decision match retail BNE exactly? | All 52 authenticated campaign fixtures remain identical through 1,800 cycles and every retained precision boundary is closed. |

## Summary

| Grade | Systems | Meaning |
|---|---:|---|
| Green | 18 | Retail behavior understood and the differential proof passes. |
| Yellow | 0 | Mapped and exercised, with known discrepancies or missing proof. |
| Orange | 0 | Implemented primarily from LegacyEngine and not established against retail. |
| Red | 0 | Disabled, absent, or unable to certify a playable loop. |

## System matrix

| System | Grade | Automated player/referee | Blocking fact |
|---|---|---|---|
| Boot, retail data and assets | GREEN | The release builder packages one game JAR and boots it in isolation against the authenticated full-media BNE pack. | No blocking fact recorded. |
| Simulation scheduler and deterministic RNG | GREEN | Two authenticated retail-data worlds consume the same movement and combat commands for a complete 1,800-cycle match while both RNG streams and all hashed simulation state are compared every tick. | No blocking fact recorded. |
| Movement and pathfinding | GREEN | Player wire commands move retail land, naval and air units through authenticated maps and unit data. | No blocking fact recorded. |
| Orders and attack-move | GREEN | A scripted commander drives both authenticated retail units and a controlled obstruction referee through the wire command seam, while the legacy diagnostic inventory is checked fail-closed. | No blocking fact recorded. |
| Idle acquisition and target selection | GREEN | A retail footman guards its post against eligible and ineligible contacts while native-shaped targeting checks and legacy diagnostics run fail-closed. | No blocking fact recorded. |
| Harvesting and resource economy | GREEN | A scripted worker is sent to gold and wood and the referee observes approach, work, return and player credit. | No blocking fact recorded. |
| Construction, placement and production | GREEN | Player wire commands build the retail-data production chain, train its unit and research its first weapon upgrade. | No blocking fact recorded. |
| Combat, damage and death | GREEN | Player wire orders drive focused retail fights and a 120-unit mixed battle with no director reissuing targets. | No blocking fact recorded. |
| Projectiles and combat feedback | GREEN | A retail-data firer launches arrows and boulders while the referee follows native construction, pool order, integer flight, parabolic frame phases, impact damage, art and screen pixels. | No blocking fact recorded. |
| Naval movement and oil economy | GREEN | Player commands drive ships along a retail coast and through the complete platform, loading and refinery loop while a terrain referee checks every anchor. | No blocking fact recorded. |
| Spells and magical effects | GREEN | A scripted caster spends mana on legal targets while the referee observes missiles, delays and effects. | No blocking fact recorded. |
| Retail ai.bin computer player | GREEN | The harness supplies only the human opening while the retail ai.bin interpreter must operate the opponent. | No blocking fact recorded. |
| Campaign missions and triggers | GREEN | A headless referee runs every retail wrapper while focused commanders satisfy and violate representative campaign conditions. | No blocking fact recorded. |
| Save, load and terrain persistence | GREEN | The harness changes terrain and live state, saves, reloads and resumes the same simulation. | No blocking fact recorded. |
| Rendering, UI and player input | GREEN | The desktop test driver issues real command-panel and map interactions, renders deterministic frames and activates the one-action playtest evidence shortcut. | No blocking fact recorded. |
| Sound bindings and playback policy | GREEN | A worker completes a player-issued build order while the referee follows its sound event through the script-free retail bindings and real mixer. | No blocking fact recorded. |
| End-to-end player control liveness | GREEN | A read-only outcome referee drives authenticated units through one-, three- and nine-unit controls locally and across two real UDP peers. | No blocking fact recorded. |
| Multiplayer lockstep | GREEN | Independent UDP peers play a long match over both a clean link and a deterministic adverse link. | No blocking fact recorded. |

## Boot, retail data and assets

Grade: **GREEN**.

Automated driver: The release builder packages one game JAR and boots it in isolation against the authenticated full-media BNE pack.

Success means: The shipped artifacts pass hash-safe installation and boot the retail roster, UI, tech tree, four campaigns and all 52 missions without a developer checkout.

Implementation:

- `engine/src/main/java/net/chonkbase/chonkcraft/engine/GameData.java`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/map/TilesetCatalog.java`
- `engine/src/main/resources/chonkcraft/tilesets.tsv`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/unit/UnitTypeCatalog.java`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/animation/AnimationCatalog.java`
- `data/src/main/java/net/chonkbase/chonkcraft/data/map/PudReader.java`
- `scripts/release/build-update-assets.sh`
- `launcher/src/main/java/net/chonkbase/chonkcraft/launcher/GameReleaseManager.java`

Automated checks:

- `engine/src/test/java/net/chonkbase/chonkcraft/engine/StandaloneBootTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/unit/NativeRosterRealDataTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/map/MapRenderRealDataTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/campaign/CampaignRealDataTest.java`
- `launcher/src/test/java/net/chonkbase/chonkcraft/launcher/GameReleaseManagerTest.java`
- `scripts/check-bne-boot-data-gate.sh`

Retail evidence:

- Retail PUD maps and Blizzard archive entries are read directly from the authenticated BNE chonkpack.
- The authenticated BNE chonkpack and game JAR boot the complete roster, command panel, technology tree, four campaigns and all 52 missions.
- The release builder emits a hashed game JAR; the launcher validates its hash and atomically promotes the installed version without a content archive.
- The 52 sealed campaign fixtures prove every retail campaign can be constructed and retain their accepted initial state.

Known blockers:

- None recorded.

Recheck command:

```text
scripts/check-bne-boot-data-gate.sh
```

## Simulation scheduler and deterministic RNG

Grade: **GREEN**.

Automated driver: Two authenticated retail-data worlds consume the same movement and combat commands for a complete 1,800-cycle match while both RNG streams and all hashed simulation state are compared every tick.

Success means: Both worlds move, fight and launch projectiles yet retain identical world hashes, synchronized RNG and asynchronous BNE RNG for all 1,800 cycles; legacy movement diagnostics add no unknown failures.

Implementation:

- `engine/src/main/java/net/chonkbase/chonkcraft/engine/World.java`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/CausalCallsite.java`

Automated checks:

- `engine/src/test/java/net/chonkbase/chonkcraft/engine/BattleNetSchedulerPlayabilityTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/SimulationTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/missile/MissileDeterminismTest.java`
- `tools/bne-readiness/scheduler-diagnostics.json`

Retail evidence:

- Authenticated async and synchronized RNG ledgers compare native and Java draws.
- The accepted 52-case frontier is a strict whole-world scheduler and stream regression gate.
- Two independent worlds using authenticated retail archer, grunt, missile and animation data consume the same three-command script for 1,800 cycles while their complete SyncHash, synchronized seed/draw count and independent BNE asynchronous seed/draw count agree after every tick.
- Four missile determinism checks prove integer flight and replay stability; 19 generic grid/simulation checks pass, while three explicit LegacyEngine logical-tile assertions are retained in a fail-closed provenance catalog owned by the GREEN BNE movement lane.

Known blockers:

- None recorded.

Recheck command:

```text
scripts/check-bne-scheduler-gate.sh
```

## Movement and pathfinding

Grade: **GREEN**.

Automated driver: Player wire commands move retail land, naval and air units through authenticated maps and unit data.

Success means: Real units complete formation detours and terrain-domain passages while 115 large-footprint, congestion, command-handoff and refusal checks pass with zero skips.

Implementation:

- `engine/src/main/java/net/chonkbase/chonkcraft/engine/BattleNetMovementSystem.java`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/pathfinder/BattleNetPathFinder.java`

Automated checks:

- `engine/src/test/java/net/chonkbase/chonkcraft/engine/BattleNetMovementPlayabilityTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/BattleNetNavalLegalityRealDataTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/TransportUnloadTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/ShoreBuildingTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/NavalPatrolCoastGoalRealDataTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/pathfinder/BattleNetPathFinderTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/BattleNetWallFollowBoundsTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/RefusedStepTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/BattleNetRefusalSleepTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/BattleNetChaseRefusalTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/BattleNetSeaOccupancyTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/PathAroundUnitsTest.java`
- `scripts/check-bne-movement-gate.sh`

Retail evidence:

- The retail-data player referee sends real footman, destroyer and gryphon move commands through CommandApplier and observes friendly-formation routing, land/naval/air terrain separation and completed orders.
- An authenticated gryphon commanded to Move during its committed attack keeps the unbreakable BNE order head until the animation releases, then completes Move and damages a second target after a new Attack without an intervening Stop.
- The movement gate runs 115 focused checks with zero skips. Its naval referee records every commanded ship anchor's visual tile and raw terrain flags; transport coast permission is explicit while destroyers and tankers remain water-only.
- Large two-tile footprints, allied congestion, route exhaustion, chase refusal, the retail eight-refusal hold and fifteenth-refusal reset are all executable gate requirements rather than untracked edge notes.

Known blockers:

- None recorded.

Recheck command:

```text
scripts/check-bne-movement-gate.sh
```

## Orders and attack-move

Grade: **GREEN**.

Automated driver: A scripted commander drives both authenticated retail units and a controlled obstruction referee through the wire command seam, while the legacy diagnostic inventory is checked fail-closed.

Success means: A retail footman detects and kills a retail grunt before the destination, resumes and completes; an obstructed command recovers without reissue; visible-wall bombardment selects combat; all legacy failures are explicitly classified with zero new names.

Implementation:

- `engine/src/main/java/net/chonkbase/chonkcraft/engine/World.java`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/BattleNetMovementSystem.java`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/network/CommandApplier.java`
- `desktop/src/main/java/net/chonkbase/chonkcraft/desktop/GameScreen.java`

Automated checks:

- `engine/src/test/java/net/chonkbase/chonkcraft/engine/BattleNetAttackMovePlayabilityTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/BattleNetAttackMoveRetailPlayabilityTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/AttackMoveTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/BlockedStepTest.java`
- `desktop/src/test/java/net/chonkbase/chonkcraft/desktop/PlayerOrderDeliveryTest.java`
- `desktop/src/test/java/net/chonkbase/chonkcraft/desktop/AcknowledgeOnceTest.java`
- `desktop/src/test/java/net/chonkbase/chonkcraft/desktop/RightClickTableTest.java`
- `tools/bne-readiness/attack-move-diagnostics.json`

Retail evidence:

- Focused refusal, chase, arrival and saved-order captures cover individual retail transitions.
- The retail 0x0044fbd0 route generator and its 0x004376c0 caller establish that chase arrival is a caller-level PF_REACHED transition after the stored route is consumed.
- The pinned BNE 2.02b 0x0040a830 acquisition driver passes a selected unit directly to 0x004513d0, which stores the live target at unit+0x88 and its current position at unit+0x84 before selecting the attack action.
- BNE's flush-on command boundary keeps a committed unbreakable step or swing current and promotes its queued replacement only after the animation releases; a real mixed footman, archer and knight selection is repeatedly re-commanded against a live construction site and no member may silently fall to Still.
- Every immediate UI order now reports the authoritative CommandApplier result, so rejected repair, return, ground-attack, unload, board, autocast and rally commands cannot produce a false acknowledgement.
- An authenticated retail footman and grunt now exercise the complete command-level interrupt, lethal-hit and resume loop through CommandApplier; a separate synthetic referee covers temporary refusal and visible-wall bombardment.
- All 65 upstream AttackMoveTest diagnostics are inventoried on every gate run: the 24 remaining names are explicitly classified as LegacyEngine COrder/SavedOrder, refusal-timing, HitUnit_AttackBack or precision-owned hypotheses, and any new name or error fails closed.

Known blockers:

- None recorded.

Recheck command:

```text
scripts/check-bne-attack-move-gate.sh
```

## Idle acquisition and target selection

Grade: **GREEN**.

Automated driver: A retail footman guards its post against eligible and ineligible contacts while native-shaped targeting checks and legacy diagnostics run fail-closed.

Success means: The footman rejects air, acquires and kills adjacent ground combat without leaving its post; 29 focused checks pass and every remaining diagnostic has an explicit provenance owner.

Implementation:

- `engine/src/main/java/net/chonkbase/chonkcraft/engine/BattleNetIdleSystem.java`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/BattleNetTargetSelection.java`

Automated checks:

- `engine/src/test/java/net/chonkbase/chonkcraft/engine/BattleNetIdleTargetingPlayabilityTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/BattleNetIdleAttackTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/TargetChoiceTest.java`
- `tools/bne-readiness/idle-targeting-diagnostics.json`

Retail evidence:

- Native idle-dispatch, acquisition cadence and target-score captures exist for accepted frontier blockers.
- The pinned BNE 0x0040a4b0 scorer, retail UDTA priorities, native spatial tie order, action-16 stationary acquisition and land/air eligibility are covered by 25 passing native-shaped checks.
- An authenticated retail footman remains idle beside an ineligible hostile balloon, then acquires and kills an adjacent retail grunt without abandoning its post.
- The six remaining diagnostic names are executable provenance: five explicitly specify the superseded ChonkCraft/LegacyEngine chooser and one is a three-visit campaign timing hypothesis owned by the 52-case precision net.

Known blockers:

- None recorded.

Recheck command:

```text
scripts/check-bne-idle-targeting-gate.sh
```

## Harvesting and resource economy

Grade: **GREEN**.

Automated driver: A scripted worker is sent to gold and wood and the referee observes approach, work, return and player credit.

Success means: Player and AI workers complete repeated gold and wood round trips through congestion, obey the retail refusal machine and bank exact resources with zero skipped checks.

Implementation:

- `engine/src/main/java/net/chonkbase/chonkcraft/engine/BattleNetHarvestSystem.java`

Automated checks:

- `engine/src/test/java/net/chonkbase/chonkcraft/engine/HarvestRoundTripTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/BattleNetResourceApproachTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/HarvestTargetTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/HarvesterFacingTest.java`
- `desktop/src/test/java/net/chonkbase/chonkcraft/desktop/PlayerOrderDeliveryTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/EconomyTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/RefusedStepTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/BattleNetRefusalSleepTest.java`

Retail evidence:

- Native resource-approach, refusal, wait-band and delivery captures have closed multiple frontier families.
- Pinned BNE 2.02b code at 0x00424221 turns a terrain harvester toward its resource point before work; all eight adjacent approaches and the 52-campaign invariant sweep enforce the same direction-byte transition.
- A real right click through the desktop command seam installs BNE's harvest order, produces one truthful acknowledgement and must remain a harvest until the peasant reaches the actual wood-work state.
- Pinned BNE 2.02b function 0x004379e0 establishes route parking on refusals one through seven, the fifteen-count eighth-refusal hold and the fifteenth-refusal reset; selected focused checks exercise those final rules rather than the older deleted compass-detour invention.
- Authenticated campaign data drives complete player and AI gold round trips, while the synthetic economy referee proves repeated gold and wood work, exact banking, depletion, congestion and non-duplication.

Known blockers:

- None recorded.

Recheck command:

```text
scripts/run-tests.sh -pl engine,desktop -am -Dtest=PlayerOrderDeliveryTest,HarvestTargetTest,HarvesterFacingTest,HarvestRoundTripTest,EconomyTest,RefusedStepTest,BattleNetRefusalSleepTest,BattleNetResourceApproachTest#adjacentGoldPeasantStepsOntoApproachPoint+firstWoodChopDrawsOneSyncRand+secondWoodcutterReaimsWhenTreeIsClaimed+aSecondPeonMayStackOnAMineApproachTileOccupiedByAnAlly+aMuchRefusedGoldPeonStillOwesAVisitBeforeTheDetour+aWorkerThatDroppedItsLeftoverStillNamesTheSquareItWasGoingTo+aGoldRouteWillNotCrossAFriendCarryingRefusals+goldResidualSettleGivesTheRouteUpOnTheCycleItLands -Dsurefire.failIfNoSpecifiedTests=false
```

## Construction, placement and production

Grade: **GREEN**.

Automated driver: Player wire commands build the retail-data production chain, train its unit and research its first weapon upgrade.

Success means: Placement is legal, every cost is paid once, completed buildings return the worker, the trained unit enters play and completed research improves it.

Implementation:

- `engine/src/main/java/net/chonkbase/chonkcraft/engine/BattleNetConstructionSystem.java`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/BattleNetBuildingPlacement.java`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/construction/ConstructionCatalog.java`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/upgrade/UpgradeCatalog.java`

Automated checks:

- `engine/src/test/java/net/chonkbase/chonkcraft/engine/ConstructionTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/BattleNetBuildingPlacementTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/BattleNetTrainWorkerTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/BattleNetProductionPlayabilityTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/ContainerDeathTest.java`
- `desktop/src/test/java/net/chonkbase/chonkcraft/desktop/PlayerOrderDeliveryTest.java`
- `desktop/src/test/java/net/chonkbase/chonkcraft/desktop/ResearchCatalogWiringTest.java`
- `desktop/src/test/java/net/chonkbase/chonkcraft/desktop/UnaffordableButtonTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/construction/ConstructionCatalogTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/construction/NativeConstructionRealDataTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/upgrade/NativeTechnologyRealDataTest.java`

Retail evidence:

- Retail placement order, worker training cadence, construction transitions and the production debit points have focused BNE tests.
- Every research button is exhaustively joined to a priced BNE upgrade, its researcher relationship and its command roster entry; every production world mode uses the same catalog bootstrap and local UI feedback follows the authoritative command result.
- A mixed authenticated BNE squad can target and destroy an under-construction building through the real desktop command seam; its contained builder is released and the site emits the building-death event.
- The zero-skip player/referee lane sends fixed-width player commands through the retail-data roster to build a farm, barracks and blacksmith, train a footman, research sword one, verify each debit and observe the trained unit's damage increase.
- All 12 construction sequences were compared field-for-field against the legacy declarations under all four tilesets; every referenced scaffolding sheet resolves from the authenticated pack without a script tree.

Known blockers:

- None recorded.

Recheck command:

```text
scripts/run-tests.sh -pl engine,desktop -am -Dtest=ConstructionTest,BattleNetBuildingPlacementTest,BattleNetTrainWorkerTest,BattleNetProductionPlayabilityTest,ContainerDeathTest,PlayerOrderDeliveryTest,ConstructionCatalogTest,NativeConstructionRealDataTest,NativeTechnologyRealDataTest,ResearchCatalogWiringTest,UnaffordableButtonTest -Dsurefire.failIfNoSpecifiedTests=false
```

## Combat, damage and death

Grade: **GREEN**.

Automated driver: Player wire orders drive focused retail fights and a 120-unit mixed battle with no director reissuing targets.

Success means: Melee, ranged, splash and retaliation scenarios terminate correctly, and every 500-cycle window of the undirected mass battle makes physical damage or death progress while both armies remain alive.

Implementation:

- `engine/src/main/java/net/chonkbase/chonkcraft/engine/BattleNetCombatSystem.java`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/BattleNetTargetSelection.java`

Automated checks:

- `engine/src/test/java/net/chonkbase/chonkcraft/engine/BattleNetCombatPlayabilityTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/ContainerDeathTest.java`
- `desktop/src/test/java/net/chonkbase/chonkcraft/desktop/PlayerOrderDeliveryTest.java`
- `desktop/src/test/java/net/chonkbase/chonkcraft/desktop/BattleShowcaseTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/CombatTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/CombatFeedbackTest.java`

Retail evidence:

- Native damage callsites, attack opcodes, asynchronous RNG draws, spatial-help reactions and causal projectile events have authenticated captures.
- The retail-data referee sends real footman, archer and ballista orders through CommandApplier and observes committed melee, projectile flight, splash target filtering, hit-point loss and death.
- The command referee rejects incompatible BNE orders instead of claiming success, and a repeatedly commanded mixed squad retains one live construction-site target until it is destroyed.
- A 120-unit mixed retail battle receives exactly one wire command per unit and no director corrections; all six consecutive 500-cycle windows produce damage or death, every order reaches a physical/combat checkpoint, at least 100 units move pixels and at least 60 die.
- Synthetic formula and feedback controls cover armour, piercing damage, minimum range, footprint splash, retaliation offers, score attribution, damage text, burning and corpse cleanup.

Known blockers:

- None recorded.

Recheck command:

```text
scripts/run-tests.sh -pl engine,desktop -am -Dtest=BattleNetCombatPlayabilityTest,ContainerDeathTest,PlayerOrderDeliveryTest,BattleShowcaseTest,CombatTest,CombatFeedbackTest -Dsurefire.failIfNoSpecifiedTests=false
```

## Projectiles and combat feedback

Grade: **GREEN**.

Automated driver: A retail-data firer launches arrows and boulders while the referee follows native construction, pool order, integer flight, parabolic frame phases, impact damage, art and screen pixels.

Success means: Every projectile visibly leaves its source, preserves its retail launch facing, advances on the retail beat, draws the native arc frames, applies its complete transient or persistent effect once and renders flight, impact and green-cross feedback across 63 zero-skip checks.

Implementation:

- `engine/src/main/java/net/chonkbase/chonkcraft/engine/BattleNetProjectileSystem.java`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/missile/Missile.java`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/missile/MissileCatalog.java`

Automated checks:

- `engine/src/test/java/net/chonkbase/chonkcraft/engine/BattleNetProjectilePoolOrderTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/missile/BattleNetMissileMotionTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/missile/NativeMissileRealDataTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/CombatFeedbackTest.java`
- `desktop/src/test/java/net/chonkbase/chonkcraft/desktop/MissileRenderingTest.java`
- `desktop/src/test/java/net/chonkbase/chonkcraft/desktop/ImpactRenderingTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/ClickMarkerTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/PresentationAheadProjectilePrepareTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/RareSpellBehaviorTest.java`
- `scripts/check-bne-projectile-gate.sh`

Retail evidence:

- Projectile pool order, constructor RNG, impact delay and integer direction stepping have dedicated ledgers and authenticated captures.
- Pinned BNE 2.02b constructors write facing at projectile +0x0a once; point action 0x004101f0 and parabolic action 0x00410260 never rewrite it. An efficacy-proved rendered-frame regression fails on the old alternating shallow-arrow sprite and passes when launch facing is preserved.
- Pinned BNE 2.02b function 0x00410260 advances the parabolic arc accumulator at projectile +0x24, divides by the stride at +0x26 and selects flattened frames 0,5,10,5,0 from table 0x0049067c without adding a fake height coordinate; the zero-skip player/referee lane proves those phases, post-zero impact timing, visible flight and visible impact art.
- All 35 retail missile declarations are loaded. Land mines now persist until triggered or expired, Whirlwinds move and pulse, Flame Shields orbit and pulse without striking their protected center, and Death Coil returns life; their live state also survives save/load.
- The retail green-cross map confirmation is a first-class projectile lifecycle: the issued location is retained, its visible cycles are counted, and it is covered by the same 63-check zero-skip referee.

Known blockers:

- None recorded.

Recheck command:

```text
scripts/check-bne-projectile-gate.sh
```

## Naval movement and oil economy

Grade: **GREEN**.

Automated driver: Player commands drive ships along a retail coast and through the complete platform, loading and refinery loop while a terrain referee checks every anchor.

Success means: Transports, warships and tankers obey their distinct BNE coast/water domains; unloading and shore placement work; player and AI oil loops complete and credit the correct player exactly once with zero skipped checks.

Implementation:

- `engine/src/main/java/net/chonkbase/chonkcraft/engine/BattleNetMovementSystem.java`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/BattleNetHarvestSystem.java`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/BattleNetConstructionSystem.java`

Automated checks:

- `engine/src/test/java/net/chonkbase/chonkcraft/engine/OilPlatformTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/TankerRoundTripTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/BattleNetNavalLegalityRealDataTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/TransportUnloadTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/ShoreBuildingTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/NavalPatrolCoastGoalRealDataTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/NavalAirTest.java`

Retail evidence:

- Native tanker boarding, platform approach, coastal patrol and sea occupancy captures establish the two-tile naval movement lattice and the retail route-buffer behavior.
- Authenticated campaign data drives player-issued platform construction, tanker loading, return-to-shipyard banking and refinery income checks; the synthetic referee separately proves contained-tanker reachability, legal sea occupancy and the complete pump-and-bank loop.
- Human 5 player commands drive a transport, destroyer and tanker while a cycle referee checks the authenticated visual tile and raw BNE terrain word at every anchor; a large sprite overlapping shore art is not confused with an illegal land anchor.
- Transport unloading, shore-building placement, coast patrol and naval/air domain tests all run from the Chonkpack alone with zero skips.

Known blockers:

- None recorded.

Recheck command:

```text
scripts/run-tests.sh -pl engine -am -Dtest=OilPlatformTest,TankerRoundTripTest,BattleNetNavalLegalityRealDataTest,TransportUnloadTest,ShoreBuildingTest,NavalPatrolCoastGoalRealDataTest,NavalAirTest -Dsurefire.failIfNoSpecifiedTests=false
```

## Spells and magical effects

Grade: **GREEN**.

Automated driver: A scripted caster spends mana on legal targets while the referee observes missiles, delays and effects.

Success means: The pinned retail dispatch and rare-handler code shapes authenticate the implementation; all generated effects are explicitly modelled, spend the declared cost and pass 36 behavior checks with zero skips.

Implementation:

- `engine/src/main/java/net/chonkbase/chonkcraft/engine/spell/Spell.java`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/spell/SpellCatalog.java`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/World.java`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/missile/Missile.java`

Automated checks:

- `engine/src/test/java/net/chonkbase/chonkcraft/engine/spell/OffensiveSpellTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/spell/SpellRealDataTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/SpellCastingTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/SpellBuffTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/RareSpellBehaviorTest.java`
- `tools/bne-readiness/spell-dispatch.json`
- `tools/bne-readiness/check_spell_dispatch.py`
- `scripts/check-bne-spell-gate.sh`

Retail evidence:

- The official Warcraft II Battle.net Edition manual describes Blizzard and Death and Decay as damaging persistent area spells, while the pinned retail executable carries distinct Blizzard/fireball graphics and spell sound assets.
- The authenticated 2.02b dispatch table maps all 19 spell orders to their retail handlers. Code-slice proofs establish one Fireball constructor, five Blizzard and five Death-and-Decay field constructors, ten delayed successors per field and two retail random draws selecting every field in a five-wide patch.
- The pinned Exorcism handler reaches retail HitUnit and its 0x0040a9d0 offer boundary before damage; the Java direct-spell path now uses that same offer-and-wait policy instead of layering a second flee/attack-back tail on top.
- Authenticated handler slices prove Polymorph's critter conversion, Eye of Kilrogg's summoned unit type and Unholy Armor's 500-cycle duration plus hit-point halving.
- The player/referee lane proves all previously unmodelled rare effects, true position-target commands, fireball, life-returning Death Coil, persistent Flame Shield, Whirlwind and Runes, delayed Blizzard, death and decay, demolish, targeted casting, self casting and mana exhaustion for 36 passing checks and zero skips.

Known blockers:

- None recorded.

Recheck command:

```text
scripts/check-bne-spell-gate.sh
```

## Retail ai.bin computer player

Grade: **GREEN**.

Automated driver: The harness supplies only the human opening while the retail ai.bin interpreter must operate the opponent.

Success means: One independently observed opponent gathers and banks resources, builds and trains, spends, receives native behavior-two force assignment and sustains an attack without scripted opponent commands; a late six-member campaign wave crosses the map and every member engages; the full authenticated sample stays active and error-free.

Implementation:

- `engine/src/main/java/net/chonkbase/chonkcraft/engine/ai/BattleNetAiBytecode.java`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/ai/AiPlayer.java`

Automated checks:

- `engine/src/test/java/net/chonkbase/chonkcraft/engine/ai/BattleNetAiBytecodeTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/ai/BattleNetAiForcePredicateTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/ai/BattleNetAiRetailDataTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/ai/AiTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/perf/AiCompetenceTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/BattleNetHumanFiveGuardBehaviorRealDataTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/BattleNetAiPatrolLivenessTest.java`
- `scripts/check-bne-ai-gate.sh`

Retail evidence:

- Rez/ai.bin, its four opcodes, 32-bit scheduler, eight predicates and per-cycle caller have been transcribed from the pinned retail executable.
- An authenticated retail snapshot of runtime unit-type flags proves the exact ground, naval and air combat rosters used by predicates 4, 5 and 6.
- The pinned 0x00426ad0/0x0044c260 control flow proves the three pending launch bytes, group sizes/counts, behavior-two assignment and fifty-cycle consumer now used by Java.
- The authenticated campaign referee executes twenty computer slots with no opponent commands. On the same independently measured slot it observes harvest/return orders, real treasury credits, production and spending, native behavior-two force assignment and sustained attack orders.
- Human 5's authenticated 1,800-cycle behavior keeps four authored base guards posted while all five members of a separate grunt/axethrower field squad engage; the pack-only AI gate prevents a tempting global wake-up regression.
- A five-minute Human expansion 10 referee follows the six-member first behavior-two wave from its retail ai.bin launch through the long campaign route and requires every survivor to move and enter real combat.

Known blockers:

- None recorded.

Recheck command:

```text
scripts/check-bne-ai-gate.sh
```

## Campaign missions and triggers

Grade: **GREEN**.

Automated driver: A headless referee runs every retail wrapper while focused commanders satisfy and violate representative campaign conditions.

Success means: All 52 wrappers run without script faults or premature outcomes, real victory and defeat conditions decide correctly, rescue paths work and every campaign has a complete ending.

Implementation:

- `engine/src/main/java/net/chonkbase/chonkcraft/engine/campaign/Campaign.java`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/trigger/TriggerSystem.java`

Automated checks:

- `engine/src/test/java/net/chonkbase/chonkcraft/engine/campaign/CampaignTriggerPlayabilityTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/campaign/CampaignRealDataTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/campaign/CampaignEndingTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/campaign/TriggerFailureTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/campaign/RescueTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/campaign/AttackPeasantTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/campaign/MissionLengthTest.java`

Retail evidence:

- All 52 retail mission wrappers arm 137 trigger pairs; a fail-closed referee now constructs every map, runs every wrapper for 30 simulated seconds and rejects script faults or premature outcomes.
- Real campaign data proves both sides of the first mission's condition, all four campaigns' closing sequences, the Human 10 wrapper substitution and the rescue conditions that are the sole victory path of nine missions.
- Authenticated BNE fixture evidence establishes per-unit rescue at the native animation marker, including a flying prisoner, rather than LegacyEngine's once-per-second whole-player town-hall shortcut.

Known blockers:

- None recorded.

Recheck command:

```text
scripts/run-tests.sh -pl engine -am -Dtest=CampaignTriggerPlayabilityTest,CampaignRealDataTest,CampaignEndingTest,TriggerFailureTest,RescueTest,AttackPeasantTest,MissionLengthTest -Dsurefire.failIfNoSpecifiedTests=false
```

## Save, load and terrain persistence

Grade: **GREEN**.

Automated driver: The harness changes terrain and live state, saves, reloads and resumes the same simulation.

Success means: Terrain, units, resources, orders, missiles and AI state survive without changing subsequent deterministic behavior.

Implementation:

- `engine/src/main/java/net/chonkbase/chonkcraft/engine/save/SaveGame.java`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/save/LoadGame.java`

Automated checks:

- `engine/src/test/java/net/chonkbase/chonkcraft/engine/save/SaveGameTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/save/SavedTerrainTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/save/NativeSaveReaderTest.java`

Retail evidence:

- The versioned native save schema retains changed terrain and live simulation state without executable script reconstruction.
- Authenticated BNE data drives full live round trips, including the independent asynchronous LCG, complete retail projectile state and the native ai.bin program counter plus 48-byte state.
- A restored in-flight catapult rock is run beside the original through impact, while assembled forces and both inherited AiLoop indices resume without restarting the opponent.

Known blockers:

- None recorded.

Recheck command:

```text
scripts/run-tests.sh -pl engine -am -Dtest=SaveGameTest,SavedTerrainTest,NativeSaveReaderTest -Dsurefire.failIfNoSpecifiedTests=false
```

## Rendering, UI and player input

Grade: **GREEN**.

Automated driver: The desktop test driver issues real command-panel and map interactions, renders deterministic frames and activates the one-action playtest evidence shortcut.

Success means: Every world object is represented, player commands enter CommandApplier, rendering never mutates simulation state and one shortcut produces a screenshot/save/forensic packet with visible confirmation and zero skips.

Implementation:

- `desktop/src/main/java/net/chonkbase/chonkcraft/desktop/GameScreen.java`
- `desktop/src/main/java/net/chonkbase/chonkcraft/desktop/PlaytestEvidence.java`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/ui/ButtonSet.java`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/ui/UiLayout.java`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/ui/PlayerColours.java`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/ui/FogOfWarSettings.java`

Automated checks:

- `engine/src/test/java/net/chonkbase/chonkcraft/engine/ViewThreadReadsTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/ui/NativeInterfaceRealDataTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/ui/NativeTitleRealDataTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/ui/NativePresentationSettingsTest.java`
- `desktop/src/test/java/net/chonkbase/chonkcraft/desktop/RenderingTruthTest.java`
- `desktop/src/test/java/net/chonkbase/chonkcraft/desktop/SelectionChangeTest.java`
- `desktop/src/test/java/net/chonkbase/chonkcraft/desktop/AcknowledgeOnceTest.java`
- `desktop/src/test/java/net/chonkbase/chonkcraft/desktop/RightClickTableTest.java`
- `desktop/src/test/java/net/chonkbase/chonkcraft/desktop/PlayerOrderDeliveryTest.java`
- `desktop/src/test/java/net/chonkbase/chonkcraft/desktop/UnaffordableButtonTest.java`
- `desktop/src/test/java/net/chonkbase/chonkcraft/desktop/HotkeyBindingTest.java`
- `desktop/src/test/java/net/chonkbase/chonkcraft/desktop/GameMenuTest.java`

Retail evidence:

- Retail BNE art, campaign maps and interface definitions drive zero-skip field, minimap and command-panel fixtures from the shipped asset pack.
- The rendering sweep accounts for living and dying units, corpses, rubble, construction, missiles, burning buildings, spells, decorations and remembered buildings; withholding each object must change the expected pixels.
- Real mouse and command-panel interactions reach CommandApplier, while a complete serialized world is byte-identical before and after repeated field and side-panel paints.
- Authenticated right-click and aimed-order gates require every capable selected unit to receive the order, rejected orders to stay silent and one group command to produce exactly one acknowledgement.
- Command-Shift-E on macOS (Ctrl-Shift-E elsewhere) atomically captures a visible screenshot, resumable native save and JSON containing nearby units/orders/targets, missiles, both RNG streams and raw visual terrain codes; the screen confirms the packet name.

Known blockers:

- None recorded.

Recheck command:

```text
scripts/run-tests.sh -pl desktop -am -Dtest=RenderingTruthTest,SelectionChangeTest,AcknowledgeOnceTest,RightClickTableTest,PlayerOrderDeliveryTest,UnaffordableButtonTest,HotkeyBindingTest,GameMenuTest,ViewThreadReadsTest,NativeInterfaceRealDataTest,NativeTitleRealDataTest,NativePresentationSettingsTest -Dsurefire.failIfNoSpecifiedTests=false
```

## Sound bindings and playback policy

Grade: **GREEN**.

Automated driver: A worker completes a player-issued build order while the referee follows its sound event through the script-free retail bindings and real mixer.

Success means: Every retail binding matches its authority, neutral and player unit selection/death/work events choose the complete legal sample groups, and a completed build renders audible PCM with zero skipped checks.

Implementation:

- `engine/src/main/java/net/chonkbase/chonkcraft/engine/sound/SoundBindings.java`
- `desktop/src/main/java/net/chonkbase/chonkcraft/desktop/GameScreen.java`
- `engine/src/main/resources/chonkcraft/sound-bindings.tsv`
- `engine/src/main/resources/chonkcraft/unit-sounds.tsv`

Automated checks:

- `engine/src/test/java/net/chonkbase/chonkcraft/engine/sound/SoundWithoutScriptsRealDataTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/sound/UnitVoicesWithoutScriptsRealDataTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/sound/SoundRealDataTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/sound/SoundChoiceTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/sound/CritterVoiceRealDataTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/sound/BuilderReportsWorkCompleteTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/EconomyTest.java`
- `desktop/src/test/java/net/chonkbase/chonkcraft/desktop/BuildingVoiceTest.java`

Retail evidence:

- All 371 sound bindings and all 398 voice-event bindings for 107 voiced units were sealed only after full declaration differentials, while every decoded clip comes from the retail BNE asset pack.
- Neutral critters resolve their audible selection and death voice from the map tileset: sheep, seal, pig or warthog; death never falls through to the old explosion placeholder.
- The authenticated town-hall dead binding renders audible PCM from BNE's three-clip building-destroyed group even when destroying the local hall removes the last sight that made it visible.
- A worker taking a gold mine's final load commits one witnessed death event before the neutral building and its sight disappear; the authenticated mine dead binding renders audible PCM from the same three-clip building-destroyed group across that fog transition and survives a saturated presentation queue.
- The zero-skip player/referee lane drives a worker through the normal build order, observes its work-complete event, resolves the retail oil-tanker exception and measures non-silent PCM from the real mixer.

Known blockers:

- None recorded.

Recheck command:

```text
scripts/run-tests.sh -pl engine,desktop -am -Dtest=SoundWithoutScriptsRealDataTest,UnitVoicesWithoutScriptsRealDataTest,SoundRealDataTest,SoundChoiceTest,CritterVoiceRealDataTest,BuilderReportsWorkCompleteTest,BuildingVoiceTest -Dsurefire.failIfNoSpecifiedTests=false
```

## End-to-end player control liveness

Grade: **GREEN**.

Automated driver: A read-only outcome referee drives authenticated units through one-, three- and nine-unit controls locally and across two real UDP peers.

Success means: Every accepted command progresses or reaches an honest terminal state within 600 cycles, no control remains unresolved, and both multiplayer worlds finish 1,200 cycles with one hash.

Implementation:

- `desktop/src/main/java/net/chonkbase/chonkcraft/desktop/PlayerIntentJournal.java`
- `desktop/src/main/java/net/chonkbase/chonkcraft/desktop/CommandSink.java`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/network/CommandApplier.java`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/network/NetworkGame.java`

Automated checks:

- `desktop/src/test/java/net/chonkbase/chonkcraft/desktop/ControlLivenessPlayabilityTest.java`
- `desktop/src/test/java/net/chonkbase/chonkcraft/desktop/PlayerIntentJournalTest.java`
- `desktop/src/test/java/net/chonkbase/chonkcraft/desktop/PlayerOrderDeliveryTest.java`
- `scripts/check-bne-control-liveness-gate.sh`

Retail evidence:

- The desktop flight recorder retains the physical gesture, ordered one-to-many command fan-out, authoritative acceptance result, first visible progress and a bounded terminal classification without changing the simulation.
- The 600-cycle watchdog distinguishes explicit rejection, supersession, successful settlement, unit loss and target loss from the player-breaking case: an accepted command which never produces physical or order-state progress.
- Authenticated retail footmen and grunts receive one-, three- and nine-unit moves, mid-stride redirects, Stop/resume and congested live-target attacks through the same CommandApplier seam used by the desktop.
- Two independent worlds exchange those controls through real loopback UDP and the production lockstep scheduler for 1,200 cycles; both player journals remain live and the complete synchronized world hashes agree at the end.
- The referee never retries, redirects or repairs an order. A liveness failure stays diagnostic evidence for the precision-owned movement or combat system instead of silently introducing non-retail recovery behavior.

Known blockers:

- None recorded.

Recheck command:

```text
scripts/check-bne-control-liveness-gate.sh
```

## Multiplayer lockstep

Grade: **GREEN**.

Automated driver: Independent UDP peers play a long match over both a clean link and a deterministic adverse link.

Success means: Both peers apply the same cycle batches, detect injected state divergence, recover from real loss, delay, duplication and reordering, and converge with equal hashes through 1,800 cycles.

Implementation:

- `engine/src/main/java/net/chonkbase/chonkcraft/engine/network/LockstepScheduler.java`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/network/NetworkGame.java`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/network/NetworkSession.java`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/network/SyncHash.java`
- `engine/src/main/java/net/chonkbase/chonkcraft/engine/network/MultiplayerOutcome.java`
- `desktop/src/main/java/net/chonkbase/chonkcraft/desktop/PassiveMultiplayerRecorder.java`
- `desktop/src/main/java/net/chonkbase/chonkcraft/desktop/MultiplayerRecording.java`
- `desktop/src/main/java/net/chonkbase/chonkcraft/desktop/BneRecordingCertification.java`
- `desktop/src/main/java/net/chonkbase/chonkcraft/desktop/LobbyScreen.java`
- `desktop/src/main/java/net/chonkbase/chonkcraft/desktop/GameScreen.java`

Automated checks:

- `engine/src/test/java/net/chonkbase/chonkcraft/engine/network/LockstepTest.java`
- `engine/src/test/java/net/chonkbase/chonkcraft/engine/network/MultiplayerOutcomeTest.java`
- `desktop/src/test/java/net/chonkbase/chonkcraft/desktop/LobbyScreenTest.java`
- `desktop/src/test/java/net/chonkbase/chonkcraft/desktop/LobbyMapSetupTest.java`
- `desktop/src/test/java/net/chonkbase/chonkcraft/desktop/MultiplayerGameOverTest.java`
- `desktop/src/test/java/net/chonkbase/chonkcraft/desktop/PassiveMultiplayerRecorderTest.java`
- `scripts/check-bne-recording.sh`
- `scripts/check-bne-network-gate.sh`

Retail evidence:

- The official Warcraft II Battle.net Edition manual establishes simultaneous network play as a shipped game mode, while the implementation follows the deterministic command-batch and timeout rules documented by the contemporary LegacyEngine network path.
- Two actual loopback UDP peers exchange independently issued commands and retain identical simulation hashes through a full 1,800-cycle battle.
- A deterministic adverse-link referee really drops, delays, duplicates and reorders traffic in both directions; independent peers recover through resend, converge on the same 1,800-cycle prefix and retain equal hashes.
- The host can assign every occupied human or computer slot to Team 1–8 independently of colour; the committed roster drives symmetric alliance/shared-sight tables and the retail AI attachment on both peers.
- The synchronized BNE opponent census counts a half-built structure as a surviving real unit, decides victory only after every hostile team is empty, and decides defeat only after the local player's entire alliance is empty. The desktop presents Quit Game and a non-destructive Keep Playing path.
- Current passive recordings seal their map, initial save, accepted command stream, complete player table, synchronization-hash schema and producing runtime identity. The strict referee reconstructs and exactly replays independent land, island and ladder BNE cases, including a live computer opponent, while corruption, an unknown hash schema and a wrong starting world all fail closed.

Known blockers:

- None recorded.

Recheck command:

```text
scripts/check-bne-network-gate.sh
```
