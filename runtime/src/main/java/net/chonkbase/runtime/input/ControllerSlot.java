package net.chonkbase.runtime.input;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.function.Consumer;

/** Per-slot raw state, hysteresis, bounded action queue, and menu repeat. */
final class ControllerSlot {
    static final float STICK_ENGAGE_THRESHOLD = 0.40f;
    static final float STICK_RELEASE_THRESHOLD = 0.20f;
    static final long EDGE_DEBOUNCE_MS = 4L;
    static final int MAX_ACTIONS = 32;

    private final int slotIndex;
    private final ArrayDeque<SemanticAction> actions = new ArrayDeque<>();
    private final MenuRepeatState repeat = new MenuRepeatState();
    private final boolean[] buttonHeld = new boolean[ControllerCodes.BUTTON_MAX];
    private final boolean[] directionHeld = new boolean[SemanticAction.values().length];
    private final long[] lastDirectionEdgeMs = new long[SemanticAction.values().length];

    private volatile ControllerDevice controller;
    private boolean stickLeft;
    private boolean stickRight;
    private boolean stickUp;
    private boolean stickDown;
    private boolean awaitingRelease;

    ControllerSlot(int slotIndex) {
        if (slotIndex < 0) {
            throw new IllegalArgumentException("slotIndex must be non-negative");
        }
        this.slotIndex = slotIndex;
        Arrays.fill(lastDirectionEdgeMs, Long.MIN_VALUE);
    }

    int slotIndex() {
        return slotIndex;
    }

    ControllerDevice controller() {
        return controller;
    }

    boolean ownsInstance(int instanceId) {
        ControllerDevice active = controller;
        return active != null && active.instanceId() == instanceId;
    }

    boolean isConnected(ControllerBackend backend) {
        ControllerDevice active = controller;
        return active != null && backend.isAttached(active);
    }

    void bind(ControllerDevice device) {
        ControllerDevice previous = controller;
        controller = device;
        if (device != null) {
            device.attached = true;
        }
        boolean sameInstance = previous != null
                && device != null
                && previous.instanceId() == device.instanceId();
        if (!sameInstance) {
            resetAllState();
        }
    }

    ControllerDevice unbind() {
        ControllerDevice previous = controller;
        controller = null;
        resetAllState();
        return previous;
    }

    void handleButton(int button, boolean pressed, long nowMs, boolean actionsAllowed) {
        if (button < 0 || button >= buttonHeld.length) {
            return;
        }
        if (buttonHeld[button] == pressed) {
            return;
        }
        buttonHeld[button] = pressed;

        if (!actionsAllowed) {
            clearDeliveryState();
            return;
        }
        if (awaitingRelease) {
            if (allPhysicalControlsReleased()) {
                awaitingRelease = false;
            }
            return;
        }

        if (isDirectionalButton(button)) {
            updateDirections(nowMs);
            return;
        }
        if (!pressed) {
            return;
        }

        SemanticAction action = switch (button) {
            case ControllerCodes.BUTTON_A -> SemanticAction.CONFIRM;
            case ControllerCodes.BUTTON_B, ControllerCodes.BUTTON_BACK -> SemanticAction.BACK;
            case ControllerCodes.BUTTON_START -> SemanticAction.PAUSE;
            case ControllerCodes.BUTTON_LEFT_SHOULDER -> SemanticAction.SPEED_SLOW;
            case ControllerCodes.BUTTON_X -> SemanticAction.SPEED_NORMAL;
            case ControllerCodes.BUTTON_RIGHT_SHOULDER -> SemanticAction.SPEED_FAST;
            case ControllerCodes.BUTTON_Y -> SemanticAction.TOGGLE_FULLSCREEN;
            default -> null;
        };
        enqueue(action);
    }

    void handleAxis(int axis, int rawValue, long nowMs, boolean actionsAllowed) {
        float normalized = normalizeAxis(rawValue);
        if (axis == ControllerCodes.AXIS_LEFT_X) {
            stickLeft = negativeAxisHeld(stickLeft, normalized);
            stickRight = positiveAxisHeld(stickRight, normalized);
        } else if (axis == ControllerCodes.AXIS_LEFT_Y) {
            stickUp = negativeAxisHeld(stickUp, normalized);
            stickDown = positiveAxisHeld(stickDown, normalized);
        } else {
            return;
        }

        if (!actionsAllowed) {
            clearDeliveryState();
            return;
        }
        if (awaitingRelease) {
            if (allPhysicalControlsReleased()) {
                awaitingRelease = false;
            }
            return;
        }
        updateDirections(nowMs);
    }

    void maybeRepeat(long nowMs) {
        if (awaitingRelease) {
            return;
        }
        enqueue(repeat.poll(nowMs));
    }

    void drainActions(Consumer<SemanticAction> sink) {
        SemanticAction action;
        while ((action = actions.poll()) != null) {
            sink.accept(action);
        }
    }

    void clearForSuppression() {
        clearDeliveryState();
    }

    void requestReleaseBeforeNextPress() {
        clearDeliveryState();
        awaitingRelease = true;
    }

    void observePhysicalControlState(ControllerBackend.PhysicalControlState state) {
        if (!awaitingRelease || !allPhysicalControlsReleased()) {
            return;
        }
        if (state != ControllerBackend.PhysicalControlState.ACTIVE) {
            awaitingRelease = false;
        }
    }

    boolean awaitingRelease() {
        return awaitingRelease;
    }

    private void updateDirections(long nowMs) {
        transitionDirection(
                SemanticAction.NAVIGATE_UP,
                buttonHeld[ControllerCodes.BUTTON_DPAD_UP] || stickUp,
                nowMs);
        transitionDirection(
                SemanticAction.NAVIGATE_DOWN,
                buttonHeld[ControllerCodes.BUTTON_DPAD_DOWN] || stickDown,
                nowMs);
        transitionDirection(
                SemanticAction.NAVIGATE_LEFT,
                buttonHeld[ControllerCodes.BUTTON_DPAD_LEFT] || stickLeft,
                nowMs);
        transitionDirection(
                SemanticAction.NAVIGATE_RIGHT,
                buttonHeld[ControllerCodes.BUTTON_DPAD_RIGHT] || stickRight,
                nowMs);
    }

    private void transitionDirection(SemanticAction action, boolean held, long nowMs) {
        int index = action.ordinal();
        boolean previous = directionHeld[index];
        if (previous == held) {
            return;
        }
        directionHeld[index] = held;
        if (!held) {
            repeat.release(action);
            return;
        }
        repeat.press(action, nowMs);
        long lastEdge = lastDirectionEdgeMs[index];
        if (lastEdge == Long.MIN_VALUE || nowMs - lastEdge >= EDGE_DEBOUNCE_MS) {
            lastDirectionEdgeMs[index] = nowMs;
            enqueue(action);
        }
    }

    private void enqueue(SemanticAction action) {
        if (action == null) {
            return;
        }
        while (actions.size() >= MAX_ACTIONS) {
            actions.poll();
        }
        actions.offer(action);
    }

    private void clearDeliveryState() {
        actions.clear();
        repeat.clear();
        Arrays.fill(directionHeld, false);
    }

    private void resetAllState() {
        clearDeliveryState();
        Arrays.fill(buttonHeld, false);
        Arrays.fill(lastDirectionEdgeMs, Long.MIN_VALUE);
        stickLeft = false;
        stickRight = false;
        stickUp = false;
        stickDown = false;
        awaitingRelease = false;
    }

    private boolean allPhysicalControlsReleased() {
        for (boolean held : buttonHeld) {
            if (held) {
                return false;
            }
        }
        return !stickLeft && !stickRight && !stickUp && !stickDown;
    }

    private static boolean isDirectionalButton(int button) {
        return button == ControllerCodes.BUTTON_DPAD_UP
                || button == ControllerCodes.BUTTON_DPAD_DOWN
                || button == ControllerCodes.BUTTON_DPAD_LEFT
                || button == ControllerCodes.BUTTON_DPAD_RIGHT;
    }

    static boolean negativeAxisHeld(boolean currentlyHeld, float value) {
        float threshold = currentlyHeld
                ? -STICK_RELEASE_THRESHOLD
                : -STICK_ENGAGE_THRESHOLD;
        return value <= threshold;
    }

    static boolean positiveAxisHeld(boolean currentlyHeld, float value) {
        float threshold = currentlyHeld
                ? STICK_RELEASE_THRESHOLD
                : STICK_ENGAGE_THRESHOLD;
        return value >= threshold;
    }

    private static float normalizeAxis(int rawValue) {
        if (rawValue < 0) {
            return Math.max(-1.0f, rawValue / 32768.0f);
        }
        return Math.min(1.0f, rawValue / 32767.0f);
    }
}
