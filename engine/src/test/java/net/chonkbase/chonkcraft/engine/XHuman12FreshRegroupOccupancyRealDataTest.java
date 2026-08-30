package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
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
        assertNotNull(firstRegroup,
                "XHuman 12 has no native-slot-1363 regroup grunt");
        assertNotNull(delayedRegroup,
                "XHuman 12 has no native-slot-1358 regroup grunt");

        mission.tick();
        assertEquals(199, fixtureCycle(world));
        assertEquals(Unit.Order.MOVE, firstRegroup.order());
        assertFalse(firstRegroup.isMoving(),
                "the promotion-pass body has not started pixel movement");
        assertTrue(world.movement.battleNetSoftClearMoveAlly(firstRegroup),
                "the freshly promoted regroup must retain its same-pass soft arm");

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
}
