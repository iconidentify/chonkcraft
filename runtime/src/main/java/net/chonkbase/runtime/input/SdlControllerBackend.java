package net.chonkbase.runtime.input;

import io.github.libsdl4j.api.event.SDL_Event;
import io.github.libsdl4j.api.event.SDL_EventType;
import io.github.libsdl4j.api.event.SdlEvents;
import io.github.libsdl4j.api.event.SdlEventsConst;
import io.github.libsdl4j.api.event.events.SDL_CommonEvent;
import io.github.libsdl4j.api.event.events.SDL_ControllerAxisEvent;
import io.github.libsdl4j.api.event.events.SDL_ControllerButtonEvent;
import io.github.libsdl4j.api.event.events.SDL_ControllerDeviceEvent;
import io.github.libsdl4j.api.gamecontroller.SDL_GameController;
import io.github.libsdl4j.api.gamecontroller.SdlGamecontroller;
import io.github.libsdl4j.api.joystick.SDL_Joystick;
import io.github.libsdl4j.api.joystick.SDL_JoystickGUID;
import io.github.libsdl4j.api.joystick.SDL_JoystickID;
import io.github.libsdl4j.api.joystick.SdlJoystick;
import io.github.libsdl4j.api.timer.SdlTimer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** SDL2/JNA implementation behind the neutral {@link ControllerBackend}. */
final class SdlControllerBackend implements ControllerBackend {
    @FunctionalInterface
    interface EventPoller {
        int poll(SDL_Event event);
    }

    private static final Comparator<ControllerDevice> SELECTION_ORDER =
            Comparator.comparingInt(SdlControllerBackend::selectionScore).reversed();

    private final long sdlTicksBaseNanoTime;
    private final EventPoller eventPoller;
    private final SDL_Event event;
    private final ArrayList<ControllerDevice> scratchControllers = new ArrayList<>(4);
    private Thread pumpOwnerThread;

    SdlControllerBackend() {
        SdlNativeRuntime.init();
        SdlGamecontroller.SDL_GameControllerEventState(SdlEventsConst.SDL_ENABLE);
        SdlJoystick.SDL_JoystickEventState(SdlEventsConst.SDL_ENABLE);
        long ticksMs = SdlTimer.SDL_GetTicks64();
        sdlTicksBaseNanoTime = System.nanoTime() - ticksMs * 1_000_000L;
        event = new SDL_Event();
        eventPoller = SdlEvents::SDL_PollEvent;
    }

    SdlControllerBackend(EventPoller eventPoller) {
        this.sdlTicksBaseNanoTime = 0L;
        this.event = new SDL_Event();
        this.eventPoller = eventPoller;
    }

    @Override
    public List<ControllerDevice> findControllers(boolean forceRefresh) {
        if (forceRefresh) {
            SdlGamecontroller.SDL_GameControllerUpdate();
            SdlJoystick.SDL_JoystickUpdate();
        }
        scratchControllers.clear();
        int joystickCount = SdlJoystick.SDL_NumJoysticks();
        for (int index = 0; index < joystickCount; index++) {
            if (!SdlGamecontroller.SDL_IsGameController(index)) {
                continue;
            }
            ControllerDevice controller = openController(index);
            if (controller != null) {
                scratchControllers.add(controller);
            }
        }
        scratchControllers.sort(SELECTION_ORDER);
        return scratchControllers;
    }

    @Override
    public void pumpEvents(EventConsumer consumer) {
        assert recordAndCheckPumpOwner();
        while (eventPoller.poll(event) != 0) {
            event.read();
            translate(event, consumer);
        }
    }

    @Override
    public boolean isAttached(ControllerDevice device) {
        if (device == null || !(device.nativeHandle instanceof SDL_GameController nativeController)) {
            return device != null && device.attached();
        }
        return device.attached()
                && SdlGamecontroller.SDL_GameControllerGetAttached(nativeController);
    }

    @Override
    public void closeController(ControllerDevice device) {
        if (device == null) {
            return;
        }
        device.attached = false;
        if (device.nativeHandle instanceof SDL_GameController nativeController) {
            SdlGamecontroller.SDL_GameControllerClose(nativeController);
        }
    }

    @Override
    public void setPlayerIndex(ControllerDevice device, int playerIndex) {
        if (device == null || !(device.nativeHandle instanceof SDL_GameController nativeController)) {
            return;
        }
        try {
            SDL_Joystick joystick =
                    SdlGamecontroller.SDL_GameControllerGetJoystick(nativeController);
            if (joystick != null) {
                SdlJoystick.SDL_JoystickSetPlayerIndex(joystick, playerIndex);
            }
        } catch (RuntimeException ignored) {
            // Player LEDs are optional and vary across controller/driver pairs.
        }
    }

    @Override
    public int connectedControllerCount() {
        int count = 0;
        int joysticks = SdlJoystick.SDL_NumJoysticks();
        for (int index = 0; index < joysticks; index++) {
            if (SdlGamecontroller.SDL_IsGameController(index)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public PhysicalControlState physicalControlState(ControllerDevice device) {
        if (device == null
                || !(device.nativeHandle instanceof SDL_GameController nativeController)
                || !isAttached(device)) {
            return PhysicalControlState.UNKNOWN;
        }
        for (int button = 0; button < ControllerCodes.BUTTON_MAX; button++) {
            if (SdlGamecontroller.SDL_GameControllerGetButton(nativeController, button) != 0) {
                return PhysicalControlState.ACTIVE;
            }
        }
        short leftX = SdlGamecontroller.SDL_GameControllerGetAxis(
                nativeController,
                ControllerCodes.AXIS_LEFT_X);
        short leftY = SdlGamecontroller.SDL_GameControllerGetAxis(
                nativeController,
                ControllerCodes.AXIS_LEFT_Y);
        int releaseThreshold =
                Math.round(ControllerSlot.STICK_RELEASE_THRESHOLD * Short.MAX_VALUE);
        return Math.abs((int) leftX) > releaseThreshold
                        || Math.abs((int) leftY) > releaseThreshold
                ? PhysicalControlState.ACTIVE
                : PhysicalControlState.NEUTRAL;
    }

    @Override
    public String diagnostics() {
        int joysticks = SdlJoystick.SDL_NumJoysticks();
        int gameControllers = 0;
        StringBuilder devices = new StringBuilder();
        for (int index = 0; index < joysticks; index++) {
            boolean recognized = SdlGamecontroller.SDL_IsGameController(index);
            if (recognized) {
                gameControllers++;
            }
            String name = recognized
                    ? coalesce(
                            SdlGamecontroller.SDL_GameControllerNameForIndex(index),
                            SdlJoystick.SDL_JoystickNameForIndex(index),
                            "SDL Controller")
                    : coalesce(
                            SdlJoystick.SDL_JoystickNameForIndex(index),
                            null,
                            "SDL Joystick");
            if (devices.length() > 0) {
                devices.append("; ");
            }
            devices.append('#')
                    .append(index)
                    .append("{name=")
                    .append(name)
                    .append(", recognized=")
                    .append(recognized)
                    .append('}');
        }
        return "joysticks="
                + joysticks
                + " gameControllers="
                + gameControllers
                + " devices=["
                + devices
                + "] "
                + SdlNativeRuntime.diagnostic();
    }

    private ControllerDevice openController(int deviceIndex) {
        SDL_GameController nativeController =
                SdlGamecontroller.SDL_GameControllerOpen(deviceIndex);
        if (nativeController == null) {
            System.err.println(
                    "[SevenDays][controller] open failed for index "
                            + deviceIndex
                            + ": "
                            + SdlNativeRuntime.lastSdlError());
            return null;
        }
        SDL_Joystick joystick =
                SdlGamecontroller.SDL_GameControllerGetJoystick(nativeController);
        SDL_JoystickID id =
                joystick == null ? null : SdlJoystick.SDL_JoystickInstanceID(joystick);
        int instanceId = id == null ? -1 : (int) id.longValue();
        String name = coalesce(
                SdlGamecontroller.SDL_GameControllerName(nativeController),
                SdlGamecontroller.SDL_GameControllerNameForIndex(deviceIndex),
                "SDL Controller");
        String path = coalesce(
                SdlGamecontroller.SDL_GameControllerPath(nativeController),
                SdlGamecontroller.SDL_GameControllerPathForIndex(deviceIndex),
                null);
        String guid = safeGuidString(
                joystick == null
                        ? SdlJoystick.SDL_JoystickGetDeviceGUID(deviceIndex)
                        : SdlJoystick.SDL_JoystickGetGUID(joystick));
        return new ControllerDevice(
                instanceId,
                name,
                "SDL_GameController",
                path,
                guid,
                nativeController);
    }

    private void translate(SDL_Event source, EventConsumer consumer) {
        if (source.type == SDL_EventType.SDL_CONTROLLERBUTTONDOWN
                || source.type == SDL_EventType.SDL_CONTROLLERBUTTONUP) {
            SDL_ControllerButtonEvent button = source.cbutton;
            consumer.accept(
                    source.type == SDL_EventType.SDL_CONTROLLERBUTTONDOWN
                            ? EventType.BUTTON_DOWN
                            : EventType.BUTTON_UP,
                    joystickId(button.which),
                    Byte.toUnsignedInt(button.button),
                    button.state,
                    capturedNanoTime(button.timestamp));
            return;
        }
        if (source.type == SDL_EventType.SDL_CONTROLLERAXISMOTION) {
            SDL_ControllerAxisEvent axis = source.caxis;
            consumer.accept(
                    EventType.AXIS_MOTION,
                    joystickId(axis.which),
                    Byte.toUnsignedInt(axis.axis),
                    axis.value,
                    capturedNanoTime(axis.timestamp));
            return;
        }
        if (source.type == SDL_EventType.SDL_CONTROLLERDEVICEADDED
                || source.type == SDL_EventType.SDL_CONTROLLERDEVICEREMOVED
                || source.type == SDL_EventType.SDL_CONTROLLERDEVICEREMAPPED) {
            SDL_ControllerDeviceEvent device = source.cdevice;
            EventType type = source.type == SDL_EventType.SDL_CONTROLLERDEVICEADDED
                    ? EventType.DEVICE_ADDED
                    : source.type == SDL_EventType.SDL_CONTROLLERDEVICEREMOVED
                            ? EventType.DEVICE_REMOVED
                            : EventType.DEVICE_REMAPPED;
            consumer.accept(
                    type,
                    device.which,
                    0,
                    0,
                    capturedNanoTime(device.timestamp));
            return;
        }
        if (source.type == SDL_EventType.SDL_APP_WILLENTERBACKGROUND
                || source.type == SDL_EventType.SDL_APP_DIDENTERFOREGROUND) {
            SDL_CommonEvent common = source.common;
            consumer.accept(
                    source.type == SDL_EventType.SDL_APP_WILLENTERBACKGROUND
                            ? EventType.APP_SUSPENDED
                            : EventType.APP_RESUMED,
                    0,
                    0,
                    0,
                    capturedNanoTime(common.timestamp));
        }
    }

    private boolean recordAndCheckPumpOwner() {
        Thread current = Thread.currentThread();
        if (pumpOwnerThread == null) {
            pumpOwnerThread = current;
            return true;
        }
        if (pumpOwnerThread != current) {
            throw new AssertionError(
                    "SDL event pump moved from "
                            + pumpOwnerThread.getName()
                            + " to "
                            + current.getName());
        }
        return true;
    }

    private long capturedNanoTime(int sdlTimestampMs) {
        return sdlTicksBaseNanoTime
                + Integer.toUnsignedLong(sdlTimestampMs) * 1_000_000L;
    }

    private static int joystickId(SDL_JoystickID id) {
        return id == null ? -1 : (int) id.longValue();
    }

    private static String safeGuidString(SDL_JoystickGUID guid) {
        if (guid == null) {
            return null;
        }
        try {
            String value = guid.toString();
            if (value == null
                    || value.isBlank()
                    || "00000000-0000-0000-0000-000000000000".equals(value)) {
                return null;
            }
            return value;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static int selectionScore(ControllerDevice device) {
        String name = device.name().toLowerCase(Locale.ROOT);
        String path = device.path() == null
                ? ""
                : device.path().toLowerCase(Locale.ROOT);
        String type = device.type().toLowerCase(Locale.ROOT);
        int score = 100;
        if (type.contains("gamecontroller")) {
            score += 30;
        }
        if (name.contains("8bitdo")
                || name.contains("xbox")
                || name.contains("dualsense")
                || name.contains("dualshock")
                || name.contains("wireless controller")
                || name.contains("switch")
                || name.contains("pro controller")) {
            score += 100;
        }
        if (name.contains("steam deck")) {
            score += 80;
        }
        if (isVirtualish(name) || isVirtualish(path)) {
            score -= 140;
        } else {
            score += 30;
        }
        return score;
    }

    private static boolean isVirtualish(String value) {
        return value.contains("virtual")
                || value.contains("uinput")
                || (value.contains("steam controller") && !value.contains("steam deck"));
    }

    private static String coalesce(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return fallback;
    }
}
