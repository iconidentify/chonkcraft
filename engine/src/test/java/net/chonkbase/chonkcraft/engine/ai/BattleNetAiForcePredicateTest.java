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
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Test;

/** Player-roster tests for retail ai.bin predicates 4, 5 and 6. */
class BattleNetAiForcePredicateTest {

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
                            : index == 1 ? PudMap.PlayerType.PERSON
                            : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        return new World(map, players);
    }

    private static UnitType fighter(String name, boolean sea, boolean air) {
        UnitType type = new UnitType(name);
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setLandUnit(!sea && !air);
        type.setSeaUnit(sea);
        type.setAirUnit(air);
        return type;
    }

    @Test
    void forceSizeGatesIgnoreMapGuardsAndCountUnmarkedFighters() {
        World world = world();
        var home = world.createUnit(fighter("unit-footman", false, false), 0, 2, 2);
        var second = world.createUnit(fighter("unit-archer", false, false), 0, 3, 2);
        var guard = world.createUnit(fighter("unit-knight", false, false), 0, 4, 2);
        var ship = world.createUnit(fighter("unit-human-destroyer", true, false), 0, 5, 2);
        var flyer = world.createUnit(fighter("unit-gryphon-rider", false, true), 0, 6, 2);
        guard.setBattleNetReadySuppressed(true);
        ship.setBattleNetReadySuppressed(true);
        flyer.setBattleNetReadySuppressed(true);
        AiPlayer ai = world.enableAi(0);
        byte[] state = new byte[BattleNetAiBytecode.STATE_BYTES];
        state[BattleNetAiBytecode.OFF_GROUND_FORCE_COUNT] = 1;
        state[BattleNetAiBytecode.OFF_GROUND_FORCE_MULTIPLIER] = 2;
        state[BattleNetAiBytecode.OFF_NAVAL_FORCE_COUNT] = 1;
        state[BattleNetAiBytecode.OFF_NAVAL_FORCE_MULTIPLIER] = 1;
        state[BattleNetAiBytecode.OFF_AIR_FORCE_COUNT] = 1;
        state[BattleNetAiBytecode.OFF_AIR_FORCE_MULTIPLIER] = 1;
        assertTrue(ai.battleNetPredicate(world, 4, state),
                "two unmarked home-base fighters meet a land-force product of 2");
        home.setBattleNetReadySuppressed(true);
        assertFalse(ai.battleNetPredicate(world, 4, state),
                "a lone unmarked archer is not enough once the footman is a map guard");
        assertFalse(ai.battleNetPredicate(world, 5, state),
                "a marked destroyer does not pass WAIT-UNTIL 5");
        assertFalse(ai.battleNetPredicate(world, 6, state),
                "a marked gryphon does not pass WAIT-UNTIL 6");
        assertTrue(AiPlayer.battleNetCountsForForce(
                fighter("unit-footman", false, false), 4),
                "a footman still belongs to the ground force domain");
        assertTrue(second.isAlive(),
                "the second unmarked fighter must still be on the map for the open gate");
    }

    @Test
    void worldTicksTheRetailBytecodeOncePerSimulationCycle() {
        World world = world();
        AiPlayer ai = world.enableAi(0);
        byte[] profile = new byte[128];
        profile[0] = 100; // Profile-zero record.
        profile[100] = 120; // Ordered build list.
        profile[102] = 120; // Synthetic threshold table.
        profile[104] = 0; // SET wanted-workers = 1.
        profile[105] = BattleNetAiBytecode.OFF_WANTED_WORKERS;
        profile[106] = 1;
        profile[107] = 2; // WAIT 2.
        profile[108] = 2;
        profile[112] = 0; // SET wanted-workers = 9.
        profile[113] = BattleNetAiBytecode.OFF_WANTED_WORKERS;
        profile[114] = 9;
        profile[115] = 2; // WAIT 100.
        profile[116] = 100;
        profile[120] = (byte) 0xff;
        ai.setBattleNetBuildProfile(profile, 0);
        assertEquals(1, ai.battleNetWantedWorkers());

        world.tick();
        world.tick();
        assertEquals(1, ai.battleNetWantedWorkers());
        world.tick();

        assertEquals(9, ai.battleNetWantedWorkers());
    }

    @Test
    void periodicPassConsumesOnePendingGroundGroupAndPatrolsItAtTheEnemy() {
        World world = world();
        var first = world.createUnit(fighter("unit-footman", false, false), 0, 2, 2);
        var second = world.createUnit(fighter("unit-archer", false, false), 0, 3, 2);
        var spare = world.createUnit(fighter("unit-knight", false, false), 0, 4, 2);
        UnitType enemyHall = new UnitType("unit-town-hall");
        enemyHall.setTileSize(2, 2);
        enemyHall.setHitPoints(1200);
        enemyHall.setBuilding(true);
        world.createUnit(enemyHall, 1, 12, 12);
        // Publish/cache the placed units the way mission initialization does
        // before the first periodic AI pass.
        world.tick();

        byte[] profile = new byte[140];
        profile[0] = 100;
        profile[100] = 120;
        profile[102] = 122;
        int pc = 104;
        profile[pc++] = 0;
        profile[pc++] = BattleNetAiBytecode.OFF_LAUNCH_GROUND;
        profile[pc++] = 1;
        profile[pc++] = 0;
        profile[pc++] = BattleNetAiBytecode.OFF_GROUND_FORCE_COUNT;
        profile[pc++] = 2;
        profile[pc++] = 0;
        profile[pc++] = BattleNetAiBytecode.OFF_GROUND_FORCE_MULTIPLIER;
        profile[pc++] = 1;
        profile[pc++] = 2;
        profile[pc++] = 100;
        profile[120] = (byte) 0xff;
        AiPlayer ai = world.enableAi(0);
        ai.setBattleNetBuildProfile(profile, 0);

        ai.battleNetRunPeriodicForces(world);

        long assigned = java.util.stream.Stream.of(first, second, spare)
                .filter(unit -> unit.battleNetAiBehavior() == 2)
                .count();
        assertEquals(2, assigned);
        java.util.stream.Stream.of(first, second, spare)
                .filter(unit -> unit.battleNetAiBehavior() == 2)
                .forEach(unit -> {
                    assertEquals(12, unit.battleNetAiHomeX());
                    assertEquals(12, unit.battleNetAiHomeY());
                });

        // The launch byte is edge-triggered. A second periodic pass must not
        // recruit the spare until ai.bin writes +9 again.
        ai.battleNetRunPeriodicForces(world);
        assertEquals(2, java.util.stream.Stream.of(first, second, spare)
                .filter(unit -> unit.battleNetAiBehavior() == 2)
                .count());
    }
}
