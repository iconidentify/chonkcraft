package net.chonkbase.chonkcraft.desktop;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns the one game that may advance and replace the application's screen.
 *
 * <p>Repeated loads used to leave two simulation threads alive. The hidden
 * world's attack cries could be heard over the visible map, and its eventual
 * defeat replaced a healthy mission's screen. Per-button guards cannot protect
 * a resource or delayed result created by a load that has already been replaced.
 * Each startup therefore owns a revocable session, including its loop, audio,
 * connection and pending result. Replacing it closes those resources before
 * the next session can publish anything.
 */
final class GameSessions {
    private volatile Session current;

    synchronized Session begin() {
        close();
        Session next = new Session();
        current = next;
        return next;
    }

    synchronized void close() {
        if (current != null) {
            current.close();
        }
    }

    final class Session implements AutoCloseable {
        private final List<Runnable> cleanup = new ArrayList<>();
        private volatile boolean closed;

        boolean isCurrent() {
            return !closed && current == this;
        }

        /** A late-created resource is closed immediately if its load was replaced. */
        boolean closeWith(Runnable release) {
            synchronized (GameSessions.this) {
                if (isCurrent()) {
                    cleanup.add(release);
                    return true;
                }
                release.run();
                return false;
            }
        }

        /** Serializes screen publication with replacement, including queued callbacks. */
        boolean runIfCurrent(Runnable action) {
            synchronized (GameSessions.this) {
                if (!isCurrent()) {
                    return false;
                }
                action.run();
                return true;
            }
        }

        @Override
        public void close() {
            synchronized (GameSessions.this) {
                if (closed) {
                    return;
                }
                closed = true;
                if (current == this) {
                    current = null;
                }
                // The loop is registered after its devices, so it stops before
                // they disappear. A result timer is newer still and stops first.
                for (int i = cleanup.size() - 1; i >= 0; i--) {
                    try {
                        cleanup.get(i).run();
                    } catch (RuntimeException failure) {
                        System.err.println("Could not close game resource: " + failure);
                    }
                }
                cleanup.clear();
            }
        }
    }
}
