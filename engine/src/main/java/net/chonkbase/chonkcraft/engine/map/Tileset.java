package net.chonkbase.chonkcraft.engine.map;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A terrain tileset: the table that turns a map's tile codes into graphics.
 *
 * <p>Implements {@code CTileset}.
 *
 * <p>A Warcraft II map stores a 16-bit code per square. That code is not a
 * graphic index; it is an index into this table, and the entry it finds
 * carries the graphic index plus the terrain properties the simulation needs.
 * The indirection is what lets one tileset present sixteen interchangeable
 * variants of grass under a single conceptual slot.
 *
 * <p>The table is built by evaluating the tileset script, which declares slots
 * in order. A {@code solid} slot occupies sixteen consecutive indices, a
 * {@code mixed} slot 256.
 */
public final class Tileset {

    /** One entry: a graphic and the terrain it represents. */
    public record Tile(int graphic, long flags, int baseTerrain, int mixTerrain) {

        /** Whether this entry was ever filled in. Unused codes stay at graphic 0. */
        public boolean isDefined() {
            return graphic != 0 || flags != 0;
        }
    }

    private static final Tile EMPTY = new Tile(0, 0L, 0, 0);

    private String name = "";
    private String imageFile = "";
    private int tileWidth = 32;
    private int tileHeight = 32;

    private final List<Tile> tiles = new ArrayList<>();
    private final Map<String, Integer> terrainNames = new LinkedHashMap<>();

    // Special tile indices the engine needs by name, from the script's
    // "special" section: chopping a forest reveals a different tile.
    private int topOneTreeTile;
    private int midOneTreeTile;
    private int botOneTreeTile;
    private int removedTreeTile;
    private int topOneRockTile;
    private int midOneRockTile;
    private int botOneRockTile;
    private int removedRockTile;

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /** The extracted tile sheet this tileset draws from. */
    public String imageFile() {
        return imageFile;
    }

    public void setImageFile(String imageFile) {
        this.imageFile = imageFile;
    }

    public int tileWidth() {
        return tileWidth;
    }

    public int tileHeight() {
        return tileHeight;
    }

    public void setTileSize(int width, int height) {
        this.tileWidth = width;
        this.tileHeight = height;
    }

    /** Number of tile-code slots defined. */
    public int tileCount() {
        return tiles.size();
    }

    /** The entry for a tile code, or an empty entry if the code is out of range. */
    public Tile tile(int code) {
        return code >= 0 && code < tiles.size() ? tiles.get(code) : EMPTY;
    }

    /**
     * The graphic index a tile code draws.
     *
     * <p>Returns 0 for an undefined code, which is what the engine renders for
     * a map referencing a slot its tileset does not define.
     */
    public int graphicFor(int code) {
        return tile(code).graphic();
    }

    /** The terrain flags for a tile code. */
    public long flagsFor(int code) {
        return tile(code).flags();
    }

    /** Grows the table to {@code count} slots, filling with empty entries. */
    public void ensureCapacity(int count) {
        while (tiles.size() < count) {
            tiles.add(EMPTY);
        }
    }

    /** Sets one slot. */
    public void setTile(int index, Tile tile) {
        ensureCapacity(index + 1);
        tiles.set(index, tile);
    }

    /** Interns a terrain name, returning its index. Index 0 is the unnamed default. */
    public int terrainIndex(String terrainName) {
        return terrainNames.computeIfAbsent(terrainName, ignored -> terrainNames.size() + 1);
    }

    /** The interned terrain names, in declaration order. */
    public Map<String, Integer> terrainNames() {
        return terrainNames;
    }

    // ---------------------------------------------------- special tile codes

    public int topOneTreeTile() {
        return topOneTreeTile;
    }

    public int midOneTreeTile() {
        return midOneTreeTile;
    }

    public int botOneTreeTile() {
        return botOneTreeTile;
    }

    /** What a forest square becomes once its trees are chopped. */
    public int removedTreeTile() {
        return removedTreeTile;
    }

    public int topOneRockTile() {
        return topOneRockTile;
    }

    public int midOneRockTile() {
        return midOneRockTile;
    }

    public int botOneRockTile() {
        return botOneRockTile;
    }

    /** What a rock square becomes once mined out. */
    public int removedRockTile() {
        return removedRockTile;
    }

    // ------------------------------------------------- felling a forest tile

    /**
     * Codes minted for graphics the table has none for.
     *
     * <p>The {@code special} section names four graphics -- the three
     * part-felled tree tiles and the cleared one -- and its own comment says
     * why they need naming: "Can't be in pud". They are drawn by the engine
     * and never by a map, so no tile code addresses them. Upstream does not
     * care, because a square there stores a graphic index; this implementation stores a
     * tile code and resolves it at draw time, which is the same indirection
     * the map file itself uses. So a code is minted for each on first use and
     * appended to the table. Nothing reads a code that high out of a map, and
     * everything that draws one now finds a real graphic behind it.
     */
    private final Map<Integer, Integer> codesByGraphic = new LinkedHashMap<>();

    /** The tile code that draws a graphic, minting one if none does. */
    private int codeFor(int graphic, long flags) {
        Integer known = codesByGraphic.get(graphic);
        if (known != null) {
            return known;
        }
        for (int code = 0; code < tiles.size(); code++) {
            if (tiles.get(code).isDefined() && tiles.get(code).graphic() == graphic) {
                codesByGraphic.put(graphic, code);
                return code;
            }
        }
        int code = tiles.size();
        setTile(code, new Tile(graphic, flags, 0, 0));
        codesByGraphic.put(graphic, code);
        return code;
    }

    /**
     * The forest terrain's own flags, taken from its solid slot.
     *
     * <p>Land, forest and unpassable in every shipped tileset, but read rather
     * than assumed: a felled square is the same flags with the trees taken
     * out of them.
     */
    private long forestFlags() {
        buildWoodTables();
        return solidForestSlot < 0 ? TileFlag.LAND_ALLOWED | TileFlag.FOREST | TileFlag.UNPASSABLE
                : tiles.get(solidForestSlot).flags();
    }

    /** The tile code for cleared ground where trees stood. */
    public int removedTreeCode() {
        return codeFor(removedTreeTile,
                forestFlags() & ~(TileFlag.FOREST | TileFlag.UNPASSABLE
                        | TileFlag.COST4 | TileFlag.COST5 | TileFlag.COST6));
    }

    /** The rock terrain's flags, taken from its solid slot. */
    private long rockFlags() {
        buildRockTables();
        return solidRockSlot < 0 ? TileFlag.LAND_ALLOWED | TileFlag.ROCKS | TileFlag.UNPASSABLE
                : tiles.get(solidRockSlot).flags();
    }

    /** The tile code for open ground where rock stood. */
    public int removedRockCode() {
        return codeFor(removedRockTile,
                rockFlags() & ~(TileFlag.ROCKS | TileFlag.UNPASSABLE));
    }

    /** Which rock tile belongs on a square, from what stands around it. */
    public int rockTileCodeFor(int up, int right, int down, int left) {
        buildRockTables();
        int graphic = rockGraphicFor(up, right, down, left);
        return graphic == -1 ? -1 : codeFor(graphic, rockFlags());
    }

    private int rockGraphicFor(int up, int right, int down, int left) {
        int u = up == -1 ? 15 : rockCornersOf(up);
        int r = right == -1 ? 15 : rockCornersOf(right);
        int d = down == -1 ? 15 : rockCornersOf(down);
        int l = left == -1 ? 15 : rockCornersOf(left);

        int index = 0;
        index += ((u & 0x01) != 0 && (l & 0x04) != 0) ? 8 : 0;
        index += ((u & 0x02) != 0 && (r & 0x08) != 0) ? 4 : 0;
        index += ((r & 0x01) != 0 && (d & 0x04) != 0) ? 2 : 0;
        index += ((l & 0x02) != 0 && (d & 0x08) != 0) ? 1 : 0;
        if ((d & 0x10) != 0) {
            index |= (l & 0x06) != 0 ? 1 : 0;
            index |= (r & 0x09) != 0 ? 2 : 0;
        }
        if ((u & 0x20) != 0) {
            index |= (l & 0x06) != 0 ? 8 : 0;
            index |= (r & 0x09) != 0 ? 4 : 0;
        }

        int graphic = rockTable[index];
        if (graphic != -1) {
            return graphic;
        }
        int single = 16;
        single += ((u & 0x03) != 0) ? 1 : 0;
        single += ((d & 0x0C) != 0) ? 2 : 0;
        return rockTable[single];
    }

    private int rockCornersOf(int graphic) {
        return graphic >= 0 && graphic < rockLookupTable.length ? rockLookupTable[graphic] : 0;
    }

    /**
     * Which forest tile belongs on a square, from what stands around it.
     *
     * <p>Implements {@code CTileset::getTileBySurrounding},
     * for wood. Each of the four orthogonal
     * neighbours is reduced to a nibble saying which of its corners hold
     * trees, and the four nibbles are matched against each other: a corner of
     * this square has trees only when both neighbours touching it do. The four
     * corner bits index a table of the sixteen mixed forest-and-grass tiles,
     * with the solid tile at fifteen.
     *
     * <p>The last four entries are what the implementation never had. When no
     * combination of corners fits, the square holds a single tree rather than
     * an edge, and which single tree depends on whether there are trees above
     * it, below it, or both: {@code bot-one-tree}, {@code top-one-tree},
     * {@code mid-one-tree}. Those three are how a forest looks while it is
     * being cut down the middle. Without them the implementation went from a full
     * tileset tile straight to bare ground, so felling one square of a wood
     * left the squares beside it drawn as though the tree were still there.
     *
     * @param up    the graphic north of the square, or -1 for off the map
     * @param right the graphic east of it
     * @param down  the graphic south of it
     * @param left  the graphic west of it
     * @return the tile code to draw, or -1 when no trees can stand here
     */
    public int woodTileCodeFor(int up, int right, int down, int left) {
        buildWoodTables();
        int graphic = woodGraphicFor(up, right, down, left);
        return graphic == -1 ? -1 : codeFor(graphic, forestFlags());
    }

    private int woodGraphicFor(int up, int right, int down, int left) {
        // Off the map counts as trees in every direction, so a wood running
        // off the edge is not drawn with a coastline.
        int u = up == -1 ? 15 : cornersOf(up);
        int r = right == -1 ? 15 : cornersOf(right);
        int d = down == -1 ? 15 : cornersOf(down);
        int l = left == -1 ? 15 : cornersOf(left);

        //  ?**?
        //  *mm*
        //  *mm*
        //  ?**?
        // Both asterisks of a corner must be wood for that corner to be.
        int index = 0;
        index += ((u & 0x01) != 0 && (l & 0x04) != 0) ? 8 : 0;
        index += ((u & 0x02) != 0 && (r & 0x08) != 0) ? 4 : 0;
        index += ((r & 0x01) != 0 && (d & 0x04) != 0) ? 2 : 0;
        index += ((l & 0x02) != 0 && (d & 0x08) != 0) ? 1 : 0;

        // The single-tree tiles are their own case: they carry trees in the
        // middle of a square rather than at its corners.
        if ((d & 0x10) != 0) {
            index |= (l & 0x06) != 0 ? 1 : 0;
            index |= (r & 0x09) != 0 ? 2 : 0;
        }
        if ((u & 0x20) != 0) {
            index |= (l & 0x06) != 0 ? 8 : 0;
            index |= (r & 0x09) != 0 ? 4 : 0;
        }

        int graphic = woodTable[index];
        if (graphic != -1) {
            return graphic;
        }
        // Nothing fits, so it is one tree on its own: which one depends on
        // whether the wood carries on above it, below it, or both.
        int single = 16;
        single += ((u & 0x03) != 0) ? 1 : 0;
        single += ((d & 0x0C) != 0) ? 2 : 0;
        return woodTable[single];
    }

    private int cornersOf(int graphic) {
        return graphic >= 0 && graphic < mixedLookupTable.length ? mixedLookupTable[graphic] : 0;
    }

    /** The twenty forest tiles by corner configuration, and the corner bits. */
    private int[] woodTable;
    private int[] mixedLookupTable;
    private int solidForestSlot = -1;
    private int mixedForestSlot = -1;
    private int[] rockTable;
    private int[] rockLookupTable;
    private int solidRockSlot = -1;
    private int mixedRockSlot = -1;

    /**
     * Builds the wood tables, as the tail of {@code DefineTileset} does
     *
     *
     * <p>Lazily, because the tables are built out of the finished slot list
     * and the script fills that in as it goes. Rebuilt if the table has grown
     * since, which minting a code does.
     */
    private void buildWoodTables() {
        if (woodTable != null) {
            return;
        }
        // A solid slot claims sixteen codes and a mixed slot 256, so the list
        // has to be walked in slot strides rather than by code.
        for (int i = 0; i < tiles.size();) {
            Tile tile = tiles.get(i);
            boolean forest = (tile.flags() & TileFlag.FOREST) != 0;
            if (tile.baseTerrain() != 0 && tile.mixTerrain() != 0) {
                if (forest) {
                    mixedForestSlot = i;
                }
                i += 256;
            } else {
                if (tile.baseTerrain() != 0 && tile.mixTerrain() == 0 && forest) {
                    solidForestSlot = i;
                }
                i += 16;
            }
        }

        int solid = Math.max(0, solidForestSlot);
        int mixed = Math.max(0, mixedForestSlot);
        woodTable = new int[] {
            -1,
            graphicOfSlot(mixed + 0x30), graphicOfSlot(mixed + 0x70),
            graphicOfSlot(mixed + 0xB0), graphicOfSlot(mixed + 0x10),
            graphicOfSlot(mixed + 0x50), graphicOfSlot(mixed + 0x90),
            graphicOfSlot(mixed + 0xD0), graphicOfSlot(mixed + 0x00),
            graphicOfSlot(mixed + 0x40), graphicOfSlot(mixed + 0x80),
            graphicOfSlot(mixed + 0xC0), graphicOfSlot(mixed + 0x20),
            graphicOfSlot(mixed + 0x60), graphicOfSlot(mixed + 0xA0),
            graphicOfSlot(solid),
            -1,
            botOneTreeTile, topOneTreeTile, midOneTreeTile,
        };

        int highest = 0;
        for (Tile tile : tiles) {
            highest = Math.max(highest, tile.graphic());
        }
        for (int special : new int[] {topOneTreeTile, midOneTreeTile, botOneTreeTile}) {
            highest = Math.max(highest, special);
        }
        mixedLookupTable = new int[highest + 1];

        // 1 bottom left, 2 bottom right, 4 top right, 8 top left. A solid
        // forest tile has trees in all four.
        for (int i = solid; i < solid + 16 && i < tiles.size(); i++) {
            mark(tiles.get(i).graphic(), 15);
        }
        // The mixed slot's sixteen groups of sixteen, one group per corner
        // configuration, in the order the tileset writes them.
        int[] byGroup = {8, 4, 8 + 4, 1, 8 + 1, 4 + 1, 8 + 4 + 1, 2,
            8 + 2, 4 + 2, 8 + 4 + 2, 2 + 1, 8 + 2 + 1, 4 + 2 + 1, 0, 0};
        for (int i = mixed; i < mixed + 256 && i < tiles.size(); i++) {
            mark(tiles.get(i).graphic(), byGroup[(i - mixed) / 16]);
        }
        // 16 marks a bottom-tree tile and 32 a top-tree one, which is what
        // lets the single-tree case above tell them from an ordinary edge.
        mark(botOneTreeTile, 12 + 16);
        mark(topOneTreeTile, 3 + 32);
        mark(midOneTreeTile, 15 + 48);
    }

    /** Builds the rock transition table, the rock twin of {@link #buildWoodTables()}. */
    private void buildRockTables() {
        if (rockTable != null) {
            return;
        }
        for (int i = 0; i < tiles.size();) {
            Tile tile = tiles.get(i);
            boolean rock = (tile.flags() & TileFlag.ROCKS) != 0;
            if (tile.baseTerrain() != 0 && tile.mixTerrain() != 0) {
                if (rock) {
                    mixedRockSlot = i;
                }
                i += 256;
            } else {
                if (tile.baseTerrain() != 0 && tile.mixTerrain() == 0 && rock) {
                    solidRockSlot = i;
                }
                i += 16;
            }
        }

        int solid = Math.max(0, solidRockSlot);
        int mixed = Math.max(0, mixedRockSlot);
        rockTable = new int[] {
            -1,
            graphicOfSlot(mixed + 0x30), graphicOfSlot(mixed + 0x70),
            graphicOfSlot(mixed + 0xB0), graphicOfSlot(mixed + 0x10),
            graphicOfSlot(mixed + 0x50), graphicOfSlot(mixed + 0x90),
            graphicOfSlot(mixed + 0xD0), graphicOfSlot(mixed + 0x00),
            graphicOfSlot(mixed + 0x40), graphicOfSlot(mixed + 0x80),
            graphicOfSlot(mixed + 0xC0), graphicOfSlot(mixed + 0x20),
            graphicOfSlot(mixed + 0x60), graphicOfSlot(mixed + 0xA0),
            graphicOfSlot(solid),
            -1,
            botOneRockTile, topOneRockTile, midOneRockTile,
        };

        int highest = 0;
        for (Tile tile : tiles) {
            highest = Math.max(highest, tile.graphic());
        }
        for (int special : new int[] {topOneRockTile, midOneRockTile, botOneRockTile}) {
            highest = Math.max(highest, special);
        }
        rockLookupTable = new int[highest + 1];
        for (int i = solid; i < solid + 16 && i < tiles.size(); i++) {
            markRock(tiles.get(i).graphic(), 15);
        }
        int[] byGroup = {8, 4, 8 + 4, 1, 8 + 1, 4 + 1, 8 + 4 + 1, 2,
            8 + 2, 4 + 2, 8 + 4 + 2, 2 + 1, 8 + 2 + 1, 4 + 2 + 1, 0, 0};
        for (int i = mixed; i < mixed + 256 && i < tiles.size(); i++) {
            markRock(tiles.get(i).graphic(), byGroup[(i - mixed) / 16]);
        }
        markRock(botOneRockTile, 12 + 16);
        markRock(topOneRockTile, 3 + 32);
        markRock(midOneRockTile, 15 + 48);
    }

    private void mark(int graphic, int corners) {
        if (graphic >= 0 && graphic < mixedLookupTable.length) {
            mixedLookupTable[graphic] = corners;
        }
    }

    private void markRock(int graphic, int corners) {
        if (graphic >= 0 && graphic < rockLookupTable.length) {
            rockLookupTable[graphic] = corners;
        }
    }

    private int graphicOfSlot(int index) {
        return index >= 0 && index < tiles.size() ? tiles.get(index).graphic() : -1;
    }

    // ------------------------------------------------------------------ walls

    /**
     * The human wall tiles by which of the four neighbours are also wall.
     *
     * <p>{@code CTileset::humanWallTable}, filled by
     * {@code buildWallReplacementTable}
     * -- which hard-codes it, under its own "FIXME: Build wall replacement
     * tables". The indices are tile codes, not graphics, and this implementation's codes
     * are laid out the same way, so they carry over unaltered.
     *
     * <p>Bit 1 is north, 2 east, 4 south, 8 west, so entry 0 is a wall with
     * nothing beside it and entry 15 one enclosed on all four sides.
     */
    private static final int[] HUMAN_WALL_TABLE = {
        0x090, 0x830, 0x810, 0x850, 0x800, 0x840, 0x820, 0x860,
        0x870, 0x8B0, 0x890, 0x8D0, 0x880, 0x8C0, 0x8A0, 0x0B0,
    };

    /** The orc half of {@link #HUMAN_WALL_TABLE}. */
    private static final int[] ORC_WALL_TABLE = {
        0x0A0, 0x930, 0x910, 0x950, 0x900, 0x940, 0x920, 0x960,
        0x970, 0x9B0, 0x990, 0x9D0, 0x980, 0x9C0, 0x9A0, 0x0C0,
    };

    /**
     * The wall tile for a set of neighbours and a state of repair.
     *
     * <p>{@code getWallTile}. The direction
     * picks the group; how much of the wall is left picks which of the three
     * pictures inside that group -- whole, broken, destroyed -- and they are
     * laid out one after another separated by empty entries, which is what
     * {@code NextSection} walks.
     *
     * <p>The fallback is upstream's too and it matters: two of the sixteen
     * groups have no picture for some directions, and rather than draw nothing
     * upstream re-derives from the direction the old tile already had. Without
     * it a wall joined on three sides can be rebuilt as a blank square.
     *
     * @param human        which race's wall
     * @param dirFlag      the four neighbour bits
     * @param value        hit points left in the square
     * @param maxHitPoints what a whole wall of this race holds
     * @param oldCode      the tile there now, for the fallback
     * @return the tile code to draw, or the old one if nothing fits
     */
    public int wallTileCodeFor(boolean human, int dirFlag, int value, int maxHitPoints,
            int oldCode) {
        int[] table = human ? HUMAN_WALL_TABLE : ORC_WALL_TABLE;
        int base = table[dirFlag & 0xF];
        int code = base;
        if (value == 0) {
            int broken = nextSection(base);
            code = broken < 0 ? -1 : nextSection(broken);
        } else if (value <= Math.max(1, maxHitPoints) / 2) {
            code = nextSection(base);
        }
        if (graphicFor(code) != 0) {
            return code;
        }
        // Nothing there. Ask the same question again with the direction the
        // square is already drawn with, exactly as getWallTile recurses.
        int fallbackDir = wallDirectionOf(oldCode, human);
        if (fallbackDir != (dirFlag & 0xF)) {
            return wallTileCodeFor(human, fallbackDir, value, maxHitPoints, 0);
        }
        return oldCode;
    }

    /**
     * The direction a wall tile is already drawn with.
     *
     * <p>{@code CTileset::getWallDirection}.
     * Only the group is compared, so a broken or destroyed picture answers for
     * the whole group it belongs to.
     */
    public int wallDirectionOf(int code, boolean human) {
        int[] table = human ? HUMAN_WALL_TABLE : ORC_WALL_TABLE;
        int group = code & 0xFF0;
        for (int i = 0; i < table.length; i++) {
            if (table[i] == group) {
                return i;
            }
        }
        return 0;
    }

    /**
     * The start of the next run of graphics after this one.
     *
     * <p>{@code NextSection}: skip the
     * defined tiles, then skip the empty ones that separate the sections. The
     * separator is why a wall group's three pictures are not simply at nought,
     * one and two -- and why they are not always at nought, two and four
     * either. Two of the shipped groups carry a second variant of each
     * picture, so their broken tile sits at three and their destroyed one at
     * six. A fixed stride had them drawing an empty square.
     */
    private int nextSection(int code) {
        int at = code;
        int limit = Math.min(tiles.size(), (code & ~0xF) + 16);
        while (at < limit && graphicFor(at) != 0) {
            at++;
        }
        while (at < limit && graphicFor(at) == 0) {
            at++;
        }
        // Off the end of the group means the section is not there. Upstream
        // walks on into whatever slot follows; -1 says "nothing", which is
        // what sends the caller to its fallback.
        return at >= limit ? -1 : at;
    }

    /** Applies one entry from the script's {@code special} section. */
    public void setSpecial(String key, int tileIndex) {
        switch (key) {
            case "top-one-tree" -> topOneTreeTile = tileIndex;
            case "mid-one-tree" -> midOneTreeTile = tileIndex;
            case "bot-one-tree" -> botOneTreeTile = tileIndex;
            case "removed-tree" -> removedTreeTile = tileIndex;
            case "top-one-rock" -> topOneRockTile = tileIndex;
            case "mid-one-rock" -> midOneRockTile = tileIndex;
            case "bot-one-rock" -> botOneRockTile = tileIndex;
            case "removed-rock" -> removedRockTile = tileIndex;
            // Kept in the data for backward compatibility and ignored upstream.
            case "growing-tree" -> { }
            default -> throw new IllegalArgumentException("unsupported special tag: " + key);
        }
    }
}
