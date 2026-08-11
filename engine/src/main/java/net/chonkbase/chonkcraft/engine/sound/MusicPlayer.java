package net.chonkbase.chonkcraft.engine.sound;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import net.chonkbase.chonkcraft.data.GraphicsIndex;
import net.chonkbase.chonkcraft.data.music.XmiToMidi;
import net.chonkbase.chonkcraft.data.source.EntryArchive;

/**
 * Plays the game's music, one track after another.
 *
 * <p>Implements {@code PlayMusic} / {@code StopMusic} and the callback machinery, together with the {@code MusicStopped} playlist
 * function the game data supplies in {@code scripts/sound.legacy-declaration}.
 *
 * <p>The tracks are MIDI once converted, so they go to the JVM's own
 * sequencer rather than through the PCM mixer: a synthesiser is exactly what
 * MIDI needs, and routing it through the sample mixer would mean rendering it
 * to PCM first for no gain.
 *
 * <p>Music used to play exactly one track and then stop for the rest of the
 * session, and there was none at all in the menus, the briefings or the end
 * screens. The cause was a single line here: {@code play} set
 * {@code LOOP_CONTINUOUSLY}, with a comment asserting that Warcraft II loops
 * its battle music. It does not. Upstream calls
 * {@code Mix_PlayMusic(music, 0)} -- play once -- and hooks
 * {@code MusicFinishedCallback}, which asks the retired scripting language for the next track. With
 * the loop on, the sequence never reaches its end, the end-of-track meta event
 * is never delivered, and there is nothing for a playlist to hang off. So the
 * loop was not merely a wrong flag; it was what made the missing playlist
 * invisible.
 *
 * <p>Fails soft throughout. A machine with no synthesiser should still play
 * the game in silence rather than not start.
 */
public final class MusicPlayer implements AutoCloseable {

    /** MIDI meta event type for end of track. */
    private static final int END_OF_TRACK = 0x2F;

    private final EntryArchive archive;
    private final GraphicsIndex index;
    private Sequencer sequencer;
    private String unavailableReason = "not started";

    /**
     * The synthesiser the sequencer feeds, opened here rather than left to
     * {@code MidiSystem} to connect.
     *
     * <p>{@code MidiSystem.getSequencer()} wires the sequencer to the default
     * synthesiser itself and hands back nothing that can be got at in between,
     * which is why the music slider had no effect on this half of the
     * soundtrack: there was nowhere to put a volume. Opening the two separately
     * and joining them by hand costs a few lines and leaves {@link MidiVolume}
     * sitting in the join.
     */
    private javax.sound.midi.Synthesizer synthesizer;

    private MidiVolume volume;

    /** Where the slider is, kept so it survives the synthesiser being opened. */
    private float musicVolume = 1f;

    /** Track paths, in the order the conversion table lists them. */
    private final List<String> tracks = new ArrayList<>();

    /** What to draw the next track from when the current one ends. */
    private final List<String> playlist = new ArrayList<>();

    /**
     * Deliberately not {@code World.syncRand}. Which track plays is
     * presentation, not simulation: drawing from the synchronised stream would
     * make a player's music advance consume shared random state and desync
     * lockstep multiplayer. Injectable so the selection can be tested.
     */
    private final Random random;

    /**
     * Upstream's {@code IsCallbackEnabled}. {@code StopMusic} there brackets
     * the halt with {@code CallbackMusicDisable}/{@code Enable} so that
     * stopping deliberately does not chain into the next track; without that,
     * every {@code stop()} would start something.
     */
    private volatile boolean advanceOnFinish = true;

    /**
     * Upstream's {@code MusicFinishedEventQueued} test-and-set. A multi-track
     * sequence delivers one end-of-track event per track, and only the first
     * should advance the playlist.
     */
    private final AtomicBoolean advanceQueued = new AtomicBoolean();

    /**
     * The meta event arrives on the sequencer's own thread, and driving
     * {@code setSequence}/{@code start} from inside that callback re-enters
     * the sequencer while it is finishing. Upstream has the same problem --
     * its callback runs on the SDL audio thread -- and solves it by posting an
     * event for the main loop to run. This is that hand-off.
     */
    private ExecutorService advancer;

    public MusicPlayer(EntryArchive archive, GraphicsIndex index) {
        this(archive, index, new Random());
    }

    /** For tests: a fixed random stream makes the selection reproducible. */
    public MusicPlayer(EntryArchive archive, GraphicsIndex index, Random random) {
        this.archive = archive;
        this.index = index;
        this.random = random;
        for (GraphicsIndex.Asset asset : index.assets()) {
            if (asset.kind() == GraphicsIndex.Kind.MUSIC) {
                tracks.add(asset.path());
            }
        }
    }

    /**
     * Opens the synthesiser.
     *
     * <p>Idempotent. Music now begins at the menu and continues into a
     * mission, and {@code GameData} hands out one player for both, so the
     * second caller must not open a second sequencer and orphan the first.
     */
    public void start() {
        if (isAvailable()) {
            return;
        }
        if (archive == null) {
            // Nothing to convert, so nothing to play: without the sound archive
            // every call to sequence() answers null. Opening a synthesiser for
            // it costs a second and a soundbank load and buys silence either
            // way.
            unavailableReason = "no sound archive to read the tracks from";
            return;
        }
        try {
            sequencer = openSequencer();
            sequencer.open();
            advancer = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "music-playlist");
                thread.setDaemon(true);
                return thread;
            });
            sequencer.addMetaEventListener(message -> {
                if (message.getType() == END_OF_TRACK) {
                    onTrackFinished();
                }
            });
        } catch (Exception e) {
            sequencer = null;
            unavailableReason = String.valueOf(e.getMessage());
        }
    }

    /**
     * A sequencer with {@link MidiVolume} between it and the synthesiser.
     *
     * <p>{@code getSequencer(false)} asks for one that is <em>not</em> already
     * wired to a synthesiser, so the two ends can be joined here with the
     * volume in the middle. Falls back to the connected sequencer if this
     * machine will not give up its synthesiser separately: silence is a worse
     * outcome than a music slider that only moves the recorded soundtrack, and
     * that is the state the implementation was in already.
     */
    private Sequencer openSequencer() throws javax.sound.midi.MidiUnavailableException {
        Sequencer detached = null;
        javax.sound.midi.Synthesizer synth = null;
        try {
            detached = MidiSystem.getSequencer(false);
            // Opened before its transmitter is asked for, not after. Closing a
            // device closes the transmitters it handed out, and the order the
            // two happen in is the kind of thing that works on the machine it
            // was written on.
            detached.open();
            synth = MidiSystem.getSynthesizer();
            synth.open();
            MidiVolume through = new MidiVolume(synth.getReceiver());
            through.setVolume(musicVolume);
            detached.getTransmitter().setReceiver(through);
            synthesizer = synth;
            volume = through;
            return detached;
        } catch (Exception e) {
            volumeUnavailableReason = String.valueOf(e.getMessage());
            closeQuietly(detached);
            closeQuietly(synth);
            return MidiSystem.getSequencer();
        }
    }

    private static void closeQuietly(javax.sound.midi.MidiDevice device) {
        if (device != null) {
            try {
                device.close();
            } catch (Exception ignored) {
                // Giving up on a device that will not close is the whole of
                // what can be done about it.
            }
        }
    }

    /** Why the slider cannot reach the synthesiser, when it cannot. */
    private String volumeUnavailableReason;

    /**
     * Whether the music slider reaches the synthesiser on this machine.
     *
     * <p>Reported rather than assumed, because the fallback above is silent by
     * design and a silent fallback is how "the volume control has no effect"
     * gets reported a second time.
     */
    public boolean isVolumeControllable() {
        return volume != null;
    }

    public String volumeUnavailableReason() {
        return volumeUnavailableReason;
    }

    /**
     * Where the music slider is, nought to one.
     *
     * <p>Kept here as well as in {@link MidiVolume} so that setting it before
     * the synthesiser is open is not thrown away: the menus set a volume from
     * the saved settings and only then does anything start a track.
     */
    public float volume() {
        return musicVolume;
    }

    /** Moves the music slider for the synthesised half of the soundtrack. */
    public void setVolume(float wanted) {
        musicVolume = Math.max(0f, Math.min(1f, wanted));
        if (volume != null) {
            volume.setVolume(musicVolume);
        }
    }

    /** Whether music can actually play. */
    public boolean isAvailable() {
        return sequencer != null && sequencer.isOpen();
    }

    public String unavailableReason() {
        return unavailableReason;
    }

    /** The track names available. */
    public List<String> tracks() {
        return tracks;
    }

    /** Converts one track to MIDI, or {@code null} if it is not there. */
    public Sequence sequence(String path) {
        GraphicsIndex.Asset asset = index.find(path);
        if (asset == null || asset.kind() != GraphicsIndex.Kind.MUSIC || archive == null) {
            return null;
        }
        try {
            byte[] midi = XmiToMidi.convert(archive.entry(asset.musicEntry()));
            return MidiSystem.getSequence(new ByteArrayInputStream(midi));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Starts a track, once.
     *
     * <p>Once, not looping, because that is what {@code PlayMusic} does
     * upstream and it is what lets the end of a track mean something. When it
     * ends, {@link #onTrackFinished} draws the next one from the playlist.
     *
     * @return whether it started
     */
    public boolean play(String path) {
        if (!isAvailable()) {
            return false;
        }
        Sequence sequence = sequence(path);
        if (sequence == null) {
            return false;
        }
        try {
            advanceOnFinish = false;
            sequencer.stop();
            sequencer.setSequence(sequence);
            sequencer.setLoopCount(0);
            sequencer.setTickPosition(0);
            advanceQueued.set(false);
            advanceOnFinish = true;
            // The channels start where the last track left them, so the slider
            // is re-applied over the reset before a note is played. Eight of
            // the eighteen tracks send fewer volume changes than they have
            // channels, and without this those channels would keep whichever
            // level the previous track happened to end on.
            if (volume != null) {
                volume.reset();
            }
            sequencer.start();
            return true;
        } catch (Exception e) {
            advanceOnFinish = true;
            return false;
        }
    }

    /**
     * Sets what plays after the current track, replacing any previous list.
     *
     * <p>An empty list means "play what is playing and then stop", which is
     * how upstream gets a briefing to play its one track and fall silent:
     * {@code scripts/menus/campaign.legacy-declaration} assigns {@code chonkcraft.playlist = {}}
     * before calling {@code PlayMusic}.
     */
    public void setPlaylist(List<String> paths) {
        synchronized (playlist) {
            playlist.clear();
            playlist.addAll(paths);
        }
    }

    /** What would be drawn from next. */
    public List<String> playlist() {
        synchronized (playlist) {
            return List.copyOf(playlist);
        }
    }

    /**
     * Starts the playlist, as {@code MusicStopped} in
     * {@code scripts/sound.legacy-declaration} does.
     *
     * <p>Picks at random, plays it, and on failure drops that entry and picks
     * again until something plays or the list is empty. Note that a track that
     * plays is <em>not</em> removed -- upstream returns as soon as
     * {@code PlayMusic} succeeds, so the same track can come round again. Only
     * broken entries are pruned.
     *
     * @return whether anything started
     */
    public boolean playFromPlaylist() {
        return isAvailable() && advanceUsing(this::play);
    }

    /**
     * The selection itself, with the attempt to play left to the caller.
     *
     * <p>Package-private, and separate from {@link #playFromPlaylist}, so that
     * the choosing and pruning can be tested without a synthesiser. No test in
     * this project opens an audio device, and a rule that holds everywhere
     * else should not be broken for the one subsystem whose defect was that
     * nobody noticed it had stopped.
     */
    boolean advanceUsing(java.util.function.Predicate<String> attempt) {
        while (true) {
            String choice;
            synchronized (playlist) {
                if (playlist.isEmpty()) {
                    return false;
                }
                choice = playlist.get(random.nextInt(playlist.size()));
            }
            if (attempt.test(choice)) {
                return true;
            }
            synchronized (playlist) {
                playlist.remove(choice);
            }
        }
    }

    /**
     * Replaces the playlist and starts it immediately.
     *
     * <p>And falls silent when the new list has nothing in it. Only
     * {@link #play} stops the sequencer, and {@link #advanceUsing} returns on
     * the spot for an empty list without reaching it, so handing this a
     * playlist that this installation does not have used to leave the previous
     * track running under the new screen. Tides of Darkness has no sixth battle
     * track and a DOS hard-disk install has no music at all until the disc is
     * cached, so an empty list is a state a real player reaches. Upstream's
     * {@code PlayMusic} halts what is playing before it starts anything
     * so nothing survives a
     * change of music there either.
     */
    public boolean playPlaylist(List<String> paths) {
        setPlaylist(paths);
        if (playFromPlaylist()) {
            return true;
        }
        stop();
        return false;
    }

    /**
     * Starts {@code paths} only if nothing is playing already.
     *
     * <p>The menus use this. Upstream's idiom, repeated in a dozen places in
     * {@code scripts/guichan.legacy-declaration} and {@code scripts/menus/*.legacy-declaration}, is to set
     * the playlist and then {@code if not (IsMusicPlaying()) then PlayMusic}
     * -- so walking between menu screens does not restart the theme from the
     * top each time.
     */
    public boolean continuePlaylist(List<String> paths) {
        setPlaylist(paths);
        if (isPlaying()) {
            return true;
        }
        return playFromPlaylist();
    }

    /** Whether something is playing now. */
    public boolean isPlaying() {
        return isAvailable() && sequencer.isRunning();
    }

    /**
     * Stops, without advancing.
     *
     * <p>Implements {@code StopMusic}, including its bracketing of the halt
     * with the callback disabled. Rewinds too, so a later {@code start} on the
     * sequencer cannot resume half a track.
     */
    public void stop() {
        if (!isAvailable()) {
            return;
        }
        advanceOnFinish = false;
        try {
            sequencer.stop();
            sequencer.setTickPosition(0);
        } catch (Exception e) {
            // A sequencer that will not stop is not worth failing startup for.
        } finally {
            advanceQueued.set(false);
            advanceOnFinish = true;
        }
    }

    /** Stops and forgets the playlist, so nothing can follow. */
    public void silence() {
        setPlaylist(List.of());
        stop();
    }

    /**
     * The end-of-track hook. Upstream's {@code MusicFinishedCallback}.
     *
     * <p>Package-private rather than private so a test can drive the advance
     * without a synthesiser.
     */
    void onTrackFinished() {
        if (!advanceOnFinish) {
            return;
        }
        if (advanceQueued.getAndSet(true)) {
            return;
        }
        ExecutorService queue = advancer;
        if (queue == null || queue.isShutdown()) {
            return;
        }
        queue.execute(() -> {
            advanceQueued.set(false);
            playFromPlaylist();
        });
    }

    // ---------------------------------------------------------------- tracks
    //
    // Which tracks belong to which situation. Upstream keeps this in the game
    // data -- scripts/human/ui_tales.legacy-declaration and its siblings assign
    // chonkcraft.playlist, scripts/menus/results.legacy-declaration picks the victory and defeat
    // themes -- so these lists are transcriptions of that data, not choices
    // made here.
    //
    // The names are the bare index names from graphics-index.tsv. Do not add
    // the "music/" prefix or the ".mid" suffix the retired scripting language uses: GraphicsIndex
    // registers both the bare and prefixed spellings, but its find() strips
    // only ".png" and ".wav", so a name ending in ".mid" resolves to nothing.

    /** The five battle tracks for a race, as the mission scripts set them. */
    public static List<String> battleTracks(boolean orc) {
        String race = orc ? "Orc" : "Human";
        List<String> list = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            list.add(race + " Battle " + i);
        }
        return List.copyOf(list);
    }

    /**
     * The menu theme.
     *
     * <p>"Orc Briefing", which is not a mistake: the shipped table gives the
     * main menu and the orc briefing the same archive entry, and every menu in
     * {@code scripts/guichan.legacy-declaration} names {@code music/Orc Briefing}.
     */
    public static List<String> menuTracks() {
        return List.of("Orc Briefing");
    }

    /** The briefing theme for a race. Plays once, then silence, as upstream. */
    public static List<String> briefingTracks(boolean orc) {
        return List.of(orc ? "Orc Briefing" : "Human Briefing");
    }

    /** The result themes, from {@code scripts/menus/results.legacy-declaration}. */
    public static List<String> resultTracks(boolean orc, boolean won) {
        return List.of((orc ? "Orc " : "Human ") + (won ? "Victory" : "Defeat"));
    }

    /**
     * Keeps only the tracks this installation actually has.
     *
     * <p>A DOS hard-disk install has no {@code snddat.war} until it is cached
     * from the disc, and the expansion adds a sixth battle track that Tides of
     * Darkness does not have. Handing the playlist a name that is not there
     * would work -- {@link #playFromPlaylist} prunes what will not play -- but
     * it would do so by trying and failing once per track, audibly late.
     */
    public List<String> available(List<String> paths) {
        List<String> present = new ArrayList<>();
        for (String path : paths) {
            for (String known : tracks) {
                if (known.equalsIgnoreCase(path)
                        || known.toLowerCase(Locale.ROOT)
                                .endsWith("/" + path.toLowerCase(Locale.ROOT))) {
                    present.add(known);
                    break;
                }
            }
        }
        return List.copyOf(present);
    }

    @Override
    public void close() {
        advanceOnFinish = false;
        if (advancer != null) {
            advancer.shutdownNow();
            advancer = null;
        }
        if (sequencer != null) {
            sequencer.close();
            sequencer = null;
        }
        if (synthesizer != null) {
            synthesizer.close();
            synthesizer = null;
        }
        volume = null;
    }
}
