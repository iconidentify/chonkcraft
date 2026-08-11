package net.chonkbase.chonkcraft.engine.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Native Battle.net Edition HUD geometry for either race. */
public final class UiLayout {
    private static final int GRID_BOTTOM_MARGIN = 4;

    public record Box(int x, int y, int width, int height) {}
    public record ResourceSlot(int iconFrame, int iconX, int iconY, int textX, int textY) {}
    public record Filler(String file, int x, int y, int width, int height) {}
    public record Layout(Box infoPanel, Box buttonPanel, List<Box> buttons,
            List<Box> selectedButtons, Box mapArea, Box minimap,
            int statusLineX, int statusLineY, int statusLineWidth,
            List<ResourceSlot> resources, List<Filler> fillers, Box menuButton,
            Box singleSelected, Box production, List<Box> transporting,
            int completedBarColour, boolean completedBarShadow,
            int maxSelectedTextX, int maxSelectedTextY,
            int autoCastBorderColour) {}

    private UiLayout() {}

    public static Layout battleNet(String race, int width, int height,
            Predicate<String> hasAsset) {
        String side = "orc".equalsIgnoreCase(race) ? "orc" : "human";
        List<Box> buttons = grid(new int[] {340, 387, 434});
        List<Box> selected = grid(new int[] {169, 223, 277});
        List<Box> transporting = List.of(
                icon(9, 387), icon(9, 434), icon(65, 387),
                icon(65, 434), icon(121, 387), icon(121, 434));

        List<Filler> fillers = new ArrayList<>();
        add(fillers, hasAsset, "ui/" + side + "/filler-right.png", width - 16, 0, 16, height);
        add(fillers, hasAsset, "ui/" + side + "/resource.png", 176, 0, width - 192, 16);
        add(fillers, hasAsset, "ui/" + side + "/statusline.png", 176, height - 16,
                width - 192, 16);
        add(fillers, hasAsset, "ui/" + side + "/buttonpanel.png", 0, 336, 176,
                144 + height - 480);
        add(fillers, hasAsset, "ui/" + side + "/menubutton.png", 0, 0, 0, 0);
        add(fillers, hasAsset, "ui/" + side + "/minimap.png", 0, 24, 0, 0);

        int gridBottom = buttons.stream().mapToInt(b -> b.y() + b.height()).max().orElse(0);
        gridBottom = Math.max(gridBottom,
                transporting.stream().mapToInt(b -> b.y() + b.height()).max().orElse(0));
        int deficit = Math.max(0, gridBottom + GRID_BOTTOM_MARGIN - height);
        int buttonPanelY = 336 - deficit;
        if (deficit > 0) {
            buttons = slide(buttons, deficit);
            transporting = slide(transporting, deficit);
            int by = buttonPanelY;
            int d = deficit;
            fillers.replaceAll(f -> f.file().contains("buttonpanel")
                    ? new Filler(f.file(), f.x(), by, f.width(), f.height() + d) : f);
        }

        List<ResourceSlot> resources = List.of(
                resource(0, 176, 0), resource(1, 251, 0), resource(2, 326, 0),
                resource(0, width - 170, 0), resource(0, width - 100, 0),
                new ResourceSlot(3, -100, -100, -100, -100),
                resource(0, width - 40, 0), new ResourceSlot(0, 0, 0, 0, 0));

        return new Layout(
                new Box(0, 160, 176, 176), new Box(0, buttonPanelY, 176, 144),
                List.copyOf(buttons), selected,
                new Box(176, 16, Math.max(0, width - 192), Math.max(0, height - 32)),
                new Box(24, 26, 128, 128), 178, height - 14,
                Math.max(0, width - 194), resources, List.copyOf(fillers),
                new Box(24, 2, 0, 0), icon(9, 169), icon(110, 241),
                List.copyOf(transporting), 0x306404, false, 10, 170, 0x0000FC);
    }

    private static List<Box> grid(int[] rows) {
        List<Box> result = new ArrayList<>();
        for (int y : rows) for (int x : new int[] {9, 65, 121}) result.add(icon(x, y));
        return List.copyOf(result);
    }

    private static Box icon(int x, int y) {
        return new Box(x, y, IconCatalog.ICON_WIDTH, IconCatalog.ICON_HEIGHT);
    }

    private static List<Box> slide(List<Box> boxes, int amount) {
        return boxes.stream().map(b -> new Box(b.x(), b.y() - amount, b.width(), b.height())).toList();
    }

    private static ResourceSlot resource(int frame, int x, int y) {
        return new ResourceSlot(frame, x, y, x + 18, y + 1);
    }

    private static void add(List<Filler> into, Predicate<String> hasAsset,
            String file, int x, int y, int width, int height) {
        if (hasAsset == null || hasAsset.test(file)) into.add(new Filler(file, x, y, width, height));
    }
}
