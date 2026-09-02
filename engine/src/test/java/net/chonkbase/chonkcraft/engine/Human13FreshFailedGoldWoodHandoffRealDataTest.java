package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Locks the fresh-empty gold constructor's same-visit terrain handoff. */
class Human13FreshFailedGoldWoodHandoffRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("Human 13's fresh empty gold route starts adjacent wood immediately")
    void freshEmptyGoldConstructorFallsThroughToAdjacentWoodStart() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human/level13h", 0, 1);
        Assumptions.assumeTrue(mission != null, "Human 13 is not in the pack");
        World world = mission.world();

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        advanceToFixture(mission, 493);
        Unit worker = unitById(world, 211);
        assertNotNull(worker,
                "Human 13 has no native-slot-1393 / Java-211 peon");
        assertEquals(14, worker.tileX());
        assertEquals(3, worker.tileY());
        assertEquals(Unit.Order.HARVEST, worker.order());
        assertNotNull(worker.resourceUnit(),
                "the fresh constructor is still aimed at the distant gold mine");
        assertEquals(30, worker.resourceTileX());
        assertEquals(5, worker.resourceTileY());
        assertEquals(0, worker.battleNetPathInitialLength());
        assertEquals(0, worker.battleNetPathStepsTaken());
        assertFalse(worker.stepDrained(),
                "this is a fresh empty constructor, not a completed gold walk");
        mission.tick();
        assertEquals(494, fixtureCycle(world));
        assertEquals(Unit.Order.HARVEST, worker.order());
        assertNull(worker.resourceUnit(),
                "UnitReady replaces the route-less mine with terrain wood");
        assertEquals(13, worker.resourceTileX(),
                "the ordinary worker-centred ring selects the adjacent western tree");
        assertEquals(4, worker.resourceTileY());
        assertTrue(worker.gatherClockStarted(),
                "fresh route failure falls through to StartGathering in this visit");
        assertEquals(0x23d3b823, world.randomSeed(),
                "the adjacent terrain claim owns native's second fixture-494 draw");
        assertEquals(3, worker.battleNetWoodSyncRemaining(),
                "the claim stages work opcode 2660 three visits later");
    }

    private static void advanceToFixture(Mission mission, int fixture) {
        while (fixtureCycle(mission.world()) < fixture) {
            mission.tick();
        }
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
