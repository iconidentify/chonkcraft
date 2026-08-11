package net.chonkbase.chonkcraft.engine.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** The complete roster must boot from the JAR and owned pack alone. */
class NativeRosterRealDataTest {
    @Test
    void rosterAnimationsAndPresentationNeedNoScriptTree() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, "CHONKCRAFT_ASSET_PACK is required");
        GameData data = new GameData(assets);

        var types = data.unitTypes().types();
        assertEquals(143, types.size());
        assertTrue(data.animationSets().sets().size() >= 50);
        for (UnitType type : types.values()) {
            if (!type.animations().isEmpty()) {
                assertNotNull(type.animationSet(), type.ident() + " animation " + type.animations());
            }
            assertTrue(type.name() != null && !type.name().isBlank(), type.ident() + " has no name");
        }

        UnitType footman = types.get("unit-footman");
        assertNotNull(footman);
        assertEquals("Footman", footman.name());
        assertEquals(60, footman.hitPoints());
        assertEquals(600, footman.costs().get(UnitType.Resource.GOLD));
        assertEquals(4, footman.animationSet().states().size());

        UnitType critter = types.get("unit-critter");
        assertNotNull(critter);
        assertEquals(10, critter.clicksToExplode());
    }
}
