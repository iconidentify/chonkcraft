package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TerrainViewTest {
    @Test
    @DisplayName("terrain pieces join without seams while panning and zooming")
    void piecesKeepTheOriginalPicture() {
        BufferedImage ground = ground();
        TerrainView view = new TerrainView();
        for (double scale : new double[] {1, 1.25, 2, 3}) {
            for (int x : new int[] {0, 1, 511, 512, 1000}) {
                assertArrayEquals(pixels(reference(ground, x, 43, scale)),
                        pixels(paint(view, ground, x, 43, scale)),
                        "terrain seam at camera " + x + " and scale " + scale);
            }
        }
        view.clear();
    }

    @Test
    @DisplayName("cycling water keeps new colours and reuses a returning palette safely")
    void palettePhasesKeepTheirColours() {
        BufferedImage ground = ground();
        TerrainView view = new TerrainView();
        for (int alpha : new int[] {255, 128, 1, 0}) {
            for (int colour : new int[] {0x2160C0, 0x4380E0, 0x2160C0}) {
                int[] colours = {0xFF203020, alpha << 24 | colour, 0xFFB89050, 0xFF405030};
                IndexColorModel palette = new IndexColorModel(8, colours.length, colours, 0, true, -1,
                        java.awt.image.DataBuffer.TYPE_BYTE);
                BufferedImage phase = new BufferedImage(palette, ground.getRaster(), false, null);
                assertArrayEquals(pixels(reference(phase, 100, 30, 2)),
                        pixels(paint(view, phase, 100, 30, 2)),
                        "the visible water must use the current palette and alpha " + alpha);
            }
        }
        view.clear();
    }

    @Test
    @DisplayName("changed ground and returning to the map cannot resurrect old terrain")
    void changedGroundInvalidatesEveryPicture() {
        BufferedImage ground = ground();
        TerrainView view = new TerrainView();
        paint(view, ground, 450, 450, 2);
        for (int y = 508; y < 533; y++) {
            for (int x = 508; x < 533; x++) {
                ground.getRaster().setSample(x, y, 0, 2);
            }
        }
        view.invalidate(508, 508, 25, 25);
        assertArrayEquals(pixels(reference(ground, 450, 450, 2)),
                pixels(paint(view, ground, 450, 450, 2)), "changes crossing piece boundaries must be visible");
        view.clear();
        assertArrayEquals(pixels(reference(ground, 450, 450, 2)),
                pixels(paint(view, ground, 450, 450, 2)), "returning after release must rebuild the current map");
        view.clear();
    }

    private static BufferedImage ground() {
        int[] colours = {0x203020, 0x2160C0, 0xB89050, 0x405030};
        IndexColorModel palette = new IndexColorModel(8, colours.length, colours, 0, false, -1,
                java.awt.image.DataBuffer.TYPE_BYTE);
        BufferedImage image = new BufferedImage(1031, 789, BufferedImage.TYPE_BYTE_INDEXED, palette);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.getRaster().setSample(x, y, 0, (x / 7 + y / 11) % 4);
            }
        }
        return image;
    }

    private static BufferedImage reference(BufferedImage ground, int x, int y, double scale) {
        BufferedImage result = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = graphics(result, scale);
        g.drawImage(ground, -x, -y, null);
        g.dispose();
        return result;
    }

    private static BufferedImage paint(TerrainView view, BufferedImage ground, int x, int y, double scale) {
        BufferedImage result = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = graphics(result, scale);
        view.draw(g, ground, x, y, (int) Math.ceil(400 / scale), (int) Math.ceil(300 / scale));
        g.dispose();
        return result;
    }

    private static Graphics2D graphics(BufferedImage image, double scale) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.scale(scale, scale);
        return g;
    }

    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }
}
