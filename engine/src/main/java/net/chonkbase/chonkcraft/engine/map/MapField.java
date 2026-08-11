package net.chonkbase.chonkcraft.engine.map;

/**
 * One square of the game map.
 *
 * <p>Implements {@code CMapField}. Holds the
 * tile's graphic, the terrain flags it inherited from the tileset, and the
 * occupancy bits that units set as they move.
 *
 * <p>Occupancy lives in the same flag word as terrain because that is what
 * makes a passability test a single mask: a footman may enter a square when
 * neither the terrain nor anything standing on it blocks land movement.
 */
public final class MapField {

    private int tile;
    private long flags;
    private int value;

    /** The tile code, which the tileset turns into a graphic. */
    public int tile() {
        return tile;
    }

    public void setTile(int tile) {
        this.tile = tile;
    }

    /** Terrain and occupancy flags together. */
    public long flags() {
        return flags;
    }

    public void setFlags(long flags) {
        this.flags = flags;
    }

    public void addFlags(long mask) {
        flags |= mask;
    }

    public void removeFlags(long mask) {
        flags &= ~mask;
    }

    public boolean hasFlag(long mask) {
        return (flags & mask) != 0;
    }

    /**
     * The square's spare value: wall hit points, or how much wood a forest
     * square still holds.
     */
    public int value() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }

    /** Whether a land unit may stand here. */
    public boolean isLandPassable() {
        return hasFlag(TileFlag.LAND_ALLOWED) && !hasFlag(TileFlag.UNPASSABLE);
    }

    /** Whether a naval unit may float here. */
    public boolean isWaterPassable() {
        return hasFlag(TileFlag.WATER_ALLOWED) && !hasFlag(TileFlag.UNPASSABLE);
    }

    /** Whether anything at all occupies the square. */
    public boolean isOccupied() {
        return hasFlag(TileFlag.LAND_UNIT | TileFlag.SEA_UNIT | TileFlag.BUILDING);
    }

    /** Whether the square holds trees that can be chopped. */
    public boolean isForest() {
        return hasFlag(TileFlag.FOREST);
    }

    /** Whether the square holds a wall. */
    public boolean isWall() {
        return hasFlag(TileFlag.WALL);
    }
}
