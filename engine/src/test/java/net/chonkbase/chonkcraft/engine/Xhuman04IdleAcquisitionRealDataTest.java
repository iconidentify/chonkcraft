package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
