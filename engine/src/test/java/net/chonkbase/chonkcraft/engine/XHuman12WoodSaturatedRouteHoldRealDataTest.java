package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.animation.BattleNetSequence;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated saturated terrain-wood route hold from XHuman 12. */
class XHuman12WoodSaturatedRouteHoldRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("a collision-four wood route waits fifteen then consumes its cached step")
    void collisionFourWoodRouteWaitsThenConsumesItsCachedStep() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit peon = unitById(world, 215);
        assertNotNull(peon,
                "XHuman 12 has no Java unit 215 / native peon slot 1385");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        tickThrough(mission, world, 200);
        assertPosition(peon, 11, 86);
        assertEquals(1, peon.battleNetCollisionCounter(),
                "the first occupied southeast probe starts the native collision generation");

        mission.tick();
        assertEquals(201, fixtureCycle(world));
        assertEquals(2, peon.battleNetCollisionCounter());

        mission.tick();
        assertEquals(202, fixtureCycle(world));
        assertEquals(3, peon.battleNetCollisionCounter(),
                "three blocked route writes pay the collision ladder before redraw");

        mission.tick();
        assertEquals(203, fixtureCycle(world));
        assertPosition(peon, 10, 87);
        assertEquals(Direction.fromDelta(-1, 1), peon.lastStepHeading());
        assertEquals(3, peon.battleNetCollisionCounter(),
                "the successful southwest redraw retains the paid generation");

        tickThrough(mission, world, 219);
        assertPosition(peon, 10, 88);
        assertEquals(Direction.fromDelta(0, 1), peon.lastStepHeading());
        assertEquals(2, peon.pathLength(),
                "southeast,northeast remain in the cached native route");
        assertEquals(3, peon.battleNetCollisionCounter());

        tickThrough(mission, world, 235);
        assertPosition(peon, 10, 88);
        assertEquals(2, peon.pathLength(),
                "collision four keeps the occupied southeast byte and its tail");
        assertEquals(Direction.fromDelta(1, 1), peon.peekHeading());
        assertEquals(4, peon.battleNetCollisionCounter());
        assertEquals(14, peon.battleNetOrderDelay(),
                "native Move timer fifteen has fourteen quiet visits remaining");
        assertEquals(15, peon.battleNetAnimationTimer());

        for (int fixture = 236; fixture <= 249; fixture++) {
            mission.tick();
            assertEquals(fixture, fixtureCycle(world));
            assertEquals(249 - fixture, peon.battleNetOrderDelay(),
                    "remaining quiet callbacks at fixture " + fixture);
            assertTrue(peon.battleNetRefusalHold(),
                    "the cached terrain route owns the refusal band at fixture "
                            + fixture);
            assertEquals(250 - fixture, peon.battleNetAnimationTimer(),
                    "native Move timer at fixture " + fixture);
        }
        assertPosition(peon, 10, 88);
        assertEquals(2, peon.pathLength(),
                "the complete refusal band retains the cached route");
        assertEquals(4, peon.battleNetCollisionCounter());
        assertEquals(0, peon.battleNetOrderDelay());

        mission.tick();
        assertEquals(250, fixtureCycle(world));
        assertPosition(peon, 11, 89);
        assertEquals(Direction.fromDelta(1, 1), peon.lastStepHeading());
        assertEquals(1, peon.pathLength(),
                "the northeast tail remains after consuming cached southeast");
        assertEquals(4, peon.battleNetCollisionCounter(),
                "the accepted saturated route keeps native collision ownership");
        assertTrue(peon.isMoving(),
                "the accepted southeast step begins its pixel residual");
    }

    @Test
    @DisplayName("a paid long wood wall retains its occupied cardinal tail")
    void paidLongWoodWallRetainsItsOccupiedCardinalTail() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit peon = unitById(world, 214);
        assertNotNull(peon,
                "XHuman 12 has no Java unit 214 / native peon slot 1386");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        tickThrough(mission, world, 315);
        assertPosition(peon, 10, 88);
        assertEquals(9, peon.pathLength());
        assertEquals(Direction.fromDelta(1, 0), peon.peekHeading());
        assertEquals(2, peon.battleNetCollisionCounter());
        assertEquals(1, peon.battleNetRefusals());

        mission.tick();
        assertEquals(316, fixtureCycle(world));
        assertPosition(peon, 10, 88);
        assertEquals(9, peon.pathLength(),
                "native retains route index seven and its east-led tail");
        assertEquals(Direction.fromDelta(1, 0), peon.peekHeading());
        assertEquals(3, peon.battleNetCollisionCounter(),
                "the occupied cached byte advances the paid generation");
        assertEquals(14, peon.battleNetOrderDelay());
        assertEquals(15, peon.battleNetAnimationTimer());
        assertEquals(world.idle.battleNetSequenceStart(peon,
                        BattleNetSequence.MOVE_ANIMATION),
                peon.battleNetSequenceOffset());

        mission.tick();
        assertEquals(317, fixtureCycle(world));
        assertPosition(peon, 10, 88);
        assertEquals(9, peon.pathLength());
        assertEquals(13, peon.battleNetOrderDelay());
        assertEquals(14, peon.battleNetAnimationTimer());
    }

    @Test
    @DisplayName("a complete forest prefix retains its occupied lateral byte")
    void completeForestPrefixRetainsItsOccupiedLateralByte() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit peon = unitById(world, 236);
        assertNotNull(peon,
                "XHuman 12 has no Java unit 236 / native peon slot 1364");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        tickThrough(mission, world, 301);
        assertPosition(peon, 11, 89);
        assertEquals(Direction.fromDelta(-1, -1), peon.lastStepHeading());
        assertEquals(7, peon.battleNetPathInitialLength());
        assertEquals(1, peon.battleNetPathStepsTaken());
        assertEquals(6, peon.pathLength());
        assertEquals(Direction.fromDelta(0, -1), peon.peekHeading());
        assertEquals(Direction.fromDelta(1, -1),
                peon.peekHeadingAtDepth(1));
        assertEquals(Direction.fromDelta(1, 0),
                peon.peekHeadingAtDepth(2));
        assertEquals(Direction.fromDelta(1, -1),
                peon.peekHeadingAtDepth(3));
        assertEquals(Direction.fromDelta(1, 1),
                peon.peekHeadingAtDepth(4));
        assertEquals(Direction.fromDelta(0, 1),
                peon.peekHeadingAtDepth(5));

        tickThrough(mission, world, 317);
        assertPosition(peon, 11, 89);
        assertEquals(6, peon.pathLength(),
                "the occupied north byte and its full tail stay cached");
        assertEquals(1, peon.battleNetCollisionCounter());
        assertEquals(14, peon.battleNetOrderDelay());
        assertEquals(15, peon.battleNetAnimationTimer());

        mission.tick();
        assertEquals(318, fixtureCycle(world));
        assertPosition(peon, 11, 89);
        assertEquals(6, peon.pathLength());
        assertEquals(13, peon.battleNetOrderDelay());
        assertEquals(14, peon.battleNetAnimationTimer());
    }

    private static void tickThrough(Mission mission, World world, int fixture) {
        while (fixtureCycle(world) < fixture) {
            mission.tick();
        }
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

    private static void assertPosition(Unit unit, int x, int y) {
        assertEquals(x, unit.tileX(), "x");
        assertEquals(y, unit.tileY(), "y");
    }
}
