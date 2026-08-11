package net.chonkbase.chonkcraft.engine.pathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Standing inside the goal is the one case the shortcut refuses to answer.
 *
 * <p>{@code AStarFindSimplePath} answers four questions before
 * {@code AStarFindPath} does anything else, and the second of them is a
 * refusal:
 *
 * <pre>
 * // At exact destination point already
 * if (goal == startPos &amp;&amp; minrange == 0) return PF_REACHED;
 *
 * // Don't allow unit inside destination area
 * if (goal.x &lt;= startPos.x &amp;&amp; startPos.x &lt;= goal.x + gw - 1
 *     &amp;&amp; goal.y &lt;= startPos.y &amp;&amp; startPos.y &lt;= goal.y + gh - 1) {
 *     return PF_FAILED;
 * }
 * </pre>
 *
 * <p>PF_FAILED is the shortcut declining, and everything it declines goes to
 * the full search: {@code AStarCleanUp}, {@code AStarMarkGoal}, and then
 * PF_REACHED from the goal test on the start square
 * The answer is the same. The cleanup is not --
 * {@code AStarCleanUp} is {@code CostMoveToCacheCleanUp}, and that memo is
 * what {@code AStarFindSimplePath} judges every one-square step in the game
 * by.
 *
 * <p>So a peasant standing on the ground its town hall is going up on empties
 * the memo for the whole map every time it asks whether it has arrived. On
 * {@code maps/skirmish/(3)critter-attack} that is one search, on cycle 53, and
 * without it a critter at 66,76 read a cost from cycle 40 on cycle 62 and
 * stepped at a square that had stopped being free. That map's first divergence
 * moved from cycle 63 to 108.
 */
class InsideTheGoalTest {

    private static GameMap openMap(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    /** A four-by-four site, which is what a town hall asks for. */
    private static final PathFinder.Goal SITE = new PathFinder.Goal(10, 10, 4, 4, 0, 0);

    private static PathFinder.Mover walker() {
        return PathFinder.Mover.of(TileFlag.LAND_ALLOWED, 1, 1);
    }

    @Test
    @DisplayName("a unit inside its goal is still told it has arrived")
    void theAnswerIsUnchanged() {
        PathFinder finder = new PathFinder(openMap(32));

        assertEquals(PathFinder.Result.REACHED,
                finder.find(12, 11, SITE, walker()).result(),
                "a unit standing in the middle of its own site was told something other than"
                        + " that it had arrived");
        assertEquals(PathFinder.Result.REACHED,
                finder.find(10, 10, SITE, walker()).result(),
                "and the exact corner is the one case the shortcut answers itself");
    }

    @Test
    @DisplayName("but asking empties the cost memo, which answering from inside it would not")
    void askingFromInsideTheGoalEmptiesTheMemo() {
        GameMap map = openMap(32);
        PathFinder finder = new PathFinder(map);

        // Fill the memo for one square, the way any step next door does.
        assertEquals(PathFinder.Result.FOUND,
                finder.find(20, 20, 21, 21, TileFlag.LAND_ALLOWED, 1, 1).result(),
                "the square is open, so this is a step");

        // The ground changes underneath it. Nothing has emptied the memo, so
        // the shortcut still answers from what it remembers.
        map.field(21, 21).setFlags(TileFlag.LAND_ALLOWED | TileFlag.UNPASSABLE);
        assertNotEquals(PathFinder.Result.UNREACHABLE,
                finder.find(20, 20, 21, 21, TileFlag.LAND_ALLOWED, 1, 1).result(),
                "the shortcut looked at the ground again, so this fixture is measuring"
                        + " something other than the memo");

        // And now a builder somewhere else entirely asks whether it has
        // arrived, from inside its own site. That question cannot be answered
        // by the shortcut, so it runs the search -- and the search begins by
        // emptying the memo.
        assertEquals(PathFinder.Result.REACHED,
                finder.find(12, 11, SITE, walker()).result(),
                "the builder was told something other than that it had arrived");

        assertEquals(PathFinder.Result.UNREACHABLE,
                finder.find(20, 20, 21, 21, TileFlag.LAND_ALLOWED, 1, 1).result(),
                "the step next door is still being judged by an answer from before the"
                        + " builder asked. A unit inside its goal rectangle is PF_FAILED from"
                        + " AStarFindSimplePath, and everything it refuses goes to the full"
                        + " search -- which opens with AStarCleanUp");
    }
}
