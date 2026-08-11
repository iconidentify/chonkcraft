package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.unit.UnitType.Resource;
import net.chonkbase.chonkcraft.engine.upgrade.Upgrade;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A complete player-command construction, production and research loop. */
class BattleNetProductionPlayabilityTest {

    private record Fixture(GameData data, World world, CommandApplier commands) {}

    private static Fixture fixture() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set -Dchonkcraft.pack=... or wc2.install.dir");

        GameData data = new GameData(assets);
        GameMap map = new GameMap(48, 48, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        data.configureWorld(world, PudMap.Tileset.FOREST);
        List<UnitType> roster = new ArrayList<>(data.unitTypes().types().values());
        CommandApplier commands = new CommandApplier(world, roster);
        data.configureCommands(commands);
        return new Fixture(data, world, commands);
    }

    @Test
    @DisplayName("a player builds, trains and researches through the normal command stream")
    void completeBuildTrainResearchLoop() {
        Fixture fixture = fixture();
        World world = fixture.world();
        UnitType peasantType = type(fixture, "unit-peasant");
        UnitType farmType = type(fixture, "unit-farm");
        UnitType barracksType = type(fixture, "unit-human-barracks");
        UnitType blacksmithType = type(fixture, "unit-human-blacksmith");
        UnitType footmanType = type(fixture, "unit-footman");
        Upgrade sword = fixture.data().upgrades().upgrades().get("upgrade-sword1");
        assertNotNull(sword, "the retail-data roster has no first sword upgrade");

        world.player(0).set(Resource.GOLD, 50_000);
        world.player(0).set(Resource.WOOD, 50_000);
        world.player(0).set(Resource.OIL, 50_000);
        Unit peasant = world.createUnit(peasantType, 0, 3, 3);
        assertNotNull(peasant, "the player could not receive a starting worker");

        Unit farm = build(fixture, peasant, farmType, 10, 10);
        assertEquals(farmType.hitPoints(), farm.hitPoints(), "the farm is not complete");
        int supplyAfterFarm = world.player(0).supply();
        assertTrue(supplyAfterFarm >= farmType.supply(),
                "the finished farm did not contribute its declared supply");

        Unit barracks = build(fixture, peasant, barracksType, 17, 10);
        int goldBeforeTrain = world.player(0).get(Resource.GOLD);
        int footmenBefore = count(world, footmanType);
        fixture.commands().apply(GameCommand.train(0, barracks.id(),
                fixture.commands().indexOf(footmanType)));
        assertNotNull(barracks.producing(), "the barracks rejected the footman command");
        assertEquals(goldBeforeTrain - cost(footmanType, Resource.GOLD),
                world.player(0).get(Resource.GOLD), "training charged the wrong gold once");
        tickUntil(world, () -> count(world, footmanType) > footmenBefore, 100_000,
                "the barracks never released its footman");
        Unit footman = newest(world, footmanType);
        assertNotNull(footman, "the produced unit never entered play");
        assertTrue(footman.isAlive() && footman.isOnMap(),
                "the produced footman is not a playable map unit");

        Unit blacksmith = build(fixture, peasant, blacksmithType, 27, 10);
        int damageBefore = world.upgrades(0).piercingDamage(footmanType);
        int goldBeforeResearch = world.player(0).get(Resource.GOLD);
        int swordIndex = fixture.commands().indexOfUpgrade(sword.ident());
        assertTrue(swordIndex >= 0, "the retail upgrade is absent from the wire roster");
        fixture.commands().apply(GameCommand.research(0, blacksmith.id(), swordIndex));
        assertEquals(sword.ident(), blacksmith.researching(),
                "the blacksmith rejected the research command");
        assertEquals(goldBeforeResearch - cost(sword, Resource.GOLD),
                world.player(0).get(Resource.GOLD), "research charged the wrong gold once");
        tickUntil(world, () -> world.upgrades(0).has(sword.ident()), 100_000,
                "the sword upgrade never completed");
        assertTrue(world.upgrades(0).piercingDamage(footmanType) > damageBefore,
                "completed sword research did not improve the trained footman");
    }

    private static Unit build(Fixture fixture, Unit worker, UnitType type, int x, int y) {
        World world = fixture.world();
        Set<Integer> before = new LinkedHashSet<>();
        for (Unit unit : world.units()) {
            before.add(unit.id());
        }
        int goldBefore = world.player(0).get(Resource.GOLD);
        int woodBefore = world.player(0).get(Resource.WOOD);
        fixture.commands().apply(GameCommand.build(0, worker.id(),
                fixture.commands().indexOf(type), x, y));
        assertEquals(Unit.Order.BUILD, worker.order(), type.ident() + " order was rejected");
        tickUntil(world, () -> newestAfter(world, type, before) != null, 100_000,
                type.ident() + " foundation never appeared");
        Unit building = newestAfter(world, type, before);
        assertNotNull(building);
        assertEquals(goldBefore - cost(type, Resource.GOLD),
                world.player(0).get(Resource.GOLD), type.ident() + " charged wrong gold");
        assertEquals(woodBefore - cost(type, Resource.WOOD),
                world.player(0).get(Resource.WOOD), type.ident() + " charged wrong wood");
        tickUntil(world,
                () -> building.order() != Unit.Order.UNDER_CONSTRUCTION && worker.isOnMap(),
                100_000, type.ident() + " never completed");
        world.tick();
        assertEquals(Unit.Order.STILL, worker.order(),
                "the builder did not return to player control after " + type.ident());
        return building;
    }

    private static UnitType type(Fixture fixture, String ident) {
        UnitType type = fixture.data().unitTypes().types().get(ident);
        assertNotNull(type, "retail-data roster has no " + ident);
        assertTrue(fixture.commands().indexOf(type) >= 0, ident + " is absent from wire roster");
        return type;
    }

    private static int count(World world, UnitType type) {
        int count = 0;
        for (Unit unit : world.units()) {
            if (unit.type() == type && unit.isAlive()) {
                count++;
            }
        }
        return count;
    }

    private static Unit newest(World world, UnitType type) {
        Unit newest = null;
        for (Unit unit : world.units()) {
            if (unit.type() == type && unit.isAlive()
                    && (newest == null || unit.id() > newest.id())) {
                newest = unit;
            }
        }
        return newest;
    }

    private static Unit newestAfter(World world, UnitType type, Set<Integer> before) {
        Unit newest = null;
        for (Unit unit : world.units()) {
            if (!before.contains(unit.id()) && unit.type() == type
                    && (newest == null || unit.id() > newest.id())) {
                newest = unit;
            }
        }
        return newest;
    }

    private static int cost(UnitType type, Resource resource) {
        return type.costs().getOrDefault(resource, 0);
    }

    private static int cost(Upgrade upgrade, Resource resource) {
        return upgrade.costs().getOrDefault(resource, 0);
    }

    private static void tickUntil(World world, java.util.function.BooleanSupplier condition,
            int limit, String failure) {
        for (int cycle = 0; cycle < limit && !condition.getAsBoolean(); cycle++) {
            world.tick();
        }
        assertTrue(condition.getAsBoolean(), failure);
    }
}
