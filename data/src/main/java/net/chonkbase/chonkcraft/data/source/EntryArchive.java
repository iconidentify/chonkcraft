package net.chonkbase.chonkcraft.data.source;

/**
 * A numbered run of archive entries, however it is actually stored.
 *
 * <p>Warcraft II's data is numbered rather than named: entry 33 of
 * {@code maindat.war} is a sprite sheet and nothing in the file says whose.
 * The engine holds those numbers as constants in a dozen places -- the font
 * palette is entry 2, the second widget palette is entry 14, each tileset is a
 * fixed triple -- so any store that wants to stand in for the 1995 archives
 * has to answer to a number, not to a path.
 *
 * <p>This is the whole of the contract. Every byte of Warcraft II data that
 * the engine reads arrives through these four methods, which is why an asset
 * pack can replace the installation without a single decoder changing: the
 * run-length sprite reader, the tileset assembler, the WAV reader and the
 * Smacker decoder all still see exactly the bytes they saw before.
 *
 * <p>Implements the reading half of {@code OpenArchive},
 * lifted out of {@link net.chonkbase.chonkcraft.data.archive.WarArchive} so that
 * something other than a file can implement it.
 */
public interface EntryArchive {

    /** Which archive this is: 1000 for maindat, 5000 for sfxdat, and so on. */
    int id();

    /**
     * How many entries the archive declares.
     *
     * <p>Load-bearing twice over, and not merely informational. The implementation
     * decides an installation has Beyond the Dark Portal by comparing this
     * against 437, and {@code SoundBank} bounds-checks every entry number the
     * conversion table gives it against this before reading. A store that
     * reports the number of assets it actually holds rather than the number of
     * slots the original had gets both of those wrong.
     */
    int entryCount();

    /**
     * Whether entry {@code index} held something readable.
     *
     * <p>The DOS build's {@code maindat.war} has five filler slots, 28 to 32,
     * whose offsets sit a byte apart while declaring multi-megabyte lengths.
     * They are junk and they still occupy their indices; closing the gap would
     * renumber every entry after them.
     */
    boolean isValid(int index);

    /**
     * The decompressed bytes of entry {@code index}.
     *
     * <p>An entry that failed validation must yield wartool's one-byte
     * placeholder {@code {0x01}} rather than throwing. Callers depend on it:
     * the font and cursor loaders sniff the result and fall through to null on
     * a miss, so an implementation that throws turns a graceful degradation
     * into a crash on any installation that is a little ragged.
     *
     * @throws IndexOutOfBoundsException if {@code index} is outside the table
     */
    byte[] entry(int index);
}
