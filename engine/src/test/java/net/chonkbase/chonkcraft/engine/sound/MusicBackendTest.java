package net.chonkbase.chonkcraft.engine.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import net.chonkbase.runtime.audio.AudioMixer;
import net.chonkbase.runtime.audio.PcmClip;
import net.chonkbase.chonkcraft.data.GraphicsIndex;
import net.chonkbase.chonkcraft.data.NameTable;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which of the two recordings of the soundtrack plays, and what happens to the
 * other one.
 *
 * <p>Warcraft II ships its score twice. Eighteen XMI tracks go to a
 * synthesiser; the discs carry the same music recorded, thirty-three tracks and
 * eighty-nine minutes of it. This implementation chose between them on one line in the
 * launcher -- the disc won whenever there was one -- and that line held two
 * faults.
 *
 * <p>The first is what a player reported: "I think after I started the game and
 * was on the map, the music from the last video / cutscene was still playing,
 * causing confusion... now that the cutscene video is done I do have control
 * over the music." The disc branch started a red book track and never touched
 * the sequencer, so the briefing theme a campaign had just started went on
 * playing over the map for the rest of its fifty-two seconds, and a skirmish
 * launch was worse: the menu leaves {@code Orc Briefing} in the playlist, and a
 * playlist restarts itself forever, so the menu theme played over the whole
 * game. Neither could be turned down, because the music slider set a bus gain
 * and the sequencer is not on the bus. Then the sequence ran out, and control
 * came back.
 *
 * <p>The second is that there was no choice: a player who preferred the
 * synthesised score had no way to ask for it.
 *
 * <p>Nothing here opens a synthesiser or a sound device. {@code MusicPlayer}
 * knows its eighteen tracks from the bundled conversion table without one, and
 * {@code AudioMixer} renders the same samples either way.
 */
class MusicBackendTest {

    @Test
    @DisplayName("the setting picks the synthesised score over the discs")
    void theSettingSelectsTheSynthesiser() {
        CdMusic disc = new CdMusic(new TonesOnly(), new AudioMixer());
        MusicPlayer synth = synthesiser();
        assertTrue(disc.isAvailable(), "the fixture must have discs or it proves nothing");
        assertFalse(synth.tracks().isEmpty(), "the fixture must have XMI or it proves nothing");

        SoundServer wantsDiscs = new SoundServer(new AudioMixer(), disc, synth,
                SoundServer.Backend.CD);
        assertEquals(SoundServer.Backend.CD, wantsDiscs.backend());
        wantsDiscs.playBattleMusic(false);
        assertNotNull(disc.playing(),
                "with both available and the discs asked for, the discs must be driving");

        disc.stop();
        SoundServer wantsSynth = new SoundServer(new AudioMixer(), disc, synth,
                SoundServer.Backend.XMI);

        assertEquals(SoundServer.Backend.XMI, wantsSynth.backend(),
                "the setting asked for the synthesised score and was overruled");
        wantsSynth.playBattleMusic(false);
        assertNull(disc.playing(),
                "the setting asked for the synthesised score and the discs played anyway,"
                        + " which is the one line in the launcher this replaces");
        assertEquals(List.of("Human Battle 1", "Human Battle 2", "Human Battle 3",
                        "Human Battle 4", "Human Battle 5"), synth.playlist(),
                "the synthesised backend is the one that was handed the mission playlist");
    }

    @Test
    @DisplayName("named Battle.net recordings follow the map's race")
    void namedBattleNetRecordingsFollowTheRace() {
        CdMusic recordings = new CdMusic(
                new TonesOnly("Human Battle 1", "Orc Battle 1"), new AudioMixer());
        SoundServer server = new SoundServer(new AudioMixer(), recordings, null,
                SoundServer.Backend.CD);

        assertTrue(server.playBattleMusic(true));
        assertEquals("Orc Battle 1", recordings.playing());

        assertTrue(server.playBattleMusic(false));
        assertEquals("Human Battle 1", recordings.playing());
    }

    @Test
    @DisplayName("Battle.net menus use the recorded theme by default")
    void battleNetMenusUseTheRecordedTheme() {
        CdMusic recordings = new CdMusic(
                new TonesOnly("Orc Briefing", "Main Menu"), new AudioMixer());
        MusicPlayer synth = synthesiser();
        SoundServer server = new SoundServer(new AudioMixer(), recordings, synth,
                SoundServer.Backend.CD);

        assertTrue(server.playMenuMusic());
        assertEquals("Main Menu", recordings.playing());
        assertTrue(synth.playlist().isEmpty(),
                "the synthetic menu playlist remained armed under recorded music");
    }

    @Test
    @DisplayName("a setting for a soundtrack this installation does not have falls back")
    void anImpossibleSettingFallsBack() {
        // A DOS hard-disk install has no recordings until the disc is cached,
        // and an installation without snddat.war has no XMI. Either side can be
        // empty on a real machine, and a setting that produces silence rather
        // than the other soundtrack is a setting that looks broken.
        MusicPlayer synth = synthesiser();
        SoundServer noDiscs = new SoundServer(new AudioMixer(),
                new CdMusic(null, new AudioMixer()), synth, SoundServer.Backend.CD);
        assertEquals(SoundServer.Backend.XMI, noDiscs.backend(),
                "with no discs the synthesised score is the only one there is");

        SoundServer noXmi = new SoundServer(new AudioMixer(),
                new CdMusic(new TonesOnly(), new AudioMixer()), null, SoundServer.Backend.XMI);
        assertEquals(SoundServer.Backend.CD, noXmi.backend(),
                "with no XMI the recordings are the only ones there are");
    }

    @Test
    @DisplayName("starting the map's music silences the theme the last screen left playing")
    void startingAMapSilencesTheOtherBackend() {
        // The reported bug. The menu sets its playlist and leaves it in place;
        // a campaign plays the briefing theme and then loads the map. Both go
        // through the sequencer, and the disc branch of the launcher never
        // touched it.
        MusicPlayer synth = synthesiser();
        synth.setPlaylist(MusicPlayer.menuTracks());
        assertFalse(synth.playlist().isEmpty(),
                "the menu theme must be queued or this proves nothing");

        CdMusic disc = new CdMusic(new TonesOnly(), new AudioMixer());
        SoundServer server = new SoundServer(new AudioMixer(), disc, synth,
                SoundServer.Backend.CD);
        server.playBattleMusic(true);

        assertNotNull(disc.playing(), "the map's own music did not start");
        assertTrue(synth.playlist().isEmpty(),
                "the menu theme is still queued, so the sequencer starts it again the moment"
                        + " the current track ends and it plays over the map for the whole"
                        + " session, with no slider able to touch it");
    }

    @Test
    @DisplayName("switching soundtrack under a running game stops the one that was playing")
    void switchingBackendStopsTheOldOne() {
        MusicPlayer synth = synthesiser();
        AudioMixer mixer = new AudioMixer();
        CdMusic disc = new CdMusic(new TonesOnly(), mixer);
        SoundServer server = new SoundServer(mixer, disc, synth,
                SoundServer.Backend.CD);
        server.playBattleMusic(false);
        assertNotNull(disc.playing(), "the recorded soundtrack did not start");
        assertTrue(peak(mixer) > 0.01f,
                "the recorded soundtrack was not audible, so this proves nothing");

        server.setBackend(SoundServer.Backend.XMI);

        assertEquals(SoundServer.Backend.XMI, server.backend(), "the switch did not take");
        assertNull(disc.playing(),
                "both soundtracks are now playing over each other, which is the fault this"
                        + " setting must not introduce");
        assertEquals(0f, peak(mixer), 1e-7f,
                "the CD voice still rendered after the synthesised soundtrack was selected");
        server.close();
    }

    @Test
    @DisplayName("a map takes soundtrack focus from the front-end audio device")
    void aMapSilencesAFrontEndServerOnAnotherMixer() {
        AudioMixer frontMixer = new AudioMixer();
        CdMusic frontDisc = new CdMusic(new TonesOnly("Main Menu"), frontMixer);
        SoundServer front = new SoundServer(frontMixer, frontDisc, synthesiser(),
                SoundServer.Backend.CD);
        assertTrue(front.playMenuMusic());
        assertTrue(peak(frontMixer) > 0.01f,
                "the front-end recording was not audible, so this proves nothing");

        AudioMixer gameMixer = new AudioMixer();
        CdMusic gameDisc = new CdMusic(new TonesOnly("Human Battle 1"), gameMixer);
        SoundServer game = new SoundServer(gameMixer, gameDisc, synthesiser(),
                SoundServer.Backend.CD);
        assertTrue(game.playBattleMusic(false));

        assertNull(frontDisc.playing(),
                "the menu CD player remained armed after the map took soundtrack focus");
        assertEquals(0f, peak(frontMixer), 1e-7f,
                "music from the old screen is still audible on its separate output device");
        assertTrue(peak(gameMixer) > 0.01f, "the map soundtrack did not replace it");

        game.setBackend(SoundServer.Backend.XMI);
        game.setMusicVolume(0f);
        assertEquals(0f, peak(frontMixer), 1e-7f,
                "the orphaned CD track ignored the in-game music volume");
        assertEquals(0f, peak(gameMixer), 1e-7f,
                "the map's CD track survived the source switch");
        game.close();
        front.close();
    }

    @Test
    @DisplayName("the soundtrack survives a battle that fills every voice")
    void theSoundtrackIsNotStolenByABattle() {
        // The mixer holds thirty-two voices and steals the lowest-priority
        // oldest one when a thirty-third starts. The soundtrack asked for
        // priority zero, the lowest number in the implementation, so it was the unique
        // global minimum and always the voice chosen -- and nothing ever
        // started it again. A dozen footmen swinging at once took the music out
        // for the rest of the session.
        GameAudio audio = new GameAudio(null);
        audio.startWithoutDevice();
        CdMusic disc = new CdMusic(new TonesOnly(), audio.mixer());
        assertTrue(disc.play(disc.tracks().get(0)), "the fixture disc would not play");
        float before = peakOfMusicAlone(audio.mixer());
        assertTrue(before > 0.01f, "the soundtrack was inaudible to begin with: " + before);

        // Forty at once, on the one mixer the game runs on, which is thirty-two
        // voices wide. A dozen footmen swinging reaches this.
        for (int i = 0; i < 40; i++) {
            audio.playVoiceClip(tone(1.0, 0.5f));
        }

        assertTrue(peakOfMusicAlone(audio.mixer()) > 0.01f,
                "the battle stole the soundtrack and nothing puts it back: the map runs in"
                        + " silence from the first big fight onwards");
    }

    // ------------------------------------------------------------ fixtures

    /**
     * Renders with everything but the music turned right down.
     *
     * <p>So that the forty effects cannot be mistaken for the soundtrack still
     * being there. The music bus is the only one left audible.
     */
    private static float peakOfMusicAlone(AudioMixer mixer) {
        mixer.setBusGainDb(net.chonkbase.runtime.audio.AudioBus.WORLD, -80f, 0);
        mixer.setBusGainDb(net.chonkbase.runtime.audio.AudioBus.UI, -80f, 0);
        mixer.setBusGainDb(net.chonkbase.runtime.audio.AudioBus.VOICE, -80f, 0);
        float[] block = new float[4_096 * AudioMixer.OUTPUT_CHANNELS];
        mixer.render(block, 4_096);
        mixer.render(block, 4_096);
        float peak = 0f;
        for (float sample : block) {
            peak = Math.max(peak, Math.abs(sample));
        }
        return peak;
    }

    /** Renders enough frames to apply queued starts, stops and gain changes. */
    private static float peak(AudioMixer mixer) {
        float[] block = new float[4_096 * AudioMixer.OUTPUT_CHANNELS];
        mixer.render(block, 4_096);
        float peak = 0f;
        for (float sample : block) {
            peak = Math.max(peak, Math.abs(sample));
        }
        return peak;
    }

    private static PcmClip tone(double seconds, float amplitude) {
        int rate = AudioMixer.SAMPLE_RATE;
        int frames = (int) (seconds * rate);
        short[] samples = new short[frames];
        for (int frame = 0; frame < frames; frame++) {
            samples[frame] = (short) (Math.sin(frame * 2 * Math.PI * 440 / rate)
                    * amplitude * Short.MAX_VALUE);
        }
        return new PcmClip("tone", 1, samples);
    }

    /**
     * A player over the real conversion table, with no archive and no device.
     *
     * <p>{@code GraphicsIndex.load} reads the bundled {@code graphics-index.tsv}
     * rather than the game archives, so the eighteen track names are present
     * without an installation.
     */
    private static MusicPlayer synthesiser() {
        return new MusicPlayer(null, GraphicsIndex.load(names("Footman")), new Random(1));
    }

    /** A minimal name table; the music rows do not depend on its contents. */
    private static NameTable names(String... strings) {
        int count = strings.length + 1;
        int headerBytes = count * 2;

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int[] offsets = new int[count];
        for (int i = 0; i < strings.length; i++) {
            offsets[i + 1] = headerBytes + body.size();
            body.writeBytes(strings[i].getBytes(StandardCharsets.ISO_8859_1));
            body.write(0);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(count & 0xFF);
        out.write((count >>> 8) & 0xFF);
        for (int i = 1; i < count; i++) {
            out.write(offsets[i] & 0xFF);
            out.write((offsets[i] >>> 8) & 0xFF);
        }
        out.writeBytes(body.toByteArray());
        return NameTable.from(out.toByteArray());
    }

    /**
     * A source with recorded music and nothing else.
     *
     * <p>A real shape rather than a convenience: a pack can carry the
     * soundtrack and no maps. Two seconds of tone rather than the real
     * two-hundred-second track, because forty megabytes of resampled disc audio
     * behaves the same way in the mixer as two seconds of it does.
     */
    private static final class TonesOnly implements AssetSource {
        private static final int RATE = 44_100;
        private static final int FRAMES = RATE * 2;
        private final List<String> names;

        TonesOnly(String... names) {
            this.names = names.length == 0
                    ? List.of("fixture track 2", "fixture track 3", "fixture track 4")
                    : List.of(names);
        }

        @Override
        public String describe() {
            return "a fixture holding one recorded track";
        }

        @Override
        public net.chonkbase.chonkcraft.data.source.EntryArchive archive(int archiveId) {
            return null;
        }

        @Override
        public boolean hasExpansion() {
            return false;
        }

        @Override
        public boolean isExpansionRelease() {
            return false;
        }

        @Override
        public boolean isBattleNetEdition() {
            return false;
        }

        @Override
        public int campaignTextOffset() {
            return 0;
        }

        @Override
        public List<String> mapNames() {
            return List.of();
        }

        @Override
        public byte[] map(String name) {
            return null;
        }

        @Override
        public List<MusicTrack> musicTracks() {
            return names.stream()
                    .map(name -> new MusicTrack(name, RATE, 1, FRAMES))
                    .toList();
        }

        @Override
        public short[] musicSamples(int index) {
            short[] samples = new short[FRAMES];
            for (int frame = 0; frame < FRAMES; frame++) {
                samples[frame] = (short) (Math.sin(frame * 2 * Math.PI * 440 / RATE)
                        * 0.5 * Short.MAX_VALUE);
            }
            return samples;
        }

        @Override
        public void close() {
        }
    }
}
