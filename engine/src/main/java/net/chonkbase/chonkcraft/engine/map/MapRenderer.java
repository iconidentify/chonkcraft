package net.chonkbase.chonkcraft.engine.map;

import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.data.graphic.TilesetDecoder;

/**
 * Draws a terrain grid into an indexed image.
 *
 * <p>Stays in palette indices rather than resolved colour, because the
 * tileset's water and fire animate by cycling index ranges at display time.
 * Resolving here would freeze them.
 */
public final class MapRenderer {

    private final Tileset tileset;
    private final IndexedImage tileSheet;
    private final int tilesPerRow;

    /**
     * @param tileset   the code-to-graphic table
     * @param tileSheet the decoded tile sheet the tileset's graphic indices address
     */
    public MapRenderer(Tileset tileset, IndexedImage tileSheet) {
        this.tileset = tileset;
        this.tileSheet = tileSheet;
        this.tilesPerRow = tileSheet.width() / TilesetDecoder.TILE_SIZE;
        if (tilesPerRow <= 0) {
            throw new IllegalArgumentException("tile sheet is narrower than one tile");
        }
    }

    /**
     * Renders a whole map.
     *
     * @param width  map width in tiles
     * @param height map height in tiles
     * @param codes  {@code width * height} tile codes, row-major
     */
    public IndexedImage render(int width, int height, int[] codes) {
        int size = TilesetDecoder.TILE_SIZE;
        IndexedImage out = new IndexedImage(width * size, height * size);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                drawTile(out, x * size, y * size, tileset.graphicFor(codes[y * width + x]));
            }
        }
        return out;
    }

    /** Draws one tile graphic at a pixel position. */
    public void drawTile(IndexedImage target, int destX, int destY, int graphic) {
        int size = TilesetDecoder.TILE_SIZE;
        int sourceX = (graphic % tilesPerRow) * size;
        int sourceY = (graphic / tilesPerRow) * size;
        if (sourceY + size > tileSheet.height()) {
            // A code pointing past the sheet: leave the square as it is rather
            // than throwing, which is what the engine does with a bad tile.
            return;
        }
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                target.set(destX + x, destY + y, tileSheet.get(sourceX + x, sourceY + y));
            }
        }
    }

    /** How many tiles the sheet holds per row. */
    public int tilesPerRow() {
        return tilesPerRow;
    }
}
