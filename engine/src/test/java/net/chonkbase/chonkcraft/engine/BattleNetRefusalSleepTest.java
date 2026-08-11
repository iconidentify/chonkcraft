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
 * How long a unit stands still after its eighth refusal.
 *
 * <p>Implements the eighth-refusal band of {@code fcn.004379e0} in Warcraft II
 * Battle.net Edition 2.02b. {@code 0x00437ab4} gives the route up and returns
 * without putting the movement timer back to 1, so the fifteen written at
 * {@code 0x00437a25} stands, and the unit is quiet until it runs out.
 *
 * <p>The count is what the length is measured in, and retail reads its timer
 * before it spends it: the visit that takes the timer from 1 to 0 is the visit
 * that acts. This implementation is quiet on that visit as well, so writing retail's
 * fifteen here buys sixteen quiet visits and every step after the sleep is a
 * cycle late. Orc 12's peon 1521 is the witness. It refuses on 86,41 until its
 * eighth on cycle 31, counts 15 down to 1 over cycles 31 to 45, and steps onto
 * 85,42 on 46; this implementation counted the same fifteen and then spent a sixteenth
 * visit reaching nought, and stepped on 47.
 */
class BattleNetRefusalSleepTest {

    /** Retail's timer, and so the number of visits the sleep is quiet for. */
    private static final int SLEEP = 15;

    /**
     * A one-tile gully across an otherwise unwalkable map, so that the friend
     * standing in it is the only way through and the planner cannot simply
     * draw a route past him.
     */
    private static GameMap gully(int size, int row) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(y == row
                        ? TileFlag.LAND_ALLOWED : TileFlag.WATER_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType soldier() {
        UnitType type = new UnitType("unit-footman");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setSightRange(12);
        type.setNumDirections(8);
        AnimationSet set = new AnimationSet("soldier");
        set.put(AnimationSet.State.STILL, Animation.parse("still",
                List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    @Test
    @DisplayName("the eighth refusal buys fifteen quiet cycles, not sixteen")
    void theEighthRefusalsSleepIsFifteenVisitsLong() {
        World world = new World(gully(30, 10));
        world.fog().revealAll(0);
        Unit walker = world.createUnit(soldier(), 0, 10, 10);
        UnitType friendType = soldier();
        Unit friend = world.createUnit(friendType, 0, 9, 10);
        assertTrue(walker != null && friend != null,
                "the walker and the friend in the way must place");
        assertTrue(world.orderMove(walker, 3, 10), "the move order was refused");

        // The friend must look like a walker to the planner and a stopped one
        // to the step: the planner stands an ally that is on its way somewhere
        // aside -- 0x00450766 -- so the route is drawn through him and only
        // the step finds him there. Holding no route of his own keeps him off
        // the cooperative arm, which would keep the route and wait instead.
        friend.clearPath();
        friend.setOffset(-16, 0);
        friend.animation().switchTo(
                friendType.animationSet().get(AnimationSet.State.MOVE));

        int armed = -1;
        for (int cycle = 0; cycle < 60 && armed < 0; cycle++) {
            world.tick();
            if (walker.battleNetRefusals() >= 8) {
                armed = cycle;
            }
        }
        assertTrue(armed >= 0,
                "the walker must be turned back eight times before it sleeps");

        // Retail's peon has its way clear again eleven cycles into the sleep
        // and does not take it, so freeing the friend does not shorten this
        // and it keeps the measurement about the timer rather than the block.
        world.remove(friend);
        int quiet = 0;
        while (walker.tileX() == 10 && walker.tileY() == 10 && quiet < 40) {
            world.tick();
            quiet++;
        }
        assertEquals(SLEEP, quiet,
                "the eighth refusal is quiet for fifteen visits and acts on"
                        + " the fifteenth, and this walker stood still for "
                        + quiet);
    }
}
