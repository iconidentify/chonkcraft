package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Pure MOVE multi-step residual free-compasses after a soft-wait when the
 * peek is blocked by an ally that is no longer cooperative.
 *
 * <p>XHuman 12 ogre 1527 soft-waited on E onto a MOVE axe, then at the next
 * refuse ally ogre 1498 had coll!=0 so cooperative was false. PF_WAIT 10
 * popped E and delayed NE (native fixture 47) until fixture 57. Free-compass
 * toward pathGoal installs NE without the ten-cycle sleep.
 */
class PureMoveResidualFreeCompassTest {

    private static final int N = 0;
    private static final int NE = 1;
    private static final int E = 2;
    private static final int NW = 7;

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType ogre() {
        UnitType type = new UnitType("unit-ogre");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(90);
        type.setSpeed(13);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(8);
        type.setPiercingDamage(4);
        type.setMaxAttackRange(1);
        type.setSightRange(5);
        type.setNumDirections(8);
        AnimationSet set = new AnimationSet("ogre");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    @Test
    @DisplayName("wall-follow soft-clear refuses an ally with elevated collision")
    void wallFollowSoftClearRefusesAnAllyWithElevatedCollision() {
        // Native 0x4501bc: unit[0x1d] high nibble (collision) keeps occupancy
        // on the wall walk. Free-scan 1516 soft-cleared Attack walkers that
        // natively carry collision 1 at 31,40 so the second face rejoined
        // the goal skirt; zero-collision Move-body allies still clear.
        GameMap map = grass(16);
        World world = new World(map);
        world.fog().revealAll(0);
        Unit ally = world.createUnit(ogre(), 0, 4, 4);
        assertTrue(ally != null, "ally places");
        ally.setOrder(Unit.Order.MOVE);
        // isMoving is pixel residual, not path leftover.
        ally.setOffset(16, 0);
        assertTrue(ally.isMoving(), "residual offset makes the ally moving");

        ally.setBattleNetCollisionCounter(0);
        assertTrue(world.movement.battleNetSoftClearMoveAlly(ally),
                "zero-collision Move ally is soft-cleared for wall-follow");

        ally.setBattleNetCollisionCounter(1);
        assertFalse(world.movement.battleNetSoftClearMoveAlly(ally),
                "elevated collision keeps occupancy (native unit+0x1d high nibble)");

        // Java keeps the sticky 0x1d refusal history separately because its
        // short-lived collision proxy is cleared by several replan arms.
        // Native has one nibble, so either representation must keep the body
        // solid to wall-follow.
        ally.setBattleNetCollisionCounter(0);
        ally.setBattleNetRefusals(8);
        assertFalse(world.movement.battleNetSoftClearMoveAlly(ally),
                "sticky refusal history also keeps native occupancy solid");
    }

    @Test
    @DisplayName("a one-heading free-compass keeps multi-refuse collision")
    void aOneHeadingFreeCompassKeepsMultiRefuseCollision() {
        // Free-scan 1516: grunt 90 free-compass SE after a long soft-wait must
        // still refuse wall soft-clear at 34,40 (native 0x4501c0 nibble).
        // Counter 1 still resets for grunt 1503 one-heading leftovers.
        GameMap map = grass(16);
        World world = new World(map);
        world.fog().revealAll(0);
        Unit unit = world.createUnit(ogre(), 0, 4, 4);
        assertTrue(unit != null, "unit places");
        unit.setBattleNetCollisionCounter(1);
        unit.setPath(new PathFinder.Path(PathFinder.Result.FOUND, new int[] {E}));
        assertEquals(0, unit.battleNetCollisionCounter(),
                "counter 1 clears on one-heading setPath (grunt 1503 fresh soft-wait)");
        unit.setBattleNetCollisionCounter(2);
        unit.setPath(new PathFinder.Path(PathFinder.Result.FOUND, new int[] {E}));
        assertEquals(2, unit.battleNetCollisionCounter(),
                "multi-refuse counter survives one-heading free-compass setPath");
        unit.setBattleNetCollisionCounter(5);
        unit.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {E, NE, N}));
        assertEquals(5, unit.battleNetCollisionCounter(),
                "multi-heading setPath does not clear collision");
    }

    @Test
    @DisplayName("wall-follow soft-clear refuses a nearly settled ranged multi leftover")
    void wallFollowSoftClearRefusesANearlySettledRangedMultiLeftover() {
        // Free-scan 1516 face two: native nibble-refuses soft-clear on axe 76
        // at 31,39 (pathn3 residual of land-in-range SW). Large residual debt
        // still soft-clears so earlier free-scans do not REG @40; debt <= 8
        // keeps the cell solid so the second wall face fails and first E wins.
        GameMap map = grass(24);
        World world = new World(map);
        world.fog().revealAll(0);
        Unit axe = world.createUnit(axethrower(), 0, 8, 8);
        Unit quarry = world.createUnit(ogre(), 1, 8, 12);
        assertTrue(axe != null && quarry != null, "units place");
        axe.setOrder(Unit.Order.ATTACK);
        axe.setTarget(quarry);
        axe.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {E, E, E, E}));
        axe.setBattleNetCollisionCounter(0);
        axe.setBattleNetRefusals(1);
        assertTrue(axe.pathLength() >= 3, "multi leftover");
        assertTrue(world.targets.inAttackRange(axe, quarry),
                "axe stands in weapon range of the quarry");

        axe.setOffset(16, 0);
        assertTrue(world.movement
                        .battleNetSoftClearLiveRouteRefusalAlly(axe),
                "early residual (large debt) still soft-clears");

        axe.setOffset(7, -7);
        assertFalse(world.movement
                        .battleNetSoftClearLiveRouteRefusalAlly(axe),
                "nearly settled residual keeps occupancy for wall-follow");
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
        type.setNumDirections(8);
        AnimationSet set = new AnimationSet("axethrower");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    @Test
    @DisplayName("a pure move residual free-compasses past a non-cooperative ally after soft-wait")
    void aPureMoveResidualFreeCompassesPastANonCooperativeAllyAfterSoftWait() {
        GameMap map = grass(24);
        World world = new World(map);
        world.fog().revealAll(0);
        world.restoreRandom(1, 0);

        Unit mover = world.createUnit(ogre(), 0, 8, 10);
        Unit blocker = world.createUnit(ogre(), 0, 9, 10);
        assertTrue(mover != null && blocker != null, "units place");

        // Pure MOVE with multi-step leftover E,NE... after residual settle.
        mover.setOrder(Unit.Order.MOVE);
        mover.setPathGoal(16, 9);
        mover.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {E, NE, E, E, E, E, E}));
        mover.setTile(8, 10);
        mover.setStepDrained(true);
        // Prior cooperative soft-wait left coll >= 1.
        mover.setBattleNetCollisionCounter(1);
        // Blocker is allied but not cooperative (coll != 0).
        blocker.setOrder(Unit.Order.MOVE);
        blocker.setBattleNetCollisionCounter(1);

        int pathnBefore = mover.pathLength();
        // First refuse after soft-wait: install free NE without PF_WAIT.
        world.movement.stepMoveOrder(mover);
        assertEquals(pathnBefore, mover.pathLength(),
                "route kept (PF_WAIT would have popped E)");
        assertEquals(NE, mover.peekHeading(),
                "free-compass installs NE toward pathGoal (y-1 from goal)");
        assertEquals(0, mover.waitCycles(),
                "must not arm PF_WAIT 10 after soft-wait on pure MOVE residual");

        // Next visit takes the free NE cell.
        world.movement.stepMoveOrder(mover);
        assertEquals(9, mover.tileX(), "NE from 8,10 lands x 9");
        assertEquals(9, mover.tileY(), "NE from 8,10 lands y 9");
    }

    @Test
    @DisplayName("an exhausted point route beside its occupied goal refuses without PF_WAIT")
    void anExhaustedPointRouteBesideItsOccupiedGoalRefusesWithoutPfWait() {
        // XHuman 6 ogre 1495: its initial route drains beside the moving peon
        // on its exact order point. Native increments unit+0x1d from 1 to 8
        // on consecutive fixtures 87..94; the eighth refusal owns the
        // fifteen-count. The generic empty-route pause delayed Java ten
        // cycles before it even attempted this terminal step.
        GameMap map = grass(24);
        World world = new World(map);
        world.fog().revealAll(0);
        Unit mover = world.createUnit(ogre(), 0, 10, 10);
        Unit blocker = world.createUnit(ogre(), 0, 9, 11);
        assertTrue(mover != null && blocker != null, "units place");

        mover.setOrder(Unit.Order.MOVE);
        mover.setPathGoal(9, 11);
        mover.setOrderTarget(9, 11);
        mover.clearPath();
        mover.setRouteSpent(true);
        mover.setStepDrained(true);
        blocker.setOrder(Unit.Order.MOVE);
        blocker.setPath(new PathFinder.Path(
                PathFinder.Result.FOUND, new int[] {NW, NW}));
        blocker.setOffset(16, -16);

        assertTrue(world.movement.battleNetOccupiedPointRefusal(mover),
                "the spent adjacent point route must select the refusal boundary");
        world.movement.stepMoveOrder(mover);

        assertEquals(0, mover.waitCycles(),
                "first refusal must not inherit the empty-route PF_WAIT ten");
    }

    private static UnitType archer() {
        UnitType type = new UnitType("unit-archer");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(40);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(3);
        type.setPiercingDamage(6);
        type.setMaxAttackRange(4);
        type.setSightRange(5);
        type.setNumDirections(8);
        AnimationSet set = new AnimationSet("archer");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    private static void takePatrolTileSteps(World world, Unit unit, int n) {
        for (int step = 0; step < n; step++) {
            int x = unit.tileX();
            int y = unit.tileY();
            boolean moved = false;
            for (int i = 0; i < 40; i++) {
                world.movement.walkTowards(unit, unit.orderTargetX(),
                        unit.orderTargetY());
                if (unit.tileX() != x || unit.tileY() != y) {
                    moved = true;
                    break;
                }
            }
            assertTrue(moved, "expected patrol tile step " + (step + 1)
                    + " from " + x + "," + y);
        }
    }

    @Test
    @DisplayName("a type-two patrol keeps its fourth identical route heading")
    void aTypeTwoPatrolKeepsItsFourthIdenticalRouteHeading() {
        // Route shape does not dispatch BNE's AI executive. The recurring
        // fifty-cycle force pass owns route-index 20; an uninterrupted patrol
        // must keep every cached heading between those passes.
        GameMap map = grass(40);
        World world = new World(map);
        world.fog().revealAll(0);
        world.restoreRandom(1, 0);

        Unit archer = world.createUnit(archer(), 0, 10, 20);
        assertTrue(archer != null, "archer places");
        archer.setBattleNetAiBehavior(2);
        assertTrue(world.orderPatrol(archer, 5, 5),
                "archer accepts patrol toward the assault home");

        // Stack order: last element is next. Travel NE×5 then E×3.
        archer.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {E, E, E, NE, NE, NE, NE, NE}));
        archer.setPathGoal(-1, -1);

        takePatrolTileSteps(world, archer, 3);
        assertEquals(13, archer.tileX(), "three NE from 10,20 lands x 13");
        assertEquals(17, archer.tileY(), "three NE from 10,20 lands y 17");
        assertEquals(5, archer.pathLength(),
                "three identical steps do not synthesize an AI force pass");

        takePatrolTileSteps(world, archer, 1);
        assertEquals(14, archer.tileX(), "the fourth NE remains executable");
        assertEquals(16, archer.tileY(), "the fourth NE remains executable");
        assertEquals(4, archer.pathLength(),
                "only the committed fourth heading is consumed");
    }

    @Test
    @DisplayName("a heading change followed by three equal headings keeps its tail")
    void aHeadingChangeFollowedByThreeEqualHeadingsKeepsItsTail() {
        GameMap map = grass(40);
        World world = new World(map);
        world.fog().revealAll(0);
        world.restoreRandom(1, 0);

        Unit knight = world.createUnit(archer(), 0, 10, 20);
        assertTrue(knight != null, "unit places");
        knight.setBattleNetAiBehavior(2);
        assertTrue(world.orderPatrol(knight, 5, 5),
                "unit accepts patrol");

        // Travel NW, NE, NE, NE, E, E, E.
        knight.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {E, E, E, NE, NE, NE, NW}));
        knight.setPathGoal(-1, -1);

        takePatrolTileSteps(world, knight, 3);
        assertTrue(knight.pathLength() > 0,
                "after NW+NE+NE leftover remains (trailing run is only 2)");

        takePatrolTileSteps(world, knight, 1);
        assertEquals(3, knight.pathLength(),
                "the route tail survives NW+NE+NE+NE until the AI pass");
    }

    @Test
    @DisplayName("a player patrol keeps bresenham leftover after three ne steps")
    void aPlayerPatrolKeepsBresenhamLeftoverAfterThreeNeSteps() {
        // Player GiveOrder 5 dest-arm is NE,NE,NE,N,NE. Type-two assault
        // leftover exhaust must not discard the N,NE tail: native last
        // heading is NE onto dest, not a replan NE,N from the south.
        GameMap map = grass(40);
        World world = new World(map);
        world.fog().revealAll(0);
        world.restoreRandom(1, 0);

        Unit grunt = world.createUnit(archer(), 0, 18, 23);
        assertTrue(grunt != null, "grunt places");
        assertTrue(world.orderPatrol(grunt, 22, 18),
                "a player patrol click is accepted");

        grunt.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {NE, N, NE, NE, NE}));
        grunt.setPathGoal(-1, -1);

        takePatrolTileSteps(world, grunt, 3);
        assertEquals(21, grunt.tileX(), "three NE from 18,23 lands x 21");
        assertEquals(20, grunt.tileY(), "three NE from 18,23 lands y 20");
        assertEquals(2, grunt.pathLength(),
                "player dest-arm keeps leftover N,NE after three NE");
        assertEquals(N, grunt.peekHeading(),
                "leftover next heading is N onto 21,19, not a discarded replan");
    }

    @Test
    @DisplayName("a doubled patrol keeps a long straight route")
    void aDoubledPatrolKeepsALongStraightRoute() {
        // Human 12 zeppelin 1500: retail keeps E,E,E,E,NE in one route and
        // turns north-east on fixture 134. Ground patrols discard their
        // leftover after three equal headings; doubled movers do not.
        GameMap map = grass(40);
        World world = new World(map);
        world.fog().revealAll(0);
        world.restoreRandom(1, 0);

        Unit zeppelin = world.createUnit(archer(), 0, 4, 20);
        assertTrue(zeppelin != null, "zeppelin places");
        zeppelin.setBattleNetDoubleStep(true);
        assertTrue(world.orderPatrol(zeppelin, 30, 18),
                "zeppelin accepts patrol");

        // Stack order: travel E four times, then NE.
        zeppelin.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {NE, E, E, E, E}));
        zeppelin.setPathGoal(-1, -1);

        takePatrolTileSteps(world, zeppelin, 4);
        assertEquals(12, zeppelin.tileX(),
                "four doubled east steps land eight tiles east");
        assertEquals(20, zeppelin.tileY(),
                "four doubled east steps retain the row");
        assertEquals(1, zeppelin.pathLength(),
                "doubled patrol retains the diagonal after four equal headings");

        takePatrolTileSteps(world, zeppelin, 1);
        assertEquals(14, zeppelin.tileX(),
                "retained doubled north-east step advances x");
        assertEquals(18, zeppelin.tileY(),
                "retained doubled north-east step advances y");
    }
}
