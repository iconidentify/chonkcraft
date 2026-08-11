package net.chonkbase.assetpack;

/**
 * What an asset is, as distinct from how it is stored.
 *
 * <p>The kind decides which accessor a consumer reaches for and which fields
 * of the manifest entry are meaningful; the {@link Codec} decides only how the
 * bytes in the zip turn back into that thing. Keeping the two apart is what
 * lets a pack change from raw pixels to PNG, or from PCM to FLAC, without any
 * consumer noticing.
 *
 * <p>This is the vocabulary an artist authors against. A pack for another game
 * uses the same kinds; nothing here is specific to Warcraft II.
 */
public enum AssetKind {

    /**
     * A sheet of animation frames, laid out on a grid, in palette indices.
     *
     * <p>Carries {@code frames}, the per-frame placement, because a sheet is
     * not self-describing: where one frame ends and the next begins is a fact
     * about the animation, not about the picture.
     */
    SPRITE("sprite"),

    /** A single picture in palette indices, with no frame structure. */
    IMAGE("image"),

    /** A picture with a hotspot: where the point of the pointer is. */
    CURSOR("cursor"),

    /** A bitmap font: a sheet of glyphs plus the width each one draws at. */
    FONT("font"),

    /** A sheet several named pieces are cut out of at recorded rectangles. */
    WIDGETS("widgets"),

    /** A 256-entry colour table, 768 bytes of red, green and blue. */
    PALETTE("palette"),

    /** A grid of small blocks that larger tiles are assembled from. */
    TILE_ATLAS("tile-atlas"),

    /** The table saying which blocks, in which orientation, compose each tile. */
    TILE_TABLE("tile-table"),

    /** A short sound: speech, an effect, a click. */
    SOUND("sound"),

    /** A recorded music track, long enough to be worth streaming if anything is. */
    MUSIC("music"),

    /** A sequenced music track: events for a synthesiser, not samples. */
    SEQUENCE("sequence"),

    /** A cutscene. */
    VIDEO("video"),

    /** A playable map. */
    MAP("map"),

    /** Prose: a briefing, an objective list, the credits. */
    TEXT("text"),

    /**
     * Bytes with a structure the format does not model.
     *
     * <p>Every pack has some. The alternative to admitting that is a format
     * that quietly drops whatever it has no name for, which is how an asset
     * goes missing without anything failing.
     */
    BINARY("binary");

    private final String id;

    AssetKind(String id) {
        this.id = id;
    }

    /** The name this kind is written under in a manifest. */
    public String id() {
        return id;
    }

    /** The kind a manifest names, or a failure listing what is valid. */
    public static AssetKind of(String id) {
        for (AssetKind kind : values()) {
            if (kind.id.equals(id)) {
                return kind;
            }
        }
        throw new PackFormatException("unknown asset kind \"" + id + "\"");
    }
}
