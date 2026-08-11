package net.chonkbase.chonkcraft.data.graphic;

import java.io.ByteArrayOutputStream;

/**
 * Reads the 8-bit PCX pictures used by Warcraft II Battle.net Edition.
 *
 * <p>The earlier WAR archives store flat pictures as width, height and raw
 * palette indices. BNE stores the same pictures as named PCX files in
 * War2Dat.mpq. This decoder is the bridge between those two representations,
 * so a named BNE picture can occupy the numbered slot the Java game already
 * understands.
 */
public final class PcxDecoder {

    private static final int HEADER_BYTES = 128;
    private static final int PALETTE_BYTES = 768;

    /** One decoded indexed picture and its palette in Warcraft's VGA form. */
    public record Pcx(IndexedImage image, byte[] vgaPalette) {}

    private PcxDecoder() {
    }

    /** Decodes an 8-bit, single-plane PCX file. */
    public static Pcx decode(byte[] data) {
        if (data == null || data.length < HEADER_BYTES + 1 + PALETTE_BYTES) {
            throw new IllegalArgumentException("PCX file is too short");
        }
        if ((data[0] & 0xFF) != 0x0A || (data[2] & 0xFF) != 1
                || (data[3] & 0xFF) != 8 || (data[65] & 0xFF) != 1) {
            throw new IllegalArgumentException("unsupported PCX encoding");
        }
        int xMin = le16(data, 4);
        int yMin = le16(data, 6);
        int width = le16(data, 8) - xMin + 1;
        int height = le16(data, 10) - yMin + 1;
        int bytesPerLine = le16(data, 66);
        if (width <= 0 || height <= 0 || bytesPerLine < width
                || width > 4096 || height > 4096) {
            throw new IllegalArgumentException(
                    "implausible PCX size " + width + "x" + height);
        }

        int paletteAt = data.length - PALETTE_BYTES;
        if ((data[paletteAt - 1] & 0xFF) != 0x0C) {
            throw new IllegalArgumentException("PCX has no 256-colour palette");
        }
        IndexedImage image = new IndexedImage(width, height);
        int cursor = HEADER_BYTES;
        for (int y = 0; y < height; y++) {
            int x = 0;
            while (x < bytesPerLine) {
                if (cursor >= paletteAt - 1) {
                    throw new IllegalArgumentException("PCX pixel data ends early");
                }
                int value = data[cursor++] & 0xFF;
                int run = 1;
                if ((value & 0xC0) == 0xC0) {
                    run = value & 0x3F;
                    if (cursor >= paletteAt - 1) {
                        throw new IllegalArgumentException("PCX run has no value");
                    }
                    value = data[cursor++] & 0xFF;
                }
                for (int i = 0; i < run && x < bytesPerLine; i++, x++) {
                    if (x < width) {
                        image.set(x, y, value);
                    }
                }
            }
        }

        byte[] palette = new byte[PALETTE_BYTES];
        for (int i = 0; i < PALETTE_BYTES; i++) {
            // PCX stores 8-bit components; Warcraft's archive entries store
            // the six bits accepted by VGA hardware.
            palette[i] = (byte) ((data[paletteAt + i] & 0xFF) >>> 2);
        }
        return new Pcx(image, palette);
    }

    /** Converts a PCX into the flat numbered-archive image representation. */
    public static byte[] imageEntry(byte[] data) {
        IndexedImage image = decode(data).image();
        ByteArrayOutputStream out = new ByteArrayOutputStream(4 + image.pixels().length);
        out.write(image.width());
        out.write(image.width() >>> 8);
        out.write(image.height());
        out.write(image.height() >>> 8);
        out.writeBytes(image.pixels());
        return out.toByteArray();
    }

    private static int le16(byte[] data, int at) {
        return (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8);
    }
}
