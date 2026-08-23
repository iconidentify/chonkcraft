package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated foundation and approach coordinates from retail Orc 5. */
class Orc05HallFoundationRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("Orc 5 keeps the town-hall foundation separate from its approach edge")
    void orcFiveKeepsHallFoundationSeparateFromApproachEdge() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc/level05o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Orc 5 is not in the pack");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        // The opening ready-worker callback runs on fixture cycle one.
        mission.tick();
        Unit builder = hallBuilder(mission.world());
        assertNotNull(builder, "Orc 5 has no peasant carrying its opening hall order");
        assertEquals(106, builder.buildTileX(),
                "native +0x80 keeps the hall's top-left foundation");
        assertEquals(48, builder.buildTileY());
        assertEquals(109, builder.buildGoalX(),
                "native +0x84 aims at the footprint edge nearest the peasant");
        assertEquals(48, builder.buildGoalY());

        for (int fixture = 2; fixture < 84; fixture++) {
            mission.tick();
        }
        assertNull(at(mission.world(), "unit-town-hall", 106, 48),
                "the foundation must not appear before retail fixture 84");

        mission.tick();
        Unit hall = at(mission.world(), "unit-town-hall", 106, 48);
        assertNotNull(hall,
                "retail slot 1511 founds the hall at 106,48 on fixture 84");
        assertEquals(Unit.Order.UNDER_CONSTRUCTION, hall.order());
        assertEquals(40, hall.hitPoints());
        assertEquals(106, builder.tileX(),
                "the contained builder moves to the foundation, not its old approach tile");
        assertEquals(48, builder.tileY());
        assertNull(at(mission.world(), "unit-town-hall", 109, 48),
                "the approach edge must never become a second foundation anchor");
    }

    private static Unit hallBuilder(World world) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.pendingBuild() != null
                    && "unit-town-hall".equals(unit.pendingBuild().ident())) {
                return unit;
            }
        }
        return null;
    }

    private static Unit at(World world, String ident, int x, int y) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.isAlive() && unit.isOnMap() && unit.tileX() == x
                    && unit.tileY() == y && unit.type() != null
                    && ident.equals(unit.type().ident())) {
                return unit;
            }
        }
        return null;
    }
}
