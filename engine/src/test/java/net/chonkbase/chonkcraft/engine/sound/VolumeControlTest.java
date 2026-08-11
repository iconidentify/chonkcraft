package net.chonkbase.chonkcraft.engine.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import net.chonkbase.runtime.audio.AudioMixer;
import net.chonkbase.runtime.audio.PcmClip;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two volume sliders, and what each of them is allowed to touch.
 *
 * <p>Reported from play: "the music volume control has no effect". It had one,
 * on half the soundtrack. Warcraft II ships its score twice -- eighteen XMI
 * tracks for a synthesiser and the same music recorded on the discs -- and this
 * port plays the recordings through {@code AudioMixer}'s music bus and the XMI
 * through the JDK's own sequencer, which the mixer never sees. The slider set a
 * bus gain, so it moved the recordings and left the synthesised score exactly
 * where it was. Upstream needs no such care: SDL_mixer sits under both of its
 * backends, so the one {@code Mix_VolumeMusic} in {@code SetMusicVolume}
 * The game covers everything.
 *
 * <p>Nothing here asks a slider what it was set to. The volumes were already
 * held in a field that a test could read, and reading it back proves only that
 * a number was stored: the whole defect is that the number went nowhere. So
 * each check drives the real producer -- {@code CdMusic} for the recordings,
 * {@link MidiVolume} for the synthesiser -- and looks at the samples the mixer
 * would have sent to the speakers, or at the controller bytes the synthesiser
 * would have been sent.
 *
 * <p>No audio device is opened. {@code AudioMixer.render} is exact: given the
 * same commands it produces the same samples whether a sound card is attached
 * or not, which is what lets the one subsystem nobody can see be measured.
 */
class VolumeControlTest {

    /** Long enough for a bus gain to finish its ramp and settle. */
    private static final int SETTLE_FRAMES = 4_096;

    private static final int MEASURE_FRAMES = 4_096;

    /**
     * Under this a voice is off rather than quiet.
     *
     * <p>A slider at nothing is not minus infinity decibels -- the mixer takes
     * no such gain -- so it is sixty decibels down, which renders a tone at half
     * scale as a five ten-thousandths of full. This is above that and fifty
     * decibels below anything audible.
     */
    private static final float INAUDIBLE = 0.002f;

    // ------------------------------------------------------ recorded music

    @Test
    @DisplayName("the music slider moves the recorded soundtrack")
    void theMusicSliderMovesTheRecordedSoundtrack() {
        AudioMixer mixer = new AudioMixer();
        CdMusic disc = new CdMusic(new TonesOnly(), mixer);
        SoundServer server = new SoundServer(mixer, disc, null, SoundServer.Backend.CD);

        server.setMusicVolume(1f);
        assertTrue(disc.play(disc.tracks().get(0)), "the fixture disc would not play");
        float full = peak(mixer);
        assertTrue(full > 0.01f,
                "the soundtrack was inaudible at full volume, so this proves nothing: " + full);

        server.setMusicVolume(0.5f);
        float half = peak(mixer);

        assertTrue(half < full * 0.6f && half > full * 0.4f,
                "half volume rendered at " + half + " against " + full + " at full: the music"
                        + " slider is not reaching the recorded soundtrack");

        server.setMusicVolume(0f);
        assertTrue(peak(mixer) < INAUDIBLE,
                "the music slider at nothing still rendered the soundtrack at "
                        + peak(mixer));
    }

    // --------------------------------------------------- synthesised music

    @Test
    @DisplayName("the music slider moves the synthesised soundtrack")
    void theMusicSliderMovesTheSynthesisedSoundtrack() {
        Listening synthesiser = new Listening();
        MidiVolume volume = new MidiVolume(synthesiser);

        // What Human Battle 1 actually sends: it opens by setting channel
        // volume on nine channels and then sends five hundred more changes
        // over its two and a half minutes. Anything set once at the start is
        // gone within a bar, which is why the slider has to sit in the stream
        // rather than write a value into it.
        volume.setVolume(1f);
        send(volume, 0, 117);
        int atFull = synthesiser.lastVolumeOn(0);
        assertEquals(117, atFull,
                "at full volume the track's own level must pass through untouched");

        synthesiser.forget();
        volume.setVolume(0.5f);
        int atHalf = synthesiser.lastVolumeOn(0);
        assertNotEquals(-1, atHalf,
                "moving the music slider sent the synthesiser nothing at all, which is the"
                        + " reported bug: the XMI half of the soundtrack never sees the slider");
        assertTrue(atHalf < atFull,
                "the slider went down and controller 7 went from " + atFull + " to " + atHalf);

        // And a track that sets its own volume afterwards is still scaled,
        // rather than winning.
        synthesiser.forget();
        send(volume, 0, 117);
        assertEquals(atHalf, synthesiser.lastVolumeOn(0),
                "the track set its own volume and took the slider with it");

        volume.setVolume(0f);
        assertEquals(0, synthesiser.lastVolumeOn(0),
                "the music slider at nothing left the synthesiser playing");
    }

    @Test
    @DisplayName("a track that never sets its own volume still follows the slider")
    void aSilentTrackStillFollowsTheSlider() {
        // Eight of the eighteen tracks send fewer volume changes than they have
        // channels; the orc briefing theme sends eight in thirty-nine seconds.
        // A channel it never mentions has to move with the slider too, or half
        // the orchestra ignores it.
        Listening synthesiser = new Listening();
        MidiVolume volume = new MidiVolume(synthesiser);

        volume.setVolume(0.25f);

        assertEquals(MidiVolume.scaled(MidiVolume.DEFAULT_VOLUME, 0.25f),
                synthesiser.lastVolumeOn(11),
                "channel 11 was never named by the track and was left where it was");
    }

    @Test
    @DisplayName("the two soundtracks move together, so switching between them is not a step")
    void theTwoSoundtracksMoveTogether() {
        // The sample mixer works in decibels and this implementation converts a slider at
        // v to 20*log10(v), so amplitude follows the slider. A MIDI channel
        // volume does not: the MMA curve, which Gervill implements as a concave
        // transform of -960 centibels, attenuates by 40*log10(cc/127), so
        // amplitude follows the square of the controller. If the two are not
        // reconciled, a player who switches backend at half volume hears a jump
        // of six decibels.
        List<String> disagreements = new ArrayList<>();
        int checked = 0;
        for (int tenths = 1; tenths <= 10; tenths++) {
            float slider = tenths / 10f;
            float pcmDb = SoundServer.decibels(slider);
            int asked = 127;
            int sent = MidiVolume.scaled(asked, slider);
            double midiDb = 40.0 * Math.log10(sent / (double) asked);
            checked++;
            if (Math.abs(pcmDb - midiDb) > 0.5) {
                disagreements.add(String.format(
                        "at %.1f the samples move %.2f dB and the synthesiser %.2f dB",
                        slider, pcmDb, midiDb));
            }
        }
        assertEquals(10, checked, "every slider position must be checked");
        assertTrue(disagreements.isEmpty(),
                "the two halves of the soundtrack do not move together, so a player hears a"
                        + " jump when the backend changes under them: " + disagreements);
    }

    // ------------------------------------------------------- crossed wires

    @Test
    @DisplayName("turning the effects off leaves the soundtrack playing")
    void theEffectsSliderLeavesTheMusicAlone() {
        AudioMixer mixer = new AudioMixer();
        CdMusic disc = new CdMusic(new TonesOnly(), mixer);
        SoundServer server = new SoundServer(mixer, disc, null, SoundServer.Backend.CD);
        server.setEffectVolume(1f);
        server.setMusicVolume(1f);
        assertTrue(disc.play(disc.tracks().get(0)), "the fixture disc would not play");
        assertTrue(peak(mixer) > 0.01f, "the soundtrack was inaudible to begin with");

        server.setEffectVolume(0f);

        assertTrue(peak(mixer) > 0.01f,
                "turning the effects off silenced the music as well, which is the same"
                        + " complaint about the other slider");
    }

    @Test
    @DisplayName("turning the music off leaves the narrator talking")
    void theMusicSliderLeavesTheNarrationAlone() {
        // A briefing's narration used to go out on the music bus, so a player
        // who had turned the music down could not hear the mission being
        // explained to them. Upstream reads a briefing with PlaySoundFile
        // (scripts/database.legacy-declaration:544), which takes a sound channel and so the
        // effects volume.
        GameAudio audio = new GameAudio(null);
        audio.startWithoutDevice();
        SoundServer server = new SoundServer(audio.mixer(), null, null, SoundServer.Backend.CD);
        server.setEffectVolume(1f);
        server.setMusicVolume(1f);

        assertNotEquals(AudioMixer.NO_VOICE, audio.playVoiceClip(tone(2.0, 0.5f)),
                "the narration never started, so this proves nothing");
        assertTrue(peak(audio.mixer()) > 0.01f, "the narration was inaudible to begin with");

        server.setMusicVolume(0f);

        assertTrue(peak(audio.mixer()) > 0.01f,
                "turning the music off silenced the briefing's narrator, which is what"
                        + " putting speech on the music bus does");

        server.setEffectVolume(0f);
        assertTrue(peak(audio.mixer()) < INAUDIBLE,
                "the effects slider does not reach the narration either, so nothing does");
    }

    // ------------------------------------------------------------ fixtures

    /** The loudest sample the mixer would send to the speakers, after settling. */
    private static float peak(AudioMixer mixer) {
        float[] block = new float[SETTLE_FRAMES * AudioMixer.OUTPUT_CHANNELS];
        mixer.render(block, SETTLE_FRAMES);
        mixer.render(block, MEASURE_FRAMES);
        float peak = 0f;
        for (int i = 0; i < MEASURE_FRAMES * AudioMixer.OUTPUT_CHANNELS; i++) {
            peak = Math.max(peak, Math.abs(block[i]));
        }
        return peak;
    }

    /** A steady tone, which is the easiest thing to measure a gain on. */
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

    private static void send(MidiVolume volume, int channel, int value) {
        try {
            volume.send(new ShortMessage(ShortMessage.CONTROL_CHANGE, channel,
                    MidiVolume.CHANNEL_VOLUME, value), -1L);
        } catch (InvalidMidiDataException e) {
            throw new AssertionError(e);
        }
    }

    /** A synthesiser that remembers the last channel volume it was told. */
    private static final class Listening implements Receiver {
        private final int[] volumes = new int[MidiVolume.CHANNELS];

        Listening() {
            forget();
        }

        void forget() {
            java.util.Arrays.fill(volumes, -1);
        }

        int lastVolumeOn(int channel) {
            return volumes[channel];
        }

        @Override
        public void send(MidiMessage message, long timeStamp) {
            if (message instanceof ShortMessage shortMessage
                    && shortMessage.getCommand() == ShortMessage.CONTROL_CHANGE
                    && shortMessage.getData1() == MidiVolume.CHANNEL_VOLUME) {
                volumes[shortMessage.getChannel()] = shortMessage.getData2();
            }
        }

        @Override
        public void close() {
        }
    }

    /**
     * A source with recorded music and nothing else.
     *
     * <p>A real shape rather than a convenience: a pack can carry the
     * soundtrack and no maps. Two seconds of tone rather than the real
     * two-hundred-second track, because forty megabytes of resampled disc audio
     * measures the same gain as two seconds of it does.
     */
    private static final class TonesOnly implements AssetSource {
        private static final int RATE = 44_100;
        private static final int FRAMES = RATE * 2;

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
            return List.of(new MusicTrack("fixture track 2", RATE, 1, FRAMES));
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
