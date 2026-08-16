package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.missile.Missile;
import net.chonkbase.chonkcraft.engine.missile.MissileClass;
import net.chonkbase.chonkcraft.engine.missile.MissileType;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.pathfinder.PathFinder;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An order that advances on a place and fights what it meets.
 *
 * <p>{@code CommandAttack(unit, GoalPos, nullptr)}, which
 * {@code AiForce::Attack} gives every aggressive unit in a force and which
 * reaches {@code COrder::NewActionAttack(attacker, dest)}: an attack order with a position
 * instead of a goal, starting in {@code AUTO_TARGETING}.
 *
 * <p>The implementation had no such order. A unit sent across the map walked, and
 * {@code HitUnit_AttackBack} did not answer for a walking unit either, so a
 * column could be shot down one at a time without a single soldier turning
 * round. The AI covered for it by re-aiming every marcher once a second.
 */
class AttackMoveTest {

    private static GameMap openField(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType soldier(String ident) {
        UnitType type = new UnitType(ident);
        type.setName(ident);
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(6);
        type.setMaxAttackRange(1);
        type.setSightRange(9);
        type.setReactRangePerson(6);
        type.setReactRangeComputer(6);
        type.setMissile("missile-none");
        AnimationSet set = new AnimationSet("soldier");
        set.put(AnimationSet.State.STILL, Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        set.put(AnimationSet.State.ATTACK, Animation.parse("attack",
                List.of("unbreakable begin", "frame 5", "wait 1",
                        "frame 10", "attack", "unbreakable end", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    /**
     * A tower: it shoots what walks past and never gives chase.
     *
     * <p>The fixture needs one, because two soldiers of the same speed never
     * trade a blow -- the walker stays exactly one square ahead all the way to
     * its destination, and the first thing it ever gets hit by is the
     * auto-attack it runs once it has arrived and gone still. Which is the bug
     * stated as a fixture.
     */
    private static UnitType tower(String ident) {
        UnitType type = soldier(ident);
        type.setSpeed(0);
        type.setMaxAttackRange(4);
        return type;
    }

    private static World twoSideField() {
        World world = new World(openField(48));
        world.setAllied(0, 1, false);
        return world;
    }

    @Test
    @DisplayName("a destroyer chase keeps its target route instead of an old command point")
    void destroyerChaseKeepsTargetRouteOverOldCommandPoint() {
        World world = twoSideField();
        UnitType destroyerType = soldier("unit-orc-destroyer");
        destroyerType.setTileSize(2, 2);
        Unit destroyer = world.createUnit(destroyerType, 0, 10, 10);
        Unit quarry = world.createUnit(soldier("unit-human-destroyer"), 1, 20, 10);
        assertTrue(destroyer.battleNetDoubleStep(),
                "the regression needs BNE's doubled ship route grid");

        // The current chase route is east. The point is deliberately stale:
        // it represents a prior player command, just as it can during a live
        // fight after the target order has replaced a march. A patrol-only
        // residual correction used that westward point to replace the whole
        // attack route with one west step, so replanning could alternate the
        // ship forever instead of closing on its quarry.
        destroyer.setOrder(Unit.Order.ATTACK);
        destroyer.setTarget(quarry);
        destroyer.setChasing(true);
        destroyer.setOrderTarget(0, 10);
        destroyer.setPathGoal(-1, -1);
        destroyer.setPath(new PathFinder.Path(
                PathFinder.Result.FOUND, new int[] {2, 2}));

        world.combat.stepMoveTowardsTarget(destroyer);

        assertEquals(12, destroyer.tileX(),
                "the destroyer reversed toward an obsolete command point");
        assertEquals(10, destroyer.tileY());
    }

    @Test
    @DisplayName("a standing unit that goes to fight comes back to its post, still hunting")
    void aStandingUnitReturnsToAnAttackMoveAtThePostItLeft() {
        World world = twoSideField();
        // Mobile, deliberately. COrder_Still::Execute sends a unit that
        // cannot move down AutoAttackStand instead, which saves no order at
        // all -- so a tower would be testing the other branch.
        UnitType guardType = soldier("unit-guard");
        guardType.setMaxAttackRange(4);
        Unit guard = world.createUnit(guardType, 0, 20, 20);
        // Frail and unarmed, so the fight is short and the guard survives it:
        // what is under test is what the guard does afterwards.
        UnitType preyType = soldier("unit-grunt");
        preyType.setHitPoints(12);
        preyType.setCanAttack(false);
        preyType.setSpeed(0);
        // Far enough that the guard has to leave its post to reach it.
        // EndActionAttack only keeps the order alive "if (IsAutoTargeting() &&
        // this->goalPos != this->attackMovePos)": a
        // unit that never moved has nowhere to come back to and does finish.
        Unit prey = world.createUnit(preyType, 1, 25, 20);

        // What a standing unit saves before it goes to fight is not its still
        // order. AutoAttack builds COrder::NewActionAttack(unit, unit.tilePos)
        // -- "Weak goal, can choose other unit, come back after attack",
        // and the position form of that
        // constructor sets attackMovePos and State = AUTO_TARGETING
        // So what it restores when the fight ends
        // is an attack-move to the square it was standing on, and it goes on
        // looking from there. Upstream's juggernaught on demo02 is still under
        // an attack order at cycle 28, back where it began, where this implementation's
        // had dropped to still at 14.
        Unit.Order fighting = null;
        for (int cycle = 0; cycle < 200 && prey.isAlive(); cycle++) {
            world.tick();
            if (guard.target() == prey) {
                fighting = guard.order();
            }
        }
        assertFalse(prey.isAlive(), "the fixture's prey never died, so nothing was restored");
        assertNotNull(fighting, "the guard never engaged, so there was no fight to end");

        assertNotEquals(20, guard.tileX(), "the guard never left its post, so nothing"
                + " was drifted from and upstream would finish the order too");

        world.tick();
        assertEquals(Unit.Order.ATTACK_MOVE, guard.order(),
                "the guard went quiet after its target died instead of coming back to an"
                        + " attack-move at its post");
        assertEquals(20, guard.attackMoveX(), "its post is the square it was standing on");
        assertEquals(20, guard.attackMoveY(), "its post is the square it was standing on");
    }

    @Test
    @DisplayName("an automatic attack keeps its live destination separate from its saved post")
    void anAutomaticAttackStoresTwoAttackMoveDestinations() {
        World world = twoSideField();
        UnitType guardType = soldier("unit-guard");
        guardType.setMaxAttackRange(4);
        Unit guard = world.createUnit(guardType, 0, 5, 10);
        UnitType preyType = soldier("unit-grunt");
        preyType.setCanAttack(false);
        preyType.setSpeed(0);
        Unit prey = world.createUnit(preyType, 1, 9, 10);

        for (int cycle = 0; cycle < 100 && guard.target() != prey; cycle++) {
            world.tick();
        }

        assertEquals(prey, guard.target(), "the standing scan never acquired its prey");
        assertEquals(9, guard.attackMoveX(),
                "CommandAttack's live attackMovePos was overwritten by the saved post");
        assertEquals(10, guard.attackMoveY());
        assertEquals(Unit.Order.ATTACK_MOVE, guard.savedOrder());
        assertEquals(5, guard.savedAttackMoveX(),
                "the cloned saved attack order did not retain the standing post");
        assertEquals(10, guard.savedAttackMoveY());
    }

    @Test
    @DisplayName("a fresh automatic attack replaces an obsolete saved order")
    void aStandingAutoAttackReplacesAnOlderSavedMove() {
        World world = twoSideField();
        UnitType guardType = soldier("unit-guard");
        guardType.setMaxAttackRange(4);
        Unit guard = world.createUnit(guardType, 0, 5, 10);
        UnitType preyType = soldier("unit-prey");
        preyType.setCanAttack(false);
        preyType.setSpeed(0);
        world.createUnit(preyType, 1, 8, 10);

        // AiMoveUnitInTheWay may have left a SavedOrder behind a short shove.
        // CommandAttack is a new command and clears that old save before
        // AutoAttack installs its freshly cloned return-to-post attack.
        guard.setSavedOrder(Unit.Order.MOVE);
        world.tick();
        world.tick();

        assertEquals(Unit.Order.ATTACK_MOVE, guard.savedOrder(),
                "AutoAttack kept the stale saved move instead of its new return order");
        assertEquals(5, guard.savedAttackMoveX());
        assertEquals(10, guard.savedAttackMoveY());
    }

    @Test
    @DisplayName("a fresh attack-move command clears an interrupted order")
    void aFreshAttackMoveClearsAnOlderSavedOrder() {
        World world = twoSideField();
        Unit marcher = world.createUnit(soldier("unit-grunt"), 0, 5, 10);

        marcher.setSavedOrder(Unit.Order.ATTACK_MOVE);
        marcher.setSavedAttackMove(5, 10);

        assertTrue(world.orderAttackMove(marcher, 30, 10));
        assertNull(marcher.savedOrder(),
                "CommandAttack with Flush On kept the saved action it must clear");
    }

    @Test
    @DisplayName("a fresh position attack invalidates output from a widened range")
    void aFreshAttackMoveComparesItsZeroRangeWithCachedOutput() {
        World world = twoSideField();
        Unit marcher = world.createUnit(soldier("unit-archer"), 0, 5, 10);

        assertTrue(world.orderAttackMove(marcher, 9, 10));
        marcher.setMoveRange(1);
        marcher.setRouteSpent(true);

        assertTrue(world.orderAttackMove(marcher, 9, 10));

        assertEquals(0, marcher.moveRange(), "the fresh order did not restore Range zero");
        assertFalse(marcher.routeSpent(),
                "range-one PathFinderOutput survived a fresh range-zero order");
        assertEquals(0, marcher.pathLength());
    }

    @Test
    @DisplayName("an automatic attack captures the quarry's tile when the scan offers it")
    void anAutomaticAttackCapturesTheScanTimeTile() {
        World world = twoSideField();
        world.fog().revealAll(0);
        UnitType guardType = soldier("unit-guard");
        guardType.setMaxAttackRange(4);
        Unit guard = world.createUnit(guardType, 0, 5, 10);
        Unit prey = world.createUnit(soldier("unit-grunt"), 1, 9, 10);

        world.tick();
        assertSame(prey, guard.pendingAttack(), "the standing scan never queued its prey");

        // CommandAttack was already issued by the scan. Moving the unit before
        // HandleUnitAction pops that queued command must not move its weak
        // goal along with the Unit object.
        prey.setTile(10, 10);
        world.tick();

        assertEquals(9, guard.attackMoveX(),
                "the queued weak goal followed its quarry after CommandAttack");
        assertEquals(10, guard.attackMoveY());
    }

    @Test
    @DisplayName("a live quarry outside reaction range restores the standing post")
    void aLiveQuarryThatEscapesReactionRangeRestoresTheSavedPost() {
        World world = twoSideField();
        UnitType guardType = soldier("unit-axethrower");
        guardType.setMaxAttackRange(4);
        guardType.setReactRangeComputer(6);
        Unit guard = world.createUnit(guardType, 0, 10, 10);
        Unit quarry = world.createUnit(soldier("unit-balloon"), 1, 20, 10);
        Unit blocker = world.createUnit(soldier("unit-friendly-blocker"), 0, 11, 10);
        blocker.setWalkHolding(true);

        assertTrue(world.orderAttackMove(guard, quarry.tileX(), quarry.tileY()));
        guard.setSavedOrder(Unit.Order.ATTACK_MOVE);
        guard.setSavedAttackMove(5, 10);
        guard.setTarget(quarry);
        guard.setChasing(true);
        guard.setPathGoal(quarry.tileX(), quarry.tileY());
        guard.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {Direction.fromDelta(1, 0)}));
        guard.setAttackScanSleep(0);
        guard.setMoveRange(1);

        world.tick();

        assertNull(guard.target(),
                "AutoSelectTarget kept a live goal after it had left reaction range");
        assertEquals(5, guard.attackMoveX(),
                "EndActionAttack did not restore the standing unit's saved post");
        assertEquals(10, guard.attackMoveY());
        assertNull(guard.savedOrder(), "the restored order remained banked behind itself");
        assertFalse(guard.chasing(), "the restored order inherited MOVE_TO_TARGET");
        assertEquals(0, guard.moveRange(),
                "the restored order inherited the live attack's widened range");
    }

    @Test
    @DisplayName("a unit marching on a place engages what it walks into")
    void aMarcherFightsOnTheWay() {
        World world = twoSideField();
        UnitType marcherType = soldier("unit-footman");
        UnitType picketType = soldier("unit-grunt");
        picketType.setSpeed(0);
        picketType.setCanAttack(false);
        Unit marcher = world.createUnit(marcherType, 0, 4, 20);
        Unit picket = world.createUnit(picketType, 1, 18, 20);
        assertNotNull(marcher);
        assertNotNull(picket);

        CommandApplier commands = new CommandApplier(
                world, List.of(marcherType, picketType));
        commands.apply(GameCommand.attackMove(0, marcher.id(), 44, 20));
        assertEquals(Unit.Order.ATTACK_MOVE, marcher.order());

        int picketHealth = picket.hitPoints();
        boolean acquiredBeforeDestination = false;
        for (int i = 0; i < 900 && picket.isAlive(); i++) {
            world.tick();
            acquiredBeforeDestination |= marcher.target() == picket
                    && marcher.tileX() < 44;
        }
        assertTrue(acquiredBeforeDestination,
                "the real command reached the destination before it acquired the picket");
        assertTrue(picket.hitPoints() < picketHealth,
                "a unit advancing on a place has to fight what stands in the way; it walked"
                        + " straight past instead");
    }

    @Test
    @DisplayName("an empty-route march plans before spending its target-scan sleep")
    void aRouteDryMarchLetsTheMoveOwnTheCycle() {
        World world = twoSideField();
        Unit marcher = world.createUnit(soldier("unit-grunt"), 0, 5, 5);
        assertTrue(world.orderAttackMove(marcher, 30, 5));
        // Past FIRST_ENTRY, with no cached PathFinderOutput: this is still
        // MOVE_TO_TARGET. DoActionMove must plan and commit before
        // CheckForTargetInRange can call AutoSelectTarget.
        marcher.setAttackMoveOpening(false);
        marcher.setAttackScanSleep(3);
        marcher.clearPath();

        world.tick();

        assertTrue(marcher.isMoving() || marcher.pathLength() > 0,
                "the empty-route march did not plan its next segment");
        assertEquals(3, marcher.attackScanSleep(),
                "AutoSelectTarget ran before the fresh DoActionMove call; a committed"
                        + " first step owns the cycle and leaves the order's Sleep untouched");
    }

    /**
     * The destination survives the fight. Upstream keeps {@code attackMovePos}
     * for the whole engagement, so a force that stops to kill a picket carries
     * on afterwards rather than standing where the picket fell.
     */
    @Test
    @DisplayName("the march resumes once what it met is dead")
    void theDestinationOutlivesTheFight() {
        World world = twoSideField();
        UnitType marcherType = soldier("unit-footman");
        UnitType picketType = soldier("unit-grunt");
        picketType.setSpeed(0);
        picketType.setCanAttack(false);
        Unit marcher = world.createUnit(marcherType, 0, 4, 20);
        Unit picket = world.createUnit(picketType, 1, 10, 20);
        picket.setHitPoints(6);

        CommandApplier commands = new CommandApplier(
                world, List.of(marcherType, picketType));
        commands.apply(GameCommand.attackMove(0, marcher.id(), 40, 20));
        assertEquals(Unit.Order.ATTACK_MOVE, marcher.order(),
                "the player command never entered the simulation as an attack-move");
        for (int i = 0; i < 3000 && marcher.order() != Unit.Order.STILL; i++) {
            world.tick();
        }
        assertFalse(picket.isAlive(), "the fixture wants the picket dead");
        assertTrue(marcher.tileX() > 30, String.format(
                "the marcher stopped at %d, where the fight was, instead of carrying on to"
                        + " the square it was aimed at", marcher.tileX()));
    }

    /** A plain move is one of HitUnit_AttackBack's explicit no-op actions. */
    @Test
    @DisplayName("a soldier shot while walking keeps its plain move")
    void aMarchingUnitDoesNotFightBack() {
        World world = twoSideField();
        Unit walker = world.createUnit(soldier("unit-footman"), 0, 4, 20);
        Unit sniper = world.createUnit(tower("unit-orc-tower"), 1, 10, 22);

        assertTrue(world.orderMove(walker, 44, 20));
        assertEquals(Unit.Order.MOVE, walker.order());

        assertTrue(world.orderAttack(sniper, walker));
        int health = walker.hitPoints();
        for (int i = 0; i < 300 && walker.hitPoints() == health; i++) {
            world.tick();
        }
        assertTrue(walker.hitPoints() < health, "the fixture wants the walker struck");
        assertEquals(Unit.Order.MOVE, walker.order(),
                "upstream's reaction switch deliberately leaves Move alone");
        assertNull(walker.target(), "the blow is not allowed to turn a plain move into combat");
        assertEquals(44, walker.orderTargetX(), "with its destination intact");
    }

    /** A unit already advancing keeps the order and only changes its aim. */
    @Test
    @DisplayName("a blow re-aims an attack-move without cancelling it")
    void anAttackMoveIsNotCancelledByABlow() {
        World world = twoSideField();
        Unit marcher = world.createUnit(soldier("unit-footman"), 0, 4, 20);
        Unit sniper = world.createUnit(tower("unit-orc-tower"), 1, 10, 22);

        assertTrue(world.orderAttackMove(marcher, 44, 20));
        assertTrue(world.orderAttack(sniper, marcher));
        int health = marcher.hitPoints();
        for (int i = 0; i < 300 && marcher.hitPoints() == health; i++) {
            world.tick();
        }
        assertTrue(marcher.hitPoints() < health, "the fixture wants the marcher struck");
        assertEquals(Unit.Order.ATTACK_MOVE, marcher.order(),
                "the march is the order; only the target changes");
        assertSame(sniper, marcher.target());
        assertEquals(44, marcher.orderTargetX());
    }

    /** Aimed at a wall it can see, this is a bombardment rather than a march. */
    @Test
    @DisplayName("attack-move at a visible wall becomes attack-ground")
    void aWallIsBombardedRatherThanWalkedAt() {
        GameMap map = openField(32);
        map.field(20, 20).setFlags(TileFlag.LAND_ALLOWED | TileFlag.WALL | TileFlag.HUMAN
                | TileFlag.UNPASSABLE);
        map.field(20, 20).setValue(GameMap.WALL_HIT_POINTS);
        World world = new World(map);
        Unit footman = world.createUnit(soldier("unit-footman"), 0, 18, 20);

        assertTrue(world.orderAttackMove(footman, 20, 20));
        assertEquals(Unit.Order.ATTACK_GROUND, footman.order(),
                "there is no unit on a wall square to auto-target, and the wall is the"
                        + " point: NewActionAttack's WallOnMap branch");
    }

    /** Nothing that cannot fight takes the order at all. */
    @Test
    @DisplayName("a worker cannot be given an attack-move")
    void onlyFightersMarch() {
        World world = twoSideField();
        UnitType peasant = soldier("unit-peasant");
        peasant.setCanAttack(false);
        Unit worker = world.createUnit(peasant, 0, 4, 20);
        assertFalse(world.orderAttackMove(worker, 40, 20));
    }

    @Test
    @DisplayName("the cycle a march ends is still a marching cycle when it is read")
    void aFinishedMarchIsStillTheCurrentAction() {
        World world = new World(openField(20));
        world.fog().revealAll(0);
        Unit walker = world.createUnit(soldier("unit-footman"), 0, 5, 5);
        assertTrue(world.orderAttackMove(walker, 5, 8), "the attack-move was refused");

        // Upstream's orders finish by setting a flag rather than by being
        // replaced: this->Finished = true leaves Orders[0] where it is, and
        // CurrentAction() goes on answering with it until HandleUnitAction
        // pops it on the next cycle. So the cycle a march arrives is still a
        // marching cycle when the world is read.
        //
        // On maps/demo/demo02 that is the whole of cycle 54: upstream's ogre
        // juggernaught is home at 4,18 from cycle 28 and stays under its
        // attack order until 55, which is how long its move animation runs,
        // and this implementation read it as standing still from 54.
        Unit.Order reportedWhenItEnded = null;
        for (int cycle = 0; cycle < 200; cycle++) {
            world.tick();
            if (walker.order() == Unit.Order.STILL) {
                reportedWhenItEnded = walker.currentAction();
                break;
            }
        }

        assertEquals(8, walker.tileY(), "the walker never got where it was sent");
        assertEquals(Unit.Order.ATTACK_MOVE, reportedWhenItEnded,
                "the cycle the march ended already read as standing still");
    }

    @Test
    @DisplayName("a residual ship bob is cleared when its chase route reports reached")
    void aResidualShipBobDoesNotKeepTheBroadsideMarchingForever() {
        World world = twoSideField();
        world.fog().revealAll(0);
        UnitType gunboatType = soldier("unit-orc-destroyer");
        gunboatType.setMaxAttackRange(8);
        Unit gunboat = world.createUnit(gunboatType, 0, 5, 10);
        Unit target = world.createUnit(soldier("unit-battleship"), 1, 12, 10);

        assertTrue(world.orderAttackMove(gunboat, 30, 10), "the attack-move was refused");
        gunboat.setTarget(target);
        gunboat.setChasing(true);
        gunboat.setPathGoal(target.tileX(), target.tileY());
        // A ship's Still wiggle can leave one pixel on the stationary axis.
        // DoActionMove clears IX/IY when the post-pause path query answers
        // PF_REACHED. Keeping the pixel makes isMoving() true forever, so the
        // attack order repeats its arrival turn and never starts the shot.
        gunboat.setOffset(0, 1);

        int health = target.hitPoints();
        for (int cycle = 0; cycle < 12 && target.hitPoints() == health; cycle++) {
            world.tick();
        }

        assertEquals(0, gunboat.offsetY(),
                "PF_REACHED left the ship's one-pixel bob looking like a live step");
        assertTrue(target.hitPoints() < health,
                "the destroyer kept repeating its arrival instead of firing");
    }

    @Test
    @DisplayName("a position march already at its destination clears the ship bob")
    void anAlreadyReachedPositionMarchClearsIdleDisplacement() {
        World world = twoSideField();
        Unit gunboat = world.createUnit(soldier("unit-human-destroyer"), 0, 5, 10);
        gunboat.setResidual(1, 1);

        assertTrue(world.orderAttackMove(gunboat, 5, 10), "the attack-move was refused");
        world.tick();

        assertEquals(0, gunboat.offsetX(), "PF_REACHED kept the drawn ship bob");
        assertEquals(0, gunboat.offsetY(), "PF_REACHED kept the drawn ship bob");
        assertEquals(0, gunboat.residualX(), "PF_REACHED kept the banked ship bob");
        assertEquals(0, gunboat.residualY(), "PF_REACHED kept the banked ship bob");
        assertEquals(Unit.Order.ATTACK_MOVE, gunboat.currentAction(),
                "the reached command did not retain its final-cycle label");
    }

    @Test
    @DisplayName("a spent march at the move boundary ignores a one-pixel ship bob")
    void aSpentPositionMarchDoesNotStartAnotherMovePassForIdleDisplacement() {
        World world = twoSideField();
        Unit gunboat = world.createUnit(soldier("unit-orc-destroyer"), 0, 5, 10);
        assertTrue(world.orderAttackMove(gunboat, 5, 10));
        gunboat.setAttackMoveOpening(false);
        gunboat.setRouteSpent(true);
        gunboat.setOffset(0, 1);

        world.tick();

        assertEquals(Unit.Order.STILL, gunboat.order(),
                "the residual pixel was mistaken for Moving and began another full"
                        + " move-animation pass instead of accepting PF_REACHED");
        assertEquals(Unit.Order.ATTACK_MOVE, gunboat.currentAction(),
                "the finished attack order must remain the reported action this cycle");
    }

    @Test
    @DisplayName("restoring an attack-move clears a wait raised by its dying target's blocked step")
    void aRestoredMarchDoesNotServeTheDeadChaseBlockedStepWait() {
        World world = twoSideField();
        Unit gunboat = world.createUnit(soldier("unit-orc-destroyer"), 0, 5, 10);
        Unit target = world.createUnit(soldier("unit-battleship"), 1, 12, 10);
        world.createUnit(soldier("unit-friendly-blocker"), 0, 6, 10);

        assertTrue(world.orderAttackMove(gunboat, 30, 10), "the attack-move was refused");
        gunboat.setSavedOrder(Unit.Order.ATTACK_MOVE);
        gunboat.setTarget(target);
        gunboat.setChasing(true);
        gunboat.setPathGoal(target.tileX(), target.tileY());
        gunboat.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {Direction.fromDelta(1, 0)}));
        target.setHitPoints(0);

        world.tick();

        assertEquals(0, gunboat.waitCycles(),
                "the restored march inherited the blocked chase's ten-cycle wait");
        assertFalse(gunboat.chasing(),
                "the restored AUTO_TARGETING order inherited MOVE_TO_TARGET state");
        assertNull(gunboat.savedOrder(),
                "the restored attack-move remained banked behind itself");
    }

    @Test
    @DisplayName("resuming the same attack-move keeps a wait raised by its dying target")
    void theSameMarchServesTheDeadChaseBlockedStepWait() {
        World world = twoSideField();
        Unit gunboat = world.createUnit(soldier("unit-orc-destroyer"), 0, 5, 10);
        Unit target = world.createUnit(soldier("unit-battleship"), 1, 12, 10);
        world.createUnit(soldier("unit-friendly-blocker"), 0, 6, 10);

        assertTrue(world.orderAttackMove(gunboat, 30, 10), "the attack-move was refused");
        gunboat.setTarget(target);
        gunboat.setChasing(true);
        gunboat.setPathGoal(target.tileX(), target.tileY());
        gunboat.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {Direction.fromDelta(1, 0)}));
        target.setHitPoints(0);

        world.tick();

        assertEquals(10, gunboat.waitCycles(),
                "EndActionAttack resumed the same weak order but erased DoActionMove's wait");
        assertFalse(gunboat.chasing(),
                "the resumed AUTO_TARGETING order inherited MOVE_TO_TARGET state");
        assertEquals(30, gunboat.attackMoveX());
        assertEquals(5, gunboat.tileX(),
                "the resumed march moved before serving the blocked chase's wait");
    }

    @Test
    @DisplayName("a widened march wakes into its move before scanning again")
    void aWidenedMarchWakeCommitsBeforeItsNextTargetScan() {
        World world = twoSideField();
        Unit marcher = world.createUnit(soldier("unit-axethrower"), 0, 5, 10);
        assertTrue(world.orderAttackMove(marcher, 12, 10));

        // The state after a goal-less MoveToTarget returned UNREACHABLE:
        // Range was widened, Wait=5 has now expired, and the order is still
        // MOVE_TO_TARGET. Its next Execute calls DoActionMove first. A route
        // that commits an unbreakable step returns before AutoSelectTarget,
        // so Sleep must not be decremented on this wake.
        marcher.setAttackMoveOpening(false);
        marcher.setMoveRange(1);
        marcher.setAttackScanSleep(6);

        world.tick();

        assertEquals(6, marcher.attackScanSleep(),
                "the widened MOVE_TO_TARGET state scanned before committing its route");
    }

    @Test
    @DisplayName("restoring an attack-move keeps the unit's spent pathfinder output")
    void aRestoredMarchStillServesTheSpentRoutePause() {
        World world = twoSideField();
        Unit grunt = world.createUnit(soldier("unit-grunt"), 0, 5, 10);
        Unit target = world.createUnit(soldier("unit-footman"), 1, 6, 10);

        assertTrue(world.orderAttackMove(grunt, 30, 10));
        grunt.setSavedOrder(Unit.Order.ATTACK_MOVE);
        grunt.setSavedAttackMove(2, 10);
        grunt.setTarget(target);
        // PathFinderOutput is CUnit state, not COrder state. RestoreOrder
        // swaps the saved attack order in but leaves this empty cached output
        // for its first MoveToTarget call, which must answer PF_WAIT.
        grunt.setRouteSpent(true);
        target.setHitPoints(0);
        target.setOrder(Unit.Order.DYING);

        world.tick();

        assertNull(grunt.savedOrder(), "the saved attack-move was not restored");
        assertEquals(2, grunt.attackMoveX());
        assertEquals(10, grunt.waitCycles(),
                "RestoreOrder erased the unit-level PF_WAIT owed by the spent route");
        assertEquals(5, grunt.tileX(),
                "the restored order planned a fresh route before serving PF_WAIT");
    }

    @Test
    @DisplayName("a restored exact-range march invalidates spent output from a widened order")
    void aRestoredMarchComparesItsSavedRangeBeforeServingSpentOutput() {
        World world = twoSideField();
        Unit grunt = world.createUnit(soldier("unit-axethrower"), 0, 5, 10);
        Unit target = world.createUnit(soldier("unit-footman"), 1, 6, 10);

        assertTrue(world.orderAttackMove(grunt, 30, 10));
        grunt.setSavedOrder(Unit.Order.ATTACK_MOVE);
        grunt.setSavedAttackMove(2, 10);
        grunt.setSavedMoveRange(0);
        grunt.setMoveRange(1);
        grunt.setTarget(target);
        grunt.setRouteSpent(true);
        target.setHitPoints(0);
        target.setOrder(Unit.Order.DYING);

        world.tick();

        assertEquals(Unit.Order.ATTACK_MOVE, grunt.order());
        assertNull(grunt.savedOrder());
        assertEquals(0, grunt.waitCycles(),
                "range-one output was treated as current for the restored exact order");
        assertEquals(4, grunt.tileX(),
                "the exact-range clone did not calculate and begin its own route");
    }

    @Test
    @DisplayName("restoring an attack-move preserves a nonempty unit pathfinder output")
    void aRestoredMarchKeepsTheRemainingRoute() {
        World world = twoSideField();
        Unit grunt = world.createUnit(soldier("unit-grunt"), 0, 5, 10);
        Unit target = world.createUnit(soldier("unit-footman"), 1, 6, 10);
        grunt.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {Direction.fromDelta(1, 0), Direction.fromDelta(1, 1)}));
        assertTrue(world.orderAttack(grunt, target));
        assertEquals(2, grunt.pathLength(),
                "constructing the temporary target order erased CUnit PathFinderOutput");
        grunt.setSavedOrder(Unit.Order.ATTACK_MOVE);
        grunt.setSavedAttackMove(30, 10);
        target.setHitPoints(0);
        target.setOrder(Unit.Order.DYING);

        world.tick();

        assertEquals(Unit.Order.ATTACK_MOVE, grunt.order());
        assertEquals(2, grunt.pathLength(),
                "RestoreOrder erased PathFinderOutput before the restored order used it");
    }

    @Test
    @DisplayName("a restored march remembers which position its retained route served")
    void aRestoredMarchInvalidatesAnOldPositionRouteWhenItEventuallyMoves() {
        World world = twoSideField();
        Unit knight = world.createUnit(soldier("unit-knight"), 0, 5, 10);
        Unit quarry = world.createUnit(soldier("unit-ogre"), 1, 6, 10);
        quarry.setHitPoints(1);

        assertTrue(world.orderAttackMove(knight, 2, 10));
        knight.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {Direction.fromDelta(-1, 0)}));
        knight.setSavedOrder(Unit.Order.ATTACK_MOVE);
        knight.setSavedAttackMove(30, 10);
        knight.setTarget(quarry);
        knight.setAttackGoal(quarry.tileX(), quarry.tileY());
        knight.setAttackMoveOpening(false);
        knight.setFighting(true);

        for (int cycle = 0; cycle < 20 && knight.savedOrder() != null; cycle++) {
            world.tick();
        }

        assertNull(knight.savedOrder(), "the saved march was never restored");
        assertEquals(30, knight.attackMoveX());
        assertEquals(2, knight.pathGoalX(),
                "the retained route forgot the position input it was calculated for");
        assertEquals(10, knight.pathGoalY());

        world.tick();

        assertEquals(6, knight.tileX(),
                "the restored eastbound march consumed its old westbound heading");
        assertEquals(10, knight.tileY());
    }

    @Test
    @DisplayName("a restored attack-move scans before consulting its cached route")
    void aRestoredMarchScansBeforeItsUnitLevelPathfinderOutput() {
        World world = twoSideField();
        Unit grunt = world.createUnit(soldier("unit-grunt"), 0, 5, 10);
        Unit live = world.createUnit(soldier("unit-live-footman"), 1, 5, 11);

        // A restored COrder_Attack is in AUTO_TARGETING while the CUnit may
        // still own output from the order it replaced. A fresh order gives us
        // the same opening state; layer the surviving unit output under it.
        assertTrue(world.orderAttackMove(grunt, 2, 10));
        grunt.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {Direction.fromDelta(-1, 0), Direction.fromDelta(-1, 1)}));

        world.tick();

        assertSame(live, grunt.target(),
                "the cached route suppressed AUTO_TARGETING's opening scan");
        assertTrue(grunt.fighting(), "the in-range target did not enter ATTACK_TARGET");
        assertEquals(2, grunt.pathLength(),
                "the opening scan erased the unit-level pathfinder output");
        assertEquals(5, grunt.tileX(), "the cached route moved before the opening scan");
        assertEquals(10, grunt.tileY());
    }

    @Test
    @DisplayName("an out-of-range opening target recalculates a spent route")
    void anOpeningScanRecalculatesSpentOutputBeforeChasingItsTarget() {
        World world = twoSideField();
        Unit grunt = world.createUnit(soldier("unit-grunt"), 0, 5, 10);
        Unit live = world.createUnit(soldier("unit-footman"), 1, 10, 10);

        assertTrue(world.orderAttackMove(grunt, 2, 10));
        grunt.setRouteSpent(true);

        world.tick();

        assertSame(live, grunt.target(), "the opening scan did not acquire its target");
        assertTrue(grunt.chasing(), "the out-of-range target did not enter MOVE_TO_TARGET");
        assertEquals(0, grunt.waitCycles(),
                "the obsolete output answered PF_WAIT instead of being recalculated");
        assertTrue(grunt.isMoving() || grunt.pathLength() > 0,
                "the chase did not replace output whose goal range changed");
        assertEquals(6, grunt.tileX(),
                "FIRST_ENTRY did not fall through into the first chase step");
    }

    @Test
    @DisplayName("an out-of-range opening target reuses output already aimed at it")
    void anOpeningScanServesSpentOutputAlreadyAimedAtItsTarget() {
        World world = twoSideField();
        Unit grunt = world.createUnit(soldier("unit-grunt"), 0, 5, 10);
        Unit live = world.createUnit(soldier("unit-footman"), 1, 10, 10);

        assertTrue(world.orderAttackMove(grunt, 2, 10));
        // PathFinderInput and PathFinderOutput live on CUnit. This represents
        // a restored order finding the same goal its now-empty output was
        // calculated for, as levelx12h's grunt does at cycle 346.
        grunt.setPathGoal(live.tileX(), live.tileY());
        grunt.setRouteSpent(true);

        world.tick();

        assertSame(live, grunt.target(), "the opening scan did not reacquire its target");
        assertTrue(grunt.chasing(), "the out-of-range target did not enter MOVE_TO_TARGET");
        assertEquals(10, grunt.waitCycles(),
                "matching unit-level output was recalculated instead of answering PF_WAIT");
        assertEquals(5, grunt.tileX(),
                "the restored order moved before serving its cached output");
    }

    @Test
    @DisplayName("restoring at a move-animation drain end clears MOVE_TO_TARGET state")
    void aRestoredMarchAfterTheLastStepDoesNotKeepChasing() {
        World world = twoSideField();
        UnitType gunboatType = soldier("unit-orc-destroyer");
        Animation move = Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 1", "wait 1",
                "unbreakable end", "wait 1"));
        gunboatType.animationSet().put(AnimationSet.State.MOVE, move);
        Unit gunboat = world.createUnit(gunboatType, 0, 5, 10);
        Unit target = world.createUnit(soldier("unit-battleship"), 1, 12, 10);

        assertTrue(world.orderAttackMove(gunboat, 30, 10), "the attack-move was refused");
        gunboat.setSavedOrder(Unit.Order.ATTACK_MOVE);
        gunboat.setTarget(target);
        gunboat.setChasing(true);
        gunboat.setPathGoal(target.tileX(), target.tileY());
        gunboat.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {Direction.fromDelta(1, 0), Direction.fromDelta(1, 0)}));

        world.tick();
        assertTrue(gunboat.animation().unbreakable(),
                "the fixture did not leave its last step inside the move animation");
        target.setHitPoints(0);

        world.tick();

        assertFalse(gunboat.chasing(),
                "the restored AUTO_TARGETING order inherited MOVE_TO_TARGET at drain-end");
    }

    @Test
    @DisplayName("a blocked chase retargeted in range enters ATTACK_TARGET before its wait")
    void anInRangeRetargetDuringARefusalIsReadyToFireOnWake() {
        World world = twoSideField();
        UnitType gunboatType = soldier("unit-human-destroyer");
        gunboatType.setMaxAttackRange(4);
        Unit gunboat = world.createUnit(gunboatType, 0, 5, 10);
        Unit oldTarget = world.createUnit(soldier("unit-orc-destroyer"), 1, 20, 10);
        Unit newTarget = world.createUnit(soldier("unit-ogre-juggernaught"), 1, 8, 10);
        world.createUnit(soldier("unit-friendly-blocker"), 0, 6, 10);

        assertTrue(world.orderAttackMove(gunboat, 30, 10), "the attack-move was refused");
        gunboat.setTarget(oldTarget);
        gunboat.setChasing(true);
        gunboat.setPathGoal(oldTarget.tileX(), oldTarget.tileY());
        gunboat.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {Direction.fromDelta(1, 0)}));
        gunboat.setAttackScanSleep(0);

        world.tick();

        assertSame(newTarget, gunboat.target(), "the refusal scan did not find the near target");
        assertFalse(gunboat.chasing(),
                "the in-range retarget stayed in MOVE_TO_TARGET through its wait");
        assertTrue(gunboat.fighting(),
                "the in-range retarget did not enter ATTACK_TARGET before sleeping");
    }

    @Test
    @DisplayName("a projectile starts at the ship's residual wiggle displacement")
    void aBroadsideStartsAtTheWiggledShipNotOnlyItsTile() {
        World world = twoSideField();
        MissileType shell = new MissileType("missile-small-cannon", null,
                MissileClass.POINT_TO_POINT, 32, 32, 1, 1, 22, 1,
                0, 1, 0, null, null, false, 0, 0, false);
        world.setMissileTypes(Map.of(shell.ident(), shell));
        UnitType gunboatType = soldier("unit-orc-destroyer");
        gunboatType.setTileSize(2, 2);
        gunboatType.setMaxAttackRange(8);
        gunboatType.setMissile(shell.ident());
        UnitType targetType = soldier("unit-human-destroyer");
        targetType.setTileSize(2, 2);
        Unit gunboat = world.createUnit(gunboatType, 0, 5, 10);
        Unit target = world.createUnit(targetType, 1, 12, 10);
        gunboat.setResidual(32, 1);

        assertTrue(world.orderAttack(gunboat, target), "the attack was refused");
        for (int cycle = 0; cycle < 12 && world.missiles().isEmpty(); cycle++) {
            world.tick();
        }

        assertFalse(world.missiles().isEmpty(), "the destroyer never fired");
        Missile shot = world.missiles().get(0);
        assertEquals(5 * Unit.TILE_PIXELS + 32 + 32, shot.fromX(),
                "the muzzle ignored IX carried in the residual displacement");
        assertEquals(10 * Unit.TILE_PIXELS + 32 + 1, shot.fromY(),
                "the muzzle ignored IY carried in the residual displacement");
    }

    @Test
    @DisplayName("the post-route-wait chase arrival clears idle displacement before firing")
    void aChaseArrivalAfterItsEmptyRouteWaitClearsTheShipBob() {
        World world = twoSideField();
        UnitType gunboatType = soldier("unit-orc-destroyer");
        gunboatType.setTileSize(2, 2);
        gunboatType.setMaxAttackRange(8);
        UnitType targetType = soldier("unit-human-destroyer");
        targetType.setTileSize(2, 2);
        Unit gunboat = world.createUnit(gunboatType, 0, 5, 10);
        Unit target = world.createUnit(targetType, 1, 12, 10);

        assertTrue(world.orderAttackMove(gunboat, 30, 10), "the attack-move was refused");
        gunboat.setTarget(target);
        gunboat.setChasing(true);
        // This is the wake after the empty cached route has already raised
        // and served its ten-cycle wait. The Still animation bobbed while it
        // slept, so the terminal PF_REACHED query must clear that IX/IY
        // before MoveToTarget changes to ATTACK_TARGET.
        gunboat.setRouteSpent(false);
        gunboat.setResidual(32, 1);

        world.tick();

        assertEquals(0, gunboat.residualX(),
                "the arrival turn kept the horizontal Still-animation wiggle");
        assertEquals(0, gunboat.residualY(),
                "the arrival turn kept the vertical Still-animation wiggle");
        assertFalse(gunboat.chasing(), "the fixture did not reach ATTACK_TARGET");
        assertTrue(gunboat.fighting(), "the fixture did not reach ATTACK_TARGET");
    }

    @Test
    @DisplayName("resuming the same attack-move preserves sleep but resets path range")
    void anAttackMovesOwnTargetDropKeepsItsScanCounter() {
        World world = twoSideField();
        Unit marcher = world.createUnit(soldier("unit-orc-destroyer"), 0, 5, 10);
        Unit target = world.createUnit(soldier("unit-human-destroyer"), 1, 8, 10);

        assertTrue(world.orderAttackMove(marcher, 30, 10), "the attack-move was refused");
        marcher.setTarget(target);
        marcher.setAutoTargeting(true);
        marcher.setAttackScanSleep(4);
        marcher.setMoveRange(1);
        target.setHitPoints(0);

        world.tick();

        assertEquals(3, marcher.attackScanSleep(),
                "resuming the same COrder_Attack replaced its live Sleep counter");
        assertEquals(0, marcher.moveRange(),
                "EndActionAttack retained the range widened before the fight");
        assertEquals(Unit.Order.ATTACK_MOVE, marcher.order());
        assertTrue(marcher.tileX() > 5,
                "the sleeping scan should leave the marcher free to resume its destination");
    }

    @Test
    @DisplayName("resuming at exact range invalidates a widened march route")
    void anAttackMovesRangeResetInvalidatesItsCachedRoute() {
        World world = twoSideField();
        Unit marcher = world.createUnit(soldier("unit-axethrower"), 0, 5, 10);
        Unit target = world.createUnit(soldier("unit-footman"), 1, 6, 10);
        UnitType blockerType = tower("unit-friendly-blocker");
        world.createUnit(blockerType, 0, 10, 10);

        assertTrue(world.orderAttackMove(marcher, 10, 10));
        marcher.setTarget(target);
        marcher.setAttackGoal(target.tileX(), target.tileY());
        marcher.setAutoTargeting(true);
        marcher.setMoveRange(1);
        marcher.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {Direction.fromDelta(0, 1)}));
        marcher.setPathGoal(-1, -1);
        target.setHitPoints(0);

        world.tick();

        assertEquals(5, marcher.tileX(),
                "the resumed exact-range order consumed a range-one heading");
        assertEquals(10, marcher.tileY());
        assertEquals(5, marcher.waitCycles(),
                "the exact destination should be refused before range widens again");
        assertEquals(1, marcher.moveRange());
    }

    @Test
    @DisplayName("a target dropped on a walk's drain beat resets the resumed march range")
    void aMidStepTargetDropAlsoRestoresThePositionOrdersExactRange() {
        World world = twoSideField();
        UnitType marcherType = soldier("unit-axethrower");
        marcherType.animationSet().put(AnimationSet.State.MOVE,
                Animation.parse("committed-move", List.of(
                        "unbreakable begin", "frame 0", "move 2", "wait 4",
                        "move 30", "unbreakable end", "wait 1")));
        Unit marcher = world.createUnit(marcherType, 0, 5, 10);
        Unit target = world.createUnit(soldier("unit-footman"), 1, 12, 10);

        assertTrue(world.orderAttackMove(marcher, 30, 10));
        marcher.setTarget(target);
        marcher.setAttackGoal(target.tileX(), target.tileY());
        marcher.setChasing(true);
        marcher.setAutoTargeting(true);
        marcher.setMoveRange(4);
        marcher.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {Direction.fromDelta(1, 0)}));
        marcher.setPathGoal(target.tileX(), target.tileY());

        world.tick();
        assertTrue(marcher.walkHolding(), "the fixture did not commit its chase step");
        target.setHitPoints(0);
        for (int cycle = 0; cycle < 60 && marcher.target() != null; cycle++) {
            world.tick();
        }

        assertNull(marcher.target(), "the dead quarry survived the step's drain beat");
        assertEquals(0, marcher.moveRange(),
                "EndActionAttack retained the chase's weapon range at the drain boundary");
        assertEquals(30, marcher.attackGoalX());
        assertEquals(10, marcher.attackGoalY());
    }

    @Test
    @DisplayName("a restored shove clone keeps its entered scan state")
    void aShoveCloneDoesNotRestoreAsAFreshAttackMove() {
        World world = twoSideField();
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.enableAi(0);
        for (int cycle = 0; cycle < 10; cycle++) {
            world.tick();
        }

        Unit marcher = world.createUnit(soldier("unit-axethrower"), 0, 5, 10);
        Unit blocker = world.createUnit(soldier("unit-busy-blocker"), 0, 6, 10);
        world.orderStandGround(blocker);
        world.createUnit(tower("unit-friendly-goal"), 0, 10, 10);

        assertTrue(world.orderAttackMove(marcher, 10, 10));
        world.tick();
        assertEquals(Unit.Order.ATTACK_MOVE, marcher.savedOrder(),
                "the exact-range refusal did not save the order while shoving its blocker");
        int savedSleep = marcher.attackScanSleep();
        assertTrue(savedSleep > 0, "the opening scan did not advance before the clone");

        Unit target = world.createUnit(soldier("unit-footman"), 1, 5, 11);
        marcher.setTarget(target);
        marcher.setAttackGoal(target.tileX(), target.tileY());
        marcher.setFighting(true);
        target.setHitPoints(0);
        for (int cycle = 0; cycle < 20 && marcher.savedOrder() != null; cycle++) {
            world.tick();
        }

        assertNull(marcher.savedOrder(), "the dying goal never restored the shove clone");
        assertFalse(marcher.attackMoveOpening(),
                "the already-entered clone was restored as FIRST_ENTRY");
        assertEquals(savedSleep, marcher.attackScanSleep(),
                "the restored clone lost its own scan counter");
    }

    @Test
    @DisplayName("an attack-move ends when its target was its destination")
    void aDroppedGoalAtTheAttackMoveTileFinishesInsteadOfMarchingThere() {
        World world = twoSideField();
        Unit marcher = world.createUnit(soldier("unit-gryphon-rider"), 0, 5, 10);
        Unit target = world.createUnit(soldier("unit-orc-destroyer"), 1, 8, 10);

        assertTrue(world.orderAttackMove(marcher, target.tileX(), target.tileY()));
        marcher.setTarget(target);
        marcher.setAutoTargeting(true);
        target.setHitPoints(0);

        world.tick();

        assertEquals(Unit.Order.STILL, marcher.order(),
                "EndActionAttack resumed the destination after goalPos equalled attackMovePos");
        assertEquals(Unit.Order.ATTACK_MOVE, marcher.currentAction(),
                "the finished attack order was popped on the cycle it finished");
        assertEquals(5, marcher.tileX(),
                "the finished order spent a step toward its dead destination");
    }

    @Test
    @DisplayName("a spent attack route still ends when its dead target was its destination")
    void aDroppedGoalAtTheAttackMoveTileFinishesAfterPathWait() {
        World world = twoSideField();
        Unit marcher = world.createUnit(soldier("unit-knight"), 0, 5, 10);
        Unit target = world.createUnit(soldier("unit-ogre"), 1, 8, 10);

        assertTrue(world.orderAttackMove(marcher, target.tileX(), target.tileY()));
        marcher.setTarget(target);
        marcher.setAttackGoal(target.tileX(), target.tileY());
        marcher.setAutoTargeting(true);
        marcher.setChasing(true);
        marcher.setRouteSpent(true);
        target.setHitPoints(0);

        world.tick();

        assertEquals(Unit.Order.STILL, marcher.order(),
                "PF_WAIT bypassed EndActionAttack's own-destination finish");
        assertEquals(Unit.Order.ATTACK_MOVE, marcher.currentAction(),
                "the finishing path refusal lost the attack order's tail label");
    }

    @Test
    @DisplayName("a released weak goal still ends the attack-move on the swing tail")
    void aReleasedGoalAtTheAttackMoveTileStillFinishes() {
        World world = twoSideField();
        Unit marcher = world.createUnit(soldier("unit-axethrower"), 0, 5, 10);
        Unit target = world.createUnit(soldier("unit-footman"), 1, 6, 10);

        assertTrue(world.orderAttackMove(marcher, target.tileX(), target.tileY()));
        marcher.setTarget(target);
        marcher.setAttackGoal(target.tileX(), target.tileY());
        marcher.setAutoTargeting(true);
        marcher.setFighting(true);

        world.tick();
        assertTrue(marcher.animation().unbreakable(),
                "the fixture did not commit its swing before the weak goal vanished");

        // CUnitPtr becomes null when the target is released, but the attack
        // order keeps goalPos. CheckIfGoalValid therefore still sees an
        // ATTACK_TARGET with no goal and EndActionAttack compares that
        // remembered position with attackMovePos on the swing's closing beat.
        marcher.setTarget(null);
        while (marcher.animation().unbreakable()) {
            world.tick();
        }

        assertEquals(Unit.Order.STILL, marcher.order(),
                "a null weak pointer was mistaken for a goal-less march and walked toward"
                        + " the target's old square instead of finishing at its destination");
        assertEquals(Unit.Order.ATTACK_MOVE, marcher.currentAction(),
                "the finished attack order should remain the reported action for its tail");
        assertEquals(5, marcher.tileX(),
                "the released goal cost an extra step toward its old square");
    }

    @Test
    @DisplayName("under attack, an auto-targeting order defers its saved-order restore")
    void underAttackKeepsTheSavedOrderAndLiveScanCounter() {
        World world = twoSideField();
        Unit marcher = world.createUnit(soldier("unit-ogre-juggernaught"), 0, 5, 10);
        Unit target = world.createUnit(soldier("unit-battleship"), 1, 8, 10);

        assertTrue(world.orderAttackMove(marcher, 30, 10), "the attack-move was refused");
        marcher.setTarget(target);
        marcher.setAutoTargeting(true);
        marcher.setSavedOrder(Unit.Order.ATTACK_MOVE);
        marcher.setAttackScanSleep(4);
        marcher.setUnderAttack(10);
        marcher.setRouteSpent(true);
        target.setHitPoints(0);

        world.tick();

        assertEquals(3, marcher.attackScanSleep(),
                "the retained route's PF_WAIT did not make MoveToTarget's scan call");
        assertEquals(Unit.Order.ATTACK_MOVE, marcher.savedOrder(),
                "EndActionAttack restored the saved order while UnderAttack was active");
        assertEquals(10, marcher.waitCycles(),
                "deferring RestoreOrder discarded the live order's spent route");
    }

    @Test
    @DisplayName("a chasing unit re-plans before accepting an edge-range arrival")
    void aChaseWakeReplansBeforeTheDirectRangeCheck() {
        World world = twoSideField();
        UnitType gryphonType = soldier("unit-gryphon-rider");
        gryphonType.setTileSize(2, 2);
        gryphonType.setMaxAttackRange(4);
        Unit gryphon = world.createUnit(gryphonType, 0, 12, 34);
        UnitType quarryType = soldier("unit-orc-destroyer");
        quarryType.setTileSize(2, 2);
        quarryType.setCanAttack(false);
        quarryType.setSpeed(0);
        Unit quarry = world.createUnit(quarryType, 1, 8, 38);

        assertTrue(world.orderAttackMove(gryphon, 4, 40));
        gryphon.setTarget(quarry);
        gryphon.setAutoTargeting(true);
        gryphon.setChasing(true);
        // The target moved during the empty-route wait. Upstream's wake calls
        // DoActionMove first, notices this stale path goal and asks A* again
        // before InAttackRange gets a say.
        gryphon.setPathGoal(9, 38);

        world.tick();

        assertEquals(11, gryphon.tileX(),
                "the stale chase was accepted by the direct range check without re-planning");
        assertEquals(35, gryphon.tileY());
        assertTrue(gryphon.chasing(), "the wake turned into ATTACK_TARGET before its step");
    }

    @Test
    @DisplayName("a chase wake asks for a fresh route even when its target stayed put")
    void anEmptyChaseRouteReplansAfterItsWait() {
        World world = twoSideField();
        UnitType destroyerType = soldier("unit-orc-destroyer");
        destroyerType.setTileSize(2, 2);
        destroyerType.setMaxAttackRange(4);
        Unit destroyer = world.createUnit(destroyerType, 0, 11, 45);
        UnitType quarryType = soldier("unit-battleship");
        quarryType.setTileSize(2, 2);
        quarryType.setCanAttack(false);
        quarryType.setSpeed(0);
        Unit quarry = world.createUnit(quarryType, 1, 18, 40);

        assertTrue(world.orderAttackMove(destroyer, 19, 39));
        destroyer.setTarget(quarry);
        destroyer.setAutoTargeting(true);
        destroyer.setChasing(true);
        destroyer.setPathGoal(18, 40);

        world.tick();

        assertNotEquals("11,45", destroyer.tileX() + "," + destroyer.tileY(),
                "the exhausted chase route was treated as an arrival instead of re-planned");
    }

    @Test
    @DisplayName("PF_REACHED takes ATTACK_TARGET before validating a dying goal")
    void aReachedDyingGoalStillBuysTheCommittedSwing() {
        World world = twoSideField();
        UnitType gryphonType = soldier("unit-gryphon-rider");
        gryphonType.setTileSize(2, 2);
        gryphonType.setMaxAttackRange(4);
        Unit gryphon = world.createUnit(gryphonType, 0, 10, 34);
        UnitType dragonType = soldier("unit-dragon");
        dragonType.setTileSize(2, 2);
        dragonType.animationSet().put(AnimationSet.State.DEATH,
                Animation.parse("death", List.of(
                        "unbreakable begin", "frame 0", "wait 20",
                        "unbreakable end", "wait 1")));
        Unit dragon = world.createUnit(dragonType, 1, 5, 31);

        assertTrue(world.orderAttackMove(gryphon, 11, 35));
        gryphon.setTarget(dragon);
        gryphon.setChasing(true);
        gryphon.setPathGoal(dragon.tileX(), dragon.tileY());
        dragon.setHitPoints(0);
        dragon.setOrder(Unit.Order.DYING);

        world.tick();

        assertSame(dragon, gryphon.target(),
                "MoveToTarget validated and dropped the goal before its PF_REACHED arm");
        assertFalse(gryphon.chasing(),
                "the reached dying goal remained in MOVE_TO_TARGET");
        assertTrue(gryphon.fighting(),
                "PF_REACHED did not enter ATTACK_TARGET for the committed corpse swing");
    }

    @Test
    @DisplayName("a chase search temporarily removes the mover's field flag")
    void aChaserCanReachItsTargetWhileSharingGroundWithADyingBody() {
        World world = twoSideField();

        UnitType bodyType = soldier("unit-body");
        bodyType.animationSet().put(AnimationSet.State.DEATH,
                Animation.parse("death", List.of(
                        "unbreakable begin", "frame 0", "wait 20",
                        "unbreakable end", "wait 1")));
        Unit body = world.createUnit(bodyType, 0, 9, 12);
        world.kill(body);

        Unit attacker = world.createUnit(soldier("unit-attacker"), 0, 9, 12);
        UnitType targetType = tower("unit-target");
        targetType.setTileSize(2, 2);
        targetType.setCanAttack(false);
        Unit target = world.createUnit(targetType, 1, 10, 10);

        assertTrue(world.orderAttackMove(attacker, 30, 30));
        attacker.setTarget(target);
        attacker.setChasing(true);
        attacker.setPathGoal(target.tileX(), target.tileY());
        attacker.setAttackScanSleep(2);
        world.tick();

        assertSame(target, attacker.target(),
                "the route search treated the dying body under the mover as an obstacle");
        assertTrue(attacker.fighting(),
                "PF_REACHED did not turn the adjacent chaser into ATTACK_TARGET");
        assertEquals(2, attacker.attackScanSleep(),
                "PF_REACHED ran AttackTarget's scan before returning");

        world.tick();

        assertEquals(2, attacker.attackScanSleep(),
                "the first ATTACK_TARGET cycle scanned before starting its animation");
    }

    @Test
    @DisplayName("a fresh attack-move does not inherit an old fight state")
    void aFreshAttackMoveClearsStaleCombatState() {
        World world = twoSideField();
        UnitType destroyerType = soldier("unit-human-destroyer");
        destroyerType.setTileSize(2, 2);
        destroyerType.setMaxAttackRange(4);
        Unit destroyer = world.createUnit(destroyerType, 0, 9, 26);
        UnitType dragonType = soldier("unit-dragon");
        dragonType.setTileSize(2, 2);
        dragonType.setCanAttack(false);
        dragonType.setSpeed(0);
        Unit dragon = world.createUnit(dragonType, 1, 5, 31);

        // A shove can replace an attack while this unit-level surrogate still
        // says ATTACK_TARGET. A new upstream COrder_Attack has only
        // AUTO_TARGETING set; no state from that old order can survive.
        destroyer.setFighting(true);
        assertTrue(world.orderAttackMove(destroyer, dragon.tileX(), dragon.tileY()));

        world.tick();

        assertEquals(dragon, destroyer.target(),
                "the stale fight state suppressed the fresh order's first target scan");
        assertFalse(destroyer.fighting(),
                "the new attack-move still reports the old order's ATTACK_TARGET state");
    }

    @Test
    @DisplayName("an attack-move reconsiders its target on the swing's closing beat"
            + " even with a cached route")
    void aSwingEndScansAndTakesTheChaseWithoutSpendingAnotherCycle() {
        World world = twoSideField();
        UnitType destroyerType = soldier("unit-orc-destroyer");
        destroyerType.setPriority(60);
        destroyerType.animationSet().put(AnimationSet.State.ATTACK,
                Animation.parse("attack", List.of(
                        "unbreakable begin", "frame 0", "attack", "wait 3",
                        "frame 0", "unbreakable end", "wait 1")));
        Unit destroyer = world.createUnit(destroyerType, 0, 10, 10);
        UnitType oldType = soldier("unit-human-shipyard");
        oldType.setCanAttack(false);
        oldType.setSpeed(0);
        oldType.setHitPoints(4000);
        oldType.setPriority(1);
        Unit oldTarget = world.createUnit(oldType, 1, 11, 10);

        assertTrue(world.orderAttackMove(destroyer, 30, 10));
        destroyer.setTarget(oldTarget);
        destroyer.setFighting(true);
        destroyer.setAttackScanSleep(0);
        world.tick();
        assertTrue(destroyer.animation().unbreakable(),
                "the fixture did not start its committed broadside");
        // PathFinderOutput belongs to the unit and may be left behind when
        // MOVE_TO_TARGET becomes ATTACK_TARGET. The attack state ignores it;
        // in particular it cannot make the closing beat look walk-owned and
        // suppress AutoSelectTarget.
        destroyer.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {Direction.fromDelta(1, 0)}));

        UnitType betterType = soldier("unit-human-destroyer");
        betterType.setCanAttack(true);
        betterType.setSpeed(0);
        betterType.setHitPoints(4000);
        betterType.setPriority(70);
        Unit better = world.createUnit(betterType, 1, 15, 10);

        for (int cycle = 0; cycle < 12 && destroyer.animation().unbreakable(); cycle++) {
            world.tick();
        }

        assertFalse(destroyer.animation().unbreakable(),
                "the fixture never reached the broadside's breakable tail");
        assertSame(better, destroyer.target(),
                "AttackTarget returned after unbreakable-end instead of scanning");
        assertTrue(destroyer.chasing(),
                "the out-of-range replacement did not become MOVE_TO_TARGET");
        assertEquals(10, destroyer.tileX(),
                "the swing-end decision also spent the following chase step");
    }

    @Test
    @DisplayName("the swing after a breakable tail starts before another scan")
    void theNextSwingDoesNotScanAgainAfterTheClosingBeat() {
        World world = twoSideField();
        UnitType destroyerType = soldier("unit-orc-destroyer");
        destroyerType.animationSet().put(AnimationSet.State.ATTACK,
                Animation.parse("attack", List.of(
                        "unbreakable begin", "frame 0", "attack", "wait 3",
                        "frame 0", "unbreakable end", "wait 1")));
        Unit destroyer = world.createUnit(destroyerType, 0, 10, 10);
        UnitType quarryType = soldier("unit-human-shipyard");
        quarryType.setCanAttack(false);
        quarryType.setSpeed(0);
        quarryType.setHitPoints(4000);
        Unit quarry = world.createUnit(quarryType, 1, 11, 10);

        assertTrue(world.orderAttackMove(destroyer, 30, 10));
        destroyer.setTarget(quarry);
        destroyer.setFighting(true);
        destroyer.setAttackScanSleep(4);
        world.tick();
        while (destroyer.animation().unbreakable()) {
            world.tick();
        }

        assertEquals(3, destroyer.attackScanSleep(),
                "the closing beat did not make its one target-selection call");
        world.tick();

        assertEquals(3, destroyer.attackScanSleep(),
                "the next swing scanned again before AnimateActionAttack");
        assertTrue(destroyer.animation().unbreakable(),
                "the next Execute did not begin the next committed swing");
    }

    @Test
    @DisplayName("a quarry killed between swings still buys the next committed swing")
    void aQuarryKilledBetweenSwingsStillBuysTheNextCommittedSwing() {
        World world = twoSideField();
        UnitType footmanType = soldier("unit-footman");
        Unit footman = world.createUnit(footmanType, 0, 10, 10);
        Unit quarry = world.createUnit(soldier("unit-grunt"), 1, 11, 10);

        assertTrue(world.orderAttackMove(footman, 30, 10));
        footman.setTarget(quarry);
        footman.setFighting(true);
        // AttackTarget's prior closing beat has looped the attack animation
        // back to its breakable first instruction. A later unit then kills
        // the quarry in the same global cycle. On the next Execute upstream
        // animates first, entering Unbreakable, and only afterwards would
        // CheckIfGoalValid be allowed to release the corpse.
        footman.animation().switchTo(
                footmanType.animationSet().get(AnimationSet.State.ATTACK));
        quarry.setHitPoints(0);
        quarry.setOrder(Unit.Order.DYING);

        world.tick();

        assertSame(quarry, footman.target(),
                "AttackTarget checked the newly dead goal before animating");
        assertTrue(footman.animation().unbreakable(),
                "the cycle did not begin the swing committed at the corpse");
        assertTrue(footman.fighting(),
                "the committed corpse swing left ATTACK_TARGET");
    }

    @Test
    @DisplayName("a quarry stepping away between swings still buys the next committed swing")
    void aQuarryMovingOutOfRangeBetweenSwingsStillBuysTheNextCommittedSwing() {
        World world = twoSideField();
        UnitType gruntType = soldier("unit-grunt");
        Unit grunt = world.createUnit(gruntType, 0, 10, 10);
        Unit footman = world.createUnit(soldier("unit-footman"), 1, 11, 10);

        assertTrue(world.orderAttackMove(grunt, 30, 10));
        grunt.setTarget(footman);
        grunt.setFighting(true);
        // The preceding AttackTarget execute has finished the breakable tail
        // and looped CurrAnim to instruction zero. A later-numbered quarry
        // then enters its next tile before this fighter executes again.
        // AttackTarget calls AnimateActionAttack first, so instruction zero
        // begins another unbreakable swing before its range check is allowed.
        grunt.animation().switchTo(
                gruntType.animationSet().get(AnimationSet.State.ATTACK));
        footman.setTile(12, 10);

        world.tick();

        assertSame(footman, grunt.target());
        assertTrue(grunt.animation().unbreakable(),
                "AttackTarget checked range before beginning the next swing");
        assertTrue(grunt.fighting(),
                "the already-committed swing conceded immediately to MOVE_TO_TARGET");
        assertFalse(grunt.chasing());
        assertEquals(10, grunt.tileX(),
                "the fighter walked after the quarry on the committed-swing beat");
    }

    @Test
    @DisplayName("a target acquired on a step's drain beat owns the following cycle")
    void aDrainBeatRetargetStartsTheOutOfRangeChaseImmediately() {
        World world = twoSideField();
        UnitType marcherType = soldier("unit-orc-destroyer");
        marcherType.animationSet().put(AnimationSet.State.MOVE,
                Animation.parse("move", List.of(
                        "unbreakable begin", "frame 0", "move 1", "wait 1",
                        "unbreakable end", "wait 1")));
        Unit marcher = world.createUnit(marcherType, 0, 5, 10);
        UnitType quarryType = soldier("unit-refinery");
        quarryType.setCanAttack(false);
        quarryType.setSpeed(0);
        Unit quarry = world.createUnit(quarryType, 1, 11, 10);

        assertTrue(world.orderAttackMove(marcher, 30, 10), "the attack-move was refused");
        // The first check expires without a target and starts the step. The
        // next breakable drain beat sees the refinery after the marcher has
        // moved one square, just as levelx11o's destroyer does at cycle 309.
        marcher.setAttackScanSleep(1);
        for (int cycle = 0; cycle < 8 && marcher.target() == null; cycle++) {
            world.tick();
        }

        assertSame(quarry, marcher.target(), "the drain-beat scan did not acquire its quarry");
        assertTrue(marcher.chasing(),
                "MoveToTarget did not claim the out-of-range target after the scan");
        int sleep = marcher.attackScanSleep();
        world.tick();
        assertEquals(sleep, marcher.attackScanSleep(),
                "the committed chase step allowed a second scan on the following cycle");
    }

    @Test
    @DisplayName("an in-range target acquired on a drain beat parks the remaining march route")
    void aDrainBeatInRangeAcquisitionPreservesTheUnitRouteTail() {
        World world = twoSideField();
        UnitType marcherType = soldier("unit-grunt");
        marcherType.animationSet().put(AnimationSet.State.MOVE,
                Animation.parse("move", List.of(
                        "unbreakable begin", "frame 0", "move 1", "wait 1",
                        "unbreakable end", "wait 1")));
        Unit marcher = world.createUnit(marcherType, 0, 5, 10);
        Unit quarry = world.createUnit(soldier("unit-footman"), 1, 6, 11);

        assertTrue(world.orderAttackMove(marcher, 30, 10));
        // FIRST_ENTRY merely expires this counter and begins the east step.
        // The breakable drain beat then finds the adjacent quarry while one
        // more heading remains in the CUnit-owned output.
        marcher.setAttackScanSleep(1);
        marcher.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {Direction.fromDelta(1, 0), Direction.fromDelta(1, 0)}));
        for (int cycle = 0; cycle < 8 && marcher.target() == null; cycle++) {
            world.tick();
        }

        assertSame(quarry, marcher.target(), "the drain beat did not acquire its quarry");
        assertTrue(marcher.fighting(), "the adjacent quarry did not enter ATTACK_TARGET");
        assertEquals(1, marcher.pathLength(),
                "the in-range SetGoal erased the still-unspent march heading");
    }

    @Test
    @DisplayName("a fresh out-of-range acquisition scans again after its blocked first step")
    void aFreshAcquisitionSpendsTheMoveArmsSecondScanOnARefusal() {
        World world = twoSideField();
        for (int y = 0; y < world.map().height(); y++) {
            if (y == 10) {
                continue;
            }
            for (int x = 0; x < world.map().width(); x++) {
                world.map().field(x, y).setFlags(TileFlag.UNPASSABLE);
            }
        }
        Unit marcher = world.createUnit(soldier("unit-grunt"), 0, 10, 10);
        Unit quarry = world.createUnit(soldier("unit-footman"), 1, 5, 10);
        Unit blocker = world.createUnit(soldier("unit-friendly-blocker"), 0, 9, 10);
        blocker.setWalkHolding(true);

        assertTrue(world.orderAttackMove(marcher, 30, 10));
        world.tick();

        assertSame(quarry, marcher.target(), "the opening scan did not acquire the quarry");
        assertEquals(10, marcher.waitCycles(),
                "the fixture's forced first step was not refused");
        assertEquals(5, marcher.attackScanSleep(),
                "FIRST_ENTRY scanned once, but MoveToTarget did not make its second call");
    }

    @Test
    @DisplayName("a sleeping first scan can acquire in range after the move arm refuses")
    void aGoalLessRefusalSpendsTheMoveArmsSecondScan() {
        World world = twoSideField();
        for (int y = 0; y < world.map().height(); y++) {
            if (y == 10) {
                continue;
            }
            for (int x = 0; x < world.map().width(); x++) {
                world.map().field(x, y).setFlags(TileFlag.UNPASSABLE);
            }
        }
        UnitType marcherType = soldier("unit-knight");
        marcherType.setMaxAttackRange(4);
        Unit marcher = world.createUnit(marcherType, 0, 5, 10);
        Unit quarry = world.createUnit(soldier("unit-ogre"), 1, 8, 10);
        Unit blocker = world.createUnit(soldier("unit-moving-blocker"), 0, 6, 10);
        blocker.setWalkHolding(true);

        assertTrue(world.orderAttackMove(marcher, 30, 10));
        // FIRST_ENTRY's check consumes this one without looking. The
        // subsequent PF_WAIT still reaches MoveToTarget's second
        // CheckForTargetInRange in the same Execute.
        marcher.setAttackScanSleep(1);
        world.tick();

        assertEquals(10, marcher.waitCycles(), "the moving blocker did not refuse the step");
        assertSame(quarry, marcher.target(),
                "the move arm did not make its second scan after PF_WAIT");
        assertFalse(marcher.chasing(),
                "an in-range quarry stayed in MOVE_TO_TARGET through the wait");
        assertTrue(marcher.fighting(),
                "the in-range quarry did not enter ATTACK_TARGET before the wait");
    }

    @Test
    @DisplayName("an AI fighter ignores a second attacker while its goal is fighting it")
    void anAiAttackOrderHandlesAHitFromItsExistingFight() {
        World world = twoSideField();
        world.fog().revealAll(0);
        world.enableAi(0);
        UnitType defenderType = soldier("unit-grunt");
        defenderType.setHitPoints(600);
        UnitType goalType = soldier("unit-footman");
        goalType.setHitPoints(600);
        UnitType secondType = soldier("unit-footman-2");
        secondType.setHitPoints(600);
        Unit defender = world.createUnit(defenderType, 0, 5, 10);
        Unit goal = world.createUnit(goalType, 1, 6, 10);
        Unit second = world.createUnit(secondType, 1, 5, 11);

        assertTrue(world.orderAttack(defender, goal));
        defender.setAutoTargeting(true);
        assertTrue(world.orderAttack(goal, defender));
        world.hit(second, defender);

        assertTrue(defender.hitPoints() < 600, "neither attacker reached its damage frame");
        assertEquals(0, defender.underAttack(),
                "COrder_Attack::OnAiHitUnit did not consume the hit from the second attacker");
        assertNull(defender.offeredTarget(),
                "the second attacker was offered despite the existing mutual fight");
    }

    @Test
    @DisplayName("an AI fighter hit after its goal dies retains that goal's last square")
    void anAiHitAfterGoalDeathUpdatesTheAttackOrdersGoalPosition() {
        World world = twoSideField();
        world.fog().revealAll(0);
        world.enableAi(0);
        Unit defender = world.createUnit(soldier("unit-ogre"), 0, 5, 10);
        Unit goal = world.createUnit(soldier("unit-wise-man"), 1, 8, 10);
        Unit second = world.createUnit(soldier("unit-knight"), 1, 5, 11);

        assertTrue(world.orderAttackMove(defender, 20, 20));
        defender.setTarget(goal);
        defender.setFighting(true);
        defender.setAutoTargeting(true);
        // The target was acquired on the march's own destination and has
        // since moved. That is level13h's exact discriminator: retaining the
        // acquisition square makes EndActionAttack think there is no march
        // to resume.
        defender.setAttackGoal(20, 20);
        world.kill(goal);
        world.hit(second, defender);

        assertNull(defender.target(), "OnAiHitUnit did not release the dead goal");
        assertEquals(goal.tileX(), defender.attackGoalX(),
                "OnAiHitUnit stored the dead goal in an unrelated orderTarget field");
        assertEquals(goal.tileY(), defender.attackGoalY(),
                "COrder_Attack::goalPos did not retain the goal's last square");
    }

    @Test
    @DisplayName("simplified targeting clears the threshold before answering a hit")
    void simplifiedTargetingDoesNotLetThresholdSuppressAttackBack() {
        World world = twoSideField();
        world.fog().revealAll(0);
        Unit defender = world.createUnit(soldier("unit-knight"), 0, 5, 10);
        Unit goal = world.createUnit(soldier("unit-grunt"), 1, 6, 10);
        Unit second = world.createUnit(soldier("unit-grunt-2"), 1, 5, 11);

        assertTrue(world.orderAttack(defender, goal));
        defender.setAutoTargeting(true);
        defender.setThreshold(20);
        world.hit(second, defender);

        assertEquals(0, defender.threshold(),
                "SimplifiedAutoTargeting did not clear Threshold on the hit");
        assertEquals(128, defender.underAttack(),
                "the stale threshold suppressed HitUnit_AttackBack");
        assertSame(second, defender.offeredTarget());
    }

    @Test
    @DisplayName("an in-range retarget keeps the unspent tail of the march route")
    void anInRangeRetargetDoesNotEraseTheCachedRoute() {
        World world = twoSideField();
        UnitType marcherType = soldier("unit-knight");
        Unit marcher = world.createUnit(marcherType, 0, 5, 10);
        UnitType quarryType = soldier("unit-grunt");
        quarryType.setPriority(70);
        quarryType.animationSet().put(AnimationSet.State.ATTACK,
                Animation.parse("attack", List.of(
                        "unbreakable begin", "wait 1", "unbreakable end", "wait 1")));
        Unit quarry = world.createUnit(quarryType, 1, 6, 10);
        UnitType oldType = soldier("unit-guard-tower");
        oldType.setPriority(1);
        Unit oldTarget = world.createUnit(oldType, 1, 10, 10);
        Unit blocker = world.createUnit(soldier("unit-moving-blocker"), 0, 4, 11);
        blocker.setWalkHolding(true);

        assertTrue(world.orderAttackMove(marcher, 3, 11));
        marcher.setTarget(oldTarget);
        marcher.setPathGoal(oldTarget.tileX(), oldTarget.tileY());
        marcher.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {Direction.fromDelta(-1, 0), Direction.fromDelta(-1, 1)}));
        marcher.setAttackScanSleep(0);
        world.tick();

        assertSame(quarry, marcher.target(), "the PF_WAIT scan did not acquire its quarry");
        assertEquals(1, marcher.pathLength(),
                "SetAutoTarget erased the cached route's still-unspent heading");
        for (int cycle = 0; cycle < 12; cycle++) {
            world.tick();
        }
        assertEquals(5, marcher.tileX(),
                "ATTACK_TARGET treated the cached route as an active march");
        assertSame(marcherType.animationSet().get(AnimationSet.State.ATTACK),
                marcher.animation().current(),
                "the fighter did not start its swing when PF_WAIT expired");
    }

    @Test
    @DisplayName("a blocked chase cycles Fast without discarding the cached route")
    void aBlockedChaseDoesNotReplanWhenFastCountsDownToZero() {
        World world = twoSideField();
        world.fog().revealAll(0);
        UnitType gruntType = soldier("unit-grunt");
        gruntType.setReactRangePerson(30);
        gruntType.setReactRangeComputer(30);
        Unit grunt = world.createUnit(gruntType, 0, 5, 10);
        UnitType quarryType = soldier("unit-guard-tower");
        quarryType.setSpeed(0);
        Unit quarry = world.createUnit(quarryType, 1, 10, 10);
        UnitType blockerType = soldier("unit-friendly-blocker");
        blockerType.setSpeed(0);
        world.createUnit(blockerType, 0, 6, 10);

        assertTrue(world.orderAttackMove(grunt, 30, 10), "the march was refused");
        grunt.setTarget(quarry);
        grunt.setChasing(true);
        grunt.setPathGoal(quarry.tileX(), quarry.tileY());
        grunt.setAttackScanSleep(1000);
        int east = Direction.fromDelta(1, 0);
        grunt.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {east, east, east, east, east, east,
                        east, east, east, east, east, east}));

        // NextPathElement spends one cached element before testing whether
        // its square is occupied. Fast follows 0,10,9...,1,0 across eleven
        // refusals, but the PF_WAIT result makes its apparent "expired"
        // re-plan branch unreachable. The twelfth cached element must
        // therefore still be waiting here.
        for (int refusal = 0; refusal < 11; refusal++) {
            while (grunt.waitCycles() > 0) {
                world.tick();
            }
            world.tick();
        }

        assertEquals(1, grunt.pathLength(),
                "Fast reaching zero discarded and re-planned the cached route");
        assertEquals(0, grunt.pathWaitBudget(),
                "the transcription did not preserve PathFinderOutput::Fast");
        assertFalse(grunt.routeSpent(),
                "the untried final cached element was reported as already spent");

        // This implementation consumes the fresh route's first element on the NewPath
        // call, whereas upstream retains Length on that call. routeSpent is
        // therefore the remaining cached consult upstream still owns: after
        // it refuses the last real heading, that consult decrements Length
        // to zero and returns the historical phantom PF_WAIT.
        while (grunt.waitCycles() > 0) {
            world.tick();
        }
        world.tick();
        assertEquals(0, grunt.pathLength());
        assertTrue(grunt.routeSpent(),
                "the final cached refusal lost upstream's remaining zero-length consult");
    }

    @Test
    @DisplayName("a march that arrives on a fresh blocked heading retains its spent route")
    void aFreshBlockedArrivalKeepsTheUnitPathOutput() {
        World world = twoSideField();
        world.fog().revealAll(0);
        UnitType archer = soldier("unit-archer");
        archer.animationSet().put(AnimationSet.State.MOVE, Animation.parse("slow-move",
                List.of("unbreakable begin", "frame 0", "move 4", "wait 1",
                        "frame 5", "move 4", "unbreakable end", "wait 1")));

        // Creation order matters: the blocker moves first in the tick. Its
        // adjacent-path lookup leaves the goal's clear cost in the shared
        // CostMoveTo cache, then it logically enters that square while its
        // long move animation still reports Moving=1.
        Unit blocker = world.createUnit(archer, 0, 7, 11);
        Unit marcher = world.createUnit(archer, 0, 6, 10);
        assertTrue(world.orderMove(blocker, 7, 10));
        assertTrue(world.orderAttackMove(marcher, 7, 10));

        world.tick();

        assertEquals(7, blocker.tileX());
        assertEquals(10, blocker.tileY());
        assertTrue(blocker.walkHolding(), "the fixture's blocker was not still moving");
        assertEquals(10, marcher.waitCycles(),
                "the heading onto the newly occupied square was not refused as PF_WAIT");
        assertEquals(0, marcher.pathLength(),
                "the port's fresh-route surrogate did not consume the refused heading");
        assertTrue(marcher.routeSpent(),
                "EndActionAttack cleared the unit-owned output left by the refused heading");
    }

    @Test
    @DisplayName("a blocked position arrival restores its saved attack-move")
    void aBlockedArrivalRestoresTheSavedAttackOrder() {
        World world = twoSideField();
        UnitType archer = soldier("unit-axethrower");
        archer.setMaxAttackRange(4);
        Unit marcher = world.createUnit(archer, 0, 5, 10);
        world.createUnit(soldier("unit-friendly-blocker"), 0, 6, 10);

        assertTrue(world.orderAttackMove(marcher, 9, 10));
        marcher.setAttackMoveOpening(false);
        marcher.setAutoTargeting(true);
        marcher.setMoveRange(1);
        marcher.setSavedOrder(Unit.Order.ATTACK_MOVE);
        marcher.setSavedAttackMove(9, 10);
        marcher.setSavedMoveRange(0);
        marcher.setSavedAttackMoveOpening(false);
        marcher.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {Direction.fromDelta(1, 0)}));

        world.tick();

        assertEquals(Unit.Order.ATTACK_MOVE, marcher.order(),
                "PF_WAIT arrival discarded the order waiting behind the live march");
        assertEquals(9, marcher.attackMoveX(),
                "EndActionAttack did not restore the saved march destination");
        assertNull(marcher.savedOrder(), "the restored order remained saved twice");
        assertEquals(0, marcher.waitCycles(),
                "the restored order inherited the finished march's PF_WAIT");
        assertEquals(0, marcher.pathLength(),
                "the exact-range clone reused its widened predecessor's route");
    }

    @Test
    @DisplayName("under attack defers a saved march at a blocked arrival")
    void underAttackDefersTheSavedOrderAtPfWaitArrival() {
        World world = twoSideField();
        UnitType archer = soldier("unit-sapper");
        Unit marcher = world.createUnit(archer, 0, 5, 10);
        world.createUnit(soldier("unit-friendly-blocker"), 0, 6, 10);

        assertTrue(world.orderAttackMove(marcher, 6, 10));
        marcher.setAttackMoveOpening(false);
        marcher.setAutoTargeting(true);
        marcher.setUnderAttack(100);
        marcher.setSavedOrder(Unit.Order.ATTACK_MOVE);
        marcher.setSavedAttackMove(5, 10);
        marcher.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {Direction.fromDelta(1, 0)}));

        world.tick();

        assertEquals(Unit.Order.STILL, marcher.order(),
                "UnderAttack restored the saved post instead of finishing the live march");
        assertEquals(Unit.Order.ATTACK_MOVE, marcher.savedOrder(),
                "EndActionAttack consumed SavedOrder through its short-circuit arm");
        assertEquals(10, marcher.waitCycles(),
                "finishing the order erased DoActionMove's PF_WAIT");
    }

    @Test
    @DisplayName("a flushed march command waits for the committed step to release")
    void aNewAttackMoveDoesNotOverwriteAnUnbreakableChaseStep() {
        World world = twoSideField();
        world.fog().revealAll(0);
        UnitType marcherType = soldier("unit-axethrower");
        marcherType.animationSet().put(AnimationSet.State.MOVE,
                Animation.parse("committed-move", List.of(
                        "unbreakable begin", "frame 0", "move 2", "wait 4",
                        "move 30", "unbreakable end", "wait 1")));
        Unit marcher = world.createUnit(marcherType, 0, 5, 10);
        UnitType quarryType = soldier("unit-guard-tower");
        quarryType.setSpeed(0);
        Unit quarry = world.createUnit(quarryType, 1, 10, 10);

        assertTrue(world.orderAttackMove(marcher, 30, 10));
        marcher.setTarget(quarry);
        marcher.setChasing(true);
        marcher.setPathGoal(quarry.tileX(), quarry.tileY());
        marcher.setAttackScanSleep(100);
        int east = Direction.fromDelta(1, 0);
        marcher.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {east, east}));
        world.tick();
        assertTrue(marcher.animation().unbreakable(),
                "the fixture never entered its committed move span");
        int cached = marcher.pathLength();

        // CommandAttack(..., EFlushMode::On) marks the current order finished
        // and queues the replacement behind it. HandleUnitAction cannot pop
        // that replacement while Anim.Unbreakable is set, so the current
        // chase target and PathFinderOutput survive until the step finishes.
        assertTrue(world.orderAttackMove(marcher, 3, 30));
        assertSame(quarry, marcher.target(),
                "the queued march erased the chase in the middle of its step");
        assertEquals(cached, marcher.pathLength(),
                "the queued march erased the committed route tail");
        assertEquals(30, marcher.attackMoveX(),
                "the queued march replaced the current order before its pop");

        for (int cycle = 0; cycle < 30 && marcher.attackMoveX() != 3; cycle++) {
            world.tick();
        }
        assertEquals(3, marcher.attackMoveX(),
                "the replacement march never became current after the step released");
    }

    @Test
    @DisplayName("a replacement attack order leaves the unit pathfinder output intact")
    void aNewAttackMovePreservesTheUnitLevelPathCache() {
        World world = twoSideField();
        Unit marcher = world.createUnit(soldier("unit-axethrower"), 0, 5, 10);
        Unit quarry = world.createUnit(soldier("unit-guard-tower"), 1, 10, 10);
        int east = Direction.fromDelta(1, 0);
        marcher.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {east, east}));
        marcher.setPathGoal(quarry.tileX(), quarry.tileY());
        marcher.setTarget(quarry);

        assertTrue(world.orderAttackMove(marcher, 30, 30));

        // COrder::NewActionAttack allocates an order; it does not reset the
        // CUnit's PathFinderInput or PathFinderOutput. The first Execute will
        // either invalidate the cache through UpdatePathFinderData or, if its
        // opening scan reacquires the same quarry, consume the old output.
        assertEquals(2, marcher.pathLength(),
                "constructing an attack order erased PathFinderOutput");
        assertEquals(quarry.tileX(), marcher.pathGoalX(),
                "constructing an attack order erased PathFinderInput");
        assertEquals(quarry.tileY(), marcher.pathGoalY());
    }

    @Test
    @DisplayName("a replacement march invalidates its old route only when the goal changes")
    void aReplacementMarchComparesItsGoalBeforeUsingTheCachedRoute() {
        int east = Direction.fromDelta(1, 0);

        World changedWorld = twoSideField();
        Unit changed = changedWorld.createUnit(
                soldier("unit-changed-axethrower"), 0, 5, 10);
        assertTrue(changedWorld.orderAttackMove(changed, 30, 10));
        changed.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {east, east}));
        assertTrue(changedWorld.orderAttackMove(changed, 5, 30));

        changedWorld.tick();

        assertEquals(5, changed.tileX(),
                "the replacement spent an east heading buffered for its old goal");
        assertEquals(11, changed.tileY(),
                "the replacement did not plan towards its new southern goal");

        World repeatedWorld = twoSideField();
        Unit repeated = repeatedWorld.createUnit(
                soldier("unit-repeated-axethrower"), 0, 5, 10);
        assertTrue(repeatedWorld.orderAttackMove(repeated, 30, 10));
        repeated.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {east, east}));
        assertTrue(repeatedWorld.orderAttackMove(repeated, 30, 10));

        repeatedWorld.tick();

        assertEquals(6, repeated.tileX(),
                "an unchanged march goal discarded its unit-level path output");
        assertEquals(10, repeated.tileY());
    }

    @Test
    @DisplayName("a replacement attack order does not inherit an offered target")
    void aReplacementAttackMoveStartsWithoutTheOldOrdersOffer() {
        World world = twoSideField();
        Unit marcher = world.createUnit(soldier("unit-knight"), 0, 5, 10);
        Unit distant = world.createUnit(soldier("unit-catapult"), 1, 20, 10);
        marcher.setOfferedTarget(distant);

        assertTrue(world.orderAttackMove(marcher, 10, 10));

        assertNull(marcher.offeredTarget(),
                "offeredTarget belongs to COrder_Attack, not to CUnit");
    }

    @Test
    @DisplayName("a queued attack waits for an unbreakable Still animation to release")
    void queuedAttackDoesNotRequeueForeverDuringCommittedStill() {
        World world = twoSideField();
        UnitType attackerType = soldier("unit-committed-grunt");
        Unit attacker = world.createUnit(attackerType, 0, 5, 10);
        Unit target = world.createUnit(soldier("unit-target-footman"), 1, 10, 10);
        attacker.setOrder(Unit.Order.STILL);
        attacker.animation().restore(
                attackerType.animationSet().get(AnimationSet.State.STILL),
                0, 4, true);
        attacker.enqueueOrder(new Unit.QueuedOrder(
                Unit.QueuedOrderKind.ATTACK,
                target.tileX(), target.tileY(), target, null, null));
        attacker.setQueuedReplacementPending(true);

        world.tick();

        assertEquals(Unit.Order.STILL, attacker.order(),
                "the committed animation was interrupted");
        assertTrue(attacker.hasQueuedOrders(),
                "the replacement was consumed before Unbreakable released");

        attacker.animation().clearUnbreakable();
        world.tick();

        assertEquals(Unit.Order.ATTACK, attacker.order());
        assertSame(target, attacker.target());
        assertFalse(attacker.hasQueuedOrders());
    }
}
