package net.chonkbase.assetpack.codec.opus;

import java.util.Arrays;
import java.util.Objects;

/**
 * An Opus encoder: interleaved 16-bit PCM in, packets out.
 *
 * <p>The framing half of {@code opus_encode_native} in {@code src/opus_encoder.c}
 * -- the table of contents byte of RFC 6716 section 3.1 -- wrapped around
 * {@link CeltEncoder}, which is the codec. Packets are code 0: one frame each,
 * one byte of framing, which is what an encoder emitting a single frame per
 * packet should use and what {@link OpusPacket} reads back.
 *
 * <p><b>CELT only, configurations 28 to 31.</b> Fullband CELT at 2.5, 5, 10 or
 * 20 ms. No SILK and no Hybrid, which is a deliberate narrowing and is
 * conformant: RFC 6716 requires a decoder to handle every mode and allows an
 * encoder to emit any subset. It is also what libopus itself picks for stereo
 * music at 128 kb/s. What it costs is speech below about 32 kb/s; see
 * {@link CeltEncoder} for the rest of the scope and for why the bitrate is
 * constant rather than variable.
 *
 * <h2>Input rates other than 48 kHz</h2>
 *
 * <p>Opus is defined at 48 kHz and this encodes at 48 kHz. Input at any other
 * rate is resampled here by {@link Resampler}, a polyphase windowed-sinc
 * interpolator evaluated on the exact rational ratio between the two rates.
 *
 * <p>Two things made that the choice rather than something cheaper. The rates
 * that matter here are the game's 11025 and 22050 Hz effects, and neither
 * divides 48000 -- the ratios are 640/147 and 320/147 -- so the reference's own
 * trick of zero-stuffing by an integer factor and scaling the MDCT bins, which
 * is how it reaches 8, 12, 16 and 24 kHz, cannot be used. And linear
 * interpolation, which is the obvious cheap answer, has a response that is 3 dB
 * down at a fifth of Nyquist and leaves images only 26 dB below the signal;
 * on an 8-bit sound effect resampled up by 4.35 that is heard as a gritty edge
 * on every sample, which is exactly the artefact a listener would blame on the
 * codec. The windowed sinc holds its images below -90 dB, under the 8-bit
 * source's own noise floor.
 *
 * <p>Because the ratio is not a whole number of samples per frame -- 20 ms at
 * 11025 Hz is 220.5 samples -- resampled audio is buffered rather than
 * converted frame by frame. {@link #encode} takes as many or as few input
 * samples as the caller has, and emits at most one packet per call, returning
 * zero when the buffer does not yet hold a whole frame. Passing zero samples
 * drains what is already there. At 48 kHz there is no buffer and no resampler:
 * a call with exactly one frame's worth of samples always produces exactly one
 * packet.
 */
public final class OpusEncoder {

    /** The rate Opus is defined at and this encodes at. */
    public static final int CODEC_RATE = 48_000;

    /** The default frame length, and the one this is tuned for. */
    public static final int DEFAULT_FRAME_MICROS = 20_000;

    /**
     * The first fullband CELT configuration; the four from here up are 2.5, 5,
     * 10 and 20 ms.
     *
     * <p>RFC 6716 section 3.1 Table 2. Fullband because CELT's band layout
     * reaches 20 kHz whatever the material, and a narrower configuration would
     * only mean the encoder had thrown the top of the spectrum away before the
     * allocator got a chance to decide it was not worth bits.
     */
    private static final int FULLBAND_CELT_CONFIG = 28;

    private final int sampleRate;
    private final int channels;
    private final int frameMicros;
    private final int frameSize48k;
    private final CeltEncoder celt;
    private final Resampler resampler;

    /** Resampled 48 kHz samples not yet made into a frame, interleaved. */
    private float[] queue;

    /** How many interleaved samples of {@link #queue} are live. */
    private int queued;

    /** The frame handed to {@link CeltEncoder}, on the 16-bit scale. */
    private final short[] frame;

    /**
     * Builds an encoder for 20 ms frames.
     *
     * @param sampleRate the rate of the PCM handed to {@link #encode}, 8000 to
     *                   192000; anything but {@value #CODEC_RATE} is resampled
     * @param channels   1 or 2
     * @param bitrateBps the target rate in bits per second, 500 to 512000
     */
    public OpusEncoder(int sampleRate, int channels, int bitrateBps) {
        this(sampleRate, channels, bitrateBps, DEFAULT_FRAME_MICROS);
    }

    /**
     * Builds an encoder for frames of a chosen length.
     *
     * @param frameMicros 2500, 5000, 10000 or 20000 microseconds. Shorter frames
     *                    cut the algorithmic delay and cost bits: every frame
     *                    pays the same fixed overhead for its flags and its
     *                    coarse energy, so 10 ms frames spend roughly a fifth
     *                    more of the budget on side information than 20 ms ones
     *                    do at the same rate.
     */
    public OpusEncoder(int sampleRate, int channels, int bitrateBps, int frameMicros) {
        if (sampleRate < 8000 || sampleRate > 192_000) {
            throw new IllegalArgumentException("an input sample rate of " + sampleRate
                    + " Hz is outside the 8000 to 192000 this encoder resamples from");
        }
        if (channels != 1 && channels != 2) {
            throw new IllegalArgumentException("Opus encodes 1 or 2 channels, not " + channels);
        }
        this.frameSize48k = switch (frameMicros) {
            case 2_500 -> 120;
            case 5_000 -> 240;
            case 10_000 -> 480;
            case 20_000 -> 960;
            default -> throw new IllegalArgumentException("a CELT frame is 2500, 5000, 10000"
                    + " or 20000 microseconds, not " + frameMicros);
        };
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.frameMicros = frameMicros;
        this.celt = new CeltEncoder(channels, bitrateBps);
        this.resampler = sampleRate == CODEC_RATE
                ? null
                : new Resampler(sampleRate, CODEC_RATE, channels);
        this.frame = new short[channels * frameSize48k];
        this.queue = new float[channels * frameSize48k * 2];
        this.queued = 0;
    }

    /** The rate the caller's PCM is at. */
    public int sampleRate() {
        return sampleRate;
    }

    /** How many channels this encodes. */
    public int channels() {
        return channels;
    }

    /** The frame length in microseconds. */
    public int frameMicros() {
        return frameMicros;
    }

    /** How many 48 kHz samples per channel one packet carries. */
    public int frameSize48k() {
        return frameSize48k;
    }

    /**
     * How many input samples per channel make one frame at the input rate.
     *
     * <p>Exact at 48 kHz and at every rate that divides it. At 11025 Hz a 20 ms
     * frame is 220.5 input samples and this rounds, which is why the buffered
     * form of {@link #encode} exists.
     */
    public int frameSamples() {
        return (int) ((long) frameSize48k * sampleRate / CODEC_RATE);
    }

    /** The largest packet this can produce at the current rate, including framing. */
    public int maxPacketBytes() {
        return 1 + celt.frameBytes(frameSize48k);
    }

    /** How many 48 kHz samples per channel are buffered and not yet encoded. */
    public int pendingSamples() {
        return queued / channels;
    }

    /** The target rate in bits per second, not counting the framing byte. */
    public int bitrate() {
        return celt.bitrate();
    }

    /**
     * Changes the target rate, {@code OPUS_SET_BITRATE}.
     *
     * <p>Takes effect on the next packet. The framing byte is not counted, so a
     * stream's true rate is this plus 400 bits per second at 20 ms frames.
     */
    public void setBitrate(int bps) {
        celt.setBitrate(bps);
    }

    /**
     * The energy envelope the last packet settled on; see
     * {@link CeltEncoder#quantisedBandEnergy()}.
     */
    public float[] quantisedBandEnergy() {
        return celt.quantisedBandEnergy();
    }

    /**
     * Whether the last packet coded its energy envelope without reference to the
     * packet before; see {@link CeltEncoder#lastFrameCodedIntra()}.
     */
    public boolean lastFrameCodedIntra() {
        return celt.lastFrameCodedIntra();
    }

    /**
     * The range coder state the last packet ended on.
     *
     * <p>Equal to {@link OpusDecoder#finalRange()} after that decoder has read
     * the same packet. Comparing the two is a bit-exact check that every symbol
     * was written and read identically -- unlike the audio, it cannot be
     * excused by floating-point rounding.
     */
    public long finalRange() {
        return celt.finalRange();
    }

    /**
     * Forgets every packet encoded so far, as {@code OPUS_RESET_STATE} does.
     *
     * <p>Drops whatever is buffered, so anything not yet emitted is lost. Needed
     * before encoding an unrelated stream with the same encoder: the MDCT
     * overlap, the pre-emphasis memory and the energy envelope all carry across
     * frames, and without a reset the first frame of the new stream is predicted
     * from the tail of the old one, which is heard as a burst of noise lasting
     * a frame or two.
     */
    public void reset() {
        celt.reset();
        if (resampler != null) {
            resampler.reset();
        }
        queued = 0;
    }

    /**
     * Encodes at most one packet.
     *
     * <p>Takes {@code frameSamples} samples per channel at the encoder's input
     * rate, resamples them to 48 kHz if the rates differ, and emits one packet
     * as soon as a whole frame has accumulated. Feeding one frame's worth per
     * call is the normal use; feeding more leaves the surplus buffered for the
     * next call, and feeding zero drains what is already buffered.
     *
     * @param pcm       interleaved 16-bit input
     * @param pcmOffset where in it to start reading
     * @param frameSamples samples per channel to take, which may be zero
     * @param out       receives the packet; needs {@link #maxPacketBytes()}
     * @param outOffset where in it to write
     * @return the packet length in bytes, or 0 when no whole frame was ready
     * @throws IllegalArgumentException if either buffer is too small
     */
    public int encode(short[] pcm, int pcmOffset, int frameSamples, byte[] out, int outOffset) {
        Objects.requireNonNull(pcm, "pcm");
        Objects.requireNonNull(out, "out");
        if (frameSamples < 0) {
            throw new IllegalArgumentException("cannot encode " + frameSamples + " samples");
        }
        long needed = (long) channels * frameSamples;
        if (pcmOffset < 0 || pcm.length - pcmOffset < needed) {
            throw new IllegalArgumentException("the input needs " + needed + " samples at offset "
                    + pcmOffset + " of a " + pcm.length + " array");
        }
        int packetBytes = maxPacketBytes();
        if (outOffset < 0 || out.length - outOffset < packetBytes) {
            throw new IllegalArgumentException("a packet needs " + packetBytes + " bytes at offset "
                    + outOffset + " of a " + out.length + " array");
        }

        if (frameSamples > 0) {
            if (resampler == null) {
                ensureQueue(queued + channels * frameSamples);
                for (int i = 0; i < channels * frameSamples; i++) {
                    queue[queued++] = pcm[pcmOffset + i];
                }
            } else {
                int produced = resampler.outputFor(frameSamples);
                ensureQueue(queued + channels * produced);
                queued += resampler.process(pcm, pcmOffset, frameSamples, queue, queued);
            }
        }

        int wanted = channels * frameSize48k;
        if (queued < wanted) {
            return 0;
        }
        for (int i = 0; i < wanted; i++) {
            frame[i] = Resampler.clampToShort(queue[i]);
        }
        System.arraycopy(queue, wanted, queue, 0, queued - wanted);
        queued -= wanted;

        // The table of contents byte: configuration, stereo flag, and frame
        // count code 0 meaning one frame follows. RFC 6716 section 3.1.
        int config = FULLBAND_CELT_CONFIG + lmForFrameSize(frameSize48k);
        out[outOffset] = (byte) ((config << 3) | ((channels == 2 ? 1 : 0) << 2));
        int length = celt.encode(frame, 0, frameSize48k, out, outOffset + 1);
        return 1 + length;
    }

    private void ensureQueue(int capacity) {
        if (queue.length < capacity) {
            queue = Arrays.copyOf(queue, Math.max(capacity, queue.length * 2));
        }
    }

    private static int lmForFrameSize(int frameSize) {
        return CeltMode.forFrameSize(frameSize).lm();
    }

    @Override
    public String toString() {
        return "OpusEncoder[" + channels + " channels at " + sampleRate + " Hz, "
                + (frameMicros / 1000.0) + " ms frames, " + celt.bitrate() + " bits per second]";
    }

}
