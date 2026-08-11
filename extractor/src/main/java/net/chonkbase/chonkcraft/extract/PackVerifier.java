package net.chonkbase.chonkcraft.extract;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import net.chonkbase.assetpack.Codec;
import net.chonkbase.assetpack.AssetKind;
import net.chonkbase.assetpack.PackAsset;
import net.chonkbase.assetpack.PackManifest;
import net.chonkbase.assetpack.codec.SignalToNoise;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.data.source.BneMusicContract;
import net.chonkbase.chonkcraft.data.source.EntryArchive;
import net.chonkbase.chonkcraft.data.source.EntryCodec;
import net.chonkbase.chonkcraft.data.source.PackSource;

/**
 * Reads a finished pack back and compares it against what it was built from.
 *
 * <p>This is the reason the format can be trusted. Everything else in the
 * extractor is an argument that a conversion is lossless; this is the
 * measurement. It opens the pack exactly as the game will, asks it for every
 * archive entry, every map and every music track, and asks the installation for
 * the same thing, and compares them in the terms that matter -- pixels for a
 * picture, samples for a sound, bytes for anything stored raw.
 *
 * <p>Deliberately not a unit test. It runs as part of the build so that the
 * claim being made is about the pack a player will actually receive rather than
 * about a fixture.
 *
 * <h2>Two questions, and the codec picks which</h2>
 *
 * <p>Most of the format is lossless and the question is bit-exactness. Opus is
 * not, and asking a lossy asset whether it came back identical would fail every
 * time; asking a lossless one whether it came back <em>close enough</em> would
 * pass a corrupt one. {@link Codec#lossless()} is what decides, so the choice is
 * a property of the asset in the manifest rather than a guess made here.
 *
 * <p>For a lossy asset the question becomes four: same sample rate, same
 * channel count, same duration to within a frame, and a signal-to-noise ratio
 * against the original above {@link SignalToNoise#MUSIC_FLOOR_DB} for music or
 * {@link SignalToNoise#SOUND_FLOOR_DB} for an effect. The first three are the
 * ones that catch real bugs -- a stream decoded at 48 kHz and served as 44,100,
 * a window taken from the wrong offset, a frame count lost in the rounding --
 * and they are exact answers, not tolerances. The decibels are the loose part,
 * and they are loose on purpose: waveform SNR under-rates a perceptual codec,
 * so the floor is set below what the encoder actually reaches and well above
 * the near-zero that any structural mistake produces.
 */
public final class PackVerifier {

    /** How many differences to name before saying "and so on". */
    private static final int REPORTED = 8;

    private PackVerifier() {
    }

    /** Completed verification checks and the exact number planned. */
    @FunctionalInterface
    public interface Progress extends BiConsumer<Integer, Integer> {
    }

    /**
     * Compares {@code packFile} against {@code original}.
     *
     * @return notes for the build report; empty when everything matched
     */
    public static List<String> verify(Path packFile, AssetSource original) {
        return verify(packFile, original, null);
    }

    /** Compares the pack while reporting exact completed verification checks. */
    public static List<String> verify(Path packFile, AssetSource original,
            Progress progress) {
        List<String> notes = new ArrayList<>();
        List<String> differences = new ArrayList<>();
        int checked = 0;
        int lossyEntries = 0;
        int lossyTracks = 0;
        double worstSoundDb = Double.POSITIVE_INFINITY;
        double worstMusicDb = Double.POSITIVE_INFINITY;

        try (PackSource packed = PackSource.open(packFile)) {
            PackManifest manifest = packed.pack().manifest();
            int archiveChecks = manifest.archives().stream()
                    .mapToInt(archive -> archive.slots().length).sum();
            int totalChecks = archiveChecks
                    + original.mapNames().size()
                    + (int) original.supplementalAssets().stream()
                            .filter(asset -> asset.kind()
                                    == AssetSource.SupplementalAsset.Kind.TEXT
                                    || asset.kind()
                                    == AssetSource.SupplementalAsset.Kind.BINARY)
                            .count()
                    + original.musicTracks().size()
                    + manifest.assets().size();
            ProgressCounter progressCounter = new ProgressCounter(progress, totalChecks);

            for (PackManifest.Archive archive : manifest.archives()) {
                EntryArchive fromPack = packed.archive(archive.id());
                EntryArchive fromInstall = original.archive(archive.id());
                if (fromInstall == null) {
                    differences.add("archive " + archive.id()
                            + " is in the pack and not in the installation");
                    progressCounter.skip(archive.slots().length);
                    continue;
                }
                if (fromPack.entryCount() != fromInstall.entryCount()) {
                    differences.add("archive " + archive.name() + " has "
                            + fromPack.entryCount() + " entries in the pack and "
                            + fromInstall.entryCount() + " in the installation");
                }
                int count = Math.min(fromPack.entryCount(), fromInstall.entryCount());
                for (int entry = 0; entry < count; entry++) {
                    try {
                        if (fromPack.isValid(entry) != fromInstall.isValid(entry)) {
                            differences.add(archive.name() + " entry " + entry
                                    + " is valid in one and not the other");
                            continue;
                        }
                        if (!fromInstall.isValid(entry)) {
                            continue;
                        }
                        checked++;
                        int slot = archive.slots()[entry];
                        PackAsset asset = manifest.at(slot);
                        EntryCodec.Form form = EntryCodec.Form.of(asset.string("form", "raw"));
                        byte[] a = fromInstall.entry(entry);
                        byte[] b = fromPack.entry(entry);
                        if (asset.codec().lossless()) {
                            if (!EntryCodec.equivalent(form, a, b)) {
                                differences.add(archive.name() + " entry " + entry
                                        + " (" + asset.id() + ", " + form.id()
                                        + ") does not decode to the same thing");
                            }
                            continue;
                        }
                        // The same question the build asked before it agreed to
                        // store this asset lossily, asked again of the bytes that
                        // actually reached the file.
                        EntryCodec.SoundMatch match = EntryCodec.compareSound(a, b);
                        lossyEntries++;
                        if (!match.acceptable()) {
                            differences.add(archive.name() + " entry " + entry + " ("
                                    + asset.id() + ", " + asset.codec().id() + ") came back "
                                    + match.describe() + ", under the "
                                    + SignalToNoise.SOUND_FLOOR_DB + " dB floor");
                        } else {
                            worstSoundDb = Math.min(worstSoundDb, match.snrDb());
                        }
                    } finally {
                        progressCounter.step();
                    }
                }
                progressCounter.skip(archive.slots().length - count);
            }

            List<String> packMaps = packed.mapNames();
            List<String> installMaps = original.mapNames();
            if (!packMaps.equals(installMaps)) {
                differences.add("the pack offers " + packMaps.size()
                        + " maps and the installation offers " + installMaps.size()
                        + ", or in a different order");
            }
            for (String name : installMaps) {
                byte[] a = original.map(name);
                byte[] b = packed.map(name);
                checked++;
                if (!Arrays.equals(a, b)) {
                    differences.add("map " + name + " differs");
                }
                progressCounter.step();
            }

            List<AssetSource.SupplementalAsset> named = original.supplementalAssets().stream()
                    .filter(asset -> asset.kind() == AssetSource.SupplementalAsset.Kind.TEXT
                            || asset.kind() == AssetSource.SupplementalAsset.Kind.BINARY)
                    .toList();
            if (!packed.supplementalAssets().equals(named)) {
                differences.add("the pack has " + packed.supplementalAssets().size()
                        + " standalone supplemental files and the installation has "
                        + named.size() + ", or in a different order");
            }
            for (AssetSource.SupplementalAsset asset : named) {
                checked++;
                if (!Arrays.equals(original.supplementalAsset(asset),
                        packed.supplementalAsset(asset))) {
                    differences.add("supplemental file " + asset.path() + " differs");
                }
                progressCounter.step();
            }

            List<AssetSource.MusicTrack> packTracks = packed.musicTracks();
            List<AssetSource.MusicTrack> installTracks = original.musicTracks();
            // The assets behind those tracks, in the order the discs list them,
            // which is the order PackSource numbers them in. Needed because the
            // question to ask of a track depends on the codec it is stored in,
            // and MusicTrack does not carry one.
            List<PackAsset> trackAssets = new ArrayList<>();
            for (PackManifest.Disc disc : manifest.discs()) {
                for (int index : disc.tracks()) {
                    trackAssets.add(manifest.at(index));
                }
            }
            if (original.isBattleNetEdition()) {
                try {
                    BneMusicContract.validate(installTracks);
                    BneMusicContract.validate(packTracks);
                } catch (IllegalStateException invalid) {
                    differences.add(invalid.getMessage());
                }
                long logicalMusic = manifest.assets().stream()
                        .filter(asset -> asset.kind() == AssetKind.MUSIC).count();
                if (logicalMusic != BneMusicContract.TRACKS) {
                    differences.add("the BNE pack has " + logicalMusic
                            + " logical music assets, expected " + BneMusicContract.TRACKS);
                }
                for (PackAsset asset : trackAssets) {
                    if (asset.codec() != Codec.OPUS
                            || asset.sampleRate() != BneMusicContract.SAMPLE_RATE
                            || asset.channels() != BneMusicContract.CHANNELS
                            || asset.bitsPerSample() != BneMusicContract.BITS_PER_SAMPLE
                            || asset.integer("decodeSampleRate", 0)
                                    != BneMusicContract.OPUS_DECODE_RATE
                            || asset.integer("bitrateBps", 0)
                                    != BneMusicContract.OPUS_BITRATE_BPS) {
                        differences.add("BNE music asset " + asset.id()
                                + " does not satisfy the Opus metadata contract");
                    }
                }
            }
            if (packTracks.size() != installTracks.size()) {
                differences.add("the pack has " + packTracks.size() + " music tracks and the"
                        + " installation has " + installTracks.size());
            }
            for (int i = 0; i < Math.min(packTracks.size(), installTracks.size()); i++) {
                try {
                    AssetSource.MusicTrack fromInstall = installTracks.get(i);
                    AssetSource.MusicTrack fromPack = packTracks.get(i);
                    String name = fromInstall.name();
                    if (!fromPack.name().equals(name)) {
                        differences.add("music track " + i + " is called \"" + fromPack.name()
                                + "\" in the pack and \"" + name + "\" in the installation");
                    }
                // The three facts that are exact whatever the codec did. A track
                // served at the wrong rate plays at the wrong speed, a track
                // served with the wrong channel count plays at half or double
                // length, and a track a frame short is a window taken from the
                // wrong place.
                    if (fromPack.sampleRate() != fromInstall.sampleRate()) {
                        differences.add("music track \"" + name + "\" is offered at "
                                + fromPack.sampleRate() + " Hz by the pack and "
                                + fromInstall.sampleRate() + " Hz by the installation");
                    }
                    if (fromPack.channels() != fromInstall.channels()) {
                        differences.add("music track \"" + name + "\" has "
                                + fromPack.channels() + " channels in the pack and "
                                + fromInstall.channels() + " in the installation");
                    }
                    checked++;
                    short[] a = original.musicSamples(i);
                    short[] b = packed.musicSamples(i);
                    int channels = Math.max(1, fromInstall.channels());
                    if (Math.abs(a.length - b.length) > channels) {
                        differences.add("music track \"" + name + "\" is "
                                + (a.length / channels) + " frames on the disc and "
                                + (b.length / channels) + " in the pack, which is more than"
                                + " the one frame a codec is allowed to round by");
                        continue;
                    }
                    PackAsset asset = trackAssets.size() > i ? trackAssets.get(i) : null;
                    if (asset == null || asset.codec().lossless()) {
                        if (!Arrays.equals(a, b)) {
                            differences.add("music track \"" + name + "\" differs: " + a.length
                                    + " samples against " + b.length + (a.length == b.length
                                    ? ", same length but different values" : ""));
                        }
                        continue;
                    }
                    lossyTracks++;
                // Compared over the frames both have, which after the length
                // check above is all but at most one. Padding the shorter one
                // with silence instead would charge the codec for a frame it was
                // allowed to round away.
                    int common = Math.min(a.length, b.length);
                    double db = SignalToNoise.db(
                            Arrays.copyOf(a, common), Arrays.copyOf(b, common));
                    if (db < SignalToNoise.MUSIC_FLOOR_DB) {
                        differences.add("music track \"" + name + "\" came back at "
                                + SignalToNoise.describe(db) + ", under the "
                                + SignalToNoise.MUSIC_FLOOR_DB + " dB floor");
                    } else {
                        worstMusicDb = Math.min(worstMusicDb, db);
                    }
                } finally {
                    progressCounter.step();
                }
            }
            progressCounter.skip(installTracks.size()
                    - Math.min(packTracks.size(), installTracks.size()));

            for (PackAsset asset : manifest.assets()) {
                if (!packed.pack().verify(asset)) {
                    differences.add(asset.id() + " does not hash to what the manifest records");
                }
                progressCounter.step();
            }
        }

        if (differences.isEmpty()) {
            int exact = checked - lossyEntries - lossyTracks;
            notes.add("verified: " + exact + " of " + checked + " assets read back out of the"
                    + " pack decode to exactly what the installation decodes to");
            if (lossyEntries + lossyTracks > 0) {
                // Named separately and never folded into the line above. A
                // reader of a build report has to be able to see how much of the
                // pack was checked for equality and how much only for closeness,
                // because those are different guarantees.
                notes.add("the other " + (lossyEntries + lossyTracks) + " are stored lossily and"
                        + " were checked for rate, channels, length and signal to noise instead:"
                        + " " + lossyEntries + " sound effects, worst "
                        + SignalToNoise.describe(worstSoundDb) + " against a floor of "
                        + SignalToNoise.SOUND_FLOOR_DB + " dB; " + lossyTracks
                        + " music tracks, worst " + SignalToNoise.describe(worstMusicDb)
                        + " against a floor of " + SignalToNoise.MUSIC_FLOOR_DB + " dB");
            }
        } else {
            notes.add("VERIFICATION FAILED: " + differences.size() + " of " + checked
                    + " assets differ");
            notes.addAll(differences.subList(0, Math.min(REPORTED, differences.size())));
            if (differences.size() > REPORTED) {
                notes.add("and " + (differences.size() - REPORTED) + " more");
            }
        }
        return notes;
    }

    private static final class ProgressCounter {

        private final Progress progress;
        private final int total;
        private int completed;

        private ProgressCounter(Progress progress, int total) {
            this.progress = progress;
            this.total = Math.max(1, total);
            if (progress != null) {
                progress.accept(0, this.total);
            }
        }

        private void step() {
            skip(1);
        }

        private void skip(int count) {
            completed = Math.min(total, completed + Math.max(0, count));
            if (progress != null) {
                progress.accept(completed, total);
            }
        }
    }
}
