package net.chonkbase.chonkcraft.engine.construction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Construction animations resolve from the authenticated pack without retired scripting language. */
class NativeConstructionRealDataTest {

    @Test
    void allTilesetsResolveWithNoScriptTree() throws Exception {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, "No authenticated BNE pack configured");
        try (assets) {
            GameData data = new GameData(assets);
            for (String tileset : List.of("summer", "winter", "wasteland", "swamp")) {
                ConstructionCatalog catalog = data.constructions(tileset);
                assertEquals(12, catalog.constructions().size(), tileset);
                for (ConstructionCatalog.Construction construction
                        : catalog.constructions().values()) {
                    assertNotNull(data.sprite(construction.sprite()),
                            tileset + ": " + construction.ident() + " -> "
                                    + construction.sprite());
                }
            }

        }
    }
}
