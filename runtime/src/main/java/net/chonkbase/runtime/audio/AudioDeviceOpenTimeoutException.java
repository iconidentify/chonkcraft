package net.chonkbase.runtime.audio;

import java.io.IOException;

/**
 * Presentation-only evidence that an audio provider exceeded its client-side
 * acquisition deadline.
 *
 * <p>This is intentionally a soft timeout. Java Sound provides no portable
 * cancellation API for a native provider call that ignores interruption.
 */
public final class AudioDeviceOpenTimeoutException extends IOException {
    private final long timeoutMillis;

    public AudioDeviceOpenTimeoutException(long timeoutMillis) {
        super("Audio output provider did not return within "
                + timeoutMillis
                + " ms");
        if (timeoutMillis <= 0L) {
            throw new IllegalArgumentException(
                    "timeoutMillis must be positive");
        }
        this.timeoutMillis = timeoutMillis;
    }

    public long timeoutMillis() {
        return timeoutMillis;
    }
}
