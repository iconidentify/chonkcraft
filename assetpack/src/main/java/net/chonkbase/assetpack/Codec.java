package net.chonkbase.assetpack;

/**
 * How an asset's bytes are stored inside the pack.
 *
 * <p>Orthogonal to {@link AssetKind}: the kind says what the thing is, this
 * says what the file is. A sprite is a sprite whether it arrives as a PNG or
 * as a raw index plane, and a consumer that switches on the kind rather than
 * on the codec keeps working when the encoding changes under it.
 *
 * <p>Every codec here is lossless with respect to what the consumer receives.
 * That is a rule of the format, not a coincidence: a pack is the only copy of
 * the art the game will ever see, so an encoder that is allowed to approximate
 * turns a build-time convenience into a permanent loss of quality.
 */
public enum Codec {

    /**
     * The bytes are the asset, unaltered.
     *
     * <p>The zip's own deflate still applies, so this is not the same as
     * "uncompressed". It is the right choice for anything already compressed
     * by its own format, and for anything whose exact bytes are the point.
     */
    STORE("store"),

    /**
     * An 8-bit palette-indexed PNG.
     *
     * <p>The archival form for every picture, chosen because it is the widest
     * format an artist can open, edit and hand back that still records a
     * palette index per pixel rather than a colour. Colour would be a
     * one-way door: team colours and animated water are index arithmetic.
     */
    PNG_INDEXED("png-indexed"),

    /**
     * Opus, in an Ogg container.
     *
     * <p>The one lossy codec the format allows, and the reason the rule above
     * says "except this". Ninety minutes of red book music is 335 MB as FLAC
     * and 50 MB as Opus at 128 kbps, for audio that plays under a game at
     * -12 dB through a mixer that already resamples it 44,100 to 48,000 by
     * linear interpolation -- a cruder operation than Opus transparency.
     *
     * <p>The bitrate is not a property of the codec here but of what is being
     * encoded, and the two the pack uses are four times apart. See
     * docs/asset-pack-format.md: at 128 kbps the game's 8-bit mono sound
     * effects come out <em>larger</em> than they are as FLAC.
     */
    OPUS("opus"),

    /** FLAC. Lossless, so the samples that reach the mixer are the disc's. */
    FLAC("flac"),

    /** A RIFF WAVE file, PCM, for audio not worth compressing. */
    WAV("wav"),

    /** A standard MIDI file. */
    MIDI("midi"),

    /** RAD Game Tools' Smacker, kept as it was authored. */
    SMACKER("smacker");

    private final String id;

    Codec(String id) {
        this.id = id;
    }

    /** The name this codec is written under in a manifest. */
    public String id() {
        return id;
    }

    /**
     * Whether the payload is already compressed.
     *
     * <p>The writer stores these without deflating them again. Deflate on a
     * PNG or a FLAC costs the whole build a pass over the data and gives back
     * a fraction of a percent, and on the 900 MB of audio in a Warcraft II
     * pack that is minutes.
     */
    public boolean selfCompressed() {
        return this == PNG_INDEXED || this == FLAC || this == SMACKER || this == OPUS;
    }

    /**
     * Whether decoding this gives back exactly the samples or pixels that went
     * in.
     *
     * <p>Read by the build's verification pass, which compares a pack against
     * the data it was made from. A lossless asset must match to the bit; a
     * lossy one can only be checked for length, rate and channel count, and
     * the verifier has to know which question to ask. Asking the bit-exact
     * question of an Opus asset would fail every time and asking the loose
     * question of a FLAC asset would pass a corrupt one.
     */
    public boolean lossless() {
        return this != OPUS;
    }

    /** The codec a manifest names, or a failure listing what is valid. */
    public static Codec of(String id) {
        for (Codec codec : values()) {
            if (codec.id.equals(id)) {
                return codec;
            }
        }
        throw new PackFormatException("unknown codec \"" + id + "\"");
    }
}
