package net.chonkbase.runtime.audio;

import java.util.Objects;

/**
 * One coherent, read-only snapshot of the platform audio route.
 *
 * <p>The snapshot is presentation diagnostics only. It must never participate
 * in mixer rendering, authored cue selection, simulation state, save data, or
 * deterministic fingerprints.
 */
public record AudioOutputStatus(
        RouteState state,
        int automaticReopenAttempts,
        int successfulReopens,
        int providerOpenTimeouts,
        Throwable lastDeviceFailure) {
    public AudioOutputStatus {
        Objects.requireNonNull(state, "state");
        if (automaticReopenAttempts < 0
                || successfulReopens < 0
                || providerOpenTimeouts < 0) {
            throw new IllegalArgumentException(
                    "audio output diagnostic counts must not be negative");
        }
    }

    public enum RouteState {
        /** The sink or owning session has completed its lifecycle. */
        CLOSED,
        /** Output is configured but intentionally inactive or suspended. */
        STOPPED,
        /** One bounded provider acquisition is pending or in progress. */
        OPENING,
        /** Rendered frames currently reach a live platform route. */
        AVAILABLE,
        /** The next bounded automatic attempt is waiting for its deadline. */
        BACKING_OFF,
        /**
         * The provider exceeded its client-side deadline and has not returned.
         *
         * <p>Java cannot safely terminate an uncooperative native provider
         * call. The single daemon acquisition lane remains bounded while the
         * game and mixer continue.
         */
        PROVIDER_STALLED,
        /** The current activation consumed its automatic recovery budget. */
        EXHAUSTED,
        /** Audio could not establish a usable runtime/session boundary. */
        DISABLED
    }

    public boolean outputAvailable() {
        return state == RouteState.AVAILABLE;
    }

    public boolean recoveryExhausted() {
        return state == RouteState.EXHAUSTED;
    }

    public AudioOutputStatus withState(RouteState replacement) {
        return new AudioOutputStatus(
                replacement,
                automaticReopenAttempts,
                successfulReopens,
                providerOpenTimeouts,
                lastDeviceFailure);
    }

    public static AudioOutputStatus disabled(Throwable failure) {
        return new AudioOutputStatus(
                RouteState.DISABLED, 0, 0, 0, failure);
    }

    public static AudioOutputStatus closed(Throwable failure) {
        return new AudioOutputStatus(
                RouteState.CLOSED, 0, 0, 0, failure);
    }
}
