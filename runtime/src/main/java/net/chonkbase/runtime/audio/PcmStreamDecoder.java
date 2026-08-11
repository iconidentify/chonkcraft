package net.chonkbase.runtime.audio;

import java.io.IOException;

/**
 * Sequential PCM16 decoder used exclusively by a {@link PcmStream} worker.
 *
 * <p>Implementations may block, allocate, decode, seek, and perform file or
 * resource I/O. The stream owns one decoder instance and never invokes these
 * methods from the mixer/render thread.
 */
public interface PcmStreamDecoder extends AutoCloseable {
    int channels();

    int frameCount();

    /**
     * Reads up to {@code maxFrames} at the current source position into an
     * interleaved destination and advances that position.
     */
    int readFrames(short[] destination, int destinationFrameOffset, int maxFrames)
            throws IOException;

    /** Moves the next read to the supplied absolute source frame. */
    void seekFrame(int sourceFrame) throws IOException;

    @Override
    void close() throws IOException;
}
