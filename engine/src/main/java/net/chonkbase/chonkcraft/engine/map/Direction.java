package net.chonkbase.chonkcraft.engine.map;

/**
 * The eight compass headings a unit can face and step in.
 *
 * <p>Numbering matches {@code Heading2X} and {@code Heading2Y}: 0 is north and the sequence runs
 * clockwise. Paths are stored as sequences of these, so the numbering has to
 * agree with the C++ or a saved path would be read as a different route.
 */
public final class Direction {

    /** North, then clockwise. Index 8 is "no movement". */
    private static final int[] DELTA_X = {0, +1, +1, +1, 0, -1, -1, -1, 0};
    private static final int[] DELTA_Y = {-1, -1, 0, +1, +1, +1, 0, -1, 0};

    /**
     * Heading for a step, indexed by {@code [dx + 1][dy + 1]}.
     *
     * <p>Transcribed from {@code XY2Heading}. Note the
     * index order: the first subscript is the <em>x</em> delta, not y, which
     * reads backwards next to the usual row-then-column convention. Getting it
     * the other way round turns north into west and every unit walks the wrong
     * way.
     *
     * <p>The centre entry is 0 because a zero step has no heading; callers
     * check for that separately.
     */
    private static final int[][] FROM_DELTA = {
        {7, 6, 5},
        {0, 0, 4},
        {1, 2, 3}
    };

    /** How many real headings there are. */
    public static final int COUNT = 8;

    /** The index meaning "not moving". */
    public static final int NONE = 8;

    private Direction() {
    }

    /** Column step for a heading. */
    public static int deltaX(int heading) {
        return DELTA_X[heading];
    }

    /** Row step for a heading. */
    public static int deltaY(int heading) {
        return DELTA_Y[heading];
    }

    /**
     * The heading for a single-tile step.
     *
     * @throws IllegalArgumentException if the step is longer than one tile
     */
    public static int fromDelta(int dx, int dy) {
        if (dx < -1 || dx > 1 || dy < -1 || dy > 1) {
            throw new IllegalArgumentException("not a single-tile step: " + dx + "," + dy);
        }
        if (dx == 0 && dy == 0) {
            return NONE;
        }
        return FROM_DELTA[dx + 1][dy + 1];
    }

    /** Whether a heading moves along a diagonal. */
    public static boolean isDiagonal(int heading) {
        return heading != NONE && DELTA_X[heading] != 0 && DELTA_Y[heading] != 0;
    }
}
