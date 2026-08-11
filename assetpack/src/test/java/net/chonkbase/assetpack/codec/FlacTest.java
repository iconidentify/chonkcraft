package net.chonkbase.assetpack.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the mixer receives after a sound has been through the pack.
 *
 * <p>FLAC is lossless, so there is exactly one right answer for every stream:
 * the samples that went in. That makes this suite unusual for this repository
 * in one way and completely ordinary in another. Unusual, because the property
 * is total -- not "close enough to hear no difference" but every one of the 244
 * million samples in the corpus, identical. Ordinary, because the failure this
 * guards against has the familiar shape: a stream that decodes without
 * complaining, produces plausible numbers, and plays as music that is not the
 * music on the disc. An off-by-one in the Rice quotient does exactly that. So
 * does reading the side channel at the frame's own bit depth instead of one
 * more, and so does rounding an LPC prediction before adding the residual
 * rather than after.
 *
 * <p>Nothing here asks whether a field was parsed. Every test drives
 * {@code encode} and {@code decode} end to end and compares samples, and where
 * a measurement is made -- a compression ratio, a checksum, the choice of
 * stereo decorrelation -- there is a second assertion proving the measurement
 * can tell the right answer from the wrong one.
 */
class FlacTest {

    @Test
    @DisplayName("a run of silence comes back as silence, and costs almost nothing to store")
    void silenceRoundTripsBitExact() {
        Flac.Pcm silence = new Flac.Pcm(44100, 2, 16, new int[44100 * 2]);
        byte[] encoded = Flac.encode(silence);

        assertEquals(silence, Flac.decode(encoded),
                "a second of stereo silence did not come back as silence");
        assertTrue(encoded.length < 500, "silence encoded to " + encoded.length
                + " bytes; CONSTANT subframes are meant to make a quiet second nearly free, and "
                + "the 487 effects are full of leading and trailing silence");
    }

    @Test
    @DisplayName("a square wave at both rails survives, including the samples at full negative")
    void aFullScaleSquareWaveRoundTripsBitExact() {
        int frames = 44100;
        int[] samples = new int[frames * 2];
        for (int i = 0; i < frames; i++) {
            boolean high = (i / 50) % 2 == 0;
            samples[2 * i] = high ? 32767 : -32768;
            samples[2 * i + 1] = high ? -32768 : 32767;
        }
        Flac.Pcm square = new Flac.Pcm(44100, 2, 16, samples);

        assertEquals(square, Flac.decode(Flac.encode(square)),
                "a full-scale square wave did not survive the round trip; -32768 is the one "
                        + "sixteen-bit value whose negation does not fit in sixteen bits");
    }

    @Test
    @DisplayName("two channels a whole scale apart survive, because the side channel gets its extra bit")
    void wideStereoSurvivesTheSideChannel() {
        int frames = 8000;
        int[] samples = new int[frames * 2];
        int widest = 0;
        for (int i = 0; i < frames; i++) {
            int left = (int) Math.round(32767 * Math.sin(2 * Math.PI * 300 * i / 44100.0));
            int right = -left;
            samples[2 * i] = left;
            samples[2 * i + 1] = right;
            widest = Math.max(widest, Math.abs(left - right));
        }
        assertTrue(widest > 32767, "the fixture must contain a left-minus-right difference that "
                + "does not fit in sixteen bits, or it cannot tell the two rules apart; the "
                + "widest it reached was " + widest);

        Flac.Pcm wide = new Flac.Pcm(44100, 2, 16, samples);
        assertEquals(wide, Flac.decode(Flac.encode(wide)),
                "wide stereo came back wrong: the side channel holds left minus right and needs "
                        + "seventeen bits, and at sixteen it wraps into a buzz that only appears "
                        + "on material far from mono");
    }

    @Test
    @DisplayName("white noise comes back exactly, and does not cost much more than storing it raw")
    void whiteNoiseRoundTripsBitExactAndDoesNotBloat() {
        Random random = new Random(20250726L);
        int[] samples = new int[100000];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = random.nextInt(65536) - 32768;
        }
        Flac.Pcm noise = new Flac.Pcm(44100, 2, 16, samples);
        byte[] encoded = Flac.encode(noise);

        assertEquals(noise, Flac.decode(encoded), "white noise did not round trip");
        assertTrue(encoded.length < noise.rawByteLength() * 1.01,
                "incompressible audio encoded to " + encoded.length + " bytes against "
                        + noise.rawByteLength() + " raw. VERBATIM subframes exist so that the worst "
                        + "case is the raw size plus frame headers, not a multiple of it");
    }

    @Test
    @DisplayName("a sound effect at eight bits and 11025 Hz comes back sample for sample")
    void anEightBitMonoSineRoundTripsBitExact() {
        int[] samples = new int[11025];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (int) Math.round(120 * Math.sin(2 * Math.PI * 440 * i / 11025.0));
        }
        Flac.Pcm effect = new Flac.Pcm(11025, 1, 8, samples);
        byte[] encoded = Flac.encode(effect);
        Flac.Pcm decoded = Flac.decode(encoded);

        assertEquals(effect, decoded, "an eight-bit effect at 11025 Hz did not round trip; that is "
                + "the shape 380 of the 383 entries in SFXDAT.SUD are stored in");
        assertEquals(11025, decoded.sampleRate(), "11025 Hz is not one of the eleven rates a FLAC "
                + "frame header can name in four bits, so it goes out in the sixteen-bit literal "
                + "form; getting that wrong plays every effect at the wrong pitch");
        assertEquals(8, decoded.bitsPerSample(), "the effect came back at a different bit depth");
    }

    @Test
    @DisplayName("a music track at sixteen bits, two channels and 44100 Hz comes back sample for sample")
    void aSixteenBitStereoSineRoundTripsBitExact() {
        int frames = 44100 * 2;
        int[] samples = new int[frames * 2];
        for (int i = 0; i < frames; i++) {
            samples[2 * i] = (int) Math.round(30000 * Math.sin(2 * Math.PI * 440 * i / 44100.0));
            samples[2 * i + 1] = (int) Math.round(30000 * Math.sin(2 * Math.PI * 660 * i / 44100.0));
        }
        Flac.Pcm music = new Flac.Pcm(44100, 2, 16, samples);

        assertEquals(music, Flac.decode(Flac.encode(music)),
                "sixteen-bit stereo at 44100 did not round trip; that is what every red book track "
                        + "on the disc is");
    }

    @Test
    @DisplayName("a stream one sample long is still a stream")
    void aSingleSampleStreamRoundTripsBitExact() {
        Flac.Pcm one = new Flac.Pcm(44100, 1, 16, new int[]{-12345});
        Flac.Pcm decoded = Flac.decode(Flac.encode(one));

        assertEquals(one, decoded, "a one-sample stream did not round trip");
        assertArrayEquals(new int[]{-12345}, decoded.samples(),
                "the single sample came back as something else");

        Flac.Pcm oneEightBit = new Flac.Pcm(11025, 1, 8, new int[]{77});
        assertEquals(oneEightBit, Flac.decode(Flac.encode(oneEightBit)),
                "a one-sample eight-bit stream did not round trip");
    }

    @Test
    @DisplayName("a stream with no samples at all encodes and decodes without inventing any")
    void anEmptyStreamRoundTripsBitExact() {
        Flac.Pcm empty = new Flac.Pcm(22050, 1, 8, new int[0]);
        Flac.Pcm decoded = Flac.decode(Flac.encode(empty));

        assertEquals(0, decoded.samples().length,
                "an empty stream decoded to " + decoded.samples().length + " samples");
        assertEquals(empty, decoded, "an empty stream did not round trip");
    }

    @Test
    @DisplayName("the checksum in the header is the checksum of the audio, and changes when the audio does")
    void streamInfoCarriesTheMd5OfTheSamplesThatWentIn() {
        int[] samples = new int[30000];
        Random random = new Random(4242L);
        for (int i = 0; i < samples.length; i++) {
            samples[i] = random.nextInt(4000) - 2000;
        }
        Flac.Pcm pcm = new Flac.Pcm(22050, 2, 16, samples);

        Flac.StreamInfo info = Flac.readStreamInfo(Flac.encode(pcm));
        assertEquals(md5OfLittleEndianSamples(samples, 2), info.md5Hex(),
                "STREAMINFO's MD5 is not the MD5 of the samples. Every reference tool checks this "
                        + "on the way past, so a wrong one condemns the whole pack");
        assertEquals(15000L, info.totalFrames(),
                "STREAMINFO reported the wrong number of sample frames");

        int[] altered = samples.clone();
        altered[17] += 1;
        Flac.StreamInfo other = Flac.readStreamInfo(
                Flac.encode(new Flac.Pcm(22050, 2, 16, altered)));
        assertNotEquals(info.md5Hex(), other.md5Hex(),
                "one sample changed by one and the MD5 did not move, so the checksum is measuring "
                        + "something other than the audio and would never catch a wrong decode");
    }

    @Test
    @DisplayName("two hundred streams of every shape the pack holds all come back unchanged")
    void everyRandomStreamRoundTripsBitExact() {
        Random random = new Random(19951109L);
        int[] rates = {8000, 11025, 22050, 44100, 48000, 3000};
        int checked = 0;
        long samplesChecked = 0;
        int compressedBelowRaw = 0;

        for (int trial = 0; trial < 200; trial++) {
            int channels = 1 + random.nextInt(4);
            int bitsPerSample = random.nextBoolean() ? 8 : 16;
            int frames = 1 + random.nextInt(9000);
            int sampleRate = rates[random.nextInt(rates.length)];
            int limit = 1 << (bitsPerSample - 1);
            int[] samples = new int[frames * channels];
            int walk = 0;
            switch (random.nextInt(4)) {
                case 0 -> {
                }
                case 1 -> {
                    for (int i = 0; i < samples.length; i++) {
                        samples[i] = random.nextInt(2 * limit) - limit;
                    }
                }
                case 2 -> {
                    for (int i = 0; i < samples.length; i++) {
                        walk = Math.clamp(walk + random.nextInt(9) - 4, -limit, limit - 1);
                        samples[i] = walk;
                    }
                }
                default -> {
                    for (int i = 0; i < samples.length; i++) {
                        samples[i] = (int) Math.round((limit - 1) * Math.sin(i * 0.01));
                    }
                }
            }

            Flac.Pcm pcm = new Flac.Pcm(sampleRate, channels, bitsPerSample, samples);
            byte[] encoded = Flac.encode(pcm);
            Flac.Pcm decoded = Flac.decode(encoded);
            assertEquals(pcm, decoded, "stream " + trial + " (" + channels + " channels, "
                    + bitsPerSample + " bit, " + frames + " frames at " + sampleRate
                    + " Hz) did not round trip");
            checked++;
            samplesChecked += samples.length;
            if (encoded.length < pcm.rawByteLength()) {
                compressedBelowRaw++;
            }
        }

        assertEquals(200, checked, "the sweep did not run two hundred streams");
        assertTrue(samplesChecked > 1_000_000, "the sweep only covered " + samplesChecked
                + " samples, which is too few to have exercised anything");
        assertTrue(compressedBelowRaw > 100, "only " + compressedBelowRaw + " of 200 streams came "
                + "out smaller than raw, so the fixtures are almost all incompressible and the "
                + "sweep is not testing the predictor at all");
    }

    @Test
    @DisplayName("thirty seconds of stereo tone fits in under a quarter of the space it took raw")
    void aThirtySecondStereoToneEncodesUnderAQuarterOfRaw() {
        int frames = 44100 * 30;
        int[] samples = new int[frames * 2];
        for (int i = 0; i < frames; i++) {
            samples[2 * i] = (int) Math.round(30000 * Math.sin(2 * Math.PI * 440 * i / 44100.0));
            samples[2 * i + 1] = (int) Math.round(30000 * Math.sin(2 * Math.PI * 660 * i / 44100.0));
        }
        Flac.Pcm pcm = new Flac.Pcm(44100, 2, 16, samples);
        byte[] encoded = Flac.encode(pcm);

        assertEquals(5_292_000, pcm.rawByteLength(),
                "the fixture is not thirty seconds of 44100 stereo");
        double ratio = 100.0 * encoded.length / pcm.rawByteLength();
        assertTrue(ratio < 25.0, String.format(
                "thirty seconds of tone encoded to %.1f%% of raw (%d bytes of %d)",
                ratio, encoded.length, pcm.rawByteLength()));
        assertEquals(pcm, Flac.decode(encoded),
                "the small stream is only worth having if it is still the same audio");
    }

    @Test
    @DisplayName("a two-and-a-bit kilobyte effect gets smaller, which is not what a default encoder does")
    void aShortEightBitEffectEncodesSmallerThanRaw() {
        int[] samples = shortEffect();
        Flac.Pcm effect = new Flac.Pcm(11025, 1, 8, samples);
        byte[] encoded = Flac.encode(effect);

        assertEquals(2904, effect.rawByteLength(),
                "the fixture is meant to be the size of entry 2 of SFXDAT.SUD, the effect a naive "
                        + "pipeline gets wrong by more than three times over");
        assertTrue(encoded.length < 2904, "a 2904-byte effect encoded to " + encoded.length
                + " bytes. ffmpeg's defaults make this same effect 9984 bytes, because a vorbis "
                + "comment and an eight-kilobyte padding block cost more than the audio does; "
                + "this encoder writes STREAMINFO and nothing else for exactly that reason");
        assertEquals(effect, Flac.decode(encoded), "the short effect did not round trip");
    }

    @Test
    @DisplayName("a second identical channel is nearly free, because the two are stored as a difference")
    void anIdenticalSecondChannelCostsAlmostNothing() {
        Random random = new Random(555L);
        int[] mono = new int[40000];
        int walk = 0;
        for (int i = 0; i < mono.length; i++) {
            walk = Math.clamp(walk + random.nextInt(2001) - 1000, -32768, 32767);
            mono[i] = walk;
        }
        int[] doubled = new int[mono.length * 2];
        for (int i = 0; i < mono.length; i++) {
            doubled[2 * i] = mono[i];
            doubled[2 * i + 1] = mono[i];
        }

        int monoBytes = Flac.encode(new Flac.Pcm(44100, 1, 16, mono)).length;
        Flac.Pcm stereo = new Flac.Pcm(44100, 2, 16, doubled);
        int stereoBytes = Flac.encode(stereo).length;

        assertTrue(stereoBytes < monoBytes * 1.1, "the same signal in both channels cost "
                + stereoBytes + " bytes against " + monoBytes + " for one channel alone. The side "
                + "channel is left minus right, which is silence here, so the second channel "
                + "should be almost free; twice the size means the four stereo decorrelations "
                + "were planned and the cheapest was not chosen");
        assertEquals(stereo, Flac.decode(Flac.encode(stereo)),
                "the decorrelated stereo stream did not come back as the two channels that went in");
    }

    @Test
    @DisplayName("the header can be read off a stream whose audio is not there at all")
    void theHeaderIsReadableWithoutTheFrames() {
        int[] samples = new int[8000];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (i % 200) - 100;
        }
        Flac.Pcm pcm = new Flac.Pcm(22050, 2, 8, samples);
        byte[] encoded = Flac.encode(pcm);

        byte[] headerOnly = new byte[42];
        System.arraycopy(encoded, 0, headerOnly, 0, 42);

        Flac.Pcm shape = Flac.decodeHeaderOnly(headerOnly);
        assertEquals(22050, shape.sampleRate(), "the manifest check read the wrong sample rate");
        assertEquals(2, shape.channels(), "the manifest check read the wrong channel count");
        assertEquals(8, shape.bitsPerSample(), "the manifest check read the wrong bit depth");
        assertEquals(0, shape.samples().length,
                "decodeHeaderOnly returned samples, so it decoded audio it was asked to skip");
        assertEquals(4000L, Flac.readStreamInfo(headerOnly).totalFrames(),
                "the header does not know how long the audio is, which is the one thing a manifest "
                        + "check cannot work out for itself");

        assertThrows(Flac.FlacFormatException.class, () -> Flac.decode(headerOnly),
                "decoding the same truncated bytes has to fail, or the test above proves nothing "
                        + "about decodeHeaderOnly skipping the frames");
    }

    @Test
    @DisplayName("a frame whose checksum disagrees with its contents is refused, and named")
    void aFrameThatFailsItsChecksumIsNamed() {
        int frames = 4096 * 3;
        int[] samples = new int[frames];
        Random random = new Random(31337L);
        int walk = 0;
        for (int i = 0; i < frames; i++) {
            walk = Math.clamp(walk + random.nextInt(401) - 200, -32768, 32767);
            samples[i] = walk;
        }
        Flac.Pcm pcm = new Flac.Pcm(44100, 1, 16, samples);
        byte[] encoded = Flac.encode(pcm);

        assertEquals(pcm, Flac.decode(encoded),
                "the fixture must decode cleanly before it is damaged, or a failure afterwards "
                        + "says nothing");

        byte[] damaged = encoded.clone();
        damaged[damaged.length - 1] ^= 0x01;
        Flac.FlacFormatException failure = assertThrows(Flac.FlacFormatException.class,
                () -> Flac.decode(damaged),
                "the last frame's CRC-16 was altered and the decoder handed back audio anyway. A "
                        + "FLAC frame carries no length, so a decoder that ignores the checksum "
                        + "returns music that plays and is not the music that was encoded");
        assertTrue(failure.getMessage().contains("frame 2"),
                "the failure has to name which of the three frames went wrong, and it said: "
                        + failure.getMessage());
    }

    @Test
    @DisplayName("a bit flipped in the middle of the audio is refused rather than played")
    void aBitFlippedInsideAFrameIsRefused() {
        int frames = 4096 * 2;
        int[] samples = new int[frames];
        for (int i = 0; i < frames; i++) {
            samples[i] = (int) Math.round(20000 * Math.sin(2 * Math.PI * 220 * i / 44100.0));
        }
        Flac.Pcm pcm = new Flac.Pcm(44100, 1, 16, samples);
        byte[] encoded = Flac.encode(pcm);
        assertEquals(pcm, Flac.decode(encoded), "the fixture must decode cleanly before damage");

        int inTheAudio = encoded.length / 2;
        byte[] damaged = encoded.clone();
        damaged[inTheAudio] ^= 0x08;

        assertThrows(Flac.FlacFormatException.class, () -> Flac.decode(damaged),
                "one bit was flipped inside a residual and the decoder returned samples. Every "
                        + "sample after that bit is wrong, and nothing about the stream looks "
                        + "malformed; the frame CRC is the only thing that can tell");
    }

    @Test
    @DisplayName("encoding the same audio twice writes the same bytes both times")
    void encodingIsDeterministic() {
        Random random = new Random(8L);
        int[] samples = new int[50000];
        int walk = 0;
        for (int i = 0; i < samples.length; i++) {
            walk = Math.clamp(walk + random.nextInt(3001) - 1500, -32768, 32767);
            samples[i] = walk;
        }
        Flac.Pcm pcm = new Flac.Pcm(44100, 2, 16, samples);

        assertArrayEquals(Flac.encode(pcm), Flac.encode(pcm),
                "two encodes of the same audio produced different bytes, so a pack's manifest "
                        + "cannot carry a hash of its own payloads and a rebuild is never a no-op");
    }

    @Test
    @DisplayName("eight-bit audio handed over as it sits in a WAV file is refused, not silently wrecked")
    void unsignedEightBitInputIsRefused() {
        int[] asStoredInAWav = new int[100];
        for (int i = 0; i < asStoredInAWav.length; i++) {
            asStoredInAWav[i] = 128 + (i % 100);
        }
        Flac.Pcm wrong = new Flac.Pcm(11025, 1, 8, asStoredInAWav);

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> Flac.encode(wrong),
                "eight-bit WAV is unsigned on disc and FLAC is signed. Encoding it as it sits "
                        + "gives an effect that is wrapped rather than centred, which sounds like "
                        + "a burst of static and passes every round-trip test there is");
        assertTrue(refusal.getMessage().contains("centred"),
                "the refusal has to say what the caller has to do about it, and it said: "
                        + refusal.getMessage());
    }

    @Test
    @DisplayName("something that is not a FLAC stream is rejected by name")
    void bytesThatAreNotFlacAreRejected() {
        byte[] wav = "RIFF....WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        Flac.FlacFormatException failure = assertThrows(Flac.FlacFormatException.class,
                () -> Flac.decode(wav), "a WAV file was accepted as a FLAC stream");
        assertTrue(failure.getMessage().contains("fLaC"),
                "the failure should say what it was looking for, and it said: "
                        + failure.getMessage());
    }

    /**
     * A short effect shaped the way the shipped ones are: a burst that decays,
     * band limited rather than white, at 11025 Hz and eight bits.
     *
     * <p>2904 bytes because that is the size of entry 2 of {@code SFXDAT.SUD},
     * which ffmpeg's defaults turn into 9984 bytes.
     */
    private static int[] shortEffect() {
        int[] samples = new int[2904];
        Random random = new Random(2904L);
        double phase = 0;
        double smoothed = 0;
        for (int i = 0; i < samples.length; i++) {
            double envelope = Math.exp(-3.0 * i / samples.length);
            phase += 2 * Math.PI * (220 + 40 * Math.sin(i / 300.0)) / 11025.0;
            smoothed = 0.8 * smoothed + 0.2 * (random.nextDouble() - 0.5);
            samples[i] = (int) Math.round(
                    Math.clamp(110 * envelope * (Math.sin(phase) + smoothed), -128.0, 127.0));
        }
        return samples;
    }

    /** The MD5 STREAMINFO is supposed to carry, computed here without going through the codec. */
    private static String md5OfLittleEndianSamples(int[] samples, int bytesPerSample) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] bytes = new byte[samples.length * bytesPerSample];
        int at = 0;
        for (int sample : samples) {
            for (int b = 0; b < bytesPerSample; b++) {
                bytes[at++] = (byte) (sample >> (8 * b));
            }
        }
        return HexFormat.of().formatHex(digest.digest(bytes));
    }
}
