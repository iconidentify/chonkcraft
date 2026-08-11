package net.chonkbase.runtime.audio;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Strict loader for uncompressed RIFF/WAVE PCM assets.
 *
 * <p>The content pipeline is expected to deliver 48 kHz signed 16-bit mono or
 * stereo files. Rejecting every other layout here keeps sample conversion and
 * resampling off the game and audio threads.
 */
public final class WavPcmLoader {
    public static final int DEFAULT_MAX_PCM_BYTES = 128 * 1024 * 1024;

    private static final int RIFF_HEADER_BYTES = 12;
    private static final int PCM_FORMAT_CODE = 1;

    private WavPcmLoader() {}

    public static PcmClip load(String debugName, InputStream source) throws IOException {
        return load(debugName, source, DEFAULT_MAX_PCM_BYTES);
    }

    public static PcmClip load(String debugName, InputStream source, int maxPcmBytes) throws IOException {
        Objects.requireNonNull(source, "source");
        if (maxPcmBytes <= 0) {
            throw new IllegalArgumentException("maxPcmBytes must be positive");
        }

        try (BufferedInputStream input = new BufferedInputStream(source)) {
            String riff = readFourCc(input);
            long riffSize = readU32(input);
            String wave = readFourCc(input);
            if (!"RIFF".equals(riff) || !"WAVE".equals(wave)) {
                throw new IOException("not a RIFF/WAVE file");
            }
            if (riffSize < 4L || riffSize > (long) maxPcmBytes + (16L * 1024L * 1024L)) {
                throw new IOException("WAV container exceeds configured limit: " + riffSize);
            }

            Format format = null;
            short[] samples = null;
            long remainingRiffBytes = riffSize - 4L;
            while (remainingRiffBytes >= 8L) {
                String chunkId = readFourCc(input);
                long chunkSize = readU32(input);
                remainingRiffBytes -= 8L;
                long paddedSize = chunkSize + (chunkSize & 1L);
                if (paddedSize > remainingRiffBytes) {
                    throw new EOFException("WAV chunk exceeds RIFF boundary: " + chunkId);
                }

                if ("fmt ".equals(chunkId)) {
                    format = readFormat(input, chunkSize);
                } else if ("data".equals(chunkId)) {
                    if (format == null) {
                        throw new IOException("WAV data chunk appears before fmt chunk");
                    }
                    samples = readPcmData(input, chunkSize, format, maxPcmBytes);
                } else {
                    skipFully(input, chunkSize);
                }
                if ((chunkSize & 1L) != 0L) {
                    skipFully(input, 1L);
                }
                remainingRiffBytes -= paddedSize;
                if (format != null && samples != null) {
                    return PcmClip.fromOwnedSamples(debugName, format.channels, samples);
                }
            }
            throw new IOException(format == null ? "WAV fmt chunk is missing" : "WAV data chunk is missing");
        }
    }

    public static PcmClip loadResource(Class<?> resourceOwner, String resourcePath) throws IOException {
        Objects.requireNonNull(resourceOwner, "resourceOwner");
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }
        InputStream input = resourceOwner.getResourceAsStream(resourcePath);
        if (input == null) {
            throw new IOException("WAV resource not found: " + resourcePath);
        }
        return load(resourcePath, input);
    }

    private static Format readFormat(InputStream input, long chunkSize) throws IOException {
        if (chunkSize < 16L) {
            throw new IOException("WAV fmt chunk is too small: " + chunkSize);
        }
        int code = readU16(input);
        int channels = readU16(input);
        long sampleRate = readU32(input);
        long byteRate = readU32(input);
        int blockAlign = readU16(input);
        int bitsPerSample = readU16(input);
        skipFully(input, chunkSize - 16L);

        if (code != PCM_FORMAT_CODE) {
            throw new IOException("WAV must use integer PCM format 1, found " + code);
        }
        if (channels != 1 && channels != 2) {
            throw new IOException("WAV must be mono or stereo, found " + channels + " channels");
        }
        if (sampleRate != PcmFormat.GAME_SAMPLE_RATE) {
            throw new IOException("WAV must be 48000 Hz, found " + sampleRate);
        }
        if (bitsPerSample != 16) {
            throw new IOException("WAV must be 16-bit PCM, found " + bitsPerSample);
        }
        int expectedBlockAlign = channels * Short.BYTES;
        long expectedByteRate = sampleRate * expectedBlockAlign;
        if (blockAlign != expectedBlockAlign || byteRate != expectedByteRate) {
            throw new IOException("WAV PCM alignment metadata is inconsistent");
        }
        return new Format(channels, blockAlign);
    }

    private static short[] readPcmData(InputStream input, long chunkSize, Format format, int maxPcmBytes)
            throws IOException {
        if (chunkSize <= 0L || chunkSize > maxPcmBytes || chunkSize > Integer.MAX_VALUE) {
            throw new IOException("WAV PCM data exceeds configured limit: " + chunkSize);
        }
        if (chunkSize % format.blockAlign != 0L) {
            throw new IOException("WAV PCM data does not contain complete frames");
        }
        int sampleCount = Math.toIntExact(chunkSize / Short.BYTES);
        short[] samples = new short[sampleCount];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (short) readU16(input);
        }
        return samples;
    }

    private static String readFourCc(InputStream input) throws IOException {
        byte[] fourCc = input.readNBytes(4);
        if (fourCc.length != 4) {
            throw new EOFException("truncated WAV chunk identifier");
        }
        return new String(fourCc, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static int readU16(InputStream input) throws IOException {
        int low = input.read();
        int high = input.read();
        if ((low | high) < 0) {
            throw new EOFException("truncated WAV integer");
        }
        return low | (high << 8);
    }

    private static long readU32(InputStream input) throws IOException {
        long low = readU16(input);
        long high = readU16(input);
        return low | (high << 16);
    }

    private static void skipFully(InputStream input, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0L) {
            long skipped = input.skip(remaining);
            if (skipped > 0L) {
                remaining -= skipped;
                continue;
            }
            if (input.read() < 0) {
                throw new EOFException("truncated WAV chunk");
            }
            remaining--;
        }
    }

    private record Format(int channels, int blockAlign) {}
}
