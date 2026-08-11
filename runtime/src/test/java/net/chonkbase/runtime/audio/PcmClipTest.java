package net.chonkbase.runtime.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PcmClipTest {
    @Test
    void ownsItsPcmAndReturnsDefensiveCopies() {
        short[] source = {100, -200, 300, -400};
        PcmClip clip = new PcmClip("owned", 2, source);

        source[0] = 999;
        short[] copy = clip.copySamples();
        copy[1] = 999;

        assertArrayEquals(new short[] {100, -200, 300, -400}, clip.copySamples());
        assertEquals(2, clip.frameCount());
        assertEquals(8L, clip.residentBytes());
        assertEquals(PcmFormat.GAME_SAMPLE_RATE, clip.sampleRate());
    }

    @Test
    void floatFactoryClampsAndSilencesNonFiniteValues() {
        PcmClip clip =
                PcmClip.fromFloats("float", 1, new float[] {-2.0f, -0.5f, Float.NaN, Float.POSITIVE_INFINITY, 2.0f});

        short[] samples = clip.copySamples();
        assertEquals(-32_767, samples[0]);
        assertEquals(-16_383, samples[1]);
        assertEquals(0, samples[2]);
        assertEquals(0, samples[3]);
        assertEquals(32_767, samples[4]);
    }

    @Test
    void rejectsUnsupportedOrIncompleteLayouts() {
        assertThrows(IllegalArgumentException.class, () -> new PcmClip("bad", 3, new short[] {1, 2, 3}));
        assertThrows(IllegalArgumentException.class, () -> new PcmClip("bad", 2, new short[] {1, 2, 3}));
        assertThrows(IllegalArgumentException.class, () -> new PcmClip("bad", 1, new short[0]));
    }
}
