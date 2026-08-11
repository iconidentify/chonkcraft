package net.chonkbase.assetpack.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Whether the rest of the world agrees with this codec about what the audio is.
 *
 * <p>This is the suite that matters most, and the reason is that a codec tested
 * only against itself proves nothing. Encode and decode can share a mistake and
 * cancel it out perfectly: fold the Rice residual with the sign in the wrong
 * place at both ends and every round-trip test in the project passes, on every
 * stream, forever. The pack would then hold 900 MB of audio that only this
 * program can read, and the day anything else touches it -- a reference
 * decoder, a future rewrite, an artist's tool -- the music turns to noise.
 *
 * <p>So these tests put a second implementation on the other side. ffmpeg
 * decodes what this encoder writes and the samples must match; ffmpeg encodes
 * at {@code -compression_level 8}, which uses LPC subframes this encoder never
 * writes, and this decoder must produce the samples that went in. Where the
 * reference {@code flac} tool is installed it is used as well, because
 * {@code flac -t} checks the MD5 in STREAMINFO against the audio it actually
 * decoded, which is the whole property in one command.
 *
 * <p>Everything here skips through {@link Assumptions} when the tool is not
 * installed, and never fails for its absence.
 */
class FlacInteropTest {

    @Test
    @DisplayName("ffmpeg decodes a music track written here and gets the same samples back")
    void ffmpegDecodesSixteenBitStereoWrittenHere() throws Exception {
        requireFfmpeg();
        Flac.Pcm music = musicLikeStereo();
        Path work = workingDirectory();
        try {
            Path ours = work.resolve("ours.flac");
            Path wav = work.resolve("decoded.wav");
            Files.write(ours, Flac.encode(music));

            run(work, "ffmpeg", "-v", "error", "-y", "-i", ours.toString(),
                    "-map_metadata", "-1", "-c:a", "pcm_s16le", wav.toString());

            Flac.Pcm throughFfmpeg = readWav(Files.readAllBytes(wav));
            assertEquals(44100, throughFfmpeg.sampleRate(),
                    "ffmpeg read a different sample rate out of the stream");
            assertEquals(2, throughFfmpeg.channels(),
                    "ffmpeg read a different channel count out of the stream");
            assertArrayEquals(music.samples(), throughFfmpeg.samples(),
                    "ffmpeg decoded this encoder's output to different audio. The two "
                            + "implementations disagree about what the bits mean, and only one of "
                            + "them can be right");
        } finally {
            deleteTree(work);
        }
    }

    @Test
    @DisplayName("ffmpeg decodes an eight-bit effect written here and gets the same samples back")
    void ffmpegDecodesEightBitMonoWrittenHere() throws Exception {
        requireFfmpeg();
        Flac.Pcm effect = effectLikeMono();
        Path work = workingDirectory();
        try {
            Path ours = work.resolve("ours.flac");
            Path wav = work.resolve("decoded.wav");
            Files.write(ours, Flac.encode(effect));

            run(work, "ffmpeg", "-v", "error", "-y", "-i", ours.toString(),
                    "-map_metadata", "-1", "-c:a", "pcm_u8", wav.toString());

            Flac.Pcm throughFfmpeg = readWav(Files.readAllBytes(wav));
            assertEquals(11025, throughFfmpeg.sampleRate(),
                    "ffmpeg read the wrong rate; 11025 has no four-bit code and is written as a "
                            + "sixteen-bit literal in the frame header");
            assertEquals(8, throughFfmpeg.bitsPerSample(),
                    "ffmpeg did not treat the stream as eight-bit audio, so the comparison below "
                            + "would be measuring a format conversion rather than the codec");
            assertArrayEquals(effect.samples(), throughFfmpeg.samples(),
                    "ffmpeg decoded this encoder's eight-bit output to different audio");
        } finally {
            deleteTree(work);
        }
    }

    @Test
    @DisplayName("a stream ffmpeg encoded at its highest setting decodes here to the audio that went in")
    void ffmpegOutputDecodesHere() throws Exception {
        requireFfmpeg();
        Flac.Pcm music = musicLikeStereo();
        Path work = workingDirectory();
        try {
            Path wav = work.resolve("in.wav");
            Path theirs = work.resolve("theirs.flac");
            Files.write(wav, writeWav(music));

            run(work, "ffmpeg", "-v", "error", "-y", "-i", wav.toString(),
                    "-c:a", "flac", "-compression_level", "8", theirs.toString());

            byte[] foreign = Files.readAllBytes(theirs);
            byte[] ours = Flac.encode(music);
            assertFalse(java.util.Arrays.equals(foreign, ours),
                    "ffmpeg produced the same bytes this encoder does, so the test is comparing "
                            + "this codec with itself and proves nothing");
            assertTrue(foreign.length < ours.length, "ffmpeg's stream is " + foreign.length
                    + " bytes and this encoder's is " + ours.length + ". ffmpeg should be smaller "
                    + "on tonal material because it uses LPC subframes, which this encoder never "
                    + "writes; if it is not smaller, the foreign stream may not be exercising the "
                    + "decoder path this test exists to cover");

            Flac.Pcm decoded = Flac.decode(foreign);
            assertEquals(44100, decoded.sampleRate(), "the foreign stream decoded at the wrong rate");
            assertEquals(2, decoded.channels(), "the foreign stream decoded to the wrong channels");
            assertArrayEquals(music.samples(), decoded.samples(),
                    "this decoder read ffmpeg's stream as different audio. LPC subframes, the "
                            + "coefficient shift and the partition layout all differ from what this "
                            + "encoder writes, and getting any of them wrong gives music that plays");
        } finally {
            deleteTree(work);
        }
    }

    @Test
    @DisplayName("a mono stream ffmpeg encoded decodes here to the audio that went in")
    void ffmpegMonoOutputDecodesHere() throws Exception {
        requireFfmpeg();
        int[] samples = new int[60000];
        Random random = new Random(6060L);
        int walk = 0;
        for (int i = 0; i < samples.length; i++) {
            walk = Math.clamp(walk + random.nextInt(1201) - 600, -32768, 32767);
            samples[i] = walk;
        }
        Flac.Pcm mono = new Flac.Pcm(22050, 1, 16, samples);
        Path work = workingDirectory();
        try {
            Path wav = work.resolve("in.wav");
            Path theirs = work.resolve("theirs.flac");
            Files.write(wav, writeWav(mono));
            run(work, "ffmpeg", "-v", "error", "-y", "-i", wav.toString(),
                    "-c:a", "flac", "-compression_level", "8", theirs.toString());

            assertArrayEquals(mono.samples(), Flac.decode(Files.readAllBytes(theirs)).samples(),
                    "this decoder read ffmpeg's mono stream as different audio");
        } finally {
            deleteTree(work);
        }
    }

    @Test
    @DisplayName("the reference decoder checks this encoder's own checksum and accepts it")
    void libFlacVerifiesStreamsWrittenHere() throws Exception {
        requireFlacTool();
        Path work = workingDirectory();
        try {
            List<Flac.Pcm> corpus = List.of(
                    musicLikeStereo(),
                    effectLikeMono(),
                    new Flac.Pcm(44100, 2, 16, new int[44100 * 2]),
                    new Flac.Pcm(8000, 1, 16, new int[]{5, -5, 5, -5, 100}));

            int verified = 0;
            for (Flac.Pcm pcm : corpus) {
                Path file = work.resolve("stream" + verified + ".flac");
                Files.write(file, Flac.encode(pcm));
                String output = run(work, "flac", "-t", "-s", file.toString());
                assertFalse(output.toLowerCase(java.util.Locale.ROOT).contains("mismatch"),
                        "the reference decoder read " + pcm + " back and its MD5 did not match "
                                + "STREAMINFO, which means the audio it decoded is not the audio "
                                + "that was encoded: " + output);
                verified++;
            }
            assertEquals(4, verified, "the sweep did not verify all four streams");
        } finally {
            deleteTree(work);
        }
    }

    @Test
    @DisplayName("an eight-bit stream from the reference encoder decodes here to the effect that went in")
    void libFlacEightBitOutputDecodesHere() throws Exception {
        requireFlacTool();
        Flac.Pcm effect = effectLikeMono();
        Path work = workingDirectory();
        try {
            Path wav = work.resolve("in.wav");
            Path theirs = work.resolve("theirs.flac");
            Files.write(wav, writeWav(effect));
            run(work, "flac", "-8", "-s", "-f", "-o", theirs.toString(), wav.toString());

            byte[] foreign = Files.readAllBytes(theirs);
            assertEquals(8, Flac.readStreamInfo(foreign).bitsPerSample(),
                    "the reference encoder did not store this as eight-bit audio, so the test is "
                            + "not covering the eight-bit path that most of the effects use");

            Flac.Pcm decoded = Flac.decode(foreign);
            assertArrayEquals(effect.samples(), decoded.samples(),
                    "this decoder read the reference encoder's eight-bit stream as different "
                            + "audio. libFLAC uses LPC and wasted bits, and neither is anything "
                            + "this encoder ever writes");
        } finally {
            deleteTree(work);
        }
    }

    /**
     * The wasted-bit path, which nothing used to reach.
     *
     * <p>{@link FlacEncoder} emits a wasted-bit count whenever a block's
     * samples share low zero bits, and the decoder's own Javadoc used to say
     * the encoder never wrote one. Because everybody believed that, no fixture
     * anywhere was shaped to reach it: all 200 streams in {@code FlacTest}'s
     * sweep and both fixtures above come out with a wasted-bit count of zero,
     * and so do all 487 shipped effects and the red book tracks. That left the
     * shift untested at both ends at once, which is the one arrangement a
     * round-trip cannot see -- shift the same wrong way in the encoder and the
     * decoder and every test in the project passes while the pack holds audio
     * only this program can read.
     */
    @Test
    @DisplayName("audio whose samples all end in zero bits comes back from the reference decoder unchanged")
    void wastedBitsWrittenHereSurviveTheReferenceDecoder() throws Exception {
        requireFlacTool();
        Flac.Pcm quiet = eightBitMaterialCarriedInSixteenBitSamples();
        for (int i = 0; i < quiet.samples().length; i++) {
            assertEquals(0, quiet.samples()[i] & 0xFF, "sample " + i + " is " + quiet.samples()[i]
                    + ", which does not end in eight zero bits, so the encoder has no wasted bits "
                    + "to write and this test would prove nothing");
        }

        byte[] ours = Flac.encode(quiet);
        assertTrue(ours.length < quiet.rawByteLength() / 4, "the stream came out " + ours.length
                + " bytes against " + quiet.rawByteLength() + " raw. Eight of every sixteen bits "
                + "here are zero, so an encoder that shifted them out lands far under a quarter of "
                + "raw and one that stored them does not; this stream is not exercising the path");

        Path work = workingDirectory();
        try {
            Path file = work.resolve("wasted.flac");
            Path back = work.resolve("wasted.wav");
            Files.write(file, ours);

            String output = run(work, "flac", "-t", "-s", file.toString());
            assertFalse(output.toLowerCase(java.util.Locale.ROOT).contains("mismatch"),
                    "the reference decoder read this back and its MD5 did not match STREAMINFO, so "
                            + "the audio it decoded is not the audio that was encoded: " + output);

            run(work, "flac", "-d", "-s", "-f", "-o", back.toString(), file.toString());
            assertArrayEquals(quiet.samples(), readWav(Files.readAllBytes(back)).samples(),
                    "the reference decoder read this encoder's wasted-bit subframes as different "
                            + "audio. Nothing else in either suite writes one, so a shift applied "
                            + "the same wrong way at both ends would round trip here forever and "
                            + "fail the first time anything else opened the pack");
        } finally {
            deleteTree(work);
        }
    }

    // ------------------------------------------------------------- fixtures

    /**
     * Sixteen-bit samples that all end in eight zero bits, which is what
     * eight-bit source material widened to sixteen looks like and the one
     * shape that makes this encoder write a wasted-bit count.
     */
    private static Flac.Pcm eightBitMaterialCarriedInSixteenBitSamples() {
        int frames = 44100;
        int[] samples = new int[frames * 2];
        Random random = new Random(2904L);
        int walk = 0;
        for (int i = 0; i < frames; i++) {
            walk = Math.clamp(walk + random.nextInt(7) - 3, -128, 127);
            samples[2 * i] = walk * 256;
            samples[2 * i + 1] = Math.clamp(walk + random.nextInt(3) - 1, -128, 127) * 256;
        }
        return new Flac.Pcm(44100, 2, 16, samples);
    }

    /**
     * Three seconds of two tones with a little dither on top, which is tonal
     * enough that LPC beats a fixed predictor and noisy enough that it is not a
     * degenerate case a broken encoder could still get right.
     */
    private static Flac.Pcm musicLikeStereo() {
        int frames = 44100 * 3;
        int[] samples = new int[frames * 2];
        Random random = new Random(1995L);
        for (int i = 0; i < frames; i++) {
            samples[2 * i] = (int) Math.round(
                    28000 * Math.sin(2 * Math.PI * 440 * i / 44100.0)) + random.nextInt(201) - 100;
            samples[2 * i + 1] = (int) Math.round(
                    28000 * Math.sin(2 * Math.PI * 277 * i / 44100.0)) + random.nextInt(201) - 100;
        }
        return new Flac.Pcm(44100, 2, 16, samples);
    }

    /** Two seconds at the shape 380 of the 383 entries in {@code SFXDAT.SUD} use. */
    private static Flac.Pcm effectLikeMono() {
        int[] samples = new int[11025 * 2];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (int) Math.round(100 * Math.sin(2 * Math.PI * 300 * i / 11025.0));
        }
        return new Flac.Pcm(11025, 1, 8, samples);
    }

    // ----------------------------------------------------------- the tooling

    private static void requireFfmpeg() {
        Assumptions.assumeTrue(onPath("ffmpeg"),
                "ffmpeg is not on PATH, so nothing else can check this codec's work.");
    }

    private static void requireFlacTool() {
        Assumptions.assumeTrue(onPath("flac"),
                "the reference flac tool is not on PATH, so nothing else can check this codec's "
                        + "work.");
    }

    private static boolean onPath(String tool) {
        try {
            Process process = new ProcessBuilder(tool, "-version")
                    .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            process.waitFor(20, TimeUnit.SECONDS);
            return true;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static String run(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS),
                "the tool did not finish: " + String.join(" ", command));
        assertEquals(0, process.exitValue(),
                String.join(" ", command) + " failed:\n" + output);
        return output;
    }

    private static Path workingDirectory() throws IOException {
        return Files.createTempDirectory("flac-interop");
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(paths::add);
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }

    // ------------------------------------------------------------------- wav

    /**
     * The interchange format, because it is the one thing both tools take
     * without argument. Eight-bit WAV is unsigned and sixteen-bit WAV is
     * signed, which is not a quirk of this helper but of the format, and it is
     * the conversion the encoder refuses to guess at.
     */
    private static byte[] writeWav(Flac.Pcm pcm) {
        int bytesPerSample = pcm.bitsPerSample() / 8;
        int dataBytes = pcm.samples().length * bytesPerSample;
        byte[] wav = new byte[44 + dataBytes];
        ascii(wav, 0, "RIFF");
        int32(wav, 4, 36 + dataBytes);
        ascii(wav, 8, "WAVE");
        ascii(wav, 12, "fmt ");
        int32(wav, 16, 16);
        int16(wav, 20, 1);
        int16(wav, 22, pcm.channels());
        int32(wav, 24, pcm.sampleRate());
        int32(wav, 28, pcm.sampleRate() * pcm.channels() * bytesPerSample);
        int16(wav, 32, pcm.channels() * bytesPerSample);
        int16(wav, 34, pcm.bitsPerSample());
        ascii(wav, 36, "data");
        int32(wav, 40, dataBytes);
        int at = 44;
        for (int sample : pcm.samples()) {
            if (bytesPerSample == 1) {
                wav[at++] = (byte) (sample + 128);
            } else {
                wav[at++] = (byte) sample;
                wav[at++] = (byte) (sample >> 8);
            }
        }
        return wav;
    }

    private static Flac.Pcm readWav(byte[] wav) {
        int at = 12;
        int sampleRate = 0;
        int channels = 0;
        int bitsPerSample = 0;
        int dataAt = -1;
        int dataBytes = 0;
        while (at + 8 <= wav.length) {
            String id = new String(wav, at, 4, StandardCharsets.US_ASCII);
            int length = readInt32(wav, at + 4);
            int body = at + 8;
            if (length < 0 || body + length > wav.length) {
                length = wav.length - body;
            }
            if (id.equals("fmt ")) {
                channels = readInt16(wav, body + 2);
                sampleRate = readInt32(wav, body + 4);
                bitsPerSample = readInt16(wav, body + 14);
            } else if (id.equals("data")) {
                dataAt = body;
                dataBytes = length;
            }
            at = body + length + (length & 1);
        }
        if (dataAt < 0) {
            throw new UncheckedIOException(new IOException("the tool wrote a WAV with no data"));
        }
        int bytesPerSample = bitsPerSample / 8;
        int count = dataBytes / bytesPerSample;
        int[] samples = new int[count];
        for (int i = 0; i < count; i++) {
            if (bytesPerSample == 1) {
                samples[i] = (wav[dataAt + i] & 0xFF) - 128;
            } else {
                samples[i] = (short) ((wav[dataAt + 2 * i] & 0xFF)
                        | ((wav[dataAt + 2 * i + 1] & 0xFF) << 8));
            }
        }
        return new Flac.Pcm(sampleRate, channels, bitsPerSample, samples);
    }

    private static void ascii(byte[] target, int at, String text) {
        for (int i = 0; i < text.length(); i++) {
            target[at + i] = (byte) text.charAt(i);
        }
    }

    private static void int32(byte[] target, int at, int value) {
        target[at] = (byte) value;
        target[at + 1] = (byte) (value >> 8);
        target[at + 2] = (byte) (value >> 16);
        target[at + 3] = (byte) (value >> 24);
    }

    private static void int16(byte[] target, int at, int value) {
        target[at] = (byte) value;
        target[at + 1] = (byte) (value >> 8);
    }

    private static int readInt32(byte[] source, int at) {
        return (source[at] & 0xFF) | ((source[at + 1] & 0xFF) << 8)
                | ((source[at + 2] & 0xFF) << 16) | ((source[at + 3] & 0xFF) << 24);
    }

    private static int readInt16(byte[] source, int at) {
        return (source[at] & 0xFF) | ((source[at + 1] & 0xFF) << 8);
    }
}
