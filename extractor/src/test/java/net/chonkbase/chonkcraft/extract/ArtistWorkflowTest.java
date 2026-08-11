package net.chonkbase.chonkcraft.extract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import net.chonkbase.assetpack.AssetKind;
import net.chonkbase.assetpack.AssetPack;
import net.chonkbase.assetpack.PackAsset;
import net.chonkbase.assetpack.codec.IndexedPng;
import net.chonkbase.chonkcraft.data.graphic.GraphicDecoder;
import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.data.source.ArchiveIds;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.data.source.EntryArchive;
import net.chonkbase.chonkcraft.data.source.EntryCodec;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The thing the format exists for: an artist opens a picture, repaints it, and
 * the game draws what they painted.
 *
 * <p>Everything else about the pack is a compression story. This is the one
 * that changes what the project can do, and it is the one most likely to be
 * quietly broken, because it fails in a direction nothing else looks at: the
 * pack would still load, the game would still run, and it would still be
 * drawing the 1995 art.
 *
 * <p>The edit here is a paint program's edit -- decode the PNG, change pixels,
 * encode a PNG, put the file back in the zip -- and nothing in the pack is told
 * that it happened. What the test then reads is what the engine reads: the
 * rebuilt archive entry, through the game's own sprite decoder.
 */
class ArtistWorkflowTest {

    /** The colour painted in, chosen so it cannot be mistaken for the original. */
    private static final int NEW_COLOUR = 42;

    private static InstallSource install() {
        String configured = System.getProperty("wc2.install.dir");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("WC2_INSTALL_DIR");
        }
        assumeTrue(configured != null && !configured.isBlank(),
                "needs a Warcraft II installation (-Dwc2.install.dir or WC2_INSTALL_DIR)");
        InstallSource source = InstallSource.tryAt(Paths.get(configured));
        assumeTrue(source != null, "no Warcraft II data under " + configured);
        return source;
    }

    /**
     * The installation with everything outside the graphics archives hidden.
     *
     * <p>Ninety minutes of red book audio is nine tenths of a pack and none of
     * what this test is about. Encoding it would put four hundred megabytes in
     * a temporary directory and a minute on the suite, twice. Battle.net
     * Edition also keeps 140 MB of movies, 115 MB of sampled sound and 153
     * multiplayer maps outside those archives; copying all of those three
     * times cannot make a pixel-editing assertion any stronger.
     */
    private record GraphicsOnly(AssetSource wrapped) implements AssetSource {

        @Override
        public String describe() {
            return wrapped.describe();
        }

        @Override
        public EntryArchive archive(int archiveId) {
            return archiveId == ArchiveIds.MAINDAT
                            || archiveId == ArchiveIds.REZDAT
                            || archiveId == ArchiveIds.STRDAT
                    ? wrapped.archive(archiveId)
                    : null;
        }

        @Override
        public boolean hasExpansion() {
            return wrapped.hasExpansion();
        }

        @Override
        public boolean isExpansionRelease() {
            return wrapped.isExpansionRelease();
        }

        @Override
        public boolean isBattleNetEdition() {
            return wrapped.isBattleNetEdition();
        }

        @Override
        public int campaignTextOffset() {
            return wrapped.campaignTextOffset();
        }

        @Override
        public List<String> mapNames() {
            return List.of();
        }

        @Override
        public byte[] map(String name) {
            return null;
        }

        @Override
        public List<MusicTrack> musicTracks() {
            return List.of();
        }

        @Override
        public short[] musicSamples(int index) {
            return new short[0];
        }

        @Override
        public void close() {
            wrapped.close();
        }
    }

    private static Path buildPack(Path dir, String name) {
        Path pack = dir.resolve(name);
        try (AssetSource source = new GraphicsOnly(install())) {
            new PackBuilder(source, false).build(pack);
        }
        return pack;
    }

    private static GraphicDecoder.Kind kindOf(PackAsset sprite) {
        return "gfu".equals(sprite.string("encoding", "gfx"))
                ? GraphicDecoder.Kind.GFU
                : GraphicDecoder.Kind.GFX;
    }

    /** What the engine would draw for this asset. */
    private static IndexedImage asTheGameSeesIt(AssetPack pack, PackAsset sprite) {
        byte[] entry = EntryCodec.decode(sprite, pack.bytes(sprite));
        return GraphicDecoder.decode(kindOf(sprite), entry);
    }

    @Test
    void repaintingASpriteChangesWhatTheGameDraws(@TempDir Path dir) throws IOException {
        Path pack = buildPack(dir, "before.chonkpack");
        Path edited = dir.resolve("after.chonkpack");

        String id;
        int width;
        int height;
        PackAsset.Frame frame;
        try (AssetPack opened = AssetPack.open(pack)) {
            PackAsset sprite = opened.manifest().assets().stream()
                    .filter(asset -> asset.kind() == AssetKind.SPRITE)
                    .filter(asset -> !asset.frames().isEmpty())
                    .filter(asset -> asset.frames().get(0).width() >= 16
                            && asset.frames().get(0).height() >= 16)
                    .findFirst()
                    .orElse(null);
            assertNotNull(sprite, "the pack should hold sprites with frames of a usable size");
            id = sprite.id();
            width = sprite.width();
            height = sprite.height();
            frame = sprite.frames().get(0);

            IndexedPng.Image picture = opened.picture(sprite);
            assertEquals(width, picture.width());
            assertEquals(height, picture.height());

            // Paint inside the first frame's declared rectangle. Where that
            // rectangle is, is in the manifest, and it has to be: a frame is
            // drawn at an offset inside its cell, and the pixels around it are
            // not part of any frame and are not stored at all.
            byte[] pixels = picture.indices().clone();
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    pixels[(frame.y() + y) * width + frame.x() + x] = (byte) NEW_COLOUR;
                }
            }
            assertTrue(!Arrays.equals(picture.indices(), pixels),
                    "the edit should have changed something");

            replace(pack, sprite.file(),
                    IndexedPng.encode(width, height, pixels, picture.palette768(), 255),
                    edited);
        }

        try (AssetPack after = AssetPack.open(edited)) {
            PackAsset sprite = after.find(id);
            assertNotNull(sprite, "the edited pack should still name " + id);
            IndexedImage drawn = asTheGameSeesIt(after, sprite);

            assertEquals(width, drawn.width(), "the sheet is still its own size");
            assertEquals(height, drawn.height());

            int painted = 0;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    if (drawn.get(frame.x() + x, frame.y() + y) == NEW_COLOUR) {
                        painted++;
                    }
                }
            }
            assertEquals(256, painted,
                    "the game drew " + painted + " of the 256 repainted pixels, so an"
                    + " artist's edit reaches the screen only partly or not at all");

            // And nothing had to be told that the file changed. The manifest
            // still records the length and hash of the asset as it was built,
            // and the pack loads the new one anyway; requiring an artist to
            // recompute a SHA-256 to change a pixel would make the whole
            // workflow theoretical.
            assertTrue(!after.verify(sprite),
                    "a verification pass should notice a replaced asset even though"
                    + " loading it does not");
        }
    }

    @Test
    void paintingOutsideAFrameHasNoEffect(@TempDir Path dir) throws IOException {
        // The constraint an artist has to know about, pinned so that it is a
        // documented property rather than a surprise. A sheet is a grid of
        // cells and a frame occupies a rectangle inside its cell; the pixels
        // around it belong to no frame, are never encoded, and come back
        // transparent however they are painted. Growing a unit means changing
        // "frames" in the manifest, not painting over the margin.
        Path pack = buildPack(dir, "margin.chonkpack");
        Path edited = dir.resolve("margin-edited.chonkpack");

        String id;
        int outsideX;
        int outsideY;
        try (AssetPack opened = AssetPack.open(pack)) {
            PackAsset sprite = opened.manifest().assets().stream()
                    .filter(asset -> asset.kind() == AssetKind.SPRITE)
                    .filter(asset -> !asset.frames().isEmpty())
                    .filter(asset -> asset.frames().get(0).x() > 0
                            && asset.frames().get(0).y() > 0)
                    .findFirst()
                    .orElse(null);
            assumeTrue(sprite != null,
                    "this installation has no sprite whose first frame sits inset in its cell");
            id = sprite.id();
            outsideX = sprite.frames().get(0).x() - 1;
            outsideY = sprite.frames().get(0).y() - 1;

            IndexedPng.Image picture = opened.picture(sprite);
            byte[] pixels = picture.indices().clone();
            pixels[outsideY * picture.width() + outsideX] = (byte) NEW_COLOUR;
            replace(pack, sprite.file(),
                    IndexedPng.encode(picture.width(), picture.height(), pixels,
                            picture.palette768(), 255),
                    edited);
        }

        try (AssetPack after = AssetPack.open(edited)) {
            IndexedImage drawn = asTheGameSeesIt(after, after.find(id));
            assertNotEquals(NEW_COLOUR, drawn.get(outsideX, outsideY),
                    "a pixel outside every frame's rectangle should not reach the screen;"
                    + " if it now does, the format has silently started storing the margins"
                    + " and every sheet in the pack has grown");
        }
    }

    @Test
    void repaintingOnePixelLeavesEveryOtherPixelAlone(@TempDir Path dir) throws IOException {
        Path pack = buildPack(dir, "frames.chonkpack");
        Path edited = dir.resolve("frames-edited.chonkpack");

        String id;
        byte[] before;
        int at;
        try (AssetPack opened = AssetPack.open(pack)) {
            PackAsset sprite = opened.manifest().assets().stream()
                    .filter(asset -> asset.kind() == AssetKind.SPRITE)
                    .filter(asset -> asset.frames().size() > 4)
                    .filter(asset -> asset.frames().get(0).width() > 2
                            && asset.frames().get(0).height() > 2)
                    .findFirst()
                    .orElse(null);
            assumeTrue(sprite != null, "this installation has no multi-frame sprite in the pack");
            id = sprite.id();
            before = asTheGameSeesIt(opened, sprite).pixels().clone();

            IndexedPng.Image picture = opened.picture(sprite);
            PackAsset.Frame frame = sprite.frames().get(0);
            // Inside the first frame's rectangle. A pixel in the margin around
            // it belongs to no frame and is never stored, so editing one there
            // would prove nothing.
            at = (frame.y() + 1) * picture.width() + frame.x() + 1;
            byte[] pixels = picture.indices().clone();
            pixels[at] = (byte) NEW_COLOUR;
            replace(pack, sprite.file(),
                    IndexedPng.encode(picture.width(), picture.height(), pixels,
                            picture.palette768(), 255),
                    edited);
        }

        try (AssetPack after = AssetPack.open(edited)) {
            PackAsset sprite = after.find(id);
            byte[] drawn = asTheGameSeesIt(after, sprite).pixels();

            assertNotEquals(before[at], drawn[at], "the edited pixel should have changed");
            // Everything else comes through untouched. An encoder that rebuilt
            // the sheet from its own idea of the frame layout would pass the
            // test above and fail this one, having quietly moved every
            // animation frame a few pixels.
            byte[] expected = before.clone();
            expected[at] = drawn[at];
            assertArrayEquals(expected, drawn,
                    "repainting one pixel changed something else on the sheet");
        }
    }

    /** Rewrites a zip with one entry's bytes replaced, as an artist's tool would. */
    private static void replace(Path from, String entryName, byte[] bytes, Path to)
            throws IOException {
        boolean found = false;
        try (ZipFile source = new ZipFile(from.toFile());
                ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(to))) {
            Enumeration<? extends ZipEntry> entries = source.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                byte[] payload;
                if (entry.getName().equals(entryName)) {
                    payload = bytes;
                    found = true;
                } else {
                    try (InputStream in = source.getInputStream(entry)) {
                        payload = in.readAllBytes();
                    }
                }
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setMethod(ZipEntry.DEFLATED);
                copy.setTime(0);
                out.putNextEntry(copy);
                out.write(payload);
                out.closeEntry();
            }
        }
        assertTrue(found, "no entry called " + entryName + " in " + from);
    }
}
