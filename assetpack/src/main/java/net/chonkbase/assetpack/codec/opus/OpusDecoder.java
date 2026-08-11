package net.chonkbase.assetpack.codec.opus;

import java.util.Objects;

/**
 * An Opus decoder: packets in, interleaved 16-bit PCM at 48 kHz out.
 *
 * <p>A port of {@code opus_decode_native} and the CELT half of
 * {@code opus_decode_frame} in {@code src/opus_decoder.c}, plus the
 * {@code FLOAT2INT16} conversion from {@code celt/float_cast.h}. The framing is
 * RFC 6716 section 3 and lives in {@link OpusPacket}; the codec layer is RFC
 * 6716 section 4.3 and lives in {@link CeltDecoder}.
 *
 * <p>Allocates in the constructor and once per packet, in {@link OpusPacket};
 * the per-frame path allocates nothing.
 *
 * <p><b>What is here and what is not.</b> The CELT-only configurations, 16
 * through 31, decode. The SILK-only configurations, 0 through 11, and the
 * Hybrid ones, 12 through 15, throw {@link UnsupportedOperationException}
 * naming the configuration. They do not return silence, and that choice is
 * deliberate: a decoder that quietly produces nothing for a mode it cannot
 * handle turns a missing feature into a bug report about audio that "sometimes
 * does not play", months later and in someone else's component. Packet loss
 * concealment is also absent, and a zero-length or one-byte frame -- which is
 * how a stream signals a gap -- throws rather than inventing samples.
 */
public final class OpusDecoder {

    /** The only rate this decoder produces, and the rate Opus is defined at. */
    public static final int SAMPLE_RATE = 48_000;

    /** The longest a packet may be, 120 ms at 48 kHz. */
    public static final int MAX_PACKET_SAMPLES = 5760;

    private final int channels;
    private final CeltDecoder celt;
    private final RangeDecoder range;

    /** The CELT layer's float output, before the conversion to 16-bit. */
    private final float[] scratch;

    /** {@code st->rangeFinal}: what the last frame's range coder ended holding. */
    private long finalRange;

    /**
     * @param sampleRate must be {@value #SAMPLE_RATE}
     * @param channels   1 or 2
     * @throws IllegalArgumentException if either is out of range
     */
    public OpusDecoder(int sampleRate, int channels) {
        if (sampleRate != SAMPLE_RATE) {
            // The reference decimates in the de-emphasis filter to reach 8, 12,
            // 16 and 24 kHz. That path is not ported, and returning 48 kHz
            // samples to a caller that asked for 24 would play back an octave
            // low rather than fail.
            throw new IllegalArgumentException("this decoder produces " + SAMPLE_RATE
                    + " Hz only, and was asked for " + sampleRate
                    + " Hz; decimation to the lower Opus rates is not implemented");
        }
        if (channels != 1 && channels != 2) {
            throw new IllegalArgumentException("Opus decodes 1 or 2 channels, not " + channels);
        }
        this.channels = channels;
        this.celt = new CeltDecoder(channels);
        // Pointed at each frame in turn by init(); never reallocated.
        this.range = new RangeDecoder(new byte[1], 0, 1);
        this.scratch = new float[channels * MAX_PACKET_SAMPLES];
    }

    /** How many channels this decoder produces. */
    public int channels() {
        return channels;
    }

    /** The sample rate this decoder produces, always {@value #SAMPLE_RATE}. */
    public int sampleRate() {
        return SAMPLE_RATE;
    }

    /**
     * The band energy envelope the last packet decoded; see
     * {@link CeltDecoder#bandEnergy()}.
     */
    public float[] bandEnergy() {
        return celt.bandEnergy();
    }

    /**
     * Forgets every packet decoded so far, as {@code OPUS_RESET_STATE} does.
     *
     * <p>Needed when seeking. Without it the first frame after a seek is
     * predicted from energies, an overlap tail and a post-filter state belonging
     * to a completely different part of the recording, which is heard as a burst
     * of noise lasting a frame or two.
     */
    public void reset() {
        celt.reset();
        finalRange = 0;
    }

    /**
     * The range coder state the last decoded packet ended on,
     * {@code OPUS_GET_FINAL_RANGE}.
     *
     * <p>The conformance gate. RFC 6716 section 6 stores this value beside every
     * packet of the test vectors: a decoder that has read every symbol of the
     * packet correctly holds exactly the same value here, and one that has
     * misread a single symbol anywhere does not. It is integer arithmetic, so
     * unlike the audio it cannot be excused by floating-point rounding.
     *
     * <p>Zero after a packet whose frames were too short to carry a range coder,
     * which is what the reference reports for a dropped or empty frame.
     */
    public long finalRange() {
        return finalRange;
    }

    /**
     * Decodes one packet.
     *
     * @param packet    the buffer the packet lives in
     * @param offset    where in it the packet starts
     * @param length    how many bytes it occupies
     * @param pcm       receives {@code channels} times the returned count of
     *                  interleaved 16-bit samples
     * @param pcmOffset where in it to start writing
     * @return samples decoded per channel
     * @throws OpusPacket.MalformedPacketException if the framing is invalid
     * @throws UnsupportedOperationException if the packet selects SILK or Hybrid,
     *                                  or carries a frame short enough to mean
     *                                  packet loss
     * @throws IllegalArgumentException if the output buffer is too small
     */
    public int decode(byte[] packet, int offset, int length, short[] pcm, int pcmOffset) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(pcm, "pcm");
        if (offset < 0 || length < 0 || offset > packet.length - length) {
            throw new IllegalArgumentException("packet slice [" + offset + "," + (offset + length)
                    + ") is not inside a " + packet.length + "-byte array");
        }

        OpusPacket parsed = OpusPacket.parse(packet, offset, length);
        if (parsed.mode() != OpusPacket.Mode.CELT) {
            throw new UnsupportedOperationException("packet configuration " + parsed.config()
                    + " selects " + parsed.mode() + ", and only the CELT configurations"
                    + " (16 to 31) are implemented; this decoder will not return silence for a"
                    + " mode it cannot decode");
        }

        // Not required to match this decoder's channel count: RFC 6716 section
        // 3.1 lets any packet change it, and the CELT layer duplicates a mono
        // frame to both outputs or sums a stereo one to the mid.
        int streamChannels = parsed.stereo() ? 2 : 1;

        int frameSize = parsed.frameSamples48k();
        int frames = parsed.frameCount();
        long total = (long) frameSize * frames;
        if (total > MAX_PACKET_SAMPLES) {
            throw new OpusPacket.MalformedPacketException("packet decodes to " + total
                    + " samples per channel, past the " + MAX_PACKET_SAMPLES
                    + " an Opus packet may hold");
        }
        long needed = total * channels;
        if (pcmOffset < 0 || pcm.length - pcmOffset < needed) {
            throw new IllegalArgumentException("the output needs " + needed + " samples at offset "
                    + pcmOffset + " of a " + pcm.length + " array");
        }

        // A CELT-only packet codes every band from 0; the bandwidth decides
        // where it stops.
        int startBand = CeltMode.codingStartBand(parsed.mode());
        int endBand = CeltMode.codingEndBand(parsed.bandwidth());

        byte[] data = parsed.data();
        int written = 0;
        for (int f = 0; f < frames; f++) {
            int frameLength = parsed.frameLength(f);
            if (frameLength <= 1) {
                // The reference treats these as a lost frame and runs its
                // concealment. Inventing a frame of audio here without having
                // ported that would be worse than saying so.
                throw new UnsupportedOperationException("frame " + f + " of this packet is "
                        + frameLength + " byte" + (frameLength == 1 ? "" : "s")
                        + ", which signals packet loss or discontinuous transmission;"
                        + " concealment is not implemented");
            }
            range.init(data, parsed.frameOffset(f), frameLength);
            celt.decode(range, frameSize, startBand, endBand, streamChannels,
                    scratch, written * channels);
            written += frameSize;
            // The reference keeps only the last frame's value, because that is
            // the state the encoder's coder held when the packet ended.
            finalRange = range.finalRange();
        }

        int count = written * channels;
        for (int i = 0; i < count; i++) {
            pcm[pcmOffset + i] = floatToInt16(scratch[i]);
        }
        return written;
    }

    /**
     * {@code FLOAT2INT16} in {@code celt/float_cast.h}.
     *
     * <p>Scale, clamp, then round to nearest with ties to even, which is what
     * {@code lrintf} does under the default rounding mode. Rounding ties away
     * from zero instead would bias every sample of the output upwards by half a
     * count on average -- inaudible on its own, but it is a systematic offset
     * that shows up as a DC step when a decode is spliced against a reference.
     *
     * <p>The clamp is at {@code -32768} and {@code 32767}, not symmetric: a
     * float that lands one count past positive full scale must come back as the
     * largest representable sample rather than wrapping to the most negative
     * one, which would be heard as a loud click on exactly the loudest passages.
     */
    static short floatToInt16(float x) {
        float scaled = x * 32768.0f;
        if (scaled < -32768.0f) {
            scaled = -32768.0f;
        }
        if (scaled > 32767.0f) {
            scaled = 32767.0f;
        }
        return (short) Math.rint(scaled);
    }

    @Override
    public String toString() {
        return "OpusDecoder[" + channels + " channels at " + SAMPLE_RATE + " Hz]";
    }
}
