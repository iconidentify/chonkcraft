package net.chonkbase.chonkcraft.engine.save;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.ai.BattleNetAiBytecode;
import net.chonkbase.chonkcraft.engine.ai.AiForce;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.missile.Missile;
import net.chonkbase.chonkcraft.engine.missile.MissileType;
import net.chonkbase.chonkcraft.engine.trigger.TriggerSystem;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Saving and loading a game.
 *
 * <p>The native document the engine writes must be one it can read, and what
 * comes back must be the game that went in.
 */
class SaveGameTest {

    @Test
    @DisplayName("version four preserves trigger flags and an in-flight victory countdown")
    void mutableTriggerStateSurvivesSaveAndResume() throws IOException {
        World world = new World(new GameMap(8, 8, new Tileset()));
        TriggerSystem before = new TriggerSystem(world, 0, List.of(
                new TriggerSystem.ProgramSpec("TRUE", "SET_FLAG Qb2lsX3JlYWR5"),
                new TriggerSystem.ProgramSpec("TRUE", "DELAYED_VICTORY 5 0"),
                new TriggerSystem.ProgramSpec("FALSE", "DEFEAT")));
        before.evaluate();
        before.evaluate();
        TriggerSystem.SavedState checkpoint = before.savedState();
        assertEquals(List.of(1, 2), checkpoint.armed());
        assertEquals(List.of("oil_ready"), checkpoint.flags());
        assertEquals(List.of(new TriggerSystem.SavedDelay(1, 3)), checkpoint.delays());

        StringWriter out = new StringWriter();
        SaveGame.writeWithTriggers(world, "test-map", "orc", 2, checkpoint, out);
        String script = out.toString();
        assertTrue(script.startsWith("SaveFormat(\"chonkcraft-save\", 4)"));
        assertEquals(checkpoint, LoadGame.triggerState(script));

        TriggerSystem resumed = new TriggerSystem(world, 0, List.of(
                new TriggerSystem.ProgramSpec("TRUE", "SET_FLAG Qb2lsX3JlYWR5"),
                new TriggerSystem.ProgramSpec("TRUE", "DELAYED_VICTORY 5 0"),
                new TriggerSystem.ProgramSpec("FALSE", "DEFEAT")));
        resumed.restoreState(LoadGame.triggerState(script));
        assertEquals(checkpoint, resumed.savedState());
        resumed.evaluate();
        resumed.evaluate();
        assertEquals(TriggerSystem.Outcome.RUNNING, resumed.outcome());
        resumed.evaluate();
        assertEquals(TriggerSystem.Outcome.VICTORY, resumed.outcome(),
                "loading restarted or shortened the native countdown");
    }

    @Test
    @DisplayName("a diplomacy trigger's war survives save and resume")
    void aDiplomacyTriggerWarSurvivesSaveAndResume() throws IOException {
        World before = rescueWorld();
        before.establishDiplomacy();
        assertFalse(before.isEnemyPlayer(4, 2),
                "two rescue-active slots are not born enemies");
        TriggerSystem triggers = new TriggerSystem(before, 0, List.of(
                new TriggerSystem.ProgramSpec(
                        "TRUE", "DIPLOMACY 4 QZW5lbXk 2 2 QZW5lbXk 4")));
        triggers.evaluate();
        assertTrue(before.isEnemyPlayer(4, 2),
                "the TRUE diplomacy action must be in force after one evaluate");
        assertTrue(before.isEnemyPlayer(2, 4));
        assertEquals(List.of(), triggers.armedTriggers(),
                "a spent diplomacy trigger must not re-fire after resume");

        StringWriter out = new StringWriter();
        SaveGame.writeWithTriggers(before, "test-map", "human", 8,
                triggers.savedState(), out);
        String script = out.toString();
        assertTrue(script.contains("SetDiplomacy(4, \"enemy\", 2)"),
                "the save must carry the trigger's directed war, not just player types");

        World after = rescueWorld();
        after.establishDiplomacy();
        LoadGame.apply(after, script, java.util.Map.of());
        TriggerSystem resumed = new TriggerSystem(after, 0, List.of(
                new TriggerSystem.ProgramSpec(
                        "TRUE", "DIPLOMACY 4 QZW5lbXk 2 2 QZW5lbXk 4")));
        resumed.restoreState(LoadGame.triggerState(script));
        assertEquals(List.of(), resumed.armedTriggers());
        assertTrue(after.isEnemyPlayer(4, 2),
                "reloading from player types alone used to drop the siege's war");
        assertTrue(after.isEnemyPlayer(2, 4));
        assertFalse(after.isEnemyPlayer(4, 6),
                "a pair the trigger never named must stay type-derived");
    }

    @Test
    @DisplayName("human 8's opening siege still hates the town after save and resume")
    void humanEightOpeningSiegeSurvivesSaveAndResume() throws IOException {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install configured. Set -Dchonkcraft.pack or wc2.install.dir.");
        GameData data = new GameData(assets);
        var continuous = data.loadMission("campaigns/human/level08h");
        Assumptions.assumeTrue(continuous != null, "human 8 did not load from the pack");
        continuous.tick();
        assertTrue(continuous.world().isEnemyPlayer(4, 2),
                "the opening diplomacy trigger must fire before any unit acts");
        assertTrue(continuous.world().isEnemyPlayer(4, 6));
        assertEquals(List.of(1, 2),
                continuous.triggers().armedTriggers(),
                "the spent opening trigger must not remain armed");

        StringWriter out = new StringWriter();
        SaveGame.writeWithTriggers(continuous.world(), "campaigns/human/level08h",
                "human", 8, continuous.triggers().savedState(), out);
        String script = out.toString();

        var resumed = data.loadMission("campaigns/human/level08h");
        for (Unit unit : new ArrayList<>(resumed.world().units())) {
            resumed.world().remove(unit);
        }
        LoadGame.apply(resumed.world(), script, data.unitTypes().types());
        resumed.triggers().restoreState(LoadGame.triggerState(script));
        assertTrue(resumed.world().isEnemyPlayer(4, 2),
                "reloading human 8 from player types used to drop the siege");
        assertTrue(resumed.world().isEnemyPlayer(4, 6));
        assertEquals(continuous.triggers().armedTriggers(),
                resumed.triggers().armedTriggers());
        assertEquals(continuous.world().diplomacyStance(4, 2),
                resumed.world().diplomacyStance(4, 2));
        assertEquals(continuous.world().diplomacyStance(2, 4),
                resumed.world().diplomacyStance(2, 4));
    }

    @Test
    @DisplayName("a mismatched trigger program cannot silently consume saved state")
    void triggerStateFailsClosedAgainstDifferentMissionProgram() {
        World world = new World(new GameMap(8, 8, new Tileset()));
        TriggerSystem system = new TriggerSystem(world, 0, List.of(
                new TriggerSystem.ProgramSpec("FALSE", "NOOP")));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> system.restoreState(new TriggerSystem.SavedState(
                        List.of(7), List.of(), List.of())));
    }

    @Test
    @DisplayName("the Human 6 saved assault resumes and partial wood remains cargo")
    void humanSixAssaultAndWoodResumeAfterSave() throws IOException {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install configured. Set -Dchonkcraft.pack or wc2.install.dir.");
        Path save = Path.of(System.getProperty("user.home"), ".chonkcraft", "saves",
                "human-mission-6.sav.gz");
        Assumptions.assumeTrue(Files.isRegularFile(save),
                "the Human 6 playtest save is not installed");

        GameData data = new GameData(assets);
        String script = LoadGame.read(save);
        LoadGame.Header header = LoadGame.header(script);
        assertNotNull(header);
        var mission = data.loadMission(header.mapPath());
        assertNotNull(mission);
        for (Unit unit : new ArrayList<>(mission.world().units())) {
            mission.world().remove(unit);
        }
        LoadGame.apply(mission.world(), script, data.unitTypes().types());
        World world = mission.world();

        Unit grunt = world.units().stream().filter(unit -> unit.id() == 163)
                .findFirst().orElse(null);
        Unit worker = world.units().stream().filter(unit -> unit.id() == 157)
                .findFirst().orElse(null);
        Unit remoteWorker = world.units().stream().filter(unit -> unit.id() == 173)
                .findFirst().orElse(null);
        assertNotNull(grunt, "saved assault grunt 163 is missing");
        assertNotNull(worker, "saved partial-load peasant 157 is missing");
        assertNotNull(remoteWorker, "saved remote-base peasant 173 is missing");
        assertEquals(UnitType.Resource.WOOD, worker.heldResource(),
                "loading discarded the identity of the partial wood cargo");
        assertEquals(96, worker.carried());
        int startX = grunt.tileX();
        int startY = grunt.tileY();
        int woodBefore = world.player(1).get(UnitType.Resource.WOOD);

        world.tick();
        assertTrue(worker.carried() >= 96,
                "the first resumed harvest cycle threw away partial wood");
        for (int cycle = 1; cycle < 700; cycle++) {
            world.tick();
        }

        assertTrue(grunt.tileX() != startX || grunt.tileY() != startY,
                "the restored assault patrol remained frozen on an empty route");
        assertTrue(world.player(1).get(UnitType.Resource.WOOD) >= woodBefore + 200,
                "the saved partial load and nearby Town Hall load were not banked");
    }

    @Test
    @DisplayName("the Human 5 footman stops fighting empty ground after load")
    void humanFiveInvisibleEnemyStateHealsAfterLoad() throws IOException {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install configured. Set -Dchonkcraft.pack or wc2.install.dir.");
        Path save = Path.of(System.getProperty("user.home"), ".chonkcraft", "saves",
                "human-mission-5.sav.gz");
        Assumptions.assumeTrue(Files.isRegularFile(save),
                "the Human 5 playtest save is not installed");

        GameData data = new GameData(assets);
        String script = LoadGame.read(save);
        LoadGame.Header header = LoadGame.header(script);
        assertNotNull(header);
        var mission = data.loadMission(header.mapPath());
        assertNotNull(mission);
        for (Unit unit : new ArrayList<>(mission.world().units())) {
            mission.world().remove(unit);
        }
        LoadGame.apply(mission.world(), script, data.unitTypes().types());
        Unit footman = mission.world().units().stream()
                .filter(unit -> unit.type() != null
                        && "unit-footman".equals(unit.type().ident())
                        && unit.order() == Unit.Order.ATTACK_GROUND
                        && unit.orderTargetX() == 45 && unit.orderTargetY() == 103)
                .findFirst().orElse(null);
        assertNotNull(footman, "saved footman fighting 45,103 is missing");
        assertEquals(Unit.Order.ATTACK_GROUND, footman.order(),
                "the regression save no longer contains its diagnostic state");
        assertTrue(footman.battleNetPlayerCommandMove());
        assertFalse(footman.battleNetAttackGroundMove(),
                "a pre-fix save cannot claim the new provenance marker");

        int healedAt = -1;
        for (int cycle = 1; cycle <= 80; cycle++) {
            mission.tick();
            if (footman.order() != Unit.Order.ATTACK_GROUND) {
                healedAt = cycle;
                break;
            }
        }

        assertTrue(healedAt > 0,
                "the footman kept fighting the empty square at 45,103");
        assertEquals(Unit.Order.STILL, footman.order(),
                "the corrupt empty-ground order did not terminate cleanly");
    }

    @Test
    @DisplayName("the Human expansion 6 battleships can reach every saved shipyard")
    void humanExpansionSixBattleshipsReachEverySavedShipyard() throws IOException {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install configured. Set -Dchonkcraft.pack or wc2.install.dir.");
        Path save = Path.of(System.getProperty("user.home"), ".chonkcraft", "saves",
                "human-exp-mission-6.sav.gz");
        Assumptions.assumeTrue(Files.isRegularFile(save),
                "the Human expansion 6 playtest save is not installed");

        GameData data = new GameData(assets);
        String script = LoadGame.read(save);
        LoadGame.Header header = LoadGame.header(script);
        assertNotNull(header);
        for (int attackerId : new int[] {191, 195}) {
            for (int targetId : new int[] {217, 239, 251}) {
                var mission = data.loadMission(header.mapPath());
                assertNotNull(mission);
                for (Unit unit : new ArrayList<>(mission.world().units())) {
                    mission.world().remove(unit);
                }
                LoadGame.apply(mission.world(), script, data.unitTypes().types());
                World world = mission.world();
                Unit attacker = world.units().stream()
                        .filter(unit -> unit.id() == attackerId)
                        .findFirst().orElse(null);
                Unit target = world.units().stream()
                        .filter(unit -> unit.id() == targetId)
                        .findFirst().orElse(null);
                assertNotNull(attacker, "saved battleship " + attackerId + " is missing");
                assertNotNull(target, "saved shipyard " + targetId + " is missing");
                world.fog().revealAll(attacker.player());
                // Keep the route and target geometry from the exact save but
                // remove defenders so this measures command fulfillment, not
                // whether the lone battleship survives the whole enemy base.
                for (Unit unit : new ArrayList<>(world.units())) {
                    if (unit != attacker && unit != target
                            && world.isEnemyPlayer(attacker.player(), unit.player())) {
                        world.remove(unit);
                    }
                }
                int hitPoints = target.hitPoints();
                assertTrue(world.orderAttack(attacker, target),
                        "saved battleship refused shipyard " + targetId);
                for (int cycle = 0; cycle < 3_000
                        && target.hitPoints() == hitPoints; cycle++) {
                    world.tick();
                }
                assertTrue(target.hitPoints() < hitPoints,
                        "battleship " + attackerId + " never reached shipyard "
                                + targetId + " without a manual move order");
            }
        }
    }

    @Test
    @DisplayName("a restored pending ballista bolt is cancelled when the player moves")
    void aRestoredPendingBallistaBoltIsCancelledWhenThePlayerMoves() {
        // Used to read ~/.chonkcraft/saves/human-mission-6.sav.gz and skip
        // when that rolling playtest file no longer held the bolt. The
        // player-visible rule is independent of that file: a presentation-
        // ahead bolt restored from a native save must vanish when the
        // ballista is given a move, or the old muzzle shot fires later.
        Fixture fixture = load();
        GameMap map = new GameMap(24, 24, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        map.recordLoadedTerrain();
        World world = new World(map);
        fixture.data().configureWorld(world, PudMap.Tileset.FOREST);
        CommandApplier commands = new CommandApplier(world,
                new ArrayList<>(fixture.data().unitTypes().types().values()));
        fixture.data().configureCommands(commands);
        UnitType ballistaType = fixture.data().unitTypes().types().get("unit-ballista");
        UnitType gruntType = fixture.data().unitTypes().types().get("unit-grunt");
        MissileType boltType = fixture.data().missiles().types().get("missile-ballista-bolt");
        assertNotNull(ballistaType, "the retail roster has a ballista");
        assertNotNull(gruntType, "the retail roster has a grunt");
        assertNotNull(boltType, "the retail catalog has a ballista bolt");
        Unit ballista = world.createUnit(ballistaType, 0, 4, 8);
        Unit target = world.createUnit(gruntType, 1, 12, 8);
        assertNotNull(ballista, "could not place the ballista");
        assertNotNull(target, "could not place the bolt's target");
        world.setAllied(0, 1, false);
        world.fog().revealAll(0);
        Missile placeholder = new Missile(boltType, ballista, target,
                ballista.pixelX(), ballista.pixelY(),
                target.pixelX(), target.pixelY());
        world.restoreMissile("missile-ballista-bolt", ballista, target,
                placeholder.savedState(), world.cycle(), world.cycle(), true);
        assertEquals(1, world.missiles().size(),
                "the restored save must keep one pending ballista bolt");
        Missile oldMuzzle = world.missiles().get(0);
        assertTrue(world.savedProjectilePending(oldMuzzle),
                "the restored bolt must still be waiting for opcode ten");

        assertTrue(commands.apply(GameCommand.move(0, ballista.id(),
                ballista.tileX() + 2, ballista.tileY())),
                "the player move was refused");

        assertTrue(world.missiles().isEmpty(),
                "the restored pending bolt stayed at the old muzzle after the move");
        for (int cycle = 0; cycle < 20; cycle++) {
            world.tick();
        }
        assertTrue(world.missiles().isEmpty(),
                "the cancelled saved bolt woke up and fired later");
    }

    private record Fixture(GameData data, World world, PudMap source, String mapPath) {}

    private static Fixture load() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install configured. Set -Dchonkcraft.pack or wc2.install.dir.");

        GameData data = new GameData(assets);
        String mapPath = "campaigns/human/level01h";
        PudMap source = data.campaignMap(mapPath);
        Assumptions.assumeTrue(source != null, "the first human mission is not available");

        World world = new World(GameMap.from(source, data.loadTileset(source.tileset()).tileset()),
                Player.from(source));
        world.setUpgrades(data.upgrades().upgrades());
        data.populate(world, source);
        world.recalculateSupply();
        return new Fixture(data, world, source, mapPath);
    }

    /** A fresh world on the same map, with terrain but no units. */
    private static World emptyWorld(Fixture fixture) {
        return new World(
                GameMap.from(fixture.source(),
                        fixture.data().loadTileset(fixture.source().tileset()).tileset()),
                Player.from(fixture.source()));
    }

    private static String save(Fixture fixture) throws IOException {
        StringWriter out = new StringWriter();
        SaveGame.write(fixture.world(), fixture.mapPath(), "human", 1, out);
        return out.toString();
    }

    @Test
    @DisplayName("a save is a versioned native document the engine can read back")
    void aSaveIsANativeDocument() throws IOException {
        Fixture fixture = load();
        String script = save(fixture);

        assertTrue(script.startsWith("SaveFormat(\"chonkcraft-save\", 3)"));
        assertTrue(!script.contains("function CreateUnit") && !script.contains("Load("),
                "the native save still embeds executable map-loading source");

        LoadGame.Header header = LoadGame.header(script);
        assertNotNull(header, "the save has no header");
        assertEquals("campaigns/human/level01h", header.mapPath());
        assertEquals("human", header.campaign());
        assertEquals(1, header.mission());
    }

    @Test
    @DisplayName("every unit comes back where it stood")
    void unitsRoundTrip() throws IOException {
        Fixture fixture = load();
        List<String> before = describe(fixture.world());
        Assumptions.assumeTrue(!before.isEmpty(), "the map places no units");

        String script = save(fixture);
        World reloaded = emptyWorld(fixture);
        reloaded.setUpgrades(fixture.data().upgrades().upgrades());
        LoadGame.apply(reloaded, script, fixture.data().unitTypes().types());

        assertEquals(before, describe(reloaded));
    }

    @Test
    @DisplayName("workers in mines and passengers in transports survive a save")
    void containedUnitsRoundTrip() throws IOException {
        Fixture fixture = load();
        var types = fixture.data().unitTypes().types();
        GameMap map = new GameMap(40, 40, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        // The board is now what a map file would have given, so a save of it
        // has no ground to carry. Without this the fixture reads as forty by
        // forty squares the game changed, and the save says so at length.
        map.recordLoadedTerrain();
        World world = new World(map);
        Unit mine = world.createUnit(types.get("unit-gold-mine"), 15, 10, 10);
        mine.setResourcesHeld(50_000);
        Unit worker = world.createUnit(types.get("unit-peasant"), 0, 9, 11);
        assertTrue(world.orderHarvest(worker, 10, 10));
        for (int cycle = 0; cycle < 20 && !worker.removed(); cycle++) {
            world.tick();
        }
        assertTrue(worker.removed(), "the fixture never put the worker inside the mine");
        assertEquals(mine, worker.worksite());

        Unit transport = world.createUnit(types.get("unit-human-transport"), 0, 20, 20);
        Unit passenger = world.createUnit(types.get("unit-footman"), 0, 19, 20);
        assertTrue(world.board(passenger, transport), "the fixture could not board its passenger");
        assertTrue(passenger.removed());

        StringWriter out = new StringWriter();
        SaveGame.write(world, "test-map", null, 0, out);
        World reloaded = new World(new GameMap(40, 40, new Tileset()));
        LoadGame.apply(reloaded, out.toString(), types);

        assertEquals(world.units().size(), reloaded.units().size(),
                "contained units disappeared from the save");
        Unit loadedWorker = find(reloaded, "unit-peasant");
        Unit loadedMine = find(reloaded, "unit-gold-mine");
        assertTrue(loadedWorker.removed());
        assertEquals(loadedMine, loadedWorker.worksite(),
                "the worker did not come back inside its mine");
        assertEquals(Unit.Order.HARVEST, loadedWorker.order());

        Unit loadedPassenger = find(reloaded, "unit-footman");
        Unit loadedTransport = find(reloaded, "unit-human-transport");
        assertTrue(loadedPassenger.removed());
        assertEquals(loadedTransport, loadedPassenger.carrier());
        assertTrue(loadedTransport.cargo().contains(loadedPassenger));
    }

    @Test
    @DisplayName("resources and researched upgrades come back")
    void playerStateRoundTrips() throws IOException {
        Fixture fixture = load();
        World world = fixture.world();
        world.player(0).set(UnitType.Resource.GOLD, 4321);
        world.player(0).set(UnitType.Resource.WOOD, 1234);
        world.upgrades(0).complete("upgrade-sword1");

        String script = save(fixture);
        World reloaded = emptyWorld(fixture);
        reloaded.setUpgrades(fixture.data().upgrades().upgrades());
        LoadGame.apply(reloaded, script, fixture.data().unitTypes().types());

        assertEquals(4321, reloaded.player(0).get(UnitType.Resource.GOLD));
        assertEquals(1234, reloaded.player(0).get(UnitType.Resource.WOOD));
        assertTrue(reloaded.upgrades(0).has("upgrade-sword1"));
    }

    @Test
    @DisplayName("a mid-game save carries the cycle and the random position")
    void theClockAndTheGeneratorRoundTrip() throws IOException {
        Fixture fixture = load();
        for (int i = 0; i < 120; i++) {
            fixture.world().tick();
        }
        // Draw a few, so the generator is somewhere other than its start.
        for (int i = 0; i < 7; i++) {
            fixture.world().syncRand(100);
        }
        long cycle = fixture.world().cycle();
        long draws = fixture.world().randomDraws();
        assertTrue(draws > 0, "nothing was drawn from the generator");

        String script = save(fixture);
        World reloaded = emptyWorld(fixture);
        reloaded.setUpgrades(fixture.data().upgrades().upgrades());
        LoadGame.apply(reloaded, script, fixture.data().unitTypes().types());

        assertEquals(cycle, reloaded.cycle());
        assertEquals(draws, reloaded.randomDraws());
        // The position, not just the count: a save that rewound the sequence
        // would carry on drawing different numbers from the game it restored.
        assertEquals(fixture.world().syncRand(1000), reloaded.syncRand(1000));
    }

    @Test
    @DisplayName("what a mission forbids survives the save")
    void theAllowTableRoundTrips() throws IOException {
        Fixture fixture = load();
        var mission = fixture.data().loadMission("campaigns/human/level01h", 0);
        Assumptions.assumeTrue(mission != null, "the first human mission is not available");

        StringWriter out = new StringWriter();
        SaveGame.write(mission.world(), "campaigns/human/level01h", "human", 1, out);

        World reloaded = emptyWorld(fixture);
        reloaded.setUpgrades(fixture.data().upgrades().upgrades());
        LoadGame.apply(reloaded, out.toString(), fixture.data().unitTypes().types());

        assertNotNull(reloaded.allowed(), "the allow table did not survive");
        assertTrue(reloaded.allowed().isAllowed(0, "unit-farm"));
        assertTrue(!reloaded.allowed().isAllowed(0, "unit-knight"),
                "the first mission still forbids knights after loading");
    }

    @Test
    @DisplayName("a save written to disk is gzipped and reads back")
    void savesGoToDiskCompressed(@TempDir Path directory) throws IOException {
        Fixture fixture = load();
        Path file = directory.resolve("test" + SaveGame.SUFFIX);
        SaveGame.write(fixture.world(), fixture.mapPath(), "human", 1, file);

        byte[] raw = Files.readAllBytes(file);
        assertEquals(0x1F, raw[0] & 0xFF, "not gzipped");
        assertEquals(0x8B, raw[1] & 0xFF, "not gzipped");

        LoadGame.Header header = LoadGame.header(LoadGame.read(file));
        assertNotNull(header);
        assertEquals(fixture.mapPath(), header.mapPath());
        assertEquals("test", LoadGame.nameOf(file));
    }

    @Test
    @DisplayName("an unknown call in a save does not lose the game")
    void unknownCallsAreIgnored() {
        // A save written by a later version can say things this one does not
        // understand. Ignoring them loses that state; refusing to load loses
        // everything.
        String script = """
                SavedGameInfo({ SaveFile = "somewhere", SyncRandSeed = 0, SyncRandDraws = 0 })
                SomethingFromTheFuture(1, 2, 3)
                GameCycle = 99
                """;
        LoadGame.Header header = LoadGame.header(script);
        assertNotNull(header);
        assertEquals("somewhere", header.mapPath());
        assertEquals(99, header.cycle());
    }

    // ----------------------------------------------------- the wider round trip

    /**
     * A world with terrain and nothing on it, and the real roster to fill it.
     *
     * <p>Synthetic rather than a campaign map because the point of the tests
     * below is to put one specific piece of state into the game and ask for it
     * back, and a mission's own units make that a needle in a haystack. The
     * unit types are the shipped ones, so the costs, build times and hit
     * points are the game's.
     */
    private record Bench(World world, java.util.Map<String, UnitType> types,
            java.util.Map<String, MissileType> missileTypes) {}

    private static Bench bench() {
        Fixture fixture = load();
        GameMap map = new GameMap(48, 48, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        map.recordLoadedTerrain();
        World world = new World(map);
        world.setUpgrades(fixture.data().upgrades().upgrades());
        world.setUnitTypes(fixture.data().unitTypes().types());
        world.setMissileTypes(fixture.data().missiles().types());
        return new Bench(world, fixture.data().unitTypes().types(),
                fixture.data().missiles().types());
    }

    private static World reload(Bench bench) throws IOException {
        StringWriter out = new StringWriter();
        SaveGame.write(bench.world(), "test-map", null, 0, out);
        GameMap map = new GameMap(bench.world().map().width(), bench.world().map().height(),
                new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        map.recordLoadedTerrain();
        World reloaded = new World(map);
        reloaded.setUpgrades(load().data().upgrades().upgrades());
        reloaded.setUnitTypes(bench.types());
        reloaded.setMissileTypes(bench.missileTypes());
        LoadGame.apply(reloaded, out.toString(), bench.types());
        return reloaded;
    }

    @Test
    @DisplayName("partial cargo keeps its resource identity across a save")
    void partialCargoRoundTrips() throws IOException {
        Bench bench = bench();
        Unit worker = bench.world().createUnit(bench.types().get("unit-peasant"), 0, 10, 10);
        worker.setOrder(Unit.Order.HARVEST);
        worker.setCarrying(UnitType.Resource.WOOD);
        worker.setHeldResource(UnitType.Resource.WOOD);
        worker.setCarried(37);

        Unit loaded = find(reload(bench), "unit-peasant");
        assertEquals(UnitType.Resource.WOOD, loaded.carrying());
        assertEquals(UnitType.Resource.WOOD, loaded.heldResource());
        assertEquals(37, loaded.carried());
    }

    @Test
    @DisplayName("a queued BNE point command keeps both of its clocks")
    void playerCommandBoundaryRoundTrips() throws IOException {
        Bench bench = bench();
        Unit unit = bench.world().createUnit(
                bench.types().get("unit-footman"), 0, 10, 10);
        unit.setOrder(Unit.Order.MOVE);
        unit.setBattleNetOrderDelay(7);
        unit.rememberActionBeforeQueued(Unit.Order.STILL, 6);
        unit.setBattleNetPlayerCommandMove(true);
        unit.setBattleNetAttackGroundMove(true);

        Unit loaded = find(reload(bench), "unit-footman");

        assertEquals(7, loaded.battleNetOrderDelay());
        assertEquals(Unit.Order.STILL, loaded.currentAction());
        assertEquals(6, loaded.actionBeforeQueuedReleaseDelay());
        assertTrue(loaded.battleNetPlayerCommandMove());
        assertTrue(loaded.battleNetAttackGroundMove());
    }

    @Test
    @DisplayName("an exhausted attack refill keeps its target-bound residual")
    void attackWaitRefillResidualRoundTrips() throws IOException {
        Bench bench = bench();
        Unit attacker = bench.world().createUnit(
                bench.types().get("unit-grunt"), 0, 10, 10);
        Unit target = bench.world().createUnit(
                bench.types().get("unit-footman"), 1, 10, 12);
        attacker.setOrder(Unit.Order.ATTACK);
        attacker.setTarget(target);
        attacker.setBattleNetAttackWaitRefillResidual(true);

        Unit loaded = find(reload(bench), "unit-grunt");

        assertNotNull(loaded.target());
        assertEquals("unit-footman", loaded.target().type().ident());
        assertTrue(loaded.battleNetAttackWaitRefillResidual(),
                "a save during the borrowed leftover must not land four cycles early");
    }

    @Test
    @DisplayName("a tanker resumes the same native oil action and cadence")
    void tankerOilStateRoundTrips() throws IOException {
        Bench bench = bench();
        Unit platform = bench.world().createUnit(
                bench.types().get("unit-human-oil-platform"), 0, 20, 20);
        Unit shipyard = bench.world().createUnit(
                bench.types().get("unit-human-shipyard"), 0, 6, 6);
        Unit tanker = bench.world().createUnit(
                bench.types().get("unit-human-oil-tanker"), 0, 10, 10);
        tanker.setOrder(Unit.Order.HARVEST);
        tanker.setCarrying(UnitType.Resource.OIL);
        tanker.setHeldResource(UnitType.Resource.OIL);
        tanker.setCarried(100);
        tanker.setResourceUnit(platform);
        tanker.setResourceTile(platform.tileX(), platform.tileY());
        tanker.setResourceDepot(shipyard);
        tanker.setReturnDepotGoal(shipyard);
        tanker.setReturningToDepot(true);
        tanker.setBattleNetOilAction(Unit.BattleNetOilAction.TO_DEPOT);
        tanker.setBattleNetOilActionTicks(0);
        tanker.setBattleNetOilStartedAdjacent(true);

        World reloaded = reload(bench);
        Unit loaded = find(reloaded, "unit-human-oil-tanker");

        assertEquals(Unit.BattleNetOilAction.TO_DEPOT,
                loaded.battleNetOilAction());
        assertEquals(24, loaded.battleNetOilAction().rawAction());
        assertEquals(0, loaded.battleNetOilActionTicks());
        assertTrue(loaded.battleNetOilStartedAdjacent());
        assertTrue(loaded.returningToDepot(),
                "a visible laden tanker reloaded on the platform-bound leg");
        assertEquals("unit-human-oil-platform", loaded.resourceUnit().type().ident());
        assertEquals("unit-human-shipyard", loaded.resourceDepot().type().ident());
        assertEquals(loaded.resourceDepot(), loaded.returnDepotGoal());
    }

    @Test
    @DisplayName("a save cannot strand native oil action 24 outside its resource order")
    void orphanedNativeOilReturnStateRepairsOnLoad() throws IOException {
        Bench bench = bench();
        Unit tanker = bench.world().createUnit(
                bench.types().get("unit-human-oil-tanker"), 0, 10, 10);
        tanker.setCarrying(UnitType.Resource.OIL);
        tanker.setHeldResource(UnitType.Resource.OIL);
        tanker.setCarried(100);
        // Reproduce the field save exactly: a raw native return action with
        // no Java COrder_Resource projection, so SaveGame writes no
        // SetHarvestState line.
        tanker.setOrder(Unit.Order.STILL);
        tanker.setBattleNetOilAction(Unit.BattleNetOilAction.TO_DEPOT);
        tanker.setReturningToDepot(false);

        Unit loaded = find(reload(bench), "unit-human-oil-tanker");

        assertEquals(Unit.Order.HARVEST, loaded.order());
        assertEquals(Unit.BattleNetOilAction.TO_DEPOT, loaded.battleNetOilAction());
        assertTrue(loaded.returningToDepot());
    }

    @Test
    @DisplayName("a player resumes with the score, kills and razings they had earned")
    void theScoreAndItsTalliesRoundTrip() throws IOException {
        Bench bench = bench();
        bench.world().player(0).setScore(1234);
        bench.world().player(0).setTotalKills(7);
        bench.world().player(0).setTotalRazings(3);

        World reloaded = reload(bench);

        assertEquals(1234, reloaded.player(0).score(),
                "the results screen would say the player had scored nothing");
        assertEquals(7, reloaded.player(0).totalKills(), "the kill tally was not saved");
        assertEquals(3, reloaded.player(0).totalRazings(), "the razing tally was not saved");
    }

    @Test
    @DisplayName("a town hall resumes the peasant it has already paid for")
    void aBuildingKeepsTheJobItHasPaidFor() throws IOException {
        Bench bench = bench();
        World world = bench.world();
        world.player(0).set(UnitType.Resource.GOLD, 5000);
        Unit hall = world.createUnit(bench.types().get("unit-town-hall"), 0, 10, 10);
        assertTrue(world.orderTrain(hall, bench.types().get("unit-peasant")),
                "the fixture could not start training");
        assertTrue(world.setRallyPoint(hall, 20, 20), "the fixture could not set a rally point");
        for (int cycle = 0; cycle < 40; cycle++) {
            world.tick();
        }
        assertTrue(hall.progress() > 0, "the fixture saved before any work was done");
        int progress = hall.progress();

        World reloaded = reload(bench);
        Unit loaded = find(reloaded, "unit-town-hall");

        // The gold was taken at the order. Losing the job loses the gold with
        // it, and nothing tells the player where it went.
        assertNotNull(loaded.producing(), "the paid-for peasant was forgotten");
        assertEquals("unit-peasant", loaded.producing().ident());
        assertEquals(progress, loaded.progress(), "the training restarted from nothing");
        assertEquals(hall.progressGoal(), loaded.progressGoal());
        assertEquals(20, loaded.rallyX(), "the rally point was forgotten");
        assertEquals(20, loaded.rallyY());
    }

    @Test
    @DisplayName("a blacksmith resumes the research it has already paid for")
    void aBuildingKeepsTheResearchItHasPaidFor() throws IOException {
        Bench bench = bench();
        World world = bench.world();
        world.player(0).set(UnitType.Resource.GOLD, 5000);
        world.player(0).set(UnitType.Resource.WOOD, 5000);
        Unit smith = world.createUnit(bench.types().get("unit-human-blacksmith"), 0, 10, 10);
        assertTrue(world.orderResearch(smith, "upgrade-sword1"),
                "the fixture could not start researching");
        for (int cycle = 0; cycle < 40; cycle++) {
            world.tick();
        }

        Unit loaded = find(reload(bench), "unit-human-blacksmith");
        assertEquals("upgrade-sword1", loaded.researching(),
                "the research and the gold spent on it were both lost");
        assertEquals(smith.progress(), loaded.progress());
    }

    @Test
    @DisplayName("an army remembers where it was going and what it was fighting")
    void unitsRememberTheirOrders() throws IOException {
        Bench bench = bench();
        World world = bench.world();
        Unit walker = world.createUnit(bench.types().get("unit-peasant"), 0, 5, 5);
        Unit fighter = world.createUnit(bench.types().get("unit-footman"), 0, 30, 20);
        Unit enemy = world.createUnit(bench.types().get("unit-grunt"), 1, 33, 20);

        assertTrue(world.orderMove(walker, 30, 30), "the fixture could not order a move");
        walker.enqueueOrder(new Unit.QueuedOrder(Unit.QueuedOrderKind.MOVE, 40, 40,
                null, null, null));
        assertTrue(world.orderAttack(fighter, enemy), "the fixture could not order an attack");
        for (int cycle = 0; cycle < 20; cycle++) {
            world.tick();
        }
        assertEquals(Unit.Order.MOVE, walker.order(), "the fixture's walker is not walking");

        World reloaded = reload(bench);
        Unit loadedWalker = find(reloaded, "unit-peasant");
        Unit loadedFighter = find(reloaded, "unit-footman");
        Unit loadedEnemy = find(reloaded, "unit-grunt");

        assertEquals(Unit.Order.MOVE, loadedWalker.order(), "the whole army stopped on load");
        assertEquals(30, loadedWalker.pathGoalX(), "the walker forgot where it was going");
        assertEquals(30, loadedWalker.pathGoalY());
        assertEquals(walker.heading(), loadedWalker.heading(), "every unit faced south again");
        assertEquals(1, loadedWalker.queuedOrders().size(), "the waypoint behind it was dropped");
        assertEquals(Unit.Order.ATTACK, loadedFighter.order());
        assertEquals(loadedEnemy, loadedFighter.target(), "the soldier forgot its target");
    }

    @Test
    @DisplayName("a projectile already in flight still lands after a reload")
    void anInFlightProjectileRoundTrips() throws IOException {
        Bench bench = bench();
        World world = bench.world();
        Unit catapult = world.createUnit(bench.types().get("unit-catapult"), 0, 10, 10);
        Unit target = world.createUnit(bench.types().get("unit-grunt"), 1, 16, 10);
        assertTrue(world.orderAttack(catapult, target),
                "the fixture could not order the catapult to fire");
        for (int cycle = 0; cycle < 300 && world.missiles().isEmpty(); cycle++) {
            world.tick();
        }
        assertEquals(1, world.missiles().size(),
                "the fixture saved before a projectile was in flight");
        catapult.setOrder(Unit.Order.STILL);
        catapult.setTarget(null);
        Missile originalMissile = world.missiles().get(0);

        World reloaded = reload(bench);
        assertEquals(1, reloaded.missiles().size(), "the flying projectile vanished on load");
        Missile loadedMissile = reloaded.missiles().get(0);
        assertEquals(originalMissile.type().ident(), loadedMissile.type().ident());
        assertEquals(originalMissile.x(), loadedMissile.x(),
                "the projectile restarted at a different position");
        assertEquals(originalMissile.y(), loadedMissile.y());
        assertEquals(originalMissile.fromX(), loadedMissile.fromX());
        assertEquals(originalMissile.fromY(), loadedMissile.fromY());
        assertEquals(originalMissile.toX(), loadedMissile.toX());
        assertEquals(originalMissile.toY(), loadedMissile.toY());
        assertEquals(originalMissile.travelled(), loadedMissile.travelled());
        assertEquals(originalMissile.delay(), loadedMissile.delay());
        assertEquals(originalMissile.damage(), loadedMissile.damage());
        assertEquals(originalMissile.battleNetRemaining(),
                loadedMissile.battleNetRemaining());
        assertEquals(originalMissile.battleNetPoolSlot(),
                loadedMissile.battleNetPoolSlot());
        Unit loadedTarget = find(reloaded, "unit-grunt");
        assertNotNull(loadedMissile.source(), "the restored projectile forgot its firer");
        assertEquals("unit-catapult", loadedMissile.source().type().ident());
        assertEquals(loadedTarget, loadedMissile.target(),
                "the restored projectile forgot the unit it was aimed at");
        assertEquals(target.hitPoints(), loadedTarget.hitPoints(),
                "the target's pre-impact health did not survive the save");
        assertEquals(world.battleNetRandomSeed(), reloaded.battleNetRandomSeed(),
                "the asynchronous random stream moved across the save");

        boolean landed = false;
        for (int cycle = 0; cycle < 100; cycle++) {
            world.tick();
            reloaded.tick();
            assertEquals(target.hitPoints(), loadedTarget.hitPoints(),
                    "the restored projectile dealt damage on a different cycle " + cycle
                            + "; original missiles=" + world.missiles().size()
                            + " restored=" + reloaded.missiles().size()
                            + " original async=" + world.battleNetRandomSeed()
                            + " restored async=" + reloaded.battleNetRandomSeed());
            assertEquals(world.missiles().size(), reloaded.missiles().size(),
                    "the restored projectile lived for a different number of actions");
            landed |= target.hitPoints() < target.type().hitPoints();
        }
        assertTrue(landed, "the projectile never landed in either game");
    }

    @Test
    @DisplayName("a damaged building is still on fire after a reload")
    void aBurningBuildingKeepsBurning() throws IOException {
        Bench bench = bench();
        Unit farm = bench.world().createUnit(bench.types().get("unit-farm"), 0, 10, 10);
        farm.setHitPoints(40);
        farm.setBurning(true);

        Unit loaded = find(reload(bench), "unit-farm");
        assertEquals(40, loaded.hitPoints(), "the fixture proves nothing if the damage is gone");
        assertTrue(loaded.isBurning(),
                "the smoke went out, so the player can no longer see which building needs a peasant");
    }

    @Test
    @DisplayName("a half-mined gold mine comes back holding what is left in it")
    void aDepositKeepsWhatIsLeftInIt() throws IOException {
        Bench bench = bench();
        Unit mine = bench.world().createUnit(bench.types().get("unit-gold-mine"), 15, 20, 20);
        mine.setResourcesHeld(12345);
        assertTrue(mine.type().hitPoints() != 12345,
                "the fixture must not let hit points stand in for the amount held");

        Unit loaded = find(reload(bench), "unit-gold-mine");
        assertEquals(12345, loaded.resourcesHeld(),
                "the mine refilled itself to the type's default across a save");
    }

    @Test
    @DisplayName("an hour of scouting is not undone by opening the save")
    void whatWasScoutedStaysScouted() throws IOException {
        Bench bench = bench();
        World world = bench.world();
        // Walk a scout across the map and take it away again, so the ground it
        // saw is explored but nothing is standing there to re-light it.
        Unit scout = world.createUnit(bench.types().get("unit-peasant"), 0, 5, 5);
        assertTrue(world.orderMove(scout, 40, 40));
        for (int cycle = 0; cycle < 900; cycle++) {
            world.tick();
        }
        world.kill(scout);
        for (int cycle = 0; cycle < 40; cycle++) {
            world.tick();
        }
        int explored = world.fog().exploredCount(0);
        assertTrue(explored > 200, "the fixture scouted almost nothing: " + explored);

        world.setSharedVision(0, 1, true);
        world.seenBuildings().remember(0, new net.chonkbase.chonkcraft.engine.map.SeenBuildings.Memory(
                bench.types().get("unit-pig-farm"), 1, 44, 44, 0, false, false, 1.0));

        World reloaded = reload(bench);
        assertEquals(explored, reloaded.fog().exploredCount(0),
                "the map went black again where nothing was standing to re-light it");
        assertTrue(reloaded.sharesVisionWith(0, 1), "shared vision was silently revoked");
        assertEquals(1, reloaded.seenBuildings().size(0),
                "the remembered enemy building vanished from the map");
    }

    @Test
    @DisplayName("a computer opponent resumes with the units it had asked for")
    void theComputerOpponentSurvives() throws IOException {
        Bench bench = bench();
        var ai = bench.world().enableAi(1);
        ai.need(bench.types().get("unit-grunt"), 4);
        ai.research("upgrade-battle-axe1");
        ai.restoreScriptPosition(7, 3);
        Unit first = bench.world().createUnit(bench.types().get("unit-grunt"), 1, 12, 12);
        Unit second = bench.world().createUnit(bench.types().get("unit-grunt"), 1, 13, 12);
        first.setBattleNetAiBehavior(2);
        first.setBattleNetAiHome(30, 31);
        AiForce force = ai.force(2);
        force.want(bench.types().get("unit-grunt"), 2);
        force.members().add(first);
        force.members().add(second);
        force.setState(AiForce.State.GOING_TO_RALLY);
        force.setGoal(30, 31);
        force.tickWaitOnRallyPoint();
        force.tickWaitOnRallyPoint();

        World reloaded = reload(bench);
        assertEquals(1, reloaded.ais().size(), "the computer opponent was not there at all");
        var loaded = reloaded.ais().get(1);
        assertNotNull(loaded);
        assertEquals(4, loaded.requests().get(bench.types().get("unit-grunt")),
                "the computer forgot the four grunts it wanted");
        assertTrue(loaded.researchRequests().contains("upgrade-battle-axe1"),
                "the computer forgot what it was researching towards");
        assertEquals(7, loaded.scriptIndex(),
                "the inherited personality restarted its build-order loop");
        assertEquals(3, loaded.scriptLoopIndex());
        assertEquals(1, loaded.forces().size(), "the assembled army vanished on load");
        AiForce loadedForce = loaded.force(2);
        assertEquals(AiForce.State.GOING_TO_RALLY, loadedForce.state(),
                "the computer forgot that its army had launched");
        assertEquals(2, loadedForce.members().size());
        assertEquals(2, loadedForce.members().getFirst().battleNetAiBehavior(),
                "the assault unit lost the AI behavior that owns its patrol");
        assertEquals(30, loadedForce.members().getFirst().battleNetAiHomeX());
        assertEquals(31, loadedForce.members().getFirst().battleNetAiHomeY());
        assertEquals(List.of("unit-grunt", "unit-grunt"), loadedForce.members().stream()
                .map(unit -> unit.type().ident()).toList());
        assertEquals(2, loadedForce.wanted().get(bench.types().get("unit-grunt")));
        assertEquals(30, loadedForce.goalX());
        assertEquals(31, loadedForce.goalY());
        assertEquals(58, loadedForce.waitOnRallyPoint());
    }

    @Test
    @DisplayName("a retail AI resumes at its next ai.bin instruction")
    void theRetailAiProgramCounterRoundTrips() throws IOException {
        Bench bench = bench();
        byte[] profile = new byte[128];
        profile[0] = 100;
        profile[100] = 120;
        profile[102] = 120;
        profile[104] = 0;
        profile[105] = BattleNetAiBytecode.OFF_WANTED_WORKERS;
        profile[106] = 1;
        profile[107] = 2;
        profile[108] = 2;
        profile[112] = 0;
        profile[113] = BattleNetAiBytecode.OFF_WANTED_WORKERS;
        profile[114] = 9;
        profile[115] = 2;
        profile[116] = 100;
        profile[120] = (byte) 0xff;

        var originalAi = bench.world().enableAi(1);
        originalAi.setBattleNetBuildProfile(profile, 0);
        bench.world().tick();
        assertEquals(1, originalAi.battleNetWantedWorkers());

        StringWriter out = new StringWriter();
        SaveGame.write(bench.world(), "test-map", null, 0, out);
        GameMap map = new GameMap(48, 48, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        map.recordLoadedTerrain();
        World reloaded = new World(map);
        reloaded.setUpgrades(load().data().upgrades().upgrades());
        reloaded.setUnitTypes(bench.types());
        reloaded.setMissileTypes(bench.missileTypes());
        var loadedAi = reloaded.enableAi(1);
        loadedAi.setBattleNetBuildProfile(profile, 0);
        LoadGame.apply(reloaded, out.toString(), bench.types());

        assertEquals(originalAi.battleNetWantedWorkers(),
                loadedAi.battleNetWantedWorkers());
        for (int cycle = 0; cycle < 5; cycle++) {
            bench.world().tick();
            reloaded.tick();
            assertEquals(originalAi.battleNetWantedWorkers(),
                    loadedAi.battleNetWantedWorkers(),
                    "the restored retail AI took a different instruction at step " + cycle);
        }
        assertEquals(9, loadedAi.battleNetWantedWorkers(),
                "the test never reached the instruction after the saved wait");
    }

    // ---------------------------------------------------------- the triggers

    private record Triggers(TriggerSystem system) {}

    private static Triggers arm(World world) {
        TriggerSystem system = new TriggerSystem(world, 0, List.of(
                new TriggerSystem.ProgramSpec("TRUE", "VICTORY"),
                new TriggerSystem.ProgramSpec("FALSE", "NOOP"),
                new TriggerSystem.ProgramSpec("FALSE", "NOOP")));
        return new Triggers(system);
    }

    @Test
    @DisplayName("a reinforcement trigger that has already fired does not fire again on load")
    void aFiredTriggerStaysFired() throws IOException {
        World world = new World(new GameMap(8, 8, new Tileset()));
        Triggers first = arm(world);
        assertEquals(3, first.system().triggerCount(), "the fixture did not arm three triggers");

        first.system().evaluate();
        assertEquals(TriggerSystem.Outcome.VICTORY, first.system().outcome(),
                "the fixture's one-shot trigger never fired");
        assertEquals(List.of(1, 2), first.system().armedTriggers(),
                "the trigger that fired is still counted as armed");

        StringWriter out = new StringWriter();
        SaveGame.write(world, "test-map", "human", 1, first.system().armedTriggers(), out);
        String script = out.toString();

        // Opening the save reloads the mission, and the mission script's own
        // AddTrigger calls arm all three again -- including the one whose
        // troops have already arrived.
        World reloaded = new World(new GameMap(8, 8, new Tileset()));
        Triggers second = arm(reloaded);
        assertEquals(3, second.system().triggerCount(),
                "the fixture must re-arm all three or it proves nothing");

        second.system().retainArmed(LoadGame.armedTriggers(script));
        assertEquals(2, second.system().triggerCount(), "the used trigger was not pruned");

        second.system().evaluate();
        assertEquals(TriggerSystem.Outcome.RUNNING, second.system().outcome(),
                "the already-used outcome trigger fired again after loading the save");
    }

    @Test
    @DisplayName("a save from before triggers were carried leaves the mission as its script armed it")
    void aSaveThatSaysNothingAboutTriggersChangesNothing() {
        World world = new World(new GameMap(8, 8, new Tileset()));
        Triggers triggers = arm(world);
        triggers.system().retainArmed(LoadGame.armedTriggers("GameCycle = 1\n"));
        assertEquals(3, triggers.system().triggerCount(),
                "an older save must not be read as saying every trigger had fired");
    }

    /** Two rescue-active slots that are not born enemies. */
    private static World rescueWorld() {
        Player[] players = new Player[Player.MAX];
        for (int index = 0; index < players.length; index++) {
            PudMap.PlayerType type = (index == 2 || index == 4)
                    ? PudMap.PlayerType.RESCUE_ACTIVE
                    : PudMap.PlayerType.NOBODY;
            players[index] = new Player(index, type, PudMap.Race.HUMAN);
        }
        return new World(new GameMap(8, 8, new Tileset()), players);
    }

    /** Each living unit as a comparable line. */
    private static List<String> describe(World world) {
        List<String> lines = new ArrayList<>();
        for (Unit unit : world.units()) {
            if (unit.type() == null || !unit.isAlive()) {
                continue;
            }
            lines.add(unit.type().ident() + " p" + unit.player()
                    + " at " + unit.tileX() + "," + unit.tileY()
                    + " hp" + unit.hitPoints());
        }
        java.util.Collections.sort(lines);
        return lines;
    }

    private static Unit find(World world, String ident) {
        return world.units().stream()
                .filter(unit -> unit.type() != null && ident.equals(unit.type().ident()))
                .findFirst().orElseThrow();
    }
}
