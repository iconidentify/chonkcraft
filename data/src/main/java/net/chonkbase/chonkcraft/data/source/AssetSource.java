package net.chonkbase.chonkcraft.data.source;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Everywhere the game's data can come from, behind one interface.
 *
 * <p>Two things implement it. {@link InstallSource} reads a 1995 Warcraft II
 * installation directly, which is what this implementation did from the beginning and
 * what the extractor still does. {@code PackSource} reads a single asset pack
 * file, which is what a player gets.
 *
 * <p>The interface is shaped by what the engine already asks for rather than
 * by what would be tidy, because the point of it is that nothing downstream
 * changes. Four kinds of thing come out of a Warcraft II installation and none
 * of them reduces to the others:
 *
 * <ul>
 *   <li>numbered archive entries, {@link #archive},</li>
 *   <li>loose {@code .PUD} maps sitting in the install directory,</li>
 *   <li>red book music, which is raw sectors on a disc and not a file at
 *       all,</li>
 *   <li>facts about which release this is, which the implementation currently works out
 *       by measuring file sizes and counting entries.</li>
 * </ul>
 *
 * <p>That last group is the one that would otherwise be lost. Three separate
 * sniffs decide whether an installation has the expansion -- an entry count
 * over 437, a {@code rezdat.war} of exactly 2,811,086 bytes, and the presence
 * of {@code support/tomes/tome.1} -- and none of them means anything once the
 * files are gone. A pack records the answers instead of the evidence.
 */
public interface AssetSource extends AutoCloseable {

    /** A named file carried outside Warcraft II's numbered WAR archives. */
    record SupplementalAsset(String path, Kind kind) {

        /** What the file contributes to a graphics pack. */
        public enum Kind {
            SOUND,
            MAP,
            VIDEO,
            MUSIC,
            TEXT,
            BINARY
        }
    }

    /** A short description of where this data came from, for diagnostics. */
    String describe();

    /** Stable identity written into a pack made from this release. */
    default String editionId() {
        if (isBattleNetEdition()) {
            return "wc2-battle-net-edition";
        }
        return hasExpansion() ? "wc2-expansion" : "wc2-tides-of-darkness";
    }

    /** Player-facing release name written into a pack made from this source. */
    default String editionName() {
        if (isBattleNetEdition()) {
            return "Warcraft II: Battle.net Edition";
        }
        return hasExpansion()
                ? "Warcraft II: Tides of Darkness and Beyond the Dark Portal"
                : "Warcraft II: Tides of Darkness";
    }

    /** The most specific source release the importer can prove. */
    default String sourceVersion() {
        if (isBattleNetEdition()) {
            return "Battle.net Edition";
        }
        return hasExpansion() ? "Beyond the Dark Portal" : "Tides of Darkness";
    }

    /** The on-media layout that identified this release. */
    default String sourceFormat() {
        if (isBattleNetEdition()) {
            return "Battle.net Edition MPQ";
        }
        return "Classic Warcraft II archives";
    }

    /**
     * The archive with this id, or {@code null} when this release has no such
     * archive.
     *
     * <p>Null is a real answer, not a failure. The DOS release genuinely has
     * no {@code snddat.war}: its sounds are in {@code sfxdat.sud} and its
     * music is on the disc as audio tracks.
     */
    EntryArchive archive(int archiveId);

    /** Whether {@link #archive} would return something for this id. */
    default boolean hasArchive(int archiveId) {
        return archive(archiveId) != null;
    }

    /**
     * Whether this source carries the original computer-player program used by
     * the native Java interpreter.
     *
     * <p>The program is main archive entry 277 in every supported retail
     * release. A source without it can draw the game but cannot provide the
     * opponent behavior ChonkCraft promises, so it is not a playable source.
     */
    default boolean hasRetailAiProgram() {
        EntryArchive main = archive(1000);
        return main != null && main.entryCount() > 277
                && main.isValid(277) && main.entry(277).length > 1;
    }

    // ------------------------------------------------------------- identity

    /**
     * Whether the data holds Beyond the Dark Portal's added entries.
     *
     * <p>Selects between the two rows the conversion table holds for a handful
     * of paths: one for the expansion and one stand-in that points at whatever
     * base-game entry is nearest. Getting it wrong makes the human campaign's
     * ending read as the tail of the sentence before it.
     */
    boolean hasExpansion();

    /**
     * Whether this is the expansion <em>release</em>, which is a different
     * question.
     *
     * <p>A Tides of Darkness installation patched up to the expansion's
     * archives has the entries but is not the expansion release, and the
     * campaign text sits at a different offset in the two. The implementation learns
     * this from a file size and a directory probe today.
     */
    boolean isExpansionRelease();

    /**
     * Whether this is the Battle.net edition.
     *
     * <p>Not the same question as either of the two above, and it cannot be
     * derived from them: the Battle.net edition carries the expansion, so
     * {@link #isExpansionRelease} folds it in and cannot be asked to tell them
     * apart again. Three interface layout scripts branch on it and use
     * different menu art.
     *
     * <p>Learnt from a directory probe for {@code support/tomes/tome.1} on the
     * raw path, which is exactly the kind of evidence that vanishes when the
     * files are gone, so a pack writes the answer down instead.
     */
    boolean isBattleNetEdition();

    /**
     * Where the campaign title and objectives table starts inside its entry.
     *
     * <p>236 in the expansion release, 140 in Tides of Darkness. The
     * conversion table says 236 in both, which is why this cannot be read off
     * the table and has to be carried alongside it.
     */
    int campaignTextOffset();

    // ----------------------------------------------------------------- maps

    /**
     * Loose {@code .PUD} maps, by the name the game shows, sorted.
     *
     * <p>Names rather than paths. On the raw path a map is a file and the
     * saved-game format writes its absolute location; in a pack it is an entry
     * with no location at all, so the name is the only identity that survives
     * both.
     */
    List<String> mapNames();

    /** Whether a map of this name is available, ignoring case. */
    default boolean hasMap(String name) {
        for (String candidate : mapNames()) {
            if (candidate.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The bytes of a loose map, or {@code null}.
     *
     * <p>Must be byte-identical on every machine that has the same source.
     * Two peers in a multiplayer game simulate from these bytes in lockstep,
     * and a map that differs by one tile desynchronises them silently.
     */
    byte[] map(String name);

    // --------------------------------------------------- supplemental files

    /**
     * Named assets this release carries outside its numbered WAR archives.
     *
     * <p>Classic releases return none. Battle.net Edition keeps its unit
     * voices, multiplayer maps, movies and recorded music in MPQ containers
     * embedded in {@code INSTALL.EXE}; those files have names rather than
     * archive slots and are exposed here without leaking MPQ details into the
     * pack builder.
     */
    default List<SupplementalAsset> supplementalAssets() {
        return List.of();
    }

    /** The original bytes of one supplemental asset, or {@code null}. */
    default byte[] supplementalAsset(SupplementalAsset asset) {
        return null;
    }

    // ---------------------------------------------------------- red book

    /** One recorded music track. */
    record MusicTrack(String name, int sampleRate, int channels, long frames,
            String sourceOrigin) {

        /** Backwards-compatible constructor for sources with no named container. */
        public MusicTrack(String name, int sampleRate, int channels, long frames) {
            this(name, sampleRate, channels, frames, "");
        }

        public MusicTrack {
            sourceOrigin = sourceOrigin == null ? "" : sourceOrigin;
        }

        /** How long it runs, in seconds. */
        public double seconds() {
            return sampleRate == 0 ? 0 : frames / (double) sampleRate;
        }
    }

    /**
     * The recorded music, in playing order.
     *
     * <p>Order is the identity: nothing in a Warcraft II installation names
     * its music, and the game asks for "track three of whichever disc is in
     * the drive". A source that reorders them plays different music.
     */
    List<MusicTrack> musicTracks();

    /**
     * One track's samples, interleaved, signed 16-bit, at its own rate.
     *
     * <p>Its own rate, not the mixer's. The disc is 44,100 and the mixer runs
     * at 48,000, and the resampling between them is a specific piece of the
     * port with a test asserting its output frame count. A source that helpfully
     * resamples first would replace that with its own arithmetic.
     */
    short[] musicSamples(int index);

    @Override
    void close();

    // ------------------------------------------------------------ discovery

    /**
     * Whichever source this machine is configured for, or {@code null}.
     *
     * <p>A pack wins when one is named or lying next to the installation,
     * because that is the shipped configuration; a raw installation is the
     * fallback, and remains what the extractor and the whole test suite use.
     *
     * <p>Checked in order: {@link #PACK_PROPERTY}, {@link #PACK_VARIABLE}, a
     * pack beside the installation directory, then the installation itself.
     * The installation is still located by {@code -Dwc2.install.dir} and
     * {@code WC2_INSTALL_DIR}, unchanged, because a hundred and thirteen tests
     * and a continuous integration script that greps for the wording of the
     * skip message depend on those two names.
     */
    static AssetSource fromEnvironment() {
        Path named = pathProperty(PACK_PROPERTY, PACK_VARIABLE);
        if (named != null) {
            AssetSource pack = PackSource.tryOpen(named);
            if (pack != null) {
                return requirePlayable(pack);
            }
        }
        Path install = pathProperty("wc2.install.dir", "WC2_INSTALL_DIR");
        if (install != null) {
            AssetSource beside = PackSource.tryOpen(install.resolve(DEFAULT_PACK_NAME));
            if (beside != null) {
                return requirePlayable(beside);
            }
            AssetSource raw = InstallSource.tryAt(install);
            return raw == null ? null : requirePlayable(raw);
        }
        return null;
    }

    private static AssetSource requirePlayable(AssetSource source) {
        if (source.hasRetailAiProgram()) {
            return source;
        }
        String description = source.describe();
        source.close();
        throw new IllegalStateException(description
                + " is missing the original ai.bin computer-player program");
    }

    /** What a pack built for this game is called when it sits beside the data. */
    String DEFAULT_PACK_NAME = "chonkcraft.chonkpack";

    /**
     * The system property naming an asset pack.
     *
     * <p>A constant because it used to be a string typed out on both sides.
     * The rename to {@code chonkcraft} changed the one that reads it and left
     * seven that write it, the launcher's own Play button among them, so a
     * player who chose a pack launched a game that never saw the choice.
     */
    String PACK_PROPERTY = "chonkcraft.pack";

    /** The environment variable naming an asset pack. */
    String PACK_VARIABLE = "CHONKCRAFT_ASSET_PACK";

    private static Path pathProperty(String property, String variable) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(variable);
        }
        return value == null || value.isBlank() ? null : Paths.get(value);
    }
}
