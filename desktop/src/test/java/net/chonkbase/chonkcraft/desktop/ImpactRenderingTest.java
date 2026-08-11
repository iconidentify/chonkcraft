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
import net.chonkbase.chonkcraft.data.source.AssetSource;
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
 * A shot arrives somewhere, and it shows.
 *
 * <p>Two things a player would notice, neither of which reached the screen.
 * Eleven of the shipped missile types name an explosion to leave where they
 * land -- {@code missile-impact} for a catapult and a ballista, a cannon-tower
 * burst for the three cannons, {@code missile-explosion} for dragon breath, a
 * gryphon's hammer and a fireball -- and {@code ImpactMissile} was parsed and
 * had no readers anywhere in the tree, so every shot in the game simply
 * vanished on arrival. And a catapult boulder is declared
 * {@code missile-class-parabolic}, twice over: the preference that chooses the
 * class defaulted to false in this implementation so the class parsed as point-to-point,
 * and the class did nothing anyway.
 *
 * <p>This paints the real {@link GameScreen} over a real campaign map. The
 * catapult is parked outside the viewport, so the only thing that can differ
 * between two frames is what its shot is doing, which is what lets each test
 * say "every pixel that changed is the thing under test" rather than "something
 * changed".
 */
class ImpactRenderingTest {

    private static final String MAP = "campaigns/human/level02h";
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static final int TILE = 32;

    private record Scene(GameScreen screen, World world, Unit catapult, int targetX, int targetY) {}

    private static GameData data() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install configured. Set -Dchonkcraft.pack or wc2.install.dir.");
        return new GameData(assets);
    }

    /** A catapult parked past the right-hand edge, shelling a square in view. */
    private static Scene scene(GameData data) {
        PudMap pud = data.campaignMap(MAP);
        Assumptions.assumeTrue(pud != null, "no campaign map available");
        GameData.LoadedTileset tileset = data.loadTileset(pud.tileset());
        World world = new World(GameMap.from(pud, tileset.tileset()), Player.from(pud));
        world.setUpgrades(data.upgrades().upgrades());
        world.setMissileTypes(data.missiles().types());
        world.setUnitTypes(data.unitTypes().types());

        UnitType catapultType = data.unitTypes().types().get("unit-catapult");
        assertNotNull(catapultType, "the shipped data has a catapult");
        int range = Math.max(2, catapultType.maxAttackRange());

        int firstColumnOutside = WIDTH / TILE + 1;
        int lastVisibleColumn = WIDTH / TILE - 1;
        Unit catapult = null;
        int[] found = null;
        for (int y = 4; y < Math.min(11, world.map().height() - 2) && catapult == null; y++) {
            for (int x = firstColumnOutside;
                    x <= Math.min(lastVisibleColumn + range, world.map().width() - 2); x++) {
                if (x - range < 1) {
                    continue;
                }
                catapult = world.createUnit(catapultType, 0, x, y);
                if (catapult != null) {
                    found = new int[] {x, y};
                    break;
                }
            }
        }
        Assumptions.assumeTrue(catapult != null, "nowhere to park a catapult off the edge");
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
        return new Scene(screen, world, catapult, found[0] - range, found[1]);
    }

    private static BufferedImage paint(GameScreen screen) {
        BufferedImage frame = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        var g = frame.createGraphics();
        screen.paint(g);
        g.dispose();
        return frame;
    }

    @Test
    @DisplayName("the boulder's explosion is drawn where the boulder landed")
    void theImpactExplosionReachesTheScreen() {
        GameData data = data();
        Scene scene = scene(data);
        World world = scene.world();

        var impactType = data.missiles().types().get("missile-catapult-rock").impactMissile();
        assertEquals("missile-impact", impactType,
                "the shipped catapult rock names its crater");

        BufferedImage empty = paint(scene.screen());
        assertTrue(world.orderAttackGround(scene.catapult(), scene.targetX(), scene.targetY()));

        // One shot only. Attack-ground fires once a second and a boulder is
        // longer than a second in the air, so left running there would always
        // be a second boulder overhead when the first one landed, and the frame
        // could not be attributed to the explosion alone.
        boolean fired = false;
        for (int cycle = 0; cycle < 400 && !fired; cycle++) {
            world.tick();
            fired = !world.missiles().isEmpty();
        }
        assertTrue(fired, "the catapult never fired");
        world.orderStop(scene.catapult());

        Missile impact = null;
        for (int cycle = 0; cycle < 400 && impact == null; cycle++) {
            world.tick();
            if (world.missiles().size() == 1
                    && "missile-impact".equals(world.missiles().get(0).type().ident())) {
                impact = world.missiles().get(0);
            }
        }
        assertNotNull(impact,
                "nothing was left where the boulder landed: ImpactMissile is parsed and "
                        + "read by nothing, so a catapult shot vanishes on arrival");

        BufferedImage landed = paint(scene.screen());

        int frameWidth = impact.type().frameWidth();
        int frameHeight = impact.type().frameHeight();
        int left = (int) Math.round(impact.x()) - frameWidth / 2;
        int top = (int) Math.round(impact.y()) - frameHeight / 2;

        int changed = 0;
        int strayX = -1;
        int strayY = -1;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                if (empty.getRGB(x, y) == landed.getRGB(x, y)) {
                    continue;
                }
                changed++;
                if (x < left || x >= left + frameWidth || y < top || y >= top + frameHeight) {
                    strayX = x;
                    strayY = y;
                }
            }
        }
        assertTrue(changed > 20,
                "only " + changed + " pixels changed where the boulder landed: nothing "
                        + "was drawn at the point of impact");
        assertEquals(-1, strayX,
                "a pixel changed at " + strayX + "," + strayY + ", outside the explosion's "
                        + "frame at " + left + "," + top + " " + frameWidth + "x" + frameHeight);
    }

    @Test
    @DisplayName("the boulder draws retail's high arc frame in mid-flight")
    void aParabolicShotDrawsRetailsHighArcFrame() {
        GameData data = data();
        var rock = data.missiles().types().get("missile-catapult-rock");
        assertNotNull(rock);
        // Both halves of finding 8. The preference that picks the class
        // defaulted to false here and the shipped prelude sets it true, so the
        // boulder parsed as an ordinary point-to-point missile.
        assertEquals(MissileClass.PARABOLIC, rock.missileClass(),
                "missiles.legacy-declaration chooses missile-class-parabolic when EnhancedEffects is on, "
                        + "and chonkcraft/scripts/legacyEngine.legacy-declaration defaults it on");

        Scene scene = scene(data);
        World world = scene.world();
        BufferedImage empty = paint(scene.screen());
        assertTrue(world.orderAttackGround(scene.catapult(), scene.targetX(), scene.targetY()));

        // BNE 0x00410260 keeps the impact coordinate on the flight line and
        // depicts height with flattened frames 0,5,10,5,0. In Java those are
        // animation rows 0,1,2,1,0 over the five stored facings.
        Missile highest = null;
        for (int cycle = 0; cycle < 60; cycle++) {
            world.tick();
            for (Missile missile : world.missiles()) {
                if ("missile-catapult-rock".equals(missile.type().ident())
                        && missile.frame() == 2
                        && missile.x() > 3 * TILE && missile.x() < WIDTH - 3 * TILE) {
                    highest = missile;
                }
            }
            if (highest != null && world.missiles().size() == 1
                    && world.missiles().get(0) == highest) {
                break;
            }
        }
        assertNotNull(highest, "the catapult never put a boulder in view");
        assertEquals(2, highest.frame(), "mid-flight did not select native frame 10");
        // BattleNetMissileMotionTest owns the coordinate invariant with a
        // horizontal, jitter-free fixture.  This real-data rendering scene
        // includes BNE's constructor aim and muzzle offsets, so its absolute
        // y is not the target-row centre; what matters here is that frame 10
        // is visible exactly where the simulated projectile says it is.

        BufferedImage flying = paint(scene.screen());
        int frameWidth = highest.type().frameWidth();
        int frameHeight = highest.type().frameHeight();
        int left = (int) Math.round(highest.x()) - frameWidth / 2;
        int top = (int) Math.round(highest.y()) - frameHeight / 2;
        int changed = 0;
        int strayX = -1;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                if (empty.getRGB(x, y) != flying.getRGB(x, y)) {
                    changed++;
                    if (x < left || x >= left + frameWidth
                            || y < top || y >= top + frameHeight) {
                        strayX = x;
                    }
                }
            }
        }
        assertTrue(changed > 20, "retail's high boulder frame was not drawn at all");
        assertEquals(-1, strayX,
                "the high boulder frame changed pixels outside its declared rectangle");
    }
}
