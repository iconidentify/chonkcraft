package net.chonkbase.chonkcraft.launcher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.awt.Dimension;
import java.awt.Insets;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JLabel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The pack manager keeps import available while guarding selection actions. */
class PackManagerViewTest {

    @Test
    @DisplayName("pack actions stay closed until a pack is selected")
    void packActionsRequireASelection() {
        PackManagerView view = new PackManagerView(new Actions());
        view.setPacks(List.of(), null);
        assertFalse(view.useEnabled(), "an empty library offered to use a pack");

        PackLibrary.PackInfo pack = new PackLibrary.PackInfo(
                Path.of("/packs/bne.chonkpack"), "wc2-battle-net-edition",
                "Warcraft II: Battle.net Edition", "retail media",
                true, true, 1566);
        view.setPacks(List.of(pack), pack.file());
        assertTrue(view.useEnabled(), "the installed pack could not be selected");
    }

    @Test
    @DisplayName("the import guide names every player-facing source family")
    void importGuideNamesTheSupportedSources() {
        PackManagerView view = new PackManagerView(new Actions());
        String guide = view.formatGuideText();

        for (String name : List.of(
                "ISO", "Toast", "BIN/CUE", "IMG/CCD", "ZIP", "StuffIt",
                "Battle.net Edition")) {
            assertTrue(guide.contains(name),
                    "the pack manager did not name supported source " + name);
        }
        assertFalse(guide.contains("War2Combat"),
                "the pack manager still advertised an unsupported derivative");
    }

    @Test
    @DisplayName("measured import work reaches the visible pack-manager meter")
    void measuredProgressIsVisible() {
        PackManagerView view = new PackManagerView(new Actions());
        view.setBusy(true, "Preparing Japanese disc");
        view.setProgress(new SourceImporter.ProgressUpdate(
                "Unpacking Japanese disc", 14,
                377_487_360L, 620_152_617L, SourceImporter.Unit.BYTES));

        assertTrue(view.progressPercent() == 14,
                "the visible meter ignored the importer's measured percentage");
        assertTrue(view.progressAmountText().contains("MB"),
                "the pack manager hid the exact byte counter");
    }

    @Test
    @DisplayName("the managed pack directory has an explicit browser action")
    void packDirectoryCanBeOpened() {
        Actions actions = new Actions();
        PackManagerView view = new PackManagerView(actions);
        view.requestOpenPackFolder();
        assertTrue(actions.openedPackFolder,
                "the pack-folder button was not wired to the host file browser");
    }

    @Test
    @DisplayName("pack names cannot change the manager canvas or clip its actions")
    void packContentDoesNotChangeWindowGeometry() {
        PackManagerView view = new PackManagerView(new Actions());
        Dimension empty = view.getPreferredSize();
        PackLibrary.PackInfo longName = new PackLibrary.PackInfo(
                Path.of("/packs/expansion.chonkpack"), "wc2-expansion",
                "Warcraft II: Tides of Darkness and Beyond the Dark Portal",
                "disc", true, false, 1400);
        view.setPacks(List.of(longName), longName.file());

        assertTrue(empty.equals(view.getPreferredSize()),
                "selected content changed the manager's preferred dimensions");
        view.setSize(view.getMinimumSize());
        layout(view);
        for (JButton button : descendants(view, JButton.class)) {
            if (button.isVisible() && button.getText() != null
                    && !button.getText().isBlank()) {
                assertTrue(button.getWidth() >= button.getMinimumSize().width,
                        button.getText() + " was squeezed into an ellipsis");
                assertCompleteLabelFits(button);
            }
        }
        JLabel editions = descendants(view, JLabel.class).stream()
                .filter(label -> "EDITIONS".equals(label.getText()))
                .findFirst().orElseThrow();
        assertContained(editions, view);
    }

    @Test
    @DisplayName("Retina-sized macOS header controls retain their complete labels")
    void macHeaderButtonsNeverEllipsize() {
        String previous = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Mac OS X");
            PackManagerView view = new PackManagerView(new Actions());
            view.setSize(view.getMinimumSize());
            layout(view);

            for (String text : List.of("OPEN IN FINDER", "CLOSE")) {
                JButton button = descendants(view, JButton.class).stream()
                        .filter(candidate -> text.equals(candidate.getText()))
                        .findFirst().orElseThrow();
                assertCompleteLabelFits(button);
            }
        } finally {
            if (previous == null) {
                System.clearProperty("os.name");
            } else {
                System.setProperty("os.name", previous);
            }
        }
    }

    private static void assertCompleteLabelFits(JButton button) {
        Insets insets = button.getInsets();
        int available = button.getWidth() - insets.left - insets.right;
        int required = button.getFontMetrics(button.getFont())
                .stringWidth(button.getText());
        assertTrue(available >= required + 8,
                button.getText() + " has " + available
                        + " px for a " + required + " px label");
    }

    private static final class Actions implements PackManagerView.Actions {
        private boolean openedPackFolder;
        @Override public void chooseFile() {}
        @Override public void chooseFolder() {}
        @Override public void importSource(Path source) {}
        @Override public void usePack(PackLibrary.PackInfo pack) {}
        @Override public void exportPack(PackLibrary.PackInfo pack) {}
        @Override public void deletePack(PackLibrary.PackInfo pack) {}
        @Override public void openPackFolder() { openedPackFolder = true; }
        @Override public void closePackManager() {}
    }

    private static void layout(java.awt.Container root) {
        root.doLayout();
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof java.awt.Container nested) {
                layout(nested);
            }
        }
    }

    private static <T> List<T> descendants(java.awt.Container root, Class<T> type) {
        java.util.ArrayList<T> found = new java.util.ArrayList<>();
        for (java.awt.Component child : root.getComponents()) {
            if (type.isInstance(child)) {
                found.add(type.cast(child));
            }
            if (child instanceof java.awt.Container nested) {
                found.addAll(descendants(nested, type));
            }
        }
        return found;
    }

    private static void assertContained(java.awt.Component child,
            java.awt.Container boundary) {
        java.awt.Component current = child;
        while (current != boundary) {
            java.awt.Container parent = current.getParent();
            assertTrue(parent != null, "component left the manager hierarchy");
            int tolerance = 2; // GridBag may distribute an odd pixel to one column.
            assertTrue(current.getX() >= -tolerance && current.getY() >= -tolerance
                            && current.getX() + current.getWidth()
                                    <= parent.getWidth() + tolerance
                            && current.getY() + current.getHeight()
                                    <= parent.getHeight() + tolerance,
                    "the " + ((JLabel) child).getText()
                            + " guide row was clipped by "
                            + parent.getClass().getSimpleName()
                            + " child=" + current.getBounds()
                            + " parent=" + parent.getBounds());
            current = parent;
        }
    }
}
