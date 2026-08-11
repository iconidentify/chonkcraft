package net.chonkbase.chonkcraft.engine.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;
import net.chonkbase.runtime.audio.AudioMixer;
import net.chonkbase.runtime.audio.PcmFormat;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Red book music through the sample mixer. */
class CdMusicTest {

    /**
     * Red book's sample rate, spelled out rather than taken from {@code CdAudio}.
     *
     * <p>{@code CdAudio} is a disc reader and the engine may not name one --
     * see {@code NoInstallDirectoryTest}. Nothing is lost by writing the number
     * here: 44,100 is fixed by the compact disc standard, not by that class,
     * and the subject below is the arithmetic that turns it into the mixer's
     * rate.
     */
    private static final int RED_BOOK_RATE = 44_100;

    @Test
    @DisplayName("resampling 44,100 to the mixer's rate keeps the length and the signal")
    void resamplingIsProportionate() {
        // A second of a tone, stereo.
        int frames = RED_BOOK_RATE;
        short[] input = new short[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            short value = (short) (Math.sin(frame * 2 * Math.PI * 440 / RED_BOOK_RATE)
                    * 20_000);
            input[frame * 2] = value;
            input[frame * 2 + 1] = value;
        }

        short[] output = CdMusic.resample(input, 2, RED_BOOK_RATE,
                PcmFormat.GAME_SAMPLE_RATE);
        assertEquals(PcmFormat.GAME_SAMPLE_RATE * 2, output.length,
                "a second in is a second out");

        // Interpolated rather than repeated, so the peak survives.
        int peak = 0;
        for (short sample : output) {
            peak = Math.max(peak, Math.abs(sample));
        }
        assertTrue(peak > 19_000, "peak fell to " + peak + ": the tone was mangled");
    }

    @Test
    @DisplayName("the same rate is passed through untouched")
    void sameRateIsUntouched() {
        short[] input = {1, 2, 3, 4};
        assertEquals(input, CdMusic.resample(input, 2, 48_000, 48_000));
    }

    @Test
    @DisplayName("the discs beside an installation are found")
    void discsAreFound() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null, "no installation configured");
        // The guard counts disc image files on disk, not the source's own
        // track list. Asking the source would make the assertion below a
        // restatement of the guard -- CdMusic is built out of musicTracks() --
        // and this test would pass having found nothing. It counts the files
        // itself rather than calling CdAudio.discsUnder, which is a disc
        // reader and so a type the engine may not name; see
        // NoInstallDirectoryTest. What is wanted here is only "are there any
        // .img files under there", which is a question about a directory.
        Assumptions.assumeTrue(discImagesUnder(install.root()) > 0, "no disc images");

        CdMusic music = new CdMusic(install, new AudioMixer());
        assertTrue(music.isAvailable(), "discs are present but no music was found");
        assertTrue(music.tracks().size() >= 16,
                "found only " + music.tracks().size() + " tracks");
        for (CdMusic.Track track : music.tracks()) {
            assertTrue(track.seconds() > 1, track.name() + " is under a second long");
        }
        music.close();
    }

    /**
     * How many readable disc images lie under a directory.
     *
     * <p>A disc is an {@code .img} with a cue sheet of the same stem beside
     * it: the image holds the raw sectors and the cue says where each track
     * begins, and without the second one there is no way to know where the
     * music is. That pairing is what {@code CdAudio.discsUnder} looks for, and
     * it is repeated here rather than called for the reason given at the call
     * site.
     */
    private static int discImagesUnder(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return (int) walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .toLowerCase(Locale.ROOT).endsWith(".img"))
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        String stem = name.substring(0, name.lastIndexOf('.'));
                        return Files.isRegularFile(path.resolveSibling(stem + ".cue"));
                    })
                    .count();
        } catch (java.io.IOException e) {
            return 0;
        }
    }
}
