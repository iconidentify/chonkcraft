package net.chonkbase.chonkcraft.data.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every authenticated retail PUD carries the same UDTA/UGRD layouts.
 */
class PudFieldDifferentialRealDataTest {

    @Test
    @DisplayName("every retail pud carries a 782-byte ugrd and a bne udta")
    void everyRetailPudCarriesA782ByteUgrdAndABneUdta() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II retail assets configured.");
        try (assets) {
            List<String> maps = assets.mapNames();
            assertTrue(maps.size() >= 77,
                    "the authenticated set names at least 77 maps");
            int udta = 0;
            int ugrd = 0;
            int customUdta = 0;
            int customUgrd = 0;
            for (String name : maps) {
                byte[] bytes = assets.map(name);
                if (bytes == null) {
                    continue;
                }
                PudMap map = PudReader.read(bytes);
                if (map.unitData() != null) {
                    udta++;
                    int hp = map.unitData().hitPoints(0);
                    int gold = map.unitData().gold(0);
                    int time = map.unitData().time(0);
                    assertTrue(hp >= 0 && hp < 0x10000,
                            name + " footman hit points left the word");
                    assertTrue(gold >= 0 && gold <= 2500,
                            name + " footman gold left the known range");
                    assertTrue(time >= 0 && time <= 255,
                            name + " footman time left the byte");
                    if (!map.unitData().useDefaults()) {
                        customUdta++;
                    }
                }
                if (map.upgradeData() != null) {
                    ugrd++;
                    int swordTime = map.upgradeData().time(0);
                    int swordGold = map.upgradeData().gold(0);
                    assertEquals(200, swordTime,
                            name + " sword1 time is not the catalog 200");
                    assertEquals(800, swordGold,
                            name + " sword1 gold is not the catalog 800");
                    if (!map.upgradeData().useDefaults()) {
                        customUgrd++;
                    }
                }
            }
            assertTrue(udta >= 77, "UDTA profiles found on " + udta + " maps");
            assertTrue(ugrd >= 77, "UGRD profiles found on " + ugrd + " maps");
            assertTrue(customUdta >= 1, "no custom UDTA table was found");
            assertTrue(customUgrd >= 1, "no custom UGRD table was found");
        }
    }
}
