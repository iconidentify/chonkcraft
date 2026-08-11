package net.chonkbase.chonkcraft.engine.pathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A square the search has already handed out can still be improved on.
 *
 * <p>{@code AStarFindPath} has no closed set. Its expansion has two arms and
 * neither of them asks whether the square has been expanded already
 *
 *
 * <pre>
 * if (AStarMatrix[eo].GetCostFromStart() == 0) {
 *     ... SetCostFromStart(new_cost); SetDirection(i); AStarAddNode(...);
 * } else if (new_cost &lt; AStarMatrix[eo].GetCostFromStart()) {
 *     ... SetCostFromStart(new_cost); SetDirection(i);
 *     const int j = AStarFindNode(eo);
 *     if (j == -1) { AStarAddNode(...); } else { AStarReplaceNode(j); }
 * }
 * </pre>
 *
 * <p>The second arm is reached by a square that has been taken off the open
 * set and expanded, and {@code AStarFindNode} returning -1 is exactly how it
 * says so; it goes straight back on. That matters here more than it would in
 * an ordinary A* because the heuristic is deliberately inadmissible --
 * {@code AStarCosts} multiplies Manhattan distance by eight against a step
 * that costs nine -- so the search dives at the goal and reaches squares by
 * the greedy route first. Correcting them afterwards is not a refinement; it
 * is how the route comes out the shape it does.
 *
 * <p>This implementation kept a closed set and refused to reconsider. On
 * {@code maps/demo/demo03} an ogre at 13,2 planning at a peasant at 8,3 got
 * five steps out of both engines and they parted at the second: upstream
 * north-west to 11,0 and this implementation west to 11,1, where a grunt stood, so it
 * slept ten cycles instead of walking. With the closed set gone both engines
 * answer {@code 7 7 6 5 4}, and that map's first divergence moved from cycle
 * 54 to 58.
 */
class ReconsideredSquareTest {

    private static final int SIZE = 16;

    /**
     * Ordinary ChonkCraft ground.
     *
     * <p>{@code SPEED_MASK} is not decoration. {@code CMapField} sets the move
     * cost to {@code 1 << (flags & MapFieldSpeedMask)} and every ChonkCraft tile
     * carries the whole of that mask, so ordinary ground costs eight and a
     * step costs nine. Left off, a step costs two against a crossing cost of
     * twenty, and a fixture built that way is a different search.
     */
    private static final long GROUND = TileFlag.LAND_ALLOWED | TileFlag.SPEED_MASK;

    /** Where the walker is, where it is going, and what stands between. */
    private static final int FROM_X = 10;
    private static final int FROM_Y = 3;
    private static final int TO_X = 12;
    private static final int TO_Y = 14;
    private static final int BLOCKER_X = 11;
    private static final int BLOCKER_Y = 4;

    private static PathFinder.Mover walker() {
        PathFinder.Occupancy occupancy = (x, y) ->
                x == BLOCKER_X && y == BLOCKER_Y
                        ? PathFinder.Occupancy.MOVING
                        : PathFinder.Occupancy.CLEAR;
        return new PathFinder.Mover(TileFlag.LAND_ALLOWED,
                TileFlag.BUILDING | TileFlag.LAND_UNIT | TileFlag.SEA_UNIT, 1, 1, occupancy);
    }

    @Test
    @DisplayName("a route committed to early is corrected once the cheaper way is found")
    void aHandedOutSquareIsReconsidered() {
        GameMap map = new GameMap(SIZE, SIZE, new Tileset());
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                map.field(x, y).setFlags(GROUND);
            }
        }
        // One unit, walking, on the square the greedy dive wants first. It is
        // crossable at twenty over the odds rather than refused, which is what
        // gives the search two answers to choose between.
        map.field(BLOCKER_X, BLOCKER_Y).setFlags(GROUND | TileFlag.LAND_UNIT);

        PathFinder.Path route = new PathFinder(map)
                .find(FROM_X, FROM_Y, TO_X, TO_Y, walker());

        assertEquals(PathFinder.Result.FOUND, route.result(),
                "open ground with one walker on it, and no route was found at all");
        // Two across and eleven down, eight ways to move: eleven steps is the
        // fewest there are, and the search finds a twelfth-step answer first
        // and has to take it back. A closed set is what stops it -- the square
        // that carries the correction has already been expanded by the time
        // the cheaper way to it turns up.
        assertEquals(11, route.length(),
                "the walker was sent " + route.length() + " steps where eleven is the whole"
                        + " diagonal distance. The search reached the tail of the route by its"
                        + " first greedy guess and never revisited it, which is what a closed"
                        + " set does and what AStarFindPath has none of");
    }
}
