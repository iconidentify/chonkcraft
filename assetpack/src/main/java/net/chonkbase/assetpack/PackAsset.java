package net.chonkbase.assetpack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One asset's row in the manifest: what it is, where its bytes are, and
 * everything about it that the bytes themselves do not say.
 *
 * <p>The last part is the reason this class exists rather than a directory of
 * files. A PNG records a width and a palette; it does not record that the
 * sheet is forty-five animation frames of seventy-two pixels laid out five to
 * a row, and a consumer that guesses that from the picture's dimensions gets
 * it wrong the first time an animation has a frame count that is not a
 * multiple of five. Anything a consumer would otherwise have to infer is
 * written down here.
 *
 * <p>{@code meta} is deliberately an open map rather than a sealed hierarchy
 * of per-kind records. A pack is a data format with independent readers and
 * writers, possibly in other languages; a reader must be able to ignore a
 * field it has never heard of, and a writer must be able to add one without
 * every reader needing a new release. The typed accessors below are a
 * convenience over that map, not a schema it is validated against.
 */
public record PackAsset(
        int index,
        String id,
        AssetKind kind,
        Codec codec,
        String file,
        long storedBytes,
        String sha256,
        Map<String, Object> meta) {

    public PackAsset {
        // Insertion order is kept, and Map.copyOf would not keep it. A
        // manifest written, read and written again has to come out identical
        // or nothing downstream can diff two builds, and Map.copyOf hands back
        // an immutable map whose iteration order is derived from the keys'
        // hashes and is not even stable between runs of the same JVM.
        meta = meta == null || meta.isEmpty()
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(meta));
    }

    /** One frame's placement on a sprite sheet, in pixels. */
    public record Frame(int x, int y, int width, int height) {}

    // ------------------------------------------------------------- pictures

    /** Pixel width, for a picture, or 0. */
    public int width() {
        return Json.integer(meta, "width", 0);
    }

    /** Pixel height, for a picture, or 0. */
    public int height() {
        return Json.integer(meta, "height", 0);
    }

    /**
     * The palette index that is a hole rather than a colour, or {@code -1}.
     *
     * <p>Written down rather than assumed because it is not the same index for
     * every picture even within one game: a sprite's holes are one value and a
     * pointer's are another, and a terrain tile has none at all, so a pack that
     * hard-codes one value punches holes in the ground.
     */
    public int transparentIndex() {
        return Json.integer(meta, "transparentIndex", -1);
    }

    /** Where the point of a pointer is, or {@code null}. */
    public int[] hotspot() {
        int[] point = Json.integers(meta, "hotspot");
        return point.length == 2 ? point : null;
    }

    /** The frames of a sprite sheet, in animation order, possibly empty. */
    public List<Frame> frames() {
        List<Object> raw = Json.array(meta, "frames");
        List<Frame> frames = new ArrayList<>(raw.size());
        for (Object element : raw) {
            if (element instanceof List<?> quad && quad.size() == 4) {
                frames.add(new Frame(
                        intOf(quad.get(0)), intOf(quad.get(1)),
                        intOf(quad.get(2)), intOf(quad.get(3))));
            }
        }
        return frames;
    }

    private static int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    // ---------------------------------------------------------------- audio

    /** Samples a second, for audio, or 0. */
    public int sampleRate() {
        return Json.integer(meta, "sampleRate", 0);
    }

    /** Channel count, for audio, or 0. */
    public int channels() {
        return Json.integer(meta, "channels", 0);
    }

    /** Bits per sample, for audio, or 0. */
    public int bitsPerSample() {
        return Json.integer(meta, "bitsPerSample", 0);
    }

    /**
     * Frames of audio, meaning samples per channel.
     *
     * <p>Recorded because a red book track has no length of its own: it is a
     * run of raw sectors, and where it stops is written in the cue sheet
     * rather than in the audio. A pack that loses the count plays the
     * beginning of the next track over the end of this one.
     */
    public long sampleFrames() {
        return Json.longValue(meta, "sampleFrames", 0);
    }

    /**
     * The asset this one shares a file with, or {@code null}.
     *
     * <p>Two assets can be the same content seen through different windows.
     * Warcraft II's two discs carry the same fourteen music tracks pressed a
     * fifth of a second apart, which is 358 MB of duplicated audio if each is
     * stored whole. An alias reads the other's file and takes
     * {@link #sampleFrames} frames starting at {@link #frameOffset}.
     */
    public String sameAs() {
        return Json.string(meta, "sameAs", null);
    }

    /** Where this asset begins inside the one it shares, in frames. */
    public long frameOffset() {
        return Json.longValue(meta, "frameOffset", 0);
    }

    // ---------------------------------------------------------------- other

    /** A named string from the metadata, or {@code fallback}. */
    public String string(String key, String fallback) {
        return Json.string(meta, key, fallback);
    }

    /** A named integer from the metadata, or {@code fallback}. */
    public int integer(String key, int fallback) {
        return Json.integer(meta, key, fallback);
    }

    /** Renders this row as the JSON the manifest holds. */
    Map<String, Object> toJson() {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("id", id);
        object.put("kind", kind.id());
        object.put("codec", codec.id());
        object.put("file", file);
        object.put("bytes", storedBytes);
        object.put("sha256", sha256);
        if (!meta.isEmpty()) {
            object.put("meta", meta);
        }
        return object;
    }

    /** Reads one row back. */
    static PackAsset fromJson(int index, Map<String, Object> object) {
        return new PackAsset(
                index,
                Json.required(object, "id"),
                AssetKind.of(Json.required(object, "kind")),
                Codec.of(Json.required(object, "codec")),
                Json.required(object, "file"),
                Json.longValue(object, "bytes", 0),
                Json.string(object, "sha256", ""),
                Json.object(object, "meta"));
    }
}
