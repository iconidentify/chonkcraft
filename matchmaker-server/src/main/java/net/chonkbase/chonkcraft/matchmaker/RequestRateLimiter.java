package net.chonkbase.chonkcraft.matchmaker;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/** Small per-source fixed-window guard for unauthenticated browse/create/join endpoints. */
final class RequestRateLimiter {

    private static final long WINDOW_MILLIS = 60_000;

    private record Key(String address, String lane) {
    }

    private static final class Window {
        private long began;
        private int requests;
    }

    private final Clock clock;
    private final Map<Key, Window> windows = new HashMap<>();

    RequestRateLimiter() {
        this(Clock.systemUTC());
    }

    RequestRateLimiter(Clock clock) {
        this.clock = clock;
    }

    synchronized boolean allow(SocketAddress source, String lane, int limit) {
        return allow(address(source), lane, limit);
    }

    synchronized boolean allow(String source, String lane, int limit) {
        long now = clock.millis();
        Key key = new Key(source, lane);
        Window window = windows.computeIfAbsent(key, ignored -> new Window());
        if (now - window.began >= WINDOW_MILLIS) {
            window.began = now;
            window.requests = 0;
        }
        window.requests++;
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().began >= WINDOW_MILLIS);
        }
        return window.requests <= limit;
    }

    private static String address(SocketAddress source) {
        return source instanceof InetSocketAddress inet && inet.getAddress() != null
                ? inet.getAddress().getHostAddress() : String.valueOf(source);
    }
}
