package net.chonkbase.chonkcraft.data.graphic;

/**
 * Decodes Warcraft II sprite sheets into indexed images.
 *
 * <p>Implements {@code ConvertGraphic}, {@code DecodeGfxEntry} and
 * {@code DecodeGfuEntry}, laying frames out on a
 * sheet the same way so the frame arithmetic in the ported engine keeps
 * working unchanged.
 *
 * <p>Entry layout, all little-endian:
 *
 * <pre>
 *   u16  frameCount
 *   u16  maxWidth
 *   u16  maxHeight
 *   frameCount x {
 *     u8   xOffset
 *     u8   yOffset
 *     u8   width
 *     u8   height
 *     u32  dataOffset      relative to the start of the entry
 *   }
 * </pre>
 *
 * <p>There are two pixel encodings. {@link Kind#GFX} frames are run-length
 * coded with a per-row offset table; {@link Kind#GFU} frames are raw rows. The
 * top bit of {@code dataOffset} adds 256 to the width, which is how frames
 * wider than 255 pixels are expressed.
 */
public final class GraphicDecoder {

    /** Which of the two pixel encodings an entry uses. */
    public enum Kind {
        /** Run-length coded with a row offset table. Units, missiles, most art. */
        GFX,
        /** Raw uncompressed rows. Interface pieces and some overlays. */
        GFU
    }

    /**
     * Frames per row on a GFX sheet.
     *
     * <p>Five, matching {@code IPR} in {@code ConvertGraphic}. Sheets with
     * fewer frames than that go in a single column instead.
     */
    private static final int FRAMES_PER_ROW = 5;

    private static final int HEADER_BYTES = 6;
    private static final int FRAME_HEADER_BYTES = 8;

    private GraphicDecoder() {
    }

    /**
     * Decodes a sprite sheet.
     *
     * @param kind  which pixel encoding the entry uses
     * @param entry the decompressed archive entry
     */
    public static IndexedImage decode(Kind kind, byte[] entry) {
        return decode(kind, entry, null, 0);
    }

    /**
     * Decodes a sprite sheet, optionally taking later frames from a second entry.
     *
     * <p>The two-entry form exists for one case in the original data: the
     * worker sprites, whose carrying-resources and repairing animations live in
     * a separate entry that continues the same frame numbering.
     *
     * @param kind        which pixel encoding the entry uses
     * @param entry       the primary decompressed entry
     * @param secondEntry frames from {@code secondFrame} onward, or {@code null}
     * @param secondFrame the first frame index taken from {@code secondEntry}
     */
    public static IndexedImage decode(Kind kind, byte[] entry, byte[] secondEntry, int secondFrame) {
        int frameCount = readLe16(entry, 0);
        int maxWidth = readLe16(entry, 2);
        int maxHeight = readLe16(entry, 4);
        if (frameCount <= 0) {
            throw new IllegalArgumentException("sprite entry declares " + frameCount + " frames");
        }

        int frameWidth;
        int frameHeight;
        int framesPerRow;
        int paddedFrameCount;

        if (kind == Kind.GFX) {
            // GFX sheets use the declared maximum as the cell size and pad the
            // frame count out to a whole number of rows.
            frameWidth = maxWidth;
            frameHeight = maxHeight;
            framesPerRow = frameCount < FRAMES_PER_ROW ? 1 : FRAMES_PER_ROW;
            paddedFrameCount = framesPerRow == 1
                    ? frameCount
                    : ((frameCount + FRAMES_PER_ROW - 1) / FRAMES_PER_ROW) * FRAMES_PER_ROW;
        } else {
            // GFU sheets are a single column sized to the frames' real extent.
            Extent extent = measure(entry, frameCount);
            frameWidth = extent.width();
            frameHeight = extent.height();
            framesPerRow = 1;
            paddedFrameCount = frameCount;
        }

        int sheetWidth = frameWidth * framesPerRow;
        int sheetHeight = frameHeight * (paddedFrameCount / framesPerRow);
        IndexedImage sheet = new IndexedImage(sheetWidth, sheetHeight);
        // Cells with no frame, and every pixel a frame does not cover, stay
        // transparent rather than palette-zero black.
        sheet.fill(Palette.TRANSPARENT_INDEX);

        for (int frame = 0; frame < frameCount; frame++) {
            int cellX = (frame % framesPerRow) * frameWidth;
            int cellY = (frame / framesPerRow) * frameHeight;
            byte[] source = (secondEntry != null && frame >= secondFrame) ? secondEntry : entry;
            if (kind == Kind.GFX) {
                decodeGfxFrame(source, frame, sheet, cellX, cellY);
            } else {
                decodeGfuFrame(source, frame, sheet, cellX, cellY);
            }
        }
        return sheet;
    }

    /** The number of frames an entry declares. */
    public static int frameCount(byte[] entry) {
        return readLe16(entry, 0);
    }

    // --------------------------------------------------------------- framing

    private record Extent(int width, int height) {}

    /** Widest and tallest frame extent, used to size a GFU sheet. */
    private static Extent measure(byte[] entry, int frameCount) {
        int width = 0;
        int height = 0;
        for (int frame = 0; frame < frameCount; frame++) {
            int header = HEADER_BYTES + frame * FRAME_HEADER_BYTES;
            int xOffset = entry[header] & 0xFF;
            int yOffset = entry[header + 1] & 0xFF;
            int frameWidth = entry[header + 2] & 0xFF;
            int frameHeight = entry[header + 3] & 0xFF;
            if ((readLe32(entry, header + 4) & 0x8000_0000) != 0) {
                frameWidth += 256;
            }
            width = Math.max(width, xOffset + frameWidth);
            height = Math.max(height, yOffset + frameHeight);
        }
        return new Extent(width, height);
    }

    // -------------------------------------------------------------- decoding

    /**
     * Run-length decodes one frame.
     *
     * <p>Each row starts at its own offset from a per-frame table. Within a
     * row, a control byte with bit 7 set is a transparent run, bit 6 set is a
     * repeated pixel, and neither set is a literal run; the low six or seven
     * bits give the length.
     */
    private static void decodeGfxFrame(byte[] entry, int frame, IndexedImage sheet, int cellX, int cellY) {
        int header = HEADER_BYTES + frame * FRAME_HEADER_BYTES;
        int xOffset = entry[header] & 0xFF;
        int yOffset = entry[header + 1] & 0xFF;
        int width = entry[header + 2] & 0xFF;
        int height = entry[header + 3] & 0xFF;
        int rowTable = readLe32(entry, header + 4);

        for (int row = 0; row < height; row++) {
            int cursor = rowTable + readLe16(entry, rowTable + row * 2);
            int x = 0;
            int y = cellY + yOffset + row;
            while (x < width) {
                int control = entry[cursor++] & 0xFF;
                if ((control & 0x80) != 0) {
                    int run = control & 0x7F;
                    for (int i = 0; i < run && x < width; i++, x++) {
                        sheet.set(cellX + xOffset + x, y, Palette.TRANSPARENT_INDEX);
                    }
                } else if ((control & 0x40) != 0) {
                    int run = control & 0x3F;
                    int value = entry[cursor++] & 0xFF;
                    for (int i = 0; i < run && x < width; i++, x++) {
                        sheet.set(cellX + xOffset + x, y, value);
                    }
                } else {
                    int run = control & 0x3F;
                    for (int i = 0; i < run && x < width; i++, x++) {
                        sheet.set(cellX + xOffset + x, y, entry[cursor++] & 0xFF);
                    }
                }
            }
        }
    }

    /** Copies one frame's raw rows. */
    private static void decodeGfuFrame(byte[] entry, int frame, IndexedImage sheet, int cellX, int cellY) {
        int header = HEADER_BYTES + frame * FRAME_HEADER_BYTES;
        int xOffset = entry[header] & 0xFF;
        int yOffset = entry[header + 1] & 0xFF;
        int width = entry[header + 2] & 0xFF;
        int height = entry[header + 3] & 0xFF;
        int dataOffset = readLe32(entry, header + 4);
        if ((dataOffset & 0x8000_0000) != 0) {
            dataOffset &= 0x7FFF_FFFF;
            width += 256;
        }

        int cursor = dataOffset;
        for (int row = 0; row < height; row++) {
            for (int x = 0; x < width; x++) {
                sheet.set(cellX + xOffset + x, cellY + yOffset + row, entry[cursor++] & 0xFF);
            }
        }
    }

    private static int readLe16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int readLe32(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }
}
