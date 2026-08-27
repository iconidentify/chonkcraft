package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.engine.ai.AiPlayer;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.ResourceInfo;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A tanker sent to an oil platform has to come back with the oil, and the
 * oil has to be worth what the refinery says it is.
 *
 * <p>The trip is the sea's copy of the peon's: into the platform, the long
 * wait, out by the face pointing home, and the load banked on arrival --
 * every leg of it {@code COrder_Resource}. Several legs fail in ways land
 * trips cannot. The way home is asked of {@code FindDeposit}, whose travel
 * answer comes from {@code PlaceReachable};
 * a contained tanker is represented at a square under the platform, so
 * upstream seeds that ask from free squares around its container. The tanker
 * itself is two tiles square and advances on a doubled anchor lattice, so
 * both route generation and the final building-skirt arrival must reason
 * about the wide hull rather than a one-tile worker.
 *
 * <p>On campaigns/human-exp/levelx03h both failures stacked: the tanker
 * surfacing at cycle 215 was told no road to a refinery six squares of open
 * water away, and dropped out by the wrong face aimed at a shipyard
 * sixty-nine squares west. The trip's last number is the refinery's own:
 * {@code ImproveProduction} pays oil at 125 the moment the load lands
 *
 */
class TankerRoundTripTest {

    /** Open water for the complete synthetic pump-and-bank loop. */
    private static GameMap sea() {
        GameMap map = new GameMap(24, 24, new Tileset());
        for (int y = 0; y < 24; y++) {
            for (int x = 0; x < 24; x++) {
                map.field(x, y).setFlags(TileFlag.WATER_ALLOWED);
            }
        }
        return map;
    }

    @Test
    @DisplayName("a tanker can leave an oppositely aligned three-tile depot")
    void aTankerLeavesAnEvenAnchorShipyardAfterBankingOil() {
        World world = new World(sea());
        // A minimally usable script.bin switches the resource dropout onto
        // the authenticated BNE placement path without making this synthetic
        // geometry test depend on an installed asset pack.
        byte[] script = new byte[9];
        script[0] = 2;
        script[6] = 8;
        world.setBattleNetSequenceData(script);
        Unit depot = world.createUnit(refinery(), 0, 10, 10);
        Unit rig = world.createUnit(platform(), 15, 18, 4);
        Unit boat = world.createUnit(tanker(), 0, 4, 10);
        assertNotNull(depot);
        assertNotNull(rig);
        assertNotNull(boat);
        rig.setResourcesHeld(25_000);
        world.restoreContained(boat, depot, false, Unit.Order.HARVEST);
        world.restoreHarvestState(boat, rig, rig.tileX(), rig.tileY(), true, 0);
        boat.setCarrying(UnitType.Resource.OIL);
        boat.setBattleNetOilAction(Unit.BattleNetOilAction.TO_DEPOT);

        world.harvest.leaveDepot(boat,
                boat.type().gathering().get(UnitType.Resource.OIL));

        assertTrue(boat.isOnMap(),
                "the free tanker remained invisibly contained in the depot");
        assertEquals(0, boat.tileX() & 1,
                "the fallback must preserve the native absolute-even ship grid");
        assertEquals(0, boat.tileY() & 1,
                "the fallback must preserve the native absolute-even ship grid");
        assertTrue(boat.battleNetDoubleStep(),
                "an aligned fallback should keep normal doubled ship movement");
    }

    @Test
    @DisplayName("a tanker surfaced on an odd anchor can finish an odd-tile move")
    void oddAnchorTankerDoesNotOscillateAcrossItsRoundedGoal() {
        GameMap map = new GameMap(64, 64, new Tileset());
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                map.field(x, y).setFlags(TileFlag.WATER_ALLOWED);
            }
        }
        World world = new World(map);
        Unit boat = world.createUnit(tanker(), 0, 47, 14);
        assertTrue(boat.battleNetDoubleStep(), "a 2x2 tanker carries the native stride bit");
        assertTrue(world.orderMove(boat, 53, 18));
        assertEquals(1, world.battleNetMovementStride(boat),
                "the legacy odd anchor needs a single-lattice recovery order");

        for (int cycle = 0; cycle < 800 && boat.order() != Unit.Order.STILL; cycle++) {
            world.tick();
        }

        assertEquals(Unit.Order.STILL, boat.order(),
                "the tanker kept crossing the rounded goal instead of finishing");
        assertEquals(53, boat.tileX());
        assertEquals(18, boat.tileY());
    }

    @Test
    @DisplayName("the captured mid-step tanker save settles instead of reversing forever")
    void capturedMidStepSelfGoalSettlesWithoutAnotherPlayerCommand() {
        GameMap map = new GameMap(64, 64, new Tileset());
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                map.field(x, y).setFlags(TileFlag.WATER_ALLOWED);
            }
        }
        World world = new World(map);
        Unit boat = world.createUnit(retailTanker(), 0, 53, 18);
        boat.setOrder(Unit.Order.MOVE);
        boat.setHeading(2);
        boat.setOffset(-36, 0);
        boat.setPathGoal(53, 18);
        boat.setOrderTarget(53, 18);
        boat.setWalkHolding(true);
        boat.setCarrying(UnitType.Resource.OIL);
        boat.setHeldResource(UnitType.Resource.OIL);
        boat.setCarried(100);
        boat.setBattleNetOilAction(Unit.BattleNetOilAction.TO_DEPOT);
        boat.setBattleNetOilStartedAdjacent(true);

        int reversals = 0;
        int previousHeading = boat.heading();
        for (int cycle = 0; cycle < 800 && boat.order() != Unit.Order.STILL; cycle++) {
            world.tick();
            if (boat.heading() != previousHeading) {
                reversals++;
                previousHeading = boat.heading();
            }
        }

        assertEquals(Unit.Order.STILL, boat.order(),
                "the captured self-goal remained an active Move order");
        assertEquals(53, boat.tileX());
        assertEquals(18, boat.tileY());
        assertEquals(0, boat.offsetX());
        assertEquals(0, boat.offsetY());
        assertTrue(reversals <= 1,
                "the tanker kept reversing across its own saved goal");
    }

    @Test
    @DisplayName("raw oil action 24 repairs a missing Java return projection and docks")
    void nativeToDepotStateIsEnoughToFinishTheUserSaveGeometry() {
        GameMap map = new GameMap(64, 64, new Tileset());
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                map.field(x, y).setFlags(TileFlag.WATER_ALLOWED);
            }
        }
        World world = new World(map);
        Unit depot = world.createUnit(refinery(), 0, 43, 16);
        Unit rig = world.createUnit(platform(), 15, 49, 11);
        rig.setResourcesHeld(130_000);
        world.recalculateSupply();
        Unit boat = world.createUnit(retailTanker(), 0, 47, 14);
        boat.setOrder(Unit.Order.HARVEST);
        boat.setCarrying(UnitType.Resource.OIL);
        boat.setHeldResource(UnitType.Resource.OIL);
        boat.setCarried(100);
        boat.setResourceUnit(rig);
        boat.setResourceTile(rig.tileX(), rig.tileY());
        boat.setBattleNetOilAction(Unit.BattleNetOilAction.TO_DEPOT);
        boat.setReturningToDepot(false);

        for (int cycle = 0; cycle < 1_200 && boat.isOnMap(); cycle++) {
            world.tick();
        }

        assertTrue(boat.returningToDepot(),
                "raw BNE action 24 did not restore its homeward projection");
        assertFalse(boat.isOnMap(),
                "the full tanker remained parked beside the depot");
        assertSame(depot, boat.worksite());
        assertEquals(125, world.player(0).get(UnitType.Resource.OIL));
        assertEquals(0, boat.carried());
    }

    /**
     * Water only north-west of a platform. The first perimeter coordinate
     * (9,9) is water, but a two-tile tanker notionally placed there overlaps
     * the platform at 10,10; every later perimeter coordinate is land.
     */
    private static GameMap cornerSeedSea() {
        GameMap map = new GameMap(20, 20, new Tileset());
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 20; x++) {
                map.field(x, y).setFlags(
                        x <= 9 && y <= 9 ? TileFlag.WATER_ALLOWED : TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType refinery() {
        UnitType type = new UnitType("unit-orc-refinery");
        type.setTileSize(3, 3);
        type.setHitPoints(600);
        type.setBuilding(true);
        type.stores().add(UnitType.Resource.OIL);
        type.improveProduction().put(UnitType.Resource.OIL, 25);
        return type;
    }

    private static UnitType platform() {
        UnitType type = new UnitType("unit-oil-platform");
        type.setTileSize(3, 3);
        type.setHitPoints(650);
        type.setBuilding(true);
        type.setGivesResource(UnitType.Resource.OIL);
        type.setCanHarvest(true);
        return type;
    }

    /** The enterable, naval-movement building a tanker can be parked over. */
    private static UnitType oilPatch() {
        UnitType type = new UnitType("unit-oil-patch");
        type.setTileSize(3, 3);
        type.setHitPoints(0);
        type.setBuilding(true);
        type.setSeaUnit(true);
        type.setGivesResource(UnitType.Resource.OIL);
        return type;
    }

    private static UnitType tanker() {
        UnitType type = new UnitType("unit-oil-tanker");
        type.setTileSize(2, 2);
        type.setHitPoints(90);
        type.setSpeed(10);
        type.setSeaUnit(true);
        type.setNumDirections(8);
        ResourceInfo oil = new ResourceInfo(UnitType.Resource.OIL);
        oil.setCapacity(100);
        oil.setWaitAtResource(10);
        oil.setWaitAtDepot(45);
        type.gathering().put(UnitType.Resource.OIL, oil);
        return type;
    }

    /** Retail's full oil cadence and cargo artwork contract. */
    private static UnitType retailTanker() {
        UnitType type = tanker();
        ResourceInfo oil = type.gathering().get(UnitType.Resource.OIL);
        oil.setWaitAtResource(150);
        oil.setWaitAtDepot(150);
        oil.setFileWhenLoaded("orc/units/oil_tanker_full.png");
        oil.setFileWhenEmpty("orc/units/oil_tanker_empty.png");
        return type;
    }

    @Test
    @DisplayName("BNE oil actions spend exactly 150 cycles in platform and depot")
    void nativeOilActionsAndBothDwellWindowsAreCycleExact() {
        World world = new World(sea());
        Unit depot = world.createUnit(refinery(), 0, 3, 3);
        Unit rig = world.createUnit(platform(), 15, 14, 14);
        rig.setResourcesHeld(25_000);
        world.recalculateSupply();
        UnitType tankerType = retailTanker();
        Unit boat = world.createUnit(tankerType, 0, 13, 13);
        assertTrue(world.orderHarvest(boat, rig));

        int resourceEntry = -1;
        int resourceExit = -1;
        int depotEntry = -1;
        int depotExit = -1;
        for (int cycle = 1; cycle <= 1_500 && depotExit < 0; cycle++) {
            world.tick();
            if (resourceEntry < 0 && boat.removed() && boat.worksite() == rig) {
                resourceEntry = cycle;
                assertSame(Unit.BattleNetOilAction.INSIDE_RESOURCE,
                        boat.battleNetOilAction());
                assertEquals(26, boat.battleNetOilAction().rawAction());
            } else if (resourceEntry >= 0 && resourceExit < 0 && boat.isOnMap()) {
                resourceExit = cycle;
                assertSame(Unit.BattleNetOilAction.TO_DEPOT,
                        boat.battleNetOilAction());
                assertEquals(24, boat.battleNetOilAction().rawAction());
                assertEquals(100, boat.carried());
                assertEquals("orc/units/oil_tanker_full.png",
                        tankerType.imageFileFor("summer", UnitType.Resource.OIL, true));
            }
            if (resourceExit >= 0 && depotEntry < 0
                    && boat.removed() && boat.worksite() == depot) {
                depotEntry = cycle;
                assertSame(Unit.BattleNetOilAction.TO_DEPOT,
                        boat.battleNetOilAction());
                assertEquals(125, world.player(0).get(UnitType.Resource.OIL),
                        "the refinery bonus must be visible when action 24 docks");
            } else if (depotEntry >= 0 && boat.isOnMap()
                    && boat.battleNetOilAction() == Unit.BattleNetOilAction.TO_RESOURCE) {
                depotExit = cycle;
            }
        }

        assertTrue(resourceEntry > 0, "the tanker never entered raw action 26");
        assertEquals(150, resourceExit - resourceEntry,
                "native capture is removed in the platform for exactly 150 cycles");
        assertEquals(150, depotExit - depotEntry,
                "native capture is removed in the depot for exactly 150 cycles");
        assertEquals(24_900, rig.resourcesHeld());
        assertEquals(0, boat.carried());
        assertEquals("orc/units/oil_tanker_empty.png",
                tankerType.imageFileFor("summer", UnitType.Resource.OIL, false));
    }

    @Test
    @DisplayName("the oil comes home, and the refinery pays a quarter over")
    void theLoadComesHomeAndPaysTheRefinerysRate() {
        World world = new World(sea());
        world.createUnit(refinery(), 0, 3, 3);
        Unit rig = world.createUnit(platform(), 15, 14, 14);
        rig.setResourcesHeld(25000);
        world.recalculateSupply();
        Unit boat = world.createUnit(tanker(), 0, 13, 13);
        assertTrue(world.orderHarvest(boat, 14, 14), "the fixture could not even begin");

        int ticks = 0;
        boolean wentIn = false;
        boolean surfacedWalking = false;
        while (ticks++ < 400
                && !(world.player(0).get(UnitType.Resource.OIL) > 0)) {
            boolean wasIn = wentIn && !boat.isOnMap();
            world.tick();
            wentIn |= !boat.isOnMap() && boat.worksite() == rig;
            if (wasIn && boat.isOnMap() && !surfacedWalking) {
                // The surfacing cycle already knows the way home and is
                // already sailing it: FindDeposit answered from the squares
                // around the platform, and Execute fell through into the
                // walk. A tanker that surfaces standing still was told
                // there was no road.
                surfacedWalking = boat.isMoving() || boat.pathLength() > 0;
            }
        }
        assertTrue(wentIn, "the tanker never went into the platform");
        assertTrue(surfacedWalking,
                "the tanker surfaced without a course home; the ask from inside the"
                        + " platform must be seeded from the squares around it"
                        + " because its recorded corner is"
                        + " under the occupied platform");
        assertEquals(125, world.player(0).get(UnitType.Resource.OIL),
                "a hundred oil banked at the refinery should read 125 in the ledger."
                        + " Three things stand between the platform and that number: the"
                        + " way home is FindDeposit's travel answer, seeded from the"
                        + " squares around the platform; the sealed-goal shortcut has"
                        + " to scan corner"
                        + " positions a two-tile hull can touch the ring from; and the"
                        + " bank pays Incomes per hundred, which the refinery's"
                        + " ImproveProduction raised");
        assertFalse(boat.isOnMap(),
                "and the tanker is inside the refinery for the unload wait, as the"
                        + " worker is inside the hall");
    }

    @Test
    @DisplayName("a contained tanker's reachability starts at a mask-passable corner")
    void theContainerPerimeterSeedTestsOneTileRatherThanTheWholeHull() {
        GameMap map = cornerSeedSea();
        World world = new World(map);
        Unit depot = world.createUnit(refinery(), 0, 2, 2);
        Unit rig = world.createUnit(platform(), 15, 10, 10);
        rig.setResourcesHeld(25000);
        Unit boat = world.createUnit(tanker(), 0, 8, 8);
        world.markOccupancy(boat, false);
        boat.setWorksite(rig);
        boat.setRemoved(true);
        boat.setTile(rig.tileX(), rig.tileY());

        assertTrue(world.unitReachableTravel(boat, depot, 1) > 0,
                "PlaceReachable tests CanMoveToMask only at each candidate top-left"
                        + " coordinate. Requiring the tanker's whole 2x2 footprint to fit"
                        + " at 9,9 rejects the only water seed because it overlaps the"
                        + " container, and falsely reports that no refinery is reachable");
    }

    @Test
    @DisplayName("an empty retail route keeps the resource order active")
    void anEmptyRetailRouteDoesNotEndTheResourceOrderImmediately() {
        GameMap map = new GameMap(20, 20, new Tileset());
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 20; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        UnitType depotType = refinery();
        depotType.setSightRange(4);
        Unit depot = world.createUnit(depotType, 0, 3, 3);
        Unit boat = world.createUnit(tanker(), 0, 15, 10);
        map.field(15, 10).setFlags(TileFlag.WATER_ALLOWED);
        boat.setCarrying(UnitType.Resource.OIL);
        boat.setHeldResource(UnitType.Resource.OIL);
        boat.setCarried(100);
        boat.setResourceDepot(depot);
        boat.setReturnDepotGoal(depot);
        boat.setReturningToDepot(true);
        boat.setOrder(Unit.Order.HARVEST);

        world.harvest.stepHarvest(boat);

        assertEquals(Unit.Order.HARVEST, boat.order(),
                "BNE preserves an all-0xff route as an active empty route; it does"
                        + " not replace COrder_Resource with Still on the first"
                        + " failed route draw");
        assertEquals(0, boat.pathLength(),
                "both failed wall faces leave the retail route buffer empty");
        assertEquals(0, boat.resourceUnreachableTries(),
                "an empty retail route is distinct from LegacyEngine PF_UNREACHABLE");
    }

    @Test
    @DisplayName("a tanker over an oil patch rejects the depot but can sail away")
    void aMooredTankersReachabilityAndMovementSeeDifferentFieldFlags() {
        GameMap map = new GameMap(32, 32, new Tileset());
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                map.field(x, y).setFlags(TileFlag.WATER_ALLOWED);
            }
        }
        World world = new World(map);
        // Creation order is cache order. The patch is therefore the first
        // naval-movement entry on every square it shares with the tanker.
        world.createUnit(oilPatch(), 15, 12, 14);
        Unit depot = world.createUnit(refinery(), 0, 4, 14);
        Unit rig = world.createUnit(platform(), 15, 20, 14);
        rig.setResourcesHeld(25_000);
        Unit boat = world.createUnit(tanker(), 0, 12, 14);

        assertSame(rig, world.findResourceUnit(boat, UnitType.Resource.OIL, 8),
                "UnitReachable must leave the tanker's SeaUnit flag marked: the"
                        + " first same-movement cache entry is then the oil patch, so"
                        + " the nearby depot is rejected and the resource flood starts"
                        + " at the tanker. Starting at the depot cannot see this rig"
                        + " inside the eight-square bound");
        assertTrue(world.orderHarvest(boat, rig.tileX(), rig.tileY()));
        world.tick();

        assertTrue(boat.pathLength() > 0 || boat.isMoving()
                        || boat.tileX() != 12 || boat.tileY() != 14,
                "DoActionMove temporarily removes the tanker's own SeaUnit field bit."
                        + " With no other unit setting that bit, the oil patch below it"
                        + " is not consulted and cannot trap the tanker on its mooring");
        assertTrue(depot.isAlive(), "the nearby depot is part of the reachability fixture");
    }

    @Test
    @DisplayName("an AI tanker with no mine searches around another depot first")
    void aMineLessAiTankerUsesASuitableAlternativeDepotAsItsSearchCentre() {
        GameMap map = new GameMap(24, 24, new Tileset());
        for (int y = 0; y < 24; y++) {
            for (int x = 0; x < 24; x++) {
                map.field(x, y).setFlags(TileFlag.WATER_ALLOWED);
            }
        }
        World world = new World(map);
        world.ais().put(0, new AiPlayer(0));
        Unit alternativeDepot = world.createUnit(refinery(), 0, 2, 10);
        Unit oldDepot = world.createUnit(refinery(), 0, 12, 10);
        Unit alternativeMine = world.createUnit(platform(), 0, 2, 16);
        alternativeMine.setResourcesHeld(25000);
        Unit oldDepotMine = world.createUnit(platform(), 0, 17, 10);
        oldDepotMine.setResourcesHeld(25000);
        Unit boat = world.createUnit(tanker(), 0, 12, 10);
        world.restoreContained(boat, oldDepot, false, Unit.Order.HARVEST);
        world.restoreHarvestState(boat, null, -1, -1, true, 0);
        boat.setCarrying(UnitType.Resource.OIL);
        boat.setResourceDepot(oldDepot);

        world.tick();

        assertTrue(boat.isOnMap(), "the completed depot wait should drop the tanker out");
        assertSame(alternativeMine, boat.resourceUnit(),
                "AiGetSuitableDepot considers the other depot before WaitInDepot's"
                        + " ordinary search around the depot the tanker is inside");
        assertTrue(alternativeDepot.distanceTo(alternativeMine)
                        < oldDepot.distanceTo(alternativeMine),
                "the fixture must make the selected mine belong to the alternative depot");
        assertTrue(oldDepot.distanceTo(oldDepotMine)
                        < alternativeDepot.distanceTo(oldDepotMine),
                "without the alternative-depot pass, the ordinary fallback would choose"
                        + " the other platform near the old depot");
    }
}
