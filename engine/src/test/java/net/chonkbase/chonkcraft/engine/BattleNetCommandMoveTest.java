package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Test;

class BattleNetCommandMoveTest {

    @Test
    void occupiedDestinationDispatchesReplacementStillOnTheSameVisit() {
        GameMap map = new GameMap(12, 12, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        UnitType type = new UnitType("unit-footman");
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        Unit mover = world.createUnit(type, 0, 3, 4);
        world.createUnit(type, 0, 4, 4);
        mover.setOrder(Unit.Order.MOVE);
        mover.setOrderTarget(4, 4);
        mover.setBattleNetOrderDelay(0);
        int phaseBefore = mover.battleNetIdlePhase();

        world.movement.stepMoveOrder(mover);

        assertEquals(Unit.Order.STILL, mover.order());
        assertEquals(phaseBefore + 1, mover.battleNetIdlePhase(),
                "retail executes the replacement Still marker in this visit");
    }

    @Test
    void savedPatrolRefreshesBeforeUnitsThenResumesAtTheStillMarker() {
        GameMap map = new GameMap(8, 8, new Tileset());
        World world = new World(map);
        UnitType type = new UnitType("unit-footman");
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        Unit unit = world.createUnit(type, 0, 2, 2);
        byte[] script = new byte[48];
        script[0] = 16;
        script[20] = 40;
        script[40] = 0;
        world.setBattleNetSequenceData(script);
        unit.setBattleNetSequenceOffset(40);
        unit.setBattleNetAnimationTimer(1);
        unit.setOrder(Unit.Order.STILL);
        unit.setSavedOrder(Unit.Order.PATROL);
        unit.setBattleNetScoutPatrol(true);

        world.idle.fireBattleNetCommandPatrolRestores();
        world.idle.stepStill(unit);

        assertEquals(Unit.Order.PATROL, unit.order());
        assertEquals(null, unit.savedOrder());
        assertEquals(2, unit.battleNetOrderDelay());
    }

    @Test
    void doubledCommandCompletesBesideItsOccupiedPoint() {
        GameMap map = new GameMap(12, 12, new Tileset());
        World world = new World(map);
        UnitType type = new UnitType("unit-daemon");
        type.setTileSize(1, 1);
        type.setAirUnit(true);
        type.setHitPoints(60);
        type.setSpeed(10);
        Unit mover = world.createUnit(type, 0, 8, 2);
        world.createUnit(type, 0, 8, 4);
        mover.setOrder(Unit.Order.MOVE);
        mover.setOrderTarget(8, 4);
        mover.setBattleNetDoubleStep(true);
        mover.setBattleNetPlayerCommandMove(true);
        mover.setRouteSpent(true);

        world.movement.stepMoveOrder(mover);

        assertEquals(Unit.Order.STILL, mover.order(),
                "the occupied stride-neighbour is the native point marker");
    }
}
