package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
 * Sailing the tanker to the oil and then pressing Build.
 *
 * <p>The engine learned to found an oil platform on an oil patch, and the
 * interface did not come with it. {@code GameScreen} asks
 * {@code World.canPlaceBuilding} with no builder named, and an on-top rule
 * refuses a site with anything of the parent's own movement kind standing
 * inside it -- which, when the player has just sailed the tanker onto the oil,
 * is the tanker. So the cursor drew red over the patch and the click was
 * answered with "cannot build there", at every oil patch in the game.
 *
 * <p>Measured across all 52 campaign maps before the fix: of the 103 oil
 * patches that will take a platform on bare water, 103 refused the click with
 * the tanker standing on them, while {@code orderBuild} given the same tanker
 * accepted all 103. The order the player does it in decided whether the third
 * resource existed.
 *
 * <p>Upstream names the builder at every one of these call sites.
 * {@code UIHandleButtonDown} passes {@code Selected[0]} to
 * {@code CanBuildUnitType} when it colours the placement cursor
 * and again when the click commits it
 * ({@code :1708}); {@code AiFindBuildingPlace} passes the worker
 * Only the map editor passes nothing,
 * which is the case the builderless form exists for.
 *
 * <p>This starts from the mouse rather than from {@code canPlaceBuilding},
 * because the engine's own answer was already right: a test that asked the
 * world would have passed while the interface went on refusing.
 */
class OilPlatformPlacementTest {

    /**
     * A mission with an oil patch inside the opening view.
     *
     * <p>The click below is a real screen click, so the patch has to be
     * somewhere the camera starts out looking: the fourteenth human mission
     * carries one at 9,5. Every map with oil behaves the same way -- the sweep
     * that measured this ran over all 52 -- and this one is chosen only so
     * that a mouse event can reach it without scrolling.
     */
    private static final String MAP = "campaigns/human/level14h";

    private static final int WIDTH = 1280;

    private static final int HEIGHT = 800;

    private static final int TILE = 32;

    private record Scene(GameScreen screen, CommandPanel commands, World world,
            GameData data, UiLayout.Layout layout, int me) {}

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
        int me = GameData.personIn(pud);
        var tileset = data.loadTileset(pud.tileset());
        World world = new World(GameMap.from(pud, tileset.tileset()), Player.from(pud));
        world.setUnitTypes(data.unitTypes().types());
        world.setUpgrades(data.upgrades().upgrades());
        world.setSpells(data.spells().spells());
        world.setMissileTypes(data.missiles().types());
        data.populate(world, pud);
        world.setBuilders(data.buildRelation(pud.tileset()));
        world.fog().revealAll(me);

        var rendered = new MapRenderer(tileset.tileset(), tileset.sheet())
                .render(world.map().width(), world.map().height(), world.map().tileCodes());
        BufferedImage terrain = rendered.toIndexedBufferedImage(tileset.palette());
        CommandApplier applier = new CommandApplier(world,
                new ArrayList<>(data.unitTypes().types().values()));

        UiLayout.Layout layout = data.uiLayout("human", WIDTH, HEIGHT);
        assertNotNull(layout, "the layout script must be readable for any of this to mean much");
        SidePanel panel = new SidePanel(world, data, me, "human", "summer", layout);
        CommandPanel commands = new CommandPanel(world, data, data.userInterface("summer"),
                data.upgrades().dependencies(), me, "summer", "human",
                data.unitTypes().types(), layout);
        GameScreen screen = new GameScreen(world, data, terrain, tileset.palette(),
                "summer", me, WIDTH, HEIGHT, null, panel, commands, applier,
                CommandSink.local(applier), List.of(), "human");
        screen.setSize(WIDTH, HEIGHT);
        screen.setLayout(layout);
        screen.setGameScale(1);
        return new Scene(screen, commands, world, data, layout, me);
    }

    @Test
    @DisplayName("the tanker is sitting on the oil, and the platform still goes down")
    void aTankerStandingOnTheOilCanStillFoundThePlatform() {
        Scene scene = scene();
        World world = scene.world();
        UnitType platform = scene.data().unitTypes().types()
                .get("unit-human-oil-platform");
        UnitType tankerType = scene.data().unitTypes().types()
                .get("unit-human-oil-tanker");
        assertNotNull(platform, "no oil platform in the roster");
        assertNotNull(tankerType, "no oil tanker in the roster");

        Unit patch = patchInView(scene);
        int px = patch.tileX();
        int py = patch.tileY();
        assertTrue(world.canPlaceBuilding(platform, px, py),
                "the fixture's patch at " + px + "," + py + " will not take a platform even "
                        + "with nothing on it, so this proves nothing about the builder");

        // The player sails the tanker onto the oil and then presses Build,
        // which is the order anybody would do it in.
        Unit tanker = world.createUnit(tankerType, scene.me(), px + 1, py + 1);
        assertNotNull(tanker, "the tanker could not be put on the patch at " + px + "," + py);
        tanker.setHitPoints(tankerType.hitPoints());
        world.player(scene.me()).set(UnitType.Resource.GOLD, 20000);
        world.player(scene.me()).set(UnitType.Resource.WOOD, 20000);
        world.player(scene.me()).set(UnitType.Resource.OIL, 20000);

        scene.screen().selectForTest(tanker);
        press(scene, tanker, "build", "unit-human-oil-platform");
        assertEquals("unit-human-oil-platform",
                scene.screen().placingForTest() == null
                        ? null : scene.screen().placingForTest().ident(),
                "the Build Oil Platform button did not arm the cursor, so the click below "
                        + "would prove nothing");

        click(scene, px, py);

        assertEquals("building " + platform.name(), scene.screen().status(),
                "the click over the oil patch at " + px + "," + py + " was refused: the screen "
                        + "asks canPlaceBuilding with no builder named, and the on-top rule "
                        + "turns down a site with anything naval standing in it -- which is the "
                        + "player's own tanker. Upstream passes Selected[0] here "
                        + "(mouse.cpp:1708)");

        boolean started = false;
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 120 && !started; cycle++) {
            world.tick();
            for (Unit unit : world.unitsSnapshot()) {
                if (unit.type() == platform && unit.tileX() == px && unit.tileY() == py
                        && !unit.removed()) {
                    started = true;
                }
            }
        }
        assertTrue(started, "the order was accepted and no platform ever appeared at "
                + px + "," + py);
    }

    /**
     * The same click one square off the oil, which must still be refused.
     *
     * <p>The control. Naming the builder must not turn the gate into a rubber
     * stamp: open sea beside the patch has no on-top rule to satisfy and a
     * platform cannot be founded there, tanker or no tanker.
     */
    @Test
    @DisplayName("naming the builder does not let a platform go down on open sea")
    void openSeaIsStillRefusedWithTheTankerThere() {
        Scene scene = scene();
        World world = scene.world();
        UnitType platform = scene.data().unitTypes().types()
                .get("unit-human-oil-platform");
        UnitType tankerType = scene.data().unitTypes().types()
                .get("unit-human-oil-tanker");
        Unit patch = patchInView(scene);
        int px = patch.tileX();
        int py = patch.tileY();

        // Far enough off the patch that its own nine squares are not involved.
        int sx = px + 5;
        int sy = py;
        Assumptions.assumeTrue(world.map().contains(sx + 2, sy + 2),
                "the fixture needs open sea beside the patch");
        Assumptions.assumeTrue(!world.canPlaceBuilding(platform, sx, sy),
                "the square beside the patch already refuses a platform for some other "
                        + "reason, so this control proves nothing");

        Unit tanker = world.createUnit(tankerType, scene.me(), sx, sy);
        Assumptions.assumeTrue(tanker != null, "no room for the tanker on the open sea");
        tanker.setHitPoints(tankerType.hitPoints());
        world.player(scene.me()).set(UnitType.Resource.GOLD, 20000);
        world.player(scene.me()).set(UnitType.Resource.WOOD, 20000);
        world.player(scene.me()).set(UnitType.Resource.OIL, 20000);

        scene.screen().selectForTest(tanker);
        press(scene, tanker, "build", "unit-human-oil-platform");
        click(scene, sx, sy);

        assertEquals("cannot build there", scene.screen().status(),
                "a platform was accepted on open sea at " + sx + "," + sy + " once the tanker "
                        + "was named as the builder: skipping the builder must not skip the "
                        + "rule");
    }

    /** An oil patch the camera can reach, since the click is a screen click. */
    private static Unit patchInView(Scene scene) {
        int tilesAcross = scene.layout().mapArea().width() / TILE;
        int tilesDown = scene.layout().mapArea().height() / TILE;
        UnitType platform = scene.data().unitTypes().types().get("unit-human-oil-platform");
        for (Unit unit : scene.world().unitsSnapshot()) {
            if (unit.type() == null || !"unit-oil-patch".equals(unit.type().ident())
                    || unit.removed()) {
                continue;
            }
            if (unit.tileX() + 3 < tilesAcross && unit.tileY() + 3 < tilesDown
                    && scene.world().canPlaceBuilding(platform, unit.tileX(), unit.tileY())) {
                return unit;
            }
        }
        Assumptions.assumeTrue(false, MAP + " has no oil patch in the opening view");
        return null;
    }

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
        assertNotNull(found, "the shipped scripts declare no " + action + " " + value
                + " button for " + unit.type().ident() + " on page "
                + scene.commands().level());
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
}
