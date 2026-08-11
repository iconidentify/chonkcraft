package net.chonkbase.runtime.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AudioOutputDriverTest {

    /**
     * How long a background thread is given to be scheduled at all.
     *
     * <p>Generous on purpose. These wait on a worker reaching its first write,
     * which takes microseconds when the machine is idle and can take seconds
     * when it is not -- and a busy machine is exactly when a suite gets run.
     * One second was enough until several builds ran at once, and then this
     * failed for a reason that had nothing to do with audio. A deadline that
     * measures how loaded the machine is tests the machine.
     */
    private static final long SCHEDULING_PATIENCE = 15L;
    @Test
    void startsPacesWritesAndClosesWorkerWithinBound() throws Exception {
        RecordingSink sink = new RecordingSink();
        AudioOutputDriver driver = new AudioOutputDriver(new AudioMixer(), sink, 48, 192, "audio-test");

        driver.start();
        assertTrue(sink.writeObserved.await(SCHEDULING_PATIENCE, TimeUnit.SECONDS));
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), driver::close);

        assertEquals(PcmFormat.GAME_STEREO, sink.format);
        assertEquals(192, sink.bufferFrames);
        assertEquals(1, sink.openCalls.get());
        assertEquals(1, sink.startCalls.get());
        assertTrue(sink.stopCalls.get() >= 1);
        assertTrue(sink.closeCalls.get() >= 1);
        assertFalse(driver.workerAlive());
        assertTrue(driver.isClosed());
    }

    @Test
    void retriesFrameAlignedShortWrites() throws Exception {
        RecordingSink sink = new RecordingSink();
        sink.maxFramesPerWrite = 3;
        AudioOutputDriver driver = new AudioOutputDriver(new AudioMixer(), sink, 12, 48, "audio-short-write");

        driver.start();
        assertTrue(sink.completeBlockObserved.await(SCHEDULING_PATIENCE, TimeUnit.SECONDS));
        driver.close();

        assertTrue(sink.writeCalls.get() >= 4);
        assertNull(driver.failure());
    }

    @Test
    void closeUnblocksADeviceWriteAndRemainsIdempotent() throws Exception {
        BlockingSink sink = new BlockingSink();
        AudioOutputDriver driver = new AudioOutputDriver(new AudioMixer(), sink, 48, 192, "audio-blocked");
        driver.start();
        assertTrue(sink.writeEntered.await(SCHEDULING_PATIENCE, TimeUnit.SECONDS));

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), driver::close);
        driver.close();

        assertFalse(driver.workerAlive());
        assertEquals(1, sink.closeCalls.get());
    }

    @Test
    void outputFailureIsObservableAndReleasesSink() throws Exception {
        RecordingSink sink = new RecordingSink();
        sink.writeFailure = new IOException("device disappeared");
        AudioOutputDriver driver = new AudioOutputDriver(new AudioMixer(), sink, 48, 192, "audio-failure");
        driver.start();
        assertTrue(sink.writeObserved.await(SCHEDULING_PATIENCE, TimeUnit.SECONDS));

        waitUntilStopped(driver);
        assertNotNull(driver.failure());
        assertFalse(driver.isRunning());
        assertTrue(sink.closeCalls.get() >= 1);
        driver.close();
    }

    @Test
    void repeatedZeroWritesFailInsteadOfSpinningForever() throws Exception {
        RecordingSink sink = new RecordingSink();
        sink.maxFramesPerWrite = 0;
        AudioOutputDriver driver =
                new AudioOutputDriver(new AudioMixer(), sink, 48, 192, "audio-zero-write");
        driver.start();
        assertTrue(sink.writeObserved.await(SCHEDULING_PATIENCE, TimeUnit.SECONDS));

        waitUntilStopped(driver);
        assertTrue(driver.failure() instanceof IOException);
        assertFalse(driver.workerAlive());
        driver.close();
    }

    @Test
    void startupFailureClosesPartialSinkAndCanNeverStartAfterClose() {
        RecordingSink sink = new RecordingSink();
        sink.startFailure = new IOException("no route");
        AudioOutputDriver driver = new AudioOutputDriver(new AudioMixer(), sink, 48, 192, "audio-start-failure");

        assertThrows(IOException.class, driver::start);
        assertNotNull(driver.failure());
        assertTrue(sink.closeCalls.get() >= 1);
        driver.close();
        assertThrows(IllegalStateException.class, driver::start);
    }

    @Test
    void suspendAndResumeKeepOneWorkerAndReleaseOutputOnClose()
            throws Exception {
        RecordingSink sink = new RecordingSink();
        AudioOutputDriver driver = new AudioOutputDriver(
                new AudioMixer(), sink, 48, 192, "audio-suspend-resume");
        driver.start();
        assertTrue(sink.writeObserved.await(SCHEDULING_PATIENCE, TimeUnit.SECONDS));

        driver.suspend();

        assertTrue(driver.isSuspended());
        assertTrue(driver.workerAlive());
        assertFalse(driver.isOutputAvailable());
        assertTrue(sink.stopCalls.get() >= 1);

        assertTrue(driver.resume());
        assertFalse(driver.isSuspended());
        assertTrue(driver.isOutputAvailable());
        assertEquals(2, sink.startCalls.get());
        driver.close();

        assertFalse(driver.workerAlive());
        assertEquals(1, sink.closeCalls.get());
        assertFalse(driver.resume());
    }

    @Test
    void failedExplicitResumeStaysSuspendedAndStillClosesCleanly()
            throws Exception {
        RecordingSink sink = new RecordingSink();
        sink.failStartCall = 2;
        AudioOutputDriver driver = new AudioOutputDriver(
                new AudioMixer(), sink, 48, 192, "audio-resume-failure");
        driver.start();
        assertTrue(sink.writeObserved.await(SCHEDULING_PATIENCE, TimeUnit.SECONDS));
        driver.suspend();

        assertFalse(driver.resume());

        assertTrue(driver.isSuspended());
        assertNotNull(driver.resumeFailure());
        assertTrue(driver.workerAlive());
        driver.close();
        assertFalse(driver.workerAlive());
        assertEquals(1, sink.closeCalls.get());
    }

    @Test
    void recoveringJavaSoundStaysSilentAfterBoundedBudgetAndLeaksNoWorker()
            throws Exception {
        AtomicInteger opens = new AtomicInteger();
        JavaSoundPcmSink sink = new JavaSoundPcmSink(
                (format, bufferBytes) -> {
                    opens.incrementAndGet();
                    throw new IOException("default route absent");
                },
                System::nanoTime,
                new JavaSoundPcmSink.RecoveryPolicy(3, 1, 2));
        AudioOutputDriver driver = new AudioOutputDriver(
                new AudioMixer(), sink, 48, 192, "audio-recovery-exhausted");

        driver.start();
        waitUntil(() -> driver.recoveryExhausted());

        assertEquals(4, opens.get());
        assertEquals(3, driver.reopenAttemptCount());
        assertTrue(driver.isRunning(), "silent mixer fallback keeps pacing");
        assertTrue(driver.workerAlive());
        assertFalse(driver.isOutputAvailable());
        assertNull(driver.failure());
        assertNotNull(driver.lastDeviceFailure());

        driver.close();

        assertFalse(driver.workerAlive());
        assertTrue(driver.isClosed());
    }

    @Test
    void midWriteJavaSoundLossReopensAndEveryLineClosesExactlyOnce()
            throws Exception {
        AtomicInteger opens = new AtomicInteger();
        RecoveringDevice lost =
                new RecoveringDevice(new IOException("headphones removed"));
        RecoveringDevice replacement = new RecoveringDevice(null);
        JavaSoundPcmSink sink = new JavaSoundPcmSink(
                (format, bufferBytes) ->
                        opens.incrementAndGet() == 1 ? lost : replacement,
                System::nanoTime,
                new JavaSoundPcmSink.RecoveryPolicy(2, 1, 2));
        AudioOutputDriver driver = new AudioOutputDriver(
                new AudioMixer(), sink, 48, 192, "audio-route-reopen");

        driver.start();
        assertTrue(replacement.writeObserved.await(SCHEDULING_PATIENCE, TimeUnit.SECONDS));

        assertEquals(2, opens.get());
        assertEquals(1, driver.successfulReopenCount());
        assertEquals(1, lost.closeCalls.get());
        assertTrue(driver.isOutputAvailable());
        assertNull(driver.failure());

        driver.close();

        assertFalse(driver.workerAlive());
        assertEquals(1, lost.closeCalls.get());
        assertEquals(1, replacement.closeCalls.get());
    }

    @Test
    void closeUnblocksJavaSoundWriteAndLeaksNeitherLineNorWorker()
            throws Exception {
        BlockingRecoveringDevice device = new BlockingRecoveringDevice();
        JavaSoundPcmSink sink =
                new JavaSoundPcmSink((format, bufferBytes) -> device);
        AudioOutputDriver driver = new AudioOutputDriver(
                new AudioMixer(), sink, 48, 192, "audio-route-blocked");
        driver.start();
        assertTrue(device.writeEntered.await(SCHEDULING_PATIENCE, TimeUnit.SECONDS));

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(2), driver::close);

        assertFalse(driver.workerAlive());
        assertEquals(1, device.closeCalls.get());
        assertTrue(driver.isClosed());
    }

    @Test
    void blockedProviderCannotDelayDriverStartOrFreezeMixerTimeline()
            throws Exception {
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        RecoveringDevice late = new RecoveringDevice(null);
        JavaSoundPcmSink sink = new JavaSoundPcmSink(
                (format, bufferBytes) -> {
                    providerEntered.countDown();
                    releaseProvider.await();
                    return late;
                });
        AudioMixer mixer = new AudioMixer();
        long voice = mixer.play(
                PcmClip.fromFloats(
                        "timeline-proof", 1, new float[24]),
                AudioBus.MUSIC,
                false,
                0.0f,
                0.0f,
                1);
        assertTrue(voice > AudioMixer.NO_VOICE);
        AudioOutputDriver driver = new AudioOutputDriver(
                mixer, sink, 48, 192, "audio-provider-stall");

        try {
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    Duration.ofMillis(250), driver::start);
            assertTrue(providerEntered.await(SCHEDULING_PATIENCE, TimeUnit.SECONDS));

            waitUntil(() -> mixer.events().snapshotSince(0).stream()
                    .anyMatch(event ->
                            event.voiceId() == voice
                                    && event.type()
                                            == AudioEventType.VOICE_COMPLETED));

            assertTrue(driver.isRunning());
            assertTrue(driver.workerAlive());
            assertEquals(
                    AudioOutputStatus.RouteState.OPENING,
                    driver.outputStatus().state());
            assertNull(driver.failure());

            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    Duration.ofMillis(250), driver::close);
            assertFalse(driver.workerAlive());
            assertEquals(
                    AudioOutputStatus.RouteState.CLOSED,
                    driver.outputStatus().state());
        } finally {
            driver.close();
            releaseProvider.countDown();
        }
        waitUntil(() -> late.closeCalls.get() == 1);
        assertEquals(
                1L,
                late.writeObserved.getCount(),
                "a late route must never receive timeline audio");
    }

    private static void waitUntilStopped(AudioOutputDriver driver) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SCHEDULING_PATIENCE);
        while ((driver.isRunning() || driver.workerAlive()) && System.nanoTime() < deadline) {
            Thread.sleep(2L);
        }
    }

    private static void waitUntil(BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SCHEDULING_PATIENCE);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(2L);
        }
        assertTrue(condition.getAsBoolean(), "condition did not become true");
    }

    private static class RecordingSink implements AudioSink {
        final AtomicInteger openCalls = new AtomicInteger();
        final AtomicInteger startCalls = new AtomicInteger();
        final AtomicInteger writeCalls = new AtomicInteger();
        final AtomicInteger stopCalls = new AtomicInteger();
        final AtomicInteger closeCalls = new AtomicInteger();
        final CountDownLatch writeObserved = new CountDownLatch(1);
        final CountDownLatch completeBlockObserved = new CountDownLatch(1);

        volatile PcmFormat format;
        volatile int bufferFrames;
        volatile int maxFramesPerWrite = Integer.MAX_VALUE;
        volatile IOException startFailure;
        volatile IOException writeFailure;
        volatile int failStartCall = -1;
        private final AtomicInteger framesInCurrentBlock = new AtomicInteger();

        @Override
        public void open(PcmFormat format, int bufferFrames) {
            this.format = format;
            this.bufferFrames = bufferFrames;
            openCalls.incrementAndGet();
        }

        @Override
        public void start() throws Exception {
            int call = startCalls.incrementAndGet();
            if (startFailure != null || call == failStartCall) {
                if (startFailure == null) {
                    throw new IOException("resume route unavailable");
                }
                throw startFailure;
            }
        }

        @Override
        public int write(float[] interleavedSamples, int frameOffset, int frameCount) throws Exception {
            writeCalls.incrementAndGet();
            writeObserved.countDown();
            if (writeFailure != null) {
                throw writeFailure;
            }
            int accepted = Math.min(frameCount, maxFramesPerWrite);
            if (framesInCurrentBlock.addAndGet(accepted) >= 12) {
                completeBlockObserved.countDown();
                framesInCurrentBlock.set(0);
            }
            return accepted;
        }

        @Override
        public void stop() {
            stopCalls.incrementAndGet();
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }

    private static final class BlockingSink extends RecordingSink {
        final CountDownLatch writeEntered = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);

        @Override
        public int write(float[] interleavedSamples, int frameOffset, int frameCount) throws InterruptedException {
            writeEntered.countDown();
            released.await();
            return 0;
        }

        @Override
        public void close() {
            if (closeCalls.getAndIncrement() == 0) {
                released.countDown();
            }
        }
    }

    private static final class RecoveringDevice
            implements JavaSoundPcmSink.Pcm16Device {
        final Exception writeFailure;
        final CountDownLatch writeObserved = new CountDownLatch(1);
        final AtomicInteger closeCalls = new AtomicInteger();

        RecoveringDevice(Exception writeFailure) {
            this.writeFailure = writeFailure;
        }

        @Override
        public void start() {}

        @Override
        public int write(byte[] pcmBytes, int offset, int length)
                throws Exception {
            writeObserved.countDown();
            if (writeFailure != null) {
                throw writeFailure;
            }
            return length;
        }

        @Override
        public void stop() {}

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }

    private static final class BlockingRecoveringDevice
            implements JavaSoundPcmSink.Pcm16Device {
        final CountDownLatch writeEntered = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public void start() {}

        @Override
        public int write(byte[] pcmBytes, int offset, int length)
                throws InterruptedException {
            writeEntered.countDown();
            released.await();
            return 0;
        }

        @Override
        public void stop() {}

        @Override
        public void close() {
            if (closeCalls.getAndIncrement() == 0) {
                released.countDown();
            }
        }
    }
}
