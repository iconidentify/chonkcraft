package net.chonkbase.chonkcraft.data.graphic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.archive.WarArchive;
import net.chonkbase.chonkcraft.data.source.ArchiveIds;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Decodes real sprites and tilesets.
 *
 * <p>Entry indices come from the {@code Todo} table in {@code wartool.h}:
 * entry 2 is the forest palette, 33 onward are unit sheets
 * ({@code wartool.h:288}), and the tilesets are the four-entry groups at
 * {@code wartool.h:280} onward.
 */
class GraphicRealDataTest {

    /** Palette, megatiles, minitiles for the three tilesets in the base game. */
    private static final int[][] TILESETS = {
        {2, 3, 4},    // forest
        {10, 11, 12}, // wasteland
        {18, 19, 20}  // winter
    };

    private static WarArchive mainArchive() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return WarArchive.open(install.archivePath(ArchiveIds.MAINDAT), ArchiveIds.MAINDAT);
    }

    @Test
    @DisplayName("unit sprite sheets decode with the expected frame layout")
    void unitSpriteSheetsDecode() {
        WarArchive main = mainArchive();

        for (int entry = 33; entry <= 38; entry++) {
            byte[] data = main.entry(entry);
            int frames = GraphicDecoder.frameCount(data);
            assertTrue(frames > 0 && frames < 500, "entry " + entry + " declares " + frames + " frames");

            IndexedImage sheet = GraphicDecoder.decode(GraphicDecoder.Kind.GFX, data);

            // Five frames to a row once there are at least five of them, so
            // the sheet width is a whole number of cells.
            int framesPerRow = frames < 5 ? 1 : 5;
            assertEquals(0, sheet.width() % framesPerRow,
                    "entry " + entry + ": width " + sheet.width() + " is not " + framesPerRow + " whole cells");

            int rows = (frames + framesPerRow - 1) / framesPerRow;
            int cellHeight = sheet.height() / rows;
            assertEquals(rows * cellHeight, sheet.height(), "entry " + entry + ": ragged sheet height");

            // A real sprite sheet is neither blank nor fully opaque.
            int opaque = 0;
            for (byte pixel : sheet.pixels()) {
                if ((pixel & 0xFF) != Palette.TRANSPARENT_INDEX) {
                    opaque++;
                }
            }
            assertTrue(opaque > 0, "entry " + entry + " decoded to nothing but transparency");
            assertTrue(opaque < sheet.pixels().length,
                    "entry " + entry + " has no transparency at all, which no unit sheet does");
        }
    }

    @Test
    @DisplayName("tilesets decode to a full 16-wide sheet of 32x32 tiles")
    void tilesetsDecode() {
        WarArchive main = mainArchive();

        for (int[] set : TILESETS) {
            byte[] megatiles = main.entry(set[1]);
            byte[] minitiles = main.entry(set[2]);

            int tiles = TilesetDecoder.tileCount(megatiles);
            // Every Warcraft II tileset defines a few hundred terrain tiles.
            assertTrue(tiles > 300 && tiles < 600, "tileset entry " + set[1] + " has " + tiles + " tiles");

            IndexedImage sheet = TilesetDecoder.decode(minitiles, megatiles);
            assertEquals(TilesetDecoder.TILES_PER_ROW * TilesetDecoder.TILE_SIZE, sheet.width());
            int rows = (tiles + TilesetDecoder.TILES_PER_ROW - 1) / TilesetDecoder.TILES_PER_ROW;
            assertEquals(rows * TilesetDecoder.TILE_SIZE, sheet.height());

            // Terrain is opaque: no tile in the addressed area should be left
            // at the fill value, which would mean a minitile never got written.
            int addressed = tiles * TilesetDecoder.TILE_SIZE * TilesetDecoder.TILE_SIZE;
            int untouched = 0;
            for (int tile = 0; tile < tiles; tile++) {
                int tileX = (tile % TilesetDecoder.TILES_PER_ROW) * TilesetDecoder.TILE_SIZE;
                int tileY = (tile / TilesetDecoder.TILES_PER_ROW) * TilesetDecoder.TILE_SIZE;
                for (int y = 0; y < TilesetDecoder.TILE_SIZE; y++) {
                    for (int x = 0; x < TilesetDecoder.TILE_SIZE; x++) {
                        if (sheet.get(tileX + x, tileY + y) == 0) {
                            untouched++;
                        }
                    }
                }
            }
            // Index 0 is a legitimate colour, so allow some, but a decoder
            // that failed to blit would leave nearly everything at zero.
            assertTrue(untouched < addressed / 4,
                    "tileset entry " + set[1] + ": " + untouched + " of " + addressed
                            + " pixels are index 0, which suggests minitiles were not written");
        }
    }

    @Test
    @DisplayName("palettes scale six-bit components the way wartool does")
    void palettesScaleToEightBits() {
        WarArchive main = mainArchive();
        byte[] raw = main.entry(2);
        assertTrue(Palette.looksLikeVga(raw), "entry 2 is not a 6-bit VGA palette");

        Palette palette = Palette.fromVga(raw);
        for (int i = 0; i < 256; i++) {
            assertEquals((raw[i * 3] & 0xFF) << 2, palette.red(i));
            assertEquals((raw[i * 3 + 1] & 0xFF) << 2, palette.green(i));
            assertEquals((raw[i * 3 + 2] & 0xFF) << 2, palette.blue(i));
        }
        // Index 255 resolves to fully transparent regardless of its colour.
        assertEquals(0, palette.argb(Palette.TRANSPARENT_INDEX));
    }
}
