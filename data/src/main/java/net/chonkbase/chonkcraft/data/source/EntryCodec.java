package net.chonkbase.chonkcraft.data.source;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.chonkbase.assetpack.AssetKind;
import net.chonkbase.assetpack.Codec;
import net.chonkbase.assetpack.PackAsset;
import net.chonkbase.assetpack.codec.Flac;
import net.chonkbase.assetpack.codec.IndexedPng;
import net.chonkbase.assetpack.codec.Opus;
import net.chonkbase.assetpack.codec.SignalToNoise;
import net.chonkbase.assetpack.codec.Wav;
import net.chonkbase.chonkcraft.data.graphic.CursorDecoder;
import net.chonkbase.chonkcraft.data.graphic.GraphicDecoder;
import net.chonkbase.chonkcraft.data.graphic.GraphicEncoder;
import net.chonkbase.chonkcraft.data.graphic.ImageDecoder;
import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.data.graphic.Palette;

/**
 * Turns one Warcraft II archive entry into a pack asset, and back again.
 *
 * <p>This is the only place in the project that knows both halves, and it is
 * where the whole design either holds or falls over. The game reads its data
 * through decoders written against the 1995 formats, so a pack that wants to
 * stand in for an installation has to be able to hand those decoders an entry
 * they accept. But an entry is run-length coded pixels against a per-row
 * offset table, which no artist can produce. So the pack stores a PNG, and
 * this rebuilds the entry from it on the way out.
 *
 * <p><b>The guarantee is decode-identity, not byte-identity.</b> A rebuilt
 * entry is not the 1995 file: the run-length coder makes choices and this one
 * makes different ones. What must hold, and what is checked here for every
 * asset at build time rather than asserted, is that the game's own decoder
 * produces the same pixels from the rebuilt entry as it did from the original.
 * Anything that fails that check is stored as raw bytes instead, so the pack
 * is correct by construction and only ever less convenient, never wrong.
 *
 * <p>The fallback is not hypothetical and not a bug when it happens. Some
 * entries in a shipped archive are junk, some declare zero frames, and some
 * sounds are not RIFF files at all; the implementation already tolerates all three, and
 * a pack has to tolerate them the same way rather than tidying them out of
 * existence.
 *
 * <p><b>Sound is the one exception to decode-identity, and it is bounded.</b> A
 * sound may be stored as Opus, which does not give the samples back; see
 * {@link AudioTarget} for when that is allowed and {@link #compareSound} for
 * what is required of it instead. Everything structural about the entry still
 * comes back exactly -- the rate, the channel count, the bit depth and the
 * frame count -- so a lossy sound is the same file to every consumer that is
 * not listening to it. See {@link #soundBytes} for why that is the rule.
 */
public final class EntryCodec {

    /** How an entry is carried in a pack. */
    public enum Form {
        /** A run-length coded sprite sheet, as an indexed PNG. */
        SPRITE_GFX("sprite-gfx"),
        /** An uncompressed sprite sheet, as an indexed PNG. */
        SPRITE_GFU("sprite-gfu"),
        /** A flat picture, as an indexed PNG. */
        IMAGE("image"),
        /** A pointer, as an indexed PNG plus its hotspot. */
        CURSOR("cursor"),
        /** A 768-byte colour table, as a 16 by 16 indexed PNG. */
        PALETTE("palette"),
        /** A run of 8 by 8 terrain blocks, as an indexed PNG laid out 32 to a row. */
        TILE_ATLAS("tile-atlas"),
        /** A RIFF WAVE, as FLAC or as Opus. */
        SOUND("sound"),
        /** The bytes, unaltered. */
        RAW("raw");

        private final String id;

        Form(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /** The form a manifest names, or {@link #RAW} for anything unknown. */
        public static Form of(String id) {
            for (Form form : values()) {
                if (form.id.equals(id)) {
                    return form;
                }
            }
            return RAW;
        }
    }

    /** How many 8 by 8 terrain blocks go across a tile atlas. */
    private static final int ATLAS_BLOCKS_PER_ROW = 32;

    /** The edge of a terrain block, in pixels. */
    private static final int BLOCK = 8;

    /** Bytes in one terrain block. */
    private static final int BLOCK_BYTES = BLOCK * BLOCK;

    /**
     * What a sampled entry is allowed to cost.
     *
     * <p>Every other form in this class has one right answer, because every
     * other codec the pack holds is lossless and a lossless encoder has nothing
     * to decide. Audio is the exception the format admits, and the size of the
     * exception is a number: 64 kb/s for a sound effect and 144 kb/s for a
     * recorded track, four times apart, both settled by measurement rather than
     * by taste. See docs/asset-pack-format.md.
     *
     * <p>It is a parameter rather than a constant here because this class does
     * not know what it is looking at. It is handed an archive entry and a form;
     * whether that entry is a footman's acknowledgement or a menu click or a
     * piece of speech is a fact the caller has from the conversion table, and a
     * default chosen here would be that caller's decision made in the wrong
     * place. {@link #LOSSLESS} is the default, so anything that does not ask
     * stays exact.
     */
    public record AudioTarget(int opusBitrateBps) {

        /** Keep every sample: FLAC, or a bare WAV where FLAC costs more. */
        public static final AudioTarget LOSSLESS = new AudioTarget(0);

        public AudioTarget {
            if (opusBitrateBps < 0) {
                throw new IllegalArgumentException("a bitrate cannot be " + opusBitrateBps);
            }
        }

        /** Opus at a chosen rate, falling back to lossless where it does not pay. */
        public static AudioTarget opus(int bitrateBps) {
            if (bitrateBps <= 0) {
                throw new IllegalArgumentException(
                        "Opus needs a positive bitrate, not " + bitrateBps);
            }
            return new AudioTarget(bitrateBps);
        }

        /** Whether this permits a lossy encoding at all. */
        public boolean lossy() {
            return opusBitrateBps > 0;
        }
    }

    /** An entry ready to go into a pack. */
    public record Encoded(AssetKind kind, Codec codec, byte[] payload,
            Map<String, Object> meta, Form form) {

        /** Whether the conversion this asked for actually held. */
        public boolean converted() {
            return form != Form.RAW;
        }
    }

    private EntryCodec() {
    }

    /**
     * Converts an entry losslessly, falling back to raw bytes when the
     * conversion does not round-trip.
     *
     * @param form      the conversion to attempt
     * @param entry     the decompressed archive entry
     * @param palette768 the palette the asset is drawn through, used only so
     *                  that the stored PNG looks right in a paint program;
     *                  the pixels are indices and the game supplies its own.
     *                  May be null.
     * @param fallbackKind what to call the asset if the conversion is refused
     */
    public static Encoded encode(Form form, byte[] entry, byte[] palette768,
            AssetKind fallbackKind) {
        return encode(form, entry, palette768, fallbackKind, AudioTarget.LOSSLESS);
    }

    /**
     * Converts an entry, with a say in what its audio may cost.
     *
     * <p>{@code audio} is consulted for {@link Form#SOUND} and ignored
     * everywhere else, because nothing else in a Warcraft II archive is
     * sampled.
     */
    public static Encoded encode(Form form, byte[] entry, byte[] palette768,
            AssetKind fallbackKind, AudioTarget audio) {
        try {
            Encoded encoded = switch (form) {
                case SPRITE_GFX -> sprite(GraphicDecoder.Kind.GFX, entry, palette768);
                case SPRITE_GFU -> sprite(GraphicDecoder.Kind.GFU, entry, palette768);
                case IMAGE -> image(entry, palette768);
                case CURSOR -> cursor(entry, palette768);
                case PALETTE -> palette(entry);
                case TILE_ATLAS -> tileAtlas(entry, palette768);
                case SOUND -> sound(entry, audio);
                case RAW -> null;
            };
            if (encoded == null) {
                return raw(entry, fallbackKind);
            }
            // The check that makes the format safe. Rebuild the entry from
            // what would go in the pack and confirm the game's own decoder
            // still sees the same thing. A conversion that cannot prove itself
            // does not get used.
            //
            // A lossy conversion cannot prove itself that way and is held to
            // compareSound instead: same rate, same channels, same depth, same
            // frame count, and SignalToNoise.SOUND_FLOOR_DB. Where it misses,
            // this falls back to a lossless encoding of the same entry rather
            // than to the raw bytes, because the conversion is not what failed
            // -- the bitrate was.
            byte[] rebuilt = decode(encoded.form(), encoded.codec(), encoded.meta(),
                    encoded.payload());
            if (encoded.codec() == Codec.OPUS) {
                if (!soundSurvived(entry, rebuilt)) {
                    return encode(form, entry, palette768, fallbackKind, AudioTarget.LOSSLESS);
                }
                return encoded;
            }
            if (!sameToTheDecoder(form, entry, rebuilt)) {
                return raw(entry, fallbackKind);
            }
            return encoded;
        } catch (RuntimeException e) {
            return raw(entry, fallbackKind);
        }
    }

    /** Rebuilds the archive entry an asset stands for. */
    public static byte[] decode(PackAsset asset, byte[] payload) {
        return decode(Form.of(asset.string("form", "raw")), asset.codec(), asset.meta(), payload);
    }

    /**
     * Rebuilds an entry from a form, the codec its payload is in, its metadata
     * and its payload.
     *
     * <p>The codec is a parameter and not a guess. Two codecs now share
     * {@link Form#SOUND}, and sniffing the payload for {@code fLaC} or
     * {@code OggS} would work today and would be a decision made from the bytes
     * rather than from the manifest, which is the shape of bug this whole class
     * exists to avoid.
     */
    public static byte[] decode(Form form, Codec codec, Map<String, Object> meta, byte[] payload) {
        return switch (form) {
            case SPRITE_GFX -> spriteBytes(GraphicDecoder.Kind.GFX, meta, payload);
            case SPRITE_GFU -> spriteBytes(GraphicDecoder.Kind.GFU, meta, payload);
            case IMAGE -> imageBytes(payload);
            case CURSOR -> cursorBytes(meta, payload);
            case PALETTE -> paletteBytes(payload);
            case TILE_ATLAS -> tileAtlasBytes(meta, payload);
            case SOUND -> soundBytes(codec, meta, payload);
            case RAW -> payload;
        };
    }

    // -------------------------------------------------------------- sprites

    private static Encoded sprite(GraphicDecoder.Kind kind, byte[] entry, byte[] palette768) {
        IndexedImage sheet = GraphicDecoder.decode(kind, entry);
        List<GraphicEncoder.Frame> frames = GraphicEncoder.frames(entry);
        int[] header = GraphicEncoder.header(entry);

        List<Object> frameJson = new ArrayList<>(frames.size());
        for (GraphicEncoder.Frame frame : frames) {
            frameJson.add(List.of((long) frame.xOffset(), (long) frame.yOffset(),
                    (long) frame.width(), (long) frame.height()));
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("form", kind == GraphicDecoder.Kind.GFX ? Form.SPRITE_GFX.id() : Form.SPRITE_GFU.id());
        meta.put("width", (long) sheet.width());
        meta.put("height", (long) sheet.height());
        meta.put("transparentIndex", (long) Palette.TRANSPARENT_INDEX);
        meta.put("encoding", kind == GraphicDecoder.Kind.GFX ? "gfx" : "gfu");
        meta.put("frameCount", (long) header[0]);
        meta.put("cellWidth", (long) header[1]);
        meta.put("cellHeight", (long) header[2]);
        meta.put("frames", frameJson);

        byte[] png = IndexedPng.encode(sheet.width(), sheet.height(), sheet.pixels(),
                displayPalette(palette768), Palette.TRANSPARENT_INDEX);
        return new Encoded(AssetKind.SPRITE, Codec.PNG_INDEXED, png, meta,
                kind == GraphicDecoder.Kind.GFX ? Form.SPRITE_GFX : Form.SPRITE_GFU);
    }

    private static byte[] spriteBytes(GraphicDecoder.Kind kind, Map<String, Object> meta,
            byte[] payload) {
        IndexedPng.Image picture = IndexedPng.decode(payload);
        IndexedImage sheet = new IndexedImage(picture.width(), picture.height(),
                picture.indices());
        List<GraphicEncoder.Frame> frames = new ArrayList<>();
        for (Object element : net.chonkbase.assetpack.Json.array(meta, "frames")) {
            if (element instanceof List<?> quad && quad.size() == 4) {
                frames.add(new GraphicEncoder.Frame(number(quad.get(0)), number(quad.get(1)),
                        number(quad.get(2)), number(quad.get(3))));
            }
        }
        return GraphicEncoder.encode(kind, sheet, frames,
                net.chonkbase.assetpack.Json.integer(meta, "cellWidth", 0),
                net.chonkbase.assetpack.Json.integer(meta, "cellHeight", 0));
    }

    // -------------------------------------------------------------- pictures

    private static Encoded image(byte[] entry, byte[] palette768) {
        IndexedImage picture = ImageDecoder.decode(entry);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("form", Form.IMAGE.id());
        meta.put("width", (long) picture.width());
        meta.put("height", (long) picture.height());
        // Not transparent. Index 0 is black here and a hole in a sprite, which
        // is the same byte meaning two things in one game.
        meta.put("transparentIndex", -1L);
        byte[] png = IndexedPng.encode(picture.width(), picture.height(), picture.pixels(),
                displayPalette(palette768), -1);
        return new Encoded(AssetKind.IMAGE, Codec.PNG_INDEXED, png, meta, Form.IMAGE);
    }

    private static byte[] imageBytes(byte[] payload) {
        IndexedPng.Image picture = IndexedPng.decode(payload);
        byte[] entry = new byte[4 + picture.indices().length];
        writeLe16(entry, 0, picture.width());
        writeLe16(entry, 2, picture.height());
        System.arraycopy(picture.indices(), 0, entry, 4, picture.indices().length);
        return entry;
    }

    private static Encoded cursor(byte[] entry, byte[] palette768) {
        if (!CursorDecoder.looksLikeCursor(entry)) {
            return null;
        }
        CursorDecoder.Cursor pointer = CursorDecoder.decode(entry);
        IndexedImage picture = pointer.image();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("form", Form.CURSOR.id());
        meta.put("width", (long) picture.width());
        meta.put("height", (long) picture.height());
        meta.put("transparentIndex", (long) Palette.TRANSPARENT_INDEX);
        meta.put("hotspot", List.of((long) pointer.hotspotX(), (long) pointer.hotspotY()));
        byte[] png = IndexedPng.encode(picture.width(), picture.height(), picture.pixels(),
                displayPalette(palette768), Palette.TRANSPARENT_INDEX);
        return new Encoded(AssetKind.CURSOR, Codec.PNG_INDEXED, png, meta, Form.CURSOR);
    }

    private static byte[] cursorBytes(Map<String, Object> meta, byte[] payload) {
        IndexedPng.Image picture = IndexedPng.decode(payload);
        int[] hotspot = net.chonkbase.assetpack.Json.integers(meta, "hotspot");
        byte[] entry = new byte[8 + picture.indices().length];
        writeLe16(entry, 0, hotspot.length > 0 ? hotspot[0] : 0);
        writeLe16(entry, 2, hotspot.length > 1 ? hotspot[1] : 0);
        writeLe16(entry, 4, picture.width());
        writeLe16(entry, 6, picture.height());
        byte[] pixels = picture.indices();
        for (int i = 0; i < pixels.length; i++) {
            // The decoder moves index 0 to 255 because 0 is the hole in a
            // cursor. Moving it back is exact even where the original really
            // held 255: the decoder would have left that as 255 too, so both
            // spellings decode the same.
            int index = pixels[i] & 0xFF;
            entry[8 + i] = (byte) (index == Palette.TRANSPARENT_INDEX ? 0 : index);
        }
        return entry;
    }

    // -------------------------------------------------------------- palettes

    private static Encoded palette(byte[] entry) {
        if (entry.length != 768) {
            return null;
        }
        // Sixteen by sixteen, one pixel per entry, each pixel its own index.
        // The picture is the palette: opening it in a paint program shows the
        // colours in the order the game holds them, and editing a swatch edits
        // the palette.
        byte[] indices = new byte[256];
        for (int i = 0; i < 256; i++) {
            indices[i] = (byte) i;
        }
        byte[] shown = new byte[768];
        for (int i = 0; i < 768; i++) {
            // Warcraft II stores 6-bit VGA components and the implementation scales them
            // by a left shift of two, so 0x3F becomes 0xFC rather than 0xFF.
            // Storing the scaled value keeps the swatches the right colour and
            // still shifts back exactly, because the value never exceeded 63.
            shown[i] = (byte) ((entry[i] & 0x3F) << 2);
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("form", Form.PALETTE.id());
        meta.put("width", 16L);
        meta.put("height", 16L);
        meta.put("transparentIndex", -1L);
        byte[] png = IndexedPng.encode(16, 16, indices, shown, -1);
        return new Encoded(AssetKind.PALETTE, Codec.PNG_INDEXED, png, meta, Form.PALETTE);
    }

    private static byte[] paletteBytes(byte[] payload) {
        IndexedPng.Image picture = IndexedPng.decode(payload);
        byte[] entry = new byte[768];
        byte[] shown = picture.palette768();
        for (int i = 0; i < 768; i++) {
            entry[i] = (byte) ((shown[i] & 0xFF) >> 2);
        }
        return entry;
    }

    // ----------------------------------------------------------- tile atlas

    private static Encoded tileAtlas(byte[] entry, byte[] palette768) {
        if (entry.length < BLOCK_BYTES) {
            return null;
        }
        int blocks = (entry.length + BLOCK_BYTES - 1) / BLOCK_BYTES;
        int rows = (blocks + ATLAS_BLOCKS_PER_ROW - 1) / ATLAS_BLOCKS_PER_ROW;
        int width = ATLAS_BLOCKS_PER_ROW * BLOCK;
        int height = rows * BLOCK;
        byte[] pixels = new byte[width * height];
        for (int block = 0; block < blocks; block++) {
            int originX = (block % ATLAS_BLOCKS_PER_ROW) * BLOCK;
            int originY = (block / ATLAS_BLOCKS_PER_ROW) * BLOCK;
            for (int y = 0; y < BLOCK; y++) {
                for (int x = 0; x < BLOCK; x++) {
                    int at = block * BLOCK_BYTES + y * BLOCK + x;
                    pixels[(originY + y) * width + originX + x] =
                            at < entry.length ? entry[at] : 0;
                }
            }
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("form", Form.TILE_ATLAS.id());
        meta.put("width", (long) width);
        meta.put("height", (long) height);
        meta.put("transparentIndex", -1L);
        meta.put("blockSize", (long) BLOCK);
        meta.put("blocksPerRow", (long) ATLAS_BLOCKS_PER_ROW);
        // The atlas is a whole number of blocks and the entry is not, so the
        // real length has to be written down or the tail of the last block
        // reappears as terrain.
        meta.put("length", (long) entry.length);
        byte[] png = IndexedPng.encode(width, height, pixels, displayPalette(palette768), -1);
        return new Encoded(AssetKind.TILE_ATLAS, Codec.PNG_INDEXED, png, meta, Form.TILE_ATLAS);
    }

    private static byte[] tileAtlasBytes(Map<String, Object> meta, byte[] payload) {
        IndexedPng.Image picture = IndexedPng.decode(payload);
        int length = net.chonkbase.assetpack.Json.integer(meta, "length", 0);
        byte[] entry = new byte[length];
        int width = picture.width();
        byte[] pixels = picture.indices();
        int blocks = (length + BLOCK_BYTES - 1) / BLOCK_BYTES;
        for (int block = 0; block < blocks; block++) {
            int originX = (block % ATLAS_BLOCKS_PER_ROW) * BLOCK;
            int originY = (block / ATLAS_BLOCKS_PER_ROW) * BLOCK;
            for (int y = 0; y < BLOCK; y++) {
                for (int x = 0; x < BLOCK; x++) {
                    int at = block * BLOCK_BYTES + y * BLOCK + x;
                    if (at < length) {
                        entry[at] = pixels[(originY + y) * width + originX + x];
                    }
                }
            }
        }
        return entry;
    }

    // ----------------------------------------------------------------- sound

    private static Encoded sound(byte[] entry, AudioTarget audio) {
        Flac.Pcm pcm = Wav.decode(entry);
        byte[] flac = Flac.encode(pcm);
        byte[] wav = Wav.encode(pcm);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("form", Form.SOUND.id());
        // Everything a reader needs to put the 1995 file back together, in the
        // 1995 file's own terms. None of it is derivable from an Opus stream:
        // Opus decodes at 48 kHz whatever went in, it has no notion of a bit
        // depth at all, and its frame count is a count of 48 kHz samples.
        meta.put("sampleRate", (long) pcm.sampleRate());
        meta.put("channels", (long) pcm.channels());
        meta.put("bitsPerSample", (long) pcm.bitsPerSample());
        meta.put("sampleFrames", (long) pcm.frameCount());

        if (audio.lossy() && pcm.frameCount() > 0) {
            // Refused rather than thrown. Opus takes one or two channels at
            // 8000 to 192000 Hz, and a shipped archive is entitled to hold a
            // sound outside that -- three channels, or 4 kHz, or something that
            // is not audio wearing a RIFF header. Letting that reach the outer
            // handler would store the entry as raw bytes and lose the lossless
            // compression it could still have had, which is a worse outcome
            // than not being offered Opus.
            byte[] opus;
            try {
                opus = Opus.encode(pcm, audio.opusBitrateBps());
            } catch (RuntimeException e) {
                opus = null;
            }
            // Smaller than both lossless forms or it is not worth having. A
            // half-second click is four hundred bytes of FLAC and, at 64 kb/s in
            // 20 ms frames, four kilobytes of Opus plus two pages of container:
            // the pack's own measurements put Opus ahead over the sound bank as
            // a whole and behind on its shortest members, and paying bytes for
            // loss on those would be the worst of both.
            if (opus != null && opus.length < Math.min(flac.length, wav.length)) {
                meta.put("decodeSampleRate", (long) Opus.CODEC_RATE);
                meta.put("bitrateBps", (long) audio.opusBitrateBps());
                return new Encoded(AssetKind.SOUND, Codec.OPUS, opus, meta, Form.SOUND);
            }
        }

        // A two-kilobyte click has more FLAC header than content. Taking
        // whichever is smaller costs nothing and stops the pack growing on
        // the four hundred shortest sounds in the game.
        if (flac.length <= wav.length) {
            return new Encoded(AssetKind.SOUND, Codec.FLAC, flac, meta, Form.SOUND);
        }
        meta.put("form", Form.RAW.id());
        return new Encoded(AssetKind.SOUND, Codec.WAV, wav, meta, Form.RAW);
    }

    /**
     * Rebuilds the RIFF file the archive held.
     *
     * <p><b>At the rate the archive held it at, not at 48 kHz.</b> This is the
     * one decision in the Opus work that changes behaviour if it is made the
     * other way, so it is written down here rather than in a commit message.
     *
     * <p>Opus only ever decodes at 48 kHz -- there is no field in the bitstream
     * that says otherwise -- and Warcraft II's effects are 8-bit mono at 11,025
     * and 22,050 Hz. So a rebuilt entry is either resampled back down to what
     * the game shipped, or it is handed over at 48 kHz and the sample count
     * changes by a factor of 4.35. The second is tempting: the engine's
     * {@code LegacyWavDecoder} resamples every sound to the mixer's 48 kHz by
     * linear interpolation, and giving it audio already at that rate would make
     * that step a no-op and replace a crude resample with the windowed sinc the
     * Opus encoder used on the way in. One resample instead of two, measurably
     * better audio.
     *
     * <p>It is still the wrong answer here, for a reason that has nothing to do
     * with audio quality. An archive entry is not a file this format owns; it is
     * the input to forty decoders written against the 1995 data, and a pack's
     * whole claim is that it can stand in for an installation without anything
     * downstream noticing. Rebuilding at 48 kHz would mean the implementation sounded
     * different depending on where its data came from -- the linear resample is
     * part of how this game sounds, mistake or not -- and it would change every
     * length that follows from the sample count. The rule the pack keeps instead
     * is one sentence long: <b>Opus's 48 kHz is a storage detail, and every
     * interface the pack offers stays at the source's own rate.</b> The cost is
     * a third resample, at load, on a signal already band-limited well below the
     * rate it is going back to, which is the cheapest of the three places this
     * could have been paid.
     *
     * <p>The bit depth is preserved for the same reason and is worth stating
     * separately: an 8-bit entry is rebuilt 8-bit, quantised back onto the
     * 256-value grid it came off. That is not free -- it is a second
     * quantisation on top of Opus's own error -- but a 16-bit rebuild would be
     * twice the size of the entry it replaces, would take the cache in
     * {@link PackSource} with it, and would hand the game a file no version of
     * it ever shipped.
     */
    private static byte[] soundBytes(Codec codec, Map<String, Object> meta, byte[] payload) {
        if (codec != Codec.OPUS) {
            return Wav.encode(Flac.decode(payload));
        }
        // The rate and the channel count come back out of the Ogg header, which
        // records them; the depth and the length come from the manifest, which
        // is the only place they exist.
        int bits = net.chonkbase.assetpack.Json.integer(meta, "bitsPerSample", 16);
        int frames = net.chonkbase.assetpack.Json.integer(meta, "sampleFrames", 0);
        Flac.Pcm pcm = Opus.decode(payload, bits == 8 ? 8 : 16);
        if (frames > 0 && frames != pcm.frameCount()) {
            // Belt and braces. Opus.decode already recovers the exact frame
            // count from the stream's granule position, and a disagreement here
            // means a stream that was not written by this build; trusting the
            // manifest keeps the entry the length the game expects either way.
            int[] exact = new int[frames * pcm.channels()];
            System.arraycopy(pcm.samples(), 0, exact, 0,
                    Math.min(exact.length, pcm.samples().length));
            pcm = new Flac.Pcm(pcm.sampleRate(), pcm.channels(), pcm.bitsPerSample(), exact);
        }
        return Wav.encode(pcm);
    }

    // ------------------------------------------------------------------ raw

    private static Encoded raw(byte[] entry, AssetKind kind) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("form", Form.RAW.id());
        return new Encoded(kind, Codec.STORE, entry, meta, Form.RAW);
    }

    // ------------------------------------------------------------ the check

    /**
     * Whether the game's own decoder sees the same thing in two entries.
     *
     * <p>Not {@code Arrays.equals} on the entries. A rebuilt entry is a
     * different encoding of the same picture and comparing bytes would reject
     * every sprite in the game.
     *
     * <p>Public because this is the question a verification pass asks of a
     * finished pack, and it must ask it in exactly the terms the build used.
     * Two implementations of "the same" would let a pack pass one and fail the
     * other, which is worse than having neither.
     */
    public static boolean equivalent(Form form, byte[] original, byte[] rebuilt) {
        try {
            return sameToTheDecoder(form, original, rebuilt);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * How two versions of the same sound compare, when one of them is lossy.
     *
     * @param sameShape whether the rate, the channel count, the bit depth and
     *                  the frame count all agree. If this is false the decibels
     *                  mean nothing, because two clips of different lengths or
     *                  rates were never comparable in the first place.
     * @param snrDb     signal to noise against the original, infinite when the
     *                  samples are identical
     * @param detail    what differs, for a build report, or an empty string
     */
    public record SoundMatch(boolean sameShape, double snrDb, String detail) {

        /** Whether this clears {@link SignalToNoise#SOUND_FLOOR_DB}. */
        public boolean acceptable() {
            return sameShape && snrDb >= SignalToNoise.SOUND_FLOOR_DB;
        }

        /** The comparison as a report reads it. */
        public String describe() {
            return sameShape ? SignalToNoise.describe(snrDb) : detail;
        }
    }

    /**
     * Compares two RIFF entries where the second one went through a lossy
     * codec.
     *
     * <p>Public, and used by the build and by the verification pass, for the
     * same reason {@link #equivalent} is: a lossy asset needs a different
     * question asked of it, and the two places that ask it must ask the same
     * one. The build uses this to decide whether an Opus encoding is good enough
     * to ship at all, so a pack that passes verification passes it by the same
     * standard that let its assets in.
     */
    public static SoundMatch compareSound(byte[] original, byte[] rebuilt) {
        Flac.Pcm before;
        Flac.Pcm after;
        try {
            before = Wav.decode(original);
            after = Wav.decode(rebuilt);
        } catch (RuntimeException e) {
            return new SoundMatch(false, Double.NEGATIVE_INFINITY,
                    "one of the two is not a readable RIFF: " + e.getMessage());
        }
        if (before.sampleRate() != after.sampleRate()) {
            return new SoundMatch(false, Double.NEGATIVE_INFINITY, before.sampleRate()
                    + " Hz became " + after.sampleRate() + " Hz");
        }
        if (before.channels() != after.channels()) {
            return new SoundMatch(false, Double.NEGATIVE_INFINITY, before.channels()
                    + " channels became " + after.channels());
        }
        if (before.bitsPerSample() != after.bitsPerSample()) {
            return new SoundMatch(false, Double.NEGATIVE_INFINITY, before.bitsPerSample()
                    + "-bit became " + after.bitsPerSample() + "-bit");
        }
        if (before.frameCount() != after.frameCount()) {
            return new SoundMatch(false, Double.NEGATIVE_INFINITY, before.frameCount()
                    + " frames became " + after.frameCount());
        }
        return new SoundMatch(true, SignalToNoise.db(before.samples(), after.samples()), "");
    }

    private static boolean soundSurvived(byte[] original, byte[] rebuilt) {
        return compareSound(original, rebuilt).acceptable();
    }

    private static boolean sameToTheDecoder(Form form, byte[] original, byte[] rebuilt) {
        return switch (form) {
            case SPRITE_GFX -> samePixels(GraphicDecoder.Kind.GFX, original, rebuilt);
            case SPRITE_GFU -> samePixels(GraphicDecoder.Kind.GFU, original, rebuilt);
            case IMAGE -> Arrays.equals(ImageDecoder.decode(original).pixels(),
                    ImageDecoder.decode(rebuilt).pixels());
            case CURSOR -> Arrays.equals(CursorDecoder.decode(original).image().pixels(),
                    CursorDecoder.decode(rebuilt).image().pixels());
            case PALETTE, TILE_ATLAS, RAW -> Arrays.equals(original, rebuilt);
            case SOUND -> sameSamples(original, rebuilt);
        };
    }

    private static boolean samePixels(GraphicDecoder.Kind kind, byte[] original, byte[] rebuilt) {
        IndexedImage before = GraphicDecoder.decode(kind, original);
        IndexedImage after = GraphicDecoder.decode(kind, rebuilt);
        return before.width() == after.width() && before.height() == after.height()
                && Arrays.equals(before.pixels(), after.pixels());
    }

    private static boolean sameSamples(byte[] original, byte[] rebuilt) {
        Flac.Pcm before = Wav.decode(original);
        Flac.Pcm after = Wav.decode(rebuilt);
        return before.sampleRate() == after.sampleRate()
                && before.channels() == after.channels()
                && before.bitsPerSample() == after.bitsPerSample()
                && Arrays.equals(before.samples(), after.samples());
    }

    // ----------------------------------------------------------------- odds

    /**
     * A palette for the stored picture to be looked at through.
     *
     * <p>Cosmetic only. The pixels are palette indices and the game supplies
     * its own table; this is so that opening the file shows a footman rather
     * than a grey smear. Where the palette is not known, a ramp is used, which
     * is honest about not knowing rather than picking a wrong one.
     */
    private static byte[] displayPalette(byte[] palette768) {
        if (palette768 != null && palette768.length == 768) {
            return palette768;
        }
        byte[] ramp = new byte[768];
        for (int i = 0; i < 256; i++) {
            ramp[i * 3] = (byte) i;
            ramp[i * 3 + 1] = (byte) i;
            ramp[i * 3 + 2] = (byte) i;
        }
        return ramp;
    }

    private static int number(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static void writeLe16(byte[] out, int at, int value) {
        out[at] = (byte) value;
        out[at + 1] = (byte) (value >> 8);
    }
}
