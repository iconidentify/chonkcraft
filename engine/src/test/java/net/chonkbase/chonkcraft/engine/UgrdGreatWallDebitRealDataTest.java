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
import net.chonkbase.chonkcraft.engine.upgrade.Upgrade;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Great Wall's stored battle-axe2 price is charged without rewriting the catalog.
 */
class UgrdGreatWallDebitRealDataTest {

    @Test
    @DisplayName("great wall charges five hundred gold and fifty time for battle-axe2")
    void greatWallChargesFiveHundredGoldAndFiftyTimeForBattleAxe2() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II retail assets configured.");
        try (assets) {
            String wallName = null;
            for (String name : assets.mapNames()) {
                if (name.toLowerCase().contains("grtwall")) {
                    wallName = name;
                    break;
                }
            }
            Assumptions.assumeTrue(wallName != null, "Great Wall is not in this pack");
            GameData data = new GameData(assets);
            Upgrade axe2 = data.upgrades().upgrades().get("upgrade-battle-axe2");
            assertEquals(1500, axe2.costs().get(Resource.GOLD),
                    "the catalog battle-axe2 gold drifted before the map loaded");
            assertEquals(250, axe2.costs().get(Resource.TIME),
                    "the catalog battle-axe2 time drifted before the map loaded");

            PudMap source = PudReader.read(assets.map(wallName));
            assertTrue(!source.upgradeData().useDefaults(),
                    "Great Wall must store its own research table");
            assertEquals(500, source.upgradeData().gold(3),
                    "Great Wall's stored battle-axe2 gold is not 500");
            assertEquals(50, source.upgradeData().time(3),
                    "Great Wall's stored battle-axe2 time is not 50");

            GameMap map = new GameMap(20, 20, data.loadTileset(source.tileset()).tileset());
            for (int y = 0; y < 20; y++) {
                for (int x = 0; x < 20; x++) {
                    map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
                }
            }
            World world = new World(map);
            data.configureWorld(world, source);

            UnitType smithType = world.registeredUnitType("unit-orc-blacksmith");
            if (smithType == null) {
                smithType = data.unitTypes().types().get("unit-orc-blacksmith");
            }
            Unit smith = world.createUnit(smithType, 0, 4, 4);
            world.player(0).set(Resource.GOLD, 2000);
            world.player(0).set(Resource.WOOD, 2000);

            assertTrue(world.orderResearch(smith, "upgrade-battle-axe1"),
                    "Great Wall's battle-axe1 was refused");
            assertEquals(1500, world.player(0).get(Resource.GOLD),
                    "battle-axe1 did not take the map 500 gold");
            assertEquals(50 * World.PROGRESS_PER_TIME_UNIT, smith.progressGoal(),
                    "battle-axe1 used the catalog 200 instead of the map 50");

            world.upgrades(0).complete("upgrade-battle-axe1");
            smith.setResearching(null);
            smith.setProgress(0);
            smith.setOrder(Unit.Order.STILL);

            assertTrue(world.orderResearch(smith, "upgrade-battle-axe2"),
                    "the cheaper Great Wall battle-axe2 was refused after axe1");
            assertEquals(1000, world.player(0).get(Resource.GOLD),
                    "the player was charged the catalog 1500 instead of the map 500");
            assertEquals(50 * World.PROGRESS_PER_TIME_UNIT, smith.progressGoal(),
                    "battle-axe2 used the catalog 250 instead of the map 50");
            assertEquals(1500, axe2.costs().get(Resource.GOLD),
                    "applying Great Wall mutated the shared upgrade catalog");
            assertEquals(250, axe2.costs().get(Resource.TIME),
                    "applying Great Wall mutated the catalog research time");
        }
    }
}
