package net.chonkbase.assetpack.codec.opus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What comes out of a speaker when this encoder's bytes are handed to somebody
 * else's decoder.
 *
 * <p>An encoder cannot be tested the way the decoder was. There is no reference
 * bitstream to match: RFC 6716 fixes what a decoder must do with a stream and
 * leaves an encoder free to make every analysis decision differently, so a
 * correct encoder's output need not be a single byte like libopus's. That
 * freedom is also the trap. Nearly every way of getting this wrong produces a
 * stream that still parses, still decodes, and still sounds broadly like the
 * input -- the pulse search settling for the second-best position, the
 * spreading rotation applied in the wrong direction, the mid/side angle
 * quantised coarsely -- so a round trip through our own decoder passes while
 * the codec quietly gives away several decibels.
 *
 * <p>So the checks here are in three layers, weakest first.
 *
 * <p><b>The range state.</b> After every frame, the encoder's range coder and
 * the decoder's hold the same 32-bit integer if and only if every symbol was
 * written and read identically. This is integer arithmetic and cannot be
 * excused by floating-point rounding. It is what caught the one real defect
 * found while this was written: the band-boost loop counted its terminating
 * zero flag as a boost, so the encoder made the next band's first flag cheaper
 * when the decoder did not, and every frame parted company from the decoder at
 * band 1 while still producing audio that looked plausible.
 *
 * <p><b>Interoperability.</b> Our decoder agreeing with our encoder proves only
 * that the two were written from the same misunderstanding. The gate that means
 * something is ffmpeg: our packets go into a real Ogg Opus file, ffmpeg decodes
 * it with its own decoder, and the audio that comes out is compared with what
 * went in. If ffmpeg refuses the file or the audio is wrong, the encoder is
 * wrong whatever our own decoder says.
 *
 * <p><b>The numbers.</b> Signal to noise against the source, on tones, noise,
 * silence, transients and real music, with thresholds close to what this
 * actually reaches so that a regression shows up rather than hiding under a
 * generous floor.
 *
 * <p>Every sweep here counts what it covered and asserts on the count first. A
 * sweep that discovered nothing passes every check downstream of it, which is
 * exactly how a suite stays green over an encoder that has stopped producing
 * packets at all.
 */
@DisplayName("the CELT encoder")
class CeltEncoderTest {

    /** The delay through an encode and a decode, which is the MDCT overlap. */
    private static final int CODEC_DELAY = 120;

    /** How long a run of frames the SNR measurements skip before believing them. */
    private static final int SETTLE_FRAMES = 4;

    private static final int FRAME_48K = 960;

    // ---------------------------------------------------------------- transform

    /**
     * The analysis transform against the synthesis one, over runs of mixed block
     * sizes.
     *
     * <p>This is the one part of the encoder with an exact answer, and it is
     * worth pinning on its own because a scale error here is invisible
     * downstream: the band energies are measured from these same coefficients,
     * so the envelope absorbs any constant gain and the decoded audio comes back
     * at the right level however wrong the transform is. What it would not
     * absorb is the shape, and what a listener would hear is a buzz at the frame
     * rate from aliasing that never cancelled.
     */
    @Test
    @DisplayName("analysis then synthesis gives back the signal it started with")
    void theAnalysisTransformInvertsTheSynthesisOne() {
        int overlap = CeltMode.OVERLAP;
        Mdct mdct = new Mdct(FRAME_48K);
        float[] window = Mdct.window(overlap);
        int[][] runs = {
            {960, 960, 960, 960},
            {120, 120, 120, 120, 120, 120, 120, 120, 960},
            {480, 240, 120, 120, 960, 480, 240},
        };

        int blocksChecked = 0;
        double worstRelative = 0;
        for (int[] sizes : runs) {
            int span = 0;
            for (int n : sizes) {
                span += n;
            }
            int pad = FRAME_48K;
            float[] signal = new float[pad + span + 2 * FRAME_48K];
            Random random = new Random(0x0B10C25);
            for (int i = 0; i < signal.length; i++) {
                signal[i] = (float) random.nextGaussian();
            }
            float[] rebuilt = new float[signal.length];
            float[] coefficients = new float[FRAME_48K];

            int at = pad;
            for (int n : sizes) {
                int shift = Integer.numberOfTrailingZeros(FRAME_48K / n);
                mdct.forward(signal, at, coefficients, 0, n, 1, shift, window, overlap);
                mdct.inverseWindowed(coefficients, 0, rebuilt, at, n, 1, shift, window, overlap);
                at += n;
                blocksChecked++;
            }

            double worst = 0;
            double peak = 0;
            for (int q = pad + overlap; q < at; q++) {
                worst = Math.max(worst, Math.abs(signal[q] - rebuilt[q]));
                peak = Math.max(peak, Math.abs(signal[q]));
            }
            assertTrue(peak > 0.5,
                    "the fixture is silent, so reconstructing it proves nothing");
            worstRelative = Math.max(worstRelative, worst / peak);
        }

        assertEquals(20, blocksChecked,
                "the run of block sizes did not cover what it was written to cover");
        assertTrue(worstRelative < 1e-5, "analysis and synthesis do not join up: the worst"
                + " sample is " + worstRelative + " of the peak, which is uncancelled"
                + " time-domain aliasing and is heard as a buzz at the block rate");
        System.out.printf("MDCT round trip: %d blocks, worst error %.2e of peak%n",
                blocksChecked, worstRelative);
    }

    // ------------------------------------------------------- bitstream validity

    /**
     * Every packet the encoder can be made to produce, parsed and decoded.
     *
     * <p>Starts from {@link OpusEncoder#encode}, not from {@link CeltEncoder},
     * because the framing byte is part of what a decoder has to accept and a
     * test that fed frames straight to {@link CeltDecoder} would pass over a
     * table of contents byte naming the wrong configuration.
     *
     * <p>The range-state comparison is the real assertion. Decoding without
     * throwing says only that the decoder found symbols where it expected them;
     * the range state says every one of those symbols was the symbol the
     * encoder wrote.
     */
    @Test
    @DisplayName("every packet it produces parses, decodes, and leaves the decoder in the encoder's state")
    void everyPacketParsesAndDecodesToTheStateItWasWrittenIn() {
        int[] bitrates = {6_000, 12_000, 24_000, 32_000, 48_000, 64_000,
            96_000, 128_000, 192_000, 256_000, 400_000, 510_000};
        int[] frameSizes = {120, 240, 480, 960};

        int framesChecked = 0;
        int rangeMismatches = 0;
        int shortestPacket = Integer.MAX_VALUE;
        int longestPacket = 0;
        Random random = new Random(0x5EED);

        for (int channels = 1; channels <= 2; channels++) {
            for (int frameSize : frameSizes) {
                for (int bitrate : bitrates) {
                    int micros = frameSize * 1_000_000 / OpusEncoder.CODEC_RATE;
                    OpusEncoder encoder = new OpusEncoder(48_000, channels, bitrate, micros);
                    OpusDecoder decoder = new OpusDecoder(48_000, channels);
                    byte[] packet = new byte[encoder.maxPacketBytes()];
                    short[] decoded = new short[channels * frameSize];

                    for (int kind = 0; kind < SIGNAL_KINDS; kind++) {
                        encoder.reset();
                        decoder.reset();
                        for (int f = 0; f < 20; f++) {
                            short[] source = signal(kind, channels, frameSize, f, random);
                            int length = encoder.encode(source, 0, frameSize, packet, 0);
                            assertTrue(length > 0, "a 48 kHz encoder fed exactly one frame must"
                                    + " produce exactly one packet, and produced nothing");

                            OpusPacket parsed = OpusPacket.parse(packet, 0, length);
                            assertEquals(OpusPacket.Mode.CELT, parsed.mode(),
                                    "the table of contents byte names " + parsed.mode()
                                    + ", and this encoder emits CELT only");
                            assertEquals(frameSize, parsed.frameSamples48k(),
                                    "the packet says it holds " + parsed.frameSamples48k()
                                    + " samples and the encoder was asked for " + frameSize);
                            assertEquals(channels == 2, parsed.stereo(),
                                    "the packet's stereo flag disagrees with the encoder");
                            assertEquals(1, parsed.frameCount(),
                                    "this encoder emits one frame per packet");

                            int produced = decoder.decode(packet, 0, length, decoded, 0);
                            assertEquals(frameSize, produced,
                                    "the decoder produced " + produced + " samples from a "
                                    + frameSize + "-sample packet");
                            if (encoder.finalRange() != decoder.finalRange()) {
                                rangeMismatches++;
                            }
                            shortestPacket = Math.min(shortestPacket, length);
                            longestPacket = Math.max(longestPacket, length);
                            framesChecked++;
                        }
                    }
                }
            }
        }

        assertEquals(2 * frameSizes.length * bitrates.length * SIGNAL_KINDS * 20, framesChecked,
                "the sweep did not encode every combination it was written to cover");
        assertTrue(framesChecked > 10_000,
                "only " + framesChecked + " frames were checked, which is not a sweep");
        assertEquals(0, rangeMismatches, rangeMismatches + " of " + framesChecked
                + " frames left the decoder's range coder in a different state from the"
                + " encoder's, which means at least one symbol was read as something other"
                + " than what was written and everything after it in that frame is noise");
        System.out.printf("bitstream validity: %d frames over %d bitrates, %d frame sizes,"
                + " mono and stereo, %d signal kinds; packets %d to %d bytes; 0 range mismatches%n",
                framesChecked, bitrates.length, frameSizes.length, SIGNAL_KINDS,
                shortestPacket, longestPacket);
    }

    // ------------------------------------------------------------- self round trip

    /**
     * The frame a sound starts on codes its energy outright, and the frames after
     * it predict from the one before.
     *
     * <p>Coarse energy is a difference from the previous frame at up to 0.9 of its
     * value, so on the frame where silence becomes music there is nothing to take
     * the difference from and every band has to code its full level. That is what
     * intra energy is: {@code alpha} set to zero for one frame. The encoder codes
     * the frame both ways and keeps the shorter, and the property worth pinning
     * is that it is really choosing -- an encoder stuck on inter spends a
     * disproportionate share of the attack frame on energy alone, and one stuck on
     * intra pays for that on every frame of the sustain.
     *
     * <p>What a listener hears if the choice is not made: at low rates the attack
     * frame runs out of bits for its shape, so the transient arrives dull and the
     * next frame or two arrive at the wrong level as the predictor catches up.
     */
    @Test
    @DisplayName("the frame a sound starts on codes its energy outright")
    void theAttackFrameCodesItsEnergyIntra() {
        int frames = 30;
        int onset = 15;
        short[] source = new short[2 * FRAME_48K * frames];
        for (int i = onset * FRAME_48K; i < frames * FRAME_48K; i++) {
            short v = (short) Math.rint(20_000 * Math.sin(2 * Math.PI * 700 * i / 48_000.0));
            source[2 * i] = v;
            source[2 * i + 1] = v;
        }

        OpusEncoder encoder = new OpusEncoder(48_000, 2, 128_000);
        byte[] packet = new byte[encoder.maxPacketBytes()];
        boolean[] intra = new boolean[frames];
        int intraCount = 0;
        for (int f = 0; f < frames; f++) {
            encoder.encode(source, f * 2 * FRAME_48K, FRAME_48K, packet, 0);
            intra[f] = encoder.lastFrameCodedIntra();
            if (intra[f]) {
                intraCount++;
            }
        }

        StringBuilder pattern = new StringBuilder();
        for (boolean b : intra) {
            pattern.append(b ? 'I' : '.');
        }
        System.out.println("intra energy over a silence-to-tone attack: " + pattern);
        assertTrue(intra[onset], "the frame the tone starts on was coded predicting from a"
                + " frame of silence, so its whole envelope is coded as a jump of twenty steps"
                + " per band: " + pattern);
        assertEquals(1, intraCount, "exactly one frame of this fixture should need intra"
                + " energy, the attack, and " + intraCount + " were coded that way: " + pattern
                + ". More than one means the two-pass choice is not choosing on cost.");
    }

    @Test
    @DisplayName("a tone comes back as the same tone")
    void aToneSurvivesTheRoundTrip() {
        Measurement stereo = roundTrip(tone(2, 150, 1000.0), 2, 128_000);
        Measurement mono = roundTrip(tone(1, 150, 1000.0), 1, 64_000);
        System.out.printf("tone: stereo 128k %.2f dB, mono 64k %.2f dB%n",
                stereo.snrDb, mono.snrDb);
        assertTrue(stereo.snrDb > 22.0, "a 1 kHz tone at 128 kb/s stereo came back at only "
                + stereo.snrDb + " dB, which is a tone with audible roughness on it");
        assertTrue(mono.snrDb > 22.0, "a 1 kHz tone at 64 kb/s mono came back at only "
                + mono.snrDb + " dB");
        assertTrue(Math.abs(stereo.levelDb) < 1.0, "the decoded tone is " + stereo.levelDb
                + " dB from the source's level, so the energy envelope is not being coded"
                + " at the level it was measured at");
    }

    @Test
    @DisplayName("a sweep and a burst of clicks come back without smearing")
    void transientsSurviveTheRoundTrip() {
        Measurement chirp = roundTrip(chirp(2, 150), 2, 128_000);
        Measurement clicks = roundTrip(clicks(2, 150), 2, 128_000);
        System.out.printf("transients: chirp %.2f dB, clicks %.2f dB%n",
                chirp.snrDb, clicks.snrDb);
        assertTrue(chirp.snrDb > 18.0, "a sweep came back at only " + chirp.snrDb + " dB");
        assertTrue(clicks.snrDb > 24.0, "a burst of clicks came back at only " + clicks.snrDb
                + " dB, which is the pre-echo a transient frame exists to prevent: the"
                + " quantisation noise of a 20 ms transform spread over the 20 ms before"
                + " the attack");
    }

    /**
     * Noise is measured by level, not by waveform, and that is the honest test
     * rather than a lowered bar.
     *
     * <p>CELT codes a band as a direction on a sphere with a handful of pulses.
     * On a tone that direction is nearly the signal; on white noise it is one of
     * millions of equally plausible directions, and the codec is not trying to
     * pick the one the source happened to be on. So the waveform SNR of coded
     * noise is a few dB by design, and a threshold set on it would be measuring
     * the wrong thing. What must survive is the spectral level, because that is
     * what a listener hears, and a level that drifted would be heard as the
     * noise floor of a recording breathing.
     */
    @Test
    @DisplayName("noise comes back at the level it went in at")
    void noiseComesBackAtTheRightLevel() {
        Measurement stereo = roundTrip(noise(2, 150), 2, 128_000);
        Measurement mono = roundTrip(noise(1, 150), 1, 64_000);
        System.out.printf("white noise: stereo 128k level %+.2f dB (SNR %.2f dB),"
                + " mono 64k level %+.2f dB (SNR %.2f dB)%n",
                stereo.levelDb, stereo.snrDb, mono.levelDb, mono.snrDb);
        assertTrue(Math.abs(stereo.levelDb) < 2.0, "coded noise came back " + stereo.levelDb
                + " dB from the level it went in at");
        assertTrue(Math.abs(mono.levelDb) < 2.0, "coded noise came back " + mono.levelDb
                + " dB from the level it went in at");
        assertTrue(stereo.snrDb > 1.0, "coded noise correlates with the source at only "
                + stereo.snrDb + " dB, which means the shape is not being coded at all");
    }

    /**
     * Silence has to be exactly silence, not nearly.
     *
     * <p>A frame of digital silence sets the silence flag, and after it the
     * encoder writes nothing at all -- not a transient flag, not an energy, not
     * a band. Both ends reach that state by charging their bit counters for the
     * whole frame. An encoder that forgot to charge would go on writing symbols
     * into a frame the decoder has stopped reading, and the two range coders
     * would part company there.
     */
    @Test
    @DisplayName("silence comes back as silence and nothing else")
    void silenceComesBackExactlySilent() {
        int frames = 40;
        short[] source = new short[2 * FRAME_48K * frames];
        OpusEncoder encoder = new OpusEncoder(48_000, 2, 128_000);
        OpusDecoder decoder = new OpusDecoder(48_000, 2);
        byte[] packet = new byte[encoder.maxPacketBytes()];
        short[] decoded = new short[2 * FRAME_48K];

        int nonZero = 0;
        int checked = 0;
        for (int f = 0; f < frames; f++) {
            int length = encoder.encode(source, f * 2 * FRAME_48K, FRAME_48K, packet, 0);
            assertEquals(encoder.maxPacketBytes(), length,
                    "a silent frame at a constant bitrate is still a whole frame");
            decoder.decode(packet, 0, length, decoded, 0);
            assertEquals(encoder.finalRange(), decoder.finalRange(),
                    "frame " + f + " of silence left the two range coders in different states,"
                    + " which is what happens when only one end charges itself for the"
                    + " rest of the frame");
            if (f >= 2) {
                for (short s : decoded) {
                    if (s != 0) {
                        nonZero++;
                    }
                }
                checked += decoded.length;
            }
        }
        assertTrue(checked > 50_000, "only " + checked + " samples of silence were examined");
        assertEquals(0, encoder.pendingSamples(), "a 48 kHz encoder fed whole frames must"
                + " buffer nothing, and is holding " + encoder.pendingSamples() + " samples");
        assertEquals(0, nonZero, nonZero + " of " + checked + " samples of decoded silence"
                + " were not zero, so the encoder is putting something into a frame it"
                + " declared empty");
        System.out.printf("silence: %d samples decoded, all exactly zero%n", checked);
    }

    /**
     * Real programme material, which is the only fixture that has all of it at
     * once: transients, tonal passages, wide stereo and a spectrum that goes to
     * the top of the band.
     *
     * <p>The tracks are 44.1 kHz and are handed to the encoder as though they
     * were 48 kHz, so they play eight per cent fast. That is deliberate and
     * costs the measurement nothing: what it needs is real music, not the right
     * pitch, and resampling first would put a second codec's worth of error into
     * the reference the SNR is measured against.
     */
    @Test
    @DisplayName("real music comes back recognisable")
    void realMusicSurvivesTheRoundTrip() {
        short[] stereo = music(2, 30);
        short[] mono = toMono(stereo);
        Measurement wide = roundTrip(stereo, 2, 128_000);
        Measurement narrow = roundTrip(mono, 1, 64_000);
        System.out.printf("music: stereo 128k %.2f dB (level %+.2f dB), mono 64k %.2f dB"
                + " (level %+.2f dB), %.1f s%n",
                wide.snrDb, wide.levelDb, narrow.snrDb, narrow.levelDb,
                stereo.length / 2.0 / OpusEncoder.CODEC_RATE);
        assertTrue(wide.snrDb > 15.0, "30 seconds of music at 128 kb/s stereo came back at only "
                + wide.snrDb + " dB");
        assertTrue(narrow.snrDb > 15.0, "30 seconds of music at 64 kb/s mono came back at only "
                + narrow.snrDb + " dB");
        assertTrue(Math.abs(wide.levelDb) < 1.0, "the decoded music is " + wide.levelDb
                + " dB from the source's level");
    }

    /**
     * The control for every signal-to-noise figure above.
     *
     * <p>The same measurement, on the same decoded audio, misaligned by half a
     * frame. If it still passed, the numbers above would be measuring the loudness
     * of two recordings rather than whether one is the other. It must fail.
     */
    @Test
    @DisplayName("the signal-to-noise measurement can tell a decode from a near miss")
    void theMeasurementFailsOnAMisalignedDecode() {
        short[] source = music(2, 8);
        Measurement aligned = roundTrip(source, 2, 128_000);
        Measurement misaligned = roundTrip(source, 2, 128_000, CODEC_DELAY + 480);
        System.out.printf("control: aligned %.2f dB, misaligned by 10 ms %.2f dB%n",
                aligned.snrDb, misaligned.snrDb);
        assertTrue(aligned.snrDb > 15.0,
                "the fixture must pass when aligned or the control proves nothing");
        assertTrue(misaligned.snrDb < 5.0, "the same audio shifted by half a frame still"
                + " measured " + misaligned.snrDb + " dB, so this measurement is not"
                + " distinguishing a decode from anything else at the same level");
    }

    // ------------------------------------------------------------------ resampling

    /**
     * The game's effects are 11025 and 22050 Hz, and neither divides 48000, so
     * they go through the resampler rather than the reference's zero-stuffing.
     *
     * <p>Measured against an analytically generated 48 kHz tone rather than
     * against a resampled copy of the input, because a reference produced by the
     * same resampler being tested would agree with it however wrong both were.
     */
    @Test
    @DisplayName("a sound effect recorded at 11 kHz comes back at the right pitch")
    void resampledInputComesBackAtTheRightPitch() {
        int[] rates = {11_025, 22_050, 44_100, 24_000, 16_000, 8_000};
        int checked = 0;
        double worst = Double.POSITIVE_INFINITY;
        StringBuilder report = new StringBuilder();
        for (int rate : rates) {
            int seconds = 3;
            int count = rate * seconds;
            short[] source = new short[count];
            for (int i = 0; i < count; i++) {
                source[i] = (short) Math.rint(9000 * Math.sin(2 * Math.PI * 1000 * i / rate));
            }
            OpusEncoder encoder = new OpusEncoder(rate, 1, 64_000);
            OpusDecoder decoder = new OpusDecoder(48_000, 1);
            byte[] packet = new byte[encoder.maxPacketBytes()];
            short[] frame = new short[FRAME_48K];
            short[] decoded = new short[48_000 * (seconds + 1)];
            int written = 0;
            int packets = 0;
            int chunk = encoder.frameSamples();
            for (int at = 0; at + chunk <= count; at += chunk) {
                int length = encoder.encode(source, at, chunk, packet, 0);
                if (length == 0) {
                    continue;
                }
                packets++;
                int produced = decoder.decode(packet, 0, length, frame, 0);
                System.arraycopy(frame, 0, decoded, written, produced);
                written += produced;
            }
            assertTrue(packets > 100, "only " + packets + " packets came out of " + seconds
                    + " seconds of " + rate + " Hz input, so the resampler is not keeping up"
                    + " with the frame rate");
            // A resampler whose rate was slightly wrong would still produce
            // packets and still sound right; what it would do is pile up the
            // difference here, a few samples per frame, until the encoder was
            // minutes behind the input.
            assertTrue(encoder.pendingSamples() < FRAME_48K, "after " + seconds + " seconds of "
                    + rate + " Hz input the encoder is still holding "
                    + encoder.pendingSamples() + " resampled samples, which is more than a"
                    + " frame: the resampler is producing samples faster than the frame rate"
                    + " consumes them");

            double best = Double.NEGATIVE_INFINITY;
            for (int delay = 0; delay < 700; delay++) {
                double signal = 0;
                double error = 0;
                for (int i = 2 * FRAME_48K; i + delay < written; i++) {
                    double want = 9000 * Math.sin(2 * Math.PI * 1000 * i / 48_000.0);
                    double got = decoded[i + delay];
                    signal += want * want;
                    error += (want - got) * (want - got);
                }
                best = Math.max(best, 10 * Math.log10(signal / Math.max(error, 1e-12)));
            }
            report.append(String.format("%d Hz %.1f dB; ", rate, best));
            worst = Math.min(worst, best);
            checked++;
        }
        assertEquals(rates.length, checked, "not every input rate was tried");
        System.out.println("resampled input: " + report);
        assertTrue(worst > 20.0, "the worst input rate reconstructed a 1 kHz tone at only "
                + worst + " dB, which is a resampler that has moved the tone or shaved the"
                + " band it lives in");
    }

    // ---------------------------------------------------------------- bitrate

    @Test
    @DisplayName("the packets add up to the bitrate that was asked for")
    void theAchievedBitrateIsWithinTenPercentOfTheTarget() {
        short[] stereo = music(2, 30);
        short[] mono = toMono(stereo);
        double wide = achievedBitrate(stereo, 2, 128_000);
        double narrow = achievedBitrate(mono, 1, 64_000);
        System.out.printf("bitrate: 128k stereo achieved %.2f kb/s, 64k mono achieved %.2f kb/s,"
                + " over %.1f s%n", wide / 1000, narrow / 1000,
                stereo.length / 2.0 / OpusEncoder.CODEC_RATE);
        assertTrue(Math.abs(wide - 128_000) < 12_800, "asked for 128 kb/s stereo and got "
                + wide / 1000 + " kb/s, which is outside ten per cent");
        assertTrue(Math.abs(narrow - 64_000) < 6_400, "asked for 64 kb/s mono and got "
                + narrow / 1000 + " kb/s, which is outside ten per cent");
    }

    // -------------------------------------------------------------- determinism

    /**
     * Two encoders given the same samples must produce the same bytes.
     *
     * <p>The pack is built once and shipped, so a codec that produced different
     * bytes on different runs would make every build of the game's asset pack
     * differ from the last for no reason, and would make any regression test
     * that compares packs useless. Nothing here may reach for a clock, a hash
     * ordering or an unseeded generator.
     */
    @Test
    @DisplayName("encoding the same audio twice gives the same bytes")
    void encodingTheSameInputTwiceGivesTheSameBytes() {
        short[] source = music(2, 10);
        byte[] first = encodeAll(source, 2, 128_000);
        byte[] second = encodeAll(source, 2, 128_000);
        assertTrue(first.length > 100_000,
                "the fixture produced only " + first.length + " bytes, which proves little");
        assertArrayEquals(first, second, "two runs over the same audio produced different"
                + " bytes, so something in the encode path is not deterministic");

        // And a reset must put an encoder back where a fresh one starts, or a
        // caller reusing one across clips would get a different pack from a
        // caller that made a new one per clip.
        OpusEncoder reused = new OpusEncoder(48_000, 2, 128_000);
        byte[] packet = new byte[reused.maxPacketBytes()];
        for (int f = 0; f < 20; f++) {
            reused.encode(source, f * 2 * FRAME_48K, FRAME_48K, packet, 0);
        }
        reused.reset();
        byte[] third = encodeAll(source, 2, 128_000, reused);
        assertArrayEquals(first, third, "an encoder that has been reset produced different"
                + " bytes from a fresh one, so reset is not clearing everything");
        System.out.printf("determinism: %d bytes, identical over two runs and across a reset%n",
                first.length);
    }

    // ------------------------------------------------------------------ interop

    /**
     * The gate that matters: ffmpeg's decoder, not ours.
     *
     * <p>Our packets are muxed into a real Ogg Opus file to RFC 7845, handed to
     * ffmpeg, and the PCM it produces is compared with what went in. Two things
     * are being tested at once and both are worth having. ffmpeg refusing the
     * file would mean the framing or the identification header is wrong; ffmpeg
     * accepting it and producing the wrong audio would mean the codec layer is
     * wrong in a way our own decoder shares.
     *
     * <p>No delay search here. The identification header declares a pre-skip of
     * {@value #CODEC_DELAY} samples, which is the codec's whole delay, so a
     * correct player hands back audio already aligned with the source -- and
     * that alignment is itself part of what is being checked.
     *
     * <p>The container comes from {@link OggWriter}, the one this module ships,
     * rather than from a copy written for the test. Two implementations of the
     * same container would let a fault in the shipped one hide: the test would
     * prove that a file nobody will ever produce is readable.
     */
    @Test
    @DisplayName("ffmpeg decodes what this encoder writes, and gets the right audio back")
    void ffmpegDecodesWhatThisEncoderWrites() {
        Path ffmpeg = findFfmpeg();
        Assumptions.assumeTrue(ffmpeg != null,
                "No ffmpeg found. Set -Dffmpeg.path=/path/to/ffmpeg or put it on PATH.");
        short[] musicSource = music(2, 20);

        int checked = 0;
        StringBuilder report = new StringBuilder();
        double worst = Double.POSITIVE_INFINITY;
        for (Interop run : new Interop[] {
            new Interop("music stereo 128k 20ms", musicSource, 2, 128_000, 20_000, 15.0),
            new Interop("music stereo 128k 10ms", musicSource, 2, 128_000, 10_000, 15.0),
            new Interop("music mono 64k 20ms", toMono(musicSource), 1, 64_000, 20_000, 15.0),
            new Interop("tone stereo 128k 20ms", tone(2, 200, 1000.0), 2, 128_000, 20_000, 22.0),
        }) {
            double snr = ffmpegRoundTrip(ffmpeg, run);
            report.append(String.format("%s %.2f dB; ", run.name, snr));
            assertTrue(snr > run.minSnrDb, "ffmpeg decoded our " + run.name + " stream at only "
                    + snr + " dB against the source, and the encoder is wrong whatever our"
                    + " own decoder says");
            worst = Math.min(worst, snr);
            checked++;
        }
        assertEquals(4, checked, "not every interop case ran");
        System.out.println("ffmpeg interop: " + report);
        assertTrue(worst > 15.0, "the worst interop case was " + worst + " dB");
    }

    private record Interop(String name, short[] source, int channels, int bitrate,
            int frameMicros, double minSnrDb) {}

    private double ffmpegRoundTrip(Path ffmpeg, Interop run) {
        OpusEncoder encoder = new OpusEncoder(48_000, run.channels, run.bitrate, run.frameMicros);
        int frameSize = encoder.frameSize48k();
        byte[] packet = new byte[encoder.maxPacketBytes()];
        int frames = run.source.length / (run.channels * frameSize);
        assertTrue(frames > 100, "the interop fixture is only " + frames + " frames long");

        java.io.ByteArrayOutputStream container = new java.io.ByteArrayOutputStream();
        int packets = 0;
        try (OggWriter ogg = new OggWriter(container, run.channels, OpusEncoder.CODEC_RATE,
                CODEC_DELAY, 0x43484f4e)) {
            for (int f = 0; f < frames; f++) {
                int length = encoder.encode(run.source, f * run.channels * frameSize,
                        frameSize, packet, 0);
                if (length == 0) {
                    continue;
                }
                packets++;
                // A running total of decodable 48 kHz samples, pre-skip included,
                // which is what RFC 7845 section 4 means by a granule position.
                ogg.write(packet, 0, length, (long) packets * frameSize);
            }
            ogg.finish();
        }
        assertTrue(packets > 100, "only " + packets + " packets reached the container");

        Path directory = null;
        try {
            directory = Files.createTempDirectory("chonk-opus-interop");
            Path stream = directory.resolve("stream.opus");
            Path raw = directory.resolve("decoded.raw");
            Files.write(stream, container.toByteArray());
            runFfmpeg(ffmpeg, stream, raw);
            short[] decoded = readLittleEndian(Files.readAllBytes(raw));
            assertTrue(decoded.length > run.channels * frameSize * (frames - 4),
                    "ffmpeg produced " + decoded.length + " samples from a stream of "
                    + frames + " frames of " + frameSize + ", so it stopped early");
            return snr(run.source, decoded, run.channels, 0,
                    SETTLE_FRAMES * frameSize * run.channels);
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("ffmpeg interop for " + run.name + " failed", e);
        } finally {
            deleteTree(directory);
        }
    }

    private static void runFfmpeg(Path ffmpeg, Path input, Path output)
            throws IOException, InterruptedException {
        Path log = output.resolveSibling("ffmpeg.log");
        Process process = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-nostdin",
                "-loglevel", "error", "-y", "-i", input.toString(),
                "-f", "s16le", "-acodec", "pcm_s16le", output.toString())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "ffmpeg did not finish in two minutes");
        String message = Files.exists(log) ? Files.readString(log).trim() : "";
        assertEquals(0, process.exitValue(), "ffmpeg refused the Ogg Opus file this encoder"
                + " wrote, which means the packets or the framing are not what RFC 6716 and"
                + " RFC 7845 describe. It said: " + message);
        assertTrue(Files.exists(output) && Files.size(output) > 0,
                "ffmpeg produced no audio. It said: " + message);
    }

    private static Path findFfmpeg() {
        List<String> candidates = new ArrayList<>();
        String configured = System.getProperty("ffmpeg.path", System.getenv("FFMPEG_PATH"));
        if (configured != null && !configured.isBlank()) {
            candidates.add(configured);
        }
        candidates.add("/opt/homebrew/bin/ffmpeg");
        candidates.add("/usr/local/bin/ffmpeg");
        candidates.add("/usr/bin/ffmpeg");
        String path = System.getenv("PATH");
        if (path != null) {
            for (String directory : path.split(java.io.File.pathSeparator)) {
                candidates.add(directory + java.io.File.separator + "ffmpeg");
            }
        }
        for (String candidate : candidates) {
            Path p = Paths.get(candidate);
            if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                return p;
            }
        }
        return null;
    }

    private static void deleteTree(Path directory) {
        if (directory == null) {
            return;
        }
        try (var entries = Files.list(directory)) {
            for (Path entry : entries.toList()) {
                Files.deleteIfExists(entry);
            }
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            // A leftover temporary directory is not worth failing a test over.
        }
    }

    // ------------------------------------------------------------------ fixtures

    /** How many kinds of signal the validity sweep tries. */
    private static final int SIGNAL_KINDS = 8;

    /**
     * One frame of one of the eight signal kinds the validity sweep uses.
     *
     * <p>They are chosen to reach the branches a musical fixture never does:
     * digital silence takes the silence flag, full scale and the alternating
     * pattern push the range coder against its buffer, the impulse train makes
     * every frame transient, and the very quiet high tone leaves the allocator
     * skipping most of the spectrum.
     */
    private static short[] signal(int kind, int channels, int frameSize, int frame,
            Random random) {
        short[] out = new short[channels * frameSize];
        for (int i = 0; i < frameSize; i++) {
            long t = (long) frame * frameSize + i;
            double v = switch (kind) {
                case 0 -> 0;
                case 1 -> 32767 * Math.sin(2 * Math.PI * 440 * t / 48_000.0);
                case 2 -> random.nextGaussian() * 8000;
                case 3 -> t % 480 == 0 ? 32767 : 0;
                case 4 -> 32767;
                case 5 -> i % 2 == 0 ? 32767 : -32768;
                case 6 -> random.nextInt(65_536) - 32_768;
                default -> 100 * Math.sin(2 * Math.PI * 19_000 * t / 48_000.0);
            };
            short s = (short) Math.max(-32768, Math.min(32767, Math.rint(v)));
            for (int c = 0; c < channels; c++) {
                out[i * channels + c] = s;
            }
        }
        return out;
    }

    private static short[] tone(int channels, int frames, double hz) {
        short[] out = new short[channels * FRAME_48K * frames];
        for (int i = 0; i < FRAME_48K * frames; i++) {
            double v = 10_000 * Math.sin(2 * Math.PI * hz * i / 48_000.0);
            for (int c = 0; c < channels; c++) {
                out[i * channels + c] = (short) Math.rint(v);
            }
        }
        return out;
    }

    private static short[] noise(int channels, int frames) {
        Random random = new Random(0x4242);
        short[] out = new short[channels * FRAME_48K * frames];
        for (int i = 0; i < FRAME_48K * frames; i++) {
            for (int c = 0; c < channels; c++) {
                double v = random.nextGaussian() * 4000;
                out[i * channels + c] = (short) Math.max(-32768, Math.min(32767, Math.rint(v)));
            }
        }
        return out;
    }

    private static short[] chirp(int channels, int frames) {
        short[] out = new short[channels * FRAME_48K * frames];
        for (int i = 0; i < FRAME_48K * frames; i++) {
            double t = i / 48_000.0;
            double v = 9000 * Math.sin(2 * Math.PI * (100 * t + 3000 * t * t));
            for (int c = 0; c < channels; c++) {
                out[i * channels + c] = (short) Math.rint(v);
            }
        }
        return out;
    }

    private static short[] clicks(int channels, int frames) {
        short[] out = new short[channels * FRAME_48K * frames];
        for (int i = 0; i < FRAME_48K * frames; i++) {
            double t = i / 48_000.0;
            double v = i % 4800 < 40 ? 20_000 * Math.sin(2 * Math.PI * 3000 * t) : 0;
            for (int c = 0; c < channels; c++) {
                out[i * channels + c] = (short) Math.rint(v);
            }
        }
        return out;
    }

    private static short[] toMono(short[] stereo) {
        short[] out = new short[stereo.length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (short) ((stereo[2 * i] + stereo[2 * i + 1]) / 2);
        }
        return out;
    }

    /**
     * A stretch of a real red book music track, as 48 kHz interleaved samples.
     *
     * <p>Skips when no music directory is configured, in the same shape the
     * conformance vectors use.
     */
    private static short[] music(int channels, int seconds) {
        Path directory = musicDirectory();
        Assumptions.assumeTrue(directory != null, "No music configured."
                + " Set -Dopus.music=/path/to/wavs to a directory of 16-bit WAV files.");
        List<Path> tracks;
        try (var entries = Files.list(directory)) {
            tracks = entries.filter(p -> p.toString().toLowerCase().endsWith(".wav")).sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Assumptions.assumeTrue(!tracks.isEmpty(),
                "the configured music directory " + directory + " holds no WAV files");

        // A fixed track and a fixed offset, so the numbers this suite reports are
        // comparable from run to run. Thirty seconds in, because the opening of a
        // CD track is often a fade from silence and would flatter the codec.
        short[] pcm = readWav(tracks.get(0), 30, seconds);
        assertTrue(pcm.length >= 2 * seconds * OpusEncoder.CODEC_RATE / 2,
                "the music fixture is only " + pcm.length + " samples, which is not the "
                + seconds + " seconds it was asked for");
        if (channels == 2) {
            return pcm;
        }
        return toMono(pcm);
    }

    private static Path musicDirectory() {
        String configured = System.getProperty("opus.music", System.getenv("OPUS_MUSIC"));
        if (configured == null || configured.isBlank()) {
            return null;
        }
        Path path = Paths.get(configured);
        return Files.isDirectory(path) ? path : null;
    }

    /**
     * A 16-bit PCM WAV file, walked chunk by chunk.
     *
     * <p>The sample rate in the header is deliberately ignored: see
     * {@link #realMusicSurvivesTheRoundTrip} for why the tracks are handed to the
     * encoder as though they were already at 48 kHz.
     */
    private static short[] readWav(Path file, int skipSeconds, int seconds) {
        byte[] raw;
        try {
            raw = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x46464952, buffer.getInt(0), file + " is not a RIFF file");
        assertEquals(0x45564157, buffer.getInt(8), file + " is not a WAVE file");
        int at = 12;
        int channels = 0;
        int bits = 0;
        while (at + 8 <= raw.length) {
            int id = buffer.getInt(at);
            int size = buffer.getInt(at + 4);
            int body = at + 8;
            if (id == 0x20746d66) {
                channels = buffer.getShort(body + 2) & 0xFFFF;
                bits = buffer.getShort(body + 14) & 0xFFFF;
            } else if (id == 0x61746164) {
                assertEquals(16, bits, file + " is " + bits + "-bit and this reads 16-bit PCM");
                assertEquals(2, channels, file + " has " + channels + " channels");
                int frameBytes = 4;
                int from = body + Math.min(size, skipSeconds * 44_100 * frameBytes);
                int to = Math.min(body + size, from + seconds * 48_000 * frameBytes);
                to -= (to - from) % frameBytes;
                short[] pcm = new short[(to - from) / 2];
                buffer.position(from);
                buffer.asShortBuffer().get(pcm);
                return pcm;
            }
            at = body + size + (size & 1);
        }
        throw new IllegalStateException(file + " has no data chunk");
    }

    private static short[] readLittleEndian(byte[] raw) {
        short[] out = new short[raw.length / 2];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out);
        return out;
    }

    // ------------------------------------------------------------------ measuring

    private record Measurement(double snrDb, double levelDb) {}

    private static Measurement roundTrip(short[] source, int channels, int bitrate) {
        return roundTrip(source, channels, bitrate, CODEC_DELAY);
    }

    /**
     * Encodes, decodes with our own decoder, and measures.
     *
     * <p>Asserts the range-state agreement as it goes, so every measurement in
     * this class is also a bit-exactness check and a fault shows up as the frame
     * it happened on rather than as a poor number at the end.
     */
    private static Measurement roundTrip(short[] source, int channels, int bitrate, int delay) {
        OpusEncoder encoder = new OpusEncoder(48_000, channels, bitrate);
        OpusDecoder decoder = new OpusDecoder(48_000, channels);
        byte[] packet = new byte[encoder.maxPacketBytes()];
        short[] frame = new short[channels * FRAME_48K];
        int frames = source.length / (channels * FRAME_48K);
        assertTrue(frames > 20, "a round trip over " + frames + " frames proves little");

        short[] decoded = new short[channels * FRAME_48K * frames];
        int written = 0;
        for (int f = 0; f < frames; f++) {
            int length = encoder.encode(source, f * channels * FRAME_48K, FRAME_48K, packet, 0);
            assertTrue(length > 0, "frame " + f + " produced no packet");
            int produced = decoder.decode(packet, 0, length, frame, 0);
            assertEquals(encoder.finalRange(), decoder.finalRange(),
                    "frame " + f + ": the decoder ended in range state " + decoder.finalRange()
                    + " and the encoder in " + encoder.finalRange() + ", so at least one"
                    + " symbol was read as something other than what was written");
            assertEnvelopesAgree(encoder, decoder, channels, f);
            System.arraycopy(frame, 0, decoded, written, produced * channels);
            written += produced * channels;
        }

        int skip = SETTLE_FRAMES * FRAME_48K * channels;
        double snr = snr(source, decoded, channels, delay, skip);
        double sourceEnergy = 0;
        double decodedEnergy = 0;
        for (int i = skip; i + delay * channels < written; i++) {
            double s = source[i];
            double d = decoded[i + delay * channels];
            sourceEnergy += s * s;
            decodedEnergy += d * d;
        }
        double level = 10 * Math.log10(Math.max(decodedEnergy, 1e-12)
                / Math.max(sourceEnergy, 1e-12));
        return new Measurement(snr, level);
    }

    /**
     * The quantised signal's envelope, on both sides, to the last bit.
     *
     * <p>Exactly equal, not close: the encoder and the decoder run the same
     * recurrence over the same symbols in the same order and in the same
     * precision, so any difference at all is a difference in the code rather
     * than in the arithmetic. It is checked per frame because the fault it
     * catches is silent in the frame it happens on and only shows up as drift
     * in the frames after it.
     */
    private static void assertEnvelopesAgree(OpusEncoder encoder, OpusDecoder decoder,
            int channels, int frame) {
        float[] written = encoder.quantisedBandEnergy();
        float[] read = decoder.bandEnergy();
        int bands = CeltMode.BAND_COUNT;
        for (int c = 0; c < channels; c++) {
            for (int i = 0; i < bands; i++) {
                assertEquals(written[c * bands + i], read[c * bands + i], 0.0f,
                        "frame " + frame + ", channel " + c + ", band " + i
                        + ": the encoder settled on energy " + written[c * bands + i]
                        + " and the decoder reconstructed " + read[c * bands + i]
                        + ", so the two ends will predict the next frame from"
                        + " different envelopes and the level of that band will drift");
            }
        }
    }

    /**
     * Signal to noise of {@code decoded} against {@code source}, with the decoded
     * stream shifted forward by {@code delay} samples per channel.
     */
    private static double snr(short[] source, short[] decoded, int channels, int delay,
            int skip) {
        double signal = 0;
        double error = 0;
        int counted = 0;
        for (int i = skip; i < source.length && i + delay * channels < decoded.length; i++) {
            double s = source[i];
            double d = decoded[i + delay * channels];
            signal += s * s;
            error += (s - d) * (s - d);
            counted++;
        }
        assertTrue(counted > 10_000,
                "only " + counted + " samples were compared, which measures nothing");
        assertTrue(signal > 0, "the source is silent, so its signal-to-noise ratio is not a"
                + " measurement of anything");
        return 10 * Math.log10(signal / Math.max(error, 1e-12));
    }

    private static double achievedBitrate(short[] source, int channels, int bitrate) {
        OpusEncoder encoder = new OpusEncoder(48_000, channels, bitrate);
        byte[] packet = new byte[encoder.maxPacketBytes()];
        int frames = source.length / (channels * FRAME_48K);
        assertTrue(frames >= 1500, "the bitrate fixture is only " + frames
                + " frames, and the measurement was asked for at least thirty seconds");
        long bytes = 0;
        int packets = 0;
        for (int f = 0; f < frames; f++) {
            bytes += encoder.encode(source, f * channels * FRAME_48K, FRAME_48K, packet, 0);
            packets++;
        }
        return bytes * 8.0 / (packets * (FRAME_48K / 48_000.0));
    }

    private static byte[] encodeAll(short[] source, int channels, int bitrate) {
        return encodeAll(source, channels, bitrate, new OpusEncoder(48_000, channels, bitrate));
    }

    private static byte[] encodeAll(short[] source, int channels, int bitrate,
            OpusEncoder encoder) {
        byte[] packet = new byte[encoder.maxPacketBytes()];
        int frames = source.length / (channels * FRAME_48K);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int f = 0; f < frames; f++) {
            int length = encoder.encode(source, f * channels * FRAME_48K, FRAME_48K, packet, 0);
            out.write(packet, 0, length);
        }
        return out.toByteArray();
    }

}
