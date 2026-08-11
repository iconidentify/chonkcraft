package net.chonkbase.chonkcraft.data.source;

import java.util.List;

/**
 * The archive identifiers, as a property of the format rather than of any file.
 *
 * <p>A Warcraft II archive declares its own id in its header: the u16 that
 * follows the magic byte and the entry count. passes the
 * expected value in to every open and refuses an archive that declares a
 * different one, so the number is how a caller says <em>which</em> archive it
 * wants without saying where that archive lives. That is the whole reason these
 * exist as bare constants. There is no path here, no file name, no release, and
 * nothing that presumes a 1995 installation is on the disk at all -- a pack
 * built years after the discs are gone still answers to 1000 for the main
 * archive, because 1000 is what the format calls it.
 *
 * <p>{@link net.chonkbase.chonkcraft.data.archive.WarArchive} carries the same six
 * numbers as its own {@code ID_*} constants, and they are deliberately not
 * shared. Those belong to the reader: they are what {@code WarArchive.open}
 * validates a header against, and a caller referring to them is a caller
 * holding on to the {@code .war} reader. The engine holds an
 * {@link AssetSource}, which may be a pack with no {@code WarArchive} behind it
 * anywhere, and it should not have to name the reader for a file it will never
 * open in order to ask for archive 1000. Same for
 * {@code Warcraft2Install.Archive}, which is worse again: reaching into it for
 * an id drags in a table of DOS and Mac file names and the install-directory
 * search that goes with them, all to obtain an integer. That is the failure
 * shape CONTRIBUTING.md names -- code written from an idea of where the number
 * comes from rather than from what the number is.
 *
 * <p>The values are fixed by the data and cannot be renumbered.
 */
public final class ArchiveIds {

    /** {@code maindat.war}: sprites, tilesets, fonts, cursors, the campaign PUDs. */
    public static final int MAINDAT = 1000;

    /** {@code snddat.war}: digitised speech and effects, CD releases only. */
    public static final int SNDDAT = 2000;

    /** {@code rezdat.war}: menu and interface art. */
    public static final int REZDAT = 3000;

    /** {@code strdat.war}: all game text. */
    public static final int STRDAT = 4000;

    /** {@code sfxdat.sud}: sound effects on floppy and DOS builds. */
    public static final int SFXDAT = 5000;

    /** {@code muddat.cud}: the intro and outro movies. */
    public static final int MUDDAT = 6000;

    /**
     * All six, in ascending order, for the callers that sweep every archive.
     *
     * <p>Six is the whole set: the format defines no others, and a release
     * ships some subset of them. A caller sweeping this list must therefore
     * expect {@link AssetSource#archive} to answer {@code null} -- the DOS
     * release genuinely has no {@code snddat.war} -- rather than treat a miss
     * as a fault.
     *
     * <p>Here because the sweeps existed already and each wrote the six
     * numbers out again as a literal. Two lists of the same constants is one
     * list that can fall behind.
     */
    public static final List<Integer> ALL =
            List.of(MAINDAT, SNDDAT, REZDAT, STRDAT, SFXDAT, MUDDAT);

    private ArchiveIds() {
    }
}
