package net.chonkbase.chonkcraft.engine.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.generated.GeneratedButtons;
import net.chonkbase.chonkcraft.engine.generated.GeneratedIcons;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** The real pack can construct the command UI with no script tree at all. */
class NativeInterfaceRealDataTest {

    @Test
    void commandPanelNeedsOnlyJarDefinitionsAndRetailArt() throws Exception {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, "No authenticated BNE pack configured");
        try (assets) {
            Path deliberatelyMissing = Path.of("target/no-chonkcraft-script-tree-may-exist");
            GameData data = new GameData(assets);
            GameData.Interface ui = data.userInterface("summer");

            assertEquals(214, ui.buttons().size());
            assertEquals(GeneratedButtons.ROWS.size(), ui.buttons().size());
            assertEquals(198, ui.icons().frames().size());
            assertEquals(GeneratedIcons.FRAMES, ui.icons().frames());
            UiLayout.Layout human = data.uiLayout("human", 640, 480);
            UiLayout.Layout orc = data.uiLayout("orc", 1280, 800);
            assertEquals(9, human.buttons().size());
            assertEquals(9, orc.buttons().size());
            assertEquals(16, data.playerColours().ramps().size());
            assertTrue(data.fogOfWar().levels().isValid());

        }
    }
}
