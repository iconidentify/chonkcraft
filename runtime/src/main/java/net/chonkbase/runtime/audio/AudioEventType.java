package net.chonkbase.runtime.audio;

/** Bounded vocabulary for mixer observability without log-string parsing. */
public enum AudioEventType {
    COMMAND_REJECTED,
    VOICE_STARTED,
    VOICE_COMPLETED,
    VOICE_STOPPED,
    VOICE_STOLEN,
    VOICE_REJECTED,
    STREAM_UNDERRUN,
    STREAM_RECOVERED,
    STREAM_FAILED,
    BUS_GAIN_CHANGED,
    BUS_MUTE_CHANGED,
    ALL_VOICES_STOPPED
}
