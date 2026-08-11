package net.chonkbase.assetpack.codec.opus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * The official Opus conformance vectors, and the two gates they support.
 *
 * <p>RFC 6716 section 6 defines conformance against these twelve streams. They
 * are 101 MB and are not in this repository; point at an unpacked copy with
 * {@code -Dopus.testvectors=/path/to/opus_testvectors} or the environment
 * variable {@code OPUS_TESTVECTORS}, and every test that needs them skips when
 * it is absent.
 *
 * <pre>
 *   curl -O https://opus-codec.org/static/testvectors/opus_testvectors.tar.gz
 *   tar xzf opus_testvectors.tar.gz
 *   mvn -pl assetpack test -Dopus.testvectors="$PWD/opus_testvectors"
 * </pre>
 *
 * <p>Two things can be checked against them, and the first is worth far more
 * than it looks.
 *
 * <p><b>The range state.</b> Each packet in a {@code .bit} file is stored with
 * the value the encoder's range coder held when it finished that packet. A
 * decoder that has read every symbol correctly ends the packet holding exactly
 * the same value, and one that has misread a single symbol anywhere does not.
 * This is an integer comparison, so unlike the audio it is unaffected by
 * floating-point rounding: it is a bit-exact test of the entropy layer and of
 * every table and probability model above it. It also localises a fault to one
 * packet instead of to a stream.
 *
 * <p><b>The audio.</b> The {@code .dec} file is the reference decoder's output
 * at 48 kHz. RFC 6716 does not require bit-exactness here, because the codec is
 * specified with floating-point arithmetic and two conforming decoders may
 * differ in the last bit; the reference tool applies a quality threshold
 * instead. So this exposes a signal-to-noise figure rather than an equality.
 */
public final class OpusTestVectors {

    /** One packet as {@code opus_demo} wrote it out. */
    public record Packet(byte[] payload, long expectedFinalRange, int index) {}

    /** A whole vector: its packets and the reference decoder's output. */
    public record Vector(String name, List<Packet> packets, Path decodedPath) {

        /** The reference PCM, interleaved 16-bit at 48 kHz. */
        public short[] reference() {
            try {
                byte[] raw = Files.readAllBytes(decodedPath);
                ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
                short[] pcm = new short[raw.length / 2];
                buffer.asShortBuffer().get(pcm);
                return pcm;
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + decodedPath, e);
            }
        }
    }

    private OpusTestVectors() {
    }

    /** Where the vectors are, or {@code null} when this machine has none. */
    public static Path directory() {
        String configured = System.getProperty("opus.testvectors");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("OPUS_TESTVECTORS");
        }
        if (configured == null || configured.isBlank()) {
            return null;
        }
        Path path = Paths.get(configured);
        return Files.isDirectory(path) ? path : null;
    }

    /** The message a skipped test states, matching the convention CI greps for. */
    public static String skipReason() {
        return "needs the Opus conformance vectors: download opus_testvectors.tar.gz from"
                + " opus-codec.org and set -Dopus.testvectors or OPUS_TESTVECTORS";
    }

    /** Loads one numbered vector, or {@code null} if it is not there. */
    public static Vector load(int number) {
        Path directory = directory();
        if (directory == null) {
            return null;
        }
        String name = String.format("testvector%02d", number);
        Path bits = directory.resolve(name + ".bit");
        Path decoded = directory.resolve(name + ".dec");
        if (!Files.isRegularFile(bits) || !Files.isRegularFile(decoded)) {
            return null;
        }
        return new Vector(name, readPackets(bits), decoded);
    }

    /**
     * Reads the packet stream.
     *
     * <p>The format {@code opus_demo} writes is a flat sequence of records: a
     * big-endian byte count, the big-endian final range, then the payload.
     * There is no header and no index.
     */
    public static List<Packet> readPackets(Path file) {
        byte[] raw;
        try {
            raw = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        List<Packet> packets = new ArrayList<>();
        int at = 0;
        while (at + 8 <= raw.length) {
            long length = readBe32(raw, at);
            long range = readBe32(raw, at + 4);
            at += 8;
            if (length < 0 || at + length > raw.length) {
                throw new IllegalStateException(file + ": packet " + packets.size()
                        + " declares " + length + " bytes and only "
                        + (raw.length - at) + " remain");
            }
            byte[] payload = new byte[(int) length];
            System.arraycopy(raw, at, payload, 0, (int) length);
            at += (int) length;
            packets.add(new Packet(payload, range, packets.size()));
        }
        if (at != raw.length) {
            throw new IllegalStateException(file + ": " + (raw.length - at)
                    + " trailing bytes that are not a packet record");
        }
        return packets;
    }

    private static long readBe32(byte[] data, int at) {
        return ((long) (data[at] & 0xFF) << 24)
                | ((long) (data[at + 1] & 0xFF) << 16)
                | ((long) (data[at + 2] & 0xFF) << 8)
                | (data[at + 3] & 0xFF);
    }

    /**
     * Signal-to-noise ratio between a decode and the reference, in decibels.
     *
     * <p>Infinity when they are identical. A conforming float decoder lands
     * somewhere very high rather than at infinity; anything below about 60 dB
     * on this material is a real defect rather than rounding.
     */
    public static double snrDb(short[] reference, short[] actual) {
        int n = Math.min(reference.length, actual.length);
        double signal = 0;
        double noise = 0;
        for (int i = 0; i < n; i++) {
            double r = reference[i];
            double d = r - actual[i];
            signal += r * r;
            noise += d * d;
        }
        if (noise == 0) {
            return Double.POSITIVE_INFINITY;
        }
        if (signal == 0) {
            return Double.NEGATIVE_INFINITY;
        }
        return 10 * Math.log10(signal / noise);
    }

    /** The largest absolute difference between a decode and the reference. */
    public static int maxDeviation(short[] reference, short[] actual) {
        int n = Math.min(reference.length, actual.length);
        int worst = 0;
        for (int i = 0; i < n; i++) {
            worst = Math.max(worst, Math.abs(reference[i] - actual[i]));
        }
        return worst;
    }
}
