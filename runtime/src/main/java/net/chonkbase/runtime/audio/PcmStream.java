package net.chonkbase.runtime.audio;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Bounded, background-decoded PCM stream for long music and ambience.
 *
 * <p>One daemon producer owns the decoder and fills a fixed ring of stereo
 * float pages. The mixer is the single consumer. Its access path consists only
 * of volatile reads/writes, primitive arithmetic, and array reads: it never
 * opens a resource, calls a decoder, waits for a lock, or allocates a page.
 *
 * <p>A stream is a single-playback object. It must be primed before an
 * {@link AudioCommand.StreamPlay} is constructed, and it cannot be attached to
 * more than one voice. Underruns advance the presentation timeline into
 * silence; decoded audio is never replayed late.
 */
public final class PcmStream implements AutoCloseable {
    public static final int DEFAULT_PAGE_FRAMES = 2_048;
    public static final int DEFAULT_PAGE_COUNT = 8;
    public static final int DEFAULT_PRIME_PAGES = 4;
    public static final int MIN_PAGE_FRAMES = 64;
    public static final int MAX_PAGE_FRAMES = 16_384;
    public static final int MIN_PAGE_COUNT = 2;
    public static final int MAX_PAGE_COUNT = 64;
    public static final long MAX_BUFFER_BYTES = 16L * 1024L * 1024L;
    public static final int MAX_LOOP_CROSSFADE_FRAMES =
            PcmFormat.GAME_SAMPLE_RATE * 2;
    public static final long CLOSE_JOIN_MILLIS = 1_000L;

    static final long FRAME_UNAVAILABLE = Long.MIN_VALUE;

    private static final long PRODUCER_PARK_NANOS = 250_000L;
    private static final int STATE_PREPARING = 0;
    private static final int STATE_READY = 1;
    private static final int STATE_CLAIMED = 2;
    private static final int STATE_PLAYING = 3;
    private static final int STATE_RELEASED = 4;

    @FunctionalInterface
    public interface DecoderFactory {
        PcmStreamDecoder open() throws IOException;
    }

    /** Fixed memory and startup-latency policy for one stream. */
    public record BufferConfig(int pageFrames, int pageCount, int primePages) {
        public BufferConfig {
            if (pageFrames < MIN_PAGE_FRAMES
                    || pageFrames > MAX_PAGE_FRAMES) {
                throw new IllegalArgumentException(
                        "pageFrames must be within "
                                + MIN_PAGE_FRAMES
                                + ".."
                                + MAX_PAGE_FRAMES);
            }
            if (pageCount < MIN_PAGE_COUNT || pageCount > MAX_PAGE_COUNT) {
                throw new IllegalArgumentException(
                        "pageCount must be within "
                                + MIN_PAGE_COUNT
                                + ".."
                                + MAX_PAGE_COUNT);
            }
            if (primePages <= 0 || primePages > pageCount) {
                throw new IllegalArgumentException(
                        "primePages must be within 1..pageCount");
            }
            long pageBytes =
                    Math.multiplyExact(
                            Math.multiplyExact((long) pageFrames, 2L),
                            Float.BYTES);
            long bufferBytes = Math.multiplyExact(pageBytes, pageCount);
            if (bufferBytes > MAX_BUFFER_BYTES) {
                throw new IllegalArgumentException(
                        "stream buffer exceeds "
                                + MAX_BUFFER_BYTES
                                + " bytes");
            }
        }

        public static BufferConfig defaults() {
            return new BufferConfig(
                    DEFAULT_PAGE_FRAMES,
                    DEFAULT_PAGE_COUNT,
                    DEFAULT_PRIME_PAGES);
        }

        public long pageRingBytes() {
            return (long) pageFrames * pageCount * 2L * Float.BYTES;
        }
    }

    private final String debugName;
    private final DecoderFactory decoderFactory;
    private final BufferConfig bufferConfig;
    private final PcmLoopRegion loopRegion;
    private final AtomicInteger lifecycle =
            new AtomicInteger(STATE_PREPARING);
    private final CountDownLatch preparationFinished = new CountDownLatch(1);
    private final CountDownLatch workerFinished = new CountDownLatch(1);
    private final Thread worker;

    private volatile Page[] pages;
    private volatile int channels;
    private volatile int sourceFrameCount;
    private volatile long playbackFrameCount;
    private volatile long publishedFrameExclusive;
    private volatile long consumedFrameExclusive;
    private volatile long underrunFrames;
    private volatile boolean decodedToEnd;
    private volatile boolean stopRequested;
    private volatile boolean closeTimedOut;
    private volatile Throwable decoderFailure;

    private PcmStream(
            String debugName,
            DecoderFactory decoderFactory,
            BufferConfig bufferConfig,
            PcmLoopRegion loopRegion) {
        if (debugName == null || debugName.isBlank()) {
            throw new IllegalArgumentException("debugName must not be blank");
        }
        this.debugName = debugName;
        this.decoderFactory =
                Objects.requireNonNull(decoderFactory, "decoderFactory");
        this.bufferConfig =
                Objects.requireNonNull(bufferConfig, "bufferConfig");
        this.loopRegion = loopRegion;
        this.worker =
                new Thread(
                        this::produce,
                        "seven-days-audio-decode-"
                                + sanitizeThreadName(debugName));
        worker.setDaemon(true);
        worker.setPriority(Math.max(
                Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        worker.start();
    }

    public static PcmStream prepare(
            String debugName, DecoderFactory decoderFactory) {
        return prepare(
                debugName,
                decoderFactory,
                BufferConfig.defaults(),
                null);
    }

    public static PcmStream prepare(
            String debugName,
            DecoderFactory decoderFactory,
            BufferConfig bufferConfig,
            PcmLoopRegion loopRegion) {
        return new PcmStream(
                debugName, decoderFactory, bufferConfig, loopRegion);
    }

    public String debugName() {
        return debugName;
    }

    public BufferConfig bufferConfig() {
        return bufferConfig;
    }

    public PcmLoopRegion loopRegion() {
        return loopRegion;
    }

    public boolean isReady() {
        int state = lifecycle.get();
        return state == STATE_READY
                || state == STATE_CLAIMED
                || state == STATE_PLAYING;
    }

    /**
     * Waits off the render thread for the configured startup buffer or a
     * terminal preparation failure.
     */
    public boolean awaitReady(long timeout, TimeUnit unit)
            throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        if (timeout < 0L) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        if (!preparationFinished.await(timeout, unit)) {
            return false;
        }
        return isReady();
    }

    /**
     * Waits until at least the requested number of sequential frames are
     * available ahead of the consumer. Intended for loading screens and tests,
     * never the mixer thread.
     */
    public boolean awaitBufferedFrames(
            int minimumFrames, long timeout, TimeUnit unit)
            throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        if (minimumFrames < 0
                || minimumFrames
                        > bufferConfig.pageFrames()
                                * bufferConfig.pageCount()) {
            throw new IllegalArgumentException(
                    "minimumFrames exceeds fixed page-ring capacity");
        }
        if (timeout < 0L) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        long timeoutNanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + timeoutNanos;
        do {
            if (decoderFailure != null || lifecycle.get() == STATE_RELEASED) {
                return false;
            }
            if (bufferedFrames() >= minimumFrames
                    || (decodedToEnd && bufferedFrames() > 0)) {
                return true;
            }
            if (timeoutNanos == 0L) {
                return false;
            }
            LockSupport.parkNanos(Math.min(
                    PRODUCER_PARK_NANOS,
                    Math.max(1L, deadline - System.nanoTime())));
        } while (System.nanoTime() < deadline);
        return bufferedFrames() >= minimumFrames;
    }

    public int channels() {
        return channels;
    }

    public int sourceFrameCount() {
        return sourceFrameCount;
    }

    /**
     * Logical playback length. Looping streams report {@link Long#MAX_VALUE}.
     */
    public long playbackFrameCount() {
        return playbackFrameCount;
    }

    public long bufferCapacityBytes() {
        return bufferConfig.pageRingBytes();
    }

    /**
     * Current bounded audio working set, excluding small object headers and the
     * decoder implementation's own fixed scratch buffer.
     */
    public long audioWorkingSetBytes() {
        long cursorBytes =
                (long) bufferConfig.pageFrames()
                        * Math.max(1, channels)
                        * Short.BYTES;
        long loopHeadBytes =
                loopRegion == null
                        ? 0L
                        : (long) loopRegion.crossfadeFrames()
                                * 2L
                                * Float.BYTES;
        return bufferConfig.pageRingBytes()
                + cursorBytes
                + loopHeadBytes;
    }

    public long bufferedFrames() {
        return Math.max(
                0L, publishedFrameExclusive - consumedFrameExclusive);
    }

    public long underrunFrameCount() {
        return underrunFrames;
    }

    public Throwable preparationFailure() {
        return decoderFailure;
    }

    /** Any decoder/open/read/seek failure, including one after priming. */
    public Throwable decoderFailure() {
        return decoderFailure;
    }

    public boolean decodedToEnd() {
        return decodedToEnd;
    }

    public boolean workerAlive() {
        return worker.isAlive();
    }

    public boolean closeTimedOut() {
        return closeTimedOut;
    }

    boolean claimForPlayback() {
        return lifecycle.compareAndSet(STATE_READY, STATE_CLAIMED);
    }

    boolean beginPlayback() {
        return lifecycle.compareAndSet(STATE_CLAIMED, STATE_PLAYING);
    }

    void releaseAsync() {
        lifecycle.set(STATE_RELEASED);
        stopRequested = true;
    }

    /**
     * Returns one stereo frame packed as raw float bits. Missing pages produce
     * a sentinel; callers must advance the consumer even on an underrun.
     */
    long frameAt(long playbackFrame) {
        Page[] currentPages = pages;
        if (playbackFrame < 0L || currentPages == null) {
            underrunFrames++;
            return FRAME_UNAVAILABLE;
        }
        long pageSequence = playbackFrame / bufferConfig.pageFrames();
        Page page =
                currentPages[
                        (int) (pageSequence % bufferConfig.pageCount())];
        if (page.sequence != pageSequence) {
            underrunFrames++;
            return FRAME_UNAVAILABLE;
        }
        int offset =
                (int) (playbackFrame
                        - pageSequence * bufferConfig.pageFrames());
        if (offset >= page.validFrames) {
            underrunFrames++;
            return FRAME_UNAVAILABLE;
        }
        int sample = offset * 2;
        return ((long) Float.floatToRawIntBits(page.samples[sample]) << 32)
                | (Float.floatToRawIntBits(page.samples[sample + 1])
                        & 0xffff_ffffL);
    }

    void consumeThrough(long frameExclusive) {
        if (frameExclusive > consumedFrameExclusive) {
            consumedFrameExclusive = frameExclusive;
        }
    }

    boolean terminallyUnavailable(long playbackFrame) {
        return decoderFailure != null
                && workerFinished.getCount() == 0L
                && playbackFrame >= publishedFrameExclusive;
    }

    private void produce() {
        try (PcmStreamDecoder decoder =
                Objects.requireNonNull(
                        decoderFactory.open(),
                        "decoderFactory returned null")) {
            prepareDecoder(decoder);
            fillPages(decoder);
        } catch (Throwable failure) {
            if (!stopRequested) {
                decoderFailure = failure;
            }
            lifecycle.set(STATE_RELEASED);
            stopRequested = true;
            preparationFinished.countDown();
        } finally {
            workerFinished.countDown();
        }
    }

    private void prepareDecoder(PcmStreamDecoder decoder) throws IOException {
        int decodedChannels = decoder.channels();
        int decodedFrames = decoder.frameCount();
        if ((decodedChannels != 1 && decodedChannels != 2)
                || decodedFrames <= 0) {
            throw new IOException(
                    "stream decoder returned invalid PCM metadata");
        }
        if (loopRegion != null) {
            loopRegion.requireWithin(decodedFrames);
            if (loopRegion.crossfadeFrames()
                    > MAX_LOOP_CROSSFADE_FRAMES) {
                throw new IOException(
                        "stream loop crossfade exceeds "
                                + MAX_LOOP_CROSSFADE_FRAMES
                                + " frames");
            }
        }

        Page[] allocatedPages = new Page[bufferConfig.pageCount()];
        for (int i = 0; i < allocatedPages.length; i++) {
            allocatedPages[i] =
                    new Page(bufferConfig.pageFrames());
        }
        channels = decodedChannels;
        sourceFrameCount = decodedFrames;
        playbackFrameCount =
                loopRegion == null ? decodedFrames : Long.MAX_VALUE;
        pages = allocatedPages;
    }

    private void fillPages(PcmStreamDecoder decoder) throws IOException {
        SourceCursor cursor =
                new SourceCursor(
                        decoder,
                        channels,
                        bufferConfig.pageFrames());
        float[] loopHead = prepareLoopHead(cursor);
        cursor.seek(0);

        long outputFrame = 0L;
        int sourceFrame = 0;
        long pageSequence = 0L;
        int publishedPages = 0;
        while (!stopRequested) {
            Page page =
                    pages[
                            (int) (pageSequence
                                    % bufferConfig.pageCount())];
            if (!awaitReusable(page)) {
                return;
            }
            page.sequence = Long.MIN_VALUE;

            int validFrames = 0;
            while (validFrames < bufferConfig.pageFrames()
                    && !stopRequested) {
                if (loopRegion == null
                        && sourceFrame >= sourceFrameCount) {
                    break;
                }
                if (loopRegion != null
                        && sourceFrame
                                >= loopRegion.endFrameExclusive()) {
                    sourceFrame =
                            loopRegion.startFrame()
                                    + loopRegion.crossfadeFrames();
                    cursor.seek(sourceFrame);
                }

                if (!cursor.next()) {
                    throw new IOException(
                            "stream decoder ended before declared frame count");
                }
                float left = cursor.left();
                float right = cursor.right();
                if (loopRegion != null
                        && loopRegion.crossfades()) {
                    int crossfadeStart =
                            loopRegion.endFrameExclusive()
                                    - loopRegion.crossfadeFrames();
                    if (sourceFrame >= crossfadeStart) {
                        int crossfadeIndex =
                                sourceFrame - crossfadeStart;
                        float mix =
                                (float) crossfadeIndex
                                        / (float) (loopRegion.crossfadeFrames() - 1);
                        int headSample = crossfadeIndex * 2;
                        left +=
                                (loopHead[headSample] - left)
                                        * mix;
                        right +=
                                (loopHead[headSample + 1] - right)
                                        * mix;
                    }
                }

                int destination = validFrames * 2;
                page.samples[destination] = left;
                page.samples[destination + 1] = right;
                validFrames++;
                outputFrame++;
                sourceFrame++;
            }

            if (validFrames == 0) {
                decodedToEnd = true;
                markReady();
                return;
            }
            page.validFrames = validFrames;
            page.sequence = pageSequence;
            publishedFrameExclusive = outputFrame;
            pageSequence++;
            publishedPages++;
            if (publishedPages >= bufferConfig.primePages()
                    || validFrames < bufferConfig.pageFrames()) {
                markReady();
            }
            if (loopRegion == null
                    && sourceFrame >= sourceFrameCount) {
                decodedToEnd = true;
                markReady();
                return;
            }
        }
    }

    private float[] prepareLoopHead(SourceCursor cursor)
            throws IOException {
        if (loopRegion == null || !loopRegion.crossfades()) {
            return new float[0];
        }
        float[] head = new float[loopRegion.crossfadeFrames() * 2];
        cursor.seek(loopRegion.startFrame());
        for (int frame = 0;
                frame < loopRegion.crossfadeFrames();
                frame++) {
            if (!cursor.next()) {
                throw new IOException(
                        "stream decoder ended while reading loop head");
            }
            head[frame * 2] = cursor.left();
            head[frame * 2 + 1] = cursor.right();
        }
        return head;
    }

    private boolean awaitReusable(Page page) {
        while (!stopRequested && page.sequence != Long.MIN_VALUE) {
            long pageEnd =
                    page.sequence * bufferConfig.pageFrames()
                            + page.validFrames;
            if (consumedFrameExclusive >= pageEnd) {
                return true;
            }
            LockSupport.parkNanos(PRODUCER_PARK_NANOS);
        }
        return !stopRequested;
    }

    private void markReady() {
        if (lifecycle.compareAndSet(STATE_PREPARING, STATE_READY)) {
            preparationFinished.countDown();
        }
    }

    /**
     * Requests cancellation and performs a bounded worker join. Lifecycle code
     * must call this off the mixer/render thread; voice removal uses the
     * non-blocking internal release path instead.
     */
    @Override
    public void close() {
        releaseAsync();
        worker.interrupt();
        try {
            if (!workerFinished.await(
                    CLOSE_JOIN_MILLIS, TimeUnit.MILLISECONDS)) {
                closeTimedOut = true;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            closeTimedOut = worker.isAlive();
        }
        preparationFinished.countDown();
    }

    private static String sanitizeThreadName(String value) {
        String sanitized =
                value.replaceAll("[^A-Za-z0-9._-]+", "-");
        return sanitized.length() <= 48
                ? sanitized
                : sanitized.substring(0, 48);
    }

    private static final class Page {
        final float[] samples;
        volatile int validFrames;
        volatile long sequence = Long.MIN_VALUE;

        Page(int pageFrames) {
            samples = new float[pageFrames * 2];
        }
    }

    private static final class SourceCursor {
        private static final float I16_TO_FLOAT = 1.0f / 32_768.0f;

        private final PcmStreamDecoder decoder;
        private final int channels;
        private final short[] samples;
        private int availableFrames;
        private int frame;
        private float left;
        private float right;

        SourceCursor(
                PcmStreamDecoder decoder,
                int channels,
                int bufferFrames) {
            this.decoder = decoder;
            this.channels = channels;
            this.samples =
                    new short[Math.multiplyExact(
                            bufferFrames, channels)];
        }

        void seek(int sourceFrame) throws IOException {
            decoder.seekFrame(sourceFrame);
            availableFrames = 0;
            frame = 0;
        }

        boolean next() throws IOException {
            if (frame >= availableFrames) {
                availableFrames =
                        decoder.readFrames(
                                samples, 0, samples.length / channels);
                frame = 0;
                if (availableFrames <= 0) {
                    return false;
                }
            }
            int sample = frame * channels;
            left = samples[sample] * I16_TO_FLOAT;
            right =
                    samples[
                                    sample
                                            + (channels == 1
                                                    ? 0
                                                    : 1)]
                            * I16_TO_FLOAT;
            frame++;
            return true;
        }

        float left() {
            return left;
        }

        float right() {
            return right;
        }
    }
}
