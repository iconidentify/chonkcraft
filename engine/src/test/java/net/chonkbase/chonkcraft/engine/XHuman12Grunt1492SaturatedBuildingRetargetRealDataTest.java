package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        tickThrough(mission, 270);
        assertEquals(28, grunt.tileX());
        assertEquals(37, grunt.tileY());
        assertEquals(2, grunt.offsetX());
        assertEquals(-2, grunt.offsetY(),
                "fixture 270 still owes the final southwest residual");
        assertEquals(BattleNetPathFinder.MAX_PATH - 1,
                grunt.pathLength());
        assertEquals(BattleNetPathFinder.MAX_PATH,
                grunt.battleNetPathInitialLength());
        assertEquals(1, grunt.battleNetPathStepsTaken());
        assertEquals(7, grunt.battleNetCollisionCounter());
        assertNotNull(grunt.target());
        assertEquals("unit-human-guard-tower",
                grunt.target().type().ident());
        assertEquals(25, grunt.target().tileX());
        assertEquals(42, grunt.target().tileY());

        tickThrough(mission, 271);
        assertEquals(28, grunt.tileX(),
                "the occupied continuation head must not move the grunt");
        assertEquals(37, grunt.tileY());
        assertEquals(0, grunt.offsetX());
        assertEquals(0, grunt.offsetY());
        assertEquals(BattleNetPathFinder.MAX_PATH, grunt.pathLength(),
                "the refused continuation retains all twenty route bytes");
        assertEquals(Direction.fromDelta(1, 0), grunt.peekHeading(),
                "the paid clockwise face opens east, not cold-route south");
        assertEquals(1, grunt.battleNetCollisionCounter(),
                "the replacement quarry starts a fresh refusal generation");
        assertEquals(15, grunt.battleNetAnimationTimer(),
                "the occupied east head enters native Move 15");
        assertNotNull(grunt.target());
        assertEquals("unit-knight", grunt.target().type().ident());
        assertEquals(30, grunt.target().tileX());
        assertEquals(43, grunt.target().tileY());
    }

    @Test
    @DisplayName("XHuman 12 grunt 1492 promotes queued Attack after the saturated route band")
    void grunt1492PromotesQueuedAttackAfterSaturatedRouteBand() {
        Mission mission = mission("campaigns/human-exp/levelx12h");
        Unit grunt = byId(mission.world(), 108);
        assertNotNull(grunt, "XHuman 12 has no Java twin for native grunt 1492");

        tickThrough(mission, 271);
        assertEquals(28, grunt.tileX());
        assertEquals(37, grunt.tileY());
        assertEquals(BattleNetPathFinder.MAX_PATH,
                grunt.battleNetPathInitialLength());
        assertEquals(BattleNetPathFinder.MAX_PATH, grunt.pathLength());
        assertEquals(0, grunt.battleNetPathStepsTaken());
        assertEquals(1, grunt.battleNetCollisionCounter());
        assertEquals(0, grunt.battleNetRefusals());
        assertTrue(grunt.battleNetSaturatedRetargetRouteBand());
        assertTrue(grunt.battleNetChaseReplanResidualHold());
        assertNotNull(grunt.target());
        assertFalse(grunt.target().type().building());

        tickThrough(mission, 285);
        assertEquals(28, grunt.tileX());
        assertEquals(37, grunt.tileY());
        assertEquals(BattleNetPathFinder.MAX_PATH, grunt.pathLength());
        assertEquals(2482, grunt.battleNetSequenceOffset());
        assertEquals(1, grunt.battleNetAnimationTimer(),
                "the refused replacement pays its complete Move band");
        assertEquals(1, grunt.battleNetCollisionCounter());

        tickThrough(mission, 286);
        assertEquals(28, grunt.tileX(),
                "the retained Attack runs before the replacement head");
        assertEquals(37, grunt.tileY());
        assertEquals(BattleNetPathFinder.MAX_PATH, grunt.pathLength());
        assertEquals(2539, grunt.battleNetSequenceOffset());
        assertEquals(3, grunt.battleNetAnimationTimer());
        assertEquals(0, grunt.battleNetCollisionCounter(),
                "Attack promotion retires the building-route collision");

        tickThrough(mission, 288);
        assertEquals(28, grunt.tileX());
        assertEquals(37, grunt.tileY());
        assertEquals(2539, grunt.battleNetSequenceOffset());
        assertEquals(1, grunt.battleNetAnimationTimer());

        tickThrough(mission, 289);
        assertEquals(29, grunt.tileX(),
                "the first east byte releases after the queued Attack");
        assertEquals(37, grunt.tileY());
        assertEquals(BattleNetPathFinder.MAX_PATH - 1,
                grunt.pathLength());
        assertEquals(1, grunt.battleNetPathStepsTaken());
        assertEquals(Direction.fromDelta(1, 0), grunt.lastStepHeading());
        assertTrue(grunt.isMoving());
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
