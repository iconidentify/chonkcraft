package net.chonkbase.chonkcraft.engine;

import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudUnitTypes;
import net.chonkbase.chonkcraft.engine.ai.AiPlayer;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationState;
import net.chonkbase.chonkcraft.engine.animation.BattleNetSequence;
import net.chonkbase.chonkcraft.engine.map.MapField;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.pathfinder.BattleNetPathFinder;
import net.chonkbase.chonkcraft.engine.pathfinder.PathFinder;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;

/**
 * Walking: the route, the step off it, and the pixels in between.
 *
 * <p>Implements {@code NextPathElement} and the retail BNE rules that decide when a
 * step may be taken: the even-grid double step large ships and flyers walk
 * on, the Move-sequence body that waits for its OP0 rather than for a tile
 * boundary, the leftover route a replan may not throw away, and the shove
 * ladder a blocked unit climbs before it moves a neighbour aside.
 *
 * <p>Everything here is timing. A step taken one visit early or one visit
 * late is the most common way this implementation has diverged from retail, so the
 * conditions that gate a step are worth having in one file.
 */
final class BattleNetMovementSystem {

    /**
     * How long a melee chaser sleeps on a step it could not take.
     *
     * <p>Native arms this in the unit record's offset-7 timer, and every one
     * of the fifteen 23s across the sealed captures belongs to a melee chaser
     * carrying route index 20 -- the refuse marker. The value is chosen by
     * unit type: 23 for type 0x01, 52 for 0x09, 44 for 0x08. Only the melee
     * one is ported, because only it has a case to answer for.</p>
     */
    private static final int BNE_MELEE_REFUSAL_HOLD = 23;




    private final World world;

    BattleNetMovementSystem(World world) {
        this.world = world;
    }


    /**
     * Orders a unit to walk to a tile.
     *
     * @return whether a route was found
     */
    boolean orderMove(Unit unit, int toX, int toY) {
        return orderMove(unit, toX, toY, 2);
    }

    /** Installs a move whose queued predecessor was popped this cycle. */
    boolean orderPoppedMove(Unit unit, int toX, int toY) {
        // HandleUnitAction erases the finished head, clears unit.Wait, and
        // executes the replacement immediately. Reusing the ordinary
        // autonomous-order entry point added its separate two-visit startup
        // delay after the old wait had correctly been cleared, making every
        // shifted move visibly hesitate.
        return orderMove(unit, toX, toY, 0);
    }

    /**
     * Quiet visits a player click waits behind the Still program that issued
     * it, plus the type's next cold Still OP0. Harvest used to skip this and
     * walk into the mine three cycles early.
     *
     * @return {@code {actionWait, queueWait}}
     */
    int[] playerCommandWaits(Unit unit) {
        int queueWait = -1;
        if (world.battleNetSequence != null
                && unit.battleNetSequenceOffset() >= 0) {
            queueWait = world.battleNetSequence.quietTicksUntilActionMarker(
                    unit.battleNetSequenceOffset(),
                    unit.battleNetAnimationTimer());
        }
        if (queueWait < 0) {
            queueWait = Math.max(0, unit.battleNetAnimationTimer() - 1);
        }
        int actionWait = 3;
        if (world.battleNetSequence != null) {
            int stillStart = world.idle.battleNetStillSequenceStart(unit);
            int scriptedWait = world.battleNetSequence
                    .quietTicksUntilActionMarker(stillStart, 3);
            if (scriptedWait >= 0) {
                actionWait = scriptedWait + 1;
            }
        }
        return new int[] {actionWait, queueWait};
    }

    int playerCommandDelay(Unit unit) {
        int[] waits = playerCommandWaits(unit);
        return waits[0] + waits[1];
    }

    /**
     * A click on occupied ground the hull already stands beside is not a
     * walk. Human 2's occupied dest used to flip to Move and settle at
     * cycle 12; native stayed Still on 8,35 from the issue cycle.
     */
    boolean alreadyTouchingBlockedDest(Unit unit, int toX, int toY) {
        if (unit == null || unit.type() == null
                || !world.map.contains(toX, toY)) {
            return false;
        }
        int width = Math.max(1, unit.type().tileWidth());
        int height = Math.max(1, unit.type().tileHeight());
        if (world.map.isFootprintFree(toX, toY, width, height,
                unit.movementMask(), unit.blockingFlags())) {
            return false;
        }
        int reach = unit.type().airUnit() ? 2 : 1;
        int destMinX = toX;
        int destMinY = toY;
        int destMaxX = toX;
        int destMaxY = toY;
        for (Unit occupant : world.unitsSnapshot()) {
            if (occupant == unit || !occupant.isAlive() || !occupant.isOnMap()
                    || occupant.type() == null) {
                continue;
            }
            int maxX = occupant.tileX() + Math.max(1, occupant.type().tileWidth()) - 1;
            int maxY = occupant.tileY() + Math.max(1, occupant.type().tileHeight()) - 1;
            if (toX >= occupant.tileX() && toX <= maxX
                    && toY >= occupant.tileY() && toY <= maxY) {
                destMinX = occupant.tileX();
                destMinY = occupant.tileY();
                destMaxX = maxX;
                destMaxY = maxY;
                break;
            }
        }
        int unitMaxX = unit.tileX() + width - 1;
        int unitMaxY = unit.tileY() + height - 1;
        int gapX = rectangleGap(unit.tileX(), unitMaxX, destMinX, destMaxX);
        int gapY = rectangleGap(unit.tileY(), unitMaxY, destMinY, destMaxY);
        return Math.max(gapX, gapY) <= reach;
    }

    private static int rectangleGap(int aMin, int aMax, int bMin, int bMax) {
        if (aMax < bMin) {
            return bMin - aMax;
        }
        if (bMax < aMin) {
            return aMin - bMax;
        }
        return 0;
    }

    /** Applies a serialized player/network move at the retail command boundary. */
    boolean orderCommandMove(Unit unit, int toX, int toY) {
        Unit.Order before = unit.currentAction();
        if (alreadyTouchingBlockedDest(unit, toX, toY)) {
            return true;
        }
        int[] waits = playerCommandWaits(unit);
        int actionWait = waits[0];
        int queueWait = waits[1];
        // Player/network clicks into forest are stored as the first tree on
        // the BNE line. Orc 1 peon 1594 commanded to 30,18 keeps 28,18.
        // Autonomous AI walks stay on orderMove so idle campaigns do not
        // inherit a click projection they never issued.
        if (world.map.contains(toX, toY)
                && !unit.type().airUnit()
                && !world.battleNetTerrainPassable(unit, toX, toY)) {
            int[] projected = BattleNetPathFinder.firstBlockedToward(
                    unit.tileX(), unit.tileY(), toX, toY,
                    (x, y) -> world.map.contains(x, y)
                            && world.battleNetTerrainPassable(unit, x, y));
            if (Math.max(Math.abs(unit.tileX() - projected[0]),
                    Math.abs(unit.tileY() - projected[1])) > 1) {
                toX = projected[0];
                toY = projected[1];
            }
        }
        boolean accepted = orderMove(unit, toX, toY,
                actionWait + queueWait);
        if (accepted) {
            unit.setBattleNetPlayerCommandMove(true);
            // A scout's native Patrol order is suspended by a point command
            // and restored when that Move completes (Human 12 zeppelin:
            // Still at fixture 48, Patrol again at 51). Patrol owns its two
            // endpoints separately, so the ordinary saved-order slot is the
            // exact durable state needed here and survives save/load.
            if (before == Unit.Order.PATROL && unit.savedOrder() == null) {
                unit.setSavedOrder(Unit.Order.PATROL);
            }
            // ReleaseOrders installs the replacement but CurrentAction keeps
            // reporting the interrupted head until HandleUnitAction may pop
            // it. A breakable head pops in this same cycle; a ship still in
            // its committed animation does not (controlled sea-E: command 5,
            // Move becomes visible at 6 and the first tile commits at 9).
            unit.rememberActionBeforeQueued(before, actionWait);
        }
        return accepted;
    }

    private boolean orderMove(Unit unit, int toX, int toY, int initialDelay) {
        if (unit.type().building() || unit.type().speed() <= 0 || !world.map.contains(toX, toY)) {
            return false;
        }
        // The order is written, not tested. {@code CommandMove}
        // The game checks that the unit is one this
        // machine may command and then does nothing but store the order --
        // it never asks the pathfinder anything. Whether the destination can
        // be reached is the walk's business, and {@link #stepMoveOrder} does
        // what upstream does about it.
        //
        // This implementation used to run the search here and refuse on UNREACHABLE.
        // It reads better on a click -- an impossible order is turned down
        // rather than silently ignored -- and it is not what the game does.
        // Two hundred units ordered onto one square are two hundred accepted
        // orders upstream and were a hundred and seventy-nine here.
        world.construction.abandonPendingBuild(unit);
        // The presentation animation may have allocated a weapon sprite
        // before retail attack opcode ten. A move replaces that attack order,
        // so the pre-retail placeholder must go with it instead of remaining
        // at the old muzzle and waking up beside a later shot.
        world.projectiles.interruptPendingAttack(unit);
        // Retail tankers select the absolute even-anchor route. A legacy save
        // can contain a 2x2 tanker on an odd anchor, where a doubled step can
        // never repair parity and crosses an odd order point forever. Keep
        // BNE's doubled rule on its valid lattice, but make this one command a
        // single-lattice recovery. A later command from an even anchor
        // restores the tanker bit. Do not rewrite other large-mover flags:
        // transports have their own shore-approach predicate.
        if (unit.type().gathering().containsKey(UnitType.Resource.OIL)) {
            unit.setBattleNetDoubleStep(
                    ((unit.tileX() | unit.tileY()) & 1) == 0);
        }
        unit.setPathGoal(toX, toY);
        unit.setOrderTarget(toX, toY);
        unit.setMoveRange(0);
        unit.clearPath();
        unit.setBattleNetPlayerCommandMove(false);
        unit.setOrder(Unit.Order.MOVE);
        // BNE queues the new order behind the Still action which issued
        // it. The following three HandleUnitAction visits leave an external
        // command on its source tile; the next visit may commit the logical
        // step. The controlled retail command matrix proves this across all
        // ordinary ground and air headings (command at fixture cycle 5,
        // first tile commit at 8). Two was inferred from autonomous startup
        // orders, whose issuing Still visit has already paid one beat, and
        // made external commands walk a cycle early.
        unit.setBattleNetOrderDelay(initialDelay);
        return true;
    }


    /**
     * One cycle of a plain move order, laying a course when it has none.
     *
     * <p>{@code COrder_Move::Execute} calls {@code DoActionMove} every action,
     * and that asks for a route whenever the stored one has run out. An
     * impossible destination is not refused but *widened*: PF_UNREACHABLE
     * increments {@code this->Range}, so the
     * order settles for arriving within one square, then two, until the goal
     * covers ground the unit can stand on. It needs no cap, because once the
     * range reaches the distance to the unit's own square the search answers
     * PF_REACHED where it stands -- which is how a footman ordered onto water
     * walks to the shore and stops there instead of refusing to set off.
     */
    void stepMoveOrder(Unit unit) {
        if (unit.battleNetOrderDelay() > 0) {
            unit.setBattleNetOrderDelay(unit.battleNetOrderDelay() - 1);
            return;
        }
        if (world.battleNetStrideOddDestEvenStop(unit)) {
            // The pathfinder floors a stride-two goal. A leftover heading
            // then walks west or north past the odd click. Native parks on
            // the even neighbour facing that click -- but it keeps Move
            // and drains the stride residual first. Wiping pixels here
            // used to Still a balloon at fixture 10 while native held
            // Move through 28 and stood down at 29.
            unit.clearPath();
            if (unit.isMoving()) {
                walkPixels(unit);
                if (unit.isMoving()) {
                    return;
                }
            }
            finishBattleNetMoveAtTarget(unit);
            return;
        }
        // Saves written before the odd-anchor tanker repair can resume in
        // the middle of the bad command rather than pass through orderMove.
        // Once its owed pixels settle, a doubled path query snaps the odd
        // self-goal onto the neighbouring even anchor and sends the tanker
        // straight back across it forever. Recover the same single-lattice
        // command that orderMove now chooses. This is deliberately limited
        // to the oil tanker; other 2x2 movers keep their retail stride.
        if (unit.battleNetDoubleStep()
                && unit.type().gathering().containsKey(UnitType.Resource.OIL)
                && ((unit.tileX() | unit.tileY()) & 1) != 0) {
            unit.setBattleNetDoubleStep(false);
        }
        if (world.map.contains(unit.orderTargetX(), unit.orderTargetY())
                && !world.battleNetTerrainPassable( unit, unit.orderTargetX(), unit.orderTargetY())) {
            // Critter one-tile wanders onto rock (Orc 7 1512 → 121,106, Orc
            // 10 1525 → 49,48) or coast (Orc 12 1461 → 76,93, Orc 10 1513 →
            // 54,61). Rock keeps bare Still so first-constructor resume
            // timer-5 + stream burns still land. Coast must not early-refuse
            // here: that used to cancel burns for a first-wander coast goal
            // (Orc 10 1510 → 57,63) and desync reverse-walk neighbours such
            // as 1526. Coast falls through to pathfind / empty-FOUND Still.
            //
            // Later Still-loop critter wanders onto impassable ground must
            // also fall through. Native FUN_004376c0 promotes Still on the
            // empty route and runs the replacement Still handler in the same
            // visit; early-refusing here used to omit that draw and hand
            // XOrc 10 1427 / Human 12 1576 a later wander-band choice at
            // fixture cycle 14 (java Move while native stayed Still).
            boolean critter = "unit-critter".equals(unit.type().ident());
            boolean firstConstructorResume = critter
                    && unit.battleNetConstructorBurnAfterCycle() > 0
                    && unit.battleNetIdlePhase() < 2;
            // Ordinary player moves must reach the pathfinder below. It widens
            // Range until a ship aimed at land, or a soldier aimed at trees,
            // reaches the closest legal edge. This guard used to include every
            // non-critter and silently finish those acknowledged orders here.
            if (critter && firstConstructorResume
                    && !world.battleNetCritterCoastGoal(
                            unit.orderTargetX(), unit.orderTargetY())) {
                resetDisplacement(unit);
                unit.clearPath();
                world.finishOrder(unit);
                // This failure is discovered by the unit's current native action,
                // not by an external command queued behind it. The replacement
                // Still action is therefore visible at this cycle's checkpoint.
                unit.setActionBeforeQueued(null);
                return;
            }
        }
        // Not while the move animation is still running: upstream reaches
        // NextPathElement, and so anything that could end the order, only when
        // the unit is neither mid-step nor mid-animation

        if (!unit.isMoving() && !isStepping(unit) && unit.pathLength() == 0) {
            // A spent route whose final tile is the requested point is a
            // completed Move, not an intermediate empty route. Retail closes
            // the action on this visit (ground/air/sea command matrix), while
            // the generic PF_WAIT below is only paid before requesting the
            // next route buffer. Treating the terminal buffer as intermediate
            // left every commanded mover visibly on Move for ten extra ticks.
            if (!"unit-critter".equals(unit.type().ident())
                    && unit.routeSpent()
                    && battleNetCommandPointReached(unit)) {
                finishBattleNetMoveAtTarget(unit);
                return;
            }
            // A route whose last stored tile borders its occupied point goal
            // has not produced the ordinary empty-buffer PF_WAIT. Native
            // goes straight through FUN_004379e0: XHuman 6 ogre 1495 drains
            // at 14,88 beside its moving peon goal at 13,89, increments the
            // refusal nibble on fixtures 87..94, then serves the eighth-
            // refusal fifteen-count before stepping SW on 109. Paying the
            // generic ten here postponed the first refusal until 98 and an
            // empty replan promoted Still after only five of them.
            if (battleNetOccupiedPointRefusal(unit)) {
                unit.setRouteSpent(false);
            } else if (spendTheEmptyRoute(unit)) {
                // Every PF_WAIT runs the blocker test, the count-born one
                // included: {@code COrder_Move::Execute}'s wait arm
                // The game does not care where the
                // wait came from. On (3)critter-attack the wanderer's last
                // wake before giving up is exactly this shape -- the route
                // spent by refusals, the blocker on the goal now standing --
                // and upstream ends the order there, wait wiped, where this
                // port slept ten more cycles first.
                // The occupant that counts is the one on the mover's own
                // layer: UnitOnMapTile filters by unit.Type->MoveType
                // so a land wanderer never finishes
                // for an air unit shadowing its goal. On level11h a
                // zeppelin hangs over the critter's wander square at 75,54:
                // upstream widens its blocked move past cycle 160 while a
                // port that saw the zeppelin as the blocker finished at 136
                // and drew a fresh wander no other engine drew.
                Unit blocker = world.blockerOnLayer(unit, unit.orderTargetX(),
                        unit.orderTargetY());
                if (blocker != null && blocker != unit
                        && unit.distanceTo(blocker) == 1
                        && (World.isEnemy(unit, blocker) || !blocker.walkHolding())) {
                    unit.setWaitCycles(0);
                    unit.clearPath();
                    world.finishOrder(unit);
                }
                return;
            }
            int toX = unit.orderTargetX();
            int toY = unit.orderTargetY();
            if (!world.map.contains(toX, toY)) {
                unit.setOrder(Unit.Order.STILL);
                return;
            }
            PathFinder.Path path = unit.moveRange() == 0
                    ? world.findBattleNetPointPath(unit, toX, toY)
                    : world.pathFinder.find(unit.tileX(), unit.tileY(),
                            new PathFinder.Goal(toX, toY, 1, 1, 0,
                                    unit.moveRange()), world.moverFor(unit));
            MapField goalField = world.map.fieldOrNull(toX, toY);
            if (unit.moveRange() == 0
                    && path.result() == PathFinder.Result.FOUND
                    && path.length() > 0
                    && goalField != null && goalField.isForest()) {
                // Harvest already prefers a forest wall-follow that lands on
                // a different skirt cell. Player moves into trees use the
                // same choice: the Orc 1 commanded peon stores NE,E onto
                // 27,17 beside tree 28,18 instead of the free east prefix.
                PathFinder.Path wall = world.findBattleNetPointPath(
                        unit, toX, toY, null, false, false, false);
                if (world.battleNetPreferForestWallOverFree(
                        path, wall, unit, toX, toY)) {
                    path = wall;
                }
            }
            switch (path.result()) {
                case REACHED -> {
                    resetDisplacement(unit);
                    unit.clearPath();
                    world.finishOrder(unit);
                    return;
                }
                case UNREACHABLE -> {
                    world.aiCanNotMove(unit, toX, toY, 1, 1);
                    resetDisplacement(unit);
                    unit.setMoveRange(unit.moveRange() + 1);
                    return;
                }
                default -> {
                    // An ordinary Move can also receive an empty FOUND route
                    // when its destination is occupied. Retail promotes the
                    // replacement Still action and executes its first idle
                    // marker in this same unit visit (Human 2 grunt 1579 at
                    // fixture cycle 12). Deferring the marker one world tick
                    // hands its async draw to the next unit and can make an
                    // unrelated critter wander several cycles early.
                    if (path.length() == 0
                            && !"unit-critter".equals(unit.type().ident())) {
                        battleNetEmptyRouteStillAndDispatch(unit);
                        return;
                    }
                    // Empty FOUND (all-0xff) from a critter one-tile wander.
                    // Native FUN_004376c0 promotes Still immediately on a
                    // 20-byte 0xff route (building XOrc 2 1580 → 29,21,
                    // occupied Human 3 1587 → 41,15, coast Orc 12 1461 →
                    // 76,93). The Still program may re-issue Move on the same
                    // or a later visit -- Orc 12 1461 rewrites 76,93→78,91
                    // as order 3 at fixture cycle 9; Orc 10 1513 Stills at
                    // cycle 8 and Moves at 9. Do not re-aim while staying on
                    // Move: that skips the Still cursor draws and desyncs the
                    // async stream that XOrc 2 / Human 3/13 need.
                    if (path.length() == 0
                            && "unit-critter".equals(unit.type().ident())) {
                        // Empty-route Still. Native FUN_004376c0 promotes
                        // Still then the Still executor may OP0 the same
                        // visit: Orc 12 1461 rewrites 76,93→78,91 as Move at
                        // the empty cycle; Orc 10 1510 stays Still. Capture
                        // constructor burns before the OP0 so a no-wander
                        // result restores them -- a plain cancel-burns
                        // Still-arm used to starve reverse-walk neighbour
                        // 1526 of its fixture-cycle-8 wander draw.
                        boolean firstConstructorResume =
                                unit.battleNetConstructorBurnAfterCycle() > 0
                                && unit.battleNetIdlePhase() < 2;
                        // Coast free-empty goal (Orc 10 57,63 / 54,61): native
                        // keeps three timer=1 Still visits after empty (c6-8
                        // for 1510) before the Still-loop WAIT 4. Rock
                        // first-constructor empties do not need that extra
                        // post-constructor OP0.
                        boolean coastFreeEmpty =
                                world.battleNetCritterCoastGoal(toX, toY);
                        if (firstConstructorResume) {
                            int phaseBefore = unit.battleNetIdlePhase();
                            int burnsBefore = unit.battleNetConstructorStreamBurns();
                            int burnAfter = unit.battleNetConstructorBurnAfterCycle();
                            int resumeOffset = unit.battleNetSequenceOffset();
                            int resumeTimer = unit.battleNetAnimationTimer();
                            resetDisplacement(unit);
                            unit.clearPath();
                            world.finishOrder(unit);
                            unit.setActionBeforeQueued(null);
                            unit.setOrderTarget(unit.tileX(), unit.tileY());
                            if (world.battleNetSequence != null) {
                                unit.setBattleNetSequenceOffset(
                                        world.idle.battleNetStillSequenceStart(unit));
                                unit.setBattleNetAnimationTimer(1);
                            }
                            // Preserve burns until orderMove's passable
                            // re-wander clears them (Orc 12 1461 → 78,91).
                            unit.setBattleNetConstructorStreamBurns(
                                    burnsBefore, burnAfter);
                            // OP0 choice stands in for this visit's burn draw
                            // when burns are due -- one async advance, not two
                            // (Orc 10 1510 free-empty must not double-draw
                            // before reverse-walk 1526).
                            world.battleNetEmptyRouteBurnSubstituted =
                                    burnsBefore > 0
                                    && world.cycle >= burnAfter;
                            world.idle.stepBattleNetIdle(unit);
                            world.battleNetEmptyRouteBurnSubstituted = false;
                            world.battleNetEmptyRouteIdled = unit;
                            if (unit.order() == Unit.Order.STILL) {
                                // No re-wander this visit (Orc 10 1510 stays
                                // Still; 1513 re-wanders next visit). Restore
                                // constructor cursor and arm timer 1 so the
                                // next visit can OP0 (native 1513 Still@8
                                // Move@9) while burns continue for 1526.
                                unit.setBattleNetIdlePhase(phaseBefore);
                                if (resumeOffset >= 0 && world.battleNetSequence != null) {
                                    unit.setBattleNetSequenceOffset(resumeOffset);
                                    unit.setBattleNetAnimationTimer(1);
                                }
                                // Coast free-empty: stretch the Still-loop WAIT
                                // that follows the post-empty constructor OP0
                                // by one quiet visit. Native 1510 reaches its
                                // first Still-loop OP0 at fixture 13; without
                                // the stretch Java fired at fixture 12 (choice
                                // 25 wander) and stole the draw 1513 needs to
                                // re-wander to 53,59. Do not insert an extra
                                // OP0 draw -- that used to desync 1526@8.
                                if (coastFreeEmpty) {
                                    unit.setBattleNetCoastEmptyExtraWait(true);
                                }
                            }
                        } else {
                            // Later empty route (building XOrc 2 1580, occupied
                            // Human 3 1587, free Orc 11 1597, later rock
                            // XOrc 10 1427 → 123,124). Native FUN_004376c0
                            // promotes Still then dispatches the new Still
                            // handler in the same visit without advancing the
                            // freshly selected Still animation. Running
                            // stepBattleNetIdle here used to burn the fresh
                            // cursor's next marker and omit/mis-order the
                            // replacement draw. Direct dispatch keeps the
                            // cursor at its first byte for the next visit and
                            // owns one idle marker now -- no occupied/free
                            // re-wander flags (those manufactured redraws are
                            // not native FUN_0040ad30).
                            battleNetCritterEmptyRouteStill(unit, 1, true);
                            int replacementPhase = unit.battleNetIdlePhase();
                            unit.setBattleNetIdlePhase(replacementPhase + 1);
                            world.idle.dispatchBattleNetIdleMarker(unit,
                                    PudUnitTypes.code(unit.type().ident()),
                                    replacementPhase);
                            world.battleNetEmptyRouteIdled = unit;
                        }
                        return;
                    }
                    unit.setPath(path);
                    unit.setPathGoal(toX, toY);
                }
            }
        }
        stepMove(unit);
    }

    private void battleNetEmptyRouteStillAndDispatch(Unit unit) {
        resetDisplacement(unit);
        unit.clearPath();
        unit.setBattleNetPlayerCommandMove(false);
        world.finishOrder(unit);
        unit.setActionBeforeQueued(null);
        unit.setOrderTarget(unit.tileX(), unit.tileY());
        if (world.battleNetSequence != null) {
            unit.setBattleNetSequenceOffset(
                    world.idle.battleNetStillSequenceStart(unit));
            unit.setBattleNetAnimationTimer(1);
        }
        int phase = unit.battleNetIdlePhase();
        unit.setBattleNetIdlePhase(phase + 1);
        world.idle.dispatchBattleNetIdleMarker(unit,
                PudUnitTypes.code(unit.type().ident()), phase);
        world.battleNetEmptyRouteIdled = unit;
    }

    private void finishBattleNetMoveAtTarget(Unit unit) {
        resetDisplacement(unit);
        unit.setRouteSpent(false);
        unit.setWaitCycles(0);
        unit.clearPath();
        unit.setBattleNetPlayerCommandMove(false);
        world.finishOrder(unit);
        unit.setActionBeforeQueued(null);
        if (world.battleNetSequence != null) {
            unit.setBattleNetSequenceOffset(
                    world.idle.battleNetStillSequenceStart(unit));
            unit.setBattleNetAnimationTimer(3);
        }
    }

    /**
     * Whether the stored point route has reached the native command goal.
     *
     * <p>A doubled mover cannot necessarily land on an occupied point while
     * staying on its two-tile lattice. Retail's point marker therefore also
     * accepts the occupied stride-neighbour selected by the wall follower.
     * Human 13 daemon 1556 is commanded to 86,4, stores NE,NE, and completes
     * at 86,2 as soon as the second residual drains. Requiring literal point
     * equality paid an invented ten-cycle pathfinder wait before Still.</p>
     */
    private boolean battleNetCommandPointReached(Unit unit) {
        if (unit.tileX() == unit.orderTargetX()
                && unit.tileY() == unit.orderTargetY()) {
            return true;
        }
        if (Math.max(Math.abs(unit.tileX() - unit.orderTargetX()),
                Math.abs(unit.tileY() - unit.orderTargetY())) <= 1
                && world.map.contains(unit.orderTargetX(), unit.orderTargetY())
                && !world.battleNetTerrainPassable(
                        unit, unit.orderTargetX(), unit.orderTargetY())) {
            return true;
        }
        if (!unit.battleNetPlayerCommandMove()
                || !unit.battleNetDoubleStep()
                || Math.max(Math.abs(unit.tileX() - unit.orderTargetX()),
                        Math.abs(unit.tileY() - unit.orderTargetY())) > 2) {
            return false;
        }
        Unit blocker = world.blockerOnLayer(unit, unit.orderTargetX(),
                unit.orderTargetY());
        return blocker != null && blocker != unit;
    }


    /**
     * Native empty-route completion for a critter one-tile wander.
     *
     * <p>FUN_004376c0 promotes Still on a 20-byte 0xff route. Building/
     * occupied empty-FOUND uses timer 1. Rock first-constructor empties keep
     * bare Still + burns (Orc 7). Coast free-empty enqueues only during the
     * unit pass; Still-arm, cancel burns, and OP0 run at end of pass.</p>
     */
    void battleNetCritterEmptyRouteStill(Unit unit, int stillTimer,
            boolean cancelConstructorBurns) {
        resetDisplacement(unit);
        unit.clearPath();
        world.finishOrder(unit);
        unit.setActionBeforeQueued(null);
        unit.setOrderTarget(unit.tileX(), unit.tileY());
        if (world.battleNetSequence != null) {
            unit.setBattleNetSequenceOffset(
                    world.idle.battleNetStillSequenceStart(unit));
            unit.setBattleNetAnimationTimer(Math.max(1, stillTimer));
            if (unit.battleNetIdlePhase() < 2) {
                unit.setBattleNetIdlePhase(2);
            }
        }
        if (cancelConstructorBurns) {
            unit.setBattleNetConstructorStreamBurns(0, 0);
        }
    }


    /**
     * Keeps a critter constructor marker on the action which owns it.
     *
     * <p>A wander selected by the first constructor marker changes the
     * current native action to Move. If that move starts, its handler selects
     * the movement animation and the adjacent Still marker is no longer an
     * idle-random call. Human 6 slot 1592 is the minimal witness: it remains
     * Move and must not draw again between corpus cycles one and two. If the
     * one-square move finishes without starting, however, BNE returns to
     * Still on that visit and the replacement action executes the constructor
     * marker; Orc 10 slot 1525 takes that branch. The same replacement-action
     * loop runs when a successfully started move eventually arrives. Human 3
     * slot 1589 completes its wander before cycle four and executes its fresh
     * Still marker in that same call; postponing it one tick omits an async
     * draw and hands slot 1585 the wrong wander choice on cycle five.</p>
     */
    void stepMoveOrderWithBattleNetCritter(Unit unit) {
        boolean critterMove = "unit-critter".equals(unit.type().ident());
        boolean resumeConstructor = critterMove
                && unit.battleNetConstructorBurnAfterCycle() > 0
                && unit.battleNetIdlePhase() < 2
                && unit.battleNetSequenceOffset() >= 0;
        world.battleNetEmptyRouteIdled = null;
        if (critterMove && unit.battleNetIdlePhase() == 2
                && !unit.isMoving() && unit.pathLength() == 0) {
            // The adjacent constructor marker counts down while the issued
            // Move is still queued. Once its delayed visits are spent, a free
            // goal starts the Move animation and retires this cursor.
            // Occupied walkable goals re-aim only while the order delay is
            // still counting (Human 13 1572: 3,4→5,5 during the queued Move).
            // Re-aiming again on the first free path visit used to burn that
            // visit without pathing, so Human 3 1587 stayed MOVE one cycle
            // after native empty-FOUND Still (occupied 41,15). Building
            // footprint goals (XOrc 2 1580 → hall) always fall through.
            if (unit.battleNetOrderDelay() > 0) {
                world.idle.stepBattleNetIdle(unit);
            }
            if (unit.battleNetIdlePhase() != 2) {
                return;
            }
        }
        // Impassable first wander: keep counting the resumed Still program
        // across MOVE/STILL so the second constructor marker is not lost.
        // After empty-route Still the order is already Still and the failed
        // goal was cleared -- only idle the constructor cursor; another
        // stepMove would REACHED-finish on the unit's own tile.
        if (resumeConstructor) {
            if (unit.order() == Unit.Order.MOVE) {
                stepMoveOrder(unit);
            }
            // Empty-route same-visit OP0 already idled inside stepMoveOrder.
            if (world.battleNetEmptyRouteIdled != unit) {
                world.idle.stepBattleNetIdle(unit);
            }
            world.battleNetEmptyRouteIdled = null;
            return;
        }
        int delayBefore = unit.battleNetOrderDelay();
        stepMoveOrder(unit);
        if (world.battleNetEmptyRouteIdled == unit) {
            world.battleNetEmptyRouteIdled = null;
            return;
        }
        if (!critterMove || delayBefore > 0) {
            return;
        }
        if (unit.order() == Unit.Order.STILL) {
            // Empty-wander abort pre-arms timer 3; do not collapse it to 1 or
            // the constructor marker re-issues Move in the same visit.
            if (unit.battleNetSequenceOffset() < 0
                    || unit.battleNetAnimationTimer() <= 0) {
                unit.setBattleNetSequenceOffset(
                        world.idle.battleNetStillSequenceStart(unit));
                unit.setBattleNetAnimationTimer(1);
            }
            // The animation program is advanced once a cycle, by whichever
            // hand holds the unit that cycle. A wander that ends onto the
            // empty-route pause armed that wait during this very visit, so the
            // visit already belongs to the wait and the order loop's own
            // advance would be the second one. Taking both put Orc 4's animal
            // on its next marker at internal 54 where retail reaches it at 55,
            // which showed up as the animal moving a cycle before retail's.
            if (unit.waitCycles() <= 0) {
                world.idle.stepBattleNetIdle(unit);
            }
            return;
        }
        if (unit.order() == Unit.Order.MOVE
                && (unit.isMoving() || unit.pathLength() > 0)) {
            // The successful Move selected animation three, so its old Still
            // cursor no longer has an adjacent marker to execute.
            unit.setBattleNetSequenceOffset(-1);
        }
    }


    /**
     * Soft-clear only allies whose live animation is Move ({@code 0x4500f0}).
     *
     * <p>{@code isMoving} (nonzero residual) is too broad: Attack-order units
     * mid-swing still have residual pixels while their sequence offset sits
     * in the Attack program. Soft-clearing those at XHuman 12 residual replan
     * opened a north-west free detour for grunt 1500 while native stores pure
     * east wall-follow with Attack-animation friends still blocking. An offset
     * in {@code [moveStart, attackStart)} is the Move program body.</p>
     *
     * <p>Native {@code 0x4501bc} also refuses soft-clear when
     * {@code unit[0x1d] & 0xf0} is set -- the collision counter packed in the
     * high nibble. Free-scan 1516 soft-cleared Attack walkers at 31,40 and
     * 34,40 that natively carry collision 1/2 there, so the second wall face
     * rejoined the goal skirt in six steps and preferred SW while native
     * walks fifty steps without a {@code 0x8000} join. Collision-elevated
     * allies stay solid; zero-collision Move-body allies still clear
     * (formation free crossings at fixture 5).</p>
     */
    boolean battleNetSoftClearMoveAlly(Unit candidate) {
        // Native's test at 0x004507b5 is the action-state byte at record
        // offset 8 reading 3 -- the Move body -- and its last test is
        // 0x1e & 0x4000, which is carried by 2,271 of the 2,524 records that
        // have it alongside byte 8 of 3 and a live route. This implementation asks
        // isMoving(), the pixel offset, and that is the last square between it
        // and native's route for XHuman 12's grunt 1507: the grunt on 30,40
        // carries byte 8 of 3 and 0x4000 with its pixels drained.
        //
        // Three proxies were measured and all cost more than they gain. The
        // Move animation alone, and the animation with a live route, both
        // stand the grunts on 22,59 and 23,59 aside -- they are on the
        // animation with byte 8 of 4 and no 0x4000, and native holds both --
        // and XHuman 12 then breaks at fixture 39 on grunt 1456 instead of 52.
        // The sequence-offset test below, which should separate state 3 from
        // state 4, does not fire for them.
        if (candidate == null || !candidate.isMoving()) {
            return false;
        }
        // Native 0x4501bc: high nibble of unit+0x1d nonzero keeps occupancy.
        // Measured over 14,616 paired unit-cycles of XHuman 12: the refusal
        // counter alone answers this square the way 0x00450766 does 98.36% of
        // the time and the pair 99.13%, and every one of the mistakes it
        // removes is a unit this implementation stood aside where native holds.
        if (candidate.battleNetCollisionCounter() > 0
                || candidate.battleNetRefusals() > 0) {
            return false;
        }
        // Ranged multi leftover residual nearly settled while already in
        // weapon range: native free-scan 1516 face-two nibble-refuses soft-
        // clear on axe 76@31,39 (pathn3 residual of SW land-in-range). Early
        // residual (large pixel debt) still soft-clears so free-scans before
        // the residual drains do not REG @40. Pixel debt uses max(|ox|,|oy|).
        if (candidate.pathLength() >= 3
                && World.battleNetRangedChaseUnit(candidate)
                && candidate.target() != null
                && candidate.target().isAlive()
                && (candidate.order() == Unit.Order.ATTACK
                        || candidate.order() == Unit.Order.ATTACK_MOVE
                        || candidate.chasing())
                && world.targets.inAttackRange(candidate,
                        candidate.target())) {
            int debt = Math.max(Math.abs(candidate.offsetX()),
                    Math.abs(candidate.offsetY()));
            if (debt > 0 && debt <= 8) {
                return false;
            }
        }
        if (world.battleNetSequence == null) {
            return true;
        }
        int move = world.idle.battleNetSequenceStart(candidate,
                BattleNetSequence.MOVE_ANIMATION);
        int attack = world.idle.battleNetSequenceStart(candidate,
                BattleNetSequence.ATTACK_ANIMATION);
        int offset = candidate.battleNetSequenceOffset();
        if (move < 0 || offset < 0) {
            return true;
        }
        if (attack > move) {
            return offset >= move && offset < attack;
        }
        // Attack table not after Move: fall back to isMoving for builders.
        return true;
    }


    /**
     * One cycle of walking towards a tile, under some other order.
     *
     * <p>Falls back to the target's neighbours when the square itself cannot
     * be entered. That is the normal case rather than the exception: a tree is
     * impassable and a mine is a building, so a worker never stands on what it
     * is working, it stands beside it.
     */
    void walkTowards(Unit worker, int tileX, int tileY) {
        if (worker.pathLength() == 0 && !worker.isMoving()) {
            // The spent route is served here too; see the building form
            // below for the measurement.
            if (spendTheEmptyRoute(worker)) {
                return;
            }
            PathFinder.Path path = world.findBattleNetPointPath(worker, tileX, tileY);
            if (path == null) {
                worker.setOrder(Unit.Order.STILL);
                world.idle.stepStill(worker);
                return;
            }
            // An AI assault uses Patrol as its travelling order. The compact
            // route builder may legitimately return an empty FOUND prefix
            // when neither bounded wall face rejoins the direct ray. A plain
            // Move finishes at that boundary; keeping Patrol alive instead
            // asks the same empty question forever. The AI's long-route
            // recovery supplies the next bounded segment, while player patrol
            // orders retain their original route semantics.
            if (path.result() == PathFinder.Result.FOUND && path.length() == 0
                    && worker.order() == Unit.Order.PATROL
                    && (worker.battleNetAiBehavior() == 2
                        || world.ais.containsKey(worker.player()))) {
                // Saves written before aiBehavior became durable can only
                // express an AI assault through this live Patrol order. Repair
                // that old representation as it is first exercised.
                worker.setBattleNetAiBehavior(2);
                if (!worker.hasBattleNetAiHome()) {
                    worker.setBattleNetAiHome(tileX, tileY);
                }
                PathFinder.Path recovered = world.findMovementPath(worker,
                        new PathFinder.Goal(tileX, tileY, 1, 1, 0, 1));
                if (recovered.result() == PathFinder.Result.FOUND
                        && recovered.length() > 0) {
                    path = recovered;
                } else {
                    worker.clearPath();
                    world.finishOrder(worker);
                    return;
                }
            }
            worker.setPath(path);
            // No path goal: this order does its own re-planning, every time
            // the path runs out, through findRouteToOrBeside -- which knows to
            // aim beside a tree or a mine rather than at it. The re-plan
            // inside stepMove is for a plain move order and would aim at the
            // tree itself, which no route can ever reach.
            worker.setPathGoal(-1, -1);
        }
        Unit.Order saved = worker.order();
        worker.setBattleNetBorrowedMoveForStep(true);
        // GiveOrder 27 walks under borrowed Move. Arming script.bin pace
        // only for that stride keeps player Move on its existing cadence
        // and stops a peon mending a hall from Still'ing at 38 while
        // native holds Repair through the last Move body to 56.
        worker.setBattleNetRepairStride(saved == Unit.Order.REPAIR);
        worker.setOrder(Unit.Order.MOVE);
        try {
            stepMove(worker);
        } finally {
            worker.setBattleNetBorrowedMoveForStep(false);
            worker.setBattleNetRepairStride(false);
        }
        if (worker.order() != Unit.Order.DYING) {
            worker.setOrder(saved);
        }
    }


    /**
     * Walks a resource order toward a unit goal.
     *
     * @return whether a wait left by the walk is a {@code PF_WAIT} and should
     *         climb {@code COrder_Resource::Range}; unreachable retries use a
     *         separate state ladder
     */
    boolean walkTowards(Unit worker, Unit building) {
        if (worker.order() == Unit.Order.HARVEST) {
            // DoActionMove increments PathFinderOutput::Cycles on every call,
            // including a cached step, a spent-route wait and an unreachable
            // answer. COrder_Resource consults this counter after the return
            // leg to decide whether the trip was long enough to seek a less
            // congested depot.
            worker.countResourceMoveCycle();
        }
        // Gold free-prefix forest re-aim once residual pixels settle. Firing
        // mid-MOVE drew SyncRand a dozen cycles early; waiting the residual
        // out lands the first chop on fixture cycle 24 with native. Do not
        // require !isStepping: under cold-commit the Move script is still
        // current after offsets hit nought, and that gate left Orc 7 peon
        // 1567 serving PF_WAIT instead of the claim draw at fixture 24.
        if (building != null
                && building.type().givesResource() == UnitType.Resource.GOLD
                && !worker.isMoving()
                && world.harvest.tryBattleNetGoldFreePrefixForestReaim(
                        worker,
                        worker.type().gathering().get(UnitType.Resource.GOLD),
                        building)) {
            return false;
        }
        if (worker.order() == Unit.Order.HARVEST
                && worker.resourceUnreachableTries() >= World.RESOURCE_UNREACHABLE_TRIES) {
            // SUB_UNREACHABLE_{RESOURCE,DEPOT}: the thirtieth failed ask first
            // serves its five-cycle wait, then the state itself gives up.
            worker.setOrderFinished(true);
            return false;
        }
        // After the last heading is spent (or a near-approach wrong leftover
        // was cleared) the worker still owes residual pixels for that tile
        // snap. stepMove's decide gate would spendTheEmptyRoute (PF_WAIT 10)
        // or stand the unit down on the same visit the drain finishes -- the
        // wood walk already drains-only here, and gold free-prefix leftovers
        // need the same (XHuman 9 peon 1550: clear at 109,24 then stage
        // action 25 NE once residual settles, without a ten-cycle wait).
        // When this visit's drain finishes the residual, fall through to the
        // empty-route stage so action 25 arms on the settle cycle rather
        // than one cycle late (fixture 23 vs native 22).
        if (worker.pathLength() == 0 && worker.isMoving()) {
            walkPixels(worker);
            if (worker.isMoving()) {
                return true;
            }
            // Residual just settled this visit: re-aim before any empty-route
            // stage so Orc 7 peon 1567's free-prefix claim draw lands on the
            // settle cycle (fixture 24) rather than after a PF_WAIT ten.
            if (building != null
                    && building.type().givesResource()
                            == UnitType.Resource.GOLD
                    && world.harvest.tryBattleNetGoldFreePrefixForestReaim(
                            worker,
                            worker.type().gathering()
                                    .get(UnitType.Resource.GOLD),
                            building)) {
                return false;
            }
        }
        // Free-prefix tip residual with a leftover heading still buffered.
        // Drain here so stepMove never arms PF_WAIT 10 with spent false
        // (XORc 12/6 peons: path=1 spent=0 while residual drains).
        //
        // Ally-blocked leftover at cheb 2 after residual settles:
        // - Short free-prefix (marked length under 4): discard and replan
        //   (XORc 12 three-step tip → NE at fixture 35 while ally stays on SE).
        // - Longer free-prefix: soft-hold the progressive leftover until free
        //   (XORc 6 four-step S,S,S,SE → SE at 58 once 1515 leaves 10,83).
        // Immediate replan while blocked took SW on XORc 6; PF_WAIT 10 froze
        // XORc 12.
        if (worker.battleNetGoldFreePrefix() && worker.pathLength() == 1
                && building != null
                && building.type().givesResource() == UnitType.Resource.GOLD
                && !worker.returningToDepot()) {
            if (worker.isMoving()) {
                walkPixels(worker);
                if (worker.isMoving()) {
                    return true;
                }
            }
            if (!worker.isMoving()) {
                int leftover = worker.peekHeading();
                int leftoverX = worker.tileX() + Direction.deltaX(leftover);
                int leftoverY = worker.tileY() + Direction.deltaY(leftover);
                int[] approach = world.battleNetApproachPoint(worker, building);
                int approachCheb = Math.max(
                        Math.abs(approach[0] - worker.tileX()),
                        Math.abs(approach[1] - worker.tileY()));
                if (approachCheb == 2
                        && !world.harvest.canEnterBattleNetResourceTarget(
                                worker, leftoverX, leftoverY)) {
                    if (worker.battleNetGoldFreePrefixLength() > 0
                            && worker.battleNetGoldFreePrefixLength() < 4) {
                        worker.clearPath();
                        worker.setRouteSpent(true);
                        worker.setBattleNetGoldFreePrefix(true);
                        worker.setBattleNetGoldFreePrefixLength(3);
                        return true;
                    }
                    return true;
                }
            }
        }
        if (worker.pathLength() == 0 && !worker.isMoving()) {
            boolean stageSpentBattleNetGold = false;
            boolean goldFreePrefixReplan = false;
            if (worker.order() == Unit.Order.HARVEST && !worker.returningToDepot()
                    && worker.routeSpent()
                    && building.type().givesResource() == UnitType.Resource.GOLD) {
                int[] approach = world.battleNetApproachPoint(worker, building);
                int approachCheb = Math.max(
                        Math.abs(approach[0] - worker.tileX()),
                        Math.abs(approach[1] - worker.tileY()));
                stageSpentBattleNetGold = approachCheb == 1;
                if (stageSpentBattleNetGold) {
                    // BNE's resource state sees the one-tile approach before
                    // the generic empty-route PF_WAIT. Orc 14 peasant steps
                    // E on fixture c22; XHuman 12 uses the centre-facing
                    // diagonal when the cardinal is blocked.
                    worker.setRouteSpent(false);
                } else if (approachCheb > 1
                        && worker.battleNetGoldFreePrefix()) {
                    // Free-prefix mid-journey: the clear ray tip drained and
                    // the peon is still short of the mine approach. Replan
                    // immediately -- PF_WAIT 10 is the full-segment break,
                    // not this short free tip (Orc 12 peon 1525: free SW,NW
                    // onto 85,41; native continues west without the ten).
                    // Keep residual corridor allies solid on this replan
                    // (Orc 5 1534 SW,SW,W vs pure-major W,W,SW).
                    worker.setRouteSpent(false);
                    worker.setBattleNetGoldFreePrefix(false);
                    stageSpentBattleNetGold = true;
                    goldFreePrefixReplan = true;
                }
            }
            // The spent route is served before the next ask, as every walk
            // pays it: NextPathElement answers PF_WAIT for the emptied
            // buffer and DoActionMove sleeps the ten -- level10h's tanker
            // steps at 715 and again at 742 upstream, the segment break in
            // the middle, where this implementation re-asked at once and sailed at
            // 731.
            if (!stageSpentBattleNetGold && spendTheEmptyRoute(worker)) {
                return true;
            }
            PathFinder.Path path;
            path = world.construction.findBattleNetBuildingPath(
                    worker, building, goldFreePrefixReplan);
            if (path.result() == PathFinder.Result.UNREACHABLE
                    && worker.order() == Unit.Order.HARVEST) {
                // DoActionMove reports the obstruction to the AI before the
                // resource order sees PF_UNREACHABLE. COrder_Resource then
                // advances one state and retries after five cycles, up to the
                // thirty states between SUB_MOVE_TO_DEPOT (70) and
                // SUB_UNREACHABLE_DEPOT (100). On level10o the tanker at
                // 7,21 reaches its first refused re-plan on cycle 859;
                // upstream keeps RESOURCE and draws for AiCanNotMove, while
                // an immediate stand-down diverged both order and seed.
                world.aiCanNotMove(worker, building.tileX(), building.tileY(),
                        building.type().tileWidth(), building.type().tileHeight());
                worker.setResourceUnreachableTries(
                        worker.resourceUnreachableTries() + 1);
                worker.setWaitCycles(5);
                return false;
            }
            if (path.result() != PathFinder.Result.FOUND) {
                worker.setOrder(Unit.Order.STILL);
                world.idle.stepStill(worker);
                return false;
            }
            worker.setPath(path);
            // No path goal, for the same reason as the tile form above: this
            // order re-plans for itself when the route runs out.
            worker.setPathGoal(-1, -1);
            if (worker.order() == Unit.Order.HARVEST && !worker.returningToDepot()
                    && building.type().givesResource() == UnitType.Resource.GOLD) {
                world.harvest.markBattleNetGoldPathKind(worker, building, path);
            }
            if (worker.order() == Unit.Order.HARVEST && !worker.returningToDepot()
                    && !world.harvest.atBattleNetResourceApproach(worker, building)) {
                int[] approach = world.battleNetApproachPoint(worker, building);
                int dx = approach[0] - worker.tileX();
                int dy = approach[1] - worker.tileY();
                int chebyshev = Math.max(Math.abs(dx), Math.abs(dy));
                if (chebyshev == 1) {
                    // Exactly one tile from the approach point: native stages
                    // action 25 then steps onto the footprint even when the
                    // order point is blocked. The ordinary pathfinder leaves
                    // the mine solid, so Bresenham's first step fails and the
                    // wall follower detours (XOrc 12 peasant 1396 at 32,75
                    // went north instead of north-east onto 33,74).
                    //
                    // When the delta is a pure cardinal into a blocked
                    // approach cell, prefer the diagonal toward the resource
                    // centre so Orc 12 peon 1511 at (58,47) with approach
                    // (58,46) steps north-east onto (59,46) like native,
                    // rather than pure north onto the SW corner.
                    int heading = Direction.fromDelta(
                            Integer.signum(dx), Integer.signum(dy));
                    boolean pureCardinal = dx == 0 || dy == 0;
                    boolean approachBlocked = !world.map.isFootprintFree(
                            approach[0], approach[1], 1, 1,
                            worker.movementMask(), worker.blockingFlags());
                    if (pureCardinal && approachBlocked) {
                        int centerX = building.tileX()
                                + Math.max(1, building.type().tileWidth()) / 2;
                        int centerY = building.tileY()
                                + Math.max(1, building.type().tileHeight()) / 2;
                        int altDx = Integer.signum(centerX - worker.tileX());
                        int altDy = Integer.signum(centerY - worker.tileY());
                        if (altDx != 0 || altDy != 0) {
                            heading = Direction.fromDelta(altDx, altDy);
                        }
                    }
                    worker.setPath(new PathFinder.Path(
                            PathFinder.Result.FOUND, new int[] {heading}));
                    worker.setBattleNetOrderDelay(2);
                    return true;
                }
                if (worker.distanceTo(building) <= 1) {
                    // BNE splits an already-adjacent arrival into action 25.
                    // Its three-call animation is visible in the raw state as
                    // timer 3,2,1 before the first logical step onto the exact
                    // footprint point.  The ready order's initial delay has
                    // already expired by this point; retain the freshly planned
                    // route and serve the remaining two calls here.
                    worker.setBattleNetOrderDelay(2);
                    return true;
                }
            }
        }
        Unit.Order saved = worker.order();
        worker.setBattleNetBorrowedMoveForStep(true);
        worker.setOrder(Unit.Order.MOVE);
        try {
            stepMove(worker);
        } finally {
            worker.setBattleNetBorrowedMoveForStep(false);
        }
        if (worker.order() != Unit.Order.DYING) {
            worker.setOrder(saved);
        }
        return true;
    }


    /**
     * Whether the unbreakable animation a unit is running is its move.
     *
     * <p>The other half of {@link #isSwinging}, and the same distinction: of
     * the three animations the shipped data declares unbreakable -- Attack,
     * Move and Death -- this is the one that means "this unit is part way
     * between two squares and may not do anything else yet".
     */
    boolean isStepping(Unit unit) {
        // script.bin residual pace parks ChonkCraft Move unbreakable while pixels
        // drain; once residual is gone the decide gate must see the step as
        // finished so critters Still without waiting for the ChonkCraft tail
        // (Human 4 1578 Still@50; isStepping-on-ChonkCraft held MOVE to ~60).
        if (unit.battleNetMovePaceOffset() < 0
                && !unit.walkHolding()
                && !unit.isMoving()
                && unit.stepDrained()) {
            return false;
        }
        if (unit.type() == null || unit.type().animationSet() == null
                || !unit.animation().unbreakable()) {
            return false;
        }
        Animation move = unit.type().animationSet().get(AnimationSet.State.MOVE);
        return move != null && unit.animation().current() == move;
    }


    /** Starts a route towards a target's tile. */
    boolean moveTowards(Unit unit, Unit target) {
        return world.planTowards(unit, target) == PathFinder.Result.FOUND;
    }


    /** The heading from one unit towards another. */
    static int headingTowards(Unit unit, Unit target) {
        int dx = Integer.signum(target.tileX() - unit.tileX());
        int dy = Integer.signum(target.tileY() - unit.tileY());
        return dx == 0 && dy == 0 ? unit.heading() : Direction.fromDelta(dx, dy);
    }


    /**
     * Lays a path to a square without changing the unit's order.
     *
     * <p>The order-issuing methods each set an order as well as a route;
     * patrol, explore and attack-ground need the route on its own, because
     * their order is the thing that keeps asking for one.
     */
    boolean route(Unit unit, int toX, int toY) {
        if (!world.map.contains(toX, toY)) {
            return false;
        }
        PathFinder.Path path = world.pathFinder.find(
                unit.tileX(), unit.tileY(), toX, toY, world.moverFor(unit));
        if (path.result() == PathFinder.Result.UNREACHABLE) {
            return false;
        }
        unit.setPath(path);
        return true;
    }


    /**
     * Shoves one of the units standing around a stuck one out of its way.
     *
     * <p>{@code AiMoveUnitInTheWay}. It looks at
     * every unit the manager holds, keeps the ones that are allied, of the
     * same movement kind, standing still and close enough to be in the way,
     * and draws a number for each of them to pick which of the eight
     * directions to start looking for a free square in. Then it draws once
     * more to choose which of the ones it found to actually move, and moves
     * exactly that one.
     *
     * <p>Both draws are the point. A port that shoved the same unit without
     * them, or shoved nobody, would part company with upstream's random
     * stream on the cycle it happened and never rejoin it.
     */
    void moveUnitInTheWay(Unit unit) {
        AiPlayer ai = world.ais.get(unit.player());
        if (ai == null || world.cycle <= ai.lastCanNotMoveCycle() + World.SHOVE_INTERVAL) {
            return;
        }
        List<Unit> movable = new ArrayList<>();
        List<int[]> destinations = new ArrayList<>();
        int width = Math.max(1, unit.type().tileWidth());
        for (Unit blocker : List.copyOf(world.units)) {
            if (!blocker.isAlive() || !blocker.isOnMap() || blocker.isDying()) {
                continue;
            }
            if (!blocker.canMove() || blocker.isMoving()) {
                continue;
            }
            if (blocker.player() != unit.player()
                    && !world.isAllied(blocker.player(), unit.player())) {
                continue;
            }
            if (blocker.type().moveType() != unit.type().moveType()) {
                continue;
            }
            if (blocker == unit) {
                continue;
            }
            // Close enough to be what is in the way. Upstream measures against
            // the stuck unit's own width, so a bigger unit reaches further.
            if (unit.distanceTo(blocker) >= width + 1) {
                continue;
            }
            int dir = world.syncRand() & 7;
            for (int tries = 0; tries < World.SHOVE_DIRS.length; tries++) {
                dir = (dir + 1) & 7;
                // Times the blocker's own width, so a two-by-two ship is sent
                // two squares rather than one and does not simply overlap
                // where it was.
                int step = Math.max(1, blocker.type().tileWidth());
                int x = blocker.tileX() + step * World.SHOVE_DIRS[dir][0];
                int y = blocker.tileY() + step * World.SHOVE_DIRS[dir][1];
                if (!world.map.contains(x, y)) {
                    continue;
                }
                if (x == unit.tileX() && y == unit.tileY()) {
                    continue;
                }
                // Anything at all on the square, which is upstream's
                // "UnitCache.size() > 0" rather than a question about what
                // could stand there.
                if (world.unitAt(x, y) != null) {
                    continue;
                }
                movable.add(blocker);
                destinations.add(new int[] {x, y});
                break;
            }
            if (movable.size() >= World.SHOVE_CANDIDATES) {
                break;
            }
        }
        if (movable.isEmpty()) {
            return;
        }
        int index = world.syncRand(movable.size());
        int[] to = destinations.get(index);
        Unit shoved = movable.get(index);
        if (System.getenv("CHONKCRAFT_TRACE_SHOVE") != null) {
            System.err.printf("JSHOVE cycle=%d unit=%d order=%s shoved=%d"
                            + " shovedOrder=%s to=%d,%d saved=%s%n",
                    world.cycle, unit.id(), unit.order(), shoved.id(), shoved.order(),
                    to[0], to[1], unit.savedOrder());
        }
        // CommandMove with EFlushMode::On, and flushing is not replacing.
        // {@code ReleaseOrders} shrinks the queue to {@code Orders[0]} and
        // marks that finished; the move is pushed in behind it
        // and only
        // {@code HandleUnitAction} pops to it only after Anim.Unbreakable has
        // let go. So the shoved unit
        // goes on reporting -- and doing -- what it was doing through a
        // committed step or swing, then starts walking on the next cycle.
        // And the stuck unit remembers what it was doing.
        // {@code AiMoveUnitInTheWay} clones *{@code unit}*'s current order --
        // the one that could not move, not the blocker's -- into
        // {@code unit.SavedOrder}, and only when the blocker was busy

        // IsIdle is not just CurrentAction == Still: it also requires exactly
        // one order. An autonomous attack queued by a still unit therefore
        // makes that blocker busy before the attack becomes current. In
        // level08h the blocker queues its attack earlier in cycle 761, then
        // grunt 21 shoves it and must clone its own attack order; looking only
        // at the blocker's displayed Still lost that clone and ended the
        // grunt's restored order seven cycles early.
        boolean shovedIsIdle = shoved.order() == Unit.Order.STILL
                && shoved.currentAction() == Unit.Order.STILL
                && shoved.pendingAttack() == null
                && !shoved.hasQueuedOrders()
                && shoved.pendingHarvestX() < 0
                && shoved.buildLatchedFrom() == null;
        if (!shovedIsIdle && unit.savedOrder() == null) {
            unit.setSavedOrder(unit.order());
            if (unit.order() == Unit.Order.ATTACK_MOVE) {
                unit.setSavedAttackMove(unit.attackMoveX(), unit.attackMoveY());
                unit.setSavedMoveRange(unit.moveRange());
                unit.setSavedAttackScanSleep(unit.attackScanSleep());
                unit.setSavedAttackMoveOpening(unit.attackMoveOpening());
            }
            if (System.getenv("CHONKCRAFT_TRACE_SAVEORDER") != null) {
                System.err.printf("JSAVE cycle=%d unit=%d source=shove saved=%s %d,%d%n",
                        world.cycle, unit.id(), unit.savedOrder(),
                        unit.savedAttackMoveX(), unit.savedAttackMoveY());
            }
        }
        shoved.rememberActionBeforeQueued(shoved.order());
        shoved.clearQueuedOrders();
        shoved.enqueueOrder(new Unit.QueuedOrder(Unit.QueuedOrderKind.MOVE,
                to[0], to[1], null, null, null));
        shoved.setQueuedReplacementPending(true);
        ai.setLastCanNotMoveCycle(world.cycle);
    }


    /** Promotes a campaign personality's startup regroup order. */
    void beginBattleNetPendingMove(Unit unit) {
        if (!unit.hasBattleNetPendingMove()) {
            return;
        }
        int x = unit.battleNetPendingMoveX();
        int y = unit.battleNetPendingMoveY();
        unit.clearBattleNetPendingMove();
        if (orderMove(unit, x, y)) {
            unit.setBattleNetOrderDelay(2);
        }
    }


    /**
     * The ten cycles a unit spends between the end of one route and the start
     * of the next, and whether this is one of them.
     *
     * <p>{@code NextPathElement} decrements {@code output.Length} at the top of
     * every call that reuses its cached route and only then reads a direction,
     * so the call after the last step of a route finds {@code Length} nought
     * and returns {@code result = output.Length} -- nought, which is
     * {@code PF_WAIT}, whatever is or is not standing in the way.
     * {@code DoActionMove} turns that into {@code unit.Wait = 10}, and only
     * the call ten cycles later takes the {@code output.Length <= 0} arm and
     * asks {@code NewPath} for a new route. So every stored route costs a
     * ten-cycle pause at its end, under every order that walks.
     *
     * <p>On {@code (3)critter-attack} that is cycle 51: an animal whose walk
     * and whose move animation both ended on 50 sits there with
     * {@code wait=10} while this implementation asked for a new route at once.
     *
     * @return whether the unit is spending that pause now
     */
    boolean spendTheEmptyRoute(Unit unit) {
        if (!unit.routeSpent()) {
            return false;
        }
        unit.setRouteSpent(false);
        // Attack empty-target routes pre-load a short wait in planTowards;
        // do not overwrite it with the ordinary ten-cycle move wait.
        // Live attack chases that emptied a short BNE route (XHuman 12 grunt
        // 1512's one-step SW) need the short replan, not the full ten: native
        // refills at fixture c19 while a ten-cycle pause pushed Java's second
        // path to world cycle 32.
        if (unit.waitCycles() <= 0) {
            boolean attackChase = unit.target() != null
                    && (unit.order() == Unit.Order.ATTACK
                            || unit.order() == Unit.Order.ATTACK_MOVE
                            || unit.order() == Unit.Order.STAND_GROUND
                            || unit.chasing());
            unit.setWaitCycles(attackChase ? 2 : 10);
        }
        // The consult that found the buffer empty read the phantom element
        // past it, and the drift until the next real step follows it.
        unit.setLastStepHeading(World.PHANTOM_HEADING);
        return true;
    }

    /** Whether a spent point route terminates in a live occupied step. */
    boolean battleNetOccupiedPointRefusal(Unit unit) {
        if (!unit.routeSpent()
                || unit.order() != Unit.Order.MOVE
                || unit.pathGoalX() < 0 || unit.pathGoalY() < 0
                || Math.max(Math.abs(unit.tileX() - unit.pathGoalX()),
                        Math.abs(unit.tileY() - unit.pathGoalY())) != 1) {
            return false;
        }
        Unit blocker = world.blockerOnLayer(
                unit, unit.pathGoalX(), unit.pathGoalY());
        return blocker != null && blocker != unit && blocker.isAlive();
    }


    void stepMove(Unit unit) {
        stepMove(unit, true);
    }

    /**
     * What retail does when a unit cannot take the step it planned.
     *
     * <p>Implements {@code fcn.004379e0} in Warcraft II Battle.net Edition
     * 2.02b, 671 bytes at {@code 0x004379e0}. The whole of retail's answer is
     * three bands off one sticky count:
     *
     * <ul>
     *   <li>refusals one to seven <b>park the route</b> -- {@code 0x00450ad0}
     *       writes 20 to the route cursor, one past the twenty heading bytes,
     *       so the unit is holding nothing -- and set the timer to 1, so it
     *       plans afresh on the next visit, walks into the same blocker, and
     *       refuses again;</li>
     *   <li>the eighth parks the route and leaves the timer at 15, so the
     *       unit stands still for fifteen cycles;</li>
     *   <li>the fifteenth clears the count and takes the next order.</li>
     * </ul>
     *
     * <p>This implementation used to do upstream LegacyEngine here instead --
     * {@code setWaitCycles(10)} and {@code popHeading()}, from
     * {@code NextPathElement} and the
     * {@code PF_WAIT} arm of {@code DoActionMove}. Every part of it disagrees: ten
     * cycles against fifteen, sleeping on the first refusal against sleeping
     * on the eighth, and spending one heading at a time against parking the
     * whole route at once. Retail's peon 1521 in Orc 12 stands on 86,41 for
     * twenty-two cycles with a friendly peon in the way; this implementation discarded
     * the refused west heading and walked south-west on the first visit.
     *
     * <p>Deviation: retail's give-up keeps the twenty heading bytes and moves
     * the cursor off the end, where this implementation clears the route outright.
     * Nothing measured reads a parked route -- {@code 0x0044fa20}, which asks
     * where a unit is going, indexes past the end and answers nothing -- so
     * the observable difference is bounded to any rule that would read the
     * abandoned headings back, and none is known.
     */
    private int battleNetRefuse(Unit unit) {
        int refusals = unit.battleNetRefusals() + 1;
        if (refusals >= 15) {
            // 0x00437a9c, the only instruction in the game that clears the
            // count, and 0x00453130 with 2 -- the unit's next order. The
            // timer is left at fifteen.
            unit.setBattleNetRefusals(0);
            unit.clearPath();
            unit.setWaitCycles(15);
            return refusals;
        }
        unit.setBattleNetRefusals(refusals);
        unit.clearPath();
        // 0x00437ab4: from the eighth refusal the handler gives the route up
        // and returns without putting the timer back to 1, so the fifteen
        // written at 0x00437a25 stands. Note what the capture says about that
        // sleep: retail's peon has its way clear again eleven cycles in and
        // does not take it, so it is not a wait for the blocker to leave.
        //
        // Fourteen, not fifteen, and for the reason E13 already found for the
        // order delay: retail's timer is read before it is spent, so the visit
        // that takes it from 1 to 0 is the visit that acts, and this implementation is
        // quiet on that visit as well. Orc 12's peon 1521 is the witness and
        // it is exact -- retail counts 15 down to 1 over cycles 31 to 45 and
        // steps onto 85,42 on 46, while a written fifteen here left the peon
        // quiet on 46 with its wait at nought and stepping on 47, one visit
        // late for the rest of the walk.
        unit.setWaitCycles(refusals >= 8 ? 14 : 0);
        return refusals;
    }

    /**
     * Transfers a refused attack approach from Attack OP0 to Move's native
     * refusal wait.
     *
     * <p>The order remains Attack, but the action program does not. XHuman 4
     * grunt 1505 and axethrower 1490 both finish Attack OP0, lay a route, and
     * find its first heading occupied. Retail records their type-specific Move
     * starts (2482 and 830) with timer 15 in that same cycle. Leaving Java on
     * Attack just past OP0 hid the refusal from the scheduler and accounted
     * for 75 of the 101 early mobile sequence mismatches fleet-wide.</p>
     */
    private void armBattleNetAttackRefusalMove(Unit unit) {
        if (world.battleNetSequence == null
                || unit == null
                || unit.target() == null
                // A unit already executing Move must keep its current cursor;
                // this transfer is specifically Attack OP0 yielding to Move.
                || executingBattleNetMoveProgram(unit)
                || !(unit.order() == Unit.Order.ATTACK
                        || unit.order() == Unit.Order.ATTACK_MOVE
                        || unit.chasing())) {
            return;
        }
        int moveStart = world.idle.battleNetSequenceStart(unit,
                BattleNetSequence.MOVE_ANIMATION);
        if (moveStart < 0) {
            return;
        }
        unit.setBattleNetSequenceOffset(moveStart);
        unit.setBattleNetAnimationTimer(15);
        unit.setBattleNetChaseStepReady(false);
    }

    /** Native action ownership is the sequence cursor, not the UI animation. */
    private boolean executingBattleNetMoveProgram(Unit unit) {
        int moveStart = world.idle.battleNetSequenceStart(unit,
                BattleNetSequence.MOVE_ANIMATION);
        int attackStart = world.idle.battleNetSequenceStart(unit,
                BattleNetSequence.ATTACK_ANIMATION);
        int cursor = unit.battleNetSequenceOffset();
        return moveStart >= 0 && cursor >= moveStart
                && (attackStart < 0 || cursor < attackStart);
    }

    /** Mirrors a newly transferred refusal in native's offset-7 Move timer. */
    void syncBattleNetAttackRefusalTimer(Unit unit) {
        if (world.battleNetSequence == null || unit == null
                || unit.battleNetOrderDelay() <= 0) {
            return;
        }
        int moveStart = world.idle.battleNetSequenceStart(unit,
                BattleNetSequence.MOVE_ANIMATION);
        if (moveStart >= 0 && unit.battleNetSequenceOffset() == moveStart) {
            unit.setBattleNetAnimationTimer(unit.battleNetOrderDelay());
        }
    }

    /**
     * @param replanOnExhaustion whether this may lay a fresh course to
     *     {@code pathGoal} when the current one is refused or runs out. True
     *     for a plain move order, whose goal really is that square. False when
     *     a chase is driving the walk: there the goal square is the target's
     *     own, which is occupied, and re-planning a move to it would undo the
     *     range-aware route the attack order laid and put the unit straight
     *     back into jogging on the spot.
     */
    /**
     * A melee leftover residual that already stands in weapon range, with
     * its last heading naming the quarry's occupied square, dest-arms into
     * Attack once leftover debt is at most a quarter-tile. The same 8-pixel
     * "nearly settled" gate already owns ranged leftover residual. Native
     * XHuman 9 enters Attack@1188 at leftover 0 on fixture 46; Java's
     * leftover is still 8 at that fixture because the chase opened later,
     * and waiting for leftover 0 pushed opcode ten past 55.
     */
    private boolean arriveMeleeLeftoverOnOccupiedQuarry(Unit unit) {
        if (unit == null || unit.type() == null
                || unit.type().maxAttackRange() != 1
                || unit.pathLength() != 1
                || unit.target() == null
                || World.battleNetRangedChaseUnit(unit)
                || (unit.order() != Unit.Order.ATTACK
                        && unit.order() != Unit.Order.ATTACK_MOVE
                        && !unit.chasing())) {
            return false;
        }
        int debt = Math.max(Math.abs(unit.offsetX()), Math.abs(unit.offsetY()));
        if (debt > 8) {
            return false;
        }
        int heading = unit.peekHeading();
        int nextX = unit.tileX() + Direction.deltaX(heading);
        int nextY = unit.tileY() + Direction.deltaY(heading);
        Unit quarry = world.unitAt(nextX, nextY);
        if (quarry == null || quarry != unit.target()
                || !world.targets.inAttackRange(unit, quarry)) {
            return false;
        }
        unit.clearPath();
        unit.setRouteSpent(false);
        unit.setBattleNetCollisionCounter(0);
        unit.setBattleNetChaseEmptyRouteReplan(false);
        unit.setChasing(false);
        unit.setFighting(true);
        unit.setBattleNetResidualEmptyRouteSettle(false);
        resetDisplacement(unit);
        world.openBattleNetAttackAfterChaseResidual(unit, true);
        world.consumeBattleNetPendingMeleeSyncRand(unit);
        world.turnToTarget(unit, quarry, 0, 0);
        return true;
    }

    void stepMove(Unit unit, boolean replanOnExhaustion) {
        if (arriveMeleeLeftoverOnOccupiedQuarry(unit)) {
            return;
        }
        // Nothing is asked of the route while the move animation is still
        // running. DoActionMove reaches NextPathElement only when
        //
        //   unit.Moving != 1 && (&Move != unit.Anim.CurrAnim
        //                        || (unit.Anim.Wait == 0 && unit.Anim.Anim == 0))
        //
        // and the second half of that is
        // what this implementation did not have: a unit that has reached its tile but is
        // still inside its move animation neither takes another step nor ends
        // its order. It finishes the animation first.
        //
        // The two are not the same length. ChonkCraft's critter walks 32 pixels
        // over sixteen `move 2` instructions but its animation is 48 cycles
        // long, and the last of those moves lands on cycle 45 -- so upstream's
        // critter is squarely on its new tile from 45 and still walking until
        // 50. This implementation called the walk over at 45 and stood the animal up,
        // and on (3)critter-attack that is 38 critters standing at cycle 46
        // where upstream's are all still walking.
        //
        // Asked once, at the top, and not again afterwards. Upstream's guard
        // is a single `if` around the whole of NextPathElement and everything
        // that follows from it, evaluated before the animation for this cycle
        // runs. Re-reading it after the animation has advanced -- which is
        // what the tail of this method used to do -- lets a unit finish its
        // order on the very cycle its animation ends, one cycle before
        // upstream, and on (3)critter-attack that is 36 animals at cycle 49.
        if (World.TRACE_MOVING != null && unit.id() == World.TRACE_MOVING_ID) {
            // The twin of MOVINGDBG at DoActionMove's top, field for field:
            // walkHolding is Moving-as-one, the offsets are IX/IY's owed
            // half, and the animation state is the decide gate's own read.
            AnimationSet dbgSet = unit.type().animationSet();
            Animation dbgMove = dbgSet == null ? null : dbgSet.get(AnimationSet.State.MOVE);
            System.err.printf("JMOVINGDBG cycle=%d unit=%d moving=%d unbreak=%d"
                    + " curranim-is-move=%d wait=%d anim=%d ix=%d iy=%d dir=%d path=%d"
                    + " fast=%d residual=%d,%d drained=%d chasing=%d pathgoal=%d,%d"
                    + " target=%d@%d,%d%n",
                    world.cycle, unit.id(), unit.walkHolding() ? 1 : 0,
                    unit.animation().unbreakable() ? 1 : 0,
                    unit.animation().current() == dbgMove ? 1 : 0,
                    unit.animation().waitCycles(), unit.animation().index(),
                    unit.offsetX(), unit.offsetY(), unit.direction(),
                    unit.pathLength(), unit.pathWaitBudget(),
                    unit.residualX(), unit.residualY(),
                    unit.stepDrained() ? 1 : 0, unit.chasing() ? 1 : 0,
                    unit.pathGoalX(), unit.pathGoalY(),
                    unit.target() == null ? -1 : unit.target().id(),
                    unit.target() == null ? -1 : unit.target().tileX(),
                    unit.target() == null ? -1 : unit.target().tileY());
        }
        // The decide gate, literal: "Moving != 1 && (&Move != CurrAnim ||
        // (Anim.Wait == 0 && Anim.Anim == 0))".
        // Not the offsets: a diagonal step whose first axis crosses nought
        // clears Moving with the other axis's pixel still owed, and
        // upstream consults at the wrap regardless -- levelx11o's destroyer
        // 74 steps at 50 with one pixel of iy in hand, where a gate that
        // read the offsets stood a whole pass.
        //
        // BNE chase steps follow the Move sequence's opcode-zero loop
        // (slot 3), not the ChonkCraft Move wait total. Skeleton Move is 21
        // ChonkCraft waits but the retail body between OP0s is 20 quiet calls;
        // XHuman 9 skeleton 1431 stepped SW at fixture 26 while Java's
        // atMoveBoundary fired at 27 on the same SW,SW,S path. The first
        // chase step still uses atMoveBoundary; after it commits, the Move
        // body is armed and subsequent headings wait for OP0.
        boolean chaseMoveSequence = world.battleNetSequence != null
                && unit.chasing()
                && unit.canMove()
                && world.combat.onBattleNetChaseMoveBody(unit);
        if (chaseMoveSequence) {
            world.combat.tickBattleNetChaseMoveSequence(unit);
        } else if (!unit.chasing()) {
            unit.setBattleNetChaseStepReady(false);
        }
        // Retail pays nought on the cycle a tile snaps and starts a cold
        // leg one cycle later than this implementation used to. Measured on Human 13
        // peon 1462 and knight 1500: the walk must finish the *old* element
        // before the gate is asked, and a unit that is not yet on Move only
        // picks the script up without running it. Walking the new step on
        // the commit cycle is what put every walker one animation beat
        // ahead of retail forever after its first step, which is why the
        // reaction band hid knight 1500 from ogre 1519 at fixture 29 while
        // native's band did not. Chase OP0 sequencing keeps the older
        // decide-then-walk order; its body is armed after the first step
        // and is not the sub-tile lead.
        boolean walkedThisCycle = false;
        if (!chaseMoveSequence && onMoveAnimation(unit)
                && (world.actionMoveWalked || unit.isMoving() || unit.walkHolding())) {
            // stepAttack may already have drained the old element so its
            // chase-boundary consult could see Moving clear. Do not walk
            // twice in that case. Do not walk a settled unit on Move at the
            // boundary either -- that burns the decide window before the
            // consult (wise-man 1496 after its Attack-four hold).
            if (world.actionMoveWalked) {
                walkedThisCycle = true;
            } else {
                walkedThisCycle = true;
                walkPixels(unit);
            }
        }
        // OP0 is the retail decide gate for the next chase heading. It can
        // fire while ChonkCraft Move still holds through its unbreakable tail
        // (21 waits vs 20 OP0 body calls). Honour the sequence even if
        // Moving is still set; the commit clears residual below. For an
        // ordinary walk the gate is read *after* the guarded walk so a
        // unit that still owed pixels into this cycle can commit while
        // the drain of the old element is what cleared Moving.
        boolean mayDecide = chaseMoveSequence
                ? unit.battleNetChaseStepReady()
                : !unit.walkHolding() && atMoveBoundary(unit);
        // Gold-approach mid-route residual settle: native writes route_index
        // 20 (movb $0x14 at 0x450ad4) and pays one quiet decide before a
        // blocked leftover commits or detours. Authenticated:
        //   - Orc 5 peasant 1529: pathLength-2 diagonal leftover onto an ally
        //     (second residual; free first residual at fixture 22 still steps).
        //   - XHuman 10 peon 1437: residual of the S step onto 16,108 with
        //     ally peon 168 on the next S cell writes route_index 1→20 at
        //     fixture 38; the SE detour is fixture 39. Java's gold refuse
        //     arm, with collision already 1, took the far detour same cycle
        //     (one early). Free open leftovers keep same-cycle commit.
        // Terrain wood (resourceUnit null) is unchanged.
        boolean goldMidRoute = unit.resourceUnit() != null
                && !unit.returningToDepot()
                && unit.resourceUnit().type() != null
                && unit.resourceUnit().type().givesResource()
                        == UnitType.Resource.GOLD;
        // Pure-cardinal pathn-2 leftover after residual when the next cell holds
        // an allied worker: native restarts Move Wait 15 twice then commits
        // once clear (XHuman 4 peon 1570: residual world 21, ally peon 22 on
        // 120,13; timer 15×2; E@49). Free open leftovers free-step (Orc 5
        // fixture 22). Non-worker blockers keep ordinary refuse/soft-wait.
        // Delay 29 → walk 30 cycles after settle. lastStep must match peek so
        // a pure corridor residual is distinguished from a replan leftover.
        if (goldMidRoute && !chaseMoveSequence
                && walkedThisCycle && unit.stepDrained()
                && unit.pathLength() == 2
                && !Direction.isDiagonal(unit.peekHeading())
                && unit.peekHeading() == unit.peekHeadingAtDepth(1)
                && unit.lastStepHeading() == unit.peekHeading()) {
            int freeNx = unit.tileX() + Direction.deltaX(unit.peekHeading());
            int freeNy = unit.tileY() + Direction.deltaY(unit.peekHeading());
            Unit freeBlocker = world.unitAt(freeNx, freeNy);
            boolean workerAlly = freeBlocker != null
                    && freeBlocker != unit
                    && freeBlocker.isOnMap()
                    && !freeBlocker.isDying()
                    && world.isAllied(unit.player(), freeBlocker.player())
                    && freeBlocker.type() != null
                    && (freeBlocker.type().ident().contains("peon")
                            || freeBlocker.type().ident().contains("peasant"));
            if (workerAlly) {
                int[] approach = world.battleNetApproachPoint(
                        unit, unit.resourceUnit());
                int approachCheb = Math.max(
                        Math.abs(approach[0] - unit.tileX()),
                        Math.abs(approach[1] - unit.tileY()));
                if (approachCheb >= 2) {
                    unit.setBattleNetOrderDelay(29);
                    mayDecide = false;
                }
            }
        }
        if (mayDecide && goldMidRoute && unit.pathLength() > 0
                && !chaseMoveSequence) {
            if (unit.battleNetWoodRouteIndex20()) {
                unit.setBattleNetWoodRouteIndex20(false);
            } else if (walkedThisCycle && unit.stepDrained()) {
                int settles = unit.battleNetWoodResidualSettles() + 1;
                unit.setBattleNetWoodResidualSettles(settles);
                int nx = unit.tileX() + Direction.deltaX(unit.peekHeading());
                int ny = unit.tileY() + Direction.deltaY(unit.peekHeading());
                Unit blocker = world.unitAt(nx, ny);
                boolean ally = blocker != null && blocker != unit
                        && blocker.isOnMap() && !blocker.isDying()
                        && world.isAllied(unit.player(), blocker.player());
                // Orc 5's peasant 1529 pays a quiet visit on its second
                // residual settle when a diagonal leftover holds an ally.
                //
                // The other half of this arm has gone. It charged the same
                // quiet visit whenever the collision counter was already
                // raised, and it was written when the free-detour arms above
                // fired a cycle early and cancelled it out. With those arms
                // gone what it stood in for is the refusal's own route-park:
                // `fcn.004379e0` marks the cursor 20 through `0x00450ad0` and
                // bumps the count at `0x00437a0d` in one visit. Retail's peon
                // 1437 in XHuman 10 marks 16,108 refused on fixture 38 and
                // steps south-east on 39; the quiet visit had this implementation
                // marking on 39 and stepping on 40, and it stayed a cycle
                // behind for the rest of the walk -- 56 against retail's 55
                // on the very next step.
                boolean orcFiveSeam = settles >= 2
                        && unit.pathLength() == 2
                        && Direction.isDiagonal(unit.peekHeading());
                if (ally && orcFiveSeam) {
                    unit.setBattleNetWoodRouteIndex20(true);
                    mayDecide = false;
                }
            }
        }
        boolean stepped = false;
        if (mayDecide) {
            if (chaseMoveSequence && unit.walkHolding()) {
                unit.setWalkHolding(false);
                resetDisplacement(unit);
            }
            if (unit.pathLength() == 0) {
                // Patrol straight-run exhaust: residual of the third identical
                // heading settled with the leftover already discarded. Native
                // holds Still animation timer 3 under PATROL (order stays 4)
                // then replans -- Orc 11 archer 1559 Still@50 step@53 (+3 gap).
                // Immediate replan free-stepped the next NE at fixture 50;
                // PF_WAIT 10 was seven cycles late.
                if (walkedThisCycle && unit.stepDrained()
                        && unit.battleNetBorrowedMoveForStep()
                        && unit.battleNetPatrolStraightRunExhausted()) {
                    unit.setBattleNetPatrolStraightRunExhausted(false);
                    unit.setRouteSpent(false);
                    resetDisplacement(unit);
                    unit.setWaitCycles(0);
                    unit.setBattleNetOrderDelay(2);
                    if (world.battleNetSequence != null) {
                        unit.setBattleNetSequenceOffset(
                                world.idle.battleNetStillSequenceStart(unit));
                        unit.setBattleNetAnimationTimer(3);
                    }
                    return;
                }
                // Residual settle of the last path step under MOVE: native
                // promotes Still (order 2) then queues next_order Attack via
                // Still-acquisition (XHuman 12 grunt 1358: path=22 exhausts
                // after E@22, residual settles at fixture 38 into
                // STAND_GROUND/Attack). spendTheEmptyRoute's PF_WAIT 10 kept
                // Java on MOVE through 45. Attack chases keep the short empty
                // wait (grunt 1512). BW: next_order 60→12 at 0x45324d after
                // target search; order Still write at 0x453127.
                if (walkedThisCycle && unit.stepDrained()
                        && unit.order() == Unit.Order.MOVE
                        && !unit.battleNetPlayerCommandMove()
                        && !unit.battleNetBorrowedMoveForStep()
                        && !chaseMoveSequence
                        && unit.routeSpent()
                        && unit.type() != null
                        && ((unit.type().canAttack() && unit.isAggressive())
                                || "unit-critter".equals(
                                        unit.type().ident()))) {
                    boolean critter = "unit-critter".equals(
                            unit.type().ident());
                    int react = Math.max(
                            unit.type().reactRange(
                                    world.isPerson(unit.player())),
                            Math.max(1, unit.type().maxAttackRange()));
                    Unit hostile = critter ? null
                            : world.targets.findBattleNetHostile(
                                    unit, react, null);
                    // Exhausting one twenty-heading route is not completing
                    // a move order. Retail promotes this residual boundary to
                    // Still only when the standing scan actually finds the
                    // next Attack order (or for a critter's completed
                    // wander). A long player move with no hostile must pay
                    // the ordinary empty-route pause and ask for its next
                    // course. Treating every aggressive mover as if its scan
                    // succeeded made real footmen abandon long commands at
                    // the first route-buffer boundary.
                    if (critter || hostile != null) {
                        // Aggressive units Still then free-scan Attack (grunt
                        // 1358). Critters Still without PF_WAIT 10 (Human 4
                        // 1578 residual settle Still@50; empty-route wait held
                        // MOVE to ~60 across six multi@50 still-vs-move cases).
                        // Skip when walkTowards borrowed Move for harvest/patrol
                        // -- Still+delay 2 was restored to HARVEST but left the
                        // delay, so free-prefix replan stepped three cycles late
                        // (XHuman 2 peon 1530 native@50 vs Java@53).
                        resetDisplacement(unit);
                        unit.setRouteSpent(false);
                        unit.setWaitCycles(0);
                        unit.setOrder(Unit.Order.STILL);
                        if (critter && world.battleNetSequence != null) {
                            unit.setBattleNetSequenceOffset(
                                    world.idle.battleNetStillSequenceStart(unit));
                            unit.setBattleNetAnimationTimer(3);
                            return;
                        }
                        if (hostile != null) {
                            // Native Still OP0 runs idle-random (0040AD58)
                            // before next_order Attack promotion (XHuman 12
                            // grunt 1358 at 12,90: idle-dispatch order=2 then
                            // Attack). Skipping that draw left reverse-order
                            // footmen on the same cycle holding this unit's
                            // async ordinal, so fixture 38 melee rolled 5+4
                            // instead of native 6+6. Only when promoting: a
                            // no-hostile Still settle must not invent a draw
                            // (XHuman 7 critter 1433 still vs MOVE at 38).
                            world.idle.advanceBattleNetActiveOrderIdleRandom(unit);
                            world.orderAttack(unit, hostile);
                            // orderAttack arms Attack construction timer 3.
                            // Two quiet visits match battleNetAutoAttack's
                            // action-16 construction delay.
                            unit.setBattleNetOrderDelay(2);
                        }
                        return;
                    }
                }
                // The last animation instruction may have drained the final
                // residual in this call, after stepMoveOrder's entrance test.
                // Retail closes the terminal Move immediately at that exact
                // pixel; PF_WAIT belongs only between non-terminal buffers.
                if (!"unit-critter".equals(unit.type().ident())
                        && unit.routeSpent()
                        && battleNetCommandPointReached(unit)) {
                    finishBattleNetMoveAtTarget(unit);
                    return;
                }
                if (battleNetOccupiedPointRefusal(unit)) {
                    // Residual settled during this visit. Native consults the
                    // occupied terminal element now, not on the following
                    // tick, so let the ordinary move executor lay and refuse
                    // that one-heading route in the same visit.
                    unit.setRouteSpent(false);
                    stepMoveOrder(unit);
                    return;
                }
                if (spendTheEmptyRoute(unit)) {
                    return;
                }
                // The fresh query after the empty-route pause has answered
                // PF_REACHED or PF_UNREACHABLE. DoActionMove clears IX/IY on
                // both answers before it returns.
                // That includes displacement left by a ship's Still wiggle:
                // levelx11o's destroyer reaches battleship range with one
                // vertical pixel in hand, and keeping it makes the attack
                // order mistake the ship for a live step forever.
                resetDisplacement(unit);
                unit.setOrder(Unit.Order.STILL);
                return;
            }
            int heading = unit.peekHeading();
            // BNE large-ship wall-follow often answers a diagonal whose pure
            // Bresenham first step was cardinal. XOrc 11's battleship at
            // 20,40 plans north toward the shipyard, wall-follows north-west
            // around the tanker, and would step to 18,38; native takes only
            // the detour cardinal west to 18,40 and holds. When the open
            // Bresenham first heading is cardinal and the stored first
            // heading is the adjacent diagonal, keep just the component that
            // is not the pure ray -- the wall-follow detour -- if that
            // double-step lands free. Pure-diagonal open rays (XOrc 11's
            // other battleship south-east to 8,26) are left alone.
            // walkTowards clears pathGoal (-1), so the order target is the
            // destination used for the pure Bresenham comparison.
            int goalX = unit.pathGoalX() >= 0 ? unit.pathGoalX()
                    : unit.orderTargetX();
            int goalY = unit.pathGoalY() >= 0 ? unit.pathGoalY()
                    : unit.orderTargetY();
            // Capital ships only: XOrc 11 battleships wall-follow a free pure
            // ray into a diagonal and native keeps just the detour cardinal.
            // Destroyers and submarines keep the full wall-follow answer
            // (XHuman 7 west replan; XHuman 7 submarine corridor).
            boolean capitalShip = "unit-battleship".equals(unit.type().ident())
                    || "unit-ogre-juggernaught".equals(unit.type().ident());
            if (unit.battleNetDoubleStep() && capitalShip
                    && Direction.isDiagonal(heading) && world.map.contains(goalX, goalY)) {
                // Match BattleNetPathFinder's even-grid snap so the pure ray
                // is the same ray the pathfinder drew (XOrc 8's battleship
                // used to lose its south-east step when pure was measured
                // against the unsnapped odd goal and "detoured" to pure south).
                int snapX = goalX & ~1;
                int snapY = goalY & ~1;
                int pure = World.battleNetFirstBresenhamHeading(unit.tileX(),
                        unit.tileY(), snapX, snapY);
                int strideProbe = world.battleNetMovementStride(unit);
                // Only when the pure cardinal ray is itself free: XOrc 11's
                // battleship can step pure north but wall-follows NW and
                // should keep only the detour west. When the pure ray is
                // blocked (XHuman 7 destroyer with a tanker due west), the
                // wall-follow diagonal is the real escape and must stay.
                boolean pureFree = pure >= 0 && pure < Direction.COUNT
                        && !Direction.isDiagonal(pure)
                        && world.canEnterBattleNetTransportAnchor(unit,
                                unit.tileX() + Direction.deltaX(pure)
                                        * strideProbe,
                                unit.tileY() + Direction.deltaY(pure)
                                        * strideProbe);
                if (pureFree) {
                    int left = Math.floorMod(pure - 1, Direction.COUNT);
                    int right = Math.floorMod(pure + 1, Direction.COUNT);
                    if (heading == left || heading == right) {
                        int dx = Direction.deltaX(heading);
                        int dy = Direction.deltaY(heading);
                        if (Direction.deltaX(pure) != 0) {
                            dx = 0;
                        }
                        if (Direction.deltaY(pure) != 0) {
                            dy = 0;
                        }
                        if (dx != 0 || dy != 0) {
                            int detour = Direction.fromDelta(dx, dy);
                            // Only keep a component that moves away from the
                            // goal on its own axis (true wall-follow detour).
                            // XOrc 8's SE step toward a south-east shipyard
                            // has both axes closer and must stay diagonal.
                            int detourX = unit.tileX()
                                    + Direction.deltaX(detour) * strideProbe;
                            int detourY = unit.tileY()
                                    + Direction.deltaY(detour) * strideProbe;
                            boolean detourAway =
                                    Math.abs(snapX - detourX)
                                            > Math.abs(snapX - unit.tileX())
                                    || Math.abs(snapY - detourY)
                                            > Math.abs(snapY - unit.tileY());
                            if (detourAway
                                    && world.canEnterBattleNetTransportAnchor(
                                            unit, detourX, detourY)) {
                                unit.replacePeekHeading(detour);
                                heading = detour;
                            }
                        }
                    }
                }
            }
            // Action 30 re-tests unit+0x1c&2 before each free commit. The order
            // arm may double-step from a far even lattice, but once residual of
            // that leg settles the remaining shore deltas often fail the same
            // major/minor gate (Human 12 transport 1522: 70,32→goal 72,29 is
            // (2,3); native clears double at fixture 41 and half-steps NE to
            // 71,31 while a sticky bit double-stepped to 72,30). Do not gate on
            // order==HARVEST: stepBattleNetTransportToHall temporarily sets
            // MOVE around stepMove, so the enum is MOVE on the decide visit.
            if (unit.battleNetDoubleStep()
                    && unit.type() != null
                    && unit.type().canTransport()
                    && unit.orderTargetX() >= 0
                    && unit.orderTargetY() >= 0) {
                unit.setBattleNetDoubleStep(world.battleNetTransportDoubleStep(
                        unit, unit.orderTargetX(), unit.orderTargetY()));
            }
            int stride = world.battleNetMovementStride(unit);
            // Double-step multi-step leftover free-closer: a wall-follow route
            // planned while a neighbour still occupied a better corridor can
            // leave a free but worse peek after residual of the first leg.
            // XOrc 11 destroyer 1558 (Java 42) plans E,E,SE,SW from 4,18 while
            // tanker 48 still sits on 8,20; after E@8 residual, leftover E to
            // 8,18 is free but free SE to 8,20 is two Chebyshev closer to the
            // patrol goal and matches native's second double-step at fixture
            // 40. Soft-clear only covers Move-animation allies, so the tanker
            // hard-blocks the SE cell at plan time. Replacing a free peek with
            // a free strictly closer heading (then a one-heading route) is the
            // residual-settle correction; pathn==1 leftovers keep their tip.
            // Destroyers and other non-capital double-step ships only:
            // capital wall-follow detour cardinals (battleship west keep)
            // are free-closer worse toward the goal and must not be rewritten.
            boolean capitalDoubleStep = unit.battleNetDoubleStep()
                    && ("unit-battleship".equals(unit.type().ident())
                    || "unit-ogre-juggernaught".equals(unit.type().ident()));
            if (unit.battleNetDoubleStep()
                    && !capitalDoubleStep
                    // This is the captured patrol residual correction. It
                    // must not rewrite routes borrowed by construction,
                    // harvesting, or combat: those orders keep their live
                    // target separately, while orderTarget may still name an
                    // older player command. Applying the patrol correction
                    // there reduced every newly planned route to one heading
                    // toward that stale point, producing a deterministic
                    // two-tile ship oscillation.
                    && unit.battleNetBorrowedMoveForStep()
                    && unit.patrolX() >= 0
                    && unit.resourceUnit() == null
                    && unit.pathLength() > 1
                    && unit.orderTargetX() >= 0
                    && unit.orderTargetY() >= 0) {
                int patrolGoalX = unit.orderTargetX();
                int patrolGoalY = unit.orderTargetY();
                int peekX = unit.tileX()
                        + Direction.deltaX(heading) * stride;
                int peekY = unit.tileY()
                        + Direction.deltaY(heading) * stride;
                boolean peekFree = world.canEnterBattleNetTransportAnchor(
                        unit, peekX, peekY);
                if (peekFree) {
                    int peekDist = Math.max(
                            Math.abs(patrolGoalX - peekX),
                            Math.abs(patrolGoalY - peekY));
                    int freeHeading = -1;
                    int bestDist = peekDist;
                    for (int dir = 0; dir < Direction.COUNT; dir++) {
                        if (dir == heading) {
                            continue;
                        }
                        int freeX = unit.tileX()
                                + Direction.deltaX(dir) * stride;
                        int freeY = unit.tileY()
                                + Direction.deltaY(dir) * stride;
                        if (!world.canEnterBattleNetTransportAnchor(
                                unit, freeX, freeY)) {
                            continue;
                        }
                        int dist = Math.max(
                                Math.abs(patrolGoalX - freeX),
                                Math.abs(patrolGoalY - freeY));
                        if (dist < bestDist) {
                            bestDist = dist;
                            freeHeading = dir;
                        }
                    }
                    if (freeHeading >= 0) {
                        unit.clearPath();
                        unit.setPath(new PathFinder.Path(
                                PathFinder.Result.FOUND,
                                new int[] {freeHeading}));
                        heading = freeHeading;
                    }
                }
            }
            int nextX = unit.tileX() + Direction.deltaX(heading) * stride;
            int nextY = unit.tileY() + Direction.deltaY(heading) * stride;

            // Re-check the square: another unit may have taken it since the
            // path was found. Wait a few cycles and re-plan, which resolves a
            // jam without searching every time two units brush past.
            //
            // The destination is the one the path was built for, not
            // orderTarget. orderTarget is set only by move, patrol, explore
            // and attack-ground; for a worker heading to a mine, or a soldier
            // closing on an enemy, it was still its initial minus one -- so
            // the re-plan asked for a route to a square off the map, got
            // nothing, kept its dead path, and bumped the same body again on
            // the next cycle for as long as the game ran.
            // BNE large ships path and step on the anchor grid (1×1), not the
            // 2×2 sprite footprint. Using the full footprint here refused the
            // south-east heading XOrc 11's battleship plans with the 1×1
            // pathfinder, so clear-on-refuse looped forever at 6,24 while
            // native stepped to 8,26.
            boolean canTakeStep = unit.type().canTransport()
                    && unit.target() != null
                    && World.isBattleNetHall(unit.target().type().ident())
                            ? world.canEnterBattleNetTransportAnchor(unit, nextX, nextY)
                            : unit.resourceUnit() != null
                                    && !unit.returningToDepot()
                                    ? world.harvest.canEnterBattleNetResourceTarget(
                                            unit, nextX, nextY)
                            : unit.battleNetDoubleStep()
                                    ? world.canEnterBattleNetTransportAnchor(
                                            unit, nextX, nextY)
                            : world.canEnter(unit, nextX, nextY);
            if (World.TRACE_MOVING != null && unit.id() == World.TRACE_MOVING_ID) {
                Unit decideBlocker = world.unitAt(nextX, nextY);
                long decideFlags = world.map.contains(nextX, nextY)
                        ? world.map.field(nextX, nextY).flags() : -1L;
                System.err.printf("JMOVEDECIDE cycle=%d unit=%d heading=%d next=%d,%d"
                                + " stride=%d can=%d double=%d path=%d blocker=%d flags=%x%n",
                        world.cycle, unit.id(), heading, nextX, nextY, stride,
                        canTakeStep ? 1 : 0, unit.battleNetDoubleStep() ? 1 : 0,
                        unit.pathLength(),
                        decideBlocker == null ? -1 : decideBlocker.id(),
                        decideFlags);
            }
            if (!canTakeStep) {
                // Large BNE ships re-query the even grid on a refused step
                // rather than burning a ten-cycle PF_WAIT on a detour planned
                // while a neighbour still occupied the corridor. XHuman 7's
                // destroyer at 28,26 pathfinds one action visit before the
                // tanker at 26,26 steps south-east (BNE walks high pool slots
                // first, so the destroyer acts before the tanker). Spending
                // PF_WAIT on the south-west detour made it miss the west step
                // native takes on fixture cycle 5 once the tanker has left.
                //
                // Residual-settled multi leftover blocked by an ally is
                // different: native keeps the route and arms Move timer 15
                // (XORc 8 destroyer 1431: residual of N lands c38 onto sub
                // 1432 at 100,88; timer 15..1 through c52; NW@53 after the
                // sub left c50). clearPath re-query stepped as soon as the
                // cell freed (fixture 51) -- two cycles early and without
                // the full fifteen-count.
                if (unit.battleNetDoubleStep()) {
                    // That hold belongs only to a live body which may vacate
                    // the anchor. A route can also end with a heading onto
                    // permanent coast or a building after its bounded prefix
                    // has carried a capital ship across the map. Treating
                    // that terrain refusal like the submarine witness above
                    // preserves the impossible heading forever: every wake
                    // retries it, rearms fifteen, and an acknowledged attack
                    // order appears to have been ignored. Drop permanent
                    // refusals so the still-live move/attack order asks the
                    // pathfinder for the next route leg around the coast.
                    Unit blocker = world.blockerOnLayer(unit, nextX, nextY);
                    boolean temporaryBody = blocker != null
                            && blocker != unit
                            && !blocker.type().building();
                    if (temporaryBody && unit.stepDrained() && !unit.isMoving()
                            && unit.pathLength() > 0) {
                        unit.setBattleNetOrderDelay(14);
                        unit.setWaitCycles(0);
                        if (world.battleNetSequence != null
                                && world.battleNetMoveAnimation(unit)) {
                            unit.setBattleNetAnimationTimer(15);
                        }
                        return;
                    }
                    unit.clearPath();
                    unit.setWaitCycles(0);
                    return;
                }
                // Ranged attack-chase leftover headings after a completed tile
                // step ({@code stepDrained}) must not sleep PF_WAIT 10.
                // Native ends the multi-step route (route_index 20) and rebuilds
                // on the next consult: XHuman 4 axethrower 1521's cached second
                // W was refused at fixture 24 and a ten-cycle wait left it at
                // (77,59) while native stepped SW from a fresh plan at 25.
                // Only after a drained step with more than one heading left so
                // a refused first approach (XHuman 4 axethrower 1490 @22) still
                // uses ordinary PF_WAIT. Melee keeps PF_WAIT entirely.
                //
                // Exception: a cooperative ally that is itself a ranged chase
                // unit and is still mid-route (moving or leftover path) keeps
                // the leftover under FUN_004379e0's fourteen-visit hold.
                // XHuman 12 axethrower 1523 (Java 77) at (31,37) held SE,S with
                // SE onto ally axethrower 76 (pathn 4, moving), native timer 15
                // through fixtures 24..38 then S@40; clearing residual-settled
                // path 2→0 and SW-replanned onto (30,38) at fixture 35.
                // A cooperative-looking ally with no remaining path and not
                // moving still hard-replans: XHuman 4 axethrower 1516 (Java 84)
                // NW onto ally 94 (pathn 0, still) must clear and step W.
                // Melee blockers also hard-replan: XHuman 12 axethrower 1522
                // S onto grunt 86 must clear and step E at fixture 26.
                if (!replanOnExhaustion && unit.target() != null
                        && World.battleNetRangedChaseUnit(unit) && unit.stepDrained()
                        && unit.pathLength() > 1
                        && (unit.order() == Unit.Order.ATTACK || unit.order() == Unit.Order.ATTACK_MOVE || unit.order() == Unit.Order.MOVE || unit.chasing())) {
                    Unit rangedBlocker = world.unitAt(nextX, nextY);
                    boolean rangedCoop = world.battleNetCooperativeBlocker(
                            unit, rangedBlocker);
                    boolean rangedAllyBlocker = rangedBlocker != null
                            && World.battleNetRangedChaseUnit(rangedBlocker);
                    boolean blockerStillRouting = rangedBlocker != null
                            && (rangedBlocker.isMoving()
                                    || rangedBlocker.pathLength() > 0);
                    if (!(rangedCoop && rangedAllyBlocker
                            && blockerStillRouting)) {
                        // Residual pathn-3 refuse onto a melee ally that still
                        // owns a multi-step route: re-arm delay 1 and keep the
                        // route until that ally leaves. There is one ordering
                        // bridge: Java visits axe 1478 before grunt 1482, while
                        // retail visits the corresponding slots in the opposite
                        // order. When Java sees that chasing grunt at a drained
                        // Move boundary with its route already spent, retail has
                        // already let it rebuild and vacate. Keeping the axe's
                        // route for this visit makes the observable ownership
                        // transition independent of pool iteration order. An
                        // ordinary pathless ally still hard-replans (XHuman 12
                        // axe 1522 onto a standing grunt).
                        if (unit.pathLength() == 3
                                && unit.stepDrained()
                                && !unit.isMoving()
                                && rangedBlocker != null
                                && !World.battleNetRangedChaseUnit(
                                        rangedBlocker)
                                && world.isAllied(unit.player(),
                                        rangedBlocker.player())
                                && (rangedBlocker.pathLength() >= 2
                                        || (rangedBlocker.pathLength() == 0
                                                && rangedBlocker.chasing()
                                                && rangedBlocker.stepDrained()
                                                && world.battleNetMoveAnimation(
                                                        rangedBlocker)))) {
                            unit.setBattleNetOrderDelay(1);
                            return;
                        }
                        // This is retail's PARK, so it is counted as one.
                        // 0x00437a0d bumps the nibble in the same visit
                        // 0x00450ad0 writes the cursor to 20, and a chaser
                        // that has refused eight times sleeps fifteen like any
                        // other unit. The arm used to give the route up with
                        // neither counter moving at all: XHuman 4's axethrower
                        // 1516 drops NW,N on fixture 38 where retail's nibble
                        // goes to 1, so nothing downstream could tell that
                        // visit from an ordinary replan.
                        battleNetRefuse(unit);
                        unit.setRouteSpent(false);
                        unit.setBattleNetOrderDelay(0);
                        return;
                    }
                    // Cooperative mid-route ranged ally leftover: hold below.
                }
                // Native 0x4379e0 does not consume a cached heading when a
                // cooperative allied mover is expected to vacate it.  It
                // keeps the route/cursor and puts the movement animation on
                // its fifteen-count refusal wait.  The Java delay stores the
                // remaining quiet visits, hence fourteen: refusal at c5,
                // quiet c6..c19, retry on c20.  A blocker which has itself
                // collided is no longer cooperative (XHuman 12 grunt 96 at
                // c20), and a head-on swap takes the invalidate arm.
                Unit movingBlocker = world.unitAt(nextX, nextY);
                boolean goldApproach = unit.resourceUnit() != null
                        && !unit.returningToDepot()
                        && unit.resourceUnit().type() != null
                        && unit.resourceUnit().type().givesResource()
                                == UnitType.Resource.GOLD;
                if (!goldApproach && world.battleNetCooperativeBlocker(unit, movingBlocker)) {
                    // Empty-route residual rebuild whose first heading lands
                    // on a soft-cleared ally: take the first free compass
                    // neighbour now (native N for XHuman 12 grunt 1507 at
                    // fixture 36). Mid-route leftovers still wait fourteen.
                    if (unit.battleNetChaseEmptyRouteReplan()
                            && unit.stepDrained()
                            && unit.pathLength() > 1
                            && unit.battleNetCollisionCounter() == 0
                            && !World.battleNetRangedChaseUnit(unit)) {
                        int strideDetour = world.battleNetMovementStride(unit);
                        int freeHeading = -1;
                        for (int dir = 0; dir < Direction.COUNT; dir++) {
                            int freeX = unit.tileX()
                                    + Direction.deltaX(dir) * strideDetour;
                            int freeY = unit.tileY()
                                    + Direction.deltaY(dir) * strideDetour;
                            if (world.canEnter(unit, freeX, freeY)) {
                                freeHeading = dir;
                                break;
                            }
                        }
                        if (freeHeading >= 0) {
                            // A detour replaces the route it stepped around.
                            // Rewriting only the head used to leave the rest of
                            // the old route underneath the detour, and the XHuman
                            // 12 grunt that sidestepped north at fixture 36 kept
                            // 19 headings pointing south-east into a cell that was
                            // still occupied. That leftover settled blocked, so the
                            // grunt replanned and stepped east on the same cycle,
                            // reaching 28,38 at fixture 52 where the retail engine
                            // reaches it at 55, which is why the whole leftover
                            // goes and a chase drops it owing one residual hold.
                            boolean multiLeftover = unit.pathLength() > 1
                                    && unit.target() != null
                                    && !World.battleNetRangedChaseUnit(unit)
                                    && (unit.order() == Unit.Order.ATTACK
                                            || unit.order()
                                                    == Unit.Order.ATTACK_MOVE
                                            || unit.chasing());
                            unit.clearPath();
                            unit.setPath(new PathFinder.Path(
                                    PathFinder.Result.FOUND,
                                    new int[] {freeHeading}));
                            unit.setBattleNetChaseEmptyRouteReplan(false);
                            if (multiLeftover) {
                                unit.setBattleNetEmptyRouteFreeDetourHold(true);
                            }
                            heading = freeHeading;
                            nextX = unit.tileX()
                                    + Direction.deltaX(heading) * strideDetour;
                            nextY = unit.tileY()
                                    + Direction.deltaY(heading) * strideDetour;
                            canTakeStep = true;
                        }
                    }
                    // After one cooperative soft-wait, residual-settled melee
                    // with nearly-full leftover path free-compass detours.
                    // XHuman 12 grunt 1494 (path 6 at 27,40) after coll>=1.
                    //
                    // It stands in for the replan native does, and taking it
                    // out costs 25 of 410,880 paired unit-cycles fleet-wide
                    // and seventeen cycles on the case. It goes when the
                    // replan is right; focused tests preserve the rule.
                    if (!canTakeStep
                            && unit.stepDrained()
                            && !unit.isMoving()
                            && unit.pathLength() == 6
                            && unit.battleNetCollisionCounter() >= 1
                            && unit.target() != null
                            && !World.battleNetRangedChaseUnit(unit)
                            && (unit.order() == Unit.Order.ATTACK
                                    || unit.order() == Unit.Order.ATTACK_MOVE
                                    || unit.chasing())) {
                        int strideDetour = world.battleNetMovementStride(unit);
                        int freeHeading = -1;
                        for (int dir = 0; dir < Direction.COUNT; dir++) {
                            int freeX = unit.tileX()
                                    + Direction.deltaX(dir) * strideDetour;
                            int freeY = unit.tileY()
                                    + Direction.deltaY(dir) * strideDetour;
                            if (world.canEnter(unit, freeX, freeY)) {
                                freeHeading = dir;
                                break;
                            }
                        }
                        if (freeHeading >= 0) {
                            unit.replacePeekHeading(freeHeading);
                            if (unit.battleNetCollisionCounter() < 2) {
                                unit.setBattleNetCollisionCounter(0);
                            }
                            heading = freeHeading;
                            nextX = unit.tileX()
                                    + Direction.deltaX(heading) * strideDetour;
                            nextY = unit.tileY()
                                    + Direction.deltaY(heading) * strideDetour;
                            canTakeStep = true;
                        }
                    }
                    if (!canTakeStep) {
                        int counter = unit.battleNetCollisionCounter() + 1;
                        unit.setBattleNetCollisionCounter(
                                counter > 14 ? 0 : counter);
                        // Residual-settled one-heading leftover blocked on the
                        // settle visit: native route_index 20 then replan
                        // (XHuman 12 grunt 1514: residual of E onto 28,38,
                        // SE onto ally, RI 1→20 at fixture 41, N@42). Soft-
                        // waiting fourteen left Java until fixture 52. Mid-
                        // route pathn1 soft-wait is not residual-settled
                        // (grunt 1503 holds E with walked=0) and still uses
                        // the second-refuse replan below.
                        boolean residualSettledPathn1 = world.actionMoveWalked
                                && unit.stepDrained()
                                && !unit.isMoving()
                                && unit.pathLength() == 1
                                && unit.target() != null
                                && !World.battleNetRangedChaseUnit(unit)
                                && (unit.order() == Unit.Order.ATTACK
                                        || unit.order()
                                                == Unit.Order.ATTACK_MOVE
                                        || unit.chasing());
                        // One-heading chase leftover after its soft-wait:
                        // native marks route_index 20 and replans (XHuman 12
                        // grunt 1503: SE hold c23-c38, then multi-step E at
                        // 39). Retrying the same heading armed delay 14
                        // forever until ~79. setPath resets the counter so
                        // each generation soft-waits once then replans.
                        if ((residualSettledPathn1 || counter > 1)
                                && unit.pathLength() == 1
                                && unit.target() != null
                                && !World.battleNetRangedChaseUnit(unit)
                                && (unit.order() == Unit.Order.ATTACK
                                        || unit.order()
                                                == Unit.Order.ATTACK_MOVE
                                        || unit.chasing())) {
                            if (System.getenv("CHONKCRAFT_TRACE_BNE_RESIDUAL")
                                    != null) {
                                String resEnv = System.getenv(
                                        "CHONKCRAFT_TRACE_BNE_RESIDUAL").trim();
                                if ("*".equals(resEnv)
                                        || unit.id() == Integer.parseInt(
                                                resEnv)) {
                                    System.err.printf(
                                            "JBNECOOPREPLAN cycle=%d unit=%d "
                                                    + "residual=%d counter=%d%n",
                                            world.cycle, unit.id(),
                                            residualSettledPathn1 ? 1 : 0,
                                            counter);
                                }
                            }
                            // Native's give-up is nine bytes -- 0x00450ad0 sets
                            // the route cursor to 20 -- and its caller then sets
                            // the movement timer to 1 and returns. Nothing else:
                            // no replan flag, no detour, no special delay, and the
                            // refusal counter is left where it is, because only
                            // the fifteenth clears it. The unit simply comes back
                            // next visit with no route and lays a fresh one.
                            unit.clearPath();
                            unit.setRouteSpent(false);
                            unit.setWaitCycles(0);
                            unit.setBattleNetOrderDelay(0);
                            return;
                        }
                        // Multi-step residual-settled leftover after one
                        // cooperative soft-wait whose planned cell is still
                        // blocked: native marks route_index 20 and pathfinds
                        // a fresh route (XHuman 12 grunt 1510: W onto ally
                        // 1512 still blocked after the wait, then SE@41).
                        // Re-arming delay 14 forever left Java at (33,39).
                        // Replanning even when the peek freed REG'd grunt
                        // 1453 at fixture 36 -- only abandon a still-blocked
                        // residual. Attack-four remainder is delay 3 so the
                        // step lands with native's c37-39 hold then c41.
                        // pathLength 2..5, pure-axis peek: 1510 residual
                        // W,SW,S,S soft-waits pure W then SE@41. Nearly-full
                        // (pathn>=6) and long residuals (1513 pathn 7) keep
                        // soft-waiting. Diagonal multi-step free-compass REG'd
                        // xhuman-04 @39.
                        boolean multiResidualSecondRefuse = counter > 1
                                && unit.pathLength() > 1
                                && unit.pathLength() < 6
                                && !Direction.isDiagonal(unit.peekHeading())
                                && unit.stepDrained()
                                && !unit.isMoving()
                                && unit.target() != null
                                && !World.battleNetRangedChaseUnit(unit)
                                && (unit.order() == Unit.Order.ATTACK
                                        || unit.order()
                                                == Unit.Order.ATTACK_MOVE
                                        || unit.chasing());
                        if (multiResidualSecondRefuse) {
                            int stridePeek =
                                    world.battleNetMovementStride(unit);
                            int peek = unit.peekHeading();
                            int peekX = unit.tileX()
                                    + Direction.deltaX(peek) * stridePeek;
                            int peekY = unit.tileY()
                                    + Direction.deltaY(peek) * stridePeek;
                            if (!world.canEnter(unit, peekX, peekY)) {
                                // Free-compass a closer neighbour while the
                                // residual peek is still blocked. Full
                                // pathfinder after the wait returned NW (or
                                // stale W once free); native SE@41 with SW
                                // wall-blocked and W still held by ally 1512.
                                // SE reduces Chebyshev distance even though it
                                // regresses west -- require only a strictly
                                // closer cell, not the no-regress wake used by
                                // one-heading soft-wait free-progress.
                                Unit quarry = unit.target();
                                int curDist = Math.max(
                                        Math.abs(quarry.tileX()
                                                - unit.tileX()),
                                        Math.abs(quarry.tileY()
                                                - unit.tileY()));
                                int strideDetour =
                                        world.battleNetMovementStride(unit);
                                int freeHeading = -1;
                                int bestDist = curDist;
                                for (int dir = 0; dir < Direction.COUNT;
                                        dir++) {
                                    int freeX = unit.tileX()
                                            + Direction.deltaX(dir)
                                            * strideDetour;
                                    int freeY = unit.tileY()
                                            + Direction.deltaY(dir)
                                            * strideDetour;
                                    if (!world.canEnter(
                                            unit, freeX, freeY)) {
                                        continue;
                                    }
                                    int dist = Math.max(
                                            Math.abs(quarry.tileX() - freeX),
                                            Math.abs(quarry.tileY()
                                                    - freeY));
                                    if (dist < bestDist) {
                                        bestDist = dist;
                                        freeHeading = dir;
                                    }
                                }
                                if (freeHeading < 0) {
                                    // No progressive free cell: fall back to
                                    // first free compass neighbour.
                                    for (int dir = 0; dir < Direction.COUNT;
                                            dir++) {
                                        int freeX = unit.tileX()
                                                + Direction.deltaX(dir)
                                                * strideDetour;
                                        int freeY = unit.tileY()
                                                + Direction.deltaY(dir)
                                                * strideDetour;
                                        if (world.canEnter(
                                                unit, freeX, freeY)) {
                                            freeHeading = dir;
                                            break;
                                        }
                                    }
                                }
                                if (freeHeading >= 0) {
                                    // Multi-step residual free-compass after
                                    // soft-wait: keep collision so wall soft-
                                    // clear still sees the elevated nibble
                                    // (90@34,40 c2; 76@31,39 after long hold).
                                    // One-heading leftovers (pathn1) still
                                    // refresh via setPath for grunt 1503.
                                    int keptColl =
                                            unit.battleNetCollisionCounter();
                                    int keptPathn = unit.pathLength();
                                    unit.replacePeekHeading(freeHeading);
                                    unit.clearPath();
                                    unit.setPath(new PathFinder.Path(
                                            PathFinder.Result.FOUND,
                                            new int[] {freeHeading}));
                                    if (keptPathn > 1 && keptColl >= 1) {
                                        unit.setBattleNetCollisionCounter(
                                                keptColl);
                                    }
                                    unit.setBattleNetChaseEmptyRouteReplan(
                                            false);
                                    unit.setBattleNetOrderDelay(3);
                                    return;
                                }
                                // Native's give-up is nine bytes -- 0x00450ad0 sets
                                // the route cursor to 20 -- and its caller then sets
                                // the movement timer to 1 and returns. Nothing else:
                                // no replan flag, no detour, no special delay, and the
                                // refusal counter is left where it is, because only
                                // the fifteenth clears it. The unit simply comes back
                                // next visit with no route and lays a fresh one.
                                unit.clearPath();
                                unit.setRouteSpent(false);
                                unit.setWaitCycles(0);
                                unit.setBattleNetOrderDelay(0);
                                return;
                            }
                            // Peek freed during the wait -- take residual
                            // rather than replan or re-arm soft-wait.
                            unit.setBattleNetCollisionCounter(0);
                            heading = peek;
                            nextX = peekX;
                            nextY = peekY;
                            canTakeStep = true;
                        } else if (counter <= 14) {
                            // Cooperative refuse is fourteen quiet visits
                            // (FUN_004379e0). A melee replan residual that is
                            // still armed also owes Attack-four (timer 3)
                            // after that wait before the first new heading:
                            // XHuman 12 grunt 1495 residual-settles with
                            // replan at fixture 19, native Move timer 15 +
                            // Attack-four 3, steps at 37; delay 14 alone
                            // stepped at fixture 34.
                            boolean replanHold = unit
                                    .battleNetChaseReplanResidualHold()
                                    && unit.pathLength() > 0
                                    && !World.battleNetRangedChaseUnit(unit);
                            if (replanHold) {
                                unit.setBattleNetChaseReplanResidualHold(
                                        false);
                            }
                            int quiet = World.battleNetCooperativeRefuseQuietVisits(
                                    replanHold);
                            // Nearly-full leftover residual-settled: one extra
                            // quiet visit so free-compass after this wait lands
                            // at fixture 37 not 36 (XHuman 12 grunt 1494).
                            if (unit.stepDrained() && !unit.isMoving()
                                    && unit.pathLength() == 6
                                    && !World.battleNetRangedChaseUnit(unit)) {
                                quiet = Math.max(quiet, 15);
                            }
                            unit.setBattleNetOrderDelay(quiet);
                            armBattleNetAttackRefusalMove(unit);
                            // Refused by an ally that is mid-step with nothing
                            // queued behind it: that ally stops where it lands,
                            // and native counts the wait out rather than taking
                            // the square the moment it frees. Every other
                            // refusal measured on xhuman-10 is against a blocker
                            // that still holds a route, or one already standing,
                            // which is why the hold is set only here.
                            Unit onward = world.unitAt(nextX, nextY);
                            if (onward != null && onward.isMoving()
                                    && onward.pathLength() == 0) {
                                unit.setBattleNetRefusalHold(true);
                            }
                            return;
                        }
                    }
                }
                // Melee attack-chase multi-step hard refuse: rebuild rather
                // than pop + PF_WAIT 10. XHuman 12 grunt 1496 (Java 104) held
                // SE,S,S,S from cycle 11, soft-waited on SE, then at the hard
                // refuse slept ten while native set route_index 20 and first-
                // stepped S onto (30,39) at fixture 25. Ranged first-approach
                // refuses still use ordinary PF_WAIT (axethrower 1490 @22);
                // drained ranged leftovers replan above when not a cooperative
                // ranged ally.
                //
                // Residual-settled short leftover (pathLength 2) refuse is
                // native route_index 20 then a fifteen-timer replan (XHuman 10
                // grunt 1486: RI 1→20 at fixture 37, replan + timer 15 at 38,
                // SE step at 53). Blanket residual delay 15 on all multi-step
                // hard refuses REG'd xhuman-12 @25. Empty-route soft-ally
                // free-detour (grunt 1507 @36) stays same-cycle.
                if (!canTakeStep
                        && !replanOnExhaustion
                        && unit.target() != null
                        && !World.battleNetRangedChaseUnit(unit)
                        && unit.pathLength() > 1
                        && (unit.order() == Unit.Order.ATTACK
                                || unit.order() == Unit.Order.ATTACK_MOVE
                                || unit.chasing())) {
                    // Residual-settled nearly-full leftover onto a non-
                    // cooperative (standing) ally on a diagonal peek: step a
                    // free cardinal component of that diagonal before hard
                    // clear. XHuman 12 grunt 1506 lands SE at (28,40) with
                    // leftover pathn 5 starting NE onto standing ally 105 at
                    // (29,39) while N at (28,39) is free after 105 vacated;
                    // cooperative soft-wait never arms because the ally is
                    // not mid-MOVE, so hard clear+replan looped NE until
                    // fixture 52. Native steps N at fixture 39 once residual
                    // has settled.
                    //
                    // Only diagonal peeks, and only their axis components
                    // for the ordinary free-component seam: open free-compass
                    // (any free dir) same-cycle REG'd grunt 1512 at fixture
                    // 35 -- N onto axe 1524 was blocked, free NE detoured
                    // five cycles before native's NE at 40.
                    // pathLength 2 keeps the short-leftover timer-15 arm
                    // below (XHuman 10 grunt 1486).
                    //
                    // When the blocked diagonal has no free axis component
                    // (1512 SE onto 99 with S terrain-blocked and E occupied;
                    // SW onto 96 with S terrain-blocked and W occupied) but a
                    // free off-axis cell exists (NE) and a melee replan
                    // residual hold is still armed from the retarget that
                    // tore the leftover, free-compass that cell after delay 4
                    // (Attack-four plus one quiet refuse). Blind hard
                    // clear+replan looped until empty-route free-detour at 55;
                    // native NE at 40. Same-cycle free-compass REG'd at 35;
                    // delay 2 stepped at 38.
                    if (unit.stepDrained() && !unit.isMoving()
                            && unit.pathLength() >= 5
                            && Direction.isDiagonal(heading)) {
                        Unit hardBlocker = world.unitAt(nextX, nextY);
                        boolean allyHard = hardBlocker != null
                                && hardBlocker != unit
                                && hardBlocker.isOnMap()
                                && !hardBlocker.isDying()
                                && world.isAllied(unit.player(),
                                        hardBlocker.player());
                        if (allyHard) {
                            int strideDetour =
                                    world.battleNetMovementStride(unit);
                            int peekDx = Direction.deltaX(heading);
                            int peekDy = Direction.deltaY(heading);
                            int freeHeading = -1;
                            for (int dir = 0; dir < Direction.COUNT; dir++) {
                                if (Direction.isDiagonal(dir)) {
                                    continue;
                                }
                                int stepDx = Direction.deltaX(dir);
                                int stepDy = Direction.deltaY(dir);
                                // Cardinal component of the blocked diagonal.
                                if ((stepDx != 0 && stepDx != peekDx)
                                        || (stepDy != 0 && stepDy != peekDy)) {
                                    continue;
                                }
                                int freeX = unit.tileX()
                                        + stepDx * strideDetour;
                                int freeY = unit.tileY()
                                        + stepDy * strideDetour;
                                if (world.canEnter(unit, freeX, freeY)) {
                                    freeHeading = dir;
                                    break;
                                }
                            }
                            boolean replanHold = unit
                                    .battleNetChaseReplanResidualHold()
                                    && !World.battleNetRangedChaseUnit(unit);
                            boolean offAxisRescue = false;
                            if (freeHeading < 0 && replanHold) {
                                for (int dir = 0; dir < Direction.COUNT;
                                        dir++) {
                                    int freeX = unit.tileX()
                                            + Direction.deltaX(dir)
                                            * strideDetour;
                                    int freeY = unit.tileY()
                                            + Direction.deltaY(dir)
                                            * strideDetour;
                                    if (world.canEnter(unit, freeX, freeY)) {
                                        freeHeading = dir;
                                        offAxisRescue = true;
                                        break;
                                    }
                                }
                            }
                            if (freeHeading >= 0) {
                                // One quiet refuse after residual settle
                                // before the component step. Same-cycle free-
                                // compass put 1506 at (28,39) on fixture 38;
                                // native still sits (28,40) and steps N at 39.
                                // orderDelay 1 added a combat-only visit and
                                // stepped late at fixture 40; coll alone keeps
                                // the path and retries next movement visit.
                                //
                                // Off-axis free-compass under replan residual
                                // hold (1512): pay Attack-four plus one quiet
                                // refuse as delay 4 so combat quiet visits land
                                // the step at fixture 40 (refuse sets delay;
                                // four decrement returns, then free visit
                                // steps). Delay 2 stepped at fixture 38; same-
                                // cycle free-compass REG'd at 35; coll-only
                                // quiet at 38. Axis component (1506 N) keeps
                                // coll quiet even if a stale replan flag is
                                // set.
                                if (offAxisRescue) {
                                    unit.replacePeekHeading(freeHeading);
                                    unit.setBattleNetChaseReplanResidualHold(
                                            false);
                                    unit.setBattleNetCollisionCounter(0);
                                    unit.setBattleNetOrderDelay(4);
                                    return;
                                }
                                if (unit.battleNetCollisionCounter() == 0) {
                                    unit.setBattleNetCollisionCounter(1);
                                    return;
                                }
                                unit.replacePeekHeading(freeHeading);
                                unit.setBattleNetCollisionCounter(0);
                                heading = freeHeading;
                                nextX = unit.tileX()
                                        + Direction.deltaX(heading)
                                        * strideDetour;
                                nextY = unit.tileY()
                                        + Direction.deltaY(heading)
                                        * strideDetour;
                                canTakeStep = true;
                            }
                        }
                    }
                    if (!canTakeStep) {
                        // Residual-settled pathLength-2 refuse after a prior
                        // soft refuse raised collision: native route_index 20
                        // then timer 15 before replan (XHuman 10 grunt 1486:
                        // fixture 37→38→53 SE, coll already 1). Immediate
                        // clear+empty-route free-detour stepped N at 38.
                        // pathLen-2 residual delay without the coll gate REG'd
                        // xhuman-12 @25.
                        boolean residualShortLeftover = unit.stepDrained()
                                && !unit.isMoving()
                                && unit.pathLength() == 2
                                && unit.battleNetCollisionCounter() > 0;
                        // Terrain-blocked multi-step residual with free
                        // neighbours: install free heading and pay Attack-four
                        // delay 4 (XHuman 12 grunt 1510 SW onto 0x31b; free SE;
                        // native Attack-four then SE@41). Same-cycle free-
                        // compass REG'd xh12@40. Delay 4 matches 1512 off-axis
                        // rescue timing.
                        if (!residualShortLeftover
                                && unit.stepDrained()
                                && !unit.isMoving()
                                && unit.pathLength() >= 4) {
                            int strideDetour =
                                    world.battleNetMovementStride(unit);
                            int freeHeading = -1;
                            for (int dir = 0; dir < Direction.COUNT; dir++) {
                                int freeX = unit.tileX()
                                        + Direction.deltaX(dir) * strideDetour;
                                int freeY = unit.tileY()
                                        + Direction.deltaY(dir) * strideDetour;
                                if (world.canEnter(unit, freeX, freeY)) {
                                    freeHeading = dir;
                                    break;
                                }
                            }
                            if (freeHeading >= 0) {
                                int keptColl =
                                        unit.battleNetCollisionCounter();
                                int keptPathn = unit.pathLength();
                                unit.replacePeekHeading(freeHeading);
                                unit.clearPath();
                                unit.setPath(new PathFinder.Path(
                                        PathFinder.Result.FOUND,
                                        new int[] {freeHeading}));
                                if (keptPathn > 1 && keptColl >= 1) {
                                    unit.setBattleNetCollisionCounter(
                                            keptColl);
                                }
                                unit.setBattleNetOrderDelay(4);
                                return;
                            }
                        }
                        // Native's give-up is nine bytes -- 0x00450ad0 sets
                        // the route cursor to 20 -- and its caller then sets
                        // the movement timer to 1 and returns. The unit comes
                        // back next visit with no route and lays a fresh one.
                        //
                        // The caller is not silent about the count, though,
                        // and this arm used to read it as though it were.
                        // 0x00437a0d adds 0x1000 to the word at 0x1c in
                        // fcn.004379e0's entry block, before the player-table
                        // arm and before any band, so it dominates every exit
                        // the handler has: giving the route up is a refusal
                        // and is counted like one. Grunt 1505 in XHuman 4 is
                        // the witness. It stands on 77,61 under an attack
                        // order and retail parks its route on cycle 24 with
                        // the nibble going 1 to 2, refuses again every cycle
                        // to 7, and on the eighth at cycle 30 stops for
                        // fifteen; this implementation cleared the route on the same
                        // cycle 24 with both counters frozen at what they
                        // were, so it never reached the eighth and walked off
                        // 77,61 at 39 -- and axethrower 1516 behind it found
                        // the square free and went north where retail plans
                        // west around it.
                        int refusals = battleNetRefuse(unit);
                        unit.setRouteSpent(false);
                        // Retail leaves the movement timer at fifteen on the
                        // eighth refusal, and the timer a unit actually reads
                        // is the one its own order dispatcher drains: a chaser
                        // returns on the order delay in stepAttack, not on the
                        // wait. E13 measured that this implementation's delay is quiet
                        // on the visit it reaches nought as well, so retail's
                        // fifteen is fourteen here.
                        unit.setWaitCycles(0);
                        unit.setBattleNetOrderDelay(refusals >= 8 ? 14 : 0);
                        return;
                    }
                }
                if (canTakeStep) {
                    unit.setBattleNetChaseEmptyRouteReplan(false);
                } else
                // Wood residual settle: native 0x450350 rewrites the route
                // head with DAT_00490e88 when the leftover is blocked by an
                // ally. XHuman 11 peon 1584 at 10,6 holds SE onto ally 11,7;
                // lastStepHeading NE + peek SE yields shortcut E onto 11,6
                // (authenticated route write 1→2 at cycle 38). The refuse on
                // the settle visit itself is quiet (route_index 20); the
                // rewrite steps on the following visit. Collision counters
                // from earlier path refusals must not skip that quiet visit.
                if (!canTakeStep && !goldApproach
                        && unit.resourceUnit() == null
                        && unit.resourceTileX() >= 0
                        && unit.resourceTileY() >= 0
                        && unit.stepDrained()
                        && unit.pathLength() > 0
                        && unit.lastStepHeading() < Direction.COUNT) {
                    Unit woodBlocker = world.unitAt(nextX, nextY);
                    boolean woodAlly = woodBlocker != null
                            && woodBlocker != unit
                            && woodBlocker.isOnMap()
                            && !woodBlocker.isDying()
                            && world.isAllied(unit.player(),
                                    woodBlocker.player());
                    if (woodAlly) {
                        // Residual just cleared this visit: mark the seam and
                        // do not step (native route_index 20 at fixture 37).
                        if (walkedThisCycle) {
                            return;
                        }
                        int shortcut = BattleNetPathFinder.twoHeadingShortcut(
                                unit.lastStepHeading(), heading);
                        if (shortcut >= 0) {
                            int shortX = unit.tileX()
                                    + Direction.deltaX(shortcut)
                                    * world.battleNetMovementStride(unit);
                            int shortY = unit.tileY()
                                    + Direction.deltaY(shortcut)
                                    * world.battleNetMovementStride(unit);
                            if (world.canEnter(unit, shortX, shortY)) {
                                unit.replacePeekHeading(shortcut);
                                heading = shortcut;
                                nextX = shortX;
                                nextY = shortY;
                                canTakeStep = true;
                            }
                        }
                        if (!canTakeStep) {
                            unit.clearPath();
                            unit.setBattleNetOrderDelay(0);
                            return;
                        }
                    }
                }
                if (!canTakeStep)
                // A terrain-resource walker that wakes from the preserved
                // cooperative refusal does not enter another ordinary
                // PF_WAIT when the same first square is still occupied.
                // Native XHuman 11 slot 1584 wakes at c20 with collision 1,
                // invalidates the cached E as collision 2, and replans/moves
                // NE on c21. The generic arm below popped E and slept ten
                // more cycles. This is the second-refusal resource seam;
                // first refusals were handled (and returned) just above.
                if (!goldApproach && unit.resourceUnit() == null
                        && unit.resourceTileX() >= 0 && unit.resourceTileY() >= 0
                        && unit.battleNetCollisionCounter() > 0) {
                    int counter = unit.battleNetCollisionCounter() + 1;
                    unit.setBattleNetCollisionCounter(
                            counter > 14 ? 0 : counter);
                    unit.clearPath();
                    unit.setBattleNetOrderDelay(0);
                    return;
                }
                // Resource approach plans S,SE through a Move-animation ally
                // (XHuman 12 1554 via 1550) then refuses the solid first step.
                // ChonkCraft PF_WAIT 10 slept through fixture 12; free-neighbor
                // detours took SW too early; clear+replan every cycle climbed
                // the harvest shove ladder and burned the seed. Native keeps
                // the route and on the wait-1 collision seam can take a later
                // stored heading once it frees (route 04,03 → step SE at c12
                // when 1549 leaves 6,27). Gate on resourceUnit only:
                // walkTowards borrows MOVE for the step. Use
                // battleNetOrderDelay so resourceWalkWaited does not run.
                if (unit.resourceUnit() != null && !unit.returningToDepot()
                        && unit.resourceUnit().type() != null
                        && unit.resourceUnit().type().givesResource() == UnitType.Resource.GOLD) {
                    // Gold-mine approach only: soft-planned through a mover,
                    // solid at step (XHuman 12 1554). Wood / oil keeps the
                    // ordinary PF_WAIT arm so reverse-free choppers (1511)
                    // stay on their sealed first steps.
                    Unit allyBlocker = world.unitAt(nextX, nextY);
                    boolean alliedOccupancy = allyBlocker != null
                            && allyBlocker != unit
                            && allyBlocker.isOnMap()
                            && !allyBlocker.isDying()
                            && world.isAllied(unit.player(), allyBlocker.player());
                    if (alliedOccupancy) {
                        int counter = unit.battleNetCollisionCounter() + 1;
                        if (counter > 14) {
                            unit.setBattleNetCollisionCounter(0);
                            unit.clearPath();
                            unit.setBattleNetOrderDelay(0);
                            return;
                        }
                        unit.setBattleNetCollisionCounter(counter);
                        // Spend the refused heading and try later stored
                        // headings only when they strictly close Chebyshev to
                        // the approach (1554: refuse S, take SE onto 6,27).
                        Unit resource = unit.resourceUnit();
                        int[] approach = world.battleNetApproachPoint(unit, resource);
                        int curChebyshev = Math.max(
                                Math.abs(approach[0] - unit.tileX()),
                                Math.abs(approach[1] - unit.tileY()));
                        // Orc 12 peon 1521: residual-settled multi-step leftover
                        // W,W,SW refused onto cooperative mover peon 75 at W
                        // while free on-path SW onto 85,42 also closes
                        // Chebyshev. FUN_004379e0 coll 1..7 keep one quiet OP0
                        // each (timer 1); coll>=8 arms the fifteen-count
                        // replan hold (Java stores fourteen remaining quiet
                        // visits) then rebuilds SW (native fixture 46).
                        // SoftDelay 14 then retried free W once 75 left
                        // (fixture 39). Arm only when residual refuse sees a
                        // cooperative blocker AND a free closer neighbour
                        // that is already on the leftover path: free detour
                        // skips cooperative allies, so without this hold the
                        // soft-wait wins and takes stale W. Standing jam
                        // free-detours at coll 2 (XORc 2 peon 1563). A
                        // cooperative corridor soft-wait whose free closer is
                        // off-path (XORc 2 peon 1561 SW while leftover is
                        // S,S...) keeps softDelay 14. Separate flag from
                        // woodRouteIndex20 so gold mid-route one-cycle quiet
                        // cannot clear it.
                        if (curChebyshev > 2 && unit.stepDrained()
                                && unit.pathLength() >= 3
                                && (walkedThisCycle
                                        || unit.battleNetFarMultiStepResidualRefuse())) {
                            if (walkedThisCycle
                                    && world.battleNetCooperativeBlocker(
                                            unit, allyBlocker)) {
                                // Orc 12 refuses axis W with free on-path
                                // diagonal SW on a three-step leftover
                                // (pathLength == 3). Longer corridors with a
                                // free diagonal (XHuman 04 peon 1567) keep
                                // softDelay 14.
                                int refusedDx = Direction.deltaX(heading);
                                int refusedDy = Direction.deltaY(heading);
                                boolean refusedAxis = (refusedDx == 0)
                                        != (refusedDy == 0);
                                int probeStride = world.battleNetMovementStride(unit);
                                boolean freeDiagonalOnPathCloser = false;
                                if (refusedAxis && unit.pathLength() == 3) {
                                    for (int depth = 1; depth < unit.pathLength();
                                            depth++) {
                                        int tail = unit.peekHeadingAtDepth(depth);
                                        if (tail < 0) {
                                            continue;
                                        }
                                        int ddx = Direction.deltaX(tail);
                                        int ddy = Direction.deltaY(tail);
                                        if (ddx == 0 || ddy == 0) {
                                            continue;
                                        }
                                        int probeX = unit.tileX()
                                                + ddx * probeStride;
                                        int probeY = unit.tileY()
                                                + ddy * probeStride;
                                        int probeDist = Math.max(
                                                Math.abs(approach[0] - probeX),
                                                Math.abs(approach[1] - probeY));
                                        if (probeDist >= curChebyshev) {
                                            continue;
                                        }
                                        if (world.harvest.canEnterBattleNetResourceTarget(
                                                unit, probeX, probeY)) {
                                            freeDiagonalOnPathCloser = true;
                                            break;
                                        }
                                    }
                                }
                                if (freeDiagonalOnPathCloser) {
                                    unit.setBattleNetFarMultiStepResidualRefuse(true);
                                }
                            }
                            if (unit.battleNetFarMultiStepResidualRefuse()) {
                                if (counter < 8) {
                                    unit.setBattleNetOrderDelay(0);
                                    return;
                                }
                                unit.setBattleNetFarMultiStepResidualRefuse(false);
                                unit.clearPath();
                                unit.setRouteSpent(false);
                                unit.setBattleNetCollisionCounter(0);
                                // Fifteen-count native timer; Java stores the
                                // remaining quiet visits after this refuse.
                                unit.setBattleNetOrderDelay(14);
                                return;
                            }
                        }
                        boolean progressiveSecond = false;
                        if (unit.pathLength() > 1) {
                            int second = unit.peekHeadingAfterNext();
                            int secondX = unit.tileX() + Direction.deltaX(second)
                                    * world.battleNetMovementStride(unit);
                            int secondY = unit.tileY() + Direction.deltaY(second)
                                    * world.battleNetMovementStride(unit);
                            int secondChebyshev = Math.max(
                                    Math.abs(approach[0] - secondX),
                                    Math.abs(approach[1] - secondY));
                            progressiveSecond = (secondX != nextX
                                    || secondY != nextY)
                                    && secondChebyshev < curChebyshev;
                        }
                        // A worker refused by an ally that is not walking
                        // somewhere else refuses, in retail's sense: the route
                        // is parked and it plans again next visit.
                        //
                        // Two arms used to stand here, both scanning the
                        // compass for a free neighbour that closed the
                        // distance to the mine and rewriting the head of the
                        // route to it. Retail has no such rule. XOrc 2's peon
                        // 1563 was what they were fitted to, and it reaches
                        // (85,36) on fixture 25 either way -- but only when it
                        // refuses does it get there holding SE,S,S,SE,E, which
                        // is retail's route heading for heading, because
                        // retail's square comes from replanning and not from a
                        // sidestep. Orc 12's peon 1521, the other unit named
                        // in those comments, went from thirty-seven
                        // unit-cycles out of place to two. The scan itself
                        // decided nothing: removing it moved no case by a
                        // single cycle, so it only ever gated the wait.
                        if (!canTakeStep
                                && !world.battleNetCooperativeBlocker(
                                        unit, allyBlocker)
                                && (curChebyshev > 2
                                        || (unit.pathLength() == 1
                                                && unit.stepDrained()
                                                && !unit.isMoving()))) {
                            battleNetRefuse(unit);
                            return;
                        }
                        // A repeated/non-progressive tail is the ordinary
                        // cooperative-refusal case: keep the refused heading
                        // and wake when its builder ally has moved (1511).
                        // S,SE near a mine is the separate progressive-alt
                        // seam below (1554), which must consume S and take SE.
                        // A far mine walk keeps its cached first heading on
                        // any allied refusal, even when the blocker is not a
                        // cooperative mover. XHuman 11 slot 1492 retries the
                        // same occupied SW at c21 and raises collision 2;
                        // clearing here instead replanned a free W and moved.
                        // Near the approach retain the stricter cooperative
                        // test so the S,SE progressive-alt seam still works.
                        if (!canTakeStep) {
                        if (curChebyshev > 2
                                || (!progressiveSecond
                                && world.battleNetCooperativeBlocker(
                                        unit, allyBlocker))) {
                            // Far cooperative soft-waits keep the generic
                            // fourteen quiet visits (FUN_004379e0). The first
                            // near-mine refuse does too: a blanket short wait
                            // stepped peon 1553 two cycles early (fixture 23
                            // vs native 25) and regressed the sealed h24 floor.
                            // After the first full wait, a second near refuse
                            // uses native's route_index-20 cadence with anim
                            // timer 6→1 through fixtures 19..24, then the SW
                            // step at 25 once blocker 1550 has left (5,27) at
                            // fixture 22. A second fourteen slept past that
                            // free window. counter is already incremented
                            // above; >1 means this is the free-window retry.
                            boolean nearApproach = curChebyshev <= 2;
                            world.causalTrace.predicate(world.cycle, unit.id(),
                                    "gold.refuse.near-approach",
                                    "max(abs(sub(resource.approach_x,unit.x)),"
                                            + "abs(sub(resource.approach_y,unit.y)))",
                                    curChebyshev, "<=", "2", 2,
                                    nearApproach, "select refusal delay 6 or 14");
                            int softDelay = (nearApproach && counter > 1)
                                    ? 6 : 14;
                            unit.setBattleNetOrderDelay(softDelay);
                            // Residual-settle replan often leaves a short path
                            // (one free heading). Free-wake only those.
                            // Residual-settle replan often leaves a single
                            // free heading. Free-wake only that short path
                            // when delay 14 was armed near the mine (XHuman 7
                            // peon 1446). Longer leftovers and mid-path soft-
                            // waits count out fully.
                            unit.setBattleNetGoldSoftWaitFreeWake(
                                    softDelay > 6 && nearApproach
                                            && unit.pathLength() == 1);
                            return;
                        }
                        // Only the immediate next stored heading, and only
                        // when it targets a different cell than the refused
                        // step (S,SE → try SE). Repeated SW,SW would re-test
                        // the same square and must not arm the fast replan.
                        int refusedX = nextX;
                        int refusedY = nextY;
                        boolean hadProgressiveAlt = false;
                        unit.popHeading();
                        if (unit.pathLength() > 0) {
                            int alt = unit.peekHeading();
                            int altX = unit.tileX() + Direction.deltaX(alt)
                                    * world.battleNetMovementStride(unit);
                            int altY = unit.tileY() + Direction.deltaY(alt)
                                    * world.battleNetMovementStride(unit);
                            int altChebyshev = Math.max(
                                    Math.abs(approach[0] - altX),
                                    Math.abs(approach[1] - altY));
                            boolean differentCell = altX != refusedX
                                    || altY != refusedY;
                            if (differentCell
                                    && altChebyshev < curChebyshev) {
                                hadProgressiveAlt = true;
                                // Near-approach only: farther peons must not
                                // consume a later stored diagonal early.
                                if (curChebyshev <= 2
                                        && world.harvest.canEnterBattleNetResourceTarget(
                                                unit, altX, altY)) {
                                    heading = alt;
                                    nextX = altX;
                                    nextY = altY;
                                    canTakeStep = true;
                                }
                            }
                        }
                        if (!canTakeStep) {
                            unit.clearPath();
                            // Near the approach (cheb <= 2) a progressive next
                            // heading (S,SE for 1554) replans every cycle so
                            // SE is taken the tick it frees. Farther gold walks
                            // (XHuman 7/11 peons) and non-progressive remains
                            // use cooperative wait 15 so they do not step early.
                            if (hadProgressiveAlt && counter < 8
                                    && curChebyshev <= 2) {
                                unit.setBattleNetOrderDelay(0);
                            } else {
                                // Fourteen, not fifteen. Retail writes 15 to
                                // the timer and the visit that takes it from
                                // 1 to 0 is the one that acts, so the sleep is
                                // fifteen quiet visits; this implementation's delay is
                                // quiet on the visit it reaches nought as
                                // well, so fifteen here is sixteen. XHuman
                                // 12's peon 1553 arms on fixture 10, and
                                // retail steps it south-west at 25.
                                unit.setBattleNetOrderDelay(
                                        counter >= 8 ? 14 : 0);
                            }
                            return;
                        }
                        }
                        // Detour or progressive alternate free: fall through.
                    }
                }
                // Residual-settled one-heading melee chase leftover onto a
                // standing ally: free-progress detour before PF_WAIT 10.
                // XHuman 12 grunt 1375 (Java 225) residual of E@23 lands at
                // (11,85) with leftover E onto standing ally 1379 at (12,85)
                // while free SE onto (12,86) matches native's third step at
                // fixture 40. Non-MOVE allies never arm cooperative soft-wait,
                // so PF_WAIT popped the leftover and slept ten -- third step
                // only at fixture 50. Soft-wait free-progress (pathn==1 under
                // delay) never ran. One coll quiet then free-progress matches
                // residual settle at 39 and native SE at 40.
                if (!canTakeStep
                        && unit.stepDrained()
                        && !unit.isMoving()
                        && unit.pathLength() == 1
                        && unit.target() != null
                        && !World.battleNetRangedChaseUnit(unit)
                        && (unit.order() == Unit.Order.ATTACK
                                || unit.order() == Unit.Order.ATTACK_MOVE
                                || unit.chasing())) {
                    Unit hardBlocker = world.unitAt(nextX, nextY);
                    // A chase route is allowed to end on its quarry. When
                    // the last cached heading names the target's occupied
                    // square and the mover is already in melee range, retail
                    // reports arrival instead of a refused step. XHuman 9's
                    // skeleton 1431 reaches (13,120) beside footman 1427 with
                    // final S still cached: BNE enters Attack@1188 on fixture
                    // 46 and strikes at 55. Treating S as an ordinary body
                    // collision armed the 23-cycle refusal wait and postponed
                    // the first Java blow until fixture 81.
                    if (arriveMeleeLeftoverOnOccupiedQuarry(unit)) {
                        return;
                    }
                    boolean allyHard = hardBlocker != null
                            && hardBlocker != unit
                            && hardBlocker.isOnMap()
                            && !hardBlocker.isDying()
                            && world.isAllied(unit.player(),
                                    hardBlocker.player())
                            && !world.battleNetCooperativeBlocker(unit,
                                    hardBlocker);
                    if (allyHard) {
                        Unit quarry = unit.target();
                        int curDist = Math.max(
                                Math.abs(quarry.tileX() - unit.tileX()),
                                Math.abs(quarry.tileY() - unit.tileY()));
                        int goalDx = Integer.signum(
                                quarry.tileX() - unit.tileX());
                        int goalDy = Integer.signum(
                                quarry.tileY() - unit.tileY());
                        int strideDetour = world.battleNetMovementStride(unit);
                        int freeHeading = -1;
                        for (int dir = 0; dir < Direction.COUNT; dir++) {
                            int freeX = unit.tileX()
                                    + Direction.deltaX(dir) * strideDetour;
                            int freeY = unit.tileY()
                                    + Direction.deltaY(dir) * strideDetour;
                            if (!world.canEnter(unit, freeX, freeY)) {
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
                            boolean noRegress = (goalDx == 0
                                    || stepDx != -goalDx)
                                    && (goalDy == 0 || stepDy != -goalDy);
                            boolean progresses = (goalDx != 0
                                    && stepDx == goalDx)
                                    || (goalDy != 0 && stepDy == goalDy);
                            if (progresses && noRegress) {
                                freeHeading = dir;
                                break;
                            }
                        }
                        if (freeHeading >= 0) {
                            if (unit.battleNetCollisionCounter() == 0) {
                                unit.setBattleNetCollisionCounter(1);
                                return;
                            }
                            unit.replacePeekHeading(freeHeading);
                            unit.setBattleNetCollisionCounter(0);
                            heading = freeHeading;
                            nextX = unit.tileX()
                                    + Direction.deltaX(heading) * strideDetour;
                            nextY = unit.tileY()
                                    + Direction.deltaY(heading) * strideDetour;
                            canTakeStep = true;
                        }
                    }
                    // Residual-settled pathn1 with no free-progress (standing
                    // ally, no progressive free neighbour): native RI20 replan
                    // (XHuman 12 grunt 1514 N@42). PF_WAIT 10 held until ~52.
                    // Free-progress cases (grunt 1375 SE) keep the arm above.
                    if (!canTakeStep
                            && world.actionMoveWalked
                            && unit.stepDrained()
                            && !unit.isMoving()
                            && unit.pathLength() == 1
                            && unit.target() != null
                            && !World.battleNetRangedChaseUnit(unit)
                            && (unit.order() == Unit.Order.ATTACK
                                    || unit.order() == Unit.Order.ATTACK_MOVE
                                    || unit.chasing())) {
                        if (System.getenv("CHONKCRAFT_TRACE_BNE_RESIDUAL") != null) {
                            String resEnv = System.getenv(
                                    "CHONKCRAFT_TRACE_BNE_RESIDUAL").trim();
                            if ("*".equals(resEnv)
                                    || unit.id() == Integer.parseInt(resEnv)) {
                                System.err.printf(
                                        "JBNERESREPLAN cycle=%d unit=%d "
                                                + "at=%d,%d pathn=1 "
                                                + "allyHard=%d walked=%d%n",
                                        world.cycle, unit.id(),
                                        unit.tileX(), unit.tileY(),
                                        allyHard ? 1 : 0,
                                        world.actionMoveWalked ? 1 : 0);
                            }
                        }
                        unit.clearPath();
                        unit.setRouteSpent(false);
                        // A melee chaser already beside its quarry sleeps on
                        // the step it could not take instead of standing free
                        // to swing. Native's grunt 1448 marks route index 20
                        // and its offset-7 timer reads 23 counting down, and
                        // it never strikes the footman beside it; every one of
                        // the fifteen 23s in the captures is a refused melee
                        // chaser.
                        unit.setWaitCycles(
                                unit.type().maxAttackRange() <= 1
                                        && world.attackDistance(
                                                unit, unit.target()) <= 1
                                        ? BNE_MELEE_REFUSAL_HOLD : 0);
                        unit.setBattleNetCollisionCounter(0);
                        unit.setBattleNetChaseEmptyRouteReplan(true);
                        unit.setBattleNetOrderDelay(0);
                        return;
                    }
                }
                if (!canTakeStep) {
                // The last square of a path is allowed to be occupied: the
                // planner lets a route end on a building or on somebody's
                // head so that an order aimed at one still sets off. What it
                // has always relied on, and what was never written, is that
                // the walk stops short on arrival. Without it a unit that
                // asked for an occupied square waited, re-planned, was handed
                // the same square, and repeated that until the game ended.
                // A march gives up when what is in its way is standing on the
                // square it was going to and is not going to move. That test
                // is upstream's, and it lives in COrder_Move::Execute's
                // PF_WAIT arm and nowhere else

                //
                //   blocker = UnitOnMapTile(this->goalPos...)
                //   if (dist == 1 && (IsEnemy(blocker) || blocker->Moving == 0))
                //       { unit.Wait = 0; this->Finished = true; }
                //
                // All three parts earn their place. On the goal square, so a
                // body met halfway is waited out rather than treated as an
                // arrival; one square away, so the unit really is as close as
                // it is going to get; and not moving, so a queue shuffling
                // forward is not mistaken for a wall.
                //
                // What this implementation had instead was "the last square of a route
                // may be occupied, so stop when it is", which is none of the
                // three and applied to chases as well. It cost
                // (3)critter-attack 657 findings.
                //
                // The gate is upstream's placement rather than a measured
                // difference: with the rule stated properly a chase cannot
                // satisfy it anyway, because a chaser one square from its goal
                // is already in range and swinging. It stays because that is
                // where the code lives.
                if (replanOnExhaustion) {
                    Unit blocker = world.unitAt(unit.pathGoalX(), unit.pathGoalY());
                    // Standing means upstream's {@code Moving == 0}, and that
                    // is a state the step sets rather than the drawing
                    // offset: it outlives the drained pixels by the walk
                    // animation's unbreakable tail. On (3)critter-attack the
                    // blocker's last two pixels drain during cycle 521 and
                    // upstream reads it as moving until 524, so its neighbour
                    // gives up on it at the wake after, 532 -- where this
                    // port, asking the offsets, finished at 521 and wandered
                    // on numbers upstream had not drawn.
                    if (blocker != null && blocker != unit
                            && unit.distanceTo(blocker) == 1
                            && (World.isEnemy(unit, blocker) || !blocker.walkHolding())) {
                        unit.setWaitCycles(0);
                        unit.clearPath();
                        world.finishOrder(unit);
                        return;
                    }
                }
                // Wait for the way to clear. NextPathElement
                // The game answers PF_WAIT when
                // the next square is occupied, and DoActionMove turns that
                // into unit.Wait = 10 --
                // ten cycles asleep, during which COrder::IsWaiting stands the
                // unit up and breathes its Still animation over the top.
                //
                // Throwing the route away on the first bump, which is what
                // this did, is half of why two units sent through each other
                // stood and looked at one another: both re-planned into the
                // same corridor and neither ever spent a cycle waiting.
                //
                // Upstream also spends the refused square rather than keeping
                // it: NextPathElement decrements output.Length at the top of
                // every call that reuses the cache and only then reads the
                // direction, so the next attempt tries the square after the
                // one that was refused, from where the unit still stands.
                //
                // Fast looks like a retry budget, but in this LegacyEngine
                // revision it is only a cycling counter. Both blocked arms
                // assign PF_WAIT (zero), then the apparent expiry branch is
                // guarded by `output.Fast == 0 && result != 0`; that branch
                // can therefore never run. The exact sequence across blocked
                // cached elements is 0,10,9...,1,0,10... with no early
                // NewPath. levelx12h's grunt 105 proves the distinction: it
                // consumes eleven blocked headings through cycle 467 and
                // must consume the twelfth at 478, where treating Fast as a
                // real budget discarded the cache and stepped north-west.
                // Pure MOVE multi-step residual after a soft-wait, blocked by
                // an ally that is no longer cooperative (coll!=0 or not mid
                // Move animation): free-compass toward pathGoal instead of
                // PF_WAIT 10. XHuman 12 ogre 1527 soft-waited@w33 on E onto
                // axe, then at w48 ally ogre 1498 had coll!=0 so cooperative
                // was false and PF_WAIT popped E -- native free-compassed NE
                // at fixture 47 (Java stayed until 57). coll>=1 marks the
                // prior soft-wait; first refuse still waits.
                if (unit.stepDrained()
                        && !unit.isMoving()
                        && unit.pathLength() > 1
                        && unit.order() == Unit.Order.MOVE
                        && !World.battleNetRangedChaseUnit(unit)
                        && unit.battleNetCollisionCounter() >= 1
                        && unit.pathGoalX() >= 0
                        && unit.pathGoalY() >= 0) {
                    Unit ally = world.unitAt(nextX, nextY);
                    boolean alliedBlock = ally != null
                            && ally != unit
                            && ally.isOnMap()
                            && !ally.isDying()
                            && world.isAllied(unit.player(), ally.player());
                    if (alliedBlock) {
                        int strideDetour =
                                world.battleNetMovementStride(unit);
                        int curDist = Math.max(
                                Math.abs(unit.pathGoalX() - unit.tileX()),
                                Math.abs(unit.pathGoalY() - unit.tileY()));
                        int freeHeading = -1;
                        int bestDist = curDist;
                        for (int dir = 0; dir < Direction.COUNT; dir++) {
                            int freeX = unit.tileX()
                                    + Direction.deltaX(dir) * strideDetour;
                            int freeY = unit.tileY()
                                    + Direction.deltaY(dir) * strideDetour;
                            if (!world.canEnter(unit, freeX, freeY)) {
                                continue;
                            }
                            int dist = Math.max(
                                    Math.abs(unit.pathGoalX() - freeX),
                                    Math.abs(unit.pathGoalY() - freeY));
                            if (dist < bestDist) {
                                bestDist = dist;
                                freeHeading = dir;
                            }
                        }
                        if (freeHeading < 0) {
                            for (int dir = 0; dir < Direction.COUNT; dir++) {
                                int freeX = unit.tileX()
                                        + Direction.deltaX(dir) * strideDetour;
                                int freeY = unit.tileY()
                                        + Direction.deltaY(dir) * strideDetour;
                                if (world.canEnter(unit, freeX, freeY)) {
                                    freeHeading = dir;
                                    break;
                                }
                            }
                        }
                        if (freeHeading >= 0) {
                            // Install the free heading and return without
                            // stepping: the next movement visit takes NE
                            // when canEnter is true (ogre 1527 native@47).
                            // Same-cycle step landed@46; orderDelay 1@48.
                            unit.replacePeekHeading(freeHeading);
                            unit.setBattleNetCollisionCounter(0);
                            unit.setWaitCycles(0);
                            return;
                        }
                    }
                }
                battleNetRefuse(unit);
                return;
                }
            }

            // The way was clear, so the patience resets: upstream's
            // "if (result != PF_WAIT) output.Fast = 0".
            unit.setPathWaitBudget(0);
            stepped = true;
            unit.popHeading();
            int priorX = unit.tileX();
            int priorY = unit.tileY();
            world.markOccupancy(unit, false);
            world.markSight(unit, false);
            unit.setTile(nextX, nextY);
            world.markOccupancy(unit, true);
            world.unitCountSeen(unit);
            world.markSight(unit, true);
            // Native walks the unit pool so a mover is often visited before
            // the idle defender. Java reverse-creation can let that defender
            // spend its Still marker before this step, so the arrival is
            // invisible until the next five-cycle OP0. XHuman 10's archer
            // at 84,94 used to open two markers late; the arrow then landed
            // after fixture 52.
            world.battleNetIdleAcquireAround(unit);
            if (System.getenv("CHONKCRAFT_TRACE_BNE_WHERE") != null
                    && String.valueOf(unit.id()).equals(
                            System.getenv("CHONKCRAFT_TRACE_BNE_WHERE"))) {
                StringBuilder w = new StringBuilder();
                StackTraceElement[] fr = Thread.currentThread().getStackTrace();
                for (int i = 2; i < Math.min(fr.length, 7); i++) {
                    w.append(fr[i].getMethodName()).append(':')
                            .append(fr[i].getLineNumber()).append(' ');
                }
                System.err.printf("JBNEWHERE cycle=%d unit=%d heading=%d %s%n",
                        world.cycle, unit.id(), heading, w);
            }
            world.causalTrace.event(world.cycle, "movement.step", unit.id(),
                    "fromX", priorX, "fromY", priorY,
                    "toX", nextX, "toY", nextY,
                    "stride", stride, "heading", heading);
            if (System.getenv("CHONKCRAFT_TRACE_BNE_STEP") != null
                    && (System.getenv("CHONKCRAFT_TRACE_BNE_STEP").isBlank()
                    || unit.id() == Integer.parseInt(
                            System.getenv("CHONKCRAFT_TRACE_BNE_STEP").trim()))) {
                MapField left = world.map.fieldOrNull(priorX, priorY);
                MapField landed = world.map.fieldOrNull(nextX, nextY);
                System.err.printf("JBNESTEP cycle=%d unit=%d type=%s "
                                + "from=%d,%d to=%d,%d stride=%d "
                                + "leftFlags=%s landFlags=%s%n",
                        world.cycle, unit.id(), unit.type().ident(),
                        priorX, priorY, nextX, nextY, stride,
                        left == null ? "-" : Long.toHexString(left.flags()),
                        landed == null ? "-"
                                : Long.toHexString(landed.flags()));
            }
            // Turned towards the step rather than snapped to it.
            // DoActionMove ends its step with UnitHeadingFromDeltaXY, which
            // sets Direction and leaves the difference in Anim.Rotate for the
            // animation to walk down. Only the two
            // siege engines turn slowly enough for it to show, and their Move
            // animation is written to notice: "if-var R >= 60 turn", and the
            // turn label is thirty cycles of standing still.
            // Trailing same-heading run on this route (Orc 11 patrol pack).
            if (heading == unit.lastStepHeading()
                    && unit.lastStepHeading() < Direction.COUNT) {
                unit.setBattleNetSameHeadingRun(
                        unit.battleNetSameHeadingRun() + 1);
            } else {
                unit.setBattleNetSameHeadingRun(1);
            }
            unit.setLastStepHeading(heading);
            unit.turnTo(heading);
            // Ground-patrol free-prefix after three consecutive identical
            // headings: native writes route_index 20 and drains residual
            // without taking the fourth (Orc 11 archers 1559/1560/1563 and
            // knight 1558's NW+NE+NE+NE trailing run). The route-index rule
            // is not used by the doubled movement lattice: Human 12's
            // commanded zeppelin keeps E,E,E,E,NE and takes the diagonal on
            // fixture 134. Applying the ground rule there cleared the route
            // after its third east leg, and the replacement route postponed
            // that north-east leg by one full twenty-cycle flight beat.
            if (!unit.battleNetDoubleStep()
                    && unit.pathLength() > 0
                    && unit.battleNetSameHeadingRun() >= 3
                    && unit.battleNetBorrowedMoveForStep()
                    && unit.patrolX() >= 0
                    && unit.resourceUnit() == null) {
                unit.clearPath();
                unit.setBattleNetPatrolStraightRunExhausted(true);
            }
            // Gold HARVEST leftover after a completed tile: when the worker is
            // already within one tile of the approach and the next cached
            // heading misses that approach point, native drops the leftover
            // and stages action 25 after residual pixels settle. Under
            // cold-commit the next leg is armed before harvest can see
            // !isMoving, so clear here and mark the route spent; walkTowards
            // must drain residual without reopening stepMove's empty-route
            // PF_WAIT, then the one-tile action-25 stage arms the approach
            // face with native's order delay.
            // XHuman 9 peon 1550 lands free-prefix N at 109,24 with approach
            // 110,23 (cheb 1) and leftover N onto 109,23 -- without this clear
            // the leftover fires at fixture 19; with rewrite-only the approach
            // step lands three cycles early. Clear + drain-only + stage lands
            // NE at fixture 22 with native.
            // Far free-prefix forest re-aim (Orc 7) uses the empty spent-route
            // path in tryBattleNetGoldFreePrefixForestReaim once residual
            // settles; do not touch mid-route leftovers merely for adjacent
            // forest or the gold corridor along a tree line breaks.
            // Order is often temporarily MOVE inside walkTowards; use the
            // resource unit attachment, not the current order enum.
            if (unit.resourceUnit() != null && unit.resourceUnit().type() != null
                    && unit.resourceUnit().type().givesResource() == UnitType.Resource.GOLD
                    && !unit.returningToDepot() && unit.pathLength() > 0) {
                int[] approach = world.battleNetApproachPoint(unit, unit.resourceUnit());
                int approachChebyshev = Math.max(
                        Math.abs(approach[0] - unit.tileX()),
                        Math.abs(approach[1] - unit.tileY()));
                if (approachChebyshev <= 1) {
                    int leftover = unit.peekHeading();
                    int leftoverX = unit.tileX() + Direction.deltaX(leftover);
                    int leftoverY = unit.tileY() + Direction.deltaY(leftover);
                    if (leftoverX != approach[0] || leftoverY != approach[1]) {
                        // clearPath also clears routeSpent; restore the spent
                        // marker so the next settled visit stages action 25
                        // rather than serving PF_WAIT 10.
                        unit.clearPath();
                        unit.setRouteSpent(true);
                        // Retail does not empty the route here: the bytes at
                        // offset 48 stay and only the cursor stops, so a unit
                        // behind this one can still ask where it is going.
                        // Keep the heading it would have taken so
                        // battleNetCooperativeBlocker -- which is 0x0044fa20 --
                        // has an answer (XHuman 10 peon 1596 on 57,4).
                        unit.setBattleNetSpentHeading(leftover);
                    }
                }
            }
            // Now standing on the new tile, drawn a whole tile back -- and
            // primed with +=, not =: "unit.IX += -posd.x * PixelTileSize.x"
            // The overshoot a previous drain
            // banked folds back in here, so this step's drain runs a pixel
            // long or short exactly as upstream's does. On
            // campaigns/orc-exp/levelx11o destroyer 78 enters its cycle-19
            // step carrying minus one and one, primes to minus thirty-three,
            // and still owes a pixel on the cycle its animation wraps --
            // which is what makes it miss the decide window and stand
            // sixteen cycles this implementation never stood.
            // A standstill owes nothing. The bob a flyer runs while it waits
            // leaves a pixel past nought in the bank, and the += above would
            // spend it on this step: Human 5's zeppelin 1541 banks plus one
            // on fixture cycle 4, primes 65 instead of 64 for its two-tile
            // leg, and is still a pixel short on the cycle its flight ends,
            // so Moving never clears at the consult, the Move animation
            // starts over, and the next leg goes a whole ten-cycle lap late
            // -- native steps at 29, 49 and 69 where this implementation stepped at 39,
            // 59 and 79. resetDisplacement already wipes the same bob when a
            // consult answers REACHED or UNREACHABLE; a route found from a
            // standstill reaches the prime without ever passing through it.
            if (!unit.walkHolding() && unit.offsetX() == 0
                    && unit.offsetY() == 0) {
                unit.setResidual(0, 0);
            }
            // Standing exactly on the tile as the step is primed means this
            // leg opens cold: there is no old element left to spend, so the
            // chase commit below must not walk it on this cycle.
            unit.setBattleNetChaseLegOpensCold(
                    unit.offsetX() == 0 && unit.offsetY() == 0);
            unit.setOffset(
                    unit.offsetX() + unit.residualX()
                            - Direction.deltaX(heading) * Unit.TILE_PIXELS * stride,
                    unit.offsetY() + unit.residualY()
                            - Direction.deltaY(heading) * Unit.TILE_PIXELS * stride);
            unit.setResidual(0, 0);
            // The step owns the unit from here: upstream's unit.Moving = 1,
            // cleared below in walkPixels on upstream's own condition.
            unit.setWalkHolding(true);
            unit.setStepDrained(false);
            // After a chase step, retail is on the Move body past the opening
            // OP0 (skeleton 1133/1). Arm that body so the next OP0 is twenty
            // quiet calls later, not a second immediate step.
            if (world.battleNetSequence != null && unit.chasing()) {
                world.combat.armBattleNetChaseMoveBody(unit);
            }
            // Residual pixel pace for 2x2 movers: script.bin Move waits, not
            // ChonkCraft Move. XOrc 8 submarine 1433 double-stepped 102,88→100,86
            // at fixture 44 while retail held residual two for one more cycle
            // and stepped at 45 -- ChonkCraft submarine Move has irregular wait-1
            // stretches that skip two native holds.
            if (world.battleNetSequence != null
                    && (unit.battleNetDoubleStep()
                            || unit.battleNetRepairStride()
                            || "unit-critter".equals(
                                    unit.type().ident()))) {
                armBattleNetMovePace(unit);
            }
            unit.setBattleNetChaseStepReady(false);
            unit.setBattleNetChaseEmptyRouteReplan(false);
        }

        // The animation decides how far the unit travels this cycle, which is
        // what keeps the footfalls in step with the ground covered. Chase
        // OP0 keeps the older commit-then-walk order. Ordinary walks already
        // spent the old element above when they had one; a cold commit only
        // picks Move up so the script's first pace lands next cycle, which
        // is the zero-spend retail opens every new leg with.
        if (chaseMoveSequence) {
            // stepAttack may already have drained the old element for the
            // chase-boundary consult. A second walk would advance Move twice
            // on the same cycle and desync the OP0 body.
            if (!world.actionMoveWalked) {
                if (stepped && unit.battleNetChaseLegOpensCold()) {
                    // A chase leg that opens from a standstill spends nothing
                    // on the cycle it commits, the same zero-spend the cold
                    // branch below already gives an ordinary walk. Human 13
                    // ogre 1482 pauses on 124,32 for fixtures 31 to 33 and
                    // steps at 34: retail leaves it drawn at 3968,1024 for
                    // that cycle, and walking here drew it at 3965,1021 and
                    // put every later pixel a cycle early, so it arrived at
                    // 45 instead of 46 and wounded the wise man at 52 where
                    // retail does it at 53. A leg that never stopped has
                    // already spent its old element above and still walks.
                    unit.setBattleNetChaseLegOpensCold(false);
                } else if (stepped) {
                    walkPixels(unit, Direction.deltaX(unit.heading()),
                            Direction.deltaY(unit.heading()));
                } else {
                    walkPixels(unit);
                }
            }
        } else if (stepped && !walkedThisCycle) {
            pickUpMoveAnimation(unit);
        } else if (!stepped && !walkedThisCycle) {
            walkPixels(unit);
        }

        // And not on the cycle the step was taken. Upstream ends a walk on
        // the answer NextPathElement gives, and the call that takes a step
        // answers PF_MOVE; PF_REACHED can only come from a later one. A unit
        // whose animation carries it a whole tile in a single cycle would
        // otherwise arrive and finish in the same breath.
        if (mayDecide && !stepped && !unit.isMoving() && unit.pathLength() == 0) {
            // The path has run out. If this was only as far as the search
            // could see -- it returns the best it reached rather than nothing
            // -- then look again from here. Each attempt starts closer, and
            // when one can get no closer the search fails and the unit
            // genuinely stops, so this terminates.
            int wantX = unit.pathGoalX();
            int wantY = unit.pathGoalY();
            if (replanOnExhaustion
                    && world.map.contains(wantX, wantY)
                    && (unit.tileX() != wantX || unit.tileY() != wantY)
                    && orderMove(unit, wantX, wantY)) {
                return;
            }
            // Not finishOrder, but the sleeps are reset all the same. Upstream
            // comes back from any walk into a brand new still order whose
            // Sleep starts at nought, wherever the walk ended; what it does
            // *not* do here is finish on this cycle, because NextPathElement
            // answers PF_WAIT for the spent route first and the order sleeps
            // ten cycles before anything reaches PF_REACHED. Reporting it as
            // finished on this cycle put the label a cycle early everywhere --
            // four clean maps picked up a finding apiece and demo02 went from
            // cycle 54 to 27 -- so only the two arms above say Finished.
            //
            // PF_REACHED also wipes IX/IY. Combat and harvest already call
            // resetDisplacement on that answer; this exhaust arm did not.
            // Commanded Human 1 footman 1592 and Human 12 gryphon 1500
            // therefore went Still on the same cycle as native but kept a
            // 5- or 20-pixel leftover, which is why five compass rows
            // compared as material drift.
            resetDisplacement(unit);
            unit.setOrder(Unit.Order.STILL);
            unit.setRandomMoveSleep(0);
            unit.setAttackScanSleep(0);
        }
    }


    /**
     * One cycle of the move animation, drawn down against the owed offset.
     *
     * <p>The pair every walking cycle runs: the animation says how far the
     * unit travels, and the drawing offset is worked towards nought without
     * overshooting -- crossing zero means the tile has been reached.
     */
    /**
     * Whether the walk's decide window is open this cycle.
     *
     * <p>The second half of {@code DoActionMove}'s gate: the current
     * animation is not the move script at all, or it stands exactly at its
     * wrap -- index nought, wait nought -- at the call's top. A window
     * missed by one cycle costs a whole pass; see the walk-gate entries in
     * focused tests for the anatomy.
     */
    boolean atMoveBoundary(Unit unit) {
        AnimationSet set = unit.type().animationSet();
        Animation move = set == null ? null : set.get(AnimationSet.State.MOVE);
        AnimationState state = unit.animation();
        return state.current() != move
                || (state.index() == 0 && state.waitCycles() == 0);
    }


    /**
     * Whether the unit is already running its Move script this cycle.
     *
     * <p>Guards the walk-before-gate arm of {@link #stepMove}: a standing
     * unit must keep the "not the move script" escape that opens
     * {@link #atMoveBoundary}, so only a unit already on Move spends
     * pixels before the consult. Without the guard, switchTo(Move) in
     * the walk destroyed that escape and human-13 broke at fixture 2.
     */
    boolean onMoveAnimation(Unit unit) {
        AnimationSet set = unit.type().animationSet();
        Animation move = set == null ? null : set.get(AnimationSet.State.MOVE);
        return move != null && unit.animation().current() == move;
    }


    /**
     * Puts a unit on its Move script without advancing it.
     *
     * <p>Retail pays nought on the cycle a cold leg opens; the first
     * {@code move} instruction runs the cycle after. {@link #walkPixels}
     * would call {@link #advanceMoveAnimation} and spend that first pace
     * immediately, which is how every walker led native by one animation
     * beat after its first step.
     */
    void pickUpMoveAnimation(Unit unit) {
        AnimationSet set = unit.type().animationSet();
        if (set == null) {
            return;
        }
        Animation move = set.get(AnimationSet.State.MOVE);
        if (move != null) {
            unit.animation().switchTo(move);
        }
    }


    void walkPixels(Unit unit) {
        // Every pixel cycle follows the element the walk last consumed:
        // upstream's Length falls at the consult, not the step, so
        // Path[Length-1] is the just-walked element for the whole of its
        // drain (levelx11o's ELSEDBG shows the
        // length standing still through a stall). Only a consult that found
        // the route empty leaves the phantom behind.
        int posdHeading = unit.lastStepHeading();
        if (posdHeading >= Direction.COUNT) {
            walkPixels(unit, 0, 0);
            return;
        }
        walkPixels(unit, Direction.deltaX(posdHeading), Direction.deltaY(posdHeading));
    }


    /**
     * One cycle of the move animation, its pixels walked along an element.
     *
     * <p>Upstream's pixel block verbatim:
     * the element's own axes accumulate its sign -- so a drain runs toward
     * nought and a slide runs past it -- and arrival is
     * {@code (posd < 0) == (IX < 0)} read on the raw value, which an exact
     * landing satisfies only for a positive step. An axis the element does
     * not move drains any leftover toward nought at movement speed with no
     * arrival test at all: a one-pixel bob on the stationary axis is walked
     * off quietly, never mistaken for the tile being reached. The raw value
     * splits on the way out: the owed side of nought stays in the drawn
     * offset, and anything past it -- an overshoot, a slid pass -- is
     * banked in the residual pair for the next step's prime to consume.
     */
    void walkPixels(Unit unit, int posdX, int posdY) {
        int move = advanceMoveAnimation(unit);
        int rawX = unit.offsetX() + unit.residualX();
        int rawY = unit.offsetY() + unit.residualY();
        boolean reached = false;
        if (posdX != 0) {
            rawX += posdX * move;
            reached = (posdX < 0) == (rawX < 0);
        } else {
            rawX = World.residualToward(rawX, move);
        }
        if (posdY != 0) {
            rawY += posdY * move;
            reached = reached || ((posdY < 0) == (rawY < 0));
        } else {
            rawY = World.residualToward(rawY, move);
        }
        int offX;
        int offY;
        if (posdX > 0) {
            offX = Math.min(rawX, 0);
        } else if (posdX < 0) {
            offX = Math.max(rawX, 0);
        } else {
            offX = rawX;
        }
        if (posdY > 0) {
            offY = Math.min(rawY, 0);
        } else if (posdY < 0) {
            offY = Math.max(rawY, 0);
        } else {
            offY = rawY;
        }
        unit.setOffset(offX, offY);
        unit.setResidual(rawX - offX, rawY - offY);
        // The step lets go on upstream's two conditions, read on the raw
        // displacement after the cycle's pixels.
        if (reached || (rawX == 0 && rawY == 0 && !unit.animation().unbreakable())) {
            unit.setWalkHolding(false);
            // This beat is upstream's check beat -- Moving falls to nought
            // on a breakable drain-end with err still positive, and
            // CheckForTargetInRange runs right after. The boundary that
            // follows reads the flag and runs the check first.
            unit.setStepDrained(true);
            // Residual finished: drop the native Move pace so the next leg
            // re-arms from frame+OP0 rather than continuing a spent body.
            if (unit.battleNetMovePaceOffset() >= 0) {
                unit.setBattleNetMovePaceOffset(-1);
                unit.setBattleNetMovePaceTimer(0);
            }
            // Critter one-tile residual complete under MOVE: Still now, not
            // after PF_WAIT 10 or ChonkCraft unbreakable tail. Human 4 1578
            // Still@50 after 48-cycle script.bin residual (six multi@50
            // still-vs-move cases).
            if ("unit-critter".equals(unit.type().ident())
                    && unit.order() == Unit.Order.MOVE
                    && unit.pathLength() == 0
                    && unit.routeSpent()
                    && world.battleNetSequence != null) {
                unit.setWaitCycles(0);
                unit.setOrder(Unit.Order.STILL);
                unit.setBattleNetSequenceOffset(
                        world.idle.battleNetStillSequenceStart(unit));
                unit.setBattleNetAnimationTimer(3);
            }
        }
    }


    /**
     * Wipes a unit's displacement whole, bob and bank alike.
     *
     * <p>{@code resetDisplacement}, which
     * upstream runs on every consult that answers REACHED or UNREACHABLE
     * with the animation breakable. It is why a wiggling ship's bob never
     * leaks into the step after an answer: level12h's zeppelin carries plus
     * one from its bob into the consult at 222, the answer wipes it, and
     * the commit at 223 primes clean where a port that kept the pixel
     * banked it into the prime and stepped a pixel long.
     */
    void resetDisplacement(Unit unit) {
        unit.setOffset(0, 0);
        unit.setResidual(0, 0);
    }


    /**
     * Runs one cycle of a unit's move animation and returns the pixels it
     * asked for.
     *
     * <p>A type with no animation set still has to move, so it falls back to
     * its declared speed. Everything in the shipped data has animations; this
     * only catches types a mod might add.
     *
     * <p>When a 2x2 residual pace is armed from {@code script.bin}, pixel
     * motion follows native Move waits (op13/op5) rather than ChonkCraft Move.
     * The ChonkCraft script still advances for presentation/atMoveBoundary so
     * the decide gate keeps its existing shape.
     */
    int advanceMoveAnimation(Unit unit) {
        // Armed residual pace owns pixel motion and leaves the ChonkCraft Move
        // script parked at its cold-commit open (index 0, wait 0). Advancing
        // ChonkCraft in parallel used to open atMoveBoundary a cycle before the
        // native residual finished (XORc 8 submarine tile step at 44 vs 45).
        if (unit.battleNetMovePaceOffset() >= 0) {
            int pacePixels = tickBattleNetMovePace(unit);
            if (pacePixels >= 0) {
                AnimationSet set = unit.type().animationSet();
                Animation move = set == null
                        ? null
                        : set.get(AnimationSet.State.MOVE);
                if (move != null) {
                    unit.animation().switchTo(move);
                }
                return pacePixels;
            }
        }
        AnimationSet set = unit.type().animationSet();
        if (set == null) {
            return Math.max(1, unit.type().speed() / 5);
        }
        Animation move = set.get(AnimationSet.State.MOVE);
        if (move == null) {
            return Math.max(1, unit.type().speed() / 5);
        }
        unit.animation().switchTo(move);
        // Scale 1: terrain move costs are not applied yet, so every square
        // costs the same to cross. Roads and rough ground arrive with the
        // tileset's cost table.
        return world.advance(unit).move();
    }


    /**
     * Arms the retail Move body that paces residual drain for double-step
     * ships and land critters.
     *
     * <p>Same open as chase: frame + OP0 leaves the cursor on the first pixel
     * opcode with timer 1, so the next walk visit spends the first pace.
     */
    void armBattleNetMovePace(Unit unit) {
        if (world.battleNetSequence == null || unit == null || unit.type() == null) {
            return;
        }
        int moveStart = world.idle.battleNetSequenceStart(unit,
                BattleNetSequence.MOVE_ANIMATION);
        if (moveStart < 0) {
            unit.setBattleNetMovePaceOffset(-1);
            return;
        }
        BattleNetSequence.Tick open = world.battleNetSequence.tick(moveStart, 1);
        if (!open.valid()) {
            unit.setBattleNetMovePaceOffset(-1);
            return;
        }
        unit.setBattleNetMovePaceOffset(open.offset());
        unit.setBattleNetMovePaceTimer(open.timer());
    }


    /**
     * One residual pace from the armed Move sequence, or {@code -1} when the
     * unit is not on a native residual pace.
     *
     * <p>Ship {@code op13} arguments are multiplied by two when the doubled
     * movement-delta table is armed: thirty-two {@code op13 1} opcodes cover
     * a 64-pixel double-step residual. Land {@code op5}/{@code op6} already
     * sum to thirty-two for a one-tile step and keep the raw argument.
     */
    int tickBattleNetMovePace(Unit unit) {
        if (world.battleNetSequence == null
                || unit.battleNetMovePaceOffset() < 0) {
            return -1;
        }
        BattleNetSequence.Tick tick = world.battleNetSequence.tick(
                unit.battleNetMovePaceOffset(), unit.battleNetMovePaceTimer());
        if (!tick.valid()) {
            unit.setBattleNetMovePaceOffset(-1);
            return -1;
        }
        unit.setBattleNetMovePaceOffset(tick.offset());
        unit.setBattleNetMovePaceTimer(tick.timer());
        int pixels = tick.pixels();
        if (pixels > 0 && unit.battleNetDoubleStep()) {
            // script.bin ships emit op13 1 for each residual beat; the doubled
            // table walks two pixels per beat (64px for a two-tile stride).
            pixels *= 2;
        }
        return pixels;
    }
}
