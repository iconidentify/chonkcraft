package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What happens when a player presses a command they cannot pay for.
 *
 * <p>A player reported one sentence with three faults in it: "I clicked a
 * dimmed Build Farm with 300 gold, got the placement cursor, placed it, the
 * peasant answered with its ready sound, and nothing was built." The
 * affordability check reached {@code CommandPanel.affordable} and the drawing,
 * and stopped there -- nothing gated the click, nothing gated the hotkey, the
 * game gave no reason, and the worker acknowledged an order the world had
 * already decided not to take.
 *
 * <p>Upstream gates it at the click. {@code CButtonPanel::DoClicked_Build}
 * The game is one condition -- "if
 * (!Selected[0]-&gt;Player-&gt;CheckUnitType(type))" -- so an unaffordable
 * building never reaches {@code CursorBuilding} at all;
 * {@code DoClicked_Train} ({@code :1309}) asks {@code CheckLimits} and then
 * {@code CheckUnitType}, in that order; {@code DoClicked_Research}
 * ({@code :1344}) asks {@code CheckCosts}. Every one of those checks notifies:
 * {@code CPlayer::CheckCosts}
 * calls {@code Notify(_("Not enough %s...%s more %s."))} for each resource
 * that is short. Note that upstream does <em>not</em> dim the icon --
 * {@code IsButtonAllowed} never consults cost -- so the dimming is this implementation's
 * addition and what was missing was everything behind it.
 *
 * <p>The sentences are Warcraft II's own, recovered from the installation
 * rather than written here: slots 438 to 441 of the string table in entry 1 of
 * {@code strdat.war}, which this implementation already reads as {@code NameTable} and
 * publishes as {@code GameData.names}. Nothing below hardcodes the English --
 * each check asks the same table the screen asks and compares.
 *
 * <p>Measured on human mission one before the fix, with the bank set to 300
 * against a farm costing 500 gold and 250 lumber: {@code affordable} answered
 * false and the icon was dimmed, {@code press} set {@code placing=unit-farm}
 * anyway, the cursor over open ground read {@code ACT}, the click left the
 * status line reading "building Farm", the peasant's acknowledgement played,
 * and the roster went from twelve units to twelve.
 */
class UnaffordableButtonTest {

    /** The mission the report came from: a peasant, a town hall, open ground. */
    private static final String MAP = "campaigns/human/level01h";

    /** Player nought is the orcs; player one is the human side. */
    private static final int ME = 1;

    private static final int WIDTH = 1280;

    private static final int HEIGHT = 800;

    private static final int TILE = 32;

    /** "Not enough food...build more farms." */
    private static final int NOT_ENOUGH_FOOD = 438;

    /** "Not enough gold...mine more gold." */
    private static final int NOT_ENOUGH_GOLD = 439;

    private record Scene(GameScreen screen, CommandPanel commands, World world,
            GameData data, UiLayout.Layout layout) {}

    private static GameData data() {
        AssetSource source = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(source != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(source);
    }

    private static Scene scene() {
        GameData data = data();
        PudMap pud = data.campaignMap(MAP);
        Assumptions.assumeTrue(pud != null, "no campaign map available");
        var tileset = data.loadTileset(pud.tileset());
        World world = new World(GameMap.from(pud, tileset.tileset()), Player.from(pud));
        data.configureWorld(world, pud);
        data.populate(world, pud);
        world.fog().revealAll(ME);

        var rendered = new MapRenderer(tileset.tileset(), tileset.sheet())
                .render(world.map().width(), world.map().height(), world.map().tileCodes());
        BufferedImage terrain = rendered.toIndexedBufferedImage(tileset.palette());
        CommandApplier applier = new CommandApplier(world,
                new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(applier);

        UiLayout.Layout layout = data.uiLayout("human", WIDTH, HEIGHT);
        assertNotNull(layout, "the layout script must be readable for any of this to mean much");
        SidePanel panel = new SidePanel(world, data, ME, "human", "summer", layout);
        CommandPanel commands = new CommandPanel(world, data, data.userInterface("summer"),
                data.upgrades().dependencies(), ME, "summer", "human",
                data.unitTypes().types(), layout);
        GameScreen screen = new GameScreen(world, data, terrain, tileset.palette(),
                "summer", ME, WIDTH, HEIGHT, null, panel, commands, applier,
                CommandSink.local(applier), List.of(), "human");
        screen.setSize(WIDTH, HEIGHT);
        screen.setLayout(layout);
        screen.setGameScale(1);
        return new Scene(screen, commands, world, data, layout);
    }

    @Test
    @DisplayName("a farm nobody can pay for never reaches the cursor")
    void anUnaffordableBuildingIsNotArmed() {
        Scene scene = scene();
        Unit peasant = find(scene, "unit-peasant");
        UnitType farm = scene.data().unitTypes().types().get("unit-farm");
        assertNotNull(farm, "the shipped scripts must define unit-farm");

        int price = farm.costs().getOrDefault(UnitType.Resource.GOLD, 0);
        assertTrue(price > 300,
                "the fixture must be unable to afford the farm or it proves nothing:"
                        + " a farm costs " + price + " gold and the bank is about to hold 300");
        broke(scene, 300);

        scene.screen().selectForTest(peasant);
        press(scene, peasant, "button", "1");
        assertTrue(grid(scene).contains("build:unit-farm"),
                "the fixture must have the build page up: drew " + grid(scene));
        assertFalse(affordable(scene, button(scene, peasant, "build", "unit-farm")),
                "the fixture must have the icon dimmed, or this is not the button the"
                        + " player pressed");

        press(scene, peasant, "build", "unit-farm");

        assertNull(scene.screen().placingForTest(),
                "a Build Farm the player cannot pay for put the farm on the cursor anyway."
                        + " DoClicked_Build only sets CursorBuilding inside \"if"
                        + " (!Selected[0]->Player->CheckUnitType(type))\", so upstream never"
                        + " arms one. The dimming reached CommandPanel.affordable and the"
                        + " drawing and nothing else, so the click, the hotkey and every"
                        + " other way in went through unchecked");
        assertEquals(recovered(scene, NOT_ENOUGH_GOLD), scene.screen().status(),
                "the game said nothing about why the farm was refused. Warcraft II prints"
                        + " its own sentence and it is in the data the player installed");
    }

    @Test
    @DisplayName("the refusal is the sentence Warcraft II prints, out of the 1995 string table")
    void theWordingComesOutOfTheGameOwnData() {
        Scene scene = scene();
        String gold = recovered(scene, NOT_ENOUGH_GOLD);
        String food = recovered(scene, NOT_ENOUGH_FOOD);
        // Not a check on the fix; a check that the comparisons above and below
        // are comparing against something. Two empty strings are equal, and a
        // table that could not be read would make every assertion in this class
        // pass without the screen saying a word.
        assertTrue(gold.startsWith("Not enough") && gold.contains("gold"),
                "slot " + NOT_ENOUGH_GOLD + " of the game's string table reads \"" + gold
                        + "\", which is not the sentence this test thinks it is");
        assertTrue(food.startsWith("Not enough") && food.contains("farms"),
                "slot " + NOT_ENOUGH_FOOD + " of the game's string table reads \"" + food
                        + "\", which is not the sentence this test thinks it is");
    }

    @Test
    @DisplayName("with the money in the bank the same press does arm the farm")
    void theCheckDoesNotRefuseEverything() {
        // The control. A gate that refuses every build would pass the test
        // above perfectly and make the game unplayable, so the same button on
        // the same peasant with a full bank has to go through.
        Scene scene = scene();
        Unit peasant = find(scene, "unit-peasant");
        scene.screen().selectForTest(peasant);
        press(scene, peasant, "button", "1");
        press(scene, peasant, "build", "unit-farm");

        assertNotNull(scene.screen().placingForTest(),
                "a Build Farm pressed with " + scene.world().player(ME)
                        .get(UnitType.Resource.GOLD) + " gold in the bank did not arm");
        assertEquals("unit-farm", scene.screen().placingForTest().ident(),
                "something other than the farm ended up on the cursor");
    }

    @Test
    @DisplayName("a peasant does not answer an order the game refused")
    void theWorkerDoesNotAcknowledgeWhatWasNeverOrdered() {
        // The money can still run out between arming the farm and choosing
        // where it goes -- press Build Farm with 600 gold, train a footman,
        // then click the ground. World.orderBuild refuses at Player.pay and
        // the order is dropped, and this screen had already said "building
        // Farm", played the placement chime and had the peasant answer.
        Scene scene = scene();
        Unit peasant = find(scene, "unit-peasant");
        scene.screen().selectForTest(peasant);
        press(scene, peasant, "button", "1");
        press(scene, peasant, "build", "unit-farm");
        assertNotNull(scene.screen().placingForTest(),
                "the fixture must have a farm on the cursor or it proves nothing");

        broke(scene, 0);
        int[] open = openTile(scene);
        long spokenBefore = scene.screen().soundChoicesForTest();
        int unitsBefore = living(scene);

        click(scene, open[0], open[1]);
        scene.world().tick();

        assertEquals(spokenBefore, scene.screen().soundChoicesForTest(),
                "the peasant acknowledged an order the world refused. That is the worst"
                        + " part of what was reported: the game affirmatively said it was"
                        + " doing something it had already decided not to do");
        assertEquals(unitsBefore, living(scene),
                "something was built with an empty bank, so this fixture is not testing"
                        + " a refusal at all");
        assertEquals(Unit.Order.STILL, peasant.order(),
                "the peasant took a build order it could not pay for");
        assertEquals(recovered(scene, NOT_ENOUGH_GOLD), scene.screen().status(),
                "the status line read \"" + scene.screen().status() + "\" over a farm that"
                        + " was never begun");
    }

    @Test
    @DisplayName("training with the farms full says to build more farms")
    void trainingWithNoFoodSaysSo() {
        Scene scene = scene();
        Unit hall = find(scene, "unit-town-hall");
        UnitType peasant = scene.data().unitTypes().types().get("unit-peasant");
        assertTrue(peasant.demand() > 0,
                "a peasant that eats nothing cannot test the food check");

        // The farm burns down, which is how a player loses food in a game.
        // Mission one gives the human side one farm at four supply and a town
        // hall at one, against four units eating one each, so losing the farm
        // is the difference between room and none -- which is the condition
        // CheckLimits tests, "Demand + type.Demand > Supply".
        Unit farm = find(scene, "unit-farm");
        farm.setHitPoints(0);
        farm.setOrder(Unit.Order.DYING);
        scene.world().recalculateSupply();
        var player = scene.world().player(ME);
        assertFalse(player.hasSupplyRoom(peasant.demand()),
                "the fixture must have no room for another peasant or it proves nothing:"
                        + " " + player.demand() + " eaten of " + player.supply() + " grown");

        scene.screen().selectForTest(hall);
        press(scene, hall, "train-unit", "unit-peasant");

        assertEquals(recovered(scene, NOT_ENOUGH_FOOD), scene.screen().status(),
                "training with every farm full said \"" + scene.screen().status() + "\"."
                        + " DoClicked_Train asks CheckLimits before it asks about money"
                        + " (botpanel.cpp:1309) and CheckLimits notifies on its way out");
        scene.world().tick();
        assertNull(hall.producing(),
                "the town hall started training with no food for the result");
    }

    @Test
    @DisplayName("a command hotkey begins one player transaction")
    void aCommandHotkeyBeginsOnePlayerTransaction() {
        Scene scene = scene();
        Unit hall = find(scene, "unit-town-hall");
        scene.screen().selectForTest(hall);
        grid(scene);
        UnitButton train = button(scene, hall, "train-unit", "unit-peasant");
        assertNotNull(train.key(), "the shipped peasant button has no hotkey");

        assertTrue(scene.screen().typed(train.key().charAt(0)),
                "the peasant hotkey did not reach the command panel");

        List<PlayerIntentJournal.Entry> journal = scene.screen().intentEntriesForTest();
        assertTrue(journal.stream().anyMatch(entry ->
                        "gesture".equals(entry.event())
                                && entry.gesture() != null
                                && "keyboard".equals(entry.gesture().origin())),
                "typing the train key must begin a keyboard transaction, not a "
                        + "silent press: " + journal);
    }

    @Test
    @DisplayName("an unaffordable train hotkey journals the pre-wire refusal")
    void anUnaffordableTrainHotkeyJournalsThePreWireRefusal() {
        Scene scene = scene();
        Unit hall = find(scene, "unit-town-hall");
        broke(scene, 0);
        scene.screen().selectForTest(hall);
        grid(scene);
        UnitButton train = button(scene, hall, "train-unit", "unit-peasant");
        assertTrue(scene.screen().typed(train.key().charAt(0)),
                "the peasant hotkey was ignored");

        List<PlayerIntentJournal.Decision> decisions = scene.screen().intentDecisionsForTest();
        List<PlayerIntentJournal.Feedback> feedback = scene.screen().intentFeedbackForTest();
        assertEquals(1, decisions.size(),
                "the refused train must be journaled without a wire command: "
                        + decisions);
        assertEquals("train", decisions.getFirst().family());
        assertEquals(false, decisions.getFirst().accepted());
        assertEquals(false, decisions.getFirst().queued());
        assertEquals(recovered(scene, NOT_ENOUGH_GOLD), decisions.getFirst().reason());
        assertEquals(1, feedback.size(), "CheckCosts Notify is the acknowledgement");
        assertEquals(true, feedback.getFirst().acknowledged());
        assertTrue(scene.screen().intentOutcomesForTest().isEmpty(),
                "a pre-wire refusal must not invent a unit order");
    }

    @Test
    @DisplayName("the status line never claims a simulation-rejected training order")
    void aRejectedTrainingCommandIsNotReportedAsAccepted() {
        Scene scene = scene();
        Unit hall = find(scene, "unit-town-hall");
        UnitType peasant = scene.data().unitTypes().types().get("unit-peasant");
        UnitType farm = scene.data().unitTypes().types().get("unit-farm");
        int[] farmTile = openTile(scene);
        assertNotNull(scene.world().createUnit(farm, ME, farmTile[0], farmTile[1]),
                "the control could not add supply");
        scene.world().recalculateSupply();
        assertTrue(scene.world().player(ME).hasSupplyRoom(peasant.demand()),
                "the control needs food room so the authoritative rejection is reached");

        // Break only the simulation-side relation. The real button stays on
        // the town hall, so this recreates the partial-startup failure that
        // made the interface say an order was accepted while the world
        // rejected it.
        scene.world().setTrainers(Map.of(
                "unit-peasant", Set.of("unit-human-barracks")));
        scene.screen().selectForTest(hall);
        press(scene, hall, "train-unit", "unit-peasant");

        assertEquals("Cannot train that now.", scene.screen().status(),
                "the status line announced work the command applier rejected");
        assertNull(hall.producing(), "the rejected order unexpectedly began training");
    }

    // -------------------------------------------------------------- fixtures

    /** The sentence the game itself holds at this slot of its string table. */
    private static String recovered(Scene scene, int slot) {
        return scene.data().names().name(slot);
    }

    /** Sets the bank to a fixed amount of gold. */
    private static void broke(Scene scene, int gold) {
        var player = scene.world().player(ME);
        player.add(UnitType.Resource.GOLD, gold - player.get(UnitType.Resource.GOLD));
    }

    private static int living(Scene scene) {
        int count = 0;
        for (Unit unit : scene.world().unitsSnapshot()) {
            if (unit.isAlive()) {
                count++;
            }
        }
        return count;
    }

    /** Whether the panel would draw this slot dimmed. */
    private static boolean affordable(Scene scene, UnitButton button) {
        try {
            var method = CommandPanel.class.getDeclaredMethod("affordable", UnitButton.class);
            method.setAccessible(true);
            return (Boolean) method.invoke(scene.commands(), button);
        } catch (ReflectiveOperationException unreachable) {
            throw new AssertionError("CommandPanel has no affordable(UnitButton)", unreachable);
        }
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
     * The real button the shipped scripts declare for this unit on the page
     * the panel is showing.
     *
     * <p>The last match rather than the first, because that is how
     * {@code ButtonSet.page} settles two buttons claiming one slot.
     */
    private static UnitButton button(Scene scene, Unit unit, String action, String value) {
        UnitButton found = null;
        for (UnitButton candidate : scene.data().userInterface("summer").buttons().all()) {
            if (action.equals(candidate.action())
                    && Objects.equals(value, candidate.value())
                    && candidate.appliesTo(unit.type().ident())
                    && candidate.level() == scene.commands().level()) {
                found = candidate;
            }
        }
        assertNotNull(found, "the shipped scripts declare no " + action
                + (value == null ? "" : " " + value) + " button for " + unit.type().ident()
                + " on page " + scene.commands().level());
        return found;
    }

    private static void press(Scene scene, Unit unit, String action, String value) {
        scene.screen().press(button(scene, unit, action, value), false);
    }

    private static void click(Scene scene, int tileX, int tileY) {
        int x = scene.layout().mapArea().x() + tileX * TILE + TILE / 2;
        int y = scene.layout().mapArea().y() + tileY * TILE + TILE / 2;
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

    /** A square in view that would take a farm. */
    private static int[] openTile(Scene scene) {
        UnitType farm = scene.data().unitTypes().types().get("unit-farm");
        for (int y = 1; y < 16; y++) {
            for (int x = 1; x < 24; x++) {
                if (scene.world().canPlaceBuilding(farm, x, y)) {
                    return new int[] {x, y};
                }
            }
        }
        Assumptions.assumeTrue(false, "no square in view on " + MAP + " will take a farm");
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
}
