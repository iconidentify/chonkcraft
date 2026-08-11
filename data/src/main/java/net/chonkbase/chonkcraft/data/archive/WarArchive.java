package net.chonkbase.chonkcraft.data.archive;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reader for the Warcraft II {@code .WAR} / {@code .SUD} archive format.
 *
 * <p>Implements {@code OpenArchive} and {@code ExtractEntry}, including its tolerance of malformed entries, because
 * the extracted output has to match the C++ tool byte for byte.
 *
 * <p>Layout, all little-endian:
 *
 * <pre>
 *   u32  magic          always 0x00000019
 *   u16  entryCount
 *   u16  id             identifies which archive this is
 *   u32  offset[entryCount]
 *   ...  entry data
 * </pre>
 *
 * <p>Each entry begins with a u32 whose top byte is a flag and whose low three
 * bytes are the uncompressed length. Flag {@code 0x00} means the bytes follow
 * verbatim; flag {@code 0x20} means they are LZSS-compressed.
 */
public final class WarArchive implements net.chonkbase.chonkcraft.data.source.EntryArchive {

    /** {@code maindat.war}: sprites, tilesets, fonts, cursors, the campaign PUDs. */
    public static final int ID_MAINDAT = 1000;

    /** {@code snddat.war}: digitised speech and effects on CD builds. */
    public static final int ID_SNDDAT = 2000;

    /** {@code rezdat.war}: menu and interface art. */
    public static final int ID_REZDAT = 3000;

    /** {@code strdat.war}: all game text. */
    public static final int ID_STRDAT = 4000;

    /** {@code sfxdat.sud}: sound effects on floppy and DOS builds. */
    public static final int ID_SFXDAT = 5000;

    /** {@code muddat.cud}: the intro and outro movies. */
    public static final int ID_MUDDAT = 6000;

    private static final int MAGIC = 0x19;
    private static final int HEADER_BYTES = 8;
    private static final int FLAG_UNCOMPRESSED = 0x00;
    private static final int FLAG_COMPRESSED = 0x20;

    /**
     * What the archive reader substitutes for an entry whose offset or length
     * is out of range: {@code static unsigned int EmptyEntry[] = {1, 1, 1}},
     * which decodes as one uncompressed byte of value 1.
     */
    private static final byte[] EMPTY_ENTRY = {0x01};

    private final Path source;
    private final byte[] bytes;
    private final int id;
    private final int[] offsets;
    private final boolean[] valid;

    private WarArchive(Path source, byte[] bytes, int id, int[] offsets, boolean[] valid) {
        this.source = source;
        this.bytes = bytes;
        this.id = id;
        this.offsets = offsets;
        this.valid = valid;
    }

    /**
     * Opens an archive and verifies its magic and id.
     *
     * @param file       the archive path
     * @param expectedId one of the {@code ID_*} constants
     */
    public static WarArchive open(Path file, int expectedId) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read archive " + file, e);
        }
        return of(file, bytes, expectedId);
    }

    /** Opens an archive already held in memory. Exposed for tests. */
    public static WarArchive of(Path source, byte[] bytes, int expectedId) {
        if (bytes.length < HEADER_BYTES) {
            throw new ArchiveFormatException(source + ": too short to be an archive");
        }
        int magic = readLe32(bytes, 0);
        if (magic != MAGIC) {
            throw new ArchiveFormatException(
                    String.format("%s: wrong magic %08x, expected %08x", source, magic, MAGIC));
        }
        int entryCount = readLe16(bytes, 4);
        int id = readLe16(bytes, 6);
        if (id != expectedId) {
            throw new ArchiveFormatException(
                    String.format("%s: wrong archive id %d, expected %d", source, id, expectedId));
        }
        if (HEADER_BYTES + (long) entryCount * 4 > bytes.length) {
            throw new ArchiveFormatException(source + ": offset table runs past end of file");
        }

        int[] offsets = new int[entryCount];
        for (int i = 0; i < entryCount; i++) {
            offsets[i] = readLe32(bytes, HEADER_BYTES + i * 4);
        }

        // How many bytes each entry actually has available: up to the next
        // offset in the table, or to end of file for the last one. Offsets are
        // monotonic in every shipped archive, but nothing guarantees it, so
        // take the next larger value rather than the next index.
        int[] sorted = offsets.clone();
        java.util.Arrays.sort(sorted);

        boolean[] valid = new boolean[entryCount];
        for (int i = 0; i < entryCount; i++) {
            int offset = offsets[i];
            // wartool treats an offset within four bytes of the end as junk.
            if (offset < 0 || offset >= bytes.length - 4) {
                valid[i] = false;
                continue;
            }
            int header = readLe32(bytes, offset);
            int flags = (header >>> 24) & 0xFF;
            int length = header & 0x00FFFFFF;
            if (flags == FLAG_UNCOMPRESSED && offset + 4L + length > bytes.length) {
                // wartool's second check.
                valid[i] = false;
                continue;
            }

            // Beyond what wartool checks. maindat.war carries filler slots
            // (entries 28 to 32 in the DOS build) whose offsets sit one byte
            // apart while declaring multi-megabyte lengths. wartool never
            // notices because it extracts by index from a fixed table and
            // those indices are not in it; anything that sweeps the archive
            // does notice, and would otherwise read a garbage flag byte. An
            // entry needs at least a 4-byte header plus one payload byte.
            int available = spanTo(sorted, offset, bytes.length) - offset;
            valid[i] = available >= 5;
        }
        return new WarArchive(source, bytes, id, offsets, valid);
    }

    /** The next offset strictly greater than {@code offset}, or {@code end}. */
    private static int spanTo(int[] sortedOffsets, int offset, int end) {
        int index = java.util.Arrays.binarySearch(sortedOffsets, offset);
        int scan = index >= 0 ? index : -index - 1;
        while (scan < sortedOffsets.length && sortedOffsets[scan] <= offset) {
            scan++;
        }
        return scan < sortedOffsets.length ? sortedOffsets[scan] : end;
    }

    /** The archive's declared id. */
    @Override
    public int id() {
        return id;
    }

    /** Number of entries in the offset table. */
    @Override
    public int entryCount() {
        return offsets.length;
    }

    /** Whether entry {@code index} has a usable offset and length. */
    @Override
    public boolean isValid(int index) {
        return valid[index];
    }

    /** The uncompressed length declared by entry {@code index}. */
    public int declaredLength(int index) {
        if (!valid[index]) {
            return EMPTY_ENTRY.length;
        }
        return readLe32(bytes, offsets[index]) & 0x00FFFFFF;
    }

    /** Whether entry {@code index} is stored compressed. */
    public boolean isCompressed(int index) {
        if (!valid[index]) {
            return false;
        }
        return ((readLe32(bytes, offsets[index]) >>> 24) & 0xFF) == FLAG_COMPRESSED;
    }

    /**
     * Decompresses entry {@code index}.
     *
     * <p>An entry that failed validation yields wartool's one-byte placeholder
     * rather than throwing, so a partially corrupt archive still extracts.
     */
    @Override
    public byte[] entry(int index) {
        if (index < 0 || index >= offsets.length) {
            throw new IndexOutOfBoundsException("entry " + index + " of " + offsets.length + " in " + source);
        }
        if (!valid[index]) {
            return EMPTY_ENTRY.clone();
        }

        int cursor = offsets[index];
        int header = readLe32(bytes, cursor);
        cursor += 4;
        int flags = (header >>> 24) & 0xFF;
        int length = header & 0x00FFFFFF;

        return switch (flags) {
            case FLAG_UNCOMPRESSED -> java.util.Arrays.copyOfRange(bytes, cursor, cursor + length);
            case FLAG_COMPRESSED -> decompress(cursor, length, index);
            default -> throw new ArchiveFormatException(
                    String.format("%s: entry %d has unknown flags %02x", source, index, flags));
        };
    }

    /**
     * The LZSS variant Blizzard used, ported from {@code ExtractEntry}.
     *
     * <p>A 4096-byte ring buffer starts zeroed. Each control byte carries eight
     * flags, least significant first: a set bit is one literal byte, a clear
     * bit is a 16-bit back-reference whose top four bits are {@code length - 3}
     * and whose low twelve bits are the ring offset.
     *
     * <p>Both the write cursor and the ring offset wrap independently at 4096,
     * and a match may read bytes it has just written, which is how runs are
     * encoded. Decoding stops the moment the output is full, even mid-match.
     */
    private byte[] decompress(int start, int length, int index) {
        byte[] out = new byte[length];
        byte[] ring = new byte[0x1000];
        int cursor = start;
        int written = 0;
        int ringWrite = 0;

        while (written < length) {
            if (cursor >= bytes.length) {
                throw new ArchiveFormatException(
                        String.format("%s: entry %d ran off the end of the archive", source, index));
            }
            int control = bytes[cursor++] & 0xFF;

            for (int bit = 0; bit < 8 && written < length; bit++, control >>= 1) {
                if ((control & 1) != 0) {
                    int value = bytes[cursor++] & 0xFF;
                    out[written++] = (byte) value;
                    ring[ringWrite++ & 0xFFF] = (byte) value;
                } else {
                    int reference = readLe16(bytes, cursor);
                    cursor += 2;
                    int runLength = (reference >>> 12) + 3;
                    int ringRead = reference & 0xFFF;
                    while (runLength-- > 0) {
                        byte value = ring[ringRead++ & 0xFFF];
                        ring[ringWrite++ & 0xFFF] = value;
                        out[written++] = value;
                        if (written == length) {
                            break;
                        }
                    }
                }
            }
        }
        return out;
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
