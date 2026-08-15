package net.chonkbase.chonkcraft.data.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Retail maps carry a 5696-byte UDTA section. Custom tables stay on the map
 * that stored them.
 */
class UdtaProfileRealDataTest {

    private static AssetSource assets() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II retail assets configured.");
        return assets;
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
    @DisplayName("every retail pud udta is 5696 bytes and rescue stores its own table")
    void everyRetailPudUdtaIs5696BytesAndRescueStoresItsOwnTable() {
        try (AssetSource assets = assets()) {
            List<String> maps = assets.mapNames();
            assertTrue(maps.size() >= 77, "the authenticated set names at least 77 maps");
            int withProfile = 0;
            int custom = 0;
            for (String name : maps) {
                byte[] bytes = assets.map(name);
                if (bytes == null) {
                    continue;
                }
                PudMap map = PudReader.read(bytes);
                if (map.unitData() == null) {
                    continue;
                }
                withProfile++;
                if (!map.unitData().useDefaults()) {
                    custom++;
                }
            }
            assertTrue(withProfile >= 77,
                    "UDTA profiles found on " + withProfile + " maps");
            assertTrue(custom >= 1, "no custom UDTA table was found");

            String gardenName = findMap(assets, "garden");
            Assumptions.assumeTrue(gardenName != null, "Garden of War is not in this pack");
            String rescueName = findMap(assets, "rescue");
            Assumptions.assumeTrue(rescueName != null, "Rescue is not in this pack");
            PudMap garden = PudReader.read(assets.map(gardenName));
            PudMap rescue = PudReader.read(assets.map(rescueName));
            assertNotNull(garden.unitData(), "Garden of War has no UDTA");
            assertNotNull(rescue.unitData(), "Rescue has no UDTA");
            assertTrue(garden.unitData().useDefaults(),
                    "Garden of War keeps the stock unit table");
            assertFalse(rescue.unitData().useDefaults(),
                    "Rescue stores its own unit table");
            assertEquals(600, garden.unitData().gold(0),
                    "stock footman gold is 600 (60 tens at UDTA 2118)");
            assertEquals(400, garden.unitData().gold(2),
                    "stock peasant gold is 400 (40 tens at UDTA 2118)");
            assertEquals(250, garden.unitData().lumber(58),
                    "stock farm lumber is 250 (25 tens at UDTA 2228)");
            assertEquals(garden.unitData().gold(0), rescue.unitData().gold(0),
                    "Rescue's footman gold is not the stock tens column");
            assertEquals(250, rescue.unitData().time(4),
                    "Rescue applies the stock 250 ballista time");
            assertEquals(120, garden.unitData().time(4),
                    "Garden of War's unused table still stores the older 120");
        }
    }

    @Test
    @DisplayName("loading a custom map after a default map does not leak unit prices")
    void loadingACustomMapAfterADefaultMapDoesNotLeakUnitPrices() {
        try (AssetSource assets = assets()) {
            String gardenName = findMap(assets, "garden");
            String rescueName = findMap(assets, "rescue");
            Assumptions.assumeTrue(gardenName != null && rescueName != null,
                    "Garden of War or Rescue is missing");
            PudMap first = PudReader.read(assets.map(gardenName));
            int stockGold = first.unitData().gold(0);
            PudMap second = PudReader.read(assets.map(rescueName));
            assertEquals(stockGold, second.unitData().gold(0),
                    "Rescue did not keep its own gold column");
            PudMap third = PudReader.read(assets.map(gardenName));
            assertEquals(stockGold, third.unitData().gold(0),
                    "re-reading Garden of War picked up Rescue's table");
            assertTrue(first.unitData().useDefaults(),
                    "Garden of War's use-defaults word flipped after Rescue");
            assertFalse(second.unitData().useDefaults(),
                    "Rescue's custom word was lost after Garden of War");
        }
    }
}
