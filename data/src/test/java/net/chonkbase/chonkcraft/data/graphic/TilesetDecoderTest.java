package net.chonkbase.chonkcraft.data.graphic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Unit tests for the two-level terrain tile format. */
class TilesetDecoderTest {

    private static final int FOG_TILES = 16;
    private static final int MINITILE_BYTES = 64;

    /**
     * Builds a minitile entry: sixteen 32x32 fog tiles, then {@code minitiles}
     * blocks of 8x8. Block {@code n} is filled with a distinct value so its
     * identity and orientation are both visible in the output.
     */
    private static byte[] minitiles(int count) {
        int fogBytes = FOG_TILES * 32 * 32;
        byte[] data = new byte[fogBytes + count * MINITILE_BYTES];
        for (int block = 0; block < count; block++) {
            int base = fogBytes + block * MINITILE_BYTES;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    // Encode the corner position so mirroring is detectable:
                    // top-left is 1, top-right 2, bottom-left 3, bottom-right 4.
                    int quadrant = (y < 4 ? 0 : 2) + (x < 4 ? 1 : 2);
                    data[base + y * 8 + x] = (byte) (block * 10 + quadrant);
                }
            }
        }
        return data;
    }

    /** A megatile entry of {@code tiles} tiles, every cell referencing one minitile. */
    private static byte[] megatiles(int tiles, int minitileIndex, boolean mirrorX, boolean mirrorY) {
        byte[] data = new byte[tiles * 32];
        // The reference stores the index already shifted left by two, with the
        // low bits carrying the flags. Byte address is (reference & 0xFFFC) * 16,
        // and the entry's minitiles begin after the fog tiles.
        int blockAddress = FOG_TILES * 32 * 32 + minitileIndex * MINITILE_BYTES;
        int reference = (blockAddress / 16) | (mirrorX ? 2 : 0) | (mirrorY ? 1 : 0);
        for (int tile = 0; tile < tiles; tile++) {
            for (int cell = 0; cell < 16; cell++) {
                int at = tile * 32 + cell * 2;
                data[at] = (byte) (reference & 0xFF);
                data[at + 1] = (byte) ((reference >>> 8) & 0xFF);
            }
        }
        return data;
    }

    @Test
    void sheetIsSixteenTilesWide() {
        IndexedImage sheet = TilesetDecoder.decode(minitiles(4), megatiles(FOG_TILES + 20, 0, false, false));
        assertEquals(16 * 32, sheet.width());
        // 36 tiles pad to three rows of sixteen.
        assertEquals(3 * 32, sheet.height());
    }

    @Test
    void buildsATileFromItsFourByFourMinitileGrid() {
        IndexedImage sheet = TilesetDecoder.decode(minitiles(4), megatiles(FOG_TILES + 1, 2, false, false));

        // Tile 16 is the first non-fog tile, at column 0 of row 1.
        int tileX = 0;
        int tileY = 32;
        // Minitile 2, top-left quadrant, is 2 * 10 + 1.
        assertEquals(21, sheet.get(tileX, tileY));
        // Its top-right quadrant, four pixels along.
        assertEquals(22, sheet.get(tileX + 4, tileY));
        // The neighbouring 8x8 cell holds the same block, so the pattern repeats.
        assertEquals(21, sheet.get(tileX + 8, tileY));
    }

    @Test
    void mirrorsHorizontally() {
        IndexedImage plain = TilesetDecoder.decode(minitiles(1), megatiles(FOG_TILES + 1, 0, false, false));
        IndexedImage mirrored = TilesetDecoder.decode(minitiles(1), megatiles(FOG_TILES + 1, 0, true, false));

        int tileY = 32;
        // Left and right quadrants swap: 1 and 2 become 2 and 1.
        assertEquals(1, plain.get(0, tileY));
        assertEquals(2, plain.get(4, tileY));
        assertEquals(2, mirrored.get(0, tileY));
        assertEquals(1, mirrored.get(4, tileY));
    }

    @Test
    void mirrorsVertically() {
        IndexedImage plain = TilesetDecoder.decode(minitiles(1), megatiles(FOG_TILES + 1, 0, false, true));

        int tileY = 32;
        // Top and bottom quadrants swap: the top row now reads the bottom block.
        assertEquals(3, plain.get(0, tileY));
        assertEquals(1, plain.get(0, tileY + 4));
    }

    @Test
    void copiesFogTilesVerbatim() {
        byte[] mini = minitiles(1);
        // Paint a marker into fog tile 3's top-left pixel and its last pixel.
        int fogBase = 3 * 32 * 32;
        mini[fogBase] = (byte) 200;
        mini[fogBase + 31 * 32 + 31] = (byte) 201;

        IndexedImage sheet = TilesetDecoder.decode(mini, megatiles(FOG_TILES + 1, 0, false, false));
        int tileX = 3 * 32;
        assertEquals(200, sheet.get(tileX, 0));
        assertEquals(201, sheet.get(tileX + 31, 31));
    }

    @Test
    void reportsTileCount() {
        assertEquals(36, TilesetDecoder.tileCount(megatiles(36, 0, false, false)));
    }

    @Test
    void rejectsAnEmptyMegatileEntry() {
        assertThrows(IllegalArgumentException.class, () -> TilesetDecoder.decode(minitiles(1), new byte[16]));
    }
}
