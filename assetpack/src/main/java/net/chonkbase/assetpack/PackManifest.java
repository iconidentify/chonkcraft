package net.chonkbase.assetpack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole contents of a pack, as a document.
 *
 * <p>A pack is a zip and this is the file at the top of it. Everything a
 * consumer needs in order to know what is in the pack, and everything an
 * artist needs in order to add to it, is here; the zip below is only storage.
 *
 * <p>There are three ways in, and a pack serves all three because different
 * callers know different things:
 *
 * <ul>
 *   <li>By <b>name</b>, {@code graphics/human/units/footman}. What an artist
 *       and a script use, and the only spelling that survives a change of
 *       game data.</li>
 *   <li>By <b>slot</b>, archive 1000 entry 33. What a port of a 1995 engine
 *       uses, because the original data is numbered rather than named and
 *       thirty places in the engine hold those numbers as constants.</li>
 *   <li>By <b>collection</b>: the discs, in order, and the loose maps. Things
 *       that were never archive entries and have their own identity.</li>
 * </ul>
 *
 * <p>All three point at the same asset. Nothing is stored twice.
 */
public final class PackManifest {

    /** The format this file claims to be. */
    public static final String FORMAT = "chonkpack";

    /**
     * The version this build reads and writes.
     *
     * <p>A pack declaring a higher number is refused rather than read
     * optimistically. A format that silently ignores what it does not
     * understand loses assets quietly, and a missing asset in a game is a
     * blank square rather than an error.
     */
    public static final int VERSION = 1;

    /** Where the manifest lives inside the zip. */
    public static final String MANIFEST_ENTRY = "pack.json";

    /** Where the human-readable data dictionary lives inside the zip. */
    public static final String DICTIONARY_ENTRY = "dictionary.md";

    /** Who made this pack and what of. */
    public record Identity(String id, String name, String source, String builtBy,
            String builtAt, Map<String, Object> properties) {

        public Identity {
            // Insertion order, for the same reason PackAsset keeps it: a
            // manifest has to survive a write, a read and a write unchanged.
            properties = properties == null || properties.isEmpty()
                    ? Map.of()
                    : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        }

        /** A named flag from the pack's properties. */
        public boolean flag(String key) {
            return Json.bool(properties, key, false);
        }
    }

    /**
     * One numbered archive, reproduced slot for slot.
     *
     * <p>The slot table is dense and keeps its holes. Warcraft II's
     * {@code maindat.war} has five entries in the DOS build whose offsets are
     * a byte apart while declaring multi-megabyte lengths; they are junk, and
     * they still occupy indices 28 to 32. Closing the gaps would renumber
     * every entry after them, and the numbers are what the engine holds.
     *
     * <p>The count is load-bearing on its own account: this port decides
     * whether an installation has the expansion by comparing an archive's
     * entry count against 437.
     *
     * @param slots one element per entry, holding an index into the asset
     *              list, or {@code -1} where the original archive had nothing
     *              readable
     */
    public record Archive(int id, String name, int[] slots) {

        /** How many entries the original archive declared. */
        public int entryCount() {
            return slots.length;
        }

        /** Whether entry {@code index} held something. */
        public boolean isValid(int index) {
            return index >= 0 && index < slots.length && slots[index] >= 0;
        }
    }

    /**
     * A disc's music, in the order the disc plays it.
     *
     * <p>Order is the identity. Nothing in a Warcraft II installation names
     * its music: the game asks for "track 3 of whichever disc is in the
     * drive", so a pack that sorts its tracks differently plays different
     * music.
     */
    public record Disc(String name, List<Integer> tracks) {

        public Disc {
            tracks = List.copyOf(tracks);
        }
    }

    private final Identity identity;
    private final List<PackAsset> assets;
    private final List<Archive> archives;
    private final List<Disc> discs;
    private final List<Integer> maps;
    private final Map<String, PackAsset> byId;

    PackManifest(Identity identity, List<PackAsset> assets, List<Archive> archives,
            List<Disc> discs, List<Integer> maps) {
        this.identity = identity;
        this.assets = List.copyOf(assets);
        this.archives = List.copyOf(archives);
        this.discs = List.copyOf(discs);
        this.maps = List.copyOf(maps);
        Map<String, PackAsset> index = new LinkedHashMap<>();
        for (PackAsset asset : this.assets) {
            PackAsset clash = index.putIfAbsent(asset.id(), asset);
            if (clash != null) {
                throw new PackFormatException(
                        "two assets share the id \"" + asset.id() + "\": "
                        + clash.file() + " and " + asset.file());
            }
        }
        this.byId = Map.copyOf(index);
    }

    public Identity identity() {
        return identity;
    }

    /** Every asset, in manifest order. */
    public List<PackAsset> assets() {
        return assets;
    }

    /** The asset with this name, or {@code null}. */
    public PackAsset find(String id) {
        return byId.get(id);
    }

    /** The asset at {@code index} in the asset list. */
    public PackAsset at(int index) {
        return assets.get(index);
    }

    /** Every archive the pack reproduces. */
    public List<Archive> archives() {
        return archives;
    }

    /** The archive with this id, or {@code null} if the pack has no such archive. */
    public Archive archive(int id) {
        for (Archive archive : archives) {
            if (archive.id() == id) {
                return archive;
            }
        }
        return null;
    }

    /** Every disc of recorded music. */
    public List<Disc> discs() {
        return discs;
    }

    /** The loose maps, in the order they were found. */
    public List<PackAsset> maps() {
        List<PackAsset> found = new ArrayList<>(maps.size());
        for (int index : maps) {
            found.add(assets.get(index));
        }
        return found;
    }

    // ------------------------------------------------------------------ json

    /** Renders the manifest. */
    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", FORMAT);
        root.put("formatVersion", (long) VERSION);

        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("id", identity.id());
        pack.put("name", identity.name());
        pack.put("source", identity.source());
        pack.put("builtBy", identity.builtBy());
        pack.put("builtAt", identity.builtAt());
        if (!identity.properties().isEmpty()) {
            pack.put("properties", identity.properties());
        }
        root.put("pack", pack);

        List<Object> archiveList = new ArrayList<>();
        for (Archive archive : archives) {
            Map<String, Object> object = new LinkedHashMap<>();
            object.put("id", (long) archive.id());
            object.put("name", archive.name());
            object.put("entryCount", (long) archive.entryCount());
            List<Object> slots = new ArrayList<>(archive.slots().length);
            for (int slot : archive.slots()) {
                slots.add((long) slot);
            }
            object.put("slots", slots);
            archiveList.add(object);
        }
        root.put("archives", archiveList);

        if (!discs.isEmpty()) {
            List<Object> discList = new ArrayList<>();
            for (Disc disc : discs) {
                Map<String, Object> object = new LinkedHashMap<>();
                object.put("name", disc.name());
                List<Object> tracks = new ArrayList<>();
                for (int track : disc.tracks()) {
                    tracks.add((long) track);
                }
                object.put("tracks", tracks);
                discList.add(object);
            }
            root.put("discs", discList);
        }

        if (!maps.isEmpty()) {
            List<Object> mapList = new ArrayList<>();
            for (int index : maps) {
                mapList.add((long) index);
            }
            root.put("maps", mapList);
        }

        List<Object> assetList = new ArrayList<>(assets.size());
        for (PackAsset asset : assets) {
            assetList.add(asset.toJson());
        }
        root.put("assets", assetList);

        return Json.write(root);
    }

    /** Reads a manifest back, refusing a format this build does not know. */
    public static PackManifest fromJson(String text) {
        Map<String, Object> root = Json.parseObject(text);
        String format = Json.string(root, "format", "");
        if (!FORMAT.equals(format)) {
            throw new PackFormatException(
                    "not a " + FORMAT + " manifest: format is \"" + format + "\"");
        }
        int version = Json.integer(root, "formatVersion", -1);
        if (version > VERSION) {
            throw new PackFormatException("pack is format version " + version
                    + ", this build reads up to " + VERSION);
        }
        if (version < 1) {
            throw new PackFormatException("pack declares no format version");
        }

        Map<String, Object> pack = Json.object(root, "pack");
        Identity identity = new Identity(
                Json.string(pack, "id", "unnamed"),
                Json.string(pack, "name", ""),
                Json.string(pack, "source", ""),
                Json.string(pack, "builtBy", ""),
                Json.string(pack, "builtAt", ""),
                Json.object(pack, "properties"));

        List<PackAsset> assets = new ArrayList<>();
        for (Object element : Json.array(root, "assets")) {
            if (element instanceof Map<?, ?> object) {
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) object;
                assets.add(PackAsset.fromJson(assets.size(), row));
            }
        }

        List<Archive> archives = new ArrayList<>();
        for (Object element : Json.array(root, "archives")) {
            if (!(element instanceof Map<?, ?> object)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) object;
            int[] slots = Json.integers(row, "slots");
            int declared = Json.integer(row, "entryCount", slots.length);
            if (declared != slots.length) {
                throw new PackFormatException("archive " + Json.integer(row, "id", -1)
                        + " declares " + declared + " entries but lists " + slots.length
                        + " slots");
            }
            for (int slot : slots) {
                if (slot >= assets.size()) {
                    throw new PackFormatException("archive " + Json.integer(row, "id", -1)
                            + " points at asset " + slot + ", past the end of the asset list");
                }
            }
            archives.add(new Archive(Json.integer(row, "id", -1),
                    Json.string(row, "name", ""), slots));
        }

        List<Disc> discs = new ArrayList<>();
        for (Object element : Json.array(root, "discs")) {
            if (!(element instanceof Map<?, ?> object)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) object;
            List<Integer> tracks = new ArrayList<>();
            for (int track : Json.integers(row, "tracks")) {
                tracks.add(track);
            }
            discs.add(new Disc(Json.string(row, "name", ""), tracks));
        }

        List<Integer> maps = new ArrayList<>();
        for (int index : Json.integers(root, "maps")) {
            maps.add(index);
        }

        return new PackManifest(identity, assets, archives, discs, maps);
    }
}
