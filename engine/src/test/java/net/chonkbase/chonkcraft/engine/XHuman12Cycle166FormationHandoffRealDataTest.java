package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.animation.BattleNetSequence;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Locks crowded paid-route handoffs on XHuman 12 fixtures 166 through 172. */
class XHuman12Cycle166FormationHandoffRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("paid route handoffs preserve the cycle 166 melee formation")
    void paidRouteHandoffsPreserveTheCycle166MeleeFormation() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        Unit northMover = unitById(world, 166);
        Unit residualMover = unitById(world, 153);
        Unit spentRoutePark = unitById(world, 121);
        Unit fullRoutePark = unitById(world, 119);
        Unit settledRoutePark = unitById(world, 120);
        Unit paidSingleHeadingPark = unitById(world, 94);
        Unit laterGenerationRoutePark = unitById(world, 104);
        assertNotNull(northMover, "XHuman 12 has no native-slot-1434 grunt");
        assertNotNull(residualMover, "XHuman 12 has no native-slot-1447 grunt");
        assertNotNull(spentRoutePark, "XHuman 12 has no native-slot-1479 grunt");
        assertNotNull(fullRoutePark, "XHuman 12 has no native-slot-1481 grunt");
        assertNotNull(settledRoutePark,
                "XHuman 12 has no native-slot-1480 grunt");
        assertNotNull(paidSingleHeadingPark,
                "XHuman 12 has no native-slot-1506 grunt");
        assertNotNull(laterGenerationRoutePark,
                "XHuman 12 has no native-slot-1496 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 165) {
            mission.tick();
        }

        assertPosition(northMover, 24, 61,
                "the blocked northern guard starts on its native square");
        assertPosition(residualMover, 23, 61,
                "the residual guard starts on its native square");
        assertPosition(spentRoutePark, 26, 39,
                "the near-full route starts on its native square");
        assertPosition(fullRoutePark, 25, 38,
                "the full route starts on its native square");

        mission.tick();
        assertEquals(166, fixtureCycle(world));
        assertAll(
                () -> assertPosition(northMover, 24, 60,
                        "the newly written north detour must move the guard"),
                () -> assertPosition(residualMover, 24, 61,
                        "the committed northeast residual must settle east"),
                () -> assertPosition(spentRoutePark, 26, 39,
                        "the near-full paid route must park without stepping east"),
                () -> assertPosition(fullRoutePark, 25, 38,
                        "the saturated paid route must park without stepping southeast"));

        mission.tick();
        assertEquals(167, fixtureCycle(world));
        assertAll(
                () -> assertPosition(settledRoutePark, 27, 38,
                        "the settled long route must park before its redraw"),
                () -> assertPosition(paidSingleHeadingPark, 32, 39,
                        "the paid one-heading route must remain parked through timer one"));

        mission.tick();
        assertEquals(168, fixtureCycle(world));
        assertAll(
                () -> assertPosition(settledRoutePark, 28, 39,
                        "the parked long route must redraw and step southeast"),
                () -> assertPosition(paidSingleHeadingPark, 31, 38,
                        "the paid one-heading route must redraw and step northwest"));

        while (fixtureCycle(world) < 171) {
            mission.tick();
        }
        assertPosition(laterGenerationRoutePark, 34, 37,
                "the later collided residual must park before its south redraw");

        mission.tick();
        assertEquals(172, fixtureCycle(world));
        assertPosition(laterGenerationRoutePark, 34, 38,
                "the parked later generation must redraw and step south");
    }

    @Test
    @DisplayName("a retained cardinal probe buys its complete generation-eight refusal band")
    void retainedCardinalProbeBuysItsCompleteGenerationEightRefusalBand() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        Unit retainedProbe = unitById(world, 166);
        Unit paidProbeTail = unitById(world, 147);
        Unit damagedGrunt = unitById(world, 165);
        Unit focusGrunt = unitById(world, 159);
        Unit arrivingGrunt = unitById(world, 118);
        assertNotNull(retainedProbe,
                "XHuman 12 has no native-slot-1434 retained probe");
        assertNotNull(paidProbeTail,
                "XHuman 12 has no native-slot-1453 paid-probe tail");
        assertNotNull(damagedGrunt,
                "XHuman 12 has no native-slot-1435 damage witness");
        assertNotNull(focusGrunt,
                "XHuman 12 has no native-slot-1441 damage witness");
        assertNotNull(arrivingGrunt,
                "XHuman 12 has no native-slot-1482 arrival witness");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 195) {
            mission.tick();
        }

        int moveStart = world.idle.battleNetSequenceStart(retainedProbe,
                BattleNetSequence.MOVE_ANIMATION);
        int attackStart = world.idle.battleNetSequenceStart(retainedProbe,
                BattleNetSequence.ATTACK_ANIMATION);
        assertAll(
                () -> assertEquals(7,
                        retainedProbe.battleNetCollisionCounter(),
                        "seven one-count probes precede the paid band"),
                () -> assertEquals(moveStart,
                        retainedProbe.battleNetSequenceOffset()),
                () -> assertEquals(1,
                        retainedProbe.battleNetAnimationTimer()));

        mission.tick();
        assertEquals(196, fixtureCycle(world));
        assertAll(
                () -> assertEquals(8,
                        retainedProbe.battleNetCollisionCounter(),
                        "the eighth blocked visit advances the collision generation"),
                () -> assertEquals(moveStart,
                        retainedProbe.battleNetSequenceOffset()),
                () -> assertEquals(15,
                        retainedProbe.battleNetAnimationTimer(),
                        "generation eight owns a complete Move refusal band"));

        while (fixtureCycle(world) < 209) {
            mission.tick();
        }
        int paidTailAttackStart = world.idle.battleNetSequenceStart(
                paidProbeTail, BattleNetSequence.ATTACK_ANIMATION);
        assertAll(
                () -> assertEquals(paidTailAttackStart,
                        paidProbeTail.battleNetSequenceOffset(),
                        "a paid probe parks its unspent tail after the first stride"),
                () -> assertEquals(3,
                        paidProbeTail.battleNetAnimationTimer(),
                        "the parked tail returns through active-order Attack construction"));

        mission.tick();
        assertEquals(210, fixtureCycle(world));
        assertAll(
                () -> assertEquals(moveStart,
                        retainedProbe.battleNetSequenceOffset()),
                () -> assertEquals(1,
                        retainedProbe.battleNetAnimationTimer(),
                        "the Move refusal band drains through timer one"));

        mission.tick();
        assertEquals(211, fixtureCycle(world));
        assertAll(
                () -> assertEquals(0,
                        retainedProbe.battleNetCollisionCounter(),
                        "the paid-band wake clears the collision generation"),
                () -> assertEquals(attackStart,
                        retainedProbe.battleNetSequenceOffset()),
                () -> assertEquals(3,
                        retainedProbe.battleNetAnimationTimer(),
                        "the wake re-enters cold Attack construction"));

        while (fixtureCycle(world) < 213) {
            mission.tick();
        }
        assertEquals(53, damagedGrunt.hitPoints(),
                "the restored idle draw leaves native damage draw 4118 on the knight hit");

        mission.tick();
        assertEquals(214, fixtureCycle(world));
        assertEquals(0x2514f3c4, world.battleNetRandomSeed(),
                "both active-order idle callbacks must precede the next damage roll");

        mission.tick();
        assertEquals(215, fixtureCycle(world));
        assertAll(
                () -> assertEquals(54, focusGrunt.hitPoints(),
                        "the knight hit must consume native's six-point damage roll"),
                () -> assertEquals(0xeb535908, world.randomSeed(),
                        "the adjacent melee arrival must spend its synchronized draw"),
                () -> assertEquals(2540,
                        arrivingGrunt.battleNetSequenceOffset(),
                        "the occupied-quarry arrival opens past Attack OP0"),
                () -> assertEquals(1,
                        arrivingGrunt.battleNetAnimationTimer()),
                () -> assertEquals(0, arrivingGrunt.pathLength(),
                        "native parks the occupied quarry route at index twenty"));
    }

    @Test
    @DisplayName("a consumed nineteen-heading retarget keeps its first refusal band")
    void consumedNineteenHeadingRetargetKeepsItsFirstRefusalBand() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit grunt = unitById(world, 104);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1496 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 255) {
            mission.tick();
        }

        assertAll(
                () -> assertPosition(grunt, 36, 39,
                        "the northeast residual settles on retail's square"),
                () -> assertEquals(18, grunt.pathLength(),
                        "the consumed nineteen-heading route keeps eighteen bytes"),
                () -> assertEquals(19, grunt.battleNetPathInitialLength()),
                () -> assertEquals(1, grunt.battleNetPathStepsTaken()),
                () -> assertEquals(0, grunt.battleNetCollisionCounter()),
                () -> assertTrue(grunt.battleNetRetargetResidualRoutePark(),
                        "Attack construction retains the route-park boundary"));

        mission.tick();
        assertEquals(256, fixtureCycle(world));
        assertAll(
                () -> assertPosition(grunt, 36, 39,
                        "the blocked east heading must remain parked"),
                () -> assertEquals(18, grunt.pathLength(),
                        "retail keeps the cached route through the refusal"),
                () -> assertEquals(1, grunt.battleNetCollisionCounter(),
                        "the first blocked visit raises the collision nibble"),
                () -> assertEquals(14, grunt.battleNetOrderDelay(),
                        "Move timer fifteen leaves fourteen quiet visits"),
                () -> assertEquals(15, grunt.battleNetAnimationTimer()),
                () -> assertTrue(grunt.battleNetRefusalHold(),
                        "the retained route owns the complete refusal band"));

        mission.tick();
        assertEquals(257, fixtureCycle(world));
        assertAll(
                () -> assertPosition(grunt, 36, 39,
                        "retail still holds at the previous Java divergence"),
                () -> assertEquals(18, grunt.pathLength()),
                () -> assertEquals(13, grunt.battleNetOrderDelay()),
                () -> assertEquals(14, grunt.battleNetAnimationTimer()));
    }

    @Test
    @DisplayName("the first post-park refill routes around a moving formation mate")
    void firstPostParkRefillRoutesAroundAMovingFormationMate() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        Unit parkedRefill = unitById(world, 108);
        assertNotNull(parkedRefill,
                "XHuman 12 has no native-slot-1492 post-park refill grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 213) {
            mission.tick();
        }
        assertPosition(parkedRefill, 28, 37,
                "the paid east residual settles on its native square");

        mission.tick();
        assertEquals(214, fixtureCycle(world));
        assertPosition(parkedRefill, 29, 36,
                "the replacement route must take north-east around the moving ally");
        assertEquals(Unit.Order.ATTACK, parkedRefill.order(),
                "the route handoff remains owned by the chase");
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }

    private static Unit unitById(World world, int id) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.id() == id) {
                return unit;
            }
        }
        return null;
    }

    private static void assertPosition(Unit unit, int x, int y,
            String message) {
        assertEquals(x, unit.tileX(), message + " (x)");
        assertEquals(y, unit.tileY(), message + " (y)");
    }

}
