package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.map.PudReader;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.unit.UnitType.Resource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sacrifice's stored peon price is charged without rewriting the catalog.
 */
class UdtaSacrificeDebitRealDataTest {

    @Test
    @DisplayName("sacrifice charges fifty gold and twenty time for a peon")
    void sacrificeChargesFiftyGoldAndTwentyTimeForAPeon() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II retail assets configured.");
        try (assets) {
            String name = null;
            for (String mapName : assets.mapNames()) {
                if (mapName.toLowerCase().contains("sacrific")) {
                    name = mapName;
                    break;
                }
            }
            Assumptions.assumeTrue(name != null, "Sacrifice is not in this pack");
            GameData data = new GameData(assets);
            UnitType catalog = data.unitTypes().types().get("unit-peon");
            assertEquals(400, catalog.costs().get(Resource.GOLD),
                    "the catalog peon gold drifted before the map loaded");
            assertEquals(45, catalog.costs().get(Resource.TIME),
                    "the catalog peon time drifted before the map loaded");

            PudMap source = PudReader.read(assets.map(name));
            assertTrue(!source.unitData().useDefaults(),
                    "Sacrifice must store its own unit table");
            assertEquals(50, source.unitData().gold(3),
                    "Sacrifice's stored peon gold is not 50");
            assertEquals(20, source.unitData().time(3),
                    "Sacrifice's stored peon time is not 20");

            GameMap map = new GameMap(20, 20, data.loadTileset(source.tileset()).tileset());
            for (int y = 0; y < 20; y++) {
                for (int x = 0; x < 20; x++) {
                    map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
                }
            }
            World world = new World(map);
            data.configureWorld(world, source);

            UnitType hallType = world.registeredUnitType("unit-great-hall");
            UnitType peon = world.registeredUnitType("unit-peon");
            if (hallType == null) {
                hallType = data.unitTypes().types().get("unit-great-hall");
            }
            if (peon == null) {
                peon = catalog;
            }
            hallType.setSupply(5);
            Unit hall = world.createUnit(hallType, 0, 4, 4);
            world.player(0).set(Resource.GOLD, 400);

            assertTrue(world.orderTrain(hall, peon),
                    "the cheaper Sacrifice peon was refused");
            assertEquals(350, world.player(0).get(Resource.GOLD),
                    "the player was charged the catalog 400 instead of the map 50");
            assertEquals(20 * World.BATTLE_NET_TRAIN_TICKS_PER_TIME, hall.progressGoal(),
                    "training used the catalog 45 instead of the map 20");
            assertEquals(400, catalog.costs().get(Resource.GOLD),
                    "applying Sacrifice mutated the shared peon catalog");
            assertEquals(45, catalog.costs().get(Resource.TIME),
                    "applying Sacrifice mutated the catalog train time");
        }
    }
}
