package net.chonkbase.assetpack.codec.opus;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The energy envelope, differentially against a second implementation of it.
 *
 * <p>Nothing about a wrong band energy announces itself. The range coder stays
 * in step, the frame decodes to the end, the allocation is unaffected, and what
 * comes out is a spectrum with one band -- or, once the inter-frame prediction
 * has carried the error forward, every band -- at the wrong level. So the whole
 * of this file is one shape: encode a known envelope with a transcription of
 * Appendix A's <em>encoder</em>, decode the result twice, once with
 * {@link CeltEnergy} and once with a transcription of Appendix A's
 * <em>decoder</em>, and require the two to agree to the last bit of every float.
 *
 * <p>Both transcriptions are in this file, both from {@code celt/quant_bands.c}:
 * {@code quant_coarse_energy_impl}, {@code quant_fine_energy} and
 * {@code quant_energy_finalise} on the encoding side, {@code unquant_coarse_energy},
 * {@code unquant_fine_energy} and {@code unquant_energy_finalise} on the decoding
 * side. They are the float build, so every {@code SHL32}, {@code PSHR32} and
 * {@code MULT16_16} in the C is the identity or a plain multiply; see
 * {@code celt/arch.h}. The tables are transcribed a second time as well, and
 * {@link #theTablesTranscribedHereAreTheOnesInAppendixA} checks that second
 * transcription against the reference source extracted from the RFC, so the
 * differential is anchored to Appendix A and not to a copy of itself.
 *
 * <p>Driving it from the encoder rather than from the conformance vectors is
 * what makes the test self-contained and, more usefully, what lets it choose
 * the frame sizes and budgets. A conformance vector never spends 3 bits on
 * twenty-one bands; the sweep below does, which is the only way to reach the
 * two fallback contexts {@code unquant_coarse_energy} drops to when the frame
 * runs out of room.
 */
class CeltEnergyTest {

    private static final int BANDS = 21;
    private static final int MAX_FINE_BITS = 8;

    // ------------------------------------------- tables, transcribed a second time

    /** {@code pred_coef} in {@code celt/quant_bands.c}: alpha, by LM. */
    private static final float[] PRED_COEF = {
        29440 / 32768.0f, 26112 / 32768.0f, 21248 / 32768.0f, 16384 / 32768.0f
    };

    /** {@code beta_coef} in {@code celt/quant_bands.c}: beta, by LM. */
    private static final float[] BETA_COEF = {
        30147 / 32768.0f, 22282 / 32768.0f, 12124 / 32768.0f, 6554 / 32768.0f
    };

    /** {@code beta_intra} in {@code celt/quant_bands.c}. */
    private static final float BETA_INTRA = 4915 / 32768.0f;

    /** {@code small_energy_icdf} in {@code celt/quant_bands.c}. */
    private static final short[] SMALL_ENERGY_ICDF = {2, 1, 0};

    /** {@code e_prob_model} in {@code celt/quant_bands.c}, {@code [LM][intra][2*band]}. */
    private static final int[][][] E_PROB_MODEL = {
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

    // ------------------------------------ Appendix A's decoder, transcribed

    /** {@code unquant_coarse_energy} in {@code celt/quant_bands.c}, float build. */
    private static void refUnquantCoarse(int[][][] probModel, RangeDecoder dec,
            int start, int end, float[] oldEBands, boolean intra, int channels, int lm) {
        int[] prob = probModel[lm][intra ? 1 : 0];
        float[] prev = new float[2];
        float coef;
        float beta;
        if (intra) {
            coef = 0;
            beta = BETA_INTRA;
        } else {
            beta = BETA_COEF[lm];
            coef = PRED_COEF[lm];
        }
        int budget = dec.frameBytes() * 8;
        for (int i = start; i < end; i++) {
            for (int c = 0; c < channels; c++) {
                int qi;
                int tell = dec.tell();
                if (budget - tell >= 15) {
                    int pi = 2 * Math.min(i, 20);
                    qi = Laplace.decode(dec, prob[pi] << 7, prob[pi + 1] << 6);
                } else if (budget - tell >= 2) {
                    qi = dec.decodeIcdf(SMALL_ENERGY_ICDF, 2);
                    qi = (qi >> 1) ^ -(qi & 1);
                } else if (budget - tell >= 1) {
                    qi = -dec.decodeBit(1);
                } else {
                    qi = -1;
                }
                float q = qi;
                int idx = i + c * BANDS;
                oldEBands[idx] = Math.max(-9.0f, oldEBands[idx]);
                float tmp = coef * oldEBands[idx] + prev[c] + q;
                oldEBands[idx] = tmp;
                prev[c] = prev[c] + q - beta * q;
            }
        }
    }

    /** {@code unquant_fine_energy} in {@code celt/quant_bands.c}, float build. */
    private static void refUnquantFine(RangeDecoder dec, int start, int end,
            float[] oldEBands, int[] fineQuant, int channels) {
        for (int i = start; i < end; i++) {
            if (fineQuant[i] <= 0) {
                continue;
            }
            for (int c = 0; c < channels; c++) {
                int q2 = dec.decodeRawBits(fineQuant[i]);
                float offset =
                        (q2 + 0.5f) * (1 << (14 - fineQuant[i])) * (1.0f / 16384) - 0.5f;
                oldEBands[i + c * BANDS] += offset;
            }
        }
    }

    /** {@code unquant_energy_finalise} in {@code celt/quant_bands.c}, float build. */
    private static void refUnquantFinalise(RangeDecoder dec, int start, int end,
            float[] oldEBands, int[] fineQuant, int[] finePriority, int bitsLeft,
            int channels) {
        for (int prio = 0; prio < 2; prio++) {
            for (int i = start; i < end && bitsLeft >= channels; i++) {
                if (fineQuant[i] >= MAX_FINE_BITS || finePriority[i] != prio) {
                    continue;
                }
                for (int c = 0; c < channels; c++) {
                    int q2 = dec.decodeRawBits(1);
                    float offset =
                            (q2 - 0.5f) * (1 << (14 - fineQuant[i] - 1)) * (1.0f / 16384);
                    oldEBands[i + c * BANDS] += offset;
                    bitsLeft--;
                }
            }
        }
    }

    // ------------------------------------ Appendix A's encoder, transcribed

    /**
     * {@code ec_laplace_get_freq1} in {@code celt/laplace.c}, transcribed.
     *
     * <p>Not {@link Laplace#freq1}: sharing it would put the same expression on
     * both sides of the round trip, where a wrong shift would cancel out and
     * leave a decoder that agrees with itself and with nothing else.
     */
    private static int laplaceFreq1(int zeroFreq, int decay) {
        return ((32768 - 2 * 16 - zeroFreq) * (16384 - decay)) >> 15;
    }

    /** {@code ec_laplace_encode} in {@code celt/laplace.c}; see {@code LaplaceTest}. */
    private static int laplaceEncode(RangeEncoder enc, int value, int fs, int decay) {
        int fl = 0;
        int val = value;
        int coded = value;
        if (val != 0) {
            int s = -(val < 0 ? 1 : 0);
            val = (val + s) ^ s;
            fl = fs;
            fs = laplaceFreq1(fs, decay);
            int i = 1;
            for (; fs > 0 && i < val; i++) {
                fs *= 2;
                fl += fs + 2;
                fs = (fs * decay) >> 15;
            }
            if (fs == 0) {
                int ndiMax = 32768 - fl;
                ndiMax = (ndiMax - s) >> 1;
                int di = Math.min(val - i, ndiMax - 1);
                fl += 2 * di + 1 + s;
                fs = Math.min(1, 32768 - fl);
                coded = (i + di + s) ^ s;
            } else {
                fs += 1;
                fl += fs & ~s;
            }
        }
        enc.encodeBin(fl, fl + fs, 15);
        return coded;
    }

    /**
     * {@code quant_coarse_energy_impl} in {@code celt/quant_bands.c}, float build.
     *
     * <p>Writes the intra flag itself, exactly as the reference does; the
     * decoder reads it in {@code celt_decode_with_ec} rather than in
     * {@code unquant_coarse_energy}, so the harness below reads it by hand.
     */
    private static void refQuantCoarse(int[][][] probModel, RangeEncoder enc,
            int start, int end, float[] eBands, float[] oldEBands, int budget,
            float[] error, int channels, int lm, boolean intra, float maxDecay) {
        int[] prob = probModel[lm][intra ? 1 : 0];
        float[] prev = new float[2];
        float coef;
        float beta;
        int tell = enc.tell();
        if (tell + 3 <= budget) {
            enc.encodeBit(intra ? 1 : 0, 3);
        }
        if (intra) {
            coef = 0;
            beta = BETA_INTRA;
        } else {
            beta = BETA_COEF[lm];
            coef = PRED_COEF[lm];
        }
        for (int i = start; i < end; i++) {
            for (int c = 0; c < channels; c++) {
                int idx = i + c * BANDS;
                float x = eBands[idx];
                float oldE = Math.max(-9.0f, oldEBands[idx]);
                float f = x - coef * oldE - prev[c];
                int qi = (int) Math.floor(0.5f + f);
                float decayBound = Math.max(-28.0f, oldEBands[idx]) - maxDecay;
                if (qi < 0 && x < decayBound) {
                    qi += (int) (decayBound - x);
                    if (qi > 0) {
                        qi = 0;
                    }
                }
                tell = enc.tell();
                int bitsLeft = budget - tell - 3 * channels * (end - i);
                if (i != start && bitsLeft < 30) {
                    if (bitsLeft < 24) {
                        qi = Math.min(1, qi);
                    }
                    if (bitsLeft < 16) {
                        qi = Math.max(-1, qi);
                    }
                }
                if (budget - tell >= 15) {
                    int pi = 2 * Math.min(i, 20);
                    qi = laplaceEncode(enc, qi, prob[pi] << 7, prob[pi + 1] << 6);
                } else if (budget - tell >= 2) {
                    qi = Math.max(-1, Math.min(qi, 1));
                    enc.encodeIcdf(2 * qi ^ -(qi < 0 ? 1 : 0), SMALL_ENERGY_ICDF, 2);
                } else if (budget - tell >= 1) {
                    qi = Math.min(0, qi);
                    enc.encodeBit(-qi, 1);
                } else {
                    qi = -1;
                }
                error[idx] = f - qi;
                float q = qi;
                float tmp = coef * oldE + prev[c] + q;
                oldEBands[idx] = tmp;
                prev[c] = prev[c] + q - beta * q;
            }
        }
    }

    /** {@code quant_fine_energy} in {@code celt/quant_bands.c}, float build. */
    private static void refQuantFine(RangeEncoder enc, int start, int end,
            float[] oldEBands, float[] error, int[] fineQuant, int channels) {
        for (int i = start; i < end; i++) {
            int frac = 1 << fineQuant[i];
            if (fineQuant[i] <= 0) {
                continue;
            }
            for (int c = 0; c < channels; c++) {
                int idx = i + c * BANDS;
                int q2 = (int) Math.floor((error[idx] + 0.5f) * frac);
                if (q2 > frac - 1) {
                    q2 = frac - 1;
                }
                if (q2 < 0) {
                    q2 = 0;
                }
                enc.encodeRawBits(q2, fineQuant[i]);
                float offset =
                        (q2 + 0.5f) * (1 << (14 - fineQuant[i])) * (1.0f / 16384) - 0.5f;
                oldEBands[idx] += offset;
                error[idx] -= offset;
            }
        }
    }

    /** {@code quant_energy_finalise} in {@code celt/quant_bands.c}, float build. */
    private static void refQuantFinalise(RangeEncoder enc, int start, int end,
            float[] oldEBands, float[] error, int[] fineQuant, int[] finePriority,
            int bitsLeft, int channels) {
        for (int prio = 0; prio < 2; prio++) {
            for (int i = start; i < end && bitsLeft >= channels; i++) {
                if (fineQuant[i] >= MAX_FINE_BITS || finePriority[i] != prio) {
                    continue;
                }
                for (int c = 0; c < channels; c++) {
                    int idx = i + c * BANDS;
                    int q2 = error[idx] < 0 ? 0 : 1;
                    enc.encodeRawBits(q2, 1);
                    float offset =
                            (q2 - 0.5f) * (1 << (14 - fineQuant[i] - 1)) * (1.0f / 16384);
                    oldEBands[idx] += offset;
                    bitsLeft--;
                }
            }
        }
    }

    // ----------------------------------------------------------- the harness

    /** One frame's worth of encoder input. */
    private record Frame(int lm, int channels, boolean intra, int start, int end,
            int bytes, float[] target, int[] fineBits, int[] finePriority) {
    }

    /** A frame after encoding: the bytes, and the encoder's own resulting envelope. */
    private record Coded(byte[] data, float[] encoderEnvelope, long finalRange) {
    }

    /**
     * Encodes one frame from {@code encoderState}, which it advances exactly as
     * the reference encoder's {@code oldEBands} is advanced.
     */
    private static Coded encode(int[][][] probModel, Frame frame, float[] encoderState) {
        byte[] data = new byte[frame.bytes()];
        RangeEncoder enc = new RangeEncoder(data);
        float[] error = new float[2 * BANDS];
        int budget = frame.bytes() * 8;
        float maxDecay = Math.min(16.0f, 0.125f * frame.bytes());

        refQuantCoarse(probModel, enc, frame.start(), frame.end(), frame.target(),
                encoderState, budget, error, frame.channels(), frame.lm(), frame.intra(),
                maxDecay);
        refQuantFine(enc, frame.start(), frame.end(), encoderState, error,
                frame.fineBits(), frame.channels());
        refQuantFinalise(enc, frame.start(), frame.end(), encoderState, error,
                frame.fineBits(), frame.finePriority(), budget - enc.tell(),
                frame.channels());
        long range = enc.finalRange();
        enc.finish();
        return new Coded(data, encoderState.clone(), range);
    }

    /** What one decode produced, from either implementation. */
    private record Decoded(float[] afterCoarse, float[] afterFine, float[] afterFinal,
            long finalRange, boolean intra) {
    }

    /** Decodes a frame with {@link CeltEnergy}. */
    private static Decoded decodeUnderTest(CeltEnergy energy, Frame frame, byte[] data) {
        RangeDecoder dec = new RangeDecoder(data);
        float[] out = new float[2 * BANDS];
        int total = data.length * 8;
        boolean intra = dec.tell() + 3 <= total && dec.decodeBit(3) != 0;

        energy.decodeCoarse(dec, frame.start(), frame.end(), frame.lm(), intra, out);
        float[] coarse = out.clone();
        energy.decodeFine(dec, frame.start(), frame.end(), frame.fineBits(), out);
        float[] fine = out.clone();
        energy.decodeFinal(dec, frame.start(), frame.end(), frame.fineBits(),
                frame.finePriority(), total - dec.tell(), out);
        return new Decoded(coarse, fine, out.clone(), dec.finalRange(), intra);
    }

    /** Decodes a frame with the transcription of Appendix A. */
    private static Decoded decodeReference(int[][][] probModel, Frame frame, byte[] data,
            float[] state) {
        RangeDecoder dec = new RangeDecoder(data);
        int total = data.length * 8;
        boolean intra = dec.tell() + 3 <= total && dec.decodeBit(3) != 0;

        refUnquantCoarse(probModel, dec, frame.start(), frame.end(), state, intra,
                frame.channels(), frame.lm());
        float[] coarse = state.clone();
        refUnquantFine(dec, frame.start(), frame.end(), state, frame.fineBits(),
                frame.channels());
        float[] fine = state.clone();
        refUnquantFinalise(dec, frame.start(), frame.end(), state, frame.fineBits(),
                frame.finePriority(), total - dec.tell(), frame.channels());
        return new Decoded(coarse, fine, state.clone(), dec.finalRange(), intra);
    }

    /** A named counter of how many individual band energies were compared. */
    private static final class Tally {
        int frames;
        int values;

        void compare(Frame frame, Decoded expected, Decoded actual, String label) {
            assertEquals(expected.intra(), actual.intra(),
                    label + ": the two decoders disagree about the intra flag");
            for (int c = 0; c < frame.channels(); c++) {
                for (int i = frame.start(); i < frame.end(); i++) {
                    int idx = i + c * BANDS;
                    compareOne(expected.afterCoarse()[idx], actual.afterCoarse()[idx],
                            label, "coarse", i, c);
                    compareOne(expected.afterFine()[idx], actual.afterFine()[idx],
                            label, "fine", i, c);
                    compareOne(expected.afterFinal()[idx], actual.afterFinal()[idx],
                            label, "final", i, c);
                    values += 3;
                }
            }
            assertEquals(expected.finalRange(), actual.finalRange(),
                    label + ": the range coders ended in different states, so one"
                    + " implementation read a symbol the other did not");
            frames++;
        }

        private static void compareOne(float expected, float actual, String label,
                String pass, int band, int channel) {
            if (Float.floatToIntBits(expected) != Float.floatToIntBits(actual)) {
                assertEquals(expected, actual, label + ": band " + band + " channel "
                        + channel + " after the " + pass + " pass");
            }
        }
    }

    // ------------------------------------------------------- scenario building

    /** A named, reproducible source of scenario parameters. */
    private static final class Rng {
        private long state;

        Rng(long seed) {
            this.state = seed * 6364136223846793005L + 1442695040888963407L;
        }

        int next(int bound) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            return (int) ((state >>> 33) % bound);
        }

        float energy() {
            // The units are 6 dB and eMeans has already been taken out, so real
            // content sits within about a factor of ten of zero. The tails go
            // well past that so that the encoder's decay bound and the -9 floor
            // are both reached.
            return (next(9000) - 4000) / 100.0f;
        }
    }

    /** Frames with room to spare, so every coding branch is the Laplace one. */
    private static List<Frame> roomyFrames() {
        List<Frame> frames = new ArrayList<>();
        Rng rng = new Rng(0x5EED_1234L);
        for (int lm = 0; lm < 4; lm++) {
            for (int channels = 1; channels <= 2; channels++) {
                for (int intra = 0; intra < 2; intra++) {
                    for (int variant = 0; variant < 16; variant++) {
                        int start = variant % 3 == 2 ? 17 : (variant % 3);
                        int end = start + 1 + rng.next(BANDS - start);
                        int[] fineBits = new int[BANDS];
                        int[] finePriority = new int[BANDS];
                        int fineTotal = 0;
                        for (int i = 0; i < BANDS; i++) {
                            fineBits[i] = rng.next(MAX_FINE_BITS + 1);
                            finePriority[i] = rng.next(2);
                            if (i >= start && i < end) {
                                fineTotal += fineBits[i] * channels;
                            }
                        }
                        float[] target = new float[2 * BANDS];
                        for (int i = 0; i < target.length; i++) {
                            target[i] = rng.energy();
                        }
                        // Enough for the worst case the coarse pass can cost,
                        // every fine bit asked for, one final bit per band per
                        // channel, and slack so the final pass has something to
                        // spend.
                        int bits = 16 + 15 * (end - start) * channels + fineTotal
                                + (end - start) * channels;
                        int bytes = (bits + 7) / 8 + 2 + rng.next(24);
                        frames.add(new Frame(lm, channels, intra == 1, start, end, bytes,
                                target, fineBits, finePriority));
                    }
                }
            }
        }
        return frames;
    }

    /** Frames squeezed until the coarse pass falls back to its cheaper contexts. */
    private static List<Frame> starvedFrames() {
        List<Frame> frames = new ArrayList<>();
        Rng rng = new Rng(0xC0FFEEL);
        int[] sizes = {1, 2, 3, 4, 5, 6, 8, 10, 12, 16, 20, 24, 32, 40};
        for (int lm = 0; lm < 4; lm++) {
            for (int channels = 1; channels <= 2; channels++) {
                for (int intra = 0; intra < 2; intra++) {
                    for (int bytes : sizes) {
                        float[] target = new float[2 * BANDS];
                        for (int i = 0; i < target.length; i++) {
                            target[i] = rng.energy();
                        }
                        frames.add(new Frame(lm, channels, intra == 1, 0, BANDS, bytes,
                                target, new int[BANDS], new int[BANDS]));
                    }
                }
            }
        }
        return frames;
    }

    // --------------------------------------------------------- the differential

    @Test
    void everyRoomyFrameDecodesIdenticallyInBothImplementations() {
        List<Frame> frames = roomyFrames();
        assertEquals(256, frames.size(), "the roomy sweep changed size");

        Tally tally = new Tally();
        for (Frame frame : frames) {
            float[] encoderState = new float[2 * BANDS];
            Coded coded = encode(E_PROB_MODEL, frame, encoderState);

            CeltEnergy energy = new CeltEnergy(CeltMode.forLm(frame.lm()), frame.channels());
            Decoded actual = decodeUnderTest(energy, frame, coded.data());
            Decoded expected = decodeReference(E_PROB_MODEL, frame, coded.data(),
                    new float[2 * BANDS]);
            tally.compare(frame, expected, actual, "roomy lm=" + frame.lm()
                    + " channels=" + frame.channels() + " intra=" + frame.intra()
                    + " bands [" + frame.start() + "," + frame.end() + ") "
                    + frame.bytes() + " bytes");

            // The encoder's own envelope is what the decoder must reconstruct.
            // Comparing against it as well is what turns a pair of agreeing
            // transcriptions into evidence that the format round-trips at all.
            for (int c = 0; c < frame.channels(); c++) {
                for (int i = frame.start(); i < frame.end(); i++) {
                    int idx = i + c * BANDS;
                    assertEquals(coded.encoderEnvelope()[idx], actual.afterFinal()[idx],
                            "band " + i + " channel " + c + " came back different from"
                            + " what the encoder recorded");
                }
            }
            assertEquals(coded.finalRange(), actual.finalRange(),
                    "the decoder's range must match the encoder's, which is the"
                    + " conformance test RFC 6716 section 4 defines");
        }

        assertEquals(256, tally.frames);
        assertEquals(9615, tally.values,
                "the roomy sweep should have compared 9615 band energies, compared "
                + tally.values);
    }

    @Test
    void everyStarvedFrameDecodesIdenticallyInBothImplementations() {
        List<Frame> frames = starvedFrames();
        assertEquals(224, frames.size(), "the starved sweep changed size");

        Tally tally = new Tally();
        for (Frame frame : frames) {
            float[] encoderState = new float[2 * BANDS];
            Coded coded = encode(E_PROB_MODEL, frame, encoderState);

            CeltEnergy energy = new CeltEnergy(CeltMode.forLm(frame.lm()), frame.channels());
            Decoded actual = decodeUnderTest(energy, frame, coded.data());
            Decoded expected = decodeReference(E_PROB_MODEL, frame, coded.data(),
                    new float[2 * BANDS]);
            tally.compare(frame, expected, actual, "starved lm=" + frame.lm()
                    + " channels=" + frame.channels() + " intra=" + frame.intra()
                    + " " + frame.bytes() + " bytes");
        }

        assertEquals(224, tally.frames);
        assertEquals(21168, tally.values,
                "the starved sweep should have compared 21168 band energies, compared "
                + tally.values);
    }

    @Test
    void theStarvedSweepActuallyReachesAllThreeCodingBranches() {
        // Without this the sweep above could be running one branch 224 times and
        // proving nothing about the other two. The branch is not observable from
        // outside, so it is inferred from what the frame can afford: a one-byte
        // frame has eight bits of budget and cannot reach the fifteen a Laplace
        // symbol needs, and by the twenty-first band it cannot reach one.
        int laplace = 0;
        int small = 0;
        int single = 0;
        int none = 0;
        for (Frame frame : starvedFrames()) {
            float[] encoderState = new float[2 * BANDS];
            Coded coded = encode(E_PROB_MODEL, frame, encoderState);
            RangeDecoder dec = new RangeDecoder(coded.data());
            int budget = frame.bytes() * 8;
            if (dec.tell() + 3 <= budget) {
                dec.decodeBit(3);
            }
            for (int i = frame.start(); i < frame.end(); i++) {
                for (int c = 0; c < frame.channels(); c++) {
                    int tell = dec.tell();
                    if (budget - tell >= 15) {
                        laplace++;
                        Laplace.decode(dec, E_PROB_MODEL[frame.lm()][frame.intra() ? 1 : 0]
                                [2 * Math.min(i, 20)] << 7,
                                E_PROB_MODEL[frame.lm()][frame.intra() ? 1 : 0]
                                [2 * Math.min(i, 20) + 1] << 6);
                    } else if (budget - tell >= 2) {
                        small++;
                        dec.decodeIcdf(SMALL_ENERGY_ICDF, 2);
                    } else if (budget - tell >= 1) {
                        single++;
                        dec.decodeBit(1);
                    } else {
                        none++;
                    }
                }
            }
        }
        assertTrue(laplace > 1000, "the Laplace branch ran " + laplace + " times");
        assertTrue(small > 100, "the small-energy icdf branch ran " + small + " times");
        assertTrue(single > 10, "the single-bit branch ran " + single + " times");
        assertTrue(none > 1000, "the no-budget branch ran " + none + " times");
    }

    @Test
    void multiFrameSequencesDecodeIdenticallyInBothImplementations() {
        // The differential above resets both decoders between frames, which
        // would let an implementation that ignored the previous frame pass. This
        // one carries both states across a run of frames.
        Tally tally = new Tally();
        Rng rng = new Rng(0xBEEF_0007L);
        int sequences = 0;
        for (int lm = 0; lm < 4; lm++) {
            for (int channels = 1; channels <= 2; channels++) {
                for (int trial = 0; trial < 4; trial++) {
                    float[] encoderState = new float[2 * BANDS];
                    float[] referenceState = new float[2 * BANDS];
                    CeltEnergy energy =
                            new CeltEnergy(CeltMode.forLm(lm), channels);

                    for (int frameIndex = 0; frameIndex < 12; frameIndex++) {
                        int start = 0;
                        int end = BANDS;
                        int[] fineBits = new int[BANDS];
                        int[] finePriority = new int[BANDS];
                        int fineTotal = 0;
                        for (int i = 0; i < BANDS; i++) {
                            fineBits[i] = rng.next(5);
                            finePriority[i] = rng.next(2);
                            fineTotal += fineBits[i] * channels;
                        }
                        float[] target = new float[2 * BANDS];
                        for (int i = 0; i < target.length; i++) {
                            target[i] = rng.energy();
                        }
                        int bits = 16 + 15 * BANDS * channels + fineTotal
                                + BANDS * channels;
                        int bytes = (bits + 7) / 8 + 2 + rng.next(16);
                        boolean intra = frameIndex == 0 || rng.next(6) == 0;

                        Frame frame = new Frame(lm, channels, intra, start, end, bytes,
                                target, fineBits, finePriority);
                        Coded coded = encode(E_PROB_MODEL, frame, encoderState);
                        Decoded actual = decodeUnderTest(energy, frame, coded.data());
                        Decoded expected = decodeReference(E_PROB_MODEL, frame,
                                coded.data(), referenceState);
                        tally.compare(frame, expected, actual, "sequence lm=" + lm
                                + " channels=" + channels + " trial=" + trial
                                + " frame=" + frameIndex);

                        for (int c = 0; c < channels; c++) {
                            for (int i = 0; i < BANDS; i++) {
                                int idx = i + c * BANDS;
                                assertEquals(coded.encoderEnvelope()[idx],
                                        actual.afterFinal()[idx],
                                        "frame " + frameIndex + " band " + i + " channel "
                                        + c + " drifted from the encoder's envelope,"
                                        + " which is what an inter-frame prediction"
                                        + " defect looks like");
                            }
                        }
                    }
                    sequences++;
                }
            }
        }
        assertEquals(32, sequences);
        assertEquals(32 * 12, tally.frames);
        assertEquals(36288, tally.values,
                "compared " + tally.values + " band energies across the sequences");
    }

    // ------------------------------------------------- the state between frames

    @Test
    void decodingFrameTwoDependsOnFrameOne() {
        // The assertion this whole class exists for. Every single-frame test
        // above passes for a decoder that resets its envelope each frame, and
        // such a decoder is wrong on every inter-frame frame in every stream.
        Frame first = simpleFrame(3, 2, true, 0);
        Frame second = simpleFrame(3, 2, false, 1);

        float[] encoderState = new float[2 * BANDS];
        byte[] firstData = encode(E_PROB_MODEL, first, encoderState).data();
        byte[] secondData = encode(E_PROB_MODEL, second, encoderState).data();

        CeltEnergy carried = new CeltEnergy(CeltMode.forLm(3), 2);
        decodeUnderTest(carried, first, firstData);
        float[] afterSequence = decodeUnderTest(carried, second, secondData).afterFinal();

        CeltEnergy fresh = new CeltEnergy(CeltMode.forLm(3), 2);
        float[] aloneFromReset = decodeUnderTest(fresh, second, secondData).afterFinal();

        int differing = countDifferences(afterSequence, aloneFromReset, 2 * BANDS);
        assertTrue(differing > 30, "decoding the second frame after the first must differ"
                + " from decoding it from a reset state, and only " + differing
                + " of 42 band energies did");

        // And the second frame must equal what the encoder recorded only when the
        // first frame preceded it, which is the same statement from the other side.
        float[] encoderTruth = encoderState;
        for (int i = 0; i < 2 * BANDS; i++) {
            assertEquals(encoderTruth[i], afterSequence[i],
                    "band " + i + " of the second frame, decoded in sequence");
        }
    }

    @Test
    void resettingBetweenFramesReproducesTheFirstFrameExactly() {
        // reset() has to put the object back where the constructor left it, or a
        // stream restarted after a seek decodes differently from the same stream
        // played from the top.
        Frame frame = simpleFrame(2, 1, false, 5);
        float[] encoderState = new float[2 * BANDS];
        byte[] data = encode(E_PROB_MODEL, frame, encoderState).data();

        CeltEnergy energy = new CeltEnergy(CeltMode.forLm(2), 1);
        float[] first = decodeUnderTest(energy, frame, data).afterFinal();
        float[] second = decodeUnderTest(energy, frame, data).afterFinal();
        energy.reset();
        float[] third = decodeUnderTest(energy, frame, data).afterFinal();

        assertTrue(countDifferences(first, second, BANDS) > 15,
                "decoding the same frame twice in a row must not give the same answer,"
                + " because the second decode predicts from the first, and only "
                + countDifferences(first, second, BANDS) + " of " + BANDS
                + " bands differed");
        assertArrayEquals(first, third,
                "reset() must put the envelope back where the constructor left it");
    }

    @Test
    void anIntraFrameIgnoresWhateverCameBefore() {
        // alpha is zero for an intra frame, so its output cannot depend on the
        // state. That is what makes an intra frame a recovery point after packet
        // loss, and a decoder that leaked the previous frame into one would never
        // recover from a dropped packet.
        Frame first = simpleFrame(1, 2, false, 9);
        Frame second = simpleFrame(1, 2, true, 10);

        float[] encoderState = new float[2 * BANDS];
        byte[] firstData = encode(E_PROB_MODEL, first, encoderState).data();
        byte[] secondData = encode(E_PROB_MODEL, second, encoderState).data();

        CeltEnergy carried = new CeltEnergy(CeltMode.forLm(1), 2);
        decodeUnderTest(carried, first, firstData);
        float[] afterSequence = decodeUnderTest(carried, second, secondData).afterFinal();

        CeltEnergy fresh = new CeltEnergy(CeltMode.forLm(1), 2);
        float[] alone = decodeUnderTest(fresh, second, secondData).afterFinal();

        assertArrayEquals(afterSequence, alone,
                "an intra frame must decode to the same envelope whatever preceded it");
    }

    @Test
    void thePreviousFrameIsFlooredAtMinusNineBeforeItIsPredictedFrom() {
        // MAX16(-9.f, oldEBands[i]) in unquant_coarse_energy. A band that has
        // been driven far below -9 -- which the silence path does, to -28 --
        // must not drag the next frame's prediction down with it.
        Frame frame = simpleFrame(3, 1, false, 21);
        float[] encoderState = new float[2 * BANDS];
        byte[] data = encode(E_PROB_MODEL, frame, encoderState).data();

        CeltEnergy floored = new CeltEnergy(CeltMode.forLm(3), 1);
        floored.setSilent(new float[2 * BANDS]);
        float[] fromSilence = decodeUnderTest(floored, frame, data).afterFinal();

        // -28 and -9 are both at or below the floor, so both must predict the
        // same. Driven through the reference transcription, so the claim is
        // about the format and not about one implementation of it.
        float[] atTheFloor = new float[2 * BANDS];
        java.util.Arrays.fill(atTheFloor, -9.0f);
        float[] fromFloor =
                decodeReference(E_PROB_MODEL, frame, data, atTheFloor).afterFinal();

        for (int i = 0; i < BANDS; i++) {
            assertEquals(fromFloor[i], fromSilence[i],
                    "band " + i + ": a band at -28 and a band at -9 must give the same"
                    + " prediction, because the reference floors both at -9 before"
                    + " multiplying by alpha");
        }

        // And a band above the floor must not, or the floor would be a clamp on
        // everything and the time prediction would do nothing at all.
        float[] wellAbove = new float[2 * BANDS];
        java.util.Arrays.fill(wellAbove, 4.0f);
        float[] fromAbove =
                decodeReference(E_PROB_MODEL, frame, data, wellAbove).afterFinal();
        assertTrue(countDifferences(fromFloor, fromAbove, BANDS) > 15,
                "a previous frame above the floor must change the prediction");
    }

    /** How many of the first {@code n} entries differ bit for bit. */
    private static int countDifferences(float[] a, float[] b, int n) {
        int differing = 0;
        for (int i = 0; i < n; i++) {
            if (Float.floatToIntBits(a[i]) != Float.floatToIntBits(b[i])) {
                differing++;
            }
        }
        return differing;
    }

    private static Frame simpleFrame(int lm, int channels, boolean intra, int seed) {
        Rng rng = new Rng(0x1000L + seed);
        int[] fineBits = new int[BANDS];
        int[] finePriority = new int[BANDS];
        int fineTotal = 0;
        for (int i = 0; i < BANDS; i++) {
            fineBits[i] = rng.next(5);
            finePriority[i] = rng.next(2);
            fineTotal += fineBits[i] * channels;
        }
        float[] target = new float[2 * BANDS];
        for (int i = 0; i < target.length; i++) {
            target[i] = rng.energy();
        }
        int bits = 16 + 15 * BANDS * channels + fineTotal + BANDS * channels;
        return new Frame(lm, channels, intra, 0, BANDS, (bits + 7) / 8 + 8, target,
                fineBits, finePriority);
    }

    // ----------------------------------------------------- the state management

    @Test
    void clearingOutsideTheCodedRangeZeroesExactlyTheUncodedBands() {
        CeltEnergy energy = new CeltEnergy(CeltMode.forLm(3), 2);
        float[] out = new float[2 * BANDS];
        energy.setSilent(out);
        for (float v : out) {
            assertEquals(CeltEnergy.SILENCE_LOG_ENERGY, v);
        }

        energy.clearOutsideCodedRange(5, 17, out);
        for (int c = 0; c < 2; c++) {
            for (int i = 0; i < BANDS; i++) {
                float expected = i >= 5 && i < 17 ? CeltEnergy.SILENCE_LOG_ENERGY : 0.0f;
                assertEquals(expected, out[i + c * BANDS],
                        "band " + i + " channel " + c + " after clearing outside [5,17)");
            }
        }
    }

    @Test
    void bandsOutsideTheCodedRangeAreNeverTouchedByADecode() {
        // start and end restrict the coded range; a decoder that ran the loop
        // over all 21 bands anyway would read the right symbols into the wrong
        // places and put energy where a Hybrid frame's SILK layer already has it.
        Frame frame = new Frame(3, 2, true, 6, 15, 200, new float[2 * BANDS],
                new int[BANDS], new int[BANDS]);
        Rng rng = new Rng(77);
        for (int i = 0; i < frame.target().length; i++) {
            frame.target()[i] = rng.energy();
        }
        for (int i = 0; i < BANDS; i++) {
            frame.fineBits()[i] = 3;
        }

        float[] encoderState = new float[2 * BANDS];
        byte[] data = encode(E_PROB_MODEL, frame, encoderState).data();

        CeltEnergy energy = new CeltEnergy(CeltMode.forLm(3), 2);
        float[] out = new float[2 * BANDS];
        energy.setSilent(out);

        RangeDecoder dec = new RangeDecoder(data);
        int total = data.length * 8;
        boolean intra = dec.decodeBit(3) != 0;
        energy.decodeCoarse(dec, frame.start(), frame.end(), frame.lm(), intra, out);
        energy.decodeFine(dec, frame.start(), frame.end(), frame.fineBits(), out);
        energy.decodeFinal(dec, frame.start(), frame.end(), frame.fineBits(),
                frame.finePriority(), total - dec.tell(), out);

        for (int c = 0; c < 2; c++) {
            for (int i = 0; i < BANDS; i++) {
                if (i < frame.start() || i >= frame.end()) {
                    assertEquals(CeltEnergy.SILENCE_LOG_ENERGY, out[i + c * BANDS],
                            "band " + i + " channel " + c + " is outside [" + frame.start()
                            + "," + frame.end() + ") and must be untouched");
                }
            }
        }
    }

    // ------------------------------------------------------------- rejections

    @Test
    void malformedArgumentsAreRefusedWithAMessage() {
        CeltEnergy energy = new CeltEnergy(CeltMode.forLm(3), 2);
        RangeDecoder dec = new RangeDecoder(new byte[40]);
        float[] out = new float[2 * BANDS];
        int[] fine = new int[BANDS];
        int[] prio = new int[BANDS];

        assertThrows(IllegalArgumentException.class,
                () -> new CeltEnergy(CeltMode.forLm(0), 3));
        assertThrows(IllegalArgumentException.class,
                () -> new CeltEnergy(CeltMode.forLm(0), 0));
        assertThrows(NullPointerException.class, () -> new CeltEnergy(null, 1));

        assertThrows(IllegalArgumentException.class,
                () -> energy.decodeCoarse(dec, 0, BANDS + 1, 3, false, out),
                "a coded range past the last band would read off the end of the tables");
        assertThrows(IllegalArgumentException.class,
                () -> energy.decodeCoarse(dec, -1, BANDS, 3, false, out));
        assertThrows(IllegalArgumentException.class,
                () -> energy.decodeCoarse(dec, 10, 4, 3, false, out));
        assertThrows(IllegalArgumentException.class,
                () -> energy.decodeCoarse(dec, 0, BANDS, 4, false, out),
                "there are four frame sizes and so four rows of e_prob_model");
        assertThrows(IllegalArgumentException.class,
                () -> energy.decodeCoarse(dec, 0, BANDS, -1, false, out));
        assertThrows(IllegalArgumentException.class,
                () -> energy.decodeCoarse(dec, 0, BANDS, 3, false, new float[BANDS]),
                "a stereo envelope does not fit an array of one channel's bands");
        assertThrows(NullPointerException.class,
                () -> energy.decodeCoarse(null, 0, BANDS, 3, false, out));

        int[] tooManyBits = new int[BANDS];
        tooManyBits[7] = MAX_FINE_BITS + 1;
        assertThrows(IllegalArgumentException.class,
                () -> energy.decodeFine(dec, 0, BANDS, tooManyBits, out),
                "nine fine bits would shift 1<<(14-bits) by more than the format allows");
        int[] negativeBits = new int[BANDS];
        negativeBits[2] = -1;
        assertThrows(IllegalArgumentException.class,
                () -> energy.decodeFine(dec, 0, BANDS, negativeBits, out));
        assertThrows(IllegalArgumentException.class,
                () -> energy.decodeFine(dec, 0, BANDS, new int[BANDS - 1], out));
        assertThrows(IllegalArgumentException.class,
                () -> energy.decodeFinal(dec, 0, BANDS, fine, new int[BANDS - 1], 40, out));
        assertThrows(NullPointerException.class,
                () -> energy.decodeFinal(dec, 0, BANDS, fine, null, 40, out));
        assertThrows(NullPointerException.class,
                () -> energy.decodeFine(dec, 0, BANDS, null, out));

        // A range that codes nothing is legal and must simply do nothing.
        energy.decodeCoarse(dec, 4, 4, 3, false, out);
        energy.decodeFine(dec, 4, 4, fine, out);
        energy.decodeFinal(dec, 4, 4, fine, prio, 0, out);
    }

    @Test
    void aTruncatedFrameDecodesToSomethingRatherThanThrowing() {
        // RFC 6716 section 4.1.5: a decoder must conceal rather than fail. Every
        // band must come back finite, because a NaN here would propagate through
        // log2Amp into the MDCT and silence the rest of the stream.
        for (int bytes = 0; bytes <= 6; bytes++) {
            CeltEnergy energy = new CeltEnergy(CeltMode.forLm(3), 2);
            RangeDecoder dec = new RangeDecoder(new byte[bytes]);
            float[] out = new float[2 * BANDS];
            int[] fine = new int[BANDS];
            java.util.Arrays.fill(fine, 4);
            int[] prio = new int[BANDS];

            energy.decodeCoarse(dec, 0, BANDS, 3, false, out);
            energy.decodeFine(dec, 0, BANDS, fine, out);
            energy.decodeFinal(dec, 0, BANDS, fine, prio,
                    Math.max(0, bytes * 8 - dec.tell()), out);
            for (int i = 0; i < out.length; i++) {
                assertTrue(Float.isFinite(out[i]),
                        "band " + i + " of a " + bytes + "-byte frame came back "
                        + out[i]);
                assertTrue(out[i] > -200.0f && out[i] < 200.0f,
                        "band " + i + " of a " + bytes + "-byte frame came back "
                        + out[i] + ", far outside anything the format can mean");
            }
        }
    }

    // ------------------------------------------- against Appendix A's own tables

    @Test
    void theTablesTranscribedHereAreTheOnesInAppendixA() {
        assumeTrue(RfcSource.path() != null, RfcSource.skipReason());

        int[] flat = RfcSource.referenceArray("celt/quant_bands.c", "e_prob_model");
        assertEquals(4 * 2 * 42, flat.length, "e_prob_model should hold 336 bytes");
        int k = 0;
        for (int lm = 0; lm < 4; lm++) {
            for (int intra = 0; intra < 2; intra++) {
                for (int i = 0; i < 42; i++) {
                    assertEquals(flat[k], E_PROB_MODEL[lm][intra][i],
                            "e_prob_model[" + lm + "][" + intra + "][" + i + "]");
                    k++;
                }
            }
        }

        // The float build writes these as Q15 integers divided by 32768, so the
        // fixed-point declaration -- which referenceArray finds first -- carries
        // the same numbers with no rounding in between.
        int[] pred = RfcSource.referenceArray("celt/quant_bands.c", "pred_coef");
        assertArrayEquals(new int[] {29440, 26112, 21248, 16384}, pred,
                "pred_coef in celt/quant_bands.c");
        for (int lm = 0; lm < 4; lm++) {
            assertEquals(pred[lm] / 32768.0f, PRED_COEF[lm], "alpha for LM " + lm);
        }

        int[] beta = RfcSource.referenceArray("celt/quant_bands.c", "beta_coef");
        assertArrayEquals(new int[] {30147, 22282, 12124, 6554}, beta,
                "beta_coef in celt/quant_bands.c");
        for (int lm = 0; lm < 4; lm++) {
            assertEquals(beta[lm] / 32768.0f, BETA_COEF[lm], "beta for LM " + lm);
        }

        Matcher intraBeta = Pattern.compile("beta_intra\\s*=\\s*(\\d+)\\s*;")
                .matcher(RfcSource.referenceSource("celt/quant_bands.c"));
        assertTrue(intraBeta.find(), "beta_intra is not declared in the reference source");
        assertEquals(4915, Integer.parseInt(intraBeta.group(1)));
        assertEquals(4915 / 32768.0f, BETA_INTRA);

        int[] icdf = RfcSource.referenceArray("celt/quant_bands.c", "small_energy_icdf");
        assertArrayEquals(new int[] {2, 1, 0}, icdf, "small_energy_icdf");
        for (int i = 0; i < icdf.length; i++) {
            assertEquals(icdf[i], SMALL_ENERGY_ICDF[i]);
        }

        Matcher maxFine = Pattern.compile("#define\\s+MAX_FINE_BITS\\s+(\\d+)")
                .matcher(RfcSource.referenceSource("celt/rate.h"));
        assertTrue(maxFine.find(), "MAX_FINE_BITS is not defined in celt/rate.h");
        assertEquals(MAX_FINE_BITS, Integer.parseInt(maxFine.group(1)));
        assertEquals(CeltEnergy.MAX_FINE_BITS, Integer.parseInt(maxFine.group(1)));
    }

    @Test
    void theFineBitCeilingAgreesWithTheAllocatorThatEnforcesIt() {
        // The allocator caps fine_quant at MAX_FINE_BITS and the final pass here
        // refuses to add to a band that reached it. If the two numbers ever
        // parted company, the decoder would read one raw bit per top-priority
        // band that the encoder never wrote, and every raw field after it in the
        // frame -- which is every remaining fine bit -- would come back shifted.
        assertEquals(CeltAllocation.MAX_FINE_BITS, CeltEnergy.MAX_FINE_BITS,
                "the allocator's fine bit ceiling and the energy decoder's must be the"
                + " same number, because they are the same number in celt/rate.h");
    }

    @Test
    void theRfcStatesTheOneCoefficientItPrintsAndAppendixAAgrees() {
        assumeTrue(RfcSource.path() != null, RfcSource.skipReason());
        String prose = RfcSource.text();
        assertTrue(prose.contains("beta=4915/32768 when using intra"),
                "RFC 6716 section 4.3.2.1 should still state beta_intra in words; if it"
                + " no longer does, the only source for it is Appendix A");
        assertEquals(4915 / 32768.0f, BETA_INTRA);

        // Section 4.3.2.2 states the fine correction in closed form. The
        // reference computes it as (q2+.5f)*(1<<(14-B))/16384 - .5f, which is the
        // same expression with the division written as a shift; check they agree
        // over every combination the format allows.
        assertTrue(prose.contains("(f+1/2)/2**B_i - 1/2"),
                "RFC 6716 section 4.3.2.2 should still give the fine energy mapping");
        for (int b = 1; b <= MAX_FINE_BITS; b++) {
            for (int f = 0; f < (1 << b); f++) {
                float fromProse = (f + 0.5f) / (1 << b) - 0.5f;
                float fromReference = (f + 0.5f) * (1 << (14 - b)) * (1.0f / 16384) - 0.5f;
                assertEquals(fromProse, fromReference,
                        "the prose and the reference disagree on the correction for f="
                        + f + " out of " + b + " bits");
            }
        }
    }
}
