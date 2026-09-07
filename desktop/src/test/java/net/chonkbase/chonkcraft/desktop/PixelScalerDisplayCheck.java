package net.chonkbase.chonkcraft.desktop;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import javax.swing.SwingUtilities;

/** Explicit display check for the accelerated path; see docs/render-performance.md. */
public final class PixelScalerDisplayCheck {
    private PixelScalerDisplayCheck() {}

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(PixelScalerDisplayCheck::check);
    }

    private static void check() {
        var configuration = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        VolatileImage output = configuration.createCompatibleVolatileImage(257, 193, Transparency.OPAQUE);
        if (!output.getCapabilities().isAccelerated()) {
            throw new IllegalStateException("the display check needs accelerated Java2D surfaces");
        }
        BufferedImage source = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
        PixelScaler.Cache accelerated = null;
        VolatileImage control = configuration.createCompatibleVolatileImage(257, 193, Transparency.OPAQUE);
        int largestDifference = 0;
        try {
            for (int frame = 0; frame < 40; frame++) {
                for (int y = 0; y < 24; y++) {
                    for (int x = 0; x < 32; x++) {
                        source.setRGB(x, y, ((x * 17 + frame * 23) & 255) << 16
                                | ((y * 29 + frame * 7) & 255) << 8 | ((x + y + frame) * 13 & 255));
                    }
                }
                int width = 73 + frame * 3;
                int height = 55 + frame * 2;
                boolean movie = frame >= 20;
                BufferedImage picture = movie ? source.getSubimage(0, 0, 32, 14) : source;
                control.validate(configuration);
                Graphics2D reference = control.createGraphics();
                reference.setColor(Color.BLACK);
                reference.fillRect(0, 0, 257, 193);
                legacyDraw(reference, picture, width, height, movie);
                reference.dispose();
                BufferedImage expected = control.getSnapshot();
                if (output.validate(configuration) == VolatileImage.IMAGE_INCOMPATIBLE) {
                    throw new IllegalStateException("display changed during the check");
                }
                Graphics2D g = output.createGraphics();
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, 257, 193);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                accelerated = movie
                        ? PixelScaler.drawAtAspect(g, picture, width, height, 4, 3, accelerated)
                        : PixelScaler.draw(g, picture, width, height, false, accelerated);
                g.dispose();
                if (output.contentsLost()) {
                    throw new IllegalStateException("display contents lost during capture; rerun the check");
                }
                BufferedImage actual = output.getSnapshot();
                for (int y = 0; y < 193; y++) {
                    for (int x = 0; x < 257; x++) {
                        int wanted = expected.getRGB(x, y);
                        int got = actual.getRGB(x, y);
                        for (int shift = 0; shift <= 16; shift += 8) {
                            int difference = Math.abs((wanted >> shift & 255) - (got >> shift & 255));
                            largestDifference = Math.max(largestDifference, difference);
                            if (difference > 2) {
                                throw new AssertionError("scaled frame " + frame + " differs at "
                                        + x + "," + y + " by " + difference + " in a colour channel");
                            }
                        }
                    }
                }
                if (frame % 3 == 0) {
                    accelerated.flush();
                }
            }
            System.out.println("accelerated_frames=40 largest_channel_difference=" + largestDifference);
        } finally {
            if (accelerated != null) {
                accelerated.flush();
            }
            control.flush();
            output.flush();
        }
    }
    private static void legacyDraw(Graphics2D g, BufferedImage source, int width, int height,
            boolean movie) {
        var target = PixelScaler.fit(movie ? 4 : source.getWidth(), movie ? 3 : source.getHeight(),
                width, height, false);
        int scaleX = Math.max(1, Math.min(8, target.width / source.getWidth()));
        int scaleY = Math.max(1, Math.min(8, target.height / source.getHeight()));
        if (!movie) {
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
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                stepped.getWidth() == target.width && stepped.getHeight() == target.height
                        ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                        : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(stepped, target.x, target.y, target.width, target.height, null);
        if (stepped != source) {
            stepped.flush();
        }
    }

}
