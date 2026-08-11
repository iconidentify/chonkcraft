package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.imageio.ImageIO;
import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.MapRenderer;
import net.chonkbase.chonkcraft.engine.missile.Missile;
import net.chonkbase.chonkcraft.engine.missile.MissileClass;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A damaged building is seen to burn.
 *
 * <p>The simulation half of this is covered in the engine's own tests; what
 * those cannot say is whether any of it reaches the screen. A fire is an
 * ordinary missile as far as the renderer is concerned, so it ought to be
 * drawn for free -- and "ought to be drawn for free" is exactly the kind of
 * claim that turns out to be wrong because the missile list the renderer reads
 * is a snapshot published at a point in the cycle the fire was never in, or
 * because the sheet is cut by facings the fire does not have.
 *
 * <p>So this paints the real {@link GameScreen} with a damaged town hall on it
 * and requires that the flames land on the building rather than a tile away,
 * and that they be the size of a fire rather than of a single pixel. It writes
 * the frame out to {@code target/burning-building.png} as well, because the
 * cheapest check on a thing that is meant to look right is to look at it.
 */
class BurningBuildingRenderingTest {

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

    private record Scene(GameScreen screen, World world, Unit hall, Unit attacker) {}

    /**
     * A town hall in the top-left corner of the map, with a footman of its own
     * player beside it.
     *
     * <p>The footman belongs to the same player so that nothing attacks
     * anything on its own account: every blow in this test is dealt by the
     * test, which is what lets it say what the building's health is at the
     * moment it paints.
     */
    private static Scene scene(GameData data) {
        PudMap pud = data.campaignMap(MAP);
        Assumptions.assumeTrue(pud != null, "no campaign map available");
        GameData.LoadedTileset tileset = data.loadTileset(pud.tileset());
        World world = new World(GameMap.from(pud, tileset.tileset()), Player.from(pud));
        world.setUnitTypes(data.unitTypes().types());
        world.setUpgrades(data.upgrades().upgrades());
        world.setMissileTypes(data.missiles().types());

        UnitType hallType = data.unitTypes().types().get("unit-town-hall");
        assertNotNull(hallType, "the shipped data has a town hall");

        // Anywhere inside the viewport with room for four squares by four and
        // a footman beside them. Which square is not interesting; being on
        // screen with the camera at the origin is.
        Unit hall = null;
        Unit attacker = null;
        for (int y = 2; y < HEIGHT / TILE - 6 && hall == null; y++) {
            for (int x = 2; x < WIDTH / TILE - 6; x++) {
                hall = world.createUnit(hallType, 0, x, y);
                if (hall != null) {
                    attacker = world.createUnit(
                            data.unitTypes().types().get("unit-footman"), 0, x + 5, y);
                    break;
                }
            }
        }
        Assumptions.assumeTrue(hall != null, "nowhere on this map to put a town hall");
        Assumptions.assumeTrue(attacker != null, "nowhere to put something to hit it with");
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
        // test can say where the flames belong on the frame.
        screen.setLayout((net.chonkbase.chonkcraft.engine.ui.UiLayout.Layout) null);
        screen.setGameScale(1);
        return new Scene(screen, world, hall, attacker);
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
            // under test; the assertions below still stand on their own.
        }
    }

    /** The one fire the world is carrying. */
    private static Missile fire(World world) {
        Missile found = null;
        for (Missile missile : world.missiles()) {
            if (missile.type().missileClass() == MissileClass.FIRE) {
                assertTrue(found == null, "more than one fire on one building");
                found = missile;
            }
        }
        return found;
    }

    @Test
    @DisplayName("a burning building is drawn burning, on the building")
    void theFireReachesTheScreen() {
        GameData data = data();
        Scene scene = scene(data);
        World world = scene.world();
        Unit hall = scene.hall();

        // Two thirds health, which the shipped table calls a small fire. The
        // health is set rather than beaten down so that the two frames below
        // differ by the fire alone: a health bar drawn at a different width
        // would show up as a stray pixel and mean nothing.
        int damaged = hall.type().hitPoints() * 2 / 3;
        hall.setHitPoints(damaged);
        world.tick();
        BufferedImage unburnt = paint(scene.screen());
        save(unburnt, "burning-building-before.png");

        world.hit(scene.attacker(), hall);
        world.tick();
        hall.setHitPoints(damaged);

        Missile flames = fire(world);
        assertNotNull(flames, "the blow did not light the building");
        assertEquals("missile-small-fire", flames.type().ident());

        BufferedImage burning = paint(scene.screen());
        save(burning, "burning-building.png");

        int frameWidth = flames.type().frameWidth();
        int frameHeight = flames.type().frameHeight();
        int left = (int) Math.round(flames.x()) - frameWidth / 2;
        int top = (int) Math.round(flames.y()) - frameHeight / 2;

        int changed = 0;
        int inside = 0;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                if (unburnt.getRGB(x, y) == burning.getRGB(x, y)) {
                    continue;
                }
                changed++;
                if (x >= left && x < left + frameWidth && y >= top && y < top + frameHeight) {
                    inside++;
                }
            }
        }

        // Something was drawn, and it is the size of a fire. The small fire's
        // frame is 32 by 48 and the flames fill a good part of it, so a couple
        // of hundred pixels is a fire and twenty is a stray highlight.
        assertTrue(inside > 150,
                "only " + inside + " pixels changed inside the fire's own frame: the fire"
                        + " was not drawn, or was drawn somewhere else");
        // And it was drawn where the fire is. A cycle passed between the two
        // frames, so a unit's own animation may have moved a pixel or two;
        // what must not happen is the flames landing off the building.
        assertTrue(inside * 10 >= changed * 9,
                inside + " of " + changed + " changed pixels are inside the fire's frame at "
                        + left + "," + top + " " + frameWidth + "x" + frameHeight
                        + ": the fire is being drawn somewhere other than where it is");

        // The building is four by four. Its flames belong over it, not beside
        // it -- which is the failure mode if the offset from HitUnit_Burning
        // is taken as a corner rather than as the middle.
        int buildingLeft = hall.tileX() * TILE;
        int buildingTop = hall.tileY() * TILE;
        int buildingRight = buildingLeft + hall.type().tileWidth() * TILE;
        int buildingBottom = buildingTop + hall.type().tileHeight() * TILE;
        assertTrue(flames.x() >= buildingLeft && flames.x() <= buildingRight,
                "the fire is at x=" + flames.x() + ", outside the building's "
                        + buildingLeft + " to " + buildingRight);
        assertTrue(flames.y() >= buildingTop && flames.y() <= buildingBottom,
                "the fire is at y=" + flames.y() + ", outside the building's "
                        + buildingTop + " to " + buildingBottom);
    }

    @Test
    @DisplayName("a building beaten down further burns bigger, on the same spot")
    void theBigFireIsDrawnToo() {
        GameData data = data();
        Scene scene = scene(data);
        World world = scene.world();
        Unit hall = scene.hall();

        hall.setHitPoints(hall.type().hitPoints() * 2 / 3);
        world.hit(scene.attacker(), hall);
        world.tick();
        Missile flames = fire(world);
        assertNotNull(flames);
        double smallX = flames.x();
        double smallY = flames.y();

        // Down to a fifth, which the shipped table calls the big fire. The
        // sheets differ in size -- 32 by 48 against 48 by 48 -- and in frame
        // count, so this is the case that catches a renderer that cached
        // either from the type it first saw.
        hall.setHitPoints(hall.type().hitPoints() / 5);
        for (int i = 0; i < 40; i++) {
            world.tick();
        }
        Missile grown = fire(world);
        assertNotNull(grown, "the fire went out when it should have grown");
        assertEquals("missile-big-fire", grown.type().ident());
        assertEquals(smallX, grown.x(), 0.01, "growing must not move the fire sideways");
        assertEquals(smallY, grown.y(), 0.01, "growing must not move the fire off the roof");

        BufferedImage frame = paint(scene.screen());
        save(frame, "burning-building-big.png");

        // Drawn, and drawn as the bigger sheet: count the flame-coloured
        // pixels over the building. The small fire cannot fill this much.
        int frameWidth = grown.type().frameWidth();
        int frameHeight = grown.type().frameHeight();
        int left = (int) Math.round(grown.x()) - frameWidth / 2;
        int top = (int) Math.round(grown.y()) - frameHeight / 2;
        int flame = 0;
        for (int y = Math.max(0, top); y < Math.min(HEIGHT, top + frameHeight); y++) {
            for (int x = Math.max(0, left); x < Math.min(WIDTH, left + frameWidth); x++) {
                int rgb = frame.getRGB(x, y);
                int red = rgb >> 16 & 0xFF;
                int green = rgb >> 8 & 0xFF;
                int blue = rgb & 0xFF;
                if (red > 150 && red > green + 40 && red > blue + 60) {
                    flame++;
                }
            }
        }
        assertTrue(flame > 100,
                "only " + flame + " flame-coloured pixels over the building: a keep at a"
                        + " fifth of its health should be well alight");
    }
}
