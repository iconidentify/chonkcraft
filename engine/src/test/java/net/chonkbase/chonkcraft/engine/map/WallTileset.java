package net.chonkbase.chonkcraft.engine.map;

/**
 * A tileset carrying the wall groups the shipped ones declare, and nothing
 * else.
 *
 * <p>Wall pictures are not interchangeable variants of one graphic: each of
 * the sixteen ways a wall can join its neighbours has a group of its own, and
 * inside that group the whole, broken and destroyed pictures are separated by
 * empty entries. So a fixture testing wall damage or wall joins needs a real
 * layout to choose from, not an empty table -- with an empty one every lookup
 * finds nothing and the picture never changes, which is indistinguishable from
 * the bug.
 *
 * <p>The layout is {@code scripts/tilesets/chonkcraft/summer.legacy-declaration}'s, reduced. Four
 * solid slots at {@code 0x090}-{@code 0x0C0} hold the isolated and enclosed
 * walls of both races, and the mixed slots at {@code 0x800} and {@code 0x900}
 * hold the fourteen joined ones each. Only the graphic indices matter here, so
 * they are the shipped ones for the human closed wall and made up, but
 * distinct, for the rest: what is under test is which entry gets chosen.
 */
public final class WallTileset {

    private WallTileset() {
    }

    /** Codes the human wall table names, in direction order. */
    public static final int[] HUMAN = {
        0x090, 0x830, 0x810, 0x850, 0x800, 0x840, 0x820, 0x860,
        0x870, 0x8B0, 0x890, 0x8D0, 0x880, 0x8C0, 0x8A0, 0x0B0,
    };

    /** Codes the orc wall table names, in direction order. */
    public static final int[] ORC = {
        0x0A0, 0x930, 0x910, 0x950, 0x900, 0x940, 0x920, 0x960,
        0x970, 0x9B0, 0x990, 0x9D0, 0x980, 0x9C0, 0x9A0, 0x0C0,
    };

    /** Where the broken picture sits inside a group. */
    public static final int BROKEN = 2;

    /** Where the destroyed picture sits inside a group. */
    public static final int DESTROYED = 4;

    /**
     * A tileset whose wall groups are all filled in.
     *
     * <p>Every code the two tables name gets a whole picture at its start, an
     * empty separator, a broken one, another separator and a destroyed one,
     * which is the shape {@code NextSection} walks. Graphics are unique per
     * code so a test can tell which entry was picked.
     */
    public static Tileset withWalls() {
        Tileset tileset = new Tileset();
        long human = TileFlag.LAND_ALLOWED | TileFlag.HUMAN | TileFlag.WALL | TileFlag.UNPASSABLE;
        long orc = TileFlag.LAND_ALLOWED | TileFlag.WALL | TileFlag.UNPASSABLE;
        int graphic = 1;
        for (int[] table : new int[][] {HUMAN, ORC}) {
            long flags = table == HUMAN ? human : orc;
            for (int base : table) {
                for (int offset : new int[] {0, BROKEN, DESTROYED}) {
                    tileset.setTile(base + offset, new Tileset.Tile(graphic++, flags, 1, 0));
                }
            }
        }
        return tileset;
    }
}
