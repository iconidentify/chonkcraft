package net.chonkbase.chonkcraft.engine.parity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Test;

class EngineTraceCommandPlanTest {

    @Test
    void appliesTheTranslatedMoveAtItsExactCycle() throws Exception {
        GameMap map = new GameMap(16, 16, new Tileset());
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
        Unit unit = world.createUnit(type, 0, 3, 4);
        unit.setBattleNetAnimationTimer(0);
        Path commands = Files.createTempFile("bne-java-commands", ".txt");
        Files.writeString(commands, "# bne-java-command-plan-v1\n"
                + "cycle 5 move unit " + unit.id() + " x 9 y 10\n");
        EngineTrace.ScriptedCommandPlan plan =
                EngineTrace.ScriptedCommandPlan.load(commands);

        plan.apply(4, world);
        assertEquals(Unit.Order.STILL, unit.order());
        plan.apply(5, world);
        assertEquals(Unit.Order.MOVE, unit.order());
        assertEquals(9, unit.orderTargetX());
        assertEquals(10, unit.orderTargetY());
        assertEquals(3, unit.battleNetOrderDelay(),
                "serialized player commands retain the three native quiet visits");
    }

    @Test
    void malformedCommandsFailClosed() throws Exception {
        Path commands = Files.createTempFile("bne-java-commands", ".txt");
        Files.writeString(commands, "cycle 5 attack unit 7 x 9 y 10\n");
        assertThrows(IllegalArgumentException.class,
                () -> EngineTrace.ScriptedCommandPlan.load(commands));
    }

    @Test
    void commandWaitsBehindTheCurrentRetailAnimationTimer() {
        GameMap map = new GameMap(16, 16, new Tileset());
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
        Unit unit = world.createUnit(type, 0, 3, 4);
        unit.setBattleNetAnimationTimer(2);

        world.orderCommandMove(unit, 9, 10);

        assertEquals(Unit.Order.STILL, unit.currentAction(),
                "the interrupted action remains visible until its timer pop");
        assertEquals(Unit.Order.MOVE, unit.order());
        assertEquals(4, unit.battleNetOrderDelay(),
                "one remaining Still beat precedes the three move setup beats");
    }

    @Test
    void commandWaitsForTheCurrentRetailAnimationMarker() {
        GameMap map = new GameMap(16, 16, new Tileset());
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
        Unit unit = world.createUnit(type, 0, 3, 4);

        byte[] script = new byte[64];
        script[32] = 4; // frame
        script[33] = 0;
        script[34] = 1; // wait four
        script[35] = 4;
        script[36] = 0; // next order-action marker
        script[0] = 16; // type-zero sequence table
        script[20] = 40; // Still sequence
        script[40] = 0; // cold Still action marker after timer three
        world.setBattleNetSequenceData(script);
        unit.setBattleNetSequenceOffset(32);
        unit.setBattleNetAnimationTimer(1);

        world.orderCommandMove(unit, 9, 10);

        assertEquals(Unit.Order.STILL, unit.currentAction());
        assertEquals(Unit.Order.MOVE, unit.order());
        assertEquals(7, unit.battleNetOrderDelay(),
                "four quiet script visits precede the three move setup beats");
    }
}
