package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One window for the life of the game.
 *
 * <p>Every screen used to build a window of its own and throw away the one
 * before it, which discarded whatever the player had done to it. The visible
 * half was watching a cutscene full screen and being dropped back into a small
 * window afterwards; the rest was losing the size and the position too.
 */
class AppWindowTest {

    private static AppWindow window() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "no display to put a window on");
        return new AppWindow("test", 800, 600);
    }

    @Test
    @DisplayName("swapping screens redraws the new screen inside the same window")
    void theWindowSurvivesAScreenChange() throws Exception {
        AppWindow window = window();
        var frame = window.frame();
        CountDownLatch presented = new CountDownLatch(1);
        JPanel next = new JPanel() {
            @Override
            public void paintImmediately(int x, int y, int width, int height) {
                super.paintImmediately(x, y, width, height);
                if (isShowing() && getWidth() > 0 && getHeight() > 0
                        && x == 0 && y == 0 && width == getWidth() && height == getHeight()) {
                    presented.countDown();
                }
            }
        };
        try {
            SwingUtilities.invokeAndWait(() -> window.show(new JPanel()));
            SwingUtilities.invokeAndWait(() -> window.show(next));
            // A native frame repaint reached paintComponent but left the old
            // End Scenario picture visible with the main menu already loaded.
            // A full Swing component repaint recovered the live game, so the
            // transition must reach that presentation path after layout.
            assertTrue(presented.await(2, TimeUnit.SECONDS),
                    "the new screen needs a full component repaint after it has a visible size");
            assertSame(frame, window.frame(), "the window must not be replaced");
            assertTrue(frame.isDisplayable(), "the window must not be disposed");
        } finally {
            SwingUtilities.invokeAndWait(frame::dispose);
        }
    }

    @Test
    @DisplayName("swapping screens keeps the size the player chose")
    void theSizeSurvives() {
        AppWindow window = window();
        window.show(new JPanel());
        window.frame().setSize(1024, 768);
        int width = window.frame().getWidth();
        int height = window.frame().getHeight();

        // A screen with a quite different preferred size must not resize the
        // window under the player: showing must not pack.
        JPanel wants = new JPanel();
        wants.setPreferredSize(new java.awt.Dimension(320, 240));
        window.show(wants);

        assertEquals(width, window.frame().getWidth());
        assertEquals(height, window.frame().getHeight());
    }

    @Test
    @DisplayName("the keyboard handler is replaced, not stacked")
    void listenersDoNotAccumulate() {
        // A window that is never thrown away keeps every listener ever added,
        // so the game's handler would go on answering keys pressed at the menu.
        AppWindow window = window();
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        window.setKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                first.incrementAndGet();
            }
        });
        window.setKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                second.incrementAndGet();
            }
        });

        assertEquals(1, window.frame().getKeyListeners().length,
                "the old handler is still attached");

        KeyEvent press = new KeyEvent(window.frame(), KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(), 0, KeyEvent.VK_A, 'a');
        for (var listener : window.frame().getKeyListeners()) {
            listener.keyPressed(press);
        }
        assertEquals(0, first.get(), "the replaced handler still fired");
        assertEquals(1, second.get());

        window.setKeyListener(null);
        assertEquals(0, window.frame().getKeyListeners().length);
    }

    @Test
    @DisplayName("a fresh window is a different window")
    void separateWindowsAreSeparate() {
        // The guard on the guard: these tests would pass trivially if the
        // constructor handed back a shared instance.
        assertNotSame(window().frame(), window().frame());
    }
}
