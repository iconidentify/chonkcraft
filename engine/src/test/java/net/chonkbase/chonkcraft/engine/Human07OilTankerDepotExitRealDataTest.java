package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated hidden-platform depot selection and exit on Human 7. */
class Human07OilTankerDepotExitRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("Human 7's first loaded tanker chooses the eastern refinery")
    void firstLoadedTankerChoosesNativeDepotAndExitFace() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level07h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        World world = mission.world();
        Unit tanker = unitById(world, 96);
        Unit easternRefinery = unitById(world, 105);
        Unit southernRefinery = unitById(world, 113);
        assertNotNull(tanker, "Human 7 has no native-slot-1504 tanker");
        assertNotNull(easternRefinery, "Human 7 has no refinery at 64,81");
        assertNotNull(southernRefinery, "Human 7 has no refinery at 58,83");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 156) {
            mission.tick();
        }

        assertTrue(tanker.removed(),
                "fixture 156 must still contain the tanker inside platform 57,73");
        assertEquals(57, tanker.orderTargetX(),
                "hidden action 26 retains the final platform order point");
        assertEquals(74, tanker.orderTargetY());
        int easternTravel = world.unitReachableTravel(tanker, easternRefinery, 1);
        int southernTravel = world.unitReachableTravel(tanker, southernRefinery, 1);
        assertSame(easternRefinery,
                world.harvest.bestDepotByTravel(
                        tanker, UnitType.Resource.OIL, 1000),
                "native selects refinery 64,81 before dropout; Java travel lengths "
                        + "were east=" + easternTravel + ", south=" + southernTravel);

        mission.tick();
        assertEquals(157, fixtureCycle(world));
        assertEquals(60, tanker.tileX(),
                "the eastern depot selects the platform's east exit face");
        assertEquals(74, tanker.tileY());
        assertEquals(Unit.Order.STILL, tanker.order(),
                "the AI tanker surfaces behind native's ready boundary");
        assertSame(easternRefinery, tanker.returnDepotGoal());

        while (fixtureCycle(world) < 185) {
            mission.tick();
        }
        assertSame(easternRefinery, tanker.returnDepotGoal(),
                "the queued return must retain the depot selected before dropout");
        assertEquals(62, tanker.tileX());
        assertEquals(76, tanker.tileY(),
                "the first doubled return stride must continue southeast toward home");
    }

    @Test
    @DisplayName("Human 7's eastern tanker keeps its platform at an uncongested depot")
    void easternTankerKeepsItsPlatformAtAnUncongestedDepot() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level07h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        World world = mission.world();
        Unit tanker = unitById(world, 109);
        Unit depot = unitById(world, 92);
        Unit easternPlatform = unitById(world, 101);
        assertNotNull(tanker, "Human 7 has no native-slot-1491 tanker");
        assertNotNull(depot, "Human 7 has no refinery at 72,72");
        assertNotNull(easternPlatform, "Human 7 has no platform at 79,77");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 404) {
            mission.tick();
        }

        assertTrue(tanker.removed(),
                "fixture 404 must still contain the tanker inside refinery 72,72");
        assertSame(depot, tanker.worksite());
        assertEquals(null, tanker.resourceUnit(),
                "the fresh Return Goods order owns no remembered platform pointer");
        assertTrue(tanker.resourceMoveCycles() <= 500,
                "the first trip is not the exceptionally long reassignment case: "
                        + tanker.resourceMoveCycles());
        assertTrue(world.approximateUnitRefs(depot) <= 15,
                "the first depot visit is not the congested reassignment case: "
                        + world.approximateUnitRefs(depot));

        mission.tick();
        assertEquals(405, fixtureCycle(world));
        assertEquals(76, tanker.tileX(),
                "native drops east toward the platform selected from this depot");
        assertEquals(74, tanker.tileY());
        assertEquals(Unit.Order.STILL, tanker.order());
        assertSame(easternPlatform, tanker.resourceUnit(),
                "the queued action-23 continuation owns the eastern platform");
    }

    private static Unit unitById(World world, int id) {
        return world.unitsSnapshot().stream()
                .filter(unit -> unit.id() == id)
                .findFirst().orElse(null);
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }
}
