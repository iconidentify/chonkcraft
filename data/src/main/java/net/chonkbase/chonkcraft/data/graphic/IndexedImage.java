package net.chonkbase.chonkcraft.data.graphic;

import java.awt.image.BufferedImage;

/**
 * An 8-bit paletted image: one byte of palette index per pixel.
 *
 * <p>The engine works in palette indices rather than resolved colour, because
 * unit team colours and the tileset's animated water and fire are produced by
 * remapping and cycling index ranges at draw time. Resolving to RGB early
 * would throw that away.
 */
public final class IndexedImage {

    private final int width;
    private final int height;
    private final byte[] pixels;

    public IndexedImage(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("bad image size " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.pixels = new byte[Math.multiplyExact(width, height)];
    }

    public IndexedImage(int width, int height, byte[] pixels) {
        if (pixels.length != Math.multiplyExact(width, height)) {
            throw new IllegalArgumentException(
                    "pixel count " + pixels.length + " does not match " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.pixels = pixels;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** The backing pixel array, row-major, not copied. */
    public byte[] pixels() {
        return pixels;
    }

    public int get(int x, int y) {
        return pixels[y * width + x] & 0xFF;
    }

    public void set(int x, int y, int index) {
        pixels[y * width + x] = (byte) index;
    }

    /** Fills every pixel with {@code index}. */
    public void fill(int index) {
        java.util.Arrays.fill(pixels, (byte) index);
    }

    /**
     * Applies the transparency fix-up {@code SavePNG} performs before writing:
     * index 0 becomes index 255, so that both the palette's black and the
     * decoder's transparent runs land on the one transparent index.
     */
    public void foldIndexZeroIntoTransparent() {
        for (int i = 0; i < pixels.length; i++) {
            if (pixels[i] == 0) {
                pixels[i] = (byte) Palette.TRANSPARENT_INDEX;
            }
        }
    }

    /** Resolves to ARGB through {@code palette}, with index 255 transparent. */
    /**
     * Converts with every index opaque, including 255.
     *
     * <p>Index 255 is the transparent one for sprites, which is why
     * {@link #toBufferedImage} drops it. A video frame has no transparency:
     * 255 is a colour like any other, and in Warcraft II's cutscenes it is a
     * dark green that a good deal of the landscape is drawn in.
     */
    public BufferedImage toOpaqueBufferedImage(Palette palette) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            int base = y * width;
            for (int x = 0; x < width; x++) {
                row[x] = palette.rgb(pixels[base + x] & 0xFF);
            }
            image.setRGB(0, y, width, 1, row, 0, width);
        }
        return image;
    }

    /**
     * Converts keeping the pixels as palette indices.
     *
     * <p>The point is that the colours can be changed afterwards for nothing.
     * A terrain rasterised to true colour has to be redrawn pixel by pixel
     * when a palette entry moves, and a Warcraft II map is three thousand
     * pixels square; sharing the raster with a new colour model instead is a
     * single allocation.
     */
    public BufferedImage toIndexedBufferedImage(Palette palette) {
        java.awt.image.IndexColorModel model = palette.toColorModel();
        BufferedImage image = new BufferedImage(width, height,
                BufferedImage.TYPE_BYTE_INDEXED, model);
        image.getRaster().setDataElements(0, 0, width, height, pixels);
        return image;
    }

    /**
     * The same picture under a different palette, sharing its pixels.
     *
     * <p>Cheap by design: this is how a cycling palette animates without the
     * terrain being redrawn.
     */
    public static BufferedImage recolour(BufferedImage indexed, Palette palette) {
        return new BufferedImage(palette.toColorModel(), indexed.getRaster(), false, null);
    }

    public BufferedImage toBufferedImage(Palette palette) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            int base = y * width;
            for (int x = 0; x < width; x++) {
                row[x] = palette.argb(pixels[base + x] & 0xFF);
            }
            image.setRGB(0, y, width, 1, row, 0, width);
        }
        return image;
    }
}
