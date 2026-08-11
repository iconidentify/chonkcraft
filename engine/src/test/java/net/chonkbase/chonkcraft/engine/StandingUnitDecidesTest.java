package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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
 * The order a standing unit does its two jobs in: breathe, then decide.
 *
 * <p>{@code COrder_Still::Execute}
 * opens with {@code UnitShowAnimation(unit, &unit.Type->Animations->Still)},
 * returns if that animation turns out to be unbreakable, and only then reaches
 * {@code AutoCast}, {@code AutoAttack}, {@code AutoRepair} and
 * {@code MoveRandomly}. This implementation decided first and animated only if nothing
 * came of it, so a unit that found something to shoot at spent that cycle
 * without breathing.
 *
 * <p>One cycle, once, for as long as the unit lives -- and it matters because
 * ChonkCraft's Still loop is five cycles long and spends a number from the shared
 * random stream every time round ({@code scripts/anim.legacy-declaration:31}). A unit whose
 * loop starts a cycle late draws a cycle late forever. On
 * {@code maps/demo/demo03} that is the whole of cycle 6: a grunt at 11,2 gives
 * itself an attack order on cycle 1, finds its way blocked and sleeps, and
 * upstream's breathes on cycles 6 and 11 where this implementation's breathed on 7 and
 * 12 -- every draw either engine made afterwards a different number.
 */
class StandingUnitDecidesTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    /** ChonkCraft's own UnitStill: five cycles a lap, one draw a lap. */
    private static AnimationSet breathing() {
        AnimationSet set = new AnimationSet("breathing");
        set.put(AnimationSet.State.STILL, Animation.parse("still", List.of(
                "frame 0", "wait 4", "random-goto 99 no-rotate",
                "random-rotate 1", "label no-rotate", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 4", "wait 1",
                "frame 5", "move 4", "unbreakable end", "wait 1")));
        return set;
    }

    private static UnitType footman() {
        UnitType type = new UnitType("unit-footman");
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(6);
        type.setMaxAttackRange(1);
        type.setReactRangePerson(6);
        type.setReactRangeComputer(6);
        type.setNumDirections(8);
        type.setBoxSize(31, 31);
        // Without a sight range the soldier sees nothing and never reaches the
        // branch this test is about.
        type.setSightRange(8);
        type.setAnimationSet(breathing());
        return type;
    }

    @Test
    @DisplayName("a unit breathes on the cycle it gives itself an attack order")
    void findingSomethingToShootAtDoesNotCostTheBreath() {
        World world = new World(grass(20));
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        Unit soldier = world.createUnit(footman(), 0, 5, 5);
        world.createUnit(footman(), 1, 9, 5);

        world.tick();

        // The branch this test is about is only reached when the unit really
        // does find a target, so say so rather than trusting it. The attack is
        // queued rather than taken -- AutoAttack leaves the still order in
        // place and the switch happens next cycle -- so the queue is what says
        // the scan found something.
        assertNotNull(soldier.pendingAttack(),
                "the soldier never noticed the enemy four squares away, so this measures"
                        + " a unit that simply stood there and proves nothing");

        Animation still = soldier.type().animationSet().get(AnimationSet.State.STILL);
        assertNotNull(soldier.animation().current(),
                "the soldier took its attack order without playing any animation at all."
                        + " COrder_Still::Execute animates before it decides, so the cycle"
                        + " a unit picks a target is still a cycle of its Still loop");
        assertSame(still, soldier.animation().current(),
                "the soldier is playing " + soldier.animation().current().name()
                        + " on the cycle it took the order, where upstream had already"
                        + " advanced Still before AutoAttack was reached");
    }
}
