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
 * What happens on the cycle a unit's next square turns out to be taken.
 *
 * <p>{@code NextPathElement} answers {@code PF_WAIT}
 * {@code DoActionMove} turns
 * that into {@code unit.Wait = 10}:
 * ten cycles asleep, during which {@code COrder::IsWaiting} stands the unit up
 * and breathes its Still animation over the top. Every order that walks goes
 * through those two functions, so a chase waits exactly as a march does.
 *
 * <p>What is <em>not</em> shared is giving up. Ending the order because the
 * thing in the way is not going to move lives in {@code COrder_Move::Execute}
 * and nowhere else, so a unit chasing
 * something sleeps where a unit walking somewhere stops. This implementation applied its
 * version to both, which is why on {@code maps/demo/demo03} upstream's grunt at
 * 11,2 sits with {@code wait=7} on cycle 6, breathing, and this implementation's stood
 * up, re-planned, and drew nothing.
 *
 * <p>Both fixtures block the route <em>after</em> it has been laid, with the
 * unit already in the air on its first step. Standing somebody on the goal
 * square beforehand measures the pathfinder refusing the search instead, which
 * is a different mechanism and never reaches the code under test.
 */
class BlockedStepTest {

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
        AnimationSet set = new AnimationSet("test");
        set.put(AnimationSet.State.STILL, Animation.parse("still", List.of(
                "frame 0", "wait 4", "random-goto 99 no-rotate",
                "random-rotate 1", "label no-rotate", "wait 1")));
        // The trailing wait is not decoration. ChonkCraft's move animations all
        // end "unbreakable end", "wait 1", and without it the instruction loop
        // runs straight from the end back round to "unbreakable begin" without
        // ever spending a cycle outside the unbreakable stretch -- a unit that
        // is mid-step for ever.
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 4", "wait 1",
                "frame 5", "move 4", "unbreakable end", "wait 1")));
        return set;
    }

    private static UnitType footman() {
        UnitType type = new UnitType("unit-footman");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(6);
        type.setMaxAttackRange(1);
        type.setSightRange(8);
        type.setNumDirections(8);
        type.setAnimationSet(animations());
        return type;
    }

    private static World world() {
        World world = new World(grass(20));
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        return world;
    }

    @Test
    @DisplayName("a chase whose last step is taken sleeps rather than standing up")
    void aBlockedChaseWaits() {
        World world = world();
        Unit soldier = world.createUnit(footman(), 0, 5, 5);
        Unit enemy = world.createUnit(footman(), 1, 5, 8);
        assertTrue(world.orderAttack(soldier, enemy), "the attack was refused");

        world.tick();
        assertEquals(6, soldier.tileY(),
                "the soldier never set off, so nothing below is about a blocked step");

        // The square its route ends on, taken while it is still in the air on
        // the step before. Attack range is one, so 5,7 is where the chase was
        // going to stop and swing.
        world.createUnit(footman(), 0, 5, 7);

        int slept = 0;
        for (int cycle = 0; cycle < 30 && slept == 0; cycle++) {
            world.tick();
            slept = soldier.waitCycles();
        }

        assertEquals(10, slept,
                "the soldier never slept. DoActionMove sets unit.Wait = 10 on PF_WAIT"
                        + " whatever order asked for the step, and those ten cycles are"
                        + " ten cycles of the Still animation -- two laps of a five-cycle"
                        + " loop, two draws off the shared random stream. Standing up and"
                        + " re-planning instead, which is what this did, spends neither");
        assertEquals(Unit.Order.ATTACK, soldier.order(),
                "the soldier gave up its attack the first time somebody was in the way."
                        + " Ending the order belongs to COrder_Move::Execute; an attack"
                        + " that cannot take its step waits and tries again");
    }

    @Test
    @DisplayName("a march whose last square is taken stops short of it and does not sleep")
    void aBlockedMarchStopsShort() {
        World world = world();
        Unit soldier = world.createUnit(footman(), 0, 5, 5);
        assertTrue(world.orderMove(soldier, 5, 7), "the order was refused");

        world.tick();
        assertEquals(6, soldier.tileY(),
                "the soldier never set off, so nothing below is about a blocked step");
        world.createUnit(footman(), 0, 5, 7);

        // The order ends on the cycle the refusal happens, and unit.Wait is
        // zeroed on that arm, so this march never sleeps at all. Waiting is
        // what says the rule did not fire: without it the soldier sits out
        // its ten cycles and the order ends later, by another route entirely.
        int longestSleep = 0;
        for (int cycle = 0; cycle < 30 && soldier.order() == Unit.Order.MOVE; cycle++) {
            world.tick();
            longestSleep = Math.max(longestSleep, soldier.waitCycles());
        }

        assertEquals(Unit.Order.STILL, soldier.order(),
                "the soldier is still marching at a square it can never stand on."
                        + " COrder_Move::Execute finishes the order when the blocker on"
                        + " its goal is next to it and going nowhere");
        assertEquals(6, soldier.tileY(), "the march did not stop where it ran out of room");
        assertEquals(0, longestSleep,
                "the march slept " + longestSleep + " cycles instead of ending. The"
                        + " blocker is standing on the square the order named, one square"
                        + " away and going nowhere, which is exactly the case upstream"
                        + " answers with unit.Wait = 0 and Finished = true");
    }

    @Test
    @DisplayName("the cycle that march ends is still a marching cycle when it is read")
    void aFinishedMarchIsStillTheCurrentAction() {
        World world = world();
        Unit soldier = world.createUnit(footman(), 0, 5, 5);
        assertTrue(world.orderMove(soldier, 5, 7), "the order was refused");

        world.tick();
        assertEquals(6, soldier.tileY(), "the soldier never set off");
        world.createUnit(footman(), 0, 5, 7);

        // Upstream's orders finish by setting a flag rather than by being
        // replaced: this->Finished = true leaves Orders[0] where it is, and
        // CurrentAction() goes on answering with it until HandleUnitAction
        // pops it on the next cycle. So the cycle a march gives up is still a
        // marching cycle when the world is read, and a standing one only from
        // the cycle after. On (3)critter-attack that is worth a cycle by
        // itself: two animals give up walking on 46, and upstream reads them
        // as moving there where this implementation read them as still.
        Unit.Order reportedWhenItEnded = null;
        for (int cycle = 0; cycle < 30; cycle++) {
            world.tick();
            if (soldier.order() == Unit.Order.STILL) {
                reportedWhenItEnded = soldier.currentAction();
                break;
            }
        }

        assertEquals(Unit.Order.MOVE, reportedWhenItEnded,
                "the cycle the march ended already read as standing still");
    }

}
