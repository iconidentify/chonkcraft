package net.chonkbase.runtime.audio;

import java.util.Objects;

/**
 * Immutable, sample-frame-accurate loop metadata for a decoded PCM clip.
 *
 * <p>The region is half-open: {@code startFrame} is included and
 * {@code endFrameExclusive} is not. Playback may contain a one-time intro
 * before {@code startFrame}; looping wraps at {@code endFrameExclusive}.
 *
 * <p>When a crossfade is present, the final {@code crossfadeFrames} frames of
 * the region are blended with the same number of frames at its beginning.
 * Those head frames are not replayed after the wrap. Requiring at least one
 * non-overlapped frame on each side prevents degenerate regions whose entire
 * steady state is an overlap.
 */
public record PcmLoopRegion(
        int startFrame, int endFrameExclusive, int crossfadeFrames) {
    public PcmLoopRegion {
        if (startFrame < 0) {
            throw new IllegalArgumentException(
                    "loop startFrame must not be negative");
        }
        if (endFrameExclusive <= startFrame) {
            throw new IllegalArgumentException(
                    "loop endFrameExclusive must be after startFrame");
        }
        if (crossfadeFrames < 0) {
            throw new IllegalArgumentException(
                    "loop crossfadeFrames must not be negative");
        }
        if (crossfadeFrames == 1) {
            throw new IllegalArgumentException(
                    "loop crossfadeFrames must be zero or at least two");
        }
        long doubledCrossfade = (long) crossfadeFrames * 2L;
        if (doubledCrossfade >= (long) endFrameExclusive - startFrame) {
            throw new IllegalArgumentException(
                    "loop crossfade must leave non-overlapped loop frames");
        }
    }

    public static PcmLoopRegion fullClip(PcmClip clip) {
        Objects.requireNonNull(clip, "clip");
        return new PcmLoopRegion(0, clip.frameCount(), 0);
    }

    public int frameCount() {
        return endFrameExclusive - startFrame;
    }

    public boolean crossfades() {
        return crossfadeFrames > 0;
    }

    /**
     * Validates the region against a decoded clip and returns this value for
     * fluent predecode-time resolution.
     */
    public PcmLoopRegion requireWithin(PcmClip clip) {
        Objects.requireNonNull(clip, "clip");
        if (endFrameExclusive > clip.frameCount()) {
            throw new IllegalArgumentException(
                    "loop endFrameExclusive "
                            + endFrameExclusive
                            + " exceeds clip frame count "
                            + clip.frameCount());
        }
        return this;
    }

    /**
     * Validates the region against source metadata without requiring a
     * resident {@link PcmClip}. This is used by background-streamed sources
     * after their decoder has opened off the render thread.
     */
    public PcmLoopRegion requireWithin(int sourceFrameCount) {
        if (sourceFrameCount <= 0) {
            throw new IllegalArgumentException(
                    "source frame count must be positive");
        }
        if (endFrameExclusive > sourceFrameCount) {
            throw new IllegalArgumentException(
                    "loop endFrameExclusive "
                            + endFrameExclusive
                            + " exceeds source frame count "
                            + sourceFrameCount);
        }
        return this;
    }
}
