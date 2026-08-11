package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Creation-order coverage for retail BNE's behavior-one defensive home. */
class BattleNetAiHomeTest {

    private static Player[] opponents() {
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i == 0 ? PudMap.PlayerType.COMPUTER
                            : i == 1 ? PudMap.PlayerType.PERSON
                                    : PudMap.PlayerType.NOBODY,
                    i == 0 ? PudMap.Race.ORC : PudMap.Race.HUMAN);
        }
        return players;
    }

    private static UnitType fighter(String ident) {
        UnitType type = new UnitType(ident);
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setReactRangeComputer(6);
        return type;
    }

    private static UnitType hall() {
        UnitType type = new UnitType("unit-great-hall");
        type.setTileSize(4, 4);
        type.setHitPoints(1200);
        type.setBuilding(true);
        return type;
    }

    @Test
    @DisplayName("BNE assigns AI homes against the partial PUD creation list")
    void homeUsesCreationTimeHostilesAndOccupancy() {
        GameMap map = new GameMap(40, 40, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map, opponents());
        world.createUnit(hall(), 0, 20, 20);

        Unit beforeHostile = world.createUnit(fighter("unit-ogre"), 0, 30, 20);
        world.createUnit(fighter("unit-footman"), 1, 35, 20);
        world.createUnit(fighter("unit-grunt"), 0, 19, 20);
        Unit afterHostile = world.createUnit(fighter("unit-ogre"), 0, 30, 25);

        assertEquals(30, beforeHostile.battleNetAiHomeX());
        assertEquals(20, beforeHostile.battleNetAiHomeY());
        // The hall's west square is occupied by the previously created
        // grunt, so native 0x443a40's first following square is west-south.
        assertEquals(19, afterHostile.battleNetAiHomeX());
        assertEquals(21, afterHostile.battleNetAiHomeY());

        world.fireBattleNetReadyForAll();

        assertFalse(beforeHostile.hasBattleNetPendingMove());
        assertTrue(afterHostile.hasBattleNetPendingMove());
        assertEquals(19, afterHostile.battleNetPendingMoveX());
        assertEquals(21, afterHostile.battleNetPendingMoveY());
    }

    @Test
    @DisplayName("profile 18 queues four land fighters onto the farm-side assault home")
    void profileEighteenLandAssaultPatrolsFarmCorridorNotNearestEnemy() {
        // Orc 11: AI profile 18 gives four unmarked forward fighters (knight
        // 1558 + archers 1559/1560/1563) behavior 2 and home 106,7 -- the free
        // tile beside pig-farm 107,6 -- then Patrols them there. Nearest-enemy
        // free squares used to aim the knight at the alchemist (117,21).
        GameMap map = new GameMap(40, 40, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i == 0 ? PudMap.PlayerType.PERSON
                            : i == 1 ? PudMap.PlayerType.COMPUTER
                                    : PudMap.PlayerType.NOBODY,
                    i == 0 ? PudMap.Race.ORC : PudMap.Race.HUMAN);
        }
        World world = new World(map, players);
        world.enableAi(1).setBattleNetBuildProfile(null, 18);

        UnitType farm = new UnitType("unit-pig-farm");
        farm.setTileSize(2, 2);
        farm.setHitPoints(400);
        farm.setBuilding(true);
        // Nearer hostile so a nearest-enemy rule would pick it instead of the farm.
        UnitType alchemist = new UnitType("unit-alchemist");
        alchemist.setTileSize(3, 3);
        alchemist.setHitPoints(500);
        alchemist.setBuilding(true);
        world.createUnit(farm, 0, 10, 6);
        world.createUnit(alchemist, 0, 20, 20);

        Unit knight = world.createUnit(fighter("unit-knight"), 1, 20, 30);
        Unit archerA = world.createUnit(fighter("unit-archer"), 1, 21, 30);
        Unit archerB = world.createUnit(fighter("unit-archer"), 1, 19, 30);
        Unit archerC = world.createUnit(fighter("unit-archer"), 1, 18, 29);
        Unit guarded = world.createUnit(fighter("unit-knight"), 1, 5, 35);
        guarded.setBattleNetReadySuppressed(true);

        world.fireBattleNetReadyForAll();

        assertEquals(2, knight.battleNetAiBehavior(),
                "unmarked forward knight joins the type-two land assault");
        assertEquals(2, archerA.battleNetAiBehavior());
        assertEquals(2, archerB.battleNetAiBehavior());
        assertEquals(2, archerC.battleNetAiBehavior());
        assertEquals(0, guarded.battleNetAiBehavior(),
                "UNIT.Data-marked fighters stay off the assault group");
        // Free square beside pig-farm 10,6 -- not the nearer alchemist 20,20.
        int homeX = knight.battleNetAiHomeX();
        int homeY = knight.battleNetAiHomeY();
        assertTrue(Math.max(Math.abs(homeX - 10), Math.abs(homeY - 6)) <= 2,
                "assault home is the free farm-corridor square, not the alchemist");
        assertFalse(Math.max(Math.abs(homeX - 20), Math.abs(homeY - 20)) <= 2,
                "assault home must not sit on the nearer alchemist");
        assertTrue(knight.hasBattleNetPendingPatrol(),
                "ready pass queues Patrol to the assault home");
        assertEquals(homeX, knight.battleNetPendingPatrolX());
        assertEquals(homeY, knight.battleNetPendingPatrolY());
        assertFalse(guarded.hasBattleNetPendingPatrol(),
                "suppressed fighters do not take the assault patrol");
    }
}
