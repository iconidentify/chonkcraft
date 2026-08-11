package net.chonkbase.chonkcraft.engine.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import net.chonkbase.runtime.audio.PcmClip;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.sound.SoundBank;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The noises the game makes rather than the ones a unit makes.
 *
 * <p>{@code DefineGameSounds} was bound to a function that returned its first
 * argument and did nothing, so seven game events had no sound: work complete,
 * research complete, the two placement answers, a building going up, a
 * transport docking and a prisoner freed. The files were all there, sitting in
 * the bank under the names {@code MakeSound} gave them, and nothing pointed at
 * them.
 *
 * <p>Each name here is resolved and then decoded. Asking whether a name is
 * bound is the test that would have passed against every fault in this
 * repository; asking whether the bytes behind it turn into audible samples is
 * the thing the player is owed.
 *
 * <p>And it is still not the whole of it, which this used to claim in its own
 * display name -- "every game event the scripts declare reaches a sound that
 * plays". It does not check that. Two of the fifteen names below,
 * {@code building-construction-human} and {@code transport-docking}, decode
 * perfectly and are played by nothing at all, and this passed throughout: a
 * player clicking a half-built farm hears the finished farm's selection line.
 * Whether a bound name can be reached from a play call is a question about
 * code paths rather than about bytes, and it is answered by check 5 of
 * {@code scripts/audit-gaps.py}, not here.
 */
class GameSoundsRealDataTest {

    /** The events, with the race that qualifies each, as upstream keys them. */
    private static final List<String> RACED = List.of(
            "work-complete", "research-complete", "placement-error",
            "placement-success", "building-construction", "rescue");

    /** The events upstream keeps raceless. */
    private static final List<String> PLAIN = List.of(
            "click", "transport-docking", "chat-message");

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    @Test
    @DisplayName("every game event the scripts declare decodes to audible samples")
    void theGameEventsAreAudible() {
        SoundBank bank = load().sounds();
        Assumptions.assumeTrue(bank.isAvailable(), "no sound archives in this release");

        int checked = 0;
        for (String event : RACED) {
            for (String race : List.of("human", "orc")) {
                checked += audible(bank, event + "-" + race);
            }
        }
        for (String event : PLAIN) {
            checked += audible(bank, event);
        }
        // Counted, not merely walked: a sweep that finds nothing declares
        // everything sound.
        assertEquals(15, checked, "fewer game-event sounds than the scripts declare");
    }

    /** Resolves a name, decodes it, and insists the result is something you can hear. */
    private static int audible(SoundBank bank, String event) {
        List<String> paths = bank.pathsFor(event);
        assertTrue(!paths.isEmpty(),
                event + " resolves to no file: DefineGameSounds bound it to nothing");
        PcmClip clip = bank.clipForName(event, 0);
        assertNotNull(clip, event + " names " + paths + ", which does not decode");
        assertTrue(clip.frameCount() > 0, event + " decodes to silence");
        return 1;
    }

    @Test
    @DisplayName("the human and orc peasants say different things when work is done")
    void theRacesAreKeptApart() {
        SoundBank bank = load().sounds();
        Assumptions.assumeTrue(bank.isAvailable(), "no sound archives in this release");

        // The fixture has to be able to tell the rules apart. Binding one race
        // for both would pass a test that only asked whether the name
        // resolved, and a human town would answer in orcish.
        assertEquals(List.of("human/units/peasant/work_complete.wav"),
                bank.pathsFor("work-complete-human"),
                "the human report is the peasant's own, not the generic voice");
        assertEquals(List.of("orc/basic_voices/work_complete.wav"),
                bank.pathsFor("work-complete-orc"),
                "the orc report comes from the basic orc voices");
        assertEquals(List.of("human/capture.wav"), bank.pathsFor("rescue-human"));
        assertEquals(List.of("orc/capture.wav"), bank.pathsFor("rescue-orc"));
    }

}
