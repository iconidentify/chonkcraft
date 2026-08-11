package net.chonkbase.assetpack.codec.opus;

import java.nio.charset.StandardCharsets;

/**
 * The identification header that opens an Ogg Opus stream.
 *
 * <p>RFC 7845 section 5.1. Nineteen bytes for the ordinary case: the magic
 * {@code "OpusHead"}, a version, a channel count, a 16-bit pre-skip, a 32-bit
 * input sample rate, a 16-bit output gain and a channel mapping family, all
 * little-endian. It sits alone on the first page of the logical stream and must
 * complete on that page.
 *
 * <p>Three of those fields decide whether the file plays back as the audio that
 * went in, and none of them is checkable by ear on a short clip.
 *
 * <p><b>Pre-skip</b> is the number of 48 kHz samples the decoder must produce
 * and throw away before the real audio starts, because the encoder delayed its
 * input to give the MDCT overlap and the SILK resampler something to converge
 * on. Write zero here and the file plays a few milliseconds of the encoder's
 * warm-up -- a soft click at the top of every sound effect, and every effect in
 * the game starts 6.5 ms late, which over a battle full of overlapping hits is
 * audible as a smear rather than as a delay.
 *
 * <p><b>Input sample rate</b> is metadata and nothing else: RFC 7845 section
 * 5.1 says outright that it "is _not_ the sample rate to use for playback".
 * Opus always decodes at 48 kHz. It is recorded so that a tool converting back
 * to PCM can return a file at the rate the user handed it, and a reader that
 * uses it to configure an audio device plays everything at the wrong pitch.
 *
 * <p><b>Output gain</b> is Q7.8 dB applied at decode. A muxer should write zero
 * and bake any gain into the samples; this one always writes zero, and reads
 * whatever is there so that a file produced elsewhere is not silently played
 * at the wrong volume.
 */
final class OpusHead {

    /** {@code "OpusHead"}. Starting with {@code "Op"} is an invalid Opus TOC byte, so a header can never be mistaken for audio. */
    static final byte[] MAGIC = {'O', 'p', 'u', 's', 'H', 'e', 'a', 'd'};

    /** The fixed part: magic, version, channels, pre-skip, input rate, gain, mapping family. */
    static final int FIXED_SIZE = 19;

    /**
     * The version this writer emits, and the highest major version a reader accepts.
     *
     * <p>RFC 7845 section 5.1 splits the octet into major and minor halves and
     * says an implementation SHOULD accept anything up to 15 and assume 16 or
     * greater is incompatible, so the check below is on the upper nibble rather
     * than on equality. It also explains why the first version is 1 rather than
     * 0: a zero there would let {@code "OpusHead"} read as a null-terminated C
     * string and every implementation would start relying on it.
     */
    static final int VERSION = 1;

    private final int version;
    private final int channels;
    private final int preSkip;
    private final int inputSampleRate;
    private final int outputGain;
    private final int mappingFamily;
    private final int streamCount;
    private final int coupledStreamCount;
    private final int[] channelMapping;

    private OpusHead(int version, int channels, int preSkip, int inputSampleRate, int outputGain,
            int mappingFamily, int streamCount, int coupledStreamCount, int[] channelMapping) {
        this.version = version;
        this.channels = channels;
        this.preSkip = preSkip;
        this.inputSampleRate = inputSampleRate;
        this.outputGain = outputGain;
        this.mappingFamily = mappingFamily;
        this.streamCount = streamCount;
        this.coupledStreamCount = coupledStreamCount;
        this.channelMapping = channelMapping;
    }

    /**
     * A mapping family 0 header, the mono or stereo case.
     *
     * <p>RFC 7845 section 5.1.1.1 allows only one or two channels in family 0
     * and forbids the channel mapping table, which is why this is the only
     * shape the writer produces. Anything wider needs family 1 and a mapping
     * table, and nothing in a game pack does.
     */
    static OpusHead family0(int channels, int preSkip, int inputSampleRate, int outputGain) {
        if (channels != 1 && channels != 2) {
            throw new IllegalArgumentException(
                    "channel mapping family 0 carries 1 or 2 channels, not " + channels
                    + "; wider layouts need family 1 and a channel mapping table");
        }
        if (preSkip < 0 || preSkip > 0xffff) {
            throw new IllegalArgumentException("pre-skip is a 16-bit field, not " + preSkip);
        }
        if (inputSampleRate < 0) {
            throw new IllegalArgumentException("input sample rate is unsigned, not " + inputSampleRate);
        }
        if (outputGain < Short.MIN_VALUE || outputGain > Short.MAX_VALUE) {
            throw new IllegalArgumentException("output gain is a signed 16-bit Q7.8 value, not " + outputGain);
        }
        return new OpusHead(VERSION, channels, preSkip, inputSampleRate, outputGain, 0, 1,
                channels == 2 ? 1 : 0, null);
    }

    /** The 19 bytes (or more, with a mapping table) exactly as they go on the wire. */
    byte[] encode() {
        int size = FIXED_SIZE + (channelMapping == null ? 0 : 2 + channelMapping.length);
        byte[] bytes = new byte[size];
        System.arraycopy(MAGIC, 0, bytes, 0, MAGIC.length);
        bytes[8] = (byte) version;
        bytes[9] = (byte) channels;
        writeLe16(bytes, 10, preSkip);
        writeLe32(bytes, 12, inputSampleRate);
        writeLe16(bytes, 16, outputGain);
        bytes[18] = (byte) mappingFamily;
        if (channelMapping != null) {
            bytes[19] = (byte) streamCount;
            bytes[20] = (byte) coupledStreamCount;
            for (int i = 0; i < channelMapping.length; i++) {
                bytes[21 + i] = (byte) channelMapping[i];
            }
        }
        return bytes;
    }

    /**
     * Reads a header packet, rejecting everything RFC 7845 section 5.1 says is invalid.
     *
     * <p>Every length is checked before it is used. A header that claims a
     * mapping table it does not carry is the shortest path from a hand-edited
     * file to an exception thrown out of the audio thread with no clue in it.
     */
    static OpusHead parse(byte[] packet) {
        if (packet.length < FIXED_SIZE) {
            throw new OggException("the identification header is " + packet.length
                    + " bytes; RFC 7845 section 5.1 needs at least " + FIXED_SIZE);
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (packet[i] != MAGIC[i]) {
                throw new OggException("the first packet is not an Opus identification header:"
                        + " expected the magic \"OpusHead\", found "
                        + new String(packet, 0, Math.min(8, packet.length), StandardCharsets.ISO_8859_1));
            }
        }
        int version = packet[8] & 0xff;
        if ((version >>> 4) != 0) {
            throw new OggException("identification header version " + version
                    + " has a major version this reader does not know; RFC 7845 section 5.1"
                    + " says treat 16 and above as incompatible");
        }
        int channels = packet[9] & 0xff;
        if (channels == 0) {
            throw new OggException("the identification header declares zero output channels");
        }
        int preSkip = readLe16(packet, 10);
        int inputSampleRate = readLe32(packet, 12);
        int outputGain = (short) readLe16(packet, 16);
        int mappingFamily = packet[18] & 0xff;

        if (mappingFamily == 0) {
            if (channels > 2) {
                throw new OggException("channel mapping family 0 carries at most 2 channels,"
                        + " and this header declares " + channels);
            }
            return new OpusHead(version, channels, preSkip, inputSampleRate, outputGain, 0, 1,
                    channels == 2 ? 1 : 0, null);
        }

        // Families 1 and 255 carry a mapping table: a stream count, a coupled
        // stream count, and one byte per output channel. Parsed rather than
        // rejected so that a 5.1 file produced elsewhere reports its channel
        // count instead of failing to open, even though nothing here writes one.
        int tableAt = FIXED_SIZE;
        if (packet.length < tableAt + 2 + channels) {
            throw new OggException("channel mapping family " + mappingFamily + " needs a "
                    + (2 + channels) + "-byte mapping table and the header is only "
                    + packet.length + " bytes");
        }
        int streamCount = packet[tableAt] & 0xff;
        int coupledCount = packet[tableAt + 1] & 0xff;
        if (streamCount < 1) {
            throw new OggException("the channel mapping table declares zero streams");
        }
        if (coupledCount > streamCount) {
            throw new OggException("the channel mapping table couples " + coupledCount
                    + " of " + streamCount + " streams");
        }
        int[] mapping = new int[channels];
        for (int i = 0; i < channels; i++) {
            mapping[i] = packet[tableAt + 2 + i] & 0xff;
        }
        return new OpusHead(version, channels, preSkip, inputSampleRate, outputGain, mappingFamily,
                streamCount, coupledCount, mapping);
    }

    int version() {
        return version;
    }

    int channels() {
        return channels;
    }

    int preSkip() {
        return preSkip;
    }

    int inputSampleRate() {
        return inputSampleRate;
    }

    int outputGain() {
        return outputGain;
    }

    int mappingFamily() {
        return mappingFamily;
    }

    int streamCount() {
        return streamCount;
    }

    int coupledStreamCount() {
        return coupledStreamCount;
    }

    private static void writeLe16(byte[] bytes, int at, int value) {
        bytes[at] = (byte) value;
        bytes[at + 1] = (byte) (value >>> 8);
    }

    private static void writeLe32(byte[] bytes, int at, int value) {
        bytes[at] = (byte) value;
        bytes[at + 1] = (byte) (value >>> 8);
        bytes[at + 2] = (byte) (value >>> 16);
        bytes[at + 3] = (byte) (value >>> 24);
    }

    private static int readLe16(byte[] bytes, int at) {
        return (bytes[at] & 0xff) | ((bytes[at + 1] & 0xff) << 8);
    }

    private static int readLe32(byte[] bytes, int at) {
        return (bytes[at] & 0xff)
                | ((bytes[at + 1] & 0xff) << 8)
                | ((bytes[at + 2] & 0xff) << 16)
                | ((bytes[at + 3] & 0xff) << 24);
    }
}
