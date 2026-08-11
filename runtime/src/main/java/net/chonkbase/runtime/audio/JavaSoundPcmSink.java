package net.chonkbase.runtime.audio;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

/**
 * Recovering Java Sound output sink for little-endian signed PCM16.
 *
 * <p>Provider acquisition is isolated on exactly one daemon lane. Neither
 * {@link #start()} nor {@link #write(float[], int, int)} calls
 * {@code AudioSystem}, opens a line, or starts a device. A missing, failing, or
 * stalled default route therefore accepts rendered frames into silence while
 * the mixer timeline continues instead of delaying launch or freezing the
 * output worker.
 *
 * <p>Java Sound exposes no portable way to terminate a native provider call
 * that ignores interruption. An acquisition that exceeds its client-side
 * deadline becomes {@link
 * AudioOutputStatus.RouteState#PROVIDER_STALLED}; the one daemon lane remains
 * bounded and no concurrent/thread-per-retry opens are created. A late result
 * is generation-rejected and closed before the latest request may proceed.
 */
public final class JavaSoundPcmSink implements AudioSink, AudioOutputHealth {
    public static final int DEFAULT_MAX_AUTOMATIC_REOPEN_ATTEMPTS = 6;
    public static final long DEFAULT_INITIAL_REOPEN_MILLIS = 100L;
    public static final long DEFAULT_MAX_REOPEN_MILLIS = 2_000L;
    public static final long DEFAULT_PROVIDER_OPEN_TIMEOUT_MILLIS = 2_000L;
    public static final long MAX_PROVIDER_OPEN_TIMEOUT_MILLIS = 30_000L;
    public static final RecoveryPolicy DEFAULT_RECOVERY_POLICY =
            new RecoveryPolicy(
                    DEFAULT_MAX_AUTOMATIC_REOPEN_ATTEMPTS,
                    DEFAULT_INITIAL_REOPEN_MILLIS,
                    DEFAULT_MAX_REOPEN_MILLIS,
                    DEFAULT_PROVIDER_OPEN_TIMEOUT_MILLIS);

    @FunctionalInterface
    interface DeviceFactory {
        Pcm16Device open(AudioFormat format, int bufferBytes) throws Exception;
    }

    interface Pcm16Device {
        void start() throws Exception;

        int write(byte[] pcmBytes, int offset, int length) throws Exception;

        void stop();

        void close();
    }

    public record RecoveryPolicy(
            int maxAutomaticReopenAttempts,
            long initialBackoffMillis,
            long maxBackoffMillis,
            long providerOpenTimeoutMillis) {
        public RecoveryPolicy(
                int maxAutomaticReopenAttempts,
                long initialBackoffMillis,
                long maxBackoffMillis) {
            this(
                    maxAutomaticReopenAttempts,
                    initialBackoffMillis,
                    maxBackoffMillis,
                    DEFAULT_PROVIDER_OPEN_TIMEOUT_MILLIS);
        }

        public RecoveryPolicy {
            if (maxAutomaticReopenAttempts < 0) {
                throw new IllegalArgumentException(
                        "maxAutomaticReopenAttempts must not be negative");
            }
            if (initialBackoffMillis <= 0L
                    || maxBackoffMillis < initialBackoffMillis) {
                throw new IllegalArgumentException(
                        "reopen backoff must be positive and capped at or above its initial value");
            }
            if (providerOpenTimeoutMillis <= 0L
                    || providerOpenTimeoutMillis
                            > MAX_PROVIDER_OPEN_TIMEOUT_MILLIS) {
                throw new IllegalArgumentException(
                        "providerOpenTimeoutMillis must be within 1.."
                                + MAX_PROVIDER_OPEN_TIMEOUT_MILLIS);
            }
        }

        long delayNanos(int attemptNumber) {
            if (attemptNumber <= 0) {
                throw new IllegalArgumentException(
                        "attemptNumber must be positive");
            }
            long delayMillis = initialBackoffMillis;
            for (int attempt = 1;
                    attempt < attemptNumber && delayMillis < maxBackoffMillis;
                    attempt++) {
                delayMillis = Math.min(
                        maxBackoffMillis,
                        delayMillis > Long.MAX_VALUE / 2L
                                ? Long.MAX_VALUE
                                : delayMillis * 2L);
            }
            return TimeUnit.MILLISECONDS.toNanos(delayMillis);
        }

        long providerOpenTimeoutNanos() {
            return TimeUnit.MILLISECONDS.toNanos(
                    providerOpenTimeoutMillis);
        }
    }

    private static final int BYTES_PER_SAMPLE = Short.BYTES;
    private static final String ACQUISITION_THREAD_NAME =
            "seven-days-audio-route-acquisition";

    private final DeviceFactory deviceFactory;
    private final LongSupplier nanoClock;
    private final RecoveryPolicy recoveryPolicy;
    private final Object lifecycleLock = new Object();

    private volatile Pcm16Device device;
    private volatile boolean configured;
    private volatile boolean started;
    private volatile byte[] conversionBuffer;

    // All fields below are guarded by lifecycleLock unless volatile.
    private AudioFormat outputFormat;
    private int outputBufferBytes;
    private long lifecycleGeneration;
    private boolean acquisitionRequested;
    private boolean requestedAttemptAutomatic;
    private boolean openInProgress;
    private long activeAttemptGeneration;
    private long activeAttemptDeadlineNanos = Long.MAX_VALUE;
    private boolean activeAttemptTimedOut;
    private Thread acquisitionWorker;
    private int automaticReopenAttempts;
    private long nextReopenNanos = Long.MAX_VALUE;
    private int successfulReopens;
    private int providerOpenTimeouts;
    private boolean recoveryExhausted;
    private Throwable lastDeviceFailure;

    public JavaSoundPcmSink() {
        this(
                JavaSoundPcmSink::openDefaultDevice,
                System::nanoTime,
                DEFAULT_RECOVERY_POLICY);
    }

    JavaSoundPcmSink(DeviceFactory deviceFactory) {
        this(deviceFactory, System::nanoTime, DEFAULT_RECOVERY_POLICY);
    }

    JavaSoundPcmSink(
            DeviceFactory deviceFactory,
            LongSupplier nanoClock,
            RecoveryPolicy recoveryPolicy) {
        this.deviceFactory =
                Objects.requireNonNull(deviceFactory, "deviceFactory");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.recoveryPolicy =
                Objects.requireNonNull(recoveryPolicy, "recoveryPolicy");
    }

    @Override
    public void open(PcmFormat format, int bufferFrames) {
        Objects.requireNonNull(format, "format");
        if (!PcmFormat.GAME_STEREO.equals(format)) {
            throw new IllegalArgumentException(
                    "Java Sound sink requires 48 kHz stereo mixer output");
        }
        if (bufferFrames <= 0) {
            throw new IllegalArgumentException(
                    "bufferFrames must be positive");
        }

        int bufferBytes = Math.multiplyExact(
                Math.multiplyExact(bufferFrames, format.channels()),
                BYTES_PER_SAMPLE);
        AudioFormat javaFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                format.sampleRate(),
                16,
                format.channels(),
                format.channels() * BYTES_PER_SAMPLE,
                format.sampleRate(),
                false);
        synchronized (lifecycleLock) {
            if (configured) {
                throw new IllegalStateException(
                        "audio sink is already open");
            }
            outputFormat = javaFormat;
            outputBufferBytes = bufferBytes;
            conversionBuffer = new byte[bufferBytes];
            lifecycleGeneration++;
            configured = true;
            started = false;
            acquisitionRequested = false;
            automaticReopenAttempts = 0;
            nextReopenNanos = Long.MAX_VALUE;
            recoveryExhausted = false;
            lastDeviceFailure = null;
            lifecycleLock.notifyAll();
        }
    }

    /**
     * Starts or explicitly resumes output without waiting on a platform
     * provider.
     *
     * <p>A single acquisition request is published to the daemon lane. If an
     * obsolete provider call is still in flight, this request replaces any
     * older pending generation and begins only after that call returns.
     */
    @Override
    public void start() {
        synchronized (lifecycleLock) {
            requireConfiguredLocked();
            if (started) {
                return;
            }
            lifecycleGeneration++;
            started = true;
            automaticReopenAttempts = 0;
            recoveryExhausted = false;
            nextReopenNanos = Long.MAX_VALUE;
            requestAcquisitionLocked(false);
        }
    }

    @Override
    public int write(
            float[] interleavedSamples, int frameOffset, int frameCount) {
        validateWrite(interleavedSamples, frameOffset, frameCount);
        if (frameCount == 0) {
            return 0;
        }

        serviceAcquisitionState();
        Pcm16Device current = device;
        if (current == null) {
            // Keep the mixer/output clock moving while no route is usable.
            return frameCount;
        }

        byte[] scratch = conversionBuffer;
        if (scratch == null) {
            throw new IllegalStateException(
                    "audio sink closed during write");
        }
        int maxChunkFrames = scratch.length
                / (PcmFormat.GAME_STEREO.channels() * BYTES_PER_SAMPLE);
        int totalFrames = 0;
        while (totalFrames < frameCount) {
            int chunkFrames =
                    Math.min(maxChunkFrames, frameCount - totalFrames);
            int sampleOffset =
                    (frameOffset + totalFrames)
                            * PcmFormat.GAME_STEREO.channels();
            int chunkSamples =
                    chunkFrames * PcmFormat.GAME_STEREO.channels();
            encodePcm16(
                    interleavedSamples,
                    sampleOffset,
                    chunkSamples,
                    scratch);
            int requestedBytes = chunkSamples * BYTES_PER_SAMPLE;
            final int writtenBytes;
            try {
                writtenBytes =
                        current.write(scratch, 0, requestedBytes);
                if (writtenBytes < 0
                        || writtenBytes > requestedBytes
                        || (writtenBytes & 3) != 0) {
                    throw new IOException(
                            "audio device returned invalid PCM16 byte count: "
                                    + writtenBytes);
                }
            } catch (Exception deviceFailure) {
                handleDeviceLoss(current, deviceFailure);
                // A partially emitted block is never replayed on a new route.
                return frameCount;
            }
            int writtenFrames = writtenBytes / 4;
            totalFrames += writtenFrames;
            if (writtenFrames < chunkFrames) {
                break;
            }
        }
        return totalFrames;
    }

    /**
     * Suspends output and releases the platform line while retaining format
     * configuration for a later {@link #start()}.
     */
    @Override
    public void stop() {
        Pcm16Device current;
        synchronized (lifecycleLock) {
            lifecycleGeneration++;
            started = false;
            acquisitionRequested = false;
            automaticReopenAttempts = 0;
            recoveryExhausted = false;
            nextReopenNanos = Long.MAX_VALUE;
            current = device;
            device = null;
            lifecycleLock.notifyAll();
        }
        safeStopAndClose(current);
    }

    @Override
    public void close() {
        Pcm16Device current;
        synchronized (lifecycleLock) {
            lifecycleGeneration++;
            configured = false;
            started = false;
            acquisitionRequested = false;
            automaticReopenAttempts = 0;
            recoveryExhausted = false;
            nextReopenNanos = Long.MAX_VALUE;
            outputFormat = null;
            outputBufferBytes = 0;
            conversionBuffer = null;
            current = device;
            device = null;
            lifecycleLock.notifyAll();
        }
        safeStopAndClose(current);
    }

    @Override
    public AudioOutputStatus outputStatus() {
        synchronized (lifecycleLock) {
            markActiveTimeoutIfDueLocked(nanoClock.getAsLong());
            AudioOutputStatus.RouteState state;
            if (!configured) {
                state = AudioOutputStatus.RouteState.CLOSED;
            } else if (!started) {
                state = AudioOutputStatus.RouteState.STOPPED;
            } else if (device != null) {
                state = AudioOutputStatus.RouteState.AVAILABLE;
            } else if (openInProgress && activeAttemptTimedOut) {
                state =
                        AudioOutputStatus.RouteState.PROVIDER_STALLED;
            } else if (openInProgress || acquisitionRequested) {
                state = AudioOutputStatus.RouteState.OPENING;
            } else if (recoveryExhausted) {
                state = AudioOutputStatus.RouteState.EXHAUSTED;
            } else {
                state = AudioOutputStatus.RouteState.BACKING_OFF;
            }
            return new AudioOutputStatus(
                    state,
                    automaticReopenAttempts,
                    successfulReopens,
                    providerOpenTimeouts,
                    lastDeviceFailure);
        }
    }

    @Override
    public boolean isOutputAvailable() {
        return outputStatus().outputAvailable();
    }

    @Override
    public boolean recoveryExhausted() {
        return outputStatus().recoveryExhausted();
    }

    @Override
    public int reopenAttemptCount() {
        return outputStatus().automaticReopenAttempts();
    }

    @Override
    public int successfulReopenCount() {
        return outputStatus().successfulReopens();
    }

    @Override
    public int providerOpenTimeoutCount() {
        return outputStatus().providerOpenTimeouts();
    }

    @Override
    public Throwable lastDeviceFailure() {
        return outputStatus().lastDeviceFailure();
    }

    private void validateWrite(
            float[] interleavedSamples,
            int frameOffset,
            int frameCount) {
        synchronized (lifecycleLock) {
            requireConfiguredLocked();
            if (!started) {
                throw new IllegalStateException(
                        "audio sink is not started");
            }
        }
        if (interleavedSamples == null) {
            throw new IllegalArgumentException(
                    "interleavedSamples must not be null");
        }
        if (frameOffset < 0 || frameCount < 0) {
            throw new IllegalArgumentException(
                    "frameOffset and frameCount must not be negative");
        }
        long endSample =
                ((long) frameOffset + frameCount)
                        * PcmFormat.GAME_STEREO.channels();
        if (endSample > interleavedSamples.length) {
            throw new IllegalArgumentException(
                    "sample buffer is too small for requested frame window");
        }
    }

    /**
     * Performs only bounded state work on the output thread. Provider calls
     * remain exclusively on the daemon acquisition lane.
     */
    private void serviceAcquisitionState() {
        synchronized (lifecycleLock) {
            long now = nanoClock.getAsLong();
            markActiveTimeoutIfDueLocked(now);
            requestAutomaticAcquisitionIfDueLocked(now);
        }
    }

    private void requestAutomaticAcquisitionIfDueLocked(long now) {
        if (!configured
                || !started
                || device != null
                || openInProgress
                || acquisitionRequested
                || recoveryExhausted
                || now - nextReopenNanos < 0L) {
            return;
        }
        requestAcquisitionLocked(true);
    }

    private void requestAcquisitionLocked(boolean automatic) {
        if (!configured || !started || device != null) {
            return;
        }
        acquisitionRequested = true;
        requestedAttemptAutomatic = automatic;
        ensureAcquisitionWorkerLocked();
        lifecycleLock.notifyAll();
    }

    private void ensureAcquisitionWorkerLocked() {
        if (acquisitionWorker != null) {
            return;
        }
        Thread worker =
                new Thread(
                        this::runAcquisitionLane,
                        ACQUISITION_THREAD_NAME);
        worker.setDaemon(true);
        worker.setPriority(Math.max(
                Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        acquisitionWorker = worker;
        worker.start();
    }

    private void runAcquisitionLane() {
        while (true) {
            Attempt attempt;
            synchronized (lifecycleLock) {
                while (!acquisitionRequested) {
                    if (!configured) {
                        acquisitionWorker = null;
                        return;
                    }
                    try {
                        lifecycleLock.wait();
                    } catch (InterruptedException ignored) {
                        // Lifecycle state and generation are authoritative.
                    }
                }
                if (!configured || !started || device != null) {
                    acquisitionRequested = false;
                    continue;
                }

                boolean automatic = requestedAttemptAutomatic;
                acquisitionRequested = false;
                if (automatic
                        && automaticReopenAttempts
                                >= recoveryPolicy
                                        .maxAutomaticReopenAttempts()) {
                    recoveryExhausted = true;
                    nextReopenNanos = Long.MAX_VALUE;
                    continue;
                }
                if (automatic) {
                    automaticReopenAttempts++;
                }

                openInProgress = true;
                activeAttemptGeneration = lifecycleGeneration;
                activeAttemptTimedOut = false;
                long startedAt = nanoClock.getAsLong();
                activeAttemptDeadlineNanos = saturatingAdd(
                        startedAt,
                        recoveryPolicy.providerOpenTimeoutNanos());
                attempt = new Attempt(
                        activeAttemptGeneration,
                        automatic,
                        outputFormat,
                        outputBufferBytes);
            }
            acquire(attempt);
        }
    }

    private void acquire(Attempt attempt) {
        Pcm16Device candidate = null;
        Throwable failure = null;
        try {
            candidate =
                    deviceFactory.open(
                            attempt.format(), attempt.bufferBytes());
            if (candidate == null) {
                throw new IOException(
                        "audio device factory returned null");
            }
            boolean startCandidate;
            synchronized (lifecycleLock) {
                markActiveTimeoutIfDueLocked(nanoClock.getAsLong());
                startCandidate = openInProgress
                        && attempt.generation()
                                == activeAttemptGeneration
                        && attempt.generation()
                                == lifecycleGeneration
                        && configured
                        && started
                        && device == null
                        && !activeAttemptTimedOut;
            }
            if (startCandidate) {
                candidate.start();
            }
        } catch (Throwable openFailure) {
            failure = openFailure;
        }

        boolean accepted = false;
        synchronized (lifecycleLock) {
            long now = nanoClock.getAsLong();
            markActiveTimeoutIfDueLocked(now);
            boolean timedOut = activeAttemptTimedOut;
            boolean current = openInProgress
                    && attempt.generation() == activeAttemptGeneration
                    && attempt.generation() == lifecycleGeneration
                    && configured
                    && started
                    && device == null;
            openInProgress = false;
            activeAttemptDeadlineNanos = Long.MAX_VALUE;

            if (failure == null && current && !timedOut) {
                device = candidate;
                accepted = true;
                nextReopenNanos = Long.MAX_VALUE;
                recoveryExhausted = false;
                if (attempt.automatic()) {
                    successfulReopens++;
                }
            } else if (current) {
                if (!timedOut) {
                    lastDeviceFailure = failure;
                    scheduleNextAttemptLocked(now);
                }
                // A timeout scheduled its retry at the deadline. The single
                // lane may now service it if that backoff has elapsed.
                requestAutomaticAcquisitionIfDueLocked(now);
            }
            lifecycleLock.notifyAll();
        }
        if (!accepted) {
            safeStopAndClose(candidate);
        }
    }

    private void markActiveTimeoutIfDueLocked(long now) {
        if (!configured
                || !started
                || !openInProgress
                || activeAttemptTimedOut
                || now - activeAttemptDeadlineNanos < 0L) {
            return;
        }
        activeAttemptTimedOut = true;
        providerOpenTimeouts++;
        lastDeviceFailure = new AudioDeviceOpenTimeoutException(
                recoveryPolicy.providerOpenTimeoutMillis());
        if (activeAttemptGeneration == lifecycleGeneration
                && configured
                && started
                && device == null) {
            scheduleNextAttemptLocked(now);
        }
    }

    private void handleDeviceLoss(
            Pcm16Device failedDevice, Exception deviceFailure) {
        boolean owned;
        synchronized (lifecycleLock) {
            owned = device == failedDevice;
            if (owned) {
                device = null;
                if (configured && started) {
                    lastDeviceFailure = deviceFailure;
                    scheduleNextAttemptLocked(nanoClock.getAsLong());
                }
            }
        }
        if (owned) {
            safeStopAndClose(failedDevice);
        }
    }

    private void scheduleNextAttemptLocked(long now) {
        if (automaticReopenAttempts
                >= recoveryPolicy.maxAutomaticReopenAttempts()) {
            recoveryExhausted = true;
            nextReopenNanos = Long.MAX_VALUE;
            return;
        }
        recoveryExhausted = false;
        int attemptNumber = automaticReopenAttempts + 1;
        nextReopenNanos = saturatingAdd(
                now, recoveryPolicy.delayNanos(attemptNumber));
    }

    private void requireConfiguredLocked() {
        if (!configured) {
            throw new IllegalStateException("audio sink is not open");
        }
    }

    boolean acquisitionWorkerAlive() {
        synchronized (lifecycleLock) {
            return acquisitionWorker != null
                    && acquisitionWorker.isAlive();
        }
    }

    private static long saturatingAdd(long value, long increment) {
        if (increment < 0L) {
            throw new IllegalArgumentException(
                    "increment must not be negative");
        }
        long result = value + increment;
        if (((value ^ result) & (increment ^ result)) < 0L) {
            return Long.MAX_VALUE;
        }
        return result;
    }

    private static void safeStopAndClose(Pcm16Device current) {
        if (current == null) {
            return;
        }
        try {
            current.stop();
        } catch (RuntimeException ignored) {
            // Recovery and shutdown remain best effort.
        }
        try {
            current.close();
        } catch (RuntimeException ignored) {
            // The exact device is detached even if its provider misbehaves.
        }
    }

    private static void encodePcm16(
            float[] source,
            int sampleOffset,
            int sampleCount,
            byte[] target) {
        for (int index = 0; index < sampleCount; index++) {
            float value = source[sampleOffset + index];
            if (!Float.isFinite(value)) {
                value = 0.0f;
            }
            value = Math.max(-1.0f, Math.min(1.0f, value));
            short pcm = (short) Math.round(value * 32_767.0f);
            target[index * 2] = (byte) (pcm & 0xff);
            target[(index * 2) + 1] =
                    (byte) ((pcm >>> 8) & 0xff);
        }
    }

    private static Pcm16Device openDefaultDevice(
            AudioFormat format, int bufferBytes) throws Exception {
        DataLine.Info info =
                new DataLine.Info(SourceDataLine.class, format);
        SourceDataLine line =
                (SourceDataLine) AudioSystem.getLine(info);
        boolean opened = false;
        try {
            line.open(format, bufferBytes);
            opened = true;
            return new SourceLineDevice(line);
        } finally {
            if (!opened) {
                line.close();
            }
        }
    }

    private record Attempt(
            long generation,
            boolean automatic,
            AudioFormat format,
            int bufferBytes) {}

    private record SourceLineDevice(SourceDataLine line)
            implements Pcm16Device {
        @Override
        public void start() {
            line.start();
        }

        @Override
        public int write(byte[] pcmBytes, int offset, int length) {
            return line.write(pcmBytes, offset, length);
        }

        @Override
        public void stop() {
            line.stop();
        }

        @Override
        public void close() {
            line.close();
        }
    }
}
