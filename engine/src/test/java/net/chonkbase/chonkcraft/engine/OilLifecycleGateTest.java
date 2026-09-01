package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.ResourceInfo;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** End-to-end BNE oil economy failure and congestion gate. */
class OilLifecycleGateTest {

    /**
     * Checks the cross-layer ownership rule on every simulated oil cycle.
     *
     * <p>Raw actions 24, 25 and 26 are substates of retail's resource order;
     * they cannot legally survive after that outer order disappears. Checking
     * the tuple on every tick catches a split at the transition where it is
     * created instead of hundreds of cycles later when a tanker appears to be
     * idle.</p>
     */
    private static void tickWithOilInvariant(World world, Unit... tankers) {
        world.tick();
        for (Unit tanker : tankers) {
            Unit.BattleNetOilAction action = tanker.battleNetOilAction();
            if (action == Unit.BattleNetOilAction.TO_DEPOT
                    || action == Unit.BattleNetOilAction.FINAL_APPROACH
                    || action == Unit.BattleNetOilAction.INSIDE_RESOURCE) {
                assertEquals(Unit.Order.HARVEST, tanker.order(),
                        "native oil action " + action.rawAction()
                                + " escaped its resource order at cycle " + world.cycle());
            }
            if (action == Unit.BattleNetOilAction.TO_DEPOT) {
                assertTrue(tanker.returningToDepot(),
                        "native oil action 24 lost its homeward projection at cycle "
                                + world.cycle());
                assertTrue(tanker.carried() > 0 || tanker.removed(),
                        "visible native oil action 24 lost its cargo at cycle "
                                + world.cycle());
                assertTrue(tanker.returnDepotGoal() != null || tanker.resourceDepot() != null,
                        "native oil action 24 lost its depot at cycle " + world.cycle());
            }
        }
    }

    private static GameMap sea() {
        GameMap map = new GameMap(36, 36, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.WATER_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType platform() {
        UnitType type = new UnitType("unit-human-oil-platform");
        type.setTileSize(3, 3);
        type.setHitPoints(650);
        type.setBuilding(true);
        type.setGivesResource(UnitType.Resource.OIL);
        type.setCanHarvest(true);
        return type;
    }

    private static UnitType refinery() {
        UnitType type = new UnitType("unit-human-refinery");
        type.setTileSize(3, 3);
        type.setHitPoints(600);
        type.setBuilding(true);
        type.stores().add(UnitType.Resource.OIL);
        type.improveProduction().put(UnitType.Resource.OIL, 25);
        return type;
    }

    private static UnitType tanker() {
        UnitType type = new UnitType("unit-human-oil-tanker");
        type.setTileSize(2, 2);
        type.setHitPoints(90);
        type.setSpeed(10);
        type.setSeaUnit(true);
        type.setNumDirections(8);
        ResourceInfo oil = new ResourceInfo(UnitType.Resource.OIL);
        oil.setCapacity(100);
        oil.setWaitAtResource(150);
        oil.setWaitAtDepot(150);
        oil.setFileWhenLoaded("human/units/oil-tanker-full.png");
        oil.setFileWhenEmpty("human/units/oil-tanker-empty.png");
        type.gathering().put(UnitType.Resource.OIL, oil);
        return type;
    }

    @Test
    @DisplayName("two congested tankers both board and visibly bank their loads")
    void twoTankersSurviveOnePlatformLane() {
        World world = new World(sea());
        world.createUnit(refinery(), 0, 10, 15);
        Unit rig = world.createUnit(platform(), 15, 22, 15);
        rig.setResourcesHeld(25_000);
        world.recalculateSupply();
        Unit first = world.createUnit(tanker(), 0, 18, 12);
        Unit second = world.createUnit(tanker(), 0, 18, 18);
        assertTrue(world.orderHarvest(first, rig));
        assertTrue(world.orderHarvest(second, rig));

        boolean firstBoarded = false;
        boolean secondBoarded = false;
        for (int cycle = 0; cycle < 5_000
                && world.player(0).get(UnitType.Resource.OIL) < 250; cycle++) {
            tickWithOilInvariant(world, first, second);
            firstBoarded |= first.removed() && first.worksite() == rig;
            secondBoarded |= second.removed() && second.worksite() == rig;
        }

        assertTrue(firstBoarded, "the first tanker never cleared the boarding lane");
        assertTrue(secondBoarded, "the second tanker starved behind the first");
        assertEquals(250, world.player(0).get(UnitType.Resource.OIL),
                "two visible 100-oil loads must include the refinery's 25% bonus");
        int oilInFlight = first.carried() + second.carried();
        assertEquals(25_000,
                rig.resourcesHeld() + 200 + oilInFlight,
                "banked, carried and field oil must remain conserved while both tankers loop");
    }

    @Test
    @DisplayName("destroying a platform releases its contained tanker to another field")
    void destroyedPlatformReleasesAndReroutesContainedTanker() {
        World world = new World(sea());
        world.createUnit(refinery(), 0, 2, 16);
        Unit doomed = world.createUnit(platform(), 15, 9, 9);
        // The released hull is at (7,8); BNE's FindAnotherResource radius is
        // eight footprint tiles, so this live field deliberately sits on the
        // inclusive edge of that native search.
        Unit replacement = world.createUnit(platform(), 15, 16, 9);
        doomed.setResourcesHeld(25_000);
        replacement.setResourcesHeld(25_000);
        Unit boat = world.createUnit(tanker(), 0, 8, 8);
        assertTrue(world.orderHarvest(boat, doomed));

        for (int cycle = 0; cycle < 300 && boat.worksite() != doomed; cycle++) {
            tickWithOilInvariant(world, boat);
        }
        assertSame(doomed, boat.worksite(), "the fixture never contained the tanker");
        world.kill(doomed);
        assertTrue(boat.isOnMap(), "destroying a platform must release its tanker");
        assertEquals(0, boat.waitCycles(),
                "the released tanker must not serve the dead platform's dwell timer");

        boolean reachedReplacement = false;
        for (int cycle = 0; cycle < 2_500 && !reachedReplacement; cycle++) {
            tickWithOilInvariant(world, boat);
            reachedReplacement = boat.resourceUnit() == replacement
                    && (boat.worksite() == replacement || boat.isOnMap());
        }
        assertTrue(reachedReplacement, "the tanker did not reroute to the live platform");
        assertSame(replacement, boat.resourceUnit());
    }

    @Test
    @DisplayName("destroying the selected refinery reroutes a laden tanker")
    void destroyedDepotReroutesTheLadenLeg() {
        World world = new World(sea());
        Unit doomed = world.createUnit(refinery(), 0, 23, 16);
        Unit replacement = world.createUnit(refinery(), 0, 8, 16);
        Unit rig = world.createUnit(platform(), 15, 17, 16);
        rig.setResourcesHeld(25_000);
        world.recalculateSupply();
        Unit boat = world.createUnit(tanker(), 0, 16, 15);
        assertTrue(world.orderHarvest(boat, rig));

        for (int cycle = 0; cycle < 1_000 && boat.returnDepotGoal() != doomed; cycle++) {
            tickWithOilInvariant(world, boat);
        }
        assertSame(doomed, boat.returnDepotGoal(),
                "the fixture must select the nearer refinery first");
        assertEquals(100, boat.carried());
        world.kill(doomed);

        for (int cycle = 0; cycle < 2_500
                && !(boat.removed() && boat.worksite() == replacement); cycle++) {
            tickWithOilInvariant(world, boat);
        }
        assertTrue(boat.removed(), "the laden tanker never entered the surviving refinery");
        assertSame(replacement, boat.worksite());
        assertEquals(125, world.player(0).get(UnitType.Resource.OIL));
    }

    @Test
    @DisplayName("depletion destroys the dry platform and continues at the next one")
    void depletionBanksTheLastLoadAndSelectsAnotherField() {
        World world = new World(sea());
        world.createUnit(refinery(), 0, 2, 16);
        Unit lastLoad = world.createUnit(platform(), 15, 11, 16);
        Unit next = world.createUnit(platform(), 15, 19, 16);
        lastLoad.setResourcesHeld(100);
        next.setResourcesHeld(25_000);
        world.recalculateSupply();
        UnitType type = tanker();
        Unit boat = world.createUnit(type, 0, 10, 15);
        assertTrue(world.orderHarvest(boat, lastLoad));

        boolean selectedNext = false;
        for (int cycle = 0; cycle < 4_000 && !selectedNext; cycle++) {
            world.tick();
            selectedNext = world.player(0).get(UnitType.Resource.OIL) >= 125
                    && boat.resourceUnit() == next;
        }
        assertFalse(lastLoad.isAlive(), "the exhausted platform must be removed");
        assertEquals(125, world.player(0).get(UnitType.Resource.OIL));
        assertTrue(selectedNext, "the empty tanker did not continue at the next field");
        assertEquals("human/units/oil-tanker-empty.png",
                type.imageFileFor("summer", UnitType.Resource.OIL, false));
    }

    @Test
    @DisplayName("a platform exit cannot orphan the automatic return order")
    void platformExitCannotOrphanTheAutomaticReturnOrder() {
        World world = new World(sea());
        Unit depot = world.createUnit(refinery(), 0, 16, 19);
        Unit rig = world.createUnit(platform(), 15, 25, 1);
        rig.setResourcesHeld(65_000);
        Unit boat = world.createUnit(tanker(), 0, 23, 4);
        world.recalculateSupply();
        world.restoreContained(boat, rig, false, Unit.Order.HARVEST);
        world.restoreHarvestState(boat, rig, rig.tileX(), rig.tileY(), false, 0);
        boat.setCarrying(UnitType.Resource.OIL);
        boat.setHeldResource(UnitType.Resource.OIL);
        boat.setCarried(0);

        tickWithOilInvariant(world, boat);

        assertFalse(boat.removed(), "the full tanker never surfaced from the platform");
        assertSame(Unit.BattleNetOilAction.TO_DEPOT, boat.battleNetOilAction());
        assertEquals(Unit.Order.HARVEST, boat.order());

        for (int cycle = 0; cycle < 3_000
                && world.player(0).get(UnitType.Resource.OIL) == 0; cycle++) {
            tickWithOilInvariant(world, boat);
        }
        assertEquals(125, world.player(0).get(UnitType.Resource.OIL),
                "the automatically started trip never reached the refinery");
        assertTrue(boat.removed());
        assertSame(depot, boat.worksite());
    }

    @Test
    @DisplayName("a restored action-24 tanker rejoins its order and route lattice")
    void restoredReturnSubstateCannotRemainSplit() {
        World world = new World(sea());
        Unit depot = world.createUnit(refinery(), 0, 16, 19);
        Unit boat = world.createUnit(tanker(), 0, 23, 4);
        boat.setOrder(Unit.Order.STILL);
        boat.setCarrying(UnitType.Resource.OIL);
        boat.setHeldResource(UnitType.Resource.OIL);
        boat.setCarried(100);
        boat.setReturningToDepot(false);
        boat.setBattleNetOilAction(Unit.BattleNetOilAction.TO_DEPOT);

        world.repairRestoredOilOrders();

        assertEquals(Unit.Order.HARVEST, boat.order());
        assertTrue(boat.returningToDepot());
        assertEquals(0, (boat.tileX() | boat.tileY()) & 1,
                "the restored tanker remained outside the doubled naval lattice");
        for (int cycle = 0; cycle < 3_000
                && world.player(0).get(UnitType.Resource.OIL) == 0; cycle++) {
            tickWithOilInvariant(world, boat);
        }
        assertEquals(100, world.player(0).get(UnitType.Resource.OIL));
        assertTrue(boat.removed());
        assertSame(depot, boat.worksite());
    }

    @Test
    @DisplayName("a real Stop destroys the native oil action with its resource order")
    void explicitStopDoesNotLookLikeAnOrphanedSave() {
        World world = new World(sea());
        Unit boat = world.createUnit(tanker(), 0, 10, 10);
        boat.setOrder(Unit.Order.HARVEST);
        boat.setCarrying(UnitType.Resource.OIL);
        boat.setHeldResource(UnitType.Resource.OIL);
        boat.setCarried(100);
        boat.setReturningToDepot(true);
        boat.setBattleNetOilAction(Unit.BattleNetOilAction.TO_DEPOT);

        world.orderStop(boat);

        assertEquals(Unit.Order.STILL, boat.order());
        assertSame(Unit.BattleNetOilAction.IDLE, boat.battleNetOilAction());
        assertFalse(boat.returningToDepot());
    }
}
