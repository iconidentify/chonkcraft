package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JPanel;
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
    @DisplayName("swapping screens keeps the same window")
    void theWindowSurvivesAScreenChange() {
        AppWindow window = window();
        var frame = window.frame();
        window.show(new JPanel());
        window.show(new JPanel());
        assertSame(frame, window.frame(), "the window must not be replaced");
        assertTrue(frame.isDisplayable(), "the window must not be disposed");
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
