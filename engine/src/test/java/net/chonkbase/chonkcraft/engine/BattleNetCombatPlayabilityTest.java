package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Complete player-command fights using the retail BNE roster and missiles. */
class BattleNetCombatPlayabilityTest {

    private record Fixture(GameData data, World world, CommandApplier commands) {}

    private static Fixture fixture() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");

        GameData data = new GameData(assets);
        GameMap map = new GameMap(32, 32, new Tileset());
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
        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);
        return new Fixture(data, world, commands);
    }

    @Test
    @DisplayName("the command boundary reports BNE refusals instead of pretending they worked")
    void rejectedOrdersAreReportedAsRejected() {
        Fixture fixture = fixture();
        Unit footman = place(fixture, "unit-footman", 0, 8, 8);
        Unit friend = place(fixture, "unit-footman", 0, 10, 8);

        assertFalse(fixture.commands().apply(
                        GameCommand.repair(0, footman.id(), friend.id())),
                "a footman falsely accepted a worker-only repair order");
        assertFalse(fixture.commands().apply(
                        GameCommand.returnGoods(0, footman.id())),
                "an empty footman falsely accepted a resource-return order");
        assertFalse(fixture.commands().apply(
                        GameCommand.attackGround(0, footman.id(), 12, 12)),
                "a footman falsely accepted a siege-only ground attack");
        assertFalse(fixture.commands().apply(
                        GameCommand.unload(0, footman.id(), 12, 12)),
                "a footman falsely accepted a transport-only unload order");
        assertFalse(fixture.commands().apply(
                        GameCommand.board(0, footman.id(), friend.id())),
                "a footman falsely accepted another footman as a transport");
        assertFalse(fixture.commands().apply(
                        GameCommand.unloadOne(0, footman.id(), friend.id())),
                "a footman falsely accepted a passenger-unload order");
        assertFalse(fixture.commands().apply(
                        GameCommand.autoCast(0, footman.id(), 0, true)),
                "a footman falsely accepted a caster-only standing spell");
        assertFalse(fixture.commands().apply(
                        GameCommand.rallyPoint(0, footman.id(), 12, 12)),
                "a footman falsely accepted a producer-only rally point");
    }

    @Test
    @DisplayName("a player-issued melee attack commits swings, deals damage and kills")
    void playerMeleeFightCompletes() {
        Fixture fixture = fixture();
        Unit footman = place(fixture, "unit-footman", 0, 8, 8);
        Unit grunt = place(fixture, "unit-grunt", 1, 10, 8);
        grunt.setHitPoints(Math.min(12, grunt.hitPoints()));
        int before = grunt.hitPoints();

        fixture.commands().apply(GameCommand.attack(0, footman.id(), grunt.id()));
        assertEquals(Unit.Order.ATTACK, footman.order(), "the wire command was refused");
        boolean damaged = false;
        for (int cycle = 0; cycle < 2_000 && grunt.isAlive(); cycle++) {
            fixture.world().tick();
            damaged |= grunt.hitPoints() < before;
        }
        assertTrue(damaged, "the retail footman never landed a committed swing");
        assertFalse(grunt.isAlive(), "the melee fight never produced a death");
        assertTrue(footman.isAlive(), "the commanded attacker died before the fixture spoke");
    }

    @Test
    @DisplayName("a player-issued ranged attack creates a travelling retail projectile")
    void playerRangedAttackTravelsAndHits() {
        Fixture fixture = fixture();
        Unit archer = place(fixture, "unit-archer", 0, 5, 5);
        Unit grunt = place(fixture, "unit-grunt", 1, 9, 5);
        int before = grunt.hitPoints();

        fixture.commands().apply(GameCommand.attack(0, archer.id(), grunt.id()));
        boolean travelled = false;
        for (int cycle = 0; cycle < 2_000 && grunt.hitPoints() == before; cycle++) {
            fixture.world().tick();
            travelled |= !fixture.world().missiles().isEmpty();
        }
        assertTrue(travelled, "the archer's retail arrow never existed in flight");
        assertTrue(grunt.hitPoints() < before, "the travelling arrow never dealt damage");
    }

    @Test
    @DisplayName("retail ballista splash hits land and spares an adjacent aircraft")
    void playerSplashRespectsTheRetailTargetDomain() {
        Fixture fixture = fixture();
        Unit ballista = place(fixture, "unit-ballista", 0, 4, 14);
        Unit grunt = place(fixture, "unit-grunt", 1, 11, 14);
        Unit flyer = place(fixture, "unit-gryphon-rider", 1, 12, 14);
        int gruntBefore = grunt.hitPoints();
        int flyerBefore = flyer.hitPoints();

        fixture.commands().apply(GameCommand.attackGround(
                0, ballista.id(), grunt.tileX(), grunt.tileY()));
        assertEquals(Unit.Order.ATTACK_GROUND, ballista.order(),
                "the player attack-ground command was refused");
        for (int cycle = 0; cycle < 2_000 && grunt.hitPoints() == gruntBefore; cycle++) {
            fixture.world().tick();
        }
        assertTrue(grunt.hitPoints() < gruntBefore,
                "the retail ballista blast missed its ground target");
        assertEquals(flyerBefore, flyer.hitPoints(),
                "land-only ballista splash damaged an aircraft");
    }

    private static Unit place(Fixture fixture, String ident, int player, int x, int y) {
        UnitType type = fixture.data().unitTypes().types().get(ident);
        assertNotNull(type, "retail roster has no " + ident);
        Unit unit = fixture.world().createUnit(type, player, x, y);
        assertNotNull(unit, "could not place " + ident + " at " + x + "," + y);
        return unit;
    }
}
