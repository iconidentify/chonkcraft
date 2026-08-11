package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.save.SaveGame;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The things the simulation gets right that never made it to the screen.
 *
 * <p>A player asked why a troll and an orc warrior disappear when they die
 * instead of falling over. They do it because {@code GameScreen} filtered its
 * draw list through {@code World.isVisibleTo(int, Unit)}, which opens with
 * {@code !unit.isAlive()}, and {@code Unit.isAlive} is false from the cycle the
 * order becomes {@code DYING}. Every death animation in the game ran its
 * hundred-odd cycles unwatched, and the corpse the simulation then laid down --
 * a neutral unit whose order is {@code DYING} for the whole of its life -- had
 * never once been painted. Nor had the rubble a building leaves, which is a
 * corpse type too.
 *
 * <p>None of that was a simulation fault, which is why five audits missed it.
 * focused tests cover the corpse chain under "Checked and found
 * correct", and it is correct: the body is created, owned by the neutral
 * player, put on the right tile, given the right sheet and the right frame,
 * and timed out on its own clock. Nothing had ever asked whether any of that
 * reached a pixel.
 *
 * <p>So this pins {@link RenderingTruth}, which asks exactly that: it plays
 * real campaign missions headlessly, stages a real fight, and for every thing
 * the world says is present, on the map and inside the local player's fog, it
 * paints the frame twice -- once whole and once with that one thing held back
 * -- and requires the two to differ. Every assertion here counts what it
 * measured before it judges it, because a sweep that found nothing would
 * otherwise report a clean run, which is precisely how a corpse went the whole
 * life of this implementation undrawn.
 */
class RenderingTruthTest {

    /**
     * Two of the four the harness table covers.
     *
     * <p>{@code level05h} is the mission the player was watching when they
     * asked, and {@code level08o} is the one with a scouted building to
     * remember. The full table is {@code RenderingTruth.main}, for the reason
     * {@code AiCompetenceTest} gives about its own subset: a failure naming one
     * of two missions is easier to act on than a total over fifty-two.
     */
    private static final List<String> MISSIONS =
            List.of("campaigns/human/level05h", "campaigns/orc/level08o");

    private static GameData data() {
        GameData data = RenderingTruth.data();
        Assumptions.assumeTrue(data != null,
                "No Warcraft II assets configured. Set CHONKCRAFT_ASSET_PACK or"
                        + " -Dwc2.install.dir=/path/to/game.");
        return data;
    }

    @Test
    @DisplayName("painting retail frames cannot change simulation state")
    void paintingIsReadOnly() throws Exception {
        GameData data = data();
        String missionPath = "campaigns/human/level05h";
        RenderingTruth.Scene scene = RenderingTruth.load(data, missionPath);
        assertNotNull(scene, "the retail mission will not load");
        assertNotNull(RenderingTruth.stageABattle(scene),
                "nowhere to stage the rendering fixture");

        String before = save(scene.world(), missionPath);
        int paintedPixels = 0;
        for (int frame = 0; frame < 8; frame++) {
            BufferedImage image = RenderingTruth.paint(scene.screen());
            paintedPixels += image.getWidth() * image.getHeight();
            BufferedImage panel = paintPanel(scene, GameScreen.Withheld.NOTHING);
            paintedPixels += panel.getWidth() * panel.getHeight();
        }
        assertTrue(paintedPixels > 1_000_000,
                "the read-only check did not actually paint meaningful frames");
        assertEquals(before, save(scene.world(), missionPath),
                "painting the field or side panel mutated serialized simulation state");
    }

    private static String save(World world, String missionPath) throws Exception {
        StringWriter out = new StringWriter();
        SaveGame.write(world, missionPath, null, 0, out);
        return out.toString();
    }

    @Test
    @DisplayName("everything the world says is on the battlefield puts pixels on the screen")
    void nothingTheWorldHasIsMissingFromTheFrame() {
        GameData data = data();
        List<RenderingTruth.Finding> blanks = new ArrayList<>();
        java.util.Map<RenderingTruth.Category, Integer> measured =
                new java.util.EnumMap<>(RenderingTruth.Category.class);
        int painted = 0;
        for (String path : MISSIONS) {
            RenderingTruth.Scene scene = RenderingTruth.load(data, path);
            Assumptions.assumeTrue(scene != null, path + " will not load");
            RenderingTruth.Staged staged = RenderingTruth.stageABattle(scene);
            Assumptions.assumeTrue(staged != null, "nowhere to stage a fight on " + path);
            RenderingTruth.Report report = RenderingTruth.sweep(scene, staged, 200, 8);
            blanks.addAll(report.unexplained());
            report.measured().forEach((category, count) ->
                    measured.merge(category, count, Integer::sum));
            painted += report.paints();
        }

        // The fixture first. A sweep that measured nothing passes every
        // assertion below it, and a corpse survived undrawn for the life of
        // this implementation behind exactly that kind of silence.
        assertTrue(painted > 2000,
                "the sweep painted only " + painted + " frames, so it can hardly have looked"
                        + " at the battlefield at all");
        for (RenderingTruth.Category category : List.of(
                RenderingTruth.Category.LIVING,
                RenderingTruth.Category.DYING,
                RenderingTruth.Category.CORPSE,
                RenderingTruth.Category.RUBBLE,
                RenderingTruth.Category.MISSILE,
                RenderingTruth.Category.BURNING,
                RenderingTruth.Category.SPELL,
                RenderingTruth.Category.DECORATION,
                RenderingTruth.Category.REMEMBERED)) {
            assertTrue(measured.getOrDefault(category, 0) > 0,
                    "the sweep never saw a single " + category + ", so it proves nothing about"
                            + " whether one is drawn. Measured: " + measured);
        }

        assertTrue(blanks.isEmpty(),
                blanks.size() + " things the world says are there put no pixels on the"
                        + " picture, and nothing is drawn over them: " + blanks);
    }

    /**
     * The one the player asked about, on its own, so a failure says which half
     * broke.
     *
     * <p>Two properties, and they fail separately. The death animation must
     * change the picture more than once while it runs -- a unit that vanishes
     * on the cycle it is killed changes it exactly once, from standing to
     * gone -- and the body it leaves must account for pixels of its own.
     */
    @Test
    @DisplayName("a dying orc falls over, and the body stays on the ground")
    void aDeathIsWatchedAndLeavesABody() {
        GameData data = data();
        RenderingTruth.Scene scene = RenderingTruth.load(data, "campaigns/human/level05h");
        Assumptions.assumeTrue(scene != null, "the mission will not load");
        World world = scene.world();
        int local = scene.localPlayer();

        Unit victim = stand(scene, "unit-grunt", enemyOf(scene));
        Assumptions.assumeTrue(victim != null, "nowhere to stand a grunt");
        Unit watcher = stand(scene, "unit-footman", local);
        Assumptions.assumeTrue(watcher != null, "nowhere to stand a footman to watch him");
        world.tick();
        scene.screen().centreOn(victim.tileX(), victim.tileY());
        Assumptions.assumeTrue(world.isVisibleTo(local, victim.tileX(), victim.tileY()),
                "the fixture must have the grunt in sight or it proves nothing");

        int animationFrames = victim.type().animationSet()
                .get(net.chonkbase.chonkcraft.engine.animation.AnimationSet.State.DEATH) == null
                ? 0 : 1;
        Assumptions.assumeTrue(animationFrames > 0, "a grunt with no death animation");

        // Read before the kill. A dead unit *becomes* its corpse rather than
        // being replaced by one, so once the change has happened the victim's
        // type is the body's and asking it what body it leaves answers with
        // whatever the body leaves, which is nothing.
        String corpseIdent = victim.type().corpse();
        world.kill(victim);
        BufferedImage previous = RenderingTruth.paint(scene.screen());
        int changes = 0;
        Unit body = null;
        for (int cycle = 0; cycle < 200 && body == null; cycle++) {
            world.tick();
            BufferedImage now = RenderingTruth.paint(scene.screen());
            if (differences(previous, now) > 0) {
                changes++;
            }
            previous = now;
            for (Unit unit : world.unitsSnapshot()) {
                if (unit.type() != null && unit.type().ident().equals(corpseIdent)) {
                    body = unit;
                }
            }
        }
        assertTrue(changes >= 3,
                "the grunt's death changed the painted frame " + changes + " times. Its Death"
                        + " animation steps through frames 45, 50 and 55, so a death that is"
                        + " actually drawn changes it several times over; a death filtered out"
                        + " of the draw list changes it once, when the unit disappears");

        assertNotNull(body, "the simulation left no corpse, which is a different fault");
        scene.screen().centreOn(body.tileX(), body.tileY());
        BufferedImage withBody = RenderingTruth.paint(scene.screen());
        scene.screen().withhold(GameScreen.Withheld.ofUnit(body));
        BufferedImage withoutBody = RenderingTruth.paint(scene.screen());
        scene.screen().withhold(GameScreen.Withheld.NOTHING);
        int pixels = differences(withBody, withoutBody);
        assertTrue(pixels > 100,
                "taking the body away changed " + pixels + " pixels, so unit-orc-dead-body is"
                        + " on the map, in the fog and holding a 72 by 72 frame of"
                        + " neutral/units/corpses.png, and none of it is being painted");
    }

    /**
     * The same measurement on a body the player genuinely cannot see, to prove
     * it distinguishes the two rather than passing on anything. This is the
     * case that must report nothing, and if it does not then the test above is
     * measuring the weather.
     */
    @Test
    @DisplayName("a body on ground nobody is watching is correctly not drawn")
    void theMeasurementCatchesAThingThatIsNotDrawn() {
        GameData data = data();
        RenderingTruth.Scene scene = RenderingTruth.load(data, "campaigns/human/level05h");
        Assumptions.assumeTrue(scene != null, "the mission will not load");
        World world = scene.world();
        int local = scene.localPlayer();

        // Somewhere the player has no eyes at all, which on a 128 square map
        // is most of it.
        Unit victim = null;
        UnitType grunt = data.unitTypes().types().get("unit-grunt");
        assertNotNull(grunt);
        for (int y = 1; y < world.map().height() - 1 && victim == null; y++) {
            for (int x = 1; x < world.map().width() - 1; x++) {
                if (world.isVisibleTo(local, x, y)) {
                    continue;
                }
                victim = world.createUnit(grunt, enemyOf(scene), x, y);
                if (victim != null) {
                    break;
                }
            }
        }
        Assumptions.assumeTrue(victim != null, "nowhere unlit to stand a grunt");
        int atX = victim.tileX();
        int atY = victim.tileY();
        String corpseIdent = victim.type().corpse();
        world.tick();
        Assumptions.assumeTrue(!world.isVisibleTo(local, atX, atY),
                "the fixture must have the grunt out of sight or it proves nothing");

        world.kill(victim);
        Unit body = null;
        for (int cycle = 0; cycle < 200 && body == null; cycle++) {
            world.tick();
            for (Unit unit : world.unitsSnapshot()) {
                if (unit.type() != null
                        && unit.type().ident().equals(corpseIdent)) {
                    body = unit;
                }
            }
        }
        Assumptions.assumeTrue(body != null, "the simulation left no corpse");
        Assumptions.assumeTrue(!world.isVisibleTo(local, body.tileX(), body.tileY()),
                "the ground lit up during the death, so this proves nothing");

        scene.screen().centreOn(body.tileX(), body.tileY());
        BufferedImage withBody = RenderingTruth.paint(scene.screen());
        scene.screen().withhold(GameScreen.Withheld.ofUnit(body));
        BufferedImage withoutBody = RenderingTruth.paint(scene.screen());
        scene.screen().withhold(GameScreen.Withheld.NOTHING);
        assertEquals(0, differences(withBody, withoutBody),
                "a body on ground the player has never lit was painted anyway, so the"
                        + " measurement the tests above rely on cannot tell drawn from"
                        + " undrawn");
    }

    /**
     * The minimap is a second surface and it forgot two things.
     *
     * <p>It tested one square of a unit's footprint against one player's own
     * fog. So an oil patch -- {@code HitPoints = 0}, {@code Indestructible = 1},
     * and therefore not {@code isAlive} in this implementation although upstream's
     * {@code CUnit::IsAlive} never looks at health -- was on no minimap on any
     * naval map, and a scouted enemy town was drawn on the field the player
     * remembers it on and left off the minimap, which is the surface a memory
     * exists for.
     */
    @Test
    @DisplayName("scenery with no hit points, and a remembered town, are both on the minimap")
    void theMinimapDrawsWhatTheFieldDraws() {
        GameData data = data();
        RenderingTruth.Scene scene = RenderingTruth.load(data, "campaigns/orc/level08o");
        Assumptions.assumeTrue(scene != null, "the mission will not load");
        Assumptions.assumeTrue(scene.panel().isAvailable(), "no side panel art");
        World world = scene.world();
        int local = scene.localPlayer();

        // Scenery the data gives no hit points: an oil patch or a circle of
        // power, standing where one of the player's own units can see it.
        Unit scenery = null;
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.type() != null && unit.type().hitPoints() == 0 && unit.isOnMap()
                    && !unit.type().revealer()) {
                scenery = unit;
                break;
            }
        }
        if (scenery == null) {
            scenery = stageHitPointFreeScenery(scene);
        }
        assertNotNull(scenery,
                "no retail hit-point-free indestructible scenery could be staged on the map");
        Unit eye = stand(scene, "unit-peon", local, scenery.tileX(), scenery.tileY());
        Assumptions.assumeTrue(eye != null, "nowhere to stand a peon beside it");
        world.tick();
        Assumptions.assumeTrue(
                world.isVisibleTo(local, scenery.tileX(), scenery.tileY()),
                "the fixture must light the scenery or it proves nothing");
        assertTrue(minimapPixels(scene, GameScreen.Withheld.ofUnit(scenery)) > 0,
                scenery.type().ident() + " has no dot on the minimap. It declares"
                        + " HitPoints = 0 with Indestructible = 1, so Unit.isAlive calls it"
                        + " dead from the moment the map loads, and upstream's"
                        + " CUnit::IsAlive never looks at health");

        // The fog lifecycle has its own engine proof. Start this view test at
        // the renderer's public memory boundary so live armies cannot keep the
        // chosen building visible and silently remove the fixture.
        var stagedMemory = RenderingTruth.stageRememberedBuilding(
                scene, enemyOf(scene));
        assertNotNull(stagedMemory, "no remembered retail building could be staged");
        var memories = new ArrayList<>(world.seenBuildings().forPlayer(local));
        assertTrue(!memories.isEmpty(), "nothing was remembered");
        int drawn = 0;
        for (var memory : memories) {
            if (memory.type().visibleUnderFog()
                    && minimapPixels(scene, GameScreen.Withheld.ofMemory(memory)) > 0) {
                drawn++;
            }
        }
        assertTrue(drawn > 0,
                "none of the " + memories.size() + " buildings this player remembers has a dot"
                        + " on the minimap, though the map view draws every one of them");
    }

    // ------------------------------------------------------------------

    /** How many pixels of the minimap one thing accounts for. */
    private static int minimapPixels(RenderingTruth.Scene scene, GameScreen.Withheld one) {
        BufferedImage whole = paintPanel(scene, GameScreen.Withheld.NOTHING);
        BufferedImage without = paintPanel(scene, one);
        return differences(whole, without);
    }

    private static BufferedImage paintPanel(RenderingTruth.Scene scene,
            GameScreen.Withheld withheld) {
        scene.panel().withhold(withheld);
        BufferedImage frame = new BufferedImage(RenderingTruth.WIDTH, RenderingTruth.HEIGHT,
                BufferedImage.TYPE_INT_RGB);
        var g = frame.createGraphics();
        scene.panel().draw(g, RenderingTruth.WIDTH, RenderingTruth.HEIGHT, null, 0, 0,
                RenderingTruth.WIDTH, RenderingTruth.HEIGHT);
        g.dispose();
        scene.panel().withhold(GameScreen.Withheld.NOTHING);
        return frame;
    }

    private static int differences(BufferedImage a, BufferedImage b) {
        int changed = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    changed++;
                }
            }
        }
        return changed;
    }

    private static int enemyOf(RenderingTruth.Scene scene) {
        for (int player = 0; player < 8; player++) {
            if (player != scene.localPlayer() && scene.world().player(player) != null
                    && scene.world().isEnemyPlayer(scene.localPlayer(), player)) {
                return player;
            }
        }
        return (scene.localPlayer() + 1) % 8;
    }

    /** Places real retail scenery when the chosen mission happens not to carry one. */
    private static Unit stageHitPointFreeScenery(RenderingTruth.Scene scene) {
        World world = scene.world();
        for (UnitType type : scene.data().unitTypes().types().values()) {
            if (type.hitPoints() != 0 || !type.indestructible() || type.revealer()) {
                continue;
            }
            for (int y = 1; y < world.map().height() - 1; y++) {
                for (int x = 1; x < world.map().width() - 1; x++) {
                    Unit placed = world.createUnit(type, 15, x, y);
                    if (placed != null) {
                        return placed;
                    }
                }
            }
        }
        return null;
    }

    /** Stands a unit somewhere near the player's own army. */
    private static Unit stand(RenderingTruth.Scene scene, String ident, int player) {
        for (Unit unit : scene.world().unitsSnapshot()) {
            if (unit.player() == scene.localPlayer() && unit.type() != null
                    && !unit.type().building()) {
                return stand(scene, ident, player, unit.tileX(), unit.tileY());
            }
        }
        return null;
    }

    private static Unit stand(RenderingTruth.Scene scene, String ident, int player,
            int nearX, int nearY) {
        UnitType type = scene.data().unitTypes().types().get(ident);
        if (type == null) {
            return null;
        }
        for (int radius = 1; radius <= 6; radius++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    Unit placed = scene.world().createUnit(type, player,
                            nearX + dx, nearY + dy);
                    if (placed != null) {
                        return placed;
                    }
                }
            }
        }
        return null;
    }
}
