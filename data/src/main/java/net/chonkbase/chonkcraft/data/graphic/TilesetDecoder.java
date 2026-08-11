package net.chonkbase.chonkcraft.data.graphic;

/**
 * Builds a terrain tile sheet from Warcraft II's two-level tile format.
 *
 * <p>Implements {@code ConvertTile} and {@code DecodeMiniTile}.
 *
 * <p>Terrain is stored twice-indirected to save space. The <em>minitile</em>
 * entry is a flat run of 8x8 pixel blocks, 64 bytes each. The <em>megatile</em>
 * entry describes 32x32 terrain tiles as 4x4 grids of minitile references, 16
 * references of two bytes each. A reference packs the minitile index in its
 * top fourteen bits and two mirroring flags in its low two, so a single stored
 * block serves up to four orientations. That is how a whole tileset fits in a
 * few tens of kilobytes.
 *
 * <p>The first sixteen tiles are the fog of war, which is not built from
 * minitiles: those are stored as complete 32x32 images at the head of the
 * minitile entry.
 */
public final class TilesetDecoder {

    /** Tiles per row on the output sheet, matching {@code TILE_PER_ROW}. */
    public static final int TILES_PER_ROW = 16;

    /** Edge length of a terrain tile, in pixels. */
    public static final int TILE_SIZE = 32;

    /** Edge length of a minitile, in pixels. */
    private static final int MINITILE_SIZE = 8;

    /** Bytes per minitile: 8 by 8, one byte per pixel. */
    private static final int MINITILE_BYTES = MINITILE_SIZE * MINITILE_SIZE;

    /** Bytes per megatile: sixteen 2-byte minitile references. */
    private static final int MEGATILE_BYTES = 32;

    /** Leading tiles stored as whole 32x32 images rather than minitile grids. */
    private static final int FOG_TILE_COUNT = 16;

    private TilesetDecoder() {
    }

    /**
     * Decodes a tileset into one sheet, sixteen tiles per row.
     *
     * @param minitiles the minitile entry: 8x8 blocks, preceded by the fog tiles
     * @param megatiles the megatile entry: 32 bytes per terrain tile
     */
    public static IndexedImage decode(byte[] minitiles, byte[] megatiles) {
        int tileCount = megatiles.length / MEGATILE_BYTES;
        if (tileCount == 0) {
            throw new IllegalArgumentException("megatile entry is too short to hold a tile");
        }

        int width = TILES_PER_ROW * TILE_SIZE;
        int height = ((tileCount + TILES_PER_ROW - 1) / TILES_PER_ROW) * TILE_SIZE;
        IndexedImage sheet = new IndexedImage(width, height);

        for (int tile = 0; tile < tileCount; tile++) {
            int tileX = (tile % TILES_PER_ROW) * TILE_SIZE;
            int tileY = (tile / TILES_PER_ROW) * TILE_SIZE;

            if (tile < FOG_TILE_COUNT) {
                copyFogTile(minitiles, tile, sheet, tileX, tileY);
                continue;
            }

            for (int cellY = 0; cellY < 4; cellY++) {
                for (int cellX = 0; cellX < 4; cellX++) {
                    int reference = readLe16(megatiles, tile * MEGATILE_BYTES + (cellX + cellY * 4) * 2);
                    // The low two bits are mirroring flags; the rest is the
                    // minitile index, already shifted so that masking them off
                    // and multiplying by 16 gives the byte address.
                    int address = (reference & 0xFFFC) * 16;
                    boolean mirrorX = (reference & 2) != 0;
                    boolean mirrorY = (reference & 1) != 0;
                    blitMinitile(minitiles, address, sheet,
                            tileX + cellX * MINITILE_SIZE, tileY + cellY * MINITILE_SIZE,
                            mirrorX, mirrorY);
                }
            }
        }
        return sheet;
    }

    /** Number of terrain tiles a megatile entry describes. */
    public static int tileCount(byte[] megatiles) {
        return megatiles.length / MEGATILE_BYTES;
    }

    private static void copyFogTile(byte[] minitiles, int tile, IndexedImage sheet, int tileX, int tileY) {
        int base = tile * TILE_SIZE * TILE_SIZE;
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                sheet.set(tileX + x, tileY + y, minitiles[base + y * TILE_SIZE + x] & 0xFF);
            }
        }
    }

    private static void blitMinitile(byte[] minitiles, int address, IndexedImage sheet,
            int destX, int destY, boolean mirrorX, boolean mirrorY) {
        for (int y = 0; y < MINITILE_SIZE; y++) {
            int sourceY = mirrorY ? MINITILE_SIZE - 1 - y : y;
            for (int x = 0; x < MINITILE_SIZE; x++) {
                int sourceX = mirrorX ? MINITILE_SIZE - 1 - x : x;
                sheet.set(destX + x, destY + y,
                        minitiles[address + sourceY * MINITILE_SIZE + sourceX] & 0xFF);
            }
        }
    }

    private static int readLe16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }
}
