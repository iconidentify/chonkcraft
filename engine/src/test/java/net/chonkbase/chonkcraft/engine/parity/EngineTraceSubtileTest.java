package net.chonkbase.chonkcraft.engine.parity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Test;

class EngineTraceSubtileTest {

    @Test
    void semanticTraceCarriesDiagnosticPixelPositionOnlyWhenEnabled() {
        World world = new World(new GameMap(16, 16, new Tileset()));
        UnitType type = new UnitType("unit-grunt");
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        Unit unit = world.createUnit(type, 0, 3, 4);
        unit.setOffset(-2, 5);
        StringWriter compactText = new StringWriter();
        EngineTrace.dump(new PrintWriter(compactText), world);
        assertFalse(compactText.toString().contains(" px "),
                compactText.toString());

        StringWriter text = new StringWriter();
        System.setProperty("chonkcraft.trace.bne.subtile", "true");
        try {
            EngineTrace.dump(new PrintWriter(text), world);
        } finally {
            System.clearProperty("chonkcraft.trace.bne.subtile");
        }

        assertTrue(text.toString().contains(
                "u " + unit.id() + " unit-grunt p0 3 4 hp 60 o STILL px 94 133"),
                text.toString());
    }
}
