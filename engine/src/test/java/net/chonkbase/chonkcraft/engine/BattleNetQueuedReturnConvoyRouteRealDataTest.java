package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated land-return convoy routes from BNE 2.02b. */
class BattleNetQueuedReturnConvoyRouteRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("XOrc 6 routes through a queued returner behind a collided convoy head")
    void xorc6RoutesThroughQueuedReturnerBehindCollidedConvoyHead() {
        Mission mission = mission("campaigns/orc-exp/levelx06o");
        World world = mission.world();
        Unit returner = unitById(world, 85);
        Unit collidedHead = unitById(world, 84);
        Unit queuedBody = unitById(world, 83);

        initialize(mission);
        while (fixtureCycle(world) < 251) {
            mission.tick();
        }
        assertEquals(8, returner.tileX());
        assertEquals(86, returner.tileY());
        assertFalse(returner.isMoving());
        assertEquals(8, collidedHead.tileX());
        assertEquals(85, collidedHead.tileY());
        assertTrue(collidedHead.battleNetCollisionCounter() > 0);
        assertFalse(collidedHead.isMoving());
        assertQueuedReturner(queuedBody, returner.returnDepotGoal(), 8, 84);

        mission.tick();
        assertEquals(252, fixtureCycle(world));
        assertEquals(7, returner.tileX());
        assertEquals(85, returner.tileY());
        assertEquals(Direction.fromDelta(-1, -1),
                returner.lastStepHeading());
        assertEquals(1, returner.pathLength(),
                "native stores only the northeast tail after committing northwest");
        assertEquals(Direction.fromDelta(1, -1), returner.peekHeading());

        while (fixtureCycle(world) < 273) {
            mission.tick();
        }
        assertEquals(7, returner.tileX());
        assertEquals(85, returner.tileY());
        assertEquals(1, returner.pathLength());

        mission.tick();
        assertEquals(274, fixtureCycle(world));
        assertEquals(8, returner.tileX(),
                "the cached northeast tail crosses the queued body's tile");
        assertEquals(84, returner.tileY());
        assertEquals(Direction.fromDelta(1, -1),
                returner.lastStepHeading());
        assertEquals(0, returner.pathLength());
    }

    @Test
    @DisplayName("XHuman 12 shares the two-diagonal return-convoy route")
    void xhuman12SharesTwoDiagonalReturnConvoyRoute() {
        Mission mission = mission("campaigns/human-exp/levelx12h");
        World world = mission.world();
        Unit returner = unitById(world, 46);
        Unit collidedHead = unitById(world, 50);
        Unit queuedBody = unitById(world, 48);

        initialize(mission);
        while (fixtureCycle(world) < 224) {
            mission.tick();
        }
        assertEquals(6, returner.tileX());
        assertEquals(30, returner.tileY());
        assertEquals(0, returner.pathLength());
        assertEquals(6, collidedHead.tileX());
        assertEquals(29, collidedHead.tileY());
        assertTrue(collidedHead.battleNetCollisionCounter() > 0);
        assertFalse(collidedHead.isMoving());
        assertQueuedReturner(queuedBody, returner.returnDepotGoal(), 6, 28);

        mission.tick();
        assertEquals(225, fixtureCycle(world));
        assertEquals(7, returner.tileX());
        assertEquals(29, returner.tileY());
        assertEquals(Direction.fromDelta(1, -1),
                returner.lastStepHeading());
        assertEquals(1, returner.pathLength(),
                "native stores only the northwest tail after committing northeast");
        assertEquals(Direction.fromDelta(-1, -1), returner.peekHeading());
    }

    private static void assertQueuedReturner(Unit unit, Unit depot,
            int x, int y) {
        assertEquals(x, unit.tileX());
        assertEquals(y, unit.tileY());
        assertEquals(Unit.Order.STILL, unit.order());
        assertTrue(unit.battleNetOrderDelay() > 0);
        assertTrue(unit.queuedReplacementPending());
        assertTrue(unit.hasQueuedOrders());
        assertEquals(Unit.QueuedOrderKind.RETURN_GOODS,
                unit.queuedOrders().getFirst().kind());
        assertEquals(depot, unit.returnDepotGoal());
        assertEquals(depot, unit.queuedOrders().getFirst().target());
        assertEquals(100, unit.carried());
        assertEquals(0, unit.battleNetCollisionCounter());
    }

    private static Mission mission(String map) {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission(
                map, GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        return mission;
    }

    private static void initialize(Mission mission) {
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
    }

    private static Unit unitById(World world, int id) {
        Unit unit = world.unitsSnapshot().stream()
                .filter(candidate -> candidate.id() == id)
                .findFirst().orElse(null);
        assertNotNull(unit, "missing Java unit " + id);
        return unit;
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }
}
