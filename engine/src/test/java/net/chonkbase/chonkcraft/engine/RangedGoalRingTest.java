package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.pathfinder.PathFinder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A ranged route ends inside upstream's ring, not one square shy of it.
 *
 * <p>{@code AStarMarkGoal} draws the goal region with
 * {@code MinMaxRangeVisitor}: each row's half-width is
 * {@code isqrt(square(range + 1) - square(dy) - 1)} with {@code dy} taken
 * corner to corner -- top of mover to top of goal above the box, bottom to
 * bottom below -- and the mover's extra tiles folded into the band bounds
 * rather than the distance. Measuring
 * footprint-to-footprint with {@code MapDistanceBetweenTypes}' rounding
 * instead agrees almost everywhere, and disagrees exactly one ring out on
 * the diagonals, where the rounded footprint distance says four and the
 * row's window is nought tiles wide.
 *
 * <p>campaigns/human/level09h is the measurement: a two-by-two destroyer at
 * 28,36 chasing the balloon whose box sits at 32,42 with reach four.
 * Upstream's route runs two steps and ends on row 38; a route cut short at
 * 29,37 -- rounded footprint distance four, inside the phantom ring --
 * left the implementation's ship a square and a spent-route pause behind upstream
 * from cycle 18, which was that map's first divergence.
 */
class RangedGoalRingTest {

    private static GameMap water(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.WATER_ALLOWED);
            }
        }
        return map;
    }

    @Test
    @DisplayName("the route to within four of a two-by-two box runs to the ring's own row")
    void aRangedRouteRunsToTheRingsOwnRow() {
        PathFinder finder = new PathFinder(water(48));
        // level09h's exact ask, taken from both engines' path channels:
        // JPATHDBG/PATHDBG "from 28,36 goal 32,42 size 2x2 range 0-4".
        PathFinder.Path path = finder.find(28, 36,
                new PathFinder.Goal(32, 42, 2, 2, 0, 4),
                PathFinder.Mover.of(TileFlag.WATER_ALLOWED, 2, 2));

        assertEquals(PathFinder.Result.FOUND, path.result(),
                "open water from ship to balloon must route");
        assertEquals(2, path.length(),
                "the route must run two steps to the ring's own row, as upstream's"
                        + " does: one step lands on row 37, where the ring's window is"
                        + " isqrt(square(5) - square(5) - 1) tiles wide -- none -- and"
                        + " a search that stops there has believed the rounded footprint"
                        + " distance instead of the ring");
    }
}
