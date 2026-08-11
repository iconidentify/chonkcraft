package net.chonkbase.runtime.audio;

/** Immutable description of the mixer output stream. */
public record PcmFormat(int sampleRate, int channels) {
    public static final int GAME_SAMPLE_RATE = 48_000;
    public static final PcmFormat GAME_STEREO = new PcmFormat(GAME_SAMPLE_RATE, 2);

    public PcmFormat {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive");
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive");
        }
    }
}
