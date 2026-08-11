package net.chonkbase.chonkcraft.data.graphic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.chonkbase.chonkcraft.data.GraphicsIndex;
import net.chonkbase.chonkcraft.data.NameTable;
import net.chonkbase.chonkcraft.data.archive.WarArchive;
import net.chonkbase.chonkcraft.data.source.ArchiveIds;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a unit looks like after its sheet has been round-tripped through the
 * asset pack.
 *
 * <p>The pack stores sprite sheets as editable indexed PNGs so an artist can
 * repaint a footman, and hands them back to the engine as archive entries,
 * which means every sprite in the game passes through {@link GraphicEncoder}
 * on its way to being drawn. Nothing else in the implementation re-creates the 1995
 * encoding, so nothing else would catch a run split in the wrong place: the
 * unit would still be there, still animating, made of pixels that had slid
 * along their rows.
 *
 * <p>Every check here decodes what the encoder produced and compares the
 * picture, never the bytes. Byte identity with the archive is explicitly not
 * the contract -- see {@link GraphicEncoder} -- so a test that compared
 * entries would fail on art that is perfectly correct, and a test that only
 * confirmed the frame table was written back would have passed on an encoder
 * that emitted no pixels at all.
 */
class GraphicEncoderTest {

    /** The conversion table's sprite rows, counted so an empty sweep cannot pass. */
    private static final int GFX_ROWS = 325;

    private static final int GFU_ROWS = 6;

    @Test
    @DisplayName("every sprite in the game comes back out of the pack unchanged")
    void everySpriteRoundTrips() {
        InstallSource install = install();
        Map<Integer, WarArchive> archives = archives(install);
        GraphicsIndex index = index(archives);

        int gfx = 0;
        int gfu = 0;
        long original = 0;
        long encoded = 0;
        List<String> wrong = new ArrayList<>();

        for (GraphicsIndex.Asset asset : index.assets()) {
            GraphicDecoder.Kind kind = switch (asset.kind()) {
                case GFX -> GraphicDecoder.Kind.GFX;
                case GFU -> GraphicDecoder.Kind.GFU;
                default -> null;
            };
            if (kind == null) {
                continue;
            }
            if (kind == GraphicDecoder.Kind.GFX) {
                gfx++;
            } else {
                gfu++;
            }

            WarArchive archive = archives.get(asset.archive());
            assertTrue(archive != null,
                    asset.path() + " wants archive " + asset.archive() + ", which is not open");
            byte[] entry = archive.entry(asset.entry());

            IndexedImage sheet = GraphicDecoder.decode(kind, entry);
            List<GraphicEncoder.Frame> frames = GraphicEncoder.frames(entry);
            int[] header = GraphicEncoder.header(entry);
            byte[] rebuilt = GraphicEncoder.encode(kind, sheet, frames, header[1], header[2]);
            IndexedImage again = GraphicDecoder.decode(kind, rebuilt);

            original += entry.length;
            encoded += rebuilt.length;

            if (again.width() != sheet.width() || again.height() != sheet.height()) {
                wrong.add(asset.path() + " came back " + again.width() + "x" + again.height()
                        + " instead of " + sheet.width() + "x" + sheet.height());
                continue;
            }
            int differing = countDiffering(sheet.pixels(), again.pixels());
            if (differing != 0) {
                wrong.add(asset.path() + ": " + differing + " of " + sheet.pixels().length
                        + " pixels moved");
            }
            if (kind == GraphicDecoder.Kind.GFX) {
                assertNoEmptyRuns(rebuilt, asset.path());
            }
        }

        assertEquals(GFX_ROWS, gfx, "the conversion table has " + GFX_ROWS
                + " run-length coded sprite rows; this run saw " + gfx);
        assertEquals(GFU_ROWS, gfu, "the conversion table has " + GFU_ROWS
                + " uncompressed sprite rows; this run saw " + gfu);
        assertTrue(wrong.isEmpty(), "sprites that did not survive the pack: " + wrong);

        System.out.println("sprite entries round-tripped: " + (gfx + gfu)
                + " (" + gfx + " GFX, " + gfu + " GFU); archive bytes " + original
                + ", re-encoded bytes " + encoded
                + String.format(" (%.2f%% of the original)", 100.0 * encoded / original));
    }

    @Test
    @DisplayName("a worker carrying his load comes back whole, both halves of him")
    void theTwoEntryWorkerSpritesRoundTrip() {
        // The sweep above says "every sprite in the game" and means every row
        // of the conversion table. Four sprites are not one row. A peasant or
        // peon carrying wood or gold is drawn out of two archive entries, the
        // second picking the frame numbering up at frame 25, and the engine
        // asks for it that way in GameData. Those continuation entries are 47
        // for the human and 48 for the orc, 31026 and 35915 bytes of sprite
        // data the pack has to store and hand back like any other, and
        // GraphicsIndex.assets() does not list them: they are reachable only
        // through Asset.second(), so the sweep walks straight past them.
        //
        // An encoder that mangled them would pass "every sprite in the game"
        // and then every worker on the map would carry his load with somebody
        // else's body from frame 25 on -- which is the whole of the harvesting
        // and repairing cycle, the part of a peasant a player watches all game.
        InstallSource install = install();
        Map<Integer, WarArchive> archives = archives(install);
        GraphicsIndex index = index(archives);

        int checked = 0;
        for (GraphicsIndex.Asset asset : index.assets()) {
            if (asset.kind() != GraphicsIndex.Kind.GFX || asset.second() <= 0) {
                continue;
            }
            checked++;
            WarArchive archive = archives.get(asset.archive());
            byte[] primary = archive.entry(asset.entry());
            byte[] second = archive.entry(asset.second());

            IndexedImage before = GraphicDecoder.decode(GraphicDecoder.Kind.GFX,
                    primary, second, asset.fourth());
            // The pack stores the two entries separately and lets the engine
            // put them back together, so both halves go through the encoder on
            // their own and have to survive being recombined afterwards.
            IndexedImage after = GraphicDecoder.decode(GraphicDecoder.Kind.GFX,
                    repack(primary), repack(second), asset.fourth());

            assertEquals(before.width(), after.width(),
                    asset.path() + " changed width on its way through the pack");
            assertEquals(before.height(), after.height(),
                    asset.path() + " changed height on its way through the pack");
            assertEquals(0, countDiffering(before.pixels(), after.pixels()),
                    asset.path() + " draws different pixels once both of its entries have"
                            + " been through the pack");
        }
        assertEquals(4, checked,
                "the game has four two-entry worker sprites; this run saw " + checked);
    }

    @Test
    @DisplayName("a sheet an artist resized is refused rather than quietly encoded")
    void aResizedSheetIsRefused() {
        // The pack hands the encoder a PNG and a frame table that were written
        // apart from each other, so the two can disagree, and the only thing
        // that notices is the check inside encode. Nothing pinned that check.
        // Drop it and a sheet one cell short still encodes: the frames that
        // fall off the bottom are read out of whatever the rows above held, so
        // the unit is there and animating, made of another pose's pixels, with
        // no exception anywhere for a reader to find.
        IndexedImage sheet = blank(10, 10);
        List<GraphicEncoder.Frame> frames = List.of(
                new GraphicEncoder.Frame(0, 0, 10, 10),
                new GraphicEncoder.Frame(0, 0, 10, 10));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> GraphicEncoder.encode(GraphicDecoder.Kind.GFX, sheet, frames, 10, 10),
                "a 10x10 sheet was accepted for two frames that lay out as 10x20");
        assertTrue(refused.getMessage().contains("10x20"),
                "the refusal does not say what size was wanted: " + refused.getMessage());
    }

    @Test
    @DisplayName("a run-length coded frame too wide for its width field is refused")
    void aGfxFrameWiderThanTheFormatAllowsIsRefused() {
        // GFU keeps a ninth width bit in the top of its offset word, which is
        // how the 300-pixel panel above works. GFX spends that whole word on
        // the row table and has nowhere to put one, so its frames stop at 255.
        // The widest the game ships is 128, so the sweep never goes near the
        // edge; without this check a 300-pixel frame would be written as its
        // low byte and come back 44 pixels wide, an interface panel missing
        // its right-hand three quarters.
        IndexedImage sheet = blank(300, 4);
        List<GraphicEncoder.Frame> frames = List.of(new GraphicEncoder.Frame(0, 0, 300, 4));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> GraphicEncoder.encode(GraphicDecoder.Kind.GFX, sheet, frames, 300, 4),
                "a 300-pixel-wide GFX frame was accepted, and its width does not fit a byte");
        assertTrue(refused.getMessage().contains("255"),
                "the refusal does not name the limit it enforced: " + refused.getMessage());
    }

    @Test
    @DisplayName("a frame that codes past what its row table can address is refused")
    void aFrameThatOutgrowsItsRowTableIsRefused() {
        // A GFX row offset is 16 bits counted from the frame's row table, so a
        // frame's coded pixels have to fit in 64KB. The game's largest frame is
        // 128 by 128 and its largest row offset 12876, a fifth of the way, so
        // the sweep cannot reach this at all. The largest a frame table can
        // describe is 255 by 255, and filled with pixels that never repeat it
        // codes to 66810 bytes and runs out of room at row 251. Unrefused, that
        // offset wraps to a small number and the bottom of the sprite is
        // decoded from the middle of its own first rows: a smear, drawn every
        // frame, with nothing logged.
        int size = 255;
        IndexedImage sheet = blank(size, size);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                // Never two alike in a row, so every pixel costs its own byte,
                // and never 255, which would code as free transparency.
                sheet.set(x, y, 1 + ((x * 31 + y * 7) % 200));
            }
        }
        List<GraphicEncoder.Frame> frames = List.of(new GraphicEncoder.Frame(0, 0, size, size));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> GraphicEncoder.encode(GraphicDecoder.Kind.GFX, sheet, frames, size, size),
                "a frame coding to more than 64KB was accepted, and its row table cannot"
                        + " address a row past 65535");
        assertTrue(refused.getMessage().contains("64KB"),
                "the refusal does not say what overflowed: " + refused.getMessage());
    }

    @Test
    @DisplayName("a frame that is nothing but empty space encodes and decodes without hanging")
    void anEmptyFrameSurvives() {
        // Every unit sheet has cells like this: the death animation's last
        // frames, and the blank cells a padded sheet ends with. A frame of
        // nothing is also the shape that would expose a zero-length run,
        // because there is nothing else in it to advance the decoder's cursor.
        //
        // 255 wide on purpose. A transparent run's length is seven bits, so a
        // row this wide has to be split into 127, 127 and 1, and no frame the
        // game ships can prove that: the widest is 128. Left to the real data
        // an encoder that never split would pass, and then the first
        // wide-screen interface panel an artist drew would decode as the top
        // half of itself with the rest read out of the following row.
        int width = 255;
        int height = 8;
        IndexedImage sheet = blank(width, height);
        List<GraphicEncoder.Frame> frames = List.of(new GraphicEncoder.Frame(0, 0, width, height));
        byte[] entry = GraphicEncoder.encode(GraphicDecoder.Kind.GFX, sheet, frames, width, height);

        assertNoEmptyRuns(entry, "an entirely transparent frame");
        IndexedImage back = GraphicDecoder.decode(GraphicDecoder.Kind.GFX, entry);
        assertArrayEquals(sheet.pixels(), back.pixels(), "an empty frame came back with pixels in it");

        // Three control bytes a row and a row table, not 2040 bytes of index
        // 255. If this regresses the pack still works and simply swells, which
        // is the sort of thing nobody notices for a year.
        assertTrue(entry.length < width * height,
                "an empty frame encoded to " + entry.length + " bytes, more than its raw pixels");
    }

    @Test
    @DisplayName("a frame with no transparency at all keeps every one of its pixels")
    void aFullyOpaqueFrameSurvives() {
        // Terrain-like art with no empty space exercises the other two run
        // types and nothing else: if the encoder leaned on transparent runs to
        // terminate a row, this is the frame it would get wrong.
        //
        // 255 wide for the same reason as the frame above, and for two more.
        // A repeat and a literal both carry six bits of length, so a flat of
        // 100 pixels has to break into 63 and 37, and a stretch of noise 155
        // long into 63, 63 and 29. Neither split can be proved on the shipped
        // art either.
        int width = 255;
        int height = 8;
        IndexedImage sheet = blank(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // A long flat, then single-pixel noise, and never index 255.
                sheet.set(x, y, x < 100 ? 1 + y : 1 + ((x * 7 + y * 13) % 200));
            }
        }
        List<GraphicEncoder.Frame> frames = List.of(new GraphicEncoder.Frame(0, 0, width, height));
        byte[] entry = GraphicEncoder.encode(GraphicDecoder.Kind.GFX, sheet, frames, width, height);

        assertNoEmptyRuns(entry, "a fully opaque frame");
        IndexedImage back = GraphicDecoder.decode(GraphicDecoder.Kind.GFX, entry);
        assertArrayEquals(sheet.pixels(), back.pixels(), "an opaque frame lost or moved pixels");
    }

    @Test
    @DisplayName("a one-pixel frame draws its one pixel")
    void aSinglePixelFrameSurvives() {
        IndexedImage sheet = blank(1, 1);
        sheet.set(0, 0, 42);
        List<GraphicEncoder.Frame> frames = List.of(new GraphicEncoder.Frame(0, 0, 1, 1));
        byte[] entry = GraphicEncoder.encode(GraphicDecoder.Kind.GFX, sheet, frames, 1, 1);

        assertNoEmptyRuns(entry, "a single-pixel frame");
        IndexedImage back = GraphicDecoder.decode(GraphicDecoder.Kind.GFX, entry);
        assertEquals(1, back.width(), "a one-pixel frame decoded to a sheet of the wrong width");
        assertEquals(42, back.get(0, 0), "the one pixel in the frame did not come back");
    }

    @Test
    @DisplayName("a three-hundred-pixel-wide panel keeps its right-hand edge")
    void aFrameWiderThanTwoHundredAndFiftyFiveSurvives() {
        // Four of the game's uncompressed frames are wider than a byte can
        // say, the widest being 300. The width's ninth bit lives in the top
        // bit of the frame's offset word; drop it and the panel comes back 44
        // pixels wide, which is the whole interface missing its right-hand
        // three quarters.
        int width = 300;
        int height = 4;
        IndexedImage sheet = blank(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                sheet.set(x, y, (x + y) % 256);
            }
        }
        List<GraphicEncoder.Frame> frames = List.of(new GraphicEncoder.Frame(0, 0, width, height));
        byte[] entry = GraphicEncoder.encode(GraphicDecoder.Kind.GFU, sheet, frames, width, height);

        IndexedImage back = GraphicDecoder.decode(GraphicDecoder.Kind.GFU, entry);
        assertEquals(width, back.width(), "the wide frame came back " + back.width() + " pixels wide");
        assertArrayEquals(sheet.pixels(), back.pixels(), "the wide frame lost pixels");
        assertEquals((299 + 3) % 256, back.get(299, 3), "the far corner of the wide frame is missing");
    }

    @Test
    @DisplayName("a sheet of three frames is read back down one column, not across five")
    void aShortSheetStacksInOneColumn() {
        // Under five frames the decoder puts them in a single column; at five
        // and over it puts five to a row and pads. The encoder has to read
        // them back out of whichever layout the decoder wrote, and the two
        // cases have to be told apart: a three-frame sheet read as five across
        // hands frame 1 the empty space to the right of frame 0, so a
        // three-frame explosion would play as one puff and two blanks.
        int cell = 10;
        IndexedImage sheet = blank(cell, cell * 3);
        List<GraphicEncoder.Frame> frames = new ArrayList<>();
        for (int frame = 0; frame < 3; frame++) {
            for (int y = 0; y < cell; y++) {
                for (int x = 0; x < cell; x++) {
                    sheet.set(x, frame * cell + y, 10 + frame);
                }
            }
            frames.add(new GraphicEncoder.Frame(0, 0, cell, cell));
        }
        byte[] entry = GraphicEncoder.encode(GraphicDecoder.Kind.GFX, sheet, frames, cell, cell);

        assertNoEmptyRuns(entry, "a three-frame sheet");
        IndexedImage back = GraphicDecoder.decode(GraphicDecoder.Kind.GFX, entry);
        assertEquals(cell, back.width(), "a three-frame sheet is one cell wide");
        assertEquals(cell * 3, back.height(), "a three-frame sheet is three cells tall");
        assertArrayEquals(sheet.pixels(), back.pixels(), "the three-frame sheet came back scrambled");
        for (int frame = 0; frame < 3; frame++) {
            assertEquals(10 + frame, back.get(5, frame * cell + 5),
                    "frame " + frame + " of the single-column sheet holds the wrong picture");
        }
    }

    @Test
    @DisplayName("a sheet of six frames is read back five across, with the row padded out")
    void aLongSheetGoesFiveAcross() {
        // The control for the case above. A fixture of three frames alone
        // cannot tell "always one column" from "one column under five", so it
        // proves nothing about which rule the encoder implements.
        int cell = 8;
        IndexedImage sheet = blank(cell * 5, cell * 2);
        List<GraphicEncoder.Frame> frames = new ArrayList<>();
        for (int frame = 0; frame < 6; frame++) {
            int cellX = (frame % 5) * cell;
            int cellY = (frame / 5) * cell;
            for (int y = 0; y < cell; y++) {
                for (int x = 0; x < cell; x++) {
                    sheet.set(cellX + x, cellY + y, 20 + frame);
                }
            }
            frames.add(new GraphicEncoder.Frame(0, 0, cell, cell));
        }
        byte[] entry = GraphicEncoder.encode(GraphicDecoder.Kind.GFX, sheet, frames, cell, cell);

        assertNoEmptyRuns(entry, "a six-frame sheet");
        IndexedImage back = GraphicDecoder.decode(GraphicDecoder.Kind.GFX, entry);
        assertEquals(cell * 5, back.width(), "a six-frame sheet is five cells wide");
        assertEquals(cell * 2, back.height(), "a six-frame sheet is padded to two whole rows");
        assertArrayEquals(sheet.pixels(), back.pixels(), "the six-frame sheet came back scrambled");
        assertEquals(25, back.get(2, cell + 2), "frame 5 is not at the start of the second row");
    }

    @Test
    @DisplayName("a pose a unit returns to is stored once and still draws in both places")
    void repeatedFramesAreSharedAndBothStillDraw() {
        // A walk cycle that comes back to the pose it started in holds the
        // same picture twice, and 616 of the 3125 frames in the shipped
        // sprites do: the archive gives both frames the same row table. The
        // encoder does the same, and if it ever handed a frame the wrong
        // neighbour's pixels a unit would snap to another pose mid-stride,
        // every stride, on the one frame nobody looks at twice.
        int cell = 8;
        List<GraphicEncoder.Frame> frames = new ArrayList<>();
        for (int frame = 0; frame < 6; frame++) {
            frames.add(new GraphicEncoder.Frame(0, 0, cell, cell));
        }

        IndexedImage repeated = blank(cell * 5, cell * 2);
        IndexedImage distinct = blank(cell * 5, cell * 2);
        for (int frame = 0; frame < 6; frame++) {
            paint(repeated, frame, cell, frame % 2);
            paint(distinct, frame, cell, frame);
        }

        byte[] shared = GraphicEncoder.encode(GraphicDecoder.Kind.GFX, repeated, frames, cell, cell);
        byte[] unshared = GraphicEncoder.encode(GraphicDecoder.Kind.GFX, distinct, frames, cell, cell);

        IndexedImage back = GraphicDecoder.decode(GraphicDecoder.Kind.GFX, shared);
        assertArrayEquals(repeated.pixels(), back.pixels(),
                "a sheet with repeated poses came back with a frame holding the wrong picture");

        // Six frames of two pictures have to cost less than six frames of six,
        // or nothing was shared and the check above proved only that the
        // encoder can write the same thing twice.
        assertTrue(shared.length < unshared.length,
                "six frames of two pictures encoded to " + shared.length
                        + " bytes and six of six to " + unshared.length
                        + ", so identical frames were written out twice");
    }

    @Test
    @DisplayName("an encoder that emitted an empty run would be caught")
    void theEmptyRunCheckCatchesAnEmptyRun() {
        // The inverted control. Every check above scans for a run of length
        // zero; this proves the scan can fail. A zero-length transparent run
        // is legal to the decoder's switch and advances its cursor by nothing,
        // so the row loop never reaches the frame's width and the game stops
        // dead on the first sprite it draws, with no exception and nothing in
        // the log.
        byte[] entry = new byte[] {
            1, 0,             // one frame
            4, 0,             // maxWidth
            1, 0,             // maxHeight
            0, 0, 4, 1,       // xOffset, yOffset, width, height
            14, 0, 0, 0,      // row table offset
            2, 0,             // row 0 starts two bytes into the row table
            (byte) 0x80,      // a transparent run of zero pixels
            (byte) 0x84       // and then four real ones
        };
        AssertionError caught = assertThrows(AssertionError.class,
                () -> assertNoEmptyRuns(entry, "a hand-built entry"),
                "the empty-run scan passed an entry that contains an empty run");
        assertTrue(caught.getMessage().contains("run of zero"),
                "the scan failed for the wrong reason: " + caught.getMessage());
    }

    // -------------------------------------------------------------- fixtures

    /** Fills one cell of a five-across sheet with a pattern that {@code seed} makes distinctive. */
    private static void paint(IndexedImage sheet, int frame, int cell, int seed) {
        int cellX = (frame % 5) * cell;
        int cellY = (frame / 5) * cell;
        for (int y = 0; y < cell; y++) {
            for (int x = 0; x < cell; x++) {
                sheet.set(cellX + x, cellY + y, 1 + ((x * 3 + y * 5 + seed * 31) % 200));
            }
        }
    }

    /** One entry through the pack and back: decoded to a sheet, then re-encoded. */
    private static byte[] repack(byte[] entry) {
        int[] header = GraphicEncoder.header(entry);
        IndexedImage sheet = GraphicDecoder.decode(GraphicDecoder.Kind.GFX, entry);
        return GraphicEncoder.encode(GraphicDecoder.Kind.GFX, sheet,
                GraphicEncoder.frames(entry), header[1], header[2]);
    }

    /** A sheet with nothing on it, which is what the decoder starts from. */
    private static IndexedImage blank(int width, int height) {
        IndexedImage sheet = new IndexedImage(width, height);
        sheet.fill(Palette.TRANSPARENT_INDEX);
        return sheet;
    }

    private static int countDiffering(byte[] expected, byte[] actual) {
        int differing = 0;
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                differing++;
            }
        }
        return differing;
    }

    /**
     * Walks a run-length coded entry the way the decoder does and fails on any
     * run that covers no pixels.
     *
     * <p>Scanned rather than inferred from a timeout, because the symptom of
     * the fault is a loop that does not end: a test that simply decoded the
     * entry would hang instead of failing.
     */
    private static void assertNoEmptyRuns(byte[] entry, String what) {
        int frameCount = readLe16(entry, 0);
        for (int frame = 0; frame < frameCount; frame++) {
            int header = 6 + frame * 8;
            int width = entry[header + 2] & 0xFF;
            int height = entry[header + 3] & 0xFF;
            int rowTable = readLe32(entry, header + 4);
            for (int row = 0; row < height; row++) {
                int cursor = rowTable + readLe16(entry, rowTable + row * 2);
                int x = 0;
                while (x < width) {
                    int control = entry[cursor++] & 0xFF;
                    int run;
                    if ((control & 0x80) != 0) {
                        run = control & 0x7F;
                    } else if ((control & 0x40) != 0) {
                        run = control & 0x3F;
                        cursor++;
                    } else {
                        run = control & 0x3F;
                        cursor += run;
                    }
                    assertTrue(run > 0, what + ": frame " + frame + " row " + row
                            + " holds a run of zero pixels at x " + x
                            + ", which would leave the decoder's row loop turning forever");
                    x += run;
                }
            }
        }
    }

    private static int readLe16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int readLe32(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static InstallSource install() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return install;
    }

    /** Every archive the sprite rows index into, keyed by the id the table names. */
    private static Map<Integer, WarArchive> archives(InstallSource install) {
        Map<Integer, WarArchive> archives = new LinkedHashMap<>();
        for (int archiveId : List.of(
                ArchiveIds.MAINDAT, ArchiveIds.REZDAT, ArchiveIds.STRDAT)) {
            Path file = install.archivePath(archiveId);
            Assumptions.assumeTrue(file != null, "no archive " + archiveId);
            archives.put(archiveId, WarArchive.open(file, archiveId));
        }
        return archives;
    }

    private static GraphicsIndex index(Map<Integer, WarArchive> archives) {
        return GraphicsIndex.load(NameTable.from(
                archives.get(ArchiveIds.STRDAT).entry(1)));
    }
}
