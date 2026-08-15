package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.engine.parity.EngineTrace;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A harvest walk dest-arms the tile onto the next path square at step start
 * while the pixels are still on the last square, then dest-arms again when
 * those pixels land -- sixteen cycles later on a continued heading.
 */
class BattleNetHarvestDestArmTest {

    private static List<int[]> destArms(World world, Unit worker, int through) {
        List<int[]> arms = new ArrayList<>();
        int lastX = worker.tileX();
        int lastY = worker.tileY();
        while (world.cycle() < through) {
            world.tick();
            if (worker.tileX() != lastX || worker.tileY() != lastY) {
                arms.add(new int[] {
                        (int) world.cycle(), worker.tileX(), worker.tileY(),
                        worker.pixelX(), worker.pixelY(), lastX, lastY
                });
                lastX = worker.tileX();
                lastY = worker.tileY();
            }
        }
        return arms;
    }

    private static boolean isWorker(Unit unit) {
        if (unit.type() == null) {
            return false;
        }
        String ident = unit.type().ident();
        return ident.contains("peon") || ident.contains("peasant");
    }

    private static Unit workerAt(World world, int x, int y) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.isAlive() && unit.isOnMap()
                    && unit.tileX() == x && unit.tileY() == y
                    && isWorker(unit)) {
                return unit;
            }
        }
        return null;
    }

    @Test
    @DisplayName("an opening harvest walk dest-arms the next square sixteen cycles later")
    void anOpeningHarvestWalkDestArmsTheNextSquareSixteenCyclesLater() {
        GameData data = EngineTrace.data();
        assumeTrue(data != null,
                "No asset pack/install configured. Set -Dchonkcraft.pack or wc2.install.dir.");
        World west = EngineTrace.world(data, "campaigns/human-exp/levelx02h");
        assumeTrue(west != null, "the second human expansion map must load");
        Unit westPeon = workerAt(west, 24, 11);
        assertNotNull(westPeon, "the north-west harvest peon must stand at 24,11");
        List<int[]> westArms = destArms(west, westPeon, 30);
        assertTrue(westArms.size() >= 2,
                "the north-west harvest peon must dest-arm twice in thirty cycles");
        int[] first = westArms.get(0);
        assertTrue(first[3] == first[5] * Unit.TILE_PIXELS
                        && first[4] == first[6] * Unit.TILE_PIXELS,
                "the first dest-arm must leave the pixels on the old square");
        assertEquals(westArms.get(1)[1] - first[1], first[1] - first[5],
                "the north-west walk must keep the same heading");
        assertEquals(westArms.get(1)[2] - first[2], first[2] - first[6],
                "the north-west walk must keep the same heading");
        assertEquals(16, westArms.get(1)[0] - first[0],
                "the next dest-arm used to wait three extra action-23 visits, "
                        + "so the tile lagged the pixels");

        World south = EngineTrace.world(data, "campaigns/human/level05h");
        assumeTrue(south != null, "the fifth human map must load");
        Unit southPeasant = workerAt(south, 35, 103);
        assertNotNull(southPeasant,
                "the southern harvest peasant must stand at 35,103");
        List<int[]> southArms = destArms(south, southPeasant, 40);
        assertTrue(southArms.size() >= 2,
                "the southern harvest peasant must dest-arm twice in forty cycles");
        assertEquals(southArms.get(1)[1] - southArms.get(0)[1],
                southArms.get(0)[1] - southArms.get(0)[5],
                "the second dest-arm must keep the same heading");
        assertEquals(southArms.get(1)[2] - southArms.get(0)[2],
                southArms.get(0)[2] - southArms.get(0)[6],
                "the second dest-arm must keep the same heading");
        assertEquals(16, southArms.get(1)[0] - southArms.get(0)[0],
                "a second independent harvest walk must dest-arm on the same beat");
    }

    @Test
    @DisplayName("a harvest walk that turns does not keep the sixteen-cycle beat")
    void aHarvestWalkThatTurnsDoesNotKeepTheSixteenCycleBeat() {
        GameData data = EngineTrace.data();
        assumeTrue(data != null,
                "No asset pack/install configured. Set -Dchonkcraft.pack or wc2.install.dir.");
        World world = EngineTrace.world(data, "campaigns/human/level05h");
        assumeTrue(world != null, "the fifth human map must load");
        Unit peasant = workerAt(world, 31, 102);
        assertNotNull(peasant, "the turning harvest peasant must stand at 31,102");
        List<int[]> arms = destArms(world, peasant, 50);
        assertTrue(arms.size() >= 2,
                "the turning harvest peasant must dest-arm at least twice");
        int firstDx = arms.get(0)[1] - arms.get(0)[5];
        int firstDy = arms.get(0)[2] - arms.get(0)[6];
        int secondDx = arms.get(1)[1] - arms.get(0)[1];
        int secondDy = arms.get(1)[2] - arms.get(0)[2];
        assertTrue(firstDx != secondDx || firstDy != secondDy,
                "the second dest-arm must change heading");
        int turnInterval = arms.get(1)[0] - arms.get(0)[0];
        assertTrue(turnInterval != 16,
                "a heading change is not the continued-heading sixteen-cycle beat; "
                        + "interval was " + turnInterval);
    }
}
