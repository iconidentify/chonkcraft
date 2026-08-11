package net.chonkbase.assetpack.codec.opus;

import java.util.Arrays;
import java.util.Objects;

/**
 * How many bits every CELT band gets, in eighths of a bit.
 *
 * <p>A port of {@code compute_allocation} and {@code interp_bits2pulses} in
 * {@code celt/rate.c}, specified by RFC 6716 section 4.3.3. The caller supplies
 * the pieces the frame decoder has already read -- the band boosts, the
 * allocation ceilings, the trim, and how many eighth-bits are left in the packet
 * -- and this hands back a shape budget, a fine-energy budget and a priority for
 * every coded band, plus the intensity stereo and dual stereo decisions, which
 * are decoded from the bitstream in the middle of the process.
 *
 * <p>RFC 6716 section 4.3.3 opens by saying why this has to be exact: "Because
 * the bit allocation drives the decoding of the range-coder stream, it MUST be
 * recovered exactly so that identical coding decisions are made in the encoder
 * and decoder." A wrong answer here does not throw and does not desynchronise a
 * symbol at the point it happens. It hands the shape decoder a different number
 * of pulses for one band, so the decoder reads a codeword of the wrong size,
 * and from there every remaining symbol in the frame is read from the wrong
 * place. What a listener hears is not a click but a whole frame of noise in
 * roughly the right spectral shape, and the only mechanical way to catch it is
 * the encoder's final range-coder state carried alongside each test vector.
 *
 * <p>The allocation is interpolated, not signalled. The static table of RFC 6716
 * Table 57 gives eleven quality rows; the process bisects to the highest row
 * that fits the budget, then bisects again in sixty-fourths between that row and
 * the next to squeeze the budget, then walks down from the top band deciding
 * which bands to drop, reading one bit per candidate band from the stream as it
 * goes. Only that last part touches the bitstream, which is why the whole thing
 * has to be reproduced rather than read.
 *
 * <h2>Deviations from the reference signature</h2>
 *
 * <p>Three, all deliberate.
 *
 * <p>There is no {@code LM} parameter. {@link CeltMode} is per frame size, so it
 * already knows LM, and a mode and an LM that disagreed would produce an
 * allocation for a frame length that is not the one being decoded.
 *
 * <p>There is no {@code signalBandwidth} parameter. Later libopus releases added
 * one to bias the encoder's band-skipping decision; RFC 6716 Appendix A's
 * {@code celt/rate.c} -- which is the normative source, per RFC 6716 section 1
 * -- has no such argument, and it would affect only the encoder's non-mandatory
 * skip choice, never how a decoder reads a stream. Carrying a parameter nothing
 * reads is how a port acquires the illusion of completeness.
 *
 * <p>The results are fields of a reusable instance rather than a freshly
 * allocated record, because this runs once per frame and the decode path must
 * not allocate. {@link #snapshot()} makes the record when a test or a tool wants
 * one.
 *
 * <h2>What the caller must have done first</h2>
 *
 * <p>{@code total} is the budget in eighth bits, computed by the frame decoder
 * around line 2512 of {@code celt/celt.c} as
 * {@code (len*8 << BITRES) - ec_tell_frac(dec) - 1}, less the anti-collapse
 * reservation of one bit when the frame is a transient with LM at least 2 and
 * the budget can afford it. The trailing {@code - 1} is not a rounding slop: RFC
 * 6716 section 4.3.3 says it is subtracted "to ensure that the resulting
 * allocation will be conservative", and dropping it lets the last band ask for
 * an eighth of a bit the packet does not contain.
 *
 * <p>{@code cap} comes from {@link CeltMode#computeCaps(int, int[])} and
 * {@code offsets} from the band-boost symbols, both of which the frame decoder
 * reads before calling here.
 */
public final class CeltAllocation {

    /**
     * Allocation units per bit, so every budget here is in eighths.
     *
     * <p>{@code BITRES} in {@code celt/entcode.h}; RFC 6716 section 4.3.3 calls
     * the unit an "8th bit".
     */
    public static final int BIT_RES = CeltMode.BIT_RES;

    /**
     * Bisection steps between two rows of the static allocation table.
     *
     * <p>{@code ALLOC_STEPS} in {@code celt/rate.c}. Six steps put the
     * interpolation point on a multiple of 1/64, which is the "steps of 1/64"
     * RFC 6716 section 4.3.3 names.
     */
    public static final int ALLOC_STEPS = CeltMode.ALLOC_STEPS;

    /**
     * The most fine-energy bits per channel any band may be given.
     *
     * <p>{@code MAX_FINE_BITS} in {@code celt/rate.h}. Beyond eight bits the
     * fine energy is more precise than the PVQ shape it multiplies, so the bits
     * would buy nothing audible.
     */
    public static final int MAX_FINE_BITS = 8;

    /**
     * The bias, in eighth bits, that shifts budget from fine energy to shape.
     *
     * <p>{@code FINE_OFFSET} in {@code celt/rate.h}.
     */
    public static final int FINE_OFFSET = 21;

    private final int[] bits1 = new int[CeltMode.BAND_COUNT];
    private final int[] bits2 = new int[CeltMode.BAND_COUNT];
    private final int[] thresh = new int[CeltMode.BAND_COUNT];
    private final int[] trimOffset = new int[CeltMode.BAND_COUNT];

    private final int[] pulses = new int[CeltMode.BAND_COUNT];
    private final int[] fineBits = new int[CeltMode.BAND_COUNT];
    private final int[] finePriority = new int[CeltMode.BAND_COUNT];

    private int codedBands;
    private int balance;
    private int intensity;
    private int dualStereo;

    /** A workspace big enough for any CELT frame. Build one per decoder and reuse it. */
    public CeltAllocation() {
    }

    /**
     * One frame's allocation, copied out of the workspace.
     *
     * <p>Copies the three arrays, so this is for tests and tools; the decode
     * path wants {@link #pulses(int)}, {@link #fineBits(int)} and
     * {@link #finePriority(int)}, which read the same numbers without
     * allocating.
     *
     * @param pulses shape budget per band in eighth bits, the {@code pulses}
     *     output of {@code compute_allocation}
     * @param fineBits fine-energy bits per band per channel, {@code ebits}
     * @param finePriority 1 where a band should be topped up first in the final
     *     fine-energy pass, {@code fine_priority}
     * @param codedBands one past the highest band that carries shape, after
     *     band skipping
     * @param balance eighth bits left over above the caps, which
     *     {@code quant_all_bands} redistributes
     * @param intensity the first band coded as intensity stereo, or 0 in mono
     * @param dualStereo 1 when the frame codes the two channels separately
     */
    public record Allocation(int[] pulses, int[] fineBits, int[] finePriority,
            int codedBands, int balance, int intensity, int dualStereo) {

        /** Defensive copies, because the source is a workspace the next frame overwrites. */
        public Allocation {
            pulses = pulses.clone();
            fineBits = fineBits.clone();
            finePriority = finePriority.clone();
        }

        /**
         * Element-wise, unlike the equals a record would generate.
         *
         * <p>The generated one compares the three arrays by identity, so two
         * identical allocations would test unequal and a test written against it
         * would pass for the wrong reason.
         */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Allocation that)) {
                return false;
            }
            return codedBands == that.codedBands && balance == that.balance
                    && intensity == that.intensity && dualStereo == that.dualStereo
                    && Arrays.equals(pulses, that.pulses)
                    && Arrays.equals(fineBits, that.fineBits)
                    && Arrays.equals(finePriority, that.finePriority);
        }

        @Override
        public int hashCode() {
            int hash = Arrays.hashCode(pulses);
            hash = 31 * hash + Arrays.hashCode(fineBits);
            hash = 31 * hash + Arrays.hashCode(finePriority);
            hash = 31 * hash + codedBands;
            hash = 31 * hash + balance;
            hash = 31 * hash + intensity;
            return 31 * hash + dualStereo;
        }

        @Override
        public String toString() {
            return "Allocation[codedBands=" + codedBands + ", balance=" + balance
                    + ", intensity=" + intensity + ", dualStereo=" + dualStereo
                    + ", pulses=" + Arrays.toString(pulses)
                    + ", fineBits=" + Arrays.toString(fineBits)
                    + ", finePriority=" + Arrays.toString(finePriority) + "]";
        }
    }

    /**
     * Works out the allocation for a frame being decoded, reading the skip,
     * intensity and dual stereo symbols from the stream.
     *
     * <p>{@code compute_allocation(..., ec, 0, 0)} as the frame decoder calls it
     * around line 2515 of {@code celt/celt.c}.
     *
     * <p>Allocates nothing. The results stay valid until the next call.
     *
     * @param mode the frame size, which fixes the band layout and LM
     * @param start first coded band: 0, or 17 for a Hybrid frame
     * @param end one past the last coded band, from the signalled bandwidth
     * @param offsets per-band boost in eighth bits from the band-boost symbols,
     *     indexed by band and non-negative
     * @param cap per-band allocation ceilings in eighth bits, from
     *     {@link CeltMode#computeCaps(int, int[])}
     * @param allocTrim the decoded allocation trim, 0 to 10, 5 meaning no tilt
     * @param total eighth bits available, before the skip and stereo
     *     reservations this makes itself; negative is treated as zero
     * @param channels 1 or 2
     * @param dec the frame's range decoder, positioned after the trim symbol
     * @return the number of coded bands, one past the highest band with shape
     * @throws IllegalArgumentException if the band range, channel count, trim or
     *     supplied arrays do not describe a frame this layer can allocate for
     */
    public int decode(CeltMode mode, int start, int end, int[] offsets, int[] cap,
            int allocTrim, int total, int channels, RangeDecoder dec) {
        Objects.requireNonNull(dec, "dec");
        checkArguments(mode, start, end, offsets, cap, allocTrim, channels);
        intensity = 0;
        dualStereo = 0;
        return compute(mode, start, end, offsets, cap, allocTrim, total, channels, dec, null, 0);
    }

    /**
     * Works out the allocation for a frame being encoded, writing the skip,
     * intensity and dual stereo symbols to the stream.
     *
     * <p>{@code compute_allocation(..., enc, 1, prev)} as the frame encoder
     * calls it around line 1505 of {@code celt/celt.c}.
     *
     * <p>The band-skipping decision is the one part of this that is not a
     * mandatory part of the bitstream, as the comment in
     * {@code interp_bits2pulses} says: whatever this chooses is signalled
     * explicitly, so a decoder never has to reproduce the choice, only read it.
     * Everything else here is identical on both sides.
     *
     * @param intensity the encoder's chosen first intensity stereo band, which
     *     is clamped to the number of coded bands and then written out; ignored
     *     in mono
     * @param dualStereo 1 to code the channels separately; ignored in mono
     * @param prev the number of bands coded in the previous frame, which biases
     *     the skip threshold so bands do not flicker in and out
     * @return the number of coded bands
     */
    public int encode(CeltMode mode, int start, int end, int[] offsets, int[] cap,
            int allocTrim, int total, int channels, int intensity, int dualStereo,
            RangeEncoder enc, int prev) {
        Objects.requireNonNull(enc, "enc");
        checkArguments(mode, start, end, offsets, cap, allocTrim, channels);
        if (intensity < start || intensity > end) {
            throw new IllegalArgumentException("intensity band " + intensity
                    + " is outside the coded range " + start + ".." + end);
        }
        if (dualStereo != 0 && dualStereo != 1) {
            throw new IllegalArgumentException("dual stereo must be 0 or 1, got " + dualStereo);
        }
        this.intensity = intensity;
        this.dualStereo = dualStereo;
        return compute(mode, start, end, offsets, cap, allocTrim, total, channels, null, enc, prev);
    }

    /** One past the highest band that carries shape, after band skipping. */
    public int codedBands() {
        return codedBands;
    }

    /**
     * Eighth bits left unspent because bands hit their caps.
     *
     * <p>The {@code balance} that {@code quant_all_bands} carries into the first
     * band it decodes. Usually zero except at very high rates, as RFC 6716
     * section 4.3.3 says.
     */
    public int balance() {
        return balance;
    }

    /**
     * The lowest band coded as intensity stereo, or 0 when the frame is mono or
     * the parameter was not worth reserving space for.
     */
    public int intensity() {
        return intensity;
    }

    /** 1 when the frame codes its two channels separately rather than jointly. */
    public int dualStereo() {
        return dualStereo;
    }

    /**
     * A band's shape budget in eighth bits, the {@code pulses} output.
     *
     * <p>Bands outside the coded range read zero.
     */
    public int pulses(int band) {
        return pulses[checkBand(band)];
    }

    /** A band's fine-energy budget, in whole bits per channel. */
    public int fineBits(int band) {
        return fineBits[checkBand(band)];
    }

    /**
     * 1 when a band should be topped up first in the final fine-energy pass.
     *
     * <p>Set on the bands whose fine allocation was rounded down or capped, so
     * that any bits left at the end of the frame go where they were lost.
     */
    public int finePriority(int band) {
        return finePriority[checkBand(band)];
    }

    /** The whole result, with the arrays copied. For tests and tools. */
    public Allocation snapshot() {
        return new Allocation(pulses, fineBits, finePriority,
                codedBands, balance, intensity, dualStereo);
    }

    /**
     * {@code compute_allocation} in {@code celt/rate.c}: reserve, tilt, bisect
     * the static table, then hand over to {@link #interpolate}.
     */
    private int compute(CeltMode mode, int start, int end, int[] offsets, int[] cap,
            int allocTrim, int total, int channels, RangeDecoder dec, RangeEncoder enc, int prev) {
        int lm = mode.lm();
        int allocFloor = channels << BIT_RES;

        // Bands the caller did not ask for are zeroed rather than left holding
        // the previous frame's numbers. The reference leaves them undefined and
        // its callers never look; zeroing costs nothing here and means a
        // snapshot, or a caller that reads one band too far, cannot depend on
        // what the last frame happened to be.
        Arrays.fill(pulses, 0);
        Arrays.fill(fineBits, 0);
        Arrays.fill(finePriority, 0);

        total = Math.max(total, 0);
        int skipStart = start;
        // One bit to signal the end of manual band skipping. It is handed back
        // in interpolate() if the skip loop never uses it.
        int skipRsv = total >= 1 << BIT_RES ? 1 << BIT_RES : 0;
        total -= skipRsv;
        int intensityRsv = 0;
        int dualStereoRsv = 0;
        if (channels == 2) {
            intensityRsv = CeltMode.intensityReservation(end - start);
            if (intensityRsv > total) {
                intensityRsv = 0;
            } else {
                total -= intensityRsv;
                dualStereoRsv = total >= 1 << BIT_RES ? 1 << BIT_RES : 0;
                total -= dualStereoRsv;
            }
        }

        for (int j = start; j < end; j++) {
            int n = mode.bandWidth(j);
            // The 2.5 ms width, m->eBands[j+1]-m->eBands[j] upstream. The
            // reference stores the band edges unshifted and applies the
            // frame-size shift in each expression that wants it, so an
            // expression that wants the unshifted width -- this next one, and
            // the leftover-bit arithmetic in interpolate() -- has to say so.
            int n0 = n >> lm;
            // Below this a band gets no shape bits at all, because a spectrum
            // that sparse sounds worse than an empty band. RFC 6716 section
            // 4.3.3 states it as "24 times the number of MDCT bins in the band
            // and divide by 16", or one bit per channel if that is greater,
            // which is exactly this. The sentence after it glosses the same
            // number as "48 128th bits per MDCT bin" and that gloss is out by a
            // factor of two -- 24/16 of an eighth bit per bin is 24 128ths, not
            // 48 -- so the formula, which is also what Appendix A's
            // celt/rate.c computes, is the one to follow.
            thresh[j] = Math.max(allocFloor, (3 * n << BIT_RES) >> 4);
            // The tilt, and it runs the other way from the way RFC 6716
            // section 4.3.3 describes it in passing.
            //
            // That section says "Values lower than 5 bias the allocation
            // towards lower frequencies and values above 5 bias it towards
            // higher frequencies". The arithmetic is the reverse, and the same
            // section states the arithmetic correctly a few paragraphs later:
            // the multiplier is (alloc_trim - 5 - LM) times "the number of
            // remaining bands", which is end-j-1. That factor is largest at the
            // bottom of the spectrum and exactly zero at the top, so a trim
            // above 5+LM feeds the low bands and, because the budget is fixed,
            // starves the high ones. Appendix A's celt/rate.c is the same
            // expression, so Appendix A and the detailed prose agree and it is
            // the summary sentence that is wrong.
            //
            // The encoder settles it. alloc_trim_analysis in celt/celt.c
            // measures the spectral tilt and does trim_index-- when it finds
            // energy concentrated at the top of the spectrum -- that is, it
            // asks for a LOW trim to buy treble. Implementing the summary
            // sentence instead would put the bits at the opposite end of the
            // spectrum from the ones the encoder was trying to protect, and a
            // bright recording would come back dull with a hissy bass.
            //
            // Note also that the neutral point is 5+LM, not 5. Only at the
            // 2.5 ms frame size does a trim of 5 mean no tilt at all.
            trimOffset[j] = channels * n0 * (allocTrim - 5 - lm) * (end - j - 1)
                    * (1 << (lm + BIT_RES)) >> 6;
            // A band one bin wide gets a bit less, because one coarse energy
            // value per bin already describes it well.
            if (n == 1) {
                trimOffset[j] -= allocFloor;
            }
        }

        // Bisect the static allocation table for the highest quality row whose
        // total fits. Row 0 is all zeros and is never probed, only used as the
        // low end of the interpolation when even row 1 does not fit.
        int lo = 1;
        int hi = mode.allocationRows() - 1;
        do {
            boolean done = false;
            int psum = 0;
            int mid = (lo + hi) >> 1;
            for (int j = end; j-- > start;) {
                int bitsj = mode.staticBits(mid, j, channels);
                if (bitsj > 0) {
                    bitsj = Math.max(0, bitsj + trimOffset[j]);
                }
                bitsj += offsets[j];
                if (bitsj >= thresh[j] || done) {
                    done = true;
                    // Never count more than the band could actually spend.
                    psum += Math.min(bitsj, cap[j]);
                } else if (bitsj >= allocFloor) {
                    psum += allocFloor;
                }
            }
            if (psum > total) {
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        } while (lo <= hi);
        hi = lo--;

        for (int j = start; j < end; j++) {
            int bits1j = mode.staticBits(lo, j, channels);
            // Past the top row the interpolation runs up to the cap, which is
            // the most a band can spend however many bits are going spare.
            int bits2j = hi >= mode.allocationRows() ? cap[j] : mode.staticBits(hi, j, channels);
            if (bits1j > 0) {
                bits1j = Math.max(0, bits1j + trimOffset[j]);
            }
            if (bits2j > 0) {
                bits2j = Math.max(0, bits2j + trimOffset[j]);
            }
            // Row 0 carries no boosts. It is the empty allocation, reached only
            // when not even row 1 fits the budget, and bits1 is the floor the
            // interpolation can never go below; a boost added there would be
            // handed out whatever the frame could afford.
            if (lo > 0) {
                bits1j += offsets[j];
            }
            bits2j += offsets[j];
            // A boosted band is never skipped. Skipping it would spend a bit
            // saying the bits just concentrated here should go elsewhere.
            if (offsets[j] > 0) {
                skipStart = j;
            }
            bits1[j] = bits1j;
            bits2[j] = Math.max(0, bits2j - bits1j);
        }

        codedBands = interpolate(mode, start, end, skipStart, cap, total, skipRsv,
                intensityRsv, dualStereoRsv, channels, dec, enc, prev);
        return codedBands;
    }

    /**
     * {@code interp_bits2pulses} in {@code celt/rate.c}: interpolate between the
     * two table rows, skip the bands not worth coding, then split each band's
     * budget between fine energy and shape.
     */
    private int interpolate(CeltMode mode, int start, int end, int skipStart, int[] cap,
            int total, int skipRsv, int intensityRsv, int dualStereoRsv, int channels,
            RangeDecoder dec, RangeEncoder enc, int prev) {
        int lm = mode.lm();
        int allocFloor = channels << BIT_RES;
        int stereo = channels > 1 ? 1 : 0;

        // Six halvings of [0,64], so the interpolation point ends up a multiple
        // of 1/64 of the way between the two rows. The loop keeps lo as the
        // largest point that fits: hi is only ever moved down to a point that
        // busted, and lo is never moved to one that did.
        int lo = 0;
        int hi = 1 << ALLOC_STEPS;
        for (int step = 0; step < ALLOC_STEPS; step++) {
            int mid = (lo + hi) >> 1;
            int psum = 0;
            boolean done = false;
            for (int j = end; j-- > start;) {
                int tmp = bits1[j] + (mid * bits2[j] >> ALLOC_STEPS);
                if (tmp >= thresh[j] || done) {
                    // The walk is from the top band down, and once one band is
                    // over its threshold every band below it counts its whole
                    // allocation even if it is under its own threshold. A band
                    // priced at the floor underneath a fully coded one would
                    // leave a hole in the middle of the spectrum.
                    done = true;
                    psum += Math.min(tmp, cap[j]);
                } else if (tmp >= allocFloor) {
                    psum += allocFloor;
                }
            }
            if (psum > total) {
                hi = mid;
            } else {
                lo = mid;
            }
        }

        int psum = 0;
        boolean done = false;
        for (int j = end; j-- > start;) {
            int tmp = bits1[j] + (lo * bits2[j] >> ALLOC_STEPS);
            if (tmp < thresh[j] && !done) {
                tmp = tmp >= allocFloor ? allocFloor : 0;
            } else {
                done = true;
            }
            tmp = Math.min(tmp, cap[j]);
            pulses[j] = tmp;
            psum += tmp;
        }

        // Decide which bands to skip, working back from the top. Each decision
        // that is affordable costs one bit in the stream; the rest are forced.
        int bands;
        for (bands = end;; bands--) {
            int j = bands - 1;
            if (j <= skipStart) {
                // Nothing left that may be skipped, so the reserved flag is
                // never coded and its bit goes back into the pot.
                total += skipRsv;
                break;
            }
            // How many left-over bits this band would receive, including any
            // stolen back from the bands already skipped above it. The widths
            // here are the 2.5 ms widths -- m->eBands, not m->eBands<<LM --
            // which makes the per-coefficient share coarser by a factor of the
            // frame size. That is what the reference does, and changing it
            // shifts a few eighth bits between bands.
            int left = total - psum;
            int span = CeltTables.eband(bands) - CeltTables.eband(start);
            int percoeff = left / span;
            left -= span * percoeff;
            int rem = Math.max(left - (CeltTables.eband(j) - CeltTables.eband(start)), 0);
            int bandWidth = CeltTables.eband(bands) - CeltTables.eband(j);
            int bandBits = pulses[j] + percoeff * bandWidth + rem;
            // Only code a skip flag when the band could afford to be coded.
            // Below that it is force-skipped, which is what guarantees there is
            // room for the flag itself.
            if (bandBits >= Math.max(thresh[j], allocFloor + (1 << BIT_RES))) {
                if (enc != null) {
                    // The only non-mandatory decision in the whole procedure.
                    // The threshold is 7/16 bit per coefficient for a band the
                    // last frame also coded and 9/16 for a new one, which is the
                    // hysteresis that stops the top band flickering on and off
                    // and pumping the high end.
                    if (bandBits > ((j < prev ? 7 : 9) * bandWidth << lm << BIT_RES) >> 4) {
                        enc.encodeBit(1, 1);
                        break;
                    }
                    enc.encodeBit(0, 1);
                } else if (dec.decodeBit(1) != 0) {
                    break;
                }
                psum += 1 << BIT_RES;
                bandBits -= 1 << BIT_RES;
            }
            // Take back what this band was holding.
            psum -= pulses[j] + intensityRsv;
            if (intensityRsv > 0) {
                // One fewer band to choose between, so the intensity parameter
                // is cheaper. Re-priced against the bands that remain.
                intensityRsv = CeltMode.intensityReservation(j - start);
            }
            psum += intensityRsv;
            if (bandBits >= allocFloor) {
                // Enough for one fine energy bit per channel, so the band keeps
                // its coarse and fine energy and loses only its shape.
                psum += allocFloor;
                pulses[j] = allocFloor;
            } else {
                pulses[j] = 0;
            }
        }

        if (bands <= start) {
            // Structurally impossible: the loop above breaks at the latest when
            // bands-1 reaches skipStart, which is at least start. Checked
            // anyway, because everything below divides by the width of
            // [start, bands) and a zero there would be an ArithmeticException
            // from the middle of a decode.
            throw new IllegalStateException(
                    "band skipping left " + bands + " coded bands, at or below the start band "
                    + start + "; this is a bug in the allocator, not in the stream");
        }

        if (intensityRsv > 0) {
            if (enc != null) {
                intensity = Math.min(intensity, bands);
                enc.encodeUniform(intensity - start, bands + 1 - start);
            } else {
                intensity = start + dec.decodeUniform(bands + 1 - start);
            }
        } else {
            intensity = 0;
        }
        if (intensity <= start) {
            // No band is coded as intensity stereo, so there is nothing for the
            // dual stereo flag to change and its reservation goes back.
            total += dualStereoRsv;
            dualStereoRsv = 0;
        }
        if (dualStereoRsv > 0) {
            if (enc != null) {
                enc.encodeBit(dualStereo, 1);
            } else {
                dualStereo = dec.decodeBit(1);
            }
        } else {
            dualStereo = 0;
        }

        // Spread whatever is left over the coded bands, whole coefficients
        // first and then one eighth bit at a time from the bottom up.
        int left = total - psum;
        int span = CeltTables.eband(bands) - CeltTables.eband(start);
        int percoeff = left / span;
        left -= span * percoeff;
        for (int j = start; j < bands; j++) {
            pulses[j] += percoeff * (CeltTables.eband(j + 1) - CeltTables.eband(j));
        }
        for (int j = start; j < bands; j++) {
            int tmp = Math.min(left, CeltTables.eband(j + 1) - CeltTables.eband(j));
            pulses[j] += tmp;
            left -= tmp;
        }

        // Split each band between fine energy and shape.
        int running = 0;
        int j = start;
        for (; j < bands; j++) {
            int n = mode.bandWidth(j);
            pulses[j] += running;
            int excess;
            if (n > 1) {
                excess = Math.max(pulses[j] - cap[j], 0);
                pulses[j] -= excess;

                // Degrees of freedom. A jointly coded stereo band wider than
                // one bin carries one extra parameter, the stereo angle.
                int den = channels * n
                        + (channels == 2 && n > 2 && dualStereo == 0 && j < intensity ? 1 : 0);
                // mode.logN() is m->logN[j] + logM, the conservative log2 of the
                // band width at this frame size, in eighth bits.
                int nclogn = den * mode.logN(j);
                int offset = (nclogn >> 1) - den * FINE_OFFSET;
                // N=2 is the one width the curve does not fit.
                if (n == 2) {
                    offset += den << BIT_RES >> 2;
                }
                // The second and third fine bits are worth more than the curve
                // says, so they are made cheaper to reach.
                if (pulses[j] + offset < den * 2 << BIT_RES) {
                    offset += nclogn >> 2;
                } else if (pulses[j] + offset < den * 3 << BIT_RES) {
                    offset += nclogn >> 3;
                }
                fineBits[j] = Math.max(0,
                        (pulses[j] + offset + (den << (BIT_RES - 1))) / (den << BIT_RES));
                // Never spend more on fine energy than the band was given.
                if (channels * fineBits[j] > (pulses[j] >> BIT_RES)) {
                    fineBits[j] = pulses[j] >> stereo >> BIT_RES;
                }
                fineBits[j] = Math.min(fineBits[j], MAX_FINE_BITS);
                // Rounded down or capped, so this band is where a leftover bit
                // should go at the end of the frame.
                finePriority[j] = fineBits[j] * (den << BIT_RES) >= pulses[j] + offset ? 1 : 0;
                pulses[j] -= channels * fineBits[j] << BIT_RES;
            } else {
                // One bin: a sign bit is the whole of the shape, so everything
                // else is fine energy.
                excess = Math.max(0, pulses[j] - (channels << BIT_RES));
                pulses[j] -= excess;
                fineBits[j] = 0;
                finePriority[j] = 1;
            }

            // Fine energy cannot take part in the rebalancing quant_all_bands
            // does, so anything a band could not spend is rebalanced here
            // instead, and only what is still left is carried forward.
            if (excess > 0) {
                int extraFine = Math.min(excess >> (stereo + BIT_RES), MAX_FINE_BITS - fineBits[j]);
                fineBits[j] += extraFine;
                int extraBits = extraFine * channels << BIT_RES;
                finePriority[j] = extraBits >= excess - running ? 1 : 0;
                excess -= extraBits;
            }
            running = excess;
        }
        balance = running;

        // The skipped bands spend everything they have left on fine energy,
        // which is one bit per channel or nothing at all.
        for (; j < end; j++) {
            fineBits[j] = pulses[j] >> stereo >> BIT_RES;
            pulses[j] = 0;
            finePriority[j] = fineBits[j] < 1 ? 1 : 0;
        }

        return bands;
    }

    private static void checkArguments(CeltMode mode, int start, int end,
            int[] offsets, int[] cap, int allocTrim, int channels) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(offsets, "offsets");
        Objects.requireNonNull(cap, "cap");
        int bandCount = mode.bandCount();
        if (start < 0 || start >= end || end > bandCount) {
            throw new IllegalArgumentException("coded bands " + start + ".." + end
                    + " are not a non-empty range inside 0.." + bandCount);
        }
        if (channels != 1 && channels != 2) {
            throw new IllegalArgumentException("channels must be 1 or 2, got " + channels);
        }
        if (allocTrim < 0 || allocTrim > 10) {
            throw new IllegalArgumentException(
                    "the allocation trim runs 0 to 10, got " + allocTrim);
        }
        if (offsets.length < bandCount || cap.length < bandCount) {
            throw new IllegalArgumentException("offsets and caps need " + bandCount
                    + " entries, got " + offsets.length + " and " + cap.length);
        }
        for (int j = start; j < end; j++) {
            // A negative boost or ceiling would drive a band's allocation below
            // zero, and the shape decoder would then ask the PVQ layer for a
            // negative number of pulses.
            if (offsets[j] < 0) {
                throw new IllegalArgumentException(
                        "band boost " + offsets[j] + " for band " + j + " is negative");
            }
            if (cap[j] < 0) {
                throw new IllegalArgumentException(
                        "allocation ceiling " + cap[j] + " for band " + j + " is negative");
            }
        }
    }

    private static int checkBand(int band) {
        if (band < 0 || band >= CeltMode.BAND_COUNT) {
            throw new IndexOutOfBoundsException(
                    "band " + band + " is outside 0.." + (CeltMode.BAND_COUNT - 1));
        }
        return band;
    }

    @Override
    public String toString() {
        return "CeltAllocation[codedBands=" + codedBands + ", balance=" + balance
                + ", intensity=" + intensity + ", dualStereo=" + dualStereo + "]";
    }
}
