package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.ui.UiLayout;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the sidebar actually puts on the screen.
 *
 * <p>Four things the layout scripts have been declaring since the game shipped
 * and this implementation read and threw away: the slot a lone unit's portrait goes in,
 * the colour a progress bar is filled with, where the overflow count for a
 * large selection is written, and the border round a spell set to cast itself.
 * Every one of them is invisible to a test that only checks arithmetic, which
 * is how all four went missing without anything failing.
 *
 * <p>So these paint. Each case draws the real {@link SidePanel} and
 * {@link CommandPanel} at the interface's own size and then reads pixels back
 * out of the frame, at coordinates taken from the scripts rather than from the
 * code under test. The frames are written to {@code target/} as well, because
 * a pixel assertion says a green rectangle is where it should be and says
 * nothing at all about whether the panel looks right.
 */
class InfoPanelRenderTest {

    private static final String MAP = "campaigns/human/level02h";

    /** The size the interface was designed at, so one pixel is one pixel. */
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;

    private record Scene(World world, SidePanel panel, CommandPanel commands,
            UiLayout.Layout layout, java.util.Map<String, UnitType> types,
            GameData data) {}

    private static GameData data() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II assets configured. Set CHONKCRAFT_ASSET_PACK or"
                        + " -Dwc2.install.dir=/path/to/game.");
        return new GameData(assets);
    }

    private static Scene scene() {
        GameData data = data();
        PudMap pud = data.campaignMap(MAP);
        Assumptions.assumeTrue(pud != null, "no campaign map available");
        var tileset = data.loadTileset(pud.tileset());
        World world = new World(GameMap.from(pud, tileset.tileset()), Player.from(pud));
        world.setUpgrades(data.upgrades().upgrades());
        world.setSpells(data.spells().spells());
        world.setMissileTypes(data.missiles().types());
        world.fog().revealAll(0);
        String tilesetName = pud.tileset() == PudMap.Tileset.FOREST
                ? "summer"
                : pud.tileset().name().toLowerCase(java.util.Locale.ROOT);

        UiLayout.Layout layout = data.uiLayout("human", WIDTH, HEIGHT);
        assertNotNull(layout, "the layout script must be readable for any of this to mean much");
        SidePanel panel = new SidePanel(world, data, 0, "human", tilesetName, layout);
        CommandPanel commands = new CommandPanel(world, data, data.userInterface(tilesetName),
                data.upgrades().dependencies(), 0, tilesetName, "human",
                data.unitTypes().types(), layout);
        return new Scene(world, panel, commands, layout, data.unitTypes().types(), data);
    }

    /** Draws the chrome as the screen draws it, at one interface pixel each. */
    private static BufferedImage paint(Scene scene, Unit selected) {
        BufferedImage frame = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = frame.createGraphics();
        scene.panel().draw(g2, WIDTH, HEIGHT, selected, 0, 0, WIDTH, HEIGHT);
        scene.commands().draw(g2, selected);
        g2.dispose();
        return frame;
    }

    private static void keep(BufferedImage frame, String name) {
        try {
            Path out = Paths.get("target", "panel-" + name + ".png");
            Files.createDirectories(out.getParent());
            javax.imageio.ImageIO.write(frame, "png", out.toFile());
        } catch (java.io.IOException ignored) {
            // A frame that could not be written is not a failing assertion.
        }
    }

    /** Selects exactly these units, as a click or a band does. */
    private static Unit select(World world, Unit... chosen) {
        for (Unit unit : world.unitsSnapshot()) {
            unit.setSelected(false);
        }
        for (Unit unit : chosen) {
            unit.setSelected(true);
        }
        return chosen.length == 0 ? null : chosen[0];
    }

    /** How many pixels in a box are not the panel's own stone. */
    private static int busyPixels(BufferedImage frame, java.awt.Rectangle box) {
        int busy = 0;
        for (int y = box.y; y < box.y + box.height; y++) {
            for (int x = box.x; x < box.x + box.width; x++) {
                if (x < 0 || y < 0 || x >= frame.getWidth() || y >= frame.getHeight()) {
                    continue;
                }
                int rgb = frame.getRGB(x, y);
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;
                // The generated stone is a dark neutral grey with a touch of
                // blue in it and never gets past 150. Anything coloured, or
                // anything bright, is something drawn on top of it.
                if (red > 150 || green > 150 || blue > 150
                        || Math.abs(red - green) > 24 || Math.abs(green - blue) > 24) {
                    busy++;
                }
            }
        }
        return busy;
    }

    /** How many pixels in a box are within a few steps of a colour. */
    private static int pixelsNear(BufferedImage frame, java.awt.Rectangle box, int colour,
            int tolerance) {
        int found = 0;
        for (int y = box.y; y < box.y + box.height; y++) {
            for (int x = box.x; x < box.x + box.width; x++) {
                if (x < 0 || y < 0 || x >= frame.getWidth() || y >= frame.getHeight()) {
                    continue;
                }
                int rgb = frame.getRGB(x, y);
                if (Math.abs(((rgb >> 16) & 0xFF) - ((colour >> 16) & 0xFF)) <= tolerance
                        && Math.abs(((rgb >> 8) & 0xFF) - ((colour >> 8) & 0xFF)) <= tolerance
                        && Math.abs((rgb & 0xFF) - (colour & 0xFF)) <= tolerance) {
                    found++;
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("the layout script's colours reach the layout")
    void theScriptsColoursAreRead() {
        UiLayout.Layout layout = scene().layout();
        // UI.CompletedBarColorRGB = CColor(48, 100, 4). CColor answered an
        // empty table, so the three numbers in that line went nowhere.
        assertEquals(0x306404, layout.completedBarColour(),
                "UI.CompletedBarColorRGB = CColor(48, 100, 4)");
        assertFalse(layout.completedBarShadow(), "UI.CompletedBarShadow = false");
        // UI.ButtonPanel.AutoCastBorderColorRGB = CColor(0, 0, 252).
        assertEquals(0x0000FC, layout.autoCastBorderColour());
        // UI.MaxSelectedTextX = 10, UI.MaxSelectedTextY = 160 + 10.
        assertEquals(10, layout.maxSelectedTextX());
        assertEquals(170, layout.maxSelectedTextY());
        // And the slot that was collected but never drawn.
        assertEquals(9, layout.singleSelected().x());
        assertEquals(169, layout.singleSelected().y());
    }

    @Test
    @DisplayName("retired scripting language-free roster icons paint unit and building portraits")
    void thePortraitIsDrawn() {
        Scene scene = scene();
        java.awt.Rectangle slot = new java.awt.Rectangle(scene.layout().singleSelected().x(),
                scene.layout().singleSelected().y(), 46, 38);
        for (String ident : java.util.List.of("unit-peasant", "unit-town-hall")) {
            Unit unit = scene.world().createUnit(scene.types().get(ident), 0,
                    "unit-peasant".equals(ident) ? 6 : 10, 16);
            assertNotNull(unit, "somewhere to stand " + ident);
            BufferedImage frame = paint(scene, select(scene.world(), unit));
            keep(frame, "portrait-" + ident);

            // Native UnitType.icon(), the generated icon catalog and the
            // authenticated pack must meet here. A blank fallback is neutral
            // grey and therefore has no busy pixels.
            int busy = busyPixels(frame, slot);
            assertTrue(busy > 200,
                    ident + " portrait slot at 9, 169 holds " + busy
                            + " drawn pixels; it held only the blank fallback");
        }
    }

    @Test
    @DisplayName("a building going up shows the game's own progress bar")
    void theCompletedBarIsDrawnInTheScriptsColour() {
        Scene scene = scene();
        Unit site = scene.world().createUnit(scene.types().get("unit-farm"), 0, 10, 16);
        assertNotNull(site, "somewhere to put a farm");
        // Half built, which is the state the bar exists to show.
        site.setOrder(Unit.Order.UNDER_CONSTRUCTION);
        site.setProgressGoal(100);
        site.setProgress(50);
        assertEquals(0.5, site.progressFraction(), 0.01);

        BufferedImage frame = paint(scene, select(scene.world(), site));
        keep(frame, "construction");

        // Pos = {12, 153} against an info panel at y 160, 152 by 14.
        java.awt.Rectangle bar = new java.awt.Rectangle(12, 160 + 153, 152, 14);
        int green = pixelsNear(frame, bar, 0x306404, 6);
        assertTrue(green > 300,
                "the completed bar holds " + green + " pixels of CColor(48, 100, 4)");
        // And it is a bar rather than a full block: the unfilled end is dark.
        int filledRight = pixelsNear(frame,
                new java.awt.Rectangle(12 + 148, 160 + 155, 3, 10), 0x306404, 6);
        assertEquals(0, filledRight,
                "a bar at " + Math.round(site.progressFraction() * 100)
                        + " per cent must not be full to its end");
    }

    @Test
    @DisplayName("a barracks at work shows what it is making, and how far along")
    void theProductionSlotAndItsBarAreDrawn() {
        Scene scene = scene();
        Unit hall = scene.world().createUnit(scene.types().get("unit-town-hall"), 0, 10, 16);
        assertNotNull(hall, "somewhere to put a town hall");
        for (int i = 0; i < 3; i++) {
            scene.world().createUnit(scene.types().get("unit-farm"), 0, 4 + i * 3, 22);
        }
        scene.world().player(0).set(UnitType.Resource.GOLD, 5000);
        scene.world().recalculateSupply();
        assertTrue(scene.world().orderTrain(hall, scene.types().get("unit-peasant")),
                "a hall with the money and the room trains a peasant");
        hall.setProgress(hall.progressGoal() * 3 / 4);

        BufferedImage frame = paint(scene, select(scene.world(), hall));
        keep(frame, "training");

        // UI.SingleTrainingButton is at 110, 241 in every shipped layout.
        java.awt.Rectangle slot = SidePanel.productionBounds();
        assertEquals(110, slot.x);
        assertEquals(241, slot.y);
        assertTrue(busyPixels(frame, slot) > 200,
                "the icon of what is being trained is drawn in its own slot");

        java.awt.Rectangle bar = new java.awt.Rectangle(12, 160 + 153, 152, 14);
        int green = pixelsNear(frame, bar, 0x306404, 6);
        assertTrue(green > 300, "the training bar holds " + green + " green pixels");
    }

    @Test
    @DisplayName("more units than portrait slots writes the count where the script says")
    void theOverflowCountGoesWhereTheScriptSaysIt() {
        Scene scene = scene();
        java.util.List<Unit> group = new java.util.ArrayList<>();
        for (int i = 0; i < 12 && group.size() < 12; i++) {
            Unit made = scene.world().createUnit(scene.types().get("unit-footman"),
                    0, 4 + i, 16);
            if (made != null) {
                group.add(made);
            }
        }
        Assumptions.assumeTrue(group.size() > 9, "not enough room to stand ten footmen");

        // Exactly nine fills the grid and says nothing more.
        BufferedImage full = paint(scene,
                select(scene.world(), group.subList(0, 9).toArray(new Unit[0])));
        BufferedImage over = paint(scene, select(scene.world(), group.toArray(new Unit[0])));
        keep(over, "group");

        // UI.MaxSelectedTextX, UI.MaxSelectedTextY: 10 and 170. The count is
        // written over the corner of the first portrait, which is where the
        // original writes it and why it is only ever two or three characters.
        var where = scene.panel().maxSelectedTextAt();
        assertEquals(10, where.x);
        assertEquals(170, where.y);
        java.awt.Rectangle badge = new java.awt.Rectangle(where.x - 2, where.y - 1, 30, 20);
        int changed = 0;
        for (int y = badge.y; y < badge.y + badge.height; y++) {
            for (int x = badge.x; x < badge.x + badge.width; x++) {
                if (full.getRGB(x, y) != over.getRGB(x, y)) {
                    changed++;
                }
            }
        }
        assertTrue(changed > 60,
                "the tenth selected unit changed " + changed + " pixels at 10, 170");

        // And nothing else on the panel moved: the count is the only
        // difference between nine selected and twelve.
        java.util.List<String> elsewhere = new java.util.ArrayList<>();
        for (int y = 160; y < 340; y++) {
            for (int x = 0; x < 176; x++) {
                if (badge.contains(x, y)) {
                    continue;
                }
                if (full.getRGB(x, y) != over.getRGB(x, y)) {
                    elsewhere.add(x + "," + y);
                }
            }
        }
        assertEquals(java.util.List.of(), elsewhere.subList(0, Math.min(8, elsewhere.size())),
                "only the overflow count may differ");
    }

    @Test
    @DisplayName("a spell set to cast itself is bordered in the panel's own blue")
    void theAutoCastBorderIsDrawn() {
        Scene scene = scene();
        Unit mage = scene.world().createUnit(scene.types().get("unit-mage"), 0, 6, 16);
        assertNotNull(mage, "somewhere to stand a mage");
        mage.setMana(mage.type().mana());
        // A mage offers its spells only once they are researched, and this is
        // about the border rather than about the research tree.
        for (String ident : scene.data().upgrades().upgrades().all().keySet()) {
            scene.world().upgrades(0).complete(ident);
        }
        select(scene.world(), mage);

        BufferedImage before = paint(scene, mage);
        keep(before, "mage-plain");

        // Find the slot the panel gave to a spell, and turn that spell on.
        int slot = -1;
        String spell = null;
        for (int i = 0; i < 9 && slot < 0; i++) {
            var bounds = scene.commands().boundsOf(i);
            if (bounds == null) {
                continue;
            }
            var button = scene.commands().buttonAt(bounds.x + 1, bounds.y + 1);
            if (button != null && "cast-spell".equals(button.action())) {
                var known = scene.data().spells().spells().get(button.value());
                if (known != null && known.autoCastable()) {
                    slot = i;
                    spell = button.value();
                }
            }
        }
        Assumptions.assumeTrue(slot >= 0, "the mage offers no spell that may be set to autocast");
        java.awt.Rectangle box = scene.commands().boundsOf(slot);
        java.awt.Rectangle ring = new java.awt.Rectangle(box.x - 2, box.y - 2,
                box.width + 4, box.height + 4);
        assertEquals(0, pixelsNear(before, ring, 0x0000FC, 8),
                "nothing is bordered blue before anything is set");

        assertTrue(scene.world().setAutoCast(mage, spell), "the world accepts the standing spell");
        BufferedImage after = paint(scene, mage);
        keep(after, "mage-autocast");
        int blue = pixelsNear(after, ring, 0x0000FC, 8);
        // Two rings round a 46 by 38 icon is a little under three hundred
        // pixels; anything of that order means the border is there.
        assertTrue(blue > 200,
                "UI.ButtonPanel.AutoCastBorderColorRGB drew " + blue + " pixels round slot "
                        + slot);
    }

    @Test
    @DisplayName("the top bar carries all six counts the layout declares")
    void theScoreAndTheIdleWorkersAreShown() {
        Scene scene = scene();
        Unit peasant = scene.world().createUnit(scene.types().get("unit-peasant"), 0, 6, 16);
        assertNotNull(peasant);
        scene.world().player(0).addScore(1234);

        BufferedImage frame = paint(scene, null);
        keep(frame, "topbar");

        // UI.Resources[ScoreCost] and [FreeWorkersCount] are the fifth and
        // seventh slots, measured back from the right edge. Both were parsed
        // and neither was drawn.
        var score = scene.layout().resources().get(4);
        var workers = scene.layout().resources().get(6);
        assertTrue(score.iconX() > 0 && workers.iconX() > 0, "both slots are on screen");
        assertTrue(busyPixels(frame,
                new java.awt.Rectangle(score.iconX(), score.iconY(), 14, 14)) > 20,
                "the score icon is drawn");
        assertTrue(busyPixels(frame,
                new java.awt.Rectangle(score.textX(), score.textY(), 40, 14)) > 20,
                "the score figure is written");
        assertTrue(busyPixels(frame,
                new java.awt.Rectangle(workers.iconX(), workers.iconY(), 14, 14)) > 20,
                "the idle worker icon is drawn");
        // One peasant, standing about.
        assertEquals(1, SidePanel.idleWorkers(scene.world(), 0).size());
    }
}
