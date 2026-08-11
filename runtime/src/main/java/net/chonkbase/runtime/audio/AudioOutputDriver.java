package net.chonkbase.runtime.audio;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Bounded lifecycle owner for one mixer and one output sink.
 *
 * <p>The worker is daemonized, renders fixed blocks, handles short writes, and
 * paces fast fake/non-blocking sinks to real time. Closing the sink before the
 * bounded join is intentional: a real {@code SourceDataLine.close()} unblocks a
 * thread waiting inside {@code write()}.
 */
public final class AudioOutputDriver implements AutoCloseable {
    public static final int DEFAULT_BLOCK_FRAMES = 512;
    public static final int DEFAULT_DEVICE_BUFFER_FRAMES = 2_048;
    public static final int MAX_CONSECUTIVE_ZERO_WRITES = 64;
    public static final long CLOSE_JOIN_MILLIS = 1_000L;

    private final AudioMixer mixer;
    private final AudioSink sink;
    private final int blockFrames;
    private final int deviceBufferFrames;
    private final String threadName;
    private final float[] block;
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean sinkOwned = new AtomicBoolean();

    private volatile boolean running;
    private volatile boolean closed;
    private volatile boolean suspended;
    private volatile Throwable failure;
    private volatile Throwable resumeFailure;
    private volatile Thread worker;

    public AudioOutputDriver(AudioMixer mixer, AudioSink sink) {
        this(mixer, sink, DEFAULT_BLOCK_FRAMES, DEFAULT_DEVICE_BUFFER_FRAMES, "seven-days-audio-output");
    }

    public AudioOutputDriver(
            AudioMixer mixer, AudioSink sink, int blockFrames, int deviceBufferFrames, String threadName) {
        this.mixer = Objects.requireNonNull(mixer, "mixer");
        this.sink = Objects.requireNonNull(sink, "sink");
        if (blockFrames <= 0 || deviceBufferFrames < blockFrames) {
            throw new IllegalArgumentException("device buffer must contain at least one positive render block");
        }
        if (threadName == null || threadName.isBlank()) {
            throw new IllegalArgumentException("threadName must not be blank");
        }
        this.blockFrames = blockFrames;
        this.deviceBufferFrames = deviceBufferFrames;
        this.threadName = threadName;
        this.block = new float[blockFrames * PcmFormat.GAME_STEREO.channels()];
    }

    public void start() throws Exception {
        synchronized (lifecycleLock) {
            if (closed) {
                throw new IllegalStateException("audio output driver is closed");
            }
            if (running) {
                return;
            }
            failure = null;
            resumeFailure = null;
            suspended = false;
            try {
                sinkOwned.set(true);
                sink.open(PcmFormat.GAME_STEREO, deviceBufferFrames);
                sink.start();
            } catch (Throwable startFailure) {
                failure = startFailure;
                safeStopAndClose();
                rethrow(startFailure);
                return;
            }
            running = true;
            Thread next = new Thread(this::run, threadName);
            next.setDaemon(true);
            next.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 1));
            worker = next;
            next.start();
        }
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isClosed() {
        return closed;
    }

    public Throwable failure() {
        return failure;
    }

    public Throwable resumeFailure() {
        return resumeFailure;
    }

    public boolean isSuspended() {
        return suspended;
    }

    public boolean isOutputAvailable() {
        return outputStatus().outputAvailable();
    }

    /** Coherent presentation-only diagnostics for the owned output route. */
    public AudioOutputStatus outputStatus() {
        synchronized (lifecycleLock) {
            AudioOutputStatus sinkStatus =
                    sink instanceof AudioOutputHealth health
                            ? health.outputStatus()
                            : new AudioOutputStatus(
                                    running && !suspended
                                            ? AudioOutputStatus.RouteState.AVAILABLE
                                            : AudioOutputStatus.RouteState.STOPPED,
                                    0,
                                    0,
                                    0,
                                    null);
            if (closed) {
                return sinkStatus.withState(
                        AudioOutputStatus.RouteState.CLOSED);
            }
            if (failure != null) {
                return new AudioOutputStatus(
                        AudioOutputStatus.RouteState.DISABLED,
                        sinkStatus.automaticReopenAttempts(),
                        sinkStatus.successfulReopens(),
                        sinkStatus.providerOpenTimeouts(),
                        failure);
            }
            if (suspended) {
                return sinkStatus.withState(
                        AudioOutputStatus.RouteState.STOPPED);
            }
            return sinkStatus;
        }
    }

    public boolean recoveryExhausted() {
        return outputStatus().recoveryExhausted();
    }

    public int reopenAttemptCount() {
        return outputStatus().automaticReopenAttempts();
    }

    public int successfulReopenCount() {
        return outputStatus().successfulReopens();
    }

    public int providerOpenTimeoutCount() {
        return outputStatus().providerOpenTimeouts();
    }

    public Throwable lastDeviceFailure() {
        return outputStatus().lastDeviceFailure();
    }

    public boolean workerAlive() {
        Thread current = worker;
        return current != null && current.isAlive();
    }

    private void run() {
        long blockNanos = Math.max(
                1L, Math.round(blockFrames * 1_000_000_000.0 / PcmFormat.GAME_SAMPLE_RATE));
        long nextDeadline = System.nanoTime();
        try {
            while (running) {
                mixer.render(block, blockFrames);
                if (!suspended) {
                    try {
                        writeCompleteBlock();
                    } catch (Throwable outputFailure) {
                        if (running && !suspended) {
                            throw outputFailure;
                        }
                    }
                }
                nextDeadline += blockNanos;
                long now = System.nanoTime();
                if (nextDeadline > now) {
                    LockSupport.parkNanos(nextDeadline - now);
                } else if (now - nextDeadline > blockNanos * 4L) {
                    nextDeadline = now;
                }
                if (Thread.interrupted() && !running) {
                    break;
                }
            }
        } catch (Throwable outputFailure) {
            if (running) {
                failure = outputFailure;
            }
        } finally {
            running = false;
            safeStopAndClose();
        }
    }

    /**
     * Releases or stops the current device while the mixer timeline continues
     * to advance into silence.
     */
    public void suspend() {
        Thread current;
        synchronized (lifecycleLock) {
            if (closed || !running || suspended) {
                return;
            }
            suspended = true;
            current = worker;
            if (current != null) {
                current.interrupt();
            }
        }
        try {
            sink.stop();
        } catch (RuntimeException ignored) {
            // Suspension remains fail-soft; close still owns final cleanup.
        }
    }

    /**
     * Explicitly resumes a suspended sink.
     *
     * <p>Recovering sinks may return true while they temporarily render to
     * silence and perform their bounded reopen sequence.
     */
    public boolean resume() {
        synchronized (lifecycleLock) {
            if (closed || !running) {
                return false;
            }
            if (!suspended) {
                return true;
            }
        }

        try {
            sink.start();
        } catch (Throwable resumeOutputFailure) {
            resumeFailure = resumeOutputFailure;
            return false;
        }

        synchronized (lifecycleLock) {
            if (closed || !running) {
                try {
                    sink.stop();
                } catch (RuntimeException ignored) {
                }
                return false;
            }
            suspended = false;
            resumeFailure = null;
            Thread current = worker;
            if (current != null) {
                current.interrupt();
            }
            return true;
        }
    }

    private void writeCompleteBlock() throws Exception {
        int offset = 0;
        int zeroWrites = 0;
        while (running && offset < blockFrames) {
            int written = sink.write(block, offset, blockFrames - offset);
            if (written < 0 || written > blockFrames - offset) {
                throw new IOException("audio sink returned invalid frame count: " + written);
            }
            if (written == 0) {
                if (++zeroWrites >= MAX_CONSECUTIVE_ZERO_WRITES) {
                    throw new IOException("audio sink made no write progress");
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
                continue;
            }
            zeroWrites = 0;
            offset += written;
        }
    }

    @Override
    public void close() {
        Thread toJoin;
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            running = false;
            suspended = false;
            toJoin = worker;
            if (toJoin != null) {
                toJoin.interrupt();
            }
        }

        // Close outside the lifecycle monitor so a device callback cannot
        // deadlock against start/close state.
        safeStopAndClose();
        if (toJoin != null && toJoin != Thread.currentThread()) {
            try {
                toJoin.join(CLOSE_JOIN_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void safeStopAndClose() {
        if (!sinkOwned.compareAndSet(true, false)) {
            return;
        }
        try {
            sink.stop();
        } catch (RuntimeException ignored) {
        }
        try {
            sink.close();
        } catch (RuntimeException ignored) {
        }
    }

    private static void rethrow(Throwable error) throws Exception {
        if (error instanceof Error fatal) {
            throw fatal;
        }
        if (error instanceof Exception checked) {
            throw checked;
        }
        throw new RuntimeException(error);
    }
}
