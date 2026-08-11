package net.chonkbase.chonkcraft.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The pack and game a player chose remain paired on the next launch. */
class LauncherStateTest {

    @TempDir
    Path temporary;

    @Test
    @DisplayName("the selected pack and game version survive closing the launcher")
    void choicesRoundTrip() throws Exception {
        Path file = temporary.resolve("launcher.properties");
        Path pack = temporary.resolve("packs/original.chonkpack");
        LauncherState first = LauncherState.load(file);
        first.selectPack(pack);
        first.selectVersion("1.8.2");
        first.save();

        LauncherState reopened = LauncherState.load(file);
        assertEquals(pack.toAbsolutePath().normalize(), reopened.selectedPack(),
                "the chosen graphics pack was forgotten");
        assertEquals("1.8.2", reopened.selectedVersion(),
                "the chosen game version was forgotten");
    }

    @Test
    @DisplayName("a failed automatic update restores the selected working version")
    void pendingUpdateRollsBack() throws Exception {
        Path file = temporary.resolve("launcher.properties");
        LauncherState state = LauncherState.load(file);
        state.selectVersion("1.0.0");
        state.stageVersion("1.0.9");
        state.save();

        LauncherState reopened = LauncherState.load(file);
        assertEquals("1.0.9", reopened.selectedVersion());
        assertEquals("1.0.9", reopened.pendingVersion());
        assertEquals("1.0.0", reopened.rollbackPending("1.0.9"));
        reopened.save();

        LauncherState finalState = LauncherState.load(file);
        assertEquals("1.0.0", finalState.selectedVersion());
        assertEquals("", finalState.pendingVersion());
    }

    @Test
    @DisplayName("a healthy automatic update becomes the durable selection")
    void pendingUpdateIsConfirmed() throws Exception {
        Path file = temporary.resolve("launcher.properties");
        LauncherState state = LauncherState.load(file);
        state.selectVersion("1.0.0");
        state.stageVersion("1.0.9");
        state.confirmVersion("1.0.9");
        state.save();

        LauncherState reopened = LauncherState.load(file);
        assertEquals("1.0.9", reopened.selectedVersion());
        assertEquals("", reopened.pendingVersion());
    }

    @Test
    @DisplayName("the new-note cue clears only for the release the player viewed")
    void viewedReleaseNotesAreDurableAndVersioned() throws Exception {
        Path file = temporary.resolve("launcher.properties");
        LauncherState state = LauncherState.load(file);
        assertFalse(state.hasSeenReleaseNotes("2.0.0"));
        state.markReleaseNotesSeen("2.0.0");
        state.save();

        LauncherState reopened = LauncherState.load(file);
        assertTrue(reopened.hasSeenReleaseNotes("2.0.0"));
        assertFalse(reopened.hasSeenReleaseNotes("2.0.1"));
    }
}
