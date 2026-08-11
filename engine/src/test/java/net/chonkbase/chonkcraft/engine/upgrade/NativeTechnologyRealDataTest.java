package net.chonkbase.chonkcraft.engine.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** The global technology model loads with the pack and no ChonkCraft checkout. */
class NativeTechnologyRealDataTest {

    @Test
    void upgradesAndDependenciesNeedNoScriptTree() throws Exception {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, "No authenticated BNE pack configured");
        try (assets) {
            GameData data = new GameData(assets);
            UpgradeCatalog catalog = data.upgrades();
            assertEquals(52, catalog.upgrades().size());
            assertEquals(66, catalog.dependencies().size());
            assertEquals("unit-paladin",
                    catalog.upgrades().get("upgrade-paladin").convertTo());

        }
    }
}
