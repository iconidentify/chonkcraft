package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A march aimed at a square nobody can stand on widens; it does not go round.
 *
 * <p>{@code COrder_Attack::MoveToTarget} says what to do when the walk answers
 * PF_UNREACHABLE and the order has no unit to chase, and it is three lines
 *
 *
 * <pre>
 * if (err == PF_UNREACHABLE) {
 *     if (!this-&gt;HasGoal()) {
 *         // When attack-moving we have to allow a bigger range (PF)
 *         this-&gt;Range++;
 *         unit.Wait = 5;
 *         return;
 *     }
 *     ...
 * }
 * </pre>
 *
 * <p>It widens the goal by a square and stands there for five cycles. It does
 * not look at the eight squares beside the destination and pick one, which is
 * what this implementation did -- {@code findRouteToOrBeside}, the form a worker walking
 * at a tree needs, borrowed for a march that is a plain move.
 *
 * <p>The difference is a search and a walk that upstream never makes. On
 * {@code maps/demo/demo03} a grunt at 11,1 marching on 13,3, which a friendly
 * axethrower is standing on, stayed where it was upstream and here went round
 * the eight squares beside it, found six it could reach and walked to the
 * nearest. That map's first divergence was cycle 61.
 */
class MarchWidensTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType soldier() {
        UnitType type = new UnitType("unit-footman");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setNumDirections(8);
        // An attack-move is an attack order, and CommandAttack turns one down
        // for a type that cannot attack.
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(6);
        type.setMaxAttackRange(1);
        type.setSightRange(4);
        AnimationSet set = new AnimationSet("soldier");
        set.put(AnimationSet.State.STILL, Animation.parse("still",
                List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.ATTACK, Animation.parse("attack", List.of(
                "unbreakable begin", "frame 0", "attack", "unbreakable end", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    /** Where the marcher starts, and the square it is aimed at. */
    private static final int FROM_X = 5;
    private static final int FROM_Y = 10;
    private static final int TO_X = 15;
    private static final int TO_Y = 10;

    /** {@code unit.Wait = 5}, and the cycle that spends the search. */
    private static final int WIDEN_WAIT = 5;

    @Test
    @DisplayName("a march on an occupied square stands for five cycles before widening")
    void anOccupiedDestinationIsWidenedRatherThanStepPastedAround() {
        World world = grassWorld();
        Unit marcher = world.createUnit(soldier(), 0, FROM_X, FROM_Y);
        // Somebody of its own side standing exactly where it was sent. A
        // stationary unit is a wall to the planner, so the search can only
        // answer unreachable.
        world.createUnit(soldier(), 0, TO_X, TO_Y);

        assertTrue(world.orderAttackMove(marcher, TO_X, TO_Y), "the order was refused");

        int firstStep = -1;
        for (int cycle = 1; cycle <= 200 && firstStep < 0; cycle++) {
            world.tick();
            if (marcher.tileX() != FROM_X || marcher.tileY() != FROM_Y
                    || marcher.offsetX() != 0 || marcher.offsetY() != 0) {
                firstStep = cycle;
            }
        }

        assertTrue(firstStep > 0, "the marcher never set off at all, so nothing below counts");
        assertTrue(firstStep > WIDEN_WAIT,
                "the marcher was moving by cycle " + firstStep + ". The first search can only"
                        + " answer unreachable, and upstream's answer to that is Range++ and"
                        + " five cycles of standing -- not a second search for somewhere"
                        + " beside the destination to walk to instead");

        // And it does get there in the end: widening is not refusing.
        for (int cycle = 0; cycle < 400 && marcher.order() != Unit.Order.STILL; cycle++) {
            world.tick();
        }
        assertEquals(Unit.Order.STILL, marcher.order(), "the march never ended");
        assertTrue(marcher.distanceTo(TO_X, TO_Y) <= 1,
                "the marcher stopped " + marcher.distanceTo(TO_X, TO_Y) + " squares from where"
                        + " it was sent. Widening the range is what lets a march aimed at"
                        + " ground nobody can stand on finish next to it");
    }

    @Test
    @DisplayName("a march keeps the route it was given instead of re-planning every cycle")
    void theRouteSurvivesTheNextCycle() {
        World world = grassWorld();
        Unit marcher = world.createUnit(soldier(), 0, 2, 2);
        assertTrue(world.orderAttackMove(marcher, 26, 26), "the order was refused");

        // Let it lay a course over open ground.
        int laid = -1;
        for (int cycle = 1; cycle <= 40 && laid < 0; cycle++) {
            world.tick();
            if (marcher.pathLength() > 1) {
                laid = cycle;
            }
        }
        assertTrue(laid > 0, "the marcher never laid a course at all");

        // A march plans with no path goal, because it re-plans for itself when
        // the route runs out -- and stepAttackMove reads a goal that is still
        // set as the mark of a route left over from a chase and throws the
        // route away. Left set, that is a fresh search every cycle.
        long before = net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.searchesRun;
        int had = marcher.pathLength();
        world.tick();

        assertEquals(before, net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.searchesRun,
                "the marcher planned again on the very next cycle, having just been given a "
                        + had + "-step route");
        assertTrue(marcher.pathLength() > 0,
                "the route it had just been given was thrown away inside one cycle");
    }

    /** A gunboat: what it was sent at is in reach long before it gets there. */
    private static UnitType shipOfTheLine() {
        UnitType type = soldier();
        type.setMaxAttackRange(8);
        return type;
    }

    @Test
    @DisplayName("a marcher refused a step while already in reach has arrived, and stops there")
    void aRefusedStepInsideItsOwnRangeEndsTheMarch() {
        World world = grassWorld();
        Unit gunboat = world.createUnit(shipOfTheLine(), 0, 5, 10);
        assertTrue(world.orderAttackMove(gunboat, 12, 10), "the order was refused");

        // One cycle to lay the course, and then somebody of its own side steps
        // onto the square it was about to take. That is a refused step, which
        // is PF_WAIT -- and the gunboat is seven squares from where it was
        // sent with a range of eight, so upstream's answer is that it has
        // arrived. Without that rule the wait is served, the route is
        // re-planned round the blocker, and it walks the rest of the way.
        world.tick();
        assertTrue(gunboat.pathLength() > 0, "the gunboat laid no course, so nothing counts");
        // Ahead of it rather than under it: the first tick both lays the
        // course and takes the first step of it.
        int blockAt = gunboat.tileX() + 2;
        world.createUnit(soldier(), 0, blockAt, 10);
        int startedFrom = gunboat.tileX();

        int ended = -1;
        int furthest = 0;
        for (int cycle = 1; cycle <= 400 && ended < 0; cycle++) {
            world.tick();
            furthest = Math.max(furthest, gunboat.tileX() - startedFrom);
            if (gunboat.order() == Unit.Order.STILL) {
                ended = cycle;
            }
        }

        assertTrue(ended > 0, "the march never ended at all");
        assertEquals(1, furthest,
                "the gunboat walked " + furthest + " squares on from where its step was"
                        + " refused. It was already inside its own attack range of the square"
                        + " it was sent to, and a refused step there is PF_REACHED rather than"
                        + " ten cycles and a way round");
    }

    @Test
    @DisplayName("a march whose route is spent waits its ten cycles before asking for another")
    void aSpentRouteCostsItsPauseBeforeTheMarchReplans() {
        World world = new World(grass(80));
        world.fog().revealAll(0);
        Unit marcher = world.createUnit(soldier(), 0, 2, 10);
        // Longer than PathFinderOutput::MAX_PATH_LENGTH, so the first stored
        // segment is necessarily spent on a successful step while there is
        // still plenty of journey left.
        assertTrue(world.orderAttackMove(marcher, 70, 10), "the order was refused");
        int spentAt = -1;
        for (int cycle = 1; cycle <= 500 && spentAt < 0; cycle++) {
            world.tick();
            if (marcher.pathLength() == 0 && marcher.routeSpent()) {
                spentAt = cycle;
            }
        }
        assertTrue(spentAt > 0, "the route was never spent, so the pause was never on trial");
        int from = marcher.tileX();

        int steppedAfter = -1;
        for (int cycle = 1; cycle <= 60 && steppedAfter < 0; cycle++) {
            world.tick();
            if (marcher.tileX() != from) {
                steppedAfter = cycle;
            }
        }

        assertTrue(steppedAfter > 0, "the march never went anywhere again at all");
        // The successful final element returns as a real step. Only its next
        // route consult observes the historical empty-output PF_WAIT and
        // raises ten cycles before NewPath. (A blocked final element differs:
        // that element's own consult has already returned PF_WAIT.)
        assertTrue(steppedAfter > 10,
                "the marcher stepped " + steppedAfter + " cycle(s) after its route was"
                        + " spent. The wake that finds the count at nought answers PF_WAIT"
                        + " and serves ten cycles; asking the planner on that wake is what"
                        + " advances a segmented march before upstream's");
    }

    private static World grassWorld() {
        World world = new World(grass(30));
        world.fog().revealAll(0);
        return world;
    }
}
