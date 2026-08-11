package net.chonkbase.chonkcraft.engine.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import net.chonkbase.runtime.audio.PcmClip;
import net.chonkbase.runtime.audio.PcmFormat;
import org.junit.jupiter.api.Test;

/** Tests for reading Warcraft II's 1995-era WAV files. */
class SoundTest {

    /**
     * Builds a WAV.
     *
     * @param bits       8 for unsigned bytes, 16 for signed shorts
     * @param sampleRate the rate to declare
     * @param channels   1 or 2
     * @param frames     how many frames of audio
     */
    private static byte[] wav(int bits, int sampleRate, int channels, int frames) {
        int blockAlign = channels * bits / 8;
        int dataSize = frames * blockAlign;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        writeLe32(out, 36 + dataSize);
        out.writeBytes("WAVE".getBytes(StandardCharsets.US_ASCII));

        out.writeBytes("fmt ".getBytes(StandardCharsets.US_ASCII));
        writeLe32(out, 16);
        writeLe16(out, 1);                      // integer PCM
        writeLe16(out, channels);
        writeLe32(out, sampleRate);
        writeLe32(out, sampleRate * blockAlign);
        writeLe16(out, blockAlign);
        writeLe16(out, bits);

        out.writeBytes("data".getBytes(StandardCharsets.US_ASCII));
        writeLe32(out, dataSize);
        for (int frame = 0; frame < frames; frame++) {
            for (int channel = 0; channel < channels; channel++) {
                if (bits == 8) {
                    // Unsigned, silence at 128.
                    out.write(128 + (frame % 100) - 50);
                } else {
                    writeLe16(out, (frame % 100) * 100);
                }
            }
        }
        return out.toByteArray();
    }

    private static void writeLe16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void writeLe32(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    @Test
    void readsEightBitMonoAndResamplesToTheMixerRate() {
        // The format Warcraft II actually ships: 8-bit unsigned at 11 kHz.
        PcmClip clip = LegacyWavDecoder.decode("test", wav(8, 11_025, 1, 1000));

        assertEquals(1, clip.channels());
        assertEquals(PcmFormat.GAME_SAMPLE_RATE, clip.sampleRate());
        // Upsampling from 11025 to 48000 is a factor of about 4.35.
        int expected = (int) (1000L * PcmFormat.GAME_SAMPLE_RATE / 11_025);
        assertEquals(expected, clip.frameCount());
    }

    @Test
    void recentresUnsignedEightBitSamples() {
        // 8-bit WAV puts silence at 128, so it must be shifted as well as
        // widened or every sound comes out with a large DC offset.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        writeLe32(out, 36 + 4);
        out.writeBytes("WAVE".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes("fmt ".getBytes(StandardCharsets.US_ASCII));
        writeLe32(out, 16);
        writeLe16(out, 1);
        writeLe16(out, 1);
        writeLe32(out, PcmFormat.GAME_SAMPLE_RATE);
        writeLe32(out, PcmFormat.GAME_SAMPLE_RATE);
        writeLe16(out, 1);
        writeLe16(out, 8);
        out.writeBytes("data".getBytes(StandardCharsets.US_ASCII));
        writeLe32(out, 4);
        out.write(128);   // silence
        out.write(255);   // full positive
        out.write(0);     // full negative
        out.write(128);

        PcmClip clip = LegacyWavDecoder.decode("test", out.toByteArray());
        short[] samples = clip.copySamples();

        assertEquals(0, samples[0], "128 should be silence");
        assertTrue(samples[1] > 30_000, "255 should be near full positive, was " + samples[1]);
        assertTrue(samples[2] < -30_000, "0 should be near full negative, was " + samples[2]);
    }

    @Test
    void readsSixteenBitStereo() {
        PcmClip clip = LegacyWavDecoder.decode("test", wav(16, PcmFormat.GAME_SAMPLE_RATE, 2, 500));
        assertEquals(2, clip.channels());
        assertEquals(500, clip.frameCount(), "already at the mixer rate, so no resampling");
    }

    @Test
    void leavesAudioAlreadyAtTheMixerRateUntouched() {
        PcmClip clip = LegacyWavDecoder.decode("test", wav(16, PcmFormat.GAME_SAMPLE_RATE, 1, 321));
        assertEquals(321, clip.frameCount());
    }

    @Test
    void skipsChunksItDoesNotUnderstand() {
        // Real files carry LIST and fact chunks between fmt and data.
        byte[] plain = wav(8, 22_050, 1, 200);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Copy the header, insert a junk chunk before data.
        out.writeBytes(java.util.Arrays.copyOfRange(plain, 0, 36));
        out.writeBytes("LIST".getBytes(StandardCharsets.US_ASCII));
        writeLe32(out, 4);
        out.writeBytes(new byte[] {1, 2, 3, 4});
        out.writeBytes(java.util.Arrays.copyOfRange(plain, 36, plain.length));

        PcmClip clip = LegacyWavDecoder.decode("test", out.toByteArray());
        assertTrue(clip.frameCount() > 0, "the data chunk should still be found");
    }

    @Test
    void rejectsSomethingThatIsNotAWav() {
        LegacyWavDecoder.UnsupportedWavException error =
                assertThrows(LegacyWavDecoder.UnsupportedWavException.class,
                        () -> LegacyWavDecoder.decode("test", "not audio at all".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(error.getMessage().contains("RIFF"));
    }

    @Test
    void rejectsCompressedAudio() {
        byte[] data = wav(16, 22_050, 1, 100);
        // Change the format code from 1 to 2 (ADPCM).
        data[20] = 2;
        assertThrows(LegacyWavDecoder.UnsupportedWavException.class,
                () -> LegacyWavDecoder.decode("test", data));
    }

    @Test
    void rejectsAnUnsupportedBitDepth() {
        byte[] data = wav(16, 22_050, 1, 100);
        // 24-bit.
        data[34] = 24;
        assertThrows(LegacyWavDecoder.UnsupportedWavException.class,
                () -> LegacyWavDecoder.decode("test", data));
    }

    @Test
    void toleratesATruncatedFinalChunk() {
        // These archives contain a few files whose data chunk claims more
        // bytes than are present. Losing the tail beats losing the sound.
        byte[] full = wav(8, 11_025, 1, 400);
        byte[] cut = java.util.Arrays.copyOf(full, full.length - 100);
        PcmClip clip = LegacyWavDecoder.decode("test", cut);
        assertTrue(clip.frameCount() > 0);
    }
}
