package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Locks BNE's shared queued-order destination spread before resource dropout. */
class Human13MineExitGoalSpreadRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("human 13's miner spreads its fortress goal before surfacing")
    void minerUsesSpreadReturnPointForNorthMineExit() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human/level13h", 0, 1);
        Assumptions.assumeTrue(mission != null, "Human 13 is not in the pack");
        World world = mission.world();
        Unit peon = unitById(world, 53);
        Unit fortress = unitAt(world, "unit-fortress", 81, 2);
        assertNotNull(peon, "Human 13 has no native-slot-1547 peon");
        assertNotNull(fortress, "Human 13 has no native-slot-1584 fortress");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 209) {
            mission.tick();
        }
        assertTrue(peon.removed());
        assertEquals(75, peon.tileX());
        assertEquals(9, peon.tileY());

        mission.tick();
        assertEquals(210, fixtureCycle(world));
        assertFalse(peon.removed());
        assertEquals(75, peon.tileX());
        assertEquals(8, peon.tileY());
        assertEquals(Unit.Order.STILL, peon.order());
        assertEquals(25, peon.battleNetOrderDelay());
        assertEquals(77, peon.orderTargetX());
        assertEquals(6, peon.orderTargetY());
        assertSame(fortress, peon.returnDepotGoal());
        assertEquals(Unit.QueuedOrderKind.RETURN_GOODS,
                peon.queuedOrders().getFirst().kind());
        assertEquals(77, peon.queuedOrders().getFirst().x());
        assertEquals(6, peon.queuedOrders().getFirst().y());
    }

    @Test
    @DisplayName("human 13's contained depot-ready miner selects gold before surfacing")
    void containedDepotReadyMinerSelectsGoldBeforeSurfacing() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human/level13h", 0, 1);
        Assumptions.assumeTrue(mission != null, "Human 13 is not in the pack");
        World world = mission.world();
        Unit peon = unitById(world, 53);
        Unit fortress = unitAt(world, "unit-fortress", 81, 2);
        Unit mine = unitAt(world, "unit-gold-mine", 75, 9);
        assertNotNull(peon, "Human 13 has no native-slot-1547 peon");
        assertNotNull(fortress, "Human 13 has no native-slot-1584 fortress");
        assertNotNull(mine, "Human 13 has no native-slot-1544 gold mine");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 522) {
            mission.tick();
        }
        assertTrue(peon.removed());
        assertEquals(81, peon.tileX());
        assertEquals(2, peon.tileY());
        assertSame(fortress, peon.worksite());
        boolean[] component = world.battleNetConnectivityCell(peon);
        assertTrue(component[fortress.tileX()
                        + fortress.tileY() * world.map.width()],
                "the contained worker's component must include its depot anchor");
        assertTrue(component[mine.tileX() + mine.tileY() * world.map.width()],
                "the contained worker's component must include the nearby mine");
        assertTrue(fortress.isAlive() && fortress.isOnMap()
                        && fortress.type().storesResource(
                                net.chonkbase.chonkcraft.engine.unit.UnitType.Resource.GOLD),
                "the fortress must remain an eligible gold depot");
        assertTrue(mine.resourcesHeld() != 0,
                "the nearby mine must retain gold, actual=" + mine.resourcesHeld());
        assertSame(mine, world.findBattleNetReadyGoldMine(peon),
                "the contained ready scan must see its connected gold mine");

        mission.tick();
        assertEquals(523, fixtureCycle(world));
        assertFalse(peon.removed());
        assertEquals(85, peon.tileX(),
                "contained ready assignment must choose native's mine-authored face");
        assertEquals(3, peon.tileY());
        assertEquals(Unit.Order.STILL, peon.order());
        assertEquals(25, peon.battleNetOrderDelay());
        assertEquals(1, peon.queuedOrders().size());
        assertEquals(Unit.QueuedOrderKind.HARVEST,
                peon.queuedOrders().getFirst().kind());
        assertEquals(75, peon.queuedOrders().getFirst().x());
        assertEquals(9, peon.queuedOrders().getFirst().y());
        assertSame(mine, peon.resourceUnit());
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

    private static Unit unitAt(World world, String ident, int x, int y) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.type() != null && ident.equals(unit.type().ident())
                    && unit.tileX() == x && unit.tileY() == y) {
                return unit;
            }
        }
        return null;
    }
}
