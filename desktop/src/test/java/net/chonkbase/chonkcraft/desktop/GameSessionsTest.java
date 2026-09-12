package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A replaced loader and its delayed callbacks must not revive a discarded game. */
class GameSessionsTest {
    @Test
    @DisplayName("replacement retires the result timer and loop before their audio device")
    void replacementClosesTheEntireGameInOrder() {
        GameSessions games = new GameSessions();
        var old = games.begin();
        List<String> closed = new ArrayList<>();
        old.closeWith(() -> closed.add("audio"));
        old.closeWith(() -> closed.add("loop"));
        old.closeWith(() -> closed.add("result"));
        var next = games.begin();
        assertEquals(List.of("result", "loop", "audio"), closed,
                "the old loop must stop before its device is closed");
        old.close();
        assertTrue(next.isCurrent(), "late old cleanup closed the replacement");
        assertEquals(3, closed.size(), "each resource must close once");
    }

    @Test
    @DisplayName("a late loader cannot publish or retain resources after cancellation")
    void lateWorkCannotReviveAReplacedSession() {
        GameSessions games = new GameSessions();
        var old = games.begin();
        games.begin();
        AtomicInteger releases = new AtomicInteger();
        AtomicInteger screens = new AtomicInteger();
        assertFalse(old.closeWith(releases::incrementAndGet),
                "a cancelled loader retained its late audio device");
        assertFalse(old.runIfCurrent(screens::incrementAndGet),
                "a queued callback revived the cancelled screen");
        assertEquals(1, releases.get(), "the late resource must be released");
        assertEquals(0, screens.get(), "a stale result must remain invisible");
    }
}
