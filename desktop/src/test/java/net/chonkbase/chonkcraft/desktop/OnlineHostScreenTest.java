package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Attempt ownership keeps a slow online worker from stranding or replacing the player's UI. */
class OnlineHostScreenTest {

    @Test
    void cancelRejectsLateSuccess() {
        OnlineHostScreen screen = screen();
        long attempt = screen.connecting();

        screen.cancelAttempt();

        assertFalse(screen.complete(attempt));
    }

    @Test
    void retryRejectsTheOlderWorkerButAcceptsTheNewOne() {
        OnlineHostScreen screen = screen();
        long old = screen.connecting();
        long current = screen.connecting();

        assertFalse(screen.complete(old));
        assertTrue(screen.complete(current));
    }

    @Test
    void staleProgressAndFailureCannotOverwriteTheCurrentAttempt() {
        OnlineHostScreen screen = screen();
        long old = screen.connecting();
        long current = screen.connecting();

        screen.progress(old, "stale progress");
        screen.failed(old, "stale failure");

        assertFalse(screen.failedForTest());
        assertEquals("Creating a room...", screen.detailForTest());

        screen.failed(current, "relay unavailable");
        assertTrue(screen.failedForTest());
        assertEquals("relay unavailable", screen.detailForTest());
    }

    private static OnlineHostScreen screen() {
        return new OnlineHostScreen(null, "Cramped BNE.pud", new OnlineHostScreen.Listener() {
            @Override
            public void onRetry() {
            }

            @Override
            public void onLocal() {
            }

            @Override
            public void onCancel() {
            }
        });
    }
}
