package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Locks BNE's two-step gold free-prefix conversion to nearby lumber. */
class Orc07ShortGoldPrefixRealDataTest {

    private static final int INITIALIZATION_TICKS = 2;

    @Test
    void twoStepGoldPrefixReaimsToWoodWhenItsResidualSettles() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc/level07o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Orc 7 is not in the pack");
        World world = mission.world();
        Unit peasant = world.unitsSnapshot().stream()
                .filter(unit -> unit.id() == 18
                        && unit.type() != null
                        && "unit-peasant".equals(unit.type().ident()))
                .findFirst().orElse(null);
        assertNotNull(peasant, "Orc 7 must contain native peasant 1576 / Java 18");

        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 39) {
            mission.tick();
        }
        assertEquals(0xc46b9b3d, world.randomSeed());
        assertEquals(UnitType.Resource.GOLD, peasant.carrying(),
                "the two-step free prefix is still a gold walk before settle");

        mission.tick();
        assertEquals(0xf94bdf32, world.randomSeed(),
                "fixture 40 includes BNE's first wood-claim SyncRand");
        assertEquals(UnitType.Resource.WOOD, peasant.carrying());
        assertNull(peasant.resourceUnit(), "the mine goal is replaced by terrain wood");
        assertEquals(42, peasant.resourceTileX());
        assertEquals(2, peasant.resourceTileY());
        assertEquals(42, peasant.tileX());
        assertEquals(3, peasant.tileY());
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - INITIALIZATION_TICKS;
    }
}
