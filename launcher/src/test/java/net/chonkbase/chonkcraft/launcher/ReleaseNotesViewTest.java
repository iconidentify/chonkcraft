package net.chonkbase.chonkcraft.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReleaseNotesViewTest {

    @Test
    @DisplayName("every release is represented by one history card")
    void everyEntryIsRendered() {
        ReleaseNotesView view = new ReleaseNotesView(() -> { });
        view.setHistory(new ReleaseNotesCatalog.History(List.of(
                entry("3.0.0"), entry("2.0.0"), entry("1.0.0"))));

        assertEquals(3, view.entryCount());
    }

    @Test
    @DisplayName("showing release notes returns a bottom-laid-out viewport to newest")
    void showingStartsAtNewestEntry() throws Exception {
        ReleaseNotesView view = new ReleaseNotesView(() -> { });
        List<ReleaseNotesCatalog.Entry> entries = new ArrayList<>();
        for (int version = 12; version > 0; version--) {
            entries.add(entry(version + ".0.0"));
        }
        ReleaseNotesCatalog.History history = new ReleaseNotesCatalog.History(entries);
        JScrollPane scroll = find(view, JScrollPane.class);

        onEdt(() -> {
            view.setSize(760, 620);
            view.setHistory(history);
            layout(view);
            scroll.getVerticalScrollBar().setValue(
                    scroll.getVerticalScrollBar().getMaximum());
        });
        assertTrue(scroll.getVerticalScrollBar().getValue() > 0,
                "the history must be long enough to reproduce the bottom layout");

        onEdt(view::showLatest);
        onEdt(() -> layout(view));

        assertEquals(scroll.getVerticalScrollBar().getMinimum(),
                scroll.getVerticalScrollBar().getValue());
        assertEquals(0, scroll.getViewport().getViewPosition().y);
    }

    private static ReleaseNotesCatalog.Entry entry(String version) {
        return new ReleaseNotesCatalog.Entry(version, "2026-08-10T12:00:00Z",
                "Release " + version, "- A player-visible improvement.", "test");
    }

    private static void onEdt(Runnable action)
            throws InvocationTargetException, InterruptedException {
        SwingUtilities.invokeAndWait(action);
    }

    private static void layout(Container container) {
        container.doLayout();
        for (Component component : container.getComponents()) {
            if (component instanceof Container child) {
                layout(child);
            }
        }
    }

    private static <T extends Component> T find(Container root, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                T found = find(child, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
