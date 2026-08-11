package net.chonkbase.runtime.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WavPcmStreamDecoderTest {
    private static final PcmStream.BufferConfig TEST_BUFFER =
            new PcmStream.BufferConfig(64, 4, 3);

    @Test
    void streamedWavLoopMatchesResidentPcmAndReopensOnlyOnProducer()
            throws Exception {
        short[] samples = new short[200 * 2];
        for (int frame = 0; frame < 200; frame++) {
            samples[frame * 2] =
                    (short) ((frame % 40) * 512 - 10_240);
            samples[frame * 2 + 1] =
                    (short) (10_240 - (frame % 40) * 512);
        }
        byte[] wav = pcmWav(2, samples);
        List<String> openThreads =
                new CopyOnWriteArrayList<>();
        PcmLoopRegion loop =
                new PcmLoopRegion(32, 180, 16);
        int frames = 220;

        float[] resident =
                renderResident(
                        new PcmClip("wav-resident", 2, samples),
                        loop,
                        frames);
        try (PcmStream stream =
                PcmStream.prepare(
                        "wav-loop",
                        WavPcmStreamDecoder.factory(
                                () -> {
                                    openThreads.add(
                                            Thread.currentThread()
                                                    .getName());
                                    return new ByteArrayInputStream(
                                            wav);
                                }),
                        TEST_BUFFER,
                        loop)) {
            assertTrue(
                    stream.awaitReady(2, TimeUnit.SECONDS));
            assertTrue(
                    stream.awaitBufferedFrames(
                            frames, 2, TimeUnit.SECONDS));
            AudioMixer mixer = new AudioMixer();
            mixer.play(
                    stream,
                    AudioBus.MUSIC,
                    -6.0f,
                    0.0f,
                    4);
            float[] streamed = new float[frames * 2];
            mixer.render(streamed, frames);

            assertArrayEquals(resident, streamed);
            assertTrue(openThreads.size() >= 3);
            assertTrue(
                    openThreads.stream()
                            .allMatch(name ->
                                    name.contains(
                                            "seven-days-audio-decode-wav-loop")));
            assertEquals(2, stream.channels());
            assertEquals(200, stream.sourceFrameCount());
            assertFalse(stream.decodedToEnd());
        }
    }

    @Test
    void malformedWavFailsPreparationWithoutExposingPlayableState()
            throws Exception {
        try (PcmStream stream =
                PcmStream.prepare(
                        "bad-wav",
                        WavPcmStreamDecoder.factory(
                                () ->
                                        new ByteArrayInputStream(
                                                new byte[] {
                                                    1, 2, 3, 4
                                                })),
                        TEST_BUFFER,
                        null)) {
            assertFalse(
                    stream.awaitReady(2, TimeUnit.SECONDS));
            assertFalse(stream.isReady());
            assertInstanceOf(
                    IOException.class,
                    stream.preparationFailure());
        }
    }

    private static float[] renderResident(
            PcmClip clip, PcmLoopRegion loop, int frames) {
        AudioMixer mixer = new AudioMixer();
        mixer.play(
                clip,
                AudioBus.MUSIC,
                loop,
                -6.0f,
                0.0f,
                4);
        float[] output = new float[frames * 2];
        mixer.render(output, frames);
        return output;
    }

    private static byte[] pcmWav(int channels, short[] samples)
            throws IOException {
        int blockAlign = channels * Short.BYTES;
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        ByteArrayOutputStream format = new ByteArrayOutputStream();
        u16(format, 1);
        u16(format, channels);
        u32(format, PcmFormat.GAME_SAMPLE_RATE);
        u32(
                format,
                (long) PcmFormat.GAME_SAMPLE_RATE
                        * blockAlign);
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
        u32(file, body.size() + 4L);
        ascii(file, "WAVE");
        file.write(body.toByteArray());
        return file.toByteArray();
    }

    private static void chunk(
            ByteArrayOutputStream output, String id, byte[] bytes)
            throws IOException {
        ascii(output, id);
        u32(output, bytes.length);
        output.write(bytes);
        if ((bytes.length & 1) != 0) {
            output.write(0);
        }
    }

    private static void ascii(
            ByteArrayOutputStream output, String value)
            throws IOException {
        output.write(
                value.getBytes(StandardCharsets.US_ASCII));
    }

    private static void u16(
            ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
    }

    private static void u32(
            ByteArrayOutputStream output, long value) {
        u16(output, (int) (value & 0xffff));
        u16(output, (int) ((value >>> 16) & 0xffff));
    }

}
