package net.chonkbase.runtime.audio;

/**
 * Read-only diagnostics for an output sink that can recover its platform route.
 *
 * <p>These values are presentation diagnostics only. They never participate in
 * mixer rendering, simulation state, save data, or authored cue selection.
 */
public interface AudioOutputHealth {
    /**
     * One internally consistent snapshot of route state and counters.
     *
     * <p>The default preserves compatibility for output sinks implementing the
     * original scalar health contract. Recovering sinks should override this
     * method when they can publish a stronger atomic snapshot.
     */
    default AudioOutputStatus outputStatus() {
        AudioOutputStatus.RouteState state = isOutputAvailable()
                ? AudioOutputStatus.RouteState.AVAILABLE
                : recoveryExhausted()
                        ? AudioOutputStatus.RouteState.EXHAUSTED
                        : AudioOutputStatus.RouteState.BACKING_OFF;
        return new AudioOutputStatus(
                state,
                reopenAttemptCount(),
                successfulReopenCount(),
                providerOpenTimeoutCount(),
                lastDeviceFailure());
    }

    /** Whether rendered frames currently have a live platform output route. */
    boolean isOutputAvailable();

    /** Whether this activation consumed its bounded automatic reopen budget. */
    boolean recoveryExhausted();

    /** Automatic reopen attempts used since the most recent explicit start. */
    int reopenAttemptCount();

    /** Successful automatic reopens since this sink instance was created. */
    int successfulReopenCount();

    /** Provider acquisitions that exceeded their client-side deadline. */
    default int providerOpenTimeoutCount() {
        return 0;
    }

    /** Most recent platform open/start/write failure, including recovered failures. */
    Throwable lastDeviceFailure();
}
