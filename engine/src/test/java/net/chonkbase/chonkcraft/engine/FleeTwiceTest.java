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
 * A coward struck twice in one cycle runs twice.
 *
 * <p>{@code HitUnit}'s run-away arm asks
 * {@code target.CurrentAction() == UnitAction::Still}, and the run it issues is
 * {@code CommandMove(target, pos, EFlushMode::Off)} -- appended, not flushed.
 * So the order the unit was running stays at {@code Orders[0]} and
 * {@code CurrentAction()} goes on answering {@code Still} for the rest of that
 * cycle. A second blow in the same cycle therefore finds it standing still
 * again and sends it running again.
 *
 * <p>That is not free. Each run calls {@code GetRndPosInDirection}
 * which spends three numbers from the shared
 * stream -- one for the range and one for each axis of the scatter. On
 * {@code maps/demo/demo03} two grunts reach the peasant at 9,2 on the same
 * cycle: upstream draws fifteen numbers on cycle 39 and this implementation drew twelve,
 * because its peasant had already been given a move order by the first blow and
 * so did not answer the second. That map's first divergence moved from 39 to 40.
 */
class FleeTwiceTest {

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
        return set;
    }

    private static UnitType grunt() {
        UnitType type = new UnitType("unit-grunt");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(6);
        type.setMaxAttackRange(1);
        type.setSightRange(9);
        type.setNumDirections(8);
        type.setAnimationSet(animations());
        return type;
    }

    /** A worker: it can move, it cannot fight, so a blow sends it running. */
    private static UnitType peasant() {
        UnitType type = new UnitType("unit-peasant");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setNumDirections(8);
        type.setAnimationSet(animations());
        return type;
    }

    @Test
    @DisplayName("two blows in one cycle send a coward running twice, at three draws each")
    void asecondBlowInTheSameCycleAlsoRuns() {
        World world = new World(grass(30));
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        Unit prey = world.createUnit(peasant(), 1, 15, 15);
        Unit first = world.createUnit(grunt(), 0, 14, 15);
        Unit second = world.createUnit(grunt(), 0, 16, 15);

        assertEquals(Unit.Order.STILL, prey.order(), "the fixture starts it standing still");
        int health = prey.hitPoints();

        // Both blows inside one cycle, which is the case this is about, and
        // counted rather than merely watched: a blow that lands is one number
        // for the damage, and a blow that also sends the target running is
        // three more for GetRndPosInDirection's range and two axes.
        long start = world.randomDraws();
        world.hit(first, prey);
        long afterOne = world.randomDraws();
        world.hit(second, prey);
        long afterTwo = world.randomDraws();

        assertTrue(prey.hitPoints() < health, "neither blow landed, so nothing below counts");
        assertEquals(4, afterOne - start,
                "the first blow spent " + (afterOne - start) + " numbers, where a blow that"
                        + " lands and sends its target running is one and three");
        assertEquals(4, afterTwo - afterOne,
                "the second blow spent " + (afterTwo - afterOne) + ". The first gave the"
                        + " peasant a move order and this port stopped there -- but upstream"
                        + " appends that order rather than flushing it, so CurrentAction still"
                        + " answers Still and the second blow sends it running again");

        // And appended means appended. Without the flush ReleaseOrders never
        // runs, so both runs sit behind the order the unit was already under
        // and it takes the first of them -- a different square from the second,
        // which is what replacing the order outright would have given it.
        assertEquals(Unit.Order.STILL, prey.order(),
                "the peasant is walking already, so the second blow could not have found it"
                        + " standing still and the reading above is luck");
        assertEquals(2, prey.queuedOrders().size(),
                "the two runs should be waiting behind what it was doing, in the order they"
                        + " were commanded");
    }

    @Test
    @DisplayName("a flee appended behind a finished order executes on the pop cycle")
    void anAppendedFleePopsBeforeTheFinishedOrderExecutesAgain() {
        World world = new World(grass(30));
        Unit prey = world.createUnit(peasant(), 1, 15, 15);
        prey.setOrder(Unit.Order.RETURN_GOODS);
        prey.setOrderFinished(true);
        prey.enqueueOrder(new Unit.QueuedOrder(Unit.QueuedOrderKind.MOVE,
                14, 16, null, null, null));

        world.tick();

        assertEquals(Unit.Order.MOVE, prey.order());
        assertEquals(14, prey.tileX(),
                "the replacement was installed after rather than before Execute");
        assertEquals(16, prey.tileY());
    }
}
