package net.chonkbase.chonkcraft.desktop;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.io.InputStream;

/**
 * Draws the game's text.
 *
 * <p>This used to blit Warcraft II's own eight-shade bitmap glyphs. Those were
 * drawn for a 640 by 480 screen and there is nothing to be done about that: a
 * bitmap glyph enlarged is an enlarged bitmap glyph, and at twice the size it
 * is visibly a grid of squares. The shading did not survive it either, since
 * the recolouring worked on four shade indices and enlarging turned each of
 * them into a block.
 *
 * <p>So the text is an outline font now, rendered at whatever size it is asked
 * for. Nothing else changes: this class keeps exactly the interface it had --
 * the same three faces, the same four inks, the same {@code draw} taking the
 * top of the line rather than the baseline -- so every place that prints
 * anything is untouched and the layouts stay where they were put.
 *
 * <p>The face is shipped in the jar rather than looked up by name. A font found
 * by family name is one uninstall away from being a different font, and the
 * sidebar is laid out to the width of the lettering in it.
 */
final class GameFont {

    /** Which of the shipped faces to use. */
    enum Face {
        /** The interface face, used for nearly everything in game. */
        GAME(13f, Font.PLAIN),
        /** Larger, for menu headings. */
        LARGE(19f, Font.BOLD),
        /** Smaller, for status lines and tooltips. */
        SMALL(11f, Font.PLAIN);

        private final float size;
        private final int style;

        Face(float size, int style) {
            this.size = size;
            this.style = style;
        }
    }

    /** How the game colours text. */
    enum Ink {
        /** The default parchment white. */
        WHITE,
        /** Resource counts and headings. */
        YELLOW,
        /** Warnings and refusals. */
        RED,
        /** Something unavailable. */
        GREY
    }

    /**
     * The ink a HUD element uses when nothing names a colour for it.
     *
     * <p>{@code UI.NormalFontColor}, declared per race in
     * {@code scripts/human/ui_pandora.legacy-declaration:81} ("white") and
     * {@code scripts/orc/ui_pandora.legacy-declaration:81} ("yellow"), and handed to
     * {@code SetDefaultTextColors}. Upstream's text drawing falls back to it
     * everywhere a colour is not given explicitly, which is why an orc mission
     * has a yellow HUD and a human one a white.
     *
     * <p>Nothing read it. The ink was a literal at every call site, so "the
     * default" had nowhere to live and every mission drew white -- the side
     * panel obeyed the player's race in its artwork and its text did not.
     *
     * <p>The two colours are named here rather than taken from the layout,
     * which is a gap and not a decision: {@code UiLayout} stubs
     * {@code SetDefaultTextColors} out and its {@code Layout} record carries no
     * font colour, so there is nothing to read yet. Adding
     * {@code String normalFontColor} to that record and capturing
     * {@code UI.NormalFontColor} in {@code collect()} is a one-line change in a
     * file this side of the implementation does not own; when it lands, this method
     * should take the layout instead of the race and this table should go.
     *
     * <p>Only the colours the scripts actually declare are mapped. Anything
     * else falls back to white, which is what the two human interfaces use and
     * what every menu outside a mission is drawn in.
     */
    static Ink normalInkFor(String race) {
        String named = race == null ? "" : race.toLowerCase(java.util.Locale.ROOT);
        return "orc".equals(named) ? Ink.YELLOW : Ink.WHITE;
    }

    /** Where the face lives inside the jar. */
    private static final String REGULAR = "/fonts/DroidSerif-Regular.ttf";

    private static final String BOLD = "/fonts/DroidSerif-Bold.ttf";

    /** Loaded once; deriving a size from a loaded font is cheap, loading is not. */
    private static Font regularBase;
    private static Font boldBase;
    private static boolean loadAttempted;

    /**
     * Whether anything has had to fall back to a font off the machine.
     *
     * <p>Kept, and complained about, rather than quietly substituted. The
     * shipped face is what every screen is laid out to; a machine's own serif
     * is a different set of widths, and a menu drawn in it does not merely look
     * different, it looks wrong beside the in-game panels that were measured
     * against the shipped one. Silence about that turns a broken build into a
     * puzzle about why the menus look cheap.
     */
    private static boolean fellBack;

    private final Font font;
    private final int lineHeight;
    private final int ascent;

    private GameFont(Font font, Extent extent) {
        this.font = font;
        this.ascent = extent.ascent();
        this.lineHeight = extent.height();
    }

    /** What a face actually marks: where its ink starts and how tall it is. */
    private record Extent(int ascent, int height) {}

    /**
     * Measures the ink rather than the box.
     *
     * <p>A caller passes the top of a line and expects the lettering to start
     * there. An outline face's reported ascent is not that: it reserves room
     * above the capitals for accents no English string uses, and drawing at
     * that distance below the top puts every line visibly low in whatever it
     * sits in -- which is what happened to the resource bar and to the name
     * above a selected unit, and why it looked systemic. It was: one wrong
     * number, used by everything.
     *
     * <p>So the face is drawn once and looked at, and the distance from the
     * top of the ink to the baseline is what {@code draw} uses. The height is
     * the ink's own, so anything centring a line on this number centres what
     * can be seen.
     */
    private static Extent measure(Font font) {
        String probe = "ABCXYZabcdefghijklmnopqrstuvwxyz0123456789/%";
        java.awt.image.BufferedImage scratch =
                new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D probeContext = scratch.createGraphics();
        applyHints(probeContext);
        FontMetrics metrics = probeContext.getFontMetrics(font);
        int boxAscent = metrics.getAscent();
        int boxDescent = metrics.getDescent();
        int width = Math.max(1, metrics.stringWidth(probe) + 8);
        int height = Math.max(1, boxAscent + boxDescent + 8);
        probeContext.dispose();

        java.awt.image.BufferedImage sheet =
                new java.awt.image.BufferedImage(width, height,
                        java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = sheet.createGraphics();
        applyHints(g2);
        g2.setFont(font);
        g2.setColor(Color.WHITE);
        int baseline = boxAscent + 4;
        g2.drawString(probe, 4, baseline);
        g2.dispose();

        int top = -1;
        int bottom = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((sheet.getRGB(x, y) >>> 24) > 24) {
                    if (top < 0) {
                        top = y;
                    }
                    bottom = y;
                    break;
                }
            }
        }
        if (top < 0) {
            // Nothing was drawn, which should not happen; the reported box is
            // a worse answer than the measured one but better than none.
            return new Extent(boxAscent, boxAscent + boxDescent);
        }

        // The ink is centred in the line box rather than pushed to the top of
        // it or hung from the reported ascent. Both of those have been tried
        // and both are visibly wrong: hanging from the ascent puts every line
        // low, because an outline face reserves room above the capitals for
        // accents no English string uses, and putting the ink at the very top
        // puts every line high, because the space the face leaves below the
        // descenders is then all on one side. Half the slack above and half
        // below is the only division that does not favour one edge, and it
        // makes anything centring a line on height() exactly centred: the box
        // is symmetrical about the ink.
        int inkAscent = baseline - top;
        int inkHeight = bottom - top + 1;
        int boxHeight = boxAscent + boxDescent;
        int leading = Math.max(0, (boxHeight - inkHeight) / 2);
        return new Extent(inkAscent + leading, boxHeight);
    }

    /**
     * Loads a face.
     *
     * <p>Takes the game data it no longer needs, so that callers do not have to
     * change. Never returns null: text that cannot be drawn is worse than text
     * drawn in whatever the runtime has, and the fallback is a real font rather
     * than nothing.
     */
    static GameFont load(net.chonkbase.chonkcraft.engine.GameData data, Face face) {
        Font base = base(face.style == Font.BOLD);
        Font sized = base.deriveFont(face.style, face.size);
        return new GameFont(sized, measure(sized));
    }

    /**
     * The same face at another size.
     *
     * <p>For the one caller that cannot use a fixed face: the briefing sets its
     * prose smaller until it fits the page, and the sizes it tries are between
     * the ones the three faces offer. It used to derive those from
     * {@code getFont()}, which is whatever the look and feel hands out -- a
     * different family from every other screen in the game, and a different one
     * again on somebody else's machine.
     */
    GameFont atSize(float size) {
        Font sized = font.deriveFont(font.getStyle(), size);
        return new GameFont(sized, measure(sized));
    }

    /** The family this face is cut from, so a test can prove it is the one. */
    String family() {
        return font.getFamily(java.util.Locale.ROOT);
    }

    /**
     * Whether any face has had to come off the machine rather than the jar.
     *
     * <p>A build that ships without its fonts still runs, because text drawn in
     * the wrong face beats no text at all -- but it must not do so quietly, and
     * this is what the test asserts on.
     */
    static synchronized boolean usingFallbackFace() {
        base(false);
        base(true);
        return fellBack;
    }

    private static synchronized Font base(boolean bold) {
        if (!loadAttempted) {
            loadAttempted = true;
            regularBase = read(REGULAR);
            boldBase = read(BOLD);
        }
        Font wanted = bold ? boldBase : regularBase;
        if (wanted != null) {
            return wanted;
        }
        // Nothing in the jar. The game goes on in whatever serif the runtime
        // has, but it says so: every layout in the program is measured against
        // the shipped face and none of them are right in this one.
        if (!fellBack) {
            fellBack = true;
            System.err.println("chonkcraft: the shipped lettering (" + REGULAR + ", " + BOLD
                    + ") is not on the classpath. Every menu will be drawn in the "
                    + "runtime's own serif, at widths nothing was laid out to.");
        }
        return new Font(Font.SERIF, bold ? Font.BOLD : Font.PLAIN, 12);
    }

    private static Font read(String resource) {
        try (InputStream stream = GameFont.class.getResourceAsStream(resource)) {
            if (stream == null) {
                return null;
            }
            return Font.createFont(Font.TRUETYPE_FONT, stream);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The hints that decide whether small text is readable.
     *
     * <p>Antialiasing on, and fractional metrics off. Fractional metrics
     * place glyphs at sub-pixel positions, which is right for a page of prose
     * and wrong for thirteen pixel labels on a dark panel: the stems land
     * between pixels and go grey.
     */
    private static void applyHints(Graphics2D g2) {
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
    }

    /** How tall a line of this face is. */
    int height() {
        return lineHeight;
    }

    /** How wide a string draws. */
    int widthOf(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        java.awt.image.BufferedImage scratch =
                new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = scratch.createGraphics();
        applyHints(g2);
        int width = g2.getFontMetrics(font).stringWidth(text);
        g2.dispose();
        return width;
    }

    /**
     * The most of a string that fits a width, cut short with an ellipsis.
     *
     * <p>For the strings that are not this program's: a map somebody named, a
     * save somebody titled, the name a player types before joining. Every one
     * of those goes into a column of a fixed size, and the alternative to
     * cutting it is what the lobby list did -- draw it at full length across
     * whatever was in the next column.
     *
     * @param width the room there is, in the same pixels the text is drawn in
     */
    String fitted(String text, int width) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        java.awt.image.BufferedImage scratch =
                new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = scratch.createGraphics();
        try {
            applyHints(g2);
            FontMetrics metrics = g2.getFontMetrics(font);
            if (metrics.stringWidth(text) <= width) {
                return text;
            }
            String ellipsis = "...";
            int room = width - metrics.stringWidth(ellipsis);
            if (room <= 0) {
                return "";
            }
            int end = text.length();
            while (end > 0 && metrics.stringWidth(text.substring(0, end)) > room) {
                end--;
            }
            return end == 0 ? "" : text.substring(0, end).stripTrailing() + ellipsis;
        } finally {
            g2.dispose();
        }
    }

    /**
     * Draws a string.
     *
     * @param x the left edge
     * @param y the top of the line, as the bitmap faces took it
     */
    void draw(Graphics2D g2, String text, int x, int y, Ink ink) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Object savedAntialias = g2.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
        Object savedFractional = g2.getRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS);
        Font savedFont = g2.getFont();
        Color savedColour = g2.getColor();

        applyHints(g2);
        g2.setFont(font);
        g2.setColor(colourOf(ink));
        g2.drawString(text, x, y + ascent);

        g2.setFont(savedFont);
        g2.setColor(savedColour);
        if (savedAntialias != null) {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, savedAntialias);
        }
        if (savedFractional != null) {
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, savedFractional);
        }
    }

    /** Draws centred on a point. */
    void drawCentred(Graphics2D g2, String text, int centreX, int y, Ink ink) {
        draw(g2, text, centreX - widthOf(text) / 2, y, ink);
    }

    /**
     * A colour matching an ink.
     *
     * <p>The game's own, and worth keeping: the parchment white is warm rather
     * than pure, and the gold is the colour of its headings.
     */
    static Color colourOf(Ink ink) {
        return switch (ink) {
            case WHITE -> new Color(0xF0F0E8);
            case YELLOW -> new Color(0xFFDC50);
            case RED -> new Color(0xE04840);
            case GREY -> new Color(0x9E9E9E);
        };
    }
}
