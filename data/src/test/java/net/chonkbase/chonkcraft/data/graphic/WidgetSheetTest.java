package net.chonkbase.chonkcraft.data.graphic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cutting the interface widgets out of their sheet.
 *
 * <p>The table is {@code GroupedGraphicsList[0]} in {@code wartool.h}. These
 * check the shape of it rather than the pixels, which need an installation.
 */
class WidgetSheetTest {

    /** A sheet tall enough for the whole group, filled with a known pattern. */
    private static IndexedImage sheet(int height) {
        IndexedImage image = new IndexedImage(320, height);
        byte[] pixels = image.pixels();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < 320; x++) {
                // The row number, so a crop can be checked against where it
                // claims to have come from.
                pixels[y * 320 + x] = (byte) (y % 251);
            }
        }
        return image;
    }

    @Test
    @DisplayName("the whole group is cut from a full-height sheet")
    void everyPieceIsCut() {
        Map<String, IndexedImage> pieces = WidgetSheet.cut(sheet(53 * 144));
        assertEquals(WidgetSheet.WIDGETS.size(), pieces.size());
        assertTrue(pieces.containsKey("button-large-normal"));
        assertTrue(pieces.containsKey("slider-knob"));
        assertTrue(pieces.containsKey("folder-up-pressed"));
    }

    @Test
    @DisplayName("the buttons are the sizes the menu scripts set")
    void buttonsAreTheDeclaredSizes() {
        Map<String, IndexedImage> pieces = WidgetSheet.cut(sheet(53 * 144));
        // addFullButton calls setSize(224, 28) and addHalfButton setSize(106, 28).
        IndexedImage full = pieces.get("button-large-normal");
        assertEquals(224, full.width());
        assertEquals(28, full.height());
        IndexedImage half = pieces.get("button-small-normal");
        assertEquals(106, half.width());
        assertEquals(28, half.height());
    }

    @Test
    @DisplayName("a piece comes from the row the table gives it")
    void cropsComeFromTheRightRow() {
        IndexedImage source = sheet(53 * 144);
        Map<String, IndexedImage> pieces = WidgetSheet.cut(source);
        // button-large-normal is row sixteen, so its first pixel row is the
        // sheet's row 16 * 144.
        int expected = (16 * 144) % 251;
        assertEquals(expected, pieces.get("button-large-normal").pixels()[0] & 0xFF);
    }

    @Test
    @DisplayName("the slider bars carry an offset of their own")
    void slidersAreNotFlushWithTheirRow() {
        Map<String, IndexedImage> pieces = WidgetSheet.cut(sheet(53 * 144));
        // The vertical bar starts twenty pixels down its row, not at the top;
        // the grayed one is row 41 and the normal one row 42.
        assertEquals((42 * 144 + 20) % 251,
                pieces.get("vslider-bar-normal").pixels()[0] & 0xFF);
        assertEquals(124, pieces.get("vslider-bar-normal").height());
        // The horizontal one is inset twenty pixels from the left instead.
        assertEquals(172, pieces.get("hslider-bar-normal").width());
    }

    @Test
    @DisplayName("a short sheet stops rather than reading past its end")
    void aShortSheetIsTruncated() {
        // The original release's sheet is shorter than the expansion's, and
        // ConvertGroupedGfu breaks out of its loop when a piece would run off
        // the bottom rather than reading rubbish.
        Map<String, IndexedImage> pieces = WidgetSheet.cut(sheet(10 * 144));
        assertNotNull(pieces.get("button-thin-small-pressed"));
        assertNull(pieces.get("folder-up-pressed"));
        assertTrue(pieces.size() < WidgetSheet.WIDGETS.size());
    }

    @Test
    @DisplayName("nothing is cut from nothing")
    void nullSheetYieldsNothing() {
        assertTrue(WidgetSheet.cut(null).isEmpty());
    }
}
