package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Locks a BNE self-scout's final west stride along the north map boundary. */
class XOrc08NorthEdgeFlyerScoutRealDataTest {

    private static final int INITIALIZATION_TICKS = 2;

    @Test
    void establishedWestScoutHeadingStaysParallelToTheNorthEdge() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc-exp/levelx08o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XOrc 8 is not in the pack");
        World world = mission.world();
        Unit gryphon = world.unitsSnapshot().stream()
                .filter(unit -> unit.id() == 19
                        && unit.type() != null
                        && "unit-gryphon-rider".equals(unit.type().ident()))
                .findFirst().orElse(null);
        assertNotNull(gryphon, "XOrc 8 must contain native gryphon 1581 / Java 19");

        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 86) {
            mission.tick();
        }
        assertEquals(2, gryphon.tileX());
        assertEquals(0, gryphon.tileY());
        assertEquals(Unit.Order.PATROL, gryphon.order());

        mission.tick();
        assertEquals(0, gryphon.tileX(),
                "native takes the fourth cached west scout stride");
        assertEquals(0, gryphon.tileY(),
                "the north-edge route must not turn south-west");
        assertEquals(Unit.Order.PATROL, gryphon.order());
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - INITIALIZATION_TICKS;
    }
}
