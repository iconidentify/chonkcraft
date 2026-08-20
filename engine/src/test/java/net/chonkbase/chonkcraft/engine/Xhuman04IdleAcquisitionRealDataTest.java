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

/** Authenticated BNE idle-acquisition timing from expansion Human mission 4. */
class Xhuman04IdleAcquisitionRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xhuman 4's ballista waits for its cycle-11 Still marker")
    void xhuman4BallistaWaitsForItsOwnStillMarker() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx04h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 4 is not in the pack");
        World world = mission.world();

        Unit ballista = unitAt(world, "unit-ballista", 68, 62);
        assertNotNull(ballista, "XHuman 4 has no ballista on 68,62");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        Unit.Order orderAt6 = null;
        Unit.Order orderAt11 = null;
        while (world.cycle() < 13) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 6) {
                orderAt6 = ballista.order();
            }
            if (fixture == 11) {
                orderAt11 = ballista.order();
            }
        }

        assertEquals(Unit.Order.STILL, orderAt6,
                "a neighbour's arrival must not invent an off-marker scan");
        assertEquals(Unit.Order.ATTACK, orderAt11,
                "the next native Still marker acquires the target");
    }

    @Test
    @DisplayName("xhuman 4's blocked grunt reaches native's eighth refusal")
    void xhuman4GruntCountsItsInitialCooperativeRefusal() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx04h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 4 is not in the pack");
        World world = mission.world();

        Unit grunt = unitAt(world, "unit-grunt", 77, 61);
        assertNotNull(grunt, "XHuman 4 has no grunt on 77,61");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 54) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 9) {
                assertEquals(1, grunt.battleNetRefusals(),
                        "the preserved cooperative route is refusal one");
                assertEquals(1, grunt.battleNetCollisionCounter());
                assertEquals(5, grunt.pathLength());
            } else if (fixture == 24) {
                assertEquals(2, grunt.battleNetRefusals(),
                        "the first hard park follows the cooperative refusal");
            } else if (fixture == 30) {
                assertEquals(8, grunt.battleNetRefusals(),
                        "retail enters its fifteen-count band on fixture 30");
            } else if (fixture == 53) {
                assertEquals(77, grunt.tileX());
                assertEquals(61, grunt.tileY(),
                        "the grunt must remain parked through fixture 53");
            }
        }

        assertEquals(77, grunt.tileX());
        assertEquals(60, grunt.tileY(),
                "retail recovers north on fixture 54, not fixture 55");
        assertTrue(grunt.isMoving(),
                "the recovered grunt must be walking rather than frozen");
    }

    @Test
    @DisplayName("xhuman 4 refills ranged and gold residual routes on cycle 56")
    void xhuman4RefillsRangedAndGoldResidualRoutesOnCycle56() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx04h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 4 is not in the pack");
        World world = mission.world();

        Unit thrower = unitAt(world, "unit-axethrower", 78, 62);
        Unit peon = unitAt(world, "unit-peon", 116, 14);
        assertNotNull(thrower, "XHuman 4 has no axethrower on 78,62");
        assertNotNull(peon, "XHuman 4 has no peon on 116,14");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 55) {
            mission.tick();
        }
        assertEquals(77, thrower.tileX());
        assertEquals(62, thrower.tileY(),
                "the ranged residual remains on its old row through cycle 55");
        assertEquals(Unit.Order.ATTACK, thrower.order());
        assertEquals(118, peon.tileX());
        assertEquals(14, peon.tileY(),
                "the gold residual remains on its old row through cycle 55");
        assertEquals(Unit.Order.HARVEST, peon.order());

        mission.tick();

        assertEquals(77, thrower.tileX());
        assertEquals(61, thrower.tileY(),
                "the ranged empty-route refill must commit native north on cycle 56");
        assertEquals(Unit.Order.ATTACK, thrower.order(),
                "the axethrower must stay live in its chase");
        assertEquals(119, peon.tileX());
        assertEquals(13, peon.tileY(),
                "the refused gold residual must replan native north-east on cycle 56");
        assertEquals(Unit.Order.HARVEST, peon.order(),
                "the peon must stay live in its gold trip");
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
