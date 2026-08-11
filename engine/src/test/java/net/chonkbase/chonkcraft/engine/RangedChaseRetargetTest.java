package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.pathfinder.PathFinder;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What an axethrower pays when it changes its mind about who to shoot.
 *
 * <p>A running chase carries a route: a short stack of headings and a cursor
 * into it. Battle.net Edition charges a chaser three still cycles for throwing
 * that route away, and it charges them before the replacement exists. Retail's
 * Human 13 axethrower in pool slot 1505 shows both halves of that: at fixture
 * cycle 25 it swaps the wise-man at (122,29) for the knight at (120,29), leaves
 * the old heading bytes exactly where they were, and moves only its cursor to
 * 20 -- past the end, the mark for "there is no route here". Its animation
 * changes to four with a timer of three, and it stands on (124,25) through 26
 * and 27. At 28 the wise-man is the better target again, and that one cycle
 * writes the fresh route, spends its first heading and lands the unit on
 * (123,26).
 *
 * <p>So the charge is for the tearing up, not for the choosing. A chaser that
 * comes to a retarget already routeless has nothing to tear up and walks on the
 * cycle it decides. This implementation used to lay the new route at the same moment it
 * armed the hold, which left 1505 holding a live route when the wise-man came
 * back into reach at 28; that second change of mind bought a second three-cycle
 * hold and the diagonal step landed at fixture 31. Native and this implementation then
 * agreed on every other field, so the whole of the difference was three cycles
 * of an axethrower standing on the wrong square.
 *
 * <p>Implements the retarget arm of {@code COrder_Attack::Execute}, whose Battle.net movement consult is
 * {@code FUN_0044fbd0}; the axethrower's stats are
 * {@code scripts/orc/units.legacy-declaration}.
 */
class RangedChaseRetargetTest {

    /** The three still cycles a torn-up route costs its chaser. */
    private static final int TEARDOWN_HOLD = 3;

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType axethrower() {
        UnitType type = new UnitType("unit-axethrower");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(40);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(6);
        type.setMaxAttackRange(4);
        type.setSightRange(9);
        type.setReactRangeComputer(9);
        type.setReactRangePerson(9);
        type.setNumDirections(8);
        AnimationSet set = new AnimationSet("axethrower");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 3", "wait 2",
                "frame 5", "move 3", "wait 2",
                "frame 5", "move 4", "wait 2",
                "frame 10", "move 3", "wait 2",
                "frame 10", "move 3", "wait 2",
                "frame 15", "move 3", "wait 2",
                "frame 15", "move 4", "wait 2",
                "frame 20", "move 3", "wait 2",
                "frame 20", "move 3", "wait 2",
                "frame 0", "move 3", "unbreakable end", "wait 1")));
        set.put(AnimationSet.State.ATTACK, Animation.parse("attack", List.of(
                "unbreakable begin", "frame 25", "wait 3", "frame 40", "attack",
                "wait 5", "frame 0", "unbreakable end", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    private static UnitType prey() {
        UnitType type = new UnitType("unit-wise-man");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(60);
        type.setLandUnit(true);
        type.setNumDirections(8);
        AnimationSet set = new AnimationSet("wise-man");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    /**
     * An axethrower already chasing {@code first}, standing on a move
     * boundary, with the route described by {@code headings} under its feet.
     * A rival appears where the auto-target scan will prefer it, so the next
     * cycle is the one that changes the chaser's mind.
     */
    private static int cyclesUntilItWalks(int[] headings) {
        GameMap map = grass(48);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        Unit thrower = world.createUnit(axethrower(), 0, 24, 4);
        // Far enough that the react scan cannot see it, so nothing competes
        // for the chase until the rival is placed.
        Unit first = world.createUnit(prey(), 1, 24, 44);
        assertTrue(thrower != null, "the axethrower must stand on (24,4)");
        assertTrue(first != null, "the first quarry must stand on (24,44)");
        assertTrue(world.orderAttack(thrower, first),
                "the axethrower must accept the chase");
        // Run the chase until a stride has finished, so the unit stands on a
        // move boundary the way slot 1505 stands on (124,25) at fixture 24.
        int settled = 0;
        while (settled < 60
                && !(thrower.chasing() && !thrower.animation().unbreakable())) {
            world.tick();
            settled++;
        }
        assertTrue(thrower.chasing(),
                "the axethrower must be chasing before its quarry changes");
        assertTrue(!thrower.animation().unbreakable(),
                "the axethrower must be between strides, not mid-stride, or the"
                        + " change of quarry cannot be reached at all");
        // The route under its feet, as 1505 carries four headings at 24.
        thrower.setPath(new PathFinder.Path(PathFinder.Result.FOUND, headings));
        thrower.setOffset(0, 0);
        thrower.setWalkHolding(false);
        thrower.setBattleNetOrderDelay(0);
        // Near enough for the react scan to prefer it, still beyond the axe's
        // own four squares so the chase carries on walking rather than firing.
        Unit rival = world.createUnit(prey(), 1,
                thrower.tileX(), thrower.tileY() + 6);
        assertTrue(rival != null, "the rival must stand six squares south");

        int startX = thrower.tileX();
        int startY = thrower.tileY();
        for (int cycle = 1; cycle <= 40; cycle++) {
            world.tick();
            if (thrower.tileX() != startX || thrower.tileY() != startY) {
                assertTrue(thrower.target() == rival,
                        "the axethrower must have changed quarry to the rival,"
                                + " or this measures an ordinary walk rather"
                                + " than the cost of tearing up a route");
                return cycle;
            }
        }
        return -1;
    }

    @Test
    @DisplayName("an axethrower that throws away a running route stands three cycles before its next step")
    void aTornUpRouteCostsTheChaserThreeStillCycles() {
        int south = Direction.fromDelta(0, 1);
        // Four headings left under the unit, as 1505 has at fixture cycle 24.
        int walked = cyclesUntilItWalks(new int[] {south, south, south, south});
        assertTrue(walked > 0,
                "the axethrower never left (24,10) in forty cycles, so there is"
                        + " no chase here to measure a hold against");
        assertEquals(TEARDOWN_HOLD + 1, walked,
                "an axethrower whose change of quarry tore up a live route must"
                        + " stand still for the three cycles retail's slot 1505"
                        + " spends on (124,25) at fixture 25, 26 and 27, and walk"
                        + " on the fourth; it walked on cycle " + walked);
    }

    @Test
    @DisplayName("an axethrower with no route left walks on the cycle it changes quarry")
    void aRoutelessRetargetWalksOnItsOwnCycle() {
        int walked = cyclesUntilItWalks(new int[0]);
        assertTrue(walked > 0,
                "the axethrower never left (24,10) in forty cycles, so there is"
                        + " no chase here to measure a hold against");
        assertEquals(1, walked,
                "a chaser that comes to its change of quarry already routeless"
                        + " has no route to tear up and pays nothing for it:"
                        + " retail's slot 1505 writes its new headings and spends"
                        + " the first of them in the single cycle 28. Standing"
                        + " here means the hold is being charged for choosing"
                        + " rather than for tearing up, which is what put 1505's"
                        + " diagonal at fixture 31 instead of 28. It walked on"
                        + " cycle " + walked);
    }
}
