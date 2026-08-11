package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.MapRenderer;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Health bars sit on the unit, not on the ground below it.
 *
 * <p>The bar was positioned from the sprite frame -- {@code drawY +
 * frameHeight - 4} -- and a Warcraft II sprite is routinely much taller than
 * the ground the unit stands on. A peasant is a 72-pixel picture over a single
 * 32-pixel tile, so its bar was drawn twenty pixels below its feet on bare
 * grass; a catapult's was sixteen below; a gryphon rider's eight. A farm's was
 * right, because a farm's picture happens to be exactly its footprint, which
 * is why the fault survived: on buildings, which is what most damage tests
 * look at, nothing was wrong.
 *
 * <p>Upstream positions every bar decoration by {@code OffsetPercent =
 * {50, 100}} of the <em>tile footprint</em> -- see the {@code DefineDecorations}
 * calls in {@code chonkcraft/scripts/ui.legacy-declaration} and {@code DrawDecoration}, which multiplies the percentage by
 * {@code TileWidth * PixelTileSize} and never looks at the sprite at all. That
 * offset exists precisely because the two sizes differ.
 *
 * <p>This paints the real {@link GameScreen} with four units of four different
 * sprite-to-footprint ratios standing in a row, once healthy and once hurt,
 * and requires that each bar's top row land on the bottom of that unit's
 * footprint. Three of the four have a sprite taller than their footprint, so
 * three of the four assertions fail on the old arithmetic; the fourth is the
 * control that says the measurement is finding a real bar rather than a
 * coincidence in the terrain.
 */
class UnitDecorationRenderingTest {

    private static final String MAP = "campaigns/human/level02h";
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static final int TILE = 32;

    /** {@link java.awt.Color#GREEN}, which is what a bar above two thirds is painted. */
    private static final int BAR_GREEN = 0xFF00FF00;

    /** The bar's declared height, {@code Size = {31, 4}} in {@code ui.legacy-declaration}. */
    private static final int BAR_HEIGHT = 4;

    private static GameData data() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    private record Scene(GameScreen screen, World world, List<Unit> units) {}

    /**
     * Four units of different builds, spread across the viewport.
     *
     * <p>Chosen for the ratio between the sprite and the footprint, which is
     * the whole subject: a peasant is 72 over 32, a catapult 64 over 32, a
     * gryphon rider 80 over 64 and a farm 64 over 64. All belong to the same
     * player so that nothing fights and the only thing that changes between
     * the two frames is the damage the test deals itself.
     */
    private static Scene scene(GameData data) {
        PudMap pud = data.campaignMap(MAP);
        Assumptions.assumeTrue(pud != null, "no campaign map available");
        GameData.LoadedTileset tileset = data.loadTileset(pud.tileset());
        World world = new World(GameMap.from(pud, tileset.tileset()), Player.from(pud));
        world.setUnitTypes(data.unitTypes().types());
        world.setUpgrades(data.upgrades().upgrades());
        world.setMissileTypes(data.missiles().types());

        List<Unit> units = new ArrayList<>();
        String[] wanted = {"unit-peasant", "unit-catapult", "unit-gryphon-rider", "unit-farm"};
        int column = 1;
        for (String ident : wanted) {
            UnitType type = data.unitTypes().types().get(ident);
            assertNotNull(type, "the shipped data has a " + ident);
            Unit placed = null;
            // Left to right across the top of the viewport, each type given
            // its own band of columns so no two bars can be confused.
            for (int x = column; x < WIDTH / TILE - 4 && placed == null; x++) {
                for (int y = 2; y < 8 && placed == null; y++) {
                    placed = world.createUnit(type, 0, x, y);
                    if (placed != null) {
                        column = x + Math.max(1, type.tileWidth()) + 2;
                    }
                }
            }
            if (placed != null) {
                units.add(placed);
            }
        }
        Assumptions.assumeTrue(units.size() >= 3,
                "nowhere on this map to stand three units side by side");
        world.fog().revealAll(0);

        IndexedImage rendered = new MapRenderer(tileset.tileset(), tileset.sheet())
                .render(world.map().width(), world.map().height(), world.map().tileCodes());
        BufferedImage terrain = rendered.toIndexedBufferedImage(tileset.palette());
        String tilesetName = pud.tileset() == PudMap.Tileset.FOREST
                ? "summer"
                : pud.tileset().name().toLowerCase(java.util.Locale.ROOT);

        GameScreen screen = new GameScreen(world, data, terrain, tileset.palette(),
                tilesetName, 0, WIDTH, HEIGHT, null, null, null, null, null,
                java.util.List.of(), "human");
        screen.setSize(WIDTH, HEIGHT);
        // No chrome and no zoom, so a world pixel is a screen pixel and the
        // test can say which row of the frame a bar belongs on.
        screen.setLayout((net.chonkbase.chonkcraft.engine.ui.UiLayout.Layout) null);
        screen.setGameScale(1);
        return new Scene(screen, world, units);
    }

    private static BufferedImage paint(GameScreen screen) {
        BufferedImage frame = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        var g = frame.createGraphics();
        screen.paint(g);
        g.dispose();
        return frame;
    }

    private static void save(BufferedImage frame, String name) {
        try {
            Path out = Paths.get("target");
            Files.createDirectories(out);
            ImageIO.write(frame, "png", out.resolve(name).toFile());
        } catch (IOException e) {
            // A frame that could not be written is not a failure of the thing
            // under test; the assertions below stand on their own.
        }
    }

    /**
     * The topmost row inside the search band holding a horizontal run of bar
     * green, or -1.
     *
     * <p>A run rather than a pixel, because a lone green pixel is something
     * the terrain can produce and eight in a row is not. The band is the
     * unit's own footprint columns and sixty-four rows either side of it, which
     * is wide enough to catch the bar wherever the two candidate formulas would
     * put it -- so a failure reads as "the bar is at the wrong height" rather
     * than "the bar was not found".
     */
    private static int barTop(BufferedImage frame, int left, int width, int top, int bottom) {
        int x0 = Math.max(0, left);
        int x1 = Math.min(frame.getWidth(), left + width);
        for (int y = Math.max(0, top); y < Math.min(frame.getHeight(), bottom); y++) {
            int run = 0;
            for (int x = x0; x < x1; x++) {
                run = frame.getRGB(x, y) == BAR_GREEN ? run + 1 : 0;
                if (run >= 8) {
                    return y;
                }
            }
        }
        return -1;
    }

    @Test
    @DisplayName("a health bar sits on the bottom of the footprint, whatever the sprite's height")
    void barsArePositionedFromTheFootprintNotTheFrame() {
        GameData data = data();
        Scene scene = scene(data);

        // Healthy first. Nothing draws a bar for an undamaged unit, so this
        // frame is the control: it proves the green the second frame finds is
        // the bar and not a shade of the grass.
        BufferedImage healthy = paint(scene.screen());
        save(healthy, "unit-decorations-healthy.png");

        for (Unit unit : scene.units()) {
            int left = unit.pixelX();
            int width = Math.max(1, unit.type().tileWidth()) * TILE;
            int footprintHeight = Math.max(1, unit.type().tileHeight()) * TILE;
            assertEquals(-1,
                    barTop(healthy, left, width, unit.pixelY() - 64,
                            unit.pixelY() + footprintHeight + 64),
                    "bar green found near an undamaged " + unit.type().ident()
                            + ": the terrain contains the colour this test looks for,"
                            + " so the measurement below proves nothing");
        }

        // Nine tenths, which is above the two-thirds line, so every bar is
        // green and every bar is nearly full width.
        for (Unit unit : scene.units()) {
            unit.setHitPoints(Math.max(1, unit.type().hitPoints() * 9 / 10));
        }
        BufferedImage hurt = paint(scene.screen());
        save(hurt, "unit-decorations-hurt.png");

        int taller = 0;
        for (Unit unit : scene.units()) {
            UnitType type = unit.type();
            int footprintWidth = Math.max(1, type.tileWidth()) * TILE;
            int footprintHeight = Math.max(1, type.tileHeight()) * TILE;
            int frameHeight = Math.max(1, type.imageHeight());
            if (frameHeight > footprintHeight) {
                taller++;
            }

            int found = barTop(hurt, unit.pixelX(), footprintWidth,
                    unit.pixelY() - 64, unit.pixelY() + footprintHeight + 64);
            assertTrue(found >= 0, "no health bar drawn for a damaged " + type.ident());

            // OffsetPercent {50, 100} of the footprint, less the sprite's own
            // four rows: the bar's last row is the last row of the footprint.
            int expected = unit.pixelY() + footprintHeight - BAR_HEIGHT;
            assertEquals(expected, found,
                    type.ident() + " (sprite " + frameHeight + " tall over a "
                            + footprintHeight + " footprint) has its bar at row " + found
                            + " rather than " + expected + ", "
                            + (found - expected) + " pixels out");
        }

        assertTrue(taller >= 2,
                "only " + taller + " of these units has a sprite taller than its footprint,"
                        + " so this test cannot tell the two formulas apart");
    }
}
