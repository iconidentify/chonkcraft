package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a right click means, branch by branch against upstream's table.
 *
 * <p>{@code DoRightButton_ForSelectedUnit} is a
 * table, and this implementation had about half of it. The pieces missing were each
 * invisible in the same way: every branch that <em>was</em> implemented did
 * something for every click, so nothing ever looked broken. A loaded peasant
 * right clicked onto its own town hall walked up to the hall and stood there,
 * because a hall at full health declined the repair branch and the worker fell
 * through to move; the gold simply never arrived. A damaged ally's building
 * could not be repaired at all. Control and alt were never read on the right
 * button, so following anything and shelling a square had no way in. And a
 * neutral unit -- a sheep, a rescuable prisoner -- was not something you could
 * follow.
 *
 * <p>Every check here drives a real {@code MouseEvent} with the real modifier
 * mask and then asks the unit what order it ended up with, because the modifier
 * plumbing is exactly the part that was missing: a test calling the branch
 * directly would have passed throughout.
 */
class RightClickTableTest {

    private static final String MAP = "campaigns/human/level02h";
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static final int TILE = 32;

    private record Scene(GameScreen screen, World world, GameData data,
            java.util.List<net.chonkbase.chonkcraft.engine.network.GameCommand> sent) {}

    private static GameData data() {
        AssetSource source = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(source != null,
                "No authenticated BNE asset source configured.");
        return new GameData(source);
    }

    private static Scene scene() {
        return scene(false);
    }

    /**
     * @param record whether the sink should hold the commands instead of
     *               applying them, for the one branch below whose order the
     *               engine refuses
     */
    private static Scene scene(boolean record) {
        GameData data = data();
        PudMap pud = data.campaignMap(MAP);
        Assumptions.assumeTrue(pud != null, "no campaign map available");
        var tileset = data.loadTileset(pud.tileset());
        World world = new World(GameMap.from(pud, tileset.tileset()), Player.from(pud));
        data.configureWorld(world, pud.tileset());
        world.fog().revealAll(0);
        String tilesetName = pud.tileset() == PudMap.Tileset.FOREST
                ? "summer"
                : pud.tileset().name().toLowerCase(java.util.Locale.ROOT);
        CommandApplier applier = new CommandApplier(world,
                new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(applier);
        java.util.List<net.chonkbase.chonkcraft.engine.network.GameCommand> sent =
                new ArrayList<>();
        CommandSink sink = record ? sent::add : CommandSink.local(applier);
        GameScreen screen = new GameScreen(world, data,
                new BufferedImage(TILE * 64, TILE * 64, BufferedImage.TYPE_INT_RGB),
                tileset.palette(), tilesetName, 0, WIDTH, HEIGHT,
                new net.chonkbase.chonkcraft.engine.sound.GameAudio(data.sounds()),
                null, null, applier, sink, java.util.List.of(), "human");
        screen.setSize(WIDTH, HEIGHT);
        screen.setLayout((net.chonkbase.chonkcraft.engine.ui.UiLayout.Layout) null);
        screen.setGameScale(1);
        return new Scene(screen, world, data, sent);
    }

    /** A right click on a tile, with whatever modifiers were asked for. */
    private static void rightClick(GameScreen screen, int tileX, int tileY,
            boolean control, boolean alt) {
        int mask = InputEvent.BUTTON3_DOWN_MASK
                | (control ? InputEvent.CTRL_DOWN_MASK : 0)
                | (alt ? InputEvent.ALT_DOWN_MASK : 0);
        MouseEvent event = new MouseEvent(screen, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), mask,
                tileX * TILE + TILE / 2, tileY * TILE + TILE / 2, 1, false,
                MouseEvent.BUTTON3);
        for (var listener : screen.getMouseListeners()) {
            listener.mousePressed(event);
        }
    }

    /** Puts a unit of a player's somewhere near a given square. */
    private static Unit place(Scene scene, String ident, int player, int fromX, int fromY) {
        UnitType type = scene.data().unitTypes().types().get(ident);
        assertNotNull(type, "the shipped data has " + ident);
        for (int y = fromY; y < fromY + 8; y++) {
            for (int x = fromX; x < fromX + 8; x++) {
                // Not on or beside a wall. A right click on a wall square is
                // an order to knock the wall down, which would answer a
                // different question from the one each test is asking.
                if (nearWall(scene.world(), x, y)) {
                    continue;
                }
                Unit made = scene.world().createUnit(type, player, x, y);
                if (made != null) {
                    return made;
                }
            }
        }
        return null;
    }

    private static boolean nearWall(World world, int tileX, int tileY) {
        for (int y = tileY - 1; y <= tileY + 1; y++) {
            for (int x = tileX - 1; x <= tileX + 1; x++) {
                if (world.map().contains(x, y) && world.map().field(x, y).isWall()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A square this unit can actually reach, found by sending it there and
     * taking the order back.
     *
     * <p>Terrain that merely holds a unit is not enough: a passable square
     * across a lake is unreachable, the move is refused, and the unit stays
     * STILL -- which would read as "the click did nothing" rather than as "the
     * test picked an island".
     */
    private static int[] walkableSquare(World world, Unit unit) {
        for (int step = 2; step < 8; step++) {
            for (int[] delta : new int[][] {{step, 0}, {0, step}, {step, step}, {-step, 0}}) {
                int x = unit.tileX() + delta[0];
                int y = unit.tileY() + delta[1];
                if (!world.map().contains(x, y)) {
                    continue;
                }
                boolean reachable = world.orderMove(unit, x, y)
                        && unit.order() == Unit.Order.MOVE;
                world.orderStop(unit);
                if (reachable) {
                    return new int[] {x, y};
                }
            }
        }
        return null;
    }

    private static void only(Scene scene, Unit unit) {
        for (Unit each : scene.world().unitsSnapshot()) {
            each.setSelected(each == unit);
        }
        scene.screen().selectForTest(unit);
        unit.setSelected(true);
    }

    private static boolean accepted(Unit unit, Unit.Order current,
            Unit.QueuedOrderKind queued) {
        return unit.order() == current
                || unit.queuedOrders().stream().anyMatch(order -> order.kind() == queued);
    }

    @Test
    @DisplayName("a loaded peasant right clicked onto the town hall delivers instead of walking")
    void aLoadedWorkerReturnsItsGoods() {
        Scene scene = scene();
        Unit peasant = place(scene, "unit-peasant", 0, 3, 3);
        Unit hall = peasant == null ? null
                : place(scene, "unit-town-hall", 0, peasant.tileX() + 2, peasant.tileY());
        Assumptions.assumeTrue(hall != null && peasant != null,
                "nowhere to put a hall and a peasant");

        // Loaded, and the hall at full health so the repair branch declines --
        // which is precisely the state in which the worker used to walk over
        // and stand there.
        peasant.setCarrying(UnitType.Resource.GOLD);
        peasant.setCarried(100);
        assertEquals(hall.type().hitPoints(), hall.hitPoints(),
                "the hall must be undamaged or this tests the repair branch instead");

        only(scene, peasant);
        rightClick(scene.screen(), hall.tileX(), hall.tileY(), false, false);

        assertEquals(Unit.Order.RETURN_GOODS, peasant.order(),
                "a peasant holding a hundred gold, sent at its own town hall, took the order"
                        + " " + peasant.order() + ": DoRightButton_Harvest_Unit asks first"
                        + " whether the thing clicked will take the load");
    }

    @Test
    @DisplayName("a right click on an ally's damaged building orders a repair")
    void alliesGetRepairedToo() {
        // The one branch here whose order the simulation still refuses.
        // {@code World.orderRepair} ({@code engine/World.java:5844}) declines
        // any target whose player is not the repairer's own, so the screen can
        // send the order and nothing happens. What is checked is therefore
        // what this file is responsible for: that the click means repair at
        // all. Upstream's condition is
        // (dest->Player == unit.Player || unit.IsAllied(*dest)), and the
        // engine's line wants the same second half before an ally's keep can
        // actually be mended. It is covered by focused tests.
        Scene scene = scene(true);
        Unit peasant = place(scene, "unit-peasant", 0, 3, 3);
        Unit theirs = peasant == null ? null
                : place(scene, "unit-human-barracks", 1, peasant.tileX() + 2, peasant.tileY());
        Assumptions.assumeTrue(theirs != null && peasant != null,
                "nowhere to put an allied barracks and a peasant");
        scene.world().setAllied(0, 1, true);
        scene.world().setAllied(1, 0, true);
        theirs.setHitPoints(theirs.type().hitPoints() / 2);

        only(scene, peasant);
        rightClick(scene.screen(), theirs.tileX(), theirs.tileY(), false, false);

        assertEquals(1, scene.sent().stream()
                        .filter(c -> c.kind()
                                == net.chonkbase.chonkcraft.engine.network.GameCommand.Kind.REPAIR
                                && c.targetId() == theirs.id())
                        .count(),
                "a peasant sent at an ally's half-dead barracks issued " + scene.sent()
                        + ", where upstream's worker branch orders a repair");
    }

    @Test
    @DisplayName("control and the right button follows an enemy instead of attacking it")
    void controlFollowsAnything() {
        // Recorded rather than applied, for the same reason as the allied
        // repair above: {@code World.orderFollow}
        // ({@code engine/World.java:5504}) refuses a target the follower is at
        // war with, so the order can be given and the simulation declines it.
        // Upstream has no such condition -- ctrl-right-click is "follow
        // anything" in as many words, -- and the engine
        // line is in focused tests.
        Scene scene = scene(true);
        Unit mine = place(scene, "unit-footman", 0, 3, 3);
        Unit theirs = mine == null ? null
                : place(scene, "unit-footman", 1, mine.tileX() + 2, mine.tileY());
        Assumptions.assumeTrue(mine != null && theirs != null, "nowhere to put two footmen");

        // Without the modifier this is an attack, which is what makes the
        // check below mean something: the same click, one key apart.
        only(scene, mine);
        rightClick(scene.screen(), theirs.tileX(), theirs.tileY(), false, false);
        assertEquals(1, scene.sent().stream()
                        .filter(c -> c.kind()
                                == net.chonkbase.chonkcraft.engine.network.GameCommand.Kind.ATTACK)
                        .count(),
                "a plain right click on an enemy footman must still be an attack; got "
                        + scene.sent());

        scene.sent().clear();
        only(scene, mine);
        rightClick(scene.screen(), theirs.tileX(), theirs.tileY(), true, false);
        assertEquals(1, scene.sent().stream()
                        .filter(c -> c.kind()
                                == net.chonkbase.chonkcraft.engine.network.GameCommand.Kind.FOLLOW
                                && c.targetId() == theirs.id())
                        .count(),
                "control and a right click on an enemy issued " + scene.sent()
                        + ": mouse.cpp:541 is \"Control + right click on unit is follow"
                        + " anything\"");
    }

    @Test
    @DisplayName("control and alt shells the square, for a siege engine and nothing else")
    void controlAndAltAttackTheGround() {
        Scene scene = scene();
        UnitType catapult = scene.data().unitTypes().types().get("unit-catapult");
        assertNotNull(catapult, "the shipped data has a catapult");
        Assumptions.assumeTrue(catapult.groundAttack(),
                "the catapult does not declare ground attack, so this branch cannot apply");

        Unit siege = place(scene, "unit-catapult", 0, 3, 3);
        Unit footman = place(scene, "unit-footman", 0, 12, 12);
        Assumptions.assumeTrue(siege != null && footman != null,
                "nowhere to put a catapult and a footman");

        only(scene, siege);
        rightClick(scene.screen(), siege.tileX() + 4, siege.tileY() + 4, true, true);
        assertTrue(accepted(siege, Unit.Order.ATTACK_GROUND,
                        Unit.QueuedOrderKind.ATTACK_GROUND),
                "ctrl-alt-right-click on empty ground with a catapult gave " + siege.order());

        // And a unit without the flag falls through to the ordinary table
        // rather than doing nothing, which is what upstream's early return
        // inside the flag test gives.
        int[] walkable = walkableSquare(scene.world(), footman);
        Assumptions.assumeTrue(walkable != null, "nowhere for the footman to walk to");
        only(scene, footman);
        rightClick(scene.screen(), walkable[0], walkable[1], true, true);
        assertNotEquals(Unit.Order.ATTACK_GROUND, footman.order(),
                "a footman has no GroundAttack flag and must not shell anything");
        // It falls through to the rest of the table, which for a fighter with
        // control held is the empty-space branch: an attack with no target.
        // Doing nothing at all is the one answer upstream never gives.
        assertTrue(accepted(footman, Unit.Order.ATTACK_MOVE,
                        Unit.QueuedOrderKind.ATTACK_MOVE),
                "a footman given ctrl-alt-right-click ended up " + footman.order()
                        + " rather than falling through to DoRightButton_Attack's"
                        + " empty-space branch");
    }

    @Test
    @DisplayName("control on open ground sends a fighter in fighting, not walking")
    void controlOnEmptyGroundIsAnAttackMove() {
        Scene scene = scene();
        Unit footman = place(scene, "unit-footman", 0, 3, 3);
        Assumptions.assumeTrue(footman != null, "nowhere to put a footman");
        int[] walkable = walkableSquare(scene.world(), footman);
        Assumptions.assumeTrue(walkable != null, "nowhere for the footman to walk to");

        // Without the modifier the same click is a plain move, which is what
        // makes the check below about the modifier rather than about the
        // square.
        only(scene, footman);
        rightClick(scene.screen(), walkable[0], walkable[1], false, false);
        assertTrue(accepted(footman, Unit.Order.MOVE, Unit.QueuedOrderKind.MOVE),
                "a plain right click on open ground must still be a walk");

        only(scene, footman);
        rightClick(scene.screen(), walkable[0], walkable[1], true, false);
        assertTrue(accepted(footman, Unit.Order.ATTACK_MOVE,
                        Unit.QueuedOrderKind.ATTACK_MOVE),
                "control and a right click on open ground gave " + footman.order()
                        + ": mouse.cpp:406-413 sends an attack with no target, which is"
                        + " an advance that fights what it meets");
    }

    @Test
    @DisplayName("a right click on a sheep follows it rather than walking to where it was")
    void neutralUnitsCanBeFollowed() {
        Scene scene = scene();
        Unit footman = place(scene, "unit-footman", 0, 3, 3);
        Assumptions.assumeTrue(footman != null, "nowhere to put a footman");
        UnitType critter = scene.data().unitTypes().types().get("unit-critter");
        Assumptions.assumeTrue(critter != null && critter.speed() > 0,
                "the shipped data has no mobile critter");
        Unit sheep = null;
        for (int step = 2; step < 8 && sheep == null; step++) {
            sheep = scene.world().createUnit(critter, World.NEUTRAL_PLAYER,
                    footman.tileX() + step, footman.tileY());
        }
        Assumptions.assumeTrue(sheep != null, "nowhere to stand a sheep");

        only(scene, footman);
        rightClick(scene.screen(), sheep.tileX(), sheep.tileY(), false, false);

        assertEquals(Unit.Order.FOLLOW, footman.order(),
                "a footman sent at a sheep took the order " + footman.order()
                        + ": every follow branch upstream ends"
                        + " \"|| dest->Player->Index == PlayerNumNeutral\"");
    }

    @Test
    @DisplayName("following something that cannot move is a walk to where it stands")
    void animmobileTargetIsWalkedTo() {
        Scene scene = scene();
        Unit footman = place(scene, "unit-footman", 0, 3, 3);
        Unit farm = footman == null ? null
                : place(scene, "unit-farm", 0, footman.tileX() + 2, footman.tileY());
        Assumptions.assumeTrue(farm != null && footman != null,
                "nowhere to put a farm and a footman");
        Assumptions.assumeTrue(farm.type().speed() == 0, "a farm should not be able to move");

        only(scene, footman);
        rightClick(scene.screen(), farm.tileX(), farm.tileY(), false, false);

        assertTrue(accepted(footman, Unit.Order.MOVE, Unit.QueuedOrderKind.MOVE),
                "a footman sent at his own farm took the order " + footman.order()
                        + ": upstream's follow branches send Move when the target"
                        + " CanMove() is false");
    }

    @Test
    @DisplayName("a worker right-clicked onto an enemy walks rather than attacks")
    void aWorkerRightClickedOntoAnEnemyWalksRatherThanAttacks() {
        Scene scene = scene();
        Unit peasant = place(scene, "unit-peasant", 0, 3, 3);
        Unit grunt = peasant == null ? null
                : place(scene, "unit-grunt", 1, peasant.tileX() + 2, peasant.tileY());
        Assumptions.assumeTrue(peasant != null && grunt != null,
                "nowhere to put a peasant and a grunt");

        only(scene, peasant);
        rightClick(scene.screen(), grunt.tileX(), grunt.tileY(), false, false);

        assertTrue(accepted(peasant, Unit.Order.MOVE, Unit.QueuedOrderKind.MOVE),
                "a peasant sent at an enemy took " + peasant.order()
                        + ": BNE's worker table has no attack branch");
    }

    @Test
    @DisplayName("alt-right-click on a friend is defend, not a walk")
    void altRightClickDefendsAFriend() {
        Scene scene = scene();
        Unit footman = place(scene, "unit-footman", 0, 3, 3);
        Unit peasant = footman == null ? null
                : place(scene, "unit-peasant", 0, footman.tileX() + 2, footman.tileY());
        Assumptions.assumeTrue(footman != null && peasant != null,
                "nowhere to put a footman and a peasant");

        only(scene, footman);
        rightClick(scene.screen(), peasant.tileX(), peasant.tileY(), false, true);

        assertEquals(Unit.Order.DEFEND, footman.order(),
                "alt-right-click on a friend gave " + footman.order()
                        + ": BNE SendCommandDefend used to fall through to Move");
        assertSame(peasant, footman.target(),
                "the defended friend is not the defend target");
    }
}
