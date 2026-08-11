package net.chonkbase.chonkcraft.engine.sound;

import net.chonkbase.runtime.audio.PcmClip;
import net.chonkbase.runtime.audio.PcmFormat;

/**
 * Reads Warcraft II's WAV files and converts them to the mixer's format.
 *
 * <p>The runtime's own {@code WavPcmLoader} deliberately refuses anything that
 * is not 48 kHz signed 16-bit, because Seven Days controls its own content
 * pipeline and wants a loud failure if an asset slips through unconverted.
 * That is the right rule there and the wrong one here: Warcraft II's audio was
 * authored in 1995 as 8-bit unsigned at 11 or 22 kHz, and there is no pipeline
 * to fix it in. Converting is this implementation's job, not the runtime's, which is why
 * this exists rather than a change to the vendored loader.
 *
 * <p>Handles 8-bit unsigned and 16-bit signed integer PCM, mono or stereo, at
 * any sample rate, and resamples to {@link PcmFormat#GAME_SAMPLE_RATE}.
 */
public final class LegacyWavDecoder {

    private LegacyWavDecoder() {
    }

    /** Thrown when the bytes are not a WAV this can read. */
    public static final class UnsupportedWavException extends RuntimeException {
        UnsupportedWavException(String message) {
            super(message);
        }
    }

    /**
     * Decodes a WAV file into a clip at the mixer's sample rate.
     *
     * @param debugName a name for diagnostics
     * @param data      the whole file
     */
    public static PcmClip decode(String debugName, byte[] data) {
        if (data.length < 12
                || data[0] != 'R' || data[1] != 'I' || data[2] != 'F' || data[3] != 'F'
                || data[8] != 'W' || data[9] != 'A' || data[10] != 'V' || data[11] != 'E') {
            throw new UnsupportedWavException("not a RIFF/WAVE file");
        }

        int formatCode = -1;
        int channels = 0;
        int sampleRate = 0;
        int bitsPerSample = 0;
        int dataOffset = -1;
        int dataLength = 0;

        // Walk the chunks. fmt may not be first and there are often others
        // (LIST, fact) between it and the data.
        int cursor = 12;
        while (cursor + 8 <= data.length) {
            String id = new String(data, cursor, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = readLe32(data, cursor + 4);
            int body = cursor + 8;
            if (size < 0 || body + size > data.length) {
                // A truncated final chunk is common in these files; take what
                // is there rather than rejecting the sound.
                size = data.length - body;
            }
            if ("fmt ".equals(id) && size >= 16) {
                formatCode = readLe16(data, body);
                channels = readLe16(data, body + 2);
                sampleRate = readLe32(data, body + 4);
                bitsPerSample = readLe16(data, body + 14);
            } else if ("data".equals(id)) {
                dataOffset = body;
                dataLength = size;
            }
            // Chunks are word aligned.
            cursor = body + size + (size & 1);
        }

        if (formatCode < 0) {
            throw new UnsupportedWavException("no fmt chunk");
        }
        if (dataOffset < 0) {
            throw new UnsupportedWavException("no data chunk");
        }
        if (formatCode != 1) {
            throw new UnsupportedWavException("only integer PCM is supported, found format " + formatCode);
        }
        if (channels != 1 && channels != 2) {
            throw new UnsupportedWavException("expected mono or stereo, found " + channels + " channels");
        }
        if (sampleRate <= 0) {
            throw new UnsupportedWavException("bad sample rate " + sampleRate);
        }

        short[] samples = switch (bitsPerSample) {
            // 8-bit WAV is unsigned with 128 as silence, so it has to be
            // recentred as well as widened.
            case 8 -> widenUnsignedBytes(data, dataOffset, dataLength);
            case 16 -> readSigned16(data, dataOffset, dataLength);
            default -> throw new UnsupportedWavException(bitsPerSample + "-bit samples are not supported");
        };
        if (samples.length < channels) {
            throw new UnsupportedWavException("no audio frames");
        }

        short[] atGameRate = resample(samples, channels, sampleRate, PcmFormat.GAME_SAMPLE_RATE);
        return new PcmClip(debugName, channels, atGameRate);
    }

    private static short[] widenUnsignedBytes(byte[] data, int offset, int length) {
        int count = Math.min(length, data.length - offset);
        short[] samples = new short[count];
        for (int i = 0; i < count; i++) {
            samples[i] = (short) (((data[offset + i] & 0xFF) - 128) << 8);
        }
        return samples;
    }

    private static short[] readSigned16(byte[] data, int offset, int length) {
        int count = Math.min(length, data.length - offset) / 2;
        short[] samples = new short[count];
        for (int i = 0; i < count; i++) {
            samples[i] = (short) readLe16(data, offset + i * 2);
        }
        return samples;
    }

    /**
     * Resamples by linear interpolation.
     *
     * <p>Good enough and then some: these are short, noisy, 8-bit effects
     * being upsampled, where the artefacts of a better filter would be well
     * below the quantisation noise already in the source.
     */
    private static short[] resample(short[] samples, int channels, int fromRate, int toRate) {
        if (fromRate == toRate) {
            return samples;
        }
        int inFrames = samples.length / channels;
        int outFrames = Math.max(1, (int) ((long) inFrames * toRate / fromRate));
        short[] out = new short[outFrames * channels];

        for (int frame = 0; frame < outFrames; frame++) {
            double source = (double) frame * fromRate / toRate;
            int left = (int) source;
            int right = Math.min(left + 1, inFrames - 1);
            double blend = source - left;
            for (int channel = 0; channel < channels; channel++) {
                int a = samples[left * channels + channel];
                int b = samples[right * channels + channel];
                out[frame * channels + channel] = (short) Math.round(a + (b - a) * blend);
            }
        }
        return out;
    }

    private static int readLe16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int readLe32(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }
}
