package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.animation.BattleNetSequence;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.pathfinder.BattleNetPathFinder;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated paired melee handoffs at XHuman 12 fixture 271. */
class XHuman12Cycle271GruntHandoffsRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("cycle-271 residual handoffs retain their native visit ownership")
    void cycle271ResidualHandoffsRetainTheirNativeVisitOwnership() {
        Mission mission = mission("campaigns/human-exp/levelx12h");
        World world = mission.world();
        Unit saturatedRetarget = unitById(world, 108);
        Unit constructorWake = unitById(world, 97);
        assertNotNull(saturatedRetarget,
                "XHuman 12 has no Java twin for native grunt 1492");
        assertNotNull(constructorWake,
                "XHuman 12 has no Java twin for native grunt 1503");

        tickThrough(mission, 270);
        assertEquals(28, saturatedRetarget.tileX());
        assertEquals(37, saturatedRetarget.tileY());
        assertEquals(40, constructorWake.tileX());
        assertEquals(38, constructorWake.tileY());
        assertEquals(world.idle.battleNetSequenceStart(constructorWake,
                        BattleNetSequence.ATTACK_ANIMATION),
                constructorWake.battleNetSequenceOffset());
        assertEquals(1, constructorWake.battleNetAnimationTimer());

        mission.tick();
        assertEquals(271, fixtureCycle(world));

        // Native slot 1492 settles its south-west pixels, changes quarry, and
        // writes a complete route through the occupied east formation square.
        // That first cooperative refusal owns Move 15..1; no heading commits on
        // the target-selection visit.
        assertEquals(28, saturatedRetarget.tileX());
        assertEquals(37, saturatedRetarget.tileY());
        assertNotNull(saturatedRetarget.target());
        assertEquals(125, saturatedRetarget.target().id());
        assertEquals(30, saturatedRetarget.pathGoalX());
        assertEquals(43, saturatedRetarget.pathGoalY());
        assertEquals(BattleNetPathFinder.MAX_PATH,
                saturatedRetarget.pathLength());
        int east = Direction.fromDelta(1, 0);
        int southEast = Direction.fromDelta(1, 1);
        int northEast = Direction.fromDelta(1, -1);
        int south = Direction.fromDelta(0, 1);
        int southWest = Direction.fromDelta(-1, 1);
        int[] route = {
                east, east, east,
                southEast, southEast, southEast,
                east, east, east,
                northEast, northEast, northEast,
                southEast, south, southEast, southEast,
                south, south, southWest, southWest
        };
        for (int depth = 0; depth < route.length; depth++) {
            assertEquals(route[depth],
                    saturatedRetarget.peekHeadingAtDepth(depth),
                    "native replacement route differs at depth " + depth);
        }
        assertEquals(world.idle.battleNetSequenceStart(saturatedRetarget,
                        BattleNetSequence.MOVE_ANIMATION),
                saturatedRetarget.battleNetSequenceOffset());
        assertEquals(15, saturatedRetarget.battleNetAnimationTimer());
        assertEquals(14, saturatedRetarget.battleNetOrderDelay());
        assertEquals(1, saturatedRetarget.battleNetCollisionCounter());

        // Native slot 1503's Attack constructor does not retire the cached
        // guard-tower ray. Timer one refreshes the nearest point inside the
        // same building footprint and consumes its approved south-east
        // heading in this visit.
        assertEquals(41, constructorWake.tileX());
        assertEquals(39, constructorWake.tileY());
        assertEquals(southEast, constructorWake.lastStepHeading());
        assertNotNull(constructorWake.target());
        assertEquals(115, constructorWake.target().id());
        assertEquals(40, constructorWake.pathGoalX());
        assertEquals(41, constructorWake.pathGoalY());
        assertEquals(11, constructorWake.pathLength());
        assertEquals(east, constructorWake.peekHeading());
        assertEquals(world.idle.battleNetSequenceStart(constructorWake,
                        BattleNetSequence.MOVE_ANIMATION) + 3,
                constructorWake.battleNetSequenceOffset());
        assertEquals(1, constructorWake.battleNetAnimationTimer());
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
        while (fixtureCycle(mission.world()) < fixtureCycle) {
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
