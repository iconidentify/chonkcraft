package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.chonkbase.runtime.audio.AudioMixer;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.data.video.SmackerVideo;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.campaign.Campaign;
import net.chonkbase.chonkcraft.engine.campaign.CampaignStep;
import net.chonkbase.chonkcraft.engine.sound.GameAudio;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A screen that starts a sound stops it again when it goes.
 *
 * <p>The same defect twice, a year apart, in the same shape.
 * {@code GameAudio.playMusicClip} hands back a voice so that {@code stopClip}
 * can silence it, after upstream's {@code StopChannel}; a caller that drops the
 * voice has started a sound that nothing can stop. First the cutscenes: "I
 * clicked to skip the Blizzard logo and it kept playing the audio even though I
 * had skipped it". Then the act cards, one file away, which nobody looked at
 * because the first fix was written as a fix to {@code VideoScreen}.
 *
 * <p>The act card is the one that hides. The fanfare is five seconds --
 * {@code sounds/human/act.wav} is 5.02 and {@code sounds/orc/act.wav} is 5.03,
 * measured, and all sixteen cards across the four campaigns name one of those
 * two -- and the card stays up for six, so waiting it out never showed
 * anything. It only bites when a player clicks past a still picture, which is
 * what a player does with a still picture, and then up to five seconds of brass
 * carries into the cutscene or the briefing behind it. So the checks below skip
 * rather than wait.
 *
 * <p>Every one of these ends by rendering the mixer and looking at the samples.
 * The old test for this asked the screen what voice it was holding, which is a
 * field the screen sets itself: it would pass against a screen that cleared the
 * field and left the voice running, which is a real way to write this wrong.
 * The mixer is the thing the player hears.
 */
class ScreenSoundStopsTest {

    /** Enough frames to be well past any bus ramp. */
    private static final int FRAMES = 4_096;

    /** Under this the mixer is producing nothing a player could hear. */
    private static final float INAUDIBLE = 0.002f;

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    // ------------------------------------------------------------ act cards

    @Test
    @DisplayName("clicking past an act card takes its fanfare with it")
    void skippingAnActCardSilencesItsFanfare() {
        GameData data = load();
        CampaignStep card = anActCard(data);
        GameAudio audio = new GameAudio(data.sounds());
        audio.startWithoutDevice();

        SplashScreen shown = Main.actCard(data, audio, card, () -> { });
        Assumptions.assumeTrue(shown != null, "this installation has no act card picture");
        shown.begin();

        // The fixture guard is the mixer, not the screen's own field. Asked
        // the screen, this reads as "the card never started a fanfare" against
        // a launcher that started one behind the card's back -- which is
        // precisely the fault, and it would be reported as a bad fixture.
        float playing = peak(audio);
        assertTrue(playing > 0.01f,
                "nothing audible while the card was up, at " + playing + ": "
                        + card.sound() + " did not resolve to a clip");

        shown.skip();

        assertTrue(peak(audio) < INAUDIBLE,
                "the card was passed and the fanfare is still coming out of the mixer, which"
                        + " is what a player hears over the cutscene or the briefing behind"
                        + " it: up to five seconds of brass under somebody else's scene");
    }

    @Test
    @DisplayName("an act card's fanfare is not louder than the game around it")
    void theFanfareIsHeldUnderFullScale() {
        // It used to go out at nought decibels, which is full scale, alongside
        // a recorded soundtrack held twelve decibels down. A player reported
        // the result: "the cutscene music was also super loud, I'm not sure
        // what volume info it was using".
        GameData data = load();
        CampaignStep card = anActCard(data);
        GameAudio audio = new GameAudio(data.sounds());
        audio.startWithoutDevice();
        var clip = data.sounds().clip(card.sound());
        Assumptions.assumeTrue(clip != null, "no fanfare in this installation");

        long asMusic = audio.playMusicClip(clip);
        assertNotEquals(AudioMixer.NO_VOICE, asMusic, "the fanfare did not start");
        float held = peak(audio);
        audio.stopClip(asMusic);

        GameAudio flat = new GameAudio(data.sounds());
        flat.startWithoutDevice();
        assertNotEquals(AudioMixer.NO_VOICE, flat.playVoiceClip(clip),
                "the control voice did not start");
        float full = peak(flat);

        assertTrue(held < full * 0.6f,
                "the fanfare came out at " + held + " against " + full + " at full scale,"
                        + " so it is not being held under the game's own sounds at all");
        assertTrue(held > full * 0.4f,
                "the fanfare came out at " + held + " against " + full + ": six decibels"
                        + " down was wanted and this is a great deal further");
    }

    // ------------------------------------------------------------ cutscenes

    @Test
    @DisplayName("skipping a cutscene silences its soundtrack")
    void skippingACutsceneSilencesIt() {
        GameData data = load();
        SmackerVideo video = data.video("videos/human-1");
        Assumptions.assumeTrue(video != null, "no cutscene to play");
        GameAudio audio = new GameAudio(data.sounds());
        audio.startWithoutDevice();

        VideoScreen screen = new VideoScreen(video, 640, 480, () -> { }, audio);
        screen.play();
        assertTrue(peak(audio) > 0.01f,
                "the cutscene's soundtrack never started, so this proves nothing");

        screen.skip();

        assertTrue(peak(audio) < INAUDIBLE,
                "the picture stopped and the sound carried on over whatever came next");
    }

    @Test
    @DisplayName("a cutscene watched to the end silences its soundtrack too")
    void aFinishedCutsceneSilencesIt() {
        // The other way out of a cutscene, and the one a player who is watching
        // takes. Both routes go through the same place, and both have to.
        GameData data = load();
        SmackerVideo video = data.video("videos/human-1");
        Assumptions.assumeTrue(video != null, "no cutscene to play");
        GameAudio audio = new GameAudio(data.sounds());
        audio.startWithoutDevice();

        int[] finished = {0};
        VideoScreen screen = new VideoScreen(video, 640, 480, () -> finished[0]++, audio);
        screen.play();
        assertTrue(peak(audio) > 0.01f,
                "the cutscene's soundtrack never started, so this proves nothing");

        screen.endForTest();

        assertEquals(1, finished[0], "the cutscene did not reach its own ending");
        assertTrue(peak(audio) < INAUDIBLE,
                "the last frame went up and the soundtrack played on underneath the screen"
                        + " that followed it");
    }

    @Test
    @DisplayName("a second key press on a finished cutscene does not skip the screen after it")
    void finishingTwiceOnlyMovesOnOnce() {
        // SplashScreen has had a guard on this since it was written and
        // VideoScreen never did, though five paths reach its finish(). Running
        // onFinished twice steps two links along the chain of screens, so a key
        // press that was meant to skip a cutscene skips the briefing behind it
        // as well.
        GameData data = load();
        SmackerVideo video = data.video("videos/human-1");
        Assumptions.assumeTrue(video != null, "no cutscene to play");

        int[] finished = {0};
        VideoScreen screen = new VideoScreen(video, 640, 480, () -> finished[0]++, null);
        screen.play();

        screen.skip();
        screen.skip();

        assertEquals(1, finished[0],
                "two key presses moved the campaign on twice, so a screen was never shown");
    }

    // ------------------------------------- the other two screens with a button

    @Test
    @DisplayName("pressing Continue twice on a briefing only starts one mission")
    void pressingContinueTwiceOnlyStartsOneMission() {
        // The same shape as the cutscene above, in the screen next door, and
        // worse. A briefing's Continue closes the cutscene device and starts
        // loading the map on a background thread, so running it twice starts
        // two games: two audio devices, two CdMusics, two soundtracks over one
        // map, one shutdown hook between them, and an in-game menu holding
        // whichever sound server was built second -- so one of the two
        // soundtracks answers the music slider and the other cannot. That is
        // the player's report reached by a second route: "the music from the
        // last video / cutscene was still playing, causing confusion... the
        // music volume control has no effect."
        //
        // A player gets there by pressing Enter twice on a briefing they have
        // finished reading, which is what pressing Enter on a page of text
        // looks like when the next screen takes a moment to arrive.
        GameData data = load();

        int[] started = {0};
        BriefingScreen briefing = new BriefingScreen(data, "human", 640, 480,
                null, "Mission 1", "Some prose.", "Continue", () -> started[0]++);

        briefing.pressForTest();
        briefing.pressForTest();

        assertEquals(1, started[0],
                "two presses started the mission twice, so two games are loading over each"
                        + " other with a soundtrack each and the slider reaching one of them");
    }

    @Test
    @DisplayName("pressing on twice at the results screen only moves one mission on")
    void pressingOnTwiceAtTheResultsScreenOnlyMovesOnOnce() {
        // The third screen with a button and the third without a guard. Its
        // Continue is the next mission, so two presses walk two missions on and
        // build the second game underneath the first.
        GameData data = load();

        int[] onward = {0};
        ResultsScreen screen = new ResultsScreen(data, "human", 640, 480,
                ResultsScreen.Outcome.VICTORY, 1000, java.util.List.of(),
                "Next Mission", () -> onward[0]++);

        screen.pressForTest();
        screen.pressForTest();

        assertEquals(1, onward[0],
                "two presses moved the campaign on twice, so a mission was skipped and its"
                        + " game was built underneath the one already loading");
    }

    // ------------------------------------------------------------ fixtures

    /** The first act card in the human campaign that names a fanfare. */
    private static CampaignStep anActCard(GameData data) {
        for (Campaign campaign : data.campaigns()) {
            for (CampaignStep step : campaign.steps()) {
                if (step.kind() == CampaignStep.Kind.PICTURE
                        && step.sound() != null && !step.sound().isBlank()) {
                    return step;
                }
            }
        }
        Assumptions.abort("no campaign act card with a fanfare in this installation");
        return null;
    }

    /** The loudest sample the mixer would have sent to the speakers. */
    private static float peak(GameAudio audio) {
        float[] block = new float[FRAMES * AudioMixer.OUTPUT_CHANNELS];
        audio.mixer().render(block, FRAMES);
        float peak = 0f;
        for (float sample : block) {
            peak = Math.max(peak, Math.abs(sample));
        }
        return peak;
    }
}
