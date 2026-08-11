package net.chonkbase.assetpack.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.chonkbase.assetpack.codec.opus.OggReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Whole Ogg Opus files, in and out.
 *
 * <p>The packet-level tests next door prove the codec. This proves the thing an
 * asset pack actually depends on, which is a level up: that a clip handed to
 * {@link Opus#encode} comes back from {@link Opus#decode} at <em>its own sample
 * rate</em>, with <em>exactly</em> the frame count it went in with, on the same
 * scale it went in on. Opus decodes only at 48 kHz, so none of those three is
 * free, and every one of them is something a pack would otherwise get wrong in
 * a way that is inaudible in a listening test and permanent in the file.
 */
@DisplayName("Opus files")
class OpusFileTest {

    /** The rates Warcraft II holds audio at, plus the codec's own. */
    private static final int[] GAME_RATES = {11_025, 22_050, 44_100, 48_000};

    // ---------------------------------------------------------------- shape

    @Test
    @DisplayName("a clip comes back at its own rate with exactly its own frame count")
    void theFrameCountSurvivesEveryRate() {
        int checked = 0;
        List<String> report = new ArrayList<>();
        for (int rate : GAME_RATES) {
            for (int channels : new int[] {1, 2}) {
                // Deliberately not a whole number of 20 ms frames, and not a
                // whole number of 48 kHz samples either: 220.5 input samples
                // make a frame at 11025 Hz, and a length that divided evenly
                // would hide every rounding error there is.
                for (int frames : new int[] {1, 997, 12_345}) {
                    Flac.Pcm source = tone(rate, channels, frames);
                    byte[] file = Opus.encode(source, 64_000);
                    Flac.Pcm back = Opus.decode(file);

                    assertEquals(rate, back.sampleRate(),
                            "a " + rate + " Hz clip came back at " + back.sampleRate()
                            + " Hz, so every consumer's resampler would run wrong");
                    assertEquals(channels, back.channels(), "channel count at " + rate);
                    assertEquals(frames, back.frameCount(),
                            "a " + frames + "-frame clip at " + rate + " Hz came back "
                            + back.frameCount() + " frames long");
                    checked++;
                    report.add(rate + "/" + channels + "ch/" + frames);
                }
            }
        }
        assertEquals(24, checked, "not every rate, channel count and length ran: " + report);
    }

    @Test
    @DisplayName("the container records the rate the audio came from, not 48 kHz")
    void theContainerRecordsTheOriginalRate() {
        int checked = 0;
        for (int rate : GAME_RATES) {
            byte[] file = Opus.encode(tone(rate, 1, 5000), 64_000);
            OggReader reader = new OggReader(file);
            assertEquals(rate, reader.inputSampleRate(),
                    "the identification header lost the original rate");
            assertEquals(Opus.PRE_SKIP, reader.preSkip(), "pre-skip at " + rate);

            Opus.Info info = Opus.readInfo(file);
            assertEquals(rate, info.sampleRate());
            assertEquals(5000, info.frameCount(), "the header count at " + rate);
            assertEquals(1, info.channels());
            assertTrue(info.packetCount() > 0, "no packets at " + rate);
            // Read without decoding a single packet, and still agreeing with
            // what a full decode produces.
            assertEquals(Opus.decode(file).frameCount(), info.frameCount(),
                    "the header count and the decoded count disagree at " + rate);
            checked++;
        }
        assertEquals(GAME_RATES.length, checked);
    }

    @Test
    @DisplayName("encoding the same audio twice produces the same bytes")
    void encodingIsDeterministic() {
        Flac.Pcm source = tone(22_050, 1, 8000);
        byte[] first = Opus.encode(source, 64_000);
        byte[] second = Opus.encode(source, 64_000);
        assertEquals(first.length, second.length, "two encodes of one clip differ in length");
        assertTrue(java.util.Arrays.equals(first, second),
                "two encodes of one clip differ, so a pack is not reproducible");
        assertNotEquals(first.length, Opus.encode(source, 128_000).length,
                "the bitrate had no effect on the size");
    }

    @Test
    @DisplayName("an empty clip is a valid file that decodes to nothing")
    void anEmptyClipRoundTrips() {
        Flac.Pcm empty = new Flac.Pcm(11_025, 1, 8, new int[0]);
        byte[] file = Opus.encode(empty, 64_000);
        Flac.Pcm back = Opus.decode(file, 8);
        assertEquals(0, back.frameCount(), "an empty clip decoded to something");
        assertEquals(11_025, back.sampleRate());
        assertEquals(8, back.bitsPerSample());
    }

    @Test
    @DisplayName("a truncated file is refused by name rather than by array index")
    void aTruncatedFileIsRefusedClearly() {
        byte[] file = Opus.encode(tone(22_050, 1, 4000), 64_000);
        byte[] cut = java.util.Arrays.copyOf(file, file.length - 200);
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> Opus.decode(cut));
        assertTrue(thrown.getMessage() != null && !thrown.getMessage().isBlank(),
                "a damaged stream threw " + thrown.getClass().getSimpleName()
                + " with nothing a person could act on");
    }

    // ----------------------------------------------------------------- gain

    @Test
    @DisplayName("8-bit audio comes back on the 8-bit scale, not shifted or scaled")
    void eightBitAudioKeepsItsScale() {
        // Full scale for an 8-bit file is -128 to 127, which is what Wav.decode
        // yields. A round trip that came back on the 16-bit scale would be 48 dB
        // too loud, and one that came back scaled by 257 instead of shifted by
        // eight would be a third of a decibel out and would never round-trip an
        // exact value.
        Flac.Pcm source = tone(11_025, 1, 6000, 8, 100);
        Flac.Pcm back = Opus.decode(Opus.encode(source, 64_000), 8);
        assertEquals(8, back.bitsPerSample());
        assertEquals(source.frameCount(), back.frameCount());

        int peak = 0;
        for (int sample : back.samples()) {
            peak = Math.max(peak, Math.abs(sample));
            assertTrue(sample >= -128 && sample <= 127,
                    "sample " + sample + " is outside the 8-bit range");
        }
        assertTrue(peak > 80 && peak <= 128,
                "the peak came back at " + peak + " where the source peaked at 100");
    }

    // ------------------------------------------------------------------ SNR

    @Test
    @DisplayName("a tone survives the round trip at every rate the game uses")
    void aToneSurvivesEveryRate() {
        StringBuilder report = new StringBuilder();
        int checked = 0;
        double worst = Double.POSITIVE_INFINITY;
        for (int rate : GAME_RATES) {
            Flac.Pcm source = tone(rate, 1, rate);
            Flac.Pcm back = Opus.decode(Opus.encode(source, 64_000));
            double db = SignalToNoise.db(source.samples(), back.samples());
            report.append(rate).append(" Hz ").append(SignalToNoise.describe(db)).append("; ");
            assertTrue(db > 10.0, "a 440 Hz tone at " + rate + " Hz came back at only "
                    + SignalToNoise.describe(db));
            worst = Math.min(worst, db);
            checked++;
        }
        assertEquals(GAME_RATES.length, checked);
        System.out.println("Opus 64k on a tone: " + report);
        assertTrue(worst > 10.0, "the worst rate managed " + worst + " dB");
    }

    @Test
    @DisplayName("noise-like audio survives at the two rates the pack encodes at")
    void noiseSurvivesAtThePackBitrates() {
        // Not a tone: a codec that only ever saw sine waves would pass on the
        // strength of its band energy alone.
        StringBuilder report = new StringBuilder();
        int checked = 0;
        for (int[] run : new int[][] {{11_025, 1, 64_000}, {22_050, 1, 64_000},
                {44_100, 2, 144_000}, {48_000, 2, 144_000}}) {
            Flac.Pcm source = filteredNoise(run[0], run[1], run[0] * 2);
            Flac.Pcm back = Opus.decode(Opus.encode(source, run[2]));
            double db = SignalToNoise.db(source.samples(), back.samples());
            report.append(run[0]).append(" Hz ").append(run[1]).append("ch @")
                    .append(run[2] / 1000).append("k ")
                    .append(SignalToNoise.describe(db)).append("; ");
            assertTrue(db > 5.0, "noise at " + run[0] + " Hz came back at only "
                    + SignalToNoise.describe(db));
            checked++;
        }
        assertEquals(4, checked);
        System.out.println("Opus on filtered noise: " + report);
    }

    // ----------------------------------------------------------------- odds

    @Test
    @DisplayName("silence in is silence out, not a burst of anything")
    void silenceStaysSilent() {
        Flac.Pcm silence = new Flac.Pcm(11_025, 1, 8, new int[4000]);
        Flac.Pcm back = Opus.decode(Opus.encode(silence, 64_000), 8);
        assertEquals(4000, back.frameCount());
        int peak = 0;
        for (int sample : back.samples()) {
            peak = Math.max(peak, Math.abs(sample));
        }
        assertEquals(0, peak, "silence decoded to a peak of " + peak + " on the 8-bit scale");
    }

    // ------------------------------------------------------------- fixtures

    private static Flac.Pcm tone(int rate, int channels, int frames) {
        return tone(rate, channels, frames, 16, 12_000);
    }

    private static Flac.Pcm tone(int rate, int channels, int frames, int bits, int amplitude) {
        int[] samples = new int[frames * channels];
        for (int frame = 0; frame < frames; frame++) {
            double t = frame / (double) rate;
            int value = (int) Math.round(amplitude
                    * (0.7 * Math.sin(2 * Math.PI * 440 * t) + 0.3 * Math.sin(2 * Math.PI * 1310 * t)));
            for (int c = 0; c < channels; c++) {
                samples[frame * channels + c] = value;
            }
        }
        return new Flac.Pcm(rate, channels, bits, samples);
    }

    /**
     * Noise with the top of its band rolled off, which is what an old game's
     * recorded audio looks like: a full-band white source would be asking the
     * codec to reproduce energy the source rate cannot carry.
     */
    private static Flac.Pcm filteredNoise(int rate, int channels, int frames) {
        Random random = new Random(20260726L);
        int[] samples = new int[frames * channels];
        double[] state = new double[channels];
        for (int frame = 0; frame < frames; frame++) {
            for (int c = 0; c < channels; c++) {
                double white = random.nextGaussian() * 8000;
                state[c] = 0.75 * state[c] + 0.25 * white;
                samples[frame * channels + c] =
                        (int) Math.max(-32768, Math.min(32767, Math.round(state[c] * 3)));
            }
        }
        return new Flac.Pcm(rate, channels, 16, samples);
    }
}
