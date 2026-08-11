package net.chonkbase.runtime.audio;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Reopenable, bounded-memory decoder for streamed 48 kHz PCM16 RIFF/WAVE
 * sources.
 *
 * <p>Construction, reads, seeks, and closure are performed by
 * {@link PcmStream}'s background worker. A seek reopens the source and skips to
 * an exact frame, which also supports packaged-resource loops without holding
 * the complete PCM payload in memory.
 */
public final class WavPcmStreamDecoder implements PcmStreamDecoder {
    public static final long DEFAULT_MAX_PCM_BYTES =
            Integer.MAX_VALUE;
    public static final int READ_BUFFER_BYTES = 16 * 1024;

    private static final int PCM_FORMAT_CODE = 1;

    @FunctionalInterface
    public interface InputStreamFactory {
        InputStream open() throws IOException;
    }

    private final InputStreamFactory inputFactory;
    private final long maxPcmBytes;
    private final byte[] byteBuffer = new byte[READ_BUFFER_BYTES];

    private BufferedInputStream input;
    private int channels;
    private int blockAlign;
    private int frameCount;
    private int positionFrame;
    private long dataBytes;
    private boolean closed;

    private WavPcmStreamDecoder(
            InputStreamFactory inputFactory, long maxPcmBytes)
            throws IOException {
        this.inputFactory =
                Objects.requireNonNull(inputFactory, "inputFactory");
        if (maxPcmBytes <= 0L
                || maxPcmBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "maxPcmBytes must be within 1..Integer.MAX_VALUE");
        }
        this.maxPcmBytes = maxPcmBytes;
        reopenAt(0);
    }

    public static PcmStream.DecoderFactory factory(
            InputStreamFactory inputFactory) {
        return factory(inputFactory, DEFAULT_MAX_PCM_BYTES);
    }

    public static PcmStream.DecoderFactory factory(
            InputStreamFactory inputFactory, long maxPcmBytes) {
        Objects.requireNonNull(inputFactory, "inputFactory");
        if (maxPcmBytes <= 0L
                || maxPcmBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "maxPcmBytes must be within 1..Integer.MAX_VALUE");
        }
        return () ->
                new WavPcmStreamDecoder(inputFactory, maxPcmBytes);
    }

    public static PcmStream.DecoderFactory path(Path path) {
        Objects.requireNonNull(path, "path");
        Path absolute = path.toAbsolutePath().normalize();
        return factory(() -> Files.newInputStream(absolute));
    }

    public static PcmStream.DecoderFactory resource(
            Class<?> resourceOwner, String resourcePath) {
        Objects.requireNonNull(resourceOwner, "resourceOwner");
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException(
                    "resourcePath must not be blank");
        }
        return factory(
                () -> {
                    InputStream stream =
                            resourceOwner.getResourceAsStream(resourcePath);
                    if (stream == null) {
                        throw new IOException(
                                "WAV resource not found: "
                                        + resourcePath);
                    }
                    return stream;
                });
    }

    @Override
    public int channels() {
        return channels;
    }

    @Override
    public int frameCount() {
        return frameCount;
    }

    @Override
    public int readFrames(
            short[] destination,
            int destinationFrameOffset,
            int maxFrames)
            throws IOException {
        requireOpen();
        Objects.requireNonNull(destination, "destination");
        if (destinationFrameOffset < 0 || maxFrames < 0) {
            throw new IllegalArgumentException(
                    "frame offset and count must not be negative");
        }
        long requiredSamples =
                ((long) destinationFrameOffset + maxFrames)
                        * channels;
        if (requiredSamples > destination.length) {
            throw new IllegalArgumentException(
                    "destination is too small for requested frames");
        }
        int framesToRead =
                Math.min(maxFrames, frameCount - positionFrame);
        long remainingBytes = (long) framesToRead * blockAlign;
        int destinationSample = destinationFrameOffset * channels;
        long decodedBytes = 0L;
        while (remainingBytes > 0) {
            int requested =
                    (int)
                            Math.min(
                                    remainingBytes,
                                    byteBuffer.length);
            int bytes = input.read(byteBuffer, 0, requested);
            if (bytes < 0) {
                throw new EOFException(
                        "WAV data ended before declared frame count");
            }
            if (bytes == 0) {
                continue;
            }
            if ((bytes & 1) != 0) {
                int next = input.read();
                if (next < 0) {
                    throw new EOFException(
                            "WAV data ended inside a PCM16 sample");
                }
                byteBuffer[bytes++] = (byte) next;
            }
            for (int byteIndex = 0;
                    byteIndex < bytes;
                    byteIndex += 2) {
                int low = byteBuffer[byteIndex] & 0xff;
                int high = byteBuffer[byteIndex + 1];
                destination[destinationSample++] =
                        (short) (low | (high << 8));
            }
            remainingBytes -= bytes;
            decodedBytes += bytes;
        }
        int decodedFrames =
                Math.toIntExact(decodedBytes / blockAlign);
        positionFrame += decodedFrames;
        return decodedFrames;
    }

    @Override
    public void seekFrame(int sourceFrame) throws IOException {
        requireOpen();
        if (sourceFrame < 0 || sourceFrame > frameCount) {
            throw new IllegalArgumentException(
                    "sourceFrame exceeds decoded bounds");
        }
        if (sourceFrame == positionFrame) {
            return;
        }
        reopenAt(sourceFrame);
    }

    private void reopenAt(int sourceFrame) throws IOException {
        BufferedInputStream previous = input;
        input = null;
        if (previous != null) {
            previous.close();
        }

        BufferedInputStream candidate =
                new BufferedInputStream(
                        Objects.requireNonNull(
                                inputFactory.open(),
                                "inputFactory returned null"));
        try {
            Header header = readHeader(candidate);
            if (channels != 0
                    && (channels != header.channels()
                            || frameCount != header.frameCount())) {
                throw new IOException(
                        "WAV metadata changed while reopening stream");
            }
            if (sourceFrame > header.frameCount()) {
                throw new IllegalArgumentException(
                        "sourceFrame exceeds decoded bounds");
            }
            skipFully(
                    candidate,
                    (long) sourceFrame * header.blockAlign());
            channels = header.channels();
            blockAlign = header.blockAlign();
            frameCount = header.frameCount();
            dataBytes = header.dataBytes();
            positionFrame = sourceFrame;
            input = candidate;
            closed = false;
        } catch (Throwable failure) {
            try {
                candidate.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private Header readHeader(InputStream source) throws IOException {
        String riff = readFourCc(source);
        long riffSize = readU32(source);
        String wave = readFourCc(source);
        if (!"RIFF".equals(riff) || !"WAVE".equals(wave)) {
            throw new IOException("not a RIFF/WAVE file");
        }
        if (riffSize < 4L) {
            throw new IOException("invalid RIFF size");
        }

        Format format = null;
        long remainingRiffBytes = riffSize - 4L;
        while (remainingRiffBytes >= 8L) {
            String chunkId = readFourCc(source);
            long chunkSize = readU32(source);
            remainingRiffBytes -= 8L;
            long paddedSize = chunkSize + (chunkSize & 1L);
            if (paddedSize > remainingRiffBytes) {
                throw new EOFException(
                        "WAV chunk exceeds RIFF boundary: " + chunkId);
            }
            if ("fmt ".equals(chunkId)) {
                format = readFormat(source, chunkSize);
            } else if ("data".equals(chunkId)) {
                if (format == null) {
                    throw new IOException(
                            "WAV data chunk appears before fmt chunk");
                }
                if (chunkSize <= 0L || chunkSize > maxPcmBytes) {
                    throw new IOException(
                            "WAV PCM data exceeds configured limit: "
                                    + chunkSize);
                }
                if (chunkSize % format.blockAlign() != 0L) {
                    throw new IOException(
                            "WAV PCM data does not contain complete frames");
                }
                long frames = chunkSize / format.blockAlign();
                if (frames > Integer.MAX_VALUE) {
                    throw new IOException(
                            "WAV frame count exceeds runtime limit: "
                                    + frames);
                }
                return new Header(
                        format.channels(),
                        format.blockAlign(),
                        Math.toIntExact(frames),
                        chunkSize);
            } else {
                skipFully(source, chunkSize);
                if ((chunkSize & 1L) != 0L) {
                    skipFully(source, 1L);
                }
            }
            remainingRiffBytes -= paddedSize;
        }
        throw new IOException(
                format == null
                        ? "WAV fmt chunk is missing"
                        : "WAV data chunk is missing");
    }

    private static Format readFormat(InputStream input, long chunkSize)
            throws IOException {
        if (chunkSize < 16L) {
            throw new IOException(
                    "WAV fmt chunk is too small: " + chunkSize);
        }
        int code = readU16(input);
        int channels = readU16(input);
        long sampleRate = readU32(input);
        long byteRate = readU32(input);
        int blockAlign = readU16(input);
        int bitsPerSample = readU16(input);
        skipFully(input, chunkSize - 16L);
        if ((chunkSize & 1L) != 0L) {
            skipFully(input, 1L);
        }

        if (code != PCM_FORMAT_CODE) {
            throw new IOException(
                    "WAV must use integer PCM format 1, found " + code);
        }
        if (channels != 1 && channels != 2) {
            throw new IOException(
                    "WAV must be mono or stereo, found "
                            + channels
                            + " channels");
        }
        if (sampleRate != PcmFormat.GAME_SAMPLE_RATE) {
            throw new IOException(
                    "WAV must be 48000 Hz, found " + sampleRate);
        }
        if (bitsPerSample != 16) {
            throw new IOException(
                    "WAV must be 16-bit PCM, found " + bitsPerSample);
        }
        int expectedBlockAlign = channels * Short.BYTES;
        long expectedByteRate = sampleRate * expectedBlockAlign;
        if (blockAlign != expectedBlockAlign
                || byteRate != expectedByteRate) {
            throw new IOException(
                    "WAV PCM alignment metadata is inconsistent");
        }
        return new Format(channels, blockAlign);
    }

    private static String readFourCc(InputStream input)
            throws IOException {
        byte[] fourCc = input.readNBytes(4);
        if (fourCc.length != 4) {
            throw new EOFException(
                    "truncated WAV chunk identifier");
        }
        return new String(fourCc, StandardCharsets.US_ASCII);
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

    private static void skipFully(InputStream input, long bytes)
            throws IOException {
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

    private void requireOpen() throws IOException {
        if (closed || input == null) {
            throw new IOException("WAV stream decoder is closed");
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        BufferedInputStream current = input;
        input = null;
        if (current != null) {
            current.close();
        }
    }

    long dataBytes() {
        return dataBytes;
    }

    private record Format(int channels, int blockAlign) {}

    private record Header(
            int channels, int blockAlign, int frameCount, long dataBytes) {}
}
