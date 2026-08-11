# What this port already models, by mission

An index of the retail behaviours the engine has already been taught, keyed by
the mission whose units proved them. It exists because it is cheaper to read
than to rediscover: a fix was once written here for a gryphon rider's patrol
that the engine had modelled all along, and the duplicate cost more than the
original.

Generated from the comments in `engine/src/main/java`, which name their witness
case by mission and slot as a matter of house style. Regenerate with the script
in this file's git history; it is an index rather than a source of truth, and a
line here is only as current as the comment it quotes.

## Human 3  (11 references)

- `BattleNetIdleSystem.java` &middot; `dispatchBattleNetIdleMarker` &middot; line 1002 -- band on the third OP0 (Human 3 1587 → 40,15). Free-empty restart
- `BattleNetMovementSystem.java` &middot; `stepMoveOrder` &middot; line 191 -- occupied Human 3 1587 → 41,15, coast Orc 12 1461 →
- `BattleNetMovementSystem.java` &middot; `stepMoveOrderWithBattleNetCritter` &middot; line 364 -- visit without pathing, so Human 3 1587 stayed MOVE one cycle
- `Unit.java` &middot; `decayBuffs` &middot; line 520 -- where reverse-walk would steal the band choice (Human 3 1587 → 40,15).

## Human 4  (6 references)

- `BattleNetMovementSystem.java` &middot; `isStepping` &middot; line 794 -- (Human 4 1578 Still@50; isStepping-on-ChonkCraft held MOVE to ~60).
- `BattleNetMovementSystem.java` &middot; `stepMove` &middot; line 1293 -- 1358). Critters Still without PF_WAIT 10 (Human 4 1578
- `BattleNetMovementSystem.java` &middot; `walkPixels` &middot; line 3308 -- after PF_WAIT 10 or ChonkCraft unbreakable tail. Human 4 1578
- `World.java` &middot; `beginBattleNetPendingTransport` &middot; line 11706 -- (Orc 4 |dx|=1, Human 4 |dy|=1), and the approach is either pure-
- `World.java` &middot; `stepBattleNetTransportToHall` &middot; line 3820 -- Human 4 at the wrong even-grid double-step.</p>

## Human 5  (20 references)

- `AiPlayer.java` &middot; `battleNetTryTrainWorker` &middot; line 1689 -- Low bytecode target still caps the hall. Human 5 p5 keeps
- `BattleNetCombatSystem.java` &middot; `hit` &middot; line 1534 -- collapse so OP10 is not missed (Human 5 1528/1532 barracks).
- `BattleNetCombatSystem.java` &middot; `stepBattleNetAttackSequence` &middot; line 3801 -- its Attack sequence (Human 5 grunt 1531: fixture 6 then 31; chasers
- `BattleNetCombatSystem.java` &middot; `stepBattleNetTower` &middot; line 3416 -- <p>Human 5's orc guard towers report ATTACK from fixture cycle 1 on
- `BattleNetConstructionSystem.java` &middot; `findBattleNetBuildingPath` &middot; line 376 -- On-axis residual still soft-clears (Human 5 / XHuman 7
- `BattleNetHarvestSystem.java` &middot; `findAdjacentForest` &middot; line 1773 -- reverse ray crosses building-occupied land first (Human 5 farm at
- `BattleNetHarvestSystem.java` &middot; `walkToWood` &middot; line 1482 -- order points (Human 5 1512: farm cell 31,106) use the same
- `BattleNetMovementSystem.java` &middot; `stepMove` &middot; line 3043 -- spend it on this step: Human 5's zeppelin 1541 banks plus one
- `Unit.java` &middot; `decayBuffs` &middot; line 421 -- loop (twenty-five cycles): Human 5 standing grunt 1531 draws at
- `World.java` &middot; `battleNetMovementStride` &middot; line 4190 -- steps -- Human 5 peasant 1512's farm order 31,106 has free tips 32,106
- `World.java` &middot; `beginBattleNetPendingTransport` &middot; line 11709 -- double-steps NE to (70,32), Human 5 (7,5) double-steps NW to
- `World.java` &middot; `damageFor` &middot; line 8485 -- The ordinary synchronized formula below desynced Human 5's
- `World.java` &middot; `freeBattleNetProjectileSlot` &middot; line 228 -- Human 5's standing grunt hit the barracks at fixture cycle 15 and
- `World.java` &middot; `openBattleNetAttackAfterChaseResidual` &middot; line 11290 -- sequence period sealed on Human 5 (standing 1531 at 6/31, chasing
- `World.java` &middot; `orderAttack` &middot; line 3314 -- firing never take that path (Human 5 grunts 1528/1532 keep

## Human 6  (1 references)

- `BattleNetMovementSystem.java` &middot; `stepMoveOrder` &middot; line 339 -- idle-random call. Human 6 slot 1592 is the minimal witness: it remains

## Human 7  (5 references)

- `BattleNetHarvestSystem.java` &middot; `beginHarvest` &middot; line 208 -- Human 7 starts already adjacent and may stage enter after a
- `BattleNetIdleSystem.java` &middot; `battleNetBuildingTrainPulse` &middot; line 787 -- transports that never carry anything are the witnesses: Human 7's hull at
- `Unit.java` &middot; `decayBuffs` &middot; line 429 -- (Human 7). Distant tankers (XOrc 8) must hold cover of the approach
- `World.java` &middot; `battleNetUnitReady` &middot; line 11491 -- delay. Human 7 starts one of each side by side.

## Human 8  (14 references)

- `AiPlayer.java` &middot; `battleNetTryTrainWorker` &middot; line 1667 -- line: Human 8/10 with 1 peon and Human 13 with 4 all debit; bases
- `BattleNetHarvestSystem.java` &middot; `findAdjacentForest` &middot; line 1859 -- Human 8 peasant 1499: tree 85,83 and reverse-free 86,82 both take a
- `BattleNetHarvestSystem.java` &middot; `walkToWood` &middot; line 1486 -- Human 8 peasant 1507 free tip 3333433 ends on the west face
- `BattleNetPathFinder.java` &middot; `BattleNetPathFinder` &middot; line 238 -- Human 8 peasant 1507 also hits a blocked tree (85,83) after
- `BattleNetPathFinder.java` &middot; `chebyshev` &middot; line 990 -- {@code [6,7]} extends free {@code [7]}. Human 8 wall
- `BattleNetPathFinder.java` &middot; `pureMajorAxisPrefix` &middot; line 623 -- captured Human 8 and Orc 5 prefixes while malformed or exceptionally
- `World.java` &middot; `battleNetMovementStride` &middot; line 4324 -- tip-upgraded. Human 8 peasant 1507's pathfinder stores
- `World.java` &middot; `battleNetTerrainPassable` &middot; line 4132 -- mover lies below or to the right. Human 8 proves the asymmetric rule:
- `World.java` &middot; `findBattleNetPointPath` &middot; line 4452 -- harvest sets this false for a second probe so Human 8's wall-
- `World.java` &middot; `tick` &middot; line 7504 -- whose halls OP0 on different cadences (Human 8@12 vs Human 13@15).

## Human 08  (1 references)

- `World.java` &middot; `findBattleNetTargetPath` &middot; line 4736 -- overlap. Human08 slot 1526 therefore stores east,east toward a

## Human 9  (4 references)

- `BattleNetCombatSystem.java` &middot; `stepAttack` &middot; line 358 -- weapon range queues Still without world.movement. Human 9's destroyers
- `GameData.java` &middot; `applyBattleNetUnitTypeProfile` &middot; line 1109 -- Beyond the Dark Portal's Human 9 campaign setup deliberately
- `World.java` &middot; `battleNetAutoAttack` &middot; line 11397 -- auto-scan onto air (Human 9 destroyers vs balloon). Computer

## Human 10  (2 references)

- `AiPlayer.java` &middot; `battleNetTryTrainWorker` &middot; line 1704 -- debits at cycle 12; Human 10/13 start at 1000. Training on 400
- `World.java` &middot; `armedAllyBeside` &middot; line 1250 -- This is why the attack peasants in Human 10 cross the checkerboard over

## Human 12  (9 references)

- `AiPlayer.java` &middot; `setBattleNetBuildProfile` &middot; line 261 -- Human 12 / Orc 12 write 3/4). Earlier the sealed
- `AiPlayer.java` &middot; `syncBattleNetWantsFromState` &middot; line 371 -- other navy personality (Human 14 black, Human 12, Orc 12) with a
- `BattleNetIdleSystem.java` &middot; `fireBattleNetReadyForAll` &middot; line 309 -- an AI callback, so Human12's person-owned slot 1428
- `BattleNetMovementSystem.java` &middot; `stepMove` &middot; line 1439 -- major/minor gate (Human 12 transport 1522: 70,32→goal 72,29 is
- `BattleNetMovementSystem.java` &middot; `stepMoveOrder` &middot; line 111 -- XOrc 10 1427 / Human 12 1576 a later wander-band choice at
- `World.java` &middot; `beginBattleNetPendingTransport` &middot; line 11708 -- (3,3) first-steps single NW to (47,115), while Human 12 (4,5)

## Human 13  (99 references)

- `AiPlayer.java` &middot; `assignHarvester` &middot; line 1606 -- {@code (workers - 1) / 2 + 1}. Human 13's computer halls both enter a
- `AiPlayer.java` &middot; `battleNetPriorityBuild` &middot; line 2696 -- left Human 13's computer halls with 1000 gold at fixture cycle
- `AiPlayer.java` &middot; `battleNetTryTrainWorker` &middot; line 1667 -- line: Human 8/10 with 1 peon and Human 13 with 4 all debit; bases
- `BattleNetCombatSystem.java` &middot; `applyDamage` &middot; line 1694 -- Melee chips during OP0 do not bulk-hold (native Human 13 knight
- `BattleNetCombatSystem.java` &middot; `finishBattleNetAttackSequenceMarker` &middot; line 3902 -- axethrowers take this arm (chasing with pathLength 0); Human 13's
- `BattleNetCombatSystem.java` &middot; `hit` &middot; line 1432 -- Attack sequence is parked on attackStart with timer 63 (Human 13
- `BattleNetCombatSystem.java` &middot; `stepAttack` &middot; line 337 -- Human 13 knight 1493 switches axe→ogre before pathing SE. Knight
- `BattleNetCombatSystem.java` &middot; `stepBattleNetAttackSequence` &middot; line 3567 -- with timer 3 before another cached heading may be taken. Human 13
- `BattleNetHarvestSystem.java` &middot; `findResourceUnit` &middot; line 2262 -- its way. Human 13 begins with a peon boxed into its fortress crowd: the
- `BattleNetHarvestSystem.java` &middot; `stepHarvest` &middot; line 884 -- under cold-commit (Human 13 peon 50: fixture 20 instead of 19).
- `BattleNetHarvestSystem.java` &middot; `walkToWood` &middot; line 1402 -- PF_WAIT 10 -- which is how Human 13 peon 50 lost the first chop
- `BattleNetIdleSystem.java` &middot; `battleNetBuildingTrainPulse` &middot; line 738 -- Human 13 stays quiet because its profile leaves basic-want at
- `BattleNetIdleSystem.java` &middot; `dispatchBattleNetIdleMarker` &middot; line 967 -- units in the shared asynchronous RNG stream. Human 13's four
- `BattleNetMovementSystem.java` &middot; `stepMove` &middot; line 1129 -- leg one cycle later than this port used to. Measured on Human 13
- `BattleNetMovementSystem.java` &middot; `stepMoveOrderWithBattleNetCritter` &middot; line 362 -- still counting (Human 13 1572: 3,4→5,5 during the queued Move).
- `BattleNetPathFinder.java` &middot; `BattleNetPathFinder` &middot; line 232 -- blocked square keeps the clear part of its ray. Human 13
- `BattleNetPathFinder.java` &middot; `chebyshev` &middot; line 989 -- <p>Headings are stack-stored (last element first). Human 13 wall
- `BattleNetProjectileSystem.java` &middot; `prepareBattleNetProjectile` &middot; line 138 -- reaches Human 13's knight on fixture cycle 14 while the native type
- `BattleNetProjectileSystem.java` &middot; `resolveBattleNetSplash` &middot; line 547 -- not recruit (Human 13@21 REG).
- `BattleNetSequence.java` &middot; `usable` &middot; line 101 -- <p>Human 13 knight 1490 takes a catapult splash while parked on Attack
- `BattleNetTargetSelection.java` &middot; `findBattleNetHostile` &middot; line 724 -- targets native cannot see: Human 13's ogre in native slot 1519 stands
- `GameData.java` &middot; `loadMission` &middot; line 1085 -- during {@code CUnit::Init}. BNE also leaves the Human 13 wise-man at its
- `Missile.java` &middot; `?` &middot; line 177 -- Type-13 catapult rocks arm wait 5: Human 13 slot 3 remaining goes
- `Missile.java` &middot; `step` &middot; line 734 -- <p>Human 13's tower arrow used to land on fixture cycle 14 with ChonkCraft
- `Missile.java` &middot; `stepBattleNetMotion` &middot; line 743 -- missile-catapult-rock -- Human 13).
- `Unit.java` &middot; `battleNetPathInitialLength` &middot; line 3228 -- ({@code initialLength - pathLength}). Human 13 axe 1495 residual-opens
- `Unit.java` &middot; `decayBuffs` &middot; line 547 -- action-33 footman/grunt auto-train (native unit+0x5c style); Human 13
- `Unit.java` &middot; `setBattleNetAttackResumeFromMove` &middot; line 2350 -- selects the Human 13 axe 1483 pre-fire stall without blocking first
- `Unit.java` &middot; `setBattleNetChaseStepReady` &middot; line 2189 -- heading (Human 13 ogre 1482). Free-approach continuous paths never set
- `Unit.java` &middot; `setBattleNetEmptyRouteFreeDetourHold` &middot; line 2224 -- OP10 may land damage without a presentation pend (Human 13 ogre 1510).
- `Unit.java` &middot; `setBattleNetGoldSoftWaitFreeWake` &middot; line 2334 -- in weapon range. Human 13 axe 1483's next in-range OP0 stalls on the
- `Unit.java` &middot; `setBattleNetRangedFreeScanHoldActive` &middot; line 2489 -- {@code bodyWaitSum - 1} instead of entering windup (Human 13 knight
- `Unit.java` &middot; `setBattleNetRangedFreeScanHoldPending` &middot; line 2474 -- this is set -- approach holds alone REGd Human 13 knight splash.
- `Unit.java` &middot; `setBattleNetRangedResidualOpen` &middot; line 2256 -- so a late OP0 still lands the blow on native's process cycle (Human 13
- `Unit.java` &middot; `setBattleNetSequenceMeleeLanded` &middot; line 2534 -- <p>Human 13 ogre 1482 pauses on 124,32 for fixtures 31 to 33 and steps
- `World.java` &middot; `battleNetAutoAttack` &middot; line 11374 -- five-call Still marker. Human 13's three timer-one troops acquire
- `World.java` &middot; `battleNetSpatialHelpReactPlusOne` &middot; line 3356 -- offer even for person defenders. Human 13 knight 1500 is Still when
- `World.java` &middot; `consumeBattleNetPendingMeleeSyncRand` &middot; line 11172 -- residual settles (Human 13 F36 wise-man+grunt) still debit below.
- `World.java` &middot; `findBattleNetPointPath` &middot; line 4557 -- Same skirt cell: wall added nothing useful (Human 13 skirt west).
- `World.java` &middot; `freeBattleNetProjectileSlot` &middot; line 178 -- retarget, and Human 13 ogre 1482 kept the knight's leftover N.
- `World.java` &middot; `isBattleNetArmedTower` &middot; line 11000 -- limit. Human 13 barracks place with PUD data 0 and never debit; XHuman
- `World.java` &middot; `openBattleNetAttackAfterChaseResidual` &middot; line 11255 -- presentation pend (Human 13 ogre 1510). Ranged
- `World.java` &middot; `planTowards` &middot; line 8126 -- enough for the action-marker idle draws Human 13's critters need
- `World.java` &middot; `recordCausalEventsTo` &middot; line 113 -- Human 13 async constructor trio before trusting randContext anim labels.
- `World.java` &middot; `tick` &middot; line 7504 -- whose halls OP0 on different cadences (Human 8@12 vs Human 13@15).
- `World.java` &middot; `tickBattleNetMeleeSyncLoop` &middot; line 11349 -- is already observable in the two unrecorded startup calls on Human 13:

## Human 14  (6 references)

- `AiPlayer.java` &middot; `battleNetTryResearchFoundry` &middot; line 2274 -- <p>Profile 27 (Human 14 p0 death-knight seat) lists codes
- `AiPlayer.java` &middot; `battleNetTryResearchTemple` &middot; line 2305 -- High-byte temple list. First armed code on Human 14 p0 is 0x93 and
- `AiPlayer.java` &middot; `syncBattleNetWantsFromState` &middot; line 371 -- other navy personality (Human 14 black, Human 12, Orc 12) with a
- `BattleNetIdleSystem.java` &middot; `battleNetBuildingTrainPulse` &middot; line 769 -- Temple / mage-tower spell research. Human 14 p0 profile 27
- `BattleNetIdleSystem.java` &middot; `stepBattleNetHallStill` &middot; line 619 -- Human 14 p0 temple: raise-dead 1500g at fixture c35. Freeze 2
- `BattleNetIdleSystem.java` &middot; `stepBattleNetIdleApproximation` &middot; line 905 -- draws in Human 14's first warm-up alone and put every later

## Orc 2  (1 references)

- `GameData.java` &middot; `applyBattleNetUnitTypeProfile` &middot; line 1105 -- Retail BNE's stock table gives the sharp-axe slot 40; Orc 2 places

## Orc 3  (1 references)

- `BattleNetPathFinder.java` &middot; `BattleNetPathFinder` &middot; line 516 -- steals XOrc 11 battleship west detours and Orc 3 tanker

## Orc 4  (4 references)

- `BattleNetIdleSystem.java` &middot; `stepBattleNetHallStill` &middot; line 609 -- over-spent later-opening halls on Orc 4 and XOrc 6/10/11);
- `World.java` &middot; `beginBattleNetPendingTransport` &middot; line 11697 -- free ship anchor on the hall→ship Bresenham ray (Orc 4: 17,37).
- `World.java` &middot; `stepBattleNetTransportToHall` &middot; line 3822 -- <p>Orc 4 lands on (17,37) -- last blocked cell before open water

## Orc 5  (14 references)

- `AiPlayer.java` &middot; `battleNetTryTrainWorker` &middot; line 1670 -- Human town-hall is tighter: sealed Orc 5 p0 has 4 peasants and
- `BattleNetConstructionSystem.java` &middot; `findBattleNetBuildingPath` &middot; line 352 -- wall-follow matches native (Orc 5 1534 SW vs pure W)
- `BattleNetConstructionSystem.java` &middot; `orderBuild` &middot; line 792 -- target footprint clamp: Orc 5's native hall route is five SW
- `BattleNetMovementSystem.java` &middot; `stepMove` &middot; line 1167 -- - Orc 5 peasant 1529: pathLength-2 diagonal leftover onto an ally
- `BattleNetMovementSystem.java` &middot; `walkTowards` &middot; line 665 -- (Orc 5 1534 SW,SW,W vs pure-major W,W,SW).
- `BattleNetPathFinder.java` &middot; `pureMajorAxisPrefix` &middot; line 623 -- captured Human 8 and Orc 5 prefixes while malformed or exceptionally
- `Unit.java` &middot; `setBattleNetResidualEmptyRouteSettle` &middot; line 2272 -- Native marks route_index 20 (Orc 5 peons at fixture 38) before the third
- `World.java` &middot; `beginBattleNetPendingTransport` &middot; line 11707 -- axis or Chebyshev >= 4. That last gate is fixture-grounded: Orc 5

## Orc 7  (18 references)

- `BattleNetConstructionSystem.java` &middot; `findBattleNetBuildingPath` &middot; line 465 -- Gold free-prefix pure-major keep (Orc 7 peon 1582).
- `BattleNetHarvestSystem.java` &middot; `chopInPlace` &middot; line 1156 -- at range-one. Orc 7 peon 1567 is the cycle-24 claim witness.
- `BattleNetHarvestSystem.java` &middot; `stepHarvest` &middot; line 455 -- native range-one. Orc 7 peasant 1567 holds (40,8) after NW from
- `BattleNetHarvestSystem.java` &middot; `walkToWood` &middot; line 1418 -- the tip is already beside forest (Orc 7 1567 re-aim before
- `BattleNetIdleSystem.java` &middot; `dispatchBattleNetIdleMarker` &middot; line 1081 -- never gives Orc 7 slot 1512 its fixture-world.cycle-5 wander.
- `BattleNetMovementSystem.java` &middot; `stepMove` &middot; line 3005 -- Far free-prefix forest re-aim (Orc 7) uses the empty spent-route
- `BattleNetMovementSystem.java` &middot; `stepMoveOrder` &middot; line 99 -- Critter one-tile wanders onto rock (Orc 7 1512 → 121,106, Orc
- `BattleNetMovementSystem.java` &middot; `walkTowards` &middot; line 549 -- current after offsets hit nought, and that gate left Orc 7 peon
- `BattleNetPathFinder.java` &middot; `BattleNetPathFinder` &middot; line 184 -- same first step but ends no closer than the free tip (Orc 7 peon
- `World.java` &middot; `findBattleNetPointPath` &middot; line 4451 -- passed preserveBlockedGoalPrefix false (Orc 7 peon 1567). Wood
- `World.java` &middot; `unitAt` &middot; line 3249 -- 54,61 are coast; Orc 7 121,106 and Orc 10 49,48 are rock.

## Orc 8  (1 references)

- `BattleNetBuildingPlacement.java` &middot; `aiFindBuildingPlace` &middot; line 115 -- drift south-east on every side: Orc 8 then places its first farm at

## Orc 10  (14 references)

- `BattleNetConstructionSystem.java` &middot; `walkToSite` &middot; line 594 -- cheb > maxRange (not max(1, maxRange)): Orc 10 peon 1583 free
- `BattleNetIdleSystem.java` &middot; `dispatchBattleNetIdleMarker` &middot; line 1104 -- neighbours (Orc 10 1510 → 57,63 keeps 1526 Move@8;
- `BattleNetIdleSystem.java` &middot; `stepBattleNetIdle` &middot; line 844 -- Orc 10 1513's issue+4 OP0 used to run after its own second burn
- `BattleNetMovementSystem.java` &middot; `stepMoveOrder` &middot; line 100 -- 10 1525 → 49,48) or coast (Orc 12 1461 → 76,93, Orc 10 1513 →
- `Unit.java` &middot; `decayBuffs` &middot; line 532 -- Still-loop WAIT by one quiet visit (Orc 10 1510: native first loop OP0
- `World.java` &middot; `unitAt` &middot; line 3248 -- LAND_ALLOWED). Distinct from UNPASSABLE rock: Orc 12 76,93 and Orc 10

## Orc 11  (11 references)

- `BattleNetIdleSystem.java` &middot; `dispatchBattleNetIdleMarker` &middot; line 1023 -- for a few visits only: Orc 11 1597 needs three OP0s (Still@8-9
- `BattleNetIdleSystem.java` &middot; `fireBattleNetReadyForAll` &middot; line 355 -- (Orc 11 106,7). Nearest-enemy free squares used to aim the
- `BattleNetMovementSystem.java` &middot; `stepMove` &middot; line 1257 -- then replans -- Orc 11 archer 1559 Still@50 step@53 (+3 gap).
- `BattleNetMovementSystem.java` &middot; `stepMoveOrder` &middot; line 273 -- Human 3 1587, free Orc 11 1597, later rock
- `Unit.java` &middot; `decayBuffs` &middot; line 512 -- skip the re-wander beat (Orc 11 1597 Still@8-9 Move@10). Building
- `Unit.java` &middot; `setBattleNetBorrowedMoveForStep` &middot; line 2410 -- <p>Orc 11's type-two land assault pack (knight 1558 and archers
- `Unit.java` &middot; `setBattleNetSameHeadingRun` &middot; line 2430 -- animation timer 3 / order delay 2 before replanning (Orc 11 archer
- `World.java` &middot; `fireOnReadyForAll` &middot; line 10122 -- land attackers (Orc 11 knight 1558 and archers 1559/1560/1563 at home
- `World.java` &middot; `prepareBattleNetInitialAttackGroups` &middot; line 10218 -- <p>Native Orc 11 stores {@code aiHome=106,7} for the four unmarked

## Orc 12  (22 references)

- `AiPlayer.java` &middot; `setBattleNetBuildProfile` &middot; line 261 -- Human 12 / Orc 12 write 3/4). Earlier the sealed
- `AiPlayer.java` &middot; `syncBattleNetWantsFromState` &middot; line 371 -- other navy personality (Human 14 black, Human 12, Orc 12) with a
- `BattleNetHarvestSystem.java` &middot; `fellTree` &middot; line 2876 -- blocked footprint (Orc 12 peon 1525: two steps onto 85,41 while the
- `BattleNetHarvestSystem.java` &middot; `stepHarvest` &middot; line 534 -- early Orc 12 / XHuman 7 gold rays stay clean.
- `BattleNetIdleSystem.java` &middot; `autoAttackStand` &middot; line 235 -- gryphon riders fight but are unmarked and qualify; Orc 12's daemons and
- `BattleNetIdleSystem.java` &middot; `battleNetBuildingTrainPulse` &middot; line 742 -- Shipyard train_fn does not require non-zero UNIT.Data. Orc 12
- `BattleNetMovementSystem.java` &middot; `stepMove` &middot; line 2307 -- Orc 12 peon 1521: residual-settled multi-step leftover
- `BattleNetMovementSystem.java` &middot; `stepMoveOrder` &middot; line 100 -- 10 1525 → 49,48) or coast (Orc 12 1461 → 76,93, Orc 10 1513 →
- `BattleNetMovementSystem.java` &middot; `walkTowards` &middot; line 662 -- not this short free tip (Orc 12 peon 1525: free SW,NW
- `Unit.java` &middot; `decayBuffs` &middot; line 451 -- replan without the emptied-buffer PF_WAIT 10 (Orc 12 peon 1525:
- `Unit.java` &middot; `setBattleNetWoodRouteIndex20` &middot; line 2301 -- Far multi-step residual refuse hold (Orc 12 peon 1521). Armed on the
- `World.java` &middot; `unitAt` &middot; line 3248 -- LAND_ALLOWED). Distinct from UNPASSABLE rock: Orc 12 76,93 and Orc 10

## Orc 13  (3 references)

- `AiPlayer.java` &middot; `battleNetTryTrainWorker` &middot; line 1676 -- (wanted 9, six peons) and Orc 13 castle (wanted 7) still train
- `World.java` &middot; `battleNetIsTownHall` &middot; line 11086 -- Human hall line peon trains (Orc 13 p3 castle debits a peasant at

## Orc 14  (26 references)

- `AiPlayer.java` &middot; `battleNetTryResearchFoundry` &middot; line 2280 -- <p>Codes 0x93-0x97 are the orc temple spell block only. Orc 14 p6 is a
- `AiPlayer.java` &middot; `battleNetTryTrainFlyer` &middot; line 1946 -- still producing). Wants below 4 must not open the arm: Orc 14 p6
- `AiPlayer.java` &middot; `battleNetTryTrainTanker` &middot; line 2036 -- top-up). Transports on Orc 14 start HARVEST-to-hall then Still;
- `BattleNetConstructionSystem.java` &middot; `findBattleNetBuildingPath` &middot; line 425 -- rays (Orc 14 116,6→122,2). Deltas use the even-snapped
- `BattleNetHarvestSystem.java` &middot; `battleNetOilTankerBoardSeat` &middot; line 2164 -- <p>Footprint cover of the approach point is the XOrc 8 path. Orc 14
- `BattleNetHarvestSystem.java` &middot; `battleNetOilTankerCoversApproach` &middot; line 2140 -- Board seat one Chebyshev step from the approach point (Orc 14 1565 at
- `BattleNetHarvestSystem.java` &middot; `beginHarvest` &middot; line 200 -- Orc 14 human transports leave construction Still into harvest before
- `BattleNetHarvestSystem.java` &middot; `stepHarvest` &middot; line 250 -- second action-25 from walkTowards (Orc 14 1575).
- `BattleNetHarvestSystem.java` &middot; `tryBattleNetOilBoardEnter` &middot; line 2108 -- (Orc 14 1565). Started-adjacent tankers have coverWait still 0
- `BattleNetIdleSystem.java` &middot; `battleNetBuildingTrainPulse` &middot; line 781 -- <p>A hull that has carried something (Orc 14 post-harvest at world 21)
- `BattleNetIdleSystem.java` &middot; `dispatchBattleNetIdleMarker` &middot; line 973 -- Orc 14 human transports at 27,27 / 91,31: after leaving
- `BattleNetIdleSystem.java` &middot; `fireBattleNetReadyForAll` &middot; line 311 -- type-specific callback for an AI transport: Orc14's
- `BattleNetMovementSystem.java` &middot; `walkTowards` &middot; line 653 -- the generic empty-route PF_WAIT. Orc 14 peasant steps
- `Unit.java` &middot; `setBattleNetAttackOp0Damaged` &middot; line 2504 -- Later Still-loop re-arms must not draw (Orc 14 post-harvest transports).
- `World.java` &middot; `canEnterBattleNetTransportAnchor` &middot; line 12014 -- XHuman 5 shoreline and while Orc 14's transport leaves an authored
- `World.java` &middot; `stepBattleNetTransportToHall` &middot; line 3733 -- Orc 14's startup transports on HARVEST through fixture 18 while

## XHuman 2  (28 references)

- `AiPlayer.java` &middot; `battleNetTryTrainWorker` &middot; line 1668 -- that already field 5+ workers (XHuman 2 p0, XHuman 11/12) never
- `BattleNetCombatSystem.java` &middot; `applyDamage` &middot; line 1682 -- DYING-HP report is open on XHuman 2 footman 1548. Subtract only when
- `BattleNetCombatSystem.java` &middot; `stepAttack` &middot; line 327 -- timer==1 visit itself is one fixture cycle early (XHuman 2 footman
- `BattleNetCombatSystem.java` &middot; `stepBattleNetTower` &middot; line 3393 -- timer), so XHuman 2's tower arrow damages on fixture cycle 11.
- `BattleNetCombatSystem.java` &middot; `tickBattleNetChaseMoveSequence` &middot; line 3528 -- <p>XHuman 2 footman 1548 finishes at fixture 39 if Still is applied on
- `BattleNetConstructionSystem.java` &middot; `stepWalkToSite` &middot; line 1282 -- back on that cycle: XHuman 2's peon 1560 is on its pig-farm site
- `BattleNetHarvestSystem.java` &middot; `chopInPlace` &middot; line 1136 -- XHuman 2 slot 1589 is the cycle-19 witness: re-aim on
- `BattleNetHarvestSystem.java` &middot; `findAdjacentForest` &middot; line 1810 -- the real open tip (XHuman 2 peon corridor). Building tiles
- `BattleNetHarvestSystem.java` &middot; `stepHarvest` &middot; line 487 -- pixels drain, and even with a leftover cached heading (XHuman 2
- `BattleNetHarvestSystem.java` &middot; `walkToWood` &middot; line 1420 -- pay that delay -- it held XHuman 2 peon 1530 at 92,100
- `BattleNetMovementSystem.java` &middot; `stepMove` &middot; line 1299 -- (XHuman 2 peon 1530 native@50 vs Java@53).
- `BattleNetProjectileSystem.java` &middot; `battleNetMissileMinFlight` &middot; line 240 -- basic-armor here double-taxed XHuman 2's barracks (armor 20):
- `BattleNetProjectileSystem.java` &middot; `resolveBattleNetSplash` &middot; line 483 -- A pure ascending tileY sort used to hit XHuman 2's ogre at 61,66
- `BattleNetProjectileSystem.java` &middot; `stepMissiles` &middot; line 404 -- accepted target draws {@code battleNetRand}. XHuman 2's northern ogre
- `Missile.java` &middot; `?` &middot; line 175 -- pass frees): XHuman 2's tower arrow shows remaining -4 with target HP
- `Unit.java` &middot; `decayBuffs` &middot; line 548 -- barracks place with data 0 and never auto-train while XHuman 2 / XOrc 11
- `Unit.java` &middot; `setBattleNetAttackOp0OutOfRange` &middot; line 2365 -- (XHuman 2 footman 1548). Cleared when a new attack order arms.
- `Unit.java` &middot; `setBattleNetPersonHelpFirstChase` &middot; line 2394 -- route Still-promotion must not arm on borrowed Move (XHuman 2 peon 1530
- `World.java` &middot; `battleNetMeleeSyncRandType` &middot; line 3344 -- <p>Broader radius-13 help regressed XHuman 2. Recruiting only
- `World.java` &middot; `battleNetMovementStride` &middot; line 4193 -- short of a distant forest (XHuman 2 peon 1530 path 707) keep the
- `World.java` &middot; `hitDirectly` &middot; line 1912 -- footman 1492@42, XHuman 2 footman 1548@43).

## XHuman 3  (3 references)

- `AiPlayer.java` &middot; `battleNetTryTrainSoldier` &middot; line 1774 -- Prerequisite counters: sealed XHuman 3 trains an ogre while
- `AiPlayer.java` &middot; `battleNetTryTrainWorker` &middot; line 1734 -- XHuman 3 p4 profile 45 takes the zero-cavalry arm (ogre, 800g/100w) at
- `BattleNetIdleSystem.java` &middot; `battleNetBuildingTrainPulse` &middot; line 739 -- zero; XHuman 3 ogre and XOrc 11 footman debit from wants alone.

## XHuman 4  (11 references)

- `BattleNetCombatSystem.java` &middot; `finishBattleNetAttackSequenceMarker` &middot; line 3901 -- falls through the waiting Still retry. XHuman 4's blocked
- `BattleNetCombatSystem.java` &middot; `stepAttack` &middot; line 306 -- when the planned next cell is free (XHuman 4 axe 1490 pathn
- `BattleNetCombatSystem.java` &middot; `stepBattleNetTowerFallback` &middot; line 3444 -- through Still and reaches {@code FUN_0040ad30}. XHuman 4's axethrowers
- `BattleNetMovementSystem.java` &middot; `stepMove` &middot; line 1182 -- once clear (XHuman 4 peon 1570: residual world 21, ally peon 22 on
- `BattleNetPathFinder.java` &middot; `BattleNetPathFinder` &middot; line 560 -- it does not invent a locally passable side step. XHuman 4 captures
- `World.java` &middot; `battleNetAutoAttack` &middot; line 11360 -- reaction range made XHuman 4's ballista Attack at fixture cycle 1
- `World.java` &middot; `failsWall` &middot; line 4863 -- clears off-axis pathLength-1 allies without REG (XHuman 4 @6).
- `World.java` &middot; `planTowards` &middot; line 8123 -- PF_WAIT(10) before the next plan misses XHuman 4: axethrowers

## XHuman 04  (1 references)

- `BattleNetMovementSystem.java` &middot; `stepMove` &middot; line 2336 -- free diagonal (XHuman 04 peon 1567) keep

## XHuman 5  (4 references)

- `AiPlayer.java` &middot; `battleNetTryTrainFlyer` &middot; line 1989 -- wants. Sealed arms so far: tankers (XHuman 5/8), destroyers (XOrc 7 dual
- `BattleNetHarvestSystem.java` &middot; `stepHarvest` &middot; line 594 -- also stage: XHuman 5 peon 1536's leftover N onto
- `World.java` &middot; `beginBattleNetPendingTransport` &middot; line 11710 -- (122,50), and XHuman 5 (2,0) double-steps pure east. Requiring the
- `World.java` &middot; `canEnterBattleNetTransportAnchor` &middot; line 12014 -- XHuman 5 shoreline and while Orc 14's transport leaves an authored

## XHuman 6  (3 references)

- `AiPlayer.java` &middot; `battleNetSatisfiedPositions` &middot; line 2785 -- native XHuman 6 snapshot is the useful proof: player 5 owns a
- `BattleNetBuildingPlacement.java` &middot; `aiFindBattleNetBuildingPlace` &middot; line 185 -- last distinction is visible at BNE startup: XHuman 6 can establish a
- `BattleNetPathFinder.java` &middot; `BattleNetPathFinder` &middot; line 489 -- keep the major-axis side. Stride-1 land paths (XHuman 6

## XHuman 07  (2 references)

- `World.java` &middot; `battleNetNavalRewriteOpenWater` &middot; line 11580 -- coast goal (XHuman 07 submarine 1511: from 20,52 onto 18,52). Falls
- `World.java` &middot; `stepPatrol` &middot; line 9689 -- 20,52→18,52 for XHuman 07 submarine 1511).

## XHuman 7  (21 references)

- `AiPlayer.java` &middot; `battleNetBarracksType` &middot; line 1896 -- <p>XHuman 7 p5 and XOrc 6 p2 debit 2500 gold (dragon/gryphon) at
- `AiPlayer.java` &middot; `battleNetTryTrainFlyer` &middot; line 1933 -- marked on XHuman 7 and must not block the first dragon).
- `BattleNetConstructionSystem.java` &middot; `findBattleNetBuildingPath` &middot; line 376 -- On-axis residual still soft-clears (Human 5 / XHuman 7
- `BattleNetHarvestSystem.java` &middot; `stepHarvest` &middot; line 233 -- Gold-mine approach soft-wait free-wake: XHuman 7 peon 1446
- `BattleNetHarvestSystem.java` &middot; `stepReturnGoods` &middot; line 3000 -- <p>XHuman 7 peon 1446 holds route_index 20 at 110,105 while ally 1447
- `BattleNetHarvestSystem.java` &middot; `walkToWood` &middot; line 1405 -- the settle cycle (XHuman 7 slot1545: timer 3/2/1 on c19-c21, east
- `BattleNetIdleSystem.java` &middot; `battleNetBuildingTrainPulse` &middot; line 763 -- 0x40fa00: dragon/gryphon. XHuman 7 / XOrc 6 debit 2500g at c15.
- `BattleNetMovementSystem.java` &middot; `stepMove` &middot; line 1325 -- settle must not invent a draw (XHuman 7 critter
- `Unit.java` &middot; `setBattleNetFarMultiStepResidualRefuse` &middot; line 2319 -- when the planned next cell clears (XHuman 7 peon 1446); ordinary mid-
- `World.java` &middot; `battleNetAutoAttack` &middot; line 11362 -- (XHuman 7 sub) keeps the ordinary reaction-range scan.

## XHuman 8  (14 references)

- `AiPlayer.java` &middot; `battleNetTryTrainTanker` &middot; line 2026 -- guards. XHuman 8 p7 places a data=1 tanker that satisfies
- `AiPlayer.java` &middot; `battleNetTryTrainWorker` &middot; line 1702 -- respect the same floor: XHuman 8 p6 sits on exactly 400 gold with
- `BattleNetBuildingPlacement.java` &middot; `aiFindBattleNetBuildingPlace` &middot; line 186 -- second base, while XHuman 8's mine is already served by its stronghold
- `BattleNetHarvestSystem.java` &middot; `canEnterBattleNetResourceTarget` &middot; line 3057 -- inside the footprint (XHuman 8 peons 1571/1575 stack on 17,10).
- `BattleNetHarvestSystem.java` &middot; `findAdjacentForest` &middot; line 1863 -- XHuman 8's reverse-free north-east vs pure-north tree must still
- `BattleNetHarvestSystem.java` &middot; `walkToWood` &middot; line 1478 -- open prefix (XHuman 8 peon 1510: NE,NE,NE onto 7,64);
- `World.java` &middot; `battleNetMovementStride` &middot; line 4313 -- <p>Endpoint packing alone (XHuman 8 peon 1510 Bresenham free ray ending
- `World.java` &middot; `battleNetNavalPatrolTarget` &middot; line 10464 -- XHuman 8 destroyer 1480 keeps near = self and far = platform 41,85;
- `World.java` &middot; `isBattleNetArmedTower` &middot; line 11001 -- 2 / XOrc 11 place with data 1 and debit 600 at the third OP0. XHuman 8

## XHuman 9  (14 references)

- `AiPlayer.java` &middot; `battleNetCountBuildings` &middot; line 477 -- accepted axe1 -- XHuman 9 barracks then trained an
- `AiPlayer.java` &middot; `battleNetTryTrainSoldier` &middot; line 1778 -- training share progress/goal, and XHuman 9's researching
- `BattleNetCombatSystem.java` &middot; `stepMoveTowardsTarget` &middot; line 1361 -- offset to survive the temporary MOVE flip and the restore (XHuman 9
- `BattleNetHarvestSystem.java` &middot; `fellTree` &middot; line 2891 -- leftover (XHuman 9 1550).
- `BattleNetHarvestSystem.java` &middot; `stepHarvest` &middot; line 488 -- [NW,N] discards N; XHuman 9 peon 1550 must not spend a second N
- `BattleNetIdleSystem.java` &middot; `battleNetBuildingTrainPulse` &middot; line 749 -- 0, 67, 70: sealed XHuman 10/11 axe1. 65: XHuman 9 p6 reseeds
- `BattleNetIdleSystem.java` &middot; `stepBattleNetHallStill` &middot; line 629 -- 65 XHuman 9 p6 third OP0 at fixture c19 (freeze 10) so axe1
- `BattleNetMovementSystem.java` &middot; `stepMove` &middot; line 1115 -- XHuman 9 skeleton 1431 stepped SW at fixture 26 while Java's
- `BattleNetMovementSystem.java` &middot; `walkTowards` &middot; line 572 -- need the same (XHuman 9 peon 1550: clear at 109,24 then stage
- `Unit.java` &middot; `decayBuffs` &middot; line 444 -- (XHuman 9 peon 1550). Fresh one-step plans must not take this arm.
- `Unit.java` &middot; `setBattleNetFarMultiStepResidualRefuse` &middot; line 2320 -- path soft-waits must count out fully (XHuman 9/10/12 peons).

## XHuman 10  (40 references)

- `AiPlayer.java` &middot; `battleNetConsumeAction33Candidate` &middot; line 549 -- the action-33 consume path. Re-arming here made XHuman 10/11
- `AiPlayer.java` &middot; `battleNetWantedTankersForTestPeek` &middot; line 2124 -- researched and the milestone is consumed. XHuman 11 p2 / XHuman 10 p2
- `BattleNetCombatSystem.java` &middot; `applyDamage` &middot; line 1680 -- XHuman 10 footman 1492 dies at fixture 42 with native HP 60 (full
- `BattleNetCombatSystem.java` &middot; `finishBattleNetAttackSequenceMarker` &middot; line 3903 -- open-path axethrower switches to animation three; XHuman 10 unit
- `BattleNetCombatSystem.java` &middot; `hit` &middot; line 1453 -- than native (XHuman 10 farm 1536 axe at fixture 27).
- `BattleNetCombatSystem.java` &middot; `stepAttack` &middot; line 223 -- closer toward the quarry. XHuman 10 grunt 1482 holds EEE at
- `BattleNetCombatSystem.java` &middot; `stepBattleNetAttackSequence` &middot; line 3645 -- Ranged free-scan only on the OP0 fire visit (timer 1). XHuman 10
- `BattleNetCombatSystem.java` &middot; `stepBattleNetTower` &middot; line 3375 -- (XHuman 10 second cannon preferred unit 100 / 1500 as primary).
- `BattleNetConstructionSystem.java` &middot; `walkToSite` &middot; line 582 -- the settle visit can replan without PF_WAIT 10 (XHuman 10 peon
- `BattleNetHarvestSystem.java` &middot; `findAiWood` &middot; line 2027 -- overlap. On {@code XHuman10}, that is the exact difference between the
- `BattleNetIdleSystem.java` &middot; `battleNetBuildingTrainPulse` &middot; line 716 -- limits of 100+ (XHuman 10 p2 profile 67) still suppress trains.
- `BattleNetIdleSystem.java` &middot; `stepBattleNetHallStill` &middot; line 691 -- XHuman 10 p2 profile 67 great-hall type 75 is limit 100.
- `BattleNetMovementSystem.java` &middot; `stepMove` &middot; line 1169 -- - XHuman 10 peon 1437: residual of the S step onto 16,108 with
- `BattleNetProjectileSystem.java` &middot; `battleNetMissileSpeed` &middot; line 192 -- delivered XHuman 10 cannon bolts several ticks early.
- `BattleNetProjectileSystem.java` &middot; `launch` &middot; line 294 -- whole drain (XHuman 10: 2521,2745 vs native 2518,2742). Cold-commit
- `BattleNetProjectileSystem.java` &middot; `resolve` &middot; line 601 -- override selected six units for 16 each on XHuman 10; native hits
- `BattleNetProjectileSystem.java` &middot; `resolveBattleNetSplash` &middot; line 516 -- full stored band. XHuman 10's second shell lands at 2512,2736
- `Missile.java` &middot; `?` &middot; line 181 -- hold delayed XHuman 10 splash four cycles.
- `Missile.java` &middot; `setBattleNetPoolSlot` &middot; line 533 -- XHuman 10's cannon shell hits that one-draw arm at fixture cycle 13;
- `Unit.java` &middot; `setBattleNetRangedFreeScanHoldPending` &middot; line 2472 -- Timer-63 hold came from a ranged free-scan retarget (XHuman 10 archer 98),
- `Unit.java` &middot; `setBattleNetStationaryRecoveryHeld` &middot; line 2380 -- (XHuman 10 knight 1493). Cleared when that first path is installed.
- `World.java` &middot; `battleNetSpatialHelpReactPlusOne` &middot; line 3426 -- <p>XHuman 10 footman 1492 dies to catapult splash; knight 1489 (Data,
- `World.java` &middot; `consumeBattleNetPendingMeleeSyncRand` &middot; line 11169 -- FUN_004234b0. XHuman 10 grunt 105 residual-settled beside footman
- `World.java` &middot; `findBattleNetPointPath` &middot; line 4602 -- ordinary combat opens keep Bresenham (XHuman 10 grunt 1486 @6). XHuman
- `World.java` &middot; `hitDirectly` &middot; line 1911 -- Lethal damage leaves last living HP (BNE corpse report; XHuman 10
- `World.java` &middot; `planTowards` &middot; line 8115 -- mid-Move brother (XHuman 10 knight 1493 SW onto 1489).
- `World.java` &middot; `recordCausalEventsTo` &middot; line 133 -- Ambient rem=0 slots never free for real shots (XHuman 10 free@42).
- `World.java` &middot; `tick` &middot; line 7295 -- aggressor first (XHuman 10 knight 1489 shares y with

## XHuman 11  (8 references)

- `AiPlayer.java` &middot; `battleNetTryTrainWorker` &middot; line 1668 -- that already field 5+ workers (XHuman 2 p0, XHuman 11/12) never
- `AiPlayer.java` &middot; `battleNetWantedTankersForTestPeek` &middot; line 2124 -- researched and the milestone is consumed. XHuman 11 p2 / XHuman 10 p2
- `BattleNetHarvestSystem.java` &middot; `walkToWood` &middot; line 1491 -- XHuman 11/12 wood peons whose wall only extends or lands on
- `BattleNetMovementSystem.java` &middot; `stepMove` &middot; line 2198 -- ally. XHuman 11 peon 1584 at 10,6 holds SE onto ally 11,7;
- `BattleNetPathFinder.java` &middot; `?` &middot; line 45 -- {@code 0x450350} (XHuman 11 peon 1584: NE+SE → E onto 11,6).</p>
- `World.java` &middot; `findBattleNetPointPath` &middot; line 4521 -- step to east and ends 86,82 (east face). XHuman 11/12 wood peons whose

## XHuman 12  (86 references)

- `AiPlayer.java` &middot; `battleNetTryTrainWorker` &middot; line 1675 -- strictly larger than the small-base cap so XHuman 12 profile 0
- `AiPlayer.java` &middot; `setBattleNetBuildProfile` &middot; line 317 -- cycle-16 peon on XHuman 12.
- `BattleNetCombatSystem.java` &middot; `BattleNetCombatSystem` &middot; line 47 -- (XHuman 12 axe 76 at 32,38m), but native steps the chasing grunt E
- `BattleNetCombatSystem.java` &middot; `hit` &middot; line 1436 -- for building targets (XHuman 12) must keep the cycle-end debit.
- `BattleNetCombatSystem.java` &middot; `marchTowards` &middot; line 3329 -- marker. In the retail XHuman 12 opening, for example, guard tower 1429
- `BattleNetCombatSystem.java` &middot; `stepAttack` &middot; line 111 -- XHuman 12 grunt 1503 holds SE while axe 1524 sits on E (32,38);
- `BattleNetCombatSystem.java` &middot; `stepBattleNetAttackSequence` &middot; line 3750 -- Move resume so pure in-range first swings (early axes, XHuman 12)
- `BattleNetCombatSystem.java` &middot; `stepMoveTowardsTarget` &middot; line 1382 -- order-time pending: pay FUN_004234b0 on this visit. XHuman 12 grunt
- `BattleNetHarvestSystem.java` &middot; `stepHarvest` &middot; line 530 -- XHuman 12 peon 1550 sits at (5,27) with approach (5,28)
- `BattleNetMovementSystem.java` &middot; `spendTheEmptyRoute` &middot; line 1021 -- Live attack chases that emptied a short BNE route (XHuman 12 grunt
- `BattleNetMovementSystem.java` &middot; `stepMove` &middot; line 1277 -- Still-acquisition (XHuman 12 grunt 1358: path=22 exhausts
- `BattleNetMovementSystem.java` &middot; `stepMoveOrderWithBattleNetCritter` &middot; line 425 -- in the Attack program. Soft-clearing those at XHuman 12 residual replan
- `BattleNetMovementSystem.java` &middot; `walkTowards` &middot; line 654 -- E on fixture c22; XHuman 12 uses the centre-facing
- `BattleNetPathFinder.java` &middot; `BattleNetPathFinder` &middot; line 261 -- prefix when wall-follow's first step is worse. XHuman 12
- `BattleNetProjectileSystem.java` &middot; `flushBattleNetCycleEndConstructorDebit` &middot; line 96 -- and motion arm on that stale geometry. XHuman 12 archer→grunt
- `BattleNetProjectileSystem.java` &middot; `prepareBattleNetProjectile` &middot; line 158 -- the next projectile pass (XHuman 12 axe→tower: rem 146@33, 134@34
- `BattleNetProjectileSystem.java` &middot; `stepMissiles` &middot; line 330 -- XHuman 12@35 (seed 6888) without a traveler-count reorder.
- `Missile.java` &middot; `setBattleNetSkipNextMotionDraw` &middot; line 515 -- XHuman 12 fixture 35. -1 means not yet allocated.
- `Unit.java` &middot; `decayBuffs` &middot; line 478 -- pass (XHuman 12 grunt 1481: next=12 at c13, action=12 at c14).
- `Unit.java` &middot; `setBattleNetChaseLegOpensCold` &middot; line 2553 -- fourteen (XHuman 12 grunt 1507).
- `Unit.java` &middot; `setBattleNetChaseReplanResidualHold` &middot; line 2205 -- <p>XHuman 12 grunt 1507 free-detours N at fixture 36 with multi leftover
- `Unit.java` &middot; `setBattleNetMultiLeftoverMelee` &middot; line 2239 -- 127's flight one cycle early on XHuman 12, spent an extra parabolic
- `Unit.java` &middot; `setPath` &middot; line 3262 -- without waiting (XHuman 12 grunt 1503). Resetting every setPath
- `Unit.java` &middot; `setPathWaitBudget` &middot; line 1518 -- fifteenth clears the counter. XHuman 12 peon 1554 stays on the wait-1
- `World.java` &middot; `battleNetAutoAttack` &middot; line 11358 -- range: XHuman 12 archer 1450 opens action 16 on the footman at
- `World.java` &middot; `battleNetMeleeSyncRandType` &middot; line 3342 -- range of the aggressor (XHuman 12 grunt 1481: dist 7, react 6).
- `World.java` &middot; `battleNetRangedChaseUnit` &middot; line 7629 -- owed before the first new heading (XHuman 12 grunt 1495).
- `World.java` &middot; `battleNetSpatialHelpReactPlusOne` &middot; line 3377 -- Attack at fixture 21 and XHuman 12 footman 1478 at 28. Person
- `World.java` &middot; `failsWall` &middot; line 4830 -- when they only hold a one-step leftover. XHuman 12 residual replan
- `World.java` &middot; `findBattleNetPointPath` &middot; line 4471 -- isMoving true -- soft-clearing them opened NW for XHuman 12
- `World.java` &middot; `findBattleNetTargetPath` &middot; line 4689 -- (XHuman 12 residual replan east wall-follow).
- `World.java` &middot; `freeBattleNetProjectileSlot` &middot; line 163 -- Native staggers brothers that share a react+1 band (XHuman 12 ogres
- `World.java` &middot; `openBattleNetAttackAfterChaseResidual` &middot; line 11257 -- melee mark made XHuman 12 axe 127 apply tower
- `World.java` &middot; `setBattleNetSequenceData` &middot; line 529 -- coordinates (XHuman 12 mover {@code 43*32+29 = 1405}).
- `World.java` &middot; `tick` &middot; line 7284 -- One spatial-help promote per player per cycle. XHuman 12

## XOrc 2  (7 references)

- `BattleNetMovementSystem.java` &middot; `stepMove` &middot; line 2407 -- neighbour is a detour, not a soft-wait. XOrc 2 peon
- `BattleNetMovementSystem.java` &middot; `stepMoveOrder` &middot; line 190 -- 20-byte 0xff route (building XOrc 2 1580 → 29,21,
- `BattleNetMovementSystem.java` &middot; `stepMoveOrderWithBattleNetCritter` &middot; line 366 -- footprint goals (XOrc 2 1580 → hall) always fall through.
- `World.java` &middot; `findBattleNetPointPath` &middot; line 4484 -- step when the goal is under a building: XOrc 2's 1580 aims at

## XOrc 4  (6 references)

- `AiPlayer.java` &middot; `battleNetTryTrainWorker` &middot; line 1671 -- resets its action-33 counter without training, while XOrc 4/5/11
- `BattleNetIdleSystem.java` &middot; `stepBattleNetHallStill` &middot; line 608 -- sealed XOrc 4/5 c3 series without a forced timer clear (which
- `BattleNetProjectileSystem.java` &middot; `launch` &middot; line 284 -- the ChonkCraft gameplay footprint. XOrc 4's zeppelin is 2x2 in ChonkCraft
- `World.java` &middot; `battleNetIsBlacksmith` &middot; line 11077 -- Human town-hall. Sealed XOrc 4/5 computer openings debit a peasant at
- `World.java` &middot; `battleNetIsFlyerRoost` &middot; line 11124 -- c3/c8/c13 series for XOrc 4/5.
- `World.java` &middot; `isBattleNetArmedTower` &middot; line 10997 -- constructor cadence. Human town-halls also run action 33: XOrc 4 p2 and

## XOrc 5  (1 references)

- `World.java` &middot; `isBattleNetArmedTower` &middot; line 10998 -- XOrc 5 p3 debit a peasant (400 gold) at fixture cycle 13. Barracks

## XOrc 6  (9 references)

- `AiPlayer.java` &middot; `battleNetBarracksType` &middot; line 1896 -- <p>XHuman 7 p5 and XOrc 6 p2 debit 2500 gold (dragon/gryphon) at
- `AiPlayer.java` &middot; `battleNetTryTrainFlyer` &middot; line 1905 -- debit on the opening pulse (XOrc 6 c15 must start exactly one).
- `AiPlayer.java` &middot; `battleNetTryTrainWorker` &middot; line 1672 -- (1 peasant) and XOrc 6/10 (2) debit.
- `BattleNetIdleSystem.java` &middot; `battleNetBuildingTrainPulse` &middot; line 724 -- XOrc 6: free aviary must start the second gryphon at fixture c18
- `BattleNetIdleSystem.java` &middot; `stepBattleNetHallStill` &middot; line 609 -- over-spent later-opening halls on Orc 4 and XOrc 6/10/11);

## XOrc 7  (7 references)

- `AiPlayer.java` &middot; `battleNetTryResearchFoundry` &middot; line 2234 -- Sealed XOrc 7 funds naval research from a huge oil bank; ordinary
- `AiPlayer.java` &middot; `battleNetTryTrainFlyer` &middot; line 1989 -- wants. Sealed arms so far: tankers (XHuman 5/8), destroyers (XOrc 7 dual
- `AiPlayer.java` &middot; `battleNetUnitReady` &middot; line 2467 -- AI: XOrc 7's gryphons stay Still through the early window while
- `AiPlayer.java` &middot; `researchTier` &middot; line 2212 -- 0x8f armor2. XOrc 7 p2 debits at fixture c16 (700g/100w/1000oil class).
- `BattleNetIdleSystem.java` &middot; `battleNetBuildingTrainPulse` &middot; line 766 -- 0x40f4b0: naval research. XOrc 7 c16 with freeze 7.
- `BattleNetIdleSystem.java` &middot; `stepBattleNetHallStill` &middot; line 616 -- XOrc 7 foundry research lands at fixture c16 (third OP0).
- `World.java` &middot; `battleNetNavalPatrolTarget` &middot; line 10457 -- tanker's fixed water component. XOrc 7's first tanker at (24,6)

## XOrc 8  (29 references)

- `AiPlayer.java` &middot; `battleNetTryResearchLumberMill` &middot; line 2394 -- <p>XOrc 8 p2 upgrades a human watch tower at fixture c15 (500g/150w)
- `AiPlayer.java` &middot; `battleNetTryResearchTemple` &middot; line 2341 -- upgrades. XOrc 8 p2 debits throwing-axe1 (code 0x80) at fixture c15
- `AiPlayer.java` &middot; `battleNetTryUpgradeWatchTower` &middot; line 2413 -- That is the sealed XOrc 8 c15 dual-spend shape; maps whose first
- `AiPlayer.java` &middot; `battleNetUnitReady` &middot; line 2465 -- constructor markers (XOrc 8 gryphons at fixture cycles 5/6/7).
- `BattleNetHarvestSystem.java` &middot; `battleNetOilTankerBoardSeat` &middot; line 2164 -- <p>Footprint cover of the approach point is the XOrc 8 path. Orc 14
- `BattleNetHarvestSystem.java` &middot; `beginHarvest` &middot; line 209 -- short action-25 delay. XOrc 8 walks into cover of 115,53 from
- `BattleNetHarvestSystem.java` &middot; `stepHarvest` &middot; line 668 -- Walk-in cover (XOrc 8 footprint cover, Orc 14 board
- `BattleNetIdleSystem.java` &middot; `autoAttackStand` &middot; line 224 -- aircraft again on a fifty-cycle beat -- XOrc 8's behaviour-four draws
- `BattleNetMovementSystem.java` &middot; `stepMove` &middot; line 1379 -- is the same ray the pathfinder drew (XOrc 8's battleship
- `BattleNetPathFinder.java` &middot; `BattleNetPathFinder` &middot; line 271 -- XOrc 8 destroyer 1426 stepped 62,102→64,104 while native
- `Unit.java` &middot; `decayBuffs` &middot; line 332 -- at its start tile. XOrc 8 gryphon 1550 first-steps after eight holds
- `World.java` &middot; `battleNetNavalPatrolTarget` &middot; line 10543 -- 36,82 toward 41,85. XOrc 8 / XOrc 10 keep owned or sole
- `World.java` &middot; `beginBattleNetPendingPatrol` &middot; line 11513 -- (XOrc 11 slot 1519: 21,34 → 22,36; XOrc 8 destroyer 1430: refinery
- `World.java` &middot; `fireOnReadyForAll` &middot; line 10120 -- <p>Profile 35 takes three unmarked surface naval attackers (XOrc 8
- `World.java` &middot; `stepPatrol` &middot; line 9543 -- plain endpoint swap. After eight free visits (XOrc 8: ready c5

## XOrc 10  (13 references)

- `BattleNetConstructionSystem.java` &middot; `battleNetApproachBuildingClearance` &middot; line 151 -- Soft-clearing the worker used to accept XOrc 10's farm at 109,5 -- the
- `BattleNetConstructionSystem.java` &middot; `stepConstruction` &middot; line 1411 -- before the first Boost (XOrc 10 farm founded at fixture c22 with
- `BattleNetConstructionSystem.java` &middot; `stepWalkToSite` &middot; line 1207 -- builder outside StartBuilding (XOrc 10 peasant 1573: native founds
- `BattleNetMovementSystem.java` &middot; `stepMoveOrder` &middot; line 111 -- XOrc 10 1427 / Human 12 1576 a later wander-band choice at
- `BattleNetPathFinder.java` &middot; `BattleNetPathFinder` &middot; line 507 -- XOrc 10 destroyer 1483 (stride 2) at (124,74) toward oil
- `Unit.java` &middot; `decayBuffs` &middot; line 407 -- (XOrc 10 farm: +3, +4, +3, … from the 360/100 accumulator).
- `World.java` &middot; `battleNetNavalPatrolTarget` &middot; line 10543 -- 36,82 toward 41,85. XOrc 8 / XOrc 10 keep owned or sole
- `World.java` &middot; `beginBattleNetPendingPatrol` &middot; line 11539 -- open-water wiggle (XOrc 10 destroyers with no oil service base)
- `World.java` &middot; `replaceOnDie` &middot; line 5852 -- climb. XOrc 10 farm 1426: founded fixture c22 at HP 40, first boost

## XOrc 11  (28 references)

- `AiPlayer.java` &middot; `battleNetTryTrainFlyer` &middot; line 1948 -- debited 2500g at fixture 35 while native held 12200), and XOrc 11
- `AiPlayer.java` &middot; `battleNetTryTrainWorker` &middot; line 1735 -- fixture cycle 16; XHuman 2 / XOrc 11 still take the empty-basic arm.
- `BattleNetIdleSystem.java` &middot; `battleNetBuildingTrainPulse` &middot; line 739 -- zero; XHuman 3 ogre and XOrc 11 footman debit from wants alone.
- `BattleNetMovementSystem.java` &middot; `stepMove` &middot; line 1355 -- Bresenham first step was cardinal. XOrc 11's battleship at
- `BattleNetPathFinder.java` &middot; `BattleNetPathFinder` &middot; line 483 -- XOrc 11 destroyer 1558 (stride 2): east corridor scores
- `Unit.java` &middot; `decayBuffs` &middot; line 548 -- barracks place with data 0 and never auto-train while XHuman 2 / XOrc 11
- `World.java` &middot; `battleNetNearestNavalOpenWater` &middot; line 11636 -- (blocked) square as the active goal. XOrc 11's destroyer at (22,38)
- `World.java` &middot; `beginBattleNetPendingPatrol` &middot; line 11513 -- (XOrc 11 slot 1519: 21,34 → 22,36; XOrc 8 destroyer 1430: refinery
- `World.java` &middot; `findBattleNetPointPath` &middot; line 4436 -- building footprint (XOrc 11 destroyer 1519).</p>
- `World.java` &middot; `isBattleNetArmedTower` &middot; line 11001 -- 2 / XOrc 11 place with data 1 and debit 600 at the third OP0. XHuman 8
- `World.java` &middot; `stepPatrol` &middot; line 9473 -- SE headings in the same breath -- XOrc 11 destroyer 1542 (Java 58)

## XOrc 12  (12 references)

- `BattleNetConstructionSystem.java` &middot; `findBattleNetBuildingPath` &middot; line 395 -- still soft-clears (XHuman 7 / XOrc 12).
- `BattleNetHarvestSystem.java` &middot; `chopInPlace` &middot; line 1165 -- desynced XOrc 12; the arm is the 25-cycle animation loop, not
- `BattleNetHarvestSystem.java` &middot; `stepHarvest` &middot; line 498 -- cold-commit) sent XOrc 12 peasant 1396 back through walkTowards
- `BattleNetHarvestSystem.java` &middot; `walkToWood` &middot; line 1606 -- <p>XOrc 12's peasant at (16,28) bound for (14,29) has two one-step
- `BattleNetIdleSystem.java` &middot; `autoAttackStand` &middot; line 236 -- XOrc 12's flying angel fight and are marked and do not, which is why
- `BattleNetMovementSystem.java` &middot; `walkTowards` &middot; line 725 -- wall follower detours (XOrc 12 peasant 1396 at 32,75
- `BattleNetPathFinder.java` &middot; `BattleNetPathFinder` &middot; line 244 -- 11/12 and XOrc 12 wood peons; taking wall on every equal-
- `World.java` &middot; `rescueBattleNetUnit` &middot; line 1258 -- Flyers are rescuable: XOrc 12's fire-breeze at (120,8) changes from
- `World.java` &middot; `syncRand` &middot; line 8989 -- (state * 0x41c64e6d + 0x3039). Fixture seeds (XOrc 12 cycle 5:
