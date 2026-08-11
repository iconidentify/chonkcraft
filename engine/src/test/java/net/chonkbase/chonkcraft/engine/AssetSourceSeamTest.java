package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import net.chonkbase.chonkcraft.data.graphic.Palette;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.sound.SoundBank;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The game reads the same bytes now that an {@code AssetSource} sits between it
 * and the disc.
 *
 * <p>{@code GameData} used to hold an installation and open {@code .war} files
 * itself, and for a while it kept a second constructor that took one and
 * wrapped it. Three checks used to live here comparing the two constructions
 * asset for asset. Both the constructor and those three are gone: with one
 * construction left they would have been comparing it to itself, which is not
 * a measurement. What still needs proving is that the source the engine now
 * holds reaches the same data a pack does, and that is
 * {@code PackParityTest}'s subject, sprite for sprite and entry for entry.
 *
 * <p>What is left here is the part of the seam a parity test cannot reach,
 * because it is about a source that is <em>missing</em> something.
 *
 * <p>The awkward part of the seam is {@code archiveFor}. When a release does
 * not have an archive, it does not fail and it does not answer nothing: it
 * hands back {@code maindat.war} and the caller reads maindat's entry of the
 * number it wanted, which is a sprite sheet where a video was asked for. That
 * is what ships, it is what the campaign and credits screens are written
 * against, and it survived the move. Two tests below pin it, one showing the
 * fallback happening and one showing the same path answering something else
 * entirely when the archive is there, so that the first is not passing on
 * nothing.
 */
class AssetSourceSeamTest {

    /**
     * A campaign speech file. It lives in {@code snddat.war}, archive 2000,
     * which is the archive a hard-disk DOS install without its CD does not
     * have.
     */
    private static final String IN_SNDDAT = "../campaigns/human/victory";

    private static InstallSource install() {
        InstallSource source = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(source != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return source;
    }

    /**
     * The same installation, one archive short.
     *
     * <p>Not a stand-in: the DOS release installs its archives into a
     * {@code DATA} directory and leaves {@code snddat.war} and
     * {@code muddat.cud} on the CD, so that directory is itself a real, if
     * partial, installation, and it is exactly the shape a player who copied
     * the game folder off the disc ends up with. Every archive on the full
     * install is present -- measured, all six -- so a genuinely missing one
     * cannot be had any other way.
     */
    private static GameData missingSnddat(InstallSource full) {
        InstallSource partial = InstallSource.tryAt(full.root().resolve("DATA"));
        Assumptions.assumeTrue(partial != null,
                "this installation keeps its archives outside a DATA directory");
        Assumptions.assumeTrue(!partial.hasArchive(2000),
                "this installation has snddat.war beside maindat.war, so nothing is missing");
        return new GameData(partial);
    }

    private static byte[] flatten(Palette palette) {
        byte[] out = new byte[256 * 3];
        for (int i = 0; i < 256; i++) {
            out[i * 3] = (byte) palette.red(i);
            out[i * 3 + 1] = (byte) palette.green(i);
            out[i * 3 + 2] = (byte) palette.blue(i);
        }
        return out;
    }

    @Test
    @DisplayName("an archive the release does not have reads maindat's entry of that number")
    void aMissingArchiveFallsBackToMaindat() {
        InstallSource install = install();
        GameData lean = missingSnddat(install);

        // Nothing here is a sound lookup: paletteFor goes through archiveFor,
        // and archive 2000 is not there, so the fallback is the only thing that
        // can answer.
        Palette fallen = lean.paletteFor(IN_SNDDAT);
        assertNotNull(fallen, "a missing archive answered nothing instead of falling back");
        assertArrayEquals(flatten(Palette.fromVga(lean.mainArchive().entry(2))), flatten(fallen),
                "the fallback read something other than maindat entry 2");

        // The same fallback reached from the other kind that lives outside
        // maindat. muddat.cud is not there either, so this reads maindat's
        // entry eleven, decides it is not a Smacker file, and answers nothing
        // -- a campaign that plays no video rather than one that will not
        // start.
        assertFalse(lean.source().hasArchive(6000), "muddat.cud is present, so this proves nothing");
        assertNull(lean.video("videos/human-1"),
                "a video out of a missing archive should degrade to nothing, not to a picture");
    }

    /**
     * The control for the test above. The fallback is only meaningful if the
     * same path answers something different when the archive is really there;
     * without this, "maindat entry 2" could be the right answer arrived at
     * honestly rather than the wrong one arrived at by falling back.
     *
     * <p>It answers loudly. Entry 2 of {@code snddat.war} is a 796,022-byte
     * wave file and {@code Palette.fromVga} wants 768 bytes, so the full
     * install throws where the partial one hands back the font palette.
     */
    @Test
    @DisplayName("the same lookup with the archive present reads something else entirely")
    void theMeasurementDistinguishesTheFallbackFromTheRealThing() {
        InstallSource install = install();
        GameData full = new GameData(install);
        Assumptions.assumeTrue(full.source().hasArchive(2000),
                "this installation has no snddat.war at all, so there is nothing to contrast");

        assertThrows(IllegalArgumentException.class, () -> full.paletteFor(IN_SNDDAT),
                "snddat entry 2 read as a palette: the fallback test is measuring nothing");
    }

    @Test
    @DisplayName("the sound bank keeps its group sizes through the seam")
    void theSoundBankKeepsItsGroupSizes() {
        SoundBank bank = new GameData(install()).sounds();

        assertTrue(bank.isAvailable(), "no sound archive reached the bank through the source");
        // Counted first: every size below would pass on an empty bank, because
        // groupSize never answers zero.
        assertTrue(bank.definedCount() > 150,
                "only " + bank.definedCount() + " names bound, so the script did not run");

        // Measured against the real 1995 data before the seam went in. These
        // are the numbers a caller draws its synchronised random from, so a
        // change here is a change to which voice line plays.
        assertEquals(3, bank.groupSize("sword attack"), "sword attack");
        assertEquals(4, bank.groupSize("tree-chopping"), "tree-chopping");
        assertEquals(3, bank.groupSize("building destroyed"), "building destroyed");
        assertEquals(6, bank.groupSize("footman-selected"), "footman-selected");
        assertEquals(4, bank.groupSize("peasant-selected"), "peasant-selected");
        assertEquals(6, bank.groupSize("grunt-selected"), "grunt-selected");
        assertEquals(7, bank.annoyedSize("footman-selected"), "footman-selected, annoyed");

        // And the bytes behind a name still arrive, which is the half a size
        // cannot tell you.
        for (String name : List.of("sword attack", "footman-selected", "grunt-selected")) {
            assertNotNull(bank.clipForName(name, 0), name + " reached no audio through the source");
        }
        assertEquals(List.of(), List.copyOf(bank.failures().keySet()),
                "the source handed the bank archives it could not read");
    }

    @Test
    @DisplayName("a release without snddat records the missing archive instead of reading maindat")
    void theSoundBankRefusesTheArchiveItWasNotGiven() {
        InstallSource install = install();
        GameData lean = missingSnddat(install);
        SoundBank bank = lean.sounds();

        // This is the one caller that must not take archiveFor's fallback. A
        // bank handed the main archive for id 2000 would read maindat entry
        // two, hand a 768-byte palette to the wave decoder, and either throw or
        // play a palette. It is given only the archives the release has, so it
        // records the miss and answers nothing.
        assertNull(bank.clip(IN_SNDDAT), "a sound out of a missing archive came back as audio");
        assertEquals("archive 2000 is not in this release", bank.failures().get(IN_SNDDAT),
                "the bank did not record why the campaign speech was silent");

        // The archives it does have still work, so this is not a bank that
        // failed at everything.
        assertNotNull(bank.clipForName("sword attack", 0),
                "sfxdat is present and its sounds stopped decoding");
    }
}
