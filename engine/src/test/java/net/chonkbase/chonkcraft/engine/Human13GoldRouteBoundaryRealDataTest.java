package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Locks full resource-path segment boundaries to native BNE's continued approach. */
class Human13GoldRouteBoundaryRealDataTest {

    private static final int INITIALIZATION_TICKS = 2;

    @Test
    void fiveStepResourcePrefixesContinueToTheirOriginalGoals() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level13h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 13 is not in the pack");
        World world = mission.world();
        Unit northPeon = world.unitsSnapshot().stream()
                .filter(unit -> unit.id() == 61
                        && unit.type() != null
                        && "unit-peon".equals(unit.type().ident()))
                .findFirst().orElse(null);
        Unit southPeon = world.unitsSnapshot().stream()
                .filter(unit -> unit.id() == 133
                        && unit.type() != null
                        && "unit-peon".equals(unit.type().ident()))
                .findFirst().orElse(null);
        assertNotNull(northPeon, "Human 13 must contain native peon 1539 / Java 61");
        assertNotNull(southPeon, "Human 13 must contain native peon 1467 / Java 133");

        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 84) {
            mission.tick();
        }
        assertEquals(28, northPeon.tileX());
        assertEquals(8, northPeon.tileY());
        assertEquals(50, southPeon.tileX());
        assertEquals(48, southPeon.tileY());

        mission.tick();
        assertEquals(0xe2319ac4, world.randomSeed(),
                "fixture 85 keeps BNE's gold-route planning draw ledger");
        assertEquals(29, northPeon.tileX(),
                "native peon 1539 takes the fresh north-east gold step");
        assertEquals(7, northPeon.tileY());
        assertEquals(51, southPeon.tileX(),
                "native peon 1467 takes the fresh north-east gold step");
        assertEquals(47, southPeon.tileY());
        assertNotNull(northPeon.resourceUnit(), "north peon remains assigned to its mine");
        assertNull(southPeon.resourceUnit(),
                "south peon remains a terrain harvester");
        assertEquals(50, southPeon.resourceTileX(),
                "full wood prefix retains its original tree instead of a nearer one");
        assertEquals(46, southPeon.resourceTileY());
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - INITIALIZATION_TICKS;
    }
}
