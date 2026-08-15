package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GiveOrder 27 keeps Repair through the last Move body after the peon
 * stands on the hall ring.
 *
 * <p>Authenticated repair-1/00: peon 1594 lands 26,21 at fixture 42 still
 * on Repair and is Still at 56. Still'ing the arrival visit fulfilled the
 * order at 38.
 */
class BattleNetRepairCadenceRealDataTest {

    @Test
    @DisplayName("a peon stays on repair after the last tile of a hall mend")
    void aPeonStaysOnRepairAfterTheLastTileOfAHallMend() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        GameMap map = new GameMap(64, 64, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        data.configureWorld(world, PudMap.Tileset.FOREST);
        UnitType hallType = data.unitTypes().types().get("unit-great-hall");
        UnitType peonType = data.unitTypes().types().get("unit-peon");
        assertNotNull(hallType, "retail roster has no great hall");
        assertNotNull(peonType, "retail roster has no peon");
        Unit hall = world.createUnit(hallType, 0, 22, 22);
        Unit peon = world.createUnit(peonType, 0, 25, 18);
        CommandApplier applier = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        assertTrue(applier.apply(GameCommand.repair(0, peon.id(), hall.id())),
                "GiveOrder 27 must accept a peon told to mend a hall");

        Integer arrived = null;
        Unit.Order orderOnArrival = null;
        Integer stillAt = null;
        for (int i = 0; i < 80; i++) {
            world.tick();
            if (peon.tileX() == 26 && peon.tileY() == 21 && arrived == null) {
                arrived = i;
                orderOnArrival = peon.order();
            }
            if (arrived != null && peon.order() == Unit.Order.STILL && stillAt == null) {
                stillAt = i;
            }
        }

        assertEquals(26, peon.tileX(),
                "the peon must still stand on 26,21");
        assertEquals(21, peon.tileY(),
                "the peon must still stand on 26,21");
        assertEquals(Unit.Order.REPAIR, orderOnArrival,
                "retail is still on Repair the visit it lands 26,21, not "
                        + orderOnArrival);
        assertTrue(stillAt != null && stillAt >= arrived + 10,
                "retail stands down after the last Move body, not the arrival visit");
        assertEquals(Unit.Order.STILL, peon.order(),
                "the peon must still stand down after the last Move body");
    }

    @Test
    @DisplayName("an orc 1 hall mend is still Repair through fixture 55")
    void anOrc1HallMendIsStillRepairThroughFixture55() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/orc/level01o", 0);
        Assumptions.assumeTrue(mission != null, "Orc 1 is not in the pack");
        World world = mission.world();
        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);
        for (int tick = 0; tick < 2; tick++) {
            mission.tick();
        }
        Unit peon = null;
        Unit hall = null;
        for (Unit unit : world.unitsSnapshot()) {
            if (!unit.isAlive() || unit.type() == null) {
                continue;
            }
            if (peon == null && unit.type().ident().contains("peon")
                    && Math.max(Math.abs(unit.tileX() - 25),
                            Math.abs(unit.tileY() - 18)) <= 2) {
                peon = unit;
            }
            if (hall == null && unit.type().ident().contains("great-hall")) {
                hall = unit;
            }
        }
        assertNotNull(peon, "Orc 1 has no peon near 25,18");
        assertNotNull(hall, "Orc 1 has no great hall");
        boolean issued = false;
        Integer stillAt = null;
        while ((int) world.cycle() - 2 <= 70) {
            if ((int) world.cycle() - 2 == 5 && !issued) {
                assertTrue(commands.apply(GameCommand.repair(
                                peon.player(), peon.id(), hall.id())),
                        "the hall mend click must be accepted");
                issued = true;
            }
            mission.tick();
            if (issued && peon.order() == Unit.Order.STILL && stillAt == null) {
                stillAt = (int) world.cycle() - 2;
            }
        }
        assertTrue(stillAt != null && stillAt >= 56,
                "retail stands Still at fixture 56 after leftover dest-arm, not "
                        + stillAt);
    }
}
