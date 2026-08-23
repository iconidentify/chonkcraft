package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated occupied-gold-skirt pathfinding from retail Orc 11. */
class Orc11MarkedGoldSkirtRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("orc 11's gold route may end under a quiescent peasant")
    void orc11PeasantTakesTheNativeWestRoutePastAnOccupiedGoldSkirt() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc/level11o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Orc 11 is not in the pack");
        World world = mission.world();
        Unit peasant = unitById(world, 139);
        assertNotNull(peasant, "Orc 11 has no Java unit 139");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 137) {
            mission.tick();
        }

        assertEquals(13, peasant.tileX(),
                "fixture 137 consumes the route's opening south-west step");
        assertEquals(124, peasant.tileY(),
                "the opening south-west step lands on native's 13,124");
        assertEquals(6, peasant.peekHeading(),
                "native's next heading is west, not a second south-west");

        while (fixtureCycle(world) < 153) {
            mission.tick();
        }

        assertEquals(12, peasant.tileX(),
                "fixture 153 consumes native's second route heading");
        assertEquals(124, peasant.tileY(),
                "the occupied marked skirt must not bend the peasant south");
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
