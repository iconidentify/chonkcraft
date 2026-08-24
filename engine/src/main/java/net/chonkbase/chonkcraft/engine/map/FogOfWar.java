package net.chonkbase.chonkcraft.engine.map;

import java.util.Arrays;

/**
 * What each player has seen and can currently see.
 *
 * <p>Implements the explored and currently-visible layers independently.
 *
 * <p>Warcraft II has two layers, and conflating them is the classic mistake.
 * <em>Unexplored</em> ground is solid black: you have never been there and the
 * terrain itself is hidden. <em>Explored but not currently visible</em> ground
 * is dimmed: you remember the lie of the land and any buildings you saw, but
 * not where the enemy's units are now. Only the second layer lifts and falls
 * as your units move.
 *
 * <p>Visibility is reference-counted rather than a flag, because several units
 * commonly see the same square and one of them walking away must not blind the
 * rest. That is what {@code CMapFieldPlayerInfo::Visible} counts upstream.
 */
public final class FogOfWar {

    private final int width;
    private final int height;
    private final int players;

    /** Per player, how many units currently see each square. */
    private final short[][] visible;

    /** Per player, how many units currently detect cloaked units on each square. */
    private final short[][] detected;

    /** Per player, whether each square has ever been seen. */
    private final boolean[][] explored;

    public FogOfWar(int width, int height, int players) {
        this.width = width;
        this.height = height;
        this.players = players;
        this.visible = new short[players][width * height];
        this.detected = new short[players][width * height];
        this.explored = new boolean[players][width * height];
    }

    /** Whether a player can see a square right now. */
    public boolean isVisible(int player, int x, int y) {
        return inside(x, y) && visible[player][y * width + x] > 0;
    }

    /** Whether a player can detect a cloaked unit on a square right now. */
    public boolean isDetected(int player, int x, int y) {
        return inside(x, y) && detected[player][y * width + x] > 0;
    }

    /** Whether a player has ever seen a square. */
    public boolean isExplored(int player, int x, int y) {
        return inside(x, y) && explored[player][y * width + x];
    }

    /**
     * How a square should be drawn for a player.
     */
    public Visibility visibility(int player, int x, int y) {
        if (!inside(x, y)) {
            return Visibility.UNEXPLORED;
        }
        int index = y * width + x;
        if (visible[player][index] > 0) {
            return Visibility.VISIBLE;
        }
        return explored[player][index] ? Visibility.EXPLORED : Visibility.UNEXPLORED;
    }

    /** The three states a square can be in for a given player. */
    public enum Visibility {
        /** Never seen: solid black, terrain included. */
        UNEXPLORED,
        /** Seen before, not seen now: terrain remembered, units hidden. */
        EXPLORED,
        /** In sight of one of the player's units. */
        VISIBLE
    }

    /**
     * Adds a unit's sight to the map.
     *
     * @param onLit called for each square this grant lit that was dark
     *              before, or {@code null}; the caller uses it to raise the
     *              per-unit counts of whatever stands there, which is
     *              upstream's {@code UnitsOnTileMarkSeen} walk inside
     *              {@code MapMarkTileSight}
     * @see #forEachInSight
     */
    public void addSight(int player, int tileX, int tileY, int tileWidth, int tileHeight,
            int range, IndexAction onLit) {
        forEachInSight(tileX, tileY, tileWidth, tileHeight, range, index -> {
            if (visible[player][index]++ == 0 && onLit != null) {
                onLit.accept(index);
            }
            explored[player][index] = true;
        });
    }

    public void addSight(int player, int tileX, int tileY, int tileWidth, int tileHeight,
            int range) {
        addSight(player, tileX, tileY, tileWidth, tileHeight, range, null);
    }

    /**
     * Removes a unit's sight again.
     *
     * @param onDark called for each square the removal darkened, upstream's
     *               {@code UnitsOnTileUnmarkSeen} walk
     */
    public void removeSight(int player, int tileX, int tileY, int tileWidth, int tileHeight,
            int range, IndexAction onDark) {
        forEachInSight(tileX, tileY, tileWidth, tileHeight, range, index -> {
            if (visible[player][index] > 0 && --visible[player][index] == 0
                    && onDark != null) {
                onDark.accept(index);
            }
        });
    }

    public void removeSight(int player, int tileX, int tileY, int tileWidth, int tileHeight,
            int range) {
        removeSight(player, tileX, tileY, tileWidth, tileHeight, range, null);
    }

    /** Adds a detector's coverage without revealing or exploring terrain. */
    public void addDetection(int player, int tileX, int tileY,
            int tileWidth, int tileHeight, int range, IndexAction onLit) {
        forEachInSight(tileX, tileY, tileWidth, tileHeight, range, index -> {
            if (detected[player][index]++ == 0 && onLit != null) {
                onLit.accept(index);
            }
        });
    }

    public void addDetection(int player, int tileX, int tileY,
            int tileWidth, int tileHeight, int range) {
        addDetection(player, tileX, tileY, tileWidth, tileHeight, range, null);
    }

    /** Removes a detector's reference-counted coverage. */
    public void removeDetection(int player, int tileX, int tileY,
            int tileWidth, int tileHeight, int range, IndexAction onDark) {
        forEachInSight(tileX, tileY, tileWidth, tileHeight, range, index -> {
            if (detected[player][index] > 0 && --detected[player][index] == 0
                    && onDark != null) {
                onDark.accept(index);
            }
        });
    }

    public void removeDetection(int player, int tileX, int tileY,
            int tileWidth, int tileHeight, int range) {
        removeDetection(player, tileX, tileY, tileWidth, tileHeight, range, null);
    }

    /** One square of the map, named by its row-major index. */
    public interface IndexAction {
        void accept(int index);
    }

    /**
     * The shape a unit sees, as {@code CFieldOfView::ProceedSimpleRadial}
     * draws it.
     *
     * <p>This was a plain {@code dx*dx + dy*dy <= range*range} disc, which is
     * the obvious reading of "sees a circle" and is not what the game does.
     * Two things are different, and both are visible.
     *
     * <p>The radius is measured against {@code (range + 1)^2 - 1} rather than
     * {@code range^2}, so the disc is a good deal wider than the naive one --
     * a unit with sight 5 sees eleven squares across its own row, not nine.
     *
     * <p>More importantly the widths per row come out rounder. The naive disc
     * ends in a single square at the top and bottom: at {@code dy == range}
     * only {@code dx == 0} fits. That leaves a one-square spike sticking out
     * of the top of every unit's sight, and once the fog is drawn with corner
     * masks instead of filled squares those spikes become holes -- a lone
     * explored square whose eight neighbours are all hidden has no mask that
     * fits it, so the fog simply fails to cover it. Upstream's widths for
     * sight 5 run 5, 5, 5, 4, 3, which tapers instead of spiking.
     *
     * <p>The rows the unit itself occupies get the full range either side,
     * which is what makes a four by four building see as far from its right
     * edge as from its left.
     */
    private void forEachInSight(int tileX, int tileY, int tileWidth, int tileHeight,
            int range, IndexAction action) {
        int unitWidth = Math.max(1, tileWidth);
        int unitHeight = Math.max(1, tileHeight);
        // (range + 1)^2 - 1, upstream's radius. Never negative below: the
        // largest offset squared is range^2, and this exceeds it by 2 * range.
        int radiusSquared = (range + 1) * (range + 1) - 1;

        // Above the unit, from the topmost row it can reach down to its own.
        int minY = Math.max(-range, -tileY);
        for (int offsetY = minY; offsetY != 0; offsetY++) {
            int offsetX = isqrt(radiusSquared - offsetY * offsetY);
            markRow(tileY + offsetY, tileX - offsetX, tileX + unitWidth + offsetX, action);
        }
        // The rows the unit stands on.
        for (int offsetY = 0; offsetY < unitHeight; offsetY++) {
            markRow(tileY + offsetY, tileX - range, tileX + unitWidth + range, action);
        }
        // Below it. Measured from the far edge of the footprint, which is why
        // upstream squares offsetY + 1 here and offsetY above.
        int maxY = Math.min(range, height - tileY - unitHeight);
        for (int offsetY = 0; offsetY < maxY; offsetY++) {
            int offsetX = isqrt(radiusSquared - (offsetY + 1) * (offsetY + 1));
            markRow(tileY + unitHeight + offsetY,
                    tileX - offsetX, tileX + unitWidth + offsetX, action);
        }
    }

    /** One row of a sight shape, clipped to the map. The end is exclusive. */
    private void markRow(int y, int fromX, int toX, IndexAction action) {
        if (y < 0 || y >= height) {
            return;
        }
        int base = y * width;
        for (int x = Math.max(0, fromX); x < Math.min(width, toX); x++) {
            action.accept(base + x);
        }
    }

    /** Integer square root, as upstream's {@code isqrt}. */
    private static int isqrt(int value) {
        if (value <= 0) {
            return 0;
        }
        int root = (int) Math.sqrt(value);
        // Guard the rounding at the edges rather than trusting the double.
        while (root * root > value) {
            root--;
        }
        while ((root + 1) * (root + 1) <= value) {
            root++;
        }
        return root;
    }

    /**
     * Which of the sixteen fog frames covers the fog edge on a square.
     *
     * <p>Implements {@code CFogOfWar::GetFogTile}. Fog is not drawn as a grid
     * of opaque squares -- that is what makes an edge look like a staircase.
     * The tileset ships sixteen 32 by 32 masks in its first sixteen tiles, one
     * for each way the four <em>corners</em> of a square can be covered, and
     * the boundary is drawn with those. It is marching squares, and it is why
     * the original's fog has rounded corners and a dithered fringe.
     *
     * <p>The bit each neighbour contributes is which corners it touches:
     * north sets both northern corners, north-west sets only the north-west
     * one, and so on. That is what upstream's odd-looking constants -- 2, 3,
     * 1, 10, 5, 8, 12, 4 -- are, once you notice 3 is 2|1 and 12 is 8|4.
     *
     * <p>Squares off the edge of the map contribute nothing, so the border of
     * the map is not permanently fringed with fog it cannot see past.
     *
     * @return an index into the tileset's first sixteen tiles; zero means
     *     nothing to draw
     */
    public int fogFrame(int player, int x, int y) {
        return frame(player, x, y, false);
    }

    /**
     * Team-aware form of {@link #fogFrame(int, int, int)}.
     *
     * <p>Retail {@code CFogOfWar::GetFogTile} asks
     * {@code CMapFieldPlayerInfo::TeamVisibilityState} for every neighbour.
     * The lookup therefore has to be the same combined view used to decide
     * whether the centre tile is lit; mixing a team centre with local-only
     * neighbours cuts black triangular masks into the seam between allies.</p>
     */
    public int fogFrame(int x, int y, VisibilityLookup lookup) {
        return frame(x, y, false, lookup);
    }

    /**
     * The same, for the boundary between explored ground and ground never
     * seen at all.
     *
     * <p>Drawn over {@link #fogFrame} and near enough opaque, which is what
     * makes the black edge of the unexplored map curve rather than step.
     */
    public int blackFrame(int player, int x, int y) {
        return frame(player, x, y, true);
    }

    /** Team-aware form of {@link #blackFrame(int, int, int)}. */
    public int blackFrame(int x, int y, VisibilityLookup lookup) {
        return frame(x, y, true, lookup);
    }

    /** Supplies the effective visibility state used to choose a fog mask. */
    @FunctionalInterface
    public interface VisibilityLookup {
        Visibility visibility(int x, int y);
    }

    /**
     * Upstream's {@code TiledFogTable}: the corner mask, as a four bit number,
     * turned into the frame in the sheet that draws it.
     *
     * <p>Entry 0 is empty ground and entry 15 is fully covered; both map to
     * frame 0, which means "draw nothing" -- a fully covered square is filled
     * rather than tiled, and an uncovered one needs no fog at all.
     */
    private static final int[] TILED_FOG_TABLE = {
        0, 11, 10, 2, 13, 6, 14, 3, 12, 15, 4, 1, 8, 9, 7, 0
    };

    private int frame(int player, int x, int y, boolean unexploredOnly) {
        return frame(x, y, unexploredOnly,
                (tileX, tileY) -> visibility(player, tileX, tileY));
    }

    private int frame(int x, int y, boolean unexploredOnly,
            VisibilityLookup lookup) {
        int index = 0;
        boolean hasNorth = y > 0;
        boolean hasSouth = y < height - 1;
        boolean hasWest = x > 0;
        boolean hasEast = x < width - 1;

        if (hasNorth) {
            if (hasWest && covered(lookup, x - 1, y - 1, unexploredOnly)) {
                index |= 2;
            }
            if (covered(lookup, x, y - 1, unexploredOnly)) {
                index |= 3;
            }
            if (hasEast && covered(lookup, x + 1, y - 1, unexploredOnly)) {
                index |= 1;
            }
        }
        if (hasWest && covered(lookup, x - 1, y, unexploredOnly)) {
            index |= 10;
        }
        if (hasEast && covered(lookup, x + 1, y, unexploredOnly)) {
            index |= 5;
        }
        if (hasSouth) {
            if (hasWest && covered(lookup, x - 1, y + 1, unexploredOnly)) {
                index |= 8;
            }
            if (covered(lookup, x, y + 1, unexploredOnly)) {
                index |= 12;
            }
            if (hasEast && covered(lookup, x + 1, y + 1, unexploredOnly)) {
                index |= 4;
            }
        }
        return TILED_FOG_TABLE[index];
    }

    /**
     * Whether a neighbour is hidden enough to pull fog over this square's
     * corner.
     *
     * <p>Upstream writes the two cases as an if/else that sets the same bit in
     * two different accumulators. Unexplored ground is also not visible, so
     * the visible test alone covers both.
     */
    private static boolean covered(VisibilityLookup lookup,
            int x, int y, boolean unexploredOnly) {
        Visibility seen = lookup.visibility(x, y);
        return unexploredOnly ? seen == Visibility.UNEXPLORED
                : seen != Visibility.VISIBLE;
    }

    /** Reveals the whole map to a player, as the reveal-map cheat does. */
    public void revealAll(int player) {
        Arrays.fill(explored[player], true);
    }

    /** Forgets everything a player knows. */
    public void reset(int player) {
        Arrays.fill(visible[player], (short) 0);
        Arrays.fill(detected[player], (short) 0);
        Arrays.fill(explored[player], false);
    }

    /** How many squares a player has explored, for tests and statistics. */
    public int exploredCount(int player) {
        int count = 0;
        for (boolean seen : explored[player]) {
            if (seen) {
                count++;
            }
        }
        return count;
    }

    public int playerCount() {
        return players;
    }

    private boolean inside(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }
}
