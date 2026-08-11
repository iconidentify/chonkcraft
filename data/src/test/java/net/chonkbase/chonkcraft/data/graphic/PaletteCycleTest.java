package net.chonkbase.chonkcraft.data.graphic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rotating palette that animates Warcraft II's water.
 *
 * <p>No pixel changes; a few ranges of the palette walk along themselves and
 * wrap. This is why a map can be rasterised once and still move, and why
 * leaving it out gives a game that looks right in a screenshot and dead in
 * motion.
 */
class PaletteCycleTest {

    private static Palette ramp() {
        byte[] vga = new byte[768];
        for (int i = 0; i < 256; i++) {
            // Distinct six-bit values so a rotation is visible.
            vga[i * 3] = (byte) (i % 64);
            vga[i * 3 + 1] = (byte) ((i * 3) % 64);
            vga[i * 3 + 2] = (byte) ((i * 7) % 64);
        }
        return Palette.fromVga(vga);
    }

    @Test
    @DisplayName("a range rotates by one and wraps")
    void aRangeRotates() {
        Palette before = ramp();
        Palette after = before.cycled(List.of(new int[] {10, 14}));

        // Every entry takes its neighbour's colour, and the first wraps to the
        // end. That is the whole of the effect.
        for (int i = 10; i < 14; i++) {
            assertEquals(before.rgb(i + 1), after.rgb(i), "entry " + i);
        }
        assertEquals(before.rgb(10), after.rgb(14), "the first colour wrapped to the end");
    }

    @Test
    @DisplayName("colours outside a range do not move")
    void everythingElseStaysPut() {
        Palette before = ramp();
        Palette after = before.cycled(List.of(new int[] {10, 14}));
        for (int i = 0; i < 256; i++) {
            if (i >= 10 && i <= 14) {
                continue;
            }
            assertEquals(before.rgb(i), after.rgb(i), "entry " + i + " should not have moved");
        }
    }

    @Test
    @DisplayName("a full lap returns the palette to where it started")
    void aLapReturns() {
        Palette start = ramp();
        List<int[]> range = List.of(new int[] {38, 42});
        Palette walked = start;
        for (int step = 0; step < 4; step++) {
            walked = walked.cycled(range);
            assertNotEquals(start.rgb(38), walked.rgb(38), "step " + step + " changed nothing");
        }
        walked = walked.cycled(range);
        for (int i = 0; i < 256; i++) {
            assertEquals(start.rgb(i), walked.rgb(i), "entry " + i + " after a full lap");
        }
    }

    @Test
    @DisplayName("a degenerate range is ignored rather than throwing")
    void degenerateRangesAreIgnored() {
        Palette before = ramp();
        // A single entry cannot rotate; out-of-bounds must not walk off the end.
        Palette after = before.cycled(List.of(
                new int[] {5, 5}, new int[] {9, 3}, new int[] {250, 400}, new int[] {-4, 2}));
        assertEquals(before.rgb(5), after.rgb(5));
        assertTrue(after.rgb(255) >= 0);
    }

    @Test
    @DisplayName("an indexed image can be recoloured without touching its pixels")
    void recolouringSharesTheRaster() {
        IndexedImage image = new IndexedImage(4, 4);
        for (int i = 0; i < 16; i++) {
            image.pixels()[i] = (byte) (10 + i % 5);
        }
        Palette palette = ramp();
        var first = image.toIndexedBufferedImage(palette);
        var second = IndexedImage.recolour(first, palette.cycled(List.of(new int[] {10, 14})));

        assertEquals(first.getWidth(), second.getWidth());
        // The raster is shared: the same pixel indices under new colours.
        assertEquals(first.getRaster().getSample(0, 0, 0),
                second.getRaster().getSample(0, 0, 0));
        assertNotEquals(first.getRGB(0, 0), second.getRGB(0, 0),
                "the colour should have moved even though the pixel did not");
    }
}
