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
    @DisplayName("an XHuman 9 peon keeps traversal order across an equally near mine face")
    void anXHuman9PeonKeepsTraversalOrderAcrossAnEquallyNearMineFace() {
        Mission mission = mission("campaigns/human-exp/levelx09h");
        for (int cycle = 1; cycle < 188; cycle++) {
            mission.tick();
        }

        mission.tick();
        Unit peon = mission.world().unitsSnapshot().stream()
                .filter(unit -> unit.id() == 50)
                .findFirst().orElse(null);
        assertNotNull(peon, "native slot 1550 must remain paired with Java peon 50");
        assertEquals(109, peon.tileX());
        assertEquals(21, peon.tileY(),
                "equal footprint distance keeps the first free west-face square");
        assertMineExitReady(peon,
                "native slot 1550 must surface on fixture cycle 188");
    }

    @Test
    @DisplayName("an XHuman 9 loaded peon pays the eighth direct-return refusal band")
    void anXHuman9LoadedPeonPaysTheEighthDirectReturnRefusalBand() {
        Mission mission = mission("campaigns/human-exp/levelx09h");
        Unit peon = mission.world().unitsSnapshot().stream()
                .filter(unit -> unit.id() == 4)
                .findFirst().orElse(null);
        assertNotNull(peon, "native slot 1596 must remain paired with Java peon 4");

        for (int cycle = 1; cycle <= 229; cycle++) {
            mission.tick();
        }
        assertEquals(61, peon.tileX());
        assertEquals(3, peon.tileY());
        assertEquals(100, peon.carried());
        assertEquals(8, peon.battleNetRefusals(),
                "the occupied south ray reaches refusal eight on fixture 229");
        assertEquals(14, peon.battleNetOrderDelay());

        for (int cycle = 230; cycle <= 243; cycle++) {
            mission.tick();
            assertEquals(61, peon.tileX(),
                    "the complete Move band holds through fixture " + cycle);
            assertEquals(3, peon.tileY());
        }
        mission.tick();
        assertEquals(Unit.Order.HARVEST, peon.order(),
                "Java's unified resource order retains the loaded return leg");
        assertEquals(61, peon.tileX());
        assertEquals(4, peon.tileY(),
                "native releases the cached south ray on fixture 244");

        Mission siblingMission = mission("campaigns/human-exp/levelx12h");
        Unit siblingPeon = byId(siblingMission.world(), 50);
        assertNotNull(siblingPeon,
                "native slot 1550 must remain paired with Java peon 50");

        while (fixtureCycle(siblingMission.world()) < 253) {
            siblingMission.tick();
        }
        assertEquals(6, siblingPeon.tileX());
        assertEquals(29, siblingPeon.tileY());
        assertEquals(100, siblingPeon.carried());
        assertEquals(10, siblingPeon.battleNetRefusals());
        assertEquals(14, siblingPeon.battleNetOrderDelay(),
                "the queued sibling owns one complete Move refusal band");
        assertEquals(15, siblingPeon.battleNetAnimationTimer());

        while (fixtureCycle(siblingMission.world()) < 268) {
            siblingMission.tick();
        }
        assertEquals(6, siblingPeon.tileX());
        assertEquals(28, siblingPeon.tileY(),
                "native first-steps north as the queued sibling vacates");
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
    @DisplayName("an Orc 10 AI tanker pays BNE's depot-ready window")
    void anOrc10AiTankerPaysBnesDepotReadyWindow() {
        Mission mission = mission("campaigns/orc/level10o");
        assertTankerDepotReadyAndResume(mission, 53,
                439, 464, 467, 8, 18, 6, 18,
                "native slot 1547");
    }

    @Test
    @DisplayName("an Orc 7 AI tanker pays BNE's depot-ready window")
    void anOrc7AiTankerPaysBnesDepotReadyWindow() {
        Mission mission = mission("campaigns/orc/level07o");
        assertTankerDepotReadyAndResume(mission, 68,
                596, 621, 624, 52, 34, 54, 36,
                "native slot 1532");
    }

    @Test
    @DisplayName("an Human 8 empty depot route owns its active idle draw")
    void anHuman8EmptyDepotRouteOwnsItsActiveIdleDraw() {
        Mission mission = mission("campaigns/human/level08h");
        World world = mission.world();
        Unit peasant = byId(world, 64);
        Unit critter = byId(world, 61);
        assertNotNull(peasant,
                "native slot 1536 must remain paired with Java peasant 64");
        assertNotNull(critter,
                "native slot 1539 must remain paired with Java critter 61");

        while (fixtureCycle(world) < 280) {
            mission.tick();
        }
        assertEquals(Unit.Order.RETURN_GOODS, peasant.order());
        assertEquals(2595, peasant.battleNetSequenceOffset(),
                "action 24 promotes on the worker's Still body");
        assertEquals(3, peasant.battleNetAnimationTimer());

        mission.tick();
        assertEquals(281, fixtureCycle(world));
        assertEquals(Unit.Order.HARVEST, peasant.order(),
                "Java's unified resource projection keeps native action 24 active");
        assertEquals(2595, peasant.battleNetSequenceOffset());
        assertEquals(2, peasant.battleNetAnimationTimer());

        mission.tick();
        assertEquals(2595, peasant.battleNetSequenceOffset());
        assertEquals(1, peasant.battleNetAnimationTimer());

        mission.tick();
        assertEquals(283, fixtureCycle(world));
        assertEquals(2595, peasant.battleNetSequenceOffset());
        assertEquals(3, peasant.battleNetAnimationTimer(),
                "the empty depot route restarts the three-call active idle band");
        assertEquals(Unit.Order.STILL, critter.order(),
                "the next critter must consume its own choice instead of the peasant's");

        mission.tick();
        assertEquals(2, peasant.battleNetAnimationTimer());
        mission.tick();
        assertEquals(1, peasant.battleNetAnimationTimer());
        mission.tick();
        assertEquals(286, fixtureCycle(world));
        assertEquals(2595, peasant.battleNetSequenceOffset());
        assertEquals(3, peasant.battleNetAnimationTimer(),
                "an unchanged empty route repeats the active-order band every three visits");
    }

    @Test
    @DisplayName("a Human 8 resource-hit Move chains the hit retained on its stride")
    void anHuman8ResourceHitMoveChainsTheHitRetainedOnItsStride() {
        Mission mission = mission("campaigns/human/level08h");
        World world = mission.world();
        Unit peasant = byId(world, 64);
        assertNotNull(peasant,
                "native slot 1536 must remain paired with Java peasant 64");

        while (fixtureCycle(world) < 346) {
            mission.tick();
        }
        assertEquals(Unit.Order.MOVE, peasant.order());
        assertEquals(Unit.Order.HARVEST, peasant.savedOrder());
        assertNotNull(peasant.offeredTarget(),
                "the fixture-331 blow remains offered while the stride drains");

        mission.tick();
        assertEquals(347, fixtureCycle(world));
        assertEquals(Unit.Order.MOVE, peasant.order(),
                "native keeps raw action 3 for the chained hit-reaction body");
        assertEquals(89, peasant.orderTargetX(),
                "the retained hit authors native's second escape point");
        assertEquals(60, peasant.orderTargetY());
        assertEquals(2595, peasant.battleNetSequenceOffset(),
                "the second reaction opens on the peasant Still sequence");
        assertEquals(3, peasant.battleNetAnimationTimer());

        mission.tick();
        mission.tick();
        mission.tick();
        assertEquals(350, fixtureCycle(world));
        assertEquals(Unit.Order.HARVEST, peasant.order(),
                "the free second escape point restores the saved resource order");
    }

    @Test
    @DisplayName("a Human 8 resource-hit restore owns its final idle marker")
    void anHuman8ResourceHitRestoreOwnsItsFinalIdleMarker() {
        Mission mission = mission("campaigns/human/level08h");
        World world = mission.world();
        Unit peasant = byId(world, 64);
        Unit critter = byId(world, 108);
        assertNotNull(peasant,
                "native slot 1536 must remain paired with Java peasant 64");
        assertNotNull(critter,
                "native slot 1492 must remain paired with Java critter 108");

        while (fixtureCycle(world) < 301) {
            mission.tick();
        }
        assertEquals(0x535014dc, world.battleNetRandomSeed(),
                "the first free hit-flee restore must pay exactly one idle draw");

        while (fixtureCycle(world) < 316) {
            mission.tick();
        }
        assertEquals(0xa9ecb6ac, world.battleNetRandomSeed(),
                "an empty resumed route must not pay the restore marker twice");

        while (fixtureCycle(world) < 350) {
            mission.tick();
        }
        assertEquals(0xaf6d1719, world.battleNetRandomSeed(),
                "the final retained-hit body owns its marker before route restoration");
        assertEquals(2595, peasant.battleNetSequenceOffset(),
                "the restored resource action keeps the native Still cursor");
        assertEquals(3, peasant.battleNetAnimationTimer(),
                "the empty restored route opens one three-call idle band");
        assertEquals(0, peasant.battleNetRefusals(),
                "a restored empty route has not entered a refusal generation");

        while (fixtureCycle(world) < 358) {
            mission.tick();
        }
        assertEquals(Unit.Order.MOVE, critter.order(),
                "the critter must consume its native choice and east direction");
        assertEquals(38, critter.orderTargetX());
        assertEquals(84, critter.orderTargetY());
        assertEquals(0x45df3775, world.battleNetRandomSeed(),
                "the complete fixture-358 asynchronous ledger must stay aligned");
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

        Unit siblingPeasant = byId(mission.world(), 161);
        assertNotNull(siblingPeasant,
                "native slot 1439 must remain paired with Java peasant 161");

        while (fixtureCycle(mission.world()) < 251) {
            mission.tick();
        }
        assertEquals(74, siblingPeasant.tileX());
        assertEquals(53, siblingPeasant.tileY(),
                "the first north-east byte is shared by native and Java");

        while (fixtureCycle(mission.world()) < 273) {
            mission.tick();
        }
        assertEquals(75, siblingPeasant.tileX());
        assertEquals(52, siblingPeasant.tileY(),
                "native keeps the second north-east byte through the vacating sibling");
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

    private static void assertTankerDepotReadyAndResume(Mission mission,
            int javaId, int exitCycle, int resourceCycle, int firstStrideCycle,
            int exitX, int exitY, int strideX, int strideY, String nativeSlot) {
        while (fixtureCycle(mission.world()) < exitCycle) {
            mission.tick();
        }

        Unit tanker = byId(mission.world(), javaId);
        assertNotNull(tanker, nativeSlot + " must remain paired with Java " + javaId);
        assertEquals("unit-human-oil-tanker", tanker.type().ident());
        assertEquals(exitX, tanker.tileX());
        assertEquals(exitY, tanker.tileY());
        assertEquals(0, tanker.carried());
        assertEquals(Unit.Order.STILL, tanker.order(),
                nativeSlot + " must surface through the naval ready boundary");
        assertEquals(25, tanker.battleNetOrderDelay(),
                "the empty depot exit must retain BNE's 25-cycle Still head");
        assertEquals(1, tanker.queuedOrders().size(),
                "raw next action 23 must remain queued behind that Still head");
        assertEquals(Unit.QueuedOrderKind.HARVEST,
                tanker.queuedOrders().get(0).kind());
        assertNotNull(tanker.queuedOrders().get(0).target(),
                "the queued continuation must retain the live oil platform");

        for (int cycle = exitCycle + 1; cycle < resourceCycle; cycle++) {
            mission.tick();
            assertEquals(Unit.Order.STILL, tanker.order(),
                    "native holds the tanker Still through fixture cycle " + cycle);
            assertEquals(exitX, tanker.tileX());
            assertEquals(exitY, tanker.tileY());
        }
        mission.tick();
        assertEquals(resourceCycle, fixtureCycle(mission.world()));
        assertEquals(Unit.Order.HARVEST, tanker.order(),
                "native promotes action 23 on fixture cycle " + resourceCycle);
        assertEquals(Unit.Order.HARVEST, tanker.currentAction());

        for (int cycle = resourceCycle + 1; cycle < firstStrideCycle; cycle++) {
            mission.tick();
            assertEquals(exitX, tanker.tileX());
            assertEquals(exitY, tanker.tileY());
        }
        mission.tick();
        assertEquals(firstStrideCycle, fixtureCycle(mission.world()));
        assertEquals(strideX, tanker.tileX(),
                "native takes its first doubled tanker stride on fixture cycle "
                        + firstStrideCycle);
        assertEquals(strideY, tanker.tileY());
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

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }

    private static Unit byId(World world, int id) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.id() == id) {
                return unit;
            }
        }
        return null;
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
