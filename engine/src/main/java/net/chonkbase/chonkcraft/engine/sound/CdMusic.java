package net.chonkbase.chonkcraft.engine.sound;

import java.util.ArrayList;
import java.util.List;
import net.chonkbase.runtime.audio.AudioBus;
import net.chonkbase.runtime.audio.AudioMixer;
import net.chonkbase.runtime.audio.PcmClip;
import net.chonkbase.runtime.audio.PcmFormat;
import net.chonkbase.chonkcraft.data.source.AssetSource;

/**
 * Plays Warcraft II's red book music.
 *
 * <p>The DOS release has its soundtrack twice over. A sound card gets the
 * eighteen XMI tracks through a synthesiser, which is what {@link MusicPlayer}
 * handles; the disc carries the same music recorded, and it is not close.
 * Thirty-three tracks across the two discs, eighty-nine minutes of it.
 *
 * <p>Tracks go through the sample mixer rather than the synthesiser, so this
 * is a different path from {@code MusicPlayer} rather than a variation of it:
 * one produces MIDI events and the other produces audio.
 *
 * <p>A track is loaded whole rather than streamed. Four minutes of stereo at
 * the mixer's rate is forty megabytes, which is worth it for a soundtrack that
 * plays continuously and would otherwise need a reader thread feeding the
 * mixer without ever falling behind.
 *
 * <p>Where the recordings come from is no longer this class's business. It
 * used to walk the installation looking for disc images and read raw sectors
 * itself, which meant red book music only existed for a player who still had
 * the discs; the source hands it recorded tracks now, whether they came off a
 * disc image or out of a pack.
 */
public final class CdMusic implements AutoCloseable {

    /**
     * A track, named for the disc and its number.
     *
     * <p>The name is the identity and has not changed: the disc image's stem
     * and the track's own number, "WC2BTDP track 4", which is what
     * {@link #play(String)} looks a track up by. The number is the disc's, so
     * it starts at two -- track one holds the data.
     *
     * <p>Order is the other half of the identity. {@code Main} takes the third
     * track it is given rather than one by name, because that is where the
     * battle music sits on both discs, so a source that returns the same
     * recordings in a different order plays the wrong music without anything
     * looking wrong.
     */
    public record Track(int index, AssetSource.MusicTrack recording) {

        /** What the track is called, disc stem and number. */
        public String name() {
            return recording.name();
        }

        /** How long it runs, in seconds. */
        public double seconds() {
            return recording.seconds();
        }
    }

    /**
     * How far under full scale the score sits.
     *
     * <p>Named rather than written into the one call that uses it, because
     * {@code GameAudio.CUTSCENE_GAIN_DB} is set relative to this figure and the
     * two want to be read together. Measured off the discs: the track the map
     * plays is at -9.8 dBFS over its loudest three hundred milliseconds, so
     * this puts it at -21.8, roughly ten decibels under the game's own effects,
     * which is where background music belongs.
     */
    public static final float BACKGROUND_GAIN_DB = -12f;

    private final List<Track> tracks = new ArrayList<>();
    private final AssetSource source;
    private final AudioMixer mixer;

    private long voice = AudioMixer.NO_VOICE;
    private String playing;

    /**
     * Takes whatever recorded music the source has.
     *
     * @param source where the recordings come from
     * @param mixer  where the samples go
     */
    public CdMusic(AssetSource source, AudioMixer mixer) {
        this.source = source;
        this.mixer = mixer;
        if (source == null) {
            return;
        }
        List<AssetSource.MusicTrack> recordings = source.musicTracks();
        for (int index = 0; index < recordings.size(); index++) {
            tracks.add(new Track(index, recordings.get(index)));
        }
    }

    /** Whether there is any red book music to play. */
    public boolean isAvailable() {
        return !tracks.isEmpty();
    }

    /** Every track found, across every disc. */
    public List<Track> tracks() {
        return List.copyOf(tracks);
    }

    /** What is playing, or null. */
    public String playing() {
        return playing;
    }

    /**
     * Plays a track, looping, replacing whatever was playing.
     *
     * @return whether it started
     */
    public boolean play(Track track) {
        if (track == null || source == null) {
            return false;
        }
        stop();
        // Read whole, not streamed: see the class comment.
        short[] samples = source.musicSamples(track.index());
        if (samples.length == 0) {
            return false;
        }

        // The track's own rate, not the disc's nominal 44,100. A pack is free
        // to hold a recording at whatever rate it was encoded at, and a
        // hardcoded rate here would play it at the wrong speed rather than
        // resample it.
        int channels = track.recording().channels();
        short[] atMixerRate = resample(samples, channels,
                track.recording().sampleRate(), PcmFormat.GAME_SAMPLE_RATE);
        PcmClip clip = new PcmClip(track.name(), channels, atMixerRate);
        // Quieter than the effects, as background music has to be: the mixer's
        // gain is in decibels and this is about a quarter of full.
        //
        // The priority used to be zero, and zero is the lowest number in the
        // port. See GameAudio.MUSIC_PRIORITY: it made the soundtrack the voice
        // the mixer always stole when a battle filled all thirty-two, and
        // nothing here ever started it again, so the music stopped for good the
        // first time a dozen footmen swung at once.
        voice = mixer.play(clip, AudioBus.MUSIC, true, BACKGROUND_GAIN_DB, 0f,
                GameAudio.MUSIC_PRIORITY);
        playing = voice == AudioMixer.NO_VOICE ? null : track.name();
        return playing != null;
    }

    /** Plays the track with a given number on the first disc that has it. */
    public boolean play(String name) {
        for (Track track : tracks) {
            if (track.name().equals(name)) {
                return play(track);
            }
        }
        return false;
    }

    public void stop() {
        if (voice != AudioMixer.NO_VOICE) {
            mixer.stop(voice);
            voice = AudioMixer.NO_VOICE;
        }
        playing = null;
    }

    /**
     * Linear resampling from the disc's rate to the mixer's.
     *
     * <p>44,100 to 48,000 is not a whole ratio, so this interpolates rather
     * than repeating samples: at that closeness, nearest-neighbour is audible
     * as a rasp on sustained notes, which a soundtrack is mostly made of.
     */
    static short[] resample(short[] samples, int channels, int fromRate, int toRate) {
        if (fromRate == toRate) {
            return samples;
        }
        int inFrames = samples.length / channels;
        long outFrames = (long) inFrames * toRate / fromRate;
        short[] out = new short[(int) outFrames * channels];
        double step = (double) fromRate / toRate;
        for (int frame = 0; frame < outFrames; frame++) {
            double at = frame * step;
            int index = (int) at;
            double fraction = at - index;
            for (int channel = 0; channel < channels; channel++) {
                int a = index * channels + channel;
                int b = Math.min(a + channels, samples.length - 1);
                double value = samples[a] * (1 - fraction) + samples[b] * fraction;
                out[frame * channels + channel] = (short) Math.max(Short.MIN_VALUE,
                        Math.min(Short.MAX_VALUE, Math.round(value)));
            }
        }
        return out;
    }

    @Override
    public void close() {
        stop();
    }
}
