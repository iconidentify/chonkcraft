package net.chonkbase.chonkcraft.data.source;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import systems.crigges.jmpq3.JMpqEditor;
import systems.crigges.jmpq3.MPQOpenOption;

/**
 * The named files inside Battle.net Edition's self-extracting MPQ.
 *
 * <p>The BNE CD looks like it has everything in its four {@code TOME} files,
 * but those are only the classic numbered archives. All unit voices and all
 * multiplayer maps are inside {@code INSTALL.EXE}; that executable is an MPQ,
 * and it contains a second MPQ called {@code War2Dat.mpq}. Reading only the
 * TOMEs therefore produces a game that draws correctly but has no unit
 * acknowledgements and no multiplayer entry on the main menu.
 *
 * <p>The file names are the Q rows from ChonkCraft's {@code wartool.h}, generated
 * into {@code battle-net-assets.tsv}. MPQs do not promise a complete file
 * list, so these known names are the archive's index.
 */
final class BattleNetAssets implements AutoCloseable {

    private static final String RESOURCE = "/chonkcraft/battle-net-assets.tsv";
    private static final String NESTED_ARCHIVE = "files\\War2Dat.mpq";
    private static final long MAX_NESTED_ARCHIVE_BYTES = 64L * 1024 * 1024;
    private static final long MAX_ASSET_BYTES = 256L * 1024 * 1024;

    private enum Container {
        OUTER,
        INNER
    }

    private record Entry(AssetSource.SupplementalAsset asset, Container container,
            String archivedPath) {}

    private final Path root;
    private final Path outerArchive;
    private final Path directInnerArchive;
    private final List<Entry> entries;
    private final List<AssetSource.SupplementalAsset> assets;
    private final Map<String, Entry> byPath;
    private List<AssetSource.SupplementalAsset> availableAssets;

    private JMpqEditor outer;
    private JMpqEditor inner;
    private Path extractedInnerFile;

    private BattleNetAssets(Path root, Path outerArchive, Path directInnerArchive) {
        this.root = root;
        this.outerArchive = outerArchive;
        this.directInnerArchive = directInnerArchive;
        LinkedHashMap<String, Entry> indexed = new LinkedHashMap<>();
        for (Entry entry : loadIndex()) {
            indexed.putIfAbsent(entry.asset().path(), entry);
        }
        this.byPath = Map.copyOf(indexed);
        this.entries = List.copyOf(indexed.values());
        this.assets = entries.stream().map(Entry::asset).toList();
    }

    /** Finds the BNE installer under an extracted CD tree, or returns null. */
    static BattleNetAssets tryAt(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return null;
        }
        try (var walk = Files.walk(root, 5)) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            Path outer = largestNamed(files, "INSTALL.EXE");
            if (outer == null) {
                outer = largestNamed(files, "INSTALL.MPQ");
            }
            Path inner = shallowestNamed(files, "War2Dat.mpq");
            return outer == null && inner == null
                    ? null
                    : new BattleNetAssets(root, outer, inner);
        } catch (IOException e) {
            return null;
        }
    }

    private static Path largestNamed(List<Path> files, String name) {
        return files.stream()
                .filter(path -> path.getFileName().toString().equalsIgnoreCase(name))
                .max(Comparator.comparingLong(BattleNetAssets::size)
                        .thenComparing(Path::toString))
                .orElse(null);
    }

    private static Path shallowestNamed(List<Path> files, String name) {
        return files.stream()
                .filter(path -> path.getFileName().toString().equalsIgnoreCase(name))
                .min(Comparator.comparingInt(Path::getNameCount)
                        .thenComparing(Path::toString))
                .orElse(null);
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1;
        }
    }

    synchronized List<AssetSource.SupplementalAsset> assets() {
        if (directInnerArchive == null) {
            return assets;
        }
        if (availableAssets == null) {
            availableAssets = entries.stream()
                    .filter(this::contains)
                    .map(Entry::asset)
                    .toList();
        }
        return availableAssets;
    }

    private boolean contains(Entry entry) {
        try {
            return editor(entry.container()).hasFile(entry.archivedPath());
        } catch (IOException e) {
            return false;
        }
    }

    synchronized byte[] read(AssetSource.SupplementalAsset asset) {
        Entry entry = asset == null ? null : byPath.get(asset.path());
        if (entry == null) {
            return null;
        }
        try {
            byte[] bytes = read(entry.container(), entry.archivedPath());
            if (bytes.length > MAX_ASSET_BYTES) {
                throw new IOException(entry.asset().path() + " is implausibly large at "
                        + bytes.length + " bytes");
            }
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + entry.archivedPath()
                    + " from " + root, e);
        }
    }

    /** Stable provenance for a named payload, independent of its staging directory. */
    synchronized String origin(AssetSource.SupplementalAsset asset) {
        Entry entry = asset == null ? null : byPath.get(asset.path());
        if (entry == null) {
            return "";
        }
        String container = switch (entry.container()) {
            case OUTER -> "INSTALL.EXE";
            case INNER -> "War2Dat.mpq";
        };
        return container + ":" + entry.archivedPath();
    }

    private byte[] read(Container container, String path) throws IOException {
        return editor(container).extractFileAsBytes(path);
    }

    private JMpqEditor editor(Container container) throws IOException {
        if (container == Container.OUTER) {
            if (outer == null) {
                if (outerArchive == null) {
                    throw new IOException("Battle.net outer archive is missing");
                }
                outer = new JMpqEditor(outerArchive,
                        MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0);
            }
            return outer;
        }
        if (inner == null) {
            Path innerPath = directInnerArchive;
            if (innerPath == null) {
                byte[] nested = editor(Container.OUTER).extractFileAsBytes(NESTED_ARCHIVE);
                if (nested.length == 0 || nested.length > MAX_NESTED_ARCHIVE_BYTES) {
                    throw new IOException("embedded War2Dat.mpq has an invalid size of "
                            + nested.length + " bytes");
                }
                extractedInnerFile = Files.createTempFile("chonkcraft-war2dat-", ".mpq");
                Files.write(extractedInnerFile, nested);
                innerPath = extractedInnerFile;
            }
            inner = new JMpqEditor(innerPath,
                    MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0);
        }
        return inner;
    }

    private static List<Entry> loadIndex() {
        List<Entry> found = new ArrayList<>();
        try (InputStream stream = BattleNetAssets.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split("\t", -1);
                if (fields.length != 5) {
                    throw new IllegalStateException("malformed " + RESOURCE + " row: " + line);
                }
                AssetSource.SupplementalAsset.Kind kind;
                try {
                    kind = AssetSource.SupplementalAsset.Kind.valueOf(
                            fields[0].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException(
                            "unknown Battle.net asset kind " + fields[0], e);
                }
                Container container = switch (fields[1]) {
                    case "outer" -> Container.OUTER;
                    case "inner" -> Container.INNER;
                    default -> throw new IllegalStateException(
                            "unknown Battle.net container " + fields[1]);
                };
                found.add(new Entry(
                        new AssetSource.SupplementalAsset(fields[2], kind),
                        container,
                        fields[4]));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    @Override
    public synchronized void close() {
        closeQuietly(inner);
        closeQuietly(outer);
        inner = null;
        outer = null;
        if (extractedInnerFile != null) {
            try {
                Files.deleteIfExists(extractedInnerFile);
            } catch (IOException ignored) {
                // A stale temporary archive is harmless and the OS can remove it later.
            }
            extractedInnerFile = null;
        }
    }

    private static void closeQuietly(JMpqEditor editor) {
        if (editor == null) {
            return;
        }
        try {
            editor.close();
        } catch (IOException ignored) {
            // Both archives are read-only; there is no buffered state to lose.
        }
    }
}
