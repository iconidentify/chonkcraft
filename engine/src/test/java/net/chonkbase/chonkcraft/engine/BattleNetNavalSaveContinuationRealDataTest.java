package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.save.LoadGame;
import net.chonkbase.chonkcraft.engine.save.SaveGame;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Reloading idle ships and flyers must not change later movement or combat rolls. */
class BattleNetNavalSaveContinuationRealDataTest {
    private static final List<String> FLEET = List.of(
            "unit-balloon", "unit-zeppelin", "unit-dragon", "unit-gryphon-rider",
            "unit-human-transport", "unit-orc-transport", "unit-human-destroyer", "unit-orc-destroyer",
            "unit-battleship", "unit-ogre-juggernaught", "unit-human-submarine", "unit-orc-submarine",
            "unit-human-oil-tanker", "unit-orc-oil-tanker");

    @Test
    @DisplayName("reloading fourteen naval and flying types preserves their later movement and random stream")
    void reloadingFourteenNavalAndFlyingTypesPreservesTheirLaterMovementAndRandomStream() throws IOException {
        GameData data = data();
        List<Frame> expected = run(data, false, -1);
        for (int checkpoint : new int[] {100, 120, 300}) {
            compare(expected, run(data, false, checkpoint), checkpoint);
        }
    }

    @Test
    @DisplayName("idle ships and flyers keep a later commanded battle's damage unchanged after reload")
    void idleShipsAndFlyersKeepALaterCommandedBattlesDamageUnchangedAfterReload() throws IOException {
        GameData data = data();
        List<Frame> expected = run(data, true, -1);
        for (int checkpoint : new int[] {100, 120}) {
            compare(expected, run(data, true, checkpoint), checkpoint);
        }
    }

    private static void compare(List<Frame> expected, List<Frame> restored, int checkpoint) {
        assertEquals(800, expected.size(), "the reference must cover subsequent commands and repeated idle callbacks");
        assertEquals(expected.size(), restored.size(), "the resumed simulation must finish every reference visit");
        for (int cycle = 0; cycle < expected.size(); cycle++) {
            assertEquals(expected.get(cycle), restored.get(cycle),
                    "saving at " + checkpoint + " must preserve positions, damage and random draws at " + (cycle + 1));
        }
    }

    private static List<Frame> run(GameData data, boolean battle, int checkpoint) throws IOException {
        World world = world(data);
        assertEquals(14, FLEET.size(), "both races' four flyers and ten hull types must be exercised");
        for (int index = 0; index < FLEET.size(); index++) {
            place(data, world, FLEET.get(index), 0, 4 + 12 * (index % 4), 4 + 12 * (index / 4));
        }
        int originalFortressHp = 0;
        if (battle) {
            place(data, world, "unit-catapult", 0, 4, 52);
            originalFortressHp = place(data, world, "unit-fortress", 1, 18, 52).hitPoints();
        }
        int population = battle ? 16 : 14;
        assertEquals(population, world.units().size(), "every referee unit must be present before commands begin");
        List<Frame> frames = new ArrayList<>();
        for (int cycle = 1; cycle <= 800; cycle++) {
            if (cycle == 5 || cycle == 150) {
                CommandApplier commands = new CommandApplier(world, new ArrayList<>(data.unitTypes().types().values()));
                data.configureCommands(commands);
                for (Unit unit : world.units()) {
                    if (!unit.canMove()) continue;
                    GameCommand command = battle && cycle == 150 && unit.type().ident().equals("unit-catapult")
                            ? GameCommand.attack(0, unit.id(), find(world, "unit-fortress").id())
                            : GameCommand.move(0, unit.id(), unit.tileX() + 2, unit.tileY());
                    assertTrue(commands.apply(command), "the player's legal fleet or siege command must be accepted");
                }
            }
            world.tick();
            if (cycle == checkpoint) {
                StringWriter saved = new StringWriter();
                SaveGame.write(world, "naval-save-referee", "orc", 1, saved);
                world = world(data);
                LoadGame.apply(world, saved.toString(), data.unitTypes().types());
                assertEquals(population, world.units().size(), "reload must retain the complete fleet and battle");
            }
            frames.add(frame(world));
        }
        if (battle) assertTrue(find(world, "unit-fortress").hitPoints() < originalFortressHp,
                "the commanded catapult must actually reach and damage the fortress");
        return frames;
    }

    private static GameData data() {
        AssetSource source = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(source != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(source);
    }

    private static World world(GameData data) {
        GameMap map = new GameMap(64, 64, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(y < 48 ? TileFlag.WATER_ALLOWED : TileFlag.LAND_ALLOWED);
            }
        }
        map.recordLoadedTerrain();
        Player[] players = new Player[Player.MAX];
        for (int index = 0; index < players.length; index++) {
            players[index] = new Player(index,
                    index < 2 ? PudMap.PlayerType.PERSON : PudMap.PlayerType.NOBODY, PudMap.Race.ORC);
        }
        World world = new World(map, players);
        world.establishDiplomacy();
        data.configureWorld(world, PudMap.Tileset.FOREST);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        return world;
    }

    private static Unit place(GameData data, World world, String ident, int player, int x, int y) {
        Unit unit = world.createUnit(data.unitTypes().types().get(ident), player, x, y);
        assertNotNull(unit, "the retail " + ident + " must fit on its referee terrain");
        return unit;
    }

    private static Unit find(World world, String ident) {
        return world.units().stream().filter(unit -> unit.type().ident().equals(ident)).findFirst().orElseThrow();
    }

    private record Position(String type, int x, int y, int px, int py, int hp, Unit.Order order) {}
    private record Frame(int syncSeed, long syncDraws, int asyncSeed, long asyncDraws, List<Position> units) {}

    private static Frame frame(World world) {
        List<Position> positions = world.units().stream()
                .sorted(Comparator.comparing(unit -> unit.type().ident()))
                .map(unit -> new Position(unit.type().ident(), unit.tileX(), unit.tileY(),
                        unit.pixelX(), unit.pixelY(), unit.hitPoints(), unit.order())).toList();
        return new Frame(world.randomSeed(), world.randomDraws(),
                world.battleNetRandomSeed(), world.battleNetRandomDraws(), positions);
    }
}
