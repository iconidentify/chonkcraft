package net.chonkbase.assetpack.codec.opus;

import java.util.Arrays;
import java.util.Objects;

/**
 * The CELT band shape decoder: RFC 6716 sections 4.3.4 through 4.3.6.
 *
 * <p>A port of the decode half of {@code quant_all_bands}, {@code quant_band},
 * {@code anti_collapse} and {@code denormalise_bands} in {@code celt/bands.c},
 * of {@code alg_unquant}, {@code exp_rotation} and {@code renormalise_vector} in
 * {@code celt/vq.c}, and of the pulse cache that {@code bits2pulses} and
 * {@code pulses2bits} in {@code celt/rate.h} read.
 *
 * <p>The encoder halves of those functions are deliberately absent. Everything
 * under {@code if (encode)} upstream is gone; everything under
 * {@code if (resynth)} is kept, because {@code resynth} is {@code !encode} and
 * so is always true on this side.
 *
 * <p>This owns the workspace the recursion needs, sized once for the longest
 * frame, so a decode allocates nothing. It also owns the folding seed, which is
 * decoder state and survives across frames.
 *
 * <p><b>What this layer is for.</b> The allocator has already decided how many
 * eighth bits each band may spend. This turns that budget into an actual unit
 * vector per band: a PVQ codeword where there are bits to pay for one, a folded
 * copy of a lower band where there are not, and pseudo-random noise where there
 * is no lower band to fold from. A band left at zero instead would be a hole in
 * the spectrum, heard on music as a hollow phasey quality that follows the
 * melody around -- which is why CELT never leaves a band empty.
 */
final class CeltBands {

    /** {@code BITRES}: the allocator works in eighths of a bit. */
    private static final int BIT_RES = CeltMode.BIT_RES;

    /** {@code SPREAD_NONE} in {@code celt/bands.h}: no rotation at all. */
    static final int SPREAD_NONE = 0;

    /** {@code SPREAD_NORMAL} in {@code celt/bands.h}, the default when none is coded. */
    static final int SPREAD_NORMAL = 2;

    /** {@code SPREAD_AGGRESSIVE} in {@code celt/bands.h}: the widest rotation. */
    static final int SPREAD_AGGRESSIVE = 3;

    /** {@code QTHETA_OFFSET} in {@code celt/rate.h}. */
    private static final int QTHETA_OFFSET = 4;

    /** {@code QTHETA_OFFSET_TWOPHASE} in {@code celt/rate.h}. */
    private static final int QTHETA_OFFSET_TWOPHASE = 16;

    /** {@code LOG_MAX_PSEUDO} in {@code celt/rate.h}: bisection steps in {@code bits2pulses}. */
    private static final int LOG_MAX_PSEUDO = 6;

    /**
     * {@code cache_index50} in {@code celt/static_modes_float.h}.
     *
     * <p>Five rows of 21 bands, the rows being {@code LM+1} for LM from -1 to 3.
     * Each entry is where that band's pseudo-pulse cost table starts inside
     * {@link #CACHE_BITS}, or -1 for a band that has no coefficients at that
     * frame size and so has no table.
     */
    private static final short[] CACHE_INDEX = {
        -1, -1, -1, -1, -1, -1, -1, -1, 0, 0, 0, 0, 41, 41, 41,
        82, 82, 123, 164, 200, 222, 0, 0, 0, 0, 0, 0, 0, 0, 41,
        41, 41, 41, 123, 123, 123, 164, 164, 240, 266, 283, 295, 41, 41, 41,
        41, 41, 41, 41, 41, 123, 123, 123, 123, 240, 240, 240, 266, 266, 305,
        318, 328, 336, 123, 123, 123, 123, 123, 123, 123, 123, 240, 240, 240, 240,
        305, 305, 305, 318, 318, 343, 351, 358, 364, 240, 240, 240, 240, 240, 240,
        240, 240, 305, 305, 305, 305, 343, 343, 343, 351, 351, 370, 376, 382, 387,
    };

    /**
     * {@code cache_bits50} in {@code celt/static_modes_float.h}.
     *
     * <p>Each band's table starts with the largest pseudo-pulse index it holds,
     * then one byte per index giving the cost, in eighth bits less one, of
     * coding {@code getPulses(k)} pulses in that band.
     * {@code compute_pulse_cache} in {@code celt/rate.c} builds this from
     * {@code get_required_bits}; the RFC ships it precomputed because the 48 kHz
     * mode is the only one Opus proper uses.
     */
    private static final int[] CACHE_BITS = {
        40, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 40, 15, 23, 28,
        31, 34, 36, 38, 39, 41, 42, 43, 44, 45, 46, 47, 47, 49, 50,
        51, 52, 53, 54, 55, 55, 57, 58, 59, 60, 61, 62, 63, 63, 65,
        66, 67, 68, 69, 70, 71, 71, 40, 20, 33, 41, 48, 53, 57, 61,
        64, 66, 69, 71, 73, 75, 76, 78, 80, 82, 85, 87, 89, 91, 92,
        94, 96, 98, 101, 103, 105, 107, 108, 110, 112, 114, 117, 119, 121, 123,
        124, 126, 128, 40, 23, 39, 51, 60, 67, 73, 79, 83, 87, 91, 94,
        97, 100, 102, 105, 107, 111, 115, 118, 121, 124, 126, 129, 131, 135, 139,
        142, 145, 148, 150, 153, 155, 159, 163, 166, 169, 172, 174, 177, 179, 35,
        28, 49, 65, 78, 89, 99, 107, 114, 120, 126, 132, 136, 141, 145, 149,
        153, 159, 165, 171, 176, 180, 185, 189, 192, 199, 205, 211, 216, 220, 225,
        229, 232, 239, 245, 251, 21, 33, 58, 79, 97, 112, 125, 137, 148, 157,
        166, 174, 182, 189, 195, 201, 207, 217, 227, 235, 243, 251, 17, 35, 63,
        86, 106, 123, 139, 152, 165, 177, 187, 197, 206, 214, 222, 230, 237, 250,
        25, 31, 55, 75, 91, 105, 117, 128, 138, 146, 154, 161, 168, 174, 180,
        185, 190, 200, 208, 215, 222, 229, 235, 240, 245, 255, 16, 36, 65, 89,
        110, 128, 144, 159, 173, 185, 196, 207, 217, 226, 234, 242, 250, 11, 41,
        74, 103, 128, 151, 172, 191, 209, 225, 241, 255, 9, 43, 79, 110, 138,
        163, 186, 207, 227, 246, 12, 39, 71, 99, 123, 144, 164, 182, 198, 214,
        228, 241, 253, 9, 44, 81, 113, 142, 168, 192, 214, 235, 255, 7, 49,
        90, 127, 160, 191, 220, 247, 6, 51, 95, 134, 170, 203, 234, 7, 47,
        87, 123, 155, 184, 212, 237, 6, 52, 97, 137, 174, 208, 240, 5, 57,
        106, 151, 192, 231, 5, 59, 111, 158, 202, 243, 5, 55, 103, 147, 187,
        224, 5, 60, 113, 161, 206, 248, 4, 65, 122, 175, 224, 4, 67, 127,
        182, 234,
    };

    /** {@code exp2_table8} in {@code compute_qn}: 2**(k/8) in Q14, k = 0 to 7. */
    private static final int[] EXP2_TABLE8 = {
        16384, 17866, 19483, 21247, 23170, 25267, 27554, 30048
    };

    /** {@code SPREAD_FACTOR} in {@code exp_rotation}, indexed by spread minus one. */
    private static final int[] SPREAD_FACTOR = {15, 10, 5};

    /**
     * {@code bit_interleave_table} in {@code quant_band}.
     *
     * <p>Folds a four-bit collapse mask down to two bits when short blocks are
     * recombined into one.
     */
    private static final int[] BIT_INTERLEAVE = {
        0, 1, 1, 1, 2, 3, 3, 3, 2, 3, 3, 3, 2, 3, 3, 3
    };

    /** {@code bit_deinterleave_table} in {@code quant_band}: the inverse spread. */
    private static final int[] BIT_DEINTERLEAVE = {
        0x00, 0x03, 0x0C, 0x0F, 0x30, 0x33, 0x3C, 0x3F,
        0xC0, 0xC3, 0xCC, 0xCF, 0xF0, 0xF3, 0xFC, 0xFF
    };

    /**
     * {@code ordery_table} in {@code celt/bands.c}, its four rows concatenated.
     *
     * <p>A bit-reversed Gray code with the order inverted so the DC term lands
     * at the end. The row for a given stride starts at {@code stride - 2}.
     */
    private static final int[] ORDERY = {
        1, 0,
        3, 0, 2, 1,
        7, 0, 4, 3, 6, 1, 5, 2,
        15, 0, 8, 7, 12, 3, 11, 4, 14, 1, 9, 6, 13, 2, 10, 5,
    };

    /**
     * {@code (.5f*PI)} from {@code celt_cos_norm} in {@code celt/mathops.h}.
     *
     * <p>The reference defines {@code PI} as {@code 3.141592653f}, not as the
     * correctly rounded float nearest pi, and evaluates the halving in float.
     * Using a more accurate constant here would rotate every band by a very
     * slightly different angle from the encoder's.
     */
    private static final float HALF_PI = 0.5f * 3.141592653f;

    /** 1/sqrt(2), the Haar scale. */
    private static final float SQRT_HALF = 0.70710678f;

    /** {@code EPSILON} in the float build of {@code celt/arch.h}. */
    private static final float EPSILON = 1e-15f;

    private final int bands;

    /** {@code _norm}, both channels: what the bands above fold from. */
    private final float[] norm;

    /** Where channel one starts inside {@link #norm}, {@code norm2 - norm} upstream. */
    private final int norm2;

    /** {@code lowband_scratch}: a copy of the folding source, safe to rewrite in place. */
    private final float[] lowbandScratch;

    /** The temporary the two Hadamard reorderings need. */
    private final float[] shuffle;

    /** {@code iy} in {@code alg_unquant}: the decoded pulse vector. */
    private final int[] pulseVector;

    // Per-frame parameters, held as fields so the recursion carries a dozen
    // arguments rather than sixteen. Set once at the top of decode().
    private RangeDecoder dec;
    private int spread;
    private int intensity;
    private int remainingBits;

    /**
     * {@code st->rng} used as the folding seed, {@code *seed} inside
     * {@code quant_band}.
     *
     * <p>Decoder state, not frame state. The encoder ran the same generator over
     * the same sequence of bands, so a decoder that reseeded per frame would
     * fill every unallocated band with different noise from the one the encoder
     * heard when it decided that band did not need bits.
     */
    private int seed;

    /**
     * @param mode     any CELT mode; only its band count is read, and every mode
     *                 has the same one
     * @param channels 1 or 2
     */
    CeltBands(CeltMode mode, int channels) {
        Objects.requireNonNull(mode, "mode");
        if (channels != 1 && channels != 2) {
            throw new IllegalArgumentException("CELT codes 1 or 2 channels, not " + channels);
        }
        this.bands = mode.bandCount();
        // Sized for the longest frame whatever frame this decoder sees first,
        // because a stream may change frame size on any packet and the
        // per-frame path must not allocate.
        CeltMode longest = CeltMode.forLm(3);
        int widest = longest.bandWidth(bands - 1);
        this.norm2 = longest.codedBins();
        this.norm = new float[2 * norm2];
        this.lowbandScratch = new float[widest];
        this.shuffle = new float[widest];
        this.pulseVector = new int[widest];
    }

    /** The folding seed, {@code st->rng}. */
    int seed() {
        return seed;
    }

    /** Sets the folding seed; the frame loop hands it the range coder's state. */
    void setSeed(int value) {
        this.seed = value;
    }

    /**
     * {@code celt_lcg_rand} in {@code celt/bands.c}.
     *
     * <p>Knuth's linear congruential generator. It has to be this one and no
     * other: the encoder decided a band needed no bits after hearing the noise
     * this sequence produces, so a decoder generating different noise would be
     * filling those bands with something the encoder never evaluated.
     */
    static int lcgRand(int state) {
        return 1664525 * state + 1013904223;
    }

    /**
     * {@code get_pulses} in {@code celt/rate.h}: pseudo-pulse index to pulse count.
     *
     * <p>Linear to 7, then three mantissa bits and an exponent, which is what
     * keeps the cost tables short at high rates.
     */
    static int getPulses(int index) {
        return index < 8 ? index : (8 + (index & 7)) << ((index >> 3) - 1);
    }

    /**
     * {@code bits2pulses} in {@code celt/rate.h}: the pseudo-pulse index whose
     * cost is nearest the budget.
     *
     * <p>Nearest, not largest that fits. RFC 6716 section 4.3.4.1 says to choose
     * the K "that produces the number of bits nearest to the allocated value",
     * and the band decoder afterwards walks K down until the frame's remaining
     * bits actually cover it. Taking the largest that fits instead would pick a
     * different K from the encoder's on any band whose budget falls between two
     * entries, and the codeword would then be read from the wrong codebook --
     * which desynchronises the range coder and ruins the rest of the frame, not
     * just that band.
     *
     * @param band the band index, 0 to 20
     * @param lm   the frame size index this band is coded at, which the split
     *             recursion drives down as far as -1
     * @param bits the budget in eighth bits
     */
    static int bits2pulses(int band, int lm, int bits) {
        int base = cacheBase(band, lm);
        int lo = 0;
        int hi = CACHE_BITS[base];
        int target = bits - 1;
        for (int i = 0; i < LOG_MAX_PSEUDO; i++) {
            int mid = (lo + hi + 1) >> 1;
            if (CACHE_BITS[base + mid] >= target) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        int below = lo == 0 ? -1 : CACHE_BITS[base + lo];
        return target - below <= CACHE_BITS[base + hi] - target ? lo : hi;
    }

    /**
     * {@code pulses2bits} in {@code celt/rate.h}: what that many pseudo-pulses cost.
     *
     * @param band   the band index, 0 to 20
     * @param lm     the frame size index this band is coded at
     * @param pulses a pseudo-pulse index, not a pulse count
     */
    static int pulses2bits(int band, int lm, int pulses) {
        if (pulses == 0) {
            return 0;
        }
        return CACHE_BITS[cacheBase(band, lm) + pulses] + 1;
    }

    /**
     * What the largest codebook for a band costs, {@code cache[cache[0]]}.
     *
     * <p>{@code quant_band} splits a band when its budget runs more than 12
     * eighth bits past this, because past that point one codebook cannot express
     * the shape however many pulses it is given. Both ends compute it and both
     * must agree: a band split on one side and not the other reads a different
     * number of symbols from that point on and the rest of the frame is noise.
     */
    static int splitThreshold(int band, int lm) {
        int base = cacheBase(band, lm);
        return CACHE_BITS[base + CACHE_BITS[base]];
    }

    private static int cacheBase(int band, int lm) {
        if (band < 0 || band >= CeltMode.BAND_COUNT) {
            throw new IndexOutOfBoundsException("band " + band + " is outside 0.."
                    + (CeltMode.BAND_COUNT - 1));
        }
        if (lm < -1 || lm > 3) {
            throw new IndexOutOfBoundsException("frame size index " + lm + " is outside -1..3");
        }
        int base = CACHE_INDEX[(lm + 1) * CeltMode.BAND_COUNT + band];
        if (base < 0) {
            // Cannot happen from the decode path: the -1 entries are the eight
            // one-bin bands at the shortest frame size, and a one-bin band
            // returns from quantBand's N==1 case before any of this is reached.
            // Checked so that a caller with a different band layout gets a
            // sentence instead of a negative array index.
            throw new IllegalStateException("band " + band + " has no pulse cache at frame size"
                    + " index " + lm + " because it holds no coefficients there");
        }
        return base;
    }

    /**
     * {@code quant_all_bands}, decode side: RFC 6716 section 4.3.4.
     *
     * <p>Walks the coded bands upwards from {@code start}, hands each the share
     * of the frame the allocator set aside plus whatever the bands below did not
     * spend, and writes a unit-norm shape into {@code spectrum}.
     *
     * @param decoder       the frame's range decoder
     * @param mode          the mode for this frame size
     * @param start         first coded band
     * @param end           one past the last coded band
     * @param spectrum      receives the normalised shapes, {@code channels*frameSize}
     *                      floats laid out {@code channel*frameSize + bin}
     * @param channels      1 or 2
     * @param collapseMasks receives one byte per band per channel, laid out
     *                      {@code band*channels + channel}; bit k is set when
     *                      short block k of that band ended up holding energy
     * @param pulses        the allocator's per-band budget in eighth bits
     * @param shortBlocks   {@code 1 << LM} for a transient frame, 0 otherwise
     * @param spreadValue   the decoded spread symbol, 0 to 3
     * @param dualStereo    the decoded dual stereo flag
     * @param intensityBand the decoded intensity stereo band
     * @param tfRes         the per-band time-frequency change, already resolved
     *                      through the tf_select table
     * @param totalBits     the frame in eighth bits, less the anti-collapse bit
     * @param balanceIn     the allocator's leftover, in eighth bits
     * @param lm            the frame size index, 0 to 3
     * @param codedBands    how many bands the allocator decided to code
     */
    void decode(RangeDecoder decoder, CeltMode mode, int start, int end,
            float[] spectrum, int channels, byte[] collapseMasks, int[] pulses,
            int shortBlocks, int spreadValue, int dualStereo, int intensityBand,
            int[] tfRes, int totalBits, int balanceIn, int lm, int codedBands) {
        Objects.requireNonNull(decoder, "decoder");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(spectrum, "spectrum");
        Objects.requireNonNull(collapseMasks, "collapseMasks");
        Objects.requireNonNull(pulses, "pulses");
        Objects.requireNonNull(tfRes, "tfRes");
        if (start < 0 || start > end || end > bands) {
            throw new IllegalArgumentException("coded band range [" + start + "," + end
                    + ") is not inside the " + bands + " bands of this mode");
        }
        if (channels != 1 && channels != 2) {
            throw new IllegalArgumentException("CELT codes 1 or 2 channels, not " + channels);
        }
        int frameSize = mode.frameSize();
        if (spectrum.length < (long) channels * frameSize) {
            throw new IllegalArgumentException("the spectrum needs " + (channels * frameSize)
                    + " floats and has " + spectrum.length);
        }
        if (collapseMasks.length < bands * channels) {
            throw new IllegalArgumentException("the collapse masks need " + (bands * channels)
                    + " bytes and have " + collapseMasks.length);
        }

        this.dec = decoder;
        this.spread = spreadValue;
        this.intensity = intensityBand;

        int blocks = shortBlocks != 0 ? 1 << lm : 1;
        boolean stereo = channels == 2;
        int dual = dualStereo;
        int balance = balanceIn;

        int lowbandOffset = 0;
        boolean updateLowband = true;

        for (int i = start; i < end; i++) {
            int bandStart = mode.bandStart(i);
            int n = mode.bandWidth(i);
            int xo = bandStart;
            int yo = frameSize + bandStart;

            int tell = decoder.tellFrac();

            // The balance is what the bands below left on the table. Spending it
            // here rather than at the end of the frame is what lets a band that
            // needed one more pulse than its share get it, and is why both sides
            // must walk the bands in the same order.
            if (i != start) {
                balance -= tell;
            }
            remainingBits = totalBits - tell - 1;
            int b;
            if (i <= codedBands - 1) {
                int currBalance = balance / Math.min(3, codedBands - i);
                b = Math.max(0, Math.min(16383,
                        Math.min(remainingBits + 1, pulses[i] + currBalance)));
            } else {
                b = 0;
            }

            if (bandStart - n >= mode.bandStart(start) && (updateLowband || lowbandOffset == 0)) {
                lowbandOffset = i;
            }

            int tfChange = tfRes[i];
            // The reference also redirects X and Y at the scratch buffer for
            // bands at or above effEBands. The 48 kHz mode has effEBands equal
            // to nbEBands, so no such band exists and the redirect is dead code
            // here; it is left out rather than written and never taken.

            int xCm;
            int yCm;
            int effectiveLowband = -1;
            if (lowbandOffset != 0 && (spread != SPREAD_AGGRESSIVE || blocks > 1 || tfChange < 0)) {
                // Never fold a band onto itself: the source window is pushed
                // down so that it ends where this band begins.
                effectiveLowband = Math.max(mode.bandStart(start),
                        mode.bandStart(lowbandOffset) - n);
                int foldStart = lowbandOffset;
                while (mode.bandStart(--foldStart) > effectiveLowband) {
                    // Walks down to the band the fold window starts inside.
                }
                int foldEnd = lowbandOffset - 1;
                while (mode.bandStart(++foldEnd) < effectiveLowband + n) {
                    if (foldEnd + 1 >= bands) {
                        throw new IllegalStateException("the fold window for band " + i
                                + " runs past the top of the band layout");
                    }
                }
                xCm = 0;
                yCm = 0;
                int foldI = foldStart;
                do {
                    xCm |= collapseMasks[foldI * channels] & 0xFF;
                    yCm |= collapseMasks[foldI * channels + channels - 1] & 0xFF;
                } while (++foldI < foldEnd);
            } else {
                // Folding from the noise generator instead, which fills every
                // short block, so nothing is treated as collapsed.
                xCm = (1 << blocks) - 1;
                yCm = xCm;
            }

            if (dual != 0 && i == intensityBand) {
                // From here up the two channels share one shape, so the folding
                // source has to become the mid of what the two channels held.
                dual = 0;
                for (int j = mode.bandStart(start); j < bandStart; j++) {
                    norm[j] = 0.5f * (norm[j] + norm[norm2 + j]);
                }
            }

            if (dual != 0) {
                xCm = quantBand(i, spectrum, xo, null, 0, n, b / 2, blocks, tfChange,
                        effectiveLowband != -1 ? norm : null, effectiveLowband,
                        lm, norm, bandStart, 0, 1.0f, lowbandScratch, xCm);
                yCm = quantBand(i, spectrum, yo, null, 0, n, b / 2, blocks, tfChange,
                        effectiveLowband != -1 ? norm : null, norm2 + effectiveLowband,
                        lm, norm, norm2 + bandStart, 0, 1.0f, lowbandScratch, yCm);
            } else {
                xCm = quantBand(i, spectrum, xo, stereo ? spectrum : null, yo, n, b, blocks,
                        tfChange, effectiveLowband != -1 ? norm : null, effectiveLowband,
                        lm, norm, bandStart, 0, 1.0f, lowbandScratch, xCm | yCm);
                yCm = xCm;
            }
            collapseMasks[i * channels] = (byte) xCm;
            collapseMasks[i * channels + channels - 1] = (byte) yCm;
            balance += pulses[i] + tell;

            // Only keep folding from a band coded at better than one bit per
            // sample. Below that its shape is mostly noise already, and folding
            // it upwards would spread that noise over the whole top of the
            // spectrum.
            updateLowband = b > (n << BIT_RES);
        }
    }

    /**
     * {@code quant_band} in {@code celt/bands.c}, decode side: one band, or one
     * half of a band that was split.
     *
     * <p>Recursive. A band splits when it would need about 1.5 bits more than
     * its largest codebook can express, and each split codes an angle saying how
     * the energy divides between the halves; a 20 ms band can end up in eight
     * pieces. The stereo case is the same machinery with the two channels as the
     * two halves.
     *
     * @param i        the band index, used for the pulse cache and for logN
     * @param xa       the array holding this piece's coefficients
     * @param xo       where in it they start
     * @param ya       the second channel's array, or null for mono
     * @param yo       where in it they start
     * @param n        coefficients in this piece
     * @param b        its budget in eighth bits
     * @param bb       how many short blocks the piece spans, {@code B} upstream
     * @param tfChange this band's time-frequency adjustment
     * @param lowbandA the folding source array, or null
     * @param lowbandO where in it the source starts
     * @param lm       the frame size index at this level of the recursion
     * @param outA     where to leave the shape for bands above to fold from, or null
     * @param outO     the offset in it
     * @param level    recursion depth; only level 0 does the block shuffling
     * @param gain     the amplitude this piece should end up with
     * @param scratchA a buffer the folding source may be copied into, or null
     * @param fill     which short blocks of the folding source hold anything
     * @return the collapse mask, one bit per short block
     */
    private int quantBand(int i, float[] xa, int xo, float[] ya, int yo, int n, int b,
            int bb, int tfChange, float[] lowbandA, int lowbandO, int lm,
            float[] outA, int outO, int level, float gain, float[] scratchA, int fill) {
        int n0 = n;
        int nb = n;
        int nb0;
        int b0 = bb;
        int timeDivide = 0;
        int recombine = 0;
        int inv = 0;
        float mid = 0;
        float side = 0;
        boolean longBlocks = b0 == 1;
        int cm = 0;

        nb /= bb;
        nb0 = nb;

        boolean stereo = ya != null;
        boolean split = stereo;

        if (n == 1) {
            // A one-bin band has no shape beyond its sign, and the sign is a raw
            // bit rather than a range-coded symbol.
            float[] arr = xa;
            int off = xo;
            for (int c = 0; c < (stereo ? 2 : 1); c++) {
                int sign = 0;
                if (remainingBits >= 1 << BIT_RES) {
                    sign = dec.decodeRawBits(1);
                    remainingBits -= 1 << BIT_RES;
                }
                arr[off] = sign != 0 ? -1.0f : 1.0f;
                arr = ya;
                off = yo;
            }
            if (outA != null) {
                outA[outO] = xa[xo];
            }
            return 1;
        }

        if (!stereo && level == 0) {
            if (tfChange > 0) {
                recombine = tfChange;
            }
            // The folding source is about to be rewritten in place, and it lives
            // in the buffer the bands below left their shapes in. Copying first
            // is what stops one band's time-frequency choice from corrupting the
            // folding source of every band above it.
            if (lowbandA != null
                    && (recombine != 0 || ((nb & 1) == 0 && tfChange < 0) || b0 > 1)) {
                if (scratchA == null) {
                    throw new IllegalStateException("band " + i + " must copy its folding source"
                            + " and was given no scratch buffer");
                }
                System.arraycopy(lowbandA, lowbandO, scratchA, 0, n);
                lowbandA = scratchA;
                lowbandO = 0;
            }

            for (int k = 0; k < recombine; k++) {
                if (lowbandA != null) {
                    haar1(lowbandA, lowbandO, n >> k, 1 << k);
                }
                // Recombining is only reached with tfChange positive, which the
                // tf_select tables only produce for transient frames, where the
                // mask is at most eight bits wide. Masking the high nibble makes
                // that bound explicit rather than reading past the table as the
                // reference would if it were ever exceeded.
                fill = BIT_INTERLEAVE[fill & 0xF] | BIT_INTERLEAVE[(fill >> 4) & 0xF] << 2;
            }
            bb >>= recombine;
            nb <<= recombine;

            while ((nb & 1) == 0 && tfChange < 0) {
                if (lowbandA != null) {
                    haar1(lowbandA, lowbandO, nb, bb);
                }
                fill |= fill << bb;
                bb <<= 1;
                nb >>= 1;
                timeDivide++;
                tfChange++;
            }
            b0 = bb;
            nb0 = nb;

            if (b0 > 1 && lowbandA != null) {
                deinterleaveHadamard(lowbandA, lowbandO, nb >> recombine, b0 << recombine,
                        longBlocks);
            }
        }

        // Split when the budget runs more than 12 eighth bits past what the
        // largest codebook for this band can carry.
        if (!stereo && lm != -1 && n > 2) {
            int base = cacheBase(i, lm);
            if (b > CACHE_BITS[base + CACHE_BITS[base]] + 12) {
                n >>= 1;
                ya = xa;
                yo = xo + n;
                split = true;
                lm -= 1;
                if (bb == 1) {
                    fill = (fill & 1) | (fill << 1);
                }
                bb = (bb + 1) >> 1;
            }
        }

        if (split) {
            int itheta = 0;
            int mbits;
            int sbits;
            int delta;
            int qalloc;
            int origFill;
            int imid;
            int iside;

            int pulseCap = CeltTables.logN(i) + lm * (1 << BIT_RES);
            int offset = (pulseCap >> 1)
                    - (stereo && n == 2 ? QTHETA_OFFSET_TWOPHASE : QTHETA_OFFSET);
            int qn = computeQn(n, b, offset, pulseCap, stereo);
            if (stereo && i >= intensity) {
                // At and above the intensity band the side is not coded at all,
                // so there is no angle to send.
                qn = 1;
            }
            int tell = dec.tellFrac();
            if (qn != 1) {
                if (stereo && n > 2) {
                    // A step PDF: angles in the lower half are three times as
                    // likely as the rest, because a stereo image usually leans
                    // towards the middle.
                    int p0 = 3;
                    int x0 = qn / 2;
                    int ft = p0 * (x0 + 1) + x0;
                    int fs = dec.decode(ft);
                    int x;
                    if (fs < (x0 + 1) * p0) {
                        x = fs / p0;
                    } else {
                        x = x0 + 1 + (fs - (x0 + 1) * p0);
                    }
                    dec.update(x <= x0 ? p0 * x : (x - 1 - x0) + (x0 + 1) * p0,
                            x <= x0 ? p0 * (x + 1) : (x - x0) + (x0 + 1) * p0, ft);
                    itheta = x;
                } else if (b0 > 1 || stereo) {
                    itheta = dec.decodeUniform(qn + 1);
                } else {
                    // A triangular PDF: a mono time split most often divides its
                    // energy evenly between the two halves.
                    int ft = ((qn >> 1) + 1) * ((qn >> 1) + 1);
                    int fm = dec.decode(ft);
                    int fs;
                    int fl;
                    if (fm < ((qn >> 1) * ((qn >> 1) + 1) >> 1)) {
                        itheta = (isqrt32(8L * fm + 1) - 1) >> 1;
                        fs = itheta + 1;
                        fl = itheta * (itheta + 1) >> 1;
                    } else {
                        itheta = (2 * (qn + 1) - isqrt32(8L * (ft - fm - 1) + 1)) >> 1;
                        fs = qn + 1 - itheta;
                        fl = ft - ((qn + 1 - itheta) * (qn + 2 - itheta) >> 1);
                    }
                    dec.update(fl, fl + fs, ft);
                }
                itheta = itheta * 16384 / qn;
            } else if (stereo) {
                // No angle, so all that is left to say about the side is whether
                // it is inverted.
                if (b > 2 << BIT_RES && remainingBits > 2 << BIT_RES) {
                    inv = dec.decodeBit(2);
                }
                itheta = 0;
            }
            qalloc = dec.tellFrac() - tell;
            b -= qalloc;

            origFill = fill;
            if (itheta == 0) {
                imid = 32767;
                iside = 0;
                fill &= (1 << bb) - 1;
                delta = -16384;
            } else if (itheta == 16384) {
                imid = 0;
                iside = 32767;
                fill &= ((1 << bb) - 1) << bb;
                delta = 16384;
            } else {
                imid = bitexactCos(itheta);
                iside = bitexactCos(16384 - itheta);
                // The mid/side split that minimises squared error in this band.
                delta = fracMul16((n - 1) << 7, bitexactLog2Tan(iside, imid));
            }

            mid = (1.0f / 32768) * imid;
            side = (1.0f / 32768) * iside;

            if (n == 2 && stereo) {
                // Two coefficients per channel: mid and side are orthogonal, so
                // the whole of the side is one sign bit.
                int sign = 0;
                mbits = b;
                sbits = 0;
                if (itheta != 0 && itheta != 16384) {
                    sbits = 1 << BIT_RES;
                }
                mbits -= sbits;
                int c = itheta > 8192 ? 1 : 0;
                remainingBits -= qalloc + sbits;

                float[] x2a = c != 0 ? ya : xa;
                int x2o = c != 0 ? yo : xo;
                float[] y2a = c != 0 ? xa : ya;
                int y2o = c != 0 ? xo : yo;
                if (sbits != 0) {
                    sign = dec.decodeRawBits(1);
                }
                int signed = 1 - 2 * sign;
                // origFill, not fill: the side still wants to fold even when the
                // angle is hard over and the low bits of fill were cleared.
                cm = quantBand(i, x2a, x2o, null, 0, n, mbits, bb, tfChange,
                        lowbandA, lowbandO, lm, outA, outO, level, gain, scratchA, origFill);
                y2a[y2o] = -signed * x2a[x2o + 1];
                y2a[y2o + 1] = signed * x2a[x2o];

                xa[xo] = mid * xa[xo];
                xa[xo + 1] = mid * xa[xo + 1];
                ya[yo] = side * ya[yo];
                ya[yo + 1] = side * ya[yo + 1];
                float tmp = xa[xo];
                xa[xo] = tmp - ya[yo];
                ya[yo] = tmp + ya[yo];
                tmp = xa[xo + 1];
                xa[xo + 1] = tmp - ya[yo + 1];
                ya[yo + 1] = tmp + ya[yo + 1];
            } else {
                float[] nextLowband2A = null;
                int nextLowband2O = 0;
                float[] nextOutA = null;
                int nextOutO = 0;
                int nextLevel = 0;

                if (b0 > 1 && !stereo && (itheta & 0x3fff) != 0) {
                    if (itheta > 8192) {
                        // Most of the energy is in the second half, so the first
                        // half is pre-echo and can be starved a little.
                        delta -= delta >> (4 - lm);
                    } else {
                        // A forward-masking slope of 1.5 dB per 10 ms.
                        delta = Math.min(0, delta + (n << BIT_RES >> (5 - lm)));
                    }
                }
                mbits = Math.max(0, Math.min(b, (b - delta) / 2));
                sbits = b - mbits;
                remainingBits -= qalloc;

                if (lowbandA != null && !stereo) {
                    nextLowband2A = lowbandA;
                    nextLowband2O = lowbandO + n;
                }
                if (stereo) {
                    nextOutA = outA;
                    nextOutO = outO;
                } else {
                    nextLevel = level + 1;
                }

                // For a stereo split the two halves are the two channels, whose
                // collapse masks describe the same blocks and so are not shifted
                // relative to each other. For a mono time split they describe
                // different blocks and the second half's mask belongs B0/2 up.
                int shift = stereo ? 0 : b0 >> 1;

                int rebalance = remainingBits;
                if (mbits >= sbits) {
                    // In stereo the mid is left unscaled, because the bands above
                    // fold from the normalised mid rather than from the scaled one.
                    cm = quantBand(i, xa, xo, null, 0, n, mbits, bb, tfChange,
                            lowbandA, lowbandO, lm, nextOutA, nextOutO,
                            nextLevel, stereo ? 1.0f : gain * mid, scratchA, fill);
                    rebalance = mbits - (rebalance - remainingBits);
                    if (rebalance > 3 << BIT_RES && itheta != 0) {
                        sbits += rebalance - (3 << BIT_RES);
                    }
                    // For a stereo split the high bits of fill are always zero,
                    // so the side never folds.
                    cm |= quantBand(i, ya, yo, null, 0, n, sbits, bb, tfChange,
                            nextLowband2A, nextLowband2O, lm, null, 0,
                            nextLevel, gain * side, null, fill >> bb) << shift;
                } else {
                    cm = quantBand(i, ya, yo, null, 0, n, sbits, bb, tfChange,
                            nextLowband2A, nextLowband2O, lm, null, 0,
                            nextLevel, gain * side, null, fill >> bb) << shift;
                    rebalance = sbits - (rebalance - remainingBits);
                    if (rebalance > 3 << BIT_RES && itheta != 16384) {
                        mbits += rebalance - (3 << BIT_RES);
                    }
                    cm |= quantBand(i, xa, xo, null, 0, n, mbits, bb, tfChange,
                            lowbandA, lowbandO, lm, nextOutA, nextOutO,
                            nextLevel, stereo ? 1.0f : gain * mid, scratchA, fill);
                }
            }
        } else {
            int q = bits2pulses(i, lm, b);
            int currBits = pulses2bits(i, lm, q);
            remainingBits -= currBits;

            // Walk the pulse count down until the frame can actually pay for it.
            // The allocator works from an estimate; this loop is what guarantees
            // the decoder never reads past the end of the frame, however the
            // rebalancing above landed.
            while (remainingBits < 0 && q > 0) {
                remainingBits += currBits;
                q--;
                currBits = pulses2bits(i, lm, q);
                remainingBits -= currBits;
            }

            if (q != 0) {
                cm = algUnquant(xa, xo, n, getPulses(q), bb, gain);
            } else {
                int cmMask = (1 << bb) - 1;
                fill &= cmMask;
                if (fill == 0) {
                    Arrays.fill(xa, xo, xo + n, 0.0f);
                } else if (lowbandA == null) {
                    // Nothing below to fold from, so fill with noise. The
                    // alternative is silence, and a silent band beside a loud one
                    // is heard as a notch that opens and closes with the music.
                    for (int j = 0; j < n; j++) {
                        seed = lcgRand(seed);
                        xa[xo + j] = seed >> 20;
                    }
                    cm = cmMask;
                    renormaliseVector(xa, xo, n, gain);
                } else {
                    // Fold: copy a lower band's shape and dither it about 48 dB
                    // down, which decorrelates the copy enough that it is not
                    // heard as a pitched echo of the band below.
                    for (int j = 0; j < n; j++) {
                        seed = lcgRand(seed);
                        float tmp = 1.0f / 256;
                        tmp = (seed & 0x8000) != 0 ? tmp : -tmp;
                        xa[xo + j] = lowbandA[lowbandO + j] + tmp;
                    }
                    cm = fill;
                    renormaliseVector(xa, xo, n, gain);
                }
            }
        }

        if (stereo) {
            if (n != 2) {
                stereoMerge(xa, xo, ya, yo, mid, n);
            }
            if (inv != 0) {
                for (int j = 0; j < n; j++) {
                    ya[yo + j] = -ya[yo + j];
                }
            }
        } else if (level == 0) {
            // Undo the time reordering, then the time-frequency changes, in the
            // reverse of the order they were applied above.
            if (b0 > 1) {
                interleaveHadamard(xa, xo, nb >> recombine, b0 << recombine, longBlocks);
            }

            nb = nb0;
            bb = b0;
            for (int k = 0; k < timeDivide; k++) {
                bb >>= 1;
                nb <<= 1;
                cm |= cm >> bb;
                haar1(xa, xo, nb, bb);
            }

            for (int k = 0; k < recombine; k++) {
                if (cm > 0xF) {
                    throw new IllegalStateException("collapse mask 0x" + Integer.toHexString(cm)
                            + " is wider than the four bits a recombined band can hold");
                }
                cm = BIT_DEINTERLEAVE[cm];
                haar1(xa, xo, n0 >> k, 1 << k);
            }
            bb <<= recombine;

            if (outA != null) {
                // Stored at unit norm per coefficient rather than per band, so a
                // wide band folded onto a narrow one does not arrive quieter
                // than it should.
                float scale = (float) Math.sqrt(n0);
                for (int j = 0; j < n0; j++) {
                    outA[outO + j] = scale * xa[xo + j];
                }
            }
            cm &= (1 << bb) - 1;
        }
        return cm;
    }

    /**
     * {@code alg_unquant} in {@code celt/vq.c}: RFC 6716 section 4.3.4.2.
     *
     * <p>Reads the codebook index, expands it to a pulse vector, scales it to the
     * requested norm and undoes the spreading rotation.
     */
    private int algUnquant(float[] xa, int xo, int n, int k, int bb, float gain) {
        if (k <= 0) {
            throw new IllegalStateException("alg_unquant needs at least one pulse, got " + k);
        }
        if (n < 2) {
            throw new IllegalStateException("alg_unquant needs at least two dimensions, got " + n);
        }
        long total = Pvq.vectorCount(n, k);
        long index = dec.decodeUniformWide(total);
        Pvq.decodePulses(pulseVector, n, k, index);

        float ryy = 0;
        for (int i = 0; i < n; i++) {
            ryy += (float) pulseVector[i] * pulseVector[i];
        }
        // normalise_residual: the pulse vector has norm sqrt(Ryy) and the band
        // is meant to come out with norm gain.
        float g = (1.0f / (float) Math.sqrt(ryy)) * gain;
        for (int i = 0; i < n; i++) {
            xa[xo + i] = g * pulseVector[i];
        }
        expRotation(xa, xo, n, -1, bb, k);
        return extractCollapseMask(pulseVector, n, bb);
    }

    /**
     * {@code extract_collapse_mask} in {@code celt/vq.c}.
     *
     * <p>One bit per short block, set when that block got any pulse at all. Both
     * the anti-collapse pass and the folding above read it.
     */
    private static int extractCollapseMask(int[] iy, int n, int bb) {
        if (bb <= 1) {
            return 1;
        }
        int n0 = n / bb;
        int mask = 0;
        for (int i = 0; i < bb; i++) {
            for (int j = 0; j < n0; j++) {
                mask |= (iy[i * n0 + j] != 0 ? 1 : 0) << i;
            }
        }
        return mask;
    }

    /**
     * {@code exp_rotation} in {@code celt/vq.c}: RFC 6716 section 4.3.4.3.
     *
     * <p>A pair of Givens rotations that smear each pulse over its neighbours.
     * Without it a band coded with two pulses across forty coefficients is two
     * isolated spikes, heard as a metallic ringing; the rotation turns the same
     * two pulses into something with a plausible spectral shape.
     */
    private void expRotation(float[] xa, int xo, int len, int dir, int stride, int k) {
        if (2 * k >= len || spread == SPREAD_NONE) {
            return;
        }
        int factor = SPREAD_FACTOR[spread - 1];
        float gain = (float) len / (len + factor * k);
        float theta = 0.5f * (gain * gain);
        float c = celtCosNorm(theta);
        float s = celtCosNorm(1.0f - theta);

        int stride2 = 0;
        if (len >= 8 * stride) {
            stride2 = 1;
            // sqrt(len/stride) with rounding, incrementing while
            // (stride2+0.5)^2 < len/stride.
            while ((stride2 * stride2 + stride2) * stride + (stride >> 2) < len) {
                stride2++;
            }
        }
        int sub = len / stride;
        for (int i = 0; i < stride; i++) {
            int at = xo + i * sub;
            if (dir < 0) {
                if (stride2 != 0) {
                    expRotation1(xa, at, sub, stride2, s, c);
                }
                expRotation1(xa, at, sub, 1, c, s);
            } else {
                expRotation1(xa, at, sub, 1, c, -s);
                if (stride2 != 0) {
                    expRotation1(xa, at, sub, stride2, s, -c);
                }
            }
        }
    }

    /** {@code exp_rotation1} in {@code celt/vq.c}: one pass of the rotation. */
    private static void expRotation1(float[] xa, int xo, int len, int stride, float c, float s) {
        int p = xo;
        for (int i = 0; i < len - stride; i++) {
            float x1 = xa[p];
            float x2 = xa[p + stride];
            xa[p + stride] = c * x2 + s * x1;
            xa[p] = c * x1 - s * x2;
            p++;
        }
        p = xo + len - 2 * stride - 1;
        for (int i = len - 2 * stride - 1; i >= 0; i--) {
            float x1 = xa[p];
            float x2 = xa[p + stride];
            xa[p + stride] = c * x2 + s * x1;
            xa[p] = c * x1 - s * x2;
            p--;
        }
    }

    /**
     * {@code celt_cos_norm} in {@code celt/mathops.h}: cos of a quarter turn per unit.
     *
     * <p>{@code StrictMath}, not {@code Math}: this sets the rotation the whole
     * band is decoded through, and a one-ulp difference between JVMs would make
     * the same packet decode to different samples on different machines.
     */
    private static float celtCosNorm(float x) {
        return (float) StrictMath.cos(HALF_PI * x);
    }

    /** {@code renormalise_vector} in {@code celt/vq.c}: scale to the given norm. */
    private static void renormaliseVector(float[] xa, int xo, int n, float gain) {
        float e = EPSILON;
        for (int i = 0; i < n; i++) {
            e += xa[xo + i] * xa[xo + i];
        }
        float g = (1.0f / (float) Math.sqrt(e)) * gain;
        for (int i = 0; i < n; i++) {
            xa[xo + i] = g * xa[xo + i];
        }
    }

    /** {@code stereo_merge} in {@code celt/bands.c}: mid and side back to left and right. */
    private static void stereoMerge(float[] xa, int xo, float[] ya, int yo, float mid, int n) {
        float xp = 0;
        float side = 0;
        for (int j = 0; j < n; j++) {
            xp += xa[xo + j] * ya[yo + j];
            side += ya[yo + j] * ya[yo + j];
        }
        // Compensate for the fact that the mid was left unnormalised.
        xp = mid * xp;
        float el = mid * mid + side - 2 * xp;
        float er = mid * mid + side + 2 * xp;
        if (er < 6e-4f || el < 6e-4f) {
            // The two channels are all but exactly out of phase, so one of the
            // gains would be in the thousands. Duplicating the mid is what the
            // reference does: it costs the stereo image of one band rather than
            // producing a burst of noise in it.
            System.arraycopy(xa, xo, ya, yo, n);
            return;
        }
        float lgain = 1.0f / (float) Math.sqrt(el);
        float rgain = 1.0f / (float) Math.sqrt(er);
        for (int j = 0; j < n; j++) {
            float l = mid * xa[xo + j];
            float r = ya[yo + j];
            xa[xo + j] = lgain * (l - r);
            ya[yo + j] = rgain * (l + r);
        }
    }

    /**
     * {@code haar1} in {@code celt/bands.c}: one stage of a Haar transform.
     *
     * <p>Trades time resolution for frequency resolution or the other way about,
     * which is how one band can be coded at a different transform size from its
     * neighbours.
     */
    static void haar1(float[] xa, int xo, int n0, int stride) {
        int half = n0 >> 1;
        for (int i = 0; i < stride; i++) {
            for (int j = 0; j < half; j++) {
                int lo = xo + stride * 2 * j + i;
                int hi = xo + stride * (2 * j + 1) + i;
                float tmp1 = SQRT_HALF * xa[lo];
                float tmp2 = SQRT_HALF * xa[hi];
                xa[lo] = tmp1 + tmp2;
                xa[hi] = tmp1 - tmp2;
            }
        }
    }

    /** {@code deinterleave_hadamard} in {@code celt/bands.c}: frequency order to time order. */
    private void deinterleaveHadamard(float[] xa, int xo, int n0, int stride, boolean hadamard) {
        if (stride <= 0) {
            throw new IllegalStateException("Hadamard stride must be positive, got " + stride);
        }
        int n = n0 * stride;
        if (hadamard) {
            int base = orderyBase(stride);
            for (int i = 0; i < stride; i++) {
                for (int j = 0; j < n0; j++) {
                    shuffle[ORDERY[base + i] * n0 + j] = xa[xo + j * stride + i];
                }
            }
        } else {
            for (int i = 0; i < stride; i++) {
                for (int j = 0; j < n0; j++) {
                    shuffle[i * n0 + j] = xa[xo + j * stride + i];
                }
            }
        }
        System.arraycopy(shuffle, 0, xa, xo, n);
    }

    /** {@code interleave_hadamard} in {@code celt/bands.c}: time order back to frequency order. */
    private void interleaveHadamard(float[] xa, int xo, int n0, int stride, boolean hadamard) {
        if (stride <= 0) {
            throw new IllegalStateException("Hadamard stride must be positive, got " + stride);
        }
        int n = n0 * stride;
        if (hadamard) {
            int base = orderyBase(stride);
            for (int i = 0; i < stride; i++) {
                for (int j = 0; j < n0; j++) {
                    shuffle[j * stride + i] = xa[xo + ORDERY[base + i] * n0 + j];
                }
            }
        } else {
            for (int i = 0; i < stride; i++) {
                for (int j = 0; j < n0; j++) {
                    shuffle[j * stride + i] = xa[xo + i * n0 + j];
                }
            }
        }
        System.arraycopy(shuffle, 0, xa, xo, n);
    }

    /**
     * The Hadamard reordering the encoder's own copy of
     * {@code deinterleave_hadamard} needs.
     *
     * <p>Reached through a method rather than by handing out {@link #ORDERY}
     * itself, because a package-private array is a package-private array that
     * something will eventually write to, and one changed entry silently
     * reorders the short blocks of every transient band.
     */
    static int orderyIndex(int stride, int i) {
        return ORDERY[orderyBase(stride) + i];
    }

    private static int orderyBase(int stride) {
        if (stride < 2 || stride > 16 || Integer.bitCount(stride) != 1) {
            throw new IllegalStateException("the Hadamard order table covers strides 2, 4, 8 and"
                    + " 16, not " + stride);
        }
        return stride - 2;
    }

    /** {@code compute_qn} in {@code celt/bands.c}: how finely to code the split angle. */
    static int computeQn(int n, int b, int offset, int pulseCap, boolean stereo) {
        int n2 = 2 * n - 1;
        if (stereo && n == 2) {
            n2--;
        }
        // The upper limit guarantees that a stereo split with the angle hard
        // over still leaves enough bits for one pulse in the side, which stops
        // the side collapsing to nothing.
        int qb = Math.min(b - pulseCap - (4 << BIT_RES), (b + n2 * offset) / n2);
        qb = Math.min(8 << BIT_RES, qb);
        if (qb < (1 << BIT_RES >> 1)) {
            return 1;
        }
        int qn = EXP2_TABLE8[qb & 0x7] >> (14 - (qb >> BIT_RES));
        qn = (qn + 1) >> 1 << 1;
        if (qn > 256) {
            throw new IllegalStateException("split angle resolution " + qn + " exceeds 256");
        }
        return qn;
    }

    /**
     * {@code bitexact_cos} in {@code celt/bands.c}.
     *
     * <p>Integer, and it has to be. This feeds the mid/side bit split, so an
     * encoder and a decoder computing it on different floating-point hardware
     * would divide a band's budget differently and then read a different number
     * of symbols out of the frame.
     */
    static int bitexactCos(int x) {
        int tmp = (4096 + x * x) >> 13;
        if (tmp > 32767) {
            throw new IllegalStateException("bitexact_cos overflowed at x = " + x);
        }
        int x2 = (short) tmp;
        x2 = (32767 - x2) + fracMul16(x2, -7651 + fracMul16(x2, 8277 + fracMul16(-626, x2)));
        return 1 + x2;
    }

    /** {@code bitexact_log2tan} in {@code celt/bands.c}: log2(sin/cos) in Q11. */
    static int bitexactLog2Tan(int isin, int icos) {
        int lc = EntropyCode.ilog(icos & 0xFFFFFFFFL);
        int ls = EntropyCode.ilog(isin & 0xFFFFFFFFL);
        int c = icos << (15 - lc);
        int s = isin << (15 - ls);
        return (ls - lc) * (1 << 11)
                + fracMul16(s, fracMul16(s, -2597) + 7932)
                - fracMul16(c, fracMul16(c, -2597) + 7932);
    }

    /** {@code FRAC_MUL16} in {@code celt/mathops.h}: a rounded Q15 product of two int16s. */
    static int fracMul16(int a, int b) {
        return (16384 + (short) a * (short) b) >> 15;
    }

    /**
     * {@code isqrt32} in {@code celt/mathops.c}: integer square root.
     *
     * <p>The triangular angle PDF inverts a triangular number, and it must invert
     * it exactly: a value one out picks the neighbouring angle, and the band's
     * two halves are then scaled wrongly relative to each other.
     */
    static int isqrt32(long value) {
        if (value < 0 || value > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("isqrt32 takes an unsigned 32-bit value, not "
                    + value);
        }
        long remainder = value;
        int g = 0;
        int bshift = (EntropyCode.ilog(remainder) - 1) >> 1;
        long b = 1L << bshift;
        do {
            long t = (((long) g << 1) + b) << bshift;
            if (t <= remainder) {
                g += b;
                remainder -= t;
            }
            b >>= 1;
            bshift--;
        } while (bshift >= 0);
        return g;
    }

    /**
     * {@code anti_collapse} in {@code celt/bands.c}: RFC 6716 section 4.3.5.
     *
     * <p>A transient frame is up to eight short blocks, and the allocator may
     * leave some of them with no pulses at all. Injecting noise into an empty
     * block is what stops a cymbal crash being chopped into eight pieces with
     * silence between them, which is heard as a flutter or a buzz on the attack.
     *
     * @param spectrum      the normalised shapes, rewritten in place
     * @param collapseMasks one byte per band per channel from {@link #decode}
     * @param lm            the frame size index
     * @param channels      1 or 2
     * @param frameSize     coefficients per channel
     * @param start         first coded band
     * @param end           one past the last coded band
     * @param logE          this frame's band energies
     * @param prev1LogE     the previous frame's
     * @param prev2LogE     the frame before that
     * @param pulses        the allocator's per-band budget in eighth bits
     * @param startSeed     the folding seed to start from
     * @return the seed after the last draw
     */
    int antiCollapse(float[] spectrum, byte[] collapseMasks, int lm, int channels,
            int frameSize, int start, int end, float[] logE, float[] prev1LogE,
            float[] prev2LogE, int[] pulses, int startSeed) {
        int rng = startSeed;
        for (int i = start; i < end; i++) {
            int n0 = CeltTables.eband(i + 1) - CeltTables.eband(i);
            // Depth in eighth bits per coefficient. A band coded finely has
            // little room for a collapse to matter, so it gets quieter noise.
            int depth = (1 + pulses[i]) / (n0 << lm);
            float thresh = 0.5f * CeltTables.exp2(-0.125f * depth);
            float sqrt1 = 1.0f / (float) Math.sqrt(n0 << lm);

            for (int c = 0; c < channels; c++) {
                float prev1 = prev1LogE[c * bands + i];
                float prev2 = prev2LogE[c * bands + i];
                if (channels == 1) {
                    // A mono frame inside a stereo decoder still carries the
                    // other channel's history, and the louder of the two is the
                    // conservative choice.
                    prev1 = Math.max(prev1, prev1LogE[bands + i]);
                    prev2 = Math.max(prev2, prev2LogE[bands + i]);
                }
                float ediff = Math.max(0, logE[c * bands + i] - Math.min(prev1, prev2));

                // Twice, because a short block holds a fraction of the frame's
                // energy, and once more by sqrt(2) at 20 ms where there are
                // eight short blocks rather than four.
                float r = 2.0f * CeltTables.exp2(-ediff);
                if (lm == 3) {
                    r *= 1.41421356f;
                }
                r = Math.min(thresh, r);
                r = r * sqrt1;

                int base = c * frameSize + (CeltTables.eband(i) << lm);
                boolean renormalize = false;
                for (int k = 0; k < 1 << lm; k++) {
                    if ((collapseMasks[i * channels + c] & (1 << k)) == 0) {
                        for (int j = 0; j < n0; j++) {
                            rng = lcgRand(rng);
                            spectrum[base + (j << lm) + k] = (rng & 0x8000) != 0 ? r : -r;
                        }
                        renormalize = true;
                    }
                }
                if (renormalize) {
                    // Energy was just added, so the band is no longer unit norm.
                    renormaliseVector(spectrum, base, n0 << lm, 1.0f);
                }
            }
        }
        return rng;
    }

    /**
     * {@code denormalise_bands} in {@code celt/bands.c}: RFC 6716 section 4.3.6.
     *
     * <p>Multiplies each band's unit-norm shape by the amplitude the energy
     * envelope decoded for it, and zeroes everything above the last band.
     *
     * @param mode     the mode for this frame size
     * @param spectrum the normalised shapes
     * @param freq     receives the denormalised spectrum; may be the same array
     * @param bandE    band amplitudes, laid out {@code channel*bandCount + band}
     * @param end      one past the last band to denormalise
     * @param channels 1 or 2
     */
    void denormalise(CeltMode mode, float[] spectrum, float[] freq, float[] bandE,
            int end, int channels) {
        int n = mode.frameSize();
        for (int c = 0; c < channels; c++) {
            int out = c * n;
            for (int i = 0; i < end; i++) {
                float g = bandE[i + c * bands];
                int to = mode.bandEnd(i);
                for (int j = mode.bandStart(i); j < to; j++) {
                    freq[out + j] = spectrum[out + j] * g;
                }
            }
            int from = end > 0 ? mode.bandEnd(end - 1) : 0;
            Arrays.fill(freq, out + from, out + n, 0.0f);
        }
    }

    @Override
    public String toString() {
        return "CeltBands[" + bands + " bands, seed=" + Integer.toUnsignedString(seed) + "]";
    }
}
