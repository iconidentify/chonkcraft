package net.chonkbase.runtime.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Device-free qualification at the exact shape of the first planned
 * production score bed.
 */
class PcmStreamProductionQualificationTest {
    private static final int ENTRY_FRAMES = 320_000;
    private static final int LOOP_CROSSFADE_FRAMES = 4_800;
    private static final int SOURCE_FRAMES = 5_440_000;
    private static final int CROSSFADE_START_FRAME =
            SOURCE_FRAMES - LOOP_CROSSFADE_FRAMES;
    private static final int LOOP_RESTART_FRAME =
            ENTRY_FRAMES + LOOP_CROSSFADE_FRAMES;
    // The two base-N digits identify every production source frame while
    // keeping both channels comfortably below the mixer's limiter ceiling.
    private static final int FRAME_IDENTITY_BASE = 24_576;
    private static final float I16_TO_FLOAT = 1.0f / 32_768.0f;
    private static final PcmLoopRegion PRODUCTION_LOOP =
            new PcmLoopRegion(
                    ENTRY_FRAMES,
                    SOURCE_FRAMES,
                    LOOP_CROSSFADE_FRAMES);
    private static final PcmStream.BufferConfig PRODUCTION_BUFFER =
            PcmStream.BufferConfig.defaults();

    @Test
    @Timeout(30)
    void completeProductionLengthSourceAndFirstSeamStayInsideFixedBufferAndPageBounds()
            throws Exception {
        GeneratedProductionDecoder decoder =
                new GeneratedProductionDecoder(null, null);
        long expectedAccountedWorkingSet =
                PRODUCTION_BUFFER.pageRingBytes()
                        + (long) PRODUCTION_BUFFER.pageFrames()
                                * 2L
                                * Short.BYTES
                        + (long) LOOP_CROSSFADE_FRAMES
                                * 2L
                                * Float.BYTES;
        long finalSeamFrame = SOURCE_FRAMES - 1L;
        long firstWrappedFrame = SOURCE_FRAMES;
        long seamFrame = PcmStream.FRAME_UNAVAILABLE;
        long wrappedFrame = PcmStream.FRAME_UNAVAILABLE;

        try (PcmStream stream =
                PcmStream.prepare(
                        "production-length-qualification",
                        () -> decoder,
                        PRODUCTION_BUFFER,
                        PRODUCTION_LOOP)) {
            assertTrue(stream.awaitReady(5, TimeUnit.SECONDS));
            assertEquals(2, stream.channels());
            assertEquals(SOURCE_FRAMES, stream.sourceFrameCount());
            assertEquals(
                    PRODUCTION_BUFFER.pageRingBytes(),
                    stream.bufferCapacityBytes());
            assertEquals(
                    expectedAccountedWorkingSet,
                    stream.audioWorkingSetBytes());
            assertTrue(
                    expectedAccountedWorkingSet < 256L * 1_024L,
                    "accounted production stream buffers must stay below 256 KiB");

            long playbackFrame = 0L;
            long frameExclusive = SOURCE_FRAMES + 1L;
            long peakBufferedFrames = 0L;
            while (playbackFrame < frameExclusive) {
                int chunkFrames = (int) Math.min(
                        PRODUCTION_BUFFER.pageFrames(),
                        frameExclusive - playbackFrame);
                assertTrue(
                        stream.awaitBufferedFrames(
                                chunkFrames, 5, TimeUnit.SECONDS),
                        "producer did not publish the next bounded page at frame "
                                + playbackFrame);
                peakBufferedFrames = Math.max(
                        peakBufferedFrames, stream.bufferedFrames());
                for (int offset = 0; offset < chunkFrames; offset++) {
                    long currentFrame = playbackFrame + offset;
                    long packed = stream.frameAt(currentFrame);
                    if (packed == PcmStream.FRAME_UNAVAILABLE) {
                        throw new AssertionError(
                                "qualified traversal underruns at frame "
                                        + currentFrame);
                    }
                    long expected = expectedPackedFrame(currentFrame);
                    if (packed != expected) {
                        throw new AssertionError(
                                "source oracle mismatch at playback frame "
                                        + currentFrame
                                        + " (source frame "
                                        + expectedSourceFrame(currentFrame)
                                        + "): expected "
                                        + describe(expected)
                                        + ", found "
                                        + describe(packed));
                    }
                    if (currentFrame == finalSeamFrame) {
                        seamFrame = packed;
                    } else if (currentFrame == firstWrappedFrame) {
                        wrappedFrame = packed;
                    }
                }
                playbackFrame += chunkFrames;
                stream.consumeThrough(playbackFrame);

                assertEquals(
                        expectedAccountedWorkingSet,
                        stream.audioWorkingSetBytes(),
                        "fixed-buffer accounting changed during traversal");
                assertTrue(
                        stream.bufferedFrames()
                                <= (long) PRODUCTION_BUFFER.pageFrames()
                                        * PRODUCTION_BUFFER.pageCount());
            }

            assertEquals(0L, stream.underrunFrameCount());
            assertFalse(stream.decodedToEnd());
            assertTrue(
                    peakBufferedFrames
                            <= (long) PRODUCTION_BUFFER.pageFrames()
                                    * PRODUCTION_BUFFER.pageCount());
            assertEquals(
                    expectedPackedFrame(finalSeamFrame),
                    seamFrame,
                    "the crossfade endpoint must be the final cached head frame");
            assertEquals(
                    expectedPackedFrame(firstWrappedFrame),
                    wrappedFrame,
                    "the first wrapped frame must continue after the cached head");
            assertEquals(
                    List.of(
                            ENTRY_FRAMES,
                            0,
                            LOOP_RESTART_FRAME),
                    decoder.seekFrames());
            assertEquals(SOURCE_FRAMES, decoder.maximumSourceFrame());
            assertTrue(
                    decoder.maximumReadRequestFrames()
                            <= PRODUCTION_BUFFER.pageFrames());
        }

        assertTrue(decoder.closed());
    }

    @Test
    @Timeout(10)
    void productionShapeStallCannotBlockRenderAndRecoversWithoutGrowingWorkingSet()
            throws Exception {
        CountDownLatch stallEntered = new CountDownLatch(1);
        CountDownLatch releaseStall = new CountDownLatch(1);
        GeneratedProductionDecoder decoder =
                new GeneratedProductionDecoder(stallEntered, releaseStall);

        try (PcmStream stream =
                PcmStream.prepare(
                        "production-stall-qualification",
                        () -> decoder,
                        PRODUCTION_BUFFER,
                        PRODUCTION_LOOP)) {
            assertTrue(stream.awaitReady(5, TimeUnit.SECONDS));
            assertTrue(stallEntered.await(5, TimeUnit.SECONDS));
            long fixedAccountedWorkingSet =
                    stream.audioWorkingSetBytes();
            long primedFrames = stream.bufferedFrames();
            assertEquals(
                    (long) PRODUCTION_BUFFER.pageFrames()
                            * PRODUCTION_BUFFER.primePages(),
                    primedFrames);

            AudioMixer mixer = new AudioMixer();
            long voice = mixer.play(
                    stream,
                    AudioBus.MUSIC,
                    0.0f,
                    0.0f,
                    5);
            int renderFrames = Math.toIntExact(primedFrames + 1L);
            float[] output = new float[renderFrames * 2];
            mixer.render(output, renderFrames);

            assertEquals(
                    1L,
                    releaseStall.getCount(),
                    "render must complete while the decoder remains blocked");
            int underrunSample = (renderFrames - 1) * 2;
            assertEquals(0.0f, output[underrunSample]);
            assertEquals(0.0f, output[underrunSample + 1]);
            assertEquals(1L, stream.underrunFrameCount());
            assertEquals(
                    fixedAccountedWorkingSet,
                    stream.audioWorkingSetBytes());
            assertEquals(
                    1L,
                    eventCount(
                            mixer,
                            AudioEventType.STREAM_UNDERRUN,
                            voice));
            assertEquals(
                    0L,
                    eventCount(
                            mixer,
                            AudioEventType.STREAM_RECOVERED,
                            voice));

            releaseStall.countDown();
            assertTrue(
                    stream.awaitBufferedFrames(
                            1, 5, TimeUnit.SECONDS));
            long expectedRecovery =
                    expectedPackedFrame(primedFrames + 1L);
            assertEquals(
                    expectedRecovery,
                    stream.frameAt(primedFrames + 1L),
                    "recovery must skip the source frame whose presentation time passed");
            float[] recovered = new float[2];
            mixer.render(recovered, 1);

            assertEquals(
                    fixedAccountedWorkingSet,
                    stream.audioWorkingSetBytes());
            assertRecoveredOutput(expectedRecovery, recovered);
            assertEquals(
                    1L,
                    eventCount(
                            mixer,
                            AudioEventType.STREAM_UNDERRUN,
                            voice));
            assertEquals(
                    1L,
                    eventCount(
                            mixer,
                            AudioEventType.STREAM_RECOVERED,
                            voice));
            assertEquals(0L, mixer.events()
                    .snapshotSince(0L)
                    .stream()
                    .filter(event ->
                            event.type() == AudioEventType.STREAM_FAILED
                                    && event.voiceId() == voice)
                    .count());
            assertTrue(
                    decoder.maximumReadRequestFrames()
                            <= PRODUCTION_BUFFER.pageFrames());
        } finally {
            releaseStall.countDown();
        }

        assertTrue(decoder.closed());
    }

    private static long eventCount(
            AudioMixer mixer, AudioEventType type, long voiceId) {
        return mixer.events()
                .snapshotSince(0L)
                .stream()
                .filter(event ->
                        event.type() == type
                                && event.voiceId() == voiceId)
                .count();
    }

    private static float left(long packed) {
        return Float.intBitsToFloat((int) (packed >>> 32));
    }

    private static float right(long packed) {
        return Float.intBitsToFloat((int) packed);
    }

    private static int expectedSourceFrame(long playbackFrame) {
        if (playbackFrame < SOURCE_FRAMES) {
            return Math.toIntExact(playbackFrame);
        }
        int loopBodyFrames = SOURCE_FRAMES - LOOP_RESTART_FRAME;
        return LOOP_RESTART_FRAME
                + (int) Math.floorMod(
                        playbackFrame - SOURCE_FRAMES,
                        (long) loopBodyFrames);
    }

    private static long expectedPackedFrame(long playbackFrame) {
        int sourceFrame = expectedSourceFrame(playbackFrame);
        float left = sampleLeft(sourceFrame) * I16_TO_FLOAT;
        float right = sampleRight(sourceFrame) * I16_TO_FLOAT;
        if (sourceFrame >= CROSSFADE_START_FRAME) {
            int crossfadeIndex =
                    sourceFrame - CROSSFADE_START_FRAME;
            float mix =
                    (float) crossfadeIndex
                            / (float) (LOOP_CROSSFADE_FRAMES - 1);
            int headFrame = ENTRY_FRAMES + crossfadeIndex;
            float headLeft = sampleLeft(headFrame) * I16_TO_FLOAT;
            float headRight = sampleRight(headFrame) * I16_TO_FLOAT;
            left += (headLeft - left) * mix;
            right += (headRight - right) * mix;
        }
        return ((long) Float.floatToRawIntBits(left) << 32)
                | (Float.floatToRawIntBits(right) & 0xffff_ffffL);
    }

    private static void assertRecoveredOutput(
            long expectedFrame, float[] recovered) {
        float angle = (float) Math.PI / 4.0f;
        float stereoCompensation = (float) Math.sqrt(2.0);
        float expectedLeft =
                left(expectedFrame)
                        * (float) Math.cos(angle)
                        * stereoCompensation;
        float expectedRight =
                right(expectedFrame)
                        * (float) Math.sin(angle)
                        * stereoCompensation;
        assertEquals(expectedLeft, recovered[0], 0.000_001f);
        assertEquals(expectedRight, recovered[1], 0.000_001f);
    }

    private static String describe(long packed) {
        return "(" + left(packed) + ", " + right(packed) + ")";
    }

    private static short sampleLeft(int frame) {
        return (short)
                (Math.floorMod(frame, FRAME_IDENTITY_BASE)
                        - FRAME_IDENTITY_BASE / 2);
    }

    private static short sampleRight(int frame) {
        int lowDigit = Math.floorMod(frame, FRAME_IDENTITY_BASE);
        int highDigit = Math.floorDiv(frame, FRAME_IDENTITY_BASE);
        return (short)
                (Math.floorMod(
                                lowDigit * 31 + highDigit,
                                FRAME_IDENTITY_BASE)
                        - FRAME_IDENTITY_BASE / 2);
    }

    private static final class GeneratedProductionDecoder
            implements PcmStreamDecoder {
        private static final int STALL_FRAME =
                PcmStream.DEFAULT_PAGE_FRAMES
                        * PcmStream.DEFAULT_PRIME_PAGES;

        private final CountDownLatch stallEntered;
        private final CountDownLatch releaseStall;
        private final List<Integer> seekFrames =
                new CopyOnWriteArrayList<>();

        private int position;
        private boolean mainPass;
        private boolean stalled;
        private volatile int maximumSourceFrame;
        private volatile int maximumReadRequestFrames;
        private volatile boolean closed;

        private GeneratedProductionDecoder(
                CountDownLatch stallEntered,
                CountDownLatch releaseStall) {
            this.stallEntered = stallEntered;
            this.releaseStall = releaseStall;
        }

        @Override
        public int channels() {
            return 2;
        }

        @Override
        public int frameCount() {
            return SOURCE_FRAMES;
        }

        @Override
        public int readFrames(
                short[] destination,
                int destinationFrameOffset,
                int maxFrames)
                throws IOException {
            maximumReadRequestFrames =
                    Math.max(maximumReadRequestFrames, maxFrames);
            if (shouldStall()) {
                stalled = true;
                stallEntered.countDown();
                try {
                    releaseStall.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(
                            "qualification stall interrupted",
                            interrupted);
                }
            }

            int frames = Math.min(maxFrames, SOURCE_FRAMES - position);
            if (stallEntered != null
                    && mainPass
                    && !stalled
                    && position < STALL_FRAME) {
                frames = Math.min(frames, STALL_FRAME - position);
            }
            for (int frame = 0; frame < frames; frame++) {
                int sourceFrame = position + frame;
                int sample = (destinationFrameOffset + frame) * 2;
                destination[sample] = sampleLeft(sourceFrame);
                destination[sample + 1] = sampleRight(sourceFrame);
            }
            position += frames;
            maximumSourceFrame =
                    Math.max(maximumSourceFrame, position);
            return frames;
        }

        private boolean shouldStall() {
            return stallEntered != null
                    && mainPass
                    && !stalled
                    && position >= STALL_FRAME;
        }

        @Override
        public void seekFrame(int sourceFrame) {
            seekFrames.add(sourceFrame);
            position = sourceFrame;
            if (sourceFrame == 0) {
                mainPass = true;
            }
        }

        @Override
        public void close() {
            closed = true;
        }

        private List<Integer> seekFrames() {
            return List.copyOf(seekFrames);
        }

        private int maximumSourceFrame() {
            return maximumSourceFrame;
        }

        private int maximumReadRequestFrames() {
            return maximumReadRequestFrames;
        }

        private boolean closed() {
            return closed;
        }
    }
}
