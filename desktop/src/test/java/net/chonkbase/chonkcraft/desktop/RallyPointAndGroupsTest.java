package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.MapRenderer;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rally points can be set, and a modifier no longer eats a control group.
 *
 * <p>Two interface gaps that had nothing in common except that both were
 * invisible from inside the code.
 *
 * <p>{@code World.setRallyPoint} was written, documented and tested, and no
 * click in the game reached it -- so a unit always walked out beside the
 * building that made it, whatever the player had chosen. A building that
 * trains cannot be told to walk anywhere, so a right click on the map is the
 * one thing it can mean.
 *
 * <p>The control-group keys were worse. The dispatch was
 * {@code groupKey(digit, control || alt || shift)}: every modifier meant
 * "define". Upstream's {@code CommandKey_Group} has five arms -- plain
 * selects, control defines, shift-control adds the selection to the group,
 * shift adds the group to the selection, and alt centres the view without
 * touching the selection at all. Pressing Alt-3 to look at your third group
 * therefore replaced your third group with whatever happened to be selected,
 * and a player loses a group they had spent the game building up and reads it
 * as their own slip.
 */
class RallyPointAndGroupsTest {

    private static final String MAP = "campaigns/human/level02h";
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static final int TILE = 32;

    private record Scene(GameScreen screen, World world, GameData data) {}

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

        IndexedImage rendered = new MapRenderer(tileset.tileset(), tileset.sheet())
                .render(world.map().width(), world.map().height(), world.map().tileCodes());
        BufferedImage terrain = rendered.toIndexedBufferedImage(tileset.palette());

        CommandApplier applier = new CommandApplier(world,
                new ArrayList<>(data.unitTypes().types().values()));
        applier.setUpgrades(data.upgrades().upgrades().all().keySet());
        applier.setSpells(data.spells().spells().all().keySet());

        GameScreen screen = new GameScreen(world, data, terrain, tileset.palette(),
                tilesetName, 0, WIDTH, HEIGHT,
                new net.chonkbase.chonkcraft.engine.sound.GameAudio(data.sounds()),
                null, null, applier, CommandSink.local(applier),
                java.util.List.of(), "human");
        screen.setSize(WIDTH, HEIGHT);
        // One world pixel to one screen pixel, so a click at (x, y) is the
        // square the test means.
        screen.setLayout((net.chonkbase.chonkcraft.engine.ui.UiLayout.Layout) null);
        screen.setGameScale(1);
        return new Scene(screen, world, data);
    }

    /** Puts a unit somewhere inside the viewport, or skips the test. */
    private static Unit place(Scene scene, String ident, int fromColumn) {
        UnitType type = scene.data().unitTypes().types().get(ident);
        assertNotNull(type, "the shipped data has a " + ident);
        for (int x = fromColumn; x < WIDTH / TILE - 5; x++) {
            for (int y = 2; y < HEIGHT / TILE - 5; y++) {
                Unit unit = scene.world().createUnit(type, 0, x, y);
                if (unit != null) {
                    return unit;
                }
            }
        }
        Assumptions.assumeTrue(false, "nowhere on this map to put a " + ident);
        return null;
    }

    /** A right click on the map, through the screen's own mouse listener. */
    private static void rightClick(GameScreen screen, int x, int y) {
        MouseEvent event = new MouseEvent(screen, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), InputEvent.BUTTON3_DOWN_MASK,
                x, y, 1, false, MouseEvent.BUTTON3);
        for (var listener : screen.getMouseListeners()) {
            listener.mousePressed(event);
        }
    }

    /** A digit press with the given modifiers, through the real key handler. */
    private static void digit(GameScreen screen, int digit, int modifiers) {
        screen.keyPressed(new KeyEvent(screen, KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(), modifiers,
                KeyEvent.VK_0 + digit, (char) ('0' + digit)));
    }

    private static List<Integer> selectedIds(World world) {
        List<Integer> ids = new ArrayList<>();
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.selected()) {
                ids.add(unit.id());
            }
        }
        return ids;
    }

    @Test
    @DisplayName("right-clicking the map with a barracks selected sets its rally point")
    void aProducingBuildingTakesARallyPoint() {
        Scene scene = scene();
        Unit barracks = place(scene, "unit-human-barracks", 2);
        barracks.setSelected(true);

        assertTrue(!barracks.hasRallyPoint(), "a new barracks has no rally point");

        // Somewhere clear of the building itself, so the click cannot be read
        // as a click on the barracks.
        int targetX = barracks.tileX() + 6;
        int targetY = barracks.tileY() + 4;
        Assumptions.assumeTrue(targetX < WIDTH / TILE && targetY < HEIGHT / TILE,
                "the target square is off screen");

        // Painted with the barracks already selected, so the selection box is
        // in both frames and the only thing that can differ is the marker.
        BufferedImage unmarked = paint(scene.screen());

        rightClick(scene.screen(), targetX * TILE + TILE / 2, targetY * TILE + TILE / 2);

        assertTrue(barracks.hasRallyPoint(),
                "a right click on the map did not reach World.setRallyPoint");
        assertEquals(targetX, barracks.rallyX());
        assertEquals(targetY, barracks.rallyY());

        // And it is visible. A rally point the player cannot see is a rally
        // point the player will not trust, so the marker is drawn while the
        // building that owns it is selected -- and only then.
        BufferedImage marked = paint(scene.screen());
        save(marked, "rally-point.png");

        int changed = 0;
        int stray = 0;
        int arm = TILE / 4 + 2;
        int centreX = targetX * TILE + TILE / 2;
        int centreY = targetY * TILE + TILE / 2;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                if (marked.getRGB(x, y) == unmarked.getRGB(x, y)) {
                    continue;
                }
                changed++;
                if (Math.abs(x - centreX) > arm || Math.abs(y - centreY) > arm) {
                    stray++;
                }
            }
        }
        assertTrue(changed > 20, "the rally point was not drawn: " + changed + " pixels differ");
        assertEquals(0, stray,
                stray + " pixels changed away from the rally square: the marker is being drawn"
                        + " somewhere other than where the rally point is");
    }

    private static BufferedImage paint(GameScreen screen) {
        BufferedImage frame = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        var g = frame.createGraphics();
        screen.paint(g);
        g.dispose();
        return frame;
    }

    private static void save(BufferedImage frame, String name) {
        try {
            Path out = Paths.get("target");
            Files.createDirectories(out);
            javax.imageio.ImageIO.write(frame, "png", out.resolve(name).toFile());
        } catch (java.io.IOException ignored) {
            // A frame that could not be written is not a failing assertion.
        }
    }

    /**
     * A blacksmith takes no rally point, because it makes nothing.
     *
     * <p>Which buildings count is read from the button tables rather than
     * listed here, so this is the assertion that says the reading is a real
     * distinction and not "every building". A farm, incidentally, does count:
     * the shipped tables give it a train-critter button.
     */
    @Test
    @DisplayName("a blacksmith takes no rally point, because it trains nothing")
    void aBuildingThatMakesNothingIsUnaffected() {
        Scene scene = scene();
        Unit smith = place(scene, "unit-human-blacksmith", 2);
        smith.setSelected(true);
        rightClick(scene.screen(), (smith.tileX() + 5) * TILE, (smith.tileY() + 3) * TILE);
        assertTrue(!smith.hasRallyPoint(),
                "a blacksmith has nothing to send anywhere and should take no rally point");
    }

    @Test
    @DisplayName("alt on a digit centres the view and leaves the group alone")
    void altCentresRatherThanRedefining() {
        Scene scene = scene();
        Unit first = place(scene, "unit-footman", 2);
        Unit second = place(scene, "unit-peasant", 8);

        first.setSelected(true);
        digit(scene.screen(), 1, InputEvent.CTRL_DOWN_MASK);

        // Now select something else entirely and press Alt-1, which in the
        // original moves the view to group one.
        first.setSelected(false);
        second.setSelected(true);
        digit(scene.screen(), 1, InputEvent.ALT_DOWN_MASK);

        // The group must still be the footman -- this is the assertion that
        // fails on the old dispatch, where alt meant "define" and group one
        // silently became the peasant.
        first.setSelected(false);
        second.setSelected(false);
        digit(scene.screen(), 1, 0);
        assertEquals(List.of(first.id()), selectedIds(scene.world()),
                "Alt-1 overwrote group one instead of centring on it");
    }

    @Test
    @DisplayName("shift on a digit adds the group to the selection")
    void shiftAddsTheGroupToTheSelection() {
        Scene scene = scene();
        Unit first = place(scene, "unit-footman", 2);
        Unit second = place(scene, "unit-peasant", 8);

        first.setSelected(true);
        digit(scene.screen(), 1, InputEvent.CTRL_DOWN_MASK);

        first.setSelected(false);
        second.setSelected(true);
        digit(scene.screen(), 1, InputEvent.SHIFT_DOWN_MASK);

        List<Integer> both = selectedIds(scene.world());
        assertTrue(both.contains(first.id()) && both.contains(second.id()),
                "Shift-1 should have added group one to the selection; got " + both);

        // And the group itself is untouched, which is the half that used to be
        // destroyed.
        first.setSelected(false);
        second.setSelected(false);
        digit(scene.screen(), 1, 0);
        assertEquals(List.of(first.id()), selectedIds(scene.world()),
                "Shift-1 overwrote group one instead of adding it to the selection");
    }

    @Test
    @DisplayName("shift-control adds the selection to the group")
    void shiftControlExtendsTheGroup() {
        Scene scene = scene();
        Unit first = place(scene, "unit-footman", 2);
        Unit second = place(scene, "unit-peasant", 8);

        first.setSelected(true);
        digit(scene.screen(), 1, InputEvent.CTRL_DOWN_MASK);

        first.setSelected(false);
        second.setSelected(true);
        digit(scene.screen(), 1, InputEvent.SHIFT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK);

        first.setSelected(false);
        second.setSelected(false);
        digit(scene.screen(), 1, 0);
        List<Integer> group = selectedIds(scene.world());
        assertTrue(group.contains(first.id()) && group.contains(second.id()),
                "Shift-Control-1 should have added the selection to group one; got " + group);
    }

    @Test
    @DisplayName("control alone still replaces the group")
    void controlStillDefines() {
        Scene scene = scene();
        Unit first = place(scene, "unit-footman", 2);
        Unit second = place(scene, "unit-peasant", 8);

        first.setSelected(true);
        digit(scene.screen(), 1, InputEvent.CTRL_DOWN_MASK);
        first.setSelected(false);
        second.setSelected(true);
        digit(scene.screen(), 1, InputEvent.CTRL_DOWN_MASK);

        second.setSelected(false);
        digit(scene.screen(), 1, 0);
        assertEquals(List.of(second.id()), selectedIds(scene.world()),
                "Control-1 must still replace the group outright");
    }
}
