package net.chonkbase.chonkcraft.engine.missile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Projectile behavior definitions load from the JAR without missiles.legacy-declaration. */
class NativeMissileRealDataTest {

    @Test
    void projectileCatalogNeedsNoScriptTree() throws Exception {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, "No authenticated BNE pack configured");
        try (assets) {
            GameData data = new GameData(assets);
            MissileCatalog catalog = data.missiles();
            assertEquals(35, catalog.types().size());
            assertEquals(3, catalog.burningBuildings().frames().size());
            assertTrue(catalog.burningBuildings().isValid());
            assertEquals(10, catalog.get("missile-blizzard").damageRandom());

        }
    }
}
