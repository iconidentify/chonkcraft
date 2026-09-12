package net.chonkbase.chonkcraft.engine.sound;

import java.util.List;
import net.chonkbase.runtime.audio.AudioBus;
import net.chonkbase.runtime.audio.AudioMixer;

/**
 * The two volume sliders and the soundtrack they act on.
 *
 * <p>Recorded music goes through the PCM music bus, while archive XMI goes
 * through a MIDI sequencer. Both sources share volume, scene and ownership
 * here. Changing screens or sources must silence the previous soundtrack and
 * preserve the requested musical role. Otherwise a menu can continue over a
 * battle, or a source switch can replace a victory theme with battle music.
 *
 * <p>Availability is checked for the requested role, not merely for any music
 * in the import. Numbered recordings have no established race or scene; the
 * authentic XMI score supplies those roles until their recording identities
 * can be established from the source media.
 */
public final class SoundServer implements AutoCloseable {

    /**
     * The one soundtrack owner allowed to be audible in this JVM.
     *
     * <p>Front-end screens and a running map use different PCM devices. A
     * server can therefore silence both of <em>its</em> backends and still
     * leave a CD track owned by the previous screen playing on another
     * device. Keeping focus here makes the invariant application-wide:
     * starting music on one server first silences the last server, even if a
     * screen hand-off forgot to close it.
     */
    private static final Object MUSIC_FOCUS_LOCK = new Object();
    private static SoundServer musicFocus;

    /** Which of the two recordings of the score is playing. */
    public enum Backend {
        /** The red book audio off the discs, mixed as samples. */
        CD,
        /** The eighteen XMI tracks, through a synthesiser. */
        XMI
    }

    /**
     * How long the mixer takes to walk a bus to its new gain.
     *
     * <p>Sixty-four frames is under a millisecond and a half at the mixer's
     * rate. Enough that dragging a slider does not click, short enough that the
     * change is heard while the finger is still moving.
     */
    private static final int VOLUME_RAMP_FRAMES = 64;

    /** Below this a slider is off rather than very quiet. */
    private static final float SILENCE = 0.001f;

    /**
     * The floor a slider at nothing converts to.
     *
     * <p>Silence is minus infinity decibels, which is not a number the mixer
     * takes, so this stands in for it. Sixty decibels down is inaudible beside
     * anything else in the game.
     */
    private static final float SILENT_DB = -60f;

    private final AudioMixer mixer;
    private final CdMusic disc;
    private final MusicPlayer synth;

    private Backend preferred;
    private Backend playingBackend;
    private Scene scene;
    private float effects = 1f;
    private float music = 1f;

    /**
     * @param mixer     where the samples go, and which the two effect buses and
     *                  the music bus belong to
     * @param disc      the recorded soundtrack, which may have no tracks
     * @param synth     the synthesised soundtrack, which may have no sequencer
     * @param preferred which of the two the player asked for
     */
    public SoundServer(AudioMixer mixer, CdMusic disc, MusicPlayer synth, Backend preferred) {
        this.mixer = mixer;
        this.disc = disc;
        this.synth = synth;
        this.preferred = preferred == null ? Backend.CD : preferred;
    }

    /** What the player asked for, whether or not it can be given to them. */
    public Backend preferred() {
        return preferred;
    }

    /**
     * Which backend actually plays.
     *
     * <p>The preference is honoured whenever that backend has music, and only
     * then. A hard-disk DOS install has no recordings until the disc is cached,
     * and an installation whose {@code snddat.war} is missing has no XMI, so
     * either side can be empty on a real machine.
     *
     * <p>"Has music" is a question about the data and not about the hardware,
     * deliberately. If a player asks for the synthesised score on a machine
     * with no synthesiser they get silence and a line on the console saying so,
     * rather than the discs they did not ask for: quietly playing the other one
     * is how a setting comes to look as though it does nothing.
     */
    public Backend backend() {
        if (playingBackend != null) {
            return playingBackend;
        }
        if (preferred == Backend.XMI) {
            return hasXmi() ? Backend.XMI : Backend.CD;
        }
        return hasCd() ? Backend.CD : Backend.XMI;
    }

    /** Whether there is a recorded soundtrack to play. */
    private boolean hasCd() {
        return disc != null && (scene == null
                ? disc.isAvailable() : disc.find(wantedTracks()) != null);
    }

    /** Whether there is a synthesised soundtrack to play. */
    private boolean hasXmi() {
        return synth != null && (scene == null
                ? !synth.tracks().isEmpty() : !synth.available(wantedTracks()).isEmpty());
    }

    /**
     * Switches backend under a running game.
     *
     * <p>Stops the one that was playing before starting the other, which is the
     * whole of the work: a switch that only started the new one would leave two
     * soundtracks over the same map, which is the fault this class was written
     * to end.
     *
     * @return whether music is playing afterwards
     */
    public boolean setBackend(Backend wanted) {
        if (wanted == null) {
            return isPlaying();
        }
        preferred = wanted;
        return playScene(scene == null ? Scene.BATTLE : scene, lastWasOrc);
    }

    /** The current scene's race, retained when its backend changes. */
    private boolean lastWasOrc;

    private enum Scene {
        MENU, BRIEFING, BATTLE, VICTORY, DEFEAT
    }

    /**
     * The music's meaning survives a change of backend or imported media.
     * BNE 2.02b resolves its scene table at 0x4a1898 through the filename table
     * at 0x4a184c (0x440f2c). Rows 0..11 name six battles per race, 12..15
     * name results, and 20..22 name the briefings and menu. A numbered legacy
     * recording does not establish any of those identities. Falling back to
     * its third position played the same song for both campaigns; requiring
     * named recordings only in the menu then made that screen silent.
     *
     * <p>Recorded playback still repeats the selected complete clip. Retail's
     * mode-two battle rows advance after a two-second gap (0x440df9), and its
     * human briefing has a separate loop start. Subsequent track order and
     * that loop boundary remain different after the first playthrough.
     */
    private List<String> wantedTracks() {
        String race = lastWasOrc ? "Orc" : "Human";
        return switch (scene == null ? Scene.BATTLE : scene) {
            case MENU -> List.of("Main Menu", "Orc Briefing");
            case BRIEFING -> MusicPlayer.briefingTracks(lastWasOrc);
            case BATTLE -> java.util.stream.IntStream.rangeClosed(1, 6)
                    .mapToObj(number -> race + " Battle " + number).toList();
            case VICTORY -> MusicPlayer.resultTracks(lastWasOrc, true);
            case DEFEAT -> MusicPlayer.resultTracks(lastWasOrc, false);
        };
    }

    /** Whether either backend is making a sound. */
    public boolean isPlaying() {
        return (disc != null && disc.playing() != null)
                || (synth != null && synth.isPlaying());
    }

    /**
     * Starts the soundtrack for a mission, on whichever backend is chosen.
     *
     * <p>Silences the other one first, and that line is the reported bug. The
     * launcher used to branch on whether a disc was present and, in the disc
     * branch, never touch the sequencer: a campaign launch plays the briefing
     * theme through the sequencer, the player reads the briefing, the map loads
     * and starts a red book track, and the briefing theme goes on playing over
     * it for the rest of its fifty-two seconds. A skirmish launch is worse,
     * because the menu leaves {@code Orc Briefing} in the playlist and the
     * playlist restarts it forever, so the menu theme plays over the whole
     * game. Either way the player hears music they have no control over -- "the
     * music from the last video / cutscene was still playing, causing
     * confusion" -- and then hears it stop on its own and control return, which
     * is the sequencer running out.
     *
     * @param orc whose five battle tracks the synthesised score draws from
     * @return whether anything started
     */
    public boolean playBattleMusic(boolean orc) {
        return playScene(Scene.BATTLE, orc);
    }

    /** Starts the menu on whichever backend has its theme. */
    public boolean playMenuMusic() {
        return playScene(Scene.MENU, false);
    }

    /** Uses the same soundtrack selection for briefings as for the game. */
    public boolean playBriefingMusic(boolean orc) {
        return playScene(Scene.BRIEFING, orc);
    }

    /** The winner and loser each hear their race's result, played once. */
    public boolean playResultMusic(boolean orc, boolean won) {
        return playScene(won ? Scene.VICTORY : Scene.DEFEAT, orc);
    }

    private boolean playScene(Scene wanted, boolean orc) {
        synchronized (MUSIC_FOCUS_LOCK) {
            scene = wanted;
            lastWasOrc = orc;
            playingBackend = null;
            claimMusicFocus();
            stopMusicWithoutFocusChange();
            Backend chosen = backend();
            playingBackend = chosen;
            return startScene(chosen);
        }
    }

    private boolean startScene(Backend chosen) {
        boolean looping = scene != Scene.VICTORY && scene != Scene.DEFEAT;
        if (chosen == Backend.CD) {
            return disc != null && disc.play(disc.find(wantedTracks()), looping);
        }
        if (synth == null) {
            return false;
        }
        synth.start();
        boolean started = synth.playPlaylist(synth.available(
                scene == Scene.MENU ? MusicPlayer.menuTracks() : wantedTracks()));
        if (!looping) {
            synth.setPlaylist(List.of());
        }
        return started;
    }

    /** What is playing, for the line the launcher prints. */
    public String describe() {
        if (backend() == Backend.CD) {
            String playing = disc == null ? null : disc.playing();
            return (playing == null ? "none" : playing) + " (CD audio)";
        }
        if (synth == null) {
            return "none (synthesised)";
        }
        if (!synth.isAvailable()) {
            return "unavailable (" + synth.unavailableReason() + ")";
        }
        // And whether the slider reaches it, which is the thing that was
        // silently untrue before. A machine that will not hand out its
        // synthesiser separately gets music it cannot turn down, and saying so
        // is what stops that being reported as "the volume control has no
        // effect" a second time.
        return (synth.isPlaying() ? "playing" : "none") + " of "
                + synth.playlist().size() + " tracks (synthesised"
                + (synth.isVolumeControllable()
                        ? ")"
                        : ", volume fixed: " + synth.volumeUnavailableReason() + ")");
    }

    /**
     * Silences both backends.
     *
     * <p>Both, not the one that is playing, because "the one that is playing"
     * is exactly the judgement that was wrong before. The synthesiser's
     * playlist is emptied as well as stopped, so nothing can start a track
     * behind the caller's back: {@code MusicPlayer} advances on the end of a
     * track from the sequencer's own thread, so a playlist left in place plays
     * on for as long as the game does.
     */
    public void stopMusic() {
        synchronized (MUSIC_FOCUS_LOCK) {
            stopMusicWithoutFocusChange();
        }
    }

    /** Stops this server's two sources while the focus lock is held. */
    private void stopMusicWithoutFocusChange() {
        if (disc != null) {
            disc.stop();
        }
        if (synth != null && ownsSynth()) {
            synth.silence();
        }
    }

    private boolean ownsSynth() {
        return musicFocus == null || musicFocus == this || musicFocus.synth != synth;
    }

    /** Takes soundtrack focus and silences a server left by another screen. */
    private void claimMusicFocus() {
        SoundServer previous = musicFocus;
        musicFocus = this;
        if (previous != null && previous != this) {
            previous.stopMusicWithoutFocusChange();
        }
    }

    /** Gives up focus without disturbing a newer owner. */
    private void releaseMusicFocus() {
        synchronized (MUSIC_FOCUS_LOCK) {
            if (musicFocus == this) {
                musicFocus = null;
            }
        }
    }

    // ---------------------------------------------------------------- volume

    /** How loud the effects are, nought to one. */
    public float effectVolume() {
        return effects;
    }

    /**
     * Moves the effects slider.
     *
     * <p>Three buses, because the game's noises are spread over three: units
     * and world sounds on {@code WORLD}, interface clicks on {@code UI}, and a
     * briefing's narration on {@code VOICE}, which is where speech goes for the
     * same reason upstream plays it with {@code PlaySoundFile} on a sound
     * channel rather than as music.
     *
     * <p>And not the music bus. That sounds too obvious to write down, and it
     * is the crossed wire that would put this implementation back where it started: a
     * player turning the effects down and hearing the music go with it has the
     * same complaint about the same two controls.
     */
    public void setEffectVolume(float wanted) {
        effects = clamp(wanted);
        float gain = decibels(effects);
        mixer.setBusGainDb(AudioBus.WORLD, gain, VOLUME_RAMP_FRAMES);
        mixer.setBusGainDb(AudioBus.UI, gain, VOLUME_RAMP_FRAMES);
        mixer.setBusGainDb(AudioBus.VOICE, gain, VOLUME_RAMP_FRAMES);
    }

    /** How loud the music is, nought to one. */
    public float musicVolume() {
        return music;
    }

    /**
     * Moves the music slider, on both backends at once.
     *
     * <p>The music bus carries the recorded soundtrack, a cutscene's own
     * soundtrack and an act card's fanfare; {@link MusicPlayer#setVolume}
     * carries the synthesised one through to controller 7 on the synthesiser.
     * They are made to agree in decibels rather than in units -- see
     * {@link MidiVolume} -- so a player at half volume is six decibels down
     * whichever half of the soundtrack is playing, and switching between them
     * is not a step in level.
     */
    public void setMusicVolume(float wanted) {
        music = clamp(wanted);
        mixer.setBusGainDb(AudioBus.MUSIC, decibels(music), VOLUME_RAMP_FRAMES);
        if (synth != null) {
            synth.setVolume(music);
        }
    }

    /**
     * A slider position from nought to one, in tenths.
     *
     * <p>Tenths because that is what the menu's slider can land on, and a
     * position that cannot be returned to is a position a player cannot undo.
     */
    public static float clamp(float volume) {
        return Math.max(0f, Math.min(1f, Math.round(volume * 10f) / 10f));
    }

    /**
     * A loudness from nought to one as a gain in decibels.
     *
     * <p>Twenty times the base-ten logarithm is the definition, so amplitude
     * follows the slider exactly. {@link MidiVolume} is built to match this
     * curve, and changing it here without changing it there puts the two
     * backends back out of step.
     */
    public static float decibels(float volume) {
        if (volume <= SILENCE) {
            return SILENT_DB;
        }
        return (float) (20.0 * Math.log10(volume));
    }

    @Override
    public void close() {
        synchronized (MUSIC_FOCUS_LOCK) {
            stopMusicWithoutFocusChange();
            if (disc != null) {
                disc.close();
            }
            // GameData shares one sequencer. A replaced loader can finish
            // closing after the next screen has taken ownership of it.
            if (synth != null && ownsSynth()) {
                synth.close();
            }
            releaseMusicFocus();
        }
    }
}
