package net.chonkbase.runtime.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WavPcmLoaderTest {
    @Test
    void loadsStrict48kPcmAndSkipsUnknownPaddedChunks() throws Exception {
        short[] source = {-32_768, -12_345, 0, 12_345, 32_767, 7};
        byte[] wav = wav(2, 48_000, 1, source, true);

        PcmClip clip = WavPcmLoader.load("valid", new ByteArrayInputStream(wav));

        assertEquals("valid", clip.debugName());
        assertEquals(2, clip.channels());
        assertEquals(3, clip.frameCount());
        assertArrayEquals(source, clip.copySamples());
    }

    @Test
    void rejectsCompressedWrongRateAndMisalignedData() throws Exception {
        assertThrows(
                IOException.class,
                () -> WavPcmLoader.load(
                        "compressed", new ByteArrayInputStream(wav(1, 48_000, 3, new short[] {1, 2}, false))));
        assertThrows(
                IOException.class,
                () -> WavPcmLoader.load(
                        "rate", new ByteArrayInputStream(wav(1, 44_100, 1, new short[] {1, 2}, false))));

        byte[] valid = wav(2, 48_000, 1, new short[] {1, 2}, false);
        // Data chunk declares four bytes. Remove one byte from the payload while
        // keeping the RIFF metadata intact.
        byte[] truncated = java.util.Arrays.copyOf(valid, valid.length - 1);
        assertThrows(
                IOException.class,
                () -> WavPcmLoader.load("truncated", new ByteArrayInputStream(truncated)));
    }

    @Test
    void enforcesExplicitResidentPcmLimit() throws Exception {
        byte[] wav = wav(1, 48_000, 1, new short[] {1, 2, 3, 4}, false);
        assertThrows(
                IOException.class,
                () -> WavPcmLoader.load("limited", new ByteArrayInputStream(wav), 4));
    }

    @Test
    void rejectsMissingResourceWithActionablePath() {
        IOException failure = assertThrows(
                IOException.class,
                () -> WavPcmLoader.loadResource(WavPcmLoaderTest.class, "/audio/does-not-exist.wav"));
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("does-not-exist.wav"));
    }

    private static byte[] wav(int channels, int sampleRate, int formatCode, short[] samples, boolean addJunk)
            throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        ascii(body, "WAVE");
        if (addJunk) {
            chunk(body, "JUNK", new byte[] {7, 8, 9});
        }

        ByteArrayOutputStream format = new ByteArrayOutputStream();
        u16(format, formatCode);
        u16(format, channels);
        u32(format, sampleRate);
        int blockAlign = channels * 2;
        u32(format, (long) sampleRate * blockAlign);
        u16(format, blockAlign);
        u16(format, 16);
        chunk(body, "fmt ", format.toByteArray());

        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        for (short sample : samples) {
            u16(pcm, sample & 0xffff);
        }
        chunk(body, "data", pcm.toByteArray());

        ByteArrayOutputStream file = new ByteArrayOutputStream();
        ascii(file, "RIFF");
        u32(file, body.size());
        file.write(body.toByteArray());
        return file.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream output, String id, byte[] bytes) throws IOException {
        ascii(output, id);
        u32(output, bytes.length);
        output.write(bytes);
        if ((bytes.length & 1) != 0) {
            output.write(0);
        }
    }

    private static void ascii(ByteArrayOutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static void u16(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
    }

    private static void u32(ByteArrayOutputStream output, long value) {
        u16(output, (int) (value & 0xffff));
        u16(output, (int) ((value >>> 16) & 0xffff));
    }
}
