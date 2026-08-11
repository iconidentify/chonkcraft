package net.chonkbase.chonkcraft.engine.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.chonkbase.runtime.audio.PcmClip;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which unit answers to which sound, with no retired scripting language of anybody's in the room.
 *
 * <p>{@code SoundWithoutScriptsRealDataTest} proved the sound table stands on
 * its own. It did not prove the game could use it: the table is keyed on names
 * like {@code footman-selected}, and until now the only thing that said a
 * footman is what answers to that name was a {@code Sounds = {...}} block in
 * somebody else's {@code scripts/human/units.legacy-declaration}. A shipped table keyed on
 * names only a checkout supplies has not left the checkout.
 *
 * <p>So both halves are read here and held to each other -- the units'
 * {@code unit-sounds.tsv} against the bank {@code sound-bindings.tsv} builds --
 * and then the question is asked the way a player would ask it: click a
 * footman and see whether six different audible lines come back, kill a peasant
 * and see whether it dies in a human voice rather than in an explosion.
 *
 * <p>Neither table is asked whether it loaded. Every fault of the shape this
 * repository keeps producing would pass that question.
 */
class UnitVoicesWithoutScriptsRealDataTest {

    /** A script root that is not there, which is the whole point. */
    private static final Path NO_CHECKOUT =
            Paths.get("/nonexistent/there-is-no-chonkcraft-checkout-here");

    private static GameData withoutScripts() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set -Dchonkcraft.pack=... or wc2.install.dir");
        return new GameData(assets);
    }

    @Test
    @DisplayName("every voice the units call for is one the game can play")
    void theTwoShippedTablesAgreeWithoutAnyScripts() {
        SoundBank bank = withoutScripts().sounds();
        Assumptions.assumeTrue(bank.isAvailable(), "no sound archives in this release");

        Map<String, Map<String, String>> units = UnitSounds.byUnitType();
        assertEquals(107, units.size(),
                "the shipped table gives 107 units a voice; this run read "
                        + units.size());

        Set<String> named = new LinkedHashSet<>();
        int bindings = 0;
        for (Map<String, String> events : units.values()) {
            named.addAll(events.values());
            bindings += events.size();
        }
        assertEquals(398, bindings,
                "the shipped table holds 398 event bindings; this run read " + bindings);
        assertEquals(185, named.size(),
                "those bindings name 185 distinct sounds; this run read " + named.size());

        // The critter is the one name that is deliberately unbound here: its
        // voice is a fact about the ground it stands on, and scripts.legacy-declaration binds
        // it only once a map has said which tileset it is. Everything else has
        // to reach a file, or a unit is silent in the game and nothing says so.
        List<String> unplayable = new ArrayList<>();
        for (String name : named) {
            if (bank.pathsFor(name).isEmpty() && bank.selection(name) == null) {
                unplayable.add(name);
            }
        }
        assertEquals(List.of("critter-selected"), unplayable,
                "units call for sounds the shipped bank has no file for: " + unplayable);
    }

    @Test
    @DisplayName("a footman clicked with no scripts installed answers in a footman's voice")
    void aFootmanAnswersToItsOwnName() {
        SoundBank bank = withoutScripts().sounds();
        Assumptions.assumeTrue(bank.isAvailable(), "no sound archives in this release");

        Map<String, String> footman = UnitSounds.byUnitType().get("unit-footman");
        assertEquals("footman-selected", footman.get("selected"),
                "a clicked footman should answer to its own selection sound");

        List<String> heard = new ArrayList<>();
        String name = footman.get("selected");
        for (int draw = 0; draw < bank.groupSize(name); draw++) {
            String file = bank.pathForSelection(name, false, draw);
            heard.add(file);
            PcmClip clip = bank.clip(file);
            assertTrue(clip != null && clip.frameCount() > 0,
                    "clicking a footman reached " + file + ", which is not audible");
        }
        assertEquals(6, heard.size(),
                "a footman answers a click with one of six recorded lines, not "
                        + heard.size());
        assertEquals(6, new LinkedHashSet<>(heard).size(),
                "the six draws should be six different lines, and were " + heard);
        assertFalse(heard.stream().anyMatch(file -> file.contains("annoyed")),
                "a single click reached an annoyed line: " + heard);
    }

    @Test
    @DisplayName("a dying peasant is heard to die and not to detonate")
    void unitsDieInTheirOwnVoices() {
        SoundBank bank = withoutScripts().sounds();
        Assumptions.assumeTrue(bank.isAvailable(), "no sound archives in this release");

        Map<String, Map<String, String>> units = UnitSounds.byUnitType();
        for (String ident : List.of("unit-peasant", "unit-footman", "unit-archer")) {
            String dying = units.get(ident).get("dead");
            String file = bank.pathForName(dying, 0);
            PcmClip clip = bank.clip(file);
            assertTrue(clip != null && clip.frameCount() > 0,
                    ident + " dies to " + file + ", which is not audible");
            // The critter used to die to this, and it is what a wrong or
            // missing binding falls back to.
            assertNotEquals("misc/explosion.wav", file,
                    ident + " blew up instead of dying");
        }

        // The one work-complete binding in the whole roster, and it is on a
        // boat. human/units.legacy-declaration:549 says why: "the oil tankers do not use the
        // nasal 'work's done' peasant sound for completing buildings". A
        // transcription that tidied this away would sound wrong in exactly one
        // place and nobody would find it.
        assertEquals("basic human voices research complete",
                units.get("unit-human-oil-tanker").get("work-complete"),
                "an oil tanker reports a finished refinery in its own voice, not"
                        + " the peasant's");
    }
}
