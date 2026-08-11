package net.chonkbase.chonkcraft.desktop;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Draws somebody pointing at a place on the map.
 *
 * <p>Three rings leaving the point at staggered intervals, each thinning and
 * fading as it goes, with a small solid dot at the centre that fades last. The
 * stagger is what makes it read as a pulse rather than a circle changing size:
 * one ring expanding looks like a mistake, three chasing each other looks like
 * an intention.
 *
 * <p>Drawn in the player's own colour, sandwiched between a dark edge and a
 * light one. A dark edge alone gives contrast in one direction: it holds a red
 * ping against snow and does nothing at all for a blue one on water, where the
 * colour and the sea are the same value. With both, there is always an edge
 * against whatever is behind it. A ping the other player cannot pick out has
 * failed at the one thing it is for.
 *
 * <p>Everything scales from the radius it is given, so the same code draws the
 * large version on the map and the small one on the minimap without a second
 * set of numbers to keep in step.
 */
final class PingArt {

    /** How many rings chase each other outward. */
    private static final int RINGS = 3;

    /** How far apart they start, as a fraction of the whole life. */
    private static final double STAGGER = 0.18;

    private PingArt() {
    }

    /**
     * Draws one ping.
     *
     * @param centreX  where it is pointing, in the space being drawn in
     * @param centreY  likewise
     * @param maxRadius how far the outermost ring travels
     * @param age      how far through its life it is, nought to one
     * @param colour   the pointing player's colour
     */
    static void draw(Graphics2D g2, int centreX, int centreY, double maxRadius,
            double age, Color colour) {
        if (age < 0 || age > 1) {
            return;
        }
        Object savedHint = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        java.awt.Stroke savedStroke = g2.getStroke();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);

        for (int ring = 0; ring < RINGS; ring++) {
            double ringAge = age - ring * STAGGER;
            if (ringAge <= 0 || ringAge >= 1) {
                continue;
            }
            // Out fast and then slowing, which is how a real pulse behaves and
            // why a linear expansion looks mechanical.
            double eased = 1 - Math.pow(1 - ringAge, 3);
            double radius = maxRadius * eased;
            int alpha = (int) (210 * (1 - ringAge) * (1 - ringAge));
            if (alpha <= 4 || radius < 1) {
                continue;
            }
            float width = (float) Math.max(1.0, 2.6 * (1 - ringAge));

            // Three passes, dark then light then the colour. A dark edge
            // alone gives contrast in one direction only: it holds a red ping
            // against snow and does nothing for a blue one on water, where the
            // colour and the sea are the same value. Sandwiching the colour
            // between a dark edge and a light one means there is always an
            // edge against whatever is behind it, and the blue and black
            // players can be seen at all.
            g2.setStroke(new BasicStroke(width + 2.6f));
            g2.setColor(new Color(0, 0, 0, Math.min(150, alpha)));
            circle(g2, centreX, centreY, radius);

            g2.setStroke(new BasicStroke(width + 1.3f));
            g2.setColor(new Color(255, 255, 255, Math.min(130, (int) (alpha * 0.8))));
            circle(g2, centreX, centreY, radius);

            g2.setStroke(new BasicStroke(width));
            g2.setColor(new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), alpha));
            circle(g2, centreX, centreY, radius);
        }

        // The centre, which fades last and is what the eye lands on.
        int dotAlpha = (int) (235 * (1 - age));
        if (dotAlpha > 6) {
            double dot = Math.max(1.5, maxRadius * 0.16 * (1 - age * 0.5));
            g2.setColor(new Color(0, 0, 0, Math.min(170, dotAlpha)));
            fill(g2, centreX, centreY, dot + 2.0);
            g2.setColor(new Color(255, 255, 255, Math.min(150, (int) (dotAlpha * 0.8))));
            fill(g2, centreX, centreY, dot + 1.0);
            g2.setColor(new Color(colour.getRed(), colour.getGreen(), colour.getBlue(),
                    dotAlpha));
            fill(g2, centreX, centreY, dot);
        }

        g2.setStroke(savedStroke);
        if (savedHint != null) {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, savedHint);
        }
    }

    private static void circle(Graphics2D g2, int centreX, int centreY, double radius) {
        int size = (int) Math.round(radius * 2);
        g2.drawOval(centreX - (int) Math.round(radius), centreY - (int) Math.round(radius),
                size, size);
    }

    private static void fill(Graphics2D g2, int centreX, int centreY, double radius) {
        int size = (int) Math.round(radius * 2);
        g2.fillOval(centreX - (int) Math.round(radius), centreY - (int) Math.round(radius),
                size, size);
    }
}
