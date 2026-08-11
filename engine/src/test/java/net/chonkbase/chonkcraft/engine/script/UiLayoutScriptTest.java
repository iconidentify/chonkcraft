package net.chonkbase.chonkcraft.engine.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.ui.UiLayout;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The interface layout, read from the game's own scripts.
 *
 * <p>These pin the numbers to what {@code ui_pandora.legacy-declaration} says rather than to
 * what the implementation used to assume. Two of them differed: the minimap sits at y 26
 * and the command grid stays at y 340 however tall the window is.
 */
class UiLayoutScriptTest {

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    @Test
    @DisplayName("the sidebar geometry is the script's")
    void theLayoutReads() {
        UiLayout.Layout layout = load().uiLayout("human", 640, 480);
        assertNotNull(layout, "the layout script did not run");

        assertEquals(0, layout.infoPanel().x());
        assertEquals(160, layout.infoPanel().y());
        assertEquals(0, layout.buttonPanel().x());
        assertEquals(336, layout.buttonPanel().y());

        // Not 24. UI.Minimap.Y is written as 24 + 2, and those two pixels are
        // the difference between clicking the map and clicking its frame.
        assertEquals(24, layout.minimap().x());
        assertEquals(26, layout.minimap().y());
        assertEquals(128, layout.minimap().width());
        assertEquals(128, layout.minimap().height());
    }

    @Test
    @DisplayName("the nine command slots are where AddButtonPanelButton puts them")
    void theCommandGridIsPlaced() {
        UiLayout.Layout layout = load().uiLayout("human", 640, 480);
        assertEquals(9, layout.buttons().size());
        int[] columns = {9, 65, 121};
        int[] rows = {340, 387, 434};
        for (int slot = 0; slot < 9; slot++) {
            assertEquals(columns[slot % 3], layout.buttons().get(slot).x(), "slot " + slot);
            assertEquals(rows[slot / 3], layout.buttons().get(slot).y(), "slot " + slot);
        }
    }

    @Test
    @DisplayName("the grid does not move when the window grows")
    void theGridStaysUnderTheInfoPanel() {
        // The button panel art stretches to fill a taller window but the slots
        // do not move with it. Anchoring them to the bottom edge instead puts
        // the command grid three hundred pixels below the info panel.
        UiLayout.Layout tall = load().uiLayout("human", 1280, 800);
        assertEquals(340, tall.buttons().getFirst().y());
        assertEquals(434, tall.buttons().getLast().y());
    }

    /**
     * The grid slides up rather than off a screen shorter than it assumes.
     *
     * <p>The script pins its rows at 340, 387 and 434 whatever the height,
     * which is fine at the 480 it was written for and wrong the moment a
     * player pins the interface scale on a window that leaves fewer design
     * rows than that: reported from play at interface two, with a
     * screenshot of the Build button half off the bottom of the screen. A
     * button that is not on the screen is a command the player does not
     * have.
     */
    @Test
    @DisplayName("a screen too short for the command grid gets the grid slid up, not cut off")
    void theGridFitsAShortScreen() {
        UiLayout.Layout shortened = load().uiLayout("human", 640, 452);
        assertEquals(9, shortened.buttons().size(),
                "the layout lost slots on a short screen, so fitting proves nothing");
        for (var slot : shortened.buttons()) {
            assertTrue(slot.y() + slot.height() <= 452 - 4,
                    "a command slot ends at " + (slot.y() + slot.height())
                            + " on a 452-row screen: the Build row is off the bottom");
        }
        for (var slot : shortened.transporting()) {
            assertTrue(slot.y() + slot.height() <= 452 - 4,
                    "a cargo slot ends at " + (slot.y() + slot.height())
                            + " on a 452-row screen");
        }
        // The rows keep their spacing: the whole grid moves as one thing.
        assertEquals(shortened.buttons().get(3).y() - shortened.buttons().get(0).y(), 47,
                "sliding the grid changed the spacing between its rows");

        // The stone moves with the grid it carries. The first cut slid the
        // slots alone, and a player screenshotted the wells sitting above
        // their own marble with the old seam showing behind them.
        int deficit = 340 - shortened.buttons().getFirst().y();
        assertEquals(336 - deficit, shortened.buttonPanel().y(),
                "the button panel seam did not follow the grid up");
        UiLayout.Filler stone = null;
        for (var filler : shortened.fillers()) {
            if (filler.file().contains("buttonpanel")) {
                stone = filler;
            }
        }
        assertNotNull(stone, "the layout lost the button panel stone entirely");
        assertEquals(336 - deficit, stone.y(),
                "the button panel stone still starts at the script's 336, so the"
                        + " grid sits above its own marble");
        assertTrue(stone.y() + stone.height() >= 452,
                "sliding the stone up pulled its foot off the bottom of the screen");

        // And a screen with room keeps the script's own numbers exactly.
        UiLayout.Layout roomy = load().uiLayout("human", 640, 480);
        assertEquals(340, roomy.buttons().getFirst().y(),
                "the clamp moved a grid that already fitted");
        assertEquals(336, roomy.buttonPanel().y(),
                "the clamp moved stone that already fitted");
    }

    @Test
    @DisplayName("the map area stops short of the filler and the status line")
    void theMapAreaLeavesRoomForTheChrome() {
        GameData data = load();
        UiLayout.Layout small = data.uiLayout("human", 640, 480);
        assertEquals(176, small.mapArea().x());
        assertEquals(16, small.mapArea().y());
        // EndX is Video.Width - 16 - 1, so the field is that much narrower than
        // everything right of the sidebar.
        assertEquals(640 - 176 - 16, small.mapArea().width());
        assertEquals(480 - 16 - 16, small.mapArea().height());

        UiLayout.Layout large = data.uiLayout("human", 1280, 800);
        assertEquals(1280 - 176 - 16, large.mapArea().width());
        assertEquals(800 - 16 - 16, large.mapArea().height());
    }

    @Test
    @DisplayName("the resource counts move with the window")
    void theResourceBarIsMeasuredFromBothEdges() {
        GameData data = load();
        UiLayout.Layout small = data.uiLayout("human", 640, 480);
        // Gold, lumber and oil are fixed distances from the sidebar.
        assertEquals(176, small.resources().get(0).iconX());
        assertEquals(176 + 75, small.resources().get(1).iconX());
        assertEquals(176 + 150, small.resources().get(2).iconX());
        // Food is measured back from the right edge, so it has to move.
        UiLayout.Layout large = data.uiLayout("human", 1280, 800);
        assertEquals(640 - 16 - 154, small.resources().get(3).iconX());
        assertEquals(1280 - 16 - 154, large.resources().get(3).iconX());
    }

    @Test
    @DisplayName("the interface art is placed, not stacked at the origin")
    void thefillersCarryTheirPositions() {
        UiLayout.Layout layout = load().uiLayout("human", 640, 480);
        assertTrue(layout.fillers().size() >= 6, "expected six pieces of chrome");

        var minimap = layout.fillers().stream()
                .filter(filler -> filler.file().contains("minimap"))
                .findFirst()
                .orElseThrow();
        // AddFiller("ui/human/minimap.png", 0, 24). Drawing it at the top of
        // the column leaves a black band where the menu strip belongs.
        assertEquals(0, minimap.x());
        assertEquals(24, minimap.y());

        var panel = layout.fillers().stream()
                .filter(filler -> filler.file().contains("buttonpanel"))
                .findFirst()
                .orElseThrow();
        assertEquals(336, panel.y());
        // AddResizedFiller stretches it: 144 + Video.Height - 480.
        assertEquals(176, panel.width());
        assertEquals(144, panel.height());
    }

    @Test
    @DisplayName("both races have a layout and they agree on the frame")
    void theOrcLayoutReadsToo() {
        GameData data = load();
        UiLayout.Layout human = data.uiLayout("human", 640, 480);
        UiLayout.Layout orc = data.uiLayout("orc", 640, 480);
        assertNotNull(orc);
        // The two races share the layout and differ only in their art.
        assertEquals(human.minimap(), orc.minimap());
        assertEquals(human.buttons(), orc.buttons());
        assertTrue(orc.fillers().stream().allMatch(filler -> filler.file().contains("orc")),
                "the orc layout should name orc art: " + orc.fillers());
    }
}
