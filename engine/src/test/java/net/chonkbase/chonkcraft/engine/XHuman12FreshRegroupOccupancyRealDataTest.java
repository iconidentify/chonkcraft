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

/** Locks the transient occupancy used by XHuman 12's fixture-204 wood route. */
class XHuman12FreshRegroupOccupancyRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("a promoted regroup is soft only on its promotion pass")
    void aPromotedRegroupIsSoftOnlyOnItsPromotionPass() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        advanceToFixture(mission, 198);
        Unit firstRegroup = unitAt(world, "unit-grunt", 12, 88);
        Unit delayedRegroup = unitAt(world, "unit-grunt", 12, 87);
        Unit collisionWorker = unitById(world, 235);
        assertNotNull(firstRegroup,
                "XHuman 12 has no native-slot-1363 regroup grunt");
        assertNotNull(delayedRegroup,
                "XHuman 12 has no native-slot-1358 regroup grunt");
        assertNotNull(collisionWorker,
                "XHuman 12 has no native-slot-1365 wood worker");
        assertEquals(1, delayedRegroup.battleNetCollisionCounter(),
                "the tower chase retains native generation one through fixture 198");

        mission.tick();
        assertEquals(199, fixtureCycle(world));
        assertEquals(Unit.Order.MOVE, firstRegroup.order());
        assertFalse(firstRegroup.isMoving(),
                "the promotion-pass body has not started pixel movement");
        assertTrue(world.movement.battleNetSoftClearMoveAlly(firstRegroup),
                "the freshly promoted regroup must retain its same-pass soft arm");
        assertEquals(Unit.Order.STILL, delayedRegroup.order(),
                "the recurring regroup remains queued behind Still");
        assertTrue(delayedRegroup.hasBattleNetPendingMove(),
                "native writes Move into NextAction on the fixture-199 pass");
        assertEquals(0, delayedRegroup.battleNetCollisionCounter(),
                "the queued Move releases the retired chase collision generation");
        assertTrue(world.movement.battleNetSoftClearMoveAlly(delayedRegroup),
                "the queued departure is already soft to same-pass wood pathing");

        advanceToFixture(mission, 201);
        assertEquals(12, collisionWorker.tileX(),
                "the worker must consume native's southeast route through the queued departure");
        assertEquals(90, collisionWorker.tileY());

        advanceToFixture(mission, 203);
        Unit worker = unitAt(world, "unit-peon", 14, 84);
        assertNotNull(worker, "XHuman 12 has no native-slot-1386 wood worker");
        mission.tick();
        assertEquals(204, fixtureCycle(world));
        assertEquals(Unit.Order.MOVE, delayedRegroup.order());
        assertFalse(delayedRegroup.isMoving(),
                "the delayed regroup is still executing the old Still body");
        assertFalse(world.movement.battleNetSoftClearMoveAlly(delayedRegroup),
                "native action-state two keeps the next-pass regroup solid");

        assertEquals(13, worker.tileX(),
                "the worker consumes the native route's first west heading");
        assertEquals(84, worker.tileY());
        int[] remaining = {
            6, 6, 5, 5, 4, 3, 2, 2, 1, 1, 2, 1, 0, 0, 0
        };
        assertEquals(remaining.length, worker.pathLength(),
                "native stores sixteen headings and consumes the first one");
        for (int depth = 0; depth < remaining.length; depth++) {
            assertEquals(remaining[depth], worker.peekHeadingAtDepth(depth),
                    "native fixture-204 route heading at depth " + depth);
        }
    }

    @Test
    @DisplayName("a fresh terrain harvest retires the prior refusal generation")
    void aFreshTerrainHarvestRetiresThePriorRefusalGeneration() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit worker = unitById(world, 214);
        assertNotNull(worker,
                "XHuman 12 has no native-slot-1386 resource worker");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        advanceToFixture(mission, 167);
        assertEquals(2, worker.battleNetCollisionCounter(),
                "the failed gold walk still owns its second collision generation");
        assertEquals(1, worker.battleNetRefusals());

        mission.tick();
        assertEquals(168, fixtureCycle(world));
        assertEquals(Unit.Order.HARVEST, worker.order());
        assertEquals(null, worker.resourceUnit(),
                "UnitReady replaces the failed mine with terrain wood");
        assertEquals(18, worker.resourceTileX());
        assertEquals(80, worker.resourceTileY());
        assertEquals(0, worker.battleNetCollisionCounter(),
                "the fresh action-23 order clears native unit+0x1d");
        assertEquals(0, worker.battleNetRefusals());
        assertEquals(0, worker.pathLength());
        assertEquals(3, worker.battleNetAnimationTimer(),
                "the replacement begins action 23's three-call constructor");

        advanceToFixture(mission, 315);
        Unit regroupBlocker = unitById(world, 241);
        assertNotNull(regroupBlocker,
                "XHuman 12 has no native-slot-1359 regroup axethrower");
        assertEquals(10, worker.tileX());
        assertEquals(88, worker.tileY());
        assertEquals(9, worker.pathLength());
        assertEquals(Direction.fromDelta(1, 0), worker.peekHeading(),
                "the cached east head remains behind the southeast stride");
        assertEquals(0, worker.battleNetCollisionCounter(),
                "the fresh wood route has no stale gold refusal generation");
        assertTrue(regroupBlocker.isMoving());
        assertEquals(0, regroupBlocker.battleNetRefusals(),
                "the earlier current-Move body has no sticky refusal owner");

        mission.tick();
        assertEquals(316, fixtureCycle(world));
        assertEquals(10, worker.tileX(),
                "the occupied east head opens a refusal instead of redrawing north");
        assertEquals(88, worker.tileY());
        assertEquals(9, worker.pathLength(),
                "native retains the full east-led tail through the refusal band");
        assertEquals(Direction.fromDelta(1, 0), worker.peekHeading());
        assertEquals(1, worker.battleNetCollisionCounter());
        assertEquals(14, worker.battleNetOrderDelay());
        assertEquals(15, worker.battleNetAnimationTimer());

        advanceToFixture(mission, 330);
        assertEquals(10, worker.tileX());
        assertEquals(88, worker.tileY());
        assertEquals(9, worker.pathLength());
        assertEquals(1, worker.battleNetAnimationTimer(),
                "the complete Move band expires before the cached east step");

        mission.tick();
        assertEquals(331, fixtureCycle(world));
        assertEquals(11, worker.tileX());
        assertEquals(88, worker.tileY());
        assertEquals(8, worker.pathLength());
        assertEquals(Direction.fromDelta(1, 0), worker.lastStepHeading());

        advanceToFixture(mission, 395);
        assertEquals(14, worker.tileX());
        assertEquals(86, worker.tileY());
        assertEquals(5, worker.pathLength());
        assertEquals(Direction.fromDelta(1, 0), worker.peekHeading());
        assertEquals(8, worker.battleNetPathInitialLength());
        assertEquals(3, worker.battleNetPathStepsTaken());
        assertEquals(0, worker.battleNetCollisionCounter());
        assertEquals(15, regroupBlocker.tileX());
        assertEquals(86, regroupBlocker.tileY());
        assertTrue(regroupBlocker.isMoving());
        assertTrue(world.movement.battleNetCurrentMoveBody(regroupBlocker));
        assertEquals(3, regroupBlocker.battleNetCollisionCounter());
        assertEquals(1, regroupBlocker.battleNetRefusals(),
                "the later crossing owns a sticky refusal generation");

        mission.tick();
        assertEquals(396, fixtureCycle(world));
        assertEquals(14, worker.tileX(),
                "the residual-settle visit parks the occupied east tail");
        assertEquals(86, worker.tileY());
        assertEquals(0, worker.pathLength(),
                "native exposes route index twenty before the action-23 redraw");
        assertEquals(1, worker.battleNetCollisionCounter());
        assertEquals(0, worker.battleNetOrderDelay(),
                "the sticky blocker must not buy another fifteen-count band");
        assertFalse(worker.battleNetRefusalHold());

        mission.tick();
        assertEquals(397, fixtureCycle(world));
        assertEquals(15, worker.tileX(),
                "action 23 redraws around the sticky Move body immediately");
        assertEquals(87, worker.tileY());
        assertEquals(Direction.fromDelta(1, 1), worker.lastStepHeading());
        assertEquals(4, worker.pathLength(),
                "the southeast commit retains the native NE,N,N,N tail");
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }

    private static void advanceToFixture(Mission mission, int fixture) {
        while (fixtureCycle(mission.world()) < fixture) {
            mission.tick();
        }
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

    private static Unit unitById(World world, int id) {
        return world.unitsSnapshot().stream()
                .filter(unit -> unit.id() == id)
                .findFirst().orElse(null);
    }
}
