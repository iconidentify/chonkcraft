package net.chonkbase.chonkcraft.launcher;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.io.InputStream;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.AbstractBorder;

/** The launcher palette, type and Warcraft-style stone furniture. */
final class LauncherTheme {

    static final Color GOLD = new Color(225, 183, 87);
    static final Color GOLD_BRIGHT = new Color(255, 222, 132);
    static final Color INK = new Color(231, 232, 225);
    static final Color MUTED = new Color(166, 177, 185);
    static final Color DEEP = new Color(11, 15, 21);
    static final Color BLUE = new Color(34, 54, 79);
    static final Color BLUE_HOVER = new Color(46, 72, 102);
    static final Color DISABLED = new Color(62, 67, 72);
    static final Font REGULAR = load("/fonts/DroidSerif-Regular.ttf", Font.PLAIN);
    static final Font BOLD = load("/fonts/DroidSerif-Bold.ttf", Font.BOLD);

    private LauncherTheme() {
    }

    static JLabel label(String text, int size, Color colour) {
        JLabel label = new JLabel(text);
        label.setFont(REGULAR.deriveFont((float) size));
        label.setForeground(colour);
        return label;
    }

    static JLabel heading(String text, int size) {
        JLabel label = new JLabel(text);
        label.setFont(BOLD.deriveFont((float) size));
        label.setForeground(GOLD);
        return label;
    }

    static JButton button(String text, boolean primary) {
        return new StoneButton(text, primary);
    }

    static class StonePanel extends JPanel {

        private final MarbleTexture.Tint tint;

        StonePanel(MarbleTexture.Tint tint) {
            this.tint = tint;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            var stone = MarbleTexture.of(getWidth(), getHeight(), tint);
            if (stone != null) {
                g.drawImage(stone, 0, 0, null);
            }
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    static final class StoneBorder extends AbstractBorder {

        @Override
        public Insets getBorderInsets(Component component) {
            return new Insets(2, 2, 2, 2);
        }

        @Override
        public void paintBorder(Component component, Graphics graphics,
                int x, int y, int width, int height) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setStroke(new BasicStroke(1f));
            g.setColor(new Color(132, 145, 151));
            g.drawRect(x, y, width - 1, height - 1);
            g.setColor(new Color(18, 23, 29));
            g.drawRect(x + 2, y + 2, width - 5, height - 5);
            g.setColor(new Color(82, 93, 101));
            g.drawRect(x + 3, y + 3, width - 7, height - 7);
            g.dispose();
        }
    }

    private static final class BevelBorder extends AbstractBorder {

        private final Color highlight;

        private BevelBorder(Color highlight) {
            this.highlight = highlight;
        }

        @Override
        public Insets getBorderInsets(Component component) {
            return new Insets(2, 2, 2, 2);
        }

        @Override
        public void paintBorder(Component component, Graphics graphics,
                int x, int y, int width, int height) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setColor(highlight);
            g.drawLine(x, y, x + width - 2, y);
            g.drawLine(x, y, x, y + height - 2);
            g.setColor(new Color(8, 10, 13));
            g.drawLine(x + 1, y + height - 1, x + width - 1, y + height - 1);
            g.drawLine(x + width - 1, y + 1, x + width - 1, y + height - 1);
            g.dispose();
        }
    }

    /**
     * A carved launcher control, painted here instead of delegated to the host
     * operating system so the main gate and the pack manager remain part of
     * the same game on every desktop.
     */
    private static final class StoneButton extends JButton {

        private static final int LABEL_SAFETY_GUTTER = 16;
        private final boolean primary;

        private StoneButton(String text, boolean primary) {
            super(text);
            this.primary = primary;
            setFont(BOLD.deriveFont(primary ? 18f : 13f));
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setBorder(BorderFactory.createEmptyBorder(primary ? 16 : 10,
                    primary ? 28 : 16, primary ? 16 : 10, primary ? 28 : 16));
        }

        /**
         * Swing's host look-and-feel may report a preferred width before its
         * final display metrics are installed. On Retina macOS that allowed
         * BasicButtonUI to replace the end of otherwise short labels with an
         * ellipsis. Derive the floor from the button's live font and insets on
         * every layout pass, with a small gutter for fractional scaling.
         */
        @Override
        public Dimension getPreferredSize() {
            Dimension preferred = super.getPreferredSize();
            FontMetrics metrics = getFontMetrics(getFont());
            Insets insets = getInsets();
            String label = getText() == null ? "" : getText();
            int completeLabelWidth = metrics.stringWidth(label)
                    + insets.left + insets.right + LABEL_SAFETY_GUTTER;
            return new Dimension(Math.max(preferred.width, completeLabelWidth),
                    preferred.height);
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            int width = getWidth();
            int height = getHeight();
            if (primary) {
                Color top = isEnabled() ? GOLD_BRIGHT : new Color(106, 103, 91);
                Color bottom = isEnabled() ? new Color(191, 139, 48) : DISABLED;
                if (getModel().isPressed()) {
                    top = new Color(166, 116, 38);
                    bottom = new Color(119, 78, 23);
                } else if (getModel().isRollover() && isEnabled()) {
                    top = new Color(255, 234, 166);
                    bottom = GOLD;
                }
                g.setPaint(new GradientPaint(0, 0, top, 0, height, bottom));
                g.fillRect(2, 2, width - 4, height - 4);
            } else {
                var stone = MarbleTexture.of(width - 4, height - 4,
                        MarbleTexture.Tint.BLUE_STONE);
                if (stone != null) {
                    g.drawImage(stone, 2, 2, null);
                }
                Color wash = !isEnabled() ? new Color(30, 30, 30, 165)
                        : getModel().isPressed() ? new Color(0, 0, 0, 105)
                        : getModel().isRollover() ? new Color(92, 130, 166, 65)
                        : new Color(10, 21, 35, 65);
                g.setColor(wash);
                g.fillRect(2, 2, width - 4, height - 4);
            }
            g.setColor(primary && isEnabled() ? GOLD_BRIGHT : new Color(116, 137, 153));
            g.drawRect(0, 0, width - 2, height - 2);
            g.setColor(new Color(7, 10, 14));
            g.drawRect(2, 2, width - 4, height - 4);
            g.setColor(primary ? new Color(113, 72, 19) : new Color(73, 91, 108));
            g.drawRect(3, 3, width - 6, height - 6);
            g.dispose();

            setForeground(primary && isEnabled() ? new Color(31, 21, 8)
                    : isEnabled() ? INK : new Color(145, 147, 146));
            super.paintComponent(graphics);
        }
    }

    private static Font load(String resource, int fallbackStyle) {
        try (InputStream in = LauncherTheme.class.getResourceAsStream(resource)) {
            if (in != null) {
                return Font.createFont(Font.TRUETYPE_FONT, in);
            }
        } catch (Exception e) {
            // The platform serif keeps every control usable if a resource is damaged.
        }
        return new Font(Font.SERIF, fallbackStyle, 14);
    }

    static void makeTransparent(JComponent component) {
        component.setOpaque(false);
    }
}
