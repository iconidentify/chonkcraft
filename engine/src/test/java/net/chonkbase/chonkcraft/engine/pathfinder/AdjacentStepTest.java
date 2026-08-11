package net.chonkbase.chonkcraft.engine.pathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A step to the square next door never reaches the search.
 *
 * <p>{@code AStarFindPath} opens with {@code AStarFindSimplePath}
 * which answers four cases without
 * looking at the map at all -- already there, already in range, standing
 * inside the goal rectangle, or one square away. The last of those is the one
 * with consequences:
 *
 * <pre>
 * if (abs(diff.x) &lt;= 1 &amp;&amp; abs(diff.y) &lt;= 1) {
 *     if (CostMoveTo(GetIndex(goal.x, goal.y), unit) == -1) return PF_UNREACHABLE;
 *     path[0] = XY2Heading[diff.x + 1][diff.y + 1];
 *     return 1;
 * }
 * </pre>
 *
 * <p>It runs <em>before</em> {@code AStarCleanUp}, and {@code CostMoveTo} is a
 * memo that only {@code AStarCleanUp} empties. So the shortcut is judged by
 * whatever the last full search left in that square's slot, however many
 * cycles ago that was. On {@code maps/skirmish/(3)critter-attack} a critter at
 * 69,23 asks three times to step onto 69,22, and the running binary prints
 * {@code cached=-1 fresh=28} on cycle 24 -- unset, so 28 is worked out and
 * kept -- and {@code cached=29 fresh=-1} on cycle 46, by which time the critter
 * standing there has stopped moving and the square is genuinely refused.
 * Upstream steps anyway, on a number twenty-two cycles old, and the move then
 * answers {@code PF_WAIT} because {@code UnitCanBeAt} is asked separately and
 * does not consult the memo.
 */
class AdjacentStepTest {

    private static GameMap openMap(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    @Test
    @DisplayName("a step to the next square costs no search at all")
    void anAdjacentGoalSkipsTheSearch() {
        GameMap map = openMap(32);
        PathFinder finder = new PathFinder(map);

        PathFinder.nodesExpanded = 0;
        PathFinder.Path step = finder.find(10, 10, 11, 11, TileFlag.LAND_ALLOWED, 1, 1);

        assertEquals(PathFinder.Result.FOUND, step.result(), "the next square is walkable");
        assertEquals(1, step.headings().length, "one square away is one step");
        assertEquals(0, PathFinder.nodesExpanded,
                "the search ran. AStarFindSimplePath answers the one-square case before"
                        + " AStarFindPath does anything else, and a unit stepping between two"
                        + " squares is most of the pathfinding a Warcraft II game does");
    }

    @Test
    @DisplayName("and it is refused when the square cannot be entered, cache cold")
    void anAdjacentGoalIsStillRefused() {
        GameMap map = openMap(32);
        map.field(11, 11).setFlags(TileFlag.LAND_ALLOWED | TileFlag.UNPASSABLE);
        PathFinder finder = new PathFinder(map);

        assertEquals(PathFinder.Result.UNREACHABLE,
                finder.find(10, 10, 11, 11, TileFlag.LAND_ALLOWED, 1, 1).result(),
                "the shortcut still asks what the square costs; it is only which answer it"
                        + " gets that is peculiar");
    }

    @Test
    @DisplayName("the shortcut answers from the memo until a full search empties it")
    void theCostMemoOutlivesTheGroundItDescribes() {
        GameMap map = openMap(32);
        PathFinder finder = new PathFinder(map);

        // Asked once while the square is open, which fills the memo.
        assertEquals(PathFinder.Result.FOUND,
                finder.find(10, 10, 11, 11, TileFlag.LAND_ALLOWED, 1, 1).result(),
                "the square is open, so this is a step");

        // The ground changes underneath it. Nothing empties the memo, because
        // nothing has run a full search.
        map.field(11, 11).setFlags(TileFlag.LAND_ALLOWED | TileFlag.UNPASSABLE);

        assertNotEquals(PathFinder.Result.UNREACHABLE,
                finder.find(10, 10, 11, 11, TileFlag.LAND_ALLOWED, 1, 1).result(),
                "the shortcut looked at the ground again. CostMoveTo is a memo and only"
                        + " AStarCleanUp empties it, so a step next door is judged by the"
                        + " last full search's answer -- which is exactly what lets a critter"
                        + " on (3)critter-attack step at a square that stopped being free"
                        + " twenty-two cycles earlier");

        // A full search is what clears it, and after that the shortcut sees
        // the world as it is.
        finder.find(2, 2, 25, 25, TileFlag.LAND_ALLOWED, 1, 1);

        assertEquals(PathFinder.Result.UNREACHABLE,
                finder.find(10, 10, 11, 11, TileFlag.LAND_ALLOWED, 1, 1).result(),
                "a full search calls AStarCleanUp, which is CostMoveToCacheCleanUp, so the"
                        + " next shortcut asks the map afresh");
    }

    @Test
    @DisplayName("a full search memoizes every square in its marked goal region")
    void markingTheGoalRegionFillsTheCostMemo() {
        GameMap map = openMap(32);
        PathFinder finder = new PathFinder(map);
        PathFinder.Mover walker =
                PathFinder.Mover.of(TileFlag.LAND_ALLOWED, 1, 1);

        // This is the far edge of the six-square goal ring. AStarMarkGoal
        // visits every square in that ring and asks CostMoveTo before the
        // search expands its first node, even though a route approaching from
        // the north-west will finish without ever exploring this edge.
        map.field(26, 20).setFlags(TileFlag.LAND_ALLOWED | TileFlag.UNPASSABLE);
        assertNotEquals(PathFinder.Result.UNREACHABLE,
                finder.find(5, 5,
                        new PathFinder.Goal(20, 20, 1, 1, 0, 6), walker).result(),
                "the fixture's ranged goal was not reachable");

        // Change the ground without another full search. The adjacent
        // shortcut must read the blocked answer left by AStarMarkGoal.
        map.field(26, 20).setFlags(TileFlag.LAND_ALLOWED);
        assertEquals(PathFinder.Result.UNREACHABLE,
                finder.find(27, 20, 26, 20,
                        TileFlag.LAND_ALLOWED, 1, 1).result(),
                "the goal marker did not populate CostMoveToCache on the"
                        + " unvisited side of its range ring");
    }
}
