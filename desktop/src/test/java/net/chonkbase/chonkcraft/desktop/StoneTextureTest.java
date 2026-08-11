package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The generated stone behaves like a texture and not like a picture.
 *
 * <p>These pin the properties the old marble could not have: it is made at
 * whatever size is asked for, it has no tile to repeat, it is the same every
 * run, and it holds a value range that bitmap lettering can be read against.
 */
class StoneTextureTest {

    @Test
    @DisplayName("stone is generated at exactly the size asked for")
    void anySizeIsExact() {
        for (int[] size : new int[][] {{176, 480}, {224, 28}, {1, 1}, {512, 300}}) {
            BufferedImage made = StoneTexture.of(size[0], size[1], StoneTexture.Tint.STONE);
            assertNotNull(made, size[0] + "x" + size[1]);
            assertEquals(size[0], made.getWidth());
            assertEquals(size[1], made.getHeight());
        }
        assertNull(StoneTexture.of(0, 10, StoneTexture.Tint.STONE));
        assertNull(StoneTexture.of(10, -1, StoneTexture.Tint.STONE));
    }

    @Test
    @DisplayName("the same request gives the same stone every time")
    void itIsDeterministic() {
        // A panel that looked different each time the game opened would be
        // worse than one that repeats.
        BufferedImage first = StoneTexture.of(120, 90, StoneTexture.Tint.STONE);
        BufferedImage again = StoneTexture.of(120, 90, StoneTexture.Tint.STONE);
        for (int y = 0; y < 90; y += 7) {
            for (int x = 0; x < 120; x += 7) {
                assertEquals(first.getRGB(x, y), again.getRGB(x, y), "at " + x + "," + y);
            }
        }
    }

    @Test
    @DisplayName("a bigger slab is more stone, not the same stone enlarged")
    void itDoesNotRepeat() {
        // The complaint about the original: tiled, it repeats on a grid and
        // the eye finds it at once. Nothing here should match itself at any
        // offset that a tile would have produced.
        BufferedImage slab = StoneTexture.of(512, 512, StoneTexture.Tint.STONE);
        for (int period : new int[] {64, 128, 176, 256}) {
            int same = 0;
            int compared = 0;
            for (int y = 0; y < 512 - period; y += 5) {
                for (int x = 0; x < 512 - period; x += 5) {
                    compared++;
                    if (slab.getRGB(x, y) == slab.getRGB(x + period, y)) {
                        same++;
                    }
                }
            }
            assertTrue(same < compared / 10,
                    "the texture repeats every " + period + " pixels: "
                            + same + " of " + compared + " matched");
        }
    }

    /**
     * How bright the art this replaces is.
     *
     * <p>Measured, not chosen: the game's own button panel averages 37, its
     * resource bar 41 and its info panel 44. The first version of this
     * texture averaged 101, which put it beside those in the same column at
     * more than twice their brightness -- the panels clashed and the bitmap
     * lettering, which has no outline, became hard to read on it.
     */
    private static final int ORIGINAL_MEAN_LOW = 30;

    private static final int ORIGINAL_MEAN_HIGH = 55;

    @Test
    @DisplayName("the stone is as dark as the art it replaces")
    void itMatchesTheOriginal() {
        BufferedImage slab = StoneTexture.of(300, 200, StoneTexture.Tint.STONE);
        long total = 0;
        int darkest = 255;
        int lightest = 0;
        for (int y = 0; y < 200; y++) {
            for (int x = 0; x < 300; x++) {
                int value = slab.getRGB(x, y) & 0xFF;
                total += value;
                darkest = Math.min(darkest, value);
                lightest = Math.max(lightest, value);
            }
        }
        int mean = (int) (total / (300L * 200L));
        assertTrue(mean >= ORIGINAL_MEAN_LOW && mean <= ORIGINAL_MEAN_HIGH,
                "the stone averages " + mean + ", against " + ORIGINAL_MEAN_LOW + " to "
                        + ORIGINAL_MEAN_HIGH + " for the art it sits beside");
        // Still has to read as stone rather than as a flat fill.
        assertTrue(lightest - darkest > 20,
                "the stone is too flat to read as stone: " + (lightest - darkest));
        // And must never wash out the white lettering that goes on top of it.
        assertTrue(lightest < 150, "the stone goes too light: " + lightest);
    }

    @Test
    @DisplayName("the button slate is blue and the panel stone is not")
    void thetintsDiffer() {
        BufferedImage stone = StoneTexture.of(64, 64, StoneTexture.Tint.STONE);
        BufferedImage slate = StoneTexture.of(64, 64, StoneTexture.Tint.SLATE);
        long stoneBlue = 0;
        long stoneRed = 0;
        long slateBlue = 0;
        long slateRed = 0;
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int a = stone.getRGB(x, y);
                int b = slate.getRGB(x, y);
                stoneRed += (a >> 16) & 0xFF;
                stoneBlue += a & 0xFF;
                slateRed += (b >> 16) & 0xFF;
                slateBlue += b & 0xFF;
            }
        }
        assertTrue(slateBlue > slateRed * 3 / 2, "the button slate should read as blue");
        assertTrue(stoneBlue < stoneRed * 6 / 5, "the panel stone should read as grey");
    }
}
