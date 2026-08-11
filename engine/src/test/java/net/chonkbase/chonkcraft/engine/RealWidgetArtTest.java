package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.data.graphic.WidgetSheet;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The interface widgets, read out of a real installation.
 *
 * <p>The conversion table's {@code D} rows were parsed from the beginning and
 * never decoded, so every {@code ui/*&#47;widgets/*} path answered null and the
 * menus were drawn with rectangles. These check the archive really does yield
 * the pieces the menu scripts ask for.
 */
class RealWidgetArtTest {

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    @Test
    @DisplayName("both races yield every widget in the group")
    void everyPieceDecodes() {
        GameData data = load();
        List<String> missing = new ArrayList<>();
        for (String race : List.of("human", "orc")) {
            for (WidgetSheet.Piece piece : WidgetSheet.WIDGETS) {
                String path = "ui/" + race + "/widgets/" + piece.name();
                IndexedImage image = data.widget(path);
                if (image == null || data.widgetPalette(path) == null) {
                    missing.add(path);
                    continue;
                }
                assertEquals(piece.width(), image.width(), path);
                assertEquals(piece.height(), image.height(), path);
            }
        }
        assertTrue(missing.isEmpty(), "these widgets did not decode: " + missing);
    }

    @Test
    @DisplayName("the two races are drawn with different palettes")
    void racesLookDifferent() {
        // Warcraft II's human interface is blue and its orc interface red. One
        // palette for both would be a plausible-looking mistake, so this is
        // worth pinning: the button art is the same pixels either way, and only
        // the palette tells them apart.
        GameData data = load();
        String human = "ui/human/widgets/button-large-normal";
        String orc = "ui/orc/widgets/button-large-normal";
        var humanImage = data.widget(human);
        var orcImage = data.widget(orc);
        assertNotNull(humanImage);
        assertNotNull(orcImage);

        var humanArt = humanImage.toBufferedImage(data.widgetPalette(human));
        var orcArt = orcImage.toBufferedImage(data.widgetPalette(orc));
        long humanBlue = 0;
        long humanRed = 0;
        long orcBlue = 0;
        long orcRed = 0;
        for (int y = 0; y < humanArt.getHeight(); y++) {
            for (int x = 0; x < humanArt.getWidth(); x++) {
                int h = humanArt.getRGB(x, y);
                int o = orcArt.getRGB(x, y);
                humanRed += (h >> 16) & 0xFF;
                humanBlue += h & 0xFF;
                orcRed += (o >> 16) & 0xFF;
                orcBlue += o & 0xFF;
            }
        }
        assertTrue(humanBlue > humanRed, "the human button should read as blue");
        assertTrue(orcRed > orcBlue, "the orc button should read as red");
    }

    @Test
    @DisplayName("the palette changes part way down the sheet")
    void theSecondPaletteIsUsed() {
        // ConvertGroupedGfu swaps palettes at the fourth piece. The greyscale
        // buttons above the swap and the coloured ones below it must not come
        // back drawn the same way.
        GameData data = load();
        var grey = data.widgetPalette("ui/human/widgets/button-grayscale-normal");
        var blue = data.widgetPalette("ui/human/widgets/button-large-normal");
        assertNotNull(grey);
        assertNotNull(blue);
        boolean same = true;
        for (int i = 0; i < 256 && same; i++) {
            same = grey.red(i) == blue.red(i)
                    && grey.green(i) == blue.green(i)
                    && grey.blue(i) == blue.blue(i);
        }
        assertTrue(!same,
                "the pieces above and below the swap share a palette, so the swap did not happen");
    }
}
