package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.network.SyncHash;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Whole-match scheduler/RNG determinism using authenticated retail data. */
class BattleNetSchedulerPlayabilityTest {

    private record Run(World world, CommandApplier commands, Unit archer, Unit grunt) { }

    @Test
    @DisplayName("two retail-data runs remain identical through 1800 cycles")
    void realCommandStreamIsDeterministic() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);

        Run first = battlefield(data);
        Run second = battlefield(data);
        assertEquals(SyncHash.of(first.world()), SyncHash.of(second.world()));
        assertEquals(first.world().battleNetRandomSeed(),
                second.world().battleNetRandomSeed());

        long initialHash = SyncHash.of(first.world());
        boolean moved = false;
        boolean fought = false;
        boolean projectileFlew = false;
        for (int cycle = 0; cycle < 1_800; cycle++) {
            if (cycle == 0) {
                applyBoth(first, second, GameCommand.attackMove(
                        0, first.archer().id(), 36, 16));
            } else if (cycle == 600) {
                applyBoth(first, second, GameCommand.attack(
                        0, first.archer().id(), first.grunt().id()));
            } else if (cycle == 1_200) {
                applyBoth(first, second, GameCommand.move(
                        0, first.archer().id(), 10, 20));
            }

            int gruntHp = first.grunt().hitPoints();
            first.world().tick();
            second.world().tick();
            moved |= first.archer().tileX() != 4 || first.archer().tileY() != 16;
            fought |= first.grunt().hitPoints() < gruntHp || !first.grunt().isAlive();
            projectileFlew |= !first.world().missiles().isEmpty();

            assertEquals(SyncHash.of(first.world()), SyncHash.of(second.world()),
                    "simulation state diverged at cycle " + cycle);
            assertEquals(first.world().randomSeed(), second.world().randomSeed(),
                    "synchronized RNG diverged at cycle " + cycle);
            assertEquals(first.world().randomDraws(), second.world().randomDraws(),
                    "synchronized RNG draw count diverged at cycle " + cycle);
            assertEquals(first.world().battleNetRandomSeed(),
                    second.world().battleNetRandomSeed(),
                    "asynchronous BNE RNG diverged at cycle " + cycle);
            assertEquals(first.world().battleNetRandomDraws(),
                    second.world().battleNetRandomDraws(),
                    "asynchronous BNE RNG draw count diverged at cycle " + cycle);
        }

        assertNotEquals(initialHash, SyncHash.of(first.world()),
                "the command stream produced no simulation change");
        assertTrue(moved, "the retail archer never consumed a movement order");
        assertTrue(fought, "the retail combatants never exchanged damage");
        assertTrue(projectileFlew, "the retail archer never launched a projectile");
        assertEquals(1_800, first.world().cycle());
        assertEquals(1_800, second.world().cycle());
    }

    private static void applyBoth(Run first, Run second, GameCommand firstCommand) {
        GameCommand secondCommand = switch (firstCommand.kind()) {
            case ATTACK_MOVE -> GameCommand.attackMove(firstCommand.player(),
                    second.archer().id(), firstCommand.x(), firstCommand.y());
            case ATTACK -> GameCommand.attack(firstCommand.player(),
                    second.archer().id(), second.grunt().id());
            case MOVE -> GameCommand.move(firstCommand.player(),
                    second.archer().id(), firstCommand.x(), firstCommand.y());
            default -> throw new IllegalArgumentException(
                    "unsupported deterministic fixture command " + firstCommand.kind());
        };
        first.commands().apply(firstCommand);
        second.commands().apply(secondCommand);
    }

    private static Run battlefield(GameData data) {
        GameMap map = new GameMap(48, 32, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int player = 0; player < players.length; player++) {
            players[player] = new Player(player,
                    player < 2 ? PudMap.PlayerType.PERSON : PudMap.PlayerType.NOBODY,
                    player == 1 ? PudMap.Race.ORC : PudMap.Race.HUMAN);
        }
        World world = new World(map, players);
        world.establishDiplomacy();
        world.setUnitTypes(data.unitTypes().types());
        world.setUpgrades(data.upgrades().upgrades());
        world.setMissileTypes(data.missiles().types());
        world.fog().revealAll(0);
        world.fog().revealAll(1);

        UnitType archerType = data.unitTypes().types().get("unit-archer");
        UnitType gruntType = data.unitTypes().types().get("unit-grunt");
        assertNotNull(archerType, "retail roster has no archer");
        assertNotNull(gruntType, "retail roster has no grunt");
        Unit archer = world.createUnit(archerType, 0, 4, 16);
        Unit grunt = world.createUnit(gruntType, 1, 18, 16);
        assertNotNull(archer);
        assertNotNull(grunt);
        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        return new Run(world, commands, archer, grunt);
    }
}
