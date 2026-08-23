package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated large-ship anchor movement on an oil-resource route. */
class Human07TankerResourceAnchorRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("a tanker resource route tests its BNE anchor, not its sprite footprint")
    void aTankerResourceRouteTestsItsAnchorInsteadOfItsSpriteFootprint() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level07h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 7 is not in the pack");
        World world = mission.world();
        Unit tanker = unitById(world, 67);
        assertNotNull(tanker, "Human 7 has no native-slot-1533 oil tanker");
        assertEquals("unit-orc-oil-tanker", tanker.type().ident());

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 72) {
            mission.tick();
        }

        assertEquals(80, tanker.tileX(),
                "native takes the cached doubled south-west resource step");
        assertEquals(52, tanker.tileY(),
                "the legal anchor may leave the rendered 2x2 hull over coast");
        assertEquals(Unit.Order.HARVEST, tanker.order());
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
