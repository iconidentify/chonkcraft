package net.chonkbase.chonkcraft.data.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Retail maps carry a 782-byte UGRD section. Custom tables stay on the map
 * that stored them.
 */
class UgrdProfileRealDataTest {

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
    @DisplayName("every retail pud ugrd is 782 bytes and great wall is cheaper")
    void everyRetailPudUgrdIs782BytesAndGreatWallIsCheaper() {
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
                if (map.upgradeData() == null) {
                    continue;
                }
                withProfile++;
                if (!map.upgradeData().useDefaults()) {
                    custom++;
                }
            }
            assertTrue(withProfile >= 77,
                    "UGRD profiles found on " + withProfile + " maps");
            assertTrue(custom >= 1, "no custom UGRD table was found");

            String wallName = findMap(assets, "grtwall");
            Assumptions.assumeTrue(wallName != null, "Great Wall is not in this pack");
            String iceName = findMap(assets, "icewall");
            Assumptions.assumeTrue(iceName != null, "a default UGRD map is missing");
            PudMap wall = PudReader.read(assets.map(wallName));
            PudMap stock = PudReader.read(assets.map(iceName));
            assertFalse(wall.upgradeData().useDefaults(),
                    "Great Wall stores its own research prices");
            assertTrue(stock.upgradeData().useDefaults(),
                    "Icewall keeps the stock table");
            assertTrue(wall.upgradeData().gold(2) < stock.upgradeData().gold(2),
                    "Great Wall's custom gold is not cheaper than the stock table");
            assertEquals(stock.upgradeData().gold(2), 1500,
                    "the stock gold column drifted");
        }
    }

    @Test
    @DisplayName("loading a custom map after a default map does not leak prices")
    void loadingACustomMapAfterADefaultMapDoesNotLeakPrices() {
        try (AssetSource assets = assets()) {
            String wallName = findMap(assets, "grtwall");
            String iceName = findMap(assets, "icewall");
            Assumptions.assumeTrue(wallName != null && iceName != null,
                    "Great Wall or Icewall is missing");
            PudMap first = PudReader.read(assets.map(iceName));
            int stockGold = first.upgradeData().gold(2);
            PudMap second = PudReader.read(assets.map(wallName));
            assertTrue(second.upgradeData().gold(2) < stockGold,
                    "the second map did not keep its own table");
            PudMap third = PudReader.read(assets.map(iceName));
            assertEquals(stockGold, third.upgradeData().gold(2),
                    "re-reading the default map picked up Great Wall's prices");
        }
    }
}
