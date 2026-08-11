package net.chonkbase.assetpack.codec.opus;

import java.util.Arrays;
import java.util.Objects;

/**
 * The CELT layer of an Opus encoder: PCM in, one frame of a bitstream out.
 *
 * <p>A port of {@code celt_encode_with_ec} in {@code celt/celt.c} together with
 * everything it calls there -- {@code transient_analysis},
 * {@code compute_mdcts}, {@code tf_analysis}, {@code tf_encode},
 * {@code init_caps}, {@code alloc_trim_analysis} and {@code stereo_analysis} --
 * the encode half of {@code quant_all_bands} and {@code quant_band} in
 * {@code celt/bands.c}, and {@code alg_quant}, {@code stereo_itheta},
 * {@code intensity_stereo} and {@code stereo_split} in {@code celt/vq.c}. The
 * entropy layer, the allocator, the energy envelope and the transform live in
 * {@link RangeEncoder}, {@link CeltAllocation}, {@link CeltEnergy} and
 * {@link Mdct}; this is the frame loop that drives them.
 *
 * <p>Everything is allocated in the constructor. Encoding a frame allocates
 * nothing, so this can sit on an audio thread.
 *
 * <h2>What this encoder does not do, and why that is still conformant</h2>
 *
 * <p><b>CELT only.</b> It emits configurations 16 to 31 and nothing else -- no
 * SILK, no Hybrid. RFC 6716 section 2 puts the asymmetry plainly: a decoder must
 * handle every mode, an encoder may emit any subset it likes. Fullband stereo
 * CELT is what libopus itself selects for music above about 64 kb/s, so this is
 * the subset that matters for the material this codec was added for. What it
 * costs is speech below about 32 kb/s, where SILK is several dB better; an
 * encoder asked for that rate here produces a valid stream that simply sounds
 * worse than libopus would.
 *
 * <p><b>Constant bitrate.</b> {@code celt_encode_with_ec}'s VBR branch and its
 * reservoir are not ported. Every frame is padded to the byte count the target
 * rate buys, which is what the {@code st->vbr == 0} path does. The cost is on
 * silence and on steady tones, where VBR would spend two bytes and this spends
 * the full frame; the benefit is that the achieved rate is the requested rate to
 * within the rounding of one frame, and that a caller sizing a buffer or a file
 * is never surprised.
 *
 * <p><b>No pitch prefilter.</b> {@code pitch_downsample}, {@code pitch_search}
 * and {@code remove_doubling} are not ported, so the prefilter gain is always
 * zero and the "no postfilter" bit is what goes into every frame. This is not an
 * invention: it is exactly what the reference does below complexity 5, which is
 * to say this encoder is libopus at complexity 4 -- transient analysis, spread
 * decision and two-pass coarse energy all on, pitch analysis off. The prefilter
 * earns its bits on strongly pitched material at low rates, chiefly speech; on
 * the 44.1 kHz music this was measured against it moves the SNR by under half a
 * dB.
 *
 * <p><b>No resynthesis.</b> The reference encoder only reconstructs what it sent
 * when built with {@code RESYNTH}, which the RFC's own tarball does not define,
 * and this follows it. That is why nothing here folds, dithers or advances a
 * folding seed: without resynthesis the encoder never has a reconstructed lower
 * band to fold from, so {@code quant_band}'s {@code lowband} is always null and
 * its collapse masks are read by nothing. The decoder folds on its own side from
 * what it decoded, which is the only copy either end can agree on anyway.
 *
 * <p><b>Two channels at most</b>, and the bitstream channel count always equals
 * the encoder's. The reference can drop a stereo stream to mono mid-stream;
 * nothing here decides to.
 */
public final class CeltEncoder {

    /** {@code maxLM}: the 48 kHz mode's longest frame is eight short blocks. */
    private static final int MAX_LM = 3;

    /** {@code BITRES}. */
    private static final int BIT_RES = CeltMode.BIT_RES;

    /** {@code SPREAD_NONE} in {@code celt/bands.h}. */
    private static final int SPREAD_NONE = 0;

    /** {@code SPREAD_LIGHT}. */
    private static final int SPREAD_LIGHT = 1;

    /** {@code SPREAD_NORMAL}. */
    private static final int SPREAD_NORMAL = 2;

    /** {@code SPREAD_AGGRESSIVE}. */
    private static final int SPREAD_AGGRESSIVE = 3;

    /** {@code QTHETA_OFFSET} in {@code celt/bands.c}. */
    private static final int QTHETA_OFFSET = 4;

    /** {@code QTHETA_OFFSET_TWOPHASE}. */
    private static final int QTHETA_OFFSET_TWOPHASE = 16;

    /**
     * {@code mode->preemph[0]} for the 48 kHz mode, from
     * {@code static_modes_float.h}.
     *
     * <p>The whole of the analysis filter at this rate: {@code 1 - 0.85000610
     * z^-1}. The reference's table has four entries, and the other three are the
     * identity here -- {@code preemph[1]} is zero and {@code preemph[2]} and
     * {@code preemph[3]} are one -- so they are folded away rather than written
     * out and multiplied by. {@link CeltDecoder} carries the full four because
     * the expression it appears in there does not collapse as cleanly.
     *
     * <p>What it is for: the filter tilts the spectrum up before quantisation so
     * that the quantisation noise, which comes out flat, is tilted back down
     * under the signal by the decoder's matching de-emphasis. Encoding without it
     * puts white noise under a signal whose top octave is 30 dB below its bottom
     * one, which is heard as hiss on quiet high-frequency passages.
     */
    private static final float PREEMPH = 0.85000610f;

    /**
     * {@code eMeans} in {@code celt/quant_bands.c}: the mean log energy of each
     * band, in units of 6 dB.
     *
     * <p>Subtracted here and added back by the decoder's {@code log2Amp}, so the
     * coded residual is centred on zero whatever the material. The same table as
     * {@link CeltDecoder}'s, and the two must stay equal: a difference of one
     * entry puts one band 6 dB out on every frame of every stream.
     */
    private static final float[] E_MEANS = {
        6.437500f, 6.250000f, 5.750000f, 5.312500f, 5.062500f,
        4.812500f, 4.500000f, 4.375000f, 4.875000f, 4.687500f,
        4.562500f, 4.437500f, 4.875000f, 4.625000f, 4.312500f,
        4.500000f, 4.375000f, 4.625000f, 4.750000f, 4.437500f,
        3.750000f, 3.750000f, 3.750000f, 3.750000f, 3.750000f
    };

    /** {@code sqrtM_1} in {@code l1_metric}: 2**(-LM/2). */
    private static final float[] SQRT_M_INVERSE = {1.0f, 0.70710678f, 0.5f, 0.35355339f};

    /** {@code SPREAD_FACTOR} in {@code exp_rotation}, indexed by spread minus one. */
    private static final int[] SPREAD_FACTOR = {15, 10, 5};

    /**
     * {@code bit_interleave_table} in {@code quant_band}.
     *
     * <p>Which short blocks a recombined band's folding source covers.
     */
    private static final int[] BIT_INTERLEAVE = {
        0, 1, 1, 1, 2, 3, 3, 3, 2, 3, 3, 3, 2, 3, 3, 3
    };

    /** {@code EPSILON} in the float build of {@code celt/arch.h}. */
    private static final float EPSILON = 1e-15f;

    /** {@code VERY_LARGE16} in the float build of {@code celt/arch.h}. */
    private static final float VERY_LARGE = 1e15f;

    /** 1/sqrt(2), the Haar and mid/side scale. */
    private static final float SQRT_HALF = 0.70710678f;

    /** Half of pi, for {@code celt_cos_norm}. */
    private static final float HALF_PI = 0.5f * 3.141592653f;

    private final int channels;
    private final int bands;
    private final int overlap;

    private final Mdct mdct;
    private final float[] window;
    private final CeltEnergy energy;
    private final CeltAllocation allocation;
    private final short[] trimIcdf;
    private final short[] spreadIcdf;

    /** {@code st->in_mem}: the last {@code overlap} pre-emphasised samples. */
    private final float[] inMem;

    /** {@code st->preemph_memE}: the pre-emphasis filter memory, one per channel. */
    private final float[] preemphMem;

    /**
     * {@code oldBandE}: the envelope as the decoder will reconstruct it.
     *
     * <p>Owned by {@link CeltEnergy}, which is what the next frame predicts from;
     * this is the copy it hands back, and {@link #quantisedBandEnergy()} exposes
     * it so a test can assert the two ends agree on it exactly.
     *
     * <p>The reference also keeps {@code oldLogE} and {@code oldLogE2} here, the
     * two previous frames' envelopes. They are not carried, because the only
     * thing that reads them is {@code anti_collapse}, and the encoder only runs
     * that when it is built with {@code RESYNTH} -- which the RFC's own tarball
     * does not define and this port does not implement. Keeping them would be two
     * arrays updated every frame and read by nothing.
     */
    private final float[] quantisedEnergy;

    // Per-frame workspaces, fields so that encoding a frame allocates nothing.
    private final float[] in;
    private final float[] freq;
    private final float[] spectrum;
    private final float[] bandAmplitude;
    private final float[] bandLogE;
    private final float[] error;
    private final float[] tfScratch;
    private final float[] shuffle;
    private final float[] pvqTarget;
    private final float[] pvqSum;
    private final int[] pvqSign;
    private final int[] pulseVector;
    private final float[] transientScratch;
    private final float[] transientBins;
    private final int[] caps;
    private final int[] offsets;
    private final int[] tfRes;
    private final int[] tfMetric;
    private final int[] tfPath0;
    private final int[] tfPath1;
    private final int[] pulses;
    private final int[] fineBits;
    private final int[] finePriority;

    /** {@code st->bitrate} in bits per second. */
    private int bitrate;

    /** {@code st->spread_decision}. */
    private int spreadDecision;

    /** {@code st->tonal_average}: a running measure of how tonal the signal is. */
    private int tonalAverage;

    /** {@code st->lastCodedBands}: biases the allocator's skip threshold. */
    private int lastCodedBands;

    /** {@code st->consec_transient}: how many transient frames in a row. */
    private int consecTransient;

    /** {@code st->rng}, kept because the reference keeps it. */
    private int rng;

    /** Whether the last frame coded its energy without reference to the one before. */
    private boolean intraEnergy;

    // Per-frame parameters the band quantiser reads, held as fields so the
    // recursion carries a dozen arguments rather than eighteen.
    private RangeEncoder enc;
    private int spread;
    private int intensity;
    private int remainingBits;

    /**
     * Builds an encoder and every buffer it will ever need.
     *
     * @param channels 1 or 2
     * @param bitrateBps the target rate in bits per second, before the packet's
     *                   own table of contents byte
     */
    public CeltEncoder(int channels, int bitrateBps) {
        if (channels != 1 && channels != 2) {
            throw new IllegalArgumentException("CELT encodes 1 or 2 channels, not " + channels);
        }
        CeltMode longest = CeltMode.forLm(MAX_LM);
        this.channels = channels;
        this.bands = longest.bandCount();
        this.overlap = longest.overlap();
        this.mdct = new Mdct(longest.frameSize());
        this.window = Mdct.window(overlap);
        this.energy = new CeltEnergy(longest, channels);
        this.allocation = new CeltAllocation();
        this.trimIcdf = CeltTables.copyTrimIcdf();
        this.spreadIcdf = CeltTables.copySpreadIcdf();

        int n = longest.frameSize();
        int widest = longest.bandWidth(bands - 1);
        this.inMem = new float[channels * overlap];
        this.preemphMem = new float[2];
        this.quantisedEnergy = new float[channels * bands];

        this.in = new float[channels * (n + overlap)];
        this.freq = new float[channels * n];
        this.spectrum = new float[channels * n];
        this.bandAmplitude = new float[channels * bands];
        this.bandLogE = new float[channels * bands];
        this.error = new float[channels * bands];
        this.tfScratch = new float[widest];
        this.shuffle = new float[widest];
        this.pvqTarget = new float[widest];
        this.pvqSum = new float[widest];
        this.pvqSign = new int[widest];
        this.pulseVector = new int[widest];
        // The transient detector works on one channel of the MDCT input buffer
        // and bins it in half-overlap blocks.
        this.transientScratch = new float[n + overlap];
        this.transientBins = new float[(n + overlap) / (overlap / 2) + 1];
        this.caps = new int[bands];
        this.offsets = new int[bands];
        this.tfRes = new int[bands];
        this.tfMetric = new int[bands];
        this.tfPath0 = new int[bands];
        this.tfPath1 = new int[bands];
        this.pulses = new int[bands];
        this.fineBits = new int[bands];
        this.finePriority = new int[bands];

        setBitrate(bitrateBps);
        reset();
    }

    /** How many channels this encoder codes. */
    public int channels() {
        return channels;
    }

    /** The target rate in bits per second. */
    public int bitrate() {
        return bitrate;
    }

    /**
     * Changes the target rate, {@code OPUS_SET_BITRATE}.
     *
     * @param bitrateBps bits per second, at least 500 and at most 512000
     */
    public void setBitrate(int bitrateBps) {
        if (bitrateBps < 500 || bitrateBps > 512_000) {
            throw new IllegalArgumentException("an Opus bitrate is 500 to 512000 bits per"
                    + " second, not " + bitrateBps);
        }
        this.bitrate = bitrateBps;
    }

    /**
     * Forgets every frame encoded so far, as {@code OPUS_RESET_STATE} does.
     *
     * <p>Three things here are not zero, and every one of them changes the first
     * frame after a reset. The spread decision starts at normal rather than at
     * none, because it is coded relative to nothing and a stream that opened with
     * no rotation would put its first frame's pulses in bare spikes. The tonal
     * average starts at 256, the middle of its range, so the first frame is not
     * treated as either a pure tone or pure noise. And the delayed-intra measure
     * inside {@link CeltEnergy} starts at 1, which is what makes the first frame
     * of a stream code its energy intra -- without it the first frame predicts
     * from an envelope of all zeros and comes out at entirely the wrong level.
     */
    public void reset() {
        Arrays.fill(inMem, 0.0f);
        Arrays.fill(preemphMem, 0.0f);
        Arrays.fill(quantisedEnergy, 0.0f);
        energy.reset();
        spreadDecision = SPREAD_NORMAL;
        tonalAverage = 256;
        lastCodedBands = 0;
        consecTransient = 0;
        rng = 0;
        intraEnergy = false;
    }

    /**
     * The energy envelope the last frame settled on, as the decoder will
     * reconstruct it.
     *
     * <p>Log base 2, one unit per 6 dB, with the per-band mean already
     * subtracted; the {@code oldBandE} of the reference, laid out
     * {@code channel*bandCount + band}. Copied, because the next frame overwrites
     * it.
     *
     * <p>This is the quantised signal's envelope, and an encoder and a decoder
     * that agree on the bitstream must agree on it to the last bit -- they run
     * the same recurrence over the same symbols. They are separate pieces of
     * code, though, so {@code CeltEncoderTest} compares them: a typo in one
     * would not change any symbol in the frame it happened on, only the
     * predictions of the frames after it.
     */
    public float[] quantisedBandEnergy() {
        return quantisedEnergy.clone();
    }

    /**
     * Whether the last encoded frame coded its energy envelope intra.
     *
     * <p>An intra frame predicts across bands only, so a decoder joining the
     * stream there -- or one that has just lost a packet -- recovers the
     * envelope immediately instead of converging over the next several frames.
     * The encoder chooses per frame, on whichever of the two predictors came out
     * shorter; see {@link CeltEnergy#encodeCoarse}.
     */
    public boolean lastFrameCodedIntra() {
        return intraEnergy;
    }

    /**
     * The range coder state the last encoded frame ended on.
     *
     * <p>Equal to what a conformant decoder reports through
     * {@link OpusDecoder#finalRange()} after reading the same frame. Comparing
     * the two is the only check that proves every symbol was written and read
     * identically; the audio can be excused by floating-point rounding and this
     * cannot.
     */
    public long finalRange() {
        return rng & 0xFFFFFFFFL;
    }

    /**
     * How many bytes a frame of this many samples will occupy at the target rate.
     *
     * <p>The reference's constant-bitrate sizing from {@code celt_encode_with_ec}:
     * the frame's share of the rate, rounded, clamped to two bytes at the bottom
     * and 1275 at the top.
     *
     * @param frameSize 120, 240, 480 or 960 samples at 48 kHz
     */
    public int frameBytes(int frameSize) {
        CeltMode.forFrameSize(frameSize);
        long tmp = (long) bitrate * frameSize;
        int bytes = (int) ((tmp + 4L * CeltMode.SAMPLE_RATE) / (8L * CeltMode.SAMPLE_RATE));
        return Math.max(2, Math.min(OpusPacket.MAX_FRAME_BYTES, bytes));
    }

    /**
     * Encodes one CELT frame, {@code celt_encode_with_ec}.
     *
     * <p>Reads {@code channels*frameSize} interleaved 16-bit samples and writes a
     * frame of exactly {@link #frameBytes(int)} bytes. The frame is what goes
     * after a packet's table of contents byte; framing is {@link OpusEncoder}'s.
     *
     * @param pcm       interleaved input, on the 16-bit scale
     * @param pcmOffset where in it the frame starts
     * @param frameSize 120, 240, 480 or 960 samples per channel at 48 kHz
     * @param out       receives the frame
     * @param outOffset where in it to write
     * @return bytes written
     * @throws IllegalArgumentException if the frame size is not one CELT codes or
     *                                  either buffer is too small
     */
    public int encode(short[] pcm, int pcmOffset, int frameSize, byte[] out, int outOffset) {
        Objects.requireNonNull(pcm, "pcm");
        Objects.requireNonNull(out, "out");
        CeltMode mode = CeltMode.forFrameSize(frameSize);
        int lm = mode.lm();
        int m = 1 << lm;
        int n = frameSize;
        long needed = (long) channels * n;
        if (pcmOffset < 0 || pcm.length - pcmOffset < needed) {
            throw new IllegalArgumentException("the input needs " + needed
                    + " samples at offset " + pcmOffset + " of a " + pcm.length + " array");
        }

        int nbCompressedBytes = frameBytes(frameSize);
        if (outOffset < 0 || out.length - outOffset < nbCompressedBytes) {
            throw new IllegalArgumentException("a " + frameSize + "-sample frame at "
                    + bitrate + " bits per second needs " + nbCompressedBytes
                    + " bytes at offset " + outOffset + " of a " + out.length + " array");
        }

        int start = 0;
        int end = bands;
        int effEnd = end;
        int channelStride = n + overlap;
        int effectiveBytes = nbCompressedBytes;
        int nbAvailableBytes = nbCompressedBytes;

        RangeEncoder encoder = new RangeEncoder(out, outOffset, nbCompressedBytes);
        this.enc = encoder;

        // Pre-emphasis, and the silence test that goes with it. The reference
        // reads the flag off the pre-emphasised signal rather than the input,
        // which matters: a constant non-zero DC input pre-emphasises to zero
        // after its first sample and is silence as far as CELT is concerned.
        boolean silence = true;
        for (int c = 0; c < channels; c++) {
            System.arraycopy(inMem, c * overlap, in, c * channelStride, overlap);
            float mem = preemphMem[c];
            int at = c * channelStride + overlap;
            int from = pcmOffset + c;
            for (int i = 0; i < n; i++) {
                float x = pcm[from];
                float value = x + mem;
                mem = -PREEMPH * x;
                in[at + i] = value;
                silence &= value == 0.0f;
                from += channels;
            }
            preemphMem[c] = mem;
            System.arraycopy(in, c * channelStride + n, inMem, c * overlap, overlap);
        }

        int totalBits = nbCompressedBytes * 8;
        encoder.encodeBit(silence ? 1 : 0, 15);
        if (silence) {
            // Charge the frame for everything it has left, so that every "is
            // there room for this symbol" test below answers no and the frame
            // becomes the flag and nothing else. The decoder does exactly this
            // on the same flag; see RangeEncoder.chargeRemainingBits.
            encoder.chargeRemainingBits();
        }

        // The prefilter is not ported; see the class javadoc. What the frame
        // still owes the decoder is the bit that says there is no post-filter.
        // A CELT-only frame always starts at band 0, so the reference's
        // st->start==0 guard is always true here.
        if (encoder.tell() + 16 <= totalBits) {
            encoder.encodeBit(0, 1);
        }

        boolean isTransient = false;
        if (lm > 0 && encoder.tell() + 3 <= totalBits) {
            isTransient = transientAnalysis(channelStride, n + overlap);
            encoder.encodeBit(isTransient ? 1 : 0, 3);
        }
        int shortBlocks = isTransient ? m : 0;

        computeMdcts(shortBlocks, lm, n, channelStride);
        computeBandEnergies(mode, n, effEnd);
        amp2Log2(effEnd, end);
        normaliseBands(mode, n, effEnd);

        tfAnalysis(mode, effEnd, isTransient, effectiveBytes, n, lm);

        intraEnergy = energy.encodeCoarse(encoder, start, end, effEnd, lm, bandLogE,
                totalBits, nbAvailableBytes, false, error, quantisedEnergy);

        tfEncode(start, end, isTransient, lm, totalBits);

        // Reset before the measurement, not after, and that ordering is
        // upstream's rather than an oversight here. spreading_decision takes the
        // previous frame's decision as the input to its hysteresis, and
        // celt_encode_with_ec assigns SPREAD_NORMAL to the same field one line
        // before it passes it -- so the hysteresis always sees "normal" as the
        // last decision, whatever the last frame actually chose. It is coded that
        // way because that is what the reference does; changing it would make
        // this encoder pick a different rotation from libopus on the frames
        // either side of a change, which is a difference a listener could hear
        // as a change in the character of the noise floor.
        spreadDecision = SPREAD_NORMAL;
        if (encoder.tell() + 4 <= totalBits) {
            // A transient frame's blocks are too short for the tonality measure
            // to mean anything, and a frame this small has no bits to spend
            // acting on it either way; both keep the default.
            if (shortBlocks == 0 && nbAvailableBytes >= 10 * channels) {
                spreadDecision = spreadingDecision(mode, effEnd, n);
            }
            encoder.encodeIcdf(spreadDecision, spreadIcdf, CeltTables.SPREAD_ICDF_FTB);
        }

        mode.computeCaps(channels, caps);
        Arrays.fill(offsets, 0);
        dynallocAnalysis(start, end, lm, effectiveBytes);

        int dynallocLogp = 6;
        int totalEighths = totalBits << BIT_RES;
        int totalBoost = 0;
        int tellFrac = encoder.tellFrac();
        for (int i = start; i < end; i++) {
            int width = channels * mode.bandWidth(i);
            // Six bits per step, but never more than one bit per sample and
            // never less than an eighth.
            int quanta = Math.min(width << BIT_RES, Math.max(6 << BIT_RES, width));
            int loopLogp = dynallocLogp;
            int boost = 0;
            int j = 0;
            while (tellFrac + (loopLogp << BIT_RES) < totalEighths - totalBoost
                    && boost < caps[i]) {
                int flag = j < offsets[i] ? 1 : 0;
                encoder.encodeBit(flag, loopLogp);
                tellFrac = encoder.tellFrac();
                if (flag == 0) {
                    break;
                }
                j++;
                boost += quanta;
                totalBoost += quanta;
                loopLogp = 1;
            }
            // One band boosted makes the next one cheaper to boost, because
            // dynalloc decisions cluster around a transient or a tone. The
            // reference writes this as "if (j)" on the encode side and
            // "if (boost>0)" on the decode side; they are the same test,
            // because the loop only reaches its increment after a set flag.
            // Counting the terminating zero flag as a boost instead makes the
            // encoder cheapen the next band's first flag when the decoder does
            // not, and the two range coders part company on that band.
            if (boost > 0) {
                dynallocLogp = Math.max(2, dynallocLogp - 1);
            }
            offsets[i] = boost;
        }

        int allocTrim = 5;
        if (tellFrac + (6 << BIT_RES) <= totalEighths - totalBoost) {
            allocTrim = allocTrimAnalysis(mode, end, lm, n);
            encoder.encodeIcdf(allocTrim, trimIcdf, CeltTables.TRIM_ICDF_FTB);
        }

        int chosenIntensity = 0;
        int dualStereo = 0;
        if (channels == 2) {
            if (lm != 0) {
                // At 2.5 ms there is no time to measure anything, so the
                // reference always uses mid/side there.
                dualStereo = stereoAnalysis(mode, lm, n);
            }
            // The rate the shape actually gets, after coarse energy, in kb/s.
            int effectiveRate = (8 * effectiveBytes - 80) >> lm;
            effectiveRate = 2 * effectiveRate / 5;
            if (effectiveRate < 35) {
                chosenIntensity = 8;
            } else if (effectiveRate < 50) {
                chosenIntensity = 12;
            } else if (effectiveRate < 68) {
                chosenIntensity = 16;
            } else if (effectiveRate < 84) {
                chosenIntensity = 18;
            } else if (effectiveRate < 102) {
                chosenIntensity = 19;
            } else if (effectiveRate < 130) {
                chosenIntensity = 20;
            } else {
                chosenIntensity = 100;
            }
            chosenIntensity = Math.min(end, Math.max(start, chosenIntensity));
        }

        int bits = ((nbCompressedBytes * 8) << BIT_RES) - encoder.tellFrac() - 1;
        int antiCollapseRsv = isTransient && lm >= 2 && bits >= ((lm + 2) << BIT_RES)
                ? (1 << BIT_RES) : 0;
        bits -= antiCollapseRsv;

        int codedBands = allocation.encode(mode, start, end, offsets, caps, allocTrim,
                bits, channels, chosenIntensity, dualStereo, encoder, lastCodedBands);
        lastCodedBands = codedBands;
        for (int i = 0; i < bands; i++) {
            pulses[i] = allocation.pulses(i);
            fineBits[i] = allocation.fineBits(i);
            finePriority[i] = allocation.finePriority(i);
        }

        energy.encodeFine(encoder, start, end, fineBits, error, quantisedEnergy);

        quantAllBands(mode, start, end, n, pulses, shortBlocks, spreadDecision,
                allocation.dualStereo(), allocation.intensity(), tfRes,
                nbCompressedBytes * (8 << BIT_RES) - antiCollapseRsv,
                allocation.balance(), lm, codedBands);

        if (antiCollapseRsv > 0) {
            // Two transients in a row means the second one is probably a
            // continuing texture rather than an attack, and injecting noise into
            // its empty blocks would add hiss rather than restore an edge.
            encoder.encodeRawBits(consecTransient < 2 ? 1 : 0, 1);
        }

        energy.encodeFinal(encoder, start, end, fineBits, finePriority,
                nbCompressedBytes * 8 - encoder.tell(), error, quantisedEnergy);

        if (silence) {
            // The envelope is set to silence rather than simply not coded,
            // because the next frame predicts from it. Leaving this frame's
            // energies in place would make the signal swell back in over several
            // frames after a gap instead of entering cleanly.
            energy.setSilent(quantisedEnergy);
        }

        // "In case start or end were to change", as the reference puts it: a
        // stream that widened its bandwidth would start coding bands whose state
        // is left over from before it narrowed. Nothing here ever changes either
        // end -- a fullband CELT-only encoder codes bands 0 to 21 on every frame
        // -- so this never has anything to clear. It goes through CeltEnergy
        // rather than writing the array directly, because CeltEnergy owns the
        // state this is a copy of and a direct write would leave the two
        // disagreeing about what the decoder is going to reconstruct.
        energy.clearOutsideCodedRange(start, end, quantisedEnergy);

        consecTransient = isTransient ? consecTransient + 1 : 0;
        rng = (int) encoder.finalRange();

        encoder.finish();
        this.enc = null;
        // The reference returns nbCompressedBytes whatever the coder used,
        // because ec_enc_done has already zero-filled the gap: a constant
        // bitrate stream whose frames varied in length by a byte or two would
        // not be a constant bitrate stream.
        return nbCompressedBytes;
    }

    /**
     * {@code transient_analysis} in {@code celt/celt.c}.
     *
     * <p>High-pass the frame, take the peak of each 2.5 ms half-block, and call
     * it a transient if any block is more than about eight times the ones near
     * it. What this is protecting against is pre-echo: quantisation noise from a
     * 20 ms transform is spread evenly over all 20 ms, so a drum hit 15 ms into
     * the frame puts its own noise 15 ms ahead of itself, which is heard as a
     * smear or a "double hit" before the attack. Splitting the frame into eight
     * short transforms confines the noise to 2.5 ms, where it is masked.
     *
     * <p>The channels are summed rather than averaged, which is upstream's --
     * the fixed-point build halves and the float build does not -- and it does
     * not matter because every threshold here is relative to the same sum.
     */
    private boolean transientAnalysis(int channelStride, int len) {
        float[] tmp = transientScratch;
        if (channels == 1) {
            System.arraycopy(in, 0, tmp, 0, len);
        } else {
            for (int i = 0; i < len; i++) {
                tmp[i] = in[i] + in[channelStride + i];
            }
        }

        // (1 - 2 z^-1 + z^-2) / (1 - z^-1 + 0.5 z^-2): a second-order high-pass
        // that removes the bass, because a bass note's own envelope is slow and
        // would otherwise never look like an attack.
        float mem0 = 0;
        float mem1 = 0;
        for (int i = 0; i < len; i++) {
            float x = tmp[i];
            float y = mem0 + x;
            mem0 = mem1 + y - 2 * x;
            mem1 = x - 0.5f * y;
            tmp[i] = y;
        }
        // The filter memory is not carried between frames, so the first samples
        // are its startup transient rather than the signal's.
        for (int i = 0; i < 12; i++) {
            tmp[i] = 0;
        }

        int block = overlap / 2;
        int blocks = len / block;
        float[] bins = transientBins;
        for (int i = 0; i < blocks; i++) {
            float maxAbs = 0;
            for (int j = 0; j < block; j++) {
                maxAbs = Math.max(maxAbs, Math.abs(tmp[i * block + j]));
            }
            bins[i] = maxAbs;
        }

        boolean isTransient = false;
        for (int i = 0; i < blocks; i++) {
            float t1 = 0.15f * bins[i];
            float t2 = 0.4f * bins[i];
            float t3 = 0.15f * bins[i];
            int conseq = 0;
            for (int j = 0; j < i; j++) {
                if (bins[j] < t1) {
                    conseq++;
                }
                if (bins[j] < t2) {
                    conseq++;
                } else {
                    conseq = 0;
                }
            }
            if (conseq >= 3) {
                isTransient = true;
            }
            conseq = 0;
            for (int j = i + 1; j < blocks; j++) {
                if (bins[j] < t3) {
                    conseq++;
                } else {
                    conseq = 0;
                }
            }
            if (conseq >= 7) {
                isTransient = true;
            }
        }
        return isTransient;
    }

    /**
     * {@code compute_mdcts} in {@code celt/celt.c}.
     *
     * <p>One transform for a normal frame, {@code 1 << LM} short ones for a
     * transient frame with their coefficients interleaved, which is the layout
     * the band structure and the decoder's inverse both expect.
     */
    private void computeMdcts(int shortBlocks, int lm, int n, int channelStride) {
        int blockSize = shortBlocks != 0 ? CeltMode.SHORT_MDCT_SIZE : n;
        int blockCount = shortBlocks != 0 ? shortBlocks : 1;
        int shift = shortBlocks != 0 ? MAX_LM : MAX_LM - lm;
        for (int c = 0; c < channels; c++) {
            for (int b = 0; b < blockCount; b++) {
                mdct.forward(in, c * channelStride + b * blockSize,
                        freq, c * n + b, blockSize, blockCount, shift, window, overlap);
            }
        }
    }

    /** {@code compute_band_energies} in {@code celt/bands.c}, float build. */
    private void computeBandEnergies(CeltMode mode, int n, int effEnd) {
        for (int c = 0; c < channels; c++) {
            for (int i = 0; i < effEnd; i++) {
                // 1e-27 rather than zero so that a wholly silent band still has
                // a finite reciprocal to normalise by.
                float sum = 1e-27f;
                int to = mode.bandEnd(i);
                for (int j = mode.bandStart(i); j < to; j++) {
                    float v = freq[c * n + j];
                    sum += v * v;
                }
                bandAmplitude[i + c * bands] = (float) Math.sqrt(sum);
            }
        }
    }

    /** {@code amp2Log2} in {@code celt/quant_bands.c}. */
    private void amp2Log2(int effEnd, int end) {
        for (int c = 0; c < channels; c++) {
            for (int i = 0; i < effEnd; i++) {
                bandLogE[i + c * bands] =
                        CeltTables.log2(bandAmplitude[i + c * bands]) - E_MEANS[i];
            }
            for (int i = effEnd; i < end; i++) {
                bandLogE[i + c * bands] = -14.0f;
            }
        }
    }

    /** {@code normalise_bands} in {@code celt/bands.c}, float build. */
    private void normaliseBands(CeltMode mode, int n, int effEnd) {
        for (int c = 0; c < channels; c++) {
            for (int i = 0; i < effEnd; i++) {
                float g = 1.0f / (1e-27f + bandAmplitude[i + c * bands]);
                int to = mode.bandEnd(i);
                for (int j = mode.bandStart(i); j < to; j++) {
                    spectrum[c * n + j] = freq[c * n + j] * g;
                }
            }
        }
    }

    /**
     * {@code tf_analysis} in {@code celt/celt.c}: RFC 6716 section 4.3.1.
     *
     * <p>Each band is measured at every time-frequency resolution the frame
     * allows, by counting the L1 norm of its coefficients -- a sparse band has a
     * small L1 norm for the same energy, and sparse is cheap to code. The
     * cheapest resolution per band would flip back and forth from band to band,
     * so a Viterbi pass over the whole frame trades a little sparsity for fewer
     * changes, since each change costs a bit.
     *
     * <p>Fills {@link #tfRes} with a 0/1 flag per band, which {@link #tfEncode}
     * writes and then maps through the tf_select table.
     *
     * <p>Upstream also hands back {@code tf_sum}, the total of the per-band
     * preferences. Nothing but its VBR rate target reads it, and this encoder is
     * constant bitrate, so it is not computed here rather than computed and left
     * unread.
     */
    private void tfAnalysis(CeltMode mode, int len, boolean isTransient, int nbCompressedBytes,
            int n, int lm) {
        if (nbCompressedBytes < 15 * channels) {
            // Not enough bits for the flags to pay for themselves.
            for (int i = 0; i < len; i++) {
                tfRes[i] = isTransient ? 1 : 0;
            }
            return;
        }
        int lambda;
        if (nbCompressedBytes < 40) {
            lambda = 12;
        } else if (nbCompressedBytes < 60) {
            lambda = 6;
        } else if (nbCompressedBytes < 100) {
            lambda = 4;
        } else {
            lambda = 3;
        }

        for (int i = 0; i < len; i++) {
            int bandN = mode.bandWidth(i);
            int start = mode.bandStart(i);
            for (int j = 0; j < bandN; j++) {
                tfScratch[j] = spectrum[start + j];
            }
            if (channels == 2) {
                for (int j = 0; j < bandN; j++) {
                    tfScratch[j] += spectrum[n + start + j];
                }
            }
            float best = l1Metric(tfScratch, bandN, isTransient ? lm : 0, bandN >> lm);
            int bestLevel = 0;
            for (int k = 0; k < lm; k++) {
                int b = isTransient ? lm - k - 1 : k + 1;
                if (isTransient) {
                    CeltBands.haar1(tfScratch, 0, bandN >> (lm - k), 1 << (lm - k));
                } else {
                    CeltBands.haar1(tfScratch, 0, bandN >> k, 1 << k);
                }
                float value = l1Metric(tfScratch, bandN, b, bandN >> lm);
                if (value < best) {
                    best = value;
                    bestLevel = k + 1;
                }
            }
            tfMetric[i] = isTransient ? bestLevel : -bestLevel;
        }

        // tf_select is left at zero. The reference's own note says so: "Future
        // optimized implementations could detect extreme transients and set
        // tf_select = 1 but so far we have not found a reliable way of making
        // this useful."
        int tfSelect = 0;
        int cost0 = 0;
        int cost1 = isTransient ? 0 : lambda;
        for (int i = 1; i < len; i++) {
            int from0 = cost0;
            int from1 = cost1 + lambda;
            int curr0;
            if (from0 < from1) {
                curr0 = from0;
                tfPath0[i] = 0;
            } else {
                curr0 = from1;
                tfPath0[i] = 1;
            }
            from0 = cost0 + lambda;
            from1 = cost1;
            int curr1;
            if (from0 < from1) {
                curr1 = from0;
                tfPath1[i] = 0;
            } else {
                curr1 = from1;
                tfPath1[i] = 1;
            }
            cost0 = curr0 + Math.abs(tfMetric[i]
                    - CeltTables.tfAdjustment(lm, isTransient, tfSelect, 0));
            cost1 = curr1 + Math.abs(tfMetric[i]
                    - CeltTables.tfAdjustment(lm, isTransient, tfSelect, 1));
        }
        tfRes[len - 1] = cost0 < cost1 ? 0 : 1;
        for (int i = len - 2; i >= 0; i--) {
            tfRes[i] = tfRes[i + 1] == 1 ? tfPath1[i + 1] : tfPath0[i + 1];
        }
    }

    /**
     * {@code l1_metric} in {@code celt/celt.c}: the L1 norm across sub-blocks,
     * biased against splitting.
     *
     * <p>The bias is what stops a band being split for a saving too small to pay
     * for the flag that signals it, and it is larger for narrow bands because a
     * narrow band's measurement is noisier.
     */
    private static float l1Metric(float[] x, int n, int lm, int width) {
        float l1 = 0;
        for (int i = 0; i < 1 << lm; i++) {
            float l2 = 0;
            for (int j = 0; j < n >> lm; j++) {
                float v = x[(j << lm) + i];
                l2 += v * v;
            }
            l1 += (float) Math.sqrt(l2);
        }
        l1 = SQRT_M_INVERSE[lm] * l1;
        float bias;
        if (width == 1) {
            bias = 0.12f * lm;
        } else if (width == 2) {
            bias = 0.05f * lm;
        } else {
            bias = 0.02f * lm;
        }
        return l1 + bias * l1;
    }

    /**
     * {@code tf_encode} in {@code celt/celt.c}.
     *
     * <p>Writes the per-band flags as differences from the band below, so a frame
     * whose bands all agree costs one expensive bit and then nothing, and rewrites
     * {@link #tfRes} into the actual resolution adjustments the band quantiser
     * needs. The tf_select bit is only written when the two tables would give
     * different answers for the flags actually sent -- otherwise it would carry
     * nothing and the reserved space goes back to the bands.
     */
    private void tfEncode(int start, int end, boolean isTransient, int lm, int totalBits) {
        int budget = totalBits;
        int tell = enc.tell();
        int logp = isTransient ? 2 : 4;
        int tfSelectRsv = lm > 0 && tell + logp + 1 <= budget ? 1 : 0;
        budget -= tfSelectRsv;
        int curr = 0;
        int changed = 0;
        for (int i = start; i < end; i++) {
            if (tell + logp <= budget) {
                enc.encodeBit(tfRes[i] ^ curr, logp);
                tell = enc.tell();
                curr = tfRes[i];
                changed |= curr;
            } else {
                tfRes[i] = curr;
            }
            logp = isTransient ? 4 : 5;
        }
        int tfSelect = 0;
        if (tfSelectRsv != 0
                && CeltTables.tfAdjustment(lm, isTransient, 0, changed)
                        != CeltTables.tfAdjustment(lm, isTransient, 1, changed)) {
            enc.encodeBit(tfSelect, 1);
        }
        for (int i = start; i < end; i++) {
            tfRes[i] = CeltTables.tfAdjustment(lm, isTransient, tfSelect, tfRes[i]);
        }
    }

    /**
     * {@code spreading_decision} in {@code celt/bands.c}: RFC 6716 section
     * 4.3.4.3.
     *
     * <p>Counts, per band, how many coefficients are far below the band's own
     * average. A tonal band has a few large coefficients and many tiny ones and
     * wants little or no rotation, because rotating would smear a pure tone into
     * its neighbours; a noisy band is flat and wants aggressive rotation, because
     * without it the handful of pulses the allocator can afford would be heard as
     * isolated whistles rather than as noise.
     *
     * <p>The measure is averaged over frames and then run through a hysteresis so
     * that the decision does not flip every frame. It flipping is audible: the
     * rotation changes the character of the noise floor, and alternating between
     * two characters at 50 Hz is a flutter.
     */
    private int spreadingDecision(CeltMode mode, int end, int n) {
        if (mode.bandWidth(end - 1) <= 8) {
            return SPREAD_NONE;
        }
        int sum = 0;
        int nbBands = 0;
        for (int c = 0; c < channels; c++) {
            for (int i = 0; i < end; i++) {
                int bandN = mode.bandWidth(i);
                if (bandN <= 8) {
                    continue;
                }
                int base = c * n + mode.bandStart(i);
                int t0 = 0;
                int t1 = 0;
                int t2 = 0;
                for (int j = 0; j < bandN; j++) {
                    float v = spectrum[base + j];
                    float x2n = v * v * bandN;
                    if (x2n < 0.25f) {
                        t0++;
                    }
                    if (x2n < 0.0625f) {
                        t1++;
                    }
                    if (x2n < 0.015625f) {
                        t2++;
                    }
                }
                int tmp = (2 * t2 >= bandN ? 1 : 0) + (2 * t1 >= bandN ? 1 : 0)
                        + (2 * t0 >= bandN ? 1 : 0);
                sum += tmp * 256;
                nbBands++;
            }
        }
        // Upstream also keeps a high-frequency tonality average here and picks a
        // post-filter tap set from it, under "if (update_hf)". update_hf is
        // pf_on && !shortBlocks and this encoder's pf_on is always zero, so that
        // branch is never taken upstream either and the two fields it maintains
        // are not carried here.
        if (nbBands == 0) {
            return SPREAD_NORMAL;
        }
        sum /= nbBands;
        sum = (sum + tonalAverage) >> 1;
        tonalAverage = sum;
        sum = (3 * sum + (((3 - spreadDecision) << 7) + 64) + 2) >> 2;
        if (sum < 80) {
            return SPREAD_AGGRESSIVE;
        }
        if (sum < 256) {
            return SPREAD_NORMAL;
        }
        if (sum < 384) {
            return SPREAD_LIGHT;
        }
        return SPREAD_NONE;
    }

    /**
     * The band-boost pass of {@code celt_encode_with_ec}.
     *
     * <p>Finds bands that stand out from both neighbours by more than 2 or 4
     * steps of 6 dB and marks them for extra bits. What this is for is a narrow
     * tone in an otherwise quiet region: the allocator's static curve gives that
     * band the same share as its silent neighbours, and a tone coded with two
     * pulses is heard as a warble at the frame rate rather than as a note.
     */
    private void dynallocAnalysis(int start, int end, int lm, int effectiveBytes) {
        if (effectiveBytes <= 50 || lm < 1) {
            return;
        }
        int t1 = lm <= 1 ? 3 : 2;
        int t2 = lm <= 1 ? 5 : 4;
        for (int i = start + 1; i < end - 1; i++) {
            float d2 = 2 * bandLogE[i] - bandLogE[i - 1] - bandLogE[i + 1];
            if (channels == 2) {
                d2 = 0.5f * (d2 + 2 * bandLogE[i + bands]
                        - bandLogE[i - 1 + bands] - bandLogE[i + 1 + bands]);
            }
            if (d2 > t1) {
                offsets[i] += 1;
            }
            if (d2 > t2) {
                offsets[i] += 1;
            }
        }
    }

    /**
     * {@code alloc_trim_analysis} in {@code celt/celt.c}: RFC 6716 section 4.3.3.
     *
     * <p>Returns 0 to 10, five meaning no tilt. The direction is worth stating
     * because RFC 6716 section 4.3.3 states it backwards: a <em>lower</em> trim
     * moves bits towards the high bands and a higher trim towards the low ones.
     * {@code interp_bits2pulses} in {@code celt/rate.c} is the authority --
     * {@code trim_offset[j] = C*(eBands[j+1]-eBands[j])*(alloc_trim-5-LM)*...}
     * is subtracted from the band's allocation and {@code eBands[j+1]-eBands[j]}
     * grows with frequency, so a trim above 5 takes bits away from the widest,
     * highest bands. This function reduces the trim when the channels correlate
     * (so a stereo image that is nearly mono spends its saving up high) and when
     * the spectrum tilts down, which is exactly the sense the reference intends
     * and the opposite of what the prose says.
     */
    private int allocTrimAnalysis(CeltMode mode, int end, int lm, int n) {
        int trimIndex = 5;
        if (channels == 2) {
            float sum = 0;
            for (int i = 0; i < 8; i++) {
                float partial = 0;
                int to = mode.bandEnd(i);
                for (int j = mode.bandStart(i); j < to; j++) {
                    partial += spectrum[j] * spectrum[n + j];
                }
                sum += partial;
            }
            sum = (1.0f / 8) * sum;
            if (sum > 0.995f) {
                trimIndex -= 4;
            } else if (sum > 0.92f) {
                trimIndex -= 3;
            } else if (sum > 0.85f) {
                trimIndex -= 2;
            } else if (sum > 0.8f) {
                trimIndex -= 1;
            }
        }

        float diff = 0;
        for (int c = 0; c < channels; c++) {
            for (int i = 0; i < end - 1; i++) {
                diff += bandLogE[i + c * bands] * (2 + 2 * i - bands);
            }
        }
        // The divide by two is the reference's, and its comment explains it:
        // "We divide by two here to avoid making the tilt larger for stereo as a
        // result of a bug in the loop above."
        diff /= 2 * channels * (end - 1);
        if (diff > 2.0f) {
            trimIndex--;
        }
        if (diff > 8.0f) {
            trimIndex--;
        }
        if (diff < -4.0f) {
            trimIndex++;
        }
        if (diff < -10.0f) {
            trimIndex++;
        }
        return Math.max(0, Math.min(10, trimIndex));
    }

    /**
     * {@code stereo_analysis} in {@code celt/celt.c}: mid/side or left/right.
     *
     * <p>Compares the L1 norm of the left/right pair against the L1 norm of the
     * mid/side pair, which models which of the two is cheaper to code, and
     * charges mid/side for the extra angle per band it has to send. Wide stereo
     * -- two nearly independent channels -- costs more as mid/side than as
     * left/right, and a mono-ish recording costs far less.
     *
     * @return 1 to code the channels separately, 0 to code them jointly
     */
    private int stereoAnalysis(CeltMode mode, int lm, int n) {
        float sumLr = EPSILON;
        float sumMs = EPSILON;
        for (int i = 0; i < 13; i++) {
            int to = mode.bandEnd(i);
            for (int j = mode.bandStart(i); j < to; j++) {
                float l = spectrum[j];
                float r = spectrum[n + j];
                sumLr += Math.abs(l) + Math.abs(r);
                sumMs += Math.abs(l + r) + Math.abs(l - r);
            }
        }
        sumMs = 0.707107f * sumMs;
        int thetas = 13;
        // Below 5 ms the lowest eight bands are one bin each and have no angle.
        if (lm <= 1) {
            thetas -= 8;
        }
        int width = mode.bandStart(13) << 1;
        return (width + thetas) * sumMs > width * sumLr ? 1 : 0;
    }

    /**
     * {@code quant_all_bands} in {@code celt/bands.c}, encode side: RFC 6716
     * section 4.3.4.
     *
     * <p>Walks the coded bands upwards, hands each the share of the frame the
     * allocator set aside plus whatever the bands below did not spend, and codes
     * its shape.
     *
     * <p>Without resynthesis there is no folding source, so this is shorter than
     * its decode counterpart by the whole {@code lowband} apparatus: the
     * reference guards every line of it with {@code resynth}, which is zero on
     * the encode path of the RFC's own build. The decoder folds from what it
     * decoded and the encoder never has to agree with it, because the fold is a
     * function of the bitstream alone.
     */
    private void quantAllBands(CeltMode mode, int start, int end, int n, int[] bandPulses,
            int shortBlocks, int spreadValue, int dualStereo, int intensityBand,
            int[] tfChangePerBand, int totalBits, int balanceIn, int lm, int codedBands) {
        this.spread = spreadValue;
        this.intensity = intensityBand;
        int blocks = shortBlocks != 0 ? 1 << lm : 1;
        boolean stereo = channels == 2;
        int dual = dualStereo;
        int balance = balanceIn;

        for (int i = start; i < end; i++) {
            int bandStart = mode.bandStart(i);
            int bandN = mode.bandWidth(i);
            int xo = bandStart;
            int yo = n + bandStart;

            int tell = enc.tellFrac();
            if (i != start) {
                balance -= tell;
            }
            remainingBits = totalBits - tell - 1;
            int b;
            if (i <= codedBands - 1) {
                int currBalance = balance / Math.min(3, codedBands - i);
                b = Math.max(0, Math.min(16383,
                        Math.min(remainingBits + 1, bandPulses[i] + currBalance)));
            } else {
                b = 0;
            }

            if (dual != 0 && i == intensityBand) {
                dual = 0;
            }

            // Every short block is treated as filled, because without a folding
            // source the fill mask can only come from the noise generator, which
            // fills all of them.
            int fill = (1 << blocks) - 1;
            if (dual != 0) {
                quantBand(i, spectrum, xo, null, 0, bandN, b / 2, blocks,
                        tfChangePerBand[i], lm, 0, fill);
                quantBand(i, spectrum, yo, null, 0, bandN, b / 2, blocks,
                        tfChangePerBand[i], lm, 0, fill);
            } else {
                quantBand(i, spectrum, xo, stereo ? spectrum : null, yo, bandN, b, blocks,
                        tfChangePerBand[i], lm, 0, fill);
            }
            balance += bandPulses[i] + tell;
        }
    }

    /**
     * {@code quant_band} in {@code celt/bands.c}, encode side: one band, or one
     * half of a band that was split.
     *
     * <p>Recursive. A band splits when it would need about 1.5 bits more than its
     * largest codebook can express, and each split codes an angle saying how the
     * energy divides between the halves; a 20 ms band can end up in eight pieces.
     * The stereo case is the same machinery with the two channels as the two
     * halves.
     *
     * <p>Rewrites the coefficients it is given, in place, exactly as the
     * reference does -- the Haar stages, the Hadamard reordering, the mid/side
     * split and the spreading rotation are all applied to the normalised
     * spectrum rather than to a copy. That is why nothing may read
     * {@link #spectrum} after this runs, and why every analysis that needs the
     * normalised spectrum happens before it.
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
     * @param lm       the frame size index at this level of the recursion
     * @param level    recursion depth; only level 0 does the block shuffling
     * @param fill     which short blocks of the folding source hold anything
     */
    private void quantBand(int i, float[] xa, int xo, float[] ya, int yo, int n, int b,
            int bb, int tfChange, int lm, int level, int fill) {
        int nb = n / bb;
        int b0 = bb;
        boolean longBlocks = b0 == 1;
        boolean stereo = ya != null;
        boolean split = stereo;
        int recombine = 0;

        if (n == 1) {
            // A one-bin band has no shape beyond its sign, and the sign is a raw
            // bit rather than a range-coded symbol.
            float[] arr = xa;
            int off = xo;
            for (int c = 0; c < (stereo ? 2 : 1); c++) {
                if (remainingBits >= 1 << BIT_RES) {
                    enc.encodeRawBits(arr[off] < 0 ? 1 : 0, 1);
                    remainingBits -= 1 << BIT_RES;
                }
                arr = ya;
                off = yo;
            }
            return;
        }

        if (!stereo && level == 0) {
            if (tfChange > 0) {
                recombine = tfChange;
            }
            for (int k = 0; k < recombine; k++) {
                CeltBands.haar1(xa, xo, n >> k, 1 << k);
                fill = BIT_INTERLEAVE[fill & 0xF] | BIT_INTERLEAVE[(fill >> 4) & 0xF] << 2;
            }
            bb >>= recombine;
            nb <<= recombine;

            while ((nb & 1) == 0 && tfChange < 0) {
                CeltBands.haar1(xa, xo, nb, bb);
                fill |= fill << bb;
                bb <<= 1;
                nb >>= 1;
                tfChange++;
            }
            b0 = bb;

            if (b0 > 1) {
                deinterleaveHadamard(xa, xo, nb >> recombine, b0 << recombine, longBlocks);
            }
        }

        if (!stereo && lm != -1 && n > 2) {
            if (b > CeltBands.splitThreshold(i, lm) + 12) {
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
            int pulseCap = CeltTables.logN(i) + lm * (1 << BIT_RES);
            int offset = (pulseCap >> 1)
                    - (stereo && n == 2 ? QTHETA_OFFSET_TWOPHASE : QTHETA_OFFSET);
            int qn = CeltBands.computeQn(n, b, offset, pulseCap, stereo);
            if (stereo && i >= intensity) {
                // At and above the intensity band the side is not coded at all,
                // so there is no angle to send.
                qn = 1;
            }
            // The angle of the (normalised) side against the (normalised) mid.
            // Both have unit norm and they are orthogonal, so this one number
            // rescales both halves.
            int itheta = stereoItheta(xa, xo, ya, yo, stereo, n);
            int tell = enc.tellFrac();
            int inv = 0;
            if (qn != 1) {
                itheta = (itheta * qn + 8192) >> 14;
                if (stereo && n > 2) {
                    // A step PDF: angles in the lower half are three times as
                    // likely as the rest, because a stereo image usually leans
                    // towards the middle.
                    int p0 = 3;
                    int x = itheta;
                    int x0 = qn / 2;
                    int ft = p0 * (x0 + 1) + x0;
                    enc.encode(x <= x0 ? p0 * x : (x - 1 - x0) + (x0 + 1) * p0,
                            x <= x0 ? p0 * (x + 1) : (x - x0) + (x0 + 1) * p0, ft);
                } else if (b0 > 1 || stereo) {
                    enc.encodeUniform(itheta, qn + 1);
                } else {
                    // A triangular PDF: a mono time split most often divides its
                    // energy evenly between the two halves.
                    int ft = ((qn >> 1) + 1) * ((qn >> 1) + 1);
                    int fs = itheta <= (qn >> 1) ? itheta + 1 : qn + 1 - itheta;
                    int fl = itheta <= (qn >> 1) ? itheta * (itheta + 1) >> 1
                            : ft - ((qn + 1 - itheta) * (qn + 2 - itheta) >> 1);
                    enc.encode(fl, fl + fs, ft);
                }
                itheta = itheta * 16384 / qn;
                if (stereo) {
                    if (itheta == 0) {
                        intensityStereo(xa, xo, ya, yo, i, n);
                    } else {
                        stereoSplit(xa, xo, ya, yo, n);
                    }
                }
            } else if (stereo) {
                // No angle, so all that is left to say about the side is whether
                // it is inverted. Getting this wrong flips the phase of one
                // channel of one band, which on headphones is heard as that
                // band's content jumping outside the head.
                inv = itheta > 8192 ? 1 : 0;
                if (inv != 0) {
                    for (int j = 0; j < n; j++) {
                        ya[yo + j] = -ya[yo + j];
                    }
                }
                intensityStereo(xa, xo, ya, yo, i, n);
                if (b > 2 << BIT_RES && remainingBits > 2 << BIT_RES) {
                    enc.encodeBit(inv, 2);
                }
                itheta = 0;
            }
            int qalloc = enc.tellFrac() - tell;
            b -= qalloc;

            int origFill = fill;
            int imid;
            int iside;
            int delta;
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
                imid = CeltBands.bitexactCos(itheta);
                iside = CeltBands.bitexactCos(16384 - itheta);
                // The mid/side split that minimises squared error in this band.
                delta = CeltBands.fracMul16((n - 1) << 7,
                        CeltBands.bitexactLog2Tan(iside, imid));
            }

            if (n == 2 && stereo) {
                // Two coefficients per channel: mid and side are orthogonal, so
                // the whole of the side is one sign bit.
                int mbits = b;
                int sbits = itheta != 0 && itheta != 16384 ? 1 << BIT_RES : 0;
                mbits -= sbits;
                int c = itheta > 8192 ? 1 : 0;
                remainingBits -= qalloc + sbits;

                float[] x2a = c != 0 ? ya : xa;
                int x2o = c != 0 ? yo : xo;
                float[] y2a = c != 0 ? xa : ya;
                int y2o = c != 0 ? xo : yo;
                int sign = 0;
                if (sbits != 0) {
                    sign = x2a[x2o] * y2a[y2o + 1] - x2a[x2o + 1] * y2a[y2o] < 0 ? 1 : 0;
                    enc.encodeRawBits(sign, 1);
                }
                int signed = 1 - 2 * sign;
                // origFill, not fill: the side still wants to fold even when the
                // angle is hard over and the low bits of fill were cleared.
                quantBand(i, x2a, x2o, null, 0, n, mbits, bb, tfChange, lm, level, origFill);
                y2a[y2o] = -signed * x2a[x2o + 1];
                y2a[y2o + 1] = signed * x2a[x2o];
            } else {
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
                int mbits = Math.max(0, Math.min(b, (b - delta) / 2));
                int sbits = b - mbits;
                remainingBits -= qalloc;
                int nextLevel = stereo ? 0 : level + 1;

                int rebalance = remainingBits;
                if (mbits >= sbits) {
                    quantBand(i, xa, xo, null, 0, n, mbits, bb, tfChange, lm, nextLevel, fill);
                    rebalance = mbits - (rebalance - remainingBits);
                    if (rebalance > 3 << BIT_RES && itheta != 0) {
                        sbits += rebalance - (3 << BIT_RES);
                    }
                    quantBand(i, ya, yo, null, 0, n, sbits, bb, tfChange, lm, nextLevel,
                            fill >> bb);
                } else {
                    quantBand(i, ya, yo, null, 0, n, sbits, bb, tfChange, lm, nextLevel,
                            fill >> bb);
                    rebalance = sbits - (rebalance - remainingBits);
                    if (rebalance > 3 << BIT_RES && itheta != 16384) {
                        mbits += rebalance - (3 << BIT_RES);
                    }
                    quantBand(i, xa, xo, null, 0, n, mbits, bb, tfChange, lm, nextLevel, fill);
                }
            }
        } else {
            int q = CeltBands.bits2pulses(i, lm, b);
            int currBits = CeltBands.pulses2bits(i, lm, q);
            remainingBits -= currBits;

            // Walk the pulse count down until the frame can actually pay for it.
            // The allocator works from an estimate; this loop is what guarantees
            // the frame never busts, however the rebalancing above landed.
            while (remainingBits < 0 && q > 0) {
                remainingBits += currBits;
                q--;
                currBits = CeltBands.pulses2bits(i, lm, q);
                remainingBits -= currBits;
            }

            if (q != 0) {
                algQuant(xa, xo, n, CeltBands.getPulses(q), bb);
            }
            // With no pulses there is nothing to send: the decoder fills the
            // band from its folding source or from noise, and both ends know it
            // without a symbol passing between them.
        }
    }

    /**
     * {@code alg_quant} in {@code celt/vq.c}: RFC 6716 section 4.3.4.2.
     *
     * <p>Finds the K-pulse unit vector closest in direction to the band, and
     * writes its codebook index. Two stages, and the second is the one that
     * matters. The first projects the target onto the pyramid and takes the
     * floor, which places most of the pulses in one pass but always places
     * slightly too few. The second adds the remaining pulses one at a time,
     * each time to whichever position maximises the correlation of the
     * <em>whole</em> vector with the target -- not to whichever position has the
     * largest residual.
     *
     * <p>That distinction is the whole algorithm and it is invisible to any
     * correctness test. Placing each pulse where the residual is largest is a
     * greedy match on the difference; placing it where {@code (x.y)^2 / (y.y)}
     * is largest is a greedy match on the <em>angle</em>, which is what the
     * codebook actually encodes, because the decoder renormalises whatever
     * pulse vector it receives back to unit norm. The two agree on the first
     * pulse and diverge after it, and the difference is worth a decibel or two
     * of band SNR on exactly the low-pulse-count bands where there is least to
     * spare. A round trip cannot see it: both choices produce a legal codeword
     * that decodes without complaint.
     *
     * <p>The comparison itself is a cross-multiply rather than a division, so
     * the search is exact in floating point and does not depend on the order the
     * candidates happen to be visited in.
     */
    private void algQuant(float[] xa, int xo, int n, int k, int bb) {
        if (k <= 0 || n < 2) {
            throw new IllegalStateException("alg_quant needs at least one pulse and two"
                    + " dimensions, and was given " + k + " and " + n);
        }
        expRotation(xa, xo, n, 1, bb, k);

        float[] x = pvqTarget;
        float[] y = pvqSum;
        int[] iy = pulseVector;
        int[] signx = pvqSign;
        float sum = 0;
        for (int j = 0; j < n; j++) {
            float v = xa[xo + j];
            if (v > 0) {
                signx[j] = 1;
                x[j] = v;
            } else {
                signx[j] = -1;
                x[j] = -v;
            }
            iy[j] = 0;
            y[j] = 0;
        }

        float xy = 0;
        float yy = 0;
        int pulsesLeft = k;

        if (k > (n >> 1)) {
            for (int j = 0; j < n; j++) {
                sum += x[j];
            }
            // A band of all zeros, or one holding an infinity or a NaN that
            // came in from a broken input, would otherwise divide by nothing
            // and place every pulse in the first bin at once. 64 stands in for
            // infinity here, as the reference's comment says.
            if (!(sum > EPSILON && sum < 64)) {
                x[0] = 1.0f;
                for (int j = 1; j < n; j++) {
                    x[j] = 0;
                }
                sum = 1.0f;
            }
            float rcp = (k - 1) * (1.0f / sum);
            for (int j = 0; j < n; j++) {
                // Towards zero, always: rounding to nearest here would place
                // more pulses than the codebook has and the refinement below
                // has no way to take one back.
                iy[j] = (int) Math.floor(rcp * x[j]);
                y[j] = iy[j];
                yy += y[j] * y[j];
                xy += x[j] * y[j];
                // Doubled once here so the inner loop below does not have to.
                y[j] *= 2;
                pulsesLeft -= iy[j];
            }
        }

        if (pulsesLeft > n + 3) {
            // Cannot happen for a normalised band, and the reference says so;
            // it is here because a band that somehow arrived denormalised would
            // otherwise spend n+3 iterations of the refinement loop per pulse.
            float tmp = pulsesLeft;
            yy += tmp * tmp;
            yy += tmp * y[0];
            iy[0] += pulsesLeft;
            pulsesLeft = 0;
        }

        for (int p = 0; p < pulsesLeft; p++) {
            int bestId = 0;
            float bestNum = -VERY_LARGE;
            float bestDen = 0;
            // The squared magnitude of the new pulse is added whichever position
            // wins, so it comes out of the loop.
            yy = yy + 1;
            for (int j = 0; j < n; j++) {
                float rxy = xy + x[j];
                float ryy = yy + y[j];
                // Maximise Rxy/sqrt(Ryy); squaring the numerator lets the
                // comparison be a cross-multiply with no square root and no
                // division. Rxy is non-negative because the signs were stripped.
                rxy = rxy * rxy;
                if (bestDen * rxy > ryy * bestNum) {
                    bestDen = ryy;
                    bestNum = rxy;
                    bestId = j;
                }
            }
            xy = xy + x[bestId];
            yy = yy + y[bestId];
            y[bestId] += 2;
            iy[bestId]++;
        }

        // Only the pulse vector needs its signs put back. The reference also
        // restores them into X, because it stripped them there; this took the
        // magnitudes into a scratch buffer instead, so the band's coefficients
        // are already the signed rotated values the reference ends up with and
        // the second pass over them would be a no-op.
        for (int j = 0; j < n; j++) {
            if (signx[j] < 0) {
                iy[j] = -iy[j];
            }
        }
        long index = Pvq.encodePulses(iy, n, k);
        enc.encodeUniformWide(index, Pvq.vectorCount(n, k));
    }

    /**
     * {@code exp_rotation} in {@code celt/vq.c}: RFC 6716 section 4.3.4.3.
     *
     * <p>The encoder rotates forwards before the search and the decoder rotates
     * backwards after it, so what the pulses actually approximate is the rotated
     * band. Without it, a band coded with two pulses across forty coefficients
     * decodes to two isolated spikes, heard as a metallic ringing; the rotation
     * turns the same two pulses into something with a plausible spectral shape.
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
     * {@code celt_cos_norm} in {@code celt/mathops.h}: cos of a quarter turn per
     * unit.
     *
     * <p>{@code StrictMath}, not {@code Math}, for the same reason
     * {@link CeltBands} uses it: this sets the rotation the whole band is coded
     * through, and a one-ulp difference between JVMs would make the same input
     * produce different bytes on different machines.
     */
    private static float celtCosNorm(float x) {
        return (float) StrictMath.cos(HALF_PI * x);
    }

    /**
     * {@code stereo_itheta} in {@code celt/vq.c}.
     *
     * <p>The angle whose tangent is the ratio of the side energy to the mid
     * energy, scaled so that 16384 is a quarter turn. Zero means the two
     * channels are identical and the side need not be coded at all; 16384 means
     * they are exactly out of phase.
     */
    private static int stereoItheta(float[] xa, int xo, float[] ya, int yo,
            boolean stereo, int n) {
        float emid = EPSILON;
        float eside = EPSILON;
        if (stereo) {
            for (int i = 0; i < n; i++) {
                float m = xa[xo + i] + ya[yo + i];
                float s = xa[xo + i] - ya[yo + i];
                emid += m * m;
                eside += s * s;
            }
        } else {
            for (int i = 0; i < n; i++) {
                float m = xa[xo + i];
                float s = ya[yo + i];
                emid += m * m;
                eside += s * s;
            }
        }
        float mid = (float) Math.sqrt(emid);
        float side = (float) Math.sqrt(eside);
        // 0.63662 is 2/pi, which turns the quarter turn atan2 can return into
        // the 0..16384 the bitstream carries.
        return (int) Math.floor(0.5f + 16384 * 0.63662f * StrictMath.atan2(side, mid));
    }

    /**
     * {@code intensity_stereo} in {@code celt/bands.c}.
     *
     * <p>Collapses the two channels of a band to one, weighted by their measured
     * energies so that the single coded shape sits where the louder channel put
     * it. The decoder gives both channels that shape at their own levels, so
     * what survives is the level difference and what is lost is the phase
     * difference. Above about 8 kHz the ear localises on level rather than
     * phase, which is why this is only used on the top bands and why it is worth
     * roughly half the bits of coding the pair.
     */
    private void intensityStereo(float[] xa, int xo, float[] ya, int yo, int band, int n) {
        float left = bandAmplitude[band];
        float right = bandAmplitude[band + bands];
        float norm = EPSILON + (float) Math.sqrt(EPSILON + left * left + right * right);
        float a1 = left / norm;
        float a2 = right / norm;
        for (int j = 0; j < n; j++) {
            float l = xa[xo + j];
            float r = ya[yo + j];
            xa[xo + j] = a1 * l + a2 * r;
            // The side is not coded, so there is no need to compute it.
        }
    }

    /** {@code stereo_split} in {@code celt/bands.c}: left and right to mid and side. */
    private static void stereoSplit(float[] xa, int xo, float[] ya, int yo, int n) {
        for (int j = 0; j < n; j++) {
            float l = SQRT_HALF * xa[xo + j];
            float r = SQRT_HALF * ya[yo + j];
            xa[xo + j] = l + r;
            ya[yo + j] = r - l;
        }
    }

    /** {@code deinterleave_hadamard} in {@code celt/bands.c}: frequency to time order. */
    private void deinterleaveHadamard(float[] xa, int xo, int n0, int stride, boolean hadamard) {
        int n = n0 * stride;
        if (hadamard) {
            for (int i = 0; i < stride; i++) {
                int to = CeltBands.orderyIndex(stride, i) * n0;
                for (int j = 0; j < n0; j++) {
                    shuffle[to + j] = xa[xo + j * stride + i];
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

    @Override
    public String toString() {
        return "CeltEncoder[" + channels + " channels at " + bitrate + " bits per second]";
    }
}
