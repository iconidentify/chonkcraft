package net.chonkbase.chonkcraft.data.source;

import java.util.Arrays;
import net.chonkbase.chonkcraft.data.GraphicsIndex;

/**
 * A numbered archive view over Battle.net Edition's named MPQ files.
 *
 * <p>The game still asks for classic archive slots because its conversion
 * table maps, for example, a knight acknowledgement to SFXDAT entry 179.
 * BNE removed SFXDAT and stored the same sound as
 * {@code Gamesfx\knight\knyessr1.wav}. This adapter joins those two published
 * mappings so every existing decoder and sound lookup keeps using the same
 * {@link EntryArchive} interface.
 */
final class BattleNetArchive implements EntryArchive {

    private final int id;
    private final BattleNetAssets source;
    private final EntryArchive base;
    private final AssetSource.SupplementalAsset[] entries;

    private BattleNetArchive(int id, BattleNetAssets source, EntryArchive base,
            AssetSource.SupplementalAsset[] entries) {
        this.id = id;
        this.source = source;
        this.base = base;
        this.entries = entries;
    }

    /**
     * Builds a virtual archive or an overlay on one of BNE's TOME archives.
     *
     * <p>The installer does not merely replace missing SFXDAT and MUDDAT. Its
     * named speech files are 16-bit versions of the 8-bit copies in TOME.3,
     * and its named Blizzard movie can replace the numbered MAINDAT entry.
     * A base therefore remains visible in every slot BNE did not replace,
     * while a named asset wins in the slots it did.
     */
    static BattleNetArchive create(int id, GraphicsIndex index, BattleNetAssets source,
            EntryArchive base) {
        int highest = base == null ? -1 : base.entryCount() - 1;
        for (GraphicsIndex.Asset asset : index.assets()) {
            if (asset.archive() == id) {
                highest = Math.max(highest, entryOf(asset));
            }
        }
        if (highest < 0) {
            return null;
        }

        AssetSource.SupplementalAsset[] entries =
                new AssetSource.SupplementalAsset[highest + 1];
        for (AssetSource.SupplementalAsset extra : source.assets()) {
            GraphicsIndex.Asset mapped = mappingOf(index, extra);
            if (mapped == null || mapped.archive() != id) {
                continue;
            }
            int entry = entryOf(mapped);
            if (entry >= 0 && entry < entries.length && entries[entry] == null) {
                entries[entry] = extra;
            }
        }
        boolean any = Arrays.stream(entries).anyMatch(asset -> asset != null);
        return any ? new BattleNetArchive(id, source, base, entries) : null;
    }

    private static GraphicsIndex.Asset mappingOf(GraphicsIndex index,
            AssetSource.SupplementalAsset asset) {
        String path = stripExtension(asset.path());
        if (asset.kind() == AssetSource.SupplementalAsset.Kind.SOUND
                && path.startsWith("sounds/")) {
            path = path.substring("sounds/".length());
        }
        GraphicsIndex.Asset mapped = index.find(path);
        if (mapped == null) {
            return null;
        }
        return switch (asset.kind()) {
            case SOUND -> mapped.kind() == GraphicsIndex.Kind.SOUND ? mapped : null;
            case VIDEO -> mapped.kind() == GraphicsIndex.Kind.VIDEO ? mapped : null;
            default -> null;
        };
    }

    private static int entryOf(GraphicsIndex.Asset asset) {
        return switch (asset.kind()) {
            case SOUND -> asset.soundEntry();
            case VIDEO -> asset.contentEntry();
            default -> -1;
        };
    }

    private static String stripExtension(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        for (String extension : java.util.List.of(".wav", ".smk")) {
            if (lower.endsWith(extension)) {
                return path.substring(0, path.length() - extension.length());
            }
        }
        return path;
    }

    @Override
    public int id() {
        return id;
    }

    @Override
    public int entryCount() {
        return entries.length;
    }

    @Override
    public boolean isValid(int index) {
        return index >= 0 && index < entries.length
                && (entries[index] != null
                        || (base != null && index < base.entryCount() && base.isValid(index)));
    }

    @Override
    public byte[] entry(int index) {
        if (index < 0 || index >= entries.length) {
            throw new IndexOutOfBoundsException(
                    "entry " + index + " of " + entries.length + " in BNE archive " + id);
        }
        AssetSource.SupplementalAsset asset = entries[index];
        if (asset != null) {
            return source.read(asset);
        }
        return base != null && index < base.entryCount()
                ? base.entry(index)
                : new byte[] {0x01};
    }
}
