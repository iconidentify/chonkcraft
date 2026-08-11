package net.chonkbase.runtime.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class AudioOutputHealthTest {
    @Test
    void legacyScalarImplementationReceivesCompatibleCoherentSnapshot() {
        IOException failure = new IOException("legacy route absent");
        AudioOutputHealth legacy = new AudioOutputHealth() {
            @Override
            public boolean isOutputAvailable() {
                return false;
            }

            @Override
            public boolean recoveryExhausted() {
                return true;
            }

            @Override
            public int reopenAttemptCount() {
                return 6;
            }

            @Override
            public int successfulReopenCount() {
                return 2;
            }

            @Override
            public Throwable lastDeviceFailure() {
                return failure;
            }
        };

        AudioOutputStatus status = legacy.outputStatus();

        assertEquals(
                AudioOutputStatus.RouteState.EXHAUSTED,
                status.state());
        assertEquals(6, status.automaticReopenAttempts());
        assertEquals(2, status.successfulReopens());
        assertEquals(0, status.providerOpenTimeouts());
        assertEquals(failure, status.lastDeviceFailure());
        assertFalse(status.outputAvailable());
    }

    @Test
    void statusRejectsNegativeDiagnosticCounts() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AudioOutputStatus(
                        AudioOutputStatus.RouteState.OPENING,
                        -1,
                        0,
                        0,
                        null));
    }
}
