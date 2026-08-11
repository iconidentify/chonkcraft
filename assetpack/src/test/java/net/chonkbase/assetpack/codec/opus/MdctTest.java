package net.chonkbase.assetpack.codec.opus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Whether the fast transform computes the sum RFC 6716 section 4.3.7 defines,
 * and whether a signal survives a trip through it.
 *
 * <p>An MDCT that is wrong is not silent and is not obviously broken. Every
 * plausible mistake in it -- a twiddle with the wrong sign, a fold reflected
 * about the wrong sample, a window that is power complementary in the wrong
 * variable -- still produces a signal of about the right loudness with about
 * the right spectrum, and what a listener gets is a rasp on sustained notes at
 * the block rate, 50 Hz for a 20 ms frame. There is no assertion about "the
 * output looks reasonable" that catches any of it.
 *
 * <p>So the reference here is a second implementation. The naive O(n^2) sum
 * below is written straight out of the defining formula and lives in the test
 * rather than in {@code Mdct}, which is the entire point: two independently
 * written evaluations of one formula that agree to seven digits are evidence,
 * and one evaluation agreeing with itself is not. The forward transform is
 * here too, for the same reason and because the decoder does not need one.
 *
 * <p>The window gets an inverted control, and it needs one for a specific
 * reason. Checking power complementarity looks like it settles which window was
 * implemented, and it does not: a plain sine window satisfies
 * {@code w[i]^2 + w[L-1-i]^2 == 1} exactly, and so does the Vorbis window CELT
 * actually uses, and so does an unbounded family of others. Only the window the
 * encoder analysed with will resynthesise what the encoder had, so
 * {@link #anyOtherWindowLeavesAliasingBehind} runs the round trip with a sine
 * window and with the window RFC 6716 draws, and requires both to fail where
 * the coded one passes. What that failure sounds like is the aliased copy of
 * the block left uncancelled underneath the music.
 */
class MdctTest {

    /** The CELT block sizes at 48 kHz, longest first, and their shifts. */
    private static final int[] SIZES = {960, 480, 240, 120};

    /** RFC 6716's frame sizes are 2.5, 5, 10 and 20 ms; the overlap is 120 for all of them. */
    private static final int OPUS_OVERLAP = 120;

    @Test
    @DisplayName("the fast transform computes the same sum as the formula in the specification")
    void theFastTransformAgreesWithTheDefiningSum() {
        Mdct mdct = new Mdct(960);
        int checked = 0;
        double worstRelative = 0;

        for (int shift = 0; shift < SIZES.length; shift++) {
            int n = SIZES[shift];
            assertEquals(960 >> shift, n, "the size and shift tables disagree");

            for (int stride : new int[] {1, 3, 8}) {
                float[] spectrum = new float[8 + n * stride];
                fill(spectrum, 0x51ED0000 + n * 31 + stride);

                float[] fast = new float[2 * n + 5];
                mdct.inverse(spectrum, 8, fast, 5, n, stride, shift);

                double[] slow = naiveInverse(spectrum, 8, n, stride);
                double relative = relativeError(slow, fast, 5);
                worstRelative = Math.max(worstRelative, relative);
                assertTrue(relative < 1e-4,
                        "the fast inverse MDCT and the defining sum disagree by " + relative
                                + " relative at n=" + n + " shift=" + shift + " stride=" + stride
                                + "; a listener hears this as a rasp at the block rate");
                checked++;
            }
        }

        assertEquals(12, checked, "every CELT block size and stride must have been transformed");
        System.out.printf("inverse vs defining sum: worst relative error %.3e over %d cases%n",
                worstRelative, checked);
    }

    @Test
    @DisplayName("a transform built for one short block still runs, so the size is not wired in")
    void aShortOnlyTransformIsStillCorrect() {
        Mdct mdct = new Mdct(120);
        float[] spectrum = new float[120];
        fill(spectrum, 0x2C0FFEE);
        float[] fast = new float[240];
        mdct.inverse(spectrum, 0, fast, 0, 120, 1, 0);

        double relative = relativeError(naiveInverse(spectrum, 0, 120, 1), fast, 0);
        assertTrue(relative < 1e-4,
                "a 2.5 ms only mode transformed wrongly, by " + relative + " relative");
    }

    @Test
    @DisplayName("the windowed form is the plain transform with the window on it, not a second one")
    void theWindowedFormIsTheTransformItClaimsToBe() {
        Mdct mdct = new Mdct(960);
        float[] window = Mdct.window(OPUS_OVERLAP);

        for (int shift = 0; shift < SIZES.length; shift++) {
            int n = SIZES[shift];
            float[] spectrum = new float[n];
            fill(spectrum, 0xBEEF00 + n);

            float[] plain = new float[2 * n];
            mdct.inverse(spectrum, 0, plain, 0, n, 1, shift);

            // Whatever was in the buffer must survive under the rising edge:
            // that is the previous block's tail and dropping it is the defect
            // this catches.
            float[] windowed = new float[n + OPUS_OVERLAP];
            for (int i = 0; i < windowed.length; i++) {
                windowed[i] = 0.125f * (i + 1);
            }
            float[] before = windowed.clone();
            mdct.inverseWindowed(spectrum, 0, windowed, 0, n, 1, shift, window, OPUS_OVERLAP);

            int lead = (n - OPUS_OVERLAP) / 2;
            double worst = 0;
            double peak = 0;
            for (int t = 0; t < n + OPUS_OVERLAP; t++) {
                double expected = assembledWindow(t + lead, n, window, OPUS_OVERLAP) * plain[t + lead];
                if (t < OPUS_OVERLAP) {
                    expected += before[t];
                }
                worst = Math.max(worst, Math.abs(expected - windowed[t]));
                peak = Math.max(peak, Math.abs(expected));
            }
            assertTrue(worst / peak < 1e-6,
                    "inverseWindowed at n=" + n + " is not its own inverse() windowed and"
                            + " overlap-added: off by " + (worst / peak) + " relative");
        }
    }

    @Test
    @DisplayName("a signal put through the transform and back comes out as the signal")
    void aSignalSurvivesAnalysisAndSynthesis() {
        Mdct mdct = new Mdct(960);
        float[] window = Mdct.window(OPUS_OVERLAP);
        int blocks = 6;
        double worstOverall = 0;

        for (int shift = 0; shift < SIZES.length; shift++) {
            int n = SIZES[shift];
            int lead = (n - OPUS_OVERLAP) / 2;

            double[] signal = new double[(blocks + 2) * n];
            fill(signal, 0x5A1E0000 + n);

            float[] out = new float[blocks * n + lead + OPUS_OVERLAP + n];
            float[] coefficients = new float[n];

            for (int b = 0; b < blocks; b++) {
                naiveForward(signal, b * n, n, window, OPUS_OVERLAP, coefficients);
                mdct.inverseWindowed(coefficients, 0, out, b * n + lead, n, 1, shift,
                        window, OPUS_OVERLAP);
            }

            // Only samples that two consecutive blocks both covered are
            // reconstructed: aliasing cancels between a pair, never within one.
            double scale = 2.0 / n;
            double worst = 0;
            double peak = 0;
            int compared = 0;
            for (int t = n; t < blocks * n; t++) {
                worst = Math.max(worst, Math.abs(signal[t] - out[t] * scale));
                peak = Math.max(peak, Math.abs(signal[t]));
                compared++;
            }
            assertEquals((blocks - 1) * n, compared,
                    "the reconstruction was compared over the wrong span at n=" + n);
            assertTrue(peak > 0.5,
                    "the fixture is silent at n=" + n + ", so reconstructing it proves nothing");
            worstOverall = Math.max(worstOverall, worst / peak);
            assertTrue(worst / peak < 1e-5,
                    "analysis then synthesis at n=" + n + " lost the signal by " + (worst / peak)
                            + " relative; time-domain aliasing is not cancelling and a listener"
                            + " hears a buzz at " + (48000 / n) + " Hz");
        }

        System.out.printf("perfect reconstruction: worst relative error %.3e%n", worstOverall);
    }

    @Test
    @DisplayName("a transient frame's eight short blocks reconstruct as one continuous signal")
    void aTransientFrameReconstructsThroughEightInterleavedBlocks() {
        Mdct mdct = new Mdct(960);
        float[] window = Mdct.window(OPUS_OVERLAP);
        int blocks = 8;
        int n = CeltMode.SHORT_MDCT_SIZE;
        int lead = (n - OPUS_OVERLAP) / 2;
        assertEquals(0, lead,
                "the shortest block is fully overlapped, so a lead here means the layout is wrong");

        double[] signal = new double[(blocks + 2) * n];
        fill(signal, 0x7A451E);

        // Laid out the way the decoder holds them: short block b's coefficient
        // k lives at b + blocks * k, which is what the stride argument exists
        // for. A decoder that copies them out into a contiguous run first is
        // allocating once per block on the audio thread.
        float[] interleaved = new float[blocks * n];
        float[] one = new float[n];
        for (int b = 0; b < blocks; b++) {
            naiveForward(signal, b * n, n, window, OPUS_OVERLAP, one);
            for (int k = 0; k < n; k++) {
                interleaved[b + blocks * k] = one[k];
            }
        }

        float[] out = new float[blocks * n + OPUS_OVERLAP + n];
        for (int b = 0; b < blocks; b++) {
            mdct.inverseWindowed(interleaved, b, out, b * n + lead, n, blocks, 3,
                    window, OPUS_OVERLAP);
        }

        double scale = 2.0 / n;
        double worst = 0;
        double peak = 0;
        for (int t = n; t < blocks * n; t++) {
            worst = Math.max(worst, Math.abs(signal[t] - out[t] * scale));
            peak = Math.max(peak, Math.abs(signal[t]));
        }
        assertTrue(peak > 0.5, "the fixture is silent, so reconstructing it proves nothing");
        assertTrue(worst / peak < 1e-5,
                "a transient frame's eight short blocks did not join up, off by " + (worst / peak)
                        + " relative; this is the layout a decoder uses for every attack in the"
                        + " material and getting the stride wrong makes each one a click");
        System.out.printf("transient frame of %d short blocks: relative error %.3e%n",
                blocks, worst / peak);
    }

    @Test
    @DisplayName("blocks of different sizes join up when the frame size changes between frames")
    void blocksOfDifferentSizesJoinWhenTheFrameSizeChanges() {
        Mdct mdct = new Mdct(960);
        float[] window = Mdct.window(OPUS_OVERLAP);

        // Every sequence a stream can hand a decoder, including the two worst
        // steps: 20 ms straight into 2.5 ms and back.
        int[][] runs = {
            {960, 120, 120, 240, 960},
            {120, 960, 120},
            {240, 480, 960, 480, 240, 120, 120, 960},
        };

        double worstOverall = 0;
        for (int[] sizes : runs) {
            int span = 0;
            for (int n : sizes) {
                span += n;
            }
            // The window's zero-padded lead is (n - overlap)/2 and it shrinks as
            // the block does, so the analysis of a 2.5 ms block starts 420
            // samples later in its own 2n frame than a 20 ms one does.
            int pad = (960 - OPUS_OVERLAP) / 2;
            double[] signal = new double[pad + span + 3 * 960];
            fill(signal, 0x0B10C25 + sizes.length);
            float[] out = new float[pad + span + 3 * 960];
            float[] coefficients = new float[960];

            int support = pad;
            for (int n : sizes) {
                int shift = Integer.numberOfTrailingZeros(960 / n);
                int lead = (n - OPUS_OVERLAP) / 2;
                naiveForward(signal, support - lead, n, window, OPUS_OVERLAP, coefficients);

                // The 4/N that opus_fft folds into clt_mdct_forward, which is
                // what makes the round trip unity gain at every block size
                // instead of n/2. A decoder that leaves it out is 18 dB louder
                // on a 20 ms frame than on a 2.5 ms one.
                float normalise = (float) (2.0 / n);
                for (int k = 0; k < n; k++) {
                    coefficients[k] *= normalise;
                }
                mdct.inverseWindowed(coefficients, 0, out, support, n, 1, shift,
                        window, OPUS_OVERLAP);
                support += n;
            }

            // Everything from the end of the first block's rising edge to the
            // start of the last block's falling edge has a partner, so it must
            // come back as itself with no scaling left over.
            int from = pad + OPUS_OVERLAP;
            int to = support;
            double worst = 0;
            double peak = 0;
            for (int q = from; q < to; q++) {
                worst = Math.max(worst, Math.abs(signal[q] - out[q]));
                peak = Math.max(peak, Math.abs(signal[q]));
            }
            assertTrue(peak > 0.5, "the fixture is silent, so reconstructing it proves nothing");
            worstOverall = Math.max(worstOverall, worst / peak);
            assertTrue(worst / peak < 1e-5,
                    "a run of blocks sized " + java.util.Arrays.toString(sizes) + " did not join"
                            + " up, off by " + (worst / peak) + " relative; the overlap regions of"
                            + " two blocks of different sizes have to land on each other and this"
                            + " is every mode switch in a real stream, heard as a click");
        }

        System.out.printf("mixed block sizes across frame boundaries: worst relative error %.3e%n",
                worstOverall);
    }

    @Test
    @DisplayName("a transform writes only where it said it would, and carries nothing between calls")
    void nothingOutsideThePromisedSpanIsTouchedAndNoStateSurvivesACall() {
        Mdct mdct = new Mdct(960);
        int pad = 600;

        for (int shift = 0; shift < SIZES.length; shift++) {
            int n = SIZES[shift];
            float[] spectrum = new float[n];
            fill(spectrum, 0x5CA1B + n);

            for (int overlap : new int[] {2, OPUS_OVERLAP, n}) {
                float[] out = new float[pad + n + overlap + pad];
                fill(out, 0xFEEDF00 + overlap);
                float[] before = out.clone();
                mdct.inverseWindowed(spectrum, 0, out, pad, n, 1, shift,
                        Mdct.window(overlap), overlap);
                for (int i = 0; i < out.length; i++) {
                    if (i >= pad && i < pad + n + overlap) {
                        continue;
                    }
                    assertEquals(before[i], out[i], 0.0f,
                            "inverseWindowed at n=" + n + " overlap=" + overlap + " wrote "
                                    + (i - pad) + " samples from the offset it was given, outside"
                                    + " the n + overlap it promises; the reference reaches"
                                    + " backwards by (n - overlap)/2 internally and a port that"
                                    + " keeps that offset in the caller's coordinates corrupts the"
                                    + " previous frame's tail");
                }
            }

            float[] plain = new float[pad + 2 * n + pad];
            fill(plain, 0xC0FFEE + n);
            float[] plainBefore = plain.clone();
            mdct.inverse(spectrum, 0, plain, pad, n, 1, shift);
            for (int i = 0; i < plain.length; i++) {
                if (i >= pad && i < pad + 2 * n) {
                    continue;
                }
                assertEquals(plainBefore[i], plain[i], 0.0f,
                        "inverse at n=" + n + " wrote outside the 2 * n it promises, at "
                                + (i - pad));
            }
        }

        // The scratch and edge buffers are shared across calls and across block
        // sizes, which is what keeps the per-frame path free of allocation. A
        // call must not be able to see what the last one left there, or a
        // decoder switching block size mid-stream decodes the previous frame's
        // residue into this one.
        float[] shortSpectrum = new float[120];
        fill(shortSpectrum, 0x51DE);
        float[] longSpectrum = new float[960];
        fill(longSpectrum, 0xF00D);
        float[] window = Mdct.window(OPUS_OVERLAP);

        float[] alone = new float[120 + OPUS_OVERLAP];
        new Mdct(960).inverseWindowed(shortSpectrum, 0, alone, 0, 120, 1, 3,
                window, OPUS_OVERLAP);

        float[] after = new float[120 + OPUS_OVERLAP];
        mdct.inverse(longSpectrum, 0, new float[1920], 0, 960, 1, 0);
        mdct.inverseWindowed(longSpectrum, 0, new float[960 + OPUS_OVERLAP], 0, 960, 1, 0,
                window, OPUS_OVERLAP);
        mdct.inverseWindowed(shortSpectrum, 0, after, 0, 120, 1, 3, window, OPUS_OVERLAP);

        for (int i = 0; i < alone.length; i++) {
            assertEquals(alone[i], after[i], 0.0f,
                    "a 2.5 ms block decoded differently at sample " + i + " because a 20 ms block"
                            + " went through the same transform first; state is leaking between"
                            + " calls and every block after a mode switch is wrong");
        }
    }

    @Test
    @DisplayName("the window cancels aliasing at every overlap a mode can ask for")
    void theWindowIsPowerComplementaryAtEveryOverlap() {
        int checked = 0;
        double worst = 0;
        for (int overlap = 2; overlap <= 480; overlap += 2) {
            float[] w = Mdct.window(overlap);
            assertEquals(overlap, w.length, "window(" + overlap + ") returned the wrong length");
            for (int i = 0; i < overlap; i++) {
                double sum = (double) w[i] * w[i]
                        + (double) w[overlap - 1 - i] * w[overlap - 1 - i];
                worst = Math.max(worst, Math.abs(sum - 1.0));
            }
            checked++;
        }
        assertEquals(240, checked, "the sweep covered the wrong number of overlaps");
        assertTrue(worst < 1e-6,
                "the overlap window is off power complementarity by " + worst
                        + ", which is gain ripple at every block boundary");
        System.out.printf("window power complementarity: worst |w^2 + w'^2 - 1| = %.3e"
                + " over %d overlaps%n", worst, checked);
    }

    @Test
    @DisplayName("the whole low-overlap window, zeros and ones included, still cancels aliasing")
    void theAssembledWindowIsPowerComplementary() {
        float[] w = Mdct.window(OPUS_OVERLAP);
        for (int n : SIZES) {
            double worst = 0;
            for (int m = 0; m < n; m++) {
                double a = assembledWindow(m, n, w, OPUS_OVERLAP);
                double b = assembledWindow(m + n, n, w, OPUS_OVERLAP);
                worst = Math.max(worst, Math.abs(a * a + b * b - 1.0));
                assertEquals(assembledWindow(2 * n - 1 - m, n, w, OPUS_OVERLAP), a, 0.0,
                        "the assembled window is not symmetric at m=" + m + " n=" + n);
            }
            assertTrue(worst < 1e-6,
                    "zero-padding the 120-sample window out to " + n + " broke power"
                            + " complementarity by " + worst);
        }
    }

    @Test
    @DisplayName("the window the specification draws is not power complementary and the coded one is")
    void theWindowAsTheRfcDrawsItFailsTheConditionTheRfcStates() {
        int overlap = OPUS_OVERLAP;
        float[] correct = Mdct.window(overlap);

        double worstCorrect = 0;
        double worstAsDrawn = 0;
        double worstSine = 0;
        for (int i = 0; i < overlap; i++) {
            worstCorrect = Math.max(worstCorrect,
                    complementarity(correct[i], correct[overlap - 1 - i]));
            worstAsDrawn = Math.max(worstAsDrawn, complementarity(
                    asDrawnInTheRfc(i, overlap), asDrawnInTheRfc(overlap - 1 - i, overlap)));
            worstSine = Math.max(worstSine,
                    complementarity(plainSine(i, overlap), plainSine(overlap - 1 - i, overlap)));
        }

        assertTrue(worstCorrect < 1e-6,
                "the normative window failed its own condition by " + worstCorrect);
        assertTrue(worstAsDrawn > 0.25,
                "the measurement does not discriminate: the RFC's drawn formula, with the square"
                        + " on the outer sine, was only off by " + worstAsDrawn
                        + " and should be off by about 0.28");

        // A plain sine window passes this and is still the wrong window, which
        // is why the round trip below exists and why complementarity on its own
        // is not evidence that the right window was implemented.
        assertTrue(worstSine < 1e-6,
                "a plain sine window is power complementary; if it is not, this fixture is wrong");

        System.out.printf("window condition: normative %.3e, RFC as drawn %.3f, plain sine %.3e%n",
                worstCorrect, worstAsDrawn, worstSine);
    }

    @Test
    @DisplayName("a synthesis window that is not the encoder's leaves the aliasing in the sound")
    void anyOtherWindowLeavesAliasingBehind() {
        Mdct mdct = new Mdct(960);
        int n = 960;
        int shift = 0;
        float[] encoderWindow = Mdct.window(OPUS_OVERLAP);

        float[] sine = new float[OPUS_OVERLAP];
        float[] asDrawn = new float[OPUS_OVERLAP];
        for (int i = 0; i < OPUS_OVERLAP; i++) {
            sine[i] = (float) plainSine(i, OPUS_OVERLAP);
            asDrawn[i] = (float) asDrawnInTheRfc(i, OPUS_OVERLAP);
        }

        double correct = reconstructionError(mdct, n, shift, encoderWindow, encoderWindow);
        double withSine = reconstructionError(mdct, n, shift, encoderWindow, sine);
        double withAsDrawn = reconstructionError(mdct, n, shift, encoderWindow, asDrawn);

        assertTrue(correct < 1e-5,
                "the normative window did not reconstruct, off by " + correct);
        assertTrue(withSine > 1e-3,
                "a plain sine synthesis window against the encoder's Vorbis window reconstructed"
                        + " to within " + withSine + ", so this test cannot tell the two windows"
                        + " apart and proves nothing about which one was implemented");
        assertTrue(withAsDrawn > 1e-3,
                "the RFC's drawn window reconstructed to within " + withAsDrawn
                        + ", so this test cannot tell it from the coded one");

        System.out.printf("residual after overlap-add: normative %.3e, plain sine %.3e,"
                + " RFC as drawn %.3e%n", correct, withSine, withAsDrawn);
    }

    /**
     * Analyses a signal with {@code analysis} and resynthesises it with
     * {@code synthesis}, and answers how much of the signal did not come back.
     */
    private static double reconstructionError(Mdct mdct, int n, int shift,
            float[] analysis, float[] synthesis) {
        int blocks = 4;
        int lead = (n - OPUS_OVERLAP) / 2;
        double[] signal = new double[(blocks + 2) * n];
        fill(signal, 0x1A11A5);

        float[] out = new float[blocks * n + lead + OPUS_OVERLAP + n];
        float[] coefficients = new float[n];
        for (int b = 0; b < blocks; b++) {
            naiveForward(signal, b * n, n, analysis, OPUS_OVERLAP, coefficients);
            mdct.inverseWindowed(coefficients, 0, out, b * n + lead, n, 1, shift,
                    synthesis, OPUS_OVERLAP);
        }

        double scale = 2.0 / n;
        double worst = 0;
        double peak = 0;
        for (int t = n; t < blocks * n; t++) {
            worst = Math.max(worst, Math.abs(signal[t] - out[t] * scale));
            peak = Math.max(peak, Math.abs(signal[t]));
        }
        return worst / peak;
    }

    @Test
    @DisplayName("a decode in progress allocates nothing, because the audio thread cannot pause")
    void aSteadyStateInverseAllocatesNothing() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        assumeTrue(bean instanceof com.sun.management.ThreadMXBean,
                "this JVM does not report per-thread allocation");
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) bean;
        assumeTrue(threads.isThreadAllocatedMemorySupported(),
                "this JVM does not report per-thread allocation");
        threads.setThreadAllocatedMemoryEnabled(true);

        Mdct mdct = new Mdct(960);
        float[] window = Mdct.window(OPUS_OVERLAP);
        float[] spectrum = new float[960];
        fill(spectrum, 0xA110C);
        float[] out = new float[1920];

        for (int i = 0; i < 20_000; i++) {
            mdct.inverse(spectrum, 0, out, 0, 960, 1, 0);
            mdct.inverseWindowed(spectrum, 0, out, 0, 960, 1, 0, window, OPUS_OVERLAP);
        }

        long id = Thread.currentThread().threadId();
        long before = threads.getThreadAllocatedBytes(id);
        for (int i = 0; i < 20_000; i++) {
            mdct.inverse(spectrum, 0, out, 0, 960, 1, 0);
            mdct.inverseWindowed(spectrum, 0, out, 0, 960, 1, 0, window, OPUS_OVERLAP);
        }
        long after = threads.getThreadAllocatedBytes(id);

        long bytes = after - before;
        assertTrue(bytes < 4096,
                "40000 transforms allocated " + bytes + " bytes; anything on this path is a"
                        + " collection pause in the middle of a frame, which is a click");
        System.out.printf("allocation over 40000 transforms: %d bytes%n", bytes);
    }

    @Test
    @DisplayName("a block costs microseconds, not milliseconds, so a frame fits in its own budget")
    void theFastTransformIsFasterThanTheSumItReplaces() {
        Mdct mdct = new Mdct(960);
        float[] out = new float[1920];
        double longBlockMicros = 0;
        double shortBlockMicros = 0;
        double worstRatio = Double.MAX_VALUE;

        for (int shift = 0; shift < SIZES.length; shift++) {
            int n = SIZES[shift];
            float[] spectrum = new float[n];
            fill(spectrum, 0x71E + n);

            for (int i = 0; i < 20_000; i++) {
                mdct.inverse(spectrum, 0, out, 0, n, 1, shift);
            }
            long best = Long.MAX_VALUE;
            for (int round = 0; round < 5; round++) {
                long start = System.nanoTime();
                for (int i = 0; i < 5_000; i++) {
                    mdct.inverse(spectrum, 0, out, 0, n, 1, shift);
                }
                best = Math.min(best, System.nanoTime() - start);
            }
            double fastMicros = best / 5_000.0 / 1000.0;

            double[] table = cosineTable(n);
            naiveInverse(spectrum, 0, n, 1, table);
            long slowStart = System.nanoTime();
            for (int i = 0; i < 5; i++) {
                naiveInverse(spectrum, 0, n, 1, table);
            }
            double slowMicros = (System.nanoTime() - slowStart) / 5.0 / 1000.0;

            if (n == 960) {
                longBlockMicros = fastMicros;
            }
            if (n == 120) {
                shortBlockMicros = fastMicros;
            }
            worstRatio = Math.min(worstRatio, slowMicros / fastMicros);
            System.out.printf("n=%4d inverse: %7.2f us fast, %8.0f us direct, %5.0fx%n",
                    n, fastMicros, slowMicros, slowMicros / fastMicros);
        }

        // The worst frame CELT can ask for at 48 kHz: 20 ms, stereo, transient,
        // so eight 120-sample blocks per channel instead of one long one.
        System.out.printf("worst 20 ms stereo frame (16 short blocks): %.1f us of 20000%n",
                16 * shortBlockMicros);
        System.out.printf("plain 20 ms stereo frame (2 long blocks): %.1f us of 20000%n",
                2 * longBlockMicros);

        assertTrue(worstRatio > 20,
                "the transform is only " + worstRatio + " times faster than the direct sum at its"
                        + " worst size, so it is not a fast transform and the audio thread will"
                        + " underrun on transient frames");
    }

    @Test
    @DisplayName("a malformed call is refused by name, never by reading off the end of an array")
    void everyBadArgumentIsRefusedWithAMessage() {
        Mdct mdct = new Mdct(960);
        float[] window = Mdct.window(OPUS_OVERLAP);
        float[] spectrum = new float[960];
        float[] out = new float[2048];

        record Bad(String what, Runnable call) {}
        Bad[] cases = {
            new Bad("shift above the largest built",
                    () -> mdct.inverse(spectrum, 0, out, 0, 1, 1, 99)),
            new Bad("negative shift",
                    () -> mdct.inverse(spectrum, 0, out, 0, 960, 1, -1)),
            new Bad("n that is not maxSize >> shift",
                    () -> mdct.inverse(spectrum, 0, out, 0, 500, 1, 0)),
            new Bad("zero stride",
                    () -> mdct.inverse(spectrum, 0, out, 0, 960, 0, 0)),
            new Bad("stride that runs off the coefficients",
                    () -> mdct.inverse(spectrum, 0, out, 0, 960, 2, 0)),
            new Bad("negative spectrum offset",
                    () -> mdct.inverse(spectrum, -1, out, 0, 960, 1, 0)),
            new Bad("output too short",
                    () -> mdct.inverse(spectrum, 0, new float[1919], 0, 960, 1, 0)),
            new Bad("output offset that runs off the end",
                    () -> mdct.inverse(spectrum, 0, out, 200, 960, 1, 0)),
            new Bad("null spectrum",
                    () -> mdct.inverse(null, 0, out, 0, 960, 1, 0)),
            new Bad("null output",
                    () -> mdct.inverse(spectrum, 0, null, 0, 960, 1, 0)),
            new Bad("overlap longer than the block",
                    () -> mdct.inverseWindowed(spectrum, 0, out, 0, 960, 1, 0, window, 962)),
            new Bad("odd overlap",
                    () -> mdct.inverseWindowed(spectrum, 0, out, 0, 960, 1, 0, window, 119)),
            new Bad("window shorter than the overlap",
                    () -> mdct.inverseWindowed(spectrum, 0, out, 0, 960, 1, 0, new float[8], 120)),
            new Bad("null window",
                    () -> mdct.inverseWindowed(spectrum, 0, out, 0, 960, 1, 0, null, 120)),
            new Bad("odd maxSize", () -> new Mdct(961)),
            new Bad("maxSize with a prime factor above five", () -> new Mdct(14 * 2)),
            new Bad("odd window length", () -> Mdct.window(7)),
        };

        assertEquals(17, cases.length, "the refusal sweep lost a case");
        for (Bad bad : cases) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> bad.call().run(),
                    bad.what() + " was accepted; a malformed stream must be refused here rather"
                            + " than become an out of bounds read further down");
            assertTrue(thrown.getMessage() != null && !thrown.getMessage().isBlank(),
                    bad.what() + " threw without saying what was wrong");
        }
    }

    // ---- the independent reference, written from the formula and nothing else ----

    /**
     * The inverse MDCT of RFC 6716 section 4.3.7, evaluated as its own
     * definition:
     *
     * <pre>
     *   y[m] = sum(k = 0 .. n-1) X[k] * cos(pi/n * (m + 1/2 + n/2) * (k + 1/2))
     * </pre>
     *
     * <p>The cosine comes from a table indexed by the exact integer phase
     * {@code (2m + 1 + n)(2k + 1) mod 8n} rather than from repeated angle
     * arithmetic, so this carries no accumulated phase error of its own and
     * a disagreement is the fast transform's.
     */
    private static double[] naiveInverse(float[] spectrum, int offset, int n, int stride) {
        return naiveInverse(spectrum, offset, n, stride, cosineTable(n));
    }

    private static double[] naiveInverse(float[] spectrum, int offset, int n, int stride,
            double[] table) {
        int period = 8 * n;
        double[] out = new double[2 * n];
        for (int m = 0; m < 2 * n; m++) {
            int step = 2 * m + 1 + n;
            int phase = step % period;
            int increment = 2 * step % period;
            double sum = 0;
            for (int k = 0; k < n; k++) {
                sum += spectrum[offset + k * stride] * table[phase];
                phase += increment;
                if (phase >= period) {
                    phase -= period;
                }
            }
            out[m] = sum;
        }
        return out;
    }

    /**
     * The forward MDCT, the transpose of the above with the analysis window
     * applied. Only the test needs it: a decoder never runs one.
     */
    private static void naiveForward(double[] signal, int from, int n, float[] window,
            int overlap, float[] out) {
        double[] table = cosineTable(n);
        for (int k = 0; k < n; k++) {
            double sum = 0;
            for (int m = 0; m < 2 * n; m++) {
                long phase = (long) (2 * m + 1 + n) * (2 * k + 1) % (8L * n);
                sum += assembledWindow(m, n, window, overlap) * signal[from + m] * table[(int) phase];
            }
            out[k] = (float) sum;
        }
    }

    private static double[] cosineTable(int n) {
        double[] table = new double[8 * n];
        for (int t = 0; t < 8 * n; t++) {
            table[t] = StrictMath.cos(Math.PI * t / (4.0 * n));
        }
        return table;
    }

    /**
     * The 2n-sample window CELT actually applies: the 120-sample Vorbis window
     * on each edge, ones between them, zeros outside. RFC 6716 section 4.3.7
     * calls this zero-padding the basic window and inserting ones in the
     * middle, and it is what makes the codec's algorithmic delay shorter than
     * its block.
     */
    private static double assembledWindow(int m, int n, float[] window, int overlap) {
        int lead = (n - overlap) / 2;
        if (m < lead || m >= 2 * n - lead) {
            return 0;
        }
        if (m < lead + overlap) {
            return window[m - lead];
        }
        if (m >= 2 * n - lead - overlap) {
            return window[2 * n - 1 - m - lead];
        }
        return 1;
    }

    private static double complementarity(double a, double b) {
        return Math.abs(a * a + b * b - 1.0);
    }

    /** The window as RFC 6716 draws it: the square on the outer sine. */
    private static double asDrawnInTheRfc(int i, int overlap) {
        double outer = StrictMath.sin(0.5 * Math.PI
                * StrictMath.sin(0.5 * Math.PI * (i + 0.5) / overlap));
        return outer * outer;
    }

    private static double plainSine(int i, int overlap) {
        return StrictMath.sin(0.5 * Math.PI * (i + 0.5) / overlap);
    }

    // ---- fixtures ----

    /**
     * A deterministic spread of positive and negative values. Not
     * {@code java.util.Random}: two machines running this suite have to compare
     * the same numbers, and the seed is what makes a failure reproducible.
     */
    private static void fill(float[] target, int seed) {
        int state = seed | 1;
        for (int i = 0; i < target.length; i++) {
            state = state * 1_664_525 + 1_013_904_223;
            target[i] = (((state >>> 8) & 0xFFFF) - 32768) / 32768.0f;
        }
    }

    private static void fill(double[] target, int seed) {
        int state = seed | 1;
        for (int i = 0; i < target.length; i++) {
            state = state * 1_664_525 + 1_013_904_223;
            target[i] = (((state >>> 8) & 0xFFFF) - 32768) / 32768.0;
        }
    }

    private static double relativeError(double[] expected, float[] actual, int actualOffset) {
        double worst = 0;
        double peak = 0;
        for (int i = 0; i < expected.length; i++) {
            worst = Math.max(worst, Math.abs(expected[i] - actual[actualOffset + i]));
            peak = Math.max(peak, Math.abs(expected[i]));
        }
        assertTrue(peak > 0, "the reference transform produced silence, so nothing was compared");
        return worst / peak;
    }
}
