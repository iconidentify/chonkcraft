package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.unit.UnitType.Resource;
import net.chonkbase.chonkcraft.engine.upgrade.Upgrade;
import net.chonkbase.chonkcraft.engine.upgrade.UpgradeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Per-map UGRD prices debit the player without rewriting the catalog.
 */
class UgrdWorldProfileTest {

    @Test
    @DisplayName("a custom ugrd gold cost is charged and the catalog stays put")
    void aCustomUgrdGoldCostIsChargedAndTheCatalogStaysPut() {
        UpgradeSet catalog = new UpgradeSet();
        Upgrade upgrade = catalog.getOrCreate("upgrade-battle-axe1");
        upgrade.costs().put(Resource.GOLD, 1500);
        upgrade.costs().put(Resource.TIME, 200);

        GameMap map = new GameMap(16, 16, new Tileset());
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        world.setUpgrades(catalog);
        int[] time = new int[52];
        int[] gold = new int[52];
        int[] lumber = new int[52];
        int[] oil = new int[52];
        time[2] = 80;
        gold[2] = 500;
        world.setBattleNetUpgradeProfile(
                new PudMap.PudUpgradeData(false, time, gold, lumber, oil));

        UnitType smithType = new UnitType("unit-orc-blacksmith");
        smithType.setTileSize(3, 3);
        smithType.setHitPoints(800);
        smithType.setBuilding(true);
        Unit smith = world.createUnit(smithType, 0, 4, 4);
        world.player(0).set(Resource.GOLD, 900);

        assertTrue(world.orderResearch(smith, "upgrade-battle-axe1"),
                "the cheaper Great Wall price was refused");
        assertEquals(400, world.player(0).get(Resource.GOLD),
                "the player was charged the catalog 1500 instead of the map 500");
        assertEquals(1500, upgrade.costs().get(Resource.GOLD),
                "applying Great Wall mutated the shared upgrade catalog");
    }
}
