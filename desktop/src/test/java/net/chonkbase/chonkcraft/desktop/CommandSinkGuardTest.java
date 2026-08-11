package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.ui.UiLayout;
import net.chonkbase.chonkcraft.engine.ui.UnitButton;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Nothing the player clicks changes the world except by going through the sink.
 *
 * <p>A network game came apart on the first "Train Peasant". Eight actions in
 * {@code GameScreen.press} called {@code World} where they stood --
 * {@code orderTrain}, {@code orderUpgradeTo}, {@code orderResearch} and the
 * four cancels, plus the rally point and the critter's suicide click -- while
 * every other button in the same switch went through {@code commands.issue}.
 * Four hundred gold left one player's treasury and not the other's, and from
 * that cycle the two simulations were different games. Upstream sends all of
 * them: {@code SendCommandTrainUnit} and its siblings,
 * The game onward.
 *
 * <p>Single player could not show it. Calling the world and sending a command
 * that calls the world produce the same result on one machine, which is why
 * this survived and why the test below cannot be written as "does clicking
 * train a unit".
 *
 * <p>So the sink here records and does not apply -- which is exactly what a
 * networked sink does for the cycle or two before the other machines agree.
 * Any click that still reaches past it shows up as a change in the world with
 * no command behind it. The last test is the guard proper: it presses every
 * button the shipped data draws for a dozen unit types, at every level, and
 * requires the world to come out of it byte for byte identical.
 */
class CommandSinkGuardTest {

    private static final String MAP = "campaigns/human/level02h";
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static final int TILE = 32;

    private record Scene(GameScreen screen, World world, GameData data,
            CommandPanel panel, List<GameCommand> sent) {}

    private static GameData data() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    private static Scene scene() {
        GameData data = data();
        PudMap pud = data.campaignMap(MAP);
        Assumptions.assumeTrue(pud != null, "no campaign map available");
        var tileset = data.loadTileset(pud.tileset());
        World world = new World(GameMap.from(pud, tileset.tileset()), Player.from(pud));
        world.setUnitTypes(data.unitTypes().types());
        world.setUpgrades(data.upgrades().upgrades());
        world.setSpells(data.spells().spells());
        world.setMissileTypes(data.missiles().types());
        world.fog().revealAll(0);
        String tilesetName = pud.tileset() == PudMap.Tileset.FOREST
                ? "summer"
                : pud.tileset().name().toLowerCase(java.util.Locale.ROOT);

        CommandApplier applier = new CommandApplier(world,
                new ArrayList<>(data.unitTypes().types().values()));
        applier.setUpgrades(data.upgrades().upgrades().all().keySet());
        applier.setSpells(data.spells().spells().all().keySet());

        UiLayout.Layout layout = data.uiLayout("human", WIDTH, HEIGHT);
        CommandPanel panel = new CommandPanel(world, data, data.userInterface(tilesetName),
                data.upgrades().dependencies(), 0, tilesetName, "human",
                data.unitTypes().types(), layout);

        // Records and does not apply. A networked sink behaves this way for
        // the two cycles between issuing an order and the peers agreeing on
        // it, so this is not a contrived sink: it is the real one, paused.
        List<GameCommand> sent = new ArrayList<>();
        GameScreen screen = new GameScreen(world, data,
                new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_RGB), tileset.palette(),
                tilesetName, 0, WIDTH, HEIGHT,
                new net.chonkbase.chonkcraft.engine.sound.GameAudio(data.sounds()),
                null, panel, applier, sent::add, java.util.List.of(), "human");
        screen.setSize(WIDTH, HEIGHT);
        // No layout on the screen, so the playing field starts at the top left
        // corner and a tile's screen position is its map position. The command
        // panel keeps its real one, because that is what decides which button
        // is where.
        screen.setLayout((UiLayout.Layout) null);
        screen.setGameScale(1);
        for (UnitType.Resource resource : UnitType.Resource.values()) {
            world.player(0).set(resource, 100000);
        }
        return new Scene(screen, world, data, panel, sent);
    }

    /** Puts a building of the local player's somewhere it fits. */
    private static Unit place(Scene scene, String ident) {
        UnitType type = scene.data().unitTypes().types().get(ident);
        assertNotNull(type, "the shipped data has " + ident);
        for (int y = 2; y < 20; y++) {
            for (int x = 2; x < 18; x++) {
                Unit made = scene.world().createUnit(type, 0, x, y);
                if (made != null) {
                    return made;
                }
            }
        }
        return null;
    }

    /** The shipped button whose action is this, for a type that has it. */
    private static UnitButton button(Scene scene, String action, String value) {
        for (UnitButton candidate : scene.data()
                .userInterface("summer").buttons().all()) {
            if (action.equals(candidate.action())
                    && (value == null || value.equals(candidate.value()))) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Everything about the world a command could change.
     *
     * <p>Compared as a string rather than field by field so that a fault says
     * which unit and which field, and so that adding a unit or losing one is
     * caught as well as a changed number.
     */
    private static String snapshot(World world) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < world.players().length; index++) {
            Player player = world.player(index);
            if (player == null) {
                continue;
            }
            out.append("player ").append(index);
            for (UnitType.Resource resource : UnitType.Resource.values()) {
                out.append(' ').append(resource).append('=').append(player.get(resource));
            }
            out.append('\n');
        }
        List<String> lines = new ArrayList<>();
        for (Unit unit : world.unitsSnapshot()) {
            lines.add(unit.id() + " " + (unit.type() == null ? "?" : unit.type().ident())
                    + " p" + unit.player()
                    + " at " + unit.tileX() + "," + unit.tileY()
                    + " hp " + unit.hitPoints()
                    + " order " + unit.order()
                    + " producing " + name(unit.producing())
                    + " researching " + unit.researching()
                    + " upgradingTo " + name(unit.upgradingTo())
                    + " progress " + unit.progress()
                    + " rally " + unit.rallyX() + "," + unit.rallyY()
                    + " alive " + unit.isAlive());
        }
        java.util.Collections.sort(lines);
        lines.forEach(line -> out.append(line).append('\n'));
        return out.toString();
    }

    private static String name(UnitType type) {
        return type == null ? "-" : type.ident();
    }

    private static void rightClick(GameScreen screen, int x, int y) {
        MouseEvent event = new MouseEvent(screen, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), InputEvent.BUTTON3_DOWN_MASK,
                x, y, 1, false, MouseEvent.BUTTON3);
        for (var listener : screen.getMouseListeners()) {
            listener.mousePressed(event);
        }
    }

    private static void leftClick(GameScreen screen, int x, int y, int clicks) {
        MouseEvent event = new MouseEvent(screen, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK,
                x, y, clicks, false, MouseEvent.BUTTON1);
        for (var listener : screen.getMouseListeners()) {
            listener.mousePressed(event);
        }
    }

    @Test
    @DisplayName("training a peasant spends no gold until the command comes back")
    void trainingTravels() {
        Scene scene = scene();
        Unit hall = place(scene, "unit-town-hall");
        Assumptions.assumeTrue(hall != null, "nowhere to put a town hall");
        scene.screen().selectForTest(hall);
        hall.setSelected(true);

        UnitButton train = button(scene, "train-unit", "unit-peasant");
        assertNotNull(train, "the shipped data has a train-peasant button");

        int goldBefore = scene.world().player(0).get(UnitType.Resource.GOLD);
        scene.screen().press(train, false);

        assertEquals(1, scene.sent().stream()
                        .filter(c -> c.kind() == GameCommand.Kind.TRAIN).count(),
                "pressing train sent " + scene.sent() + ", and exactly one TRAIN was wanted");
        assertEquals(goldBefore, scene.world().player(0).get(UnitType.Resource.GOLD),
                "the gold was spent before the command was released: on a second machine"
                        + " it would not have been, and the two treasuries would disagree");
        assertEquals(null, hall.producing(),
                "the hall started training without waiting for the command");
    }

    @Test
    @DisplayName("every action that used to call the world now travels as a command")
    void allEightTravel() {
        Scene scene = scene();
        Unit hall = place(scene, "unit-town-hall");
        Unit barracks = place(scene, "unit-human-barracks");
        Unit smith = place(scene, "unit-human-blacksmith");
        Assumptions.assumeTrue(hall != null && barracks != null && smith != null,
                "nowhere to put a hall, a barracks and a blacksmith");

        String before = snapshot(scene.world());
        EnumSet<GameCommand.Kind> seen = EnumSet.noneOf(GameCommand.Kind.class);

        press(scene, hall, button(scene, "train-unit", "unit-peasant"));
        press(scene, hall, button(scene, "upgrade-to", "unit-keep"));
        press(scene, smith, firstResearch(scene, smith));
        press(scene, hall, button(scene, "cancel-train-unit", null));
        press(scene, hall, button(scene, "cancel-build", null));
        // One button, two meanings. Which one it sends is decided by what the
        // building is doing, so it is driven twice from two set-up states --
        // and the set-up is done on the world directly, which is what a
        // fixture is for.
        hall.setUpgradingTo(scene.data().unitTypes().types().get("unit-keep"));
        press(scene, hall, button(scene, "cancel-upgrade", null));
        hall.setUpgradingTo(null);
        hall.setResearching("upgrade-battle-axe1");
        press(scene, hall, button(scene, "cancel-upgrade", null));
        hall.setResearching(null);

        // The rally point: a right click on the ground with a producing
        // building selected.
        select(scene, barracks);
        rightClick(scene.screen(), (barracks.tileX() + 4) * TILE, barracks.tileY() * TILE);

        for (GameCommand command : scene.sent()) {
            seen.add(command.kind());
        }
        for (GameCommand.Kind wanted : List.of(
                GameCommand.Kind.TRAIN, GameCommand.Kind.UPGRADE_TO,
                GameCommand.Kind.RESEARCH, GameCommand.Kind.CANCEL_TRAIN,
                GameCommand.Kind.CANCEL_BUILD, GameCommand.Kind.CANCEL_UPGRADE_TO,
                GameCommand.Kind.CANCEL_RESEARCH, GameCommand.Kind.RALLY_POINT)) {
            assertTrue(seen.contains(wanted),
                    wanted + " never reached the sink; what did: " + seen);
        }

        // And none of it touched the world. The fixture's own two writes are
        // undone above, so the snapshot must match exactly.
        assertEquals(before, snapshot(scene.world()),
                "a command-panel action changed the world without going through the sink");
    }

    /** The blacksmith's first research button, whichever the data gives. */
    private static UnitButton firstResearch(Scene scene, Unit smith) {
        for (UnitButton candidate : scene.data().userInterface("summer").buttons().all()) {
            if ("research".equals(candidate.action())
                    && candidate.appliesTo(smith.type().ident())) {
                return candidate;
            }
        }
        return null;
    }

    private static void select(Scene scene, Unit unit) {
        for (Unit each : scene.world().unitsSnapshot()) {
            each.setSelected(each == unit);
        }
        scene.screen().selectForTest(unit);
    }

    private static void press(Scene scene, Unit on, UnitButton button) {
        if (button == null) {
            return;
        }
        select(scene, on);
        scene.panel().resetLevel();
        scene.screen().press(button, false);
    }

    @Test
    @DisplayName("clicking a sheep to death sends the order rather than doing it")
    void theSheepDiesOnEveryMachineOrOnNone() {
        Scene scene = scene();
        UnitType critter = null;
        for (UnitType type : scene.data().unitTypes().types().values()) {
            Object clicks = type.rawProperties().get("ClicksToExplode");
            if (clicks instanceof Number number && number.intValue() > 0) {
                critter = type;
                break;
            }
        }
        Assumptions.assumeTrue(critter != null, "no type in the data explodes when clicked");
        int needed = ((Number) critter.rawProperties().get("ClicksToExplode")).intValue();

        // A footman first, and the sheep beside him. A unit is only clickable
        // where the local player can see, and revealAll only marks ground as
        // explored: something has to be watching the square for the sheep to
        // be under the pointer at all.
        Unit watcher = place(scene, "unit-footman");
        Assumptions.assumeTrue(watcher != null, "nowhere to stand a footman");
        Unit sheep = null;
        for (int step = 1; step < 4 && sheep == null; step++) {
            sheep = scene.world().createUnit(critter, World.NEUTRAL_PLAYER,
                    watcher.tileX() + step, watcher.tileY());
        }
        Assumptions.assumeTrue(sheep != null, "nowhere to stand a sheep");
        Assumptions.assumeTrue(scene.world().isVisibleTo(0, sheep),
                "the footman cannot see the sheep, so it cannot be clicked");

        int screenX = sheep.tileX() * TILE + TILE / 2;
        int screenY = sheep.tileY() * TILE + TILE / 2;
        for (int click = 0; click < needed; click++) {
            leftClick(scene.screen(), screenX, screenY, 1);
        }

        assertEquals(1, scene.sent().stream()
                        .filter(c -> c.kind() == GameCommand.Kind.DISMISS).count(),
                needed + " clicks on a sheep sent " + scene.sent()
                        + ", and a critter blowing up is a unit dying: it has to happen"
                        + " on every machine or on none");
        assertTrue(sheep.isAlive(),
                "the sheep blew up on the machine that clicked it and nowhere else");
    }

    @Test
    @DisplayName("pressing every button the game draws leaves the world exactly as it was")
    void nothingInTheGridTouchesTheWorld() {
        Scene scene = scene();
        List<Unit> subjects = new ArrayList<>();
        for (String ident : List.of("unit-town-hall", "unit-human-barracks",
                "unit-human-blacksmith", "unit-elven-lumber-mill", "unit-human-shipyard",
                "unit-stables", "unit-peasant", "unit-footman", "unit-archer",
                "unit-mage", "unit-human-transport", "unit-farm")) {
            Unit made = place(scene, ident);
            if (made != null) {
                subjects.add(made);
            }
        }
        Assumptions.assumeTrue(subjects.size() >= 8,
                "only " + subjects.size() + " of the twelve types could be placed");

        // Four farms and the money, or the sweep proves nothing: World.orderTrain
        // refuses for want of supply before it ever charges anybody, so a town
        // hall with nothing behind it would sit unchanged whether the press went
        // through the sink or straight into the world.
        for (int extra = 0; extra < 4; extra++) {
            place(scene, "unit-farm");
        }
        scene.world().recalculateSupply();
        Assumptions.assumeTrue(scene.world().player(0).hasSupplyRoom(1),
                "the fixture has no supply room, so training would be refused anyway");

        String before = snapshot(scene.world());
        int pressed = 0;
        for (Unit unit : subjects) {
            select(scene, unit);
            for (UnitButton candidate : scene.data()
                    .userInterface("summer").buttons().all()) {
                if (!candidate.appliesTo(unit.type().ident())) {
                    continue;
                }
                scene.panel().resetLevel();
                scene.screen().press(candidate, false);
                scene.screen().press(candidate, true);
                pressed += 2;
            }
        }

        // Count as well as check: a sweep that found no buttons would pass
        // this test perfectly.
        assertTrue(pressed > 100,
                "only " + pressed + " button presses were made, which is too few for the"
                        + " sweep to mean anything");
        assertEquals(before, snapshot(scene.world()),
                "one of the " + pressed + " presses changed the world without going through"
                        + " the sink");
    }
}
