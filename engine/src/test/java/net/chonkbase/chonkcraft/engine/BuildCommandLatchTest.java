package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.ResourceInfo;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.unit.UnitType.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A build order given mid-step waits for the step.
 *
 * <p>A command does not break an unbreakable animation. Upstream's flush
 * shrinks the queue to the running order and marks it finished.
 * {@code HandleUnitAction} pops the queue only once
 * {@code !unit.Anim.Unbreakable} --
 * with {@code unit.Wait} cleared on the pop. So a worker told to build while
 * walking finishes its step under the old order, and the build that then
 * becomes current has never walked: its first path ask answers reached and
 * the foundation goes down at once, without the ten-cycle pause a builder
 * whose own route ran out is served.
 *
 * <p>level08h's peon 93 is the measurement: told to build at cycle 37 while
 * mid-step, upstream shows {@code resource} through 39 and the farm founded
 * on 40. This implementation used to flip the order on the spot, bill the build order
 * for the step still in flight, and found the farm on 51 -- eleven cycles of
 * a pause upstream never serves.
 */
class BuildCommandLatchTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    /** A walker whose step owns it, as every real move animation does. */
    private static AnimationSet walker() {
        AnimationSet set = new AnimationSet("w");
        set.put(AnimationSet.State.STILL, Animation.parse("s", List.of("frame 0", "wait 1")));
        // The real peasant's shape (scripts/human/anim.legacy-declaration:66-71): the
        // unbreakable section closes with the last move instruction, and a
        // single breakable wait trails it -- the window every pop uses.
        set.put(AnimationSet.State.MOVE, Animation.parse("m", List.of(
                "unbreakable begin", "frame 0", "move 8", "wait 1",
                "frame 5", "move 8", "wait 1", "frame 10", "move 8", "wait 1",
                "frame 15", "move 8", "unbreakable end", "wait 1")));
        set.put(AnimationSet.State.DEATH, Animation.parse("d", List.of("frame 50", "wait 1")));
        return set;
    }

    private static UnitType peasant() {
        UnitType type = new UnitType("unit-peasant");
        type.setTileSize(1, 1);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setSightRange(4);
        type.setAnimationSet(walker());
        ResourceInfo gold = new ResourceInfo(Resource.GOLD);
        gold.setCapacity(100);
        gold.setWaitAtResource(3);
        gold.setWaitAtDepot(3);
        type.gathering().put(Resource.GOLD, gold);
        return type;
    }

    private static UnitType mine() {
        UnitType type = new UnitType("unit-gold-mine");
        type.setTileSize(3, 3);
        type.setHitPoints(25_500);
        type.setBuilding(true);
        type.setGivesResource(Resource.GOLD);
        type.setCanHarvest(true);
        return type;
    }

    private static UnitType hall() {
        UnitType type = new UnitType("unit-town-hall");
        type.setTileSize(4, 4);
        type.setHitPoints(1_200);
        type.setBuilding(true);
        type.stores().add(Resource.GOLD);
        return type;
    }

    private static UnitType farm() {
        UnitType type = new UnitType("unit-farm");
        type.setTileSize(2, 2);
        type.setHitPoints(400);
        type.setBuilding(true);
        type.setSupply(4);
        type.costs().put(Resource.TIME, 1);
        type.costs().put(Resource.GOLD, 500);
        return type;
    }

    private static Player[] onePlayer() {
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i == 0 ? PudMap.PlayerType.PERSON : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        return players;
    }

    @Test
    @DisplayName("a worker ordered to build mid-step finishes the step as a walker first")
    void aBuildOrderGivenMidStepWaitsForTheStep() {
        World world = new World(grass(30), onePlayer());
        world.player(0).set(Resource.GOLD, 2000);
        Unit worker = world.createUnit(peasant(), 0, 5, 5);

        assertTrue(world.orderMove(worker, 15, 5), "the walk must be accepted");
        // Into the middle of a step: walking east, the offsets are owed and
        // the move animation's unbreakable section is open.
        int guard = 0;
        while (!(worker.isMoving() && worker.animation().unbreakable())) {
            world.tick();
            assertTrue(++guard < 60, "the walker never got mid-step; the fixture's"
                    + " move animation must open an unbreakable section");
        }

        UnitType what = farm();
        assertTrue(world.orderBuild(worker, what, worker.tileX(), worker.tileY()),
                "the build order at the worker's own square must be accepted");
        assertEquals(Unit.Order.MOVE, worker.order(),
                "a command given mid-step must not break the step: the old order"
                        + " stays current until the animation lets go, which is what"
                        + " upstream's trace shows as resource through cycle 39 on"
                        + " level08h");

        // The step lands, the build pops, and the foundation goes down at
        // once -- the builder never walked under the build order, so it is
        // not served the walked builder's ten-cycle arrival pause. Upstream's
        // peon is told on 37 and the farm exists on 40.
        int cyclesToFoundation = 0;
        boolean founded = false;
        for (; cyclesToFoundation < 12; cyclesToFoundation++) {
            world.tick();
            founded = world.units().stream()
                    .anyMatch(u -> u.type().building() && u.type().supply() > 0);
            if (founded) {
                break;
            }
        }
        assertTrue(founded,
                "the popped build order must found on the spot once the step lands:"
                        + " a ten-cycle pause here is the walked builder's PF_WAIT, which"
                        + " this builder -- whose build order never walked -- does not owe");
    }

    @Test
    @DisplayName("a build released after its worker enters a mine waits for the worker to emerge")
    void aNetworkDelayedBuildCannotLoseAWorkerInsideAMine() {
        World world = new World(grass(30), onePlayer());
        world.player(0).set(Resource.GOLD, 2_000);
        Unit depot = world.createUnit(hall(), 0, 2, 2);
        Unit source = world.createUnit(mine(), World.NEUTRAL_PLAYER, 12, 12);
        Unit worker = world.createUnit(peasant(), 0, 10, 13);
        source.setResourcesHeld(25_500);
        worker.setOrder(Unit.Order.HARVEST);
        worker.setCarrying(Resource.GOLD);
        worker.setResourceUnit(source);
        worker.setResourceTile(source.tileX(), source.tileY());
        worker.setResourceDepot(depot);
        worker.setReturnDepotGoal(depot);
        world.harvest.enterResource(worker, source);
        worker.setWaitCycles(2);

        UnitType what = farm();
        assertTrue(world.orderBuild(worker, what, 20, 20),
                "the build was clicked while the worker was visible and must survive"
                        + " lockstep releasing it just after mine entry");
        assertEquals(Unit.Order.HARVEST, worker.order(),
                "only Harvest can drive a contained worker back onto the map");

        boolean surfacedIntoBuild = false;
        for (int cycle = 0; cycle < 80; cycle++) {
            world.tick();
            assertTrue(worker.isOnMap() || worker.order() == Unit.Order.HARVEST,
                    "a contained worker switched to " + worker.order()
                            + " and can never be stepped out of its mine");
            if (worker.isOnMap() && worker.order() == Unit.Order.BUILD) {
                surfacedIntoBuild = true;
                break;
            }
        }
        assertTrue(surfacedIntoBuild,
                "the acknowledged farm order did not resume after the miner emerged");
        assertEquals(what, worker.pendingBuild());
    }
}
