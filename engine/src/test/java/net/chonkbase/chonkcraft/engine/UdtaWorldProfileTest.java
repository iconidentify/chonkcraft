package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.unit.UnitType.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Per-map UDTA prices debit the player without rewriting the catalog.
 */
class UdtaWorldProfileTest {

    @Test
    @DisplayName("a custom udta gold cost is charged and the catalog stays put")
    void aCustomUdtaGoldCostIsChargedAndTheCatalogStaysPut() {
        GameMap map = grass(16);
        World world = new World(map);
        UnitType hallType = building("unit-human-barracks");
        UnitType footman = new UnitType("unit-footman");
        footman.setTileSize(1, 1);
        footman.setHitPoints(60);
        footman.setDemand(1);
        footman.costs().put(Resource.GOLD, 600);
        footman.costs().put(Resource.TIME, 60);
        world.setTrainers(java.util.Map.of("unit-footman",
                java.util.Set.of("unit-human-barracks")));

        int[] empty = new int[110];
        int[] goldTens = new int[110];
        int[] times = new int[110];
        goldTens[0] = 20;
        times[0] = 20;
        world.setBattleNetUnitProfile(new PudMap.PudUnitData(
                false, empty, empty, times, goldTens, empty, empty, empty,
                empty, empty, empty));

        Unit hall = world.createUnit(hallType, 0, 4, 4);
        world.player(0).set(Resource.GOLD, 400);

        assertTrue(world.orderTrain(hall, footman),
                "the cheaper Rescue-style price was refused");
        assertEquals(200, world.player(0).get(Resource.GOLD),
                "the player was charged the catalog 600 instead of the map 200");
        assertEquals(20 * World.PROGRESS_PER_TIME_UNIT, hall.progressGoal(),
                "training used the catalog 60 instead of the map 20");
        assertEquals(600, footman.costs().get(Resource.GOLD),
                "applying a custom UDTA mutated the shared unit catalog");
        assertEquals(60, footman.costs().get(Resource.TIME),
                "applying a custom UDTA mutated the catalog train time");
    }

    @Test
    @DisplayName("a defaults udta table leaves the catalog price in the bank")
    void aDefaultsUdtaTableLeavesTheCatalogPriceInTheBank() {
        GameMap map = grass(16);
        World world = new World(map);
        UnitType hallType = building("unit-human-barracks");
        UnitType footman = new UnitType("unit-footman");
        footman.setTileSize(1, 1);
        footman.setHitPoints(60);
        footman.setDemand(1);
        footman.costs().put(Resource.GOLD, 600);
        footman.costs().put(Resource.TIME, 60);
        world.setTrainers(java.util.Map.of("unit-footman",
                java.util.Set.of("unit-human-barracks")));

        int[] empty = new int[110];
        int[] goldTens = new int[110];
        goldTens[0] = 20;
        world.setBattleNetUnitProfile(new PudMap.PudUnitData(
                true, empty, empty, empty, goldTens, empty, empty, empty,
                empty, empty, empty));

        Unit hall = world.createUnit(hallType, 0, 4, 4);
        world.player(0).set(Resource.GOLD, 600);

        assertTrue(world.orderTrain(hall, footman),
                "the stock-price train was refused");
        assertEquals(0, world.player(0).get(Resource.GOLD),
                "a use-defaults UDTA still overlaid the unused tens column");
        assertEquals(600, footman.costs().get(Resource.GOLD),
                "the catalog gold drifted while the defaults table was installed");
    }

    @Test
    @DisplayName("a custom udta gold cost is what the builder has to have")
    void aCustomUdtaGoldCostIsWhatTheBuilderHasToHave() {
        World world = new World(grass(16));
        UnitType peasant = new UnitType("unit-peasant");
        peasant.setTileSize(1, 1);
        peasant.setHitPoints(30);
        peasant.setLandUnit(true);
        UnitType farm = new UnitType("unit-farm");
        farm.setTileSize(2, 2);
        farm.setHitPoints(400);
        farm.setBuilding(true);
        farm.costs().put(Resource.GOLD, 500);
        farm.costs().put(Resource.WOOD, 250);
        farm.costs().put(Resource.TIME, 100);

        int[] empty = new int[110];
        int[] goldTens = new int[110];
        goldTens[58] = 10;
        world.setBattleNetUnitProfile(new PudMap.PudUnitData(
                false, empty, empty, empty, goldTens, empty, empty, empty,
                empty, empty, empty));

        Unit worker = world.createUnit(peasant, 0, 2, 2);
        world.player(0).set(Resource.GOLD, 200);
        world.player(0).set(Resource.WOOD, 250);

        assertTrue(world.orderBuild(worker, farm, 6, 6),
                "the cheaper farm price was refused");
        assertEquals(200, world.player(0).get(Resource.GOLD),
                "the build order billed the catalog before the foundation");
        assertEquals(500, farm.costs().get(Resource.GOLD),
                "applying a custom UDTA mutated the farm catalog price");
    }

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType building(String ident) {
        UnitType type = new UnitType(ident);
        type.setTileSize(3, 3);
        type.setHitPoints(800);
        type.setBuilding(true);
        type.setSupply(5);
        return type;
    }
}
