package net.chonkbase.chonkcraft.data.graphic;

/**
 * A 256-entry colour palette.
 *
 * <p>Warcraft II stores palettes as 768 bytes of 6-bit VGA components. Scaling
 * to 8 bits is what {@code ConvertPalette} does with {@code pal[i] <<= 2}, so
 * a component of 0x3F becomes 0xFC rather than 0xFF. That is not a rounding
 * bug to fix: matching it keeps every extracted pixel identical to what the
 * C++ tool produces, and the engine's colour-cycling and player-colour
 * remapping index these exact values.
 */
public final class Palette {

    /** The palette index the engine treats as transparent. */
    public static final int TRANSPARENT_INDEX = 255;

    private static final int ENTRIES = 256;
    private static final int VGA_BYTES = ENTRIES * 3;

    private final byte[] red = new byte[ENTRIES];
    private final byte[] green = new byte[ENTRIES];
    private final byte[] blue = new byte[ENTRIES];

    private Palette() {
    }

    /**
     * Reads a 768-byte 6-bit VGA palette and scales it to 8 bits.
     *
     * @throws IllegalArgumentException if {@code data} is not 768 bytes
     */
    public static Palette fromVga(byte[] data) {
        if (data.length != VGA_BYTES) {
            throw new IllegalArgumentException("expected a " + VGA_BYTES + "-byte palette, got " + data.length);
        }
        Palette palette = new Palette();
        for (int i = 0; i < ENTRIES; i++) {
            palette.red[i] = (byte) ((data[i * 3] & 0xFF) << 2);
            palette.green[i] = (byte) ((data[i * 3 + 1] & 0xFF) << 2);
            palette.blue[i] = (byte) ((data[i * 3 + 2] & 0xFF) << 2);
        }
        return palette;
    }

    /**
     * Reads a 768-byte palette that is already 8 bits per component.
     *
     * <p>Smacker carries its palette expanded rather than as the 6-bit values
     * the VGA hardware took, so scaling it again would wash the picture out.
     *
     * @throws IllegalArgumentException if {@code data} is not 768 bytes
     */
    public static Palette fromRgb(byte[] data) {
        if (data.length != VGA_BYTES) {
            throw new IllegalArgumentException(
                    "expected a " + VGA_BYTES + "-byte palette, got " + data.length);
        }
        Palette palette = new Palette();
        for (int i = 0; i < ENTRIES; i++) {
            palette.red[i] = data[i * 3];
            palette.green[i] = data[i * 3 + 1];
            palette.blue[i] = data[i * 3 + 2];
        }
        return palette;
    }

    /** Whether {@code data} looks like a 6-bit VGA palette: 768 bytes, no component above 0x3F. */
    public static boolean looksLikeVga(byte[] data) {
        if (data.length != VGA_BYTES) {
            return false;
        }
        for (byte component : data) {
            if ((component & 0xFF) > 0x3F) {
                return false;
            }
        }
        return true;
    }

    public int red(int index) {
        return red[index] & 0xFF;
    }

    public int green(int index) {
        return green[index] & 0xFF;
    }

    public int blue(int index) {
        return blue[index] & 0xFF;
    }

    /** Colour {@code index} as packed 0xRRGGBB. */
    /** This palette as an AWT colour model, with index 255 transparent. */
    public java.awt.image.IndexColorModel toColorModel() {
        byte[] r = red.clone();
        byte[] g = green.clone();
        byte[] b = blue.clone();
        return new java.awt.image.IndexColorModel(8, ENTRIES, r, g, b, TRANSPARENT_INDEX);
    }

    /**
     * A copy with the given index ranges rotated by one.
     *
     * <p>What Warcraft II's animated water is: the pixels never change, the
     * colours walk along their range and wrap. A range of five entries takes
     * five steps to return to where it started.
     *
     * @param ranges pairs of inclusive first and last indices
     */
    public Palette cycled(java.util.List<int[]> ranges) {
        Palette out = new Palette();
        System.arraycopy(red, 0, out.red, 0, ENTRIES);
        System.arraycopy(green, 0, out.green, 0, ENTRIES);
        System.arraycopy(blue, 0, out.blue, 0, ENTRIES);
        for (int[] range : ranges) {
            int first = Math.max(0, Math.min(ENTRIES - 1, range[0]));
            int last = Math.max(0, Math.min(ENTRIES - 1, range[1]));
            if (last <= first) {
                continue;
            }
            byte firstRed = out.red[first];
            byte firstGreen = out.green[first];
            byte firstBlue = out.blue[first];
            for (int i = first; i < last; i++) {
                out.red[i] = out.red[i + 1];
                out.green[i] = out.green[i + 1];
                out.blue[i] = out.blue[i + 1];
            }
            out.red[last] = firstRed;
            out.green[last] = firstGreen;
            out.blue[last] = firstBlue;
        }
        return out;
    }

    public int rgb(int index) {
        return (red(index) << 16) | (green(index) << 8) | blue(index);
    }

    /**
     * Colour {@code index} as packed 0xAARRGGBB, with
     * {@link #TRANSPARENT_INDEX} fully transparent.
     */
    public int argb(int index) {
        if (index == TRANSPARENT_INDEX) {
            return 0;
        }
        return 0xFF00_0000 | rgb(index);
    }
}
