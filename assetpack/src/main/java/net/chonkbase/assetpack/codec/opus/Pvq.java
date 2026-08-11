package net.chonkbase.assetpack.codec.opus;

import java.util.Arrays;

/**
 * One CELT band's shape: N coefficients carrying exactly K pulses between them,
 * written down as a single integer.
 *
 * <p>A port of {@code cwrsi}, {@code icwrs}, {@code decode_pulses} and the
 * {@code V(N,K)} tables in {@code celt/cwrs.c}, together with
 * {@code normalise_residual} in {@code celt/vq.c}, following the five numbered
 * steps of RFC 6716 section 4.3.4.2 rather than libopus's table-driven
 * rewriting of them, because the steps are the normative text.
 *
 * <p>CELT sends a band as a gain and a shape. The shape is a vector of N
 * integers whose absolute values sum to exactly K, and the whole vector travels
 * as one uniformly distributed index between 0 and V(N,K)-1, where V(N,K)
 * counts the vectors. There is no slack in that index: every value of it names
 * a different vector, and neighbouring values name vectors that are not
 * neighbours. A decoder that computes V(N,K) one off, or that lets an index
 * wrap, does not produce a slightly wrong band; it produces an unrelated one,
 * and because V(N,K) is also the range decoder's frequency total it leaves the
 * entropy layer in a different state, so every band after it in the frame is
 * wrong as well. One arithmetic slip here is heard as a burst of noise across
 * the whole spectrum, not as a dulled band. Everything below is therefore
 * integer arithmetic with the ranges worked out rather than assumed.
 *
 * <p><b>Where the numbers stop fitting.</b> V(N,K) grows about like
 * (2N)^K/K!, and CELT's widest band is 176 bins (RFC 6716 Table 55, band 20 at
 * 20 ms). V(176,4) is 639,716,352 and fits a signed int. V(176,5) is
 * 45,040,392,672 and does not: it needs 36 bits. V(176,10) is
 * 8,063,144,027,840,174,592, the last entry of that row to fit a signed long,
 * and V(176,11) needs 68 bits. Nothing here silently wraps at either boundary
 * -- the table below stops at the last entry that fits and {@link #vectorCount}
 * reports anything past it.
 *
 * <p>CELT itself never gets near the long boundary, because of split decoding
 * (RFC 6716 section 4.3.4.4): a codebook index may be at most 32 bits, and a
 * band whose allocation would need a wider one is halved into two sub-vectors
 * of N/2 with a coded gain between them, up to LM+1 times. So the 176-bin band
 * is handed to this code either with K at most 4, or split down through 88, 44,
 * 22 and 11. {@link #maxPulsesWithoutSplitting} is that rule as a number.
 *
 * <p>A consequence worth naming, because it is what makes the reference
 * implementation's compact tables legal: V(13,13) is 830,764,794 and V(14,14)
 * is 4,666,890,936, which is over 2^32. V(N,K) rises with both arguments, so
 * any codebook that fits 32 bits has min(N,K) at most 13. That is why
 * {@code CELT_PVQ_U_ROW} in {@code celt/cwrs.c} only needs fifteen rows.
 *
 * <p><b>Arithmetic.</b> Everything that turns an index into pulses is
 * {@code long} integer arithmetic and is exact, as RFC 6716 requires of the
 * entropy layer. Only {@link #normalise} is floating point, and it is
 * {@code float}, matching libopus's float build: the sum of squares is
 * accumulated exactly in integers, and the scale is
 * {@code (1f / (float) sqrt(energy)) * gain}, which is what
 * {@code celt_rsqrt_norm} times {@code gain} comes to when
 * {@code celt_sqrt(x)} is {@code (float) sqrt(x)}. {@code Math.sqrt} is
 * correctly rounded by the Java specification, so that scale is the same value
 * on every JVM and two machines decoding the same packet get the same samples.
 */
public final class Pvq {

    /**
     * The most coefficients one band can have.
     *
     * <p>RFC 6716 Table 55: band 20 is 22 bins at 2.5 ms, and a 20 ms frame
     * has eight times that.
     */
    public static final int MAX_DIMENSIONS = 176;

    /**
     * The most pulses the allocator can put in one band.
     *
     * <p>{@code CELT_MAX_PULSES} in {@code celt/rate.h}. RFC 6716 section
     * 4.3.4.1 says the encoder searches "a precomputed allocation table that
     * only permits some K values for each N"; that table is
     * {@code get_pulses(i)} for i up to {@code MAX_PSEUDO}, and its last entry
     * is 128. K is produced by the allocator rather than read from the stream,
     * so a larger one is a bug in this decoder and not a malformed packet,
     * which is why it is reported rather than clamped: a clamped K decodes the
     * band to a different vector and desynchronises the range decoder.
     */
    public static final int MAX_PULSES = 128;

    /**
     * How wide a single codebook index may be, from RFC 6716 section 4.3.4.4.
     *
     * <p>This is the reason split decoding exists. The range decoder's uniform
     * symbol carries a 32-bit frequency total and no more, so a band needing a
     * wider codebook has to be halved instead.
     */
    public static final int MAX_CODEBOOK_BITS = 32;

    /** The exclusive ceiling {@link #MAX_CODEBOOK_BITS} puts on V(N,K). */
    private static final long CODEBOOK_LIMIT = 1L << MAX_CODEBOOK_BITS;

    /**
     * V(N,K), one row per N, each row truncated at the last K that fits a
     * signed long.
     *
     * <p>4,382 entries, about 35 kB, built once at class load from the
     * recurrence in RFC 6716 section 4.3.4.2 and never written again. The
     * decode path is three array reads per coefficient and allocates nothing,
     * which is what lets it run on the audio thread.
     */
    private static final long[][] COUNTS = buildCounts();

    private Pvq() {
    }

    /**
     * V(N,K), the number of vectors of N integers whose absolute values sum to
     * K.
     *
     * <p>The recurrence is V(N,K) = V(N-1,K) + V(N,K-1) + V(N-1,K-1), with
     * V(N,0) = 1 and V(0,K) = 0 for K other than zero: split on whether the
     * first coefficient is zero, positive or negative.
     *
     * @throws IllegalArgumentException if N or K is outside the range CELT can
     *                                  reach, or if V(N,K) would not fit a
     *                                  signed 64-bit integer
     */
    public static long vectorCount(int n, int k) {
        if (n < 0 || n > MAX_DIMENSIONS) {
            throw new IllegalArgumentException("PVQ dimension " + n + " is outside 0.."
                    + MAX_DIMENSIONS + ", the widest band CELT codes");
        }
        if (k < 0 || k > MAX_PULSES) {
            throw new IllegalArgumentException("PVQ pulse count " + k + " is outside 0.."
                    + MAX_PULSES + ", the most the CELT allocator can ask for");
        }
        long[] row = COUNTS[n];
        if (k >= row.length) {
            throw new IllegalArgumentException("V(" + n + "," + k + ") does not fit a signed"
                    + " 64-bit integer; CELT cannot ask for it because split decoding caps a"
                    + " codebook at " + MAX_CODEBOOK_BITS + " bits");
        }
        return row[k];
    }

    /**
     * How many bits the codebook index occupies: ceil(log2(V(N,K))).
     *
     * <p>This is {@code ftb} in RFC 6716 section 4.1.5: decoding the index with
     * {@code ec_dec_uint} at a frequency total of ft splits it into a range
     * coded symbol of up to 8 bits and {@code ilog(ft - 1) - 8} raw bits, so
     * the value spans exactly this many bits. K = 0 spans none, because there
     * is only one vector and it is all zeros.
     *
     * <p><b>This is not the number the allocator compares against a band's
     * budget.</b> RFC 6716 section 4.3.4.1 performs allocation in 1/8th bit
     * units and picks the K "that produces the number of bits nearest to the
     * allocated value", which needs log2(V(N,K)) to 1/8th of a bit --
     * {@code log2_frac} in {@code celt/rate.c} -- not this whole-bit ceiling.
     * Rounding to whole bits first would pick a different K for bands whose
     * budget falls between two entries, and a K that disagrees with the
     * encoder's decodes the band from the wrong codebook and desynchronises
     * every band after it. The fractional form belongs with the allocator, and
     * is deliberately not here.
     */
    public static int bitsForPulses(int n, int k) {
        long total = vectorCount(n, k);
        if (total < 1) {
            throw new IllegalArgumentException("V(" + n + "," + k + ") is empty:"
                    + " no vector of " + n + " coefficients can carry " + k + " pulses");
        }
        return 64 - Long.numberOfLeadingZeros(total - 1);
    }

    /**
     * Whether a band of this shape can be coded as one codebook index.
     *
     * <p>When this is false the band decoder must split, per RFC 6716 section
     * 4.3.4.4. Handing an oversized codebook to the range decoder instead
     * would truncate the frequency total to 32 bits, and every band from that
     * point in the frame onwards would decode from a range coder that no
     * longer agrees with the encoder's.
     *
     * <p>An empty codebook is not a codebook that fits. V(0,K) is zero for K
     * other than zero, and answering yes there would tell a band decoder that
     * a shape with no codewords at all needs no splitting; it would then call
     * {@code ec_dec_uint} with a frequency total of zero, which RFC 6716
     * section 4.1.5 does not define and which reads a symbol out of a
     * distribution that has none.
     */
    public static boolean fitsOneCodebook(int n, int k) {
        if (n < 0 || n > MAX_DIMENSIONS || k < 0 || k > MAX_PULSES) {
            return false;
        }
        long[] row = COUNTS[n];
        return k < row.length && row[k] >= 1 && row[k] < CODEBOOK_LIMIT;
    }

    /**
     * The largest K a band of N coefficients can carry without being split.
     *
     * <p>Four for the 176-bin band, five for 96 bins, nine for 22, and no
     * limit worth naming below that -- V(2,K) is only 4K, so a two-coefficient
     * band never needs splitting and is capped by the allocator instead.
     */
    public static int maxPulsesWithoutSplitting(int n) {
        if (n < 0 || n > MAX_DIMENSIONS) {
            throw new IllegalArgumentException("PVQ dimension " + n + " is outside 0.."
                    + MAX_DIMENSIONS + ", the widest band CELT codes");
        }
        long[] row = COUNTS[n];
        int best = 0;
        while (best + 1 < row.length && best + 1 <= MAX_PULSES
                && row[best + 1] >= 1 && row[best + 1] < CODEBOOK_LIMIT) {
            best++;
        }
        return best;
    }

    /**
     * Turns a codebook index into the pulse vector it names.
     *
     * <p>A port of {@code cwrsi} in {@code celt/cwrs.c}, written as the five
     * steps of RFC 6716 section 4.3.4.2. Writes N values into {@code out} and
     * allocates nothing.
     *
     * @param out   receives the pulses, N of them, signed
     * @param n     coefficients in the band
     * @param k     pulses to place, summed over absolute values
     * @param index the codeword, 0 to V(N,K)-1
     * @throws IllegalArgumentException if the index is at or past V(N,K), if
     *                                  {@code out} is too short, or if N or K
     *                                  is outside the range CELT can reach
     */
    public static void decodePulses(int[] out, int n, int k, long index) {
        long total = vectorCount(n, k);
        if (n < 1) {
            throw new IllegalArgumentException("a PVQ band needs at least one coefficient, got " + n);
        }
        if (out.length < n) {
            throw new IllegalArgumentException("the pulse buffer holds " + out.length
                    + " and the band has " + n + " coefficients");
        }
        if (index < 0 || index >= total) {
            throw new IllegalArgumentException("PVQ codeword index " + index
                    + " is outside 0.." + (total - 1) + " for V(" + n + "," + k + ")");
        }

        long i = index;
        int pulses = k;
        for (int j = 0; j < n; j++) {
            long[] shorter = COUNTS[n - j - 1];
            long[] here = COUNTS[n - j];
            long a = shorter[pulses];
            long b = here[pulses];

            // Step 1, and the shift has to be the unsigned one. V(175,10) +
            // V(176,10) is 15,679,861,705,100,127,042, past a signed long's
            // ceiling, so the sum comes back negative; it is still the right
            // 64 bits, because two non-negative longs cannot overflow 64
            // unsigned bits, and >>> reads them back. An arithmetic >> would
            // halve the negative number instead and decode the top of the
            // widest band from an index it never had. The sum is always even,
            // so nothing is lost either way: negating a vector pairs the
            // codewords off, which makes V(N,K) even for K >= 1, and V(N,0)
            // is one on both sides.
            long half = (a + b) >>> 1;

            // Step 2. The first half of the range is the codewords whose next
            // coefficient is zero or positive.
            int sign = 1;
            if (i >= half) {
                sign = -1;
                i -= half;
            }

            // Steps 3 and 4. p walks down from the start of the "at least one
            // pulse here" region; each step it passes is one more pulse in
            // this coefficient. It cannot walk past pulses = 0, because
            // half equals the sum of V(N-j-1,t) for t = 0..pulses, so p is
            // exactly zero there and i is never negative.
            int before = pulses;
            long p = half - a;
            while (p > i) {
                pulses--;
                p -= shorter[pulses];
            }

            // Step 5.
            out[j] = sign * (before - pulses);
            i -= p;
        }
    }

    /**
     * Turns a pulse vector back into its codebook index.
     *
     * <p>A port of {@code icwrs} in {@code celt/cwrs.c}. The decoder does
     * nothing but subtract as it walks the coefficients, so the index is the
     * sum of what it would subtract, which is what this accumulates.
     *
     * @throws IllegalArgumentException if the absolute values do not sum to
     *                                  exactly K, or if N or K is outside the
     *                                  range CELT can reach
     */
    public static long encodePulses(int[] pulses, int n, int k) {
        vectorCount(n, k);
        if (n < 1) {
            throw new IllegalArgumentException("a PVQ band needs at least one coefficient, got " + n);
        }
        if (pulses.length < n) {
            throw new IllegalArgumentException("the pulse buffer holds " + pulses.length
                    + " and the band has " + n + " coefficients");
        }

        long index = 0;
        int remaining = k;
        for (int j = 0; j < n; j++) {
            long[] shorter = COUNTS[n - j - 1];
            long[] here = COUNTS[n - j];
            long a = shorter[remaining];
            long half = (a + here[remaining]) >>> 1;

            int value = pulses[j];
            int magnitude = Math.abs(value);
            // The lower bound is not paranoia about a value that cannot
            // happen: Math.abs(Integer.MIN_VALUE) is Integer.MIN_VALUE, so a
            // buffer that was never written by decodePulses would slip past a
            // check that only looked at the upper bound and then run the
            // pulse count backwards into an overflow.
            if (magnitude < 0 || magnitude > remaining) {
                throw new IllegalArgumentException("coefficient " + j + " carries " + value
                        + " and only " + remaining + " of the " + k + " pulses are left");
            }
            long p = half;
            for (int t = 0; t <= magnitude; t++) {
                p -= shorter[remaining - t];
            }
            remaining -= magnitude;
            index += p;
            if (value < 0) {
                index += half;
            }
        }
        if (remaining != 0) {
            throw new IllegalArgumentException("the pulses sum to " + (k - remaining)
                    + " and the codebook is V(" + n + "," + k + ")");
        }
        return index;
    }

    /**
     * Scales a pulse vector to the unit sphere and applies the band's gain.
     *
     * <p>A port of {@code normalise_residual} in {@code celt/vq.c}, the last
     * sentence of RFC 6716 section 4.3.4.2: "The decoded vector X is then
     * normalized such that its L2-norm equals one."
     *
     * <p>The energy is summed as an integer and is therefore exact -- K is at
     * most 128, so the largest it can be is 16,384 and it is representable
     * without rounding either way. Only the reciprocal square root and the
     * scaling are floating point, and both are {@code float}, which is what
     * libopus's float build uses for band data.
     *
     * @throws IllegalArgumentException if every pulse is zero, which has no
     *                                  direction to normalise; dividing by
     *                                  that zero would fill the band with NaN
     *                                  and the NaN would survive the inverse
     *                                  MDCT into a whole frame of silence with
     *                                  a click at each end. Also if any
     *                                  coefficient is larger than
     *                                  {@link #MAX_PULSES}, which no PVQ
     *                                  vector is
     */
    public static void normalise(int[] pulses, float[] out, int n, float gain) {
        if (n < 1) {
            throw new IllegalArgumentException("a PVQ band needs at least one coefficient, got " + n);
        }
        if (pulses.length < n) {
            throw new IllegalArgumentException("the pulse buffer holds " + pulses.length
                    + " and the band has " + n + " coefficients");
        }
        if (out.length < n) {
            throw new IllegalArgumentException("the output buffer holds " + out.length
                    + " and the band has " + n + " coefficients");
        }
        long energy = 0;
        for (int i = 0; i < n; i++) {
            int value = pulses[i];
            // The bound is what makes the sum below exact rather than merely
            // wide. No PVQ coefficient exceeds K in absolute value and K is at
            // most MAX_PULSES, so the energy of a real band is at most
            // 176 * 128 * 128, well inside a long. A buffer that never came out
            // of decodePulses is a different matter, and the sum wraps in two
            // different ways depending on how far: 176 coefficients of
            // Integer.MAX_VALUE make it negative, sqrt gives NaN, and the whole
            // band comes back NaN, which survives the inverse MDCT and the
            // overlap-add into the next frame. Five of them make it positive and
            // wrong, which is worse, because the band is then scaled 2.2 times
            // too loud with no NaN and no exception to show for it and nothing
            // downstream can tell. Compared without Math.abs because
            // Math.abs(Integer.MIN_VALUE) is negative.
            if (value < -MAX_PULSES || value > MAX_PULSES) {
                throw new IllegalArgumentException("coefficient " + i + " carries " + value
                        + ", and no PVQ vector holds more than " + MAX_PULSES + " pulses");
            }
            energy += (long) value * value;
        }
        if (energy == 0) {
            throw new IllegalArgumentException(
                    "an all-zero pulse vector has no direction to normalise");
        }
        float scale = (1.0f / (float) Math.sqrt(energy)) * gain;
        for (int i = 0; i < n; i++) {
            out[i] = scale * pulses[i];
        }
    }

    /**
     * Builds V(N,K) row by row from the recurrence.
     *
     * <p>Rows are cut at the last K that fits a signed long rather than
     * saturated, so that a lookup past the end is a reported fault instead of
     * a plausible-looking number. Two full-width scratch rows are reused and
     * the copy out is what gets kept, which is why the table is 35 kB rather
     * than the 182 kB a rectangular one would be: the row for N = 176 is
     * eleven entries long, and only N up to 11 reaches K = 128.
     */
    private static long[][] buildCounts() {
        long[][] rows = new long[MAX_DIMENSIONS + 1][];
        long[] previous = new long[MAX_PULSES + 1];
        long[] current = new long[MAX_PULSES + 1];

        // V(0,0) is one, the empty vector; V(0,K) is zero for K > 0, because
        // no pulses fit in no coefficients. Getting this base case wrong is
        // the classic way to shift an entire band by one codeword.
        previous[0] = 1;
        rows[0] = previous.clone();

        int reach = MAX_PULSES;
        for (int n = 1; n <= MAX_DIMENSIONS; n++) {
            current[0] = 1;
            int limit = 0;
            for (int k = 1; k <= reach; k++) {
                long sum;
                try {
                    sum = Math.addExact(Math.addExact(previous[k], current[k - 1]), previous[k - 1]);
                } catch (ArithmeticException tooBig) {
                    break;
                }
                current[k] = sum;
                limit = k;
            }
            // V rises with N, so this row can never be longer than the last.
            reach = limit;
            rows[n] = Arrays.copyOf(current, limit + 1);
            long[] swap = previous;
            previous = current;
            current = swap;
        }
        return rows;
    }
}
