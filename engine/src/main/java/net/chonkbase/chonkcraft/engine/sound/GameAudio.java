package net.chonkbase.chonkcraft.engine.sound;

import java.util.function.IntUnaryOperator;
import net.chonkbase.runtime.audio.AudioBus;
import net.chonkbase.runtime.audio.AudioMixer;
import net.chonkbase.runtime.audio.AudioOutputDriver;
import net.chonkbase.runtime.audio.JavaSoundPcmSink;
import net.chonkbase.runtime.audio.PcmClip;
import net.chonkbase.chonkcraft.engine.unit.Unit;

/**
 * Plays the game's sounds through the shared runtime mixer.
 *
 * <p>Thin on purpose: the runtime already owns the hard parts, a voice
 * limiter, buses, and a render thread that keeps the device fed. This decides
 * only what to play and where it is coming from.
 *
 * <p>Fails soft. A machine with no audio device should still play the game,
 * so a failure to open one is reported once and then ignored.
 */
public final class GameAudio implements AutoCloseable {

    /** Unit voices and world effects go to the world bus, clicks to the UI bus. */
    private static final int UNIT_VOICE_PRIORITY = 50;
    private static final int EFFECT_PRIORITY = 30;

    /**
     * Music outranks every sound in the game, and that is the point.
     *
     * <p>The mixer holds thirty-two voices and steals the lowest-priority
     * oldest one when a thirty-third starts. {@link CdMusic} used to ask for
     * priority zero -- the lowest number anywhere in the implementation -- so the
     * soundtrack was the unique global minimum and always the voice chosen. A
     * battle that put thirty-two sounds in flight at once, which is a dozen
     * footmen swinging, took the music out and nothing ever put it back:
     * {@code CdMusic} holds no completion listener, so it went on reporting
     * that it was playing while the map ran in silence for the rest of the
     * session. Music is the one voice that must never be stolen, because it is
     * the only one that does not come round again a second later.
     */
    public static final int MUSIC_PRIORITY = 100;

    /**
     * How far under full scale a cutscene's soundtrack and an act card's
     * fanfare are held.
     *
     * <p>Both of these went out at nought decibels, which is full scale, and a
     * player reported the result: "the cutscene music was also super loud, I'm
     * not sure what volume info it was using".
     *
     * <p>Upstream says which way to go but not how far. {@code PlayMovie} brackets a film with
     * {@code SetMusicVolume(GetEffectsVolume())} and the comment "since video's
     * may be longer and should be streamed, we play them as music, but use the
     * effects volume for them", and {@code CreatePictureStep} in
     * {@code scripts/menus/campaign.legacy-declaration:160-164} plays the act fanfare through
     * {@code PlayMusic} as well. So neither is background music and neither may
     * be attenuated to the twelve decibels {@link CdMusic} holds the score at.
     *
     * <p>The distance is measured rather than guessed, and measured where it
     * counts: the figures below are the samples {@code AudioMixer.render}
     * actually produces with both sliders fully up, not the levels of the
     * files, so they already carry the mixer's own gain and pan law. Loudest
     * three hundred milliseconds of the real 1995 data, in dBFS at the
     * speakers: the four cutscene soundtracks come out between -19.5 and -15.4
     * with this attenuation applied, which is -13.5 to -9.4 without it; the
     * four hundred and ninety-three sounds in the bank average -14.8 on the
     * world bus and reach -9.0 at the loudest; the red book track the map plays
     * comes out at -22.6 after {@link CdMusic#BACKGROUND_GAIN_DB}. So at nought
     * decibels a cutscene is louder than the average sound the game makes and
     * some thirteen decibels louder than the music it hands over to, sustained
     * for two minutes rather than for the half-second an effect lasts. That
     * last part is most of the complaint: nothing else in the game is both that
     * loud and that long.
     *
     * <p>Six decibels down puts a cutscene at about -18 dBFS: three decibels
     * below the average game effect rather than above it, four to seven above
     * the score so it is still plainly the foreground, and half the amplitude
     * it had. Six is also where ChonkCraft's own shipped preferences put both
     * volumes -- {@code EffectsVolume = 128} and {@code MusicVolume = 128} of
     * 255, {@code scripts/legacyEngine.legacy-declaration:398} and {@code :418}.
     *
     * <p>What this is <em>not</em> is a clipping fix. Rendered at nought
     * decibels the loudest cutscene peaks at 0.699, and the master limiter's
     * ceiling is 0.98: measured over thirty seconds of {@code videos/human-1},
     * not one sample ever reached it. The soundtrack was too loud beside
     * everything else, which is what the player said; it was never distorting,
     * and a comment claiming it was would send the next reader looking at the
     * limiter.
     */
    public static final float CUTSCENE_GAIN_DB = -6f;

    private final SoundBank bank;
    private final AudioMixer mixer = new AudioMixer();
    private AudioOutputDriver driver;
    private boolean available;
    private String unavailableReason = "not started";

    public GameAudio(SoundBank bank) {
        this.bank = bank;
    }

    /** Opens the output device. Safe to call when there is none. */
    public void start() {
        try {
            driver = new AudioOutputDriver(mixer, new JavaSoundPcmSink());
            driver.start();
            available = true;
        } catch (Exception e) {
            available = false;
            unavailableReason = String.valueOf(e.getMessage());
        }
    }

    /**
     * Starts with no output device, leaving the rendering to the caller.
     *
     * <p>Everything below is submitted to {@link #mixer}, and the mixer is
     * exact: given the same commands it renders the same samples whether a
     * sound card is attached or not. This lets a test drive the real path --
     * the real clips, the real buses, the real gains -- and then read the
     * samples that would have gone to the speakers.
     *
     * <p>Here for the same reason {@link MusicPlayer#advanceUsing} is: no test
     * in this project opens an audio device, and a rule that holds everywhere
     * else should not be broken for the one subsystem whose defects are the
     * ones nobody can see.
     */
    public void startWithoutDevice() {
        available = true;
        unavailableReason = "rendered by the caller";
    }

    /** Whether sound is actually coming out. */
    public boolean isAvailable() {
        return available;
    }

    /** Why not, when it is not. */
    public String unavailableReason() {
        return unavailableReason;
    }

    public AudioMixer mixer() {
        return mixer;
    }

    /** Plays an interface sound, centred and at full volume. */
    public void playUi(String name) {
        play(bank.pathForName(name, 0), AudioBus.UI, 0f, EFFECT_PRIORITY);
    }

    /**
     * Plays one of a unit's own sounds.
     *
     * @param unit  whose voice it is
     * @param event the sound event, such as {@code selected} or {@code dead}
     * @param pick  asked for a number below the size of the group, and asked
     *              exactly once however this call turns out. See
     *              {@link #choose} for why both halves of that matter
     */
    public void playUnit(Unit unit, String event, IntUnaryOperator pick) {
        playUnitAt(unit, event, pick, 0f);
    }

    /**
     * Plays one of a unit's own sounds at a fixed choice.
     *
     * <p>For the sounds that have nothing to choose between and must not
     * disturb the sequence the simulation is drawing from.
     */
    public void playUnit(Unit unit, String event, int pick) {
        playUnit(unit, event, size -> pick);
    }

    /**
     * Plays a unit sound positioned relative to the view.
     *
     * @param panFromCentre -1 for hard left through 1 for hard right
     */
    public void playUnitAt(Unit unit, String event, IntUnaryOperator pick,
            float panFromCentre) {
        play(chosenPath(unit, event, pick), AudioBus.WORLD, clampPan(panFromCentre),
                UNIT_VOICE_PRIORITY);
    }

    /**
     * Which file a unit's sound event resolves to on this occasion.
     *
     * <p>The decision, separated from the playing of it. Which clip the game
     * reaches for is a fact about the game and not about the speakers, so it
     * can be settled -- and checked -- on a machine that has no sound device.
     * Advances the selection handler exactly as playing it does, because it is
     * the same call.
     */
    String chosenPath(Unit unit, String event, IntUnaryOperator pick) {
        String name = unit.type() == null ? null : unit.type().sounds().get(event);
        if (name == null || name.isEmpty()) {
            // Fall back to the race's game sound, as upstream does at
            // The game for WorkComplete. This is not a rare path: of
            // every unit in the game, only the oil tanker declares a
            // work-complete of its own. Without this, DefineGameSounds could
            // be parsed, bound and correct -- which it now is -- and a peasant
            // finishing a farm would still say nothing, because the lookup
            // never asked for the race's sound.
            String raced = event + "-" + raceOf(unit);
            if (!bank.pathsFor(raced).isEmpty()) {
                name = raced;
            }
        }
        return choose(name, pick, unit.id());
    }

    /**
     * Which race's game sound a unit falls back to.
     *
     * <p>Upstream keys this on {@code ThisPlayer->Race} rather than on the
     * unit, because in Warcraft II a player's own units are all of their own
     * race and that is the only case it has to get right. This implementation has no
     * per-player race on {@code World}, so the unit's own art path is used
     * instead: it agrees with upstream for a player's own units, and gives a
     * better answer than upstream for an observed enemy.
     *
     * <p>The art path is the discriminator because it is the only race marker
     * that survives into {@code UnitType}. Every unit's image sits under
     * {@code human/} or {@code orc/}, sometimes below a tileset prefix --
     * {@code tilesets/summer/orc/buildings/altar_of_storms.png}. Neutral units
     * match neither and get the human sound, which is what upstream's default
     * race does.
     */
    private static String raceOf(Unit unit) {
        String image = unit.type() == null ? null : unit.type().imageFile();
        if (image != null) {
            String path = image.toLowerCase(java.util.Locale.ROOT);
            if (path.startsWith("orc/") || path.contains("/orc/")) {
                return "orc";
            }
        }
        return "human";
    }

    /**
     * Plays a sound the game names directly, positioned in the world.
     *
     * <p>What the {@code sound} instruction inside an animation asks for. It
     * names a mapped sound -- {@code peasant-attack}, {@code tree-chopping} --
     * rather than one of the unit's voice events, so it cannot go through
     * {@link #playUnit}. It is an effect rather than a voice, so it does not
     * compete with acknowledgements for the voice priority.
     */
    public void playNamedAt(String name, IntUnaryOperator pick, float panFromCentre) {
        String path = choose(name, pick, NO_SOURCE);
        play(path, AudioBus.WORLD, clampPan(panFromCentre), EFFECT_PRIORITY);
    }

    /**
     * Plays a clip that stands in for the score: a cutscene's soundtrack or
     * the fanfare over an act card.
     *
     * <p>A cutscene's soundtrack comes out of the video file rather than the
     * sound bank, so it has nothing to be looked up by. It goes on the music
     * bus because that is the channel upstream plays both of these through --
     * {@code PlayMovie} at {@code CreatePictureStep} at {@code scripts/menus/campaign.legacy-declaration:163} both
     * call {@code PlayMusic} -- and it goes on at {@link #CUTSCENE_GAIN_DB},
     * which is where the measuring in that constant's comment put it.
     *
     * <p>Departs from upstream in one bounded way. Upstream sets the music
     * volume to the effects volume for the length of a film and puts it back
     * afterwards, so a player who has turned the music down still gets a loud
     * cutscene; here a cutscene stays on the music slider, so turning the music
     * down turns the cutscene down with it. At the default both sliders are
     * fully up and the two rules give the same answer; they diverge only for a
     * player who has moved one of them, and for that player following the
     * slider they moved is the less surprising of the two.
     *
     * <p>This used to be one method called {@code playClip} that put a
     * cutscene, an act fanfare and a briefing's narration on the music bus at
     * full scale. That is three different kinds of sound at one wrong level,
     * and splitting it is what makes the choice visible at the call site.
     */
    public long playMusicClip(PcmClip clip) {
        if (!available || clip == null) {
            return AudioMixer.NO_VOICE;
        }
        return mixer.play(clip, AudioBus.MUSIC, false, CUTSCENE_GAIN_DB, 0f, MUSIC_PRIORITY);
    }

    /**
     * Plays somebody talking: a briefing's narration or a campaign's closing
     * words.
     *
     * <p>Speech, not music, and upstream treats it as such. Both places that
     * read a briefing -- {@code scripts/database.legacy-declaration:544} and
     * {@code scripts/menus/campaign.legacy-declaration:99} -- call {@code PlaySoundFile},
     * which takes a sound channel and so the effects volume, not the music
     * one. Sending it to the music bus instead, which is what this used to do,
     * means a player who turns the music off silences the narrator.
     *
     * <p>At full scale because that is where every other spoken line in the
     * game plays: the four hundred and ninety-three sounds in the bank average
     * -11.9 dBFS over their loudest three hundred milliseconds and the mission
     * narrations measure within a decibel of that, so they are already mixed to
     * sit beside the effects and want no help from here.
     */
    public long playVoiceClip(PcmClip clip) {
        if (!available || clip == null) {
            return AudioMixer.NO_VOICE;
        }
        return mixer.play(clip, AudioBus.VOICE, false, 0f, 0f, EFFECT_PRIORITY);
    }

    /**
     * Silences a clip started by {@link #playMusicClip} or
     * {@link #playVoiceClip}.
     *
     * <p>A briefing's voice-over has to stop when the player presses on, which
     * is what upstream's {@code StopChannel} does in the same place. Without it
     * the narration keeps talking over the first minute of the mission.
     */
    public void stopClip(long voice) {
        if (available && voice != AudioMixer.NO_VOICE) {
            mixer.stop(voice);
        }
    }

    /** No unit is being pestered; used where a sound has no selection source. */
    private static final int NO_SOURCE = Integer.MIN_VALUE;

    /** The unit the last selection sound was for, and how far into the pair. */
    private int selectionSource = NO_SOURCE;
    private boolean selectionAnnoyed;
    private int selectionCount;

    /** How many times the same unit is clicked before it starts grumbling. */
    private static final int CLICKS_BEFORE_ANNOYED = 3;

    /**
     * Which file a sound event resolves to on this occasion.
     *
     * <p>Implements {@code ChooseSample}, and the
     * one place that is allowed to know how many clips a group has. Callers
     * used to pass a number they had picked out of the air -- 2 for a death, 3
     * for an animation sound, 4 for a voice -- and the data agreed with none of
     * them. The size comes from the bank instead, so a script that adds a
     * fourth grunt is heard without anyone editing Java.
     *
     * <p>{@code pick} is asked for a number exactly once and before anything
     * can return early, including when there is no such sound and when there is
     * no sound device at all. The callers draw from the simulation's own
     * synchronised generator, and a draw that happens on one machine and not on
     * another puts the two games on different numbers from then on -- which is
     * a desync, over a sound effect.
     *
     * <p>Selection sounds are the pair upstream makes them: the first three
     * clicks on a unit take a line at random from its acknowledging group, and
     * from the fourth it works through the annoyed group in order until that
     * runs out. Clicking a different unit starts again. Running the two halves
     * together into one list and drawing across the whole thing would have a
     * footman answer his first order with a complaint.
     *
     * @param source the unit being selected, or {@link #NO_SOURCE}
     * @return the asset path to play, or null if there is nothing to play
     */
    private String choose(String name, IntUnaryOperator pick, int source) {
        if (name == null || name.isEmpty()) {
            // Still drawn: see above.
            pick.applyAsInt(1);
            return null;
        }
        int chosen = pick.applyAsInt(Math.max(1, bank.groupSize(name)));
        if (bank.selection(name) == null) {
            // Any other sound out of the unit being pestered ends the
            // pestering, as upstream's handler does.
            if (source != NO_SOURCE && source == selectionSource) {
                selectionSource = NO_SOURCE;
                selectionAnnoyed = false;
                selectionCount = 0;
            }
            return bank.pathForName(name, chosen);
        }
        if (source == NO_SOURCE || source != selectionSource) {
            selectionSource = source;
            selectionAnnoyed = false;
            selectionCount = 1;
            return bank.pathForSelection(name, false, chosen);
        }
        if (!selectionAnnoyed) {
            String path = bank.pathForSelection(name, false, chosen);
            if (++selectionCount >= CLICKS_BEFORE_ANNOYED) {
                selectionCount = 0;
                selectionAnnoyed = true;
            }
            return path;
        }
        String path = bank.pathForSelection(name, true, selectionCount);
        if (++selectionCount >= Math.max(1, bank.annoyedSize(name))) {
            selectionCount = 0;
            selectionAnnoyed = false;
        }
        return path;
    }

    private void play(String path, AudioBus bus, float pan, int priority) {
        if (!available || path == null) {
            return;
        }
        PcmClip clip = bank.clip(path);
        if (clip == null) {
            return;
        }
        mixer.play(clip, bus, false, 0f, pan, priority);
    }

    private static float clampPan(float pan) {
        return Math.max(-1f, Math.min(1f, pan));
    }

    @Override
    public void close() {
        if (driver != null) {
            driver.close();
        }
    }
}
