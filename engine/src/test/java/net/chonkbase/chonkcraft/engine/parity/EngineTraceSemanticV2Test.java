package net.chonkbase.chonkcraft.engine.parity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Test;

class EngineTraceSemanticV2Test {

    @Test
    void broaderStateIsAdditiveAndOptIn() {
        GameMap map = new GameMap(16, 16, new Tileset());
        map.field(6, 7).setFlags(TileFlag.LAND_ALLOWED | TileFlag.FOREST);
        map.field(6, 7).setValue(100);
        map.recordLoadedTerrain();
        map.field(6, 7).setValue(98);
        Player[] players = new Player[Player.MAX];
        for (int index = 0; index < players.length; index++) {
            players[index] = new Player(index,
                    index == 0
                            ? net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.PERSON
                            : net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.NOBODY,
                    net.chonkbase.chonkcraft.data.map.PudMap.Race.HUMAN);
        }
        World world = new World(map, players);
        UnitType type = new UnitType("unit-footman");
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        Unit unit = world.createUnit(type, 0, 3, 4);
        unit.setOffset(-2, 5);

        StringWriter ordinary = new StringWriter();
        EngineTrace.dump(new PrintWriter(ordinary), world);
        assertFalse(ordinary.toString().contains("v2w "), ordinary.toString());

        StringWriter expanded = new StringWriter();
        System.setProperty("chonkcraft.trace.bne.semantic-v2", "true");
        try {
            EngineTrace.dump(new PrintWriter(expanded), world);
        } finally {
            System.clearProperty("chonkcraft.trace.bne.semantic-v2");
        }
        String trace = expanded.toString();
        assertTrue(trace.contains("v2w cycle=0"), trace);
        assertTrue(trace.contains("v2p cycle=0 player=0"), trace);
        assertTrue(trace.contains("v2u cycle=0 unit=" + unit.id()
                + " type=unit-footman player=0 x=3 y=4 px=94 py=133"), trace);
        assertTrue(trace.contains("v2t cycle=0 x=6 y=7"), trace);
    }

    @Test
    void semanticFamiliesCanEmitOnlyPlayerMacroState() {
        GameMap map = new GameMap(4, 4, new Tileset());
        Player[] players = new Player[Player.MAX];
        for (int index = 0; index < players.length; index++) {
            players[index] = new Player(index,
                    index == 0
                            ? net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.PERSON
                            : net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.NOBODY,
                    net.chonkbase.chonkcraft.data.map.PudMap.Race.HUMAN);
        }
        World world = new World(map, players);
        System.setProperty("chonkcraft.trace.bne.semantic-v2", "true");
        System.setProperty("chonkcraft.trace.bne.semantic-v2.families", "player");
        StringWriter output = new StringWriter();
        try {
            EngineTrace.dump(new PrintWriter(output), world);
        } finally {
            System.clearProperty("chonkcraft.trace.bne.semantic-v2");
            System.clearProperty("chonkcraft.trace.bne.semantic-v2.families");
        }
        assertTrue(output.toString().contains("v2p cycle=0 player=0"), output.toString());
        assertFalse(output.toString().contains("v2u "), output.toString());
        assertFalse(output.toString().contains("v2m "), output.toString());
        assertFalse(output.toString().contains("v2t "), output.toString());
    }
}
