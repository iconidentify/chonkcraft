package net.chonkbase.chonkcraft.launcher;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import javax.swing.JComponent;
import javax.swing.Timer;

/**
 * A determinate progress meter made as a piece of the game rather than a host
 * operating-system control.
 *
 * <p>The trough is carved blue stone, the completed work is a bank of molten
 * gold segments, and the live edge carries a small animated glint. Animation
 * is decoration only: the width always comes from measured importer work.
 */
final class ForgeProgressBar extends JComponent {

    private int percent;
    private int pulse;
    private boolean indeterminate = true;
    private boolean active;
    private final Timer animation = new Timer(55, event -> {
        pulse = (pulse + 1) % 120;
        if (isShowing()) {
            repaint();
        }
    });

    ForgeProgressBar() {
        setOpaque(false);
        setPreferredSize(new Dimension(380, 29));
        setMinimumSize(new Dimension(180, 29));
        setFont(LauncherTheme.BOLD.deriveFont(11f));
        setToolTipText("Graphics pack import progress");
    }

    void begin() {
        percent = 0;
        indeterminate = true;
        active = true;
        if (isShowing()) {
            animation.start();
        }
        repaint();
    }

    void measured(int value) {
        percent = Math.max(0, Math.min(100, value));
        indeterminate = false;
        active = true;
        if (isShowing()) {
            animation.start();
        }
        setToolTipText(percent + " percent complete");
        repaint();
    }

    void rest() {
        indeterminate = false;
        active = false;
        animation.stop();
        repaint();
    }

    int percent() {
        return percent;
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (active) {
            animation.start();
        }
    }

    @Override
    public void removeNotify() {
        animation.stop();
        super.removeNotify();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);

        int width = getWidth();
        int height = getHeight();
        int x = 5;
        int y = 5;
        int innerWidth = Math.max(0, width - 10);
        int innerHeight = Math.max(0, height - 10);

        // A three-line bevel reads as a recess cut into the same stone as the
        // manager, even at the launcher's small physical size.
        g.setColor(new Color(8, 11, 16));
        g.fillRoundRect(0, 0, width, height, 8, 8);
        g.setColor(new Color(120, 137, 149));
        g.drawRoundRect(1, 1, width - 3, height - 3, 7, 7);
        g.setColor(new Color(38, 51, 65));
        g.drawRoundRect(3, 3, width - 7, height - 7, 5, 5);
        g.setPaint(new GradientPaint(0, y, new Color(5, 10, 17),
                0, y + innerHeight, new Color(25, 39, 54)));
        g.fillRoundRect(x, y, innerWidth, innerHeight, 4, 4);

        int fill;
        int fillStart = x;
        if (indeterminate) {
            fill = Math.max(36, innerWidth / 4);
            fillStart = x + (pulse * Math.max(1, innerWidth + fill) / 120) - fill;
        } else {
            fill = (int) Math.round(innerWidth * percent / 100.0);
        }
        int clippedStart = Math.max(x, fillStart);
        int clippedEnd = Math.min(x + innerWidth, fillStart + fill);
        int painted = Math.max(0, clippedEnd - clippedStart);
        if (painted > 0) {
            var oldClip = g.getClip();
            g.clipRect(x, y, innerWidth, innerHeight);
            g.setPaint(new GradientPaint(0, y,
                    new Color(255, 232, 143), 0, y + innerHeight,
                    new Color(169, 96, 25)));
            g.fillRoundRect(fillStart, y, fill, innerHeight, 4, 4);
            g.setColor(new Color(255, 247, 190, 150));
            g.drawLine(fillStart + 3, y + 3, fillStart + fill - 3, y + 3);

            // Repeated iron seams make the counter legible as completed
            // sections, without changing the measured width.
            g.setStroke(new BasicStroke(1f));
            for (int seam = x + 18; seam < x + innerWidth; seam += 18) {
                if (seam >= fillStart && seam < fillStart + fill) {
                    g.setColor(new Color(91, 48, 13, 120));
                    g.drawLine(seam, y + 3, seam, y + innerHeight - 3);
                    g.setColor(new Color(255, 224, 124, 85));
                    g.drawLine(seam + 1, y + 3, seam + 1, y + innerHeight - 3);
                }
            }

            int edge = Math.min(x + innerWidth - 2, fillStart + fill - 1);
            int glint = 72 + (pulse % 20) * 7;
            g.setColor(new Color(255, 246, 190, Math.min(210, glint)));
            g.fillOval(edge - 4, y + 2, 8, Math.max(3, innerHeight - 4));
            g.setClip(oldClip);
        }

        // Small heraldic arrowheads at the ends finish the meter like a piece
        // of UI furniture rather than a flat web rectangle.
        g.setColor(new Color(121, 139, 151));
        drawChevron(g, 2, height / 2, false);
        drawChevron(g, width - 3, height / 2, true);

        if (!indeterminate) {
            String text = percent + "%";
            FontMetrics metrics = g.getFontMetrics(getFont());
            int textX = (width - metrics.stringWidth(text)) / 2;
            int textY = (height - metrics.getHeight()) / 2 + metrics.getAscent();
            g.setFont(getFont());
            g.setColor(new Color(0, 0, 0, 185));
            g.drawString(text, textX + 1, textY + 1);
            g.setColor(percent >= 48 ? new Color(37, 23, 7) : LauncherTheme.INK);
            g.drawString(text, textX, textY);
        }
        g.dispose();
    }

    private static void drawChevron(Graphics2D g, int x, int y, boolean right) {
        int direction = right ? -1 : 1;
        Path2D shape = new Path2D.Float();
        shape.moveTo(x, y - 4);
        shape.lineTo(x + direction * 3, y);
        shape.lineTo(x, y + 4);
        g.draw(shape);
    }
}
