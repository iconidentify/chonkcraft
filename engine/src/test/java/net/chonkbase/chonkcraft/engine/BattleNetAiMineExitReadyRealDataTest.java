package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated AI gold-mine exit dispatch from retail BNE. */
class BattleNetAiMineExitReadyRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("an XHuman 8 AI peon surfaces Still before walking gold home")
    void anXHuman8AiPeonSurfacesStillBeforeWalkingGoldHome() {
        Mission mission = mission("campaigns/human-exp/levelx08h");
        for (int cycle = 1; cycle < 173; cycle++) {
            mission.tick();
        }

        mission.tick();
        Unit peon = at(mission.world(), "unit-peon", 18, 9);
        assertMineExitReady(peon,
                "native slot 1571 must surface on the mine edge at fixture cycle 173");
        assertReadyHoldAndReturn(mission, peon, 174, 197, 198, 201, 19, 9);
    }

    @Test
    @DisplayName("an XOrc 12 AI peasant surfaces Still before walking gold home")
    void anXOrc12AiPeasantSurfacesStillBeforeWalkingGoldHome() {
        Mission mission = mission("campaigns/orc-exp/levelx12o");
        for (int cycle = 1; cycle < 171; cycle++) {
            mission.tick();
        }

        mission.tick();
        Unit peasant = at(mission.world(), "unit-peasant", 32, 74);
        assertMineExitReady(peasant,
                "native slot 1396 must surface on the mine edge at fixture cycle 171");
        assertReadyHoldAndReturn(mission, peasant, 172, 195, 196, 199, 31, 75);
    }

    private static void assertMineExitReady(Unit worker, String message) {
        assertNotNull(worker, message);
        assertEquals(Unit.Order.STILL, worker.order(),
                "the mine exit exposes retail's ready boundary before Return Goods");
        assertEquals(25, worker.battleNetOrderDelay(),
                "the surfaced worker holds the native ready window");
        assertEquals(100, worker.carried());
    }

    private static void assertReadyHoldAndReturn(Mission mission, Unit worker,
            int firstHoldCycle, int lastHoldCycle, int returnCycle,
            int firstStrideCycle, int strideX, int strideY) {
        int startX = worker.tileX();
        int startY = worker.tileY();
        for (int cycle = firstHoldCycle; cycle <= lastHoldCycle; cycle++) {
            mission.tick();
            assertEquals(Unit.Order.STILL, worker.order(),
                    "native holds the mine-exit ready boundary through fixture cycle "
                            + cycle);
            assertEquals(startX, worker.tileX());
            assertEquals(startY, worker.tileY());
        }
        mission.tick();
        assertEquals(Unit.Order.RETURN_GOODS, worker.order(),
                "native promotes the queued return on fixture cycle " + returnCycle);
        assertEquals(Unit.Order.RETURN_GOODS, worker.currentAction(),
                "the promoted return must be visible in the same fixture cycle");
        assertEquals(startX, worker.tileX());
        assertEquals(startY, worker.tileY());
        for (int cycle = returnCycle + 1; cycle < firstStrideCycle; cycle++) {
            mission.tick();
            assertEquals(startX, worker.tileX());
            assertEquals(startY, worker.tileY());
        }
        mission.tick();
        assertEquals(Unit.Order.HARVEST, worker.order(),
                "Java's unified resource order owns the active homeward leg");
        assertEquals(strideX, worker.tileX(),
                "native takes the first homeward stride on fixture cycle "
                        + firstStrideCycle);
        assertEquals(strideY, worker.tileY());
    }

    private static Mission mission(String map) {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        return mission;
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
