package net.chonkbase.runtime;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Small fixed-rate simulation loop adapted from ChonkBlocker's production
 * logic loop. Rendering is intentionally not part of this class.
 */
public final class FixedStepLoop implements AutoCloseable {
    public static final int DEFAULT_HZ = 60;
    private static final int MAX_CATCH_UP_STEPS = 5;

    /**
     * How long one step takes. Not final: Warcraft II's speed control moves
     * the simulation rate while the game is running, so the loop has to be able
     * to change tempo without being torn down and rebuilt.
     */
    private volatile long periodNanos;
    private final List<Runnable> tasks = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean paused = new AtomicBoolean();
    private final Thread thread;

    public FixedStepLoop(String threadName) {
        this(threadName, DEFAULT_HZ);
    }

    public FixedStepLoop(String threadName, int hertz) {
        if (hertz <= 0) {
            throw new IllegalArgumentException("hertz must be positive");
        }
        periodNanos = 1_000_000_000L / hertz;
        thread = new Thread(this::run, threadName);
        thread.setDaemon(true);
    }

    public void register(Runnable task) {
        tasks.add(task);
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            thread.start();
        }
    }

    /** Changes the step rate of a running loop. */
    public void setHertz(int hertz) {
        if (hertz <= 0) {
            throw new IllegalArgumentException("hertz must be positive");
        }
        periodNanos = 1_000_000_000L / hertz;
        LockSupport.unpark(thread);
    }

    /** The current step rate. */
    public int hertz() {
        return (int) Math.max(1, 1_000_000_000L / periodNanos);
    }

    public void setPaused(boolean value) {
        paused.set(value);
        LockSupport.unpark(thread);
    }

    public boolean isPaused() {
        return paused.get();
    }

    private void run() {
        long next = System.nanoTime();
        while (running.get()) {
            long now = System.nanoTime();
            long remaining = next - now;
            if (remaining > 0) {
                LockSupport.parkNanos(remaining);
                continue;
            }

            long lateBy = Math.max(0L, now - next);
            int steps = 1 + (int) Math.min(MAX_CATCH_UP_STEPS - 1L, lateBy / periodNanos);
            for (int i = 0; i < steps && running.get(); i++) {
                if (!paused.get()) {
                    for (Runnable task : tasks) {
                        task.run();
                    }
                }
                next += periodNanos;
            }

            if (now - next > periodNanos * MAX_CATCH_UP_STEPS) {
                next = now + periodNanos;
            }
        }
    }

    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            LockSupport.unpark(thread);
            try {
                thread.join(1_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

