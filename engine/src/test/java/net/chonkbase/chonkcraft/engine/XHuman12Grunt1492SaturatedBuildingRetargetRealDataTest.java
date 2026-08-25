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

/** Authenticated saturated chase-route handoff onto XHuman 12's guard tower. */
class XHuman12Grunt1492SaturatedBuildingRetargetRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("XHuman 12 grunt 1492 parks a saturated mobile-to-building retarget")
    void grunt1492ParksSaturatedMobileToBuildingRetarget() {
        Mission mission = mission("campaigns/human-exp/levelx12h");
        Unit grunt = byId(mission.world(), 108);
        assertNotNull(grunt, "XHuman 12 has no Java twin for native grunt 1492");

        tickThrough(mission, 229);
        assertEquals(Unit.Order.ATTACK, grunt.order());
        assertEquals(29, grunt.tileX());
        assertEquals(36, grunt.tileY());
        assertEquals(19, grunt.pathLength(),
                "one heading is spent from the saturated twenty-byte route");
        assertEquals(1, grunt.battleNetPathStepsTaken());
        assertEquals(1, grunt.battleNetCollisionCounter());

        tickThrough(mission, 230);
        assertEquals(Unit.Order.ATTACK, grunt.order());
        assertEquals(29, grunt.tileX(),
                "the retarget landing callback parks the replacement route");
        assertEquals(36, grunt.tileY());
        assertNotNull(grunt.target());
        assertEquals("unit-human-guard-tower", grunt.target().type().ident());
        assertEquals(2482, grunt.battleNetSequenceOffset());
        assertEquals(1, grunt.battleNetAnimationTimer());
        assertTrue(grunt.battleNetRetargetResidualParkRefill());

        tickThrough(mission, 231);
        assertEquals(29, grunt.tileX());
        assertEquals(36, grunt.tileY());
        assertEquals(2539, grunt.battleNetSequenceOffset());
        assertEquals(3, grunt.battleNetAnimationTimer(),
                "the parked saturated retarget pays Attack construction");

        tickThrough(mission, 234);
        assertEquals(29, grunt.tileX());
        assertEquals(36, grunt.tileY());
        assertEquals(2482, grunt.battleNetSequenceOffset());
        assertEquals(1, grunt.battleNetAnimationTimer());
        assertTrue(grunt.battleNetRetargetResidualParkRefill());

        tickThrough(mission, 235);
        assertEquals(29, grunt.tileX());
        assertEquals(36, grunt.tileY());
        assertEquals(2, grunt.battleNetCollisionCounter());
        assertTrue(grunt.battleNetRetargetResidualParkRefill());
        assertEquals(1, grunt.battleNetRetargetResidualParkSteps());

        tickThrough(mission, 239);
        assertEquals(29, grunt.tileX());
        assertEquals(36, grunt.tileY());
        assertEquals(0, grunt.pathLength());
        assertEquals(6, grunt.battleNetCollisionCounter());
        assertEquals(2482, grunt.battleNetSequenceOffset());
        assertEquals(1, grunt.battleNetAnimationTimer());

        tickThrough(mission, 240);
        assertEquals(29, grunt.tileX());
        assertEquals(36, grunt.tileY());
        assertEquals(20, grunt.pathLength());
        assertEquals(Direction.fromDelta(-1, 1), grunt.peekHeading());
        assertEquals(7, grunt.battleNetCollisionCounter());
        assertEquals(2482, grunt.battleNetSequenceOffset());
        assertEquals(15, grunt.battleNetAnimationTimer());

        tickThrough(mission, 255);
        assertEquals(28, grunt.tileX(),
                "the paid replacement eventually releases south-west");
        assertEquals(37, grunt.tileY());
        assertEquals(Direction.fromDelta(-1, 1), grunt.lastStepHeading());
    }

    private static Mission mission(String map) {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        return mission;
    }

    private static void tickThrough(Mission mission, int fixtureCycle) {
        while (mission.world().cycle() - BNE_INITIALIZATION_TICKS
                < fixtureCycle) {
            mission.tick();
        }
    }

    private static Unit byId(World world, int id) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.id() == id) {
                return unit;
            }
        }
        return null;
    }
}
