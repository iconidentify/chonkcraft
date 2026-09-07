package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.MapRenderer;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Arrow scrolling used to wait for the simulation's 30 Hz ticks, while minimap
 * dragging moved immediately on mouse events. These drive real key events on
 * a retail map and check travel at display cadence, with the world stopped.
 */
class CameraScrollingTest {
    private record Scene(GameScreen screen, World world) {}

    private static Scene scene() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set -Dchonkcraft.pack=... or wc2.install.dir.");
        GameData data = new GameData(assets);
        var pud = data.campaignMap("campaigns/human/level02h");
        assertNotNull(pud, "the scrolling fixture needs the second human mission");
        var tileset = data.loadTileset(pud.tileset());
        World world = new World(GameMap.from(pud, tileset.tileset()), Player.from(pud));
        var terrain = new MapRenderer(tileset.tileset(), tileset.sheet())
                .render(pud.width(), pud.height(), world.map().tileCodes())
                .toIndexedBufferedImage(tileset.palette());
        var applier = new CommandApplier(world, new ArrayList<>(data.unitTypes().types().values()));
        GameScreen screen = new GameScreen(world, data, terrain, tileset.palette(), "summer", 0,
                640, 480, null, null, null, applier, CommandSink.local(applier), List.of(), "human");
        screen.setSize(640, 480);
        screen.setLayout(data.uiLayout("human", 640, 480));
        screen.setGameScale(1);
        screen.setEdgeScroll(GameScreen.EdgeScroll.NEVER);
        screen.centreOn(20, 20);
        return new Scene(screen, world);
    }

    private static void press(GameScreen screen, int key) {
        assertTrue(screen.keyPressed(new KeyEvent(screen, KeyEvent.KEY_PRESSED,
                0, 0, key, KeyEvent.CHAR_UNDEFINED)), "the map must claim the scrolling key");
    }

    @Test
    @DisplayName("holding an arrow moves the displayed map even when the world is stopped")
    void heldArrowsMoveWithoutSimulationTicks() throws Exception {
        Scene scene = scene();
        GameScreen screen = scene.screen();
        int start = screen.cameraX();
        CountDownLatch moved = new CountDownLatch(1);
        Timer observe = new Timer(10, event -> {
            if (screen.cameraX() > start) {
                moved.countDown();
            }
        });
        try {
            SwingUtilities.invokeAndWait(() -> {
                screen.addNotify();
                press(screen, KeyEvent.VK_RIGHT);
                observe.start();
            });
            assertTrue(moved.await(2, TimeUnit.SECONDS),
                    "holding right left the map still while minimap dragging can already move it");
            assertEquals(0, scene.world().cycle(), "camera travel must not advance the battle");
            SwingUtilities.invokeAndWait(() -> {
                observe.stop();
                screen.removeNotify();
            });
            int removed = screen.cameraX();
            Thread.sleep(100);
            SwingUtilities.invokeAndWait(() -> assertEquals(removed, screen.cameraX(),
                    "a removed game screen must stop scrolling"));
            SwingUtilities.invokeAndWait(screen::addNotify);
            Thread.sleep(100);
            SwingUtilities.invokeAndWait(() -> assertEquals(removed, screen.cameraX(),
                    "returning to the screen must not restore a stale held arrow"));
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                observe.stop();
                screen.removeNotify();
            });
        }
    }

    @Test
    @DisplayName("arrows travel at the same speed with small steps at every display rate")
    void displayCadencePreservesTravelAndFractionalPixels() {
        GameScreen screen = scene().screen();
        press(screen, KeyEvent.VK_RIGHT);
        for (int frames : new int[] {30, 60, 120, 144, 240}) {
            screen.centreOn(20, 20);
            int startX = screen.cameraX();
            int startY = screen.cameraY();
            for (int frame = 1; frame <= frames; frame++) {
                screen.scrollStep(1.0 / frames);
                assertTrue(Math.abs(screen.cameraX() - startX - 240.0 * frame / frames) <= 1,
                        "rightward travel must reach each display frame within one world pixel at "
                                + frames + " frames per second, frame " + frame);
                assertEquals(startY, screen.cameraY(), "holding right must not drift vertically");
            }
            assertEquals(startX + 240, screen.cameraX(),
                    "one second of scrolling must cover 240 world pixels at " + frames + " Hz");
        }
    }

    @Test
    @DisplayName("uneven display frames and key repeats preserve steady diagonal travel")
    void unevenFramesAndKeyRepeatsDoNotChangeSpeed() {
        GameScreen screen = scene().screen();
        press(screen, KeyEvent.VK_RIGHT);
        press(screen, KeyEvent.VK_DOWN);
        int startX = screen.cameraX();
        int startY = screen.cameraY();
        // Each group spans 50 ms, with intervals on both sides of one pixel.
        for (int group = 0; group < 20; group++) {
            for (double seconds : new double[] {0.003, 0.011, 0.007, 0.029}) {
                press(screen, KeyEvent.VK_RIGHT);
                screen.scrollStep(seconds);
            }
        }
        assertEquals(startX + 240, screen.cameraX(), "key repeat must not add horizontal steps");
        assertEquals(startY + 240, screen.cameraY(), "uneven frames must preserve vertical speed");
        screen.keyDown(KeyEvent.VK_RIGHT, false);
        screen.keyDown(KeyEvent.VK_DOWN, false);
        screen.scrollStep(0.01);
        assertEquals(startX + 240, screen.cameraX(), "releasing right must stop without coasting");
        assertEquals(startY + 240, screen.cameraY(), "releasing down must stop without coasting");
    }

    @Test
    @DisplayName("opposite arrows cancel and reversing away from the map edge responds immediately")
    void oppositeArrowsAndMapEdgesDoNotAccumulateTravel() {
        GameScreen screen = scene().screen();
        int start = screen.cameraX();
        press(screen, KeyEvent.VK_RIGHT);
        press(screen, KeyEvent.VK_LEFT);
        screen.scrollStep(0.05);
        assertEquals(start, screen.cameraX(), "opposite arrows must cancel");
        screen.keyDown(KeyEvent.VK_RIGHT, false);
        screen.centreOn(0, 0);
        for (int frame = 0; frame < 100; frame++) {
            screen.scrollStep(0.008);
        }
        assertEquals(0, screen.cameraX(), "leftward travel must stop at the map boundary");
        screen.keyDown(KeyEvent.VK_LEFT, false);
        press(screen, KeyEvent.VK_RIGHT);
        screen.scrollStep(0.008);
        assertEquals(2, screen.cameraX(), "reversing from the edge must move on the next frame");
        press(screen, KeyEvent.VK_UP);
        screen.scrollStep(0.008);
        assertEquals(0, screen.cameraY(), "upward travel must stop at the map boundary");
    }

    @Test
    @DisplayName("an event-thread stall cannot throw the camera across the map")
    void delayedFramesLimitCameraCatchUp() {
        GameScreen screen = scene().screen();
        int start = screen.cameraX();
        press(screen, KeyEvent.VK_RIGHT);
        screen.scrollStep(2);
        assertEquals(start + 12, screen.cameraX(),
                "a long stall must catch up by at most 50 ms of camera travel");
        screen.scrollStep(0.008);
        assertEquals(start + 14, screen.cameraX(), "the next frame must resume ordinary travel");
    }

    @Test
    @DisplayName("minimap dragging still moves immediately after releasing an arrow")
    void minimapDraggingKeepsItsImmediatePosition() {
        GameScreen screen = scene().screen();
        press(screen, KeyEvent.VK_RIGHT);
        screen.scrollStep(0.008);
        screen.keyDown(KeyEvent.VK_RIGHT, false);
        int before = screen.cameraX();
        screen.dispatchEvent(new MouseEvent(screen, MouseEvent.MOUSE_PRESSED, 0, 0,
                80, 80, 1, false, MouseEvent.BUTTON1));
        int clicked = screen.cameraX();
        assertTrue(clicked != before, "the minimap click must move the camera without a display tick");
        screen.dispatchEvent(new MouseEvent(screen, MouseEvent.MOUSE_DRAGGED, 0,
                MouseEvent.BUTTON1_DOWN_MASK, 100, 90, 0, false, MouseEvent.NOBUTTON));
        int dragged = screen.cameraX();
        assertTrue(dragged > clicked, "dragging right on the minimap must move immediately");
        screen.scrollStep(0.008);
        assertEquals(dragged, screen.cameraX(), "the next display frame must retain the minimap position");
    }
}
