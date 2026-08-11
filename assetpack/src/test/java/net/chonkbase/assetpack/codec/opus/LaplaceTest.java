package net.chonkbase.assetpack.codec.opus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The Laplace coder, proved by round trip against a transcription of the
 * encoder half.
 *
 * <p>{@link Laplace} only decodes, because only decoding is needed, which
 * leaves nothing in the tree to compare it against. So
 * {@code ec_laplace_encode} from {@code celt/laplace.c} is transcribed below
 * and the two are driven against each other. The encoder is the right oracle
 * rather than merely a convenient one: the two functions walk the same
 * distribution in different ways -- the encoder accumulates a lower bound while
 * counting down the magnitude it was given, the decoder accumulates the same
 * bound while comparing against a point it read -- so agreement between them is
 * evidence about the shape of the distribution rather than about one
 * transcription of one loop.
 *
 * <p>The parameter space swept is every {@code (fs, decay)} pair the
 * {@code e_prob_model} table of {@code celt/quant_bands.c} could produce. That
 * table holds a pair of Q8 bytes per band per frame size per prediction type;
 * the zero-probability byte spans 21 to 192 and the decay byte spans 6 to 179,
 * and this file sweeps the whole of both spans rather than only the 159 pairs
 * that actually appear. The superset costs nothing and does not have to be kept
 * in step with a second copy of the table.
 *
 * <p>The other thing only the encoder can show is the "run out of range"
 * behaviour. Past some magnitude -- 17 to 65 steps, depending on the model --
 * the distribution has no symbol left, and {@code ec_laplace_encode} codes the
 * largest one it does have, reporting the substitution by overwriting its
 * {@code *value} argument. A round trip that compared against the value handed
 * in rather than the value written would look like a decoder defect at exactly
 * the magnitudes a loud transient produces.
 */
class LaplaceTest {

    /** {@code LAPLACE_MINP}, repeated so the transcription reads like the C. */
    private static final int MINP = 1;

    /** {@code LAPLACE_LOG_MINP}. */
    private static final int LOG_MINP = 0;

    /** The context total, {@code 1<<15}. */
    private static final int TOTAL = 32768;

    /** Lowest zero-probability byte in {@code e_prob_model}. */
    private static final int MIN_PROB_BYTE = 21;

    /** Highest zero-probability byte in {@code e_prob_model}. */
    private static final int MAX_PROB_BYTE = 192;

    /** Lowest decay byte in {@code e_prob_model}. */
    private static final int MIN_DECAY_BYTE = 6;

    /** Highest decay byte in {@code e_prob_model}. */
    private static final int MAX_DECAY_BYTE = 179;

    // --------------------------------------------------------------- oracle

    /**
     * {@code ec_laplace_get_freq1} in {@code celt/laplace.c}, transcribed.
     *
     * <p>Deliberately not {@link Laplace#freq1}. Sharing that one method would
     * put the same expression on both sides of every round trip below, and a
     * wrong shift or a wrong {@code LAPLACE_NMIN} would cancel out perfectly --
     * the sweep would pass while the decoder disagreed with every other Opus
     * implementation in the world.
     */
    private static int freq1(int zeroFreq, int decay) {
        int ft = TOTAL - MINP * (2 * 16) - zeroFreq;
        return (ft * (16384 - decay)) >> 15;
    }

    /**
     * {@code ec_laplace_encode} in {@code celt/laplace.c}, transcribed.
     *
     * <p>C reports the value it actually coded by writing back through its
     * {@code int *value} argument; here that is the return value.
     */
    private static int laplaceEncode(RangeEncoder enc, int value, int fs, int decay) {
        int fl = 0;
        int val = value;
        int coded = value;
        if (val != 0) {
            int s = -(val < 0 ? 1 : 0);
            val = (val + s) ^ s;
            fl = fs;
            fs = freq1(fs, decay);
            int i = 1;
            for (; fs > 0 && i < val; i++) {
                fs *= 2;
                fl += fs + 2 * MINP;
                fs = (fs * decay) >> 15;
            }
            if (fs == 0) {
                int ndiMax = (TOTAL - fl + MINP - 1) >> LOG_MINP;
                ndiMax = (ndiMax - s) >> 1;
                int di = Math.min(val - i, ndiMax - 1);
                fl += (2 * di + 1 + s) * MINP;
                fs = Math.min(MINP, TOTAL - fl);
                coded = (i + di + s) ^ s;
            } else {
                fs += MINP;
                fl += fs & ~s;
            }
            // The reference's own two assertions, kept because they are the
            // invariant that makes the format work at all: the geometric part
            // of the distribution sums to exactly the total minus the 32 units
            // LAPLACE_NMIN reserves, so it can fill the context but never
            // overrun it.
            assertTrue(fl + fs <= TOTAL, "celt_assert(fl+fs<=32768) failed at value "
                    + value + " with fs=" + fs + " decay=" + decay + ": fl=" + fl);
            assertTrue(fs > 0, "celt_assert(fs>0) failed at value " + value
                    + " with fs=" + fs + " decay=" + decay);
        }
        enc.encodeBin(fl, fl + fs, 15);
        return coded;
    }

    // ------------------------------------------------------------ round trips

    @Test
    void everyParameterPairTheEnergyModelCanProduceSurvivesARoundTrip() {
        int[] values = {
            0, 1, -1, 2, -2, 3, -3, 4, -4, 5, -5, 7, -7, 11, -11, 16, -16,
            17, -17, 23, -23, 32, -32, 52, -52, 65, -65, 66, -66, 200, -200,
            32768, -32768
        };
        assertEquals(33, values.length, "the compact value sweep changed size");

        byte[] buffer = new byte[512];
        RangeEncoder enc = new RangeEncoder(buffer);
        RangeDecoder dec = new RangeDecoder(buffer);
        int[] coded = new int[values.length];

        int cases = 0;
        int pairs = 0;
        int substituted = 0;
        for (int p = MIN_PROB_BYTE; p <= MAX_PROB_BYTE; p++) {
            for (int d = MIN_DECAY_BYTE; d <= MAX_DECAY_BYTE; d++) {
                int fs = p << 7;
                int decay = d << 6;
                pairs++;

                enc.init(buffer, 0, buffer.length);
                for (int i = 0; i < values.length; i++) {
                    coded[i] = laplaceEncode(enc, values[i], fs, decay);
                    if (coded[i] != values[i]) {
                        substituted++;
                    }
                }
                int bytes = enc.finish();

                dec.init(buffer, 0, bytes);
                for (int i = 0; i < values.length; i++) {
                    int got = Laplace.decode(dec, fs, decay);
                    if (got != coded[i]) {
                        // Built lazily: this string would otherwise be
                        // assembled a million times to be thrown away.
                        assertEquals(coded[i], got, "value " + values[i] + " was coded as "
                                + coded[i] + " and came back as " + got
                                + " with fs=" + fs + " (byte " + p + ") decay=" + decay
                                + " (byte " + d + ")");
                    }
                    cases++;
                }
                if (enc.finalRange() != dec.finalRange()) {
                    assertEquals(enc.finalRange(), dec.finalRange(),
                            "encoder and decoder ranges diverged over a whole sweep at"
                            + " fs=" + fs + " decay=" + decay + ", so some symbol was read"
                            + " with a different width from the one it was written with");
                }
            }
        }

        // Count after checking, so that a sweep which silently covered nothing
        // cannot pass by having no assertions to fail.
        assertEquals(172 * 174, pairs,
                "the zero-probability and decay byte spans of e_prob_model should give"
                + " 29928 parameter pairs, swept " + pairs);
        assertEquals(29928 * 33, cases, "every pair should have run every value");
        assertTrue(substituted > 100_000,
                "the sweep must reach magnitudes ec_laplace_encode cannot represent, and"
                + " only " + substituted + " values were substituted");
    }

    @Test
    void everyRepresentableMagnitudeRoundTripsAndEverythingPastItClampsToTheEdge() {
        // The interesting boundary in the whole coder: where the geometric part
        // of the distribution runs out and the flat LAPLACE_MINP tail takes
        // over, and then where the tail itself runs out. A decoder that got the
        // first boundary wrong decodes small energy steps correctly and large
        // ones as noise; one that got the second wrong desynchronises the range
        // coder on the loudest frames only.
        byte[] buffer = new byte[1024];
        RangeEncoder enc = new RangeEncoder(buffer);
        RangeDecoder dec = new RangeDecoder(buffer);

        int cases = 0;
        int pairs = 0;
        int smallestLimit = Integer.MAX_VALUE;
        int largestLimit = 0;
        for (int p = MIN_PROB_BYTE; p <= MAX_PROB_BYTE; p += 4) {
            for (int d = MIN_DECAY_BYTE; d <= MAX_DECAY_BYTE; d += 4) {
                int fs = p << 7;
                int decay = d << 6;
                pairs++;

                int limit = representableLimit(buffer, fs, decay);
                smallestLimit = Math.min(smallestLimit, limit);
                largestLimit = Math.max(largestLimit, limit);

                int span = limit + 8;
                enc.init(buffer, 0, buffer.length);
                int[] coded = new int[2 * span + 1];
                for (int v = -span; v <= span; v++) {
                    int got = laplaceEncode(enc, v, fs, decay);
                    coded[v + span] = got;
                    int expected = Math.max(-limit, Math.min(limit, v));
                    if (got != expected) {
                        assertEquals(expected, got, "value " + v + " with fs=" + fs
                                + " decay=" + decay + " should code as " + expected
                                + ", the largest magnitude this model holds");
                    }
                }
                int bytes = enc.finish();

                dec.init(buffer, 0, bytes);
                for (int v = -span; v <= span; v++) {
                    int got = Laplace.decode(dec, fs, decay);
                    if (got != coded[v + span]) {
                        assertEquals(coded[v + span], got, "value " + v
                                + " with fs=" + fs + " decay=" + decay);
                    }
                    cases++;
                }
            }
        }

        assertEquals(43 * 44, pairs, "the sampled grid changed shape");
        assertEquals(133_112, cases, "the grid should have run every value of every pair");
        // The reference guarantees LAPLACE_NMIN magnitudes each side beyond the
        // decaying part, and every model in the table comfortably exceeds it.
        assertTrue(smallestLimit >= Laplace.MIN_REPRESENTABLE,
                "LAPLACE_NMIN promises at least " + Laplace.MIN_REPRESENTABLE
                + " magnitudes in each direction and the worst model held "
                + smallestLimit);
        assertEquals(17, smallestLimit, "the narrowest model in the swept grid");
        assertEquals(62, largestLimit, "the widest model in the swept grid");
    }

    /** The largest magnitude {@code ec_laplace_encode} codes without substituting. */
    private static int representableLimit(byte[] buffer, int fs, int decay) {
        RangeEncoder probe = new RangeEncoder(buffer);
        int limit = 0;
        // Bounded: no model can represent more than (32768-fl)/2 magnitudes and
        // fl is at least fs, so a runaway here would be a transcription bug
        // rather than a slow test.
        while (limit < 20000) {
            probe.init(buffer, 0, buffer.length);
            if (laplaceEncode(probe, limit + 1, fs, decay) != limit + 1) {
                break;
            }
            limit++;
        }
        assertTrue(limit < 20000, "no model should represent 20000 magnitudes");
        return limit;
    }

    @Test
    void theMagnitudeLimitIsTheSameEitherSideOfZero() {
        // (ndi_max-s)>>1 in ec_laplace_encode looks like it should make the
        // negative half one longer, because s is -1 there. It does not: the
        // tail alternates negative, positive, negative, and the odd unit at the
        // top belongs to whichever sign the arithmetic rounds towards. Asserting
        // the symmetry pins that down, because an implementation that got it
        // wrong would only differ on the single loudest step a band can take.
        byte[] buffer = new byte[64];
        RangeEncoder enc = new RangeEncoder(buffer);
        for (int p = MIN_PROB_BYTE; p <= MAX_PROB_BYTE; p += 7) {
            for (int d = MIN_DECAY_BYTE; d <= MAX_DECAY_BYTE; d += 7) {
                int fs = p << 7;
                int decay = d << 6;
                enc.init(buffer, 0, buffer.length);
                int positive = laplaceEncode(enc, 1_000_000, fs, decay);
                enc.init(buffer, 0, buffer.length);
                int negative = laplaceEncode(enc, -1_000_000, fs, decay);
                assertEquals(positive, -negative,
                        "fs=" + fs + " decay=" + decay + " clamps to +" + positive
                        + " but " + negative);
                assertNotEquals(1_000_000, positive,
                        "a million steps cannot fit a 15-bit context");
            }
        }
    }

    @Test
    void theExtremesOfTheParameterSpaceItselfRoundTrip() {
        // Not values e_prob_model produces, but the corners of what the format
        // can express, so that a caller which ever computes a parameter instead
        // of reading one gets a decoder that still behaves.
        int[] frequencies = {1, 2, 128, 8192, 24576, 32000, Laplace.MAX_ZERO_FREQ};
        int[] decays = {0, 1, 64, 4096, 11456, 16320, 16383, Laplace.MAX_DECAY};
        int[] values = {0, 1, -1, 2, -2, 7, -7, 40, -40, 500, -500, 20000, -20000};

        byte[] buffer = new byte[512];
        RangeEncoder enc = new RangeEncoder(buffer);
        RangeDecoder dec = new RangeDecoder(buffer);
        int cases = 0;
        for (int fs : frequencies) {
            for (int decay : decays) {
                enc.init(buffer, 0, buffer.length);
                int[] coded = new int[values.length];
                for (int i = 0; i < values.length; i++) {
                    coded[i] = laplaceEncode(enc, values[i], fs, decay);
                }
                int bytes = enc.finish();

                dec.init(buffer, 0, bytes);
                for (int i = 0; i < values.length; i++) {
                    assertEquals(coded[i], Laplace.decode(dec, fs, decay),
                            "value " + values[i] + " with fs=" + fs + " decay=" + decay);
                    cases++;
                }
                assertEquals(enc.finalRange(), dec.finalRange(),
                        "range disagreement at fs=" + fs + " decay=" + decay);
            }
        }
        assertEquals(frequencies.length * decays.length * values.length, cases);
    }

    @Test
    void aZeroProbabilityOfZeroStillDecodes() {
        // fs of 0 means the value zero has no symbol at all, so it cannot be
        // encoded; everything else still can. e_prob_model never produces it,
        // but the decoder must not divide by it or read a symbol of no width,
        // either of which stalls renormalisation rather than returning a wrong
        // answer -- a hang in an audio thread instead of a bad frame.
        byte[] buffer = new byte[256];
        RangeEncoder enc = new RangeEncoder(buffer);
        int[] values = {1, -1, 2, -2, 9, -9, 31, -31};
        int[] coded = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            coded[i] = laplaceEncode(enc, values[i], 0, 6000);
        }
        int bytes = enc.finish();

        RangeDecoder dec = new RangeDecoder(buffer, 0, bytes);
        for (int i = 0; i < values.length; i++) {
            assertEquals(coded[i], Laplace.decode(dec, 0, 6000), "value " + values[i]);
        }
    }

    @Test
    void theDistributionFillsTheContextExactlyAndNeverOverflowsIt() {
        // ec_laplace_decode ends on IMIN(fl+fs,32768), and that IMIN is the one
        // thing in the function no round trip can exercise, because a stream
        // that reached it could not have been written. So the state machine is
        // walked here directly, without a range coder: every (fl, fs) pair the
        // decoder can stop on, for every model the energy tables can produce.
        //
        // The invariant matters because it is the whole design. The geometric
        // part of the distribution sums to the total minus the 32 units
        // LAPLACE_NMIN reserves; the flat tail spends exactly those 32. If the
        // sum came out short, magnitudes the encoder can write would have no
        // symbol to be read back from; if it came out long, the last symbols
        // would overlap and two different energy steps would decode to the same
        // value.
        int worst = 0;
        int worstProb = 0;
        int worstDecay = 0;
        int states = 0;
        for (int p = MIN_PROB_BYTE; p <= MAX_PROB_BYTE; p++) {
            for (int d = MIN_DECAY_BYTE; d <= MAX_DECAY_BYTE; d++) {
                int zeroFreq = p << 7;
                int decay = d << 6;

                // Stopping on the zero symbol.
                int top = zeroFreq;
                states++;

                int fl = zeroFreq;
                int fs = freq1(zeroFreq, decay) + MINP;
                while (true) {
                    // The two arms of "if (fm < fl+fs)": negative keeps fl,
                    // positive advances it by fs.
                    top = Math.max(top, fl + fs);
                    top = Math.max(top, fl + fs + fs);
                    states += 2;
                    if (fs <= MINP) {
                        break;
                    }
                    int nextFl = fl + 2 * fs;
                    if (nextFl >= TOTAL) {
                        break;
                    }
                    fs = (((2 * fs) - 2 * MINP) * decay >> 15) + MINP;
                    fl = nextFl;
                }
                if (fs <= MINP) {
                    // The flat tail: di can walk fl as far as fm can reach.
                    int di = (TOTAL - 1 - fl) / 2;
                    top = Math.max(top, fl + 2 * di + fs);
                    top = Math.max(top, fl + 2 * di + fs + fs);
                    states += 2;
                }

                if (top > TOTAL) {
                    assertTrue(false, "fl+fs reached " + top + " for the model at"
                            + " probability byte " + p + " decay byte " + d
                            + ", which is past the 32768 the context holds");
                }
                if (top > worst) {
                    worst = top;
                    worstProb = p;
                    worstDecay = d;
                }
            }
        }
        assertEquals(TOTAL, worst,
                "the tightest model should fill the context to the last unit, and the"
                + " worst reached " + worst + " at probability byte " + worstProb
                + " decay byte " + worstDecay);
        assertTrue(states > 500_000, "walked only " + states + " stopping states");
    }

    // ----------------------------------------------------- shape of the model

    @Test
    void theCoderSpendsFewestBitsOnZeroAndMoreOnEveryStepAwayFromIt() {
        // The whole point of the model. If this failed while the round trip
        // passed, the coder would be perfectly self-consistent and would simply
        // spend more bits on the common case than on the rare one -- a bitrate
        // defect rather than a decode defect, which nothing else here reports.
        int fs = 96 << 7;
        int decay = 66 << 6;
        int previous = -1;
        for (int magnitude = 0; magnitude <= 7; magnitude++) {
            int cost = costInEighthBits(magnitude, fs, decay);
            assertEquals(cost, costInEighthBits(-magnitude, fs, decay),
                    "magnitude " + magnitude + " must cost the same either side of zero");
            assertTrue(cost > previous, "magnitude " + magnitude + " cost " + cost
                    + " eighth-bits, no more than magnitude " + (magnitude - 1)
                    + " at " + previous);
            previous = cost;
        }
        // Past the decaying part every magnitude is one unit of 32768 wide, so
        // the cost stops climbing. That flat tail is what LAPLACE_MINP buys.
        int tail = costInEighthBits(9, fs, decay);
        assertEquals(tail, costInEighthBits(10, fs, decay),
                "magnitudes in the flat tail all cost the same");
        assertEquals(tail, costInEighthBits(-10, fs, decay));
    }

    @Test
    void aSharperDecayCostsLessForSmallStepsAndMoreForLargeOnes() {
        int fs = 96 << 7;
        int sharp = 20 << 6;
        int flat = 170 << 6;
        assertTrue(costInEighthBits(1, fs, sharp) < costInEighthBits(1, fs, flat),
                "a sharply decaying model should code a one-step change more cheaply");
        assertTrue(costInEighthBits(9, fs, sharp) > costInEighthBits(9, fs, flat),
                "a sharply decaying model should code a nine-step change more dearly");
    }

    private static int costInEighthBits(int value, int fs, int decay) {
        RangeEncoder enc = new RangeEncoder(new byte[64]);
        int before = enc.tellFrac();
        laplaceEncode(enc, value, fs, decay);
        return enc.tellFrac() - before;
    }

    // --------------------------------------------------------- malformed input

    @Test
    void parametersOutsideWhatTheFormatCanExpressAreRefused() {
        RangeDecoder dec = new RangeDecoder(new byte[8]);
        assertThrows(RangeCoderException.class, () -> Laplace.decode(dec, -1, 0),
                "a negative probability of zero is not a probability");
        assertThrows(RangeCoderException.class,
                () -> Laplace.decode(dec, Laplace.MAX_ZERO_FREQ + 1, 0),
                "a probability of zero above 32736 leaves the reserved tail no room and"
                + " wraps ec_laplace_get_freq1's unsigned subtraction");
        assertThrows(RangeCoderException.class, () -> Laplace.decode(dec, 1000, -1),
                "a negative decay rate is not a decay rate");
        assertThrows(RangeCoderException.class,
                () -> Laplace.decode(dec, 1000, Laplace.MAX_DECAY + 1),
                "a decay above 16384 makes 16384-decay negative and the first symbol"
                + " wider than the whole context");
        assertThrows(NullPointerException.class, () -> Laplace.decode(null, 1000, 1000));
    }

    @Test
    void aFrameWithNothingInItDecodesRatherThanThrows() {
        // Reading past the end of a frame yields zeros, which RFC 6716 section
        // 4.1.2.1 requires rather than merely allows. The Laplace coder has to
        // survive that: a truncated packet must conceal, not take down the
        // thread rendering audio.
        RangeDecoder dec = new RangeDecoder(new byte[0]);
        for (int i = 0; i < 64; i++) {
            int value = Laplace.decode(dec, 96 << 7, 66 << 6);
            assertTrue(value > -40000 && value < 40000,
                    "a value decoded from nothing must still be bounded, and was " + value);
        }
    }

    @Test
    void aFrameOfOnesDecodesRatherThanThrows() {
        byte[] noise = new byte[64];
        java.util.Arrays.fill(noise, (byte) 0xFF);
        RangeDecoder dec = new RangeDecoder(noise);
        for (int i = 0; i < 200; i++) {
            int value = Laplace.decode(dec, (21 + i % 172) << 7, (6 + i % 174) << 6);
            assertTrue(value > -40000 && value < 40000,
                    "a value decoded from noise must still be bounded, and was " + value);
        }
    }

    // ---------------------------------------- against the RFC's own laplace.c

    @Test
    void theConstantsMatchTheOnesInAppendixA() {
        assumeTrue(RfcSource.path() != null, RfcSource.skipReason());
        String source = RfcSource.referenceSource("celt/laplace.c");

        assertEquals(0, define(source, "LAPLACE_LOG_MINP"),
                "LAPLACE_LOG_MINP in celt/laplace.c");
        assertEquals(16, define(source, "LAPLACE_NMIN"), "LAPLACE_NMIN in celt/laplace.c");
        assertEquals(Laplace.LOG_MIN_PROB, define(source, "LAPLACE_LOG_MINP"));
        assertEquals(Laplace.MIN_REPRESENTABLE, define(source, "LAPLACE_NMIN"));
        assertEquals(1 << define(source, "LAPLACE_LOG_MINP"), Laplace.MIN_PROB);
        assertEquals(32768 - Laplace.MIN_PROB * 2 * Laplace.MIN_REPRESENTABLE,
                Laplace.MAX_ZERO_FREQ,
                "MAX_ZERO_FREQ is exactly where ec_laplace_get_freq1's subtraction"
                + " reaches zero");
        assertTrue(source.contains("ft*(16384-decay)>>15"),
                "ec_laplace_get_freq1 should still be a Q14 multiply; if Appendix A says"
                + " otherwise then the transcription of freq1 is stale");
        assertTrue(source.contains("ec_decode_bin(dec, 15)"),
                "ec_laplace_decode should still read a 15-bit binary context");
    }

    @Test
    void theProbabilityBytesSweptHereAreTheOnesTheEnergyModelHolds() {
        assumeTrue(RfcSource.path() != null, RfcSource.skipReason());
        int[] table = RfcSource.referenceArray("celt/quant_bands.c", "e_prob_model");
        assertEquals(4 * 2 * 42, table.length,
                "e_prob_model is four frame sizes by two prediction types by 21 pairs");

        int minProb = Integer.MAX_VALUE;
        int maxProb = 0;
        int minDecay = Integer.MAX_VALUE;
        int maxDecay = 0;
        for (int i = 0; i < table.length; i += 2) {
            minProb = Math.min(minProb, table[i]);
            maxProb = Math.max(maxProb, table[i]);
            minDecay = Math.min(minDecay, table[i + 1]);
            maxDecay = Math.max(maxDecay, table[i + 1]);
        }
        assertEquals(MIN_PROB_BYTE, minProb, "the swept zero-probability span starts here");
        assertEquals(MAX_PROB_BYTE, maxProb, "the swept zero-probability span ends here");
        assertEquals(MIN_DECAY_BYTE, minDecay, "the swept decay span starts here");
        assertEquals(MAX_DECAY_BYTE, maxDecay, "the swept decay span ends here");

        // The parameters are these bytes shifted, and the shifts differ. That
        // asymmetry is easy to write as one shift and would leave every band
        // decoding from a distribution twice as sharp as the encoder's.
        assertTrue((maxProb << 7) <= Laplace.MAX_ZERO_FREQ,
                "the widest zero probability in the table must fit the coder");
        assertTrue((maxDecay << 6) <= Laplace.MAX_DECAY,
                "the flattest decay in the table must fit the coder");
    }

    private static int define(String source, String name) {
        Matcher m = Pattern.compile("#define\\s+" + name + "\\s+\\(?(\\d+)\\)?")
                .matcher(source);
        assertTrue(m.find(), name + " is not defined in the reference source");
        return Integer.parseInt(m.group(1));
    }
}
