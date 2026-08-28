package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated final-entry overlap for crowded BNE land depots. */
class BattleNetDepotEntryOverlapRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("XOrc 12's staged returner enters behind a draining depot mate")
    void xorc12StagedReturnerEntersBehindADrainingDepotMate() {
        Mission mission = mission("campaigns/orc-exp/levelx12o");
        World world = mission.world();
        Unit mover = byId(world, 206);
        Unit blocker = byId(world, 204);
        assertNotNull(mover, "native slot 1394 must remain paired with Java 206");
        assertNotNull(blocker, "native slot 1396 must remain paired with Java 204");

        advanceToFixture(mission, 263);
        assertFinalDepotOverlapSetup(world, mover, blocker, 30, 75, 29, 76);

        mission.tick();
        assertEquals(264, fixtureCycle(world));
        assertEquals(29, mover.tileX(),
                "native slot 1394 spends the staged south-west entry on cycle 264");
        assertEquals(76, mover.tileY());
        assertEquals(blocker.tileX(), mover.tileX(),
                "both native workers temporarily own the depot-entry tile");
        assertEquals(blocker.tileY(), mover.tileY());
    }

    @Test
    @DisplayName("XHuman 7's staged returner enters behind a draining depot mate")
    void xhuman7StagedReturnerEntersBehindADrainingDepotMate() {
        Mission mission = mission("campaigns/human-exp/levelx07h");
        World world = mission.world();
        Unit mover = byId(world, 142);
        Unit blocker = byId(world, 154);
        assertNotNull(mover, "native slot 1458 must remain paired with Java 142");
        assertNotNull(blocker, "native slot 1446 must remain paired with Java 154");

        advanceToFixture(mission, 329);
        assertFinalDepotOverlapSetup(world, mover, blocker, 111, 106, 112, 107);

        mission.tick();
        assertEquals(330, fixtureCycle(world));
        assertEquals(112, mover.tileX(),
                "native slot 1458 spends the staged south-east entry on cycle 330");
        assertEquals(107, mover.tileY());
        assertEquals(blocker.tileX(), mover.tileX(),
                "the independent depot queue must permit the same brief overlap");
        assertEquals(blocker.tileY(), mover.tileY());
    }

    @Test
    @DisplayName("XHuman 8's pre-stage return still waits behind depot traffic")
    void xhuman8PreStageReturnStillWaitsBehindDepotTraffic() {
        Mission mission = mission("campaigns/human-exp/levelx08h");
        World world = mission.world();
        Unit waiter = byId(world, 25);
        Unit entrant = byId(world, 29);
        assertNotNull(waiter, "native slot 1575 must remain paired with Java 25");
        assertNotNull(entrant, "native slot 1571 must remain paired with Java 29");

        advanceToFixture(mission, 250);
        assertEquals(20, waiter.tileX());
        assertEquals(9, waiter.tileY());
        assertTrue(waiter.returningToDepot());
        assertEquals(100, waiter.carried());
        assertEquals(false, waiter.battleNetResourceApproachStaged(),
                "ordinary action 24 has not earned the final-entry overlap");
        assertEquals(21, entrant.tileX());
        assertEquals(9, entrant.tileY());
        assertTrue(entrant.battleNetResourceApproachStaged());

        advanceToFixture(mission, 269);
        assertEquals(20, waiter.tileX(),
                "the pre-stage return must not overlap the occupied entry before cycle 270");
        assertEquals(9, waiter.tileY());
    }

    @Test
    @DisplayName("a stationary depot entrant remains solid to the staged follower")
    void aStationaryDepotEntrantRemainsSolidToTheStagedFollower() {
        Mission mission = mission("campaigns/orc-exp/levelx12o");
        World world = mission.world();
        Unit mover = byId(world, 206);
        Unit blocker = byId(world, 204);
        assertNotNull(mover);
        assertNotNull(blocker);

        advanceToFixture(mission, 263);
        blocker.setOffset(0, 0);
        blocker.setStepDrained(true);
        assertEquals(false, blocker.isMoving(),
                "the held-out control removes the native draining-body witness");

        mission.tick();
        assertEquals(30, mover.tileX(),
                "a stationary worker remains a real land-body collision");
        assertEquals(75, mover.tileY());
    }

    private static void assertFinalDepotOverlapSetup(World world, Unit mover,
            Unit blocker, int moverX, int moverY, int blockerX, int blockerY) {
        assertEquals(moverX, mover.tileX());
        assertEquals(moverY, mover.tileY());
        assertEquals(blockerX, blocker.tileX());
        assertEquals(blockerY, blocker.tileY());
        assertTrue(mover.returningToDepot());
        assertTrue(blocker.returningToDepot());
        assertEquals(100, mover.carried());
        assertEquals(100, blocker.carried());
        assertTrue(mover.battleNetResourceApproachStaged());
        assertTrue(blocker.battleNetResourceApproachStaged());
        assertTrue(blocker.isMoving(),
                "the native overlap is owned by a final-entry body still draining pixels");
        assertEquals(0, mover.pathLength(),
                "the follower arms its one-byte final-entry route on the next call");
        assertEquals(0, blocker.pathLength(),
                "the leader must have spent its final-entry route");
        assertTrue(blocker.routeSpent());
        assertEquals(0, blocker.battleNetCollisionCounter());
        assertEquals(0, blocker.battleNetRefusals());
        assertTrue(world.movement.battleNetSoftClearMoveAlly(blocker),
                "the draining leader must be a native-transparent Move body");
        assertEquals(mover.returnDepotGoal(), blocker.returnDepotGoal(),
                "both workers must be entering the same depot");
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
