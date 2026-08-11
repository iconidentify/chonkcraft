package net.chonkbase.chonkcraft.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * The choices that survive closing the launcher.
 *
 * <p>Only identities and paths live here. Graphics stay in packs and game code
 * stays in version directories, so a partly written settings file cannot
 * damage either one.
 */
public final class LauncherState {

    private static final String SELECTED_PACK = "selected.pack";
    private static final String SELECTED_VERSION = "selected.version";
    private static final String PENDING_VERSION = "update.pending.version";
    private static final String PREVIOUS_VERSION = "update.previous.version";
    private static final String LAST_GOOD_VERSION = "update.last-good.version";
    private static final String SEEN_RELEASE_NOTES = "release-notes.seen.version";

    private final Path file;
    private final Properties values = new Properties();

    private LauncherState(Path file) {
        this.file = file;
    }

    public static LauncherState load(Path file) {
        LauncherState state = new LauncherState(file);
        if (!Files.isRegularFile(file)) {
            return state;
        }
        try (InputStream in = Files.newInputStream(file)) {
            state.values.load(in);
        } catch (IOException e) {
            // A launcher with forgotten choices is still a working launcher.
        }
        return state;
    }

    public Path selectedPack() {
        String value = values.getProperty(SELECTED_PACK);
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    public void selectPack(Path pack) {
        set(SELECTED_PACK, pack == null ? null : pack.toAbsolutePath().normalize().toString());
    }

    public String selectedVersion() {
        return values.getProperty(SELECTED_VERSION, "");
    }

    public void selectVersion(String version) {
        set(SELECTED_VERSION, version);
    }

    /** Selects a downloaded update while retaining the version to restore on failure. */
    public void stageVersion(String version) {
        String selected = selectedVersion();
        if (version == null || version.isBlank() || version.equals(selected)) {
            return;
        }
        set(PREVIOUS_VERSION, selected);
        set(PENDING_VERSION, version);
        set(SELECTED_VERSION, version);
    }

    public String pendingVersion() {
        return values.getProperty(PENDING_VERSION, "");
    }

    public boolean hasSeenReleaseNotes(String version) {
        return version != null && version.equals(values.getProperty(SEEN_RELEASE_NOTES, ""));
    }

    public void markReleaseNotesSeen(String version) {
        set(SEEN_RELEASE_NOTES, version);
    }

    /** Marks a staged version healthy after it starts successfully. */
    public void confirmVersion(String version) {
        if (version != null && version.equals(pendingVersion())) {
            set(LAST_GOOD_VERSION, version);
            set(PENDING_VERSION, null);
            set(PREVIOUS_VERSION, null);
        }
    }

    /** Restores the last selected version after a staged build fails to start. */
    public String rollbackPending(String version) {
        if (version == null || !version.equals(pendingVersion())) {
            return selectedVersion();
        }
        String previous = values.getProperty(PREVIOUS_VERSION, "");
        set(SELECTED_VERSION, previous);
        set(PENDING_VERSION, null);
        set(PREVIOUS_VERSION, null);
        return previous;
    }

    private void set(String key, String value) {
        if (value == null || value.isBlank()) {
            values.remove(key);
        } else {
            values.setProperty(key, value);
        }
    }

    /** Writes through a sibling and replaces the old choices in one move. */
    public void save() throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".new");
        try (OutputStream out = Files.newOutputStream(temporary)) {
            values.store(out, "ChonkCraft launcher");
        }
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
