package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The chrome is generated at the size it is really drawn.
 *
 * <p>The whole interface goes through a scaling transform, so a slab of stone
 * or an icon made at its declared size is enlarged before it reaches the
 * screen. That is the very fault the 1995 marble had, reproduced in a texture
 * that need not have it: at threefold the generated pattern came out in three
 * pixel blocks. These pin the fix -- that the size asked for follows the
 * transform, and that the result really is finer rather than merely larger.
 */
class DrawnAtScaleTest {

    @Test
    @DisplayName("stone is generated at the size it will cover")
    void theSlabFollowsTheScale() {
        BufferedImage plain = StoneTexture.of(60, 40, StoneTexture.Tint.STONE, 1.0);
        BufferedImage tripled = StoneTexture.of(60, 40, StoneTexture.Tint.STONE, 3.0);
        assertNotNull(plain);
        assertNotNull(tripled);
        assertEquals(60, plain.getWidth());
        assertEquals(180, tripled.getWidth(), "sixty interface pixels at threefold is a hundred "
                + "and eighty real ones");
        assertEquals(120, tripled.getHeight());

        // And it is more stone rather than the same stone bigger. Enlarged by
        // three, every three by three block would be one colour; generated at
        // three times the size, neighbouring pixels differ throughout.
        int blocks = 0;
        int flat = 0;
        for (int y = 0; y + 3 < tripled.getHeight(); y += 3) {
            for (int x = 0; x + 3 < tripled.getWidth(); x += 3) {
                blocks++;
                if (tripled.getRGB(x, y) == tripled.getRGB(x + 1, y)
                        && tripled.getRGB(x, y) == tripled.getRGB(x, y + 1)
                        && tripled.getRGB(x, y) == tripled.getRGB(x + 1, y + 1)) {
                    flat++;
                }
            }
        }
        assertTrue(flat < blocks / 4,
                flat + " of " + blocks + " three-pixel blocks are one flat colour, which is "
                        + "what an enlarged texture looks like");
    }

    @Test
    @DisplayName("a panel takes its scale from the transform it is drawn through")
    void panelArtReadsTheTransform() {
        BufferedImage frame = new BufferedImage(120, 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = frame.createGraphics();
        assertEquals(1.0, PanelArt.scaleOf(g2), 0.0001);
        g2.scale(3, 3);
        assertEquals(3.0, PanelArt.scaleOf(g2), 0.0001);

        // Drawn through that transform, a forty pixel panel fills a hundred
        // and twenty and is not a grid of three pixel squares.
        PanelArt.panel(g2, 0, 0, 40, 40, StoneTexture.Tint.STONE);
        g2.dispose();
        int flat = 0;
        int blocks = 0;
        for (int y = 6; y + 3 < 114; y += 3) {
            for (int x = 6; x + 3 < 114; x += 3) {
                blocks++;
                if (frame.getRGB(x, y) == frame.getRGB(x + 1, y)
                        && frame.getRGB(x, y) == frame.getRGB(x, y + 1)) {
                    flat++;
                }
            }
        }
        assertTrue(flat < blocks / 3,
                flat + " of " + blocks + " blocks inside the panel are flat");
    }

    @Test
    @DisplayName("every resource counter has an icon, at whatever size is asked for")
    void theResourceIconsAreDrawn() {
        for (ResourceIcons.Kind kind : ResourceIcons.Kind.values()) {
            BufferedImage small = ResourceIcons.of(kind, 1.0);
            BufferedImage large = ResourceIcons.of(kind, 4.0);
            assertNotNull(small, kind.name());
            assertEquals(ResourceIcons.DESIGN_SIZE, small.getWidth(), kind.name());
            assertEquals(ResourceIcons.DESIGN_SIZE * 4, large.getWidth(), kind.name());

            // Each is a picture rather than a blank plate: the frame alone
            // would give one colour round the edge and nothing inside.
            java.util.Set<Integer> colours = new java.util.HashSet<>();
            for (int y = 2; y < large.getHeight() - 2; y++) {
                for (int x = 2; x < large.getWidth() - 2; x++) {
                    colours.add(large.getRGB(x, y));
                }
            }
            assertTrue(colours.size() > 20,
                    kind + " has only " + colours.size() + " colours inside its frame");
        }
    }

    @Test
    @DisplayName("the same icon is the same picture every time")
    void theIconsAreDeterministic() {
        BufferedImage first = ResourceIcons.of(ResourceIcons.Kind.GOLD, 2.0);
        BufferedImage again = ResourceIcons.of(ResourceIcons.Kind.GOLD, 2.0);
        for (int y = 0; y < first.getHeight(); y += 3) {
            for (int x = 0; x < first.getWidth(); x += 3) {
                assertEquals(first.getRGB(x, y), again.getRGB(x, y), "at " + x + "," + y);
            }
        }
    }
}
