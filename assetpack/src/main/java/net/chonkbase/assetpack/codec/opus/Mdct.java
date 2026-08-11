package net.chonkbase.assetpack.codec.opus;

import java.util.Arrays;

/**
 * Turns a CELT block of frequency coefficients back into overlapping audio, and
 * builds the window that joins one block to the next.
 *
 * <p>A port of {@code clt_mdct_backward} in {@code celt/mdct.c} and of the
 * mixed-radix transform underneath it, {@code opus_ifft} in
 * {@code celt/kiss_fft.c}, together with the overlap window from
 * {@code opus_custom_mode_create} in {@code celt/modes.c:374}. Those three
 * files are part of the reference implementation that RFC 6716 Appendix A
 * makes normative; section 4.3.7 is the prose that describes them.
 *
 * <p>Evaluated as a pre-rotation, an n/2-point complex FFT and a post-rotation
 * rather than as the sum in its own definition. A 960-coefficient block written
 * out directly is 1.8 million multiply-accumulates; measured by
 * {@code MdctTest} on an Apple M3 Pro that is 1819 microseconds against 4.3 for
 * the route taken here, a factor of 420. A stereo 20 ms frame
 * needs two of them, so the transform costs 8.7 microseconds of a 20000
 * microsecond budget where the direct sum would cost 3600 -- a fifth of the
 * frame, before the codec has done anything else.
 *
 * <p>Two separate transforms live behind one set of tables, because CELT needs
 * both. A normal frame is a single long block of {@code n} coefficients; a
 * frame with the transient flag of section 4.3.1 set is 2, 4 or 8 short blocks
 * of {@code n/2}, {@code n/4} or {@code n/8}, interleaved in the coefficient
 * array and handed here one at a time with a {@code stride}. The {@code shift}
 * argument picks the table set, exactly as {@code l->n >> shift} does upstream,
 * so a decoder that switches block size between frames does not reallocate.
 *
 * <p>How a decoder drives it, following {@code compute_inv_mdcts} in
 * {@code celt/celt.c}. Build one {@code Mdct} per channel at
 * {@link CeltMode#SHORT_MDCT_SIZE} times the mode's largest block count, which
 * is 960 at 48 kHz. A frame of {@code 120 << lm} samples that is not transient
 * is one call with {@code n = 120 << lm}, {@code stride = 1} and
 * {@code shift = maxLm - lm}, placed at the running output position. A frame
 * that is transient is {@code 1 << lm} calls with {@code n = 120},
 * {@code stride = 1 << lm} and {@code shift = maxLm}, the block index as the
 * spectrum offset because the short blocks are interleaved, and each output
 * 120 samples after the last. Either way the overlap is
 * {@link CeltMode#OVERLAP}, never the frame size.
 *
 * <p>The window is the part that is easy to get wrong quietly. RFC 6716 draws
 * it as {@code W(n) = sin(pi/2 * sin(pi/2 * (n+1/2)/L))} all squared, but the
 * normative code in {@code modes.c} computes {@code sin(pi/2 * sin(pi/2 *
 * (n+1/2)/L)^2)} -- the square is on the inner sine, not the outer one. Only
 * the code's version is power complementary, which the RFC's own next sentence
 * requires, so the drawing is a typesetting fault and the code is followed
 * here. Building the drawn one instead leaves {@code w[i]^2 + w[L-1-i]^2}
 * peaking at 1.289 rather than 1, and measured over a full analysis and
 * synthesis it leaves 17 percent of the signal's peak amplitude behind as
 * uncancelled aliasing.
 *
 * <p>A plain sine window is the trap next to that one, because it passes the
 * power complementarity check and is still wrong. Aliasing cancels only when
 * the synthesis window is the one the encoder analysed with; substituting a
 * sine leaves the same 17 percent residual as the misdrawn window does. What a
 * listener gets either way is a mirrored copy of each block sitting under the
 * music at the block rate, 50 Hz on a 20 ms frame and 400 Hz on a transient
 * one, which is the faint buzz on sustained notes.
 *
 * <p>Nothing here scales, and RFC 6716 section 4.3.7 says the inverse transform
 * scales by 1/2, so the two need reconciling before someone splits the
 * difference. Compiling {@code clt_mdct_backward} out of the RFC's own tarball
 * and feeding it the same coefficients, its output is the plain unscaled sum
 * below -- the ratio is 1, not 1/2 -- and the only scaling in the pair lives in
 * the forward direction, where {@code opus_fft} folds in {@code 4/N} with
 * {@code N} the 2n-sample transform length. So the encoder hands the decoder
 * coefficients already divided by {@code n/2} and the round trip is unity gain
 * at every block size. The prose is measuring against a convention it does not
 * state; the code is what this follows. A decoder that reads the prose and
 * inserts a 1/2 here, or a 2 to cancel one it thinks is missing, is 6 dB out,
 * and because {@code n/2} is the factor at stake a decoder that scales per
 * block instead is 18 dB louder on a 20 ms frame than on a 2.5 ms one.
 * {@code MdctTest} pins the unity gain across a run of mixed block sizes.
 *
 * <p>Sample data is {@code float}, matching libopus's float build, which is
 * what the conformance vectors were produced with. The twiddle and window
 * tables are computed in {@code double} and stored as {@code float}, as
 * {@code compute_twiddles} and {@code modes.c} do, because a double-precision
 * table would buy nothing once the data it multiplies is single precision and
 * would halve the width of every vectorised multiply. They are computed with
 * {@code StrictMath} rather than {@code Math}: {@code Math.sin} and
 * {@code Math.cos} are allowed to differ by an ulp between JVMs and platforms,
 * and one differing table entry is a decoder that produces different samples on
 * a different machine.
 *
 * <p>This departs from the reference in one place. Upstream stores only
 * {@code cos(2*pi*i/N)} and reaches the rotation it actually wants,
 * {@code exp(i*2*pi*(i+1/8)/N)}, by following the table lookup with a
 * small-angle turn of {@code 2*pi/(8N)} whose cosine it approximates as one.
 * This port stores the exact rotation per block size instead, which is both
 * closer to the transform section 4.3.7 defines and cheaper -- one complex
 * multiply instead of a complex multiply and a shear.
 *
 * <p>The bound on that difference, and it is worth deriving rather than
 * eyeballing, because the shear happens twice and counting it once halves the
 * answer. {@code clt_mdct_backward} shears in its pre-rotation and again in its
 * post-rotation, and each one multiplies by {@code 1 + i*s} where
 * {@code s = 2*pi/(8N) = pi/(8n)}, not by {@code exp(i*s)}. Two of those leave
 * upstream's output high by {@code |1 + i*s|^2 = 1 + s^2}, which is
 * {@code 1.07e-5} at the 120-coefficient block CELT uses for transients and
 * {@code 1.67e-7} at the 960-coefficient one: -99.4 dB at worst, not the -107
 * that counting one shear would suggest. Compiling {@code clt_mdct_backward}
 * out of the RFC's own tarball and fitting the two outputs against each other
 * gives {@code 1 + 1.0653e-5} at {@code n = 120} and {@code 1 + 1.4931e-7} at
 * {@code n = 960}, matching the prediction to three digits at all four sizes.
 *
 * <p>So the deviation is a scalar gain of one part in 100000 on the block, not
 * broadband noise -- there is nothing here for a listener to hear, and no
 * spectral shape for a conformance metric to catch. Measured against the same
 * transform evaluated in double precision, this port is the closer of the two
 * at every block size and every stride, so reverting it would cost accuracy
 * rather than buy it. Revert anyway if a conformance run ever traces an SNR
 * shortfall to the MDCT; it is about ten lines.
 *
 * <p>Not thread safe: the scratch buffers that keep the per-frame path free of
 * allocation are instance fields. Give each decoder its own {@code Mdct}, which
 * is what a decoder does anyway since the tables depend on its frame size.
 */
public final class Mdct {

    /** Enough for any size this accepts: 2, 3, 4 and 5 leave at most 16 stages. */
    private static final int MAX_FACTORS = 16;

    private final int maxSize;
    private final int maxShift;

    /** Per shift, the kiss_fft factorisation as (radix, sub-length) pairs. */
    private final int[][] factors;

    /** Per shift, the stride into the twiddle table for each stage. */
    private final int[][] stageStride;

    /** Per shift, the digit-reversal scatter kiss_fft applies before its butterflies. */
    private final int[][] bitrev;

    /** Per shift, interleaved exp(-2*pi*i*j/nfft) for j in [0, nfft). */
    private final float[][] fftTwiddles;

    /** Per shift, interleaved exp(2*pi*i*(k + 1/8)/(2n)) for k in [0, n/2). */
    private final float[][] rotation;

    /** The FFT works in place here; sized for the largest block. */
    private final float[] scratch;

    /** Holds the rising edge until it can be added onto the previous block's tail. */
    private final float[] edge;

    /**
     * Builds every table a decoder with this longest block will ever need.
     *
     * @param maxSize the largest number of frequency coefficients one call will
     *                be given, which is the frame size in samples: 960 for the
     *                48 kHz Opus mode, and {@code shortMdctSize << maxLM} for a
     *                custom one
     */
    public Mdct(int maxSize) {
        if (maxSize < 4 || (maxSize & 1) != 0) {
            throw new IllegalArgumentException(
                    "maxSize must be an even number of at least 4, not " + maxSize);
        }
        if (factor(maxSize >> 1) == null) {
            throw new IllegalArgumentException("maxSize " + maxSize + " needs a "
                    + (maxSize >> 1) + "-point FFT, and this transform only factors 2, 3, 4 and 5");
        }

        int shifts = 0;
        while (true) {
            int size = maxSize >> (shifts + 1);
            if (size < 4 || (size & 1) != 0 || factor(size >> 1) == null) {
                break;
            }
            shifts++;
        }

        this.maxSize = maxSize;
        this.maxShift = shifts;
        this.factors = new int[shifts + 1][];
        this.stageStride = new int[shifts + 1][];
        this.bitrev = new int[shifts + 1][];
        this.fftTwiddles = new float[shifts + 1][];
        this.rotation = new float[shifts + 1][];
        this.scratch = new float[maxSize];
        this.edge = new float[maxSize];

        for (int shift = 0; shift <= shifts; shift++) {
            int n = maxSize >> shift;
            int nfft = n >> 1;

            int[] stages = factor(nfft);
            factors[shift] = stages;

            int stageCount = stages.length / 2;
            int[] strides = new int[stageCount];
            int stride = 1;
            for (int i = 0; i < stageCount; i++) {
                strides[i] = stride;
                stride *= stages[2 * i];
            }
            stageStride[shift] = strides;

            int[] scatter = new int[nfft];
            computeBitrev(0, scatter, 0, 1, stages, 0);
            bitrev[shift] = scatter;

            float[] twiddles = new float[2 * nfft];
            for (int j = 0; j < nfft; j++) {
                double phase = -2.0 * Math.PI * j / nfft;
                twiddles[2 * j] = (float) StrictMath.cos(phase);
                twiddles[2 * j + 1] = (float) StrictMath.sin(phase);
            }
            fftTwiddles[shift] = twiddles;

            float[] turns = new float[2 * nfft];
            for (int k = 0; k < nfft; k++) {
                double phase = 2.0 * Math.PI * (k + 0.125) / (2.0 * n);
                turns[2 * k] = (float) StrictMath.cos(phase);
                turns[2 * k + 1] = (float) StrictMath.sin(phase);
            }
            rotation[shift] = turns;
        }
    }

    /** The longest block this was built for, in coefficients. */
    public int maxSize() {
        return maxSize;
    }

    /** The largest {@code shift} this accepts; block size {@code maxSize >> shift}. */
    public int maxShift() {
        return maxShift;
    }

    /**
     * The inverse MDCT on its own, unwindowed.
     *
     * <p>{@code n} frequency coefficients in, {@code 2 * n} time samples out,
     * which is the transform RFC 6716 section 4.3.7 names, evaluated with the
     * normative code's scaling rather than the section's prose:
     *
     * <pre>
     *   out[m] = sum(k = 0 .. n-1) spectrum[k] * cos(pi/n * (m + 1/2 + n/2) * (k + 1/2))
     * </pre>
     *
     * <p>No 1/2 -- see the class javadoc for the measurement that settles it.
     *
     * <p>The reference does not expose this shape -- {@code clt_mdct_backward}
     * folds the window and the overlap-add into the same pass, which is what
     * {@link #inverseWindowed} does and what a decoder should call. This form
     * exists because the folded one cannot be checked against the definition
     * without it, and because the (n - overlap)/2 samples at each end that the
     * low-overlap window zeroes are exactly the ones a bug hides in.
     *
     * @param spectrum       the coefficients
     * @param spectrumOffset index of the first coefficient
     * @param out            receives {@code 2 * n} samples, overwritten not added
     * @param outOffset      index of the first output sample
     * @param n              coefficient count, equal to {@code maxSize >> shift}
     * @param stride         gap between consecutive coefficients, 1 for a long
     *                       block and the short-block count for a transient one
     * @param shift          which block size, 0 for the longest
     */
    public void inverse(float[] spectrum, int spectrumOffset, float[] out, int outOffset,
            int n, int stride, int shift) {
        checkSizes(n, shift, stride);
        checkSpectrum(spectrum, spectrumOffset, n, stride);
        checkOut(out, outOffset, 2L * n, "2 * n");
        transform(spectrum, spectrumOffset, n, stride, shift);
        expand(n, out, outOffset, 0, 2 * n);
    }

    /**
     * The inverse MDCT as a decoder uses it: windowed, and overlap-added onto
     * whatever the previous block left behind.
     *
     * <p>Writes {@code n + overlap} samples. The first {@code overlap} of them
     * are <em>added</em> to what is already in {@code out}, because that is the
     * previous block's falling edge and the sum of the two is what cancels the
     * time-domain aliasing; the remaining {@code n} are assigned, and the last
     * {@code overlap} of those are the falling edge the next call will add onto.
     * A caller that zeroes the buffer between blocks, or that assigns where this
     * adds, throws away half of every overlap region and leaves the uncancelled
     * aliasing described above on every boundary.
     *
     * <p>Consecutive blocks of a transient frame are therefore placed
     * {@code n} apart, and the first block of a frame lands on the tail the
     * last block of the previous frame left, which is why the decoder's output
     * buffer carries {@code overlap} samples of history.
     *
     * @param spectrum       the coefficients
     * @param spectrumOffset index of the first coefficient
     * @param out            receives {@code n + overlap} samples
     * @param outOffset      index of the first output sample
     * @param n              coefficient count, equal to {@code maxSize >> shift}
     * @param stride         gap between consecutive coefficients
     * @param shift          which block size, 0 for the longest
     * @param window         the rising half from {@link #window(int)}
     * @param overlap        its length, even and no greater than {@code n}
     */
    public void inverseWindowed(float[] spectrum, int spectrumOffset, float[] out, int outOffset,
            int n, int stride, int shift, float[] window, int overlap) {
        checkSizes(n, shift, stride);
        checkSpectrum(spectrum, spectrumOffset, n, stride);
        if (overlap < 2 || (overlap & 1) != 0 || overlap > n) {
            throw new IllegalArgumentException("overlap must be even and in [2, " + n
                    + "], not " + overlap);
        }
        if (window == null || window.length < overlap) {
            throw new IllegalArgumentException("window holds "
                    + (window == null ? "null" : String.valueOf(window.length))
                    + " values and " + overlap + " are needed");
        }
        checkOut(out, outOffset, (long) n + overlap, "n + overlap");

        transform(spectrum, spectrumOffset, n, stride, shift);

        // The window's rising edge starts (n - overlap)/2 samples into the
        // transform's own output, because zero-padding the 240-sample Vorbis
        // window out to the block length is what makes it low-overlap.
        int lead = (n - overlap) >> 1;
        expand(n, edge, 0, lead, overlap);
        expand(n, out, outOffset + overlap, lead + overlap, n);

        for (int i = 0; i < overlap; i++) {
            out[outOffset + i] += window[i] * edge[i];
            out[outOffset + n + i] *= window[overlap - 1 - i];
        }
    }

    /**
     * The forward MDCT as an encoder uses it: windowed, folded and transformed.
     *
     * <p>A port of {@code clt_mdct_forward} in {@code celt/mdct.c}, driven by
     * {@code compute_mdcts} in {@code celt/celt.c}. Reads {@code n + overlap}
     * time samples and writes {@code n} coefficients, which is the exact
     * transpose of what {@link #inverseWindowed} consumes and produces, so a
     * frame analysed here and synthesised there comes back at unity gain.
     *
     * <p>Two differences from the reference are deliberate. Upstream trashes its
     * input array, folding in place and then using it as the FFT's output
     * buffer; this reads the input and never writes it, because the encoder
     * takes two passes over the same samples -- the transient detector runs on
     * the buffer this transforms -- and a transform that ate its input would
     * force a copy at every call. And the rotation is the exact one this class
     * stores rather than upstream's table lookup plus shear, for the reason and
     * with the bound the class javadoc gives; the same one part in 100000 that
     * applies to the inverse applies here.
     *
     * <p>The {@code 4/N} that {@code opus_fft} folds in is applied here, so the
     * coefficients come out already divided by {@code n/2}. Leaving it out is
     * not a gain error a listener would hear as loudness -- the energy envelope
     * is measured from these same coefficients and would absorb it -- but it
     * would put the normalised shapes at a different scale from the pulse
     * vectors that approximate them, and the pulse search would then be
     * comparing a target against candidates of the wrong size.
     *
     * @param in        the time samples, {@code n + overlap} of them
     * @param inOffset  index of the first one
     * @param out       receives {@code n} coefficients, {@code stride} apart
     * @param outOffset index of the first one
     * @param n         coefficient count, equal to {@code maxSize >> shift}
     * @param stride    gap between consecutive coefficients, 1 for a long block
     *                  and the short-block count for a transient one
     * @param shift     which block size, 0 for the longest
     * @param window    the rising half from {@link #window(int)}
     * @param overlap   its length, even and no greater than {@code n}
     */
    public void forward(float[] in, int inOffset, float[] out, int outOffset,
            int n, int stride, int shift, float[] window, int overlap) {
        checkSizes(n, shift, stride);
        if (overlap < 2 || (overlap & 1) != 0 || overlap > n) {
            throw new IllegalArgumentException("overlap must be even and in [2, " + n
                    + "], not " + overlap);
        }
        if (window == null || window.length < overlap) {
            throw new IllegalArgumentException("window holds "
                    + (window == null ? "null" : String.valueOf(window.length))
                    + " values and " + overlap + " are needed");
        }
        if (in == null || inOffset < 0 || in.length - inOffset < (long) n + overlap) {
            throw new IllegalArgumentException("the forward transform reads " + (n + overlap)
                    + " samples from " + inOffset + " of a "
                    + (in == null ? "null" : String.valueOf(in.length)) + " element array");
        }
        checkSpectrum(out, outOffset, n, stride);

        float[] f = scratch;
        float[] turns = rotation[shift];
        int[] scatter = bitrev[shift];
        int quarter = n >> 1;
        int half = overlap >> 1;
        // The 4/N of opus_fft, with N the 2n-sample transform: 1/(n/2).
        float scale = 1.0f / quarter;

        // Window, shuffle and fold. The block is read as four quarters
        // [a, b, c, d] and folded to [-d-cR, a-bR] with the window on the two
        // ends only, which is what makes an n-coefficient transform need
        // n + overlap samples rather than 2n.
        int i = 0;
        int lead = overlap >> 2;
        for (; i < lead; i++) {
            int xp1 = inOffset + half + 2 * i;
            int xp2 = inOffset + n - 1 + half - 2 * i;
            float w1 = window[half + 2 * i];
            float w2 = window[half - 1 - 2 * i];
            fold(f, scatter, turns, i, scale,
                    w2 * in[xp1 + n] + w1 * in[xp2],
                    w1 * in[xp1] - w2 * in[xp2 - n]);
        }
        for (; i < quarter - lead; i++) {
            int xp1 = inOffset + half + 2 * i;
            int xp2 = inOffset + n - 1 + half - 2 * i;
            fold(f, scatter, turns, i, scale, in[xp2], in[xp1]);
        }
        for (; i < quarter; i++) {
            int j = i - (quarter - lead);
            int xp1 = inOffset + half + 2 * i;
            int xp2 = inOffset + n - 1 + half - 2 * i;
            float w1 = window[2 * j];
            float w2 = window[overlap - 1 - 2 * j];
            fold(f, scatter, turns, i, scale,
                    -w1 * in[xp1 - n] + w2 * in[xp2],
                    w2 * in[xp1] + w1 * in[xp2 + n]);
        }

        // The forward FFT, run as the conjugate of the inverse. Negating an
        // IEEE-754 float is exact and every butterfly is sums and products of
        // real parts, so conj(ifft(conj(x))) is bit-identical to the forward
        // transform written out with its own twiddles -- one set of butterflies
        // for both directions instead of two that can drift apart.
        ifft(f, shift);

        // Post-rotation. What the FFT left is already the conjugate the
        // reference computes here, so this multiplies rather than divides the
        // work the fold set up.
        for (int k = 0; k < quarter; k++) {
            float gr = f[2 * k];
            float gi = f[2 * k + 1];
            float c = turns[2 * k];
            float s = turns[2 * k + 1];
            out[outOffset + 2 * k * stride] = gr * c - gi * s;
            out[outOffset + (n - 1 - 2 * k) * stride] = gr * s + gi * c;
        }
    }

    /**
     * Pre-rotates one folded pair and writes it straight to the place the FFT's
     * digit reversal wants it, which saves a whole pass over the block.
     */
    private static void fold(float[] f, int[] scatter, float[] turns, int i, float scale,
            float re, float im) {
        float c = turns[2 * i];
        float s = turns[2 * i + 1];
        int at = 2 * scatter[i];
        f[at] = -(re * c + im * s) * scale;
        f[at + 1] = (im * c - re * s) * scale;
    }

    /**
     * The overlap window, rising half only.
     *
     * <p>A port of {@code modes.c:374}. The falling half is this reversed,
     * which is why only one is stored and why {@link #inverseWindowed} indexes
     * it backwards on the way out.
     *
     * <p>It satisfies {@code w[i]^2 + w[overlap-1-i]^2 == 1}, the power
     * complementarity condition of [PRINCEN86] that RFC 6716 section 4.3.7
     * names. That identity is the whole reason overlap-add reconstructs
     * anything: two adjacent blocks each carry an aliased copy of the samples
     * they share, and the aliases cancel only when the squares sum to one.
     *
     * @param overlap the window length, even and at least 2; 120 for every Opus
     *                mode at 48 kHz, whatever the frame size
     */
    public static float[] window(int overlap) {
        if (overlap < 2 || (overlap & 1) != 0) {
            throw new IllegalArgumentException("overlap must be even and at least 2, not " + overlap);
        }
        float[] w = new float[overlap];
        for (int i = 0; i < overlap; i++) {
            double inner = StrictMath.sin(0.5 * Math.PI * (i + 0.5) / overlap);
            w[i] = (float) StrictMath.sin(0.5 * Math.PI * inner * inner);
        }
        return w;
    }

    /**
     * Pre-rotation, FFT and post-rotation, leaving n interleaved reals in
     * {@link #scratch} that {@link #expand} unfolds into time samples.
     */
    private void transform(float[] spectrum, int spectrumOffset, int n, int stride, int shift) {
        float[] turns = rotation[shift];
        int[] scatter = bitrev[shift];
        float[] s = scratch;
        int quarter = n >> 1;

        // The pre-rotation reads the coefficients from both ends at once and
        // writes each result straight to the place the FFT's digit reversal
        // wants it, which saves a whole pass over the block compared with
        // opus_ifft's separate scatter.
        int low = spectrumOffset;
        int high = spectrumOffset + (n - 1) * stride;
        int step = 2 * stride;
        for (int i = 0; i < quarter; i++) {
            float a = spectrum[high];
            float b = spectrum[low];
            float cr = turns[2 * i];
            float ci = turns[2 * i + 1];
            int at = 2 * scatter[i];
            s[at] = b * ci - a * cr;
            s[at + 1] = -(a * ci + b * cr);
            low += step;
            high -= step;
        }

        ifft(s, shift);

        for (int k = 0; k < quarter; k++) {
            float re = s[2 * k];
            float im = s[2 * k + 1];
            float cr = turns[2 * k];
            float ci = turns[2 * k + 1];
            s[2 * k] = re * cr - im * ci;
            s[2 * k + 1] = im * cr + re * ci;
        }
    }

    /**
     * Unfolds {@code count} time samples starting at {@code from} out of the
     * n values the transform left in scratch.
     *
     * <p>An n-coefficient block has 2n time samples but only n of them are
     * independent: the first quarter is the second quarter reversed and negated,
     * and the fourth is the third reversed. That is the time-domain aliasing the
     * window exists to cancel, and it is why the transform is half the length
     * of its own output.
     */
    private void expand(int n, float[] dst, int dstOffset, int from, int count) {
        float[] s = scratch;
        int quarter = n >> 1;
        int at = dstOffset;
        int m = from;
        int end = from + count;

        int limit = Math.min(end, quarter);
        for (; m < limit; m++) {
            int q = quarter - 1 - m;
            dst[at++] = (q & 1) == 0 ? s[q] : -s[n - q];
        }
        limit = Math.min(end, 3 * quarter);
        for (; m < limit; m++) {
            int q = m - quarter;
            dst[at++] = (q & 1) == 0 ? -s[q] : s[n - q];
        }
        for (; m < end; m++) {
            int q = 5 * quarter - 1 - m;
            dst[at++] = (q & 1) == 0 ? -s[q] : s[n - q];
        }
    }

    /** A port of {@code opus_ifft}: no scaling, and conjugated twiddles. */
    private void ifft(float[] buf, int shift) {
        int[] stages = factors[shift];
        int[] strides = stageStride[shift];
        float[] tw = fftTwiddles[shift];
        int stageCount = strides.length;

        int m = stages[2 * stageCount - 1];
        for (int i = stageCount - 1; i >= 0; i--) {
            int m2 = i != 0 ? stages[2 * i - 1] : 1;
            int stride = strides[i];
            switch (stages[2 * i]) {
                case 2 -> butterfly2(buf, stride, tw, m, stride, m2);
                case 3 -> butterfly3(buf, stride, tw, m, stride, m2);
                case 4 -> butterfly4(buf, stride, tw, m, stride, m2);
                case 5 -> butterfly5(buf, stride, tw, m, stride, m2);
                default -> throw new IllegalStateException(
                        "radix " + stages[2 * i] + " has no butterfly");
            }
            m = m2;
        }
    }

    /** A port of {@code ki_bfly2}. */
    private static void butterfly2(float[] f, int fstride, float[] tw, int m, int n, int mm) {
        int span = 2 * m;
        for (int i = 0; i < n; i++) {
            int p0 = 2 * i * mm;
            int p1 = p0 + span;
            int t = 0;
            for (int j = 0; j < m; j++) {
                float ar = f[p1];
                float ai = f[p1 + 1];
                float wr = tw[2 * t];
                float wi = tw[2 * t + 1];
                float tr = ar * wr + ai * wi;
                float ti = ai * wr - ar * wi;
                t += fstride;
                float f0r = f[p0];
                float f0i = f[p0 + 1];
                f[p1] = f0r - tr;
                f[p1 + 1] = f0i - ti;
                f[p0] = f0r + tr;
                f[p0 + 1] = f0i + ti;
                p0 += 2;
                p1 += 2;
            }
        }
    }

    /** A port of {@code ki_bfly3}. */
    private static void butterfly3(float[] f, int fstride, float[] tw, int m, int n, int mm) {
        int o1 = 2 * m;
        int o2 = 4 * m;
        float epi3i = tw[2 * (fstride * m) + 1];
        for (int i = 0; i < n; i++) {
            int p = 2 * i * mm;
            int t1 = 0;
            int t2 = 0;
            for (int k = 0; k < m; k++) {
                float a1r = f[p + o1];
                float a1i = f[p + o1 + 1];
                float a2r = f[p + o2];
                float a2i = f[p + o2 + 1];
                float w1r = tw[2 * t1];
                float w1i = tw[2 * t1 + 1];
                float w2r = tw[2 * t2];
                float w2i = tw[2 * t2 + 1];
                float s1r = a1r * w1r + a1i * w1i;
                float s1i = a1i * w1r - a1r * w1i;
                float s2r = a2r * w2r + a2i * w2i;
                float s2i = a2i * w2r - a2r * w2i;
                float s3r = s1r + s2r;
                float s3i = s1i + s2i;
                float s0r = s1r - s2r;
                float s0i = s1i - s2i;
                t1 += fstride;
                t2 += 2 * fstride;
                float f0r = f[p];
                float f0i = f[p + 1];
                float midr = f0r - 0.5f * s3r;
                float midi = f0i - 0.5f * s3i;
                s0r *= -epi3i;
                s0i *= -epi3i;
                f[p] = f0r + s3r;
                f[p + 1] = f0i + s3i;
                f[p + o2] = midr + s0i;
                f[p + o2 + 1] = midi - s0r;
                f[p + o1] = midr - s0i;
                f[p + o1 + 1] = midi + s0r;
                p += 2;
            }
        }
    }

    /** A port of {@code ki_bfly4}. */
    private static void butterfly4(float[] f, int fstride, float[] tw, int m, int n, int mm) {
        int o1 = 2 * m;
        int o2 = 4 * m;
        int o3 = 6 * m;
        for (int i = 0; i < n; i++) {
            int p = 2 * i * mm;
            int t1 = 0;
            int t2 = 0;
            int t3 = 0;
            for (int j = 0; j < m; j++) {
                float a0r = f[p + o1];
                float a0i = f[p + o1 + 1];
                float a1r = f[p + o2];
                float a1i = f[p + o2 + 1];
                float a2r = f[p + o3];
                float a2i = f[p + o3 + 1];
                float w1r = tw[2 * t1];
                float w1i = tw[2 * t1 + 1];
                float w2r = tw[2 * t2];
                float w2i = tw[2 * t2 + 1];
                float w3r = tw[2 * t3];
                float w3i = tw[2 * t3 + 1];
                float s0r = a0r * w1r + a0i * w1i;
                float s0i = a0i * w1r - a0r * w1i;
                float s1r = a1r * w2r + a1i * w2i;
                float s1i = a1i * w2r - a1r * w2i;
                float s2r = a2r * w3r + a2i * w3i;
                float s2i = a2i * w3r - a2r * w3i;
                float f0r = f[p];
                float f0i = f[p + 1];
                float s5r = f0r - s1r;
                float s5i = f0i - s1i;
                f0r += s1r;
                f0i += s1i;
                float s3r = s0r + s2r;
                float s3i = s0i + s2i;
                float s4r = s0r - s2r;
                float s4i = s0i - s2i;
                t1 += fstride;
                t2 += 2 * fstride;
                t3 += 3 * fstride;
                f[p + o2] = f0r - s3r;
                f[p + o2 + 1] = f0i - s3i;
                f[p] = f0r + s3r;
                f[p + 1] = f0i + s3i;
                f[p + o1] = s5r - s4i;
                f[p + o1 + 1] = s5i + s4r;
                f[p + o3] = s5r + s4i;
                f[p + o3 + 1] = s5i - s4r;
                p += 2;
            }
        }
    }

    /** A port of {@code ki_bfly5}. */
    private static void butterfly5(float[] f, int fstride, float[] tw, int m, int n, int mm) {
        int o1 = 2 * m;
        int o2 = 4 * m;
        int o3 = 6 * m;
        int o4 = 8 * m;
        float yar = tw[2 * (fstride * m)];
        float yai = tw[2 * (fstride * m) + 1];
        float ybr = tw[2 * (2 * fstride * m)];
        float ybi = tw[2 * (2 * fstride * m) + 1];
        for (int i = 0; i < n; i++) {
            int p = 2 * i * mm;
            for (int u = 0; u < m; u++) {
                int t = u * fstride;
                float s0r = f[p];
                float s0i = f[p + 1];
                float s1r = mulcRe(f, p + o1, tw, t);
                float s1i = mulcIm(f, p + o1, tw, t);
                float s2r = mulcRe(f, p + o2, tw, 2 * t);
                float s2i = mulcIm(f, p + o2, tw, 2 * t);
                float s3r = mulcRe(f, p + o3, tw, 3 * t);
                float s3i = mulcIm(f, p + o3, tw, 3 * t);
                float s4r = mulcRe(f, p + o4, tw, 4 * t);
                float s4i = mulcIm(f, p + o4, tw, 4 * t);

                float s7r = s1r + s4r;
                float s7i = s1i + s4i;
                float s10r = s1r - s4r;
                float s10i = s1i - s4i;
                float s8r = s2r + s3r;
                float s8i = s2i + s3i;
                float s9r = s2r - s3r;
                float s9i = s2i - s3i;

                f[p] = s0r + s7r + s8r;
                f[p + 1] = s0i + s7i + s8i;

                float s5r = s0r + s7r * yar + s8r * ybr;
                float s5i = s0i + s7i * yar + s8i * ybr;
                float s6r = -(s10i * yai) - s9i * ybi;
                float s6i = s10r * yai + s9r * ybi;
                f[p + o1] = s5r - s6r;
                f[p + o1 + 1] = s5i - s6i;
                f[p + o4] = s5r + s6r;
                f[p + o4 + 1] = s5i + s6i;

                float s11r = s0r + s7r * ybr + s8r * yar;
                float s11i = s0i + s7i * ybr + s8i * yar;
                float s12r = s10i * ybi - s9i * yai;
                float s12i = -(s10r * ybi) + s9r * yai;
                f[p + o2] = s11r + s12r;
                f[p + o2 + 1] = s11i + s12i;
                f[p + o3] = s11r - s12r;
                f[p + o3 + 1] = s11i - s12i;
                p += 2;
            }
        }
    }

    private static float mulcRe(float[] f, int at, float[] tw, int t) {
        return f[at] * tw[2 * t] + f[at + 1] * tw[2 * t + 1];
    }

    private static float mulcIm(float[] f, int at, float[] tw, int t) {
        return f[at + 1] * tw[2 * t] - f[at] * tw[2 * t + 1];
    }

    private void checkSizes(int n, int shift, int stride) {
        if (shift < 0 || shift > maxShift) {
            throw new IllegalArgumentException("shift must be in [0, " + maxShift
                    + "] for a transform built at maxSize " + maxSize + ", not " + shift);
        }
        if (n != (maxSize >> shift)) {
            throw new IllegalArgumentException("n must be maxSize >> shift, which is "
                    + (maxSize >> shift) + " here, not " + n);
        }
        if (stride < 1) {
            throw new IllegalArgumentException("stride must be at least 1, not " + stride);
        }
    }

    private static void checkSpectrum(float[] spectrum, int offset, int n, int stride) {
        if (spectrum == null) {
            throw new IllegalArgumentException("spectrum is null");
        }
        long last = (long) offset + (long) (n - 1) * stride;
        if (offset < 0 || last >= spectrum.length) {
            throw new IllegalArgumentException("reading " + n + " coefficients from " + offset
                    + " every " + stride + " reaches index " + last + " of a "
                    + spectrum.length + " element array");
        }
    }

    private static void checkOut(float[] out, int offset, long count, String what) {
        if (out == null) {
            throw new IllegalArgumentException("out is null");
        }
        long last = offset + count;
        if (offset < 0 || last > out.length) {
            throw new IllegalArgumentException("writing " + count + " samples (" + what
                    + ") from " + offset + " needs " + last + " elements and out holds "
                    + out.length);
        }
    }

    /**
     * A port of {@code kf_factor}, returning null rather than failing when a
     * prime above five appears.
     */
    private static int[] factor(int n) {
        int[] buf = new int[2 * MAX_FACTORS];
        int at = 0;
        int p = 4;
        int remaining = n;
        do {
            while (remaining % p != 0) {
                p = switch (p) {
                    case 4 -> 2;
                    case 2 -> 3;
                    default -> p + 2;
                };
                if (p > 32000 || (long) p * p > remaining) {
                    p = remaining;
                }
            }
            remaining /= p;
            if (p > 5) {
                return null;
            }
            if (at + 2 > buf.length) {
                return null;
            }
            buf[at++] = p;
            buf[at++] = remaining;
        } while (remaining > 1);
        return Arrays.copyOf(buf, at);
    }

    /** A port of {@code compute_bitrev_table}; {@code in_stride} is always 1 here. */
    private static void computeBitrev(int fout, int[] table, int f, int fstride,
            int[] stages, int at) {
        int p = stages[at];
        int m = stages[at + 1];
        if (m == 1) {
            for (int j = 0; j < p; j++) {
                table[f] = fout + j;
                f += fstride;
            }
        } else {
            for (int j = 0; j < p; j++) {
                computeBitrev(fout, table, f, fstride * p, stages, at + 2);
                f += fstride;
                fout += m;
            }
        }
    }
}
