package net.chonkbase.chonkcraft.engine.map;

/**
 * Per-tile terrain properties.
 *
 * <p>Values are transcribed from {@code tileset.h} and must stay identical:
 * saved games, the map editor, and the pathfinder all compare raw flag words.
 * A 64-bit field, because the top sixteen bits carry the per-subtile
 * impassability mask.
 */
public final class TileFlag {

    private TileFlag() {
    }

    /** Movement speed class; two bits, unused by ChonkCraft. */
    public static final long SPEED_MASK = 0x0000_0000_0000_0003L;

    /** Blocks line of sight for fog of war and field of view. */
    public static final long OPAQUE = 0x0000_0000_0000_0004L;

    /** A human-owned field. Only walls use this. */
    public static final long HUMAN = 0x0000_0000_0000_0008L;

    public static final long LAND_ALLOWED = 0x0000_0000_0000_0010L;
    public static final long COAST_ALLOWED = 0x0000_0000_0000_0020L;
    public static final long WATER_ALLOWED = 0x0000_0000_0000_0040L;
    public static final long NO_BUILDING = 0x0000_0000_0000_0080L;

    public static final long UNPASSABLE = 0x0000_0000_0000_0100L;
    public static final long WALL = 0x0000_0000_0000_0200L;
    public static final long ROCKS = 0x0000_0000_0000_0400L;
    public static final long FOREST = 0x0000_0000_0000_0800L;

    public static final long LAND_UNIT = 0x0000_0000_0000_1000L;
    public static final long AIR_UNIT = 0x0000_0000_0000_2000L;
    public static final long SEA_UNIT = 0x0000_0000_0000_4000L;
    public static final long BUILDING = 0x0000_0000_0000_8000L;

    /** Needs no edge mixing; an editor concern. */
    public static final long DECORATIVE = 0x0000_0000_0001_0000L;

    // Harvestable terrain that yields an alternate resource. Each implies
    // FOREST, exactly as the C++ constants do.
    public static final long COST4 = 0x0000_0000_0002_0000L | FOREST;
    public static final long COST5 = 0x0000_0000_0004_0000L | FOREST;
    public static final long COST6 = 0x0000_0000_0008_0000L | FOREST;

    /** Marks a tile sharing a subslot with unrelated tiles; editor randomisation reads it. */
    public static final long FROM_UNSEPARATED_SLOT = 0x0000_0000_1000_0000L;

    /** Does not mix with neighbours. */
    public static final long NON_MIXING = 0x0000_0000_8000_0000L;

    /** Where the 16-bit subtile impassability mask starts. */
    public static final int SUBTILES_UNPASSABLE_SHIFT = 48;

    public static final long SUBTILES_UNPASSABLE_MASK = 0xFFFFL << SUBTILES_UNPASSABLE_SHIFT;

    /** First tile index of the extended tileset range. */
    public static final int EXTENDED_TILESET_BEGIN = 0x1010;

    /** Resolves a flag name as written in a tileset script, or 0 if unknown. */
    public static long byName(String name) {
        return switch (name) {
            case "water" -> WATER_ALLOWED;
            case "land" -> LAND_ALLOWED;
            case "coast" -> COAST_ALLOWED;
            case "no-building" -> NO_BUILDING;
            case "unpassable" -> UNPASSABLE;
            case "wall" -> WALL;
            case "rock" -> ROCKS;
            case "forest" -> FOREST;
            case "cost4" -> COST4;
            case "cost5" -> COST5;
            case "cost6" -> COST6;
            case "land-unit" -> LAND_UNIT;
            case "air-unit" -> AIR_UNIT;
            case "sea-unit" -> SEA_UNIT;
            case "building" -> BUILDING;
            case "human" -> HUMAN;
            case "decorative" -> DECORATIVE;
            case "non-mixing" -> NON_MIXING;
            case "unseparated-slot" -> FROM_UNSEPARATED_SLOT;
            case "opaque" -> OPAQUE;
            default -> 0L;
        };
    }

    /** Whether {@code name} names a flag. */
    public static boolean isFlagName(String name) {
        return byName(name) != 0L;
    }
}
