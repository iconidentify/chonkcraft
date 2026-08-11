package net.chonkbase.assetpack.codec.opus;

/**
 * The constant tables the CELT layer of Opus is defined against.
 *
 * <p>A port of {@code eband5ms}, {@code band_allocation} and {@code logN400} in
 * {@code celt/modes.c}, {@code cache_caps50} in {@code celt/static_modes_float.h},
 * {@code LOG2_FRAC_TABLE} in {@code celt/rate.c}, {@code tf_select_table},
 * {@code trim_icdf} and {@code spread_icdf} in {@code celt/celt.c}, and
 * {@code celt_log2} / {@code celt_exp2} in {@code celt/mathops.h}. These are
 * RFC 6716 Tables 55 and 57 through 63, sections 4.3 to 4.3.4.5.
 *
 * <p>Every number here was transcribed from the RFC and then compared against the
 * reference implementation carried in Appendix A of the same RFC, which is the
 * normative part of the specification. Both statements agree; where the RFC gives
 * a table only once, the accompanying test rederives it from an independent
 * property instead. This double-sourcing is the whole point of the class: a single
 * wrong digit in the allocation table does not crash anything, it just makes one
 * band in one frame size come out too quiet or too noisy, and nothing downstream
 * would ever report it.
 *
 * <p>Everything in this file is integer except the two polynomial approximations at
 * the bottom, which are {@code float} because the libopus float build computes band
 * energies in {@code float}; see {@link #log2Approx(float)}.
 */
final class CeltTables {

    private CeltTables() {
    }

    /**
     * Number of critical bands the normal (non-custom) CELT layer uses.
     *
     * <p>RFC 6716 section 4.3: "The normal CELT layer uses 21 of those bands".
     * Table 55 lists them as bands 0 through 20.
     */
    static final int BAND_COUNT = 21;

    /** Rows in the static allocation table, RFC 6716 Table 57. */
    static final int ALLOCATION_ROWS = 11;

    /**
     * Band boundaries in MDCT bins of the 2.5 ms (120 sample) frame.
     *
     * <p>RFC 6716 Table 55, the "2.5 ms" column read as a running total. Twenty-two
     * entries, one more than there are bands, because the last entry is the upper
     * edge of the top band.
     *
     * <p>The last entry is 100 and not 120: CELT codes nothing above 20 kHz, so the
     * top sixth of the 24 kHz spectrum the MDCT produces is simply never given a
     * band. A reader who assumes the boundaries reach the end of the transform will
     * write a decoder that walks 20 bins past the last band.
     */
    private static final int[] EBANDS_2_5MS = {
        0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16, 20, 24, 28, 34, 40, 48, 60, 78, 100
    };

    /**
     * Band edge frequencies in Hz, from the "Start Frequency" column of RFC 6716
     * Table 55 with the final "Stop Frequency" appended.
     *
     * <p>Held separately from the bin counts on purpose. Table 55 states the same
     * layout twice, once as bins and once as frequencies, and holding both lets the
     * test prove they agree rather than trusting one transcription.
     */
    private static final int[] BAND_EDGE_HZ = {
        0, 200, 400, 600, 800, 1000, 1200, 1400, 1600, 2000, 2400, 2800,
        3200, 4000, 4800, 5600, 6800, 8000, 9600, 12000, 15600, 20000
    };

    /**
     * MDCT bins per channel per band, indexed by LM then band.
     *
     * <p>RFC 6716 Table 55 transcribed column by column: LM 0 is the 2.5 ms column,
     * LM 3 the 20 ms column. Redundant with {@link #EBANDS_2_5MS} shifted left by
     * LM, and kept anyway so the test can check the shift against the numbers the
     * RFC actually prints.
     */
    private static final int[][] BINS_PER_BAND = {
        {1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 6, 6, 8, 12, 18, 22},
        {2, 2, 2, 2, 2, 2, 2, 2, 4, 4, 4, 4, 8, 8, 8, 12, 12, 16, 24, 36, 44},
        {4, 4, 4, 4, 4, 4, 4, 4, 8, 8, 8, 8, 16, 16, 16, 24, 24, 32, 48, 72, 88},
        {8, 8, 8, 8, 8, 8, 8, 8, 16, 16, 16, 16, 32, 32, 32, 48, 48, 64, 96, 144, 176}
    };

    /**
     * The static bit allocation table, in units of 1/32 bit per MDCT bin.
     *
     * <p>RFC 6716 Table 57, stored transposed from the way the RFC prints it:
     * {@code ALLOCATION[q][band]}, eleven quality rows of twenty-one bands. The RFC
     * prints bands down the page and quality across, which is the transpose of the
     * reference implementation's {@code band_allocation}; both were read and they
     * agree on all 231 numbers.
     *
     * <p>Row 0 is all zeros and row 10 is the ceiling; the decoder never uses row 0
     * as an interpolation endpoint (the reference bisects over rows 1 to 10) but the
     * row has to exist because the low endpoint of the interpolation can be row 0
     * when the frame is nearly empty.
     */
    private static final int[][] ALLOCATION = {
        {  0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0},
        { 90,  80,  75,  69,  63,  56,  49,  40,  34,  29,  20,  18,  10,   0,   0,   0,   0,   0,   0,   0,   0},
        {110, 100,  90,  84,  78,  71,  65,  58,  51,  45,  39,  32,  26,  20,  12,   0,   0,   0,   0,   0,   0},
        {118, 110, 103,  93,  86,  80,  75,  70,  65,  59,  53,  47,  40,  31,  23,  15,   4,   0,   0,   0,   0},
        {126, 119, 112, 104,  95,  89,  83,  78,  72,  66,  60,  54,  47,  39,  32,  25,  17,  12,   1,   0,   0},
        {134, 127, 120, 114, 103,  97,  91,  85,  78,  72,  66,  60,  54,  47,  41,  35,  29,  23,  16,  10,   1},
        {144, 137, 130, 124, 113, 107, 101,  95,  88,  82,  76,  70,  64,  57,  51,  45,  39,  33,  26,  15,   1},
        {152, 145, 138, 132, 123, 117, 111, 105,  98,  92,  86,  80,  74,  67,  61,  55,  49,  43,  36,  20,   1},
        {162, 155, 148, 142, 133, 127, 121, 115, 108, 102,  96,  90,  84,  77,  71,  65,  59,  53,  46,  30,   1},
        {172, 165, 158, 152, 143, 137, 131, 125, 118, 112, 106, 100,  94,  87,  81,  75,  69,  63,  56,  45,  20},
        {200, 200, 200, 200, 200, 200, 200, 200, 198, 193, 188, 183, 178, 173, 168, 163, 158, 153, 148, 129, 104}
    };

    /**
     * Per-band allocation ceilings, before scaling, indexed by
     * {@code 2*LM + (channels-1)} then band.
     *
     * <p>A port of {@code cache_caps50} in {@code celt/static_modes_float.h}, which
     * RFC 6716 section 4.3.3 names and tells implementations to reuse verbatim
     * rather than recompute. The eight rows are (LM 0 mono, LM 0 stereo, LM 1 mono,
     * ... LM 3 stereo), exactly the order the RFC's index expression
     * {@code nbBands*(2*LM+stereo)} produces.
     *
     * <p>Not printed in the RFC's prose, so there is no second statement to check it
     * against; the test instead checks the property the RFC does state about the
     * result -- that the scaled caps fit in a signed 16-bit integer but not in eight
     * bits. A cap that is too small silently costs the top of a band its resolution;
     * one that is too large wastes bitstream capacity that cannot be reclaimed.
     */
    private static final int[][] CAPS = {
        {224, 224, 224, 224, 224, 224, 224, 224, 160, 160, 160, 160, 185, 185, 185, 178, 178, 168, 134, 61, 37},
        {224, 224, 224, 224, 224, 224, 224, 224, 240, 240, 240, 240, 207, 207, 207, 198, 198, 183, 144, 66, 40},
        {160, 160, 160, 160, 160, 160, 160, 160, 185, 185, 185, 185, 193, 193, 193, 183, 183, 172, 138, 64, 38},
        {240, 240, 240, 240, 240, 240, 240, 240, 207, 207, 207, 207, 204, 204, 204, 193, 193, 180, 143, 66, 40},
        {185, 185, 185, 185, 185, 185, 185, 185, 193, 193, 193, 193, 193, 193, 193, 183, 183, 172, 138, 65, 39},
        {207, 207, 207, 207, 207, 207, 207, 207, 204, 204, 204, 204, 201, 201, 201, 188, 188, 176, 141, 66, 40},
        {193, 193, 193, 193, 193, 193, 193, 193, 193, 193, 193, 193, 194, 194, 194, 184, 184, 173, 139, 65, 39},
        {204, 204, 204, 204, 204, 204, 204, 204, 201, 201, 201, 201, 198, 198, 198, 187, 187, 175, 140, 66, 40}
    };

    /**
     * Conservative base-2 log of each 2.5 ms band width, in 1/8 bit units.
     *
     * <p>A port of {@code logN400} in {@code celt/static_modes_float.h}, which the
     * reference generates with {@code log2_frac(width, 3)} in {@code celt/cwrs.c}.
     * "Conservative" means rounded up: the allocator subtracts this from a band's
     * budget to decide how many bits go to fine energy rather than to shape, and
     * rounding the log down there would hand out bits the band cannot spend.
     *
     * <p>These are the 2.5 ms widths. For a longer frame add {@code LM*8}, which is
     * exact because shifting a width left by LM shifts its log by a whole number of
     * bits and cannot change the rounding.
     */
    private static final int[] LOG_N = {
        0, 0, 0, 0, 0, 0, 0, 0, 8, 8, 8, 8, 16, 16, 16, 21, 21, 24, 29, 34, 36
    };

    /**
     * Conservative base-2 log in 1/8 bit units of 1 through 24, used to price the
     * intensity stereo parameter.
     *
     * <p>A port of {@code LOG2_FRAC_TABLE} in {@code celt/rate.c}. RFC 6716
     * section 4.3.3 refers to it by name for the intensity reservation: entry
     * {@code n} is the cost of coding one value out of {@code n+1}, so the lookup is
     * indexed by the number of coded bands rather than by the number of choices.
     */
    private static final int[] LOG2_FRAC = {
        0,
        8, 13,
        16, 19, 21, 23,
        24, 26, 27, 28, 29, 30, 31, 32,
        32, 33, 34, 34, 35, 36, 36, 37, 37
    };

    /**
     * Time-frequency resolution adjustments, indexed by LM then by
     * {@code 4*transient + 2*tf_select + tf_change}.
     *
     * <p>A port of {@code tf_select_table} in {@code celt/celt.c}, which is the same
     * data RFC 6716 prints as Tables 60 through 63. Negative means more time
     * resolution, positive means more frequency resolution.
     */
    private static final int[][] TF_SELECT = {
        {0, -1, 0, -1, 0, -1, 0, -1},
        {0, -1, 0, -2, 1,  0, 1, -1},
        {0, -2, 0, -3, 2,  0, 1, -1},
        {0, -2, 0, -3, 3,  0, 1, -1}
    };

    /** Probability of each allocation trim value out of 128, RFC 6716 Table 58. */
    private static final int[] TRIM_PDF = {2, 2, 5, 10, 22, 46, 22, 10, 5, 2, 2};

    /**
     * The trim PDF as an inverse cumulative table for the range decoder.
     *
     * <p>A port of {@code trim_icdf} in {@code celt/celt.c}. Entry {@code i} is
     * {@code 128} minus the cumulative frequency through symbol {@code i}, which is
     * the form {@code ec_dec_icdf} consumes with {@code ftb = 7}.
     */
    private static final int[] TRIM_ICDF = {126, 124, 119, 109, 87, 41, 19, 9, 4, 2, 0};

    /** Bits of range-coder precision the trim symbol is coded with. */
    static final int TRIM_ICDF_FTB = 7;

    /** Probability of each spread value out of 32, RFC 6716 Table 56. */
    private static final int[] SPREAD_PDF = {7, 2, 21, 2};

    /**
     * The spread PDF as an inverse cumulative table for the range decoder.
     *
     * <p>A port of {@code spread_icdf} in {@code celt/celt.c}, used with
     * {@code ftb = 5}.
     */
    private static final int[] SPREAD_ICDF = {25, 23, 2, 0};

    /** Bits of range-coder precision the spread symbol is coded with. */
    static final int SPREAD_ICDF_FTB = 5;

    /**
     * The {@code f_r} rotation factor for each spread value, RFC 6716 Table 59.
     *
     * <p>Entry 0 is a sentinel: the RFC gives f_r as infinite for spread 0, meaning
     * no rotation at all, and zero is used here because no real factor can encode
     * "skip". Callers must test for spread 0 before using the value; see
     * {@link #spreadRotationFactor(int)}.
     */
    private static final int[] SPREAD_FACTOR = {0, 15, 10, 5};

    /**
     * Coefficients of the cubic that approximates {@code log2} of a mantissa in
     * [1, 2), highest power last.
     *
     * <p>A port of the polynomial in {@code celt_log2} in {@code celt/mathops.h}
     * under {@code FLOAT_APPROX}. The polynomial is evaluated in the variable
     * {@code m - 1.5} and returns {@code log2(m) - 1}.
     */
    private static final float[] LOG2_POLY = {-0.41445418f, 0.95909232f, -0.33951290f, 0.16541097f};

    /**
     * Coefficients of the cubic that approximates {@code 2^f} for {@code f} in
     * [0, 1), highest power last.
     *
     * <p>A port of the polynomial in {@code celt_exp2} in {@code celt/mathops.h}
     * under {@code FLOAT_APPROX}. The reference names the ideal coefficients
     * {@code K0 = 1, K1 = log(2), K2 = 3-4*log(2), K3 = 3*log(2)-2}; the numbers
     * below are the fitted ones it actually uses, which are not the same.
     */
    private static final float[] EXP2_POLY = {0.99992522f, 0.69583354f, 0.22606716f, 0.078024523f};

    /** How many bands the layer codes. */
    static int bandCount() {
        return BAND_COUNT;
    }

    /** How many quality rows the static allocation table has. */
    static int allocationRows() {
        return ALLOCATION_ROWS;
    }

    /**
     * Lower edge of a band in MDCT bins of the 2.5 ms frame.
     *
     * @param edge 0 through {@link #BAND_COUNT}; the last is the top edge of the
     *     highest band, not the start of a band
     */
    static int eband(int edge) {
        checkRange(edge, BAND_COUNT + 1, "band edge");
        return EBANDS_2_5MS[edge];
    }

    /**
     * Frequency of a band edge in Hz, as printed in RFC 6716 Table 55.
     *
     * @param edge 0 through {@link #BAND_COUNT}
     */
    static int bandEdgeHz(int edge) {
        checkRange(edge, BAND_COUNT + 1, "band edge");
        return BAND_EDGE_HZ[edge];
    }

    /**
     * MDCT bins in a band, as printed in RFC 6716 Table 55.
     *
     * @param lm 0 for 2.5 ms through 3 for 20 ms
     * @param band 0 through {@link #BAND_COUNT} minus one
     */
    static int binsPerBand(int lm, int band) {
        checkRange(lm, BINS_PER_BAND.length, "LM");
        checkRange(band, BAND_COUNT, "band");
        return BINS_PER_BAND[lm][band];
    }

    /**
     * A static allocation table entry in 1/32 bit per MDCT bin.
     *
     * @param row quality row, 0 through {@link #ALLOCATION_ROWS} minus one
     * @param band 0 through {@link #BAND_COUNT} minus one
     */
    static int allocation(int row, int band) {
        checkRange(row, ALLOCATION_ROWS, "allocation row");
        checkRange(band, BAND_COUNT, "band");
        return ALLOCATION[row][band];
    }

    /**
     * A raw allocation ceiling, before the scaling described in RFC 6716
     * section 4.3.3.
     *
     * @param lm 0 for 2.5 ms through 3 for 20 ms
     * @param channels 1 or 2
     * @param band 0 through {@link #BAND_COUNT} minus one
     */
    static int cap(int lm, int channels, int band) {
        checkRange(lm, 4, "LM");
        if (channels != 1 && channels != 2) {
            throw new IllegalArgumentException("channels must be 1 or 2, got " + channels);
        }
        checkRange(band, BAND_COUNT, "band");
        return CAPS[2 * lm + channels - 1][band];
    }

    /** Conservative log2 of a 2.5 ms band width, in 1/8 bit units. */
    static int logN(int band) {
        checkRange(band, BAND_COUNT, "band");
        return LOG_N[band];
    }

    /**
     * Conservative log2 in 1/8 bit units of {@code choices + 1}.
     *
     * @param choices 0 through 23; in practice the number of coded bands
     */
    static int log2Frac(int choices) {
        checkRange(choices, LOG2_FRAC.length, "coded band count");
        return LOG2_FRAC[choices];
    }

    /**
     * Time-frequency resolution adjustment for one band.
     *
     * @param lm 0 for 2.5 ms through 3 for 20 ms
     * @param isTransient whether the frame set the transient flag
     * @param tfSelect the decoded tf_select flag, 0 or 1
     * @param tfChange the decoded per-band tf_change flag, 0 or 1
     */
    static int tfAdjustment(int lm, boolean isTransient, int tfSelect, int tfChange) {
        checkRange(lm, TF_SELECT.length, "LM");
        checkRange(tfSelect, 2, "tf_select");
        checkRange(tfChange, 2, "tf_change");
        return TF_SELECT[lm][(isTransient ? 4 : 0) + 2 * tfSelect + tfChange];
    }

    /** Probability of one trim value out of 128. */
    static int trimProbability(int trim) {
        checkRange(trim, TRIM_PDF.length, "trim");
        return TRIM_PDF[trim];
    }

    /** Probability of one spread value out of 32. */
    static int spreadProbability(int spread) {
        checkRange(spread, SPREAD_PDF.length, "spread");
        return SPREAD_PDF[spread];
    }

    /**
     * The trim symbol's inverse CDF, freshly copied, in the shape
     * {@link RangeDecoder#decodeIcdf} takes.
     *
     * <p>Copies, so call it once when a decoder is built and never per frame.
     */
    static short[] copyTrimIcdf() {
        return toShorts(TRIM_ICDF);
    }

    /**
     * The spread symbol's inverse CDF, freshly copied, in the shape
     * {@link RangeDecoder#decodeIcdf} takes.
     *
     * <p>Copies, so call it once when a decoder is built and never per frame.
     */
    static short[] copySpreadIcdf() {
        return toShorts(SPREAD_ICDF);
    }

    private static short[] toShorts(int[] values) {
        short[] result = new short[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (short) values[i];
        }
        return result;
    }

    /**
     * The rotation factor {@code f_r} for a spread value, or zero for spread 0.
     *
     * <p>RFC 6716 Table 59 gives f_r as infinite for spread 0, which makes the
     * rotation gain {@code N/(N+f_r*K)} zero and the rotation a no-op. Zero is
     * returned instead of any finite factor so that a caller that forgets the
     * special case gets an obviously wrong answer rather than a subtly wrong one.
     *
     * @param spread 0 through 3
     */
    static int spreadRotationFactor(int spread) {
        checkRange(spread, SPREAD_FACTOR.length, "spread");
        return SPREAD_FACTOR[spread];
    }

    /**
     * Base-2 logarithm, matching the default float build of the reference.
     *
     * <p>A port of {@code celt_log2} in {@code celt/mathops.h}. The reference builds
     * without {@code FLOAT_APPROX} by default, so this is a libm call scaled by
     * {@code 1/ln(2)}; {@link #log2Approx(float)} is the other branch.
     *
     * <p>{@code StrictMath} rather than {@code Math}: {@code Math.log} is allowed a
     * one-ulp error that may differ between JVMs and between machines, and this
     * function sets band energies, so two decoders could disagree in the last bit of
     * a gain. The RFC only requires bit exactness of the integer entropy layer, so
     * either would decode correctly, but a codec that gives different bytes on
     * different machines cannot be regression tested.
     */
    static float log2(float x) {
        return (float) (1.442695040888963387 * StrictMath.log(x));
    }

    /**
     * Base-2 exponential, matching the default float build of the reference.
     *
     * <p>A port of {@code celt_exp2} in {@code celt/mathops.h}, the branch taken when
     * {@code FLOAT_APPROX} is not defined. See {@link #log2(float)} for why this uses
     * {@code StrictMath}.
     */
    static float exp2(float x) {
        return (float) StrictMath.exp(0.6931471805599453094 * x);
    }

    /**
     * Base-2 logarithm by the polynomial CELT uses in place of libm.
     *
     * <p>A port of {@code celt_log2} in {@code celt/mathops.h} under
     * {@code FLOAT_APPROX}: pull the exponent straight out of the IEEE-754 bits, then
     * fit the remaining mantissa with a cubic. Accurate to about 0.003 bits, which is
     * far finer than the 6 dB coarse energy step it feeds, and roughly an order of
     * magnitude faster than a correctly rounded log.
     *
     * <p>As in the reference, denormals, infinities and NaN are not handled, and the
     * argument must be strictly positive.
     */
    static float log2Approx(float x) {
        int bits = Float.floatToRawIntBits(x);
        int exponent = (bits >> 23) - 127;
        float mantissa = Float.intBitsToFloat(bits - (exponent << 23));
        float f = mantissa - 1.5f;
        float poly = LOG2_POLY[0] + f * (LOG2_POLY[1] + f * (LOG2_POLY[2] + f * LOG2_POLY[3]));
        return 1 + exponent + poly;
    }

    /**
     * Base-2 exponential by the polynomial CELT uses in place of libm.
     *
     * <p>A port of {@code celt_exp2} in {@code celt/mathops.h} under
     * {@code FLOAT_APPROX}: fit the fractional part with a cubic, then add the
     * integer part straight into the IEEE-754 exponent field. The masking of the sign
     * bit is the reference's, and is what keeps a large negative argument from
     * producing a negative gain instead of a small one.
     */
    static float exp2Approx(float x) {
        int integer = (int) StrictMath.floor(x);
        if (integer < -50) {
            return 0;
        }
        float f = x - integer;
        float poly = EXP2_POLY[0] + f * (EXP2_POLY[1] + f * (EXP2_POLY[2] + f * EXP2_POLY[3]));
        int bits = (Float.floatToRawIntBits(poly) + (integer << 23)) & 0x7fffffff;
        return Float.intBitsToFloat(bits);
    }

    private static void checkRange(int value, int bound, String what) {
        if (value < 0 || value >= bound) {
            throw new IndexOutOfBoundsException(what + " " + value + " is outside 0.." + (bound - 1));
        }
    }
}
