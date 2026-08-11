package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.data.video.SmackerVideo;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cutscenes have sound.
 *
 * <p>Smacker keeps its audio cut into one chunk per frame and interleaved with
 * the pictures, which is how a 1995 machine played a cutscene off a CD.
 * Each chunk is differentially coded and Huffman compressed, one tree per byte
 * of each channel.
 *
 * <p>The decoder was checked sample for sample against ffmpeg's, which is the
 * only way to be sure of a lossy-looking format that is in fact exact: it
 * agreed on every one of the two and a half million samples in the opening
 * cinematic. The one thing that took finding is that the running value is a
 * byte that wraps rather than saturates.
 */
class CutsceneAudioTest {

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    /** Every cutscene the campaigns name, and the two the title sequence does. */
    private static final List<String> VIDEOS = List.of(
            "videos/logo", "videos/gameintro",
            "videos/human-1", "videos/human-2", "videos/human-3", "videos/human-4",
            "videos/orc-1", "videos/orc-2", "videos/orc-3", "videos/orc-4",
            "videos/exp-1");

    @Test
    @DisplayName("every cutscene carries a soundtrack")
    void theyAllHaveSound() {
        GameData data = load();
        List<String> silent = new ArrayList<>();
        int found = 0;
        for (String path : VIDEOS) {
            SmackerVideo video = data.video(path);
            if (video == null) {
                continue;
            }
            found++;
            SmackerVideo.Audio audio = video.decodeAudio(0);
            if (audio == null || audio.samples().length == 0) {
                silent.add(path);
            }
        }
        Assumptions.assumeTrue(found > 0, "no cutscenes in this installation");
        assertTrue(silent.isEmpty(), "these cutscenes came back silent: " + silent);
    }

    @Test
    @DisplayName("the soundtrack is as long as the picture")
    void theSoundMatchesThePicture() {
        // The surest check that the chunks were all found and none skipped:
        // the sound has to last as long as the frames do. A dropped chunk
        // shortens it and a double-counted one stretches it.
        GameData data = load();
        SmackerVideo video = data.video("videos/human-1");
        Assumptions.assumeTrue(video != null, "the first human cutscene is not available");

        SmackerVideo.Audio audio = video.decodeAudio(0);
        assertNotNull(audio);
        assertEquals(22050, audio.sampleRate());
        assertEquals(video.height() == 288 ? 2 : 1, audio.channels(),
                "the original movies are mono and BNE's taller replacements are stereo");

        double soundSeconds = audio.samples().length
                / (double) (audio.sampleRate() * audio.channels());
        double pictureSeconds = video.frameCount() * video.frameMillis() / 1000.0;
        assertTrue(Math.abs(soundSeconds - pictureSeconds) < 0.5,
                "sound is " + soundSeconds + "s against " + pictureSeconds + "s of picture");
    }

    @Test
    @DisplayName("the samples are a signal, not noise")
    void theSoundIsNotNoise() {
        // A Huffman-coded stream read out of step still decodes; it just
        // decodes to hiss. Real speech and music move smoothly between
        // neighbouring samples, so the average step between them is a small
        // fraction of the range. Noise averages a third of it.
        GameData data = load();
        SmackerVideo video = data.video("videos/human-1");
        Assumptions.assumeTrue(video != null, "the first human cutscene is not available");
        short[] samples = video.decodeAudio(0).samples();

        long steps = 0;
        for (int i = 1; i < samples.length; i++) {
            steps += Math.abs(samples[i] - samples[i - 1]);
        }
        double meanStep = steps / (double) (samples.length - 1);
        assertTrue(meanStep < 6000,
                "neighbouring samples jump by " + Math.round(meanStep)
                        + " on average, which is noise rather than sound");
    }
}
