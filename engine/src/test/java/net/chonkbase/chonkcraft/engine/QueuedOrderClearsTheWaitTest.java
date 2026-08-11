package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Taking the next order off the queue wipes what the last one was waiting out.
 *
 * <p>{@code HandleUnitAction} pops an order that has finished when something
 * is queued behind it, and the line after the pop is {@code unit.Wait = 0}
 * It has to be: {@code unit.Wait} is
 * how the old order paced itself -- the ten cycles a spent route costs, the
 * five a widened march costs, an attack's own reload -- and none of it is the
 * new order's to serve.
 *
 * <p>It shows wherever a command lands on a unit that is pausing. On
 * {@code maps/demo/demo03} the computer player shoves a grunt at 10,2 aside on
 * cycle 61; upstream's walks on 62 and this implementation's had six cycles of somebody
 * else's pause left to sit through first, so it was still under a move order
 * at 67 where upstream had finished at 64.
 */
class QueuedOrderClearsTheWaitTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType walker() {
        UnitType type = new UnitType("unit-peasant");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setNumDirections(8);
        AnimationSet set = new AnimationSet("walker");
        set.put(AnimationSet.State.STILL, Animation.parse("still",
                List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    /** Longer than any pause this test could be waiting on by accident. */
    private static final int LONG_PAUSE = 40;

    @Test
    @DisplayName("a queued order starts at once however long the last one was pausing")
    void thePopWipesTheWait() {
        World world = new World(grass(24));
        world.fog().revealAll(0);
        Unit walker = world.createUnit(walker(), 0, 10, 10);

        // Standing still with a long pause to serve, which is the state a
        // spent route or a widened march leaves a unit in.
        walker.setWaitCycles(LONG_PAUSE);
        walker.enqueueOrder(new Unit.QueuedOrder(Unit.QueuedOrderKind.MOVE,
                10, 16, null, null, null));

        int started = -1;
        for (int cycle = 1; cycle <= LONG_PAUSE && started < 0; cycle++) {
            world.tick();
            if (walker.tileY() != 10 || walker.offsetY() != 0) {
                started = cycle;
            }
        }

        assertTrue(started > 0,
                "the walker never set off inside " + LONG_PAUSE + " cycles: it sat out the"
                        + " whole of a pause that belonged to the order before this one");
        assertEquals(1, started,
                "the walker set off on cycle " + started + " with a pause of " + LONG_PAUSE
                        + " left over from the order before. Taking an order off the queue"
                        + " clears the wait, so the first step belongs to the first cycle the"
                        + " new order runs");
    }
}
