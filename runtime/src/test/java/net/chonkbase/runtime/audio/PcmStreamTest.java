package net.chonkbase.runtime.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PcmStreamTest {
    private static final PcmStream.BufferConfig SMALL_BUFFER =
            new PcmStream.BufferConfig(64, 4, 2);

    @Test
    void decoderLifecycleRunsOnlyOnTheBackgroundProducer()
            throws Exception {
        List<String> decoderThreads =
                new CopyOnWriteArrayList<>();
        short[] samples = ascendingMono(180);
        AtomicReference<ArrayDecoder> decoder =
                new AtomicReference<>();
        String caller = Thread.currentThread().getName();

        try (PcmStream stream =
                PcmStream.prepare(
                        "thread-proof",
                        () -> {
                            decoderThreads.add(
                                    "open:"
                                            + Thread.currentThread()
                                                    .getName());
                            ArrayDecoder opened =
                                    new ArrayDecoder(
                                            samples,
                                            1,
                                            decoderThreads);
                            decoder.set(opened);
                            return opened;
                        },
                        SMALL_BUFFER,
                        null)) {
            assertTrue(
                    stream.awaitReady(2, TimeUnit.SECONDS));
            assertEquals(1, stream.channels());
            assertEquals(180, stream.sourceFrameCount());
            assertEquals(
                    64L * 4L * 2L * Float.BYTES,
                    stream.bufferCapacityBytes());

            AudioMixer mixer = new AudioMixer();
            mixer.play(
                    stream,
                    AudioBus.MUSIC,
                    0.0f,
                    -1.0f,
                    4);
            mixer.render(new float[64], 32);
            mixer.stopAll();
            mixer.render(new float[2], 1);
        }

        assertTrue(decoder.get().closed);
        assertTrue(
                decoderThreads.stream()
                        .allMatch(entry ->
                                entry.contains(
                                                "seven-days-audio-decode-thread-proof")
                                        && !entry.endsWith(
                                                caller)));
    }

    @Test
    void finiteStreamCompletesWithExactPcmAndVoiceEvents()
            throws Exception {
        short[] samples =
                new short[] {
                    8_192, 16_384, -8_192, -16_384, 4_096
                };
        try (PcmStream stream =
                stream(samples, 1, SMALL_BUFFER, null)) {
            assertTrue(
                    stream.awaitReady(2, TimeUnit.SECONDS));
            AudioMixer mixer = new AudioMixer();
            long voice =
                    mixer.play(
                            stream,
                            AudioBus.AMBIENCE,
                            0.0f,
                            -1.0f,
                            3);
            float[] output = new float[12];
            mixer.render(output, 6);

            assertArrayEquals(
                    new float[] {
                        0.25f,
                        0.0f,
                        0.5f,
                        0.0f,
                        -0.25f,
                        0.0f,
                        -0.5f,
                        0.0f,
                        0.125f,
                        0.0f,
                        0.0f,
                        0.0f
                    },
                    output);
            assertEquals(0, mixer.activeVoiceCount());
            assertTrue(
                    mixer.events().snapshotSince(0L).stream()
                            .anyMatch(event ->
                                    event.type()
                                                    == AudioEventType
                                                            .VOICE_COMPLETED
                                            && event.voiceId()
                                                    == voice));
            assertEquals(0L, stream.underrunFrameCount());
        }
    }

    @Test
    void streamedLoopAndSeamCrossfadeMatchResidentMixerBitForBit()
            throws Exception {
        short[] samples =
                new short[] {
                    4_096,
                    8_192,
                    -16_384,
                    -12_288,
                    -8_192,
                    -4_096,
                    0,
                    4_096,
                    8_192,
                    12_288,
                    16_384,
                    16_384,
                    -14_746
                };
        PcmLoopRegion loop = new PcmLoopRegion(2, 12, 3);
        int[] blocks = {1, 7, 2, 13, 41};

        float[] resident =
                renderResident(
                        new PcmClip("resident", 1, samples),
                        loop,
                        blocks);
        float[] streamed =
                renderStreamed(samples, loop, blocks);

        assertArrayEquals(resident, streamed);
    }

    @Test
    void stalledDecoderCannotBlockRenderAndUnderrunRecovers()
            throws Exception {
        short[] samples = constantMono(256, (short) 8_192);
        CountDownLatch continueDecode = new CountDownLatch(1);
        List<String> decoderThreads =
                new CopyOnWriteArrayList<>();
        AtomicReference<ArrayDecoder> decoder =
                new AtomicReference<>();
        PcmStream.BufferConfig buffer =
                new PcmStream.BufferConfig(64, 2, 1);

        try (PcmStream stream =
                PcmStream.prepare(
                        "blocked-read",
                        () -> {
                            ArrayDecoder opened =
                                    new ArrayDecoder(
                                            samples,
                                            1,
                                            decoderThreads);
                            opened.blockAtFrame = 64;
                            opened.readGate = continueDecode;
                            decoder.set(opened);
                            return opened;
                        },
                        buffer,
                        null)) {
            assertTrue(
                    stream.awaitReady(2, TimeUnit.SECONDS));
            AudioMixer mixer = new AudioMixer();
            long voice =
                    mixer.play(
                            stream,
                            AudioBus.MUSIC,
                            0.0f,
                            -1.0f,
                            5);

            long started = System.nanoTime();
            float[] first = new float[65 * 2];
            mixer.render(first, 65);
            Duration elapsed =
                    Duration.ofNanos(
                            System.nanoTime() - started);
            assertTrue(
                    elapsed.compareTo(Duration.ofMillis(100))
                            < 0,
                    "render waited for the decoder: " + elapsed);
            assertEquals(0.25f, first[0]);
            assertEquals(0.0f, first[128]);
            assertTrue(
                    mixer.events().snapshotSince(0L).stream()
                            .anyMatch(event ->
                                    event.type()
                                                    == AudioEventType
                                                            .STREAM_UNDERRUN
                                            && event.voiceId()
                                                    == voice));
            assertEquals(1L, stream.underrunFrameCount());

            continueDecode.countDown();
            assertTrue(
                    stream.awaitBufferedFrames(
                            1, 2, TimeUnit.SECONDS));
            float[] recovered = new float[2];
            mixer.render(recovered, 1);
            assertArrayEquals(
                    new float[] {0.25f, 0.0f},
                    recovered);
            assertTrue(
                    mixer.events().snapshotSince(0L).stream()
                            .anyMatch(event ->
                                    event.type()
                                                    == AudioEventType
                                                            .STREAM_RECOVERED
                                            && event.voiceId()
                                                    == voice));
            assertTrue(
                    decoderThreads.stream()
                            .allMatch(name ->
                                    name.contains(
                                            "seven-days-audio-decode-blocked-read")));
        } finally {
            continueDecode.countDown();
        }
    }

    @Test
    void atomicReplacementRetainsTailContractForStreamedIncoming()
            throws Exception {
        AudioMixer mixer = new AudioMixer();
        PcmClip outgoing =
                new PcmClip(
                        "outgoing",
                        1,
                        constantMono(32, (short) 8_192));
        long outgoingVoice =
                mixer.play(
                        outgoing,
                        AudioBus.MUSIC,
                        true,
                        0.0f,
                        -1.0f,
                        4);
        mixer.render(new float[2], 1);

        try (PcmStream incoming =
                stream(
                        constantMono(32, (short) -8_192),
                        1,
                        SMALL_BUFFER,
                        null)) {
            assertTrue(
                    incoming.awaitReady(2, TimeUnit.SECONDS));
            long incomingVoice =
                    mixer.replace(
                            outgoingVoice,
                            incoming,
                            AudioBus.MUSIC,
                            0.0f,
                            -1.0f,
                            4,
                            5);
            float[] transition = new float[12];
            mixer.render(transition, 6);

            assertArrayEquals(
                    new float[] {
                        0.25f,
                        0.0f,
                        0.125f,
                        0.0f,
                        0.0f,
                        0.0f,
                        -0.125f,
                        0.0f,
                        -0.25f,
                        0.0f,
                        -0.25f,
                        0.0f
                    },
                    transition);
            assertEquals(1, mixer.logicalVoiceCount());
            assertEquals(1, mixer.activeVoiceCount());
            assertTrue(
                    mixer.events().snapshotSince(0L).stream()
                            .anyMatch(event ->
                                    event.type()
                                                    == AudioEventType
                                                            .VOICE_STOPPED
                                            && event.voiceId()
                                                    == outgoingVoice));
            assertTrue(
                    mixer.events().snapshotSince(0L).stream()
                            .anyMatch(event ->
                                    event.type()
                                                    == AudioEventType
                                                            .VOICE_STARTED
                                            && event.voiceId()
                                                    == incomingVoice));
        }
    }

    @Test
    void terminalDecoderFailureRetiresVoiceInsteadOfOwningSilence()
            throws Exception {
        short[] samples = constantMono(256, (short) 8_192);
        AtomicReference<ArrayDecoder> decoder =
                new AtomicReference<>();
        CountDownLatch failGate = new CountDownLatch(1);
        PcmStream.BufferConfig buffer =
                new PcmStream.BufferConfig(64, 2, 1);
        try (PcmStream stream =
                PcmStream.prepare(
                        "failed-read",
                        () -> {
                            ArrayDecoder opened =
                                    new ArrayDecoder(
                                            samples,
                                            1,
                                            new CopyOnWriteArrayList<>());
                            opened.blockAtFrame = 64;
                            opened.readGate = failGate;
                            opened.failAtFrame = 64;
                            decoder.set(opened);
                            return opened;
                        },
                        buffer,
                        null)) {
            assertTrue(
                    stream.awaitReady(2, TimeUnit.SECONDS));
            AudioMixer mixer = new AudioMixer();
            long voice =
                    mixer.play(
                            stream,
                            AudioBus.AMBIENCE,
                            0.0f,
                            -1.0f,
                            4);
            float[] primed = new float[64 * 2];
            mixer.render(primed, 64);
            failGate.countDown();
            for (int attempt = 0;
                    attempt < 200
                            && stream.decoderFailure() == null;
                    attempt++) {
                TimeUnit.MILLISECONDS.sleep(1L);
            }
            assertInstanceOf(
                    IOException.class,
                    stream.decoderFailure());
            float[] failedFrame = new float[2];
            mixer.render(failedFrame, 1);

            assertEquals(0, mixer.logicalVoiceCount());
            assertEquals(0, mixer.activeVoiceCount());
            assertTrue(
                    mixer.events().snapshotSince(0L).stream()
                            .anyMatch(event ->
                                    event.type()
                                                    == AudioEventType
                                                            .STREAM_FAILED
                                            && event.voiceId()
                                                    == voice));
            assertTrue(
                    mixer.events().snapshotSince(0L).stream()
                            .noneMatch(event ->
                                    event.type()
                                            == AudioEventType
                                                    .STREAM_UNDERRUN));
            assertTrue(decoder.get().closed);
        } finally {
            failGate.countDown();
        }
    }

    @Test
    void queueRejectionReleasesSingleUseStreamWithoutChangingOwner()
            throws Exception {
        AudioMixer mixer = new AudioMixer(1, 32, 1);
        PcmClip resident =
                new PcmClip(
                        "owner",
                        1,
                        constantMono(8, (short) 8_192));
        long owner =
                mixer.play(
                        resident,
                        AudioBus.MUSIC,
                        true,
                        0.0f,
                        -1.0f,
                        3);
        mixer.render(new float[2], 1);
        assertTrue(
                mixer.setBusMuted(
                        AudioBus.UI, true, 0));

        try (PcmStream rejected =
                stream(
                        constantMono(128, (short) -8_192),
                        1,
                        SMALL_BUFFER,
                        null)) {
            assertTrue(
                    rejected.awaitReady(2, TimeUnit.SECONDS));
            assertEquals(
                    AudioMixer.NO_VOICE,
                    mixer.replace(
                            owner,
                            rejected,
                            AudioBus.MUSIC,
                            0.0f,
                            -1.0f,
                            3,
                            5));
            float[] output = new float[2];
            mixer.render(output, 1);

            assertArrayEquals(
                    new float[] {0.25f, 0.0f}, output);
            assertEquals(1, mixer.logicalVoiceCount());
            assertFalse(rejected.isReady());
        }
    }

    @Test
    void rejectsUnprimedPlaybackAndOutOfRangeBufferPolicies()
            throws Exception {
        CountDownLatch openGate = new CountDownLatch(1);
        try (PcmStream preparing =
                PcmStream.prepare(
                        "not-ready",
                        () -> {
                            try {
                                openGate.await();
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IOException(interrupted);
                            }
                            return new ArrayDecoder(
                                    ascendingMono(128),
                                    1,
                                    new CopyOnWriteArrayList<>());
                        },
                        SMALL_BUFFER,
                        null)) {
            AudioMixer mixer = new AudioMixer();
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            mixer.play(
                                    preparing,
                                    AudioBus.MUSIC,
                                    0.0f,
                                    0.0f,
                                    1));
        } finally {
            openGate.countDown();
        }

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PcmStream.BufferConfig(
                                PcmStream.DEFAULT_PAGE_FRAMES,
                                PcmStream.MAX_PAGE_COUNT + 1,
                                1));
    }

    private static PcmStream stream(
            short[] samples,
            int channels,
            PcmStream.BufferConfig config,
            PcmLoopRegion loop) {
        return PcmStream.prepare(
                "array",
                () ->
                        new ArrayDecoder(
                                samples,
                                channels,
                                new CopyOnWriteArrayList<>()),
                config,
                loop);
    }

    private static float[] renderResident(
            PcmClip clip, PcmLoopRegion loop, int[] blocks) {
        AudioMixer mixer = new AudioMixer();
        mixer.play(
                clip,
                AudioBus.AMBIENCE,
                loop,
                -9.0f,
                0.25f,
                4);
        return renderBlocks(mixer, blocks);
    }

    private static float[] renderStreamed(
            short[] samples,
            PcmLoopRegion loop,
            int[] blocks)
            throws Exception {
        int frames = 0;
        for (int block : blocks) {
            frames += block;
        }
        try (PcmStream stream =
                stream(samples, 1, SMALL_BUFFER, loop)) {
            assertTrue(
                    stream.awaitReady(2, TimeUnit.SECONDS));
            assertTrue(
                    stream.awaitBufferedFrames(
                            frames, 2, TimeUnit.SECONDS));
            AudioMixer mixer = new AudioMixer();
            mixer.play(
                    stream,
                    AudioBus.AMBIENCE,
                    -9.0f,
                    0.25f,
                    4);
            return renderBlocks(mixer, blocks);
        }
    }

    private static float[] renderBlocks(
            AudioMixer mixer, int[] blocks) {
        int frames = 0;
        for (int block : blocks) {
            frames += block;
        }
        float[] result = new float[frames * 2];
        int destination = 0;
        for (int blockFrames : blocks) {
            float[] block = new float[blockFrames * 2];
            mixer.render(block, blockFrames);
            System.arraycopy(
                    block,
                    0,
                    result,
                    destination,
                    block.length);
            destination += block.length;
        }
        return result;
    }

    private static short[] ascendingMono(int frames) {
        short[] samples = new short[frames];
        for (int frame = 0; frame < frames; frame++) {
            samples[frame] =
                    (short) ((frame % 32) * 512 - 8_192);
        }
        return samples;
    }

    private static short[] constantMono(
            int frames, short value) {
        short[] samples = new short[frames];
        java.util.Arrays.fill(samples, value);
        return samples;
    }

    private static final class ArrayDecoder
            implements PcmStreamDecoder {
        private final short[] samples;
        private final int channels;
        private final List<String> threadLog;
        private int positionFrame;
        private volatile int blockAtFrame = Integer.MAX_VALUE;
        private volatile int failAtFrame = Integer.MAX_VALUE;
        private volatile CountDownLatch readGate;
        private volatile boolean closed;

        ArrayDecoder(
                short[] samples,
                int channels,
                List<String> threadLog) {
            this.samples = samples.clone();
            this.channels = channels;
            this.threadLog = threadLog;
        }

        @Override
        public int channels() {
            log("channels");
            return channels;
        }

        @Override
        public int frameCount() {
            log("frameCount");
            return samples.length / channels;
        }

        @Override
        public int readFrames(
                short[] destination,
                int destinationFrameOffset,
                int maxFrames)
                throws IOException {
            log("read");
            CountDownLatch gate = readGate;
            if (positionFrame >= blockAtFrame && gate != null) {
                try {
                    gate.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            }
            if (positionFrame >= failAtFrame) {
                throw new IOException("injected decoder failure");
            }
            int available =
                    frameCount() - positionFrame;
            int frames =
                    Math.min(maxFrames, available);
            if (positionFrame < failAtFrame) {
                frames =
                        Math.min(
                                frames,
                                failAtFrame - positionFrame);
            }
            if (positionFrame < blockAtFrame) {
                frames =
                        Math.min(
                                frames,
                                blockAtFrame - positionFrame);
            }
            System.arraycopy(
                    samples,
                    positionFrame * channels,
                    destination,
                    destinationFrameOffset * channels,
                    frames * channels);
            positionFrame += frames;
            return frames;
        }

        @Override
        public void seekFrame(int sourceFrame) {
            log("seek");
            positionFrame = sourceFrame;
        }

        @Override
        public void close() {
            log("close");
            closed = true;
        }

        private void log(String operation) {
            threadLog.add(
                    operation
                            + ":"
                            + Thread.currentThread().getName());
        }
    }

}
