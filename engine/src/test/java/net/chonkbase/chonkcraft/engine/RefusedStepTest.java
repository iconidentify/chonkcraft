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
 * What a unit does about the square in front of it when something is standing
 * there.
 *
 * <p>Implements {@code fcn.004379e0} in Warcraft II Battle.net Edition 2.02b,
 * 671 bytes at {@code 0x004379e0}, whose whole answer is three bands off one
 * count: refusals one to seven park the route -- {@code 0x00450ad0} writes 20
 * to the route cursor, one past the twenty heading bytes -- and set the timer
 * to 1, so the unit plans again on the very next visit; the eighth parks it
 * and leaves the timer at the 15 written at {@code 0x00437a25}; the fifteenth
 * clears the count and takes the next order.
 *
 * <p>This implementation did upstream LegacyEngine here instead: ten cycles asleep and the
 * refused heading thrown away, from {@code NextPathElement} and the {@code PF_WAIT} arm of
 * {@code DoActionMove}. Retail's peon
 * 1521 in Orc 12 stands on 86,41 for twenty-two cycles with a friendly peon
 * in the way; this implementation threw the west heading away and walked south-west on
 * the first visit.
 */
class RefusedStepTest {

    /** ChonkCraft's critter Move: sixteen {@code move 2}s, a tile exactly. */
    private static UnitType walker() {
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
        java.util.List<String> move = new java.util.ArrayList<>();
        move.add("unbreakable begin");
        move.add("frame 0");
        move.add("move 2");
        move.add("wait 2");
        for (int step = 0; step < 15; step++) {
            move.add("frame 0");
            move.add("move 2");
            move.add("wait 3");
        }
        move.add("frame 0");
        move.add("unbreakable end");
        move.add("wait 1");
        set.put(AnimationSet.State.MOVE, Animation.parse("move", move));
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

    /**
     * How many cycles a walker spends on the square where a friend stepped
     * into its way, or -1 if it never gets off it.
     *
     * <p>The friend has to arrive after the route is laid. A friend already
     * standing there when the order is given is one the pathfinder simply
     * walks around, so nothing is ever refused and the walk proves nothing --
     * which is how the first draft of this test passed against the very
     * behaviour it was written to reject.
     */
    private static int cyclesSpentStuck(int cycles) {
        World world = grassWorld();
        Unit walker = world.createUnit(walker(), 0, 10, 10);
        assertTrue(world.orderMove(walker, 16, 10), "the walk order was refused");
        int arrived = -1;
        for (int cycle = 1; cycle <= 2000 && arrived < 0; cycle++) {
            world.tick();
            if (walker.tileX() == 12 && walker.tileY() == 10
                    && walker.offsetX() == 0 && walker.offsetY() == 0) {
                arrived = cycle;
            }
        }
        assertTrue(arrived > 0,
                "the walker never reached 12,10 on open grass, so there was"
                        + " nowhere to put the friend and this measures"
                        + " nothing");
        assertTrue(walker.pathLength() > 0,
                "the walker reached 12,10 holding no route, so the step it is"
                        + " about to be refused does not exist");
        world.createUnit(walker(), 0, 13, 10);
        for (int cycle = 1; cycle <= cycles; cycle++) {
            world.tick();
            if (walker.tileX() != 12 || walker.tileY() != 10) {
                return cycle;
            }
        }
        return -1;
    }

    @Test
    @DisplayName("a walker whose way is blocked tries again on the next cycle rather than sleeping ten")
    void aRefusedWalkerPlansAgainOnTheNextVisit() {
        int moving = cyclesSpentStuck(60);
        assertTrue(moving > 0,
                "the walker never got past a single friend standing on one"
                        + " square of open grass, so it is stuck rather than"
                        + " refusing");
        // Retail parks the route and puts the movement timer to 1, so the very
        // next visit plans afresh and the unit is walking again within a step's
        // worth of cycles. Upstream's PF_WAIT sleeps ten before it will look at
        // the square again, which is what this used to do, and ten cycles is a
        // third of a second of a peon standing in front of a friend it could
        // have walked around.
        assertTrue(moving <= 8,
                "the walker stood in front of its friend for " + moving
                        + " cycles before going round. Retail sets the movement"
                        + " timer to 1 on a refused step and plans again on the"
                        + " next visit; sleeping ten cycles first is upstream's"
                        + " PF_WAIT, which the Battle.net Edition did not keep");
    }
}
