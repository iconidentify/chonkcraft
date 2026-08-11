package net.chonkbase.chonkcraft.data.graphic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Writes indexed sprite sheets back out as Warcraft II archive entries.
 *
 * <p>New infrastructure with no upstream analogue. only
 * ever goes the other way: it reads an entry and writes a PNG, and nothing in
 * Warcraft II or ChonkCraft ever writes the entry format back. What this produces
 * is the input side of {@code DecodeGfxEntry} and {@code DecodeGfuEntry}, so
 * {@link GraphicDecoder} is the specification and this class is written as its
 * exact mirror -- the same frame table, the same sheet layout, the same run
 * control bits.
 *
 * <p>It exists because the asset pack keeps every sprite sheet as an editable
 * indexed PNG rather than as the 1995 run-length coded blob, so that an artist
 * can open a footman, repaint him and hand him back. The engine still asks for
 * the sheet through {@link GraphicDecoder}, which wants an entry, so the pack
 * has to be able to make one.
 *
 * <p><b>The contract is decode-identity, not byte-identity, and that is
 * deliberate.</b> An entry produced here decodes to exactly the pixels the
 * sheet holds; it is not the same bytes the 1995 archive held, and no attempt
 * is made to make it so. Where a run gets split is a free choice that the
 * decoder cannot tell apart, and the pack's sheets are meant to be repainted
 * anyway, so an entry that matched byte for byte would only do so until the
 * first artist touched it. A future reader who diffs an encode against the
 * archive and finds it different has found the documented behaviour, not a
 * bug. The only thing that has to hold is that
 * {@code decode(encode(decode(entry)))} and {@code decode(entry)} produce the
 * same pixels, and {@code GraphicEncoderTest} proves that over every sprite in
 * the game.
 *
 * <p>The size is close all the same, which is what makes the pack affordable:
 * the 331 sprite rows in the conversion table occupy 5860044 bytes in the
 * archive and 5856256 bytes re-encoded, a difference of 3788 bytes in this
 * encoder's favour. Stored as raw pixels instead they would be 19789168.
 */
public final class GraphicEncoder {

    /**
     * Frames per row on a GFX sheet.
     *
     * <p>Five, and sheets of fewer than five frames go in a single column
     * instead. Both numbers are read off {@link GraphicDecoder}, which is
     * where they have to come from: a sheet written in one layout and read
     * back in another hands the engine frame 7 when it asked for frame 3, so
     * a repainted footman would walk with a dying unit's legs.
     */
    private static final int FRAMES_PER_ROW = 5;

    private static final int HEADER_BYTES = 6;
    private static final int FRAME_HEADER_BYTES = 8;

    /** Longest transparent run a control byte can carry: {@code control & 0x7F}. */
    private static final int MAX_TRANSPARENT_RUN = 0x7F;

    /** Longest repeat or literal run a control byte can carry: {@code control & 0x3F}. */
    private static final int MAX_RUN = 0x3F;

    /**
     * The run length at which a repeat starts beating a literal.
     *
     * <p>A repeat costs two bytes whatever its length; a literal costs one
     * byte per pixel, and its own control byte is amortised over up to 63 of
     * them. So two equal pixels are a wash and three are the first win.
     */
    private static final int REPEAT_THRESHOLD = 3;

    private GraphicEncoder() {
    }

    /** One frame's placement, as the entry's frame table records it. */
    public record Frame(int xOffset, int yOffset, int width, int height) {}

    /**
     * Reads the frame table out of an original entry, for a pack to record.
     *
     * <p>The offsets and sizes are not recoverable from the decoded sheet. A
     * frame is drawn at its own offset inside a cell that is usually bigger
     * than it is, and everything the frame does not cover is transparent, so
     * a footman standing in the middle of a 72 by 72 cell could have come from
     * any of a few thousand offset-and-size combinations that all look the
     * same once drawn. Guessing by cropping to the opaque pixels gets it wrong
     * the moment a frame has a transparent edge column, which most of them do,
     * and the unit would shift a pixel or two every time its animation
     * advanced. So the pack stores the table the archive stated.
     *
     * <p>The top bit of the frame's 32-bit word is taken as "add 256 to the
     * width" here for both encodings, although only GFU means it that way; on
     * GFX the word is a row table offset and the bit is part of the number.
     * That is safe rather than sloppy: the largest sprite entry the game ships
     * is 278276 bytes, so a GFX offset does not come within eleven orders of
     * magnitude of setting bit 31, and across all 3486 GFX frames in the
     * conversion table not one has it set.
     */
    public static List<Frame> frames(byte[] entry) {
        int frameCount = readLe16(entry, 0);
        List<Frame> frames = new ArrayList<>(Math.max(frameCount, 0));
        for (int frame = 0; frame < frameCount; frame++) {
            int header = HEADER_BYTES + frame * FRAME_HEADER_BYTES;
            int width = entry[header + 2] & 0xFF;
            if ((readLe32(entry, header + 4) & 0x8000_0000) != 0) {
                width += 256;
            }
            frames.add(new Frame(
                    entry[header] & 0xFF,
                    entry[header + 1] & 0xFF,
                    width,
                    entry[header + 3] & 0xFF));
        }
        return frames;
    }

    /** The declared cell size at the head of an entry: frameCount, maxWidth, maxHeight. */
    public static int[] header(byte[] entry) {
        return new int[] {readLe16(entry, 0), readLe16(entry, 2), readLe16(entry, 4)};
    }

    /**
     * Encodes a sheet back into an entry {@link GraphicDecoder} decodes to the
     * same pixels.
     *
     * <p>{@code maxWidth} and {@code maxHeight} are the cell size the original
     * entry declared, from {@link #header}. They are not derived from the
     * frames, because on GFX they are the cell the decoder lays the sheet out
     * in and a sheet laid out in cells of one size cannot be read back in
     * cells of another.
     *
     * @param kind      which pixel encoding to write
     * @param sheet     the decoded sheet, in the layout {@link GraphicDecoder}
     *                  produced it in
     * @param frames    the frame table, from {@link #frames}
     * @param maxWidth  the declared cell width
     * @param maxHeight the declared cell height
     */
    public static byte[] encode(GraphicDecoder.Kind kind, IndexedImage sheet, List<Frame> frames,
            int maxWidth, int maxHeight) {
        int frameCount = frames.size();
        if (frameCount <= 0) {
            throw new IllegalArgumentException("a sprite entry needs at least one frame");
        }
        if (frameCount > 0xFFFF) {
            throw new IllegalArgumentException(
                    "frame count " + frameCount + " does not fit the entry's 16-bit field");
        }

        // Before the layout, so that a frame the format cannot express is
        // reported as itself rather than as the sheet-size mismatch it causes
        // three lines further down.
        for (Frame frame : frames) {
            checkFrame(kind, frame);
        }

        Layout layout = layoutFor(kind, frames, maxWidth, maxHeight);
        // A sheet of the wrong size is an artist who resized the PNG, and it
        // has to be refused here. Read back at the size the entry declares, a
        // sheet even one pixel wider hands every frame after the first a
        // diagonal slice of its neighbour, which the engine draws without
        // complaint: the unit is there, animating, made of the wrong pixels.
        if (sheet.width() != layout.sheetWidth() || sheet.height() != layout.sheetHeight()) {
            throw new IllegalArgumentException("sheet is " + sheet.width() + "x" + sheet.height()
                    + ", but " + frameCount + " frames of " + layout.frameWidth() + "x"
                    + layout.frameHeight() + " lay out as " + layout.sheetWidth() + "x"
                    + layout.sheetHeight());
        }

        Buffer out = new Buffer(
                HEADER_BYTES + frameCount * FRAME_HEADER_BYTES + sheet.pixels().length);
        out.writeLe16(frameCount);
        out.writeLe16(maxWidth);
        out.writeLe16(maxHeight);
        for (Frame frame : frames) {
            out.write(frame.xOffset());
            out.write(frame.yOffset());
            // A GFU frame wider than 255 stores width minus 256 and says so in
            // the top bit of its offset word, which is how the decoder reads it.
            out.write(frame.width() > 0xFF ? frame.width() - 256 : frame.width());
            out.write(frame.height());
            out.writeLe32(0);
        }

        // Frames that code to the same bytes are written once and pointed at
        // twice. This is not an invention: 616 of the 3125 GFX frames in the
        // shipped conversion table already share a row table with an earlier
        // frame, because a walk cycle that returns to its starting pose, or a
        // building that spends four frames not moving, really does hold the
        // same picture more than once. Written out separately every time, the
        // 331 sprite entries come to 6592268 bytes against the archive's
        // 5860044; shared, they come to 5856256, and the pack stops being
        // twelve per cent more expensive than the data it replaces.
        LinkedHashMap<ByteKey, Integer> written = new LinkedHashMap<>();
        for (int index = 0; index < frameCount; index++) {
            Frame frame = frames.get(index);
            int cellX = (index % layout.framesPerRow()) * layout.frameWidth();
            int cellY = (index / layout.framesPerRow()) * layout.frameHeight();
            byte[] coded = kind == GraphicDecoder.Kind.GFX
                    ? codeGfxFrame(sheet, frame, cellX, cellY, index)
                    : codeGfuFrame(sheet, frame, cellX, cellY);

            // Keyed on the frame's shape as well as its bytes. Two frames
            // that code alike should already be the same shape -- a GFX blob
            // opens with its row table, whose first entry is twice the height,
            // and the runs in a row sum to the width -- but that is an
            // argument rather than a guarantee, and nobody reviewing a change
            // to the run splitter is going to re-derive it. Two integers in
            // the key cost nothing and mean it does not have to hold.
            ByteKey key = new ByteKey(frame.width(), frame.height(), coded);
            Integer at = written.get(key);
            if (at == null) {
                at = out.size();
                if ((at & 0x8000_0000) != 0) {
                    throw new IllegalArgumentException("entry is too large to address at " + at);
                }
                out.write(coded);
                written.put(key, at);
            }
            int word = kind == GraphicDecoder.Kind.GFU && frame.width() > 0xFF
                    ? at | 0x8000_0000
                    : at;
            out.patchLe32(HEADER_BYTES + index * FRAME_HEADER_BYTES + 4, word);
        }
        return out.toByteArray();
    }

    // --------------------------------------------------------------- framing

    private record Layout(int frameWidth, int frameHeight, int framesPerRow,
            int sheetWidth, int sheetHeight) {}

    /**
     * The cell grid the sheet is in.
     *
     * <p>Copied line for line from {@code GraphicDecoder.decode}, including the
     * padding of a GFX frame count up to whole rows of five. Read the two side
     * by side before changing either.
     */
    private static Layout layoutFor(GraphicDecoder.Kind kind, List<Frame> frames,
            int maxWidth, int maxHeight) {
        int frameCount = frames.size();
        int frameWidth;
        int frameHeight;
        int framesPerRow;
        int paddedFrameCount;

        if (kind == GraphicDecoder.Kind.GFX) {
            frameWidth = maxWidth;
            frameHeight = maxHeight;
            framesPerRow = frameCount < FRAMES_PER_ROW ? 1 : FRAMES_PER_ROW;
            paddedFrameCount = framesPerRow == 1
                    ? frameCount
                    : ((frameCount + FRAMES_PER_ROW - 1) / FRAMES_PER_ROW) * FRAMES_PER_ROW;
        } else {
            int width = 0;
            int height = 0;
            for (Frame frame : frames) {
                width = Math.max(width, frame.xOffset() + frame.width());
                height = Math.max(height, frame.yOffset() + frame.height());
            }
            frameWidth = width;
            frameHeight = height;
            framesPerRow = 1;
            paddedFrameCount = frameCount;
        }
        return new Layout(frameWidth, frameHeight, framesPerRow,
                frameWidth * framesPerRow, frameHeight * (paddedFrameCount / framesPerRow));
    }

    private static void checkFrame(GraphicDecoder.Kind kind, Frame frame) {
        if (frame.xOffset() < 0 || frame.xOffset() > 0xFF
                || frame.yOffset() < 0 || frame.yOffset() > 0xFF
                || frame.height() < 0 || frame.height() > 0xFF) {
            throw new IllegalArgumentException("frame " + frame + " does not fit the frame table");
        }
        if (frame.width() < 0) {
            throw new IllegalArgumentException("frame " + frame + " has a negative width");
        }
        // GFX has nowhere to put the extra bit. Its 32-bit word is the row
        // table offset and every bit of it is the offset, so a GFX frame can
        // only ever be as wide as the 8-bit field says. The widest one the
        // game ships is 128.
        int limit = kind == GraphicDecoder.Kind.GFX ? 0xFF : 0xFF + 256;
        if (frame.width() > limit) {
            throw new IllegalArgumentException("a " + kind + " frame cannot be wider than "
                    + limit + " pixels: " + frame);
        }
    }

    // -------------------------------------------------------------- encoding

    /**
     * Run-length codes one frame into its row table and coded rows.
     *
     * <p>The blob is position-independent, which is what makes sharing it
     * between two frames legal: a row offset is counted from the row table
     * rather than from the entry, and the row table is the head of the blob,
     * so the whole thing means the same wherever it lands.
     */
    private static byte[] codeGfxFrame(IndexedImage sheet, Frame frame,
            int cellX, int cellY, int index) {
        Buffer out = new Buffer(frame.height() * (2 + frame.width() + 2));
        out.skip(frame.height() * 2);

        int[] row = new int[frame.width()];
        for (int y = 0; y < frame.height(); y++) {
            int offset = out.size();
            // The row table is 16 bits per entry, so a frame's coded pixels
            // have to fit in 64KB. Nothing shipped comes close -- the largest
            // original row offset in the game is 12876, in the winter orc
            // fortress -- but an oversized hand-made sheet would otherwise
            // wrap silently and decode as noise.
            if (offset > 0xFFFF) {
                throw new IllegalArgumentException("frame " + index + " codes to more than 64KB;"
                        + " its row table cannot address row " + y);
            }
            out.patchLe16(y * 2, offset);
            for (int x = 0; x < frame.width(); x++) {
                row[x] = sheet.get(cellX + frame.xOffset() + x, cellY + frame.yOffset() + y);
            }
            encodeRow(out, row);
        }
        return out.toByteArray();
    }

    /**
     * Codes one row.
     *
     * <p>Every run emitted here covers at least one pixel. The decoder's row
     * loop advances only by what a run covers and stops when it reaches the
     * frame's width, so a run of length zero advances nothing and the loop
     * never ends: the game would hang, silently and with no error, on the
     * first frame that carried one -- which for a unit sheet is before
     * anything is drawn at all.
     */
    private static void encodeRow(Buffer out, int[] row) {
        int width = row.length;
        int x = 0;
        while (x < width) {
            int value = row[x];

            // Transparent first, and always. Index 255 would in fact survive
            // as a repeat or a literal: the decoder writes
            // Palette.TRANSPARENT_INDEX for a transparent run and writes back
            // whatever byte it was handed for the other two, and those are the
            // same number, so the picture is right either way. It is taken
            // here for the room. A transparent run says 127 pixels in one
            // byte where a repeat says 63 in two, and a unit frame is mostly
            // the space around the unit; left to the other two run types, the
            // 331 sprite entries come to 5941971 bytes instead of 5856256,
            // which is 85715 bytes of nothing.
            if (value == Palette.TRANSPARENT_INDEX) {
                int run = 1;
                while (x + run < width && run < MAX_TRANSPARENT_RUN
                        && row[x + run] == Palette.TRANSPARENT_INDEX) {
                    run++;
                }
                out.write(0x80 | run);
                x += run;
                continue;
            }

            int repeat = runLength(row, x, value);
            if (repeat >= REPEAT_THRESHOLD) {
                out.write(0x40 | repeat);
                out.write(value);
                x += repeat;
                continue;
            }

            int start = x;
            int length = 0;
            while (x < width && length < MAX_RUN) {
                int pixel = row[x];
                if (pixel == Palette.TRANSPARENT_INDEX) {
                    break;
                }
                if (runLength(row, x, pixel) >= REPEAT_THRESHOLD) {
                    break;
                }
                x++;
                length++;
            }
            out.write(length);
            for (int i = 0; i < length; i++) {
                out.write(row[start + i]);
            }
        }
    }

    /** How many pixels from {@code x} hold {@code value}, capped at what a control byte says. */
    private static int runLength(int[] row, int x, int value) {
        int run = 1;
        while (x + run < row.length && run < MAX_RUN && row[x + run] == value) {
            run++;
        }
        return run;
    }

    /** Copies one frame's rows out raw, top to bottom, left to right. */
    private static byte[] codeGfuFrame(IndexedImage sheet, Frame frame, int cellX, int cellY) {
        Buffer out = new Buffer(Math.max(frame.width() * frame.height(), 1));
        for (int y = 0; y < frame.height(); y++) {
            for (int x = 0; x < frame.width(); x++) {
                out.write(sheet.get(cellX + frame.xOffset() + x, cellY + frame.yOffset() + y));
            }
        }
        return out.toByteArray();
    }

    /**
     * A coded frame, compared by its shape and its bytes, so that two frames
     * holding the same picture can be written once.
     */
    private record ByteKey(int width, int height, byte[] bytes) {

        @Override
        public boolean equals(Object other) {
            return other instanceof ByteKey key
                    && key.width == width
                    && key.height == height
                    && Arrays.equals(key.bytes, bytes);
        }

        @Override
        public int hashCode() {
            return (width * 31 + height) * 31 + Arrays.hashCode(bytes);
        }
    }

    // ---------------------------------------------------------------- buffer

    /**
     * A growable byte sink that can go back and fill in an offset.
     *
     * <p>Both encodings write a table of offsets to data that has not been
     * written yet, so the writer has to be able to reach back.
     */
    private static final class Buffer {

        private byte[] data;
        private int size;

        Buffer(int capacity) {
            this.data = new byte[Math.max(capacity, 64)];
        }

        int size() {
            return size;
        }

        void write(int value) {
            ensure(1);
            data[size++] = (byte) value;
        }

        void write(byte[] bytes) {
            ensure(bytes.length);
            System.arraycopy(bytes, 0, data, size, bytes.length);
            size += bytes.length;
        }

        void writeLe16(int value) {
            ensure(2);
            data[size++] = (byte) value;
            data[size++] = (byte) (value >>> 8);
        }

        void writeLe32(int value) {
            ensure(4);
            data[size++] = (byte) value;
            data[size++] = (byte) (value >>> 8);
            data[size++] = (byte) (value >>> 16);
            data[size++] = (byte) (value >>> 24);
        }

        void skip(int count) {
            ensure(count);
            size += count;
        }

        void patchLe16(int at, int value) {
            data[at] = (byte) value;
            data[at + 1] = (byte) (value >>> 8);
        }

        void patchLe32(int at, int value) {
            data[at] = (byte) value;
            data[at + 1] = (byte) (value >>> 8);
            data[at + 2] = (byte) (value >>> 16);
            data[at + 3] = (byte) (value >>> 24);
        }

        byte[] toByteArray() {
            return Arrays.copyOf(data, size);
        }

        private void ensure(int extra) {
            if (size + extra > data.length) {
                data = Arrays.copyOf(data, Math.max(size + extra, data.length * 2));
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
