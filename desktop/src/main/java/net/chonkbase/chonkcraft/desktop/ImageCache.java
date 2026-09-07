package net.chonkbase.chonkcraft.desktop;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;

/**
 * Generated images bounded by both count and raster bytes.
 *
 * <p>An entry limit alone lets a resize history or large creature frames keep
 * hundreds of megabytes. Eviction also flushes managed device copies, whose
 * memory the heap collector cannot use to decide when it should run.
 * Callers serialize access with painting or their own lock.
 */
final class ImageCache<K> {
    private final int entryLimit;
    private final long byteLimit;
    private final LinkedHashMap<K, BufferedImage> images = new LinkedHashMap<>(16, 0.75f, true);
    private long bytes;

    ImageCache(int entryLimit, long byteLimit) {
        if (entryLimit < 1 || byteLimit < 1) {
            throw new IllegalArgumentException("image cache limits must be positive");
        }
        this.entryLimit = entryLimit;
        this.byteLimit = byteLimit;
    }

    BufferedImage get(K key) {
        return images.get(key);
    }

    /** The most recent compatible source for extending a generated texture. */
    BufferedImage find(java.util.function.Predicate<K> compatible) {
        for (var entry : images.reversed().entrySet()) {
            if (compatible.test(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    void put(K key, BufferedImage image) {
        BufferedImage previous = images.remove(key);
        if (previous != null) {
            bytes -= weight(previous);
            if (previous != image) {
                previous.flush();
            }
        }
        long weight = weight(image);
        if (weight > byteLimit) {
            return;
        }
        while (!images.isEmpty()
                && (images.size() >= entryLimit || bytes + weight > byteLimit)) {
            BufferedImage evicted = images.pollFirstEntry().getValue();
            bytes -= weight(evicted);
            evicted.flush();
        }
        images.put(key, image);
        bytes += weight;
    }

    void clear() {
        images.values().forEach(BufferedImage::flush);
        images.clear();
        bytes = 0;
    }

    void removeIf(java.util.function.Predicate<K> changed) {
        var entries = images.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (changed.test(entry.getKey())) {
                bytes -= weight(entry.getValue());
                entry.getValue().flush();
                entries.remove();
            }
        }
    }

    private static long weight(BufferedImage image) {
        return 4L * image.getWidth() * image.getHeight();
    }
}
