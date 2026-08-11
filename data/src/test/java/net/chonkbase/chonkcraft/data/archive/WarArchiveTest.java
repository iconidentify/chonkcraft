package net.chonkbase.chonkcraft.data.archive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests over hand-assembled archives.
 *
 * <p>These cover the decoder's edge cases precisely, which real data cannot:
 * a back-reference that reads bytes it is still writing, a match truncated by
 * the output filling up, and the malformed entries the shipped archives
 * contain. {@link WarArchiveRealDataTest} then checks the whole thing against
 * actual game data.
 */
class WarArchiveTest {

    private static final int TEST_ID = 1000;

    // ------------------------------------------------------------ assembling

    /** Builds an archive whose entries are the given already-encoded payloads. */
    private static byte[] archive(int id, List<byte[]> entryPayloads) {
        int headerBytes = 8 + entryPayloads.size() * 4;
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int[] offsets = new int[entryPayloads.size()];
        int cursor = headerBytes;
        for (int i = 0; i < entryPayloads.size(); i++) {
            offsets[i] = cursor;
            body.writeBytes(entryPayloads.get(i));
            cursor += entryPayloads.get(i).length;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLe32(out, 0x19);
        writeLe16(out, entryPayloads.size());
        writeLe16(out, id);
        for (int offset : offsets) {
            writeLe32(out, offset);
        }
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    /** An entry stored verbatim. */
    private static byte[] uncompressed(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLe32(out, data.length);
        out.writeBytes(data);
        return out.toByteArray();
    }

    /** An entry with the compressed flag and a caller-supplied LZSS stream. */
    private static byte[] compressed(int uncompressedLength, byte[] stream) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLe32(out, 0x20_000000 | uncompressedLength);
        out.writeBytes(stream);
        return out.toByteArray();
    }

    /** A back-reference token: {@code length} bytes starting at ring offset {@code offset}. */
    private static byte[] backReference(int length, int offset) {
        int token = ((length - 3) << 12) | offset;
        return new byte[] {(byte) (token & 0xFF), (byte) ((token >>> 8) & 0xFF)};
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

    private static WarArchive open(byte[] bytes) {
        return WarArchive.of(Path.of("test.war"), bytes, TEST_ID);
    }

    // ---------------------------------------------------------------- header

    @Test
    void readsHeaderFields() {
        WarArchive war = open(archive(TEST_ID, List.of(uncompressed("hello".getBytes(StandardCharsets.US_ASCII)))));
        assertEquals(TEST_ID, war.id());
        assertEquals(1, war.entryCount());
    }

    @Test
    void rejectsWrongMagic() {
        byte[] bytes = archive(TEST_ID, List.of(uncompressed(new byte[] {1})));
        bytes[0] = 0x18;
        ArchiveFormatException error = assertThrows(ArchiveFormatException.class, () -> open(bytes));
        assertTrue(error.getMessage().contains("wrong magic"));
    }

    @Test
    void rejectsWrongArchiveId() {
        byte[] bytes = archive(4000, List.of(uncompressed(new byte[] {1})));
        ArchiveFormatException error = assertThrows(ArchiveFormatException.class, () -> open(bytes));
        assertTrue(error.getMessage().contains("wrong archive id 4000"));
    }

    // --------------------------------------------------------------- entries

    @Test
    void readsAnUncompressedEntryVerbatim() {
        byte[] payload = "Warcraft II".getBytes(StandardCharsets.US_ASCII);
        WarArchive war = open(archive(TEST_ID, List.of(uncompressed(payload))));
        assertFalse(war.isCompressed(0));
        assertEquals(payload.length, war.declaredLength(0));
        assertArrayEquals(payload, war.entry(0));
    }

    @Test
    void expandsLiteralsAndBackReferences() {
        // Three literals, then a reference to ring offset 0 of length 3, which
        // replays them: "ABC" -> "ABCABC".
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(0b0000_0111); // bits, least significant first: lit lit lit ref
        stream.writeBytes("ABC".getBytes(StandardCharsets.US_ASCII));
        stream.writeBytes(backReference(3, 0));

        WarArchive war = open(archive(TEST_ID, List.of(compressed(6, stream.toByteArray()))));
        assertTrue(war.isCompressed(0));
        assertEquals("ABCABC", new String(war.entry(0), StandardCharsets.US_ASCII));
    }

    @Test
    void backReferenceCanReadBytesItIsStillWriting() {
        // This is how the format encodes runs: one literal 'X', then a match of
        // length 5 starting at the same ring slot, reading each byte just after
        // it is written.
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(0b0000_0001); // lit, then ref
        stream.write('X');
        stream.writeBytes(backReference(5, 0));

        WarArchive war = open(archive(TEST_ID, List.of(compressed(6, stream.toByteArray()))));
        assertEquals("XXXXXX", new String(war.entry(0), StandardCharsets.US_ASCII));
    }

    @Test
    void stopsMidMatchWhenTheOutputIsFull() {
        // Same stream as above but the entry declares only 4 bytes, so the
        // 5-byte match is cut short rather than overrunning.
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(0b0000_0001);
        stream.write('X');
        stream.writeBytes(backReference(5, 0));

        WarArchive war = open(archive(TEST_ID, List.of(compressed(4, stream.toByteArray()))));
        assertEquals("XXXX", new String(war.entry(0), StandardCharsets.US_ASCII));
    }

    @Test
    void ringBufferStartsZeroed() {
        // A match against never-written ring slots yields zero bytes, which the
        // real archives rely on to encode leading transparent pixels.
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(0b0000_0000); // one reference
        stream.writeBytes(backReference(4, 100));

        WarArchive war = open(archive(TEST_ID, List.of(compressed(4, stream.toByteArray()))));
        assertArrayEquals(new byte[4], war.entry(0));
    }

    @Test
    void consumesEightFlagsPerControlByte() {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(0xFF); // eight literals
        stream.writeBytes("01234567".getBytes(StandardCharsets.US_ASCII));
        stream.write(0xFF); // eight more
        stream.writeBytes("89abcdef".getBytes(StandardCharsets.US_ASCII));

        WarArchive war = open(archive(TEST_ID, List.of(compressed(16, stream.toByteArray()))));
        assertEquals("0123456789abcdef", new String(war.entry(0), StandardCharsets.US_ASCII));
    }

    @Test
    void rejectsUnknownEntryFlags() {
        ByteArrayOutputStream entry = new ByteArrayOutputStream();
        writeLe32(entry, 0x11_000000 | 4);
        entry.writeBytes(new byte[] {1, 2, 3, 4});
        WarArchive war = open(archive(TEST_ID, List.of(entry.toByteArray())));
        ArchiveFormatException error = assertThrows(ArchiveFormatException.class, () -> war.entry(0));
        assertTrue(error.getMessage().contains("unknown flags 11"));
    }

    // -------------------------------------------------------- broken entries

    @Test
    void treatsFillerSlotsAsInvalidAndYieldsWartoolsPlaceholder() {
        // maindat.war carries slots whose offsets sit one byte apart while
        // declaring multi-megabyte lengths. They must not be read as entries.
        //
        // Layout: an 8-byte header plus a 12-byte offset table, one real
        // 8-byte entry at 20, then two filler offsets at 28 and 29, in a file
        // padded to exactly 33 bytes.
        //
        //   entry 0  offset 20, 8 bytes of room          -> valid
        //   entry 1  offset 28, 1 byte of room           -> invalid, no space
        //   entry 2  offset 29, within 4 bytes of the end -> invalid, wartool's own rule
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLe32(out, 0x19);
        writeLe16(out, 3);
        writeLe16(out, TEST_ID);
        writeLe32(out, 20);
        writeLe32(out, 28);
        writeLe32(out, 29);
        out.writeBytes(uncompressed("real".getBytes(StandardCharsets.US_ASCII)));
        out.writeBytes(new byte[5]);
        assertEquals(33, out.size());

        WarArchive war = open(out.toByteArray());
        assertTrue(war.isValid(0));
        assertEquals("real", new String(war.entry(0), StandardCharsets.US_ASCII));

        assertFalse(war.isValid(1));
        assertFalse(war.isValid(2));
        // wartool substitutes EmptyEntry = {1, 1, 1}: one uncompressed byte of value 1.
        assertArrayEquals(new byte[] {1}, war.entry(1));
        assertArrayEquals(new byte[] {1}, war.entry(2));
    }

    @Test
    void treatsAnOffsetPastTheEndAsInvalid() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLe32(out, 0x19);
        writeLe16(out, 1);
        writeLe16(out, TEST_ID);
        writeLe32(out, 9_999_999);
        out.writeBytes(uncompressed("x".getBytes(StandardCharsets.US_ASCII)));

        WarArchive war = open(out.toByteArray());
        assertFalse(war.isValid(0));
        assertArrayEquals(new byte[] {1}, war.entry(0));
    }

    @Test
    void rejectsAnOutOfRangeEntryIndex() {
        WarArchive war = open(archive(TEST_ID, List.of(uncompressed(new byte[] {1}))));
        assertThrows(IndexOutOfBoundsException.class, () -> war.entry(5));
    }
}
