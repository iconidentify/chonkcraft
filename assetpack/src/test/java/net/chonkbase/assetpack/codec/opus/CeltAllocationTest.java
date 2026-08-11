package net.chonkbase.assetpack.codec.opus;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The CELT bit allocator, checked against a second, independent transcription of
 * {@code compute_allocation} and {@code interp_bits2pulses} from RFC 6716
 * Appendix A's {@code celt/rate.c}.
 *
 * <p>This layer cannot be tested the way a parser can. It reads three symbols
 * from the bitstream at most and computes everything else, so a wrong answer
 * does not throw, does not run off the end of a buffer, and does not
 * desynchronise the range coder at the point the mistake is made. It hands the
 * shape decoder a different number of eighth-bits for one band, the shape
 * decoder reads a codeword of the wrong width, and every symbol after that comes
 * out of the wrong place. The frame still decodes. It just sounds wrong.
 *
 * <p>So the test is differential. {@link Rate} below is the reference C written
 * out again in Java, line for line, with its own copies of the four tables it
 * indexes -- {@code eband5ms}, {@code logN400}, {@code band_allocation} and
 * {@code LOG2_FRAC_TABLE} -- typed in from {@code celt/modes.c},
 * {@code celt/static_modes_float.h} and {@code celt/rate.c} rather than read
 * from the production tables. The two implementations are then run side by side
 * over every frame size, both channel counts, every trim value, every coded band
 * range Opus can signal, and budgets from a frame with no room for a single flag
 * up to a saturated 1275-byte packet, with the boosts, the ceilings and the
 * bitstream itself drawn from a fixed-seed generator.
 *
 * <p>Both implementations read their skip and stereo flags from their own
 * decoder over the same bytes, so a divergence in how many symbols each consumes
 * shows up as a divergence in the decoder's own bit counter, not just in the
 * numbers that come out.
 */
class CeltAllocationTest {

    /** Every {@code (start, end)} pair the Opus mode and bandwidth signalling can produce. */
    private static final int[][] BAND_RANGES = {
        {0, 13}, {0, 17}, {0, 19}, {0, 21}, {17, 19}, {17, 21}
    };

    /**
     * Budgets in eighth bits, from a frame too small to reserve the skip flag to
     * a saturated 1275-byte stereo packet.
     *
     * <p>The small values are not decoration. The reservations in
     * {@code compute_allocation} are all of the form "take this if there are at
     * least eight eighth-bits left", so 7, 8 and 9 are where a transcription
     * error in a comparison shows up and nowhere else.
     */
    private static final int[] BUDGETS = {
        0, 1, 7, 8, 9, 15, 16, 17, 23, 24, 32, 48, 64, 96, 160, 256, 400, 640,
        1024, 1600, 2560, 4096, 6500, 10_000, 16_000, 26_000, 41_000, 65_000, 81_600
    };

    @Test
    void matchesTheReferenceAllocationAcrossTheWholeParameterSpace() {
        Lcg rng = new Lcg(0x5CE17A11_0CA7E5L);
        CeltAllocation subject = new CeltAllocation();
        Rate reference = new Rate();
        int[] offsets = new int[CeltMode.BAND_COUNT];
        int[] caps = new int[CeltMode.BAND_COUNT];
        int[] realCaps = new int[CeltMode.BAND_COUNT];
        byte[] stream = new byte[64];
        RangeDecoder subjectDec = new RangeDecoder(stream);
        RangeDecoder referenceDec = new RangeDecoder(stream);

        int combinations = 0;
        for (int lm = 0; lm <= 3; lm++) {
            CeltMode mode = CeltMode.forLm(lm);
            for (int channels = 1; channels <= 2; channels++) {
                mode.computeCaps(channels, realCaps);
                for (int trim = 0; trim <= 10; trim++) {
                    for (int[] range : BAND_RANGES) {
                        for (int budget : BUDGETS) {
                            for (int draw = 0; draw < 3; draw++) {
                                drawInputs(rng, mode, channels, realCaps, offsets, caps, stream);
                                combinations += checkOneCase(subject, reference, subjectDec,
                                        referenceDec, mode, range[0], range[1], offsets, caps,
                                        trim, budget, channels, stream);
                            }
                        }
                    }
                }
            }
        }

        // A second pass with everything drawn at random, including the budget,
        // so the sweep is not confined to the round numbers chosen above.
        for (int i = 0; i < 120_000; i++) {
            int lm = rng.next(4);
            CeltMode mode = CeltMode.forLm(lm);
            int channels = 1 + rng.next(2);
            mode.computeCaps(channels, realCaps);
            int[] range = BAND_RANGES[rng.next(BAND_RANGES.length)];
            int trim = rng.next(11);
            int budget = rng.next(90_000) - 64;
            drawInputs(rng, mode, channels, realCaps, offsets, caps, stream);
            combinations += checkOneCase(subject, reference, subjectDec, referenceDec, mode,
                    range[0], range[1], offsets, caps, trim, budget, channels, stream);
        }

        assertTrue(combinations > 130_000,
                "the sweep should cover well over a hundred thousand cases, covered "
                + combinations);
        System.out.println("CeltAllocation: " + combinations
                + " parameter combinations agreed with the RFC 6716 Appendix A reference");
    }

    /**
     * The encoder's band-skipping choice is the one part of the allocation that
     * is not implied by the budget, so it has to survive a real bitstream.
     *
     * <p>{@code interp_bits2pulses} says as much in its own comment: "any bands
     * we choose to skip here must be explicitly signaled". If the encoder wrote
     * a flag the decoder does not read, or read one the encoder never wrote,
     * every symbol in the rest of the frame would be misaligned and the frame
     * would decode to noise.
     */
    @Test
    void whatTheEncoderSignalsIsWhatTheDecoderReads() {
        Lcg rng = new Lcg(0xB17A110CL);
        CeltAllocation subject = new CeltAllocation();
        int[] offsets = new int[CeltMode.BAND_COUNT];
        int[] caps = new int[CeltMode.BAND_COUNT];
        int[] realCaps = new int[CeltMode.BAND_COUNT];
        byte[] buffer = new byte[128];
        byte[] scratch = new byte[64];

        int roundTrips = 0;
        for (int i = 0; i < 40_000; i++) {
            CeltMode mode = CeltMode.forLm(rng.next(4));
            int channels = 1 + rng.next(2);
            mode.computeCaps(channels, realCaps);
            int[] range = BAND_RANGES[rng.next(BAND_RANGES.length)];
            int start = range[0];
            int end = range[1];
            int trim = rng.next(11);
            int budget = rng.next(90_000) - 64;
            drawInputs(rng, mode, channels, realCaps, offsets, caps, scratch);
            int intensity = start + rng.next(end - start + 1);
            int dualStereo = rng.next(2);
            int prev = rng.next(CeltMode.BAND_COUNT + 1);

            Arrays.fill(buffer, (byte) 0);
            RangeEncoder enc = new RangeEncoder(buffer);
            int before = enc.tellFrac();
            int encodedBands = subject.encode(mode, start, end, offsets, caps, trim, budget,
                    channels, intensity, dualStereo, enc, prev);
            int encoderSpent = enc.tellFrac() - before;
            CeltAllocation.Allocation encoded = subject.snapshot();
            enc.finish();

            // Over the whole buffer, not just the bytes finish() reports: the
            // rest is zero and a range decoder invents zeros past the end
            // anyway, so this avoids the empty-frame edge case without changing
            // a single symbol.
            RangeDecoder dec = new RangeDecoder(buffer, 0, buffer.length);
            int decoderStart = dec.tellFrac();
            int decodedBands = subject.decode(mode, start, end, offsets, caps, trim, budget,
                    channels, dec);
            int decoderSpent = dec.tellFrac() - decoderStart;
            CeltAllocation.Allocation decoded = subject.snapshot();

            String where = describe(mode, start, end, trim, budget, channels);
            assertEquals(encodedBands, decodedBands, where + ": coded band count");
            assertEquals(encoderSpent, decoderSpent,
                    where + ": the two sides used a different number of eighth bits");
            assertEquals(encoded, decoded, where + ": allocation");
            roundTrips++;
        }
        assertEquals(40_000, roundTrips);
    }

    @Test
    void aBoostedBandIsNeverSkipped() {
        // Skipping a band that dynalloc just boosted would spend a bit saying
        // the bits concentrated there should go somewhere else, which is both a
        // waste and a contradiction. interp_bits2pulses stops the skip walk at
        // the highest boosted band for exactly that reason.
        Lcg rng = new Lcg(0xB0057EDL);
        CeltAllocation subject = new CeltAllocation();
        int[] offsets = new int[CeltMode.BAND_COUNT];
        int[] caps = new int[CeltMode.BAND_COUNT];
        int[] realCaps = new int[CeltMode.BAND_COUNT];
        byte[] stream = new byte[64];

        int checked = 0;
        for (int i = 0; i < 20_000; i++) {
            CeltMode mode = CeltMode.forLm(rng.next(4));
            int channels = 1 + rng.next(2);
            mode.computeCaps(channels, realCaps);
            int[] range = BAND_RANGES[rng.next(BAND_RANGES.length)];
            drawInputs(rng, mode, channels, realCaps, offsets, caps, stream);
            int highestBoost = -1;
            for (int j = range[0]; j < range[1]; j++) {
                if (offsets[j] > 0) {
                    highestBoost = j;
                }
            }
            if (highestBoost < 0) {
                continue;
            }
            int codedBands = subject.decode(mode, range[0], range[1], offsets, caps,
                    rng.next(11), rng.next(60_000), channels, new RangeDecoder(stream));
            assertTrue(codedBands > highestBoost,
                    describe(mode, range[0], range[1], 0, 0, channels)
                    + ": band " + highestBoost + " was boosted but " + codedBands
                    + " bands were coded");
            checked++;
        }
        assertTrue(checked > 1000, "the draw should have produced boosts, produced " + checked);
    }

    @Test
    void skippedBandsKeepTheirEnergyAndLoseOnlyTheirShape() {
        // A skipped band is not silent: it still carries coarse energy, and it
        // may still carry one fine energy bit per channel. Zeroing its fine bits
        // as well would make the band jump in level from frame to frame, which
        // is a much more audible fault than the missing shape.
        Lcg rng = new Lcg(0x5217EDL);
        CeltAllocation subject = new CeltAllocation();
        int[] offsets = new int[CeltMode.BAND_COUNT];
        int[] caps = new int[CeltMode.BAND_COUNT];
        int[] realCaps = new int[CeltMode.BAND_COUNT];
        byte[] stream = new byte[64];

        for (int i = 0; i < 20_000; i++) {
            CeltMode mode = CeltMode.forLm(rng.next(4));
            int channels = 1 + rng.next(2);
            mode.computeCaps(channels, realCaps);
            int[] range = BAND_RANGES[rng.next(BAND_RANGES.length)];
            drawInputs(rng, mode, channels, realCaps, offsets, caps, stream);
            int codedBands = subject.decode(mode, range[0], range[1], offsets, caps,
                    rng.next(11), rng.next(60_000), channels, new RangeDecoder(stream));

            String where = describe(mode, range[0], range[1], 0, 0, channels);
            assertTrue(codedBands > range[0] && codedBands <= range[1],
                    where + ": coded band count " + codedBands + " is outside the coded range");
            for (int j = codedBands; j < range[1]; j++) {
                assertEquals(0, subject.pulses(j), where + ": skipped band " + j + " has shape");
                assertTrue(subject.fineBits(j) <= 1,
                        where + ": skipped band " + j + " has " + subject.fineBits(j)
                        + " fine bits, more than the one per channel it can afford");
            }
        }
    }

    @Test
    void everyBandGetsANonNegativeBudgetItCanActuallySpend() {
        Lcg rng = new Lcg(0xB0D9E7L);
        CeltAllocation subject = new CeltAllocation();
        int[] offsets = new int[CeltMode.BAND_COUNT];
        int[] caps = new int[CeltMode.BAND_COUNT];
        int[] realCaps = new int[CeltMode.BAND_COUNT];
        byte[] stream = new byte[64];

        for (int i = 0; i < 20_000; i++) {
            CeltMode mode = CeltMode.forLm(rng.next(4));
            int channels = 1 + rng.next(2);
            mode.computeCaps(channels, realCaps);
            int[] range = BAND_RANGES[rng.next(BAND_RANGES.length)];
            drawInputs(rng, mode, channels, realCaps, offsets, caps, stream);
            subject.decode(mode, range[0], range[1], offsets, caps, rng.next(11),
                    rng.next(90_000) - 64, channels, new RangeDecoder(stream));

            String where = describe(mode, range[0], range[1], 0, 0, channels);
            assertTrue(subject.balance() >= 0, where + ": negative balance " + subject.balance());
            for (int j = range[0]; j < range[1]; j++) {
                // A negative shape budget would be handed to the pulse layer as
                // a negative pulse count, and V(N,K) is not defined there.
                assertTrue(subject.pulses(j) >= 0,
                        where + ": band " + j + " has " + subject.pulses(j) + " eighth bits");
                assertTrue(subject.fineBits(j) >= 0 && subject.fineBits(j) <= 8,
                        where + ": band " + j + " has " + subject.fineBits(j)
                        + " fine bits, outside 0..8");
                assertTrue(subject.finePriority(j) == 0 || subject.finePriority(j) == 1,
                        where + ": band " + j + " has fine priority " + subject.finePriority(j));
            }
        }
    }

    @Test
    void bandsOutsideTheCodedRangeReadAsZeroRatherThanAsTheLastFrame() {
        // The reference leaves these entries uninitialised and its callers never
        // look. Here they are zeroed, so that a caller that walks one band too
        // far gets silence rather than a band budget from a frame that is
        // already played.
        CeltMode mode = CeltMode.forLm(3);
        int[] caps = new int[CeltMode.BAND_COUNT];
        mode.computeCaps(2, caps);
        int[] offsets = new int[CeltMode.BAND_COUNT];
        CeltAllocation subject = new CeltAllocation();

        subject.decode(mode, 0, 21, offsets, caps, 5, 40_000, 2, new RangeDecoder(noSkipStream()));
        assertTrue(subject.pulses(20) > 0, "the first frame should have filled every band");

        subject.decode(mode, 17, 19, offsets, caps, 5, 400, 2, new RangeDecoder(noSkipStream()));

        for (int band = 0; band < 17; band++) {
            assertEquals(0, subject.pulses(band), "band " + band + " is below the coded range");
            assertEquals(0, subject.fineBits(band), "band " + band + " is below the coded range");
            assertEquals(0, subject.finePriority(band), "band " + band + " is below the range");
        }
        for (int band = 19; band < 21; band++) {
            assertEquals(0, subject.pulses(band), "band " + band + " is above the coded range");
            assertEquals(0, subject.fineBits(band), "band " + band + " is above the coded range");
        }
    }

    @Test
    void raisingTheTrimMovesBitsDownTheSpectrumNotUpIt() {
        // RFC 6716 section 4.3.3 says in passing that "Values lower than 5 bias
        // the allocation towards lower frequencies and values above 5 bias it
        // towards higher frequencies". That sentence is backwards, and the same
        // section's step-by-step statement of trim_offsets[] a page later is
        // what is right: the multiplier is (alloc_trim - 5 - LM) times "the
        // number of remaining bands", so the tilt is largest at the bottom of
        // the spectrum and zero at the top, and raising the trim feeds the low
        // bands out of the high ones. Appendix A's celt/rate.c is the same
        // expression.
        //
        // alloc_trim_analysis in celt/celt.c settles which one to believe: it
        // lowers the trim when it measures energy at the top of the spectrum,
        // so a low trim is what buys treble. This test asserts the direction
        // Appendix A actually implements, because a decoder that followed the
        // summary sentence would still decode every frame -- it would just put
        // the bits at the wrong end of the spectrum.
        byte[] noSkip = noSkipStream();
        for (int lm = 0; lm <= 3; lm++) {
            CeltMode mode = CeltMode.forLm(lm);
            for (int channels = 1; channels <= 2; channels++) {
                int[] caps = new int[CeltMode.BAND_COUNT];
                mode.computeCaps(channels, caps);
                int[] offsets = new int[CeltMode.BAND_COUNT];
                int budget = 1500 * (1 << lm) * channels;
                CeltAllocation subject = new CeltAllocation();

                // The endpoints, not every step. The interpolation is discrete
                // -- eleven table rows and sixty-four points between two of
                // them -- so one step of trim can wobble a single band by a few
                // eighth bits in either direction. The tilt across the whole
                // range is what the parameter is for.
                int[] bottom = new int[11];
                int[] top = new int[11];
                int[] lowHalf = new int[11];
                int[] highHalf = new int[11];
                for (int trim = 0; trim <= 10; trim++) {
                    subject.decode(mode, 0, 21, offsets, caps, trim, budget, channels,
                            new RangeDecoder(noSkip));
                    bottom[trim] = subject.pulses(0);
                    top[trim] = subject.pulses(20);
                    for (int band = 0; band < 21; band++) {
                        if (band < 11) {
                            lowHalf[trim] += subject.pulses(band);
                        } else {
                            highHalf[trim] += subject.pulses(band);
                        }
                    }
                }

                String where = "LM " + lm + " C" + channels;
                assertTrue(top[10] < top[0],
                        where + ": trim 10 should leave the top band worse off than trim 0, got "
                        + top[10] + " and " + top[0]);
                assertTrue(highHalf[10] < highHalf[0],
                        where + ": trim 10 should leave the top half of the spectrum worse off"
                        + " than trim 0, got " + highHalf[10] + " and " + highHalf[0]);
                assertTrue(lowHalf[10] > lowHalf[0],
                        where + ": trim 10 should leave the bottom half of the spectrum better"
                        + " off than trim 0, got " + lowHalf[10] + " and " + lowHalf[0]);
                // At the 2.5 ms frame size the bottom band is one bin wide, so
                // it is pinned at the one-sign-bit floor and cannot move at all;
                // everywhere else it must actually gain.
                if (lm > 0) {
                    assertTrue(bottom[10] > bottom[0],
                            where + ": trim 10 should leave the bottom band better off than trim 0,"
                            + " got " + bottom[10] + " and " + bottom[0]);
                }
            }
        }
    }

    @Test
    void anEmptyBudgetStillProducesOneCodedBand() {
        // compute_allocation asserts codedBands > start. If it could return
        // start, everything downstream would divide by the width of an empty
        // band range.
        CeltMode mode = CeltMode.forLm(0);
        int[] caps = new int[CeltMode.BAND_COUNT];
        mode.computeCaps(2, caps);
        int[] offsets = new int[CeltMode.BAND_COUNT];
        CeltAllocation subject = new CeltAllocation();

        for (int total = -64; total <= 64; total++) {
            int codedBands = subject.decode(mode, 0, 21, offsets, caps, 5, total, 2,
                    new RangeDecoder(new byte[64]));
            assertTrue(codedBands > 0, total + " eighth bits gave " + codedBands + " coded bands");
        }
    }

    @Test
    void rejectsInputsItCannotAllocateFor() {
        CeltMode mode = CeltMode.forLm(3);
        int[] caps = new int[CeltMode.BAND_COUNT];
        mode.computeCaps(2, caps);
        int[] offsets = new int[CeltMode.BAND_COUNT];
        CeltAllocation subject = new CeltAllocation();
        RangeDecoder dec = new RangeDecoder(new byte[64]);

        assertMessage("an empty band range",
                () -> subject.decode(mode, 5, 5, offsets, caps, 5, 1000, 2, dec));
        assertMessage("a band range past the top of the layer",
                () -> subject.decode(mode, 0, 22, offsets, caps, 5, 1000, 2, dec));
        assertMessage("a negative start band",
                () -> subject.decode(mode, -1, 21, offsets, caps, 5, 1000, 2, dec));
        assertMessage("three channels",
                () -> subject.decode(mode, 0, 21, offsets, caps, 5, 1000, 3, dec));
        assertMessage("a trim above 10",
                () -> subject.decode(mode, 0, 21, offsets, caps, 11, 1000, 2, dec));
        assertMessage("a short caps array",
                () -> subject.decode(mode, 0, 21, offsets, new int[3], 5, 1000, 2, dec));
        assertMessage("a negative boost",
                () -> subject.decode(mode, 0, 21, negativeAt(4), caps, 5, 1000, 2, dec));
        assertMessage("a negative ceiling",
                () -> subject.decode(mode, 0, 21, offsets, negativeAt(4), 5, 1000, 2, dec));

        RangeEncoder enc = new RangeEncoder(new byte[64]);
        assertMessage("an intensity band below the coded range",
                () -> subject.encode(mode, 5, 21, offsets, caps, 5, 1000, 2, 4, 0, enc, 0));
        assertMessage("a dual stereo flag that is not a flag",
                () -> subject.encode(mode, 0, 21, offsets, caps, 5, 1000, 2, 0, 2, enc, 0));

        assertThrows(NullPointerException.class,
                () -> subject.decode(mode, 0, 21, offsets, caps, 5, 1000, 2, null));
        assertThrows(IndexOutOfBoundsException.class, () -> subject.pulses(21));
        assertThrows(IndexOutOfBoundsException.class, () -> subject.fineBits(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> subject.finePriority(21));
    }

    @Test
    void aSnapshotDoesNotChangeWhenTheNextFrameIsAllocated() {
        CeltMode mode = CeltMode.forLm(3);
        int[] caps = new int[CeltMode.BAND_COUNT];
        mode.computeCaps(2, caps);
        int[] offsets = new int[CeltMode.BAND_COUNT];
        CeltAllocation subject = new CeltAllocation();

        subject.decode(mode, 0, 21, offsets, caps, 5, 40_000, 2, new RangeDecoder(new byte[64]));
        CeltAllocation.Allocation first = subject.snapshot();
        int[] pulsesThen = first.pulses().clone();

        subject.decode(mode, 0, 21, offsets, caps, 5, 900, 2, new RangeDecoder(new byte[64]));
        CeltAllocation.Allocation second = subject.snapshot();

        assertArrayEquals(pulsesThen, first.pulses(),
                "the snapshot must not follow the workspace into the next frame");
        assertTrue(!first.equals(second), "a starved frame should not allocate like a full one");
        assertEquals(second, subject.snapshot(), "the workspace should still hold the second frame");
    }

    @Test
    void theRecordComparesItsArraysElementWise() {
        // A record's generated equals compares arrays by identity, so two
        // identical allocations would test unequal and every differential
        // assertion written against it would pass without checking anything.
        int[] a = {1, 2, 3};
        int[] b = {1, 2, 3};
        CeltAllocation.Allocation one = new CeltAllocation.Allocation(a, a, a, 4, 5, 6, 1);
        CeltAllocation.Allocation two = new CeltAllocation.Allocation(b, b, b, 4, 5, 6, 1);

        assertEquals(one, two);
        assertEquals(one.hashCode(), two.hashCode());
        assertTrue(!one.equals(new CeltAllocation.Allocation(a, a, a, 4, 5, 6, 0)));

        a[0] = 99;
        assertEquals(1, one.pulses()[0], "the record must have copied the array it was given");
    }

    private static int checkOneCase(CeltAllocation subject, Rate reference,
            RangeDecoder subjectDec, RangeDecoder referenceDec, CeltMode mode,
            int start, int end, int[] offsets, int[] caps, int trim, int budget,
            int channels, byte[] stream) {
        subjectDec.init(stream, 0, stream.length);
        referenceDec.init(stream, 0, stream.length);

        int subjectBands = subject.decode(mode, start, end, offsets, caps, trim, budget,
                channels, subjectDec);
        int referenceBands = reference.computeAllocation(start, end, offsets, caps, trim,
                budget, channels, mode.lm(), referenceDec, null, 0);

        String where = describe(mode, start, end, trim, budget, channels);
        assertEquals(referenceBands, subjectBands, where + ": coded band count");
        assertEquals(reference.outBalance, subject.balance(), where + ": balance");
        assertEquals(reference.outIntensity, subject.intensity(), where + ": intensity band");
        assertEquals(reference.outDualStereo, subject.dualStereo(), where + ": dual stereo");
        for (int j = 0; j < CeltMode.BAND_COUNT; j++) {
            assertEquals(reference.pulses[j], subject.pulses(j), where + ": band " + j + " shape");
            assertEquals(reference.ebits[j], subject.fineBits(j),
                    where + ": band " + j + " fine bits");
            assertEquals(reference.finePriority[j], subject.finePriority(j),
                    where + ": band " + j + " fine priority");
        }
        // If the two disagreed about how many skip flags to read, they would be
        // at different points in the stream even when the numbers matched.
        assertEquals(referenceDec.tellFrac(), subjectDec.tellFrac(),
                where + ": the two sides read a different number of symbols");
        assertEquals(referenceDec.finalRange(), subjectDec.finalRange(),
                where + ": the two range decoders ended in different states");
        return 1;
    }

    /**
     * Draws one set of boosts, ceilings and bitstream bytes.
     *
     * <p>The ceilings are the real ones most of the time and a perturbation of
     * them the rest, because the interpolation's high endpoint is the ceiling
     * itself whenever the budget runs past the top table row, and a ceiling that
     * only ever took its natural value would never exercise that endpoint hard.
     */
    private static void drawInputs(Lcg rng, CeltMode mode, int channels, int[] realCaps,
            int[] offsets, int[] caps, byte[] stream) {
        int shape = rng.next(4);
        for (int j = 0; j < CeltMode.BAND_COUNT; j++) {
            // Boosts are quantised the way the band-boost decoder produces them:
            // a multiple of min(8*width, max(48, width)) eighth bits.
            int width = channels * mode.bandWidth(j);
            int quanta = Math.min(width << 3, Math.max(48, width));
            offsets[j] = switch (shape) {
                case 0 -> 0;
                case 1 -> rng.next(8) == 0 ? quanta : 0;
                case 2 -> rng.next(3) == 0 ? quanta * (1 + rng.next(3)) : 0;
                default -> rng.next(2) == 0 ? quanta * (1 + rng.next(8)) : 0;
            };
            caps[j] = switch (rng.next(4)) {
                case 0 -> Math.max(0, realCaps[j] / (1 + rng.next(8)));
                case 1 -> realCaps[j] + rng.next(4096);
                case 2 -> rng.next(2048);
                default -> realCaps[j];
            };
        }
        for (int i = 0; i < stream.length; i++) {
            stream[i] = (byte) rng.next(256);
        }
    }

    /**
     * A bitstream whose first skip flag reads as one, so no band is skipped.
     *
     * <p>All-zero bytes are the opposite: {@code ec_dec_bit_logp(dec, 1)} then
     * answers zero every time, the skip walk runs all the way to the bottom, and
     * a frame comes out with one coded band whatever the budget was. Any test
     * about how bits are spread between bands needs bands to spread them over.
     */
    private static byte[] noSkipStream() {
        byte[] stream = new byte[64];
        Arrays.fill(stream, (byte) 0xFF);
        return stream;
    }

    private static int[] negativeAt(int band) {
        int[] values = new int[CeltMode.BAND_COUNT];
        Arrays.fill(values, 100);
        values[band] = -1;
        return values;
    }

    private static void assertMessage(String what, Runnable call) {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, call::run,
                what + " should be rejected");
        assertNotNull(thrown.getMessage(), what + " should be rejected with a message");
        assertTrue(!thrown.getMessage().isBlank(), what + " should be rejected with a message");
    }

    private static String describe(CeltMode mode, int start, int end, int trim, int budget,
            int channels) {
        return "LM " + mode.lm() + " bands " + start + ".." + end + " trim " + trim
                + " total " + budget + " C" + channels;
    }

    /**
     * A 64-bit linear congruential generator, so that the sweep is the same
     * sequence on every JVM and a failure can be reproduced from the seed alone.
     *
     * <p>Knuth's MMIX multiplier and increment. The modulo is slightly biased for
     * bounds that are not powers of two, which does not matter for choosing test
     * inputs and keeps the generator to three lines.
     */
    private static final class Lcg {

        private long state;

        Lcg(long seed) {
            this.state = seed;
        }

        int next(int bound) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            return (int) ((state >>> 33) % bound);
        }
    }

    /**
     * {@code compute_allocation} and {@code interp_bits2pulses} from RFC 6716
     * Appendix A's {@code celt/rate.c}, transcribed statement by statement.
     *
     * <p>Deliberately not refactored. Every shift, every truncating division and
     * every comparison is in the order the C has it, and the tables are indexed
     * the way the C indexes them, flat and row-major, because the point of this
     * class is to be a second opinion and a tidier version would be a second
     * opinion about a tidier algorithm.
     *
     * <p>The one structural change is that {@code ec_ctx} is split into a
     * decoder and an encoder, of which exactly one is non-null, since Java has no
     * single type that is both.
     */
    private static final class Rate {

        /** {@code eband5ms} in {@code celt/modes.c}: band edges in 2.5 ms bins. */
        private static final int[] EBAND5MS = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16, 20, 24, 28, 34, 40, 48, 60, 78, 100
        };

        /** {@code logN400} in {@code celt/static_modes_float.h}. */
        private static final int[] LOGN400 = {
            0, 0, 0, 0, 0, 0, 0, 0, 8, 8, 8, 8, 16, 16, 16, 21, 21, 24, 29, 34, 36
        };

        /** {@code LOG2_FRAC_TABLE} in {@code celt/rate.c}. */
        private static final int[] LOG2_FRAC_TABLE = {
            0,
            8, 13,
            16, 19, 21, 23,
            24, 26, 27, 28, 29, 30, 31, 32,
            32, 33, 34, 34, 35, 36, 36, 37, 37
        };

        /** {@code band_allocation} in {@code celt/modes.c}, flat and row-major as the C has it. */
        private static final int[] BAND_ALLOCATION = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            90, 80, 75, 69, 63, 56, 49, 40, 34, 29, 20, 18, 10, 0, 0, 0, 0, 0, 0, 0, 0,
            110, 100, 90, 84, 78, 71, 65, 58, 51, 45, 39, 32, 26, 20, 12, 0, 0, 0, 0, 0, 0,
            118, 110, 103, 93, 86, 80, 75, 70, 65, 59, 53, 47, 40, 31, 23, 15, 4, 0, 0, 0, 0,
            126, 119, 112, 104, 95, 89, 83, 78, 72, 66, 60, 54, 47, 39, 32, 25, 17, 12, 1, 0, 0,
            134, 127, 120, 114, 103, 97, 91, 85, 78, 72, 66, 60, 54, 47, 41, 35, 29, 23, 16, 10, 1,
            144, 137, 130, 124, 113, 107, 101, 95, 88, 82, 76, 70, 64, 57, 51, 45, 39, 33, 26, 15, 1,
            152, 145, 138, 132, 123, 117, 111, 105, 98, 92, 86, 80, 74, 67, 61, 55, 49, 43, 36, 20, 1,
            162, 155, 148, 142, 133, 127, 121, 115, 108, 102, 96, 90, 84, 77, 71, 65, 59, 53, 46, 30, 1,
            172, 165, 158, 152, 143, 137, 131, 125, 118, 112, 106, 100, 94, 87, 81, 75, 69, 63, 56, 45, 20,
            200, 200, 200, 200, 200, 200, 200, 200, 198, 193, 188, 183, 178, 173, 168, 163, 158, 153,
            148, 129, 104
        };

        private static final int NB_EBANDS = 21;
        private static final int NB_ALLOC_VECTORS = 11;
        private static final int BITRES = 3;
        private static final int ALLOC_STEPS = 6;
        private static final int MAX_FINE_BITS = 8;
        private static final int FINE_OFFSET = 21;

        private final int[] pulses = new int[NB_EBANDS];
        private final int[] ebits = new int[NB_EBANDS];
        private final int[] finePriority = new int[NB_EBANDS];
        private int outBalance;
        private int outIntensity;
        private int outDualStereo;

        int computeAllocation(int start, int end, int[] offsets, int[] cap, int allocTrim,
                int total, int c, int lm, RangeDecoder dec, RangeEncoder enc, int prev) {
            Arrays.fill(pulses, 0);
            Arrays.fill(ebits, 0);
            Arrays.fill(finePriority, 0);

            int len = NB_EBANDS;
            int skipStart = start;
            int[] bits1 = new int[len];
            int[] bits2 = new int[len];
            int[] thresh = new int[len];
            int[] trimOffset = new int[len];

            total = Math.max(total, 0);
            int skipRsv = total >= 1 << BITRES ? 1 << BITRES : 0;
            total -= skipRsv;
            int intensityRsv = 0;
            int dualStereoRsv = 0;
            if (c == 2) {
                intensityRsv = LOG2_FRAC_TABLE[end - start];
                if (intensityRsv > total) {
                    intensityRsv = 0;
                } else {
                    total -= intensityRsv;
                    dualStereoRsv = total >= 1 << BITRES ? 1 << BITRES : 0;
                    total -= dualStereoRsv;
                }
            }

            for (int j = start; j < end; j++) {
                thresh[j] = Math.max(c << BITRES,
                        (3 * (EBAND5MS[j + 1] - EBAND5MS[j]) << lm << BITRES) >> 4);
                trimOffset[j] = c * (EBAND5MS[j + 1] - EBAND5MS[j]) * (allocTrim - 5 - lm)
                        * (end - j - 1) * (1 << (lm + BITRES)) >> 6;
                if ((EBAND5MS[j + 1] - EBAND5MS[j]) << lm == 1) {
                    trimOffset[j] -= c << BITRES;
                }
            }

            int lo = 1;
            int hi = NB_ALLOC_VECTORS - 1;
            do {
                boolean done = false;
                int psum = 0;
                int mid = (lo + hi) >> 1;
                for (int j = end; j-- > start;) {
                    int n = EBAND5MS[j + 1] - EBAND5MS[j];
                    int bitsj = c * n * BAND_ALLOCATION[mid * len + j] << lm >> 2;
                    if (bitsj > 0) {
                        bitsj = Math.max(0, bitsj + trimOffset[j]);
                    }
                    bitsj += offsets[j];
                    if (bitsj >= thresh[j] || done) {
                        done = true;
                        psum += Math.min(bitsj, cap[j]);
                    } else {
                        if (bitsj >= c << BITRES) {
                            psum += c << BITRES;
                        }
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
                int n = EBAND5MS[j + 1] - EBAND5MS[j];
                int bits1j = c * n * BAND_ALLOCATION[lo * len + j] << lm >> 2;
                int bits2j = hi >= NB_ALLOC_VECTORS
                        ? cap[j] : c * n * BAND_ALLOCATION[hi * len + j] << lm >> 2;
                if (bits1j > 0) {
                    bits1j = Math.max(0, bits1j + trimOffset[j]);
                }
                if (bits2j > 0) {
                    bits2j = Math.max(0, bits2j + trimOffset[j]);
                }
                if (lo > 0) {
                    bits1j += offsets[j];
                }
                bits2j += offsets[j];
                if (offsets[j] > 0) {
                    skipStart = j;
                }
                bits2j = Math.max(0, bits2j - bits1j);
                bits1[j] = bits1j;
                bits2[j] = bits2j;
            }

            return interpBits2Pulses(start, end, skipStart, bits1, bits2, thresh, cap, total,
                    skipRsv, intensityRsv, dualStereoRsv, pulses, ebits, finePriority,
                    c, lm, dec, enc, prev);
        }

        private int interpBits2Pulses(int start, int end, int skipStart, int[] bits1, int[] bits2,
                int[] thresh, int[] cap, int total, int skipRsv, int intensityRsv,
                int dualStereoRsv, int[] bits, int[] ebitsOut, int[] finePriorityOut,
                int c, int lm, RangeDecoder dec, RangeEncoder enc, int prev) {
            int allocFloor = c << BITRES;
            int stereo = c > 1 ? 1 : 0;
            int logM = lm << BITRES;

            int lo = 0;
            int hi = 1 << ALLOC_STEPS;
            for (int i = 0; i < ALLOC_STEPS; i++) {
                int mid = (lo + hi) >> 1;
                int psum = 0;
                boolean done = false;
                for (int j = end; j-- > start;) {
                    int tmp = bits1[j] + (mid * bits2[j] >> ALLOC_STEPS);
                    if (tmp >= thresh[j] || done) {
                        done = true;
                        psum += Math.min(tmp, cap[j]);
                    } else {
                        if (tmp >= allocFloor) {
                            psum += allocFloor;
                        }
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
                    if (tmp >= allocFloor) {
                        tmp = allocFloor;
                    } else {
                        tmp = 0;
                    }
                } else {
                    done = true;
                }
                tmp = Math.min(tmp, cap[j]);
                bits[j] = tmp;
                psum += tmp;
            }

            int codedBands;
            for (codedBands = end;; codedBands--) {
                int j = codedBands - 1;
                if (j <= skipStart) {
                    total += skipRsv;
                    break;
                }
                int left = total - psum;
                int percoeff = left / (EBAND5MS[codedBands] - EBAND5MS[start]);
                left -= (EBAND5MS[codedBands] - EBAND5MS[start]) * percoeff;
                int rem = Math.max(left - (EBAND5MS[j] - EBAND5MS[start]), 0);
                int bandWidth = EBAND5MS[codedBands] - EBAND5MS[j];
                int bandBits = bits[j] + percoeff * bandWidth + rem;
                if (bandBits >= Math.max(thresh[j], allocFloor + (1 << BITRES))) {
                    if (enc != null) {
                        if (bandBits > ((j < prev ? 7 : 9) * bandWidth << lm << BITRES) >> 4) {
                            enc.encodeBit(1, 1);
                            break;
                        }
                        enc.encodeBit(0, 1);
                    } else if (dec.decodeBit(1) != 0) {
                        break;
                    }
                    psum += 1 << BITRES;
                    bandBits -= 1 << BITRES;
                }
                psum -= bits[j] + intensityRsv;
                if (intensityRsv > 0) {
                    intensityRsv = LOG2_FRAC_TABLE[j - start];
                }
                psum += intensityRsv;
                if (bandBits >= allocFloor) {
                    psum += allocFloor;
                    bits[j] = allocFloor;
                } else {
                    bits[j] = 0;
                }
            }

            if (intensityRsv > 0) {
                if (enc != null) {
                    outIntensity = Math.min(outIntensity, codedBands);
                    enc.encodeUniform(outIntensity - start, codedBands + 1 - start);
                } else {
                    outIntensity = start + dec.decodeUniform(codedBands + 1 - start);
                }
            } else {
                outIntensity = 0;
            }
            if (outIntensity <= start) {
                total += dualStereoRsv;
                dualStereoRsv = 0;
            }
            if (dualStereoRsv > 0) {
                if (enc != null) {
                    enc.encodeBit(outDualStereo, 1);
                } else {
                    outDualStereo = dec.decodeBit(1);
                }
            } else {
                outDualStereo = 0;
            }

            int left = total - psum;
            int percoeff = left / (EBAND5MS[codedBands] - EBAND5MS[start]);
            left -= (EBAND5MS[codedBands] - EBAND5MS[start]) * percoeff;
            for (int j = start; j < codedBands; j++) {
                bits[j] += percoeff * (EBAND5MS[j + 1] - EBAND5MS[j]);
            }
            for (int j = start; j < codedBands; j++) {
                int tmp = Math.min(left, EBAND5MS[j + 1] - EBAND5MS[j]);
                bits[j] += tmp;
                left -= tmp;
            }

            int balance = 0;
            int j = start;
            for (; j < codedBands; j++) {
                int n0 = EBAND5MS[j + 1] - EBAND5MS[j];
                int n = n0 << lm;
                int excess;
                bits[j] += balance;

                if (n > 1) {
                    excess = Math.max(bits[j] - cap[j], 0);
                    bits[j] -= excess;

                    int den = c * n
                            + (c == 2 && n > 2 && outDualStereo == 0 && j < outIntensity ? 1 : 0);
                    int nclogn = den * (LOGN400[j] + logM);
                    int offset = (nclogn >> 1) - den * FINE_OFFSET;
                    if (n == 2) {
                        offset += den << BITRES >> 2;
                    }
                    if (bits[j] + offset < den * 2 << BITRES) {
                        offset += nclogn >> 2;
                    } else if (bits[j] + offset < den * 3 << BITRES) {
                        offset += nclogn >> 3;
                    }
                    ebitsOut[j] = Math.max(0,
                            (bits[j] + offset + (den << (BITRES - 1))) / (den << BITRES));
                    if (c * ebitsOut[j] > (bits[j] >> BITRES)) {
                        ebitsOut[j] = bits[j] >> stereo >> BITRES;
                    }
                    ebitsOut[j] = Math.min(ebitsOut[j], MAX_FINE_BITS);
                    finePriorityOut[j] =
                            ebitsOut[j] * (den << BITRES) >= bits[j] + offset ? 1 : 0;
                    bits[j] -= c * ebitsOut[j] << BITRES;
                } else {
                    excess = Math.max(0, bits[j] - (c << BITRES));
                    bits[j] -= excess;
                    ebitsOut[j] = 0;
                    finePriorityOut[j] = 1;
                }

                if (excess > 0) {
                    int extraFine = Math.min(excess >> (stereo + BITRES),
                            MAX_FINE_BITS - ebitsOut[j]);
                    ebitsOut[j] += extraFine;
                    int extraBits = extraFine * c << BITRES;
                    finePriorityOut[j] = extraBits >= excess - balance ? 1 : 0;
                    excess -= extraBits;
                }
                balance = excess;
            }
            outBalance = balance;

            for (; j < end; j++) {
                ebitsOut[j] = bits[j] >> stereo >> BITRES;
                bits[j] = 0;
                finePriorityOut[j] = ebitsOut[j] < 1 ? 1 : 0;
            }
            return codedBands;
        }
    }
}
