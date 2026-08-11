package net.chonkbase.chonkcraft.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JComboBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * First run is a gate, while a complete installation has one clear Play path.
 *
 * <p>The game cannot draw anything without a graphics pack and cannot run
 * without a jar. The visible button is checked instead of the model fields so
 * a future layout cannot accidentally enable the way through.
 */
class LauncherViewTest {

    @Test
    @DisplayName("play stays closed until both graphics and game are ready")
    void playIsGatedByBothSelections() {
        LauncherView view = new LauncherView(new Actions());
        view.setPacks(List.of(), null);
        view.setCurrentGame(null);
        assertFalse(view.playEnabled(), "first run offered to start with no assets");
        assertTrue(view.graphicsButtonText().contains("ADD PACK"),
                "the empty graphics selector did not open with a clear choice");

        view.setPacks(List.of(new PackLibrary.PackInfo(
                Path.of("/packs/original.chonkpack"), "original", "Original",
                "disc", false, false, 1200)), null);
        assertFalse(view.playEnabled(), "a graphics pack alone enabled Play");

        view.setCurrentGame(new GameReleaseManager.Installed(
                "1.0.0", Path.of("/game.jar")));
        assertTrue(view.playEnabled(), "a complete installation left Play disabled");

        view.setBusy(true, "Installing the latest game");
        assertFalse(view.playEnabled(), "Play remained open during a mandatory update");
        view.setBusy(false, "Ready");
        assertTrue(view.playEnabled(), "Play did not reopen after the update completed");
        assertEquals(0, count(view, JComboBox.class),
                "the simplified launcher exposed a version selector");
    }

    @Test
    @DisplayName("the original ChonkCraft icon is packaged with transparency")
    void iconIsPackaged() throws Exception {
        var resource = LauncherView.class.getResource("/icons/chonkcraft.png");
        assertTrue(resource != null, "the launcher icon resource is missing");
        var icon = ImageIO.read(resource);
        assertTrue(icon.getWidth() >= 1024 && icon.getHeight() >= 1024,
                "the application icon is not release resolution");
        assertTrue(icon.getColorModel().hasAlpha(),
                "the application icon lost its transparent background");
    }

    @Test
    @DisplayName("update failures stay inside the game-code field")
    void updateFailureUsesACompactAccurateBadge() {
        LauncherView view = new LauncherView(new Actions());
        view.setGameStatus("Update check unavailable");
        assertEquals("CHECK UNAVAILABLE", view.updateBadgeText());

        view.setGameStatus("Up to date");
        assertEquals("", view.updateBadgeText());
    }

    @Test
    @DisplayName("new authenticated notes get a subtle cue until they are viewed")
    void newReleaseNotesAreVisibleWithoutInterruptingPlay() {
        LauncherView view = new LauncherView(new Actions());
        var history = new ReleaseNotesCatalog.History(List.of(
                new ReleaseNotesCatalog.Entry("2.0.0", "2026-08-10T12:00:00Z",
                        "Better battles", "- Orders are more reliable.", "abc")));
        view.setReleaseNotes(history, true);

        assertEquals("WHAT'S NEW", view.releaseNotesButtonText());
        assertTrue(view.newReleaseNotesVisible());

        view.setReleaseNotes(history, false);
        assertEquals("RELEASE NOTES", view.releaseNotesButtonText());
        assertFalse(view.newReleaseNotesVisible());
    }

    private static final class Actions implements LauncherView.Actions {
        @Override public void managePacks() {}
        @Override public void releaseNotes() {}
        @Override public void play() {}
    }

    private static int count(java.awt.Container root, Class<?> type) {
        int total = 0;
        for (java.awt.Component child : root.getComponents()) {
            if (type.isInstance(child)) {
                total++;
            }
            if (child instanceof java.awt.Container nested) {
                total += count(nested, type);
            }
        }
        return total;
    }
}
