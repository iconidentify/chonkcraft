package net.chonkbase.chonkcraft.data.archive;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * Reads the classic HFS data track used by Macintosh Warcraft II discs.
 *
 * <p>The Japanese retail disc in the launcher's compatibility corpus has no
 * ISO 9660 filesystem at all. Its first track is an Apple partition map and a
 * classic HFS volume; treating every 1990s CD as ISO loses the entire game.
 * This reader covers the read-only half the launcher needs: the partition map,
 * volume allocation blocks, the catalog B-tree, and contiguous data forks.
 *
 * <p>Resource forks are deliberately not extracted. Warcraft II's six archives
 * and maps are data forks, and those are what the pack builder reads. Finder
 * metadata and executable resources have no part in the pack.
 */
public final class HfsImage implements AutoCloseable {

    private static final int BLOCK = 512;
    private static final int MDB_OFFSET = 1024;
    private static final int HFS_SIGNATURE = 0x4244;
    private static final Charset MAC_ROMAN = Charset.forName("x-MacRoman");

    private final RandomAccessFile file;
    private final long volumeOffset;
    private final int allocationBlockSize;
    private final long allocationBlocksOffset;
    private final Map<Integer, Folder> folders;
    private final List<StoredFile> files;
    private final String volumeName;

    private HfsImage(RandomAccessFile file, long volumeOffset, int allocationBlockSize,
            long allocationBlocksOffset, Map<Integer, Folder> folders,
            List<StoredFile> files, String volumeName) {
        this.file = file;
        this.volumeOffset = volumeOffset;
        this.allocationBlockSize = allocationBlockSize;
        this.allocationBlocksOffset = allocationBlocksOffset;
        this.folders = folders;
        this.files = files;
        this.volumeName = volumeName;
    }

    /** Opens a cooked HFS image, or returns null when it is not one. */
    public static HfsImage open(Path path) {
        RandomAccessFile file = null;
        try {
            if (!Files.isRegularFile(path) || Files.size(path) < 4L * BLOCK) {
                return null;
            }
            file = new RandomAccessFile(path.toFile(), "r");
            long volumeOffset = hfsPartition(file);
            byte[] mdb = read(file, volumeOffset + MDB_OFFSET, 162);
            if (u16(mdb, 0) != HFS_SIGNATURE) {
                file.close();
                return null;
            }
            int allocationSize = checkedInt(u32(mdb, 20), "HFS allocation block");
            long allocationOffset = volumeOffset + (long) u16(mdb, 28) * BLOCK;
            int catalogSize = checkedInt(u32(mdb, 146), "HFS catalog");
            List<Extent> catalogExtents = extents(mdb, 150);
            byte[] catalog = readFork(file, allocationOffset, allocationSize,
                    catalogSize, catalogExtents);
            Catalog parsed = readCatalog(catalog);
            String name = pascal(mdb, 36, 27);
            return new HfsImage(file, volumeOffset, allocationSize, allocationOffset,
                    parsed.folders(), parsed.files(), name);
        } catch (IOException | RuntimeException e) {
            closeQuietly(file);
            return null;
        }
    }

    public String volumeName() {
        return volumeName;
    }

    /** Recreates every data fork beneath {@code destination}. */
    public void extractAll(Path destination) throws IOException {
        extractAll(destination, ignored -> { });
    }

    /** Recreates every data fork and reports the exact bytes written. */
    public void extractAll(Path destination, LongConsumer progress) throws IOException {
        Path root = destination.toAbsolutePath().normalize();
        Files.createDirectories(root);
        List<StoredFile> ordered = files.stream()
                .sorted(Comparator.comparing(file -> pathOf(file.parent(), file.name())))
                .toList();
        long written = 0;
        for (StoredFile stored : ordered) {
            String relative = pathOf(stored.parent(), stored.name());
            Path target = root.resolve(relative).normalize();
            if (!target.startsWith(root)) {
                throw new IOException("HFS entry escapes its destination: " + relative);
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            byte[] bytes = readFork(file, allocationBlocksOffset, allocationBlockSize,
                    stored.length(), stored.extents());
            Files.write(target, bytes);
            written += bytes.length;
            progress.accept(written);
        }
    }

    /** Total bytes that {@link #extractAll(Path)} will write. */
    public long dataBytes() {
        return files.stream().mapToLong(StoredFile::length).sum();
    }

    private String pathOf(int parent, String name) {
        List<String> parts = new ArrayList<>();
        parts.add(safeName(name));
        int folder = parent;
        for (int depth = 0; folder > 2 && depth < 64; depth++) {
            Folder found = folders.get(folder);
            if (found == null) {
                break;
            }
            parts.add(safeName(found.name()));
            folder = found.parent();
        }
        java.util.Collections.reverse(parts);
        return String.join("/", parts);
    }

    private static String safeName(String name) {
        return name.replace('/', '_').replace(':', '_').replace('\0', '_');
    }

    private static long hfsPartition(RandomAccessFile file) throws IOException {
        byte[] entry = read(file, BLOCK, BLOCK);
        if (u16(entry, 0) != 0x504D) {
            return 0;
        }
        int count = Math.min(256, checkedInt(u32(entry, 4), "partition map"));
        for (int index = 1; index <= count; index++) {
            entry = read(file, (long) index * BLOCK, BLOCK);
            if (u16(entry, 0) != 0x504D) {
                continue;
            }
            String type = cString(entry, 48, 32);
            if ("Apple_HFS".equalsIgnoreCase(type)) {
                return u32(entry, 8) * BLOCK;
            }
        }
        return 0;
    }

    private static Catalog readCatalog(byte[] tree) throws IOException {
        if (tree.length < 64) {
            throw new IOException("HFS catalog is too short");
        }
        int nodeSize = u16(tree, 32);
        if (nodeSize < 256 || nodeSize > 32_768 || tree.length < nodeSize) {
            throw new IOException("HFS catalog has an invalid node size");
        }
        int firstLeaf = checkedInt(u32(tree, 24), "catalog first leaf");
        Map<Integer, Folder> folders = new LinkedHashMap<>();
        folders.put(2, new Folder(1, ""));
        List<StoredFile> files = new ArrayList<>();
        int node = firstLeaf;
        int visited = 0;
        while (node > 0 && (long) node * nodeSize + nodeSize <= tree.length
                && visited++ <= tree.length / nodeSize) {
            int base = node * nodeSize;
            int kind = tree[base + 8];
            if (kind != -1) {
                throw new IOException("HFS catalog leaf chain reached a non-leaf node");
            }
            int records = u16(tree, base + 10);
            for (int index = 0; index < records; index++) {
                int record = base + u16(tree, base + nodeSize - (index + 1) * 2);
                readCatalogRecord(tree, record, folders, files);
            }
            node = checkedInt(u32(tree, base), "catalog leaf link");
        }
        if (visited == 0) {
            throw new IOException("HFS catalog has no leaf nodes");
        }
        return new Catalog(Map.copyOf(folders), List.copyOf(files));
    }

    private static void readCatalogRecord(byte[] tree, int record,
            Map<Integer, Folder> folders, List<StoredFile> files) throws IOException {
        if (record < 0 || record + 8 > tree.length) {
            throw new IOException("HFS catalog record is outside the tree");
        }
        int keyLength = tree[record] & 0xFF;
        if (keyLength < 6 || record + 1 + keyLength > tree.length) {
            return;
        }
        int parent = checkedInt(u32(tree, record + 2), "catalog parent");
        int nameLength = Math.min(tree[record + 6] & 0xFF, keyLength - 6);
        String name = new String(tree, record + 7, nameLength, MAC_ROMAN);
        int data = record + ((keyLength + 2) & ~1);
        if (data + 8 > tree.length) {
            return;
        }
        int type = tree[data] & 0xFF;
        if (type == 1) {
            int id = checkedInt(u32(tree, data + 6), "folder id");
            folders.put(id, new Folder(parent, name));
        } else if (type == 2 && data + 98 <= tree.length) {
            int length = checkedInt(u32(tree, data + 26), "file data fork");
            files.add(new StoredFile(parent, name, length, extents(tree, data + 74)));
        }
    }

    private static byte[] readFork(RandomAccessFile file, long allocationOffset,
            int allocationSize, int logicalSize, List<Extent> extents) throws IOException {
        byte[] out = new byte[logicalSize];
        int written = 0;
        for (Extent extent : extents) {
            long bytes = (long) extent.blocks() * allocationSize;
            int take = (int) Math.min(bytes, logicalSize - written);
            if (take <= 0) {
                break;
            }
            long offset = allocationOffset + (long) extent.start() * allocationSize;
            file.seek(offset);
            file.readFully(out, written, take);
            written += take;
        }
        if (written != logicalSize) {
            throw new IOException("HFS fork uses overflow extents; read "
                    + written + " of " + logicalSize + " bytes");
        }
        return out;
    }

    private static List<Extent> extents(byte[] bytes, int offset) {
        List<Extent> found = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            int start = u16(bytes, offset + index * 4);
            int blocks = u16(bytes, offset + index * 4 + 2);
            if (blocks > 0) {
                found.add(new Extent(start, blocks));
            }
        }
        return List.copyOf(found);
    }

    private static byte[] read(RandomAccessFile file, long offset, int length)
            throws IOException {
        byte[] out = new byte[length];
        file.seek(offset);
        file.readFully(out);
        return out;
    }

    private static int checkedInt(long value, String label) throws IOException {
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IOException(label + " is too large");
        }
        return (int) value;
    }

    private static int u16(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }

    private static long u32(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xFF) << 24)
                | ((long) (bytes[offset + 1] & 0xFF) << 16)
                | ((long) (bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFFL);
    }

    private static String pascal(byte[] bytes, int offset, int maximum) {
        int length = Math.min(bytes[offset] & 0xFF, maximum);
        return new String(bytes, offset + 1, length, MAC_ROMAN);
    }

    private static String cString(byte[] bytes, int offset, int length) {
        int end = offset;
        while (end < offset + length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, offset, end - offset,
                java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static void closeQuietly(RandomAccessFile file) {
        if (file == null) {
            return;
        }
        try {
            file.close();
        } catch (IOException e) {
            // Nothing useful to do with a failed close on a read handle.
        }
    }

    @Override
    public void close() throws IOException {
        file.close();
    }

    private record Extent(int start, int blocks) {}
    private record Folder(int parent, String name) {}
    private record StoredFile(int parent, String name, int length, List<Extent> extents) {}
    private record Catalog(Map<Integer, Folder> folders, List<StoredFile> files) {}
}
