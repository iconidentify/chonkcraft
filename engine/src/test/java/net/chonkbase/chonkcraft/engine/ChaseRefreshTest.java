package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
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
 * A chaser lays its next course before anything asks whether to bother.
 *
 * <p>The route refresh is not part of the attack order at all. It lives inside
 * {@code NextPathElement}, which begins
 * {@code unit.CurrentOrder()->UpdatePathFinderData(input)} and then re-plans
 * whenever the stored route has run out or the goal has moved
 * {@code DoActionMove} calls that,
 * and {@code COrder_Attack::MoveToTarget} calls {@code DoActionMove} as its
 * first act -- so the step is taken before {@code CheckForTargetInRange} is
 * reached, and {@code CheckForTargetInRange} is the only thing that asks
 * whether the goal is still worth having.
 *
 * <p>So a chaser whose quarry has just begun to die re-plans at it, steps, and
 * is told to stop afterwards. This implementation asked first: it kept the target,
 * refused to re-plan for something dying, and stood still. On
 * {@code maps/demo/demo03} a footman at 8,1 chasing a peasant that died at 7,4
 * on cycle 60 re-plans on 67 and steps west to 7,1; this implementation's stayed at 8,1
 * for the rest of the run, and that map's first divergence moved from cycle 67
 * to 71.
 */
class ChaseRefreshTest {

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
        set.put(AnimationSet.State.ATTACK, Animation.parse("attack", List.of(
                "unbreakable begin", "frame 0", "attack", "unbreakable end", "wait 1")));
        // Long enough that the quarry is dying rather than gone for the whole
        // of the window this test watches.
        set.put(AnimationSet.State.DEATH, Animation.parse("death",
                List.of("frame 5", "wait 40", "frame 10", "wait 40")));
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
        type.setBasicDamage(6);
        type.setMaxAttackRange(1);
        type.setSightRange(12);
        type.setNumDirections(8);
        type.setAnimationSet(animations());
        return type;
    }

    private static UnitType prey() {
        UnitType type = new UnitType("unit-peasant");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setNumDirections(8);
        type.setCorpse("unit-human-dead-body");
        type.setAnimationSet(animations());
        return type;
    }

    private static UnitType body() {
        UnitType type = new UnitType("unit-human-dead-body");
        type.setTileSize(1, 1);
        type.setHitPoints(1);
        type.setVanishes(true);
        type.setNumDirections(1);
        AnimationSet set = new AnimationSet("dead-body");
        set.put(AnimationSet.State.DEATH, Animation.parse("death",
                List.of("frame 0", "wait 20")));
        type.setAnimationSet(set);
        return type;
    }

    @Test
    @DisplayName("a chaser re-plans at a quarry that has begun to die, and steps")
    void theRefreshDoesNotAskWhetherTheTargetIsWorthIt() {
        World world = new World(grass(30));
        world.setUnitTypes(Map.of("unit-human-dead-body", body()));
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);

        Unit chaser = world.createUnit(soldier(), 0, 5, 10);
        Unit quarry = world.createUnit(prey(), 1, 14, 10);
        assertTrue(world.orderAttack(chaser, quarry), "the attack was refused");

        // Let the chase get going, then put the chaser in the state the
        // refresh is for and only for: standing between two courses, with the
        // square its last course was aimed at no longer the one its quarry is
        // on. That is where demo03's footman was -- ten cycles of waiting out
        // a refused step, and its peasant had walked on and died meanwhile --
        // and it is the only state in which the refresh decides anything.
        for (int cycle = 0; cycle < 6; cycle++) {
            world.tick();
        }
        assertTrue(chaser.chasing(), "the chaser never gave chase, so nothing below counts");
        while (chaser.isMoving()) {
            world.tick();
        }
        chaser.clearPath();
        chaser.setPathGoal(quarry.tileX() + 3, quarry.tileY());
        int startedAt = chaser.tileX();

        // And the quarry starts dying, which is what this implementation used to refuse
        // to re-plan for.
        world.kill(quarry);
        assertTrue(quarry.isDying(), "the quarry died outright rather than starting to die");

        world.tick();

        assertTrue(chaser.pathLength() > 0 || chaser.tileX() != startedAt
                        || chaser.isMoving(),
                "the chaser laid no course at all. NextPathElement lays the next one before"
                        + " MoveToTarget reaches anything that examines the goal, so a chaser"
                        + " re-plans at something dying and is told to stop afterwards");
    }

    @Test
    @DisplayName("and it does stop, once something has looked")
    void theOrderStillEnds() {
        World world = new World(grass(30));
        world.setUnitTypes(Map.of("unit-human-dead-body", body()));
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);

        Unit chaser = world.createUnit(soldier(), 0, 5, 10);
        Unit quarry = world.createUnit(prey(), 1, 14, 10);
        assertTrue(world.orderAttack(chaser, quarry), "the attack was refused");
        for (int cycle = 0; cycle < 6; cycle++) {
            world.tick();
        }
        world.kill(quarry);
        for (int cycle = 0; cycle < 200 && chaser.order() != Unit.Order.STILL; cycle++) {
            world.tick();
        }

        assertEquals(Unit.Order.STILL, chaser.order(),
                "the chaser is still under an attack order for something that has been dead"
                        + " for two hundred cycles. Not asking during the refresh is not the"
                        + " same as never asking");
    }
}
