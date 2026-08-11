package net.chonkbase.runtime.input;

/**
 * SDL2 GameController numeric vocabulary kept out of the device-neutral state
 * machine. Values match SDL_GameControllerButton/Axis in SDL2 2.28.x.
 */
public final class ControllerCodes {
    public static final int BUTTON_A = 0;
    public static final int BUTTON_B = 1;
    public static final int BUTTON_X = 2;
    public static final int BUTTON_Y = 3;
    public static final int BUTTON_BACK = 4;
    public static final int BUTTON_GUIDE = 5;
    public static final int BUTTON_START = 6;
    public static final int BUTTON_LEFT_STICK = 7;
    public static final int BUTTON_RIGHT_STICK = 8;
    public static final int BUTTON_LEFT_SHOULDER = 9;
    public static final int BUTTON_RIGHT_SHOULDER = 10;
    public static final int BUTTON_DPAD_UP = 11;
    public static final int BUTTON_DPAD_DOWN = 12;
    public static final int BUTTON_DPAD_LEFT = 13;
    public static final int BUTTON_DPAD_RIGHT = 14;
    public static final int BUTTON_MAX = 21;

    public static final int AXIS_LEFT_X = 0;
    public static final int AXIS_LEFT_Y = 1;

    private ControllerCodes() {}
}
