package net.chonkbase.chonkcraft.launcher;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;

/**
 * One branded dialog policy for the entire launcher.
 *
 * <p>The host owns the title bar and modality, while ChonkCraft owns the
 * content and buttons. This avoids Java's dated Aqua/Metal dialog furniture
 * without faking macOS controls on Windows or Linux.
 */
final class LauncherDialogs {

    private static final int DIALOG_ICON_SIZE = 76;
    private static final Color PRIMARY = new Color(186, 132, 42);
    private static final Color PRIMARY_HOVER = new Color(215, 164, 65);
    private static final Color DESTRUCTIVE = new Color(177, 57, 49);
    private static final Color DESTRUCTIVE_HOVER = new Color(202, 69, 59);

    private LauncherDialogs() {
    }

    static boolean confirmRemoval(Component owner, String packName) {
        return confirm(owner, "Remove Graphics Pack",
                "Remove “" + packName + "” from this computer?\n\n"
                        + "The managed graphics pack will be deleted.\n"
                        + "Your original game media will not be changed.",
                "Remove Pack", ButtonKind.DESTRUCTIVE);
    }

    static boolean confirmReplace(Component owner, String fileName) {
        return confirm(owner, "Replace Existing File?",
                fileName + " already exists.\n\n"
                        + "Replace it with the exported graphics pack?",
                "Replace", ButtonKind.PRIMARY);
    }

    static void error(Component owner, String detail) {
        showOnEventThread(() -> {
            JDialog dialog = create(owner, ApplicationIdentity.NAME);
            DialogButton close = new DialogButton("Close", ButtonKind.PRIMARY);
            close.addActionListener(event -> dialog.dispose());
            dialog.setContentPane(content(detail, List.of(close)));
            show(dialog, close);
        });
    }

    static void about(Component owner) {
        showOnEventThread(() -> {
            JDialog dialog = create(owner, "About " + ApplicationIdentity.NAME);
            JPanel copy = new JPanel(new BorderLayout(0, 9));
            copy.setOpaque(false);
            JLabel name = new JLabel(ApplicationIdentity.NAME);
            name.setFont(systemFont(Font.BOLD, 24f));
            name.setForeground(foreground());
            JTextArea details = text("Version " + ApplicationIdentity.version()
                    + "\n\nA faithful desktop real-time strategy game."
                    + "\nAutomatic game-code updates are authenticated."
                    + "\n\n© 2026 " + ApplicationIdentity.VENDOR);
            copy.add(name, BorderLayout.NORTH);
            copy.add(details, BorderLayout.CENTER);

            DialogButton close = new DialogButton("Close", ButtonKind.PRIMARY);
            close.addActionListener(event -> dialog.dispose());
            dialog.setContentPane(content(copy, List.of(close)));
            show(dialog, close);
        });
    }

    /** Platform order with Cancel as the safe keyboard default everywhere. */
    static List<String> confirmationOptions(String action) {
        return ApplicationIdentity.isMac()
                ? List.of("Cancel", action)
                : List.of(action, "Cancel");
    }

    private static boolean confirm(Component owner, String title,
            String message, String action, ButtonKind kind) {
        AtomicBoolean accepted = new AtomicBoolean();
        showOnEventThread(() -> {
            JDialog dialog = create(owner, title);
            List<String> labels = confirmationOptions(action);
            DialogButton cancel = new DialogButton("Cancel", ButtonKind.SECONDARY);
            cancel.addActionListener(event -> dialog.dispose());
            DialogButton proceed = new DialogButton(action, kind);
            proceed.addActionListener(event -> {
                accepted.set(true);
                dialog.dispose();
            });
            List<DialogButton> buttons = labels.getFirst().equals("Cancel")
                    ? List.of(cancel, proceed) : List.of(proceed, cancel);
            dialog.setContentPane(content(message, buttons));
            show(dialog, cancel);
        });
        return accepted.get();
    }

    private static JDialog create(Component owner, String title) {
        Window window = owner instanceof Window direct
                ? direct : owner == null ? null : SwingUtilities.getWindowAncestor(owner);
        Dialog.ModalityType modality = window == null
                ? Dialog.ModalityType.APPLICATION_MODAL
                : Dialog.ModalityType.DOCUMENT_MODAL;
        JDialog dialog = new JDialog(window, title, modality);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setResizable(false);
        dialog.setIconImages(ApplicationIdentity.icons());
        dialog.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        dialog.getRootPane().getActionMap().put("close", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                dialog.dispose();
            }
        });
        return dialog;
    }

    private static JPanel content(String message, List<? extends JButton> buttons) {
        return content(text(message), buttons);
    }

    private static JPanel content(Component copy, List<? extends JButton> buttons) {
        JPanel root = new JPanel(new BorderLayout(24, 18));
        root.setBackground(background());
        root.setBorder(BorderFactory.createEmptyBorder(26, 28, 22, 24));
        JLabel icon = new JLabel(brandedIcon());
        icon.setVerticalAlignment(JLabel.TOP);
        root.add(icon, BorderLayout.WEST);

        JPanel body = new JPanel(new BorderLayout(0, 22));
        body.setOpaque(false);
        body.add(copy, BorderLayout.CENTER);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        controls.setOpaque(false);
        buttons.forEach(controls::add);
        body.add(controls, BorderLayout.SOUTH);
        root.add(body, BorderLayout.CENTER);
        return root;
    }

    private static JTextArea text(String value) {
        JTextArea copy = new JTextArea(value);
        copy.setEditable(false);
        copy.setFocusable(false);
        copy.setOpaque(false);
        copy.setLineWrap(true);
        copy.setWrapStyleWord(true);
        copy.setColumns(42);
        copy.setRows(Math.max(2, value.split("\\R", -1).length));
        copy.setFont(systemFont(Font.PLAIN, 15f));
        copy.setForeground(foreground());
        copy.setBorder(null);
        return copy;
    }

    private static ImageIcon brandedIcon() {
        BufferedImage source = ApplicationIdentity.iconImage();
        if (source == null) {
            return new ImageIcon();
        }
        Image scaled = source.getScaledInstance(DIALOG_ICON_SIZE, DIALOG_ICON_SIZE,
                Image.SCALE_SMOOTH);
        return new ImageIcon(scaled, ApplicationIdentity.NAME);
    }

    private static void show(JDialog dialog, JButton safeDefault) {
        dialog.getRootPane().setDefaultButton(safeDefault);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(Math.max(560, dialog.getWidth()),
                dialog.getHeight()));
        dialog.setLocationRelativeTo(dialog.getOwner());
        dialog.setVisible(true);
    }

    private static void showOnEventThread(Runnable dialog) {
        if (SwingUtilities.isEventDispatchThread()) {
            dialog.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(dialog);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("the application dialog could not open",
                    e.getCause());
        }
    }

    private static Font systemFont(int style, float size) {
        Font font = UIManager.getFont("Label.font");
        if (font == null) {
            font = new Font(Font.SANS_SERIF, style, Math.round(size));
        }
        return font.deriveFont(style, size);
    }

    private static Color background() {
        Color color = UIManager.getColor("Panel.background");
        return color == null ? new Color(244, 244, 246) : color;
    }

    private static Color foreground() {
        Color color = UIManager.getColor("Label.foreground");
        return color == null ? new Color(35, 35, 38) : color;
    }

    private enum ButtonKind {
        PRIMARY,
        DESTRUCTIVE,
        SECONDARY
    }

    /** A flat control that does not inherit Java's legacy Aqua/Metal chrome. */
    private static final class DialogButton extends JButton {

        private final ButtonKind kind;

        private DialogButton(String text, ButtonKind kind) {
            super(text);
            this.kind = kind;
            setFont(systemFont(Font.BOLD, 13f));
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(9, 18, 9, 18));
            setPreferredSize(new Dimension(Math.max(104, getPreferredSize().width), 38));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = switch (kind) {
                case PRIMARY -> getModel().isRollover() ? PRIMARY_HOVER : PRIMARY;
                case DESTRUCTIVE -> getModel().isRollover()
                        ? DESTRUCTIVE_HOVER : DESTRUCTIVE;
                case SECONDARY -> secondaryFill();
            };
            if (getModel().isPressed()) {
                fill = fill.darker();
            }
            g.setColor(fill);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            g.setColor(kind == ButtonKind.SECONDARY
                    ? new Color(120, 120, 125, 120) : fill.brighter());
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            g.dispose();
            setForeground(kind == ButtonKind.SECONDARY ? foreground() : Color.WHITE);
            super.paintComponent(graphics);
        }

        private Color secondaryFill() {
            Color base = background();
            int adjustment = getModel().isRollover() ? -18 : -8;
            return new Color(clamp(base.getRed() + adjustment),
                    clamp(base.getGreen() + adjustment),
                    clamp(base.getBlue() + adjustment));
        }

        private static int clamp(int value) {
            return Math.max(0, Math.min(255, value));
        }
    }
}
