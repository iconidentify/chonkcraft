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
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BattleNetStrideDestTest {

    @Test
    @DisplayName("a destroyer south click of three tiles parks on the even neighbour")
    void aDestroyerSouthClickOfThreeTilesParksOnTheEvenNeighbour() {
        World world = openWater();
        Unit ship = world.createUnit(destroyerType(), 0, 10, 10);
        assertTrue(ship.battleNetDoubleStep(),
                "destroyers walk the even lattice");
        CommandApplier applier = new CommandApplier(world, List.of(ship.type()));
        assertTrue(applier.apply(GameCommand.move(0, ship.id(), 10, 7)),
                "the south click must be accepted");
        for (int i = 0; i < 80; i++) {
            world.tick();
        }
        assertEquals(10, ship.tileX(),
                "a three-tile south click must stay on the same file");
        assertEquals(8, ship.tileY(),
                "native parks on the even neighbour, not one tile past the odd dest");
        assertEquals(Unit.Order.STILL, ship.order(),
                "the hull stands down on that even neighbour");
    }

    @Test
    @DisplayName("a balloon west click of three tiles parks on the even neighbour")
    void aBalloonWestClickOfThreeTilesParksOnTheEvenNeighbour() {
        World world = openAir();
        Unit balloon = world.createUnit(balloonType(), 0, 10, 10);
        assertTrue(balloon.battleNetDoubleStep(),
                "balloons walk the even lattice");
        CommandApplier applier = new CommandApplier(world, List.of(balloon.type()));
        assertTrue(applier.apply(GameCommand.move(0, balloon.id(), 7, 10)),
                "the west click must be accepted");
        for (int i = 0; i < 80; i++) {
            world.tick();
        }
        assertEquals(8, balloon.tileX(),
                "native parks on the even neighbour, not one tile past the odd dest");
        assertEquals(10, balloon.tileY(),
                "a three-tile west click must stay on the same rank");
    }

    @Test
    @DisplayName("a destroyer north click of three tiles still stops short")
    void aDestroyerNorthClickOfThreeTilesStillStopsShort() {
        World world = openWater();
        Unit ship = world.createUnit(destroyerType(), 0, 10, 10);
        CommandApplier applier = new CommandApplier(world, List.of(ship.type()));
        assertTrue(applier.apply(GameCommand.move(0, ship.id(), 10, 13)),
                "the north click must be accepted");
        for (int i = 0; i < 80; i++) {
            world.tick();
        }
        assertEquals(10, ship.tileX(),
                "a three-tile north click must stay on the same file");
        assertEquals(12, ship.tileY(),
                "an increasing odd dest still stops on the even neighbour");
    }

    @Test
    @DisplayName("a destroyer even dest four tiles south is reached")
    void aDestroyerEvenDestFourTilesSouthIsReached() {
        World world = openWater();
        Unit ship = world.createUnit(destroyerType(), 0, 10, 10);
        CommandApplier applier = new CommandApplier(world, List.of(ship.type()));
        assertTrue(applier.apply(GameCommand.move(0, ship.id(), 10, 6)),
                "the even dest must be accepted");
        for (int i = 0; i < 80; i++) {
            world.tick();
        }
        assertEquals(10, ship.tileX(),
                "an even dest must stay on the same file");
        assertEquals(6, ship.tileY(),
                "an even dest is the even lattice point itself");
    }

    private static World openWater() {
        GameMap map = new GameMap(24, 24, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.WATER_ALLOWED);
            }
        }
        return new World(map);
    }

    private static World openAir() {
        GameMap map = new GameMap(24, 24, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return new World(map);
    }

    private static UnitType destroyerType() {
        UnitType ship = new UnitType("unit-orc-destroyer");
        ship.setTileSize(2, 2);
        ship.setHitPoints(100);
        ship.setSpeed(32);
        ship.setSeaUnit(true);
        ship.setCanAttack(true);
        AnimationSet animations = new AnimationSet("destroyer");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", List.of("frame 0", "move 32", "wait 1")));
        ship.setAnimationSet(animations);
        return ship;
    }

    private static UnitType balloonType() {
        UnitType balloon = new UnitType("unit-balloon");
        balloon.setTileSize(1, 1);
        balloon.setHitPoints(60);
        balloon.setSpeed(14);
        balloon.setAirUnit(true);
        balloon.setCanAttack(false);
        AnimationSet animations = new AnimationSet("balloon");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", List.of("frame 0", "move 32", "wait 1")));
        balloon.setAnimationSet(animations);
        return balloon;
    }
}
