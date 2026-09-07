package net.chonkbase.chonkcraft.desktop;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.util.Arrays;
import java.util.LinkedHashMap;

/**
 * Immutable, bounded pieces of the visible terrain.
 *
 * <p>A changing full-map palette view can acquire a full-map device texture;
 * repeatedly uploading a viewport avoids that retention but stalls on large
 * displays. Small immutable pieces let Java2D retain useful device copies.
 * Only colours actually used in a piece participate in its cache key, so
 * water cycling does not invalidate grass and returning palette phases reuse
 * their pictures. The original indexed map remains the source of truth.
 */
final class TerrainView {
    // Large enough to keep draw submission modest, with at most a one MiB
    // raster to upload when a piece changes. The byte bound also covers all
    // retained palette phases, rather than charging only the current one.
    private static final int SIZE = 512;
    private static final int LIMIT = 512;
    private final ImageCache<Key> pictures = new ImageCache<>(LIMIT, 32L * 1024 * 1024);
    private final LinkedHashMap<Integer, Piece> pieces = new LinkedHashMap<>(64, .75f, true);
    private int[] samples;
    private int[] pixels;
    private int[] colours;
    private IndexColorModel expandedPalette;
    private java.awt.GraphicsConfiguration uploadConfiguration;
    private java.awt.image.VolatileImage uploadPrimer;

    void draw(Graphics2D g, BufferedImage ground, int cameraX, int cameraY, int width, int height) {
        if (!(ground.getColorModel() instanceof IndexColorModel palette)) {
            g.drawImage(ground, -cameraX, -cameraY, null);
            return;
        }
        if (samples == null) {
            samples = new int[SIZE * SIZE];
            pixels = new int[SIZE * SIZE];
        }
        if (expandedPalette != palette) {
            if (colours == null || colours.length != (1 << palette.getPixelSize())) {
                colours = new int[1 << palette.getPixelSize()];
            }
            for (int index = 0; index < colours.length; index++) {
                int argb = palette.getRGB(index);
                int alpha = argb >>> 24;
                // Terrain is composited over black, including transparent
                // index 255. Preserve that rule when expanding into RGB.
                int red = ((argb >> 16 & 255) * alpha + 127) / 255;
                int green = ((argb >> 8 & 255) * alpha + 127) / 255;
                int blue = ((argb & 255) * alpha + 127) / 255;
                colours[index] = red << 16 | green << 8 | blue;
            }
            expandedPalette = palette;
        }
        int fromX = Math.max(0, Math.floorDiv(cameraX, SIZE));
        int fromY = Math.max(0, Math.floorDiv(cameraY, SIZE));
        int toX = Math.min((ground.getWidth() - 1) / SIZE, (cameraX + width) / SIZE);
        int toY = Math.min((ground.getHeight() - 1) / SIZE, (cameraY + height) / SIZE);
        for (int y = fromY; y <= toY; y++) {
            for (int x = fromX; x <= toX; x++) {
                int index = y * ((ground.getWidth() + SIZE - 1) / SIZE) + x;
                Piece piece = pieces.get(index);
                if (piece == null) {
                    piece = new Piece(ground, x * SIZE, y * SIZE, samples);
                    if (pieces.size() == LIMIT) {
                        Piece old = pieces.pollFirstEntry().getValue();
                        pictures.removeIf(key -> key.piece == old);
                    }
                    pieces.put(index, piece);
                }
                Key key = piece.key(palette);
                BufferedImage image = pictures.get(key);
                if (image == null) {
                    image = new BufferedImage(piece.width, piece.height, BufferedImage.TYPE_INT_RGB);
                    // The indexed image has a transparent palette entry.
                    // Java2D's general compositing blit made a changing sea
                    // spend tens of milliseconds expanding one palette phase.
                    // A lookup and bulk raster write avoid that compositing
                    // path without making the immutable RGB image untrackable.
                    ground.getRaster().getSamples(piece.x, piece.y, piece.width, piece.height,
                            0, samples);
                    for (int pixel = 0; pixel < piece.width * piece.height; pixel++) {
                        pixels[pixel] = colours[samples[pixel]];
                    }
                    image.getRaster().setDataElements(0, 0, piece.width, piece.height, pixels);
                    pictures.put(key, image);
                    primeUpload(g, image);
                }
                g.drawImage(image, piece.x - cameraX, piece.y - cameraY, null);
            }
        }
    }

    /** Changed terrain must replace every cached palette phase of its piece. */
    void invalidate(int x, int y, int width, int height) {
        var entries = pieces.values().iterator();
        while (entries.hasNext()) {
            Piece piece = entries.next();
            if (piece.x < x + width && piece.x + piece.width > x
                    && piece.y < y + height && piece.y + piece.height > y) {
                pictures.removeIf(key -> key.piece == piece);
                entries.remove();
            }
        }
    }

    void clear() {
        pictures.clear();
        pieces.clear();
        samples = null;
        pixels = null;
        colours = null;
        expandedPalette = null;
        if (uploadPrimer != null) {
            uploadPrimer.flush();
            uploadPrimer = null;
        }
        uploadConfiguration = null;
    }

    private void primeUpload(Graphics2D destination, BufferedImage image) {
        var configuration = destination.getDeviceConfiguration();
        if (configuration.getDevice().getType() != java.awt.GraphicsDevice.TYPE_RASTER_SCREEN) {
            return;
        }
        if (uploadPrimer == null || uploadConfiguration != configuration) {
            if (uploadPrimer != null) {
                uploadPrimer.flush();
            }
            uploadConfiguration = configuration;
            uploadPrimer = configuration.createCompatibleVolatileImage(1, 1, java.awt.Transparency.OPAQUE);
        }
        if (uploadPrimer.validate(configuration) == java.awt.image.VolatileImage.IMAGE_INCOMPATIBLE) {
            uploadPrimer.flush();
            uploadPrimer = null;
            return;
        }
        // Managed images normally wait one copy before uploading. Make that
        // first copy a clipped, unscaled blit, so the first visible zoomed
        // water frame can use a texture instead of a full software transform.
        Graphics2D into = uploadPrimer.createGraphics();
        into.setTransform(new java.awt.geom.AffineTransform());
        into.drawImage(image, 0, 0, null);
        into.dispose();
    }

    private static final class Piece {
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final int[] used;
        private IndexColorModel palette;
        private Key lastKey;

        Piece(BufferedImage ground, int x, int y, int[] samples) {
            this.x = x;
            this.y = y;
            width = Math.min(SIZE, ground.getWidth() - x);
            height = Math.min(SIZE, ground.getHeight() - y);
            boolean[] seen = new boolean[1 << ground.getColorModel().getPixelSize()];
            int count = 0;
            // getSamples supports both byte-indexed and packed indexed art.
            ground.getRaster().getSamples(x, y, width, height, 0, samples);
            for (int pixel = 0; pixel < width * height; pixel++) {
                int sample = samples[pixel];
                if (!seen[sample]) {
                    seen[sample] = true;
                    count++;
                }
            }
            used = new int[count];
            int at = 0;
            for (int index = 0; index < seen.length; index++) {
                if (seen[index]) {
                    used[at++] = index;
                }
            }
        }

        Key key(IndexColorModel next) {
            if (palette != next) {
                int[] colours = new int[used.length];
                for (int index = 0; index < used.length; index++) {
                    colours[index] = next.getRGB(used[index]);
                }
                if (lastKey == null || !Arrays.equals(lastKey.colours, colours)) {
                    lastKey = new Key(this, colours);
                }
                palette = next;
            }
            return lastKey;
        }
    }

    private static final class Key {
        private final Piece piece;
        private final int[] colours;
        private final int hash;

        Key(Piece piece, int[] colours) {
            this.piece = piece;
            this.colours = colours;
            hash = 31 * System.identityHashCode(piece) + Arrays.hashCode(colours);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && piece == key.piece
                    && Arrays.equals(colours, key.colours);
        }
    }
}
