package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.ResourceInfo;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.unit.UnitType.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An empty Return Goods request must leave the worker's current job alone.
 * BNE's player dispatcher checks the cargo flag at 0x004760b3 before it
 * reaches the internal constructor, which can walk even an empty unit home.
 */
class BattleNetReturnGoodsDestTest {

    @Test
    @DisplayName("an empty worker ignores return goods even when a hall is nearby")
    void anEmptyWorkerIgnoresReturnGoodsEvenWhenAHallIsNearby() {
        World world = openLand();
        UnitType peasant = peasantType();
        UnitType hallType = hallType();
        world.createUnit(hallType, 0, 4, 4);
        Unit worker = world.createUnit(peasant, 0, 12, 12);
        assertEquals(0, worker.carried(), "the hand starts empty");
        CommandApplier applier = new CommandApplier(world, List.of(peasant, hallType));
        assertFalse(applier.apply(GameCommand.returnGoods(0, worker.id())),
                "the player dispatcher must refuse an empty hand before choosing a depot");
        for (int i = 0; i < 200; i++) {
            world.tick();
        }
        assertEquals(12, worker.tileX(), "the ignored click must not start a hall walk");
        assertEquals(12, worker.tileY(), "the empty worker must stay on its original row");
    }

    @Test
    @DisplayName("an empty send-home without a depot stands still")
    void anEmptySendHomeWithoutADepotStandsStill() {
        World world = openLand();
        UnitType peasant = peasantType();
        Unit worker = world.createUnit(peasant, 0, 12, 12);
        CommandApplier applier = new CommandApplier(world, List.of(peasant));
        assertFalse(applier.apply(GameCommand.returnGoods(0, worker.id())),
                "the empty hand is refused before FindDeposit runs");
        for (int i = 0; i < 40; i++) {
            world.tick();
        }
        assertEquals(12, worker.tileX(),
                "no hall means the hull stays on its spawn file");
        assertEquals(12, worker.tileY(),
                "no hall means the hull stays on its spawn rank");
        assertEquals(Unit.Order.STILL, worker.order(),
                "native installs Still rather than a hall walk");
    }

    @Test
    @DisplayName("an ignored return goods preserves the active move and queued waypoints")
    void anIgnoredReturnGoodsPreservesTheActiveMoveAndQueuedWaypoints() {
        for (boolean queued : new boolean[] {false, true}) {
            World world = openLand();
            UnitType peasant = peasantType();
            UnitType hall = hallType();
            world.createUnit(hall, 0, 4, 4);
            Unit worker = world.createUnit(peasant, 0, 12, 12);
            CommandApplier applier = new CommandApplier(world, List.of(peasant, hall));
            assertTrue(applier.apply(GameCommand.move(0, worker.id(), 18, 12)),
                    "the worker must accept its original move");
            assertTrue(applier.apply(GameCommand.move(0, worker.id(), 18, 18).withQueued(true)),
                    "the worker must retain a second waypoint");
            Unit.PendingOrderState pending = worker.snapshotPendingOrders();
            Unit.Order order = worker.order();
            assertFalse(applier.apply(GameCommand.returnGoods(0, worker.id()).withQueued(queued)),
                    "an empty return cannot replace or join the worker's route");
            assertEquals(order, worker.order(), "the ignored request must keep the active walk");
            assertEquals(pending, worker.snapshotPendingOrders(),
                    "the ignored request must neither erase nor extend the waypoint queue");
            for (int cycle = 0; cycle < 300; cycle++) {
                world.tick();
            }
            assertEquals(18, worker.tileX(), "the worker must reach the original final column");
            assertEquals(18, worker.tileY(), "the worker must reach the original final row");
        }
    }

    private static World openLand() {
        GameMap map = new GameMap(24, 24, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return new World(map);
    }

    private static UnitType peasantType() {
        UnitType type = new UnitType("unit-peasant");
        type.setTileSize(1, 1);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setAnimationSet(walker());
        ResourceInfo gold = new ResourceInfo(Resource.GOLD);
        gold.setCapacity(100);
        gold.setWaitAtResource(4);
        gold.setWaitAtDepot(6);
        type.gathering().put(Resource.GOLD, gold);
        return type;
    }

    private static UnitType hallType() {
        UnitType type = new UnitType("unit-town-hall");
        type.setTileSize(3, 3);
        type.setHitPoints(1200);
        type.setBuilding(true);
        type.setLandUnit(true);
        type.setAnimationSet(walker());
        type.stores().add(Resource.GOLD);
        return type;
    }

    private static AnimationSet walker() {
        AnimationSet set = new AnimationSet("walker");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move",
                List.of("frame 0", "move 16", "wait 1")));
        return set;
    }
}
