package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated first return route of Orc 10's loaded tanker 1541. */
class Orc10LoadedTankerReturnRouteRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("orc 10's loaded tanker corrects its spread depot point and first-steps north")
    void orc10LoadedTankerFirstStepsNorthTowardCorrectedDepotPoint() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc/level10o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Orc 10 is not in the pack");
        World world = mission.world();
        Unit tanker = unitById(world, 59);
        assertNotNull(tanker,
                "Orc 10 has no Java unit 59 / native tanker 1541");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 221) {
            mission.tick();
        }

        assertEquals(14, tanker.tileX(), "the tanker surfaces at 14,34");
        assertEquals(34, tanker.tileY(), "the tanker surfaces at 14,34");
        assertEquals(11, tanker.orderTargetX(),
                "Return Goods stores the authenticated spread x before MoveToDepot");
        assertEquals(23, tanker.orderTargetY(),
                "the queued Return Goods order retains its spread y before Move OP0");

        mission.tick();
        assertEquals(222, fixtureCycle(world));
        assertEquals(12, tanker.orderTargetX(),
                "MoveToDepot corrects the spread x onto the doubled lattice");
        assertEquals(23, tanker.orderTargetY(),
                "the odd marked-range y remains the native depot point");
        assertEquals(14, tanker.tileX(),
                "the first doubled heading is cardinal north");
        assertEquals(32, tanker.tileY(),
                "fixture 222 consumes a two-tile north heading");
        assertEquals(Direction.fromDelta(0, -1), tanker.lastStepHeading(),
                "the replacement route begins north, not north-west");
        assertEquals(4, tanker.pathLength(),
                "four native return headings remain after the first stride");
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
