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
 * A chaser that arrives does not swing in the same breath.
 *
 * <p>{@code COrder_Attack::MoveToTarget}'s arrival arm is four lines and the
 * last of them is the one that matters:
 *
 * <pre>
 * if (goal &amp;&amp; InAttackRange(unit, *goal)) {
 *     TurnToTarget(unit, goal);
 *     this-&gt;State &amp;= AUTO_TARGETING;
 *     this-&gt;State |= ATTACK_TARGET;
 *     return;
 * }
 * </pre>
 *
 * <p> It takes the state, turns the
 * unit and <em>returns</em>. The swing belongs to {@code AttackTarget}, which
 * the order reaches on the next cycle. A chaser that arrives and strikes in the
 * same cycle lands every blow one cycle early for the rest of the game -- and
 * the damage roll comes off the shared random stream, so every later roll in
 * both engines is a different number.
 *
 * <p>On {@code maps/demo/demo03} the peasant at 9,2 is struck on cycle 38 by
 * this implementation and 39 by upstream, and flees to 8,3 on 40 against 41: the same
 * fight, a cycle out. That map's first divergence moved from 38 to 39.
 */
class ArriveThenSwingTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
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
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(6);
        type.setMaxAttackRange(1);
        type.setSightRange(12);
        type.setNumDirections(8);
        AnimationSet set = new AnimationSet("soldier");
        set.put(AnimationSet.State.STILL, Animation.parse("still",
                List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        // The blow lands on the first cycle of the swing, so "when it struck"
        // and "when it started swinging" are the same reading and the one
        // cycle this test is about is not buried in an animation.
        set.put(AnimationSet.State.ATTACK, Animation.parse("attack", List.of(
                "unbreakable begin", "frame 0", "attack", "unbreakable end", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    @Test
    @DisplayName("the blow lands the cycle after the chaser comes into reach")
    void arrivalAndTheBlowAreDifferentCycles() {
        World world = new World(grass(30));
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        Unit chaser = world.createUnit(soldier(), 0, 5, 10);
        UnitType preyType = soldier();
        preyType.setCanAttack(false);
        Unit prey = world.createUnit(preyType, 1, 12, 10);
        assertTrue(world.orderAttack(chaser, prey), "the attack was refused");

        // The cycle the chase gives way to the fight -- upstream's
        // `State |= ATTACK_TARGET` -- against the cycle the blow lands. The
        // attack animation strikes on its first cycle, so those two readings
        // are one apart when the arrival arm returns and equal when it does
        // not.
        int health = prey.hitPoints();
        boolean wasChasing = false;
        int tookAim = -1;
        int struck = -1;
        for (int cycle = 1; cycle <= 300 && struck < 0; cycle++) {
            boolean before = chaser.chasing();
            world.tick();
            if (tookAim < 0 && before && !chaser.chasing()) {
                tookAim = cycle;
            }
            wasChasing |= before;
            if (prey.hitPoints() < health) {
                struck = cycle;
            }
        }

        assertTrue(wasChasing, "the chaser never gave chase, so nothing below counts");
        assertTrue(tookAim > 0, "the chase never gave way to the fight");
        assertTrue(struck > 0, "the chaser never struck at all");
        assertEquals(tookAim + 1, struck,
                "the chase gave way to the fight on cycle " + tookAim + " and the blow landed"
                        + " on " + struck + ". Upstream takes ATTACK_TARGET on the arrival"
                        + " cycle and returns; the swing is AttackTarget's, and that is the"
                        + " next cycle");
    }
}
