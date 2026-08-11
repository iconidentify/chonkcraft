package net.chonkbase.chonkcraft.launcher;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;

/**
 * The small front door to the game.
 *
 * <p>Pack construction and library maintenance live in a separate manager.
 * The launcher itself answers only the two questions a returning player has:
 * which graphics pack is active and whether Play is ready. Game code always
 * follows the authenticated current channel and is never a player choice.
 */
public final class LauncherView extends LauncherTheme.StonePanel {

    public interface Actions {
        void managePacks();
        void releaseNotes();
        void play();
    }

    private final Actions actions;
    private final JLabel packName = LauncherTheme.label("NO PACK SELECTED", 13,
            LauncherTheme.INK);
    private final JButton graphics = LauncherTheme.button("MANAGE PACKS", false);
    private final JLabel gameVersion = LauncherTheme.label("NOT INSTALLED", 15,
            LauncherTheme.INK);
    private final JLabel updateBadge = LauncherTheme.label("UPDATES AUTOMATIC", 10,
            LauncherTheme.GOLD_BRIGHT);
    private final JLabel newNotes = LauncherTheme.label("NEW", 9,
            LauncherTheme.GOLD_BRIGHT);
    private final JButton releaseNotes = LauncherTheme.button("RELEASE NOTES", false);
    private final JButton play = LauncherTheme.button("PLAY", true);
    private final JProgressBar progress = new JProgressBar();

    private PackLibrary.PackInfo selectedPack;
    private GameReleaseManager.Installed currentGame;
    private boolean busy;

    public LauncherView(Actions actions) {
        super(MarbleTexture.Tint.BLACK_STONE);
        this.actions = actions;
        setPreferredSize(new Dimension(620, 660));
        setMinimumSize(new Dimension(580, 640));
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        add(gate(), BorderLayout.CENTER);
        wire();
        setReleaseNotes(ReleaseNotesCatalog.History.empty(), false);
        refreshSummary();
    }

    private JPanel gate() {
        JPanel gate = new LauncherTheme.StonePanel(MarbleTexture.Tint.BLUE_STONE);
        gate.setBorder(BorderFactory.createCompoundBorder(
                new LauncherTheme.StoneBorder(),
                BorderFactory.createEmptyBorder(24, 42, 20, 42)));
        gate.setLayout(new BorderLayout());
        gate.add(standard(), BorderLayout.CENTER);
        gate.add(footer(), BorderLayout.SOUTH);
        return gate;
    }

    private JPanel standard() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        JLabel brand = LauncherTheme.heading("CHONKCRAFT", 30);
        brand.setAlignmentX(CENTER_ALIGNMENT);
        brand.setHorizontalAlignment(SwingConstants.CENTER);
        brand.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                brand.getPreferredSize().height));
        panel.add(brand);
        panel.add(Box.createVerticalStrut(8));

        Crest crest = new Crest();
        crest.setAlignmentX(CENTER_ALIGNMENT);
        panel.add(crest);
        panel.add(Box.createVerticalStrut(8));

        JLabel heading = LauncherTheme.heading("READY TO MARCH", 20);
        heading.setHorizontalAlignment(SwingConstants.CENTER);
        heading.setAlignmentX(CENTER_ALIGNMENT);
        heading.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                heading.getPreferredSize().height));
        panel.add(heading);
        panel.add(Box.createVerticalStrut(7));

        panel.add(Box.createVerticalStrut(14));
        panel.add(rule());
        panel.add(Box.createVerticalStrut(18));

        panel.add(fieldLabel("GRAPHICS PACK"));
        panel.add(Box.createVerticalStrut(7));
        panel.add(graphicsField());
        panel.add(Box.createVerticalStrut(18));

        panel.add(versionField());
        panel.add(Box.createVerticalStrut(22));

        play.setAlignmentX(CENTER_ALIGNMENT);
        play.setMaximumSize(new Dimension(Integer.MAX_VALUE, 66));
        panel.add(play);
        return panel;
    }

    private JPanel graphicsField() {
        JPanel field = new JPanel(new BorderLayout());
        field.setOpaque(true);
        field.setBackground(new Color(18, 27, 39));
        field.setBorder(BorderFactory.createLineBorder(new Color(103, 123, 141)));
        field.setAlignmentX(CENTER_ALIGNMENT);
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));

        packName.setFont(LauncherTheme.BOLD.deriveFont(13f));
        packName.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
        field.add(packName, BorderLayout.CENTER);
        graphics.setPreferredSize(new Dimension(156, 54));
        graphics.setHorizontalAlignment(SwingConstants.CENTER);
        field.add(graphics, BorderLayout.EAST);
        return field;
    }

    private JPanel versionField() {
        JPanel field = new JPanel();
        field.setOpaque(false);
        field.setLayout(new BoxLayout(field, BoxLayout.Y_AXIS));
        field.setAlignmentX(CENTER_ALIGNMENT);
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 88));
        field.add(fieldLabel("GAME CODE"));
        field.add(Box.createVerticalStrut(7));

        JPanel current = new JPanel(new BorderLayout(12, 0));
        current.setOpaque(true);
        current.setBackground(new Color(18, 27, 39));
        current.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(103, 123, 141)),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        gameVersion.setFont(LauncherTheme.BOLD.deriveFont(15f));
        current.add(gameVersion, BorderLayout.WEST);
        updateBadge.setFont(LauncherTheme.BOLD.deriveFont(10f));
        JPanel actions = new JPanel();
        actions.setOpaque(false);
        actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
        updateBadge.setVisible(false);
        actions.add(updateBadge);
        actions.add(Box.createHorizontalStrut(10));
        newNotes.setFont(LauncherTheme.BOLD.deriveFont(Font.BOLD, 9f));
        newNotes.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LauncherTheme.GOLD),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)));
        newNotes.setVisible(false);
        actions.add(newNotes);
        actions.add(Box.createHorizontalStrut(7));
        releaseNotes.setPreferredSize(new Dimension(144, 38));
        actions.add(releaseNotes);
        current.add(actions, BorderLayout.EAST);
        current.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                current.getPreferredSize().height));
        field.add(current);
        return field;
    }

    private JPanel footer() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);

        progress.setIndeterminate(true);
        progress.setVisible(false);
        progress.setPreferredSize(new Dimension(10, 4));
        progress.setBorderPainted(false);
        progress.setForeground(LauncherTheme.GOLD);
        progress.setBackground(new Color(19, 27, 38));
        footer.add(progress, BorderLayout.CENTER);
        return footer;
    }

    private static JLabel fieldLabel(String text) {
        JLabel label = LauncherTheme.label(text, 11, LauncherTheme.GOLD);
        label.setFont(LauncherTheme.BOLD.deriveFont(Font.BOLD, 11f));
        label.setAlignmentX(CENTER_ALIGNMENT);
        label.setHorizontalAlignment(SwingConstants.LEFT);
        label.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                label.getPreferredSize().height));
        return label;
    }

    private static JPanel rule() {
        JPanel rule = new JPanel();
        rule.setOpaque(true);
        rule.setBackground(new Color(103, 117, 123));
        rule.setAlignmentX(CENTER_ALIGNMENT);
        rule.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        rule.setPreferredSize(new Dimension(10, 1));
        return rule;
    }

    private void wire() {
        graphics.addActionListener(event -> actions.managePacks());
        releaseNotes.addActionListener(event -> actions.releaseNotes());
        play.addActionListener(event -> actions.play());
    }

    public void setPacks(List<PackLibrary.PackInfo> items, java.nio.file.Path selected) {
        selectedPack = null;
        if (selected != null) {
            java.nio.file.Path normalized = selected.toAbsolutePath().normalize();
            for (PackLibrary.PackInfo item : items) {
                if (item.file().equals(normalized)) {
                    selectedPack = item;
                    break;
                }
            }
        }
        if (selectedPack == null && !items.isEmpty()) {
            selectedPack = items.getFirst();
        }
        refreshSummary();
    }

    public void setCurrentGame(GameReleaseManager.Installed installed) {
        currentGame = installed;
        refreshSummary();
    }

    public PackLibrary.PackInfo selectedPack() {
        return selectedPack;
    }

    public GameReleaseManager.Installed currentGame() {
        return currentGame;
    }

    boolean playEnabled() {
        return play.isEnabled();
    }

    String graphicsButtonText() {
        return graphics.getText();
    }

    String updateBadgeText() {
        return updateBadge.getText();
    }

    String releaseNotesButtonText() {
        return releaseNotes.getText();
    }

    boolean newReleaseNotesVisible() {
        return newNotes.isVisible();
    }

    public void setReleaseNotes(ReleaseNotesCatalog.History history, boolean unseen) {
        boolean available = history != null && !history.entries().isEmpty();
        releaseNotes.setEnabled(available);
        releaseNotes.setText(unseen && available ? "WHAT'S NEW" : "RELEASE NOTES");
        newNotes.setVisible(unseen && available);
    }

    public void setBusy(boolean value, String message) {
        busy = value;
        progress.setVisible(value);
        graphics.setEnabled(!value);
        refreshSummary();
    }

    public void setStatus(String message) {
        // Routine readiness text is intentionally absent from the compact gate.
    }

    public void setGameStatus(String message) {
        String value = message == null ? "" : message;
        if (value.startsWith("Checking")) {
            updateBadge.setText("CHECKING");
            updateBadge.setVisible(true);
            updateBadge.setForeground(LauncherTheme.MUTED);
            updateBadge.setToolTipText(null);
        } else if (value.startsWith("Downloading") || value.startsWith("Installing")) {
            updateBadge.setText("UPDATING");
            updateBadge.setVisible(true);
            updateBadge.setForeground(LauncherTheme.GOLD_BRIGHT);
            updateBadge.setToolTipText(null);
        } else if (value.startsWith("Update check unavailable")) {
            updateBadge.setText("CHECK UNAVAILABLE");
            updateBadge.setVisible(true);
            updateBadge.setForeground(new Color(214, 161, 88));
            updateBadge.setToolTipText(
                    "The verified installed game is ready; the update service could not be checked.");
        } else {
            updateBadge.setText("");
            updateBadge.setVisible(false);
            updateBadge.setForeground(LauncherTheme.GOLD_BRIGHT);
            updateBadge.setToolTipText(null);
        }
    }

    private void refreshSummary() {
        GameReleaseManager.Installed version = currentGame();
        if (selectedPack == null) {
            packName.setText("NO PACK SELECTED");
            graphics.setText("ADD PACK");
        } else {
            packName.setText(selectedPack.edition().toUpperCase(java.util.Locale.ROOT));
            graphics.setText("MANAGE PACKS");
        }
        if (version == null) {
            gameVersion.setText("NOT INSTALLED");
        } else {
            gameVersion.setText("VERSION " + version.version());
        }
        boolean ready = selectedPack != null && version != null && !busy;
        play.setEnabled(ready);
        play.setText(selectedPack == null ? "GRAPHICS PACK REQUIRED"
                : version == null ? "PREPARING GAME" : "PLAY");
    }

    /** The original ChonkCraft mark used by both the launcher and native app. */
    static final class Crest extends JPanel {

        private static final int SIZE = 208;
        /*
         * Keep the full-resolution master. Swing's Graphics2D already carries
         * the display transform (2x on a Retina screen), so drawing the master
         * into the logical bounds lets the pipeline resample directly to the
         * real device-pixel size. Pre-scaling this to SIZE first made Retina
         * enlarge a 208 px intermediate to about 416 px, visibly stair-stepping
         * the ring and fine armour detail.
         */
        private static final BufferedImage ICON = loadIcon();

        Crest() {
            setOpaque(false);
            setPreferredSize(new Dimension(SIZE, SIZE));
            setMinimumSize(new Dimension(SIZE, SIZE));
            setMaximumSize(new Dimension(SIZE, SIZE));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION,
                    RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            int x = (getWidth() - SIZE) / 2;
            int y = (getHeight() - SIZE) / 2;
            g.drawImage(ICON, x, y, SIZE, SIZE, null);
            g.dispose();
        }

        private static BufferedImage loadIcon() {
            try {
                return ImageIO.read(LauncherView.class.getResource(
                        "/icons/chonkcraft.png"));
            } catch (IOException | IllegalArgumentException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }
}
