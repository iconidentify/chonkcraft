package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated doubled-step Patrol route for XOrc 8 destroyer 1468. */
class XOrc08DestroyerPatrolRouteRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xorc 8 destroyer keeps the wall-optimized sixth patrol heading")
    void xorc8DestroyerKeepsTheWallOptimizedSixthPatrolHeading() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/orc-exp/levelx08o", 1, 1);
        Assumptions.assumeTrue(mission != null, "XOrc 8 is not in the pack");
        World world = mission.world();

        Unit destroyer = unitById(world, 132);
        assertNotNull(destroyer, "XOrc 8 has no native-slot-1468 destroyer");
        assertEquals(116, destroyer.tileX());
        assertEquals(48, destroyer.tileY());

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 4) {
            mission.tick();
        }

        assertEquals(114, destroyer.tileX());
        assertEquals(50, destroyer.tileY());
        assertEquals(8, destroyer.pathLength());
        assertEquals(5, destroyer.peekHeadingAtDepth(4),
                "native's sixth stored heading is south-west, not the open-ray west tie");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 164) {
            mission.tick();
        }
        assertEquals(104, destroyer.tileX());
        assertEquals(58, destroyer.tileY(),
                "the sixth doubled patrol stride lands on native row 58");
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
