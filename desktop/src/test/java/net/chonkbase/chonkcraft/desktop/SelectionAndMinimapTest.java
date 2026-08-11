package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.MapRenderer;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.ui.UiLayout;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Selection markers and minimap dots say whose things they are.
 *
 * <p>Three pieces of the same complaint, all of them "the data says one thing
 * and the renderer says another".
 *
 * <p>The selection marker was a closed green rectangle the size of the tile
 * footprint. {@code DrawUnitSelection} sizes it from {@code BoxWidth} and
 * {@code BoxHeight}, draws it as four corner brackets --
 * {@code SelectionStyle = "corners"} in {@code scripts/legacyEngine.legacy-declaration} -- and
 * colours it by ownership: yellow for neutral, green for your own, red for an
 * enemy, the side's own colour for anybody else. A catapult declares
 * {@code BoxSize = {63, 63}} on a single 32-pixel tile, so its marker was
 * drawn at half the size and vanished behind its own sprite.
 *
 * <p>The minimap painted your units green and everything else red -- allies,
 * gold mines and sheep included -- and gave every unit one pixel, with a second
 * bolted on for buildings. {@code DrawUnitOn} takes a neutral unit's colour
 * from its own {@code NeutralMinimapColor} and everybody else's from their
 * player colour, and sizes the dot by the footprint.
 *
 * <p>The command icons carried no hotkey letter, though
 * {@code UI.ButtonPanel.ShowCommandKey} defaults to true and the keys all
 * worked. There was no way to learn one except by hovering each slot in turn.
 */
class SelectionAndMinimapTest {

    private static final String MAP = "campaigns/human/level02h";
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static final int TILE = 32;

    private record Scene(GameScreen screen, SidePanel panel, CommandPanel commands,
            World world, UiLayout.Layout layout, GameData data) {}

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
        UiLayout.Layout layout = data.uiLayout("human", WIDTH, HEIGHT);
        assertNotNull(layout);
        SidePanel panel = new SidePanel(world, data, 0, "human", tilesetName, layout);
        CommandPanel commands = new CommandPanel(world, data, data.userInterface(tilesetName),
                data.upgrades().dependencies(), 0, tilesetName, "human",
                data.unitTypes().types(), layout);

        GameScreen screen = new GameScreen(world, data, terrain, tileset.palette(),
                tilesetName, 0, WIDTH, HEIGHT,
                new net.chonkbase.chonkcraft.engine.sound.GameAudio(data.sounds()),
                null, null, applier, CommandSink.local(applier),
                java.util.List.of(), "human");
        screen.setSize(WIDTH, HEIGHT);
        screen.setLayout((UiLayout.Layout) null);
        screen.setGameScale(1);
        return new Scene(screen, panel, commands, world, layout, data);
    }

    private static BufferedImage paintWorld(GameScreen screen) {
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

    private static Unit place(Scene scene, String ident, int player, int fromColumn) {
        UnitType type = scene.data().unitTypes().types().get(ident);
        assertNotNull(type, "the shipped data has a " + ident);
        for (int x = fromColumn; x < WIDTH / TILE - 4; x++) {
            for (int y = 2; y < HEIGHT / TILE - 4; y++) {
                Unit made = scene.world().createUnit(type, player, x, y);
                if (made != null) {
                    return made;
                }
            }
        }
        Assumptions.assumeTrue(false, "nowhere to put a " + ident);
        return null;
    }

    /** Puts the local player's sight over a unit, so it can be drawn at all. */
    private static void see(Scene scene, Unit unit) {
        scene.world().fog().addSight(0, unit.tileX(), unit.tileY(),
                Math.max(1, unit.type().tileWidth()), Math.max(1, unit.type().tileHeight()), 3);
        // A bare fog poke moves no unit's watcher count, and what is drawn
        // is the count, not the tiles. The global recount settles it the
        // way any real step would.
        scene.world().recountSeen();
    }

    /** How many pixels of one exact colour lie in a box of the frame. */
    private static int count(BufferedImage frame, int left, int top, int width, int height,
            int rgb) {
        int found = 0;
        for (int y = Math.max(0, top); y < Math.min(frame.getHeight(), top + height); y++) {
            for (int x = Math.max(0, left); x < Math.min(frame.getWidth(), left + width); x++) {
                if ((frame.getRGB(x, y) & 0xFFFFFF) == (rgb & 0xFFFFFF)) {
                    found++;
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("a catapult's marker is drawn at its declared box size, not its tile")
    void theMarkerFollowsTheDeclaredBox() {
        Scene scene = scene();
        Unit catapult = place(scene, "unit-catapult", 0, 4);
        UnitType type = catapult.type();

        // The premise. A catapult is the case that makes the two rules
        // disagree: one tile across, a box twice that.
        assertEquals(63, type.boxWidth(), "the shipped catapult declares a 63 pixel box");
        assertEquals(1, type.tileWidth(), "and stands on a single tile");

        BufferedImage before = paintWorld(scene.screen());
        catapult.setSelected(true);
        BufferedImage after = paintWorld(scene.screen());
        save(after, "selection-marker.png");

        // Where the corners must be, from BoxWidth centred on the footprint.
        int left = catapult.pixelX() + (TILE - type.boxWidth()) / 2;
        int top = catapult.pixelY() + (TILE - type.boxHeight()) / 2;

        int leftmost = Integer.MAX_VALUE;
        int rightmost = -1;
        int changed = 0;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                if (before.getRGB(x, y) == after.getRGB(x, y)) {
                    continue;
                }
                changed++;
                leftmost = Math.min(leftmost, x);
                rightmost = Math.max(rightmost, x);
            }
        }
        assertTrue(changed > 20, "no selection marker was drawn: " + changed + " pixels");
        assertEquals(left, leftmost,
                "the marker starts at column " + leftmost + " rather than " + left
                        + ": it is still being sized from the tile footprint");
        assertEquals(left + type.boxWidth(), rightmost,
                "the marker ends at column " + rightmost + " rather than "
                        + (left + type.boxWidth()));

        // Corners, not a closed box. A full rectangle of 63 by 63 would be
        // about 250 pixels of outline; four six-pixel brackets are about 44.
        assertTrue(changed < 100,
                changed + " pixels of marker for a 63 pixel box: that is a closed rectangle,"
                        + " and ChonkCraft asks for corners");
    }

    @Test
    @DisplayName("markers are coloured by ownership")
    void theMarkerIsColouredByOwnership() {
        Scene scene = scene();
        Unit mine = place(scene, "unit-footman", 0, 2);
        Unit sheep = place(scene, "unit-critter",
                net.chonkbase.chonkcraft.engine.World.NEUTRAL_PLAYER, 8);
        Unit theirs = place(scene, "unit-footman", 1, 14);
        mine.setSelected(true);
        sheep.setSelected(true);
        theirs.setSelected(true);
        // Sight, not just exploration. revealAll marks ground as explored;
        // a unit is only drawn where the local player can currently see, so a
        // neutral or enemy unit needs somebody's sight over it. Upstream's
        // rule, and the reason a scouted town is remembered rather than
        // watched.
        see(scene, sheep);
        see(scene, theirs);

        BufferedImage frame = paintWorld(scene.screen());
        save(frame, "selection-colours.png");

        assertTrue(count(frame, mine.pixelX() - 24, mine.pixelY() - 24, 80, 80,
                        java.awt.Color.GREEN.getRGB()) > 10,
                "your own footman is not marked green");
        assertTrue(count(frame, sheep.pixelX() - 24, sheep.pixelY() - 24, 80, 80,
                        java.awt.Color.YELLOW.getRGB()) > 10,
                "a neutral critter is not marked yellow: DrawUnitSelection's first arm");
        assertTrue(count(frame, theirs.pixelX() - 24, theirs.pixelY() - 24, 80, 80,
                        java.awt.Color.RED.getRGB()) > 10,
                "an enemy footman is not marked red");
        // And your own is not also red, which is what "everything is green"
        // would look like from the other direction.
        assertEquals(0, count(frame, mine.pixelX() - 20, mine.pixelY() - 20, 72, 72,
                        java.awt.Color.RED.getRGB()),
                "your own footman is marked in the enemy colour");
    }

    @Test
    @DisplayName("a gold mine is not painted in the enemy's red on the minimap")
    void theMinimapUsesTheDeclaredNeutralColour() {
        Scene scene = scene();
        Unit mine = place(scene, "unit-gold-mine",
                net.chonkbase.chonkcraft.engine.World.NEUTRAL_PLAYER, 2);
        UnitType type = mine.type();

        // The premise, straight out of scripts/units.legacy-declaration.
        Object declared = type.rawProperties().get("NeutralMinimapColor");
        assertNotNull(declared, "a gold mine declares a neutral minimap colour");
        see(scene, mine);

        BufferedImage frame =
                new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = frame.createGraphics();
        scene.panel().draw(g2, WIDTH, HEIGHT, null, 0, 0, WIDTH, HEIGHT);
        g2.dispose();
        save(frame, "minimap-neutral.png");

        var box = scene.layout().minimap();
        int red = count(frame, box.x(), box.y(), box.width(), box.width(), 0xFF0000);
        assertEquals(0, red,
                red + " pixels of enemy red on a minimap whose only units are neutral:"
                        + " NeutralMinimapColor is not being read");
        // The gold mine's own declared yellow is there instead.
        assertTrue(count(frame, box.x(), box.y(), box.width(), box.width(), 0xFFFF00) > 0,
                "the gold mine's declared {255, 255, 0} is not on the minimap");
    }

    /**
     * The target of a right click flashes back.
     *
     * <p>{@code dest->Blink = 4} appears in every branch of
     * {@code DoRightButton} that has a target, and {@code HandleActions}
     * counts it down a cycle at a time while {@code DrawUnitSelection} marks
     * the unit on the odd values. Nothing here set it, so a click on a distant
     * unit looked exactly like a click on the ground beside it.
     */
    @Test
    @DisplayName("a right-clicked target flashes for a few cycles and then stops")
    void theTargetOfAClickBlinks() {
        Scene scene = scene();
        Unit footman = place(scene, "unit-footman", 0, 2);
        Unit target = place(scene, "unit-footman", 1, 10);
        see(scene, target);
        footman.setSelected(true);

        BufferedImage quiet = paintWorld(scene.screen());

        java.awt.event.MouseEvent event = new java.awt.event.MouseEvent(scene.screen(),
                java.awt.event.MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                java.awt.event.InputEvent.BUTTON3_DOWN_MASK,
                target.tileX() * TILE + TILE / 2, target.tileY() * TILE + TILE / 2,
                1, false, java.awt.event.MouseEvent.BUTTON3);
        for (var listener : scene.screen().getMouseListeners()) {
            listener.mousePressed(event);
        }
        // One cycle in, the counter is odd and the flash is on.
        scene.world().tick();
        BufferedImage flashing = paintWorld(scene.screen());
        save(flashing, "blink-target.png");

        int marked = count(flashing, target.pixelX() - 24, target.pixelY() - 24, 80, 80,
                java.awt.Color.RED.getRGB());
        assertTrue(marked > 10,
                "the right-clicked enemy is not flashed: Blink is set in every branch of"
                        + " DoRightButton that has a target");
        assertEquals(0, count(quiet, target.pixelX() - 24, target.pixelY() - 24, 80, 80,
                        java.awt.Color.RED.getRGB()),
                "the enemy was already marked before the click, so the flash proves nothing");

        // And it stops. A mark that never goes out is a selection, not a flash.
        for (int i = 0; i < 8; i++) {
            scene.world().tick();
        }
        assertEquals(0, count(paintWorld(scene.screen()),
                        target.pixelX() - 24, target.pixelY() - 24, 80, 80,
                        java.awt.Color.RED.getRGB()),
                "the flash never went out");
    }

    @Test
    @DisplayName("command icons carry their hotkey letter")
    void theHotkeyLetterIsDrawn() {
        Scene scene = scene();
        Unit hall = place(scene, "unit-town-hall", 0, 2);
        hall.setSelected(true);

        BufferedImage with = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = with.createGraphics();
        scene.commands().draw(g2, hall);
        g2.dispose();
        save(with, "command-hotkeys.png");

        scene.commands().setShowCommandKey(false);
        BufferedImage without = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D bare = without.createGraphics();
        scene.commands().draw(bare, hall);
        bare.dispose();

        int changed = 0;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                if (with.getRGB(x, y) != without.getRGB(x, y)) {
                    changed++;
                }
            }
        }
        assertTrue(changed > 40,
                "turning ShowCommandKey off changed only " + changed + " pixels, so the letters"
                        + " were not being drawn in the first place");
    }

    /**
     * A unit of yours under attack flashes red on the minimap.
     *
     * <p>{@code DrawUnitOn}: struck within the
     * last second, the dot is solid red; for the six seconds after that it
     * blinks red on alternate seconds; then it is a green dot again. The
     * minimap is how a player learns about a fight they are not looking at,
     * and without this arm a base being razed off screen looked exactly like
     * a base at peace. The state it reads, the cycle of the last blow, is
     * {@code Unit.attackedCycle} -- set by every hit for the help-cry gag and
     * never cleared, so the one field serves both rules.
     */
    @Test
    @DisplayName("a struck unit's dot turns red, blinks, and goes green again")
    void aStruckUnitFlashesOnTheMinimap() {
        Scene scene = scene();
        Unit footman = place(scene, "unit-footman", 0, 4);
        var box = scene.layout().minimap();

        BufferedImage calm = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = calm.createGraphics();
        scene.panel().draw(g2, WIDTH, HEIGHT, null, 0, 0, WIDTH, HEIGHT);
        g2.dispose();
        assertEquals(0, count(calm, box.x(), box.y(), box.width(), box.width(), 0xFF0000),
                "there is red on the minimap before anything was struck, so a flash"
                        + " could not be told from it");

        // Struck on cycle three. Solid red for the first second...
        for (int i = 0; i < 3; i++) {
            scene.world().tick();
        }
        footman.setAttackedCycle(scene.world().cycle());
        assertTrue(minimapRed(scene, box) > 0,
                "a unit struck this very second is not red on the minimap:"
                        + " ATTACK_RED_DURATION is one second and this is cycle one of it");

        // ...blinking after it: on during an even second, off during an odd.
        scene.world().setCycle(3 + 60);
        assertTrue(minimapRed(scene, box) > 0,
                "two seconds after the blow, an even second, the blink is not on");
        scene.world().setCycle(3 + 90);
        assertEquals(0, minimapRed(scene, box),
                "three seconds after the blow, an odd second, the blink is not off --"
                        + " a dot that stays red is an alarm that never ends");

        // And over after seven seconds.
        scene.world().setCycle(3 + 211);
        assertEquals(0, minimapRed(scene, box),
                "the flash outlives ATTACK_BLINK_DURATION: the dot is still red"
                        + " more than seven seconds after the last blow");
    }

    /** Red pixels in the minimap frame as the panel draws it right now. */
    private int minimapRed(Scene scene, UiLayout.Box box) {
        BufferedImage frame = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = frame.createGraphics();
        scene.panel().draw(g2, WIDTH, HEIGHT, null, 0, 0, WIDTH, HEIGHT);
        g2.dispose();
        return count(frame, box.x(), box.y(), box.width(), box.width(), 0xFF0000);
    }
}
