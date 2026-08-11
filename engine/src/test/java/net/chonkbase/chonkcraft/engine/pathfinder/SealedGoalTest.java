package net.chonkbase.chonkcraft.engine.pathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A goal nothing can walk up to follows upstream's bounded-search verdict.
 *
 * <p>This is the twelve-fold slowdown on {@code levelx12h}, and it was never
 * about the map being large -- {@code level12h} is a 128x128 map with 187
 * units and simulates at 244 nanoseconds per unit-cycle, while
 * {@code levelx12h} is 96x96 with 257 units and cost 3018.
 *
 * <p>Two of that map's guard towers are built into rock: 2x2 buildings with
 * impassable terrain on all eight sides, which is a deliberate piece of map
 * design -- they can be shot at and never stormed. An orc army is camped
 * beside them. Every grunt in it asked, once a cycle, whether it could walk to
 * a tower; A* has no way to answer that except by expanding every square it
 * can reach, so each question cost the full four-thousand-node budget. Three
 * hundred and fifty-four of those searches over nine hundred cycles came to
 * rather more than half of all the route-finding the map did.
 *
 * <p>A search that exhausts its budget hands back the closest square it
 * managed as {@code FOUND}; only an exhausted open set answers
 * {@code UNREACHABLE}. That distinction is surprising for a sealed goal, but
 * it is upstream's distinction and long routes rely on it.
 */
class SealedGoalTest {

    private static GameMap openMap(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    /**
     * A square ringed by rock is unreachable however small the budget.
     *
     * <p>The budget is deliberately tiny, because that is the case that used
     * to give the wrong answer: with room to expand every square the search
     * runs the open set dry and says unreachable, but the moment it runs out
     * of budget first it says {@code FOUND} and hands back a route to
     * somewhere else entirely. On a real map the budget always runs out first,
     * which is why this only ever showed up in play.
     */
    @Test
    @DisplayName("a bounded search returns its partial route even for a sealed goal")
    void aSealedGoalFollowsTheSearchBudgetVerdict() {
        GameMap map = openMap(64);
        for (int y = 30; y <= 34; y++) {
            for (int x = 30; x <= 34; x++) {
                if (x != 32 || y != 32) {
                    map.field(x, y).setFlags(TileFlag.LAND_ALLOWED | TileFlag.UNPASSABLE);
                }
            }
        }

        PathFinder thrifty = new PathFinder(map, 64);
        assertEquals(PathFinder.Result.FOUND,
                thrifty.find(2, 2, 32, 32, TileFlag.LAND_ALLOWED, 1, 1).result(),
                "AStarFindPath saves the best partial route when its node budget expires");

        // With enough room to exhaust the open set, the same topology is
        // finally proved unreachable. Upstream deliberately distinguishes
        // that from exhausting the search budget.
        PathFinder generous = new PathFinder(map, 1 << 20);
        assertEquals(PathFinder.Result.UNREACHABLE,
                generous.find(2, 2, 32, 32, TileFlag.LAND_ALLOWED, 1, 1).result(),
                "a bigger budget must not change the answer");
    }

    /**
     * The check must not refuse anything that is merely far away.
     *
     * <p>The distinction the pathfinder draws is that an exhausted open set
     * means no route exists while an exhausted budget means the search was
     * taking a while, and a unit told to walk a long way should still set off.
     * A pre-check that fired on anything but a genuinely sealed goal would
     * collapse that distinction and strand every unit given a distant order.
     */
    @Test
    @DisplayName("a distant goal still returns the way it got, and an open one is found")
    void anOpenGoalIsUnaffected() {
        GameMap map = openMap(64);
        PathFinder thrifty = new PathFinder(map, 12);
        PathFinder.Path partial = thrifty.find(2, 2, 60, 60, TileFlag.LAND_ALLOWED, 1, 1);
        assertEquals(PathFinder.Result.FOUND, partial.result(),
                "a route plainly exists, the search just could not afford to finish it");
        assertTrue(partial.headings().length > 0, "and that should be somewhere to walk");

        assertEquals(PathFinder.Result.FOUND,
                new PathFinder(map).find(2, 2, 60, 60, TileFlag.LAND_ALLOWED, 1, 1).result(),
                "with budget to spare the same route is found outright");

        // One gap in the ring is enough. The check asks whether any of the
        // eight squares round the goal can be stood on, not whether the goal
        // looks enclosed.
        GameMap chinked = openMap(64);
        for (int y = 31; y <= 33; y++) {
            for (int x = 31; x <= 33; x++) {
                if (x != 32 || y != 32) {
                    chinked.field(x, y).setFlags(TileFlag.LAND_ALLOWED | TileFlag.UNPASSABLE);
                }
            }
        }
        chinked.field(31, 32).setFlags(TileFlag.LAND_ALLOWED);
        assertEquals(PathFinder.Result.FOUND,
                new PathFinder(chinked).find(2, 2, 32, 32, TileFlag.LAND_ALLOWED, 1, 1).result(),
                "one open square beside the goal is a way in");
    }

    @Test
    @DisplayName("a bounded detour may begin by walking away from a ranged goal")
    void thePartialRouteIsTheOpenSetsBestRatherThanTheClosestVisitedSquare() {
        GameMap map = openMap(64);
        // The destination is only two columns away, but every square on its
        // near range-one edge is behind this wall.  A path would have to walk
        // away from the goal and around an end of the wall.  With a tiny
        // budget, therefore, no visited square is closer to (4,32) than the
        // start at (2,32).
        for (int y = 0; y < 64; y++) {
            map.field(3, y).setFlags(TileFlag.LAND_ALLOWED | TileFlag.UNPASSABLE);
        }

        PathFinder.Path partial = new PathFinder(map, 12).find(2, 32,
                new PathFinder.Goal(4, 32, 1, 1, 0, 1),
                PathFinder.Mover.of(TileFlag.LAND_ALLOWED, 1, 1));

        assertEquals(PathFinder.Result.FOUND, partial.result(),
                "AStarSavePath uses the current open-set minimum at budget expiry");
        assertTrue(partial.length() > 0,
                "the partial route was discarded because it did not improve Manhattan distance");
    }

    /**
     * A unit boxed in by its own fellows can still be asked to go somewhere.
     *
     * <p>The searcher's own square is exempt from the check. Without the
     * exemption a unit standing on a square the planner would refuse to
     * *enter* -- which is most of them, once somebody is standing there -- gets
     * told its neighbour is unreachable.
     */
    @Test
    @DisplayName("the searcher's own square counts as a way out")
    void theSearcherCanAlwaysLeaveWhereItStands() {
        GameMap map = openMap(32);
        // Everything around the goal is rock except the square the searcher is
        // standing on, which the planner would otherwise refuse to enter.
        for (int y = 15; y <= 17; y++) {
            for (int x = 15; x <= 17; x++) {
                if (x != 16 || y != 16) {
                    map.field(x, y).setFlags(TileFlag.LAND_ALLOWED | TileFlag.UNPASSABLE);
                }
            }
        }
        map.field(15, 16).setFlags(TileFlag.LAND_ALLOWED | TileFlag.LAND_UNIT);

        PathFinder finder = new PathFinder(map);
        PathFinder.Mover boxedIn = new PathFinder.Mover(TileFlag.LAND_ALLOWED,
                TileFlag.BUILDING | TileFlag.LAND_UNIT | TileFlag.SEA_UNIT, 1, 1,
                (x, y) -> PathFinder.Occupancy.STATIONARY);
        assertEquals(PathFinder.Result.FOUND,
                finder.find(15, 16, 16, 16, boxedIn).result(),
                "it is standing right next to the goal; it can certainly step onto it");
    }

    /**
     * A wide unit does not turn an exact blocked square into an area goal.
     *
     * <p>The footprint-expanded candidate rectangle is appropriate when the
     * order may finish beside a target.  For an exact move it used to let the
     * clear top-left position northwest of the goal stand in for the blocked
     * goal itself, then the search's goal exception walked onto the obstacle.
     */
    @Test
    @DisplayName("a wide mover must be able to enter an exact point goal")
    void aWideMoverCannotMarkABlockedPointThroughAnOverlappingPosition() {
        GameMap map = openMap(32);
        map.field(11, 10).setFlags(TileFlag.LAND_ALLOWED | TileFlag.BUILDING);

        PathFinder finder = new PathFinder(map, 1 << 20);
        PathFinder.Mover wide = new PathFinder.Mover(TileFlag.LAND_ALLOWED,
                TileFlag.BUILDING, 2, 2,
                (x, y) -> PathFinder.Occupancy.CLEAR);

        assertEquals(PathFinder.Result.UNREACHABLE,
                finder.find(5, 5, PathFinder.Goal.square(10, 10), wide).result(),
                "the exact goal's complete 2x2 footprint includes the building");
        assertEquals(PathFinder.Result.FOUND,
                finder.find(5, 5, new PathFinder.Goal(10, 10, 1, 1, 0, 1), wide).result(),
                "a range-one order may legitimately finish with its footprint beside the goal");
    }

}
