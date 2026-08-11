package net.chonkbase.runtime.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

class ControllerSessionTest {
    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void explicitDisableNeverAttemptsNativeStartup() {
        String previous = System.getProperty(ControllerSession.ENABLED_PROPERTY);
        try {
            System.setProperty(ControllerSession.ENABLED_PROPERTY, "false");
            LastInputFamilyTracker families = new LastInputFamilyTracker();
            try (ControllerSession session = ControllerSession.startDefault(
                    action -> {
                        throw new AssertionError("disabled session delivered input");
                    },
                    families,
                    () -> {
                        throw new AssertionError("disabled session reported resume");
                    })) {
                assertEquals(ControllerSession.Status.DISABLED, session.status());
                assertFalse(session.nativeBackendRunning());
                assertEquals(0, session.connectedControllers().size());
            }
        } finally {
            if (previous == null) {
                System.clearProperty(ControllerSession.ENABLED_PROPERTY);
            } else {
                System.setProperty(ControllerSession.ENABLED_PROPERTY, previous);
            }
        }
    }
}
