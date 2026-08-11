package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.unit.UnitType.Resource;
import net.chonkbase.chonkcraft.engine.upgrade.Upgrade;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Native upgrade costs must affect actual research. */
class UpgradeCostRealDataTest {

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    private static World world() {
        GameMap map = new GameMap(20, 20, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return new World(map);
    }

    @Test
    void declaredSwordCostIsChargedAndControlsResearchTime() {
        GameData data = load();
        Upgrade upgrade = data.upgrades().upgrades().get("upgrade-sword1");
        assertNotNull(upgrade);
        assertEquals(200, upgrade.costs().get(Resource.TIME));
        assertEquals(800, upgrade.costs().get(Resource.GOLD));

        World world = world();
        world.setUpgrades(data.upgrades().upgrades());
        UnitType smithType = new UnitType("unit-smith");
        smithType.setTileSize(3, 3);
        smithType.setHitPoints(800);
        smithType.setBuilding(true);
        Unit smith = world.createUnit(smithType, 0, 5, 5);
        world.player(0).set(Resource.GOLD, 900);

        assertTrue(world.orderResearch(smith, "upgrade-sword1"));
        assertEquals(100, world.player(0).get(Resource.GOLD));
        assertEquals(200 * 600, smith.progressGoal());
    }
}
