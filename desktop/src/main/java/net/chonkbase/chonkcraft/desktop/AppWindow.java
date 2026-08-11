package net.chonkbase.chonkcraft.desktop;

import java.awt.Component;
import java.awt.event.KeyListener;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import net.chonkbase.runtime.PlatformFullscreen;

/**
 * The one window the game lives in.
 *
 * <p>Every screen -- the title cutscenes, the menu, a briefing, the game
 * itself, the result page -- used to build a window of its own and throw away
 * the one before it. That is not how a game behaves. The window is the
 * player's: they sized it, they moved it, they put it full screen, and
 * replacing it discards all three. Watching a cutscene full screen and being
 * dropped back into a small window on a different part of the desktop is the
 * visible half of it; losing the position and the size is the rest.
 *
 * <p>So there is one frame for the life of the process and screens are swapped
 * into it. Nothing here disposes anything.
 *
 * <p>The key listener is held here too, because it has to be swapped with the
 * screen rather than accumulated: a frame that is never thrown away keeps
 * every listener ever added to it, and the game's keyboard handler would go on
 * answering keys pressed at the main menu.
 */
final class AppWindow {

    private final JFrame frame;
    private final PlatformFullscreen fullscreen;
    private KeyListener keys;

    /**
     * Told whenever the window enters or leaves full screen.
     *
     * <p>Held as a mutable delegate because the fullscreen controller takes
     * its listener at construction, and the screen that wants to hear about it
     * does not exist yet at that point.
     */
    private volatile PlatformFullscreen.Listener onFullscreenChange;

    AppWindow(String title, int width, int height) {
        frame = new JFrame(title);
        DesktopApplicationIdentity.install(frame);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(width, height);
        frame.setLocationRelativeTo(null);
        frame.setFocusable(true);
        fullscreen = PlatformFullscreen.install(frame, nowFullscreen -> {
            PlatformFullscreen.Listener told = onFullscreenChange;
            if (told != null) {
                told.onFullscreenChange(nowFullscreen);
            }
        });
    }

    JFrame frame() {
        return frame;
    }

    PlatformFullscreen fullscreen() {
        return fullscreen;
    }

    /** Puts a screen up, keeping the window's size, place and full-screen state. */
    void show(JComponent content) {
        frame.setContentPane(content);
        // revalidate and repaint rather than pack: packing would resize the
        // window to the new screen's preferred size, which is the same
        // discourtesy as replacing it.
        frame.revalidate();
        frame.repaint();
        if (!frame.isVisible()) {
            frame.setVisible(true);
        }
        // The content first, and the frame if the content will not take it.
        // A screen that reads its own keys is focusable and wants it; one that
        // does not leaves the frame holding the keyboard, which is where its
        // listener is.
        SwingUtilities.invokeLater(() -> {
            if (!content.requestFocusInWindow()) {
                frame.requestFocusInWindow();
            }
        });
    }

    /** Registers what to do when the window changes between window and screen. */
    void onFullscreenChange(PlatformFullscreen.Listener listener) {
        this.onFullscreenChange = listener;
    }

    /** Retitles the window without disturbing it. */
    void setTitle(String title) {
        frame.setTitle(title);
    }

    /**
     * Replaces the keyboard handler.
     *
     * <p>Passing null leaves the window with none, which is what every screen
     * that reads its own keys wants.
     *
     * <p>Attached to the content as well as to the frame, because only one of
     * the two is ever the focus owner and which one it is depends on whether
     * the screen on show is focusable. A listener on the frame alone is silent
     * the moment anything inside it takes focus, and a listener on the content
     * alone is silent until something does. That is what made the scroll keys
     * work in one session and not the next: nothing about them changed, only
     * where the focus happened to land.
     */
    void setKeyListener(KeyListener listener) {
        if (keys != null) {
            frame.removeKeyListener(keys);
            Component content = frame.getContentPane();
            if (content != null) {
                content.removeKeyListener(keys);
            }
        }
        keys = listener;
        if (listener != null) {
            frame.addKeyListener(listener);
            Component content = frame.getContentPane();
            if (content != null) {
                content.addKeyListener(listener);
            }
        }
    }

    /** The size a screen should lay itself out for. */
    int contentWidth() {
        Component content = frame.getContentPane();
        int width = content == null ? 0 : content.getWidth();
        return width > 0 ? width : frame.getWidth();
    }

    int contentHeight() {
        Component content = frame.getContentPane();
        int height = content == null ? 0 : content.getHeight();
        return height > 0 ? height : frame.getHeight();
    }
}
