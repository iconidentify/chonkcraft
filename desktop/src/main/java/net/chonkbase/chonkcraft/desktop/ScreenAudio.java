package net.chonkbase.chonkcraft.desktop;

import net.chonkbase.runtime.audio.AudioMixer;
import net.chonkbase.runtime.audio.PcmClip;
import net.chonkbase.chonkcraft.engine.sound.GameAudio;

/**
 * A sound a screen owns: started when the screen comes up, stopped when it
 * goes.
 *
 * <p>Has no retail counterpart. It exists because the same three lines were
 * wanted in three places and were written correctly in two of them.
 * {@code GameAudio.playMusicClip} and {@code playVoiceClip} hand back a voice
 * so that {@code stopClip} can silence it, after upstream's
 * {@code StopChannel}; a caller that drops the voice has started a sound
 * nothing can stop.
 *
 * <p>That has now been the same reported bug twice. First a cutscene: "I
 * clicked to skip the Blizzard logo and it kept playing the audio even though I
 * had skipped it". Then an act card, which is the identical shape one file
 * away -- the fanfare is five seconds long and the card only stays up for six,
 * so letting it time out hides the fault entirely, and a player who clicks past
 * a still picture, which is what a player does, carries up to five seconds of
 * brass into the cutscene or the briefing behind it.
 *
 * <p>So the discipline is here rather than repeated: a screen holds one of
 * these, plays through it, and silences it on the way out. Holding the voice is
 * not optional because there is nowhere else to put it.
 */
final class ScreenAudio {

    /** Which of the two kinds of clip this is, and so which bus it goes to. */
    enum Kind {
        /** A cutscene's soundtrack or an act card's fanfare. */
        MUSIC,
        /** A briefing's narration or a campaign's closing words. */
        VOICE
    }

    private final GameAudio audio;
    private final Kind kind;

    private long voice = AudioMixer.NO_VOICE;

    ScreenAudio(GameAudio audio, Kind kind) {
        this.audio = audio;
        this.kind = kind;
    }

    /**
     * Starts a clip, silencing whatever this was playing before.
     *
     * <p>Silencing first matters for the briefings, which read two takes of one
     * speech in order and time the second off the length of the first: an
     * installation whose clip length and playing length disagree by a fraction
     * would otherwise have the narrator talking over himself.
     */
    void play(PcmClip clip) {
        silence();
        if (audio == null || clip == null) {
            return;
        }
        voice = switch (kind) {
            case MUSIC -> audio.playMusicClip(clip);
            case VOICE -> audio.playVoiceClip(clip);
        };
    }

    /** Stops it, and forgets it, so silencing twice is safe. */
    void silence() {
        if (audio != null && voice != AudioMixer.NO_VOICE) {
            audio.stopClip(voice);
        }
        voice = AudioMixer.NO_VOICE;
    }

    /**
     * The voice it is playing on, or {@code AudioMixer.NO_VOICE}.
     *
     * <p>For the test that checks the screen gave the voice up. It is the
     * weaker of the two checks and it is not on its own: a field set to nothing
     * proves only that a field was set to nothing, so the tests that matter
     * render the mixer and listen. This defect has already survived once by
     * looking right at the call site.
     */
    long voice() {
        return voice;
    }
}
