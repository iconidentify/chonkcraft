package net.chonkbase.assetpack.codec;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import net.chonkbase.assetpack.codec.opus.OggReader;
import net.chonkbase.assetpack.codec.opus.OggWriter;
import net.chonkbase.assetpack.codec.opus.OpusDecoder;
import net.chonkbase.assetpack.codec.opus.OpusEncoder;
import net.chonkbase.assetpack.codec.opus.Resampler;

/**
 * Ogg Opus, whole files in and whole files out.
 *
 * <p>The counterpart of {@link Flac}: same shape, same {@link Flac.Pcm} on both
 * sides, so a caller storing audio in a pack chooses between them by swapping
 * one call. Everything underneath -- the encoder, the decoder, the container,
 * the resampler -- is in {@code codec.opus} and is addressed packet by packet.
 * This is the layer that knows how a <em>file</em> is assembled from those.
 *
 * <h2>The 48 kHz problem, and where it is solved</h2>
 *
 * <p><b>Opus only ever decodes at 48 kHz.</b> That is not a property of this
 * implementation, it is RFC 6716: there is no field anywhere in the bitstream
 * that says what rate to reconstruct at. Warcraft II's audio is 8-bit mono at
 * 11,025 and 22,050 Hz and 16-bit stereo at 44,100, and every consumer of it in
 * this port was written against those rates.
 *
 * <p>So this class resamples on both sides, and the sample rate of the
 * {@link Flac.Pcm} that comes out of {@link #decode} is the sample rate of the
 * one that went into {@link #encode}. The Ogg identification header already has
 * the field to carry that -- RFC 7845's "input sample rate", which the spec is
 * explicit is metadata and "is _not_ the sample rate to use for playback" -- so
 * nothing outside the file has to remember it. A caller that wants the codec's
 * own 48 kHz output, unresampled, asks for it with {@link #decodeAtCodecRate}.
 *
 * <p>The frame count survives the round trip <em>exactly</em>, which is the
 * property that lets an asset pack rebuild a 1995 archive entry byte-shape for
 * byte-shape. The encoder writes a final granule position of
 * {@code preSkip + ceil(frames * 48000 / rate)} and the decoder recovers
 * {@code floor(count * rate / 48000)} from it; for any rate at or below 48 kHz
 * that composition is the identity, because the ceiling can only push the 48 kHz
 * count up by less than one input frame's worth.
 */
public final class Opus {

    /** The rate Opus is defined at, and the only rate it decodes at. */
    public static final int CODEC_RATE = OpusEncoder.CODEC_RATE;

    /**
     * The 48 kHz samples a decoder discards before the audio starts.
     *
     * <p>The whole delay through this encoder and decoder, which is the CELT
     * MDCT overlap and nothing else -- there is no SILK resampler to converge
     * and no lookahead. Declaring it in the identification header is what makes
     * the decoded audio come back aligned with the source rather than 2.5 ms
     * late, and {@code CeltEncoderTest} pins that alignment against ffmpeg.
     */
    public static final int PRE_SKIP = 120;

    /**
     * The Ogg logical bitstream serial number every stream this writes uses.
     *
     * <p>Fixed, so that encoding the same audio twice produces the same bytes.
     * RFC 3533 suggests a random serial because it is thinking about
     * multiplexing independent streams into one physical file; a pack holds one
     * stream per file, and a random number here would make every rebuild of a
     * pack differ from the last for no reason a human could see.
     */
    private static final int SERIAL = 0x43484f4e;

    private Opus() {
    }

    /**
     * Encodes a whole clip into an Ogg Opus file.
     *
     * <p>The input's own sample rate and bit depth are honoured: 8-bit samples
     * arrive from {@link Wav#decode} on the -128 to 127 scale and are shifted up
     * to the 16-bit scale the codec works on, which is the same widening
     * {@code LegacyWavDecoder} does to play them.
     *
     * @param pcm        what to encode, 1 or 2 channels at 8000 to 192000 Hz
     * @param bitrateBps the constant target rate; see docs/asset-pack-format.md
     *                   for the two the pack uses and the measurements behind
     *                   them
     */
    public static byte[] encode(Flac.Pcm pcm, int bitrateBps) {
        int rate = pcm.sampleRate();
        int channels = pcm.channels();
        int frames = pcm.frameCount();
        short[] input = widen(pcm);

        OpusEncoder encoder = new OpusEncoder(rate, channels, bitrateBps);
        int frameSize = encoder.frameSize48k();
        int chunk = Math.max(1, encoder.frameSamples());

        // How many 48 kHz samples the stream has to carry: the audio, and the
        // pre-skip in front of it. Rounded up, because a frame of input that
        // lands three quarters of the way through a 48 kHz sample still has to
        // be represented, and the decoder rounds back down.
        long wanted = PRE_SKIP + ceilDiv((long) frames * CODEC_RATE, rate);

        ByteArrayOutputStream file = new ByteArrayOutputStream(
                Math.max(1024, (int) (wanted * bitrateBps / (8L * CODEC_RATE)) + 512));
        byte[] packet = new byte[encoder.maxPacketBytes()];
        short[] silence = new short[channels * chunk];
        long produced = 0;
        int at = 0;

        try (OggWriter ogg = new OggWriter(file, channels, rate, PRE_SKIP, SERIAL)) {
            while (produced < wanted) {
                int take = Math.min(chunk, frames - at);
                int length;
                if (take > 0) {
                    length = encoder.encode(input, at * channels, take, packet, 0);
                    at += take;
                } else {
                    // Past the end of the audio. The encoder still owes whatever
                    // its resampler is holding, and the last real sample only
                    // reaches the output once a frame's worth of silence has
                    // pushed it through, so the tail is flushed rather than
                    // dropped. This is also what pads the final frame out to a
                    // whole 20 ms, which Opus has no way to avoid.
                    length = encoder.encode(silence, 0, chunk, packet, 0);
                }
                if (length == 0) {
                    continue;
                }
                produced += frameSize;
                // A running total of decodable 48 kHz samples, pre-skip
                // included, which is what RFC 7845 section 4 means by a granule
                // position. The last one is capped at the true length so that
                // any player, not just this one, trims the padding off the end.
                ogg.write(packet, 0, length, Math.min(produced, wanted));
            }
            ogg.finish();
        }
        return file.toByteArray();
    }

    /**
     * Decodes a whole file back to 16-bit PCM at the rate it was encoded from.
     *
     * @throws net.chonkbase.assetpack.codec.opus.OggException if the container
     *         is damaged; the message names the page and the byte offset
     */
    public static Flac.Pcm decode(byte[] ogg) {
        return decode(ogg, 16);
    }

    /**
     * Decodes a whole file back to PCM at the rate and depth it was encoded
     * from.
     *
     * <p>The depth is a parameter because the container does not record it: Opus
     * has no notion of one. Passing 8 narrows back to the -128 to 127 scale
     * {@link Wav#decode} yields for an 8-bit file, which is what an asset pack
     * needs to rebuild an entry whose header says 8 bits. Narrowing rounds
     * rather than truncates, so a sample that came back a hair under its
     * original value lands on that value rather than one below it.
     */
    public static Flac.Pcm decode(byte[] ogg, int bitsPerSample) {
        OggReader reader = new OggReader(ogg);
        int rate = reader.inputSampleRate() > 0 ? reader.inputSampleRate() : CODEC_RATE;
        Flac.Pcm at48k = decodeAtCodecRate(ogg, reader);
        int channels = at48k.channels();
        int frames = (int) ((long) at48k.frameCount() * rate / CODEC_RATE);

        short[] narrow = new short[at48k.samples().length];
        for (int i = 0; i < narrow.length; i++) {
            narrow[i] = (short) at48k.samples()[i];
        }
        short[] atRate = rate == CODEC_RATE
                ? Arrays.copyOf(narrow, frames * channels)
                : Resampler.resample(narrow, channels, CODEC_RATE, rate, frames);

        int shift = 16 - bitsPerSample;
        int[] out = new int[atRate.length];
        int limit = (1 << (bitsPerSample - 1)) - 1;
        for (int i = 0; i < out.length; i++) {
            int value = shift <= 0 ? atRate[i] : Math.round(atRate[i] / (float) (1 << shift));
            out[i] = Math.max(-limit - 1, Math.min(limit, value));
        }
        return new Flac.Pcm(rate, channels, bitsPerSample, out);
    }

    /**
     * Decodes a whole file to the codec's own 48 kHz output, with the pre-skip
     * and the end padding already trimmed.
     *
     * <p>For a caller that is going to resample anyway and would rather not have
     * this class do it twice.
     */
    public static Flac.Pcm decodeAtCodecRate(byte[] ogg) {
        return decodeAtCodecRate(ogg, new OggReader(ogg));
    }

    private static Flac.Pcm decodeAtCodecRate(byte[] ogg, OggReader reader) {
        int channels = reader.channels();
        OpusDecoder decoder = new OpusDecoder(CODEC_RATE, channels);
        int[] pcm = new int[0];
        short[] scratch = new short[OpusDecoder.MAX_PACKET_SAMPLES * channels];
        int total = 0;
        while (reader.hasNext()) {
            byte[] packet = reader.next();
            int decoded = decoder.decode(packet, 0, packet.length, scratch, 0) * channels;
            if (pcm.length < total + decoded) {
                pcm = Arrays.copyOf(pcm, Math.max(total + decoded, pcm.length * 2 + 4096));
            }
            for (int i = 0; i < decoded; i++) {
                pcm[total + i] = scratch[i];
            }
            total += decoded;
        }

        // What the stream says it holds, against what actually decoded. The
        // granule position is the authority on where the audio ends -- the last
        // packet is padded out to a whole frame and a decoder that kept the
        // padding would append up to 20 ms of the encoder's ringing to every
        // clip -- but a truncated file has fewer samples than it claims, and
        // taking the smaller of the two is what turns that into a short clip
        // rather than an array index out of bounds.
        int preSkip = reader.preSkip();
        long declared = reader.finalGranulePosition() - preSkip;
        int decodedFrames = total / channels - preSkip;
        int frames = (int) Math.max(0, Math.min(declared, decodedFrames));

        int[] trimmed = new int[frames * channels];
        System.arraycopy(pcm, preSkip * channels, trimmed, 0, trimmed.length);
        return new Flac.Pcm(CODEC_RATE, channels, 16, trimmed);
    }

    /** What an encoded stream says about itself, without decoding any of it. */
    public static Info readInfo(byte[] ogg) {
        OggReader reader = new OggReader(ogg);
        int rate = reader.inputSampleRate() > 0 ? reader.inputSampleRate() : CODEC_RATE;
        long frames48k = Math.max(0, reader.finalGranulePosition() - reader.preSkip());
        return new Info(rate, reader.channels(), reader.preSkip(),
                (int) (frames48k * rate / CODEC_RATE), (int) frames48k, reader.packetCount());
    }

    /**
     * The header facts of a stream.
     *
     * @param sampleRate    the rate the audio was encoded from
     * @param channels      1 or 2
     * @param preSkip       48 kHz samples the decoder discards
     * @param frameCount    frames per channel at {@link #sampleRate}
     * @param codecFrames   frames per channel at 48 kHz
     * @param packetCount   how many Opus packets the container carries
     */
    public record Info(int sampleRate, int channels, int preSkip, int frameCount,
            int codecFrames, int packetCount) {

        /** How long it plays for, in seconds. */
        public double durationSeconds() {
            return codecFrames / (double) CODEC_RATE;
        }
    }

    /**
     * Lifts a clip onto the 16-bit scale the codec works on.
     *
     * <p>An 8-bit sample arrives on the -128 to 127 scale and is shifted left by
     * eight, which is exactly what {@code LegacyWavDecoder.widenUnsignedBytes}
     * does before the mixer sees it. Scaling by 257 to reach true full scale
     * instead would be a gain change of a third of a decibel that the decode
     * side would have to undo by division, and a division is not a shift.
     */
    private static short[] widen(Flac.Pcm pcm) {
        int shift = 16 - pcm.bitsPerSample();
        int[] samples = pcm.samples();
        short[] out = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            int value = shift <= 0 ? samples[i] : samples[i] << shift;
            out[i] = Resampler.clampToShort(value);
        }
        return out;
    }

    private static long ceilDiv(long value, long divisor) {
        return (value + divisor - 1) / divisor;
    }
}
