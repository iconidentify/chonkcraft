package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.MapRenderer;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.ui.UiLayout;
import net.chonkbase.chonkcraft.engine.ui.UnitButton;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the command grid draws after the selection changes.
 *
 * <p>A player reported two things about human mission one and they are one
 * fault. First: "clicking on buildings, they will have no options to build
 * anything, greyed out or other. No options for the barracks. Same with the
 * town hall." Both buildings were finished, both drew their portrait, name,
 * health bar and armour line correctly, and the nine command slots were
 * completely empty. Not dimmed -- an unaffordable button is dimmed, and the
 * bank held 1100 gold and 900 wood. Then, later in the same session: "right
 * now EVERYTHING I click on has the same ESC button and nothing else", with a
 * peasant, a town hall and a farm each showing exactly one icon.
 *
 * <p>Both are the button page outliving the selection it belonged to.
 * {@code ButtonSet.page} drops every button whose {@code level} is not the page
 * being asked for, and the shipped data settles what each page looks like: no
 * button in the game declares a town hall or a barracks on page one or page
 * two, so a grid stuck on a build page is empty, and exactly one button in the
 * game sits on page nine -- {@code scripts/buttons.legacy-declaration:47}, declared
 * {@code ForUnit = {"*"}} -- so a grid stuck on the cancel page is one ESC icon
 * for every unit alive.
 *
 * <p>Upstream has one function for this, {@code SelectionChanged}, and its own comment states the rule: "We
 * Changed out selection, anything pending buttonwise must be cleared". It
 * clears the status line, the costs, {@code CurrentButtonLevel}, the popup and
 * {@code CursorBuilding} together, and fifteen sites call it -- including
 * {@code CUnit::Remove}, which is what fires when a
 * unit dies, enters a mine or boards a transport. This implementation open-coded the page
 * reset at nine separate sites and cleared nothing else, so a build cursor or
 * an armed order survived every selection change, and a unit taken off the map
 * reached no reset at all.
 *
 * <p>Every check here paints the real screen and reads the grid that came out,
 * because "the panel is empty" is a fact about what was drawn. Measured
 * headless on mission one before the fix: with a move armed and the footman
 * killed, {@code grid=[cancel]}; with a farm left on the cursor by a peasant
 * selected minutes earlier, every later left click on the map went to
 * {@code placeBuilding} and answered "cannot build there", so nothing was ever
 * selected again and the grid never left the page it was stuck on. That is why
 * the ESC was on everything the player clicked rather than on one thing.
 */
class SelectionChangeTest {

    /** The mission the screenshots came from. */
    private static final String MAP = "campaigns/human/level01h";

    /**
     * The human side of mission one. Player nought is the orcs: the map places
     * four grunts for them and one peasant, three footmen, a farm and a town
     * hall for player one.
     */
    private static final int ME = 1;

    /**
     * Bigger than the 640 by 480 the chrome was drawn for, so that the
     * mission's own units are on screen with the camera where it starts. The
     * clicks below are worked out from tile positions, and a unit scrolled off
     * the right-hand edge cannot be clicked at all.
     */
    private static final int WIDTH = 1280;

    private static final int HEIGHT = 800;

    private static final int TILE = 32;

    private record Scene(GameScreen screen, CommandPanel commands, World world,
            GameData data, UiLayout.Layout layout) {}

    private static GameData data() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II assets configured. Set CHONKCRAFT_ASSET_PACK or"
                        + " -Dwc2.install.dir=/path/to/game.");
        return new GameData(assets);
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
        data.populate(world, pud);
        world.fog().revealAll(ME);
        String tilesetName = pud.tileset() == PudMap.Tileset.FOREST
                ? "summer"
                : pud.tileset().name().toLowerCase(java.util.Locale.ROOT);

        var rendered = new MapRenderer(tileset.tileset(), tileset.sheet())
                .render(world.map().width(), world.map().height(), world.map().tileCodes());
        BufferedImage terrain = rendered.toIndexedBufferedImage(tileset.palette());
        CommandApplier applier = new CommandApplier(world,
                new ArrayList<>(data.unitTypes().types().values()));

        UiLayout.Layout layout = data.uiLayout("human", WIDTH, HEIGHT);
        assertNotNull(layout, "the layout script must be readable for any of this to mean much");
        SidePanel panel = new SidePanel(world, data, ME, "human", tilesetName, layout);
        CommandPanel commands = new CommandPanel(world, data, data.userInterface(tilesetName),
                data.upgrades().dependencies(), ME, tilesetName, "human",
                data.unitTypes().types(), layout);
        GameScreen screen = new GameScreen(world, data, terrain, tileset.palette(),
                tilesetName, ME, WIDTH, HEIGHT, null, panel, commands, applier,
                CommandSink.local(applier), List.of(), "human");
        screen.setSize(WIDTH, HEIGHT);
        screen.setLayout(layout);
        screen.setGameScale(1);
        return new Scene(screen, commands, world, data, layout);
    }

    @Test
    @DisplayName("a footman killed under an aimed order stops being the only unit the panel knows")
    void aLostUnitLeavesNoLoneEscBehind() {
        Scene scene = scene();
        Unit footman = find(scene, "unit-footman");
        Unit peasant = find(scene, "unit-peasant");

        // The fixture must be able to click, or nothing below is a click.
        click(scene, peasant);
        assertTrue(peasant.selected(),
                "a click at the middle of tile " + peasant.tileX() + "," + peasant.tileY()
                        + " did not land on the peasant, so this test is not clicking on"
                        + " anything and cannot prove what a click does");

        scene.screen().selectForTest(footman);
        press(scene, footman, "move", null);
        assertEquals(List.of("cancel"), grid(scene),
                "the fixture must start on the cancel page or it proves nothing: arming a"
                        + " move should leave the one ESC icon and no other, which is what"
                        + " DoClicked_SelectTarget's \"level 9 is cancel-only\" means");

        // A grunt catches him on the way. Upstream unselects him on the
        // instant: LetUnitDie calls CUnit::Remove, and Remove
        // ends with UnSelectUnit and SelectionChanged.
        footman.setHitPoints(0);
        footman.setOrder(Unit.Order.DYING);

        List<String> afterHeDied = grid(scene);
        assertNotEquals(List.of("cancel"), afterHeDied,
                "the panel is still drawing one ESC icon, and the sidebar still his portrait"
                        + " and health bar, for a footman who is dead: nothing takes a lost"
                        + " unit out of the selection, so the cancel page it was left on"
                        + " belongs to nobody and never clears. Drew " + afterHeDied);

        click(scene, peasant);
        List<String> peasantGrid = grid(scene);
        assertNotEquals(List.of("cancel"), peasantGrid,
                "the peasant clicked afterwards was drawn as one ESC icon: " + peasantGrid);
        assertTrue(peasantGrid.contains("move") && peasantGrid.contains("harvest")
                        && peasantGrid.contains("repair"),
                "a peasant shows " + peasantGrid + " rather than its own commands;"
                        + " move, harvest and repair are declared for it in"
                        + " scripts/human/buttons.legacy-declaration and all three sit on page nought");
    }

    @Test
    @DisplayName("a farm left on the cursor does not outlive the peasant that held it")
    void aBuildCursorDoesNotOutliveItsWorker() {
        Scene scene = scene();
        Unit peasant = find(scene, "unit-peasant");
        Unit footman = find(scene, "unit-footman");
        int[] open = openTile(scene);
        int x = screenX(scene, open[0]);
        int y = screenY(scene, open[1]);

        // The footmen go in control group four. A digit key is how a player
        // changes the selection without the mouse, and without the mouse there
        // is no click for the placement to be cancelled by.
        scene.screen().selectForTest(footman);
        scene.screen().groupKey(4, GameScreen.GroupAction.DEFINE);

        scene.screen().selectForTest(peasant);
        press(scene, peasant, "button", "1");
        press(scene, peasant, "build", "unit-farm");
        assertTrue(grid(scene).contains("build:unit-farm"),
                "the fixture must have the first build page up or it proves nothing:"
                        + " drew " + grid(scene));
        assertNotEquals(GameCursors.Kind.POINT, scene.screen().kindAtForTest(x, y),
                "the fixture must have a farm on the cursor: the pointer over open ground"
                        + " at tile " + open[0] + "," + open[1] + " should be saying that a"
                        + " click there is already spoken for");

        scene.screen().groupKey(4, GameScreen.GroupAction.SELECT);
        assertTrue(footman.selected(), "the control group did not come back");

        // The page itself does come back, and has done since each of the nine
        // sites was given its own reset by hand. This is the control: it is the
        // half that was already right, and a failure here means the page went
        // wrong rather than the cursor.
        List<String> footmenGrid = grid(scene);
        assertTrue(footmenGrid.contains("move") && footmenGrid.contains("attack"),
                "the footmen recalled from group four were drawn as " + footmenGrid
                        + " rather than their own commands");

        assertEquals(GameCursors.Kind.POINT, scene.screen().kindAtForTest(x, y),
                "the farm is still on the cursor after the selection changed to the footmen."
                        + " SelectionChanged clears CursorBuilding in the same breath as"
                        + " CurrentButtonLevel (script_ui.cpp:1159-1171); clearing only the"
                        + " page leaves a build cursor belonging to a peasant nobody has"
                        + " selected for a while");

        click(scene, open[0], open[1]);
        assertFalse(footman.selected(),
                "the click on empty ground was swallowed by the farm still on the cursor:"
                        + " placeBuilding answered \"cannot build there\" and the footmen were"
                        + " neither deselected nor ordered anywhere. That is what \"EVERYTHING"
                        + " I click on has the same ESC button\" is -- every click is eaten,"
                        + " so the selection never changes, so the grid never leaves its page");
    }

    /**
     * The door the bug came through, closed.
     *
     * <p>Not a behaviour check, and it does not pretend to be one. The two
     * tests above say what the panel draws; this one says that there is exactly
     * one place in the screen where the selection changes, so that the next
     * person to add a tenth way of selecting something cannot forget to clear
     * the page, the build cursor and the armed order along with it. Nine sites
     * each clearing the page by hand is how a player came to click a town hall
     * and be shown nothing at all, and adding a tenth reset is not a fix for
     * that shape of fault.
     *
     * <p>The same idea as {@code NoInstallDirectoryTest} and
     * {@code extractor/IsolationTest}: a design rule the compiler cannot state,
     * enforced by reading the module's own source.
     */
    @Test
    @DisplayName("the selection changes in exactly one place in the screen")
    void theSelectionChangesInOnePlace() {
        // The package this rule reads, taken from a class that lives in it
        // rather than typed out. Spelled out, it said "net/chonkbase/chonkcraft"
        // for a while after the rename moved the sources to chonkcraft, and a
        // rule that cannot find the file it guards is a rule nobody is
        // keeping. Nothing reported it: the reactor stops at the engine's
        // failures and never reaches this module.
        Path source = Path.of("src", "main", "java")
                .resolve(GameScreen.class.getPackageName().replace('.', '/'))
                .resolve("GameScreen.java");
        assertTrue(Files.isRegularFile(source),
                "cannot find " + source.toAbsolutePath() + "; this test has to run from"
                        + " the desktop module directory");
        String text = read(source);
        assertTrue(text.length() > 100_000,
                "only " + text.length() + " characters were scanned, which is not GameScreen");

        String signature = "private void selectionChanged(Unit nowShown) {";
        int opens = text.indexOf(signature);
        assertTrue(opens >= 0,
                "GameScreen has no selectionChanged: this port's SelectionChanged"
                        + " (src/ui/script_ui.cpp:1159) is the one place the selection"
                        + " is allowed to change");
        int closes = text.indexOf("\n    }\n", opens);
        assertTrue(closes > opens, "selectionChanged does not close where expected");

        // "selected =", but not "==", not "onlySelected =", and not the sound
        // event named "selected".
        Matcher matcher = Pattern.compile("(?<![A-Za-z0-9_])selected\\s*=(?!=)").matcher(text);
        List<String> outside = new ArrayList<>();
        int found = 0;
        while (matcher.find()) {
            found++;
            if (matcher.start() < opens || matcher.start() > closes) {
                outside.add("line " + lineOf(text, matcher.start()));
            }
        }
        assertEquals(1, found,
                "the selection is assigned " + found + " times in GameScreen. Upstream has"
                        + " one SelectionChanged and fifteen callers; this port had nine"
                        + " assignments, each clearing the button page by hand and nothing"
                        + " else, and the build cursor and the armed order outlived every"
                        + " one of them. Route the new one through selectionChanged");
        assertTrue(outside.isEmpty(),
                "the selection is assigned outside selectionChanged at " + outside
                        + ", so the page, the build cursor and the armed order that belong"
                        + " to the old selection are not cleared with it");
    }

    /** What the command grid would draw, as the game's own action names. */
    private static List<String> grid(Scene scene) {
        BufferedImage frame = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = frame.createGraphics();
        scene.screen().paint(g2);
        g2.dispose();
        List<String> drawn = new ArrayList<>();
        for (String slot : scene.commands().describeForTest()) {
            if (!"-".equals(slot)) {
                drawn.add(slot);
            }
        }
        return drawn;
    }

    /**
     * Presses the real button the shipped scripts declare for this unit on the
     * page the panel is showing.
     *
     * <p>The last match rather than the first, because that is how
     * {@code ButtonSet.page} settles two buttons claiming one slot, and pressing
     * a button the panel would not have drawn proves nothing about the panel.
     */
    private static void press(Scene scene, Unit unit, String action, String value) {
        UnitButton found = null;
        for (UnitButton button : scene.data().userInterface("summer").buttons().all()) {
            if (action.equals(button.action())
                    && Objects.equals(value, button.value())
                    && button.appliesTo(unit.type().ident())
                    && button.level() == scene.commands().level()) {
                found = button;
            }
        }
        assertNotNull(found, "the shipped scripts declare no " + action
                + (value == null ? "" : " " + value) + " button for " + unit.type().ident()
                + " on page " + scene.commands().level());
        scene.screen().press(found, false);
    }

    /** A left press and release where a unit is standing, through the screen's own listener. */
    private static void click(Scene scene, Unit unit) {
        click(scene, unit.tileX(), unit.tileY());
    }

    private static void click(Scene scene, int tileX, int tileY) {
        int x = screenX(scene, tileX);
        int y = screenY(scene, tileY);
        MouseEvent pressed = new MouseEvent(scene.screen(), MouseEvent.MOUSE_PRESSED, 0L, 0,
                x, y, 1, false, MouseEvent.BUTTON1);
        MouseEvent released = new MouseEvent(scene.screen(), MouseEvent.MOUSE_RELEASED, 0L, 0,
                x, y, 1, false, MouseEvent.BUTTON1);
        MouseListener[] listeners = scene.screen().getMouseListeners();
        assertTrue(listeners.length > 0, "the screen has no mouse listener to click");
        for (MouseListener listener : listeners) {
            listener.mousePressed(pressed);
            listener.mouseReleased(released);
        }
    }

    @Test
    @DisplayName("left-clicking the minimap moves only the camera; right-clicking gives the order")
    void minimapButtonsDoOneJobEach() {
        Scene scene = scene();
        Unit footman = find(scene, "unit-footman");
        scene.screen().selectForTest(footman);

        Unit.Order orderBefore = footman.order();
        int targetXBefore = footman.orderTargetX();
        int targetYBefore = footman.orderTargetY();
        int cameraXBefore = scene.screen().cameraX();
        int cameraYBefore = scene.screen().cameraY();

        minimapClick(scene, MouseEvent.BUTTON1);

        assertEquals(orderBefore, footman.order(),
                "a left click on the minimap gave the selected footman an order");
        assertEquals(targetXBefore, footman.orderTargetX());
        assertEquals(targetYBefore, footman.orderTargetY());
        assertTrue(cameraXBefore != scene.screen().cameraX()
                        || cameraYBefore != scene.screen().cameraY(),
                "a left click near the far corner of the minimap did not move the camera");

        int cameraXAfterLeft = scene.screen().cameraX();
        int cameraYAfterLeft = scene.screen().cameraY();
        minimapClick(scene, MouseEvent.BUTTON3);

        assertTrue(orderBefore != footman.order()
                        || targetXBefore != footman.orderTargetX()
                        || targetYBefore != footman.orderTargetY(),
                "a right click on the minimap did not give the selected footman an order");
        assertEquals(cameraXAfterLeft, scene.screen().cameraX(),
                "a right click on the minimap moved the camera");
        assertEquals(cameraYAfterLeft, scene.screen().cameraY(),
                "a right click on the minimap moved the camera");
    }

    /** Clicks near the far corner of the real minimap through the screen listener. */
    private static void minimapClick(Scene scene, int button) {
        Rectangle area = SidePanel.minimapArea();
        double scale = scene.screen().interfaceScale();
        int x = (int) Math.floor((area.x + area.width - 2) * scale);
        int y = (int) Math.floor((area.y + area.height - 2) * scale);
        int modifiers = button == MouseEvent.BUTTON3
                ? InputEvent.BUTTON3_DOWN_MASK
                : InputEvent.BUTTON1_DOWN_MASK;
        MouseEvent pressed = new MouseEvent(scene.screen(), MouseEvent.MOUSE_PRESSED, 0L,
                modifiers, x, y, 1, false, button);
        MouseEvent released = new MouseEvent(scene.screen(), MouseEvent.MOUSE_RELEASED, 0L,
                0, x, y, 1, false, button);
        for (MouseListener listener : scene.screen().getMouseListeners()) {
            listener.mousePressed(pressed);
            listener.mouseReleased(released);
        }
    }

    /** The camera starts at the origin and nothing here moves it. */
    private static int screenX(Scene scene, int tileX) {
        return scene.layout().mapArea().x() + tileX * TILE + TILE / 2;
    }

    private static int screenY(Scene scene, int tileY) {
        return scene.layout().mapArea().y() + tileY * TILE + TILE / 2;
    }

    /** A square on screen with nothing standing on it and no wall. */
    private static int[] openTile(Scene scene) {
        for (int y = 1; y < 16; y++) {
            for (int x = 1; x < 24; x++) {
                if (scene.world().unitAt(x, y) == null
                        && !scene.world().map().field(x, y).isWall()) {
                    return new int[] {x, y};
                }
            }
        }
        Assumptions.assumeTrue(false, "no open square in view on " + MAP);
        return null;
    }

    private static Unit find(Scene scene, String ident) {
        for (Unit unit : scene.world().unitsSnapshot()) {
            if (unit.player() == ME && unit.isAlive() && unit.isOnMap()
                    && unit.type() != null && ident.equals(unit.type().ident())) {
                return unit;
            }
        }
        Assumptions.assumeTrue(false, MAP + " places no " + ident + " for player " + ME);
        return null;
    }

    private static int lineOf(String text, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /**
     * Control-clicking a farm gives you that farm, not every farm on screen.
     *
     * <p>Both upstream selectors stop before their sweep when the clicked
     * type lacks {@code SelectableByRectangle}: {@code SelectUnitsByType}
     * keeps just the clicked unit {@code ToggleUnitsByType} leaves the selection as it stands
     * ({@code :433}). The implementation honoured the flag for a band drag and not for
     * the control click, and 87 of the 143 shipped types lack it -- every
     * building in both tech trees among them.
     */
    @Test
    @DisplayName("control-clicking a farm selects that farm and not its kind")
    void controlClickingAFarmSelectsThatFarmAlone() {
        Scene scene = scene();
        Unit farm = find(scene, "unit-farm");
        Unit second = placeSecondFarm(scene, farm);

        controlClick(scene, farm, false);

        List<Unit> chosen = selectedUnits(scene);
        assertEquals(List.of(farm), chosen,
                "a control click on the farm at " + farm.tileX() + "," + farm.tileY()
                        + " selected " + chosen.size() + " units with a second farm at "
                        + second.tileX() + "," + second.tileY() + " on screen."
                        + " SelectUnitsByType stops at the clicked unit for a type"
                        + " without SelectableByRectangle, and no building carries it");
    }

    /** The control: a footman still gathers its kind, so the gate is the flag. */
    @Test
    @DisplayName("control-clicking a footman still gathers every footman on screen")
    void controlClickingAFootmanStillGathersItsKind() {
        Scene scene = scene();
        Unit footman = find(scene, "unit-footman");

        controlClick(scene, footman, false);

        List<Unit> chosen = selectedUnits(scene);
        assertTrue(chosen.size() > 1,
                "a control click on a footman selected " + chosen.size()
                        + "; the first human mission places three footmen in view, so a"
                        + " gather that finds one means the flag gate swallowed the sweep");
        for (Unit unit : chosen) {
            assertEquals(footman.type(), unit.type(),
                    "the gather collected a " + unit.type().ident() + " alongside footmen");
        }
    }

    /** The toggle form: upstream returns 0 and the selection does not move. */
    @Test
    @DisplayName("shift-control-clicking a farm leaves the selection as it stands")
    void shiftControlClickOnAFarmLeavesTheSelectionAlone() {
        Scene scene = scene();
        Unit footman = find(scene, "unit-footman");
        Unit farm = find(scene, "unit-farm");
        controlClick(scene, footman, false);
        List<Unit> before = selectedUnits(scene);
        assertTrue(before.size() > 1, "the fixture never gathered the footmen,"
                + " so leaving the selection alone would be indistinguishable from a bug");

        controlClick(scene, farm, true);

        assertEquals(before, selectedUnits(scene),
                "ToggleUnitsByType answers 0 for a type without SelectableByRectangle --"
                        + " the selection is left as it stands, and it moved");
    }

    /** A control click, optionally with shift, through the screen's own listener. */
    private static void controlClick(Scene scene, Unit unit, boolean shift) {
        int x = screenX(scene, unit.tileX());
        int y = screenY(scene, unit.tileY());
        int modifiers = MouseEvent.CTRL_DOWN_MASK | (shift ? MouseEvent.SHIFT_DOWN_MASK : 0);
        MouseEvent pressed = new MouseEvent(scene.screen(), MouseEvent.MOUSE_PRESSED, 0L,
                modifiers, x, y, 1, false, MouseEvent.BUTTON1);
        MouseEvent released = new MouseEvent(scene.screen(), MouseEvent.MOUSE_RELEASED, 0L,
                modifiers, x, y, 1, false, MouseEvent.BUTTON1);
        MouseListener[] listeners = scene.screen().getMouseListeners();
        assertTrue(listeners.length > 0, "the screen has no mouse listener to click");
        for (MouseListener listener : listeners) {
            listener.mousePressed(pressed);
            listener.mouseReleased(released);
        }
    }

    /** Every selected unit of the local player, in roster order. */
    private static List<Unit> selectedUnits(Scene scene) {
        List<Unit> chosen = new ArrayList<>();
        for (Unit unit : scene.world().unitsSnapshot()) {
            if (unit.selected() && unit.isAlive() && unit.player() == ME) {
                chosen.add(unit);
            }
        }
        return chosen;
    }

    /**
     * The status line says its piece in the middle of its strip.
     *
     * <p>Design direction from play, with a screenshot: "the status text
     * should be perfectly centered on the area it's supposed to be on."
     * Upstream draws from {@code TextX}, which reads fine at 640 wide where
     * the strip is short; on a wide window the strip runs the whole bottom
     * of the screen and a five-letter order name huddled in its far corner
     * read as misplaced. The lit text's centre must sit on the strip's
     * centre, give or take a character of rounding.
     */
    @Test
    @DisplayName("a rectangle over twelve units reports nine selected")
    void aRectangleOverTwelveUnitsReportsNineSelected() {
        Scene scene = scene();
        var type = scene.data.unitTypes().types().get("unit-footman");
        Assumptions.assumeTrue(type != null, "no footman type");
        java.util.List<Unit> group = new java.util.ArrayList<>();
        for (int i = 0; i < 16 && group.size() < 12; i++) {
            Unit made = scene.world.createUnit(type, ME, 2 + (i % 6), 2 + (i / 6));
            if (made != null) {
                group.add(made);
            }
        }
        Assumptions.assumeTrue(group.size() >= 12, "could not stand twelve footmen");
        scene.screen.selectWithinForTest(new Rectangle(0, 0, WIDTH, HEIGHT));
        assertEquals(9, scene.screen.selectedIdsForTest().size(),
                "more than nine units stayed selected");
        assertEquals("9 selected.", scene.screen.status(),
                "the status line counted units the nine-slot packet cannot hold");
    }

    @Test
    @DisplayName("a tenth shift-click does not steal the sidebar")
    void aTenthShiftClickDoesNotStealTheSidebar() {
        Scene scene = scene();
        var type = scene.data.unitTypes().types().get("unit-footman");
        Assumptions.assumeTrue(type != null, "no footman type");
        java.util.List<Unit> group = new java.util.ArrayList<>();
        for (int i = 0; i < 16 && group.size() < 10; i++) {
            Unit made = scene.world.createUnit(type, ME, 2 + (i % 5), 2 + (i / 5));
            if (made != null) {
                group.add(made);
            }
        }
        Assumptions.assumeTrue(group.size() >= 10, "could not stand ten footmen");
        scene.screen.selectForTest(group.subList(0, 9));
        Unit first = group.get(0);
        Unit tenth = group.get(9);
        scene.screen.shiftSelectForTest(tenth);
        assertEquals(9, scene.screen.selectedIdsForTest().size(),
                "the tenth unit joined the selection");
        assertTrue(first.selected(), "the original selection was dropped");
        assertFalse(tenth.selected(), "the rejected tenth stayed selected");
        assertEquals(first, scene.screen.selectedForTest(),
                "the sidebar switched to the unit the packet refused");
    }

    @Test
    @DisplayName("an armed order's name sits centred on the status strip")
    void theStatusLineIsCentredOnItsStrip() {
        Scene scene = scene();
        Unit footman = find(scene, "unit-footman");
        scene.screen().selectForTest(footman);
        press(scene, footman, "attack", null);
        Assumptions.assumeTrue(!scene.screen().status().isEmpty(),
                "arming attack set no status, so there is no text to centre");

        BufferedImage frame = new BufferedImage(WIDTH, HEIGHT,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = frame.createGraphics();
        scene.screen().paint(g2);
        g2.dispose();

        int stripLeft = scene.layout().statusLineX();
        int stripWidth = scene.layout().statusLineWidth();
        int minX = Integer.MAX_VALUE;
        int maxX = -1;
        for (int y = HEIGHT - 16; y < HEIGHT; y++) {
            for (int x = stripLeft; x < stripLeft + stripWidth && x < WIDTH; x++) {
                int rgb = frame.getRGB(x, y);
                if (((rgb >> 16) & 0xFF) > 150 && ((rgb >> 8) & 0xFF) > 150
                        && (rgb & 0xFF) > 130) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                }
            }
        }
        assertTrue(maxX >= 0, "no lettering on the status strip at all, so there"
                + " is nothing to measure");
        int textCentre = (minX + maxX) / 2;
        int stripCentre = stripLeft + stripWidth / 2;
        assertTrue(Math.abs(textCentre - stripCentre) <= 8,
                "the status text is centred on " + textCentre + " where the strip's"
                        + " centre is " + stripCentre + ": the line still starts at"
                        + " TextX instead of sitting on its strip");
    }

    /**
     * A second farm inside the opening view, so a gather would have something
     * to find. Without it the mission's single farm cannot tell "the clicked
     * farm" from "every farm on screen" and the test above proves nothing.
     */
    private static Unit placeSecondFarm(Scene scene, Unit first) {
        for (int y = 1; y < 13; y++) {
            for (int x = 1; x < 20; x++) {
                Unit placed = scene.world().createUnit(first.type(), ME, x, y);
                if (placed != null) {
                    return placed;
                }
            }
        }
        Assumptions.assumeTrue(false, "nowhere on the opening screen to stand a second farm");
        return null;
    }
}
