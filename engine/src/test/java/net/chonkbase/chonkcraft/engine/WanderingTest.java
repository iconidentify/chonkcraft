package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * Where an animal wanders to, and where it does not.
 *
 * <p>{@code MoveRandomly} draws a
 * square within {@code RandomMovementDistance}, clamps it to the map, and then
 * asks one question and only one: {@code UnitCanBeAt(unit, pos)}, with the
 * animal's own occupancy unmarked either side of it so that it does not block
 * itself. If the answer is no it commands nothing at all and stays where it
 * is. It never asks the planner -- there is no route in this decision, only a
 * square.
 *
 * <p>This implementation lost that question when {@code orderMove} stopped refusing
 * impossible destinations, and after that it commanded the walk whatever was
 * standing there. What that costs is a unit's <em>order</em> rather than its
 * position: the walk is blocked at its first step either way, but a unit under
 * a move order is not a unit standing still, and a unit standing still is the
 * one that looks around again. On
 * {@code maps/skirmish/(3)critter-attack} that was the whole of cycle 2, where
 * five critters draw a square with something already on it -- four a land unit
 * and one a building, printed from the running binary as {@code flags=1013}
 * and {@code 8013} against a critter's {@code mask=d160} -- and upstream left
 * all five standing. Restoring the question took that map's first divergence
 * from cycle 2 to cycle 46.
 */
class WanderingTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static AnimationSet animations() {
        AnimationSet set = new AnimationSet("critter");
        set.put(AnimationSet.State.STILL, Animation.parse("still", List.of(
                "frame 0", "wait 4", "random-goto 99 no-rotate",
                "random-rotate 1", "label no-rotate", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 4", "wait 2",
                "frame 0", "move 4", "wait 2", "frame 0", "move 4", "wait 2",
                "frame 0", "move 4", "wait 2", "frame 0", "move 4", "wait 2",
                "frame 0", "move 4", "wait 2", "frame 0", "move 4", "wait 2",
                "frame 0", "move 4", "unbreakable end", "wait 2")));
        return set;
    }

    /** ChonkCraft's critter: it wanders every time it is asked, one square. */
    private static UnitType critter() {
        UnitType type = new UnitType("unit-critter");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(5);
        type.setSpeed(3);
        type.setLandUnit(true);
        type.setNumDirections(8);
        type.setRandomMovementProbability(100);
        type.setRandomMovementDistance(1);
        type.setAnimationSet(animations());
        return type;
    }

    /** Something to be in the way that will not move out of it. */
    private static UnitType boulder() {
        UnitType type = new UnitType("unit-critter-blocker");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(100);
        type.setLandUnit(true);
        type.setNumDirections(1);
        return type;
    }

    @Test
    @DisplayName("an animal boxed in by its neighbours stays standing, order and all")
    void aWanderOntoAnOccupiedSquareIsNotCommanded() {
        World world = new World(grass(20));
        Unit animal = world.createUnit(critter(), 0, 5, 5);
        // Every square it could draw is taken, and its own is the only one
        // left, so every draw it makes is refused.
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx != 0 || dy != 0) {
                    world.createUnit(boulder(), 0, 5 + dx, 5 + dy);
                }
            }
        }

        for (int cycle = 0; cycle < 200; cycle++) {
            world.tick();
            assertEquals(Unit.Order.STILL, animal.order(),
                    "on cycle " + cycle + " the animal took a move order to a square with"
                            + " somebody already on it. MoveRandomly asks UnitCanBeAt and"
                            + " commands nothing when the answer is no, and the difference"
                            + " is not where the animal ends up -- it is blocked either way"
                            + " -- but whether it counts as standing still, which is what"
                            + " decides whether it looks around again");
        }
        assertEquals(5, animal.tileX(), "the animal got out of the box");
        assertEquals(5, animal.tileY(), "the animal got out of the box");
    }

    @Test
    @DisplayName("with room to move it still wanders, so the check has not simply frozen it")
    void anAnimalWithRoomStillWanders() {
        World world = new World(grass(20));
        Unit animal = world.createUnit(critter(), 0, 5, 5);

        boolean moved = false;
        for (int cycle = 0; cycle < 200 && !moved; cycle++) {
            world.tick();
            moved = animal.tileX() != 5 || animal.tileY() != 5;
        }

        assertNotEquals(false, moved,
                "the animal never wandered at all in two hundred cycles with open ground on"
                        + " every side, so the square test above is refusing everything and"
                        + " the first test passes for the wrong reason");
    }

    @Test
    @DisplayName("an animal that has just stopped looks around again at once")
    void aFreshStillOrderScansImmediately() {
        World world = new World(grass(20));
        Unit animal = world.createUnit(critter(), 0, 10, 10);

        // Every walk it makes is over in a few cycles, so what decides how
        // often it sets off again is when it next looks around. Upstream comes
        // back from a walk into a brand new still order, whose sleep starts at
        // nought (COrder_Still::Sleep = 0, action/action_still.h:64), so its
        // first standing cycle is a scanning cycle. This implementation kept its scan
        // counters on the unit, where they ran down across the walk, and an
        // animal that stopped had to wait out whatever was left of a scan it
        // had already had.
        int steps = 0;
        int wasX = animal.tileX();
        int wasY = animal.tileY();
        for (int cycle = 0; cycle < 200; cycle++) {
            world.tick();
            if (animal.tileX() != wasX || animal.tileY() != wasY) {
                steps++;
                wasX = animal.tileX();
                wasY = animal.tileY();
            }
        }

        // The arithmetic, rather than a number read off a run: the walk is
        // sixteen cycles, the route it walked out costs ten more before a new
        // one is asked for, and the animal wanders on every scan. So one that
        // scans the cycle it comes back to standing takes a step about every
        // twenty-seven -- seven in two hundred -- and one that must first wait
        // out a fifteen-cycle counter it carried through the walk takes one
        // about every forty-two, or four or five. Six sits between them.
        assertTrue(steps >= 6,
                "the animal took " + steps + " steps in two hundred cycles, which is the"
                        + " pace of an animal waiting out a scan counter it kept across its"
                        + " walk rather than one coming back to a fresh still order");
    }
}
