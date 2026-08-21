package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        // There is intentionally no BattleShowcase.Director here to notice a
        // lull and repair it with a fresh target. The opening orders alone
        // must make progress through the central melee and perimeter ocean.
        Map<Integer, Long> startingPixels = new HashMap<>();
        Map<Integer, Unit> startingTargets = new HashMap<>();
        Map<Integer, Integer> startingMana = new HashMap<>();
        for (Unit unit : battle.units()) {
            startingPixels.put(unit.id(), pixels(unit));
            startingTargets.put(unit.id(), unit.target());
            startingMana.put(unit.id(), unit.mana());
            assertFalse(unit.order() == Unit.Order.STILL,
                    unit.type().ident() + " received no opening order");
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
                        || unit.mana() < startingMana.get(unit.id())
                        || unit.target() != startingTargets.get(unit.id())) {
                    orderMadeProgress.add(unit.id());
                }
                Unit openingTarget = startingTargets.get(unit.id());
                if (openingTarget != null && (!openingTarget.isAlive()
                        || openingTarget.hitPoints() < openingTarget.type().hitPoints())) {
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
        assertTrue(physicallyMoved.size() >= 40,
                "only " + physicallyMoved.size() + " of 120 player-ordered units moved pixels");
        assertTrue(orderMadeProgress.size() >= battle.deployed() - 1,
                "more than one player order produced neither physical movement, combat,"
                        + " damage nor death: " + battle.units().stream()
                                .filter(unit -> !orderMadeProgress.contains(unit.id()))
                                .map(unit -> unit.id() + ":" + unit.type().ident()
                                        + "@" + unit.tileX() + "," + unit.tileY()
                                        + "/" + unit.order())
                                .toList());
        assertTrue(120 - alive(battle) >= 55,
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
                "retail Garden of War should supply the original showcase stage");
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
        assertEquals(96, result.landUnits());
        assertEquals(24, result.navalUnits());
        assertTrue(result.openingSpells() > 0,
                "the opening should include real mage and death-knight spell orders");
        assertTrue(world.fog().isVisible(0, 0, 0)
                        && world.fog().isVisible(0,
                            world.map().width() - 1, world.map().height() - 1),
                "showcase recording should have no fog of war");
        assertTrue(world.fog().isDetected(0, 0, 0)
                        && world.fog().isDetected(0,
                            world.map().width() - 1, world.map().height() - 1),
                "showcase recording should reveal submarines everywhere");
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

        int land = 0;
        int water = 0;
        int coast = 0;
        int barriers = 0;
        for (int y = 0; y < world.map().height(); y++) {
            for (int x = 0; x < world.map().width(); x++) {
                var field = world.map().field(x, y);
                land += field.isLandPassable() ? 1 : 0;
                water += field.isWaterPassable() ? 1 : 0;
                coast += field.hasFlag(TileFlag.COAST_ALLOWED) ? 1 : 0;
                barriers += field.hasFlag(TileFlag.FOREST | TileFlag.ROCKS
                        | TileFlag.UNPASSABLE) ? 1 : 0;
            }
        }
        assertTrue(land > 0 && water > 0, "the showcase needs both combat domains");
        assertEquals(0, coast, "the artificial perimeter should not strand ships on coast");
        assertEquals(0, barriers, "the central arena should remain completely open");
        assertTrue(world.map().field(result.centreX(), result.centreY()).isLandPassable());
        assertTrue(world.map().field(0, 0).isWaterPassable());

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
