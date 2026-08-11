package net.chonkbase.chonkcraft.data.graphic;

/**
 * Reads Warcraft II's flat images: interface panels, buttons, backgrounds.
 *
 * <p>Implements {@code ConvertImg}. The format is as
 * plain as it gets: a 16-bit width, a 16-bit height, then one palette index
 * per pixel with no compression and no row padding. Sprites earn their
 * run-length coding by being mostly transparent; a full-bleed interface panel
 * would not gain anything from it.
 */
public final class ImageDecoder {

    private static final int HEADER_BYTES = 4;

    private ImageDecoder() {
    }

    /** Decodes an image entry. */
    public static IndexedImage decode(byte[] entry) {
        if (entry.length < HEADER_BYTES) {
            throw new IllegalArgumentException("image entry is too short");
        }
        int width = readLe16(entry, 0);
        int height = readLe16(entry, 2);
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("image declares a size of " + width + "x" + height);
        }

        int pixels = Math.multiplyExact(width, height);
        byte[] data = new byte[pixels];
        // Some entries are a few bytes short of their declared size; take what
        // is there rather than refusing the image, which is what wartool's
        // memcpy effectively does when the archive is ragged.
        int available = Math.min(pixels, entry.length - HEADER_BYTES);
        System.arraycopy(entry, HEADER_BYTES, data, 0, available);
        return new IndexedImage(width, height, data);
    }

    /** The declared size, without decoding the pixels. */
    public static int[] size(byte[] entry) {
        return new int[] {readLe16(entry, 0), readLe16(entry, 2)};
    }

    private static int readLe16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }
}
