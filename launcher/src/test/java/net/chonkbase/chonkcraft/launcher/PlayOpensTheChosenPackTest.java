package net.chonkbase.chonkcraft.launcher;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.chonkbase.assetpack.AssetKind;
import net.chonkbase.assetpack.AssetPackWriter;
import net.chonkbase.assetpack.Codec;
import net.chonkbase.assetpack.PackManifest;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Play launched a game that could not see the pack the player had chosen.
 *
 * <p>So this asserts the whole path: build the command Play would run, apply
 * the settings it carries as a child JVM would, and ask what the game opens.
 * It has to be the file the player picked.
 */
class PlayOpensTheChosenPackTest {

    @TempDir
    Path temporary;

    @AfterEach
    void clearWhatTheChildWouldHaveSet() {
        System.clearProperty(AssetSource.PACK_PROPERTY);
        System.clearProperty("chonkcraft.packaged.native.dir");
    }

    @Test
    @DisplayName("a packaged Mac child uses the separately signed JNA library")
    void packagedMacChildUsesSignedJnaLibrary() throws Exception {
        Path chosen = writePack(temporary.resolve("packs/chosen.chonkpack"));
        Path natives = temporary.resolve("packaged-app");
        Files.createDirectories(natives);
        Files.writeString(natives.resolve("libjnidispatch.jnilib"), "signed native");
        System.setProperty("chonkcraft.packaged.native.dir", natives.toString());
        GameReleaseManager launcher =
                new GameReleaseManager(new LauncherHome(temporary.resolve("home")));

        List<String> command = launcher.launchCommand(installedVersion(), chosen);

        if (System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            assertTrue(command.contains("-Djna.boot.library.path=" + natives),
                    "the child JVM cannot find the separately signed JNA library: " + command);
            assertTrue(command.contains("-Djna.nounpack=true"),
                    "JNA can still extract an unsigned native from the JAR: " + command);
        }
    }

    @Test
    @DisplayName("the pack chosen in the launcher is the pack the game opens")
    void playCarriesTheChosenPackIntoTheGame() throws Exception {
        Path chosen = writePack(temporary.resolve("packs/chosen.chonkpack"));
        GameReleaseManager launcher =
                new GameReleaseManager(new LauncherHome(temporary.resolve("home")));

        List<String> command = launcher.launchCommand(installedVersion(), chosen);
        assertTrue(command.contains("-Dchonkcraft.version=development-1"),
                "the child game did not identify its exact installed release: " + command);
        assertTrue(command.contains("-Dchonkcraft.network.build=development-1"),
                "multiplayer did not receive the installed gameplay build: " + command);

        // Exactly what a child JVM does with the -D flags on its own line.
        applySettings(command);
        try (AssetSource opened = AssetSource.fromEnvironment()) {
            assertNotNull(opened,
                    "Play started a game with no assets at all; the command was " + command);
            assertTrue(opened.describe().contains("The pack the player chose"),
                    "the game opened " + opened.describe()
                            + " rather than the pack chosen in the launcher");
        }
    }

    @Test
    @DisplayName("a packaged child leaves its startup failure in the launcher log")
    void childOutputIsNeverDiscarded() throws Exception {
        Path chosen = writePack(temporary.resolve("packs/logged.chonkpack"));
        LauncherHome home = new LauncherHome(temporary.resolve("logged-home"));
        GameReleaseManager launcher = new GameReleaseManager(home);

        Process child = launcher.launch(installedVersion(), chosen);
        assertTrue(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));

        Path log = home.logs().resolve("game-latest.log");
        assertTrue(Files.isRegularFile(log));
        assertTrue(Files.size(log) > 0,
                "the child failed but its diagnostics were still discarded");

        Process next = launcher.launch(installedVersion(), chosen);
        assertTrue(next.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        try (var files = Files.list(home.logs())) {
            Path archived = files.filter(path -> path.getFileName().toString()
                            .startsWith("game-development-1-"))
                    .findFirst().orElseThrow();
            assertTrue(Files.size(archived) > 0,
                    "pressing Play again erased the only evidence of the prior failure");
        }
    }

    /** Applies the command's {@code -D} settings, as the child JVM would. */
    private static void applySettings(List<String> command) {
        for (String argument : command) {
            if (!argument.startsWith("-D")) {
                continue;
            }
            int split = argument.indexOf('=');
            if (split > 2) {
                System.setProperty(argument.substring(2, split),
                        argument.substring(split + 1));
            }
        }
    }

    /** A minimal playable pack carrying the retail AI slot. */
    private static Path writePack(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        PackManifest.Identity identity = new PackManifest.Identity(
                "wc2-chosen", "The pack the player chose",
                "test installer", "test builder", "2026-08-07T12:00:00Z",
                new LinkedHashMap<>());
        try (AssetPackWriter writer = new AssetPackWriter(file, identity)) {
            int ai = writer.add("archives/maindat/0277", AssetKind.BINARY, Codec.STORE,
                    "assets/archives/maindat/0277.bin", new byte[] {1, 2, 3, 4},
                    4, Map.of());
            int[] slots = new int[278];
            Arrays.fill(slots, -1);
            slots[277] = ai;
            writer.archive(1000, "maindat", slots);
            writer.finish();
        }
        return file;
    }

    /** A version tree complete enough that Play will run it. */
    private GameReleaseManager.Installed installedVersion() throws Exception {
        Path root = temporary.resolve("versions/development-1");
        Files.createDirectories(root);
        Files.writeString(root.resolve("game.jar"), "not a real jar\n");
        return new GameReleaseManager.Installed("development-1", root.resolve("game.jar"));
    }
}
