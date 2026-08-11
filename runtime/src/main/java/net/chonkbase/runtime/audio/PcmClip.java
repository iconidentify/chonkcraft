package net.chonkbase.runtime.audio;

import java.util.Arrays;

/**
 * Immutable 48 kHz PCM clip stored as interleaved signed 16-bit samples.
 *
 * <p>Clips may be mono or stereo. The mixer always produces stereo output.
 * Constructors defensively copy caller-owned arrays so decoded asset buffers
 * cannot be mutated while the audio thread is reading them.
 */
public final class PcmClip {
    private static final float I16_TO_FLOAT = 1.0f / 32_768.0f;

    private final String debugName;
    private final int channels;
    private final short[] samples;
    private final int frameCount;

    public PcmClip(String debugName, int channels, short[] interleavedSamples) {
        this(debugName, channels, interleavedSamples, true);
    }

    private PcmClip(String debugName, int channels, short[] interleavedSamples, boolean copySamples) {
        if (channels != 1 && channels != 2) {
            throw new IllegalArgumentException("channels must be mono or stereo");
        }
        if (interleavedSamples == null) {
            throw new IllegalArgumentException("interleavedSamples must not be null");
        }
        if (interleavedSamples.length == 0 || interleavedSamples.length % channels != 0) {
            throw new IllegalArgumentException("samples must contain complete, non-empty frames");
        }
        this.debugName = debugName == null ? "" : debugName;
        this.channels = channels;
        this.samples = copySamples
                ? Arrays.copyOf(interleavedSamples, interleavedSamples.length)
                : interleavedSamples;
        this.frameCount = samples.length / channels;
    }

    /**
     * Transfers a freshly allocated decoder buffer into an immutable clip.
     * Package-private so only trusted runtime loaders can use the no-copy path.
     */
    static PcmClip fromOwnedSamples(String debugName, int channels, short[] interleavedSamples) {
        return new PcmClip(debugName, channels, interleavedSamples, false);
    }

    public static PcmClip fromFloats(String debugName, int channels, float[] interleavedSamples) {
        if (interleavedSamples == null) {
            throw new IllegalArgumentException("interleavedSamples must not be null");
        }
        short[] pcm = new short[interleavedSamples.length];
        for (int i = 0; i < interleavedSamples.length; i++) {
            float value = interleavedSamples[i];
            if (!Float.isFinite(value)) {
                value = 0.0f;
            }
            value = Math.max(-1.0f, Math.min(1.0f, value));
            pcm[i] = (short) Math.round(value * 32_767.0f);
        }
        return new PcmClip(debugName, channels, pcm);
    }

    public String debugName() {
        return debugName;
    }

    public int sampleRate() {
        return PcmFormat.GAME_SAMPLE_RATE;
    }

    public int channels() {
        return channels;
    }

    public int frameCount() {
        return frameCount;
    }

    public long residentBytes() {
        return (long) samples.length * Short.BYTES;
    }

    public short[] copySamples() {
        return Arrays.copyOf(samples, samples.length);
    }

    float sampleAt(int frame, int channel) {
        return samples[(frame * channels) + channel] * I16_TO_FLOAT;
    }
}
