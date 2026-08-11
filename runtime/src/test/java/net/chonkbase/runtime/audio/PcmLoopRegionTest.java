package net.chonkbase.runtime.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PcmLoopRegionTest {
    @Test
    void halfOpenRegionReportsFramesAndValidatesDecodedBounds() {
        PcmClip clip = PcmClip.fromFloats(
                "bounded", 1, new float[] {0, 0, 0, 0, 0, 0, 0, 0});
        PcmLoopRegion region = new PcmLoopRegion(2, 8, 2);

        assertEquals(6, region.frameCount());
        assertTrue(region.crossfades());
        assertSame(region, region.requireWithin(clip));
        assertSame(region, region.requireWithin(8));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PcmLoopRegion(2, 9, 2).requireWithin(clip));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PcmLoopRegion(2, 9, 2).requireWithin(8));
    }

    @Test
    void fullClipRegionPreservesLegacyHardWrapContract() {
        PcmClip clip =
                PcmClip.fromFloats("full", 1, new float[] {0.1f, 0.2f});
        PcmLoopRegion region = PcmLoopRegion.fullClip(clip);

        assertEquals(0, region.startFrame());
        assertEquals(2, region.endFrameExclusive());
        assertEquals(0, region.crossfadeFrames());
        assertFalse(region.crossfades());
    }

    @Test
    void rejectsNegativeEmptySingleFrameAndDegenerateOverlapMetadata() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PcmLoopRegion(-1, 8, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PcmLoopRegion(4, 4, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PcmLoopRegion(0, 8, -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PcmLoopRegion(0, 8, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PcmLoopRegion(0, 8, 4));
    }
}
