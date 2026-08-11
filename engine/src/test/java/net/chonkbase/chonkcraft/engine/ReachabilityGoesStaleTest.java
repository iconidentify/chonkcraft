package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * "Can I get at that?" is true of a moment, not of a map.
 *
 * <p>Whether one unit can reach another is answered by a route search, and a
 * route search answers with everybody's positions in it: upstream's A* refuses
 * to cross a unit that is standing still. So a queue of your own men is a wall for as long
 * as it stands there and not a moment longer, and
 * {@code COrder_Still::AutoAttack} asks the question again on every idle scan.
 *
 * <p>This implementation cached the answer until the terrain changed, for the sake of
 * the search cost, which made "no" permanent. On {@code maps/demo/demo03} an
 * ogre at 15,3 and a grunt at 13,3 are sealed off from the peasant every unit
 * on the map is converging on -- by rock below and by three of their own
 * grunts standing in a column at 11,0, 11,1 and 11,2 above. Those three walk
 * away on cycle 2. Upstream's ogre asks again on its next idle scan, at cycle
 * 17, finds the way open and goes; this implementation's was still holding the answer it
 * got on cycle 1, and stood there for the rest of the game.
 */
class ReachabilityGoesStaleTest {

    /**
     * A one-square passage down x=5 with a dead-end arm east at y=2.
     *
     * <p>The arm is north of where the blocker stands and the enemy is south
     * of it, so it is somewhere for the blocker to go and never a way round
     * it.
     */
    private static GameMap corridor(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            map.field(5, y).setFlags(TileFlag.LAND_ALLOWED);
        }
        map.field(6, 2).setFlags(TileFlag.LAND_ALLOWED);
        map.field(7, 2).setFlags(TileFlag.LAND_ALLOWED);
        return map;
    }

    private static AnimationSet animations() {
        AnimationSet set = new AnimationSet("test");
        set.put(AnimationSet.State.STILL, Animation.parse("still", List.of(
                "frame 0", "wait 4", "random-goto 99 no-rotate",
                "random-rotate 1", "label no-rotate", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 4", "wait 1",
                "frame 5", "move 4", "wait 1", "frame 10", "move 4", "wait 1",
                "frame 15", "move 4", "wait 1", "frame 0", "move 4", "wait 1",
                "frame 5", "move 4", "wait 1", "frame 10", "move 4", "wait 1",
                "frame 15", "move 4", "unbreakable end", "wait 1")));
        return set;
    }

    /**
     * Two cycles a square, so that the whole of the blocker's walk fits
     * between two of the soldier's idle scans.
     *
     * <p>It has to. A unit that is moving is <em>not</em> a wall to either
     * engine's A* -- {@code goal->Moving} takes the crossing-cost arm -- so a
     * scan that lands while the blocker is in motion finds the way open for a
     * reason that has nothing to do with the question here, and a stale
     * answer latched to true reads the same as a fresh one.
     */
    private static AnimationSet quickAnimations() {
        AnimationSet set = new AnimationSet("quick");
        set.put(AnimationSet.State.STILL, Animation.parse("still", List.of(
                "frame 0", "wait 4", "random-goto 99 no-rotate",
                "random-rotate 1", "label no-rotate", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        return set;
    }

    private static UnitType footman(String ident, boolean fights) {
        UnitType type = new UnitType(ident);
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setNumDirections(8);
        type.setSightRange(30);
        type.setAnimationSet(animations());
        if (fights) {
            type.setCanAttack(true);
            type.setCanTargetLand(true);
            type.setBasicDamage(6);
            type.setMaxAttackRange(1);
            type.setReactRangePerson(20);
            type.setReactRangeComputer(20);
        }
        return type;
    }

    @Test
    @DisplayName("a unit walled in by its own side notices when they walk away")
    void theAnswerIsAskedAgainWhenTheWayOpens() {
        World world = new World(corridor(20));
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);

        Unit soldier = world.createUnit(footman("unit-footman", true), 0, 5, 0);
        world.createUnit(footman("unit-orc", true), 1, 5, 10);
        // In the passage between the two, and going nowhere until it is told
        // to. A unit standing still is a wall to upstream's A* as much as to
        // this one, so the enemy is genuinely out of reach to begin with.
        UnitType peasant = footman("unit-peasant", false);
        peasant.setAnimationSet(quickAnimations());
        Unit blocker = world.createUnit(peasant, 0, 5, 5);

        world.tick();
        assertNull(soldier.pendingAttack(),
                "the soldier picked a fight through a sealed passage, so the fixture is not"
                        + " sealed and nothing below measures what it says it does");

        // Out of the way, up the passage and into the arm, which is behind the
        // soldier and so was never a route to anywhere.
        assertTrue(world.orderMove(blocker, 7, 2), "the blocker would not move");

        Unit chosen = null;
        int noticed = -1;
        for (int cycle = 1; cycle <= 400 && chosen == null; cycle++) {
            world.tick();
            chosen = soldier.pendingAttack() != null ? soldier.pendingAttack() : soldier.target();
            if (chosen != null) {
                noticed = cycle;
            }
        }

        assertNotNull(chosen, "the soldier never picked a fight at all, in four hundred"
                + " cycles, with an enemy it could walk to for most of them");
        assertTrue(blocker.tileX() == 7 && blocker.tileY() == 2 && !blocker.isMoving(),
                "the blocker is at " + blocker.tileX() + "," + blocker.tileY()
                        + ", so the passage was not clear and standing still when this"
                        + " measured what it measured");
        // The blocker is out of the passage inside a dozen cycles and the
        // soldier scans for a fight every sixteen, so the first scan after the
        // way opens is the answer. A search whose answer is kept past the
        // cycle that produced it makes the soldier wait for whatever else
        // happens to throw the answer away -- here another sixty cycles.
        assertTrue(noticed <= 32,
                "the soldier took " + noticed + " cycles to notice an enemy it could reach"
                        + " from about cycle 12. Whether a unit can get at something is true"
                        + " of the moment its route search was run and of nothing else:"
                        + " upstream asks again on every idle scan, and keeping the answer"
                        + " leaves a unit refusing a fight it could walk to");
    }
}
