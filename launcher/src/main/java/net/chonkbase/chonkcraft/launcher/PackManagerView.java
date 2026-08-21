package net.chonkbase.chonkcraft.launcher;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.FlowLayout;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.TransferHandler;

/** The library and import workshop behind the launcher's graphics selector. */
public final class PackManagerView extends LauncherTheme.StonePanel {

    public interface Actions {
        void chooseFile();
        void chooseFolder();
        void importSource(Path source);
        void usePack(PackLibrary.PackInfo pack);
        void exportPack(PackLibrary.PackInfo pack);
        void deletePack(PackLibrary.PackInfo pack);
        void openPackFolder();
        void closePackManager();
    }

    private static final DateTimeFormatter BUILT_DATE =
            DateTimeFormatter.ofPattern("MMM d, uuuu, h:mm a")
                    .withLocale(Locale.ENGLISH)
                    .withZone(ZoneId.systemDefault());

    private final Actions actions;
    private final JList<PackLibrary.PackInfo> packs = new JList<>();
    private final JPanel packChoice = new JPanel(new CardLayout());
    private final JTextArea detailName = detailText("NO PACK SELECTED", 18,
            LauncherTheme.GOLD, true, 2);
    private final JTextArea detailVersion = detailText("", 13,
            LauncherTheme.INK, false, 1);
    private final JTextArea detailSource = detailText("", 12,
            LauncherTheme.MUTED, false, 2);
    private final JTextArea detailContents = detailText("", 12,
            LauncherTheme.MUTED, false, 1);
    private final JTextArea detailBuilt = detailText("", 12,
            LauncherTheme.MUTED, false, 1);
    private final JTextArea detailFingerprint = detailText("", 11,
            LauncherTheme.MUTED, false, 1);
    private final JButton chooseFile = LauncherTheme.button("CHOOSE FILE", true);
    private final JButton chooseFolder = LauncherTheme.button("CHOOSE FOLDER", false);
    private final JButton use = LauncherTheme.button("USE SELECTED PACK", true);
    private final JButton export = LauncherTheme.button("EXPORT PACK", false);
    private final JButton delete = LauncherTheme.button("REMOVE", false);
    private final JButton openFolder = LauncherTheme.button(folderActionName(), false);
    private final JButton done = LauncherTheme.button("CLOSE", false);
    private final JLabel status = LauncherTheme.label("", 11, LauncherTheme.MUTED);
    private final JLabel progressAmount = LauncherTheme.label("", 11,
            LauncherTheme.GOLD_BRIGHT);
    private final ForgeProgressBar progress = new ForgeProgressBar();
    private final JPanel activity = new JPanel(new BorderLayout(0, 6));
    private final DropPanel drop;
    private final JPanel guide;

    private Path activePack;
    private boolean busy;

    public PackManagerView(Actions actions) {
        super(MarbleTexture.Tint.BLACK_STONE);
        this.actions = actions;
        drop = new DropPanel();
        guide = formats();
        setPreferredSize(new Dimension(960, 720));
        setMinimumSize(new Dimension(860, 680));
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        add(frame(), BorderLayout.CENTER);
        wire();
        refreshDetails();
    }

    private JPanel frame() {
        JPanel frame = new LauncherTheme.StonePanel(MarbleTexture.Tint.BLUE_STONE);
        frame.setLayout(new BorderLayout());
        frame.setBorder(BorderFactory.createCompoundBorder(
                new LauncherTheme.StoneBorder(),
                BorderFactory.createEmptyBorder(23, 28, 18, 28)));
        frame.add(header(), BorderLayout.NORTH);
        frame.add(body(), BorderLayout.CENTER);
        frame.add(footer(), BorderLayout.SOUTH);
        return frame;
    }

    private JPanel header() {
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 18, 0));
        JPanel words = new JPanel();
        words.setOpaque(false);
        words.setLayout(new BoxLayout(words, BoxLayout.Y_AXIS));
        words.add(LauncherTheme.heading("GRAPHICS PACKS", 27));
        words.add(Box.createVerticalStrut(4));
        words.add(LauncherTheme.label(
                "Choose the artwork and media ChonkCraft uses, or build a new pack.",
                13, LauncherTheme.INK));
        header.add(words, BorderLayout.WEST);
        JPanel headerActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        headerActions.setOpaque(false);
        keepNaturalWidth(openFolder, 42);
        keepNaturalWidth(done, 42);
        headerActions.add(openFolder);
        headerActions.add(done);
        header.add(headerActions, BorderLayout.EAST);
        return header;
    }

    private JPanel body() {
        JPanel body = new JPanel(new GridBagLayout());
        body.setOpaque(false);
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.fill = GridBagConstraints.BOTH;
        constraints.weightx = 0.48;
        constraints.weighty = 1;
        constraints.insets = new Insets(0, 0, 0, 16);
        body.add(library(), constraints);

        constraints.gridx = 1;
        constraints.weightx = 0.52;
        constraints.insets = new Insets(0, 0, 0, 0);
        body.add(importArea(), constraints);
        return body;
    }

    private JPanel library() {
        JPanel library = stoneCard();
        library.setLayout(new BorderLayout(0, 12));
        library.add(sectionTitle("INSTALLED PACKS"), BorderLayout.NORTH);

        packs.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        packs.setCellRenderer(new PackRenderer());
        packs.setFixedCellHeight(78);
        packs.setBackground(new Color(12, 19, 28));
        packs.setForeground(LauncherTheme.INK);
        packs.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JScrollPane scroll = new JScrollPane(packs);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(83, 101, 117)));
        scroll.getViewport().setBackground(new Color(12, 19, 28));

        JPanel empty = new JPanel(new BorderLayout());
        empty.setOpaque(true);
        empty.setBackground(new Color(12, 19, 28));
        JLabel message = LauncherTheme.label(
                "No ChonkPacks yet. Import your Warcraft II media to begin.",
                13, LauncherTheme.MUTED);
        message.setHorizontalAlignment(SwingConstants.CENTER);
        message.setBorder(BorderFactory.createEmptyBorder(25, 24, 25, 24));
        empty.add(message, BorderLayout.CENTER);

        packChoice.setOpaque(false);
        packChoice.add(empty, "empty");
        packChoice.add(scroll, "packs");
        library.add(packChoice, BorderLayout.CENTER);
        library.add(details(), BorderLayout.SOUTH);
        return library;
    }

    private JPanel details() {
        JPanel details = new JPanel();
        details.setOpaque(false);
        details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
        details.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(83, 101, 117)),
                BorderFactory.createEmptyBorder(13, 2, 0, 2)));
        details.add(detailName);
        details.add(Box.createVerticalStrut(6));
        details.add(detailVersion);
        details.add(Box.createVerticalStrut(4));
        details.add(detailSource);
        details.add(Box.createVerticalStrut(4));
        details.add(detailContents);
        details.add(Box.createVerticalStrut(4));
        details.add(detailBuilt);
        details.add(Box.createVerticalStrut(4));
        details.add(detailFingerprint);
        return details;
    }

    private JPanel importArea() {
        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        drop.setAlignmentX(LEFT_ALIGNMENT);
        drop.setMinimumSize(new Dimension(360, 230));
        drop.setPreferredSize(new Dimension(420, 232));
        drop.setMaximumSize(new Dimension(Integer.MAX_VALUE, 235));
        column.add(drop);
        column.add(Box.createVerticalStrut(14));
        guide.setAlignmentX(LEFT_ALIGNMENT);
        guide.setMinimumSize(new Dimension(360, 205));
        guide.setPreferredSize(new Dimension(420, 210));
        guide.setMaximumSize(new Dimension(Integer.MAX_VALUE, 215));
        column.add(guide);
        return column;
    }

    private JPanel formats() {
        JPanel card = stoneCard();
        card.setLayout(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.gridwidth = 2;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1;
        constraints.anchor = GridBagConstraints.WEST;
        card.add(sectionTitle("SUPPORTED ORIGINAL MEDIA"), constraints);

        constraints.gridy = 1;
        constraints.gridwidth = 2;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(5, 0, 0, 0);
        card.add(formatGroup("CD / FOLDER",
                "Mounted CD or installed game folder."), constraints);
        constraints.gridy = 2;
        card.add(formatGroup("DISC IMAGE",
                "ISO, Toast, BIN/CUE, IMG/CCD."), constraints);

        constraints.gridy = 3;
        card.add(formatGroup("ARCHIVES",
                "ZIP, StuffIt, 7z, RAR, TAR/GZ, DMG."), constraints);
        constraints.gridy = 4;
        card.add(formatGroup("EDITIONS",
                "DOS, Mac and Battle.net."),
                constraints);

        return card;
    }

    private JPanel footer() {
        JPanel footer = new JPanel(new BorderLayout(0, 10));
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(18, 0, 0, 0));

        JPanel actionsPanel = new JPanel(new GridBagLayout());
        actionsPanel.setOpaque(false);
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridy = 0;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(0, 0, 0, 8);
        constraints.gridx = 0;
        constraints.weightx = 1;
        actionsPanel.add(use, constraints);
        constraints.gridx = 1;
        constraints.weightx = 0;
        actionsPanel.add(export, constraints);
        constraints.gridx = 2;
        actionsPanel.add(delete, constraints);
        footer.add(actionsPanel, BorderLayout.NORTH);

        activity.setOpaque(false);
        activity.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(
                        1, 0, 0, 0, new Color(83, 101, 117)),
                BorderFactory.createEmptyBorder(9, 0, 0, 0)));
        JPanel legend = new JPanel(new BorderLayout(12, 0));
        legend.setOpaque(false);
        legend.add(status, BorderLayout.WEST);
        progressAmount.setHorizontalAlignment(SwingConstants.RIGHT);
        legend.add(progressAmount, BorderLayout.EAST);
        activity.add(legend, BorderLayout.NORTH);
        progress.setVisible(false);
        activity.add(progress, BorderLayout.CENTER);
        activity.setVisible(false);
        footer.add(activity, BorderLayout.SOUTH);
        return footer;
    }

    private static JPanel stoneCard() {
        JPanel card = new LauncherTheme.StonePanel(MarbleTexture.Tint.BLACK_STONE);
        card.setBorder(BorderFactory.createCompoundBorder(
                new LauncherTheme.StoneBorder(),
                BorderFactory.createEmptyBorder(17, 18, 17, 18)));
        return card;
    }

    /**
     * Keeps the actual font metrics authoritative. A fixed width here used to
     * turn labels into ellipses as soon as the packaged Droid Serif replaced
     * the developer machine's narrower fallback font.
     */
    private static void keepNaturalWidth(JButton button, int height) {
        Dimension natural = button.getPreferredSize();
        button.setPreferredSize(new Dimension(natural.width, height));
        button.setMinimumSize(new Dimension(natural.width, height));
    }

    private static JLabel sectionTitle(String text) {
        JLabel title = LauncherTheme.label(text, 12, LauncherTheme.GOLD);
        title.setFont(LauncherTheme.BOLD.deriveFont(12f));
        title.setHorizontalAlignment(SwingConstants.LEFT);
        title.setAlignmentX(LEFT_ALIGNMENT);
        title.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                title.getPreferredSize().height));
        return title;
    }

    private static JPanel formatGroup(String title, String explanation) {
        JPanel group = new JPanel(new BorderLayout(10, 0));
        group.setOpaque(false);
        group.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(71, 88, 105)),
                BorderFactory.createEmptyBorder(5, 9, 5, 9)));
        group.setMinimumSize(new Dimension(240, 29));
        group.setPreferredSize(new Dimension(360, 29));
        JLabel heading = LauncherTheme.label(title, 11, LauncherTheme.GOLD);
        heading.setFont(LauncherTheme.BOLD.deriveFont(11f));
        heading.setHorizontalAlignment(SwingConstants.LEFT);
        group.add(heading, BorderLayout.WEST);
        JTextArea words = copy(explanation, 11, LauncherTheme.INK);
        words.setRows(1);
        group.add(words, BorderLayout.CENTER);
        return group;
    }

    private static JTextArea copy(String text, int size, Color colour) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(LauncherTheme.REGULAR.deriveFont((float) size));
        area.setForeground(colour);
        area.setBorder(null);
        return area;
    }

    private static JTextArea detailText(String text, int size, Color colour,
            boolean bold, int rows) {
        JTextArea area = copy(text, size, colour);
        area.setFont((bold ? LauncherTheme.BOLD : LauncherTheme.REGULAR)
                .deriveFont((float) size));
        area.setRows(rows);
        area.setColumns(1);
        area.setAlignmentX(LEFT_ALIGNMENT);
        area.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                area.getPreferredSize().height));
        return area;
    }

    private void wire() {
        chooseFile.addActionListener(event -> actions.chooseFile());
        chooseFolder.addActionListener(event -> actions.chooseFolder());
        use.addActionListener(event -> actions.usePack(selectedPack()));
        export.addActionListener(event -> actions.exportPack(selectedPack()));
        delete.addActionListener(event -> actions.deletePack(selectedPack()));
        openFolder.addActionListener(event -> actions.openPackFolder());
        done.addActionListener(event -> actions.closePackManager());
        packs.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                refreshDetails();
            }
        });
        packs.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() == 2 && selectedPack() != null && !busy) {
                    actions.usePack(selectedPack());
                }
            }
        });
    }

    public void setPacks(List<PackLibrary.PackInfo> items, Path active) {
        PackLibrary.PackInfo previous = selectedPack();
        activePack = active == null ? null : active.toAbsolutePath().normalize();
        DefaultListModel<PackLibrary.PackInfo> model = new DefaultListModel<>();
        items.forEach(model::addElement);
        packs.setModel(model);

        PackLibrary.PackInfo selection = null;
        if (previous != null) {
            selection = find(items, previous.file());
        }
        if (selection == null && activePack != null) {
            selection = find(items, activePack);
        }
        if (selection == null && !items.isEmpty()) {
            selection = items.getFirst();
        }
        packs.setSelectedValue(selection, true);
        ((CardLayout) packChoice.getLayout()).show(packChoice,
                items.isEmpty() ? "empty" : "packs");
        refreshDetails();
    }

    public PackLibrary.PackInfo selectedPack() {
        return packs.getSelectedValue();
    }

    public void setBusy(boolean value, String message) {
        busy = value;
        status.setText(message == null || message.isBlank() ? "Ready" : message);
        progress.setVisible(value);
        activity.setVisible(value);
        guide.setVisible(!value);
        progressAmount.setText(value ? "WORKING" : "");
        if (value) {
            progress.begin();
        } else {
            progress.rest();
        }
        packs.setEnabled(!value);
        chooseFile.setEnabled(!value);
        chooseFolder.setEnabled(!value);
        done.setEnabled(!value);
        openFolder.setEnabled(!value);
        refreshButtons();
    }

    public void setStatus(String message) {
        boolean visible = message != null && !message.isBlank()
                && !"Ready".equalsIgnoreCase(message);
        status.setText(visible ? message : "");
        activity.setVisible(visible || busy);
    }

    /** Shows a measured import update in the manager that initiated it. */
    public void setProgress(SourceImporter.ProgressUpdate update) {
        if (update == null) {
            return;
        }
        busy = true;
        status.setText(update.message());
        progress.setVisible(true);
        activity.setVisible(true);
        guide.setVisible(false);
        if (update.measured()) {
            progress.measured(update.percent());
            progressAmount.setText(progressText(update));
        } else {
            progress.begin();
            progressAmount.setText("WORKING");
        }
    }

    boolean useEnabled() {
        return use.isEnabled();
    }

    int progressPercent() {
        return progress.percent();
    }

    String progressAmountText() {
        return progressAmount.getText();
    }

    String formatGuideText() {
        return "ISO Toast BIN/CUE IMG/CCD ZIP StuffIt 7z RAR TAR/GZ DMG "
                + "Battle.net Edition";
    }

    void requestOpenPackFolder() {
        openFolder.doClick();
    }

    private void refreshDetails() {
        PackLibrary.PackInfo pack = selectedPack();
        if (pack == null) {
            detailName.setText("NO PACK SELECTED");
            detailVersion.setText("Import Warcraft II media to create one.");
            detailSource.setText(" ");
            detailContents.setText(" ");
            detailBuilt.setText(" ");
            detailFingerprint.setText(" ");
            refreshButtons();
            return;
        }
        boolean active = activePack != null && activePack.equals(pack.file());
        detailName.setText(active ? pack.name() + "  |  IN USE" : pack.name());
        detailVersion.setText("Source release: " + pack.versionDetail());

        String format = present(pack.sourceFormat(), "Warcraft II media");
        String original = present(pack.sourceOriginalName(), "");
        String originalSize = pack.sourceOriginalBytes() > 0
                ? "  |  " + humanBytes(pack.sourceOriginalBytes())
                : "";
        detailSource.setText(original.isBlank() ? "Source: " + format
                : "Source: " + original + "  |  " + format + originalSize);

        String contents = String.format("%,d assets  |  %,d maps  |  %,d music tracks  |  %s",
                pack.assets(), pack.maps(), pack.musicTracks(), humanBytes(pack.storedBytes()));
        detailContents.setText(contents);
        detailBuilt.setText(pack.builtAt() == null || pack.builtAt().isBlank()
                ? "Build date was not recorded by this pack."
                : "Built " + builtDate(pack.builtAt()));
        detailFingerprint.setText(pack.fingerprint().isBlank()
                ? "Source checksum was not recorded by this pack."
                : "Source ID: " + shortFingerprint(pack.fingerprint()));
        refreshButtons();
    }

    private void refreshButtons() {
        boolean selected = selectedPack() != null && !busy;
        use.setEnabled(selected);
        export.setEnabled(selected);
        delete.setEnabled(selected);
    }

    private static PackLibrary.PackInfo find(List<PackLibrary.PackInfo> items, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        for (PackLibrary.PackInfo item : items) {
            if (item.file().equals(normalized)) {
                return item;
            }
        }
        return null;
    }

    private static String present(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String builtDate(String value) {
        try {
            return BUILT_DATE.format(Instant.parse(value));
        } catch (RuntimeException e) {
            return value;
        }
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " bytes";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.ENGLISH, "%.1f %s", value, units[unit]);
    }

    private static String shortFingerprint(String fingerprint) {
        return fingerprint.length() <= 16 ? fingerprint
                : fingerprint.substring(0, 16) + "…";
    }

    private static String progressText(SourceImporter.ProgressUpdate update) {
        if (!update.measured()) {
            return update.percent() + "%";
        }
        if (update.unit() == SourceImporter.Unit.BYTES) {
            return humanBytes(update.completed()) + " / " + humanBytes(update.total());
        }
        String noun = switch (update.unit()) {
            case ASSETS -> "assets";
            case CHECKS -> "checks";
            case TRACKS -> "tracks";
            default -> "items";
        };
        return String.format(Locale.ENGLISH, "%,d / %,d %s",
                update.completed(), update.total(), noun);
    }

    private static String folderActionName() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("mac")
                        ? "OPEN IN FINDER" : "OPEN PACKS FOLDER";
    }

    private final class DropPanel extends LauncherTheme.StonePanel {

        private boolean accepting;

        private DropPanel() {
            super(MarbleTexture.Tint.BLACK_STONE);
            setLayout(new GridBagLayout());
            setBorder(BorderFactory.createCompoundBorder(
                    new LauncherTheme.StoneBorder(),
                    BorderFactory.createEmptyBorder(10, 28, 10, 28)));
            GridBagConstraints constraints = new GridBagConstraints();
            constraints.gridx = 0;
            constraints.gridy = 0;
            constraints.anchor = GridBagConstraints.CENTER;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            constraints.weightx = 1;

            JLabel mark = LauncherTheme.heading("+", 30);
            mark.setHorizontalAlignment(SwingConstants.CENTER);
            add(mark, constraints);
            constraints.gridy++;
            constraints.insets = new Insets(3, 0, 0, 0);
            JLabel title = LauncherTheme.heading("ADD A GRAPHICS PACK", 17);
            title.setHorizontalAlignment(SwingConstants.CENTER);
            add(title, constraints);
            constraints.gridy++;
            constraints.insets = new Insets(4, 12, 0, 12);
            JTextArea explanation = copy(
                    "Drop original game media or an existing .chonkpack here.",
                    13, LauncherTheme.INK);
            explanation.setRows(2);
            add(explanation, constraints);
            constraints.gridy++;
            constraints.insets = new Insets(8, 12, 0, 12);
            JPanel choices = new JPanel(new GridBagLayout());
            choices.setOpaque(false);
            choices.setMinimumSize(new Dimension(300, 86));
            choices.setPreferredSize(new Dimension(380, 86));
            chooseFile.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            chooseFolder.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            keepNaturalWidth(chooseFile, 39);
            keepNaturalWidth(chooseFolder, 39);
            GridBagConstraints choice = new GridBagConstraints();
            choice.gridx = 0;
            choice.gridy = 0;
            choice.fill = GridBagConstraints.HORIZONTAL;
            choice.weightx = 1;
            choice.insets = new Insets(0, 0, 8, 0);
            choices.add(chooseFile, choice);
            choice.gridy = 1;
            choice.insets = new Insets(0, 0, 0, 0);
            choices.add(chooseFolder, choice);
            add(choices, constraints);

            setTransferHandler(new TransferHandler() {
                @Override
                public boolean canImport(TransferSupport support) {
                    accepting = supported(support);
                    repaint();
                    return accepting;
                }

                @Override
                public boolean importData(TransferSupport support) {
                    accepting = false;
                    repaint();
                    if (!supported(support)) {
                        return false;
                    }
                    try {
                        @SuppressWarnings("unchecked")
                        List<File> files = (List<File>) support.getTransferable()
                                .getTransferData(DataFlavor.javaFileListFlavor);
                        Path selected = preferred(files);
                        if (selected == null) {
                            return false;
                        }
                        actions.importSource(selected);
                        return true;
                    } catch (Exception e) {
                        setStatus("That item could not be opened.");
                        return false;
                    }
                }

                private boolean supported(TransferSupport support) {
                    return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                            && !busy;
                }
            });
        }

        @Override
        protected void paintComponent(java.awt.Graphics graphics) {
            super.paintComponent(graphics);
            if (!accepting) {
                return;
            }
            java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
            g.setColor(new Color(255, 222, 132, 80));
            g.fillRect(5, 5, getWidth() - 10, getHeight() - 10);
            g.setColor(LauncherTheme.GOLD_BRIGHT);
            g.setStroke(new java.awt.BasicStroke(2f));
            g.drawRect(6, 6, getWidth() - 13, getHeight() - 13);
            g.dispose();
        }

        private Path preferred(List<File> files) {
            for (String suffix : List.of(".cue", ".ccd")) {
                for (File file : files) {
                    if (file.getName().toLowerCase(Locale.ROOT).endsWith(suffix)) {
                        return file.toPath();
                    }
                }
            }
            return files.isEmpty() ? null : files.getFirst().toPath();
        }
    }

    private final class PackRenderer
            implements ListCellRenderer<PackLibrary.PackInfo> {

        @Override
        public Component getListCellRendererComponent(
                JList<? extends PackLibrary.PackInfo> list,
                PackLibrary.PackInfo value, int index,
                boolean selected, boolean focused) {
            boolean active = activePack != null && activePack.equals(value.file());
            String details = (active ? "IN USE  |  " : "")
                    + value.versionDetail() + "  |  "
                    + String.format("%,d assets", value.assets());
            String foreground = selected ? "#FFDE84" : "#E7E8E1";
            String muted = "#A6B1B9";
            JLabel cell = new JLabel("<html><b><font color='" + foreground + "'>"
                    + html(value.name()) + "</font></b><br><font color='" + muted
                    + "' size='-1'>" + html(details) + "</font></html>");
            cell.setOpaque(true);
            cell.setBackground(selected ? new Color(41, 59, 80) : new Color(23, 34, 48));
            cell.setFont(LauncherTheme.REGULAR.deriveFont(13f));
            cell.setVerticalAlignment(SwingConstants.CENTER);
            cell.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(selected
                            ? LauncherTheme.GOLD
                            : new Color(64, 82, 100), selected ? 2 : 1),
                    BorderFactory.createEmptyBorder(9, 11, 9, 11)));
            return cell;
        }

        private String html(String text) {
            return text.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;");
        }
    }
}
