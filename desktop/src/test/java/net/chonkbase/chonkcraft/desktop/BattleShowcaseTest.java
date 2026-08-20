package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.map.PudReader;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BattleShowcaseTest {

    @Test
    @DisplayName("one player order per unit sustains a battle without a director")
    void playerOrderedBattleMakesSustainedProgressWithoutDirector() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String mapName = BattleShowcase.defaultMapName(assets);
        PudMap source = PudReader.read(assets.map(mapName));
        World world = new World(
                GameMap.from(source, data.loadTileset(source.tileset()).tileset()),
                Player.forSoloGame(source));
        data.configureWorld(world, source);
        BattleShowcase.Result battle = BattleShowcase.deploy(
                world, data.unitTypes().types(), 120);
        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);

        // Exactly one wire command per unit. There is intentionally no
        // BattleShowcase.Director here to notice a lull and repair it with a
        // fresh target. Each combatant must acquire, fight and resume from
        // its own attack-move state; the two scouts merely cross the field.
        Map<Integer, Long> startingPixels = new HashMap<>();
        for (Unit unit : battle.units()) {
            startingPixels.put(unit.id(), pixels(unit));
            int destinationX = unit.player() == 0
                    ? battle.centreX() + 20 : battle.centreX() - 20;
            GameCommand order = unit.type().canAttack()
                    ? GameCommand.attackMove(
                            unit.player(), unit.id(), destinationX, unit.tileY())
                    : GameCommand.move(
                            unit.player(), unit.id(), destinationX, unit.tileY());
            assertTrue(commands.apply(order),
                    unit.type().ident() + " refused its opening player order");
        }

        int previousAlive = alive(battle);
        long previousHealth = livingHealth(battle);
        Set<Integer> physicallyMoved = new java.util.HashSet<>();
        Set<Integer> orderMadeProgress = new java.util.HashSet<>();
        boolean sawProjectile = false;
        for (int cycle = 1; cycle <= 3_000; cycle++) {
            world.tick();
            sawProjectile |= !world.missiles().isEmpty();
            for (Unit unit : battle.units()) {
                if (pixels(unit) != startingPixels.get(unit.id())) {
                    physicallyMoved.add(unit.id());
                    orderMadeProgress.add(unit.id());
                }
                if (!unit.isAlive()
                        || unit.hitPoints() < unit.type().hitPoints()
                        || unit.target() != null) {
                    // A unit killed before its first step, a front-ranker
                    // taking a blow, and a stationary ranged unit acquiring
                    // a target are all real outcomes of its one order rather
                    // than frozen/no-op commands.
                    orderMadeProgress.add(unit.id());
                }
            }
            if (cycle % 500 != 0) {
                continue;
            }
            int nowAlive = alive(battle);
            long nowHealth = livingHealth(battle);
            assertTrue(alive(battle, 0) > 0 && alive(battle, 1) > 0,
                    "the sustained-progress window ended only because one army was gone");
            assertTrue(nowAlive < previousAlive || nowHealth < previousHealth,
                    "both hostile armies remained alive but combat made no damage or death"
                            + " progress during cycles " + (cycle - 499) + ".." + cycle
                            + "; a director would have hidden this stall by reissuing orders");
            previousAlive = nowAlive;
            previousHealth = nowHealth;
        }

        assertTrue(sawProjectile,
                "the undirected battle never put a retail projectile in flight");
        assertTrue(physicallyMoved.size() >= 100,
                "only " + physicallyMoved.size() + " of 120 player-ordered units moved pixels");
        assertEquals(battle.deployed(), orderMadeProgress.size(),
                "some player orders produced neither physical movement, combat acquisition,"
                        + " damage nor death");
        assertTrue(120 - alive(battle) >= 60,
                "the one-command battle produced only " + (120 - alive(battle))
                        + " casualties in 3,000 cycles");
    }

    @Test
    @DisplayName("the showcase deploys two full armies and they engage")
    void theShowcaseDeploysTwoFullArmiesAndTheyEngage() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String mapName = BattleShowcase.defaultMapName(assets);
        assertTrue(mapName.toLowerCase().contains("garden"),
                "the retail Garden of War should be the showcase stage");
        PudMap source = PudReader.read(assets.map(mapName));
        World world = new World(
                GameMap.from(source, data.loadTileset(source.tileset()).tileset()),
                Player.forSoloGame(source));
        data.configureWorld(world, source);

        BattleShowcase.Result result = BattleShowcase.deploy(
                world, data.unitTypes().types(), 120);
        assertEquals(120, result.deployed());
        assertEquals(60, result.humanUnits());
        assertEquals(60, result.orcUnits());
        assertEquals(world.map().width() / 2, result.centreX());
        assertEquals(world.map().height() / 2, result.centreY());
        assertEquals(BattleShowcase.HUMAN_FIELD_TYPES,
                deployedTypes(result, 0));
        assertEquals(BattleShowcase.ORC_FIELD_TYPES,
                deployedTypes(result, 1));
        long attackers = result.units().stream()
                .filter(unit -> unit.type().canAttack())
                .count();
        long explicitlyAimed = result.units().stream()
                .filter(unit -> unit.type().canAttack())
                .filter(unit -> unit.target() != null)
                .count();
        assertEquals(attackers, explicitlyAimed,
                "every combatant starts with a concrete enemy, not a vague destination");

        int oneTile = world.map().field(0, 0).tile();
        for (int y = 0; y < world.map().height(); y++) {
            for (int x = 0; x < world.map().width(); x++) {
                var field = world.map().field(x, y);
                assertEquals(oneTile, field.tile(), "arena picture at " + x + "," + y);
                assertTrue(field.isLandPassable(), "open ground at " + x + "," + y);
                assertFalse(field.hasFlag(TileFlag.FOREST | TileFlag.ROCKS
                                | TileFlag.WALL | TileFlag.UNPASSABLE),
                        "no terrain barrier at " + x + "," + y);
            }
        }

        BattleShowcase.Director director = new BattleShowcase.Director(world, result);
        int redirected = 0;
        for (int cycle = 0; cycle < 3_600; cycle++) {
            world.tick();
            redirected += director.update().redirected();
        }
        long hurtOrDead = result.units().stream()
                .filter(unit -> !unit.isAlive()
                        || unit.hitPoints() < unit.type().hitPoints())
                .count();
        assertTrue(hurtOrDead > 0,
                "the showcase must exercise combat rather than draw a static formation");
        assertTrue(redirected > 0,
                "disengaged survivors must be re-engaged instead of silently stalling");
    }

    private static Set<String> deployedTypes(BattleShowcase.Result result, int player) {
        return result.units().stream()
                .filter(unit -> unit.player() == player)
                .map(unit -> unit.type().ident())
                .collect(java.util.stream.Collectors.toSet());
    }

    private static long pixels(Unit unit) {
        return ((long) unit.pixelX() << 32) ^ (unit.pixelY() & 0xffffffffL);
    }

    private static int alive(BattleShowcase.Result battle) {
        return (int) battle.units().stream().filter(Unit::isAlive).count();
    }

    private static int alive(BattleShowcase.Result battle, int player) {
        return (int) battle.units().stream()
                .filter(Unit::isAlive)
                .filter(unit -> unit.player() == player)
                .count();
    }

    private static long livingHealth(BattleShowcase.Result battle) {
        return battle.units().stream()
                .filter(Unit::isAlive)
                .mapToLong(Unit::hitPoints)
                .sum();
    }
}
