package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ImageCacheTest {
    private static final class Picture extends BufferedImage {
        private boolean flushed;

        Picture(int size) {
            super(size, size, BufferedImage.TYPE_INT_RGB);
            setRGB(0, 0, 0x123456);
        }

        @Override
        public void flush() {
            flushed = true;
            super.flush();
        }
    }

    @Test
    @DisplayName("large pictures evict old device copies before the entry limit")
    void bytesBoundTheCache() {
        ImageCache<String> cache = new ImageCache<>(48, 800);
        Picture old = new Picture(10);
        Picture recent = new Picture(10);
        Picture next = new Picture(10);
        cache.put("old", old);
        cache.put("recent", recent);
        cache.get("old");
        cache.put("next", next);
        assertNull(cache.get("recent"), "the least recently used picture exceeds the byte budget");
        assertTrue(recent.flushed, "native storage must be released at eviction");
        assertSame(old, cache.get("old"), "drawing a picture keeps it recent");
        assertSame(next, cache.get("next"), "the new picture must remain available");
        assertEquals(0xFF123456, recent.getRGB(0, 0), "eviction preserves drawable source pixels");
    }

    @Test
    @DisplayName("replacing and leaving a screen release its generated pictures")
    void replacementAndClearReleaseSurfaces() {
        ImageCache<String> cache = new ImageCache<>(2, 8000);
        Picture old = new Picture(10);
        Picture replacement = new Picture(10);
        Picture second = new Picture(10);
        cache.put("frame", old);
        cache.put("frame", replacement);
        assertTrue(old.flushed, "replaced frames must not wait for a heap collection");
        cache.put("other", second);
        cache.clear();
        assertNull(cache.get("frame"), "leaving the screen must drop the cache");
        assertTrue(replacement.flushed && second.flushed, "clear releases every device copy");
        cache.put("again", old);
        assertSame(old, cache.get("again"), "the same cache can be used after a return");
    }

    @Test
    @DisplayName("small pictures obey the entry limit and oversized ones are not retained")
    void entryLimitAndOversizedPictures() {
        ImageCache<String> cache = new ImageCache<>(1, 400);
        Picture first = new Picture(5);
        Picture second = new Picture(5);
        cache.put("first", first);
        cache.put("second", second);
        assertNull(cache.get("first"), "small images still obey the count bound");
        assertTrue(first.flushed, "count eviction must release native storage too");
        Picture oversized = new Picture(11);
        cache.put("large", oversized);
        assertNull(cache.get("large"), "one picture cannot defeat the byte budget");
        assertSame(second, cache.get("second"), "uncacheable pictures do not evict useful frames");
        assertEquals(0xFF123456, oversized.getRGB(0, 0), "oversized pictures can still be drawn");
    }
}
