package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
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
 * What the sidebar and the top bar look like, at every size and in every state.
 *
 * <p>The sidebar is not one panel: it is nine different panels depending on
 * what is selected, drawn at four different interface sizes, and the states
 * that go wrong are never the one that was being looked at when the code was
 * written. A single unit reads well and a caster's mana bar hangs off the
 * bottom; a group of nine fills the grid and a group of twelve writes its
 * overflow count off the panel; the resource bar is right at the size it was
 * measured at and jams three counts into the corner at every other.
 *
 * <p>So this draws all of them, writes each one out to {@code target/qa} to be
 * looked at, and asserts the two things a pair of eyes is bad at: that nothing
 * is drawn outside the panel it belongs to, and that nothing is drawn on top of
 * anything else.
 */
class SidePanelVisualTest {

    private static final String MAP = "campaigns/human/level02h";

    /**
     * The window the panels are drawn in.
     *
     * <p>Tall enough that the whole sidebar is on the screen at four times the
     * interface size: the column is 480 design pixels from the menu button to
     * the bottom of the command grid, so at four times it wants 1920 of window
     * and anything less cuts the grid off. Four times in this window is the
     * 640 by 480 the game was designed at, one to one is a 2560 pixel monitor,
     * and the two sizes between them are the ordinary cases.
     */
    private static final int WINDOW_WIDTH = 2560;
    private static final int WINDOW_HEIGHT = 1920;

    /** A window narrow enough to crowd the top bar at every size. */
    private static final int NARROW_WINDOW = 1024;

    /** The info panel, in design pixels: {@code UI.InfoPanel} and its art. */
    private static final Rectangle INFO_PANEL = new Rectangle(0, 160, SidePanel.WIDTH, 176);

    private record Scene(World world, SidePanel panel, CommandPanel commands,
            UiLayout.Layout layout, java.util.Map<String, UnitType> types,
            GameData data, int viewWidth, int viewHeight, double scale) {}

    private static GameData data() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    /**
     * A world and a sidebar laid out for one interface size.
     *
     * <p>The design size is the window divided by the scale, which is what
     * {@code GameScreen} hands the layout script: half the bar is measured back
     * from the right edge, so a layout built for the window rather than for the
     * design puts those counts a long way off the end of it.
     */
    private static Scene scene(double scale) {
        return scene(scale, WINDOW_WIDTH);
    }

    /** The same, in a window of a chosen width. */
    private static Scene scene(double scale, int windowWidth) {
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

        int viewWidth = (int) Math.floor(windowWidth / scale);
        int viewHeight = (int) Math.floor(WINDOW_HEIGHT / scale);
        UiLayout.Layout layout = data.uiLayout("human", viewWidth, viewHeight);
        assertNotNull(layout, "the layout script must be readable for any of this to mean much");
        SidePanel panel = new SidePanel(world, data, 0, "human", tilesetName, layout);
        CommandPanel commands = new CommandPanel(world, data, data.userInterface(tilesetName),
                data.upgrades().dependencies(), 0, tilesetName, "human",
                data.unitTypes().types(), layout);
        // Figures wide enough to be a nuisance, which is the case the bar has
        // to survive: five digits of gold and a five digit score.
        world.player(0).set(UnitType.Resource.GOLD, 12345);
        world.player(0).set(UnitType.Resource.WOOD, 9870);
        world.player(0).set(UnitType.Resource.OIL, 5400);
        world.player(0).addScore(87650);
        return new Scene(world, panel, commands, layout, data.unitTypes().types(), data,
                viewWidth, viewHeight, scale);
    }

    /** Draws the chrome the way {@code GameScreen} draws it, scale and all. */
    private static BufferedImage paint(Scene scene, Unit selected) {
        BufferedImage frame = new BufferedImage(
                (int) Math.round(scene.viewWidth() * scene.scale()),
                (int) Math.round(scene.viewHeight() * scene.scale()),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = frame.createGraphics();
        g2.scale(scene.scale(), scene.scale());
        scene.panel().draw(g2, scene.viewWidth(), scene.viewHeight(), selected, 0, 0,
                scene.viewWidth(), scene.viewHeight());
        scene.commands().draw(g2, selected);
        g2.dispose();
        return frame;
    }

    private static void keep(BufferedImage frame, String name) {
        try {
            Path out = Paths.get("target", "qa", name + ".png");
            Files.createDirectories(out.getParent());
            javax.imageio.ImageIO.write(frame, "png", out.toFile());
        } catch (java.io.IOException ignored) {
            // A frame that could not be written is not a failing assertion.
        }
    }

    private static Unit select(World world, Unit... chosen) {
        for (Unit unit : world.unitsSnapshot()) {
            unit.setSelected(false);
        }
        for (Unit unit : chosen) {
            unit.setSelected(true);
        }
        return chosen.length == 0 ? null : chosen[0];
    }

    /**
     * Every state the info panel has, drawn at every interface size.
     *
     * <p>The assertion is the same for all of them: whatever the selection puts
     * on the panel stays on the panel. Everything drawn for a selection is a
     * difference from the same frame with nothing selected, so the difference
     * is exactly the panel's contents and can be measured without knowing what
     * any of it is.
     */
    @Test
    @DisplayName("nothing the info panel draws lands outside the info panel")
    void everyStateStaysInsideItsPanel() {
        for (int step = 1; step <= 4; step++) {
            Scene scene = scene(step);
            // Built first: standing the units up changes the supply and the
            // idle worker count, and the frame with nothing selected has to be
            // of the same world as the frames it is compared with.
            var states = states(scene);
            BufferedImage bare = paint(scene, select(scene.world()));
            keep(bare, "scale" + step + "-empty");

            for (var state : states) {
                BufferedImage frame = paint(scene, select(scene.world(),
                        state.units().toArray(new Unit[0])));
                keep(frame, "scale" + step + "-" + state.name());
                Rectangle outside = firstDifferenceOutside(bare, frame, scene, INFO_PANEL);
                assertTrue(outside == null,
                        "at scale " + step + ", " + state.name() + " drew at "
                                + (outside == null ? "" : outside.x + ", " + outside.y)
                                + ", which is outside the info panel");
            }
        }
    }

    /** One thing that can be selected, and what to call the picture of it. */
    private record State(String name, java.util.List<Unit> units) {}

    /** The selections worth looking at, built in a world that has room. */
    private static java.util.List<State> states(Scene scene) {
        World world = scene.world();
        var types = scene.types();
        java.util.List<State> states = new java.util.ArrayList<>();

        Unit footman = world.createUnit(types.get("unit-footman"), 0, 6, 16);
        assertNotNull(footman, "somewhere to stand a footman");
        states.add(new State("single", java.util.List.of(footman)));

        Unit hurt = world.createUnit(types.get("unit-knight"), 0, 7, 16);
        if (hurt != null) {
            hurt.setHitPoints(Math.max(1, hurt.type().hitPoints() / 5));
            states.add(new State("damaged", java.util.List.of(hurt)));
        }

        Unit mage = world.createUnit(types.get("unit-mage"), 0, 8, 16);
        if (mage != null) {
            mage.setMana(mage.type().mana() * 2 / 3);
            states.add(new State("caster", java.util.List.of(mage)));
        }

        Unit peasant = world.createUnit(types.get("unit-peasant"), 0, 9, 16);
        if (peasant != null) {
            peasant.setCarrying(UnitType.Resource.GOLD);
            peasant.setCarried(100);
            states.add(new State("carrying", java.util.List.of(peasant)));
        }

        // A finished building, which is the case the script's own rows are
        // worst for: it has an armour and a sight and neither of the two
        // figures declared between them, so the two lines came out forty-seven
        // pixels apart with a hole in the middle.
        Unit built = world.createUnit(types.get("unit-human-barracks"), 0, 22, 16);
        if (built != null) {
            states.add(new State("building", java.util.List.of(built)));
        }

        Unit site = world.createUnit(types.get("unit-farm"), 0, 12, 16);
        if (site != null) {
            site.setOrder(Unit.Order.UNDER_CONSTRUCTION);
            site.setProgressGoal(100);
            site.setProgress(47);
            states.add(new State("construction", java.util.List.of(site)));
        }

        Unit hall = world.createUnit(types.get("unit-town-hall"), 0, 16, 16);
        if (hall != null) {
            for (int i = 0; i < 3; i++) {
                world.createUnit(types.get("unit-farm"), 0, 4 + i * 3, 22);
            }
            world.player(0).set(UnitType.Resource.GOLD, 12345);
            world.recalculateSupply();
            if (world.orderTrain(hall, types.get("unit-peasant"))) {
                hall.setProgress(hall.progressGoal() * 3 / 4);
                states.add(new State("training", java.util.List.of(hall)));
            }
        }

        java.util.List<Unit> crowd = new java.util.ArrayList<>();
        for (int i = 0; i < 14 && crowd.size() < 14; i++) {
            Unit made = world.createUnit(types.get("unit-footman"), 0, 4 + i, 26);
            if (made != null) {
                crowd.add(made);
            }
        }
        if (crowd.size() >= 9) {
            states.add(new State("group-9", java.util.List.copyOf(crowd.subList(0, 9))));
        }
        if (crowd.size() >= 12) {
            states.add(new State("group-12", java.util.List.copyOf(crowd.subList(0, 12))));
        }

        Unit transport = world.createUnit(types.get("unit-human-transport"), 0, 20, 3);
        if (transport != null && crowd.size() >= 4) {
            for (int i = 0; i < 4; i++) {
                Unit aboard = crowd.get(i);
                aboard.setCarrier(transport);
                transport.cargo().add(aboard);
            }
            states.add(new State("transport", java.util.List.of(transport)));
        }
        return states;
    }

    /**
     * The first pixel two frames differ at that is outside a design rectangle.
     *
     * <p>In design pixels, so the answer means something at any interface size:
     * the frames are drawn at the scale the screen draws them at, and a
     * difference at scale four is four device pixels across.
     */
    private static Rectangle firstDifferenceOutside(BufferedImage bare, BufferedImage frame,
            Scene scene, Rectangle allowed) {
        double scale = scene.scale();
        for (int y = 0; y < frame.getHeight(); y++) {
            int designY = (int) (y / scale);
            for (int x = 0; x < frame.getWidth(); x++) {
                int designX = (int) (x / scale);
                if (allowed.contains(designX, designY)) {
                    continue;
                }
                // The command grid changes with the selection as well, and it
                // is a panel of its own; so does the minimap, which shows the
                // selection.
                if (designY >= 336 || SidePanel.isOnMinimap(designX, designY)) {
                    continue;
                }
                if (bare.getRGB(x, y) != frame.getRGB(x, y)) {
                    return new Rectangle(designX, designY, 1, 1);
                }
            }
        }
        return null;
    }

    /**
     * The top bar, at every size, with figures wide enough to crowd it.
     *
     * <p>The counts the script measures back from the right edge are the ones
     * that go wrong, and they go wrong quietly: the last of them slides under
     * the sixteen pixel strip of art drawn over the right edge and loses a
     * digit. So the check is arithmetic on where they were actually drawn.
     */
    @Test
    @DisplayName("the top bar's counts never touch each other or the edges")
    void theTopBarHoldsUpAtEverySize() {
        for (int step = 1; step <= 4; step++) {
            Scene scene = scene(step);
            for (int i = 0; i < 30; i++) {
                scene.world().createUnit(scene.types().get("unit-peasant"), 0, 4 + i % 20,
                        30 + i / 20);
            }
            BufferedImage frame = paint(scene, null);
            keep(frame, "scale" + step + "-topbar");

            var cells = SidePanel.layOutTopBar(scene.layout().resources(),
                    widths(scene), scene.viewWidth());
            assertTrue(!cells.isEmpty(), "the bar shows something at scale " + step);
            java.awt.Rectangle previous = null;
            for (var cell : cells) {
                java.awt.Rectangle box = cell.bounds();
                assertTrue(box.x >= SidePanel.WIDTH,
                        "at scale " + step + " the count in slot " + cell.slot()
                                + " starts at " + box.x + ", which is on the sidebar");
                assertTrue(box.x + box.width <= scene.viewWidth() - 16,
                        "at scale " + step + " the count in slot " + cell.slot()
                                + " ends at " + (box.x + box.width) + ", under the right"
                                + " hand strip of a bar " + scene.viewWidth() + " wide");
                if (previous != null) {
                    assertTrue(box.x >= previous.x + previous.width,
                            "at scale " + step + " the count in slot " + cell.slot()
                                    + " starts at " + box.x + ", inside the one before it"
                                    + " which ends at " + (previous.x + previous.width));
                }
                previous = box;
            }
        }
    }

    /**
     * The same bar in the narrowest window the game is meant for.
     *
     * <p>A thousand and twenty-four pixels at four times the interface size is
     * two hundred and fifty-six design pixels of screen, of which the sidebar
     * takes a hundred and seventy-six and the strip down the right another
     * sixteen: sixty-four pixels of bar for six counts. Something has to be
     * left out, and what must not happen is that it is left out by being drawn
     * off the end.
     */
    @Test
    @DisplayName("the top bar gives way rather than overflowing a narrow window")
    void theTopBarGivesWayWhenThereIsNoRoom() {
        for (int step = 1; step <= 4; step++) {
            Scene scene = scene(step, NARROW_WINDOW);
            BufferedImage frame = paint(scene, null);
            keep(frame, "narrow" + step + "-topbar");

            var cells = SidePanel.layOutTopBar(scene.layout().resources(),
                    widths(scene), scene.viewWidth());
            java.awt.Rectangle previous = null;
            for (var cell : cells) {
                java.awt.Rectangle box = cell.bounds();
                assertTrue(box.x >= SidePanel.WIDTH
                                && box.x + box.width <= scene.viewWidth() - 16,
                        "in a " + NARROW_WINDOW + " window at scale " + step
                                + " slot " + cell.slot() + " covers " + box
                                + " on a bar of " + scene.viewWidth());
                if (previous != null) {
                    assertTrue(box.x >= previous.x + previous.width,
                            "slot " + cell.slot() + " overlaps the count before it");
                }
                previous = box;
            }
            // Gold is the one that never gives way: a player who cannot see it
            // cannot tell whether they can afford anything at all.
            assertTrue(cells.stream().anyMatch(cell -> cell.slot() == 0),
                    "the gold count survives at scale " + step + " in a "
                            + NARROW_WINDOW + " window");
        }
    }

    /**
     * The gold line round a resource icon, and where it may not go.
     *
     * <p>The strip of marble the counts sit on is sixteen pixels tall with a
     * two pixel moulding down each edge, and the icon the script declares is
     * fourteen. Drawn at the size and the height the script asks for, the gold
     * line round the icon crossed the moulding at the top and at the bottom and
     * stood proud of the marble -- at every interface size, since both numbers
     * grow together, which is why it never looked like a scaling problem.
     *
     * <p>Checked in the frame rather than in the arithmetic, and at all four
     * sizes: the colour is unmistakable and it either appears in those rows or
     * it does not.
     */
    @Test
    @DisplayName("the gold line round each count's icon stays inside the marble")
    void theIconBordersSitInsideTheStrip() {
        for (int step = 1; step <= 4; step++) {
            Scene scene = scene(step);
            BufferedImage frame = paint(scene, null);
            keep(frame, "scale" + step + "-topbar-icons");

            var cells = SidePanel.layOutTopBar(scene.layout().resources(),
                    widths(scene), scene.viewWidth());
            Rectangle field = new Rectangle(SidePanel.WIDTH, SidePanel.TOP_BAR_BEVEL,
                    scene.viewWidth() - SidePanel.WIDTH - 16,
                    SidePanel.TOP_BAR_HEIGHT - SidePanel.TOP_BAR_BEVEL * 2);
            for (var cell : cells) {
                Rectangle icon = new Rectangle(cell.iconX(), cell.iconY(),
                        SidePanel.RESOURCE_ICON, SidePanel.RESOURCE_ICON);
                assertTrue(field.contains(icon),
                        "at scale " + step + " the icon of slot " + cell.slot() + " covers "
                                + icon + ", which is not inside the marble field " + field);
            }

            // And in the frame: no pixel of the icon's own gold anywhere in the
            // moulding, at either edge, at any size. Over the columns the icons
            // occupy rather than the whole bar, because the counts beside them
            // are drawn in a yellow that passes through this gold on its way to
            // the dark background, one antialiased pixel at a time.
            int bevel = (int) Math.round(SidePanel.TOP_BAR_BEVEL * scene.scale());
            int barBottom = (int) Math.round(SidePanel.TOP_BAR_HEIGHT * scene.scale());
            for (var cell : cells) {
                int left = (int) Math.round(cell.iconX() * scene.scale());
                int right = (int) Math.round(
                        (cell.iconX() + SidePanel.RESOURCE_ICON) * scene.scale());
                for (int y = 0; y < barBottom; y++) {
                    if (y >= bevel && y < barBottom - bevel) {
                        continue;
                    }
                    for (int x = left; x < right && x < frame.getWidth(); x++) {
                        assertTrue(!isIconGold(frame.getRGB(x, y)),
                                "at scale " + step + " the gold line round the icon of slot "
                                        + cell.slot() + " reaches row " + y + " of a bar "
                                        + barBottom + " device pixels tall");
                    }
                }
            }
        }
    }

    /** {@code ResourceIcons.FRAME}: the gold the icon borders are drawn in. */
    private static boolean isIconGold(int rgb) {
        return Math.abs(((rgb >> 16) & 0xFF) - 150) < 14
                && Math.abs(((rgb >> 8) & 0xFF) - 122) < 14
                && Math.abs((rgb & 0xFF) - 52) < 14;
    }

    /** How wide each figure draws, measured in the face the panel uses. */
    private static int[] widths(Scene scene) {
        GameFont face = GameFont.load(scene.data(), GameFont.Face.GAME);
        String[] counts = {"12345", "9870", "5400", "48/50", "87650", null, "30"};
        int[] widths = new int[counts.length];
        for (int i = 0; i < counts.length; i++) {
            widths[i] = counts[i] == null ? -1 : face.widthOf(counts[i]);
        }
        return widths;
    }

    /**
     * The explaining panel a count shows when the pointer rests on it.
     *
     * <p>Drawn for each of the six, because the one that goes wrong is the one
     * at the right hand end: a box wide enough to be worth reading, hung off a
     * count twenty pixels from the edge of the screen, has to come back inside.
     */
    @Test
    @DisplayName("every count explains itself, inside the screen")
    void theTooltipsAreDrawnAndFitOnScreen() {
        for (int step = 1; step <= 4; step += 3) {
            Scene scene = scene(step);
            scene.world().createUnit(scene.types().get("unit-peasant"), 0, 6, 16);
            BufferedImage bare = paint(scene, null);
            java.util.List<Integer> explained = new java.util.ArrayList<>();
            for (int slot = 0; slot < 7; slot++) {
                var box = boundsOf(scene, slot);
                if (box == null) {
                    continue;
                }
                scene.panel().setPointer(new java.awt.Point(box.x + 2, box.y + 2));
                BufferedImage frame = paint(scene, null);
                keep(frame, "scale" + step + "-tooltip-" + slot);
                Rectangle drawn = changedArea(bare, frame, scene.scale());
                assertNotNull(drawn, "slot " + slot + " says something when pointed at");
                assertTrue(drawn.x >= 0 && drawn.y >= 0,
                        "the box for slot " + slot + " starts at " + drawn.x + ", " + drawn.y);
                assertTrue(drawn.x + drawn.width <= scene.viewWidth(),
                        "the box for slot " + slot + " ends at " + (drawn.x + drawn.width)
                                + " on a screen " + scene.viewWidth() + " wide");
                assertTrue(drawn.y + drawn.height <= scene.viewHeight(),
                        "the box for slot " + slot + " ends below the screen");
                explained.add(slot);
                scene.panel().setPointer(null);
            }
            // Every count the bar found room for says what it means. How many
            // that is depends on the room: at four times the interface size in
            // this window the whole bar is a hundred and twenty pixels.
            var shown = SidePanel.layOutTopBar(scene.layout().resources(),
                    widths(scene), scene.viewWidth());
            assertEquals(shown.size(), explained.size(),
                    "every count on the bar explains itself");
            if (step == 1) {
                assertEquals(6, explained.size(),
                        "at one to one the bar holds all six counts");
            }
        }
    }

    /** Where a count was drawn, by asking the panel what is under the point. */
    private static Rectangle boundsOf(Scene scene, int slot) {
        for (int x = SidePanel.WIDTH; x < scene.viewWidth(); x++) {
            if (scene.panel().countAt(x, 4) == slot) {
                return new Rectangle(x, 0, 1, SidePanel.TOP_BAR_HEIGHT);
            }
        }
        return null;
    }

    /** The design-pixel rectangle two frames differ in. */
    private static Rectangle changedArea(BufferedImage bare, BufferedImage frame, double scale) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < frame.getHeight(); y++) {
            for (int x = 0; x < frame.getWidth(); x++) {
                if (bare.getRGB(x, y) != frame.getRGB(x, y)) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < 0) {
            return null;
        }
        return new Rectangle((int) (minX / scale), (int) (minY / scale),
                (int) Math.ceil((maxX - minX + 1) / scale),
                (int) Math.ceil((maxY - minY + 1) / scale));
    }
}
