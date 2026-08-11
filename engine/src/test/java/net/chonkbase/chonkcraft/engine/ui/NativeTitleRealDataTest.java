package net.chonkbase.chonkcraft.engine.ui;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class NativeTitleRealDataTest {

    @Test
    void nativeSequenceResolvesEveryRetailEntryWithoutSyntheticArt() throws Exception {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, "No authenticated BNE pack configured");
        try (assets) {
            GameData data = new GameData(assets);
            for (TitleSequence.Screen screen : data.titleScreens()) {
                switch (screen.kind()) {
                    case BLACK -> assertTrue(screen.assetPath() == null,
                            "native black must not resolve through the pack");
                    case IMAGE -> {
                        assertNotNull(data.image(screen.assetPath()), screen.assetPath());
                        assertNotNull(data.paletteFor(screen.assetPath()), screen.assetPath());
                    }
                    case VIDEO -> assertNotNull(data.video(screen.assetPath()), screen.assetPath());
                }
            }
            assertTrue(data.unresolvedPaths().stream().noneMatch(path ->
                    path.contains("black_title")), "synthetic ChonkCraft art was requested");
        }
    }
}
