package net.chonkbase.chonkcraft.data.graphic;

import java.nio.charset.StandardCharsets;

/**
 * Decodes Warcraft II's bitmap fonts.
 *
 * <p>Implements {@code ConvertFnt}. The game ships five
 * of them, and this implementation drew all its text with whatever font the JVM had
 * lying about, which reads as a 1995 game someone has typed over.
 *
 * <p>The format is a header, a table of glyph offsets, and a run-length stream
 * per glyph. Every byte in that stream carries two things at once: the top
 * five bits say how many transparent pixels to skip, the bottom three say
 * which of eight shades to put down next. That is why a glyph is small enough
 * to be worth the trouble, and why reading it as one value per pixel produces
 * a smear rather than a letter.
 *
 * <p>Glyphs are laid out fifteen to a row in the sheet, which is the layout
 * the extractor writes and the one everything downstream expects.
 */
public final class FontDecoder {

    /** Glyphs per row in the assembled sheet. */
    public static final int PER_ROW = 15;

    /**
     * The character the first glyph stands for.
     *
     * <p>The header's count includes the unprintable range, so the table is
     * shifted by a space: glyph zero is {@code ' '}.
     */
    public static final int FIRST_CHARACTER = 32;

    /** The palette index left where a glyph does not draw. */
    public static final int TRANSPARENT = 255;

    private FontDecoder() {
    }

    /** Thrown when the bytes are not a font this can read. */
    public static final class NotFontException extends RuntimeException {
        NotFontException(String message) {
            super(message);
        }
    }

    /**
     * One decoded font.
     *
     * @param sheet      the glyphs, fifteen to a row
     * @param glyphWidth the widest a glyph can be, which is the cell width
     * @param glyphHeight the cell height
     * @param count      how many glyphs the sheet holds
     * @param widths     each glyph's true width, for spacing text
     */
    public record Font(IndexedImage sheet, int glyphWidth, int glyphHeight, int count,
            int[] widths) {

        /** The cell a character occupies, or {@code -1} if it has none. */
        public int glyphOf(char character) {
            int index = character - FIRST_CHARACTER;
            return index >= 0 && index < count ? index : -1;
        }

        /** How wide a character draws, falling back to the cell width. */
        public int widthOf(char character) {
            int glyph = glyphOf(character);
            if (glyph < 0) {
                return glyphWidth / 2;
            }
            return widths[glyph] > 0 ? widths[glyph] : glyphWidth / 2;
        }

        /** How wide a string draws. */
        public int widthOf(String text) {
            int width = 0;
            for (int i = 0; i < text.length(); i++) {
                width += widthOf(text.charAt(i)) + 1;
            }
            return width;
        }
    }

    /** Whether some bytes look like a font. */
    public static boolean looksLikeFont(byte[] data) {
        return data != null && data.length > 8
                && "FONT ".equals(new String(data, 0, 5, StandardCharsets.ISO_8859_1));
    }

    /** Reads one font. */
    public static Font decode(byte[] data) {
        if (!looksLikeFont(data)) {
            throw new NotFontException("not a Warcraft II font");
        }
        int count = (data[5] & 0xFF) - FIRST_CHARACTER;
        int glyphWidth = data[6] & 0xFF;
        int glyphHeight = data[7] & 0xFF;
        if (count <= 0 || glyphWidth <= 0 || glyphHeight <= 0) {
            throw new NotFontException("implausible font header");
        }

        int rows = (count + PER_ROW - 1) / PER_ROW;
        IndexedImage sheet = new IndexedImage(glyphWidth * PER_ROW, rows * glyphHeight);
        java.util.Arrays.fill(sheet.pixels(), (byte) TRANSPARENT);

        int[] offsets = new int[count];
        for (int i = 0; i < count; i++) {
            int at = 8 + i * 4;
            if (at + 4 > data.length) {
                break;
            }
            offsets[i] = (data[at] & 0xFF)
                    | ((data[at + 1] & 0xFF) << 8)
                    | ((data[at + 2] & 0xFF) << 16)
                    | ((data[at + 3] & 0xFF) << 24);
        }

        int[] widths = new int[count];
        for (int glyph = 0; glyph < count; glyph++) {
            // A zero offset means the character is not in the font at all,
            // which is ordinary: the range has gaps.
            if (offsets[glyph] <= 0 || offsets[glyph] + 4 > data.length) {
                continue;
            }
            widths[glyph] = drawGlyph(data, offsets[glyph], sheet, glyph,
                    glyphWidth, glyphHeight);
        }
        return new Font(sheet, glyphWidth, glyphHeight, count, widths);
    }

    /**
     * Paints one glyph into its cell.
     *
     * @return the glyph's own width, which is narrower than the cell for most
     *         letters and is what makes text look spaced rather than tabulated
     */
    private static int drawGlyph(byte[] data, int offset, IndexedImage sheet, int glyph,
            int glyphWidth, int glyphHeight) {
        int cursor = offset;
        int width = data[cursor++] & 0xFF;
        int height = data[cursor++] & 0xFF;
        int offsetX = data[cursor++] & 0xFF;
        int offsetY = data[cursor++] & 0xFF;
        if (width <= 0 || height <= 0) {
            return width;
        }

        int cellX = (glyph % PER_ROW) * glyphWidth + offsetX;
        int cellY = (glyph / PER_ROW) * glyphHeight + offsetY;

        int x = 0;
        int y = 0;
        while (cursor < data.length && y < height) {
            int control = data[cursor++] & 0xFF;
            // The top five bits skip, the bottom three colour.
            x += (control >> 3) & 0x1F;
            while (x >= width) {
                x -= width;
                if (++y >= height) {
                    return width + offsetX;
                }
            }
            sheet.set(cellX + x, cellY + y, control & 0x07);
            if (++x >= width) {
                x -= width;
                if (++y >= height) {
                    return width + offsetX;
                }
            }
        }
        return width + offsetX;
    }
}
