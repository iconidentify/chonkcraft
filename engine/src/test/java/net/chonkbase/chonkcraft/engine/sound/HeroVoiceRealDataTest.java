package net.chonkbase.chonkcraft.engine.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ten named heroes of Beyond the Dark Portal spoke with borrowed voices.
 *
 * <p>Alleria answered in the elven archer's lines, Danath in the ordinary
 * footman's, Khadgar in the mage's, Turalyon in the knight's, Dentarg in an
 * ogre's and Deathwing in a dragon's. Ninety recorded files -- nine for each
 * hero, their own acknowledgement, selection and annoyed lines -- sat in the
 * player's installation and the game never reached for one of them.
 *
 * <p>{@code scripts/sound.legacy-declaration} guards those definitions with {@code if
 * chonkcraft.expansion}, and on the false branch maps each hero onto the common
 * unit it resembles. That branch is right for Tides of Darkness, which has no
 * hero recordings. It ran on every release, because the flag was never set:
 * {@code GameData.sounds()} runs the sound scripts on an interpreter of its
 * own, and {@code applyExtractorConfig} -- which sets {@code chonkcraft.expansion}
 * from the release -- is reached only through the {@code Load} handler that
 * {@code installLoad} puts on the prelude's interpreter, when a script asks
 * for {@code scripts/wc2-config.legacy-declaration}. The sound loader installs a different
 * {@code Load}, and {@code sound.legacy-declaration} does not call {@code Load} at all, so
 * the configuration never arrived and {@code chonkcraft.expansion} read nil.
 *
 * <p>Nothing was unbound, which is why this survived. Every hero name resolved
 * to a real file that decoded to audible samples, so the sound tests passed:
 * they were the wrong unit's samples. The measurement below is where the file
 * lives in the installation, because a hero's own voice is a fact about the
 * player's data rather than about which name the script happened to bind.
 */
class HeroVoiceRealDataTest {

    /**
     * Each hero, by the unit type the player selects, and the directory in the
     * installation holding the recordings made for that hero.
     *
     * <p>The type idents are ChonkCraft's and read strangely -- Alleria is
     * {@code unit-female-hero} and Deathwing {@code unit-fire-breeze} -- but
     * they are what the campaign maps place, so they are what the player
     * clicks on.
     */
    private static final Map<String, String> HEROES = new LinkedHashMap<>();

    static {
        HEROES.put("unit-female-hero", "human/units/alleria/");
        HEROES.put("unit-arthor-literios", "human/units/danath/");
        HEROES.put("unit-white-mage", "human/units/khadgar/");
        HEROES.put("unit-knight-rider", "human/units/turalyon/");
        HEROES.put("unit-flying-angel", "human/units/kurdan/");
        HEROES.put("unit-fire-breeze", "orc/units/deathwing/");
        HEROES.put("unit-fad-man", "orc/units/dentarg/");
        HEROES.put("unit-beast-cry", "orc/units/grom_hellscream/");
        HEROES.put("unit-quick-blade", "orc/units/korgath_bladefist/");
        HEROES.put("unit-evil-knight", "orc/units/teron_gorefiend/");
    }

    private static GameData load() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set -Dchonkcraft.pack=... or wc2.install.dir");
        Assumptions.assumeTrue(assets.isExpansionRelease(),
                "this release is Tides of Darkness, which has no hero recordings");
        return new GameData(assets);
    }

    @Test
    @DisplayName("selecting Alleria plays Alleria and not the elven archer")
    void aSelectedHeroAnswersInItsOwnVoice() {
        GameData data = load();
        SoundBank bank = data.sounds();
        Assumptions.assumeTrue(bank.isAvailable(), "no sound archives in this release");
        Map<String, UnitType> types = data.unitTypes().types();

        List<String> borrowed = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, String> hero : HEROES.entrySet()) {
            UnitType type = types.get(hero.getKey());
            if (type == null) {
                continue;
            }
            String name = type.sounds().get("selected");
            if (name == null) {
                continue;
            }
            checked++;
            String file = bank.pathForSelection(name, false, 0);
            if (file == null || !file.startsWith(hero.getValue())) {
                borrowed.add(hero.getKey() + " selected plays " + file
                        + ", not a line from " + hero.getValue());
            }
        }

        assertEquals(HEROES.size(), checked,
                "the expansion places ten named heroes and every one of them "
                        + "has a selection line; found " + checked);
        assertTrue(borrowed.isEmpty(),
                "these heroes answer in another unit's voice: " + borrowed);
    }

    @Test
    @DisplayName("a hero ordered somewhere acknowledges in its own voice")
    void anOrderedHeroAcknowledgesInItsOwnVoice() {
        GameData data = load();
        SoundBank bank = data.sounds();
        Assumptions.assumeTrue(bank.isAvailable(), "no sound archives in this release");
        Map<String, UnitType> types = data.unitTypes().types();

        List<String> borrowed = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, String> hero : HEROES.entrySet()) {
            UnitType type = types.get(hero.getKey());
            if (type == null) {
                continue;
            }
            String name = type.sounds().get("acknowledge");
            if (name == null) {
                continue;
            }
            checked++;
            List<String> files = bank.pathsFor(name);
            if (files.isEmpty()) {
                borrowed.add(hero.getKey() + " acknowledges with no file at all");
                continue;
            }
            for (String file : files) {
                if (!file.startsWith(hero.getValue())) {
                    borrowed.add(hero.getKey() + " acknowledges with " + file
                            + ", not a line from " + hero.getValue());
                }
            }
        }

        assertEquals(HEROES.size(), checked,
                "every one of the ten heroes answers an order; found " + checked);
        assertTrue(borrowed.isEmpty(),
                "these heroes answer in another unit's voice: " + borrowed);
    }

    /**
     * The critter of the Dark Portal's tilesets, which had the same fault for
     * the same reason and is the cheapest witness that the flag is the cause.
     */
    @Test
    @DisplayName("the warthog squeals as a warthog rather than as a pig")
    void theWarthogHasItsOwnSqueal() {
        SoundBank bank = load().sounds();
        Assumptions.assumeTrue(bank.isAvailable(), "no sound archives in this release");

        assertEquals("neutral/units/warthog/selected/1.wav",
                bank.pathForName("warthog selected", 0),
                "clicking a warthog should play the warthog recording");
        assertEquals("neutral/units/warthog/annoyed/1.wav",
                bank.pathForName("warthog annoyed", 0),
                "pestering a warthog should play the warthog recording");
    }
}
