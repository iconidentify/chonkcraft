package net.chonkbase.chonkcraft.data.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The red book music on a Warcraft II disc.
 *
 * <p>The cue-sheet parsing is checked against text, so it runs anywhere. The
 * rest needs a disc image and skips without one.
 */
class CdAudioTest {

    @Test
    @DisplayName("a cue sheet gives every track a start and an end")
    void theCueIsRead() {
        // The shape a CloneCD cue takes, with the index written as "1" rather
        // than "01": matching only the padded form finds no tracks at all,
        // and the disc then looks like it has no music.
        List<String> cue = List.of(
                "FILE \"WC2TOD.img\" BINARY",
                "   TRACK 1 MODE1/2352",
                "   INDEX 1 00:00:00",
                "   TRACK 2 AUDIO",
                "   INDEX 1 14:27:17",
                "   TRACK 3 AUDIO",
                "   INDEX 1 18:03:33");

        List<CdAudio.Track> tracks = CdAudio.parseCue(cue, 100_000);
        assertEquals(3, tracks.size());

        assertFalse(tracks.get(0).audio(), "the first track is the data track");
        assertEquals(0, tracks.get(0).startLba());

        // 14:27:17 is (14*60 + 27) * 75 + 17, which is what the .ccd beside
        // the image records as this track's absolute sector.
        assertTrue(tracks.get(1).audio());
        assertEquals(65042, tracks.get(1).startLba());
        // A track ends where the next begins; the cue never says so itself.
        assertEquals(81258, tracks.get(1).endLba());
        assertEquals(81258, tracks.get(2).startLba());
        // The last runs to the end of the image.
        assertEquals(100_000, tracks.get(2).endLba());
    }

    @Test
    @DisplayName("a track's length follows from its sector count")
    void trackLengthIsSectors() {
        // Seventy-five sectors to the second, so one second exactly.
        CdAudio.Track track = new CdAudio.Track(2, true, 1000, 1075);
        assertEquals(1.0, track.seconds(), 1e-9);
    }

    @Test
    @DisplayName("the discs carry the recorded soundtrack")
    void theDiscsCarryMusic() throws IOException {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        List<Path> discs = CdAudio.discsUnder(install.root());
        Assumptions.assumeTrue(!discs.isEmpty(), "no disc images beside this installation");

        int music = 0;
        double seconds = 0;
        for (Path disc : discs) {
            try (CdAudio audio = CdAudio.open(disc)) {
                if (audio == null) {
                    continue;
                }
                for (CdAudio.Track track : audio.musicTracks()) {
                    assertTrue(track.endLba() > track.startLba(),
                            "track " + track.number() + " has no length");
                    music++;
                    seconds += track.seconds();
                }
            }
        }
        assertTrue(music >= 16, "expected the soundtrack, found " + music + " tracks");
        assertTrue(seconds > 30 * 60, "only " + (int) seconds + " seconds of music");
    }

    @Test
    @DisplayName("a track reads as real audio rather than silence")
    void aTrackIsAudible() throws IOException {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null, "no installation configured");
        List<Path> discs = CdAudio.discsUnder(install.root());
        Assumptions.assumeTrue(!discs.isEmpty(), "no disc images");

        try (CdAudio audio = CdAudio.open(discs.getFirst())) {
            Assumptions.assumeTrue(audio != null, "the first image is not a readable disc");
            List<CdAudio.Track> music = audio.musicTracks();
            Assumptions.assumeTrue(!music.isEmpty(), "no music on this disc");

            short[] samples = audio.read(music.getFirst());
            assertEquals(0, samples.length % CdAudio.CHANNELS, "not whole stereo frames");
            assertTrue(samples.length > CdAudio.SAMPLE_RATE * 2 * 30,
                    "a music track is minutes long, not " + samples.length + " samples");

            // Raw sectors decode to silence just as readily as to music if the
            // offsets are wrong, so this checks there is a signal.
            long peak = 0;
            double energy = 0;
            for (short sample : samples) {
                peak = Math.max(peak, Math.abs(sample));
                energy += (double) sample * sample;
            }
            double rms = Math.sqrt(energy / samples.length);
            assertTrue(peak > 10_000, "peak of " + peak + " is not a recording");
            assertTrue(rms > 500, "rms of " + (int) rms + " is silence with a click in it");
        }
    }
}
