package net.chonkbase.chonkcraft.launcher;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Taskbar;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Owns the user-visible application identity on every supported desktop. */
final class ApplicationIdentity {

    static final String NAME = "ChonkCraft";
    static final String VENDOR = "chonkbase.net";
    private static final String ICON = "/icons/chonkcraft.png";

    private ApplicationIdentity() {
    }

    /**
     * Must run before AWT starts. In particular, macOS otherwise derives the
     * application-menu name from the Java entry-point class and displays
     * "Main" to the player.
     */
    static void initialize() {
        System.setProperty("apple.awt.application.name", NAME);
        System.setProperty("com.apple.mrj.application.apple.menu.about.name", NAME);
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.appearance", "system");
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // A missing host theme must not prevent the launcher from opening.
        }
    }

    /** Installs the crest, About action and host-appropriate application menu. */
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
                // jpackage still supplies the native package icon.
            }
        }

        boolean nativeAbout = false;
        if (Desktop.isDesktopSupported()) {
            try {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.APP_ABOUT)) {
                    desktop.setAboutHandler(event -> SwingUtilities.invokeLater(
                            () -> LauncherDialogs.about(frame)));
                    nativeAbout = true;
                }
            } catch (UnsupportedOperationException | SecurityException e) {
                // Fall through to the ordinary Help menu.
            }
        }
        if (!isMac() || !nativeAbout) {
            frame.setJMenuBar(applicationMenu(frame));
        }
    }

    static String version() {
        String configured = System.getProperty("chonkcraft.version", "").trim();
        if (!configured.isEmpty()) {
            return configured;
        }
        String packaged = ApplicationIdentity.class.getPackage().getImplementationVersion();
        return packaged == null || packaged.isBlank() ? "Development" : packaged;
    }

    static List<Image> icons() {
        BufferedImage source = iconImage();
        if (source == null) {
            return List.of();
        }
        return List.of(16, 32, 48, 64, 128, 256, 512).stream()
                .map(size -> source.getScaledInstance(size, size, Image.SCALE_SMOOTH))
                .toList();
    }

    static BufferedImage iconImage() {
        try (InputStream input = ApplicationIdentity.class.getResourceAsStream(ICON)) {
            return input == null ? null : ImageIO.read(input);
        } catch (IOException e) {
            return null;
        }
    }

    static boolean isMac() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("mac");
    }

    private static JMenuBar applicationMenu(JFrame frame) {
        JMenuBar bar = new JMenuBar();
        JMenu help = new JMenu("Help");
        help.setMnemonic('H');
        JMenuItem about = new JMenuItem("About " + NAME);
        about.addActionListener(event -> LauncherDialogs.about(frame));
        help.add(about);
        bar.add(help);
        return bar;
    }
}
