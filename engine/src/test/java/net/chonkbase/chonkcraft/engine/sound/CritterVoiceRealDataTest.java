package net.chonkbase.chonkcraft.engine.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.runtime.audio.PcmClip;
import net.chonkbase.chonkcraft.data.GraphicsIndex;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Clicking a sheep played nothing, and a sheep that died blew up.
 *
 * <p>The critter is the only unit in Warcraft II whose voice depends on the
 * ground it stands on: it is a sheep in summer, a seal in winter, a pig in
 * wasteland and a warthog in swamp. {@code scripts/scripts.legacy-declaration:54} points
 * {@code critter-selected} and {@code critter-dead} at that tileset's animal,
 * after the map has said which tileset it uses.
 *
 * <p>This implementation never ran that file. Its sound loader read
 * {@code scripts/sound.legacy-declaration} and nothing else, so both names kept what
 * {@code sound.legacy-declaration} leaves them as before {@code scripts.legacy-declaration} overrides them:
 * {@code critter-selected} was never bound at all, and {@code critter-dead}
 * was bound to {@code explosion}. Measured on the Battle.net pack,
 * {@code critter-selected} resolved to zero files and {@code critter-dead} to
 * {@code misc/explosion.wav}.
 *
 * <p>Older than it looks. This was equally true when the game still ran the
 * retired scripting language, and the shipped sound table reproduced it exactly -- which is how it
 * surfaced, because the table has 371 names where the audit's reading of the
 * scripts has 372, and the one name in the gap was {@code critter-selected}.
 *
 * <p>The measurement is which file the game reaches for on a map of each
 * tileset, because which animal a critter sounds like is a fact about the
 * ground rather than about the binding.
 */
class CritterVoiceRealDataTest {

    /**
     * The bank the game would have on a map of this tileset, built with a
     * native table so the answer is independent of any external source tree.
     */
    private static SoundBank bankFor(String tileset) {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set -Dchonkcraft.pack=... or wc2.install.dir");
        SoundBank bank = new net.chonkbase.chonkcraft.engine.GameData(assets).sounds();
        SoundBindings.installForTileset(bank,
                GraphicsIndex.load(assets.hasExpansion()), tileset);
        return bank;
    }

    @Test
    @DisplayName("a critter answers as the animal its tileset makes it")
    void aCritterSoundsLikeItsGround() {
        record Ground(String tileset, String animal) {}
        List<Ground> grounds = List.of(
                new Ground("summer", "neutral/units/sheep/"),
                new Ground("winter", "neutral/units/seal/"),
                new Ground("wasteland", "neutral/units/pig/"),
                new Ground("swamp", "neutral/units/warthog/"));

        for (Ground ground : grounds) {
            SoundBank bank = bankFor(ground.tileset());
            String file = bank.pathForSelection("critter-selected", false, 0);
            assertTrue(file != null && file.startsWith(ground.animal()),
                    "on " + ground.tileset() + " a clicked critter played " + file
                            + " rather than a line from " + ground.animal());
        }
    }

    @Test
    @DisplayName("a critter that dies does not explode")
    void aDyingCritterDoesNotExplode() {
        SoundBank bank = bankFor("summer");

        String file = bank.pathForName("critter-dead", 0);
        assertNotEquals("misc/explosion.wav", file,
                "a dying sheep detonated, which is sound.legacy-declaration's placeholder"
                        + " showing through because scripts.legacy-declaration never ran");
        assertTrue(file != null && file.startsWith("neutral/units/sheep/"),
                "a sheep dying should be a sheep, and was " + file);
    }

    @Test
    @DisplayName("clicking a critter is audible at all")
    void aClickedCritterIsAudible() {
        SoundBank bank = bankFor("summer");
        Assumptions.assumeTrue(bank.isAvailable(), "no sound archives in this release");

        assertEquals(1, bank.groupSize("critter-selected"),
                "the sheep has one recorded line to answer a click with");
        String file = bank.pathForSelection("critter-selected", false, 0);
        PcmClip clip = bank.clip(file);
        assertTrue(clip != null && clip.frameCount() > 0,
                "clicking a critter reached " + file + ", which is not audible");
    }
}
