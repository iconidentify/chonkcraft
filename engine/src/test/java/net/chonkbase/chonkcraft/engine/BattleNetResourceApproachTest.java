package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.pathfinder.PathFinder;
import net.chonkbase.chonkcraft.engine.unit.ResourceInfo;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Regression coverage for BNE's resource-arrival substates. */
class BattleNetResourceApproachTest {

    private static UnitType platform() {
        UnitType type = new UnitType("unit-human-oil-platform");
        type.setTileSize(3, 3);
        type.setHitPoints(650);
        type.setBuilding(true);
        type.setGivesResource(UnitType.Resource.OIL);
        type.setCanHarvest(true);
        return type;
    }

    private static UnitType tanker() {
        UnitType type = new UnitType("unit-human-oil-tanker");
        type.setTileSize(2, 2);
        type.setHitPoints(90);
        type.setSpeed(10);
        type.setSeaUnit(true);
        ResourceInfo oil = new ResourceInfo(UnitType.Resource.OIL);
        oil.setCapacity(100);
        oil.setWaitAtResource(150);
        oil.setWaitAtDepot(150);
        type.gathering().put(UnitType.Resource.OIL, oil);
        return type;
    }

    private static UnitType woodcutter() {
        UnitType type = new UnitType("unit-peon");
        type.setTileSize(1, 1);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        ResourceInfo wood = new ResourceInfo(UnitType.Resource.WOOD);
        wood.setCapacity(100);
        wood.setWaitAtResource(25);
        wood.setStep(10);
        wood.setTerrainHarvester(true);
        type.gathering().put(UnitType.Resource.WOOD, wood);
        AnimationSet animations = new AnimationSet("unit-peon");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", java.util.List.of("frame 0", "wait 5")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", java.util.List.of(
                        "frame 0", "move 8", "wait 1", "frame 0", "wait 1")));
        animations.put(AnimationSet.State.HARVEST,
                Animation.parse("harvest", java.util.List.of(
                        "frame 0", "wait 5", "frame 0", "wait 5")));
        type.setAnimationSet(animations);
        return type;
    }

    @Test
    @DisplayName("BNE stages an adjacent tanker for three calls before entering")
    void adjacentTankerUsesNativeApproachState() {
        GameMap map = new GameMap(20, 20, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.WATER_ALLOWED);
            }
        }
        World world = new World(map);
        Unit rig = world.createUnit(platform(), 15, 5, 5);
        rig.setResourcesHeld(25_000);
        Unit boat = world.createUnit(tanker(), 0, 8, 4);
        assertTrue(world.orderHarvest(boat, rig));
        assertEquals(Unit.BattleNetOilAction.FINAL_APPROACH,
                boat.battleNetOilAction());
        assertEquals(25, boat.battleNetOilAction().rawAction());

        for (int call = 0; call < 3; call++) {
            world.tick();
            assertTrue(boat.isOnMap(), "action 25 must remain visible through call " + call);
            assertEquals(Unit.BattleNetOilAction.FINAL_APPROACH,
                    boat.battleNetOilAction());
            assertEquals(8, boat.tileX());
            assertEquals(4, boat.tileY());
        }

        world.tick();
        assertFalse(boat.isOnMap());
        assertEquals(Unit.BattleNetOilAction.INSIDE_RESOURCE,
                boat.battleNetOilAction());
        assertEquals(26, boat.battleNetOilAction().rawAction());
        assertEquals(rig, boat.worksite());
        assertEquals(5, boat.tileX());
        assertEquals(5, boat.tileY());
    }

    @Test
    @DisplayName("a board-seat tanker holds cover before entering the platform")
    void boardSeatTankerHoldsCoverBeforeEnter() {
        // Orc 14 tanker 1565: starts at 8,6, walks to 6,6 next to approach
        // 5,5 of the platform at 3,3 -- one Chebyshev step off the entry, so
        // the 2x2 hull never covers the approach point. Native stays HARVEST
        // at 6,6 for thirty-two seats, stages BOARD for three, then UNLOAD.
        // Java used to keep walking toward 5,5 and never board. The order
        // must start from outside distance 1 so the walk-in cover path is
        // used rather than the started-adjacent action-25 arm.
        GameMap map = new GameMap(20, 20, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.WATER_ALLOWED);
            }
        }
        World world = new World(map);
        Unit rig = world.createUnit(platform(), 15, 3, 3);
        rig.setResourcesHeld(25_000);
        Unit boat = world.createUnit(tanker(), 0, 8, 6);
        assertTrue(world.orderHarvest(boat, rig),
                "the board-seat tanker must accept the harvest order");

        int seatVisible = 0;
        int action23Visible = 0;
        int action25Visible = 0;
        boolean sawSeat = false;
        for (int call = 0; call < 80; call++) {
            world.tick();
            if (!boat.isOnMap()) {
                break;
            }
            if (boat.tileX() == 6 && boat.tileY() == 6) {
                sawSeat = true;
                seatVisible++;
                if (boat.battleNetOilAction() == Unit.BattleNetOilAction.TO_RESOURCE) {
                    action23Visible++;
                } else if (boat.battleNetOilAction()
                        == Unit.BattleNetOilAction.FINAL_APPROACH) {
                    action25Visible++;
                }
            } else if (sawSeat) {
                // Left the board seat without entering -- residual path
                // walked it toward the approach point.
                break;
            }
        }
        assertTrue(sawSeat, "the tanker must reach the 6,6 board seat");
        assertEquals(32, action23Visible,
                "native remains in action 23 at the board seat for 32 cycles");
        assertEquals(3, action25Visible,
                "native exposes exactly three action-25 BOARD cycles");
        assertEquals(35, seatVisible,
                "the board seat is the 32-cycle action-23 hold plus three BOARD cycles");
        assertFalse(boat.isOnMap(),
                "after cover and the three BOARD seats the tanker must enter");
        assertEquals(rig, boat.worksite(),
                "the tanker must be inside the platform it boarded");
    }

    @Test
    @DisplayName("a distant tanker stays visible on first platform cover")
    void distantTankerDoesNotEnterOnTheLandingCall() {
        // XOrc 8: tanker at (112,52) bound for platform (115,53). After the
        // first double-step it covers the entry point at (114,52) but native
        // remains HARVEST-visible there; entering on that call was the map's
        // cycle-5 divergence (removed True vs False).
        GameMap map = new GameMap(130, 130, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.WATER_ALLOWED);
            }
        }
        World world = new World(map);
        Unit rig = world.createUnit(platform(), 15, 115, 53);
        rig.setResourcesHeld(25_000);
        Unit boat = world.createUnit(tanker(), 0, 112, 52);
        assertTrue(world.orderHarvest(boat, rig));

        boolean sawCover = false;
        for (int call = 0; call < 20; call++) {
            world.tick();
            if (boat.tileX() == 114 && boat.tileY() == 52) {
                sawCover = true;
                assertTrue(boat.isOnMap(),
                        "first cover of the platform entry must stay visible");
                break;
            }
        }
        assertTrue(sawCover, "the tanker must reach the (114,52) approach tile");
    }

    @Test
    @DisplayName("an adjacent gold peasant steps onto the mine approach point")
    void adjacentGoldPeasantStepsOntoApproachPoint() {
        // XOrc 12 geometry, shifted into a compact map: peasant at (2,5)
        // harvesting the mine at (3,2). The exact approach point is (3,4) on
        // the mine's southern face -- the same offsets as native (32,75) to
        // (33,74). Leaving the mine's movement flags up during the resource
        // path search blocked that first Bresenham step and detoured north.
        GameMap map = new GameMap(20, 20, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        UnitType mineType = new UnitType("unit-gold-mine");
        mineType.setTileSize(3, 3);
        mineType.setHitPoints(25_500);
        mineType.setBuilding(true);
        mineType.setLandUnit(true);
        mineType.setGivesResource(UnitType.Resource.GOLD);
        mineType.setCanHarvest(true);
        Unit mine = world.createUnit(mineType, World.NEUTRAL_PLAYER, 3, 2);
        assertTrue(mine != null, "the gold mine must place");
        mine.setResourcesHeld(25_500);
        UnitType peasantType = new UnitType("unit-peasant");
        peasantType.setTileSize(1, 1);
        peasantType.setHitPoints(30);
        peasantType.setSpeed(10);
        peasantType.setLandUnit(true);
        ResourceInfo gold = new ResourceInfo(UnitType.Resource.GOLD);
        gold.setCapacity(100);
        gold.setWaitAtResource(150);
        gold.setWaitAtDepot(100);
        peasantType.gathering().put(UnitType.Resource.GOLD, gold);
        Unit peasant = world.createUnit(peasantType, 0, 2, 5);
        assertTrue(peasant != null, "the peasant must place");
        assertTrue(world.orderHarvest(peasant, mine),
                "the gold order must be accepted");

        boolean stepped = false;
        for (int call = 0; call < 12; call++) {
            world.tick();
            if (peasant.tileX() == 3 && peasant.tileY() == 4) {
                stepped = true;
                break;
            }
            if (!peasant.isOnMap()) {
                // Entered without lingering on the approach tile: still the
                // approach path reached the footprint.
                stepped = true;
                break;
            }
        }
        assertTrue(stepped,
                "the peasant must reach the mine's (3,4) approach square");
    }

    @Test
    @DisplayName("Orc 12 peon south of the mine left column steps north-east onto the footprint")
    void orcTwelvePeonSouthOfMineLeftColumnStepsNortheastNotNorth() {
        // retail-orc-12-idle peon 1511 at (58,47) harvests gold mine (58,44).
        // Approach point is (58,46) on the SW corner; forcing pure north onto
        // that blocked cell was the cycle-8 divergence. Native steps
        // north-east onto (59,46) toward the mine centre.
        GameMap map = new GameMap(20, 20, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        UnitType mineType = new UnitType("unit-gold-mine");
        mineType.setTileSize(3, 3);
        mineType.setHitPoints(25_500);
        mineType.setBuilding(true);
        mineType.setLandUnit(true);
        mineType.setGivesResource(UnitType.Resource.GOLD);
        mineType.setCanHarvest(true);
        Unit mine = world.createUnit(mineType, World.NEUTRAL_PLAYER, 5, 2);
        assertTrue(mine != null, "the gold mine must place");
        mine.setResourcesHeld(25_500);
        UnitType peonType = new UnitType("unit-peon");
        peonType.setTileSize(1, 1);
        peonType.setHitPoints(30);
        peonType.setSpeed(10);
        peonType.setLandUnit(true);
        ResourceInfo gold = new ResourceInfo(UnitType.Resource.GOLD);
        gold.setCapacity(100);
        gold.setWaitAtResource(150);
        gold.setWaitAtDepot(100);
        peonType.gathering().put(UnitType.Resource.GOLD, gold);
        // South of the mine's left column: mine at (5,2) covers x5-7 y2-4;
        // peon at (5,5) mirrors 58,47 south of 58,44.
        Unit peon = world.createUnit(peonType, 0, 5, 5);
        assertTrue(peon != null, "the peon must place");
        assertTrue(world.orderHarvest(peon, mine),
                "the gold order must be accepted");

        boolean steppedNortheast = false;
        for (int call = 0; call < 16; call++) {
            world.tick();
            if (peon.tileX() == 6 && peon.tileY() == 4) {
                steppedNortheast = true;
                break;
            }
            if (peon.tileX() == 5 && peon.tileY() == 4) {
                break;
            }
        }
        assertTrue(steppedNortheast,
                "peon must step north-east onto (6,4), not pure north onto (5,4)");
    }

    @Test
    @DisplayName("a long gold approach stages the mine-centre diagonal when the pure-cardinal approach is blocked")
    void longGoldApproachStagesMineCentreDiagonalNotPureEast() {
        // retail-xorc-12-idle peasant 1394 at (32,74) harvests mine (33,72).
        // Approach is the blocked SW corner (33,74). The first path segment
        // lands the worker one tile west with a free wrong leftover north;
        // action-25 must stage north-east onto (33,73) toward the mine centre
        // rather than pure east onto the approach corner (Java's c23 miss).
        GameMap map = new GameMap(20, 20, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        UnitType mineType = new UnitType("unit-gold-mine");
        mineType.setTileSize(3, 3);
        mineType.setHitPoints(25_500);
        mineType.setBuilding(true);
        mineType.setLandUnit(true);
        mineType.setGivesResource(UnitType.Resource.GOLD);
        mineType.setCanHarvest(true);
        // Mine top-left (5,2) covers x5-7 y2-4; approach for a worker at
        // (4,4) is the SW corner (5,4), matching 33,74 beside 33,72.
        Unit mine = world.createUnit(mineType, World.NEUTRAL_PLAYER, 5, 2);
        assertTrue(mine != null, "the gold mine must place");
        mine.setResourcesHeld(25_500);
        UnitType peasantType = new UnitType("unit-peasant");
        peasantType.setTileSize(1, 1);
        peasantType.setHitPoints(30);
        peasantType.setSpeed(10);
        peasantType.setLandUnit(true);
        ResourceInfo gold = new ResourceInfo(UnitType.Resource.GOLD);
        gold.setCapacity(100);
        gold.setWaitAtResource(150);
        gold.setWaitAtDepot(100);
        peasantType.gathering().put(UnitType.Resource.GOLD, gold);
        // Two tiles south-west of the approach so the opening path is longer
        // than one heading and arms battleNetGoldLongApproach.
        Unit peasant = world.createUnit(peasantType, 0, 3, 5);
        assertTrue(peasant != null, "the peasant must place");
        assertTrue(world.orderHarvest(peasant, mine),
                "the gold order must be accepted");

        boolean sawMineCentre = false;
        boolean sawPureEastApproach = false;
        for (int call = 0; call < 40; call++) {
            world.tick();
            // After the opening segment the worker sits at (4,4). Native then
            // stages action 25 and steps north-east onto (5,3) -- the left
            // face toward the mine centre -- not pure east onto (5,4).
            if (peasant.tileX() == 5 && peasant.tileY() == 3) {
                sawMineCentre = true;
                break;
            }
            if (peasant.tileX() == 5 && peasant.tileY() == 4) {
                sawPureEastApproach = true;
                break;
            }
            if (!peasant.isOnMap()) {
                // Entered without a visible approach step: not the c23 shape.
                break;
            }
        }
        assertTrue(sawMineCentre,
                "long gold approach must stage the mine-centre diagonal onto (5,3), not pure east onto (5,4)");
        assertFalse(sawPureEastApproach,
                "pure east onto the blocked approach corner is the xorc-12 c23 miss");
    }

    @Test
    @DisplayName("the first wood chop under BNE draws one SyncRand")
    void firstWoodChopDrawsOneSyncRand() {
        // Retail 0x423550 seeds unit+0xb from SyncRand when terrain harvest
        // first swings. XOrc 12 peasant 1494 is the corpus witness: wood
        // order, then one seed step (1→0x41c67ea6) with no unit-position
        // change. Gold harvest must not draw.
        GameMap map = new GameMap(16, 16, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        map.field(5, 5).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
        World world = new World(map);
        world.restoreRandom(1, 0);
        UnitType peasantType = new UnitType("unit-peasant");
        peasantType.setTileSize(1, 1);
        peasantType.setHitPoints(30);
        peasantType.setSpeed(10);
        peasantType.setLandUnit(true);
        ResourceInfo wood = new ResourceInfo(UnitType.Resource.WOOD);
        wood.setCapacity(100);
        wood.setWaitAtResource(25);
        wood.setStep(10);
        wood.setTerrainHarvester(true);
        peasantType.gathering().put(UnitType.Resource.WOOD, wood);
        AnimationSet animations = new AnimationSet("unit-peasant");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", java.util.List.of("frame 0", "wait 5")));
        animations.put(AnimationSet.State.HARVEST,
                Animation.parse("harvest", java.util.List.of(
                        "frame 0", "wait 5", "frame 0", "wait 5")));
        peasantType.setAnimationSet(animations);
        Unit peasant = world.createUnit(peasantType, 0, 5, 6);
        assertTrue(peasant != null, "the peasant must place");
        int seedBefore = world.randomSeed();
        assertTrue(world.orderHarvest(peasant, 5, 5),
                "the wood order must be accepted");
        boolean chopped = false;
        for (int call = 0; call < 40; call++) {
            world.tick();
            if (world.randomSeed() != seedBefore) {
                chopped = true;
                break;
            }
        }
        assertTrue(chopped, "the first wood swing must advance SyncRand");
        // Exactly one retail LCG step from the pre-chop seed.
        int expected = seedBefore * 0x41c64e6d + 0x3039;
        assertEquals(expected, world.randomSeed(),
                "the first wood swing draws exactly one SyncRand");
    }

    @Test
    @DisplayName("a standing woodcutter re-seeds SyncRand every harvest animation loop")
    void standingWoodcutterReseedsEveryAnimationLoop() {
        // Retail 0x423550 at work opcode 2660: first swing, then every
        // twenty-five-cycle Harvest_wood loop. Orc 7 peon 1576 draws at
        // fixture 6 and again at 31 while still on the same tree.
        GameMap map = new GameMap(16, 16, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        map.field(5, 5).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
        map.field(5, 5).setValue(100);
        World world = new World(map);
        world.restoreRandom(1, 0);
        UnitType peasantType = new UnitType("unit-peasant");
        peasantType.setTileSize(1, 1);
        peasantType.setHitPoints(30);
        peasantType.setSpeed(10);
        peasantType.setLandUnit(true);
        ResourceInfo wood = new ResourceInfo(UnitType.Resource.WOOD);
        wood.setCapacity(100);
        wood.setWaitAtResource(24);
        wood.setStep(2);
        wood.setTerrainHarvester(true);
        peasantType.gathering().put(UnitType.Resource.WOOD, wood);
        AnimationSet animations = new AnimationSet("unit-peasant");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", java.util.List.of("frame 0", "wait 5")));
        animations.put(AnimationSet.State.HARVEST,
                Animation.parse("harvest", java.util.List.of(
                        "frame 0", "wait 5", "frame 0", "wait 5")));
        peasantType.setAnimationSet(animations);
        Unit peasant = world.createUnit(peasantType, 0, 5, 6);
        assertTrue(peasant != null, "the peasant must place");
        assertTrue(world.orderHarvest(peasant, 5, 5),
                "the wood order must be accepted");
        int seed = world.randomSeed();
        int firstDrawCycle = -1;
        for (int call = 0; call < 40; call++) {
            world.tick();
            if (world.randomSeed() != seed) {
                firstDrawCycle = call + 1;
                seed = world.randomSeed();
                break;
            }
        }
        assertTrue(firstDrawCycle > 0, "the first wood swing must draw SyncRand");
        int secondDrawCycle = -1;
        for (int call = firstDrawCycle; call < firstDrawCycle + 40; call++) {
            world.tick();
            if (world.randomSeed() != seed) {
                secondDrawCycle = call + 1;
                break;
            }
        }
        assertTrue(secondDrawCycle > 0, "the animation loop must draw SyncRand again");
        assertEquals(25, secondDrawCycle - firstDrawCycle,
                "work-swing SyncRand repeats every twenty-five cycles, not WaitAtResource");
    }

    @Test
    @DisplayName("a walk-claim wood start draws work-swing SyncRand three cycles later")
    void walkClaimWoodStartDrawsWorkSwingThreeLater() {
        // Orc 7 peon 1567 after gold free-prefix forest re-aim: claim draw at
        // 2657, work opcode 2660 three cycles later. The free-prefix path
        // arms battleNetWoodWalkClaim; this pins the +3 arm that standing
        // starts must not take.
        GameMap map = new GameMap(16, 16, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        map.field(5, 5).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
        map.field(5, 5).setValue(100);
        World world = new World(map);
        world.restoreRandom(1, 0);
        UnitType peasantType = new UnitType("unit-peasant");
        peasantType.setTileSize(1, 1);
        peasantType.setHitPoints(30);
        peasantType.setSpeed(10);
        peasantType.setLandUnit(true);
        ResourceInfo wood = new ResourceInfo(UnitType.Resource.WOOD);
        wood.setCapacity(100);
        wood.setWaitAtResource(24);
        wood.setStep(2);
        wood.setTerrainHarvester(true);
        peasantType.gathering().put(UnitType.Resource.WOOD, wood);
        AnimationSet animations = new AnimationSet("unit-peasant");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", java.util.List.of("frame 0", "wait 5")));
        animations.put(AnimationSet.State.HARVEST,
                Animation.parse("harvest", java.util.List.of(
                        "frame 0", "wait 5", "frame 0", "wait 5")));
        peasantType.setAnimationSet(animations);
        Unit peasant = world.createUnit(peasantType, 0, 5, 6);
        assertTrue(peasant != null, "the peasant must place");
        assertTrue(world.orderHarvest(peasant, 5, 5),
                "the wood order must be accepted");
        // Simulate free-prefix re-aim: order is adjacent but the claim arm
        // is set so the first swing is staged three cycles after StartGathering.
        peasant.setBattleNetWoodWalkClaim(true);
        int seed = world.randomSeed();
        world.tick();
        assertNotEquals(seed, world.randomSeed(),
                "claim StartGathering must draw SyncRand");
        seed = world.randomSeed();
        int workAt = -1;
        for (int call = 0; call < 10; call++) {
            world.tick();
            if (world.randomSeed() != seed) {
                workAt = call + 1;
                break;
            }
        }
        assertEquals(3, workAt,
                "work swing SyncRand follows three cycles after the claim draw");
    }

    @Test
    @DisplayName("a woodcutter beside its tree starts chopping without consuming leftover route")
    void rangeOneTerrainArrivalDiscardsLeftoverRoute() {
        // XHuman 2 slot 1588: adjacent with route still holding a north
        // heading. Native range-one PF_REACHED clears the cache and swings
        // in place; consuming the leftover moved Java one tile further and
        // delayed the first SyncRand draw past cycle 19.
        GameMap map = new GameMap(16, 16, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        map.field(6, 5).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
        World world = new World(map);
        world.restoreRandom(1, 0);
        UnitType peasantType = new UnitType("unit-peasant");
        peasantType.setTileSize(1, 1);
        peasantType.setHitPoints(30);
        peasantType.setSpeed(10);
        peasantType.setLandUnit(true);
        ResourceInfo wood = new ResourceInfo(UnitType.Resource.WOOD);
        wood.setCapacity(100);
        wood.setWaitAtResource(25);
        wood.setStep(10);
        wood.setTerrainHarvester(true);
        peasantType.gathering().put(UnitType.Resource.WOOD, wood);
        AnimationSet animations = new AnimationSet("unit-peasant");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", java.util.List.of("frame 0", "wait 5")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", java.util.List.of(
                        "frame 0", "move 8", "wait 1", "frame 0", "wait 1")));
        animations.put(AnimationSet.State.HARVEST,
                Animation.parse("harvest", java.util.List.of(
                        "frame 0", "wait 5", "frame 0", "wait 5")));
        peasantType.setAnimationSet(animations);
        Unit peasant = world.createUnit(peasantType, 0, 5, 5);
        assertTrue(peasant != null, "the peasant must place");
        assertTrue(world.orderHarvest(peasant, 6, 5),
                "the wood order must be accepted");
        // Simulate a finished first step with one leftover heading still
        // cached (the shape native keeps at range one).
        // Heading 0 is north (Direction numbering).
        peasant.setPath(new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Path(
                net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                new int[] {0}));
        peasant.setResourceTile(6, 5);
        int seedBefore = world.randomSeed();
        int xBefore = peasant.tileX();
        int yBefore = peasant.tileY();
        world.tick();
        assertEquals(xBefore, peasant.tileX(),
                "range-one arrival must not consume the leftover heading");
        assertEquals(yBefore, peasant.tileY(),
                "range-one arrival must not step off the stand tile");
        assertEquals(seedBefore * 0x41c64e6d + 0x3039, world.randomSeed(),
                "range-one arrival claims the tree and draws one SyncRand");
    }

    @Test
    @DisplayName("a second woodcutter re-aims when its tree is already claimed")
    void secondWoodcutterReaimsWhenTreeIsClaimed() {
        // XHuman 2 slots 1588 then 1589: first claim draws; second sees -4,
        // installs another tree, keeps the standing tail, and draws nothing.
        GameMap map = new GameMap(16, 16, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        map.field(5, 4).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
        map.field(6, 4).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
        World world = new World(map);
        world.restoreRandom(1, 0);
        UnitType peasantType = new UnitType("unit-peasant");
        peasantType.setTileSize(1, 1);
        peasantType.setHitPoints(30);
        peasantType.setSpeed(10);
        peasantType.setLandUnit(true);
        ResourceInfo wood = new ResourceInfo(UnitType.Resource.WOOD);
        wood.setCapacity(100);
        wood.setWaitAtResource(25);
        wood.setStep(10);
        wood.setTerrainHarvester(true);
        peasantType.gathering().put(UnitType.Resource.WOOD, wood);
        AnimationSet animations = new AnimationSet("unit-peasant");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", java.util.List.of("frame 0", "wait 5")));
        animations.put(AnimationSet.State.HARVEST,
                Animation.parse("harvest", java.util.List.of(
                        "frame 0", "wait 5", "frame 0", "wait 5")));
        peasantType.setAnimationSet(animations);
        Unit first = world.createUnit(peasantType, 0, 5, 5);
        Unit second = world.createUnit(peasantType, 0, 6, 5);
        assertTrue(first != null && second != null, "peasants must place");
        assertTrue(world.orderHarvest(first, 5, 4),
                "first wood order must be accepted");
        assertTrue(world.orderHarvest(second, 5, 4),
                "second wood order must be accepted");
        int seedBefore = world.randomSeed();
        // Process both on the same cycle: the first arrival in pool order
        // claims and draws; the later arrival re-aims without drawing.
        world.tick();
        int expectedOneDraw = seedBefore * 0x41c64e6d + 0x3039;
        assertEquals(expectedOneDraw, world.randomSeed(),
                "only the first claim may draw SyncRand");
        boolean firstKept = first.resourceTileX() == 5 && first.resourceTileY() == 4;
        boolean secondKept = second.resourceTileX() == 5 && second.resourceTileY() == 4;
        boolean firstMoved = first.resourceTileX() == 6 && first.resourceTileY() == 4;
        boolean secondMoved = second.resourceTileX() == 6 && second.resourceTileY() == 4;
        assertTrue((firstKept && secondMoved) || (secondKept && firstMoved),
                "exactly one woodcutter must keep the claimed tree and the other re-aim");
    }

    @Test
    @DisplayName("a moved woodcutter releases its tree for the next peon")
    void replacingHarvestWithMoveReleasesTreeClaim() {
        GameMap map = new GameMap(12, 12, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        map.field(6, 5).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
        World world = new World(map);
        Unit first = world.createUnit(woodcutter(), 0, 5, 5);
        Unit second = world.createUnit(woodcutter(), 0, 6, 6);
        assertTrue(first != null && second != null, "peons must place");
        assertTrue(world.orderHarvest(first, 6, 5));
        world.tick();
        assertTrue(first.gatherClockStarted(), "the first peon must own the tree");

        assertTrue(world.orderMove(first, 2, 5), "the move must replace harvesting");
        assertTrue(world.orderHarvest(second, 6, 5), "the second harvest is valid");
        for (int cycle = 0; cycle < 20 && !second.gatherClockStarted(); cycle++) {
            world.tick();
        }

        assertTrue(second.gatherClockStarted(),
                "the abandoned claim made a valid peon loop beside the tree instead of chopping");
        assertEquals(6, second.resourceTileX());
        assertEquals(5, second.resourceTileY());
    }

    @Test
    @DisplayName("a second peon may stack on a mine approach tile occupied by an ally")
    void aSecondPeonMayStackOnAMineApproachTileOccupiedByAnAlly() {
        // XHuman 8: peons 1571 and 1575 both harvest the 3x3 mine at (15,9).
        // The first reaches approach (17,10); the second must leave (18,9)
        // onto the same approach. Soft-clearing only moving allies left the
        // second peon HARVEST-idle at (18,9) while native stepped to (17,10).
        GameMap map = new GameMap(32, 32, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        UnitType mineType = new UnitType("unit-gold-mine");
        mineType.setTileSize(3, 3);
        mineType.setHitPoints(25_500);
        mineType.setBuilding(true);
        mineType.setGivesResource(UnitType.Resource.GOLD);
        mineType.setCanHarvest(true);
        UnitType peonType = new UnitType("unit-peon");
        peonType.setTileSize(1, 1);
        peonType.setHitPoints(30);
        peonType.setSpeed(10);
        peonType.setLandUnit(true);
        ResourceInfo gold = new ResourceInfo(UnitType.Resource.GOLD);
        gold.setCapacity(100);
        gold.setWaitAtResource(50);
        peonType.gathering().put(UnitType.Resource.GOLD, gold);
        AnimationSet animations = new AnimationSet("unit-peon");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", java.util.List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", java.util.List.of(
                        "frame 0", "move 8", "wait 1")));
        peonType.setAnimationSet(animations);
        Unit mine = world.createUnit(mineType, 15, 15, 9);
        mine.setResourcesHeld(25_500);
        Unit first = world.createUnit(peonType, 0, 18, 10);
        Unit second = world.createUnit(peonType, 0, 18, 9);
        assertTrue(mine != null && first != null && second != null,
                "mine and peons must place");
        assertTrue(world.orderHarvest(first, mine),
                "first peon accepts the gold order");
        assertTrue(world.orderHarvest(second, mine),
                "second peon accepts the gold order");

        boolean stacked = false;
        for (int call = 0; call < 60; call++) {
            world.tick();
            if (first.tileX() == 17 && first.tileY() == 10
                    && second.tileX() == 17 && second.tileY() == 10) {
                stacked = true;
                break;
            }
        }
        assertTrue(stacked,
                "both peons must share approach (17,10) at once, matching XHuman 8 1571/1575");
    }

    @Test
    @DisplayName("a long gold soft-wait free-wakes when the blocked cell clears")
    void aLongGoldSoftWaitFreeWakesWhenTheBlockedCellClears() {
        // XHuman 7 peon 1446 residual-settles at 110,105 and first-refuses
        // SW onto ally 1447 at 109,106 with softDelay 14. Native steps the
        // cycle the ally leaves (fixture 45); a blind fourteen slept until
        // fixture 54. Free-wake clears remaining delay (>6) once the cell
        // is free so the cached heading is taken without waiting out 14.
        GameMap map = new GameMap(20, 40, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        UnitType mineType = new UnitType("unit-gold-mine");
        mineType.setTileSize(3, 3);
        mineType.setHitPoints(25_500);
        mineType.setBuilding(true);
        mineType.setGivesResource(UnitType.Resource.GOLD);
        mineType.setCanHarvest(true);
        UnitType peonType = new UnitType("unit-peon");
        peonType.setTileSize(1, 1);
        peonType.setHitPoints(30);
        peonType.setSpeed(10);
        peonType.setLandUnit(true);
        ResourceInfo gold = new ResourceInfo(UnitType.Resource.GOLD);
        gold.setCapacity(100);
        gold.setWaitAtResource(50);
        peonType.gathering().put(UnitType.Resource.GOLD, gold);
        AnimationSet animations = new AnimationSet("unit-peon");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", java.util.List.of("frame 0", "wait 8")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", java.util.List.of(
                        "unbreakable begin", "frame 0", "move 4", "wait 1",
                        "unbreakable end", "wait 1")));
        peonType.setAnimationSet(animations);
        Unit mine = world.createUnit(mineType, 15, 3, 28);
        mine.setResourcesHeld(25_500);
        Unit blocker = world.createUnit(peonType, 0, 5, 27);
        Unit waiter = world.createUnit(peonType, 0, 6, 26);
        assertTrue(mine != null && blocker != null && waiter != null,
                "mine and peons must place");
        assertTrue(world.orderHarvest(waiter, mine),
                "waiter accepts the gold order");
        blocker.clearPath();
        blocker.setOffset(-16, -16);
        blocker.setWalkHolding(false);
        blocker.animation().switchTo(animations.get(AnimationSet.State.MOVE));
        int sw = net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(-1, 1);
        int se = net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(1, 1);
        // Single free heading after residual replan (pathLength == 1).
        waiter.setPath(new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Path(
                net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                new int[] {sw}));
        waiter.setRouteSpent(false);
        waiter.setOffset(0, 0);
        waiter.setWalkHolding(false);
        waiter.setBattleNetOrderDelay(0);
        waiter.setStepDrained(true);
        waiter.setBattleNetCollisionCounter(0);
        waiter.animation().switchTo(animations.get(AnimationSet.State.MOVE));
        world.actionMoveWalked = true;

        world.movement.stepMove(waiter, true);
        assertEquals(14, waiter.battleNetOrderDelay(),
                "first residual soft-refuse must arm delay 14");
        assertEquals(6, waiter.tileX(), "waiter stays put during the soft-wait");
        assertEquals(26, waiter.tileY(), "waiter stays put during the soft-wait");
        assertTrue(waiter.pathLength() == 1,
                "single free heading must be kept for free-wake");

        world.remove(blocker);
        boolean steppedSouthwest = false;
        int calls = 0;
        for (; calls < 8; calls++) {
            if (waiter.pathLength() == 0 && waiter.battleNetOrderDelay() == 0
                    && waiter.tileX() == 6 && waiter.tileY() == 26) {
                waiter.setPath(new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Path(
                        net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                        new int[] {sw}));
            }
            world.tick();
            if (waiter.tileX() == 5 && waiter.tileY() == 27) {
                steppedSouthwest = true;
                break;
            }
        }
        assertTrue(steppedSouthwest,
                "once the ally leaves, free-wake must take the cached SW step");
        assertTrue(calls < 6,
                "free-wake must not wait out the full fourteen quiet visits; "
                        + "took " + (calls + 1) + " visits after the ally left");
    }

    @Test
    @DisplayName("a near gold soft-refuse uses the short collision wait so the freed tile is taken")
    void aNearGoldSoftRefuseUsesTheShortCollisionWaitSoTheFreedTileIsTaken() {
        // XHuman 12 peon 1553 at (6,26) harvests mine approach (5,28) with
        // cached SW,SE. Ally 1550 holds (5,27) under a Move animation;
        // native soft-refuses SW with the short near-approach collision wait
        // (route_index 20, anim timer 4→1) and steps SW once 1550 leaves.
        // The generic cooperative delay of fourteen quiet visits missed that
        // free window through fixture 30.
        GameMap map = new GameMap(20, 40, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        UnitType mineType = new UnitType("unit-gold-mine");
        mineType.setTileSize(3, 3);
        mineType.setHitPoints(25_500);
        mineType.setBuilding(true);
        mineType.setGivesResource(UnitType.Resource.GOLD);
        mineType.setCanHarvest(true);
        UnitType peonType = new UnitType("unit-peon");
        peonType.setTileSize(1, 1);
        peonType.setHitPoints(30);
        peonType.setSpeed(10);
        peonType.setLandUnit(true);
        ResourceInfo gold = new ResourceInfo(UnitType.Resource.GOLD);
        gold.setCapacity(100);
        gold.setWaitAtResource(50);
        peonType.gathering().put(UnitType.Resource.GOLD, gold);
        AnimationSet animations = new AnimationSet("unit-peon");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", java.util.List.of("frame 0", "wait 8")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", java.util.List.of(
                        "unbreakable begin", "frame 0", "move 4", "wait 8",
                        "unbreakable end", "wait 1")));
        peonType.setAnimationSet(animations);
        Unit mine = world.createUnit(mineType, 15, 3, 28);
        mine.setResourcesHeld(25_500);
        Unit blocker = world.createUnit(peonType, 0, 5, 27);
        Unit waiter = world.createUnit(peonType, 0, 6, 26);
        assertTrue(mine != null && blocker != null && waiter != null,
                "mine and peons must place");
        assertTrue(world.orderHarvest(waiter, mine),
                "waiter accepts the gold order");
        // Cooperative residual: Move animation with no leftover path, so
        // battleNetCooperativeBlocker is true while the solid tile is held.
        blocker.clearPath();
        blocker.setOffset(-16, -16);
        blocker.setWalkHolding(false);
        blocker.animation().switchTo(animations.get(AnimationSet.State.MOVE));
        int sw = net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(-1, 1);
        int se = net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(1, 1);
        // Stack: first consumed is last index (SW then SE). SE from (6,26)
        // does not close Chebyshev to approach (5,28) -- non-progressive.
        waiter.setPath(new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Path(
                net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                new int[] {se, sw}));
        waiter.setRouteSpent(false);
        waiter.setOffset(0, 0);
        waiter.setWalkHolding(false);
        waiter.setBattleNetOrderDelay(0);
        // Prior full cooperative wait already spent (collision 1); the free-
        // window retry is counter 2 and must arm native's six-cycle cadence
        // (anim timer 6→1), not another fourteen.
        waiter.setBattleNetCollisionCounter(1);
        waiter.animation().switchTo(animations.get(AnimationSet.State.STILL));

        world.tick();
        assertEquals(6, waiter.battleNetOrderDelay(),
                "near gold free-window retry must arm delay 6, not another fourteen");
        assertEquals(6, waiter.tileX(), "waiter stays put during the soft-wait");
        assertEquals(26, waiter.tileY(), "waiter stays put during the soft-wait");
        assertTrue(waiter.pathLength() >= 1,
                "the cached SW heading must be kept for the free window");

        world.remove(blocker);
        boolean steppedSouthwest = false;
        for (int call = 0; call < 12; call++) {
            // Keep the waiter on its cached route while the short delay burns
            // down; harvest must not replan a detour around the free tile.
            if (waiter.pathLength() == 0 && waiter.battleNetOrderDelay() == 0
                    && waiter.tileX() == 6 && waiter.tileY() == 26) {
                waiter.setPath(new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Path(
                        net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                        new int[] {se, sw}));
            }
            world.tick();
            if (waiter.tileX() == 5 && waiter.tileY() == 27) {
                steppedSouthwest = true;
                break;
            }
        }
        assertTrue(steppedSouthwest,
                "once the ally leaves (5,27) the peon must take the cached SW step onto that tile");
    }

    @Test
    @DisplayName("a gold peon jammed by a standing ally takes a free closer detour")
    void aGoldPeonJammedByAStandingAllyTakesAFreeCloserDetour() {
        // XOrc 2 peon 1563 at (86,35) holds leftover S into ally 1561 on
        // (86,36) toward mine approach (93,50). Free SW onto (85,36) closes
        // Chebyshev; native steps there at fixture 25. Soft-waiting the jammed
        // S left Java on (86,35) through the sealed window.
        GameMap map = new GameMap(100, 60, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        UnitType mineType = new UnitType("unit-gold-mine");
        mineType.setTileSize(3, 3);
        mineType.setHitPoints(25_500);
        mineType.setBuilding(true);
        mineType.setGivesResource(UnitType.Resource.GOLD);
        mineType.setCanHarvest(true);
        UnitType peonType = new UnitType("unit-peon");
        peonType.setTileSize(1, 1);
        peonType.setHitPoints(30);
        peonType.setSpeed(10);
        peonType.setLandUnit(true);
        ResourceInfo gold = new ResourceInfo(UnitType.Resource.GOLD);
        gold.setCapacity(100);
        gold.setWaitAtResource(50);
        peonType.gathering().put(UnitType.Resource.GOLD, gold);
        AnimationSet animations = new AnimationSet("unit-peon");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", java.util.List.of("frame 0", "wait 8")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", java.util.List.of(
                        "unbreakable begin", "frame 0", "move 4", "wait 1",
                        "unbreakable end", "wait 1")));
        peonType.setAnimationSet(animations);
        Unit mine = world.createUnit(mineType, 15, 92, 49);
        mine.setResourcesHeld(25_500);
        // Great hall footprint blocks SE from (86,35) the way XOrc 2's hall
        // at (87,36) does.
        UnitType hallType = new UnitType("unit-great-hall");
        hallType.setTileSize(4, 4);
        hallType.setHitPoints(1200);
        hallType.setBuilding(true);
        hallType.setLandUnit(true);
        Unit hall = world.createUnit(hallType, 0, 87, 36);
        Unit jammer = world.createUnit(peonType, 0, 86, 36);
        Unit waiter = world.createUnit(peonType, 0, 86, 35);
        assertTrue(mine != null && hall != null && jammer != null && waiter != null,
                "mine, hall, and peons must place");
        assertTrue(world.orderHarvest(waiter, mine),
                "waiter accepts the gold order");
        // Standing ally on the leftover S cell; not a Move-animation
        // cooperative vacate.
        jammer.clearPath();
        jammer.setOffset(0, 0);
        jammer.animation().switchTo(animations.get(AnimationSet.State.STILL));
        int south = net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(0, 1);
        int se = net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(1, 1);
        // Leftover corridor S,S,SE toward the far mine -- S jammed, SE hall.
        waiter.setPath(new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Path(
                net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                new int[] {se, south, south}));
        waiter.setRouteSpent(false);
        waiter.setOffset(0, 0);
        waiter.setWalkHolding(false);
        waiter.setBattleNetOrderDelay(0);
        // Prior refuse already raised collision (native route_index 20 visit).
        waiter.setBattleNetCollisionCounter(1);
        waiter.animation().switchTo(animations.get(AnimationSet.State.STILL));

        boolean steppedSouthwest = false;
        StringBuilder trail = new StringBuilder();
        for (int call = 0; call < 8; call++) {
            if (waiter.tileX() == 86 && waiter.tileY() == 35
                    && waiter.battleNetOrderDelay() == 0) {
                waiter.setPath(new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Path(
                        net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                        new int[] {se, south, south}));
                waiter.setWalkHolding(false);
                waiter.setOffset(0, 0);
            }
            world.tick();
            trail.append(String.format("t%d pos=%d,%d d=%d path=%d; ",
                    call, waiter.tileX(), waiter.tileY(),
                    waiter.battleNetOrderDelay(), waiter.pathLength()));
            if (waiter.tileX() == 85 && waiter.tileY() == 36) {
                steppedSouthwest = true;
                break;
            }
        }
        assertTrue(steppedSouthwest,
                "jammed gold leftover must detour SW onto the free closer tile (85,36); trail="
                        + trail);
        assertEquals(86, jammer.tileX(), "standing jammer must stay put");
        assertEquals(36, jammer.tileY(), "standing jammer must stay put");
        // Free-detour must keep multi residual under the detour heading.
        // setPath(single SW) spent the only heading and left 1563 pathn 0 /
        // routeSpent draining residual through fixture 42 while native kept
        // residual and free-compassed S@42. pathLength after the detour step
        // must still hold leftover corridor headings (SE under SW peek).
        assertTrue(waiter.pathLength() > 0,
                "free-detour must keep leftover residual under the detour; pathn="
                        + waiter.pathLength() + " trail=" + trail);
    }

    @Test
    @DisplayName("a much-refused gold peon still owes a visit before it takes the detour")
    void aMuchRefusedGoldPeonStillOwesAVisitBeforeTheDetour() {
        // The same jam as 1563 above, but reached by a peon that has already
        // been refused more than once. XHuman 10's peon 1432 sat on (16,109)
        // through two full fifteen-count refusals of its south-east leftover,
        // so its collision count was three by the time a closer square opened.
        // Retail still writes twenty into its route index on that visit and
        // walks on the one after -- it stands on (16,109) at fixture 53 and
        // reaches (16,110) at 54, and all ten route-index marks in that
        // mission are followed by a step on the next cycle, never on the mark.
        // Turning on the visit itself gained 1432 a cycle on retail.
        GameMap map = new GameMap(100, 60, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        UnitType mineType = new UnitType("unit-gold-mine");
        mineType.setTileSize(3, 3);
        mineType.setHitPoints(25_500);
        mineType.setBuilding(true);
        mineType.setGivesResource(UnitType.Resource.GOLD);
        mineType.setCanHarvest(true);
        UnitType peonType = new UnitType("unit-peon");
        peonType.setTileSize(1, 1);
        peonType.setHitPoints(30);
        peonType.setSpeed(10);
        peonType.setLandUnit(true);
        ResourceInfo gold = new ResourceInfo(UnitType.Resource.GOLD);
        gold.setCapacity(100);
        gold.setWaitAtResource(50);
        peonType.gathering().put(UnitType.Resource.GOLD, gold);
        AnimationSet animations = new AnimationSet("unit-peon");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", java.util.List.of("frame 0", "wait 8")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", java.util.List.of(
                        "unbreakable begin", "frame 0", "move 4", "wait 1",
                        "unbreakable end", "wait 1")));
        peonType.setAnimationSet(animations);
        Unit mine = world.createUnit(mineType, 15, 92, 49);
        mine.setResourcesHeld(25_500);
        UnitType hallType = new UnitType("unit-great-hall");
        hallType.setTileSize(4, 4);
        hallType.setHitPoints(1200);
        hallType.setBuilding(true);
        hallType.setLandUnit(true);
        Unit hall = world.createUnit(hallType, 0, 87, 36);
        Unit jammer = world.createUnit(peonType, 0, 86, 36);
        Unit waiter = world.createUnit(peonType, 0, 86, 35);
        assertTrue(mine != null && hall != null && jammer != null && waiter != null,
                "mine, hall, and peons must place");
        assertTrue(world.orderHarvest(waiter, mine),
                "waiter accepts the gold order");
        jammer.clearPath();
        jammer.setOffset(0, 0);
        jammer.animation().switchTo(animations.get(AnimationSet.State.STILL));
        int south = net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(0, 1);
        int se = net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(1, 1);
        waiter.setPath(new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Path(
                net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                new int[] {se, south, south}));
        waiter.setRouteSpent(false);
        waiter.setOffset(0, 0);
        waiter.setWalkHolding(false);
        waiter.setBattleNetOrderDelay(0);
        // Two fifteen-count refusals already behind it, the way 1432 arrives.
        waiter.setBattleNetCollisionCounter(3);
        waiter.animation().switchTo(animations.get(AnimationSet.State.STILL));

        world.tick();
        assertEquals(86, waiter.tileX(),
                "the peon gave its route up and walked the detour on the same "
                        + "visit; retail marks the route index and stands");
        assertEquals(35, waiter.tileY(),
                "the peon gave its route up and walked the detour on the same "
                        + "visit; retail marks the route index and stands");

        world.tick();
        assertEquals(85, waiter.tileX(),
                "the visit after the route was given up must walk the free "
                        + "closer square south-west");
        assertEquals(36, waiter.tileY(),
                "the visit after the route was given up must walk the free "
                        + "closer square south-west");
    }

    @Test
    @DisplayName("a gold free-detour leftover free residual steps after one quiet visit")
    void aGoldFreeDetourLeftoverFreeResidualStepsAfterOneQuietVisit() {
        // XORc 2 peon 1563 after free-detour SW@25 keeps residual and takes
        // free S only at fixture 42. Free residual on residual settle alone
        // stepped S@41 (REG earlier than accepted h41 floor). One harvest
        // quiet after free-detour delays residual drain so free residual
        // lands with native.
        GameMap map = new GameMap(100, 60, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        UnitType mineType = new UnitType("unit-gold-mine");
        mineType.setTileSize(3, 3);
        mineType.setHitPoints(25_500);
        mineType.setBuilding(true);
        mineType.setGivesResource(UnitType.Resource.GOLD);
        mineType.setCanHarvest(true);
        UnitType peonType = new UnitType("unit-peon");
        peonType.setTileSize(1, 1);
        peonType.setHitPoints(30);
        peonType.setSpeed(10);
        peonType.setLandUnit(true);
        ResourceInfo gold = new ResourceInfo(UnitType.Resource.GOLD);
        gold.setCapacity(100);
        gold.setWaitAtResource(50);
        peonType.gathering().put(UnitType.Resource.GOLD, gold);
        // Fast move so residual of free-detour SW settles in a few ticks.
        AnimationSet animations = new AnimationSet("unit-peon");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", java.util.List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", java.util.List.of(
                        "unbreakable begin", "frame 0", "move 16", "wait 1",
                        "frame 0", "move 16", "unbreakable end", "wait 1")));
        peonType.setAnimationSet(animations);
        Unit mine = world.createUnit(mineType, 15, 92, 49);
        mine.setResourcesHeld(25_500);
        UnitType hallType = new UnitType("unit-great-hall");
        hallType.setTileSize(4, 4);
        hallType.setHitPoints(1200);
        hallType.setBuilding(true);
        hallType.setLandUnit(true);
        Unit hall = world.createUnit(hallType, 0, 87, 36);
        Unit jammer = world.createUnit(peonType, 0, 86, 36);
        Unit waiter = world.createUnit(peonType, 0, 86, 35);
        assertTrue(mine != null && hall != null && jammer != null && waiter != null,
                "units must place");
        assertTrue(world.orderHarvest(waiter, mine), "gold order accepted");
        jammer.clearPath();
        jammer.setOffset(0, 0);
        jammer.animation().switchTo(animations.get(AnimationSet.State.STILL));
        int south = net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(0, 1);
        int se = net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(1, 1);
        waiter.setPath(new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Path(
                net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                new int[] {se, south, south}));
        waiter.setRouteSpent(false);
        waiter.setOffset(0, 0);
        waiter.setWalkHolding(false);
        waiter.setBattleNetOrderDelay(0);
        waiter.setBattleNetCollisionCounter(1);
        waiter.animation().switchTo(animations.get(AnimationSet.State.STILL));

        Integer freeDetourTick = null;
        Integer freeResidualTick = null;
        for (int call = 0; call < 40; call++) {
            world.tick();
            if (freeDetourTick == null
                    && waiter.tileX() == 85 && waiter.tileY() == 36) {
                freeDetourTick = call;
            }
            if (freeDetourTick != null
                    && waiter.tileX() == 85 && waiter.tileY() == 37) {
                freeResidualTick = call;
                break;
            }
        }
        assertTrue(freeDetourTick != null,
                "free-detour must land SW on (85,36)");
        assertTrue(freeResidualTick != null,
                "free residual S must step after free-detour residual drain");
        int gap = freeResidualTick - freeDetourTick;
        // Residual drain of free-detour SW (two move-16) plus one harvest
        // quiet after free-detour: free residual must not land on the settle
        // visit alone (native S@42 after SW@25).
        assertTrue(gap >= 2,
                "free residual after free-detour must not step same residual "
                        + "settle visit; gap=" + gap + " free-detour="
                        + freeDetourTick + " free-residual=" + freeResidualTick);
    }

    @Test
    @DisplayName("a near residual one-heading gold refuse free-detours southwest past a standing ally")
    void aNearResidualOneHeadingGoldRefuseFreeDetoursSouthwestPastAStandingAlly() {
        // retail-xhuman-12-idle peon 1561: residual of S onto (4,26) leaves
        // one-heading SE onto standing ally at (5,27). Approach cheb is 2.
        // Free S onto (4,27) and free SW onto (3,27) both close to 1; native
        // steps SW at fixture 39. Far free-detour (cheb>2) did not arm; delay
        // 15 then SE at 55. Diagonal preference on equal-close picks SW.
        GameMap map = new GameMap(20, 40, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        UnitType mineType = new UnitType("unit-gold-mine");
        mineType.setTileSize(3, 3);
        mineType.setHitPoints(25_500);
        mineType.setBuilding(true);
        mineType.setGivesResource(UnitType.Resource.GOLD);
        mineType.setCanHarvest(true);
        UnitType peonType = new UnitType("unit-peon");
        peonType.setTileSize(1, 1);
        peonType.setHitPoints(30);
        peonType.setSpeed(10);
        peonType.setLandUnit(true);
        ResourceInfo gold = new ResourceInfo(UnitType.Resource.GOLD);
        gold.setCapacity(100);
        gold.setWaitAtResource(50);
        peonType.gathering().put(UnitType.Resource.GOLD, gold);
        AnimationSet animations = new AnimationSet("unit-peon");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", java.util.List.of("frame 0", "wait 8")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", java.util.List.of(
                        "unbreakable begin", "frame 0", "move 4", "wait 1",
                        "unbreakable end", "wait 1")));
        peonType.setAnimationSet(animations);
        Unit mine = world.createUnit(mineType, 15, 3, 28);
        mine.setResourcesHeld(25_500);
        Unit jammer = world.createUnit(peonType, 0, 5, 27);
        Unit waiter = world.createUnit(peonType, 0, 4, 26);
        assertTrue(mine != null && jammer != null && waiter != null,
                "mine and peons must place");
        assertTrue(world.orderHarvest(waiter, mine),
                "waiter accepts the gold order");
        jammer.clearPath();
        jammer.setOffset(0, 0);
        jammer.animation().switchTo(animations.get(AnimationSet.State.STILL));
        int se = net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(1, 1);
        // One residual leftover SE onto the standing ally.
        waiter.setPath(new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Path(
                net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                new int[] {se}));
        waiter.setRouteSpent(false);
        waiter.setOffset(0, 0);
        waiter.setWalkHolding(false);
        waiter.setStepDrained(true);
        waiter.setBattleNetOrderDelay(0);
        // First residual refuse (coll 0→1) steps same visit -- native 1561
        // SW at fixture 39, the settle cycle.
        waiter.setBattleNetCollisionCounter(0);
        waiter.animation().switchTo(animations.get(AnimationSet.State.STILL));

        world.movement.stepMove(waiter, false);
        assertEquals(3, waiter.tileX(),
                "near residual SE refuse must free-detour SW to 3,27 not sit "
                        + "or take pure S; x=" + waiter.tileX());
        assertEquals(27, waiter.tileY(),
                "near residual SE refuse must free-detour SW to 3,27 same "
                        + "visit as residual settle; y=" + waiter.tileY());
    }

    @Test
    @DisplayName("a first near gold soft-refuse still uses the full cooperative wait")
    void aFirstNearGoldSoftRefuseStillUsesTheFullCooperativeWait() {
        // Shortening every near refuse to four quiet visits stepped other
        // XHuman 12 peons early (gate clean only through 22). The first
        // cooperative soft-wait stays fourteen; only the free-window retry
        // shortens.
        GameMap map = new GameMap(20, 40, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        UnitType mineType = new UnitType("unit-gold-mine");
        mineType.setTileSize(3, 3);
        mineType.setHitPoints(25_500);
        mineType.setBuilding(true);
        mineType.setGivesResource(UnitType.Resource.GOLD);
        mineType.setCanHarvest(true);
        UnitType peonType = new UnitType("unit-peon");
        peonType.setTileSize(1, 1);
        peonType.setHitPoints(30);
        peonType.setSpeed(10);
        peonType.setLandUnit(true);
        ResourceInfo gold = new ResourceInfo(UnitType.Resource.GOLD);
        gold.setCapacity(100);
        gold.setWaitAtResource(50);
        peonType.gathering().put(UnitType.Resource.GOLD, gold);
        AnimationSet animations = new AnimationSet("unit-peon");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", java.util.List.of("frame 0", "wait 8")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", java.util.List.of(
                        "unbreakable begin", "frame 0", "move 4", "wait 8",
                        "unbreakable end", "wait 1")));
        peonType.setAnimationSet(animations);
        Unit mine = world.createUnit(mineType, 15, 3, 28);
        mine.setResourcesHeld(25_500);
        Unit blocker = world.createUnit(peonType, 0, 5, 27);
        Unit waiter = world.createUnit(peonType, 0, 6, 26);
        assertTrue(mine != null && blocker != null && waiter != null,
                "mine and peons must place");
        assertTrue(world.orderHarvest(waiter, mine),
                "waiter accepts the gold order");
        blocker.clearPath();
        blocker.setOffset(-16, -16);
        blocker.animation().switchTo(animations.get(AnimationSet.State.MOVE));
        int sw = net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(-1, 1);
        int se = net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(1, 1);
        waiter.setPath(new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Path(
                net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                new int[] {se, sw}));
        waiter.setRouteSpent(false);
        waiter.setOffset(0, 0);
        waiter.setWalkHolding(false);
        waiter.setBattleNetOrderDelay(0);
        waiter.setBattleNetCollisionCounter(0);
        waiter.animation().switchTo(animations.get(AnimationSet.State.STILL));

        world.tick();
        assertEquals(14, waiter.battleNetOrderDelay(),
                "first near gold cooperative soft-refuse must keep delay 14");
        assertEquals(6, waiter.tileX(), "waiter stays put on the first soft-wait");
        assertEquals(26, waiter.tileY(), "waiter stays put on the first soft-wait");
    }

    @Test
    @DisplayName("a peon refused on a residual soft-clear step takes the next progressive heading")
    void aPeonRefusedOnAResidualSoftClearStepTakesTheNextProgressiveHeading() {
        // XHuman 12 peon 1554 at (5,26) harvesting mine (3,28). Path soft-
        // clears Move-offset ally 1550 at (5,27), plans S then SE, then the
        // solid south step refuses. The gold refuse arm tries the next
        // progressive heading (SE) once free -- native steps onto (6,27) at
        // fixture 12 rather than PF_WAIT 10 on pure south.
        GameMap map = new GameMap(20, 20, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        UnitType mineType = new UnitType("unit-gold-mine");
        mineType.setTileSize(3, 3);
        mineType.setHitPoints(25_500);
        mineType.setBuilding(true);
        mineType.setGivesResource(UnitType.Resource.GOLD);
        mineType.setCanHarvest(true);
        UnitType peonType = new UnitType("unit-peon");
        peonType.setTileSize(1, 1);
        peonType.setHitPoints(30);
        peonType.setSpeed(10);
        peonType.setLandUnit(true);
        ResourceInfo gold = new ResourceInfo(UnitType.Resource.GOLD);
        gold.setCapacity(100);
        gold.setWaitAtResource(50);
        peonType.gathering().put(UnitType.Resource.GOLD, gold);
        AnimationSet animations = new AnimationSet("unit-peon");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", java.util.List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", java.util.List.of(
                        "frame 0", "move 8", "wait 1")));
        peonType.setAnimationSet(animations);
        Unit mine = world.createUnit(mineType, 15, 3, 8);
        mine.setResourcesHeld(25_500);
        Unit blocker = world.createUnit(peonType, 0, 5, 7);
        Unit waiter = world.createUnit(peonType, 0, 5, 6);
        assertTrue(mine != null && blocker != null && waiter != null,
                "mine and peons must place");
        // Residual-only "moving": tile settled, no path left, IX/IY drain so
        // path soft-clear treats (5,7) free while the step cannot enter.
        blocker.setOffset(-29, -29);
        blocker.clearPath();
        assertTrue(world.orderHarvest(blocker, mine),
                "blocker accepts the gold order");
        assertTrue(world.orderHarvest(waiter, mine),
                "waiter accepts the gold order");

        boolean steppedSoutheast = false;
        for (int call = 0; call < 40; call++) {
            world.tick();
            if (waiter.tileX() == 6 && waiter.tileY() == 7) {
                steppedSoutheast = true;
                break;
            }
        }
        assertTrue(steppedSoutheast,
                "waiter must detour south-east onto (6,7) after the pure-south step refuses");
    }

    @Test
    @DisplayName("a gold free-prefix that ends beside forest re-aims to the tree and chops")
    void goldFreePrefixBesideForestReaimsToAdjacentTreeAndChops() {
        // retail-orc-07-idle peon 1567 is gold-assigned, free-prefix NW onto
        // (40,8) toward mine approach (32,2), then on fixture cycle 24
        // re-aims to adjacent tree (40,7) and draws the first chop SyncRand.
        // Serving PF_WAIT and re-planning the mine left the seed stuck at
        // 41c67ea6 with no draw.
        GameMap map = new GameMap(48, 16, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        // Forest mass west of the free landing, including the mine approach.
        for (int x = 30; x <= 39; x++) {
            for (int y = 1; y <= 8; y++) {
                map.field(x, y).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
            }
        }
        // Free landing (40,8); adjacent tree north is the re-aim target.
        map.field(40, 8).setFlags(TileFlag.LAND_ALLOWED);
        map.field(40, 7).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
        World world = new World(map);
        world.restoreRandom(1, 0);
        UnitType mineType = new UnitType("unit-gold-mine");
        mineType.setTileSize(3, 3);
        mineType.setHitPoints(25_500);
        mineType.setBuilding(true);
        mineType.setGivesResource(UnitType.Resource.GOLD);
        mineType.setCanHarvest(true);
        UnitType peonType = new UnitType("unit-peasant");
        peonType.setTileSize(1, 1);
        peonType.setHitPoints(30);
        peonType.setSpeed(10);
        peonType.setLandUnit(true);
        ResourceInfo gold = new ResourceInfo(UnitType.Resource.GOLD);
        gold.setCapacity(100);
        gold.setWaitAtResource(50);
        peonType.gathering().put(UnitType.Resource.GOLD, gold);
        ResourceInfo wood = new ResourceInfo(UnitType.Resource.WOOD);
        wood.setCapacity(100);
        wood.setWaitAtResource(25);
        wood.setStep(10);
        wood.setTerrainHarvester(true);
        peonType.gathering().put(UnitType.Resource.WOOD, wood);
        AnimationSet animations = new AnimationSet("unit-peasant");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", java.util.List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", java.util.List.of(
                        "frame 0", "move 8", "wait 1")));
        animations.put(AnimationSet.State.HARVEST,
                Animation.parse("harvest", java.util.List.of(
                        "frame 0", "wait 5", "frame 0", "wait 5")));
        peonType.setAnimationSet(animations);
        Unit mine = world.createUnit(mineType, 15, 30, 0);
        mine.setResourcesHeld(25_500);
        Unit peon = world.createUnit(peonType, 0, 40, 8);
        assertTrue(mine != null && peon != null, "mine and peon must place");
        assertTrue(world.orderHarvest(peon, mine),
                "gold order must be accepted");
        // Free-prefix residue after residual pixels settle: spent empty route
        // far from the mine approach, standing already beside forest.
        peon.clearPath();
        peon.setRouteSpent(true);
        peon.setOffset(0, 0);
        peon.setWalkHolding(false);
        int seedBefore = world.randomSeed();
        world.tick();
        assertEquals(UnitType.Resource.WOOD, peon.carrying(),
                "spent gold free-prefix beside forest must re-aim to wood");
        assertEquals(40, peon.resourceTileX(),
                "re-aim must keep the adjacent north tree");
        assertEquals(7, peon.resourceTileY(),
                "re-aim must keep the adjacent north tree");
        assertEquals(seedBefore * 0x41c64e6d + 0x3039, world.randomSeed(),
                "first chop after re-aim must draw one SyncRand this call");
    }

    @Test
    @DisplayName("a near-approach gold free-prefix stages the approach instead of a wrong leftover")
    void nearApproachGoldFreePrefixStagesApproachInsteadOfWrongLeftover() {
        // retail-xhuman-09-idle peon 1550: free-prefix lands at (109,24) with
        // gold approach (110,23) at Chebyshev 1 and a leftover N onto
        // (109,23). Cold-commit used to walk that leftover and diverge at
        // fixture 19. Native clears the leftover, drains residual on the
        // free-prefix tile, then stages action 25 onto the approach at
        // fixture 22.
        GameMap map = new GameMap(24, 24, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        UnitType mineType = new UnitType("unit-gold-mine");
        mineType.setTileSize(3, 3);
        mineType.setHitPoints(25_500);
        mineType.setBuilding(true);
        mineType.setGivesResource(UnitType.Resource.GOLD);
        mineType.setCanHarvest(true);
        // Mine top-left (10,5) covers x10-12 y5-7. Worker at (9,8) after the
        // first free-prefix step has approach (10,7) -- Chebyshev 1 -- and a
        // leftover north onto (9,7) misses it (real shape: 109,24 / 110,23).
        Unit mine = world.createUnit(mineType, World.NEUTRAL_PLAYER, 10, 5);
        assertTrue(mine != null, "the gold mine must place");
        mine.setResourcesHeld(25_500);
        UnitType peonType = new UnitType("unit-peon");
        peonType.setTileSize(1, 1);
        peonType.setHitPoints(30);
        peonType.setSpeed(10);
        peonType.setLandUnit(true);
        ResourceInfo gold = new ResourceInfo(UnitType.Resource.GOLD);
        gold.setCapacity(100);
        gold.setWaitAtResource(50);
        peonType.gathering().put(UnitType.Resource.GOLD, gold);
        AnimationSet animations = new AnimationSet("unit-peon");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", java.util.List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", java.util.List.of(
                        "frame 0", "move 8", "wait 1")));
        peonType.setAnimationSet(animations);
        Unit peon = world.createUnit(peonType, 0, 9, 9);
        assertTrue(peon != null, "the peon must place");
        assertTrue(world.orderHarvest(peon, mine),
                "the gold order must be accepted");
        // Two pure-north free-prefix steps: land at (9,8), leftover N to (9,7).
        int north = net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(0, -1);
        peon.setPath(new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Path(
                net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                new int[] {north, north}));
        peon.setBattleNetGoldLongApproach(true);

        boolean sawLanding = false;
        boolean walkedWrongLeftover = false;
        boolean reachedApproach = false;
        for (int call = 0; call < 60; call++) {
            world.tick();
            if (peon.tileX() == 9 && peon.tileY() == 8) {
                sawLanding = true;
            }
            if (peon.tileX() == 9 && peon.tileY() == 7) {
                walkedWrongLeftover = true;
                break;
            }
            if (peon.tileX() == 10 && peon.tileY() == 7) {
                reachedApproach = true;
                break;
            }
            if (!peon.isOnMap()) {
                break;
            }
        }
        assertTrue(sawLanding,
                "free-prefix must land at (9,8) one tile from the approach");
        assertFalse(walkedWrongLeftover,
                "leftover north onto (9,7) must not fire after free-prefix land");
        assertTrue(reachedApproach,
                "action-25 stage after residual must step onto the approach (10,7)");
    }

    @Test
    @DisplayName("a mid-journey free-prefix replans without the ten-cycle wait")
    void midJourneyGoldFreePrefixReplansWithoutTheTenCycleWait() {
        // retail-orc-12-idle peon 1525 (gold) and retail-xhuman-10-idle peon
        // 1551 (build): free-prefix lands short of the goal with routeSpent.
        // Residual settle used to arm PF_WAIT 10; native replans immediately.
        GameMap map = new GameMap(24, 24, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        UnitType mineType = new UnitType("unit-gold-mine");
        mineType.setTileSize(3, 3);
        mineType.setHitPoints(25_500);
        mineType.setBuilding(true);
        mineType.setGivesResource(UnitType.Resource.GOLD);
        mineType.setCanHarvest(true);
        // Mine (10,5)-(12,7). Peon at free tip (14,7): approach (12,7), cheb 2.
        Unit mine = world.createUnit(mineType, World.NEUTRAL_PLAYER, 10, 5);
        assertTrue(mine != null, "the gold mine must place");
        mine.setResourcesHeld(25_500);
        UnitType peonType = new UnitType("unit-peon");
        peonType.setTileSize(1, 1);
        peonType.setHitPoints(30);
        peonType.setSpeed(10);
        peonType.setLandUnit(true);
        ResourceInfo gold = new ResourceInfo(UnitType.Resource.GOLD);
        gold.setCapacity(100);
        gold.setWaitAtResource(50);
        peonType.gathering().put(UnitType.Resource.GOLD, gold);
        AnimationSet animations = new AnimationSet("unit-peon");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", java.util.List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", java.util.List.of(
                        "unbreakable begin",
                        "frame 0", "move 8", "wait 1",
                        "frame 0", "move 8", "wait 1",
                        "frame 0", "move 8", "wait 1",
                        "frame 0", "move 8", "wait 1",
                        "unbreakable end", "wait 1")));
        peonType.setAnimationSet(animations);
        Unit peon = world.createUnit(peonType, 0, 14, 7);
        assertTrue(peon != null, "the peon must place");
        assertTrue(world.orderHarvest(peon, mine),
                "the gold order must be accepted");
        // Emulate free-prefix tip residual just settled: empty spent route,
        // free-prefix flag, no wait yet. clearPath wipes flags; restore them.
        peon.clearPath();
        peon.setRouteSpent(true);
        peon.setBattleNetGoldFreePrefix(true);
        peon.setBattleNetGoldLongApproach(true);
        peon.setOffset(0, 0);
        peon.setWalkHolding(false);
        peon.setWaitCycles(0);
        peon.setBattleNetOrderDelay(0);

        world.tick();
        assertTrue(peon.waitCycles() < 10,
                "mid-journey free-prefix settle must not arm PF_WAIT 10; "
                        + "saw wait " + peon.waitCycles());
        assertTrue(peon.pathLength() > 0 || peon.tileX() < 14,
                "free-prefix settle must replan toward the mine approach");
    }

    @Test
    @DisplayName("a short free-prefix tip with an ally-blocked leftover replans without PF_WAIT ten")
    void shortFreePrefixTipBlockedLeftoverReplansWithoutTheTenCycleWait() {
        // XORc 12 peasant 1397 (Java 203): three-step free-prefix lands on
        // 31,72 with leftover SE onto ally 205 at 32,73 (approach 33,72,
        // cheb 2). Residual used to leave path=1 spent=0 then PF_WAIT 10.
        // Native marks spent and replans NE. Short free-prefix (length < 4)
        // discards the blocked leftover; longer free-prefix (XORc 6) soft-
        // holds progressive leftovers instead.
        GameMap map = new GameMap(24, 24, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        UnitType mineType = new UnitType("unit-gold-mine");
        mineType.setTileSize(3, 3);
        mineType.setHitPoints(25_500);
        mineType.setBuilding(true);
        mineType.setGivesResource(UnitType.Resource.GOLD);
        mineType.setCanHarvest(true);
        Unit mine = world.createUnit(mineType, World.NEUTRAL_PLAYER, 10, 5);
        assertTrue(mine != null, "the gold mine must place");
        mine.setResourcesHeld(25_500);
        UnitType peonType = new UnitType("unit-peon");
        peonType.setTileSize(1, 1);
        peonType.setHitPoints(30);
        peonType.setSpeed(10);
        peonType.setLandUnit(true);
        ResourceInfo gold = new ResourceInfo(UnitType.Resource.GOLD);
        gold.setCapacity(100);
        gold.setWaitAtResource(50);
        peonType.gathering().put(UnitType.Resource.GOLD, gold);
        AnimationSet animations = new AnimationSet("unit-peon");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", java.util.List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", java.util.List.of(
                        "unbreakable begin",
                        "frame 0", "move 8", "wait 1",
                        "frame 0", "move 8", "wait 1",
                        "frame 0", "move 8", "wait 1",
                        "frame 0", "move 8", "wait 1",
                        "unbreakable end", "wait 1")));
        peonType.setAnimationSet(animations);
        // Tip (8,7): approach (10,7) cheb 2. Ally on SE (9,8).
        Unit peon = world.createUnit(peonType, 0, 8, 7);
        Unit ally = world.createUnit(peonType, 0, 9, 8);
        assertTrue(peon != null && ally != null, "both peons must place");
        assertTrue(world.orderHarvest(peon, mine),
                "the gold order must be accepted");
        assertTrue(world.orderHarvest(ally, mine),
                "the ally must share the mine so occupancy is solid");
        int se = net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(1, 1);
        peon.clearPath();
        peon.setPath(new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Path(
                net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                new int[] {se}));
        peon.setBattleNetGoldFreePrefix(true);
        peon.setBattleNetGoldFreePrefixLength(3);
        peon.setBattleNetGoldLongApproach(true);
        peon.setRouteSpent(false);
        peon.setOffset(-8, 0);
        peon.setWalkHolding(true);
        peon.setWaitCycles(0);
        peon.setBattleNetOrderDelay(0);
        peon.animation().switchTo(animations.get(AnimationSet.State.MOVE));

        for (int i = 0; i < 8; i++) {
            world.tick();
            if (!peon.isMoving() && peon.pathLength() == 0) {
                break;
            }
        }
        assertTrue(peon.waitCycles() < 10,
                "short free-prefix blocked leftover must not arm PF_WAIT 10; "
                        + "saw wait " + peon.waitCycles());
        assertTrue(peon.routeSpent() || peon.pathLength() > 0
                        || peon.tileX() != 8 || peon.tileY() != 7,
                "tip settle must mark spent or replan toward the mine");

        int settleX = peon.tileX();
        int settleY = peon.tileY();
        boolean leftTip = false;
        for (int i = 0; i < 12; i++) {
            world.tick();
            if (peon.tileX() != settleX || peon.tileY() != settleY) {
                leftTip = true;
                break;
            }
            if (peon.pathLength() > 0 && peon.waitCycles() == 0) {
                leftTip = true;
                break;
            }
        }
        assertTrue(leftTip,
                "after the settle visit the peon must replan off the free tip");
        assertTrue(peon.tileX() != 9 || peon.tileY() != 8,
                "must not walk the blocked SE leftover onto the ally");
    }

    @Test
    @DisplayName("a build free-prefix tip one chebyshev from the site replans without the ten-cycle wait")
    void buildFreePrefixTipOneChebyshevFromSiteReplansWithoutTheTenCycleWait() {
        // retail-orc-10-idle peon 1583: free tip at 43,3 with build goal 44,4
        // is cheb 1 and maxRange 0. Requiring cheb > max(1, maxRange) left the
        // tip on PF_WAIT 10 while native residual-settled and stepped SE.
        GameMap map = new GameMap(24, 24, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        world.setBuilders(java.util.Map.of("unit-farm",
                java.util.Set.of("unit-peon")));
        world.player(0).set(UnitType.Resource.GOLD, 5000);
        world.player(0).set(UnitType.Resource.WOOD, 5000);
        UnitType farmType = new UnitType("unit-farm");
        farmType.setTileSize(2, 2);
        farmType.setHitPoints(400);
        farmType.setBuilding(true);
        farmType.costs().put(UnitType.Resource.TIME, 1);
        farmType.costs().put(UnitType.Resource.GOLD, 500);
        farmType.costs().put(UnitType.Resource.WOOD, 250);
        UnitType peonType = new UnitType("unit-peon");
        peonType.setTileSize(1, 1);
        peonType.setHitPoints(30);
        peonType.setSpeed(10);
        peonType.setLandUnit(true);
        AnimationSet animations = new AnimationSet("unit-peon");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", java.util.List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", java.util.List.of(
                        "unbreakable begin",
                        "frame 0", "move 8", "wait 1",
                        "frame 0", "move 8", "wait 1",
                        "frame 0", "move 8", "wait 1",
                        "frame 0", "move 8", "wait 1",
                        "unbreakable end", "wait 1")));
        peonType.setAnimationSet(animations);
        // Tip at (9,9), site/goal at (10,10): cheb 1, maxRange 0 for a farm.
        Unit peon = world.createUnit(peonType, 0, 9, 9);
        assertTrue(peon != null, "the peon must place");
        assertTrue(world.orderBuild(peon, farmType, 10, 10),
                "the build order must be accepted");
        assertEquals(10, peon.buildGoalX(),
                "build goal x must be the farm footprint aim");
        assertEquals(10, peon.buildGoalY(),
                "build goal y must be the farm footprint aim");
        // Emulate free-prefix tip residual just settled one tile short.
        peon.clearPath();
        peon.setRouteSpent(true);
        peon.setBattleNetGoldFreePrefix(true);
        peon.setOffset(0, 0);
        peon.setWalkHolding(false);
        peon.setWaitCycles(0);
        peon.setBattleNetOrderDelay(0);

        world.tick();
        assertTrue(peon.waitCycles() < 10,
                "build free-prefix tip at cheb 1 must not arm PF_WAIT 10; "
                        + "saw wait " + peon.waitCycles());
        assertTrue(peon.pathLength() > 0
                        || peon.tileX() != 9 || peon.tileY() != 9,
                "build free-prefix tip at cheb 1 must replan or step onto "
                        + "the site; still at " + peon.tileX() + ","
                        + peon.tileY() + " path " + peon.pathLength());
    }

    /** XOrc 2's corner of the map: the mine at 92,49 and open grass. */
    private static World xorcTwoGoldField() {
        GameMap map = new GameMap(100, 60, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return new World(map);
    }

    /** A three-by-three neutral mine holding gold, as every campaign has. */
    private static Unit goldMine(World world, int tileX, int tileY) {
        UnitType type = new UnitType("unit-gold-mine");
        type.setTileSize(3, 3);
        type.setHitPoints(25_500);
        type.setBuilding(true);
        type.setLandUnit(true);
        type.setGivesResource(UnitType.Resource.GOLD);
        type.setCanHarvest(true);
        Unit built = world.createUnit(type, World.NEUTRAL_PLAYER, tileX, tileY);
        assertTrue(built != null, "the gold mine must place");
        built.setResourcesHeld(25_500);
        return built;
    }

    /** A peon that walks a tile per pixel step and can carry gold. */
    private static UnitType goldPeon() {
        UnitType type = new UnitType("unit-peon");
        type.setTileSize(1, 1);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        ResourceInfo gold = new ResourceInfo(UnitType.Resource.GOLD);
        gold.setCapacity(100);
        gold.setWaitAtResource(150);
        gold.setWaitAtDepot(100);
        type.gathering().put(UnitType.Resource.GOLD, gold);
        AnimationSet animations = new AnimationSet("unit-peon");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", java.util.List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", java.util.List.of(
                        "frame 0", "move 32", "wait 1")));
        type.setAnimationSet(animations);
        return type;
    }

    /**
     * The first heading of the route a peon on 85,36 lays for the mine, with
     * a friend part-way through a step onto 86,37 carrying {@code nibble}
     * refusals.
     */
    private static int firstHeadingPastARefusingFriend(int nibble) {
        World world = xorcTwoGoldField();
        Unit mine = goldMine(world, 92, 49);

        UnitType peonType = goldPeon();
        Unit friend = world.createUnit(peonType, 0, 86, 37);
        Unit peon = world.createUnit(peonType, 0, 85, 36);
        assertTrue(friend != null && peon != null, "both peons must place");
        // The friend is standing still on 86,37 with pixels still owed for
        // the step that put it there -- native's 1561 at fixture 41, whose
        // record carries action state 3, a live route, and nibble 1.
        friend.setOffset(0, -23);
        friend.setWalkHolding(true);
        friend.animation().switchTo(
                peonType.animationSet().get(AnimationSet.State.MOVE));
        friend.setOrder(Unit.Order.HARVEST);
        friend.setResourceUnit(mine);
        friend.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {2, 3, 4, 4}));
        friend.setBattleNetCollisionCounter(nibble);
        peon.setOrder(Unit.Order.HARVEST);
        peon.setResourceUnit(mine);

        PathFinder.Path route =
                world.construction.findBattleNetBuildingPath(peon, mine);
        assertTrue(route != null && route.length() > 0,
                "the peon laid no route to the mine at all, so there is no"
                        + " first heading to read");
        return route.headings()[route.length() - 1];
    }

    @Test
    @DisplayName("a peon that has dropped its last heading is still going somewhere")
    void aWorkerThatDroppedItsLeftoverStillNamesTheSquareItWasGoingTo() {
        // retail-xhuman-10-idle peon 1596. It steps north-west onto 57,4 on
        // fixture 9 and stands there draining pixels until 24, and retail's
        // record holds both bytes of its route -- NW,N -- with the cursor one
        // step in the whole time. Peon 1590 behind it asks 0x0044fa20 where
        // it is going, is told 57,3, and takes retail's fifteen-cycle wait.
        //
        // This implementation empties the route when the mine comes into range, so the
        // question came back with nothing and 1590 gave its own route up
        // instead. The heading is kept in a field of its own now; what it
        // costs to keep the whole remainder is not known and nothing has
        // asked for more than the next square.
        World world = xorcTwoGoldField();
        UnitType peonType = goldPeon();
        Unit blocker = world.createUnit(peonType, 0, 57, 4);
        Unit mover = world.createUnit(peonType, 0, 57, 5);
        assertTrue(blocker != null && mover != null, "both peons must place");
        blocker.setOrder(Unit.Order.HARVEST);
        blocker.animation().switchTo(
                peonType.animationSet().get(AnimationSet.State.MOVE));
        blocker.setOffset(5, 5);
        blocker.setWalkHolding(true);
        blocker.setResourceUnit(goldMine(world, 58, 2));
        // Came into range of the mine and dropped the north it had left,
        // which is what the walk does at approach Chebyshev 1 -- retail's
        // 1596 on fixture 9.
        blocker.clearPath();
        blocker.setRouteSpent(true);
        blocker.setBattleNetSpentHeading(Direction.fromDelta(0, -1));

        assertTrue(world.battleNetCooperativeBlocker(mover, blocker),
                "a worker walking north off 57,4 is going to 57,3, which is"
                        + " not the square the peon behind it is standing on,"
                        + " so it is the ally retail waits fifteen cycles for."
                        + " Emptying the route on arrival is this port's"
                        + " bookkeeping and retail's record keeps the bytes");

        // And it stops being one the moment it is coming back at the mover.
        blocker.setBattleNetSpentHeading(Direction.fromDelta(0, 1));
        assertFalse(world.battleNetCooperativeBlocker(mover, blocker),
                "a worker heading south onto the mover's own square is the"
                        + " swap case, which 0x00437b70 sends to PARK rather"
                        + " than the wait");

    }

    @Test
    @DisplayName("a peon will not draw its route through a friend that is refusing")
    void aGoldRouteWillNotCrossAFriendCarryingRefusals() {
        // retail-xorc-02-idle peon 1563. On fixture 41 it gives its route up
        // on 85,36 and on 42 it stores S,SE and steps south onto 85,37, whose
        // map word is 0x0001. South-east of it, 86,37 reads 0x0501: peon 1561
        // is standing there, mid-animation with pixels owed, carrying refusal
        // nibble 1. Native's soft clear at 0x4501bc keeps a square occupied
        // when the blocker's high nibble at 0x1d is set, whatever its
        // animation says, so 1563's ray never crosses it.
        //
        // The gold branch of this implementation's soft clear asked isMoving() and
        // nothing else. It stood 1561 aside, drew south-east first, and then
        // could not take the step it had planned: six refusals and a fifteen
        // cycle sleep, and the case parted from retail at 42 instead of 58.
        assertEquals(Direction.fromDelta(0, 1), firstHeadingPastARefusingFriend(1),
                "a friend carrying a refusal is a wall to the planner, so the"
                        + " route to a mine to the south-east has to start"
                        + " south -- retail's peon 1563 stores S,SE from"
                        + " 85,36 and steps onto 85,37");
        assertEquals(Direction.fromDelta(1, 1), firstHeadingPastARefusingFriend(0),
                "and a friend with no refusals against its name is still stood"
                        + " aside, so the same peon draws the straight"
                        + " south-east line through him. Without this half the"
                        + " test would pass on a planner that walled every"
                        + " moving ally");
    }

    @Test
    @DisplayName("a gold walker whose step lands on a friend gives its route up that same cycle")
    void goldResidualSettleGivesTheRouteUpOnTheCycleItLands() {
        // retail-xhuman-10-idle peon 1437. It walks a straight column to the
        // mine with ally 168 standing on the next south cell, and the last two
        // pixels of its step land on fixture cycle 38. Retail marks the route
        // given up on that very cycle -- route_index 1 to 20, refusal nibble 1
        // to 2, both in the record for 38 -- and steps south-east on 39.
        //
        // This test used to require a quiet visit here instead, one cycle of
        // standing still with the route kept, and only then the refusal. That
        // was written when two free-detour arms above it fired a cycle early
        // and cancelled it out; those arms were an invention with nothing
        // behind them in fcn.004379e0 and are gone, and the quiet visit was
        // standing in for the refusal's own route-park. With it left in, peon
        // 1437 marked on 39 and stepped on 40 and stayed a cycle behind for
        // the rest of the walk -- 56 against retail's 55 on the next step.
        GameMap map = new GameMap(20, 20, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        UnitType mineType = new UnitType("unit-gold-mine");
        mineType.setTileSize(3, 3);
        mineType.setHitPoints(25_500);
        mineType.setBuilding(true);
        mineType.setLandUnit(true);
        mineType.setGivesResource(UnitType.Resource.GOLD);
        mineType.setCanHarvest(true);
        // Far mine so a same-cycle refuse would soft-wait / detour rather
        // than progressive-step; the quiet must stop that arm entirely.
        Unit mine = world.createUnit(mineType, World.NEUTRAL_PLAYER, 12, 2);
        assertTrue(mine != null, "the gold mine must place");
        mine.setResourcesHeld(25_500);

        UnitType peasantType = new UnitType("unit-peasant");
        peasantType.setTileSize(1, 1);
        peasantType.setHitPoints(30);
        peasantType.setSpeed(10);
        peasantType.setLandUnit(true);
        ResourceInfo gold = new ResourceInfo(UnitType.Resource.GOLD);
        gold.setCapacity(100);
        gold.setWaitAtResource(150);
        gold.setWaitAtDepot(100);
        peasantType.gathering().put(UnitType.Resource.GOLD, gold);
        AnimationSet animations = new AnimationSet("unit-peasant");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", java.util.List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", java.util.List.of(
                        "frame 0", "move 32", "wait 1")));
        peasantType.setAnimationSet(animations);

        // Ally occupies the NE leftover; peon at 5,10 with residual NE drain.
        Unit ally = world.createUnit(peasantType, 0, 6, 9);
        Unit peon = world.createUnit(peasantType, 0, 5, 10);
        assertTrue(ally != null && peon != null, "ally and peon place");
        peon.setResourceUnit(mine);
        peon.setOrder(Unit.Order.HARVEST);
        // Longer leftover like XHuman 10 (pathLen 7); stack peeks NE last.
        peon.setPath(new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Path(
                net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                new int[] {2, 2, 2, 2, 2, 2, 1}));
        peon.setLastStepHeading(1);
        peon.setOffset(-1, 1);
        peon.setWalkHolding(true);
        // Prior refuse already raised collision; without residual quiet the
        // gold far-detour arm would step a free neighbour same cycle.
        peon.setBattleNetCollisionCounter(1);
        peon.animation().switchTo(animations.get(AnimationSet.State.MOVE));

        world.movement.stepMove(peon, true);
        assertEquals(5, peon.tileX(),
                "the peon must not step through the ally standing on 6,9");
        assertEquals(10, peon.tileY(),
                "the peon must not leave 5,10 on the cycle it is refused");
        assertEquals(0, peon.pathLength(),
                "the cycle the step lands is the cycle the route is given up:"
                        + " 0x00450ad0 writes the route cursor to 20, one past"
                        + " the twenty heading bytes, so the peon is holding"
                        + " nothing. Keeping the seven headings for another"
                        + " visit is the quiet visit that put retail's peon"
                        + " 1437 a cycle ahead of this port for its whole walk");
        assertEquals(2, peon.battleNetCollisionCounter(),
                "the refusal count is bumped in the same visit it parks the"
                        + " route (0x00437a0d), so a peon that had refused once"
                        + " is on two");
        assertEquals(0, peon.battleNetOrderDelay(),
                "refusals one to seven put the movement timer back to 1, so the"
                        + " peon plans afresh on the very next visit rather"
                        + " than sleeping. Retail's peon 1437 marks 16,108"
                        + " refused on fixture 38 and is standing on 17,109"
                        + " at 39");
    }

    @Test
    @DisplayName("a far multi-step residual gold refuse uses coll bands then replans")
    void aFarMultiStepResidualGoldRefuseUsesCollBandsThenReplans() {
        // Orc 12 peon 1521 residual-settles onto 86,41 with leftover W,W,SW
        // toward mine approach while cooperative mover peon 75 holds 85,41
        // mid-HARVEST residual. FUN_004379e0 climbs coll 1..7 with timer 1,
        // arms timer 15 at coll 8, then replans SW onto 85,42 (fixture 46).
        // SoftDelay 14 then retrying free W stepped at fixture 39. Standing
        // jams keep the free-detour (XORc 2) and must not arm this hold.
        GameMap map = new GameMap(100, 50, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        UnitType mineType = new UnitType("unit-gold-mine");
        mineType.setTileSize(3, 3);
        mineType.setHitPoints(25_500);
        mineType.setBuilding(true);
        mineType.setGivesResource(UnitType.Resource.GOLD);
        mineType.setCanHarvest(true);
        UnitType peonType = new UnitType("unit-peon");
        peonType.setTileSize(1, 1);
        peonType.setHitPoints(30);
        peonType.setSpeed(10);
        peonType.setLandUnit(true);
        ResourceInfo gold = new ResourceInfo(UnitType.Resource.GOLD);
        gold.setCapacity(100);
        gold.setWaitAtResource(50);
        peonType.gathering().put(UnitType.Resource.GOLD, gold);
        AnimationSet animations = new AnimationSet("unit-peon");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", java.util.List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", java.util.List.of(
                        "unbreakable begin", "frame 0", "move 32", "wait 1",
                        "unbreakable end", "wait 1")));
        peonType.setAnimationSet(animations);
        // Mine far west so approach Chebyshev from 86,41 is > 2 (res 81,39).
        Unit mine = world.createUnit(mineType, 15, 81, 39);
        mine.setResourcesHeld(25_500);
        Unit blocker = world.createUnit(peonType, 0, 85, 41);
        Unit walker = world.createUnit(peonType, 0, 86, 41);
        assertTrue(mine != null && blocker != null && walker != null,
                "mine and peons must place");
        assertTrue(world.orderHarvest(walker, mine),
                "walker accepts the gold order");
        // Cooperative residual mover on the next W cell (MOVE animation,
        // empty path, coll 0) -- matching peon 75 mid-HARVEST residual.
        int west = net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(-1, 0);
        blocker.clearPath();
        blocker.setOffset(8, 0);
        blocker.setWalkHolding(false);
        blocker.setBattleNetCollisionCounter(0);
        blocker.animation().switchTo(animations.get(AnimationSet.State.MOVE));
        int west2 = west;
        int southwest = net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(-1, 1);
        // Stack last-index-first: peeks W then W then SW (pathLength 3).
        walker.setPath(new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Path(
                net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                new int[] {southwest, west2, west}));
        walker.setRouteSpent(false);
        walker.setOffset(0, 0);
        walker.setWalkHolding(false);
        walker.setStepDrained(true);
        walker.setBattleNetOrderDelay(0);
        walker.setBattleNetCollisionCounter(0);
        walker.setLastStepHeading(7); // arrived NW
        // Residual settle: MOVE program still live, drain-complete this decide.
        walker.animation().switchTo(animations.get(AnimationSet.State.MOVE));
        world.actionMoveWalked = true;

        // Seven quiet refuses (coll 1..7): delay 0, stay put, keep route.
        for (int refuse = 1; refuse <= 7; refuse++) {
            // Only the first call is the residual settle; later OP0s keep the
            // hold via battleNetFarMultiStepResidualRefuse without walkedThisCycle.
            if (refuse > 1) {
                world.actionMoveWalked = false;
            }
            // Keep the blocker cooperative across the hold (native 75 stays on
            // MOVE residual through the coll ramp).
            blocker.animation().switchTo(animations.get(AnimationSet.State.MOVE));
            blocker.setOffset(8, 0);
            blocker.setBattleNetCollisionCounter(0);
            walker.animation().switchTo(animations.get(AnimationSet.State.MOVE));
            world.movement.stepMove(walker, true);
            assertEquals(86, walker.tileX(),
                    "coll " + refuse + " must not take W onto the ally");
            assertEquals(41, walker.tileY(),
                    "coll " + refuse + " must not leave the residual tile");
            assertEquals(0, walker.battleNetOrderDelay(),
                    "coll " + refuse + " must use timer-1 quiet, not softDelay 14");
            assertTrue(walker.pathLength() >= 3,
                    "coll " + refuse + " must keep the multi-step leftover");
            assertEquals(refuse, walker.battleNetCollisionCounter(),
                    "each quiet refuse must raise collision toward the band-8 hold");
            assertTrue(walker.battleNetFarMultiStepResidualRefuse(),
                    "coll " + refuse + " must keep the residual refuse hold armed");
        }
        // Eighth refuse: clear for replan and arm the fifteen-count hold
        // (Java stores fourteen remaining quiet visits).
        walker.animation().switchTo(animations.get(AnimationSet.State.MOVE));
        world.movement.stepMove(walker, true);
        assertEquals(14, walker.battleNetOrderDelay(),
                "coll 8 must arm fourteen remaining quiet visits of the fifteen-count hold");
        assertEquals(0, walker.pathLength(),
                "coll 8 must clear the stale leftover so resume replans");
        assertEquals(86, walker.tileX(),
                "the replan hold must not step onto the ally");
        assertEquals(41, walker.tileY(),
                "the replan hold must not leave the residual tile");
        assertFalse(walker.battleNetFarMultiStepResidualRefuse(),
                "coll 8 must clear the residual refuse hold after replan arm");
    }

    @Test
    @DisplayName("a gold replan wall-follows around residual allies on the parallel approach row")
    void aGoldReplanWallFollowsAroundResidualAlliesOnTheParallelApproachRow() {
        // Orc 5 peasant 1534 at (34,100) mid-journey replans toward mine
        // (28,99) while residual isMoving pathLength-0 allies hold 30,101
        // and 31,101. Soft-clearing those corridor bodies after the free
        // prefix rewrote pure-major W,W,SW onto 33,100; native wall-follows
        // SW,SW,W onto 33,101. First gold path plans still soft-clear
        // residual (XHuman 12 1554/1561).
        GameMap map = new GameMap(50, 120, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        UnitType mineType = new UnitType("unit-gold-mine");
        mineType.setTileSize(3, 3);
        mineType.setHitPoints(25_500);
        mineType.setBuilding(true);
        mineType.setLandUnit(true);
        mineType.setGivesResource(UnitType.Resource.GOLD);
        mineType.setCanHarvest(true);
        Unit mine = world.createUnit(mineType, World.NEUTRAL_PLAYER, 28, 99);
        assertTrue(mine != null, "the gold mine must place");
        mine.setResourcesHeld(25_500);
        UnitType peasantType = new UnitType("unit-peasant");
        peasantType.setTileSize(1, 1);
        peasantType.setHitPoints(30);
        peasantType.setSpeed(10);
        peasantType.setLandUnit(true);
        ResourceInfo gold = new ResourceInfo(UnitType.Resource.GOLD);
        gold.setCapacity(100);
        gold.setWaitAtResource(150);
        gold.setWaitAtDepot(100);
        peasantType.gathering().put(UnitType.Resource.GOLD, gold);
        for (int[] seat : new int[][] {{30, 101}, {31, 101}, {32, 101}}) {
            Unit ally = world.createUnit(peasantType, 0, seat[0], seat[1]);
            assertTrue(ally != null, "corridor ally must place at "
                    + seat[0] + "," + seat[1]);
            ally.setOrder(Unit.Order.HARVEST);
            ally.setResourceUnit(mine);
            ally.setOffset(2, 0);
            ally.clearPath();
            assertTrue(ally.isMoving(),
                    "corridor ally must look mid-pixel residual");
            assertEquals(0, ally.pathLength(),
                    "corridor ally must be residual-only at replan");
        }
        Unit walker = world.createUnit(peasantType, 0, 34, 100);
        assertTrue(walker != null, "the replan walker must place");
        walker.setOrder(Unit.Order.HARVEST);
        walker.setResourceUnit(mine);
        int[] approach = world.battleNetApproachPoint(walker, mine);
        assertEquals(30, approach[0], "approach x is the mine's right face");
        assertEquals(100, approach[1], "approach y is the mine mid-row");
        // Drive walkTowards free-prefix replan (spent after clearPath) so the
        // test fails on pre-fix HEAD without needing a 3-arg path API.
        walker.clearPath();
        walker.setBattleNetGoldFreePrefix(true);
        walker.setBattleNetGoldFreePrefixLength(3);
        walker.setRouteSpent(true);
        world.movement.walkTowards(walker, mine);
        assertTrue(walker.pathLength() > 0,
                "the gold free-prefix replan must seal a route");
        assertEquals(Direction.fromDelta(-1, 1), walker.peekHeading(),
                "first replan step must be south-west around residual allies, "
                        + "not pure west onto 33,100");
    }

    @Test
    @DisplayName("a wood free-prefix of three steps replans without an action-23 delay")
    void aWoodFreePrefixOfThreeStepsReplansWithoutAnAction23Delay() {
        // XHuman 2 peon 1530 free-prefix NW,N,NW onto 92,100 toward wood
        // 87,95; residual settles and native steps NW at fixture 50. Delay 2
        // on every spent free-prefix held Java through 52 (step@53).
        GameMap map = new GameMap(40, 40, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        // Forest goal mass around 20,20; peon starts south-east.
        for (int x = 18; x <= 22; x++) {
            for (int y = 18; y <= 22; y++) {
                map.field(x, y).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
            }
        }
        World world = new World(map);
        UnitType peonType = new UnitType("unit-peon");
        peonType.setTileSize(1, 1);
        peonType.setHitPoints(30);
        peonType.setSpeed(10);
        peonType.setLandUnit(true);
        peonType.setCanAttack(true);
        ResourceInfo wood = new ResourceInfo(UnitType.Resource.WOOD);
        wood.setCapacity(100);
        wood.setWaitAtResource(25);
        wood.setStep(10);
        wood.setTerrainHarvester(true);
        peonType.gathering().put(UnitType.Resource.WOOD, wood);
        AnimationSet animations = new AnimationSet("unit-peon");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", java.util.List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", java.util.List.of(
                        "frame 0", "move 8", "wait 1")));
        peonType.setAnimationSet(animations);
        Unit peon = world.createUnit(peonType, 0, 26, 26);
        assertTrue(peon != null, "the peon must place");
        assertTrue(world.orderHarvest(peon, 20, 20),
                "the wood order must be accepted");
        // Simulate free-prefix residual settle: three-step path spent, not
        // beside the tree (cheb from 24,24 to 20,20 is 4).
        peon.setTile(24, 24);
        peon.clearPath();
        peon.setRouteSpent(true);
        peon.setBattleNetGoldFreePrefix(true);
        peon.setBattleNetGoldFreePrefixLength(3);
        peon.setOffset(0, 0);
        peon.setWalkHolding(false);
        world.harvest.walkToWood(peon);
        assertEquals(0, peon.battleNetOrderDelay(),
                "mid-journey free-prefix of length 3 must not arm action-23 delay 2");
        assertTrue(peon.pathLength() > 0 || peon.isMoving(),
                "mid-journey free-prefix must replan or step on the settle visit");
    }
}
