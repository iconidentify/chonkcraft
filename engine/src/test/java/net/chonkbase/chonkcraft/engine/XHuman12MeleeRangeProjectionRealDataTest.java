package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated melee target-range projection from XHuman 12. */
class XHuman12MeleeRangeProjectionRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("a melee chase aligned within range keeps a pure cardinal route")
    void meleeChaseAlignedWithinRangeKeepsPureCardinalRoute() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit grunt = unitById(world, 92);
        assertNotNull(grunt,
                "XHuman 12 has no Java unit 92 / native grunt slot 1508");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        tickThrough(mission, world, 287);
        assertEquals(37, grunt.tileX());
        assertEquals(44, grunt.tileY());
        assertEquals(Direction.fromDelta(-1, 0), grunt.lastStepHeading());
        assertEquals(6, grunt.battleNetPathInitialLength());
        assertEquals(1, grunt.battleNetPathStepsTaken());
        assertEquals(5, grunt.pathLength());
        for (int depth = 0; depth < grunt.pathLength(); depth++) {
            assertEquals(Direction.fromDelta(-1, 0),
                    grunt.peekHeadingAtDepth(depth),
                    "pure west tail at depth " + depth);
        }
        assertNotNull(grunt.target());
        assertEquals(123, grunt.target().id());
        assertEquals(32, grunt.pathGoalX(),
                "the weak order goal remains the target point");
        assertEquals(43, grunt.pathGoalY());

        tickThrough(mission, world, 306);
        assertEquals(36, grunt.tileX());
        assertEquals(44, grunt.tileY());
        assertEquals(4, grunt.pathLength());
        assertTrue(grunt.isMoving());

        tickThrough(mission, world, 322);
        assertEquals(35, grunt.tileX());
        assertEquals(44, grunt.tileY(),
                "the third cached byte remains west rather than north-west");
        assertEquals(Direction.fromDelta(-1, 0), grunt.lastStepHeading());
        assertEquals(3, grunt.pathLength());
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
}
