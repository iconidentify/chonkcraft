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

/** Authenticated trained-unit birth placement from retail Battle.net Edition. */
class BattleNetTrainedUnitDropoutRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("an XHuman 5 tanker takes the first legal native perimeter anchor")
    void anXHuman5TankerUsesTheNativeTrainingPerimeter() {
        Mission mission = loadMission("campaigns/human-exp/levelx05h");
        World world = mission.world();
        Unit shipyard = unitById(world, 66);
        assertNotNull(shipyard, "XHuman 5 has no p3 orc shipyard at 35,105");
        assertEquals(35, shipyard.tileX());
        assertEquals(105, shipyard.tileY());

        tickThrough(mission, 529);
        assertNull(unitById(world, 100),
                "the first trained p3 tanker must not exist before fixture 530");

        mission.tick();
        assertEquals(530, fixtureCycle(world));
        Unit tanker = unitById(world, 100);
        assertNotNull(tanker,
                "native slot 1500 / Java unit 100 is born on fixture 530");
        assertEquals("unit-orc-oil-tanker", tanker.type().ident());
        assertEquals(34, tanker.tileX(),
                "the west perimeter begins one tile left of the producer anchor");
        assertEquals(106, tanker.tileY(),
                "the first absolute-even naval anchor follows the rejected odd corner");
        assertEquals(Unit.Order.STILL, tanker.order());
    }

    @Test
    @DisplayName("the native walker preserves XHuman 3's already-correct tanker birth")
    void anXHuman3TankerKeepsItsNativeTrainingAnchor() {
        assertTrainedTankerBirth(
                "campaigns/human-exp/levelx03h", 536, 176, 80, 32);
    }

    @Test
    @DisplayName("the native walker preserves XHuman 8's already-correct tanker birth")
    void anXHuman8TankerKeepsItsNativeTrainingAnchor() {
        assertTrainedTankerBirth(
                "campaigns/human-exp/levelx08h", 532, 132, 34, 82);
    }

    private static void assertTrainedTankerBirth(String map, int birthCycle,
            int unitId, int tileX, int tileY) {
        Mission mission = loadMission(map);
        World world = mission.world();
        tickThrough(mission, birthCycle - 1);
        assertNull(unitById(world, unitId),
                "the trained tanker must not exist before fixture " + birthCycle);

        mission.tick();
        assertEquals(birthCycle, fixtureCycle(world));
        Unit tanker = unitById(world, unitId);
        assertNotNull(tanker, "the trained tanker must be born on its native fixture");
        assertEquals(tileX, tanker.tileX());
        assertEquals(tileY, tanker.tileY());
        assertEquals(Unit.Order.STILL, tanker.order());
    }

    private static Unit unitById(World world, int id) {
        return world.unitsSnapshot().stream()
                .filter(unit -> unit.id() == id)
                .findFirst().orElse(null);
    }

    private static Mission loadMission(String map) {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        return mission;
    }

    private static void tickThrough(Mission mission, int fixtureCycle) {
        while (fixtureCycle(mission.world()) < fixtureCycle) {
            mission.tick();
        }
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }
}
