package net.chonkbase.chonkcraft.data.archive;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.BufferedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * Reads files out of a Warcraft II CD image.
 *
 * <p>The DOS release installs only part of itself. The videos, the full sound
 * archive and the CD audio stay on the disc, which is why a hard-disk install
 * has no {@code muddat.cud} and why this implementation could not show a cutscene until
 * it could read one of these.
 *
 * <p>Two things stand between the file and the filesystem it is in.
 *
 * <p>A CloneCD {@code .img} or {@code .bin} is raw sectors: 2352 bytes per
 * sector, with a 12-byte sync pattern, a 4-byte header, the 2048 bytes that are
 * actually the file, and 288 bytes of error correction. An {@code .iso} or
 * Roxio {@code .toast} is usually cooked 2048-byte sectors instead. The volume
 * descriptor is probed in both layouts rather than guessed from the suffix,
 * because both suffixes are routinely changed when old discs are archived.
 *
 * <p>The filesystem is ISO 9660, whose directory records are variable length
 * and may not straddle a sector boundary. A record that would cross one is
 * preceded by zero padding to the next, so walking a directory means watching
 * for a zero length and skipping ahead rather than treating it as the end.
 */
public final class CdImage implements AutoCloseable {

    /** Bytes per raw sector in a CloneCD image. */
    private static final int RAW_SECTOR = 2352;

    /** Bytes per cooked sector in an ISO or Toast image. */
    private static final int COOKED_SECTOR = 2048;

    /** Where the file data starts within one, past sync and header. */
    private static final int USER_DATA_OFFSET = 16;

    /** How much of a sector is the file. */
    private static final int USER_DATA = 2048;

    /** The sector holding the primary volume descriptor. */
    private static final int VOLUME_DESCRIPTOR_SECTOR = 16;

    private final RandomAccessFile file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final String volumeName;
    private final int sectorSize;
    private final int userDataOffset;

    /**
     * One file on the disc.
     *
     * @param path   its full path, upper case, slash separated, without the
     *               {@code ;1} version suffix ISO 9660 appends
     * @param sector where it starts
     * @param length how long it is
     */
    public record Entry(String path, int sector, int length) {}

    private CdImage(RandomAccessFile file, String volumeName, int sectorSize,
            int userDataOffset) {
        this.file = file;
        this.volumeName = volumeName;
        this.sectorSize = sectorSize;
        this.userDataOffset = userDataOffset;
    }

    /**
     * Opens an image, or returns null if it is not one this can read.
     *
     * <p>Null rather than an exception because the caller is usually asking
     * "is this a CD image" of a file it found by looking, and an answer of no
     * is ordinary.
     */
    public static CdImage open(Path path) {
        RandomAccessFile file = null;
        try {
            if (!Files.isRegularFile(path) || Files.size(path) < COOKED_SECTOR * 20L) {
                return null;
            }
            file = new RandomAccessFile(path.toFile(), "r");
            SectorLayout layout = findLayout(file);
            if (layout == null) {
                file.close();
                return null;
            }
            byte[] descriptor = readSector(file, VOLUME_DESCRIPTOR_SECTOR,
                    layout.sectorSize(), layout.userDataOffset());
            if (descriptor == null || descriptor[0] != 1
                    || !"CD001".equals(new String(descriptor, 1, 5, StandardCharsets.ISO_8859_1))) {
                file.close();
                return null;
            }
            String name = new String(descriptor, 40, 32, StandardCharsets.ISO_8859_1).trim();
            CdImage image = new CdImage(file, name,
                    layout.sectorSize(), layout.userDataOffset());
            // The root directory record sits inside the descriptor.
            int rootSector = readInt(descriptor, 156 + 2);
            int rootLength = readInt(descriptor, 156 + 10);
            image.walk(rootSector, rootLength, "");
            return image;
        } catch (IOException e) {
            closeQuietly(file);
            return null;
        } catch (RuntimeException e) {
            // A file that is the right size and starts plausibly but is not
            // actually a disc image. Not readable is not a crash.
            closeQuietly(file);
            return null;
        }
    }

    /** The two disc-image layouts Warcraft II is commonly distributed in. */
    private record SectorLayout(int sectorSize, int userDataOffset) {}

    private static SectorLayout findLayout(RandomAccessFile file) throws IOException {
        for (SectorLayout candidate : List.of(
                new SectorLayout(RAW_SECTOR, USER_DATA_OFFSET),
                new SectorLayout(COOKED_SECTOR, 0))) {
            byte[] descriptor = readSector(file, VOLUME_DESCRIPTOR_SECTOR,
                    candidate.sectorSize(), candidate.userDataOffset());
            if (descriptor != null && descriptor[0] == 1
                    && "CD001".equals(new String(
                            descriptor, 1, 5, StandardCharsets.ISO_8859_1))) {
                return candidate;
            }
        }
        return null;
    }

    private static void closeQuietly(RandomAccessFile file) {
        if (file != null) {
            try {
                file.close();
            } catch (IOException ignored) {
                // Nothing useful to do about a failed close on a read handle.
            }
        }
    }

    /** The volume's own name, for reporting which disc this is. */
    public String volumeName() {
        return volumeName;
    }

    /** Every file on the disc, by upper-case path. */
    public Map<String, Entry> entries() {
        return entries;
    }

    /**
     * Finds a file by name, ignoring which directory it is in.
     *
     * <p>The callers know they want {@code MUDDAT.CUD} and not where Blizzard
     * chose to put it, which differs between the two discs.
     */
    public Entry findByName(String name) {
        String wanted = name.toUpperCase(Locale.ROOT);
        for (Entry entry : entries.values()) {
            String last = entry.path().substring(entry.path().lastIndexOf('/') + 1);
            if (last.equals(wanted)) {
                return entry;
            }
        }
        return null;
    }

    /** Reads a file out of the image. */
    public byte[] read(Entry entry) throws IOException {
        byte[] out = new byte[entry.length()];
        int sectors = (entry.length() + USER_DATA - 1) / USER_DATA;
        int written = 0;
        for (int i = 0; i < sectors; i++) {
            byte[] sector = readSector(entry.sector() + i);
            if (sector == null) {
                break;
            }
            int take = Math.min(USER_DATA, out.length - written);
            System.arraycopy(sector, 0, out, written, take);
            written += take;
        }
        return out;
    }

    /** Reads a file and writes it out, for callers that want it on disk. */
    public void extract(Entry entry, Path destination) throws IOException {
        Path parent = destination.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(destination, read(entry));
    }

    /**
     * Recreates the data track beneath {@code destination}.
     *
     * <p>The launcher needs the whole installation, not one archive by name:
     * maps and Battle.net's tome files carry meaning too. Paths come from ISO
     * 9660 directory records, but are still checked after normalization so a
     * malformed image cannot write outside the import directory.
     */
    public void extractAll(Path destination) throws IOException {
        extractAll(destination, ignored -> { });
    }

    /**
     * Recreates the data track while reporting the exact number of file bytes
     * written. The callback is intentionally byte-based: archive entries vary
     * from a few bytes to tens of megabytes, so an entry counter would make a
     * progress bar look almost finished while the largest file was still
     * being copied.
     */
    public void extractAll(Path destination, LongConsumer progress) throws IOException {
        Path root = destination.toAbsolutePath().normalize();
        Files.createDirectories(root);
        long written = 0;
        for (Entry entry : entries.values()) {
            Path target = root.resolve(entry.path()).normalize();
            if (!target.startsWith(root)) {
                throw new IOException("disc entry escapes its destination: " + entry.path());
            }
            extract(entry, target);
            written += entry.length();
            progress.accept(written);
        }
    }

    /**
     * Removes the sync, address and error-correction bytes from a raw data
     * track, producing the 2048-byte-sector image macOS and general archive
     * tools understand.
     *
     * <p>Some Macintosh Warcraft II discs have an HFS data track and no ISO
     * 9660 volume descriptor. They still use raw BIN sectors, so this reader
     * cannot walk their filesystem itself; cooking the track loses no file
     * data and lets the platform's HFS reader take over. The caller supplies
     * the data-track length from the CUE so red-book audio is not copied into
     * the filesystem image.
     *
     * @return whether {@code raw} had recognizable 2352-byte sectors
     */
    public static boolean cookDataTrack(Path raw, Path cooked, int sectorLimit)
            throws IOException {
        return cookDataTrack(raw, cooked, sectorLimit, ignored -> { });
    }

    /** Cooks a raw track while reporting exact source bytes consumed. */
    public static boolean cookDataTrack(Path raw, Path cooked, int sectorLimit,
            LongConsumer progress) throws IOException {
        if (!Files.isRegularFile(raw) || Files.size(raw) < RAW_SECTOR) {
            return false;
        }
        long available = Files.size(raw) / RAW_SECTOR;
        int sectors = (int) Math.min(available,
                sectorLimit > 0 ? sectorLimit : available);
        byte[] sector = new byte[RAW_SECTOR];
        try (RandomAccessFile input = new RandomAccessFile(raw.toFile(), "r");
                var output = new BufferedOutputStream(Files.newOutputStream(cooked))) {
            for (int at = 0; at < sectors; at++) {
                input.readFully(sector);
                if (!hasSync(sector)) {
                    return false;
                }
                int mode = sector[15] & 0xFF;
                int offset = mode == 2 ? 24 : USER_DATA_OFFSET;
                output.write(sector, offset, USER_DATA);
                if ((at & 255) == 255 || at + 1 == sectors) {
                    progress.accept((long) (at + 1) * RAW_SECTOR);
                }
            }
        }
        return true;
    }

    private static boolean hasSync(byte[] sector) {
        if (sector.length < RAW_SECTOR || sector[0] != 0 || sector[11] != 0) {
            return false;
        }
        for (int i = 1; i < 11; i++) {
            if ((sector[i] & 0xFF) != 0xFF) {
                return false;
            }
        }
        return true;
    }

    /** Walks a directory, recording its files and descending into its folders. */
    private void walk(int sector, int length, String prefix) throws IOException {
        // Depth is bounded by the disc's own layout, and these discs are two
        // levels deep, but a malformed image should not recurse forever.
        if (prefix.chars().filter(c -> c == '/').count() > 8) {
            return;
        }
        int sectors = (length + USER_DATA - 1) / USER_DATA;
        byte[] data = new byte[sectors * USER_DATA];
        for (int i = 0; i < sectors; i++) {
            byte[] read = readSector(sector + i);
            if (read == null) {
                return;
            }
            System.arraycopy(read, 0, data, i * USER_DATA, USER_DATA);
        }

        int position = 0;
        while (position < data.length) {
            int recordLength = data[position] & 0xFF;
            if (recordLength == 0) {
                // Padding to the next sector: a record may not straddle one.
                // Reading this as the end of the directory loses every file
                // after the first sector's worth.
                position = (position / USER_DATA + 1) * USER_DATA;
                continue;
            }
            if (position + recordLength > data.length) {
                return;
            }
            int childSector = readInt(data, position + 2);
            int childLength = readInt(data, position + 10);
            int flags = data[position + 25] & 0xFF;
            int nameLength = data[position + 32] & 0xFF;
            String name = new String(data, position + 33, nameLength, StandardCharsets.ISO_8859_1);
            position += recordLength;

            // "\0" and "\1" are this directory and its parent.
            if (nameLength == 1 && (name.charAt(0) == 0 || name.charAt(0) == 1)) {
                continue;
            }
            // ISO 9660 appends a file version: FOO.BAR;1
            int semicolon = name.indexOf(';');
            if (semicolon >= 0) {
                name = name.substring(0, semicolon);
            }
            String path = prefix + name.toUpperCase(Locale.ROOT);
            if ((flags & 0x02) != 0) {
                walk(childSector, childLength, path + "/");
            } else {
                entries.put(path, new Entry(path, childSector, childLength));
            }
        }
    }

    /** One sector's file data, or null past the end of the image. */
    private byte[] readSector(int sector) throws IOException {
        return readSector(file, sector, sectorSize, userDataOffset);
    }

    private static byte[] readSector(RandomAccessFile file, int sector,
            int sectorSize, int userDataOffset) throws IOException {
        long offset = (long) sector * sectorSize + userDataOffset;
        if (offset + USER_DATA > file.length()) {
            return null;
        }
        file.seek(offset);
        byte[] out = new byte[USER_DATA];
        file.readFully(out);
        return out;
    }

    private static int readInt(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    /** Every image found under a directory, deepest last. */
    public static List<Path> imagesUnder(Path root) {
        List<Path> found = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".img") || name.endsWith(".iso")
                                || name.endsWith(".bin") || name.endsWith(".toast");
                    })
                    .sorted()
                    .forEach(found::add);
        } catch (IOException e) {
            return found;
        }
        return found;
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}
