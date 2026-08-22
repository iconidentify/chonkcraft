package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
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
}
