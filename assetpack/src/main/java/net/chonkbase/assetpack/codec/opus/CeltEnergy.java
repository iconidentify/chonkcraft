package net.chonkbase.assetpack.codec.opus;

import java.util.Arrays;
import java.util.Objects;

/**
 * CELT's band energy envelope: the coarse pass, the fine pass, and the leftover
 * bits at the end of the frame.
 *
 * <p>A port of {@code unquant_coarse_energy}, {@code unquant_fine_energy} and
 * {@code unquant_energy_finalise} in {@code celt/quant_bands.c} together with
 * their encoding counterparts {@code quant_coarse_energy},
 * {@code quant_coarse_energy_impl}, {@code loss_distortion},
 * {@code quant_fine_energy} and {@code quant_energy_finalise} from the same
 * file, and the
 * {@code e_prob_model}, {@code pred_coef}, {@code beta_coef}, {@code beta_intra}
 * and {@code small_energy_icdf} tables from it. Both directions live here
 * because both run the same predictor over the same state, and an encoder whose
 * predictor drifted from the decoder's by one band would send corrections
 * computed against energies the decoder never had. RFC 6716 section
 * 4.3.2 describes the three-step strategy, 4.3.2.1 the coarse pass and 4.3.2.2
 * the two fine passes.
 *
 * <p>Everything here works in the log domain, base 2, one unit per 6 dB, with
 * the per-band mean of {@code eMeans} already subtracted. That is exactly the
 * {@code oldEBands} array of the reference decoder. Turning it into linear band
 * energies is {@code log2Amp}, which is a separate step and is not here.
 *
 * <h2>Why this object exists at all</h2>
 *
 * <p>The coarse energy of band {@code i} is not coded. What is coded is the
 * error left over after predicting it from two places at once: the band below
 * it in the same frame, and the same band in the frame before. RFC 6716 section
 * 4.3.2.1 writes the predictor as a 2-D z-transform in {@code alpha} (across
 * frames) and {@code beta} (across bands). {@code alpha} is the reason this
 * class holds state: the value it multiplies is the previous frame's energy
 * <em>after</em> both fine passes, so all three methods below write into the
 * same array and that array outlives the frame.
 *
 * <p>A decoder that starts each frame from zero decodes every symbol correctly
 * and still produces the wrong energies, quietly, on every non-intra frame --
 * which is nearly all of them. The error is largest where the prediction is
 * worth most, so the result tracks the signal: loud passages come out with the
 * envelope drifting a fraction of a step per frame and snapping back at each
 * intra frame. That is heard as pumping or breathing rather than as a defect in
 * any one frame, which is what makes it hard to find by listening to one.
 *
 * <p>An instance is per stream and, for stereo, per channel pair. It allocates
 * once; none of the decode methods allocate.
 */
public final class CeltEnergy {

    /**
     * {@code MAX_FINE_BITS} in {@code celt/rate.h}.
     *
     * <p>The allocator never hands out more, and the final pass refuses to add
     * a bit to a band already at the ceiling: with 8 bits the correction is
     * already finer than 0.025 dB, so a ninth would be spent on nothing.
     *
     * <p>{@link CeltAllocation} declares the same ceiling from the same header,
     * because it is the end that enforces it; {@code CeltEnergyTest} asserts the
     * two agree so that changing one alone fails rather than producing a decoder
     * that spends a bit the encoder did not.
     */
    public static final int MAX_FINE_BITS = 8;

    /**
     * Floor applied to the previous frame's energy before it is predicted from.
     *
     * <p>{@code MAX16(-QCONST16(9.f,DB_SHIFT), oldEBands[...])} in
     * {@code unquant_coarse_energy}. Without it a band that decayed into
     * silence would drag the next frame's prediction arbitrarily far negative,
     * and every band above it in that frame with it, because the frequency
     * predictor accumulates.
     */
    private static final float PREDICTION_FLOOR = -9.0f;

    /**
     * The log energy a band is given when the frame is flagged silent, and when
     * a band falls outside the coded range.
     *
     * <p>{@code -QCONST16(28.f,DB_SHIFT)} in {@code celt_decode_with_ec}.
     */
    public static final float SILENCE_LOG_ENERGY = -28.0f;

    /**
     * {@code pred_coef} in {@code celt/quant_bands.c}: the inter-frame
     * coefficient {@code alpha}, indexed by LM.
     *
     * <p>The comment on the reference declaration reads "prediction
     * coefficients: 0.9, 0.8, 0.65, 0.5" and the numbers below are those
     * rounded to Q15. They are transcribed as the Q15 integers rather than as
     * the decimal the comment gives, because the float build of the reference
     * writes them the same way and the two are not the same number: 0.9 is not
     * 29440/32768. A decoder built from the comment would predict every frame
     * from a slightly wrong fraction of the last one, which is inaudible in one
     * frame and accumulates.
     *
     * <p>Longer frames get a smaller coefficient because there is more time
     * between them for the signal to have changed.
     */
    private static final float[] PRED_COEF = {
        29440 / 32768.0f, 26112 / 32768.0f, 21248 / 32768.0f, 16384 / 32768.0f
    };

    /**
     * {@code beta_coef} in {@code celt/quant_bands.c}: the intra-frame
     * coefficient {@code beta}, indexed by LM, for inter-frame frames.
     */
    private static final float[] BETA_COEF = {
        30147 / 32768.0f, 22282 / 32768.0f, 12124 / 32768.0f, 6554 / 32768.0f
    };

    /**
     * {@code beta_intra} in {@code celt/quant_bands.c}: the intra-frame
     * coefficient used when there is no previous frame to predict from.
     *
     * <p>RFC 6716 section 4.3.2.1 states this one in the prose -- "alpha=0,
     * beta=4915/32768 when using intra energy" -- and Appendix A agrees.
     */
    private static final float BETA_INTRA = 4915 / 32768.0f;

    /**
     * {@code e_prob_model} in {@code celt/quant_bands.c}, indexed by
     * {@code [LM][intra][2*band]} and {@code [LM][intra][2*band+1]}.
     *
     * <p>RFC 6716 section 4.3.2.1 names this table and prints none of it: "We
     * approximate the ideal probability distribution of the prediction error
     * using a Laplace distribution with separate parameters for each frame size
     * in intra- and inter-frame modes. These parameters are held in the
     * e_prob_model table in quant_bands.c." Appendix A is therefore the only
     * source, and it is the normative one.
     *
     * <p>Each band has a pair: the probability of a zero prediction error, and
     * the decay rate, both in Q8. {@link #decodeCoarse} shifts the first left by
     * 7 and the second left by 6 before handing them to {@link Laplace}, which
     * is the reference's own asymmetry -- the first is out of 32768 and the
     * second is Q14.
     *
     * <p>Index 1 of the middle dimension is the intra model, not index 0. The
     * reference calls {@code e_prob_model[LM][1]} for its intra pass, and the
     * two rows are close enough in shape that swapping them decodes without
     * complaint and simply spends the wrong number of bits, desynchronising the
     * range coder partway through the frame.
     *
     * <p>Twenty-one bands, so 42 entries per row.
     */
    private static final int[][][] E_PROB_MODEL = {
        // 120 sample frames (2.5 ms, LM 0).
        {
            {
                72, 127,  65, 129,  66, 128,  65, 128,  64, 128,  62, 128,  64, 128,
                64, 128,  92,  78,  92,  79,  92,  78,  90,  79, 116,  41, 115,  40,
                114,  40, 132,  26, 132,  26, 145,  17, 161,  12, 176,  10, 177,  11
            },
            {
                24, 179,  48, 138,  54, 135,  54, 132,  53, 134,  56, 133,  55, 132,
                55, 132,  61, 114,  70,  96,  74,  88,  75,  88,  87,  74,  89,  66,
                91,  67, 100,  59, 108,  50, 120,  40, 122,  37,  97,  43,  78,  50
            }
        },
        // 240 sample frames (5 ms, LM 1).
        {
            {
                83,  78,  84,  81,  88,  75,  86,  74,  87,  71,  90,  73,  93,  74,
                93,  74, 109,  40, 114,  36, 117,  34, 117,  34, 143,  17, 145,  18,
                146,  19, 162,  12, 165,  10, 178,   7, 189,   6, 190,   8, 177,   9
            },
            {
                23, 178,  54, 115,  63, 102,  66,  98,  69,  99,  74,  89,  71,  91,
                73,  91,  78,  89,  86,  80,  92,  66,  93,  64, 102,  59, 103,  60,
                104,  60, 117,  52, 123,  44, 138,  35, 133,  31,  97,  38,  77,  45
            }
        },
        // 480 sample frames (10 ms, LM 2).
        {
            {
                61,  90,  93,  60, 105,  42, 107,  41, 110,  45, 116,  38, 113,  38,
                112,  38, 124,  26, 132,  27, 136,  19, 140,  20, 155,  14, 159,  16,
                158,  18, 170,  13, 177,  10, 187,   8, 192,   6, 175,   9, 159,  10
            },
            {
                21, 178,  59, 110,  71,  86,  75,  85,  84,  83,  91,  66,  88,  73,
                87,  72,  92,  75,  98,  72, 105,  58, 107,  54, 115,  52, 114,  55,
                112,  56, 129,  51, 132,  40, 150,  33, 140,  29,  98,  35,  77,  42
            }
        },
        // 960 sample frames (20 ms, LM 3).
        {
            {
                42, 121,  96,  66, 108,  43, 111,  40, 117,  44, 123,  32, 120,  36,
                119,  33, 127,  33, 134,  34, 139,  21, 147,  23, 152,  20, 158,  25,
                154,  26, 166,  21, 173,  16, 184,  13, 184,  10, 150,  13, 139,  15
            },
            {
                22, 178,  63, 114,  74,  82,  84,  83,  92,  82, 103,  62,  96,  72,
                96,  67, 101,  73, 107,  72, 113,  55, 118,  52, 125,  52, 118,  52,
                117,  55, 135,  49, 137,  39, 157,  32, 145,  29,  97,  33,  77,  40
            }
        }
    };

    /**
     * {@code small_energy_icdf} in {@code celt/quant_bands.c}: a three-symbol
     * fallback for when there is not room for a Laplace symbol.
     *
     * <p>Two bits of context, so the symbols are 2/4, 1/4 and 1/4 wide. They map
     * to prediction errors of 0, -1 and +1 in that order, which is the
     * {@code (qi>>1)^-(qi&1)} in {@link #decodeCoarse}.
     *
     * <p>Private and never written to, so it does not need the defensive copy
     * that {@link CeltTables} gives its exported tables.
     */
    private static final short[] SMALL_ENERGY_ICDF = {2, 1, 0};

    /** Bits in the small-energy context, the {@code ec_dec_icdf} argument. */
    private static final int SMALL_ENERGY_FTB = 2;

    /** Bits needed before a Laplace symbol will be coded. */
    private static final int LAPLACE_BUDGET = 15;

    private final int bands;
    private final int channels;

    /**
     * The live envelope, {@code oldEBands}, laid out {@code channel*bands+band}.
     *
     * <p>Both the output of this frame and the prediction input to the next.
     */
    private final float[] logEnergy;

    /**
     * {@code prev[2]} in {@code unquant_coarse_energy}: the running frequency
     * predictor, one per channel.
     *
     * <p>A field only so that the per-frame path allocates nothing. It is reset
     * to zero at the top of every {@link #decodeCoarse}, because the reference
     * declares it inside the function -- the frequency predictor does not cross
     * a frame boundary even though the time predictor does.
     */
    private final float[] prev = new float[2];

    /**
     * {@code st->delayedIntra}: a running measure of how badly the previous
     * frames would have been reconstructed from a lost one.
     *
     * <p>Encoder state. It decides when to spend a frame on intra energy, which
     * costs bits but stops a packet loss from smearing the envelope forward
     * through the inter-frame predictor for a second or more. Reset to 1, not 0,
     * because {@code opus_custom_encoder_ctl(OPUS_RESET_STATE)} sets it to 1 and
     * a stream starting at 0 would not code its first frame intra.
     */
    private float delayedIntra = 1.0f;

    /** {@code oldEBands_intra}: the envelope the intra pass would leave behind. */
    private final float[] logEnergyIntra;

    /** {@code error_intra}: the residual the intra pass would leave behind. */
    private final float[] errorIntra;

    /** Where the coarse pass started, so the losing pass can be undone. */
    private final RangeEncoder.Bookmark startState = new RangeEncoder.Bookmark();

    /** Where the intra pass ended, so it can be reinstated if it wins. */
    private final RangeEncoder.Bookmark intraState = new RangeEncoder.Bookmark();

    /**
     * @param mode     the CELT mode, read only for its band count
     * @param channels 1 or 2
     */
    public CeltEnergy(CeltMode mode, int channels) {
        Objects.requireNonNull(mode, "mode");
        if (channels != 1 && channels != 2) {
            throw new IllegalArgumentException("CELT codes 1 or 2 channels, not " + channels);
        }
        this.bands = mode.bandCount();
        this.channels = channels;
        this.logEnergy = new float[channels * bands];
        this.logEnergyIntra = new float[channels * bands];
        this.errorIntra = new float[channels * bands];
    }

    /** How many bands the envelope covers, per channel. */
    public int bandCount() {
        return bands;
    }

    /** How many channels the envelope covers. */
    public int channelCount() {
        return channels;
    }

    /**
     * Forgets the previous frame, as {@code OPUS_RESET_STATE} does.
     *
     * <p>Zero, not {@link #SILENCE_LOG_ENERGY}: the reference decoder's state is
     * cleared to zero when it is created, and the first frame of a stream is
     * predicted from that. Starting anywhere else makes the first frame of every
     * stream come out at the wrong level unless it happens to be an intra frame.
     */
    public void reset() {
        Arrays.fill(logEnergy, 0.0f);
        Arrays.fill(logEnergyIntra, 0.0f);
        Arrays.fill(errorIntra, 0.0f);
        prev[0] = 0.0f;
        prev[1] = 0.0f;
        delayedIntra = 1.0f;
    }

    /**
     * Takes an envelope as the state the next coarse pass predicts from.
     *
     * <p>Exists because {@code celt_decode_with_ec} keeps {@code oldBandE} at two
     * channels whatever the packet carries, while this class is sized for the
     * channel count it decodes. A stream may change channel count on any packet
     * -- RFC 6716 section 3.1 puts the flag in every table of contents byte, and
     * conformance vector 7 exercises it on nearly half its packets -- and the
     * reference folds the two stored channels together on the way into a mono
     * frame and mirrors them back on the way out. A decoder that follows it in
     * holding that two-channel array is the owner of the state, and this is how
     * it hands the relevant part back.
     *
     * <p>Without this, a decoder would have to keep a separate history per
     * channel count, and the one that was not in use would go stale. Coarse
     * energy is a difference from the previous frame at up to 0.9 of its value,
     * so predicting from a history that stopped several frames ago puts the
     * whole spectrum at the wrong level and lets it drift back over the frames
     * that follow -- heard as the signal swelling in and out as the encoder
     * switches between mono and stereo.
     *
     * <p>Reads the first {@code channelCount()*bandCount()} entries and copies
     * them; the caller's array is not retained.
     *
     * @param bandLogE the envelope to adopt, laid out {@code channel*bandCount+band}
     */
    public void adoptEnvelope(float[] bandLogE) {
        checkOutput(bandLogE);
        System.arraycopy(bandLogE, 0, logEnergy, 0, logEnergy.length);
    }

    /**
     * The coarse pass: one 6 dB step per band per channel, RFC 6716 section
     * 4.3.2.1, {@code unquant_coarse_energy}.
     *
     * <p>The caller reads the intra flag itself, before calling this; it is one
     * bit at probability 1/8 and {@code celt_decode_with_ec} decodes it rather
     * than {@code unquant_coarse_energy}.
     *
     * <p>Three coding branches, chosen by how much of the frame is left. With 15
     * bits or more a Laplace symbol is coded. With 2 to 14 a three-symbol icdf
     * covering only -1, 0 and +1. With exactly 1, a single bit meaning 0 or -1.
     * With none, the band is assumed to have fallen by one step and nothing is
     * read. Both ends make the same choice because {@code ec_tell} is exact and
     * agrees between encoder and decoder after every symbol; getting the
     * thresholds wrong does not misread a symbol, it reads a symbol of the wrong
     * kind and everything after it in the frame is noise.
     *
     * @param dec       the frame's range decoder
     * @param start     first coded band, 0 for CELT-only, 17 for Hybrid
     * @param end       one past the last coded band
     * @param lm        frame size as log2 of the number of 2.5 ms blocks, 0 to 3
     * @param intra     whether this frame is coded without reference to the last
     * @param bandLogE  filled with the whole envelope, {@code channels*bandCount()}
     *                  entries laid out {@code channel*bandCount()+band}
     */
    public void decodeCoarse(RangeDecoder dec, int start, int end, int lm, boolean intra,
            float[] bandLogE) {
        Objects.requireNonNull(dec, "dec");
        checkBandRange(start, end);
        if (lm < 0 || lm >= E_PROB_MODEL.length) {
            throw new IllegalArgumentException("a CELT frame size index must be 0 to "
                    + (E_PROB_MODEL.length - 1) + ", not " + lm);
        }
        checkOutput(bandLogE);

        int[] probModel = E_PROB_MODEL[lm][intra ? 1 : 0];
        // alpha is zero for an intra frame, which is what makes it intra: the
        // previous frame's energy is multiplied out of the prediction entirely.
        float coef = intra ? 0.0f : PRED_COEF[lm];
        float beta = intra ? BETA_INTRA : BETA_COEF[lm];

        int budget = dec.frameBytes() * 8;
        prev[0] = 0.0f;
        prev[1] = 0.0f;

        for (int i = start; i < end; i++) {
            // The reference clamps the band index into the table rather than
            // sizing the table to the mode, because custom CELT modes may have
            // more than 21 bands and the top band's model is reused for all of
            // them. Opus proper never exceeds 21, so this never binds here; it
            // is kept because removing it would make the table's 42 entries look
            // like a coincidence rather than a bound.
            int pi = 2 * Math.min(i, 20);
            for (int c = 0; c < channels; c++) {
                int tell = dec.tell();
                int qi;
                if (budget - tell >= LAPLACE_BUDGET) {
                    qi = Laplace.decode(dec, probModel[pi] << 7, probModel[pi + 1] << 6);
                } else if (budget - tell >= 2) {
                    qi = dec.decodeIcdf(SMALL_ENERGY_ICDF, SMALL_ENERGY_FTB);
                    qi = (qi >> 1) ^ -(qi & 1);
                } else if (budget - tell >= 1) {
                    qi = -dec.decodeBit(1);
                } else {
                    qi = -1;
                }

                int idx = i + c * bands;
                float oldE = Math.max(PREDICTION_FLOOR, logEnergy[idx]);
                float q = qi;
                // The two predictions and the residual, in one line, exactly as
                // the float build of unquant_coarse_energy computes it. The
                // fixed-point build clamps this at -28 as well; that clamp is
                // inside #ifdef FIXED_POINT and is not part of the float path,
                // so it is deliberately absent.
                logEnergy[idx] = coef * oldE + prev[c] + q;
                prev[c] = prev[c] + q - beta * q;
            }
        }
        copyOut(bandLogE);
    }

    /**
     * The coarse pass, encoding: {@code quant_coarse_energy}.
     *
     * <p>Codes the frame twice and keeps the better one, which is what
     * {@code two_pass} does upstream at complexity 4 and above. The first pass
     * predicts only across bands, the second also across frames; the second is
     * usually far cheaper, but on an attack -- where every band's energy jumps
     * at once and the previous frame predicts nothing -- it is not, and the
     * comparison is what keeps a cymbal from eating a quarter of its frame on
     * energy alone. The tie-break is the frame that came out smaller.
     *
     * <p>The intra flag itself is written here rather than by the caller,
     * because it is the first symbol of the pass and both passes have to write
     * their own. {@link #decodeCoarse} takes the flag as an argument for the
     * matching reason: {@code celt_decode_with_ec} reads it before calling
     * {@code unquant_coarse_energy}.
     *
     * <p>Leaves this object holding exactly the energies a decoder will
     * reconstruct, and fills {@code error} with what the fine passes still have
     * to correct.
     *
     * @param enc            the frame's range encoder
     * @param start          first coded band
     * @param end            one past the last coded band
     * @param effEnd         one past the last band with coefficients, for the
     *                       loss-distortion measure only
     * @param lm             frame size as log2 of the number of 2.5 ms blocks
     * @param target         the measured log energies to code,
     *                       {@code channels*bandCount()} of them
     * @param budget         the frame in whole bits
     * @param availableBytes bytes the frame has left, which bounds how fast a
     *                       band is allowed to be coded as decaying
     * @param forceIntra     code without reference to the previous frame
     * @param error          receives the residual per band per channel
     * @param bandLogE       filled with the whole quantised envelope
     * @return whether the frame was coded intra
     */
    public boolean encodeCoarse(RangeEncoder enc, int start, int end, int effEnd, int lm,
            float[] target, int budget, int availableBytes, boolean forceIntra,
            float[] error, float[] bandLogE) {
        Objects.requireNonNull(enc, "enc");
        checkBandRange(start, end);
        if (effEnd < start || effEnd > end) {
            throw new IllegalArgumentException("the effective band end " + effEnd
                    + " is not inside the coded range [" + start + "," + end + ")");
        }
        if (lm < 0 || lm >= E_PROB_MODEL.length) {
            throw new IllegalArgumentException("a CELT frame size index must be 0 to "
                    + (E_PROB_MODEL.length - 1) + ", not " + lm);
        }
        checkOutput(target);
        checkOutput(error);
        checkOutput(bandLogE);

        boolean twoPass = true;
        boolean intra = forceIntra;
        // loss_distortion, measured against the state before this frame changes
        // it. In the float build every shift in it is the identity.
        float newDistortion = 0.0f;
        for (int c = 0; c < channels; c++) {
            for (int i = start; i < effEnd; i++) {
                float d = target[i + c * bands] - logEnergy[i + c * bands];
                newDistortion += d * d;
            }
        }
        newDistortion = Math.min(200.0f, newDistortion);

        if (enc.tell() + 3 > budget) {
            // No room even for the intra flag, so there is nothing to choose
            // between and the pass below will skip writing it.
            twoPass = false;
            intra = false;
        }
        float maxDecay = Math.min(16.0f, 0.125f * availableBytes);

        enc.save(startState);
        System.arraycopy(logEnergy, 0, logEnergyIntra, 0, logEnergy.length);

        int badness1 = 0;
        if (twoPass || intra) {
            badness1 = coarsePass(enc, start, end, lm, target, logEnergyIntra, budget,
                    true, maxDecay, errorIntra);
        }

        if (!intra) {
            int tellIntra = enc.tellFrac();
            enc.save(intraState);
            enc.restore(startState);
            int badness2 = coarsePass(enc, start, end, lm, target, logEnergy, budget,
                    false, maxDecay, error);
            // The intra bias upstream is scaled by the expected packet loss
            // rate, which this encoder does not model, so it is zero and the
            // tie-break is purely which pass was shorter.
            if (twoPass && (badness1 < badness2
                    || (badness1 == badness2 && enc.tellFrac() > tellIntra))) {
                enc.restore(intraState);
                System.arraycopy(logEnergyIntra, 0, logEnergy, 0, logEnergy.length);
                System.arraycopy(errorIntra, 0, error, 0, error.length);
                intra = true;
            }
        } else {
            System.arraycopy(logEnergyIntra, 0, logEnergy, 0, logEnergy.length);
            System.arraycopy(errorIntra, 0, error, 0, error.length);
        }

        if (intra) {
            delayedIntra = newDistortion;
        } else {
            float alpha = PRED_COEF[lm];
            delayedIntra = alpha * alpha * delayedIntra + newDistortion;
        }
        copyOut(bandLogE);
        return intra;
    }

    /**
     * One pass of the coarse encoder, {@code quant_coarse_energy_impl}.
     *
     * @return the badness: how far the residuals had to be clamped to fit,
     *         summed over the frame. Zero means the frame fitted exactly.
     */
    private int coarsePass(RangeEncoder enc, int start, int end, int lm, float[] target,
            float[] state, int budget, boolean intra, float maxDecay, float[] error) {
        if (enc.tell() + 3 <= budget) {
            enc.encodeBit(intra ? 1 : 0, 3);
        }
        int[] probModel = E_PROB_MODEL[lm][intra ? 1 : 0];
        float coef = intra ? 0.0f : PRED_COEF[lm];
        float beta = intra ? BETA_INTRA : BETA_COEF[lm];

        int badness = 0;
        prev[0] = 0.0f;
        prev[1] = 0.0f;
        for (int i = start; i < end; i++) {
            int pi = 2 * Math.min(i, 20);
            for (int c = 0; c < channels; c++) {
                int idx = i + c * bands;
                float x = target[idx];
                float oldE = Math.max(PREDICTION_FLOOR, state[idx]);
                float f = x - coef * oldE - prev[c];
                // Rounding to nearest, which the reference's comment calls
                // "really important": truncating instead biases every band's
                // coded energy downwards by half a step on average, and the
                // fine pass cannot correct a bias it was handed.
                int qi = (int) Math.floor(0.5f + f);

                // A band is not allowed to be coded as falling faster than the
                // frame can afford, because a band that drops a long way in one
                // frame costs a long Laplace tail and starves the rest of the
                // spectrum to pay for it.
                float decayBound = Math.max(SILENCE_LOG_ENERGY, state[idx]) - maxDecay;
                if (qi < 0 && x < decayBound) {
                    qi += (int) (decayBound - x);
                    if (qi > 0) {
                        qi = 0;
                    }
                }
                int wanted = qi;

                int tell = enc.tell();
                // Three eighth-bits per band per channel is what the cheapest
                // remaining branch costs, so this is what is left after the
                // rest of the frame is guaranteed a way to be written at all.
                int bitsLeft = budget - tell - 3 * channels * (end - i);
                if (i != start && bitsLeft < 30) {
                    if (bitsLeft < 24) {
                        qi = Math.min(1, qi);
                    }
                    if (bitsLeft < 16) {
                        qi = Math.max(-1, qi);
                    }
                }
                if (budget - tell >= LAPLACE_BUDGET) {
                    qi = Laplace.encode(enc, qi, probModel[pi] << 7, probModel[pi + 1] << 6);
                } else if (budget - tell >= 2) {
                    qi = Math.max(-1, Math.min(qi, 1));
                    enc.encodeIcdf(2 * qi ^ -(qi < 0 ? 1 : 0), SMALL_ENERGY_ICDF,
                            SMALL_ENERGY_FTB);
                } else if (budget - tell >= 1) {
                    qi = Math.min(0, qi);
                    enc.encodeBit(-qi, 1);
                } else {
                    // Nothing written at all; the decoder assumes a fall of one
                    // step here and this must assume the same.
                    qi = -1;
                }
                error[idx] = f - qi;
                badness += Math.abs(wanted - qi);

                float q = qi;
                state[idx] = coef * oldE + prev[c] + q;
                prev[c] = prev[c] + q - beta * q;
            }
        }
        return badness;
    }

    /**
     * The fine pass, encoding: {@code quant_fine_energy}.
     *
     * <p>Raw bits, so they cost exactly what they say and are packed from the
     * back of the frame. The correction is the same {@code (f+1/2)/2**B - 1/2}
     * the decoder applies, and the residual is reduced by it so that the final
     * pass below is deciding on what is genuinely left.
     *
     * @param enc      the frame's range encoder
     * @param start    first coded band
     * @param end      one past the last coded band
     * @param fineBits bits per band per channel from the allocator
     * @param error    the residual from {@link #encodeCoarse}, updated in place
     * @param bandLogE filled with the whole quantised envelope
     */
    public void encodeFine(RangeEncoder enc, int start, int end, int[] fineBits,
            float[] error, float[] bandLogE) {
        Objects.requireNonNull(enc, "enc");
        checkBandRange(start, end);
        checkFineBits(fineBits, start, end);
        checkOutput(error);
        checkOutput(bandLogE);

        for (int i = start; i < end; i++) {
            int bits = fineBits[i];
            if (bits <= 0) {
                continue;
            }
            int frac = 1 << bits;
            for (int c = 0; c < channels; c++) {
                int idx = i + c * bands;
                int q2 = (int) Math.floor((error[idx] + 0.5f) * frac);
                // The residual can sit outside [-1/2, 1/2) when the coarse pass
                // had to clamp, and a value outside the field would be written
                // as its low bits -- a band coded at the far end of its range
                // instead of the near one, which is a 6 dB error in one band.
                if (q2 > frac - 1) {
                    q2 = frac - 1;
                }
                if (q2 < 0) {
                    q2 = 0;
                }
                enc.encodeRawBits(q2, bits);
                float offset = (q2 + 0.5f) * (1 << (14 - bits)) * (1.0f / 16384.0f) - 0.5f;
                logEnergy[idx] += offset;
                error[idx] -= offset;
            }
        }
        copyOut(bandLogE);
    }

    /**
     * The final pass, encoding: {@code quant_energy_finalise}.
     *
     * <p>One more bit for as many bands as the leftovers cover, priority 0
     * first. The bit is the sign of what is left of the residual, so it always
     * moves the envelope the right way even though it cannot move it far.
     *
     * @param enc          the frame's range encoder
     * @param start        first coded band
     * @param end          one past the last coded band
     * @param fineBits     bits already spent per band
     * @param finePriority 0 or 1 per band, from the allocator
     * @param bitsLeft     bits of the frame not yet consumed
     * @param error        the residual from {@link #encodeFine}
     * @param bandLogE     filled with the whole quantised envelope
     */
    public void encodeFinal(RangeEncoder enc, int start, int end, int[] fineBits,
            int[] finePriority, int bitsLeft, float[] error, float[] bandLogE) {
        Objects.requireNonNull(enc, "enc");
        checkBandRange(start, end);
        checkFineBits(fineBits, start, end);
        Objects.requireNonNull(finePriority, "finePriority");
        if (finePriority.length < end) {
            throw new IllegalArgumentException("finePriority covers " + finePriority.length
                    + " bands but the frame codes up to band " + end);
        }
        checkOutput(error);
        checkOutput(bandLogE);

        int remaining = bitsLeft;
        for (int prio = 0; prio < 2; prio++) {
            for (int i = start; i < end && remaining >= channels; i++) {
                if (fineBits[i] >= MAX_FINE_BITS || finePriority[i] != prio) {
                    continue;
                }
                for (int c = 0; c < channels; c++) {
                    int idx = i + c * bands;
                    int q2 = error[idx] < 0 ? 0 : 1;
                    enc.encodeRawBits(q2, 1);
                    float offset = (q2 - 0.5f) * (1 << (14 - fineBits[i] - 1))
                            * (1.0f / 16384.0f);
                    logEnergy[idx] += offset;
                    remaining--;
                }
            }
        }
        copyOut(bandLogE);
    }

    /**
     * The fine pass: {@code fineBits[i]} raw bits per band per channel, RFC 6716
     * section 4.3.2.2, {@code unquant_fine_energy}.
     *
     * <p>The correction for an {@code f} read out of {@code B} bits is
     * {@code (f+1/2)/2**B - 1/2}, exactly as the RFC prose states it, which
     * centres the correction on zero so that the fine pass cannot bias the
     * envelope. These are raw bits, taken from the back of the frame, so they
     * cost no probability model and a bit error in one corrupts only that band.
     *
     * @param dec      the frame's range decoder
     * @param start    first coded band
     * @param end      one past the last coded band
     * @param fineBits bits per band from the allocator, 0 to {@link #MAX_FINE_BITS}
     * @param bandLogE filled with the whole envelope
     */
    public void decodeFine(RangeDecoder dec, int start, int end, int[] fineBits,
            float[] bandLogE) {
        Objects.requireNonNull(dec, "dec");
        checkBandRange(start, end);
        checkFineBits(fineBits, start, end);
        checkOutput(bandLogE);

        for (int i = start; i < end; i++) {
            int bits = fineBits[i];
            if (bits <= 0) {
                continue;
            }
            for (int c = 0; c < channels; c++) {
                int q2 = dec.decodeRawBits(bits);
                float offset = (q2 + 0.5f) * (1 << (14 - bits)) * (1.0f / 16384.0f) - 0.5f;
                logEnergy[i + c * bands] += offset;
            }
        }
        copyOut(bandLogE);
    }

    /**
     * The final pass: one more bit for some bands, from whatever is left of the
     * frame, RFC 6716 section 4.3.2.2, {@code unquant_energy_finalise}.
     *
     * <p>Priority 0 bands are served first from band {@code start} upwards, then
     * priority 1, then any remainder is left unused. The allocator produced the
     * priorities; see {@code interp_bits2pulses} in {@code celt/rate.c}.
     *
     * <p>Reads a bit per channel, and stops the moment fewer than
     * {@code channels} bits remain -- never half a stereo pair. A decoder that
     * checked per bit rather than per pair would take one bit past the end of a
     * stereo frame, which reads as zero and silently lowers the last band it
     * touched in the left channel only.
     *
     * @param dec           the frame's range decoder
     * @param start         first coded band
     * @param end           one past the last coded band
     * @param fineBits      bits already spent per band, from {@link #decodeFine}
     * @param finePriority  0 or 1 per band, from the allocator
     * @param bitsLeft      bits of the frame not yet consumed,
     *                      {@code frameBytes()*8 - dec.tell()}
     * @param bandLogE      filled with the whole envelope
     */
    public void decodeFinal(RangeDecoder dec, int start, int end, int[] fineBits,
            int[] finePriority, int bitsLeft, float[] bandLogE) {
        Objects.requireNonNull(dec, "dec");
        checkBandRange(start, end);
        checkFineBits(fineBits, start, end);
        Objects.requireNonNull(finePriority, "finePriority");
        if (finePriority.length < end) {
            throw new IllegalArgumentException("finePriority covers " + finePriority.length
                    + " bands but the frame codes up to band " + end);
        }
        checkOutput(bandLogE);

        int remaining = bitsLeft;
        for (int prio = 0; prio < 2; prio++) {
            for (int i = start; i < end && remaining >= channels; i++) {
                // A band already at the ceiling gets nothing however many bits
                // are going spare: a ninth fine bit would be worth 0.012 dB and
                // the reference does not spend it, so spending it here would
                // read a bit the encoder never wrote and shift every remaining
                // raw field in the frame.
                if (fineBits[i] >= MAX_FINE_BITS || finePriority[i] != prio) {
                    continue;
                }
                for (int c = 0; c < channels; c++) {
                    int q2 = dec.decodeRawBits(1);
                    float offset = (q2 - 0.5f) * (1 << (14 - fineBits[i] - 1))
                            * (1.0f / 16384.0f);
                    logEnergy[i + c * bands] += offset;
                    remaining--;
                }
            }
        }
        copyOut(bandLogE);
    }

    /**
     * Sets every band to {@link #SILENCE_LOG_ENERGY}, for a frame whose silence
     * flag was set.
     *
     * <p>{@code celt_decode_with_ec} does this after the three passes, not
     * instead of them. Skipping it leaves the previous frame's loud energies in
     * the state, and because the next frame predicts from them at up to 0.9 the
     * signal comes back after the silence at the wrong level and settles over
     * several frames -- a swell out of a gap rather than a clean entry.
     *
     * @param bandLogE filled with the whole envelope
     */
    public void setSilent(float[] bandLogE) {
        checkOutput(bandLogE);
        Arrays.fill(logEnergy, SILENCE_LOG_ENERGY);
        copyOut(bandLogE);
    }

    /**
     * Zeroes the bands outside the coded range, which
     * {@code celt_decode_with_ec} does at the end of every frame.
     *
     * <p>Its comment there is "In case start or end were to change". A stream
     * that widens its bandwidth mid-stream -- narrowband to wideband, or a
     * Hybrid frame followed by a CELT-only one -- starts coding bands that were
     * never touched before, and their state would otherwise be whatever was left
     * from before the last narrowing. The time prediction would then start those
     * bands from a stale level, which is heard as the newly opened top of the
     * spectrum arriving too loud for a few frames.
     *
     * @param start    first coded band of the frame just decoded
     * @param end      one past its last coded band
     * @param bandLogE filled with the whole envelope
     */
    public void clearOutsideCodedRange(int start, int end, float[] bandLogE) {
        checkBandRange(start, end);
        checkOutput(bandLogE);
        for (int c = 0; c < channels; c++) {
            for (int i = 0; i < start; i++) {
                logEnergy[i + c * bands] = 0.0f;
            }
            for (int i = end; i < bands; i++) {
                logEnergy[i + c * bands] = 0.0f;
            }
        }
        copyOut(bandLogE);
    }

    private void copyOut(float[] bandLogE) {
        System.arraycopy(logEnergy, 0, bandLogE, 0, logEnergy.length);
    }

    private void checkBandRange(int start, int end) {
        if (start < 0 || end > bands || start > end) {
            throw new IllegalArgumentException("coded band range [" + start + "," + end
                    + ") is not inside the " + bands + " bands of this mode");
        }
    }

    private void checkOutput(float[] bandLogE) {
        Objects.requireNonNull(bandLogE, "bandLogE");
        if (bandLogE.length < logEnergy.length) {
            throw new IllegalArgumentException("the energy envelope needs "
                    + logEnergy.length + " floats for " + channels + " channels of "
                    + bands + " bands, and was given " + bandLogE.length);
        }
    }

    private void checkFineBits(int[] fineBits, int start, int end) {
        Objects.requireNonNull(fineBits, "fineBits");
        if (fineBits.length < end) {
            throw new IllegalArgumentException("fineBits covers " + fineBits.length
                    + " bands but the frame codes up to band " + end);
        }
        // Only the coded range is checked, because only the coded range is read.
        // The allocator fills the whole array, but a caller that reuses one
        // across frames of different bandwidths leaves stale values below start
        // and above end, and those are none of this method's business.
        for (int i = start; i < end; i++) {
            // The shift that turns a fine bit count into a correction is
            // 1<<(14-bits) and it is computed per band, so a count outside this
            // range would not overflow an array -- it would shift by a negative
            // amount and apply a correction thousands of times too large,
            // putting one band at an energy nothing downstream would question.
            if (fineBits[i] < 0 || fineBits[i] > MAX_FINE_BITS) {
                throw new IllegalArgumentException("band " + i + " was allocated "
                        + fineBits[i] + " fine energy bits, outside 0 to " + MAX_FINE_BITS);
            }
        }
    }

    @Override
    public String toString() {
        return "CeltEnergy[" + channels + " channels, " + bands + " bands]";
    }
}
