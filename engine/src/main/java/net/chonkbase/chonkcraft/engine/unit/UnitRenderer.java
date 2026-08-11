package net.chonkbase.chonkcraft.engine.unit;

import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.data.graphic.Palette;
import net.chonkbase.chonkcraft.data.graphic.TilesetDecoder;

/**
 * Draws unit sprites onto a rendered map.
 *
 * <p>A unit occupies a whole number of tiles but its sprite is usually larger
 * than that footprint: a footman stands on one 32 by 32 tile and is drawn from
 * a 72 by 72 frame, so the picture overhangs the square it owns on every side.
 * Centring the frame on the footprint is what puts it in the right place.
 */
public final class UnitRenderer {

    private final Palette palette;

    public UnitRenderer(Palette palette) {
        this.palette = palette;
    }

    /**
     * Draws one frame of a unit's sprite at a tile position.
     *
     * @param target     the map image being drawn into
     * @param sheet      the unit's decoded sprite sheet
     * @param type       the unit type, for its frame and footprint sizes
     * @param tileX      tile column of the unit's top-left corner
     * @param tileY      tile row of the unit's top-left corner
     * @param frame      which animation frame to draw
     */
    public void draw(IndexedImage target, IndexedImage sheet, UnitType type, int tileX, int tileY, int frame) {
        int frameWidth = type.imageWidth() > 0 ? type.imageWidth() : TilesetDecoder.TILE_SIZE;
        int frameHeight = type.imageHeight() > 0 ? type.imageHeight() : TilesetDecoder.TILE_SIZE;

        int columns = Math.max(1, sheet.width() / frameWidth);
        int rows = Math.max(1, sheet.height() / frameHeight);
        int total = columns * rows;
        if (total == 0) {
            return;
        }
        int clamped = Math.floorMod(frame, total);
        int sourceX = (clamped % columns) * frameWidth;
        int sourceY = (clamped / columns) * frameHeight;

        // Centre the frame over the footprint.
        int footprintWidth = Math.max(1, type.tileWidth()) * TilesetDecoder.TILE_SIZE;
        int footprintHeight = Math.max(1, type.tileHeight()) * TilesetDecoder.TILE_SIZE;
        int destX = tileX * TilesetDecoder.TILE_SIZE + (footprintWidth - frameWidth) / 2;
        int destY = tileY * TilesetDecoder.TILE_SIZE + (footprintHeight - frameHeight) / 2;

        blit(target, sheet, sourceX, sourceY, frameWidth, frameHeight, destX, destY);
    }

    /** Copies a frame, skipping transparent pixels and clipping at the edges. */
    private void blit(IndexedImage target, IndexedImage sheet,
            int sourceX, int sourceY, int width, int height, int destX, int destY) {
        for (int y = 0; y < height; y++) {
            int sy = sourceY + y;
            int dy = destY + y;
            if (sy >= sheet.height() || dy < 0 || dy >= target.height()) {
                continue;
            }
            for (int x = 0; x < width; x++) {
                int sx = sourceX + x;
                int dx = destX + x;
                if (sx >= sheet.width() || dx < 0 || dx >= target.width()) {
                    continue;
                }
                int index = sheet.get(sx, sy);
                if (index == Palette.TRANSPARENT_INDEX) {
                    continue;
                }
                target.set(dx, dy, index);
            }
        }
    }

    /** The palette units are drawn with. */
    public Palette palette() {
        return palette;
    }
}
