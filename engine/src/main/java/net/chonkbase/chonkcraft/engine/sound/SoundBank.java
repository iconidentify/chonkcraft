package net.chonkbase.chonkcraft.engine.sound;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.chonkbase.runtime.audio.PcmClip;
import net.chonkbase.chonkcraft.data.GraphicsIndex;
import net.chonkbase.chonkcraft.data.source.EntryArchive;

/**
 * The game's sounds, decoded from the archive on demand.
 *
 * <p>The sounds themselves are already RIFF WAV inside {@code sfxdat.sud}, so
 * there is nothing to convert: the archive reader hands back a well-formed
 * file and the runtime's own loader turns it into PCM. That is why the
 * extractor's sound path is a lookup rather than a decoder.
 *
 * <p>Named sounds are a second layer above the files. The scripts declare
 * {@code MakeSound("footman-selected", "human/units/footman/selected1.wav")}
 * and units then refer to the name, so one voice can be swapped without
 * touching a unit definition. {@link #define} records those bindings.
 */
public final class SoundBank {

    /**
     * Archives by id, because sounds are spread across three of them: the
     * interface clicks live in maindat, the effects and voices in sfxdat, and
     * the CD releases add more in snddat.
     */
    private final Map<Integer, EntryArchive> archives;
    private final GraphicsIndex index;

    /** Script name to asset path or paths. */
    private final Map<String, java.util.List<String>> named = new LinkedHashMap<>();

    /** Decoded clips, by asset path. */
    private final Map<String, PcmClip> clips = new HashMap<>();

    /** Paths that turned out not to be loadable, so they are not retried. */
    private final Map<String, String> failures = new LinkedHashMap<>();

    /**
     * @param archives archives by id; a release that lacks one simply omits it
     * @param index    the asset index, for path to entry lookups
     */
    public SoundBank(Map<Integer, EntryArchive> archives, GraphicsIndex index) {
        this.archives = Map.copyOf(archives);
        this.index = index;
    }

    /** Whether there is anything to read from at all. */
    public boolean isAvailable() {
        return !archives.isEmpty();
    }

    /**
     * Binds a script name to one or more sound files.
     *
     * <p>Several files means a group: the engine picks one at random each
     * time, which is why a barracks full of footmen does not answer in
     * unison.
     */
    public void define(String name, java.util.List<String> paths) {
        named.put(name, java.util.List.copyOf(paths));
    }

    /**
     * A selection sound: the lines a unit gives when it is clicked, and the
     * ones it gives when it has been clicked once too often.
     *
     * <p>{@code MakeSoundGroup("footman-selected", "basic human voices
     * selected", "basic human voices annoyed")}. Upstream models this as a
     * pair of sounds rather than as one long list ({@code std::pair<CSound *,
     * CSound *>} in {@code CSound::Sound}), and the distinction matters: the
     * two halves are not interchangeable. The first is picked from at random;
     * the second is only reached by pestering the same unit, and is then walked
     * in order.
     *
     * @param normal  the name of the acknowledging group
     * @param annoyed the name of the group it turns to under repetition
     */
    public record Selection(String normal, String annoyed) {}

    /** Selection pairs by name. */
    private final Map<String, Selection> selections = new LinkedHashMap<>();

    /**
     * Binds a name to a pair of existing sounds, as {@code MakeSoundGroup}
     * does.
     *
     * <p>The flattened list is bound as well so that anything asking only for
     * this name's files still gets them all.
     */
    public void defineSelection(String name, String normal, String annoyed) {
        selections.put(name, new Selection(normal, annoyed));
        java.util.List<String> combined = new java.util.ArrayList<>(pathsFor(normal));
        combined.addAll(pathsFor(annoyed));
        define(name, combined);
    }

    /** The pair behind a selection sound, or null if this name is a plain group. */
    public Selection selection(String name) {
        return selections.get(name);
    }

    /** The files bound to a script name, or an empty list. */
    public java.util.List<String> pathsFor(String name) {
        return named.getOrDefault(name, java.util.List.of());
    }

    /**
     * How many clips a single random pick has to choose between.
     *
     * <p>The one number a caller must not invent. Every place that plays a
     * sound used to hand in {@code syncRand(n)} with {@code n} written out by
     * hand -- 4 for a voice, 3 for an animation sound, 2 for a death -- and the
     * data does not agree with any of them. {@code building destroyed} has
     * three clips and was drawn from two, so a third of the sound the player
     * was meant to hear did not exist as far as the game was concerned;
     * {@code tree-chopping} has four and was drawn from three. The group's own
     * size is the only answer that cannot go stale when the scripts change.
     *
     * <p>For a selection pair it is the size of the first half only. A single
     * draw across both halves would have a footman answer an order with an
     * annoyed grumble half the time, which is not what the pair means.
     *
     * <p>Never zero, so that a caller drawing from the synchronised generator
     * consumes exactly one number whatever the bank knows about the name. A
     * machine with no sound archives must still advance the sequence in step
     * with one that has them.
     */
    public int groupSize(String name) {
        Selection pair = selections.get(name);
        if (pair != null) {
            return Math.max(1, pathsFor(pair.normal()).size());
        }
        return Math.max(1, pathsFor(name).size());
    }

    /** How many names are bound. */
    public int definedCount() {
        return named.size();
    }

    /**
     * Every bound name, in the order it was bound.
     *
     * <p>Binding order, because the definitions are order-dependent: a
     * selection pair is built from the files two earlier names already hold,
     * so a reader checking one bank against another has to walk them the way
     * they were written.
     */
    public java.util.Set<String> names() {
        return java.util.Collections.unmodifiableSet(named.keySet());
    }

    /** Paths that could not be decoded, with the reason. */
    public Map<String, String> failures() {
        return failures;
    }

    /**
     * Decodes the clip at an asset path, or {@code null} if there is none.
     *
     * <p>Cached, because a unit acknowledging an order plays the same handful
     * of files hundreds of times a game.
     */
    public PcmClip clip(String path) {
        if (archives.isEmpty() || path == null || path.isEmpty()) {
            return null;
        }
        PcmClip cached = clips.get(path);
        if (cached != null) {
            return cached;
        }
        if (failures.containsKey(path)) {
            return null;
        }

        GraphicsIndex.Asset asset = index.find(stripExtension(path));
        if (asset == null || asset.kind() != GraphicsIndex.Kind.SOUND) {
            failures.put(path, "not a sound in the conversion table");
            return null;
        }
        EntryArchive archive = archives.get(asset.archive());
        if (archive == null) {
            failures.put(path, "archive " + asset.archive() + " is not in this release");
            return null;
        }
        int entry = asset.soundEntry();
        if (entry < 0 || entry >= archive.entryCount()) {
            failures.put(path, "entry " + entry + " is outside archive " + asset.archive());
            return null;
        }
        try {
            PcmClip clip = LegacyWavDecoder.decode(path, archive.entry(entry));
            clips.put(path, clip);
            return clip;
        } catch (RuntimeException e) {
            failures.put(path, String.valueOf(e.getMessage()));
            return null;
        }
    }

    /**
     * A clip for a script name, choosing among a group.
     *
     * @param pick a value used to select within a group; the simulation
     *             passes its own synced random number so that two machines
     *             agree on which voice line played
     */
    public PcmClip clipForName(String name, int pick) {
        return clip(pathForName(name, pick));
    }

    /**
     * Which file a name and a pick resolve to, without decoding it.
     *
     * <p>Split out from {@link #clipForName} so that the choice can be checked
     * on a machine with no sound device: which clip the game reaches for is a
     * fact about the game, not about the speakers.
     */
    public String pathForName(String name, int pick) {
        java.util.List<String> paths = pathsFor(name);
        if (paths.isEmpty()) {
            // A name may itself be a path, which the shorter definitions use.
            return name;
        }
        return paths.get(Math.floorMod(pick, paths.size()));
    }

    /**
     * Which file one half of a selection pair resolves to.
     *
     * @param index the position within that half, taken modulo its size
     */
    public String pathForSelection(String name, boolean annoyed, int index) {
        Selection pair = selections.get(name);
        if (pair == null) {
            return pathForName(name, index);
        }
        java.util.List<String> paths = pathsFor(annoyed ? pair.annoyed() : pair.normal());
        if (paths.isEmpty()) {
            return pathForName(name, index);
        }
        return paths.get(Math.floorMod(index, paths.size()));
    }

    /** How many clips the annoyed half of a selection pair holds. */
    public int annoyedSize(String name) {
        Selection pair = selections.get(name);
        return pair == null ? 0 : pathsFor(pair.annoyed()).size();
    }

    /** Whether a path resolves to a sound entry, without decoding it. */
    public boolean has(String path) {
        GraphicsIndex.Asset asset = index.find(stripExtension(path));
        return asset != null && asset.kind() == GraphicsIndex.Kind.SOUND;
    }

    private static String stripExtension(String path) {
        return path.endsWith(".wav") ? path.substring(0, path.length() - 4) : path;
    }
}
