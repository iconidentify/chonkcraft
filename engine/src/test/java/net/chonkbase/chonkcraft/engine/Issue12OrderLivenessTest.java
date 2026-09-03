package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
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

/** Player-visible regression for GitHub issue 12's accepted-but-lost orders. */
class Issue12OrderLivenessTest {

    private record Fixture(GameData data, World world, CommandApplier commands) {}

    private static Fixture fixture(long flags) {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, "authenticated BNE assets are required");
        GameData data = new GameData(assets);
        GameMap map = new GameMap(64, 40, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(flags);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int player = 0; player < players.length; player++) {
            players[player] = new Player(player,
                    player < 2 ? PudMap.PlayerType.PERSON : PudMap.PlayerType.NOBODY,
                    player == 0 ? PudMap.Race.HUMAN : PudMap.Race.ORC);
        }
        World world = new World(map, players);
        data.configureWorld(world, PudMap.Tileset.FOREST);
        world.setAllied(0, 1, false);
        world.fog().revealAll(0);
        return new Fixture(data, world, new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values())));
    }

    private static Unit place(Fixture fixture, String ident, int player, int x, int y) {
        UnitType type = fixture.data().unitTypes().types().get(ident);
        assertNotNull(type, "retail roster has no " + ident);
        Unit unit = fixture.world().createUnit(type, player, x, y);
        assertNotNull(unit, "could not place " + ident);
        return unit;
    }

    @Test
    @DisplayName("reported combat units keep taking moves across committed chase steps")
    void reportedCombatUnitsKeepTakingMovesAcrossCommittedAttacks() {
        for (String ident : List.of(
                "unit-footman", "unit-archer", "unit-ballista", "unit-catapult")) {
            Fixture fixture = fixture(TileFlag.LAND_ALLOWED);
            Unit actor = place(fixture, ident, 0, 8, 16);
            Unit target = place(fixture, "unit-fortress", 1, 42, 14);
            exercise(fixture, actor, target, 20, 28);
        }

        Fixture fixture = fixture(TileFlag.WATER_ALLOWED);
        Unit destroyer = place(fixture, "unit-human-destroyer", 0, 8, 16);
        Unit target = place(fixture, "unit-ogre-juggernaught", 1, 42, 16);
        exercise(fixture, destroyer, target, 20, 28);
    }

    private static void exercise(Fixture fixture, Unit actor, Unit target,
            int nearX, int farX) {
        for (int repetition = 0; repetition < 12; repetition++) {
            target.setHitPoints(target.type().hitPoints());
            assertTrue(fixture.commands().apply(GameCommand.attack(
                            0, actor.id(), target.id())),
                    actor.type().ident() + " refused Attack at repetition " + repetition);
            boolean committed = false;
            for (int cycle = 0; cycle < 2_000 && target.isAlive(); cycle++) {
                fixture.world().tick();
                if (actor.order() == Unit.Order.ATTACK
                        && actor.animation().unbreakable()) {
                    committed = true;
                    break;
                }
            }
            assertTrue(committed, actor.type().ident()
                    + " never entered a committed attack at repetition " + repetition);

            int goal = repetition % 2 == 0 ? nearX : farX;
            assertTrue(fixture.commands().apply(GameCommand.move(
                            0, actor.id(), goal, 16)),
                    actor.type().ident() + " refused Move at repetition " + repetition);
            boolean obeyed = false;
            for (int cycle = 0; cycle < 4_000; cycle++) {
                fixture.world().tick();
                if (actor.tileX() == goal && actor.tileY() == 16
                        && actor.order() == Unit.Order.STILL
                        && !actor.hasQueuedOrders()
                        && !actor.queuedReplacementPending()) {
                    obeyed = true;
                    break;
                }
            }
            assertTrue(obeyed, actor.type().ident()
                    + " stopped obeying moves at repetition " + repetition
                    + ": at=" + actor.tileX() + "," + actor.tileY()
                    + " order=" + actor.order() + " target="
                    + (actor.target() == null ? -1 : actor.target().id())
                    + " queue=" + actor.queuedOrders()
                    + " replacement=" + actor.queuedReplacementPending()
                    + " unbreakable=" + actor.animation().unbreakable());
        }
    }
}
