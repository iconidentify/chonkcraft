package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A gold-route residual sees the native collision view for the whole visit.
 *
 * <p>Authenticated Orc 5 idle fixture: peasant 1529 still owns 32,101 when
 * peasant 1532 settles its second residual at fixture 38. Native parks route
 * index 20, then replans SW,SW at fixture 39 after 1529 leaves. Java visits
 * 1529 first and has already committed it west, but its full-tile pixel debt
 * preserves the collision view that 1532 must observe.
 */
class BattleNetGoldVacatedAllyRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("an orc 5 gold residual parks behind an ally that just vacated")
    void anOrc5GoldResidualParksBehindAnAllyThatJustVacated() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/orc/level05o",
                GameData.personIn(data.campaignMap("campaigns/orc/level05o")), 1);
        Assumptions.assumeTrue(mission != null, "Orc 5 is not in the pack");
        World world = mission.world();
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit worker = atTile(world, 35, 100);
        assertNotNull(worker, "Orc 5 has no focus peasant on 35,100");

        while (fixtureCycle(world) < 55) {
            mission.tick();
        }

        assertEquals(Unit.Order.HARVEST, worker.order(),
                "the peasant must remain on its native gold order");
        assertEquals(31, worker.tileX(),
                "native's parked residual replans SW,SW to x=31");
        assertEquals(102, worker.tileY(),
                "native's parked residual replans SW,SW to y=102");
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - 2;
    }

    private static Unit atTile(World world, int x, int y) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.isAlive() && unit.isOnMap() && unit.type() != null
                    && !unit.type().building()
                    && unit.tileX() == x && unit.tileY() == y) {
                return unit;
            }
        }
        return null;
    }
}
