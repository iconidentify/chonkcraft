package net.chonkbase.chonkcraft.engine.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.chonkbase.runtime.audio.PcmClip;
import net.chonkbase.runtime.audio.PcmFormat;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Decodes the real sound bank out of a Warcraft II installation. */
class SoundRealDataTest {

    private static GameData gameData() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II asset pack/install configured");
        return new GameData(assets);
    }

    @Test
    @DisplayName("the native sound table binds the game's named sounds")
    void theNativeSoundTableBindsNamedSounds() {
        SoundBank bank = gameData().sounds();
        assertTrue(bank.isAvailable(), "no sound archive found");
        assertTrue(bank.definedCount() > 150,
                "expected the full native table, got " + bank.definedCount() + " names");
    }

    @Test
    @DisplayName("every named sound decodes to 48 kHz PCM")
    void everyNamedSoundDecodes() {
        SoundBank bank = gameData().sounds();

        int decoded = 0;
        List<String> failed = new ArrayList<>();
        for (String name : List.of("click", "explosion", "sword attack",
                "basic human voices ready", "basic human voices dead",
                "footman-selected", "peasant-selected", "grunt-selected")) {
            PcmClip clip = bank.clipForName(name, 0);
            if (clip == null) {
                failed.add(name);
                continue;
            }
            decoded++;
            // The mixer only takes its own rate, so every clip must arrive
            // converted. Warcraft II authored these at 11 or 22 kHz.
            assertEquals(PcmFormat.GAME_SAMPLE_RATE, clip.sampleRate(), name);
            assertTrue(clip.frameCount() > 0, name + " decoded to no audio");
            assertTrue(clip.channels() == 1 || clip.channels() == 2, name);
        }
        assertEquals(List.of(), failed, "these sounds did not decode");
        assertEquals(8, decoded);
    }

    @Test
    @DisplayName("unit sound events resolve to real audio")
    void unitSoundEventsResolve() {
        GameData data = gameData();
        SoundBank bank = data.sounds();
        UnitType footman = data.unitTypes().types().get("unit-footman");
        Assumptions.assumeTrue(footman != null, "unit-footman not defined");

        // A unit's Sounds table names events; each must reach a real clip.
        assertTrue(!footman.sounds().isEmpty(), "the footman declares no sounds");
        for (var entry : footman.sounds().entrySet()) {
            PcmClip clip = bank.clipForName(entry.getValue(), 0);
            assertTrue(clip != null,
                    "footman event '" + entry.getKey() + "' names '" + entry.getValue()
                            + "', which does not resolve");
        }
    }

    @Test
    @DisplayName("a sound group returns different lines for different picks")
    void aSoundGroupVariesItsLines() {
        SoundBank bank = gameData().sounds();
        List<String> paths = bank.pathsFor("footman-selected");
        Assumptions.assumeTrue(paths.size() > 1, "this release has a single-line group");

        // The point of a group is that a squad does not answer in unison.
        PcmClip first = bank.clipForName("footman-selected", 0);
        PcmClip second = bank.clipForName("footman-selected", 1);
        assertTrue(first != null && second != null);
        assertTrue(first != second, "two picks returned the same clip");
    }

    @Test
    @DisplayName("the only sounds that fail are expansion voices")
    void onlyExpansionVoicesFail() {
        SoundBank bank = gameData().sounds();
        // Decode representative names from the native table.
        for (String name : List.of("click", "explosion", "sword attack")) {
            bank.clipForName(name, 0);
        }
        // Anything that failed should be a Beyond the Dark Portal hero, whose
        // voice files a Tides of Darkness installation does not contain.
        for (String path : bank.failures().keySet()) {
            assertTrue(path.contains("alleria") || path.contains("teron")
                            || path.contains("kurdan") || path.contains("danath")
                            || path.contains("khadgar") || path.contains("grom")
                            || path.contains("dentarg") || path.contains("korgath")
                            || path.contains("deathwing") || path.contains("turalyon")
                            || path.contains("cho-gall"),
                    "unexpected sound failure: " + path + " = " + bank.failures().get(path));
        }
    }
}
