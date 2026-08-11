package net.chonkbase.chonkcraft.engine.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.chonkbase.runtime.audio.PcmClip;
import net.chonkbase.runtime.audio.PcmFormat;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A worker reporting a finished building actually reaches a decoded clip.
 *
 * <p>The second half of the {@code DefineGameSounds} fix. Binding the game
 * sounds made {@code work-complete-human} resolve to
 * {@code human/units/peasant/work_complete.wav} in the bank, but the player
 * still heard nothing, because {@code GameAudio.chosenPath} looked only at the
 * unit type's own sound table and stopped there. Of every unit in the game,
 * only the oil tanker declares a {@code work-complete} of its own, so the
 * fallback is not an edge case -- it is the entire feature.
 *
 * <p>These assert on the whole path, from the unit to bytes of audio, rather
 * than on the bank holding the file. A test that checks the bank would have
 * passed throughout the period in which the game was silent.
 */
class WorkCompleteSoundTest {

    private static GameData gameData() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    @Test
    @DisplayName("a peasant finishing a building resolves to a real decoded clip")
    void aPeasantReportsWorkComplete() {
        GameData data = gameData();
        SoundBank bank = data.sounds();
        GameAudio audio = new GameAudio(bank);

        Unit peasant = unit(data, "unit-peasant");
        // The peasant declares no work-complete of its own; if it did, this
        // test would pass without exercising the fallback at all.
        assertTrue(isBlank(peasant.type().sounds().get("work-complete")),
                "the peasant is expected to have no work-complete of its own; "
                        + "if that changed, this test no longer covers the fallback");

        String path = audio.chosenPath(peasant, "work-complete", size -> 0);
        assertNotNull(path, "a peasant finishing a building must have something to say");

        PcmClip clip = bank.clip(path);
        assertNotNull(clip, path + " did not decode");
        assertEquals(PcmFormat.GAME_SAMPLE_RATE, clip.sampleRate());
        assertTrue(clip.frameCount() > 0, path + " decoded to no audio");
    }

    @Test
    @DisplayName("a peon and a peasant do not report in the same voice")
    void theRaceSplitHolds() {
        GameData data = gameData();
        GameAudio audio = new GameAudio(data.sounds());

        String human = audio.chosenPath(unit(data, "unit-peasant"), "work-complete", size -> 0);
        String orc = audio.chosenPath(unit(data, "unit-peon"), "work-complete", size -> 0);

        assertNotNull(human);
        assertNotNull(orc);
        assertNotEquals(human, orc,
                "work-complete is defined per race; both sides sharing one clip means "
                        + "the race was not being resolved");
        assertTrue(human.contains("human"), "expected a human path, got " + human);
        assertTrue(orc.contains("orc"), "expected an orc path, got " + orc);
    }

    @Test
    @DisplayName("a unit with its own sound keeps it rather than falling back")
    void anOwnSoundWins() {
        GameData data = gameData();
        GameAudio audio = new GameAudio(data.sounds());

        // The oil tanker is the one unit that declares work-complete itself.
        // The fallback must not override it, or the exception upstream took
        // care to allow would be flattened away.
        Unit tanker = unit(data, "unit-human-oil-tanker");
        String own = tanker.type().sounds().get("work-complete");
        Assumptions.assumeTrue(!isBlank(own),
                "the oil tanker no longer declares its own work-complete");

        String path = audio.chosenPath(tanker, "work-complete", size -> 0);
        assertNotNull(path);
        assertEquals(data.sounds().pathForName(own, 0), path,
                "a unit's own sound must win over the race fallback");
    }

    @Test
    @DisplayName("the fallback still draws from the selection stream")
    void theFallbackStillDraws() {
        GameData data = gameData();
        GameAudio audio = new GameAudio(data.sounds());
        Unit peasant = unit(data, "unit-peasant");

        // Determinism: choose() draws unconditionally, including when there is
        // nothing to play, because lockstep peers must consume the same random
        // sequence whether or not a given machine had the sound. The fallback
        // must not introduce a path that skips the draw.
        int[] draws = {0};
        audio.chosenPath(peasant, "work-complete", size -> {
            draws[0]++;
            return 0;
        });
        assertEquals(1, draws[0], "exactly one draw per resolution, fallback or not");

        draws[0] = 0;
        audio.chosenPath(peasant, "no-such-event", size -> {
            draws[0]++;
            return 0;
        });
        assertEquals(1, draws[0], "an event with no sound at all still draws");
    }

    private static Unit unit(GameData data, String ident) {
        UnitType type = data.unitTypes().types().get(ident);
        Assumptions.assumeTrue(type != null, ident + " is not in the parsed unit types");
        return new Unit(1, type, 0, 5, 5);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }
}
