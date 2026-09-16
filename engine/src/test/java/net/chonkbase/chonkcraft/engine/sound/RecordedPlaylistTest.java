package net.chonkbase.chonkcraft.engine.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.runtime.audio.AudioMixer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Having all six battle recordings did not help: the first clip looped forever.
 * These render through SoundServer and the production mixer beyond every track
 * and gap, including the wrap, instead of testing the catalog's declarations.
 */
class RecordedPlaylistTest {
    private static final int TRACK_FRAMES = 160;
    private static final int GAP = 96_000;

    @Test
    @DisplayName("recorded battles play each track and two seconds of silence before wrapping")
    void recordedBattlesAdvanceThroughBothRaces() throws Exception {
        for (boolean orc : new boolean[] {false, true}) {
            checkPlaylist(orc, false, List.of(1, 2, 3, 4, 5, 6, 1));
            checkPlaylist(orc, true, List.of(2, 3, 4, 5, 6, 2));
        }
    }

    @Test
    @DisplayName("changing race or screen cancels the previous recorded playlist")
    void changingRaceOrScreenCancelsTheOldPlaylist() throws Exception {
        List<String> reads = new ArrayList<>();
        AudioMixer mixer = new AudioMixer();
        CdMusic disc = new CdMusic(source(reads), mixer);
        try (SoundServer server = new SoundServer(mixer, disc, null, SoundServer.Backend.CD)) {
            assertTrue(server.playBattleMusic(false), "the human soundtrack must start");
            render(disc, mixer, TRACK_FRAMES + GAP / 2);
            assertTrue(
                    server.playBattleMusic(true, true), "the orc campaign soundtrack must start");
            float[] first = render(disc, mixer, TRACK_FRAMES);
            assertTrue(first[100] < -0.001f, "changing race must replace the old human samples");
            assertTrue(server.playMenuMusic(), "the recorded menu must replace battle music");
            float[] menu = render(disc, mixer, TRACK_FRAMES + GAP + TRACK_FRAMES);
            assertTrue(menu[menu.length - 1] > 0.001f,
                    "the menu must keep looping instead of advancing a battle");
            server.stopMusic();
            float[] stopped = render(disc, mixer, GAP + TRACK_FRAMES);
            assertEquals(
                    0.0, energy(stopped), "an old playlist must not resume after its owner stops");
        }
    }

    @Test
    @DisplayName("unavailable battle roles are skipped without replaying a different race")
    void missingBattleRolesDoNotSilenceThePlaylist() throws Exception {
        AssetSource all = source(new ArrayList<>());
        AssetSource partial =
                (AssetSource) Proxy.newProxyInstance(AssetSource.class.getClassLoader(),
                        new Class<?>[] {AssetSource.class}, (proxy, method, args) -> {
                            if (method.getName().equals("musicTracks"))
                                return all.musicTracks()
                                        .stream()
                                        .filter(t
                                                -> t.name().equals("Human Battle 2")
                                                        || t.name().equals("Human Battle 5"))
                                        .toList();
                            if (method.getName().equals("musicSamples"))
                                return all.musicSamples((Integer) args[0] == 0 ? 1 : 4);
                            return method.invoke(all, args);
                        });
        AudioMixer mixer = new AudioMixer();
        CdMusic disc = new CdMusic(partial, mixer);
        try (SoundServer server = new SoundServer(mixer, disc, null, SoundServer.Backend.CD)) {
            assertTrue(
                    server.playBattleMusic(false), "the two available human recordings must start");
            float[] output = render(disc, mixer, 2 * (TRACK_FRAMES + GAP) + TRACK_FRAMES);
            assertTrue(output[100] > 0.001f, "the first available recording must emit samples");
            assertTrue(output[(TRACK_FRAMES + GAP) * 2 + 100] > output[100],
                    "the second available recording must replace the first");
            assertEquals(output[100], output[2 * (TRACK_FRAMES + GAP) * 2 + 100], 0.00001f,
                    "the playlist must wrap to its first available recording");
        }
    }

    @Test
    @DisplayName("mono and stereo recordings retain their clip volume and channel balance")
    void mixedRecordingFormatsPreserveTheirSound() throws Exception {
        List<AssetSource.MusicTrack> tracks = List.of(
                new AssetSource.MusicTrack("Human Battle 1", 44100, 1, 147),
                new AssetSource.MusicTrack("Human Battle 2", 48000, 2, TRACK_FRAMES));
        AssetSource assets = (AssetSource) Proxy.newProxyInstance(AssetSource.class.getClassLoader(),
                new Class<?>[] {AssetSource.class}, (proxy, method, args) -> {
                    if (method.getName().equals("musicTracks")) {
                        return tracks;
                    }
                    if (method.getName().equals("musicSamples")) {
                        var track = tracks.get((Integer) args[0]);
                        short[] samples = new short[(int) track.frames() * track.channels()];
                        for (int index = 0; index < samples.length; index++) {
                            samples[index] = (short) (track.channels() == 1 || index % 2 == 0
                                    ? 4000 : -2000);
                        }
                        return samples;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        AudioMixer mixer = new AudioMixer();
        try (CdMusic disc = new CdMusic(assets, mixer)) {
            assertTrue(disc.playPlaylist(List.of("Human Battle 1", "Human Battle 2")),
                    "the mixed-format playlist must start");
            float[] sequence = render(disc, mixer, TRACK_FRAMES * 2 + GAP);
            for (int index = 0; index < tracks.size(); index++) {
                AudioMixer clipMixer = new AudioMixer();
                try (CdMusic clip = new CdMusic(assets, clipMixer)) {
                    assertTrue(clip.play(clip.tracks().get(index), false),
                            "the reference recording must start as an individual clip");
                    float[] reference = render(clip, clipMixer, TRACK_FRAMES);
                    for (int channel = 0; channel < 2; channel++) {
                        assertEquals(reference[100 * 2 + channel],
                                sequence[(index * (TRACK_FRAMES + GAP) + 100) * 2 + channel],
                                0.00002f, "playlist conversion must preserve each channel's level");
                    }
                }
            }
        }
    }

    private static void checkPlaylist(boolean orc, boolean original, List<Integer> order)
            throws Exception {
        List<String> reads = new ArrayList<>();
        AudioMixer mixer = new AudioMixer();
        CdMusic disc = new CdMusic(source(reads), mixer);
        try (SoundServer server = new SoundServer(mixer, disc, null, SoundServer.Backend.CD)) {
            assertTrue(server.playBattleMusic(orc, original), "the battle playlist must start");
            float[] output =
                    render(disc, mixer, (order.size() - 1) * (TRACK_FRAMES + GAP) + TRACK_FRAMES);
            double gain = Math.pow(10, -12.0 / 20);
            for (int visit = 0; visit < order.size(); visit++) {
                int beginning = visit * (TRACK_FRAMES + GAP);
                float expected = (float) ((orc ? -1 : 1) * (1000 + 200 * order.get(visit)) / 32768.0
                        * gain / Math.sqrt(2));
                assertEquals(expected, output[(beginning + 80) * 2], 0.00002f,
                        "the actual PCM must identify battle " + order.get(visit) + " at visit "
                                + visit);
                if (visit + 1 < order.size()) {
                    for (int frame = beginning + TRACK_FRAMES;
                            frame < beginning + TRACK_FRAMES + GAP; frame++) {
                        assertEquals(0f, output[frame * 2],
                                "the next track must wait the full two-second gap");
                    }
                }
            }
            assertEquals(order.stream().map(n -> (orc ? "Orc" : "Human") + " Battle " + n).toList(),
                    reads, "decoding must visit every selected recording and wrap");
        }
    }

    private static float[] render(CdMusic disc, AudioMixer mixer, int frames) throws Exception {
        float[] all = new float[frames * 2];
        float[] block = new float[1024];
        for (int at = 0; at < frames; at += 512) {
            int count = Math.min(512, frames - at);
            assertTrue(disc.awaitBufferedFrames(count),
                    "recorded audio must be available before offline rendering");
            mixer.render(block, count);
            System.arraycopy(block, 0, all, at * 2, count * 2);
        }
        return all;
    }

    private static double energy(float[] samples) {
        double total = 0;
        for (float sample : samples) total += sample * sample;
        return total;
    }

    private static AssetSource source(List<String> reads) {
        List<AssetSource.MusicTrack> tracks = new ArrayList<>();
        for (String race : List.of("Human", "Orc"))
            for (int n = 1; n <= 6; n++)
                tracks.add(new AssetSource.MusicTrack(race + " Battle " + n, 44100, 1, 147));
        tracks.add(new AssetSource.MusicTrack("Main Menu", 48000, 1, TRACK_FRAMES));
        return (AssetSource) Proxy.newProxyInstance(AssetSource.class.getClassLoader(),
                new Class<?>[] {AssetSource.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "musicTracks" -> tracks;
                    case "musicSamples" -> {
                        int index = (Integer) args[0];
                        synchronized (reads) {
                            reads.add(tracks.get(index).name());
                        }
                        short[] samples = new short[(int) tracks.get(index).frames()];
                        Arrays.fill(samples,
                                (short) ((index >= 6 && index < 12 ? -1 : 1)
                                        * (1000 + 200 * (index % 6 + 1))));
                        yield samples;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
