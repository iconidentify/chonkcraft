package net.chonkbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JFrame;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Going full screen and coming back.
 *
 * <p>The property under test is mostly a negative one: on macOS the exclusive
 * display lease must never be taken. Holding it at the same time as an AppKit
 * fullscreen Space leaves the two disagreeing about what the window is, and
 * leaving by either route leaves the other still holding on -- a window that
 * stops redrawing and can be neither moved nor closed.
 */
class PlatformFullscreenTest {

    @Test
    @DisplayName("macOS gets the native path and everything else gets borderless")
    void eachPlatformGetsItsStrategy() {
        assertEquals(PlatformFullscreen.Strategy.MAC_EAWT,
                PlatformFullscreen.detectStrategy("Mac OS X", false));
        assertEquals(PlatformFullscreen.Strategy.BORDERLESS_BOUNDS,
                PlatformFullscreen.detectStrategy("Windows 11", false));
        assertEquals(PlatformFullscreen.Strategy.BORDERLESS_BOUNDS,
                PlatformFullscreen.detectStrategy("Linux", false));
        assertEquals(PlatformFullscreen.Strategy.BORDERLESS_BOUNDS,
                PlatformFullscreen.detectStrategy(null, false));
    }

    @Test
    @DisplayName("there is no third strategy to fall into")
    void thereIsNoExclusiveStrategy() {
        // The wedge came from a fallback that engaged an exclusive lease when
        // the native path declined. There is no longer a value that could
        // select one.
        assertEquals(2, PlatformFullscreen.Strategy.values().length,
                "a third strategy has appeared: " + java.util.Arrays.toString(
                        PlatformFullscreen.Strategy.values()));
    }

    @Test
    @DisplayName("the override forces borderless on every platform")
    void theOverrideWins() {
        // So a test on a developer's Mac exercises the same code the players
        // on other machines run.
        assertEquals(PlatformFullscreen.Strategy.BORDERLESS_BOUNDS,
                PlatformFullscreen.detectStrategy("Mac OS X", true));
    }

    @Test
    @DisplayName("borderless goes out and comes back to where it started")
    void theRoundTripRestoresTheWindow() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "no display");
        String saved = System.getProperty(PlatformFullscreen.FORCE_BORDERLESS_PROPERTY);
        System.setProperty(PlatformFullscreen.FORCE_BORDERLESS_PROPERTY, "true");
        try {
            JFrame frame = new JFrame("fullscreen test");
            frame.setBounds(120, 90, 640, 480);
            AtomicInteger changes = new AtomicInteger();
            PlatformFullscreen fullscreen = PlatformFullscreen.install(frame,
                    now -> changes.incrementAndGet());
            assertEquals(PlatformFullscreen.Strategy.BORDERLESS_BOUNDS, fullscreen.strategy());
            assertFalse(fullscreen.isFullscreen());

            var before = frame.getBounds();
            fullscreen.toggle();
            assertTrue(fullscreen.isFullscreen(), "did not go full screen");
            assertEquals(1, changes.get(), "the listener was not told");

            // The debounce would swallow an immediate second toggle, and that
            // is deliberate; wait it out rather than work around it.
            sleepPastDebounce();
            fullscreen.toggle();
            assertFalse(fullscreen.isFullscreen(), "did not come back out");
            assertEquals(before, frame.getBounds(),
                    "the window did not return to where the player left it");
            assertEquals(2, changes.get());
            frame.dispose();
        } finally {
            if (saved == null) {
                System.clearProperty(PlatformFullscreen.FORCE_BORDERLESS_PROPERTY);
            } else {
                System.setProperty(PlatformFullscreen.FORCE_BORDERLESS_PROPERTY, saved);
            }
        }
    }

    @Test
    @DisplayName("a second toggle inside the debounce is ignored")
    void rapidTogglesAreDebounced() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "no display");
        String saved = System.getProperty(PlatformFullscreen.FORCE_BORDERLESS_PROPERTY);
        System.setProperty(PlatformFullscreen.FORCE_BORDERLESS_PROPERTY, "true");
        try {
            JFrame frame = new JFrame("fullscreen debounce");
            frame.setBounds(0, 0, 400, 300);
            PlatformFullscreen fullscreen = PlatformFullscreen.install(frame);
            fullscreen.toggle();
            assertTrue(fullscreen.isFullscreen());
            fullscreen.toggle();
            assertTrue(fullscreen.isFullscreen(),
                    "a toggle arriving during the animation put the window straight back");
            frame.dispose();
        } finally {
            if (saved == null) {
                System.clearProperty(PlatformFullscreen.FORCE_BORDERLESS_PROPERTY);
            } else {
                System.setProperty(PlatformFullscreen.FORCE_BORDERLESS_PROPERTY, saved);
            }
        }
    }

    private static void sleepPastDebounce() {
        try {
            Thread.sleep(450);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("installing without a listener is allowed")
    void aListenerIsOptional() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "no display");
        JFrame frame = new JFrame("no listener");
        assertNotNull(PlatformFullscreen.install(frame));
        frame.dispose();
    }
}
