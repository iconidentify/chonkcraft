package net.chonkbase.chonkcraft.data.graphic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

/** Unit tests for the sprite decoders, over hand-assembled entries. */
class GraphicDecoderTest {

    /**
     * Builds a one-frame GFX entry.
     *
     * @param rows one run-length coded row each, in the order they are drawn
     */
    private static byte[] gfxEntry(int width, int height, byte[]... rows) {
        int frameHeaderAt = 6;
        int rowTableAt = frameHeaderAt + 8;
        int rowDataAt = rowTableAt + rows.length * 2;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLe16(out, 1);          // frame count
        writeLe16(out, width);      // max width
        writeLe16(out, height);     // max height
        out.write(0);               // x offset
        out.write(0);               // y offset
        out.write(width);
        out.write(height);
        writeLe32(out, rowTableAt); // row table position

        // Row offsets are relative to the row table itself.
        int cursor = rowDataAt - rowTableAt;
        for (byte[] row : rows) {
            writeLe16(out, cursor);
            cursor += row.length;
        }
        for (byte[] row : rows) {
            out.writeBytes(row);
        }
        return out.toByteArray();
    }

    private static void writeLe16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void writeLe32(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    @Test
    void decodesALiteralRun() {
        // Control byte 0x04: neither high bit set, so four literal pixels.
        byte[] entry = gfxEntry(4, 1, new byte[] {0x04, 10, 20, 30, 40});
        IndexedImage image = GraphicDecoder.decode(GraphicDecoder.Kind.GFX, entry);

        assertEquals(10, image.get(0, 0));
        assertEquals(20, image.get(1, 0));
        assertEquals(30, image.get(2, 0));
        assertEquals(40, image.get(3, 0));
    }

    @Test
    void decodesARepeatRun() {
        // Bit 6 set: repeat the following byte four times.
        byte[] entry = gfxEntry(4, 1, new byte[] {0x44, 77});
        IndexedImage image = GraphicDecoder.decode(GraphicDecoder.Kind.GFX, entry);

        for (int x = 0; x < 4; x++) {
            assertEquals(77, image.get(x, 0));
        }
    }

    @Test
    void decodesATransparentRun() {
        // Bit 7 set: skip four pixels, leaving them transparent.
        byte[] entry = gfxEntry(4, 1, new byte[] {(byte) 0x84});
        IndexedImage image = GraphicDecoder.decode(GraphicDecoder.Kind.GFX, entry);

        for (int x = 0; x < 4; x++) {
            assertEquals(Palette.TRANSPARENT_INDEX, image.get(x, 0));
        }
    }

    @Test
    void mixesRunTypesWithinARow() {
        // Two transparent, one repeated pair, then two literals.
        byte[] entry = gfxEntry(6, 1, new byte[] {(byte) 0x82, 0x42, 99, 0x02, 5, 6});
        IndexedImage image = GraphicDecoder.decode(GraphicDecoder.Kind.GFX, entry);

        assertEquals(Palette.TRANSPARENT_INDEX, image.get(0, 0));
        assertEquals(Palette.TRANSPARENT_INDEX, image.get(1, 0));
        assertEquals(99, image.get(2, 0));
        assertEquals(99, image.get(3, 0));
        assertEquals(5, image.get(4, 0));
        assertEquals(6, image.get(5, 0));
    }

    @Test
    void everyRowHasItsOwnOffset() {
        // Rows are found through a table, not by scanning, so rows of unequal
        // encoded length still line up.
        byte[] entry = gfxEntry(2, 2,
                new byte[] {0x42, 1},        // two pixels of 1, two bytes encoded
                new byte[] {0x02, 8, 9});    // two literals, three bytes encoded
        IndexedImage image = GraphicDecoder.decode(GraphicDecoder.Kind.GFX, entry);

        assertEquals(1, image.get(0, 0));
        assertEquals(1, image.get(1, 0));
        assertEquals(8, image.get(0, 1));
        assertEquals(9, image.get(1, 1));
    }

    @Test
    void laysFramesOutFiveToARow() {
        // A sheet with more than five frames wraps, and the padded tail cells
        // stay transparent.
        int frames = 7;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLe16(out, frames);
        writeLe16(out, 8);
        writeLe16(out, 8);
        int rowTableAt = 6 + frames * 8;
        for (int i = 0; i < frames; i++) {
            out.write(0);
            out.write(0);
            out.write(1);
            out.write(1);
            writeLe32(out, rowTableAt);
        }
        writeLe16(out, 2);          // single row, data follows the table
        out.writeBytes(new byte[] {0x01, 42});

        IndexedImage image = GraphicDecoder.decode(GraphicDecoder.Kind.GFX, out.toByteArray());

        // Seven frames pad to ten cells: two rows of five 8x8 cells.
        assertEquals(40, image.width());
        assertEquals(16, image.height());
        assertEquals(42, image.get(0, 0));   // frame 0
        assertEquals(42, image.get(32, 0));  // frame 4, last of the first row
        assertEquals(42, image.get(0, 8));   // frame 5, wrapped
        assertEquals(Palette.TRANSPARENT_INDEX, image.get(16, 8)); // unused cell 7
    }

    @Test
    void singleColumnLayoutBelowFiveFrames() {
        byte[] entry = gfxEntry(4, 1, new byte[] {0x04, 1, 2, 3, 4});
        IndexedImage image = GraphicDecoder.decode(GraphicDecoder.Kind.GFX, entry);
        assertEquals(4, image.width());
        assertEquals(1, image.height());
    }

    @Test
    void decodesRawGfuRows() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLe16(out, 1);
        writeLe16(out, 2);
        writeLe16(out, 2);
        out.write(0);
        out.write(0);
        out.write(2);
        out.write(2);
        writeLe32(out, 14);
        out.writeBytes(new byte[] {1, 2, 3, 4});

        IndexedImage image = GraphicDecoder.decode(GraphicDecoder.Kind.GFU, out.toByteArray());
        assertEquals(1, image.get(0, 0));
        assertEquals(2, image.get(1, 0));
        assertEquals(3, image.get(0, 1));
        assertEquals(4, image.get(1, 1));
    }

    @Test
    void gfuWidthsAbove255UseTheOffsetHighBit() {
        // A 300-pixel-wide frame: width byte holds 44, and the top bit of the
        // data offset signals the missing 256.
        int width = 300;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLe16(out, 1);
        writeLe16(out, width);
        writeLe16(out, 1);
        out.write(0);
        out.write(0);
        out.write(width - 256);
        out.write(1);
        writeLe32(out, 0x8000_0000 | 14);
        for (int x = 0; x < width; x++) {
            out.write(x & 0x7F);
        }

        IndexedImage image = GraphicDecoder.decode(GraphicDecoder.Kind.GFU, out.toByteArray());
        assertEquals(width, image.width());
        assertEquals(299 & 0x7F, image.get(299, 0));
    }

    @Test
    void readsFrameCountWithoutDecoding() {
        byte[] entry = gfxEntry(1, 1, new byte[] {0x01, 7});
        assertEquals(1, GraphicDecoder.frameCount(entry));
    }
}
