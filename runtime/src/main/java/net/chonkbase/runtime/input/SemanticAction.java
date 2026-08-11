package net.chonkbase.runtime.input;

/**
 * Device-neutral actions understood by the desktop game shell.
 *
 * <p>Keyboard, mouse, and the optional native controller backend all converge
 * on this vocabulary. Device code translates physical inputs and hands these
 * actions to the UI on the Swing event-dispatch thread.
 */
public enum SemanticAction {
    NAVIGATE_UP,
    NAVIGATE_DOWN,
    NAVIGATE_LEFT,
    NAVIGATE_RIGHT,
    CONFIRM,
    BACK,
    PAUSE,
    SPEED_SLOW,
    SPEED_NORMAL,
    SPEED_FAST,
    TOGGLE_FULLSCREEN
}
