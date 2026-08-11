package net.chonkbase.assetpack.codec.opus;

import java.util.Arrays;
import java.util.Objects;

/**
 * The CELT layer of an Opus decoder: RFC 6716 section 4.3.
 *
 * <p>A port of {@code celt_decode_with_ec} in {@code celt/celt.c}, together with
 * the helpers it calls there -- {@code tf_decode}, {@code init_caps},
 * {@code compute_inv_mdcts}, {@code comb_filter} and {@code deemphasis} -- and
 * {@code log2Amp} from {@code celt/quant_bands.c}. The entropy layer, the
 * allocator, the energy envelope, the band shapes and the transform live in
 * {@link RangeDecoder}, {@link CeltAllocation}, {@link CeltEnergy},
 * {@link CeltBands} and {@link Mdct}; this is the frame loop that drives them
 * and the state that survives between frames.
 *
 * <p>Everything is allocated in the constructor. A frame decode allocates
 * nothing, so this can sit on an audio thread.
 *
 * <p><b>What state survives a frame, and why.</b> Five things, and every one of
 * them is audible if it is dropped. The previous frames' band energies, because
 * coarse energy is coded as a difference from them. The MDCT overlap, because
 * half of every output sample comes from the previous block and the
 * time-domain aliasing only cancels when both halves are present. The
 * post-filter's period and gain, which are cross-faded from the previous
 * frame's rather than switched. The de-emphasis filter memory, a one-pole
 * integrator whose reset would put a step at every frame boundary. And the
 * folding seed, which the encoder advanced over exactly the same sequence of
 * bands.
 *
 * <p><b>Channel changes.</b> The bitstream may carry a different number of
 * channels from the decoder's, and may change it on any packet, which
 * conformance vector 7 does on nearly half of its. A mono frame in a stereo
 * decoder is duplicated to both outputs and a stereo frame in a mono decoder is
 * summed to the mid; the energy history is folded and mirrored across the switch
 * exactly as {@code celt_decode_with_ec} does, because the encoder predicted
 * from the folded value and a decoder that predicted from either channel alone
 * would put the whole spectrum at the wrong level for several frames after
 * every switch.
 */
public final class CeltDecoder {

    /** {@code DECODE_BUFFER_SIZE} in {@code celt/celt.c}. */
    private static final int DECODE_BUFFER_SIZE = 2048;

    /**
     * {@code MAX_PERIOD} in {@code celt/modes.h}: the longest post-filter delay.
     *
     * <p>Checked against the buffer size in the constructor rather than used as
     * an offset. The reference reaches this by placing {@code out_mem} at
     * {@code decode_mem + DECODE_BUFFER_SIZE - MAX_PERIOD} and the frame at
     * {@code out_mem + MAX_PERIOD - N}, where the two cancel; what does not
     * cancel is the requirement that the history in front of the frame be long
     * enough for the comb filter to read back through it.
     */
    private static final int MAX_PERIOD = 1024;

    /** {@code COMBFILTER_MINPERIOD} in {@code celt/celt.c}. */
    private static final int COMBFILTER_MIN_PERIOD = 15;

    /** {@code maxLM}: the 48 kHz mode's longest frame is eight short blocks. */
    private static final int MAX_LM = 3;

    /** {@code BITRES}. */
    private static final int BIT_RES = CeltMode.BIT_RES;

    /**
     * {@code tapset_icdf} in {@code celt/celt.c}, used with {@code ftb = 2}.
     *
     * <p>Three equally coded post-filter tap sets. Private and never written, so
     * it needs no defensive copy.
     */
    private static final short[] TAPSET_ICDF = {2, 1, 0};

    /** Bits of range-coder precision the tapset symbol is coded with. */
    private static final int TAPSET_ICDF_FTB = 2;

    /**
     * {@code gains[3][3]} in {@code comb_filter}: the three post-filter tap sets.
     *
     * <p>Row 0 is the widest and dullest, row 2 the narrowest and brightest. The
     * encoder picks one from the high-frequency content of the signal, so using
     * the wrong row makes a voice sound either lisping or boxy.
     */
    private static final float[][] COMB_GAINS = {
        {0.3066406250f, 0.2170410156f, 0.1296386719f},
        {0.4638671875f, 0.2680664062f, 0.0f},
        {0.7998046875f, 0.1000976562f, 0.0f}
    };

    /**
     * {@code mode->preemph} for the 48 kHz mode, from
     * {@code static_modes_float.h}.
     *
     * <p>The encoder pre-emphasises with {@code 1 - 0.85 z^-1}; this is the
     * matching de-emphasis. Entries 1 and 3 are the identity at this rate and
     * are kept so the expression reads as the reference writes it.
     */
    private static final float[] PREEMPH = {0.85000610f, 0.0f, 1.0f, 1.0f};

    /**
     * {@code eMeans} in {@code celt/quant_bands.c}: the mean log energy of each
     * band, in units of 6 dB.
     *
     * <p>Subtracted by the encoder before the envelope is coded and added back
     * here, so that the coded residual is centred on zero whatever the material.
     * The top four entries are for band layouts wider than Opus proper uses and
     * are carried so the table matches the reference exactly.
     */
    private static final float[] E_MEANS = {
        6.437500f, 6.250000f, 5.750000f, 5.312500f, 5.062500f,
        4.812500f, 4.500000f, 4.375000f, 4.875000f, 4.687500f,
        4.562500f, 4.437500f, 4.875000f, 4.625000f, 4.312500f,
        4.500000f, 4.375000f, 4.625000f, 4.750000f, 4.437500f,
        3.750000f, 3.750000f, 3.750000f, 3.750000f, 3.750000f
    };

    /** {@code -QCONST16(28.f,DB_SHIFT)}, the log energy a silent band is set to. */
    private static final float SILENCE_LOG_ENERGY = CeltEnergy.SILENCE_LOG_ENERGY;

    private final int channels;
    private final int bands;
    private final int overlap;

    private final Mdct mdct;
    private final float[] window;

    /**
     * The coarse, fine and final energy passes, one per bitstream channel count.
     *
     * <p>Two of them because a stream may change channel count on any packet
     * while {@link #oldBandE} stays the two-channel history the reference keeps.
     * Whichever one a frame uses is handed that history first; see
     * {@link CeltEnergy#adoptEnvelope}.
     */
    private final CeltEnergy monoEnergy;
    private final CeltEnergy stereoEnergy;

    private final CeltAllocation allocation;
    private final CeltBands bandDecoder;

    private final short[] trimIcdf;
    private final short[] spreadIcdf;

    /**
     * {@code _decode_mem}: per channel, {@value #DECODE_BUFFER_SIZE} samples of
     * synthesis history followed by {@code overlap} samples of MDCT tail.
     */
    private final float[] decodeMem;

    /** Stride between channels inside {@link #decodeMem}. */
    private final int memStride;

    /** {@code oldBandE}: the live energy envelope, both channels. */
    private final float[] oldBandE;

    /** {@code oldLogE}: the previous frame's envelope, for the anti-collapse test. */
    private final float[] oldLogE;

    /** {@code oldLogE2}: the frame before that. */
    private final float[] oldLogE2;

    /**
     * {@code backgroundLogE}: a slowly rising floor tracking the quietest the
     * signal has been.
     *
     * <p>Maintained here and read by nothing, and that is deliberate rather than
     * an oversight. The only reader upstream is {@code celt_decode_lost}, whose
     * comfort-noise branch generates its spectrum from this floor after five
     * consecutive lost frames, and that path is not ported; see
     * {@link OpusDecoder} for what a lost frame does instead. Keeping it up to
     * date costs 42 additions per frame and means the state is already correct
     * whenever concealment does land -- a floor that started tracking at the
     * moment of the first loss would sit at whatever the signal happened to be
     * doing, and the comfort noise generated from it would come in at the
     * programme level rather than under it.
     */
    private final float[] backgroundLogE;

    /** {@code preemph_memD}: the de-emphasis integrator, one per channel. */
    private final float[] preemphMem;

    // Per-frame workspaces, fields so that a decode allocates nothing.
    private final float[] spectrum;
    private final float[] freq;
    private final float[] bandAmplitude;
    private final float[] blockOut;
    private final int[] caps;
    private final int[] offsets;
    private final int[] tfRes;
    private final int[] pulses;
    private final int[] fineBits;
    private final int[] finePriority;
    private final byte[] collapseMasks;

    private int postfilterPeriod;
    private int postfilterPeriodOld;
    private float postfilterGain;
    private float postfilterGainOld;
    private int postfilterTapset;
    private int postfilterTapsetOld;

    /** {@code st->rng}: the folding seed, which is the range coder's final state. */
    private int rng;

    /**
     * Builds a decoder and every buffer it will ever need.
     *
     * @param channels 1 or 2 output channels; the bitstream may carry either
     */
    public CeltDecoder(int channels) {
        if (channels != 1 && channels != 2) {
            throw new IllegalArgumentException("CELT decodes 1 or 2 channels, not " + channels);
        }
        CeltMode longest = CeltMode.forLm(MAX_LM);
        // The comb filter reads back as far as T+2 samples before the first
        // sample of the frame, with T up to MAX_PERIOD, and the frame sits at
        // the end of the synthesis buffer. Everything the filter reaches has to
        // be inside the buffer -- this is the whole reason DECODE_BUFFER_SIZE is
        // 2048 rather than the 1080 a frame and its overlap need. Checked once
        // here so that the per-sample indexing in combFilter needs no test of
        // its own, and so that changing either constant fails loudly instead of
        // reading the previous channel's samples as if they were this one's
        // pitch history.
        int reach = DECODE_BUFFER_SIZE - longest.frameSize() - (MAX_PERIOD + 2);
        if (reach < 0) {
            throw new IllegalStateException("the synthesis buffer of " + DECODE_BUFFER_SIZE
                    + " samples leaves the post-filter " + (-reach)
                    + " samples short of its longest reach behind a "
                    + longest.frameSize() + "-sample frame");
        }
        this.channels = channels;
        this.bands = longest.bandCount();
        this.overlap = longest.overlap();
        this.memStride = DECODE_BUFFER_SIZE + overlap;

        this.mdct = new Mdct(longest.frameSize());
        this.window = Mdct.window(overlap);
        this.monoEnergy = new CeltEnergy(longest, 1);
        this.stereoEnergy = new CeltEnergy(longest, 2);
        this.allocation = new CeltAllocation();
        this.bandDecoder = new CeltBands(longest, channels);
        this.trimIcdf = CeltTables.copyTrimIcdf();
        this.spreadIcdf = CeltTables.copySpreadIcdf();

        this.decodeMem = new float[channels * memStride];
        this.oldBandE = new float[2 * bands];
        this.oldLogE = new float[2 * bands];
        this.oldLogE2 = new float[2 * bands];
        this.backgroundLogE = new float[2 * bands];
        this.preemphMem = new float[2];

        int n = longest.frameSize();
        this.spectrum = new float[2 * n];
        this.freq = new float[2 * n];
        this.bandAmplitude = new float[2 * bands];
        this.blockOut = new float[n + overlap];
        this.caps = new int[bands];
        this.offsets = new int[bands];
        this.tfRes = new int[bands];
        this.pulses = new int[bands];
        this.fineBits = new int[bands];
        this.finePriority = new int[bands];
        this.collapseMasks = new byte[2 * bands];

        reset();
    }

    /** How many channels this decoder produces. */
    public int channels() {
        return channels;
    }

    /**
     * The energy envelope the last frame decoded, {@code oldBandE}.
     *
     * <p>Log base 2, one unit per 6 dB, with the per-band mean still subtracted,
     * laid out {@code channel*bandCount + band} and always two channels' worth
     * whatever the packet carried. Copied, because the next frame overwrites it.
     *
     * <p>Exposed so that {@code CeltEncoderTest} can compare it against
     * {@link CeltEncoder#quantisedBandEnergy()}. The two are computed by
     * different code -- {@code unquant_coarse_energy} on this side and
     * {@code quant_coarse_energy_impl} on the other -- running the same
     * recurrence over the same symbols, so they must agree exactly. A difference
     * between them changes no symbol in the frame it happens on and every symbol
     * in the frames after it, which is the hardest shape of fault to localise
     * from the audio alone.
     */
    public float[] bandEnergy() {
        return oldBandE.clone();
    }

    /**
     * Forgets everything, as {@code OPUS_RESET_STATE} does.
     *
     * <p>Clears the synthesis history, the energy envelope, the post-filter and
     * the de-emphasis memory to zero. The reference clears the whole struct from
     * {@code DECODER_RESET_START}, and zero is what every one of those fields
     * starts at.
     */
    public void reset() {
        Arrays.fill(decodeMem, 0.0f);
        Arrays.fill(oldBandE, 0.0f);
        Arrays.fill(oldLogE, 0.0f);
        Arrays.fill(oldLogE2, 0.0f);
        Arrays.fill(backgroundLogE, 0.0f);
        Arrays.fill(preemphMem, 0.0f);
        monoEnergy.reset();
        stereoEnergy.reset();
        postfilterPeriod = 0;
        postfilterPeriodOld = 0;
        postfilterGain = 0.0f;
        postfilterGainOld = 0.0f;
        postfilterTapset = 0;
        postfilterTapsetOld = 0;
        rng = 0;
        bandDecoder.setSeed(0);
    }

    /**
     * Decodes one CELT frame, {@code celt_decode_with_ec}.
     *
     * <p>Writes {@code frameSize} interleaved samples per channel into
     * {@code pcm}, in the range {@code [-1, 1]} before clipping; the reference's
     * float output is scaled the same way, so
     * {@code (short) rint(pcm[i] * 32768)} reproduces its 16-bit output.
     *
     * @param dec            the frame's range decoder, positioned at the start of
     *                       the CELT part of the frame
     * @param frameSize      120, 240, 480 or 960 samples at 48 kHz
     * @param start          the first coded band: 0 for a CELT-only frame, 17 for
     *                       a Hybrid one, where SILK has already coded below 8 kHz
     * @param end            one past the last coded band, from the bandwidth
     * @param streamChannels how many channels the bitstream carries, 1 or 2,
     *                       which need not be this decoder's channel count
     * @param pcm            receives {@code channels*frameSize} interleaved samples
     * @param pcmOffset      where in it to start writing
     * @return the number of samples written per channel, which is {@code frameSize}
     * @throws IllegalArgumentException if the frame size is not one CELT codes,
     *                                  the band range is not inside the layout,
     *                                  the channel count is not 1 or 2, or the
     *                                  output buffer is too small
     */
    public int decode(RangeDecoder dec, int frameSize, int start, int end,
            int streamChannels, float[] pcm, int pcmOffset) {
        Objects.requireNonNull(dec, "dec");
        Objects.requireNonNull(pcm, "pcm");
        CeltMode mode = CeltMode.forFrameSize(frameSize);
        int lm = mode.lm();
        int m = 1 << lm;
        int n = frameSize;

        if (start < 0 || start > end || end > bands) {
            throw new IllegalArgumentException("coded band range [" + start + "," + end
                    + ") is not inside the " + bands + " bands of the CELT layer");
        }
        if (streamChannels != 1 && streamChannels != 2) {
            throw new IllegalArgumentException("a CELT bitstream carries 1 or 2 channels, not "
                    + streamChannels);
        }
        long needed = (long) channels * n;
        if (pcmOffset < 0 || pcm.length - pcmOffset < needed) {
            throw new IllegalArgumentException("the output needs " + needed
                    + " samples at offset " + pcmOffset + " of a " + pcm.length + " array");
        }

        int c = streamChannels;
        int effEnd = Math.min(end, bands);
        int len = dec.frameBytes();

        if (c == 1) {
            // A mono frame predicts from one history, and the two the decoder
            // holds may have drifted apart while the stream was stereo. The
            // louder of the two is the conservative choice: predicting from the
            // quieter one would make the first mono frame after a stereo passage
            // decode too loud, because the coded residual is a difference from
            // whatever the encoder predicted, and the encoder took the maximum.
            for (int i = 0; i < bands; i++) {
                oldBandE[i] = Math.max(oldBandE[i], oldBandE[bands + i]);
            }
        }
        // The two-channel history in oldBandE is the authority; the energy
        // decoder for this frame's channel count takes its share of it.
        CeltEnergy energy = c == 1 ? monoEnergy : stereoEnergy;
        energy.adoptEnvelope(oldBandE);

        int lowEdge = bandEdge(mode, start);
        int highEdge = bandEdge(mode, effEnd);

        // The spectrum outside the coded bands is never written by the band
        // decoder, and the transform reads all of it.
        for (int ch = 0; ch < c; ch++) {
            Arrays.fill(spectrum, ch * n, ch * n + lowEdge, 0.0f);
            Arrays.fill(spectrum, ch * n + highEdge, ch * n + n, 0.0f);
        }

        int totalBits = len * 8;
        int tell = dec.tell();

        boolean silence;
        if (tell >= totalBits) {
            silence = true;
        } else if (tell == 1) {
            silence = dec.decodeBit(15) != 0;
        } else {
            silence = false;
        }
        if (silence) {
            // Charge the frame for everything it has left, so that every "is
            // there room for this symbol" test below answers no. See
            // RangeDecoder.chargeRemainingBits.
            dec.chargeRemainingBits();
            tell = totalBits;
        }

        float pfGain = 0.0f;
        int pfPitch = 0;
        int pfTapset = 0;
        if (start == 0 && tell + 16 <= totalBits) {
            if (dec.decodeBit(1) != 0) {
                int octave = dec.decodeUniform(6);
                pfPitch = (16 << octave) + dec.decodeRawBits(4 + octave) - 1;
                int qg = dec.decodeRawBits(3);
                if (dec.tell() + 2 <= totalBits) {
                    pfTapset = dec.decodeIcdf(TAPSET_ICDF, TAPSET_ICDF_FTB);
                }
                pfGain = 0.09375f * (qg + 1);
            }
            tell = dec.tell();
        }

        boolean isTransient = false;
        if (lm > 0 && tell + 3 <= totalBits) {
            isTransient = dec.decodeBit(3) != 0;
            tell = dec.tell();
        }
        int shortBlocks = isTransient ? m : 0;

        boolean intraEnergy = tell + 3 <= totalBits && dec.decodeBit(3) != 0;
        energy.decodeCoarse(dec, start, end, lm, intraEnergy, oldBandE);

        tfDecode(dec, start, end, isTransient, lm);

        tell = dec.tell();
        int spread = CeltBands.SPREAD_NORMAL;
        if (tell + 4 <= totalBits) {
            spread = dec.decodeIcdf(spreadIcdf, CeltTables.SPREAD_ICDF_FTB);
        }

        mode.computeCaps(c, caps);

        // Band boosts, RFC 6716 section 4.3.3. The first flag of each band costs
        // dynalloc_logp bits and every one after it costs one, so a band that
        // wants a lot of extra bits pays for the decision once.
        Arrays.fill(offsets, 0);
        int dynallocLogp = 6;
        int totalEighths = totalBits << BIT_RES;
        int tellFrac = dec.tellFrac();
        for (int i = start; i < end; i++) {
            int width = c * mode.bandWidth(i);
            // Six bits per step, but never more than one bit per sample and
            // never less than an eighth.
            int quanta = Math.min(width << BIT_RES, Math.max(6 << BIT_RES, width));
            int loopLogp = dynallocLogp;
            int boost = 0;
            while (tellFrac + (loopLogp << BIT_RES) < totalEighths && boost < caps[i]) {
                int flag = dec.decodeBit(loopLogp);
                tellFrac = dec.tellFrac();
                if (flag == 0) {
                    break;
                }
                boost += quanta;
                totalEighths -= quanta;
                loopLogp = 1;
            }
            offsets[i] = boost;
            // One band boosted makes the next one cheaper to boost, because
            // dynalloc decisions cluster around a transient or a tone.
            if (boost > 0) {
                dynallocLogp = Math.max(2, dynallocLogp - 1);
            }
        }

        int allocTrim = tellFrac + (6 << BIT_RES) <= totalEighths
                ? dec.decodeIcdf(trimIcdf, CeltTables.TRIM_ICDF_FTB) : 5;

        int bits = ((len * 8) << BIT_RES) - dec.tellFrac() - 1;
        // One eighth-bit held back for the anti-collapse flag, but only on a
        // transient frame long enough for a collapse to be audible.
        int antiCollapseRsv = isTransient && lm >= 2 && bits >= ((lm + 2) << BIT_RES)
                ? (1 << BIT_RES) : 0;
        bits -= antiCollapseRsv;

        int codedBands = allocation.decode(mode, start, end, offsets, caps, allocTrim,
                bits, c, dec);
        for (int i = 0; i < bands; i++) {
            pulses[i] = allocation.pulses(i);
            fineBits[i] = allocation.fineBits(i);
            finePriority[i] = allocation.finePriority(i);
        }

        energy.decodeFine(dec, start, end, fineBits, oldBandE);

        Arrays.fill(collapseMasks, 0, bands * c, (byte) 0);
        bandDecoder.setSeed(rng);
        bandDecoder.decode(dec, mode, start, end, spectrum, c, collapseMasks, pulses,
                shortBlocks, spread, allocation.dualStereo(), allocation.intensity(),
                tfRes, len * (8 << BIT_RES) - antiCollapseRsv, allocation.balance(),
                lm, codedBands);
        rng = bandDecoder.seed();

        boolean antiCollapseOn = false;
        if (antiCollapseRsv > 0) {
            antiCollapseOn = dec.decodeRawBits(1) != 0;
        }

        energy.decodeFinal(dec, start, end, fineBits, finePriority,
                len * 8 - dec.tell(), oldBandE);

        if (antiCollapseOn) {
            // The return value is deliberately dropped: the reference passes the
            // seed by value here, so the noise the anti-collapse pass draws does
            // not advance the folding seed the next frame starts from.
            bandDecoder.antiCollapse(spectrum, collapseMasks, lm, c, n, start, end,
                    oldBandE, oldLogE, oldLogE2, pulses, rng);
        }

        log2Amp(start, end, c);

        if (silence) {
            // The envelope is set to silence rather than the bands simply not
            // being coded, because the next frame predicts from it. Leaving the
            // previous frame's loud energies in place makes the signal swell back
            // in over several frames after a gap instead of entering cleanly.
            Arrays.fill(bandAmplitude, 0, c * bands, 0.0f);
            energy.setSilent(oldBandE);
        }

        bandDecoder.denormalise(mode, spectrum, freq, bandAmplitude, effEnd, c);

        // Slide the synthesis history down by one frame to make room.
        for (int ch = 0; ch < channels; ch++) {
            System.arraycopy(decodeMem, ch * memStride + n, decodeMem, ch * memStride,
                    DECODE_BUFFER_SIZE - n);
        }

        for (int ch = 0; ch < c; ch++) {
            Arrays.fill(freq, ch * n, ch * n + lowEdge, 0.0f);
            Arrays.fill(freq, ch * n + highEdge, ch * n + n, 0.0f);
        }

        if (channels == 2 && c == 1) {
            // A mono frame in a stereo stream. Both outputs get the same
            // spectrum rather than one going silent, and the copy happens before
            // the transform so that both channels' overlap tails stay in step
            // with each other for the next frame.
            System.arraycopy(freq, 0, freq, n, n);
        } else if (channels == 1 && c == 2) {
            // A stereo frame in a mono decoder: the mid, at half scale, which is
            // what the two channels sum to.
            for (int i = 0; i < n; i++) {
                freq[i] = 0.5f * (freq[i] + freq[n + i]);
            }
        }

        inverseMdcts(shortBlocks, lm, n);

        for (int ch = 0; ch < channels; ch++) {
            int synth = ch * memStride + DECODE_BUFFER_SIZE - n;
            // A period below the minimum would let the comb filter read forward
            // of the sample it is writing.
            postfilterPeriod = Math.max(postfilterPeriod, COMBFILTER_MIN_PERIOD);
            postfilterPeriodOld = Math.max(postfilterPeriodOld, COMBFILTER_MIN_PERIOD);
            // The first short block cross-fades from the previous frame's
            // post-filter to this frame's; the rest of the frame cross-fades
            // from this frame's to the next one's parameters. Switching instead
            // of fading puts a click at every parameter change.
            combFilter(synth, postfilterPeriodOld, postfilterPeriod, mode.shortMdctSize(),
                    postfilterGainOld, postfilterGain, postfilterTapsetOld, postfilterTapset,
                    overlap);
            if (lm != 0) {
                combFilter(synth + mode.shortMdctSize(), postfilterPeriod, pfPitch,
                        n - mode.shortMdctSize(), postfilterGain, pfGain,
                        postfilterTapset, pfTapset, overlap);
            }
        }
        postfilterPeriodOld = postfilterPeriod;
        postfilterGainOld = postfilterGain;
        postfilterTapsetOld = postfilterTapset;
        postfilterPeriod = pfPitch;
        postfilterGain = pfGain;
        postfilterTapset = pfTapset;
        if (lm != 0) {
            // A frame longer than one short block already applied this frame's
            // parameters to everything past the first block, so the next frame
            // has nothing left to fade from.
            postfilterPeriodOld = postfilterPeriod;
            postfilterGainOld = postfilterGain;
            postfilterTapsetOld = postfilterTapset;
        }

        if (c == 1) {
            // A mono frame codes one channel of energy; the second copy is what
            // the anti-collapse pass and a later stereo frame read. Leaving it
            // stale would compare this frame's energies against a channel that
            // stopped being decoded frames ago.
            System.arraycopy(oldBandE, 0, oldBandE, bands, bands);
        }

        // Roll the energy history forward. A transient frame takes the minimum
        // rather than replacing, because its energies are measured over a
        // shorter window and would otherwise raise the floor the anti-collapse
        // pass compares against.
        if (!isTransient) {
            System.arraycopy(oldLogE, 0, oldLogE2, 0, 2 * bands);
            System.arraycopy(oldBandE, 0, oldLogE, 0, 2 * bands);
            for (int i = 0; i < 2 * bands; i++) {
                backgroundLogE[i] = Math.min(backgroundLogE[i] + m * 0.001f, oldBandE[i]);
            }
        } else {
            for (int i = 0; i < 2 * bands; i++) {
                oldLogE[i] = Math.min(oldLogE[i], oldBandE[i]);
            }
        }
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < start; i++) {
                oldLogE[ch * bands + i] = SILENCE_LOG_ENERGY;
                oldLogE2[ch * bands + i] = SILENCE_LOG_ENERGY;
            }
            for (int i = end; i < bands; i++) {
                oldLogE[ch * bands + i] = SILENCE_LOG_ENERGY;
                oldLogE2[ch * bands + i] = SILENCE_LOG_ENERGY;
            }
        }
        energy.clearOutsideCodedRange(start, end, oldBandE);
        if (c == 1) {
            // clearOutsideCodedRange only reaches the channels this decoder
            // codes, and the reference clears both copies whatever the packet
            // said. Nothing in a mono decoder reads the second copy, but a
            // stream that widens its bandwidth would otherwise leave stale
            // energies in the bands it has just started coding.
            for (int i = 0; i < start; i++) {
                oldBandE[bands + i] = 0.0f;
            }
            for (int i = end; i < bands; i++) {
                oldBandE[bands + i] = 0.0f;
            }
        }

        rng = (int) dec.finalRange();

        deemphasis(n, pcm, pcmOffset);
        return n;
    }

    /**
     * The first MDCT bin of a band edge, accepting the one-past-the-top edge.
     *
     * <p>{@code M*eBands[band]} upstream, where the edge table has one more
     * entry than there are bands. {@link CeltMode#bandStart} refuses the last
     * edge because it is not the start of any band, and this is the only place
     * that needs it: the top of the coded range is a band edge, not a band.
     */
    private static int bandEdge(CeltMode mode, int band) {
        return band == 0 ? 0 : mode.bandEnd(band - 1);
    }

    /**
     * {@code tf_decode} in {@code celt/celt.c}: RFC 6716 section 4.3.1.
     *
     * <p>One flag per band saying whether its time-frequency resolution changes
     * from the previous band's, then a single {@code tf_select} flag choosing
     * between two tables of what the change means. Coding the differences rather
     * than the values is why the first band's flag is the expensive one.
     */
    private void tfDecode(RangeDecoder dec, int start, int end, boolean isTransient, int lm) {
        int budget = dec.frameBytes() * 8;
        int tell = dec.tell();
        int logp = isTransient ? 2 : 4;
        int tfSelectRsv = lm > 0 && tell + logp + 1 <= budget ? 1 : 0;
        budget -= tfSelectRsv;
        int changed = 0;
        int curr = 0;
        for (int i = start; i < end; i++) {
            if (tell + logp <= budget) {
                curr ^= dec.decodeBit(logp);
                tell = dec.tell();
                changed |= curr;
            }
            tfRes[i] = curr;
            logp = isTransient ? 4 : 5;
        }
        int tfSelect = 0;
        // Only coded when the two tables would actually disagree; otherwise the
        // bit would carry nothing and the reserved space goes back to the bands.
        if (tfSelectRsv != 0
                && CeltTables.tfAdjustment(lm, isTransient, 0, changed)
                        != CeltTables.tfAdjustment(lm, isTransient, 1, changed)) {
            tfSelect = dec.decodeBit(1);
        }
        for (int i = start; i < end; i++) {
            tfRes[i] = CeltTables.tfAdjustment(lm, isTransient, tfSelect, tfRes[i]);
        }
    }

    /**
     * {@code log2Amp} in {@code celt/quant_bands.c}: log energy to amplitude.
     *
     * <p>Bands outside the coded range are set to zero amplitude, which is what
     * makes {@code denormalise_bands} leave them silent whatever shape the band
     * decoder happened to write there.
     */
    private void log2Amp(int start, int end, int channelsCoded) {
        for (int ch = 0; ch < channelsCoded; ch++) {
            int base = ch * bands;
            for (int i = 0; i < start; i++) {
                bandAmplitude[base + i] = 0.0f;
            }
            for (int i = start; i < end; i++) {
                bandAmplitude[base + i] = CeltTables.exp2(oldBandE[base + i] + E_MEANS[i]);
            }
            for (int i = end; i < bands; i++) {
                bandAmplitude[base + i] = 0.0f;
            }
        }
    }

    /**
     * {@code compute_inv_mdcts} in {@code celt/celt.c}: RFC 6716 section 4.3.7.
     *
     * <p>A transient frame is {@code 1 << LM} short transforms whose coefficients
     * are interleaved in the spectrum, each windowed and overlap-added onto the
     * one before; a normal frame is a single long transform. The tail of the
     * last block is kept for the next frame, because the time-domain aliasing in
     * it only cancels against the next frame's leading edge.
     */
    private void inverseMdcts(int shortBlocks, int lm, int n) {
        int blockSize = shortBlocks != 0 ? CeltMode.SHORT_MDCT_SIZE : n;
        int blocks = shortBlocks != 0 ? shortBlocks : 1;
        int shift = shortBlocks != 0 ? MAX_LM : MAX_LM - lm;

        for (int ch = 0; ch < channels; ch++) {
            // Only the first overlap samples are added onto; the rest are
            // assigned by the transform, so they need no clearing.
            Arrays.fill(blockOut, 0, overlap, 0.0f);
            for (int b = 0; b < blocks; b++) {
                mdct.inverseWindowed(freq, ch * n + b, blockOut, blockSize * b,
                        blockSize, blocks, shift, window, overlap);
            }
            int synth = ch * memStride + DECODE_BUFFER_SIZE - n;
            int tail = ch * memStride + DECODE_BUFFER_SIZE;
            for (int j = 0; j < overlap; j++) {
                decodeMem[synth + j] = blockOut[j] + decodeMem[tail + j];
            }
            for (int j = overlap; j < n; j++) {
                decodeMem[synth + j] = blockOut[j];
            }
            for (int j = 0; j < overlap; j++) {
                decodeMem[tail + j] = blockOut[n + j];
            }
        }
    }

    /**
     * {@code comb_filter} in {@code celt/celt.c}: RFC 6716 section 4.3.7.1.
     *
     * <p>A five-tap comb filter at the pitch period, which puts energy back into
     * the harmonics the transform smeared. It runs in place, reading samples it
     * has already written, exactly as the reference does; the minimum period of
     * {@value #COMBFILTER_MIN_PERIOD} is what keeps that recursion from reading
     * a sample written on the same iteration.
     *
     * <p>The first {@code overlap} samples cross-fade from the old parameters to
     * the new ones using the squared MDCT window, so a change of pitch or gain
     * is smooth rather than a step.
     */
    private void combFilter(int at, int t0, int t1, int n, float g0, float g1,
            int tapset0, int tapset1, int fade) {
        float g00 = g0 * COMB_GAINS[tapset0][0];
        float g01 = g0 * COMB_GAINS[tapset0][1];
        float g02 = g0 * COMB_GAINS[tapset0][2];
        float g10 = g1 * COMB_GAINS[tapset1][0];
        float g11 = g1 * COMB_GAINS[tapset1][1];
        float g12 = g1 * COMB_GAINS[tapset1][2];
        float[] x = decodeMem;
        int lead = Math.min(fade, n);
        for (int i = 0; i < lead; i++) {
            float f = window[i] * window[i];
            x[at + i] = x[at + i]
                    + (1.0f - f) * g00 * x[at + i - t0]
                    + (1.0f - f) * g01 * x[at + i - t0 - 1]
                    + (1.0f - f) * g01 * x[at + i - t0 + 1]
                    + (1.0f - f) * g02 * x[at + i - t0 - 2]
                    + (1.0f - f) * g02 * x[at + i - t0 + 2]
                    + f * g10 * x[at + i - t1]
                    + f * g11 * x[at + i - t1 - 1]
                    + f * g11 * x[at + i - t1 + 1]
                    + f * g12 * x[at + i - t1 - 2]
                    + f * g12 * x[at + i - t1 + 2];
        }
        for (int i = fade; i < n; i++) {
            x[at + i] = x[at + i]
                    + g10 * x[at + i - t1]
                    + g11 * x[at + i - t1 - 1]
                    + g11 * x[at + i - t1 + 1]
                    + g12 * x[at + i - t1 - 2]
                    + g12 * x[at + i - t1 + 2];
        }
    }

    /**
     * {@code deemphasis} in {@code celt/celt.c}: RFC 6716 section 4.3.7.
     *
     * <p>Undoes the encoder's first-order pre-emphasis. Its memory is decoder
     * state: this is an integrator, and resetting it between frames would put a
     * step at every frame boundary, heard as a low-frequency tick at the frame
     * rate.
     *
     * <p>Writes at the reference's float scale, one thirty-two-thousandth of the
     * internal signal, so the caller's conversion to 16-bit is the reference's
     * {@code FLOAT2INT16} unchanged.
     */
    private void deemphasis(int n, float[] pcm, int pcmOffset) {
        for (int ch = 0; ch < channels; ch++) {
            int synth = ch * memStride + DECODE_BUFFER_SIZE - n;
            float mem = preemphMem[ch];
            int out = pcmOffset + ch;
            for (int j = 0; j < n; j++) {
                float in = decodeMem[synth + j];
                float tmp = in + mem;
                mem = PREEMPH[0] * tmp - PREEMPH[1] * in;
                tmp = PREEMPH[3] * tmp;
                pcm[out] = tmp * (1.0f / 32768.0f);
                out += channels;
            }
            preemphMem[ch] = mem;
        }
    }

    @Override
    public String toString() {
        return "CeltDecoder[" + channels + " channels, rng="
                + Integer.toUnsignedString(rng) + "]";
    }
}
