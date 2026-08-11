package net.chonkbase.assetpack.codec.opus;

/**
 * The bit-usage arithmetic the Opus range encoder and decoder must agree on.
 *
 * <p>A port of {@code ec_tell_frac} in {@code celt/entcode.c} and of
 * {@code EC_ILOG} and {@code ec_tell} in {@code celt/entcode.h}, specified by
 * RFC 6716 sections 4.1.6 and 5.1.6.
 *
 * <p>Shared between the two coders for the same reason upstream shares it: the
 * RFC requires these functions to "produce exactly the same value returned by
 * the same functions in the encoder after encoding the same symbols", and CELT
 * reads them constantly to decide how many bits each band may spend. Two copies
 * that drifted by one would not corrupt a symbol -- both sides would still
 * decode every symbol they were given -- they would silently hand the decoder a
 * different bit allocation from the one the encoder used, and a whole frame of
 * audio would come out plausible and wrong.
 *
 * <p>Integer throughout, with no floating point anywhere: this is an exact
 * computation and a {@code double} log would round differently on different
 * hardware. {@code rng} arrives as a {@code long} carrying an unsigned 32-bit
 * value, because it reaches exactly 2**31 the moment a decoder finishes
 * initialising and a signed {@code int} would be negative there.
 */
final class EntropyCode {

    /** The eight-fold precision of {@code ec_tell_frac}, {@code BITRES} upstream. */
    static final int BITRES = 3;

    private EntropyCode() {
    }

    /**
     * One more than the index of the highest set bit; zero for zero.
     *
     * <p>{@code EC_ILOG} upstream, which is the number of whole bits still
     * buffered inside {@code rng} and therefore not yet spent.
     */
    static int ilog(long value) {
        return 64 - Long.numberOfLeadingZeros(value);
    }

    /**
     * Whole bits used so far, {@code ec_tell}.
     *
     * <p>A conservative upper bound, never an exact count. A freshly
     * initialised coder reports 1 rather than 0, and that is not an off-by-one:
     * RFC 6716 section 4.1.6.1 reserves the bit for terminating the encoder's
     * stream, and every allocation decision in CELT is made against a budget
     * that already has it subtracted.
     */
    static int tell(int nbitsTotal, long rng) {
        return nbitsTotal - ilog(rng);
    }

    /**
     * Bits used so far in eighths, {@code ec_tell_frac}.
     *
     * <p>Refines {@link #tell} by squaring the fractional part of {@code rng}
     * three times, taking one more bit of the base-2 logarithm each time. This
     * is the whole of RFC 6716 section 4.1.6.2 and it must be done in exactly
     * this order: the guarantee CELT depends on is that
     * {@code tell() == ceil(tellFrac()/8)}, and a rounding done differently
     * breaks that identity for some values of {@code rng} and nothing else.
     */
    static int tellFrac(int nbitsTotal, long rng) {
        int lg = ilog(rng);
        // Q15: 32768 <= r < 65536, the fractional part of rng above its
        // highest set bit. lg is at least 24 for any normalised rng, so the
        // shift is at least 8 and never negative.
        int r = (int) (rng >>> (lg - 16));
        for (int i = 0; i < BITRES; i++) {
            // The product needs 32 unsigned bits; a signed int multiply would
            // go negative for r above 46341 and the shift would then bring
            // down sign bits instead of the square.
            r = (int) (((long) r * r) >>> 15);
            int bit = r >>> 16;
            lg = (lg << 1) | bit;
            r >>>= bit;
        }
        return (nbitsTotal << BITRES) - lg;
    }
}
