package net.chonkbase.runtime.input;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Serialized multi-slot controller coordinator.
 *
 * <p>The hub owns scanning, hotplug reconciliation, resume recovery, focus
 * gating, and the single 250 Hz backend event pump. Per-slot state owns stick
 * hysteresis, release latching, repeat, and bounded semantic action queues.
 */
public final class ControllerInputHub implements AutoCloseable {
    public static final int DEFAULT_SLOT_COUNT = 2;
    public static final int POLL_HZ = 250;
    public static final long POLL_PERIOD_MS = 1_000L / POLL_HZ;
    public static final long RESCAN_INTERVAL_MS = 3_000L;
    public static final long RESUME_JUMP_MS = 30_000L;
    public static final long RECONNECT_GRACE_MS = 30_000L;

    private final ControllerBackend backend;
    private final ControllerSlot[] slots;
    private final InputGate gate;
    private final LastInputFamilyTracker inputFamilies;
    private final Consumer<SemanticAction> actionSink;
    private final Object pumpLock = new Object();
    private final Map<String, Integer> preferredSlotByDevice = new HashMap<>();
    private final String[] unpluggedDeviceBySlot;
    private final long[] unpluggedUntilBySlot;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AutoCloseable gateListener;

    private ScheduledExecutorService pollExecutor;
    private volatile String pendingForcedRescanReason;
    private long currentEventNowMs;
    private boolean currentEventActionsAllowed;
    private boolean inPumpCallback;
    private long lastScanAtMs = Long.MIN_VALUE;
    private int lastIdleDeviceCount = Integer.MIN_VALUE;
    private long lastPollWallMs = Long.MIN_VALUE;
    private long lastPollNanoNs = Long.MIN_VALUE;
    private boolean requestReleaseAfterNextBinding;
    private volatile boolean closed;
    private volatile String diagnostic = "not started";

    public ControllerInputHub(
            ControllerBackend backend,
            int slotCount,
            InputGate gate,
            LastInputFamilyTracker inputFamilies,
            Consumer<SemanticAction> actionSink) {
        if (slotCount < 1) {
            throw new IllegalArgumentException("slotCount must be positive");
        }
        this.backend = Objects.requireNonNullElse(backend, ControllerBackend.NOOP);
        this.gate = Objects.requireNonNull(gate, "gate");
        this.inputFamilies = Objects.requireNonNull(inputFamilies, "inputFamilies");
        this.actionSink = Objects.requireNonNull(actionSink, "actionSink");
        this.slots = new ControllerSlot[slotCount];
        this.unpluggedDeviceBySlot = new String[slotCount];
        this.unpluggedUntilBySlot = new long[slotCount];
        for (int index = 0; index < slots.length; index++) {
            slots[index] = new ControllerSlot(index);
        }
        gateListener = gate.addListener(state -> {
            if (!state.suppressed()) {
                return;
            }
            synchronized (pumpLock) {
                for (ControllerSlot slot : slots) {
                    slot.clearForSuppression();
                }
            }
        });
    }

    /** Performs the first scan and starts the one serialized daemon pump. */
    public void start() {
        synchronized (pumpLock) {
            if (closed || pollExecutor != null) {
                return;
            }
            attemptControllerScanLocked("startup", true, System.currentTimeMillis());
            pollExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "seven-controller-sdl-poll");
                thread.setDaemon(true);
                return thread;
            });
            pollExecutor.scheduleAtFixedRate(
                    this::pollSafely,
                    0L,
                    POLL_PERIOD_MS,
                    TimeUnit.MILLISECONDS);
        }
    }

    public AutoCloseable addListener(Listener listener) {
        if (listener == null) {
            return () -> {};
        }
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public void setWindowFocused(boolean focused) {
        synchronized (pumpLock) {
            if (focused) {
                for (ControllerSlot slot : slots) {
                    slot.requestReleaseBeforeNextPress();
                }
                gate.setSuppressed(false, "window-focus");
            } else {
                gate.setSuppressed(true, "window-focus");
            }
        }
    }

    public void requestControllerRescan(String reason) {
        if (!closed) {
            pendingForcedRescanReason =
                    reason == null || reason.isBlank() ? "manual" : reason;
        }
    }

    public boolean isAnyConnected() {
        synchronized (pumpLock) {
            for (ControllerSlot slot : slots) {
                if (slot.isConnected(backend)) {
                    return true;
                }
            }
            return false;
        }
    }

    public List<ConnectedController> connectedControllers() {
        synchronized (pumpLock) {
            return java.util.Arrays.stream(slots)
                    .map(ControllerSlot::controller)
                    .filter(Objects::nonNull)
                    .map(device -> new ConnectedController(
                            slotForInstance(device.instanceId()).slotIndex(),
                            device.name(),
                            device.bindingId()))
                    .toList();
        }
    }

    public String diagnostic() {
        return diagnostic;
    }

    InputGate inputGate() {
        return gate;
    }

    void pollOnce(long nowMs, long monotonicNs) {
        synchronized (pumpLock) {
            if (closed) {
                return;
            }
            if (detectResumeJump(nowMs, monotonicNs)) {
                rememberPollClock(nowMs, monotonicNs);
                handleResumeDetectedLocked(nowMs);
                return;
            }
            rememberPollClock(nowMs, monotonicNs);

            String requestedRescan = pendingForcedRescanReason;
            if (requestedRescan != null) {
                pendingForcedRescanReason = null;
                attemptControllerScanLocked(requestedRescan, true, nowMs);
                return;
            }

            for (ControllerSlot slot : slots) {
                ControllerDevice device = slot.controller();
                if (device != null && !backend.isAttached(device)) {
                    slotLostLocked(slot, true, nowMs);
                    attemptControllerScanLocked("reacquire", true, nowMs);
                    return;
                }
            }

            pumpControllerEventsLocked(nowMs, gate.acceptsInput());
            refreshReleaseLatchesLocked();

            String deferredRescan = pendingForcedRescanReason;
            if (deferredRescan != null) {
                pendingForcedRescanReason = null;
                attemptControllerScanLocked(deferredRescan, true, nowMs);
                return;
            }

            if (gate.acceptsInput()) {
                for (ControllerSlot slot : slots) {
                    if (slot.isConnected(backend)) {
                        slot.maybeRepeat(nowMs);
                        slot.drainActions(actionSink);
                    }
                }
            } else {
                for (ControllerSlot slot : slots) {
                    slot.clearForSuppression();
                }
            }
            maybeIdleRescanLocked(nowMs);
        }
    }

    void attemptControllerScan(String reason, boolean forceRefresh, long nowMs) {
        synchronized (pumpLock) {
            attemptControllerScanLocked(reason, forceRefresh, nowMs);
        }
    }

    private void pollSafely() {
        try {
            pollOnce(System.currentTimeMillis(), System.nanoTime());
        } catch (Throwable failure) {
            diagnostic = "poll failed: "
                    + failure.getClass().getSimpleName()
                    + ": "
                    + failure.getMessage();
            System.err.println("[SevenDays][controller] " + diagnostic);
        }
    }

    private void pumpControllerEventsLocked(long nowMs, boolean actionsAllowed) {
        currentEventNowMs = nowMs;
        currentEventActionsAllowed = actionsAllowed;
        inPumpCallback = true;
        try {
            backend.pumpEvents(this::handleCurrentControllerEvent);
        } catch (Throwable failure) {
            diagnostic = "event pump failed: "
                    + failure.getClass().getSimpleName()
                    + ": "
                    + failure.getMessage();
        } finally {
            inPumpCallback = false;
        }
    }

    private void handleCurrentControllerEvent(
            ControllerBackend.EventType type,
            int controllerInstanceId,
            int code,
            int value,
            long capturedNanoTime) {
        long nowMs = currentEventNowMs;
        switch (type) {
            case DEVICE_ADDED -> requestForcedRescanLocked("hotplug-added", nowMs);
            case DEVICE_REMAPPED -> requestForcedRescanLocked("remapped", nowMs);
            case DEVICE_REMOVED -> {
                ControllerSlot removed = slotForInstance(controllerInstanceId);
                if (removed != null) {
                    slotLostLocked(removed, true, nowMs);
                }
                requestForcedRescanLocked("reacquire", nowMs);
            }
            case APP_SUSPENDED -> gate.setSuppressed(true, "native-lifecycle");
            case APP_RESUMED -> {
                gate.setSuppressed(false, "native-lifecycle");
                handleResumeDetectedLocked(nowMs);
            }
            case BUTTON_DOWN, BUTTON_UP, AXIS_MOTION -> {
                ControllerSlot slot = slotForInstance(controllerInstanceId);
                if (slot == null) {
                    return;
                }
                if (currentEventActionsAllowed
                        && (type == ControllerBackend.EventType.BUTTON_DOWN
                                || (type == ControllerBackend.EventType.AXIS_MOTION
                                        && Math.abs(value) > 8_000))) {
                    inputFamilies.recordController(slot.controller().name());
                }
                if (type == ControllerBackend.EventType.AXIS_MOTION) {
                    slot.handleAxis(code, value, nowMs, currentEventActionsAllowed);
                } else {
                    slot.handleButton(
                            code,
                            type == ControllerBackend.EventType.BUTTON_DOWN,
                            nowMs,
                            currentEventActionsAllowed);
                }
            }
        }
    }

    private void requestForcedRescanLocked(String reason, long nowMs) {
        if (inPumpCallback) {
            pendingForcedRescanReason = reason;
        } else {
            pendingForcedRescanReason = null;
            attemptControllerScanLocked(reason, true, nowMs);
        }
    }

    private void maybeIdleRescanLocked(long nowMs) {
        if (elapsedSince(lastScanAtMs, nowMs) < RESCAN_INTERVAL_MS) {
            return;
        }
        int deviceCount;
        try {
            deviceCount = backend.connectedControllerCount();
        } catch (Throwable failure) {
            attemptControllerScanLocked("idle-rescan", false, nowMs);
            return;
        }
        if (deviceCount == lastIdleDeviceCount && allBoundSlotsAttached()) {
            lastScanAtMs = nowMs;
            return;
        }
        attemptControllerScanLocked("idle-rescan", false, nowMs);
    }

    private boolean allBoundSlotsAttached() {
        for (ControllerSlot slot : slots) {
            ControllerDevice device = slot.controller();
            if (device != null && !backend.isAttached(device)) {
                return false;
            }
        }
        return true;
    }

    private void attemptControllerScanLocked(String reason, boolean forceRefresh, long nowMs) {
        if (closed
                || (!forceRefresh
                        && elapsedSince(lastScanAtMs, nowMs) < RESCAN_INTERVAL_MS)) {
            return;
        }
        lastScanAtMs = nowMs;
        try {
            List<ControllerDevice> found = backend.findControllers(forceRefresh);
            reconcileControllersLocked(found == null ? List.of() : found, nowMs);
            applyDeferredReleaseLatchLocked();
            lastIdleDeviceCount = backend.connectedControllerCount();
            diagnostic = "reason="
                    + reason
                    + " bound="
                    + connectedControllerCountLocked()
                    + "; "
                    + backend.diagnostics();
        } catch (Throwable failure) {
            diagnostic = "scan failed: "
                    + failure.getClass().getSimpleName()
                    + ": "
                    + failure.getMessage();
            System.err.println("[SevenDays][controller] " + diagnostic);
        }
    }

    private void reconcileControllersLocked(List<ControllerDevice> found, long nowMs) {
        boolean[] assignedSlots = new boolean[slots.length];
        for (ControllerDevice device : found) {
            if (device == null) {
                continue;
            }
            int slotIndex = chooseSlot(device, assignedSlots, nowMs);
            if (slotIndex < 0) {
                closeController(device);
                continue;
            }
            assignedSlots[slotIndex] = true;
            ControllerSlot slot = slots[slotIndex];
            ControllerDevice previous = slot.controller();
            if (previous != device) {
                if (previous != null) {
                    closeController(previous);
                }
                slot.bind(device);
                backend.setPlayerIndex(device, slotIndex);
                String key = device.deviceKey();
                boolean reconnected = key != null
                        && key.equals(unpluggedDeviceBySlot[slotIndex])
                        && nowMs <= unpluggedUntilBySlot[slotIndex];
                unpluggedDeviceBySlot[slotIndex] = null;
                unpluggedUntilBySlot[slotIndex] = 0L;
                if (reconnected) {
                    notifyReconnected(slotIndex, device);
                } else {
                    notifyBound(slotIndex, device);
                }
            }
            if (device.deviceKey() != null) {
                preferredSlotByDevice.put(device.deviceKey(), slotIndex);
            }
        }

        for (int index = 0; index < slots.length; index++) {
            if (!assignedSlots[index] && slots[index].controller() != null) {
                slotLostLocked(slots[index], false, nowMs);
            }
        }
    }

    private int chooseSlot(ControllerDevice device, boolean[] assignedSlots, long nowMs) {
        String key = device.deviceKey();
        if (key != null) {
            Integer preferred = preferredSlotByDevice.get(key);
            if (preferred != null
                    && preferred >= 0
                    && preferred < slots.length
                    && !assignedSlots[preferred]
                    && (unpluggedUntilBySlot[preferred] == 0L
                            || nowMs <= unpluggedUntilBySlot[preferred])) {
                return preferred;
            }
        }
        for (int index = 0; index < slots.length; index++) {
            ControllerDevice current = slots[index].controller();
            if (!assignedSlots[index]
                    && current != null
                    && current.instanceId() == device.instanceId()) {
                return index;
            }
        }
        for (int index = 0; index < slots.length; index++) {
            if (!assignedSlots[index] && slots[index].controller() == null) {
                return index;
            }
        }
        for (int index = 0; index < slots.length; index++) {
            if (!assignedSlots[index]) {
                return index;
            }
        }
        return -1;
    }

    private void slotLostLocked(ControllerSlot slot, boolean unplugged, long nowMs) {
        ControllerDevice removed = slot.unbind();
        if (removed == null) {
            return;
        }
        String key = removed.deviceKey();
        if (key != null) {
            preferredSlotByDevice.put(key, slot.slotIndex());
            if (unplugged) {
                unpluggedDeviceBySlot[slot.slotIndex()] = key;
                unpluggedUntilBySlot[slot.slotIndex()] = nowMs + RECONNECT_GRACE_MS;
            }
        }
        closeController(removed);
        for (Listener listener : listeners) {
            if (unplugged) {
                listener.onUnplugged(slot.slotIndex(), removed);
            } else {
                listener.onUnbound(slot.slotIndex());
            }
        }
    }

    private void handleResumeDetectedLocked(long nowMs) {
        for (ControllerSlot slot : slots) {
            slotLostLocked(slot, false, nowMs);
        }
        requestReleaseAfterNextBinding = true;
        requestForcedRescanLocked("resume", nowMs);
        for (Listener listener : listeners) {
            try {
                listener.onResumeDetected();
            } catch (RuntimeException ignored) {
                // Recovery hooks must not strand the native event pump.
            }
        }
    }

    private void applyDeferredReleaseLatchLocked() {
        if (!requestReleaseAfterNextBinding || connectedControllerCountLocked() == 0) {
            return;
        }
        requestReleaseAfterNextBinding = false;
        for (ControllerSlot slot : slots) {
            if (slot.isConnected(backend)) {
                slot.requestReleaseBeforeNextPress();
            }
        }
    }

    private void refreshReleaseLatchesLocked() {
        for (ControllerSlot slot : slots) {
            ControllerDevice device = slot.controller();
            if (device == null || !slot.awaitingRelease()) {
                continue;
            }
            ControllerBackend.PhysicalControlState state;
            try {
                state = backend.physicalControlState(device);
            } catch (RuntimeException failure) {
                state = ControllerBackend.PhysicalControlState.UNKNOWN;
            }
            slot.observePhysicalControlState(state);
        }
    }

    private void notifyBound(int slotIndex, ControllerDevice device) {
        for (Listener listener : listeners) {
            listener.onBound(slotIndex, device);
        }
    }

    private void notifyReconnected(int slotIndex, ControllerDevice device) {
        for (Listener listener : listeners) {
            listener.onReconnected(slotIndex, device);
        }
    }

    private ControllerSlot slotForInstance(int instanceId) {
        for (ControllerSlot slot : slots) {
            if (slot.ownsInstance(instanceId)) {
                return slot;
            }
        }
        return null;
    }

    private int connectedControllerCountLocked() {
        int connected = 0;
        for (ControllerSlot slot : slots) {
            if (slot.isConnected(backend)) {
                connected++;
            }
        }
        return connected;
    }

    private boolean detectResumeJump(long nowMs, long monotonicNs) {
        if (lastPollWallMs == Long.MIN_VALUE || lastPollNanoNs == Long.MIN_VALUE) {
            return false;
        }
        long wallDelta = nowMs - lastPollWallMs;
        long monotonicDeltaMs = (monotonicNs - lastPollNanoNs) / 1_000_000L;
        return wallDelta > RESUME_JUMP_MS || monotonicDeltaMs > RESUME_JUMP_MS;
    }

    private void rememberPollClock(long nowMs, long monotonicNs) {
        lastPollWallMs = nowMs;
        lastPollNanoNs = monotonicNs;
    }

    private static long elapsedSince(long previousMs, long nowMs) {
        return previousMs == Long.MIN_VALUE ? Long.MAX_VALUE : nowMs - previousMs;
    }

    private void closeController(ControllerDevice device) {
        if (device == null) {
            return;
        }
        try {
            backend.setPlayerIndex(device, -1);
        } catch (RuntimeException ignored) {
        }
        try {
            backend.closeController(device);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void close() {
        synchronized (pumpLock) {
            if (closed) {
                return;
            }
            closed = true;
            pendingForcedRescanReason = null;
            requestReleaseAfterNextBinding = false;
            if (pollExecutor != null) {
                pollExecutor.shutdownNow();
                pollExecutor = null;
            }
            try {
                gateListener.close();
            } catch (Exception ignored) {
            }
            for (ControllerSlot slot : slots) {
                closeController(slot.unbind());
            }
            listeners.clear();
            // Deliberately no SDL_Quit: packaged shutdown still needs a native
            // smoke test proving no callback/EDT teardown race.
        }
    }

    public record ConnectedController(int slot, String name, String bindingId) {}

    public interface Listener {
        default void onBound(int slot, ControllerDevice device) {}

        default void onUnbound(int slot) {}

        default void onUnplugged(int slot, ControllerDevice device) {}

        default void onReconnected(int slot, ControllerDevice device) {}

        default void onResumeDetected() {}
    }
}
