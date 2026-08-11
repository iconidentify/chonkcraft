package net.chonkbase.runtime.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class EdtSemanticActionSinkTest {
    @Test
    void backgroundProductionArrivesOnTheSwingEventThread() throws Exception {
        List<Boolean> eventThread = new ArrayList<>();
        EdtSemanticActionSink sink =
                new EdtSemanticActionSink(action ->
                        eventThread.add(SwingUtilities.isEventDispatchThread()));

        Thread producer =
                new Thread(() -> sink.accept(SemanticAction.CONFIRM), "fake-controller");
        producer.start();
        producer.join();
        SwingUtilities.invokeAndWait(() -> {});

        assertEquals(List.of(true), eventThread);
    }

    @Test
    void aFocusEpochChangeRejectsAnActionAlreadyQueuedForTheEdt() throws Exception {
        InputGate gate = new InputGate();
        List<SemanticAction> delivered = new ArrayList<>();
        EdtSemanticActionSink sink = new EdtSemanticActionSink(gate, delivered::add);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            blockerStarted.countDown();
            try {
                releaseBlocker.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(blockerStarted.await(2, TimeUnit.SECONDS));

        sink.accept(SemanticAction.CONFIRM);
        gate.setSuppressed(true, "focus");
        releaseBlocker.countDown();
        SwingUtilities.invokeAndWait(() -> {});

        assertTrue(delivered.isEmpty());
    }
}
