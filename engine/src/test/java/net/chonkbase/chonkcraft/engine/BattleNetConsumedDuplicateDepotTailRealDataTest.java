package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.pathfinder.PathFinder;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The consumed duplicate-cardinal depot tail shared by two native workers. */
class BattleNetConsumedDuplicateDepotTailRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("XHuman 5 redraws its consumed S,S depot tail through SW")
    void xhuman5RedrawsItsConsumedSouthTailThroughSouthwest() {
        Mission mission = mission("campaigns/human-exp/levelx05h");
        Unit peon = byId(mission.world(), 67);
        assertNotNull(peon,
                "native slot 1533 must remain paired with Java peon 67");

        advanceToFixture(mission, 286);
        assertConsumedDuplicateTail(peon, 0, 1,
                "native arrives with the spent [SW,S,SE,S,S] route");

        mission.tick();
        assertEquals(287, fixtureCycle(mission.world()));
        assertEquals(0, peon.pathLength(),
                "the refused consumed tail parks instead of being restored");
        assertEquals(50, peon.tileX());
        assertEquals(106, peon.tileY());

        mission.tick();
        assertEquals(288, fixtureCycle(mission.world()));
        assertEquals(49, peon.tileX());
        assertEquals(107, peon.tileY(),
                "retail discards S,S and first-steps the SW,SE bypass");
        assertEquals(Direction.fromDelta(-1, 1), peon.lastStepHeading());
    }

    @Test
    @DisplayName("XHuman 11 redraws its consumed N,N depot tail through NE")
    void xhuman11RedrawsItsConsumedNorthTailThroughNortheast() {
        Mission mission = mission("campaigns/human-exp/levelx11h");
        Unit peon = byId(mission.world(), 104);
        assertNotNull(peon,
                "native slot 1496 must remain paired with Java peon 104");

        advanceToFixture(mission, 309);
        assertConsumedDuplicateTail(peon, 0, -1,
                "native arrives with the spent [N,N,N,NW,N,N] route");

        mission.tick();
        assertEquals(310, fixtureCycle(mission.world()));
        assertEquals(0, peon.pathLength(),
                "the refused consumed tail parks instead of being restored");
        assertEquals(17, peon.tileX());
        assertEquals(86, peon.tileY());

        mission.tick();
        assertEquals(311, fixtureCycle(mission.world()));
        assertEquals(18, peon.tileX());
        assertEquals(85, peon.tileY(),
                "retail discards N,N and first-steps the NE,NW bypass");
        assertEquals(Direction.fromDelta(1, -1), peon.lastStepHeading());
    }

    @Test
    @DisplayName("XHuman 11 keeps a free N,N tail across lateral depot re-aim")
    void xhuman11KeepsFreeNorthTailAcrossLateralDepotReaim() {
        Mission mission = mission("campaigns/human-exp/levelx11h");
        Unit peon = byId(mission.world(), 105);
        assertNotNull(peon,
                "native slot 1495 must remain paired with Java peon 105");

        advanceToFixture(mission, 319);
        assertEquals(100, peon.carried());
        assertTrue(peon.returningToDepot());
        assertEquals(19, peon.tileX());
        assertEquals(87, peon.tileY());
        assertEquals(2, peon.pathLength(),
                "the consumed six-byte route retains its final N,N tail");
        assertEquals(4, peon.battleNetPathStepsTaken());
        assertEquals(Direction.fromDelta(1, -1), peon.lastStepHeading());
        assertEquals(Direction.fromDelta(0, -1), peon.peekHeading());
        assertEquals(Direction.fromDelta(0, -1),
                peon.peekHeadingAtDepth(1));
        assertEquals(18, peon.orderTargetX());
        assertEquals(84, peon.orderTargetY());
        assertEquals(0, peon.battleNetCollisionCounter());

        mission.tick();
        assertEquals(320, fixtureCycle(mission.world()));
        assertEquals(19, peon.tileX());
        assertEquals(86, peon.tileY(),
                "native consumes the free north head on the settle visit");
        assertEquals(608, peon.pixelX());
        assertEquals(2784, peon.pixelY());
        assertEquals(1, peon.pathLength());
        assertEquals(5, peon.battleNetPathStepsTaken());
        assertEquals(Direction.fromDelta(0, -1), peon.lastStepHeading());
        assertEquals(Direction.fromDelta(0, -1), peon.peekHeading());
        assertEquals(20, peon.orderTargetX(),
                "the depot edge still refreshes across the hall midpoint");
        assertEquals(84, peon.orderTargetY());
        assertEquals(0, peon.battleNetCollisionCounter());
    }

    @Test
    @DisplayName("XOrc 12 redraws a consumed depot tail behind a settling queue head")
    void xorc12RedrawsConsumedTailBehindSettlingDepotQueueHead() {
        Mission mission = mission("campaigns/orc-exp/levelx12o");
        World world = mission.world();
        Unit follower = byId(world, 258);
        Unit leader = byId(world, 263);
        assertNotNull(follower,
                "native slot 1342 must remain paired with Java peasant 258");
        assertNotNull(leader,
                "native slot 1337 must remain paired with Java peasant 263");

        advanceToFixture(mission, 372);
        assertEquals(59, follower.tileX());
        assertEquals(104, follower.tileY());
        assertEquals(1, follower.pathLength(),
                "the consumed six-byte route retains its final north byte");
        assertTrue(follower.battleNetPathStepsTaken() > 0);
        assertEquals(Direction.fromDelta(0, -1), follower.peekHeading());
        assertTrue(follower.isMoving(),
                "the follower still owes the final pixels of its northwest stride");
        assertEquals(59, leader.tileX());
        assertEquals(103, leader.tileY());
        assertEquals(0, leader.pathLength());
        assertTrue(leader.routeSpent());
        assertTrue(leader.isMoving(),
                "the lower native slot will settle onto the depot ring first");
        assertEquals(follower.returnDepotGoal(), leader.returnDepotGoal());
        assertEquals(leader, world.blockerOnLayer(follower, 59, 103));
        assertFalse(leader.battleNetResourceApproachStaged());
        assertEquals(0, leader.battleNetCollisionCounter());
        assertEquals(0, leader.battleNetRefusals());
        assertEquals(1, leader.distanceTo(follower.returnDepotGoal()));
        int[] refreshedEdge = world.battleNetDepotEntryPoint(
                follower, follower.returnDepotGoal());
        assertEquals(59, refreshedEdge[0]);
        assertEquals(102, refreshedEdge[1]);
        assertEquals(60, follower.orderTargetX());
        assertEquals(102, follower.orderTargetY());

        mission.tick();
        assertEquals(373, fixtureCycle(world));
        assertEquals(0, follower.pathLength(),
                "retail parks the blocked consumed tail at cursor twenty");
        assertEquals(1, follower.battleNetCollisionCounter());
        assertEquals(59, follower.orderTargetX(),
                "the native settle visit refreshes the nearest depot edge");
        assertEquals(102, follower.orderTargetY());

        mission.tick();
        assertEquals(374, fixtureCycle(world));
        assertEquals(58, follower.tileX());
        assertEquals(103, follower.tileY(),
                "the redraw bypasses the action-25 queue head through northwest");
        assertEquals(Direction.fromDelta(-1, -1),
                follower.lastStepHeading());
    }

    @Test
    @DisplayName("Orc 12 redraws its saturated one-byte depot tail through SE")
    void orc12RedrawsItsSaturatedOneByteDepotTailThroughSoutheast() {
        Mission mission = mission("campaigns/orc/level12o");
        Unit peon = byId(mission.world(), 93);
        assertNotNull(peon,
                "native slot 1507 must remain paired with Java peon 93");

        advanceToFixture(mission, 326);
        assertEquals(100, peon.carried());
        assertTrue(peon.returningToDepot());
        assertEquals(58, peon.tileX());
        assertEquals(49, peon.tileY());
        assertEquals(1, peon.pathLength(),
                "the five-byte return route retains its final south byte");
        assertEquals(5, peon.battleNetPathInitialLength());
        assertEquals(4, peon.battleNetPathStepsTaken());
        assertEquals(Direction.fromDelta(0, 1), peon.peekHeading());
        assertEquals(1, peon.battleNetCollisionCounter());
        assertEquals(8, peon.battleNetRefusals());

        mission.tick();
        assertEquals(327, fixtureCycle(mission.world()));
        assertEquals(58, peon.tileX());
        assertEquals(49, peon.tileY());
        assertEquals(0, peon.pathLength(),
                "refusal nine parks the saturated south tail instead of restoring it");
        assertEquals(9, peon.battleNetRefusals());
        assertEquals(14, peon.waitCycles());

        advanceToFixture(mission, 341);
        assertEquals(58, peon.tileX());
        assertEquals(49, peon.tileY());
        assertEquals(0, peon.pathLength(),
                "the parked tail stays empty through the complete refusal band");
        assertEquals(0, peon.waitCycles());

        mission.tick();
        assertEquals(342, fixtureCycle(mission.world()));
        assertEquals(59, peon.tileX());
        assertEquals(50, peon.tileY(),
                "retail redraws and commits southeast when the paid band wakes");
        assertEquals(Direction.fromDelta(1, 1), peon.lastStepHeading());
        assertEquals(1, peon.pathLength());
        assertEquals(Direction.fromDelta(1, 0), peon.peekHeading(),
                "the redraw retains its east suffix");
    }

    @Test
    @DisplayName("fresh, one-byte, nonduplicate, and diagonal tails stay outside the rule")
    void onlyAConsumedDuplicateCardinalTwoByteTailQualifies() {
        UnitType type = new UnitType("unit-peon");
        Unit unit = new Unit(1, type, 0, 0, 0);
        int north = Direction.fromDelta(0, -1);
        int northeast = Direction.fromDelta(1, -1);

        unit.setPath(path(north, north));
        assertFalse(BattleNetMovementSystem.consumedDuplicateCardinalTail(unit, 2),
                "a fresh direct N,N route has no consumed prefix");

        unit.setPath(path(north));
        assertFalse(BattleNetMovementSystem.consumedDuplicateCardinalTail(unit, 1),
                "the established one-byte depot refusal ray is retained");

        unit.setPath(path(north, northeast, north));
        unit.popHeading();
        assertFalse(BattleNetMovementSystem.consumedDuplicateCardinalTail(unit, 2),
                "a consumed nonduplicate cardinal/diagonal tail is retained");

        unit.setPath(path(northeast, northeast, north));
        unit.popHeading();
        assertFalse(BattleNetMovementSystem.consumedDuplicateCardinalTail(unit, 2),
                "a consumed duplicate diagonal tail is retained");

        unit.setPath(path(north, north, northeast));
        unit.popHeading();
        assertTrue(BattleNetMovementSystem.consumedDuplicateCardinalTail(unit, 2),
                "a consumed duplicate cardinal tail is the precise positive shape");
    }

    private static void assertConsumedDuplicateTail(Unit peon,
            int deltaX, int deltaY, String message) {
        int cardinal = Direction.fromDelta(deltaX, deltaY);
        assertEquals(100, peon.carried(), message + " (laden)");
        assertEquals(2, peon.pathLength(), message + " (remaining)");
        assertTrue(peon.battleNetPathInitialLength() > peon.pathLength(),
                message + " (consumed prefix)");
        assertTrue(peon.battleNetPathStepsTaken() > 0,
                message + " (steps taken)");
        assertEquals(cardinal, peon.peekHeading(), message + " (head)");
        assertEquals(cardinal, peon.peekHeadingAtDepth(1),
                message + " (duplicate tail)");
        assertTrue(BattleNetMovementSystem.consumedDuplicateCardinalTail(
                peon, peon.pathLength()), message + " (qualified)");
    }

    private static PathFinder.Path path(int... headings) {
        return new PathFinder.Path(PathFinder.Result.FOUND, headings);
    }

    private static Mission mission(String map) {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        return mission;
    }

    private static void advanceToFixture(Mission mission, int fixture) {
        while (fixtureCycle(mission.world()) < fixture) {
            mission.tick();
        }
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
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
