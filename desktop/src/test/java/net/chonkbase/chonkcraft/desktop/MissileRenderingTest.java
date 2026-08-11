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
import net.chonkbase.chonkcraft.engine.missile.Missile;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.unit.SpriteFrame;
import net.chonkbase.chonkcraft.engine.map.MapRenderer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Projectiles are drawn.
 *
 * <p>The world simulated every arrow, axe, boulder and bolt correctly --
 * travel time, splash, friendly fire, all of it -- and the renderer drew none
 * of them. {@code World.missiles()} had one caller in the repository and it was
 * a test, so damage arrived out of nowhere and a catapult firing looked exactly
 * like a catapult standing still. Nothing failed, because nothing was watching
 * the screen.
 *
 * <p>This watches the screen. It paints the real {@link GameScreen} twice over
 * a map with nothing else on it -- once before a shot is fired and once with
 * the boulder mid-flight -- and requires that the second frame differ from the
 * first, that the difference sit exactly where the world says the boulder is,
 * and that nothing else on the frame have moved. Delete the call to
 * {@code drawMissiles} and the first of those fails; draw the wrong frame of
 * the sheet, or at the wrong place, and the second does.
 */
class MissileRenderingTest {

    private static final String MAP = "campaigns/human/level02h";

    /** A window small enough that a unit can be parked outside it. */
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

    /**
     * A catapult parked off the right-hand edge, shooting back into view.
     *
     * <p>Off the edge on purpose. The camera sits at the origin and the
     * viewport is 640 by 480, so a unit past the twentieth column is culled and
     * the only thing that can change between two frames is the boulder. That is
     * what lets the test say "every pixel that changed is the missile" rather
     * than "something, somewhere, changed".
     */
    private static Scene scene(GameData data) {
        PudMap pud = data.campaignMap(MAP);
        Assumptions.assumeTrue(pud != null, "no campaign map available");
        GameData.LoadedTileset tileset = data.loadTileset(pud.tileset());
        World world = new World(GameMap.from(pud, tileset.tileset()), Player.from(pud));
        world.setUpgrades(data.upgrades().upgrades());
        world.setMissileTypes(data.missiles().types());

        UnitType catapultType = data.unitTypes().types().get("unit-catapult");
        assertNotNull(catapultType, "the shipped data has a catapult");
        int range = Math.max(2, catapultType.maxAttackRange());

        // Somewhere for the catapult to stand that is past the right-hand edge
        // of the viewport, with the square it shells still inside it. Only the
        // catapult's own tile has to be passable: a boulder flies over water
        // and trees like anything else, and the ground it crosses is drawn the
        // same in both frames whatever it is.
        int firstColumnOutside = WIDTH / TILE + 1;
        int lastVisibleColumn = WIDTH / TILE - 1;
        Unit catapult = null;
        int[] found = null;
        // Not against the top of the viewport. A catapult rock is a
        // missile-class-parabolic and now actually arcs, and the arc for a
        // full-range shot is four tiles high -- fired from row two it leaves
        // the frame entirely on the way over, and "no pixels changed" would
        // read as "the missile was not drawn".
        for (int y = 6; y < Math.min(14, world.map().height() - 2) && catapult == null; y++) {
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

        IndexedImage scene = new MapRenderer(tileset.tileset(), tileset.sheet())
                .render(world.map().width(), world.map().height(), world.map().tileCodes());
        BufferedImage terrain = scene.toIndexedBufferedImage(tileset.palette());

        String tilesetName = pud.tileset() == PudMap.Tileset.FOREST
                ? "summer"
                : pud.tileset().name().toLowerCase(java.util.Locale.ROOT);
        GameScreen screen = new GameScreen(world, data, terrain, tileset.palette(),
                tilesetName, 0, WIDTH, HEIGHT, null, null, null, null, null,
                java.util.List.of(), "human");
        screen.setSize(WIDTH, HEIGHT);
        // No chrome and no zoom, so a world pixel is a screen pixel and the
        // test can say where a missile ought to have landed on the frame.
        // Setting it after the size stops the screen rebuilding one on paint.
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
    @DisplayName("a boulder in the air is drawn, where the world says it is")
    void aMissileInFlightReachesTheScreen() {
        GameData data = data();
        Scene scene = scene(data);
        World world = scene.world();

        BufferedImage empty = paint(scene.screen());

        assertTrue(world.orderAttackGround(scene.catapult(), scene.targetX(), scene.targetY()),
                "a catapult can be told to shell a square");

        // Fly it far enough in that the whole frame is on screen, so a missing
        // edge cannot be mistaken for a missing missile.
        Missile boulder = null;
        for (int i = 0; i < 400 && boulder == null; i++) {
            world.tick();
            for (Missile missile : world.missiles()) {
                // Clear of every edge, not just the sides: the arc moves it up
                // the frame as well as across it.
                if (missile.x() > 3 * TILE && missile.x() < WIDTH - 3 * TILE
                        && missile.y() > 3 * TILE && missile.y() < HEIGHT - 3 * TILE) {
                    boulder = missile;
                }
            }
        }
        assertNotNull(boulder, "the catapult never put a boulder in the air");
        assertEquals(1, world.missiles().size(), "exactly one shot should be up");

        BufferedImage flying = paint(scene.screen());

        // Where the drawing ought to be: the frame the type declares, centred
        // on the missile, with the camera at the origin.
        int frameWidth = boulder.type().frameWidth();
        int frameHeight = boulder.type().frameHeight();
        int left = (int) Math.round(boulder.x()) - frameWidth / 2;
        int top = (int) Math.round(boulder.y()) - frameHeight / 2;

        int changed = 0;
        int strayX = -1;
        int strayY = -1;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                if (empty.getRGB(x, y) == flying.getRGB(x, y)) {
                    continue;
                }
                changed++;
                if (x < left || x >= left + frameWidth || y < top || y >= top + frameHeight) {
                    strayX = x;
                    strayY = y;
                }
            }
        }

        // The whole point: something was drawn. Nothing consumes
        // world.missiles() and this is zero.
        assertTrue(changed > 20,
                "only " + changed + " pixels changed with a boulder in the air: "
                        + "the missile was not drawn");
        assertEquals(-1, strayX,
                "a pixel changed at " + strayX + "," + strayY + ", outside the boulder's frame at "
                        + left + "," + top + " " + frameWidth + "x" + frameHeight
                        + ": the missile is being drawn somewhere other than where it is");
    }

    @Test
    @DisplayName("a hit-class missile is drawn as its damage number")
    void aDamageFigureIsDrawnAsText() {
        GameData data = data();
        Scene scene = scene(data);
        World world = scene.world();
        world.setDamageMissile("missile-hit");

        UnitType footman = data.unitTypes().types().get("unit-footman");
        assertNotNull(footman);
        Unit victim = world.createUnit(footman, 0, 10, 10);
        Unit attacker = world.createUnit(footman, 1, 12, 10);
        BufferedImage before = paint(scene.screen());

        world.hit(attacker, victim);
        Missile figure = world.missiles().stream()
                .filter(missile -> missile.type().missileClass()
                        == net.chonkbase.chonkcraft.engine.missile.MissileClass.HIT)
                .findFirst().orElse(null);
        assertNotNull(figure, "the hit did not create its feedback missile");
        BufferedImage after = paint(scene.screen());

        int changed = 0;
        int left = (int) Math.round(figure.x());
        int top = (int) Math.round(figure.y());
        for (int y = Math.max(0, top - 2); y < Math.min(HEIGHT, top + 24); y++) {
            for (int x = Math.max(0, left - 2); x < Math.min(WIDTH, left + 48); x++) {
                if (before.getRGB(x, y) != after.getRGB(x, y)) {
                    changed++;
                }
            }
        }
        assertTrue(changed > 10,
                "a missile-class-hit changed only " + changed + " pixels: its number was not drawn");
    }

    @Test
    @DisplayName("the boulder tumbles rather than spinning through its facings")
    void theAnimationRunsOverStepsNotFacings() {
        GameData data = data();
        var catapultRock = data.missiles().types().get("missile-catapult-rock");
        assertNotNull(catapultRock);

        // Fifteen frames, nine directions. Five of the fifteen are the drawn
        // facings of one moment, so the animation is three steps and not
        // fifteen. Reading it the other way sends a boulder through every
        // compass point on the way to its target.
        assertEquals(5, catapultRock.storedFacings());
        assertEquals(3, catapultRock.animationSteps());

        var sheet = data.sprite(catapultRock.sprite());
        assertNotNull(sheet, "the archive holds the boulder sheet");
        assertEquals(catapultRock.storedFacings() * catapultRock.frameWidth(), sheet.width(),
                "one row of the sheet is one frame per stored facing");
        assertEquals(catapultRock.animationSteps() * catapultRock.frameHeight(), sheet.height(),
                "one row of the sheet is one step of the animation");

        // An arrow is the degenerate case that catches the mistake: its five
        // frames are five facings and no animation at all.
        var arrow = data.missiles().types().get("missile-arrow");
        assertEquals(1, arrow.animationSteps(), "an arrow does not animate in flight");
        assertEquals(200, data.sprite(arrow.sprite()).width());
        assertEquals(40, data.sprite(arrow.sprite()).height());
    }

    @Test
    @DisplayName("a shallow BNE arrow keeps one rendered facing for its complete flight")
    void aShallowBattleNetArrowKeepsOneRenderedFacingForItsCompleteFlight() {
        GameData data = data();
        var arrow = data.missiles().types().get("missile-arrow");
        assertNotNull(arrow, "the native missile catalog has the arrow");

        // Human 13's tower shot is 48 pixels east and 72 north. Retail writes
        // projectile +0x0a once in FUN_0040fb10. Its integer flight then
        // alternates vertical and diagonal micro-steps, but 0x004101f0 never
        // rewrites that facing byte. The old Java renderer visibly flickered
        // between north and north-east because motion changed direction after
        // every one of those unequal steps.
        Missile shot = new Missile(arrow, null, null, 3872, 1152, 3920, 1080);
        shot.enableBattleNetMotion(12, 0);
        var launched = renderedMissileFrame(shot);
        assertEquals(1, launched.index(), "the launch picture is north-east");

        for (int step = 1; step <= 7; step++) {
            shot.step();
            assertEquals(launched, renderedMissileFrame(shot),
                    "rendered sprite cell/mirror changed on BNE motion step " + step);
        }
    }

    /** The sprite-cell formula used by {@link GameScreen#drawMissiles}. */
    private static SpriteFrame.Resolved renderedMissileFrame(Missile missile) {
        return GameScreen.missileSpriteFrame(missile);
    }
}
