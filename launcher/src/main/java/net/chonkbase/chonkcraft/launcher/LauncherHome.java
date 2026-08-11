package net.chonkbase.chonkcraft.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The launcher's durable files, kept apart from replaceable game versions.
 *
 * <p>The launcher can replace every game jar beneath {@link #versions()} and
 * never touch a graphics pack. That separation is what makes an update safe:
 * the copy made from the player's disc is not an incidental file beside the
 * executable being replaced.
 */
public record LauncherHome(Path root) {

    /** The configured home, or {@code ~/.chonkcraft}. */
    public static LauncherHome configured() {
        String configured = System.getProperty("chonkcraft.home");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("CHONKCRAFT_HOME");
        }
        Path root = configured == null || configured.isBlank()
                ? Paths.get(System.getProperty("user.home"), ".chonkcraft")
                : Paths.get(configured);
        return new LauncherHome(root.toAbsolutePath().normalize());
    }

    public Path packs() {
        return root.resolve("packs");
    }

    public Path versions() {
        return root.resolve("versions");
    }

    public Path work() {
        return root.resolve("work");
    }

    public Path logs() {
        return root.resolve("logs");
    }

    public Path stateFile() {
        return root.resolve("launcher.properties");
    }

    public Path releaseNotesFile() {
        return root.resolve("release-notes.properties");
    }

    public Path updateLock() {
        return root.resolve("update.lock");
    }

    /** Creates every directory a launcher operation may need. */
    public void create() throws IOException {
        Files.createDirectories(packs());
        Files.createDirectories(versions());
        Files.createDirectories(work());
        Files.createDirectories(logs());
    }
}
