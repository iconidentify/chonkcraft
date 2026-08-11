package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.MapField;
import net.chonkbase.chonkcraft.engine.map.MapRenderer;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ground a player is looking at is the ground the simulation says is
 * there.
 *
 * <p>A player sent in a screenshot of a woodcutter standing in the middle of a
 * stand of trees, surrounded by intact forest on every side, chopping: "that's
 * not a valid place to cut trees". The worker was not in the wood. It had
 * felled its way in over fourteen game minutes, the square under it carried
 * {@code LAND_ALLOWED} and nothing else, and the pathfinder had never let it
 * near an unpassable square in ninety-eight thousand simulated cycles --
 * {@code PathFinder.enterCost} refuses one outright, before the allowance that
 * lets a route end on a building. Every tree it had cut was gone from the map
 * and still on the screen.
 *
 * <p>Because the ground was rasterised once, at load, into an image the size
 * of the whole map, and nothing ever redrew a square of it. The only thing
 * that regenerated the picture at all was the palette walking for the water.
 * The minimap, which is built from the live map every frame, showed the
 * clearing spreading correctly the whole time, so the two halves of the same
 * screen disagreed for the rest of the game.
 *
 * <p>Which makes the assertion a rendering one. The map's flags were never
 * wrong, so a test of them passes on the broken code; so does a test of the
 * tile codes, which the simulation updates properly. What has to be pinned is
 * the picture.
 *
 * <p>It is pinned by painting the same square twice: once on a screen given
 * the map before the axe fell, and once on a screen given the map afterwards.
 * The second is what the player is entitled to see -- the same renderer, the
 * same fog, the same palette, the only difference being that its ground was
 * rasterised out of a map with the tree already gone. The two frames have to
 * agree, and a third frame of ground nobody has touched has to differ from
 * both, so that agreement cannot be reached by drawing nothing. This is
 * {@code FogRenderingTest}'s inverted control: paint it, paint it again with
 * the one thing changed, and compare, because a frame with a felled tree on it
 * and a frame without one are otherwise both just pixels.
 *
 * <p>Trees are the case the player hit. Walls are the same defect one door
 * along -- {@code GameMap.hitWall} and {@code breakWall} rewrite tile codes at
 * run time exactly as {@code clearWoodTile} does -- so a breach is pinned here
 * too, because a wall knocked down and still drawn standing is the same lie
 * told about different terrain.
 */
class TerrainChangeRenderingTest {

    private static final String MAP = "campaigns/human/level11h";
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static final int TILE = 32;

    private static GameData data() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    private record Scene(GameScreen screen, World world) {}

    /**
     * A real campaign map, painted by a real {@link GameScreen}.
     *
     * <p>The fog is lifted and no unit is placed, so every pixel of the
     * playing field is ground and a square that changes has nothing drawn over
     * it to confuse the comparison.
     *
     * @param beforeRasterising run against the world while the ground image is
     *                          still to be made, which is how a screen that
     *                          has never been wrong about a square is built
     */
    private static Scene scene(GameData data, Consumer<World> beforeRasterising) {
        PudMap pud = data.campaignMap(MAP);
        Assumptions.assumeTrue(pud != null, "no campaign map available");
        GameData.LoadedTileset tileset = data.loadTileset(pud.tileset());
        World world = new World(GameMap.from(pud, tileset.tileset()), Player.from(pud));
        world.setUnitTypes(data.unitTypes().types());
        world.fog().revealAll(0);
        beforeRasterising.accept(world);

        BufferedImage terrain = new MapRenderer(tileset.tileset(), tileset.sheet())
                .render(world.map().width(), world.map().height(), world.map().tileCodes())
                .toIndexedBufferedImage(tileset.palette());
        String tilesetName = pud.tileset() == PudMap.Tileset.FOREST
                ? "summer"
                : pud.tileset().name().toLowerCase(java.util.Locale.ROOT);

        GameScreen screen = new GameScreen(world, data, terrain, tileset.palette(),
                tilesetName, 0, WIDTH, HEIGHT, null, null, null, null, null,
                java.util.List.of(), "human");
        screen.setSize(WIDTH, HEIGHT);
        screen.setLayout((net.chonkbase.chonkcraft.engine.ui.UiLayout.Layout) null);
        screen.setGameScale(1);
        return new Scene(screen, world);
    }

    /** The block of pixels one map square occupies, as the screen paints it. */
    private static int[] paintedSquare(Scene scene, int tileX, int tileY) {
        scene.screen().centreOn(tileX, tileY);
        BufferedImage frame = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = frame.createGraphics();
        scene.screen().paint(g);
        g.dispose();
        int left = tileX * TILE - scene.screen().cameraX();
        int top = tileY * TILE - scene.screen().cameraY();
        assertTrue(left >= 0 && top >= 0 && left + TILE <= WIDTH && top + TILE <= HEIGHT,
                "square " + tileX + "," + tileY + " is off the painted frame");
        return frame.getRGB(left, top, TILE, TILE, null, 0, TILE);
    }

    /** A square of the wanted kind with eight of the same kind round it. */
    private static int[] findSurroundedSquare(GameMap map, Predicate<MapField> wanted) {
        for (int y = 2; y < map.height() - 2; y++) {
            for (int x = 2; x < map.width() - 2; x++) {
                boolean all = true;
                for (int dy = -1; dy <= 1 && all; dy++) {
                    for (int dx = -1; dx <= 1 && all; dx++) {
                        all = wanted.test(map.field(x + dx, y + dy));
                    }
                }
                if (all) {
                    return new int[] {x, y};
                }
            }
        }
        return null;
    }

    /** A square of the wanted kind, anywhere well inside the map. */
    private static int[] findSquare(GameMap map, Predicate<MapField> wanted) {
        for (int y = 2; y < map.height() - 2; y++) {
            for (int x = 2; x < map.width() - 2; x++) {
                if (wanted.test(map.field(x, y))) {
                    return new int[] {x, y};
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------ the report

    @Test
    @DisplayName("a felled tree is drawn as the cleared ground it became")
    void aFelledTreeIsDrawnAsClearedGround() {
        GameData data = data();
        Scene untouched = scene(data, world -> { });
        int[] tree = findSurroundedSquare(untouched.world().map(), MapField::isForest);
        Assumptions.assumeTrue(tree != null, "no stand of trees on " + MAP);
        int x = tree[0];
        int y = tree[1];

        // What World.fellTree does when a worker's fiftieth swing lands:
        // CMap::ClearWoodTile, the square and its eight neighbours together.
        Consumer<World> fell = world -> world.map().clearWoodTile(x, y);

        Scene played = scene(data, world -> { });
        fell.accept(played.world());

        MapField field = played.world().map().field(x, y);
        assertFalse(field.hasFlag(TileFlag.FOREST),
                "the felled square keeps no forest flag");
        assertFalse(field.hasFlag(TileFlag.UNPASSABLE),
                "the felled square is ground a worker may stand on");
        assertNotEquals(untouched.world().map().field(x, y).tile(), field.tile(),
                "felling changed the square's tile code, so there is something to redraw");

        Scene truth = scene(data, fell);
        int[] canopy = paintedSquare(untouched, x, y);
        int[] clearing = paintedSquare(truth, x, y);
        assertFalse(java.util.Arrays.equals(canopy, clearing),
                "trees and the ground they stood on are drawn differently, or this test"
                        + " cannot tell whether the screen followed the map");

        assertArrayEquals(clearing, paintedSquare(played, x, y),
                "the square the trees came off is drawn as the ground the map says it is,"
                        + " not as the canopy that stood there when the map loaded");
    }

    @Test
    @DisplayName("the neighbours a felling repairs are redrawn as well")
    void repairedNeighboursAreRedrawnToo() {
        GameData data = data();
        Scene untouched = scene(data, world -> { });
        int[] tree = findSurroundedSquare(untouched.world().map(), MapField::isForest);
        Assumptions.assumeTrue(tree != null, "no stand of trees on " + MAP);
        int x = tree[0];
        int y = tree[1];
        Consumer<World> fell = world -> world.map().clearWoodTile(x, y);

        Scene played = scene(data, world -> { });
        fell.accept(played.world());
        Scene truth = scene(data, fell);

        // A forest tile is an edge between trees and grass, so taking one
        // square out of the middle changes how the ones round it have to be
        // drawn. That half reaching the screen is what stops a felled square
        // looking like a hole punched in a picture of a wood.
        int repaired = 0;
        for (int i = 0; i < 9; i++) {
            int nx = x - 1 + i % 3;
            int ny = y - 1 + i / 3;
            if (nx == x && ny == y) {
                continue;
            }
            if (played.world().map().field(nx, ny).tile()
                    == untouched.world().map().field(nx, ny).tile()) {
                continue;
            }
            repaired++;
            assertArrayEquals(paintedSquare(truth, nx, ny), paintedSquare(played, nx, ny),
                    "square " + nx + "," + ny + " was re-derived by the felling and is drawn"
                            + " the way the map now says");
        }
        assertTrue(repaired > 1,
                "felling a square in the middle of a wood re-derives some of its neighbours;"
                        + " only " + repaired + " neighbouring tile codes moved, so this"
                        + " proved nothing");
    }

    @Test
    @DisplayName("a wall knocked down is drawn as the breach it became")
    void aBreachedWallIsDrawnAsRubble() {
        GameData data = data();
        Scene untouched = scene(data, world -> { });
        int[] wall = findSquare(untouched.world().map(), MapField::isWall);
        Assumptions.assumeTrue(wall != null, "no wall on " + MAP);
        int x = wall[0];
        int y = wall[1];

        // Enough damage in one blow to spend it: CMap::HitWall reaches
        // RemoveWall, which is the whole of a breach.
        Consumer<World> breach = world -> world.map()
                .hitWall(x, y, world.map().field(x, y).value(), GameMap.WALL_HIT_POINTS);

        Scene played = scene(data, world -> { });
        breach.accept(played.world());

        MapField field = played.world().map().field(x, y);
        assertFalse(field.hasFlag(TileFlag.WALL), "the breached square is no longer wall");
        assertFalse(field.hasFlag(TileFlag.UNPASSABLE), "an army walks through a breach");
        assertNotEquals(untouched.world().map().field(x, y).tile(), field.tile(),
                "the breach changed the square's tile code, so there is something to redraw");

        Scene truth = scene(data, breach);
        assertFalse(java.util.Arrays.equals(
                        paintedSquare(untouched, x, y), paintedSquare(truth, x, y)),
                "a standing wall and a breach are drawn differently, or this test cannot"
                        + " tell whether the screen followed the map");

        assertArrayEquals(paintedSquare(truth, x, y), paintedSquare(played, x, y),
                "the breach is drawn as a breach, and not as the intact wall the map was"
                        + " rasterised with while an army walks through the picture of it");
    }
}
