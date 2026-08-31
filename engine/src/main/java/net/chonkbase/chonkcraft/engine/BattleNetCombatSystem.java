package net.chonkbase.chonkcraft.engine;

import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudUnitTypes;
import net.chonkbase.chonkcraft.engine.ai.AiPlayer;
import net.chonkbase.chonkcraft.engine.animation.AnimationRunner;
import net.chonkbase.chonkcraft.engine.missile.Missile;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.animation.BattleNetSequence;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.map.MapField;
import net.chonkbase.chonkcraft.engine.missile.MissileType;
import net.chonkbase.chonkcraft.engine.pathfinder.BattleNetPathFinder;
import net.chonkbase.chonkcraft.engine.pathfinder.PathFinder;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;

/**
 * A fight: closing on something, swinging at it, and taking the blow back.
 *
 * <p>Implements retail BNE's target acquisition and attack sequence with its
 * action markers. The chase and the swing are one
 * subject because BNE interleaves them: the Move body drains before the
 * chase boundary is consulted, a committed swing lands after its target has
 * gone, and an approach holds the attack open for a set number of quiet
 * visits rather than for a distance.
 *
 * <p>Choosing what to shoot at is {@link BattleNetTargetSelection}; this is
 * what happens once it has been chosen.
 */
final class BattleNetCombatSystem {

    private static final int EXPIRED_QUARRY_FIRST_BAND_TAIL = 7;
    private static final int EXPIRED_QUARRY_SECOND_BAND = 8;
    private static final int EXPIRED_QUARRY_ATTACK_CONSTRUCTION = 9;
    private static final int EXPIRED_QUARRY_SINGLE_BAND_TAIL = 10;
    private static final int EXPIRED_QUARRY_REPLACEMENT_BODY_HOLD = 11;
    private static final int EXPIRED_QUARRY_FRESH_ROUTE_CONSTRUCTION = 12;
    private static final int SURFACED_QUARRY_RETARGET_CONSTRUCTION = 13;

    private final World world;

    BattleNetCombatSystem(World world) {
        this.world = world;
    }

    /**
     * Whether a one-step residual has advanced a saturated wall trace to the
     * face immediately counterclockwise from its direct destination, with a
     * three-byte straight run still cached behind that consumed prefix.
     */
    private boolean saturatedRetainedRouteFace(Unit unit) {
        if (unit.pathLength() != BattleNetPathFinder.MAX_PATH - 1
                || unit.battleNetPathStepsTaken() != 1
                || unit.pathGoalX() < 0 || unit.pathGoalY() < 0) {
            return false;
        }
        int direct = World.battleNetFirstBresenhamHeading(
                unit.tileX(), unit.tileY(),
                unit.pathGoalX(), unit.pathGoalY());
        return direct >= 0 && direct < Direction.COUNT
                && unit.peekHeading()
                        == Math.floorMod(direct - 1, Direction.COUNT)
                && unit.peekHeadingAtDepth(1) == unit.peekHeading()
                && unit.peekHeadingAtDepth(2) == unit.peekHeading();
    }

    /**
     * Whether a building retarget retains a reusable route behind its first
     * cardinal stride.
     *
     * <p>Retail leaves the consumed opening byte in the native route buffer.
     * When the stride settles, Attack construction owns three visits, Move
     * parks the route cursor for one visit, and the following callback redraws
     * the building-footprint route before replaying that opening. A short tail
     * is the distinct Move-fifteen construction, while a saturated wall face
     * has its own route-index-twenty policy.</p>
     */
    private boolean retainedBuildingRetargetReplay(Unit unit) {
        Unit target = unit.target();
        return unit.battleNetChaseReplanResidualHold()
                && unit.pathLength() >= 6
                && unit.pathLength() < BattleNetPathFinder.MAX_PATH - 1
                && unit.battleNetPathStepsTaken() == 1
                && unit.battleNetCollisionCounter() == 0
                && unit.battleNetRefusals() == 0
                && !Direction.isDiagonal(unit.lastStepHeading())
                && Direction.isDiagonal(unit.peekHeading())
                && target != null && target.isAlive() && target.isOnMap()
                && target.type() != null && target.type().building()
                && unit.type() != null && unit.type().maxAttackRange() <= 1
                && !World.battleNetRangedChaseUnit(unit);
    }

    /**
     * Keeps the cardinal wall head of a collided ranged residual refill.
     *
     * <p>The route optimizer normally swaps two compass headings when the
     * diagonal shortcut is free. On the residual-refill visit native still
     * tests that shortcut through the old collision view: XHuman 4
     * axethrower 1490 therefore keeps N at 77,62 instead of swapping N,NE to
     * Java's NE,N and walking away from its footman target. Restrict this to
     * a diagonal which actually increases target distance, and retain only
     * its free, non-regressing cardinal component.</p>
     */
    private void preserveCollidedRangedWallHead(Unit unit, Unit target) {
        if (unit.pathLength() == 0 || target == null
                || unit.type() == null || target.type() == null) {
            return;
        }
        int planned = unit.peekHeading();
        if (!Direction.isDiagonal(planned)) {
            return;
        }
        int targetLeft = target.tileX();
        int targetTop = target.tileY();
        int targetWidth = Math.max(1, target.type().tileWidth());
        int targetHeight = Math.max(1, target.type().tileHeight());
        int targetRight = targetLeft + targetWidth - 1;
        int targetBottom = targetTop + targetHeight - 1;
        int goalX = targetLeft;
        if (targetLeft < unit.tileX()) {
            goalX = unit.tileX() < targetRight
                    ? targetLeft + targetWidth / 2 : targetRight;
        }
        int goalY = targetTop;
        if (targetTop < unit.tileY()) {
            goalY = unit.tileY() < targetBottom
                    ? targetTop + targetHeight / 2 : targetBottom;
        }
        int stepX = Direction.deltaX(planned);
        int stepY = Direction.deltaY(planned);
        int towardX = Integer.signum(goalX - unit.tileX());
        int towardY = Integer.signum(goalY - unit.tileY());
        boolean xAgrees = stepX == towardX;
        boolean yAgrees = stepY == towardY;
        if (xAgrees == yAgrees) {
            return;
        }
        int cardinal = xAgrees
                ? Direction.fromDelta(stepX, 0)
                : Direction.fromDelta(0, stepY);
        int stride = world.battleNetMovementStride(unit);
        int currentDistance = Math.max(Math.abs(unit.tileX() - goalX),
                Math.abs(unit.tileY() - goalY));
        int diagonalDistance = Math.max(Math.abs(unit.tileX()
                        + stepX * stride - goalX),
                Math.abs(unit.tileY() + stepY * stride - goalY));
        int cardinalX = unit.tileX()
                + Direction.deltaX(cardinal) * stride;
        int cardinalY = unit.tileY()
                + Direction.deltaY(cardinal) * stride;
        int cardinalDistance = Math.max(Math.abs(cardinalX - goalX),
                Math.abs(cardinalY - goalY));
        world.causalTrace.event(world.cycle,
                "path.ranged-collided-wall-head", unit.id(),
                "target", target.id(),
                "planned", planned,
                "cardinal", cardinal,
                "stride", stride,
                "goalX", goalX,
                "goalY", goalY,
                "currentDistance", currentDistance,
                "diagonalDistance", diagonalDistance,
                "cardinalDistance", cardinalDistance,
                "cardinalEnterable",
                        world.canEnter(unit, cardinalX, cardinalY));
        if (diagonalDistance <= currentDistance
                || cardinalDistance > currentDistance
                || !world.canEnter(unit, cardinalX, cardinalY)) {
            return;
        }
        unit.replacePeekHeading(cardinal);
        world.causalTrace.event(world.cycle,
                "path.ranged-collided-wall-head-rewrite", unit.id(),
                "target", target.id(),
                "from", planned,
                "to", cardinal);
    }

    /**
     * Whether a chase soft-wait may treat {@code x,y} as enterable.
     *
     * <p>Includes a cell a moving ally is vacating: the ally's tile still
     * reports occupied under {@link World#canEnter} while residual drains
     * (XHuman 12 axe 76 at 32,38m), but native steps the chasing grunt E
     * onto that cell the cycle the axe leaves.
     */
    /**
     * Vacating ally only -- not an empty cell. Empty free neighbours are
     * already handled by ordinary soft-wait expiry; counting them here
     * REGed human-13's async seed at fixture 42.
     */
    private boolean chaseCellVacating(Unit unit, int x, int y) {
        Unit blocker = world.unitAt(x, y);
        if (blocker == null || blocker == unit || !blocker.isOnMap()
                || blocker.isDying()
                || !world.isAllied(unit.player(), blocker.player())
                || !blocker.isMoving()) {
            return false;
        }
        // Vacating: last step or path heading takes them off this cell.
        int leave = blocker.pathLength() > 0 ? blocker.peekHeading()
                : blocker.lastStepHeading();
        if (leave < 0 || leave >= Direction.COUNT) {
            return false;
        }
        int leaveX = blocker.tileX()
                + Direction.deltaX(leave) * world.battleNetMovementStride(blocker);
        int leaveY = blocker.tileY()
                + Direction.deltaY(leave) * world.battleNetMovementStride(blocker);
        return leaveX != x || leaveY != y;
    }

    /**
     * Keeps the dead quarry's final approach square as dest-arm's first step.
     *
     * <p>Attack OP0 can replace a quarry after its exhausted chase residual
     * has landed.  Native still feeds the old goal square to the first route
     * heading before the replacement goal takes ownership.  XHuman 10 grunt
     * 1495 therefore writes NE,E toward knight 1489: NE is the adjacent cell
     * formerly occupied by dying footman 1492, while an immediate fresh plan
     * writes E,E.  The old path goal survives {@link Unit#clearPath()}, so it
     * is also the state a save made during Attack construction can preserve.
     */
    private void keepDeadQuarryDestArmHeading(Unit unit, Unit replacement,
            int oldGoalX, int oldGoalY, boolean oldGoalEnterable) {
        if (replacement == null || unit.pathLength() == 0
                || oldGoalX < 0 || oldGoalY < 0
                || (oldGoalX == replacement.tileX()
                        && oldGoalY == replacement.tileY())) {
            return;
        }
        int dx = oldGoalX - unit.tileX();
        int dy = oldGoalY - unit.tileY();
        if (Math.max(Math.abs(dx), Math.abs(dy)) != 1
                || !oldGoalEnterable) {
            return;
        }
        int targetWidth = replacement.type() == null ? 1
                : Math.max(1, replacement.type().tileWidth());
        int targetHeight = replacement.type() == null ? 1
                : Math.max(1, replacement.type().tileHeight());
        int nearX = World.battleNetNearFootprintCoordinate(oldGoalX,
                replacement.tileX(), targetWidth);
        int nearY = World.battleNetNearFootprintCoordinate(oldGoalY,
                replacement.tileY(), targetHeight);
        int distance = Math.max(Math.abs(nearX - oldGoalX),
                Math.abs(nearY - oldGoalY));
        if (unit.type() == null
                || distance < unit.type().minAttackRange()
                || distance > Math.max(1, unit.type().maxAttackRange())) {
            return;
        }
        int heading = Direction.fromDelta(dx, dy);
        if (heading < 0 || heading >= Direction.COUNT) {
            return;
        }
        int n = unit.pathLength();
        int[] headings = new int[n];
        for (int depth = 0; depth < n; depth++) {
            headings[n - 1 - depth] = unit.peekHeadingAtDepth(depth);
        }
        headings[n - 1] = heading;
        unit.setPath(new PathFinder.Path(PathFinder.Result.FOUND, headings));
        unit.setPathGoal(replacement.tileX(), replacement.tileY());
    }

    /** Clears only stale blocking bits; dying bodies stay in UnitCache. */
    private void clearDyingMovementFieldAt(int x, int y) {
        for (Unit corpse : world.unitsSnapshot()) {
            if (corpse.isDying() && corpse.isOnMap() && corpse.type() != null
                    && corpse.covers(x, y)) {
                world.setMovementFieldFlags(corpse, false);
            }
        }
    }

    /**
     * Points a woodcutter at a square, throwing away the route it was walking.
     *
     * @return whether the worker is already standing next to it, and so has
     *         nothing left to walk
     */
    boolean aimAt(Unit worker, int[] tile) {
        if (tile[0] != worker.resourceTileX() || tile[1] != worker.resourceTileY()) {
            worker.setResourceTile(tile[0], tile[1]);
            worker.clearPath();
        }
        // The chop begins on the next cycle, through the ordinary "beside the
        // resource" test. Asking for a route now would be asking for a route
        // to a square the worker is already standing beside, which
        // findRouteToOrBeside answers with null -- and that null would read as
        // "no way there" and stand the worker down beside a full tree.
        return Math.max(Math.abs(worker.tileX() - tile[0]),
                Math.abs(worker.tileY() - tile[1])) <= 1;
    }


    /**
     * Transfers a saturated cardinal chase tail through retail's Still retry.
     *
     * <p>The final residual can settle inside the borrowed Move call, so this
     * boundary is checked both before and immediately after that call. The
     * post-Move check owns {@code FUN_0040ad30}'s land-idle choice; a Move
     * state first observed at the top of a later callback has already paid
     * that dispatcher elsewhere. Native then promotes the retained quarry
     * into Attack construction 3,2,1. If its first timer-one visit remains
     * blocked, the Still retry pays one more choice and opens one new 3,2,1
     * construction before releasing to Move. XHuman 12 slot 1482 is the
     * sealed witness: fixtures 193 and 196 both record the idle draw and
     * Attack timer three at (31,41), then fixture 199 commits southwest.
     * Deferring the first handoff leaves melee damage one asynchronous draw
     * behind; releasing the first timer-one visit moves three cycles early.</p>
     */
    private boolean openBattleNetSaturatedCardinalRouteTerminator(
            Unit unit, boolean settlingNow) {
        int attackStart = world.idle.battleNetSequenceStart(unit,
                BattleNetSequence.ATTACK_ANIMATION);
        boolean recurringBlockedStillRetry = attackStart >= 0
                && unit.battleNetBlockedChaseAttackConstruction()
                && unit.battleNetSaturatedCardinalRetryLoop()
                && unit.battleNetSequenceOffset() == attackStart
                && unit.battleNetAnimationTimer() == 1;
        boolean saturatedCardinalRouteTerminator =
                !unit.isMoving()
                && unit.pathLength() == 0
                && unit.battleNetCollisionCounter() >= 6
                && unit.battleNetRefusals() == 0
                && unit.battleNetRetargetResidualParkSteps() == 0
                && !Direction.isDiagonal(unit.lastStepHeading())
                && (recurringBlockedStillRetry
                        || (unit.battleNetAnimationTimer() == 1
                                && onBattleNetChaseMoveBody(unit)))
                && unit.target() != null
                && unit.target().isAlive()
                && !world.targets.inAttackRange(unit, unit.target())
                && unit.type() != null
                && unit.type().maxAttackRange() <= 1;
        if (!saturatedCardinalRouteTerminator) {
            return false;
        }
        if (attackStart < 0) {
            return false;
        }
        // Only a residual that actually spent a cardinal heading owns the
        // Still dispatcher. A parked hard-refusal route can reach the same
        // visible cursor without settling, but it remains in Move retry: its
        // eighth refusal opens the complete Move band and later paid bands
        // retain that ownership. Promoting that cursor into Attack here made
        // XHuman 12 slot 1457 leave Move six fixtures early, then cycle a
        // hidden Attack constructor while native drained its retained route.
        boolean ownsIdleDraw = (settlingNow || recurringBlockedStillRetry)
                && unit.routeSpent()
                && unit.battleNetPathStepsTaken() > 0;
        if (!ownsIdleDraw) {
            return false;
        }
        world.idle.advanceBattleNetActiveOrderIdleRandom(unit);
        if (settlingNow) {
            unit.setBattleNetSaturatedCardinalRetryLoop(true);
        } else if (recurringBlockedStillRetry) {
            // The retained Still callback is a one-shot owner. Its new
            // construction drains 3,2,1, then the ordinary Move handoff may
            // spend the replacement heading (slot 1482, fixture 199).
            unit.setBattleNetSaturatedCardinalRetryLoop(false);
        }
        unit.setBattleNetSequenceOffset(attackStart);
        unit.setBattleNetAnimationTimer(3);
        unit.setBattleNetBlockedChaseAttackConstruction(true);
        AnimationSet set = unit.type().animationSet();
        Animation attack = set == null ? null
                : set.get(AnimationSet.State.ATTACK);
        if (attack != null && unit.animation().current() != attack) {
            unit.animation().switchTo(attack);
        }
        world.causalTrace.event(world.cycle,
                "combat.saturated-cardinal-route-terminator", unit.id(),
                "target", unit.target().id(),
                "collision", unit.battleNetCollisionCounter(),
                "last_heading", unit.lastStepHeading(),
                "idle_draw", ownsIdleDraw,
                "recurring_retry", recurringBlockedStillRetry);
        return true;
    }


    /**
     * Advances a unit that is fighting.
     *
     * <p>Out of range it walks closer; in range it runs its attack animation,
     * and the blow lands on the cycle the animation says it does rather than
     * the moment the order was given. That is what gives Warcraft II's combat
     * its wind-up.
     */
    void stepAttack(Unit unit) {
        // BNE's ranged cadence byte is wall-clock state owned by the order,
        // not by the currently visible Attack animation. It keeps draining
        // through retarget construction and chase movement so arrival waits
        // only the unspent portion of the attack period.
        if (unit.battleNetRangedAttackCadenceRemaining() > 0) {
            unit.setBattleNetRangedAttackCadenceRemaining(
                    unit.battleNetRangedAttackCadenceRemaining() - 1);
        }
        if (unit.battleNetResidualEmptyApproachIdlePending()) {
            unit.setBattleNetResidualEmptyApproachIdlePending(false);
            if (unit.battleNetSaturatedResidualFaceRetry()
                    && unit.battleNetCollisionCounter() >= 6) {
                // A saturated diagonal route terminator owns one RI20 visit,
                // then clears the completed collision generation through the
                // ordinary active-order Still callback below. The sealed
                // native trace records slot 1495's 0040AD58 draw immediately
                // after projectile damage at fixture 122; suppressing it
                // reassigns the later missile and melee damage rolls. State is
                // 0x50 while NW drains, 0x60/Move-start-1 on fixture 121, and
                // 0x00/Attack-start-3 after the callback on fixture 122.
                unit.setBattleNetSaturatedResidualFaceRetry(false);
                unit.setBattleNetRefusals(0);
                unit.setBattleNetCollisionCounter(0);
            }
            if (unit.target() != null && unit.target().isAlive()
                    && !world.targets.inAttackRange(unit, unit.target())
                    && rearmBattleNetHardRefusalAttack(unit)) {
                return;
            }
        }
        if (openBattleNetSaturatedCardinalRouteTerminator(unit, false)) {
            return;
        }
        boolean planOnlyAfterEmptyPaidConstruction = false;
        boolean saturatedPaidEmptyImmediateStep = false;
        // A completed fifteen-count Move refusal band can hand a still-blocked
        // chase through Attack construction 3,2,1 before Move owns the route
        // park. Keep that construction ticking even though the quarry remains
        // out of range; the ordinary attack-sequence gate correctly ignores
        // all other out-of-range chasers.
        if (unit.battleNetBlockedChaseAttackConstruction()) {
            boolean retainedBuildingReplay =
                    retainedBuildingRetargetReplay(unit);
            boolean paidReplacementBand =
                    unit.battleNetChaseReplanResidualHold()
                    && (unit.pathLength() == 0
                            || unit.battleNetPathStepsTaken() > 0
                            || unit.battleNetRefusals() == 0);
            boolean retainedPaidConstruction = paidReplacementBand
                    && (saturatedRetainedRouteFace(unit)
                            || retainedBuildingReplay
                            || (unit.battleNetAttackWrapDestArmPending()
                                    && (unit.pathLength() == 4
                                            || unit.pathLength()
                                                    == BattleNetPathFinder
                                                            .MAX_PATH - 1)))
                    && unit.battleNetPathStepsTaken() > 0;
            boolean longRetainedPaidConstruction =
                    retainedPaidConstruction && unit.pathLength() == 4;
            boolean saturatedRetainedPaidConstruction =
                    retainedPaidConstruction
                            && unit.pathLength()
                                    == BattleNetPathFinder.MAX_PATH - 1;
            int retainedBuildingHeading = retainedBuildingReplay
                    ? unit.peekHeading() : -1;
            int retainedBuildingStride = retainedBuildingReplay
                    ? world.battleNetMovementStride(unit) : 0;
            boolean retainedBuildingHeadBlocked = retainedBuildingReplay
                    && !world.canEnter(unit,
                            unit.tileX() + Direction.deltaX(
                                    retainedBuildingHeading)
                                    * retainedBuildingStride,
                            unit.tileY() + Direction.deltaY(
                                    retainedBuildingHeading)
                                    * retainedBuildingStride);
            saturatedPaidEmptyImmediateStep = paidReplacementBand
                    && unit.pathLength() == 0
                    && unit.battleNetCollisionCounter() == 0
                    && unit.battleNetRefusals() == 0
                    && unit.battleNetParkedRefusalHeading() >= 0
                    && unit.battleNetParkedRefusalHeading() < Direction.COUNT
                    && Direction.isDiagonal(unit.lastStepHeading());
            planOnlyAfterEmptyPaidConstruction = paidReplacementBand
                    && unit.pathLength() == 0
                    && !saturatedPaidEmptyImmediateStep;
            int attackStart = world.battleNetSequence == null ? -1
                    : world.idle.battleNetSequenceStart(unit,
                            BattleNetSequence.ATTACK_ANIMATION);
            if (attackStart >= 0
                    && unit.battleNetSequenceOffset() == attackStart
                    && unit.battleNetAnimationTimer() > 1) {
                unit.setBattleNetAnimationTimer(
                        unit.battleNetAnimationTimer() - 1);
                return;
            }
            Unit completedQuarryLeg = unit.target();
            boolean recurringMovingQuarryRetarget =
                    unit.battleNetRetargetResidualRoutePark()
                    && unit.battleNetAttackWrapDestArmPending()
                    && unit.pathLength() == 1
                    && unit.battleNetPathInitialLength() > 1
                    && unit.battleNetPathStepsTaken() == 1
                    && unit.battleNetCollisionCounter() == 0
                    && unit.battleNetRefusals() == 0
                    && completedQuarryLeg != null
                    && completedQuarryLeg.isAlive()
                    && completedQuarryLeg.type() != null
                    && !completedQuarryLeg.type().building()
                    && completedQuarryLeg.order() == Unit.Order.HARVEST;
            if (recurringMovingQuarryRetarget) {
                int reactRange = Math.max(
                        unit.type().reactRange(world.isPerson(unit.player())),
                        Math.max(1, unit.type().maxAttackRange()));
                Unit replacement = world.targets.findBattleNetHostile(
                        unit, reactRange, null);
                if (replacement != null && replacement != completedQuarryLeg
                        && replacement.isAlive() && !replacement.isDying()
                        && !world.targets.inAttackRange(unit, replacement)) {
                    // The retained last byte belongs to the quarry which has
                    // moved again during construction. Timer one therefore
                    // enters active-order Still, free-scans a replacement,
                    // parks the old route at twenty and immediately reopens
                    // Attack 3,2,1. Human 8 slot 1520 names slot 1536 and
                    // owns 0040AD58 on fixture 307; releasing the retained
                    // south-east byte instead delays that draw and hands its
                    // value to critter 1496 three fixtures later.
                    unit.setBattleNetBlockedChaseAttackConstruction(false);
                    unit.setBattleNetChaseReplanResidualHold(false);
                    unit.setBattleNetRetargetResidualRoutePark(false);
                    unit.setBattleNetAttackWrapDestArmPending(false);
                    unit.clearPath();
                    unit.setRouteSpent(false);
                    unit.setBattleNetChaseStepReady(false);
                    unit.setBattleNetChaseEmptyRouteReplan(true);
                    setAutoTarget(unit, replacement);
                    unit.setPathGoal(
                            replacement.tileX(), replacement.tileY());
                    unit.setChasing(true);
                    unit.setFighting(false);
                    rearmBattleNetHardRefusalAttack(unit);
                    return;
                }
            }
            unit.setBattleNetBlockedChaseAttackConstruction(false);
            unit.setBattleNetSaturatedCardinalRetryLoop(false);
            unit.setBattleNetChaseReplanResidualHold(false);
            unit.setBattleNetRetargetResidualRoutePark(true);
            int moveStart = world.battleNetSequence == null ? -1
                    : world.idle.battleNetSequenceStart(unit,
                            BattleNetSequence.MOVE_ANIMATION);
            if (moveStart >= 0) {
                unit.setBattleNetSequenceOffset(moveStart);
                unit.setBattleNetAnimationTimer(
                        paidReplacementBand
                                && !planOnlyAfterEmptyPaidConstruction
                                && !retainedBuildingReplay
                                && !saturatedRetainedPaidConstruction
                                        ? 15 : 1);
            }
            if (paidReplacementBand
                    && !planOnlyAfterEmptyPaidConstruction
                    && !retainedBuildingReplay) {
                // The post-band Attack handoff gives Move one route-plan-only
                // visit. Keep the replacement buffer and timer fifteen; the
                // first heading is a later Execute. This is stage three's
                // existing ownership contract, also used by the hard-refusal
                // recovery state machine.
                unit.setBattleNetAttackRefusalRecoveryStage(3);
            }
            if (saturatedPaidEmptyImmediateStep) {
                // This marker described the retired route generation. The
                // replacement writer below must choose its own first byte.
                unit.setBattleNetParkedRefusalHeading(-1);
            }
            AnimationSet set = unit.type() == null
                    ? null : unit.type().animationSet();
            Animation move = set == null
                    ? null : set.get(AnimationSet.State.MOVE);
            if (move != null && unit.animation().current() != move) {
                unit.animation().switchTo(move);
            }
            if (planOnlyAfterEmptyPaidConstruction) {
                // Empty parked buffers are not rebuilt on the Attack-one
                // completion visit. Retail exposes Move-start/1 with RI 20,
                // then performs target scan, route generation and the first
                // step together on the following visit (XHuman 12 grunt
                // 1512: park fixture 39, NE fixture 40).
                unit.setBattleNetAttackRefusalRecoveryStage(0);
                return;
            }
            if (retainedBuildingHeadBlocked) {
                // The native cursor is parked past the route buffer on this
                // visit. The consumed cardinal opening is still present in
                // raw storage, but NewPath redraws the complete footprint-
                // aware route before replaying it on the following visit.
                // Keeping Java's old tail happened to replay east correctly,
                // but retained a stale duplicate diagonal: XHuman 12 slot
                // 1510 then consumed SE instead of S thirty-two cycles later.
                // Clearing the logical route models RI20; the ordinary chase
                // visit redraws and consumes E on fixture 207.
                unit.clearPath();
                unit.setRouteSpent(false);
                unit.setWaitCycles(0);
                unit.setBattleNetCollisionCounter(1);
                unit.setBattleNetAttackRefusalRecoveryStage(0);
                unit.setBattleNetChaseStepReady(false);
                return;
            }
            // A free cached head is still owned by the completed Attack
            // constructor. It transfers directly to Move and is consumed
            // below on this timer-one visit; only a refused head takes the
            // route-index-twenty replay above. XHuman 12 slot 1503 retains
            // southeast after its fixture-252 east leg and spends it on 271.
            if (longRetainedPaidConstruction) {
                // The timer-one handoff itself only transfers ownership.
                // Native keeps every cached byte and exposes the complete
                // Move 15 constructor on this visit; probing the parked head
                // here collapses that constructor to Move 1. The following
                // callbacks drain 14..1 before the route-plan visit.
                unit.setBattleNetOrderDelay(14);
                return;
            }
        }
        boolean completedPersonHelpRetargetHandoff = false;
        // A lethal-splash help chase retains its commanded route while native
        // pays Attack construction 3,2,1, then hands ownership to automatic
        // retargeting on the timer-one visit. This is deliberately outside
        // stepBattleNetAttackSequence: the unit is still chasing an out-of-
        // range quarry, so the ordinary sequence gate correctly ignores it.
        if (unit.battleNetPersonHelpRetargetHandoff()) {
            int attackStart = world.battleNetSequence == null ? -1
                    : world.idle.battleNetSequenceStart(unit,
                            BattleNetSequence.ATTACK_ANIMATION);
            boolean constructionOwnsVisit = attackStart >= 0
                    && unit.battleNetSequenceOffset() == attackStart;
            // A live hit offer may be accepted on the callback which also
            // commits the replacement route's first heading.  Its handoff
            // marker must survive that whole pixel residual; construction
            // does not own the marker until residual settlement installs the
            // Attack-start cursor.  Existing splash-help callers install the
            // cursor immediately and therefore enter the same arm directly.
            if (constructionOwnsVisit) {
                if (unit.battleNetAnimationTimer() > 1) {
                    unit.setBattleNetAnimationTimer(
                            unit.battleNetAnimationTimer() - 1);
                    if (unit.battleNetOrderDelay() > 0) {
                        unit.setBattleNetOrderDelay(
                                unit.battleNetOrderDelay() - 1);
                    }
                    return;
                }
                boolean liveCloseHitHandoff =
                        unit.battleNetPendingCloseHitHelp();
                boolean spatialHitHandoff =
                        unit.battleNetSpatialHitHelpHandoff();
                boolean personNavalHitHandoff = world.isPerson(unit.player())
                        && unit.type() != null
                        && unit.type().moveType() == UnitType.Movement.NAVAL;
                boolean personLandHitHandoff =
                        unit.battleNetPersonHitHelpAutoSelectHandoff();
                boolean personLandSpatialHitHandoff = spatialHitHandoff
                        && world.isPerson(unit.player())
                        && unit.type() != null
                        && unit.type().moveType() == UnitType.Movement.LAND;
                unit.setBattleNetPendingCloseHitHelp(false);
                unit.setBattleNetPersonHelpRetargetHandoff(false);
                unit.setBattleNetPersonHitHelpAutoSelectHandoff(false);
                // Person shoreline defenders keep this provenance through
                // the temporary route's committed residual. Their HitUnit
                // response ends at that Move tail rather than becoming an
                // indefinite strong chase. A person naval responder also
                // retains it while the selected quarry is still out of range:
                // every doubled stride belongs to the same queued HitUnit
                // response and pays a fresh Attack constructor on settlement.
                boolean continuingPersonNavalHitHandoff =
                        personNavalHitHandoff
                        && unit.target() != null && unit.target().isAlive()
                        && !world.targets.inAttackRange(
                                unit, unit.target());
                unit.setBattleNetSpatialHitHelpHandoff(
                        personLandSpatialHitHandoff
                                || continuingPersonNavalHitHandoff);
                unit.setBattleNetOrderDelay(0);
                if (personLandHitHandoff && unit.target() != null
                        && world.projectiles.hasLethalDirectImpactDueThisCycle(
                                unit.target())) {
                    // Retail's fixed projectile pool has already reached its
                    // owed direct-impact free before this queued HitUnit
                    // handoff may spend a route byte. The shot's damage and
                    // victim were committed by its constructor; only release
                    // the temporary order here and let the ordinary cycle-end
                    // projectile pass apply the real hit effects. XHuman 10
                    // knight 1489 pays Attack 3,2,1 through fixture 202, then
                    // guard-tower arrow slot 1468 kills grunt 1471 on 203 and
                    // the knight exposes Still@1869/1 without stepping south.
                    world.finishAttackOrder(unit);
                    if (world.battleNetSequence != null) {
                        unit.setBattleNetSequenceOffset(
                                world.idle.battleNetStillSequenceStart(unit));
                        unit.setBattleNetAnimationTimer(1);
                        // EndActionAttack has installed active-order Still,
                        // and HandleUnitAction dispatches that new program on
                        // this same scheduler visit. Its OP0 callback owns a
                        // random-facing draw while retaining Still@start/1.
                        // XHuman 10 knight 1489 is the sealed witness: the
                        // cycle-end arrow frees grunt 1471 on fixture 203,
                        // then 0040AD58 returns 25389 for the knight before
                        // the scheduler continues to the remaining units.
                        world.idle.advanceBattleNetActiveOrderIdleRandom(unit);
                    }
                    return;
                }
                if ((personNavalHitHandoff || personLandHitHandoff)
                        && unit.target() != null && unit.target().isAlive()) {
                    // Person HitUnit banks the blow's source as next_order,
                    // then AutoSelectTarget owns the timer-one handoff.
                    // XHuman 10 footman 1529 therefore changes from ogre 1538
                    // to ogre 1548 before first-stepping NW; XOrc 11
                    // destroyer 1525 changes from the battleship at 16,40 to
                    // the nearer destroyer at 6,30 and first-steps north.
                    int react = Math.max(
                            unit.type().reactRange(true),
                            Math.max(1, unit.type().maxAttackRange()));
                    Unit hitCandidate = world.targets.findBattleNetHostile(
                            unit, react, null);
                    if (hitCandidate != null
                            && hitCandidate != unit.target()) {
                        setAutoTarget(unit, hitCandidate);
                        // The timer-one hit-help handoff selects the new sea
                        // or land quarry before committing its first route
                        // byte, but that route is still owned by the queued
                        // Attack. Keep the ownership through the whole pixel
                        // residual so settlement opens fresh Attack
                        // construction instead of treating arrival as an
                        // already-open swing. XHuman 10 footman 1529 exposes
                        // 2539/3,2,1 before cached N; XOrc 11 destroyer 1525
                        // exposes 3266/3,2,1 before its cannon opcode.
                        unit.setBattleNetChaseReplanResidualHold(true);
                        // Keep this constructor owner across the first pixel
                        // leg too. Residual settlement installs Attack-start
                        // at timer three; this same handoff then drains 3,2,1
                        // directly before Move may consume the retained tail.
                        // A bare replan hold substitutes an orderDelay and
                        // leaves the visible cursor frozen at three.
                        unit.setBattleNetPersonHelpRetargetHandoff(true);
                    } else if (personLandHitHandoff
                            && !world.targets.inAttackRange(
                                    unit, unit.target())
                            && !world.movement
                                    .battleNetHasStrictlyCloserFreeNeighbour(
                                            unit, unit.target())) {
                        // A person's close HitUnit offer is a temporary
                        // response, not a strong chase of the offered source.
                        // AutoSelectTarget may replace that source and turn
                        // the response into a chase (footman 1529 replaces
                        // ogre 1538 with ogre 1548 on fixture 176). If the
                        // scan only returns the same out-of-range source and
                        // no free compass step closes weapon distance, retail
                        // consumes the queued Attack constructor and releases
                        // the order instead. XHuman 10 knight 1489 therefore
                        // drains 1922/3,2,1 against grunt 1482, then exposes
                        // Still 1869/1 on fixture 212 without stepping around
                        // the friendly knight at 82,89. XHuman 9 footman 1423
                        // is the counterexample: its north square is free and
                        // closer, so the same close-hit handoff pursues on
                        // fixture 59 rather than returning to Still.
                        world.finishAttackOrder(unit);
                        if (world.battleNetSequence != null) {
                            unit.setBattleNetSequenceOffset(
                                    world.idle.battleNetStillSequenceStart(
                                            unit));
                            unit.setBattleNetAnimationTimer(1);
                            // The temporary order ends through the same
                            // active-order Still dispatch as the lethal-free
                            // arm above. Native XHuman 10 knight 1489 pays
                            // 0040AD58 here on fixture 212; omitting it hands
                            // that result to ogre 1548's fixture-225 damage
                            // roll instead.
                            world.idle.advanceBattleNetActiveOrderIdleRandom(
                                    unit);
                        }
                        return;
                    }
                }
                if (liveCloseHitHandoff) {
                    // The queued close-hit Attack owns a fresh target scan,
                    // not the old residual's facing preference. Native
                    // XHuman 9 footman 1420 drops its retained NE at the
                    // timer-one handoff, selects skeleton 1431, and consumes
                    // the fresh route's W on fixture 78. The generic residual
                    // park deliberately preserves facing for other queued-
                    // Attack handoffs, so clear it only for this proved offer
                    // provenance.
                    unit.setBattleNetRetargetResidualRoutePark(false);
                }
                if (unit.stepDrained() && !unit.isMoving()
                        && unit.pathLength() > 0) {
                    int retainedHeading = unit.peekHeading();
                    int retainedStride = world.battleNetMovementStride(unit);
                    int retainedX = unit.tileX()
                            + Direction.deltaX(retainedHeading) * retainedStride;
                    int retainedY = unit.tileY()
                            + Direction.deltaY(retainedHeading) * retainedStride;
                    if (!world.canEnter(unit, retainedX, retainedY)) {
                        // Promotion of the queued Attack does not erase the
                        // route which the interrupted chase owned. If its next
                        // byte now refuses, retail first parks the old cursor at
                        // 20 and returns to Move-start/1; only the next visit
                        // scans, writes a replacement and spends its head.
                        // XHuman 4 footman 1497 therefore stays on (71,60) at
                        // fixture 87 and first-steps N on 88. Replanning in the
                        // timer-one handoff stepped it one cycle early.
                        unit.clearPath();
                        unit.setRouteSpent(false);
                        int moveStart = world.battleNetSequence == null ? -1
                                : world.idle.battleNetSequenceStart(unit,
                                        BattleNetSequence.MOVE_ANIMATION);
                        if (moveStart >= 0) {
                            unit.setBattleNetSequenceOffset(moveStart);
                            unit.setBattleNetAnimationTimer(1);
                        }
                        AnimationSet set = unit.type() == null
                                ? null : unit.type().animationSet();
                        Animation move = set == null ? null
                                : set.get(AnimationSet.State.MOVE);
                        if (move != null
                                && unit.animation().current() != move) {
                            unit.animation().switchTo(move);
                        }
                        return;
                    }
                }
                if (spatialHitHandoff && unit.type() != null
                        && unit.type().moveType() == UnitType.Movement.NAVAL
                        && unit.pathLength() == 0
                        && unit.target() != null && unit.target().isAlive()) {
                    // A fresh naval hit-help order enters the retail sea-path
                    // writer with no retained route. Its first compass byte
                    // is the direct heading when that doubled stride is open.
                    // XOrc 11's destroyer at 22,38 therefore banks SW toward
                    // the aggressor at 10,44 and commits 20,40 on fixture 95;
                    // the generic optimized Java ray preferred W. Land keeps
                    // the ordinary route writer: XHuman 12 grunt 1447 must
                    // detour SE around the blocked direct SW approach.
                    Unit hitTarget = unit.target();
                    int dx = Integer.signum(
                            hitTarget.tileX() - unit.tileX());
                    int dy = Integer.signum(
                            hitTarget.tileY() - unit.tileY());
                    if (dx != 0 || dy != 0) {
                        int heading = Direction.fromDelta(dx, dy);
                        int stride = world.battleNetMovementStride(unit);
                        int nextX = unit.tileX()
                                + Direction.deltaX(heading) * stride;
                        int nextY = unit.tileY()
                                + Direction.deltaY(heading) * stride;
                        if (world.canEnter(unit, nextX, nextY)) {
                            unit.setPath(new PathFinder.Path(
                                    PathFinder.Result.FOUND,
                                    new int[] {heading}));
                            unit.setPathGoal(
                                    hitTarget.tileX(), hitTarget.tileY());
                            unit.setRouteSpent(false);
                        }
                    }
                }
                completedPersonHelpRetargetHandoff = true;
            }
        }
        if (unit.battleNetNavalPatrolAttackConstruction()) {
            if (unit.battleNetAnimationTimer() > 1) {
                unit.setBattleNetAnimationTimer(
                        unit.battleNetAnimationTimer() - 1);
                return;
            }
            unit.setBattleNetNavalPatrolAttackTimerOneReady(true);
        }
        if (unit.battleNetLandPatrolAttackConstruction()) {
            if (unit.battleNetAnimationTimer() > 1) {
                unit.setBattleNetAnimationTimer(
                        unit.battleNetAnimationTimer() - 1);
                return;
            }
            // Route bytes live on CUnit and survive the Patrol -> Attack pop,
            // but native moves their cursor to 20 on the timer-one handoff.
            // This is a park, not a hard path refusal: retain no high-nibble
            // refusal and let the following Move-start/1 visit lay and spend
            // the chase route immediately.
            unit.setBattleNetLandPatrolAttackConstruction(false);
            unit.setBattleNetLandPatrolAttackRoutePending(true);
            unit.clearPath();
            unit.setRouteSpent(false);
            unit.setBattleNetCollisionCounter(
                    unit.battleNetCollisionCounter() + 1);
            unit.setChasing(true);
            int moveStart = world.battleNetSequence == null ? -1
                    : world.idle.battleNetSequenceStart(unit,
                            BattleNetSequence.MOVE_ANIMATION);
            if (moveStart >= 0) {
                unit.setBattleNetSequenceOffset(moveStart);
                unit.setBattleNetAnimationTimer(1);
            }
            AnimationSet set = unit.type() == null
                    ? null : unit.type().animationSet();
            Animation move = set == null ? null
                    : set.get(AnimationSet.State.MOVE);
            if (move != null && unit.animation().current() != move) {
                unit.animation().switchTo(move);
            }
            return;
        }
        if (unit.battleNetLandPatrolAttackRoutePending()
                && unit.battleNetRefusalHold()
                && unit.pathLength() == 0
                && unit.battleNetCollisionCounter() > 0
                && unit.battleNetCollisionCounter() < 8) {
            // A one-byte route written by the Patrol -> Attack handoff can be
            // physically present while its native cursor is already parked at
            // twenty. Retail advances the collision generation on each Move-1
            // visit without re-entering active-order Still. XHuman 12 slot
            // 1457 writes E/collision one at fixture 166, reaches collision
            // eight at 173, and only then buys the complete Move-15 band.
            int collision = unit.battleNetCollisionCounter() + 1;
            unit.setBattleNetCollisionCounter(collision);
            if (collision >= 8) {
                unit.setBattleNetLandPatrolAttackRoutePending(false);
                unit.setBattleNetOrderDelay(14);
                int moveStart = world.idle.battleNetSequenceStart(unit,
                        BattleNetSequence.MOVE_ANIMATION);
                if (moveStart >= 0) {
                    unit.setBattleNetSequenceOffset(moveStart);
                    unit.setBattleNetAnimationTimer(15);
                    unit.setBattleNetChaseStepReady(false);
                }
            }
            return;
        }
        Unit completedRefusalConstructorGoal = unit.target();
        boolean dyingQuarryCompletedRefusalConstructor =
                unit.battleNetAttackRefusalRecoveryStage() == 5
                && unit.battleNetAnimationTimer() == 1
                && unit.pathLength() == 0
                && unit.stepDrained() && !unit.isMoving()
                && unit.battleNetChaseEmptyRouteReplan()
                && unit.battleNetCollisionCounter() == 0
                && unit.battleNetRefusals() == 0
                && unit.type() != null
                && unit.type().maxAttackRange() <= 1
                && completedRefusalConstructorGoal != null
                && !world.targets.validAttackTarget(
                        unit, completedRefusalConstructorGoal);
        if (stepBattleNetAttackRefusalRecovery(unit)) {
            return;
        }
        if (stepBattleNetAttackSequence(unit)) {
            return;
        }
        Unit quietSequenceGoal = unit.target();
        if (world.battleNetSequence != null
                && unit.fighting() && !unit.chasing()
                && quietSequenceGoal != null
                && !world.targets.validAttackTarget(unit, quietSequenceGoal)
                && !world.battleNetAttackMarkers.contains(unit)) {
            // The BNE script.bin cursor, not the parallel presentation
            // animation, owns the next order decision.  A dying quarry may
            // outlive the renderer's last breakable frame while Attack is
            // still draining the final wait before its tail goto.  Keep the
            // COrder_Attack and corpse pointer through those quiet visits so
            // the tail's OP0 can perform AutoSelectTarget. XHuman 10 knight
            // 1489 is the sealed witness: its visible swing finishes while
            // Attack@1945 still counts 3,2,1; native retains Attack and names
            // grunt 1477 on the wrap, whereas Java used to become Still three
            // visits early and never re-engage.
            return;
        }
        if (world.battleNetSequence != null
                && unit.battleNetAttackResumeHoldActive()
                && quietSequenceGoal != null && quietSequenceGoal.isDying()
                && unit.type() != null && unit.type().firesMissile()) {
            // The OP0 reached after a ranged chase is itself a committed
            // body hold. A retained Die pointer does not turn that marker
            // into EndActionAttack: XHuman 10 axethrower 1478 remains on its
            // stand-ground attack while Attack@887 counts 63.., even though
            // footman 1492 is already dying. Letting the ordinary target-gone
            // arm run here changed the order to Still on the marker visit,
            // paid a phantom idle draw, and reassigned every later projectile
            // and damage roll.
            return;
        }
        if (unit.battleNetOrderDelay() > 0) {
            world.movement.syncBattleNetAttackRefusalTimer(unit);
            if (unit.battleNetWrappedCollisionRetryPark()) {
                // The post-wrap replacement byte owns a literal Move 2,1
                // countdown. It is not eligible for the ordinary cooperative
                // free-wake: retail retains the byte through timer one even
                // when the blocker vacates during this short handoff.
                unit.setBattleNetOrderDelay(
                        unit.battleNetOrderDelay() - 1);
                return;
            }
            // One-heading chase cooperative soft-wait: re-check every visit.
            // XHuman 12 grunt 1503 holds SE while axe 1524 sits on E (32,38);
            // when that axe steps S at fixture 38, native route_index 20 and
            // multi-step E at 39. A blind delay-14 countdown from the SE
            // refuse expired only after another axe re-blocked E, so replan
            // found SSSS again. Wake early when a free neighbour is not
            // farther from the quarry than the current tile.
            if (unit.chasing() && unit.pathLength() == 1
                    && unit.target() != null && unit.canMove()
                    && unit.type() != null
                    && unit.type().maxAttackRange() <= 1
                    && !World.battleNetRangedChaseUnit(unit)) {
                // Free-wake is for cooperative soft-wait (coll>0) and for
                // free-compass Attack-four remainders (delay>=3, coll 0) that
                // install a free one-heading path. Melee replan residual
                // Attack-four is delay 2 with coll 0 and a free leftover:
                // free-waking that hold stepped XHuman 12 grunt 1505 S at
                // fixture 42 while native holds Attack-four through 43 and
                // steps only at 44. Soft-wait free-wake for grunt 1503 still
                // has coll>=1 when E frees; multi-step free-compass (delay 3)
                // still free-wakes so 1510/1476 keep native @41.
                // A moving quarry's one-byte refusal is different: Move owns
                // its complete timer band even if a blocker vacates during
                // the wait. Its timer-one callback is served by the sticky
                // OP0 bridge below. Human 8 attack-peasant 1520 proves both
                // outcomes there: a free stale head commits next visit,
                // while a blocked stale head immediately buys another band.
                if (unit.battleNetCollisionCounter() == 0
                        && unit.battleNetOrderDelay() < 3) {
                    unit.setBattleNetOrderDelay(
                            unit.battleNetOrderDelay() - 1);
                    return;
                }
                Unit quarry = unit.target();
                int curDist = Math.max(
                        Math.abs(quarry.tileX() - unit.tileX()),
                        Math.abs(quarry.tileY() - unit.tileY()));
                int stride = world.battleNetMovementStride(unit);
                int peek = unit.peekHeading();
                int peekX = unit.tileX() + Direction.deltaX(peek) * stride;
                int peekY = unit.tileY() + Direction.deltaY(peek) * stride;
                boolean peekFree = world.canEnter(unit, peekX, peekY);
                // A free (or vacating) neighbour that progresses toward the
                // quarry. Plain dist<= woke on free west; requiring progress
                // without regress is the SE-goal rule. A moving ally still
                // listed on the cell but mid-step off it (XHuman 12 axe 76
                // at 32,38m while draining residual S) must count as free --
                // native 1503 steps E the cycle after that axe leaves.
                boolean freeProgress = false;
                int vacateHeading = -1;
                // A vacating ally can release only the route whose quarry
                // point is still current. If the quarry moved after this
                // one-heading route was drawn, native keeps the refusal band
                // and its target ownership; borrowing another heading here
                // would turn the stale route into an uncommitted retarget.
                boolean quarryPointCurrent =
                        unit.pathGoalX() == quarry.tileX()
                        && unit.pathGoalY() == quarry.tileY();
                if (!peekFree && quarryPointCurrent) {
                    int goalDx = Integer.signum(
                            quarry.tileX() - unit.tileX());
                    int goalDy = Integer.signum(
                            quarry.tileY() - unit.tileY());
                    for (int dir = 0; dir < Direction.COUNT; dir++) {
                        int freeX = unit.tileX()
                                + Direction.deltaX(dir) * stride;
                        int freeY = unit.tileY()
                                + Direction.deltaY(dir) * stride;
                        if (!chaseCellVacating(unit, freeX, freeY)) {
                            continue;
                        }
                        int dist = Math.max(
                                Math.abs(quarry.tileX() - freeX),
                                Math.abs(quarry.tileY() - freeY));
                        if (dist > curDist) {
                            continue;
                        }
                        int stepDx = Integer.signum(freeX - unit.tileX());
                        int stepDy = Integer.signum(freeY - unit.tileY());
                        // Reject SW when the goal is SE: it advances south but
                        // regresses west and false-woke the SE soft-wait on
                        // the first refuse (XHuman 12 1503 at c26).
                        boolean noRegress = (goalDx == 0 || stepDx != -goalDx)
                                && (goalDy == 0 || stepDy != -goalDy);
                        boolean progresses = (goalDx != 0 && stepDx == goalDx)
                                || (goalDy != 0 && stepDy == goalDy);
                        if (progresses && noRegress) {
                            freeProgress = true;
                            vacateHeading = dir;
                            break;
                        }
                    }
                }
                if (quarryPointCurrent && (peekFree || freeProgress)
                        && !unit.battleNetRefusalHold()) {
                    unit.setBattleNetOrderDelay(0);
                    if (unit.battleNetMovingQuarryResidual()) {
                        // Waking the first paid band does not erase its
                        // refusal ownership. If this retained head refuses
                        // again, native increments the same generation and
                        // counts its next complete Move band even though the
                        // ally which caused the first wait has vacated.
                        unit.setBattleNetRefusalHold(true);
                    }
                    if (!peekFree && vacateHeading >= 0) {
                        // Install the progress heading so this visit can step
                        // (native 1503 E onto the cell the vacating axe left).
                        unit.clearPath();
                        unit.setPath(new PathFinder.Path(
                                PathFinder.Result.FOUND,
                                new int[] {vacateHeading}));
                        unit.setPathGoal(quarry.tileX(), quarry.tileY());
                        if (!unit.battleNetMovingQuarryResidual()) {
                            unit.setBattleNetCollisionCounter(0);
                        }
                        unit.setBattleNetChaseEmptyRouteReplan(false);
                        unit.setChasing(true);
                    } else if (!peekFree) {
                        unit.clearPath();
                        unit.setRouteSpent(false);
                        if (!unit.battleNetMovingQuarryResidual()) {
                            unit.setBattleNetCollisionCounter(0);
                        }
                        unit.setBattleNetChaseEmptyRouteReplan(true);
                    }
                    // Fall through into the ordinary chase body.
                } else {
                    unit.setBattleNetOrderDelay(
                            unit.battleNetOrderDelay() - 1);
                    return;
                }
            } else if (unit.chasing() && unit.pathLength() == 3
                    && unit.target() != null && unit.canMove()
                    && unit.type() != null
                    && unit.type().maxAttackRange() <= 1
                    && !World.battleNetRangedChaseUnit(unit)
                    && unit.battleNetCollisionCounter() > 0
                    && unit.stepDrained() && !unit.isMoving()
                    // A directly offered harvesting quarry owns the complete
                    // paid Move band even when the optimizer can see a free
                    // diagonal around its retained cardinal route. Human 8
                    // attack-peasant 1505 stores E,E,E and timer 15 on
                    // fixture 226; detouring SE on 227 both discarded two
                    // native bytes and woke fourteen visits early. The timer-
                    // one target refresh parks that stale ray on 241 and
                    // redraws the quarry's new north-east approach on 242.
                    && !(unit.offeredTarget() == unit.target()
                            && unit.target().order() == Unit.Order.HARVEST)
                    && (!unit.battleNetRetargetResidualRoutePark()
                            || world.targets.validAttackTarget(
                                    unit, unit.target()))
                    && !Direction.isDiagonal(unit.peekHeading())) {
                // Axis leftover of length 3 under soft-wait: free diagonal
                // closer toward the quarry. XHuman 10 grunt 1482 holds EEE at
                // (78,89) with free SE onto (79,88); native steps SE at
                // fixture 40. pathn==1 vacating arm never saw that free SE.
                // Broader free-closer (pathn 1-4) REG'd 1503 and h13 1482.
                Unit quarry = unit.target();
                int curDist = Math.max(
                        Math.abs(quarry.tileX() - unit.tileX()),
                        Math.abs(quarry.tileY() - unit.tileY()));
                int stride = world.battleNetMovementStride(unit);
                int peek = unit.peekHeading();
                int peekX = unit.tileX() + Direction.deltaX(peek) * stride;
                int peekY = unit.tileY() + Direction.deltaY(peek) * stride;
                if (world.canEnter(unit, peekX, peekY)
                        && !unit.battleNetRefusalHold()) {
                    unit.setBattleNetOrderDelay(0);
                    // Fall through -- planned axis is free.
                } else {
                    int freeHeading = -1;
                    int bestDist = curDist;
                    for (int dir = 0; dir < Direction.COUNT; dir++) {
                        if (!Direction.isDiagonal(dir)) {
                            continue;
                        }
                        int freeX = unit.tileX()
                                + Direction.deltaX(dir) * stride;
                        int freeY = unit.tileY()
                                + Direction.deltaY(dir) * stride;
                        if (!world.canEnter(unit, freeX, freeY)) {
                            continue;
                        }
                        int dist = Math.max(
                                Math.abs(quarry.tileX() - freeX),
                                Math.abs(quarry.tileY() - freeY));
                        if (dist >= bestDist) {
                            continue;
                        }
                        bestDist = dist;
                        freeHeading = dir;
                    }
                    // A wait refused by an ally on its last step is counted
                    // out, not cancelled the moment the square frees. XHuman 10
                    // grunt 1490 used to take 79,89 on fixture 53 with three
                    // cycles still on its wait, where native holds at 78,88 and
                    // gives that square to the grunt from 78,85. Ordinary order
                    // delays still detour, which is why the flag is tested
                    // rather than the count.
                    if (freeHeading >= 0 && !unit.battleNetRefusalHold()) {
                        unit.setBattleNetOrderDelay(0);
                        unit.clearPath();
                        unit.setPath(new PathFinder.Path(
                                PathFinder.Result.FOUND,
                                new int[] {freeHeading}));
                        unit.setPathGoal(quarry.tileX(), quarry.tileY());
                        unit.setBattleNetCollisionCounter(0);
                        unit.setBattleNetChaseEmptyRouteReplan(false);
                        unit.setChasing(true);
                        // Fall through into the ordinary chase body.
                    } else {
                        unit.setBattleNetOrderDelay(
                                unit.battleNetOrderDelay() - 1);
                        return;
                    }
                }
            } else if (World.battleNetRangedChaseUnit(unit)
                    && unit.chasing() && unit.pathLength() == 3
                    && unit.target() != null && unit.canMove()
                    && unit.stepDrained() && !unit.isMoving()
                    && unit.battleNetOrderDelay() == 1) {
                // Residual pathn-3 re-arm delay 1 (XHuman 10 axe 1478 onto
                // multi-step melee ally): when the planned NE cell frees,
                // wake this visit. A blind countdown of the re-arm spent one
                // extra quiet after free and stepped at fixture 42 while
                // native RI20@40 / NE@41. Full soft-wait (delay 14) and
                // pathn < 6 free-wake remain off -- those REG'd 1496@25.
                int stride = world.battleNetMovementStride(unit);
                int peek = unit.peekHeading();
                int peekX = unit.tileX() + Direction.deltaX(peek) * stride;
                int peekY = unit.tileY() + Direction.deltaY(peek) * stride;
                if (world.canEnter(unit, peekX, peekY)) {
                    unit.setBattleNetOrderDelay(0);
                    // Fall through into the ordinary chase body.
                } else {
                    unit.setBattleNetOrderDelay(
                            unit.battleNetOrderDelay() - 1);
                    return;
                }
            } else if (World.battleNetRangedChaseUnit(unit)
                    && unit.chasing() && unit.pathLength() >= 6
                    && unit.target() != null && unit.canMove()
                    && unit.battleNetCollisionCounter() > 0) {
                // Nearly-full ranged leftover soft-wait after a refuse: wake
                // when the planned next cell is free (XHuman 4 axe 1490 pathn
                // 6 W under delay 14; native W at fixture 40 once free).
                // pathn < 6 must not wake (1496 REG @25).
                int stride = world.battleNetMovementStride(unit);
                int peek = unit.peekHeading();
                int peekX = unit.tileX() + Direction.deltaX(peek) * stride;
                int peekY = unit.tileY() + Direction.deltaY(peek) * stride;
                if (world.canEnter(unit, peekX, peekY)) {
                    unit.setBattleNetOrderDelay(0);
                } else {
                    unit.setBattleNetOrderDelay(
                            unit.battleNetOrderDelay() - 1);
                    return;
                }
            } else {
                boolean coldChaseRefusalPark =
                        unit.battleNetOrderDelay() == 1
                        && unit.chasing()
                        && unit.stepDrained() && !unit.isMoving()
                        && unit.pathLength() > 1
                        && unit.battleNetPathStepsTaken() == 0
                        && unit.battleNetCollisionCounter() == 1
                        && unit.battleNetRefusals() == 0
                        && unit.target() != null
                        // A matching standing offer is the order provenance
                        // for this park. No offer, or an offer naming another
                        // attacker, hands the paid refusal to Attack
                        // construction instead (the held-out XHuman 10 and
                        // XHuman 12 witnesses).
                        && unit.offeredTarget() == unit.target()
                        && !World.battleNetRangedChaseUnit(unit);
                boolean coldChaseHeadFree = false;
                if (coldChaseRefusalPark) {
                    // The refusal band pays through timer one, and its cached
                    // head is probed at that boundary. If the square opened
                    // during the wait, native retains the route instead of
                    // parking it at index twenty; Move commits it on the next
                    // callback. XHuman 12 grunt 1516 retains E while 1520
                    // clears (40,39), then steps E on fixture 184. Clearing
                    // the buffer unconditionally left Java at (39,39).
                    int wakeHeading = unit.peekHeading();
                    int wakeStride = world.battleNetMovementStride(unit);
                    coldChaseHeadFree = world.canEnter(unit,
                            unit.tileX()
                                    + Direction.deltaX(wakeHeading)
                                            * wakeStride,
                            unit.tileY()
                                    + Direction.deltaY(wakeHeading)
                                            * wakeStride);
                }
                boolean paidCachedEmptyRefill =
                        unit.battleNetChaseEmptyRouteReplan()
                        && unit.stepDrained() && !unit.isMoving()
                        && unit.pathLength() == BattleNetPathFinder.MAX_PATH
                        && unit.battleNetCollisionCounter() == 2
                        && unit.battleNetRefusals() == 0
                        && unit.target() != null
                        && !World.battleNetRangedChaseUnit(unit)
                        && (unit.order() == Unit.Order.ATTACK
                                || unit.order() == Unit.Order.ATTACK_MOVE
                                || unit.chasing());
                if (paidCachedEmptyRefill) {
                    // A saturated surrogate route is not by itself evidence
                    // that native approved all twenty headings. A route
                    // which remains cached while Attack pays its cooperative
                    // timer is different: native retained only the bounded
                    // prefix through that same wait. Remember that paid
                    // provenance until the first heading actually commits,
                    // then park the synthetic Java tail after its pixels
                    // drain (XHuman 12 grunt 1520, fixtures 45..75).
                    unit.setBattleNetMoveFreeDetourPending(true);
                }
                // A collided route which has also paid a real refusal leaves
                // the native collision nibble live through its timer-one
                // wake, then clears it only if the following movement probe
                // succeeds. Remember that one-visit boundary before
                // setBattleNetOrderDelay(0) clears RefusalHold. Requiring the
                // A native hard-refusal count is one proof of a fully slept
                // wake. A settled cooperative route can also enter the same
                // fifteen-visit band with no hard refusals at all; its
                // collision nibble is already at least three when that band
                // begins (XHuman 12 grunt 1510: collision three at fixture
                // 57, timer 15..1, stale E parked at fixture 72). Remembering
                // that wake prevents a still-occupied head from buying the
                // same fifteen visits forever. An ordinary collided refill
                // remains below three and keeps its pressure through a
                // successful walk (XHuman 12 grunt 1476). XHuman 10 grunt
                // 1486 is the hard-refusal witness: nibble three at fixture
                // 52, zero after its fixture-53 SE step.
                boolean refusalWake = unit.battleNetOrderDelay() == 1
                        && unit.stepDrained() && !unit.isMoving()
                        && unit.pathLength() > 0
                        && unit.battleNetCollisionCounter() > 0
                        && (unit.battleNetRefusals() > 0
                                || unit.battleNetCollisionCounter() >= 3);
                boolean paidWakeHeadFree = coldChaseHeadFree;
                boolean saturatedCachedWake = refusalWake
                        && unit.battleNetRefusals() == 0
                        && unit.battleNetCollisionCounter() >= 4
                        && unit.battleNetPathStepsTaken() >= 3
                        && unit.pathLength()
                                + unit.battleNetPathStepsTaken()
                                == BattleNetPathFinder.MAX_PATH;
                if (saturatedCachedWake) {
                    int wakeHeading = unit.peekHeading();
                    int wakeStride = world.battleNetMovementStride(unit);
                    paidWakeHeadFree = world.canEnter(unit,
                            unit.tileX()
                                    + Direction.deltaX(wakeHeading)
                                            * wakeStride,
                            unit.tileY()
                                    + Direction.deltaY(wakeHeading)
                                            * wakeStride);
                }
                unit.setBattleNetOrderDelay(unit.battleNetOrderDelay() - 1);
                if (coldChaseRefusalPark && coldChaseHeadFree) {
                    // Timer one only completes the paid cold-refusal band.
                    // A newly free cached head remains intact, but Move does
                    // not receive its next Execute until the following unit
                    // callback: XHuman 12 slot 1516 is still on (39,39) at
                    // fixture 183 and commits E on 184. Falling through here
                    // spent the heading one fixture early.
                    return;
                }
                if (coldChaseRefusalPark && !coldChaseHeadFree) {
                    // A freshly drawn melee chase route whose opening byte
                    // refused has already paid FUN_004379e0's complete Move
                    // timer.  Timer one parks the untouched buffer at native
                    // route index 20; the following action visit draws a new
                    // route against current occupancy.  Retrying the stale
                    // head instead buys another complete refusal band and is
                    // an unbounded crowded-combat freeze.  Human 13 grunt
                    // 1507 is the sealed witness: SE,SE / timer 15..1 at
                    // fixtures 114..129, then a fresh E,SE,SW route and the E
                    // step at 130.
                    world.causalTrace.event(world.cycle,
                            "path.cold-refusal-park", unit.id(),
                            "path_length", unit.pathLength(),
                            "heading", unit.peekHeading(),
                            "collision", unit.battleNetCollisionCounter());
                    unit.clearPath();
                    unit.setRouteSpent(false);
                    unit.setWaitCycles(0);
                    // The park is its own action visit.  Refill begins on the
                    // following visit, so retain one Java scheduler beat
                    // after exposing the empty native buffer.
                    unit.setBattleNetOrderDelay(1);
                    unit.setBattleNetChaseStepReady(false);
                    return;
                }
                if (refusalWake) {
                    unit.setBattleNetRefusalHold(true);
                }
                if (!paidWakeHeadFree) {
                    return;
                }
                // A complete refusal band ends on timer one, not on a
                // separate timer-zero callback. If its cached head has become
                // free, native's timer-one visit probes and commits that byte
                // immediately. XHuman 12 slot 1494 is the saturated witness:
                // collision four, Move timer 15..1, then NE on fixture 117.
                // A still-blocked head returns above with RefusalHold armed so
                // the next visit parks/rebands it without buying another
                // unbounded quiet cycle. The ordinary successful-step path
                // below owns collision cleanup and preserves saturated
                // formation provenance.
            }
        }
        if (armBattleNetExpiredHarvestQuarryLadder(unit)) {
            return;
        }
        Unit residualConstructionGoal = unit.target();
        int residualAttackStart = world.battleNetSequence == null ? -1
                : world.idle.battleNetSequenceStart(unit,
                        BattleNetSequence.ATTACK_ANIMATION);
        if (unit.battleNetRetargetResidualRoutePark()
                && unit.chasing() && !unit.isMoving()
                && unit.battleNetOrderDelay() == 0
                && unit.pathLength() > 0
                && residualAttackStart >= 0
                && unit.battleNetSequenceOffset() == residualAttackStart
                && unit.battleNetAnimationTimer() == 1
                && (residualConstructionGoal == null
                        || !world.targets.validAttackTarget(
                                unit, residualConstructionGoal))) {
            // The residual has settled and Attack construction now owns its
            // timer-one callback. Retail validates the CUnitPtr here and
            // scans for a replacement before releasing the active order. A
            // live out-of-range replacement receives a freshly drawn route
            // and its first Move byte on this same paid callback. Human 8's
            // attack peasant therefore changes from its expired quarry and
            // steps NE without passing through Still.
            int reactRange = Math.max(
                    unit.type().reactRange(world.isPerson(unit.player())),
                    Math.max(1, unit.type().maxAttackRange()));
            Unit replacement = world.targets.findBattleNetHostile(
                    unit, reactRange, null);
            boolean chaseReplacement = replacement != null
                    && replacement != residualConstructionGoal
                    && replacement.isAlive() && !replacement.isDying()
                    && !world.targets.inAttackRange(unit, replacement);
            unit.setBattleNetRetargetResidualRoutePark(false);
            if (chaseReplacement) {
                boolean singleHeadingExpiredConstructor =
                        unit.pathLength() == 1
                        && unit.battleNetCollisionCounter() == 0
                        && unit.battleNetPathStepsTaken() == 1;
                if (singleHeadingExpiredConstructor) {
                    // The last cached heading was the whole residual
                    // generation. Its expired CUnitPtr is replaced at this
                    // timer-one callback, but the new quarry owns fresh
                    // Attack construction before another route may move.
                    // Human 8 slot 1505 exposes Attack 3,2,1 on fixtures
                    // 223..225 while the adjacent slot 1513's longer tail
                    // hands directly to Move on fixture 224.
                    unit.setBattleNetChaseStepReady(false);
                    setAutoTarget(unit, replacement);
                    unit.setFighting(false);
                    unit.setChasing(false);
                    unit.setBattleNetSequenceOffset(residualAttackStart);
                    unit.setBattleNetAnimationTimer(3);
                    unit.setOfferedTarget(replacement);
                    unit.setBattleNetAttackWrapDestArmPending(true);
                    world.turnToTarget(unit, replacement, 0, 0);
                    return;
                }
                if (!unit.battleNetChaseStepReady()
                        && unit.battleNetCollisionCounter() > 0) {
                    // A successful replacement scan makes timer one a visible
                    // completed-construction state. Its route handoff belongs
                    // to the following OP0 callback when the old generation
                    // still owns collision provenance. An uncollided retained
                    // tail may scan and move on this callback; Human 8 slot
                    // 1513 is that scheduler-order counter-boundary.
                    unit.setBattleNetRetargetResidualRoutePark(true);
                    unit.setBattleNetChaseStepReady(true);
                    return;
                }
                unit.setBattleNetChaseStepReady(false);
                setAutoTarget(unit, replacement);
                unit.setFighting(false);
                unit.setChasing(true);
                unit.setRouteSpent(false);
                unit.setBattleNetChaseEmptyRouteReplan(false);
                world.movement.moveTowards(unit, replacement);
                int moveStart = world.idle.battleNetSequenceStart(unit,
                        BattleNetSequence.MOVE_ANIMATION);
                if (moveStart >= 0 && unit.pathLength() > 0) {
                    unit.setBattleNetSequenceOffset(moveStart);
                    unit.setBattleNetAnimationTimer(1);
                    unit.setBattleNetChaseStepReady(false);
                    unit.animation().clearCurrent();
                    stepMoveTowardsTarget(unit);
                    return;
                }
            }
            // No replacement keeps the previously sealed release boundary:
            // XHuman 9 footman 1420 finds no live successor to skeleton 1430,
            // clears the retained route and opens Still on fixture 132.
            finishStationaryAttackToStill(unit);
            return;
        }
        Unit parkedWeakGoal = unit.target();
        int parkedMoveStart = world.battleNetSequence == null ? -1
                : world.idle.battleNetSequenceStart(
                        unit, BattleNetSequence.MOVE_ANIMATION);
        if (parkedWeakGoal != null
                && !world.targets.validAttackTarget(unit, parkedWeakGoal)
                && unit.chasing() && !unit.isMoving()
                && unit.pathLength() == 0 && unit.stepDrained()
                && unit.battleNetAttackWrapDestArmPending()
                && unit.battleNetCollisionCounter() > 0
                && unit.offeredTarget() == parkedWeakGoal
                && parkedMoveStart >= 0
                && unit.battleNetSequenceOffset() == parkedMoveStart
                && unit.battleNetAnimationTimer() == 1) {
            // A paid refusal can leave a weak offered quarry behind native
            // route index twenty. On the next Move-start callback the order
            // validates that CUnitPtr before granting another probe. Human 13
            // ogre 1482 therefore releases removed knight 1493 into Still on
            // fixture 216 instead of drawing and spending a route to its old
            // square.
            finishStationaryAttackToStill(unit);
            return;
        }
        installBattleNetPaidRecoveryDirectRefill(unit);
        if (armBattleNetBlockedChaseAttackConstruction(unit)) {
            return;
        }
        // Deferred action-16 drop: the last recovery tick (timer==1) arms
        // the hold and finishes on the following visit. Finishing on the
        // timer==1 visit itself is one fixture cycle early (XHuman 2 footman
        // 1548: 39 vs native 40); holding without a deferred finish never
        // re-enters the out-of-range arm after the bytecode advances.
        if (unit.battleNetStationaryRecoveryHeld()
                && unit.battleNetStationaryAttack()) {
            finishStationaryAttackToStill(unit);
            return;
        }
        // Free-scan retarget on the first cold path after the order-delay
        // window while a live offer remains (native 0x409ff0 null seed).
        // Dest-arm leftover is planned to the acquired quarry first. Human 13
        // knight 1490 dest-arms SE around the ogre on axe 1486's column, then
        // the scan names that ogre; retargeting first dest-armed due south
        // onto 124,31. Knight 1500 still free-scans onto ogre 120,24 after
        // the NW leftover toward axe 118,24 is written, so equal-cost face
        // preference keeps 119,25.
        if (!unit.chasing() && !unit.fighting() && unit.pathLength() == 0
                && unit.target() != null && !unit.battleNetStationaryAttack()
                && unit.offeredTarget() != null
                && (unit.type().maxAttackRange() <= 1
                        || unit.battleNetAttackWrapDestArmPending())) {
            Unit acquired = unit.target();
            if (unit.canMove()
                    && !world.targets.inAttackRange(unit, acquired)) {
                boolean wrapDestArm = unit.battleNetAttackWrapDestArmPending();
                boolean deadResidualDestArm = wrapDestArm
                        && unit.battleNetPendingMeleeSyncRand();
                int priorGoalX = unit.pathGoalX();
                int priorGoalY = unit.pathGoalY();
                if (deadResidualDestArm
                        && priorGoalX >= 0 && priorGoalY >= 0) {
                    clearDyingMovementFieldAt(priorGoalX, priorGoalY);
                }
                boolean priorGoalEnterable = priorGoalX >= 0
                        && priorGoalY >= 0
                        && world.canEnter(unit, priorGoalX, priorGoalY);
                world.movement.moveTowards(unit, acquired);
                if (deadResidualDestArm) {
                    keepDeadQuarryDestArmHeading(unit, acquired,
                            priorGoalX, priorGoalY, priorGoalEnterable);
                    clearDyingMovementFieldAt(priorGoalX, priorGoalY);
                }
                if (wrapDestArm && unit.pathLength() > 0) {
                    // This is not a cold Move order. Attack's completed loop
                    // has already paid the action visit that asks for the
                    // replacement route, and retail hands the first leftover
                    // straight to DoActionMove on that same visit. Keeping
                    // Java's finished Attack presentation installed makes
                    // stepMove's animation gate defer the logical SW step one
                    // fixture (Human 13 ogre 1511: native 119,27 at 118).
                    unit.animation().clearCurrent();
                }
                // Keep the wrap marker across the leftover itself.  Retail
                // already paid Attack's construction 3,2,1 before asking
                // for this route; when the leftover lands in range it
                // enters the attack body immediately.  Clearing the marker
                // here made the generic Move->Attack seam charge the same
                // construction a second time (Human 13 ogre 1511: native
                // Attack@644 on fixture 130, Java stayed on 643/3,2,1).
                if (!wrapDestArm || unit.pathLength() == 0) {
                    unit.setBattleNetAttackWrapDestArmPending(false);
                }
            }
            Unit offered = unit.offeredTarget();
            if (!offered.isAlive() || offered.isDying() || !offered.isOnMap()) {
                unit.setOfferedTarget(null);
            } else {
                int reactRange = Math.max(
                        unit.type().reactRange(world.isPerson(unit.player())),
                        Math.max(1, unit.type().maxAttackRange()));
                // AutoSelectTarget prices the banked hit offer before walking
                // the reaction band. The settled-route pass below does the
                // same; this follow-up dest-arm pass must not null-seed and
                // immediately undo an equal-score offer on the next visit.
                // XHuman 4 grunt 1489 retained footman 1495 at fixture 73,
                // while the null seed switched north to 1518 on fixture 74.
                Unit candidate = world.targets.findBattleNetHostile(
                        unit, reactRange, offered);
                if (candidate != null && candidate != unit.target()) {
                    boolean keepLeftover = unit.pathLength() > 0;
                    setAutoTarget(unit, candidate, keepLeftover);
                    if (keepLeftover) {
                        // The leftover belongs to the acquired quarry. Name
                        // the new goal so the later chase does not treat the
                        // still-valid prefix as stale and dest-arm again.
                        unit.setPathGoal(candidate.tileX(), candidate.tileY());
                        world.refineBattleNetDestArmLeftover(unit, candidate);
                    }
                }
            }
        }
        // BNE idle auto-acquire is raw action 16 (stationary combat via
        // 0x453130), not chase mode 12: flag bit 0x0004 is clear, so out of
        // weapon range queues Still without world.movement. Human 9's destroyers
        // pick the balloon at 32,42 from 26,38 (outside weapon range 4),
        // hold Attack for the order-delay window, then drop back to Still
        // without a tile step. XHuman 12 archer 1450 acquires the footman
        // at 24,60 in range and fires without leaving 28,59. Re-arming the
        // Still constructor with timer 3 matches native's post-16 gap.
        if (unit.battleNetStationaryAttack() && !unit.chasing() && !unit.fighting()
                && unit.target() != null && !world.targets.inAttackRange(unit, unit.target())) {
            // No recovery hold here: never-swung action 16 (Human 9 destroyers
            // vs balloon) must drop after the order-delay window alone.
            finishStationaryAttackToStill(unit);
            return;
        }
        // A swing that has begun finishes. Upstream's AttackTarget animates
        // and then returns while Anim.Unbreakable is set: no re-target, no
        // dropping into a chase, not even a turn. Without this the implementation threw
        // a blow away whenever its target stepped out of reach mid-swing,
        // which is what made kiting free here and not in Warcraft II.
        //
        // Only for a swing, though. Every Move animation in the game is also
        // wrapped in "unbreakable begin" / "unbreakable end" -- a footman's is
        // sixteen cycles long -- so testing the flag alone made an attacking
        // unit skip its entire walk and take one step of the twelve its
        // animation asks for per loop. It crawled towards the enemy at about a
        // twelfth speed with its legs going the whole time, which is the
        // running on the spot and the slow motion in the same bug.
        //
        // Upstream's own order settles it: MoveToTarget runs DoActionMove
        // first, unconditionally, and only then returns on Unbreakable. The
        // flag stops a unit re-deciding mid-animation; it never stops it
        // moving.
        //
        // Animated first and asked afterwards, which is the order
        // {@code AttackTarget} does it in: {@code AnimateActionAttack(unit,
        // *this)} and then {@code if (unit.Anim.Unbreakable || this->Finished)
        // return}. On the cycle a
        // swing ends -- the {@code wait 1} after {@code unbreakable end} --
        // that means the animation advances *and* the order decides, in the
        // same cycle. Skipping the animation on that cycle and deciding
        // anyway puts everything the decision leads to one cycle out.
        // Gated on the state and not on the animation alone. Upstream's swing
        // arm is {@code AttackTarget}, which {@code Execute} reaches only in
        // the ATTACK_TARGET state; a unit that has gone back to MOVE_TO_TARGET
        // is animating its walk, not its swing, however recently it struck.
        // Without this a unit that ended a swing and set off after its target
        // never switched animation -- the attack one was still current, so it
        // read as swinging, and it stood there swinging at nothing.
        boolean swung = false;
        // script.bin, not the parallel presentation animation, owns order
        // validity at an Attack marker. When the native cursor reaches that
        // boundary it checks the goal even if the renderer still carries its
        // Unbreakable bit. Letting the visual swing return first pins an
        // attacker to a corpse for an extra visit (or much longer with a
        // mismatched visual script). XHuman 9 footman 1423 is the compact
        // witness: its skeleton is already dying, and native changes Attack
        // to Still on fixture 125 while the Java animation is still closing.
        Unit sequenceBoundaryGoal = unit.target();
        boolean battleNetDeadGoalBoundary = world.battleNetSequence != null
                && world.battleNetAttackMarkers.contains(unit)
                && ((sequenceBoundaryGoal != null
                                && !world.targets.validAttackTarget(
                                        unit, sequenceBoundaryGoal))
                        || (sequenceBoundaryGoal == null && unit.fighting()));
        if (!battleNetDeadGoalBoundary
                && !unit.chasing() && unit.fighting()) {
            // ATTACK_TARGET animates before it validates or scans. This is
            // observable on the first cycle after a chase reaches its goal:
            // the previous animation is still Move, but UnitShowAnimation
            // switches it to Attack and normally raises Unbreakable before
            // AutoSelectTarget can run. Treating "currently drawing Attack"
            // as the state scanned once here and once on the PF_REACHED cycle,
            // two calls upstream never makes (level11o, archer 36 @ 656-657).
            if (world.isSwinging(unit)) {
                world.finishSwing(unit);
            } else {
                world.strike(unit, unit.target());
            }
            if (unit.animation().unbreakable()) {
                return;
            }
            swung = true;
        } else if (!battleNetDeadGoalBoundary
                && !unit.chasing() && unit.animation().unbreakable()
                && world.isSwinging(unit)) {
            // Compatibility for an order restored from state that predates
            // the explicit ATTACK_TARGET surrogate above: a committed swing
            // is still owed even if fighting was not serialized with it. The
            // unbreakable gate is essential: a fresh order can inherit the
            // previous order's attack animation at its breakable index-zero
            // boundary. That is FIRST_ENTRY, not a committed swing, and it
            // must run AutoSelectTarget before beginning the new animation
            // (level11o, archer 36 at cycle 1151).
            world.finishSwing(unit);
            if (unit.animation().unbreakable()) {
                return;
            }
            swung = true;
        }

        // A unit already walking to its target steps before it reconsiders
        // anything. That is upstream's MOVE_TO_TARGET state:
        // {@code COrder_Attack::Execute} sends it to {@code MoveToTarget},
        // which runs {@code DoActionMove} and only then returns on
        // {@code Anim.Unbreakable} -- so the goal is never re-examined while
        // the step is in the air. This implementation
        // examined first and stepped afterwards, which is a cycle late on
        // every chase: on demo02 upstream's juggernaught is home at 4,18 on
        // cycle 28 and this implementation's arrived on 29.
        //
        // Gated on the state rather than on the flag alone. Returning on
        // Anim.Unbreakable whatever the unit was doing holds the order too,
        // but it also pins a unit whose patrol was interrupted, which is what
        // the earlier attempt at this did.
        //
        // Once, though. Upstream runs DoActionMove exactly once per Execute
        // and everything below it decides rather than walks -- the "waiting or
        // on the way" arm returns without touching the unit's position at all
        // This implementation stepped here and then stepped
        // again at the range check, which is invisible on eleven cycles out of
        // twelve because the second call is inside an unbreakable move
        // animation and does nothing. On the twelfth, the cycle the step
        // lands, the animation has just ended and the second call starts the
        // next step: a chaser crossed a square in eleven cycles where its own
        // animation says twelve. On maps/demo/demo03 that is cycle 13, where a
        // knight, a gryphon-rider and a dragon are each one square ahead of
        // upstream's.
        boolean walked = false;
        Unit chaseTargetBeforeWalk = unit.target();
        // A moving resource worker can remain tile-adjacent while its pixel
        // anchor is still more than one square ahead. Native keeps the
        // pursuer's current residual under Attack ownership until it is fully
        // paid. Human 8's siege peasant follows the harvesting peasant this
        // way from fixture 5 through 117 instead of freezing at fixture 17.
        if (world.battleNetSequence != null
                && unit.chasing() && unit.isMoving() && unit.pathLength() == 1
                && unit.target() != null && unit.target().isMoving()
                && unit.target().order() == Unit.Order.HARVEST) {
            unit.setBattleNetMovingQuarryResidual(true);
        }
        // Only a unit that can walk has a walk to be part-way through.
        // COrder_Attack::MoveToTarget opens with Assert(unit.CanMove()) and is
        // only ever reached from the MOVE_TO_TARGET state, which a static
        // defender never enters -- it uses AttackTarget and AutoAttackStand.
        // Without this a tower was treated as "on its way" and never reached
        // the swing: on AttackMoveTest's fixture it took forty-three cycles
        // longer to land its first shot.
        // Leftover cached headings after an earlier approach must not resume
        // once the unit is already inside weapon range, whether or not the
        // MOVE_TO_TARGET surrogate is still set. Human 13's wise-man held
        // 122,29 through fixture c23 and only stepped south at c24; a stale
        // south heading under ATTACK took Java there at c21.
        if (unit.canMove() && unit.target() != null && !unit.isMoving()
                && !world.movement.isStepping(unit) && unit.pathLength() > 0
                && world.targets.inAttackRange(unit, unit.target())) {
            // A single leftover heading that residual-landed in range stays
            // through Attack start 3,2,1. Human 13 grunt 1485 keeps
            // route_index 1 beside the wise-man through fixture 43 and only
            // spends it at OP0 (index 20) at 44. Multi leftover (ogre 1484
            // pathn 5) still residual-opens past OP0 and spends immediately.
            int attackStart = world.battleNetSequence == null
                    || world.idle == null
                    ? -1
                    : world.idle.battleNetSequenceStart(unit,
                            BattleNetSequence.ATTACK_ANIMATION);
            boolean leftoverConstruction = unit.pathLength() == 1
                    && attackStart >= 0
                    && unit.battleNetSequenceOffset() == attackStart
                    && unit.battleNetAnimationTimer() > 0
                    && unit.chasing();
            if (!leftoverConstruction) {
                // Multi-step leftover discard once residual has already settled:
                // Human 13 ogre 1510 keeps two unused pure-south headings when the
                // knight free-scans into range. Native opens Attack at post-OP0
                // 644/1 (fixture 30); cold 643/3 delayed OP10 to fixture 40.
                // pathLength >= 2 avoids single-heading mid-fight discards.
                boolean multiLeftoverDiscard = unit.pathLength() >= 2
                        && onBattleNetChaseMoveBody(unit)
                        && unit.type() != null
                        && unit.type().maxAttackRange() <= 1;
                unit.clearPath();
                unit.setRouteSpent(false);
                unit.setChasing(false);
                unit.setFighting(true);
                armBattleNetRangedAttackCadence(unit);
                if (multiLeftoverDiscard) {
                    world.openBattleNetAttackAfterChaseResidual(unit);
                }
            }
        }
        if (unit.chasing() && unit.canMove()) {
            // The route is refreshed before the step and not after it.
            // Upstream reaches UpdatePathFinderData from inside
            // NextPathElement, so SetGoal -- and the isRecalculatePathNeeded
            // it raises when the goal tile has moved -- happens as part of
            // DoActionMove rather than in the order body. Leaving it in the
            // body meant a chaser that returns on "waiting or on the way"
            // never re-aims at all and walks to where its quarry used to be.
            //
            // Drain the old Move element first when this unit is mid-leg.
            // The cold-commit walk model spends the old square before the
            // gate; the chase-boundary consult is that gate's retarget arm.
            // Asking it with offsets still owed skipped the equal-score
            // knight→wise-man rewrite on Human 13 ogre 1482 and kept the
            // leftover N, while native wiped and first-stepped NW. The flag
            // tells stepMove the drain already ran so it does not walk twice.
            world.actionMoveWalked = false;
            world.actionSettledMeleeReplacementRoute = false;
            world.actionSettledMeleeReplacementBroadRoute = false;
            world.actionSettledMeleeReplacementAfterPaidBand = false;
            int pathLengthBeforeChaseWalk = unit.pathLength();
            boolean routeSpentBeforeChaseWalk = unit.routeSpent();
            try {
            // Drain before the consult for every mid-leg chaser, including
            // ones on the OP0 Move body. armBattleNetChaseMoveBody puts the
            // unit on that body after the first chase step, and skipping the
            // drain there left isMoving set so the equal-score rewrite never
            // ran (Human 13 ogre 1482 kept N toward the knight). Only while
            // pixels are still owed: walking a settled unit advances Move off
            // its boundary and blocked the wise-man's post-hold step
            // (Human 13 1496 held forever past fixture 24).
            if (world.movement.onMoveAnimation(unit) && unit.isMoving()) {
                world.movement.walkPixels(unit);
                world.actionMoveWalked = true;
                if (openBattleNetSaturatedCardinalRouteTerminator(
                        unit, true)) {
                    return;
                }
                if (openBattleNetRetainedDyingRangedConstruction(unit)) {
                    return;
                }
                Unit settledQuarry = unit.target();
                Unit settledDyingTailReplacement = null;
                if (!unit.isMoving()
                        && settledQuarry != null && settledQuarry.isDying()
                        && unit.type() != null
                        && unit.type().maxAttackRange() <= 1) {
                    int reactRange = Math.max(
                            unit.type().reactRange(
                                    world.isPerson(unit.player())),
                            Math.max(1, unit.type().maxAttackRange()));
                    settledDyingTailReplacement =
                            world.targets.findBattleNetHostile(
                                    unit, reactRange, null);
                }
                boolean paidRefillTailSettledOnDyingMelee = !unit.isMoving()
                        && unit.pathLength() == 4
                        && unit.battleNetPathStepsTaken() == 1
                        && unit.battleNetAttackWrapDestArmPending()
                        && unit.type() != null
                        && unit.type().maxAttackRange() <= 1
                        && settledQuarry != null
                        && settledQuarry.isDying();
                boolean halfPaidTailSettledOnDyingMelee = !unit.isMoving()
                        && unit.pathLength() == 2
                        && unit.battleNetPathInitialLength() == 4
                        && unit.battleNetPathStepsTaken() == 2
                        && unit.battleNetCollisionCounter() == 0
                        && unit.battleNetRefusals() == 0
                        && unit.battleNetAttackWrapDestArmPending()
                        && !unit.battleNetChaseReplanResidualHold()
                        && settledDyingTailReplacement == null
                        && unit.type() != null
                        && unit.type().maxAttackRange() <= 1
                        && settledQuarry != null
                        && settledQuarry.isDying();
                boolean matureWallTailSettledOnDyingMelee = !unit.isMoving()
                        && unit.pathLength() == 1
                        && unit.battleNetPathStepsTaken() == 4
                        && unit.battleNetCollisionCounter() == 3
                        && unit.battleNetRefusals() == 1
                        && unit.type() != null
                        && unit.type().maxAttackRange() <= 1
                        && settledQuarry != null
                        && settledQuarry.isDying();
                if (paidRefillTailSettledOnDyingMelee
                        || halfPaidTailSettledOnDyingMelee
                        || matureWallTailSettledOnDyingMelee) {
                    // MoveToTarget's four-byte paid tail has completed its
                    // first committed stride before it asks whether CUnitPtr
                    // still names a valid quarry. That retained generation
                    // does not grant another cached heading on this visit:
                    // EndActionAttack installs Still but leaves
                    // PathFinderOutput on CUnit. Human 13 ogre 1510 reaches
                    // the centre of 124,31 at fixture 224, retains the four
                    // route bytes and exposes Still 581/3. The narrower
                    // length/step signature excludes ogre 1511's shorter
                    // retarget tail at fixture 142. Ogre 1501 owns the other
                    // authenticated form: four paid headings are already
                    // spent and its last cached west byte remains when the
                    // southwest pixel residual settles at fixture 226. Its
                    // collision/refusal provenance prevents a fresh NewPath
                    // toward the same dying knight on that visit. Grunt 1507
                    // is the midpoint form: two clean SE headings from its
                    // four-byte tail are spent when the second residual
                    // settles at fixture 229 with no live successor in its
                    // reaction band; native keeps S,SW while ending Attack
                    // instead of consuming S. A live successor keeps this
                    // midpoint in the retarget path (the ogre 1511 contrast).
                    world.finishAttackOrderPreservingPath(unit);
                    return;
                }
            }
            boolean saturatedWallFacePairSettle =
                    world.actionMoveWalked && !unit.isMoving()
                    && unit.pathLength() == 1
                    && unit.battleNetPathStepsTaken() == 1
                    && unit.battleNetSaturatedWallFacePairHeading()
                            == unit.peekHeading()
                    && !unit.battleNetSaturatedWallFacePairParked()
                    && unit.battleNetCollisionCounter() >= 5
                    && unit.battleNetRefusals() >= 2;
            if (saturatedWallFacePairSettle) {
                int retainedHeading = unit.peekHeading();
                int collision = unit.battleNetCollisionCounter() + 1;
                unit.setBattleNetCollisionCounter(
                        collision > 14 ? 0 : collision);
                unit.clearPath();
                unit.setRouteSpent(false);
                unit.setWaitCycles(0);
                unit.setBattleNetOrderDelay(0);
                unit.setBattleNetSaturatedWallFacePairParked(true);
                world.causalTrace.event(world.cycle,
                        "path.saturated-wall-face-pair-park", unit.id(),
                        "heading", retainedHeading,
                        "collision", unit.battleNetCollisionCounter(),
                        "refusals", unit.battleNetRefusals());
                int moveStart = world.idle.battleNetSequenceStart(unit,
                        BattleNetSequence.MOVE_ANIMATION);
                if (moveStart >= 0) {
                    unit.setBattleNetSequenceOffset(moveStart);
                    unit.setBattleNetAnimationTimer(1);
                    unit.setBattleNetChaseStepReady(false);
                }
                return;
            }
            // After residual drain, a unit that has just settled in range can
            // pay table-0x27 this visit. Human 13 wise-man 1496 and grunt
            // 1507 both settle into Attack and debit at fixture 36; waiting
            // for the next top-of-stepAttack left Java one SyncRand short.
            // A footman's diagonal route-owned handoff is the narrower
            // exception and must be tested before the generic debit. XHuman 4
            // footman 1510 has already retained its grunt target by this
            // point, so waiting for a later retarget-only branch can never
            // recover the native no-draw result at fixture 82. An ordinary
            // cardinal exhaustion pays on settlement (XHuman 9 footman 1423,
            // fixture 75), as does an exhausted-route replan (XHuman 10
            // footman 1529, fixture 211).
            if (world.actionMoveWalked && !unit.isMoving()
                    && unit.pathLength() == 0
                    && unit.target() == chaseTargetBeforeWalk
                    && Direction.isDiagonal(unit.lastStepHeading())
                    && deferBattleNetFootmanRetainedRouteSyncRand(unit)) {
                return;
            }
            world.consumeBattleNetPendingMeleeSyncRand(unit);
            if (world.actionMoveWalked && !unit.isMoving()
                    && unit.pathLength() == 0
                    && unit.battleNetSpatialHitHelpHandoff()
                    && world.isPerson(unit.player())
                    && unit.type() != null
                    && unit.type().moveType() == UnitType.Movement.LAND
                    && unit.target() != null && unit.target().isAlive()
                    && !world.targets.inAttackRange(unit, unit.target())) {
                // Person land units pulled into a naval HitUnit response own
                // only the offered route. When its final residual settles,
                // native returns the Move program to OP0 and destroys the
                // temporary Attack instead of continually replanning toward
                // a distant ship. XOrc 11 axethrower 1507 stores one east
                // byte, drains it through fixture 154, and is Still on 155.
                world.finishAttackOrder(unit);
                int stillStart = world.idle.battleNetStillSequenceStart(unit);
                if (stillStart >= 0) {
                    unit.setBattleNetSequenceOffset(stillStart);
                    unit.setBattleNetAnimationTimer(1);
                }
                // This returns through retail's active-order Still dispatcher,
                // which performs its random-facing callback on the same visit
                // while leaving the freshly installed Still cursor at OP0.
                // Its shared asynchronous draw also owns every later damage
                // and projectile constructor ordinal.
                world.idle.advanceBattleNetActiveOrderIdleRandom(unit);
                AnimationSet set = unit.type().animationSet();
                Animation still = set == null ? null
                        : set.getOrStill(AnimationSet.State.STILL);
                if (still != null && unit.animation().current() != still) {
                    unit.animation().switchTo(still);
                }
                return;
            }
            // Attack animation four, timer 3, when Move residual settles mid-
            // chase before the next heading:
            //   - type 50 wise-man always (Human 13 1496 hold c21-23);
            //   - any chaser whose live route was torn up by a retarget and
            //     whose first new-path residual is now settling (Human 13
            //     ogre 1482: replan knight→wise-man, first NW onto 124,32,
            //     residual drain, anim 3→4 timer 3 at fixture 31, SW at 34).
            // Free-approach continuous multi-step paths never set the replan
            // flag (ogre 1511 steps every residual without Attack-four).
            // Melee replan residual: order delay 2 after settle matches native
            // three-cycle Attack-four hold (c31-33) then step at 34 without
            // running attack-sequence markers that would debit SyncRand while
            // still out of weapon range (Java knight 1490 dropped 50 HP at
            // c31 when the sequence was armed). Gate on residual settle of a
            // flagged replan (world.actionMoveWalked && !isMoving), not
            // atMoveBoundary: the first new-path residual usually lands
            // mid-Move script, and requiring wrap index 0 dropped the hold
            // (and the focused regression). Type 50 still arms Attack-four
            // on the sequence at its own wrap boundary (wise-man 1496).
            // A one-heading replacement has no leftover path by settlement,
            // but the spent-route bit still owns the queued Attack promotion:
            // XHuman 4 grunt 1505 carries next_order 12 while its lone NW
            // drains on fixtures 70-85, then promotes it into Attack 3,2,1
            // on 86-88 before replanning and first-stepping SW on 89.
            boolean replanResidualHold = unit.battleNetChaseReplanResidualHold()
                    && (unit.pathLength() > 0 || unit.routeSpent());
            boolean spentSingleReplanConstruction = replanResidualHold
                    && unit.pathLength() == 0 && unit.routeSpent()
                    && unit.battleNetPathStepsTaken() == 1
                    && world.battleNetSequence != null;
            boolean paidTailWrapConstruction = replanResidualHold
                    && unit.battleNetAttackWrapDestArmPending()
                    && unit.battleNetAiBehavior() == 1
                    && unit.battleNetPathStepsTaken() == 1
                    && unit.pathLength() == 3
                    && unit.battleNetCollisionCounter() == 1
                    && world.battleNetSequence != null;
            boolean expiredHarvestReplacementConstruction =
                    replanResidualHold
                    && unit.battleNetAttackWrapDestArmPending()
                    && unit.battleNetPathStepsTaken() == 1
                    && unit.pathLength() == 1
                    && unit.battleNetCollisionCounter() == 0
                    && unit.battleNetRefusals() == 0
                    && unit.type() != null
                    && unit.type().maxAttackRange() <= 1
                    && unit.target() != null
                    && unit.target().type() != null
                    && unit.target().type().building()
                    && world.battleNetSequence != null;
            boolean navalReplanConstruction = replanResidualHold
                    && unit.type() != null
                    && unit.type().moveType() == UnitType.Movement.NAVAL;
            boolean retainedReplanConstruction = replanResidualHold
                    && (saturatedRetainedRouteFace(unit)
                            || retainedBuildingRetargetReplay(unit)
                            || (unit.battleNetAttackWrapDestArmPending()
                                    && (unit.pathLength() == 4
                                            || unit.pathLength()
                                                    == BattleNetPathFinder
                                                            .MAX_PATH - 1)))
                    && unit.battleNetPathStepsTaken() == 1
                    && unit.type() != null
                    && unit.type().maxAttackRange() <= 1
                    && !World.battleNetRangedChaseUnit(unit)
                    && world.battleNetSequence != null;
            // Empty-route free-detour residual: path is only the free heading
            // (or already spent), so ordinary replan residual hold (pathn>0)
            // never arms. Hold delay 2 when that free first step residual
            // settles; empty-route replan then same-cycle steps (XHuman 12
            // grunt 1507 E@55 not @52).
            boolean freeDetourResidualHold =
                    unit.battleNetEmptyRouteFreeDetourHold();
            boolean movingQuarryFirstRouteResidualConstruction =
                    world.actionMoveWalked && !unit.isMoving()
                    && unit.chasing()
                    && unit.pathLength() > 0
                    && unit.battleNetPathInitialLength() > 1
                    && unit.battleNetPathStepsTaken() == 1
                    && (unit.battleNetCollisionCounter() == 1
                            || (replanResidualHold
                                    && unit
                                            .battleNetAttackWrapDestArmPending()))
                    && unit.battleNetRefusals() == 0
                    && unit.type() != null
                    && unit.type().maxAttackRange() <= 1
                    && unit.target() != null
                    && unit.target().isMoving()
                    && unit.target().order() == Unit.Order.HARVEST
                    && world.battleNetMoveAnimation(unit);
            boolean inRangeReplanSettle = false;
            if (world.actionMoveWalked && !unit.isMoving()
                    && (replanResidualHold || freeDetourResidualHold
                            || movingQuarryFirstRouteResidualConstruction)
                    && world.battleNetMoveAnimation(unit)) {
                Unit quarry = unit.target();
                boolean inRange = quarry != null && quarry.isAlive()
                        && world.targets.inAttackRange(unit, quarry);
                // Attack-four delay 2 is only for a chaser that still needs
                // the next heading (Human 13 ogre 1482: settle 31, SW at 34).
                // A leftover residual that already stands in weapon range
                // opens Attack start 3 that visit -- Human 13 grunt 1485
                // residual-lands beside the wise-man at fixture 41 on 2539/3
                // and first chips at 54. Delay 2 used to burn the settle
                // visit, open 2539 at timer 2, then re-arm 3,2,1 and land
                // that blow at 57. Ordinary chase leftover (Orc 1 1592)
                // still residual-opens past OP0.
                // Tile range does not settle a route against a quarry that
                // still owns a movement residual. Human 8's harvesting
                // peasant is at -7,+7 here; native serves the two-cycle hold
                // before the attack peasant resumes its approach.
                boolean movingQuarry = quarry != null && quarry.isMoving();
                if (!inRange || movingQuarry) {
                    if (System.getenv("CHONKCRAFT_TRACE_BNE_RESIDUAL") != null) {
                        String resEnv = System.getenv("CHONKCRAFT_TRACE_BNE_RESIDUAL")
                                .trim();
                        if ("*".equals(resEnv)
                                || unit.id() == Integer.parseInt(resEnv)) {
                            System.err.printf(
                                    "JBNEREPLANHOLD cycle=%d unit=%d at=%d,%d "
                                            + "pathn=%d freeDetour=%d arm-delay=2%n",
                                    world.cycle, unit.id(),
                                    unit.tileX(), unit.tileY(),
                                    unit.pathLength(),
                                    freeDetourResidualHold ? 1 : 0);
                        }
                    }
                    if (!retainedReplanConstruction) {
                        unit.setBattleNetChaseReplanResidualHold(false);
                    }
                    if (replanResidualHold) {
                        // The hold ends the first pixel leg of a route laid by
                        // a melee retarget. Retail keeps that provenance for
                        // the following Move decision: if the cached next
                        // square refuses, its route cursor is parked before a
                        // replacement route receives the cooperative wait.
                        // XHuman 12 grunt 1492 settles at fixture 38, pays the
                        // Attack-four tail, parks the stale E route at 41, and
                        // only then plans the blocked SW detour at 42.
                        unit.setBattleNetRetargetResidualRoutePark(true);
                    }
                    if (replanResidualHold
                            && unit.type() != null
                            && "unit-grunt".equals(unit.type().ident())
                            && unit.battleNetCollisionCounter() == 0
                            && unit.battleNetRefusals() == 0
                            && unit.battleNetPathStepsTaken() == 1
                            && unit.pathLength() > 0
                            && Direction.isDiagonal(unit.lastStepHeading())
                            && Direction.isDiagonal(unit.peekHeading())
                            && Direction.deltaY(unit.lastStepHeading())
                                    == Direction.deltaY(unit.peekHeading())
                            && Direction.deltaX(unit.lastStepHeading())
                                    == -Direction.deltaX(unit.peekHeading())) {
                        int repeatX = unit.tileX() + Direction.deltaX(
                                unit.lastStepHeading())
                                * world.battleNetMovementStride(unit);
                        int repeatY = unit.tileY() + Direction.deltaY(
                                unit.lastStepHeading())
                                * world.battleNetMovementStride(unit);
                        if (world.canEnter(unit, repeatX, repeatY)) {
                            // The replacement buffer keeps its first diagonal
                            // across Attack-four. Java's fresh wall face can
                            // contain the mirrored diagonal instead, producing
                            // an unnatural NE/NW zigzag in a packed line.
                            unit.replacePeekHeading(unit.lastStepHeading());
                        }
                    }
                    unit.setBattleNetEmptyRouteFreeDetourHold(false);
                    if (movingQuarryFirstRouteResidualConstruction
                            && !replanResidualHold
                            && !freeDetourResidualHold) {
                        // Each freshly committed route leg toward a harvesting
                        // quarry returns through Attack construction before the
                        // cached tail may advance. The constructor is periodic,
                        // not only provenance from the target switch which laid
                        // the route, and it outlives the collision generation:
                        // Human 8 attack-peasant 1520 takes NE on fixture 214,
                        // settles it on 230, retains E,E,NE, and exposes Attack
                        // 2657/3,2,1 before E on 233. Its later uncollided
                        // south-east leg settles on 304 and owns the same
                        // constructor before the fixture-307 replacement scan.
                        world.causalTrace.event(world.cycle,
                                "attack.moving-quarry-route-leg-construction",
                                unit.id(), "target", quarry.id(),
                                "path_length", unit.pathLength(),
                                "path_steps",
                                        unit.battleNetPathStepsTaken(),
                                "collision",
                                        unit.battleNetCollisionCounter());
                        unit.setBattleNetRetargetResidualRoutePark(true);
                    }
                    // This is Attack-four even while the quarry is still out
                    // of weapon range. The logical delay already prevents
                    // attack opcodes from running; exposing the native
                    // Attack-start/3 cursor is what lets the final quiet
                    // callback hand the retained route directly back to Move.
                    // Keeping the old Move tail here made packed assault
                    // lines look frozen on their residual boundary.
                    world.armBattleNetAttackStart(unit);
                    if (movingQuarryFirstRouteResidualConstruction
                            && !freeDetourResidualHold) {
                        // Unlike a paid route-wrap constructor, this recurring
                        // quarry leg owns the complete two quiet callbacks,
                        // including when the prior target switch also left the
                        // Java replan marker set. Do not let that retained-tail
                        // marker freeze timer three behind an order delay.
                        unit.setBattleNetOrderDelay(0);
                        unit.setBattleNetBlockedChaseAttackConstruction(true);
                        unit.setBattleNetAttackRefusalRecoveryStage(0);
                    } else if (retainedReplanConstruction) {
                        // A replacement with a substantial cached tail has
                        // already entered the paid blocked-chase handoff, so
                        // its residual settlement exposes real Attack 3,2,1.
                        // A short four-byte tail then owns Move 15 (Human 13
                        // grunt 1507, fixtures 162..165); a saturated route
                        // with nineteen bytes parks through Move 1 instead
                        // (XHuman 12 grunt 1517, fixtures 91..94). Treating
                        // either as an ordinary order-delay hold freezes the
                        // visible Attack constructor at three.
                        unit.setBattleNetOrderDelay(0);
                        unit.setBattleNetBlockedChaseAttackConstruction(true);
                        if (unit.pathLength()
                                == BattleNetPathFinder.MAX_PATH - 1) {
                            // This is a paid, saturated buffer rather than a
                            // reusable short tail. Keep its refusal owner
                            // through Attack construction so a moved quarry
                            // parks all nineteen stale bytes on the Move-one
                            // handoff and draws the direct replacement ray.
                            unit.setBattleNetRefusalHold(true);
                        }
                    } else if (expiredHarvestReplacementConstruction) {
                        // This is the same recurring leg constructor as a
                        // still-moving harvesting quarry, reached through the
                        // expired pointer's replacement route. Its timer-one
                        // callback selects and spends the next route in one
                        // visit; stage two would split planning from Execute
                        // and move one fixture late.
                        unit.setBattleNetOrderDelay(0);
                        unit.setBattleNetBlockedChaseAttackConstruction(true);
                        unit.setBattleNetAttackRefusalRecoveryStage(0);
                    } else if (spentSingleReplanConstruction
                            || paidTailWrapConstruction
                            || navalReplanConstruction) {
                        // A spent one-byte replacement has no live Move tail
                        // for a surrogate order delay to protect. A paid
                        // four-byte wrap has the complementary proof: native
                        // keeps its three live tail bytes while Attack owns
                        // 3,2,1. Naval refreshed-goal routes likewise retain
                        // their tail while the real Attack cursor drains
                        // (XOrc 11 destroyer 1519, fixtures 159..162). In all
                        // three cases construction is already
                        // exposed, so drain it directly and let timer one
                        // return ownership to Move. XHuman 12 slot 1476 does
                        // this with an empty tail at fixtures 124..127;
                        // XHuman 10 slot 1497 keeps SE,SE,NE through fixtures
                        // 90..92 and spends SE on 93. A surrogate order delay
                        // leaves the visible Attack cursor frozen at three.
                        unit.setBattleNetOrderDelay(0);
                        unit.setBattleNetAttackRefusalRecoveryStage(2);
                    } else {
                        unit.setBattleNetOrderDelay(2);
                    }
                    if (freeDetourResidualHold) {
                        // Attack-four owns the two following visits, but its
                        // expiry hands the replacement route a Move decision
                        // immediately. Keep both the permit and provenance
                        // sticky through the delay so XHuman 12 grunt 1507
                        // free-scans and steps E on fixture 55 rather than
                        // only laying E,SE there and waiting another visit.
                        unit.setBattleNetChaseStepReady(true);
                        unit.setBattleNetChaseEmptyRouteReplan(true);
                    }
                    return;
                }
                inRangeReplanSettle = replanResidualHold;
                unit.setBattleNetChaseReplanResidualHold(false);
                unit.setBattleNetEmptyRouteFreeDetourHold(false);
            }
            if (world.actionMoveWalked && !unit.isMoving()
                    && inRangeReplanSettle
                    && unit.type() != null
                    && unit.type().moveType() == UnitType.Movement.NAVAL
                    && unit.target() != null && unit.target().isAlive()
                    && world.targets.inAttackRange(unit, unit.target())
                    && world.battleNetSequence != null) {
                // A sea route installed by an Attack handoff remains a Move
                // residual until its final pixels drain.  Retail then starts
                // the ranged attack program at construction 3; it does not
                // resume past OP0 as an ordinary continuous chase arrival.
                // This is route ownership, not a ship/type exception: it also
                // prevents a responding fleet from emitting early invisible
                // shells when several ships settle during the same battle.
                int attackStart = world.idle.battleNetSequenceStart(unit,
                        BattleNetSequence.ATTACK_ANIMATION);
                if (attackStart >= 0) {
                    unit.clearPath();
                    unit.setRouteSpent(false);
                    unit.setChasing(false);
                    unit.setFighting(true);
                    unit.setBattleNetSequenceOffset(attackStart);
                    unit.setBattleNetAnimationTimer(3);
                    AnimationSet set = unit.type().animationSet();
                    Animation attack = set == null ? null
                            : set.get(AnimationSet.State.ATTACK);
                    if (attack != null
                            && unit.animation().current() != attack) {
                        unit.animation().switchTo(attack);
                    }
                    return;
                }
            }
            if (world.actionMoveWalked && !unit.isMoving()
                    && inRangeReplanSettle
                    && spentSingleReplanConstruction
                    && unit.type() != null
                    && unit.type().moveType() != UnitType.Movement.NAVAL
                    && unit.target() != null && unit.target().isAlive()
                    && world.targets.inAttackRange(unit, unit.target())) {
                // A replacement quarry reached by one freshly drawn heading
                // owns a new queued Attack, even when that heading was drawn
                // while an older paid tail was still active. The old wrap bit
                // describes the route which selected the replacement; it must
                // not waive construction for the replacement's arrival.
                // Human 13 ogre 1511 is the binary witness: its paid route to
                // the dying wise-man retargets knight 1493, spends one SE, then
                // exposes Attack 643/3,2,1 on fixtures 154..156. Treating the
                // stale wrap bit as payment enters OP0 at fixture 155 and deals
                // the next blow two fixtures early. A direct paid-tail arrival
                // has no replan-residual owner and remains on the immediate OP0
                // seam below.
                unit.setBattleNetAttackWrapDestArmPending(false);
                unit.clearPath();
                unit.setRouteSpent(false);
                unit.setChasing(false);
                unit.setFighting(true);
                armBattleNetRangedAttackCadence(unit);
                int replacementAttackStart = world.idle
                        .battleNetSequenceStart(unit,
                                BattleNetSequence.ATTACK_ANIMATION);
                if (replacementAttackStart >= 0) {
                    unit.setBattleNetSequenceOffset(replacementAttackStart);
                    unit.setBattleNetAnimationTimer(3);
                }
                AnimationSet set = unit.type().animationSet();
                Animation attack = set == null ? null
                        : set.get(AnimationSet.State.ATTACK);
                if (attack != null && unit.animation().current() != attack) {
                    unit.animation().switchTo(attack);
                }
                return;
            }
            if (world.actionMoveWalked && !unit.isMoving()) {
                if (System.getenv("CHONKCRAFT_TRACE_BNE_RESIDUAL") != null
                        && (unit.battleNetChaseReplanResidualHold()
                                || unit.battleNetEmptyRouteFreeDetourHold())) {
                    String resEnv = System.getenv("CHONKCRAFT_TRACE_BNE_RESIDUAL")
                            .trim();
                    if ("*".equals(resEnv)
                            || unit.id() == Integer.parseInt(resEnv)) {
                        System.err.printf(
                                "JBNEREPLANCLEAR cycle=%d unit=%d at=%d,%d "
                                        + "pathn=%d moveAnim=%d%n",
                                world.cycle, unit.id(),
                                unit.tileX(), unit.tileY(),
                                unit.pathLength(),
                                world.battleNetMoveAnimation(unit) ? 1 : 0);
                    }
                }
                unit.setBattleNetChaseReplanResidualHold(false);
                unit.setBattleNetEmptyRouteFreeDetourHold(false);
            }
            // A paid tail-wrap route can retain collision provenance after its
            // first committed stride. On that residual-settle visit, native's
            // behavior-one attack action queues Still, keeps the remaining
            // route bytes, and gives Attack construction 3,2,1 ownership before
            // Move may spend the next heading. XHuman 10 grunt 1497 is the raw
            // witness: residual pixels settle at 78,88 on fixture 90, Attack
            // 2539 counts 3,2,1, and the retained SE is spent on 93. Reuse the
            // existing routed-construction stage so equivalent crowded AI
            // formations receive the same Attack -> Move handoff.
            if (world.actionMoveWalked && !unit.isMoving()
                    && unit.battleNetAttackWrapDestArmPending()
                    && unit.battleNetAttackRefusalRecoveryStage() == 0
                    && unit.battleNetAiBehavior() == 1
                    && unit.battleNetPathStepsTaken() == 1
                    // Native's four-byte tail retains exactly three headings
                    // after this first stride. Longer cooperative prefixes
                    // keep moving instead of yielding to Attack construction
                    // (XHuman 12 grunt 1495 retains four and spends SE at 53).
                    && unit.pathLength() == 3
                    && unit.battleNetCollisionCounter() == 1
                    && unit.target() != null && unit.target().isAlive()
                    && !world.targets.inAttackRange(unit, unit.target())) {
                world.armBattleNetAttackStart(unit);
                unit.setBattleNetAttackRefusalRecoveryStage(2);
                AnimationSet set = unit.type() == null
                        ? null : unit.type().animationSet();
                Animation attack = set == null
                        ? null : set.get(AnimationSet.State.ATTACK);
                if (attack != null && unit.animation().current() != attack) {
                    unit.animation().switchTo(attack);
                }
                return;
            }
            // Leftover residual that just settled in melee range opens Attack
            // start construction 3 this visit and keeps the leftover heading
            // through 3,2,1. stepBattleNetAttackSequence already ran while
            // the last pixels were still owed, so without this arm Java
            // stayed on the Move body, then leftover-discarded the heading
            // and re-armed construction three cycles late (1485 chip 57).
            // Dest-arm leftover remaining one heading (1490 SE,S with SE
            // already spent) uses the same Attack start 3 keep. Replan
            // leftover 1485 is the other witness. Ordinary chase leftover
            // after several steps still residual-opens past OP0 (1592).
            int settledMeleeType = unit.type() == null ? -1
                    : PudUnitTypes.code(unit.type().ident());
            boolean infantryReplanSettle = inRangeReplanSettle
                    && settledMeleeType != 6 && settledMeleeType != 7;
            if (world.actionMoveWalked && !unit.isMoving()
                    && unit.pathLength() == 1
                    // PathStepsTaken spans the whole Attack order, not just
                    // the replacement route.  A retarget may therefore
                    // settle the first stride of its fresh route with a
                    // larger accumulated value.  The retained replan
                    // provenance is the native discriminator: XHuman 4
                    // footman 1484 has already walked once toward its old
                    // quarry, then keeps the replacement route's N byte while
                    // Attack construction owns fixtures 102..104 and OP0
                    // consumes the byte plus SyncRand on 105. Cavalry owns a
                    // separate retained-tail seam: XHuman 10 knight 1480
                    // residual-opens past OP0 and debits immediately on
                    // fixture 76, even with the same accumulated step count.
                    && (unit.battleNetPathStepsTaken() == 1
                            || infantryReplanSettle)
                    && (inRangeReplanSettle
                            || !unit.battleNetAttackWaitRefillResidual())
                    && unit.type() != null
                    && unit.type().maxAttackRange() <= 1
                    && world.battleNetMoveAnimation(unit)
                    && world.battleNetSequence != null
                    && unit.target() != null
                    && unit.target().isAlive()
                    // A moving quarry normally owns every borrowed pixel:
                    // tile adjacency alone can still leave the sprites more
                    // than a square apart.  Once their actual BNE pixel
                    // anchors close to melee range, however, the final cached
                    // heading yields to Attack construction. Human 8's second
                    // attack peasant reaches (2304,2080) while its quarry is
                    // only three pixels east and one tile south; retail keeps
                    // the south byte and opens Attack 3,2,1 instead of walking
                    // onto 72,66.
                    && (!unit.battleNetMovingQuarryResidual()
                            || battleNetMovingQuarryPixelInMeleeRange(
                                    unit, unit.target()))
                    && world.targets.inAttackRange(unit, unit.target())) {
                int leftoverAttackStart = world.idle.battleNetSequenceStart(unit,
                        BattleNetSequence.ATTACK_ANIMATION);
                    if (leftoverAttackStart >= 0) {
                        boolean paidWrapConstruction =
                                unit.battleNetAttackWrapDestArmPending();
                        if (unit.battleNetMovingQuarryResidual()) {
                            unit.setBattleNetMovingQuarryResidual(false);
                        }
                        unit.setBattleNetSequenceOffset(leftoverAttackStart);
                    unit.setBattleNetAnimationTimer(
                            paidWrapConstruction ? 1 : 3);
                    if (paidWrapConstruction) {
                        unit.setBattleNetAttackWrapDestArmPending(false);
                    }
                    AnimationSet set = unit.type() == null
                            ? null : unit.type().animationSet();
                    Animation attack = set == null
                            ? null : set.get(AnimationSet.State.ATTACK);
                    if (attack != null && unit.animation().current() != attack) {
                        unit.animation().switchTo(attack);
                    }
                    if (paidWrapConstruction) {
                        // The native Move completion returns through the
                        // already-open Attack OP0 in this same scheduler
                        // visit.  Let the ordinary sequence interpreter run
                        // that marker now so all target, hold and SyncRand
                        // side effects remain centralized.  Merely parking
                        // at attackStart/1 inserted an otherwise impossible
                        // extra cycle before the body (Human 13 ogre 1511:
                        // native 644/1 at fixture 130, Java 643/1).
                        stepBattleNetAttackSequence(unit);
                        // Route cursor 20 is part of that same callback, but
                        // only after OP0 has used the retained route while it
                        // runs its target scan. Clearing before the marker
                        // changes equal-score selection in packed lines. Once
                        // OP0 returns, close the paid arrival and let the
                        // synchronized debit observe its post-OP0 cursor.
                        if (unit.battleNetPendingMeleeSyncRand()
                                && unit.target() != null
                                && unit.target().isAlive()
                                && world.targets.inAttackRange(
                                        unit, unit.target())) {
                            unit.clearPath();
                            unit.setRouteSpent(false);
                            unit.setChasing(false);
                            unit.setFighting(true);
                            armBattleNetRangedAttackCadence(unit);
                            unit.setBattleNetResidualEmptyRouteSettle(false);
                            world.consumeBattleNetPendingMeleeSyncRand(unit);
                        }
                    }
                    return;
                }
            }
            if (world.actionMoveWalked && !unit.isMoving()
                    && PudUnitTypes.code(unit.type().ident()) == 50
                    && world.battleNetMoveAnimation(unit) && world.movement.atMoveBoundary(unit)
                    && world.battleNetSequence != null && onBattleNetChaseMoveBody(unit)) {
                int attackStart = world.idle.battleNetSequenceStart(unit,
                        BattleNetSequence.ATTACK_ANIMATION);
                if (attackStart >= 0) {
                    unit.setBattleNetSequenceOffset(attackStart);
                    unit.setBattleNetAnimationTimer(3);
                    return;
                }
            }
            Unit chased = unit.target();
            boolean clearCollisionAfterSettledRetargetStep = false;
            boolean clearRefusalsAfterSettledRetargetStep = false;
            boolean goalMoved = chased != null
                    && (unit.pathGoalX() != chased.tileX()
                        || unit.pathGoalY() != chased.tileY());
            // A goal that has moved wins over a route that has just run out.
            // NextPathElement asks `output.Length <= 0 || IsRecalculateNeeded()`
            // in that order and both arms lead to NewPath, so a chaser whose
            // quarry has walked on re-plans at once and never spends the
            // ten-cycle pause an exhausted route costs.
            // Not asked whether the quarry is still worth attacking. The
            // refresh lives inside {@code NextPathElement}, which
            // {@code DoActionMove} calls before {@code MoveToTarget} reaches
            // anything that examines the goal
            // so a chaser
            // re-plans at a target that has just started dying and only
            // afterwards is told to stop. On {@code maps/demo/demo03} a
            // footman at 8,1 whose peasant died at 7,4 on cycle 60 re-plans
            // at 67 and steps west; this implementation kept its target, refused to
            // re-plan for it, and stood.
            //
            // BNE 2.02's world.movement consult (FUN_0044fbd0) is stricter and
            // softer in different places than LegacyEngine:
            //   - exhausted world.movement.route(cursor at 0xff): rebuild and hand the new
            //     first step to FUN_0044fab0 in the same consult -- no PF_WAIT
            //     (XHuman 12 grunt 1512's second SW at fixture cycle 19);
            //   - same quarry walked one tile: keep the multi-step prefix and
            //     only refresh the remembered goal (Human 13 ogre 1484 keeps
            //     NE,N... while the wise-man steps 123,28 → 122,29);
            //   - auto-acquire picks a different unit: wipe the old prefix and
            //     replan (Human 13 ogre 1482 drops the knight at 124,30 for the
            //     wise-man and first-steps NW instead of the knight path's N).
            // The boundary is either the ordinary Move wrap (offsets clear,
            // atMoveBoundary) or a retail OP0 permit on the chase Move body.
            // After the pre-walk drain above, a chaser that still owes pixels
            // into this cycle becomes isMoving-clear; OP0-ready covers the
            // skeleton-style body where ChonkCraft wrap and retail OP0 disagree.
            // A moving-quarry residual can finish its last pixels one Java
            // visit before the retail Move program reaches OP0.  The unit is
            // still represented as walkHolding on that visit, but an empty
            // route plus the imminent OP0 is already the native target/path
            // decision boundary.  Let the ordinary rescan below run before
            // stepMove consumes that OP0; otherwise stepMove sees only the
            // exhausted route and installs the generic two-cycle chase wait.
            // Human 8's attack peasant 1520 is the sealed witness: at fixture
            // 105 it changes peasant targets, writes SW,W and consumes SW in
            // one callback.  The missed boundary left Java parked until 108.
            boolean settledSpentMovingQuarryBoundary =
                    battleNetSettledSpentMovingQuarryDecisionDue(unit);
            boolean atChaseBoundary = (!unit.isMoving()
                    && world.movement.atMoveBoundary(unit))
                    || unit.battleNetChaseStepReady()
                    || settledSpentMovingQuarryBoundary;
            if (chased != null && atChaseBoundary) {
                // In-range leftovers are discarded before any further heading
                // is consumed. XHuman 12 axethrower 1473 reaches 21,44 in
                // weapon range of tower 25,42 after its first NE and native
                // holds STAND_GROUND there; Java's leftover NE to 22,43 fired
                // at fixture cycle 21 five steps early.
                // Tile adjacency is not arrival while the quarry still owns
                // a committed pixel step. Human 8's attack peasant and the
                // harvesting peasant ahead of it are adjacent in tile space
                // throughout fixtures 5..116, but retail keeps MOVE_TO_TARGET
                // running until the quarry settles and their pixel anchors
                // close to one tile at fixture 117. Treating the moving tile
                // as attack range froze the chaser at fixture 17 and let it
                // damage the quarry from several visible squares away.
                boolean attackOwnsResidual =
                        unit.order() == Unit.Order.ATTACK
                        || unit.autoTargeting()
                        || unit.battleNetAttackWaitRefillResidual()
                        || unit.battleNetMovingQuarryResidual();
                // A chase residual can land beside a quarry which entered
                // Die while the unbreakable step was still draining.  The
                // arrival opens Attack OP0, and that OP0 free-scan may name
                // an out-of-range replacement before it decides whether to
                // fight or dest-arm another route.  Do not turn the corpse's
                // tile adjacency into ATTACK_TARGET first: XHuman 10 grunt
                // 1495 settles at 81,89 on fixture 51 after footman 1492 has
                // begun dying, names the live knight on 83,89, holds Attack
                // start through 3,2,1, and first-steps NE on fixture 54.
                // Java instead opened the post-OP0 swing against the corpse
                // and stayed frozen on 81,89.
                boolean deadMeleeResidual = world.actionMoveWalked
                        && !unit.isMoving()
                        // A native route may still expose its terminal byte
                        // into the quarry's own square. That byte is not a
                        // third movement permit once the residual has landed
                        // beside a target already in Die. XHuman 9 knight
                        // 1414 retains N in its raw buffer at 15,122, then
                        // clears it and opens Still on fixture 126. Limiting
                        // this seam to only an empty Java path made preserving
                        // the authentic full route resurrect Attack against
                        // the corpse.
                        && (unit.pathLength() == 0
                                || (unit.pathLength() == 1
                                        && world.targets.inAttackRange(
                                                unit, chased)))
                        && unit.canMove()
                        && unit.type() != null
                        && unit.type().maxAttackRange() <= 1
                        && chased != null
                        && !world.targets.validAttackTarget(unit, chased);
                if (deadMeleeResidual) {
                    int deadReactRange = Math.max(
                            unit.type().reactRange(
                                    world.isPerson(unit.player())),
                            Math.max(1, unit.type().maxAttackRange()));
                    Unit replacement = world.targets.findBattleNetHostile(
                            unit, deadReactRange, null);
                    boolean replacementInRange = replacement != null
                            && replacement != chased
                            && replacement.isAlive()
                            && !replacement.isDying()
                            && world.targets.inAttackRange(
                                    unit, replacement);
                    boolean replacementOutOfRange = replacement != null
                            && replacement != chased
                            && replacement.isAlive()
                            && !replacement.isDying()
                            && !world.targets.inAttackRange(
                                    unit, replacement);
                    if (replacement == null
                            || replacement == chased
                            || !replacement.isAlive()
                            || replacement.isDying()) {
                        // The residual has paid its committed pixels, and the
                        // OP0 replacement scan found no next quarry. Native
                        // now executes EndActionAttack; it does not let the
                        // generic in-range footprint test below convert the
                        // expired goal into ATTACK_TARGET. XHuman 9 knight
                        // 1414 clears skeleton 1430 and becomes Still 1869/3
                        // on fixture 126. Falling through opened a complete
                        // new swing program against the corpse.
                        world.finishAttackOrder(unit);
                        if (world.battleNetSequence != null) {
                            unit.setBattleNetSequenceOffset(
                                    world.idle.battleNetStillSequenceStart(unit));
                            unit.setBattleNetAnimationTimer(3);
                        }
                        return;
                    }
                    if (replacementInRange) {
                        // The final paid pixels can settle after their weak
                        // quarry has left Alive. CheckForTargetInRange still
                        // null-scans before it assigns the inherited OP0; an
                        // adjacent successor owns a fresh Attack constructor,
                        // not the dead quarry's already-open body. Human 8
                        // ogre 1538 seals the distinction: peasant 1519 is
                        // retained through fixture 258, then peasant 1536 is
                        // installed at Attack 2657/3 on fixture 259 and the
                        // constructor enters its committed 23-count hold.
                        // The synchronized melee draw remains pending until
                        // the next live in-range callback (fixture 260).
                        int replacementAttackStart = world.idle
                                .battleNetSequenceStart(unit,
                                        BattleNetSequence.ATTACK_ANIMATION);
                        setAutoTarget(unit, replacement);
                        unit.setRouteSpent(false);
                        unit.setChasing(false);
                        unit.setFighting(true);
                        if (replacementAttackStart >= 0) {
                            unit.setBattleNetSequenceOffset(
                                    replacementAttackStart);
                            unit.setBattleNetAnimationTimer(3);
                        }
                        finishBattleNetPaidWrapRetargetArrival(unit);
                        world.turnToTarget(unit, replacement, 0, 0);
                        return;
                    }
                    if (replacementOutOfRange) {
                        int replacementAttackStart = world.idle
                                .battleNetSequenceStart(unit,
                                        BattleNetSequence.ATTACK_ANIMATION);
                        if (chased.isDying()) {
                            // LetUnitDie has already removed this bit in BNE.
                            // A later Java occupancy restore can put it back;
                            // clear only the field flag and keep the corpse in
                            // UnitCache for targeting/release order.
                            world.setMovementFieldFlags(chased, false);
                        }
                        setAutoTarget(unit, replacement);
                        if (replacementAttackStart >= 0) {
                            unit.setBattleNetSequenceOffset(
                                    replacementAttackStart);
                            unit.setBattleNetAnimationTimer(3);
                        }
                        world.turnToTarget(unit, replacement, 0, 0);
                        unit.setOfferedTarget(replacement);
                        unit.setFighting(false);
                        unit.setChasing(false);
                        unit.setBattleNetAttackWrapDestArmPending(true);
                        return;
                    }
                }
                if (world.actionMoveWalked && !unit.isMoving()
                        && unit.type() != null
                        && unit.type().moveType() == UnitType.Movement.NAVAL
                        && unit.type().firesMissile()
                        && unit.offeredTarget() == chased
                        && world.targets.inAttackRange(unit, chased)) {
                    // A struck person warship accepts its aggressor as an
                    // offered goal, but that offer is not a locked player
                    // command.  When the response route settles, retail runs
                    // AutoSelectTarget before accepting the now-in-range goal.
                    // Null-seeding matters for equal scores: spatial order can
                    // replace the aggressor with another ship in the battle
                    // line.  Treating the offer like a direct click skipped
                    // this boundary and fired at the stale hull immediately.
                    int reactRange = Math.max(
                            unit.type().reactRange(world.isPerson(unit.player())),
                            Math.max(1, unit.type().maxAttackRange()));
                    Unit reactiveCandidate = world.targets
                            .findBattleNetHostile(unit, reactRange, null);
                    if (reactiveCandidate != null
                            && reactiveCandidate != chased
                            && reactiveCandidate.isAlive()
                            && world.targets.inAttackRange(
                                    unit, reactiveCandidate)) {
                        setAutoTarget(unit, reactiveCandidate);
                        unit.clearPath();
                        unit.setRouteSpent(false);
                        unit.setChasing(false);
                        unit.setFighting(true);
                        int reactiveAttackStart = world.idle
                                .battleNetSequenceStart(unit,
                                        BattleNetSequence.ATTACK_ANIMATION);
                        if (reactiveAttackStart >= 0) {
                            unit.setBattleNetSequenceOffset(
                                    reactiveAttackStart);
                            unit.setBattleNetAnimationTimer(3);
                            // This is a Move-boundary free scan. After its
                            // constructor, OP0 serves the unspent wall-clock
                            // ranged cadence instead of manufacturing a shot.
                            unit.setBattleNetAttackResumeFromMove(true);
                            unit.setBattleNetAttackOp0OutOfRange(true);
                            unit.setBattleNetRangedFreeScanHoldPending(true);
                            AnimationSet set = unit.type().animationSet();
                            Animation attack = set == null ? null
                                    : set.get(AnimationSet.State.ATTACK);
                            if (attack != null
                                    && unit.animation().current() != attack) {
                                unit.animation().switchTo(attack);
                            }
                            world.turnToTarget(
                                    unit, reactiveCandidate, 0, 0);
                            return;
                        }
                    }
                }
                if ((!unit.isMoving() || !attackOwnsResidual)
                        && world.targets.inAttackRange(unit, chased)) {
                    int leftoverAttackStart = world.battleNetSequence == null
                            || world.idle == null
                            ? -1
                            : world.idle.battleNetSequenceStart(unit,
                                    BattleNetSequence.ATTACK_ANIMATION);
                    if (unit.pathLength() == 1
                            && leftoverAttackStart >= 0
                            && unit.battleNetSequenceOffset() == leftoverAttackStart
                            && unit.battleNetAnimationTimer() > 0
                            && unit.chasing()) {
                        // Keep the leftover heading through Attack 3,2,1.
                        // Discarding here used to re-arm construction and
                        // walk into stepMove, which dest-armed the leftover
                        // heading (Human 13 grunt 1485 chip 55).
                        return;
                    }
                    // Melee multi-step leftover: open past OP0 with melee mark
                    // (Human 13 ogre 1510). Ranged multi-heading leftover after
                    // approach residual: OP0 already spent; native resumes
                    // mid-windup. markMelee false so OP10 still flies the axe.
                    // Open when at most three headings remain (XHuman 4 axes
                    // 1521/79, 1506/94, and 1516/84; XHuman 12 axe 1524/76),
                    // or after two-or-more tile
                    // steps with any multi leftover (Human 13 axe 1495/105 at
                    // 115,28 after NE,E). Native opens 888/1 on the residual-
                    // settle visit in each case.
                    // Cold-starting them armed the unrelated 63-cycle hold and
                    // made live ranged attackers appear permanently frozen.
                    // Single-step four-leftover (Human 13 axe 1483/117 at
                    // 119,33 after one NE) stays cold so its approach+resume
                    // OP0 can seal timer 63.
                    // Melee multi leftover: open past OP0. Do not require the
                    // Move body cursor -- Human 13 wise-man 1496 residual-settles
                    // pathn 2 already on Attack@1922 (native opens mid-windup at
                    // 1923); requiring onChaseMoveBody left Java on OP0 and the
                    // swing finished three cycles late so presentation collapse
                    // had to paper over fixture 46.
                    int pathStepsAtSettle = unit.battleNetPathStepsTaken();
                    // A one-heading route admitted after a refused chase is
                    // already the refusal handler's paid approach.  When that
                    // heading drains in weapon range, retail enters Attack
                    // past its opening OP0 just like a multi-step residual;
                    // it does not cold-start construction and then charge the
                    // bodyWaitSum-1 approach hold. XHuman 10 ogre 1538 is the
                    // sealed witness: its refused SE tail settles on 97,57 at
                    // fixture 85 as Attack@644/1 and reaches OP10 at 92.
                    // Java used to reopen at 643/3 and park for 23 more visits,
                    // leaving the adjacent footman untouched and making this
                    // common crowded-formation arrival look frozen.
                    boolean refusalTailResidual = unit.pathLength() == 0
                            && pathStepsAtSettle == 1
                            && unit.routeSpent()
                            && unit.battleNetRefusals() > 0
                            && unit.battleNetCollisionCounter() == 0
                            && !unit.battleNetChaseEmptyRouteReplan()
                            && onBattleNetChaseMoveBody(unit);
                    // A melee unit that consumed a multi-step route into
                    // range has already entered the attack program even when
                    // the final heading exhausted the route. Preserve that
                    // post-marker state instead of cold-starting Attack and
                    // charging a second wind-up.
                    boolean meleeResidualOpen = world.actionMoveWalked
                            && unit.type() != null
                            && unit.type().maxAttackRange() <= 1
                            && (unit.battleNetAttackWaitRefillResidual()
                                    || unit.battleNetAttackWrapDestArmPending()
                                    || unit.battleNetMovingQuarryResidual()
                                    || (unit.pathLength() >= 2
                                    && (onBattleNetChaseMoveBody(unit)
                                            || unit.battleNetSequenceOffset()
                                                    == world.idle.battleNetSequenceStart(
                                                            unit,
                                                            BattleNetSequence.ATTACK_ANIMATION)))
                                    || refusalTailResidual
                                    || (pathStepsAtSettle >= 2
                                            && onBattleNetChaseMoveBody(unit)));
                    boolean continuingPersonNavalHitHandoff =
                            world.actionMoveWalked
                            && unit.battleNetSpatialHitHelpHandoff()
                            && world.isPerson(unit.player())
                            && unit.type() != null
                            && unit.type().moveType()
                                    == UnitType.Movement.NAVAL;
                    boolean retainedPersonNavalHitRoute =
                            continuingPersonNavalHitHandoff
                            && unit.pathLength() > 0;
                    boolean rangedResidualOpen = world.actionMoveWalked
                            && onBattleNetChaseMoveBody(unit)
                            && unit.type() != null
                            && unit.type().maxAttackRange() > 1
                            && !retainedPersonNavalHitRoute
                            && battleNetRangedResidualRouteQualifies(
                                    unit, pathStepsAtSettle);
                    int pathnAtSettle = unit.pathLength();
                    boolean coldNavalRangedArrival =
                            retainedPersonNavalHitRoute
                            && unit.type().firesMissile();
                    unit.clearPath();
                    unit.setRouteSpent(false);
                    unit.setChasing(false);
                    unit.setFighting(true);
                    if (coldNavalRangedArrival) {
                        // A warship does not inherit the land-projectile
                        // residual shortcut. Its settled doubled stride opens
                        // a fresh 3,2,1 constructor and restarts the full
                        // broadside clock. XOrc 11 destroyer 1521 is the sealed
                        // witness: Move@3261/1 on fixture 205 becomes
                        // Attack@3266/3,2,1 on 206..208, then 3266/118 on 209.
                        // Keeping the old order clock fired a cannon on 207;
                        // opening past OP0 skipped construction and damaged
                        // destroyer 1542 on fixture 217.
                        unit.setBattleNetRangedAttackCadenceRemaining(0);
                    } else if (continuingPersonNavalHitHandoff) {
                        // An exhausted response route owns the authenticated
                        // post-OP0 contrast (XOrc 11 destroyer 1493): it fires
                        // on the settlement callback instead of paying another
                        // constructor, so the help-route provenance is spent.
                        unit.setBattleNetSpatialHitHelpHandoff(false);
                    }
                    armBattleNetRangedAttackCadence(unit);
                    if (meleeResidualOpen) {
                        unit.setBattleNetResidualEmptyRouteSettle(false);
                        world.openBattleNetAttackAfterChaseResidual(unit, true);
                        if (!unit.battleNetPendingMeleeSyncRand()
                                && unit.battleNetMeleeSyncRemaining() == 1) {
                            // Only an expiring refresh is owned by this fused
                            // arrival callback. A live clock was already
                            // advanced by the ordinary sequence visit; ticking
                            // every positive value double-counted Human 13's
                            // wise man at 25 and moved its draw one fixture
                            // early. XHuman 10's paid one-step cavalry arrival
                            // reaches this otherwise-returning seam at exactly
                            // one, where native refreshes in the same visit.
                            world.tickBattleNetMeleeSyncLoop(unit);
                        }
                        unit.setBattleNetAttackWaitRefillResidual(false);
                        unit.setBattleNetMovingQuarryResidual(false);
                    } else if (rangedResidualOpen) {
                        unit.setBattleNetResidualEmptyRouteSettle(false);
                        world.openBattleNetAttackAfterChaseResidual(unit, false);
                    } else {
                        boolean coldRangedArrival = unit.type() != null
                                && unit.type().maxAttackRange() > 1;
                        if (coldRangedArrival) {
                            // Ranged residuals rejected by the shortcut
                            // classifier land on the cold Attack constructor.
                            // In particular, one paid step with four retained
                            // headings exposes 3,2,1; tails of 0..3, saturated
                            // tails, and multi-step arrivals opened above.
                            unit.setBattleNetResidualEmptyRouteSettle(false);
                            world.armBattleNetAttackStart(unit);
                            if (coldNavalRangedArrival) {
                                // The constructor's OP0 must serve the freshly
                                // restarted broadside period instead of firing.
                                unit.setBattleNetAttackResumeFromMove(true);
                                unit.setBattleNetAttackOp0OutOfRange(true);
                            }
                        } else {
                            // Empty-route residual (pathn 0 after last heading spent):
                            // OP0 restarts cold and finishes late; presentation may
                            // collapse the pre-OP10 wait (Human 13 grunt 93). A
                            // leftover heading (pathn >= 1) keeps the full OP10 wait
                            // (Human 13 knight 100 / native 1500 fixture 50).
                            unit.setBattleNetResidualEmptyRouteSettle(pathnAtSettle == 0);
                        }
                    }
                    if (deferBattleNetFootmanRetainedRouteSyncRand(
                            unit, chased, chaseTargetBeforeWalk)) {
                        return;
                    }
                    world.consumeBattleNetPendingMeleeSyncRand(unit);
                    world.turnToTarget(unit, chased, 0, 0);
                    return;
                }
                Unit previous = chased;
                // A new path element is a native targeting boundary. Rescan
                // without seeding the incumbent so equal retail scores resolve
                // by spatial order: Human 13 ogre 1482 at 125,33 picks the
                // wise-man (first 0x1003d) over the knight it was chasing
                // (also 0x1003d) and first-steps NW. Seeding the knight kept
                // the stale N prefix.
                //
                // When a multi-step cache remains and its next heading still
                // closes Chebyshev on the new quarry, keep the prefix rather
                // than wipe: XHuman 12 grunt 1470 at (20,45) had leftover
                // E... ; equal-score tower 117 (25,42) would replan N to
                // (20,44) while native continued E to (21,45). A leftover that
                // does not close (1482's N toward the wise-man) still wipes.
                int reactRange = Math.max(
                        unit.type().reactRange(world.isPerson(unit.player())),
                        Math.max(1, unit.type().maxAttackRange()));
                // Nearly-full diagonal residual next blocked while pathGoal
                // still names a live building quarry: free-compass a detour
                // *before* free-scan. Branch Witness shows native 0x44fbd0
                // path work then order_x write (0x4513fc) after; Java free-
                // scanned first onto footman 29,43 (score 0x10033 > tower
                // 0x10027) and pathfound NW while native stepped SW@41.
                // Full pathfind toward the tower from 23,41 is empty FOUND
                // (Bresenham SE into wall, both wall faces fail); free-
                // compass starting after the blocked residual heading picks
                // the first free neighbour (NE blocked → … → SW free when
                // S is occupied). Pure-axis nearly-full residuals still
                // free-scan (1476 N@22 soft-waits / keeps residual; free-
                // compass S REG'd y 44). Short leftovers (1453 pathn 2)
                // free-scan. Free residual next free-scans (1489@22).
                // Non-building quarries unchanged.
                boolean residualBlockedBuilding = false;
                int residualBlockedFreeHeading = -1;
                int residualBlockedPathStepsTaken = -1;
                boolean residualPathOneChangedTarget = false;
                boolean residualPathOneSaturatedPaidBuildingTarget = false;
                if (previous != null && unit.pathLength() >= 6
                        && previous.type() != null && previous.type().building()
                        && previous.isAlive() && previous.isOnMap()
                        && !previous.isDying()
                        && unit.stepDrained() && !unit.isMoving()
                        && Direction.isDiagonal(unit.peekHeading())) {
                    int heading = unit.peekHeading();
                    int stride = world.battleNetMovementStride(unit);
                    int nextX = unit.tileX()
                            + Direction.deltaX(heading) * stride;
                    int nextY = unit.tileY()
                            + Direction.deltaY(heading) * stride;
                    int gx = unit.pathGoalX();
                    int gy = unit.pathGoalY();
                    int bx = previous.tileX();
                    int by = previous.tileY();
                    int bw = Math.max(1, previous.type().tileWidth());
                    int bh = Math.max(1, previous.type().tileHeight());
                    boolean goalOnBuilding = gx >= bx && gx < bx + bw
                            && gy >= by && gy < by + bh;
                    if (goalOnBuilding
                            && !world.canEnter(unit, nextX, nextY)) {
                        for (int offset = 1; offset < Direction.COUNT;
                                offset++) {
                            int dir = (heading + offset) % Direction.COUNT;
                            int freeX = unit.tileX()
                                    + Direction.deltaX(dir) * stride;
                            int freeY = unit.tileY()
                                    + Direction.deltaY(dir) * stride;
                            if (world.canEnter(unit, freeX, freeY)) {
                                residualBlockedFreeHeading = dir;
                                residualBlockedBuilding = true;
                                residualBlockedPathStepsTaken =
                                        unit.battleNetPathStepsTaken();
                                break;
                            }
                        }
                    }
                }
                if (System.getenv("CHONKCRAFT_TRACE_BNE_RESIDUAL") != null) {
                    String resEnv = System.getenv("CHONKCRAFT_TRACE_BNE_RESIDUAL")
                            .trim();
                    boolean resLog = "*".equals(resEnv)
                            || unit.id() == Integer.parseInt(resEnv);
                    if (resLog) {
                        int heading = unit.pathLength() > 0
                                ? unit.peekHeading() : -1;
                        int stride = world.battleNetMovementStride(unit);
                        int nextX = heading >= 0
                                ? unit.tileX() + Direction.deltaX(heading)
                                        * stride
                                : -1;
                        int nextY = heading >= 0
                                ? unit.tileY() + Direction.deltaY(heading)
                                        * stride
                                : -1;
                        boolean canNext = heading >= 0
                                && world.canEnter(unit, nextX, nextY);
                        System.err.printf(
                                "JBNERES cycle=%d unit=%d at=%d,%d "
                                        + "pathn=%d coll=%d drained=%d "
                                        + "initial=%d steps=%d spent=%d "
                                        + "refusals=%d emptyReplan=%d "
                                        + "satRetry=%d parkRefill=%d "
                                        + "parkSteps=%d last=%d "
                                        + "mov=%d prev=%d bldg=%d "
                                        + "goal=%d,%d head=%d next=%d,%d "
                                        + "can=%d blockedBldg=%d freeH=%d "
                                        + "replanHold=%d delay=%d walked=%d%n",
                                world.cycle, unit.id(),
                                unit.tileX(), unit.tileY(),
                                unit.pathLength(),
                                unit.battleNetCollisionCounter(),
                                unit.stepDrained() ? 1 : 0,
                                unit.battleNetPathInitialLength(),
                                unit.battleNetPathStepsTaken(),
                                unit.routeSpent() ? 1 : 0,
                                unit.battleNetRefusals(),
                                unit.battleNetChaseEmptyRouteReplan() ? 1 : 0,
                                unit.battleNetSaturatedResidualFaceRetry()
                                        ? 1 : 0,
                                unit.battleNetRetargetResidualParkRefill()
                                        ? 1 : 0,
                                unit.battleNetRetargetResidualParkSteps(),
                                unit.lastStepHeading(),
                                unit.isMoving() ? 1 : 0,
                                previous == null ? -1 : previous.id(),
                                previous != null && previous.type() != null
                                        && previous.type().building() ? 1 : 0,
                                unit.pathGoalX(), unit.pathGoalY(),
                                heading, nextX, nextY,
                                canNext ? 1 : 0,
                                residualBlockedBuilding ? 1 : 0,
                                residualBlockedFreeHeading,
                                unit.battleNetChaseReplanResidualHold()
                                        ? 1 : 0,
                                unit.battleNetOrderDelay(),
                                world.actionMoveWalked ? 1 : 0);
                    }
                }
                // A later-generation route to the same mobile quarry parks
                // as a whole when the next cached byte is blocked after the
                // current stride settles.  Native advances one collision
                // generation and exposes Move-start/1 with route index 20;
                // the following visit draws through the completed-refusal
                // formation view. XHuman 12 slot 1482 repeats this mechanism
                // at fixture 110 (0x30 -> 0x40) after its earlier 0x20 ->
                // 0x30 moved-goal park at fixture 93.  Treating the blocked
                // byte as a free-compass request stepped north and broke the
                // attack line.
                int settledSameHeading = unit.pathLength() > 0
                        ? unit.peekHeading() : -1;
                int settledSameStride = world.battleNetMovementStride(unit);
                int settledSameX = settledSameHeading >= 0
                        ? unit.tileX()
                                + Direction.deltaX(settledSameHeading)
                                        * settledSameStride
                        : unit.tileX();
                int settledSameY = settledSameHeading >= 0
                        ? unit.tileY()
                                + Direction.deltaY(settledSameHeading)
                                        * settledSameStride
                        : unit.tileY();
                Unit settledSameBlocker = settledSameHeading >= 0
                        ? world.unitAt(settledSameX, settledSameY) : null;
                Unit settledSameCandidate = previous == null ? null
                        : world.targets.findBattleNetHostile(
                                unit, reactRange, null);
                boolean collidedSameQuarryResidualPark =
                        world.actionMoveWalked
                        && unit.stepDrained() && !unit.isMoving()
                        && unit.pathLength() > 1
                        && (unit.battleNetPathStepsTaken() == 2
                                || unit.battleNetCollisionCounter() >= 5)
                        && unit.battleNetCollisionCounter() >= 3
                        && unit.battleNetRefusals() == 0
                        && unit.offeredTarget() == null
                        && previous != null && previous.type() != null
                        && !previous.type().building()
                        && settledSameCandidate == previous
                        && settledSameBlocker != null
                        && settledSameBlocker != unit
                        && world.isAllied(unit.player(),
                                settledSameBlocker.player())
                        && !world.canEnter(unit,
                                settledSameX, settledSameY)
                        && unit.type() != null
                        && unit.type().maxAttackRange() <= 1
                        && !World.battleNetRangedChaseUnit(unit);
                boolean paidSameQuarryResidualBand =
                        world.actionMoveWalked
                        && unit.stepDrained() && !unit.isMoving()
                        && unit.pathLength() > 1
                        && unit.battleNetPathStepsTaken() == 1
                        && unit.battleNetCollisionCounter() >= 3
                        && unit.battleNetRefusals() >= 2
                        // This band belongs to the direct paid-refusal
                        // generation: every hard refusal has advanced the
                        // collision byte once, after the initial cooperative
                        // generation. A much older saturated wall trace can
                        // carry the same visible counts (for example collision
                        // eight with three refusals) but owns route park/refill,
                        // not another retained-tail band.
                        && unit.battleNetCollisionCounter()
                                == unit.battleNetRefusals() + 1
                        && unit.offeredTarget() == null
                        && previous != null && previous.type() != null
                        && !previous.type().building()
                        && settledSameCandidate == previous
                        && settledSameBlocker != null
                        && settledSameBlocker != unit
                        && world.isAllied(unit.player(),
                                settledSameBlocker.player())
                        && !world.canEnter(unit,
                                settledSameX, settledSameY)
                        && unit.type() != null
                        && unit.type().maxAttackRange() <= 1
                        && !World.battleNetRangedChaseUnit(unit);
                if (paidSameQuarryResidualBand) {
                    // This is not a route-index-twenty park. The same quarry
                    // and the same cooperative wall still own the cached tail,
                    // but two earlier hard refusals prove that the collision
                    // generation has bought a complete Move band. Retail
                    // advances the generation, retains every route byte, and
                    // exposes Move 15..1 before probing the head again.
                    int collision = unit.battleNetCollisionCounter() + 1;
                    unit.setBattleNetCollisionCounter(
                            collision > 14 ? 0 : collision);
                    unit.setRouteSpent(false);
                    unit.setWaitCycles(0);
                    unit.setBattleNetOrderDelay(14);
                    unit.setBattleNetRefusalHold(true);
                    unit.setBattleNetChaseStepReady(false);
                    int moveStart = world.idle.battleNetSequenceStart(unit,
                            BattleNetSequence.MOVE_ANIMATION);
                    if (moveStart >= 0) {
                        unit.setBattleNetSequenceOffset(moveStart);
                        unit.setBattleNetAnimationTimer(15);
                    }
                    world.causalTrace.event(world.cycle,
                            "path.paid-same-quarry-residual-band", unit.id(),
                            "target", previous.id(),
                            "path_length", unit.pathLength(),
                            "heading", settledSameHeading,
                            "collision", unit.battleNetCollisionCounter(),
                            "refusals", unit.battleNetRefusals());
                    return;
                }
                if (collidedSameQuarryResidualPark) {
                    int parkedSteps = unit.battleNetPathStepsTaken();
                    int collision =
                            unit.battleNetCollisionCounter() + 1;
                    unit.setBattleNetCollisionCounter(
                            collision > 14 ? 0 : collision);
                    unit.clearPath();
                    unit.setRouteSpent(false);
                    unit.setWaitCycles(0);
                    unit.setBattleNetOrderDelay(0);
                    boolean directTwoStepRefill = parkedSteps == 2;
                    unit.setBattleNetRetargetResidualParkRefill(
                            directTwoStepRefill);
                    unit.setBattleNetRetargetResidualParkSteps(parkedSteps);
                    unit.setBattleNetChaseEmptyRouteReplan(
                            !directTwoStepRefill);
                    int moveStart = world.idle.battleNetSequenceStart(unit,
                            BattleNetSequence.MOVE_ANIMATION);
                    if (moveStart >= 0) {
                        unit.setBattleNetSequenceOffset(moveStart);
                        unit.setBattleNetAnimationTimer(1);
                        unit.setBattleNetChaseStepReady(false);
                    }
                    return;
                }
                // Residual-settled one-heading leftover blocked with no
                // free-progress: native route_index 20 then replan (XHuman 12
                // grunt 1514: SE onto ally after E residual; RI20@41 N@42).
                // Chase OP0 mayDecide is not always ready on the settle
                // visit, so movement soft-wait/PF_WAIT never saw residual-
                // settled and held until ~52. Free residual peeks and free-
                // progress peeks (grunt 1375 SE) fall through.
                if (world.actionMoveWalked
                        && unit.stepDrained()
                        && !unit.isMoving()
                        && unit.pathLength() == 1
                        && unit.target() != null
                        && unit.type() != null
                        && unit.type().maxAttackRange() <= 1
                        && !World.battleNetRangedChaseUnit(unit)
                        && (unit.order() == Unit.Order.ATTACK
                                || unit.order() == Unit.Order.ATTACK_MOVE
                                || unit.chasing())) {
                    int pathn1Stride = world.battleNetMovementStride(unit);
                    int pathn1Peek = unit.peekHeading();
                    int pathn1X = unit.tileX()
                            + Direction.deltaX(pathn1Peek) * pathn1Stride;
                    int pathn1Y = unit.tileY()
                            + Direction.deltaY(pathn1Peek) * pathn1Stride;
                    if (!world.canEnter(unit, pathn1X, pathn1Y)) {
                        Unit pathn1Quarry = unit.target();
                        Unit pathn1Blocker = world.blockerOnLayer(
                                unit, pathn1X, pathn1Y);
                        boolean retainedDirectDestinationArmRefusal =
                                unit.battleNetAttackWrapDestArmPending()
                                && !unit.battleNetChaseEmptyRouteReplan()
                                && unit.battleNetPathInitialLength() == 2
                                && unit.battleNetPathStepsTaken() == 1
                                && unit.battleNetCollisionCounter() == 0
                                && unit.battleNetRefusals() == 0
                                && pathn1Quarry == previous
                                && pathn1Quarry.isAlive()
                                && pathn1Quarry.type() != null
                                && !pathn1Quarry.type().building()
                                && unit.tileX() != pathn1Quarry.tileX()
                                && unit.tileY() != pathn1Quarry.tileY()
                                && pathn1Blocker != null
                                && pathn1Blocker != unit
                                && pathn1Blocker.isOnMap()
                                && !pathn1Blocker.isDying()
                                && pathn1Blocker.type() != null
                                && !pathn1Blocker.type().building()
                                && world.isAllied(unit.player(),
                                        pathn1Blocker.player());
                        if (retainedDirectDestinationArmRefusal) {
                            // The direct two-byte Attack-tail route retains
                            // ownership after its first heading lands. When
                            // the moving quarry advances but an allied body
                            // still occupies the cached diagonal tail, retail
                            // enters FUN_004379e0's complete refusal band on
                            // this settle callback; target scan does not redraw
                            // a free cardinal route. Human 8 attack-peasant
                            // 1526 keeps southeast at fixture 236 and exposes
                            // Move-start/15 instead of stepping east on 238.
                            world.causalTrace.event(world.cycle,
                                    "path.residual-destination-arm-refusal",
                                    unit.id(), "target", pathn1Quarry.id(),
                                    "blocked_heading", pathn1Peek,
                                    "blocker", pathn1Blocker.id());
                            unit.setBattleNetCollisionCounter(1);
                            unit.setRouteSpent(false);
                            unit.setWaitCycles(0);
                            unit.setBattleNetOrderDelay(14);
                            unit.setBattleNetRefusalHold(true);
                            unit.setBattleNetChaseStepReady(false);
                            boolean movingHarvestQuarryEntrance =
                                    pathn1Quarry.order()
                                            == Unit.Order.HARVEST
                                    && pathn1Quarry.isOnMap()
                                    && world.targets.validAttackTarget(
                                            unit, pathn1Quarry)
                                    && unit.offeredTarget() == pathn1Quarry;
                            if (movingHarvestQuarryEntrance) {
                                // The quarry is still a valid mobile target
                                // when this band is purchased. Human 8
                                // attack-peasant 1526 then watches it enter
                                // the mine during Move 15..1. Preserve the
                                // paid band now so its timer-one wake, rather
                                // than an ordinary empty-route chase visit,
                                // owns the adjacent replacement scan.
                                unit.setBattleNetAttackRefusalRecoveryStage(
                                        EXPIRED_QUARRY_SINGLE_BAND_TAIL);
                            }
                            int moveStart = world.idle
                                    .battleNetSequenceStart(unit,
                                            BattleNetSequence.MOVE_ANIMATION);
                            if (moveStart >= 0) {
                                unit.setBattleNetSequenceOffset(moveStart);
                                unit.setBattleNetAnimationTimer(15);
                            }
                            return;
                        }
                        int pathn1Cur = Math.max(
                                Math.abs(pathn1Quarry.tileX()
                                        - unit.tileX()),
                                Math.abs(pathn1Quarry.tileY()
                                        - unit.tileY()));
                        int pathn1GoalDx = Integer.signum(
                                pathn1Quarry.tileX() - unit.tileX());
                        int pathn1GoalDy = Integer.signum(
                                pathn1Quarry.tileY() - unit.tileY());
                        boolean pathn1FreeProgress = false;
                        for (int dir = 0; dir < Direction.COUNT; dir++) {
                            int freeX = unit.tileX()
                                    + Direction.deltaX(dir) * pathn1Stride;
                            int freeY = unit.tileY()
                                    + Direction.deltaY(dir) * pathn1Stride;
                            if (!world.canEnter(unit, freeX, freeY)) {
                                continue;
                            }
                            int dist = Math.max(
                                    Math.abs(pathn1Quarry.tileX() - freeX),
                                    Math.abs(pathn1Quarry.tileY() - freeY));
                            if (dist > pathn1Cur) {
                                continue;
                            }
                            int stepDx = Integer.signum(
                                    freeX - unit.tileX());
                            int stepDy = Integer.signum(
                                    freeY - unit.tileY());
                            boolean noRegress = (pathn1GoalDx == 0
                                    || stepDx != -pathn1GoalDx)
                                    && (pathn1GoalDy == 0
                                    || stepDy != -pathn1GoalDy);
                            boolean progresses = (pathn1GoalDx != 0
                                    && stepDx == pathn1GoalDx)
                                    || (pathn1GoalDy != 0
                                    && stepDy == pathn1GoalDy);
                            if (progresses && noRegress) {
                                pathn1FreeProgress = true;
                                break;
                            }
                        }
                        // A collided mobile quarry whose best target changes
                        // gives free-scan this same settle visit. Let it
                        // retarget and lay its route before a compass fallback.
                        // XHuman 12 grunt 1496 sees the knight on 30,44 here;
                        // taking N first occupied 30,38 and blocked axe 1522's
                        // cached SE later in the action cycle. Grunt 1514's
                        // best target stays the footman, so it still takes
                        // native's independent N fallback. Ordinary
                        // uncollided leftovers and building quarries keep the
                        // earlier fallback ordering (XHuman 10 grunt 1500 and
                        // Human 13 ogre 1491). A saturated paid building
                        // refill is the exception: its accepted first byte
                        // carries collision generation one into this blocked
                        // tail, where target scan owns the same callback.
                        Unit pathn1Candidate = world.targets
                                .findBattleNetHostile(unit, reactRange, null);
                        boolean saturatedPaidBuildingTailTargetChange =
                                pathn1Candidate != null
                                && pathn1Candidate != pathn1Quarry
                                && pathn1Quarry.type() != null
                                && pathn1Quarry.type().building()
                                && unit.battleNetCollisionCounter() == 1
                                && unit.battleNetRefusals() == 0
                                && unit.battleNetPathInitialLength() == 2
                                && unit.battleNetPathStepsTaken() == 1
                                && unit.battleNetAttackWrapDestArmPending();
                        boolean pathn1ChangedTarget = pathn1Candidate != null
                                && pathn1Candidate != pathn1Quarry
                                && pathn1Quarry.type() != null
                                && ((!pathn1Quarry.type().building()
                                        && unit.battleNetCollisionCounter() > 0)
                                    || saturatedPaidBuildingTailTargetChange);
                        residualPathOneSaturatedPaidBuildingTarget =
                                saturatedPaidBuildingTailTargetChange;
                        residualPathOneChangedTarget = pathn1ChangedTarget
                                && !saturatedPaidBuildingTailTargetChange;
                        if (!pathn1FreeProgress && !pathn1ChangedTarget) {
                            boolean paidAttackTailRefillPark =
                                    unit.battleNetAttackWrapDestArmPending()
                                    && unit.battleNetRetargetResidualParkRefill()
                                    && unit.battleNetRetargetResidualParkSteps()
                                            == 1
                                    && unit.battleNetCollisionCounter() > 0
                                    && unit.battleNetRefusals() == 0
                                    && pathn1Quarry.type() != null
                                    && !pathn1Quarry.type().building();
                            if (paidAttackTailRefillPark) {
                                // A paid Attack-tail replacement retains its
                                // native route-buffer ownership through every
                                // accepted residual. When the final cached byte
                                // then refuses, FUN_004379e0 advances the
                                // collision generation and parks route index
                                // twenty for this callback; NewPath belongs to
                                // the following Move visit. Human 13 ogre 1510
                                // settles SW with blocked NW at fixture 211,
                                // remains on (123,30), then redraws and commits
                                // SE on 212. Folding the park and redraw together
                                // moved it one cycle early. A cold close-range
                                // leftover has no paid Attack-tail owner and keeps
                                // the immediate refill below (XHuman 10 grunt
                                // 1500 at fixture 53).
                                world.causalTrace.event(world.cycle,
                                        "path.paid-attack-tail-refill-park",
                                        unit.id(), "target", pathn1Quarry.id(),
                                        "blocked_heading", pathn1Peek,
                                        "collision",
                                                unit.battleNetCollisionCounter());
                                unit.clearPath();
                                unit.setRouteSpent(false);
                                unit.setStepDrained(false);
                                unit.setWaitCycles(0);
                                unit.setBattleNetOrderDelay(0);
                                unit.setBattleNetChaseEmptyRouteReplan(true);
                                unit.setBattleNetRetargetResidualParkRefill(false);
                                int moveStart = world.idle
                                        .battleNetSequenceStart(unit,
                                                BattleNetSequence.MOVE_ANIMATION);
                                if (moveStart >= 0) {
                                    unit.setBattleNetSequenceOffset(moveStart);
                                    unit.setBattleNetAnimationTimer(1);
                                    unit.setBattleNetChaseStepReady(false);
                                }
                                return;
                            }
                            // A behavior-one defender already inside the
                            // expanded skirt of a multi-tile building has a
                            // route goal on that footprint, not merely on its
                            // north-west tile.  At XHuman 12 fixture 57 grunt
                            // 1358 is distance two from tower 1370's 2x2
                            // footprint although the raw tile delta is three.
                            // Native parks the blocked N leftover, refills the
                            // complete NW,NE route, and takes NW on the next
                            // visit. Treating the raw delta as a far compass
                            // fallback selected the first free S instead.
                            boolean footprintNearBuilding =
                                    unit.battleNetAiBehavior() == 1
                                    && pathn1Quarry.type() != null
                                    && pathn1Quarry.type().building()
                                    && pathn1Cur > 2
                                    && world.battleNetDistance(
                                            unit, pathn1Quarry) <= 2;
                            if (footprintNearBuilding) {
                                unit.clearPath();
                                unit.setRouteSpent(false);
                                unit.setWaitCycles(0);
                                unit.setBattleNetOrderDelay(0);
                                int collision =
                                        unit.battleNetCollisionCounter() + 1;
                                unit.setBattleNetCollisionCounter(
                                        collision > 14 ? 0 : collision);
                                unit.setBattleNetBuildingFootprintParkCollision(
                                        unit.battleNetCollisionCounter() > 0);
                                int moveStart = world.idle
                                        .battleNetSequenceStart(unit,
                                                BattleNetSequence.MOVE_ANIMATION);
                                if (moveStart >= 0) {
                                    unit.setBattleNetSequenceOffset(moveStart);
                                    unit.setBattleNetAnimationTimer(1);
                                    unit.setBattleNetChaseStepReady(false);
                                }
                                return;
                            }
                            // A sticky native refusal means this is also a
                            // buffer-refill boundary, even when the quarry is
                            // mobile. XHuman 12 grunt 1514 has already paid
                            // refusal one when its last N residual settles at
                            // fixture 41. Retail writes N,NE,SE,E... and waits
                            // one visit before consuming N; Java kept only N,
                            // so the residual which lands at 58 had no NE tail
                            // to commit. A target-changing refusal takes the
                            // separate retarget arm above and is not this case.
                            boolean refusalBackedMobileRefill =
                                    unit.battleNetAiBehavior() == 1
                                    && unit.battleNetRefusals() > 0
                                    && pathn1Quarry.type() != null
                                    && !pathn1Quarry.type().building();
                            if (refusalBackedMobileRefill) {
                                unit.clearPath();
                                unit.setRouteSpent(false);
                                unit.setWaitCycles(0);
                                unit.setBattleNetOrderDelay(0);
                                world.planTowards(unit, pathn1Quarry, true);
                                return;
                            }
                            // RI20 + free-compass first free neighbour. Full
                            // pathfind after clear returned SE (path 3134) and
                            // stepped N only at fixture 43; native N@42.
                            // Free-compass dir order matches empty-route free-
                            // detour (N first when free).
                            int freeHeading = -1;
                            for (int dir = 0; dir < Direction.COUNT; dir++) {
                                int freeX = unit.tileX()
                                        + Direction.deltaX(dir) * pathn1Stride;
                                int freeY = unit.tileY()
                                        + Direction.deltaY(dir) * pathn1Stride;
                                if (world.canEnter(unit, freeX, freeY)) {
                                    freeHeading = dir;
                                    break;
                                }
                            }
                            if (System.getenv("CHONKCRAFT_TRACE_BNE_RESIDUAL")
                                    != null) {
                                String resEnv = System.getenv(
                                        "CHONKCRAFT_TRACE_BNE_RESIDUAL").trim();
                                if ("*".equals(resEnv)
                                        || unit.id() == Integer.parseInt(
                                                resEnv)) {
                                    System.err.printf(
                                            "JBNERESPATHN1 cycle=%d unit=%d "
                                                    + "at=%d,%d freeH=%d%n",
                                            world.cycle, unit.id(),
                                            unit.tileX(), unit.tileY(),
                                            freeHeading);
                                }
                            }
                            if (freeHeading >= 0) {
                                // RI20 settle draws the replacement buffer,
                                // installs the free heading as its approved
                                // first byte, and pays one quiet refuse so the
                                // step lands on the following visit. Native
                                // slot 1496 retains nineteen bytes after its
                                // east detour at fixture 221; a complete
                                // one-byte surrogate falsely closed Move when
                                // that step's residual later retargeted.
                                unit.clearPath();
                                PathFinder.Result refill = world
                                        .planTowardsAfterRefusalBand(
                                                unit, pathn1Quarry, true);
                                if (refill == PathFinder.Result.FOUND
                                        && unit.pathLength() > 0) {
                                    unit.replacePeekHeading(freeHeading);
                                } else {
                                    unit.setPath(new PathFinder.Path(
                                            PathFinder.Result.FOUND,
                                            new int[] {freeHeading}));
                                }
                                unit.setRouteSpent(false);
                                unit.setWaitCycles(0);
                                unit.setBattleNetCollisionCounter(1);
                                unit.setBattleNetChaseEmptyRouteReplan(false);
                                unit.setBattleNetOrderDelay(0);
                                // A chaser all but on its quarry does not pay
                                // that quiet cycle: it lays a whole route and
                                // takes the first heading in the same visit.
                                // XHuman 10's grunt 1500 stands two tiles off,
                                // stores SW,SE,E,NE and steps SW on fixture 53,
                                // where this implementation used to step on 54 and stay a
                                // tile out for the rest of the run.
                                //
                                // Farther off the quiet cycle is native's own
                                // behaviour and must stay. XHuman 12's grunts
                                // at 28,38 and 30,39 are four and five tiles
                                // from their quarry: they draw here but consume
                                // the approved first byte on the next visit.
                                // Recursively consuming the refill here costs
                                // that case eleven cycles, 53 down to 42.
                                if (pathn1Cur > 2) {
                                    return;
                                }
                                unit.clearPath();
                                unit.setBattleNetChaseEmptyRouteReplan(true);
                                world.movement.moveTowards(unit, unit.target());
                            } else {
                            unit.clearPath();
                            unit.setRouteSpent(false);
                            unit.setWaitCycles(0);
                            unit.setBattleNetCollisionCounter(0);
                            unit.setBattleNetChaseEmptyRouteReplan(true);
                            unit.setBattleNetOrderDelay(0);
                            return;
                            }
                        }
                    }
                }
                if (residualBlockedBuilding
                        && residualBlockedFreeHeading >= 0) {
                    Unit blockedCandidate = world.targets
                            .findBattleNetHostile(unit, reactRange,
                                    unit.battleNetAiBehavior() == 1
                                            ? previous : null);
                    if (blockedCandidate != null
                            && blockedCandidate != previous) {
                        // Path work owns the first half of this boundary, but
                        // behavior one's target callback still runs before the
                        // heading is committed. XHuman 12 grunt 1480 first
                        // finds SW around its blocked tower residual, then
                        // upgrades tower 1483 -> footman 1478 and stores the
                        // full SW,S... replacement route in the same visit.
                        // Deferring that scan kept only a one-heading detour;
                        // when it drained at fixture 57 Java walked north while
                        // native paid the retarget residual Attack-four hold.
                        setAutoTarget(unit, blockedCandidate);
                        chased = blockedCandidate;
                        unit.setRouteSpent(false);
                        unit.setBattleNetCollisionCounter(0);
                        unit.setBattleNetChaseEmptyRouteReplan(false);
                        unit.setBattleNetChaseReplanResidualHold(true);
                        world.movement.moveTowards(unit, chased);
                        if (unit.pathLength() > 0
                                && residualBlockedPathStepsTaken == 2) {
                            // The blocked building residual has already paid
                            // this visit's path consult. The target upgrade
                            // replaces the tail, but it does not replace the
                            // compass byte which that consult accepted when
                            // the block closes the two-step opening prefix.
                            // Keep SW as the new buffer head and let the
                            // borrowed Move probe consume it now; otherwise
                            // the generic footman ray starts NE and waits an
                            // extra visit. A later residual does not transfer
                            // that compass byte: native XHuman 12 grunt 1481
                            // has spent five headings when its NE blocks, and
                            // consumes the replacement route's N rather than
                            // the free-compass W on fixture 97.
                            unit.replacePeekHeading(
                                    residualBlockedFreeHeading);
                            unit.setBattleNetChaseStepReady(true);
                        }
                    } else {
                        boolean saturatedPaidBuildingRefill =
                                unit.pathLength()
                                        == BattleNetPathFinder.MAX_PATH - 1
                                && unit.battleNetPathStepsTaken() == 1
                                && unit.battleNetRetargetResidualRoutePark()
                                && unit.battleNetRefusalHold()
                                && unit.battleNetAttackRefusalRecoveryStage()
                                        == 3
                                && previous.type() != null
                                && previous.type().building();
                        unit.clearPath();
                        if (saturatedPaidBuildingRefill) {
                            // Attack construction has parked a saturated
                            // building route at RI 20, so the following Move
                            // writer owns a complete replacement buffer rather
                            // than the one-byte free-compass surrogate used by
                            // a cold blocked residual. XHuman 12 slot 1517
                            // writes S,SW at fixture 210, consumes S on 211,
                            // and still owns SW when target scan selects the
                            // knight on fixture 227. Losing that tail bought a
                            // spurious Attack 3,2,1 construction instead of
                            // the native immediate east retarget step.
                            world.planTowards(unit, previous, true);
                            if (unit.pathLength() > 0) {
                                unit.replacePeekHeading(
                                        residualBlockedFreeHeading);
                            } else {
                                unit.setPath(new PathFinder.Path(
                                        PathFinder.Result.FOUND,
                                        new int[] {
                                                residualBlockedFreeHeading}));
                            }
                        } else {
                            unit.setPath(new PathFinder.Path(
                                    PathFinder.Result.FOUND,
                                    new int[] {residualBlockedFreeHeading}));
                        }
                        unit.setRouteSpent(false);
                        unit.setBattleNetCollisionCounter(
                                saturatedPaidBuildingRefill ? 1 : 0);
                        if (saturatedPaidBuildingRefill) {
                            // The refusal generation paid for this writer;
                            // it is not a pending refusal on the accepted S
                            // byte. Keeping RefusalHold would make the generic
                            // successful-step cleanup erase native's collision
                            // one before the retained SW tail settles.
                            unit.setBattleNetRefusalHold(false);
                        }
                        unit.setBattleNetChaseEmptyRouteReplan(false);
                        unit.setTarget(previous);
                        unit.setPathGoal(previous.tileX(), previous.tileY());
                        unit.setChasing(true);
                        chased = previous;
                        // No strict target upgrade: native leaves the residual
                        // route parked on this callback and consumes the new
                        // free-compass byte on the following Move visit.
                        // XHuman 12 grunt 1470 is still at (22,42), RI20, on
                        // fixture 107 after NE meets tower 117; it writes E and
                        // reaches (23,42) only on fixture 108. Spending E here
                        // made Java one full action visit early.
                        return;
                    }
                } else {
                // OfferNewTarget's bank is the first incumbent supplied to
                // AutoSelectTarget. A direct hit therefore wins an equal-score
                // spatial tie even when another enemy appears earlier in the
                // screen-Y index. XHuman 4 footman 1495 hits grunt 1489 on
                // fixture 68; when the grunt's ballista chase settles on 73,
                // all three adjacent footmen score 0x2003c, but the banked
                // 1495 is retained and receives the later blow. Null-seeding
                // the scan selected northern footman 1518 instead.
                //
                // Without an offer, behavior-one computer defenders retain
                // an equal-scoring building incumbent. Their native order
                // callback supplies the current goal to 0x409ff0: XHuman 12
                // grunt 1470 keeps tower 1464 at its first Move boundary,
                // then changes to the now-closer tower 1483 at the next one.
                Unit scanIncumbent = unit.offeredTarget() != null
                        ? unit.offeredTarget()
                        : unit.battleNetAiBehavior() == 1
                                && previous.type() != null
                                && previous.type().building()
                                ? previous : null;
                Unit candidate = world.targets.findBattleNetHostile(
                        unit, reactRange, scanIncumbent);
                if (candidate != null && candidate != previous) {
                    // A lethal-splash help chase does not hand its just-
                    // settled commanded route straight to automatic
                    // retargeting. Native XHuman 10 knight 1480 retains the
                    // catapult route while Attack start pays 3,2,1, then
                    // retargets the nearby grunt and first-steps on fixture
                    // 64. Ordinary commanded and autonomous chases do not
                    // inherit this one-time ownership boundary.
                    boolean splashHelpRetargetHandoff =
                            unit.battleNetPersonSplashHelpAttack()
                            && world.actionMoveWalked
                            && unit.stepDrained()
                            && !unit.isMoving()
                            && unit.pathLength() > 0
                            && unit.type() != null
                            && unit.type().maxAttackRange() <= 1
                            && !world.targets.inAttackRange(unit, candidate)
                            && onBattleNetChaseMoveBody(unit);
                    // Footmen leaving a ranged quarry use the same handoff
                    // after a settled chase residual even without person-help
                    // provenance. XHuman 4 footman 1484 drains SE at (72,63),
                    // retains its old axe target and route while Attack
                    // construction pays 3,2,1 on fixtures 83-85, then chooses
                    // the grunt and first-steps NE on 86. Installing the grunt
                    // immediately made Java take that step on 83. This is not
                    // a blanket footman or settled-melee rule: on fixture 104
                    // footman 1497 changes grunt -> grunt and immediately
                    // spends NE, queuing the next Attack behind that residual.
                    // Applying this pre-step hold there froze it through 106.
                    boolean footmanResidualRetargetHandoff =
                            PudUnitTypes.code(unit.type().ident()) == 0
                            && previous.type() != null
                            && previous.type().maxAttackRange() > 1
                            && world.actionMoveWalked
                            && unit.stepDrained()
                            && !unit.isMoving()
                            && unit.pathLength() > 0
                            && !world.targets.inAttackRange(unit, candidate)
                            && onBattleNetChaseMoveBody(unit);
                    if ((splashHelpRetargetHandoff
                            || footmanResidualRetargetHandoff)
                            && world.battleNetSequence != null) {
                        int handoffAttackStart = world.idle
                                .battleNetSequenceStart(unit,
                                        BattleNetSequence.ATTACK_ANIMATION);
                        if (handoffAttackStart >= 0) {
                            unit.setBattleNetSequenceOffset(handoffAttackStart);
                            unit.setBattleNetAnimationTimer(3);
                            unit.setBattleNetOrderDelay(2);
                            unit.setBattleNetPersonHelpRetargetHandoff(true);
                            AnimationSet set = unit.type().animationSet();
                            Animation attack = set == null ? null
                                    : set.get(AnimationSet.State.ATTACK);
                            if (attack != null
                                    && unit.animation().current() != attack) {
                                unit.animation().switchTo(attack);
                            }
                            return;
                        }
                    }
                    // Decide keep before setAutoTarget: the default arm clears
                    // the multi-step cache as soon as the target changes, which
                    // is why XHuman 12 grunt 1470's leftover E was gone by the
                    // time the keep check ran (pathn always 0) and the replan
                    // stepped N to (20,44) while native kept E to (21,45).
                    //
                    // Only equal-score spatial reorders onto another building
                    // keep the prefix when the next heading still closes. A
                    // strict score upgrade still wipes; so does a mobile-unit
                    // retarget (Human 13 ogre 1482: equal-score knight→wise-
                    // man, N closes Chebyshev 4→3 but native wipes and first-
                    // steps NW). Keeping every Chebyshev-closing leftover also
                    // made XHuman 12 grunts 1489/1492 step south at fixture 22.
                    boolean keepPrefix = false;
                    int keepHeading = -1;
                    int keepCur = -1;
                    int keepNxt = -1;
                    int keepPrevScore = -1;
                    int keepCandScore = -1;
                    int keepPathn = unit.pathLength();
                    int personHelpConsumedPrefix =
                            completedPersonHelpRetargetHandoff
                                    ? unit.battleNetPathStepsTaken() : 0;
                    boolean navalResidualRetarget =
                            unit.type().moveType()
                                    == UnitType.Movement.NAVAL
                            && world.actionMoveWalked
                            && unit.stepDrained() && !unit.isMoving();
                    boolean behaviorOneStrictBuildingHeading = false;
                    // A settled multi-heading chase that has just paid a
                    // complete cooperative refusal band reaches target scan
                    // on Move-start/1 with its collision provenance intact.
                    // Native draws the replacement ray through friends that
                    // moved during that band. XHuman 10 grunt 1490 therefore
                    // stores E,E,SE toward the new knight and takes E on the
                    // wake visit; the ordinary short-corridor compensation
                    // wall-followed SW instead.
                    boolean completedSettledRefusalBand = keepPathn > 1
                            && unit.type() != null
                            && "unit-grunt".equals(unit.type().ident())
                            && (unit.battleNetPathStepsTaken() >= 2
                                    || (unit.battleNetRefusalHold()
                                            && unit.battleNetRefusals() > 0))
                            && unit.stepDrained() && !unit.isMoving()
                            && unit.battleNetCollisionCounter() > 0
                            && unit.battleNetAnimationTimer() == 1
                            && onBattleNetChaseMoveBody(unit);
                    boolean settledBlockedTail = keepPathn == 1
                            && world.actionMoveWalked
                            && unit.stepDrained()
                            && !unit.isMoving()
                            && !world.canEnter(unit,
                                    unit.tileX() + Direction.deltaX(
                                            unit.peekHeading())
                                            * world.battleNetMovementStride(unit),
                                    unit.tileY() + Direction.deltaY(
                                            unit.peekHeading())
                                            * world.battleNetMovementStride(unit));
                    // A ranged stride that drained on this visit is no longer
                    // a live route to tear down. XHuman 10 axethrower 1496
                    // reaches the end of E on fixture 55 with four cached
                    // headings, changes footman -> knight, writes E,E,SE and
                    // spends the first E in that same visit. Charging the
                    // ordinary three-visit ranged teardown cleared the route;
                    // the allied grunt then stepped west into the free cell
                    // and every subsequent replan refused against it.
                    boolean settledRangedResidual =
                            World.battleNetRangedChaseUnit(unit)
                            && world.actionMoveWalked
                            && unit.stepDrained()
                            && !unit.isMoving()
                            && !world.targets.inAttackRange(unit, candidate);
                    // A melee replacement selected on the visit which drains
                    // the old chase residual draws its new ray through moving
                    // allies before testing the first heading. XHuman 10
                    // grunt 1475 writes E,NE,E,E through the allied traffic
                    // at fixture 57 and then refuses E for a Move timer band.
                    // Re-hardening the short approach corridor produced an
                    // empty Java route, losing both the native ray and its
                    // cooperative-refusal ownership.
                    boolean settledMeleeResidualRetarget =
                            !World.battleNetRangedChaseUnit(unit)
                            && unit.stepDrained()
                            && !unit.isMoving()
                            && keepPathn > 0
                            && (world.actionMoveWalked
                                    || unit.battleNetRetargetResidualRoutePark())
                            && !world.targets.inAttackRange(unit, candidate);
                    boolean saturatedSettledResidualRetarget =
                            settledMeleeResidualRetarget
                            && unit.battleNetCollisionCounter() >= 4
                            && unit.battleNetRefusals() == 0;
                    boolean saturatedBuildingQuarryRetarget =
                            saturatedSettledResidualRetarget
                            && unit.battleNetPathInitialLength()
                                    == BattleNetPathFinder.MAX_PATH
                            && unit.battleNetPathStepsTaken() == 1
                            && unit.battleNetCollisionCounter() >= 5
                            && previous.type() != null
                            && previous.type().building()
                            && candidate.type() != null
                            && !candidate.type().building()
                            && world.canEnter(unit,
                                    unit.tileX() + Direction.deltaX(
                                            unit.peekHeading())
                                            * world.battleNetMovementStride(
                                                    unit),
                                    unit.tileY() + Direction.deltaY(
                                            unit.peekHeading())
                                            * world.battleNetMovementStride(
                                                    unit));
                    boolean incumbentPlanBeforePaidRefillRetarget =
                            settledMeleeResidualRetarget
                            && unit.battleNetRetargetResidualParkRefill()
                            && unit.battleNetRetargetResidualParkSteps() == 1
                            && unit.battleNetPathStepsTaken() >= 3
                            && unit.battleNetCollisionCounter() == 0
                            && unit.battleNetRefusals() == 0
                            && previous.type() != null
                            && !previous.type().building()
                            && candidate.type() != null
                            && candidate.type().building();
                    // A fully consumed route has no cached heading, but its
                    // last residual can settle on the same visit target scan
                    // installs a replacement. One cooperative collision still
                    // belongs to the old route: native uses it while drawing
                    // NewPath and clears it when the replacement head commits.
                    // XHuman 12 slot 1453 carries collision one through c67,
                    // changes tower to knight and steps SE on c68, then exposes
                    // collision zero. Keeping it made a following fighter treat
                    // this visibly moving ally as a hard wall at c124.
                    boolean spentSingleCollisionResidualRetarget =
                            !World.battleNetRangedChaseUnit(unit)
                            && unit.stepDrained()
                            && !unit.isMoving()
                            && keepPathn == 0
                            && world.actionMoveWalked
                            && unit.battleNetPathInitialLength() > 0
                            && unit.battleNetCollisionCounter() == 1
                            && unit.battleNetRefusals() == 0
                            && !world.targets.inAttackRange(unit, candidate);
                    int expiredParkMoveStart = world.battleNetSequence == null
                            ? -1 : world.idle.battleNetSequenceStart(
                                    unit, BattleNetSequence.MOVE_ANIMATION);
                    boolean expiredHarvestParkRetarget =
                            !World.battleNetRangedChaseUnit(unit)
                            && unit.stepDrained() && !unit.isMoving()
                            && keepPathn == 0
                            && unit.battleNetCollisionCounter() == 1
                            && unit.battleNetRefusals() <= 1
                            && previous.order() == Unit.Order.HARVEST
                            && !world.targets.validAttackTarget(unit, previous)
                            && !previous.isOnMap()
                            && !world.targets.inAttackRange(unit, candidate)
                            && expiredParkMoveStart >= 0
                            && unit.battleNetSequenceOffset()
                                    == expiredParkMoveStart
                            && unit.battleNetAnimationTimer() == 1;
                    boolean saturatedFirstStepBuildingRetargetPark =
                            settledMeleeResidualRetarget
                            && keepPathn
                                    == BattleNetPathFinder.MAX_PATH - 1
                            && unit.battleNetPathInitialLength()
                                    == BattleNetPathFinder.MAX_PATH
                            && unit.battleNetPathStepsTaken() == 1
                            && unit.battleNetCollisionCounter() == 1
                            && unit.battleNetRefusals() == 0
                            && previous.type() != null
                            && !previous.type().building()
                            && candidate.type() != null
                            && candidate.type().building();
                    clearCollisionAfterSettledRetargetStep =
                            spentSingleCollisionResidualRetarget
                            || expiredHarvestParkRetarget
                            || (settledMeleeResidualRetarget
                                && unit.battleNetCollisionCounter() > 0
                                && unit.battleNetRefusals() == 0
                                && (unit.battleNetPathStepsTaken() == 1
                                        || saturatedSettledResidualRetarget));
                    clearRefusalsAfterSettledRetargetStep =
                            expiredHarvestParkRetarget;
                    boolean settledResidualHeadFree = keepPathn > 0
                            && world.canEnter(unit,
                                    unit.tileX() + Direction.deltaX(
                                            unit.peekHeading())
                                            * world.battleNetMovementStride(unit),
                                    unit.tileY() + Direction.deltaY(
                                            unit.peekHeading())
                                            * world.battleNetMovementStride(unit));
                    // Consuming the only byte closes the old Move program.
                    // If target scan replaces its quarry on that residual-settle
                    // visit, native first constructs Attack 3,2,1 and only then
                    // draws the replacement route. Longer exhausted routes stay
                    // live and may replace immediately. XHuman 12 slot 1470
                    // changes tower to footman on c124 and first-steps N on c127.
                    boolean spentOneStepRetargetConstruction =
                            !World.battleNetRangedChaseUnit(unit)
                            && unit.stepDrained()
                            && !unit.isMoving()
                            && keepPathn == 0
                            && world.actionMoveWalked
                            && unit.battleNetPathInitialLength() == 1
                            && unit.battleNetPathStepsTaken() == 1
                            && !world.targets.inAttackRange(unit, candidate);
                    // The in-range form closes the Move program at the same
                    // boundary, but has no replacement route to defer behind
                    // construction. Install Attack 3,2,1 immediately and arm
                    // the ordinary post-approach body hold. This is based on
                    // the completed movement visit, not the original route's
                    // length, when a mobile quarry is replaced by a building:
                    // XHuman 10 ogre 1543 drains its one south heading before
                    // replacing footman 1529 with tower 1537, while ogre 1548
                    // consumes a later heading from a longer cached route
                    // before making the same replacement. Both expose
                    // 643/3,2,1 and then 643/23. Mobile-to-mobile replacement
                    // remains on its proved direct handoff; constructing that
                    // class regresses XHuman 10 at fixture 59.
                    boolean settledInRangeBuildingRetargetConstruction =
                            !World.battleNetRangedChaseUnit(unit)
                            && unit.stepDrained()
                            && !unit.isMoving()
                            && world.actionMoveWalked
                            && unit.battleNetRefusals() == 0
                            && previous.type() != null
                            && !previous.type().building()
                            && candidate.type() != null
                            && candidate.type().building()
                            && world.targets.inAttackRange(unit, candidate);
                    int replacementPreviousScore =
                            world.targets.battleNetTargetScore(unit, previous);
                    int replacementCandidateScore =
                            world.targets.battleNetTargetScore(unit, candidate);
                    // Behavior-one guards keep a compact approved mobile route
                    // until a strictly better combatant is offered. If that
                    // upgrade arrives on a settled residual after at least two
                    // headings, native tears down the incumbent tail through
                    // land Still and re-enters Attack 3,2,1 before replanning.
                    // XHuman 12 slot 1468 / Java 132 upgrades footman ->
                    // knight at fixture 119 and owns the idle draw which fixes
                    // the following melee damage assignments.
                    boolean behaviorOneStrictMobileUpgradeConstruction =
                            settledMeleeResidualRetarget
                            && unit.battleNetAiBehavior() == 1
                            && unit.battleNetPathStepsTaken() >= 2
                            && unit.battleNetPathInitialLength()
                                    < BattleNetPathFinder.MAX_PATH
                            && unit.battleNetCollisionCounter() < 4
                            && unit.offeredTarget() != null
                            && unit.offeredTarget() != previous
                            && unit.offeredTarget() != candidate
                            && previous.type() != null
                            && !previous.type().building()
                            && candidate.type() != null
                            && !candidate.type().building()
                            && (replacementCandidateScore >>> 16)
                                    == (replacementPreviousScore >>> 16)
                            && replacementCandidateScore
                                    > replacementPreviousScore;
                    // A target replacement reached at a pressured residual
                    // boundary owns a fresh Attack construction before the
                    // replacement route may be drawn.  Pressure is visible
                    // either directly in the collision nibble, or in the
                    // route-park marker left by a completed replan-residual
                    // construction.  Native clears the stale route and
                    // installs the new target immediately, then pays 3,2,1:
                    // XHuman 12 grunts 1513 (collided residual) and 1516
                    // (paid route park) are the two forms of the same handoff.
                    // An ordinary unpressured residual retarget still lays
                    // its replacement immediately; that is the behavior
                    // proved by XHuman 12 grunt 1495 and Human 13 ogre 1482.
                    boolean collisionOwnsRetargetConstruction =
                            ((((unit.battleNetRefusals() == 0
                                            && unit.battleNetPathStepsTaken()
                                                    >= 2
                                            && keepPathn <= 6)
                                    || (unit.battleNetRefusals() > 0
                                            && (keepPathn < 6
                                                    || unit
                                                            .battleNetCollisionCounter()
                                                            >= 3)))
                                // Pressure has to own the route, not merely be
                                // nearby. An unrefused compact route earns
                                // ownership after two consumed headings; a
                                // long retained tail is redrawn immediately
                                // (XHuman 12 slot 1503 retains fourteen bytes
                                // when it switches to the guard tower at
                                // fixture 252). A hard-refused route earns it
                                // in the compact buffer or paid collision band.
                                // Generations four and above have already paid
                                // and commit directly.
                                && unit.battleNetCollisionCounter() > 1
                                && unit.battleNetCollisionCounter() < 4
                                && (!settledResidualHeadFree
                                        || unit.battleNetRefusals() > 0))
                            || (unit.battleNetRetargetResidualRoutePark()
                                    && unit.battleNetCollisionCounter() == 0
                                    && keepPathn < 19
                                    && candidate.type() != null
                                    && candidate.type().building()
                                    && settledResidualHeadFree));
                    boolean longPaidTailRetargetRemainsLive =
                            settledMeleeResidualRetarget
                            && unit.battleNetPathInitialLength()
                                    == BattleNetPathFinder.MAX_PATH
                            && keepPathn >= 14
                            && unit.battleNetPathStepsTaken()
                                    == BattleNetPathFinder.MAX_PATH - keepPathn
                            && unit.battleNetCollisionCounter() == 3
                            && unit.battleNetRefusals() > 0
                            && previous.type() != null
                            && !previous.type().building()
                            && candidate.type() != null
                            && candidate.type().building()
                            && settledResidualHeadFree;
                    boolean repeatedPaidLongTailRetarget =
                            longPaidTailRetargetRemainsLive
                            && unit.battleNetRefusals() > 1;
                    boolean pressuredResidualRetargetConstruction =
                            (spentOneStepRetargetConstruction
                                || behaviorOneStrictMobileUpgradeConstruction
                                || (settledMeleeResidualRetarget
                                    && keepPathn > 1
                                    && collisionOwnsRetargetConstruction
                                    && !longPaidTailRetargetRemainsLive))
                            && world.battleNetSequence != null;
                    int oldReplacementHeading = keepPathn > 0
                            ? unit.peekHeading() : -1;
                    int oldReplacementX = keepPathn > 0
                            ? unit.tileX()
                                    + Direction.deltaX(oldReplacementHeading)
                                            * world.battleNetMovementStride(unit)
                            : unit.tileX();
                    int oldReplacementY = keepPathn > 0
                            ? unit.tileY()
                                    + Direction.deltaY(oldReplacementHeading)
                                            * world.battleNetMovementStride(unit)
                            : unit.tileY();
                    Unit oldReplacementBlocker = keepPathn > 0
                            ? world.unitAt(oldReplacementX, oldReplacementY)
                            : null;
                    boolean replacementThroughCollidedMover =
                            settledMeleeResidualRetarget
                            && keepPathn == 2
                            && oldReplacementBlocker != null
                            && world.isAllied(unit.player(),
                                    oldReplacementBlocker.player())
                            && oldReplacementBlocker
                                    .battleNetCollisionCounter() > 0
                            && world.movement
                                    .battleNetRefusalBandSoftClearMoveAlly(
                                            oldReplacementBlocker);
                    if (unit.pathLength() > 1
                            && previous.type().building()
                            && candidate.type().building()) {
                        int prevScore = world.targets.battleNetTargetScore(unit, previous);
                        int candScore = world.targets.battleNetTargetScore(unit, candidate);
                        keepPrevScore = prevScore;
                        keepCandScore = candScore;
                        if (candScore > 0 && (candScore == prevScore
                                || unit.battleNetAiBehavior() == 1)) {
                            int heading = unit.peekHeading();
                            keepHeading = heading;
                            int nextX = unit.tileX()
                                    + Direction.deltaX(heading)
                                    * world.battleNetMovementStride(unit);
                            int nextY = unit.tileY()
                                    + Direction.deltaY(heading)
                                    * world.battleNetMovementStride(unit);
                            int cur = world.battleNetDistance(unit, candidate);
                            int targetWidth = Math.max(1,
                                    candidate.type().tileWidth());
                            int targetHeight = Math.max(1,
                                    candidate.type().tileHeight());
                            int nearX = World.battleNetNearFootprintCoordinate(
                                    nextX, candidate.tileX(), targetWidth);
                            int nearY = World.battleNetNearFootprintCoordinate(
                                    nextY, candidate.tileY(), targetHeight);
                            int nxt = Math.max(Math.abs(nearX - nextX),
                                    Math.abs(nearY - nextY));
                            keepCur = cur;
                            keepNxt = nxt;
                            boolean closes = nxt < cur;
                            keepPrefix = closes && candScore == prevScore;
                            // Behavior-one's current goal breaks an equal tie,
                            // but a strict score upgrade still changes target.
                            // Its already-approved next compass element is
                            // handed to the replacement route, though. XHuman
                            // 12 grunt 1470 upgrades tower 1464 -> 1483 at
                            // fixture 41 and takes its cached NE; rebuilding
                            // solely from Java occupancy chose E and diverged
                            // immediately. The replacement tail starts with N,
                            // which is what native consumes after the residual
                            // Attack-four handoff.
                            behaviorOneStrictBuildingHeading = closes
                                    && unit.battleNetAiBehavior() == 1
                                    && candScore > prevScore;
                        }
                    }
                    if (System.getenv("CHONKCRAFT_TRACE_BNE_KEEP") != null) {
                        String keepEnv = System.getenv("CHONKCRAFT_TRACE_BNE_KEEP")
                                .trim();
                        boolean keepLog = "*".equals(keepEnv)
                                || unit.id() == Integer.parseInt(keepEnv);
                        if (keepLog) {
                            System.err.printf("JBNEKEEP cycle=%d unit=%d "
                                            + "prev=%d cand=%d pathn=%d "
                                            + "head=%d cur=%d nxt=%d "
                                            + "ps=%x cs=%x keep=%d "
                                            + "at=%d,%d%n",
                                    world.cycle, unit.id(),
                                    previous == null ? -1 : previous.id(),
                                    candidate.id(), keepPathn,
                                    keepHeading, keepCur, keepNxt,
                                    keepPrevScore, keepCandScore,
                                    keepPrefix ? 1 : 0,
                                    unit.tileX(), unit.tileY());
                        }
                    }
                    if (keepPrefix) {
                        setAutoTarget(unit, candidate, true);
                        chased = candidate;
                        unit.setPathGoal(candidate.tileX(),
                                candidate.tileY());
                    } else {
                        if (pressuredResidualRetargetConstruction) {
                            // A pressured residual handoff reaches the target
                            // scan through the land Still dispatcher. Preserve
                            // its async choice and rare facing nudge without
                            // running target selection twice (XHuman 12 slots
                            // 1513/1516 at fixture 70). Completed refusal bands
                            // are different: native slots 1476 and 1514 lay
                            // their replacement routes at fixtures 73/74
                            // without entering Still or owning an async draw.
                            world.idle.battleNetLandIdleChoice(unit);
                        }
                        setAutoTarget(unit, candidate);
                        chased = candidate;
                        unit.setRouteSpent(false);
                        if (dyingQuarryCompletedRefusalConstructor
                                && previous == completedRefusalConstructorGoal
                                && candidate.isAlive() && !candidate.isDying()
                                && !world.targets.inAttackRange(
                                        unit, candidate)) {
                            // Stage five finished against a quarry which
                            // entered Die before its timer-one Move probe.
                            // AutoSelectTarget still publishes the replacement
                            // on this visit, but retail starts a fresh cold
                            // Attack constructor before NewPath may inspect
                            // that replacement. Reusing the dead quarry's
                            // completed constructor made the empty-route
                            // cooperative scan immediately take the first free
                            // non-progressing compass square (XHuman 12 slot
                            // 1453: N on fixture 239). This cold active-order
                            // handoff also pays the land-idle choice before
                            // Attack restarts. The sealed cycle-239 ledger
                            // shows the same call for slots 1453, 1456 and
                            // 1457; omitting all three shifted footman 1449's
                            // next damage roll from retail's four to eight.
                            world.idle.advanceBattleNetActiveOrderIdleRandom(
                                    unit);
                            unit.clearPath();
                            unit.setRouteSpent(false);
                            unit.setWaitCycles(0);
                            unit.setBattleNetOrderDelay(0);
                            unit.setPathGoal(
                                    candidate.tileX(), candidate.tileY());
                            int retargetAttackStart = world.idle
                                    .battleNetSequenceStart(unit,
                                            BattleNetSequence.ATTACK_ANIMATION);
                            if (retargetAttackStart >= 0) {
                                unit.setBattleNetSequenceOffset(
                                        retargetAttackStart);
                                unit.setBattleNetAnimationTimer(3);
                                unit.setBattleNetAttackRefusalRecoveryStage(5);
                                unit.setBattleNetColdNoProgressRefusalLoop(true);
                                AnimationSet set = unit.type().animationSet();
                                Animation attack = set == null ? null
                                        : set.get(AnimationSet.State.ATTACK);
                                if (attack != null
                                        && unit.animation().current()
                                                != attack) {
                                    unit.animation().switchTo(attack);
                                }
                            }
                            return;
                        }
                        if (saturatedFirstStepBuildingRetargetPark) {
                            // A target switch after the first committed byte
                            // of a saturated route parks the replacement
                            // transaction before it may probe that route. The
                            // retained first collision owns Attack 3,2,1 on
                            // the following callback; releasing a fresh byte
                            // on the landing visit is one tile early. XHuman
                            // 12 grunt 1492 drains NE on fixture 230, changes
                            // knight -> guard tower, stays on (29,36), then
                            // exposes Attack 2539/3 on fixture 231.
                            unit.clearPath();
                            unit.setPathGoal(
                                    chased.tileX(), chased.tileY());
                            unit.setBattleNetRetargetResidualParkRefill(true);
                            unit.setBattleNetRetargetResidualParkSteps(1);
                            unit.setBattleNetChaseReplanResidualHold(true);
                            unit.setBattleNetChaseEmptyRouteReplan(true);
                            unit.setWaitCycles(0);
                            unit.setBattleNetOrderDelay(0);
                            int moveStart = world.idle
                                    .battleNetSequenceStart(unit,
                                            BattleNetSequence.MOVE_ANIMATION);
                            if (moveStart >= 0) {
                                unit.setBattleNetSequenceOffset(moveStart);
                                unit.setBattleNetAnimationTimer(1);
                                unit.setBattleNetChaseStepReady(false);
                            }
                            return;
                        }
                        if (settledInRangeBuildingRetargetConstruction) {
                            int retargetAttackStart = world.idle
                                    .battleNetSequenceStart(unit,
                                            BattleNetSequence.ATTACK_ANIMATION);
                            if (retargetAttackStart >= 0) {
                                unit.setBattleNetSequenceOffset(
                                        retargetAttackStart);
                                unit.setBattleNetAnimationTimer(3);
                                unit.setBattleNetSequenceMeleeLanded(false);
                                unit.setBattleNetAttackResumeFromMove(true);
                                unit.setBattleNetAttackOp0OutOfRange(true);
                                AnimationSet set = unit.type().animationSet();
                                Animation attack = set == null ? null
                                        : set.get(AnimationSet.State.ATTACK);
                                if (attack != null
                                        && unit.animation().current() != attack) {
                                    unit.animation().switchTo(attack);
                                }
                                world.turnToTarget(unit, candidate, 0, 0);
                                return;
                            }
                        }
                        if (pressuredResidualRetargetConstruction) {
                            int retargetAttackStart = world.idle
                                    .battleNetSequenceStart(unit,
                                            BattleNetSequence.ATTACK_ANIMATION);
                            if (retargetAttackStart >= 0) {
                                unit.setBattleNetCollisionCounter(0);
                                unit.setBattleNetChaseReplanResidualHold(false);
                                unit.setBattleNetRetargetResidualRoutePark(false);
                                unit.setBattleNetSequenceOffset(
                                        retargetAttackStart);
                                unit.setBattleNetAnimationTimer(3);
                                unit.setBattleNetOrderDelay(2);
                                // This existing handoff state ticks Attack
                                // construction even while the new quarry is
                                // still out of range, then returns to the
                                // ordinary target/route decision on timer one.
                                unit.setBattleNetPersonHelpRetargetHandoff(true);
                                AnimationSet set = unit.type().animationSet();
                                Animation attack = set == null ? null
                                        : set.get(AnimationSet.State.ATTACK);
                                if (attack != null
                                        && unit.animation().current() != attack) {
                                    unit.animation().switchTo(attack);
                                }
                                return;
                            }
                        }
                        // A ranged chase that free-scans directly onto an
                        // in-range quarry has completed the same approach
                        // handoff as Move -> Attack. Retail discards the old
                        // route and pays the post-approach OP0 hold after
                        // construction, regardless of whether the replacement
                        // is mobile or a building. XHuman 12 axe 1521 names
                        // the guard tower from 37,37 and remains on 887/63;
                        // treating it as a cold in-range first swing launched
                        // a phantom axe fourteen cycles early.
                        boolean rangedInRangeChaseRetarget =
                                World.battleNetRangedChaseUnit(unit)
                                && world.targets.inAttackRange(unit, candidate);
                        if (rangedInRangeChaseRetarget) {
                            unit.setBattleNetAttackResumeFromMove(true);
                            unit.setBattleNetAttackOp0OutOfRange(true);
                            unit.setBattleNetRangedFreeScanHoldPending(true);
                        }
                        // A melee chase whose old route is already parked has
                        // also arrived from Move, even though the replacement
                        // quarry is adjacent and no new heading is needed.
                        // Native preserves that action ownership across the
                        // fresh 3,2,1 constructor and then parks Attack start
                        // for bodyWaitSum-1. XHuman 4 grunt 1520 changes from
                        // the ballista to footman 1497 on fixture 107 and is
                        // Attack@2539/23 on fixture 110; treating it as a cold
                        // standing swing dealt a phantom blow on fixture 120.
                        boolean meleeInRangeParkedChaseRetarget =
                                !World.battleNetRangedChaseUnit(unit)
                                && unit.battleNetRetargetResidualRoutePark()
                                && unit.stepDrained() && !unit.isMoving()
                                // Approach damage already owns this exact
                                // post-construction hold. Do not arm a second
                                // owner behind it when the hit offer wins the
                                // retarget (XHuman 4 grunt 1520's southern
                                // sibling is the paired one-hold witness).
                                && !unit.battleNetAttackOp0Damaged()
                                && world.targets.inAttackRange(unit, candidate)
                                && (candidate.type() == null
                                        || !candidate.type().building());
                        if (meleeInRangeParkedChaseRetarget) {
                            unit.setBattleNetAttackResumeFromMove(true);
                            unit.setBattleNetAttackOp0OutOfRange(true);
                        }
                        // Ranged retarget at the end of Move: native arms
                        // attack animation four with timer 3 before the new
                        // first step. Human 13 axethrower 1505 replanned onto
                        // (120,29) and stepped SW on fixture 25 while native
                        // held through 27 and stepped only at 28. Delay 2 +
                        // this quiet visit = three holds.
                        //
                        // The hold is what tearing up a live route costs, and
                        // it is paid before the replacement is laid, not after.
                        // Native's 1505 keeps its old route bytes at fixture 25
                        // and only moves the cursor to 20 -- the past-the-end
                        // mark -- then writes [SW,S,S,SW,S] and spends its
                        // first heading in the single visit at 28. Laying the
                        // new route at the same time as the hold left 1505 with
                        // a live route when the wise-man came back into reach
                        // at 28, so the second retarget bought a second hold
                        // and the diagonal landed at fixture 31 instead of 28.
                        // A chaser that is already routeless has nothing to
                        // tear up, so it replans and steps on the one visit.
                        // A single blocked tail after the previous residual
                        // settled is exhausted for the same purpose: XHuman
                        // 12 axethrower 1529 has stale S left against the tower
                        // crowd at fixture 54, retargets the footman and spends
                        // the replacement SW immediately. Charging the generic
                        // ranged hold left it frozen through fixture 56.
                        if (World.battleNetRangedChaseUnit(unit)
                                && keepPathn > 0 && !settledBlockedTail
                                && !settledRangedResidual) {
                            unit.clearPath();
                            unit.setBattleNetOrderDelay(2);
                            return;
                        }
                        // Melee (and any non-ranged) retarget that tore up a
                        // live multi-step route: residual of the first new
                        // heading ends with Attack-four timer 3 before the
                        // second heading (Human 13 ogre 1482). Ranged already
                        // held before the first step above.
                        // Settled-retarget Attack-four before the first new
                        // heading was tried for XHuman 12 grunt 1495 and
                        // rejected: it pulled Human 13 ogre 1482 and other
                        // grunts earlier (fixture 19).
                        if ((keepPathn > 0
                                || completedPersonHelpRetargetHandoff
                                || unit.battleNetRetargetResidualRoutePark()
                                || navalResidualRetarget
                                || expiredHarvestParkRetarget)
                                && !World.battleNetRangedChaseUnit(unit)) {
                            // A completed queued-Attack handoff may have
                            // exhausted its retained route before this new
                            // target is installed. RetargetResidualRoutePark
                            // is the same ownership after the ordinary replan
                            // hold has completed. In either case the newly
                            // queued Attack belongs behind the replacement's
                            // first residual: XHuman 4 grunt 1505 promotes at
                            // fixtures 105-107 after first-stepping SW on 89.
                            // Person warships use the same order ownership
                            // even when the old route was a single spent
                            // heading. XOrc 11 destroyer 1519 changes ships
                            // while its southwest residual settles at fixture
                            // 127, first-steps northwest, then exposes Attack
                            // 3266/3,2,1 before spending the retained west
                            // heading on fixture 162.
                            unit.setBattleNetChaseReplanResidualHold(true);
                            if (expiredHarvestParkRetarget) {
                                // The expired CUnitPtr's replacement route is
                                // followed by a queued Attack promotion. This
                                // is native next_order=Attack while the farm
                                // route drains; its first residual therefore
                                // counts Attack 3,2,1 directly before the next
                                // target/route decision.
                                unit.setBattleNetAttackWrapDestArmPending(true);
                            }
                            unit.setBattleNetSaturatedRetargetRouteBand(
                                    saturatedSettledResidualRetarget);
                        } else {
                            unit.setBattleNetSaturatedRetargetRouteBand(false);
                            // Exhausted route retarget (keepPathn 0): same
                            // empty-route free-detour seam as the pathLength
                            // == 0 arm below (XHuman 12 grunt 1507).
                            unit.setBattleNetChaseEmptyRouteReplan(true);
                            if (settledRangedResidual && keepPathn > 0) {
                                // A ranged target switch reached by draining the
                                // old approach does not pay the ordinary tear-down
                                // before its replacement ray. It first commits one
                                // heading from that ray, then pays Attack-four when
                                // the new residual settles and parks the remaining
                                // buffer. XHuman 12 axethrower 1523 changes footman
                                // -> knight at fixture 88, takes E, holds 104..106,
                                // parks at 107, and replans SE on 108. Continuing
                                // directly into the second cached E made crowded
                                // ranged lines overrun their targets and appear to
                                // ignore the combat handoff.
                                unit.setBattleNetChaseReplanResidualHold(true);
                            }
                            // When this retarget is reached by draining the
                            // old route's final residual, the first new step
                            // still owes native Attack-four after its pixels
                            // settle. The marker used to be armed only when
                            // that step was a compass detour. XHuman 12 grunt
                            // 1507 now gets the same N from the new route
                            // itself, but native still holds c52-c54 before E.
                            if (world.actionMoveWalked
                                    && unit.stepDrained()
                                    && !unit.isMoving()
                                    && unit.type() != null
                                    && unit.type().maxAttackRange() <= 1
                                    && !World.battleNetRangedChaseUnit(unit)) {
                                unit.setBattleNetEmptyRouteFreeDetourHold(true);
                            }
                        }
                        if (residualPathOneChangedTarget
                                && unit.battleNetCollisionCounter() == 1) {
                            world.planTowards(unit, chased, true);
                            // Retargeting from a settled refused leftover
                            // transfers back to Move start with native timer
                            // 15 before any replacement heading is spent.
                            // This is the other half of XHuman 12 grunt 1496:
                            // merely yielding to free-scan still let it take
                            // the new route's first step on fixture 41.
                            unit.setBattleNetChaseReplanResidualHold(false);
                            unit.setBattleNetOrderDelay(14);
                            int moveStart = world.idle
                                    .battleNetSequenceStart(unit,
                                            BattleNetSequence.MOVE_ANIMATION);
                            if (moveStart >= 0) {
                                unit.setBattleNetSequenceOffset(moveStart);
                                unit.setBattleNetAnimationTimer(15);
                                unit.setBattleNetChaseStepReady(false);
                            }
                            return;
                        }
                        if (residualPathOneChangedTarget
                                && unit.battleNetCollisionCounter() > 1) {
                            // The first collision-backed target change owns the
                            // complete Move band above. A later generation has
                            // already paid that transfer: native clears the
                            // collision nibble and may spend the replacement's
                            // first byte on this settle visit. XHuman 12 slot
                            // 1500 reaches its second collision with W blocked,
                            // changes footman -> knight, writes an E-led route
                            // and commits E at fixture 107 (raw collision byte
                            // 0x20 -> 0x00). Reapplying the first-generation
                            // fifteen-count hold left it occupying 34,40 and in
                            // turn blocked slot 1503's authentic east handoff.
                            unit.setBattleNetCollisionCounter(0);
                        }
                        if (completedSettledRefusalBand) {
                            if (repeatedPaidLongTailRetarget) {
                                planRepeatedPaidLongTailRetarget(
                                        unit, chased);
                                unit.setBattleNetCollisionCounter(0);
                                unit.setBattleNetRetargetResidualRoutePark(
                                        false);
                                stepMoveTowardsTarget(unit);
                                return;
                            }
                            boolean retainPaidMobileWallFace =
                                    unit.battleNetRefusals() > 0
                                    && chased.type() != null
                                    && !chased.type().building();
                            if (retainPaidMobileWallFace) {
                                world.planTowardsAfterCompletedRefusalBandRetarget(
                                        unit, chased, true);
                            } else {
                                world.planTowardsAfterRefusalBand(
                                        unit, chased, false);
                            }
                            if (world.actionMoveWalked
                                    && settledResidualHeadFree
                                    && oldReplacementHeading >= 0
                                    && unit.battleNetRefusals() == 0
                                    // A completed-band handoff into a mobile
                                    // quarry owns the freshly drawn route.
                                    // Only a building keeps the approved old
                                    // cardinal wall face; a diagonal remains
                                    // transferable because it is already the
                                    // formation's two-axis approach. XHuman 4
                                    // grunt 1520 changes footman rows with
                                    // collision two: native discards old W and
                                    // consumes the replacement NW at fixture
                                    // 69. Keeping W stranded the assault line
                                    // one row south. XHuman 12 grunt 1476's
                                    // old S toward a tower remains the building
                                    // form below.
                                    && (Direction.isDiagonal(
                                                    oldReplacementHeading)
                                            || (chased.type() != null
                                                    && chased.type()
                                                            .building()))
                                    && unit.pathLength() > 0) {
                                // On the residual-settle visit, the approved
                                // next byte belongs to Move before the target
                                // scan owns the replacement tail. Retail grunt
                                // 1476 therefore consumes its old S onto
                                // (22,46) at fixture 73 while replacing the
                                // footman with the cannon tower and writing a
                                // fresh route behind it. Taking the new ray's
                                // NE head instead jumped to (23,44). This is
                                // A hard-refused head has lost that approval:
                                // grunt 1514 discards SE for the fresh SW ray
                                // at fixture 74. This is also distinct from a
                                // route parked on an earlier paid-band visit:
                                // that cardinal head is stale and may be
                                // discarded (grunt 1500 at fixture 56), while
                                // only its authenticated diagonal transfer is
                                // handled below.
                                unit.replacePeekHeading(
                                        oldReplacementHeading);
                                // Collision two transfers a cardinal head into
                                // two bytes of the shared route buffer when the
                                // settled retarget is a building. This is the
                                // handoff boundary -- the raw pathfinder route
                                // still begins NE,N before the approved old S
                                // replaces it. XHuman 12 native slot 1476
                                // stores S,S through Attack construction and
                                // steps south again at fixture 92; preserving
                                // the untouched second N reversed the grunt.
                                if (unit.battleNetCollisionCounter() == 2
                                        && chased.type() != null
                                        && chased.type().building()
                                        && !Direction.isDiagonal(
                                                oldReplacementHeading)
                                        && unit.pathLength() > 1) {
                                    int stride = world
                                            .battleNetMovementStride(unit);
                                    int secondX = unit.tileX()
                                            + Direction.deltaX(
                                                    oldReplacementHeading)
                                            * stride * 2;
                                    int secondY = unit.tileY()
                                            + Direction.deltaY(
                                                    oldReplacementHeading)
                                            * stride * 2;
                                    if (world.canEnter(unit,
                                            secondX, secondY)) {
                                        unit.replacePeekHeadingAfterNext(
                                                oldReplacementHeading);
                                    }
                                }
                            }
                            // Collision owns this one completed-band handoff:
                            // it decides whether the approved old head (and,
                            // for collision two against a building, its second
                            // byte) is transferred into the replacement ray.
                            // NewPath clears that nibble after consuming it.
                            // Retail XHuman 12 grunt 1476 is 0x20 through the
                            // first handoff at fixture 72 and 0x00 from fixture
                            // 73 onward. Keeping Java at two let the same stale
                            // NW be transferred again at fixture 108 instead
                            // of taking the replacement route's SE, sending a
                            // live attacker backwards through its formation.
                            unit.setBattleNetCollisionCounter(0);
                        } else if (settledMeleeResidualRetarget) {
                            // This route is probed later in the same attack
                            // callback. Movement needs to distinguish its
                            // immediate cooperative refusal from a route whose
                            // first replacement leg already settled: native
                            // pays Move 15 now and Attack construction after
                            // the band, not one combined seventeen-visit
                            // surrogate delay.
                            world.actionSettledMeleeReplacementRoute = true;
                            boolean replacementAfterPaidBand = unit
                                    .battleNetRetargetResidualRoutePark();
                            world.actionSettledMeleeReplacementAfterPaidBand =
                                    replacementAfterPaidBand;
                            if (repeatedPaidLongTailRetarget) {
                                planRepeatedPaidLongTailRetarget(
                                        unit, chased);
                            } else if (incumbentPlanBeforePaidRefillRetarget) {
                                // A route which was parked and then refilled
                                // owns one final NewPath transaction against
                                // its incumbent before target scan publishes
                                // the replacement. The new target pointer and
                                // goal change now, but the already-approved
                                // compass buffer survives the handoff.
                                Unit replacement = chased;
                                unit.setTarget(previous);
                                world.planTowardsRetainingFirstWallFace(
                                        unit, previous);
                                unit.setTarget(replacement);
                                unit.setPathGoal(replacement.tileX(),
                                        replacement.tileY());
                            } else if (replacementAfterPaidBand
                                    || replacementThroughCollidedMover) {
                                // The paid-band route draw uses native's
                                // broader moving-ally view. A just-settled
                                // replacement uses that same view only when
                                // collision-elevated departing friends made
                                // the ordinary ray falsely empty: the field
                                // is restored before the first heading is
                                // probed, so it can still refuse and buy its
                                // own band (XHuman 10 grunts 1490 and 1497).
                                boolean saturatedParkedRetarget =
                                        replacementAfterPaidBand
                                        && unit.battleNetCollisionCounter()
                                                >= 5
                                        && unit.battleNetRefusals() > 0;
                                if (saturatedParkedRetarget) {
                                    // A route parked after several formation
                                    // collisions and at least one hard refusal
                                    // has already paid its cooperative wait.
                                    // Its Attack-construction retarget sees the
                                    // formation as a hard wall and may spend the
                                    // replacement head immediately. Soft-
                                    // clearing moving allies drew a blocked
                                    // south route and bought a second band,
                                    // leaving packed melee units visibly idle.
                                    world.planTowards(unit, chased, true);
                                } else {
                                    world.planTowardsAfterRefusalBand(
                                            unit, chased,
                                            (Direction.isDiagonal(
                                                    oldReplacementHeading)
                                                    && (keepPathn
                                                            >= BattleNetPathFinder
                                                                    .MAX_PATH
                                                                    - 1
                                                            || chased.type()
                                                                    != null
                                                                    && chased
                                                                            .type()
                                                                            .building()))
                                                    || (keepPathn >= 19
                                                            && chased.type()
                                                                    != null
                                                            && chased.type()
                                                                    .building()));
                                }
                                world.actionSettledMeleeReplacementBroadRoute =
                                        replacementThroughCollidedMover;
                                if (replacementAfterPaidBand
                                        && settledResidualHeadFree
                                        && unit.battleNetRefusals() == 0
                                        && Direction.isDiagonal(
                                                oldReplacementHeading)
                                        // The transferable diagonal is the
                                        // head of a saturated twenty-byte
                                        // wall buffer. XHuman 12 slot 1508
                                        // carries its initial-length-20 NE
                                        // face (19 bytes remain) through the
                                        // paid park at fixture 72. Slot 1492
                                        // has only three old bytes left at
                                        // fixture 194; native discards that
                                        // stale SE and keeps the fresh E-led
                                        // replacement route.
                                        && keepPathn >= BattleNetPathFinder
                                                .MAX_PATH - 1
                                        && !(chased.type() != null
                                                && chased.type().building()
                                                && unit
                                                        .battleNetCollisionCounter()
                                                        >= 5)
                                        && unit.pathLength() > 0) {
                                    // Native transfers an already-approved
                                    // diagonal through a paid park while it
                                    // redraws the tail for a new quarry
                                    // (XHuman 12 grunt 1508 keeps NE and
                                    // moves on fixture 72). A cardinal does
                                    // not transfer: grunt 1500 discards E and
                                    // takes the replacement SW on fixture 56.
                                    // Treating both alike either strands 1508
                                    // in a false refusal or sends 1500 away.
                                    // A saturated building retarget is the
                                    // bounded-wall exception: XHuman 12 slot
                                    // 1492's freshly written S,SE,SE,SW route
                                    // already contains the paid face at
                                    // fixture 175. Replacing S with its stale
                                    // southwest tail moves one tile left of
                                    // native on the handoff visit.
                                    unit.replacePeekHeading(
                                            oldReplacementHeading);
                                }
                            } else if (residualPathOneSaturatedPaidBuildingTarget) {
                                int direct = World
                                        .battleNetFirstBresenhamHeading(
                                                unit.tileX(), unit.tileY(),
                                                chased.tileX(),
                                                chased.tileY());
                                int inheritedTurn = Math.floorMod(
                                        oldReplacementHeading - direct,
                                        Direction.COUNT);
                                int turnSign = inheritedTurn == 1 ? 1
                                        : inheritedTurn
                                                == Direction.COUNT - 1
                                                        ? -1 : 0;
                                int side = Math.floorMod(
                                        direct - 2 * turnSign,
                                        Direction.COUNT);
                                int approach = Math.floorMod(
                                        direct - turnSign,
                                        Direction.COUNT);
                                int stride = world
                                        .battleNetMovementStride(unit);
                                int sideX = unit.tileX()
                                        + Direction.deltaX(side) * stride;
                                int sideY = unit.tileY()
                                        + Direction.deltaY(side) * stride;
                                if (!Direction.isDiagonal(direct)
                                        && turnSign != 0
                                        && world.canEnter(unit,
                                                sideX, sideY)) {
                                    // A saturated building refill owns its
                                    // two-byte wall buffer through the later
                                    // mobile retarget. The blocked cardinal
                                    // ray reuses the inherited adjacent face
                                    // as the far end of a bounded arc: two
                                    // lateral bytes, then the three compass
                                    // faces back to that inheritance. XHuman
                                    // 12 slot 1517 converts retained SW into
                                    // E,E,SE,S,SW and consumes the first E on
                                    // fixture 227. A fresh optimizer instead
                                    // chose the opposite W,SW,SE,E face.
                                    unit.setPath(new PathFinder.Path(
                                            PathFinder.Result.FOUND,
                                            new int[] {
                                                    oldReplacementHeading,
                                                    direct, approach,
                                                    side, side}));
                                } else {
                                    world.planTowards(unit, chased, true);
                                }
                            } else if (saturatedBuildingQuarryRetarget) {
                                // A saturated building chase which replaces
                                // its quarry on residual settlement continues
                                // the already-paid clockwise wall face. The
                                // continuation uses native's Move-action view:
                                // a live-route ally with raw collision zero is
                                // soft to the wall trace even when Java's
                                // separate refusal proxy remains, but stays
                                // hard to optimization and the later movement
                                // probe. XHuman 12 slot 1492 therefore writes
                                // the twenty-byte E-led route on fixture 271,
                                // then refuses its occupied east head and
                                // starts Move 15. A cold redraw chose S,SE and
                                // moved immediately.
                                world.planTowardsAfterSaturatedBuildingRetarget(
                                        unit, chased);
                                // Target replacement retires the saturated
                                // building generation before Move tests the
                                // continued head. Native changes raw 0x80 to
                                // 0x10 on the occupied east probe: zero for
                                // the new quarry, then the first refusal.
                                unit.setBattleNetCollisionCounter(0);
                            } else {
                                world.planTowards(unit, chased, true);
                            }
                            // A strict behavior-one building upgrade inherits
                            // the already-approved first compass element even
                            // when the old residual route drained on this
                            // visit. The generic replacement branch does this
                            // below; native applies the same handoff to the
                            // settled-melee replacement route.
                            if (behaviorOneStrictBuildingHeading
                                    && keepHeading >= 0
                                    && unit.pathLength() > 0) {
                                unit.replacePeekHeading(keepHeading);
                            }
                            if (longPaidTailRetargetRemainsLive
                                    && unit.pathLength() > 0
                                    && !unit.isMoving()) {
                                // A paid long tail stays inside the live Move
                                // transaction when target scan replaces its
                                // mobile quarry with a building. The fresh
                                // route head belongs to this same callback,
                                // not the next scheduler visit. Independent
                                // XHuman 12 witnesses retain fourteen and
                                // sixteen old bytes before committing E and
                                // SW respectively.
                                stepMoveTowardsTarget(unit);
                            }
                            // The old route-park provenance was needed while
                            // choosing the replacement ray, but must not park
                            // that newly installed ray as though it were the
                            // stale one. Its immediate refusal is served by
                            // the scoped replacement-route handoff above.
                            unit.setBattleNetRetargetResidualRoutePark(false);
                        } else {
                            world.movement.moveTowards(unit, chased);
                            if (personHelpConsumedPrefix > 0) {
                                // The splash-help Attack 3,2,1 handoff changes
                                // quarry but does not rewind BNE's route-buffer
                                // cursor. Java redraws the headings to match
                                // current occupancy, so explicitly carry the
                                // consumed prefix across that redraw. XHuman
                                // 10 knight 1480 is cursor two after its new
                                // NW stride and residual-opens immediately;
                                // treating it as a fresh cursor-one route
                                // charged another Attack construction and
                                // made the fighter look frozen for three
                                // simulation visits.
                                unit.carryBattleNetPathStepsTaken(
                                        personHelpConsumedPrefix);
                            }
                            if (behaviorOneStrictBuildingHeading
                                    && keepHeading >= 0
                                    && unit.pathLength() > 0) {
                                unit.replacePeekHeading(keepHeading);
                            }
                        }
                    }
                } else if (unit.pathLength() == 0) {
                    int landPatrolAttackCollision =
                            unit.battleNetLandPatrolAttackRoutePending()
                                    ? unit.battleNetCollisionCounter() : -1;
                    int parkedRefusalHeading =
                            unit.battleNetParkedRefusalHeading();
                    boolean paidWrapRouteParked =
                            unit.hasBattleNetLongPaidWrapParkedRoute();
                    boolean paidWrapRouteRedraw = paidWrapRouteParked
                            && unit.battleNetLongPaidWrapParkedTailLength() == 0;
                    int saturatedWallFacePairHeading =
                            unit.battleNetSaturatedWallFacePairHeading();
                    boolean saturatedWallFacePairParked =
                            saturatedWallFacePairHeading >= 0
                            && unit.battleNetSaturatedWallFacePairParked();
                    if (saturatedWallFacePairParked) {
                        if (world.actionMoveWalked) {
                            return;
                        }
                        // The opposite wall-face byte is physically written,
                        // but its cursor remains parked beyond the native
                        // route buffer for the complete refusal band. Model
                        // that logical cursor, not the otherwise unreadable
                        // raw byte: exposing this as a live one-heading Java
                        // path makes the timer-one wake spend a park visit
                        // before it can redraw. XHuman 12 slot 1506 writes SW
                        // at fixture 153 with route index twenty, then redraws
                        // NW and moves as timer one wakes on fixture 168.
                        unit.clearPath();
                        unit.setRouteSpent(false);
                        int collision =
                                unit.battleNetCollisionCounter() + 1;
                        unit.setBattleNetCollisionCounter(
                                collision > 14 ? 0 : collision);
                        unit.setWaitCycles(0);
                        unit.setBattleNetOrderDelay(14);
                        unit.setBattleNetRefusalHold(true);
                        unit.setBattleNetChaseEmptyRouteReplan(true);
                        unit.setBattleNetSaturatedWallFacePairHeading(-1);
                        int moveStart = world.idle
                                .battleNetSequenceStart(unit,
                                        BattleNetSequence.MOVE_ANIMATION);
                        if (moveStart >= 0) {
                            unit.setBattleNetSequenceOffset(moveStart);
                            unit.setBattleNetAnimationTimer(15);
                            unit.setBattleNetChaseStepReady(false);
                        }
                        return;
                    }
                    boolean sameQuarryCollisionRefill =
                            unit.battleNetRetargetResidualParkRefill()
                            && unit.battleNetCollisionCounter() >= 4
                            && unit.battleNetRefusals() == 0
                            && unit.battleNetRetargetResidualParkSteps() == 2;
                    boolean directRefusalRecoveryProbe =
                            unit.battleNetDirectRefusalRecoveryProbe()
                            && unit.battleNetAttackRefusalRecoveryStage() == 6
                            && unit.target() != null
                            && unit.target().isAlive()
                            && !world.targets.inAttackRange(
                                    unit, unit.target())
                            && unit.chasing();
                    if (directRefusalRecoveryProbe) {
                        Unit retryTarget = unit.target();
                        int heading = World.battleNetFirstBresenhamHeading(
                                unit.tileX(), unit.tileY(),
                                retryTarget.tileX(), retryTarget.tileY());
                        int stride = world.battleNetMovementStride(unit);
                        boolean validHeading = heading >= 0
                                && heading < Direction.COUNT;
                        int directX = validHeading ? unit.tileX()
                                + Direction.deltaX(heading) * stride
                                : unit.tileX();
                        int directY = validHeading ? unit.tileY()
                                + Direction.deltaY(heading) * stride
                                : unit.tileY();
                        boolean accepted = validHeading
                                && world.canEnter(unit, directX, directY);
                        world.causalTrace.event(world.cycle,
                                "path.direct-refusal-recovery-probe", unit.id(),
                                "target", retryTarget.id(),
                                "heading", heading,
                                "accepted", accepted ? 1 : 0,
                                "x", directX, "y", directY);
                        unit.clearPath();
                        unit.setRouteSpent(false);
                        unit.setWaitCycles(0);
                        unit.setBattleNetOrderDelay(0);
                        unit.setBattleNetChaseEmptyRouteReplan(false);
                        if (!accepted) {
                            int refusalGeneration = Math.max(
                                    unit.battleNetDirectRecoveryGeneration(),
                                    unit.battleNetRefusals()) + 1;
                            unit.setBattleNetDirectRecoveryGeneration(
                                    refusalGeneration);
                            if (unit.battleNetSaturatedNearRecoveryFullRoute()) {
                                // A paid recovery byte that has already closed
                                // to range two gets one naked direct retry.
                                // Once that face refuses, the next completed
                                // Attack construction belongs to the complete
                                // wall-route writer. Repeating the direct face
                                // here can leave a melee unit frozen forever
                                // behind the same formation square.
                                unit.setBattleNetDirectRefusalRecoveryProbe(
                                        false);
                                if (!rearmBattleNetHardRefusalAttack(unit)) {
                                    unit.setBattleNetAttackRefusalRecoveryStage(
                                            0);
                                    unit.setBattleNetSaturatedNearRecoveryFullRoute(
                                            false);
                                }
                                return;
                            }
                            if (refusalGeneration >= 8) {
                                // Direct Attack-recovery probes are one
                                // bounded refusal generation. On the eighth
                                // rejected face retail writes a complete wall
                                // route and pays Move 15..1 before allowing
                                // that route to advance. XHuman 12 slot 1501
                                // repeats the direct south probe, writes the
                                // retained route at fixture 125, and remains
                                // on (30,41) through fixture 140.
                                world.planTowardsAfterRefusalBand(
                                        unit, retryTarget);
                                unit.setBattleNetDirectRefusalRecoveryProbe(
                                        false);
                                unit.setBattleNetDirectRefusalReplacementBand(
                                        true);
                                unit.setBattleNetAttackRefusalRecoveryStage(4);
                                unit.setBattleNetOrderDelay(14);
                                int moveStart = world.idle
                                        .battleNetSequenceStart(unit,
                                                BattleNetSequence.MOVE_ANIMATION);
                                if (moveStart >= 0) {
                                    unit.setBattleNetSequenceOffset(moveStart);
                                    unit.setBattleNetAnimationTimer(15);
                                    unit.setBattleNetChaseStepReady(false);
                                }
                                return;
                            }
                            if (!rearmBattleNetHardRefusalAttack(unit)) {
                                unit.setBattleNetAttackRefusalRecoveryStage(0);
                                unit.setBattleNetDirectRefusalRecoveryProbe(false);
                            }
                            return;
                        }
                        unit.setPath(new PathFinder.Path(
                                PathFinder.Result.FOUND,
                                new int[] {heading}));
                        unit.setPathGoal(
                                retryTarget.tileX(), retryTarget.tileY());
                        unit.setBattleNetDirectRefusalRecoveryProbe(false);
                        unit.setBattleNetSaturatedNearRecoveryFullRoute(false);
                    }
                    boolean paidOneStepRefusalApproachSettled =
                            world.actionMoveWalked
                            && unit.stepDrained() && !unit.isMoving()
                            && unit.routeSpent()
                            && unit.battleNetPathStepsTaken() == 1
                            && unit.battleNetCollisionCounter() == 0
                            && (unit.battleNetRefusals() > 0
                                    || unit.battleNetDirectRecoveryGeneration()
                                            > 0)
                            && unit.battleNetAttackRefusalRecoveryStage() == 0
                            && !unit.battleNetChaseEmptyRouteReplan()
                            && unit.target() != null
                            && unit.target().isAlive()
                            && !world.targets.inAttackRange(
                                    unit, unit.target())
                            && unit.type() != null
                            && unit.type().maxAttackRange() <= 1
                            && !World.battleNetRangedChaseUnit(unit)
                            && unit.chasing()
                            && (unit.order() == Unit.Order.ATTACK
                                    || unit.order() == Unit.Order.ATTACK_MOVE);
                    if (paidOneStepRefusalApproachSettled) {
                        // A one-byte route admitted by the hard-refusal retry
                        // loop remains owned by that loop when its residual
                        // finishes. Retail does not generate and refuse a new
                        // wall-follow route on the settle visit. It parks the
                        // exhausted cursor and enters Attack construction
                        // 3,2,1 before granting Move one
                        // fresh probe. XHuman 12 grunt 1501 takes SW when the
                        // cell opens at fixture 88, settles at 104, and exposes
                        // Attack 2539/3 there; an immediate Java refill cached
                        // a north route through the formation and stepped away
                        // at 105. A diagonal byte which still carries a hard-
                        // refusal generation is different. Slot 1495 settles
                        // SW at fixture 174, advances raw collision one to two,
                        // writes its full twenty-byte route, and pays Move
                        // 15..1 before consuming N at fixture 189. It does not
                        // enter Attack construction or consume 0040AD58.
                        // Cardinal slot 1504 at 156 and the refusal-free SW of
                        // slot 1501 at 104 do enter active-order Still and own
                        // that draw.
                        Unit retryTarget = unit.target();
                        world.causalTrace.event(world.cycle,
                                "path.paid-one-step-refusal-settle", unit.id(),
                                "target", retryTarget.id(),
                                "refusals", unit.battleNetRefusals(),
                                "last_heading", unit.lastStepHeading());
                        int settledHeading = unit.lastStepHeading();
                        boolean retainedAttackPromotion =
                                unit.battleNetRefusals() > 0
                                && settledHeading >= 0
                                && (settledHeading & 1) != 0;
                        unit.clearPath();
                        unit.setWaitCycles(0);
                        unit.setBattleNetOrderDelay(0);
                        unit.setBattleNetChaseEmptyRouteReplan(false);
                        if (retainedAttackPromotion) {
                            // The paid diagonal remains under Move ownership.
                            // Native advances its single packed refusal nibble
                            // before NewPath and retains generation two through
                            // the accepted N residual. Clear Java's auxiliary
                            // hard-refusal projection so timer-one does not arm
                            // RefusalHold and erase that native generation when
                            // the heading commits.
                            int collision = Math.max(
                                    unit.battleNetCollisionCounter(),
                                    unit.battleNetRefusals()) + 1;
                            unit.setBattleNetCollisionCounter(
                                    Math.min(14, collision));
                            unit.setBattleNetRefusals(0);
                            unit.setBattleNetDirectRecoveryGeneration(0);
                            unit.setBattleNetDirectRefusalRecoveryProbe(false);
                            unit.setBattleNetAttackRefusalRecoveryStage(0);
                            unit.setBattleNetRefusalHold(false);
                            world.planTowards(unit, retryTarget, true);
                            unit.setBattleNetOrderDelay(14);
                            int moveStart = world.idle
                                    .battleNetSequenceStart(unit,
                                            BattleNetSequence.MOVE_ANIMATION);
                            if (moveStart >= 0) {
                                unit.setBattleNetSequenceOffset(moveStart);
                                unit.setBattleNetAnimationTimer(15);
                                unit.setBattleNetChaseStepReady(false);
                            }
                            return;
                        }
                        unit.setBattleNetDirectRefusalRecoveryProbe(true);
                        if (rearmBattleNetHardRefusalAttack(
                                unit, false)) {
                            return;
                        }
                    }
                    boolean saturatedResidualFaceRetry =
                            unit.battleNetSaturatedResidualFaceRetry()
                            && unit.stepDrained() && !unit.isMoving()
                            && unit.battleNetCollisionCounter() >= 3
                            && unit.battleNetCollisionCounter() < 5
                            && unit.battleNetRefusals() >= 2
                            && unit.battleNetPathStepsTaken() == 0
                            && unit.target() != null
                            && unit.type() != null
                            && unit.type().maxAttackRange() <= 1
                            && !World.battleNetRangedChaseUnit(unit)
                            && !unit.battleNetChaseEmptyRouteReplan();
                    if (saturatedResidualFaceRetry) {
                        // After a nearly-full residual route hard-parks, retail
                        // retries the direct wall face as two naked collision
                        // visits before drawing another full twenty-byte ray.
                        // Those visits leave Move-start/1 and route_index 20;
                        // they do not convert the already-sticky hard refusal
                        // into a fresh fifteen-count cooperative wait. Formation
                        // traffic can therefore clear before the real refill.
                        // XHuman 12 grunt 1495 parks its direct S face at
                        // fixtures 103 and 104 (collision 3->4->5), then sees
                        // the vacated north-west cell and commits NW on 105.
                        // Planning immediately at collision three cached an E
                        // ray through traffic and slept fifteen, which is the
                        // characteristic permanent-looking battle freeze.
                        int collision =
                                unit.battleNetCollisionCounter() + 1;
                        unit.setBattleNetCollisionCounter(collision);
                        world.causalTrace.event(world.cycle,
                                "path.saturated-residual-face-retry", unit.id(),
                                "collision", collision,
                                "refusals", unit.battleNetRefusals(),
                                "heading",
                                        World.battleNetFirstBresenhamHeading(
                                                unit.tileX(), unit.tileY(),
                                                unit.target().tileX(),
                                                unit.target().tileY()),
                                "target", unit.target().id());
                        unit.setRouteSpent(false);
                        unit.clearPath();
                        unit.setWaitCycles(0);
                        unit.setBattleNetOrderDelay(0);
                        return;
                    }
                    boolean rangedCollidedResidualRefill =
                            world.actionMoveWalked
                            && unit.stepDrained()
                            && !unit.isMoving()
                            && unit.battleNetCollisionCounter() > 0
                            && World.battleNetRangedChaseUnit(unit)
                            && !unit.battleNetChaseEmptyRouteReplan();
                    boolean saturatedDiagonalRouteTerminator =
                            world.actionMoveWalked
                            && unit.stepDrained()
                            && !unit.isMoving()
                            && unit.routeSpent()
                            && unit.battleNetCollisionCounter() >= 5
                            && unit.battleNetRefusals() >= 2
                            && Direction.isDiagonal(unit.lastStepHeading())
                            && !World.battleNetRangedChaseUnit(unit)
                            && !unit.battleNetChaseEmptyRouteReplan();
                    if (saturatedDiagonalRouteTerminator) {
                        // The diagonal owns both formation axes through the
                        // exhausted-buffer boundary. Native advances collision
                        // 0x50 -> 0x60 and parks route index 20 for this visit;
                        // the following active-order callback clears pressure
                        // and enters Attack construction 3,2,1. Refilling here
                        // made the surrounded attacker slide through its line.
                        int collision =
                                unit.battleNetCollisionCounter() + 1;
                        unit.setBattleNetCollisionCounter(
                                collision > 14 ? 0 : collision);
                        unit.clearPath();
                        unit.setRouteSpent(false);
                        unit.setStepDrained(false);
                        unit.setWaitCycles(0);
                        unit.setBattleNetOrderDelay(0);
                        unit.setBattleNetChaseEmptyRouteReplan(true);
                        unit.setBattleNetSaturatedResidualFaceRetry(true);
                        unit.setBattleNetResidualEmptyApproachIdlePending(true);
                        int moveStart = world.idle.battleNetSequenceStart(unit,
                                BattleNetSequence.MOVE_ANIMATION);
                        if (moveStart >= 0) {
                            unit.setBattleNetSequenceOffset(moveStart);
                            unit.setBattleNetAnimationTimer(1);
                            unit.setBattleNetChaseStepReady(false);
                        }
                        return;
                    }
                    boolean stickyCollisionThreeCardinalTerminator =
                            world.actionMoveWalked
                            && unit.stepDrained() && !unit.isMoving()
                            && unit.routeSpent()
                            && unit.battleNetPathInitialLength() == 1
                            && unit.battleNetPathStepsTaken() == 1
                            && unit.battleNetCollisionCounter() == 3
                            && unit.battleNetRefusals() == 1
                            && !Direction.isDiagonal(unit.lastStepHeading())
                            && unit.target() != null
                            && unit.target().isAlive()
                            && unit.target().type() != null
                            && !unit.target().type().building()
                            && !World.battleNetRangedChaseUnit(unit);
                    if (stickyCollisionThreeCardinalTerminator) {
                        // A one-byte cardinal replacement can finish with one
                        // hard refusal still attached to collision generation
                        // three. Retail advances that generation to four and
                        // parks RI 20 for this visit; Move OP0 writes and
                        // consumes the next route on the following callback.
                        // XHuman 12 grunt 1495 settles south on fixture 222
                        // and first-steps south-east on fixture 223.
                        unit.setBattleNetCollisionCounter(4);
                        unit.clearPath();
                        unit.setRouteSpent(false);
                        unit.setStepDrained(false);
                        unit.setWaitCycles(0);
                        unit.setBattleNetOrderDelay(0);
                        unit.setBattleNetChaseEmptyRouteReplan(true);
                        int moveStart = world.idle.battleNetSequenceStart(unit,
                                BattleNetSequence.MOVE_ANIMATION);
                        if (moveStart >= 0) {
                            unit.setBattleNetSequenceOffset(moveStart);
                            unit.setBattleNetAnimationTimer(1);
                            unit.setBattleNetChaseStepReady(false);
                        }
                        return;
                    }
                    // A collided route's final residual owns this visit. The
                    // following Move OP0 lays the refill; it does not share
                    // the residual-settle visit. XHuman 12 grunt 1503 drains
                    // E on fixture 55 with collision one and route index 20,
                    // then plans S,SW,SW,SE on 56. Combining both visits let
                    // Java route around the moving ally and step SE at 55.
                    boolean paidCollisionFourDeferredRefill =
                            unit.battleNetPaidLongResidualRefill()
                            && !world.actionMoveWalked
                            && unit.stepDrained() && !unit.isMoving()
                            && unit.battleNetChaseEmptyRouteReplan()
                            && unit.battleNetCollisionCounter() == 4
                            && unit.battleNetRefusals() == 0
                            && unit.target() != null
                            && unit.target().isAlive()
                            && unit.target().type() != null
                            && !unit.target().type().building()
                            && unit.type() != null
                            && unit.type().maxAttackRange() <= 1
                            && !World.battleNetRangedChaseUnit(unit);
                    boolean paidCollisionFourImmediateRefill =
                            (world.actionMoveWalked
                            && unit.stepDrained() && !unit.isMoving()
                            && unit.routeSpent()
                            && unit.battleNetCollisionCounter() == 4
                            && unit.battleNetRefusals() == 0)
                            || paidCollisionFourDeferredRefill;
                    if (world.actionMoveWalked
                            && unit.stepDrained()
                            && !unit.isMoving()
                            && unit.battleNetCollisionCounter() > 0
                            // The first refused generation owns no extra
                            // route-index-20 settle visit: it may refill as
                            // the one-byte stride drains (XHuman 12 slot 1517,
                            // collision 0x10, N on fixture 111).  A later
                            // generation does park before refilling (slot
                            // 1503, native 0x40 -> 0x50 at fixture 55).
                            // Java's refusal count carries that generation
                            // distinction only while the collision projection
                            // is in its ordinary band. A saturated cardinal
                            // retry is allowed to refill as that probe settles:
                            // XHuman 12 slot 1506 replaces its lone E with a
                            // complete route and commits E on fixture 103. A
                            // diagonal residual keeps both collision axes owned
                            // by the formation, however: slots 1495 (NW, fixture
                            // 121) and 1506 (SE, fixture 152) advance their cursor
                            // to 20 before the next action construction/refill.
                            // Synthetic and cold residuals have no reconstructed
                            // count; retain the ordinary park for zero, and
                            // exclude only the authenticated first generation or
                            // saturated cardinal retry generation.
                            && unit.battleNetRefusals() != 1
                            && !World.battleNetRangedChaseUnit(unit)
                            && !(unit.routeSpent()
                                    && unit.battleNetCollisionCounter() >= 5
                                    && unit.battleNetRefusals() >= 2
                                    && !Direction.isDiagonal(
                                            unit.lastStepHeading()))
                            // A route whose final residual was explicitly
                            // parked for the post-retarget refill owns NewPath
                            // on this same action.  The native Move OP0 drains
                            // the last pixels, writes route index twenty, lays
                            // the replacement ray and lets FUN_004379e0 refuse
                            // its occupied head before returning.  Deferring
                            // the generic collided-route refill below by one
                            // visit loses refusal one and shifts the complete
                            // recovery band (XHuman 10 slot 1475, fixture 182).
                            && !unit.battleNetRetargetResidualParkRefill()
                            // Generation four with no hard-refusal history has
                            // already paid the cooperative formation band. Its
                            // exhausted diagonal refills on this settle visit;
                            // later generations with sticky refusals retain
                            // the park above. XHuman 12 slot 1482 commits NE
                            // on fixture 127 instead of waiting and taking N.
                            && !paidCollisionFourImmediateRefill
                            && !unit.battleNetLandPatrolAttackRoutePending()
                            && !unit.battleNetChaseEmptyRouteReplan()) {
                        unit.setRouteSpent(false);
                        unit.clearPath();
                        unit.setStepDrained(false);
                        unit.setBattleNetChaseEmptyRouteReplan(true);
                        return;
                    }
                    // Route terminator: rebuild without the generic ten-cycle
                    // empty-route wait. A deferred collided refill keeps
                    // moving allies soft while drawing the new route, so its
                    // direct first heading can enter the native cooperative
                    // refusal machine instead of being routed around them.
                    boolean collidedResidualRefill =
                            (unit.battleNetChaseEmptyRouteReplan()
                                    || paidCollisionFourImmediateRefill)
                            && unit.battleNetCollisionCounter() > 0;
                    unit.setRouteSpent(false);
                    unit.clearPath();
                    if (candidate != null) {
                        chased = candidate;
                        unit.setTarget(chased);
                        if (sameQuarryCollisionRefill) {
                            int direct = World
                                    .battleNetFirstBresenhamHeading(
                                            unit.tileX(), unit.tileY(),
                                            chased.tileX(), chased.tileY());
                            int stride = world
                                    .battleNetMovementStride(unit);
                            boolean validDirect = direct >= 0
                                    && direct < Direction.COUNT;
                            int directX = validDirect ? unit.tileX()
                                    + Direction.deltaX(direct) * stride
                                    : unit.tileX();
                            int directY = validDirect ? unit.tileY()
                                    + Direction.deltaY(direct) * stride
                                    : unit.tileY();
                            if (validDirect
                                    && world.canEnter(
                                            unit, directX, directY)) {
                                // The next generation is a direct one-byte
                                // probe, not another optimized twenty-byte
                                // wall route. XHuman 12 slot 1482 parks its
                                // blocked NE tail at fixture 110, then stores
                                // only SE and commits it on 111. A full search
                                // preferred the free north wall and broke the
                                // same formation the collision park protects.
                                unit.setPath(new PathFinder.Path(
                                        PathFinder.Result.FOUND,
                                        new int[] {direct}));
                                unit.setPathGoal(
                                        chased.tileX(), chased.tileY());
                            } else {
                                world.planTowardsAfterRefusalBand(
                                        unit, chased);
                            }
                        }
                    }
                    // Mark empty-route rebuild so a soft-cleared first step
                    // free-detours instead of cooperative-waiting fourteen
                    // (XHuman 12 grunt 1507 residual SE exhaust → replan).
                    unit.setBattleNetChaseEmptyRouteReplan(true);
                    if (sameQuarryCollisionRefill) {
                        // The generation-specific one-byte probe was installed
                        // above. Do not immediately overwrite it with the
                        // ordinary optimized wall route.
                    } else if (paidCollisionFourImmediateRefill) {
                        // Preserve the already-paid moving-ally view and the
                        // first wall face written into BNE's route buffer. The
                        // deferred form is the same boundary split over two
                        // callbacks: XHuman 12 grunt 1504 parks its blocked
                        // nineteen-byte residual on fixture 245, then runs this
                        // paid writer and consumes NW on 246.
                        // This is the formation-wide collision-four boundary,
                        // not a unit- or map-specific path exception.
                        world.planTowardsAfterRefusalBand(
                                unit, chased, true);
                        unit.setBattleNetPaidLongResidualRefill(false);
                    } else if (unit.battleNetRetargetResidualParkRefill()
                            && unit.battleNetRetargetResidualParkSteps() == 1
                            && (unit.battleNetCollisionCounter() == 1
                                    || paidWrapRouteRedraw
                                    || (chased.type() != null
                                            && chased.type().building()
                                            && unit.battleNetCollisionCounter()
                                                    <= 6))
                            && unit.battleNetRefusals() == 0
                            && !World.battleNetRangedChaseUnit(unit)
                            && chased.type() != null) {
                        // A paid one-step residual has already admitted the
                        // allied body which owned its cooperative refusal.
                        // Its first replacement writer runs before that body
                        // has settled, so retail still treats the mover as a
                        // wall and routes around it. XHuman 12 grunt 1492 is
                        // the sealed witness: after the east residual parks,
                        // the moving formation mate on 29,37 makes the fresh
                        // route begin NE instead of Java's soft E probe. The
                        // building form can revisit the same paid writer while
                        // its direct footprint face is occupied; each naked
                        // retry must redraw that retained face instead of
                        // falling back to the ordinary free west wall.
                        PathFinder.Path retainedPaidWrapTail = unit
                                .takeBattleNetLongPaidWrapParkedTail();
                        if (retainedPaidWrapTail != null) {
                            // Native parks the cursor, not the route bytes.
                            // The following NewPath visit resumes behind the
                            // discarded stale head: XHuman 12 slot 1517 keeps
                            // SE,SE,SW after parking E at fixture 262.
                            unit.setPath(retainedPaidWrapTail);
                        } else if (paidWrapRouteRedraw) {
                            // A compact completed-wrap tail that is refused
                            // after its committed first stride returns to the
                            // paid wall writer. Unlike the first post-wrap
                            // replay above, no stale bytes remain to restore;
                            // collision ownership selects native's retained
                            // clockwise face. XHuman 12 slot 1517 writes the
                            // southeast-led full buffer on fixture 280.
                            world.planTowardsAfterPaidWrapPark(
                                    unit, chased);
                        } else {
                            world.planTowardsAfterRetargetPark(unit, chased);
                        }
                        if (chased.type().building()
                                && unit.pathLength() > 0) {
                            int nearX = World.battleNetNearFootprintCoordinate(
                                    unit.tileX(), chased.tileX(),
                                    Math.max(1,
                                            chased.type().tileWidth()));
                            int nearY = World.battleNetNearFootprintCoordinate(
                                    unit.tileY(), chased.tileY(),
                                    Math.max(1,
                                            chased.type().tileHeight()));
                            int footprintHeading = World
                                    .battleNetFirstBresenhamHeading(
                                            unit.tileX(), unit.tileY(),
                                            nearX, nearY);
                            if (footprintHeading >= 0
                                    && footprintHeading < Direction.COUNT) {
                                unit.replacePeekHeading(footprintHeading);
                            }
                        }
                    } else if (completedPersonHelpRetargetHandoff
                            && !World.battleNetRangedChaseUnit(unit)
                            && chased.type() != null
                            && !chased.type().building()) {
                        // The Attack 3,2,1 handoff was entered from a
                        // pressured melee residual. Its timer-one route draw
                        // retains native's cooperative view of allied movers,
                        // just like a completed refusal band. XHuman 12 grunt
                        // 1513 therefore redraws SW after grunt 1510 vacates
                        // (34,40); the hardened ordinary ray chose SE and sent
                        // the two attackers away from one another.
                        world.planTowardsAfterPersonHelpHandoff(unit, chased);
                        if (unit.battleNetRefusals() == 1
                                && unit.pathLength() > 0) {
                            // A pressured retarget which owns its first
                            // refusal does not discard the first failed wall
                            // face merely because the opposite face reaches
                            // the goal. The native twenty-byte writer leaves
                            // that face's first progressive compass byte as a
                            // complete route, then the cooperative movement
                            // probe pays the ordinary fifteen-count band.
                            // This is a formation-level rule: it prevents a
                            // surrounded fighter from escaping around the
                            // back of the line while its front neighbour is
                            // about to move. XHuman 12 slot 1512 stores the
                            // one-byte S face at fixture 94 and advances only
                            // after that band, rather than taking Java's
                            // opposite NE wall at fixture 95. Later refusal
                            // generations have already selected a stable wall
                            // face and keep the complete replacement route
                            // (slot 1503, refusal two, first-steps SE at 90).
                            int direct = World
                                    .battleNetFirstBresenhamHeading(
                                            unit.tileX(), unit.tileY(),
                                            chased.tileX(), chased.tileY());
                            int stride = world
                                    .battleNetMovementStride(unit);
                            int currentDistance = Math.max(
                                    Math.abs(chased.tileX() - unit.tileX()),
                                    Math.abs(chased.tileY() - unit.tileY()));
                            int retainedFace = -1;
                            for (int turn = 0; turn < Direction.COUNT;
                                    turn++) {
                                int heading = Math.floorMod(
                                        direct - turn, Direction.COUNT);
                                int faceX = unit.tileX()
                                        + Direction.deltaX(heading) * stride;
                                int faceY = unit.tileY()
                                        + Direction.deltaY(heading) * stride;
                                int faceDistance = Math.max(
                                        Math.abs(chased.tileX() - faceX),
                                        Math.abs(chased.tileY() - faceY));
                                if (faceDistance >= currentDistance) {
                                    continue;
                                }
                                Unit faceBlocker = world.unitAt(
                                        faceX, faceY);
                                boolean cooperativeFace =
                                        faceBlocker != null
                                        && faceBlocker != unit
                                        && world.isAllied(unit.player(),
                                                faceBlocker.player())
                                        && world.movement
                                                .battleNetRefusalBandSoftClearMoveAlly(
                                                        faceBlocker);
                                if (world.canEnter(unit, faceX, faceY)
                                        || cooperativeFace) {
                                    retainedFace = heading;
                                    break;
                                }
                            }
                            if (retainedFace >= 0) {
                                world.causalTrace.event(world.cycle,
                                        "path.pressured-wall-face-retain",
                                        unit.id(),
                                        "target", chased.id(),
                                        "heading", retainedFace,
                                        "refusals",
                                                unit.battleNetRefusals());
                                unit.setPath(new PathFinder.Path(
                                        PathFinder.Result.FOUND,
                                        new int[] {retainedFace}));
                                unit.setRouteSpent(false);
                            }
                        }
                    } else {
                        world.planTowards(unit, chased,
                                collidedResidualRefill);
                        if (unit
                                .battleNetFirstSaturatedResidualProgressiveRefill()) {
                            unit
                                    .setBattleNetFirstSaturatedResidualProgressiveRefill(
                                            false);
                            int direct = World
                                    .battleNetFirstBresenhamHeading(
                                            unit.tileX(), unit.tileY(),
                                            chased.tileX(), chased.tileY());
                            int stride = world.battleNetMovementStride(unit);
                            boolean validDirect = direct >= 0
                                    && direct < Direction.COUNT;
                            int directX = validDirect ? unit.tileX()
                                    + Direction.deltaX(direct) * stride
                                    : unit.tileX();
                            int directY = validDirect ? unit.tileY()
                                    + Direction.deltaY(direct) * stride
                                    : unit.tileY();
                            int currentDistance = Math.max(
                                    Math.abs(chased.tileX() - unit.tileX()),
                                    Math.abs(chased.tileY() - unit.tileY()));
                            int directDistance = Math.max(
                                    Math.abs(chased.tileX() - directX),
                                    Math.abs(chased.tileY() - directY));
                            int plannedHeading = unit.pathLength() == 0
                                    ? -1 : unit.peekHeading();
                            int plannedX = plannedHeading < 0
                                    ? unit.tileX()
                                    : unit.tileX()
                                            + Direction.deltaX(plannedHeading)
                                                    * stride;
                            int plannedY = plannedHeading < 0
                                    ? unit.tileY()
                                    : unit.tileY()
                                            + Direction.deltaY(plannedHeading)
                                                    * stride;
                            int plannedDistance = Math.max(
                                    Math.abs(chased.tileX() - plannedX),
                                    Math.abs(chased.tileY() - plannedY));
                            if (validDirect
                                    && unit.pathLength() > 1
                                    && directDistance < currentDistance
                                    && plannedDistance >= currentDistance
                                    && world.canEnter(unit, directX, directY)) {
                                world.causalTrace.event(world.cycle,
                                        "path.first-saturated-refusal-progressive",
                                        unit.id(),
                                        "target", chased.id(),
                                        "heading", direct,
                                        "discarded_heading", plannedHeading);
                                unit.setPath(new PathFinder.Path(
                                        PathFinder.Result.FOUND,
                                        new int[] {direct}));
                                unit.setRouteSpent(false);
                            }
                        }
                    }
                    if (paidWrapRouteRedraw
                            && unit.battleNetAttackWrapDestArmPending()
                            && chased.type() != null
                            && !chased.type().building()
                            && unit.type() != null
                            && (unit.tileX() == chased.tileX()
                                    || unit.tileY() == chased.tileY())) {
                        // A saturated Attack-tail cursor redraws the direct
                        // axis through its first blocked square. This is a
                        // bounded route buffer, not permission to enter that
                        // square: XHuman 12 slot 1504 writes S,S at fixture
                        // 263, consumes the free first S, and retains the
                        // occupied second S behind route index one.
                        int direct = World.battleNetFirstBresenhamHeading(
                                unit.tileX(), unit.tileY(),
                                chased.tileX(), chased.tileY());
                        int distance = Math.max(
                                Math.abs(chased.tileX() - unit.tileX()),
                                Math.abs(chased.tileY() - unit.tileY()));
                        int count = Math.max(0, Math.min(2,
                                distance - Math.max(1,
                                        unit.type().maxAttackRange())));
                        int stride = world.battleNetMovementStride(unit);
                        int scanX = unit.tileX();
                        int scanY = unit.tileY();
                        int retained = 0;
                        for (int step = 0; step < count; step++) {
                            scanX += Direction.deltaX(direct) * stride;
                            scanY += Direction.deltaY(direct) * stride;
                            retained++;
                            if (!world.canEnter(unit, scanX, scanY)) {
                                break;
                            }
                        }
                        if (retained > 0) {
                            int[] headings = new int[retained];
                            java.util.Arrays.fill(headings, direct);
                            unit.setPath(new PathFinder.Path(
                                    PathFinder.Result.FOUND, headings));
                            unit.setPathGoal(
                                    chased.tileX(), chased.tileY());
                        }
                    }
                    if (parkedRefusalHeading >= 0
                            && parkedRefusalHeading < Direction.COUNT
                            && !unit.battleNetAttackWrapDestArmPending()
                            && !paidWrapRouteParked) {
                        // A cold paid refill continues the retained wall face.
                        // An Attack-tail wrap instead owns the fresh route
                        // writer: Human 13 ogre 1501 parks blocked SE at
                        // fixture 177, then native writes E,SE,S,SW,W and
                        // consumes E on 178. Replacing that head with the old
                        // face's clockwise successor consumed S instead.
                        int continuedHeading = Math.floorMod(
                                parkedRefusalHeading + 1, Direction.COUNT);
                        if (unit.pathLength() > 0) {
                            world.causalTrace.event(world.cycle,
                                    "path.parked-refusal-face-continue",
                                    unit.id(), "target", chased.id(),
                                    "blocked_heading", parkedRefusalHeading,
                                    "continued_heading", continuedHeading,
                                    "collision",
                                            unit.battleNetCollisionCounter(),
                                    "refusals", unit.battleNetRefusals());
                            boolean saturatedRetargetContinuation =
                                    unit.battleNetSaturatedRetargetRouteBand();
                            if (saturatedRetargetContinuation
                                    && unit.pathLength() > 1
                                    && unit.peekHeadingAtDepth(1)
                                            == continuedHeading) {
                                // The parked face promotes the matching second
                                // compass byte to the native route head without
                                // discarding the route writer's first byte.
                                // XHuman 12 slot 1496 turns NE,SE,NE into
                                // SE,NE,NE when its paid east face resumes.
                                int first = unit.peekHeading();
                                unit.replacePeekHeading(continuedHeading);
                                unit.replacePeekHeadingAfterNext(first);
                            } else {
                                unit.replacePeekHeading(continuedHeading);
                            }
                            if (saturatedRetargetContinuation) {
                                // Move must see that this is the second half of
                                // the route-index-twenty transaction. Its
                                // occupied head owns collision generation three
                                // and a complete Move 15..1 band.
                                unit.setBattleNetRetargetResidualRoutePark(true);
                            }
                        }
                    }
                    if (!unit.battleNetSaturatedRetargetRouteBand()) {
                        unit.setBattleNetParkedRefusalHeading(-1);
                    }
                    unit.setBattleNetSaturatedResidualFaceRetry(false);
                    if (rangedCollidedResidualRefill) {
                        preserveCollidedRangedWallHead(unit, chased);
                    }
                    if (sameQuarryCollisionRefill) {
                        unit.setBattleNetRetargetResidualParkRefill(false);
                    }
                    if (landPatrolAttackCollision > 0
                            && unit.battleNetLandPatrolAttackRoutePending()
                            && unit.pathLength() > 0
                            && unit.battleNetCollisionCounter() == 0) {
                        // A Patrol -> Attack pop owns the packed collision
                        // generation across its first chase-route writer.
                        // Unit.setPath normally clears generation one for a
                        // one-byte chase refill, but native's Patrol owner
                        // survives that overwrite. XHuman 12 slot 1356 parks
                        // at generation one on fixture 75, writes and commits
                        // its single NE byte on 76, and remains generation
                        // one until the residual hands control back to Attack.
                        unit.setBattleNetCollisionCounter(
                                landPatrolAttackCollision);
                    }
                    if (goalMoved
                            && unit.type().moveType()
                                    == UnitType.Movement.NAVAL
                            && world.actionMoveWalked
                            && unit.stepDrained() && !unit.isMoving()
                            && unit.pathLength() > 0) {
                        // A moving quarry can invalidate a warship's
                        // goal on the exact visit its old residual settles.
                        // Native constructs a replacement Attack behind the
                        // first heading of the refreshed route, even though
                        // the CUnit target pointer itself did not change.
                        // Preserve that order ownership until the newly
                        // committed residual lands; the common replan-tail
                        // handler then exposes Attack start 3,2,1.
                        unit.setBattleNetChaseReplanResidualHold(true);
                    }
                } else if (goalMoved) {
                    int staleHeading = unit.pathLength() > 0
                            ? unit.peekHeading() : -1;
                    int staleStride = world.battleNetMovementStride(unit);
                    boolean staleHeadingBlocked = staleHeading >= 0
                            && staleHeading < Direction.COUNT
                            && !world.canEnter(unit,
                                    unit.tileX()
                                            + Direction.deltaX(staleHeading)
                                                    * staleStride,
                                    unit.tileY()
                                            + Direction.deltaY(staleHeading)
                                                    * staleStride);
                    boolean blockedSettledStalePrefix =
                            world.actionMoveWalked
                            && unit.stepDrained() && !unit.isMoving()
                            && unit.battleNetCollisionCounter() < 3
                            && unit.offeredTarget() != null
                            && staleHeadingBlocked;
                    boolean collidedSameQuarryPrefixPark =
                            world.actionMoveWalked
                            && unit.stepDrained() && !unit.isMoving()
                            && unit.battleNetCollisionCounter() == 2
                            && unit.battleNetRefusals() == 0
                            && unit.offeredTarget() == null
                            && unit.battleNetPathStepsTaken() == 1
                            && staleHeadingBlocked;
                    boolean paidBandMovedGoalPark =
                            unit.battleNetRetargetResidualRoutePark()
                            && unit.battleNetRefusalHold()
                            && unit.battleNetCollisionCounter() == 2
                            && unit.battleNetRefusals() > 0
                            && unit.battleNetPathStepsTaken() == 1;
                    if (blockedSettledStalePrefix
                            || paidBandMovedGoalPark) {
                        // A moved quarry invalidates a cached prefix when its
                        // next byte is refused on the residual-settle visit.
                        // A live attack-back offer owns the target scan at this
                        // boundary; without one, same-quarry movement merely
                        // refreshes the remembered goal and retains its prefix.
                        // Native parks the old cursor at 20 and writes the
                        // direct free compass byte for the following visit.
                        // XHuman 12 slot 1489 therefore drops stale NE at
                        // fixture 92, stores just SE, and takes it on 93;
                        // component-rescuing the old byte sent Java east,
                        // while a full wall search escaped north-west.
                        world.causalTrace.event(world.cycle,
                                "path.stale-prefix-park", unit.id(),
                                "target", chased.id(),
                                "path_length", unit.pathLength(),
                                "path_steps",
                                        unit.battleNetPathStepsTaken(),
                                "heading", staleHeading,
                                "collision",
                                        unit.battleNetCollisionCounter(),
                                "refusals", unit.battleNetRefusals(),
                                "offered_target",
                                        unit.offeredTarget() == null ? -1
                                                : unit.offeredTarget().id(),
                                "paid_band", paidBandMovedGoalPark ? 1 : 0);
                        int refreshHeading = World
                                .battleNetFirstBresenhamHeading(
                                        unit.tileX(), unit.tileY(),
                                        chased.tileX(), chased.tileY());
                        int refreshX = unit.tileX()
                                + Direction.deltaX(refreshHeading)
                                        * staleStride;
                        int refreshY = unit.tileY()
                                + Direction.deltaY(refreshHeading)
                                        * staleStride;
                        unit.clearPath();
                        if (world.canEnter(unit, refreshX, refreshY)) {
                            unit.setPath(new PathFinder.Path(
                                    PathFinder.Result.FOUND,
                                    new int[] {refreshHeading}));
                        }
                        unit.setRouteSpent(false);
                        unit.setWaitCycles(0);
                        unit.setBattleNetOrderDelay(0);
                        if (blockedSettledStalePrefix) {
                            // This park is still one refused movement probe.
                            // Native slot 1489 changes unit+0x1d from 0x00
                            // to 0x10 at fixture 92 and retains that collision
                            // generation while its direct SE refresh drains.
                            // Losing the nibble made later path writers treat
                            // the moving grunt as transparent and route a
                            // following fighter through its body.
                            int collision =
                                    unit.battleNetCollisionCounter() + 1;
                            unit.setBattleNetCollisionCounter(
                                    collision > 14 ? 0 : collision);
                            if (unit.battleNetRefusals() == 0) {
                                // Java keeps the sticky FUN_004379e0
                                // generation separately from the projected
                                // collision count. This is its first refused
                                // generation, so the spent one-byte refresh
                                // may refill on its settle visit instead of
                                // paying the later-generation route park.
                                unit.setBattleNetRefusals(1);
                            }
                        } else if (paidBandMovedGoalPark) {
                            // A route retained through the completed refusal
                            // construction is stale as a whole, even when its
                            // old head has become free. Retail parks it at
                            // route index 20, refreshes the moved quarry, and
                            // spends the direct compass byte on the following
                            // Move visit. XHuman 12 slot 1517 therefore sits
                            // at (26,39) on fixture 94 and takes SE on 95;
                            // consuming its cached E immediately stole the
                            // square just vacated by slot 1482.
                            unit.setBattleNetRetargetResidualRoutePark(false);
                            unit.setBattleNetCollisionCounter(1);
                        }
                        unit.setBattleNetChaseEmptyRouteReplan(
                                unit.pathLength() == 0);
                        unit.setPathGoal(chased.tileX(), chased.tileY());
                        return;
                    }
                    if (collidedSameQuarryPrefixPark) {
                        // The same quarry moved while the first cached byte
                        // was blocked by a cooperative unit that is completing
                        // its own step this scheduler pass. Native does not
                        // convert that transient occupancy into a full
                        // fifteen-count refusal. It parks the old cursor for
                        // one visit and redraws against the refreshed goal on
                        // the next. This general handoff keeps congested melee
                        // lines flowing instead of leaving a unit frozen after
                        // the blocker has already departed (XHuman 12 slot
                        // 1482 at fixtures 93-94).
                        world.causalTrace.event(world.cycle,
                                "path.moved-goal-collision-park", unit.id(),
                                "target", chased.id(),
                                "path_length", unit.pathLength(),
                                "path_steps",
                                        unit.battleNetPathStepsTaken(),
                                "heading", staleHeading,
                                "collision",
                                        unit.battleNetCollisionCounter());
                        // The native high collision nibble advances in this
                        // visit (slot 1482 is 0x20 -> 0x30 at fixture 93).
                        // Keep the already-approved Java prefix for the one
                        // quiet park: a fresh ordinary ray is empty while the
                        // cooperative blocker still occupies the corridor,
                        // whereas native's parked bytes remain available to
                        // the following NewPath visit.  The collision
                        // generation, not merely the quiet visit, is what
                        // makes the next residual obey the same formation
                        // policy.
                        int collision =
                                unit.battleNetCollisionCounter() + 1;
                        unit.setBattleNetCollisionCounter(
                                collision > 14 ? 0 : collision);
                        unit.setRouteSpent(false);
                        unit.setWaitCycles(0);
                        unit.setBattleNetOrderDelay(0);
                        unit.setBattleNetChaseEmptyRouteReplan(false);
                        unit.setPathGoal(chased.tileX(), chased.tileY());
                        return;
                    }
                    unit.setPathGoal(chased.tileX(), chased.tileY());
                }
                } // else free-scan / empty-route / goalMoved
            }
            boolean paidConstructionMoveProbe = false;
            boolean changedDuringPaidConstruction = false;
            if (unit.battleNetAttackRefusalRecoveryStage() == 3) {
                // The Attack timer-one boundary above returned ownership to
                // Move. A changed target or a construction entered from an
                // empty parked buffer may use this visit to cache the new
                // route. A changed target whose replacement head is already
                // free executes immediately: native XHuman 10 slot 1497 lays
                // SW and spends it on fixture 74. A blocked replacement still
                // returns after planning and lets the following Move probe own
                // its refusal band (slot 1475).
                changedDuringPaidConstruction =
                        unit.target() != chaseTargetBeforeWalk;
                boolean changedReplacementEnterable = false;
                boolean changedReplacementFree = false;
                if (changedDuringPaidConstruction
                        && unit.pathLength() > 0) {
                    int changedHeading = unit.peekHeading();
                    int changedStride = world.battleNetMovementStride(unit);
                    int changedX = unit.tileX()
                            + Direction.deltaX(changedHeading) * changedStride;
                    int changedY = unit.tileY()
                            + Direction.deltaY(changedHeading) * changedStride;
                    changedReplacementEnterable =
                            world.canEnter(unit, changedX, changedY);
                    changedReplacementFree = unit.type() != null
                            // The one-tile infantry Move program can consume
                            // this free byte on the construction handoff.
                            // Cavalry retains a distinct plan-only visit even
                            // when its replacement west is open (XHuman 10
                            // knight at fixtures 66/67).
                            && "unit-grunt".equals(unit.type().ident())
                            && changedReplacementEnterable;
                }
                if (planOnlyAfterEmptyPaidConstruction
                        || (changedDuringPaidConstruction
                                && !changedReplacementFree)) {
                    if (!planOnlyAfterEmptyPaidConstruction
                            && changedReplacementEnterable) {
                        // Cavalry pays a distinct plan-only visit before it
                        // consumes the free replacement heading. Attack
                        // construction is nevertheless already complete, so
                        // carry that ownership across the quiet planning
                        // visit and let the eventual residual return through
                        // OP0 (XHuman 10 knight 1493, fixtures 66..79).
                        unit.setBattleNetAttackWrapDestArmPending(true);
                        // Target replacement above marks an ordinary melee
                        // replan residual. That marker normally buys Attack
                        // construction after the first replacement stride,
                        // but stage three is proof this route already paid
                        // construction 3,2,1. Keeping both owners inserted a
                        // second three-visit pause when the stride settled:
                        // XHuman 10 knight 1485 parked its route on fixture
                        // 80, while Java held to 83 before redrawing west.
                        unit.setBattleNetChaseReplanResidualHold(false);
                    }
                    unit.setBattleNetAttackRefusalRecoveryStage(0);
                    return;
                }
                unit.setBattleNetAnimationTimer(1);
                // Keep stage three visible to the movement probe. It
                // distinguishes a post-construction cooperative blocker from
                // an ordinary retained-route park, then is consumed below.
                paidConstructionMoveProbe = true;
            }
            boolean deferSettledRetargetSync = world.actionMoveWalked
                    && unit.target() != chaseTargetBeforeWalk
                    // Footman table-0x27 is the native cold-construction
                    // witness here. Route-formation rewrites are decided by
                    // planTowards before this movement probe; this argument
                    // owns only the pending SyncRand handoff below.
                    && unit.type() != null
                    && PudUnitTypes.code(unit.type().ident()) == 0;
            if (unit.battleNetRetargetResidualParkRefill()
                    && unit.battleNetRetargetResidualParkSteps() == 1
                    && unit.battleNetCollisionCounter() >= 1
                    && unit.battleNetCollisionCounter() <= 6
                    && unit.battleNetRefusals() == 0
                    && unit.pathLength() > 0
                    && unit.target() != null
                    && unit.target().type() != null
                    && unit.target().type().building()) {
                // Some naked retries occur between target-scan visits, where
                // MoveToTarget's ordinary route writer—not the scan-owned
                // refill above—lays the next buffer. They are still the same
                // saturated building transaction and retain the direct nearest
                // footprint face through the movement probe.
                Unit building = unit.target();
                int nearX = World.battleNetNearFootprintCoordinate(
                        unit.tileX(), building.tileX(),
                        Math.max(1, building.type().tileWidth()));
                int nearY = World.battleNetNearFootprintCoordinate(
                        unit.tileY(), building.tileY(),
                        Math.max(1, building.type().tileHeight()));
                int footprintHeading = World.battleNetFirstBresenhamHeading(
                        unit.tileX(), unit.tileY(), nearX, nearY);
                if (footprintHeading >= 0
                        && footprintHeading < Direction.COUNT) {
                    unit.replacePeekHeading(footprintHeading);
                }
            }
            int tileXBeforeChaseWalk = unit.tileX();
            int tileYBeforeChaseWalk = unit.tileY();
            boolean underWay = stepMoveTowardsTarget(
                    unit, deferSettledRetargetSync, chaseTargetBeforeWalk);
            if (unit.battleNetLongPaidWrapTimerOneSeen()
                    && unit.pathLength() == 4
                    && !unit.isMoving()) {
                // The first callback after this retained Move band only
                // exposes timer one. DoActionMove's ordinary empty action
                // advances the visible cursor past Move start even though
                // retail pins it there until the next callback parks RI 20.
                int moveStart = world.idle.battleNetSequenceStart(unit,
                        BattleNetSequence.MOVE_ANIMATION);
                if (moveStart >= 0) {
                    unit.setBattleNetSequenceOffset(moveStart);
                    unit.setBattleNetAnimationTimer(1);
                }
                unit.setBattleNetChaseStepReady(false);
                unit.setBattleNetAttackRefusalRecoveryStage(0);
                return;
            }
            if (openBattleNetSaturatedCardinalRouteTerminator(unit, true)) {
                return;
            }
            boolean committedChaseHeading = underWay && unit.isMoving()
                    && (unit.tileX() != tileXBeforeChaseWalk
                            || unit.tileY() != tileYBeforeChaseWalk);
            boolean retainedTailReachabilityRefusal = underWay
                    && !unit.isMoving()
                    && unit.pathLength() == 1
                    && unit.battleNetAttackWrapDestArmPending()
                    && unit.battleNetChaseEmptyRouteReplan();
            if (clearCollisionAfterSettledRetargetStep
                    && underWay && unit.isMoving()) {
                // The collision nibble participates in the replacement ray,
                // then NewPath clears it once that ray's first byte commits.
                // XHuman 12 slots 1507 and 1520 are 0x10/0x30 while drawing
                // their knight routes and 0x00 from the successful fixture-91
                // step onward. Keeping those generations contaminated the
                // next crowded retarget nineteen cycles later.
                unit.setBattleNetCollisionCounter(0);
                if (clearRefusalsAfterSettledRetargetStep) {
                    unit.setBattleNetRefusals(0);
                }
            }
            if (paidConstructionMoveProbe) {
                if (underWay && unit.isMoving()) {
                    // This heading was admitted by the single Move probe
                    // after Attack construction 3,2,1. Its residual therefore
                    // returns directly through the already-paid OP0 instead
                    // of buying construction again. XHuman 10 knight 1493
                    // takes W on fixture 67, residual-settles on 79 and both
                    // opens Attack@1923 and pays table-0x27 there. Losing this
                    // ownership delayed the draw three visits and changed the
                    // following footman's randomized damage.
                    unit.setBattleNetAttackWrapDestArmPending(true);
                    if (!changedDuringPaidConstruction) {
                        unit.setBattleNetChaseReplanResidualHold(false);
                    }
                }
                unit.setBattleNetAttackRefusalRecoveryStage(0);
            }
            // Hard-refusal stage six is the single Move probe after Attack
            // construction.  A refused probe re-arms stage five inside
            // BattleNetMovementSystem; an accepted/reached probe has paid the
            // handoff and must no longer retain its retry ownership.
            if (unit.battleNetAttackRefusalRecoveryStage() == 6) {
                Unit retryTarget = unit.target();
                boolean retainedParkedCardinalProbe =
                        unit.battleNetStageSixCardinalProbePark()
                        && !unit.isMoving()
                        && unit.pathLength() == 1
                        && unit.battleNetPathStepsTaken() == 0
                        && retryTarget != null && retryTarget.isAlive()
                        && !world.targets.inAttackRange(unit, retryTarget);
                if (retainedParkedCardinalProbe) {
                    // Native leaves the direct byte behind route index
                    // twenty and keeps the final Move-probe owner alive.
                    // Clearing stage six here makes the drained one-byte Java
                    // surrogate transparent to the following path writer;
                    // XHuman 12 slot 1456 then plans blocked E instead of
                    // walking SE on the same fixture-189 unit pass.
                    return;
                }
                boolean refusedEmptyProbe = !unit.isMoving()
                        && unit.pathLength() == 0
                        && retryTarget != null && retryTarget.isAlive()
                        && !world.targets.inAttackRange(unit, retryTarget);
                if (refusedEmptyProbe) {
                    if (unit.battleNetLandPatrolAttackRoutePending()
                            && unit.battleNetRefusalHold()
                            && unit.battleNetCollisionCounter() > 0) {
                        // Movement just parked a physically written one-byte
                        // route behind native cursor twenty. Its collision
                        // generation owns the next visits; this is not the
                        // empty stage-six probe that re-enters Still below.
                        return;
                    }
                    // An exhausted route is rebuilt inside stepMove after the
                    // earlier chase-boundary consult.  If that rebuilt first
                    // heading refuses, movement has already parked it again
                    // before the explicit refusal arm can observe stage six.
                    // The empty parked route is the same native result and
                    // therefore re-enters Attack construction here.
                    // spendTheEmptyRoute reports its refused rebuild as a
                    // short Java wait. Native has already transferred
                    // ownership back to Attack and revisits on 3,2,1;
                    // retaining that surrogate wait inserts two frozen
                    // cycles between every probe.
                    if (!rearmBattleNetHardRefusalAttack(unit)) {
                        unit.setBattleNetAttackRefusalRecoveryStage(0);
                    }
                } else {
                    if (underWay && unit.isMoving()) {
                        // Stage six is the one Move probe granted by the
                        // Attack constructor which just counted 3,2,1. A
                        // successful heading retains that paid ownership
                        // through its pixel residual. XHuman 10 grunt 1475
                        // commits east on fixture 231 and settles at 2540/1
                        // on 247; forgetting this handoff charged 2539/3,2,1
                        // a second time and lost its fixture-257 blow.
                        unit.setBattleNetPaidRefusalRecoveryApproach(true);
                    }
                    unit.setBattleNetAttackRefusalRecoveryStage(0);
                }
            }
            // An exhausted melee approach does not serve Java's generic
            // two-cycle empty-route sleep. Native enters the active-order
            // Still dispatcher, pays its land-idle choice, and re-arms Attack
            // construction 3,2,1 before giving Move one more probe. This is
            // the ordinary entrance to the same loop used by a fully slept
            // hard refusal. XHuman 12 slots 1516 and 1501 enter at fixtures
            // 73 and 76, then both repeat at 79/82 while their approaches
            // remain blocked. Keeping the surrogate wait omitted both draws
            // on every loop and reassigned later melee/projectile randomness.
            Unit emptyApproachTarget = unit.target();
            boolean exhaustedMeleeApproach =
                    unit.battleNetAttackRefusalRecoveryStage() == 0
                    && unit.battleNetChaseEmptyRouteReplan()
                    && unit.waitCycles() == 2
                    && !unit.isMoving() && unit.pathLength() == 0
                    && unit.chasing()
                    && emptyApproachTarget != null
                    && emptyApproachTarget.isAlive()
                    && !world.targets.inAttackRange(
                            unit, emptyApproachTarget)
                    && !World.battleNetRangedChaseUnit(unit)
                    && (unit.order() == Unit.Order.ATTACK
                            || unit.order() == Unit.Order.ATTACK_MOVE);
            if (exhaustedMeleeApproach && world.actionMoveWalked) {
                if (pathLengthBeforeChaseWalk == 0
                        && routeSpentBeforeChaseWalk) {
                    // A cursor that was already exhausted before the last
                    // pixels settle enters active-order Still on this visit.
                    // A cursor which still owned its final cached heading
                    // belongs to the deferred route-index-20 park below.
                    unit.setBattleNetResidualEmptyApproachIdlePending(false);
                    unit.setWaitCycles(0);
                    unit.setBattleNetOrderDelay(0);
                    if (rearmBattleNetHardRefusalAttack(unit)) {
                        return;
                    }
                }
                // Residual settlement and active-order retry are distinct
                // native callbacks. Keep the exhausted Move cursor parked for
                // this visit; the next scheduler callback pays the idle draw
                // and opens Attack construction. XHuman 12 ogre slot 1381 /
                // Java 219 settles east at fixture 118 and opens Attack 3 at
                // 119. Folding the visits together moved its draw one cycle
                // early and reassigned the fixture-125 knight damage roll.
                unit.setBattleNetResidualEmptyApproachIdlePending(true);
                unit.setWaitCycles(0);
                unit.setBattleNetOrderDelay(0);
                return;
            }
            if (exhaustedMeleeApproach
                    && rearmBattleNetHardRefusalAttack(unit)) {
                return;
            }
            walked = true;
            // Mid-animation first, and nothing else is looked at: upstream's
            // MoveToTarget runs DoActionMove and then
            // `if (unit.Anim.Unbreakable || this->Finished) return;` before it
            // reaches any target check at all. A
            // unit whose step is in the air does not notice its quarry die.
            if ((unit.animation().unbreakable()
                    && !retainedTailReachabilityRefusal)
                    || committedChaseHeading
                    || (underWay && unit.isMoving()
                            && unit.type() != null
                            && unit.type().moveType()
                                    == UnitType.Movement.LAND
                            && onBattleNetChaseMoveBody(unit))
                    || (underWay && unit.isMoving()
                            && unit.pathLength() > 0
                            && unit.type() != null
                            && unit.type().seaUnit()
                            && unit.battleNetDoubleStep())) {
                // A successful path element owns this entire MoveToTarget
                // callback. Native's Move program is unbreakable immediately
                // after the logical tile commit, even when the presentation
                // animation is on a Java-breakable frame. That ownership
                // lasts for the entire committed doubled naval residual while
                // cached route bytes remain. An exhausted buffer re-enters
                // the moving target-scan cadence; a retained tail does not.
                // Land movement retains its independently authenticated
                // refusal/retarget seams below. Target scan remains due until
                // the owned sea stride settles.
                return;
            }
            if (underWay) {
                // Not a bare return. Upstream's arm opens with
                // CheckForTargetInRange, which is
                // three things in order: end the order if the goal has stopped
                // being a goal, re-aim an auto-targeting unit, and -- if that
                // found a *different* goal already in reach -- take
                // ATTACK_TARGET on the spot.
                Unit goal = unit.target();
                if (goal != null && !world.targets.validAttackTarget(unit, goal)) {
                    world.finishAttackOrder(unit);
                    return;
                }
                if (!world.idle.autoSelectTarget(unit)) {
                    world.finishAttackOrder(unit);
                    if (retainedTailReachabilityRefusal
                            && world.battleNetSequence != null) {
                        unit.setBattleNetSequenceOffset(
                                world.idle.battleNetStillSequenceStart(unit));
                        unit.setBattleNetAnimationTimer(1);
                        // CheckForTargetInRange released the weak Attack while
                        // returning from Move OP0's retained-tail refusal.
                        // Retail continues through HandleUnitAction's newly
                        // installed active-order Still callback on this same
                        // visit. That callback owns its random-facing draw but
                        // leaves the fresh Still cursor at OP0. Deferring the
                        // draw until the next scheduler visit reassigns every
                        // later projectile and damage roll: XHuman 10's boxed
                        // knight (native 1489 / Java 111) is the sealed witness.
                        world.idle.advanceBattleNetActiveOrderIdleRandom(unit);
                    }
                    return;
                }
                Unit settledRetarget = unit.target();
                boolean boxedBuildingRetargetAfterResidual =
                        world.actionMoveWalked
                        && chaseTargetBeforeWalk != null
                        && settledRetarget != null
                        && settledRetarget != chaseTargetBeforeWalk
                        && settledRetarget.isAlive()
                        && settledRetarget.type() != null
                        && settledRetarget.type().building()
                        && unit.type() != null
                        && unit.type().maxAttackRange() <= 1
                        && unit.battleNetAiBehavior() == 1
                        && !unit.isMoving() && unit.pathLength() == 0
                        && unit.stepDrained()
                        && unit.battleNetCollisionCounter() == 0
                        && unit.battleNetRefusals() == 0
                        && unit.battleNetChaseReplanResidualHold()
                        && !world.targets.inAttackRange(
                                unit, settledRetarget)
                        && !world.movement
                                .battleNetHasStrictlyCloserFreeNeighbour(
                                        unit, settledRetarget);
                if (boxedBuildingRetargetAfterResidual) {
                    // The last pixels of a live mobile-quarry route can settle
                    // on the same callback whose chase-boundary target scan
                    // replaces it with a building footprint. If every free compass square
                    // fails to close footprint distance, retail enters the
                    // active-order land-idle callback immediately and opens a
                    // cold Attack 3,2,1 constructor with RI20. XHuman 12 slot
                    // 1513 is the sealed witness: its SE residual lands at
                    // (40,39), footman 1477 becomes tower 1485, and fixtures
                    // 240/243/246/249 each pay 0040AD58 and expose 2539/3.
                    // Keeping Java's generic PF_REACHED Move-start/1 sleep
                    // omitted two draws before the fixture-249 melee roll.
                    unit.clearPath();
                    unit.setRouteSpent(false);
                    unit.setWaitCycles(0);
                    unit.setBattleNetOrderDelay(0);
                    unit.setBattleNetChaseStepReady(false);
                    unit.setBattleNetChaseEmptyRouteReplan(true);
                    unit.setBattleNetChaseReplanResidualHold(false);
                    unit.setBattleNetColdNoProgressRefusalLoop(true);
                    unit.setPathGoal(
                            settledRetarget.tileX(), settledRetarget.tileY());
                    rearmBattleNetHardRefusalAttack(unit);
                    return;
                }
                if (World.TRACE_MOVING != null
                        && unit.id() == World.TRACE_MOVING_ID) {
                    System.err.printf("JATTACKMOVERETURN cycle=%d unit=%d "
                                    + "seq=%d/%d path=%d collision=%d target=%d "
                                    + "sleep=%d chase=%d%n",
                            world.cycle, unit.id(),
                            unit.battleNetSequenceOffset(),
                            unit.battleNetAnimationTimer(), unit.pathLength(),
                            unit.battleNetCollisionCounter(),
                            unit.target() == null ? -1 : unit.target().id(),
                            unit.attackScanSleep(), unit.chasing() ? 1 : 0);
                }
                Unit aimed = unit.target();
                if (aimed != null && aimed != goal
                        && world.targets.inAttackRange(unit, aimed)) {
                    boolean paidWrapRetarget =
                            unit.battleNetAttackWrapDestArmPending();
                    unit.setChasing(false);
                    unit.setFighting(true);
                    // CheckForTargetInRange takes ATTACK_TARGET on this same
                    // callback after AutoSelectTarget replaces the quarry.
                    // A footman's proved cold-retarget gate below defers its
                    // table-0x27 draw through construction. Other types use
                    // the ordinary first-in-range debit here. Returning with
                    // only chasing cleared made a completed residual look
                    // live in the coarse trace while its Attack program and
                    // shared RNG were one scheduler visit late.
                    if (deferBattleNetFootmanRetainedRouteSyncRand(
                            unit, aimed, goal)) {
                        return;
                    }
                    world.consumeBattleNetPendingMeleeSyncRand(unit);
                    if (paidWrapRetarget) {
                        finishBattleNetPaidWrapRetargetArrival(unit);
                    }
                    world.turnToTarget(unit, aimed, 0, 0);
                }
                return;
            }
            // A cold PF_REACHED changes only the order state and turns. It
            // does not fall through to CheckForTargetInRange/AutoSelectTarget,
            // and it does not start the swing until the following Execute.
            // That is the committed-corpse case covered by AttackMoveTest.
            //
            // A residual drain is different: DoActionMove has first finished
            // the borrowed step, then the attack order gets its goal-validity
            // boundary. XHuman 9 knight 1414 is the sealed witness. Skeleton
            // 1430 has been DYING since fixture 119 while the knight pays its
            // north pixels; when the last three land on fixture 126 native
            // clears the CUnitPtr and installs Still 1869/3. Treating every
            // drain as cold PF_REACHED opened a full new Attack program on the
            // corpse and left the knight visibly frozen in combat.
            Unit reached = unit.target();
            if (reached != null && world.targets.inAttackRange(unit, reached)) {
                unit.setChasing(false);
                unit.setFighting(true);
                if (unit.battleNetAttackWaitRefillResidual()) {
                    // A spent AttackTarget visit rebuilt this route and its
                    // final borrowed leftover has just answered PF_REACHED.
                    // Retail opens the next Attack body past OP0 now, not on
                    // the following Execute (Human 1 grunt 1591: land 417,
                    // opcode-ten blow 427).
                    world.openBattleNetAttackAfterChaseResidual(unit, true);
                    unit.setBattleNetAttackWaitRefillResidual(false);
                }
                // First in-range action: native FUN_004234b0 may debit SyncRand
                // here for table-0x27 melee types (not only on a later attack
                // animation marker). Deferring until the marker left Human 13
                // seed at 1 through fixture 19 while native advanced.
                if (deferBattleNetFootmanRetainedRouteSyncRand(
                        unit, reached, chaseTargetBeforeWalk)) {
                    return;
                }
                world.consumeBattleNetPendingMeleeSyncRand(unit);
                world.turnToTarget(unit, reached, 0, 0);
                return;
            }
            } finally {
                world.actionMoveWalked = false;
                world.actionSettledMeleeReplacementRoute = false;
                world.actionSettledMeleeReplacementBroadRoute = false;
                world.actionSettledMeleeReplacementAfterPaidBand = false;
            }
        }

        Unit target = unit.target();
        if (!world.targets.validAttackTarget(unit, target)) {
            int invalidAttackStart = world.battleNetSequence == null
                    ? -1 : world.idle.battleNetSequenceStart(
                            unit, BattleNetSequence.ATTACK_ANIMATION);
            BattleNetSequence.Tick invalidOp0 = invalidAttackStart < 0
                    ? null : world.battleNetSequence.tick(
                            invalidAttackStart, 1);
            boolean invalidatedOnFreshOp0 = invalidOp0 != null
                    && invalidOp0.valid()
                    && invalidOp0.actionMarker()
                    && unit.battleNetSequenceOffset() == invalidOp0.offset()
                    && unit.battleNetAnimationTimer() == invalidOp0.timer()
                    && !unit.fighting() && !unit.chasing();
            // A target which dies during an unbreakable swing is reconsidered
            // at EndActionAttack before the order is released.  XHuman 4
            // footman 1518 completes its body against dying grunt 1489 on
            // fixture 234 and immediately selects grunt 1505 at (74,61).
            // Finishing first skipped AutoSelectTarget and exposed Still.
            boolean replacedInvalidGoal = !unit.battleNetStationaryAttack()
                    && world.idle.autoSelectTarget(unit)
                    && world.targets.validAttackTarget(unit, unit.target());
            if (replacedInvalidGoal) {
                target = unit.target();
                boolean opensMeleeReplacementBody = invalidAttackStart >= 0
                        && unit.type() != null
                        && unit.type().maxAttackRange() <= 1
                        && !unit.battleNetStationaryAttack()
                        && !world.targets.inAttackRange(unit, target);
                if (opensMeleeReplacementBody) {
                    // This is the invalid-goal entry to the same native
                    // tail -> OP0 handoff handled inside the sequence walker.
                    // AutoSelectTarget has named a live but distant melee
                    // replacement, so the completed body owns a fresh Attack
                    // constructor before its destination arm may chase. The
                    // direct witness is XHuman 4 footman 1518: fixture 234
                    // replaces dying grunt 1489 with grunt 1505, exposes
                    // Attack 2539/3,2,1, then first-steps southeast on 237.
                    unit.setBattleNetAttackResumeHoldActive(false);
                    unit.setBattleNetStationaryRecoveryHeld(false);
                    unit.setBattleNetSequenceOffset(invalidAttackStart);
                    unit.setBattleNetAnimationTimer(3);
                    unit.setBattleNetSequenceMeleeLanded(false);
                    unit.setOfferedTarget(target);
                    unit.setFighting(false);
                    unit.setChasing(false);
                    unit.setBattleNetAttackWrapDestArmPending(true);
                    return;
                }
            } else {
                world.finishAttackOrder(unit);
            }
            if (!replacedInvalidGoal && invalidatedOnFreshOp0
                    && world.battleNetSequence != null
                    && world.idle.battleNetUsesLandIdleRandom(unit)) {
                // Construction reached its first OP0 after the quarry had
                // already entered Die. EndActionAttack installs Still on
                // this same callback, so its fresh cursor is timer one rather
                // than another 3,2,1 constructor. XHuman 10's close-hit helper
                // knight 1489 pays Attack 3,2,1 on fixtures 176..178, sees
                // grunt 1477 dying at OP0, and records Still@1869/1 on 179.
                unit.setBattleNetSequenceOffset(
                        world.idle.battleNetStillSequenceStart(unit));
                unit.setBattleNetAnimationTimer(1);
                // Ordinary land units then dispatch that newly installed
                // active-order Still program on the same visit. Siege engines
                // use the separate Still arm: Human 13 catapult 1479 retains
                // Still@413/3 on fixture 216 and takes no 0040AD58 draw. The
                // XHuman 10 knights are the non-siege contrast; their fresh
                // Still callback owns one draw and remains at Still@start/1.
                world.idle.advanceBattleNetActiveOrderIdleRandom(unit);
            }
            if (!replacedInvalidGoal) {
                return;
            }
        } else {
            // A live automatically selected quarry is reconsidered on its
            // ordinary six-visit cadence.  The invalid-goal arm above has
            // already performed its one immediate replacement scan.
            if (!world.idle.autoSelectTarget(unit)) {
                world.finishAttackOrder(unit);
                return;
            }
            target = unit.target();
            if (!world.targets.validAttackTarget(unit, target)) {
                world.finishAttackOrder(unit);
                return;
            }
        }

        int range = Math.max(1, unit.type().maxAttackRange());
        int distance = world.attackDistance(unit, target);

        // Standing on top of a catapult is how you beat one, and it only works
        // if the catapult refuses the shot. MinAttackRange was parsed and read
        // by nothing, so a siege engine happily bombarded a footman leaning on
        // it and there was no reason to close.
        if (distance < unit.type().minAttackRange()) {
            unit.setFighting(false);
            moveToBetterPos(unit, target);
            return;
        }

        if (distance > range) {
            // Action 16 never chases: if the target has left weapon range
            // after the delay window, drop to Still (same as the pre-chase
            // stationary branch above).
            if (unit.battleNetStationaryAttack()) {
                if (armBattleNetStationaryRecoveryHold(unit)) {
                    return;
                }
                finishStationaryAttackToStill(unit);
                return;
            }
            // Walk towards it. Repathing every cycle would be wasteful, so a
            // new route is started only when there is none to follow, or when
            // the target has moved off the square this one was aimed at --
            // upstream's rule exactly: PathFinderInput::SetGoal raises
            // isRecalculatePathNeeded whenever the goal tile changes, and
            // NextPathElement acts on it before taking the next step.
            //
            // Without it a chaser followed its whole original route to where
            // its quarry used to be, and only looked again once it got there.
            boolean stale = unit.pathGoalX() != target.tileX()
                    || unit.pathGoalY() != target.tileY();
            boolean refilled = false;
            boolean exhaustedRefill = false;
            if (!unit.isMoving() && (unit.pathLength() == 0 || stale)) {
                exhaustedRefill = unit.pathLength() == 0;
                unit.clearPath();
                if (!world.movement.moveTowards(unit, target)) {
                    unit.setChasing(false);
                    world.finishAttackOrder(unit);
                    return;
                }
                if (unit.pathLength() == 0 && unit.type().landUnit()
                        && !world.battleNetTerrainReachable(unit, target)) {
                    // An empty answer whose quarry is unreachable over terrain
                    // alone is retail's give-up, not a route to wait on. XOrc
                    // 11's axethrower 1517 acquires the archer row on 10,30
                    // from its walled shore pocket at fixture 248, winds
                    // Attack 3,2,1 across 250..252, and on 253 clears the
                    // target and returns to Still without a step -- the
                    // GiveOrder epilogue at 0x00453097 proved by capture.
                    // This used to promote the chase anyway and loop the
                    // windup timer against the wall forever. Reachable
                    // quarries keep today's compensated retry: XHuman 4's
                    // packed axethrower row answers empty for three visits,
                    // stays five steps over open ground, and then opens --
                    // its opening chase is exact.
                    unit.setChasing(false);
                    world.finishAttackOrder(unit);
                    if (world.battleNetSequence != null) {
                        // GiveOrder returns this failed Attack directly at the
                        // fresh Still marker with one tick left. XOrc 11's
                        // axethrower 1517 records Still@825/1 on fixture 253
                        // and dispatches its first OP0 on 254. Reconstructing
                        // Still through the generic order installer leaves the
                        // normal 3,2,1 construction here, two ticks late.
                        int stillStart = world.idle
                                .battleNetStillSequenceStart(unit);
                        if (stillStart >= 0) {
                            unit.setBattleNetSequenceOffset(stillStart);
                            unit.setBattleNetAnimationTimer(1);
                        }
                    }
                    return;
                }
                refilled = true;
                // Bind the marker below, after the first movement consult has
                // told us whether this is a borrowed leftover or a buffered
                // multi-step route. It must never leak from an older leg.
                unit.setBattleNetAttackWaitRefillResidual(false);
            }
            unit.setFighting(false);
            unit.setChasing(true);
            // A live leftover on swing-end still belongs to the next
            // MoveToTarget visit. A spent buffer that just asked 0x44fbd0
            // dest-arms now: native 0x437c80 writes the first leftover the
            // same visit the pathfinder answers. Skipping that dest-arm
            // because a swing had just ended left Human 1 grunt 1591 on
            // 27,21 at 321 while native dest-armed 26,22.
            if (!walked && (!swung || refilled)) {
                stepMoveTowardsTarget(unit);
                // AttackTarget has already spent this visit's swing wait.
                // Only an exhausted-route refill owns its borrowed movement
                // residual through zero. A stale-goal replan still owns a
                // buffered route and uses the ordinary eight-pixel occupied-
                // quarry band. This is route state, not a unit or fixture
                // exception.
                if (refilled && exhaustedRefill && swung && unit.isMoving()) {
                    unit.setBattleNetAttackWaitRefillResidual(true);
                }
            }
            return;
        }

        // In range -- but a step already begun has to finish before the unit
        // may swing. Upstream's {@code MoveToTarget} runs {@code DoActionMove}
        // and then returns while {@code Anim.Unbreakable} is set
        // so it never reaches the
        // in-range test, never calls {@code TurnToTarget}, and never sets
        // {@code ATTACK_TARGET}, until the move animation has run out to its
        // "unbreakable end".
        //
        // This implementation marked the new square, dropped the walk, and swung from
        // where it stood -- so a unit drawn half way between two squares was
        // already dealing damage. The differential harness measured what that
        // is worth: on {@code maps/demo/demo03}, from the same seed, the same
        // grunt reaches the square beside its victim on cycle 2 in both
        // engines and the first blow lands on cycle 12 here against cycle 39
        // upstream. Most of a second of every battle, in this implementation's favour,
        // and it compounds -- the damage roll comes off the shared random
        // stream, so striking early moves every later roll too.
        //
        // The walk still runs. That distinction is the whole of the earlier
        // lesson recorded above: the flag stops a unit re-deciding
        // mid-animation, it never stops it moving, and testing it without
        // that care once left units crawling at a twelfth speed.
        if (!walked && world.movement.isStepping(unit)) {
            stepMoveTowardsTarget(unit);
            return;
        }

        // The refill marker belongs to one out-of-range chase leg. If the
        // target walks back into range before that leg reaches its occupied
        // quarry, the leg is over and the marker must not leak into a later
        // chase. Human 8's moving peasant does exactly that before leaving
        // range again; retaining the old marker made the later, ordinary
        // approach drain past retail's eight-pixel arrival band.
        unit.setBattleNetAttackWaitRefillResidual(false);

        // In range: take ATTACK_TARGET, turn to the target -- and stop there.
        // Upstream's arm is
        //
        //   if (goal && InAttackRange(unit, *goal)) {
        //       TurnToTarget(unit, goal);
        //       this->State &= AUTO_TARGETING;
        //       this->State |= ATTACK_TARGET;
        //       return;
        //   }
        //
        // and the return is the point:
        // the swing belongs to AttackTarget, which runs on the *next* cycle.
        // A chaser that arrives and swings in the same breath lands every blow
        // a cycle early, and the damage roll comes off the shared random
        // stream. On maps/demo/demo03 the peasant at 9,2 is struck on cycle 38
        // here and 39 upstream, and flees a cycle early in step with it.
        if (walked) {
            unit.setChasing(false);
            unit.setFighting(true);
            // A residual-settle target switch enters cold Attack construction
            // before table 0x27 is charged. Native XHuman 4 footman 1510
            // finishes NE and changes axe 1506 -> grunt 1515 on fixture 82,
            // records Attack timer 3,2,1 through 84, then spends SyncRand at
            // OP0 on 85. Paying on the settle visit exposed the new target
            // three callbacks early and made the shared seed diverge at 82.
            if (World.BNE_PEND_TRACE) {
                System.err.printf("JBNEMELEESYNC event=walked-arrival cycle=%d "
                                + "unit=%d target=%d chaseBefore=%d code=%d "
                                + "pending=%d pathGoal=%d,%d%n",
                        world.cycle, unit.id(),
                        target == null ? -1 : target.id(),
                        chaseTargetBeforeWalk == null
                                ? -1 : chaseTargetBeforeWalk.id(),
                        unit.type() == null ? -1
                                : PudUnitTypes.code(unit.type().ident()),
                        unit.battleNetPendingMeleeSyncRand() ? 1 : 0,
                        unit.pathGoalX(), unit.pathGoalY());
            }
            if (deferBattleNetFootmanRetainedRouteSyncRand(
                    unit, target, chaseTargetBeforeWalk)) {
                return;
            }
            world.consumeBattleNetPendingMeleeSyncRand(unit);
            world.turnToTarget(unit, target, 0, 0);
            return;
        }
        unit.setChasing(false);
        if (swung) {
            // The swing this cycle has already been animated at the top, and
            // {@code AttackTarget} animates once. All that is left of it is
            // its last line, {@code TurnToTarget(unit, goal)}; the next swing
            // belongs to the next cycle.
            //
            // Still pay a pending table-0x27 first SyncRand while swinging:
            // native FUN_004234b0 runs on the first in-range Attack callback,
            // not only in the gap between unbreakable swings. Deferring until
            // !swung left Human 13 knight 100 one cycle late at fixture 36
            // (seed one draw short of native's two-draw c36).
            world.consumeBattleNetPendingMeleeSyncRand(unit);
            world.turnToTarget(unit, target, 0, 0);
            return;
        }
        unit.setFighting(true);
        world.consumeBattleNetPendingMeleeSyncRand(unit);
        world.strike(unit, target);
    }

    /**
     * Defers table-0x27's first draw while a footman's completed chase route
     * remains owned by the Attack handoff.
     *
     * <p>Retail enters cold Attack construction first and calls
     * {@code FUN_004234b0} only when OP0 is reached while the completed route
     * cursor remains owned by the handoff. XHuman 4 footman 1510 is the binary
     * witness: arrival on fixture 82, Attack timers 3,2,1 through 84, retained
     * route index one, and SyncRand on 85. That draw stays deferred even when
     * the final scan keeps the same grunt; quarry identity is not the native
     * discriminator. An exhausted-route replan is the
     * contrasting state: XHuman 10 footman 1529 settles its final pixels and
     * replaces ogre 1548 with ogre 1543 on fixture 211, writes route index
     * twenty, opens the same 3,2,1 constructor, but calls 0x004234b0 on that
     * settle visit. The constructor shape alone therefore cannot decide the
     * debit; ownership of the exhausted route does.</p>
     */
    private boolean deferBattleNetFootmanRetainedRouteSyncRand(
            Unit unit, Unit target, Unit targetBeforeWalk) {
        if (target == targetBeforeWalk) {
            return false;
        }
        return deferBattleNetFootmanRetainedRouteSyncRand(unit);
    }

    /** Applies the retained-route deferral after its caller proves the handoff. */
    private boolean deferBattleNetFootmanRetainedRouteSyncRand(Unit unit) {
        if (unit.type() == null || PudUnitTypes.code(unit.type().ident()) != 0
                || !unit.battleNetPendingMeleeSyncRand()
                || unit.battleNetChaseEmptyRouteReplan()) {
            return false;
        }
        Unit target = unit.target();
        if (World.BNE_PEND_TRACE) {
            System.err.printf("JBNEMELEESYNC event=defer-retarget cycle=%d "
                            + "unit=%d target=%d previous=%d%n",
                    world.cycle, unit.id(),
                    target == null ? -1 : target.id(),
                    -1);
        }
        world.armBattleNetAttackStart(unit);
        unit.setBattleNetOrderDelay(2);
        world.turnToTarget(unit, unit.target(), 0, 0);
        return true;
    }

    /** Installs the paid direct byte that follows a recovered route residual. */
    private void installBattleNetPaidRecoveryDirectRefill(Unit unit) {
        Unit target = unit.target();
        if (unit.pathLength() != 0
                || unit.battleNetDirectRecoveryGeneration() <= 0
                || unit.battleNetAttackRefusalRecoveryStage() != 0
                || unit.battleNetCollisionCounter() != 1
                || unit.battleNetRefusals() <= 0
                || target == null || !target.isAlive()
                || world.targets.inAttackRange(unit, target)
                || !unit.chasing() || unit.type() == null
                || unit.type().maxAttackRange() > 1
                || World.battleNetRangedChaseUnit(unit)) {
            return;
        }
        int direct = World.battleNetFirstBresenhamHeading(
                unit.tileX(), unit.tileY(), target.tileX(), target.tileY());
        if (direct < 0 || direct >= Direction.COUNT) {
            return;
        }
        int stride = world.battleNetMovementStride(unit);
        int currentDistance = Math.max(
                Math.abs(unit.tileX() - target.tileX()),
                Math.abs(unit.tileY() - target.tileY()));
        boolean reversingDepartedWallStep = currentDistance >= 4
                && unit.lastStepHeading() >= 0
                && unit.lastStepHeading() < Direction.COUNT
                && Math.floorMod(unit.lastStepHeading() - direct,
                        Direction.COUNT) == Direction.COUNT / 2;
        if (reversingDepartedWallStep) {
            // A paid recovery which has just consumed the compass face
            // directly away from a still-distant quarry returns to the full
            // wall writer. It is not the near-skirt naked probe served below.
            // XHuman 12 slot 1501 parks its north/NE tail at fixture 177,
            // writes SE,NE,SE,E... on 178 and retains that buffer through the
            // next residual. Collapsing it to the accepted SE byte loses NE,
            // so Java cannot make the fixture-194 continuation.
            world.planTowardsAfterRefusalBand(unit, target, true);
            if (unit.pathLength() > 0) {
                unit.setPathGoal(target.tileX(), target.tileY());
                unit.setRouteSpent(false);
                unit.setWaitCycles(0);
                unit.setBattleNetChaseEmptyRouteReplan(false);
                unit.setBattleNetDirectRecoveryGeneration(0);
                return;
            }
        }
        int heading = -1;
        int acceptedDistance = Integer.MAX_VALUE;
        for (int turn = 0; turn < Direction.COUNT; turn++) {
            int candidate = Math.floorMod(direct - turn, Direction.COUNT);
            int nextX = unit.tileX()
                    + Direction.deltaX(candidate) * stride;
            int nextY = unit.tileY()
                    + Direction.deltaY(candidate) * stride;
            int nextDistance = Math.max(
                    Math.abs(nextX - target.tileX()),
                    Math.abs(nextY - target.tileY()));
            if (nextDistance < currentDistance
                    && world.canEnter(unit, nextX, nextY)) {
                heading = candidate;
                acceptedDistance = nextDistance;
                break;
            }
        }
        if (heading < 0) {
            return;
        }
        // The first accepted route after Attack recovery can finish into
        // another formation refusal. Native keeps that paid generation and
        // writes only the refreshed direct compass byte; a cold wall search
        // escapes around the battle line. XHuman 12 slot 1504 is the sealed
        // witness: RI20/collision one at fixture 139, then the one-byte south
        // route commits on fixture 140.
        unit.setPath(new PathFinder.Path(
                PathFinder.Result.FOUND, new int[] {heading}));
        unit.setPathGoal(target.tileX(), target.tileY());
        unit.setRouteSpent(false);
        unit.setWaitCycles(0);
        unit.setBattleNetChaseEmptyRouteReplan(false);
        if (unit.battleNetDirectRecoveryGeneration() >= 2
                && acceptedDistance <= 2) {
            // The accepted byte closes an already-paid recovery to the
            // range-two edge. Preserve that provenance across its pixel
            // residual so a later blocked direct probe escalates to a full
            // wall route instead of restarting the same refusal forever.
            unit.setBattleNetSaturatedNearRecoveryFullRoute(true);
        }
        unit.setBattleNetDirectRecoveryGeneration(0);
    }

    /** Completes a paid route whose settle selected a new in-range quarry. */
    private void finishBattleNetPaidWrapRetargetArrival(Unit unit) {
        // The old swing already paid the route which just settled, but the
        // newly selected in-range quarry owns fresh Attack construction.
        // Native XHuman 10 knight 1485 lands beside grunt 1477 on fixture 93,
        // records Attack@1922/3 and caller 0x4234CD's draw, then counts
        // 2,1,23. Preserve the resume provenance for that committed post-
        // construction body hold, but retire the old route owner now its
        // index is twenty.
        unit.setBattleNetAttackWrapDestArmPending(false);
        unit.setBattleNetChaseReplanResidualHold(false);
        unit.setBattleNetAttackResumeFromMove(true);
        unit.setBattleNetAttackOp0OutOfRange(true);
        AnimationSet set = unit.type() == null
                ? null : unit.type().animationSet();
        Animation attack = set == null ? null
                : set.get(AnimationSet.State.ATTACK);
        if (attack != null && unit.animation().current() != attack) {
            unit.animation().switchTo(attack);
        }
    }

    /**
     * Opens the three-visit Attack-four handoff after a full blocked Move band.
     */
    private boolean armBattleNetBlockedChaseAttackConstruction(Unit unit) {
        boolean retainedTailReachabilityProbe = unit != null
                && unit.battleNetAttackWrapDestArmPending()
                && unit.battleNetChaseEmptyRouteReplan()
                && unit.pathLength() == 1;
        boolean paidReplacementBand = unit != null
                && unit.battleNetChaseReplanResidualHold();
        boolean saturatedRetargetBandWake = unit != null
                && unit.battleNetSaturatedRetargetRouteBand();
        boolean paidEmptyReplacement = paidReplacementBand
                && unit.pathLength() == 0;
        boolean saturatedPaidEmptyResidualConstruction =
                paidEmptyReplacement
                && unit.battleNetCollisionCounter() == 3
                && unit.battleNetRefusals() == 1
                && unit.battleNetParkedRefusalHeading() >= 0
                && unit.battleNetParkedRefusalHeading() < Direction.COUNT
                && Direction.isDiagonal(unit.lastStepHeading())
                && unit.target() != null && unit.target().isAlive()
                && unit.target().type() != null
                && !unit.target().type().building()
                && !World.battleNetRangedChaseUnit(unit);
        if (world.battleNetSequence == null || unit == null
                || retainedTailReachabilityProbe
                || saturatedRetargetBandWake
                || unit.type() == null || !unit.chasing() || unit.isMoving()
                || !unit.stepDrained()
                || (!paidReplacementBand && unit.pathLength() < 4)
                || unit.battleNetCollisionCounter() <= 0
                || unit.battleNetRetargetResidualRoutePark()
                || (!paidReplacementBand
                        && Direction.isDiagonal(unit.peekHeading()))
                || unit.target() == null || !unit.target().isAlive()
                || world.targets.inAttackRange(unit, unit.target())) {
            return false;
        }
        int moveStart = world.idle.battleNetSequenceStart(unit,
                BattleNetSequence.MOVE_ANIMATION);
        if (moveStart < 0 || unit.battleNetSequenceOffset() != moveStart
                || unit.battleNetAnimationTimer() != 1) {
            return false;
        }
        int stride = world.battleNetMovementStride(unit);
        int nextX = paidEmptyReplacement ? unit.tileX()
                : unit.tileX()
                        + Direction.deltaX(unit.peekHeading()) * stride;
        int nextY = paidEmptyReplacement ? unit.tileY()
                : unit.tileY()
                        + Direction.deltaY(unit.peekHeading()) * stride;
        // A route installed and refused on the residual-settle callback has
        // already bought this construction. The blocker may have completely
        // vacated during Move 15; native still opens Attack 3,2,1 before it
        // is allowed to probe the replacement route again. Other cooperative
        // waits require the blocker to remain visibly mid-step here.
        if (!paidReplacementBand && world.canEnter(unit, nextX, nextY)) {
            return false;
        }
        Unit blocker = world.unitAt(nextX, nextY);
        // This is later than FUN_004379e0's ordinary cooperative wait. The
        // blocker may carry its own collision nibble by now (1495 carries one),
        // but it is visibly mid-stride away from the refused cell. Reusing the
        // stricter cooperative predicate therefore skips the native handoff.
        if (!paidReplacementBand
                && (blocker == null || blocker == unit || !blocker.isOnMap()
                        || blocker.isDying()
                        || !world.isAllied(unit.player(), blocker.player())
                        || !blocker.isMoving()
                        || !world.battleNetMoveAnimation(blocker))) {
            return false;
        }
        int attackStart = world.idle.battleNetSequenceStart(unit,
                BattleNetSequence.ATTACK_ANIMATION);
        if (attackStart < 0) {
            return false;
        }
        unit.setBattleNetSequenceOffset(attackStart);
        unit.setBattleNetAnimationTimer(3);
        if (saturatedPaidEmptyResidualConstruction) {
            // The paid residual has reached active-order Still before it
            // promotes the retained Attack. That callback owns the ordinary
            // land-idle choice even though the replacement route is already
            // queued. XHuman 12 slot 1481 is the sealed witness: fixture 216
            // clears the diagonal route park, records 0040AD58, and exposes
            // Attack 3,2,1 before its fixture-219 east refill. Omitting this
            // draw reassigns the fixture-225 grunt damage roll.
            world.idle.advanceBattleNetActiveOrderIdleRandom(unit);
            // The parked heading proved which old route generation paid the
            // constructor, but it is not a byte of the replacement route.
            // Retire that generation as native does when Attack start/3 is
            // installed; the fresh path writer chooses east independently.
            unit.setBattleNetCollisionCounter(0);
            unit.setBattleNetRefusals(0);
        }
        if (!paidReplacementBand) {
            unit.setBattleNetChaseReplanResidualHold(false);
        }
        unit.setBattleNetBlockedChaseAttackConstruction(true);
        AnimationSet set = unit.type().animationSet();
        Animation attack = set == null
                ? null : set.get(AnimationSet.State.ATTACK);
        if (attack != null && unit.animation().current() != attack) {
            unit.animation().switchTo(attack);
        }
        return true;
    }

    /**
     * Parks a blocked sea-combat leftover behind native Attack construction.
     *
     * <p>This is the combat twin of Patrol's cooperative naval hold. Patrol
     * keeps the blocked heading under Move timer fifteen because the body in
     * front is part of the route's traffic contract. An active attack chase
     * instead discards that heading, pays Attack 3,2,1, exposes an empty Move
     * start for one visit, and replans. Keeping those cases in the same
     * fifteen-count arm is a visible combat freeze.</p>
     */
    boolean armBattleNetNavalBlockedChaseConstruction(Unit unit) {
        if (world.battleNetSequence == null || unit == null
                || unit.type() == null || !unit.type().seaUnit()
                || !unit.battleNetDoubleStep()
                || unit.target() == null || !unit.target().isAlive()
                || !unit.chasing() || unit.isMoving()
                || !unit.stepDrained()) {
            return false;
        }
        int attackStart = world.idle.battleNetSequenceStart(unit,
                BattleNetSequence.ATTACK_ANIMATION);
        if (attackStart < 0) {
            return false;
        }
        unit.clearPath();
        unit.setRouteSpent(false);
        unit.setWaitCycles(0);
        unit.setBattleNetOrderDelay(0);
        unit.setBattleNetSequenceOffset(attackStart);
        unit.setBattleNetAnimationTimer(3);
        unit.setBattleNetChaseReplanResidualHold(true);
        unit.setBattleNetBlockedChaseAttackConstruction(true);
        AnimationSet set = unit.type().animationSet();
        Animation attack = set == null ? null
                : set.get(AnimationSet.State.ATTACK);
        if (attack != null && unit.animation().current() != attack) {
            unit.animation().switchTo(attack);
        }
        return true;
    }


    /**
     * Backs a siege engine away from something standing too close.
     *
     * <p>{@code COrder_Attack::MoveToBetterPos}. The new spot is
     * {@code MinAttackRange} away in the direction opposite the target, give
     * or take, which is why a catapult under attack shuffles rather than
     * retreating in a straight line.
     */
    void moveToBetterPos(Unit unit, Unit goal) {
        if (!unit.canMove()) {
            // Immobile and unable to shoot what is on top of it: upstream ends
            // the order rather than standing there aiming.
            world.finishAttackOrder(unit);
            return;
        }
        if (unit.isMoving() || unit.pathLength() > 0) {
            // Already backing away; let the step finish before choosing again,
            // or the unit picks a new spot every cycle and never leaves.
            stepMoveTowardsTarget(unit);
            return;
        }
        int[] pos = world.rndPosInDirection(unit.tileX(), unit.tileY(),
                goal.tileX() + Math.max(1, goal.type().tileWidth()) / 2,
                goal.tileY() + Math.max(1, goal.type().tileHeight()) / 2,
                true, unit.type().minAttackRange(), 3);
        if (world.movement.route(unit, pos[0], pos[1])) {
            unit.setPathGoal(pos[0], pos[1]);
            stepMoveTowardsTarget(unit);
        }
    }


    /**
     * One cycle of walking while under an attack order.
     *
     * @return whether the walk is still waiting or on its way
     */
    boolean stepMoveTowardsTarget(Unit unit) {
        return stepMoveTowardsTarget(unit, false);
    }


    private boolean stepMoveTowardsTarget(
            Unit unit, boolean deferSettledRetargetSync) {
        return stepMoveTowardsTarget(
                unit, deferSettledRetargetSync, unit.target());
    }


    private boolean stepMoveTowardsTarget(
            Unit unit, boolean deferSettledRetargetSync,
            Unit targetBeforeStep) {
        int tileXBeforeStep = unit.tileX();
        int tileYBeforeStep = unit.tileY();
        Unit paidQuarry = unit.target();
        boolean clearPaidMovingQuarryCollision =
                unit.battleNetRetargetResidualRoutePark()
                && unit.battleNetCollisionCounter() == 1
                && unit.battleNetRefusals() == 0
                && unit.battleNetPathStepsTaken() == 1
                && unit.pathLength() > 1
                && paidQuarry != null
                && paidQuarry.order() == Unit.Order.HARVEST;
        Unit.Order saved = unit.order();
        // setOrder(MOVE/ATTACK) clears battleNetSequenceOffset so Still
        // cursors cannot fire mid-walk. Chase Move OP0 cadence needs that
        // offset to survive the temporary MOVE flip and the restore (XHuman 9
        // skeleton 1431).
        int savedOffset = unit.battleNetSequenceOffset();
        int savedTimer = unit.battleNetAnimationTimer();
        boolean savedStepReady = unit.battleNetChaseStepReady();
        boolean savedBorrowedMove = unit.battleNetBorrowedMoveForStep();
        unit.setOrder(Unit.Order.MOVE);
        unit.setBattleNetSequenceOffset(savedOffset);
        unit.setBattleNetAnimationTimer(savedTimer);
        unit.setBattleNetChaseStepReady(savedStepReady);
        // MOVE is only a Java call-seam surrogate here. Native remains inside
        // COrder_Attack::MoveToTarget, so an occupied-quarry arrival must not
        // mistake the temporary label for weaker movement ownership and snap
        // a residual of eight pixels. XHuman 9 skeleton 1431 pays its last
        // ten pixels through fixture 46 before Attack owns the unit.
        unit.setBattleNetBorrowedMoveForStep(true);
        try {
            world.movement.stepMove(unit, false);
        } finally {
            unit.setBattleNetBorrowedMoveForStep(savedBorrowedMove);
        }
        boolean underWay = unit.order() == Unit.Order.MOVE;
        int offsetAfter = unit.battleNetSequenceOffset();
        int timerAfter = unit.battleNetAnimationTimer();
        boolean readyAfter = unit.battleNetChaseStepReady();
        // Borrowed MOVE leftover under Attack used to promote Attack Ground
        // and leave it stuck, because only MOVE/STILL were restored.
        // Restore that specific chase transition as Attack. Other saved
        // orders must not overwrite a real order transition made by
        // stepMove: doing so trapped a replayed Attack-Move peon and made
        // later player Move/Attack clicks acknowledge without progressing.
        if (unit.order() == Unit.Order.MOVE || unit.order() == Unit.Order.STILL
                || (saved == Unit.Order.ATTACK
                        && unit.order() == Unit.Order.ATTACK_GROUND)) {
            unit.setOrder(saved);
        }
        unit.setBattleNetSequenceOffset(offsetAfter);
        unit.setBattleNetAnimationTimer(timerAfter);
        unit.setBattleNetChaseStepReady(readyAfter);
        if (clearPaidMovingQuarryCollision && unit.isMoving()
                && (unit.tileX() != tileXBeforeStep
                        || unit.tileY() != tileYBeforeStep)) {
            // Attack construction owns the boundary between successive legs
            // of this cached quarry route. Once the next retained byte is
            // physically accepted, native clears the collision generation:
            // Human 8 slot 1520 exposes collision one through fixture 232,
            // commits east on 233, and carries zero into the later refusal.
            unit.setBattleNetCollisionCounter(0);
            unit.setBattleNetRefusals(0);
        }
        // Approach residual settled in weapon range under Attack with unpaid
        // order-time pending: pay FUN_004234b0 on this visit. XHuman 12 grunt
        // 225 (native 1375) lands on 12,86 next to tower 13,86 at fixture 40;
        // the temporary MOVE flip for stepMove never re-entered Attack OP0, so
        // pending SyncRand stayed unpaid until sibling grunt 221's 25-cycle
        // re-arm at 47 (seed 2781e494 vs native c46b9b3d from caller 0x4234CD).
        // Only order-time pending (not a remaining==0 re-arm) so mid-fight
        // residual settles do not double-debit (XHuman 9).
        if (saved == Unit.Order.ATTACK && !unit.isMoving()
                && unit.pathLength() == 0
                && unit.battleNetPendingMeleeSyncRand()) {
            Unit target = unit.target();
            if (target != null && world.targets.inAttackRange(unit, target)) {
                unit.setChasing(false);
                unit.setFighting(true);
                if (deferSettledRetargetSync) {
                    world.armBattleNetAttackStart(unit);
                    unit.setBattleNetOrderDelay(2);
                } else {
                    boolean paidWrapRetarget =
                            unit.battleNetAttackWrapDestArmPending()
                            && target != targetBeforeStep;
                    world.consumeBattleNetPendingMeleeSyncRand(unit);
                    if (paidWrapRetarget) {
                        finishBattleNetPaidWrapRetargetArrival(unit);
                    }
                }
            }
        }
        if (World.TRACE_MOVING != null
                && unit.id() == World.TRACE_MOVING_ID) {
            System.err.printf("JMOVEWRAP cycle=%d unit=%d under=%d order=%s "
                            + "seq=%d/%d path=%d collision=%d empty=%d wrap=%d%n",
                    world.cycle, unit.id(), underWay ? 1 : 0, unit.order(),
                    unit.battleNetSequenceOffset(),
                    unit.battleNetAnimationTimer(), unit.pathLength(),
                    unit.battleNetCollisionCounter(),
                    unit.battleNetChaseEmptyRouteReplan() ? 1 : 0,
                    unit.battleNetAttackWrapDestArmPending() ? 1 : 0);
        }
        return underWay;
    }


    /**
     * Applies one blow.
     *
     * <p>The formula is {@code CalculateDamageStats}:
     *
     * <pre>
     *   damage  = max(basicDamage - targetArmor, 1)
     *   damage += piercingDamage
     *   damage -= rand() % ((damage + 2) / 2)
     * </pre>
     *
     * The final subtraction is what makes Warcraft II's combat swingy: every
     * blow lands for somewhere between just over half and all of its nominal
     * damage.
     */
    /**
     * Resolves an attack: throws something, or strikes directly.
     *
     * <p>Sixty-six of the shipped unit types name {@code missile-none} and hit
     * on the spot. The rest launch a projectile that has to cross the ground
     * first, and a great deal follows from that: the target can die, move, or
     * be healed before the shot lands, and where it lands can hurt more than
     * the thing it was aimed at.
     */
    void hit(Unit attacker, Unit target) {
        MissileType missile = world.projectiles.missileFor(attacker);
        if (missile != null && !missile.isNone()) {
            // OP10 is the authoritative retail firing boundary. On repeated
            // stationary swings the Java presentation can trail it by eight
            // cycles (Human 13 axethrower 1506: native axe born @78 while the
            // visual hit callback arrived @86). If OP10 already constructed
            // that shot, this later callback is presentation only.
            if (world.battleNetSequenceProjectileFired.contains(attacker)) {
                world.logBattleNetPend("suppress-after-op10", attacker, target,
                        null, "sequence-projectile-already-fired", -1);
                return;
            }
            // Approach-hold OP0 stall: presentation can still fire while the
            // Attack sequence is parked on attackStart with timer 63 (Human 13
            // axe 117: pend-put at world 35 with seq 887 timer 56). That queues
            // a stale pending shot native never constructs. Only this hold
            // flag suppresses -- mid-wait presentation at attackStart timer 3
            // for building targets (XHuman 12) must keep the cycle-end debit.
            if (attacker.canMove() && attacker.battleNetAttackResumeHoldActive()) {
                if (World.BNE_PEND_TRACE) {
                    world.logBattleNetPend("suppress-op0-hold", attacker, target,
                            null, "presentation-during-approach-hold", -1);
                }
                return;
            }
            // Without a program there is no opcode ten to hand the shot to,
            // and the pend below is collected nowhere else: the arrow was
            // built, filed, and never flew. Same fault as the melee pend
            // further down, and the same answer -- fire it here.
            boolean immediateBattleNetShot = world.battleNetSequence == null
                    || !attacker.canMove()
                    || world.battleNetInlineAttackMarkers.remove(attacker);
            Missile shot = world.projectiles.launch(attacker, target, missile);
            // Retail BNE's ordinary projectile constructor
            // (FUN_0040fb10) gives every shot an independent pixel
            // offset of -3..4 on both aim axes. The draws come from the
            // asynchronous stream (Human 13 catapults before critter
            // 1572 wanders) and the geometry feeds remaining distance,
            // so a pure tile-centre aim can land a speed-step later
            // than native (XHuman 10 farm 1536 axe at fixture 27).
            if (immediateBattleNetShot) {
                world.logBattleNetPend("immediate-prepare", attacker, target,
                        shot, attacker.canMove() ? "inline-or-tower"
                                : "tower", world.cycle);
                world.prepareBattleNetProjectile(shot, attacker.canMove());
            } else {
                // Java's presentation animation can reach its attack
                // command one call before BNE script.bin reaches opcode
                // ten. Remember the shot and debit the native stream at
                // that authoritative boundary, not at the early visual
                // frame -- unless presentation is already mid-wait past
                // the OP10 tick (same rule as melee below).
                world.projectiles.queuePendingAttack(attacker, shot, world.cycle);
                world.logBattleNetPend("pend-put", attacker, target, shot,
                        "presentation-hit", -1);
                // Mobile weapons: presentation can sit mid-wait before
                // OP10. Building targets spend the three constructor draws
                // (damage + two aim jitters) on this unit's visit so the
                // asynchronous stream matches native pool order: reverse
                // creation walks high id first, so axe 127 fires before
                // lower-id Still OP0s (knight 125, footman 122, …), just as
                // native slot 1473 fires before slots 1475+. Cycle-end debit
                // used to hold those three draws until after every later
                // idle in the same loop, so XHuman 12's footman melee at
                // fixture 38 rolled idle ordinals (5+4) instead of native
                // damage ordinals (6+6) and left grunt 1448 at 38 HP not 35.
                // Flight still waits for OP10 -- arming motion here was what
                // reordered Human 13 critter 1576 (still vs MOVE at fixture
                // 34), not the constructor draws themselves.
                if (attacker.canMove()
                        && attacker.battleNetSequenceOffset() >= 0
                        && attacker.battleNetAnimationTimer() > 1
                        && target.type() != null
                        && target.type().building()) {
                    // Building targets only: XHuman 12 tower axe. Unit
                    // targets keep OP10 constructor timing so Human 13
                    // person/computer axes against troops do not reorder
                    // the critter stream before fixture 34.
                    Missile pend = world.battleNetPendingProjectileShots
                            .get(attacker);
                    if (pend != null && !pend.battleNetConstructorDrawn()) {
                        world.logBattleNetPend("mid-visit-debit", attacker,
                                target, pend, "building-mid-wait", -1);
                        world.projectiles.debitBattleNetProjectileConstructor(
                                pend, true);
                    }
                    // Presentation left Attack wait at 3 while OP10 was
                    // still two ticks away; debit-only draws ran now but
                    // flight waited for that OP10, so axe 127→tower 117
                    // rem 148 native from fixture 31 first flew in Java
                    // around 35 (two motion draws short before the rock
                    // free). Collapse the wait like presentation-ahead
                    // melee so OP10 arms motion next unit visit -- except
                    // after a ranged residual-open, where collapsing put
                    // OP10 on the same visit as the constructor debit and
                    // armed flight one cycle early (extra stepMissiles
                    // draw at world 34, splash REG on tower 1370).
                    if (!attacker.battleNetRangedResidualOpen()) {
                        attacker.setBattleNetAnimationTimer(1);
                    }
                }
            }
            return;
        }
        // Only when there is a program to hand the blow to. The pend below is
        // collected at opcode ten of the Attack sequence and nowhere else, so
        // without `Rez\script.bin` -- a 1995 installation, any non-BNE source,
        // or a hand-built world -- every melee swing was filed and never
        // resolved, and no unit that could walk ever damaged anything. Retail
        // has no such state; this is the older approximation the loader's own
        // documentation promises to fall back on, and falling through to
        // applyDamage below is it.
        if (world.battleNetSequence != null
                && attacker.canMove()
                && !world.battleNetInlineAttackMarkers.remove(attacker)) {
            // Sequence OP10 already landed this multi-leftover swing; do not
            // pend a second presentation blow (Human 13 ogre 90 double-hit).
            if (attacker.battleNetSequenceMeleeLanded()) {
                return;
            }
            // ChonkCraft's presentation animation can invoke the melee command
            // one call before retail script.bin reaches opcode ten. Preserve
            // the named victim, but let the native animation boundary own
            // the damage roll and hit side effects.
            Unit previousPendingMelee =
                    world.battleNetPendingMeleeHits.put(attacker, target);
            // Presentation Attack can fire while the Attack sequence is still
            // mid-wait before opcode 10. Standing 1531 pends when the wait is
            // already 1, so OP10 lands next cycle (native fixture 16). Chase
            // mid-program waits that are not yet the pre-OP10 hold still
            // collapse so OP10 is not missed (Human 5 1528/1532 barracks).
            //
            // Do not collapse attack-start OP0 (Human 13 knight 1490 splash
            // bulk hold) or the pre-OP10 wait itself (offset at opcode 10:
            // Human 13 knight 1500 native t3..t1 on 1935, damage fixture 50).
            int attackStart = world.idle != null
                    ? world.idle.battleNetSequenceStart(attacker,
                            BattleNetSequence.ATTACK_ANIMATION)
                    : -1;
            int seqOff = attacker.battleNetSequenceOffset();
            // Parked on the pre-OP10 wait (offset is the OP10 byte, timer the
            // body wait before processing it): native holds t3..t1 then fires
            // (Human 13 knight 1500 1935→damage fixture 50). Do not collapse
            // or resolve here -- that advanced the blow to fixture 47.
            boolean onPreOp10Wait = world.battleNetSequence != null
                    && seqOff >= 0
                    && world.battleNetSequence.opcodeAt(seqOff) == 10;
            // Pre-OP10 wait: keep native t3..t1 hold against mobile targets
            // after a leftover residual (Human 13 knight 1500 → ogre 1510
            // fixture 50). Collapse when the residual was an empty route
            // (Human 13 grunt 93 → knight fixture 46) or the target is a
            // building (Human 5 barracks / XHuman 12 tower 1370 fixture 32):
            // those OP0s finish late and native process lands on entry.
            boolean buildingTarget = target != null && target.type() != null
                    && target.type().building();
            boolean allowPreOp10Collapse = onPreOp10Wait
                    && (attacker.battleNetResidualEmptyRouteSettle()
                            || buildingTarget);
            // Once a mobile fighter has settled into an ordinary repeated
            // swing, the presentation frame is not allowed to shorten the
            // native program's waits.  Human 13 ogre 1510/Java 90 is the
            // compact mobile-target witness: presentation reached hit() while
            // offset 658 still held timer 2; collapsing it made OP10 and every
            // later blow two visits early. Buildings are not a blanket
            // exception. XHuman 10 ogre 1538/Java 62 reaches presentation hit
            // during recovery at 666/4, but native keeps 666/3,2,1 and does not
            // strike guard tower 1537 until the next OP10 at fixture 193.
            // The proved building bridge is specifically a presentation hit
            // already parked on the pre-OP10 wait (Human 5 barracks / XHuman
            // 12 tower 1370); a just-settled empty route remains the other
            // bridge.
            boolean allowMidProgramCollapse = attacker
                    .battleNetResidualEmptyRouteSettle()
                    || (buildingTarget && onPreOp10Wait);
            if (seqOff >= 0
                    && attacker.battleNetAnimationTimer() > 1
                    && (attackStart < 0 || seqOff != attackStart)
                    && allowMidProgramCollapse
                    && (!onPreOp10Wait || allowPreOp10Collapse)) {
                attacker.setBattleNetAnimationTimer(1);
                Unit pend = world.battleNetPendingMeleeHits.remove(attacker);
                if (pend != null && (pend.isAlive()
                        || pend.order() == Unit.Order.DYING)) {
                    applyBattleNetSequenceMeleeDamage(attacker, pend);
                    // This is the blow for the current sequence. OP10 still
                    // must not strike again.
                    attacker.setBattleNetSequenceMeleeLanded(true);
                    // A proved presentation collapse is the native OP10
                    // visit, not merely an early copy of its damage. Leaving
                    // the cursor parked on OP10 added one cycle to every
                    // later swing (Human 13 grunt 1507: 46->72 instead of
                    // retail's 46->71). Consume that opcode now so the entire
                    // repeated attack loop retains its retail period.
                    if (onPreOp10Wait) {
                        BattleNetSequence.Tick op10 = world.battleNetSequence
                                .tick(seqOff, 1);
                        if (op10.valid() && op10.inlineActionMarker()) {
                            attacker.setBattleNetSequenceOffset(op10.offset());
                            attacker.setBattleNetAnimationTimer(op10.timer());
                        }
                    }
                }
            }
            return;
        }
        if (attacker.battleNetSequenceMeleeLanded()) {
            return;
        }
        // And the blow only counts as retail's opcode-ten melee when there was
        // an opcode ten. FUN_00418370 floors basic-minus-armour at nought and
        // rolls on the asynchronous stream; with no program to have reached it
        // the ordinary CalculateDamageStats applies, which floors at one. A
        // footman with two damage against fifty armour has to leave a mark.
        if (world.battleNetSequence != null && attacker.canMove()) {
            world.battleNetNativeMeleeDamage.add(attacker);
        }
        applyDamage(attacker, target, 1);
    }


    /** Damage from one unit to another, divided by a splash falloff. */
    void applyDamage(Unit attacker, Unit target, int falloff) {
        applyDamage(attacker, target, falloff, null);
    }


    /**
     * Damage from one unit to another, divided by a splash falloff.
     *
     * @param missile the shot that carried it, or {@code null} for a blow
     *                struck by hand. {@code MissileHitsGoal} lets a missile
     *                overrule the firer's stats, which is the only way a spell
     *                can name its own damage
     */
    void applyDamage(Unit attacker, Unit target, int falloff, Missile missile) {
        // The roll happens first, and it happens even for something the blow
        // cannot hurt. Upstream splits the two across a caller and a callee:
        // {@code MissileHitsGoal} works the damage out for any goal not
        // already dying and hands it to
        // {@code HitUnit}, which is where the invulnerable are turned away
        // Since {@code CalculateDamageStats} takes a
        // number from the shared stream, skipping the arithmetic skips a draw.
        //
        // What made that visible: on demo02 a peasant dies at 0,25 on cycle
        // 13, its death animation spawns a vision revealer over the body, and
        // the second shell lands on cycle 16 and rolls damage against the
        // revealer -- which is indestructible, takes nothing, and still costs
        // upstream the number. This implementation turned it away before rolling and was
        // one draw behind from that cycle on.
        int damage = world.damageFor(attacker, target, falloff, missile);
        applyRolledDamage(attacker, target, damage, falloff, missile);
    }


    /** Applies an already-priced blow without consuming a second RNG value. */
    private void applyRolledDamage(Unit attacker, Unit target, int damage,
            int falloff, Missile missile) {
        world.causalTrace.event(world.cycle, "combat.damage", target.id(),
                "fixture_cycle", Math.max(0, world.cycle - 2),
                "attacker", attacker == null ? -1 : attacker.id(),
                "target", target.id(),
                "damage", damage,
                "hp_before", target.hitPoints(),
                "lethal", damage > 0 && target.hitPoints() - damage <= 0,
                "carrier", missile == null || missile.type() == null
                        ? "melee" : missile.type().ident(),
                "falloff", falloff);
        if (System.getenv("CHONKCRAFT_TRACE_BNE_DAMAGE") != null
                && target != null
                && (System.getenv("CHONKCRAFT_TRACE_BNE_DAMAGE").isBlank()
                        || Integer.toString(target.id()).equals(
                                System.getenv("CHONKCRAFT_TRACE_BNE_DAMAGE").trim())
                        || (attacker != null && Integer.toString(attacker.id())
                                .equals(System.getenv("CHONKCRAFT_TRACE_BNE_DAMAGE")
                                        .trim())))) {
            System.err.printf(
                    "JBNEDMG cycle=%d atk=%d(%s)@%d,%d tgt=%d(%s)@%d,%d "
                            + "dmg=%d hp=%d->%d miss=%s%n",
                    world.cycle,
                    attacker == null ? -1 : attacker.id(),
                    attacker == null || attacker.type() == null ? "?"
                            : attacker.type().ident(),
                    attacker == null ? -1 : attacker.tileX(),
                    attacker == null ? -1 : attacker.tileY(),
                    target.id(),
                    target.type() == null ? "?" : target.type().ident(),
                    target.tileX(), target.tileY(),
                    damage, target.hitPoints(),
                    Math.max(0, target.hitPoints() - Math.max(0, damage)),
                    missile == null ? "-" : missile.type().ident());
        }
        if (missile != null && System.getenv("CHONKCRAFT_TRACE_MISSILE") != null) {
            // The implementation-side twin of MissileHitsGoal's MISSILEDBG print.
            System.err.println("JMISSILE " + world.cycle + " type=" + missile.type().ident()
                    + " goal=" + target.id() + " " + (target.type() == null ? "?"
                            : target.type().ident()) + " hp=" + target.hitPoints()
                    + " splash=" + falloff);
        }
        if (target.type() != null && target.type().indestructible()
                || target.hasBuff(Unit.Buff.UNHOLY_ARMOR)) {
            // HitUnit's second line. A Circle of Power, an oil
            // patch, a start-location marker and ChonkCraft's vision revealers all
            // carry the flag, and the Dark Portal missions are won by walking
            // onto a Circle of Power -- one a stray catapult shot could level.
            //
            // The buff is the other half of that same line -- "vladi: units
            // with active UnholyArmour are invulnerable" -- and it is the
            // whole of the death knight's Unholy Armour, which until now cost
            // a hundred mana and did nothing whatever.
            return;
        }
        if (damage <= 0) {
            // HitUnit's first line: splash can produce nothing, and nothing is
            // not a hit. It raises no cry and provokes no answer.
            return;
        }
        noteAttacked(attacker, target);
        AiPlayer targetAi = world.ais.get(target.player());
        if (System.getenv("CHONKCRAFT_TRACE_AIHELP") != null) {
            System.err.printf("JAIHELPPROBE cycle=%d attacker=%d target=%d p%d ai=%d wall=%d damage=%d%n",
                    world.cycle, attacker == null ? -1 : attacker.id(), target.id(), target.player(),
                    targetAi == null ? 0 : 1,
                    target.type() != null && target.type().wall() ? 1 : 0, damage);
        }
        if (targetAi != null && attacker != null
                && (target.type() == null || !target.type().wall())) {
            targetAi.helpMe(world, attacker, target);
        }
        if (attacker != null && (target.type() == null || !target.type().wall())) {
            world.battleNetSpatialHitHelp(attacker, target);
        }
        // Lethal damage leaves the last living hit-point count on the corpse.
        // XHuman 10 footman 1492 dies at fixture 42 with native HP 60 (full
        // living value) while Java zeroed on the killing blow; the same
        // DYING-HP report is open on XHuman 2 footman 1548. Subtract only when
        // the unit survives the hit.
        int before = target.hitPoints();
        if (before - damage <= 0) {
            // The killer is free to look elsewhere at once.
            if (attacker != null) {
                attacker.setThreshold(0);
            }
            world.kill(target, attacker);
            return;
        }
        target.setHitPoints(before - damage);
        // A direct hit taken while a chase is still draining its Move body
        // survives into the Attack opening. XHuman 12 grunt 1448 takes four
        // direct arrow/melee hits on its final east residual, settles beside
        // footman 1449 on fixture 40, then native keeps Attack@2539 for the
        // 23-call body hold instead of striking on fixture 53. Direct chips
        // that already land on Attack OP0 do not qualify (Human 13 knight
        // 1500); this bridge is deliberately restricted to the approach.
        noteBattleNetChaseApproachDamage(target);
        world.showDamage(target, damage);
        world.catchFire(target);
        // Retail BNE's direct hit path calls FUN_0040a9d0 before subtracting
        // hit points. That helper installs the attacker and offers nearby
        // same-owner defenders, but it never runs ChonkCraft's
        // HitUnit_RunAway/AttackBack tail. The BNE spatial-help bridge above
        // owns the native reaction; running this second policy made a struck
        // zeppelin spend three synchronized draws choosing a flee square that
        // does not exist in the native state.
    }


    /** Carries direct approach damage into the first in-range Attack OP0. */
    private void noteBattleNetChaseApproachDamage(Unit victim) {
        if (victim == null || !victim.canMove()
                || world.battleNetSequence == null || world.idle == null
                || !victim.chasing() || !onBattleNetChaseMoveBody(victim)) {
            return;
        }
        victim.setBattleNetAttackOp0Damaged(true);
        if (World.BNE_IDLE_TRACE) {
            System.err.printf("JBNEATTACKOP0DMG approach cycle=%d unit=%d "
                            + "off=%d%n",
                    world.cycle, victim.id(), victim.battleNetSequenceOffset());
        }
    }


    /**
     * Marks a mobile unit that lost hit points while its Attack sequence sat
     * on the opening OP0 wait.
     *
     * <p>Human 13 knight 1490 takes catapult splash at fixture 35 while the
     * cursor is still on Attack@1922. Native does not walk that OP0 into
     * windup; the next OP0 fire arms timer {@code bodyWaitSum - 1} (23) and
     * keeps the cursor on attack-start, so OP10 never lands through fixture
     * 44. Splash writes hit points directly and never reaches
     * {@link #applyDamage}, which is why the marker is shared from both
     * paths.</p>
     */
    void noteBattleNetAttackOp0Damage(Unit victim) {
        if (victim == null || !victim.canMove() || world.battleNetSequence == null
                || world.idle == null) {
            return;
        }
        int attackStart = world.idle.battleNetSequenceStart(victim,
                BattleNetSequence.ATTACK_ANIMATION);
        if (attackStart < 0
                || victim.battleNetSequenceOffset() != attackStart) {
            return;
        }
        victim.setBattleNetAttackOp0Damaged(true);
        if (World.BNE_IDLE_TRACE) {
            System.err.printf("JBNEATTACKOP0DMG arm cycle=%d unit=%d "
                            + "off=%d%n",
                    world.cycle, victim.id(), attackStart);
        }
    }


    /**
     * Raises the under-attack cue, on upstream's terms.
     *
     * <p>{@code HitUnit_LastAttack}. The implementation kept one
     * timestamp per player and a flat twenty-second gag, so a base being
     * attacked in three places at once was announced once and then not again
     * for twenty seconds however the battle went. Upstream's rule is in two
     * parts and both parts matter to a player:
     *
     * <ul>
     *   <li>A unit hit within the last two seconds says nothing at all. This is
     *       per unit, not per player, and it is what stops a single footman
     *       under fire from drowning out everything else -- a blow lands
     *       several times a second.</li>
     *   <li>Past that, the cry itself waits two seconds, and then goes up only
     *       if two minutes have passed or the trouble is more than fourteen
     *       tiles from where it last was. So a grinding siege of one base is
     *       announced every two minutes and an attack that opens on a second
     *       front is announced two seconds later.</li>
     * </ul>
     *
     * <p>Walls are exempt outright: they are struck constantly by anything
     * breaking through and have no voice to cry with.
     */
    void noteAttacked(Unit attacker, Unit target) {
        long lastHit = target.attackedCycle();
        target.setAttackedCycle(Math.max(1, world.cycle));
        if (target.type() != null && target.type().wall()) {
            return;
        }
        if (lastHit != 0 && world.cycle <= lastHit + World.CYCLES_PER_SECOND * 2) {
            return;
        }
        if (world.attackNotices.size() < 64) {
            world.attackNotices.add(new World.AttackNotice(target));
        }
        World.HelpCry cry = world.helpCries.get(target.player());
        if (cry != null && cry.quietUntil() >= world.cycle) {
            return;
        }
        boolean elsewhere = cry == null
                || cry.cycle() + World.CYCLES_PER_SECOND * 120 < world.cycle
                || Math.abs(target.tileX() - cry.tileX()) > World.HELP_CRY_RADIUS
                || Math.abs(target.tileY() - cry.tileY()) > World.HELP_CRY_RADIUS;
        if (!elsewhere) {
            return;
        }
        world.helpCries.put(target.player(), new World.HelpCry(world.cycle,
                world.cycle + World.CYCLES_PER_SECOND * 2, target.tileX(), target.tileY()));
        world.announce(target, "help");
    }


    /**
     * Turns a unit round on whoever hit it.
     *
     * <p>{@code HitUnit_AttackBack}. The point of it is that reaction range
     * does not come into it at all: a unit is answered wherever the blow came
     * from, provided it can actually get there. That last clause is the only
     * gate, and it is the reason a footman does not set off across a river
     * after a ship.
     */
    void attackBack(Unit attacker, Unit target) {
        if (attacker == target.target()) {
            return;
        }
        if (attacker.player() == target.player()
                || !world.isEnemyPlayer(target.player(), attacker.player())
                || !world.targets.canTarget(target, attacker)) {
            return;
        }
        boolean computer = !world.isPerson(target.player());
        Unit.Order interrupted = target.currentAction();
        if (interrupted == Unit.Order.ATTACK || interrupted == Unit.Order.ATTACK_MOVE) {
            // A target the player picked is not overridden by a poke in the
            // back; one the unit picked for itself is.
            if (!target.autoTargeting() && !computer) {
                return;
            }
            if (world.targets.isVisibleAsGoal(target.player(), attacker)) {
                if (world.isReachable(target, attacker)) {
                    target.setUnderAttack(World.UNDER_ATTACK_CYCLES);
                    // Not a retarget: the attacker is banked through
                    // OfferNewTarget, weighed against any standing offer by
                    // the same priority the scans use, and the order's own
                    // targeting pass decides (
                    // The game ). level13h's ogre keeps its
                    // fleeing wise-man through the knight's blow; a port
                    // that turned it round here was the map's cycle-38
                    // divergence.
                    Unit standing = target.offeredTarget();
                    if (standing == null || !standing.isAlive()
                            || !world.targets.isVisibleAsGoal(target.player(), standing)
                            || world.targets.targetPriority(target, attacker)
                                    > world.targets.targetPriority(target, standing)) {
                        target.setOfferedTarget(attacker);
                    }
                }
                return;
            }
            // Shot from somewhere unseen. If what it is already fighting can
            // fight back, that is the better use of its time.
            if (target.target() != null && target.target().isAggressive()) {
                return;
            }
        }
        // Answering one aggressor at a time: while the counter runs, a fresh
        // blow does not start the whole decision again.
        if (target.underAttack() > 0) {
            return;
        }
        switch (interrupted) {
            case STAND_GROUND, ATTACK_GROUND, EXPLORE -> {
                // A person's unit told to hold or to bombard keeps doing it.
                // Only the computer breaks off.
                if (!computer) {
                    return;
                }
            }
            case ATTACK, ATTACK_MOVE, STILL, PATROL -> { }
            default -> {
                return;
            }
        }
        if (!world.isReachable(target, attacker)) {
            return;
        }
        int oldX = target.tileX();
        int oldY = target.tileY();
        int attackX = attacker.tileX();
        int attackY = attacker.tileY();
        if (!world.targets.isVisibleAsGoal(target.player(), attacker)) {
            int[] guessed = world.rndPosInDirection(target.tileX(), target.tileY(),
                    attacker.tileX() + Math.max(1, attacker.type().tileWidth()) / 2,
                    attacker.tileY() + Math.max(1, attacker.type().tileHeight()) / 2,
                    false, target.type().reactRangeComputer(), 2);
            attackX = guessed[0];
            attackY = guessed[1];
        }
        target.setUnderAttack(World.UNDER_ATTACK_CYCLES);
        if (!orderAttackMove(target, attackX, attackY)) {
            return;
        }
        // CommandAttack(..., On) appends the replacement behind the order it
        // just marked finished. CurrentAction therefore keeps reporting the
        // interrupted Still/Patrol for the rest of this cycle, and the pop
        // clears its wait on the next unit turn.
        target.rememberActionBeforeQueued(interrupted);
        if (interrupted == Unit.Order.STILL || interrupted == Unit.Order.STAND_GROUND) {
            target.setSavedOrder(Unit.Order.ATTACK_MOVE);
            target.setSavedAttackMove(oldX, oldY);
        } else if (interrupted == Unit.Order.PATROL
                || interrupted == Unit.Order.EXPLORE) {
            target.setSavedOrder(interrupted);
        }
    }


    boolean autoAttack(Unit unit) {
        if (!unit.type().canAttack() || unit.isDying() || !unit.isOnMap()) {
            return false;
        }
        // A coward does not go looking. IsAggressive gates AutoAttack in
        // COrder_Still::Execute, and without it a peasant walked off to fight.
        if (!unit.isAggressive()) {
            return false;
        }
        // On a cadence, not every cycle. Upstream sleeps half a second between
        // scans while idle; this implementation swept every unit on the map thirty times
        // a second, which is both wrong and the most expensive thing it did.
        if (unit.attackScanSleep() > 0) {
            unit.setAttackScanSleep(unit.attackScanSleep() - 1);
            return false;
        }
        unit.setAttackScanSleep(World.IDLE_SCAN_INTERVAL);

        int range = unit.type().reactRange(world.isPerson(unit.player()));
        if (range <= 0) {
            return false;
        }
        Unit target = world.targets.findHostile(unit, 0, range);
        if (target == null) {
            return false;
        }
        // Queued, not taken. A unit that spots an enemy while its own still
        // order is running does not begin the attack this cycle: upstream's
        // command leaves the order it interrupts in place and marked
        // finished, and puts the new one behind it, and the switch happens
        // when the action loop comes round again. Answering true here is
        // still right -- AutoAttack returning true is what stops
        // COrder_Still going on to MoveRandomly this cycle.
        unit.setPendingAttack(target, unit.order(), target.tileX(), target.tileY());
        return true;
    }


    /** Writes the paid refusal-two target handoff's bounded direct prefix. */
    private void planRepeatedPaidLongTailRetarget(Unit unit, Unit target) {
        // Repeated hard refusals have already bought the bounded direct
        // writer. Target scan keeps its first three line bytes when replacing
        // the quarry instead of immediately tracing a fresh wall around the
        // same formation. XHuman 12's independent refusal-two long tail
        // writes SW,SW,S before committing SW.
        PathFinder.Path direct = BattleNetPathFinder.clearLine(
                unit.tileX(), unit.tileY(), target.tileX(), target.tileY(),
                world.battleNetMovementStride(unit), world.map::contains);
        if (direct.result() == PathFinder.Result.FOUND
                && direct.length() > 3) {
            int[] headings = direct.headings();
            direct = new PathFinder.Path(PathFinder.Result.FOUND,
                    java.util.Arrays.copyOfRange(headings,
                            headings.length - 3, headings.length));
        }
        unit.setPath(direct);
        unit.setPathGoal(target.tileX(), target.tileY());
    }

    /**
     * Aims a unit at something it chose for itself.
     *
     * <p>{@code COrder_Attack::SetAutoTarget}. The thirty-cycle threshold is
     * the whole point: for the second it runs, nothing may re-aim this unit,
     * so a fight is a fight rather than a sequence of changed minds.
     */
    void setAutoTarget(Unit unit, Unit target) {
        setAutoTarget(unit, target, false);
    }


    void setAutoTarget(Unit unit, Unit target, boolean preserveCachedRoute) {
        // SetGoal changes the pathfinder input but does not erase its output.
        // If this target needs a chase, the next DoActionMove sees that the
        // input goal moved and re-plans then. If it is already in range, the
        // cached output survives behind the fight and is what the same
        // COrder_Attack resumes afterwards.
        Unit previous = unit.target();
        if (previous != null && previous != target) {
            armBattleNetRangedAttackCadence(unit);
        }
        if (previous != target) {
            unit.setBattleNetPaidRefusalRecoveryApproach(false);
            unit.setBattleNetPersonSplashHelpAttack(false);
            unit.setBattleNetPersonHelpRetargetHandoff(false);
            unit.setBattleNetPersonHitHelpAutoSelectHandoff(false);
            unit.setBattleNetSpatialHitHelpHandoff(false);
            // A boundary selection is itself the cadence's scan. Several
            // native seams call AutoSelectTarget inline rather than through
            // the idle wrapper, so they must still arm the next-scan sleep.
            // Otherwise CheckForTargetInRange immediately scans a second
            // time in the same callback and can undo the target and route it
            // just selected in a packed battle line.
            if (unit.attackScanSleep() == 0) {
                unit.setAttackScanSleep(World.ATTACK_SCAN_INTERVAL);
            }
        }
        if (previous != target && !preserveCachedRoute) {
            // The implementation folds COrder state into the unit. Callers that have
            // just executed upstream's PF_WAIT or restored AUTO_TARGETING
            // say explicitly that this is real unit-level output; other
            // surrogate leftovers must be discarded before they masquerade
            // as output the C++ unit would own.
            unit.clearPath();
        }
        if (previous != target
                && System.getenv("CHONKCRAFT_TRACE_ROUTEKEEP") != null) {
            System.err.printf("JROUTEKEEP cycle=%d unit=%d target=%d path=%d"
                            + " explicit=%d underattack=%d saved=%s home=%d,%d%n",
                    world.cycle, unit.id(), target.id(), unit.pathLength(),
                    preserveCachedRoute ? 1 : 0,
                    unit.underAttack(), unit.savedOrder(),
                    unit.attackMoveX(), unit.attackMoveY());
        }
        if (World.BNE_PEND_TRACE && previous != target) {
            CausalCallsite retargetCallsite = CausalCallsite.resolve();
            System.err.printf("JBNERETARGET cycle=%d unit=%d type=%s "
                            + "prev=%d->%d ttype=%s at=%d,%d keepPath=%d "
                            + "chasing=%d pathLen=%d caller=%s line=%d "
                            + "chain=%s%n",
                    world.cycle, unit.id(),
                    unit.type() == null ? "?" : unit.type().ident(),
                    previous == null ? -1 : previous.id(),
                    target == null ? -1 : target.id(),
                    target == null || target.type() == null
                            ? "?" : target.type().ident(),
                    target == null ? -1 : target.tileX(),
                    target == null ? -1 : target.tileY(),
                    preserveCachedRoute ? 1 : 0,
                    unit.chasing() ? 1 : 0, unit.pathLength(),
                    retargetCallsite.caller(), retargetCallsite.line(),
                    retargetCallsite.chain());
        }
        unit.setTarget(target);
        // COrder_Attack keeps goalPos beside its weak CUnitPtr. The pointer
        // can be cleared when the target is released, but that position is
        // still what CheckIfGoalValid and EndActionAttack use to decide
        // whether this fight was the attack-move's own destination.
        unit.setAttackGoal(target.tileX(), target.tileY());
        unit.setAttackRequiresVisibility(true);
        unit.setAutoTargeting(true);
        if (!world.simplifiedAutoTargeting && target.isAggressive()) {
            unit.setThreshold(World.TARGET_THRESHOLD);
        }
    }


    /** Starts the native ranged wall-clock once a chase owns a live swing. */
    private void armBattleNetRangedAttackCadence(Unit unit) {
        if (unit == null || unit.type() == null
                || !unit.type().firesMissile() || !unit.canMove()
                || unit.battleNetRangedAttackCadenceRemaining() != 0
                || world.battleNetSequence == null || world.idle == null) {
            return;
        }
        int attackStart = world.idle.battleNetSequenceStart(unit,
                BattleNetSequence.ATTACK_ANIMATION);
        int bodyWait = attackStart < 0 ? 0
                : world.battleNetSequence.attackBodyWaitSum(attackStart);
        if (bodyWait > 0) {
            // Native's cadence includes the two quiet constructor visits
            // before the body wait. Axethrower attackStart 887 therefore
            // carries 66 while its animation-body sum is 64.
            unit.setBattleNetRangedAttackCadenceRemaining(bodyWait + 2);
        }
    }


    /**
     * Holds position, striking whatever comes within reach.
     *
     * <p>One arm of {@code COrder_Still::Execute}
     * and it serves three kinds of
     * unit because upstream's test is {@code Action == StandGround ||
     * unit.Removed || unit.CanMove() == false}: the soldier told to hold, and
     * every armed building under plain {@code still}. The cycle's shape is
     * the order's own -- animate first, the swing when the last scan bought
     * one and the idle breath otherwise; decide nothing while the animation
     * holds; then {@code State = SUB_STILL_STANDBY}, so the acquisition dies
     * with the swing it bought and each fresh swing is a fresh scan's answer;
     * then sleep half a second between decisions.
     *
     * <p>The acquisition itself is {@link #autoAttackStand}, a sub-state and
     * not an order. This mattered on campaigns/orc-exp/levelx04o from its
     * second cycle: a zeppelin sat four tiles from a guard tower, and this
     * port's tower answered through {@code autoAttack} -- reaction range, a
     * cycle of pending-attack latency, and a whole attack order the trace
     * prints -- where upstream's tower asked its own attack range on the
     * standing cadence and was under {@code still} the entire fight.
     */
    void stepStandGround(Unit unit) {
        if (unit.fighting() && unit.target() != null) {
            world.idle.swingStanding(unit);
        } else if (unit.animation().unbreakable()) {
            world.finishSwing(unit);
        } else {
            world.idle.stepIdle(unit);
        }
        if (unit.animation().unbreakable()) {
            return;
        }
        // COrder_Still returns to SUB_STILL_STANDBY here but does not clear
        // its Goal. That apparently idle reference is observable by
        // TargetPriorityCalculate: another unit treats a tower whose retained
        // goal names it as "attacking me" until the standing order picks a
        // different goal. Keep the sub-state separately in fighting instead
        // of using a null target as the standby marker.
        unit.setFighting(false);
        if (unit.attackScanSleep() > 0) {
            unit.setAttackScanSleep(unit.attackScanSleep() - 1);
            return;
        }
        unit.setAttackScanSleep(World.IDLE_SCAN_INTERVAL);
        if (unit.isAggressive()) {
            world.idle.autoAttackStand(unit);
        }
    }


    /**
     * Shoots at a square rather than at anything in particular.
     *
     * <p>Only worth anything for a weapon whose shot spreads, which is why the
     * scripts give the button to exactly the two siege units: it is how you
     * hit something you cannot see, or a cluster you would rather not walk
     * into.
     */
    void stepAttackGround(Unit unit) {
        if (unit.battleNetOrderDelay() > 0) {
            int left = unit.battleNetOrderDelay() - 1;
            unit.setBattleNetOrderDelay(left);
            return;
        }
        int toX = unit.orderTargetX();
        int toY = unit.orderTargetY();
        if (!world.map.contains(toX, toY)) {
            unit.setOrder(Unit.Order.STILL);
            return;
        }
        MissileType missile = world.projectiles.missileFor(unit);
        boolean direct = missile == null || missile.isNone();
        if (direct && unit.animation().unbreakable() && world.isSwinging(unit)) {
            AnimationRunner.Step step = world.advance(unit);
            if (step.attacked()) {
                world.hitWall(unit, toX, toY);
            }
            return;
        }
        boolean wall = world.map.field(toX, toY).isWall();
        // Saves made before attack-ground Move provenance was explicit can
        // contain this corrupt state: a normal player Move inherited an old
        // attackGoal, was promoted to ATTACK_GROUND, and now points at empty
        // terrain. Let an already-committed swing drain above, then heal the
        // order. A real GiveOrder 17 walk carries battleNetAttackGroundMove;
        // newly issued direct Attack Ground clears playerCommandMove.
        if (direct && !wall && unit.battleNetPlayerCommandMove()
                && !unit.battleNetAttackGroundMove()) {
            unit.setBattleNetPlayerCommandMove(false);
            world.finishOrder(unit);
            return;
        }
        // Melee GiveOrder 17 on grass is a real order: the Orc 1 grunt
        // stays in ATTACK_GROUND beside 22,23. Java used to stand down on
        // the first step, so the commanded fixture never held the label.
        // There is still nothing to swing at, so in-range melee just faces
        // the square. Out of range it walks, which is how the Orc 1 peon
        // left 25,18 toward 30,18.
        int range = Math.max(1, unit.type().maxAttackRange());
        int distance = Math.max(Math.abs(unit.tileX() - toX), Math.abs(unit.tileY() - toY));
        if (distance > range) {
            // Leftover dest-arm that lands beside an unenterable click is
            // the arrival. Native 1594 leftover-lands 27,17 and Stills at
            // 43; walking on paid PF_WAIT 10 and stayed Attack Ground.
            if (direct && !unit.isMoving() && unit.pathLength() == 0
                    && unit.routeSpent()
                    && world.movement.leftoverLandedBesideForest(unit, toX, toY)) {
                world.finishOrder(unit);
                return;
            }
            world.movement.walkTowards(unit, toX, toY);
            return;
        }
        if (direct) {
            int dx = Integer.signum(toX - unit.tileX());
            int dy = Integer.signum(toY - unit.tileY());
            if (dx != 0 || dy != 0) {
                unit.setHeading(Direction.fromDelta(dx, dy));
            }
            // Native retains the Attack Ground order label on empty grass,
            // but there is nothing to strike: the unit faces the square and
            // presents its idle animation. Only a wall runs the melee attack
            // program and receives hitWall at the action marker.
            if (!wall) {
                world.idle.stepIdle(unit);
                return;
            }
            AnimationSet set = unit.type().animationSet();
            if (set == null) {
                return;
            }
            Animation attack = set.get(AnimationSet.State.ATTACK);
            if (attack == null) {
                return;
            }
            unit.animation().switchTo(attack);
            AnimationRunner.Step step = world.advance(unit);
            if (step.attacked()) {
                world.hitWall(unit, toX, toY);
            }
            return;
        }
        stepProjectileAttackGround(unit, missile, toX, toY);
    }

    /**
     * Runs native order 17 through the same attack program as unit combat.
     *
     * <p>The removed implementation fired whenever the <em>global</em> world
     * cycle was divisible by thirty.  A newly ordered catapult could therefore
     * wait anywhere from zero to twenty-nine cycles and then fire every thirty
     * cycles, while its ordinary attack uses its 200-cycle retail program.
     * Besides the 6.7x fire-rate error, that path bypassed turning, the visible
     * attack animation, Haste/Slow, projectile-constructor state, and the
     * command's unbreakable reload.</p>
     *
     * <p>The pinned BNE dispatch table maps order 17 to the shared 0x0040b010
     * handler and script.bin opcode ten remains the projectile boundary.  The
     * presentation animation is advanced in parallel, but its {@code attack}
     * instruction is deliberately not allowed to manufacture a second shot.</p>
     */
    private void stepProjectileAttackGround(Unit unit, MissileType missile,
            int toX, int toY) {
        AnimationSet set = unit.type().animationSet();
        Animation attack = set == null ? null : set.get(AnimationSet.State.ATTACK);
        if (attack == null) {
            // Minimal hand-built worlds used by engine tests may deliberately
            // omit presentation data. Keep their attack-ground functional on
            // a per-unit delay; production BNE units always take the retail
            // script/animation path below.
            if (unit.battleNetAnimationTimer() > 0) {
                unit.setBattleNetAnimationTimer(
                        unit.battleNetAnimationTimer() - 1);
                return;
            }
            world.projectiles.launchGround(unit, toX, toY, missile);
            unit.setBattleNetAnimationTimer(World.CYCLES_PER_SECOND);
            return;
        }

        // Human 07 catapult 1519 is still face 7 (NW) when native opens
        // Attack at fixture 9 (seq 503 timer 3). It snaps to face 2 at 12
        // and constructs at 13. Calling TurnToTarget first wrote R=72 and
        // held the cursor at -1 until leftover rotation fell below 30, so
        // the rock was born at 44. The Attack program owns the facing snap;
        // do not pre-aim or gate the cursor on Anim.Rotate.
        AnimationRunner.Step presentation = null;
        if (unit.battleNetSequenceOffset() < 0) {
            if (unit.animation().current() != attack) {
                openBattleNetGroundAttack(unit);
                unit.animation().switchTo(attack);
            }
            int attackBody = attack.labelIndex("go");
            boolean stillInTurnBranch = unit.animation().unbreakable()
                    && attackBody >= 0
                    && unit.animation().index() < attackBody;
            if (presentation == null && stillInTurnBranch) {
                world.advance(unit);
                return;
            }
            if (unit.battleNetSequenceOffset() < 0) {
                openBattleNetGroundAttack(unit);
            }
        }

        boolean fired = false;
        if (world.battleNetSequence != null
                && unit.battleNetSequenceOffset() >= 0) {
            BattleNetSequence.Tick tick = world.battleNetSequence.tick(
                    unit.battleNetSequenceOffset(),
                    unit.battleNetAnimationTimer());
            if (!tick.valid()) {
                unit.setBattleNetSequenceOffset(-1);
                return;
            }
            unit.setBattleNetSequenceOffset(tick.offset());
            unit.setBattleNetAnimationTimer(tick.timer());
            fired = tick.inlineActionMarker();
        }

        if (unit.animation().current() != attack) {
            unit.animation().switchTo(attack);
        }
        if (presentation == null) {
            presentation = world.advance(unit);
        }

        if (world.battleNetSequence == null) {
            // Hand-built/test worlds have no retail script.bin.  The attack
            // instruction is still a real cadence; unlike the deleted global
            // modulo it follows this unit's animation and spell speed.
            fired = presentation.attacked();
        }
        if (!fired) {
            return;
        }
        Missile shot = world.projectiles.launchGround(unit, toX, toY, missile);
        if (world.battleNetSequence != null) {
            world.prepareBattleNetProjectile(shot, true);
        }
    }

    /** Opens this unit type's retail Attack program with the native three-call delay. */
    private void openBattleNetGroundAttack(Unit unit) {
        if (world.battleNetSequence == null) {
            return;
        }
        int attackStart = world.idle.battleNetSequenceStart(unit,
                BattleNetSequence.ATTACK_ANIMATION);
        if (attackStart < 0) {
            return;
        }
        unit.setBattleNetSequenceOffset(attackStart);
        unit.setBattleNetAnimationTimer(3);
    }


    /**
     * Sends a unit at a place, fighting what it meets on the way.
     *
     * <p>{@code CommandAttack(unit, pos, nullptr)}, which reaches
     * {@code COrder::NewActionAttack(attacker, dest)}: the same attack order as one
     * given a unit, but with {@code attackMovePos} set and no goal, so it
     * begins in {@code AUTO_TARGETING} and picks its own targets as it
     * advances. {@code AiForce::Attack} gives this to every aggressive unit in
     * a force.
     *
     * <p>Upstream's one special case is kept: aimed at a wall the player can
     * see, this is a bombardment of that square rather than a march, because
     * there is no unit there to auto-target and the wall is the point. That is
     * the {@code Map.WallOnMap(dest)} branch of the same function.
     *
     * @return whether the order was accepted
     */
    boolean orderAttackMove(Unit unit, int toX, int toY) {
        if (unit == null || !unit.isAlive() || !unit.type().canAttack()
                || !world.map.contains(toX, toY)) {
            return false;
        }
        String traceCommand = System.getenv("CHONKCRAFT_TRACE_COMMAND");
        if (traceCommand != null && unit.id() == Integer.parseInt(traceCommand)) {
            String caller = StackWalker.getInstance().walk(frames -> frames
                    .skip(1)
                    .limit(5)
                    .map(frame -> frame.getClassName() + "." + frame.getMethodName()
                            + ":" + frame.getLineNumber())
                    .collect(java.util.stream.Collectors.joining("<-")));
            System.err.printf("JCOMMAND cycle=%d unit=%d attack-move=%d,%d from=%s"
                            + " unbreak=%d target=%d path=%d queued=%d caller=%s%n",
                    world.cycle, unit.id(), toX, toY, unit.order(),
                    unit.animation().unbreakable() ? 1 : 0,
                    unit.target() == null ? -1 : unit.target().id(),
                    unit.pathLength(), unit.queuedOrders().size(), caller);
        }
        if (world.map.field(toX, toY).isWall() && world.isVisibleTo(unit.player(), toX, toY)) {
            return world.orderAttackGround(unit, toX, toY);
        }
        if (unit.type().speed() <= 0) {
            // Nothing to march with. A tower given a place holds it instead,
            // which is what an attack order with an unreachable goal already
            // collapses to.
            return false;
        }
        // Every accepted CommandAttack is a new command. Upstream clears the
        // unit's SavedOrder after constructing its replacement, including
        // when GetNextOrder queued that replacement behind an unbreakable
        // animation. levelx12h exposes the distinction:
        // a force command is queued at cycle 609 and pops at 620; retaining
        // the return-to-post order from cycle 470 restores that obsolete post
        // when the next target dies at cycle 670.
        unit.setSavedOrder(null);
        if (unit.animation().unbreakable()) {
            // CommandAttack(..., EFlushMode::On) flushes by marking the
            // current order finished and appending the replacement. The pop
            // itself is inside HandleUnitAction's !Anim.Unbreakable gate
            // so a command arriving in a committed
            // step must not overwrite the current COrder_Attack or the
            // unit-level PathFinderOutput yet. levelx12h's axethrower 75 is
            // relaunched by its force during the step begun at cycle 487;
            // upstream keeps its tower chase and refuses the next heading at
            // 503, while immediate replacement erased the tail and laid a
            // fresh route on 503.
            unit.clearQueuedOrders();
            unit.setPendingAttack(null, null, -1, -1);
            unit.enqueueOrder(new Unit.QueuedOrder(Unit.QueuedOrderKind.ATTACK_MOVE,
                    toX, toY, null, null, null));
            unit.setQueuedReplacementPending(true);
            unit.rememberActionBeforeQueued(unit.order());
            return true;
        }
        world.projectiles.interruptPendingAttack(unit);
        world.construction.abandonPendingBuild(unit);
        // offeredTarget is COrder_Attack state upstream. A fresh position
        // order starts without the candidate offered to the order it
        // replaced; keeping it on the unit let levelx10h's newly restarted
        // knight prefer the distant ballista that had hit its old order over
        // the grunt standing beside it.
        unit.setOfferedTarget(null);
        // PathFinderInput/Output belong to CUnit, not to COrder_Attack.
        // Constructing or popping a replacement order leaves both intact;
        // UpdatePathFinderData invalidates them on the first DoActionMove
        // only when the new effective goal differs. This is observable when
        // FIRST_ENTRY reacquires the same quarry: levelx12h's axethrower 75
        // pops a force march at cycle 503, reacquires the guard tower, and
        // consumes the blocked heading still cached from its prior chase.
        //
        // A position march deliberately leaves pathGoal unset after planning
        // (marchTowards owns its re-plans), so remember that input explicitly
        // while replacing it.  The first Execute below can then make the same
        // UpdatePathFinderData comparison upstream does: keep the output if
        // an opening scan restores this input, or discard it if the new
        // position really is different.  human/level08h exposes the latter:
        // four force members pop a march from 70,7 to 70,58 with three old
        // headings left, and must not spend one of those headings first.
        boolean ownsPathOutput = unit.isMoving() || unit.pathLength() > 0
                || unit.routeSpent();
        if (ownsPathOutput && unit.moveRange() != 0) {
            // UpdatePathFinderData compares the whole input, not only the
            // destination. A new position attack starts with Range zero. If
            // the order it replaces had widened its range (or was chasing at
            // weapon range), even an identical goal invalidates the cached
            // output. level11o's archer is reissued 7,56 at cycle 1569 after
            // its old march reached that same square with Range one: upstream
            // replans and steps north-west, while retaining the range-one
            // empty output makes this implementation answer PF_WAIT in place.
            unit.clearPath();
            ownsPathOutput = false;
        }
        if (ownsPathOutput && unit.pathGoalX() < 0 && unit.pathGoalY() < 0
                && unit.attackMoveX() >= 0 && unit.attackMoveY() >= 0) {
            unit.setPathGoal(unit.attackMoveX(), unit.attackMoveY());
        }
        unit.setTarget(null);
        unit.setAttackMove(toX, toY);
        unit.setAttackGoal(toX, toY);
        unit.setOrderTarget(toX, toY);
        unit.setChasing(false);
        unit.setFighting(false);
        unit.setSwingAtAir(false);
        unit.setAttackMoveOpening(true);
        // Its own targets from the first cycle: AUTO_TARGETING is the state
        // this order starts in, and it is what lets attackBack renew the aim.
        unit.setAutoTargeting(true);
        unit.setAttackScanSleep(0);
        // {@code this->Range}, which the march widens for itself when the
        // square it was aimed at cannot be stood on. It belongs to the order,
        // so a fresh one starts at nought.
        unit.setMoveRange(0);
        unit.setOrder(Unit.Order.ATTACK_MOVE);
        unit.setBattleNetAttackWaitRefillResidual(false);
        unit.setBattleNetNavalPatrolAttackConstruction(false);
        unit.setBattleNetNavalPatrolAttackTimerOneReady(false);
        unit.setBattleNetLandPatrolAttackConstruction(false);
        unit.setBattleNetRetargetResidualParkRefill(false);
        return true;
    }


    /**
     * Advances a unit marching on a place.
     *
     * <p>The attack order's auto-targeting half, with the walk underneath it:
     * anything worth hitting inside the unit's reaction range is engaged where
     * it stands, and when there is nothing the unit carries on towards the
     * square it was aimed at. Reaching that square ends the order.
     *
     * <p>Fighting does not lose the destination. {@code attackMovePos} is
     * remembered across the whole engagement upstream, so a force that stops
     * to kill a picket resumes its advance instead of standing where the
     * picket fell; this keeps the order and its goal, and only the target
     * changes.
     */
    void stepAttackMove(Unit unit) {
        // Retail's commanded attack, attack-march and opportunistic march all
        // execute the same COrder_Attack animation program.  The Java order
        // enum separates ATTACK_MOVE so it can retain its destination, but
        // that separation must stop at dispatch: otherwise presentation can
        // swing while script.bin's opcode-ten cursor never advances, leaving
        // the pending melee blow permanently unresolved.  Human 6's adjacent
        // grunt and ballista are the player-visible witness.
        boolean navalPatrolConstruction =
                unit.battleNetNavalPatrolAttackConstruction();
        if (navalPatrolConstruction
                && unit.battleNetAnimationTimer() > 1) {
            // Keep executing the attack-move far enough to choose and cache
            // its native route, but do not tick the ordinary Attack program
            // or spend that route while construction owns this visit.
            unit.setBattleNetAnimationTimer(
                    unit.battleNetAnimationTimer() - 1);
        } else if (navalPatrolConstruction) {
            // Timer one was committed in the previous visit. This visit is
            // the native OP0 handoff that may spend the cached first heading.
            unit.setBattleNetNavalPatrolAttackTimerOneReady(true);
        }
        if (!navalPatrolConstruction && stepBattleNetAttackSequence(unit)) {
            return;
        }
        if (unit.battleNetPlayerCommandMove()
                || unit.battleNetStopAfterLeftover()) {
            // ReleaseOrders has already destroyed this attack-march's goal;
            // only its committed animation/pixels may outlive the player's
            // replacement. Do not run AutoSelectTarget again while those
            // pixels drain. The retained Garden of War replay has the broad
            // witness: native slot 1539 is redirected from (12,44) to
            // (12,49) during an adjacent fight. Java cleared the quarry on
            // the click, reacquired it three visits later, and fought forever
            // without giving the accepted Move ownership. Patrol uses this
            // same replacement discipline in World.stepPatrol.
            if (!unit.isMoving() && unit.offsetX() == 0 && unit.offsetY() == 0
                    && (unit.residualX() != 0 || unit.residualY() != 0)) {
                // Once Moving has fallen, a same-sign residual is animation
                // overshoot banked for a possible next leg, not an old pixel
                // debt. Replaying it with the old heading grows it forever
                // (the 1539 witness is west with residual -2). A replacement
                // has erased that leg, so take the same clean standstill
                // boundary used before a fresh movement prime.
                world.movement.resetDisplacement(unit);
            } else if (unit.isMoving()
                    || unit.residualX() != 0 || unit.residualY() != 0) {
                world.movement.walkPixels(unit);
            }
            world.movement.finishLeftoverReplacement(unit);
            return;
        }
        if (unit.destPathOpeningHold() && unit.battleNetOrderDelay() > 0) {
            // Dest-path GiveOrder 8 dest-arms two visits after install:
            // Human 1 1588 at fixture 8, Orc 1 1592 at fixture 12 after the
            // Still pop. Without this hold the march first-progressed on the
            // issue visit. Later chase delays are not this opening hold.
            unit.setBattleNetOrderDelay(unit.battleNetOrderDelay() - 1);
            if (unit.battleNetOrderDelay() == 0) {
                unit.setDestPathOpeningHold(false);
            }
            return;
        }
        String attackStateTrace = System.getenv("CHONKCRAFT_TRACE_ATTACKSTATE");
        if (attackStateTrace != null
                && unit.id() == Integer.parseInt(attackStateTrace)) {
            System.err.printf("JATTACKSTATE cycle=%d unit=%d at=%d,%d"
                            + " goalPos=%d,%d attackMovePos=%d,%d goal=%d chase=%b fight=%b"
                            + " open=%b wait=%d path=%d spent=%b moving=%b"
                            + " saved=%s savedPos=%d,%d range=%d savedRange=%d%n",
                    world.cycle, unit.id(), unit.tileX(), unit.tileY(),
                    unit.attackGoalX(), unit.attackGoalY(),
                    unit.attackMoveX(), unit.attackMoveY(),
                    unit.target() == null ? -1 : unit.target().id(),
                    unit.chasing(), unit.fighting(), unit.attackMoveOpening(),
                    unit.waitCycles(), unit.pathLength(), unit.routeSpent(),
                    unit.isMoving(), unit.savedOrder(), unit.savedAttackMoveX(),
                    unit.savedAttackMoveY(), unit.moveRange(), unit.savedMoveRange());
        }
        boolean swung = false;
        // Snapshot the order state, not a value EndActionAttack may raise
        // later in this same Execute. A restored order's AUTO_TARGETING arm
        // begins on its next Execute; it does not jump backwards into the
        // opening arm after a goal drop halfway through this one.
        boolean openingAtEntry = unit.attackMoveOpening();
        if (unit.animation().unbreakable() && world.isSwinging(unit)) {
            world.finishSwing(unit);
            if (unit.animation().unbreakable()) {
                return;
            }
            // AttackTarget animates before it checks the goal. The instruction
            // that clears Unbreakable therefore shares its cycle with the
            // scan and state transition below; it does not end Execute. Keep
            // the fact that AnimateActionAttack already ran, though, because
            // this cycle may turn or take MOVE_TO_TARGET but may not start a
            // second swing or spend the first chase step.
            swung = true;
        }
        // A step in the air finishes before anything is decided, exactly as
        // the chase holds it: upstream's MoveToTarget runs DoActionMove and
        // returns on Anim.Unbreakable before it reaches any target check
        // Without this gate a marcher reaching
        // its quarry mid-step dropped the walk and swung from between two
        // squares -- the first blow of every fight landed most of a second
        // early, and the damage roll comes off the shared stream.
        // Once the retail Move program itself is about to yield at OP0, its
        // action boundary outranks a stale unbreakable bit in the parallel
        // presentation Move. The latter can be parked on its final move
        // instruction after residual pixels have reached zero. Calling that
        // a live step forever prevents the route consult and target scan that
        // OP0 explicitly permits.
        boolean battleNetMoveDecisionDue = battleNetChaseMoveDecisionDue(unit);
        if (world.movement.isStepping(unit) && !battleNetMoveDecisionDue) {
            stepMoveTowardsTarget(unit);
            // Dest leftover last heading that lands this visit Stills now.
            // Orc 1 dest-attack offsets hit nought at 92 and native is Still
            // that visit; returning here forced the next-visit dest-arrival
            // and settled at 93. A last step that is still Moving after the
            // drain keeps the next-visit dest-arrival below.
            if (finishDestAttackWhenLeftoverLands(unit)) {
                return;
            }
            // The dead-goal check is not sleep-gated and runs on any beat
            // that leaves the animation breakable -- CheckForTargetInRange
            // opens with CheckIfGoalValid, and
            // MoveToTarget reaches it the moment the step's unbreakable lets
            // go, mid-drain included (759). demo03's grunt drops its dead
            // peasant on the last drain beat of the step, at 60, and the
            // restore boundary consumes the rest of the cycle -- the
            // restored march's first ask is 61's. A step whose script stays
            // unbreakable to the boundary starves the check across whole
            // steps, which is the walk-first face of the corpse-chase.
            // Every beat that entered here was a drain beat -- DoActionMove's
            // else arm, err always at least nought -- so every breakable exit
            // checks, the last step's drain-end included. The route-remaining
            // clause that used to guard this skipped exactly that beat, and
            // levelx04h's footman crossed into its corpse-march at 88 where
            // upstream's had dropped at 87 and scanned fresh at 88.
            if (!unit.animation().unbreakable()) {
                Unit quarry = unit.target();
                if (System.getenv("CHONKCRAFT_TRACE_DROP") != null) {
                    System.err.printf("JDROPDBG cycle=%d unit=%d quarry=%d valid=%b%n",
                            world.cycle, unit.id(), quarry == null ? -1 : quarry.id(),
                            quarry != null && world.targets.validAttackTarget(unit, quarry));
                }
                if (quarry != null && !world.targets.validAttackTarget(unit, quarry)) {
                    int deadGoalX = quarry.tileX();
                    int deadGoalY = quarry.tileY();
                    boolean restoreDeferred = unit.underAttack() > 0
                            && unit.autoTargeting();
                    boolean separateOrderRestores = unit.savedOrder() != null
                            && !restoreDeferred;
                    boolean finishesAtOwnDestination = !separateOrderRestores
                            && unit.autoTargeting()
                            && deadGoalX == unit.attackMoveX()
                            && deadGoalY == unit.attackMoveY();
                    unit.setTarget(null);
                    World.resetRestoredAttackScan(unit);
                    // RestoreOrder replaces MOVE_TO_TARGET with a fresh
                    // AUTO_TARGETING attack-move. Keeping the chase state
                    // spends the restored order's first execute on a turn
                    // instead of letting it acquire and attack immediately.
                    unit.setChasing(false);
                    unit.setFighting(false);
                    unit.setSwingAtAir(false);
                    unit.clearPath();
                    if (finishesAtOwnDestination) {
                        unit.setAutoTargeting(false);
                        world.finishOrder(unit);
                    } else if (!separateOrderRestores && unit.autoTargeting()) {
                        // This is the same EndActionAttack arm as the
                        // standing target-drop below. It runs here on
                        // DoActionMove's breakable drain beat, and it restores
                        // the position march's exact Range zero before the
                        // next Execute. human/level08h's axethrower drops its
                        // dying peasant on 1694; carrying the chase's weapon
                        // range into 1695 finds a route and steps immediately,
                        // while upstream's exact destination is unreachable
                        // and raises the five-cycle widening wait.
                        unit.setAttackGoal(unit.attackMoveX(), unit.attackMoveY());
                        unit.setMoveRange(0);
                    }
                } else {
                    // The same breakable beat weighs the scan for a live
                    // quarry: CheckForTargetInRange falls through
                    // CheckIfGoalValid into AutoSelectTarget.
                    // This scan is reached only after DoActionMove has
                    // drained to a breakable instruction.  Its remaining
                    // PathFinderOutput is therefore genuine CUnit state,
                    // not a surrogate order leftover.  SetGoal does not
                    // erase that output when the scan finds an in-range
                    // target; the fight parks it until this attack-move
                    // resumes.  levelx12h's grunt keeps the crowd-shaped
                    // tail laid at cycle 809 through its long adjacent
                    // fight, then consumes it again at cycle 1375.
                    Unit retargeted = world.idle.marchScan(unit, quarry, true);
                    if (retargeted != null && retargeted != quarry) {
                        // MoveToTarget consumes the result of that scan in
                        // the same execute. A new goal already in range turns
                        // into ATTACK_TARGET; one outside the gun's range
                        // becomes MOVE_TO_TARGET. Merely storing the target
                        // left this order in AUTO_TARGETING, so the following
                        // cycle scanned a second time before it walked.
                        if (world.targets.inAttackRange(unit, retargeted)) {
                            unit.setChasing(false);
                            unit.setFighting(true);
                            unit.setSwingAtAir(false);
                            world.turnToTarget(unit, retargeted, 0, 0);
                        } else {
                            unit.setChasing(true);
                            unit.setFighting(false);
                            unit.setSwingAtAir(false);
                        }
                    }
                }
            }
            return;
        }
        Unit target = unit.target();
        boolean resumedSameAttackThisExecute = false;
        // A chase still walking and a swing not yet wound both defer the
        // drop: the walk's committed beats starve the check outright, the
        // arrival beat turns and winds a whole swing at the corpse before
        // AttackTarget's own check can run -- AnimateActionAttack comes
        // first -- and only the swing's
        // breakable tail reads the goal and lets go. The three faces the
        // corpse-chase was recorded under are this one mechanism.
        // ATTACK_TARGET with an already-cleared weak goal is invalid too.
        // CUnitPtr becoming null does not turn the order back into a
        // goal-less attack-move: CheckIfGoalValid explicitly rejects a null
        // goal while the ATTACK_TARGET bit is set, then EndActionAttack uses
        // the separately retained goalPos.
        boolean targetGone = (target != null && !world.targets.validAttackTarget(unit, target))
                || (target == null && unit.fighting());
        AnimationSet swingSet = unit.type().animationSet();
        boolean canSwing = swingSet != null
                && swingSet.get(AnimationSet.State.ATTACK) != null;
        // The stepDrained flag is consumed unread for now: a check-first
        // fusion keyed on it fired at every post-drain boundary, including
        // the ones upstream starves, because every boundary top is
        // breakable here -- the dispatcher wraps the move script before the
        // order runs -- and the beat that actually discriminates is the
        // drain-end's own breakability, which this implementation reads one beat
        // late. The root is the animation-advance order inside the walk,
        // covered by focused tests.
        unit.setStepDrained(false);
        boolean deferDrop = ((unit.chasing()
                && (unit.isMoving() || unit.pathLength() > 0 || unit.routeSpent()
                        || targetGone))
                || (unit.fighting() && canSwing && !swung));
        if (!deferDrop && targetGone) {
            // EndActionAttack only resumes this COrder_Attack when the weak
            // goal position differs from attackMovePos. If both positions
            // are the same, there is no march to restore and the order is
            // marked Finished instead. A separate SavedOrder wins unless
            // UnderAttack deliberately defers RestoreOrder.
            boolean restoreDeferred = unit.underAttack() > 0 && unit.autoTargeting();
            boolean separateOrderRestores =
                    unit.savedOrder() != null && !restoreDeferred;
            int deadGoalX = target == null ? unit.attackGoalX() : target.tileX();
            int deadGoalY = target == null ? unit.attackGoalY() : target.tileY();
            boolean finishesAtOwnDestination = !separateOrderRestores
                    && unit.autoTargeting()
                    && deadGoalX == unit.attackMoveX()
                    && deadGoalY == unit.attackMoveY();
            if (System.getenv("CHONKCRAFT_TRACE_DROP") != null) {
                System.err.printf("JDROPSTATE cycle=%d unit=%d saved=%s sleep=%d"
                                + " underattack=%d goal=%d,%d home=%d,%d%n",
                        world.cycle, unit.id(), unit.savedOrder(), unit.attackScanSleep(),
                        unit.underAttack(), deadGoalX, deadGoalY,
                        unit.attackMoveX(), unit.attackMoveY());
            }
            // The counter starts over with the quarry gone, because upstream
            // changes orders: EndActionAttack restores the saved march
            // Sleep lives on the order, and the
            // restored order's first Execute scans at once -- levelx09h's
            // knight drops its dead quarry at 75 and has the skeleton picked
            // at 76. This implementation ran one counter per unit, and carrying the
            // fight's remainder across the drop made the knight miss that
            // scan by two cycles and march home instead.
            unit.setTarget(null);
            if (separateOrderRestores && swung
                    && (unit.isMoving() || unit.pathLength() > 0
                            || unit.routeSpent())
                    && unit.pathGoalX() < 0 && unit.pathGoalY() < 0
                    && unit.attackMoveX() >= 0 && unit.attackMoveY() >= 0) {
                // A position march deliberately leaves pathGoal unset, but
                // its PathFinderInput still remembers the position it was
                // calculated for upstream.  RestoreOrder queues the saved
                // COrder behind the swing that is just releasing, so that
                // input and its output both cross the pop.  If the restored
                // opening scan immediately finds an in-range target, no
                // DoActionMove weighs the input yet; the old output can sit
                // under a whole fight.  Preserve its identity here so the
                // eventual return to the restored march can make
                // UpdatePathFinderData's real comparison. levelx10h's
                // knight carries a west heading for 80,89 through the fight
                // begun at cycle 248, then must discard it when the restored
                // 84,91 march finally moves at cycle 323.
                //
                // The swung gate is the queued-pop seam. A weak goal found
                // invalid before AttackTarget animates is handled in this
                // port's fused execute and may consume the restored output
                // immediately; its established PF_WAIT behaviour remains
                // separate.
                unit.setPathGoal(unit.attackMoveX(), unit.attackMoveY());
            }
            World.resetRestoredAttackScan(unit);
            unit.setFighting(false);
            unit.setSwingAtAir(false);
            if (!separateOrderRestores && unit.autoTargeting()
                    && (deadGoalX != unit.attackMoveX()
                            || deadGoalY != unit.attackMoveY())) {
                resumedSameAttackThisExecute = true;
                // EndActionAttack resumes this same COrder_Attack by copying
                // attackMovePos back into goalPos and restoring the position
                // form's exact ranges: Range=0, MinRange=0
                // SetAutoTarget had replaced
                // Range with the weapon range, and an earlier unreachable
                // march may have widened it further. Carrying either value
                // into the resumed position march skips its required
                // range-zero refusal and five-cycle widening wait.
                int resumedRange = unit.moveRange();
                unit.setMoveRange(0);
                if (resumedRange != 0) {
                    // PathFinderOutput is unit-owned, but NextPathElement
                    // first runs UpdatePathFinderData against this order's
                    // input. Changing Range makes that input stale even when
                    // goalPos itself is the same, so its cached headings are
                    // discarded before the exact-range search. At
                    // levelx12h cycle 689 the axethrower still owns the tail
                    // of a range-one march; consuming it steps south, while
                    // upstream rejects the occupied exact destination and
                    // waits five cycles before widening again.
                    unit.clearPath();
                }
            }
            // RestoreOrder swaps COrder objects, but PathFinderOutput belongs
            // to CUnit and is not erased by that swap. Its cached route,
            // including the phantom empty-route beat, therefore survives both
            // exits. levelx12h's knight drops its quarry under attack at 112
            // and serves PF_WAIT on 113; grunt 79 restores a separate saved
            // order at 345 and owes the same PF_WAIT on 346.
            target = null;
            if (finishesAtOwnDestination) {
                unit.setAutoTargeting(false);
                world.finishOrder(unit);
                return;
            }
            if (swung) {
                return;
            }
        }
        // AutoSelectTarget's beat is the walk's breakable exit, not the top
        // of the decide: MoveToTarget runs DoActionMove before anything --
        // an empty-routed chaser re-plans and commits inside it, starved --
        // and CheckForTargetInRange only runs after, on err >= 0
        // So a chasing unit with a quarry never
        // takes the standing call, whatever its route looks like from here:
        // level13h's wise-man ran one call ahead of upstream's counter
        // because the boundary at 48 read as routeless up here while the
        // marching arm below re-planned and committed, and that extra call
        // pushed its sixth-beat scan to 60, trading the axethrower for the
        // ogre. A goal-less march that has widened Range is likewise already
        // in MOVE_TO_TARGET even while its five-cycle wait leaves no output
        // in hand: levelx12h's axethrower wakes at 136, commits its new route,
        // and does not spend Sleep until the drain end at 151. Fighters
        // between swings and AUTO_TARGETING goal-less marchers decide here.
        // And the swing about to start owns its beat the same way:
        // AttackTarget animates first, and the entry beat's animate begins
        // the unbreakable, so its guard returns before AutoSelectTarget --
        // the call is starved until the swing's breakable tail. The wise-man
        // arriving at 71 has no call at 72 upstream; this implementation's top call
        // there was the last extra beat on the counter.
        // A type with no attack animation has no swing to pend: its animate
        // leaves nothing unbreakable, so the call runs every beat, exactly
        // as upstream's guard would read it.
        boolean walkOwned = (!openingAtEntry
                && ((!unit.fighting()
                && (unit.isMoving() || unit.pathLength() > 0
                        || unit.routeSpent()
                        // A goal-less order that has left FIRST_ENTRY is in
                        // MOVE_TO_TARGET even when its cached route is empty
                        // and its exact-position Range is still zero. Its
                        // next operation is DoActionMove, which may plan and
                        // commit a step; AutoSelectTarget is reached only if
                        // that call returns breakable. levelx12h's grunt 120
                        // wakes with an empty route on cycle 976, and upstream
                        // commits north-east before its Sleep counter can be
                        // touched. Treating only widened Range as walk-owned
                        // spent the counter there and acquired a target on
                        // the following drain boundary at 991.
                        || (target == null && !resumedSameAttackThisExecute)))
                || (unit.chasing() && target != null)
                || (unit.fighting() && canSwing && !swung)));
        boolean scanned = false;
        boolean acquiredThisExecute = false;
        if (!walkOwned) {
            Unit beforeScan = target;
            // The scan being entered spends FIRST_ENTRY. If it restores yet
            // another order, resetRestoredAttackScan raises the bit again.
            if (openingAtEntry) {
                unit.setAttackMoveOpening(false);
            }
            target = world.idle.marchScan(unit, target, openingAtEntry);
            scanned = true;
            acquiredThisExecute = beforeScan == null && target != null;
            if (beforeScan != null && target == null) {
                // AutoSelectTarget returning false with a standing goal makes
                // CheckForTargetInRange call EndActionAttack(RESTORE_ONLY).
                // A separate saved order is queued behind the current one
                // and cannot execute until HandleUnitAction's next-cycle pop.
                return;
            }
        }
        if (target != null) {
            int range = Math.max(1, unit.type().maxAttackRange());
            int distance = world.attackDistance(unit, target);
            if (acquiredThisExecute && openingAtEntry
                    && world.targets.inAttackRange(unit, target)) {
                // FIRST_ENTRY checks range before MoveToTarget can consult
                // the unit's cached output. A restored attack order can own
                // old output and still enter ATTACK_TARGET immediately.
                //
                // The transition is not just a state bit: upstream calls
                // TurnToTarget before AttackTarget (:
                // 944-949). That leaves Anim.Rotate for the first attack
                // animation beat to read. Marking the unit fighting first
                // skipped the shared turn below; levelx02h's catapult then
                // walked straight past its R branch and fired on cycle 2
                // instead of spending thirty cycles turning and firing on
                // 33.
                world.turnToTarget(unit, target, 0, 0);
                unit.setChasing(false);
                unit.setFighting(true);
                unit.setSwingAtAir(false);
            }
            if (swung) {
                // This is AttackTarget after AnimateActionAttack returned
                // breakable. Its tail only updates the state and aim. Even
                // when AutoSelectTarget found something new out of range,
                // MoveToTarget and its first DoActionMove belong to the next
                // Execute.
                boolean inRange = world.targets.inAttackRange(unit, target);
                unit.setChasing(!inRange);
                unit.setFighting(inRange);
                unit.setSwingAtAir(false);
                world.turnToTarget(unit, target, 0, 0);
                return;
            }
            if (distance < unit.type().minAttackRange()) {
                moveToBetterPos(unit, target);
                return;
            }
            // A spent route still owes upstream's end-of-route wait:
            // NextPathElement's else-arm decrements before it reads, so the
            // call after a route's last element answers PF_WAIT and
            // DoActionMove sleeps ten cycles before anyone re-plans --
            // demo03's grunt #0 pays it at cycle 18 and swings only after.
            // PathFinderOutput belongs to the unit, not to the attack
            // order's state. ATTACK_TARGET deliberately leaves a cached
            // MOVE_TO_TARGET route behind it; that route is ignored for the
            // whole fight and becomes relevant again only when
            // EndActionAttack returns the order to AUTO_TARGETING.
            boolean marching = !unit.fighting()
                    && (unit.isMoving() || unit.pathLength() > 0
                            || unit.routeSpent());
            if (!marching && unit.fighting() && distance > range) {
                // ATTACK_TARGET animates before it measures the range
                // That ordering matters at the
                // loop boundary: the preceding breakable tail has already
                // left CurrAnim at instruction zero, so AnimateActionAttack
                // begins the next unbreakable swing before it can notice
                // that a later-numbered quarry walked away meanwhile.
                // levelx12h's grunt 97 does exactly that on cycle 289. This
                // port used CurrAnim == Attack as a reason not to animate,
                // conceded the fight, and stepped south instead.
                //
                // Animate even if the script is already current. switchTo
                // deliberately preserves its index, and advance therefore
                // does the same one cycle of work as AnimateActionAttack.
                // A script that stays breakable falls through to the range
                // transition; an unbreakable beginning owns the whole cycle.
                AnimationSet set = unit.type().animationSet();
                Animation attack = set == null ? null
                        : set.get(AnimationSet.State.ATTACK);
                if (attack != null) {
                    unit.setSwingAtAir(true);
                    if (System.getenv("CHONKCRAFT_TRACE_FIGHT") != null) {
                        System.err.printf("JFIGHT cycle=%d unit=%d airswing tgt=%d%n",
                                world.cycle, unit.id(), target.id());
                    }
                    world.strike(unit, target);
                    if (unit.animation().unbreakable()) {
                        return;
                    }
                }
                unit.setSwingAtAir(false);
                unit.setFighting(false);
                if (System.getenv("CHONKCRAFT_TRACE_FIGHT") != null) {
                    System.err.printf("JFIGHT cycle=%d unit=%d concede tgt=%d%n",
                            world.cycle, unit.id(), target.id());
                }
            } else if (distance <= range && !marching && !unit.chasing()) {
                // Arrived from a walk: turn, and the swing belongs to the
                // next cycle -- MoveToTarget's PF_REACHED arm is TurnToTarget
                // and a state change. A
                // *fresh* acquisition already in reach is different:
                // FIRST_ENTRY turns and calls {@code AttackTarget} in the
                // same execute, so the
                // swing starts on the spot. And an acquisition out of reach
                // falls through this arm entirely -- {@code State |=
                // MOVE_TO_TARGET} and [[fallthrough]] into the walk -- which
                // is why blocking the first step here held level13h's
                // force-ordered knight and destroyer a cycle behind
                // upstream's from the map's second cycle.
                if (unit.chasing()) {
                    // This is the terminal DoActionMove query after the
                    // emptied route's ten-cycle wait. PF_REACHED clears
                    // IX/IY before MoveToTarget turns and enters
                    // ATTACK_TARGET (
                    // The game ). Ships can add a Still
                    // wiggle while serving that wait: levelx11o's destroyer
                    // 114 wakes with 32,1 at 141, upstream clears it in the
                    // query, and its cycle-142 broadside starts at 480,1472.
                    world.movement.resetDisplacement(unit);
                    // The turn beat is also the state change: upstream sets
                    // ATTACK_TARGET beside TurnToTarget (:
                    // 767-776), and the fighting flag is this implementation's state
                    // bit. Without it the next beat read as a standing
                    // decide and took the scan call AttackTarget's animate
                    // starves -- level13h's wise-man swapped quarry on the
                    // very beat its first swing should have started.
                    unit.setChasing(false);
                    unit.setFighting(true);
                    world.turnToTarget(unit, target, 0, 0);
                    return;
                }
                if (!unit.fighting()) {
                    // FIRST_ENTRY's turn, before the swing it starts in the
                    // same execute: the pending
                    // rotation it leaves is what the siege scripts' R-gate
                    // reads on the swing's first beat. strike itself turns
                    // only on breakable exits now, as AttackTarget does.
                    world.turnToTarget(unit, target, 0, 0);
                    if (System.getenv("CHONKCRAFT_TRACE_FIGHT") != null) {
                        System.err.printf("JFIGHT cycle=%d unit=%d enter inreach tgt=%d%n",
                                world.cycle, unit.id(), target.id());
                    }
                }
                unit.setFighting(true);
                unit.setSwingAtAir(false);
                world.strike(unit, target);
                return;
            }
            if (marching || unit.chasing()) {
                // A route in hand owns the cycle, in range or not. Upstream's
                // MoveToTarget with an unchanged goal has exactly two ways
                // out of the walk -- PF_REACHED, and a re-aim that found a
                // *different* goal in reach -- so a grunt whose planned ring
                // tile is mobbed stands at arm's length from its victim,
                // takes PF_WAIT, and breathes out a ten-cycle wait instead
                // of swinging. demo03's
                // grunt #0 does exactly that from cycle 19: this implementation struck
                // from the blocked queue and every damage roll after it was
                // a different number.
                //
                // And the walk is a chase in upstream's books -- state
                // MOVE_TO_TARGET -- which is what buys the PF_REACHED turn
                // on arrival: the cycle that ends the walk turns to the
                // target and the swing belongs to the next one. It also buys
                // the chase's mid-walk re-plan: UpdatePathFinderData raises
                // isRecalculatePathNeeded the moment the goal's tile moves,
                // so a marcher whose quarry flees re-aims from where it
                // stands -- demo03's ogre re-plans at the peasant's new
                // square on cycle 42, mid-route, and this implementation's walked its
                // stale course a step longer.
                boolean goalMoved = !unit.isMoving()
                        && (unit.pathGoalX() != target.tileX()
                                || unit.pathGoalY() != target.tileY());
                // A chase whose route ran dry re-plans at the goal it
                // remembers, dead or not: levelx04h's footman 115 spends its
                // route against a blocked square, waits out two ten-cycle
                // refusals across its quarry's death at 84, and at 88
                // upstream's NewPath aims at the corpse's own ring -- goal
                // 74,60, ongoal the dead grunt -- and commits south with the
                // check starved behind the step. This implementation used to drop the
                // dead goal at 88 and scan, and its footman walked north into
                // the square a living target's chase had just left.
                //
                // Chasing itself owns this arm after an empty-route wait,
                // even when the footprint distance says the gun is just in
                // range. MoveToTarget calls DoActionMove before
                // InAttackRange; if the quarry moved during the wait, that
                // call re-plans and may commit one more step. levelx11o's
                // gryphon wakes at 12,34 on cycle 385 with the destroyer now
                // at 8,38: the direct distance is four, but the range-aware
                // path asks for one south-west step and owns the cycle.
                boolean routeDry = !unit.isMoving() && unit.pathLength() == 0
                        && !unit.routeSpent();
                PathFinder.Result replanned = null;
                if (goalMoved || routeDry) {
                    unit.clearPath();
                    replanned = world.planTowards(unit, target);
                }
                if (replanned == PathFinder.Result.UNREACHABLE) {
                    world.endUnreachableAttackChase(unit, target);
                    return;
                }
                if (replanned == PathFinder.Result.REACHED) {
                    // MoveToTarget's PF_REACHED arm precedes any goal-validity
                    // check. A quarry that died during the empty-route wait
                    // can therefore still buy the arrival turn and one whole
                    // committed swing; AttackTarget notices the corpse only
                    // after animating. levelx11o's gryphon reaches its dying
                    // dragon on cycle 813 and starts that swing on 814.
                    world.movement.resetDisplacement(unit);
                    unit.setChasing(false);
                    unit.setFighting(true);
                    unit.setSwingAtAir(false);
                    world.turnToTarget(unit, target, 0, 0);
                    return;
                }
                unit.setChasing(true);
                boolean underWay = stepMoveTowardsTarget(unit);
                // A BNE Move OP0 that finds no usable route has already
                // returned PF_WAIT from the native action boundary. The
                // parallel presentation Move can still be parked on its last
                // move instruction with Unbreakable set; that cosmetic tail
                // is not another native gate. Treating it as one starves the
                // CheckForTargetInRange call below forever because each wake
                // lays another empty route and immediately sleeps again.
                // Human expansion 3's grunt 313 is the player-visible
                // witness: settled at 44,119, it looped Move OP0 two-cycle
                // refusals without ever reconsidering footman 338 or the
                // nearby cannon tower.
                boolean retainedTailReachabilityRefusal = underWay
                        && unit.chasing() && !unit.isMoving()
                        && unit.pathLength() == 1
                        && unit.battleNetAttackWrapDestArmPending()
                        && unit.battleNetChaseEmptyRouteReplan()
                        && onBattleNetChaseMoveBody(unit);
                boolean battleNetEmptyRouteRefusal = (underWay
                                && world.battleNetSequence != null
                                && unit.chasing() && !unit.isMoving()
                                && unit.pathLength() == 0 && !unit.routeSpent()
                                && unit.waitCycles() > 0
                                && onBattleNetChaseMoveBody(unit))
                        || retainedTailReachabilityRefusal;
                if (underWay || unit.animation().unbreakable()) {
                    if (underWay && (!unit.animation().unbreakable()
                            || battleNetEmptyRouteRefusal)) {
                        // A refusal answers PF_WAIT, err >= 0, and the check
                        // runs on that breakable beat: a dead goal is let go
                        // and a live one weighed -- these are upstream's
                        // double-beats, the drain-end call and the refusal
                        // call landing on adjacent cycles.
                        if (target != null && !world.targets.validAttackTarget(unit, target)) {
                            boolean restoresSeparateOrder = unit.savedOrder() != null
                                    && !(unit.underAttack() > 0 && unit.autoTargeting());
                            boolean finishesAtOwnDestination = !restoresSeparateOrder
                                    && unit.autoTargeting()
                                    && target.tileX() == unit.attackMoveX()
                                    && target.tileY() == unit.attackMoveY();
                            unit.setTarget(null);
                            World.resetRestoredAttackScan(unit);
                            unit.setChasing(false);
                            unit.setFighting(false);
                            unit.setSwingAtAir(false);
                            unit.clearPath();
                            // DoActionMove may have raised its ten-cycle
                            // PF_WAIT before CheckForTargetInRange notices
                            // that the quarry died. EndActionAttack then
                            // restores the saved march; HandleUnitAction pops
                            // to it on the next cycle and clears unit.Wait
                            // levelx11o's destroyer
                            // meets exactly that ordering on cycle 93.
                            // With no SavedOrder, EndActionAttack resumes this
                            // same weak COrder_Attack in place. No action is
                            // popped, so the PF_WAIT survives: levelx12h's
                            // grunt 93 drops its dead archer at cycle 418 and
                            // serves the ten through 428 before marching home.
                            if (restoresSeparateOrder) {
                                unit.setWaitCycles(0);
                            } else if (finishesAtOwnDestination) {
                                // EndActionAttack still runs after DoActionMove
                                // returns PF_WAIT. When the dead weak goal is
                                // the position this very attack-move was
                                // marching to, there is no order to resume:
                                // the native COrder_Attack is marked Finished
                                // even though the refusal just raised Wait.
                                // levelx12h's knight reaches this exact edge
                                // at cycle 1353; leaving the order live strands
                                // it behind that spent route indefinitely.
                                unit.setAutoTargeting(false);
                                world.finishOrder(unit);
                            }
                        } else if (!scanned) {
                            // One call per execute: a fused check-first
                            // beat already spent this cycle's call.
                            Unit retargeted = world.idle.marchScan(unit, target, true);
                            // MoveToTarget's err >= 0 arm notices when
                            // CheckForTargetInRange chose a different goal.
                            // If that goal is already reachable by the gun it
                            // turns and enters ATTACK_TARGET now, before any
                            // PF_WAIT DoActionMove just raised is served

                            if (retargeted != null && retargeted != target
                                    && world.targets.inAttackRange(unit, retargeted)) {
                                unit.setChasing(false);
                                unit.setFighting(true);
                                unit.setSwingAtAir(false);
                                world.turnToTarget(unit, retargeted, 0, 0);
                            }
                        }
                    }
                    if (World.TRACE_MOVING != null
                            && unit.id() == World.TRACE_MOVING_ID) {
                        System.err.printf("JCHASERETURN cycle=%d unit=%d "
                                        + "empty-refusal=%d seq=%d/%d path=%d "
                                        + "collision=%d target=%d sleep=%d%n",
                                world.cycle, unit.id(),
                                battleNetEmptyRouteRefusal ? 1 : 0,
                                unit.battleNetSequenceOffset(),
                                unit.battleNetAnimationTimer(),
                                unit.pathLength(),
                                unit.battleNetCollisionCounter(),
                                unit.target() == null ? -1
                                        : unit.target().id(),
                                unit.attackScanSleep());
                    }
                    return;
                }
                // The walk ended this cycle. MoveToTarget's PF_REACHED arm
                // turns on the arrival cycle itself, not the one after --
                // demo03's destroyer turns at 50 and fires its broadside at
                // 51, and spending the arrival on nothing pushed its whole
                // shell one cycle behind upstream's splash. But only a live
                // goal is turned to: the REACHED arm's guard rejects a dying
                // one and MoveToTarget falls through to EndActionAttack --
                // levelx04h's footman 104 arrives beside its dead quarry at
                // 87 and upstream's restored order is scanning fresh at 88,
                // where this implementation wound a whole swing at the corpse.
                if (target != null && !world.targets.validAttackTarget(unit, target)) {
                    unit.setTarget(null);
                    World.resetRestoredAttackScan(unit);
                    unit.setFighting(false);
                    unit.setSwingAtAir(false);
                    unit.clearPath();
                    unit.setChasing(false);
                    return;
                }
                // A spent route owes its ten before the arrival may turn:
                // NextPathElement's else-arm answers PF_WAIT on the emptied
                // route even with the goal already in range, and only the
                // re-ask after the wait answers REACHED and buys the turn.
                // levelx12h's grunt 226 walks its last step to the tower's
                // ring at 50, waits to 60, re-asks at 61 and fights from 66
                // (NEXTELEM, upstream).
                if (unit.routeSpent()) {
                    return;
                }
                int distanceToTarget = world.attackDistance(unit, target);
                if (distanceToTarget <= range
                        && distanceToTarget >= unit.type().minAttackRange()) {
                    unit.setChasing(false);
                    world.turnToTarget(unit, target, 0, 0);
                    unit.setFighting(true);
                    if (System.getenv("CHONKCRAFT_TRACE_FIGHT") != null) {
                        System.err.printf("JFIGHT cycle=%d unit=%d enter walkend tgt=%d%n",
                                world.cycle, unit.id(), target.id());
                    }
                }
                return;
            }
            // Close on it, but never further than the reaction range it was
            // noticed at: a march is not a chase across the map.
            if (acquiredThisExecute && openingAtEntry) {
                // FIRST_ENTRY sets MOVE_TO_TARGET before falling through to
                // this first DoActionMove. Delay the implementation's state bit until
                // this arm so it does not mistake that same execute for a
                // pre-existing chase and skip MoveToTarget's second scan on
                // a breakable PF_WAIT.
                unit.setChasing(true);
                unit.setFighting(false);
                unit.setSwingAtAir(false);
            }
            boolean stale = unit.pathGoalX() != target.tileX()
                    || unit.pathGoalY() != target.tileY();
            if (!unit.isMoving() && (unit.pathLength() == 0 || stale)) {
                unit.clearPath();
                if (world.movement.moveTowards(unit, target)) {
                    boolean underWay = stepMoveTowardsTarget(unit);
                    // FIRST_ENTRY does not stop after AutoSelectTarget finds
                    // an out-of-range quarry: it falls through to
                    // MoveToTarget in the same Execute. If DoActionMove then
                    // answers PF_WAIT rather than beginning an unbreakable
                    // step, MoveToTarget reaches CheckForTargetInRange and
                    // calls AutoSelectTarget a second time. The first call
                    // has just reset Sleep to six, so this one is the
                    // otherwise invisible decrement to five. levelx04h's
                    // packed grunt line does this on cycle 2; carrying six
                    // made its first real re-scan land at 109 instead of
                    // upstream's later beat, and it changed targets.
                    if (acquiredThisExecute && underWay
                            && !unit.animation().unbreakable()) {
                        world.idle.marchScan(unit, unit.target());
                    }
                    return;
                }
                unit.setTarget(null);
            } else {
                stepMoveTowardsTarget(unit);
                return;
            }
        }
        if (swung) {
            return;
        }

        // A step already begun keeps the order alive, arrival or no. This is
        // the same MOVE_TO_TARGET rule the chase follows: upstream's
        // {@code MoveToTarget} runs {@code DoActionMove} and then returns on
        // {@code Anim.Unbreakable}, so it never reaches the code that would
        // finish the order until the animation has let go of the unit.
        //
        // On demo02 that is twenty-seven cycles. The juggernaught is back at
        // its post at 4,18 on cycle 28 and upstream keeps it under an attack
        // order until 55, which is how long an ogre juggernaught's move
        // animation runs; this implementation called the march over the moment the tile
        // matched.
        int toX = unit.attackMoveX();
        int toY = unit.attackMoveY();
        if (!world.map.contains(toX, toY)
                || (unit.tileX() == toX && unit.tileY() == toY)) {
            // Arrived -- but a step already begun keeps the order alive. This
            // is the MOVE_TO_TARGET rule the chase follows: upstream's
            // {@code MoveToTarget} runs {@code DoActionMove} and then returns
            // on {@code Anim.Unbreakable}, so it never reaches the code that
            // would finish the order until the animation has let go.
            //
            // On demo02 that is twenty-seven cycles. The juggernaught is back
            // at its post at 4,18 on cycle 28 and upstream keeps it under an
            // attack order until 55, which is how long an ogre juggernaught's
            // move animation runs; this implementation called the march over the moment
            // the tile matched.
            //
            // The walk's hold is read before the cycle's animation runs, as
            // {@code DoActionMove} reads {@code unit.Moving} at its top: a
            // unit whose last pixels drain away during this very cycle is
            // still "on the way" for the whole of it, and decides nothing
            // until the next. Printed from the real binary: the axethrower's
            // offsets reach nought during cycle 92 and its walk answers
            // {@code err=1} there all the same, deciding -- and ending -- on
            // 93.
            // DoActionMove's gate is the Moving flag plus the move-script
            // boundary, not IX/IY. A diagonal ship step can clear Moving and
            // reach instruction zero with one pixel still banked on the
            // stationary axis. At that point upstream is allowed to consult
            // the spent route and turn PF_WAIT into PF_REACHED; treating the
            // pixel as another live step starts a whole fresh move-animation
            // pass before the order can finish (level08o, destroyer at
            // 34,18, cycles 1122-1123).
            boolean walkHeld = unit.walkHolding() || !world.movement.atMoveBoundary(unit);
            if (walkHeld) {
                // DoActionMove's half: the animation advances and the owed
                // pixels are drawn down. This used to advance the animation
                // alone, which left a unit that arrived under a turning
                // animation -- demo03's catapult, thirty cycles of {@code
                // if-var R >= 60 turn} before its walking frames -- drawn a
                // whole tile from its square for the rest of the run, and
                // reading as mid-step for ever.
                world.movement.walkPixels(unit);
                return;
            }
            // The walk lets go, and what the spent route answers is
            // upstream's PF_WAIT -- converted to an arrival only when the
            // unit is within its own attack range of the square, minimum
            // included: distance
            // nought is in range of everything but a siege engine, whose gun
            // cannot reach its own feet. A unit the conversion refuses stays
            // to serve the route's ten-cycle pause, and the re-plan after
            // that answers REACHED, which ends the order whatever the range
            // says. Printed from the real binary: the juggernaught, minimum
            // nought, goes still on 55 with its wait still pending; the
            // catapult, minimum two, on 141 with every stage served.
            int arrivedDistance = unit.distanceTo(toX, toY);
            boolean inRangeOfSquare = arrivedDistance >= unit.type().minAttackRange()
                    && arrivedDistance <= Math.max(1, unit.type().maxAttackRange());
            if (!inRangeOfSquare && world.movement.spendTheEmptyRoute(unit)) {
                return;
            }
            // With no spent route this is NextPathElement's direct
            // PF_REACHED, and DoActionMove clears IX/IY before returning it.
            // A ship's Still animation can have left a one-pixel bob there:
            // levelx08o's destroyer is repeatedly commanded to its own tile,
            // and the cycle-310 command wipes +1,+1 upstream. Keeping it lets
            // the next negative bob cancel to zero instead of leaving -1,-1,
            // shortening the later diagonal drain by a cycle. Do not clear a
            // spent-route arrival: that is PF_WAIT converted to PF_REACHED by
            // MoveToTarget after DoActionMove, whose PF_WAIT arm does not
            // reset displacement.
            if (!unit.routeSpent()) {
                world.movement.resetDisplacement(unit);
            }
            unit.clearPath();
            unit.setTarget(null);
            unit.setAutoTargeting(false);
            // The march ends the way the attack ends: EndActionAttack runs
            // {@code unit.RestoreOrder()}, so the patrol or exploration this
            // acquisition interrupted picks up where it left off rather than
            // dying into a stand. Deviation, deliberately narrow: upstream
            // also restores the attack-move-at-its-own-post a still unit's
            // scan saved, and this implementation does not do that here -- its chained
            // saves put the post where each re-acquisition happened rather
            // than where upstream's does, and restoring them walked demo02's
            // juggernaught at a post upstream never kept, cycle 55. The
            // bounded difference: a still-scanned unit whose march completes
            // stands where it finished instead of walking home, until
            // EndActionAttack's whole conditional is carried across.
            unit.rememberActionBeforeQueued(unit.order());
            if (unit.savedOrder() == Unit.Order.PATROL
                    || unit.savedOrder() == Unit.Order.EXPLORE) {
                world.finishAttackOrder(unit);
            } else {
                unit.takeSavedOrder();
                world.finishOrder(unit);
            }
            return;
        }
        // A route left over from a chase goes no further than the thing that
        // has just died. A march normally plans with no path goal at all --
        // see marchTowards -- so a goal still set is the retained
        // PathFinderInput of an earlier order. UpdatePathFinderData only
        // invalidates its output when the effective goal changed. Equal is
        // important: a fresh order that reacquires the same quarry or repeats
        // the same march consumes the already-buffered heading upstream.
        if (unit.pathGoalX() >= 0 || unit.pathGoalY() >= 0) {
            if (unit.pathGoalX() != toX || unit.pathGoalY() != toY) {
                unit.clearPath();
            }
            unit.setPathGoal(-1, -1);
        }
        int waitBeforeMarch = unit.waitCycles();
        marchTowards(unit, toX, toY);
        // FIRST_ENTRY/AUTO_TARGETING checks once before it enters the move
        // arm, then MoveToTarget checks again after a breakable PF_WAIT.
        // The first call may merely expire Sleep; the second is still allowed
        // to acquire a goal and, when it is already in range, change the state
        // to ATTACK_TARGET before the ten-cycle wait is served. This is the
        // retained attack order at human-exp/levelx12h cycle 113: its first
        // call takes Sleep 1 to zero, the empty-route ask raises Wait 10, and
        // the second call finds the ogre beside it.
        if (unit.order() == Unit.Order.ATTACK_MOVE
                && unit.target() == null
                && waitBeforeMarch == 0
                && unit.waitCycles() == World.MAX_PATH_WAIT
                && !unit.animation().unbreakable()) {
            Unit retargeted = world.idle.marchScan(unit, null, true);
            if (retargeted != null) {
                boolean inRange = world.targets.inAttackRange(unit, retargeted);
                unit.setChasing(!inRange);
                unit.setFighting(inRange);
                unit.setSwingAtAir(false);
                if (inRange) {
                    world.turnToTarget(unit, retargeted, 0, 0);
                }
            }
        }
    }


    /**
     * Dest leftover last heading that just landed on the dest square.
     *
     * <p>The stepping arm used to return after draining those last pixels,
     * so dest-arrival only ran the next visit. Dest-attack Stills on the
     * land visit itself.
     */
    private boolean finishDestAttackWhenLeftoverLands(Unit unit) {
        int toX = unit.attackMoveX();
        int toY = unit.attackMoveY();
        if (unit.target() != null
                || !world.map.contains(toX, toY)
                || unit.tileX() != toX || unit.tileY() != toY
                || unit.offsetX() != 0 || unit.offsetY() != 0
                || unit.walkHolding()
                || unit.animation().unbreakable()) {
            return false;
        }
        // The drain-end shortcut must preserve the same waiting-arrival rule
        // as the ordinary MoveToTarget boundary below.  A position attack
        // whose gun can reach the destination converts the spent route's
        // PF_WAIT to PF_REACHED and finishes on this visit.  Siege engines
        // cannot attack their own square: distance zero is below MinRange,
        // so native serves the route's ten-cycle wait before asking again.
        // Finishing every landed march here made a ballista snap straight
        // from its last walk frame to Still and erased that native control
        // seam entirely.
        int arrivedDistance = unit.distanceTo(toX, toY);
        boolean inRangeOfSquare = arrivedDistance >= unit.type().minAttackRange()
                && arrivedDistance <= Math.max(1, unit.type().maxAttackRange());
        if (!inRangeOfSquare && world.movement.spendTheEmptyRoute(unit)) {
            return true;
        }
        if (!unit.routeSpent()) {
            world.movement.resetDisplacement(unit);
        }
        unit.clearPath();
        unit.setAutoTargeting(false);
        unit.rememberActionBeforeQueued(unit.order());
        if (unit.savedOrder() == Unit.Order.PATROL
                || unit.savedOrder() == Unit.Order.EXPLORE) {
            world.finishAttackOrder(unit);
        } else {
            unit.takeSavedOrder();
            world.finishOrder(unit);
        }
        return true;
    }


    /**
     * One cycle of a march towards a square, under the attack order.
     *
     * <p>Not {@link #walkTowards}. That one aims beside its destination when
     * the square itself cannot be entered, which is right for a worker walking
     * at a tree and wrong here: a march is a plain move, and upstream's
     * {@code COrder_Attack::MoveToTarget} is explicit about what it does when
     * the walk answers PF_UNREACHABLE and the order has no unit goal --
     * "When attack-moving we have to allow a bigger range (PF)",
     * {@code this->Range++} and {@code unit.Wait = 5}
     * It widens the goal and tries
     * again; it never goes looking at the neighbours.
     *
     * <p>The two are not the same walk. On {@code maps/demo/demo03} a grunt at
     * 11,1 is marching on 13,3, which a friendly axethrower is standing on, so
     * both engines' searches answer unreachable -- and upstream stands there
     * while this implementation went round the eight squares beside it, found six it
     * could reach, walked to the nearest and drew from the shared stream doing
     * it. That map's first divergence was cycle 61.
     */
    void marchTowards(Unit unit, int toX, int toY) {
        if (!unit.isMoving() && !world.movement.isStepping(unit) && unit.pathLength() == 0) {
            // The emptied route costs its ten-cycle pause here too, before
            // the march may re-plan. Upstream's decrement-at-the-top means
            // the call after a route's last element was spent answers PF_WAIT
            // from the count alone, whatever now stands on the ground.
            // demo03's knight at 6,2 is the measurement: three refused wakes
            // spend its three-step route, the archer blocking it walks away
            // by cycle 120, and upstream still waits out cycle 121's answer
            // and steps on 132 -- where this implementation re-planned at 121 and took
            // the freed square eleven cycles early.
            if (world.movement.spendTheEmptyRoute(unit)) {
                // And in the same action, MoveToTarget's waiting arrival:
                // within attack range of the square, minimum included, the
                // order ends now rather than serving the pause. Printed from
                // the real binary for demo03's grunt 11: cycle 83 sets
                // wait=10 and finishes the order together, cycle 84's pop
                // hands the restored march a cleared wait --
                // wipes unit.Wait when it pops to a waiting order -- and the
                // fresh route steps the same cycle. A unit with nothing
                // saved keeps the wait, which is the juggernaught's still
                // wait=10 at cycle 56.
                int spentDistance = unit.distanceTo(toX, toY);
                if (unit.target() == null
                        && spentDistance >= unit.type().minAttackRange()
                        && spentDistance <= Math.max(1, unit.type().maxAttackRange())) {
                    unit.clearPath();
                    unit.setAutoTargeting(false);
                    if (unit.savedOrder() != null) {
                        unit.setWaitCycles(0);
                        unit.setMoveRange(0);
                        world.finishAttackOrder(unit);
                    } else {
                        world.finishOrder(unit);
                    }
                }
                return;
            }
            PathFinder.Path path = world.pathFinder.find(unit.tileX(), unit.tileY(),
                    new PathFinder.Goal(toX, toY, 1, 1, 0, unit.moveRange()), world.moverFor(unit));
            switch (path.result()) {
                case REACHED -> {
                    // Close enough for the range the order has widened to,
                    // which is how a march aimed at ground nobody can stand on
                    // ends rather than going on for ever.
                    world.movement.resetDisplacement(unit);
                    unit.clearPath();
                    unit.setTarget(null);
                    unit.setAutoTargeting(false);
                    if (unit.savedOrder() != null) {
                        // {@code EndActionAttack} puts back what was stored
                        // behind the order, and what it puts back is a *fresh*
                        // one: a new {@code COrder_Attack} has {@code Range}
                        // nought. Carried over, the widening that just ended
                        // this march would end the restored one on its first
                        // cycle too.
                        unit.setMoveRange(0);
                        world.finishAttackOrder(unit);
                    } else {
                        world.finishOrder(unit);
                    }
                    return;
                }
                case UNREACHABLE -> {
                    world.aiCanNotMove(unit, toX, toY, 1, 1);
                    world.movement.resetDisplacement(unit);
                    unit.setMoveRange(unit.moveRange() + 1);
                    unit.setWaitCycles(World.MARCH_WIDEN_WAIT);
                    return;
                }
                default -> {
                    unit.setPath(path);
                    // No path goal, as {@link #walkTowards} sets none: this
                    // order re-plans for itself, above, every time the route
                    // runs out. Leaving one set is worse than useless here --
                    // {@link #stepAttackMove} reads a goal that is still set
                    // as the mark of a route left over from a chase and throws
                    // the route away on the next cycle. On
                    // {@code maps/demo/demo03} that cost an orc destroyer the
                    // ten-step route it had just been given: upstream's takes
                    // the ninth of those steps on cycle 82 and this implementation's had
                    // re-planned, widened its range and gone the other way.
                    unit.setPathGoal(-1, -1);
                }
            }
        }
        // stepMove reads the order it is given, so the march borrows the move
        // order for the step and gives it back, exactly as a patrol does.
        Unit.Order saved = unit.order();
        unit.setOrder(Unit.Order.MOVE);
        int waiting = unit.waitCycles();
        world.movement.stepMove(unit);
        if (unit.order() != Unit.Order.DYING) {
            unit.setOrder(saved);
        }
        if (unit.order() != Unit.Order.ATTACK_MOVE) {
            return;
        }
        // Waiting, and already within reach of where it was sent, is arriving.
        // {@code MoveToTarget} converts the one into the other before it looks
        // at anything else:
        //
        //   // Look if we have reached the target.
        //   if (err == 0 && !this->HasGoal()) {
        //       // Check if we're in range when attacking a location and we are waiting
        //       if (InAttackRange(unit, this->goalPos)) {
        //           err = PF_REACHED;
        //       }
        //   }
        //
        // {@code err == 0} is PF_WAIT, which is what a refused step answers,
        // and PF_REACHED with no unit goal and no attack-ground falls through
        // everything below it into {@code EndActionAttack}. A wait, not an
        // ordinary walking cycle: a marcher does not finish the moment it
        // comes within range of its destination, only when a step it wanted
        // was refused while it was there.
        //
        // On {@code maps/demo/demo03} that is a human destroyer at 5,11 sent
        // to 4,12 with an attack range of ten: upstream's last step is refused
        // on cycle 82 and the march is over, and this implementation's went on marching
        // for the rest of the run.
        // Minimum range included: {@code InAttackRange} is
        // {@code minRange <= distance && distance <= range}, so a catapult
        // blocked one square from where it was sent is not there yet --
        // its gun cannot reach its own feet -- and keeps marching, where
        // reading the maximum alone would have ended it.
        int marchDistance = unit.distanceTo(toX, toY);
        if (unit.waitCycles() > waiting && unit.target() == null
                && marchDistance >= unit.type().minAttackRange()
                && marchDistance <= Math.max(1, unit.type().maxAttackRange())) {
            // PF_WAIT is converted to PF_REACHED by MoveToTarget, after
            // NextPathElement has already left its output in the unit. Ending
            // COrder_Attack does not clear that unit-owned PathFinderOutput.
            // If this was a freshly planned refused heading, Length is still
            // one and a later command to the same square consumes it. On
            // level11o archer 54 is relaunched at cycle 1179 and upstream
            // reads exactly the heading refused at 1149; clearing it here
            // replans the now-stationary blocker as UNREACHABLE and buys an
            // AiMoveUnitInTheWay random draw no upstream game makes.
            // EndActionAttack tests this before touching SavedOrder.  A live
            // UnderAttack counter and an auto-targeting position order take
            // the short-circuit arm: the just-arrived order is marked
            // Finished, its PF_WAIT stays on the unit, and the save remains
            // parked behind it.  level11o's sapper reaches the square it was
            // sent to on cycle 553 with both conditions true; restoring its
            // saved post immediately erases the one-cycle finished-order
            // seam in which upstream's Still order notices the same enemy.
            boolean restoreDeferred = unit.underAttack() > 0
                    && unit.autoTargeting();
            unit.setAutoTargeting(false);
            if (unit.savedOrder() != null && !restoreDeferred) {
                // EndActionAttack restores the saved COrder by putting it
                // behind this now-finished order. HandleUnitAction pops the
                // finished head on the next cycle and wipes the PF_WAIT that
                // DoActionMove just raised. A shoved attack-move therefore
                // resumes its cloned march instead of falling through to
                // Still: levelx12h's axethrower does this at cycle 1444.
                // RestoreOrder keeps the unit-owned path output, but the
                // next UpdatePathFinderData invalidates it when the restored
                // order's effective input differs. Position marches do not
                // store pathGoal, so compare their order-owned goal and range
                // explicitly: this is the widened live range one versus the
                // saved exact range zero in levelx12h.
                if (unit.savedOrder() == Unit.Order.ATTACK_MOVE
                        && (unit.savedAttackMoveX() != unit.attackMoveX()
                                || unit.savedAttackMoveY() != unit.attackMoveY()
                                || unit.savedMoveRange() != unit.moveRange())) {
                    unit.clearPath();
                }
                unit.setWaitCycles(0);
                world.finishAttackOrder(unit);
            } else {
                world.finishOrder(unit);
            }
        }
    }


    /**
     * Advances BNE's terminal armed-tower idle action (raw action 14).
     *
     * <p>The tower is not a ChonkCraft stand-ground order. It first runs the
     * ordinary native Still sequence. At that sequence's action marker a
     * target promotes it to action 14 and selects animation four. Every later
     * shot is made only when that binary animation reaches its own action
     * marker. In the retail XHuman 12 opening, for example, guard tower 1429
     * records sequence 3679 with timer {@code 3,2,1}; its arrow is created at
     * the following marker. Running the Java stand-ground swing instead fires
     * during the two hidden startup calls and hits the grunt four cycles too
     * early.</p>
     */
    void stepBattleNetTower(Unit tower) {
        int offset = tower.battleNetSequenceOffset();
        if (offset < 0) {
            offset = world.idle.battleNetSequenceStart(tower,
                    tower.battleNetTowerActive()
                            ? BattleNetSequence.ATTACK_ANIMATION
                            : BattleNetSequence.STILL_ANIMATION);
            if (offset < 0) {
                // No binary Still/attack program for this type: still scan
                // with the native scoring so a tower with a live target is
                // not permanently silent while mobile units use the same
                // findBattleNetHostile path.
                stepBattleNetTowerFallback(tower);
                return;
            }
            tower.setBattleNetSequenceOffset(offset);
            tower.setBattleNetAnimationTimer(1);
        }

        BattleNetSequence.Tick tick = world.battleNetSequence.tick(offset,
                tower.battleNetAnimationTimer());
        if (!tick.valid()) {
            stepBattleNetTowerFallback(tower);
            return;
        }
        tower.setBattleNetSequenceOffset(tick.offset());
        tower.setBattleNetAnimationTimer(tick.timer());
        if (!tick.actionMarker()) {
            return;
        }

        int range = Math.max(1, tower.type().maxAttackRange());
        if (System.getenv("CHONKCRAFT_TRACE_BNE_TARGET") != null) {
            System.err.printf("JBNETOWER cycle=%d tower=%d type=%s range=%d "
                            + "active=%d offset=%d%n",
                    world.cycle, tower.id(), tower.type().ident(), range,
                    tower.battleNetTowerActive() ? 1 : 0, offset);
        }
        // Prefer the current attack goal when scores are equal so a tower
        // does not retarget onto a newly higher spatial unit between volleys
        // (XHuman 10 second cannon preferred unit 100 / 1500 as primary).
        Unit incumbent = tower.target();
        Unit target = world.targets.findBattleNetHostile(tower, range, incumbent);
        if (!tower.battleNetTowerActive()) {
            if (target == null) {
                return;
            }
            int attack = world.idle.battleNetSequenceStart(tower,
                    BattleNetSequence.ATTACK_ANIMATION);
            if (attack < 0) {
                return;
            }
            tower.setBattleNetTowerActive(true);
            tower.setBattleNetSequenceOffset(attack);
            // FUN_00452ef0 selects the new action with the standard native
            // three-call animation delay. The first attack opcode itself is
            // not executed on the promotion call. Landing is delayed one
            // projectile pass by Missile action-6 (not by lengthening this
            // timer), so XHuman 2's tower arrow damages on fixture cycle 11.
            tower.setBattleNetAnimationTimer(3);
            return;
        }

        if (target != null) {
            hit(tower, target);
            return;
        }

        // FUN_0040afb0 returns finished when its fresh scan finds nothing.
        // Action 14 names itself as NextAction, so selection restarts the
        // same attack sequence rather than dropping the tower back to action
        // two.
        tower.setBattleNetSequenceOffset(world.idle.battleNetSequenceStart(tower,
                BattleNetSequence.ATTACK_ANIMATION));
        tower.setBattleNetAnimationTimer(3);
    }


    /**
     * Scans and fires when the binary tower program is missing or invalid.
     *
     * <p>Human 5's orc guard towers report ATTACK from fixture cycle 1 on
     * the native side while this implementation left them permanently Still and never
     * loosed an arrow. The ChonkCraft stand-ground fallback only used
     * {@code findHostile}, which scores differently and often returns
     * nothing for those openings. Prefer the same native target score the
     * scripted tower path uses.
     */
    void stepBattleNetTowerFallback(Unit tower) {
        if (tower.attackScanSleep() > 0) {
            tower.setAttackScanSleep(tower.attackScanSleep() - 1);
            return;
        }
        tower.setAttackScanSleep(World.ATTACK_SCAN_INTERVAL);
        int range = Math.max(1, tower.type().maxAttackRange());
        Unit target = world.targets.findBattleNetHostile(tower, range, null);
        if (target != null) {
            hit(tower, target);
        }
    }


    /**
     * Advances the native animation carried by a mobile attack action.
     *
     * <p>BNE action 12/16 continues through animation four until its opcode
     * zero gives the order a chance to fire or chase. The outcome matters:
     * a successful move switches to animation three and an in-range fighter
     * continues after the marker, while a blocked out-of-range retry falls
     * through Still and reaches {@code FUN_0040ad30}. XHuman 4's axethrowers
     * 1506 and 1516 are the compact witness for that final arm: both draw
     * between recorded cycles two and three, and omitting the calls gives
     * three later critters the wrong wander choices at cycle four.</p>
     */
    /**
     * Advances the native animation carried by a mobile attack action.
     *
     * @return {@code true} when the caller must hold the order for this cycle
     *         without consuming a further cached chase heading
     */
    /**
     * Whether the unit's sequence cursor sits on the retail Move body used
     * to pace chase steps after the first heading.
     */
    boolean onBattleNetChaseMoveBody(Unit unit) {
        int moveStart = world.idle.battleNetSequenceStart(unit,
                BattleNetSequence.MOVE_ANIMATION);
        if (moveStart < 0) {
            return false;
        }
        int offset = unit.battleNetSequenceOffset();
        if (offset < moveStart) {
            return false;
        }
        int attackStart = world.idle.battleNetSequenceStart(unit,
                BattleNetSequence.ATTACK_ANIMATION);
        // Move programs live between the Move start and the Attack start
        // (skeleton 1130..1186, attack 1187).
        return attackStart < 0 || offset < attackStart;
    }


    /**
     * Whether a ranged chase residual has already paid its Attack OP0.
     *
     * <p>Short tails and routes with two paid steps were the first proved
     * forms. A nearly-full segment is the other native boundary: after the
     * first heading of a 20-heading cache lands in range, retail discards the
     * remaining nineteen and opens at the instruction after OP0. XHuman 12
     * axethrower 1523 is the sealed witness (route index one, Attack 888/1 at
     * fixture 124, axe born at 134). Treating it as an ordinary one-step
     * approach charged the unrelated 63-cycle resume hold and suppressed the
     * shot, which then reassigned every asynchronous combat roll behind it.
     */
    static boolean battleNetRangedResidualRouteQualifies(
            Unit unit, int pathStepsAtSettle) {
        return unit != null && (unit.pathLength() <= 3
                || unit.pathLength() >= BattleNetPathFinder.MAX_PATH - 1
                || pathStepsAtSettle >= 2);
    }


    /** Whether the next native Move tick yields to a settled chase order. */
    private boolean battleNetChaseMoveDecisionDue(Unit unit) {
        if (world.battleNetSequence == null || unit == null
                || !unit.chasing() || unit.isMoving() || unit.walkHolding()
                || unit.offsetX() != 0 || unit.offsetY() != 0
                || !onBattleNetChaseMoveBody(unit)) {
            return false;
        }
        BattleNetSequence.Tick next = world.battleNetSequence.tick(
                unit.battleNetSequenceOffset(),
                unit.battleNetAnimationTimer());
        return next.valid() && next.actionMarker();
    }


    /** Whether a settled moving-quarry route will reach Move OP0 this visit. */
    private boolean battleNetSettledSpentMovingQuarryDecisionDue(Unit unit) {
        if (world.battleNetSequence == null || unit == null
                || !unit.chasing() || unit.isMoving()
                || !unit.walkHolding()
                || unit.offsetX() != 0 || unit.offsetY() != 0
                || unit.pathLength() != 0 || !unit.routeSpent()
                || !unit.battleNetMovingQuarryResidual()
                || !onBattleNetChaseMoveBody(unit)) {
            return false;
        }
        BattleNetSequence.Tick next = world.battleNetSequence.tick(
                unit.battleNetSequenceOffset(),
                unit.battleNetAnimationTimer());
        return next.valid() && next.actionMarker();
    }


    /** Whether a moving quarry has visually closed to a one-tile melee reach. */
    private static boolean battleNetMovingQuarryPixelInMeleeRange(
            Unit attacker, Unit quarry) {
        if (attacker == null || quarry == null || !quarry.isMoving()) {
            return false;
        }
        int attackerX = attacker.pixelX() + attacker.residualX();
        int attackerY = attacker.pixelY() + attacker.residualY();
        int quarryX = quarry.pixelX() + quarry.residualX();
        int quarryY = quarry.pixelY() + quarry.residualY();
        return Math.max(Math.abs(attackerX - quarryX),
                Math.abs(attackerY - quarryY)) <= Unit.TILE_PIXELS;
    }


    /**
     * Arms the Move-sequence body past the opening opcode-zero after a chase
     * step commits (native offset after the step is the body, timer 1).
     */
    void armBattleNetChaseMoveBody(Unit unit) {
        int moveStart = world.idle.battleNetSequenceStart(unit,
                BattleNetSequence.MOVE_ANIMATION);
        if (moveStart < 0) {
            return;
        }
        // Opening is frame + OP0. Tick once with timer 1 to land on the body
        // the way a just-taken step does (skeleton 1130/1 → 1133/1 action).
        BattleNetSequence.Tick open = world.battleNetSequence.tick(moveStart, 1);
        if (!open.valid()) {
            return;
        }
        unit.setBattleNetSequenceOffset(open.offset());
        unit.setBattleNetAnimationTimer(open.timer());
        unit.setBattleNetChaseStepReady(false);
    }


    /**
     * Advances the retail Move sequence during a chase and marks when
     * opcode zero allows the next path heading.
     */
    void tickBattleNetChaseMoveSequence(Unit unit) {
        // Sticky ready: OP0 may fire one visit before Moving clears; keep
        // the permit until the heading is taken.
        int offset = unit.battleNetSequenceOffset();
        if (offset < 0) {
            return;
        }
        BattleNetSequence.Tick tick = world.battleNetSequence.tick(offset,
                unit.battleNetAnimationTimer());
        if (!tick.valid()) {
            unit.setBattleNetSequenceOffset(-1);
            return;
        }
        unit.setBattleNetSequenceOffset(tick.offset());
        unit.setBattleNetAnimationTimer(tick.timer());
        if (tick.actionMarker()) {
            unit.setBattleNetChaseStepReady(true);
        }
    }


    /**
     * Parks a completed ranged chase whose retained quarry has entered Die.
     *
     * <p>A chase body's last pixels drain after its Move-sequence tick. If
     * the retained CUnitPtr enters Die while those pixels are owed, retail
     * still opens cold Attack construction; it does not fall through the
     * adjacent script bytes as though Move were already an attack body.
     * XHuman 10 axethrower 1478 is the sealed witness: fixture 56 is
     * Move@882/1 with two pixels owed, fixture 57 is Attack@887/3, and
     * fixture 60 enters the 63-count OP0 hold. Missing this handoff created a
     * phantom axe at fixture 67 and reassigned the following damage roll.
     *
     * <p>The movement caller catches a quarry already in Die when the pixels
     * drain. The attack caller catches the equally authentic ordering where
     * the quarry enters Die later in that cycle and the settled chaser sees
     * it on its next visit.</p>
     */
    boolean openBattleNetRetainedDyingRangedConstruction(Unit unit) {
        Unit target = unit.target();
        if (world.battleNetSequence == null
                || !unit.chasing()
                || unit.isMoving() || !unit.stepDrained()
                || !world.battleNetMoveAnimation(unit)
                || unit.order() != Unit.Order.ATTACK
                || target == null || !target.isDying()
                || unit.type() == null || !unit.type().firesMissile()
                || !world.targets.inAttackRange(unit, target)) {
            return false;
        }
        if (unit.type().minAttackRange() > 1) {
            // The positive dead zone makes siege settlement a validation
            // boundary, not a new cold ranged constructor. Human 13 catapult
            // 1488 retains its dying knight while the final pixels are owed,
            // then clears that pointer, parks RI20 and installs Still 413/3
            // on fixture 242. Zero-minimum missile infantry keep the ordinary
            // retained-quarry Attack construction proved by XHuman 10.
            world.finishAttackOrder(unit);
            return true;
        }
        int attackStart = world.idle.battleNetSequenceStart(unit,
                BattleNetSequence.ATTACK_ANIMATION);
        if (attackStart < 0) {
            return false;
        }
        unit.clearPath();
        unit.setRouteSpent(false);
        unit.setChasing(false);
        unit.setFighting(true);
        unit.setBattleNetAttackResumeFromMove(true);
        unit.setBattleNetAttackResumeHoldActive(false);
        unit.setBattleNetAttackOp0OutOfRange(false);
        unit.setBattleNetSequenceMeleeLanded(false);
        unit.setBattleNetSequenceOffset(attackStart);
        unit.setBattleNetAnimationTimer(3);
        AnimationSet set = unit.type().animationSet();
        Animation attack = set == null ? null
                : set.get(AnimationSet.State.ATTACK);
        if (attack != null && unit.animation().current() != attack) {
            unit.animation().switchTo(attack);
        }
        return true;
    }

    /**
     * Arms a one-visit deferral of action-16 Still when the recovery timer is
     * on its last tick after this visit's sequence countdown.
     *
     * <p>XHuman 2 footman 1548 finishes at fixture 39 if Still is applied on
     * that timer==1 visit; native keeps action 16 through fixture 40. The
     * following visit applies the deferred finish via
     * {@link #stepAttack}'s held-flag arm.</p>
     */
    private boolean armBattleNetStationaryRecoveryHold(Unit unit) {
        if (world.battleNetSequence == null
                || unit.battleNetSequenceOffset() < 0
                || unit.battleNetAnimationTimer() != 1
                || unit.battleNetStationaryRecoveryHeld()) {
            return false;
        }
        unit.setBattleNetStationaryRecoveryHeld(true);
        return true;
    }

    private void finishStationaryAttackToStill(Unit unit) {
        world.finishAttackOrder(unit);
        if (world.battleNetSequence != null) {
            unit.setBattleNetSequenceOffset(
                    world.idle.battleNetStillSequenceStart(unit));
            unit.setBattleNetAnimationTimer(3);
        }
    }

    /**
     * Whether action 16 refuses a splash shot over too many friendly cells.
     *
     * <p>The shared native order handler tests type-flag {@code 0x4000} at
     * {@code 0x0040b181}; the authenticated type table assigns that bit only
     * to ballistae, catapults, battleships and juggernauts. It then walks the
     * target-centred three-by-three map cache at {@code 0x0040b23d} and calls
     * Still at {@code 0x0040b2c6} when the same-owner cell allowance is
     * exceeded. The controller byte at {@code 0x004acbac + player} supplies
     * that allowance: zero for a person, two for every non-person slot.
     * Counting cache cells, rather than distinct units, preserves building
     * footprints and native's per-square walk.</p>
     *
     * <p>The two campaign ballistae provide held-out contrasts. XHuman 10
     * slot 1483 commits its opening shot with zero friendly cells beside
     * grunt 1500, but repeatedly returns to Still when four player-one cells
     * surround grunt 1475. XHuman 4 slot 1488 likewise commits at zero and
     * refuses its later crowded targets.</p>
     */
    private boolean battleNetStationarySplashSafetyRefuses(
            Unit attacker, Unit target) {
        if (attacker.type() == null || target == null) {
            return false;
        }
        int type = PudUnitTypes.code(attacker.type().ident());
        if (type != 4 && type != 5 && type != 32 && type != 33) {
            return false;
        }
        int allowance = world.isPerson(attacker.player()) ? 0 : 2;
        int friendlyCells = 0;
        for (int y = target.tileY() - 1; y <= target.tileY() + 1; y++) {
            for (int x = target.tileX() - 1; x <= target.tileX() + 1; x++) {
                if (!world.map.contains(x, y)) {
                    continue;
                }
                List<Unit> cached = world.unitCache.get(
                        x + y * world.map.width());
                if (cached == null || cached.isEmpty()) {
                    continue;
                }
                // Native reads the map cell's one cached pointer and does not
                // walk through a dead head to another occupant on that cell.
                Unit occupant = cached.get(0);
                if (!occupant.isAlive() || occupant.isDying()
                        || !occupant.isOnMap()
                        || occupant.player() != attacker.player()) {
                    continue;
                }
                friendlyCells++;
                if (friendlyCells > allowance) {
                    return true;
                }
            }
        }
        return false;
    }

    boolean stepBattleNetAttackSequence(Unit unit) {
        if (world.battleNetSequence == null || !unit.canMove()) {
            return false;
        }
        if (openBattleNetRetainedDyingRangedConstruction(unit)) {
            return true;
        }
        Unit sequenceTarget = unit.target();
        boolean completedMeleeArrival = unit.chasing()
                && !unit.isMoving()
                && world.battleNetMoveAnimation(unit)
                && world.movement.atMoveBoundary(unit)
                && unit.battleNetPendingMeleeSyncRand()
                && sequenceTarget != null
                && sequenceTarget.isAlive()
                && world.targets.inAttackRange(unit, sequenceTarget);
        // Type 50 (wise-man): completed Move returns to attack animation four
        // with timer 3 before another cached heading may be taken. Human 13
        // slot 1496 is the compact witness (hold c21-c23, step S at c24).
        boolean chaseDecision = unit.chasing()
                && PudUnitTypes.code(unit.type().ident()) == 50
                && !unit.isMoving()
                && world.battleNetMoveAnimation(unit)
                && world.movement.atMoveBoundary(unit);
        // Settled in weapon range with the chase flag still set: keep the
        // Attack sequence ticking. Requiring !chasing froze Human 13 ogre 90
        // and grunt 93 Attack programs for ~25 cycles after they stopped
        // moving (last marker ~c8, resume ~c33), so OP10 landed at fixture 40
        // instead of 37 and the missing damage rolls shifted the async stream
        // into critter 1399's fixture-37 OP0 (choice 24 wander vs native Still
        // until 42).
        boolean retainedDyingRangedInRange = !unit.isMoving()
                && sequenceTarget != null && sequenceTarget.isDying()
                && unit.type() != null && unit.type().firesMissile()
                && world.targets.inAttackRange(unit, sequenceTarget);
        boolean settledInRange = !unit.isMoving()
                && sequenceTarget != null
                && (sequenceTarget.isAlive()
                        || retainedDyingRangedInRange)
                && world.targets.inAttackRange(unit, sequenceTarget);
        int attackStart = world.idle.battleNetSequenceStart(unit,
                BattleNetSequence.ATTACK_ANIMATION);
        // A ranged approach hold is a committed pause, not permission to
        // finish the rest of the attack body against empty air.  Human 13
        // axethrower 1505 seals Attack@887/63 while knight 1493 is in range.
        // The knight walks away during that pause; on the 887/1 visit retail
        // changes directly to Move@833 and takes its next chase step.  Java
        // had cleared chasing when it armed the hold, so it walked offsets
        // 888..900 for ten extra cycles before noticing the same distance.
        boolean rangedHoldExpiredOutOfRange = unit.battleNetAttackResumeHoldActive()
                && attackStart >= 0
                && unit.battleNetSequenceOffset() == attackStart
                && unit.battleNetAnimationTimer() == 1
                && unit.type() != null
                && unit.type().firesMissile()
                && sequenceTarget != null
                && sequenceTarget.isAlive()
                && !settledInRange;
        if (rangedHoldExpiredOutOfRange) {
            // Keep ResumeHoldActive across this one chase leg. It records
            // that the committed hold has been paid; the in-range OP0 after
            // arrival must enter the firing body instead of charging 63
            // cycles again. The ordinary action-marker cleanup below clears
            // it once that OP0 has advanced.
            unit.setBattleNetRangedFreeScanHoldActive(false);
            unit.setBattleNetRangedFreeScanHoldPending(false);
            unit.setBattleNetAttackOp0OutOfRange(true);
            unit.setFighting(false);
            unit.setChasing(true);
        }
        // A settled chase on the last quiet Move-body tick has reached the
        // boundary, but that visit is still owned by Move.  Native exposes
        // timer one in the cycle-end unit record and consumes the cached
        // heading only on the following OP0 visit.  Merely looking ahead to
        // the marker made Java spend the heading on the timer-one visit:
        // Human 8 attack-peasant 1520 moved NW at fixture 88 while native
        // held 74,65 and moved on fixture 89.  Keep the permit sticky so the
        // next visit can pass through the ordinary Move decision without
        // charging another quiet cycle.
        if (unit.chasing() && unit.battleNetMovingQuarryResidual()
                && !unit.isMoving()
                && !unit.walkHolding()
                && unit.offsetX() == 0 && unit.offsetY() == 0
                && onBattleNetChaseMoveBody(unit)
                && unit.pathLength() > 0
                && unit.battleNetAnimationTimer() == 1
                && !unit.battleNetChaseStepReady()) {
            BattleNetSequence.Tick nextMoveTick = world.battleNetSequence.tick(
                    unit.battleNetSequenceOffset(),
                    unit.battleNetAnimationTimer());
            if (nextMoveTick.valid() && nextMoveTick.actionMarker()) {
                unit.setBattleNetChaseStepReady(true);
                int peek = unit.peekHeading();
                int stride = world.battleNetMovementStride(unit);
                boolean blockedStaleHead = sequenceTarget != null
                        && (unit.pathGoalX() != sequenceTarget.tileX()
                                || unit.pathGoalY()
                                        != sequenceTarget.tileY())
                        && !world.canEnter(unit,
                                unit.tileX()
                                        + Direction.deltaX(peek) * stride,
                                unit.tileY()
                                        + Direction.deltaY(peek) * stride);
                if (unit.battleNetOrderDelay() > 0) {
                    // stepBattleNetAttackSequence runs before the logical
                    // refusal-delay gate. On the visible timer-one callback,
                    // consume that final logical count here without advancing
                    // the Move cursor. A free stale head remains parked until
                    // the next callback (Human 8 fixture 88 -> 89); a blocked
                    // stale head may continue below and re-enter refusal on
                    // this callback (fixture 180 -> Move-start/15).
                    unit.setBattleNetOrderDelay(
                            unit.battleNetOrderDelay() - 1);
                    if (!blockedStaleHead) {
                        return true;
                    }
                }
                if (!blockedStaleHead) {
                    return true;
                }
                // Timer one was visible on the previous cycle; this visit is
                // already Move OP0. A moved PathFinderInput goal whose stale
                // cached head now refuses is recalculated on this callback,
                // allowing its refusal band to begin immediately. Human 8
                // attack-peasant 1520 sees 75,64 -> 76,63 on fixture 180 and
                // exposes Move 15 there; inserting another quiet visit shifts
                // the wake past fixture 195. The cycle-88 contrast has a free
                // stale north-west head, so it still returns above, exposes
                // timer one, and spends that byte on the following visit.
            }
        }
        if ((unit.chasing() && !chaseDecision && !completedMeleeArrival
                && !settledInRange)
                || unit.isMoving()) {
            return false;
        }
        int offset = unit.battleNetSequenceOffset();
        int moveStart = world.idle.battleNetSequenceStart(unit,
                BattleNetSequence.MOVE_ANIMATION);
        // In-range and settled (chase flag optional): the Attack program owns
        // the swing. A leftover Move body after the approach step left Human
        // 5 grunts 1528/1532 on Move offsets (2482+) while the presentation
        // Attack animation fired hit(); opcode 10 never ran, so deferred
        // melee pending never resolved and the barracks under-damaged at
        // fixture 32 (native -8 vs axe-only -3). Standing 1531 stayed on
        // Attack (2539+) and landed its OP10 blow.
        boolean standingInRange = settledInRange;
        boolean resumedFromMove = false;
        if (standingInRange && attackStart >= 0 && moveStart >= 0
                && offset >= moveStart && offset < attackStart) {
            boolean paidWrapConstruction =
                    unit.battleNetAttackWrapDestArmPending();
            offset = attackStart;
            unit.setBattleNetSequenceOffset(offset);
            unit.setBattleNetAnimationTimer(paidWrapConstruction ? 1 : 3);
            // This is a new sequence-owned swing.  The duplicate-hit latch
            // belongs to the Attack body that preceded the chase, not to the
            // unit: carrying it across Move silently suppresses the next OP10.
            unit.setBattleNetSequenceMeleeLanded(false);
            // Human 13 axe 1483 re-enters Attack from the Move body then
            // stalls on the opening OP0 (timer 63). Mark the resume so the
            // next in-range OP0 can match that pre-fire hold.
            // A retained Die pointer still crosses the same Move -> Attack
            // construction boundary, but it is not a fresh free-scan visit.
            // XHuman 10 axethrower 1478 keeps dying footman 1492 and opens
            // 887/3 at fixture 57; scanning here replaces the pointer instead
            // of entering the proved OP0 hold.
            resumedFromMove = !paidWrapConstruction
                    && !retainedDyingRangedInRange;
            unit.setBattleNetAttackResumeFromMove(!paidWrapConstruction);
            if (paidWrapConstruction) {
                unit.setBattleNetAttackWrapDestArmPending(false);
            }
        }
        if (completedMeleeArrival && offset != attackStart) {
            if (attackStart < 0) {
                return false;
            }
            offset = attackStart;
            unit.setBattleNetSequenceOffset(offset);
            unit.setBattleNetAnimationTimer(1);
            // Arrival starts another Attack body even when the previous body
            // landed before this chase began.  Native XHuman 10 knight 1493
            // proves the lifetime: it hits grunt 1475, chases grunt 1477, and
            // must hit again at the new body's OP10 on fixture 154.
            unit.setBattleNetSequenceMeleeLanded(false);
        }
        if (chaseDecision && offset != attackStart) {
            if (attackStart < 0) {
                return false;
            }
            unit.setBattleNetSequenceOffset(attackStart);
            unit.setBattleNetAnimationTimer(3);
            return true;
        }
        if (offset < 0) {
            offset = attackStart;
            if (offset < 0) {
                return false;
            }
            unit.setBattleNetSequenceOffset(offset);
            unit.setBattleNetAnimationTimer(3);
        }
        // Mid-OP0 free-scan retarget: Human 13 knight 1490 switches axe at
        // 124,33 → ogre at 123,31 while still on Attack@1922, re-arms timer 3
        // and face 5, so the catapult splash at fixture 35 lands during OP0
        // rather than mid-windup. Without this re-arm Java walks OP0 into
        // windup one visit early and the splash lands on 1927.
        //
        // Ranged free-scan on the OP0 fire visit (timer 1) or the dest-arm
        // leftover residual-open that just installed construction timer 3.
        // Human 13 axe 1483 lands that residual and names the wise-man on
        // the same visit native opens Attack@887/3; waiting for timer 1
        // re-armed construction and delayed the axe three cycles (99 vs 102).
        // Scanning every mid-wait tick still retargets early (XHuman 10
        // archer 98). Melee keeps the broader timer>0 window (Human 13
        // knight 1490).
        boolean rangedOp0 = unit.type() != null
                && unit.type().maxAttackRange() > 1
                && unit.type().firesMissile();
        // Attack-start bulk holds are unbreakable until their OP0 fires.
        // Construction is also parked on attackStart, but remains eligible
        // for the proved mid-wait melee scan (Human 13 knight 1490 at timer
        // two). The hold provenance, rather than the cursor alone, separates
        // those states: XHuman 12 grunt 1448 must retain its footman through
        // timer 23..1 and only scan the knight on the following OP0.
        boolean attackStartHold = unit.battleNetAttackResumeHoldActive();
        // A tail retarget can temporarily name a spatial quarry while a
        // still-live hit response remains offered to this same order. Retail
        // lets the fresh target own the quiet 3,2,1 constructor, then prices
        // the offer at OP0. Scanning during timer 3 or 2 restored XHuman 4's
        // offered grunt too early. A settled-leftover scan does not carry
        // resume provenance, so XHuman 10's deliberately replaced knight
        // never enters this state.
        boolean pendingResumeOffer = !rangedOp0
                && unit.battleNetAttackResumeFromMove()
                && unit.offeredTarget() != null
                && unit.offeredTarget().isAlive()
                && unit.offeredTarget() != sequenceTarget;
        // A dying melee CUnitPtr still owns the remainder of an Attack-start
        // constructor. Retail does not spatially replace it on timer two;
        // the timer-one OP0 visit performs that scan and installs the new
        // quarry directly into the already-paid body hold. XHuman 10 knight
        // 1480 is the sealed witness: it keeps dying footman 1471 through
        // Attack@1922/1 on fixture 204, then names grunt 1482 and parks at
        // 1922/23 on fixture 205. Scanning on timer two restarted 3,2,1 and
        // delayed the synchronized table-0x27 debit by one fixture.
        boolean dyingMeleeConstructor = !rangedOp0
                && !attackStartHold
                && attackStart >= 0
                && offset == attackStart
                && sequenceTarget != null
                && sequenceTarget.isDying();
        int op0RangedType = unit.type() == null ? -1
                : PudUnitTypes.code(unit.type().ident());
        boolean splashConstructor = rangedOp0
                && !unit.chasing()
                && !unit.isMoving()
                && (op0RangedType == 4 || op0RangedType == 5
                        || op0RangedType == 32 || op0RangedType == 33)
                && attackStart >= 0 && offset == attackStart
                && sequenceTarget != null;
        boolean stationarySplashConstructor = splashConstructor
                && unit.battleNetStationaryAttack();
        boolean retainedPersonNavalHitTarget = rangedOp0
                && unit.battleNetSpatialHitHelpHandoff()
                && world.isPerson(unit.player())
                && unit.type() != null
                && unit.type().moveType() == UnitType.Movement.NAVAL;
        boolean refusedLiveSplash = splashConstructor
                && unit.battleNetAnimationTimer() == 1
                && sequenceTarget.isAlive()
                && world.targets.inAttackRange(unit, sequenceTarget)
                && battleNetStationarySplashSafetyRefuses(
                        unit, sequenceTarget);
        if (refusedLiveSplash) {
            // The safety predicate precedes AutoSelectTarget for a live
            // incumbent. A crowded target therefore closes this aim pulse;
            // it is not replaced by another quarry on the same OP0.
            finishStationaryAttackToStill(unit);
            return true;
        }
        boolean freeScanWindow = unit.battleNetAnimationTimer() > 0
                && !retainedPersonNavalHitTarget
                && (rangedOp0
                        ? unit.battleNetAnimationTimer() == 1
                                || resumedFromMove
                        : !attackStartHold
                                && (!pendingResumeOffer
                                        && !dyingMeleeConstructor)
                                || unit.battleNetAnimationTimer() == 1);
        boolean deferMeleeRetarget = false;
        if (attackStart >= 0
                && offset == attackStart
                && freeScanWindow
                && unit.canMove()
                && unit.type() != null
                && sequenceTarget != null
                && !(rangedOp0 && retainedDyingRangedInRange
                        && unit.battleNetAttackResumeFromMove())
                && (sequenceTarget.isAlive() || sequenceTarget.isDying())) {
            // A committed ranged OP0 still performs its free scan when the
            // incumbent has entered Die but remains a live CUnitPtr. Retail
            // XHuman 10 archer 1502 replaces dying grunt 1495 at fixture 155
            // and restarts Attack construction; rejecting the dying incumbent
            // here let action 16 fall through to Still instead.
            int reactRange = Math.max(
                    unit.type().reactRange(world.isPerson(unit.player())),
                    Math.max(1, unit.type().maxAttackRange()));
            // Once the offered aggressor has become the live goal, later
            // AutoSelectTarget passes price that banked goal first. Equal
            // scores do not dislodge it. XHuman 4 grunt 1489 installs footman
            // 1495 on fixture 73 and retains it through Attack OP0; null-
            // seeding this fixture-74 pass immediately switched to northern
            // 1518. Ordinary OP0 scans remain null-seeded: footman 1518 must
            // still switch between equal adjacent grunts on fixture 57.
            // A final cached chase heading is priced before construction,
            // but it is no longer an incumbent once the timer-one OP0
            // callback is reached. Retail performs a fresh spatial-order
            // scan there: XHuman 10 grunt 1495 keeps offered knight 1489 and
            // route index one through fixture 69, then selects the equal-
            // score knight 1493 which appears first in the screen-Y list at
            // fixture 70. Seeding 1489 made equal scores retain the stale
            // goal forever. Earlier construction visits still seed their
            // banked offer (the XHuman 4 fixture-74 witness).
            boolean settledLeftoverOp0 = !rangedOp0
                    && unit.chasing()
                    && unit.pathLength() == 1
                    && unit.battleNetAnimationTimer() == 1;
            Unit offered = unit.offeredTarget();
            Unit scanIncumbent = !settledLeftoverOp0
                    && (sequenceTarget == offered
                            || unit.battleNetAttackResumeFromMove())
                    ? offered : null;
            Unit candidate = world.targets.findBattleNetHostile(
                    unit, reactRange, scanIncumbent);
            boolean candidateInRange = candidate != null
                    && candidate != sequenceTarget
                    && candidate.isAlive()
                    && world.targets.inAttackRange(unit, candidate);
            boolean surfacedLadenQuarryConstructor = candidateInRange
                    && !rangedOp0
                    && unit.battleNetAnimationTimer() == 1
                    && unit.chasing() && !unit.isMoving()
                    && unit.pathLength() == 1 && unit.stepDrained()
                    && unit.type().maxAttackRange() <= 1
                    && sequenceTarget.carried() > 0
                    && sequenceTarget.order() == Unit.Order.STILL
                    && sequenceTarget.hasQueuedOrders()
                    && sequenceTarget.queuedOrders().getFirst().kind()
                            == Unit.QueuedOrderKind.RETURN_GOODS
                    && unit.offeredTarget() != sequenceTarget;
            if (surfacedLadenQuarryConstructor) {
                // The last quarry stride settled on this visit and opened
                // Attack construction. AutoSelectTarget can already see an
                // equal adjacent unit, but native retains the surfaced quarry
                // for 3,2,1 and makes that scan only on the following OP0.
                // Keep this phase semantic: it follows any laden quarry which
                // surfaced while the pursuer's committed pixels drained.
                unit.setBattleNetAttackRefusalRecoveryStage(
                        SURFACED_QUARRY_RETARGET_CONSTRUCTION);
                unit.setBattleNetSequenceOffset(attackStart);
                unit.setBattleNetAnimationTimer(3);
                unit.setBattleNetChaseStepReady(false);
                return true;
            }
            boolean candidateOutOfRange = candidate != null
                    && candidate != sequenceTarget
                    && candidate.isAlive()
                    && !world.targets.inAttackRange(unit, candidate)
                    && !rangedOp0
                    && unit.type().maxAttackRange() <= 1
                    && !unit.battleNetStationaryAttack()
                    && unit.autoTargeting()
                    && (sequenceTarget == null
                            || !sequenceTarget.isAlive()
                            || sequenceTarget.isDying());
            if (candidateOutOfRange) {
                // AutoSelectTarget is also allowed to replace a gone melee
                // quarry from an automatically owned OP0 which is already
                // parked at Attack start; this is the same acceptance rule as
                // tail -> OP0 below, not an in-range-only construction scan.
                // Human 13 ogre 1519 is the authenticated existing-OP0
                // witness: its adjacent knight enters Die while the 23-count
                // body hold drains, then fixture 192 names the knight two
                // tiles west, keeps action 12, and opens Attack 643/3,2,1
                // before dest-arming southwest. A direct/person hit response
                // is deliberately excluded: XHuman 10 knight 1489 pays its
                // temporary Attack 1922/3,2,1 and returns to Still on fixture
                // 179 despite another visible grunt two tiles away.
                setAutoTarget(unit, candidate);
                unit.setBattleNetAttackResumeHoldActive(false);
                unit.setBattleNetStationaryRecoveryHeld(false);
                unit.setBattleNetSequenceOffset(attackStart);
                unit.setBattleNetAnimationTimer(3);
                unit.setBattleNetSequenceMeleeLanded(false);
                unit.setOfferedTarget(candidate);
                unit.setFighting(false);
                unit.setChasing(false);
                unit.setBattleNetAttackWrapDestArmPending(true);
                if (World.BNE_IDLE_TRACE) {
                    System.err.printf("JBNEATTACKOP0DESTARM cycle=%d "
                                    + "unit=%d from=%d to=%d timer=3%n",
                            world.cycle, unit.id(),
                            sequenceTarget == null ? -1
                                    : sequenceTarget.id(),
                            candidate.id());
                }
                return true;
            }
            boolean completedMeleeRetargetDelay = candidateInRange
                    && !rangedOp0
                    && unit.battleNetOrderDelay() > 0
                    && unit.battleNetAnimationTimer() == 1;
            // A newly acquired melee order carries a two-visit target-
            // selection delay while Attack construction still counts down.
            // Native scans and reports the better candidate on both visits,
            // but keeps the old target pointer and advances 3,2,1; only the
            // timer-one visit installs the candidate and restarts at 3.
            // XHuman 4 footman 1518 is the direct witness: it holds grunt
            // 1489 through fixtures 54..56, selects adjacent grunt 1515 on
            // 55/56, switches on 57, and enters the native 23-count OP0 hold
            // after construction. Switching on 55 froze construction under
            // the surrogate delay and let it hit 1515 at 68, three cycles
            // before native.
            deferMeleeRetarget = candidateInRange && !rangedOp0
                    && unit.battleNetOrderDelay() > 0
                    && unit.battleNetAnimationTimer() > 1;
            if (deferMeleeRetarget
                    && unit.battleNetOrderDelay() > 1) {
                unit.setBattleNetOrderDelay(
                        unit.battleNetOrderDelay() - 1);
            }
            if (candidateInRange && !deferMeleeRetarget) {
                setAutoTarget(unit, candidate);
                if (stationarySplashConstructor
                        && unit.battleNetAnimationTimer() == 1
                        && battleNetStationarySplashSafetyRefuses(
                                unit, candidate)) {
                    // A dying incumbent may reach the free scan first. Apply
                    // the same native cell predicate to its replacement: a
                    // safe replacement restarts construction, while XHuman
                    // 10's crowded grunt 1475 completes through Still.
                    finishStationaryAttackToStill(unit);
                    return true;
                }
                world.turnToTarget(unit, candidate, 0, 0);
                // The pre-scan callback may have deferred the first melee
                // draw solely because the old goal still owned one cached
                // heading. SetAutoTarget has now cleared that stale output;
                // retry the same live in-range callback against the new goal.
                // This is the native fixture-70 FUN_004234b0 visit, not a
                // second callback or a map-specific RNG debit.
                if (!rangedOp0 && settledLeftoverOp0) {
                    world.consumeBattleNetPendingMeleeSyncRand(unit);
                }
                // Retargeting returns before the ordinary tail below can tick
                // table 0x27. If its countdown is due on this same OP0 visit,
                // carry that debit across the early return before replacing
                // the old body with fresh construction. This is true for an
                // unbreakable resume hold (XHuman 12 c66) and for an ordinary
                // completed attack loop: native footman 1449 changes +0xb
                // 0->41 while retargeting on fixture 78, the second of two
                // synchronized draws that visit. Limiting this to resume-hold
                // provenance left Java one draw short.
                if (unit.battleNetMeleeSyncRemaining() == 1) {
                    world.debitBattleNetAttackLoopSyncRand(unit);
                }
                if (!rangedOp0 && attackStartHold) {
                    unit.setBattleNetAttackResumeHoldActive(false);
                }
                // Construction-open retarget keeps the 3,2,1 countdown.
                // Re-arming timer 3 here is only for a true OP0 fire visit.
                // Replacing a temporary tail quarry with its still-live offer
                // on the final constructor visit flows directly into the
                // committed body hold; that constructor is already paid.
                boolean completedResumeOffer = pendingResumeOffer
                        && unit.battleNetAnimationTimer() == 1;
                boolean completedDyingConstructor = dyingMeleeConstructor
                        && unit.battleNetAnimationTimer() == 1;
                boolean restartConstruction = !rangedOp0
                        ? !completedResumeOffer
                                && !completedDyingConstructor
                        : unit.battleNetAnimationTimer() == 1
                                && !resumedFromMove;
                if (restartConstruction) {
                    unit.setBattleNetSequenceOffset(attackStart);
                    unit.setBattleNetAnimationTimer(3);
                    // Retarget construction starts a distinct sequence-owned
                    // swing.  Do not let the old target's duplicate-hit latch
                    // suppress this body's OP10.
                    unit.setBattleNetSequenceMeleeLanded(false);
                    boolean outOfRangeMeleeReplacement = !rangedOp0
                            && sequenceTarget.isAlive()
                            && !world.targets.inAttackRange(
                                    unit, sequenceTarget);
                    if (outOfRangeMeleeReplacement) {
                        // Replacing an out-of-range melee quarry with an
                        // in-range one is not a cold first swing. After fresh
                        // 3,2,1 construction retail parks the replacement
                        // action at attack-start for bodyWaitSum-1. Human 8
                        // attack-peasant 1501 names adjacent chopping peasant
                        // 1499 on fixture 198, then exposes 2657/23 on 201.
                        unit.setBattleNetAttackResumeFromMove(true);
                        unit.setBattleNetAttackOp0OutOfRange(true);
                    }
                }
                if (completedMeleeRetargetDelay) {
                    // Installing the deferred target is the first live
                    // in-range Attack callback, so table 0x27 is charged on
                    // this visit even though construction just restarted.
                    world.consumeBattleNetPendingMeleeSyncRand(unit);
                    // The completed retarget uses the same post-construction
                    // OP0 hold as an in-range melee residual: after 3,2,1,
                    // native parks for bodyWaitSum-1 rather than entering the
                    // windup. Reuse that proved state-machine arm; XHuman 4
                    // footman 1518 reaches Attack@2539/23 on fixture 60.
                    unit.setBattleNetAttackResumeFromMove(true);
                    unit.setBattleNetAttackOp0OutOfRange(true);
                }
                if (completedDyingConstructor) {
                    // The old pointer paid construction through timer one.
                    // Reuse the normal melee-resume OP0 arm below so this
                    // same visit parks bodyWaitSum-1 instead of either
                    // restarting construction or entering windup.
                    unit.setBattleNetAttackResumeFromMove(true);
                    unit.setBattleNetAttackOp0OutOfRange(true);
                }
                if (!rangedOp0 && attackStartHold) {
                    // Replacing the quarry on the final visit of an OP0
                    // body hold starts a new swing; it does not turn that
                    // hold into an ordinary windup. Preserve the same
                    // post-construction arm used by chase arrivals so the
                    // fresh 3,2,1 constructor enters bodyWaitSum-1 again.
                    // XHuman 12 grunt 1448 proves both halves: it holds the
                    // footman through timer one, retargets the adjacent
                    // knight at fixture 66, then holds 23.. before dealing
                    // any damage. Java previously entered windup and hit the
                    // knight six points early at fixture 79.
                    unit.setBattleNetAttackResumeFromMove(true);
                    unit.setBattleNetAttackOp0OutOfRange(true);
                }
                if (rangedOp0) {
                    // A replacement target starts a new ranged OP0 hold.
                    // The previous hold's presentation-suppression bit can
                    // still be set when its timer expires; carrying it across
                    // construction makes the next OP0 skip the wall-clock
                    // cadence entirely. XHuman 10 archer 1502 proves the
                    // recurring case: both retargets construct 3,2,1 and then
                    // park at Attack@2039/63. Java held the first one but let
                    // the second enter windup and create a phantom arrow.
                    if (restartConstruction) {
                        unit.setBattleNetAttackResumeHoldActive(false);
                    }
                    unit.setBattleNetAttackResumeFromMove(true);
                    unit.setBattleNetAttackOp0OutOfRange(true);
                    if (restartConstruction) {
                        unit.setBattleNetRangedFreeScanHoldPending(true);
                    }
                }
                if (World.BNE_IDLE_TRACE) {
                    System.err.printf("JBNEATTACKOP0RETARGET cycle=%d unit=%d "
                                    + "from=%d to=%d timer=%d restart=%d "
                                    + "ranged=%d%n",
                            world.cycle, unit.id(),
                            sequenceTarget.id(), candidate.id(),
                            unit.battleNetAnimationTimer(),
                            restartConstruction ? 1 : 0,
                            rangedOp0 ? 1 : 0);
                }
                if (restartConstruction) {
                    return false;
                }
            }
        }
        // Construction 3,2,1 after a melee tail wrap onto an out-of-range
        // quarry is already spent. The next Attack-start OP0 dest-arms
        // leftover via the dest-arm leftover acquire arm instead of walking
        // OP0 into windup (Human 13 ogre 1511: 643/1 at 117, dest-arm SW,S
        // onto 119,27 at 118).
        if (unit.battleNetAttackWrapDestArmPending()
                && attackStart >= 0
                && offset == attackStart
                && unit.battleNetAnimationTimer() == 1
                && sequenceTarget != null
                && sequenceTarget.isAlive()
                && !world.targets.inAttackRange(unit, sequenceTarget)) {
            return false;
        }
        BattleNetSequence.Tick tick = world.battleNetSequence.tick(offset,
                unit.battleNetAnimationTimer());
        if (World.BNE_IDLE_TRACE) {
            System.err.printf("JBNEATTACKSEQ cycle=%d unit=%d offset=%d->%d "
                            + "timer=%d->%d marker=%d inline=%d valid=%d%n",
                    world.cycle, unit.id(), offset, tick.offset(),
                    unit.battleNetAnimationTimer(), tick.timer(),
                    tick.actionMarker() ? 1 : 0,
                    tick.inlineActionMarker() ? 1 : 0,
                    tick.valid() ? 1 : 0);
        }
        if (World.BNE_PEND_TRACE && tick.actionMarker()) {
            Unit tgt = unit.target();
            boolean inRange = tgt != null && tgt.isAlive()
                    && world.targets.inAttackRange(unit, tgt);
            System.err.printf("JBNEATTACKOP0 cycle=%d unit=%d type=%s "
                            + "off=%d->%d timer=%d->%d chasing=%d pathLen=%d "
                            + "moving=%d wait=%d inRange=%d target=%d "
                            + "missile=%d%n",
                    world.cycle, unit.id(),
                    unit.type() == null ? "?" : unit.type().ident(),
                    offset, tick.offset(), unit.battleNetAnimationTimer(),
                    tick.timer(), unit.chasing() ? 1 : 0, unit.pathLength(),
                    unit.isMoving() ? 1 : 0, unit.waitCycles(),
                    inRange ? 1 : 0, tgt == null ? -1 : tgt.id(),
                    unit.type() != null && unit.type().firesMissile() ? 1 : 0);
        }
        if (!tick.valid()) {
            unit.setBattleNetSequenceOffset(-1);
            return false;
        }
        // This cursor is already the authority for the swing and its OP10
        // damage boundary, so it must also own the sprite frame selected on
        // the same visit.  Leaving frames to the independent presentation
        // program made a perfectly live melee look frozen: the Human
        // expansion 3 playtest save had footmen, a knight and a grunt dealing
        // damage while remaining visibly on frame zero.
        if (tick.frame() >= 0) {
            unit.setFrame(tick.frame());
        }
        // A completed Attack program returns from its tail directly through
        // OP0. Retail performs the free target scan on that transition, not
        // one scheduler visit later after the cursor already sits at the
        // opening offset. The pre-tick scan above covers an existing OP0;
        // this covers tail -> OP0. Human 13 axethrower 1486 changes knight
        // 1490 to wise-man 1496 at fixture 71, holds attackStart 3,2,1,63,
        // and therefore does not invent a second axe at fixture 81.
        //
        // The scan is not ranged-only. Human 13 knight 1493 wraps 1945/1
        // onto Attack@1922/3 and names ogre 1511 once that ogre is adjacent.
        // Wrap without an in-range replacement still walks OP0 (1923/1 at
        // 98). When the quarry is already dying, the wrap still names a new
        // quarry: ogre 1511 wraps 666/1 onto 643/3 and names knight 1493 at
        // 115 even though 1493 is two tiles off, then dest-arms leftover
        // SW,S onto 119,27 at 118. Java used to finish Attack to Still at
        // 644/1 and leave 1511 on 120,26, so 1493's wrap could not see it.
        if (tick.actionMarker()
                && attackStart >= 0
                && offset != attackStart
                && unit.canMove()
                && unit.type() != null) {
            boolean quarryGone = sequenceTarget == null
                    || !sequenceTarget.isAlive()
                    || sequenceTarget.isDying();
            int rangedType = PudUnitTypes.code(unit.type().ident());
            boolean stationarySiegeTail = rangedOp0
                    && unit.battleNetStationaryAttack()
                    && (rangedType == 4 || rangedType == 5);
            boolean scanWrap = rangedOp0
                    ? !stationarySiegeTail && sequenceTarget != null
                            && (sequenceTarget.isAlive()
                                    || sequenceTarget.isDying())
                    : true;
            // Stationary siege action 16 completes through Still before idle
            // acquisition can install another target; it does not borrow the
            // archer/axethrower tail free-scan. The sealed corpus contains 32
            // siege Attack@tail/1 -> Still@start/3 transitions. XHuman 10
            // ballista 1483 is the compact witness: fixtures 226..230 expose
            // Attack 1716/1, Still 1609/3,2,1, then Attack 1699/3. Mobile
            // action 12 is the counterexample: computer catapult 1487 wraps
            // 540/1 directly onto Attack 503/3 at fixture 203.
            if (scanWrap) {
                int reactRange = Math.max(
                        unit.type().reactRange(world.isPerson(unit.player())),
                        Math.max(1, unit.type().maxAttackRange()));
                // OfferNewTarget belongs to COrder_Attack and is priced
                // before the spatial walk on every AutoSelectTarget pass,
                // including the callback reached directly from an Attack
                // tail. Equal scores therefore retain the unit which most
                // recently struck this attacker. XHuman 10 footman 1542 is
                // surrounded by three adjacent ogres at fixture 83; native
                // keeps offered ogre 1543, restarts Attack construction and
                // enters its committed body hold. Null-seeding this wrap
                // picked earlier screen-Y ogre 1548 and dealt damage on 93.
                Unit candidate = world.targets.findBattleNetHostile(
                        unit, reactRange, unit.offeredTarget());
                boolean inRange = candidate != null && candidate.isAlive()
                        && world.targets.inAttackRange(unit, candidate);
                boolean mobileRangedChase = rangedOp0
                        && !unit.battleNetStationaryAttack();
                boolean takeOutOfRange = candidate != null
                        && candidate.isAlive()
                        && !inRange
                        && ((quarryGone
                                && unit.type().maxAttackRange() <= 1
                                && !unit.battleNetStationaryAttack())
                                || mobileRangedChase);
                boolean acceptsCandidate = candidate != null
                        && candidate != sequenceTarget
                        && candidate.isAlive()
                        && (inRange || takeOutOfRange);
                // A dying splash quarry still reaches the shared safety
                // predicate before its mobile tail replacement is installed.
                // Human 13's two catapults see more than the computer
                // allowance of two friendly cells around the replacement
                // knight at fixture 204 and enter Still 3,2,1. XHuman 10
                // catapult 1487 is the held-out safe replacement and keeps
                // its direct action-12 retarget at fixture 203.
                if (acceptsCandidate && rangedOp0
                        && sequenceTarget != null
                        && sequenceTarget.isDying()
                        && battleNetStationarySplashSafetyRefuses(
                                unit, candidate)) {
                    finishStationaryAttackToStill(unit);
                    return true;
                }
                if (acceptsCandidate) {
                    setAutoTarget(unit, candidate);
                    if (quarryGone && !rangedOp0
                            && unit.offeredTarget() != null
                            && unit.battleNetStationaryAttack()) {
                        // A person melee defender first enters this order as
                        // stationary action 16. When its completed body loses
                        // that quarry, EndActionAttack promotes the accepted
                        // replacement to ordinary mobile action 12. XHuman 4
                        // footman 1518's banked hit offer exposes the
                        // transition on fixture 158;
                        // retaining action 16 was invisible until its next
                        // quarry died and the replacement needed a chase.
                        unit.setBattleNetStationaryAttack(false);
                    }
                    // Tail -> OP0 retarget returns before the ordinary
                    // Attack-sequence epilogue can service table 0x27. Carry
                    // a due melee re-seed across that early return just as
                    // the mid-OP0 retarget path above does. Native XHuman 12
                    // footman 1449 is the sealed witness: on fixture 78 it
                    // wraps, names a new adjacent footman, and changes its
                    // +0xb attack variant on the same visit. Deferring the
                    // draw until fixture 79 shifted every later synchronized
                    // combat decision by one RNG call.
                    // The expiring table-0x27 arm belongs to a continuing
                    // in-range swing, not merely to the old tail cursor.  When
                    // OP0 replaces a dead quarry with an out-of-range unit,
                    // retail drops the arm and starts the chase without a
                    // SyncRand debit (XHuman 9 knight 1414 at fixture 99).
                    // An adjacent replacement still refreshes on this visit,
                    // as sealed by XHuman 12 footman 1449 at fixture 78.
                    if (inRange && unit.battleNetMeleeSyncRemaining() == 1) {
                        world.debitBattleNetAttackLoopSyncRand(unit);
                    }
                    unit.setBattleNetSequenceOffset(attackStart);
                    unit.setBattleNetAnimationTimer(3);
                    // Tail -> OP0 replacement creates a fresh Attack body.
                    // The previous body's recovery and landed bits are target
                    // scoped. Carrying either through the replacement can end
                    // a live engagement after the new body has run: XHuman 10
                    // archer 1473 retargets on fixture 90 and must remain in
                    // Attack when the replacement hold expires on fixture 156.
                    unit.setBattleNetStationaryRecoveryHeld(false);
                    unit.setBattleNetSequenceMeleeLanded(false);
                    if (inRange) {
                        // A tail replacement which is already in weapon range
                        // has no destination arm left to spend.  Retaining an
                        // older out-of-range wrap token makes the fresh Attack
                        // constructor and its committed OP0 hold bypass the
                        // otherwise wall-clock table-0x27 cadence.  XHuman 10
                        // knights 1485 and 1493 both refresh on fixture 194,
                        // retarget adjacent grunt 1482, and refresh together
                        // again on fixture 220; only 1493 carries the stale
                        // token into this boundary.
                        unit.setBattleNetAttackWrapDestArmPending(false);
                        world.turnToTarget(unit, candidate, 0, 0);
                        unit.setBattleNetAttackResumeFromMove(true);
                        unit.setBattleNetAttackOp0OutOfRange(true);
                        if (rangedOp0) {
                            unit.setBattleNetRangedFreeScanHoldPending(true);
                        }
                        if (World.BNE_IDLE_TRACE) {
                            System.err.printf("JBNEATTACKLOOPRETARGET cycle=%d "
                                            + "unit=%d from=%d to=%d timer=3%n",
                                    world.cycle, unit.id(),
                                    sequenceTarget == null ? -1
                                            : sequenceTarget.id(),
                                    candidate.id());
                        }
                        // Stationary action 16 must keep the fresh Attack
                        // constructor alive; chase action 12 continues into
                        // its order body after installing the same retarget.
                        // XHuman 10 archer 1473 is action 16 at fixture 90;
                        // Human 13 axe 1505 is action 12 at fixture 25 and
                        // must dest-arm its next quarry on fixture 28.
                        return !rangedOp0
                                || unit.battleNetStationaryAttack();
                    }
                    // Mobile ranged action 12 accepts the fresh target from
                    // the tail-boundary spatial scan even when it is outside
                    // weapon range. It pays Attack construction first, then
                    // hands the order back to Move. Human 13 axethrower 1495
                    // replaces the still-live knight 1503 with wise-man 1496
                    // on fixture 100, holds 887/3,2,1, and first-steps east on
                    // 103. Rejecting the out-of-range candidate kept firing
                    // at the stale knight and left the thrower visibly parked.
                    // Stationary action 16 remains excluded: a stand-ground
                    // defender may retarget in range but must never chase.
                    unit.setOfferedTarget(candidate);
                    unit.setFighting(false);
                    unit.setChasing(false);
                    unit.setBattleNetAttackWrapDestArmPending(true);
                    if (!rangedOp0 && sequenceTarget != null
                            && sequenceTarget.removed()
                            && !sequenceTarget.isDying()
                            && sequenceTarget.order() == Unit.Order.HARVEST) {
                        // A worker hidden inside a mine is unavailable, not a
                        // dying quarry. Its Attack-tail replacement hands
                        // straight to Move OP0: target scan, route creation
                        // and the first chase step share this callback. Human
                        // 8 attack-peasant 1538 is the sealed witness: the
                        // old mine-contained peasant remains on Harvest while
                        // native names slot 1525 and steps W on fixture 130.
                        // Charging the dying-quarry construction 3,2,1 here
                        // leaves attackers visibly staring at vanished
                        // workers before they resume combat.
                        unit.setBattleNetAnimationTimer(1);
                        // The same callback enters Move, so retire the old
                        // Attack presentation directly instead of borrowing
                        // the paid-construction marker to make stepMove do it.
                        unit.animation().clearCurrent();
                        // The immediate Move handoff did not pay the new
                        // quarry's Attack construction. Retaining the generic
                        // wrap owner made the chase arrival skip 3,2,1 and
                        // strike three fixtures early (the same Human 8
                        // peasant reaches slot 1525 on fixture 146).
                        unit.setBattleNetAttackWrapDestArmPending(false);
                        return false;
                    }
                    if (World.BNE_IDLE_TRACE) {
                        System.err.printf("JBNEATTACKLOOPDESTARM cycle=%d "
                                        + "unit=%d from=%d to=%d timer=3%n",
                                world.cycle, unit.id(),
                                sequenceTarget == null ? -1
                                        : sequenceTarget.id(),
                                candidate.id());
                    }
                    return true;
                }
            }
        }
        // Human 13 knight 1490: splash during Attack OP0 (fixture 35, hp
        // 77→6) arms a bulk hold when that OP0 would fire. Native keeps
        // sequence 1922 and sets timer bodyWaitSum-1 (23) instead of walking
        // into windup/OP10, so the ogre at 123,31 stays at 90 through fixture
        // 44. Mid-windup holds REG the floor (async stream); only the OP0
        // cursor qualifies.
        boolean pendingPathOneOp0Arrival = tick.actionMarker()
                && attackStart >= 0
                && offset == attackStart
                && unit.battleNetPendingMeleeSyncRand()
                && unit.chasing()
                && unit.pathLength() == 1
                && unit.battleNetPathStepsTaken() == 1
                && !unit.isMoving()
                && sequenceTarget != null
                && sequenceTarget.isAlive()
                && world.targets.inAttackRange(unit, sequenceTarget);
        if (tick.actionMarker()
                && attackStart >= 0
                && offset == attackStart
                && !pendingPathOneOp0Arrival
                && unit.battleNetAttackOp0Damaged()) {
            int bodySum = world.battleNetSequence.attackBodyWaitSum(attackStart);
            int hold = bodySum > 0 ? bodySum - 1 : 0;
            unit.setBattleNetAttackOp0Damaged(false);
            if (hold > 0) {
                // Damage on the replacement constructor supersedes the
                // approach/retarget hold: both are the same parked Attack OP0,
                // not two consecutive periods. Human 13 knight 1490 retargets
                // from its out-of-range axe to an adjacent ogre, then takes a
                // catapult splash during 3,2,1. Retail drains one 23-count hold
                // and advances on fixture 60; retaining resume provenance
                // charges a second hold and omits that fixture's SyncRand.
                unit.setBattleNetAttackResumeFromMove(false);
                unit.setBattleNetAttackOp0OutOfRange(false);
                unit.setBattleNetSequenceOffset(attackStart);
                unit.setBattleNetAnimationTimer(hold);
                unit.setBattleNetAttackResumeHoldActive(true);
                if (World.BNE_IDLE_TRACE) {
                    System.err.printf("JBNEATTACKOP0HOLD cycle=%d unit=%d "
                                    + "bodySum=%d hold=%d%n",
                            world.cycle, unit.id(), bodySum, hold);
                }
                return false;
            }
        }
        // Human 13 axe 1483/117: after an out-of-range OP0 and a Move→Attack
        // resume, the next in-range OP0 stays on attackStart with timer 63
        // (native sealed c26–42) and does not construct through fixture 42.
        // Human 13 axe 1505 is the same start wait after a dest-arm leftover
        // residual lands in range: native is 887 timer 3,2,1 then 63, and
        // never builds the extra axe Java used to throw at fixture 38.
        // Construction timer 3 is not that start opcode wait -- ticking OP0
        // into windup spent the three-draw constructor and shifted later
        // damage. The chase flag may already be clear by the OP0 visit
        // (in-range leftover discard runs after the first resume tick).
        // Pure in-range first swings never resume from Move, so they still
        // walk the windup (early axes, XHuman 12).
        if (tick.actionMarker()
                && attackStart >= 0
                && offset == attackStart
                && unit.battleNetAttackResumeFromMove()
                && !unit.battleNetAttackResumeHoldActive()
                && unit.type() != null
                && unit.type().firesMissile()
                && unit.canMove()) {
            Unit tgt = unit.target();
            if (tgt != null && (tgt.isAlive() || tgt.isDying())
                    && world.targets.inAttackRange(unit, tgt)
                    && !unit.isMoving()
                    // Ordinary building approaches enter windup immediately
                    // (XHuman 12 axe 216). A building qualifies only when a
                    // free-scan replaced the exhausted chase quarry; that
                    // provenance is what distinguishes axe 79's native hold.
                    && (tgt.type() == null || !tgt.type().building()
                            || unit.battleNetRangedFreeScanHoldPending())) {
                unit.setBattleNetAttackResumeFromMove(false);
                unit.setBattleNetAttackOp0OutOfRange(false);
                unit.setBattleNetAttackResumeHoldActive(true);
                if (retainedPersonNavalHitTarget) {
                    // The queued HitUnit source remains the incumbent through
                    // this arrival OP0. Once the full broadside hold owns the
                    // action, ordinary later target scans may resume.
                    unit.setBattleNetSpatialHitHelpHandoff(false);
                }
                if (unit.battleNetRangedFreeScanHoldPending()) {
                    unit.setBattleNetRangedFreeScanHoldActive(true);
                    unit.setBattleNetRangedFreeScanHoldPending(false);
                }
                unit.setBattleNetSequenceOffset(attackStart);
                int remaining = unit.battleNetRangedAttackCadenceRemaining();
                int bodyWait = world.battleNetSequence
                        .attackBodyWaitSum(attackStart);
                // A free-scan which replaces a mobile quarry with a building
                // owns a fresh native OP0 period. Person-to-person scans keep
                // draining the existing ranged cadence: Human 13 axe 1484
                // still throws at fixture 101, while XHuman 12 axe 1517's
                // replacement guard tower restarts the full period.
                boolean buildingFreeScan =
                        unit.battleNetRangedFreeScanHoldActive()
                        && tgt.type() != null && tgt.type().building();
                // A free-scan performed as the last committed chase stride
                // is discarded owns a fresh OP0 period too. The old quarry's
                // wall-clock is no longer the live attack action at this
                // boundary: XHuman 10 axethrower 1496 parks route index 20,
                // changes targets, and exposes 887/63 at fixture 77. Serving
                // the old remaining value (44) launched a phantom axe at 131
                // while native was still on Move-start/9. Keep ordinary
                // mid-action mobile retargets on their unspent cadence.
                boolean exhaustedResidualFreeScan =
                        unit.battleNetRangedFreeScanHoldActive()
                        && unit.battleNetRetargetResidualRoutePark()
                        && unit.stepDrained() && unit.pathLength() == 0;
                int hold = buildingFreeScan || exhaustedResidualFreeScan
                        ? Math.max(0, bodyWait - 1)
                        : remaining > 0
                                ? remaining : Math.max(0, bodyWait - 1);
                unit.setBattleNetAnimationTimer(hold);
                if (unit.chasing() && unit.pathLength() == 0) {
                    // Dest-arm leftover residual already put the thrower in
                    // weapon range. Leaving chase set used to dest-arm again
                    // on the same visit and walk off the stall.
                    unit.setChasing(false);
                    unit.setFighting(true);
                }
                if (World.BNE_PEND_TRACE) {
                    System.err.printf("JBNEATTACKHOLD cycle=%d unit=%d "
                                    + "approach+resume timer=%d freeScan=%d%n",
                            world.cycle, unit.id(), hold,
                            unit.battleNetRangedFreeScanHoldActive() ? 1 : 0);
                }
                world.battleNetAttackMarkers.add(unit);
                // The 63-count OP0 hold is still the live Attack action. Letting
                // the order body continue on this same visit can run its
                // completion arm and replace action 16 with Still (XHuman 10
                // archer 1473 on fixture 93), even though native remains Attack.
                return unit.battleNetStationaryAttack();
            }
        }
        // Human 13 ogre 1491 dest-arms leftover toward knight 1500, lands
        // that residual on 118,27, free-scans onto knight 1493, and native
        // parks Attack@643 with construction 3,2,1 then bodyWaitSum-1 (23).
        // Java consumed that start OP0 and walked into opcode 10 at fixture
        // 53 -- eight extra melee on 1493, twenty-three cycles early.
        // Construction timer 3 is not the start wait. Multi-step chase
        // residual-open already leaves the cursor past OP0, so those first
        // blows still land (Human 13 ogre 1510).
        if (tick.actionMarker()
                && attackStart >= 0
                && offset == attackStart
                && !pendingPathOneOp0Arrival
                && unit.battleNetAttackResumeFromMove()
                && unit.type() != null
                && !unit.type().firesMissile()
                && unit.canMove()) {
            Unit tgt = unit.target();
            if (tgt != null && tgt.isAlive() && world.targets.inAttackRange(unit, tgt)
                    && !unit.isMoving()) {
                int bodySum = world.battleNetSequence.attackBodyWaitSum(attackStart);
                int hold = bodySum > 0 ? bodySum - 1 : 0;
                if (hold > 0) {
                    unit.setBattleNetAttackResumeFromMove(false);
                    unit.setBattleNetAttackOp0OutOfRange(false);
                    // The completed 3,2,1 constructor now owns an
                    // unbreakable OP0 body hold. Keep spatial free-scans out
                    // until timer one; XHuman 4 grunt 1505 otherwise replaces
                    // its live footman at fixture 242 instead of 250 and
                    // carries FUN_004234b0's due draw one visit late.
                    unit.setBattleNetAttackResumeHoldActive(true);
                    unit.setBattleNetSequenceOffset(attackStart);
                    unit.setBattleNetAnimationTimer(hold);
                    if (unit.chasing() && unit.pathLength() == 0) {
                        unit.setChasing(false);
                        unit.setFighting(true);
                    }
                    if (World.BNE_PEND_TRACE) {
                        System.err.printf("JBNEATTACKHOLD cycle=%d unit=%d "
                                        + "melee-resume bodySum=%d hold=%d%n",
                                world.cycle, unit.id(), bodySum, hold);
                    }
                    // Mobile melee uses the same post-construction OP0 hold
                    // against units and buildings. XHuman 10 ogre 1538 wraps
                    // from an out-of-range footman to the adjacent guard tower,
                    // counts Attack 3,2,1, then remains at Attack@643/23 on
                    // fixture 138. Treating buildings like the ranged direct-
                    // approach exception walked straight into OP10 and damaged
                    // the tower seven visits before retail.
                    world.battleNetAttackMarkers.add(unit);
                    return false;
                }
            }
        }
        if (pendingPathOneOp0Arrival) {
            // A first-arrival melee keeps its final cached heading through
            // Attack construction, then parks that heading at OP0 without
            // walking it. This is not the damaged/resume body hold used by an
            // already committed swing. XHuman 10 grunt 1477 is Attack-start
            // 3,2,1 with route index one at fixtures 87..89; fixture 90 writes
            // route index twenty, advances to 2540/1, and owns caller
            // 0x4234CD's synchronized draw.
            unit.clearPath();
            unit.setRouteSpent(false);
            unit.setChasing(false);
            unit.setFighting(true);
            unit.setBattleNetAttackOp0Damaged(false);
        }
        if (tick.actionMarker()) {
            Unit tgt = unit.target();
            boolean inRange = tgt != null && tgt.isAlive()
                    && world.targets.inAttackRange(unit, tgt);
            if (!inRange) {
                unit.setBattleNetAttackOp0OutOfRange(true);
            }
            unit.setBattleNetAttackResumeFromMove(false);
            // Leaving the hold stall for a real OP0 advance (or any other
            // action marker) drops the presentation suppress flag.
            unit.setBattleNetAttackResumeHoldActive(false);
            unit.setBattleNetRangedFreeScanHoldActive(false);
            unit.setBattleNetRangedFreeScanHoldPending(false);
            if (offset == attackStart && inRange
                    && unit.type() != null && unit.type().firesMissile()) {
                int bodyWait = world.battleNetSequence
                        .attackBodyWaitSum(attackStart);
                if (bodyWait > 0) {
                    unit.setBattleNetRangedAttackCadenceRemaining(bodyWait + 2);
                }
            }
        }
        unit.setBattleNetSequenceOffset(tick.offset());
        unit.setBattleNetAnimationTimer(tick.timer());
        // A quarry may leave range or enter Die during the committed swing
        // body. The last non-marker visit still belongs to Attack even when
        // Java's parallel presentation animation becomes breakable on that
        // visit. Native hands ownership to Move or Still only on the
        // following OP0 marker. XHuman 9 knight 1419 is the dying-target
        // witness: its target pointer clears on fixture 94, but Attack tail
        // 1945/1 remains visible through fixture 108 and becomes Still on 109.
        //
        // A live quarry may leave range during the committed swing body.
        // The last non-marker visit still belongs to Attack even when Java's
        // parallel presentation animation becomes breakable on that visit.
        // Native hands ownership to Move only on the following OP0 marker.
        // Human 8 attack-peasant 1513 therefore remains at 70,72 on fixture
        // 57 (Attack tail timer 1) and first-steps NE on fixture 58. Letting
        // stepAttack continue after the timer-2 -> timer-1 tick moved it at
        // 57, one visit before the sequence authorized a chase decision.
        boolean recoveryMarkerNext = false;
        if (!tick.actionMarker()
                && unit.fighting() && !unit.isMoving()
                && (!unit.battleNetStationaryAttack()
                        || sequenceTarget == null
                        || !sequenceTarget.isAlive())
                && !settledInRange
                && attackStart >= 0 && offset >= attackStart) {
            BattleNetSequence.Tick next = world.battleNetSequence.tick(
                    tick.offset(), tick.timer());
            recoveryMarkerNext = next.valid() && next.actionMarker();
        }
        // Keep dest-arm leftover construction on Attack start through 3,2,1
        // so stepAttack does not dest-arm on the same visits (Human 13 ogre
        // 1511 holds 120,26 at 115-117).
        if (unit.battleNetAttackWrapDestArmPending()
                && attackStart >= 0
                && unit.battleNetSequenceOffset() == attackStart
                && unit.battleNetAnimationTimer() >= 1
                && !tick.actionMarker()) {
            return true;
        }
        // Attack animation loop re-seed: after the first 0x4234b0 debit the
        // table-0x27 melee arm runs for its fixed 25-cycle period. Tick every
        // Attack-sequence visit, not only OP0 markers -- the arm is wall-clock
        // on the sealed Human 5 fixtures.
        if (unit.battleNetMeleeSyncRemaining() > 0) {
            world.tickBattleNetMeleeSyncLoop(unit);
        }
        if (tick.inlineActionMarker()) {
            unit.setBattleNetResidualEmptyRouteSettle(false);
            Unit meleeTarget = world.battleNetPendingMeleeHits.remove(unit);
            Unit currentMeleeTarget = unit.target();
            if (meleeTarget != null && meleeTarget != currentMeleeTarget
                    && currentMeleeTarget != null
                    && world.targets.validAttackTarget(
                            unit, currentMeleeTarget)
                    && !unit.isMoving()
                    && world.targets.inAttackRange(
                            unit, currentMeleeTarget)) {
                // Presentation can name the victim before a later OP0
                // replaces the attack goal. The pending entry still owns the
                // random debit, but OP10 resolves through the live COrder goal
                // when that replacement has completed its chase into range.
                // Human 13 ogre 1519 therefore spends its fixture-214 roll on
                // knight 1493, not the dying knight retained by presentation.
                // If the replacement remains out of range, the committed old
                // swing still resolves and consumes its debit; XHuman 10's
                // fixture-323 dying-grunt handoff proves that counter-boundary.
                meleeTarget = currentMeleeTarget;
            }
            Missile shot = world.battleNetPendingProjectileShots.remove(unit);
            if (shot != null) {
                long queued = world.battleNetPendingProjectileQueuedCycle
                        .getOrDefault(shot, -1L);
                world.logBattleNetPend("pend-remove-op10", unit, shot.target(), shot,
                        "inline-op10", queued);
            }
            if (meleeTarget != null) {
                if (meleeTarget.isAlive()
                        || meleeTarget.order() == Unit.Order.DYING) {
                    applyBattleNetSequenceMeleeDamage(unit, meleeTarget);
                }
            } else if (shot != null) {
                // Presentation may already have spent constructor draws;
                // OP10 always owns motion arming when the shot is still
                // pending. If draws were not taken early, take the full
                // constructor here. Clear residual-open once OP10 arms so
                // a later swing can mid-visit-collapse again.
                // Queued cycle stays until after prepare so flight-start
                // back-date can read the presentation debit cycle.
                unit.setBattleNetRangedResidualOpen(false);
                if (!shot.battleNetMotion()) {
                    world.prepareBattleNetProjectile(shot, true);
                }
            } else if (!unit.battleNetSequenceMeleeLanded()
                    && unit.type() != null
                    && !unit.type().firesMissile()) {
                // OP10 is the retail melee hit, whether or not Java's
                // separate presentation animation happened to call hit()
                // first.  The initial chase residual exposed this on Human
                // 13 ogre 1510, but repeated settled swings have the same
                // rule: its second and third OP10s land at fixtures 64 and
                // 89 with no fresh presentation callback. Restricting this
                // arm to the one-shot multi-leftover latch silently skipped
                // those blows and kept knight 1500 alive past fixture 97.
                unit.setBattleNetMultiLeftoverMelee(false);
                Unit sequenceVictim = unit.target();
                if (sequenceVictim != null
                        && (sequenceVictim.isAlive()
                                || sequenceVictim.order() == Unit.Order.DYING)
                        && !unit.isMoving()
                        && world.targets.inAttackRange(unit, sequenceVictim)) {
                    applyBattleNetSequenceMeleeDamage(unit, sequenceVictim);
                    // Block a later presentation hit for this same swing.
                    unit.setBattleNetSequenceMeleeLanded(true);
                } else {
                    world.battleNetInlineAttackMarkers.add(unit);
                }
            } else {
                // A repeated ranged sequence may reach OP10 before Java's
                // separate presentation animation calls hit(). Retail does
                // not wait for that renderer-side callback: it constructs the
                // projectile here. Launch from the sequence target and mark
                // the later visual callback as already paid.
                Unit sequenceVictim = unit.target();
                MissileType sequenceMissile = world.projectiles.missileFor(unit);
                if (sequenceMissile != null && !sequenceMissile.isNone()
                        && sequenceVictim != null
                        && (sequenceVictim.isAlive()
                                || sequenceVictim.order() == Unit.Order.DYING)
                        && !unit.isMoving()
                        && world.targets.inAttackRange(unit, sequenceVictim)) {
                    // Opcode ten completes the committed ranged body against
                    // its retained CUnitPtr even after that target has entered
                    // Die. Human 13's axethrower 1486 is the authenticated
                    // witness: wise-man 1496 is already DYING, but fixture
                    // 147 still rolls damage, spends both constructor jitter
                    // draws and creates the visible axe. Rejecting Die here
                    // left the attacker animating in ATTACK with no missile,
                    // then shifted every later asynchronous consumer.
                    Missile inlineShot = world.projectiles.launch(
                            unit, sequenceVictim, sequenceMissile);
                    world.logBattleNetPend("op10-without-presentation", unit,
                            sequenceVictim, inlineShot,
                            "authoritative-sequence-boundary", world.cycle);
                    if (unit.order() == Unit.Order.STAND_GROUND) {
                        // COrder_Still's standing swing reaches OP10 during
                        // the unit visit, but retail completes its projectile
                        // constructor after the unit table. It is still in
                        // the same cycle-end missile snapshot. Deferring only
                        // construction (not birth) preserves that ordering.
                        world.battleNetCycleEndProjectileArm.add(inlineShot);
                    } else {
                        world.prepareBattleNetProjectile(inlineShot, true);
                    }
                    world.battleNetSequenceProjectileFired.add(unit);
                } else {
                    world.battleNetInlineAttackMarkers.add(unit);
                }
            }
            if (shot != null) {
                world.battleNetPendingProjectileQueuedCycle.remove(shot);
            }
        }
        // Next Attack OP0 starts a new swing; clear sequence-owned melee.
        if (tick.actionMarker()) {
            unit.setBattleNetSequenceMeleeLanded(false);
            world.battleNetSequenceProjectileFired.remove(unit);
        }
        if (!tick.actionMarker()) {
            // A ranged loop-wrap retarget has installed a fresh 3,2,1 Attack
            // constructor. ResumeFromMove is its durable ownership token until
            // OP0 enters the 63-count hold; each quiet construction visit must
            // therefore return to the scheduler without letting the order body
            // finish action 16 to Still.
            boolean rangedRetargetConstruction = rangedOp0
                    && unit.battleNetAttackResumeFromMove()
                    && unit.battleNetRangedFreeScanHoldPending()
                    && unit.battleNetStationaryAttack()
                    && attackStart >= 0 && offset == attackStart;
            // Once that constructor enters its 63-count OP0 hold, the
            // pending/resume latches have been consumed but the hold itself
            // remains an unbreakable Attack action. Keep returning ownership
            // to the sequence on every quiet hold visit. Otherwise action 16
            // falls through to its out-of-range recovery body and becomes
            // Still immediately after recording timer 62: XHuman 10 archer
            // 1473 vanished from the fight at fixture 94 while native remains
            // Attack@2039 through the full 63..1 commitment.
            boolean rangedStationaryCommittedHold = rangedOp0
                    && unit.battleNetStationaryAttack()
                    && unit.battleNetAttackResumeHoldActive()
                    && attackStart >= 0 && offset == attackStart;
            return deferMeleeRetarget || chaseDecision || recoveryMarkerNext
                    || rangedRetargetConstruction
                    || rangedStationaryCommittedHold;
        }
        if (unit.battleNetPendingMeleeSyncRand()) {
            Unit target = unit.target();
            // Standing in-range with an exhausted chase route is an arrival
            // for SyncRand purposes: Human 13 knight 100 sat ATTACK at
            // 119,25 with chasing still set and pending SyncRand unpaid until
            // fixture 37 while native paid it at 36 (seed one draw short).
            // Dying targets are not arrivals -- see consumeBattleNetPending-
            // MeleeSyncRand (XHuman 10 grunt 105 vs dying footman 108).
            boolean chaseArrived = completedMeleeArrival
                    || (unit.chasing() && unit.pathLength() == 0
                            && !unit.routeSpent() && !unit.isMoving());
            if (target != null && target.isAlive()
                    && (!unit.chasing() || chaseArrived)
                    && !unit.isMoving()
                    && world.targets.inAttackRange(unit, target)) {
                unit.setBattleNetPendingMeleeSyncRand(false);
                world.debitBattleNetMeleeSyncRand(unit);
            }
        }
        world.battleNetAttackMarkers.add(unit);
        return false;
    }

    /**
     * Serves the native Move-refusal -> Attack-construction -> Move handoff.
     *
     * <p>A refused attack approach remains logically a chase, so the ordinary
     * attack-sequence gate intentionally ignores its out-of-range Attack
     * cursor. The refusal provenance supplies the narrower authority needed
     * to count construction without allowing target selection or movement on
     * those visits.</p>
     */
    boolean rearmBattleNetHardRefusalAttack(Unit unit) {
        return rearmBattleNetHardRefusalAttack(unit, false);
    }

    private boolean rearmBattleNetHardRefusalAttack(Unit unit,
            boolean retainedAttackPromotion) {
        int attackStart = world.idle.battleNetSequenceStart(unit,
                BattleNetSequence.ATTACK_ANIMATION);
        if (attackStart < 0) {
            return false;
        }
        // A cold retry reaches this boundary through active-order Still.
        // That dispatch owns one asynchronous draw (and its rare facing
        // change) before Attack construction begins. A replan-residual hold,
        // however, is native next_order=Attack already queued behind the Move
        // residual. Its settlement promotes that queued order directly and
        // never enters Still. Charging Still here stole the next land-idle
        // draw from Human 13's grunt 1525 and shifted critter 1404's wander
        // seven fixtures later. Keep the draw attached to the dispatch which
        // actually owns it, rather than to this shared Java rearm seam.
        // The one-step refusal seam above is also the sole constructor of a
        // direct-refusal recovery probe.  Its diagonal, non-zero-refusal arm
        // remains a queued Attack promotion for the whole bounded probe
        // generation, not merely for the first 3,2,1 constructor.  XHuman 12
        // slot 1495 settles SW at fixture 174 and refuses the following south
        // probes without any 0040AD58 calls; charging the repeated stage-six
        // rearms stole the guard-tower damage value at fixture 177.
        boolean retainedPaidOneStepDirectProbe =
                unit.battleNetDirectRefusalRecoveryProbe()
                && unit.battleNetRefusals() > 0
                && Direction.isDiagonal(unit.lastStepHeading());
        boolean queuedAttackPromotion = retainedAttackPromotion
                || retainedPaidOneStepDirectProbe
                || unit.battleNetChaseReplanResidualHold();
        if (!queuedAttackPromotion) {
            world.idle.advanceBattleNetActiveOrderIdleRandom(unit);
        }
        world.causalTrace.event(world.cycle,
                "combat.refusal-attack-rearm", unit.id(),
                "queued_attack_promotion", queuedAttackPromotion,
                "idle_draw", !queuedAttackPromotion,
                "collision", unit.battleNetCollisionCounter(),
                "refusals", unit.battleNetRefusals(),
                "stage", unit.battleNetAttackRefusalRecoveryStage(),
                "path_length", unit.pathLength(),
                "path_steps", unit.battleNetPathStepsTaken(),
                "route_spent", unit.routeSpent(),
                "chase_empty_replan",
                        unit.battleNetChaseEmptyRouteReplan(),
                "residual_idle_pending",
                        unit.battleNetResidualEmptyApproachIdlePending(),
                "direct_probe", unit.battleNetDirectRefusalRecoveryProbe(),
                "refusal_hold", unit.battleNetRefusalHold());
        // This Java seam also serves stage-four/stage-six retry callbacks
        // which do not all enter the same native function. The cold stage-zero
        // entrance is the proved 0x438410 active-order handoff: it calls
        // 0x450ad0 to park the old route and masks word[unit+0x1c] with 0x0fff
        // before Attack begins. Keep both Java refusal projections on that
        // native lifetime. XHuman 12 slot 1504 changes 0x40 -> 0x00 at fixture
        // 87; retaining four makes it a hard wall to slot 1476 at fixture 127.
        boolean freshActiveOrderHandoff =
                unit.battleNetAttackRefusalRecoveryStage() == 0
                && unit.pathLength() == 0
                && unit.battleNetChaseEmptyRouteReplan()
                && unit.battleNetCollisionCounter() > 0
                && unit.battleNetRefusals() > 0;
        if (freshActiveOrderHandoff) {
            unit.setBattleNetDirectRecoveryGeneration(
                    Math.max(unit.battleNetDirectRecoveryGeneration(),
                            unit.battleNetRefusals()));
            unit.setBattleNetCollisionCounter(0);
            unit.setBattleNetRefusals(0);
        }
        unit.setWaitCycles(0);
        unit.setBattleNetSequenceOffset(attackStart);
        unit.setBattleNetAnimationTimer(3);
        unit.setBattleNetAttackRefusalRecoveryStage(5);
        AnimationSet set = unit.type() == null
                ? null : unit.type().animationSet();
        Animation attack = set == null
                ? null : set.get(AnimationSet.State.ATTACK);
        if (attack != null && unit.animation().current() != attack) {
            unit.animation().switchTo(attack);
        }
        return true;
    }

    /**
     * Opens retail's literal two-band handoff after a harvesting quarry leaves.
     *
     * <p>The first committed stride of a three-byte moving-quarry route can
     * settle while its quarry is removed from the map. The remaining bytes are
     * not a route to redraw around the current formation. Native leaves the
     * cursor parked, finishes the current Move band, pays one complete new Move
     * band, counts Attack construction 3,2,1, and only then makes one fresh
     * compass decision toward the retained point. Human 8 attack-peasant 1513
     * is the authenticated witness: it holds (77,62) through fixture 275 and
     * takes one east step on 276.</p>
     */
    private boolean armBattleNetExpiredHarvestQuarryLadder(Unit unit) {
        Unit quarry = unit.target();
        int moveStart = world.battleNetSequence == null ? -1
                : world.idle.battleNetSequenceStart(
                        unit, BattleNetSequence.MOVE_ANIMATION);
        boolean twoBandResidual = unit.pathLength() == 2
                && unit.battleNetPathInitialLength() == 3
                && unit.battleNetPathStepsTaken() == 1
                && unit.offeredTarget() == null;
        boolean expiredHarvestQuarryFirstResidual =
                unit.battleNetAttackRefusalRecoveryStage() == 0
                && unit.battleNetOrderDelay() == 0
                && unit.chasing() && unit.stepDrained() && !unit.isMoving()
                && twoBandResidual
                && unit.battleNetCollisionCounter() == 1
                && unit.battleNetRefusals() == 0
                && unit.type() != null
                && unit.type().moveType() == UnitType.Movement.LAND
                && unit.type().maxAttackRange() <= 1
                && !World.battleNetRangedChaseUnit(unit)
                && quarry != null && quarry.type() != null
                && !quarry.type().building()
                && quarry.order() == Unit.Order.HARVEST
                && !world.targets.validAttackTarget(unit, quarry)
                && !quarry.isOnMap()
                && moveStart >= 0
                && onBattleNetChaseMoveBody(unit)
                && unit.battleNetAnimationTimer() == 1;
        if (!expiredHarvestQuarryFirstResidual) {
            return false;
        }
        unit.clearPath();
        unit.setRouteSpent(false);
        unit.setWaitCycles(0);
        unit.setBattleNetOrderDelay(2);
        unit.setBattleNetSequenceOffset(moveStart);
        unit.setBattleNetAnimationTimer(3);
        int firstTailStage = EXPIRED_QUARRY_FIRST_BAND_TAIL;
        unit.setBattleNetAttackRefusalRecoveryStage(firstTailStage);
        unit.setBattleNetChaseStepReady(false);
        world.causalTrace.event(world.cycle,
                "combat.expired-harvest-quarry-ladder", unit.id(),
                "target", quarry.id(),
                "goal_x", unit.pathGoalX(), "goal_y", unit.pathGoalY(),
                "stage", firstTailStage);
        return true;
    }

    /** Serves the two Move bands, Attack constructor, and one fresh decision. */
    private boolean stepBattleNetExpiredHarvestQuarryLadder(
            Unit unit, int stage) {
        if (stage == SURFACED_QUARRY_RETARGET_CONSTRUCTION) {
            return stepBattleNetSurfacedQuarryRetargetConstruction(unit);
        }
        if (stage == EXPIRED_QUARRY_REPLACEMENT_BODY_HOLD) {
            return stepBattleNetExpiredHarvestReplacementBodyHold(unit);
        }
        if (stage == EXPIRED_QUARRY_FRESH_ROUTE_CONSTRUCTION) {
            return stepBattleNetExpiredHarvestFreshRoute(unit);
        }
        if (!unit.chasing()
                || !(unit.order() == Unit.Order.ATTACK
                        || unit.order() == Unit.Order.ATTACK_MOVE)) {
            unit.setBattleNetAttackRefusalRecoveryStage(0);
            return false;
        }
        if (stage == EXPIRED_QUARRY_SINGLE_BAND_TAIL) {
            if (unit.battleNetOrderDelay() > 0) {
                return false;
            }
            int reactRange = Math.max(
                    unit.type().reactRange(world.isPerson(unit.player())),
                    Math.max(1, unit.type().maxAttackRange()));
            Unit replacement = world.targets.findBattleNetHostile(
                    unit, reactRange, null);
            int attackStart = world.idle.battleNetSequenceStart(
                    unit, BattleNetSequence.ATTACK_ANIMATION);
            if (replacement == null || attackStart < 0
                    || !world.targets.inAttackRange(unit, replacement)) {
                unit.setBattleNetAttackRefusalRecoveryStage(0);
                return false;
            }
            setAutoTarget(unit, replacement);
            unit.clearPath();
            unit.setRouteSpent(false);
            unit.setWaitCycles(0);
            int targetWidth = replacement.type() != null
                    && replacement.type().building()
                            ? Math.max(1, replacement.type().tileWidth()) : 1;
            int targetHeight = replacement.type() != null
                    && replacement.type().building()
                            ? Math.max(1, replacement.type().tileHeight()) : 1;
            unit.setPathGoal(
                    World.battleNetNearFootprintCoordinate(
                            unit.tileX(), replacement.tileX(), targetWidth),
                    World.battleNetNearFootprintCoordinate(
                            unit.tileY(), replacement.tileY(), targetHeight));
            unit.setBattleNetCollisionCounter(0);
            unit.setBattleNetRefusals(0);
            unit.setBattleNetRefusalHold(false);
            unit.setBattleNetChaseStepReady(false);
            unit.setChasing(false);
            unit.setFighting(true);
            unit.setBattleNetAttackResumeFromMove(true);
            unit.setBattleNetAttackOp0OutOfRange(true);
            unit.setBattleNetSequenceOffset(attackStart);
            unit.setBattleNetAnimationTimer(3);
            unit.setBattleNetAttackRefusalRecoveryStage(
                    EXPIRED_QUARRY_REPLACEMENT_BODY_HOLD);
            AnimationSet set = unit.type() == null
                    ? null : unit.type().animationSet();
            Animation attack = set == null ? null
                    : set.get(AnimationSet.State.ATTACK);
            if (attack != null && unit.animation().current() != attack) {
                unit.animation().switchTo(attack);
            }
            world.turnToTarget(unit, replacement, 0, 0);
            return true;
        }
        if (stage == EXPIRED_QUARRY_FIRST_BAND_TAIL) {
            if (unit.battleNetOrderDelay() > 0) {
                return false;
            }
            int moveStart = world.idle.battleNetSequenceStart(
                    unit, BattleNetSequence.MOVE_ANIMATION);
            if (moveStart < 0) {
                unit.setBattleNetAttackRefusalRecoveryStage(0);
                return false;
            }
            int reactRange = Math.max(
                    unit.type().reactRange(world.isPerson(unit.player())),
                    Math.max(1, unit.type().maxAttackRange()));
            Unit replacement = world.targets.findBattleNetHostile(
                    unit, reactRange, null);
            if (replacement != null) {
                setAutoTarget(unit, replacement);
                unit.setPathGoal(
                        replacement.tileX(), replacement.tileY());
                unit.setChasing(true);
            }
            unit.setBattleNetOrderDelay(14);
            unit.setBattleNetSequenceOffset(moveStart);
            unit.setBattleNetAnimationTimer(15);
            unit.setBattleNetAttackRefusalRecoveryStage(
                    EXPIRED_QUARRY_SECOND_BAND);
            return true;
        }
        if (stage == EXPIRED_QUARRY_SECOND_BAND) {
            if (unit.battleNetOrderDelay() > 0) {
                return false;
            }
            int attackStart = world.idle.battleNetSequenceStart(
                    unit, BattleNetSequence.ATTACK_ANIMATION);
            if (attackStart < 0) {
                unit.setBattleNetAttackRefusalRecoveryStage(0);
                return false;
            }
            unit.setBattleNetSequenceOffset(attackStart);
            unit.setBattleNetAnimationTimer(3);
            unit.setBattleNetAttackRefusalRecoveryStage(
                    EXPIRED_QUARRY_ATTACK_CONSTRUCTION);
            AnimationSet set = unit.type() == null
                    ? null : unit.type().animationSet();
            Animation attack = set == null ? null
                    : set.get(AnimationSet.State.ATTACK);
            if (attack != null && unit.animation().current() != attack) {
                unit.animation().switchTo(attack);
            }
            return true;
        }
        if (stage != EXPIRED_QUARRY_ATTACK_CONSTRUCTION) {
            unit.setBattleNetAttackRefusalRecoveryStage(0);
            return false;
        }
        int attackStart = world.idle.battleNetSequenceStart(
                unit, BattleNetSequence.ATTACK_ANIMATION);
        if (attackStart < 0
                || unit.battleNetSequenceOffset() != attackStart) {
            unit.setBattleNetAttackRefusalRecoveryStage(0);
            return false;
        }
        if (unit.battleNetAnimationTimer() > 1) {
            unit.setBattleNetAnimationTimer(
                    unit.battleNetAnimationTimer() - 1);
            return true;
        }
        int reactRange = Math.max(
                unit.type().reactRange(world.isPerson(unit.player())),
                Math.max(1, unit.type().maxAttackRange()));
        Unit replacement = world.targets.findBattleNetHostile(
                unit, reactRange, null);
        if (replacement != null && replacement != unit.target()) {
            setAutoTarget(unit, replacement);
        }
        Unit freshTarget = unit.target();
        if (freshTarget == null
                || !world.targets.validAttackTarget(unit, freshTarget)) {
            unit.setBattleNetAttackRefusalRecoveryStage(0);
            return false;
        }
        unit.clearPath();
        PathFinder.Result planned = world.planTowardsAfterRefusalBand(
                unit, freshTarget);
        if (planned != PathFinder.Result.FOUND || unit.pathLength() == 0) {
            unit.setBattleNetAttackRefusalRecoveryStage(0);
            return false;
        }
        int heading = unit.peekHeading();
        int moveStart = world.idle.battleNetSequenceStart(
                unit, BattleNetSequence.MOVE_ANIMATION);
        if (moveStart < 0) {
            unit.setBattleNetAttackRefusalRecoveryStage(0);
            return false;
        }
        int goalX = freshTarget.tileX();
        int goalY = freshTarget.tileY();
        unit.setPathGoal(goalX, goalY);
        unit.setRouteSpent(false);
        unit.setWaitCycles(0);
        unit.setBattleNetOrderDelay(0);
        unit.setBattleNetSequenceOffset(moveStart);
        unit.setBattleNetAnimationTimer(1);
        unit.setBattleNetChaseStepReady(true);
        AnimationSet set = unit.type() == null
                ? null : unit.type().animationSet();
        Animation move = set == null ? null : set.get(AnimationSet.State.MOVE);
        if (move != null && unit.animation().current() != move) {
            unit.animation().switchTo(move);
        }
        world.causalTrace.event(world.cycle,
                "path.expired-harvest-quarry-fresh-step", unit.id(),
                "target", freshTarget.id(), "heading", heading,
                "goal_x", goalX, "goal_y", goalY);
        stepMoveTowardsTarget(unit);
        unit.setBattleNetAttackRefusalRecoveryStage(0);
        unit.setBattleNetChaseStepReady(false);
        return true;
    }

    /**
     * Releases the temporary building target when its committed body hold ends.
     *
     * <p>The farm selected when a moving harvesting quarry enters its mine
     * owns the already-paid melee OP0 hold even though no blow is delivered.
     * At timer one retail performs a fresh spatial decision and accepts the
     * best mobile quarry even when it is outside melee range. Human 8
     * attack-peasant 1526 changes from farm 1540 to peasant 1533 on fixture
     * 277, opens Attack construction 3,2,1, then takes the new route's first
     * south-east heading on fixture 280.</p>
     */
    private boolean stepBattleNetExpiredHarvestReplacementBodyHold(Unit unit) {
        int attackStart = world.battleNetSequence == null ? -1
                : world.idle.battleNetSequenceStart(
                        unit, BattleNetSequence.ATTACK_ANIMATION);
        boolean completedBodyHold = unit.order() == Unit.Order.ATTACK
                && attackStart >= 0
                && unit.battleNetSequenceOffset() == attackStart
                && unit.battleNetAnimationTimer() == 1
                && unit.battleNetAttackResumeHoldActive()
                && unit.target() != null
                && unit.target().type() != null
                && unit.target().type().building();
        if (!completedBodyHold) {
            if (unit.order() != Unit.Order.ATTACK
                    || unit.target() == null
                    || attackStart < 0) {
                unit.setBattleNetAttackRefusalRecoveryStage(0);
            }
            return false;
        }
        int reactRange = Math.max(
                unit.type().reactRange(world.isPerson(unit.player())),
                Math.max(1, unit.type().maxAttackRange()));
        Unit replacement = world.targets.findBattleNetHostile(
                unit, reactRange, null);
        if (replacement == null || replacement == unit.target()
                || !replacement.isAlive()) {
            unit.setBattleNetAttackRefusalRecoveryStage(0);
            return false;
        }
        Unit previous = unit.target();
        setAutoTarget(unit, replacement);
        unit.setPathGoal(replacement.tileX(), replacement.tileY());
        unit.setBattleNetAttackResumeHoldActive(false);
        unit.setBattleNetStationaryRecoveryHeld(false);
        unit.setBattleNetSequenceOffset(attackStart);
        unit.setBattleNetAnimationTimer(3);
        unit.setBattleNetSequenceMeleeLanded(false);
        unit.setOfferedTarget(replacement);
        unit.setFighting(false);
        unit.setChasing(false);
        unit.setBattleNetAttackWrapDestArmPending(false);
        unit.setBattleNetAttackRefusalRecoveryStage(
                EXPIRED_QUARRY_FRESH_ROUTE_CONSTRUCTION);
        world.turnToTarget(unit, replacement, 0, 0);
        world.causalTrace.event(world.cycle,
                "combat.expired-harvest-body-hold-retarget", unit.id(),
                "from", previous.id(), "to", replacement.id());
        return true;
    }

    /** Spends the one fresh route decision after the replacement constructor. */
    private boolean stepBattleNetExpiredHarvestFreshRoute(Unit unit) {
        int attackStart = world.battleNetSequence == null ? -1
                : world.idle.battleNetSequenceStart(
                        unit, BattleNetSequence.ATTACK_ANIMATION);
        if (unit.order() != Unit.Order.ATTACK || unit.target() == null
                || attackStart < 0) {
            unit.setBattleNetAttackRefusalRecoveryStage(0);
            return false;
        }
        if (unit.battleNetSequenceOffset() != attackStart) {
            return false;
        }
        if (unit.battleNetAnimationTimer() > 1) {
            unit.setBattleNetAnimationTimer(
                    unit.battleNetAnimationTimer() - 1);
            return true;
        }
        Unit target = unit.target();
        unit.clearPath();
        int face = unit.heading();
        if (face < 0 || face >= Direction.COUNT) {
            face = Direction.fromDelta(
                    Integer.signum(target.tileX() - unit.tileX()),
                    Integer.signum(target.tileY() - unit.tileY()));
        }
        int clockwise = Math.floorMod(face + 1, Direction.COUNT);
        int counterclockwise = Math.floorMod(face - 1, Direction.COUNT);
        // Both native wall probes write into one route buffer at this
        // post-hold decision. Their opening faces remain on either side of
        // the target-facing compass byte: the stack is read backwards, so
        // this stores clockwise, face, counterclockwise in walking order.
        unit.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {counterclockwise, face, clockwise}));
        unit.setPathGoal(target.tileX(), target.tileY());
        int moveStart = world.idle.battleNetSequenceStart(
                unit, BattleNetSequence.MOVE_ANIMATION);
        if (moveStart < 0) {
            unit.setBattleNetAttackRefusalRecoveryStage(0);
            return false;
        }
        int heading = unit.peekHeading();
        unit.setChasing(true);
        unit.setFighting(false);
        unit.setRouteSpent(false);
        unit.setWaitCycles(0);
        unit.setBattleNetOrderDelay(0);
        unit.setBattleNetSequenceOffset(moveStart);
        unit.setBattleNetAnimationTimer(1);
        unit.setBattleNetChaseStepReady(true);
        AnimationSet set = unit.type() == null
                ? null : unit.type().animationSet();
        Animation move = set == null ? null : set.get(AnimationSet.State.MOVE);
        if (move != null && unit.animation().current() != move) {
            unit.animation().switchTo(move);
        }
        world.causalTrace.event(world.cycle,
                "path.expired-harvest-replacement-fresh-step", unit.id(),
                "target", target.id(), "heading", heading,
                "path_length", unit.pathLength());
        stepMoveTowardsTarget(unit);
        unit.setBattleNetAttackRefusalRecoveryStage(0);
        unit.setBattleNetChaseStepReady(false);
        return true;
    }

    /**
     * Finishes construction against a just-surfaced laden quarry before the
     * in-range replacement scan owns a new construction body.
     *
     * <p>The quarry can stop being a moving HARVEST unit while the pursuer's
     * final pixels are still draining. Its CUnit pointer nevertheless owns
     * the complete Attack 3,2,1 constructor. Only the following OP0 scans
     * the adjacent formation and installs a replacement. Human 8 slot 1505
     * keeps slot 1536 through fixtures 274..276, names slot 1533 on 277, pays
     * another 3,2,1, and reaches the committed body hold on 280.</p>
     */
    private boolean stepBattleNetSurfacedQuarryRetargetConstruction(
            Unit unit) {
        int attackStart = world.battleNetSequence == null ? -1
                : world.idle.battleNetSequenceStart(
                        unit, BattleNetSequence.ATTACK_ANIMATION);
        Unit surfaced = unit.target();
        if (unit.order() != Unit.Order.ATTACK || attackStart < 0
                || unit.battleNetSequenceOffset() != attackStart
                || surfaced == null || !surfaced.isAlive()) {
            unit.setBattleNetAttackRefusalRecoveryStage(0);
            return false;
        }
        if (unit.battleNetAnimationTimer() > 1) {
            unit.setBattleNetAnimationTimer(
                    unit.battleNetAnimationTimer() - 1);
            return true;
        }
        int reactRange = Math.max(
                unit.type().reactRange(world.isPerson(unit.player())),
                Math.max(1, unit.type().maxAttackRange()));
        Unit replacement = world.targets.findBattleNetHostile(
                unit, reactRange, null);
        if (replacement == null || replacement == surfaced
                || !replacement.isAlive()
                || !world.targets.inAttackRange(unit, replacement)) {
            unit.setBattleNetAttackRefusalRecoveryStage(0);
            return false;
        }
        setAutoTarget(unit, replacement);
        unit.clearPath();
        unit.setRouteSpent(false);
        unit.setChasing(false);
        unit.setFighting(true);
        unit.setBattleNetSequenceOffset(attackStart);
        unit.setBattleNetAnimationTimer(3);
        unit.setBattleNetSequenceMeleeLanded(false);
        // This second constructor is the replacement half of a paid chase
        // arrival. Its OP0 enters the normal melee bodyWaitSum-1 hold rather
        // than walking directly into wind-up.
        unit.setBattleNetAttackResumeFromMove(true);
        unit.setBattleNetAttackOp0OutOfRange(true);
        unit.setBattleNetAttackRefusalRecoveryStage(0);
        world.turnToTarget(unit, replacement, 0, 0);
        world.causalTrace.event(world.cycle,
                "combat.surfaced-quarry-retarget", unit.id(),
                "from", surfaced.id(), "to", replacement.id());
        return true;
    }


    private boolean stepBattleNetAttackRefusalRecovery(Unit unit) {
        int stage = unit.battleNetAttackRefusalRecoveryStage();
        if (stage == 0 || world.battleNetSequence == null) {
            return false;
        }
        if (World.BNE_PEND_TRACE) {
            System.err.printf("JBNEREFUSALRECOVERY cycle=%d unit=%d "
                            + "step stage=%d delay=%d seq=%d/%d chase=%d%n",
                    world.cycle, unit.id(), stage,
                    unit.battleNetOrderDelay(),
                    unit.battleNetSequenceOffset(),
                    unit.battleNetAnimationTimer(), unit.chasing() ? 1 : 0);
        }
        if (stage >= EXPIRED_QUARRY_FIRST_BAND_TAIL) {
            return stepBattleNetExpiredHarvestQuarryLadder(unit, stage);
        }
        if (!unit.chasing() || unit.target() == null
                || !(unit.order() == Unit.Order.ATTACK
                        || unit.order() == Unit.Order.ATTACK_MOVE)) {
            unit.setBattleNetAttackRefusalRecoveryStage(0);
            unit.setBattleNetDirectRefusalRecoveryProbe(false);
            unit.setBattleNetSaturatedNearRecoveryFullRoute(false);
            unit.setBattleNetDirectRefusalReplacementBand(false);
            unit.setBattleNetDirectRecoveryGeneration(0);
            return false;
        }
        // A hard-blocked chase retries immediately for seven visits, then
        // owns one complete Move timer band.  On its wake native clears the
        // refusal nibble and runs Attack construction 3,2,1.  Timer one hands
        // one route probe to Move; if that probe is still blocked the
        // movement side re-arms stage five without charging another band.
        // XHuman 4 grunt 1505 is the compact witness: Move 2482/1 and
        // collision 0x20..0x70 on fixtures 24..29, Move 2482/15 on 30,
        // Attack 2539/3 on 45, and repeated Attack probes until N frees on 54.
        if (stage == 4) {
            if (unit.battleNetOrderDelay() > 0) {
                return false;
            }
            // Keep the retained cardinal-probe marker through its complete
            // paid Move band so timer synchronization can pin the native
            // cursor. The wake consumes that ownership before Attack 3..1;
            // otherwise stage six would treat the later Move handoff as a
            // ninth naked probe instead of a fresh recovery callback.
            boolean retainedStageSixCardinalBand =
                    unit.battleNetStageSixCardinalProbePark();
            unit.setBattleNetStageSixCardinalProbePark(false);
            if (retainedStageSixCardinalBand) {
                unit.setBattleNetRefusalHold(false);
            }
            if (unit.pathLength() == BattleNetPathFinder.MAX_PATH
                    && unit.battleNetPathStepsTaken() == 0
                    && unit.battleNetDirectRefusalReplacementBand()) {
                // The paid band owns a retained replacement route. Retail's
                // wake parks its cursor and advances the collision generation
                // for one visit; spending the first heading here makes a
                // surrounded fighter slide out of formation.
                unit.clearPath();
                unit.setRouteSpent(false);
                unit.setBattleNetCollisionCounter(2);
                unit.setBattleNetDirectRefusalReplacementBand(false);
                unit.setBattleNetAttackRefusalRecoveryStage(6);
                int moveStart = world.idle.battleNetSequenceStart(unit,
                        BattleNetSequence.MOVE_ANIMATION);
                if (moveStart >= 0) {
                    unit.setBattleNetSequenceOffset(moveStart);
                    unit.setBattleNetAnimationTimer(1);
                    unit.setBattleNetChaseStepReady(false);
                }
                return true;
            }
            unit.setBattleNetDirectRefusalReplacementBand(false);
            // This saturated-wake path already reached native's explicit
            // refusal-clear arm before entering the Java rearm seam. Clear it
            // before the active-order idle callback to retain that ordering.
            unit.setBattleNetRefusals(0);
            unit.setBattleNetCollisionCounter(0);
            if (!rearmBattleNetHardRefusalAttack(unit)) {
                unit.setBattleNetAttackRefusalRecoveryStage(0);
                return false;
            }
            return true;
        }
        if (stage == 5) {
            int attackStart = world.idle.battleNetSequenceStart(unit,
                    BattleNetSequence.ATTACK_ANIMATION);
            if (attackStart < 0
                    || unit.battleNetSequenceOffset() != attackStart) {
                unit.setBattleNetAttackRefusalRecoveryStage(0);
                return false;
            }
            if (unit.battleNetAnimationTimer() > 1) {
                unit.setBattleNetAnimationTimer(
                        unit.battleNetAnimationTimer() - 1);
                return true;
            }
            Unit boxedTarget = unit.target();
            boolean boxedRoutelessMeleeProbe =
                    (unit.battleNetColdNoProgressRefusalLoop()
                            || (unit.battleNetAiBehavior() == 0
                                    && unit.offeredTarget() == boxedTarget))
                    && !unit.isMoving()
                    && unit.pathLength() == 0
                    && unit.battleNetChaseEmptyRouteReplan()
                    && boxedTarget != null && boxedTarget.isAlive()
                    && boxedTarget.type() != null
                    && !world.targets.inAttackRange(unit, boxedTarget)
                    && !World.battleNetRangedChaseUnit(unit)
                    && !world.movement
                            .battleNetHasStrictlyCloserFreeNeighbour(
                                    unit, boxedTarget);
            if (boxedRoutelessMeleeProbe) {
                // A cold Attack constructor does not grant its final
                // Move probe to an arbitrary free compass cell when every
                // such cell fails to close weapon distance. This remains true
                // XHuman 12 slots 1453, 1456 and 1457 are the authenticated
                // cold-construction witnesses at fixtures 242/245. A matching
                // standing offer is the same native cold-order provenance
                // after target publication has cleared the Java latch: Human
                // 8 slot 1526 keeps its offered harvesting quarry and pays
                // the next Attack constructor on fixture 300. Java's empty-
                // route scan accepted a non-progressing free square, breaking
                // both position and the asynchronous stream.
                return rearmBattleNetHardRefusalAttack(unit);
            }
            unit.setBattleNetColdNoProgressRefusalLoop(false);
            int moveStart = world.idle.battleNetSequenceStart(unit,
                    BattleNetSequence.MOVE_ANIMATION);
            if (moveStart < 0) {
                unit.setBattleNetAttackRefusalRecoveryStage(0);
                return false;
            }
            unit.setBattleNetSequenceOffset(moveStart);
            unit.setBattleNetAnimationTimer(1);
            unit.setBattleNetAttackRefusalRecoveryStage(6);
            int retainedRefusalHeading =
                    unit.battleNetParkedRefusalHeading();
            if (retainedRefusalHeading >= 0
                    && retainedRefusalHeading < Direction.COUNT) {
                // Refusal eight parks its route cursor beyond the native
                // twenty-byte buffer, but the refused byte remains stored.
                // Attack construction's final Move callback probes that byte
                // directly; it does not ask the pathfinder for a new compass
                // face. Java represents the parked buffer as an empty route,
                // so reconstruct its one readable probe for stage six.
                unit.clearPath();
                unit.setPath(new PathFinder.Path(
                        PathFinder.Result.FOUND,
                        new int[] {retainedRefusalHeading}));
                unit.setRouteSpent(false);
                unit.setBattleNetChaseEmptyRouteReplan(true);
            }
            if (unit.battleNetChaseReplanResidualHold()
                    && unit.pathLength() == 0
                    && unit.battleNetChaseEmptyRouteReplan()) {
                // A replacement ray which refused at the residual boundary
                // has already paid the active-order Attack constructor that
                // stage five just completed.  Do not let the generic blocked-
                // chase gate buy the same 3,2,1 a second time.  Retail parks
                // the empty cursor on this handoff, serves one Move-start
                // visit, then redraws and probes. Human 13 ogre 1519 is the
                // sealed crowded-line witness: Attack 643/3,2,1 on fixtures
                // 149..151, Move 586 on 152..153, and the open southeast
                // detour commits on 154. The duplicate constructor left the
                // live fighter visibly frozen through fixture 155.
                unit.setBattleNetChaseReplanResidualHold(false);
                unit.setBattleNetRetargetResidualRoutePark(true);
                unit.setBattleNetAttackRefusalRecoveryStage(0);
                unit.setBattleNetOrderDelay(1);
                return true;
            }
            return false;
        }
        if (stage == 6) {
            return false;
        }
        if (stage == 1) {
            if (unit.battleNetOrderDelay() > 0) {
                return false;
            }
            int attackStart = world.idle.battleNetSequenceStart(unit,
                    BattleNetSequence.ATTACK_ANIMATION);
            if (attackStart < 0) {
                unit.setBattleNetAttackRefusalRecoveryStage(0);
                return false;
            }
            unit.setBattleNetSequenceOffset(attackStart);
            unit.setBattleNetAnimationTimer(3);
            unit.setBattleNetAttackRefusalRecoveryStage(2);
            return true;
        }
        if (stage == 2) {
            int attackStart = world.idle.battleNetSequenceStart(unit,
                    BattleNetSequence.ATTACK_ANIMATION);
            if (attackStart < 0
                    || unit.battleNetSequenceOffset() != attackStart) {
                unit.setBattleNetAttackRefusalRecoveryStage(0);
                return false;
            }
            if (unit.battleNetAnimationTimer() > 1) {
                unit.setBattleNetAnimationTimer(
                        unit.battleNetAnimationTimer() - 1);
                return true;
            }
            int moveStart = world.idle.battleNetSequenceStart(unit,
                    BattleNetSequence.MOVE_ANIMATION);
            if (moveStart < 0) {
                unit.setBattleNetAttackRefusalRecoveryStage(0);
                return false;
            }
            unit.setBattleNetSequenceOffset(moveStart);
            unit.setBattleNetAnimationTimer(1);
            unit.setBattleNetAttackRefusalRecoveryStage(3);
        }
        return false;
    }


    /** Resolves an attack marker after the order has attempted its move/fire. */
    void finishBattleNetAttackSequenceMarker(Unit unit) {
        // An inline marker only spans this unit's current scheduler call.
        // If its order did not fire, a later Java animation frame must wait
        // for the next native marker rather than borrowing this one.
        world.battleNetInlineAttackMarkers.remove(unit);
        boolean hadMarker = world.battleNetAttackMarkers.remove(unit);
        if (World.TRACE_MOVING != null
                && unit.id() == World.TRACE_MOVING_ID) {
            System.err.printf("JATTACKMARKER cycle=%d unit=%d had=%d order=%s "
                            + "seq=%d/%d chase=%d path=%d moving=%d wait=%d "
                            + "delay=%d blocked=%d replan-hold=%d stage=%d%n",
                    world.cycle, unit.id(), hadMarker ? 1 : 0, unit.order(),
                    unit.battleNetSequenceOffset(),
                    unit.battleNetAnimationTimer(), unit.chasing() ? 1 : 0,
                    unit.pathLength(), unit.isMoving() ? 1 : 0,
                    unit.waitCycles(), unit.battleNetOrderDelay(),
                    unit.battleNetBlockedChaseAttackConstruction() ? 1 : 0,
                    unit.battleNetChaseReplanResidualHold() ? 1 : 0,
                    unit.battleNetAttackRefusalRecoveryStage());
        }
        if (!hadMarker) {
            return;
        }
        // The marker itself is not an idle draw. It becomes one only when the
        // out-of-range attack cannot start its chase and native action 12
        // falls through the waiting Still retry. XHuman 4's blocked
        // axethrowers take this arm (chasing with pathLength 0); Human 13's
        // open-path axethrower switches to animation three; XHuman 10 unit
        // 1496 has a live multi-step chase route and must not draw. A bare
        // chasing() guard is too broad for the blocked Still-retry cases.
        // Applying the draw to every Attack marker was two extra RNG calls
        // in Human 13 and moved its critters at cycle four; missing the
        // live-route arm left XHuman 10 one draw ahead by cycle 9.
        if (unit.order() != Unit.Order.ATTACK
                || (unit.chasing() && unit.pathLength() > 0)
                || unit.isMoving()
                || unit.waitCycles() <= 0) {
            return;
        }
        int type = PudUnitTypes.code(unit.type().ident());
        if (type != 4 && type != 5) {
            world.idle.advanceBattleNetActiveOrderIdleRandom(unit);
        }
        unit.setBattleNetSequenceOffset(world.idle.battleNetSequenceStart(unit,
                BattleNetSequence.ATTACK_ANIMATION));
        unit.setBattleNetAnimationTimer(3);
    }

    /**
     * Completes retail script.bin opcode ten for a melee swing.
     *
     * <p>A target that entered Die after the swing was committed is no longer
     * hittable, but BNE still calls {@code FUN_00418370} at opcode ten.  The
     * returned damage is discarded.  This matters because that helper owns an
     * asynchronous RNG draw: Human 13 has three independent late attackers on
     * dying knight 1500 (slots 1501, 1519 and 1507).  Cancelling those swings
     * reassigned every later damage roll in every fight on the map.</p>
     */
    private void applyBattleNetSequenceMeleeDamage(Unit attacker, Unit target) {
        int damage = rollBattleNetSequenceMeleeDamage(attacker, target);
        if (!target.isAlive()) {
            world.causalTrace.event(world.cycle, "combat.damage.discarded",
                    target.id(), "attacker", attacker.id(), "target", target.id(),
                    "damage", damage, "reason", "target-dying");
            return;
        }
        applyRolledDamage(attacker, target, damage, 1, null);
    }

    /** Takes one retail opcode-ten physical-damage ordinal without hit effects. */
    private int rollBattleNetSequenceMeleeDamage(Unit attacker, Unit target) {
        world.battleNetNativeMeleeDamage.add(attacker);
        return world.damageFor(attacker, target, 1, null);
    }
}
