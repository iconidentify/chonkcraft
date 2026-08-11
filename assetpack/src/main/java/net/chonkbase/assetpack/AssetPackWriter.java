package net.chonkbase.assetpack;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a pack.
 *
 * <p>Write the assets, declare the archives and collections that point at
 * them, then {@link #finish}. The manifest is written last because it records
 * every asset's stored size and hash, and those are not known until the bytes
 * are down.
 *
 * <p>Deliberately unopinionated about where assets come from. This class knows
 * nothing about Warcraft II, or about any game: it is handed named bytes with
 * a kind and a codec, and its whole job is to lay them out so that a reader
 * can find them again. Everything game-specific belongs in the tool driving
 * it.
 */
public final class AssetPackWriter implements AutoCloseable {

    private final Path file;
    private final PackManifest.Identity identity;
    private final ZipOutputStream zip;

    private final List<PackAsset> assets = new ArrayList<>();
    private final List<PackManifest.Archive> archives = new ArrayList<>();
    private final List<PackManifest.Disc> discs = new ArrayList<>();
    private final List<Integer> maps = new ArrayList<>();
    private final Set<String> usedFiles = new LinkedHashSet<>();
    private final Map<String, Integer> byId = new LinkedHashMap<>();
    private final List<Long> compressed = new ArrayList<>();

    /** What the pack would have weighed as raw bytes, for the build's report. */
    private long inputBytes;

    private String dictionary = "";
    private boolean finished;

    public AssetPackWriter(Path file, PackManifest.Identity identity) {
        this.file = file;
        this.identity = identity;
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.zip = new ZipOutputStream(Files.newOutputStream(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create pack " + file, e);
        }
    }

    /**
     * Adds one asset and returns its index, which is how archives and
     * collections refer to it.
     *
     * @param id        the name a consumer asks for it by
     * @param kind      what it is
     * @param codec     how {@code payload} is encoded
     * @param path      where to put it in the zip
     * @param payload   the encoded bytes
     * @param sourceBytes what this asset weighed in the source data, for the
     *                  build report only; pass 0 when there is no answer
     * @param meta      everything about it the bytes do not say
     */
    public int add(String id, AssetKind kind, Codec codec, String path, byte[] payload,
            long sourceBytes, Map<String, Object> meta) {
        requireOpen();
        if (byId.containsKey(id)) {
            throw new IllegalArgumentException("an asset called \"" + id + "\" is already in the pack");
        }
        if (!usedFiles.add(path)) {
            throw new IllegalArgumentException("two assets want to be written to " + path);
        }

        try {
            ZipEntry entry = new ZipEntry(path);
            if (codec.selfCompressed()) {
                // Deflating a PNG or a FLAC costs a pass over the data and
                // returns a fraction of a percent. Across nine hundred
                // megabytes of Warcraft II audio that is minutes of build time
                // for kilobytes of pack.
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(payload.length);
                entry.setCompressedSize(payload.length);
                CRC32 crc = new CRC32();
                crc.update(payload);
                entry.setCrc(crc.getValue());
            } else {
                entry.setMethod(ZipEntry.DEFLATED);
            }
            // A fixed timestamp, so that building the same pack twice from the
            // same installation produces the same file. Otherwise every build
            // is a different artefact and nothing downstream can be cached or
            // compared.
            entry.setTime(0);
            zip.putNextEntry(entry);
            zip.write(payload);
            zip.closeEntry();
            // What the asset actually costs in the file, which is not the
            // payload length: a STORE payload is still deflated by the zip.
            // Reporting the payload instead made every raw-stored asset look
            // like it compressed to exactly 100 per cent, which is the sort of
            // number that ends an investigation before it starts.
            long inFile = entry.getCompressedSize();
            compressed.add(inFile > 0 ? inFile : payload.length);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + path + " into " + file, e);
        }

        int index = assets.size();
        assets.add(new PackAsset(index, id, kind, codec, path, payload.length,
                AssetPack.sha256(payload), meta));
        byId.put(id, index);
        inputBytes += sourceBytes;
        return index;
    }

    /**
     * What the asset at {@code index} costs in the file, after the zip's own
     * compression.
     *
     * <p>The number a build report has to use. An asset stored with the
     * {@code store} codec is still deflated by the zip, so its payload length
     * says nothing about what it weighs.
     */
    public long compressedBytes(int index) {
        return index >= 0 && index < compressed.size() ? compressed.get(index) : 0;
    }

    /**
     * Adds an asset that shares another asset's file.
     *
     * <p>For content that appears twice with a different window onto it. The
     * two Warcraft II discs carry the same fourteen music tracks, cut a
     * fifth of a second apart, so storing both costs 358 MB of duplicated
     * audio; this lets the second one be a window into the first with no loss
     * at all.
     *
     * @param sharedIndex the asset whose file this one reads
     */
    public int alias(String id, AssetKind kind, int sharedIndex, long sourceBytes,
            Map<String, Object> meta) {
        requireOpen();
        if (byId.containsKey(id)) {
            throw new IllegalArgumentException("an asset called \"" + id + "\" is already in the pack");
        }
        PackAsset shared = assets.get(sharedIndex);
        Map<String, Object> full = new LinkedHashMap<>(meta);
        full.put("sameAs", shared.id());
        int index = assets.size();
        assets.add(new PackAsset(index, id, kind, shared.codec(), shared.file(),
                shared.storedBytes(), shared.sha256(), full));
        byId.put(id, index);
        compressed.add(0L);
        inputBytes += sourceBytes;
        return index;
    }

    /** The index of an asset already added, or {@code -1}. */
    public int indexOf(String id) {
        Integer index = byId.get(id);
        return index == null ? -1 : index;
    }

    /**
     * Declares a numbered archive.
     *
     * @param slots one entry per original archive slot, holding the index
     *              {@link #add} returned, or {@code -1} for a slot the
     *              original had nothing readable in
     */
    public void archive(int id, String name, int[] slots) {
        requireOpen();
        archives.add(new PackManifest.Archive(id, name, slots.clone()));
    }

    /** Declares a disc's music, in playing order. */
    public void disc(String name, List<Integer> trackIndices) {
        requireOpen();
        discs.add(new PackManifest.Disc(name, trackIndices));
    }

    /** Declares the loose maps, in the order they should be offered. */
    public void mapList(List<Integer> mapIndices) {
        requireOpen();
        maps.clear();
        maps.addAll(mapIndices);
    }

    /** Sets the human-readable data dictionary written alongside the manifest. */
    public void dictionary(String markdown) {
        this.dictionary = markdown == null ? "" : markdown;
    }

    /** Total source bytes reported by the calls to {@link #add}. */
    public long inputBytes() {
        return inputBytes;
    }

    /** Everything added so far. */
    public List<PackAsset> assets() {
        return List.copyOf(assets);
    }

    /** Writes the manifest and closes the file. Returns what was built. */
    public PackManifest finish() {
        requireOpen();
        PackManifest manifest = new PackManifest(identity, assets, archives, discs, maps);
        try {
            if (!dictionary.isEmpty()) {
                writeText(PackManifest.DICTIONARY_ENTRY, dictionary);
            }
            writeText(PackManifest.MANIFEST_ENTRY, manifest.toJson());
            zip.close();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot finish pack " + file, e);
        }
        finished = true;
        return manifest;
    }

    private void writeText(String path, String text) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        entry.setMethod(ZipEntry.DEFLATED);
        entry.setTime(0);
        zip.putNextEntry(entry);
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private void requireOpen() {
        if (finished) {
            throw new IllegalStateException("this pack has already been finished");
        }
    }

    /**
     * Closes without a manifest if {@link #finish} was never reached.
     *
     * <p>A half-written pack is deleted rather than left behind. It would
     * otherwise be a zip with assets in it and no manifest, which
     * {@link AssetPack#open} rejects with a message about the file not being a
     * pack, and someone would spend an afternoon on it.
     */
    @Override
    public void close() {
        if (finished) {
            return;
        }
        try {
            zip.close();
        } catch (IOException e) {
            // Already failing; the delete below is what matters.
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot remove the half-built pack " + file, e);
        }
    }

    /** A convenience for writers that build a payload with a stream. */
    public interface PayloadWriter {
        void writeTo(OutputStream out) throws IOException;
    }
}
