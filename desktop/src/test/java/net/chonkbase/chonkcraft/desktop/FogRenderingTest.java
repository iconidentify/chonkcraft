package net.chonkbase.chonkcraft.desktop;

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
 * The fog on a real frame, not in the abstract.
 *
 * <p>{@link net.chonkbase.chonkcraft.engine.map.FogOfWar} chooses the right mask
 * and {@link FogTiles} reads it out of the data, and both are covered on their
 * own. Neither would have caught the actual bug the player saw, which was that
 * the renderer ignored both and filled squares. So this paints the real screen
 * and looks at the pixels.
 *
 * <p>The property that separates tiled fog from filled squares, stated so a
 * machine can check it: along the boundary between lit and hidden ground there
 * must be columns of pixels that are <em>partly</em> covered. Fill squares and
 * every 32-pixel column is either wholly fogged or wholly clear, and the count
 * of partial ones is zero. That is what "blocky" means, measured.
 */
class FogRenderingTest {

    private static final String MAP = "campaigns/human/level02h";
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static final int TILE = 32;

    private record Scene(GameScreen screen, World world, GameData data,
            BufferedImage terrain) {}

    private static GameData data() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    private static Scene scene(GameData data, boolean withFogTiles) {
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
        if (withFogTiles) {
            screen.setFogTiles(FogTiles.from(tileset.sheet()));
        }
        return new Scene(screen, world, data, terrain);
    }

    private static BufferedImage paint(GameScreen screen) {
        BufferedImage frame = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        var g = frame.createGraphics();
        screen.paint(g);
        g.dispose();
        return frame;
    }

    /** Roughly how dark a pixel is, so "fogged" can be asked of one. */
    private static int brightness(BufferedImage frame, int x, int y) {
        int rgb = frame.getRGB(x, y);
        return ((rgb >> 16 & 0xFF) + (rgb >> 8 & 0xFF) + (rgb & 0xFF)) / 3;
    }

    /**
     * How much darker one pixel of the frame is than the same pixel of the
     * bare terrain.
     *
     * <p>Measuring the frame's own brightness does not work and the control
     * test below is what proved it: trees are dark and snow is bright, so a
     * tile of ordinary terrain contains both and reads as "partly covered"
     * whatever the fog is doing. Differencing against the map the screen drew
     * from isolates the fog and nothing else. The camera is at the origin at
     * scale one, so a frame pixel is a terrain pixel.
     */
    private static int fogAt(Scene scene, BufferedImage frame, int x, int y) {
        return brightness(scene.terrain(), x, y) - brightness(frame, x, y);
    }

    /**
     * How many tiles the fog covers only part of.
     *
     * <p>This is the whole difference between tiled fog and filled squares,
     * stated so a machine can check it. Every square the masks touch is partly
     * covered; if fog is drawn by filling squares then each one is covered
     * completely or not at all, and this count collapses to nothing.
     */
    private static int partiallyCoveredTiles(Scene scene, BufferedImage frame) {
        int partial = 0;
        for (int tileY = 0; tileY < HEIGHT / TILE; tileY++) {
            for (int tileX = 0; tileX < WIDTH / TILE; tileX++) {
                boolean covered = false;
                boolean clear = false;
                for (int y = 0; y < TILE; y++) {
                    for (int x = 0; x < TILE; x++) {
                        int fog = fogAt(scene, frame, tileX * TILE + x, tileY * TILE + y);
                        if (fog > 20) {
                            covered = true;
                        } else if (fog <= 2) {
                            clear = true;
                        }
                    }
                }
                if (covered && clear) {
                    partial++;
                }
            }
        }
        return partial;
    }

    /**
     * A unit somewhere near the top-left, so the viewport holds both the disc
     * it lights and the unexplored ground around it.
     */
    private static void lightAPatch(World world, GameData data) {
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
    }

    @Test
    @DisplayName("The fog edge is not a staircase")
    void theEdgeIsTiledRatherThanFilled() {
        GameData data = data();
        Scene scene = scene(data, true);
        lightAPatch(scene.world(), scene.data());
        BufferedImage frame = paint(scene.screen());

        int partial = partiallyCoveredTiles(scene, frame);
        assertTrue(partial >= 8,
                "only " + partial + " tiles on the frame are partly covered by fog. A fog"
                        + " edge drawn with the tileset's masks produces a ring of them"
                        + " around every lit patch; filling whole squares produces none,"
                        + " which is the staircase this exists to catch");
    }

    /**
     * The same frame without the masks handed over, to prove the measurement
     * above distinguishes the two rather than passing on anything. This is the
     * old behaviour, and it must fail the property the new one passes.
     */
    @Test
    @DisplayName("Filling squares instead would fail that measurement")
    void theMeasurementCatchesTheOldBehaviour() {
        GameData data = data();
        Scene scene = scene(data, false);
        lightAPatch(scene.world(), scene.data());
        BufferedImage frame = paint(scene.screen());

        int partial = partiallyCoveredTiles(scene, frame);
        assertTrue(partial <= 4,
                "the square-filling fallback produced " + partial + " partly covered tiles,"
                        + " so the measurement does not actually tell the two apart and the"
                        + " test above proves nothing");
    }

    /**
     * A scouted building reaches the screen.
     *
     * <p>The engine's side of this is covered by {@code SeenBuildingsTest};
     * what that cannot tell you is whether the renderer ever reads the
     * memories. So the frame is painted twice, once with the memories and once
     * with them dropped, and the difference must lie inside the footprint of
     * the building that was scouted. Forget to call {@code drawRemembered} and
     * the two frames are identical.
     */
    @Test
    @DisplayName("A remembered building is painted where it stood")
    void aRememberedBuildingIsDrawn() {
        GameData data = data();
        Scene scene = scene(data, true);
        World world = scene.world();

        UnitType hall = data.unitTypes().types().get("unit-town-hall");
        assertNotNull(hall);
        // An enemy building on screen, and a scout of ours beside it.
        Unit building = null;
        int atX = -1;
        int atY = -1;
        for (int y = 4; y < 10 && building == null; y++) {
            for (int x = 4; x < 12; x++) {
                building = world.createUnit(hall, 1, x, y);
                if (building != null) {
                    atX = x;
                    atY = y;
                    break;
                }
            }
        }
        Assumptions.assumeTrue(building != null, "nowhere to put a hall");
        Unit scout = null;
        for (int dx = 0; dx < 6 && scout == null; dx++) {
            scout = world.createUnit(data.unitTypes().types().get("unit-footman"),
                    0, atX + 4 + dx, atY + 1);
        }
        Assumptions.assumeTrue(scout != null, "nowhere to stand a scout");
        world.tick();
        Assumptions.assumeTrue(world.fog().isVisible(0, atX, atY), "the scout never saw it");

        world.remove(scout);
        world.tick();
        Assumptions.assumeTrue(world.seenBuildings().size(0) > 0, "nothing was remembered");

        BufferedImage remembered = paint(scene.screen());
        world.seenBuildings().clear(0);
        BufferedImage forgotten = paint(scene.screen());

        int changed = 0;
        int outsideFootprint = 0;
        int left = atX * TILE;
        int top = atY * TILE;
        int right = left + Math.max(1, hall.tileWidth()) * TILE;
        int bottom = top + Math.max(1, hall.tileHeight()) * TILE;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                if (remembered.getRGB(x, y) != forgotten.getRGB(x, y)) {
                    changed++;
                    // The sprite is wider than the footprint it stands on, so
                    // a margin either side is expected; a whole tile is not.
                    if (x < left - TILE || x >= right + TILE
                            || y < top - TILE || y >= bottom + TILE) {
                        outsideFootprint++;
                    }
                }
            }
        }
        assertTrue(changed > 200,
                "dropping the player's memories changed " + changed + " pixels, so the"
                        + " remembered building was never being drawn");
        assertTrue(outsideFootprint == 0,
                outsideFootprint + " of the changed pixels are nowhere near where the"
                        + " building stood");
    }

    /**
     * Ground never visited is solid; ground remembered is veiled but still
     * shows its terrain. Conflating the two is the classic fog mistake and
     * would leave a player unable to tell explored ground from unexplored.
     */
    @Test
    @DisplayName("Unexplored ground is solid and remembered ground is not")
    void theTwoLayersAreDistinct() {
        GameData data = data();
        Scene scene = scene(data, true);
        World world = scene.world();
        lightAPatch(world, scene.data());

        // A lit square well inside the lit patch, not on its rim. A rim square
        // is legitimately covered by the black mask of the unexplored ground
        // beside it, so its middle pixel says nothing about the veil.
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

        BufferedImage frame = paint(scene.screen());
        int remembered = fogAt(scene, frame, litX * TILE + 16, litY * TILE + 16);

        // A square well away from anything the footman ever saw.
        int unexploredX = WIDTH / TILE - 1;
        int unexploredY = HEIGHT / TILE - 1;
        Assumptions.assumeTrue(!world.fog().isExplored(0, unexploredX, unexploredY));
        int unexploredBrightness =
                brightness(frame, unexploredX * TILE + 16, unexploredY * TILE + 16);

        assertTrue(unexploredBrightness < 6,
                "ground never seen should be near black, and is at brightness "
                        + unexploredBrightness);
        assertTrue(remembered > 8,
                "remembered ground is darkened by only " + remembered + ", so it is not"
                        + " veiled at all");
        int terrainThere = brightness(scene.terrain(), litX * TILE + 16, litY * TILE + 16);
        assertTrue(remembered < terrainThere,
                "remembered ground is darkened by " + remembered + " out of a possible "
                        + terrainThere + ", which is the whole way: the player cannot tell it"
                        + " apart from ground they have never seen");
    }
}
