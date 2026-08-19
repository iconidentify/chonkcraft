package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Locks terrain-harvest residual timing to authenticated BNE campaign state. */
class BattleNetWoodResidualSettleRealDataTest {

    private static final int INITIALIZATION_TICKS = 2;

    @Test
    void xhuman11WoodPeonPaysTheQuietResidualSettleBeforeItsShortcut() {
        // XHuman 11 peon 1584 (Java 16) walks NE onto 10,6 while its leftover
        // SE is blocked by the allied peon on 11,7. Native reaches pixel
        // 320,192 at fixture 37 but writes route_index 20 and remains on
        // 10,6. Only fixture 38 rewrites NE+SE to E and commits 11,6.
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx11h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 11 is not in the pack");
        World world = mission.world();
        Unit worker = world.unitsSnapshot().stream()
                .filter(unit -> unit.player() == 2
                        && unit.type() != null
                        && "unit-peon".equals(unit.type().ident())
                        && unit.tileX() == 9 && unit.tileY() == 7)
                .findFirst().orElse(null);
        assertNotNull(worker,
                "XHuman 11 must contain native peon 1584 / Java 16");

        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 36) {
            mission.tick();
        }
        assertPosition(worker, 10, 6, -2, 2,
                "fixture 36 is the final two-pixel residual");

        mission.tick();
        assertPosition(worker, 10, 6, 0, 0,
                "fixture 37 must settle quietly before the shortcut");
        assertEquals(6, worker.pathLength(),
                "the quiet settle keeps the cached terrain route");
        assertEquals(3, worker.peekHeading(),
                "the allied peon still blocks the cached southeast heading");

        mission.tick();
        assertPosition(worker, 11, 6, -32, 0,
                "fixture 38 may rewrite NE+SE to east and commit the step");

        while (fixtureCycle(world) < 53) {
            mission.tick();
        }
        assertPosition(worker, 11, 6, -2, 0,
                "fixture 53 is the next final two-pixel residual");

        mission.tick();
        assertPosition(worker, 11, 6, 0, 0,
                "fixture 54 must also pay the quiet route-index-20 visit");
        assertEquals(0, worker.pathLength(),
                "the repeated east remainder is parked for a fresh route");

        mission.tick();
        assertPosition(worker, 12, 6, -32, 0,
                "fixture 55 may commit the next east heading");

        while (fixtureCycle(world) < 70) {
            mission.tick();
        }
        assertPosition(worker, 12, 6, -2, 0,
                "fixture 70 is the final east residual before a free turn");

        mission.tick();
        assertPosition(worker, 13, 7, -32, -32,
                "fixture 71 must commit the free southeast turn without a hold");

        while (fixtureCycle(world) < 103) {
            mission.tick();
        }
        assertPosition(worker, 14, 9, -32, -32,
                "fixture 103 must replan southeast after the short route exhausts");

        while (fixtureCycle(world) < 119) {
            mission.tick();
        }
        assertPosition(worker, 15, 10, -32, -32,
                "later repeated southeast headings must not inherit the one-shot hold");
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - INITIALIZATION_TICKS;
    }

    private static void assertPosition(Unit unit, int x, int y,
            int offsetX, int offsetY, String message) {
        assertEquals(x, unit.tileX(), message + " (x)");
        assertEquals(y, unit.tileY(), message + " (y)");
        assertEquals(offsetX, unit.offsetX(), message + " (offset x)");
        assertEquals(offsetY, unit.offsetY(), message + " (offset y)");
    }
}
