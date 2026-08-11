package net.chonkbase.chonkcraft.engine.map;

import net.chonkbase.chonkcraft.data.map.PudMap;

/**
 * The playable map: a grid of {@link MapField}s built from a loaded map and
 * its tileset.
 *
 * <p>Implements the parts of {@code CMap} the simulation needs. The tileset
 * supplies each square's terrain flags; units then set and clear occupancy
 * bits on top as they move.
 */
public final class GameMap {

    private final int width;
    private final int height;
    private final MapField[] fields;
    private final Tileset tileset;

    /**
     * Squares whose picture has changed since somebody last took the list.
     *
     * <p>Warcraft II has no such list because it has nothing to tell.
     * {@code CViewport::DrawMapBackgroundInViewport}
     * blits every visible square out of the tile sheet on every frame, so a
     * square that changes is simply drawn differently the next time round.
     * This implementation rasterises the whole map into one image at load instead --
     * three thousand pixels square, so that colour cycling costs a palette
     * swap rather than a redraw -- and that image is a cache. Trees are felled,
     * rock is blown open and walls are breached while the game runs, and a
     * cache nobody invalidates is a picture of the map as it was at load.
     *
     * <p>Which is what a player reported: a woodcutter standing in the middle
     * of a stand of trees, surrounded by intact forest, chopping. The worker
     * was on ground it had legitimately cleared -- the square's flags said
     * open ground, the pathfinder had never let it near an unpassable one --
     * and the screen was still drawing the canopy that had been there when the
     * map loaded. Half an hour of chopping moved nothing but the minimap,
     * which is redrawn from the live map every frame and so was right.
     *
     * <p>A {@link java.util.BitSet} rather than a queue so that a consumer
     * which never comes -- a headless run, a test, a dedicated server -- costs
     * one bit per square and no growth: the same square felled and repaired a
     * hundred times is one entry.
     */
    private final java.util.BitSet changedPictures = new java.util.BitSet();

    private static final int[] NOTHING_CHANGED = new int[0];

    /**
     * The map as the map file made it, which is what a save does not have to
     * carry.
     *
     * <p>Three arrays rather than a copy of the fields, because only the three
     * things a run-time change can touch are wanted and a {@link MapField} is
     * an object per square.
     */
    private final int[] loadedTiles;
    private final long[] loadedFlags;
    private final int[] loadedValues;

    public GameMap(int width, int height, Tileset tileset) {
        this.width = width;
        this.height = height;
        this.tileset = tileset;
        this.fields = new MapField[Math.multiplyExact(width, height)];
        for (int i = 0; i < fields.length; i++) {
            fields[i] = new MapField();
        }
        this.loadedTiles = new int[fields.length];
        this.loadedFlags = new long[fields.length];
        this.loadedValues = new int[fields.length];
    }

    /**
     * Builds a map from a parsed PUD, taking each square's flags from the
     * tileset entry its tile code selects.
     */
    public static GameMap from(PudMap source, Tileset tileset) {
        GameMap map = new GameMap(source.width(), source.height(), tileset);
        for (int y = 0; y < source.height(); y++) {
            for (int x = 0; x < source.width(); x++) {
                int code = source.tileAt(x, y);
                MapField field = map.field(x, y);
                field.setTile(code);
                field.setFlags(tileset.flagsFor(code));
                // Walls are terrain in Warcraft II, so their hit points ride
                // along in the square's spare value. So does a forest square's
                // wood, which is what makes a tree take a while to fell rather
                // than vanishing at the first swing.
                // pudconvert passes the PUD tile's encoded wall value to
                // SetTile even when ChonkCraft classifies that graphic as forest.
                // CMapField::setTileIndex only supplies the default hundred
                // when the explicit value is zero. The order matters on such
                // overlap tiles: levelx10h's tree at 62,2 is worth forty, not
                // a freshly invented hundred.
                int encoded = PudMap.wallValue(code);
                field.setValue(encoded != 0
                        ? encoded
                        : field.isForest() ? WOOD_PER_FOREST_TILE : 0);
            }
        }
        map.recordLoadedTerrain();
        return map;
    }

    /**
     * How much wood one forest square holds.
     *
     * <p>{@code CMapField::setTileIndex} sets a
     * hundred, with a note that it should one day come from
     * {@code DefaultResourceAmounts}. A peasant carries a hundred, so one
     * square is one full load and fifty swings of the axe.
     */
    public static final int WOOD_PER_FOREST_TILE = 100;

    /**
     * How much a whole wall square is worth, when no wall unit type says.
     *
     * <p>The figure the PUD reader recovers from a wall tile code; see
     * {@code PudMap.wallValue}.
     */
    public static final int WALL_HIT_POINTS = 40;

    /**
     * Takes a piece out of a wall, and the wall itself once it is spent.
     *
     * <p>{@code CMap::HitWall},, plus the
     * {@code RemoveWall} it reaches through {@code ClearTile}. The implementation had no
     * counterpart at all, so walls were indestructible terrain and a walled
     * base could only be entered by a gap the mapper had left.
     *
     * <p>A wall is not a unit in Warcraft II -- it is terrain with hit points
     * riding in the square's spare value -- which is why this is here and not
     * in the unit code, and why breaking one clears terrain flags rather than
     * killing anything.
     *
     * <p>The graphic is re-derived rather than nudged. {@link #fixWallTile}
     * asks the tileset for the picture matching this square's four neighbours
     * and its remaining hit points together, and then the four squares around
     * it are asked the same question, because one of them may now be joining
     * on to a hole.
     *
     * @param damage       how much to take off, already divided by any splash
     * @param maxHitPoints what a whole wall of this race is worth, which
     *                     decides when it starts looking broken
     */
    public void hitWall(int x, int y, int damage, int maxHitPoints) {
        MapField field = fieldOrNull(x, y);
        if (field == null || !field.isWall() || damage <= 0) {
            return;
        }
        if (field.value() <= damage) {
            breakWall(x, y, maxHitPoints);
            return;
        }
        field.setValue(field.value() - damage);
        fixWallTile(x, y, maxHitPoints);
        // A neighbour cannot change shape over a wall that is merely damaged
        // -- it is still a wall of the same race, so every dirFlag round it is
        // what it was. Upstream fixes only the square itself here too.
    }

    /**
     * Pulls a spent wall down: rubble to look at, open ground to walk over.
     *
     * <p>{@code CMap::RemoveWall},, in its order,
     * which is the part that matters. The square's own picture is chosen while
     * it still counts as a wall, so the rubble is drawn joined the way the
     * wall was; then the flags come off; then the four neighbours are
     * re-derived, and by that point this square no longer reads as wall, so
     * each of them draws an end rather than a join into the hole.
     */
    private void breakWall(int x, int y, int maxHitPoints) {
        MapField field = field(x, y);
        field.setValue(0);
        fixWallTile(x, y, maxHitPoints);
        // Exactly what RemoveWall clears. The square keeps its land flag, which
        // is the whole point: a breach is something an army walks through.
        field.removeFlags(TileFlag.WALL | TileFlag.HUMAN | TileFlag.UNPASSABLE
                | TileFlag.OPAQUE);
        fixWallNeighbours(x, y, maxHitPoints);
    }

    /**
     * Re-derives one wall square's picture from what stands around it.
     *
     * <p>{@code MapFixWallTile},, for the
     * played map rather than the remembered one -- this implementation keeps what each
     * player has seen elsewhere, so the {@code MapFixSeenWallTile} half is not
     * here.
     *
     * <p>Nothing had ever changed a wall tile mid-game before walls could be
     * broken, which is why the implementation had no wall direction table at all and a
     * surviving segment beside a breach went on drawing a join to a square
     * that was no longer there.
     *
     * @param maxHitPoints what a whole wall holds; human and orc walls both
     *                     hold forty in the shipped data, so one figure serves
     *                     the square and its neighbours alike
     */
    private void fixWallTile(int x, int y, int maxHitPoints) {
        MapField field = fieldOrNull(x, y);
        if (field == null || !field.isWall()) {
            return;
        }
        boolean human = field.hasFlag(TileFlag.HUMAN);
        int code = tileset.wallTileCodeFor(human, wallDirectionAround(x, y, human),
                field.value(), maxHitPoints, field.tile());
        if (code >= 0) {
            repaintTile(field, x, y, code);
        }
    }

    /** The four squares round one, as {@code MapFixWallNeighbors} lists them. */
    private void fixWallNeighbours(int x, int y, int maxHitPoints) {
        fixWallTile(x + 1, y, maxHitPoints);
        fixWallTile(x - 1, y, maxHitPoints);
        fixWallTile(x, y + 1, maxHitPoints);
        fixWallTile(x, y - 1, maxHitPoints);
    }

    /**
     * Which of the four neighbours are wall of the same race.
     *
     * <p>{@code GetDirectionFromSurrounding}. North is
     * bit one, east two, south four, west eight, and off the map counts as
     * wall -- a wall running to the edge is drawn joined to it rather than
     * ending in mid-air.
     *
     * <p>Upstream asks the tile type table, in which a destroyed wall's
     * graphic is deliberately marked unknown. This asks the square's flags,
     * which {@link #breakWall} clears at the same moment and for the same
     * reason, so a breach does not count as a neighbour either way.
     */
    private int wallDirectionAround(int x, int y, boolean human) {
        int flag = 0;
        int[][] offsets = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        for (int i = 0; i < offsets.length; i++) {
            MapField neighbour = fieldOrNull(x + offsets[i][0], y + offsets[i][1]);
            if (neighbour == null
                    || (neighbour.isWall() && neighbour.hasFlag(TileFlag.HUMAN) == human)) {
                flag |= 1 << i;
            }
        }
        return flag;
    }


    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public Tileset tileset() {
        return tileset;
    }

    /**
     * Takes the trees off a square and mends the ones around it.
     *
     * <p>{@code CMap::ClearWoodTile}. The square becomes
     * the tileset's cleared-ground tile and loses its forest and unpassable
     * flags -- and then, which the implementation never did, its eight neighbours are
     * looked at again.
     *
     * <p>That second half is what a wood being cut down actually looks like.
     * A forest tile is drawn as an edge between trees and grass, and which
     * edge depends on what stands beside it; take one square out of the middle
     * and four of its neighbours are now drawing an edge that no longer exists.
     * Upstream re-derives each of them from its own four neighbours, and a
     * square with no valid edge left becomes one of the three single-tree
     * tiles the tileset declares for exactly this -- top, middle or bottom of
     * a lone column of trees -- or clears entirely if there is nothing left to
     * draw. This implementation went from a full forest tile to bare ground in one step,
     * so a half-felled wood was drawn as though it were whole right up to the
     * squares that had been cut.
     *
     * <p>The flags are cleared rather than replaced, which is also new. Setting
     * them to the cleared tile's own flags looked equivalent and was not: the
     * cleared tile is one of the four the tileset names outside the map's code
     * space, so the lookup found an undefined slot and every felled square
     * ended up with a flag word of zero -- no land, and so not walkable. The
     * wood came down and left a hole nothing could cross.
     */
    public void clearWoodTile(int x, int y) {
        MapField field = fieldOrNull(x, y);
        if (field == null) {
            return;
        }
        if (System.getenv("CHONKCRAFT_TRACE_WOOD") != null) {
            System.err.println("JWOODCLR " + x + "," + y);
        }
        repaintTile(field, x, y, tileset.removedTreeCode());
        field.removeFlags(TileFlag.COST4 | TileFlag.COST5 | TileFlag.COST6
                | TileFlag.FOREST | TileFlag.UNPASSABLE);
        field.setValue(0);
        fixWoodNeighbours(x, y);
    }

    /** The eight squares round one, as {@code CMap::FixNeighbors} lists them. */
    private void fixWoodNeighbours(int x, int y) {
        int[][] offsets = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {-1, -1}, {-1, 1}, {1, -1}, {1, 1},
        };
        for (int[] offset : offsets) {
            fixWoodTile(x + offset[0], y + offset[1]);
        }
    }

    /**
     * Re-derives one forest square's tile from its four orthogonal neighbours.
     *
     * <p>{@code CMap::FixTile},, for the played map rather
     * than the remembered one: this implementation keeps what each player has seen
     * elsewhere, so only the {@code seen == 0} half is here.
     */
    private void fixWoodTile(int x, int y) {
        MapField field = fieldOrNull(x, y);
        if (field == null || !field.isForest()) {
            return;
        }
        // Off the map reads as trees, which is what stops a wood running to
        // the edge from being drawn with a shoreline along it.
        int up = y - 1 < 0 ? -1 : graphicAt(x, y - 1);
        int right = x + 1 >= width ? -1 : graphicAt(x + 1, y);
        int down = y + 1 >= height ? -1 : graphicAt(x, y + 1);
        int left = x - 1 < 0 ? -1 : graphicAt(x - 1, y);

        int code = tileset.woodTileCodeFor(up, right, down, left);
        if (code == -1) {
            // No trees can stand here any more. Upstream clears it without
            // going round again; a cascade would take a whole wood out at
            // once from a single square being cut.
            if (System.getenv("CHONKCRAFT_TRACE_WOOD") != null) {
                System.err.println("JWOODFIX " + x + "," + y);
            }
            repaintTile(field, x, y, tileset.removedTreeCode());
            field.removeFlags(TileFlag.COST4 | TileFlag.COST5 | TileFlag.COST6
                    | TileFlag.FOREST | TileFlag.UNPASSABLE);
            field.setValue(0);
            return;
        }
        // Only the picture changes: the square still holds trees, and its
        // flags and its remaining wood are its own.
        repaintTile(field, x, y, code);
    }

    /**
     * Takes rock off a square and mends the rock edge drawn around the hole.
     *
     * <p>{@code CMap::ClearRockTile} is deliberately the twin of
     * {@link #clearWoodTile}: both terrain types use the same twenty-entry
     * transition shape and the same eight-neighbour repair.
     */
    public void clearRockTile(int x, int y) {
        MapField field = fieldOrNull(x, y);
        if (field == null) {
            return;
        }
        repaintTile(field, x, y, tileset.removedRockCode());
        field.removeFlags(TileFlag.ROCKS | TileFlag.UNPASSABLE);
        field.setValue(0);

        int[][] offsets = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {-1, -1}, {-1, 1}, {1, -1}, {1, 1},
        };
        for (int[] offset : offsets) {
            fixRockTile(x + offset[0], y + offset[1]);
        }
    }

    /** Re-derives one surviving rock square from its orthogonal neighbours. */
    private void fixRockTile(int x, int y) {
        MapField field = fieldOrNull(x, y);
        if (field == null || !field.hasFlag(TileFlag.ROCKS)) {
            return;
        }
        int up = y - 1 < 0 ? -1 : graphicAt(x, y - 1);
        int right = x + 1 >= width ? -1 : graphicAt(x + 1, y);
        int down = y + 1 >= height ? -1 : graphicAt(x, y + 1);
        int left = x - 1 < 0 ? -1 : graphicAt(x - 1, y);

        int code = tileset.rockTileCodeFor(up, right, down, left);
        if (code == -1) {
            repaintTile(field, x, y, tileset.removedRockCode());
            field.removeFlags(TileFlag.ROCKS | TileFlag.UNPASSABLE);
            field.setValue(0);
            return;
        }
        repaintTile(field, x, y, code);
    }

    /**
     * Puts a new picture on a square and remembers that it moved.
     *
     * <p>Every run-time change of a tile code goes through here and none
     * bypasses it, which is the only thing that makes {@link
     * #drainChangedPictures} trustworthy. {@link #from} does not: that is the
     * load, and what the load produces is the picture the renderer starts
     * from.
     *
     * <p>A square set to the code it already carries is not reported. Felling
     * one tree asks all eight neighbours to re-derive themselves and most of
     * them come back with the tile they already had.
     */
    private void repaintTile(MapField field, int x, int y, int code) {
        if (field.tile() == code) {
            return;
        }
        field.setTile(code);
        synchronized (changedPictures) {
            changedPictures.set(y * width + x);
        }
    }

    /**
     * The squares whose picture has changed, leaving the list empty.
     *
     * <p>Indices into a row-major grid, the same order {@link #tileCodes}
     * hands out, so a consumer holding a rasterised map can turn each one
     * straight back into the square it has to redraw.
     *
     * <p>Synchronised, and not for the list's sake: it is what publishes the
     * tile codes themselves. The simulation runs on its own thread and the
     * screen draws on the toolkit's, so without a barrier between the write
     * and the read a renderer could be told a square had moved and then read
     * the code it used to have -- and, having redrawn it and cleared the flag,
     * never be told again.
     */
    public int[] drainChangedPictures() {
        synchronized (changedPictures) {
            if (changedPictures.isEmpty()) {
                return NOTHING_CHANGED;
            }
            int[] drained = changedPictures.stream().toArray();
            changedPictures.clear();
            return drained;
        }
    }

    /**
     * The flags a unit standing on a square puts there, as opposed to the ones
     * the ground itself carries.
     *
     * <p>{@code World.markOccupancy} sets and clears these as things move, and
     * a save has no business in them: the units are written out and put back,
     * and each of them marks its own square again on the way in. Carrying them
     * would also make the answer depend on when in the file the terrain was
     * written relative to the units.
     */
    public static final long OCCUPANCY_FLAGS = TileFlag.LAND_UNIT | TileFlag.AIR_UNIT
            | TileFlag.SEA_UNIT | TileFlag.BUILDING;

    /**
     * One square that no longer looks like the map file said it should.
     *
     * @param flags terrain flags only, with the occupancy bits masked off
     * @param value the square's spare value: wall hit points, or the wood a
     *              forest square still holds
     */
    public record TerrainChange(int x, int y, int tile, long flags, int value) {}

    /**
     * Takes the map as it now stands to be the map as it was loaded.
     *
     * <p>{@link #from} calls this once the whole grid is built, so everything
     * after that point is the game changing the ground rather than the map file
     * describing it. A map assembled by hand -- a test fixture -- should call it
     * when its terrain is finished, for the same reason.
     *
     * <p>Upstream needs no such line because {@code CMap::Save},
     * writes every field of the map into the save
     * and {@code SaveGame} stubs out
     * {@code SetTile} while the map reloads, so upstream's save is the sole
     * source of the terrain. This implementation's save reloads the map and gets its
     * ground back intact, so the only thing it has to carry is the difference.
     */
    public void recordLoadedTerrain() {
        for (int i = 0; i < fields.length; i++) {
            loadedTiles[i] = fields[i].tile();
            loadedFlags[i] = fields[i].flags();
            loadedValues[i] = fields[i].value();
        }
    }

    /**
     * Every square the game has changed since the map was loaded.
     *
     * <p>What a save has to carry, and the reason it has to carry anything:
     * {@link #clearWoodTile}, {@link #clearRockTile} and {@link #hitWall} all
     * rewrite a square's picture, its flags and its remaining value while the
     * game runs, and {@code World.takeResource} counts a forest square's wood
     * down two at a time as a peasant swings. None of that is in the map file,
     * so a save that names only the map restores the ground as it was an hour
     * ago -- the felled wood standing again, and, because the flags come back
     * with it, standing on top of whatever is now parked there.
     *
     * <p>A difference rather than a dump. Upstream writes all
     * {@code Info.MapWidth * Info.MapHeight} fields because its save is the
     * only description of the terrain it will have; this one has the map file,
     * so a game that has felled two hundred squares writes two hundred lines
     * instead of the 16,384 a 128 by 128 map would otherwise cost.
     */
    public java.util.List<TerrainChange> terrainChangedSinceLoad() {
        java.util.List<TerrainChange> changed = new java.util.ArrayList<>();
        for (int i = 0; i < fields.length; i++) {
            MapField field = fields[i];
            long flags = field.flags() & ~OCCUPANCY_FLAGS;
            if (field.tile() == loadedTiles[i]
                    && flags == (loadedFlags[i] & ~OCCUPANCY_FLAGS)
                    && field.value() == loadedValues[i]) {
                continue;
            }
            changed.add(new TerrainChange(i % width, i / width, field.tile(), flags,
                    field.value()));
        }
        return changed;
    }

    /**
     * Puts one square back as a save found it.
     *
     * <p>What {@code CMapField::parse},, does
     * for each entry of upstream's {@code map-fields} table, for the squares
     * this implementation's save actually names.
     *
     * <p>The occupancy bits are kept rather than restored. They belong to
     * whatever is standing here now, and on the way in that is decided by the
     * units the save creates, not by the ground.
     *
     * <p>No neighbour repair. The save carries the neighbours too, already
     * mended, which is the same reason upstream reads its fields straight in:
     * re-deriving them here would mend a wood twice and, on a save loaded
     * square by square, mend it against half-restored ground.
     */
    public void restoreSavedTile(int x, int y, int tile, long terrainFlags, int value) {
        MapField field = fieldOrNull(x, y);
        if (field == null) {
            return;
        }
        repaintTile(field, x, y, tile);
        field.setFlags((terrainFlags & ~OCCUPANCY_FLAGS) | (field.flags() & OCCUPANCY_FLAGS));
        field.setValue(value);
    }

    /** The graphic a square currently draws. */
    private int graphicAt(int x, int y) {
        return tileset.graphicFor(field(x, y).tile());
    }

    /** Whether a tile position is on the map. */
    public boolean contains(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }

    /** The square at a position. */
    public MapField field(int x, int y) {
        return fields[y * width + x];
    }

    /** The square at a position, or {@code null} if it is off the map. */
    public MapField fieldOrNull(int x, int y) {
        return contains(x, y) ? fields[y * width + x] : null;
    }

    /** The tile codes, for the renderer. */
    public int[] tileCodes() {
        int[] codes = new int[fields.length];
        for (int i = 0; i < fields.length; i++) {
            codes[i] = fields[i].tile();
        }
        return codes;
    }

    /**
     * Whether a footprint of {@code tileWidth} by {@code tileHeight} placed at
     * a position is passable for the given movement mask, ignoring one unit.
     *
     * @param mask       the flag a square must carry, {@link TileFlag#LAND_ALLOWED}
     *                   or {@link TileFlag#WATER_ALLOWED}
     * @param blocking   occupancy flags that count as blocked
     */
    public boolean isFootprintFree(int x, int y, int tileWidth, int tileHeight, long mask, long blocking) {
        // A mask naming both land and water belongs to a flyer, which is not
        // stopped by the terrain that stops everything else.
        boolean flying = (mask & TileFlag.WATER_ALLOWED) != 0 && (mask & TileFlag.LAND_ALLOWED) != 0;
        for (int dy = 0; dy < tileHeight; dy++) {
            for (int dx = 0; dx < tileWidth; dx++) {
                MapField field = fieldOrNull(x + dx, y + dy);
                if (field == null || !field.hasFlag(mask) || field.hasFlag(blocking)) {
                    return false;
                }
                if (!flying && field.hasFlag(TileFlag.UNPASSABLE)) {
                    return false;
                }
            }
        }
        return true;
    }
}
