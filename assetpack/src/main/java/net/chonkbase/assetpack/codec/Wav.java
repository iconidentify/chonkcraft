package net.chonkbase.assetpack.codec;

import java.nio.charset.StandardCharsets;
import net.chonkbase.assetpack.PackFormatException;

/**
 * RIFF WAVE, read and written.
 *
 * <p>Present for two reasons. Some audio is too short for FLAC to pay for
 * itself: a two-kilobyte click has more format overhead than content, and a
 * pack that compresses everything on principle ends up bigger. And a consumer
 * that wants to hand a sound to something else usually wants a WAV.
 *
 * <p>Reads what a 1990s game actually ships: 8-bit unsigned and 16-bit signed
 * integer PCM, mono or stereo, at any rate, with chunks in any order and a
 * final chunk that lies about its length, which is common in these files and
 * is taken as "to the end" rather than as a reason to refuse the sound.
 */
public final class Wav {

    private static final int PCM = 1;

    private Wav() {
    }

    /**
     * Decodes a WAV into interleaved samples at its own bit depth.
     *
     * <p>Samples come back in the file's own scale: an 8-bit file yields
     * values 0 to 255 recentred to -128 to 127, and a 16-bit file yields
     * -32768 to 32767. Nothing is normalised or widened. A pack that widened
     * to 16 bits here would silently change what the game's own converter
     * receives, and that converter's arithmetic is asserted by tests.
     */
    public static Flac.Pcm decode(byte[] data) {
        if (data.length < 12 || !"RIFF".equals(tag(data, 0)) || !"WAVE".equals(tag(data, 8))) {
            throw new PackFormatException("not a RIFF/WAVE file");
        }

        int format = -1;
        int channels = 0;
        int sampleRate = 0;
        int bits = 0;
        int dataAt = -1;
        int dataLength = 0;

        int cursor = 12;
        while (cursor + 8 <= data.length) {
            String id = tag(data, cursor);
            int size = readLe32(data, cursor + 4);
            int body = cursor + 8;
            if (size < 0 || body + size > data.length) {
                size = data.length - body;
            }
            if ("fmt ".equals(id) && size >= 16) {
                format = readLe16(data, body);
                channels = readLe16(data, body + 2);
                sampleRate = readLe32(data, body + 4);
                bits = readLe16(data, body + 14);
            } else if ("data".equals(id)) {
                dataAt = body;
                dataLength = size;
            }
            cursor = body + size + (size & 1);
        }

        if (format != PCM) {
            throw new PackFormatException("WAV format " + format + " is not integer PCM");
        }
        if (dataAt < 0) {
            throw new PackFormatException("WAV has no data chunk");
        }
        if (channels <= 0 || sampleRate <= 0) {
            throw new PackFormatException(
                    "WAV declares " + channels + " channels at " + sampleRate + " Hz");
        }

        int[] samples;
        if (bits == 8) {
            samples = new int[dataLength];
            for (int i = 0; i < dataLength; i++) {
                samples[i] = (data[dataAt + i] & 0xFF) - 128;
            }
        } else if (bits == 16) {
            samples = new int[dataLength / 2];
            for (int i = 0; i < samples.length; i++) {
                samples[i] = (short) readLe16(data, dataAt + i * 2);
            }
        } else {
            throw new PackFormatException("WAV is " + bits + " bits per sample, expected 8 or 16");
        }
        return new Flac.Pcm(sampleRate, channels, bits, samples);
    }

    /**
     * Writes a canonical WAV: a header, a {@code fmt } chunk and a
     * {@code data} chunk, and nothing else.
     *
     * <p>Canonical rather than byte-identical to whatever was read. The
     * original files carry {@code LIST} chunks and truncated tails that no
     * consumer looks at, and reproducing them would mean carrying them in the
     * pack. What has to survive is the format and the samples, and those do.
     */
    public static byte[] encode(Flac.Pcm pcm) {
        int bits = pcm.bitsPerSample();
        if (bits != 8 && bits != 16) {
            throw new IllegalArgumentException("cannot write " + bits + "-bit WAV");
        }
        int bytesPerSample = bits / 8;
        int dataLength = pcm.samples().length * bytesPerSample;
        byte[] out = new byte[44 + dataLength];

        writeTag(out, 0, "RIFF");
        writeLe32(out, 4, 36 + dataLength);
        writeTag(out, 8, "WAVE");
        writeTag(out, 12, "fmt ");
        writeLe32(out, 16, 16);
        writeLe16(out, 20, PCM);
        writeLe16(out, 22, pcm.channels());
        writeLe32(out, 24, pcm.sampleRate());
        writeLe32(out, 28, pcm.sampleRate() * pcm.channels() * bytesPerSample);
        writeLe16(out, 32, pcm.channels() * bytesPerSample);
        writeLe16(out, 34, bits);
        writeTag(out, 36, "data");
        writeLe32(out, 40, dataLength);

        int at = 44;
        if (bits == 8) {
            for (int sample : pcm.samples()) {
                out[at++] = (byte) (sample + 128);
            }
        } else {
            for (int sample : pcm.samples()) {
                writeLe16(out, at, sample);
                at += 2;
            }
        }
        return out;
    }

    private static String tag(byte[] data, int at) {
        return new String(data, at, 4, StandardCharsets.US_ASCII);
    }

    private static void writeTag(byte[] out, int at, String tag) {
        for (int i = 0; i < 4; i++) {
            out[at + i] = (byte) tag.charAt(i);
        }
    }

    private static int readLe16(byte[] data, int at) {
        return (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8);
    }

    private static int readLe32(byte[] data, int at) {
        return (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8)
                | ((data[at + 2] & 0xFF) << 16) | ((data[at + 3] & 0xFF) << 24);
    }

    private static void writeLe16(byte[] out, int at, int value) {
        out[at] = (byte) value;
        out[at + 1] = (byte) (value >> 8);
    }

    private static void writeLe32(byte[] out, int at, int value) {
        out[at] = (byte) value;
        out[at + 1] = (byte) (value >> 8);
        out[at + 2] = (byte) (value >> 16);
        out[at + 3] = (byte) (value >> 24);
    }
}
