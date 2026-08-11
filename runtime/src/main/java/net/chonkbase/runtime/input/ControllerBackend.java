package net.chonkbase.runtime.input;

import java.util.List;

/**
 * Neutral controller boundary implemented by SDL in production and fakes in
 * characterization tests.
 */
public interface ControllerBackend {
    ControllerBackend NOOP = new ControllerBackend() {
        @Override
        public List<ControllerDevice> findControllers(boolean forceRefresh) {
            return List.of();
        }

        @Override
        public void pumpEvents(EventConsumer consumer) {}

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
            return 0;
        }
    };

    List<ControllerDevice> findControllers(boolean forceRefresh) throws Exception;

    void pumpEvents(EventConsumer consumer) throws Exception;

    boolean isAttached(ControllerDevice device);

    void closeController(ControllerDevice device);

    int connectedControllerCount();

    default void setPlayerIndex(ControllerDevice device, int playerIndex) {}

    /**
     * Samples mapped controls without manufacturing semantic edges.
     *
     * <p>Backends that cannot query current device state may return
     * {@link PhysicalControlState#UNKNOWN}; the slot will then fall back to
     * the raw events observed by the serialized pump.
     */
    default PhysicalControlState physicalControlState(ControllerDevice device) {
        return PhysicalControlState.UNKNOWN;
    }

    default String diagnostics() {
        return "gameControllers=" + connectedControllerCount();
    }

    @FunctionalInterface
    interface EventConsumer {
        void accept(
                EventType type,
                int controllerInstanceId,
                int code,
                int value,
                long capturedNanoTime);
    }

    enum EventType {
        BUTTON_DOWN,
        BUTTON_UP,
        AXIS_MOTION,
        DEVICE_ADDED,
        DEVICE_REMOVED,
        DEVICE_REMAPPED,
        APP_SUSPENDED,
        APP_RESUMED
    }

    enum PhysicalControlState {
        ACTIVE,
        NEUTRAL,
        UNKNOWN
    }
}
