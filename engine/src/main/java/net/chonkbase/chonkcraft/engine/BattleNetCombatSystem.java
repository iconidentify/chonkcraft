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

    private final World world;

    BattleNetCombatSystem(World world) {
        this.world = world;
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
        if (diagonalDistance <= currentDistance
                || cardinalDistance > currentDistance
                || !world.canEnter(unit, cardinalX, cardinalY)) {
            return;
        }
        unit.replacePeekHeading(cardinal);
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
     * Advances a unit that is fighting.
     *
     * <p>Out of range it walks closer; in range it runs its attack animation,
     * and the blow lands on the cycle the animation says it does rather than
     * the moment the order was given. That is what gives Warcraft II's combat
     * its wind-up.
     */
    void stepAttack(Unit unit) {
        // A completed fifteen-count Move refusal band can hand a still-blocked
        // chase through Attack construction 3,2,1 before Move owns the route
        // park. Keep that construction ticking even though the quarry remains
        // out of range; the ordinary attack-sequence gate correctly ignores
        // all other out-of-range chasers.
        if (unit.battleNetBlockedChaseAttackConstruction()) {
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
            unit.setBattleNetBlockedChaseAttackConstruction(false);
            unit.setBattleNetRetargetResidualRoutePark(true);
            int moveStart = world.battleNetSequence == null ? -1
                    : world.idle.battleNetSequenceStart(unit,
                            BattleNetSequence.MOVE_ANIMATION);
            if (moveStart >= 0) {
                unit.setBattleNetSequenceOffset(moveStart);
                unit.setBattleNetAnimationTimer(1);
            }
            AnimationSet set = unit.type() == null
                    ? null : unit.type().animationSet();
            Animation move = set == null
                    ? null : set.get(AnimationSet.State.MOVE);
            if (move != null && unit.animation().current() != move) {
                unit.animation().switchTo(move);
            }
        }
        // A lethal-splash help chase retains its commanded route while native
        // pays Attack construction 3,2,1, then hands ownership to automatic
        // retargeting on the timer-one visit. This is deliberately outside
        // stepBattleNetAttackSequence: the unit is still chasing an out-of-
        // range quarry, so the ordinary sequence gate correctly ignores it.
        if (unit.battleNetPersonHelpRetargetHandoff()) {
            int attackStart = world.battleNetSequence == null ? -1
                    : world.idle.battleNetSequenceStart(unit,
                            BattleNetSequence.ATTACK_ANIMATION);
            if (attackStart >= 0
                    && unit.battleNetSequenceOffset() == attackStart
                    && unit.battleNetAnimationTimer() > 1) {
                unit.setBattleNetAnimationTimer(
                        unit.battleNetAnimationTimer() - 1);
                if (unit.battleNetOrderDelay() > 0) {
                    unit.setBattleNetOrderDelay(
                            unit.battleNetOrderDelay() - 1);
                }
                return;
            }
            unit.setBattleNetPersonHelpRetargetHandoff(false);
            unit.setBattleNetOrderDelay(0);
        }
        if (unit.battleNetNavalPatrolAttackConstruction()) {
            if (unit.battleNetAnimationTimer() > 1) {
                unit.setBattleNetAnimationTimer(
                        unit.battleNetAnimationTimer() - 1);
                return;
            }
            unit.setBattleNetNavalPatrolAttackTimerOneReady(true);
        }
        if (stepBattleNetAttackRefusalRecovery(unit)) {
            return;
        }
        if (stepBattleNetAttackSequence(unit)) {
            return;
        }
        if (unit.battleNetOrderDelay() > 0) {
            world.movement.syncBattleNetAttackRefusalTimer(unit);
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
                if (!peekFree) {
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
                if (peekFree || freeProgress) {
                    unit.setBattleNetOrderDelay(0);
                    if (!peekFree && vacateHeading >= 0) {
                        // Install the progress heading so this visit can step
                        // (native 1503 E onto the cell the vacating axe left).
                        unit.clearPath();
                        unit.setPath(new PathFinder.Path(
                                PathFinder.Result.FOUND,
                                new int[] {vacateHeading}));
                        unit.setPathGoal(quarry.tileX(), quarry.tileY());
                        unit.setBattleNetCollisionCounter(0);
                        unit.setBattleNetChaseEmptyRouteReplan(false);
                        unit.setChasing(true);
                    } else if (!peekFree) {
                        unit.clearPath();
                        unit.setRouteSpent(false);
                        unit.setBattleNetCollisionCounter(0);
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
                unit.setBattleNetOrderDelay(unit.battleNetOrderDelay() - 1);
                return;
            }
        }
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
                && unit.offeredTarget() != null && unit.type().maxAttackRange() <= 1) {
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
                Unit candidate = world.targets.findBattleNetHostile(unit, reactRange, null);
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
        if (!unit.chasing() && unit.fighting()) {
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
        } else if (!unit.chasing() && unit.animation().unbreakable()
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
            }
            // After residual drain, a unit that has just settled in range can
            // pay table-0x27 this visit. Human 13 wise-man 1496 and grunt
            // 1507 both settle into Attack and debit at fixture 36; waiting
            // for the next top-of-stepAttack left Java one SyncRand short.
            world.consumeBattleNetPendingMeleeSyncRand(unit);
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
            boolean replanResidualHold = unit.battleNetChaseReplanResidualHold()
                    && unit.pathLength() > 0;
            // Empty-route free-detour residual: path is only the free heading
            // (or already spent), so ordinary replan residual hold (pathn>0)
            // never arms. Hold delay 2 when that free first step residual
            // settles; empty-route replan then same-cycle steps (XHuman 12
            // grunt 1507 E@55 not @52).
            boolean freeDetourResidualHold =
                    unit.battleNetEmptyRouteFreeDetourHold();
            boolean inRangeReplanSettle = false;
            if (world.actionMoveWalked && !unit.isMoving()
                    && (replanResidualHold || freeDetourResidualHold)
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
                    unit.setBattleNetChaseReplanResidualHold(false);
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
                    unit.setBattleNetEmptyRouteFreeDetourHold(false);
                    unit.setBattleNetOrderDelay(2);
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
            if (world.actionMoveWalked && !unit.isMoving()
                    && unit.pathLength() == 1
                    && (inRangeReplanSettle
                            || (unit.battleNetPathStepsTaken() == 1
                                    && !unit.battleNetAttackWaitRefillResidual()))
                    && unit.type() != null
                    && unit.type().maxAttackRange() <= 1
                    && world.battleNetMoveAnimation(unit)
                    && world.battleNetSequence != null
                    && unit.target() != null
                    && unit.target().isAlive()
                    && !unit.battleNetMovingQuarryResidual()
                    && world.targets.inAttackRange(unit, unit.target())) {
                int leftoverAttackStart = world.idle.battleNetSequenceStart(unit,
                        BattleNetSequence.ATTACK_ANIMATION);
                if (leftoverAttackStart >= 0) {
                    boolean paidWrapConstruction =
                            unit.battleNetAttackWrapDestArmPending();
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
            boolean atChaseBoundary = (!unit.isMoving() && world.movement.atMoveBoundary(unit))
                    || unit.battleNetChaseStepReady();
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
                        && unit.pathLength() == 0
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
                    boolean replacementOutOfRange = replacement != null
                            && replacement != chased
                            && replacement.isAlive()
                            && !replacement.isDying()
                            && !world.targets.inAttackRange(
                                    unit, replacement);
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
                    // Open when remaining pathn==2 (XHuman 12 axe 1473/127 at
                    // 21,44 after one NE) or after two-or-more tile steps with
                    // any multi leftover (Human 13 axe 1495/105 at 115,28 after
                    // NE,E -- native seq 888). Single-step multi-leftover
                    // (Human 13 axe 1483/117 at 119,33 after one NE) stays cold
                    // so the approach+resume OP0 can seal timer 63.
                    // Melee multi leftover: open past OP0. Do not require the
                    // Move body cursor -- Human 13 wise-man 1496 residual-settles
                    // pathn 2 already on Attack@1922 (native opens mid-windup at
                    // 1923); requiring onChaseMoveBody left Java on OP0 and the
                    // swing finished three cycles late so presentation collapse
                    // had to paper over fixture 46.
                    int pathStepsAtSettle = unit.battleNetPathStepsTaken();
                    // A melee unit that consumed a multi-step route into
                    // range has already entered the attack program even when
                    // the final heading exhausted the route. Preserve that
                    // post-marker state instead of cold-starting Attack and
                    // charging a second wind-up.
                    boolean meleeResidualOpen = world.actionMoveWalked
                            && unit.type() != null
                            && unit.type().maxAttackRange() <= 1
                            && (unit.battleNetAttackWaitRefillResidual()
                                    || unit.battleNetMovingQuarryResidual()
                                    || (unit.pathLength() >= 2
                                    && (onBattleNetChaseMoveBody(unit)
                                            || unit.battleNetSequenceOffset()
                                                    == world.idle.battleNetSequenceStart(
                                                            unit,
                                                            BattleNetSequence.ATTACK_ANIMATION)))
                                    || (pathStepsAtSettle >= 2
                                            && onBattleNetChaseMoveBody(unit)));
                    boolean rangedResidualOpen = world.actionMoveWalked
                            && unit.pathLength() >= 2
                            && onBattleNetChaseMoveBody(unit)
                            && unit.type() != null
                            && unit.type().maxAttackRange() > 1
                            && (unit.pathLength() == 2
                                    || pathStepsAtSettle >= 2);
                    int pathnAtSettle = unit.pathLength();
                    unit.clearPath();
                    unit.setRouteSpent(false);
                    unit.setChasing(false);
                    unit.setFighting(true);
                    if (meleeResidualOpen) {
                        unit.setBattleNetResidualEmptyRouteSettle(false);
                        world.openBattleNetAttackAfterChaseResidual(unit, true);
                        unit.setBattleNetAttackWaitRefillResidual(false);
                        unit.setBattleNetMovingQuarryResidual(false);
                    } else if (rangedResidualOpen) {
                        unit.setBattleNetResidualEmptyRouteSettle(false);
                        world.openBattleNetAttackAfterChaseResidual(unit, false);
                    } else {
                        // Empty-route residual (pathn 0 after last heading spent):
                        // OP0 restarts cold and finishes late; presentation may
                        // collapse the pre-OP10 wait (Human 13 grunt 93). A
                        // leftover heading (pathn >= 1) keeps the full OP10 wait
                        // (Human 13 knight 100 / native 1500 fixture 50).
                        unit.setBattleNetResidualEmptyRouteSettle(pathnAtSettle == 0);
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
                boolean residualPathOneChangedTarget = false;
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
                                        + "mov=%d prev=%d bldg=%d "
                                        + "goal=%d,%d head=%d next=%d,%d "
                                        + "can=%d blockedBldg=%d freeH=%d "
                                        + "replanHold=%d delay=%d walked=%d%n",
                                world.cycle, unit.id(),
                                unit.tileX(), unit.tileY(),
                                unit.pathLength(),
                                unit.battleNetCollisionCounter(),
                                unit.stepDrained() ? 1 : 0,
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
                        // native's independent N fallback. Uncollided leftovers
                        // and building quarries keep the earlier fallback
                        // ordering (XHuman 10 grunt 1500, Human 13 ogre 1491,
                        // and XHuman 12 grunt 1517).
                        Unit pathn1Candidate = world.targets
                                .findBattleNetHostile(unit, reactRange, null);
                        boolean pathn1ChangedTarget = pathn1Candidate != null
                                && pathn1Candidate != pathn1Quarry
                                && pathn1Quarry.type() != null
                                && !pathn1Quarry.type().building()
                                && unit.battleNetCollisionCounter() > 0;
                        residualPathOneChangedTarget = pathn1ChangedTarget;
                        if (!pathn1FreeProgress && !pathn1ChangedTarget) {
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
                                world.planTowards(unit, pathn1Quarry, true);
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
                                // RI20 settle installs free heading and pays
                                // one quiet refuse so the step lands at
                                // fixture 42 (same-cycle free-compass stepped
                                // at 41; empty-route replan stepped at 43).
                                unit.clearPath();
                                unit.setPath(new PathFinder.Path(
                                        PathFinder.Result.FOUND,
                                        new int[] {freeHeading}));
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
                                // from their quarry, and native marks route
                                // index 20 and free-steps on the cycle after;
                                // replanning those too costs that case eleven
                                // cycles, 53 down to 42.
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
                    } else {
                        unit.clearPath();
                        unit.setPath(new PathFinder.Path(
                                PathFinder.Result.FOUND,
                                new int[] {residualBlockedFreeHeading}));
                        unit.setRouteSpent(false);
                        unit.setBattleNetCollisionCounter(0);
                        unit.setBattleNetChaseEmptyRouteReplan(false);
                        unit.setTarget(previous);
                        unit.setPathGoal(previous.tileX(), previous.tileY());
                        unit.setChasing(true);
                        chased = previous;
                        // No strict target upgrade: keep the independent
                        // free-compass heading and defer the ordinary scan.
                    }
                } else {
                // Behavior-one computer defenders retain an equal-scoring
                // incumbent. Their native order callback supplies the current
                // goal to 0x409ff0, unlike the null-seeded free scan used by
                // ordinary autonomous attacks. XHuman 12 grunt 1470 therefore
                // keeps tower 1464 at its first Move boundary (equal score),
                // then changes to the now-closer tower 1483 at the next one.
                // Null-seeding both visits changed it sixteen cycles early and
                // erased the retarget provenance which owns the residual hold.
                Unit scanIncumbent = unit.battleNetAiBehavior() == 1
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
                    if (splashHelpRetargetHandoff
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
                            && unit.battleNetPathStepsTaken() >= 2
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
                        setAutoTarget(unit, candidate);
                        chased = candidate;
                        unit.setRouteSpent(false);
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
                        if (keepPathn > 0
                                && !World.battleNetRangedChaseUnit(unit)) {
                            unit.setBattleNetChaseReplanResidualHold(true);
                        } else {
                            // Exhausted route retarget (keepPathn 0): same
                            // empty-route free-detour seam as the pathLength
                            // == 0 arm below (XHuman 12 grunt 1507).
                            unit.setBattleNetChaseEmptyRouteReplan(true);
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
                        if (residualPathOneChangedTarget) {
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
                        if (completedSettledRefusalBand) {
                            world.planTowardsAfterRefusalBand(unit, chased);
                        } else {
                            world.movement.moveTowards(unit, chased);
                            if (behaviorOneStrictBuildingHeading
                                    && keepHeading >= 0
                                    && unit.pathLength() > 0) {
                                unit.replacePeekHeading(keepHeading);
                            }
                        }
                    }
                } else if (unit.pathLength() == 0) {
                    boolean rangedCollidedResidualRefill =
                            world.actionMoveWalked
                            && unit.stepDrained()
                            && !unit.isMoving()
                            && unit.battleNetCollisionCounter() > 0
                            && World.battleNetRangedChaseUnit(unit)
                            && !unit.battleNetChaseEmptyRouteReplan();
                    // A collided route's final residual owns this visit. The
                    // following Move OP0 lays the refill; it does not share
                    // the residual-settle visit. XHuman 12 grunt 1503 drains
                    // E on fixture 55 with collision one and route index 20,
                    // then plans S,SW,SW,SE on 56. Combining both visits let
                    // Java route around the moving ally and step SE at 55.
                    if (world.actionMoveWalked
                            && unit.stepDrained()
                            && !unit.isMoving()
                            && unit.battleNetCollisionCounter() > 0
                            && !World.battleNetRangedChaseUnit(unit)
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
                            unit.battleNetChaseEmptyRouteReplan()
                            && unit.battleNetCollisionCounter() > 0;
                    unit.setRouteSpent(false);
                    unit.clearPath();
                    if (candidate != null) {
                        chased = candidate;
                        unit.setTarget(chased);
                    }
                    // Mark empty-route rebuild so a soft-cleared first step
                    // free-detours instead of cooperative-waiting fourteen
                    // (XHuman 12 grunt 1507 residual SE exhaust → replan).
                    unit.setBattleNetChaseEmptyRouteReplan(true);
                    world.planTowards(unit, chased, collidedResidualRefill);
                    if (rangedCollidedResidualRefill) {
                        preserveCollidedRangedWallHead(unit, chased);
                    }
                } else if (goalMoved) {
                    unit.setPathGoal(chased.tileX(), chased.tileY());
                }
                } // else free-scan / empty-route / goalMoved
            }
            if (unit.battleNetAttackRefusalRecoveryStage() == 3) {
                // The Attack timer-one boundary above returned ownership to
                // Move. Native uses this visit to free-scan and cache the new
                // route, but the first heading belongs to the next Execute.
                unit.setBattleNetAttackRefusalRecoveryStage(0);
                return;
            }
            boolean underWay = stepMoveTowardsTarget(unit);
            walked = true;
            // Mid-animation first, and nothing else is looked at: upstream's
            // MoveToTarget runs DoActionMove and then
            // `if (unit.Anim.Unbreakable || this->Finished) return;` before it
            // reaches any target check at all. A
            // unit whose step is in the air does not notice its quarry die.
            if (unit.animation().unbreakable()) {
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
                    return;
                }
                Unit aimed = unit.target();
                if (aimed != null && aimed != goal && world.targets.inAttackRange(unit, aimed)) {
                    unit.setChasing(false);
                }
                return;
            }
            // PF_REACHED changes only the order state and turns. It does not
            // fall through to CheckForTargetInRange/AutoSelectTarget, and it
            // does not start the swing until the following Execute. Keep the
            // target even when it began dying later in this cycle: upstream's
            // reached arm asks only whether the retained goal is in range.
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
                world.consumeBattleNetPendingMeleeSyncRand(unit);
                world.turnToTarget(unit, reached, 0, 0);
                return;
            }
            } finally {
                world.actionMoveWalked = false;
            }
        }

        Unit target = unit.target();
        if (!world.targets.validAttackTarget(unit, target)) {
            world.finishAttackOrder(unit);
            return;
        }

        // A target the unit chose for itself is reconsidered on a cadence; one
        // the player clicked on is not.
        if (!world.idle.autoSelectTarget(unit)) {
            world.finishAttackOrder(unit);
            return;
        }
        target = unit.target();
        if (!world.targets.validAttackTarget(unit, target)) {
            world.finishAttackOrder(unit);
            return;
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
     * Opens the three-visit Attack-four handoff after a full blocked Move band.
     */
    private boolean armBattleNetBlockedChaseAttackConstruction(Unit unit) {
        if (world.battleNetSequence == null || unit == null
                || unit.type() == null || !unit.chasing() || unit.isMoving()
                || !unit.stepDrained() || unit.pathLength() < 4
                || unit.battleNetCollisionCounter() <= 0
                || unit.battleNetRetargetResidualRoutePark()
                || Direction.isDiagonal(unit.peekHeading())
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
        int nextX = unit.tileX()
                + Direction.deltaX(unit.peekHeading()) * stride;
        int nextY = unit.tileY()
                + Direction.deltaY(unit.peekHeading()) * stride;
        if (world.canEnter(unit, nextX, nextY)) {
            return false;
        }
        Unit blocker = world.unitAt(nextX, nextY);
        // This is later than FUN_004379e0's ordinary cooperative wait. The
        // blocker may carry its own collision nibble by now (1495 carries one),
        // but it is visibly mid-stride away from the refused cell. Reusing the
        // stricter cooperative predicate therefore skips the native handoff.
        if (blocker == null || blocker == unit || !blocker.isOnMap()
                || blocker.isDying()
                || !world.isAllied(unit.player(), blocker.player())
                || !blocker.isMoving()
                || !world.battleNetMoveAnimation(blocker)) {
            return false;
        }
        int attackStart = world.idle.battleNetSequenceStart(unit,
                BattleNetSequence.ATTACK_ANIMATION);
        if (attackStart < 0) {
            return false;
        }
        unit.setBattleNetSequenceOffset(attackStart);
        unit.setBattleNetAnimationTimer(3);
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
                world.consumeBattleNetPendingMeleeSyncRand(unit);
            }
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
            // compact witness: presentation reached hit() while offset 658
            // still held timer 2; collapsing it made OP10 and every later
            // blow two visits early.  The only proved early-presentation
            // bridges are a just-settled empty route and building attacks.
            boolean allowMidProgramCollapse = attacker
                    .battleNetResidualEmptyRouteSettle() || buildingTarget;
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
            world.battleNetSpatialHelpReactPlusOne(attacker, target);
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
        if (previous != target) {
            unit.setBattleNetPersonSplashHelpAttack(false);
            unit.setBattleNetPersonHelpRetargetHandoff(false);
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
            System.err.printf("JBNERETARGET cycle=%d unit=%d type=%s "
                            + "prev=%d->%d ttype=%s at=%d,%d keepPath=%d "
                            + "chasing=%d pathLen=%d caller=%s%n",
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
                    world.causalCaller());
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
                boolean battleNetEmptyRouteRefusal = underWay
                        && world.battleNetSequence != null
                        && unit.chasing() && !unit.isMoving()
                        && unit.pathLength() == 0 && !unit.routeSpent()
                        && unit.waitCycles() > 0
                        && onBattleNetChaseMoveBody(unit);
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

    boolean stepBattleNetAttackSequence(Unit unit) {
        if (world.battleNetSequence == null || !unit.canMove()) {
            return false;
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
        boolean settledInRange = !unit.isMoving()
                && sequenceTarget != null
                && sequenceTarget.isAlive()
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
            // Human 13 axe 1483 re-enters Attack from the Move body then
            // stalls on the opening OP0 (timer 63). Mark the resume so the
            // next in-range OP0 can match that pre-fire hold.
            resumedFromMove = !paidWrapConstruction;
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
        boolean freeScanWindow = unit.battleNetAnimationTimer() > 0
                && (!rangedOp0 || unit.battleNetAnimationTimer() == 1
                        || resumedFromMove);
        if (attackStart >= 0
                && offset == attackStart
                && freeScanWindow
                && unit.canMove()
                && unit.type() != null
                && sequenceTarget != null
                && sequenceTarget.isAlive()) {
            int reactRange = Math.max(
                    unit.type().reactRange(world.isPerson(unit.player())),
                    Math.max(1, unit.type().maxAttackRange()));
            Unit candidate = world.targets.findBattleNetHostile(
                    unit, reactRange, null);
            if (candidate != null && candidate != sequenceTarget
                    && candidate.isAlive()
                    && world.targets.inAttackRange(unit, candidate)) {
                setAutoTarget(unit, candidate);
                world.turnToTarget(unit, candidate, 0, 0);
                // Construction-open retarget keeps the 3,2,1 countdown.
                // Re-arming timer 3 here is only for a true OP0 fire visit.
                boolean restartConstruction = !rangedOp0
                        || (unit.battleNetAnimationTimer() == 1
                                && !resumedFromMove);
                if (restartConstruction) {
                    unit.setBattleNetSequenceOffset(attackStart);
                    unit.setBattleNetAnimationTimer(3);
                }
                if (rangedOp0) {
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
            boolean scanWrap = rangedOp0
                    ? sequenceTarget != null && sequenceTarget.isAlive()
                    : true;
            if (scanWrap) {
                int reactRange = Math.max(
                        unit.type().reactRange(world.isPerson(unit.player())),
                        Math.max(1, unit.type().maxAttackRange()));
                Unit candidate = world.targets.findBattleNetHostile(
                        unit, reactRange, null);
                boolean inRange = candidate != null && candidate.isAlive()
                        && world.targets.inAttackRange(unit, candidate);
                boolean takeOutOfRange = quarryGone
                        && candidate != null && candidate.isAlive()
                        && !inRange
                        && unit.type().maxAttackRange() <= 1;
                if (candidate != null && candidate != sequenceTarget
                        && candidate.isAlive()
                        && (inRange || takeOutOfRange)) {
                    setAutoTarget(unit, candidate);
                    unit.setBattleNetSequenceOffset(attackStart);
                    unit.setBattleNetAnimationTimer(3);
                    world.turnToTarget(unit, candidate, 0, 0);
                    if (inRange) {
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
                        return !rangedOp0;
                    }
                    unit.setOfferedTarget(candidate);
                    unit.setFighting(false);
                    unit.setChasing(false);
                    unit.setBattleNetAttackWrapDestArmPending(true);
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
        if (tick.actionMarker()
                && attackStart >= 0
                && offset == attackStart
                && unit.battleNetAttackOp0Damaged()) {
            int bodySum = world.battleNetSequence.attackBodyWaitSum(attackStart);
            int hold = bodySum > 0 ? bodySum - 1 : 0;
            unit.setBattleNetAttackOp0Damaged(false);
            if (hold > 0) {
                unit.setBattleNetSequenceOffset(attackStart);
                unit.setBattleNetAnimationTimer(hold);
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
            if (tgt != null && tgt.isAlive() && world.targets.inAttackRange(unit, tgt)
                    && !unit.isMoving()
                    && (tgt.type() == null || !tgt.type().building())) {
                unit.setBattleNetAttackResumeFromMove(false);
                unit.setBattleNetAttackOp0OutOfRange(false);
                unit.setBattleNetAttackResumeHoldActive(true);
                if (unit.battleNetRangedFreeScanHoldPending()) {
                    unit.setBattleNetRangedFreeScanHoldActive(true);
                    unit.setBattleNetRangedFreeScanHoldPending(false);
                }
                unit.setBattleNetSequenceOffset(attackStart);
                unit.setBattleNetAnimationTimer(63);
                if (unit.chasing() && unit.pathLength() == 0) {
                    // Dest-arm leftover residual already put the thrower in
                    // weapon range. Leaving chase set used to dest-arm again
                    // on the same visit and walk off the stall.
                    unit.setChasing(false);
                    unit.setFighting(true);
                }
                if (World.BNE_PEND_TRACE) {
                    System.err.printf("JBNEATTACKHOLD cycle=%d unit=%d "
                                    + "approach+resume timer=63 freeScan=%d%n",
                            world.cycle, unit.id(),
                            unit.battleNetRangedFreeScanHoldActive() ? 1 : 0);
                }
                world.battleNetAttackMarkers.add(unit);
                return false;
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
                && unit.battleNetAttackResumeFromMove()
                && unit.type() != null
                && !unit.type().firesMissile()
                && unit.canMove()) {
            Unit tgt = unit.target();
            if (tgt != null && tgt.isAlive() && world.targets.inAttackRange(unit, tgt)
                    && !unit.isMoving()
                    && (tgt.type() == null || !tgt.type().building())) {
                int bodySum = world.battleNetSequence.attackBodyWaitSum(attackStart);
                int hold = bodySum > 0 ? bodySum - 1 : 0;
                if (hold > 0) {
                    unit.setBattleNetAttackResumeFromMove(false);
                    unit.setBattleNetAttackOp0OutOfRange(false);
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
                    world.battleNetAttackMarkers.add(unit);
                    return false;
                }
            }
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
        }
        unit.setBattleNetSequenceOffset(tick.offset());
        unit.setBattleNetAnimationTimer(tick.timer());
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
                && !unit.battleNetStationaryAttack()
                && !settledInRange
                && sequenceTarget != null && sequenceTarget.isAlive()
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
        // unit+0xb arm runs every twenty-five cycles while the unit stays on
        // its Attack sequence (Human 5 grunt 1531: fixture 6 then 31; chasers
        // 1528/1532: 22 then 47). Wood's 2660 work loop is the same period.
        // Tick every Attack-sequence visit, not only OP0 markers -- the arm
        // is wall-clock on the sealed fixtures.
        if (unit.battleNetMeleeSyncRemaining() > 0) {
            world.tickBattleNetMeleeSyncLoop(unit);
        }
        if (tick.inlineActionMarker()) {
            unit.setBattleNetResidualEmptyRouteSettle(false);
            Unit meleeTarget = world.battleNetPendingMeleeHits.remove(unit);
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
                        && sequenceVictim != null && sequenceVictim.isAlive()
                        && !unit.isMoving()
                        && world.targets.inAttackRange(unit, sequenceVictim)) {
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
            return chaseDecision || recoveryMarkerNext;
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
        if (!unit.chasing() || unit.target() == null
                || !(unit.order() == Unit.Order.ATTACK
                        || unit.order() == Unit.Order.ATTACK_MOVE)) {
            unit.setBattleNetAttackRefusalRecoveryStage(0);
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
        if (!world.battleNetAttackMarkers.remove(unit)) {
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
        world.battleNetNativeMeleeDamage.add(attacker);
        if (!target.isAlive()) {
            int discarded = world.damageFor(attacker, target, 1, null);
            world.causalTrace.event(world.cycle, "combat.damage.discarded",
                    target.id(), "attacker", attacker.id(), "target", target.id(),
                    "damage", discarded, "reason", "target-dying");
            return;
        }
        applyDamage(attacker, target, 1);
    }
}
