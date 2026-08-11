package net.chonkbase.chonkcraft.engine.sound;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.GraphicsIndex;

/**
 * What the game calls each sound, without asking anybody's retired scripting language.
 *
 * <p>This subsystem is backed entirely by native declarations. The
 * engine could not boot without a checkout of the ChonkCraft project, and
 * {@code scripts/sound.legacy-declaration} was the cheapest of the twenty files to do without:
 * 878 lines of which 398 are binding calls, in three shapes and no others.
 * Measured across all 371 names the script binds, every one of them is a single
 * file, or every numbered file in one directory, or a pair of two other names.
 * Nothing else. That regularity is why this is a table and not a program.
 *
 * <p>Implements what {@code MakeSound}, {@code MakeSoundGroup},
 * {@code MapSound} and {@code CclDefineGameSounds} do once {@code scripts/sound.legacy-declaration} has run
 * through them. The ordered legacy differential was sealed before the script
 * loader was removed; this resource is now the sole shipped sound definition.
 *
 * <p><b>Deviation.</b> A group's files are counted in the player's own
 * installation here, by asking the index for {@code 1}, {@code 2} and so on
 * until it runs out. Upstream writes each filename in the script, so a group
 * has the size the script says. The bounded difference is a release whose
 * archive holds fewer recordings than the script names: upstream binds the
 * missing ones and plays silence, this binds the ones that are there. Both
 * agree on every release measured, because ChonkCraft's lists were themselves
 * copied from the archive.
 *
 * <p>The whole point of counting rather than listing is that the count is the
 * one number a caller must not invent -- see {@link SoundBank#groupSize} for
 * what hand-written group sizes cost when the data disagreed with them.
 */
public final class SoundBindings {

    /** Where the shipped table lives. */
    private static final String RESOURCE = "/chonkcraft/sound-bindings.tsv";

    /** Highest variant a group is searched for, well past the largest real one. */
    private static final int MAX_GROUP = 32;

    private SoundBindings() {
    }

    /**
     * Binds every shipped sound name on {@code bank}.
     *
     * @param expansionRelease whether this release carries Beyond the Dark
     *                         Portal's recordings. Thirty-four names differ
     *                         between the two: the ten named heroes speak for
     *                         themselves on the expansion and borrow an
     *                         ordinary unit's lines without it, and so does the
     *                         warthog and the skeleton
     */
    public static void install(SoundBank bank, GraphicsIndex index,
            boolean expansionRelease) {
        for (String[] row : rows()) {
            String when = row.length > 4 ? row[4] : "";
            if (when.equals("expansion") && !expansionRelease) {
                continue;
            }
            if (when.equals("base") && expansionRelease) {
                continue;
            }
            if (when.startsWith("tileset:")) {
                // Not yet: no map is loaded, so there is no ground to stand on.
                continue;
            }
            apply(bank, index, row);
        }
    }

    /**
     * Binds the sounds that depend on which tileset the map uses.
     *
     * <p>The critter is the only unit in the game whose voice is a fact about
     * the ground: a sheep in summer, a seal in winter, a pig in wasteland and a
     * warthog in swamp. Upstream does it in {@code scripts/scripts.legacy-declaration:54},
     * after the tileset is known, by pointing {@code critter-selected} and
     * {@code critter-dead} at that tileset's animal.
     *
     * <p>This implementation never ran that file. The sound loader read
     * {@code scripts/sound.legacy-declaration} and nothing else, so a critter was left with
     * what {@code sound.legacy-declaration:840} sets as a placeholder before
     * {@code scripts.legacy-declaration} overrides it: {@code critter-selected} bound to no
     * file at all, and {@code critter-dead} bound to {@code explosion}. A
     * player clicking a sheep heard nothing, and a sheep that died blew up.
     * Both were true before the sounds came off the retired scripting language -- the shipped table
     * reproduced the fault exactly, which is how it was found.
     *
     * @param tileset the map's tileset name, lower case
     */
    public static void installForTileset(SoundBank bank, GraphicsIndex index,
            String tileset) {
        if (tileset == null) {
            return;
        }
        String wanted = "tileset:" + tileset.toLowerCase(java.util.Locale.ROOT);
        for (String[] row : rows()) {
            if (row.length > 4 && row[4].equals(wanted)) {
                apply(bank, index, row);
            }
        }
    }

    private static void apply(SoundBank bank, GraphicsIndex index, String[] row) {
        String name = row[1];
        switch (row[0]) {
            case "file" -> bank.define(name, List.of(row[2]));
            case "group" -> bank.define(name, group(index, row[2]));
            case "pair" -> bank.defineSelection(name, row[2], row[3]);
            // An alias onto a selection pair stays a pair. Flattening it would
            // hand the annoyed lines to a single click, which is the
            // distinction the pair exists to keep.
            case "alias" -> {
                SoundBank.Selection pair = bank.selection(row[2]);
                if (pair != null) {
                    bank.defineSelection(name, pair.normal(), pair.annoyed());
                } else {
                    bank.define(name, bank.pathsFor(row[2]));
                }
            }
            default -> throw new IllegalStateException(
                    "unknown sound binding kind " + row[0] + " for " + name);
        }
    }

    /**
     * Every numbered recording in a directory of the installation, in order.
     *
     * <p>Asked of the index rather than of the filesystem, because a player has
     * a pack rather than a directory tree and the index is what both look like
     * from here.
     */
    private static List<String> group(GraphicsIndex index, String directory) {
        List<String> files = new ArrayList<>();
        for (int variant = 1; variant <= MAX_GROUP; variant++) {
            String path = directory + "/" + variant + ".wav";
            if (index.find(path) == null) {
                break;
            }
            files.add(path);
        }
        return files;
    }

    /** The shipped table, as rows of fields. */
    private static List<String[]> rows() {
        List<String[]> rows = new ArrayList<>();
        try (InputStream in = SoundBindings.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                rows.add(line.split("\t", -1));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rows;
    }
}
