package net.chonkbase.chonkcraft.data.source;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.chonkbase.assetpack.codec.Flac;
import net.chonkbase.assetpack.codec.Wav;
import net.chonkbase.chonkcraft.data.GraphicsIndex;
import net.chonkbase.chonkcraft.data.archive.CdAudio;
import net.chonkbase.chonkcraft.data.archive.WarArchive;

/**
 * A real 1995 Warcraft II installation, read in place.
 *
 * <p>This is what the implementation did before there were packs, gathered behind
 * {@link AssetSource} without changing any of it. It stays because the
 * extractor has to read an installation to build a pack from one, and because
 * the whole test suite runs against a real install.
 *
 * <p>The release sniffing here is lifted verbatim out of {@code GameData}, and
 * the comments that came with it are kept, because the values are peculiar and
 * were arrived at the hard way: wartool tells the two discs apart by the exact
 * byte size of {@code rezdat.war} and nothing else.
 */
public final class InstallSource implements AssetSource {

    /**
     * The size of {@code rezdat.war} on the expansion disc.
     *
     * <p>wartool tells the two discs apart by this and nothing else: anything
     * of a different size is an original release, and this exact size is
     * Beyond the Dark Portal.
     */
    private static final long EXPANSION_REZDAT_SIZE = 2_811_086L;

    /**
     * How many entries the base game's {@code maindat.war} holds.
     *
     * <p>The conversion table's last base-game row names entry 436, and every
     * row past it is marked as the expansion's, so an archive with more
     * entries than that is Beyond the Dark Portal, and the difference decides
     * which of two rows sharing a path is the real one and which is the
     * stand-in.
     *
     * <p>Counted rather than taken from the directory's name: this implementation has
     * been developed against a folder called "Tides of Darkness 1995" that is
     * in fact a full expansion install, which is exactly the kind of thing a
     * name will tell you wrongly.
     */
    private static final int BASE_GAME_ENTRIES = 437;

    /**
     * Stereo frames in one raw CD sector.
     *
     * <p>2,352 bytes of red book audio, four bytes to a frame.
     */
    private static final int FRAMES_PER_SECTOR = 588;

    private final Warcraft2Install install;
    private final Map<Integer, EntryArchive> open = new LinkedHashMap<>();
    private final boolean expansion;

    private List<String> mapNames;
    private Map<String, Path> mapFiles;
    private Map<String, SupplementalAsset> supplementalMaps;
    private List<MusicTrack> tracks;
    private List<TrackSource> trackSources;
    private BattleNetAssets battleNetAssets;
    private boolean battleNetAssetsScanned;

    private sealed interface TrackSource permits DiscTrackSource, BattleNetTrackSource {}

    private record DiscTrackSource(Path disc, CdAudio.Track track) implements TrackSource {}

    private record BattleNetTrackSource(SupplementalAsset asset) implements TrackSource {}

    private record WaveInfo(int sampleRate, int channels, int bitsPerSample, long frames) {}

    private InstallSource(Warcraft2Install install) {
        this.install = install;
        Path mainFile = archivePath(ArchiveIds.MAINDAT);
        if (mainFile == null) {
            throw new IllegalStateException("no maindat.war under " + install.root());
        }
        EntryArchive main = WarArchive.open(mainFile, ArchiveIds.MAINDAT);
        this.expansion = main.entryCount() > BASE_GAME_ENTRIES;
        EntryArchive overlaid = battleNetArchive(ArchiveIds.MAINDAT, main);
        open.put(ArchiveIds.MAINDAT, overlaid == null ? main : overlaid);
    }

    /** Opens the installation at {@code root}, or fails. */
    public static InstallSource at(Path root) {
        return new InstallSource(Warcraft2Install.at(root));
    }

    /** Opens the installation at {@code root}, or returns null. */
    public static InstallSource tryAt(Path root) {
        Warcraft2Install found = Warcraft2Install.tryAt(root);
        return found == null ? null : new InstallSource(found);
    }

    /**
     * The installation this machine is configured for, or {@code null}.
     *
     * <p>Checks the {@code wc2.install.dir} system property, then the
     * {@code WC2_INSTALL_DIR} environment variable, and returns {@code null}
     * when neither is set or neither points at a real installation -- the same
     * order and the same answers as {@code Warcraft2Install.fromEnvironment},
     * because those two names are what the setup docs and the continuous
     * integration scripts tell a developer to set.
     *
     * <p>Unlike {@link AssetSource#fromEnvironment} this never returns a pack.
     * It is for the callers that specifically need an installation read in
     * place: the extractor, which builds a pack out of one, and the tests that
     * cover the raw path.
     */
    public static InstallSource fromEnvironment() {
        Warcraft2Install install = Warcraft2Install.fromEnvironment();
        return install == null ? null : new InstallSource(install);
    }

    /**
     * The directory this was opened on.
     *
     * <p>An installation is a directory and a pack is not, which is why this
     * is here and not on {@link AssetSource}. It exists for the handful of
     * checks whose subject is the directory itself -- a test comparing this
     * source's map list against a raw walk of the same folder has to walk
     * something real -- and for nothing else. A caller that wants bytes should
     * ask {@link #map} or {@link #archive}; those work against a pack too, and
     * this does not.
     */
    public Path root() {
        return install.root();
    }

    /**
     * Where an archive lives on disk, or {@code null} if this release does not
     * ship it.
     *
     * <p>The same escape hatch as {@link #root}, one level finer, and it is
     * how the {@code data} module's own tests reach a {@code .war} file to
     * open with {@link WarArchive} directly: their subject is the reader, so
     * they need the file rather than the entries. Everything that just wants
     * the contents calls {@link #archive}, which memoises the open archive and
     * works whether the bytes came off a disc or out of a pack.
     */
    public Path archivePath(int archiveId) {
        Warcraft2Install.Archive kind = kindOf(archiveId);
        return kind == null ? null : install.find(kind);
    }

    /** The 1995 file this archive id names, or null if the id is not one of the six. */
    private static Warcraft2Install.Archive kindOf(int archiveId) {
        for (Warcraft2Install.Archive kind : Warcraft2Install.Archive.values()) {
            if (kind.id() == archiveId) {
                return kind;
            }
        }
        return null;
    }

    @Override
    public String describe() {
        return "Warcraft II installation at " + install.root();
    }

    @Override
    public synchronized EntryArchive archive(int archiveId) {
        EntryArchive already = open.get(archiveId);
        if (already != null) {
            return already;
        }
        Path file = archivePath(archiveId);
        EntryArchive base = file == null ? null : WarArchive.open(file, archiveId);
        EntryArchive overlaid = battleNetArchive(archiveId, base);
        EntryArchive result = overlaid == null ? base : overlaid;
        if (result != null) {
            open.put(archiveId, result);
        }
        return result;
    }

    /**
     * Recreates the two archives BNE replaced with named MPQ files.
     *
     * <p>SFXDAT becomes {@code Gamesfx\...} inside War2Dat.mpq and MUDDAT
     * becomes {@code Smk\...} inside INSTALL.EXE. The conversion table still
     * addresses their classic slot numbers, so the adapter joins the named
     * files back to those slots.
     */
    private EntryArchive battleNetArchive(int archiveId, EntryArchive base) {
        if ((archiveId != ArchiveIds.MAINDAT
                    && archiveId != ArchiveIds.SNDDAT
                    && archiveId != ArchiveIds.REZDAT
                    && archiveId != ArchiveIds.SFXDAT
                    && archiveId != ArchiveIds.MUDDAT)
                || !hasBattleNetTomes()) {
            return null;
        }
        BattleNetAssets assets = battleNetAssets();
        EntryArchive strings = archive(ArchiveIds.STRDAT);
        if (assets == null || strings == null || !strings.isValid(1)) {
            return null;
        }
        GraphicsIndex index = GraphicsIndex.load(expansion);
        return BattleNetArchive.create(archiveId, index, assets, base);
    }

    @Override
    public boolean hasExpansion() {
        return expansion;
    }

    @Override
    public boolean isExpansionRelease() {
        if (hasBattleNetTomes()) {
            // The Battle.net edition carries the expansion.
            return true;
        }
        Path rezdat = archivePath(ArchiveIds.REZDAT);
        if (rezdat == null) {
            return false;
        }
        try {
            return Files.size(rezdat) == EXPANSION_REZDAT_SIZE;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public boolean isBattleNetEdition() {
        return hasBattleNetTomes();
    }

    /** Whether the installation has the Battle.net edition's tome files. */
    private boolean hasBattleNetTomes() {
        for (String candidate : List.of(
                "support/tomes/tome.1", "Support/TOMES/TOME.1",
                "SUPPORT/TOMES/TOME.1",
                "MapEditor/support/tomes/tome.1",
                "MapEditor/Support/TOMES/TOME.1",
                "MAPEDITOR/SUPPORT/TOMES/TOME.1",
                "War2Dat.mpq", "WAR2DAT.MPQ")) {
            if (Files.isRegularFile(install.root().resolve(candidate))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int campaignTextOffset() {
        // CampaignsCreate overrides the offset the conversion table carries:
        // 236 for the expansion and 140 for everything else. Getting it wrong
        // does not fail, it shifts, and every mission shows the previous one's
        // objectives.
        return isExpansionRelease() ? 236 : 140;
    }

    // ----------------------------------------------------------------- maps

    private synchronized void scanMaps() {
        if (mapNames != null) {
            return;
        }
        Map<String, Path> found = new LinkedHashMap<>();
        try (var walk = Files.walk(install.root())) {
            List<Path> puds = walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .toUpperCase(Locale.ROOT).endsWith(".PUD"))
                    // Sorted by file name, then by full path, so the menu reads
                    // the same twice running. A directory walk does not promise
                    // an order, and a list that shuffles between launches is the
                    // sort of thing a player notices and cannot explain.
                    .sorted(Comparator
                            .comparing((Path path) -> path.getFileName().toString())
                            .thenComparing(Path::toString))
                    .toList();
            for (Path pud : puds) {
                String name = pud.getFileName().toString();
                if (found.containsKey(name)) {
                    // Two maps of the same name in different folders. Rare, and
                    // the relative path is the only spelling that tells them
                    // apart, so the second one wears it.
                    name = install.root().relativize(pud).toString().replace('\\', '/');
                }
                found.put(name, pud);
            }
        } catch (IOException e) {
            // An unreadable install directory means no skirmish maps, which is
            // the same outcome as an install that has none.
        }
        Map<String, SupplementalAsset> extras = new LinkedHashMap<>();
        BattleNetAssets bne = isBattleNetEdition() ? battleNetAssets() : null;
        if (bne != null) {
            for (SupplementalAsset asset : bne.assets()) {
                if (asset.kind() != SupplementalAsset.Kind.MAP
                        || !asset.path().startsWith("maps/")) {
                    continue;
                }
                String name = asset.path().substring("maps/".length());
                boolean duplicate = found.keySet().stream()
                        .anyMatch(existing -> existing.equalsIgnoreCase(name));
                if (!duplicate) {
                    extras.putIfAbsent(name, asset);
                }
            }
        }
        mapFiles = found;
        supplementalMaps = extras;
        List<String> names = new ArrayList<>(found.keySet());
        names.addAll(extras.keySet());
        mapNames = List.copyOf(names);
    }

    @Override
    public List<String> mapNames() {
        scanMaps();
        return mapNames;
    }

    @Override
    public byte[] map(String name) {
        scanMaps();
        Path file = mapFiles.get(name);
        if (file == null) {
            for (Map.Entry<String, Path> candidate : mapFiles.entrySet()) {
                if (candidate.getKey().equalsIgnoreCase(name)) {
                    file = candidate.getValue();
                    break;
                }
            }
        }
        if (file == null) {
            SupplementalAsset extra = supplementalMaps.get(name);
            if (extra == null) {
                for (Map.Entry<String, SupplementalAsset> candidate
                        : supplementalMaps.entrySet()) {
                    if (candidate.getKey().equalsIgnoreCase(name)) {
                        extra = candidate.getValue();
                        break;
                    }
                }
            }
            return extra == null ? null : supplementalAsset(extra);
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read map " + file, e);
        }
    }

    // --------------------------------------------------- supplemental files

    private synchronized BattleNetAssets battleNetAssets() {
        if (!battleNetAssetsScanned) {
            battleNetAssets = BattleNetAssets.tryAt(install.root());
            battleNetAssetsScanned = true;
        }
        return battleNetAssets;
    }

    @Override
    public List<SupplementalAsset> supplementalAssets() {
        BattleNetAssets assets = isBattleNetEdition() ? battleNetAssets() : null;
        return assets == null ? List.of() : assets.assets();
    }

    @Override
    public byte[] supplementalAsset(SupplementalAsset asset) {
        BattleNetAssets assets = isBattleNetEdition() ? battleNetAssets() : null;
        return assets == null ? null : assets.read(asset);
    }

    /** Where a map came from, for the callers that still write a path down. */
    public Path mapPath(String name) {
        scanMaps();
        return mapFiles.get(name);
    }

    // -------------------------------------------------------------- red book

    private synchronized void scanRecordings() {
        if (tracks != null) {
            return;
        }
        List<MusicTrack> found = new ArrayList<>();
        List<TrackSource> sources = new ArrayList<>();

        // Battle.net Edition replaced red-book tracks with named, 16-bit
        // stereo WAV recordings inside INSTALL.EXE. They are the release's
        // soundtrack, not optional loose files, and must win over any disc
        // image that happens to sit beside an extracted CD directory.
        BattleNetAssets bne = isBattleNetEdition() ? battleNetAssets() : null;
        if (bne != null) {
            for (SupplementalAsset asset : bne.assets()) {
                if (asset.kind() != SupplementalAsset.Kind.MUSIC) {
                    continue;
                }
                byte[] wav = bne.read(asset);
                WaveInfo info = waveInfo(wav);
                if (info == null) {
                    throw new IllegalStateException("Battle.net music " + asset.path()
                            + " is not a complete PCM WAVE recording");
                }
                if (info.bitsPerSample() != 16) {
                    throw new IllegalStateException("Battle.net music " + asset.path()
                            + " is " + info.bitsPerSample() + "-bit, expected 16-bit PCM");
                }
                String name = asset.path();
                if (name.regionMatches(true, 0, "music/", 0, "music/".length())) {
                    name = name.substring("music/".length());
                }
                if (name.toLowerCase(Locale.ROOT).endsWith(".wav")) {
                    name = name.substring(0, name.length() - ".wav".length());
                }
                found.add(new MusicTrack(name, info.sampleRate(), info.channels(), info.frames(),
                        bne.origin(asset)));
                sources.add(new BattleNetTrackSource(asset));
            }
            if (!found.isEmpty()) {
                BneMusicContract.validate(found);
                tracks = List.copyOf(found);
                trackSources = List.copyOf(sources);
                return;
            }
        }

        for (Path disc : CdAudio.discsUnder(install.root())) {
            try (CdAudio audio = CdAudio.open(disc)) {
                if (audio == null) {
                    continue;
                }
                String label = disc.getFileName().toString().replaceFirst("(?i)\\.img$", "");
                for (CdAudio.Track track : audio.musicTracks()) {
                    // Counted in sectors, not worked out from the duration. A
                    // sector is 2,352 bytes and holds exactly 588 stereo
                    // frames, so this is exact; going through seconds is not.
                    // Track 16 of Tides of Darkness runs 3,036 sectors, which
                    // is 40.48 seconds, which is not representable in binary,
                    // and 40.48 times 44,100 truncated comes out one frame
                    // short. The track then read two samples shorter than it
                    // is, which is inaudible and made a byte-for-byte
                    // comparison fail.
                    long frames = (long) (track.endLba() - track.startLba()) * FRAMES_PER_SECTOR;
                    found.add(new MusicTrack(label + " track " + track.number(),
                            CdAudio.SAMPLE_RATE, CdAudio.CHANNELS, frames));
                    sources.add(new DiscTrackSource(disc, track));
                }
            } catch (IOException e) {
                // One unreadable disc among however many are lying about.
                continue;
            }
        }
        tracks = List.copyOf(found);
        trackSources = List.copyOf(sources);
    }

    @Override
    public List<MusicTrack> musicTracks() {
        scanRecordings();
        return tracks;
    }

    @Override
    public short[] musicSamples(int index) {
        scanRecordings();
        if (index < 0 || index >= trackSources.size()) {
            return new short[0];
        }
        TrackSource source = trackSources.get(index);
        if (source instanceof BattleNetTrackSource named) {
            byte[] wav = supplementalAsset(named.asset());
            if (wav == null) {
                return new short[0];
            }
            try {
                Flac.Pcm pcm = Wav.decode(wav);
                short[] out = new short[pcm.samples().length];
                int scale = pcm.bitsPerSample() == 8 ? 256 : 1;
                for (int i = 0; i < out.length; i++) {
                    out[i] = (short) (pcm.samples()[i] * scale);
                }
                return out;
            } catch (RuntimeException e) {
                return new short[0];
            }
        }
        DiscTrackSource disc = (DiscTrackSource) source;
        try (CdAudio audio = CdAudio.open(disc.disc())) {
            return audio == null ? new short[0] : audio.read(disc.track());
        } catch (IOException e) {
            return new short[0];
        }
    }

    /** Reads the PCM description without expanding a whole soundtrack to ints. */
    private static WaveInfo waveInfo(byte[] wav) {
        if (wav == null || wav.length < 12 || !tag(wav, 0, "RIFF") || !tag(wav, 8, "WAVE")) {
            return null;
        }
        int channels = 0;
        int sampleRate = 0;
        int bits = 0;
        int dataBytes = -1;
        int cursor = 12;
        while (cursor + 8 <= wav.length) {
            int declared = readLe32(wav, cursor + 4);
            int body = cursor + 8;
            if (declared < 0) {
                return null;
            }
            int available = Math.min(declared, wav.length - body);
            if (tag(wav, cursor, "fmt ") && available >= 16) {
                if (readLe16(wav, body) != 1) {
                    return null;
                }
                channels = readLe16(wav, body + 2);
                sampleRate = readLe32(wav, body + 4);
                bits = readLe16(wav, body + 14);
            } else if (tag(wav, cursor, "data")) {
                dataBytes = available;
            }
            long next = (long) body + available + (available & 1);
            if (next <= cursor || next > wav.length) {
                break;
            }
            cursor = (int) next;
        }
        int bytesPerFrame = channels * (bits / 8);
        if (channels < 1 || channels > 2 || sampleRate <= 0
                || (bits != 8 && bits != 16) || dataBytes < 0 || bytesPerFrame <= 0) {
            return null;
        }
        return new WaveInfo(sampleRate, channels, bits, dataBytes / bytesPerFrame);
    }

    private static boolean tag(byte[] bytes, int at, String wanted) {
        if (at < 0 || at + wanted.length() > bytes.length) {
            return false;
        }
        for (int i = 0; i < wanted.length(); i++) {
            if ((bytes[at + i] & 0xFF) != wanted.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int readLe16(byte[] bytes, int at) {
        return (bytes[at] & 0xFF) | ((bytes[at + 1] & 0xFF) << 8);
    }

    private static int readLe32(byte[] bytes, int at) {
        return (bytes[at] & 0xFF) | ((bytes[at + 1] & 0xFF) << 8)
                | ((bytes[at + 2] & 0xFF) << 16) | ((bytes[at + 3] & 0xFF) << 24);
    }

    @Override
    public synchronized void close() {
        if (battleNetAssets != null) {
            battleNetAssets.close();
            battleNetAssets = null;
        }
        open.clear();
    }
}
