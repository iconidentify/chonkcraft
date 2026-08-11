package net.chonkbase.chonkcraft.data.graphic;

/**
 * Decodes a Warcraft II mouse pointer.
 *
 * <p>Implements {@code ConvertCur}.
 *
 * <p>A cursor is not an image with a hotspot attached. Its header is four
 * 16-bit values -- hotspot x, hotspot y, width, height -- where a flat image's
 * is two. Decoding one as the other reads the hotspot as the dimensions, which
 * produces a picture of the right bytes at the wrong size: recognisably
 * pointer-shaped and completely wrong.
 *
 * <p>The other difference is transparency. In a cursor, palette index 0 is the
 * hole rather than a colour, so it is moved to 255, which is the index the
 * rest of this implementation treats as transparent.
 */
public final class CursorDecoder {

    /** Where the pixels begin: two hotspot values and two dimensions. */
    private static final int HEADER_BYTES = 8;

    private CursorDecoder() {
    }

    /** Thrown when the bytes are not a cursor this can read. */
    public static final class NotCursorException extends RuntimeException {
        NotCursorException(String message) {
            super(message);
        }
    }

    /**
     * A decoded pointer.
     *
     * @param image   the picture, with 255 transparent
     * @param hotspotX where the point of the pointer is
     * @param hotspotY likewise
     */
    public record Cursor(IndexedImage image, int hotspotX, int hotspotY) {}

    /** Whether some bytes plausibly describe a cursor. */
    public static boolean looksLikeCursor(byte[] data) {
        if (data == null || data.length < HEADER_BYTES) {
            return false;
        }
        int width = readShort(data, 4);
        int height = readShort(data, 6);
        return width > 0 && height > 0 && width <= 256 && height <= 256
                && data.length >= HEADER_BYTES + width * height;
    }

    /** Reads one pointer. */
    public static Cursor decode(byte[] data) {
        if (data == null || data.length < HEADER_BYTES) {
            throw new NotCursorException("too short to be a cursor");
        }
        int hotspotX = readShort(data, 0);
        int hotspotY = readShort(data, 2);
        int width = readShort(data, 4);
        int height = readShort(data, 6);
        if (width <= 0 || height <= 0 || width > 256 || height > 256) {
            throw new NotCursorException("implausible cursor size " + width + "x" + height);
        }

        IndexedImage image = new IndexedImage(width, height);
        byte[] pixels = image.pixels();
        int available = Math.min(width * height, data.length - HEADER_BYTES);
        for (int i = 0; i < available; i++) {
            int index = data[HEADER_BYTES + i] & 0xFF;
            // Index 0 is the hole in a cursor, not a colour.
            pixels[i] = (byte) (index == 0 ? 255 : index);
        }
        // Anything the file did not reach stays transparent rather than black.
        for (int i = available; i < pixels.length; i++) {
            pixels[i] = (byte) 255;
        }
        return new Cursor(image, hotspotX, hotspotY);
    }

    private static int readShort(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }
}
