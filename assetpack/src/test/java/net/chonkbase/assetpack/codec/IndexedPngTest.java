package net.chonkbase.assetpack.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import net.chonkbase.assetpack.PackFormatException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What an artist gets back after opening a pack's picture, editing it and
 * handing it over, and what the game draws from it afterwards.
 *
 * <p>The failure being guarded against does not throw and does not look wrong
 * in a viewer. Every pixel of a Warcraft II sprite is a palette index and the
 * game does arithmetic on the index: 208 to 211 are recoloured per player, and
 * the tileset's water, fire and flashing minimap are index bands cycled at draw
 * time. A codec that renumbers indices -- quantising, dropping palette entries
 * it thinks are unused, or doing what {@code SavePNG}
 * does and merging index 0 into index 255 -- writes a file that opens perfectly
 * in any paint program and freezes every river in the game the moment it loads.
 *
 * <p>So nothing here asserts that a header parsed. Each test puts known indices
 * through the writer and the reader and looks at the indices that came back.
 */
class IndexedPngTest {

    /** Two hundred and fifty-six triples, all different, so a swap would show. */
    private static byte[] palette() {
        byte[] palette = new byte[768];
        for (int i = 0; i < 256; i++) {
            palette[i * 3] = (byte) i;
            palette[i * 3 + 1] = (byte) (255 - i);
            palette[i * 3 + 2] = (byte) (i * 7);
        }
        return palette;
    }

    @Test
    @DisplayName("all two hundred and fifty-six palette indices come back as themselves")
    void everyIndexSurvivesWhenAllOfThemAppearAtOnce() {
        // Not a 0..255 ramp. A ramp is exactly the shape the Sub filter turns
        // into a constant, so it would pass on an encoder that had thrown the
        // pixel values away and kept only the differences between them.
        byte[] indices = new byte[256];
        for (int i = 0; i < 256; i++) {
            indices[i] = (byte) (i * 97 + 13);
        }
        assertEquals(256, distinct(indices),
                "the fixture must contain every index once or it proves nothing about the rest");

        IndexedPng.Image image =
                IndexedPng.decode(IndexedPng.encode(16, 16, indices, palette(), 0));

        assertEquals(16, image.width(), "the picture came back a different width");
        assertEquals(16, image.height(), "the picture came back a different height");
        assertArrayEquals(indices, image.indices(),
                "a palette index changed in the round trip, which recolours whatever drew with it");
        assertArrayEquals(palette(), image.palette768(),
                "the palette came back reordered, so every index now names a different colour");

        // The same picture with no transparency at all. An encoder that only
        // held indices still when it had a tRNS chunk to hang them off would
        // pass the check above and fail here.
        IndexedPng.Image opaque =
                IndexedPng.decode(IndexedPng.encode(16, 16, indices, palette(), -1));
        assertArrayEquals(indices, opaque.indices(),
                "an image written with no transparent index lost a palette index");
    }

    @Test
    @DisplayName("a pixel of index 255 is still index 255, and index 0 is still index 0")
    void theEndsOfThePaletteAreNotMergedByTransparency() {
        // Upstream's trap. SavePNG, told an image is
        // transparent, walks every pixel rewriting index 0 to index 255 and
        // then declares 255 transparent, because LegacyEngine wants the
        // transparent colour last. A sprite using both ends of the palette
        // comes out of that with the two merged into one, and nothing says so.
        byte[] indices = {0, (byte) 255, 1, (byte) 254, (byte) 255, 0, (byte) 128, 0};
        assertTrue(contains(indices, 0) && contains(indices, 255),
                "the fixture must use both ends of the palette or it cannot tell the rules apart");

        for (int transparentIndex : new int[] {-1, 0, 255, 128}) {
            IndexedPng.Image image = IndexedPng.decode(
                    IndexedPng.encode(8, 1, indices, palette(), transparentIndex));
            assertArrayEquals(indices, image.indices(),
                    "with tRNS naming index " + transparentIndex + " the picture's own indices"
                            + " changed, which is upstream's index 0 to 255 remap coming back");
        }
    }

    @Test
    @DisplayName("only the one named colour is see-through when the artist opens the file")
    void everyOtherPaletteEntryIsWrittenOpaque() {
        byte[] indices = new byte[64];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = (byte) i;
        }
        byte[] png = IndexedPng.encode(8, 8, indices, palette(), 12);

        int at = chunkAt(png, "tRNS");
        int length = readInt(png, at);
        assertEquals(13, length, "tRNS must stop at the transparent entry: PNG treats every"
                + " palette entry it does not mention as fully opaque");
        for (int i = 0; i < length; i++) {
            int alpha = png[at + 8 + i] & 0xFF;
            assertEquals(i == 12 ? 0 : 255, alpha, "palette entry " + i + " was written with alpha "
                    + alpha + "; anything but 255 fades part of the sprite out in the artist's"
                    + " editor and in the game");
        }
    }

    @Test
    @DisplayName("the file on disc holds the picture, not just the one in memory")
    void thePictureSurvivesBeingWrittenOutAndReadBackIn(@TempDir Path directory)
            throws IOException {
        byte[] indices = spriteSheet(4, 4, 32);
        byte[] png = IndexedPng.encode(4 * 32, 4 * 32, indices, palette(), 0);

        Path file = directory.resolve("footman.png");
        Files.write(file, png);
        byte[] fromDisc = Files.readAllBytes(file);

        assertArrayEquals(png, fromDisc, "the file on disc is not the bytes the encoder produced");
        IndexedPng.Image image = IndexedPng.decode(fromDisc);
        assertArrayEquals(indices, image.indices(),
                "the sprite read back off disc is not the sprite that was written");
        assertArrayEquals(palette(), image.palette768(),
                "the palette read back off disc is not the palette that was written");
    }

    @Test
    @DisplayName("a thirty-two bit export is turned away by name instead of decoded into noise")
    void aTruecolourWithAlphaFileIsRejectedSayingWhichColourTypeItIs() {
        // The expected mistake: an artist opens a sprite, edits it, and lets
        // the editor export RGBA, which is what every editor does by default.
        // What comes back is a valid PNG in which no pixel is a palette index.
        byte[] rgba = truecolourWithAlpha(8, 8);

        // The same chunk writer, used for an indexed file, produces something
        // this reader is happy with. Without this the test below would pass on
        // a fixture that was merely malformed, and prove nothing about colour.
        assertArrayEquals(new byte[] {1, 2, 3, 4},
                IndexedPng.decode(handWritten(2, 2, 8, new byte[] {1, 2, 3, 4}, 12)).indices(),
                "the fixture builder does not produce files this reader accepts, so the"
                        + " rejection below would prove nothing");

        PackFormatException thrown =
                assertThrows(PackFormatException.class, () -> IndexedPng.decode(rgba),
                        "a 32-bit RGBA export was accepted, and every pixel of it is a colour"
                                + " where the game expects a palette index");
        String message = thrown.getMessage();
        assertTrue(message.contains("colour type 6"),
                "the message must name the colour type the artist actually exported: " + message);
        assertTrue(message.contains("truecolour with alpha"),
                "the message must say what colour type 6 is, in words: " + message);
        assertTrue(message.contains("indexed"),
                "the message must say what to export instead: " + message);
    }

    @Test
    @DisplayName("a corrupted pack says which chunk went bad")
    void aBrokenChecksumNamesTheChunkItBelongsTo() {
        byte[] indices = spriteSheet(2, 2, 32);
        byte[] original = IndexedPng.encode(64, 64, indices, palette(), 0);
        assertArrayEquals(indices, IndexedPng.decode(original).indices(),
                "the fixture must read cleanly before it is damaged");

        byte[] brokenPixels = original.clone();
        brokenPixels[chunkAt(brokenPixels, "IDAT") + 8] ^= 0x01;
        String pixels = assertThrows(PackFormatException.class,
                () -> IndexedPng.decode(brokenPixels),
                "a flipped bit in the compressed pixels was read as a picture").getMessage();
        assertTrue(pixels.contains("IDAT"),
                "the message must name IDAT so the damage can be located: " + pixels);
        assertFalse(pixels.contains("IHDR"),
                "the message named a chunk other than the damaged one: " + pixels);

        // A flipped bit in IHDR is the dangerous one: it lands in the width,
        // and a picture claiming to be sixteen million pixels across is an
        // out-of-memory error rather than a message. The checksum is verified
        // before the header is looked at, which is why this reports instead.
        byte[] brokenHeader = original.clone();
        brokenHeader[chunkAt(brokenHeader, "IHDR") + 8] ^= 0x01;
        String header = assertThrows(PackFormatException.class,
                () -> IndexedPng.decode(brokenHeader),
                "a flipped bit in the image header was trusted").getMessage();
        assertTrue(header.contains("IHDR"),
                "the message must name IHDR, not whichever chunk was checked first: " + header);
        assertFalse(header.contains("IDAT"),
                "the message named IDAT for damage that is in IHDR: " + header);
    }

    @Test
    @DisplayName("no damaged file gets a stack trace instead of a sentence")
    void everySingleByteOfDamageIsReportedAndNoneOfItCrashes() {
        // A pack is a zip someone unpacks, edits inside and zips up again, and
        // a half-copied file is the ordinary way it goes wrong. The reader
        // walks a length it was handed and indexes arrays with it, so the
        // failure mode to rule out is an array index or an out-of-memory
        // reaching the player instead of a message naming the chunk.
        byte[] original = IndexedPng.encode(64, 64, spriteSheet(2, 2, 32), palette(), 0);
        int reported = 0;
        for (int i = 0; i < original.length; i++) {
            byte[] damaged = original.clone();
            damaged[i] ^= 0x5A;
            try {
                IndexedPng.decode(damaged);
                throw new AssertionError("byte " + i + " was changed and the file still read as a"
                        + " picture, so the damage would reach the game unnoticed");
            } catch (PackFormatException e) {
                reported++;
            }
        }
        assertEquals(original.length, reported, "every one of the " + original.length + " bytes"
                + " in this file must be reported when it changes");

        int truncations = 0;
        for (int length = 0; length < original.length; length++) {
            byte[] cut = Arrays.copyOf(original, length);
            assertThrows(PackFormatException.class, () -> IndexedPng.decode(cut),
                    "a file cut off at " + length + " bytes was read as a picture");
            truncations++;
        }
        assertEquals(original.length, truncations,
                "the sweep must actually have cut the file somewhere");
    }

    @Test
    @DisplayName("a header claiming an enormous picture is reported, not believed")
    void anImpossibleSizeInTheHeaderIsNotAllocated() {
        // IHDR is four bytes of width and four of height and nothing else in
        // the file has to agree with them. A pack whose header says 65535 by
        // 65535 asks the game for four gigabytes before it has read a pixel,
        // and the two numbers multiplied out in an int wrap to something
        // negative, so the honest answer is a sentence rather than either.
        for (int[] size : new int[][] {{65535, 65535}, {2000000000, 2}, {1, Integer.MAX_VALUE}}) {
            byte[] png = assemble(size[0], size[1], 8, palette(), -1, deflate(new byte[0]), 1);
            String message = assertThrows(PackFormatException.class, () -> IndexedPng.decode(png),
                    "a header claiming " + size[0] + "x" + size[1] + " pixels was believed")
                    .getMessage();
            assertTrue(message.contains(size[0] + "x" + size[1]),
                    "the message must repeat the size the file claimed: " + message);
        }
    }

    @Test
    @DisplayName("a sprite sheet of fifty frames comes back frame for frame")
    void aSpriteSheetRoundTripsWithoutOneChangedPixel() {
        int frame = 72;
        int columns = 5;
        int rows = 10;
        int width = columns * frame;
        int height = rows * frame;
        byte[] indices = spriteSheet(columns, rows, frame);

        // Guards on the fixture. A sheet that was all background, or one drawn
        // from all 256 indices, would not be the shape of art this codec is
        // for, and the size measured off it would mean nothing.
        int transparent = 0;
        for (byte index : indices) {
            if (index == 0) {
                transparent++;
            }
        }
        assertTrue(transparent > indices.length / 2, "the fixture must be mostly transparent like"
                + " real sprite art: only " + transparent + " of " + indices.length + " pixels");
        assertTrue(transparent < indices.length * 9 / 10, "the fixture must actually have sprites"
                + " drawn on it: " + transparent + " of " + indices.length + " pixels are empty");
        assertTrue(distinct(indices) <= 16, "the fixture must draw from a small palette like real"
                + " sprite art: it used " + distinct(indices) + " indices");

        byte[] png = IndexedPng.encode(width, height, indices, palette(), 0);
        IndexedPng.Image image = IndexedPng.decode(png);

        assertEquals(width, image.width(), "the sheet came back a different width, so every frame"
                + " after the first would be sliced out of the wrong place");
        assertEquals(height, image.height(), "the sheet came back a different height");
        assertArrayEquals(indices, image.indices(),
                "a pixel changed in a " + width + "x" + height + " sheet");

        assertTrue(png.length < indices.length / 8, "a sheet that is mostly one background index"
                + " came out at " + png.length + " bytes from " + indices.length + " pixels, which"
                + " means the per-scanline filter search is not doing its job");
    }

    @Test
    @DisplayName("a terrain sheet of one repeated tile is a few kilobytes, not a quarter megabyte")
    void oneTileRepeatedAcrossFiveHundredPixelsCompressesHard() {
        byte[] tile = terrainTile(32);
        int width = 512;
        int height = 512;
        byte[] indices = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                indices[y * width + x] = tile[y % 32 * 32 + x % 32];
            }
        }

        byte[] png = IndexedPng.encode(width, height, indices, palette(), -1);

        assertTrue(png.length < 4096, "512x512 of one repeated 32x32 tile came out at "
                + png.length + " bytes; across the few thousand tiles in a pack that is the"
                + " difference between a download an artist will take and one they will not");
        assertArrayEquals(indices, IndexedPng.decode(png).indices(),
                "the tiled terrain did not survive the compression it just proved");
    }

    @Test
    @DisplayName("filtering is used where it helps and dropped where it costs")
    void neitherKindOfPictureIsMadeBiggerByTheFilterSearch() {
        // Two fixtures that pull in opposite directions, because one of them
        // alone cannot tell the two policies apart. Sprite art is names in a
        // palette and subtracting one name from another produces bytes deflate
        // cannot pack; a gradient is brightnesses and subtracting is the whole
        // point of PNG filtering.
        byte[] sprites = spriteSheet(5, 10, 72);
        byte[] gradient = new byte[256 * 256];
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                gradient[y * 256 + x] = (byte) ((x + y) / 2);
            }
        }

        // Measured on the pixel data alone. A full 256-entry PLTE is 780 bytes
        // whatever the picture is, and on a small one it swamps the difference
        // being measured.
        int sheet = pixelBytes(IndexedPng.encode(360, 720, sprites, palette(), 0));
        int sheetUnfiltered = unfilteredPixelBytes(360, 720, sprites);
        assertTrue(sheet <= sheetUnfiltered, "the filter search made a sprite sheet bigger: "
                + sheet + " bytes of pixels against " + sheetUnfiltered + " with no filtering at"
                + " all, and everybody downloading the pack pays that difference");

        int smooth = pixelBytes(IndexedPng.encode(256, 256, gradient, palette(), -1));
        int smoothUnfiltered = unfilteredPixelBytes(256, 256, gradient);
        assertTrue(smooth * 2 < smoothUnfiltered, "filtering was skipped on the one kind of"
                + " picture it was designed for: " + smooth + " bytes of pixels against "
                + smoothUnfiltered + " unfiltered, so the search is not used where it pays");

        assertArrayEquals(sprites, IndexedPng.decode(
                        IndexedPng.encode(360, 720, sprites, palette(), 0)).indices(),
                "the sheet whose size was just measured does not read back");
        assertArrayEquals(gradient, IndexedPng.decode(
                        IndexedPng.encode(256, 256, gradient, palette(), -1)).indices(),
                "the gradient whose size was just measured does not read back");
    }

    @Test
    @DisplayName("a file another tool wrote, with a different filter on every row, still reads")
    void everyPngFilterTypeIsUnderstoodOnAnyRow() {
        int width = 24;
        int height = 20;
        byte[] indices = new byte[width * height];
        Noise noise = new Noise(0xF117E5);
        for (int i = 0; i < indices.length; i++) {
            indices[i] = (byte) noise.next(256);
        }
        assertTrue(distinct(indices) > 128, "the fixture must span the palette so a filter that"
                + " was undone with the wrong neighbour would show: it used " + distinct(indices));

        for (int filterType = 0; filterType <= 4; filterType++) {
            byte[] png = handWrittenFiltered(width, height, indices, filterType);
            assertArrayEquals(indices, IndexedPng.decode(png).indices(),
                    "a file filtered entirely with type " + filterType + " read back wrong");
        }

        // Editors choose per row and nothing says the choice is consistent. A
        // reader that understood only the filters this encoder happens to pick
        // would open its own files and fail on the artist's.
        assertArrayEquals(indices, IndexedPng.decode(
                        handWrittenFiltered(width, height, indices, -1)).indices(),
                "a file with a different filter on each row read back wrong");
    }

    @Test
    @DisplayName("a picture split over several data chunks is one picture")
    void severalDataChunksReadAsOneImage() {
        byte[] indices = spriteSheet(2, 2, 24);
        byte[] split = splitPixelData(IndexedPng.encode(48, 48, indices, palette(), 0), 5);

        assertEquals(5, count(split, "IDAT"),
                "the fixture must actually be split or it proves nothing");
        assertArrayEquals(indices, IndexedPng.decode(split).indices(),
                "a picture written as five data chunks, which is what a streaming writer"
                        + " produces, read back wrong");
    }

    @Test
    @DisplayName("a palette an editor trimmed to the colours in use still names the same indices")
    void aShortPaletteIsPaddedWithBlackAndLeavesIndicesAlone() {
        // Editors write only the entries a picture uses. A twelve-colour sprite
        // handed back has a twelve-entry PLTE and its pixels still say 0 to 11;
        // the entries past that are the pack's business, not the file's.
        byte[] indices = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
        byte[] trimmed = new byte[36];
        for (int i = 0; i < 12; i++) {
            trimmed[i * 3] = (byte) (i * 20);
            trimmed[i * 3 + 1] = (byte) (i * 3);
            trimmed[i * 3 + 2] = (byte) (200 - i);
        }

        IndexedPng.Image image =
                IndexedPng.decode(handWritten(4, 3, 8, indices, 0, trimmed));

        assertArrayEquals(indices, image.indices(),
                "a trimmed palette renumbered the sprite, which is the whole failure this codec"
                        + " exists to prevent");
        assertEquals(768, image.palette768().length,
                "the palette must always come back 768 bytes so the pack can index all of it");
        for (int i = 0; i < 36; i++) {
            assertEquals(trimmed[i] & 0xFF, image.palette768()[i] & 0xFF,
                    "palette byte " + i + " changed colour on the way in");
        }
        for (int i = 36; i < 768; i++) {
            assertEquals(0, image.palette768()[i] & 0xFF,
                    "palette byte " + i + ", past the end of a trimmed PLTE, must be black");
        }
    }

    @Test
    @DisplayName("a header whose pixel count overflows an int is reported, not multiplied out")
    void aSubByteHeaderTooBigForAnIntIsReportedRatherThanWrapped() {
        // The sibling test above checks sizes whose scanline total passes two
        // billion. That is not the only way a header gets too big, and below
        // eight bits per pixel it is not even the first: a row is a fraction of
        // its width there, so 65536x32769 at one bit per pixel is 268476417
        // bytes of scanline, which fits in an int, and 2147549184 pixels, which
        // does not. A file that size is 261 kilobytes with every checksum in it
        // correct, so nothing earlier turns it away, and it used to reach the
        // widening step and come out of the codec as a bare ArithmeticException
        // from Math.multiplyExact -- the stack trace instead of the sentence
        // this reader promises the pack layer for every file it will not read.
        byte[] png = assemble(65536, 32769, 1, palette(), -1, deflate(new byte[64]), 1);

        Throwable thrown = assertThrows(Throwable.class, () -> IndexedPng.decode(png));
        assertEquals(PackFormatException.class, thrown.getClass(),
                "a picture of 2147549184 pixels was reported as " + thrown.getClass().getName()
                        + ", and only a PackFormatException carries a sentence the pack layer"
                        + " can put in front of somebody: " + thrown);
        assertTrue(thrown.getMessage().contains("65536x32769"),
                "the message must repeat the size the file claimed: " + thrown.getMessage());
        assertFalse(thrown.getMessage().contains("truncated"),
                "a header no int can hold was reported as truncated data, which sends the artist"
                        + " looking for a bad copy of a file that copied fine: "
                        + thrown.getMessage());
    }

    @Test
    @DisplayName("a header claiming a huge picture is not allocated before it is checked")
    void aBelievableButLyingHeaderCostsNothingToTurnAway() {
        // The sibling test is named for not allocating and never measures it.
        // Sizes past two billion are rejected by arithmetic, but 20000x20000 is
        // under that bar and still 400 megabytes, and the file claiming it is
        // 848 bytes with every checksum correct so the CRC gate stays quiet. A
        // pack is a zip downloaded from somewhere; believing its header to the
        // tune of 400 megabytes is an OutOfMemoryError on any modest heap, and
        // measured here it really did allocate 400 megabytes to read 20 bytes.
        byte[] png = assemble(20000, 20000, 8, palette(), -1, deflate(new byte[20]), 1);

        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isThreadAllocatedMemorySupported()
                && threads.isThreadAllocatedMemoryEnabled(),
                "this JVM cannot measure how much a thread allocated");

        long before = threads.getCurrentThreadAllocatedBytes();
        assertThrows(PackFormatException.class, () -> IndexedPng.decode(png),
                "a header claiming 20000x20000 was believed");
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;

        assertTrue(allocated < 8L << 20, "turning away an 848-byte file whose header lies cost "
                + (allocated >> 20) + " megabytes, so the size in the header was allocated before"
                + " it was checked and a big enough lie is an OutOfMemoryError");

        // The picture it lied about still has to read, or the cheap rejection
        // above is just a reader that stopped working.
        byte[] indices = spriteSheet(2, 2, 32);
        assertArrayEquals(indices,
                IndexedPng.decode(IndexedPng.encode(64, 64, indices, palette(), 0)).indices(),
                "the reader stopped reading real pictures");
    }

    @Test
    @DisplayName("a small-palette sprite an editor filtered as well as packed keeps its indices")
    void subByteDepthsSurviveEveryFilterOnAnyRow() {
        // The four-bit test below hands over a file with filter type 0 on every
        // row, and no editor writes that. ImageIO, handed a sixteen-colour
        // image, writes four bits a pixel AND picks a filter per row from all
        // five, which is two features at once: the filter runs over packed
        // bytes holding two pixels each, and the last byte of a row is part
        // padding. A reader that undid the filter a pixel at a time instead of
        // a byte at a time would come apart here and nowhere else.
        for (int bitDepth : new int[] {1, 2, 4}) {
            // Widths chosen so no row is a whole number of bytes: the padding
            // bits at the end of a row are filtered with the pixels and have to
            // be reconstructed before they can be thrown away.
            int width = 61;
            int height = 9;
            int colours = 1 << bitDepth;
            byte[] indices = new byte[width * height];
            Noise noise = new Noise(0xB17DEB7);
            for (int i = 0; i < indices.length; i++) {
                indices[i] = (byte) noise.next(colours);
            }
            assertEquals(colours, distinct(indices), "the fixture must use all " + colours
                    + " indices available at " + bitDepth + " bits a pixel");

            byte[] png = packedAndFiltered(width, height, bitDepth, indices);
            assertArrayEquals(indices, IndexedPng.decode(png).indices(),
                    "a " + bitDepth + "-bit file with a different filter on every row came back"
                            + " with different indices, and every index is what the game draws");
        }
    }

    @Test
    @DisplayName("a sixteen-colour sprite saved at four bits a pixel keeps its indices")
    void aFourBitFileWidensToOneIndexPerByte() {
        byte[] indices = new byte[64];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = (byte) (i * 7 % 16);
        }
        assertEquals(16, distinct(indices),
                "the fixture must use all sixteen four-bit indices or it proves little");

        byte[] png = handWritten(8, 8, 4, indices, 0, Arrays.copyOf(palette(), 48));
        assertArrayEquals(indices, IndexedPng.decode(png).indices(),
                "a four-bit file, which is what an editor writes for a small palette, came back"
                        + " with different indices");
    }

    // -------------------------------------------------------------- fixtures

    /**
     * A pseudo-random source with a fixed seed and no dependence on the JDK's.
     *
     * <p>A linear congruential generator written out here rather than
     * {@code java.util.Random}, so a failure is the same failure on every
     * machine and every JDK, and so a size measured today is the size measured
     * next year.
     */
    private static final class Noise {
        private int state;

        Noise(int seed) {
            this.state = seed;
        }

        int next(int bound) {
            state = state * 1103515245 + 12345;
            return (state >>> 16 & 0x7FFF) % bound;
        }
    }

    /** The colours a unit frame draws from, the last four being the team band. */
    private static final int[] SPRITE_PALETTE = {
        12, 13, 14, 27, 28, 29, 55, 56, 57, 87, 208, 209, 210, 211
    };

    /**
     * A sheet shaped like unit art: a transparent field with a blob in the
     * middle of each cell, drawn in short runs from a dozen indices, with every
     * third row repeating the one above it the way a walk cycle's frames do.
     *
     * <p>Runs rather than one draw per pixel. Per-pixel noise is not sprite
     * art, and an encoder measured against it is being measured on deflate's
     * worst case: the same sheet filled a pixel at a time comes out at 35056
     * bytes where this one comes out at 18184.
     */
    private static byte[] spriteSheet(int columns, int rows, int frame) {
        int width = columns * frame;
        byte[] indices = new byte[columns * rows * frame * frame];
        Noise noise = new Noise(0x5EED4A11);
        for (int cellY = 0; cellY < rows; cellY++) {
            for (int cellX = 0; cellX < columns; cellX++) {
                int originX = cellX * frame;
                int originY = cellY * frame;
                int centre = frame / 2;
                int span = frame / 6;
                for (int y = frame / 8; y < frame - frame / 8; y++) {
                    int row = (originY + y) * width + originX;
                    if (y % 3 == 0 && y > frame / 8) {
                        System.arraycopy(indices, row - width, indices, row, frame);
                        continue;
                    }
                    span = Math.max(2, Math.min(frame / 3, span + noise.next(3) - 1));
                    int colour = 0;
                    int run = 0;
                    for (int x = centre - span; x <= centre + span; x++) {
                        if (run-- <= 0) {
                            colour = SPRITE_PALETTE[noise.next(SPRITE_PALETTE.length)];
                            run = 2 + noise.next(5);
                        }
                        indices[row + x] = (byte) colour;
                    }
                }
            }
        }
        return indices;
    }

    /**
     * A tile shaped like terrain: one ground colour, a scatter of three near
     * shades and a couple of darker seams. Nothing in a tileset is noise, and a
     * noise fixture would measure deflate rather than measuring this encoder.
     */
    private static byte[] terrainTile(int size) {
        byte[] tile = new byte[size * size];
        Noise noise = new Noise(0x7A11E5E7);
        for (int i = 0; i < tile.length; i++) {
            int roll = noise.next(100);
            tile[i] = (byte) (roll < 82 ? 62 : roll < 92 ? 63 : roll < 97 ? 61 : 64);
        }
        for (int y = 7; y < size; y += 11) {
            for (int x = 0; x < size; x++) {
                tile[y * size + x] = (byte) 58;
            }
        }
        return tile;
    }

    // -------------------------------------------- files other tools would write

    /** An indexed PNG assembled by hand, filter type 0 on every row. */
    private static byte[] handWritten(int width, int height, int bitDepth, byte[] indices,
            int transparentIndex) {
        return handWritten(width, height, bitDepth, indices, transparentIndex, palette());
    }

    private static byte[] handWritten(int width, int height, int bitDepth, byte[] indices,
            int transparentIndex, byte[] plte) {
        int bytesPerRow = (width * bitDepth + 7) / 8;
        byte[] raw = new byte[height * (bytesPerRow + 1)];
        int perByte = 8 / bitDepth;
        for (int y = 0; y < height; y++) {
            int row = y * (bytesPerRow + 1) + 1;
            for (int x = 0; x < width; x++) {
                int index = indices[y * width + x] & 0xFF;
                if (bitDepth == 8) {
                    raw[row + x] = (byte) index;
                } else {
                    raw[row + x / perByte] |= (byte) (index << 8 - bitDepth * (x % perByte + 1));
                }
            }
        }
        return assemble(width, height, bitDepth, plte, transparentIndex, deflate(raw), 1);
    }

    /**
     * A file packed at fewer than eight bits a pixel and filtered per row, both
     * at once, which is what an editor hands back for a small-palette sprite.
     *
     * <p>The filter runs over the packed bytes, not over pixels: at four bits a
     * pixel the byte to the left holds the two pixels before this one, and the
     * last byte of a row is part padding. That is what makes this different
     * from the eight-bit fixture rather than a smaller copy of it.
     */
    private static byte[] packedAndFiltered(int width, int height, int bitDepth, byte[] indices) {
        int bytesPerRow = (width * bitDepth + 7) / 8;
        int perByte = 8 / bitDepth;
        byte[] packed = new byte[height * bytesPerRow];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = indices[y * width + x] & 0xFF;
                packed[y * bytesPerRow + x / perByte] |=
                        (byte) (index << 8 - bitDepth * (x % perByte + 1));
            }
        }

        byte[] raw = new byte[height * (bytesPerRow + 1)];
        int at = 0;
        for (int y = 0; y < height; y++) {
            int type = y % 5;
            raw[at++] = (byte) type;
            int row = y * bytesPerRow;
            for (int x = 0; x < bytesPerRow; x++) {
                int value = packed[row + x] & 0xFF;
                int left = x > 0 ? packed[row + x - 1] & 0xFF : 0;
                int above = y > 0 ? packed[row - bytesPerRow + x] & 0xFF : 0;
                int aboveLeft = x > 0 && y > 0 ? packed[row - bytesPerRow + x - 1] & 0xFF : 0;
                raw[at + x] = (byte) switch (type) {
                    case 0 -> value;
                    case 1 -> value - left;
                    case 2 -> value - above;
                    case 3 -> value - ((left + above) >> 1);
                    default -> value - paeth(left, above, aboveLeft);
                };
            }
            at += bytesPerRow;
        }
        return assemble(width, height, bitDepth, Arrays.copyOf(palette(), (1 << bitDepth) * 3),
                -1, deflate(raw), 1);
    }

    /**
     * An indexed PNG filtered the way some other encoder chose to filter it:
     * one type throughout, or a different type on every row when asked for -1.
     */
    private static byte[] handWrittenFiltered(int width, int height, byte[] indices,
            int filterType) {
        byte[] raw = new byte[height * (width + 1)];
        int at = 0;
        for (int y = 0; y < height; y++) {
            int type = filterType < 0 ? y % 5 : filterType;
            raw[at++] = (byte) type;
            for (int x = 0; x < width; x++) {
                int value = indices[y * width + x] & 0xFF;
                int left = x > 0 ? indices[y * width + x - 1] & 0xFF : 0;
                int above = y > 0 ? indices[(y - 1) * width + x] & 0xFF : 0;
                int aboveLeft = x > 0 && y > 0 ? indices[(y - 1) * width + x - 1] & 0xFF : 0;
                raw[at + x] = (byte) switch (type) {
                    case 0 -> value;
                    case 1 -> value - left;
                    case 2 -> value - above;
                    case 3 -> value - ((left + above) >> 1);
                    default -> value - paeth(left, above, aboveLeft);
                };
            }
            at += width;
        }
        return assemble(width, height, 8, palette(), -1, deflate(raw), 1);
    }

    /** A valid 32-bit RGBA PNG: colour type 6, which is what an editor exports. */
    private static byte[] truecolourWithAlpha(int width, int height) {
        byte[] raw = new byte[height * (width * 4 + 1)];
        int at = 0;
        for (int y = 0; y < height; y++) {
            at++;
            for (int x = 0; x < width; x++) {
                raw[at++] = (byte) (x * 8);
                raw[at++] = (byte) (y * 8);
                raw[at++] = (byte) 0x40;
                raw[at++] = (byte) 0xFF;
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(SIGNATURE);
        byte[] header = new byte[13];
        putInt(header, 0, width);
        putInt(header, 4, height);
        header[8] = 8;
        header[9] = 6;
        chunk(out, "IHDR", header);
        chunk(out, "IDAT", deflate(raw));
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static byte[] assemble(int width, int height, int bitDepth, byte[] plte,
            int transparentIndex, byte[] pixels, int dataChunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(SIGNATURE);
        byte[] header = new byte[13];
        putInt(header, 0, width);
        putInt(header, 4, height);
        header[8] = (byte) bitDepth;
        header[9] = 3;
        chunk(out, "IHDR", header);
        chunk(out, "PLTE", plte);
        if (transparentIndex >= 0) {
            byte[] alpha = new byte[transparentIndex + 1];
            Arrays.fill(alpha, (byte) 0xFF);
            alpha[transparentIndex] = 0;
            chunk(out, "tRNS", alpha);
        }
        int each = (pixels.length + dataChunks - 1) / dataChunks;
        for (int at = 0; at < pixels.length; at += each) {
            chunk(out, "IDAT",
                    Arrays.copyOfRange(pixels, at, Math.min(pixels.length, at + each)));
        }
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    /** The compressed pixels a file carries, headers and palette left out. */
    private static int pixelBytes(byte[] png) {
        int total = 0;
        int at = SIGNATURE.length;
        while (at + 12 <= png.length) {
            int length = readInt(png, at);
            if ("IDAT".equals(new String(png, at + 4, 4, StandardCharsets.ISO_8859_1))) {
                total += length;
            }
            at += 12 + length;
        }
        return total;
    }

    /** What the same pixels compress to with filter type 0 on every row. */
    private static int unfilteredPixelBytes(int width, int height, byte[] indices) {
        byte[] raw = new byte[height * (width + 1)];
        for (int y = 0; y < height; y++) {
            System.arraycopy(indices, y * width, raw, y * (width + 1) + 1, width);
        }
        return deflate(raw).length;
    }

    /** Rewrites a file's one data chunk as several, as a streaming writer would. */
    private static byte[] splitPixelData(byte[] png, int pieces) {
        int at = chunkAt(png, "IDAT");
        int length = readInt(png, at);
        byte[] pixels = Arrays.copyOfRange(png, at + 8, at + 8 + length);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(Arrays.copyOfRange(png, 0, at));
        int each = (pixels.length + pieces - 1) / pieces;
        for (int from = 0; from < pixels.length; from += each) {
            chunk(out, "IDAT",
                    Arrays.copyOfRange(pixels, from, Math.min(pixels.length, from + each)));
        }
        out.writeBytes(Arrays.copyOfRange(png, at + 12 + length, png.length));
        return out.toByteArray();
    }

    // ----------------------------------------------------------------- bytes

    private static final byte[] SIGNATURE = {
        (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'
    };

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] length = new byte[4];
        putInt(length, 0, data.length);
        out.writeBytes(length);
        byte[] name = type.getBytes(StandardCharsets.ISO_8859_1);
        out.writeBytes(name);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        byte[] checksum = new byte[4];
        putInt(checksum, 0, (int) crc.getValue());
        out.writeBytes(checksum);
    }

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static int chunkAt(byte[] png, String type) {
        int at = SIGNATURE.length;
        while (at + 12 <= png.length) {
            if (type.equals(new String(png, at + 4, 4, StandardCharsets.ISO_8859_1))) {
                return at;
            }
            at += 12 + readInt(png, at);
        }
        throw new AssertionError("the fixture has no " + type + " chunk");
    }

    private static int count(byte[] png, String type) {
        int found = 0;
        int at = SIGNATURE.length;
        while (at + 12 <= png.length) {
            if (type.equals(new String(png, at + 4, 4, StandardCharsets.ISO_8859_1))) {
                found++;
            }
            at += 12 + readInt(png, at);
        }
        return found;
    }

    private static int readInt(byte[] in, int at) {
        return (in[at] & 0xFF) << 24 | (in[at + 1] & 0xFF) << 16
                | (in[at + 2] & 0xFF) << 8 | in[at + 3] & 0xFF;
    }

    private static void putInt(byte[] out, int at, int value) {
        out[at] = (byte) (value >>> 24);
        out[at + 1] = (byte) (value >>> 16);
        out[at + 2] = (byte) (value >>> 8);
        out[at + 3] = (byte) value;
    }

    private static int paeth(int left, int above, int aboveLeft) {
        int estimate = left + above - aboveLeft;
        int fromLeft = Math.abs(estimate - left);
        int fromAbove = Math.abs(estimate - above);
        int fromAboveLeft = Math.abs(estimate - aboveLeft);
        if (fromLeft <= fromAbove && fromLeft <= fromAboveLeft) {
            return left;
        }
        return fromAbove <= fromAboveLeft ? above : aboveLeft;
    }

    private static int distinct(byte[] indices) {
        boolean[] seen = new boolean[256];
        int found = 0;
        for (byte index : indices) {
            if (!seen[index & 0xFF]) {
                seen[index & 0xFF] = true;
                found++;
            }
        }
        return found;
    }

    private static boolean contains(byte[] indices, int index) {
        for (byte value : indices) {
            if ((value & 0xFF) == index) {
                return true;
            }
        }
        return false;
    }
}
