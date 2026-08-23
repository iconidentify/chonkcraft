package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Locks the native Human 5 gold-failure transition to the AI wood path. */
class Human05ResourceFailureWoodReadyRealDataTest {

    private static final int INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("Human 5's blocked gold peon waits for BNE's north-shifted wood route")
    void blockedGoldPeonUsesTheNativeWoodReadyRetryCadence() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No BNE asset pack/install is configured");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level05h";
        PudMap source = data.campaignMap(map);
        Assumptions.assumeTrue(source != null, "Human 5 is unavailable");
        Mission mission = data.loadMission(map, GameData.personIn(source), 1);
        Assumptions.assumeTrue(mission != null, "Human 5 will not load");
        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        Unit worker = unitAt(mission.world(), 5, "unit-peon", 107, 43);
        assertNotNull(worker, "Human 5 has no player-5 peon on 107,43");

        while (fixtureCycle(mission.world()) < 107) {
            mission.tick();
        }
        assertWorker(worker, 105, 48);
        assertEquals(104, worker.resourceTileX(),
                "BNE's ready fallback chooses the north-west tree order point");
        assertEquals(46, worker.resourceTileY(),
                "the AI wood ring is centered one row north of the unit anchor");
        assertTrue(worker.battleNetWoodReadyPathRequired(),
                "an adjacent-looking fallback still has to pass through path readiness");
        assertEquals(0xd2bfa1d7, mission.world().randomSeed(),
                "the failed gold route must not consume the AI wood random draw");

        while (fixtureCycle(mission.world()) < 121) {
            mission.tick();
        }
        assertWorker(worker, 105, 48);

        mission.tick();
        assertEquals(122, fixtureCycle(mission.world()));
        assertWorker(worker, 106, 48);
    }

    private static Unit unitAt(World world, int player, String ident,
            int x, int y) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.player() == player && unit.tileX() == x && unit.tileY() == y
                    && unit.type() != null && ident.equals(unit.type().ident())) {
                return unit;
            }
        }
        return null;
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - INITIALIZATION_TICKS;
    }

    private static void assertWorker(Unit worker, int x, int y) {
        assertEquals(x, worker.tileX());
        assertEquals(y, worker.tileY());
        assertEquals(Unit.Order.HARVEST, worker.order());
    }
}
