package net.chonkbase.chonkcraft.engine.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.chonkbase.runtime.audio.PcmClip;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The game's sounds are complete native definitions backed by authenticated
 * game assets. This asks for the bank in player-visible terms: how many sounds
 * the game knows, whether a footman answers in a human voice, and whether the
 * bytes behind that answer become audible samples.
 *
 * <p>Asking whether the table loaded would pass against every fault this
 * repository has had. The recurring one here is a field parsed, given an
 * accessor, documented, and read by nothing, so the question has to be what
 * came out of the speakers.
 */
class SoundWithoutScriptsRealDataTest {

    private static GameData withoutScripts() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set -Dchonkcraft.pack=... or wc2.install.dir");
        return new GameData(assets);
    }

    @Test
    @DisplayName("the game knows all its sounds with no script checkout at all")
    void theSoundsSurviveWithoutTheScripts() {
        SoundBank bank = withoutScripts().sounds();
        Assumptions.assumeTrue(bank.isAvailable(), "no sound archives in this release");

        assertEquals(371, bank.definedCount(),
                "the shipped table names 371 sounds and the game found "
                        + bank.definedCount() + " without the scripts");

        List<String> empty = new ArrayList<>();
        for (String name : bank.names()) {
            if (bank.pathsFor(name).isEmpty()) {
                empty.add(name);
            }
        }
        assertTrue(empty.isEmpty(),
                "these sounds have a name and no file behind it: " + empty);
    }

    @Test
    @DisplayName("a footman clicked without any scripts installed still answers")
    void aFootmanStillAnswers() {
        SoundBank bank = withoutScripts().sounds();
        Assumptions.assumeTrue(bank.isAvailable(), "no sound archives in this release");

        // Six lines to answer with, and the seventh through thirteenth are the
        // annoyed ones, which a single click must never reach.
        assertEquals(6, bank.groupSize("footman-selected"),
                "a footman answers a click with one of six recorded lines");

        List<String> heard = new ArrayList<>();
        for (int draw = 0; draw < bank.groupSize("footman-selected"); draw++) {
            String file = bank.pathForSelection("footman-selected", false, draw);
            heard.add(file);
            PcmClip clip = bank.clip(file);
            assertTrue(clip != null && clip.frameCount() > 0,
                    "clicking a footman reached " + file + ", which is not audible");
        }
        assertEquals(6, List.copyOf(new java.util.LinkedHashSet<>(heard)).size(),
                "the six draws should be six different lines, and were " + heard);
        assertFalse(heard.stream().anyMatch(file -> file.contains("annoyed")),
                "a single click reached an annoyed line: " + heard);
    }

    @Test
    @DisplayName("the interface click and a peasant reporting work are still there")
    void theGameEventsSurviveToo() {
        SoundBank bank = withoutScripts().sounds();
        Assumptions.assumeTrue(bank.isAvailable(), "no sound archives in this release");

        for (String event : List.of("click", "work-complete-human",
                "work-complete-orc", "placement-error-human", "rescue-human")) {
            String file = bank.pathForName(event, 0);
            PcmClip clip = bank.clip(file);
            assertTrue(clip != null && clip.frameCount() > 0,
                    event + " reached " + file + ", which is not audible");
        }
    }
}
