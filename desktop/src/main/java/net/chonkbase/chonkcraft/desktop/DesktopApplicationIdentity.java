package net.chonkbase.chonkcraft.desktop;

import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Taskbar;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Keeps the child game process from surfacing Java or "Main" as its identity. */
final class DesktopApplicationIdentity {

    static final String NAME = "ChonkCraft";
    private static final String ICON = "/icons/chonkcraft.png";

    private DesktopApplicationIdentity() {
    }

    /** Must be called before the rendering pipeline initializes AWT. */
    static void initialize() {
        System.setProperty("apple.awt.application.name", NAME);
        System.setProperty("com.apple.mrj.application.apple.menu.about.name", NAME);
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.appearance", "system");
    }

    static void install(JFrame frame) {
        List<Image> icons = icons();
        frame.setIconImages(icons);
        if (!icons.isEmpty() && !GraphicsEnvironment.isHeadless()) {
            try {
                Taskbar taskbar = Taskbar.getTaskbar();
                if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                    taskbar.setIconImage(icons.getLast());
                }
            } catch (UnsupportedOperationException | SecurityException e) {
                // The packaged launcher icon remains available to the host.
            }
        }
        if (Desktop.isDesktopSupported()) {
            try {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.APP_ABOUT)) {
                    desktop.setAboutHandler(event -> SwingUtilities.invokeLater(
                            () -> about(frame)));
                }
            } catch (UnsupportedOperationException | SecurityException e) {
                // About is supplemental and must never prevent play.
            }
        }
    }

    private static void about(JFrame owner) {
        JDialog dialog = new JDialog(owner, "About " + NAME, true);
        dialog.setIconImages(icons());
        dialog.setResizable(false);

        JPanel content = new JPanel(new java.awt.BorderLayout(24, 18));
        content.setBorder(BorderFactory.createEmptyBorder(26, 28, 22, 24));
        content.add(new JLabel(new ImageIcon(scaledIcon(76))),
                java.awt.BorderLayout.WEST);

        JPanel body = new JPanel(new java.awt.BorderLayout(0, 18));
        JPanel copy = new JPanel(new java.awt.BorderLayout(0, 8));
        JLabel name = new JLabel(NAME);
        name.setFont(systemFont(Font.BOLD, 24f));
        JLabel details = new JLabel("<html>Version " + version()
                + "<br><br>ChonkCraft game</html>");
        details.setFont(systemFont(Font.PLAIN, 15f));
        copy.add(name, java.awt.BorderLayout.NORTH);
        copy.add(details, java.awt.BorderLayout.CENTER);
        body.add(copy, java.awt.BorderLayout.CENTER);

        JButton close = new FlatButton("Close");
        close.addActionListener(event -> dialog.dispose());
        JPanel controls = new JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.RIGHT, 0, 0));
        controls.add(close);
        body.add(controls, java.awt.BorderLayout.SOUTH);
        content.add(body, java.awt.BorderLayout.CENTER);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(close);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(Math.max(500, dialog.getWidth()),
                dialog.getHeight()));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    private static String version() {
        String version = System.getProperty("chonkcraft.version", "").trim();
        return version.isEmpty() ? "Development" : version;
    }

    private static List<Image> icons() {
        BufferedImage source = iconImage();
        if (source == null) {
            return List.of();
        }
        return List.of(16, 32, 48, 64, 128, 256, 512).stream()
                .map(size -> source.getScaledInstance(size, size, Image.SCALE_SMOOTH))
                .toList();
    }

    private static Image scaledIcon(int size) {
        BufferedImage source = iconImage();
        return source == null ? new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
                : source.getScaledInstance(size, size, Image.SCALE_SMOOTH);
    }

    private static BufferedImage iconImage() {
        try (InputStream input = DesktopApplicationIdentity.class.getResourceAsStream(ICON)) {
            return input == null ? null : ImageIO.read(input);
        } catch (IOException e) {
            return null;
        }
    }

    private static Font systemFont(int style, float size) {
        Font font = UIManager.getFont("Label.font");
        return (font == null ? new Font(Font.SANS_SERIF, style, Math.round(size)) : font)
                .deriveFont(style, size);
    }

    private static final class FlatButton extends JButton {
        private FlatButton(String text) {
            super(text);
            setFont(systemFont(Font.BOLD, 13f));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setBorder(BorderFactory.createEmptyBorder(9, 18, 9, 18));
            setPreferredSize(new Dimension(104, 38));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            Color gold = getModel().isRollover()
                    ? new Color(215, 164, 65) : new Color(186, 132, 42);
            if (getModel().isPressed()) {
                gold = gold.darker();
            }
            g.setColor(gold);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            g.dispose();
            setForeground(Color.WHITE);
            super.paintComponent(graphics);
        }
    }
}
