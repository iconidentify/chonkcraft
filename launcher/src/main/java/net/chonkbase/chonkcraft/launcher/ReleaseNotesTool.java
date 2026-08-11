package net.chonkbase.chonkcraft.launcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** Release-pipeline entry point for carrying verified history between publications. */
public final class ReleaseNotesTool {

    private ReleaseNotesTool() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("expected fetch or append");
        }
        switch (args[0]) {
            case "fetch" -> fetch(args);
            case "append" -> append(args);
            default -> throw new IllegalArgumentException("unknown release-note command " + args[0]);
        }
    }

    private static void fetch(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("fetch needs CATALOG HOME OUTPUT");
        }
        System.setProperty("chonkcraft.update.catalog", args[1]);
        LauncherHome home = new LauncherHome(Path.of(args[2]).toAbsolutePath().normalize());
        home.create();
        GameReleaseManager manager = new GameReleaseManager(home);
        var history = manager.releaseNotes(manager.latest());
        write(Path.of(args[3]), ReleaseNotesCatalog.encode(history));
    }

    private static void append(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("append needs PREVIOUS OUTPUT");
        }
        Path previous = Path.of(args[1]);
        var history = Files.isRegularFile(previous)
                ? ReleaseNotesCatalog.parse(Files.readAllBytes(previous))
                : ReleaseNotesCatalog.History.empty();
        String version = required("CHONKCRAFT_VERSION");
        String published = environment("CHONKCRAFT_RELEASE_PUBLISHED", Instant.now().toString());
        String title = environment("CHONKCRAFT_RELEASE_TITLE", "ChonkCraft " + version);
        String notes = environment("CHONKCRAFT_RELEASE_NOTES", title);
        String revision = environment("GITHUB_SHA", "local");
        var current = new ReleaseNotesCatalog.Entry(
                version, published, title, notes, revision);
        write(Path.of(args[2]), ReleaseNotesCatalog.encode(
                ReleaseNotesCatalog.append(history, current)));
    }

    private static void write(Path destination, byte[] bytes) throws Exception {
        Path parent = destination.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(destination, bytes);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
