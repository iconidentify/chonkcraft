package net.chonkbase.chonkcraft.launcher;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic stone at the size the launcher actually draws it. */
final class MarbleTexture {

    enum Tint {
        BLUE_STONE,
        BLACK_STONE,
        PARCHMENT
    }

    private static final int CACHE_LIMIT = 24;
    private static final Map<String, BufferedImage> CACHE =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
                    return size() > CACHE_LIMIT;
                }
            };

    private MarbleTexture() {
    }

    static synchronized BufferedImage of(int width, int height, Tint tint) {
        if (width <= 0 || height <= 0) {
            return null;
        }
        String key = width + "x" + height + ":" + tint;
        BufferedImage found = CACHE.get(key);
        if (found != null) {
            return found;
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double u = x / 32.0;
                double v = y / 32.0;
                double bendX = u + 0.42 * fractal(u * 0.55 + 17, v * 0.55 + 3, 2);
                double bendY = v + 0.42 * fractal(u * 0.55 + 5, v * 0.55 + 29, 2);
                double body = fractal(bendX, bendY, 5);
                double ridge = 1 - Math.abs(2 * fractal(
                        bendX * 1.17, bendY * 1.17, 3) - 1);
                double vein = Math.pow(Math.max(0, ridge), 18);
                double grit = fractal(u * 17, v * 17, 2) - 0.5;
                double shade = clamp(0.48 + (body - 0.5) * 0.34
                        - vein * 0.14 + grit * 0.10);
                pixels[y * width + x] = colour(shade, tint);
            }
        }
        image.setRGB(0, 0, width, height, pixels, 0, width);
        CACHE.put(key, image);
        return image;
    }

    private static double fractal(double x, double y, int octaves) {
        double total = 0;
        double weight = 0;
        double amplitude = 0.5;
        double frequency = 1;
        for (int i = 0; i < octaves; i++) {
            total += noise(x * frequency, y * frequency, i) * amplitude;
            weight += amplitude;
            amplitude *= 0.5;
            frequency *= 2;
        }
        return total / weight;
    }

    private static double noise(double x, double y, int layer) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        double fx = smooth(x - x0);
        double fy = smooth(y - y0);
        double top = mix(hash(x0, y0, layer), hash(x0 + 1, y0, layer), fx);
        double bottom = mix(hash(x0, y0 + 1, layer), hash(x0 + 1, y0 + 1, layer), fx);
        return mix(top, bottom, fy);
    }

    private static double hash(int x, int y, int layer) {
        int value = x * 374_761_393 + y * 668_265_263 + layer * 1_442_695_041;
        value = (value ^ (value >>> 13)) * 1_274_126_177;
        value ^= value >>> 16;
        return (value & 0x7FFF_FFFF) / (double) 0x7FFF_FFFF;
    }

    private static double smooth(double value) {
        return value * value * (3 - 2 * value);
    }

    private static double mix(double a, double b, double amount) {
        return a + (b - a) * amount;
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static int colour(double shade, Tint tint) {
        return switch (tint) {
            case BLUE_STONE -> pack(
                    (int) (21 + shade * 43),
                    (int) (29 + shade * 49),
                    (int) (42 + shade * 65));
            case BLACK_STONE -> pack(
                    (int) (10 + shade * 31),
                    (int) (12 + shade * 34),
                    (int) (16 + shade * 39));
            case PARCHMENT -> pack(
                    (int) (90 + shade * 98),
                    (int) (73 + shade * 83),
                    (int) (44 + shade * 57));
        };
    }

    private static int pack(int red, int green, int blue) {
        return (bounded(red) << 16) | (bounded(green) << 8) | bounded(blue);
    }

    private static int bounded(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
