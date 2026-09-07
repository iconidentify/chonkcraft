package net.chonkbase.chonkcraft.desktop;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.Image;
import java.awt.Transparency;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;

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
     * @param cache the screen-owned scaling surfaces, or null. The owner flushes
     *              them when removed so native graphics memory does not wait
     *              for heap pressure before it can be reclaimed.
     */
    static Cache draw(Graphics2D g2, BufferedImage source,
            int windowWidth, int windowHeight, boolean stretch, Cache cache) {
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
    static Cache drawAtAspect(Graphics2D g2, BufferedImage source,
            int windowWidth, int windowHeight, int aspectWidth, int aspectHeight,
            Cache cache) {
        if (source == null || windowWidth <= 0 || windowHeight <= 0) {
            return cache;
        }
        Rectangle target = fit(aspectWidth, aspectHeight,
                windowWidth, windowHeight, false);
        return drawInto(g2, source, target, cache, true);
    }

    private static Cache drawInto(Graphics2D g2, BufferedImage source,
            Rectangle target, Cache cache, boolean independentAxes) {
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

        if (cache == null) {
            cache = new Cache();
        }
        int width = source.getWidth() * prescaleX;
        int height = source.getHeight() * prescaleY;
        Object saved = g2.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                width == target.width && height == target.height
                        ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                        : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        try {
            if (!cache.drawAccelerated(g2, source, width, height, target)) {
                Image stepped = source;
                if (prescaleX > 1 || prescaleY > 1) {
                    if (cache.software == null || cache.software.getWidth() != width
                            || cache.software.getHeight() != height) {
                        if (cache.software != null) {
                            cache.software.flush();
                        }
                        cache.software = new BufferedImage(width, height,
                                BufferedImage.TYPE_INT_RGB);
                    }
                    Graphics2D into = cache.software.createGraphics();
                    into.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    into.drawImage(source, 0, 0, width, height, null);
                    into.dispose();
                    stepped = cache.software;
                }
                g2.drawImage(stepped, target.x, target.y, target.width, target.height, null);
            }
        } finally {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, saved == null
                    ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : saved);
        }
        return cache;
    }

    /**
     * At most a source upload, an integer intermediate and a software fallback.
     *
     * <p>A modified BufferedImage scaled directly to an accelerated destination
     * can fall through Java2D's software transform, allocating a full-size
     * raster on every paint. Uploading at one to one first lets both scaling
     * passes stay on the GPU. These surfaces belong to one screen and are
     * explicitly flushed on resize, device changes and screen removal.
     */
    static final class Cache {
        private GraphicsConfiguration configuration;
        private VolatileImage upload;
        private VolatileImage enlarged;
        private BufferedImage software;
        private boolean unavailable;

        private boolean drawAccelerated(Graphics2D destination, BufferedImage source,
                int width, int height, Rectangle target) {
            GraphicsConfiguration next = destination.getDeviceConfiguration();
            if (next.getDevice().getType() != GraphicsDevice.TYPE_RASTER_SCREEN) {
                return false;
            }
            if (configuration != next) {
                flush();
                configuration = next;
            }
            if (unavailable) {
                return false;
            }
            // Volatile images inherit the device scale. Request logical sizes
            // whose backing pixels match the art, and upload with an identity
            // transform. Otherwise Retina uploads scale in software and the
            // intermediate uses four times the intended storage and changes
            // the sharp-bilinear sampling footprint.
            var deviceScale = next.getDefaultTransform();
            double scaleX = deviceScale.getScaleX();
            double scaleY = deviceScale.getScaleY();
            int sourceWidth = (int) Math.round(source.getWidth() / scaleX);
            int sourceHeight = (int) Math.round(source.getHeight() / scaleY);
            if (sourceWidth < 1 || sourceHeight < 1
                    || sourceWidth * scaleX != source.getWidth()
                    || sourceHeight * scaleY != source.getHeight()) {
                return false;
            }
            int enlargedWidth = (int) Math.round(width / scaleX);
            int enlargedHeight = (int) Math.round(height / scaleY);
            int transparency = source.getTransparency() == Transparency.OPAQUE
                    ? Transparency.OPAQUE : Transparency.TRANSLUCENT;
            if (upload == null || upload.getWidth() != sourceWidth
                    || upload.getHeight() != sourceHeight
                    || upload.getTransparency() != transparency) {
                releaseAccelerated();
                upload = next.createCompatibleVolatileImage(sourceWidth, sourceHeight,
                        transparency);
                if (!upload.getCapabilities().isAccelerated()) {
                    releaseAccelerated();
                    unavailable = true;
                    return false;
                }
            }
            boolean prescaled = width != source.getWidth() || height != source.getHeight();
            if (enlarged != null && (!prescaled || enlarged.getWidth() != enlargedWidth
                    || enlarged.getHeight() != enlargedHeight)) {
                enlarged.flush();
                enlarged = null;
            }
            if (prescaled && enlarged == null) {
                enlarged = next.createCompatibleVolatileImage(enlargedWidth, enlargedHeight,
                        Transparency.OPAQUE);
            }
            // Surface loss can persist while a window moves between devices.
            // Bound retries so losing acceleration cannot stall the event queue.
            for (int attempt = 0; attempt < 3; attempt++) {
                if (upload.validate(next) == VolatileImage.IMAGE_INCOMPATIBLE
                        || enlarged != null
                        && enlarged.validate(next) == VolatileImage.IMAGE_INCOMPATIBLE) {
                    releaseAccelerated();
                    return false;
                }
                Graphics2D into = upload.createGraphics();
                into.setTransform(new java.awt.geom.AffineTransform());
                into.setComposite(AlphaComposite.Src);
                into.drawImage(source, 0, 0, null);
                into.dispose();
                Image stepped = upload;
                if (prescaled) {
                    into = enlarged.createGraphics();
                    into.setTransform(new java.awt.geom.AffineTransform());
                    into.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    into.drawImage(upload, 0, 0, width, height, null);
                    into.dispose();
                    stepped = enlarged;
                }
                destination.drawImage(stepped, target.x, target.y,
                        target.width, target.height, null);
                if (!upload.contentsLost() && (enlarged == null || !enlarged.contentsLost())) {
                    if (software != null) {
                        software.flush();
                        software = null;
                    }
                    return true;
                }
            }
            releaseAccelerated();
            return false;
        }

        private void releaseAccelerated() {
            if (upload != null) {
                upload.flush();
                upload = null;
            }
            if (enlarged != null) {
                enlarged.flush();
                enlarged = null;
            }
        }

        /** Releases device memory even when the Java heap has plenty of room. */
        void flush() {
            releaseAccelerated();
            if (software != null) {
                software.flush();
                software = null;
            }
            configuration = null;
            unavailable = false;
        }
    }
}
