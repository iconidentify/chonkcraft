package net.chonkbase.chonkcraft.extract;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.chonkbase.assetpack.AssetKind;
import net.chonkbase.assetpack.AssetPackWriter;
import net.chonkbase.assetpack.Codec;
import net.chonkbase.assetpack.PackManifest;
import net.chonkbase.assetpack.codec.Flac;
import net.chonkbase.assetpack.codec.Opus;
import net.chonkbase.chonkcraft.data.GraphicsIndex;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.map.PudReader;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.data.source.EntryArchive;
import net.chonkbase.chonkcraft.data.source.EntryCodec;
import net.chonkbase.chonkcraft.data.video.SmackerVideo;

/**
 * Builds one asset pack out of a Warcraft II installation.
 *
 * <p>Knows nothing about the game that will read the pack. It reads a
 * {@link AssetSource}, which for this purpose is always a real 1995
 * installation, and writes a file; the only thing it and the game agree on is
 * the format, and that lives in a module neither of them can see past.
 *
 * <p>Everything goes in. There is no filter for "assets the implementation currently
 * uses", because a pack that only holds what today's code asks for stops
 * working the moment someone implements a screen that was not finished. All
 * 1,274 archive entries are carried, including the ones the conversion table
 * never names and the five in {@code maindat.war} that are junk.
 */
public final class PackBuilder {

    /** The three measurable passes of a verified pack build. */
    public enum Phase {
        ANALYZING,
        BUILDING,
        VERIFYING
    }

    /** Completed work items and the exact count for the current pass. */
    @FunctionalInterface
    public interface Progress {
        void update(Phase phase, int completed, int total);
    }

    /**
     * What a sound effect is encoded at.
     *
     * <p>Not a rounding of the music rate below, and four times under it. The
     * effects are hundreds of clips of 8-bit mono at 11 and 22 kHz, and Opus
     * runs at 48 kHz in 20 ms frames whatever it is given, so at 128 kb/s a
     * half-second grunt costs 8 KB regardless of what is in it. Measured over
     * the whole sound bank: raw 52.4 MB, FLAC 30.5 MB, Opus at 128k 45.8 MB,
     * Opus at 64k 23.2 MB. A single global bitrate would have made a third of
     * the audio worse and larger at the same time. See
     * docs/asset-pack-format.md.
     */
    private static final int SOUND_BITRATE_BPS = 64_000;

    /**
     * What a recorded music track is encoded at.
     *
     * <p>Above the 128 kb/s the format notes were first written around, for a
     * reason that is about this encoder rather than about Opus: it is CELT-only
     * and constant-rate, measured level with the RFC 6716 reference encoder and
     * ahead of libopus on the sound effects, but behind libopus on music at
     * 128k. Buying the difference back with bits is the honest fix, and 144k on
     * ninety minutes of soundtrack is about six megabytes of pack.
     */
    private static final int MUSIC_BITRATE_BPS = 144_000;

    /** The archives a Warcraft II installation can have, in id order. */
    private static final Map<Integer, String> ARCHIVES = new LinkedHashMap<>();

    static {
        ARCHIVES.put(1000, "maindat");
        ARCHIVES.put(2000, "snddat");
        ARCHIVES.put(3000, "rezdat");
        ARCHIVES.put(4000, "strdat");
        ARCHIVES.put(5000, "sfxdat");
        ARCHIVES.put(6000, "muddat");
    }

    /** What the build did, for the report. */
    public record Report(PackManifest manifest, long packBytes, long sourceBytes,
            int assetCount, int convertedCount, int rawCount, int skippedSlots,
            Map<String, Category> categories, List<String> notes) {

        /** How one class of asset fared. */
        public record Category(int count, long sourceBytes, long packBytes) {

            Category plus(long source, long packed) {
                return new Category(count + 1, sourceBytes + source, packBytes + packed);
            }
        }

        /** How much smaller the pack is, as a percentage of the source. */
        public double reduction() {
            return sourceBytes == 0 ? 0 : 100.0 * (sourceBytes - packBytes) / sourceBytes;
        }
    }

    private final AssetSource source;
    private final boolean verify;
    private final Map<String, Object> sourceProperties;
    private final Progress progress;
    private final List<String> notes = new ArrayList<>();
    private final Map<String, Report.Category> categories = new TreeMap<>();

    private int converted;
    private int rawStored;
    private int skipped;
    private long sourceBytes;
    private int buildCompleted;
    private int buildTotal;

    /**
     * @param source what to read; in practice always an installation
     * @param verify whether to re-read every asset out of the finished pack
     *               and decode it again. Doubles the build time and is worth
     *               it: the guarantee that makes this format usable is not
     *               that the code is careful but that every asset was checked.
     */
    public PackBuilder(AssetSource source, boolean verify) {
        this(source, verify, Map.of(), null);
    }

    /**
     * Builds from the same game source while retaining facts about the
     * container the player selected, such as an installer name and checksum.
     */
    public PackBuilder(AssetSource source, boolean verify,
            Map<String, Object> sourceProperties) {
        this(source, verify, sourceProperties, null);
    }

    /** Builds with pass-by-pass progress suitable for a launcher. */
    public PackBuilder(AssetSource source, boolean verify,
            Map<String, Object> sourceProperties, Progress progress) {
        this.source = source;
        this.verify = verify;
        // Map.copyOf does not promise iteration order. These properties are
        // serialized into pack.json, so hash-derived order made two otherwise
        // identical fixed-epoch imports differ byte-for-byte.
        this.sourceProperties = sourceProperties == null || sourceProperties.isEmpty()
                ? Map.of()
                : java.util.Collections.unmodifiableMap(
                        new LinkedHashMap<>(sourceProperties));
        this.progress = progress;
    }

    /** Builds the pack at {@code out} and returns what it did. */
    public Report build(Path out) {
        EntryArchive main = source.archive(1000);
        if (main == null) {
            throw new IllegalStateException("no maindat.war in " + source.describe());
        }
        EntryArchive strings = source.archive(4000);
        if (strings == null) {
            throw new IllegalStateException("no strdat.war in " + source.describe());
        }
        GraphicsIndex index = GraphicsIndex.load(source.hasExpansion());
        SlotPlan plan = SlotPlan.from(index);
        buildTotal = plannedBuildItems();
        List<MusicGrouping.Group> musicGroups = analyzeMusic();
        update(Phase.BUILDING, 0, buildTotal);

        Map<String, Object> properties = new LinkedHashMap<>();
        // The facts a pack has to carry because the evidence for them does not
        // survive the copy. Whether an installation has the expansion is worked
        // out by counting archive entries, measuring rezdat.war to the byte,
        // and probing for a Battle.net tome file; none of those means anything
        // once the files are gone.
        properties.put("expansionEntries", source.hasExpansion());
        properties.put("expansionRelease", source.isExpansionRelease());
        properties.put("battleNetEdition", source.isBattleNetEdition());
        properties.put("campaignTextOffset", (long) source.campaignTextOffset());
        properties.put("sourceVersion", source.sourceVersion());
        properties.put("sourceFormat", source.sourceFormat());
        for (Map.Entry<String, Object> property : sourceProperties.entrySet()) {
            if (property.getKey().startsWith("source")) {
                properties.put(property.getKey(), property.getValue());
            }
        }

        Object originalName = sourceProperties.get("sourceOriginalName");
        String stableSource = originalName instanceof String name && !name.isBlank()
                ? "Imported from " + name
                : source.describe();
        PackManifest.Identity identity = new PackManifest.Identity(
                source.editionId(),
                source.editionName(),
                stableSource,
                "chonkcraft-extractor " + version(),
                buildTimestamp(),
                properties);

        PackManifest manifest;
        long packBytes;
        try (AssetPackWriter writer = new AssetPackWriter(out, identity)) {
            for (Map.Entry<Integer, String> archive : ARCHIVES.entrySet()) {
                writeArchive(writer, plan, archive.getKey(), archive.getValue());
            }
            writeMusic(writer, musicGroups);
            writeMaps(writer);
            writeSupplemental(writer);
            writer.dictionary(Dictionary.of(writer.assets(), identity));
            built(1);
            manifest = writer.finish();
        }
        try {
            packBytes = java.nio.file.Files.size(out);
        } catch (java.io.IOException e) {
            packBytes = 0;
        }

        if (!plan.collisions().isEmpty()) {
            notes.add(plan.collisions().size() + " asset paths name an entry another path"
                    + " reached first, which is how the conversion table is written;"
                    + " the first name wins, as it does everywhere else in the port");
        }
        if (verify) {
            notes.addAll(PackVerifier.verify(out, source,
                    (completed, total) -> update(
                            Phase.VERIFYING, completed, total)));
        }

        return new Report(manifest, packBytes, sourceBytes,
                manifest.assets().size(), converted, rawStored, skipped,
                Map.copyOf(categories), List.copyOf(notes));
    }

    // ------------------------------------------------------------- archives

    private void writeArchive(AssetPackWriter writer, SlotPlan plan, int id, String name) {
        EntryArchive archive = source.archive(id);
        if (archive == null) {
            // A real answer, not a failure. The DOS release genuinely has no
            // snddat.war: its sounds are in sfxdat.sud and its music is on the
            // disc as audio tracks. A pack built from it records the absence
            // by having no such archive, so the engine's fail-soft fallback
            // runs exactly as it did.
            notes.add("no " + name + " in this installation; the pack has no archive " + id);
            return;
        }

        int[] slots = new int[archive.entryCount()];
        for (int entry = 0; entry < archive.entryCount(); entry++) {
            if (!archive.isValid(entry)) {
                // Junk in the original stays junk here. maindat's entries 28
                // to 32 in the DOS build declare multi-megabyte lengths at
                // offsets a byte apart; closing the gap would renumber every
                // entry after them, and the numbers are what the engine holds.
                slots[entry] = -1;
                skipped++;
                continue;
            }
            slots[entry] = writeEntry(writer, plan, archive, name, entry);
            built(1);
        }
        writer.archive(id, name, slots);
    }

    private int writeEntry(AssetPackWriter writer, SlotPlan plan, EntryArchive archive,
            String archiveName, int entry) {
        byte[] bytes = archive.entry(entry);
        SlotPlan.Slot slot = plan.slot(archive.id(), entry);
        if (slot == null) {
            slot = sniff(SlotPlan.unnamed(archiveName, entry), bytes);
        }

        byte[] palette = slot.paletteEntry() > 0 && slot.paletteEntry() < archive.entryCount()
                ? paletteOf(archive, slot.paletteEntry())
                : null;

        // Only a sound effect is offered a lossy encoding, and only at the rate
        // measured for sound effects. Everything else in an archive -- sprites,
        // palettes, maps, text, the sequenced music -- stays exact, which is the
        // format's rule with one deliberate exception rather than a preference.
        EntryCodec.AudioTarget audio = slot.form() == EntryCodec.Form.SOUND
                ? EntryCodec.AudioTarget.opus(SOUND_BITRATE_BPS)
                : EntryCodec.AudioTarget.LOSSLESS;
        EntryCodec.Encoded encoded =
                EntryCodec.encode(slot.form(), bytes, palette, slot.kind(), audio);
        AssetKind kind = encoded.converted() ? encoded.kind() : slot.kind();
        Codec codec = encoded.codec();
        Map<String, Object> meta = new LinkedHashMap<>(encoded.meta());

        // A cutscene keeps its own codec name so that a reader knows what the
        // bytes are without decoding them. It is stored unaltered: see
        // docs/asset-pack-format.md for the measurements that decided that.
        if (kind == AssetKind.VIDEO && SmackerVideo.looksLikeSmacker(bytes)) {
            codec = Codec.SMACKER;
            describeVideo(meta, bytes);
        }
        if (kind == AssetKind.MAP) {
            describeMap(meta, bytes);
        }

        if (encoded.converted()) {
            converted++;
        } else {
            rawStored++;
        }
        sourceBytes += bytes.length;

        String path = "assets/" + safePath(slot.id()) + extensionFor(kind, codec);
        int index = writer.add(slot.id(), kind, codec, path, encoded.payload(), bytes.length, meta);
        record(kind, bytes.length, writer.compressedBytes(index));
        return index;
    }

    /**
     * Works out what an entry the conversion table never names actually is.
     *
     * <p>Two hundred entries are in the archives and not in the table, and
     * about a hundred of those are the four cutscenes Warcraft II ships and
     * never plays -- twenty-four megabytes of Smacker with no row pointing at
     * them. Guessing wrong here cannot break anything, because a wrong guess
     * falls back to storing the bytes as they are; guessing right means the
     * build report says what is in the pack instead of calling a fifth of it
     * "binary", and means the stray sounds get compressed like the rest.
     */
    private static SlotPlan.Slot sniff(SlotPlan.Slot unnamed, byte[] bytes) {
        if (SmackerVideo.looksLikeSmacker(bytes)) {
            return new SlotPlan.Slot(unnamed.id(), AssetKind.VIDEO, EntryCodec.Form.RAW, 0);
        }
        if (bytes.length > 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V') {
            return new SlotPlan.Slot(unnamed.id(), AssetKind.SOUND, EntryCodec.Form.SOUND, 0);
        }
        if (bytes.length == 768) {
            return new SlotPlan.Slot(unnamed.id(), AssetKind.PALETTE, EntryCodec.Form.PALETTE, 0);
        }
        return unnamed;
    }

    /** A palette entry, or null when that entry does not hold one. */
    private byte[] paletteOf(EntryArchive archive, int entry) {
        try {
            byte[] bytes = archive.entry(entry);
            return bytes.length == 768 ? bytes : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void describeVideo(Map<String, Object> meta, byte[] bytes) {
        try {
            SmackerVideo video = SmackerVideo.read(bytes);
            meta.put("width", (long) video.width());
            meta.put("height", (long) video.height());
            meta.put("frameCount", (long) video.frameCount());
            meta.put("frameMicros", (long) video.frameMicros());
            if (video.hasAudio(0)) {
                SmackerVideo.Audio audio = video.decodeAudio(0);
                meta.put("sampleRate", (long) audio.sampleRate());
                meta.put("channels", (long) audio.channels());
            }
        } catch (RuntimeException e) {
            // A cutscene that will not parse still goes in the pack byte for
            // byte. Refusing it would lose it; describing it is a convenience.
        }
    }

    private static void describeMap(Map<String, Object> meta, byte[] bytes) {
        try {
            PudMap map = PudReader.read(bytes);
            meta.put("width", (long) map.width());
            meta.put("height", (long) map.height());
            meta.put("tileset", map.tileset().name().toLowerCase(Locale.ROOT));
        } catch (RuntimeException e) {
            // Some P rows point at entries that are not maps on every release.
        }
    }

    // ---------------------------------------------------------------- music

    private List<MusicGrouping.Group> analyzeMusic() {
        List<AssetSource.MusicTrack> tracks = source.musicTracks();
        if (tracks.isEmpty()) {
            return List.of();
        }
        update(Phase.ANALYZING, 0, Math.max(1, tracks.size()));
        return MusicGrouping.of(source,
                completed -> update(Phase.ANALYZING,
                        completed, Math.max(1, tracks.size())));
    }

    private void writeMusic(AssetPackWriter writer, List<MusicGrouping.Group> groups) {
        List<AssetSource.MusicTrack> tracks = source.musicTracks();
        if (tracks.isEmpty()) {
            notes.add("no red book music found; the pack has recorded music only if a disc"
                    + " image was beside the installation");
            return;
        }
        int shared = 0;
        for (MusicGrouping.Group group : groups) {
            shared += group.members().size() - 1;
        }
        if (shared > 0) {
            notes.add(shared + " of the " + tracks.size() + " music tracks are the same"
                    + " recording as another, pressed a moment apart, and are stored once");
        }

        // Track index to asset index, filled in as groups are written, because
        // a disc's list has to come out in the disc's own order and the groups
        // do not follow it.
        int[] assetOf = new int[tracks.size()];
        Arrays.fill(assetOf, -1);

        for (MusicGrouping.Group group : groups) {
            writeMusicGroup(writer, tracks, group, assetOf);
            built(group.members().size());
        }

        Map<String, List<Integer>> discs = new LinkedHashMap<>();
        for (int i = 0; i < tracks.size(); i++) {
            if (assetOf[i] < 0) {
                continue;
            }
            discs.computeIfAbsent(discOf(tracks.get(i).name()), key -> new ArrayList<>())
                    .add(assetOf[i]);
        }
        for (Map.Entry<String, List<Integer>> disc : discs.entrySet()) {
            writer.disc(disc.getKey(), disc.getValue());
        }
    }

    private void writeMusicGroup(AssetPackWriter writer, List<AssetSource.MusicTrack> tracks,
            MusicGrouping.Group group, int[] assetOf) {
        int channels = group.channels();
        int[] master = new int[group.totalSamples()];
        long rawBytes = 0;
        // Each member's real length, taken from what was actually read rather
        // than from what the cue sheet implies. A track's declared duration is
        // a rounded number of seconds and its true length is a whole number of
        // sectors, and the two disagree by a frame often enough to matter.
        long[] frames = new long[group.members().size()];
        for (int m = 0; m < group.members().size(); m++) {
            MusicGrouping.Member member = group.members().get(m);
            short[] samples = source.musicSamples(member.track());
            int at = member.frameOffset() * channels;
            for (int i = 0; i < samples.length; i++) {
                master[at + i] = samples[i];
            }
            frames[m] = samples.length / channels;
            rawBytes += (long) samples.length * 2;
        }

        MusicGrouping.Member first = group.members().get(0);
        AssetSource.MusicTrack lead = tracks.get(first.track());
        if (master.length == 0) {
            notes.add("track \"" + lead.name() + "\" read as empty and was left out");
            return;
        }

        // The group's whole union goes into one Opus stream, and each member is
        // a window onto it. The windows stay in the recording's own 44,100 Hz
        // frames: Opus stores the audio at 48 kHz because it has no choice, and
        // PackSource resamples it back before any window is taken, so nothing
        // outside this file has to know which rate the bytes are in.
        byte[] opus = Opus.encode(new Flac.Pcm(group.sampleRate(), channels, 16, master),
                MUSIC_BITRATE_BPS);
        String id = idOf(lead.name());
        int index = writer.add(id, AssetKind.MUSIC, Codec.OPUS,
                "assets/" + safePath(id) + ".opus", opus,
                (long) master.length * 2, musicMeta(lead, first, frames[0]));
        assetOf[first.track()] = index;
        sourceBytes += rawBytes;
        record(AssetKind.MUSIC, rawBytes, writer.compressedBytes(index));

        for (int m = 1; m < group.members().size(); m++) {
            MusicGrouping.Member member = group.members().get(m);
            AssetSource.MusicTrack track = tracks.get(member.track());
            assetOf[member.track()] = writer.alias(idOf(track.name()), AssetKind.MUSIC, index,
                    0, musicMeta(track, member, frames[m]));
            // Categories count logical assets. The shared payload was already
            // charged above; an alias contributes identity but no bytes.
            record(AssetKind.MUSIC, 0, 0);
        }
    }

    private static Map<String, Object> musicMeta(AssetSource.MusicTrack track,
            MusicGrouping.Member member, long frames) {
        Map<String, Object> meta = new LinkedHashMap<>();
        // The name is the identity. Nothing in a Warcraft II installation
        // names its music: the game asks for "track three of whichever disc is
        // in the drive", so this string is what has to survive.
        meta.put("name", track.name());
        meta.put("sampleRate", (long) track.sampleRate());
        meta.put("channels", (long) track.channels());
        meta.put("bitsPerSample", 16L);
        meta.put("frameOffset", (long) member.frameOffset());
        meta.put("sampleFrames", frames);
        // The rate the stream underneath actually decodes at, which is not the
        // one above it and never can be. A reader that resamples for itself
        // needs both numbers, and a reader that does not still wants to be able
        // to see that the pack knew the difference.
        meta.put("decodeSampleRate", (long) Opus.CODEC_RATE);
        meta.put("bitrateBps", (long) MUSIC_BITRATE_BPS);
        if (!track.sourceOrigin().isBlank()) {
            meta.put("sourceOrigin", track.sourceOrigin());
        }
        return meta;
    }

    private static String idOf(String trackName) {
        return "music/cd/" + discOf(trackName) + "/" + trackOf(trackName);
    }

    private static String discOf(String trackName) {
        int at = trackName.lastIndexOf(" track ");
        return at < 0 ? "disc" : trackName.substring(0, at);
    }

    private static String trackOf(String trackName) {
        int at = trackName.lastIndexOf(" track ");
        return at < 0 ? trackName : "track-" + trackName.substring(at + 7);
    }

    // ----------------------------------------------------------------- maps

    private void writeMaps(AssetPackWriter writer) {
        List<Integer> indices = new ArrayList<>();
        for (String name : source.mapNames()) {
            byte[] bytes = source.map(name);
            if (bytes == null) {
                built(1);
                continue;
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("form", "raw");
            // The name the game shows and the name a saved game records. On
            // the raw path a map was a file and saves wrote its absolute
            // location; a packed map has no location at all, so the name is
            // the only identity that survives both.
            meta.put("name", name);
            describeMap(meta, bytes);
            sourceBytes += bytes.length;
            int index = writer.add("maps/" + name, AssetKind.MAP, Codec.STORE,
                    "assets/maps/" + safePath(name), bytes, bytes.length, meta);
            record(AssetKind.MAP, bytes.length, writer.compressedBytes(index));
            indices.add(index);
            built(1);
        }
        writer.mapList(indices);
    }

    /**
     * Preserves named BNE tables that do not stand in for an archive entry.
     *
     * <p>Sounds and videos already arrive through their overlaid numbered
     * archives, maps through {@link AssetSource#mapNames}, and recorded music
     * through {@link AssetSource#musicTracks}. The four BNE-only text tables
     * have no classic slot at all; without this pass they were the one part of
     * INSTALL.EXE that a pack silently dropped.
     */
    private void writeSupplemental(AssetPackWriter writer) {
        for (AssetSource.SupplementalAsset asset : source.supplementalAssets()) {
            AssetKind kind = switch (asset.kind()) {
                case TEXT -> AssetKind.TEXT;
                case BINARY -> AssetKind.BINARY;
                default -> null;
            };
            if (kind == null) {
                continue;
            }
            byte[] bytes = source.supplementalAsset(asset);
            if (bytes == null) {
                built(1);
                continue;
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("form", "raw");
            meta.put("supplementalPath", asset.path());
            meta.put("supplementalKind", asset.kind().name().toLowerCase(Locale.ROOT));
            String id = "supplemental/" + asset.path();
            int index = writer.add(id, kind, Codec.STORE,
                    "assets/" + safePath(id) + (kind == AssetKind.TEXT ? ".tbl" : ".bin"),
                    bytes, bytes.length, meta);
            sourceBytes += bytes.length;
            rawStored++;
            record(kind, bytes.length, writer.compressedBytes(index));
            built(1);
        }
    }

    private int plannedBuildItems() {
        int total = 1 + source.musicTracks().size() + source.mapNames().size();
        for (int id : ARCHIVES.keySet()) {
            EntryArchive archive = source.archive(id);
            if (archive == null) {
                continue;
            }
            for (int entry = 0; entry < archive.entryCount(); entry++) {
                if (archive.isValid(entry)) {
                    total++;
                }
            }
        }
        total += (int) source.supplementalAssets().stream()
                .filter(asset -> asset.kind() == AssetSource.SupplementalAsset.Kind.TEXT
                        || asset.kind() == AssetSource.SupplementalAsset.Kind.BINARY)
                .count();
        return Math.max(1, total);
    }

    private void built(int count) {
        buildCompleted = Math.min(buildTotal, buildCompleted + Math.max(0, count));
        update(Phase.BUILDING, buildCompleted, buildTotal);
    }

    private void update(Phase phase, int completed, int total) {
        if (progress != null) {
            progress.update(phase, completed, Math.max(1, total));
        }
    }

    // ----------------------------------------------------------------- odds

    private void record(AssetKind kind, long source, long packed) {
        categories.merge(kind.id(), new Report.Category(1, source, packed),
                (a, b) -> new Report.Category(a.count() + b.count(),
                        a.sourceBytes() + b.sourceBytes(), a.packBytes() + b.packBytes()));
    }

    /**
     * A file path for an asset name.
     *
     * <p>Asset names come out of the game's own string table and are not
     * filenames: they hold spaces, apostrophes and capitals. Zip entry names
     * are case-sensitive where the filesystem the data came from was not, so a
     * pack that kept the original spelling would work on one machine and fail
     * on another.
     */
    static String safePath(String id) {
        StringBuilder out = new StringBuilder(id.length());
        for (int i = 0; i < id.length(); i++) {
            char c = Character.toLowerCase(id.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '/' || c == '.' || c == '-' || c == '_') {
                out.append(c);
            } else {
                out.append('-');
            }
        }
        return out.toString();
    }

    private static String extensionFor(AssetKind kind, Codec codec) {
        return switch (codec) {
            case PNG_INDEXED -> ".png";
            case OPUS -> ".opus";
            case FLAC -> ".flac";
            case WAV -> ".wav";
            case MIDI -> ".mid";
            case SMACKER -> ".smk";
            case STORE -> switch (kind) {
                case MAP -> ".pud";
                case TEXT -> ".txt";
                case SEQUENCE -> ".xmi";
                case FONT -> ".fnt";
                default -> ".bin";
            };
        };
    }

    /**
     * When this pack was built, or a fixed time when the caller asks for one.
     *
     * <p>Every asset in a pack is a pure function of the installation it came
     * from, so two builds from the same data produce byte-identical payloads.
     * The file was still not identical, because this one field is not a
     * function of anything: the timestamp differs, and being of variable
     * length -- {@code Instant} prints as many fractional digits as it has --
     * it even deflated to a different size, so two packs of identical content
     * differed in their total byte count. That is the sort of difference
     * somebody eventually spends an afternoon on.
     *
     * <p>{@code SOURCE_DATE_EPOCH} is the reproducible-builds convention:
     * seconds since the epoch, and when it is set the build is a function of
     * its inputs and nothing else. Unset, a pack records when it was made,
     * which is what a person looking at one usually wants to know.
     */
    static String buildTimestamp() {
        String pinned = System.getenv("SOURCE_DATE_EPOCH");
        if (pinned == null || pinned.isBlank()) {
            return Instant.now().toString();
        }
        try {
            return Instant.ofEpochSecond(Long.parseLong(pinned.trim())).toString();
        } catch (NumberFormatException | java.time.DateTimeException e) {
            throw new IllegalArgumentException(
                    "SOURCE_DATE_EPOCH must be seconds since the epoch, got \"" + pinned + "\"", e);
        }
    }

    private static String version() {
        String declared = PackBuilder.class.getPackage().getImplementationVersion();
        return declared == null ? "0.1.0-SNAPSHOT" : declared;
    }

    /** The dictionary the pack carries, generated from what actually went in. */
    static final class Dictionary {

        private Dictionary() {
        }

        static String of(List<net.chonkbase.assetpack.PackAsset> assets,
                PackManifest.Identity identity) {
            Map<String, long[]> byKind = new TreeMap<>();
            java.util.Set<String> counted = new java.util.HashSet<>();
            for (var asset : assets) {
                long[] tally = byKind.computeIfAbsent(asset.kind().id(), key -> new long[3]);
                tally[0]++;
                // An asset that shares another's file is counted once. Adding
                // its size again would say the pack holds 563 MB of music when
                // the file holds 335 MB of it, which is the kind of number
                // somebody would later try to reconcile against the zip.
                if (counted.add(asset.file())) {
                    tally[1] += asset.storedBytes();
                } else {
                    tally[2]++;
                }
            }

            StringBuilder out = new StringBuilder();
            out.append("# ").append(identity.name()).append("\n\n");
            out.append("Built by ").append(identity.builtBy())
                    .append(" on ").append(identity.builtAt())
                    .append(" from ").append(identity.source()).append(".\n\n");
            out.append("The format this file describes is specified in ")
                    .append("docs/asset-pack-format.md. This is what is in THIS pack.\n\n");
            out.append("| Kind | Assets | Sharing another's file | Bytes in the pack |\n");
            out.append("|---|---:|---:|---:|\n");
            for (Map.Entry<String, long[]> kind : byKind.entrySet()) {
                out.append("| `").append(kind.getKey()).append("` | ")
                        .append(kind.getValue()[0]).append(" | ")
                        .append(kind.getValue()[2]).append(" | ")
                        .append(String.format(Locale.ROOT, "%,d", kind.getValue()[1]))
                        .append(" |\n");
            }
            out.append("\nEvery asset is listed in `pack.json` with its id, its codec, the ")
                    .append("SHA-256 of its stored bytes, and everything about it that the ")
                    .append("bytes do not say. To replace one, edit the file it names and ")
                    .append("keep its palette indices; see the format specification for what ")
                    .append("a paint program is allowed to change.\n");
            return out.toString();
        }
    }

    /** Bytes as a human reads them. */
    public static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    /** The report as a person reads it. */
    public static String describe(Report report) {
        StringBuilder out = new StringBuilder();
        out.append("Asset pack built.\n\n");
        // Source is what the assets weigh once unpacked from their 1995
        // containers, because that is what the pack's contents are comparable
        // to. The archives themselves are smaller on disk, and the red book
        // audio is larger, since it is stored as raw sectors.
        out.append(String.format(Locale.ROOT, "  source data   %12s  (%,d bytes, decompressed)%n",
                humanBytes(report.sourceBytes()), report.sourceBytes()));
        out.append(String.format(Locale.ROOT, "  pack          %12s  (%,d bytes)%n",
                humanBytes(report.packBytes()), report.packBytes()));
        out.append(String.format(Locale.ROOT, "  reduction     %11.1f%%%n", report.reduction()));
        out.append(String.format(Locale.ROOT, "  assets        %12d (%d converted, %d stored raw)%n",
                report.assetCount(), report.convertedCount(), report.rawCount()));
        out.append(String.format(Locale.ROOT, "  empty slots   %12d%n", report.skippedSlots()));
        out.append("\n");
        out.append(String.format(Locale.ROOT, "  %-14s %7s %14s %14s %9s%n",
                "kind", "count", "source", "packed", "of source"));
        for (Map.Entry<String, Report.Category> kind : report.categories().entrySet()) {
            Report.Category category = kind.getValue();
            double ratio = category.sourceBytes() == 0 ? 0
                    : 100.0 * category.packBytes() / category.sourceBytes();
            out.append(String.format(Locale.ROOT, "  %-14s %7d %14s %14s %8.1f%%%n",
                    kind.getKey(), category.count(),
                    humanBytes(category.sourceBytes()), humanBytes(category.packBytes()), ratio));
        }
        if (!report.notes().isEmpty()) {
            out.append("\nNotes:\n");
            for (String note : report.notes()) {
                out.append("  - ").append(note).append('\n');
            }
        }
        return out.toString();
    }

    /** The manifest as bytes, for a caller that wants to write it elsewhere. */
    public static byte[] manifestBytes(PackManifest manifest) {
        return manifest.toJson().getBytes(StandardCharsets.UTF_8);
    }
}
