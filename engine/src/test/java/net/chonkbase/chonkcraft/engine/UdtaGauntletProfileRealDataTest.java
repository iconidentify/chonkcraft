package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.map.PudReader;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Gauntlet's custom footman is 900 hit points on that map only.
 */
class UdtaGauntletProfileRealDataTest {

    private static GameData load() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II retail assets configured.");
        return new GameData(assets);
    }

    private static String findMap(AssetSource assets, String needle) {
        String wanted = needle.toLowerCase();
        for (String name : assets.mapNames()) {
            if (name.toLowerCase().contains(wanted)) {
                return name;
            }
        }
        return null;
    }

    @Test
    @DisplayName("a gauntlet footman is born at nine hundred and the catalog stays at sixty")
    void aGauntletFootmanIsBornAtNineHundredAndTheCatalogStaysAtSixty() {
        GameData data = load();
        String gauntletName = findMap(data.source(), "gauntlet");
        Assumptions.assumeTrue(gauntletName != null, "Gauntlet is not in this pack");
        String gardenName = findMap(data.source(), "garden of war");
        Assumptions.assumeTrue(gardenName != null, "Garden of War is not in this pack");

        UnitType catalog = data.unitTypes().types().get("unit-footman");
        int stock = catalog.hitPoints();
        assertEquals(60, stock, "the stock footman is not 60 before any map loads");

        PudMap gauntlet = PudReader.read(data.source().map(gauntletName));
        assertTrue(!gauntlet.unitData().useDefaults(),
                "Gauntlet must store its own unit table");
        assertEquals(900, gauntlet.unitData().hitPoints(0),
                "Gauntlet's UDTA footman column is not 900");

        World first = new World(
                GameMap.from(gauntlet, data.loadTileset(gauntlet.tileset()).tileset()),
                Player.from(gauntlet));
        data.configureWorld(first, gauntlet);
        data.populate(first, gauntlet);

        UnitType local = first.registeredUnitType("unit-footman");
        assertEquals(900, local.hitPoints(),
                "Gauntlet's world table did not carry the 900-HP footman");
        Unit footman = null;
        for (Unit unit : first.unitsSnapshot()) {
            if (unit.isAlive() && "unit-footman".equals(unit.type().ident())) {
                footman = unit;
                break;
            }
        }
        if (footman == null) {
            // The scenario buffs the type even when it places none. Birth
            // still has to go through the world's table, which is how a
            // later train or a map that does place one is born.
            for (int y = 1; y < first.map().height() - 1 && footman == null; y++) {
                for (int x = 1; x < first.map().width() - 1 && footman == null; x++) {
                    footman = first.createUnit(local, 0, x, y);
                }
            }
        }
        assertTrue(footman != null, "no tile would take a Gauntlet footman");
        assertEquals(900, footman.hitPoints(),
                "the Gauntlet footman was born on the catalog "
                        + stock + " instead of the map 900");
        assertEquals(900, footman.type().hitPoints(),
                "the world's footman type did not carry Gauntlet's maximum");
        assertEquals(stock, catalog.hitPoints(),
                "loading Gauntlet mutated the shared footman catalog");

        PudMap garden = PudReader.read(data.source().map(gardenName));
        World second = new World(
                GameMap.from(garden, data.loadTileset(garden.tileset()).tileset()),
                Player.from(garden));
        data.configureWorld(second, garden);
        assertEquals(stock, catalog.hitPoints(),
                "loading Garden of War after Gauntlet left the catalog changed");
        assertEquals(stock, second.registeredUnitType("unit-footman").hitPoints(),
                "Garden of War inherited Gauntlet's 900-HP footman");
    }
}
