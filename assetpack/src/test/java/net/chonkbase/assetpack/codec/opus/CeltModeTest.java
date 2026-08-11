package net.chonkbase.assetpack.codec.opus;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The CELT mode tables, checked against the second statement RFC 6716 makes of
 * each of them.
 *
 * <p>Nothing here can be checked by decoding, because a wrong number in these
 * tables does not throw and does not desynchronise the range coder in any way a
 * framing test would notice. It gives one band in one frame size the wrong number
 * of bits, and what comes out is a track that sounds slightly wrong in one
 * register. So every table is checked against something independent: the bin
 * counts against the frequencies RFC 6716 Table 55 prints beside them, the
 * allocation table against the monotonicity the shape of Table 57 requires, the
 * logarithm tables against integer arithmetic that rederives them, and the
 * time-frequency table against Tables 60 to 63 typed out again below.
 */
class CeltModeTest {

    private static final int[] FRAME_SIZES = {120, 240, 480, 960};

    /**
     * The "Start Frequency" column of RFC 6716 Table 55 with the final "Stop
     * Frequency" appended, typed in from the RFC independently of the copy the
     * production tables hold.
     */
    private static final int[] TABLE_55_EDGE_HZ = {
        0, 200, 400, 600, 800, 1000, 1200, 1400, 1600, 2000, 2400, 2800,
        3200, 4000, 4800, 5600, 6800, 8000, 9600, 12000, 15600, 20000
    };

    /** The bins-per-band columns of RFC 6716 Table 55, indexed by LM then band. */
    private static final int[][] TABLE_55_BINS = {
        {1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 6, 6, 8, 12, 18, 22},
        {2, 2, 2, 2, 2, 2, 2, 2, 4, 4, 4, 4, 8, 8, 8, 12, 12, 16, 24, 36, 44},
        {4, 4, 4, 4, 4, 4, 4, 4, 8, 8, 8, 8, 16, 16, 16, 24, 24, 32, 48, 72, 88},
        {8, 8, 8, 8, 8, 8, 8, 8, 16, 16, 16, 16, 32, 32, 32, 48, 48, 64, 96, 144, 176}
    };

    /**
     * RFC 6716 Tables 60 to 63, in the RFC's own layout: outer index selects the
     * table (non-transient tf_select 0 and 1, then transient tf_select 0 and 1),
     * middle index is LM, inner index is the decoded tf_change flag.
     */
    private static final int[][][] TABLES_60_TO_63 = {
        {{0, -1}, {0, -1}, {0, -2}, {0, -2}},
        {{0, -1}, {0, -2}, {0, -3}, {0, -3}},
        {{0, -1}, {1,  0}, {2,  0}, {3,  0}},
        {{0, -1}, {1, -1}, {1, -1}, {1, -1}}
    };

    @Test
    void bandBoundariesRunFromZeroToTheTopOfTheCodedSpectrum() {
        for (int frameSize : FRAME_SIZES) {
            CeltMode mode = CeltMode.forFrameSize(frameSize);
            int[] edges = mode.bandBoundaries();

            assertEquals(mode.bandCount() + 1, edges.length,
                    frameSize + ": one more edge than bands");
            assertEquals(0, edges[0], frameSize + ": the first band starts at bin 0");
            for (int i = 1; i < edges.length; i++) {
                assertTrue(edges[i] > edges[i - 1],
                        frameSize + ": edge " + i + " (" + edges[i] + ") does not exceed edge "
                        + (i - 1) + " (" + edges[i - 1] + ")");
            }

            // Five sixths, not all of it. The MDCT spans 0 to 24 kHz and CELT codes
            // nothing above 20 kHz, so the top of the transform has no band. A
            // decoder that treated the last edge as the end of the frame would read
            // a sixth of the spectrum out of uninitialised memory.
            assertEquals(frameSize * 5 / 6, edges[edges.length - 1],
                    frameSize + ": the top band edge should be the 20 kHz bin");
            assertTrue(mode.codedBins() < mode.frameSize(),
                    frameSize + ": the band layout must not cover the whole transform");
        }
    }

    @Test
    void everyFrameSizeHasTheTwentyOneBandsTheRfcStates() {
        for (int frameSize : FRAME_SIZES) {
            CeltMode mode = CeltMode.forFrameSize(frameSize);
            assertEquals(21, mode.bandCount(), frameSize + ": RFC 6716 section 4.3 says 21 bands");
        }
    }

    @Test
    void bandWidthsAreTheBinCountsTable55Prints() {
        for (int lm = 0; lm < FRAME_SIZES.length; lm++) {
            CeltMode mode = CeltMode.forFrameSize(FRAME_SIZES[lm]);
            for (int band = 0; band < mode.bandCount(); band++) {
                assertEquals(TABLE_55_BINS[lm][band], mode.bandWidth(band),
                        FRAME_SIZES[lm] + " band " + band + ": width disagrees with Table 55");
                assertEquals(mode.bandEnd(band) - mode.bandStart(band), mode.bandWidth(band),
                        FRAME_SIZES[lm] + " band " + band + ": width is not end minus start");

                // Table 55's printed bin counts, held separately from the band
                // edges the mode is built out of. The two are the same layout
                // written down twice and have to agree; if they do not, one of the
                // two transcriptions is wrong and only this comparison would say so.
                assertEquals(mode.bandWidth(band), CeltTables.binsPerBand(lm, band),
                        FRAME_SIZES[lm] + " band " + band + ": the Table 55 bin count and the"
                        + " band edges disagree");
            }
            // Table 55 states each column twice over: as its own numbers, and
            // implicitly as the 2.5 ms column scaled by the frame size. If the two
            // disagree the transcription of one of them is wrong.
            for (int band = 0; band < mode.bandCount(); band++) {
                assertEquals(TABLE_55_BINS[0][band] << lm, TABLE_55_BINS[lm][band],
                        "Table 55 column for LM " + lm + " band " + band
                        + " is not the 2.5 ms column shifted");
            }
        }
    }

    @Test
    void bandsCoverZeroToTwentyKilohertzOnTheCriticalBandScale() {
        for (int frameSize : FRAME_SIZES) {
            CeltMode mode = CeltMode.forFrameSize(frameSize);

            assertEquals(0, mode.bandStartHz(0), frameSize + ": band 0 starts at DC");
            assertEquals(20_000, mode.bandEndHz(mode.bandCount() - 1),
                    frameSize + ": the top band must end at the 20 kHz limit RFC 6716"
                    + " section 2.1.3 sets");

            double previousCentre = -1;
            int previousWidthHz = 0;
            for (int band = 0; band < mode.bandCount(); band++) {
                assertEquals(TABLE_55_EDGE_HZ[band], mode.bandStartHz(band),
                        frameSize + " band " + band + ": start frequency disagrees with Table 55");
                assertEquals(TABLE_55_EDGE_HZ[band + 1], mode.bandEndHz(band),
                        frameSize + " band " + band + ": stop frequency disagrees with Table 55");

                // Table 55 gives the layout twice over, as bins and as frequencies.
                // The frequency column is stored on its own and has to come back out
                // of the bin arithmetic unchanged.
                assertEquals(CeltTables.bandEdgeHz(band), mode.bandStartHz(band),
                        frameSize + " band " + band + ": the stored edge frequency and the one"
                        + " derived from the bins disagree");

                double centre = mode.bandCentreHz(band);
                assertTrue(centre > previousCentre,
                        frameSize + " band " + band + ": centre " + centre
                        + " Hz is not above the previous band's " + previousCentre);
                previousCentre = centre;

                // The Bark scale never narrows as frequency rises. A band that is
                // narrower than the one below it means two edges have been swapped,
                // which no monotonicity check on the edges alone would catch.
                int widthHz = mode.bandEndHz(band) - mode.bandStartHz(band);
                assertTrue(widthHz >= previousWidthHz,
                        frameSize + " band " + band + " is " + widthHz + " Hz wide, narrower than"
                        + " the band below it at " + previousWidthHz + " Hz");
                previousWidthHz = widthHz;
            }
            assertTrue(previousCentre > 17_000 && previousCentre < 20_000,
                    frameSize + ": the top band's centre should sit near 18 kHz, got "
                    + previousCentre);
        }
    }

    @Test
    void bandEdgesAreTheSameFrequenciesAtEveryFrameSize() {
        CeltMode shortest = CeltMode.forFrameSize(120);
        for (int frameSize : FRAME_SIZES) {
            CeltMode mode = CeltMode.forFrameSize(frameSize);
            for (int band = 0; band < mode.bandCount(); band++) {
                assertEquals(shortest.bandStartHz(band), mode.bandStartHz(band),
                        frameSize + " band " + band + ": the band layout must not move with the"
                        + " frame size");
                assertEquals(shortest.bandWidth(band) << mode.lm(), mode.bandWidth(band),
                        frameSize + " band " + band + ": width should be the 2.5 ms width shifted"
                        + " by LM");
            }
        }
    }

    @Test
    void allocationTableHasTheDimensionsTable57Gives() {
        CeltMode mode = CeltMode.forFrameSize(960);
        assertEquals(11, mode.allocationRows(), "Table 57 has eleven quality columns");
        for (int row = 0; row < mode.allocationRows(); row++) {
            assertEquals(21, mode.allocationRow(row).length,
                    "row " + row + " must cover all 21 bands");
        }
    }

    @Test
    void allocationRisesWithQualityAndFallsWithFrequency() {
        CeltMode mode = CeltMode.forFrameSize(960);

        // Down a column of Table 57: a higher band never gets more bits per bin
        // than a lower one at the same quality. This is the shape of the table and
        // a single mistyped digit almost always breaks it.
        for (int row = 0; row < mode.allocationRows(); row++) {
            for (int band = 1; band < mode.bandCount(); band++) {
                assertTrue(mode.allocation(row, band) <= mode.allocation(row, band - 1),
                        "row " + row + ": band " + band + " gets " + mode.allocation(row, band)
                        + "/32 bits per bin, more than band " + (band - 1) + " at "
                        + mode.allocation(row, band - 1));
            }
        }

        // Across a row: raising the quality parameter never takes bits away from a
        // band. If it did, the allocator's bisection over q would not be searching a
        // monotone function and would settle on the wrong row.
        for (int band = 0; band < mode.bandCount(); band++) {
            for (int row = 1; row < mode.allocationRows(); row++) {
                assertTrue(mode.allocation(row, band) >= mode.allocation(row - 1, band),
                        "band " + band + ": quality " + row + " allocates "
                        + mode.allocation(row, band) + "/32 bits per bin, fewer than quality "
                        + (row - 1) + " at " + mode.allocation(row - 1, band));
            }
        }

        // The endpoints the interpolation relies on: nothing at all at the bottom,
        // and something everywhere at the top.
        for (int band = 0; band < mode.bandCount(); band++) {
            assertEquals(0, mode.allocation(0, band), "quality 0 must allocate nothing");
            assertTrue(mode.allocation(mode.allocationRows() - 1, band) > 0,
                    "quality 10 must allocate something to band " + band);
        }
    }

    @Test
    void staticAllocationFollowsTheFormulaSection433States() {
        for (int frameSize : FRAME_SIZES) {
            CeltMode mode = CeltMode.forFrameSize(frameSize);
            int lm = mode.lm();
            for (int channels = 1; channels <= 2; channels++) {
                for (int row = 0; row < mode.allocationRows(); row++) {
                    for (int band = 0; band < mode.bandCount(); band++) {
                        // RFC 6716 section 4.3.3: channels*N*alloc[band][q]<<LM>>2,
                        // with N the width in the shortest frame for this mode.
                        int shortWidth = TABLE_55_BINS[0][band];
                        int expected =
                                (channels * shortWidth * mode.allocation(row, band) << lm) >> 2;
                        assertEquals(expected, mode.staticBits(row, band, channels),
                                frameSize + " ch" + channels + " row " + row + " band " + band
                                + ": static allocation disagrees with the RFC formula");
                    }
                }
            }
            // The reference multiplies by the channel count and only then divides by
            // four, so a stereo band gets twice the mono budget and sometimes one
            // eighth-bit more, never less. An implementation that divided first would
            // round the odd eighth away on every narrow band and drift a little
            // quieter than the encoder expected across the whole frame.
            for (int row = 0; row < mode.allocationRows(); row++) {
                for (int band = 0; band < mode.bandCount(); band++) {
                    int mono = mode.staticBits(row, band, 1);
                    int stereo = mode.staticBits(row, band, 2);
                    assertTrue(stereo == 2 * mono || stereo == 2 * mono + 1,
                            frameSize + " row " + row + " band " + band + ": stereo budget "
                            + stereo + " is not twice the mono " + mono + " to within the"
                            + " eighth-bit the division loses");
                }
            }
        }
    }

    @Test
    void allocationCeilingsFitSixteenBitsAndNotEight() {
        int[] caps = new int[21];
        int largest = 0;
        int smallest = Integer.MAX_VALUE;
        for (int frameSize : FRAME_SIZES) {
            CeltMode mode = CeltMode.forFrameSize(frameSize);
            for (int channels = 1; channels <= 2; channels++) {
                mode.computeCaps(channels, caps);
                for (int band = 0; band < mode.bandCount(); band++) {
                    assertTrue(caps[band] > 0,
                            frameSize + " ch" + channels + " band " + band
                            + ": a ceiling of " + caps[band] + " would forbid coding the band");
                    largest = Math.max(largest, caps[band]);
                    smallest = Math.min(smallest, caps[band]);

                    // A ceiling below the band's own minimum allocation would make
                    // the allocator ask for bits it is then not allowed to spend.
                    int floorBits = Math.max(channels << CeltMode.BIT_RES,
                            (3 * mode.bandWidth(band) << CeltMode.BIT_RES) >> 4);
                    assertTrue(caps[band] >= floorBits,
                            frameSize + " ch" + channels + " band " + band + ": ceiling "
                            + caps[band] + " is below the band minimum " + floorBits);
                }
            }
        }
        // RFC 6716 section 4.3.3: "The elements fit in signed 16-bit integers but do
        // not fit in 8 bits."
        assertTrue(largest <= Short.MAX_VALUE,
                "largest ceiling " + largest + " does not fit in a signed 16-bit integer");
        assertTrue(largest > 255,
                "largest ceiling " + largest + " would have fit in 8 bits, so the table is wrong");
        assertTrue(smallest > 0, "smallest ceiling " + smallest + " must be positive");
    }

    @Test
    void ceilingsGrowWithFrameSizeAndChannels() {
        int[] mono = new int[21];
        int[] stereo = new int[21];
        for (int frameSize : FRAME_SIZES) {
            CeltMode mode = CeltMode.forFrameSize(frameSize);
            mode.computeCaps(1, mono);
            mode.computeCaps(2, stereo);
            for (int band = 0; band < mode.bandCount(); band++) {
                assertTrue(stereo[band] > mono[band],
                        frameSize + " band " + band + ": a stereo band cannot have a ceiling of "
                        + stereo[band] + " when mono is allowed " + mono[band]);
            }
        }
        int[] shortMono = new int[21];
        int[] longMono = new int[21];
        CeltMode shortest = CeltMode.forFrameSize(120);
        CeltMode longest = CeltMode.forFrameSize(960);
        shortest.computeCaps(1, shortMono);
        longest.computeCaps(1, longMono);
        for (int band = 0; band < shortest.bandCount(); band++) {
            assertTrue(longMono[band] > shortMono[band],
                    "band " + band + ": a 20 ms band holds eight times the bins of a 2.5 ms band"
                    + " and must have the larger ceiling, got " + longMono[band] + " against "
                    + shortMono[band]);
        }
    }

    @Test
    void logNIsTheConservativeLogarithmOfTheBandWidth() {
        for (int frameSize : FRAME_SIZES) {
            CeltMode mode = CeltMode.forFrameSize(frameSize);
            for (int band = 0; band < mode.bandCount(); band++) {
                int width = mode.bandWidth(band);
                assertEquals(conservativeLog2Eighths(width), mode.logN(band),
                        frameSize + " band " + band + ": logN for a width of " + width
                        + " is not the rounded-up log2 the allocator needs");
            }
            // The shift identity the mode relies on to store one table for four frame
            // sizes: rounding cannot change when a width is shifted by whole bits.
            for (int band = 0; band < mode.bandCount(); band++) {
                assertEquals(CeltMode.forFrameSize(120).logN(band) + 8 * mode.lm(),
                        mode.logN(band),
                        frameSize + " band " + band + ": logN did not scale by 8 per LM");
            }
        }
    }

    @Test
    void intensityReservationIsTheConservativeLogarithmOfTheChoiceCount() {
        // The intensity parameter picks one of codedBands+1 values, so its cost is
        // the rounded-up log2 of that count in eighths of a bit. Under-reserving here
        // lets the intensity symbol run off the end of the frame.
        for (int codedBands = 0; codedBands <= 21; codedBands++) {
            assertEquals(conservativeLog2Eighths(codedBands + 1),
                    CeltMode.intensityReservation(codedBands),
                    "reservation for " + codedBands + " coded bands");
        }
        assertEquals(0, CeltMode.intensityReservation(0),
                "with a single choice there is nothing to code");
    }

    @Test
    void timeFrequencyAdjustmentsMatchTables60To63() {
        for (int table = 0; table < TABLES_60_TO_63.length; table++) {
            boolean isTransient = table >= 2;
            int tfSelect = table % 2;
            for (int lm = 0; lm < 4; lm++) {
                CeltMode mode = CeltMode.forLm(lm);
                for (int tfChange = 0; tfChange < 2; tfChange++) {
                    assertEquals(TABLES_60_TO_63[table][lm][tfChange],
                            mode.tfAdjustment(isTransient, tfSelect, tfChange),
                            "LM " + lm + (isTransient ? " transient" : " steady")
                            + " tf_select " + tfSelect + " tf_change " + tfChange);
                }
            }
        }
        // A steady frame can only ever be moved towards more time resolution, and a
        // transient one towards more frequency resolution. Getting the sign wrong
        // applies the Hadamard transform along the wrong axis, which smears a
        // transient across the whole frame.
        for (int lm = 0; lm < 4; lm++) {
            CeltMode mode = CeltMode.forLm(lm);
            for (int tfSelect = 0; tfSelect < 2; tfSelect++) {
                for (int tfChange = 0; tfChange < 2; tfChange++) {
                    assertTrue(mode.tfAdjustment(false, tfSelect, tfChange) <= 0,
                            "LM " + lm + ": a steady frame must not gain frequency resolution");
                }
            }
        }
    }

    @Test
    void trimAndSpreadInverseCdfsAgreeWithThePdfsTheRfcPrints() {
        // RFC 6716 states these distributions as PDFs in Tables 56 and 58, and the
        // reference implementation in Appendix A states them again as the inverse
        // CDFs the range decoder consumes. They must be the same distribution.
        short[] trim = CeltTables.copyTrimIcdf();
        assertEquals(11, trim.length, "eleven trim values, 0 to 10");
        int cumulative = 0;
        for (int i = 0; i < trim.length; i++) {
            cumulative += CeltTables.trimProbability(i);
            assertEquals((1 << CeltTables.TRIM_ICDF_FTB) - cumulative, trim[i],
                    "trim inverse CDF entry " + i);
        }
        assertEquals(1 << CeltTables.TRIM_ICDF_FTB, cumulative, "the trim PDF must sum to 128");
        assertEquals(0, trim[trim.length - 1], "the last inverse CDF entry must be zero");

        short[] spread = CeltTables.copySpreadIcdf();
        assertEquals(4, spread.length, "four spread values, 0 to 3");
        cumulative = 0;
        for (int i = 0; i < spread.length; i++) {
            cumulative += CeltTables.spreadProbability(i);
            assertEquals((1 << CeltTables.SPREAD_ICDF_FTB) - cumulative, spread[i],
                    "spread inverse CDF entry " + i);
        }
        assertEquals(1 << CeltTables.SPREAD_ICDF_FTB, cumulative, "the spread PDF must sum to 32");
        assertEquals(0, spread[spread.length - 1], "the last inverse CDF entry must be zero");
    }

    @Test
    void spreadingFactorsShrinkTheRotationAsSpreadingGrows() {
        // RFC 6716 Table 59. The rotation gain is N/(N+f_r*K), so a smaller factor is
        // a larger gain and a stronger rotation; spread 0 is the no-rotation case and
        // is reported as zero rather than as any usable factor.
        assertEquals(0, CeltMode.spreadRotationFactor(0), "spread 0 means no rotation at all");
        assertEquals(15, CeltMode.spreadRotationFactor(1));
        assertEquals(10, CeltMode.spreadRotationFactor(2));
        assertEquals(5, CeltMode.spreadRotationFactor(3));
        for (int spread = 2; spread <= 3; spread++) {
            assertTrue(CeltMode.spreadRotationFactor(spread)
                            < CeltMode.spreadRotationFactor(spread - 1),
                    "spread " + spread + " must rotate harder than " + (spread - 1));
        }
    }

    @Test
    void codedBandsEndOnTheCutoffFrequencyOfTheSignalledBandwidth() {
        CeltMode mode = CeltMode.forFrameSize(960);

        assertEquals(13, CeltMode.codingEndBand(OpusPacket.Bandwidth.NARROWBAND));
        assertEquals(17, CeltMode.codingEndBand(OpusPacket.Bandwidth.WIDEBAND));
        assertEquals(19, CeltMode.codingEndBand(OpusPacket.Bandwidth.SUPERWIDEBAND));
        assertEquals(21, CeltMode.codingEndBand(OpusPacket.Bandwidth.FULLBAND));

        // The end band and the bandwidth's cutoff in RFC 6716 Table 1 are two
        // statements of the same limit, and they have to land on the same hertz. If
        // they do not, either the band layout or the switch is wrong, and the symptom
        // is a decoder that codes bands the encoder did not, which desynchronises the
        // range decoder for the rest of the frame.
        for (OpusPacket.Bandwidth bandwidth : new OpusPacket.Bandwidth[] {
                OpusPacket.Bandwidth.NARROWBAND, OpusPacket.Bandwidth.WIDEBAND,
                OpusPacket.Bandwidth.SUPERWIDEBAND, OpusPacket.Bandwidth.FULLBAND}) {
            int end = CeltMode.codingEndBand(bandwidth);
            int cutoffHz = end == mode.bandCount()
                    ? mode.bandEndHz(end - 1)
                    : mode.bandStartHz(end);
            assertEquals(bandwidth.cutoffHz(), cutoffHz,
                    bandwidth + " stops at band " + end + ", which is " + cutoffHz
                    + " Hz, not its " + bandwidth.cutoffHz() + " Hz cutoff");
        }

        // Medium-band is the documented exception: there is no band edge at 6 kHz, so
        // the reference rounds up to the wideband answer.
        assertEquals(17, CeltMode.codingEndBand(OpusPacket.Bandwidth.MEDIUMBAND));
        assertTrue(mode.bandStartHz(17) > OpusPacket.Bandwidth.MEDIUMBAND.cutoffHz(),
                "the medium-band rounding should only ever code more than asked, never less");
    }

    @Test
    void hybridFramesStartWhereSilkStops() {
        CeltMode mode = CeltMode.forFrameSize(960);
        assertEquals(17, CeltMode.codingStartBand(OpusPacket.Mode.HYBRID));
        assertEquals(0, CeltMode.codingStartBand(OpusPacket.Mode.CELT));
        assertEquals(0, CeltMode.codingStartBand(OpusPacket.Mode.SILK));
        assertEquals(8_000, mode.bandStartHz(CeltMode.codingStartBand(OpusPacket.Mode.HYBRID)),
                "RFC 6716 section 4.3: hybrid leaves the first 17 bands, up to 8 kHz, to SILK");
    }

    @Test
    void frameSizeConstantsAreTheOnesTheTransformNeeds() {
        for (int frameSize : FRAME_SIZES) {
            CeltMode mode = CeltMode.forFrameSize(frameSize);
            assertEquals(frameSize, mode.frameSize());
            assertEquals(frameSize / 120, mode.shortBlocks(),
                    frameSize + ": a transient frame splits into 2.5 ms blocks");
            assertEquals(frameSize, mode.shortBlocks() * mode.shortMdctSize(),
                    frameSize + ": the short blocks must tile the frame exactly");
            assertEquals(frameSize / 120, 1 << mode.lm(), frameSize + ": LM is log2 of that");
            assertEquals(2500 << mode.lm(), mode.frameMicros());

            // The overlap does not scale. CELT keeps its 2.5 ms algorithmic delay at
            // every frame size by zero-padding the window instead of widening it, so
            // an implementation that ties the overlap to the frame size will overlap
            // the wrong number of samples and click at every frame boundary.
            assertEquals(120, mode.overlap(),
                    frameSize + ": overlap is 120 samples whatever the frame size");
        }
    }

    @Test
    void modesAreSharedAndTheirArraysCannotBeCorruptedByCallers() {
        // The decode path calls this per frame, so it must not allocate.
        assertSame(CeltMode.forFrameSize(960), CeltMode.forFrameSize(960));
        assertSame(CeltMode.forFrameSize(240), CeltMode.forLm(1));

        CeltMode mode = CeltMode.forFrameSize(960);
        int[] edges = mode.bandBoundaries();
        assertNotSame(edges, mode.bandBoundaries(), "the edges must be copied out, not shared");
        edges[3] = -1;
        assertEquals(24, mode.bandStart(3), "a caller's scribble must not reach the mode");

        int[] row = mode.allocationRow(5);
        row[0] = -1;
        assertEquals(134, mode.allocation(5, 0), "a caller's scribble must not reach the table");
    }

    @Test
    void badArgumentsGetAnExplanationRatherThanAnArrayIndexFault() {
        CeltMode mode = CeltMode.forFrameSize(960);

        for (int bad : new int[] {0, 119, 121, 481, 1920, -960}) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> CeltMode.forFrameSize(bad));
            assertTrue(thrown.getMessage().contains(String.valueOf(bad)),
                    "the message should name the frame size that was rejected: "
                    + thrown.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> CeltMode.forLm(4));
        assertThrows(IllegalArgumentException.class, () -> CeltMode.forLm(-1));

        for (int bad : new int[] {-1, 21, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            assertBandRejected(() -> mode.bandStart(bad));
            assertBandRejected(() -> mode.bandEnd(bad));
            assertBandRejected(() -> mode.bandWidth(bad));
            assertBandRejected(() -> mode.bandStartHz(bad));
            assertBandRejected(() -> mode.bandCentreHz(bad));
            assertBandRejected(() -> mode.logN(bad));
            assertBandRejected(() -> mode.allocation(5, bad));
        }
        assertThrows(IndexOutOfBoundsException.class, () -> mode.allocation(11, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> mode.allocationRow(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> CeltMode.intensityReservation(24));
        assertThrows(IndexOutOfBoundsException.class, () -> CeltMode.spreadRotationFactor(4));

        assertThrows(IllegalArgumentException.class, () -> mode.staticBits(5, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> mode.staticBits(5, 0, 3));
        assertThrows(IllegalArgumentException.class, () -> mode.computeCaps(1, new int[20]));
    }

    @Test
    void theLogarithmAndExponentialApproximationsTrackTheRealFunctions() {
        // These two polynomials replace libm inside CELT. A mistyped coefficient does
        // not throw; it biases every band energy in the frame by a fraction of a
        // decibel, which sounds like a codec that is quietly a little worse than it
        // should be.
        double worstLog = 0;
        for (double x = 1e-6; x < 1e6; x *= 1.037) {
            float approx = CeltTables.log2Approx((float) x);
            double exact = StrictMath.log(x) / StrictMath.log(2);
            worstLog = Math.max(worstLog, Math.abs(approx - exact));
        }
        // Bounded on both sides. The upper bound catches a mistyped coefficient; the
        // lower one catches the opposite mistake, an implementation that quietly
        // called a correctly rounded logarithm instead of the cubic and so would not
        // reproduce the reference's arithmetic.
        assertTrue(worstLog < 1.2e-3 && worstLog > 5e-4,
                "log2 approximation is off by " + worstLog + " bits; the reference's cubic is"
                + " off by about 8.8e-4 over this range");

        double worstExp = 0;
        for (double x = -40; x <= 40; x += 0.017) {
            float approx = CeltTables.exp2Approx((float) x);
            double exact = StrictMath.pow(2, x);
            worstExp = Math.max(worstExp, Math.abs(approx - exact) / exact);
        }
        assertTrue(worstExp < 1.2e-4 && worstExp > 4e-5,
                "exp2 approximation is off by a relative " + worstExp + "; the reference's cubic"
                + " is off by about 7.6e-5");

        // The libm path is the one the reference float build actually takes, so it
        // has to agree with the polynomial to the polynomial's own accuracy.
        for (double x = 1e-3; x < 1e3; x *= 1.13) {
            assertEquals(CeltTables.log2Approx((float) x), CeltTables.log2((float) x), 1.2e-3f,
                    "the two log2 paths disagree at " + x);
        }
        for (double x = -20; x <= 20; x += 0.37) {
            float exact = CeltTables.exp2((float) x);
            assertEquals(exact, CeltTables.exp2Approx((float) x), Math.abs(exact) * 1.5e-4f,
                    "the two exp2 paths disagree at " + x);
        }

        // Round trip: whatever else is wrong, these have to be inverses.
        for (double x = 1e-4; x < 1e4; x *= 1.29) {
            assertEquals(x, CeltTables.exp2(CeltTables.log2((float) x)), x * 1e-5,
                    "log2 and exp2 are not inverses at " + x);
        }
        assertEquals(0.0f, CeltTables.exp2Approx(-100f),
                "the reference returns exactly zero below -50");
    }

    // ----------------------------------------------------------------------
    // Against RFC 6716 itself. These skip without a copy of the RFC, and they are
    // the only checks that can see a wrong digit that happens to preserve every
    // property the tables are supposed to have.
    // ----------------------------------------------------------------------

    @Test
    void bandLayoutMatchesTable55InTheRfcText() {
        assumeTrue(RfcSource.path() != null, RfcSource.skipReason());

        int[][] bins = RfcSource.table55Bins();
        int[] edgeHz = RfcSource.table55EdgeHz();
        for (int lm = 0; lm < 4; lm++) {
            CeltMode mode = CeltMode.forLm(lm);
            assertEquals(bins[lm].length, mode.bandCount(),
                    "RFC Table 55 lists " + bins[lm].length + " bands");
            for (int band = 0; band < mode.bandCount(); band++) {
                assertEquals(bins[lm][band], mode.bandWidth(band),
                        "LM " + lm + " band " + band + ": Table 55 in the RFC says "
                        + bins[lm][band] + " bins");
                assertEquals(edgeHz[band], mode.bandStartHz(band),
                        "LM " + lm + " band " + band + ": Table 55 starts it at "
                        + edgeHz[band] + " Hz");
            }
            assertEquals(edgeHz[edgeHz.length - 1], mode.bandEndHz(mode.bandCount() - 1),
                    "LM " + lm + ": Table 55 stops the top band at "
                    + edgeHz[edgeHz.length - 1] + " Hz");
        }
    }

    @Test
    void staticAllocationMatchesTable57InTheRfcText() {
        assumeTrue(RfcSource.path() != null, RfcSource.skipReason());

        int[][] table57 = RfcSource.table57();
        CeltMode mode = CeltMode.forFrameSize(960);
        assertEquals(table57.length, mode.allocationRows(), "quality columns in Table 57");
        for (int row = 0; row < table57.length; row++) {
            assertEquals(table57[row].length, mode.bandCount(), "bands in Table 57 row " + row);
            for (int band = 0; band < table57[row].length; band++) {
                assertEquals(table57[row][band], mode.allocation(row, band),
                        "Table 57 in the RFC gives quality " + row + " band " + band + " as "
                        + table57[row][band] + "/32 bits per bin");
            }
        }
    }

    @Test
    void trimSpreadAndTimeFrequencyTablesMatchTheRfcText() {
        assumeTrue(RfcSource.path() != null, RfcSource.skipReason());

        int[] trim = RfcSource.table58TrimPdf();
        for (int i = 0; i < trim.length; i++) {
            assertEquals(trim[i], CeltTables.trimProbability(i), "Table 58 entry " + i);
        }
        int[] spread = RfcSource.table56SpreadPdf();
        for (int i = 0; i < spread.length; i++) {
            assertEquals(spread[i], CeltTables.spreadProbability(i), "Table 56 spread entry " + i);
        }

        int[] factors = RfcSource.table59SpreadFactors();
        assertEquals(3, factors.length, "Table 59 prints a number for spreads 1 to 3");
        for (int spreadValue = 1; spreadValue <= 3; spreadValue++) {
            assertEquals(factors[spreadValue - 1], CeltMode.spreadRotationFactor(spreadValue),
                    "Table 59 f_r for spread " + spreadValue);
        }

        int[][][] tf = RfcSource.tables60To63();
        for (int table = 0; table < 4; table++) {
            boolean isTransient = table >= 2;
            int tfSelect = table % 2;
            for (int lm = 0; lm < 4; lm++) {
                for (int change = 0; change < 2; change++) {
                    assertEquals(tf[table][lm][change],
                            CeltMode.forLm(lm).tfAdjustment(isTransient, tfSelect, change),
                            "RFC Table " + (60 + table) + ", LM " + lm + ", tf_change " + change);
                }
            }
        }
    }

    @Test
    void tablesTheRfcOnlyNamesMatchItsNormativeReferenceSource() {
        assumeTrue(RfcSource.path() != null, RfcSource.skipReason());

        // The allocation ceilings, the band log table and the intensity cost table
        // are never printed in the RFC's prose; section 4.3.3 names the files they
        // live in and tells implementations to reuse them. Those files are in
        // Appendix A, whose SHA-1 RfcSource verifies before reading a byte of it.
        int[] caps = RfcSource.referenceArray("celt/static_modes_float.h", "cache_caps50");
        assertEquals(8 * 21, caps.length, "cache_caps50 covers four LM by two channel counts");
        for (int lm = 0; lm < 4; lm++) {
            for (int channels = 1; channels <= 2; channels++) {
                for (int band = 0; band < 21; band++) {
                    assertEquals(caps[21 * (2 * lm + channels - 1) + band],
                            CeltTables.cap(lm, channels, band),
                            "cache_caps50 at LM " + lm + " ch" + channels + " band " + band);
                }
            }
        }

        int[] logN = RfcSource.referenceArray("celt/static_modes_float.h", "logN400");
        for (int band = 0; band < logN.length; band++) {
            assertEquals(logN[band], CeltTables.logN(band), "logN400 entry " + band);
        }

        int[] log2Frac = RfcSource.referenceArray("celt/rate.c", "LOG2_FRAC_TABLE");
        for (int i = 0; i < log2Frac.length; i++) {
            assertEquals(log2Frac[i], CeltMode.intensityReservation(i),
                    "LOG2_FRAC_TABLE entry " + i);
        }

        int[] eband = RfcSource.referenceArray("celt/modes.c", "eband5ms");
        CeltMode shortest = CeltMode.forFrameSize(120);
        for (int edge = 0; edge < eband.length; edge++) {
            int actual = edge == shortest.bandCount()
                    ? shortest.bandEnd(edge - 1) : shortest.bandStart(edge);
            assertEquals(eband[edge], actual, "eband5ms entry " + edge);
        }

        int[] allocation = RfcSource.referenceArray("celt/modes.c", "band_allocation");
        CeltMode mode = CeltMode.forFrameSize(960);
        for (int row = 0; row < mode.allocationRows(); row++) {
            for (int band = 0; band < mode.bandCount(); band++) {
                assertEquals(allocation[row * 21 + band], mode.allocation(row, band),
                        "band_allocation row " + row + " band " + band);
            }
        }

        assertArrayEquals(RfcSource.referenceArray("celt/celt.c", "trim_icdf"),
                widen(CeltTables.copyTrimIcdf()), "trim_icdf");
        assertArrayEquals(RfcSource.referenceArray("celt/celt.c", "spread_icdf"),
                widen(CeltTables.copySpreadIcdf()), "spread_icdf");

        int[] tfSelect = RfcSource.referenceArray("celt/celt.c", "tf_select_table");
        for (int lm = 0; lm < 4; lm++) {
            for (int i = 0; i < 8; i++) {
                assertEquals(tfSelect[lm * 8 + i],
                        CeltMode.forLm(lm).tfAdjustment(i >= 4, (i / 2) % 2, i % 2),
                        "tf_select_table[" + lm + "][" + i + "]");
            }
        }
    }

    @Test
    void logarithmPolynomialsMatchTheNormativeReferenceSource() {
        assumeTrue(RfcSource.path() != null, RfcSource.skipReason());

        // A coefficient wrong in the seventh decimal changes the approximation by
        // less than its own fit error, so no accuracy bound can catch it. Only the
        // source it was copied from can.
        float[] log2Poly = RfcSource.referenceFloats("celt/mathops.h", "frac = -0.41445418f");
        assertEquals(4, log2Poly.length, "celt_log2 is a cubic");
        for (float mantissa = 1.0f; mantissa < 2.0f; mantissa += 1f / 64) {
            assertEquals(1 + f(mantissa - 1.5f, log2Poly), CeltTables.log2Approx(mantissa), 0.0f,
                    "celt_log2 polynomial disagrees at a mantissa of " + mantissa);
        }

        float[] exp2Poly = RfcSource.referenceFloats("celt/mathops.h", "res.f = 0.99992522f");
        assertEquals(4, exp2Poly.length, "celt_exp2 is a cubic");
        for (float x = 0; x < 1.0f; x += 1f / 64) {
            assertEquals(f(x, exp2Poly), CeltTables.exp2Approx(x), 0.0f,
                    "celt_exp2 polynomial disagrees at " + x);
        }
    }

    @Test
    void ceilingsMatchTheOnesRebuiltFromThePulseCache() {
        assumeTrue(RfcSource.path() != null, RfcSource.skipReason());

        // Everything else about the ceilings is checked against cache_caps50 itself,
        // which leaves the two constants in the sentence that scales it -- "cache.caps
        // + 64 ... divide the result by 4" -- resting on nobody. Both survive the rest
        // of this class: halving every ceiling, or adding 65, still leaves them
        // positive, still above each band's minimum, still monotone in channels and
        // frame size, and still inside sixteen bits. A ceiling that is half what it
        // should be only bites on loud frames at high rates, where it quietly takes
        // the top of a band's resolution away and nothing reports it.
        //
        // So this rebuilds the ceilings from the other end. RFC 6716 section 4.3.3
        // says "the procedure for generating this table is included in rate.c as part
        // of compute_pulse_cache()", which is a second normative statement of the
        // whole vector and does not read cache_caps50 at all: every number below is
        // built out of the pulse cache and comes back through the scaling.
        int[] eband = RfcSource.referenceArray("celt/modes.c", "eband5ms");
        int[] logN = RfcSource.referenceArray("celt/static_modes_float.h", "logN400");
        int[] cacheIndex = RfcSource.referenceArray("celt/static_modes_float.h", "cache_index50");
        int[] cacheBits = RfcSource.referenceArray("celt/static_modes_float.h", "cache_bits50");
        assertEquals(22, eband.length, "eband5ms is one longer than the band count");
        assertEquals(21, logN.length, "logN400 has one entry per band");
        assertEquals(21 * 5, cacheIndex.length, "cache_index50 covers LM -1 through 3");
        assertEquals(392, cacheBits.length, "cache_bits50 is the mode's whole pulse cache");

        int bitres = referenceDefine("celt/entcode.h", "BITRES");
        int maxFineBits = referenceDefine("celt/rate.h", "MAX_FINE_BITS");
        int fineOffset = referenceDefine("celt/rate.h", "FINE_OFFSET");
        int qthetaOffset = referenceDefine("celt/rate.h", "QTHETA_OFFSET");
        int qthetaTwoPhase = referenceDefine("celt/rate.h", "QTHETA_OFFSET_TWOPHASE");
        assertEquals(CeltMode.BIT_RES, bitres, "BITRES is the 1/8 bit unit both sides work in");

        int[] actual = new int[21];
        int compared = 0;
        int bindingBands = 0;
        for (int lm = 0; lm < 4; lm++) {
            CeltMode mode = CeltMode.forLm(lm);
            for (int channels = 1; channels <= 2; channels++) {
                mode.computeCaps(channels, actual);
                for (int band = 0; band < 21; band++) {
                    int width = eband[band + 1] - eband[band];
                    int maxBits;
                    if (width << lm == 1) {
                        maxBits = channels * (1 + maxFineBits) << bitres;
                    } else {
                        int splitWidth = width;
                        int splitLm = 0;
                        if (splitWidth > 2) {
                            splitWidth >>= 1;
                            splitLm--;
                        } else if (splitWidth <= 1) {
                            splitLm = Math.min(lm, 1);
                            splitWidth <<= splitLm;
                        }
                        int entry = cacheIndex[(splitLm + 1) * 21 + band];
                        maxBits = cacheBits[entry + cacheBits[entry]] + 1;
                        int n = splitWidth;
                        for (int k = 0; k < lm - splitLm; k++) {
                            maxBits <<= 1;
                            int offset = ((logN[band] + ((splitLm + k) << bitres)) >> 1)
                                    - qthetaOffset;
                            int num = 459 * ((2 * n - 1) * offset + maxBits);
                            int den = ((2 * n - 1) << 9) - 459;
                            maxBits += Math.min((num + (den >> 1)) / den, 57);
                            n <<= 1;
                        }
                        if (channels == 2) {
                            maxBits <<= 1;
                            int offset = ((logN[band] + (lm << bitres)) >> 1)
                                    - (n == 2 ? qthetaTwoPhase : qthetaOffset);
                            int ndof = 2 * n - 1 - (n == 2 ? 1 : 0);
                            int weight = n == 2 ? 512 : 487;
                            int num = weight * (maxBits + ndof * offset);
                            int den = (ndof << 9) - weight;
                            maxBits += Math.min((num + (den >> 1)) / den, n == 2 ? 64 : 61);
                        }
                        int ndof = channels * n + (channels == 2 && n > 2 ? 1 : 0);
                        int offset = ((logN[band] + (lm << bitres)) >> 1) - fineOffset;
                        if (n == 2) {
                            offset += 1 << bitres >> 2;
                        }
                        int num = maxBits + ndof * offset;
                        int den = (ndof - 1) << bitres;
                        maxBits += channels * Math.min((num + (den >> 1)) / den, maxFineBits)
                                << bitres;
                    }
                    // compute_pulse_cache stores (4*max_bits)/(C*N) - 64 so that it
                    // fits a byte; section 4.3.3 tells the decoder to undo exactly
                    // that. Storing and undoing must compose to the identity, so the
                    // stored form is rebuilt here and computeCaps has to land on it.
                    int stored = 4 * maxBits / (channels * (width << lm)) - 64;
                    assertTrue(stored >= 0 && stored < 256,
                            "LM " + lm + " ch" + channels + " band " + band + ": the generating"
                            + " procedure produced " + stored + ", which is not a byte");
                    int expected = (stored + 64) * channels * (width << lm) >> 2;
                    assertEquals(expected, actual[band],
                            "LM " + lm + " ch" + channels + " band " + band + ": the ceiling"
                            + " rebuilt from compute_pulse_cache is " + expected + " eighth"
                            + " bits, the mode says " + actual[band]);
                    if (actual[band] < mode.staticBits(10, band, channels)) {
                        bindingBands++;
                    }
                    compared++;
                }
            }
        }
        assertEquals(168, compared, "four frame sizes by two channel counts by 21 bands");
        // If no ceiling ever came in under the top row of Table 57, the ceilings would
        // never change an allocation and this whole comparison would be vacuous.
        assertTrue(bindingBands > 0,
                "no ceiling anywhere binds below the highest quality row, so the ceilings"
                + " cannot be doing anything");
    }

    @Test
    void trimAndSpreadSymbolsCostWhatTheirRfcProbabilitiesSay() {
        // The inverse CDFs are checked elsewhere against the PDFs the RFC prints, but
        // that check would pass just as happily on a table that had been reversed or
        // rotated: any monotone table round-trips through the range coder. What cannot
        // survive is the price. The trim and spread symbols sit in the middle of every
        // CELT frame, so if their probabilities are off by so much as one symbol's
        // worth the decoder's ec_tell_frac drifts from the encoder's, the allocation
        // computed from the remaining space is a different allocation, and the frame
        // after it decodes as noise. This drives the real range coder and prices them.
        assertIcdfPricesItsOwnPdf("trim", CeltTables.copyTrimIcdf(), CeltTables.TRIM_ICDF_FTB,
                new int[] {2, 2, 5, 10, 22, 46, 22, 10, 5, 2, 2});
        assertIcdfPricesItsOwnPdf("spread", CeltTables.copySpreadIcdf(),
                CeltTables.SPREAD_ICDF_FTB, new int[] {7, 2, 21, 2});
    }

    /**
     * Puts every symbol of an inverse CDF through the range coder and checks both
     * that it survives and that it cost what its probability says it should.
     *
     * @param pdf the RFC's probabilities, typed in here from Tables 56 and 58 rather
     *     than read back out of the table under test
     */
    private static void assertIcdfPricesItsOwnPdf(String what, short[] icdf, int ftb, int[] pdf) {
        assertEquals(pdf.length, icdf.length,
                what + " has " + icdf.length + " symbols but the RFC gives " + pdf.length);
        int total = 0;
        for (int probability : pdf) {
            total += probability;
        }
        assertEquals(1 << ftb, total,
                what + " probabilities sum to " + total + ", not the " + (1 << ftb)
                + " that " + ftb + " bits of range coder precision divide into");

        final int repeats = 256;
        for (int symbol = 0; symbol < pdf.length; symbol++) {
            byte[] buffer = new byte[4096];
            RangeEncoder encoder = new RangeEncoder(buffer);
            int before = encoder.tellFrac();
            for (int i = 0; i < repeats; i++) {
                encoder.encodeIcdf(symbol, icdf, ftb);
            }
            double eighths = (encoder.tellFrac() - before) / (double) repeats;
            double expected = 8 * Math.log((double) total / pdf[symbol]) / Math.log(2);
            assertEquals(expected, eighths, 0.4,
                    what + " " + symbol + " has probability " + pdf[symbol] + "/" + total
                    + ", so it must cost " + expected + " eighth bits; the table charges "
                    + eighths);

            int bytes = encoder.finish();
            RangeDecoder decoder = new RangeDecoder(buffer, 0, bytes);
            for (int i = 0; i < repeats; i++) {
                assertEquals(symbol, decoder.decodeIcdf(icdf, ftb),
                        what + " " + symbol + " came back as a different symbol at repeat " + i);
            }
        }

        // Interleaved, because a table can be right for a symbol on its own and wrong
        // about where one symbol's slice of the range ends and the next one begins.
        byte[] buffer = new byte[4096];
        RangeEncoder encoder = new RangeEncoder(buffer);
        int[] sequence = new int[512];
        for (int i = 0; i < sequence.length; i++) {
            sequence[i] = (i * 7 + i / pdf.length) % pdf.length;
            encoder.encodeIcdf(sequence[i], icdf, ftb);
        }
        int bytes = encoder.finish();
        RangeDecoder decoder = new RangeDecoder(buffer, 0, bytes);
        for (int i = 0; i < sequence.length; i++) {
            assertEquals(sequence[i], decoder.decodeIcdf(icdf, ftb),
                    what + " symbol " + i + " of a mixed run came back wrong");
        }
        assertEquals(encoder.finalRange(), decoder.finalRange(),
                what + " left the encoder and decoder in different range states, so the"
                + " table divides the range differently on the two sides");
    }

    /** The value of a {@code #define} in a file of RFC 6716 Appendix A. */
    private static int referenceDefine(String file, String name) {
        String source = RfcSource.referenceSource(file);
        Matcher matcher = Pattern
                .compile("^#\\s*define\\s+" + name + "\\s+(-?\\d+)\\s*$", Pattern.MULTILINE)
                .matcher(source);
        assertTrue(matcher.find(), name + " is not defined in " + file);
        return Integer.parseInt(matcher.group(1));
    }

    private static int[] widen(short[] values) {
        int[] result = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = values[i];
        }
        return result;
    }

    /** Horner evaluation of a cubic, lowest power first, in float as CELT does it. */
    private static float f(float x, float[] c) {
        return c[0] + x * (c[1] + x * (c[2] + x * c[3]));
    }

    private static void assertBandRejected(Runnable call) {
        IndexOutOfBoundsException thrown = assertThrows(IndexOutOfBoundsException.class,
                call::run);
        assertNotNull(thrown.getMessage(), "an out-of-range band must say which band");
        assertTrue(thrown.getMessage().contains("band"),
                "the message should name the band: " + thrown.getMessage());
    }

    /**
     * The rounded-up base-2 logarithm in eighths of a bit, in exact integer
     * arithmetic.
     *
     * <p>This is {@code log2_frac(value, 3)} from {@code celt/cwrs.c} rederived
     * without floating point: the answer is the smallest L with 2**(L/8) at least
     * value, which is the smallest L with 2**L at least value**8.
     */
    private static int conservativeLog2Eighths(int value) {
        long power = 1;
        for (int i = 0; i < 8; i++) {
            power *= value;
        }
        int l = 0;
        while ((1L << l) < power) {
            l++;
        }
        return l;
    }
}
