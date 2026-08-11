package net.chonkbase.chonkcraft.launcher;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import net.chonkbase.chonkcraft.data.source.AssetSource;

/**
 * Installs replaceable game jars while the launcher itself stays running.
 *
 * <p>A release catalog names one self-contained game JAR and its mandatory
 * SHA-256. Player-owned retail media remains in the selected chonkpack.
 */
public final class GameReleaseManager {

    /** A short status message for game-download operations. */
    @FunctionalInterface
    public interface Progress {
        void say(String message);
    }

    public static final String DEFAULT_CATALOG =
            "https://updates.chonkbase.net/latest.properties";

    /** One downloadable release. */
    public record Release(String version, URI game, String gameSha256,
            long gameBytes, String notes, String revision, String published,
            URI releaseNotes, String releaseNotesSha256, long releaseNotesBytes) {

        public Release(String version, URI game, String gameSha256,
                long gameBytes, String notes, String revision) {
            this(version, game, gameSha256, gameBytes, notes, revision,
                    "", null, "", -1L);
        }

        public Release(String version, URI game, String gameSha256, String notes) {
            this(version, game, gameSha256, -1L, notes, "");
        }
    }

    /** One complete local version. */
    public record Installed(String version, Path gameJar) {

        @Override
        public String toString() {
            return version;
        }
    }

    private static final long MAX_GAME_BYTES = 256L * 1024 * 1024;
    private static final long MAX_CATALOG_BYTES = 64L * 1024;
    private static final String TRUSTED_KEYS = "/chonkcraft-update-keys.properties";

    private final LauncherHome home;
    private final Map<String, PublicKey> trustedKeys;

    public GameReleaseManager(LauncherHome home) {
        this(home, loadTrustedKeys());
    }

    GameReleaseManager(LauncherHome home, Map<String, PublicKey> trustedKeys) {
        this.home = home;
        this.trustedKeys = Map.copyOf(trustedKeys);
    }

    /** Reads the configured release catalog. */
    public Release latest() throws IOException {
        String configured = System.getProperty("chonkcraft.update.catalog");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("CHONKCRAFT_UPDATE_CATALOG");
        }
        URI catalog = URI.create(configured == null || configured.isBlank()
                ? DEFAULT_CATALOG : configured);
        byte[] catalogBytes = read(catalog, MAX_CATALOG_BYTES, "update catalog");
        Properties values = verifyCatalog(catalogBytes);
        String format = values.getProperty("format", "");
        if (!"chonkcraft-release-3".equals(format)) {
            throw new IOException("the update catalog has an unsupported format");
        }
        String version = versionId(required(values, "version"));
        URI game = catalog.resolve(required(values, "game.url"));
        String gameHash = hash(required(values, "game.sha256"));
        long gameBytes = positiveLong(required(values, "game.bytes"), "game.bytes");
        if (gameBytes > MAX_GAME_BYTES) {
            throw new IOException("the update catalog's game is larger than the safety limit");
        }
        URI releaseNotes = null;
        String releaseNotesHash = "";
        long releaseNotesBytes = -1L;
        boolean hasNotesReference = values.containsKey("notes.url")
                || values.containsKey("notes.sha256") || values.containsKey("notes.bytes");
        if (hasNotesReference) {
            releaseNotes = catalog.resolve(required(values, "notes.url"));
            releaseNotesHash = hash(required(values, "notes.sha256"));
            releaseNotesBytes = positiveLong(required(values, "notes.bytes"), "notes.bytes");
            if (releaseNotesBytes > ReleaseNotesCatalog.MAX_BYTES) {
                throw new IOException("the update catalog's release notes are larger than the safety limit");
            }
        }
        return new Release(version, game, gameHash, gameBytes,
                values.getProperty("notes", ""), values.getProperty("source.revision", ""),
                values.getProperty("published", ""), releaseNotes, releaseNotesHash,
                releaseNotesBytes);
    }

    /** Downloads, authenticates through the signed catalog, and caches release history. */
    public ReleaseNotesCatalog.History releaseNotes(Release release) throws IOException {
        ReleaseNotesCatalog.History history;
        byte[] bytes;
        if (release.releaseNotes() == null) {
            history = ReleaseNotesCatalog.fromRelease(release);
            bytes = ReleaseNotesCatalog.encode(history);
        } else {
            bytes = read(release.releaseNotes(), ReleaseNotesCatalog.MAX_BYTES,
                    "release-note history");
            if (release.releaseNotesBytes() >= 0
                    && bytes.length != release.releaseNotesBytes()) {
                throw new IOException("release-note history has " + bytes.length
                        + " bytes; the signed catalog requires " + release.releaseNotesBytes());
            }
            verify(bytes, release.releaseNotesSha256(), "release-note history");
            history = ReleaseNotesCatalog.parse(bytes);
        }
        home.create();
        Path temporary = home.releaseNotesFile().resolveSibling("release-notes.properties.new");
        Files.write(temporary, bytes);
        moveReplacing(temporary, home.releaseNotesFile());
        return history;
    }

    /** Last verified release history, for offline launcher starts. */
    public ReleaseNotesCatalog.History cachedReleaseNotes() {
        try {
            if (Files.isRegularFile(home.releaseNotesFile())) {
                return ReleaseNotesCatalog.parse(Files.readAllBytes(home.releaseNotesFile()));
            }
        } catch (IOException e) {
            // A damaged optional cache must not prevent the installed game from starting.
        }
        return ReleaseNotesCatalog.History.empty();
    }

    /** Every complete installed version, newest first. */
    public List<Installed> installed() throws IOException {
        home.create();
        List<Installed> found = new ArrayList<>();
        try (var listing = Files.list(home.versions())) {
            for (Path directory : listing.filter(Files::isDirectory).toList()) {
                Installed installed = at(directory);
                if (installed != null) {
                    found.add(installed);
                }
            }
        }
        found.sort(Comparator.comparing(Installed::version, GameReleaseManager::compareVersions)
                .reversed());
        return List.copyOf(found);
    }

    public Installed find(String version) throws IOException {
        if (!isVersionId(version)) {
            return null;
        }
        return at(home.versions().resolve(version));
    }

    /**
     * Downloads and verifies a release into a new version directory.
     *
     * <p>Installing the same complete version twice is a no-op. An incomplete
     * directory is never trusted and is replaced only after a new staged copy
     * has been checked.
     */
    public Installed install(Release release, Progress progress)
            throws IOException {
        home.create();
        try (FileChannel channel = FileChannel.open(home.updateLock(),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock ignored = channel.lock()) {
            return installLocked(release, progress);
        }
    }

    private Installed installLocked(Release release, Progress progress) throws IOException {
        String version = versionId(release.version());
        Installed existing = find(version);
        if (existing != null && sha256(existing.gameJar()).equals(release.gameSha256())
                && (release.gameBytes() < 0
                        || Files.size(existing.gameJar()) == release.gameBytes())) {
            return existing;
        }
        Path stage = Files.createTempDirectory(home.work(), "version-");
        try {
            Path game = stage.resolve("game.jar");
            say(progress, "Downloading game " + version);
            download(release.game(), game, MAX_GAME_BYTES, "game jar");
            if (release.gameBytes() >= 0 && Files.size(game) != release.gameBytes()) {
                throw new IOException("game jar has " + Files.size(game)
                        + " bytes; the signed catalog requires " + release.gameBytes());
            }
            verify(game, release.gameSha256(), "game jar");
            writeInstalledMetadata(stage, release);

            Path destination = home.versions().resolve(version);
            say(progress, "Installing game " + version);
            replace(stage, destination);
            stage = null;
            Installed installed = at(destination);
            if (installed == null) {
                throw new IOException("the installed game version is incomplete");
            }
            return installed;
        } finally {
            deleteTree(stage);
        }
    }

    /**
     * Installs the locally built self-contained game for development.
     */
    public Installed installBootstrap(String version, Path gameJar, Progress progress)
            throws IOException {
        home.create();
        try (FileChannel channel = FileChannel.open(home.updateLock(),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock ignored = channel.lock()) {
            return installBootstrapLocked(version, gameJar, progress);
        }
    }

    private Installed installBootstrapLocked(String version, Path gameJar, Progress progress)
            throws IOException {
        version = versionId(version);
        if (!Files.isRegularFile(gameJar)) {
            throw new IOException("bootstrap game jar not found: " + gameJar);
        }
        Installed existing = find(version);
        if (existing != null && Files.mismatch(gameJar, existing.gameJar()) == -1L) {
            return existing;
        }
        Path stage = Files.createTempDirectory(home.work(), "bootstrap-");
        try {
            say(progress, existing == null
                    ? "Installing the bundled game"
                    : "Refreshing the bundled game");
            Files.copy(gameJar, stage.resolve("game.jar"));
            Properties metadata = new Properties();
            metadata.setProperty("format", "chonkcraft-installed-1");
            metadata.setProperty("version", version);
            metadata.setProperty("game.sha256", sha256(gameJar));
            metadata.setProperty("origin", "bundled");
            try (OutputStream out = Files.newOutputStream(stage.resolve("release.properties"))) {
                metadata.store(out, "ChonkCraft installed game");
            }
            Path destination = home.versions().resolve(version);
            replace(stage, destination);
            stage = null;
            return at(destination);
        } finally {
            deleteTree(stage);
        }
    }

    /** Starts the selected jar with the selected graphics pack. */
    public Process launch(Installed version, Path pack) throws IOException {
        List<String> command = launchCommand(version, pack);
        home.create();
        Path log = home.logs().resolve("game-latest.log");
        return new ProcessBuilder(command)
                .directory(version.gameJar().getParent().toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(log.toFile()))
                .start();
    }

    /**
     * The command Play runs, without running it.
     *
     * <p>Split out from {@link #launch} so that the pack the player chose can
     * be followed all the way to the child process. It used to name the pack
     * with {@code -Dchonkcraft.pack}, which the game stopped reading when the
     * property was renamed, so Play launched a game that fell back to the
     * environment or to no assets at all -- and the selection in the launcher
     * had no effect on what loaded. The property name is taken from the side
     * that reads it, which is the only arrangement the next rename cannot
     * break.
     */
    List<String> launchCommand(Installed version, Path pack) throws IOException {
        if (version == null) {
            throw new IOException("no game version is installed");
        }
        if (PackLibrary.read(pack) == null) {
            throw new IOException("select a readable graphics pack before playing");
        }
        Path java = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java");
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-Xms256m");
        command.add("-Xmx2048m");
        // The child identifies the exact verified game JAR it is executing,
        // not the launcher's independently packaged version. Multiplayer
        // admission compares this value before assigning a slot.
        command.add("-Dchonkcraft.version=" + version.version());
        command.add("-Dchonkcraft.network.build=" + version.version());
        command.add("-D" + AssetSource.PACK_PROPERTY + "="
                + pack.toAbsolutePath().normalize());
        if (isMac()) {
            command.add("--add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED");
            String nativeDirectory = System.getProperty(
                    "chonkcraft.packaged.native.dir", "").trim();
            if (!nativeDirectory.isEmpty()
                    && Files.isRegularFile(Path.of(nativeDirectory)
                            .resolve("libjnidispatch.jnilib"))) {
                command.add("-Djna.boot.library.path=" + nativeDirectory);
                command.add("-Djna.nounpack=true");
            }
        }
        command.add("-jar");
        command.add(version.gameJar().toString());
        return command;
    }

    /** Whether this computer differs from the authenticated current channel. */
    public boolean isUpdate(Release release) throws IOException {
        Installed sameVersion = find(release.version());
        if (sameVersion != null) {
            return !sha256(sameVersion.gameJar()).equals(release.gameSha256())
                    || (release.gameBytes() >= 0
                            && Files.size(sameVersion.gameJar()) != release.gameBytes());
        }
        // The signed catalog is authoritative. A stale bundled build can have a
        // numerically larger legacy label and must not pin the player forever.
        return true;
    }

    /**
     * Bounds update disk use while retaining bundled recovery and explicit choices.
     */
    public void retainNewest(int maximum, List<String> protectedVersions) throws IOException {
        if (maximum < 1) {
            throw new IllegalArgumentException("at least one game version must be retained");
        }
        home.create();
        try (FileChannel channel = FileChannel.open(home.updateLock(),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock ignored = channel.lock()) {
            List<Installed> versions = installed();
            Set<String> keep = new LinkedHashSet<>(protectedVersions);
            versions.stream().limit(maximum).map(Installed::version).forEach(keep::add);
            for (Installed version : versions) {
                if (isBundled(version)) {
                    keep.add(version.version());
                }
            }
            for (Installed version : versions) {
                if (!keep.contains(version.version())) {
                    deleteTree(version.gameJar().getParent());
                }
            }
        }
    }

    private static boolean isBundled(Installed installed) {
        Path metadata = installed.gameJar().resolveSibling("release.properties");
        if (!Files.isRegularFile(metadata)) {
            return false;
        }
        Properties values = new Properties();
        try (InputStream in = Files.newInputStream(metadata)) {
            values.load(in);
            return "bundled".equals(values.getProperty("origin"));
        } catch (IOException e) {
            return false;
        }
    }

    private static Installed at(Path directory) {
        Path game = directory.resolve("game.jar");
        if (!Files.isRegularFile(game)) {
            return null;
        }
        return new Installed(directory.getFileName().toString(), game);
    }

    private static void download(URI source, Path destination, long maximum, String label)
            throws IOException {
        Path parent = destination.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (InputStream in = new BufferedInputStream(open(source));
                OutputStream out = new BufferedOutputStream(Files.newOutputStream(destination))) {
            byte[] buffer = new byte[128 * 1024];
            long written = 0;
            for (int count; (count = in.read(buffer)) >= 0; ) {
                if (count == 0) {
                    continue;
                }
                written += count;
                if (written > maximum) {
                    throw new IOException(label + " is larger than the "
                            + (maximum / 1024 / 1024) + " MB safety limit");
                }
                out.write(buffer, 0, count);
            }
        }
    }

    private static InputStream open(URI source) throws IOException {
        URLConnection connection = source.toURL().openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(120_000);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "ChonkCraft-Launcher");
        if (connection instanceof HttpURLConnection http) {
            http.setInstanceFollowRedirects(true);
            int code = http.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("update server returned HTTP " + code);
            }
        }
        return connection.getInputStream();
    }

    private static byte[] read(URI source, long maximum, String label) throws IOException {
        try (InputStream in = new BufferedInputStream(open(source));
                var out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long written = 0;
            for (int count; (count = in.read(buffer)) >= 0; ) {
                if (count == 0) {
                    continue;
                }
                written += count;
                if (written > maximum) {
                    throw new IOException(label + " is larger than its safety limit");
                }
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    private Properties verifyCatalog(byte[] envelopeBytes) throws IOException {
        Properties envelope = properties(envelopeBytes, "update catalog envelope");
        if (!"chonkcraft-signed-release-1".equals(envelope.getProperty("format", ""))) {
            throw new IOException("the update catalog envelope has an unsupported format");
        }
        String keyId = required(envelope, "key.id");
        PublicKey key = trustedKeys.get(keyId);
        if (key == null) {
            throw new IOException("the update catalog uses an untrusted signing key: " + keyId);
        }
        byte[] payload;
        byte[] encodedSignature;
        try {
            payload = Base64.getDecoder().decode(required(envelope, "payload"));
            encodedSignature = Base64.getDecoder().decode(required(envelope, "signature"));
        } catch (IllegalArgumentException e) {
            throw new IOException("the update catalog envelope is not valid base64", e);
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(payload);
            if (!verifier.verify(encodedSignature)) {
                throw new IOException("the update catalog signature is invalid");
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("the update catalog signature could not be verified", e);
        }
        return properties(payload, "signed update catalog");
    }

    private static Properties properties(byte[] bytes, String label) throws IOException {
        Properties values = new Properties();
        try (InputStream in = new java.io.ByteArrayInputStream(bytes)) {
            values.load(in);
        } catch (IllegalArgumentException e) {
            throw new IOException(label + " is malformed", e);
        }
        return values;
    }

    private static Map<String, PublicKey> loadTrustedKeys() {
        try (InputStream in = GameReleaseManager.class.getResourceAsStream(TRUSTED_KEYS)) {
            if (in == null) {
                throw new IllegalStateException("the launcher has no trusted update keys");
            }
            Properties encoded = new Properties();
            encoded.load(in);
            Map<String, PublicKey> keys = new LinkedHashMap<>();
            KeyFactory factory = KeyFactory.getInstance("Ed25519");
            for (String id : encoded.stringPropertyNames()) {
                byte[] der = Base64.getDecoder().decode(encoded.getProperty(id).trim());
                keys.put(id, factory.generatePublic(new X509EncodedKeySpec(der)));
            }
            if (keys.isEmpty()) {
                throw new IllegalStateException("the launcher has no trusted update keys");
            }
            return keys;
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("the launcher's trusted update keys are invalid", e);
        }
    }

    private static long positiveLong(String value, String label) throws IOException {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IOException("the update catalog contains an invalid " + label, e);
        }
    }

    private static void writeInstalledMetadata(Path stage, Release release) throws IOException {
        Properties metadata = new Properties();
        metadata.setProperty("format", "chonkcraft-installed-1");
        metadata.setProperty("version", release.version());
        metadata.setProperty("game.sha256", release.gameSha256());
        metadata.setProperty("game.bytes", Long.toString(release.gameBytes()));
        metadata.setProperty("origin", "remote");
        metadata.setProperty("source.revision", release.revision());
        try (OutputStream out = Files.newOutputStream(stage.resolve("release.properties"))) {
            metadata.store(out, "ChonkCraft installed game");
        }
    }

    private static void verify(Path file, String expected, String label) throws IOException {
        String actual = sha256(file);
        if (!actual.equals(expected)) {
            throw new IOException(label + " failed its SHA-256 check: expected "
                    + expected + ", downloaded " + actual);
        }
    }

    private static void verify(byte[] bytes, String expected, String label) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String actual = HexFormat.of().formatHex(digest.digest(bytes));
            if (!actual.equals(expected)) {
                throw new IOException(label + " failed its SHA-256 check: expected "
                        + expected + ", downloaded " + actual);
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("this JVM has no SHA-256", e);
        }
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[128 * 1024];
                for (int count; (count = in.read(buffer)) >= 0; ) {
                    if (count > 0) {
                        digest.update(buffer, 0, count);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("this JVM has no SHA-256", e);
        }
    }

    private static String required(Properties values, String key) throws IOException {
        String value = values.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IOException("the update catalog has no " + key);
        }
        return value.trim();
    }

    private static String hash(String value) throws IOException {
        String normalized = value.toLowerCase(Locale.ROOT).replaceFirst("^sha256:", "");
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IOException("the update catalog contains an invalid SHA-256");
        }
        return normalized;
    }

    private static String versionId(String value) throws IOException {
        if (!isVersionId(value)) {
            throw new IOException("the game version is not a safe local identifier");
        }
        return value;
    }

    private static boolean isVersionId(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,79}");
    }

    static int compareVersions(String left, String right) {
        String[] a = left.replaceFirst("^[vV]", "").split("[.-]");
        String[] b = right.replaceFirst("^[vV]", "").split("[.-]");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            String x = i < a.length ? a[i] : "0";
            String y = i < b.length ? b[i] : "0";
            int comparison;
            if (x.matches("\\d+") && y.matches("\\d+")) {
                comparison = new java.math.BigInteger(x).compareTo(new java.math.BigInteger(y));
            } else {
                comparison = x.compareToIgnoreCase(y);
            }
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static void say(Progress progress, String message) {
        if (progress != null) {
            progress.say(message);
        }
    }

    private static void move(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, destination);
        }
    }

    private static void moveReplacing(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Replaces a version without ever deleting the last usable copy first. */
    private void replace(Path source, Path destination) throws IOException {
        Path previous = null;
        if (Files.exists(destination)) {
            previous = Files.createTempDirectory(home.work(), "previous-");
            Files.delete(previous);
            move(destination, previous);
        }
        boolean installed = false;
        try {
            move(source, destination);
            installed = true;
        } finally {
            if (!installed && previous != null && Files.exists(previous)) {
                move(previous, destination);
                previous = null;
            }
            deleteTree(previous);
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            // An unused staging directory is harmless and can be retried later.
        }
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
