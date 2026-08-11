package net.chonkbase.assetpack.codec.opus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.assetpack.codec.SignalToNoise;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The resampler on both sides of the codec.
 *
 * <p>It lived inside {@link OpusEncoder} while only the upward direction
 * existed, and the encoder's own tests covered it from above. Now that a pack
 * resamples back down as well -- Opus decodes only at 48 kHz and the game's
 * audio is at 11,025, 22,050 and 44,100 -- the downward direction needs testing
 * in its own right, and the properties that matter are not the ones the encoder
 * happened to exercise.
 *
 * <p>Three of them. The output has exactly the length asked for, because a pack
 * rebuilds an archive entry to a recorded frame count and one frame short is a
 * clipped consonant. The output is time-aligned with the input, because a
 * resampler with a delay would shift every window taken out of a shared music
 * stream. And a round trip up and back down returns the signal, because that is
 * literally what every Opus asset in a pack goes through.
 */
@DisplayName("the resampler")
class ResamplerTest {

    /** The rates a Warcraft II pack converts between. */
    private static final int[][] PAIRS = {
        {11_025, 48_000}, {22_050, 48_000}, {44_100, 48_000},
        {48_000, 11_025}, {48_000, 22_050}, {48_000, 44_100},
    };

    @Test
    @DisplayName("the output is exactly the length asked for, at every rate pair")
    void theOutputLengthIsExact() {
        int checked = 0;
        for (int[] pair : PAIRS) {
            for (int channels : new int[] {1, 2}) {
                short[] input = tone(pair[0], channels, 5000, 1000.0);
                for (int wanted : new int[] {1, 4321, 20_000}) {
                    short[] out = Resampler.resample(input, channels, pair[0], pair[1], wanted);
                    assertEquals(wanted * channels, out.length,
                            pair[0] + " to " + pair[1] + " Hz, " + channels + " channels");
                    checked++;
                }
            }
        }
        assertEquals(PAIRS.length * 2 * 3, checked, "not every case ran");
    }

    @Test
    @DisplayName("a tone comes back through 48 kHz and out again")
    void aToneSurvivesTheRoundTrip() {
        StringBuilder report = new StringBuilder();
        int checked = 0;
        double worst = Double.POSITIVE_INFINITY;
        for (int rate : new int[] {11_025, 22_050, 44_100}) {
            int frames = rate / 2;
            // Well inside the band, so the round trip is a fair question: a tone
            // at the very top of the source's range would be measuring the
            // filter's transition band rather than its passband.
            short[] source = tone(rate, 1, frames, rate / 8.0);
            short[] up = Resampler.resample(source, 1, rate, 48_000,
                    (int) ((long) frames * 48_000 / rate));
            short[] down = Resampler.resample(up, 1, 48_000, rate, frames);

            // The ends are skipped. The kernel reads silence from before the
            // start and after the end, which is correct and is not the thing
            // being measured.
            int skip = 200;
            short[] a = new short[frames - 2 * skip];
            short[] b = new short[a.length];
            System.arraycopy(source, skip, a, 0, a.length);
            System.arraycopy(down, skip, b, 0, b.length);
            double db = SignalToNoise.db(a, b);
            report.append(rate).append(" Hz ").append(SignalToNoise.describe(db)).append("; ");
            assertTrue(db > 60.0, "a tone through " + rate + " to 48000 and back came out at "
                    + SignalToNoise.describe(db) + ", which is worse than the 8-bit source"
                    + " material's own noise floor");
            worst = Math.min(worst, db);
            checked++;
        }
        assertEquals(3, checked);
        System.out.println("resampler round trip: " + report);
        assertTrue(worst > 60.0, "the worst rate managed " + worst + " dB");
    }

    @Test
    @DisplayName("output sample n lines up with input time n, with no delay to compensate for")
    void thereIsNoDelay() {
        // A step. Its edge is at a known time, and a resampler with a delay puts
        // the edge somewhere else -- which, in a pack, moves every window taken
        // out of a shared music stream by the same amount and is inaudible in
        // isolation.
        int rate = 44_100;
        int frames = 4000;
        int edge = 2000;
        short[] input = new short[frames];
        for (int i = edge; i < frames; i++) {
            input[i] = 20_000;
        }
        short[] out = Resampler.resample(input, 1, rate, 48_000,
                (int) ((long) frames * 48_000 / rate));

        int expected = (int) ((long) edge * 48_000 / rate);
        int found = -1;
        for (int i = 0; i < out.length; i++) {
            if (out[i] > 10_000) {
                found = i;
                break;
            }
        }
        assertTrue(found >= 0, "the step never appeared in the output");
        assertTrue(Math.abs(found - expected) <= 1, "the step crossed half scale at output "
                + found + " where the input crosses at " + expected
                + ", so the filter has a delay of " + (found - expected) + " samples");
    }

    @Test
    @DisplayName("downsampling filters to the output's Nyquist rather than the input's")
    void downsamplingStopsAtTheOutputsNyquist() {
        // 15 kHz is fine at 48,000 and above Nyquist at 22,050. A filter written
        // with the rate ratio the wrong way up passes it and folds it down to
        // 7 kHz, which is not silence and not obviously broken, just a whistle
        // that was not in the recording.
        int frames = 24_000;
        short[] input = tone(48_000, 1, frames, 15_000.0);
        short[] out = Resampler.resample(input, 1, 48_000, 22_050, frames * 22_050 / 48_000);

        long energy = 0;
        for (int i = 500; i < out.length - 500; i++) {
            energy += (long) out[i] * out[i];
        }
        double rms = Math.sqrt(energy / (double) Math.max(1, out.length - 1000));
        assertTrue(rms < 200, "a 15 kHz tone downsampled to 22,050 Hz came out at an RMS of "
                + rms + " where it should have been filtered away; it has aliased down"
                + " into the audible band");
    }

    @Test
    @DisplayName("an impossible conversion is refused by name")
    void nonsenseIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Resampler(0, 48_000, 1));
        assertThrows(IllegalArgumentException.class, () -> new Resampler(48_000, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Resampler(48_000, 44_100, 0));
    }

    @Test
    @DisplayName("the same rate in and out is a copy, not a filter pass")
    void anIdenticalRateIsACopy() {
        short[] input = {1, -2, 3, -4, 5, -6};
        short[] out = Resampler.resample(input, 2, 48_000, 48_000, 3);
        assertEquals(6, out.length);
        for (int i = 0; i < 6; i++) {
            assertEquals(input[i], out[i], "sample " + i + " was altered by a no-op conversion");
        }
    }

    private static short[] tone(int rate, int channels, int frames, double hz) {
        short[] out = new short[frames * channels];
        for (int frame = 0; frame < frames; frame++) {
            short value = (short) Math.round(18_000 * Math.sin(2 * Math.PI * hz * frame / rate));
            for (int c = 0; c < channels; c++) {
                out[frame * channels + c] = value;
            }
        }
        return out;
    }
}
