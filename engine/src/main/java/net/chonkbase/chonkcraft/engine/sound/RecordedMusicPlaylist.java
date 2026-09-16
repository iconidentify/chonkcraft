package net.chonkbase.chonkcraft.engine.sound;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.runtime.audio.PcmFormat;
import net.chonkbase.runtime.audio.PcmStreamDecoder;

/**
 * A battle's recordings and the quiet interval between them.
 *
 * <p>BNE's mode-two rows at 0x4a1898 name the next scene in byte three;
 * 0x440e24 waits 2,000 ms before advancing. Repeating one complete clip used
 * to leave every other recording unheard. This decoder supplies the same
 * succession on the audio timeline, independently of game speed and pause.
 * Only the current recording is resident; the runtime's producer performs
 * decoding away from the mixer thread.
 */
final class RecordedMusicPlaylist implements PcmStreamDecoder {
    static final int GAP_FRAMES = 2 * PcmFormat.GAME_SAMPLE_RATE;
    private static final double MONO_GAIN = Math.sqrt(0.5);
    private final AssetSource source;
    private final List<CdMusic.Track> tracks;
    private final int[] starts;
    private final int[] lengths;
    private final int frames;
    private volatile int position;
    private int loaded = -1;
    private short[] samples;

    RecordedMusicPlaylist(AssetSource source, List<CdMusic.Track> tracks) {
        this.source = source;
        this.tracks = List.copyOf(tracks);
        starts = new int[tracks.size()];
        lengths = new int[tracks.size()];
        int total = 0;
        for (int index = 0; index < tracks.size(); index++) {
            var recording = tracks.get(index).recording();
            starts[index] = total;
            lengths[index] = Math.toIntExact(
                    recording.frames() * PcmFormat.GAME_SAMPLE_RATE / recording.sampleRate());
            total = Math.addExact(total, Math.addExact(lengths[index], GAP_FRAMES));
        }
        if (total == 0) {
            throw new IllegalArgumentException("a battle playlist needs recordings");
        }
        frames = total;
    }

    @Override
    public int channels() {
        return 2;
    }

    @Override
    public int frameCount() {
        return frames;
    }

    /** The display follows the audible end of the producer's lookahead. */
    String playing(long bufferedFrames) {
        int audible = (int) Math.floorMod((long) position - bufferedFrames, frames);
        return tracks.get(trackAt(audible)).name();
    }

    private int trackAt(int frame) {
        int index = Arrays.binarySearch(starts, frame);
        return index >= 0 ? index : -index - 2;
    }

    @Override
    public int readFrames(short[] destination, int offset, int maximum) throws IOException {
        int count = Math.min(maximum, frames - position);
        int remaining = count;
        while (remaining > 0) {
            int index = trackAt(position);
            int within = position - starts[index];
            int part = Math.min(remaining, lengths[index] + GAP_FRAMES - within);
            if (within >= lengths[index]) {
                Arrays.fill(destination, offset * 2, (offset + part) * 2, (short) 0);
            } else {
                part = Math.min(part, lengths[index] - within);
                load(index);
                int channels = tracks.get(index).recording().channels();
                for (int frame = 0; frame < part; frame++) {
                    int input = (within + frame) * channels;
                    int output = (offset + frame) * 2;
                    if (channels == 1) {
                        // A stereo voice bypasses the mixer's mono centre attenuation.
                        // Preserve the level of the same recording played as a clip.
                        short mono = (short) Math.round(samples[input] * MONO_GAIN);
                        destination[output] = mono;
                        destination[output + 1] = mono;
                    } else {
                        destination[output] = samples[input];
                        destination[output + 1] = samples[input + 1];
                    }
                }
            }
            position += part;
            offset += part;
            remaining -= part;
        }
        return count;
    }

    private void load(int index) throws IOException {
        if (loaded == index) {
            return;
        }
        samples = null;
        var track = tracks.get(index);
        var recording = track.recording();
        short[] decoded = source.musicSamples(track.index());
        samples = CdMusic.resample(
                decoded, recording.channels(), recording.sampleRate(), PcmFormat.GAME_SAMPLE_RATE);
        if (samples.length / recording.channels() != lengths[index]) {
            throw new IOException(
                    "recorded track length differs from its catalog: " + track.name());
        }
        loaded = index;
    }

    @Override
    public void seekFrame(int frame) throws IOException {
        if (frame < 0 || frame > frames) {
            throw new IOException("recorded playlist seek outside its tracks");
        }
        position = frame;
    }

    @Override
    public void close() {
        samples = null;
        loaded = -1;
    }
}
