package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.chonkbase.runtime.audio.AudioMixer;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.data.video.SmackerVideo;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.sound.GameAudio;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Skipping a video stops its sound.
 *
 * <p>Reported from play: "I clicked to skip the Blizzard logo and it kept
 * playing the audio even though I had skipped it, I think that's new". It was
 * new; it arrived with the commit that gave the cutscenes sound at all.
 *
 * <p>{@code VideoScreen.play} started the soundtrack with
 * {@code GameAudio.playClip} and threw away the voice it hands back, so
 * {@code finish} -- which both the key listener and the mouse listener call --
 * stopped the frame timer and nothing else. The pictures went away and the
 * narration carried on over whatever came next.
 *
 * <p>What it needed already existed and was already written for this exact
 * situation: {@code GameAudio.stopClip}, whose own comment is about a
 * briefing's voice-over having to stop when the player presses on, after
 * upstream's {@code StopChannel}. The video path simply never called it.
 */
class VideoSkipSilencesTest {

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    @Test
    @DisplayName("Pressing a key during a cutscene silences its soundtrack")
    void skippingSilencesTheSoundtrack() {
        GameData data = load();
        SmackerVideo video = data.video("videos/human-1");
        Assumptions.assumeTrue(video != null, "no cutscene to play");

        GameAudio audio = new GameAudio(data.sounds());
        audio.start();
        Assumptions.assumeTrue(audio.isAvailable(),
                "no audio device, so no voice is ever handed out to stop: "
                        + audio.unavailableReason());
        try {
            VideoScreen screen = new VideoScreen(video, 640, 480, () -> { }, audio);
            screen.play();
            assertNotEquals(AudioMixer.NO_VOICE, screen.voice(),
                    "the cutscene never started a soundtrack, so this proves nothing");

            screen.skip();
            assertEquals(AudioMixer.NO_VOICE, screen.voice(),
                    "the skipped video is still holding the voice it started, which is"
                            + " exactly the reported bug: the picture stops and the sound"
                            + " keeps playing over what comes next");
        } finally {
            audio.close();
        }
    }
}
