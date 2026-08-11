package net.chonkbase.runtime.input;

/**
 * Tracks the last family that produced real input. Connection alone never
 * steals controller prompts from keyboard or pointer users.
 */
public final class LastInputFamilyTracker {
    private volatile InputFamily family = InputFamily.UNKNOWN;
    private volatile String controllerName;
    private volatile long changedAtMillis;

    public void recordKeyboard() {
        mark(InputFamily.KEYBOARD, null);
    }

    public void recordPointer() {
        mark(InputFamily.POINTER, null);
    }

    public void recordController(String name) {
        mark(InputFamily.CONTROLLER, name);
    }

    public Snapshot snapshot() {
        return new Snapshot(family, controllerName, changedAtMillis);
    }

    private void mark(InputFamily nextFamily, String nextControllerName) {
        if (nextControllerName != null && !nextControllerName.isBlank()) {
            controllerName = nextControllerName;
        }
        family = nextFamily;
        changedAtMillis = System.currentTimeMillis();
    }

    public enum InputFamily {
        UNKNOWN,
        KEYBOARD,
        POINTER,
        CONTROLLER
    }

    public record Snapshot(
            InputFamily family,
            String controllerName,
            long changedAtMillis) {}
}
