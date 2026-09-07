package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PixelScalerTest {
    @Test
    @DisplayName("menus keep sharp pixel edges and their black bars at every scale")
    void sharpScalingKeepsThePicture() {
        BufferedImage source = picture();
        for (int[] size : new int[][] {{7, 6}, {13, 9}, {26, 18}, {47, 32}, {120, 95}}) {
            for (boolean stretch : new boolean[] {false, true}) {
                BufferedImage actual = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_RGB);
                Graphics2D g = actual.createGraphics();
                PixelScaler.Cache cache = PixelScaler.draw(g, source, size[0], size[1], stretch, null);
                g.dispose();
                assertArrayEquals(pixels(reference(source, size[0], size[1], stretch, 13, 9, false)),
                        pixels(actual), "menu shape at " + size[0] + "x" + size[1] + ": " + stretch);
                cache.flush();
            }
        }
    }

    @Test
    @DisplayName("movie pixels keep their intended display aspect")
    void movieAspectKeepsBothScalingPasses() {
        BufferedImage source = picture().getSubimage(0, 0, 13, 4);
        BufferedImage actual = new BufferedImage(80, 70, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = actual.createGraphics();
        PixelScaler.Cache cache = PixelScaler.drawAtAspect(g, source, 80, 70, 4, 3, null);
        g.dispose();
        assertArrayEquals(pixels(reference(source, 80, 70, false, 4, 3, true)), pixels(actual),
                "non-square movie samples must retain their separate integer scales");
        cache.flush();
    }

    @Test
    @DisplayName("changed pictures and resized screens never reuse old pixels")
    void repaintResizeAndReleaseKeepFreshPixels() {
        BufferedImage source = picture();
        PixelScaler.Cache cache = null;
        for (int size : new int[] {39, 65, 26, 39}) {
            BufferedImage actual = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            Graphics2D ink = source.createGraphics();
            ink.setColor(new Color(size * 3, 80, 200 - size));
            ink.fillRect(0, 0, 13, 9);
            ink.dispose();
            Graphics2D g = actual.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            cache = PixelScaler.draw(g, source, size, size, true, cache);
            assertEquals(RenderingHints.VALUE_INTERPOLATION_BICUBIC,
                    g.getRenderingHint(RenderingHints.KEY_INTERPOLATION), "painting restores the caller's hint");
            g.dispose();
            assertArrayEquals(pixels(reference(source, size, size, true, 13, 9, false)), pixels(actual),
                    "a reused surface must show the latest frame");
            if (size == 26) {
                cache.flush();
            }
        }
        cache.flush();
    }

    private static BufferedImage picture() {
        BufferedImage image = new BufferedImage(13, 9, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 13; x++) {
                image.setRGB(x, y, (x * 19 << 16) | (y * 29 << 8) | ((x + y) * 11));
            }
        }
        return image;
    }

    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    // The two Java2D passes used before accelerated screen-owned surfaces.
    private static BufferedImage reference(BufferedImage source, int width, int height,
            boolean stretch, int aspectWidth, int aspectHeight, boolean independent) {
        var target = PixelScaler.fit(aspectWidth, aspectHeight, width, height, stretch);
        int scaleX = Math.max(1, Math.min(8, target.width / source.getWidth()));
        int scaleY = Math.max(1, Math.min(8, target.height / source.getHeight()));
        if (!independent) {
            scaleX = scaleY = Math.min(scaleX, scaleY);
        }
        BufferedImage stepped = source;
        if (scaleX > 1 || scaleY > 1) {
            stepped = new BufferedImage(source.getWidth() * scaleX, source.getHeight() * scaleY,
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D into = stepped.createGraphics();
            into.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            into.drawImage(source, 0, 0, stepped.getWidth(), stepped.getHeight(), null);
            into.dispose();
        }
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(stepped, target.x, target.y, target.width, target.height, null);
        g.dispose();
        return result;
    }
}
