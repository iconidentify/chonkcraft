package net.chonkbase.chonkcraft.data.graphic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The interface widgets, cut out of one tall sheet.
 *
 * <p>Implements {@code ConvertGroupedGfu} together with
 * the {@code GroupedGraphicsList} table in {@code wartool.h}.
 *
 * <p>Warcraft II does not store its buttons, checkboxes, arrows and sliders as
 * separate images. They are one uncompressed graphic, and the fifty-three
 * pieces are cut out of it at fixed offsets: each sits at a multiple of 144
 * pixels down the sheet, at its own size. Without this table there is no way to
 * ask for a button, which is why the implementation's menus were drawn with a rectangle
 * and a JVM typeface while the real art sat in the archive.
 *
 * <p>The row pitch of 144 is not the height of anything. It is the stride the
 * artists laid the sheet out on, and most pieces use a fraction of it.
 */
public final class WidgetSheet {

    /** How far apart the pieces are laid out down the sheet. */
    private static final int PITCH = 144;

    /**
     * One piece: where it is and what it is called.
     *
     * @param x      left edge in the sheet
     * @param y      top edge in the sheet
     * @param width  how wide the piece is
     * @param height how tall the piece is
     * @param name   the name the scripts ask for it by
     */
    public record Piece(int x, int y, int width, int height, String name) {}

    private static Piece at(int row, int width, int height, String name) {
        return new Piece(0, row * PITCH, width, height, name);
    }

    /**
     * The widget group, in the order {@code GroupedGraphicsList[0]} lists it.
     *
     * <p>Three of the entries carry an offset of their own rather than sitting
     * flush: the vertical slider bars start twenty pixels down their row and
     * the horizontal ones twenty pixels in. Those four are the reason this
     * cannot be generated from a name and a row number.
     */
    public static final List<Piece> WIDGETS = List.of(
            at(0, 106, 28, "button-grayscale-grayed"),
            at(1, 106, 28, "button-grayscale-normal"),
            at(2, 106, 28, "button-grayscale-pressed"),
            at(3, 128, 20, "button-thin-medium-grayed"),
            at(4, 128, 20, "button-thin-medium-normal"),
            at(5, 128, 20, "button-thin-medium-pressed"),
            at(6, 80, 20, "button-thin-small-grayed"),
            at(7, 80, 20, "button-thin-small-normal"),
            at(8, 80, 20, "button-thin-small-pressed"),
            at(9, 106, 28, "button-small-grayed"),
            at(10, 106, 28, "button-small-normal"),
            at(11, 106, 28, "button-small-pressed"),
            at(12, 164, 28, "button-medium-grayed"),
            at(13, 164, 28, "button-medium-normal"),
            at(14, 164, 28, "button-medium-pressed"),
            at(15, 224, 28, "button-large-grayed"),
            at(16, 224, 28, "button-large-normal"),
            at(17, 224, 28, "button-large-pressed"),
            at(18, 19, 19, "radio-grayed"),
            at(19, 19, 19, "radio-normal-unselected"),
            at(20, 19, 19, "radio-pressed-unselected"),
            at(21, 19, 19, "radio-normal-selected"),
            at(22, 19, 19, "radio-pressed-selected"),
            at(23, 17, 17, "checkbox-grayed"),
            at(24, 17, 17, "checkbox-normal-unselected"),
            at(25, 17, 17, "checkbox-pressed-unselected"),
            at(26, 17, 20, "checkbox-normal-selected"),
            at(27, 17, 20, "checkbox-pressed-selected"),
            at(28, 19, 20, "up-arrow-grayed"),
            at(29, 19, 20, "up-arrow-normal"),
            at(30, 19, 20, "up-arrow-pressed"),
            at(31, 19, 20, "down-arrow-grayed"),
            at(32, 19, 20, "down-arrow-normal"),
            at(33, 19, 20, "down-arrow-pressed"),
            at(34, 20, 19, "left-arrow-grayed"),
            at(35, 20, 19, "left-arrow-normal"),
            at(36, 20, 19, "left-arrow-pressed"),
            at(37, 20, 19, "right-arrow-grayed"),
            at(38, 20, 19, "right-arrow-normal"),
            at(39, 20, 19, "right-arrow-pressed"),
            at(40, 17, 17, "slider-knob"),
            new Piece(0, 41 * PITCH + 20, 19, 124, "vslider-bar-grayed"),
            new Piece(0, 42 * PITCH + 20, 19, 124, "vslider-bar-normal"),
            new Piece(20, 43 * PITCH, 172, 19, "hslider-bar-grayed"),
            new Piece(20, 44 * PITCH, 172, 19, "hslider-bar-normal"),
            at(45, 300, 18, "pulldown-bar-grayed"),
            at(46, 300, 18, "pulldown-bar-normal"),
            at(47, 80, 15, "button-verythin-grayed"),
            at(48, 80, 15, "button-verythin-normal"),
            at(49, 80, 15, "button-verythin-pressed"),
            at(50, 37, 24, "folder-up-grayed"),
            at(51, 37, 24, "folder-up-normal"),
            at(52, 37, 24, "folder-up-pressed"));

    /**
     * Where the palette changes part way down the sheet.
     *
     * <p>{@code ConvertGroupedGfu} swaps to archive entry 14 once it reaches
     * the fourth piece: "hack for multiple palettes". The greyscale buttons at
     * the top are drawn with one palette and everything after them with
     * another, and using one palette throughout tints two thirds of the
     * interface wrongly.
     */
    public static final int SECOND_PALETTE_FROM = 3;

    /** The archive entry the second palette comes from. */
    public static final int SECOND_PALETTE_ENTRY = 14;

    private WidgetSheet() {
    }

    /**
     * Cuts the pieces out of a decoded sheet.
     *
     * <p>A piece whose rectangle runs off the bottom is dropped rather than
     * padded: {@code ConvertGroupedGfu} breaks out of its loop at that point,
     * because the original release's sheet is shorter than the expansion's and
     * simply does not have the last few.
     */
    public static Map<String, IndexedImage> cut(IndexedImage sheet) {
        Map<String, IndexedImage> pieces = new LinkedHashMap<>();
        if (sheet == null) {
            return pieces;
        }
        for (Piece piece : WIDGETS) {
            if (piece.y() + piece.height() > sheet.height()
                    || piece.x() + piece.width() > sheet.width()) {
                break;
            }
            pieces.put(piece.name(), crop(sheet, piece));
        }
        return pieces;
    }

    private static IndexedImage crop(IndexedImage sheet, Piece piece) {
        IndexedImage out = new IndexedImage(piece.width(), piece.height());
        byte[] source = sheet.pixels();
        byte[] target = out.pixels();
        for (int y = 0; y < piece.height(); y++) {
            int from = (piece.y() + y) * sheet.width() + piece.x();
            System.arraycopy(source, from, target, y * piece.width(), piece.width());
        }
        return out;
    }
}
