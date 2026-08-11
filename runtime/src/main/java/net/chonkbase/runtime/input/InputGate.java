package net.chonkbase.runtime.input;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Monotonic input-ownership gate used for window focus and native lifecycle
 * transitions. Multiple reasons can suppress input without clearing one
 * another accidentally.
 */
public final class InputGate {
    private final Set<String> activeReasons = new LinkedHashSet<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile State state = new State(false, "startup", 0L, System.nanoTime());

    public State state() {
        return state;
    }

    public boolean acceptsInput() {
        return !state.suppressed();
    }

    public AutoCloseable addListener(Listener listener) {
        if (listener == null) {
            return () -> {};
        }
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public void setSuppressed(boolean suppressed, String reason) {
        String normalizedReason = normalizeReason(reason);
        State after;
        synchronized (this) {
            State before = state;
            boolean changed = suppressed
                    ? activeReasons.add(normalizedReason)
                    : activeReasons.remove(normalizedReason);
            boolean nowSuppressed = !activeReasons.isEmpty();
            if (!changed && before.suppressed() == nowSuppressed) {
                return;
            }
            after = new State(
                    nowSuppressed,
                    nowSuppressed ? String.join("+", activeReasons) : normalizedReason,
                    before.epoch() + 1L,
                    System.nanoTime());
            state = after;
        }
        for (Listener listener : listeners) {
            try {
                listener.onInputGateChanged(after);
            } catch (RuntimeException ignored) {
                // One diagnostic listener must never strand the input gate.
            }
        }
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unknown";
        }
        StringBuilder normalized = new StringBuilder();
        for (char character : reason.trim().toLowerCase().toCharArray()) {
            if ((character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')) {
                normalized.append(character);
            } else {
                normalized.append('_');
            }
        }
        return normalized.toString();
    }

    public record State(
            boolean suppressed,
            String reason,
            long epoch,
            long changedNanoTime) {}

    @FunctionalInterface
    public interface Listener {
        void onInputGateChanged(State state);
    }
}
