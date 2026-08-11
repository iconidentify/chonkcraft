package net.chonkbase.chonkcraft.desktop;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The counters along the top bar, drawn rather than blitted.
 *
 * <p>Warcraft II's resource icons are fourteen pixels square. The interface is
 * shown at two or three times that, and a fourteen pixel painting shown at
 * three times is a fourteen pixel painting: the gold nugget becomes a field of
 * three-pixel blocks and the oil drop sits on a light grey square that belongs
 * to the sheet it was cut from rather than to the bar it is drawn on. The same
 * complaint {@link StoneTexture} answers for the panels.
 *
 * <p>So these are drawn: a framed well and a symbol inside it, laid out in the
 * fourteen pixel square the layout script reserves and rendered at whatever
 * size that square really covers. Two of the six -- the score and the idle
 * worker count -- have no icon in the 1995 archive at all; ChonkCraft supplies them
 * as its own drawings, and this supplies them as drawings too, so that all six
 * are one set rather than four of one kind and two of another.
 */
final class ResourceIcons {

    /** Which counter an icon stands for. */
    enum Kind {
        /** A nugget of gold. */
        GOLD,
        /** A conifer, for lumber. */
        LUMBER,
        /** A drop of oil. */
        OIL,
        /** Supply: a sheaf and an apple. */
        FOOD,
        /** Points: the bar chart ChonkCraft draws. */
        SCORE,
        /** Idle workers: a peasant standing about. */
        WORKERS
    }

    /** The square the layout script reserves for one of these. */
    static final int DESIGN_SIZE = 14;

    private ResourceIcons() {
    }

    private static final int CACHE_LIMIT = 32;

    private static final Map<String, BufferedImage> CACHE =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
                    return size() > CACHE_LIMIT;
                }
            };

    /**
     * One icon, generated at the size it will really cover.
     *
     * @param scale how many device pixels one interface pixel covers
     */
    static synchronized BufferedImage of(Kind kind, double scale) {
        return of(kind, DESIGN_SIZE, scale);
    }

    /**
     * The same, at a chosen size in interface pixels.
     *
     * <p>The script reserves fourteen pixels for these and the strip of marble
     * they sit on is sixteen tall with a two pixel bevel down each edge, so
     * fourteen does not fit inside it: the gold line round the icon crossed the
     * bevel at the top and the bottom, and read as an icon slightly too big for
     * its bar -- which it was. The caller says how much room there really is
     * and the drawing is generated for it, rather than a fourteen pixel drawing
     * being squeezed into twelve by the blitter and going soft at the edges.
     *
     * @param designSize the square the icon has to fill, in interface pixels
     * @param scale      how many device pixels one interface pixel covers
     */
    static synchronized BufferedImage of(Kind kind, int designSize, double scale) {
        double factor = scale <= 0 ? 1.0 : Math.min(8.0, scale);
        int pixels = Math.max(8, (int) Math.round(Math.max(4, designSize) * factor));
        String key = kind + ":" + pixels;
        BufferedImage found = CACHE.get(key);
        if (found != null) {
            return found;
        }
        BufferedImage made = generate(kind, pixels);
        CACHE.put(key, made);
        return made;
    }

    /** The gold line the game's own icon frames are drawn in. */
    private static final Color FRAME = new Color(150, 122, 52);

    /** What sits behind the symbol: near black, a touch blue, like the panels. */
    private static final Color WELL = new Color(14, 15, 20);

    private static BufferedImage generate(Kind kind, int pixels) {
        BufferedImage image = new BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                RenderingHints.VALUE_STROKE_PURE);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        // Everything below is written in the fourteen pixel square the layout
        // reserves, so the drawing does not have to know what it is being
        // rendered at.
        double unit = pixels / (double) DESIGN_SIZE;
        g2.scale(unit, unit);

        g2.setColor(WELL);
        g2.fill(new Rectangle2D.Double(0.5, 0.5, DESIGN_SIZE - 1, DESIGN_SIZE - 1));
        g2.setColor(FRAME);
        g2.setStroke(new BasicStroke(1f));
        g2.draw(new Rectangle2D.Double(0.5, 0.5, DESIGN_SIZE - 1, DESIGN_SIZE - 1));

        java.awt.Shape saved = g2.getClip();
        g2.clip(new Rectangle2D.Double(1, 1, DESIGN_SIZE - 2, DESIGN_SIZE - 2));
        switch (kind) {
            case GOLD -> gold(g2);
            case LUMBER -> lumber(g2);
            case OIL -> oil(g2);
            case FOOD -> food(g2);
            case SCORE -> score(g2);
            case WORKERS -> workers(g2);
        }
        g2.setClip(saved);
        g2.dispose();
        return image;
    }

    /** A nugget: a lit lump with a shadow under it. */
    private static void gold(Graphics2D g2) {
        GeneralPath lump = new GeneralPath(Path2D.WIND_NON_ZERO);
        lump.moveTo(2.4, 9.6);
        lump.curveTo(1.6, 7.4, 3.4, 4.6, 6.2, 3.6);
        lump.curveTo(9.0, 2.6, 11.6, 4.4, 11.6, 6.6);
        lump.curveTo(11.6, 9.0, 9.4, 11.0, 6.8, 11.0);
        lump.curveTo(4.6, 11.0, 3.0, 10.6, 2.4, 9.6);
        lump.closePath();
        g2.setPaint(new GradientPaint(3, 3, new Color(255, 236, 150),
                10, 11, new Color(158, 108, 20)));
        g2.fill(lump);
        // A bright edge along the top left, which is what makes metal metal.
        g2.setColor(new Color(255, 250, 214, 220));
        g2.setStroke(new BasicStroke(1.1f));
        GeneralPath sheen = new GeneralPath();
        sheen.moveTo(3.4, 8.4);
        sheen.curveTo(3.2, 6.2, 5.0, 4.8, 7.2, 4.6);
        g2.draw(sheen);
        g2.setColor(new Color(96, 62, 10, 200));
        GeneralPath shade = new GeneralPath();
        shade.moveTo(4.0, 10.6);
        shade.curveTo(7.6, 11.4, 10.2, 10.0, 11.2, 7.6);
        g2.draw(shade);
    }

    /** A conifer: three tiers and a trunk. */
    private static void lumber(Graphics2D g2) {
        g2.setColor(new Color(96, 62, 30));
        g2.fill(new Rectangle2D.Double(6.2, 9.4, 1.6, 3.2));
        for (int tier = 0; tier < 3; tier++) {
            double top = 1.6 + tier * 2.6;
            double halfWidth = 2.0 + tier * 1.4;
            GeneralPath bough = new GeneralPath();
            bough.moveTo(7.0, top);
            bough.lineTo(7.0 + halfWidth, top + 3.4);
            bough.lineTo(7.0 - halfWidth, top + 3.4);
            bough.closePath();
            g2.setPaint(new GradientPaint((float) (7.0 - halfWidth), (float) top,
                    new Color(74, 150, 62),
                    (float) (7.0 + halfWidth), (float) (top + 3.4),
                    new Color(22, 74, 30)));
            g2.fill(bough);
        }
    }

    /** A drop of oil, black with one highlight. */
    private static void oil(Graphics2D g2) {
        GeneralPath drop = new GeneralPath();
        drop.moveTo(7.0, 1.6);
        drop.curveTo(9.4, 5.0, 11.4, 6.8, 11.4, 8.8);
        drop.curveTo(11.4, 11.2, 9.4, 12.6, 7.0, 12.6);
        drop.curveTo(4.6, 12.6, 2.6, 11.2, 2.6, 8.8);
        drop.curveTo(2.6, 6.8, 4.6, 5.0, 7.0, 1.6);
        drop.closePath();
        g2.setPaint(new GradientPaint(4, 4, new Color(64, 66, 78),
                10, 12, new Color(6, 6, 10)));
        g2.fill(drop);
        g2.setColor(new Color(220, 226, 240, 210));
        g2.fill(new Ellipse2D.Double(4.5, 7.6, 1.6, 2.4));
    }

    /** Supply: a loaf and an apple, which is what the shipped icon shows. */
    private static void food(Graphics2D g2) {
        g2.setPaint(new GradientPaint(2, 3, new Color(216, 176, 96),
                9, 8, new Color(146, 100, 40)));
        g2.fill(new Ellipse2D.Double(1.6, 3.0, 8.0, 4.6));
        g2.setColor(new Color(120, 78, 30, 190));
        g2.setStroke(new BasicStroke(0.8f));
        for (int slash = 0; slash < 3; slash++) {
            double x = 3.2 + slash * 2.0;
            g2.draw(new java.awt.geom.Line2D.Double(x, 3.8, x - 0.8, 6.4));
        }
        g2.setPaint(new GradientPaint(6, 7, new Color(224, 74, 60),
                12, 13, new Color(140, 24, 24)));
        g2.fill(new Ellipse2D.Double(6.4, 7.0, 6.0, 5.8));
        g2.setColor(new Color(255, 210, 200, 200));
        g2.fill(new Ellipse2D.Double(7.6, 8.2, 1.4, 1.4));
        g2.setColor(new Color(60, 132, 52));
        g2.fill(new Ellipse2D.Double(9.6, 6.2, 2.4, 1.2));
    }

    /** Points: three bars of a chart, as the shipped icon has. */
    private static void score(Graphics2D g2) {
        Color[] bars = {new Color(198, 44, 44), new Color(46, 92, 214), new Color(38, 176, 132)};
        double[] tops = {2.4, 6.2, 4.4};
        for (int bar = 0; bar < 3; bar++) {
            double x = 2.2 + bar * 3.4;
            g2.setColor(bars[bar]);
            g2.fill(new Rectangle2D.Double(x, tops[bar], 2.6, 11.0 - tops[bar]));
            g2.setColor(new Color(255, 255, 255, 70));
            g2.fill(new Rectangle2D.Double(x, tops[bar], 0.7, 11.0 - tops[bar]));
        }
        g2.setColor(new Color(190, 190, 200));
        g2.fill(new Rectangle2D.Double(1.4, 11.0, 11.2, 0.9));
    }

    /** Idle workers: one peasant, standing about doing nothing. */
    private static void workers(Graphics2D g2) {
        g2.setColor(new Color(236, 206, 68));
        g2.fill(new Ellipse2D.Double(5.2, 1.8, 3.6, 2.6));
        g2.setColor(new Color(206, 168, 132));
        g2.fill(new Ellipse2D.Double(5.4, 3.2, 3.2, 2.8));
        g2.setPaint(new GradientPaint(5, 6, new Color(154, 104, 68),
                9, 11, new Color(96, 60, 38)));
        g2.fill(new Rectangle2D.Double(5.0, 5.8, 4.0, 5.2));
        g2.setColor(new Color(176, 176, 184));
        g2.fill(new Rectangle2D.Double(3.6, 6.4, 1.4, 3.4));
        g2.fill(new Rectangle2D.Double(9.0, 6.4, 1.4, 3.4));
        g2.fill(new Rectangle2D.Double(5.2, 11.0, 1.4, 1.8));
        g2.fill(new Rectangle2D.Double(7.4, 11.0, 1.4, 1.8));
    }
}
