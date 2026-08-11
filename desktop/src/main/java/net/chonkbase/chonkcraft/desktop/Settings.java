package net.chonkbase.chonkcraft.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Properties;
import net.chonkbase.chonkcraft.engine.sound.SoundServer;

/**
 * What the player has chosen, kept between sessions.
 *
 * <p>Upstream's {@code wc2.preferences}, which
 * {@code scripts/legacyEngine.legacy-declaration:390-430} declares with its defaults and
 * {@code SavePreferences} writes back, and which
 * {@code scripts/legacyEngine.legacy-declaration:490-515} plays into the engine at startup --
 * {@code SetEffectsVolume}, {@code SetMusicVolume} and thirty others. This implementation
 * has had a two-key stub of that table, built fresh every run and never
 * written, so nothing a player set has ever survived quitting.
 *
 * <p>Three things live here, and each of them is a reported complaint. The
 * choice between the two recordings of the soundtrack, which had no setting at
 * all: the discs won whenever there were discs, and a player who wanted the
 * synthesised score could not ask for it. And the two volumes, which were held
 * in a pair of local arrays inside the launcher, so every new game began at full
 * volume however the last one was left -- a player who turns the music down,
 * quits, comes back and finds it loud again reports that the control does not
 * work, and they are not wrong.
 *
 * <p>It sits beside the saves, in {@code ~/.chonkcraft}, because that
 * directory is already this implementation's own and a second place for per-player state
 * is a second place to look for it.
 *
 * <p>Order of precedence follows {@code AssetSource.fromEnvironment}: a system
 * property beats an environment variable beats the file beats the default. The
 * flags exist so that a run can be pinned without disturbing what the player
 * chose, which is the same reason {@code -Dchonkcraft.pack} and
 * {@code WC2_INSTALL_DIR} are the pair they are.
 */
final class Settings {

    /** The file's name, beside {@code saves}. */
    static final String FILE_NAME = "settings.properties";

    static final String MUSIC_BACKEND = "music.backend";
    static final String EFFECT_VOLUME = "volume.effects";
    static final String MUSIC_VOLUME = "volume.music";

    /** {@code -Dchonkcraft.music=xmi} or {@code -Dchonkcraft.music=cd}. */
    static final String MUSIC_PROPERTY = "chonkcraft.music";

    static final String MUSIC_VARIABLE = "CHONKCRAFT_MUSIC";

    private final Path file;
    private final Properties values = new Properties();

    private Settings(Path file) {
        this.file = file;
        if (file != null && Files.isRegularFile(file)) {
            try (var in = Files.newInputStream(file)) {
                values.load(in);
            } catch (IOException | RuntimeException e) {
                // A settings file that will not parse is not a reason to
                // refuse to start the game. The defaults below cover it, and
                // the next save rewrites it. RuntimeException as well as
                // IOException because a malformed backslash-u escape makes
                // Properties.load throw IllegalArgumentException, which would
                // otherwise take the launcher out before a window opened.
                values.clear();
            }
        }
    }

    /** The player's own settings, from {@code ~/.chonkcraft}. */
    static Settings load() {
        return new Settings(defaultFile());
    }

    /** The same, from a stated file, so a test need not touch a home directory. */
    static Settings load(Path file) {
        return new Settings(file);
    }

    /** Where the file lives, beside the saves. */
    static Path defaultFile() {
        return Paths.get(System.getProperty("user.home"), ".chonkcraft", FILE_NAME);
    }

    /**
     * Which recording of the soundtrack to play.
     *
     * <p>Defaults to the discs, which is what the implementation did before there was a
     * setting: the recordings are the better of the two by a wide margin, so
     * they win when there are any. {@link SoundServer#backend} falls back to
     * the synthesised score when there are none.
     */
    SoundServer.Backend musicBackend() {
        SoundServer.Backend flagged = parseBackend(
                Main.setting(MUSIC_PROPERTY, MUSIC_VARIABLE));
        if (flagged != null) {
            return flagged;
        }
        SoundServer.Backend saved = parseBackend(values.getProperty(MUSIC_BACKEND));
        return saved != null ? saved : SoundServer.Backend.CD;
    }

    /**
     * The spellings a player might reasonably write.
     *
     * <p>"xmi" is what the files are called and "midi" and "synth" are what a
     * player calls them; "cd" and "redbook" likewise. Anything else is not an
     * error, it is a line in a file that this version does not understand, and
     * the answer to that is the default rather than a refusal to start.
     */
    static SoundServer.Backend parseBackend(String written) {
        if (written == null || written.isBlank()) {
            return null;
        }
        return switch (written.trim().toLowerCase(Locale.ROOT)) {
            case "xmi", "midi", "synth", "synthesised", "synthesized" -> SoundServer.Backend.XMI;
            case "cd", "redbook", "red-book", "disc", "disk" -> SoundServer.Backend.CD;
            default -> null;
        };
    }

    /** How it is written back out. */
    static String spell(SoundServer.Backend backend) {
        return backend == SoundServer.Backend.XMI ? "xmi" : "cd";
    }

    /**
     * Whether anything has changed since this was last written.
     *
     * <p>The menu's sliders call back on every mouse move while they are being
     * dragged, and a file written on each of those puts the disk in the middle
     * of the drag. The values are rounded to tenths on the way in
     * ({@link SoundServer#clamp}), so a drag from end to end changes this ten
     * times rather than two hundred.
     */
    private boolean changed;

    void setMusicBackend(SoundServer.Backend backend) {
        set(MUSIC_BACKEND, spell(backend));
    }

    private void set(String key, String value) {
        if (!value.equals(values.getProperty(key))) {
            values.setProperty(key, value);
            changed = true;
        }
    }

    float effectVolume() {
        return volume(EFFECT_VOLUME);
    }

    float musicVolume() {
        return volume(MUSIC_VOLUME);
    }

    void setVolumes(float effects, float music) {
        set(EFFECT_VOLUME, Float.toString(SoundServer.clamp(effects)));
        set(MUSIC_VOLUME, Float.toString(SoundServer.clamp(music)));
    }

    private float volume(String key) {
        String written = values.getProperty(key);
        if (written == null || written.isBlank()) {
            return 1f;
        }
        try {
            return SoundServer.clamp(Float.parseFloat(written.trim()));
        } catch (NumberFormatException e) {
            return 1f;
        }
    }

    /**
     * Writes it back, creating the directory if this is the first time.
     *
     * <p>Fails soft. A read-only home directory should cost a player their
     * settings and nothing else.
     *
     * @return whether it was written
     */
    boolean save() {
        if (file == null) {
            return false;
        }
        if (!changed) {
            return true;
        }
        try {
            Path directory = file.getParent();
            if (directory != null) {
                Files.createDirectories(directory);
            }
            try (var out = Files.newOutputStream(file)) {
                values.store(out, "ChonkCraft settings");
            }
            changed = false;
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
