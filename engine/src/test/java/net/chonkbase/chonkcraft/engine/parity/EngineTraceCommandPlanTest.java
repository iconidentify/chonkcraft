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
    }

    @Test
    void malformedCommandsFailClosed() throws Exception {
        Path commands = Files.createTempFile("bne-java-commands", ".txt");
        Files.writeString(commands, "cycle 5 attack unit 7 x 9 y 10\n");
        assertThrows(IllegalArgumentException.class,
                () -> EngineTrace.ScriptedCommandPlan.load(commands));
    }
}
