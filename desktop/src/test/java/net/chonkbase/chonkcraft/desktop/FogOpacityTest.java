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
import net.chonkbase.chonkcraft.engine.ui.FogOfWarSettings;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How dark the fog is comes from the script, and can be seen on the screen.
 *
 * <p>{@code SetFogOfWarOpacityLevels(0x7F, 0xBE, 0xFE)} used to run into
 * nothing while the same three numbers sat in {@link FogTiles} and in
 * {@code GameScreen} as constants. The two agreed, so nothing looked wrong; the
 * point is that the script could not change anything. That is not a property a
 * test can check by reading a constant, so this paints real frames and measures
 * them: feed the renderer a different set of levels and the pixels have to move.
 *
 * <p>{@link FogRenderingTest} is the same rig for the shape of the fog. This is
 * about its depth.
 */
class FogOpacityTest {

    private static final String MAP = "campaigns/human/level02h";
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static final int TILE = 32;

    private record Scene(GameScreen screen, World world, IndexedImage sheet,
            BufferedImage terrain) {}

    private static GameData data() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    private static Scene scene(GameData data) {
        PudMap pud = data.campaignMap(MAP);
        Assumptions.assumeTrue(pud != null, "no campaign map available");
        GameData.LoadedTileset tileset = data.loadTileset(pud.tileset());
        World world = new World(GameMap.from(pud, tileset.tileset()), Player.from(pud));
        world.setUnitTypes(data.unitTypes().types());
        world.setUpgrades(data.upgrades().upgrades());
        world.setMissileTypes(data.missiles().types());

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
        return new Scene(screen, world, tileset.sheet(), terrain);
    }

    /** Hands the screen a set of levels the way the launcher does. */
    private static void apply(Scene scene, FogOfWarSettings.Levels levels) {
        scene.screen().setFogTiles(FogTiles.from(scene.sheet(), levels));
        scene.screen().setFogOpacity(levels);
    }

    private static BufferedImage paint(GameScreen screen) {
        BufferedImage frame = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        var g = frame.createGraphics();
        screen.paint(g);
        g.dispose();
        return frame;
    }

    private static int brightness(BufferedImage frame, int x, int y) {
        int rgb = frame.getRGB(x, y);
        return ((rgb >> 16 & 0xFF) + (rgb >> 8 & 0xFF) + (rgb & 0xFF)) / 3;
    }

    /**
     * A patch of ground the player has seen and is no longer watching, and a
     * square they have never been near. The two layers of fog, one of each.
     */
    private record Ground(int rememberedX, int rememberedY, int unseenX, int unseenY) {}

    private static Ground prepare(Scene scene, GameData data) {
        World world = scene.world();
        UnitType footman = data.unitTypes().types().get("unit-footman");
        assertNotNull(footman);
        Unit placed = null;
        for (int y = 6; y < 12 && placed == null; y++) {
            for (int x = 6; x < 12; x++) {
                placed = world.createUnit(footman, 0, x, y);
                if (placed != null) {
                    break;
                }
            }
        }
        Assumptions.assumeTrue(placed != null, "nowhere to stand a footman");
        world.tick();

        // A lit square well inside the lit patch. A square on the rim is
        // legitimately covered by the black mask of the ground beside it, so it
        // says nothing about the veil.
        int litX = -1;
        int litY = -1;
        for (int y = 1; y < HEIGHT / TILE - 1 && litX < 0; y++) {
            for (int x = 1; x < WIDTH / TILE - 1; x++) {
                boolean interior = true;
                for (int dy = -1; dy <= 1 && interior; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (!world.fog().isVisible(0, x + dx, y + dy)) {
                            interior = false;
                            break;
                        }
                    }
                }
                if (interior) {
                    litX = x;
                    litY = y;
                    break;
                }
            }
        }
        Assumptions.assumeTrue(litX >= 0, "nothing was lit well away from an edge");

        for (Unit unit : world.unitsSnapshot()) {
            if (unit.player() == 0) {
                world.remove(unit);
            }
        }
        world.tick();
        Assumptions.assumeTrue(!world.fog().isVisible(0, litX, litY));
        Assumptions.assumeTrue(world.fog().isExplored(0, litX, litY));

        int unseenX = WIDTH / TILE - 1;
        int unseenY = HEIGHT / TILE - 1;
        Assumptions.assumeTrue(!world.fog().isExplored(0, unseenX, unseenY));
        return new Ground(litX, litY, unseenX, unseenY);
    }

    /**
     * The veil over remembered ground is black at the explored alpha, so what
     * survives of the terrain is {@code (255 - alpha) / 255} of it. Halve the
     * alpha and twice as much terrain comes through. Nothing about that is
     * visible from a constant, which is the point.
     */
    @Test
    @DisplayName("the veil is as deep as the script says it is")
    void changingTheExploredLevelChangesTheFrame() {
        GameData data = data();
        Scene scene = scene(data);
        Ground ground = prepare(scene, data);
        int x = ground.rememberedX() * TILE + 16;
        int y = ground.rememberedY() * TILE + 16;
        int bare = brightness(scene.terrain(), x, y);
        Assumptions.assumeTrue(bare > 40, "the terrain there is too dark to measure against");

        apply(scene, data.fogOfWar().levels());
        int shipped = brightness(paint(scene.screen()), x, y);

        apply(scene, new FogOfWarSettings.Levels(0x20, 0xBE, 0xFE));
        int lighter = brightness(paint(scene.screen()), x, y);

        apply(scene, new FogOfWarSettings.Levels(0xE0, 0xE8, 0xFE));
        int darker = brightness(paint(scene.screen()), x, y);

        assertTrue(lighter > shipped + 10 && shipped > darker + 10,
                "remembered ground came out at " + lighter + ", " + shipped + " and "
                        + darker + " for explored levels 0x20, 0x7F and 0xE0. The renderer"
                        + " is not reading the levels at all if these do not separate");

        // And it is not merely responding: it is the alpha the script named.
        // A black veil at alpha a over terrain b leaves b * (255 - a) / 255.
        assertEquals(bare * (255 - 0x20) / 255, lighter, 6,
                "the veil at 0x20 is not the depth 0x20 means");
        assertEquals(bare * (255 - 0xE0) / 255, darker, 6,
                "the veil at 0xE0 is not the depth 0xE0 means");
    }

    /**
     * The same for the other layer. Ground never seen is filled at the unseen
     * level, which the shipped script puts at 0xFE rather than at a flat 0xFF:
     * a sliver of terrain survives and the edge of the map reads as dark rather
     * than as a hole cut in the screen.
     */
    @Test
    @DisplayName("ground never seen is filled at the unseen level")
    void changingTheUnseenLevelChangesTheFrame() {
        GameData data = data();
        Scene scene = scene(data);
        Ground ground = prepare(scene, data);
        int x = ground.unseenX() * TILE + 16;
        int y = ground.unseenY() * TILE + 16;
        int bare = brightness(scene.terrain(), x, y);
        Assumptions.assumeTrue(bare > 40, "the terrain there is too dark to measure against");

        apply(scene, data.fogOfWar().levels());
        int shipped = brightness(paint(scene.screen()), x, y);
        assertTrue(shipped < 6,
                "ground never seen should be all but black at the shipped 0xFE, and is at "
                        + shipped);

        apply(scene, new FogOfWarSettings.Levels(0x7F, 0x80, 0x90));
        int thin = brightness(paint(scene.screen()), x, y);
        assertEquals(bare * (255 - 0x90) / 255, thin, 6,
                "an unseen level of 0x90 should leave " + (bare * (255 - 0x90) / 255)
                        + " of the terrain showing and left " + thin + ", so the fill is"
                        + " still using a number of its own");
    }

    /** The masks are baked at the levels they were handed, not at remembered ones. */
    @Test
    @DisplayName("the masks are baked at the script's levels")
    void theMasksTakeTheirAlphaFromTheScript() {
        GameData data = data();
        GameData.LoadedTileset tileset = data.loadTileset(PudMap.Tileset.FOREST);
        var levels = new FogOfWarSettings.Levels(0x30, 0x40, 0x50);
        FogTiles tiles = FogTiles.from(tileset.sheet(), levels);

        int explored = -1;
        int unseen = -1;
        for (int y = 0; y < 32 && explored < 0; y++) {
            for (int x = 0; x < 32; x++) {
                int alpha = tiles.explored(1).getRGB(x, y) >>> 24;
                if (alpha != 0) {
                    explored = alpha;
                    unseen = tiles.unseen(1).getRGB(x, y) >>> 24;
                    break;
                }
            }
        }
        assertEquals(0x30, explored, "the fringe mask ignored the level it was given");
        assertEquals(0x50, unseen, "the black mask ignored the level it was given");
        assertEquals(levels, tiles.levels());
    }
}
