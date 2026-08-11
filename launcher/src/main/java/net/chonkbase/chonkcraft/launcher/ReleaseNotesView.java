package net.chonkbase.chonkcraft.launcher;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;

/** Themed, offline-capable history of authenticated game updates. */
public final class ReleaseNotesView extends LauncherTheme.StonePanel {

    public interface Actions {
        void closeReleaseNotes();
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter
            .ofPattern("MMMM d, uuuu").withZone(ZoneOffset.UTC);
    private final JPanel timeline = new JPanel();
    private final JScrollPane scroll;

    public ReleaseNotesView(Actions actions) {
        super(MarbleTexture.Tint.BLUE_STONE);
        setPreferredSize(new Dimension(760, 620));
        setMinimumSize(getPreferredSize());
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createCompoundBorder(
                new LauncherTheme.StoneBorder(),
                BorderFactory.createEmptyBorder(28, 34, 28, 34)));

        JPanel header = new JPanel(new BorderLayout(20, 0));
        header.setOpaque(false);
        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.add(LauncherTheme.heading("RELEASE NOTES", 27));
        titles.add(Box.createVerticalStrut(5));
        titles.add(LauncherTheme.label(
                "What changed in each authenticated ChonkCraft update.",
                12, LauncherTheme.INK));
        header.add(titles, BorderLayout.CENTER);
        JButton close = LauncherTheme.button("CLOSE", false);
        close.setPreferredSize(new Dimension(112, 46));
        close.addActionListener(event -> actions.closeReleaseNotes());
        header.add(close, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        timeline.setOpaque(false);
        timeline.setLayout(new BoxLayout(timeline, BoxLayout.Y_AXIS));
        scroll = new JScrollPane(timeline,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(BorderFactory.createEmptyBorder(24, 0, 0, 0));
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        add(scroll, BorderLayout.CENTER);
        setHistory(ReleaseNotesCatalog.History.empty());
    }

    public void setHistory(ReleaseNotesCatalog.History history) {
        timeline.removeAll();
        if (history.entries().isEmpty()) {
            JPanel empty = card(false);
            empty.setLayout(new BorderLayout());
            JLabel message = LauncherTheme.label(
                    "Release history will appear after the first authenticated update.",
                    13, LauncherTheme.MUTED);
            message.setHorizontalAlignment(JLabel.CENTER);
            empty.add(message, BorderLayout.CENTER);
            empty.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
            timeline.add(empty);
        } else {
            for (int index = 0; index < history.entries().size(); index++) {
                timeline.add(entry(history.entries().get(index), index == 0));
                if (index + 1 < history.entries().size()) {
                    timeline.add(Box.createVerticalStrut(12));
                }
            }
        }
        timeline.revalidate();
        timeline.repaint();
        showLatest();
    }

    /**
     * Returns the history to its newest entry, including after Swing completes
     * a deferred viewport layout. A newly shown scrollable history can otherwise
     * finish that layout at the bottom and open on the oldest release.
     */
    void showLatest() {
        resetScrollPosition();
        SwingUtilities.invokeLater(this::resetScrollPosition);
    }

    private void resetScrollPosition() {
        scroll.getViewport().setViewPosition(new Point(0, 0));
        scroll.getVerticalScrollBar().setValue(
                scroll.getVerticalScrollBar().getMinimum());
    }

    int entryCount() {
        return (int) java.util.Arrays.stream(timeline.getComponents())
                .filter(component -> component instanceof JPanel).count();
    }

    private static JPanel entry(ReleaseNotesCatalog.Entry entry, boolean latest) {
        JPanel card = card(latest);
        card.setLayout(new BorderLayout(0, 12));

        JPanel metadata = new JPanel(new BorderLayout(12, 0));
        metadata.setOpaque(false);
        JLabel version = LauncherTheme.heading("VERSION " + entry.version(), 14);
        metadata.add(version, BorderLayout.WEST);
        String right = latest ? "LATEST  ·  " + date(entry.published())
                : date(entry.published());
        JLabel date = LauncherTheme.label(right, 10,
                latest ? LauncherTheme.GOLD_BRIGHT : LauncherTheme.MUTED);
        date.setFont(LauncherTheme.BOLD.deriveFont(Font.BOLD, 10f));
        metadata.add(date, BorderLayout.EAST);
        metadata.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                metadata.getPreferredSize().height));
        card.add(metadata, BorderLayout.NORTH);

        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        JLabel title = LauncherTheme.label(entry.title(), 16, LauncherTheme.INK);
        title.setFont(LauncherTheme.BOLD.deriveFont(Font.BOLD, 16f));
        title.setAlignmentX(LEFT_ALIGNMENT);
        title.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                title.getPreferredSize().height));
        copy.add(title);
        copy.add(Box.createVerticalStrut(8));

        String visibleBody = entry.body().lines()
                .map(line -> line.startsWith("- ") ? "•  " + line.substring(2) : line)
                .collect(java.util.stream.Collectors.joining("\n"));
        JTextArea body = new JTextArea(visibleBody);
        body.setEditable(false);
        body.setFocusable(false);
        body.setLineWrap(true);
        body.setWrapStyleWord(true);
        body.setOpaque(false);
        body.setForeground(LauncherTheme.MUTED);
        body.setFont(LauncherTheme.REGULAR.deriveFont(12f));
        body.setBorder(null);
        body.setAlignmentX(LEFT_ALIGNMENT);
        body.setRows(Math.max(1, visibleBody.split("\\R", -1).length));
        body.setColumns(58);
        body.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                Math.max(42, body.getPreferredSize().height)));
        copy.add(body);
        card.add(copy, BorderLayout.CENTER);
        int lines = Math.max(1, visibleBody.split("\\R", -1).length);
        int height = 94 + lines * 18;
        card.setPreferredSize(new Dimension(620, height));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                height));
        return card;
    }

    private static JPanel card(boolean latest) {
        JPanel card = new JPanel();
        card.setOpaque(true);
        card.setBackground(latest ? new Color(29, 43, 61) : new Color(15, 22, 31));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(latest
                        ? LauncherTheme.GOLD : new Color(74, 91, 105)),
                BorderFactory.createEmptyBorder(18, 20, 18, 20)));
        card.setAlignmentX(LEFT_ALIGNMENT);
        return card;
    }

    private static String date(String value) {
        try {
            return DATE.format(Instant.parse(value)).toUpperCase(java.util.Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }
}
