package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How long a step owns the unit that took it, and why the answer depends on
 * which way the step went.
 *
 * <p>{@code unit.Moving} is a state, not a displacement. {@code DoActionMove}
 * clears it on {@code reached_next_tile} or on the animation going breakable
 * with nothing owed, and
 * {@code reached_next_tile} is {@code (posd.x < 0) == (unit.IX < 0)} -- a test
 * that is not symmetric. The drawing offset starts opposite the step and works
 * towards nought, so a step east or south satisfies it on landing exactly at
 * nought, and a step west or north does not: only an overshoot past nought
 * would, and a critter's Move is sixteen {@code move 2}s, exactly the
 * thirty-two pixels of a tile, so its west and north steps never overshoot.
 *
 * <p>What turns on it is when a neighbour may give up waiting: the blocked
 * walk's ending asks {@code blocker->Moving == 0}. On
 * {@code maps/skirmish/(3)critter-attack} the east-stepping blocker at 69,22
 * reads standing the cycle its pixels land and its neighbour gives up on
 * cycle 47, while the west-stepping one at 13,41 reads moving for three more
 * cycles -- until {@code unbreakable end} -- and its neighbour holds on to
 * cycle 532. This implementation read the drained offset for both, gave up eleven
 * cycles early, and wandered on numbers upstream had not drawn; that map's
 * last divergence was exactly this flag.
 */
class WalkHoldingTest {

    /** ChonkCraft's critter Move: sixteen {@code move 2}s, a tile exactly. */
    private static List<String> critterMove() {
        List<String> move = new ArrayList<>();
        move.add("unbreakable begin");
        move.add("frame 0");
        move.add("move 2");
        move.add("wait 2");
        for (int i = 0; i < 15; i++) {
            move.add("frame 0");
            move.add("move 2");
            move.add("wait 3");
        }
        move.add("frame 0");
        move.add("unbreakable end");
        move.add("wait 1");
        return move;
    }

    private static UnitType critter() {
        UnitType type = new UnitType("unit-critter");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(5);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setNumDirections(8);
        AnimationSet set = new AnimationSet("critter");
        set.put(AnimationSet.State.STILL, Animation.parse("still",
                List.of("frame 0", "wait 4", "frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", critterMove()));
        type.setAnimationSet(set);
        return type;
    }

    private static World grassWorld() {
        GameMap map = new GameMap(30, 30, new Tileset());
        for (int y = 0; y < 30; y++) {
            for (int x = 0; x < 30; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return new World(map);
    }

    @Test
    @DisplayName("a critter stepping west is still moving after its pixels land, until its animation lets go")
    void aWestStepHoldsThroughTheAnimationTail() {
        World world = grassWorld();
        Unit critter = world.createUnit(critter(), 0, 10, 10);
        assertTrue(world.orderMove(critter, 9, 10), "the order was refused");

        int landedAt = -1;
        for (int cycle = 1; cycle <= 60 && landedAt < 0; cycle++) {
            world.tick();
            if (critter.tileX() == 9 && critter.offsetX() == 0 && critter.offsetY() == 0) {
                landedAt = cycle;
            }
        }
        assertTrue(landedAt > 0, "the critter never landed, so nothing here counts");
        assertTrue(critter.walkHolding(),
                "the step let go the cycle the pixels landed. A west step's offset works"
                        + " down from positive and reached_next_tile is (posd < 0) == (IX"
                        + " < 0), which an exact landing at nought cannot satisfy; upstream"
                        + " holds Moving until the animation's unbreakable end, three cycles"
                        + " more, and a neighbour that gives up in that window wanders on"
                        + " numbers upstream has not drawn");

        boolean released = false;
        for (int cycle = 1; cycle <= 8 && !released; cycle++) {
            world.tick();
            released = !critter.walkHolding();
        }
        assertTrue(released,
                "the step never let go at all: the animation ended and the flag stayed,"
                        + " which jams every neighbour for ever");
    }

    @Test
    @DisplayName("and one stepping east reads standing the cycle its pixels land")
    void anEastStepReleasesAtTheLanding() {
        World world = grassWorld();
        Unit critter = world.createUnit(critter(), 0, 10, 10);
        assertTrue(world.orderMove(critter, 11, 10), "the order was refused");

        int landedAt = -1;
        for (int cycle = 1; cycle <= 60 && landedAt < 0; cycle++) {
            world.tick();
            if (critter.tileX() == 11 && critter.offsetX() == 0 && critter.offsetY() == 0) {
                landedAt = cycle;
            }
        }
        assertTrue(landedAt > 0, "the critter never landed, so nothing here counts");
        assertFalse(critter.walkHolding(),
                "an east step's offset climbs from negative, so landing exactly at nought"
                        + " satisfies reached_next_tile and upstream clears Moving that very"
                        + " cycle -- the animation's remaining tail notwithstanding. Holding"
                        + " here keeps a neighbour waiting that upstream releases");
    }
}
