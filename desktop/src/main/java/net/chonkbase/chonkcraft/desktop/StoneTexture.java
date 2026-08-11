package net.chonkbase.chonkcraft.desktop;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The interface's stone, generated rather than scanned.
 *
 * <p>Warcraft II's panels are a 176 pixel photograph of marble. At the size it
 * was drawn for it is fine; at any other size it is not, and there is no way to
 * make it so. Stretched it smears into vertical streaks. Tiled it repeats every
 * 176 pixels, and the eye finds that instantly -- a photograph has features in
 * it, and a feature that recurs on a grid stops reading as stone and starts
 * reading as wallpaper. Enlarged it is a 176 pixel photograph enlarged.
 *
 * <p>So the stone is computed at whatever size the panel actually is. There is
 * no source image to smear, no tile to repeat and no resolution to run out of:
 * a panel three times the size is not the same stone bigger, it is more stone.
 *
 * <p>The pattern is fractal noise -- several octaves of value noise summed,
 * each finer and fainter than the last -- with the coordinates of the finer
 * octaves bent by the coarser ones. That bending is what gives the veining its
 * wander; noise alone gives clouds, and clouds do not read as rock. A ridge
 * function on top picks out the sharp bright veins that make marble marble.
 *
 * <p>Everything is deterministic, from a fixed seed, so the same panel looks
 * the same every time the game is opened.
 */
final class StoneTexture {

    /** How the stone is coloured. */
    enum Tint {
        /** The panels and the sidebar: cool grey granite. */
        STONE,
        /** The buttons: the blue slate Warcraft II uses for them. */
        SLATE,
        /** A pressed button, which is the same slate in shadow. */
        SLATE_PRESSED
    }

    /**
     * Generated textures, by size and tint.
     *
     * <p>Small: a handful of panels at a handful of sizes. Bounded anyway,
     * because a window being dragged to resize would otherwise generate a
     * texture per pixel of travel.
     */
    private static final int CACHE_LIMIT = 48;

    private static final Map<String, BufferedImage> CACHE =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
                    return size() > CACHE_LIMIT;
                }
            };

    private StoneTexture() {
    }

    /** A slab of stone at exactly this size. */
    static synchronized BufferedImage of(int width, int height, Tint tint) {
        return of(width, height, tint, 1.0);
    }

    /**
     * A slab of stone that will be shown at this size and drawn this much
     * larger.
     *
     * <p>The interface is laid out in the game's own 640 by 480 pixels and the
     * whole of it is put through a scaling transform, so a panel declared 176
     * wide covers 528 device pixels at threefold. Generating 176 pixels of
     * stone and letting the transform trebble it is the very fault the
     * original marble had: the pattern comes out in three-pixel blocks. So the
     * slab is generated at the size it will really occupy, and the grain is
     * enlarged to match, which gives the same stone at three times the
     * resolution rather than three times the size.
     *
     * @param width  the width in the interface's own pixels
     * @param height likewise
     * @param scale  how many device pixels one interface pixel covers
     */
    static synchronized BufferedImage of(int width, int height, Tint tint, double scale) {
        if (width <= 0 || height <= 0) {
            return null;
        }
        double factor = scale <= 0 ? 1.0 : Math.min(MAX_SCALE, scale);
        int pixelWidth = Math.max(1, (int) Math.round(width * factor));
        int pixelHeight = Math.max(1, (int) Math.round(height * factor));
        String key = pixelWidth + "x" + pixelHeight + ":" + tint + ":" + factor;
        BufferedImage found = CACHE.get(key);
        if (found != null) {
            return found;
        }
        BufferedImage made = generate(pixelWidth, pixelHeight, tint, factor);
        CACHE.put(key, made);
        return made;
    }

    /**
     * As large a scale as is worth generating for.
     *
     * <p>A guard rather than a judgement: the scale comes off a graphics
     * transform, and a transform with something unexpected in it must not be
     * able to ask for a hundred megapixels of marble.
     */
    private static final double MAX_SCALE = 8.0;

    /**
     * How coarse the stone is, in pixels per unit of noise.
     *
     * <p>Fixed rather than relative to the panel, so the grain stays the same
     * size whether it is a small button or the whole sidebar. Making it
     * relative would give a button the grain of a boulder.
     */
    private static final double GRAIN = 26.0;

    private static BufferedImage generate(int width, int height, Tint tint, double scale) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] pixels = new int[width * height];

        // The grain is enlarged with the slab, so a panel drawn twice the size
        // is the same stone seen closer rather than a finer-grained stone.
        double grain = GRAIN * scale;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double u = x / grain;
                double v = y / grain;

                // Bend the coordinates with a coarse field before sampling the
                // fine one, so the grain wanders instead of running straight.
                // Gently: a strong warp gives swirls, and swirls read as smoke
                // rather than as rock.
                double warpX = u + 0.45 * fractal(u * 0.6 + 11.3, v * 0.6 + 4.1, 2);
                double warpY = v + 0.45 * fractal(u * 0.6 + 2.7, v * 0.6 + 19.6, 2);

                // The body of the stone: broad, quiet mottling. Real granite
                // holds a narrow range of value with the interest in its
                // texture, not in big light and dark patches.
                double body = fractal(warpX, warpY, 4);

                // A very faint ridge, and no more than faint. Pushed any
                // harder this closes into a network of outlined cells --
                // crazy paving -- and a regular cell structure is the surest
                // sign that something was computed rather than quarried.
                double ridge = 1.0 - Math.abs(2.0 * fractal(warpX * 1.3, warpY * 1.3, 3) - 1.0);
                double seam = Math.pow(Math.max(0, ridge), 20);

                // Granite is speckled at close range, and this is what stops
                // it going glassy when a panel is drawn at three times the
                // size. Two frequencies: one you can see, one you can only
                // feel.
                double grit = fractal(u * 19.0, v * 19.0, 2) - 0.5;
                double fine = fractal(u * 53.0, v * 53.0, 1) - 0.5;

                // A broad, slow gradient across the whole slab, so a large
                // panel has some depth in it rather than being uniformly busy.
                double depth = fractal(u * 0.22 + 31.7, v * 0.22 + 8.9, 2) - 0.5;

                double shade = clamp(0.47
                        + (body - 0.5) * 0.24
                        + depth * 0.22
                        - seam * 0.16
                        + grit * 0.13
                        + fine * 0.10);
                pixels[y * width + x] = colour(shade, tint);
            }
        }
        image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }

    /** Sums octaves of value noise, each finer and fainter. */
    private static double fractal(double x, double y, int octaves) {
        double total = 0;
        double amplitude = 0.5;
        double frequency = 1.0;
        double weight = 0;
        for (int i = 0; i < octaves; i++) {
            total += amplitude * noise(x * frequency, y * frequency, i);
            weight += amplitude;
            amplitude *= 0.5;
            frequency *= 2.0;
        }
        return weight == 0 ? 0 : total / weight;
    }

    /** Smoothly interpolated value noise on the integer lattice. */
    private static double noise(double x, double y, int layer) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        double fx = smooth(x - x0);
        double fy = smooth(y - y0);

        double topLeft = hash(x0, y0, layer);
        double topRight = hash(x0 + 1, y0, layer);
        double bottomLeft = hash(x0, y0 + 1, layer);
        double bottomRight = hash(x0 + 1, y0 + 1, layer);

        double top = topLeft + (topRight - topLeft) * fx;
        double bottom = bottomLeft + (bottomRight - bottomLeft) * fx;
        return top + (bottom - top) * fy;
    }

    /** The smoothstep curve, so the lattice does not show as a grid of creases. */
    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    /** A stable pseudo-random value in nought to one for a lattice point. */
    private static double hash(int x, int y, int layer) {
        int h = x * 374_761_393 + y * 668_265_263 + layer * 1_442_695_041;
        h = (h ^ (h >>> 13)) * 1_274_126_177;
        h = h ^ (h >>> 16);
        return (h & 0x7FFF_FFFF) / (double) 0x7FFF_FFFF;
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    /**
     * The colour ramp.
     *
     * <p>Warcraft II's own palette, near enough: the panels are a cool grey
     * that never goes white, and the buttons a deep blue that lifts to steel
     * at the highlights. Both keep a good deal of contrast, because the
     * lettering that goes on top of them is a bitmap font with no outline and
     * needs something to sit against.
     */
    private static int colour(double shade, Tint tint) {
        return switch (tint) {
            case STONE -> {
                // Measured against the art it replaces rather than judged by
                // eye: the game's own panels average 37 to 44, and the first
                // attempt at this averaged 101. More than twice as bright is
                // not a matter of taste -- the lettering on top is a bitmap
                // with no outline, and it stops being readable.
                int base = (int) (14 + shade * 62);
                // A touch more blue than red keeps it stone rather than mud.
                yield pack(base, base + 2, base + 7);
            }
            case SLATE -> {
                // Left brighter than the stone on purpose: a button has to
                // stand off the panel it sits on.
                // A blue-grey stone, not a blue. The lettering that goes on
                // top has no outline, so the button has to stay dark enough
                // for white to read against it and quiet enough not to fight
                // the gold of the headings.
                int base = (int) (30 + shade * 58);
                yield pack((int) (base * 0.80), (int) (base * 0.92), (int) (base * 1.22) + 14);
            }
            case SLATE_PRESSED -> {
                int base = (int) (26 + shade * 44);
                yield pack((int) (base * 0.80), (int) (base * 0.92), (int) (base * 1.22) + 10);
            }
        };
    }

    private static int pack(int red, int green, int blue) {
        return (Math.min(255, Math.max(0, red)) << 16)
                | (Math.min(255, Math.max(0, green)) << 8)
                | Math.min(255, Math.max(0, blue));
    }
}
