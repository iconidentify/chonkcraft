package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.pathfinder.BattleNetPathFinder;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Locks behavior-one incumbent and blocked-retarget boundaries on XHuman 12. */
class XHuman12BehaviorOneRetargetRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("marker-zero land guards answer hit help through the normal route writer")
    void markerZeroLandGuardsAnswerHitHelpThroughTheNormalRouteWriter() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        Unit southGuard = unitAt(world, "unit-grunt", 17, 60);
        Unit northGuard = unitAt(world, "unit-grunt", 18, 42);
        assertNotNull(southGuard, "XHuman 12 has no native-slot-1447 grunt");
        assertNotNull(northGuard, "XHuman 12 has no native-slot-1481 grunt");
        assertEquals(1, southGuard.battleNetAiBehavior());
        assertEquals(1, northGuard.battleNetAiBehavior());
        assertEquals(false, southGuard.battleNetReadySuppressed(),
                "native slot 1447 carries ai_marker zero");
        assertEquals(false, northGuard.battleNetReadySuppressed(),
                "native slot 1481 carries ai_marker zero despite behavior one");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 19) {
            mission.tick();
            int fixture = fixtureCycle(world);
            if (fixture == 14) {
                assertEquals(Unit.Order.ATTACK, northGuard.order(),
                        "marker-zero grunt answers from beyond the four-tile gate");
            }
            if (fixture == 16) {
                assertEquals(Unit.Order.ATTACK, southGuard.order());
                assertEquals(3, southGuard.battleNetAnimationTimer(),
                        "promoted land help opens native Attack construction");
            }
        }

        assertEquals(18, southGuard.tileX(),
                "land pathfinding detours east instead of forcing direct SW");
        assertEquals(61, southGuard.tileY());
        assertEquals(19, northGuard.tileX());
        assertEquals(42, northGuard.tileY());
    }

    @Test
    @DisplayName("behavior-one grunts retarget on native chase boundaries")
    void behaviorOneGruntsRetargetOnNativeChaseBoundaries() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        Unit buildingUpgrade = unitAt(world, "unit-grunt", 19, 46);
        Unit blockedUpgrade = unitAt(world, "unit-grunt", 21, 42);
        assertNotNull(buildingUpgrade, "XHuman 12 has no native-slot-1470 grunt");
        assertNotNull(blockedUpgrade, "XHuman 12 has no native-slot-1480 grunt");
        assertEquals(1, buildingUpgrade.battleNetAiBehavior());
        assertEquals(1, blockedUpgrade.battleNetAiBehavior());

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 60) {
            mission.tick();
            int fixture = fixtureCycle(world);
            if (fixture == 25) {
                assertTargetAt(buildingUpgrade, "unit-human-guard-tower", 24, 50,
                        "an equal building score retains behavior one's incumbent");
                assertTargetAt(blockedUpgrade, "unit-human-guard-tower", 25, 42,
                        "the nearer defender still owns its opening tower goal");
            }
            if (fixture == 41) {
                assertEquals(22, buildingUpgrade.tileX());
                assertEquals(44, buildingUpgrade.tileY());
                assertTargetAt(buildingUpgrade, "unit-human-guard-tower", 25, 42,
                        "a strict building upgrade retargets after the cached NE");
                assertEquals(Direction.fromDelta(0, -1),
                        buildingUpgrade.peekHeading(),
                        "the replacement route keeps native north next");

                assertEquals(22, blockedUpgrade.tileX());
                assertEquals(42, blockedUpgrade.tileY());
                assertTargetAt(blockedUpgrade, "unit-footman", 29, 43,
                        "blocked building path work hands off to the mobile threat");
                assertEquals(19, blockedUpgrade.pathLength());
                assertEquals(Direction.fromDelta(0, 1), blockedUpgrade.peekHeading());
            }
            if (fixture == 57) {
                assertEquals(22, buildingUpgrade.tileX(),
                        "the first retarget leg pays its residual hold");
                assertEquals(44, buildingUpgrade.tileY());
                assertEquals(22, blockedUpgrade.tileX());
                assertEquals(42, blockedUpgrade.tileY());
            }
        }

        assertEquals(22, buildingUpgrade.tileX());
        assertEquals(43, buildingUpgrade.tileY(),
                "north follows the residual hold at fixture 60");
        assertEquals(23, blockedUpgrade.tileX());
        assertEquals(41, blockedUpgrade.tileY(),
                "the blocked retarget returns to the tower and steps NE at fixture 60");
        assertTargetAt(blockedUpgrade, "unit-human-guard-tower", 25, 42,
                "the next behavior-one boundary selects the stronger live goal");
    }

    @Test
    @DisplayName("an exhausted retarget clears the old single collision generation")
    void exhaustedRetargetClearsTheOldSingleCollisionGeneration() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        // Native slot 1453 / Java 147. Its first tower route pays one
        // cooperative collision generation at fixture 21, successfully wakes
        // north at fixture 36, and retains that ownership until the route is
        // exhausted. On fixture 68 BNE replaces the tower with knight 154,
        // commits the new SE ray, and clears the collision byte. Leaving it
        // set makes native slot 1463 treat this moving lead grunt as a hard
        // blocker at fixture 123 and step out of formation on 124.
        Unit lead = unitAt(world, "unit-grunt", 18, 57);
        assertNotNull(lead, "XHuman 12 has no native-slot-1453 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 123) {
            mission.tick();
            int fixture = fixtureCycle(world);
            if (fixture == 21) {
                assertEquals(1, lead.battleNetCollisionCounter(),
                        "the first blocked cached north owns collision one");
            }
            if (fixture == 36) {
                assertEquals(19, lead.tileX());
                assertEquals(55, lead.tileY());
                assertEquals(1, lead.battleNetCollisionCounter(),
                        "a cached-route wake retains its collision owner");
            }
            if (fixture == 68) {
                assertEquals(21, lead.tileX());
                assertEquals(55, lead.tileY());
                assertTargetAt(lead, "unit-knight", 26, 60,
                        "the exhausted tower route retargets to the knight");
                assertEquals(0, lead.battleNetCollisionCounter(),
                        "committing NewPath clears the old single generation");
            }
        }

        assertEquals(22, lead.tileX());
        assertEquals(58, lead.tileY());
        assertEquals(0, lead.battleNetCollisionCounter(),
                "the lead grunt remains cooperative for its crowded follower");
    }

    @Test
    @DisplayName("a spent one-byte retarget pays cold attack construction")
    void spentOneByteRetargetPaysColdAttackConstruction() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        // Native slot 1470 / Java 130. Its one-byte E route settles on
        // fixture 124 as target scan replaces the tower with footman 1478.
        // Retail closes Move into Attack 3,2,1 and only draws/commits N on
        // fixture 127; immediately planning made the grunt glide north three
        // visits early and shifted the following melee damage roll.
        Unit grunt = unitAt(world, "unit-grunt", 19, 46);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1470 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 127) {
            mission.tick();
            int fixture = fixtureCycle(world);
            if (fixture >= 124 && fixture <= 126) {
                assertEquals(23, grunt.tileX());
                assertEquals(42, grunt.tileY(),
                        "Attack construction must retain the battle square");
                assertTargetAt(grunt, "unit-footman", 29, 43,
                        "target scan installs the replacement immediately");
                assertEquals(world.idle.battleNetSequenceStart(grunt,
                                net.chonkbase.chonkcraft.engine.animation
                                        .BattleNetSequence.ATTACK_ANIMATION),
                        grunt.battleNetSequenceOffset());
                assertEquals(127 - fixture,
                        grunt.battleNetAnimationTimer(),
                        "retail exposes Attack timers 3,2,1");
                assertEquals(0, grunt.pathLength(),
                        "the replacement route is not drawn during construction");
            }
        }

        assertEquals(23, grunt.tileX());
        assertEquals(41, grunt.tileY(),
                "the paid handoff commits the replacement north heading");
    }

    @Test
    @DisplayName("a spent single retarget drains construction without a surrogate delay")
    void spentSingleRetargetDrainsConstructionWithoutASurrogateDelay() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        // Native slot 1476 / Java 124 has spent the only south heading in its
        // replacement buffer when it enters Attack construction on fixture
        // 124. Retail exposes 3,2,1 without also sleeping that sequence behind
        // an order delay. Timer one therefore selects footman 1478, writes a
        // full replacement route and commits north on fixture 127. The extra
        // surrogate delay left the grunt visibly parked until fixture 136.
        Unit grunt = unitAt(world, "unit-grunt", 22, 44);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1476 grunt");
        assertEquals(124, grunt.id());

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 127) {
            mission.tick();
            int fixture = fixtureCycle(world);
            if (fixture >= 124 && fixture <= 126) {
                assertEquals(23, grunt.tileX());
                assertEquals(48, grunt.tileY(),
                        "Attack construction retains the completed south leg");
                assertEquals(world.idle.battleNetSequenceStart(grunt,
                                net.chonkbase.chonkcraft.engine.animation
                                        .BattleNetSequence.ATTACK_ANIMATION),
                        grunt.battleNetSequenceOffset());
                assertEquals(127 - fixture,
                        grunt.battleNetAnimationTimer(),
                        "retail drains the exposed Attack timers 3,2,1");
            }
        }

        assertEquals(23, grunt.tileX());
        assertEquals(47, grunt.tileY(),
                "timer one must hand the paid retarget directly to Move");
        assertTargetAt(grunt, "unit-footman", 29, 43,
                "the timer-one scan installs the native replacement quarry");
        assertEquals(19, grunt.pathLength(),
                "the first north byte is spent from a full native route");
        assertEquals(Direction.fromDelta(0, -1), grunt.lastStepHeading());
    }

    @Test
    @DisplayName("an exhausted long approach parks before active-order retry")
    void exhaustedLongApproachParksBeforeActiveOrderRetry() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        // Sealed BNE slot 1381 / Java 219 finishes the pixels of its long east
        // approach on fixture 118. Retail leaves the exhausted Move cursor
        // parked for that visit, then active-order Still pays its idle draw
        // and opens Attack 3 on fixture 119. Folding both callbacks together
        // gives this ogre an early draw and assigns the wrong damage roll to
        // native slot 1446 / Java 154 at fixture 125.
        Unit ogre = unitAt(world, "unit-ogre", 6, 84);
        Unit knight = unitAt(world, "unit-knight", 27, 60);
        assertNotNull(ogre, "XHuman 12 has no native-slot-1381 ogre");
        assertNotNull(knight, "XHuman 12 has no native-slot-1446 knight");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 125) {
            mission.tick();
            int fixture = fixtureCycle(world);
            if (fixture == 118) {
                assertEquals(11, ogre.tileX());
                assertEquals(87, ogre.tileY());
                assertEquals(1, ogre.battleNetAnimationTimer());
                assertTrue(ogre.battleNetResidualEmptyApproachIdlePending(),
                        "the residual-settle visit owns only the RI20 Move park");
            }
            if (fixture == 119) {
                assertEquals(world.idle.battleNetSequenceStart(ogre,
                                net.chonkbase.chonkcraft.engine.animation
                                        .BattleNetSequence.ATTACK_ANIMATION),
                        ogre.battleNetSequenceOffset(),
                        "active-order retry opens Attack on the next callback");
                assertEquals(3, ogre.battleNetAnimationTimer());
            }
        }

        assertEquals(65, knight.hitPoints(),
                "the native fixture-125 melee draw deals six damage");
    }

    @Test
    @DisplayName("a strict mobile upgrade re-enters attack construction")
    void strictMobileUpgradeReentersAttackConstruction() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        // Native slot 1468 / Java 132 has already consumed several headings
        // toward a footman when hit-help offers a strictly better knight on
        // fixture 119. Retail discards the compact incumbent tail through the
        // land Still callback, pays its idle draw and exposes Attack 3 before
        // drawing the replacement route. Skipping that callback assigns the
        // next damage roll to slot 1502 / Java 98 on fixture 121.
        Unit upgrading = unitAt(world, "unit-grunt", 18, 48);
        Unit damageWitness = unitAt(world, "unit-grunt", 33, 38);
        assertNotNull(upgrading, "XHuman 12 has no native-slot-1468 grunt");
        assertNotNull(damageWitness, "XHuman 12 has no native-slot-1502 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 121) {
            mission.tick();
            if (fixtureCycle(world) == 119) {
                assertEquals("unit-knight", upgrading.target().type().ident(),
                        "the higher-scored mobile quarry wins immediately");
                assertEquals(world.idle.battleNetSequenceStart(upgrading,
                                net.chonkbase.chonkcraft.engine.animation
                                        .BattleNetSequence.ATTACK_ANIMATION),
                        upgrading.battleNetSequenceOffset());
                assertEquals(3, upgrading.battleNetAnimationTimer(),
                        "retail re-enters Attack construction before replanning");
                assertEquals(0, upgrading.pathLength());
            }
        }

        assertEquals(33, damageWitness.hitPoints(),
                "the native fixture-121 blow deals five damage");
    }

    @Test
    @DisplayName("a moved-quarry stale-prefix park keeps native collision ownership")
    void movedQuarryStalePrefixParkKeepsNativeCollisionOwnership() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        // Sealed BNE slots 1489 and 1480 respectively. Slot 1489 parks a
        // stale NE route at fixture 92 when its knight quarry moves, and the
        // raw unit record changes collision byte 0x1d from 0x00 to 0x10.
        Unit movingBlocker = unitAt(world, "unit-grunt", 22, 40);
        Unit followingGrunt = unitAt(world, "unit-grunt", 21, 42);
        assertNotNull(movingBlocker, "XHuman 12 has no native-slot-1489 grunt");
        assertNotNull(followingGrunt, "XHuman 12 has no native-slot-1480 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 114) {
            mission.tick();
            int fixture = fixtureCycle(world);
            if (fixture == 92) {
                assertEquals(27, movingBlocker.tileX());
                assertEquals(39, movingBlocker.tileY());
                assertEquals(1, movingBlocker.battleNetCollisionCounter(),
                        "parking the refused stale prefix raises native's "
                                + "collision high nibble to one");
                assertEquals(1, movingBlocker.battleNetRefusals(),
                        "the parked stale prefix is the first refusal generation");
            }
        }

        assertEquals(1, movingBlocker.battleNetCollisionCounter(),
                "the moving blocker remains solid to native route planning");
        assertEquals(26, followingGrunt.tileX());
        assertEquals(39, followingGrunt.tileY(),
                "the following grunt takes BNE's north-east wall face");
    }

    @Test
    @DisplayName("a fully paid formation refusal spends a free cached head on timer one")
    void fullyPaidFormationRefusalSpendsAFreeCachedHeadOnTimerOne() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        // Native slot 1494 / Java 106. Its cached NE is occupied when the
        // preceding SW residual settles at fixture 102. BNE raises collision
        // 3 -> 4, pays Move timer 15..1, and consumes that same NE byte on
        // fixture 117 after the blocker leaves; it does not add a timer-zero
        // visit between the paid band and the successful probe.
        Unit grunt = unitAt(world, "unit-grunt", 26, 39);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1494 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 117) {
            mission.tick();
            int fixture = fixtureCycle(world);
            if (fixture == 102) {
                assertEquals(28, grunt.tileX());
                assertEquals(38, grunt.tileY());
                assertEquals(4, grunt.battleNetCollisionCounter());
                assertEquals(15, grunt.battleNetOrderDelay(),
                        "the first refused probe owns BNE's full Move wait");
            }
            if (fixture == 116) {
                assertEquals(28, grunt.tileX());
                assertEquals(38, grunt.tileY());
                assertEquals(1, grunt.battleNetOrderDelay());
            }
        }

        assertEquals(29, grunt.tileX(),
                "timer one must hand the now-free cached head to movement");
        assertEquals(37, grunt.tileY());
        assertEquals(16, grunt.pathLength(),
                "the successful probe consumes exactly one cached heading");
        assertEquals(4, grunt.battleNetCollisionCounter(),
                "saturated formation collision provenance survives the step");
    }

    @Test
    @DisplayName("saturated formation routes hand off without sliding or freezing")
    void saturatedFormationRoutesHandOffWithoutSlidingOrFreezing() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        // Sealed BNE slots 1495 and 1510, paired to Java units 105 and 90.
        // Both drain their residual pixels on fixture 121, but the native
        // state machine takes opposite branches. The diagonal terminator parks
        // at route index 20 for one visit; the saturated retarget has already
        // paid formation pressure and immediately spends its new south-east
        // heading. Treating both as ordinary refills made one grunt slide
        // through its line while the other visibly froze behind Attack 3,2,1.
        Unit diagonalTerminator = unitAt(world, "unit-grunt", 27, 38);
        Unit paidRetarget = unitAt(world, "unit-grunt", 34, 38);
        assertNotNull(diagonalTerminator,
                "XHuman 12 has no native-slot-1495 grunt");
        assertNotNull(paidRetarget,
                "XHuman 12 has no native-slot-1510 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 122) {
            mission.tick();
            int fixture = fixtureCycle(world);
            if (fixture == 120) {
                assertEquals(29, diagonalTerminator.tileX());
                assertEquals(39, diagonalTerminator.tileY());
                assertEquals(36, paidRetarget.tileX());
                assertEquals(38, paidRetarget.tileY());
            }
            if (fixture == 121) {
                assertEquals(29, diagonalTerminator.tileX(),
                        "diagonal RI20 park must not slide south-east");
                assertEquals(39, diagonalTerminator.tileY());
                assertEquals(6,
                        diagonalTerminator.battleNetCollisionCounter(),
                        "native collision byte advances 0x50 to 0x60");
                assertEquals(0, diagonalTerminator.pathLength(),
                        "native route index 20 exposes an exhausted buffer");

                assertEquals(37, paidRetarget.tileX(),
                        "paid formation pressure commits the replacement ray");
                assertEquals(39, paidRetarget.tileY());
                assertEquals(0, paidRetarget.battleNetCollisionCounter(),
                        "native NewPath clears the paid collision generation");
                assertTargetAt(paidRetarget, "unit-footman", 32, 43,
                        "the replacement target is native slot 1512");
            }
        }

        assertEquals(29, diagonalTerminator.tileX(),
                "the RI20 owner remains parked during Attack construction");
        assertEquals(39, diagonalTerminator.tileY());
        assertEquals(0, diagonalTerminator.battleNetCollisionCounter(),
                "the completed generation clears on the following callback");
        assertEquals(3, diagonalTerminator.battleNetAnimationTimer(),
                "native enters Attack-start construction at fixture 122");
        assertEquals(37, paidRetarget.tileX(),
                "the replacement move is draining sub-tile pixels, not frozen");
        assertEquals(39, paidRetarget.tileY());
    }

    @Test
    @DisplayName("late crowded retargets refill their route before the shared frontier")
    void lateCrowdedRetargetsRefillTheirRouteBeforeTheSharedFrontier() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        // Native slot 1510 / Java 90 parks the old tower route on fixture
        // 206, then writes E,SE,S,... and consumes E on 207. Java previously
        // kept E,SE,SE,...; both engines consequently occupied the same tiles
        // through 238 and only exposed the stale third byte at fixture 239.
        Unit towerChaser = unitAt(world, "unit-grunt", 34, 38);
        // Native slot 1513 / Java 87 finishes its southeast residual on
        // fixture 240 while AutoSelectTarget replaces footman 1477 with guard
        // tower 1485. Retail pays the active-order land-idle callback and
        // immediately opens Attack 3,2,1; Java used to expose Move-start/1
        // with a two-visit sleep, leaving the asynchronous stream two damage
        // rolls behind by fixture 249.
        Unit buildingRetargetChaser = unitAt(
                world, "unit-grunt", 36, 37);
        // Native slot 1503 / Java 97 lands from a long retained chase on
        // fixture 252 as the target changes from footman 1477 to guard tower
        // 1485. The fourteen-byte incumbent tail is not the compact-pressure
        // constructor witnessed by slot 1513: retail redraws, commits E and
        // gives the cycle's next async value to melee damage instead of an
        // idle callback.
        Unit longTailRetargetChaser = unitAt(
                world, "unit-grunt", 31, 38);
        // Native slot 1479 / Java 121 is the independent long-tail witness.
        // It settles the fourth heading of a twenty-byte knight route on
        // fixture 265, changes to guard tower 1483, writes SW,SW,S and commits
        // southwest immediately. Its sixteen-byte incumbent and two prior
        // refusals differ from slot 1503's fourteen-byte/refusal-one shape.
        Unit frontierLongTailRetarget = unitById(world, 121);
        Unit guardTower = unitAt(
                world, "unit-human-guard-tower", 39, 41);
        Unit defenderKnight = unitAt(world, "unit-knight", 30, 44);
        // Native slot 1453 / Java 147 is routeless when its knight quarry
        // enters Die on fixture 239. Retail installs the replacement footman
        // immediately but restarts cold Attack 3,2,1 before any new route.
        Unit dyingQuarryChaser = unitAt(world, "unit-grunt", 18, 57);
        assertNotNull(towerChaser, "XHuman 12 has no native-slot-1510 grunt");
        assertNotNull(buildingRetargetChaser,
                "XHuman 12 has no native-slot-1513 grunt");
        assertNotNull(longTailRetargetChaser,
                "XHuman 12 has no native-slot-1503 grunt");
        assertNotNull(frontierLongTailRetarget,
                "XHuman 12 has no native-slot-1479 grunt");
        assertNotNull(guardTower,
                "XHuman 12 has no native-slot-1485 guard tower");
        assertNotNull(defenderKnight,
                "XHuman 12 has no native-slot-1475 knight");
        assertNotNull(dyingQuarryChaser,
                "XHuman 12 has no native-slot-1453 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 249) {
            mission.tick();
            int fixture = fixtureCycle(world);
            if (fixture == 206) {
                assertEquals(0, towerChaser.pathLength(),
                        "native parks the old route at index twenty");
            }
            if (fixture == 207) {
                assertEquals(41, towerChaser.tileX(),
                        "the replacement route opens east");
                assertEquals(38, towerChaser.tileY());
                assertEquals(Direction.fromDelta(1, 1),
                        towerChaser.peekHeadingAtDepth(0),
                        "the replacement route then turns south-east");
                assertEquals(Direction.fromDelta(0, 1),
                        towerChaser.peekHeadingAtDepth(1),
                        "the refill rewrites the stale duplicate diagonal");
            }
            if (fixture == 239 || fixture == 242 || fixture == 245
                    || fixture == 248) {
                assertEquals(23, dyingQuarryChaser.tileX(),
                        "a boxed retarget must not take a non-progressing detour at "
                                + fixture);
                assertEquals(60, dyingQuarryChaser.tileY(),
                        "the cold refusal loop retains the battle square at "
                                + fixture);
                assertEquals(3, dyingQuarryChaser.battleNetAnimationTimer(),
                        "each refused probe reopens Attack construction");
                assertEquals(0, dyingQuarryChaser.pathLength(),
                        "a no-progress retry keeps native route index twenty");
                if (fixture == 239) {
                    assertEquals(0x6a0aecce, world.battleNetRandomSeed(),
                            "the initial three cold constructors pay land-idle");
                }
            }
            if (fixture == 240 || fixture == 243 || fixture == 246
                    || fixture == 249) {
                assertEquals(40, buildingRetargetChaser.tileX(),
                        "the building retarget retains its settled square at "
                                + fixture);
                assertEquals(39, buildingRetargetChaser.tileY());
                assertTargetAt(buildingRetargetChaser,
                        "unit-human-guard-tower",
                        39, 41,
                        "the settled residual selects native tower 1485");
                assertEquals(world.idle.battleNetSequenceStart(
                                buildingRetargetChaser,
                                net.chonkbase.chonkcraft.engine.animation
                                        .BattleNetSequence.ATTACK_ANIMATION),
                        buildingRetargetChaser.battleNetSequenceOffset(),
                        "a no-progress building retarget remains in Attack");
                assertEquals(3,
                        buildingRetargetChaser.battleNetAnimationTimer(),
                        "each native land-idle visit reopens Attack 3,2,1");
                assertEquals(0, buildingRetargetChaser.pathLength(),
                        "the blocked footprint keeps route index twenty");
            }
        }

        assertEquals(42, towerChaser.tileX(),
                "the third replacement heading is south, not south-east");
        assertEquals(40, towerChaser.tileY());
        assertEquals(23, dyingQuarryChaser.tileX(),
                "a dying-quarry retarget must not move during construction");
        assertEquals(60, dyingQuarryChaser.tileY());
        assertTargetAt(dyingQuarryChaser, "unit-footman", 26, 59,
                "the replacement is selected on the dying-quarry visit");
        assertEquals(world.idle.battleNetSequenceStart(dyingQuarryChaser,
                        net.chonkbase.chonkcraft.engine.animation
                                .BattleNetSequence.ATTACK_ANIMATION),
                dyingQuarryChaser.battleNetSequenceOffset());
        assertEquals(2, dyingQuarryChaser.battleNetAnimationTimer(),
                "fixture 249 drains the constructor reopened on 248");
        assertEquals(0, dyingQuarryChaser.pathLength(),
                "construction precedes the replacement route writer");
        assertTrue(dyingQuarryChaser.battleNetColdNoProgressRefusalLoop(),
                "the authenticated active-order retry remains the loop owner");
        assertEquals(39, defenderKnight.hitPoints(),
                "restored idle ownership gives the fixture-249 grunt blow "
                        + "native damage five");
        assertEquals(0x7aeac18f, world.battleNetRandomSeed(),
                "the complete asynchronous ledger agrees through fixture 249");

        while (fixtureCycle(world) < 252) {
            mission.tick();
        }
        assertEquals(40, longTailRetargetChaser.tileX(),
                "a long collided tail redraws and commits east immediately");
        assertEquals(38, longTailRetargetChaser.tileY());
        assertTargetAt(longTailRetargetChaser,
                "unit-human-guard-tower", 39, 41,
                "the landing callback installs the native building target");
        assertEquals(11, longTailRetargetChaser.pathLength(),
                "the committed east byte leaves the native route tail");
        assertEquals(Direction.fromDelta(1, 1),
                longTailRetargetChaser.peekHeading(),
                "the replacement route next turns south-east");
        assertEquals(0, longTailRetargetChaser.battleNetCollisionCounter(),
                "NewPath clears the obsolete collision generation");
        assertEquals(76, guardTower.hitPoints(),
                "the retarget does not steal the native melee-damage draw");

        while (fixtureCycle(world) < 264) {
            mission.tick();
        }
        assertEquals(30, frontierLongTailRetarget.tileX());
        assertEquals(36, frontierLongTailRetarget.tileY());
        assertEquals(-2, frontierLongTailRetarget.offsetX());
        assertEquals(2, frontierLongTailRetarget.offsetY(),
                "fixture 264 still owes the final northeast pixels");
        assertEquals(16, frontierLongTailRetarget.pathLength());
        assertEquals(BattleNetPathFinder.MAX_PATH,
                frontierLongTailRetarget.battleNetPathInitialLength());
        assertEquals(4,
                frontierLongTailRetarget.battleNetPathStepsTaken());
        assertEquals(3,
                frontierLongTailRetarget.battleNetCollisionCounter());
        assertEquals(2, frontierLongTailRetarget.battleNetRefusals());
        assertEquals(defenderKnight, frontierLongTailRetarget.target());

        mission.tick();
        assertEquals(265, fixtureCycle(world));
        assertEquals(29, frontierLongTailRetarget.tileX(),
                "the long-tail retarget commits southwest without construction");
        assertEquals(37, frontierLongTailRetarget.tileY());
        assertEquals(Direction.fromDelta(-1, 1),
                frontierLongTailRetarget.lastStepHeading());
        assertEquals(2, frontierLongTailRetarget.pathLength(),
                "the committed southwest leaves southwest,south cached");
        assertEquals(Direction.fromDelta(-1, 1),
                frontierLongTailRetarget.peekHeading());
        assertTargetAt(frontierLongTailRetarget,
                "unit-human-guard-tower", 25, 42,
                "the settle callback publishes native guard tower 1483");
        assertEquals(0,
                frontierLongTailRetarget.battleNetCollisionCounter());
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }

    private static void assertTargetAt(Unit unit, String ident, int x, int y,
            String message) {
        Unit target = unit.target();
        assertNotNull(target, message);
        assertEquals(ident, target.type().ident(), message);
        assertEquals(x, target.tileX(), message);
        assertEquals(y, target.tileY(), message);
    }

    private static Unit unitAt(World world, String ident, int x, int y) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.isAlive() && unit.isOnMap() && unit.type() != null
                    && ident.equals(unit.type().ident())
                    && unit.tileX() == x && unit.tileY() == y) {
                return unit;
            }
        }
        return null;
    }

    private static Unit unitById(World world, int id) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.id() == id) {
                return unit;
            }
        }
        return null;
    }
}
