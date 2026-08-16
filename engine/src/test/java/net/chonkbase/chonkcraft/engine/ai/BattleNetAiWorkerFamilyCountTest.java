package net.chonkbase.chonkcraft.engine.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.ResourceInfo;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Opcode-3 predicate 3 reads the completed peasant/peon family word, not a
 * live gatherer walk.
 */
class BattleNetAiWorkerFamilyCountTest {

    private static World world() {
        GameMap map = new GameMap(16, 16, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED
                        | TileFlag.WATER_ALLOWED | TileFlag.COAST_ALLOWED);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int index = 0; index < players.length; index++) {
            players[index] = new Player(index,
                    index == 0 ? PudMap.PlayerType.COMPUTER
                            : PudMap.PlayerType.NOBODY,
                    PudMap.Race.ORC);
        }
        return new World(map, players);
    }

    private static UnitType peon() {
        UnitType type = new UnitType("unit-peon");
        type.setTileSize(1, 1);
        type.setHitPoints(30);
        type.setLandUnit(true);
        type.gathering().put(UnitType.Resource.GOLD,
                new ResourceInfo(UnitType.Resource.GOLD));
        type.gathering().put(UnitType.Resource.WOOD,
                new ResourceInfo(UnitType.Resource.WOOD));
        return type;
    }

    private static UnitType tanker() {
        UnitType type = new UnitType("unit-orc-oil-tanker");
        type.setTileSize(1, 1);
        type.setHitPoints(90);
        type.setSeaUnit(true);
        type.gathering().put(UnitType.Resource.OIL,
                new ResourceInfo(UnitType.Resource.OIL));
        return type;
    }

    private static UnitType attackPeon() {
        UnitType type = new UnitType("unit-attack-peon");
        type.setTileSize(1, 1);
        type.setHitPoints(30);
        type.setLandUnit(true);
        return type;
    }

    @Test
    @DisplayName("a tanker does not fill the computer's worker-family word")
    void aTankerDoesNotFillTheComputersWorkerFamilyWord() {
        World world = world();
        world.createUnit(peon(), 0, 2, 2);
        world.createUnit(tanker(), 0, 4, 4);
        AiPlayer ai = world.enableAi(0);
        byte[] state = new byte[BattleNetAiBytecode.STATE_BYTES];
        state[BattleNetAiBytecode.OFF_WANTED_WORKERS] = 2;
        assertEquals(1, world.battleNetWorkerFamilyCount(0),
                "one peon and a tanker must leave the family word at one");
        assertFalse(ai.battleNetPredicate(world, 3, state),
                "a tanker must not open WAIT-UNTIL 3 when the town still "
                        + "wants two peons");
    }

    @Test
    @DisplayName("an attack peon counts with the town's completed workers")
    void anAttackPeonCountsWithTheTownsCompletedWorkers() {
        World world = world();
        world.createUnit(peon(), 0, 2, 2);
        world.createUnit(attackPeon(), 0, 3, 2);
        assertEquals(2, world.battleNetWorkerFamilyCount(0),
                "an armed peon shares the peasant/peon family word");
        AiPlayer ai = world.enableAi(0);
        byte[] state = new byte[BattleNetAiBytecode.STATE_BYTES];
        state[BattleNetAiBytecode.OFF_WANTED_WORKERS] = 2;
        assertTrue(ai.battleNetPredicate(world, 3, state),
                "two completed family members must pass WAIT-UNTIL 3");
    }

    @Test
    @DisplayName("losing a peon lowers the worker-family word")
    void losingAPeonLowersTheWorkerFamilyWord() {
        World world = world();
        world.createUnit(peon(), 0, 2, 2);
        Unit spare = world.createUnit(peon(), 0, 3, 2);
        assertEquals(2, world.battleNetWorkerFamilyCount(0),
                "two placed peons must start the family word at two");
        world.kill(spare);
        assertEquals(1, world.battleNetWorkerFamilyCount(0),
                "freeing a peon must drop the family word");
    }
}
