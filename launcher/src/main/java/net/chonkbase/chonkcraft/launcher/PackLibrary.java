package net.chonkbase.chonkcraft.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.chonkbase.assetpack.AssetPack;
import net.chonkbase.assetpack.Json;
import net.chonkbase.assetpack.PackManifest;

/** The graphics packs a player can choose before starting the game. */
public final class PackLibrary {

    /** One usable choice in the launcher. */
    public record PackInfo(Path file, String id, String name, String source,
            boolean expansion, boolean battleNet, int assets,
            String sourceVersion, String sourceFormat, String sourceOriginalName,
            String sourceSha256, long sourceOriginalBytes, String builtAt, long storedBytes,
            int maps, int musicTracks) {

        public PackInfo(Path file, String id, String name, String source,
                boolean expansion, boolean battleNet, int assets) {
            this(file, id, name, source, expansion, battleNet, assets,
                    "", "", "", "", 0, "", 0, 0, 0);
        }

        public String edition() {
            if (battleNet) {
                return "Battle.net Edition";
            }
            return expansion ? "Tides of Darkness + Beyond the Dark Portal"
                    : "Tides of Darkness";
        }

        public String versionDetail() {
            if (sourceVersion == null || sourceVersion.isBlank()) {
                return edition();
            }
            return sourceVersion;
        }

        public String fingerprint() {
            return sourceSha256 == null || sourceSha256.isBlank()
                    ? ""
                    : sourceSha256.substring(0, Math.min(12, sourceSha256.length()));
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final Path directory;

    public PackLibrary(Path directory) {
        this.directory = directory;
    }

    /** Every readable pack in display-name order. */
    public List<PackInfo> scan() throws IOException {
        Files.createDirectories(directory);
        List<PackInfo> found = new ArrayList<>();
        try (var listing = Files.list(directory)) {
            for (Path file : listing.filter(Files::isRegularFile)
                    .filter(PackLibrary::isPackName).toList()) {
                PackInfo info = read(file);
                if (info != null) {
                    found.add(info);
                }
            }
        }
        found.sort(Comparator.comparing(PackInfo::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(info -> info.file().toString()));
        return List.copyOf(found);
    }

    /** Reads one pack's identity without loading its payloads. */
    public static PackInfo read(Path file) {
        try (AssetPack pack = AssetPack.open(file)) {
            PackManifest manifest = pack.manifest();
            PackManifest.Identity identity = manifest.identity();
            if (identity.id().startsWith("wc2-war2combat-") || !hasRetailAi(manifest)) {
                return null;
            }
            Map<String, Object> properties = identity.properties();
            int musicTracks = manifest.discs().stream()
                    .mapToInt(disc -> disc.tracks().size())
                    .sum();
            return new PackInfo(file.toAbsolutePath().normalize(), identity.id(),
                    identity.name(), identity.source(),
                    identity.flag("expansionRelease"),
                    identity.flag("battleNetEdition"),
                    manifest.assets().size(),
                    Json.string(properties, "sourceVersion", ""),
                    Json.string(properties, "sourceFormat", ""),
                    Json.string(properties, "sourceOriginalName", ""),
                    Json.string(properties, "sourceOriginalSha256", ""),
                    Json.longValue(properties, "sourceOriginalBytes", 0),
                    identity.builtAt(), Files.size(file),
                    manifest.maps().size(), musicTracks);
        } catch (RuntimeException e) {
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean hasRetailAi(PackManifest manifest) {
        PackManifest.Archive main = manifest.archive(1000);
        return main != null && main.isValid(277);
    }

    /** Copies an already-built pack into the managed library. */
    public Path add(Path source) throws IOException {
        PackInfo info = read(source);
        if (info == null) {
            throw new IOException(source + " is not a readable ChonkCraft graphics pack");
        }
        Files.createDirectories(directory);
        Path destination = unique(slug(info.id()) + ".chonkpack");
        Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
        return destination;
    }

    /** Copies a managed pack somewhere the player chose. */
    public void export(PackInfo pack, Path destination, boolean replace) throws IOException {
        Path source = managed(pack);
        Path target = destination.toAbsolutePath().normalize();
        if (source.equals(target)) {
            throw new IOException("the export destination is the managed graphics pack");
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (replace) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
        } else {
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    /** Removes one pack from the managed library. */
    public void delete(PackInfo pack) throws IOException {
        Files.delete(managed(pack));
    }

    /** A non-clashing destination named after the selected source. */
    public Path destinationFor(Path source) throws IOException {
        Files.createDirectories(directory);
        String name = source == null || source.getFileName() == null
                ? "warcraft-ii"
                : source.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        return unique(slug(name) + ".chonkpack");
    }

    private Path unique(String fileName) {
        Path candidate = directory.resolve(fileName);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        int dot = fileName.lastIndexOf('.');
        String base = dot < 0 ? fileName : fileName.substring(0, dot);
        String suffix = dot < 0 ? "" : fileName.substring(dot);
        for (int number = 2; ; number++) {
            candidate = directory.resolve(base + "-" + number + suffix);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }

    private Path managed(PackInfo pack) throws IOException {
        if (pack == null) {
            throw new IOException("no graphics pack is selected");
        }
        Path root = directory.toAbsolutePath().normalize();
        Path file = pack.file().toAbsolutePath().normalize();
        if (!root.equals(file.getParent()) || !Files.isRegularFile(file)) {
            throw new IOException("the selected graphics pack is not in the managed library");
        }
        return file;
    }

    private static boolean isPackName(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".chonkpack");
    }

    static String slug(String value) {
        String slug = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return slug.isBlank() ? "warcraft-ii" : slug;
    }
}
