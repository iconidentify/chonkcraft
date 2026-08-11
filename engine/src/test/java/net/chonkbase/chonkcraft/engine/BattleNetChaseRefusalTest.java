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
 * What a unit chasing a quarry does when a friend stands in its way.
 *
 * <p>Implements the give-up arm of {@code fcn.004379e0} in Warcraft II
 * Battle.net Edition 2.02b, reached from the attack order's walk. The handler
 * adds {@code 0x1000} to the word at record offset {@code 0x1c} at
 * {@code 0x00437a0d}, in its entry block, before the player-kind arm and
 * before any of the three bands -- so the bump dominates every exit the
 * function has, and giving a route up counts as a refusal exactly like
 * standing and waiting does. From the eighth the handler leaves the movement
 * timer at the fifteen written at {@code 0x00437a25} and the unit stops
 * trying.
 *
 * <p>Grunt 1505 in {@code retail-xhuman-04-idle} is the witness this was read
 * against. It stands on 77,61 under an attack order with a friend in front of
 * it; retail parks its route on cycle 24 with the nibble going 1 to 2, refuses
 * again every cycle to 7, and on the eighth at cycle 30 stands still for
 * fifteen cycles. This implementation used to clear the route on the same cycle 24 with
 * both counters frozen, so the eighth never arrived, and the grunt walked off
 * the square on 39 -- which let the axethrower behind it take a square retail
 * plans around.
 */
class BattleNetChaseRefusalTest {

    /**
     * A one-tile gully across an otherwise unwalkable map. The point of the
     * gully is that there is no way round the friend: in open country the
     * planner simply draws a route past him and the refusal never happens, so
     * the walls are what make the blocked step the only step there is.
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
        set.put(AnimationSet.State.ATTACK, Animation.parse("attack", List.of(
                "unbreakable begin", "frame 0", "attack", "unbreakable end",
                "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    /**
     * The sleep is not a wait for the way to clear, which is what makes it
     * measurable from outside. Retail's own capture says so twice: peon 1521
     * in {@code retail-orc-12-idle} has the square it wanted free again eleven
     * cycles into the sleep and does not take it, and grunt 1505 above sits
     * out the whole fifteen. A port that never counts the refusal has nothing
     * to sleep on and sets off the moment the friend steps aside.
     */
    @Test
    @DisplayName("a chaser that has been refused eight times ignores the way opening")
    void aChaserRefusedEightTimesDoesNotSetOffWhenTheWayClears() {
        World world = new World(gully(30, 10));
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        Unit chaser = world.createUnit(soldier(), 0, 10, 10);
        UnitType preyType = soldier();
        preyType.setCanAttack(false);
        Unit prey = world.createUnit(preyType, 1, 3, 10);
        UnitType friendType = soldier();
        Unit friend = world.createUnit(friendType, 0, 9, 10);
        assertTrue(chaser != null && prey != null && friend != null,
                "the chaser, its quarry and the friend in the way must place");
        assertTrue(world.orderAttack(chaser, prey), "the attack was refused");

        // The friend has to look like a walker to the planner and like a
        // stopped one to the walk, and both halves matter. The planner stands
        // an ally that is on its way somewhere aside -- 0x00450766 -- so the
        // route is drawn straight through him and only the step finds him
        // there and refuses. That is the whole reason a unit can refuse the
        // same square seven times running instead of being told once that
        // there is no way past: the planner and the step do not ask the same
        // question. Here the pixel offset is what this implementation reads for "on his
        // way", so the friend owes pixels and holds no route. Holding no
        // route is the second half: 0x0044fa20 cannot then say where he is
        // going, so he is not the cooperative blocker that earns the other
        // fifteen -- the one that keeps its route -- and every visit is the
        // ordinary give-up.
        friend.clearPath();
        friend.setOffset(-16, 0);
        friend.animation().switchTo(
                friendType.animationSet().get(AnimationSet.State.MOVE));

        int refusedVisits = 0;
        for (int cycle = 0; cycle < 40 && refusedVisits < 8; cycle++) {
            world.tick();
            if (chaser.tileX() == 10 && chaser.tileY() == 10
                    && chaser.pathLength() == 0) {
                refusedVisits++;
            }
        }
        assertEquals(8, refusedVisits,
                "the chaser must be turned back eight times before the sleep,"
                        + " and it was turned back " + refusedVisits);
        assertEquals(10, chaser.tileX(),
                "the chaser has not got past its friend yet");
        assertEquals(10, chaser.tileY(),
                "the chaser has not got past its friend yet");

        // The way opens. Retail does not care.
        world.remove(friend);
        for (int quiet = 0; quiet < 12; quiet++) {
            world.tick();
            assertEquals(10, chaser.tileX(),
                    "the eighth refusal buys fifteen cycles of standing still,"
                            + " and the chaser set off on cycle " + quiet
                            + " after the way cleared");
            assertEquals(10, chaser.tileY(),
                    "the eighth refusal buys fifteen cycles of standing still,"
                            + " and the chaser set off on cycle " + quiet
                            + " after the way cleared");
        }

        boolean setOff = false;
        for (int cycle = 0; cycle < 20 && !setOff; cycle++) {
            world.tick();
            setOff = chaser.tileX() != 10 || chaser.tileY() != 10;
        }
        assertTrue(setOff,
                "once the sleep is over the chaser must go after its quarry"
                        + " again rather than stand on 10,10 for good");
    }
}
