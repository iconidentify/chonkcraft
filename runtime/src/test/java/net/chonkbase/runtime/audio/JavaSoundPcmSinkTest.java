package net.chonkbase.runtime.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import javax.sound.sampled.AudioFormat;
import org.junit.jupiter.api.Test;

class JavaSoundPcmSinkTest {

    /**
     * How long a background thread is given to be scheduled at all.
     *
     * <p>The same reasoning as its sibling in AudioOutputDriverTest: two
     * seconds is plenty on an idle machine and not plenty on a loaded one,
     * and a loaded machine is exactly when a suite gets run. A deadline this
     * short measures how busy the computer is, not whether the audio sink
     * retries.
     */
    private static final long SCHEDULING_PATIENCE = 15L;
    @Test
    void recoveryPolicyBoundsProviderDeadline() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new JavaSoundPcmSink.RecoveryPolicy(
                        1, 5, 10, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new JavaSoundPcmSink.RecoveryPolicy(
                        1,
                        5,
                        10,
                        JavaSoundPcmSink
                                        .MAX_PROVIDER_OPEN_TIMEOUT_MILLIS
                                + 1));
        assertEquals(
                JavaSoundPcmSink.DEFAULT_PROVIDER_OPEN_TIMEOUT_MILLIS,
                new JavaSoundPcmSink.RecoveryPolicy(1, 5, 10)
                        .providerOpenTimeoutMillis());
    }

    @Test
    void opensExpectedFormatAndEncodesLittleEndianPcm16()
            throws Exception {
        FakeDevice device = new FakeDevice();
        CapturingFactory factory = new CapturingFactory(device);
        JavaSoundPcmSink sink = new JavaSoundPcmSink(factory);

        sink.open(PcmFormat.GAME_STEREO, 4);
        assertEquals(0, factory.openCalls.get(), "open only configures");
        sink.start();
        await(sink::isOutputAvailable, "default route did not open");
        int written = sink.write(
                new float[] {-1.0f, -0.5f, 0.5f, 1.0f}, 0, 2);
        sink.close();

        assertEquals(2, written);
        assertEquals(48_000.0f, factory.format.getSampleRate());
        assertEquals(16, factory.format.getSampleSizeInBits());
        assertEquals(2, factory.format.getChannels());
        assertFalse(factory.format.isBigEndian());
        assertEquals(16, factory.bufferBytes);
        assertArrayEquals(
                new byte[] {
                    0x01,
                    (byte) 0x80,
                    0x01,
                    (byte) 0xc0,
                    0x00,
                    0x40,
                    (byte) 0xff,
                    0x7f
                },
                device.bytes);
        assertEquals(1, device.startCalls);
        assertTrue(device.closed);
        assertTrue(sink.lastDeviceFailure() == null);
    }

    @Test
    void reportsFrameAlignedShortWritesForDriverRetry() throws Exception {
        FakeDevice device = new FakeDevice();
        device.maxWriteBytes = 4;
        JavaSoundPcmSink sink =
                new JavaSoundPcmSink(new CapturingFactory(device));
        sink.open(PcmFormat.GAME_STEREO, 4);
        sink.start();
        await(sink::isOutputAvailable, "route did not open");

        assertEquals(
                1,
                sink.write(
                        new float[] {0.1f, 0.2f, 0.3f, 0.4f},
                        0,
                        2));
        sink.close();
    }

    @Test
    void validatesLifecycleFormatAndTreatsInvalidDeviceCountAsRouteLoss()
            throws Exception {
        FakeDevice device = new FakeDevice();
        JavaSoundPcmSink sink =
                new JavaSoundPcmSink(new CapturingFactory(device));

        assertThrows(IllegalStateException.class, sink::start);
        assertThrows(
                IllegalArgumentException.class,
                () -> sink.open(new PcmFormat(44_100, 2), 512));

        sink.open(PcmFormat.GAME_STEREO, 4);
        assertThrows(
                IllegalStateException.class,
                () -> sink.write(new float[] {0.0f, 0.0f}, 0, 1));
        sink.start();
        await(sink::isOutputAvailable, "route did not open");
        device.invalidWriteBytes = 3;

        assertEquals(
                1,
                sink.write(new float[] {0.0f, 0.0f}, 0, 1),
                "the damaged block is consumed rather than replayed");
        assertInstanceOf(IOException.class, sink.lastDeviceFailure());
        assertFalse(sink.isOutputAvailable());
        assertEquals(1, device.closeCalls);

        sink.close();
        sink.close();
        assertEquals(1, device.closeCalls);
    }

    @Test
    void nonFiniteSamplesBecomeSilence() throws Exception {
        FakeDevice device = new FakeDevice();
        JavaSoundPcmSink sink =
                new JavaSoundPcmSink(new CapturingFactory(device));
        sink.open(PcmFormat.GAME_STEREO, 1);
        sink.start();
        await(sink::isOutputAvailable, "route did not open");
        sink.write(
                new float[] {Float.NaN, Float.POSITIVE_INFINITY},
                0,
                1);
        sink.close();
        assertArrayEquals(new byte[] {0, 0, 0, 0}, device.bytes);
    }

    @Test
    void initialLineOpenFailureUsesDeterministicBackoffThenRecovers()
            throws Exception {
        FakeClock clock = new FakeClock();
        AtomicInteger opens = new AtomicInteger();
        FakeDevice recovered = new FakeDevice();
        JavaSoundPcmSink sink = new JavaSoundPcmSink(
                (format, bufferBytes) -> {
                    if (opens.incrementAndGet() <= 2) {
                        throw new IOException(
                                "default route unavailable");
                    }
                    return recovered;
                },
                clock,
                new JavaSoundPcmSink.RecoveryPolicy(3, 10, 40, 100));
        sink.open(PcmFormat.GAME_STEREO, 4);

        sink.start();
        await(
                () -> opens.get() == 1
                        && sink.outputStatus().state()
                                == AudioOutputStatus.RouteState.BACKING_OFF,
                "initial failure was not recorded");

        assertEquals(2, sink.write(new float[4], 0, 2));
        assertEquals(1, opens.get(), "no retry before first deadline");

        clock.advanceMillis(10);
        assertEquals(2, sink.write(new float[4], 0, 2));
        await(() -> opens.get() == 2, "first retry did not run");
        assertEquals(1, sink.reopenAttemptCount());
        assertFalse(sink.recoveryExhausted());

        clock.advanceMillis(19);
        sink.write(new float[4], 0, 2);
        assertEquals(2, opens.get(), "second delay doubles to 20 ms");

        clock.advanceMillis(1);
        sink.write(new float[4], 0, 2);
        await(sink::isOutputAvailable, "recovered route did not open");
        assertEquals(3, opens.get());
        assertEquals(2, sink.reopenAttemptCount());
        assertEquals(1, sink.successfulReopenCount());
        assertNotNull(sink.lastDeviceFailure());
        sink.write(new float[4], 0, 2);
        assertEquals(8, recovered.bytes.length);
        sink.close();
        assertEquals(1, recovered.closeCalls);
    }

    @Test
    void midWriteDeviceLossClosesExactLineAndDoesNotReplayBlock()
            throws Exception {
        FakeClock clock = new FakeClock();
        AtomicInteger opens = new AtomicInteger();
        FakeDevice lost = new FakeDevice();
        lost.writeFailure = new IOException("USB route disappeared");
        FakeDevice replacement = new FakeDevice();
        JavaSoundPcmSink sink = new JavaSoundPcmSink(
                (format, bufferBytes) ->
                        opens.incrementAndGet() == 1 ? lost : replacement,
                clock,
                new JavaSoundPcmSink.RecoveryPolicy(2, 5, 10, 100));
        sink.open(PcmFormat.GAME_STEREO, 4);
        sink.start();
        await(sink::isOutputAvailable, "initial route did not open");

        assertEquals(2, sink.write(new float[4], 0, 2));
        assertFalse(sink.isOutputAvailable());
        assertEquals(1, lost.closeCalls);
        assertEquals(0, replacement.bytes.length);

        clock.advanceMillis(5);
        assertEquals(2, sink.write(new float[4], 0, 2));
        await(sink::isOutputAvailable, "replacement route did not open");
        sink.write(new float[4], 0, 2);

        assertEquals(1, sink.successfulReopenCount());
        assertEquals(8, replacement.bytes.length);
        assertEquals(2, opens.get());
        sink.close();
        assertEquals(1, replacement.closeCalls);
    }

    @Test
    void repeatedOpenFailureExhaustsBudgetWithoutFurtherRetryOrSpin()
            throws Exception {
        FakeClock clock = new FakeClock();
        AtomicInteger opens = new AtomicInteger();
        JavaSoundPcmSink sink = new JavaSoundPcmSink(
                (format, bufferBytes) -> {
                    opens.incrementAndGet();
                    throw new IOException("no route");
                },
                clock,
                new JavaSoundPcmSink.RecoveryPolicy(3, 5, 20, 100));
        sink.open(PcmFormat.GAME_STEREO, 2);
        sink.start();
        await(() -> opens.get() == 1, "initial open did not run");

        int expectedOpens = 1;
        for (long delay : new long[] {5L, 10L, 20L}) {
            clock.advanceMillis(delay);
            assertEquals(1, sink.write(new float[2], 0, 1));
            int target = ++expectedOpens;
            await(() -> opens.get() == target, "retry did not run");
        }
        await(sink::recoveryExhausted, "recovery did not exhaust");

        assertEquals(4, opens.get(), "one initial plus three reopens");
        assertEquals(3, sink.reopenAttemptCount());
        assertFalse(sink.isOutputAvailable());

        clock.advanceMillis(10_000);
        for (int index = 0; index < 100; index++) {
            assertEquals(1, sink.write(new float[2], 0, 1));
        }
        assertEquals(4, opens.get(), "exhausted recovery stays silent");
        sink.close();
    }

    @Test
    void failedCandidateStartIsClosedBeforeRetry() throws Exception {
        FakeClock clock = new FakeClock();
        FakeDevice candidate = new FakeDevice();
        candidate.startFailure =
                new IOException("line rejected start");
        JavaSoundPcmSink sink = new JavaSoundPcmSink(
                new CapturingFactory(candidate),
                clock,
                new JavaSoundPcmSink.RecoveryPolicy(1, 5, 5, 100));
        sink.open(PcmFormat.GAME_STEREO, 2);

        sink.start();
        await(() -> candidate.closeCalls == 1, "candidate was not closed");

        assertFalse(sink.isOutputAvailable());
        assertInstanceOf(IOException.class, sink.lastDeviceFailure());
        sink.close();
        assertEquals(1, candidate.closeCalls);
    }

    @Test
    void stopSuppressesRecoveryAndExplicitResumeGetsFreshLine()
            throws Exception {
        FakeClock clock = new FakeClock();
        AtomicInteger opens = new AtomicInteger();
        FakeDevice first = new FakeDevice();
        FakeDevice resumed = new FakeDevice();
        JavaSoundPcmSink sink = new JavaSoundPcmSink(
                (format, bufferBytes) ->
                        opens.incrementAndGet() == 1 ? first : resumed,
                clock,
                new JavaSoundPcmSink.RecoveryPolicy(2, 5, 10, 100));
        sink.open(PcmFormat.GAME_STEREO, 2);
        sink.start();
        await(sink::isOutputAvailable, "initial route did not open");

        sink.stop();
        clock.advanceMillis(1_000);

        assertFalse(sink.isOutputAvailable());
        assertEquals(AudioOutputStatus.RouteState.STOPPED,
                sink.outputStatus().state());
        assertEquals(1, first.closeCalls);
        assertThrows(
                IllegalStateException.class,
                () -> sink.write(new float[2], 0, 1));
        assertEquals(1, opens.get());

        sink.start();
        await(sink::isOutputAvailable, "resumed route did not open");

        assertEquals(2, opens.get());
        assertEquals(0, sink.reopenAttemptCount());
        sink.close();
        assertEquals(1, resumed.closeCalls);
    }

    @Test
    void closeWhileRecoveringPreventsAnyLaterDeviceOpen()
            throws Exception {
        FakeClock clock = new FakeClock();
        AtomicInteger opens = new AtomicInteger();
        JavaSoundPcmSink sink = new JavaSoundPcmSink(
                (format, bufferBytes) -> {
                    opens.incrementAndGet();
                    throw new IOException("missing");
                },
                clock,
                new JavaSoundPcmSink.RecoveryPolicy(3, 5, 20, 100));
        sink.open(PcmFormat.GAME_STEREO, 2);
        sink.start();
        await(() -> opens.get() == 1, "initial open did not run");

        sink.close();
        clock.advanceMillis(1_000);

        assertThrows(
                IllegalStateException.class,
                () -> sink.write(new float[2], 0, 1));
        assertEquals(1, opens.get());
        assertFalse(sink.isOutputAvailable());
        assertEquals(
                AudioOutputStatus.RouteState.CLOSED,
                sink.outputStatus().state());
        await(
                () -> !sink.acquisitionWorkerAlive(),
                "idle acquisition lane did not exit");
    }

    @Test
    void closeDuringDeviceOpenRejectsStaleLineAndCoalescesNewLifecycle()
            throws Exception {
        AtomicInteger opens = new AtomicInteger();
        CountDownLatch firstOpenEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstOpen = new CountDownLatch(1);
        FakeDevice stale = new FakeDevice();
        FakeDevice replacement = new FakeDevice();
        JavaSoundPcmSink sink = new JavaSoundPcmSink(
                (format, bufferBytes) -> {
                    if (opens.incrementAndGet() == 1) {
                        firstOpenEntered.countDown();
                        if (!releaseFirstOpen.await(2, TimeUnit.SECONDS)) {
                            throw new IOException(
                                    "test did not release stale open");
                        }
                        return stale;
                    }
                    return replacement;
                });
        sink.open(PcmFormat.GAME_STEREO, 2);
        sink.start();
        assertTrue(firstOpenEntered.await(1, TimeUnit.SECONDS));

        sink.close();
        sink.open(PcmFormat.GAME_STEREO, 2);
        sink.start();
        assertEquals(
                1,
                opens.get(),
                "one blocked lane forbids concurrent provider opens");

        releaseFirstOpen.countDown();
        await(() -> stale.closeCalls == 1, "stale line was not closed");
        await(sink::isOutputAvailable, "replacement route did not open");

        assertEquals(2, opens.get());
        assertEquals(0, stale.startCalls);
        assertEquals(1, replacement.startCalls);
        sink.close();
        assertEquals(1, replacement.closeCalls);
    }

    @Test
    void blockedProviderTimesOutOnceWithoutBlockingStartWriteOrClose()
            throws Exception {
        FakeClock clock = new FakeClock();
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger activeCalls = new AtomicInteger();
        AtomicInteger peakCalls = new AtomicInteger();
        AtomicBoolean acquisitionWasDaemon = new AtomicBoolean();
        FakeDevice late = new FakeDevice();
        JavaSoundPcmSink sink = new JavaSoundPcmSink(
                (format, bufferBytes) -> {
                    opens.incrementAndGet();
                    int active = activeCalls.incrementAndGet();
                    peakCalls.accumulateAndGet(active, Math::max);
                    acquisitionWasDaemon.set(Thread.currentThread().isDaemon());
                    providerEntered.countDown();
                    try {
                        releaseProvider.await();
                        return late;
                    } finally {
                        activeCalls.decrementAndGet();
                    }
                },
                clock,
                new JavaSoundPcmSink.RecoveryPolicy(3, 5, 20, 25));
        sink.open(PcmFormat.GAME_STEREO, 2);

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofMillis(250), sink::start);
        assertTrue(providerEntered.await(1, TimeUnit.SECONDS));
        assertTrue(acquisitionWasDaemon.get());
        assertEquals(1, sink.write(new float[2], 0, 1));

        clock.advanceMillis(24);
        assertEquals(
                AudioOutputStatus.RouteState.OPENING,
                sink.outputStatus().state());
        clock.advanceMillis(1);
        assertEquals(1, sink.write(new float[2], 0, 1));

        AudioOutputStatus timedOut = sink.outputStatus();
        assertEquals(
                AudioOutputStatus.RouteState.PROVIDER_STALLED,
                timedOut.state());
        assertEquals(1, timedOut.providerOpenTimeouts());
        assertInstanceOf(
                AudioDeviceOpenTimeoutException.class,
                timedOut.lastDeviceFailure());

        for (int index = 0; index < 100; index++) {
            clock.advanceMillis(100);
            assertEquals(1, sink.write(new float[2], 0, 1));
        }
        assertEquals(1, opens.get());
        assertEquals(1, peakCalls.get());
        assertEquals(1, sink.providerOpenTimeoutCount());

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofMillis(250), sink::close);
        assertEquals(
                AudioOutputStatus.RouteState.CLOSED,
                sink.outputStatus().state());
        assertTrue(
                sink.acquisitionWorkerAlive(),
                "the one uncooperative call may retain only its daemon lane");

        releaseProvider.countDown();
        await(() -> late.closeCalls == 1, "late candidate was not closed");
        await(
                () -> !sink.acquisitionWorkerAlive(),
                "released acquisition lane did not exit");
        assertEquals(0, late.startCalls);
    }

    @Test
    void timedOutLateCandidateIsRejectedThenLatestAutomaticRetryRecovers()
            throws Exception {
        FakeClock clock = new FakeClock();
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        AtomicInteger opens = new AtomicInteger();
        FakeDevice late = new FakeDevice();
        FakeDevice recovered = new FakeDevice();
        JavaSoundPcmSink sink = new JavaSoundPcmSink(
                (format, bufferBytes) -> {
                    if (opens.incrementAndGet() == 1) {
                        providerEntered.countDown();
                        releaseProvider.await();
                        return late;
                    }
                    return recovered;
                },
                clock,
                new JavaSoundPcmSink.RecoveryPolicy(2, 5, 10, 10));
        sink.open(PcmFormat.GAME_STEREO, 2);
        sink.start();
        assertTrue(providerEntered.await(1, TimeUnit.SECONDS));

        clock.advanceMillis(10);
        sink.write(new float[2], 0, 1);
        assertEquals(
                AudioOutputStatus.RouteState.PROVIDER_STALLED,
                sink.outputStatus().state());
        clock.advanceMillis(5);
        for (int index = 0; index < 20; index++) {
            sink.write(new float[2], 0, 1);
        }
        assertEquals(1, opens.get(), "no concurrent retry is permitted");

        releaseProvider.countDown();
        await(() -> late.closeCalls == 1, "late candidate was not closed");
        await(sink::isOutputAvailable, "bounded retry did not recover");
        sink.write(new float[2], 0, 1);

        assertEquals(2, opens.get());
        assertEquals(0, late.startCalls);
        assertEquals(0, late.bytes.length);
        assertEquals(1, sink.reopenAttemptCount());
        assertEquals(1, sink.successfulReopenCount());
        assertEquals(1, sink.providerOpenTimeoutCount());
        assertEquals(4, recovered.bytes.length);
        sink.close();
    }

    private static void await(BooleanSupplier condition, String failure)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SCHEDULING_PATIENCE);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(1L);
        }
        assertTrue(condition.getAsBoolean(), failure);
    }

    private static final class CapturingFactory
            implements JavaSoundPcmSink.DeviceFactory {
        final FakeDevice device;
        final AtomicInteger openCalls = new AtomicInteger();
        volatile AudioFormat format;
        volatile int bufferBytes;

        CapturingFactory(FakeDevice device) {
            this.device = device;
        }

        @Override
        public JavaSoundPcmSink.Pcm16Device open(
                AudioFormat format, int bufferBytes) {
            openCalls.incrementAndGet();
            this.format = format;
            this.bufferBytes = bufferBytes;
            return device;
        }
    }

    private static final class FakeClock implements LongSupplier {
        private long nowNanos;

        @Override
        public synchronized long getAsLong() {
            return nowNanos;
        }

        synchronized void advanceMillis(long millis) {
            nowNanos += TimeUnit.MILLISECONDS.toNanos(millis);
        }
    }

    private static final class FakeDevice
            implements JavaSoundPcmSink.Pcm16Device {
        volatile boolean started;
        volatile boolean closed;
        volatile int startCalls;
        volatile int closeCalls;
        volatile int maxWriteBytes = Integer.MAX_VALUE;
        volatile int invalidWriteBytes = -1;
        volatile Exception startFailure;
        volatile Exception writeFailure;
        volatile byte[] bytes = new byte[0];

        @Override
        public void start() throws Exception {
            if (startFailure != null) {
                throw startFailure;
            }
            startCalls++;
            started = true;
        }

        @Override
        public int write(byte[] pcmBytes, int offset, int length)
                throws Exception {
            if (writeFailure != null) {
                throw writeFailure;
            }
            int accepted = invalidWriteBytes >= 0
                    ? invalidWriteBytes
                    : Math.min(length, maxWriteBytes);
            if (invalidWriteBytes < 0) {
                bytes = Arrays.copyOfRange(
                        pcmBytes, offset, offset + accepted);
            }
            return accepted;
        }

        @Override
        public void stop() {
            started = false;
        }

        @Override
        public void close() {
            closeCalls++;
            closed = true;
        }
    }
}
