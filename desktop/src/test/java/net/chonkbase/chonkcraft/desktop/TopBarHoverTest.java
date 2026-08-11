package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.MapRenderer;
import net.chonkbase.chonkcraft.engine.ui.UiLayout;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pointing at a count on the resource bar, at a pinned interface scale.
 *
 * <p>Reported from play at interface two on a HiDPI display: "hover over
 * doesn't work for resources. The only one that shows up is for gold and it
 * shows up when the mouse cursor is on the number for OIL." The panel worked
 * the pointer out for itself from an AWT event listener, dividing window
 * coordinates by the full transform scale -- which on such a display
 * includes the device's own doubling that mouse events are already
 * normalised for -- so every hover landed at half its real position, and
 * the counts on the right half of the bar could not be hovered at all.
 *
 * <p>The screen now feeds the panel the pointer through the same
 * {@code toDesign} conversion every other hit test uses, so these drive real
 * {@code mouseMoved} events through the screen's own listeners at scale two
 * and ask that each of the six counts answers at its own position -- which
 * also pins the cards themselves: each one draws, each is its own card, and
 * the gold card carries the mine's actual picture rather than prose alone.
 */
class TopBarHoverTest {

    private static final String MAP = "campaigns/human/level03h";
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 960;
    private static final int SCALE = 2;

    private static GameData data() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    private record Scene(GameScreen screen, SidePanel panel) {}

    private static Scene scene() {
        GameData data = data();
        PudMap pud = data.campaignMap(MAP);
        Assumptions.assumeTrue(pud != null, "no campaign map available");
        GameData.LoadedTileset tileset = data.loadTileset(pud.tileset());
        World world = new World(GameMap.from(pud, tileset.tileset()), Player.from(pud));
        world.setUnitTypes(data.unitTypes().types());
        world.setMissileTypes(data.missiles().types());
        world.fog().revealAll(1);

        IndexedImage rendered = new MapRenderer(tileset.tileset(), tileset.sheet())
                .render(world.map().width(), world.map().height(), world.map().tileCodes());
        BufferedImage terrain = rendered.toIndexedBufferedImage(tileset.palette());
        String tilesetName = pud.tileset() == PudMap.Tileset.FOREST
                ? "summer"
                : pud.tileset().name().toLowerCase(java.util.Locale.ROOT);
        UiLayout.Layout layout = data.uiLayout("human",
                WIDTH / SCALE, HEIGHT / SCALE);
        SidePanel panel = new SidePanel(world, data, 1, "human", tilesetName, layout);
        GameScreen screen = new GameScreen(world, data, terrain, tileset.palette(),
                tilesetName, 1, WIDTH, HEIGHT, null, panel, null, null, null,
                java.util.List.of(), "human");
        screen.setSize(WIDTH, HEIGHT);
        screen.setLayout(layout);
        screen.setGameScale(1);
        // The interface scale, which is the one under test -- setGameScale
        // is the map zoom and says nothing about the chrome.
        screen.setInterfaceScale(SCALE);
        return new Scene(screen, panel);
    }

    private static BufferedImage paint(GameScreen screen) {
        BufferedImage frame = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        var g = frame.createGraphics();
        screen.paint(g);
        g.dispose();
        return frame;
    }

    /** A real pointer movement, through the screen's own listeners. */
    private static void moveTo(Scene scene, int x, int y) {
        MouseEvent moved = new MouseEvent(scene.screen(), MouseEvent.MOUSE_MOVED,
                0L, 0, x, y, 0, false);
        for (var listener : scene.screen().getMouseMotionListeners()) {
            listener.mouseMoved(moved);
        }
    }

    private static int differing(BufferedImage a, BufferedImage b) {
        int changed = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    changed++;
                }
            }
        }
        return changed;
    }

    @Test
    @DisplayName("every count answers a pointer parked on it, at scale two, at its own position")
    void everyCountAnswersAtItsOwnPosition() {
        Scene scene = scene();
        BufferedImage bare = paint(scene.screen());
        var cells = scene.panel().topBarForTest();
        assertTrue(cells.size() >= 6,
                "the bar laid out " + cells.size() + " counts, so a sweep of it proves"
                        + " little");

        BufferedImage previous = null;
        for (var cell : cells) {
            var box = cell.bounds();
            // The design point, scaled up to the window pixels a real mouse
            // event carries at interface two.
            moveTo(scene, (box.x + box.width / 2) * SCALE, (box.y + 8) * SCALE);
            BufferedImage hovered = paint(scene.screen());
            assertTrue(differing(bare, hovered) > 50,
                    "count " + cell.slot() + " drew no card for a pointer parked on"
                            + " it: the hover position reaches the panel wrongly");
            if (previous != null) {
                assertTrue(differing(previous, hovered) > 0,
                        "two neighbouring counts drew the same card, so the hit test"
                                + " cannot tell them apart");
            }
            previous = hovered;
        }

        // Leaving the window takes the card with it.
        MouseEvent exited = new MouseEvent(scene.screen(), MouseEvent.MOUSE_EXITED,
                0L, 0, 0, 0, 0, false);
        for (var listener : scene.screen().getMouseListeners()) {
            listener.mouseExited(exited);
        }
        assertEquals(0, differing(bare, paint(scene.screen())),
                "the card outlived the pointer that asked for it");
    }

    /**
     * The gold card carries a picture block, and the score card does not.
     *
     * <p>Structural rather than artistic: a card with the mine's thumb in it
     * is at least the thumb's height, and the score -- the one count with
     * nothing to photograph -- draws the short text-only card. Measuring the
     * two against each other is what stops this passing on any old paint:
     * if no art were drawn anywhere, the two cards would be the same
     * height.
     */
    @Test
    @DisplayName("the gold card is picture-tall and the score card is text-short")
    void theGoldCardCarriesItsPicture() {
        Scene scene = scene();
        BufferedImage bare = paint(scene.screen());
        java.awt.Rectangle gold = null;
        java.awt.Rectangle score = null;
        for (var cell : scene.panel().topBarForTest()) {
            if (cell.slot() == SidePanel.GOLD_SLOT) {
                gold = cell.bounds();
            }
            if (cell.slot() == 4) {
                score = cell.bounds();
            }
        }
        assertNotNull(gold, "the bar has no gold count");
        assertNotNull(score, "the bar has no score count, so there is no text-only"
                + " card to measure the picture against");

        moveTo(scene, (gold.x + gold.width / 2) * SCALE, (gold.y + 8) * SCALE);
        int goldHeight = changedHeight(bare, paint(scene.screen()));
        moveTo(scene, (score.x + score.width / 2) * SCALE, (score.y + 8) * SCALE);
        int scoreHeight = changedHeight(bare, paint(scene.screen()));

        assertTrue(goldHeight >= 56 * SCALE,
                "the gold card is " + goldHeight + " pixels tall at scale two, which"
                        + " has no room for the mine's 44-pixel picture in it");
        assertTrue(scoreHeight < goldHeight,
                "the score card (" + scoreHeight + ") is as tall as the gold card ("
                        + goldHeight + "), so the height is not the picture and this"
                        + " measurement proves nothing");
    }

    /** The height of the area two frames differ in. */
    private static int changedHeight(BufferedImage a, BufferedImage b) {
        int minY = Integer.MAX_VALUE;
        int maxY = -1;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return maxY < 0 ? 0 : maxY - minY + 1;
    }
}
