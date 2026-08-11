package net.chonkbase.chonkcraft.data.source;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import net.chonkbase.chonkcraft.data.archive.CdImage;

/**
 * Locates a Warcraft II installation and its archives.
 *
 * <p>The DOS release ships its archives in a {@code DATA} subdirectory with
 * upper-case names; other releases use lower case, and the Mac release renames
 * them entirely. Resolution is case-insensitive and checks both layouts, the
 * same set probes.
 *
 * <p><b>Package-private on purpose, and it used to be public.</b> This class is
 * the only thing in the implementation that knows a 1995 installation is a directory: it
 * holds a {@link Path}, it knows the DOS layout, it knows the Mac file names,
 * and it will pull an archive out of a CD image. Everything above it reads an
 * {@link AssetSource}, which cannot answer a question about a directory because
 * a pack has none. While this was public the engine could -- and did -- reach
 * back through it for a file name or an archive id, and every one of those
 * reaches quietly reintroduced the assumption that the game is being run out of
 * somebody's Warcraft folder.
 *
 * <p>{@link InstallSource} is now the only class that can construct or name
 * one. If a caller outside this package needs something from here, the answer
 * belongs on {@code AssetSource} or on {@code InstallSource}, not on a widened
 * modifier here. {@code NoInstallDirectoryTest} in {@code engine} and
 * {@code desktop} fails the build if either module names this type again.
 */
final class Warcraft2Install {

    /** One archive we know how to read. */
    enum Archive {
        MAINDAT("maindat.war", "War Data", 1000),
        SNDDAT("snddat.war", "War Music", 2000),
        REZDAT("rezdat.war", "War Resources", 3000),
        STRDAT("strdat.war", "War Strings", 4000),
        SFXDAT("sfxdat.sud", "War Sounds", 5000),
        MUDDAT("muddat.cud", "War Movies", 6000);

        private final String pcName;
        private final String macName;
        private final int id;

        Archive(String pcName, String macName, int id) {
            this.pcName = pcName;
            this.macName = macName;
            this.id = id;
        }

        /** The archive id its header must declare. */
        int id() {
            return id;
        }

        /** The file name in a PC installation. */
        String pcName() {
            return pcName;
        }

        /** The file name in a Mac installation. */
        String macName() {
            return macName;
        }
    }

    private final Path root;

    private Warcraft2Install(Path root) {
        this.root = root;
    }

    /**
     * Opens an installation rooted at {@code root}, which may be either the
     * game directory or its {@code DATA} subdirectory.
     *
     * @throws IllegalArgumentException if {@code maindat.war} is not found
     */
    static Warcraft2Install at(Path root) {
        Warcraft2Install install = new Warcraft2Install(root);
        if (install.find(Archive.MAINDAT) == null) {
            throw new IllegalArgumentException("no Warcraft II data found under " + root);
        }
        return install;
    }

    /** Opens an installation, or returns {@code null} if {@code root} does not hold one. */
    static Warcraft2Install tryAt(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return null;
        }
        Warcraft2Install install = new Warcraft2Install(root);
        return install.find(Archive.MAINDAT) == null ? null : install;
    }

    /** The installation root as given. */
    Path root() {
        return root;
    }

    /**
     * Resolves one archive, or returns {@code null} when this release does not
     * ship it. The DOS release has no {@code snddat.war}, for instance; its
     * sounds live in {@code sfxdat.sud} and its music on the CD as audio
     * tracks.
     */
    Path find(Archive archive) {
        for (Path directory : List.of(root, root.resolve("DATA"), root.resolve("data"))) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            Path match = findIgnoringCase(directory, archive.pcName());
            if (match != null) {
                return match;
            }
            match = findIgnoringCase(directory, archive.macName());
            if (match != null) {
                return match;
            }
        }
        // Battle.net Edition stores its numbered archives as TOME.1 through
        // TOME.4. The archive id in the eight-byte header is authoritative, so
        // use that instead of assigning meaning to the file number.
        Path tome = findTomeById(archive.id());
        if (tome != null) {
            return tome;
        }
        // Not installed. The DOS release leaves the videos and the full sound
        // archive on the disc, so an installation that has never seen a CD
        // genuinely does not have muddat.cud on it. If a disc image is lying
        // about, the file is in there.
        return fromDisc(archive);
    }

    private Path findTomeById(int archiveId) {
        for (Path directory : List.of(
                root.resolve("support/tomes"),
                root.resolve("Support/TOMES"),
                root.resolve("SUPPORT/TOMES"),
                root.resolve("MapEditor/support/tomes"),
                root.resolve("MapEditor/Support/TOMES"),
                root.resolve("MAPEDITOR/SUPPORT/TOMES"))) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            for (int number = 1; number <= 4; number++) {
                Path candidate = findIgnoringCase(directory, "TOME." + number);
                if (candidate != null && archiveId(candidate) == archiveId) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** Reads the id without pulling a forty-seven-megabyte tome into memory. */
    private static int archiveId(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] header = in.readNBytes(8);
            if (header.length != 8
                    || (header[0] & 0xFF) != 0x19
                    || header[1] != 0 || header[2] != 0 || header[3] != 0) {
                return -1;
            }
            return (header[6] & 0xFF) | ((header[7] & 0xFF) << 8);
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Pulls an archive out of a CD image and caches it beside the install.
     *
     * <p>Cached rather than read through, because the callers memory-map these
     * and expect a real file, and because reading a fifty-megabyte archive out
     * of raw sectors once is better than doing it on every run.
     */
    private Path fromDisc(Archive archive) {
        Path cache = root.resolve("chonkcraft-cache");
        Path cached = cache.resolve(archive.pcName().toUpperCase(Locale.ROOT));
        if (Files.isRegularFile(cached)) {
            return cached;
        }
        for (Path image : CdImage.imagesUnder(root)) {
            try (CdImage disc = CdImage.open(image)) {
                if (disc == null) {
                    continue;
                }
                CdImage.Entry entry = disc.findByName(archive.pcName());
                if (entry == null) {
                    continue;
                }
                disc.extract(entry, cached);
                return cached;
            } catch (IOException e) {
                // An unreadable image is one candidate among several.
                continue;
            }
        }
        return null;
    }

    private static Path findIgnoringCase(Path directory, String name) {
        Path exact = directory.resolve(name);
        if (Files.isRegularFile(exact)) {
            return exact;
        }
        Path upper = directory.resolve(name.toUpperCase(Locale.ROOT));
        if (Files.isRegularFile(upper)) {
            return upper;
        }
        try (var listing = Files.list(directory)) {
            return listing
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase(name))
                    .findFirst()
                    .orElse(null);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * Finds an installation from configuration.
     *
     * <p>Checks the {@code wc2.install.dir} system property, then the
     * {@code WC2_INSTALL_DIR} environment variable. Returns {@code null} when
     * neither is set or neither points at a real installation.
     */
    static Warcraft2Install fromEnvironment() {
        String property = System.getProperty("wc2.install.dir");
        if (property != null && !property.isBlank()) {
            return tryAt(Paths.get(property));
        }
        String environment = System.getenv("WC2_INSTALL_DIR");
        if (environment != null && !environment.isBlank()) {
            return tryAt(Paths.get(environment));
        }
        return null;
    }
}
