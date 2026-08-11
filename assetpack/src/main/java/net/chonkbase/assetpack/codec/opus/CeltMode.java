package net.chonkbase.assetpack.codec.opus;

/**
 * Everything about a CELT frame that depends only on how long the frame is.
 *
 * <p>A port of {@code OpusCustomMode} in {@code celt/modes.h} restricted to the
 * one mode Opus proper defines, {@code mode48000_960_120} in
 * {@code celt/static_modes_float.h}, viewed at each of the four frame sizes. It
 * carries the band layout of RFC 6716 Table 55, the static allocation table of
 * Table 57 (section 4.3.3), the allocation ceilings that section names, and the
 * frame-size constants sections 4.3.1 and 4.3.7 depend on.
 *
 * <p>Opus has exactly one CELT mode, and the frame size is not part of it: the band
 * edges are stored once in units of the 2.5 ms transform and shifted left by LM for
 * longer frames. Presenting each frame size as its own object turns that shift into
 * a fact of the object rather than something every call site has to remember, which
 * matters because forgetting it does not fail loudly. It produces a decoder that
 * reads the right number of symbols and puts them in bands a factor of eight too
 * narrow, which sounds like a low-pass filter with a hole in it.
 *
 * <p>The four instances are built once and handed out; {@link #forFrameSize(int)}
 * allocates nothing, and neither does any accessor except the two that say in their
 * name that they copy. All the data here is integer and exactly specified by the
 * RFC. The only floating point on this class is the band edge frequencies, which are
 * derived, not decoded, and never feed the bitstream.
 */
public final class CeltMode {

    /** The rate the MDCT layer always runs at, whatever the signalled bandwidth. */
    public static final int SAMPLE_RATE = 48_000;

    /**
     * Samples in the shortest CELT frame, and so the size of one short MDCT.
     *
     * <p>2.5 ms at 48 kHz. Every longer frame is a whole number of these.
     */
    public static final int SHORT_MDCT_SIZE = 120;

    /**
     * Samples of window overlap between consecutive frames.
     *
     * <p>The {@code overlap} field of {@code mode48000_960_120}. It is 120 for every
     * frame size, not just the shortest: CELT keeps its algorithmic delay at 2.5 ms
     * by zero-padding the window rather than widening it, which is the "low-overlap"
     * window of RFC 6716 section 4.3.7. A decoder that scales the overlap with the
     * frame size will produce a click at every frame boundary.
     */
    public static final int OVERLAP = 120;

    /**
     * How many bands the layer has in total, before any bandwidth restriction.
     *
     * <p>RFC 6716 section 4.3: "The normal CELT layer uses 21 of those bands".
     */
    public static final int BAND_COUNT = CeltTables.BAND_COUNT;

    /**
     * Bit-resolution shift of the allocation arithmetic: allocations are in 1/8 bits.
     *
     * <p>{@code BITRES} in {@code celt/entcode.h}. RFC 6716 section 4.3.3 states the
     * same thing in words, calling the units "8th bits".
     */
    public static final int BIT_RES = 3;

    /**
     * Bisection steps used to interpolate between two rows of the allocation table.
     *
     * <p>{@code ALLOC_STEPS} in {@code celt/rate.c}. Six steps means the interpolation
     * point is a multiple of 1/64, which is what RFC 6716 section 4.3.3 means by
     * "linearly interpolating between two values of q (in steps of 1/64)".
     */
    public static final int ALLOC_STEPS = 6;

    /** The first band a CELT-only or SILK-only frame codes. */
    public static final int CODING_START_BAND = 0;

    /**
     * The first band a Hybrid frame codes.
     *
     * <p>RFC 6716 section 4.3: "In Hybrid mode, the first 17 bands (up to 8 kHz) are
     * not coded", because SILK has already coded that range.
     */
    public static final int HYBRID_CODING_START_BAND = 17;

    private static final CeltMode[] BY_LM = {
        new CeltMode(0), new CeltMode(1), new CeltMode(2), new CeltMode(3)
    };

    private final int lm;
    private final int frameSize;
    private final int[] bandBoundaries;

    private CeltMode(int lm) {
        this.lm = lm;
        this.frameSize = SHORT_MDCT_SIZE << lm;
        this.bandBoundaries = new int[BAND_COUNT + 1];
        for (int edge = 0; edge <= BAND_COUNT; edge++) {
            this.bandBoundaries[edge] = CeltTables.eband(edge) << lm;
        }
    }

    /**
     * The mode for a frame of the given length.
     *
     * <p>Returns a shared instance and allocates nothing, so it is safe to call from
     * the decode path.
     *
     * @param samples48k 120, 240, 480 or 960, being 2.5, 5, 10 or 20 ms
     * @throws IllegalArgumentException if the frame size is not one CELT codes
     */
    public static CeltMode forFrameSize(int samples48k) {
        if (samples48k == 120) {
            return BY_LM[0];
        }
        if (samples48k == 240) {
            return BY_LM[1];
        }
        if (samples48k == 480) {
            return BY_LM[2];
        }
        if (samples48k == 960) {
            return BY_LM[3];
        }
        throw new IllegalArgumentException(
                "CELT frame size must be 120, 240, 480 or 960 samples at 48 kHz, got " + samples48k);
    }

    /**
     * The mode for a given LM, the base-2 log of the frame size in short blocks.
     *
     * @param lm 0 through 3
     */
    public static CeltMode forLm(int lm) {
        if (lm < 0 || lm > 3) {
            throw new IllegalArgumentException("LM must be 0 through 3, got " + lm);
        }
        return BY_LM[lm];
    }

    /** Frame length in samples at 48 kHz, which is also the MDCT bins per channel. */
    public int frameSize() {
        return frameSize;
    }

    /**
     * Base-2 log of the frame size in units of the shortest frame.
     *
     * <p>{@code LM} throughout the reference, and defined in RFC 6716 section 4.3.3
     * as {@code log2(frame_size/120)}.
     */
    public int lm() {
        return lm;
    }

    /** Frame duration in microseconds: 2500, 5000, 10000 or 20000. */
    public int frameMicros() {
        return 2500 << lm;
    }

    /** How many bands the layer has, before any bandwidth restriction. */
    public int bandCount() {
        return BAND_COUNT;
    }

    /**
     * The band boundaries in MDCT bins, {@link #bandCount()} plus one of them.
     *
     * <p>Copies the array, so this is for tests and tools; the decode path wants
     * {@link #bandStart(int)} and {@link #bandEnd(int)}, which read the same numbers
     * without allocating.
     *
     * <p>The last entry is five sixths of the frame size, not the frame size. CELT
     * codes nothing above 20 kHz, so the top sixth of the transform is left out of
     * the band layout entirely and the decoder must zero it.
     */
    public int[] bandBoundaries() {
        return bandBoundaries.clone();
    }

    /** First MDCT bin of a band. */
    public int bandStart(int band) {
        checkBand(band);
        return bandBoundaries[band];
    }

    /** One past the last MDCT bin of a band. */
    public int bandEnd(int band) {
        checkBand(band);
        return bandBoundaries[band + 1];
    }

    /**
     * MDCT bins in a band, for one channel.
     *
     * <p>This is the {@code N} of RFC 6716 section 4.3.3, and ranges from 1 bin in
     * the lowest band of a 2.5 ms frame to 176 in the highest band of a 20 ms frame.
     */
    public int bandWidth(int band) {
        checkBand(band);
        return bandBoundaries[band + 1] - bandBoundaries[band];
    }

    /**
     * MDCT bins the band layout covers, which is fewer than {@link #frameSize()}.
     *
     * <p>Everything from here to the end of the transform is above 20 kHz and is
     * never coded.
     */
    public int codedBins() {
        return bandBoundaries[BAND_COUNT];
    }

    /** Lower edge of a band in Hz. Exact: every edge lands on a whole hertz. */
    public int bandStartHz(int band) {
        checkBand(band);
        return edgeHz(band);
    }

    /** Upper edge of a band in Hz. */
    public int bandEndHz(int band) {
        checkBand(band);
        return edgeHz(band + 1);
    }

    /**
     * Centre frequency of a band in Hz.
     *
     * <p>Not a whole number for the odd-width bands, hence the double. Nothing in the
     * bitstream depends on this; it exists so that the band layout can be checked
     * against the critical-band scale it is meant to follow.
     */
    public double bandCentreHz(int band) {
        checkBand(band);
        return (edgeHz(band) + edgeHz(band + 1)) / 2.0;
    }

    /** Window overlap in samples. Always {@value #OVERLAP}, whatever the frame size. */
    public int overlap() {
        return OVERLAP;
    }

    /**
     * How many short MDCTs a transient frame splits into.
     *
     * <p>{@code shortBlocks} in {@code celt_decode_with_ec}, which is {@code 1 << LM}
     * when the transient flag is set and 1 when it is not. This is the transient
     * value; a non-transient frame of any size is a single long MDCT.
     */
    public int shortBlocks() {
        return 1 << lm;
    }

    /** Samples in one short MDCT. Always {@value #SHORT_MDCT_SIZE}. */
    public int shortMdctSize() {
        return SHORT_MDCT_SIZE;
    }

    /** How many quality rows the static allocation table has. */
    public int allocationRows() {
        return CeltTables.allocationRows();
    }

    /**
     * One quality row of the static allocation table, in 1/32 bit per MDCT bin.
     *
     * <p>Copies the array. The decode path wants {@link #allocation(int, int)}.
     *
     * @param index quality row, 0 through {@link #allocationRows()} minus one
     */
    public int[] allocationRow(int index) {
        int[] row = new int[BAND_COUNT];
        for (int band = 0; band < BAND_COUNT; band++) {
            row[band] = CeltTables.allocation(index, band);
        }
        return row;
    }

    /**
     * One entry of the static allocation table, in 1/32 bit per MDCT bin.
     *
     * <p>RFC 6716 Table 57. Frame size does not enter into it; see
     * {@link #staticBits(int, int, int)} for the value the allocator actually uses.
     */
    public int allocation(int row, int band) {
        checkBand(band);
        return CeltTables.allocation(row, band);
    }

    /**
     * The static allocation for a band at a given quality, in 1/8 bits.
     *
     * <p>RFC 6716 section 4.3.3 states this as
     * {@code channels*N*alloc[band][q]<<LM>>2}, with N the band width in the shortest
     * frame; the width here is already scaled by LM, so the shift is folded in and
     * the result is identical. The right shift is by two because the table is in
     * 1/32 bit units and the allocator works in 1/8 bit units.
     *
     * <p>This is the allocation before the trim, the boosts, the caps and the band
     * minimums, all of which depend on what the bitstream said and so belong to the
     * allocator rather than to the mode.
     *
     * @param row quality row, 0 through {@link #allocationRows()} minus one
     * @param band 0 through {@link #bandCount()} minus one
     * @param channels 1 or 2
     */
    public int staticBits(int row, int band, int channels) {
        checkChannels(channels);
        return channels * bandWidth(band) * allocation(row, band) >> 2;
    }

    /**
     * The allocation ceilings for this frame size and channel count, in 1/8 bits.
     *
     * <p>RFC 6716 section 4.3.3: take the stored value, add 64, multiply by the
     * channel count and by the band width in bins, and divide by four. Writes into
     * the caller's array so the decode path can hoist it out of the frame loop; the
     * ceilings change only when the channel count does.
     *
     * @param channels 1 or 2
     * @param out an array of at least {@link #bandCount()} entries
     * @throws IllegalArgumentException if the array is too short
     */
    public void computeCaps(int channels, int[] out) {
        checkChannels(channels);
        if (out.length < BAND_COUNT) {
            throw new IllegalArgumentException(
                    "caps array needs " + BAND_COUNT + " entries, got " + out.length);
        }
        for (int band = 0; band < BAND_COUNT; band++) {
            int width = bandBoundaries[band + 1] - bandBoundaries[band];
            out[band] = (CeltTables.cap(lm, channels, band) + 64) * channels * width >> 2;
        }
    }

    /**
     * Conservative log2 of a band's width in 1/8 bits, for this frame size.
     *
     * <p>{@code m->logN[band] + (LM << BITRES)} as the reference computes it in
     * {@code celt/rate.c}. The allocator subtracts this from a band's budget to split
     * it between fine energy and shape.
     */
    public int logN(int band) {
        checkBand(band);
        return CeltTables.logN(band) + (lm << BIT_RES);
    }

    /**
     * Time-frequency resolution adjustment for one band of this frame.
     *
     * <p>RFC 6716 Tables 60 through 63, selected by the transient flag and the
     * tf_select flag. Negative raises time resolution, positive raises frequency
     * resolution.
     *
     * @param isTransient the frame's transient flag
     * @param tfSelect the decoded tf_select flag, 0 or 1
     * @param tfChange the decoded per-band tf_change flag, 0 or 1
     */
    public int tfAdjustment(boolean isTransient, int tfSelect, int tfChange) {
        return CeltTables.tfAdjustment(lm, isTransient, tfSelect, tfChange);
    }

    /**
     * The first band coded, given the mode the packet signalled.
     *
     * <p>Hybrid frames start at band 17 because SILK has already coded everything
     * below 8 kHz. SILK-only frames never reach the CELT layer at all, but are
     * answered here as 0 so that the method is total.
     */
    public static int codingStartBand(OpusPacket.Mode mode) {
        return mode == OpusPacket.Mode.HYBRID ? HYBRID_CODING_START_BAND : CODING_START_BAND;
    }

    /**
     * One past the last band coded, given the bandwidth the packet signalled.
     *
     * <p>A port of the {@code endband} switch in {@code opus_decode_frame}
     * ({@code src/opus_decoder.c}). Four of the five answers land exactly on the
     * bandwidth's cutoff frequency from RFC 6716 Table 1: band 13 begins at 4 kHz,
     * band 17 at 8 kHz, band 19 at 12 kHz and band 21 is the 20 kHz top.
     *
     * <p>Medium-band is the exception and is deliberately not 6 kHz. There is no band
     * edge at 6 kHz to stop on, so the reference rounds it up to the wideband answer.
     * It never matters in practice because no configuration selects medium-band with
     * the CELT layer active -- configurations 4 through 7 are SILK-only -- but
     * answering 17 rather than throwing keeps a decoder from failing on a packet the
     * framing layer accepted.
     */
    public static int codingEndBand(OpusPacket.Bandwidth bandwidth) {
        return switch (bandwidth) {
            case NARROWBAND -> 13;
            case MEDIUMBAND, WIDEBAND -> 17;
            case SUPERWIDEBAND -> 19;
            case FULLBAND -> 21;
        };
    }

    /**
     * Bits, in eighths, that must be reserved for the intensity stereo parameter.
     *
     * <p>RFC 6716 section 4.3.3 calls this "the conservative log2 in 8th bits of the
     * number of coded bands", and names {@code LOG2_FRAC_TABLE} in {@code celt/rate.c}
     * as the source. Conservative means rounded up: the parameter selects one of
     * {@code codedBands+1} values, and under-reserving would let the intensity symbol
     * run past the end of the frame.
     *
     * @param codedBands the number of bands this frame codes, end minus start
     */
    public static int intensityReservation(int codedBands) {
        return CeltTables.log2Frac(codedBands);
    }

    /**
     * The rotation factor {@code f_r} for a spread value, zero meaning no rotation.
     *
     * <p>RFC 6716 Table 59. Spread 0 is listed there as an infinite factor, which is
     * the RFC's way of saying the rotation is skipped.
     */
    public static int spreadRotationFactor(int spread) {
        return CeltTables.spreadRotationFactor(spread);
    }

    private int edgeHz(int edge) {
        return bandBoundaries[edge] * (SAMPLE_RATE / 2) / frameSize;
    }

    private void checkBand(int band) {
        if (band < 0 || band >= BAND_COUNT) {
            throw new IndexOutOfBoundsException(
                    "band " + band + " is outside 0.." + (BAND_COUNT - 1));
        }
    }

    private static void checkChannels(int channels) {
        if (channels != 1 && channels != 2) {
            throw new IllegalArgumentException("channels must be 1 or 2, got " + channels);
        }
    }

    @Override
    public String toString() {
        return "CeltMode[" + frameSize + " samples, LM " + lm + ", " + BAND_COUNT + " bands, "
                + codedBins() + " coded bins]";
    }
}
