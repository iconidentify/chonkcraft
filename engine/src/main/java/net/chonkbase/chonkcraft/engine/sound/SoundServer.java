package net.chonkbase.chonkcraft.engine.sound;

import java.util.List;
import net.chonkbase.runtime.audio.AudioBus;
import net.chonkbase.runtime.audio.AudioMixer;

/**
 * The two volume sliders and the soundtrack they act on.
 *
 * <p>Implements the volume and music-transport half of
 * The game {@code SetEffectsVolume} and
 * {@code GetEffectsVolume} at {@code :540-551}, {@code SetMusicVolume} and
 * {@code GetMusicVolume} at {@code :627-643}, and {@code PlayMusic} and
 * {@code StopMusic} at {@code :590-620}. The menu sliders in
 * {@code scripts/menus/options.legacy-declaration:45-117} call exactly those four, and
 * {@code scripts/legacyEngine.legacy-declaration:495} and {@code :511} restore them from the
 * saved preferences at startup.
 *
 * <p>It exists because this implementation has the soundtrack twice and upstream does
 * not. Warcraft II ships eighteen XMI tracks for a synthesiser and the same
 * music recorded on the discs; upstream reaches both through SDL_mixer, so one
 * {@code Mix_VolumeMusic} covers them. Here the recordings are samples and go
 * through {@code AudioMixer}'s music bus while the XMI goes to the JDK's
 * sequencer, which the mixer never sees. Nothing joined the two, so the music
 * slider moved the recorded half and left the synthesised half where it was --
 * reported from play as "the music volume control has no effect". The
 * volumes and the choice between the two backends are held here so there is one
 * answer to "how loud is the music" rather than one per backend.
 *
 * <p>The choice was not a choice before either: the disc won whenever there was
 * one, on a single {@code if} in the launcher, and a player who preferred the
 * synthesised score had no way to say so.
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
        if (preferred == Backend.XMI) {
            return hasXmi() ? Backend.XMI : Backend.CD;
        }
        return hasCd() ? Backend.CD : Backend.XMI;
    }

    /** Whether there is a recorded soundtrack to play. */
    private boolean hasCd() {
        return disc != null && disc.isAvailable();
    }

    /** Whether there is a synthesised soundtrack to play. */
    private boolean hasXmi() {
        return synth != null && !synth.tracks().isEmpty();
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
        return playBattleMusic(lastWasOrc);
    }

    /** Whose battle music was last asked for, so a switch can restart it. */
    private boolean lastWasOrc;

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
        synchronized (MUSIC_FOCUS_LOCK) {
            return playBattleMusicWithFocus(orc);
        }
    }

    /** Starts battle music while holding the application-wide music focus. */
    private boolean playBattleMusicWithFocus(boolean orc) {
        lastWasOrc = orc;
        claimMusicFocus();
        stopMusicWithoutFocusChange();
        if (backend() == Backend.CD) {
            // Both backends can be absent at once, and this class is
            // constructed that way on purpose: the title sequence and the act
            // cards build one with no disc and no sequencer, only to carry the
            // saved volumes onto a device of their own. Silencing is still the
            // right thing for such a server to do; starting is not, and
            // reaching through a null to find that out would take the launcher
            // down on a machine with no music at all.
            if (disc == null) {
                return false;
            }
            List<CdMusic.Track> tracks = disc.tracks();
            if (tracks.isEmpty()) {
                return false;
            }
            // Battle.net Edition's recordings are named for the situation
            // they belong to rather than numbered by a CD table of contents.
            // Using the old disc's third-position convention here would play
            // Human Battle 3 for an orc map as well.
            String namedPrefix = orc ? "Orc Battle " : "Human Battle ";
            for (CdMusic.Track track : tracks) {
                if (track.name().startsWith(namedPrefix)) {
                    return disc.play(track);
                }
            }
            // The battle music, which on both discs follows the opening
            // themes. Any track is better than none if the count surprises us.
            return disc.play(tracks.get(Math.min(2, tracks.size() - 1)));
        }
        if (synth == null) {
            return false;
        }
        synth.start();
        // The mission playlist, as scripts/human/ui_tales.legacy-declaration and its siblings
        // set it: all five of the race's battle tracks, drawn from at random as
        // each one ends. Handed over even when there is no synthesiser to play
        // it, so that what the game asked for is what the game is holding: the
        // playlist is the record of which music this screen wants, and a
        // machine with no MIDI device that later gets one should not be left
        // playing the menu theme.
        return synth.playPlaylist(synth.available(MusicPlayer.battleTracks(orc)));
    }

    /** Starts the menu theme on the player's selected soundtrack backend. */
    public boolean playMenuMusic() {
        synchronized (MUSIC_FOCUS_LOCK) {
            claimMusicFocus();
            stopMusicWithoutFocusChange();
            return playMenuMusicWithFocus();
        }
    }

    /** Starts menu music after the caller has made this server the owner. */
    private boolean playMenuMusicWithFocus() {
        if (backend() == Backend.CD) {
            if (disc == null) {
                return false;
            }
            // The BNE catalog retains both logical names over the one proved
            // recording. Orc Briefing also works on older disc catalogs.
            return disc.play("Main Menu") || disc.play("Orc Briefing");
        }
        if (synth == null) {
            return false;
        }
        synth.start();
        return synth.playPlaylist(synth.available(MusicPlayer.menuTracks()));
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
        if (synth != null) {
            synth.silence();
        }
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
        stopMusic();
        releaseMusicFocus();
        if (disc != null) {
            disc.close();
        }
        if (synth != null) {
            synth.close();
        }
    }
}
