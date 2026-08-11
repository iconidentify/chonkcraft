package net.chonkbase.runtime.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ControllerInputHubTest {
    @Test
    void stickHysteresisAndMenuRepeatMatchThePinnedCadence() {
        FakeBackend backend = new FakeBackend();
        backend.devices = List.of(device(1, "pad-a"));
        List<SemanticAction> actions = new ArrayList<>();
        ControllerInputHub hub = hub(backend, actions);
        hub.attemptControllerScan("test", true, 100L);

        backend.axis(1, ControllerCodes.AXIS_LEFT_X, -15_000);
        hub.pollOnce(101L, 101_000_000L);
        assertEquals(List.of(SemanticAction.NAVIGATE_LEFT), actions);

        hub.pollOnce(350L, 350_000_000L);
        assertEquals(1, actions.size());
        hub.pollOnce(351L, 351_000_000L);
        assertEquals(
                List.of(
                        SemanticAction.NAVIGATE_LEFT,
                        SemanticAction.NAVIGATE_LEFT),
                actions);
        hub.pollOnce(425L, 425_000_000L);
        assertEquals(2, actions.size());
        hub.pollOnce(426L, 426_000_000L);
        assertEquals(3, actions.size());

        // 0.275 remains held because it has not crossed the 0.20 release band.
        backend.axis(1, ControllerCodes.AXIS_LEFT_X, -9_000);
        hub.pollOnce(427L, 427_000_000L);
        assertEquals(3, actions.size());

        backend.axis(1, ControllerCodes.AXIS_LEFT_X, -6_000);
        hub.pollOnce(428L, 428_000_000L);
        backend.axis(1, ControllerCodes.AXIS_LEFT_X, -15_000);
        hub.pollOnce(430L, 430_000_000L);
        assertEquals(4, actions.size());
        hub.close();
    }

    @Test
    void focusGateDrainsActionsAndRequiresAReleaseBeforeTheNextPress() {
        FakeBackend backend = new FakeBackend();
        backend.devices = List.of(device(7, "focus-pad"));
        List<SemanticAction> actions = new ArrayList<>();
        ControllerInputHub hub = hub(backend, actions);
        hub.attemptControllerScan("test", true, 100L);

        backend.button(7, ControllerCodes.BUTTON_A, true);
        hub.pollOnce(101L, 101_000_000L);
        assertEquals(List.of(SemanticAction.CONFIRM), actions);
        backend.button(7, ControllerCodes.BUTTON_A, false);
        hub.pollOnce(102L, 102_000_000L);

        hub.setWindowFocused(false);
        backend.button(7, ControllerCodes.BUTTON_A, true);
        hub.pollOnce(103L, 103_000_000L);
        assertEquals(1, actions.size());

        hub.setWindowFocused(true);
        backend.button(7, ControllerCodes.BUTTON_A, false);
        hub.pollOnce(104L, 104_000_000L);
        assertEquals(1, actions.size());

        backend.button(7, ControllerCodes.BUTTON_A, true);
        hub.pollOnce(105L, 105_000_000L);
        assertEquals(
                List.of(SemanticAction.CONFIRM, SemanticAction.CONFIRM),
                actions);
        assertFalse(hub.inputGate().state().suppressed());
        hub.close();
    }

    @Test
    void hotplugRescanIsDeferredAndReconnectRestoresThePreferredSlot() {
        FakeBackend backend = new FakeBackend();
        ControllerDevice original = device(1, "stable-guid");
        backend.devices = List.of(original);
        ControllerInputHub hub = hub(backend, new ArrayList<>());
        RecordingListener listener = new RecordingListener();
        hub.addListener(listener);
        hub.attemptControllerScan("test", true, 100L);

        ControllerDevice replacement = device(44, "stable-guid");
        backend.devices = List.of(replacement);
        backend.device(ControllerBackend.EventType.DEVICE_REMOVED, 1);
        hub.pollOnce(200L, 200_000_000L);

        assertFalse(backend.scanWhilePumping);
        assertEquals(1, listener.reconnected);
        assertEquals(
                List.of(new ControllerInputHub.ConnectedController(
                        0,
                        "Test Pad",
                        "guid:stable-guid")),
                hub.connectedControllers());
        hub.close();
    }

    @Test
    void eitherWallOrMonotonicClockJumpForcesResumeReconciliation() {
        assertResumeDetected(30_102L, 102_000_000L, "wall-jump");
        assertResumeDetected(102L, 30_102_000_000L, "monotonic-jump");
    }

    @Test
    void resumeRebindRejectsAControlHeldAcrossTheClockJumpUntilRelease() {
        FakeBackend backend = new FakeBackend();
        backend.devices = List.of(device(19, "held-on-resume"));
        List<SemanticAction> actions = new ArrayList<>();
        ControllerInputHub hub = hub(backend, actions);
        hub.attemptControllerScan("test", true, 100L);
        hub.pollOnce(101L, 101_000_000L);

        backend.button(19, ControllerCodes.BUTTON_A, true);
        hub.pollOnce(30_102L, 30_102_000_000L);
        hub.pollOnce(30_103L, 30_103_000_000L);
        assertTrue(actions.isEmpty());

        backend.button(19, ControllerCodes.BUTTON_A, false);
        hub.pollOnce(30_104L, 30_104_000_000L);
        backend.button(19, ControllerCodes.BUTTON_A, true);
        hub.pollOnce(30_105L, 30_105_000_000L);
        assertEquals(List.of(SemanticAction.CONFIRM), actions);
        hub.close();
    }

    @Test
    void semanticFaceButtonsAndLastInputFamilyAreDeviceNeutral() {
        FakeBackend backend = new FakeBackend();
        backend.devices = List.of(device(3, "semantic-pad"));
        List<SemanticAction> actions = new ArrayList<>();
        LastInputFamilyTracker families = new LastInputFamilyTracker();
        ControllerInputHub hub = new ControllerInputHub(
                backend,
                1,
                new InputGate(),
                families,
                actions::add);
        hub.attemptControllerScan("test", true, 100L);

        pressAndRelease(backend, hub, 3, ControllerCodes.BUTTON_A, 101L);
        pressAndRelease(backend, hub, 3, ControllerCodes.BUTTON_B, 103L);
        pressAndRelease(backend, hub, 3, ControllerCodes.BUTTON_START, 105L);
        pressAndRelease(
                backend,
                hub,
                3,
                ControllerCodes.BUTTON_LEFT_SHOULDER,
                107L);
        pressAndRelease(backend, hub, 3, ControllerCodes.BUTTON_X, 109L);
        pressAndRelease(
                backend,
                hub,
                3,
                ControllerCodes.BUTTON_RIGHT_SHOULDER,
                111L);
        pressAndRelease(backend, hub, 3, ControllerCodes.BUTTON_Y, 113L);

        assertEquals(
                List.of(
                        SemanticAction.CONFIRM,
                        SemanticAction.BACK,
                        SemanticAction.PAUSE,
                        SemanticAction.SPEED_SLOW,
                        SemanticAction.SPEED_NORMAL,
                        SemanticAction.SPEED_FAST,
                        SemanticAction.TOGGLE_FULLSCREEN),
                actions);
        assertEquals(
                LastInputFamilyTracker.InputFamily.CONTROLLER,
                families.snapshot().family());
        assertEquals("Test Pad", families.snapshot().controllerName());
        hub.close();
    }

    @Test
    void boundedSlotQueueDropsOldestActionsWithoutGrowingPastItsCap() {
        ControllerSlot slot = new ControllerSlot(0);
        for (int index = 0; index < ControllerSlot.MAX_ACTIONS + 5; index++) {
            slot.handleButton(ControllerCodes.BUTTON_A, true, index * 2L, true);
            slot.handleButton(ControllerCodes.BUTTON_A, false, index * 2L + 1L, true);
        }
        List<SemanticAction> actions = new ArrayList<>();
        slot.drainActions(actions::add);
        assertEquals(ControllerSlot.MAX_ACTIONS, actions.size());
        assertTrue(actions.stream().allMatch(action -> action == SemanticAction.CONFIRM));
    }

    private static void assertResumeDetected(
            long secondWallMs,
            long secondMonotonicNs,
            String deviceGuid) {
        FakeBackend backend = new FakeBackend();
        backend.devices = List.of(device(9, deviceGuid));
        ControllerInputHub hub = hub(backend, new ArrayList<>());
        RecordingListener listener = new RecordingListener();
        hub.addListener(listener);
        hub.attemptControllerScan("test", true, 100L);
        hub.pollOnce(101L, 101_000_000L);

        hub.pollOnce(secondWallMs, secondMonotonicNs);

        assertEquals(1, listener.resumed);
        assertTrue(backend.scanCount >= 2);
        assertTrue(hub.isAnyConnected());
        hub.close();
    }

    private static void pressAndRelease(
            FakeBackend backend,
            ControllerInputHub hub,
            int instance,
            int button,
            long nowMs) {
        backend.button(instance, button, true);
        hub.pollOnce(nowMs, nowMs * 1_000_000L);
        backend.button(instance, button, false);
        hub.pollOnce(nowMs + 1L, (nowMs + 1L) * 1_000_000L);
    }

    private static ControllerInputHub hub(
            FakeBackend backend, List<SemanticAction> actions) {
        return new ControllerInputHub(
                backend,
                2,
                new InputGate(),
                new LastInputFamilyTracker(),
                actions::add);
    }

    private static ControllerDevice device(int instanceId, String guid) {
        return new ControllerDevice(
                instanceId,
                "Test Pad",
                "SDL_GameController",
                "/fake/" + guid,
                guid);
    }

    private static final class RecordingListener implements ControllerInputHub.Listener {
        int reconnected;
        int resumed;

        @Override
        public void onReconnected(int slot, ControllerDevice device) {
            reconnected++;
        }

        @Override
        public void onResumeDetected() {
            resumed++;
        }
    }

    private record FakeEvent(
            ControllerBackend.EventType type,
            int instance,
            int code,
            int value) {}

    private static final class FakeBackend implements ControllerBackend {
        List<ControllerDevice> devices = List.of();
        final ArrayDeque<FakeEvent> events = new ArrayDeque<>();
        boolean pumping;
        boolean scanWhilePumping;
        boolean physicalActive;
        int scanCount;

        @Override
        public List<ControllerDevice> findControllers(boolean forceRefresh) {
            scanCount++;
            scanWhilePumping |= pumping;
            return devices;
        }

        @Override
        public void pumpEvents(EventConsumer consumer) {
            pumping = true;
            try {
                FakeEvent event;
                while ((event = events.poll()) != null) {
                    consumer.accept(
                            event.type(),
                            event.instance(),
                            event.code(),
                            event.value(),
                            1L);
                }
            } finally {
                pumping = false;
            }
        }

        @Override
        public boolean isAttached(ControllerDevice device) {
            return device != null && device.attached();
        }

        @Override
        public void closeController(ControllerDevice device) {
            if (device != null) {
                device.attached = false;
            }
        }

        @Override
        public int connectedControllerCount() {
            return devices.size();
        }

        @Override
        public PhysicalControlState physicalControlState(ControllerDevice device) {
            return physicalActive
                    ? PhysicalControlState.ACTIVE
                    : PhysicalControlState.NEUTRAL;
        }

        void button(int instance, int code, boolean pressed) {
            physicalActive = pressed;
            events.add(new FakeEvent(
                    pressed ? EventType.BUTTON_DOWN : EventType.BUTTON_UP,
                    instance,
                    code,
                    pressed ? 1 : 0));
        }

        void axis(int instance, int code, int value) {
            physicalActive = Math.abs(value)
                    > Math.round(ControllerSlot.STICK_RELEASE_THRESHOLD * Short.MAX_VALUE);
            events.add(new FakeEvent(
                    EventType.AXIS_MOTION,
                    instance,
                    code,
                    value));
        }

        void device(EventType type, int instance) {
            events.add(new FakeEvent(type, instance, 0, 0));
        }
    }
}
