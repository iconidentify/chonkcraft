package net.chonkbase.runtime;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * Puts the window full screen and takes it back out again.
 *
 * <p>Follows the state machine ChonkBlocker settled on, and the reason it is
 * worth following is one thing it refuses to do: it never asks
 * {@code GraphicsDevice.setFullScreenWindow} for an exclusive lease on macOS.
 *
 * <p>The version this replaces tried the native path and fell back to that
 * exclusive lease when the native one declined. Both can be half engaged at
 * once, and then they disagree about what the window is: AppKit believes the
 * frame is in a Space, AWT believes it holds the display, and leaving by either
 * route leaves the other still holding on. The window stops redrawing and can
 * be neither moved nor closed. Falling back to something worse than the thing
 * that failed is not a fallback.
 *
 * <p>So there are two strategies and no path between them. On macOS the frame
 * is decorated and AppKit's own fullscreen Space does the work through EAWT,
 * with the mode committed when AppKit reports the transition finished rather
 * than when it was asked to begin. Everywhere else the frame is undecorated and
 * the toggle sets its bounds to the monitor it is on: no window is destroyed,
 * no decoration is flipped, and the native handle is stable for the session.
 */
public final class PlatformFullscreen {

    /** Which way this platform goes full screen. */
    public enum Strategy {
        /** macOS: AppKit's own fullscreen Space, through EAWT. */
        MAC_EAWT,
        /** Everywhere else: an undecorated window sized to the monitor. */
        BORDERLESS_BOUNDS
    }

    /** The only source of truth about where the window is. */
    private enum Mode {
        WINDOWED,
        FULLSCREEN
    }

    /** Told after every committed change, on the event thread. */
    public interface Listener {
        void onFullscreenChange(boolean nowFullscreen);
    }

    /**
     * Forces the borderless path even on macOS, so a test can exercise the
     * same code on any machine.
     */
    public static final String FORCE_BORDERLESS_PROPERTY = "chonk.fullscreen.forceBorderless";

    /**
     * How long a second toggle is ignored for.
     *
     * <p>The mode commits when AppKit finishes animating, so a toggle arriving
     * before that would work out the same target again and put the window
     * straight back where it came from.
     */
    private static final long TOGGLE_DEBOUNCE_NANOS = 400_000_000L;

    private final JFrame frame;
    private final Strategy strategy;
    private final Listener listener;

    private volatile Mode mode = Mode.WINDOWED;

    /** The monitor the window is on now, so the toggle covers the right one. */
    private volatile GraphicsConfiguration currentConfiguration;

    /** Where the window was before it went full screen. */
    private Rectangle savedBounds;

    private boolean eawtArmed;

    private volatile long lastToggleNanos;

    private PlatformFullscreen(JFrame frame, Listener listener) {
        this.frame = frame;
        this.listener = listener;
        this.strategy = detectStrategy(System.getProperty("os.name", ""),
                Boolean.getBoolean(FORCE_BORDERLESS_PROPERTY));
        this.currentConfiguration = frame.getGraphicsConfiguration();

        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent event) {
                GraphicsConfiguration candidate = frame.getGraphicsConfiguration();
                if (candidate != null) {
                    currentConfiguration = candidate;
                }
            }
        });

        if (strategy == Strategy.MAC_EAWT) {
            frame.getRootPane().putClientProperty("apple.awt.fullscreenable", Boolean.TRUE);
            frame.setResizable(true);
            eawtArmed = armEawt();
        }
    }

    public static PlatformFullscreen install(JFrame frame) {
        return new PlatformFullscreen(frame, null);
    }

    public static PlatformFullscreen install(JFrame frame, Listener listener) {
        return new PlatformFullscreen(frame, listener);
    }

    /** Which strategy an operating system gets. */
    public static Strategy detectStrategy(String osName, boolean forceBorderless) {
        if (forceBorderless || osName == null) {
            return Strategy.BORDERLESS_BOUNDS;
        }
        return osName.toLowerCase().startsWith("mac")
                ? Strategy.MAC_EAWT
                : Strategy.BORDERLESS_BOUNDS;
    }

    public Strategy strategy() {
        return strategy;
    }

    public boolean isFullscreen() {
        return mode == Mode.FULLSCREEN;
    }

    public void toggle() {
        runOnEdt(this::toggleOnEdt);
    }

    private void toggleOnEdt() {
        long now = System.nanoTime();
        if (lastToggleNanos != 0L && now - lastToggleNanos < TOGGLE_DEBOUNCE_NANOS) {
            return;
        }
        lastToggleNanos = now;

        if (strategy == Strategy.MAC_EAWT && eawtArmed) {
            // Asked, not done: AppKit animates, and the mode is committed when
            // it reports the transition finished.
            if (requestEawtToggle()) {
                return;
            }
            // This runtime cannot service it. Degrade to the borderless path,
            // which is a lesser experience and a working window, and never to
            // an exclusive lease, which is how this used to wedge.
            eawtArmed = false;
        }
        if (mode == Mode.FULLSCREEN) {
            leaveBorderless();
        } else {
            enterBorderless();
        }
    }

    private void enterBorderless() {
        savedBounds = new Rectangle(frame.getBounds());
        GraphicsConfiguration configuration = currentConfiguration;
        if (configuration == null) {
            configuration = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice()
                    .getDefaultConfiguration();
        }
        frame.setBounds(configuration.getBounds());
        commit(Mode.FULLSCREEN);
    }

    private void leaveBorderless() {
        if (savedBounds != null) {
            frame.setBounds(savedBounds);
        }
        commit(Mode.WINDOWED);
    }

    /** Records a change and tells whoever is listening. */
    private void commit(Mode wanted) {
        if (mode == wanted) {
            return;
        }
        mode = wanted;
        if (listener != null) {
            listener.onFullscreenChange(wanted == Mode.FULLSCREEN);
        }
    }

    private boolean armEawt() {
        try {
            Class<?> utilities = Class.forName("com.apple.eawt.FullScreenUtilities");
            Class<?> listenerType = Class.forName("com.apple.eawt.FullScreenListener");
            Object proxy = Proxy.newProxyInstance(
                    listenerType.getClassLoader(),
                    new Class<?>[] {listenerType},
                    new AppKitListener());
            utilities.getMethod("setWindowCanFullScreen", Window.class, boolean.class)
                    .invoke(null, frame, true);
            utilities.getMethod("addFullScreenListenerTo", Window.class, listenerType)
                    .invoke(null, frame, proxy);
            return true;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            System.err.println("Native macOS fullscreen unavailable: " + unavailable);
            return false;
        }
    }

    private boolean requestEawtToggle() {
        try {
            Class<?> applicationType = Class.forName("com.apple.eawt.Application");
            Object application = applicationType.getMethod("getApplication").invoke(null);
            applicationType.getMethod("requestToggleFullScreen", Window.class)
                    .invoke(application, frame);
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            System.err.println("Native macOS fullscreen toggle failed: " + failure);
            return false;
        }
    }

    /**
     * AppKit's own account of what happened.
     *
     * <p>Only "entered" and "exited" commit anything. The two events that fire
     * when the animation begins are not the truth: treating them as the truth
     * leaves the mode wrong for the third of a second the animation lasts,
     * which is exactly when a repaint asks.
     */
    private final class AppKitListener implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            switch (method.getName()) {
                case "windowEnteredFullScreen" -> commit(Mode.FULLSCREEN);
                case "windowExitedFullScreen" -> commit(Mode.WINDOWED);
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "equals" -> {
                    return proxy == arguments[0];
                }
                case "toString" -> {
                    return "PlatformFullscreen.AppKitListener";
                }
                default -> {
                    // "Will enter" and "will exit" start the animation and
                    // commit nothing.
                }
            }
            return null;
        }
    }

    private static void runOnEdt(Runnable operation) {
        if (SwingUtilities.isEventDispatchThread()) {
            operation.run();
        } else {
            SwingUtilities.invokeLater(operation);
        }
    }
}
