package net.chonkbase.runtime.audio;

/**
 * Stable routing categories for the shared game mix.
 *
 * <p>{@link #MASTER} is the parent of every other bus and cannot be used as a
 * voice destination.
 */
public enum AudioBus {
    MASTER,
    MUSIC,
    AMBIENCE,
    WORLD,
    UI,
    VOICE;

    public boolean acceptsVoices() {
        return this != MASTER;
    }
}
