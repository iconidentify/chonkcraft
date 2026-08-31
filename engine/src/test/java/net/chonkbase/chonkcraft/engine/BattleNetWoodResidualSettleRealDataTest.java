package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
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

        while (fixtureCycle(world) < 215) {
            mission.tick();
        }
        assertPosition(worker, 19, 15, 0, 0,
                "fixture 215 settles the northeast residual at its pixel anchor");
        assertEquals(0, worker.pathLength(),
                "native parks the ally-blocked southeast tail at route index 20");

        mission.tick();
        assertPosition(worker, 20, 15, -32, 0,
                "fixture 216 redraws around the allied peon and commits east");
        assertEquals(Direction.fromDelta(1, 0), worker.lastStepHeading());
    }

    @Test
    void xhuman11FirstGenerationTerminalRefusalRedrawsImmediately() {
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
                .filter(unit -> unit.id() == 12)
                .findFirst().orElse(null);
        assertNotNull(worker,
                "XHuman 11 must contain native peon 1588 / Java 12");

        // The final southeast residual settles beside allied peon 1586 on
        // fixture 210. Native raises packed collision one to two and parks
        // route index 20. Because this is only the first terminal generation,
        // fixture 211 redraws E,SE and commits E immediately. The later
        // XHuman 12 witness enters with collision two, advances to three, and
        // therefore owns the separate 3,2,1 resource construction cadence.
        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 210) {
            mission.tick();
        }
        assertPosition(worker, 19, 16, 0, 0,
                "fixture 210 parks the refused terminal route");
        assertEquals(2, worker.battleNetCollisionCounter());
        assertEquals(0, worker.pathLength());

        mission.tick();
        assertPosition(worker, 20, 16, -32, 0,
                "fixture 211 immediately consumes the redrawn east head");
        assertEquals(Direction.fromDelta(1, 0), worker.lastStepHeading());
        assertEquals(1, worker.pathLength(),
                "native retains the southeast tail of E,SE");
        assertEquals(Direction.fromDelta(1, 1), worker.peekHeading());
    }

    @Test
    void xhuman12BlockedWoodOrderPointReplansAfterItsOneStepPrefix() {
        // XHuman 12 peon 1376 / Java 224 approaches tree (14,89) from
        // (11,88). Static blocked square (13,88) is native orderXY, so the
        // first route contains only NE. Peon 1387 claims that tree before the
        // residual settles at (12,87), so action 23 replaces it with the next
        // unclaimed tree (15,89), pays 3,2,1, and redraws NE,E,SE,S. A direct
        // tree route caches NE,E,E,SE and consumes its stale east tail on
        // fixture 296 instead.
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit worker = world.unitsSnapshot().stream()
                .filter(unit -> unit.id() == 224)
                .findFirst().orElse(null);
        assertNotNull(worker,
                "XHuman 12 must contain native peon 1376 / Java 224");

        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 280) {
            mission.tick();
        }
        assertPosition(worker, 12, 87, -32, 32,
                "fixture 280 commits the one-step northeast prefix");
        assertEquals(0, worker.pathLength(),
                "native does not cache the tree-directed east tail");

        while (fixtureCycle(world) < 295) {
            mission.tick();
        }
        assertPosition(worker, 12, 87, -2, 2,
                "fixture 295 retains the final northeast pixels");

        mission.tick();
        assertPosition(worker, 12, 87, 0, 0,
                "fixture 296 settles and starts the three-call replan");
        assertEquals(0, worker.pathLength(),
                "settlement retains route index twenty");
        assertEquals(2, worker.battleNetOrderDelay(),
                "fixture 296 is the first action-23 construction visit");
        assertEquals(15, worker.resourceTileX(),
                "the fresh construction skips the tree another peon claimed");
        assertEquals(89, worker.resourceTileY());
        assertEquals(15, worker.battleNetWoodOrderX(),
                "native writes the replacement tree into orderXY on the"
                        + " settle visit");
        assertEquals(89, worker.battleNetWoodOrderY());
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
