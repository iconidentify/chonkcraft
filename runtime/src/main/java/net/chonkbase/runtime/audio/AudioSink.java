package net.chonkbase.runtime.audio;

/**
 * Device boundary for rendered PCM.
 *
 * <p>Platform implementations own sample conversion and device interaction
 * behind this interface; the mixer and its tests remain deterministic. The
 * Java Sound implementation uses the OS default route and performs bounded,
 * fail-soft route recovery behind this boundary.
 */
public interface AudioSink extends AutoCloseable {
    void open(PcmFormat format, int bufferFrames) throws Exception;

    void start() throws Exception;

    /**
     * Writes interleaved floating-point frames and returns the number of frames
     * accepted. The sink must not retain the reusable buffer.
     */
    int write(float[] interleavedSamples, int frameOffset, int frameCount) throws Exception;

    void stop();

    @Override
    void close();
}
