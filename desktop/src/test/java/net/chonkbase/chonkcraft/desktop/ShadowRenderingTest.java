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
 * A flyer casts a shadow on the ground it flies over.
 *
 * <p>Eight shipped types declare one -- every one of them
 * {@code ShadowDefinition(scale)} from {@code scripts/units.legacy-declaration:32}, a cell
 * of the shared {@code missiles/unit_shadow.png} pushed south-west of the
 * wearer -- and {@code UnitType.shadowFile} was parsed and read by nothing,
 * with the size, offset and frame not even kept. A gryphon and a dragon flew
 * with nothing under them, which is the one visual cue that a thing is in
 * the air rather than standing very still.
 *
 * <p>The measurement is a pair of frames: the same gryphon with its
 * declaration as shipped and with the file blanked, which is the old
 * behaviour run through the same paint. They must differ inside the square
 * the declared offset puts the shadow in, and nowhere else -- which also
 * pins the shadow to where {@code DrawShadow}
 * puts it rather than merely somewhere.
 */
class ShadowRenderingTest {

    private static final String MAP = "campaigns/human/level02h";
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static final int TILE = 32;

    private static GameData data() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    private record Scene(GameScreen screen, World world, GameData data) {}

    private static Scene scene() {
        GameData data = data();
        PudMap pud = data.campaignMap(MAP);
        Assumptions.assumeTrue(pud != null, "no campaign map available");
        GameData.LoadedTileset tileset = data.loadTileset(pud.tileset());
        World world = new World(GameMap.from(pud, tileset.tileset()), Player.from(pud));
        world.setUnitTypes(data.unitTypes().types());
        world.setUpgrades(data.upgrades().upgrades());
        world.setMissileTypes(data.missiles().types());
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
        return new Scene(screen, world, data);
    }

    private static BufferedImage paint(GameScreen screen) {
        BufferedImage frame = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        var g = frame.createGraphics();
        screen.paint(g);
        g.dispose();
        return frame;
    }

    /** The bounding box of differences outside the shadow's own square. */
    private static String outsideBox(BufferedImage a, BufferedImage b,
            int boxX, int boxY, int boxW, int boxH) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (x >= boxX && x < boxX + boxW && y >= boxY && y < boxY + boxH) {
                    continue;
                }
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return maxX < 0 ? "nowhere" : minX + "," + minY + " to " + maxX + "," + maxY;
    }

    /** Pixels differing between two frames inside a box. */
    private static int differing(BufferedImage a, BufferedImage b,
            int left, int top, int width, int height) {
        int changed = 0;
        for (int y = Math.max(0, top); y < Math.min(a.getHeight(), top + height); y++) {
            for (int x = Math.max(0, left); x < Math.min(a.getWidth(), left + width); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    changed++;
                }
            }
        }
        return changed;
    }

    @Test
    @DisplayName("a gryphon rider's shadow lies south-west of it, and only there")
    void aFlyerCastsItsShadow() {
        Scene scene = scene();
        UnitType gryphon = scene.data().unitTypes().types().get("unit-gryphon-rider");
        assertNotNull(gryphon, "the shipped data has a gryphon rider");
        Assumptions.assumeTrue(!gryphon.shadowFile().isEmpty(),
                "the gryphon declares no shadow, so there is nothing to draw");
        assertTrue(gryphon.shadowSpriteFrame() > 0 && gryphon.shadowHeight() > 0,
                "the declaration parsed a file and lost the frame and size, which is the"
                        + " fault this test exists for: nothing drawable was kept");

        Unit flyer = scene.world().createUnit(gryphon, 0, 8, 6);
        assertNotNull(flyer, "nowhere to put the gryphon");

        BufferedImage shadowed = paint(scene.screen());
        // The same frame with the declaration blanked is the old behaviour.
        gryphon.setShadowFile("");
        BufferedImage bare = paint(scene.screen());

        // Where DrawShadow puts it: centred on the footprint -- the gryphon
        // is a two-by-two -- then the declared offset, {-9, 30} for
        // ShadowDefinition(2).
        int footprintW = Math.max(1, gryphon.tileWidth()) * TILE;
        int footprintH = Math.max(1, gryphon.tileHeight()) * TILE;
        int x = flyer.pixelX() - (gryphon.shadowWidth() - footprintW) / 2
                + gryphon.shadowOffsetX();
        int y = flyer.pixelY() - (gryphon.shadowHeight() - footprintH) / 2
                + gryphon.shadowOffsetY();
        int inside = differing(shadowed, bare, x, y,
                Math.max(1, gryphon.shadowWidth()), Math.max(1, gryphon.shadowHeight()));
        assertTrue(inside > 20,
                "blanking the shadow declaration changed " + inside + " pixels in the"
                        + " square the declared offset puts the shadow in: the shadow"
                        + " is not being drawn there");

        // And nowhere else: the two frames agree away from the shadow's box,
        // so what changed is the shadow rather than the whole picture.
        int elsewhere = differing(shadowed, bare, 0, 0, WIDTH, HEIGHT) - inside;
        assertEquals(0, elsewhere,
                elsewhere + " pixels changed outside the shadow's own square at "
                        + outsideBox(shadowed, bare, x, y, gryphon.shadowWidth(),
                                gryphon.shadowHeight())
                        + ", so the difference between the frames is not the shadow");
    }
}
