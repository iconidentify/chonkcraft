package net.chonkbase.chonkcraft.desktop;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Draws a small picture large without spoiling it.
 *
 * <p>Warcraft II's art is 320 or 640 pixels across. Filling a modern screen
 * with it means scaling by four or five, and the two obvious ways of doing
 * that are both wrong.
 *
 * <p>Nearest neighbour at a whole factor is perfect, and at a fractional one it
 * is not: some source pixels land on three output pixels and their neighbours
 * on four, so straight edges come out with a stagger in them and anything that
 * moves shimmers. Bilinear has no stagger and no crisp edges either; it turns a
 * hand-drawn 320 pixel frame into a blur.
 *
 * <p>So this does what it is usually called sharp bilinear: nearest neighbour
 * up to the largest whole multiple that still fits, which is exact and free of
 * artefacts, and then a single bilinear pass over the fraction that is left.
 * The result keeps the hard pixel edges of the original and spreads the
 * leftover fraction evenly instead of piling it onto every third column.
 *
 * <p>Aspect ratio is kept and the remainder is left as black bars. Warcraft
 * II's cutscenes are 320 by 144 on a 4:3 screen; stretching them to whatever
 * shape the window happens to be would be a worse crime than the bars.
 */
final class PixelScaler {

    /** How far past the target an integer prescale may go before it is wasteful. */
    private static final int MAX_PRESCALE = 8;

    private PixelScaler() {
    }

    /**
     * Where a picture of a given size sits inside a window, keeping its shape.
     *
     * @param stretch true to fill the window and ignore the picture's shape
     */
    static Rectangle fit(int sourceWidth, int sourceHeight, int windowWidth, int windowHeight,
            boolean stretch) {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return new Rectangle(0, 0, windowWidth, windowHeight);
        }
        if (stretch) {
            return new Rectangle(0, 0, windowWidth, windowHeight);
        }
        double factor = Math.min(windowWidth / (double) sourceWidth,
                windowHeight / (double) sourceHeight);
        int width = Math.max(1, (int) Math.round(sourceWidth * factor));
        int height = Math.max(1, (int) Math.round(sourceHeight * factor));
        return new Rectangle((windowWidth - width) / 2, (windowHeight - height) / 2,
                width, height);
    }

    /**
     * Draws a picture to fill a window, keeping its shape unless told not to.
     *
     * @param cache a one-slot cache for the intermediate, or null. Passing one
     *              matters for video: a cutscene draws twelve times a second
     *              and allocating a full-size intermediate each time is work
     *              the collector then has to undo.
     */
    static BufferedImage draw(Graphics2D g2, BufferedImage source,
            int windowWidth, int windowHeight, boolean stretch, BufferedImage cache) {
        if (source == null || windowWidth <= 0 || windowHeight <= 0) {
            return cache;
        }
        Rectangle target = fit(source.getWidth(), source.getHeight(),
                windowWidth, windowHeight, stretch);
        return drawInto(g2, source, target, cache, false);
    }

    /**
     * Draws pixels whose intended display shape differs from their stored
     * raster shape.
     *
     * <p>Battle.net Edition's full-motion movies were authored with non-square
     * pixels. Treating those samples as square makes both people and the
     * Blizzard logo tall and thin. The display aspect controls only the target
     * rectangle; the source raster remains intact.
     */
    static BufferedImage drawAtAspect(Graphics2D g2, BufferedImage source,
            int windowWidth, int windowHeight, int aspectWidth, int aspectHeight,
            BufferedImage cache) {
        if (source == null || windowWidth <= 0 || windowHeight <= 0) {
            return cache;
        }
        Rectangle target = fit(aspectWidth, aspectHeight,
                windowWidth, windowHeight, false);
        return drawInto(g2, source, target, cache, true);
    }

    private static BufferedImage drawInto(Graphics2D g2, BufferedImage source,
            Rectangle target, BufferedImage cache, boolean independentAxes) {
        // The whole-number part, done exactly.
        int prescaleX;
        int prescaleY;
        if (independentAxes) {
            prescaleX = Math.max(1, Math.min(MAX_PRESCALE,
                    (int) Math.floor(target.width / (double) source.getWidth())));
            prescaleY = Math.max(1, Math.min(MAX_PRESCALE,
                    (int) Math.floor(target.height / (double) source.getHeight())));
        } else {
            int common = Math.max(1, Math.min(MAX_PRESCALE,
                    (int) Math.floor(Math.min(
                            target.width / (double) source.getWidth(),
                            target.height / (double) source.getHeight()))));
            prescaleX = common;
            prescaleY = common;
        }

        BufferedImage stepped = source;
        if (prescaleX > 1 || prescaleY > 1) {
            int width = source.getWidth() * prescaleX;
            int height = source.getHeight() * prescaleY;
            BufferedImage into = cache != null
                    && cache.getWidth() == width && cache.getHeight() == height
                    ? cache
                    : new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D into2 = into.createGraphics();
            into2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            into2.drawImage(source, 0, 0, width, height, null);
            into2.dispose();
            stepped = into;
            cache = into;
        }

        // The fraction that is left, spread evenly.
        Object interpolation = stepped.getWidth() == target.width
                && stepped.getHeight() == target.height
                ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                : RenderingHints.VALUE_INTERPOLATION_BILINEAR;
        Object saved = g2.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.drawImage(stepped, target.x, target.y, target.width, target.height, null);
        if (saved != null) {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, saved);
        }
        return cache;
    }
}
