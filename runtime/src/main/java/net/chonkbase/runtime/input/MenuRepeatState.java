package net.chonkbase.runtime.input;

import java.util.Arrays;

/** Held-direction repeat: edge first, then 250 ms delay and 75 ms cadence. */
final class MenuRepeatState {
    static final long INITIAL_DELAY_MS = 250L;
    static final long INTERVAL_MS = 75L;

    private static final SemanticAction[] REPEAT_ORDER = {
        SemanticAction.NAVIGATE_UP,
        SemanticAction.NAVIGATE_DOWN,
        SemanticAction.NAVIGATE_LEFT,
        SemanticAction.NAVIGATE_RIGHT
    };

    private final boolean[] held = new boolean[SemanticAction.values().length];
    private final long[] pressedAtMs = new long[SemanticAction.values().length];
    private final long[] lastRepeatMs = new long[SemanticAction.values().length];

    void press(SemanticAction action, long nowMs) {
        if (!isRepeatable(action)) {
            return;
        }
        int index = action.ordinal();
        if (held[index]) {
            return;
        }
        held[index] = true;
        pressedAtMs[index] = nowMs;
        lastRepeatMs[index] = Long.MIN_VALUE;
    }

    void release(SemanticAction action) {
        if (!isRepeatable(action)) {
            return;
        }
        int index = action.ordinal();
        held[index] = false;
        pressedAtMs[index] = 0L;
        lastRepeatMs[index] = Long.MIN_VALUE;
    }

    SemanticAction poll(long nowMs) {
        for (SemanticAction action : REPEAT_ORDER) {
            int index = action.ordinal();
            if (!held[index] || nowMs - pressedAtMs[index] < INITIAL_DELAY_MS) {
                continue;
            }
            long lastRepeat = lastRepeatMs[index];
            if (lastRepeat != Long.MIN_VALUE && nowMs - lastRepeat < INTERVAL_MS) {
                continue;
            }
            lastRepeatMs[index] = nowMs;
            return action;
        }
        return null;
    }

    void clear() {
        Arrays.fill(held, false);
        Arrays.fill(pressedAtMs, 0L);
        Arrays.fill(lastRepeatMs, Long.MIN_VALUE);
    }

    private static boolean isRepeatable(SemanticAction action) {
        return action == SemanticAction.NAVIGATE_UP
                || action == SemanticAction.NAVIGATE_DOWN
                || action == SemanticAction.NAVIGATE_LEFT
                || action == SemanticAction.NAVIGATE_RIGHT;
    }
}
