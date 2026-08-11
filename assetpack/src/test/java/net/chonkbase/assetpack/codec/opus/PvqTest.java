package net.chonkbase.assetpack.codec.opus;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The pyramid vector quantiser, over every band shape CELT can reach.
 *
 * <p>Nothing here checks that a number was computed. A wrong V(N,K), a wrong
 * codeword order, or an index allowed to wrap all produce a vector that looks
 * completely legitimate -- N integers, exactly K pulses, unit norm after
 * scaling -- and is simply the wrong one. Downstream there is nothing to catch
 * it: the inverse MDCT will happily transform it, and what a listener gets is
 * a band of noise where a tone should be, plus every band after it in the
 * frame decoded from a range coder that no longer agrees with the encoder's.
 * So each check below either compares against a count derived a different way,
 * or drives a whole codebook and asserts a property of the result.
 *
 * <p>Three of the oracles are deliberately independent of the implementation.
 * {@code combinatorialCount} counts the vectors by choosing which coefficients
 * are non-zero rather than by the recurrence the table is built from.
 * {@code enumerateInOrder} states the codeword order as a recursion over the
 * first coefficient rather than as the subtract-and-walk the decoder performs.
 * {@code everyVectorWith} enumerates the codebook by brute force. An
 * implementation that agrees with all three is right for reasons, not by
 * coincidence.
 */
class PvqTest {

    /**
     * RFC 6716 Table 55, the 2.5 ms column: MDCT bins per channel per band.
     *
     * <p>Twenty-one bands. A 20 ms frame has eight times these, which is where
     * the 176 of band 20 comes from.
     */
    private static final int[] BAND_WIDTHS_2_5MS = {
        1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 6, 6, 8, 12, 18, 22
    };

    // ------------------------------------------------------------- round trips

    @Test
    void everyShapeTheModeTablesReachSurvivesARoundTrip() {
        int[] dimensions = reachableDimensions();

        // Count before checking. A sweep that discovered nothing would pass
        // every assertion inside the loop.
        assertEquals(23, dimensions.length,
                "the mode tables and the split rule should reach 23 band widths, found "
                + dimensions.length);
        assertEquals(176, dimensions[dimensions.length - 1],
                "band 20 at 20 ms is 176 bins and must be in the covered set");
        assertEquals(11, dimensions[7],
                "a 176-bin band split four times is 11 coefficients, and 11 is odd:"
                + " a decoder that assumed every band width was even would drop a coefficient");

        Sweep sweep = sweep(dimensions);
        assertEquals(822, sweep.pairs,
                "the band widths CELT reaches, with every pulse count that fits one"
                + " codebook, should be 822 shapes, swept " + sweep.pairs);
        assertTrue(sweep.roundTrips > 15_000,
                "only " + sweep.roundTrips + " round trips over the reachable shapes");
        System.out.printf("PVQ reachable shapes: %d (N,K) pairs, %,d round trips%n",
                sweep.pairs, sweep.roundTrips);
    }

    @Test
    void theWholeDimensionAndPulsePlaneSurvivesARoundTrip() {
        // Every N from one coefficient to the widest band, not only the ones
        // the shipped mode tables happen to produce, because Opus Custom
        // (RFC 6716 section 6.2) may lay its bands out differently and the
        // quantiser is the same code either way.
        int[] dimensions = new int[Pvq.MAX_DIMENSIONS];
        for (int n = 1; n <= Pvq.MAX_DIMENSIONS; n++) {
            dimensions[n - 1] = n;
        }

        Sweep sweep = sweep(dimensions);
        assertEquals(1963, sweep.pairs,
                "N from 1 to 176 with every K that fits one codebook is 1963 shapes, swept "
                + sweep.pairs);
        assertTrue(sweep.roundTrips > 40_000,
                "only " + sweep.roundTrips + " round trips over the whole plane");
        System.out.printf("PVQ whole plane: %d (N,K) pairs, %,d round trips%n",
                sweep.pairs, sweep.roundTrips);
    }

    /** What one sweep proved, so the tests above can assert they proved anything. */
    private record Sweep(int pairs, long roundTrips) {}

    /**
     * Drives every codeword index in {@code spread} for every (N,K) with these
     * dimensions, and checks the four things that must hold of the result.
     */
    private static Sweep sweep(int[] dimensions) {
        int pairs = 0;
        long roundTrips = 0;
        int[] pulses = new int[Pvq.MAX_DIMENSIONS];
        float[] shape = new float[Pvq.MAX_DIMENSIONS];

        for (int n : dimensions) {
            int maxK = Pvq.maxPulsesWithoutSplitting(n);
            for (int k = 0; k <= maxK; k++) {
                long size = Pvq.vectorCount(n, k);
                assertTrue(Pvq.fitsOneCodebook(n, k),
                        "V(" + n + "," + k + ") = " + size + " should fit one codebook");
                pairs++;
                for (long index : spread(size, n * 8191L + k)) {
                    Pvq.decodePulses(pulses, n, k, index);

                    int carried = 0;
                    for (int i = 0; i < n; i++) {
                        carried += Math.abs(pulses[i]);
                    }
                    assertEquals(k, carried,
                            "V(" + n + "," + k + ") index " + index + " decoded to a vector"
                            + " carrying " + carried + " pulses, and the band's gain is scaled"
                            + " for " + k);

                    assertEquals(index, Pvq.encodePulses(pulses, n, k),
                            "V(" + n + "," + k + ") index " + index + " did not come back");

                    if (k > 0) {
                        Pvq.normalise(pulses, shape, n, 1.0f);
                        double norm = 0;
                        for (int i = 0; i < n; i++) {
                            norm += (double) shape[i] * shape[i];
                        }
                        assertEquals(1.0, norm, 1e-6,
                                "V(" + n + "," + k + ") index " + index + " normalised to a norm"
                                + " of " + norm + ", which mis-scales the whole band against its"
                                + " separately coded energy");
                    }
                    roundTrips++;
                }
            }
        }
        return new Sweep(pairs, roundTrips);
    }

    // -------------------------------------------------------- independent counts

    @Test
    void vectorCountObeysItsOwnRecurrence() {
        // V(N,K) = V(N-1,K) + V(N,K-1) + V(N-1,K-1), from RFC 6716 section
        // 4.3.4.2: split on whether the first coefficient is zero, positive or
        // negative. Checked over every entry the decoder can read rather than
        // at a few spot values, because the entry that matters is the one at
        // the top of a band's row and that is the one a spot check misses.
        int checked = 0;
        for (int n = 1; n <= Pvq.MAX_DIMENSIONS; n++) {
            for (int k = 1; k <= Pvq.MAX_PULSES; k++) {
                if (!fits(n, k) || !fits(n - 1, k) || !fits(n, k - 1) || !fits(n - 1, k - 1)) {
                    continue;
                }
                long expected = Pvq.vectorCount(n - 1, k)
                        + Pvq.vectorCount(n, k - 1)
                        + Pvq.vectorCount(n - 1, k - 1);
                assertEquals(expected, Pvq.vectorCount(n, k),
                        "V(" + n + "," + k + ") breaks the recurrence");
                checked++;
            }
        }
        assertTrue(checked > 4_000,
                "only " + checked + " entries satisfied the recurrence, expected the whole table");
        System.out.printf("PVQ recurrence: %,d table entries%n", checked);
    }

    @Test
    void vectorCountMatchesACountTakenADifferentWay() {
        // The recurrence above is how the table is built, so agreeing with it
        // proves only that the arithmetic did not overflow. This counts the
        // same vectors by choosing which j coefficients are non-zero instead:
        // C(N,j) ways to pick them, 2^j sign patterns, and C(K-1,j-1) ways to
        // split K pulses among j non-empty coefficients. In BigInteger, so a
        // 64-bit wrap in the table shows up as a disagreement rather than as
        // two matching wrong answers.
        int checked = 0;
        for (int n = 0; n <= Pvq.MAX_DIMENSIONS; n++) {
            for (int k = 0; k <= Pvq.MAX_PULSES; k++) {
                if (!fits(n, k)) {
                    continue;
                }
                assertEquals(combinatorialCount(n, k), BigInteger.valueOf(Pvq.vectorCount(n, k)),
                        "V(" + n + "," + k + ") disagrees with the direct count");
                checked++;
            }
        }
        assertTrue(checked > 4_000,
                "only " + checked + " entries were cross-checked");
        System.out.printf("PVQ combinatorial cross-check: %,d table entries%n", checked);
    }

    // ------------------------------------------------------------ the codebook

    @Test
    void theCodebookIsExactlyTheSetOfVectorsCarryingKPulses() {
        // Drives every index of every small codebook and compares the set of
        // vectors that came out against a brute-force enumeration. This is the
        // check that a codebook is a bijection: an implementation that emitted
        // one vector twice would still round-trip both indices to something,
        // and would silently lose a distinct shape.
        int codebooks = 0;
        long codewords = 0;
        int[] pulses = new int[8];
        for (int n = 1; n <= 6; n++) {
            for (int k = 0; k <= 6; k++) {
                long size = Pvq.vectorCount(n, k);
                Set<String> decoded = new LinkedHashSet<>();
                for (long index = 0; index < size; index++) {
                    Pvq.decodePulses(pulses, n, k, index);
                    assertTrue(decoded.add(key(pulses, n)),
                            "V(" + n + "," + k + ") gives the same vector for two indices,"
                            + " so one shape in the codebook cannot be reached at all");
                    codewords++;
                }
                assertEquals(everyVectorWith(n, k), decoded,
                        "V(" + n + "," + k + ") is not the set of vectors carrying " + k
                        + " pulses in " + n + " coefficients");
                codebooks++;
            }
        }
        assertEquals(42, codebooks, "expected 42 small codebooks, enumerated " + codebooks);
        System.out.printf("PVQ exhaustive codebooks: %d, %,d codewords%n", codebooks, codewords);
    }

    @Test
    void theCodewordOrderIsTheOneTheProcedureProduces() {
        // Set equality above says nothing about which index names which
        // vector, and the index is what travels in the bitstream, so the order
        // is the whole contract. This states it a different way: the codebook
        // runs down the first coefficient from +K to +1, then the vectors with
        // zero there, then from -K to -1, with the tail of each block ordered
        // by the same rule. RFC 6716 section 4.3.4.2 step 2 is the split into
        // the positive and negative halves; step 4 is the walk down the
        // magnitudes.
        int[] pulses = new int[8];
        int compared = 0;
        for (int n = 1; n <= 6; n++) {
            for (int k = 0; k <= 6; k++) {
                List<int[]> expected = enumerateInOrder(n, k);
                assertEquals(Pvq.vectorCount(n, k), expected.size(),
                        "the ordering oracle and V(" + n + "," + k + ") disagree on the size");
                for (int index = 0; index < expected.size(); index++) {
                    Pvq.decodePulses(pulses, n, k, index);
                    int[] got = new int[n];
                    System.arraycopy(pulses, 0, got, 0, n);
                    assertArrayEquals(expected.get(index), got,
                            "V(" + n + "," + k + ") index " + index + " is the wrong codeword;"
                            + " every band coded with this shape lands on a different vector");
                    compared++;
                }
            }
        }
        assertTrue(compared > 3_000, "only " + compared + " codewords were order-checked");
    }

    @Test
    void theCodewordOrderMatchesTheRfcTracedByHand() {
        // The two smallest interesting codebooks, worked through the five
        // numbered steps of RFC 6716 section 4.3.4.2 on paper. These are here
        // because the oracle above and the decoder share a reading of the
        // specification, and these do not.
        assertOrder(2, 1, new int[][] {{1, 0}, {0, 1}, {0, -1}, {-1, 0}});
        assertOrder(3, 1, new int[][] {
            {1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}, {-1, 0, 0}});
    }

    @Test
    void theFirstCodewordPutsEveryPulseInTheFirstCoefficient() {
        // Index zero is (K, 0..., 0) for every shape. It falls out of step 4:
        // at i = 0 the walk runs all the way down, because the half-way point
        // equals the sum of V(N-1,t) for t up to K. This is the cheapest
        // possible check that the base of the indexing has not shifted, and a
        // shifted base moves every codeword in every band.
        int[] pulses = new int[Pvq.MAX_DIMENSIONS];
        for (int n : reachableDimensions()) {
            for (int k = 1; k <= Math.min(8, Pvq.maxPulsesWithoutSplitting(n)); k++) {
                Pvq.decodePulses(pulses, n, k, 0);
                assertEquals(k, pulses[0],
                        "V(" + n + "," + k + ") index 0 should put all " + k
                        + " pulses in the first coefficient");
                for (int i = 1; i < n; i++) {
                    assertEquals(0, pulses[i],
                            "V(" + n + "," + k + ") index 0 coefficient " + i + " is not zero");
                }
            }
        }
    }

    // ------------------------------------- the reference implementation's algebra

    @Test
    void theCodewordOrderMatchesTheReferenceImplementationsOwnIndexing() {
        // Everything above states the codebook order in terms of RFC 6716
        // section 4.3.4.2's five steps, which is also how the decoder is
        // written, so all of it shares one reading of one paragraph. This does
        // not. It builds U(N,K) -- the count of vectors whose leading non-zero
        // coefficient is positive, V(N,K) = U(N,K) + U(N,K+1) -- and runs
        // libopus's own cwrsi and icwrs over it: cwrsi with the separate closed
        // forms libopus uses once N falls to two and then to one, and icwrs
        // walking the coefficients backwards from the last, which is the
        // opposite direction from encodePulses. A shared misreading of the RFC
        // would have to reappear in a different recurrence, a different table
        // and a different direction of travel to survive this.
        //
        // What it protects: the index is the whole contract with the encoder.
        // A codebook that is the right set of vectors in the wrong order
        // decodes every band to a legitimate-looking shape that is not the one
        // that was sent, on every real Opus stream, and no amount of internal
        // round-tripping notices.
        BigInteger[][] u = leadingPositiveCounts();

        long exhaustive = 0;
        int[] pulses = new int[Pvq.MAX_DIMENSIONS];
        for (int n = 1; n <= 7; n++) {
            for (int k = 0; k <= 7; k++) {
                long size = Pvq.vectorCount(n, k);
                assertEquals(BigInteger.valueOf(size),
                        u[n][k].add(u[n][k + 1]),
                        "V(" + n + "," + k + ") is not U(N,K) + U(N,K+1)");
                for (long index = 0; index < size; index++) {
                    Pvq.decodePulses(pulses, n, k, index);
                    int[] reference = referenceDecode(u, n, k, BigInteger.valueOf(index));
                    for (int at = 0; at < n; at++) {
                        assertEquals(reference[at], pulses[at],
                                "V(" + n + "," + k + ") index " + index + " coefficient " + at
                                + " is not what cwrsi gives");
                    }
                    assertEquals(BigInteger.valueOf(index), referenceEncode(u, n, reference),
                            "icwrs did not return index " + index + " of V(" + n + "," + k + ")");
                    exhaustive++;
                }
            }
        }
        assertTrue(exhaustive > 70_000,
                "only " + exhaustive + " codewords were compared exhaustively");

        // The same comparison at the sizes the exhaustive sweep cannot reach:
        // the widest band, and the rows where the two counts step one added
        // together no longer fit a signed long.
        long sampled = 0;
        int wide = 0;
        for (int n = 1; n <= Pvq.MAX_DIMENSIONS; n++) {
            for (int k = 0; k <= Pvq.MAX_PULSES; k++) {
                if (!fits(n, k)) {
                    continue;
                }
                long size = Pvq.vectorCount(n, k);
                assertEquals(BigInteger.valueOf(size), u[n][k].add(u[n][k + 1]),
                        "V(" + n + "," + k + ") is not U(N,K) + U(N,K+1)");
                for (long index : spread(size, n * 40_961L + k)) {
                    Pvq.decodePulses(pulses, n, k, index);
                    int[] reference = referenceDecode(u, n, k, BigInteger.valueOf(index));
                    for (int at = 0; at < n; at++) {
                        assertEquals(reference[at], pulses[at],
                                "V(" + n + "," + k + ") index " + index + " coefficient " + at
                                + " is not what cwrsi gives");
                    }
                    assertEquals(BigInteger.valueOf(index), referenceEncode(u, n, reference),
                            "icwrs did not return index " + index + " of V(" + n + "," + k + ")");
                    sampled++;
                }
                if (n == Pvq.MAX_DIMENSIONS) {
                    wide++;
                }
            }
        }
        assertTrue(sampled > 20_000, "only " + sampled + " codewords were sampled");
        assertEquals(11, wide,
                "the 176-bin band should have been compared at every K its row holds, got " + wide);
        System.out.printf("PVQ against libopus indexing: %,d exhaustive, %,d sampled codewords%n",
                exhaustive, sampled);
    }

    @Test
    void theHalfWayPointIsAWholeNumberEverywhereInTheTable() {
        // Step 1 of the decode divides V(N-1,K) + V(N,K) by two, and the
        // implementation does it with a shift. A shift is only the same thing
        // as a division when the sum is even, and nothing in the round trips
        // would notice if it were not: the truncation would move the boundary
        // between the positive and negative halves by one codeword, so a single
        // index at the seam would decode with the wrong sign and every other
        // index would still be right. Checked in BigInteger over the whole
        // table rather than argued.
        int checked = 0;
        for (int n = 1; n <= Pvq.MAX_DIMENSIONS; n++) {
            for (int k = 0; k <= Pvq.MAX_PULSES; k++) {
                if (!fits(n, k) || !fits(n - 1, k)) {
                    continue;
                }
                BigInteger sum = BigInteger.valueOf(Pvq.vectorCount(n - 1, k))
                        .add(BigInteger.valueOf(Pvq.vectorCount(n, k)));
                assertFalse(sum.testBit(0),
                        "V(" + (n - 1) + "," + k + ") + V(" + n + "," + k + ") is " + sum
                        + ", which is odd, so halving it with a shift loses a codeword");
                checked++;
            }
        }
        assertTrue(checked > 4_000, "only " + checked + " half-way points were checked");
    }

    @Test
    void theSplitThresholdIsTheExactWidthTheRangeCoderCanCarry() {
        // RFC 6716 section 4.1.5: ec_dec_uint takes a frequency total that "may
        // be as large as (2**32 - 1)". So the last codebook that can travel as
        // one index is V(N,K) = 2^32 - 1 and the first that cannot is 2^32, and
        // the boundary has to be that exact, because being one conservative
        // means splitting a band the encoder did not split and reading a gain
        // symbol that was never written.
        //
        // The threshold is checked against a count taken in BigInteger from the
        // non-zero-coefficient identity, so a wrong V(N,K) near 2^32 cannot
        // agree with itself.
        BigInteger cap = BigInteger.ONE.shiftLeft(32);
        int checked = 0;
        for (int n = 1; n <= Pvq.MAX_DIMENSIONS; n++) {
            int expected = 0;
            for (int k = 1; k <= Pvq.MAX_PULSES; k++) {
                if (combinatorialCount(n, k).compareTo(cap) >= 0) {
                    break;
                }
                expected = k;
            }
            assertEquals(expected, Pvq.maxPulsesWithoutSplitting(n),
                    "a band of " + n + " coefficients holds " + expected
                    + " pulses inside 32 bits");
            assertTrue(Pvq.fitsOneCodebook(n, expected),
                    "V(" + n + "," + expected + ") should fit one codebook");
            if (expected < Pvq.MAX_PULSES) {
                assertFalse(Pvq.fitsOneCodebook(n, expected + 1),
                        "V(" + n + "," + (expected + 1) + ") is at least 2^32 and must be split");
            }
            checked++;
        }
        assertEquals(176, checked, "every band width should have been checked");

        // The same boundary read the other way round, as the widest band for
        // each pulse count. These are the numbers libopus keeps in the maxN
        // table of fits_in32(), reached here from the RFC's own count.
        int[] widestFor = {0, 0, 0, 1476, 283, 109, 60, 40, 29, 24, 20, 18, 16, 14, 13};
        for (int k = 3; k < widestFor.length; k++) {
            assertTrue(combinatorialCount(widestFor[k], k).compareTo(cap) < 0,
                    "V(" + widestFor[k] + "," + k + ") should still fit 32 bits");
            assertTrue(combinatorialCount(widestFor[k] + 1, k).compareTo(cap) >= 0,
                    "V(" + (widestFor[k] + 1) + "," + k + ") should be past 32 bits");
            if (widestFor[k] <= Pvq.MAX_DIMENSIONS) {
                assertTrue(Pvq.fitsOneCodebook(widestFor[k], k),
                        "V(" + widestFor[k] + "," + k + ") is the widest band that fits");
            }
            if (widestFor[k] < Pvq.MAX_DIMENSIONS) {
                assertFalse(Pvq.fitsOneCodebook(widestFor[k] + 1, k),
                        "V(" + (widestFor[k] + 1) + "," + k + ") is one bin too wide");
            }
        }
    }

    @Test
    void aCodebookWithNoCodewordsIsNotOneThatFits() {
        // V(0,K) is zero for K other than zero: no pulse fits in no
        // coefficients. That is not a codebook that can be sent in one index,
        // it is a codebook with nothing in it, and a band decoder that asked
        // whether it needed splitting and was told no would hand the range
        // decoder a frequency total of zero. RFC 6716 section 4.1.5 defines
        // ec_dec_uint only for a positive ft, so what happens next is whatever
        // the arithmetic does -- in practice a division by zero or a symbol
        // read out of an empty distribution.
        assertEquals(0L, Pvq.vectorCount(0, 1), "V(0,1) is empty");
        assertFalse(Pvq.fitsOneCodebook(0, 1),
                "an empty codebook cannot be coded as one index");
        assertFalse(Pvq.fitsOneCodebook(0, Pvq.MAX_PULSES),
                "an empty codebook cannot be coded as one index");
        assertEquals(0, Pvq.maxPulsesWithoutSplitting(0),
                "no coefficients can carry a pulse, split or not");

        // The one shape with no coefficients that does exist: the empty vector,
        // which is the only member of its codebook and costs nothing.
        assertEquals(1L, Pvq.vectorCount(0, 0), "V(0,0) is the empty vector");
        assertTrue(Pvq.fitsOneCodebook(0, 0), "a codebook of one costs no bits");

        // And nothing was broken for the bands that do exist.
        assertEquals(4, Pvq.maxPulsesWithoutSplitting(176), "the widest band still holds four");
        assertEquals(Pvq.MAX_PULSES, Pvq.maxPulsesWithoutSplitting(1),
                "one coefficient is a sign bit at any K");
    }

    // ------------------------------------------------------------- bit lengths

    @Test
    void bitsForPulsesIsTheCeilingOfLogTwoOfTheCodebookSize() {
        // This is what the allocator spends and what the range decoder reads:
        // ec_dec_uint with a frequency total of ft consumes ilog(ft-1) bits.
        // One bit out here and the band after this one starts at the wrong
        // place in the stream.
        int checked = 0;
        for (int n = 1; n <= Pvq.MAX_DIMENSIONS; n++) {
            for (int k = 0; k <= Pvq.MAX_PULSES; k++) {
                if (!fits(n, k)) {
                    continue;
                }
                BigInteger size = BigInteger.valueOf(Pvq.vectorCount(n, k));
                int expected = size.subtract(BigInteger.ONE).bitLength();
                assertEquals(expected, Pvq.bitsForPulses(n, k),
                        "V(" + n + "," + k + ") = " + size + " needs " + expected + " bits");
                checked++;
            }
        }
        assertTrue(checked > 4_000, "only " + checked + " bit lengths were checked");

        // K = 0 is a codebook of one vector and costs nothing to send.
        assertEquals(0, Pvq.bitsForPulses(176, 0),
                "a band with no pulses should cost no bits at all");
        assertEquals(1, Pvq.bitsForPulses(1, 1),
                "one coefficient with one pulse is a sign bit");
    }

    // -------------------------------------------------------- refusals and range

    @Test
    void anIndexAtOrPastTheCodebookSizeIsReportedRatherThanWrapped() {
        long size = Pvq.vectorCount(22, 5);
        int[] pulses = new int[22];

        // The control: the last legal index decodes, so the refusal below is
        // about the boundary and not about the shape being unusable.
        Pvq.decodePulses(pulses, 22, 5, size - 1);

        for (long past : new long[] {size, size + 1, size * 2, Long.MAX_VALUE, -1}) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> Pvq.decodePulses(pulses, 22, 5, past),
                    "index " + past + " is outside V(22,5) = " + size + " and was accepted");
            assertTrue(thrown.getMessage().contains(String.valueOf(past)),
                    "the refusal should name the index: " + thrown.getMessage());
        }

        // Why silence is not an option here. An implementation that reduced
        // the index modulo the codebook size would produce index 0, and index
        // 0 is a perfectly well formed codeword: 22 coefficients, exactly 5
        // pulses, unit norm once scaled. Nothing downstream could tell it from
        // the right answer, so the only place the fault can be caught is here.
        Pvq.decodePulses(pulses, 22, 5, size % size);
        int carried = 0;
        for (int value : pulses) {
            carried += Math.abs(value);
        }
        assertEquals(5, carried,
                "the vector a wrapping decoder would produce is indistinguishable from a"
                + " correct one, which is why the wrap must be refused");
    }

    @Test
    void aShapeOutsideWhatCeltCanReachIsReportedRatherThanRead() {
        assertThrows(IllegalArgumentException.class, () -> Pvq.vectorCount(177, 1),
                "177 coefficients is wider than any CELT band");
        assertThrows(IllegalArgumentException.class, () -> Pvq.vectorCount(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> Pvq.vectorCount(8, 129),
                "129 pulses is past the allocator's ceiling");
        assertThrows(IllegalArgumentException.class, () -> Pvq.vectorCount(8, -1));
    }

    @Test
    void aBufferTooShortForTheBandIsReportedRatherThanOverrun() {
        // The failure this prevents is an ArrayIndexOutOfBoundsException
        // escaping from inside the decode loop, which a caller cannot
        // distinguish from a bug in itself and which carries no message.
        assertThrows(IllegalArgumentException.class,
                () -> Pvq.decodePulses(new int[3], 4, 2, 0),
                "a four-coefficient band written into a three-element buffer");
        assertThrows(IllegalArgumentException.class,
                () -> Pvq.encodePulses(new int[3], 4, 2));
        assertThrows(IllegalArgumentException.class,
                () -> Pvq.normalise(new int[3], new float[4], 4, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> Pvq.normalise(new int[4], new float[3], 4, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> Pvq.decodePulses(new int[4], 0, 2, 0),
                "a band with no coefficients");
    }

    @Test
    void aVectorWhosePulsesDoNotSumToKIsReported() {
        // The encoder side of the same contract. A vector carrying the wrong
        // number of pulses has no index in this codebook, and returning one
        // anyway would put the decoder on a different vector.
        assertThrows(IllegalArgumentException.class,
                () -> Pvq.encodePulses(new int[] {1, 0, 0, 0}, 4, 3),
                "one pulse offered to the three-pulse codebook");
        assertThrows(IllegalArgumentException.class,
                () -> Pvq.encodePulses(new int[] {4, 0, 0, 0}, 4, 3),
                "four pulses offered to the three-pulse codebook");
        assertThrows(IllegalArgumentException.class,
                () -> Pvq.encodePulses(new int[] {2, -2, 0, 0}, 4, 3));

        // Integer.MIN_VALUE is its own absolute value, so a buffer that never
        // came out of decodePulses gets past any check that only looks at the
        // upper bound, and the running pulse count then wraps positive.
        assertThrows(IllegalArgumentException.class,
                () -> Pvq.encodePulses(new int[] {Integer.MIN_VALUE, 0, 0, 0}, 4, 3));
    }

    // ---------------------------------------------------------------- overflow

    @Test
    void theWidestBandLeavesAnIntegerAndThenALongAtKnownPoints() {
        // The numbers the class Javadoc quotes, pinned. If V(N,K) is ever
        // computed in a narrower type than a long these are the first entries
        // that come back positive and wrong.
        assertEquals(639_716_352L, Pvq.vectorCount(176, 4),
                "V(176,4) is the last codebook for the widest band that fits a signed int");
        assertTrue(Pvq.vectorCount(176, 4) < Integer.MAX_VALUE,
                "V(176,4) should still fit a signed int");
        assertEquals(45_040_392_672L, Pvq.vectorCount(176, 5),
                "V(176,5) needs 36 bits");
        assertEquals(8_063_144_027_840_174_592L, Pvq.vectorCount(176, 10),
                "V(176,10) is the last entry of that row to fit a signed long");

        // One past it is refused rather than returned as a wrapped value.
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Pvq.vectorCount(176, 11),
                "V(176,11) needs 68 bits and cannot be returned");
        assertTrue(thrown.getMessage().contains("64-bit"), thrown.getMessage());

        // Every entry in the table is positive. A wrap shows up as a negative
        // number or a zero, and a zero-sized codebook would make the range
        // decoder read a symbol with no symbols in it.
        int entries = 0;
        for (int n = 1; n <= Pvq.MAX_DIMENSIONS; n++) {
            for (int k = 0; k <= Pvq.MAX_PULSES; k++) {
                if (!fits(n, k)) {
                    continue;
                }
                assertTrue(Pvq.vectorCount(n, k) > 0,
                        "V(" + n + "," + k + ") is " + Pvq.vectorCount(n, k) + ", which has wrapped");
                entries++;
            }
        }
        assertTrue(entries > 4_000, "only " + entries + " entries were checked for a wrap");
    }

    @Test
    void theHalfWayPointStaysExactWhereTheCountsNearlyFillALong() {
        // Step 1 of the decode is (V(N-1,K) + V(N,K)) / 2. At the top of the
        // table that sum does not fit: V(175,10) + V(176,10) is
        // 15,679,861,705,100,127,042 against a signed long's ceiling of
        // 9,223,372,036,854,775,807, so adding first and shifting gives a
        // negative half and the band decodes from nonsense. The sweeps above
        // never reach here because they stop at the 32-bit codebook cap, so
        // these rows are the only thing holding that arithmetic honest.
        int[] pulses = new int[Pvq.MAX_DIMENSIONS];
        int rows = 0;
        long roundTrips = 0;
        for (int n = 1; n <= Pvq.MAX_DIMENSIONS; n++) {
            int k = Pvq.MAX_PULSES;
            while (k > 0 && !fits(n, k)) {
                k--;
            }
            if (k == 0 || !fits(n - 1, k)) {
                continue;
            }
            long lower = Pvq.vectorCount(n - 1, k);
            long size = Pvq.vectorCount(n, k);
            if (lower <= Long.MAX_VALUE - size) {
                continue;
            }
            rows++;
            for (long index : spread(size, n * 65_537L + k)) {
                Pvq.decodePulses(pulses, n, k, index);
                int carried = 0;
                for (int i = 0; i < n; i++) {
                    carried += Math.abs(pulses[i]);
                }
                assertEquals(k, carried,
                        "V(" + n + "," + k + ") index " + index + " decoded to " + carried
                        + " pulses, so the half-way point overflowed");
                assertEquals(index, Pvq.encodePulses(pulses, n, k),
                        "V(" + n + "," + k + ") index " + index + " did not come back");
                roundTrips++;
            }
        }
        assertEquals(44, rows,
                "44 rows of the table end where the two counts no longer add without"
                + " overflowing, exercised " + rows);
        System.out.printf("PVQ overflow-edge rows: %d, %,d round trips%n", rows, roundTrips);
    }

    @Test
    void splittingIsWhatKeepsAWideBandInsideThirtyTwoBits() {
        // RFC 6716 section 4.3.4.4. The widest band cannot carry more than
        // four pulses in one codebook, so anything the allocator gives it
        // beyond that has to be coded as two halves with a gain between them,
        // up to LM+1 times. A decoder that skipped the split would hand the
        // range coder a frequency total wider than the 32 bits its uniform
        // symbol carries, and every band after it in the frame would decode
        // from a range coder that no longer matches the encoder's.
        assertEquals(4, Pvq.maxPulsesWithoutSplitting(176),
                "the 176-bin band holds four pulses in one codebook");
        assertTrue(Pvq.fitsOneCodebook(176, 4), "V(176,4) is 30 bits");
        assertFalse(Pvq.fitsOneCodebook(176, 5), "V(176,5) is 36 bits and must be split");

        // The halves the split produces, and what each can carry. The band
        // gets steadily more pulses as it is cut down, which is the point of
        // cutting it.
        assertEquals(5, Pvq.maxPulsesWithoutSplitting(88), "88 coefficients");
        assertEquals(6, Pvq.maxPulsesWithoutSplitting(44), "44 coefficients");
        assertEquals(9, Pvq.maxPulsesWithoutSplitting(22), "22 coefficients");
        assertEquals(18, Pvq.maxPulsesWithoutSplitting(11), "11 coefficients");

        // A two-coefficient band is never split, and never needs to be:
        // V(2,K) is only 4K, so even the allocator's ceiling is 512.
        assertEquals(Pvq.MAX_PULSES, Pvq.maxPulsesWithoutSplitting(2),
                "a two-coefficient band is capped by the allocator, not by the codebook");
        assertEquals(512L, Pvq.vectorCount(2, 128), "V(2,128)");

        // And the reason the reference implementation's tables are indexed by
        // the smaller of N and K with only fifteen rows.
        assertTrue(Pvq.fitsOneCodebook(13, 13), "V(13,13) is 830,764,794");
        assertFalse(Pvq.fitsOneCodebook(14, 14),
                "V(14,14) is 4,666,890,936, so nothing that fits one codebook has both"
                + " N and K above 13");
    }

    // --------------------------------------------------------------- normalise

    @Test
    void theNormalisedBandCarriesTheGainAndNothingElse() {
        // The shape is unit norm and the band's energy arrives separately, so
        // the gain is the only thing that sets how loud the band is. A norm
        // that drifts with K or N would make the level of a band depend on how
        // many pulses the allocator happened to give it, which is heard as the
        // spectrum breathing.
        int[] pulses = new int[176];
        float[] shape = new float[176];
        for (float gain : new float[] {1.0f, 0.5f, 0.125f, 3.75f}) {
            for (int n : reachableDimensions()) {
                for (int k = 1; k <= Math.min(9, Pvq.maxPulsesWithoutSplitting(n)); k++) {
                    long size = Pvq.vectorCount(n, k);
                    Pvq.decodePulses(pulses, n, k, size / 2);
                    Pvq.normalise(pulses, shape, n, gain);
                    double norm = 0;
                    for (int i = 0; i < n; i++) {
                        norm += (double) shape[i] * shape[i];
                    }
                    // Measured against the gain rather than as an absolute
                    // difference: at a gain of 3.75 a single float rounding is
                    // already 1.8e-6 of the answer, and holding that to the
                    // same absolute bound as a unit-gain band would be a test
                    // of float's mantissa, not of the normalisation.
                    assertEquals(1.0, norm / (gain * gain), 1e-6,
                            "V(" + n + "," + k + ") at gain " + gain + " normalised to " + norm
                            + " where the gain squared is " + (gain * gain));
                }
            }
        }
    }

    @Test
    void theNormalisedBandPointsWhereThePulsesDo() {
        // Direction, not just length. Scaling by the wrong sign or permuting
        // the coefficients would keep the norm at one and put the energy in
        // the wrong MDCT bins.
        int[] pulses = {3, 0, -4, 0};
        float[] shape = new float[4];
        Pvq.normalise(pulses, shape, 4, 1.0f);
        assertEquals(0.6f, shape[0], 1e-6f, "3 of a 5-long vector");
        assertEquals(0.0f, shape[1], 1e-6f, "an empty coefficient stays empty");
        assertEquals(-0.8f, shape[2], 1e-6f, "the sign of the pulse is the sign of the coefficient");
        assertEquals(0.0f, shape[3], 1e-6f, "an empty coefficient stays empty");
    }

    @Test
    void anAllZeroBandIsRefusedRatherThanNormalisedToNaN() {
        // Dividing by a zero norm gives infinity, times zero gives NaN, and a
        // NaN survives the inverse MDCT and the overlap-add into the next
        // frame as well: a single silent band would take out a fifth of a
        // second of audio rather than one band of one frame.
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Pvq.normalise(new int[4], new float[4], 4, 1.0f));
        assertTrue(thrown.getMessage().contains("direction"), thrown.getMessage());
    }

    @Test
    void aBandThatCouldNotHaveComeFromTheCodebookIsRefusedRatherThanMisScaled() {
        // normalise takes a buffer and no K, so it cannot check the pulse count
        // -- but it can check that no single coefficient is larger than any
        // pulse count allows, and it has to, because the sum of squares is
        // where the arithmetic runs out. Two different things go wrong past
        // that point and only one of them is loud.
        //
        // Loud: 176 coefficients of Integer.MAX_VALUE sum to -755,914,243,920,
        // the square root of a negative number is NaN, and every sample of the
        // band comes back NaN. A NaN does not stay in its band; it goes through
        // the inverse MDCT into every sample of the frame and through the
        // overlap-add into the next one, so a fifth of a second goes silent
        // with a click at each end.
        int[] huge = new int[176];
        java.util.Arrays.fill(huge, Integer.MAX_VALUE);
        float[] band = new float[176];
        IllegalArgumentException loud = assertThrows(IllegalArgumentException.class,
                () -> Pvq.normalise(huge, band, 176, 1.0f),
                "a coefficient of " + Integer.MAX_VALUE + " wraps the energy sum negative"
                + " and fills the band with NaN");
        assertTrue(loud.getMessage().contains(String.valueOf(Integer.MAX_VALUE)),
                "the refusal should name the coefficient: " + loud.getMessage());

        // Quiet, and worse. Five of the same value wrap the sum the other way,
        // to 4,611,685,996,952,551,429, which is positive and plausible. There
        // is no NaN and no exception; the band is simply scaled by the square
        // root of five too much. Nothing downstream can tell a band that is
        // 2.2 times too loud from one the encoder meant, so if it is not caught
        // here it is not caught.
        int[] quiet = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
            Integer.MAX_VALUE, Integer.MAX_VALUE};
        assertThrows(IllegalArgumentException.class,
                () -> Pvq.normalise(quiet, new float[5], 5, 1.0f),
                "five coefficients of " + Integer.MAX_VALUE + " wrap the energy sum to a"
                + " positive number and scale the band 2.2 times too loud in silence");

        // Integer.MIN_VALUE is its own absolute value, so a check written with
        // Math.abs would let it through.
        assertThrows(IllegalArgumentException.class,
                () -> Pvq.normalise(new int[] {Integer.MIN_VALUE, 0}, new float[2], 2, 1.0f));

        // One past the largest pulse count is refused; the largest is not.
        // Every coefficient a real band can hold survives, which is what stops
        // this from being a check that rejects legitimate audio.
        assertThrows(IllegalArgumentException.class,
                () -> Pvq.normalise(new int[] {Pvq.MAX_PULSES + 1, 0}, new float[2], 2, 1.0f));
        float[] limit = new float[2];
        Pvq.normalise(new int[] {Pvq.MAX_PULSES, 0}, limit, 2, 1.0f);
        assertEquals(1.0f, limit[0], 1e-6f,
                "a band with every pulse in one coefficient normalises to one");
        Pvq.normalise(new int[] {-Pvq.MAX_PULSES, 0}, limit, 2, 1.0f);
        assertEquals(-1.0f, limit[0], 1e-6f, "and so does its negative");
    }

    // ----------------------------------------------------------------- fixtures

    /**
     * U(N,K), the count libopus indexes its codebooks with, as BigInteger.
     *
     * <p>U(N,K) counts the vectors of N coefficients carrying K pulses whose
     * leading non-zero coefficient is positive, so V(N,K) = U(N,K) + U(N,K+1).
     * It obeys the same three-term recurrence as V but with different base
     * cases -- U(N,0) = 0, U(0,K) = 0, U(1,K) = 1 for K >= 1 -- which is what
     * makes it usable as a check on V rather than a restatement of it.
     */
    private static BigInteger[][] leadingPositiveCounts() {
        int rows = Pvq.MAX_DIMENSIONS + 2;
        int columns = Pvq.MAX_PULSES + 3;
        BigInteger[][] u = new BigInteger[rows][columns];
        for (BigInteger[] row : u) {
            java.util.Arrays.fill(row, BigInteger.ZERO);
        }
        for (int k = 1; k < columns; k++) {
            u[1][k] = BigInteger.ONE;
        }
        for (int n = 2; n < rows; n++) {
            for (int k = 1; k < columns; k++) {
                u[n][k] = u[n - 1][k].add(u[n][k - 1]).add(u[n - 1][k - 1]);
            }
        }
        return u;
    }

    /**
     * {@code cwrsi} from {@code celt/cwrs.c}, index to vector, over U(N,K).
     *
     * <p>Including the two closed forms libopus drops into once the dimension
     * falls to two and then to one, because those are separate code paths
     * upstream and an off-by-one in the general loop would not show up in them.
     */
    private static int[] referenceDecode(BigInteger[][] u, int n, int k, BigInteger index) {
        int[] y = new int[n];
        int at = 0;
        int dimension = n;
        int left = k;
        BigInteger i = index;
        while (dimension > 2) {
            BigInteger half = u[dimension][left + 1];
            boolean negative = i.compareTo(half) >= 0;
            if (negative) {
                i = i.subtract(half);
            }
            int before = left;
            BigInteger p = u[dimension][left];
            while (p.compareTo(i) > 0) {
                left--;
                p = u[dimension][left];
            }
            i = i.subtract(p);
            y[at++] = negative ? -(before - left) : before - left;
            dimension--;
        }
        if (dimension == 2) {
            BigInteger half = BigInteger.valueOf(2L * left + 1);
            boolean negative = i.compareTo(half) >= 0;
            if (negative) {
                i = i.subtract(half);
            }
            int before = left;
            left = i.add(BigInteger.ONE).shiftRight(1).intValueExact();
            if (left != 0) {
                i = i.subtract(BigInteger.valueOf(2L * left - 1));
            }
            y[at++] = negative ? -(before - left) : before - left;
        }
        y[at] = i.signum() == 0 ? left : -left;
        return y;
    }

    /**
     * {@code icwrs} from {@code celt/cwrs.c}, vector to index, over U(N,K).
     *
     * <p>Walks the coefficients from the last to the first, which is the
     * opposite direction from {@link Pvq#encodePulses}: the two agreeing is a
     * statement about the codebook rather than about either walk.
     */
    private static BigInteger referenceEncode(BigInteger[][] u, int n, int[] y) {
        if (n == 1) {
            return y[0] < 0 ? BigInteger.ONE : BigInteger.ZERO;
        }
        int j = n - 1;
        BigInteger i = BigInteger.valueOf(y[j] < 0 ? 1 : 0);
        int k = Math.abs(y[j]);
        do {
            j--;
            i = i.add(u[n - j][k]);
            k += Math.abs(y[j]);
            if (y[j] < 0) {
                i = i.add(u[n - j][k + 1]);
            }
        } while (j > 0);
        return i;
    }

    /**
     * The band widths the PVQ can be handed, from RFC 6716 Table 55 and the
     * split rule in section 4.3.4.4.
     *
     * <p>A band is {@code width << LM} coefficients, and a band too wide for
     * one codebook is halved, LM dropping by one each time, up to LM+1 times
     * and never below three coefficients. That last halving is where the odd
     * widths come from: 22 becomes 11, 18 becomes 9, 6 becomes 3.
     */
    private static int[] reachableDimensions() {
        Set<Integer> found = new TreeSet<>();
        for (int lm = 0; lm <= 3; lm++) {
            for (int width : BAND_WIDTHS_2_5MS) {
                int n = width << lm;
                int level = lm;
                found.add(n);
                while (level != -1 && n > 2) {
                    n >>= 1;
                    level--;
                    found.add(n);
                }
            }
        }
        int[] result = new int[found.size()];
        int at = 0;
        for (int n : found) {
            result[at++] = n;
        }
        return result;
    }

    /**
     * Indices to drive for a codebook of this size: all of them when the
     * codebook is small, otherwise the ends, the middle, and a fixed spread.
     *
     * <p>The spread comes from a seeded linear congruential generator rather
     * than {@code java.util.Random}, so the same shapes are exercised on every
     * machine and a failure can be reproduced from the message alone.
     */
    private static long[] spread(long size, long seed) {
        if (size <= 64) {
            long[] all = new long[(int) size];
            for (int i = 0; i < size; i++) {
                all[i] = i;
            }
            return all;
        }
        long[] chosen = new long[24];
        chosen[0] = 0;
        chosen[1] = 1;
        chosen[2] = 2;
        chosen[3] = size / 8;
        chosen[4] = size / 4;
        chosen[5] = size / 3;
        chosen[6] = size / 2;
        chosen[7] = size - size / 3;
        chosen[8] = size - size / 4;
        chosen[9] = size - size / 8;
        chosen[10] = size - 3;
        chosen[11] = size - 2;
        chosen[12] = size - 1;
        long state = seed * 2_654_435_761L + 1;
        for (int i = 13; i < chosen.length; i++) {
            state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L;
            chosen[i] = Math.floorMod(state >>> 1, size);
        }
        return chosen;
    }

    /** Whether V(N,K) is a value this implementation will hand out at all. */
    private static boolean fits(int n, int k) {
        if (n < 0 || n > Pvq.MAX_DIMENSIONS || k < 0 || k > Pvq.MAX_PULSES) {
            return false;
        }
        try {
            Pvq.vectorCount(n, k);
            return true;
        } catch (IllegalArgumentException tooBig) {
            return false;
        }
    }

    /**
     * V(N,K) counted by how many coefficients are non-zero.
     *
     * <p>Choose j of the N coefficients to be non-zero, C(N,j) ways; give each
     * a sign, 2^j ways; split K pulses into j non-empty parts, C(K-1,j-1)
     * ways. Nothing about this derivation touches the recurrence the table is
     * built from, which is the point of having it.
     */
    private static BigInteger combinatorialCount(int n, int k) {
        if (k == 0) {
            return BigInteger.ONE;
        }
        BigInteger total = BigInteger.ZERO;
        for (int j = 1; j <= Math.min(n, k); j++) {
            total = total.add(BigInteger.TWO.pow(j)
                    .multiply(binomial(n, j))
                    .multiply(binomial(k - 1, j - 1)));
        }
        return total;
    }

    private static BigInteger binomial(int n, int k) {
        if (k < 0 || k > n) {
            return BigInteger.ZERO;
        }
        BigInteger result = BigInteger.ONE;
        for (int i = 0; i < k; i++) {
            result = result.multiply(BigInteger.valueOf(n - i))
                    .divide(BigInteger.valueOf(i + 1));
        }
        return result;
    }

    /**
     * The codebook in index order, stated as a recursion over the first
     * coefficient instead of as the decoder's subtract-and-walk.
     *
     * <p>The vectors with the first coefficient at +K come first, then +K-1,
     * down to +1; then the vectors with zero there; then -K down to -1. Each
     * block's tail is ordered by the same rule with the pulses that are left.
     */
    private static List<int[]> enumerateInOrder(int n, int k) {
        List<int[]> out = new ArrayList<>();
        if (n == 0) {
            if (k == 0) {
                out.add(new int[0]);
            }
            return out;
        }
        for (int m = k; m >= 1; m--) {
            appendWithHead(out, m, enumerateInOrder(n - 1, k - m));
        }
        appendWithHead(out, 0, enumerateInOrder(n - 1, k));
        for (int m = k; m >= 1; m--) {
            appendWithHead(out, -m, enumerateInOrder(n - 1, k - m));
        }
        return out;
    }

    private static void appendWithHead(List<int[]> out, int head, List<int[]> tails) {
        for (int[] tail : tails) {
            int[] whole = new int[tail.length + 1];
            whole[0] = head;
            System.arraycopy(tail, 0, whole, 1, tail.length);
            out.add(whole);
        }
    }

    /** Every vector of N integers whose absolute values sum to K, by brute force. */
    private static Set<String> everyVectorWith(int n, int k) {
        Set<String> found = new LinkedHashSet<>();
        collect(found, new int[n], 0, k);
        return found;
    }

    private static void collect(Set<String> found, int[] vector, int at, int left) {
        if (at == vector.length) {
            if (left == 0) {
                found.add(key(vector, vector.length));
            }
            return;
        }
        for (int value = -left; value <= left; value++) {
            vector[at] = value;
            collect(found, vector, at + 1, left - Math.abs(value));
        }
        vector[at] = 0;
    }

    private static String key(int[] vector, int n) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < n; i++) {
            text.append(vector[i]).append(',');
        }
        return text.toString();
    }

    private static void assertOrder(int n, int k, int[][] expected) {
        assertEquals(expected.length, Pvq.vectorCount(n, k),
                "V(" + n + "," + k + ") should have " + expected.length + " codewords");
        int[] pulses = new int[n];
        for (int index = 0; index < expected.length; index++) {
            Pvq.decodePulses(pulses, n, k, index);
            assertArrayEquals(expected[index], pulses,
                    "V(" + n + "," + k + ") index " + index + " is not what the RFC's steps give");
            assertEquals(index, Pvq.encodePulses(pulses, n, k),
                    "V(" + n + "," + k + ") index " + index + " did not come back");
        }
    }
}
