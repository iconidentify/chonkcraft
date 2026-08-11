package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.ResourceInfo;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.unit.UnitType.Resource;
import org.junit.jupiter.api.Test;

/** Tests for resources, gathering, and the player bank. */
class EconomyTest {

    private static GameMap grass(int size) {
        Tileset tileset = new Tileset();
        // A cleared-forest tile the chopper can leave behind.
        tileset.setTile(1, new Tileset.Tile(1, TileFlag.LAND_ALLOWED, 1, 0));
        tileset.setSpecial("removed-tree", 1);

        GameMap map = new GameMap(size, size, tileset);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static AnimationSet walker() {
        AnimationSet set = new AnimationSet("walker");
        set.put(AnimationSet.State.STILL, Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move",
                List.of("frame 0", "move 16", "wait 1", "frame 5", "move 16", "wait 1")));
        return set;
    }

    /** A peasant that can carry gold and wood. */
    private static UnitType peasant() {
        UnitType type = new UnitType("unit-peasant");
        type.setTileSize(1, 1);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setAnimationSet(walker());

        ResourceInfo gold = new ResourceInfo(Resource.GOLD);
        gold.setCapacity(100);
        gold.setWaitAtResource(2);
        gold.setWaitAtDepot(2);
        type.gathering().put(Resource.GOLD, gold);

        ResourceInfo wood = new ResourceInfo(Resource.WOOD);
        wood.setCapacity(100);
        wood.setWaitAtResource(2);
        wood.setWaitAtDepot(2);
        wood.setTerrainHarvester(true);
        type.gathering().put(Resource.WOOD, wood);
        return type;
    }

    private static UnitType townHall() {
        UnitType type = new UnitType("unit-town-hall");
        type.setTileSize(4, 4);
        type.setHitPoints(1200);
        type.setBuilding(true);
        type.setSupply(1);
        type.stores().add(Resource.GOLD);
        type.stores().add(Resource.WOOD);
        return type;
    }

    private static UnitType goldMine(int amount) {
        UnitType type = new UnitType("unit-gold-mine");
        type.setTileSize(3, 3);
        // A mine's remaining ore rides in its hit points, as upstream does.
        type.setHitPoints(amount);
        type.setBuilding(true);
        // What makes it a mine, said the way the data says it:
        // GivesResource = "gold", CanHarvest = true (scripts/units.legacy-declaration:288).
        // The engine used to decide from the identifier, so a fixture that was
        // merely named after a mine was one.
        type.setGivesResource(Resource.GOLD);
        type.setCanHarvest(true);
        return type;
    }

    /** Runs until the player has banked something, or gives up. */
    private static int runUntilBanked(World world, int player, Resource resource, int maxCycles) {
        for (int cycle = 0; cycle < maxCycles; cycle++) {
            world.tick();
            if (world.player(player).get(resource) > 0) {
                return cycle + 1;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------ the bank

    @Test
    void aPlayerBanksAndSpends() {
        Player player = new Player(0, net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.PERSON,
                net.chonkbase.chonkcraft.data.map.PudMap.Race.HUMAN);
        player.set(Resource.GOLD, 1000);
        player.set(Resource.WOOD, 500);

        Map<Resource, Integer> farm = Map.of(Resource.TIME, 100, Resource.GOLD, 500, Resource.WOOD, 250);
        assertTrue(player.canAfford(farm));
        assertTrue(player.pay(farm));
        assertEquals(500, player.get(Resource.GOLD));
        assertEquals(250, player.get(Resource.WOOD));
        // Time is a build duration, not a resource, and is never deducted.
        assertEquals(0, player.get(Resource.TIME));
    }

    @Test
    void anUnaffordableCostDeductsNothing() {
        Player player = new Player(0, net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.PERSON,
                net.chonkbase.chonkcraft.data.map.PudMap.Race.HUMAN);
        player.set(Resource.GOLD, 100);
        player.set(Resource.WOOD, 400);

        assertFalse(player.pay(Map.of(Resource.GOLD, 500, Resource.WOOD, 250)));
        assertEquals(100, player.get(Resource.GOLD), "gold should be untouched");
        assertEquals(400, player.get(Resource.WOOD), "wood should be untouched");
    }

    @Test
    void supplyAndDemandComeFromWhatIsOwned() {
        World world = new World(grass(30));
        UnitType peasantType = peasant();
        peasantType.setDemand(1);

        world.createUnit(townHall(), 0, 2, 2);
        world.createUnit(peasantType, 0, 8, 2);
        world.createUnit(peasantType, 0, 9, 2);
        world.recalculateSupply();

        assertEquals(1, world.player(0).supply(), "one hall provides one supply");
        assertEquals(2, world.player(0).demand(), "two peasants demand two");
        assertFalse(world.player(0).hasSupplyRoom(1));
    }

    // ------------------------------------------------------------ gathering

    @Test
    void aWorkerMinesGoldAndBanksIt() {
        World world = new World(grass(30));
        world.createUnit(townHall(), 0, 2, 2);
        world.createUnit(goldMine(50_000), 15, 12, 2);
        Unit worker = world.createUnit(peasant(), 0, 7, 3);

        assertTrue(world.orderHarvest(worker, 12, 2));
        assertEquals(Unit.Order.HARVEST, worker.order());

        int cycles = runUntilBanked(world, 0, Resource.GOLD, 4000);
        assertTrue(cycles > 0, "the worker never banked any gold");
        assertEquals(100, world.player(0).get(Resource.GOLD), "a trip should bank one full load");
    }

    @Test
    void theWorkerKeepsGoingBackForMore() {
        World world = new World(grass(30));
        world.createUnit(townHall(), 0, 2, 2);
        world.createUnit(goldMine(50_000), 15, 12, 2);
        Unit worker = world.createUnit(peasant(), 0, 7, 3);
        world.orderHarvest(worker, 12, 2);

        for (int cycle = 0; cycle < 12_000; cycle++) {
            world.tick();
        }
        assertTrue(world.player(0).get(Resource.GOLD) >= 300,
                "expected several trips, banked " + world.player(0).get(Resource.GOLD));
    }

    @Test
    void miningDrainsTheMine() {
        World world = new World(grass(30));
        world.createUnit(townHall(), 0, 2, 2);
        Unit mine = world.createUnit(goldMine(250), 15, 12, 2);
        Unit worker = world.createUnit(peasant(), 0, 7, 3);
        world.orderHarvest(worker, 12, 2);

        // Run past the mine's death: the last part-load is still in the
        // worker's hands and has to be walked home.
        for (int cycle = 0; cycle < 20_000; cycle++) {
            world.tick();
        }
        assertFalse(mine.isAlive(), "the mine should be worked out");
        assertEquals(250, world.player(0).get(Resource.GOLD), "all of it should have been banked");
    }

    @Test
    void choppingWoodClearsTheForestSquare() {
        World world = new World(grass(30));
        world.createUnit(townHall(), 0, 2, 2);
        Unit worker = world.createUnit(peasant(), 0, 8, 8);

        // A stand of forest to chop.
        world.map().field(12, 8).addFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
        assertTrue(world.map().field(12, 8).isForest());

        assertTrue(world.orderHarvest(worker, 12, 8));
        int cycles = runUntilBanked(world, 0, Resource.WOOD, 6000);

        assertTrue(cycles > 0, "the worker never banked any wood");
        assertEquals(100, world.player(0).get(Resource.WOOD));
        // The square is now cleared ground, and walkable.
        assertFalse(world.map().field(12, 8).isForest(), "the trees should be gone");
        assertTrue(world.map().field(12, 8).isLandPassable(), "cleared ground should be walkable");
    }

    @Test
    void aWorkerWithNowhereToUnloadStops() {
        // No town hall on the map.
        World world = new World(grass(30));
        world.createUnit(goldMine(50_000), 15, 12, 2);
        Unit worker = world.createUnit(peasant(), 0, 7, 3);
        world.orderHarvest(worker, 12, 2);

        for (int cycle = 0; cycle < 4000; cycle++) {
            world.tick();
        }
        assertEquals(0, world.player(0).get(Resource.GOLD));
        assertEquals(Unit.Order.STILL, worker.order(), "the worker should give up, not spin");
    }

    @Test
    void aGoallessTerrainOrderKeepsItsFinishedLabelUntilTheNextTurn() {
        World world = new World(grass(30));
        world.map().field(12, 8).addFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
        Unit worker = world.createUnit(peasant(), 0, 11, 8);
        world.orderHarvest(worker, 12, 8);

        for (int cycle = 0; cycle < 4000 && !worker.orderFinished(); cycle++) {
            world.tick();
        }
        assertTrue(worker.orderFinished(), "the chopper never gave up its missing depot");
        assertEquals(Unit.Order.HARVEST, worker.order(),
                "a freshly finished COrder_Resource remains current for this cycle");

        world.tick();
        assertEquals(Unit.Order.STILL, worker.order(),
                "HandleUnitAction should pop the finished resource order next turn");
    }

    @Test
    void aUnitThatCannotGatherRefusesTheOrder() {
        World world = new World(grass(30));
        world.createUnit(goldMine(50_000), 15, 12, 2);

        UnitType footman = new UnitType("unit-footman");
        footman.setTileSize(1, 1);
        footman.setHitPoints(60);
        footman.setSpeed(10);
        footman.setLandUnit(true);
        Unit soldier = world.createUnit(footman, 0, 7, 3);

        assertFalse(world.orderHarvest(soldier, 12, 2));
    }

    @Test
    void harvestingEmptyGroundIsRefused() {
        World world = new World(grass(30));
        world.createUnit(townHall(), 0, 2, 2);
        Unit worker = world.createUnit(peasant(), 0, 7, 3);
        assertFalse(world.orderHarvest(worker, 20, 20), "there is nothing there to gather");
    }
}
