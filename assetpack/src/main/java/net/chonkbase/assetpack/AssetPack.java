package net.chonkbase.assetpack;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.chonkbase.assetpack.codec.Flac;
import net.chonkbase.assetpack.codec.IndexedPng;
import net.chonkbase.assetpack.codec.Opus;
import net.chonkbase.assetpack.codec.Wav;

/**
 * A pack, open for reading.
 *
 * <p>Nothing is read until it is asked for. A Warcraft II pack holds ninety
 * minutes of music and thirteen cutscenes; a reader that loaded eagerly would
 * spend half a gigabyte of heap to draw a menu.
 *
 * <p>Not thread-safe for concurrent reads of the same pack, because
 * {@link ZipFile} is not. The game loads assets from one thread and caches
 * them, so this has not needed to be more than that.
 */
public final class AssetPack implements AutoCloseable {

    private final Path source;
    private final ZipFile zip;
    private final PackManifest manifest;

    private AssetPack(Path source, ZipFile zip, PackManifest manifest) {
        this.source = source;
        this.zip = zip;
        this.manifest = manifest;
    }

    /** Opens a pack, or fails saying what is wrong with it. */
    public static AssetPack open(Path file) {
        ZipFile zip;
        try {
            zip = new ZipFile(file.toFile());
        } catch (IOException e) {
            throw new PackFormatException("cannot open pack " + file, e);
        }
        try {
            ZipEntry entry = zip.getEntry(PackManifest.MANIFEST_ENTRY);
            if (entry == null) {
                throw new PackFormatException(
                        file + " has no " + PackManifest.MANIFEST_ENTRY + ", so it is not a pack");
            }
            String text;
            try (InputStream stream = zip.getInputStream(entry)) {
                text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            return new AssetPack(file, zip, PackManifest.fromJson(text));
        } catch (IOException e) {
            closeQuietly(zip);
            throw new PackFormatException("cannot read " + PackManifest.MANIFEST_ENTRY
                    + " out of " + file, e);
        } catch (RuntimeException e) {
            closeQuietly(zip);
            throw e;
        }
    }

    /** Opens a pack, or returns null when the file is not one. */
    public static AssetPack tryOpen(Path file) {
        if (file == null || !java.nio.file.Files.isRegularFile(file)) {
            return null;
        }
        try {
            return open(file);
        } catch (PackFormatException e) {
            return null;
        }
    }

    /** Where this pack came from. */
    public Path source() {
        return source;
    }

    /** What is in it. */
    public PackManifest manifest() {
        return manifest;
    }

    /** The asset with this name, or {@code null}. */
    public PackAsset find(String id) {
        return manifest.find(id);
    }

    /**
     * The stored bytes of an asset, still in its codec's encoding.
     *
     * <p>Callers usually want {@link #picture} or {@link #audio} instead. This
     * is for the codecs the pack does not decode itself, and for a consumer
     * that wants to hand the payload straight to something else.
     */
    public byte[] bytes(PackAsset asset) {
        ZipEntry entry = zip.getEntry(asset.file());
        if (entry == null) {
            throw new PackFormatException("the manifest lists " + asset.id()
                    + " at " + asset.file() + ", and the pack has no such file");
        }
        try (InputStream stream = zip.getInputStream(entry)) {
            // Whatever is in the file, not what the manifest expected. The two
            // differ for exactly one reason that matters, and it is the reason
            // the format exists: somebody has replaced the art. An artist who
            // repaints a footman edits one PNG and puts it back, and refusing
            // it because the recorded length moved would mean recomputing a
            // size and a SHA-256 to change a pixel.
            //
            // Nothing is lost by allowing it. The zip carries a CRC per entry,
            // so a truncated or corrupted file still fails here; the manifest's
            // size and hash say which build an asset came from, which is a
            // question for a verification pass and not for the load path.
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + asset.file() + " from " + source, e);
        }
    }

    /** The stored bytes of the asset with this name, or {@code null}. */
    public byte[] bytes(String id) {
        PackAsset asset = manifest.find(id);
        return asset == null ? null : bytes(asset);
    }

    /**
     * Decodes a picture to its palette indices.
     *
     * @throws PackFormatException if the asset is not stored as a picture
     */
    public IndexedPng.Image picture(PackAsset asset) {
        if (asset.codec() != Codec.PNG_INDEXED) {
            throw new PackFormatException(asset.id() + " is stored as "
                    + asset.codec().id() + ", which is not a picture");
        }
        return IndexedPng.decode(bytes(asset));
    }

    /**
     * Decodes audio to interleaved samples.
     *
     * <p>What comes back is the <em>whole file</em>, at the sample rate and bit
     * depth the audio had before it was encoded, whatever codec it is stored
     * in. Both halves of that matter.
     *
     * <p>The rate is the original one even for Opus, which only ever decodes at
     * 48 kHz: {@link Opus} resamples back on the way out, and the rate it
     * resamples to is recorded in the Ogg identification header, so the pack
     * does not have to be consulted for it. The alternative -- handing back
     * 48 kHz and letting the consumer notice -- would mean a pack sounded
     * different from the installation it was built from, because the port's own
     * resamplers would stop running.
     *
     * <p>"The whole file" rather than "this asset's share of it" is what makes
     * an alias work. Several assets can name the same file and take different
     * windows of it, and the window is applied by the consumer that knows about
     * windows; trimming here to {@link PackAsset#sampleFrames} would truncate
     * the shared stream to the length of whichever alias asked for it.
     *
     * @throws PackFormatException if the asset is not stored as audio
     */
    public Flac.Pcm audio(PackAsset asset) {
        return switch (asset.codec()) {
            case FLAC -> Flac.decode(bytes(asset));
            case WAV -> Wav.decode(bytes(asset));
            // The depth is the one fact about the audio that an Ogg Opus file
            // has nowhere to record, so it comes from the manifest. Sixteen is
            // the right default: it is what the codec produces, and an asset
            // written by something that did not record a depth is not an 8-bit
            // one being silently widened, it is an unknown one being left alone.
            case OPUS -> Opus.decode(bytes(asset), asset.bitsPerSample() == 8 ? 8 : 16);
            default -> throw new PackFormatException(asset.id() + " is stored as "
                    + asset.codec().id() + ", which is not audio");
        };
    }

    /**
     * Whether an asset's bytes still hash to what the manifest recorded.
     *
     * <p>Not checked on every read. Hashing ninety minutes of music costs
     * seconds, and a zip already has a CRC per entry that catches the damage
     * this would; this is for a verification pass, not for the load path.
     */
    public boolean verify(PackAsset asset) {
        if (asset.sha256().isEmpty()) {
            return true;
        }
        return sha256(bytes(asset)).equals(asset.sha256());
    }

    /** The hex SHA-256 of some bytes, in the spelling the manifest uses. */
    public static String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16));
                out.append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("this JVM has no SHA-256", e);
        }
    }

    @Override
    public void close() {
        closeQuietly(zip);
    }

    private static void closeQuietly(ZipFile zip) {
        try {
            zip.close();
        } catch (IOException e) {
            // Closing a read-only zip cannot fail in a way a caller can act on.
        }
    }
}
