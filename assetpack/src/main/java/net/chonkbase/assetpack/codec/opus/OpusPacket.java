package net.chonkbase.assetpack.codec.opus;

import java.util.ArrayList;
import java.util.List;

/**
 * One Opus packet, split into the frames it carries.
 *
 * <p>A port of the internal framing defined in RFC 6716 section 3. This is the
 * outermost layer of the format and the only part of it that is not
 * entropy-coded: a table-of-contents byte says which of the codec's three
 * modes, which bandwidth, and which frame size the packet uses, and then two
 * bits say how many frames follow and how their lengths are written down.
 *
 * <p>Deliberately strict about the things RFC 6716 section 3.4 says a decoder
 * must reject, and deliberately tolerant of nothing else. A malformed packet
 * that is quietly accepted here becomes a range decoder reading past the end
 * of its buffer, and the symptom of that is not a crash but a burst of noise
 * in the middle of a music track.
 */
public final class OpusPacket {

    /** Which of the three codecs the payload uses. */
    public enum Mode {
        /** Linear prediction, for speech. Configurations 0 to 11. */
        SILK,
        /** Both layers, SILK below 8 kHz and CELT above. Configurations 12 to 15. */
        HYBRID,
        /** The MDCT layer alone, for music and low delay. Configurations 16 to 31. */
        CELT
    }

    /** The audio bandwidth a configuration selects. */
    public enum Bandwidth {
        NARROWBAND(4_000, 8_000),
        MEDIUMBAND(6_000, 12_000),
        WIDEBAND(8_000, 16_000),
        SUPERWIDEBAND(12_000, 24_000),
        FULLBAND(20_000, 48_000);

        private final int cutoffHz;
        private final int rateHz;

        Bandwidth(int cutoffHz, int rateHz) {
            this.cutoffHz = cutoffHz;
            this.rateHz = rateHz;
        }

        /** The highest frequency this bandwidth carries. */
        public int cutoffHz() {
            return cutoffHz;
        }

        /** The sample rate the layer runs at internally. */
        public int rateHz() {
            return rateHz;
        }
    }

    /** Thrown when the bytes are not a packet a decoder may accept. */
    public static final class MalformedPacketException extends RuntimeException {
        MalformedPacketException(String message) {
            super(message);
        }
    }

    /**
     * Frame durations in microseconds, indexed by configuration number.
     *
     * <p>From the table in RFC 6716 section 3.1. The three mode families count
     * differently and there is no formula: SILK offers 10, 20, 40 and 60 ms,
     * hybrid only 10 and 20, and CELT 2.5, 5, 10 and 20. Written out because a
     * clever derivation of this table is how a decoder ends up producing the
     * right samples at the wrong rate.
     */
    private static final int[] FRAME_MICROS = {
        // 0-11: SILK, four sizes each for NB, MB, WB
        10_000, 20_000, 40_000, 60_000,
        10_000, 20_000, 40_000, 60_000,
        10_000, 20_000, 40_000, 60_000,
        // 12-15: hybrid, SWB then FB, 10 and 20 ms
        10_000, 20_000,
        10_000, 20_000,
        // 16-31: CELT, four sizes each for NB, WB, SWB, FB
        2_500, 5_000, 10_000, 20_000,
        2_500, 5_000, 10_000, 20_000,
        2_500, 5_000, 10_000, 20_000,
        2_500, 5_000, 10_000, 20_000,
    };

    /** Bandwidth by configuration number, same table. */
    private static final Bandwidth[] BANDWIDTHS = {
        Bandwidth.NARROWBAND, Bandwidth.NARROWBAND, Bandwidth.NARROWBAND, Bandwidth.NARROWBAND,
        Bandwidth.MEDIUMBAND, Bandwidth.MEDIUMBAND, Bandwidth.MEDIUMBAND, Bandwidth.MEDIUMBAND,
        Bandwidth.WIDEBAND, Bandwidth.WIDEBAND, Bandwidth.WIDEBAND, Bandwidth.WIDEBAND,
        Bandwidth.SUPERWIDEBAND, Bandwidth.SUPERWIDEBAND,
        Bandwidth.FULLBAND, Bandwidth.FULLBAND,
        Bandwidth.NARROWBAND, Bandwidth.NARROWBAND, Bandwidth.NARROWBAND, Bandwidth.NARROWBAND,
        Bandwidth.WIDEBAND, Bandwidth.WIDEBAND, Bandwidth.WIDEBAND, Bandwidth.WIDEBAND,
        Bandwidth.SUPERWIDEBAND, Bandwidth.SUPERWIDEBAND,
        Bandwidth.SUPERWIDEBAND, Bandwidth.SUPERWIDEBAND,
        Bandwidth.FULLBAND, Bandwidth.FULLBAND, Bandwidth.FULLBAND, Bandwidth.FULLBAND,
    };

    /**
     * The largest a packet may be.
     *
     * <p>RFC 6716 section 3.4: a frame may not exceed 1275 bytes, which is what
     * 20 ms at the codec's 510 kb/s ceiling comes to.
     */
    public static final int MAX_FRAME_BYTES = 1275;

    /** The most frames a single packet may carry: 120 ms at 2.5 ms each. */
    public static final int MAX_FRAMES = 48;

    private final int config;
    private final boolean stereo;
    private final byte[] data;
    private final int[] frameOffset;
    private final int[] frameLength;

    private OpusPacket(int config, boolean stereo, byte[] data,
            int[] frameOffset, int[] frameLength) {
        this.config = config;
        this.stereo = stereo;
        this.data = data;
        this.frameOffset = frameOffset;
        this.frameLength = frameLength;
    }

    /**
     * Splits a packet into its frames.
     *
     * @throws MalformedPacketException if the packet is one RFC 6716 section
     *                                  3.4 requires a decoder to reject
     */
    public static OpusPacket parse(byte[] data) {
        return parse(data, 0, data.length);
    }

    /** Splits a packet held inside a larger buffer. */
    public static OpusPacket parse(byte[] data, int from, int length) {
        if (length < 1) {
            throw new MalformedPacketException("an Opus packet must have at least a TOC byte");
        }
        int toc = data[from] & 0xFF;
        int config = toc >>> 3;
        boolean stereo = (toc & 0x04) != 0;
        int code = toc & 0x03;

        List<int[]> frames = new ArrayList<>();
        int at = from + 1;
        int end = from + length;

        switch (code) {
            case 0 -> {
                // One frame, occupying the rest of the packet.
                frames.add(new int[] {at, end - at});
            }
            case 1 -> {
                // Two frames of equal size, so the remainder must be even.
                int remaining = end - at;
                if ((remaining & 1) != 0) {
                    throw new MalformedPacketException(
                            "code 1 packet has an odd payload of " + remaining
                            + " bytes, which cannot split into two equal frames");
                }
                int each = remaining / 2;
                frames.add(new int[] {at, each});
                frames.add(new int[] {at + each, each});
            }
            case 2 -> {
                // Two frames, the first one's length written out.
                int[] read = readLength(data, at, end);
                int first = read[0];
                at = read[1];
                if (at + first > end) {
                    throw new MalformedPacketException(
                            "code 2 packet says its first frame is " + first
                            + " bytes and only " + (end - at) + " remain");
                }
                frames.add(new int[] {at, first});
                frames.add(new int[] {at + first, end - at - first});
            }
            case 3 -> parseCode3(data, at, end, frames);
            default -> throw new IllegalStateException("unreachable code " + code);
        }

        int[] offsets = new int[frames.size()];
        int[] lengths = new int[frames.size()];
        for (int i = 0; i < frames.size(); i++) {
            offsets[i] = frames.get(i)[0];
            lengths[i] = frames.get(i)[1];
            if (lengths[i] < 0) {
                throw new MalformedPacketException("frame " + i + " has a negative length");
            }
            if (lengths[i] > MAX_FRAME_BYTES) {
                throw new MalformedPacketException("frame " + i + " is " + lengths[i]
                        + " bytes, over the " + MAX_FRAME_BYTES + "-byte limit");
            }
            if (offsets[i] + lengths[i] > end) {
                throw new MalformedPacketException("frame " + i + " runs past the end of the packet");
            }
        }
        return new OpusPacket(config, stereo, data, offsets, lengths);
    }

    /**
     * Code 3: a count byte, then optionally a padding count, then either equal
     * frames or an explicit length per frame.
     */
    private static void parseCode3(byte[] data, int at, int end, List<int[]> frames) {
        if (at >= end) {
            throw new MalformedPacketException("code 3 packet has no frame count byte");
        }
        int count = data[at++] & 0xFF;
        boolean variable = (count & 0x80) != 0;
        boolean padded = (count & 0x40) != 0;
        int frameCount = count & 0x3F;

        if (frameCount < 1) {
            throw new MalformedPacketException("code 3 packet declares no frames");
        }
        if (frameCount > MAX_FRAMES) {
            throw new MalformedPacketException("code 3 packet declares " + frameCount
                    + " frames, over the " + MAX_FRAMES + " a 120 ms packet can hold");
        }

        int padding = 0;
        if (padded) {
            // Padding is counted in a run of bytes where 255 means "255 more,
            // and read another byte". The bytes themselves sit at the end.
            while (true) {
                if (at >= end) {
                    throw new MalformedPacketException("code 3 padding count runs past the packet");
                }
                int b = data[at++] & 0xFF;
                if (b == 255) {
                    padding += 254;
                } else {
                    padding += b;
                    break;
                }
            }
        }
        int available = end - at - padding;
        if (available < 0) {
            throw new MalformedPacketException("code 3 packet declares "
                    + padding + " bytes of padding and has only " + (end - at) + " left");
        }

        if (!variable) {
            if (available % frameCount != 0) {
                throw new MalformedPacketException("code 3 CBR packet has " + available
                        + " bytes for " + frameCount + " equal frames, which does not divide");
            }
            int each = available / frameCount;
            if (each > MAX_FRAME_BYTES) {
                throw new MalformedPacketException("code 3 CBR frames are " + each
                        + " bytes, over the limit");
            }
            for (int i = 0; i < frameCount; i++) {
                frames.add(new int[] {at + i * each, each});
            }
            return;
        }

        // Variable bitrate: every frame but the last states its length.
        int[] lengths = new int[frameCount];
        int stated = 0;
        for (int i = 0; i < frameCount - 1; i++) {
            int[] read = readLength(data, at, end);
            lengths[i] = read[0];
            at = read[1];
            stated += lengths[i];
        }
        int last = end - padding - at - stated;
        if (last < 0) {
            throw new MalformedPacketException(
                    "code 3 VBR frame lengths overrun the packet by " + (-last) + " bytes");
        }
        lengths[frameCount - 1] = last;
        for (int i = 0; i < frameCount; i++) {
            frames.add(new int[] {at, lengths[i]});
            at += lengths[i];
        }
    }

    /**
     * Reads one frame length.
     *
     * <p>Zero to 251 is written in one byte; anything larger is two, where the
     * second byte counts fours. The maximum expressible is 1275, which is not
     * a coincidence: it is the frame size limit.
     *
     * @return the length and the offset just past it
     */
    private static int[] readLength(byte[] data, int at, int end) {
        if (at >= end) {
            throw new MalformedPacketException("a frame length runs past the end of the packet");
        }
        int first = data[at++] & 0xFF;
        if (first < 252) {
            return new int[] {first, at};
        }
        if (at >= end) {
            throw new MalformedPacketException("a two-byte frame length is cut off");
        }
        int second = data[at++] & 0xFF;
        return new int[] {second * 4 + first, at};
    }

    /** The configuration number from the table of contents byte, 0 to 31. */
    public int config() {
        return config;
    }

    /** Which codec the frames use. */
    public Mode mode() {
        if (config < 12) {
            return Mode.SILK;
        }
        return config < 16 ? Mode.HYBRID : Mode.CELT;
    }

    /** The audio bandwidth the configuration selects. */
    public Bandwidth bandwidth() {
        return BANDWIDTHS[config];
    }

    /** How long each frame is, in microseconds. */
    public int frameMicros() {
        return FRAME_MICROS[config];
    }

    /** Samples per frame per channel at 48 kHz, the rate Opus always outputs. */
    public int frameSamples48k() {
        return frameMicros() * 48 / 1000;
    }

    /** Whether the packet carries two channels. */
    public boolean stereo() {
        return stereo;
    }

    /** How many frames the packet carries. */
    public int frameCount() {
        return frameLength.length;
    }

    /** Where frame {@code index} starts in the backing array. */
    public int frameOffset(int index) {
        return frameOffset[index];
    }

    /** How many bytes frame {@code index} occupies. */
    public int frameLength(int index) {
        return frameLength[index];
    }

    /** The bytes this packet was parsed out of. */
    public byte[] data() {
        return data;
    }

    /** Total samples per channel the packet decodes to at 48 kHz. */
    public int samples48k() {
        return frameSamples48k() * frameCount();
    }

    @Override
    public String toString() {
        return "OpusPacket[config=" + config + " " + mode() + " " + bandwidth()
                + " " + (frameMicros() / 1000.0) + "ms"
                + (stereo ? " stereo" : " mono")
                + " frames=" + frameCount() + "]";
    }
}
