package net.chonkbase.runtime.input;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Fail-soft application facade for the optional native controller subsystem.
 * The Java binding is packaged normally; missing or unloadable SDL2 leaves the
 * game fully usable with keyboard and mouse.
 */
public final class ControllerSession implements AutoCloseable {
    public static final String ENABLED_PROPERTY = "seven.controller.enabled";

    private final Status status;
    private final ControllerInputHub hub;
    private final InputGate gate;
    private final LastInputFamilyTracker inputFamilies;
    private final String startupDiagnostic;

    private ControllerSession(
            Status status,
            ControllerInputHub hub,
            InputGate gate,
            LastInputFamilyTracker inputFamilies,
            String startupDiagnostic) {
        this.status = status;
        this.hub = hub;
        this.gate = gate;
        this.inputFamilies = inputFamilies;
        this.startupDiagnostic = startupDiagnostic;
    }

    public static ControllerSession startDefault(
            Consumer<SemanticAction> actionTarget,
            LastInputFamilyTracker inputFamilies,
            Runnable resumeListener) {
        Objects.requireNonNull(actionTarget, "actionTarget");
        Objects.requireNonNull(inputFamilies, "inputFamilies");
        InputGate gate = new InputGate();
        if (!isEnabled()) {
            return new ControllerSession(
                    Status.DISABLED,
                    null,
                    gate,
                    inputFamilies,
                    "disabled by -D" + ENABLED_PROPERTY + "=false");
        }

        ControllerInputHub hub = null;
        try {
            SdlControllerBackend backend = new SdlControllerBackend();
            hub = new ControllerInputHub(
                    backend,
                    ControllerInputHub.DEFAULT_SLOT_COUNT,
                    gate,
                    inputFamilies,
                    new EdtSemanticActionSink(gate, actionTarget));
            Runnable safeResumeListener =
                    resumeListener == null ? () -> {} : resumeListener;
            hub.addListener(new ControllerInputHub.Listener() {
                @Override
                public void onResumeDetected() {
                    safeResumeListener.run();
                }
            });
            hub.start();
            return new ControllerSession(
                    Status.RUNNING,
                    hub,
                    gate,
                    inputFamilies,
                    hub.diagnostic());
        } catch (Throwable failure) {
            if (hub != null) {
                hub.close();
            }
            String message = rootMessage(failure);
            System.err.println(
                    "[SevenDays][controller] native controller input unavailable; "
                            + "keyboard/mouse remain active: "
                            + message);
            return new ControllerSession(
                    Status.UNAVAILABLE,
                    null,
                    gate,
                    inputFamilies,
                    message);
        }
    }

    public Status status() {
        return status;
    }

    public boolean nativeBackendRunning() {
        return status == Status.RUNNING;
    }

    public void setWindowFocused(boolean focused) {
        if (hub != null) {
            hub.setWindowFocused(focused);
        } else {
            gate.setSuppressed(!focused, "window-focus");
        }
    }

    public void requestRescan(String reason) {
        if (hub != null) {
            hub.requestControllerRescan(reason);
        }
    }

    public List<ControllerInputHub.ConnectedController> connectedControllers() {
        return hub == null ? List.of() : hub.connectedControllers();
    }

    public LastInputFamilyTracker.Snapshot lastInputFamily() {
        return inputFamilies.snapshot();
    }

    public InputGate.State inputGateState() {
        return gate.state();
    }

    public String diagnostic() {
        return hub == null ? startupDiagnostic : hub.diagnostic();
    }

    @Override
    public void close() {
        if (hub != null) {
            hub.close();
        }
    }

    private static boolean isEnabled() {
        String configured = System.getProperty(ENABLED_PROPERTY, "true").trim();
        return !configured.equalsIgnoreCase("false")
                && !configured.equals("0")
                && !configured.equalsIgnoreCase("off");
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    public enum Status {
        RUNNING,
        DISABLED,
        UNAVAILABLE
    }
}
