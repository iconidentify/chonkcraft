package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.chonkbase.chonkcraft.engine.sound.SoundServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The settings that survive quitting.
 *
 * <p>Upstream's {@code wc2.preferences}: declared with its defaults at
 * {@code scripts/legacyEngine.legacy-declaration:390-430}, written by {@code SavePreferences} and
 * played back into the engine at {@code :490-515}. This implementation had a two-key stub
 * of that table, built fresh every run and never written, so nothing a player
 * chose has ever survived quitting -- and the two volumes were locals of the
 * launcher, so nothing a player chose survived starting a second game either.
 *
 * <p>These write to a temporary file. The real one lives at
 * {@code ~/.chonkcraft/settings.properties}, beside the saves, and a test that
 * used it would change the settings of whoever ran it.
 */
class SettingsTest {

    @Test
    @DisplayName("a soundtrack chosen in one game is still chosen in the next")
    void theSoundtrackChoiceSurvives(@TempDir Path directory) {
        Path file = directory.resolve(Settings.FILE_NAME);
        Settings first = Settings.load(file);
        assertEquals(SoundServer.Backend.CD, first.musicBackend(),
                "the discs are the default, as they were before there was a setting");

        first.setMusicBackend(SoundServer.Backend.XMI);
        assertTrue(first.save(), "the settings would not write");

        assertEquals(SoundServer.Backend.XMI, Settings.load(file).musicBackend(),
                "the player asked for the synthesised score and the next launch forgot");
    }

    @Test
    @DisplayName("volumes chosen in one game are still chosen in the next")
    void theVolumesSurvive(@TempDir Path directory) throws IOException {
        Path file = directory.resolve(Settings.FILE_NAME);
        Settings first = Settings.load(file);
        assertEquals(1f, first.effectVolume(), 1e-6f, "full is the default");
        assertEquals(1f, first.musicVolume(), 1e-6f, "full is the default");

        first.setVolumes(0.8f, 0.3f);
        assertTrue(first.save(), "the settings would not write");
        assertTrue(Files.exists(file), "nothing was written to " + file);

        Settings second = Settings.load(file);
        assertEquals(0.8f, second.effectVolume(), 1e-6f,
                "the effects volume went back to full, so a player who turned it down"
                        + " has to turn it down again every time they start the game");
        assertEquals(0.3f, second.musicVolume(), 1e-6f,
                "the music volume went back to full, which is the complaint that the"
                        + " control does not work");
    }

    @Test
    @DisplayName("dragging a slider does not rewrite the file on every mouse move")
    void anUnchangedSettingIsNotRewritten(@TempDir Path directory) throws IOException {
        // The menu's sliders call back on every mouse move while they are
        // dragged. Writing the file on each of those would put the disk in the
        // middle of the drag.
        Path file = directory.resolve(Settings.FILE_NAME);
        Settings settings = Settings.load(file);
        settings.setVolumes(0.4f, 0.4f);
        assertTrue(settings.save(), "the first write must happen");
        long written = Files.getLastModifiedTime(file).toMillis();

        for (int i = 0; i < 50; i++) {
            settings.setVolumes(0.4f, 0.4f);
            settings.save();
        }

        assertEquals(written, Files.getLastModifiedTime(file).toMillis(),
                "the file was rewritten although nothing changed");
    }

    @Test
    @DisplayName("the spellings a player might reasonably write are all understood")
    void theBackendNamesAreForgiving() {
        assertEquals(SoundServer.Backend.XMI, Settings.parseBackend("xmi"));
        assertEquals(SoundServer.Backend.XMI, Settings.parseBackend("MIDI"));
        assertEquals(SoundServer.Backend.XMI, Settings.parseBackend(" synth "));
        assertEquals(SoundServer.Backend.CD, Settings.parseBackend("cd"));
        assertEquals(SoundServer.Backend.CD, Settings.parseBackend("redbook"));
        assertNull(Settings.parseBackend("loud"),
                "an unknown word is a line this version does not understand, not a reason"
                        + " to refuse to start");
        assertNull(Settings.parseBackend(""));
        assertNull(Settings.parseBackend(null));
    }

    @Test
    @DisplayName("the flag overrides the saved choice, as -Dchonkcraft.pack does")
    void theFlagWins(@TempDir Path directory) {
        Path file = directory.resolve(Settings.FILE_NAME);
        Settings settings = Settings.load(file);
        settings.setMusicBackend(SoundServer.Backend.CD);
        settings.save();

        String was = System.getProperty(Settings.MUSIC_PROPERTY);
        try {
            System.setProperty(Settings.MUSIC_PROPERTY, "xmi");
            assertEquals(SoundServer.Backend.XMI, Settings.load(file).musicBackend(),
                    "-Dchonkcraft.music must pin a run without disturbing what the player chose,"
                            + " which is what -Dchonkcraft.pack and WC2_INSTALL_DIR do");
        } finally {
            if (was == null) {
                System.clearProperty(Settings.MUSIC_PROPERTY);
            } else {
                System.setProperty(Settings.MUSIC_PROPERTY, was);
            }
        }

        assertEquals(SoundServer.Backend.CD, Settings.load(file).musicBackend(),
                "the flag went away and took the player's own choice with it");
    }

    @Test
    @DisplayName("a settings file that will not parse is not a reason to refuse to start")
    void aBrokenFileFallsBackToTheDefaults(@TempDir Path directory) throws IOException {
        Path file = directory.resolve(Settings.FILE_NAME);
        Files.writeString(file, "music.backend=\\uZZZZ\nvolume.music=loud\n");

        Settings settings = Settings.load(file);
        assertEquals(SoundServer.Backend.CD, settings.musicBackend());
        assertEquals(1f, settings.musicVolume(), 1e-6f);
    }

    @Test
    @DisplayName("the file sits beside the saves rather than in a second place")
    void theFileSitsBesideTheSaves() {
        // ~/.chonkcraft is already this implementation's own: GameScreen.saveDirectory
        // puts saves there. A second directory for per-player state is a second
        // place to look for it.
        assertEquals(GameScreen.saveDirectory().getParent(),
                Settings.defaultFile().getParent(),
                "the settings and the saves must live in the same directory");
        assertEquals(Settings.FILE_NAME, Settings.defaultFile().getFileName().toString());
    }
}
