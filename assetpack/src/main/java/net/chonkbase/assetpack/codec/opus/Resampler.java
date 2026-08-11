package net.chonkbase.assetpack.codec.opus;

import java.util.Arrays;

/**
 * A streaming rational resampler, used on both sides of the codec.
 *
 * <p>New infrastructure with no upstream analogue: RFC 6716's reference encoder
 * does not resample, it only accepts the five rates that divide 48 kHz and
 * reaches them by zero-stuffing. Opus is defined at 48 kHz and nothing else, so
 * every rate a game actually holds audio at needs converting twice -- up on the
 * way into the encoder and back down on the way out of the decoder -- and this
 * is the filter that does both. It lived inside {@link OpusEncoder} while only
 * the upward direction existed; the arithmetic is unchanged, only the two rates
 * are now parameters rather than one of them being a constant.
 *
 * <p>The kernel is a Blackman-Harris-windowed sinc with {@value #LOBES} zero
 * crossings each side, cut off at {@value #CUTOFF} of the lower of the two
 * Nyquist frequencies. Cutting at the lower of the two is the part to get right
 * in both directions: upsampling has to pass the whole input band, downsampling
 * has to stop at the output's Nyquist, and a filter written with that ratio the
 * wrong way up throttles 11025 Hz input to 1.2 kHz -- which is not silence and
 * not obviously broken, just a sound effect that has lost everything above the
 * fundamental.
 *
 * <p>Rational rather than fractional-phase: the ratio {@code L/M} is exact, so a
 * stream of any length ends on exactly the sample it should and there is no
 * accumulated phase error. {@code L} is at most 640 for the rates in range, so
 * the table of {@code L} phases is small enough to build in the constructor.
 *
 * <p><b>Zero delay.</b> Output sample {@code o} is the kernel centred on input
 * time {@code o * inputRate / outputRate}, so the output is time-aligned with
 * the input and a caller does not have to compensate. What it does mean is that
 * the first outputs read input from before the stream began and the last read
 * input from after it ended; both are taken as silence, which is what a file
 * with a hard start and a hard end already implies.
 */
public final class Resampler {

    /** Zero crossings of the sinc each side of the kernel's centre. */
    public static final int LOBES = 24;

    /**
     * How far below Nyquist the kernel cuts off.
     *
     * <p>A kernel this length cannot turn over instantly, so the cutoff is
     * pulled down to leave the transition band inside the passband rather than
     * outside it. Cutting exactly at Nyquist instead would fold the top of the
     * transition band back down as aliasing.
     */
    public static final double CUTOFF = 0.94;

    private final int inputRate;
    private final int outputRate;
    private final int channels;

    /** Output samples per {@link #down} input samples; {@code outputRate/gcd}. */
    private final int up;

    /** Input samples per {@link #up} output samples; {@code inputRate/gcd}. */
    private final int down;

    /** {@code up} phases of {@code 2*wings+1} taps each, phase-major. */
    private final float[] taps;

    /**
     * Input samples the kernel reaches each side of its centre.
     *
     * <p>{@link #LOBES} when upsampling, where the filter passes the whole input
     * band and its zero crossings are one input sample apart. More when
     * downsampling, where the cutoff comes down and the crossings spread out:
     * holding the wing count in input samples instead would truncate the kernel
     * after a couple of lobes and leave the stopband 20 dB worse.
     */
    private final int wings;

    /** Taps per phase. */
    private final int width;

    /** The last {@code 2*wings} input samples per channel, interleaved. */
    private final float[] history;

    /** Absolute index of the next input sample to arrive. */
    private long inputCount;

    /** Absolute index of the next output sample to produce. */
    private long outputCount;

    /** Scratch holding the history followed by this call's input. */
    private float[] work;

    /**
     * @param inputRate  the rate the samples handed to {@link #process} are at
     * @param outputRate the rate they come out at
     * @param channels   how many interleaved channels
     */
    public Resampler(int inputRate, int outputRate, int channels) {
        if (inputRate <= 0 || outputRate <= 0) {
            throw new IllegalArgumentException("cannot resample " + inputRate
                    + " Hz to " + outputRate + " Hz");
        }
        if (channels < 1) {
            throw new IllegalArgumentException("cannot resample " + channels + " channels");
        }
        int g = gcd(inputRate, outputRate);
        this.inputRate = inputRate;
        this.outputRate = outputRate;
        this.channels = channels;
        this.up = outputRate / g;
        this.down = inputRate / g;
        // The filter has to pass the whole of whichever band is narrower: the
        // input's when upsampling, the output's when downsampling. Measured in
        // input Nyquists, that is min(1, outRate/inRate).
        double band = Math.min(1.0, (double) up / down);
        double cutoff = CUTOFF * band;
        this.wings = (int) Math.ceil(LOBES / band);
        this.width = 2 * wings + 1;
        this.history = new float[channels * 2 * wings];
        this.taps = buildTaps(up, cutoff, wings, width);
        this.work = new float[channels * (2 * wings + 1024)];
    }

    /** The rate the input is at. */
    public int inputRate() {
        return inputRate;
    }

    /** The rate the output is at. */
    public int outputRate() {
        return outputRate;
    }

    /** How many interleaved channels. */
    public int channels() {
        return channels;
    }

    /**
     * Input samples the kernel reaches past its centre.
     *
     * <p>A caller draining a finished stream has to push this many silent input
     * samples through before the last real output appears.
     */
    public int wings() {
        return wings;
    }

    /** Forgets every sample seen so far. */
    public void reset() {
        Arrays.fill(history, 0.0f);
        inputCount = 0;
        outputCount = 0;
    }

    /**
     * How many output samples per channel {@code count} input samples will
     * produce, so the caller can size the destination before the work starts.
     */
    public int outputFor(int count) {
        long available = inputCount + count;
        // An output at index o reads input up to base+wings, where
        // base = floor(o*down/up); it is producible once that index has arrived.
        long o = outputCount;
        long produced = 0;
        while (((o * down) / up) + wings < available) {
            o++;
            produced++;
        }
        return (int) produced;
    }

    /**
     * Resamples {@code count} input samples per channel, appending interleaved
     * output samples to {@code dst}.
     *
     * @return how many interleaved samples were written
     */
    public int process(short[] pcm, int pcmOffset, int count, float[] dst, int dstOffset) {
        int histSamples = 2 * wings;
        int workNeeded = channels * (histSamples + count);
        if (work.length < workNeeded) {
            work = new float[workNeeded];
        }
        System.arraycopy(history, 0, work, 0, channels * histSamples);
        for (int i = 0; i < channels * count; i++) {
            work[channels * histSamples + i] = pcm[pcmOffset + i];
        }

        // work[0] is absolute input index inputCount - histSamples.
        long origin = inputCount - histSamples;
        long available = inputCount + count;
        int written = 0;
        while (((outputCount * down) / up) + wings < available) {
            long numerator = outputCount * down;
            long base = numerator / up;
            int phase = (int) (numerator - base * up);
            int tapBase = phase * width;
            int first = (int) (base - wings - origin);
            for (int c = 0; c < channels; c++) {
                float sum = 0;
                int at = channels * first + c;
                // Taps run from the oldest sample to the newest, which is the
                // reverse of the polyphase index, so the table is built reversed
                // and read forwards.
                for (int t = 0; t < width; t++) {
                    sum += taps[tapBase + t] * work[at];
                    at += channels;
                }
                dst[dstOffset + written + c] = sum;
            }
            written += channels;
            outputCount++;
        }

        inputCount = available;
        int keepFrom = channels * (histSamples + count) - channels * histSamples;
        System.arraycopy(work, keepFrom, history, 0, channels * histSamples);
        return written;
    }

    /**
     * Resamples a whole buffer in one call and returns exactly
     * {@code outputFrames} frames.
     *
     * <p>The length is a parameter rather than a result because both callers
     * already know it and neither can tolerate being a sample out. A pack
     * records the frame count an asset had before it was encoded, and the
     * archive entry rebuilt from it has to be that long: one frame short is a
     * clipped consonant on a unit's acknowledgement, and one frame long is a
     * sample of the next thing in the buffer. Where the filter runs out of
     * input the tail is silence, which is what the end of a file is anyway.
     *
     * @param pcm          interleaved input
     * @param channels     how many channels it interleaves
     * @param inputRate    the rate it is at
     * @param outputRate   the rate to produce
     * @param outputFrames how many frames per channel to return
     */
    public static short[] resample(short[] pcm, int channels, int inputRate, int outputRate,
            int outputFrames) {
        short[] out = new short[Math.max(0, outputFrames) * channels];
        if (out.length == 0) {
            return out;
        }
        if (inputRate == outputRate) {
            System.arraycopy(pcm, 0, out, 0, Math.min(pcm.length, out.length));
            return out;
        }

        Resampler resampler = new Resampler(inputRate, outputRate, channels);
        int frames = pcm.length / channels;
        // Enough silence after the audio for the kernel to reach the last real
        // output. One wing is what it reaches past its centre; two is free.
        int tail = 2 * resampler.wings() + 1;
        float[] scratch = new float[(resampler.outputFor(frames + tail)) * channels];
        int written = resampler.process(pcm, 0, frames, scratch, 0);
        written += resampler.process(new short[channels * tail], 0, tail, scratch, written);

        int copy = Math.min(written, out.length);
        for (int i = 0; i < copy; i++) {
            out[i] = clampToShort(scratch[i]);
        }
        return out;
    }

    /**
     * Rounds and clamps a resampled sample back to 16 bits.
     *
     * <p>The kernel overshoots on a step, so a signal already at full scale
     * comes out past it. Wrapping instead of clamping would turn the loudest
     * sample of a clipped recording into the quietest, which is a click on
     * exactly the material where a click is most audible.
     */
    public static short clampToShort(float x) {
        float r = (float) Math.rint(x);
        if (r < -32768.0f) {
            return -32768;
        }
        if (r > 32767.0f) {
            return 32767;
        }
        return (short) r;
    }

    /**
     * The polyphase table: for each phase, a windowed sinc sampled at the
     * offsets that phase needs.
     *
     * <p>Each phase is normalised to sum to one. The window already makes the
     * sum very close to one, but "very close" is a gain that varies with phase,
     * and a gain that varies at the output rate is amplitude modulation --
     * audible on a sustained tone as a buzz at the beat frequency between the
     * two sample rates.
     */
    private static float[] buildTaps(int up, double cutoff, int wings, int width) {
        float[] table = new float[up * width];
        for (int phase = 0; phase < up; phase++) {
            double[] row = new double[width];
            double sum = 0;
            for (int t = 0; t < width; t++) {
                // Distance in input samples from this tap to the exact output
                // position, which sits phase/up of the way between two input
                // samples.
                double x = (t - wings) - (double) phase / up;
                double value = sinc(cutoff * x) * blackmanHarris(x, wings);
                row[t] = value;
                sum += value;
            }
            for (int t = 0; t < width; t++) {
                table[phase * width + t] = (float) (row[t] / sum);
            }
        }
        return table;
    }

    private static double sinc(double x) {
        if (x == 0.0) {
            return 1.0;
        }
        double px = Math.PI * x;
        return StrictMath.sin(px) / px;
    }

    /** The four-term Blackman-Harris window, whose sidelobes are 92 dB down. */
    private static double blackmanHarris(double x, int wings) {
        if (Math.abs(x) >= wings) {
            return 0.0;
        }
        double t = 2.0 * Math.PI * (x + wings) / (2.0 * wings);
        return 0.35875
                - 0.48829 * StrictMath.cos(t)
                + 0.14128 * StrictMath.cos(2 * t)
                - 0.01168 * StrictMath.cos(3 * t);
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    @Override
    public String toString() {
        return "Resampler[" + inputRate + " Hz to " + outputRate + " Hz, " + channels
                + " channels, " + width + " taps]";
    }
}
