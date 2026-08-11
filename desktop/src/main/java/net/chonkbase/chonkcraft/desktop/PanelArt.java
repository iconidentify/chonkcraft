package net.chonkbase.chonkcraft.desktop;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Panels and buttons, drawn rather than blitted.
 *
 * <p>The stone comes from {@link StoneTexture}; this puts an edge on it. A slab
 * of texture with nothing round it reads as a photograph lying on the screen,
 * and what makes a panel look like a panel is the light on its edges: a lit rim
 * along the top and left, a shadowed one along the bottom and right, and a dark
 * line outside both to separate it from whatever is behind.
 *
 * <p>Drawn at the size the thing actually is, so the bevel stays one pixel of
 * the screen rather than four pixels of a blown-up sprite. That is the whole
 * difference between an interface that is bigger and one that is sharper.
 */
final class PanelArt {

    private PanelArt() {
    }

    /** A raised slab: the sidebar, a menu panel, an unpressed button. */
    static void panel(Graphics2D g2, int x, int y, int width, int height,
            StoneTexture.Tint tint) {
        draw(g2, x, y, width, height, tint, true);
    }

    /** A sunken one: a pressed button, or a well for something to sit in. */
    static void sunken(Graphics2D g2, int x, int y, int width, int height,
            StoneTexture.Tint tint) {
        draw(g2, x, y, width, height, tint, false);
    }

    /**
     * How far the light and shadow reach in from the edge.
     *
     * <p>One pixel reads as a line, two as an edge, three as a moulding. Two is
     * what most interfaces settle on and it is what this uses; it survives
     * being drawn small without disappearing and being drawn large without
     * looking like a picture frame.
     */
    private static final int BEVEL = 2;

    private static void draw(Graphics2D g2, int x, int y, int width, int height,
            StoneTexture.Tint tint, boolean raised) {
        if (width <= 0 || height <= 0) {
            return;
        }
        // The whole sidebar is drawn through a scaling transform, so a slab
        // generated at its declared width is enlarged before it reaches the
        // screen. Asking for it at the size it will actually cover, and then
        // drawing it back into the declared rectangle, puts it on the screen
        // one generated pixel to one screen pixel.
        double scale = scaleOf(g2);
        BufferedImage stone = StoneTexture.of(width, height, tint, scale);
        if (stone != null) {
            g2.drawImage(stone, x, y, width, height, null);
        }

        java.awt.Stroke saved = g2.getStroke();
        g2.setStroke(new BasicStroke(1f));

        Color light = new Color(255, 255, 255, raised ? 62 : 28);
        Color dark = new Color(0, 0, 0, raised ? 120 : 150);
        Color topLeft = raised ? light : dark;
        Color bottomRight = raised ? dark : light;

        for (int i = 0; i < BEVEL; i++) {
            g2.setColor(fade(topLeft, i));
            g2.drawLine(x + i, y + i, x + width - 1 - i, y + i);
            g2.drawLine(x + i, y + i, x + i, y + height - 1 - i);

            g2.setColor(fade(bottomRight, i));
            g2.drawLine(x + i, y + height - 1 - i, x + width - 1 - i, y + height - 1 - i);
            g2.drawLine(x + width - 1 - i, y + i, x + width - 1 - i, y + height - 1 - i);
        }

        // A dark line outside the bevel, so the panel has an edge against
        // whatever is behind it rather than bleeding into it.
        g2.setColor(new Color(0, 0, 0, 170));
        g2.drawRect(x, y, width - 1, height - 1);
        g2.setStroke(saved);
    }

    /**
     * How many device pixels one drawing unit covers.
     *
     * <p>Taken off the transform rather than passed in, so that every caller
     * gets it right without having to know it exists. The length of the first
     * column rather than the bare {@code m00}, so a flipped or rotated
     * transform still gives a positive size.
     */
    static double scaleOf(Graphics2D g2) {
        java.awt.geom.AffineTransform at = g2 == null ? null : g2.getTransform();
        if (at == null) {
            return 1.0;
        }
        double scale = Math.hypot(at.getScaleX(), at.getShearY());
        return scale <= 0 ? 1.0 : scale;
    }

    /** Each step of the bevel is fainter than the one outside it. */
    private static Color fade(Color colour, int step) {
        int alpha = Math.max(0, colour.getAlpha() - step * (colour.getAlpha() / 2));
        return new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), alpha);
    }
}
