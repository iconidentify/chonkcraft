package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.pathfinder.PathFinder;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.unit.UnitType.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A builder standing on its site asks whether it has arrived.
 *
 * <p>{@code COrder_Build}'s walk is {@code DoActionMove} like every other, so
 * the cycle a builder's route runs out costs the usual PF_WAIT and ten cycles,
 * and what ends the walk is the search on the call after that. This implementation
 * measured the distance to the footprint instead and asked nothing.
 *
 * <p>The question matters more than the answer, which was right either way. A
 * builder on its own site is inside the goal rectangle, and that is the one
 * case {@code AStarFindSimplePath} refuses outright -- so the question goes to
 * the full search, and {@code AStarCleanUp} empties {@code CostMoveToCache} on
 * the way past. That memo is what every one-square step in the game is judged
 * by; see {@code InsideTheGoalTest}.
 *
 * <p>On {@code maps/skirmish/(3)critter-attack} the whole difference is one
 * search. Upstream's orc peasant reaches 65,78 on cycle 42, waits ten, and
 * asks on 53; between cycles 40 and 62 that is the only full search either
 * engine ought to run, and this implementation ran none -- so a critter at 66,76 read a
 * cost from cycle 40 on cycle 62 and stepped at a square that had stopped
 * being free. That map's first divergence moved from cycle 63 to 108.
 */
class BuilderAsksTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType peasant() {
        UnitType type = new UnitType("unit-peasant");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setNumDirections(8);
        AnimationSet set = new AnimationSet("walker");
        set.put(AnimationSet.State.STILL, Animation.parse("still",
                List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    /** Four by four, so a builder that walks to it ends up standing inside. */
    private static UnitType hall() {
        UnitType type = new UnitType("unit-town-hall");
        type.setTileSize(4, 4);
        type.setHitPoints(1200);
        type.setBuilding(true);
        type.costs().put(Resource.TIME, 1);
        type.costs().put(Resource.GOLD, 100);
        return type;
    }

    @Test
    @DisplayName("the builder's arrival costs a search, which is what empties the cost memo")
    void arrivingIsAskedRatherThanMeasured() {
        World world = new World(grass(24));
        world.setBuilders(Map.of("unit-town-hall", Set.of("unit-peasant")));
        world.player(0).set(Resource.GOLD, 5000);

        // Started away from the site, so that it walks: a builder that never
        // moved never had a square refused and never serves the pause the
        // search comes after. From the east, so that it comes to rest inside
        // the footprint rather than on its top-left corner -- the corner is
        // "at exact destination point already", the one case
        // {@code AStarFindSimplePath} answers itself, and a fixture that lands
        // there measures that instead.
        Unit worker = world.createUnit(peasant(), 0, 20, 11);
        assertTrue(world.orderBuild(worker, hall(), 10, 9), "the build order was refused");

        // Walk it in, and stop the moment it is standing on the footprint with
        // the walk behind it. Everything after that is the arrival.
        int arrived = -1;
        for (int cycle = 1; cycle <= 300 && arrived < 0; cycle++) {
            world.tick();
            if (worker.tileX() >= 10 && worker.tileX() <= 13
                    && worker.tileY() >= 9 && worker.tileY() <= 12
                    && !worker.isMoving()) {
                arrived = cycle;
            }
        }
        assertTrue(arrived > 0, "the builder never reached its site, so nothing below counts");

        long before = PathFinder.searchesRun;
        // Long enough to cover the ten-cycle pause the last refused square
        // costs and the call after it, and short enough that the building is
        // not up and the order gone.
        for (int cycle = 0; cycle < 40; cycle++) {
            world.tick();
        }

        assertTrue(PathFinder.searchesRun > before,
                "the builder ran no search at all between standing on its site and starting"
                        + " to build. Upstream's asks -- and the asking is what empties the"
                        + " cost memo every one-square step in the game is judged by");
    }

    @Test
    @DisplayName("and it asks once, not again on every retry of a fouled site")
    void theQuestionIsAskedOnceAndNotOnEveryRetry() {
        World world = new World(grass(24));
        world.setBuilders(Map.of("unit-town-hall", Set.of("unit-peasant")));
        world.player(0).set(Resource.GOLD, 5000);

        Unit worker = world.createUnit(peasant(), 0, 20, 11);
        assertTrue(world.orderBuild(worker, hall(), 10, 9), "the build order was refused");
        // Somebody standing on the far side of the footprint, so the builder
        // arrives, is refused, and retries: COrder_Build counts the refusals in
        // its own State and gives each one ten cycles, and none of that touches
        // the pathfinder.
        world.createUnit(blocker(), 0, 13, 12);

        int arrived = -1;
        for (int cycle = 1; cycle <= 300 && arrived < 0; cycle++) {
            world.tick();
            if (worker.tileX() >= 10 && worker.tileX() <= 13
                    && worker.tileY() >= 9 && worker.tileY() <= 12
                    && !worker.isMoving()) {
                arrived = cycle;
            }
        }
        assertTrue(arrived > 0, "the builder never reached its site, so nothing below counts");

        // Past the arrival search and well into the retries.
        long afterFirst = -1;
        for (int cycle = 0; cycle < 20; cycle++) {
            world.tick();
        }
        afterFirst = PathFinder.searchesRun;
        assertTrue(worker.order() == Unit.Order.BUILD,
                "the builder gave the job up before it had retried at all");
        for (int cycle = 0; cycle < 60; cycle++) {
            world.tick();
        }

        assertEquals(afterFirst, PathFinder.searchesRun,
                "the builder asked again while retrying a fouled site: "
                        + (PathFinder.searchesRun - afterFirst) + " more searches over sixty"
                        + " cycles. Every one of them empties the cost memo, and upstream runs"
                        + " no search at all for the whole of that stretch");
    }

    /** Standing on the ground and not going anywhere. */
    private static UnitType blocker() {
        UnitType type = new UnitType("unit-boulder");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(100);
        type.setLandUnit(true);
        type.setNumDirections(1);
        return type;
    }
}
