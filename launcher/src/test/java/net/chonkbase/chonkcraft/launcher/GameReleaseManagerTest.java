package net.chonkbase.chonkcraft.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A self-contained game update becomes runnable only after its JAR verifies.
 *
 * <p>The old packaging had one jar and no durable launcher, so replacing the
 * running application was the update plan. These tests start from the same
 * catalog a release server hosts and look at the complete local version the
 * launcher can hand to a child JVM.
 */
class GameReleaseManagerTest {

    @TempDir
    Path temporary;
    private KeyPair signingKey;

    @BeforeEach
    void createSigningKey() throws Exception {
        signingKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    @AfterEach
    void clearCatalog() {
        System.clearProperty("chonkcraft.update.catalog");
        System.clearProperty("chonkcraft.update.check");
    }

    @Test
    @DisplayName("a hash-checked catalog installs the self-contained game")
    void aReleaseIsInstalled() throws Exception {
        Path server = temporary.resolve("server");
        Files.createDirectories(server);
        Path game = server.resolve("game.jar");
        Files.writeString(game, "game", StandardCharsets.US_ASCII);
        Path catalog = catalog(server, game, GameReleaseManager.sha256(game));
        System.setProperty("chonkcraft.update.catalog", catalog.toUri().toString());

        LauncherHome home = new LauncherHome(temporary.resolve("home"));
        GameReleaseManager manager = manager(home);
        GameReleaseManager.Release release = manager.latest();
        assertTrue(manager.isUpdate(release), "a fresh launcher did not offer the release");

        GameReleaseManager.Installed installed = manager.install(release, null);
        assertEquals("1.2.3", installed.version(), "the installed version changed");
        assertEquals("game", Files.readString(installed.gameJar(), StandardCharsets.US_ASCII),
                "the game jar changed during installation");
        assertEquals(2, Files.list(installed.gameJar().getParent()).count(),
                "the installed version did not contain exactly its jar and receipt");
        assertFalse(manager.isUpdate(release), "an installed release is still offered as new");
    }

    @Test
    @DisplayName("release history is hash-checked and retained for offline starts")
    void releaseHistoryIsAuthenticatedAndCached() throws Exception {
        Path server = temporary.resolve("server-notes");
        Files.createDirectories(server);
        Path game = server.resolve("game.jar");
        Files.writeString(game, "game", StandardCharsets.US_ASCII);
        var expected = new ReleaseNotesCatalog.History(List.of(
                new ReleaseNotesCatalog.Entry("1.2.3", "2026-08-10T12:00:00Z",
                        "A better game", "- Tankers return reliably.", "test-revision")));
        Path notes = server.resolve("release-notes.properties");
        Files.write(notes, ReleaseNotesCatalog.encode(expected));
        Path catalog = catalog(server, game, GameReleaseManager.sha256(game));
        System.setProperty("chonkcraft.update.catalog", catalog.toUri().toString());

        GameReleaseManager manager = manager(new LauncherHome(temporary.resolve("home-notes")));
        GameReleaseManager.Release release = manager.latest();
        assertEquals(expected, manager.releaseNotes(release));
        assertEquals(expected, manager.cachedReleaseNotes());

        Files.writeString(notes, "tampered");
        IOException failure = assertThrows(IOException.class,
                () -> manager.releaseNotes(release));
        assertTrue(failure.getMessage().contains("bytes")
                        || failure.getMessage().contains("SHA-256"));
        assertEquals(expected, manager.cachedReleaseNotes(),
                "a failed refresh replaced the last verified history");
    }

    @Test
    @DisplayName("a rebuilt bundled game refreshes a cached copy with the same version")
    void aSameVersionBootstrapRefreshesChangedGameCode() throws Exception {
        Path game = temporary.resolve("game.jar");
        Files.writeString(game, "old game", StandardCharsets.US_ASCII);
        LauncherHome home = new LauncherHome(temporary.resolve("home"));
        GameReleaseManager manager = manager(home);
        GameReleaseManager.Installed first =
                manager.installBootstrap("1.0.0", game, null);
        assertEquals("old game",
                Files.readString(first.gameJar(), StandardCharsets.US_ASCII));

        Files.writeString(game, "fixed BNE game", StandardCharsets.US_ASCII);
        GameReleaseManager.Installed refreshed =
                manager.installBootstrap("1.0.0", game, null);

        assertEquals("fixed BNE game",
                Files.readString(refreshed.gameJar(), StandardCharsets.US_ASCII),
                "the same version label kept a stale bundled game jar");
    }

    @Test
    @DisplayName("a download with the wrong digest never becomes an installed version")
    void aBadDigestIsRefused() throws Exception {
        Path server = temporary.resolve("server");
        Files.createDirectories(server);
        Path game = server.resolve("game.jar");
        Files.writeString(game, "game", StandardCharsets.US_ASCII);
        Path catalog = catalog(server, game, "0".repeat(64));
        System.setProperty("chonkcraft.update.catalog", catalog.toUri().toString());

        GameReleaseManager manager = manager(new LauncherHome(temporary.resolve("home")));
        IOException failure = assertThrows(IOException.class,
                () -> manager.install(manager.latest(), null),
                "a corrupt jar was installed");
        assertTrue(failure.getMessage().contains("SHA-256"),
                "the refusal did not say the integrity check failed");
        assertTrue(manager.installed().isEmpty(),
                "a failed download left a selectable game version");
    }

    @Test
    @DisplayName("numeric versions sort as releases rather than words")
    void versionsSortNumerically() {
        assertTrue(GameReleaseManager.compareVersions("1.10.0", "1.9.9") > 0,
                "version 1.10 sorted before 1.9");
        assertEquals(0, GameReleaseManager.compareVersions("v2.0", "2.0.0"),
                "a leading v or omitted zero changed the release");
    }

    @Test
    @DisplayName("a catalog version cannot escape the managed version directory")
    void anUnsafeVersionIsRefused() throws Exception {
        Path game = temporary.resolve("game.jar");
        Files.writeString(game, "game", StandardCharsets.US_ASCII);
        GameReleaseManager.Release release = new GameReleaseManager.Release(
                "../../outside", game.toUri(), GameReleaseManager.sha256(game), "");
        GameReleaseManager manager = manager(new LauncherHome(temporary.resolve("home")));

        IOException failure = assertThrows(IOException.class,
                () -> manager.install(release, null),
                "a path-shaped version was installed");
        assertTrue(failure.getMessage().contains("safe local identifier"),
                "the unsafe version refusal was not explained");
        assertFalse(Files.exists(temporary.resolve("outside")),
                "the version wrote outside the managed launcher home");
    }

    @Test
    @DisplayName("a catalog not signed by a trusted key is refused before its URL is trusted")
    void anUntrustedCatalogIsRefused() throws Exception {
        Path server = temporary.resolve("server");
        Files.createDirectories(server);
        Path game = server.resolve("game.jar");
        Files.writeString(game, "game", StandardCharsets.US_ASCII);
        Path catalog = catalog(server, game, GameReleaseManager.sha256(game));
        Properties envelope = new Properties();
        try (var in = Files.newInputStream(catalog)) {
            envelope.load(in);
        }
        byte[] payload = Base64.getDecoder().decode(envelope.getProperty("payload"));
        payload[0] ^= 1;
        envelope.setProperty("payload", Base64.getEncoder().encodeToString(payload));
        try (var out = Files.newOutputStream(catalog)) {
            envelope.store(out, "");
        }
        System.setProperty("chonkcraft.update.catalog", catalog.toUri().toString());

        IOException failure = assertThrows(IOException.class,
                () -> manager(new LauncherHome(temporary.resolve("home"))).latest());
        assertTrue(failure.getMessage().contains("signature is invalid"),
                "a modified catalog did not fail authentication");
    }

    @Test
    @DisplayName("a reused version label cannot pin a stale jar")
    void aSameVersionWithAChangedSignedHashIsReplaced() throws Exception {
        LauncherHome home = new LauncherHome(temporary.resolve("home"));
        GameReleaseManager manager = manager(home);
        Path bundled = temporary.resolve("bundled.jar");
        Files.writeString(bundled, "bundled game", StandardCharsets.US_ASCII);
        manager.installBootstrap("1.2.3", bundled, null);

        Path server = temporary.resolve("server");
        Files.createDirectories(server);
        Path game = server.resolve("game.jar");
        Files.writeString(game, "fixed remote game", StandardCharsets.US_ASCII);
        Path catalog = catalog(server, game, GameReleaseManager.sha256(game));
        System.setProperty("chonkcraft.update.catalog", catalog.toUri().toString());

        GameReleaseManager.Release release = manager.latest();
        assertTrue(manager.isUpdate(release),
                "a changed signed JAR under the same version was not offered");
        GameReleaseManager.Installed installed = manager.install(release, null);
        assertEquals("fixed remote game", Files.readString(installed.gameJar()),
                "the old same-label jar was trusted without matching the signed hash");
    }

    @Test
    @DisplayName("a legacy bundled version cannot outrank the authenticated channel")
    void aHigherLegacyLabelCannotPinOldGameCode() throws Exception {
        LauncherHome home = new LauncherHome(temporary.resolve("home"));
        GameReleaseManager manager = manager(home);
        Path bundled = temporary.resolve("legacy.jar");
        Files.writeString(bundled, "legacy game", StandardCharsets.US_ASCII);
        manager.installBootstrap("1.0.1", bundled, null);

        Path game = temporary.resolve("current.jar");
        Files.writeString(game, "authenticated current game", StandardCharsets.US_ASCII);
        GameReleaseManager.Release release = new GameReleaseManager.Release(
                "0.1.1-beta1", game.toUri(), GameReleaseManager.sha256(game),
                Files.size(game), "", "test-revision");

        assertTrue(manager.isUpdate(release),
                "a numerically larger legacy label pinned stale game code");
    }

    @Test
    @DisplayName("closing the game immediately checks and stages the authenticated current build")
    void gameExitChecksForAnUpdate() throws Exception {
        LauncherHome home = new LauncherHome(temporary.resolve("home"));
        GameReleaseManager manager = manager(home);
        Path bundled = temporary.resolve("old-game.jar");
        Files.writeString(bundled, "old game");
        var old = manager.installBootstrap("1.0.0", bundled, null);
        LauncherState state = LauncherState.load(home.stateFile());
        state.selectVersion(old.version());
        state.save();

        Path server = temporary.resolve("server");
        Files.createDirectories(server);
        Path current = server.resolve("game.jar");
        Files.writeString(current, "current game");
        System.setProperty("chonkcraft.update.catalog",
                catalog(server, current, GameReleaseManager.sha256(current)).toUri().toString());

        Main.checkForUpdateAfterExit(manager, state);

        LauncherState reloaded = LauncherState.load(home.stateFile());
        assertEquals("1.2.3", reloaded.selectedVersion());
        assertEquals("current game", Files.readString(manager.find("1.2.3").gameJar()));
    }

    @Test
    @DisplayName("old downloaded versions are bounded without deleting a protected version")
    void oldVersionsArePruned() throws Exception {
        LauncherHome home = new LauncherHome(temporary.resolve("home"));
        home.create();
        for (String version : List.of("1.0.0", "2.0.0", "3.0.0", "4.0.0")) {
            Path directory = home.versions().resolve(version);
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("game.jar"), version);
        }
        GameReleaseManager manager = manager(home);

        manager.retainNewest(2, List.of("1.0.0"));

        assertEquals(List.of("4.0.0", "3.0.0", "1.0.0"),
                manager.installed().stream().map(GameReleaseManager.Installed::version).toList());
        assertFalse(Files.exists(home.versions().resolve("2.0.0")));
    }

    private Path catalog(Path server, Path game, String gameHash) throws Exception {
        Properties values = new Properties();
        values.setProperty("format", "chonkcraft-release-3");
        values.setProperty("version", "1.2.3");
        values.setProperty("game.url", game.getFileName().toString());
        values.setProperty("game.sha256", gameHash);
        values.setProperty("game.bytes", Long.toString(Files.size(game)));
        values.setProperty("source.revision", "test-revision");
        values.setProperty("published", "2026-08-10T12:00:00Z");
        Path notes = server.resolve("release-notes.properties");
        if (Files.isRegularFile(notes)) {
            values.setProperty("notes.url", notes.getFileName().toString());
            values.setProperty("notes.sha256", GameReleaseManager.sha256(notes));
            values.setProperty("notes.bytes", Long.toString(Files.size(notes)));
        }
        Path payloadFile = server.resolve("release.payload");
        try (var out = Files.newOutputStream(payloadFile)) {
            values.store(out, "");
        }
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(signingKey.getPrivate());
        byte[] payload = Files.readAllBytes(payloadFile);
        signer.update(payload);
        Properties envelope = new Properties();
        envelope.setProperty("format", "chonkcraft-signed-release-1");
        envelope.setProperty("key.id", "test-key");
        envelope.setProperty("payload", Base64.getEncoder().encodeToString(payload));
        envelope.setProperty("signature", Base64.getEncoder().encodeToString(signer.sign()));
        Path catalog = server.resolve("release.properties");
        try (var out = Files.newOutputStream(catalog)) {
            envelope.store(out, "");
        }
        Files.delete(payloadFile);
        return catalog;
    }

    private GameReleaseManager manager(LauncherHome home) {
        return new GameReleaseManager(home, Map.of("test-key", signingKey.getPublic()));
    }

}
