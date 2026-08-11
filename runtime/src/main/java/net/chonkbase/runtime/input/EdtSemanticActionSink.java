package net.chonkbase.runtime.input;

import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/** Delivers controller actions to Swing in producer order on the EDT. */
public final class EdtSemanticActionSink implements Consumer<SemanticAction> {
    private final InputGate gate;
    private final Consumer<SemanticAction> target;

    public EdtSemanticActionSink(Consumer<SemanticAction> target) {
        this(null, target);
    }

    public EdtSemanticActionSink(
            InputGate gate,
            Consumer<SemanticAction> target) {
        this.gate = gate;
        this.target = Objects.requireNonNull(target, "target");
    }

    @Override
    public void accept(SemanticAction action) {
        if (action == null) {
            return;
        }
        long capturedEpoch = gate == null ? Long.MIN_VALUE : gate.state().epoch();
        if (gate != null && !gate.acceptsInput()) {
            return;
        }
        if (SwingUtilities.isEventDispatchThread()) {
            deliverIfCurrent(action, capturedEpoch);
        } else {
            SwingUtilities.invokeLater(() -> deliverIfCurrent(action, capturedEpoch));
        }
    }

    private void deliverIfCurrent(SemanticAction action, long capturedEpoch) {
        if (gate != null
                && (!gate.acceptsInput() || gate.state().epoch() != capturedEpoch)) {
            return;
        }
        target.accept(action);
    }
}
