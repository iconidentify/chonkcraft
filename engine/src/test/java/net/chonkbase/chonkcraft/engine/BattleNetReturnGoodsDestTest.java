package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class BattleNetReturnGoodsDestTest {

    @Test
    @DisplayName("an empty send-home walks to the friendly hall")
    void anEmptySendHomeWalksToTheFriendlyHall() {
        World world = openLand();
        UnitType peasant = peasantType();
        UnitType hallType = hallType();
        Unit hall = world.createUnit(hallType, 0, 4, 4);
        Unit worker = world.createUnit(peasant, 0, 12, 12);
        assertEquals(0, worker.carried(), "the hand starts empty");
        CommandApplier applier = new CommandApplier(world, List.of(peasant, hallType));
        assertTrue(applier.apply(GameCommand.returnGoods(0, worker.id())),
                "GiveOrder table 24 applies an empty send-home");
        for (int i = 0; i < 200; i++) {
            world.tick();
        }
        int chebyshev = Math.max(Math.abs(worker.tileX() - hall.tileX()),
                Math.abs(worker.tileY() - hall.tileY()));
        assertTrue(chebyshev <= 3,
                "native walks the empty hand to the hall, not the spawn tile");
    }

    @Test
    @DisplayName("an empty send-home without a depot stands still")
    void anEmptySendHomeWithoutADepotStandsStill() {
        World world = openLand();
        UnitType peasant = peasantType();
        Unit worker = world.createUnit(peasant, 0, 12, 12);
        CommandApplier applier = new CommandApplier(world, List.of(peasant));
        assertTrue(applier.apply(GameCommand.returnGoods(0, worker.id())),
                "GiveOrder table 24 still applies when FindDeposit answers none");
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
