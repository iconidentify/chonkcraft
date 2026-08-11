package net.chonkbase.chonkcraft.data.source;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.chonkbase.assetpack.AssetKind;
import net.chonkbase.assetpack.AssetPack;
import net.chonkbase.assetpack.AssetPackWriter;
import net.chonkbase.assetpack.Codec;
import net.chonkbase.assetpack.PackAsset;
import net.chonkbase.assetpack.PackManifest;
import net.chonkbase.assetpack.codec.Flac;
import net.chonkbase.assetpack.codec.SignalToNoise;
import net.chonkbase.assetpack.codec.Wav;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Opus audio through a whole pack, written and then read the way the game reads
 * it.
 *
 * <p>{@code OpusFileTest} proves the codec and {@code OpusCodecTest} proves the
 * container. Neither proves the thing that can actually go wrong here, which is
 * everything <em>around</em> the codec: that a sound comes back out of
 * {@link PackSource#archive} as a RIFF file at the rate the archive held it at,
 * that a music track comes back out of {@link PackSource#musicSamples} at the
 * disc's rate and not the codec's, and that a track stored as a window into
 * another track's stream lands on the right samples. A pack that got any of
 * those wrong would still hold perfectly good Opus.
 *
 * <p>Every pack here is built in the test rather than taken from a fixture, so
 * that what is being asserted is the behaviour of the writer and the reader
 * together and not the contents of a checked-in file that nothing regenerates.
 */
@DisplayName("Opus audio in a pack")
class PackOpusAudioTest {

    /** The archive number Warcraft II's sound effects live in. */
    private static final int SFXDAT = 5000;

    /** What the pack encodes sound effects at; see PackBuilder. */
    private static final int SOUND_BITRATE = 64_000;

    /** What the pack encodes recorded music at; see PackBuilder. */
    private static final int MUSIC_BITRATE = 144_000;

    private Path packFile;

    @BeforeEach
    void createTempFile() throws IOException {
        packFile = Files.createTempFile("chonk-opus-pack", ".chonkpack");
        Files.delete(packFile);
    }

    @AfterEach
    void removeTempFile() throws IOException {
        Files.deleteIfExists(packFile);
    }

    // --------------------------------------------------------------- effects

    @Test
    @DisplayName("a sound effect comes back out of the pack as a RIFF at its own rate")
    void aSoundEffectKeepsItsRateThroughThePack() {
        int checked = 0;
        StringBuilder report = new StringBuilder();
        // The two rates and the depth Warcraft II actually ships its effects
        // at, plus a 16-bit one to prove the depth is carried rather than
        // assumed. Stereo is covered by the music test, which is where the only
        // stereo audio in this game lives.
        for (int[] run : new int[][] {{11_025, 1, 8}, {22_050, 1, 8}, {22_050, 1, 16}}) {
            int rate = run[0];
            int channels = run[1];
            int bits = run[2];
            byte[] original = riffNoise(rate, channels, bits, rate * 2);
            EntryCodec.Encoded encoded = EntryCodec.encode(EntryCodec.Form.SOUND, original,
                    null, AssetKind.SOUND, EntryCodec.AudioTarget.opus(SOUND_BITRATE));

            assertEquals(Codec.OPUS, encoded.codec(), rate + " Hz " + channels
                    + "ch did not choose Opus, so this case is not testing what it says");
            assertEquals((long) codecRate(), encoded.meta().get("decodeSampleRate"),
                    "the meta does not record the rate the stream actually decodes at");
            assertEquals((long) rate, encoded.meta().get("sampleRate"));
            assertEquals((long) channels, encoded.meta().get("channels"));
            assertEquals((long) bits, encoded.meta().get("bitsPerSample"));
            assertEquals((long) rate * 2, encoded.meta().get("sampleFrames"));

            byte[] rebuilt = writeAndReadEntry(encoded, original.length);

            // The whole point: a 1995 RIFF, not a 48 kHz one.
            Flac.Pcm before = Wav.decode(original);
            Flac.Pcm after = Wav.decode(rebuilt);
            assertEquals(rate, after.sampleRate(), "the rebuilt entry is at the wrong rate,"
                    + " so the engine's own resampler would run on the wrong input");
            assertEquals(channels, after.channels(), "channel count");
            assertEquals(bits, after.bitsPerSample(), "bit depth");
            assertEquals(before.frameCount(), after.frameCount(), "frame count");

            double db = SignalToNoise.db(before.samples(), after.samples());
            assertTrue(db >= SignalToNoise.SOUND_FLOOR_DB, rate + " Hz " + channels
                    + "ch came back at " + SignalToNoise.describe(db) + ", under the floor");
            report.append(rate).append("/").append(channels).append("ch/").append(bits)
                    .append("bit ").append(SignalToNoise.describe(db)).append("; ");
            checked++;
        }
        assertEquals(3, checked, "not every sound case ran");
        System.out.println("sound effects through the pack: " + report);
    }

    @Test
    @DisplayName("the build refuses a lossy encoding that would be larger than a lossless one")
    void aClipTooShortForOpusStaysLossless() {
        // Twenty milliseconds is one Opus frame, and one Opus frame at 64 kb/s
        // costs more than the whole clip does uncompressed. Paying bytes for
        // loss is the one outcome this path must never produce.
        byte[] click = riffNoise(11_025, 1, 8, 220);
        EntryCodec.Encoded encoded = EntryCodec.encode(EntryCodec.Form.SOUND, click, null,
                AssetKind.SOUND, EntryCodec.AudioTarget.opus(SOUND_BITRATE));
        assertNotEquals(Codec.OPUS, encoded.codec(),
                "a 20 ms click was stored lossily and came out bigger for it");
        assertTrue(encoded.codec().lossless(), "it should still be exact");

        byte[] rebuilt = writeAndReadEntry(encoded, click.length);
        assertArrayEquals(Wav.decode(click).samples(), Wav.decode(rebuilt).samples(),
                "a losslessly stored click did not come back exactly");
    }

    @Test
    @DisplayName("FLAC stays the default: an entry that is not offered a bitrate is exact")
    void losslessRemainsTheDefault() {
        byte[] original = riffNoise(22_050, 1, 8, 22_050);
        EntryCodec.Encoded encoded = EntryCodec.encode(EntryCodec.Form.SOUND, original,
                null, AssetKind.SOUND);
        assertEquals(Codec.FLAC, encoded.codec(),
                "the no-bitrate call stopped being lossless");
        assertTrue(encoded.codec().lossless());

        byte[] rebuilt = writeAndReadEntry(encoded, original.length);
        assertArrayEquals(Wav.decode(original).samples(), Wav.decode(rebuilt).samples(),
                "the lossless path is no longer lossless");
    }

    // ----------------------------------------------------------------- music

    @Test
    @DisplayName("music comes back at the disc's rate, and a window lands on the right samples")
    void musicAndItsWindowsComeBackAtTheDiscsRate() {
        int rate = 44_100;
        int channels = 2;
        int masterFrames = rate * 3;
        // One master recording, and two tracks cut out of it: the whole thing,
        // and a window a fifth of a second in, which is how the two Warcraft II
        // discs share their fourteen common tracks.
        short[] master = music(masterFrames, channels);
        int windowOffset = rate / 5;
        int windowFrames = masterFrames - windowOffset - 1000;

        try (AssetPackWriter writer = new AssetPackWriter(packFile, identity())) {
            int[] samples = new int[master.length];
            for (int i = 0; i < samples.length; i++) {
                samples[i] = master[i];
            }
            byte[] opus = net.chonkbase.assetpack.codec.Opus.encode(
                    new Flac.Pcm(rate, channels, 16, samples), MUSIC_BITRATE);

            int whole = writer.add("music/cd/disc/track-2", AssetKind.MUSIC, Codec.OPUS,
                    "assets/music/track-2.opus", opus, master.length * 2L,
                    musicMeta("disc track 2", rate, channels, 0, masterFrames));
            int window = writer.alias("music/cd/disc/track-3", AssetKind.MUSIC, whole, 0,
                    musicMeta("disc track 3", rate, channels, windowOffset, windowFrames));
            writer.disc("disc", List.of(whole, window));
            writer.mapList(List.of());
            writer.finish();
        }

        try (PackSource source = PackSource.open(packFile)) {
            List<AssetSource.MusicTrack> tracks = source.musicTracks();
            assertEquals(2, tracks.size(), "the pack offered a different number of tracks");

            // The rate the disc was pressed at, not the 48 kHz Opus decodes at.
            // CdMusic resamples by this number, so a pack that reported 48000
            // would play the track nine percent fast or not resample at all.
            assertEquals(rate, tracks.get(0).sampleRate(), "track 2 rate");
            assertEquals(rate, tracks.get(1).sampleRate(), "track 3 rate");
            assertEquals(channels, tracks.get(0).channels());
            assertEquals(masterFrames, tracks.get(0).frames(), "track 2 length");
            assertEquals(windowFrames, tracks.get(1).frames(), "track 3 length");

            short[] whole = source.musicSamples(0);
            assertEquals(masterFrames * channels, whole.length,
                    "the whole track came back a different length");
            double wholeDb = SignalToNoise.db(master, whole);
            assertTrue(wholeDb >= 8.0, "the whole track came back at "
                    + SignalToNoise.describe(wholeDb));

            short[] windowed = source.musicSamples(1);
            assertEquals(windowFrames * channels, windowed.length,
                    "the window came back a different length");

            // The window has to be the same samples the whole track has at that
            // offset. This is the assertion that catches an offset applied in
            // the codec's 48 kHz frames instead of the recording's 44,100.
            short[] expected = new short[windowFrames * channels];
            System.arraycopy(master, windowOffset * channels, expected, 0, expected.length);
            double windowDb = SignalToNoise.db(expected, windowed);
            assertTrue(windowDb >= 8.0, "the window came back at "
                    + SignalToNoise.describe(windowDb) + ", which means it is not the"
                    + " slice of the master it claims to be");

            // And it has to be the same samples the whole track already handed
            // back, to the sample: both come out of one decode of one stream.
            short[] fromWhole = new short[expected.length];
            System.arraycopy(whole, windowOffset * channels, fromWhole, 0, fromWhole.length);
            assertArrayEquals(fromWhole, windowed,
                    "the window and the same range of the whole track disagree, so the"
                    + " stream is being decoded from two different places");

            System.out.println("music through the pack: whole "
                    + SignalToNoise.describe(wholeDb) + ", window "
                    + SignalToNoise.describe(windowDb));
        }
    }

    @Test
    @DisplayName("the pack's audio() hands back the whole shared stream, not one alias's share")
    void audioReturnsTheWholeSharedStream() {
        int rate = 22_050;
        int frames = rate;
        short[] master = music(frames, 1);
        try (AssetPackWriter writer = new AssetPackWriter(packFile, identity())) {
            int[] samples = new int[master.length];
            for (int i = 0; i < samples.length; i++) {
                samples[i] = master[i];
            }
            byte[] opus = net.chonkbase.assetpack.codec.Opus.encode(
                    new Flac.Pcm(rate, 1, 16, samples), MUSIC_BITRATE);
            int whole = writer.add("music/cd/disc/track-2", AssetKind.MUSIC, Codec.OPUS,
                    "assets/music/track-2.opus", opus, master.length * 2L,
                    musicMeta("disc track 2", rate, 1, 0, frames));
            writer.alias("music/cd/disc/track-3", AssetKind.MUSIC, whole, 0,
                    musicMeta("disc track 3", rate, 1, 100, 500));
            writer.disc("disc", List.of(whole, whole + 1));
            writer.mapList(List.of());
            writer.finish();
        }

        try (AssetPack pack = AssetPack.open(packFile)) {
            PackAsset alias = pack.find("music/cd/disc/track-3");
            assertEquals(500L, alias.sampleFrames(), "the alias records its own length");
            Flac.Pcm decoded = pack.audio(alias);
            // Its own length is 500 frames and the stream holds a second. Cut to
            // 500 here and every window past that offset would be silence.
            assertEquals(frames, decoded.frameCount(),
                    "audio() trimmed the shared stream to one alias's window");
            assertEquals(rate, decoded.sampleRate(), "audio() served the codec's rate");
        }
    }

    // ------------------------------------------------------- the whole shape

    @Test
    @DisplayName("a pack holding both codecs reports which assets are exact and which are not")
    void aPackCanHoldBothAtOnce() {
        byte[] effect = riffNoise(11_025, 1, 8, 11_025 * 2);
        byte[] click = riffNoise(11_025, 1, 8, 220);
        EntryCodec.Encoded lossy = EntryCodec.encode(EntryCodec.Form.SOUND, effect, null,
                AssetKind.SOUND, EntryCodec.AudioTarget.opus(SOUND_BITRATE));
        EntryCodec.Encoded exact = EntryCodec.encode(EntryCodec.Form.SOUND, click, null,
                AssetKind.SOUND, EntryCodec.AudioTarget.opus(SOUND_BITRATE));

        try (AssetPackWriter writer = new AssetPackWriter(packFile, identity())) {
            int a = writer.add("sound/effect", AssetKind.SOUND, lossy.codec(),
                    "assets/sound/effect.opus", lossy.payload(), effect.length, lossy.meta());
            int b = writer.add("sound/click", AssetKind.SOUND, exact.codec(),
                    "assets/sound/click.flac", exact.payload(), click.length, exact.meta());
            writer.archive(SFXDAT, "sfxdat", new int[] {a, b});
            writer.mapList(List.of());
            writer.finish();
        }

        try (PackSource source = PackSource.open(packFile)) {
            PackManifest manifest = source.pack().manifest();
            int lossyCount = 0;
            int losslessCount = 0;
            for (PackAsset asset : manifest.assets()) {
                if (asset.codec().lossless()) {
                    losslessCount++;
                } else {
                    lossyCount++;
                }
            }
            assertEquals(1, lossyCount, "exactly one asset should be lossy");
            assertEquals(1, losslessCount, "exactly one asset should be exact");

            EntryArchive archive = source.archive(SFXDAT);
            assertEquals(2, archive.entryCount());

            // The lossy one, held to the floor; the exact one, held to the bit.
            EntryCodec.SoundMatch match = EntryCodec.compareSound(effect, archive.entry(0));
            assertTrue(match.sameShape(), "the lossy entry changed shape: " + match.detail());
            assertTrue(match.acceptable(), "the lossy entry came back " + match.describe());
            assertArrayEquals(Wav.decode(click).samples(), Wav.decode(archive.entry(1)).samples(),
                    "the exact entry did not come back exactly");
        }
    }

    // ------------------------------------------------------------- machinery

    /**
     * Writes one encoded entry into a pack of its own and reads it back through
     * {@link PackSource}, which is the path the game takes.
     */
    private byte[] writeAndReadEntry(EntryCodec.Encoded encoded, long sourceBytes) {
        try (AssetPackWriter writer = new AssetPackWriter(packFile, identity())) {
            int index = writer.add("sound/test", AssetKind.SOUND, encoded.codec(),
                    "assets/sound/test.bin", encoded.payload(), sourceBytes, encoded.meta());
            writer.archive(SFXDAT, "sfxdat", new int[] {index});
            writer.mapList(List.of());
            writer.finish();
        }
        byte[] entry;
        try (PackSource source = PackSource.open(packFile)) {
            entry = source.archive(SFXDAT).entry(0);
        }
        try {
            Files.deleteIfExists(packFile);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return entry;
    }

    private static PackManifest.Identity identity() {
        return new PackManifest.Identity("test", "Opus pack test", "a fixture",
                "PackOpusAudioTest", "1970-01-01T00:00:00Z", new LinkedHashMap<>());
    }

    private static Map<String, Object> musicMeta(String name, int rate, int channels,
            long offset, long frames) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", name);
        meta.put("sampleRate", (long) rate);
        meta.put("channels", (long) channels);
        meta.put("bitsPerSample", 16L);
        meta.put("frameOffset", offset);
        meta.put("sampleFrames", frames);
        meta.put("decodeSampleRate", (long) codecRate());
        return meta;
    }

    /** The rate an Opus stream decodes at, which is the only one it has. */
    private static int codecRate() {
        return net.chonkbase.assetpack.codec.Opus.CODEC_RATE;
    }

    // -------------------------------------------------------------- fixtures

    /**
     * A RIFF file of band-limited noise at a given rate and depth.
     *
     * <p>Noise rather than a tone, because FLAC compresses a tone to almost
     * nothing and every size comparison in these tests would then be answering a
     * question about sine waves. A real Warcraft II effect is speech or a metallic
     * impact, and neither is periodic.
     */
    private static byte[] riffNoise(int rate, int channels, int bits, int frames) {
        Random random = new Random(19951109L);
        int limit = (1 << (bits - 1)) - 1;
        int[] samples = new int[frames * channels];
        double[] state = new double[channels];
        for (int frame = 0; frame < frames; frame++) {
            // An envelope, so the clip has a start and an end like a real one.
            double envelope = Math.sin(Math.PI * frame / (double) frames);
            for (int c = 0; c < channels; c++) {
                // Only lightly smoothed. Heavier smoothing makes the signal
                // something FLAC's linear predictor can follow, and then every
                // size comparison below is answering a question about how well
                // an LPC fits a low-pass filter rather than about the codecs.
                state[c] = 0.7 * state[c] + 0.3 * random.nextGaussian();
                int value = (int) Math.round(state[c] * limit * 0.9 * envelope);
                samples[frame * channels + c] = Math.max(-limit - 1, Math.min(limit, value));
            }
        }
        return Wav.encode(new Flac.Pcm(rate, channels, bits, samples));
    }

    /** Something with harmonic structure and a beat, standing in for a track. */
    private static short[] music(int frames, int channels) {
        List<double[]> voices = new ArrayList<>();
        voices.add(new double[] {110, 0.35});
        voices.add(new double[] {220, 0.25});
        voices.add(new double[] {330, 0.15});
        voices.add(new double[] {987, 0.10});
        short[] out = new short[frames * channels];
        for (int frame = 0; frame < frames; frame++) {
            double t = frame / 44_100.0;
            double value = 0;
            for (double[] voice : voices) {
                value += voice[1] * Math.sin(2 * Math.PI * voice[0] * t);
            }
            value *= 0.7 + 0.3 * Math.sin(2 * Math.PI * 2 * t);
            for (int c = 0; c < channels; c++) {
                // A little difference between the channels, so a swap or a
                // downmix would show up.
                double side = c == 1 ? 0.9 : 1.0;
                out[frame * channels + c] =
                        (short) Math.max(-32768, Math.min(32767, Math.round(value * side * 22_000)));
            }
        }
        return out;
    }
}
