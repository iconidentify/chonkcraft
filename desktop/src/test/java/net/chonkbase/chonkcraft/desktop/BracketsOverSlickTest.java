package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 * A selected ship's corner brackets sit on top of the slick it floats over.
 *
 * <p>Reported from play with a screenshot: a tanker moored on an oil patch
 * with its green brackets swallowed by the slick. The brackets were drawn in
 * a single pass before any sprite, which put them under everything -- an
 * overcorrection of the earlier fault where each box was drawn after its own
 * unit and landed on the farm behind it. Upstream's {@code CUnit::Draw}
 * calls {@code DrawUnitSelection} right before its own sprite inside the
 * sorted draw loop: under its own unit, under
 * whatever draws later, and over whatever drew earlier -- and the slick, at
 * {@code DrawLevel = 5} against the ship's 40, draws earlier.
 *
 * <p>The measurement: the same selected tanker painted with the patch under
 * it and with the patch removed must show the same number of bracket-green
 * pixels. Fewer with the patch means the slick is being laid over the
 * brackets.
 */
class BracketsOverSlickTest {

    private static final String MAP = "campaigns/human/level03h";
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;

    private static GameData data() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    @Test
    @DisplayName("the brackets survive the slick: as many green pixels over oil as over water")
    void theBracketsSitAboveTheSlick() {
        GameData data = data();
        PudMap pud = data.campaignMap(MAP);
        Assumptions.assumeTrue(pud != null, "no campaign map available");
        GameData.LoadedTileset tileset = data.loadTileset(pud.tileset());
        World world = new World(GameMap.from(pud, tileset.tileset()), Player.from(pud));
        world.setUnitTypes(data.unitTypes().types());
        world.setMissileTypes(data.missiles().types());
        data.populate(world, pud);
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
        screen.setLayout((net.chonkbase.chonkcraft.engine.ui.UiLayout.Layout) null);
        screen.setGameScale(1);

        Unit patch = null;
        for (Unit unit : world.units()) {
            if (unit.type() != null && "unit-oil-patch".equals(unit.type().ident())) {
                patch = unit;
                break;
            }
        }
        assertNotNull(patch, "the mission has no oil patch");
        UnitType tankerType = data.unitTypes().types().get("unit-human-oil-tanker");
        Unit tanker = world.createUnit(tankerType, 0, patch.tileX() + 1, patch.tileY() + 1);
        assertNotNull(tanker, "no ship would sit on the patch");
        tanker.setSelected(true);
        screen.centreOn(tanker.tileX(), tanker.tileY());

        int overSlick = bracketGreen(paint(screen), screen, tanker);
        world.remove(patch);
        int overWater = bracketGreen(paint(screen), screen, tanker);

        assertTrue(overWater > 0,
                "no bracket green anywhere around a selected tanker on open water, so"
                        + " this measurement is not seeing the marker at all");
        assertEquals(overWater, overSlick,
                "the slick swallows the brackets: " + overSlick + " green pixels over"
                        + " the patch against " + overWater + " over open water. The box"
                        + " must be drawn inside the sorted loop, over the slick that"
                        + " drew before it");
    }

    /** Pure bracket green inside the tanker's box, camera applied. */
    private static int bracketGreen(BufferedImage frame, GameScreen screen, Unit unit) {
        java.awt.Rectangle box = screen.selectionBoxForTest(unit);
        int found = 0;
        for (int y = box.y - 1; y <= box.y + box.height + 1; y++) {
            for (int x = box.x - 1; x <= box.x + box.width + 1; x++) {
                if (x < 0 || y < 0 || x >= frame.getWidth() || y >= frame.getHeight()) {
                    continue;
                }
                if ((frame.getRGB(x, y) & 0xFFFFFF) == 0x00FF00) {
                    found++;
                }
            }
        }
        return found;
    }

    private static BufferedImage paint(GameScreen screen) {
        BufferedImage frame = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        var g = frame.createGraphics();
        screen.paint(g);
        g.dispose();
        return frame;
    }
}
