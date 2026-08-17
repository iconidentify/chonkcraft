package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Retail preserves an in-mine gather wait when Return Goods replaces it. */
class BattleNetReturnGoodsHarvestWaitRealDataTest {

    @Test
    void returnGoodsDuringTheMineWaitDoesNotInterruptTheWait() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc/level01o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Orc 1 is not in the pack");
        World world = mission.world();
        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);
        mission.tick();
        mission.tick();
        Unit worker = atTile(world, 25, 18);
        Unit mine = atTile(world, 26, 13);
        assertNotNull(worker, "Orc 1 has no peon on 25,18");
        assertNotNull(mine, "Orc 1 has no gold mine on 26,13");
        for (int cycle = 1; cycle <= 400; cycle++) {
            if (cycle == 5) {
                assertTrue(commands.apply(GameCommand.harvest(
                        0, worker.id(), mine.tileX(), mine.tileY())));
            }
            if (cycle == 220) {
                assertTrue(commands.apply(GameCommand.returnGoods(0, worker.id())));
            }
            mission.tick();
            switch (cycle) {
                case 209 -> {
                    assertTrue(worker.isOnMap());
                    assertEquals(Unit.Order.STILL, worker.order());
                    assertEquals(25, worker.tileX());
                    assertEquals(15, worker.tileY());
                    assertEquals(25, worker.battleNetOrderDelay());
                    assertEquals(100, worker.carried());
                }
                case 220 -> {
                    assertEquals(Unit.Order.STILL, worker.order());
                    assertEquals(14, worker.battleNetOrderDelay(),
                            "the click must preserve the existing ready wait");
                }
                case 234 -> {
                    assertEquals(Unit.Order.RETURN_GOODS, worker.order());
                    assertEquals(2, worker.battleNetOrderDelay());
                }
                case 237 -> assertPixel(worker, 800, 480);
                case 238, 239, 240 -> assertPixel(worker, 797, 483);
                case 241 -> assertPixel(worker, 794, 486);
                case 259 -> assertPixel(worker, 768, 512);
                case 369 -> {
                    assertPixel(worker, 768, 672);
                    assertEquals(2, worker.battleNetOrderDelay());
                }
                case 372 -> {
                    assertEquals(23, worker.tileX());
                    assertEquals(22, worker.tileY());
                    assertPixel(worker, 768, 672);
                }
                case 394 -> {
                    assertFalse(worker.isOnMap());
                    assertEquals(0, worker.carried());
                    assertEquals(149, worker.waitCycles());
                }
                default -> { }
            }
        }
    }

    private static void assertPixel(Unit unit, int x, int y) {
        assertEquals(x, unit.pixelX());
        assertEquals(y, unit.pixelY());
    }

    private static Unit atTile(World world, int x, int y) {
        return world.unitsSnapshot().stream()
                .filter(Unit::isAlive)
                .filter(Unit::isOnMap)
                .filter(unit -> unit.tileX() == x && unit.tileY() == y)
                .findFirst().orElse(null);
    }
}
