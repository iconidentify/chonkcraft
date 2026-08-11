package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where the minimap is and what a point on it means.
 *
 * <p>These need no installation, which is the point: the mapping from a pixel
 * in the sidebar to a tile in the world is arithmetic, and it was wrong by two
 * pixels for as long as the numbers were copied by hand rather than read from
 * {@code UI.Minimap}.
 */
class SidePanelGeometryTest {

    @Test
    @DisplayName("the frame around the minimap is not the minimap")
    void theFrameIsNotClickable() {
        // UI.Minimap is at 24, 26 and 128 square. Everything outside that is
        // the frame it sits in, and clicking the frame must do nothing rather
        // than jump the view to a corner.
        assertTrue(SidePanel.isOnMinimap(SidePanel.INSET_X, SidePanel.INSET_Y),
                "the top left pixel of the picture is part of the picture");
        assertTrue(SidePanel.isOnMinimap(SidePanel.INSET_X + SidePanel.SIZE - 1,
                SidePanel.INSET_Y + SidePanel.SIZE - 1));

        assertFalse(SidePanel.isOnMinimap(SidePanel.INSET_X - 1, SidePanel.INSET_Y));
        assertFalse(SidePanel.isOnMinimap(SidePanel.INSET_X, SidePanel.INSET_Y - 1));
        assertFalse(SidePanel.isOnMinimap(SidePanel.INSET_X + SidePanel.SIZE,
                SidePanel.INSET_Y + SidePanel.SIZE));
        // The command grid is a long way below it and must never be mistaken
        // for it, or pressing a button would also move the view.
        assertFalse(SidePanel.isOnMinimap(9, 340));
    }

    @Test
    @DisplayName("a point on the minimap is the tile under it")
    void theCornersMapToTheCorners() {
        int width = 96;
        int height = 64;
        assertArrayEquals(new int[] {0, 0},
                SidePanel.tileAt(SidePanel.INSET_X, SidePanel.INSET_Y, width, height));
        assertArrayEquals(new int[] {width - 1, height - 1},
                SidePanel.tileAt(SidePanel.INSET_X + SidePanel.SIZE - 1,
                        SidePanel.INSET_Y + SidePanel.SIZE - 1, width, height));
        // The middle of the picture is the middle of the map.
        assertArrayEquals(new int[] {width / 2, height / 2},
                SidePanel.tileAt(SidePanel.INSET_X + SidePanel.SIZE / 2,
                        SidePanel.INSET_Y + SidePanel.SIZE / 2, width, height));
    }

    @Test
    @DisplayName("a point outside the map is clamped rather than read off the end")
    void outOfRangeIsClamped() {
        int[] far = SidePanel.tileAt(SidePanel.INSET_X + SidePanel.SIZE + 40,
                SidePanel.INSET_Y + SidePanel.SIZE + 40, 32, 32);
        assertArrayEquals(new int[] {31, 31}, far);
        int[] before = SidePanel.tileAt(0, 0, 32, 32);
        assertArrayEquals(new int[] {0, 0}, before);
    }

    /**
     * The seven slots as {@code ui_pandora.legacy-declaration} declares them.
     *
     * <p>Gold, lumber and oil at 176 plus 0, 75 and 150; food, score and
     * workers at the width less 16 less 154, 84 and 24; mana parked at minus a
     * hundred, which is how the script says "do not draw this". The figure is
     * always eighteen right of the icon.
     */
    private static java.util.List<
            net.chonkbase.chonkcraft.engine.ui.UiLayout.ResourceSlot> declared(int width) {
        int[] iconX = {176, 176 + 75, 176 + 150, width - 16 - 154, width - 16 - 84, -100,
            width - 16 - 24};
        java.util.List<net.chonkbase.chonkcraft.engine.ui.UiLayout.ResourceSlot> slots =
                new java.util.ArrayList<>();
        for (int x : iconX) {
            slots.add(new net.chonkbase.chonkcraft.engine.ui.UiLayout.ResourceSlot(
                    0, x, 0, x < 0 ? -100 : x + 18, 1));
        }
        return slots;
    }

    /** Icon, gap and figure, which is what one count covers on the bar. */
    private static int width(int textWidth) {
        return 18 + textWidth;
    }

    @Test
    @DisplayName("counts that fit are left exactly where the script puts them")
    void thedeclaredPositionsAreKeptWhenTheyWork() {
        int view = 1280;
        int[] widths = {30, 30, 24, 32, 40, -1, 12};
        var cells = SidePanel.layOutTopBar(declared(view), widths, view);
        assertEquals(6, cells.size(), "six counts, the mana slot left out");
        // Two in from the 176 the script declares, which is the sidebar's own
        // edge: the strip has a two pixel moulding and the icon goes inside it.
        assertEquals(176 + SidePanel.TOP_BAR_BEVEL, cells.get(0).iconX());
        assertEquals(176 + 75, cells.get(1).iconX());
        assertEquals(176 + 150, cells.get(2).iconX());
        // The three measured back from the right edge are pulled in, never
        // pushed out. The script's own spacing does not survive contact with a
        // figure of any width: the idle worker count is declared twenty-four
        // pixels from the strip of art down the right hand edge and a count and
        // its icon are thirty, so it moves, and the score and the food move
        // ahead of it.
        int[] declaredRight = {view - 16 - 154, view - 16 - 84, view - 16 - 24};
        for (int i = 0; i < 3; i++) {
            assertTrue(cells.get(3 + i).iconX() <= declaredRight[i],
                    "slot " + cells.get(3 + i).slot() + " is at " + cells.get(3 + i).iconX()
                            + ", right of the " + declaredRight[i] + " the script declares");
        }
        // The last of them ends inside the strip, which is the whole point.
        assertEquals(view - 16 - 4 - width(12), cells.get(5).iconX());
    }

    @Test
    @DisplayName("no count is ever drawn under the strip of art down the right edge")
    void nothingIsClippedByTheFiller() {
        for (int view : new int[] {1024, 1280, 1600, 1920, 2560, 640, 512, 341, 256}) {
            // Five digits everywhere, which is what a long game looks like.
            int[] widths = {36, 36, 36, 40, 44, -1, 22};
            var cells = SidePanel.layOutTopBar(declared(view), widths, view);
            assertFalse(cells.isEmpty(), "something is shown on a bar of " + view);
            java.awt.Rectangle previous = null;
            for (var cell : cells) {
                java.awt.Rectangle box = cell.bounds();
                assertTrue(box.x >= SidePanel.WIDTH + SidePanel.TOP_BAR_BEVEL,
                        "on a bar of " + view + " slot " + cell.slot() + " starts at " + box.x);
                assertTrue(box.x + box.width <= view - 16,
                        "on a bar of " + view + " slot " + cell.slot() + " ends at "
                                + (box.x + box.width));
                if (previous != null) {
                    assertTrue(box.x >= previous.x + previous.width,
                            "on a bar of " + view + " slot " + cell.slot()
                                    + " overlaps the one before it");
                }
                previous = box;
            }
            // Gold is never the count that gives way.
            assertTrue(cells.get(0).slot() == 0, "gold survives a bar of " + view);
        }
    }

    @Test
    @DisplayName("a resource icon fits inside the moulding of the strip it sits on")
    void theIconIsSmallerThanTheStrip() {
        // Sixteen tall with a two pixel bevel at each edge leaves twelve, and
        // the icon has to be the twelve rather than the fourteen the script
        // declares, or its gold border stands proud of the marble.
        assertEquals(SidePanel.TOP_BAR_HEIGHT - SidePanel.TOP_BAR_BEVEL * 2,
                SidePanel.RESOURCE_ICON);
        var cells = SidePanel.layOutTopBar(declared(1280), new int[] {30, 30, 24, 32, 40, -1, 12},
                1280);
        for (var cell : cells) {
            assertTrue(cell.iconY() >= SidePanel.TOP_BAR_BEVEL
                            && cell.iconY() + SidePanel.RESOURCE_ICON
                                    <= SidePanel.TOP_BAR_HEIGHT - SidePanel.TOP_BAR_BEVEL,
                    "slot " + cell.slot() + " has its icon at " + cell.iconY());
        }
    }
}
