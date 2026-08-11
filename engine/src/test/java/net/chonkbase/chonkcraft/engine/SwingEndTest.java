package net.chonkbase.chonkcraft.engine;

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
 * A fighter animates its swing and decides in the same breath.
 *
 * <p>{@code COrder_Attack::AttackTarget} opens with the animation and only
 * then asks whether to stop:
 *
 * <pre>
 * AnimateActionAttack(unit, *this);
 * if (unit.Anim.Unbreakable || this-&gt;Finished) {
 *     return;
 * }
 * </pre>
 *
 * <p>So on the cycle a swing ends -- the {@code wait 1} that follows
 * {@code unbreakable end} -- the animation advances <em>and</em> the order
 * decides. This implementation had it the other way round: it animated only while the
 * flag was set, so the closing cycle was skipped and the next call to
 * {@code strike} switched the animation back to its start.
 *
 * <p>What the order does with that cycle is the readable half, and it is not
 * a step. Its out-of-range arm is {@code unit.Frame = 0},
 * {@code State |= MOVE_TO_TARGET}, {@code TurnToTarget}, and a return
 * the step belongs to
 * {@code MoveToTarget} on the cycle after. Its last line is
 * {@code TurnToTarget} rather than another swing, because
 * {@code AnimateActionAttack} has already run once at the top.
 *
 * <p>The swing arm is reached by state and not by animation. {@code Execute}
 * sends a unit to {@code AttackTarget} only in the ATTACK_TARGET state, so one
 * that has gone back to MOVE_TO_TARGET is animating its walk however recently
 * it struck. Reading the animation alone left a unit that ended a swing and
 * set off after its target still holding the attack animation, so it read as
 * swinging, never switched, and stood there swinging at nothing -- demo03
 * measured 4,983 findings against 2,980.
 *
 * <p>On {@code maps/demo/demo02} this was the last two findings: upstream's
 * two battleships and this implementation's part company at cycle 233 and the two traces
 * now agree over the whole 300-cycle window, with the first divergence over
 * 900 moving from 233 to 368. On {@code maps/demo/demo03} it moved from 71
 * to 82.
 */
class SwingEndTest {

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
        set.put(AnimationSet.State.STILL, Animation.parse("still",
                List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        // Long enough on both sides of the blow that the cycle which closes
        // the loop is a reading of its own rather than lost in the noise.
        set.put(AnimationSet.State.ATTACK, Animation.parse("attack", List.of(
                "unbreakable begin", "frame 0", "wait 3",
                "frame 5", "attack", "wait 4",
                "unbreakable end", "wait 1")));
        return set;
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
        type.setBasicDamage(4);
        type.setMaxAttackRange(1);
        type.setSightRange(12);
        type.setNumDirections(8);
        type.setAnimationSet(animations());
        return type;
    }

    /** Something that stands there and takes it, so nothing else draws. */
    private static UnitType dummy() {
        UnitType type = soldier();
        type.setCanAttack(false);
        type.setHitPoints(4000);
        return type;
    }

    @Test
    @DisplayName("and the cycle a swing ends is not also a step")
    void theSwingsEndIsNotAStep() {
        World world = new World(grass(24));
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);

        Unit fighter = world.createUnit(soldier(), 0, 10, 10);
        Unit sandbag = world.createUnit(dummy(), 1, 11, 10);
        assertTrue(world.orderAttack(fighter, sandbag), "the attack was refused");

        // Let a swing get going, then walk the target out of reach. The cycle
        // the swing closes on is the cycle the order notices, and upstream
        // spends it setting MOVE_TO_TARGET rather than stepping.
        for (int cycle = 0; cycle < 4; cycle++) {
            world.tick();
        }
        assertTrue(world.orderMove(sandbag, 20, 10), "the sandbag would not walk away");

        int noticed = -1;
        int stepped = -1;
        for (int cycle = 1; cycle <= 60 && stepped < 0; cycle++) {
            world.tick();
            if (noticed < 0 && fighter.chasing()) {
                noticed = cycle;
            }
            if (noticed > 0 && (fighter.tileX() != 10 || fighter.offsetX() != 0)) {
                stepped = cycle;
            }
        }

        assertTrue(noticed > 0, "the fighter never gave chase, so nothing below counts");
        assertTrue(stepped > noticed,
                "the fighter took the chase and stepped on the same cycle, " + noticed
                        + ". AttackTarget's out-of-range arm sets MOVE_TO_TARGET and returns;"
                        + " the step belongs to MoveToTarget, which is the cycle after");
    }
}
