package net.chonkbase.chonkcraft.data;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which archive entry produces which extracted asset.
 *
 * <p>Covers graphics, tilesets and sounds. Sounds come from a different
 * archive from everything else, so {@link Asset#kind()} has to be checked
 * before an entry number is used against one.
 *
 * <p>Warcraft II's archives are numbered, not named: entry 33 is a sprite
 * sheet, but nothing in the file says whose. The mapping lives in the
 * conversion table in ChonkCraft's {@code wartool.h}, and the game scripts refer
 * to assets by the path that table assigns. This is that mapping, generated
 * by {@code tools/extract-asset-index.py} and shipped as a resource.
 *
 * <p>The paths are canonical identifiers, not localized display text. ChonkCraft's
 * conversion table spells some of them with {@code %N} references, but the
 * generator resolves those references through wartool's built-in English
 * identifier table. A Japanese, German or French game's string table must
 * never be allowed to rename the assets the scripts ask for.
 */
public final class GraphicsIndex {

    /** How an entry's pixels are encoded, and what its arguments mean. */
    public enum Kind {
        /** Run-length coded sprite sheet. */
        GFX,
        /** Uncompressed sprite sheet. */
        GFU,
        /** Terrain tileset: palette, megatiles, minitiles, map. */
        TILESET,
        /** A sound; the entry lives in the sound archive, not maindat. */
        SOUND,
        /** A flat image: a 16-bit width and height, then one byte per pixel. */
        IMAGE,
        /** A cursor, stored the same way as an image. */
        CURSOR,
        /** An XMI music track. */
        MUSIC,
        /** A campaign map, stored as a PUD. */
        MAP,
        /** A briefing, credits or victory text. */
        TEXT,
        /** A Smacker cutscene. */
        VIDEO,
        /** A bitmap font. */
        FONT,
        /** A tileset's RGB palette. */
        RGB,
        /** Grouped uncompressed sprites: the interface widgets. */
        WIDGETS,
        /** The campaign title and objectives table. */
        CAMPAIGN_TEXT
    }

    /**
     * One conversion table row.
     *
     * @param kind    which decoder the entry needs
     * @param version 0 for the base game; non-zero restricts it to a release
     * @param archive the id of the archive its entries index into. The
     *                conversion table is stateful: an {@code F} row opens an
     *                archive and everything after it belongs to that one, so
     *                an entry number means nothing without this
     * @param path    the asset path, with {@code %N} already expanded
     * @param palette archive entry holding the palette
     * @param entry   archive entry holding the pixels
     * @param second  for a sprite, a second entry continuing the frame
     *                numbering, or 0; for a tileset, the minitile entry
     * @param fourth  for a sprite, the frame {@code second} starts at; for a
     *                tileset, the map entry
     */
    public record Asset(Kind kind, int version, int archive, String path,
            int palette, int entry, int second, int fourth) {

        /** For a tileset row, the megatile entry. */
        public int megatiles() {
            return entry;
        }

        /** For a tileset row, the minitile entry. */
        public int minitiles() {
            return second;
        }

        /**
         * For a sound row, the archive entry holding the WAV.
         *
         * <p>Arg1, not Arg2. The record names its first argument
         * {@code palette} because that is what it means for the graphic rows
         * that dominate the table, but {@code ConvertWav} takes the entry as
         * its only argument, so for a sound the first slot is the entry
         * itself. Reading {@code entry()} on a sound row silently yields
         * zero, which decodes as whatever happens to be first in the archive.
         */
        public int soundEntry() {
            return palette;
        }

        /**
         * For a music row, the archive entry holding the XMI.
         *
         * <p>Arg1 again, for the same reason a sound's is: {@code ConvertXmi}
         * takes the entry as its only argument.
         */
        public int musicEntry() {
            return palette;
        }

        /**
         * For a campaign map or a text, the archive entry holding it.
         *
         * <p>Arg1 once more. {@code ConvertPud} and {@code ConvertText} take
         * the entry as their first argument, exactly as the sound and music
         * conversions do, and for the same reason the name here is wrong for
         * them: the field is called {@code palette} because that is what Arg1
         * means for the graphics rows that dominate the table.
         */
        public int contentEntry() {
            return palette;
        }
    }

    private static final String RESOURCE = "/chonkcraft/graphics-index.tsv";

    private final List<Asset> assets;
    private final Map<String, Asset> byPath;

    private GraphicsIndex(List<Asset> assets, boolean expansion) {
        this.assets = List.copyOf(assets);
        Map<String, Asset> index = new LinkedHashMap<>();
        for (Asset asset : assets) {
            if (!appliesTo(asset, expansion)) {
                continue;
            }
            // The table lists some paths twice, once per release. First wins,
            // matching wartool, which processes rows in order.
            index.putIfAbsent(asset.path(), asset);
        }

        // Alternative spellings, in later passes so that a path the table
        // states outright always beats one derived from another row. Doing
        // them in one pass let a derived name claim a key a real row was
        // going to use: a text row's "campaigns/human/level01h" was indexed
        // before the map row of that exact name, and every campaign map
        // stopped loading. A spelling that is inferred must never outrank a
        // spelling that is written down.
        //
        // Some rows are written a level above the graphics directory and say
        // so with a leading "../": the act title cards go to
        // "../campaigns/human/interface/Act_I_-_...". That prefix is about
        // where wartool puts the file, not what the thing is called, and the
        // scripts ask for it by the name without it.
        for (Asset asset : assets) {
            if (appliesTo(asset, expansion)) {
                index.putIfAbsent(stripLeadingParents(asset.path()), asset);
            }
        }

        // And, last of all, under the directory the extractor writes it into.
        // A row's path is relative to a folder chosen by its kind -- sounds go
        // to "sounds", music to "music", pictures to "graphics" -- and the
        // scripts sometimes spell that folder and sometimes do not. The table
        // says "human/act" and the campaign says "sounds/human/act.wav"; they
        // are the same file.
        //
        // Last because this spelling invents the most. Both derived names are
        // guesses, but one only drops a marker of where a file was put while
        // the other adds a whole directory, and the two do collide: the ending
        // narration is a sound row reading "../campaigns/human/victory" and the
        // ending prose is a text row reading "human/victory", which becomes
        // "campaigns/human/victory" once its folder is prefixed. Ranked the
        // other way, asking for the recording returned the paragraph.
        for (Asset asset : assets) {
            String folder = appliesTo(asset, expansion) ? folderFor(asset.kind()) : null;
            if (folder != null) {
                index.putIfAbsent(folder + "/" + stripLeadingParents(asset.path()), asset);
            }
        }

        this.byPath = Map.copyOf(index);
    }

    /**
     * Whether a row belongs to the release in hand.
     *
     * <p>{@code wartool} skips two sets of rows, and both matter. A row marked
     * with bit two is on the expansion disc only; a row marked exactly three is
     * the stand-in used when the expansion is absent, and it points at whatever
     * base-game entry is nearest. The swamp tileset's stand-in is the wasteland
     * one, and Beyond the Dark Portal's ending text is the same entry as Tides
     * of Darkness's read from four bytes short of where it starts.
     *
     * <p>Without this the stand-ins won, because they come first in the table
     * and the index took the first spelling of a path it saw. The human
     * campaign's ending read {@code ">0Quiet settles over the Black Morass"}
     * and the orc campaign's read {@code "in."} -- the tail of the sentence
     * before it -- on an installation that has both endings in full.
     */
    private static boolean appliesTo(Asset asset, boolean expansion) {
        if (!expansion && (asset.version() & 2) != 0) {
            return false;
        }
        return !(expansion && asset.version() == 3);
    }

    /**
     * Loads the canonical index, assuming the expansion is present.
     *
     * @deprecated The source string table no longer participates in asset
     *             identity. Use {@link #load(boolean)}.
     */
    @Deprecated
    public static GraphicsIndex load(NameTable names) {
        return load(true);
    }

    /**
     * Loads the canonical index for a release.
     *
     * @deprecated The source string table no longer participates in asset
     *             identity. Use {@link #load(boolean)}.
     *
     * @param names     ignored; retained for source compatibility
     * @param expansion whether this installation has Beyond the Dark Portal
     */
    @Deprecated
    public static GraphicsIndex load(NameTable names, boolean expansion) {
        return load(expansion);
    }

    /**
     * Loads the canonical index for a release.
     *
     * @param expansion whether this installation has Beyond the Dark Portal
     */
    public static GraphicsIndex load(boolean expansion) {
        List<Asset> assets = new ArrayList<>();
        try (InputStream stream = GraphicsIndex.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split("\t");
                if (fields.length < 8) {
                    continue;
                }
                Kind kind = switch (fields[0]) {
                    case "G" -> Kind.GFX;
                    case "U" -> Kind.GFU;
                    case "T" -> Kind.TILESET;
                    case "W" -> Kind.SOUND;
                    case "I" -> Kind.IMAGE;
                    case "C" -> Kind.CURSOR;
                    case "M" -> Kind.MUSIC;
                    case "P" -> Kind.MAP;
                    case "X" -> Kind.TEXT;
                    case "V" -> Kind.VIDEO;
                    case "N" -> Kind.FONT;
                    case "R" -> Kind.RGB;
                    case "D" -> Kind.WIDGETS;
                    case "L" -> Kind.CAMPAIGN_TEXT;
                    default -> null;
                };
                if (kind == null) {
                    continue;
                }
                if (NameTable.hasReference(fields[3])) {
                    throw new IllegalStateException(
                            "unresolved localized identifier in " + RESOURCE + ": " + fields[3]);
                }
                assets.add(new Asset(
                        kind,
                        Integer.parseInt(fields[1]),
                        Integer.parseInt(fields[2]),
                        fields[3],
                        Integer.parseInt(fields[4]),
                        Integer.parseInt(fields[5]),
                        Integer.parseInt(fields[6]),
                        Integer.parseInt(fields[7])));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new GraphicsIndex(assets, expansion);
    }

    /** Every row, in table order. */
    public List<Asset> assets() {
        return assets;
    }

    /**
     * The asset at a path, or {@code null}.
     *
     * <p>A trailing {@code .png} or {@code .wav} is accepted and ignored: the
     * scripts write {@code "human/units/footman.png"} and
     * {@code "ui/click.wav"} while the table stores the stem.
     */
    public Asset find(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        String stem = path.endsWith(".png") || path.endsWith(".wav")
                ? path.substring(0, path.length() - 4)
                : path;
        return byPath.get(stem);
    }

    /** Where wartool writes a row of this kind, or null when it has no folder of its own. */
    private static String folderFor(Kind kind) {
        return switch (kind) {
            case SOUND -> "sounds";
            case MUSIC -> "music";
            case VIDEO -> "videos";
            case TEXT, CAMPAIGN_TEXT -> "campaigns";
            case GFX, GFU, IMAGE, CURSOR, WIDGETS, FONT, RGB, TILESET -> "graphics";
            default -> null;
        };
    }

    /** A path with any leading {@code ../} segments removed. */
    private static String stripLeadingParents(String path) {
        String rest = path;
        while (rest.startsWith("../")) {
            rest = rest.substring(3);
        }
        return rest;
    }

    /** Number of indexed assets. */
    public int size() {
        return assets.size();
    }
}
