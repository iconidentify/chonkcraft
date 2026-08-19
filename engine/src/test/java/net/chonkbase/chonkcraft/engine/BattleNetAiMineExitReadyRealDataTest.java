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
    @DisplayName("an XHuman 8 AI peon keeps BNE's west route to its stronghold")
    void anXHuman8AiPeonKeepsTheWestRouteToItsStronghold() {
        Mission mission = mission("campaigns/human-exp/levelx08h");
        for (int cycle = 1; cycle < 193; cycle++) {
            mission.tick();
        }

        mission.tick();
        Unit peon = at(mission.world(), "unit-peon", 59, 70);
        assertMineExitReady(peon,
                "native slot 1501 must surface south-west of its mine on cycle 193");
        for (int cycle = 194; cycle < 221; cycle++) {
            mission.tick();
        }
        mission.tick();
        assertEquals(58, peon.tileX());
        assertEquals(70, peon.tileY(),
                "native stores W,W,W toward the solid stronghold skirt");
    }

    @Test
    @DisplayName("an XHuman 8 laden peon retries its occupied stronghold skirt on time")
    void anXHuman8LadenPeonRetriesItsOccupiedStrongholdSkirtOnTime() {
        Mission mission = mission("campaigns/human-exp/levelx08h");
        for (int cycle = 1; cycle < 228; cycle++) {
            mission.tick();
        }

        mission.tick();
        Unit peon = at(mission.world(), "unit-peon", 19, 10);
        assertNotNull(peon,
                "native slot 1575 must settle its east stride on fixture cycle 228");
        assertEquals(100, peon.carried());
        for (int cycle = 229; cycle < 250; cycle++) {
            mission.tick();
        }
        mission.tick();
        assertEquals(20, peon.tileX());
        assertEquals(9, peon.tileY(),
                "the blocked north-east heading must retry without a second action-25 delay");
    }

    @Test
    @DisplayName("an XHuman 8 laden peon finishes its negative Move body before retrying")
    void anXHuman8LadenPeonFinishesItsNegativeMoveBodyBeforeRetrying() {
        Mission mission = mission("campaigns/human-exp/levelx08h");
        for (int cycle = 1; cycle < 260; cycle++) {
            mission.tick();
        }

        mission.tick();
        Unit peon = at(mission.world(), "unit-peon", 58, 71);
        assertNotNull(peon,
                "native slot 1498 must be draining its north-west return stride on cycle 260");
        assertEquals(100, peon.carried());
        for (int cycle = 261; cycle < 272; cycle++) {
            mission.tick();
            assertEquals(58, peon.tileX(),
                    "the negative stride's Move tail must hold through cycle " + cycle);
            assertEquals(71, peon.tileY());
        }
        mission.tick();
        assertEquals(57, peon.tileX(),
                "native parks the blocked north-west leftover then replans west on cycle 272");
        assertEquals(71, peon.tileY());
    }

    @Test
    @DisplayName("an XHuman 8 peon takes BNE's first free fallback mine face")
    void anXHuman8PeonTakesTheFirstFreeFallbackMineFace() {
        Mission mission = mission("campaigns/human-exp/levelx08h");
        for (int cycle = 1; cycle < 402; cycle++) {
            mission.tick();
        }

        mission.tick();
        Unit peon = at(mission.world(), "unit-peon", 10, 83);
        assertMineExitReady(peon,
                "native slot 1475 must surface at the first free east-face square"
                        + " after the coastal mine's west and south faces are blocked");
    }

    @Test
    @DisplayName("an XHuman 8 hall exit queues and pays its BNE watch-tower job")
    void anXHuman8HallExitQueuesAndPaysItsWatchTowerJob() {
        Mission mission = mission("campaigns/human-exp/levelx08h");
        for (int cycle = 1; cycle < 420; cycle++) {
            mission.tick();
        }

        mission.tick();
        Unit peon = at(mission.world(), "unit-peon", 20, 8);
        assertNotNull(peon,
                "native slot 1571 must leave the Great Hall's nearest free west square");
        assertEquals(Unit.Order.STILL, peon.order());
        assertEquals(25, peon.battleNetOrderDelay());
        assertEquals(1, peon.queuedOrders().size());
        assertEquals(Unit.QueuedOrderKind.BUILD,
                peon.queuedOrders().get(0).kind());
        assertNotNull(peon.pendingBuild());
        assertEquals("unit-orc-watch-tower", peon.pendingBuild().ident());
        assertEquals(21, peon.buildGoalX());
        assertEquals(3, peon.buildGoalY());
        assertEquals(50, mission.world().player(6).get(
                net.chonkbase.chonkcraft.engine.unit.UnitType.Resource.GOLD));
        assertEquals(1300, mission.world().player(6).get(
                net.chonkbase.chonkcraft.engine.unit.UnitType.Resource.WOOD));

        for (int cycle = 421; cycle <= 444; cycle++) {
            mission.tick();
            assertEquals(Unit.Order.STILL, peon.order(),
                    "native holds the depot-ready Still head through cycle " + cycle);
            assertEquals(20, peon.tileX());
            assertEquals(8, peon.tileY());
        }
        mission.tick();
        assertEquals(Unit.Order.BUILD, peon.order(),
                "native promotes Build on fixture cycle 445");
        assertEquals(Unit.Order.BUILD, peon.currentAction(),
                "the queued Build is semantically current on its promotion cycle");
        for (int cycle = 446; cycle < 448; cycle++) {
            mission.tick();
        }
        mission.tick();
        assertEquals(20, peon.tileX());
        assertEquals(7, peon.tileY(),
                "native takes its first northward tower stride on fixture cycle 448");
    }

    @Test
    @DisplayName("an XHuman 8 Stronghold exit keeps BNE's shallow east face")
    void anXHuman8StrongholdExitKeepsBnesShallowEastFace() {
        Mission mission = mission("campaigns/human-exp/levelx08h");
        for (int cycle = 1; cycle < 440; cycle++) {
            mission.tick();
        }

        mission.tick();
        Unit peon = at(mission.world(), "unit-peon", 57, 69);
        assertNotNull(peon,
                "native slot 1501 must leave the Stronghold's east face on cycle 440");
        assertEquals(Unit.Order.STILL, peon.order());
        assertEquals(25, peon.battleNetOrderDelay());
        assertEquals(1, peon.queuedOrders().size());
        assertEquals(Unit.QueuedOrderKind.HARVEST,
                peon.queuedOrders().get(0).kind());
        assertEquals(60, peon.queuedOrders().get(0).x());
        assertEquals(70, peon.queuedOrders().get(0).y());
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
        for (int cycle = 200; cycle < 221; cycle++) {
            mission.tick();
        }
        mission.tick();
        assertEquals(30, peasant.tileX());
        assertEquals(76, peasant.tileY(),
                "native's second stored heading stays south-west toward the castle skirt");
    }

    @Test
    @DisplayName("an XOrc 12 laden peasant stops its cached route on the keep skirt")
    void anXOrc12LadenPeasantStopsItsCachedRouteOnTheKeepSkirt() {
        Mission mission = mission("campaigns/orc-exp/levelx12o");
        for (int cycle = 1; cycle < 228; cycle++) {
            mission.tick();
        }

        mission.tick();
        Unit peasant = at(mission.world(), "unit-peasant", 75, 52);
        assertNotNull(peasant,
                "native slot 1434 must land its second north-east stride on cycle 228");
        assertEquals(100, peasant.carried());
        for (int cycle = 229; cycle < 250; cycle++) {
            mission.tick();
        }
        mission.tick();
        assertEquals(75, peasant.tileX());
        assertEquals(52, peasant.tileY(),
                "the final cached north heading stays on the already-reached keep skirt");
        assertEquals(2, peasant.battleNetOrderDelay(),
                "native enters action 25 on the same cycle that residual movement settles");
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
