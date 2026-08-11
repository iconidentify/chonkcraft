package net.chonkbase.assetpack.codec;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * A lossless FLAC encoder and decoder with nothing behind it but the JDK.
 *
 * <p>New infrastructure, with no upstream analogue to cite. LegacyEngine and
 * ChonkCraft name a file and hand it to libFLAC, libvorbis or SDL_sound and let a
 * native library do this. A pack has to be readable by the game on any machine
 * with no native code shipped alongside it, so the format is implemented here
 * instead.
 *
 * <p>Why it is worth the code: the pack carries 89 minutes of red book music
 * across the two discs and 487 sound effects, and stored as raw PCM that is
 * 1.00 GB. FLAC is lossless, so what the mixer receives is what was cut on the
 * disc in 1995, bit for bit, and it is 59 percent of the size. Lossy would have
 * been smaller and would also have meant that a sound effect played ten
 * thousand times over a campaign is not the sound effect Blizzard recorded.
 *
 * <p>The encoder is deliberately narrow, and every restriction below was
 * measured on this corpus rather than guessed. The measurements are over 487
 * WAV entries from {@code SFXDAT.SUD} and {@code SNDDAT.WAR} plus the 16 red
 * book tracks of the Tides of Darkness disc, 488,304,627 bytes of raw PCM:
 *
 * <ul>
 * <li>Fixed predictors of order 0 to 4, and no LPC. Fixed-only lands at 59.0
 * percent of raw, against 57.0 percent for libFLAC 1.5.0 at {@code -8} with its
 * metadata blocks turned off so that only the audio is compared. Two points, or
 * 3.6 percent more bytes, does not pay for an autocorrelation, a
 * Levinson-Durbin recursion, quantised coefficients and the rounding rules that
 * go with them, all of which are places for an arithmetic error to hide in a
 * format whose failure mode is music that plays and is wrong.</li>
 * <li>One STREAMINFO block and nothing else -- no seektable, no vorbis comment,
 * no padding. Those are pure overhead for a pack, and they are not small
 * overhead. Entry 2 of {@code SFXDAT.SUD} is a 2904-byte WAV; ffmpeg's defaults
 * turn it into 9984 bytes and {@code flac -8} into 9878, because both attach an
 * eight-kilobyte padding block and a vorbis comment. This encoder writes it as
 * 1744. The smallest entry in that archive is 1084 bytes, so the padding block
 * alone is eight times the whole sound.</li>
 * <li>Block size 4096, the libFLAC default.</li>
 * </ul>
 *
 * <p>The decoder is wider than the encoder on purpose. It reads LPC subframes,
 * which this encoder never writes, so a pack whose audio was produced by
 * {@code flac} or by ffmpeg still loads. An asset pipeline that could only
 * read its own output would make every future decision about the encoder a
 * migration. Wasted bits are read and also written, on both sides; see
 * {@link FlacDecoder} for why that is worth stating.
 */
public final class Flac {

    private Flac() {
    }

    /** Interleaved linear PCM, which is what the mixer takes and what a round trip must return. */
    public record Pcm(int sampleRate, int channels, int bitsPerSample, int[] samples) {

        public Pcm {
            if (sampleRate <= 0) {
                throw new IllegalArgumentException("sample rate must be positive: " + sampleRate);
            }
            if (channels < 1 || channels > 8) {
                throw new IllegalArgumentException("FLAC carries 1 to 8 channels, not " + channels);
            }
            if (bitsPerSample < 4 || bitsPerSample > 32) {
                throw new IllegalArgumentException(
                        "FLAC carries 4 to 32 bits per sample, not " + bitsPerSample);
            }
            if (samples == null) {
                throw new IllegalArgumentException("samples must not be null");
            }
            if (samples.length % channels != 0) {
                throw new IllegalArgumentException("interleaved samples (" + samples.length
                        + ") do not divide into " + channels + " channels");
            }
        }

        /** Sample frames, meaning one sample per channel. */
        public int frameCount() {
            return samples.length / channels;
        }

        /** How long this plays for, in seconds. */
        public double durationSeconds() {
            return frameCount() / (double) sampleRate;
        }

        /** How many bytes this would occupy stored uncompressed. */
        public int rawByteLength() {
            return samples.length * ((bitsPerSample + 7) / 8);
        }

        /**
         * Value equality, comparing the samples themselves.
         *
         * <p>The generated one compares the array by identity, so
         * {@code decode(encode(x)).equals(x)} would be false for every stream
         * ever encoded and the round-trip property could not be stated at all.
         */
        @Override
        public boolean equals(Object other) {
            return other instanceof Pcm that
                    && sampleRate == that.sampleRate
                    && channels == that.channels
                    && bitsPerSample == that.bitsPerSample
                    && Arrays.equals(samples, that.samples);
        }

        @Override
        public int hashCode() {
            int hash = sampleRate;
            hash = hash * 31 + channels;
            hash = hash * 31 + bitsPerSample;
            return hash * 31 + Arrays.hashCode(samples);
        }

        @Override
        public String toString() {
            return "Pcm[" + sampleRate + " Hz, " + channels + " ch, " + bitsPerSample
                    + " bit, " + frameCount() + " frames]";
        }
    }

    /**
     * What STREAMINFO says about a stream, without decoding any of it.
     *
     * <p>{@code totalFrames} counts sample frames and is zero when the encoder
     * did not know the length. {@code md5} is over the unencoded samples and is
     * all zeroes when the encoder did not compute one; this encoder always
     * does, which is what lets a pack check its own audio without decoding it.
     */
    public record StreamInfo(int minBlockSize, int maxBlockSize, int minFrameSize, int maxFrameSize,
                             int sampleRate, int channels, int bitsPerSample, long totalFrames,
                             byte[] md5) {

        /** The MD5 as the lowercase hex a manifest carries. */
        public String md5Hex() {
            return HexFormat.of().formatHex(md5);
        }

        @Override
        public String toString() {
            return "StreamInfo[" + sampleRate + " Hz, " + channels + " ch, " + bitsPerSample
                    + " bit, " + totalFrames + " frames, md5 " + md5Hex() + "]";
        }
    }

    /** Thrown when a stream is not FLAC, or is FLAC that this decoder will not guess about. */
    public static final class FlacFormatException extends RuntimeException {
        public FlacFormatException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------- api

    /**
     * Encodes interleaved PCM into a complete native FLAC stream.
     *
     * @param pcm 8 or 16 bits per sample, any sample rate, 1 to 8 channels
     */
    public static byte[] encode(Pcm pcm) {
        return FlacEncoder.encode(pcm);
    }

    /** Decodes a complete FLAC stream, verifying every frame's CRC-16 as it goes. */
    public static Pcm decode(byte[] flac) {
        return FlacDecoder.decode(flac);
    }

    /**
     * Reads STREAMINFO and stops, for a manifest check that must not pay for
     * decoding the audio.
     *
     * <p>The returned {@link Pcm} carries the shape of the stream and an empty
     * sample array, because no samples were read. Use {@link #readStreamInfo}
     * when the length or the MD5 is what is wanted.
     */
    public static Pcm decodeHeaderOnly(byte[] flac) {
        StreamInfo info = readStreamInfo(flac);
        return new Pcm(info.sampleRate(), info.channels(), info.bitsPerSample(), new int[0]);
    }

    /** Reads STREAMINFO and stops, keeping the length and the MD5 that {@link Pcm} cannot carry. */
    public static StreamInfo readStreamInfo(byte[] flac) {
        return FlacDecoder.readStreamInfo(flac);
    }

    // -------------------------------------------------------------- checksums

    static final int STREAM_MARKER = 0x664C6143;

    static final int BLOCK_TYPE_STREAMINFO = 0;

    static final int SUBFRAME_CONSTANT = 0;
    static final int SUBFRAME_VERBATIM = 1;
    static final int SUBFRAME_FIXED = 2;
    static final int SUBFRAME_LPC = 3;

    /** Two channels stored as themselves. The independent codes are simply {@code channels - 1}. */
    static final int CHANNELS_INDEPENDENT_STEREO = 1;

    static final int CHANNELS_LEFT_SIDE = 8;
    static final int CHANNELS_RIGHT_SIDE = 9;
    static final int CHANNELS_MID_SIDE = 10;

    /** Sample rates FLAC can name in four bits, indexed by the frame header's code. */
    static final int[] SAMPLE_RATE_CODES = {
            0, 88200, 176400, 192000, 8000, 16000, 22050, 24000,
            32000, 44100, 48000, 96000, 0, 0, 0, 0,
    };

    private static final int[] CRC8_TABLE = new int[256];
    private static final int[] CRC16_TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int crc8 = i;
            for (int bit = 0; bit < 8; bit++) {
                crc8 = ((crc8 & 0x80) != 0) ? ((crc8 << 1) ^ 0x07) : (crc8 << 1);
            }
            CRC8_TABLE[i] = crc8 & 0xFF;

            int crc16 = i << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc16 = ((crc16 & 0x8000) != 0) ? ((crc16 << 1) ^ 0x8005) : (crc16 << 1);
            }
            CRC16_TABLE[i] = crc16 & 0xFFFF;
        }
    }

    /** FLAC's frame header checksum: polynomial x^8 + x^2 + x + 1, initialised to zero. */
    static int crc8(byte[] bytes, int from, int to) {
        int crc = 0;
        for (int i = from; i < to; i++) {
            crc = CRC8_TABLE[(crc ^ bytes[i]) & 0xFF];
        }
        return crc;
    }

    /** FLAC's whole-frame checksum: polynomial x^16 + x^15 + x^2 + 1, initialised to zero. */
    static int crc16(byte[] bytes, int from, int to) {
        int crc = 0;
        for (int i = from; i < to; i++) {
            crc = ((crc << 8) ^ CRC16_TABLE[((crc >>> 8) ^ bytes[i]) & 0xFF]) & 0xFFFF;
        }
        return crc;
    }

    /**
     * The MD5 STREAMINFO carries: the samples themselves, interleaved, each one
     * little-endian in as many whole bytes as the bit depth needs.
     *
     * <p>Signed, always. Eight-bit WAV on disc is unsigned, and a caller that
     * forgets to subtract 128 gets an MD5 that will never match anything the
     * reference tools compute, so the conversion belongs at the WAV boundary
     * and never here.
     */
    static byte[] md5OfSamples(int[] samples, int bitsPerSample) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("this JDK has no MD5", e);
        }
        int bytesPerSample = (bitsPerSample + 7) / 8;
        byte[] chunk = new byte[4096 * bytesPerSample];
        int filled = 0;
        for (int sample : samples) {
            for (int b = 0; b < bytesPerSample; b++) {
                chunk[filled++] = (byte) (sample >> (8 * b));
            }
            if (filled == chunk.length) {
                digest.update(chunk, 0, filled);
                filled = 0;
            }
        }
        digest.update(chunk, 0, filled);
        return digest.digest();
    }
}
