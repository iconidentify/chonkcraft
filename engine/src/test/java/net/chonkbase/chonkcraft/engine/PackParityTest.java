package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import net.chonkbase.assetpack.codec.SignalToNoise;
import net.chonkbase.chonkcraft.data.GraphicsIndex;
import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.data.graphic.Palette;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.ArchiveIds;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.data.source.PackSource;
import org.junit.jupiter.api.Test;

/**
 * The game, loading the same data twice: once from the 1995 files and once
 * from an asset pack built out of them.
 *
 * <p>The extractor already proves that a pack's assets rebuild the archive
 * entries they came from. This proves the thing that actually matters, which
 * is a level up: that the <em>engine's own loader</em> reaches the same
 * pictures, palettes, sounds and maps either way. A pack could be a faithful
 * copy of every entry and still be wrong if the engine addressed it
 * differently, and this is the test that would catch that.
 *
 * <p>Needs a pack, which the test suite cannot build: the extractor is
 * deliberately invisible from here. Build one and point at it:
 *
 * <pre>
 *   scripts/build-asset-pack.sh --out /tmp/wc2.chonkpack
 *   mvn -pl engine test -Dchonkcraft.pack=/tmp/wc2.chonkpack \
 *       -Dwc2.install.dir=/path/to/Warcraft
 * </pre>
 */
class PackParityTest {

    /** How many of each kind to compare. Enough to be meaningful, quick enough to run. */
    private static final int SAMPLE = 40;

    private record Pair(GameData fromInstall, GameData fromPack,
            AssetSource install, AssetSource pack) {}

    private static Pair open() {
        String packPath = System.getProperty("chonkcraft.pack", System.getenv("CHONKCRAFT_ASSET_PACK"));
        assumeTrue(packPath != null && !packPath.isBlank(),
                "needs an asset pack: build one with scripts/build-asset-pack.sh and set"
                + " -Dchonkcraft.pack or CHONKCRAFT_ASSET_PACK");
        Path file = Paths.get(packPath);
        assumeTrue(Files.isRegularFile(file), "no asset pack at " + file);

        AssetSource install = InstallSource.fromEnvironment();
        assumeTrue(install != null,
                "needs a Warcraft II installation to compare against (-Dwc2.install.dir"
                + " or WC2_INSTALL_DIR)");

        AssetSource pack = PackSource.open(file);
        return new Pair(new GameData(install), new GameData(pack),
                install, pack);
    }

    @Test
    void theSameSpritesComeOutOfBoth() {
        Pair both = open();
        List<String> compared = new ArrayList<>();
        for (GraphicsIndex.Asset asset : both.fromInstall().graphics().assets()) {
            if (asset.kind() != GraphicsIndex.Kind.GFX && asset.kind() != GraphicsIndex.Kind.GFU) {
                continue;
            }
            if (compared.size() >= SAMPLE) {
                break;
            }
            IndexedImage fromInstall = both.fromInstall().sprite(asset.path());
            if (fromInstall == null) {
                continue;
            }
            IndexedImage fromPack = both.fromPack().sprite(asset.path());
            assertNotNull(fromPack, asset.path() + " is missing from the pack");
            assertEquals(fromInstall.width(), fromPack.width(), asset.path() + " width");
            assertEquals(fromInstall.height(), fromPack.height(), asset.path() + " height");
            assertArrayEquals(fromInstall.pixels(), fromPack.pixels(),
                    asset.path() + " draws different pixels out of the pack");
            compared.add(asset.path());
        }
        assertTrue(compared.size() >= 20,
                "expected to compare a real sample of sprites, compared " + compared.size());
    }

    @Test
    void theSamePalettesComeOutOfBoth() {
        Pair both = open();
        int compared = 0;
        for (GraphicsIndex.Asset asset : both.fromInstall().graphics().assets()) {
            if (asset.kind() != GraphicsIndex.Kind.GFX || compared >= SAMPLE) {
                continue;
            }
            Palette fromInstall = both.fromInstall().paletteFor(asset.path());
            Palette fromPack = both.fromPack().paletteFor(asset.path());
            if (fromInstall == null) {
                continue;
            }
            assertNotNull(fromPack, asset.path() + " has no palette in the pack");
            for (int index = 0; index < 256; index++) {
                assertEquals(fromInstall.rgb(index), fromPack.rgb(index),
                        asset.path() + " palette entry " + index
                        + " differs, which would shift every colour it draws");
            }
            compared++;
        }
        assertTrue(compared > 0, "no palettes were compared");
    }

    @Test
    void everyTilesetAssemblesTheSame() {
        Pair both = open();
        int compared = 0;
        for (PudMap.Tileset which : PudMap.Tileset.values()) {
            GameData.LoadedTileset fromInstall;
            try {
                fromInstall = both.fromInstall().loadTileset(which);
            } catch (RuntimeException e) {
                continue;
            }
            if (fromInstall == null) {
                continue;
            }
            GameData.LoadedTileset fromPack = both.fromPack().loadTileset(which);
            assertNotNull(fromPack, which + " is missing from the pack");
            assertEquals(fromInstall.sheet().width(), fromPack.sheet().width(),
                    which + " sheet width decides the tile numbering");
            assertArrayEquals(fromInstall.sheet().pixels(), fromPack.sheet().pixels(),
                    which + " terrain differs");
            for (int index = 0; index < 256; index++) {
                assertEquals(fromInstall.palette().rgb(index), fromPack.palette().rgb(index),
                        which + " palette entry " + index);
            }
            assertEquals(fromInstall.cycles(), fromPack.cycles(),
                    which + " animates its water in one and not the other");
            compared++;
        }
        assertTrue(compared >= 3, "expected at least three tilesets, compared " + compared);
    }

    @Test
    void theSameTextComesOutOfBoth() {
        Pair both = open();
        int compared = 0;
        for (GraphicsIndex.Asset asset : both.fromInstall().graphics().assets()) {
            if (asset.kind() != GraphicsIndex.Kind.TEXT || compared >= SAMPLE) {
                continue;
            }
            String fromInstall = both.fromInstall().text(asset.path());
            if (fromInstall == null || fromInstall.isEmpty()) {
                continue;
            }
            assertEquals(fromInstall, both.fromPack().text(asset.path()),
                    asset.path() + " reads differently out of the pack");
            compared++;
        }
        assertTrue(compared > 0, "no text was compared");
    }

    @Test
    void theSameMapsAreOfferedInTheSameOrder() {
        Pair both = open();
        assertEquals(both.install().mapNames(), both.pack().mapNames(),
                "the menu would list different maps, or the same ones in a different order");
        int compared = 0;
        for (String name : both.install().mapNames()) {
            assertArrayEquals(both.install().map(name), both.pack().map(name),
                    name + " differs, which desynchronises a multiplayer game");
            if (++compared >= SAMPLE) {
                break;
            }
        }
        assertTrue(compared > 0, "no maps were compared");
    }

    /**
     * The music, which is the one part of a pack that is allowed to differ.
     *
     * <p>Recorded music is stored as Opus, so "sample for sample" is not a
     * question that can be asked of it any more and this test does not pretend
     * otherwise. What is still exact, and asserted exactly, is everything
     * structural: the track list and its order, the sample rate, the channel
     * count and the length to within one frame. Those are what a bug in this
     * area actually breaks -- a stream served at the codec's 48 kHz instead of
     * the disc's 44,100 plays nine percent fast, and a window taken at the wrong
     * offset starts on the wrong bar -- and none of them is a matter of degree.
     *
     * <p>Only the samples themselves are compared loosely, against
     * {@code SignalToNoise.MUSIC_FLOOR_DB}, and that floor is far above what a
     * misaligned or wrongly-rated stream gives: a signal compared against a
     * shifted copy of itself has as much error power as signal, which is about
     * 0 dB whatever the codec did.
     */
    @Test
    void theSameMusicIsOfferedInTheSameOrder() {
        Pair both = open();
        List<AssetSource.MusicTrack> fromInstall = both.install().musicTracks();
        assumeTrue(!fromInstall.isEmpty(),
                "needs a Warcraft II disc image beside the installation for red book music");
        List<AssetSource.MusicTrack> fromPack = both.pack().musicTracks();
        assertEquals(fromInstall.size(), fromPack.size(),
                "the game asks for a track by its position, so the lists must agree");

        int compared = 0;
        double worst = Double.POSITIVE_INFINITY;
        for (int i = 0; i < fromInstall.size(); i++) {
            AssetSource.MusicTrack disc = fromInstall.get(i);
            AssetSource.MusicTrack packed = fromPack.get(i);
            assertEquals(disc.name(), packed.name(), "track " + i + " is a different recording");
            assertEquals(disc.sampleRate(), packed.sampleRate(), disc.name()
                    + " is offered at a different rate, so it would play at a different speed");
            assertEquals(disc.channels(), packed.channels(), disc.name() + " channel count");

            short[] a = both.install().musicSamples(i);
            short[] b = both.pack().musicSamples(i);
            int channels = Math.max(1, disc.channels());
            assertTrue(Math.abs(a.length - b.length) <= channels,
                    disc.name() + " is " + (a.length / channels) + " frames on the disc and "
                    + (b.length / channels) + " in the pack, which is more than the one frame"
                    + " a codec is allowed to round by");

            int common = Math.min(a.length, b.length);
            double db = SignalToNoise.db(
                    java.util.Arrays.copyOf(a, common), java.util.Arrays.copyOf(b, common));
            assertTrue(db >= SignalToNoise.MUSIC_FLOOR_DB, disc.name() + " came back at "
                    + SignalToNoise.describe(db) + ", under the "
                    + SignalToNoise.MUSIC_FLOOR_DB + " dB floor");
            worst = Math.min(worst, db);
            compared++;
        }
        assertEquals(fromInstall.size(), compared, "not every track was compared");
        assertTrue(compared > 0, "no music was compared");
        System.out.println("pack music against the disc: " + compared + " tracks, worst "
                + SignalToNoise.describe(worst));
    }

    @Test
    void theReleaseIsIdentifiedTheSameWay() {
        Pair both = open();
        // Three filesystem sniffs decide this on the raw path and none of them
        // survives into a zip, so the pack has to have written the answers
        // down. Getting hasExpansion wrong picks the stand-in rows out of the
        // conversion table and the human campaign's ending becomes the tail of
        // the sentence before it.
        assertEquals(both.install().hasExpansion(), both.pack().hasExpansion());
        assertEquals(both.install().isExpansionRelease(), both.pack().isExpansionRelease());
        assertEquals(both.install().campaignTextOffset(), both.pack().campaignTextOffset());
        assertEquals(both.fromInstall().hasExpansion(), both.fromPack().hasExpansion());
    }

    @Test
    void everyArchiveEntryRebuildsToTheSameThing() {
        Pair both = open();
        // The blunt one. Not a sample: every entry of every archive, compared
        // through whatever decoder the pack's own record says applies.
        int compared = 0;
        for (int id : ArchiveIds.ALL) {
            var fromInstall = both.install().archive(id);
            var fromPack = both.pack().archive(id);
            if (fromInstall == null) {
                continue;
            }
            assertNotNull(fromPack, "archive " + id + " is missing from the pack");
            assertEquals(fromInstall.entryCount(), fromPack.entryCount(),
                    "archive " + id + " entry count; the port reads the expansion flag off it");
            for (int entry = 0; entry < fromInstall.entryCount(); entry++) {
                assertEquals(fromInstall.isValid(entry), fromPack.isValid(entry),
                        "archive " + id + " entry " + entry + " validity");
                compared++;
            }
        }
        assertTrue(compared > 1000, "expected over a thousand entries, compared " + compared);
    }
}
