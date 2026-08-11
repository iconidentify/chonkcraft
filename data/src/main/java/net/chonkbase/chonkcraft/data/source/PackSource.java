package net.chonkbase.chonkcraft.data.source;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.chonkbase.assetpack.AssetPack;
import net.chonkbase.assetpack.Json;
import net.chonkbase.assetpack.PackAsset;
import net.chonkbase.assetpack.PackFormatException;
import net.chonkbase.assetpack.PackManifest;
import net.chonkbase.assetpack.codec.Flac;

/**
 * An asset pack, standing in for a Warcraft II installation.
 *
 * <p>The point of this class is how little it does. Every decoder in the
 * project -- the run-length sprite reader, the tileset assembler, the WAV
 * reader, the Smacker player, the map reader -- still receives exactly the
 * bytes it received from the 1995 archives, because {@link EntryCodec} rebuilds
 * an entry from whatever modern form the pack holds it in. So a pack changes
 * where the data comes from and nothing about what happens to it, and the
 * forty or so behaviours that depend on the exact shape of a Warcraft II entry
 * are preserved by not being touched.
 *
 * <p>Small entries are cached after their first read. A palette is a
 * seven-hundred-and-sixty-eight-byte table that gets rebuilt on every sprite
 * lookup, and on the raw path that was a memory read; here it is a zip read and
 * a PNG decode, which is fast but not free at a few hundred times a second.
 * Cutscenes and music are far too big to hold and are not cached.
 */
public final class PackSource implements AssetSource {

    /** Entries up to this size are kept after their first read. */
    private static final int CACHE_LIMIT = 256 * 1024;

    private final AssetPack pack;
    private final PackManifest manifest;
    private final Map<Integer, PackArchive> archives = new LinkedHashMap<>();
    private final Map<Integer, byte[]> cache = new ConcurrentHashMap<>();
    private final Map<String, PackAsset> mapsByName = new LinkedHashMap<>();
    private final List<String> mapNames;
    private final List<MusicTrack> tracks = new ArrayList<>();
    private final List<PackAsset> trackAssets = new ArrayList<>();
    private final List<SupplementalAsset> supplemental = new ArrayList<>();
    private final Map<String, PackAsset> supplementalAssets = new LinkedHashMap<>();

    private PackSource(AssetPack pack) {
        this.pack = pack;
        this.manifest = pack.manifest();
        for (PackManifest.Archive archive : manifest.archives()) {
            archives.put(archive.id(), new PackArchive(archive));
        }
        for (PackAsset map : manifest.maps()) {
            mapsByName.put(map.string("name", lastSegment(map.id())), map);
        }
        this.mapNames = List.copyOf(mapsByName.keySet());
        for (PackManifest.Disc disc : manifest.discs()) {
            for (int index : disc.tracks()) {
                PackAsset asset = manifest.at(index);
                trackAssets.add(asset);
                tracks.add(new MusicTrack(
                        asset.string("name", asset.id()),
                        asset.sampleRate(),
                        asset.channels(),
                        asset.sampleFrames(),
                        asset.string("sourceOrigin", "")));
            }
        }
        for (PackAsset asset : manifest.assets()) {
            String path = asset.string("supplementalPath", "");
            String kind = asset.string("supplementalKind", "");
            if (path.isEmpty() || kind.isEmpty()) {
                continue;
            }
            try {
                SupplementalAsset named = new SupplementalAsset(path,
                        SupplementalAsset.Kind.valueOf(kind.toUpperCase(java.util.Locale.ROOT)));
                supplemental.add(named);
                supplementalAssets.put(path, asset);
            } catch (IllegalArgumentException ignored) {
                // A future supplemental kind is safe for this reader to skip.
            }
        }
    }

    /** Opens a pack as a data source, or fails saying what is wrong with it. */
    public static PackSource open(Path file) {
        return new PackSource(AssetPack.open(file));
    }

    /** Opens a pack as a data source, or returns null when the file is not one. */
    public static PackSource tryOpen(Path file) {
        AssetPack opened = AssetPack.tryOpen(file);
        return opened == null ? null : new PackSource(opened);
    }

    /** Wraps a pack that is already open. Closing this closes it. */
    public static PackSource of(AssetPack pack) {
        return new PackSource(pack);
    }

    /** The pack underneath, for callers that want the manifest. */
    public AssetPack pack() {
        return pack;
    }

    @Override
    public String describe() {
        return "asset pack " + manifest.identity().name() + " (" + pack.source() + ")";
    }

    @Override
    public EntryArchive archive(int archiveId) {
        return archives.get(archiveId);
    }

    // ------------------------------------------------------------- identity

    @Override
    public boolean hasExpansion() {
        return manifest.identity().flag("expansionEntries");
    }

    @Override
    public boolean isExpansionRelease() {
        return manifest.identity().flag("expansionRelease");
    }

    @Override
    public boolean isBattleNetEdition() {
        return manifest.identity().flag("battleNetEdition");
    }

    @Override
    public int campaignTextOffset() {
        return Json.integer(manifest.identity().properties(), "campaignTextOffset", 140);
    }

    // ----------------------------------------------------------------- maps

    @Override
    public List<String> mapNames() {
        return mapNames;
    }

    @Override
    public byte[] map(String name) {
        PackAsset asset = mapsByName.get(name);
        if (asset == null) {
            for (Map.Entry<String, PackAsset> candidate : mapsByName.entrySet()) {
                if (candidate.getKey().equalsIgnoreCase(name)) {
                    asset = candidate.getValue();
                    break;
                }
            }
        }
        return asset == null ? null : pack.bytes(asset);
    }

    // --------------------------------------------------- supplemental files

    @Override
    public List<SupplementalAsset> supplementalAssets() {
        return List.copyOf(supplemental);
    }

    @Override
    public byte[] supplementalAsset(SupplementalAsset asset) {
        if (asset == null) {
            return null;
        }
        PackAsset packed = supplementalAssets.get(asset.path());
        if (packed == null) {
            for (Map.Entry<String, PackAsset> candidate : supplementalAssets.entrySet()) {
                if (candidate.getKey().equalsIgnoreCase(asset.path())) {
                    packed = candidate.getValue();
                    break;
                }
            }
        }
        return packed == null ? null : pack.bytes(packed);
    }

    // -------------------------------------------------------------- red book

    @Override
    public List<MusicTrack> musicTracks() {
        return List.copyOf(tracks);
    }

    @Override
    public short[] musicSamples(int index) {
        if (index < 0 || index >= trackAssets.size()) {
            return new short[0];
        }
        PackAsset asset = trackAssets.get(index);
        // The whole stream, at the rate the recording was made at, whether it is
        // stored as FLAC or as Opus. AssetPack.audio is what makes those two the
        // same call: Opus decodes at 48 kHz and is resampled back to 44,100 on
        // the way out, so a track that came off a disc at 44,100 is served at
        // 44,100 and CdMusic's own resampler runs exactly as it does against a
        // real installation.
        Flac.Pcm pcm = pack.audio(asset);
        int[] samples = pcm.samples();

        // A track can be a window into another one. The two Warcraft II discs
        // carry the same fourteen tracks pressed a fifth of a second apart, so
        // the pack stores the audio once and each disc takes its own slice.
        //
        // The window is taken here, after the whole file has been decoded, and
        // for Opus there is no other place it could be taken. A FLAC stream is
        // sample-addressable -- frames are independent and a seek table says
        // which one holds a given sample -- so a reader that wanted to could
        // decode only the frames a window covers. An Opus stream is not: every
        // packet is predicted from the one before it through the MDCT overlap
        // and the energy envelope, so decoding from the middle produces a burst
        // of noise lasting a frame or two, and the only correct entry point is
        // the start of the stream. That costs nothing in practice, because this
        // class loads whole tracks anyway -- see CdMusic on why music is not
        // streamed -- and the offsets stay in the recording's own frames rather
        // than being converted into the codec's 48 kHz, so a window is the same
        // arithmetic it was before any of this was lossy.
        int channels = Math.max(1, pcm.channels());
        int from = (int) Math.min(samples.length, asset.frameOffset() * channels);
        int wanted = asset.sampleFrames() > 0
                ? (int) Math.min(samples.length - from, asset.sampleFrames() * channels)
                : samples.length - from;

        short[] out = new short[Math.max(0, wanted)];
        for (int i = 0; i < out.length; i++) {
            out[i] = (short) samples[from + i];
        }
        return out;
    }

    @Override
    public void close() {
        cache.clear();
        pack.close();
    }

    private static String lastSegment(String id) {
        int slash = id.lastIndexOf('/');
        return slash < 0 ? id : id.substring(slash + 1);
    }

    /**
     * One archive, rebuilt entry by entry out of the pack.
     *
     * <p>Implements the reading half of {@code OpenArchive} only in the sense that it answers the same
     * questions; there is no archive here, just a table saying which asset
     * stands for which entry number.
     */
    private final class PackArchive implements EntryArchive {

        private final PackManifest.Archive archive;

        PackArchive(PackManifest.Archive archive) {
            this.archive = archive;
        }

        @Override
        public int id() {
            return archive.id();
        }

        @Override
        public int entryCount() {
            return archive.entryCount();
        }

        @Override
        public boolean isValid(int index) {
            return archive.isValid(index);
        }

        @Override
        public byte[] entry(int index) {
            if (index < 0 || index >= archive.entryCount()) {
                throw new IndexOutOfBoundsException(
                        "entry " + index + " of " + archive.entryCount() + " in " + archive.name());
            }
            int slot = archive.slots()[index];
            if (slot < 0) {
                // What wartool substitutes for an entry whose offset or length
                // is out of range: one uncompressed byte of value 1. The font
                // and cursor loaders sniff this and fall through to null, so
                // throwing here would turn a graceful degradation into a crash.
                return new byte[] {0x01};
            }
            byte[] cached = cache.get(slot);
            if (cached != null) {
                return cached;
            }
            PackAsset asset = manifest.at(slot);
            byte[] bytes;
            try {
                bytes = EntryCodec.decode(asset, pack.bytes(asset));
            } catch (RuntimeException e) {
                throw new PackFormatException("cannot rebuild " + asset.id()
                        + " for " + archive.name() + " entry " + index, e);
            }
            if (bytes.length <= CACHE_LIMIT) {
                cache.put(slot, bytes);
            }
            return bytes;
        }
    }
}
