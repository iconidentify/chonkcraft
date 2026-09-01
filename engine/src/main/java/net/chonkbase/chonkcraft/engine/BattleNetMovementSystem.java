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

    /**
     * Same-visit move-order reentry. The empty-path residual arm may
     * ask once for a replacement route (Human 5 peasant 1512 leftover
     * harvest to Move). A second ask used to recurse until replay smoke
     * overflowed on NerzyvsHTOSGOW after two-short leftover dest-arm.
     */
    private int moveOrderDepth;

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
        // ReleaseOrders erases the old combat order at this pop, not when a
        // committed attack animation first receives the player click. Doing
        // it at issue time left long air-unit attack bodies executing over
        // an already-empty target/chase record.
        world.releaseBattleNetCombatOrderForPlayerReplacement(unit);
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
     * Player/network clicks into forest are stored as the first tree on the
     * BNE line. Orc 1 peon 1594 commanded to 30,18 keeps 28,18.
     */
    int[] projectPlayerMovePoint(Unit unit, int toX, int toY) {
        if (world.map.contains(toX, toY)
                && !unit.type().airUnit()
                && !world.battleNetTerrainPassable(unit, toX, toY)) {
            int[] projected = BattleNetPathFinder.firstBlockedToward(
                    unit.tileX(), unit.tileY(), toX, toY,
                    (x, y) -> world.map.contains(x, y)
                            && world.battleNetTerrainPassable(unit, x, y));
            if (Math.max(Math.abs(unit.tileX() - projected[0]),
                    Math.abs(unit.tileY() - projected[1])) > 1) {
                return projected;
            }
        }
        return new int[] {toX, toY};
    }

    boolean leftoverWalkBearing(Unit.Order action, Unit unit) {
        if (action == null || unit == null) {
            return false;
        }
        return switch (action) {
            case HARVEST, PATROL, MOVE, RETURN_GOODS, REPAIR,
                    ATTACK_MOVE, FOLLOW, EXPLORE ->
                    unit.isMoving() || unit.residualX() != 0 || unit.residualY() != 0;
            default -> false;
        };
    }

    /**
     * After leftover dest-arm pixels land, native pops the queued Move and
     * pays wait 3 -- Human 5 peasant 1512 Harvest to Move at fixture 19,
     * Orc 6 flyer 1553 Patrol to Move at fixture 23.
     */
    boolean finishLeftoverReplacement(Unit unit) {
        if (unit == null || unit.isMoving()
                || unit.residualX() != 0 || unit.residualY() != 0) {
            return false;
        }
        if (unit.battleNetStopAfterLeftover()) {
            unit.setBattleNetStopAfterLeftover(false);
            battleNetEmptyRouteStillAndDispatch(unit);
            return true;
        }
        if (promoteQueuedPlayerMoveAfterLeftover(unit)) {
            return true;
        }
        if (promoteQueuedStandGroundAfterLeftover(unit)) {
            return true;
        }
        return promoteQueuedRepairAfterLeftover(unit);
    }

    /**
     * After leftover dest-arm pixels land, native pops queued stand-ground
     * as order 15 with timer 3. The leftover-land visit already paid one
     * quiet call, so two remain.
     */
    boolean promoteQueuedStandGroundAfterLeftover(Unit unit) {
        if (unit == null || !unit.queuedReplacementPending()
                || !unit.hasQueuedOrders() || unit.isMoving()) {
            return false;
        }
        if (unit.residualX() != 0 || unit.residualY() != 0) {
            return false;
        }
        Unit.QueuedOrder next = unit.queuedOrders().getFirst();
        if (next.kind() != Unit.QueuedOrderKind.STAND_GROUND) {
            return false;
        }
        unit.pollQueuedOrder();
        unit.setQueuedReplacementPending(false);
        world.installStandGroundHold(unit, false);
        unit.setBattleNetOrderDelay(2);
        unit.setActionBeforeQueued(null);
        return true;
    }

    /**
     * After leftover dest-arm pixels land, native pops queued Repair and
     * pays wait 3 -- Human 5 peasant 1512 Harvest to Repair at fixture 19.
     */
    boolean promoteQueuedRepairAfterLeftover(Unit unit) {
        if (unit == null || !unit.queuedReplacementPending()
                || !unit.hasQueuedOrders() || unit.isMoving()) {
            return false;
        }
        if (unit.residualX() != 0 || unit.residualY() != 0) {
            return false;
        }
        Unit.QueuedOrder next = unit.queuedOrders().getFirst();
        if (next.kind() != Unit.QueuedOrderKind.REPAIR || next.target() == null) {
            return false;
        }
        unit.pollQueuedOrder();
        unit.setQueuedReplacementPending(false);
        world.construction.orderRepair(unit, next.target(), false);
        // Leftover-land visit already executed the walk-bearing leftover,
        // so two remaining quiet Repair visits match that three-visit start.
        unit.setBattleNetOrderDelay(2);
        unit.setActionBeforeQueued(null);
        return true;
    }

    boolean promoteQueuedPlayerMoveAfterLeftover(Unit unit) {
        if (unit == null || !unit.battleNetPlayerCommandMove()
                || unit.order() == Unit.Order.MOVE || unit.isMoving()) {
            return false;
        }
        if (unit.residualX() != 0 || unit.residualY() != 0) {
            return false;
        }
        unit.setOrder(Unit.Order.MOVE);
        // Native pops Move at the leftover-land visit and pays wait 3
        // on that same snapshot (1512 Harvest to Move at 19, dest-arm at
        // 22). This visit already executed the walk-bearing leftover, so
        // two remaining quiet Move visits match that three-visit start.
        // Delay 3 dest-armed at 23 and settled a cycle late.
        unit.setBattleNetOrderDelay(2);
        unit.setActionBeforeQueued(null);
        return true;
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
        if (unit.type().building() || unit.type().speed() <= 0
                || !world.map.contains(toX, toY)) {
            return false;
        }
        // A new ordinary Move supersedes any GiveOrder 17 walk provenance.
        // The attack-ground constructor sets it again only after its projected
        // Move has actually been accepted.
        unit.setBattleNetAttackGroundMove(false);
        // ReleaseOrders replaces the attack even when Move must first wait
        // behind the current Still body or drain a residual heading. Cancel
        // its presentation-ahead projectile at the command boundary, not
        // only in the immediate orderMove arm. Otherwise a restored siege
        // placeholder survives a queued move at its old muzzle and can wake
        // up during a later attack.
        world.projectiles.interruptPendingAttack(unit);
        Unit.Order before = unit.currentAction();
        boolean restoreScoutPatrol = before == Unit.Order.PATROL
                && unit.battleNetScoutPatrol();
        // The flushed replacement is not allowed to become Orders[0] while
        // that order's animation is committed. BNE keeps the current swing
        // visible and executable, then erases its order and promotes Move on
        // the first breakable visit. This is especially visible on gryphons:
        // their long attack body used to remain active after the code below
        // had already changed the semantic order to MOVE and erased all
        // combat state, so Move and the next Attack appeared unresponsive
        // until Stop reset the body.
        if (before != Unit.Order.STILL
                && unit.animation() != null
                && unit.animation().unbreakable()
                && !leftoverWalkBearing(before, unit)) {
            int[] dest = projectPlayerMovePoint(unit, toX, toY);
            unit.clearQueuedOrders();
            unit.setSavedOrder(null);
            unit.enqueueOrder(new Unit.QueuedOrder(
                    Unit.QueuedOrderKind.MOVE, dest[0], dest[1],
                    null, null, null));
            unit.setQueuedReplacementPending(true);
            unit.setBattleNetPlayerCommandMove(true);
            unit.rememberActionBeforeQueued(before);
            return true;
        }
        // ReleaseOrders destroys the replaced order object. Combat state is
        // projected onto Unit in this implementation, so release its target
        // and chase state explicitly when the player's Move replaces it.
        world.releaseBattleNetCombatOrderForPlayerReplacement(unit);
        // A dest-arm leftover already owns a heading. Native writes
        // next_order=MOVE and the new order point, then keeps draining that
        // leftover -- Human 5 peasant 1512 first walks at fixture 6 still on
        // Harvest; Orc 6 flyer 1553 first walks at fixture 5 still on Patrol.
        // A 2x2 scout's odd click overlaps its own hull, so the occupied
        // neighbour test used to swallow that click and leave Patrol walking
        // to 18,51 / 83,10. Ask leftover first.
        if (leftoverWalkBearing(before, unit)) {
            int[] dest = projectPlayerMovePoint(unit, toX, toY);
            unit.setPathGoal(dest[0], dest[1]);
            unit.setOrderTarget(dest[0], dest[1]);
            unit.setMoveRange(0);
            // Keep dest-arm residual pixels. Remaining harvest/patrol
            // headings belong to the old order point and must not keep
            // walking after the click -- native 1512 only drains the
            // already dest-armed leftover onto 33,106, then pops Move.
            unit.clearPath();
            unit.setBattleNetPlayerCommandMove(true);
            if (restoreScoutPatrol && unit.savedOrder() == null) {
                unit.setSavedOrder(Unit.Order.PATROL);
            }
            return true;
        }
        if (alreadyTouchingBlockedDest(unit, toX, toY)) {
            return true;
        }
        int[] waits = playerCommandWaits(unit);
        int actionWait = waits[0];
        int queueWait = waits[1];
        // Native GiveOrder 3 from Still writes dest and next_order 3:
        // Human 1 1598 queueWait 1 is Still at cycle 5 and MOVE at 6;
        // 1597 queueWait 4 stays Still through the 4985 body until 9.
        // Installing MOVE on the issue visit showed MOVE at cycle 5.
        if (before == Unit.Order.STILL && queueWait > 0) {
            int[] dest = projectPlayerMovePoint(unit, toX, toY);
            unit.setPathGoal(dest[0], dest[1]);
            unit.setOrderTarget(dest[0], dest[1]);
            unit.setMoveRange(0);
            unit.clearPath();
            unit.enqueueOrder(new Unit.QueuedOrder(
                    Unit.QueuedOrderKind.MOVE, dest[0], dest[1],
                    null, null, null));
            unit.setQueuedReplacementPending(true);
            unit.setBattleNetPlayerCommandMove(true);
            unit.setDestPathOpeningHold(true);
            // The issue visit still decrements this delay, so add the
            // beat native spends writing next_order instead of counting
            // down.
            unit.setBattleNetOrderDelay(queueWait + 1);
            return true;
        }
        // Script.bin Still is already on OP0 (queueWait 0) while a slow
        // siege engine's presentation Still still has a wait. Native 413
        // OP0 continues into the shared 4985 Still body -- remaining timer
        // 3 at the click -- so first dest-arm is fixture 11. Paying only
        // actionWait snapped Orc 8 1576 at 8 and left every later leftover
        // three cycles early.
        if (queueWait == 0 && before == Unit.Order.STILL
                && siegeUsesScriptBinMovePace(unit)
                && unit.animation() != null
                && unit.animation().waitCycles() > 0) {
            queueWait = unit.animation().waitCycles();
        }
        int[] dest = projectPlayerMovePoint(unit, toX, toY);
        boolean accepted = orderMove(unit, dest[0], dest[1],
                actionWait + queueWait);
        if (accepted) {
            unit.setBattleNetPlayerCommandMove(true);
            // A scout's native Patrol order is suspended by a point command
            // and restored when that Move completes (Human 12 zeppelin:
            // Still at fixture 48, Patrol again at 51). Patrol owns its two
            // endpoints separately, so the ordinary saved-order slot is the
            // exact durable state needed here and survives save/load.
            if (restoreScoutPatrol && unit.savedOrder() == null) {
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
        // Retail doubled movers select the absolute even-anchor route. A
        // legacy save or a custom map with an oppositely aligned 3x3 shipyard
        // can surface a 2x2 hull on an odd anchor, where a doubled step can
        // never repair parity. The reported destroyer stayed on the odd/even
        // lattice, repeatedly crossed its rounded Move point, and looked like
        // Patrol until Stop drained the current stride. Keep BNE's doubled
        // rule on every valid anchor, but make a player command from an
        // already-invalid anchor a single-lattice recovery when its requested
        // water anchor is genuinely enterable. Keep the doubled shoreline
        // refusal path for an impassable click, and do not touch transports:
        // both have separately authenticated movement rules. Tankers retain
        // their existing ability to re-arm the native bit after they return
        // to a valid anchor.
        if (unit.type().gathering().containsKey(UnitType.Resource.OIL)) {
            unit.setBattleNetDoubleStep(
                    ((unit.tileX() | unit.tileY()) & 1) == 0);
        } else if (usesOffGridShipRecovery(unit, toX, toY)) {
            unit.setBattleNetDoubleStep(false);
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

    /** Narrow custom-map recovery for a doubled combat ship born off-grid. */
    private boolean usesOffGridShipRecovery(Unit unit, int goalX, int goalY) {
        return unit != null && unit.type() != null
                && unit.battleNetDoubleStep()
                && unit.type().seaUnit()
                && !unit.type().canTransport()
                && ((unit.tileX() | unit.tileY()) & 1) != 0
                && world.map.contains(goalX, goalY)
                && world.battleNetTerrainPassable(unit, goalX, goalY);
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
        moveOrderDepth++;
        try {
            stepMoveOrderVisit(unit);
        } finally {
            moveOrderDepth--;
        }
    }

    private void stepMoveOrderVisit(Unit unit) {
        if (unit.battleNetOrderDelay() > 0) {
            unit.setBattleNetOrderDelay(unit.battleNetOrderDelay() - 1);
            return;
        }
        // A recurring AI force launch can replace an ordinary Move while its
        // final stride is still physically committed. Retail parks the route
        // immediately, drains only those old pixels, then promotes the queued
        // Patrol on the settle visit. Letting the empty Move route replan here
        // loses the launch and sends the fighter gliding past the assault.
        // XHuman 12 ogre 1356 is the sealed witness: launch/RI20 at fixture 49,
        // Patrol/Still construction at 57, north step at 60.
        if (unit.order() == Unit.Order.MOVE
                && unit.battleNetAiBehavior() == 2
                && unit.hasBattleNetPendingPatrol()) {
            if (unit.isMoving()) {
                walkPixels(unit);
                if (unit.isMoving()) {
                    return;
                }
            }
            world.beginBattleNetPendingPatrol(unit);
            return;
        }
        if (finishLeftoverReplacement(unit)) {
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
        // command that orderMove now chooses. A custom-map shipyard can put
        // a combat ship on the same invalid anchor, so repair that saved Move
        // as well—but only toward enterable sea. Coastline refusal and
        // transports keep their authenticated doubled rules.
        if (unit.battleNetDoubleStep()
                && (unit.type().gathering().containsKey(UnitType.Resource.OIL)
                    || usesOffGridShipRecovery(unit,
                            unit.orderTargetX(), unit.orderTargetY()))
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

        boolean coldBehaviorOneChaseReturnReplacement = false;
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
            } else if (battleNetEmptyRouteRefillsImmediately(unit)) {
                // A spent 20-byte buffer used to pay PF_WAIT 10 before
                // 0x44fbd0, which is why a Human 1 walk sat 27 cycles on
                // 23,13 and 19,12 while native dest-armed at 16. 0x437c80
                // calls the pathfinder on the same visit 0x44fab0 fails
                // (cursor >= 20). The last-one-or-two-tile exception was
                // that refill seen only next to dest.
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
            boolean plainMoveRefusalReplacement = unit.moveRange() == 0
                    && unit.battleNetPlainMoveRefusalReplacement();
            coldBehaviorOneChaseReturnReplacement =
                    plainMoveRefusalReplacement
                    && unit.battleNetAiBehavior() == 1
                    && unit.hasBattleNetAiHome()
                    && unit.orderTargetX() == unit.battleNetAiHomeX()
                    && unit.orderTargetY() == unit.battleNetAiHomeY()
                    && unit.battleNetChaseLegOpensCold()
                    && unit.battleNetAttackOp0OutOfRange()
                    && unit.battleNetCollisionCounter() >= 2;
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
            // The native replacement view belongs to one route generation.
            // Consume it even when that generation reaches or fails so it
            // cannot leak into a widened range probe or a later order.
            unit.setBattleNetPlainMoveRefusalReplacement(false);
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
                        Unit movingWanderBlocker = world.blockerOnLayer(
                                unit, toX, toY);
                        if (movingWanderBlocker != null
                                && movingWanderBlocker != unit
                                && movingWanderBlocker.isOnMap()
                                && !movingWanderBlocker.isDying()
                                && (movingWanderBlocker.isMoving()
                                        || movingWanderBlocker.walkHolding())) {
                            // Empty FOUND against a body which still owns its
                            // walk is a real FUN_004379e0 refusal, not a free
                            // provisional retry. Retail leaves Move visible,
                            // advances the sticky refusal nibble on each
                            // retry, and from refusal eight pays the complete
                            // Move 15..1 band. Orc 13 critter 1456 reaches
                            // collision ten while 1457 owns its northeast
                            // square; Human 10 slot 1577 and Human 14 slot
                            // 1524 independently reach nine and eight. The
                            // old free retry left Java at collision zero and
                            // promoted Still as soon as the blocker settled.
                            int refusals = battleNetRefuse(unit);
                            unit.setRouteSpent(false);
                            unit.setBattleNetOrderDelay(0);
                            pickUpMoveAnimation(unit);
                            if (world.battleNetSequence != null) {
                                int moveStart = world.idle
                                        .battleNetSequenceStart(unit,
                                                BattleNetSequence.MOVE_ANIMATION);
                                if (moveStart >= 0) {
                                    unit.setBattleNetSequenceOffset(moveStart);
                                    unit.setBattleNetAnimationTimer(
                                            refusals >= 8 ? 15 : 1);
                                }
                            }
                            return;
                        }
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
                    // Retail's plain Move writes the whole bounded terrain
                    // line and ignores mobile occupants while deciding. It
                    // refuses only when a stored byte is later consumed.
                    // Human 13 ogre 1519 stores eleven north bytes on fixture
                    // 253, takes one, then refuses the occupied second byte on
                    // 265 and replans NW,NE on 266. Java's occupancy route was
                    // N,NW,NE and therefore took NW one visit early. Ogre 1510
                    // independently stores NW,N,NW,N,NW,N,NW,NW,N on 255,
                    // refuses its occupied N byte on 267, and replans on 268.
                    // Sibling 1501 keeps its N,NW,W detour untouched because
                    // the line's first NW square is occupied at plan time.
                    // The gate carries refusal history: a fresh acquisition's
                    // first plan is itself retail's answer (XHuman 4's
                    // opening axethrowers wall correctly from cycle three),
                    // while a wake after refusals and a constructor band owns
                    // this direct writer. Borrowed attack-chase moves keep
                    // their independently authenticated planning.
                    if (unit.pathLength() > 0
                            && !unit.battleNetBorrowedMoveForStep()
                            && !plainMoveRefusalReplacement
                            && (unit.battleNetCollisionCounter() > 0
                                    || unit.battleNetRefusals() > 0)) {
                        int lineFirst = BattleNetPathFinder.firstLineHeading(
                                unit.tileX(), unit.tileY(), toX, toY);
                        int faceX = unit.tileX()
                                + Direction.deltaX(lineFirst);
                        int faceY = unit.tileY()
                                + Direction.deltaY(lineFirst);
                        if (world.canEnter(unit, faceX, faceY)) {
                            PathFinder.Path line =
                                    BattleNetPathFinder.clearLine(
                                            unit.tileX(), unit.tileY(),
                                            toX, toY, 1,
                                            (x, y) -> {
                                                if (!world.map.contains(x, y)
                                                        || !world
                                                                .battleNetTerrainPassable(
                                                                        unit,
                                                                        x, y)) {
                                                    return false;
                                                }
                                                Unit occupant =
                                                        world.blockerOnLayer(
                                                                unit, x, y);
                                                return occupant == null
                                                        || occupant == unit
                                                        || occupant.type()
                                                                == null
                                                        || !occupant.type()
                                                                .building();
                                            });
                            if (line.result()
                                    == PathFinder.Result.FOUND) {
                                unit.setPath(line);
                                unit.setBattleNetPlainMoveDirectLine(true);
                            }
                        }
                    }
                }
            }
        }
        stepMove(unit);
        if (coldBehaviorOneChaseReturnReplacement
                && !unit.isMoving() && unit.pathLength() > 0
                && unit.battleNetCollisionCounter() >= 3) {
            // The first replacement head is occupied as well. Retail keeps
            // its newly written bytes, advances the packed collision ladder,
            // and pays the complete Move band rather than parking the route a
            // second time. Human 13 slot 1511 retains NW,NE under 586/15.
            unit.setBattleNetOrderDelay(14);
            int moveStart = world.idle.battleNetSequenceStart(unit,
                    BattleNetSequence.MOVE_ANIMATION);
            if (moveStart >= 0) {
                unit.setBattleNetSequenceOffset(moveStart);
                unit.setBattleNetAnimationTimer(15);
                unit.setBattleNetChaseStepReady(false);
            }
        }
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
        finishBattleNetMoveAtTarget(unit, 3);
    }

    private void finishBattleNetMoveAtTarget(Unit unit, int stillTimer) {
        resetDisplacement(unit);
        unit.setRouteSpent(false);
        unit.setWaitCycles(0);
        unit.clearPath();
        unit.setBattleNetPlayerCommandMove(false);
        // The dest-path opening hold is the walk that just landed. Leaving
        // it armed made a later Attack-Move think it still owed dest-arm.
        unit.setDestPathOpeningHold(false);
        // Hits taken on the walk used to leave HitUnit's offer, so the first
        // post-settle Still OP0 chased (Human 1 1598 Attack@396 dest-arm@399
        // onto 25,27) instead of staying Still. Native 396 is 4983 Still
        // with 1591 at dist 2; Attack in place only when that grunt
        // dest-arms adjacent at 401. A temporary resource-hit Move is the
        // exception: its saved resource order owns the reaction, and another
        // hit retained while the stride drains starts a second reaction body
        // before RestoreOrder. Human 8 peasant 1536 lands on fixture 347,
        // authors (89,60), and remains raw Move for that three-call body.
        if (world.harvest.restartBattleNetStrandedResourceHitFlee(unit)) {
            return;
        }
        unit.setOfferedTarget(null);
        world.finishOrder(unit);
        unit.setActionBeforeQueued(null);
        if (world.battleNetSequence != null) {
            unit.setBattleNetSequenceOffset(
                    world.idle.battleNetStillSequenceStart(unit));
            unit.setBattleNetAnimationTimer(Math.max(1, stillTimer));
        }
    }

    /** Whether {@code from} has reached the edge of an occupied point goal. */
    private boolean battleNetTerminalOccupiedPointFrom(
            Unit unit, int fromX, int fromY) {
        if (unit == null || unit.order() != Unit.Order.MOVE
                || unit.pathGoalX() < 0 || unit.pathGoalY() < 0
                || Math.max(Math.abs(fromX - unit.pathGoalX()),
                        Math.abs(fromY - unit.pathGoalY())) != 1) {
            return false;
        }
        Unit blocker = world.blockerOnLayer(
                unit, unit.pathGoalX(), unit.pathGoalY());
        return blocker != null && blocker != unit
                && (World.isEnemy(unit, blocker) || !blocker.walkHolding());
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
        if (world.harvest.stepBattleNetStrandedResourceHitFlee(unit)) {
            return;
        }
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
    /** Drained Move body whose one-byte route is due this scheduler pass. */
    boolean battleNetArmedDrainedMoveAlly(Unit candidate) {
        if (candidate == null || candidate.isMoving()
                || candidate.type() == null
                || candidate.type().maxAttackRange() > 1
                || !candidate.stepDrained()
                || candidate.pathLength() != 1
                || candidate.battleNetStageSixCardinalProbePark()
                || !candidate.battleNetChaseEmptyRouteReplan()
                || candidate.battleNetOrderDelay() > 1
                || candidate.battleNetAnimationTimer() > 2
                || world.battleNetSequence == null) {
            return false;
        }
        int move = world.idle.battleNetSequenceStart(candidate,
                BattleNetSequence.MOVE_ANIMATION);
        int attack = world.idle.battleNetSequenceStart(candidate,
                BattleNetSequence.ATTACK_ANIMATION);
        int offset = candidate.battleNetSequenceOffset();
        return move >= 0 && offset >= move
                && (attack < 0 || offset < attack);
    }

    boolean battleNetSoftClearMoveAlly(Unit candidate) {
        return battleNetSoftClearMoveAlly(candidate, false);
    }

    /** Native Move view when a live route proves Java's refusal proxy stale. */
    boolean battleNetSoftClearLiveRouteRefusalAlly(Unit candidate) {
        return battleNetSoftClearMoveAlly(candidate, true);
    }

    private boolean battleNetSoftClearMoveAlly(Unit candidate,
            boolean ignoreLiveRouteRefusal) {
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
        if (candidate == null) {
            return false;
        }
        boolean armedDrainedMove =
                battleNetArmedDrainedMoveAlly(candidate);
        // The recurring behaviour-one regroup callback installs a plain Move
        // body before it has a route or pixel residual. Native's occupancy
        // test sees action-state byte 3 immediately, so a later unit in this
        // scheduler pass may plan through the departing ally. XHuman 12's
        // peon 1376 only finds its south route on fixture 199 because grunt
        // 1363 was promoted toward its recorded home earlier in that pass.
        boolean regroupUnit = candidate.battleNetAiBehavior() == 1
                && candidate.hasBattleNetAiHome();
        boolean freshLandRegroupMove = !candidate.isMoving()
                && regroupUnit
                && candidate.order() == Unit.Order.MOVE
                && candidate.pathLength() == 0
                // beginBattleNetPendingMove arms two on the promotion pass.
                // Once that drops to one, the coarse Move order can still be
                // executing the old Still body and must remain solid until
                // the Move sequence actually starts (XHuman 12 grunt 1358,
                // native action-state 2 at fixtures 203..205). Grunt 1363's
                // same-pass fixture-199 promotion retains the soft arm.
                && candidate.battleNetOrderDelay() > 1
                && candidate.orderTargetX() == candidate.battleNetAiHomeX()
                && candidate.orderTargetY() == candidate.battleNetAiHomeY();
        // The same player pass can leave Move queued behind a Still program.
        // Native XHuman 12 grunt 1358 is Still with next-order 3 on fixture
        // 199; the peon route written later that cycle passes through its
        // square. The pending Java regroup is that next-order state.
        boolean pendingLandRegroupMove = !candidate.isMoving()
                && regroupUnit
                && candidate.order() == Unit.Order.STILL
                && candidate.hasBattleNetPendingMove()
                && candidate.battleNetPendingMoveX()
                        == candidate.battleNetAiHomeX()
                && candidate.battleNetPendingMoveY()
                        == candidate.battleNetAiHomeY();
        if (!candidate.isMoving() && !armedDrainedMove
                && !freshLandRegroupMove && !pendingLandRegroupMove) {
            return false;
        }
        // Native 0x4501bc: high nibble of unit+0x1d nonzero keeps occupancy.
        // Measured over 14,616 paired unit-cycles of XHuman 12: the refusal
        // counter alone answers this square the way 0x00450766 does 98.36% of
        // the time and the pair 99.13%, and every one of the mistakes it
        // removes is a unit this implementation stood aside where native holds.
        Unit retainedMeleeTarget =
                world.battleNetPendingMeleeHits.get(candidate);
        boolean retiredMeleeRefusal = candidate.isMoving()
                && candidate.battleNetPathStepsTaken() > 0
                && Math.max(Math.abs(candidate.offsetX()),
                        Math.abs(candidate.offsetY())) == 32
                && retainedMeleeTarget != null
                && retainedMeleeTarget != candidate.target()
                && (retainedMeleeTarget.isDying()
                        || !retainedMeleeTarget.isAlive());
        boolean liveRouteRefusalProxy = ignoreLiveRouteRefusal
                && candidate.pathLength() > 0
                && candidate.battleNetCollisionCounter() == 0;
        if (!armedDrainedMove
                && (candidate.battleNetCollisionCounter() > 0
                        || (candidate.battleNetRefusals() > 0
                                && !retiredMeleeRefusal
                                && !liveRouteRefusalProxy))) {
            return false;
        }
        // A committed melee swing may still name the retired quarry after
        // AutoSelectTarget has installed the next one. Native has already
        // cleared the old collision nibble at that active-order boundary;
        // Java's separate refusal proxy remains only to finish the committed
        // swing's refusal timing. At the exact same-pass full-tile commit the
        // body is therefore soft for path occupancy. XHuman 10 knight 1485
        // changes dying grunt 1475 for axethrower 1496, commits SW on fixture
        // 324, and raw unit+0x1d stays zero while knight 1493 routes through
        // it. The paired XHuman 4 axes retain their current quarry and raw
        // nibble one at their full-tile commits, so they remain hard.
        if (pendingLandRegroupMove) {
            // Its current animation is deliberately still the Still body;
            // the queued departure is the native passability evidence.
            return true;
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

    /** Whether a queued assault departure yields to this blocked wood retry. */
    boolean battleNetPendingLandAssaultYieldsToWood(
            Unit router, Unit candidate) {
        if (router == null || candidate == null || router == candidate
                || router.order() != Unit.Order.HARVEST
                || router.returningToDepot()
                || router.resourceUnit() != null
                || router.isMoving() || router.pathLength() != 0
                || !router.stepDrained()
                || candidate.isMoving()
                || candidate.battleNetAiBehavior() != 2
                || candidate.order() != Unit.Order.STILL
                || !candidate.hasBattleNetPendingPatrol()
                || candidate.type() == null
                || candidate.type().moveType() != UnitType.Movement.LAND) {
            return false;
        }
        // Native XHuman 12 peons 1376 and 1360 have both drained their old
        // action-23 routes when the same player pass leaves ogre 1356 Still
        // with next-order Patrol. Their correctly scheduled retries route
        // through that departing body. The exhausted-route predicate keeps a
        // fresh terrain order from treating a merely queued body as absent.
        return true;
    }

    /**
     * Move-body ownership used by the replacement ray after a paid refusal.
     *
     * <p>The ordinary 0x4501bc view keeps a collision-elevated ally solid.
     * Once a chaser has parked its route and paid the complete refusal band,
     * the replacement target ray is drawn through melee friends that are
     * visibly leaving on a Move body. XHuman 10's grunt 1490 stores E,E,SE
     * through the collision-two grunt already moving across 80,88.</p>
     */
    boolean battleNetRefusalBandSoftClearMoveAlly(Unit candidate) {
        if (candidate == null || candidate.type() == null
                || candidate.type().maxAttackRange() > 1) {
            return false;
        }
        if (world.battleNetSequence == null) {
            return candidate.isMoving();
        }
        int move = world.idle.battleNetSequenceStart(candidate,
                BattleNetSequence.MOVE_ANIMATION);
        int attack = world.idle.battleNetSequenceStart(candidate,
                BattleNetSequence.ATTACK_ANIMATION);
        int offset = candidate.battleNetSequenceOffset();
        if (move < 0 || offset < 0) {
            return candidate.isMoving();
        }
        // This native view is keyed by the action-state byte (Move == 3),
        // not by nonzero residual pixels. A unit whose compass element is
        // armed at Move-start/1 is already transparent to a paid-band
        // replacement ray even though its tile changes on the following
        // visit. XHuman 12 grunt 1512 is exactly that state at fixture 108:
        // raw action 3, sequence 2482/1, collision 1 and a live S route.
        // Axethrower 1523 therefore draws its SE-led ray through the grunt's
        // current square and advances with the formation. Requiring
        // isMoving() kept the drained-pixel proxy solid for one extra visit,
        // selected the opposite wall face, and made a live firing line look
        // frozen. The ordinary soft-clear above remains deliberately stricter;
        // this broader view belongs only to a completed refusal-band redraw.
        boolean armedDrainedMove = !candidate.isMoving()
                && candidate.stepDrained()
                && candidate.pathLength() == 1
                && candidate.battleNetChaseEmptyRouteReplan()
                // Java visits the lower id first; native's reverse pool order
                // has already paid this final quiet count. Accept both the
                // post-decrement state and the one-count pre-visit twin.
                && candidate.battleNetOrderDelay() <= 1
                && candidate.battleNetAnimationTimer() <= 2;
        return (candidate.isMoving() || armedDrainedMove)
                && offset >= move && (attack < 0 || offset < attack);
    }

    /**
     * Whether a later native pool slot owns a paid redraw which will vacate
     * its current cell on this scheduler cycle.
     */
    private boolean battleNetPaidParkedRouteWillVacate(
            Unit mover, Unit candidate) {
        if (mover == null || candidate == null || candidate == mover
                || !candidate.hasBattleNetLongPaidWrapParkedRoute()
                || candidate.pathLength() != 0
                || !candidate.stepDrained() || candidate.isMoving()
                || candidate.target() == null
                || candidate.target() != mover.target()
                || candidate.type() == null
                || candidate.type().maxAttackRange() > 1
                || !world.isAllied(mover.player(), candidate.player())) {
            return false;
        }
        Unit target = candidate.target();
        int currentDistance = Math.max(
                Math.abs(target.tileX() - candidate.tileX()),
                Math.abs(target.tileY() - candidate.tileY()));
        int stride = world.battleNetMovementStride(candidate);
        for (int heading = 0; heading < Direction.COUNT; heading++) {
            int nextX = candidate.tileX()
                    + Direction.deltaX(heading) * stride;
            int nextY = candidate.tileY()
                    + Direction.deltaY(heading) * stride;
            int nextDistance = Math.max(
                    Math.abs(target.tileX() - nextX),
                    Math.abs(target.tileY() - nextY));
            if (nextDistance < currentDistance
                    && world.canEnter(candidate, nextX, nextY)) {
                return true;
            }
        }
        return false;
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
        if (worker.pathLength() == 0 && worker.isMoving()) {
            walkPixels(worker);
            if (worker.isMoving()) {
                return;
            }
            // GiveOrder 17 leftover-land beside an unenterable click is
            // Still. Native 1594 lands 27,17 at 43; PF_WAIT 10 stayed
            // Attack Ground through the window.
            if (worker.order() == Unit.Order.ATTACK_GROUND
                    && leftoverLandedBesideForest(worker, tileX, tileY)) {
                world.finishOrder(worker);
                return;
            }
        }
        if (worker.pathLength() == 0 && !worker.isMoving()) {
            // Patrol leftover dest-arm that lands on dest is the endpoint
            // exchange, not PF_WAIT 10. Native peon 1594 residual-settles
            // on 22,18 and turns around; the empty ten left it on 23,18
            // at the commanded window.
            if (worker.order() == Unit.Order.PATROL
                    && worker.tileX() == tileX && worker.tileY() == tileY) {
                worker.setRouteSpent(false);
                return;
            }
            // The spent route is served here too; see the building form
            // below for the measurement.
            if (spendTheEmptyRoute(worker)) {
                return;
            }
            PathFinder.Path path = world.findBattleNetPointPath(worker, tileX, tileY);
            worker.setBattleNetNavalPaidParkedRoute(false);
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
                    && worker.battleNetAiBehavior() == 2) {
                // Only a launched behavior-two force owns long-route assault
                // recovery. An ordinary map Patrol can belong to an AI
                // player too: XHuman 7 juggernaught 1573 exhausts its single
                // south heading at fixture 59 and goes Still. Treating every
                // AI player's Patrol as a missing legacy assault invents a
                // north-west route there and keeps the ship moving forever.
                if (!worker.hasBattleNetAiHome()) {
                    worker.setBattleNetAiHome(tileX, tileY);
                }
                // The assault home is the selected enemy's top-left tile.
                // Preserve its whole footprint in the long-route recovery:
                // a 2x2 tower can be reachable only from its south/east edge,
                // whose legal firing skirt is two tiles from that top-left.
                // Treating every home as 1x1 made the recovery answer
                // UNREACHABLE at XHuman 10's cannon tower (82,93), so grunts
                // 245/248 stood down permanently at the terrain boundary.
                Unit assaultGoal = world.unitAt(tileX, tileY);
                int goalWidth = assaultGoal == null || assaultGoal.type() == null
                        ? 1 : Math.max(1, assaultGoal.type().tileWidth());
                int goalHeight = assaultGoal == null || assaultGoal.type() == null
                        ? 1 : Math.max(1, assaultGoal.type().tileHeight());
                PathFinder.Path recovered = world.findMovementPath(worker,
                        new PathFinder.Goal(tileX, tileY,
                                goalWidth, goalHeight, 0, 1));
                if (recovered.result() == PathFinder.Result.FOUND
                        && recovered.length() > 0) {
                    path = recovered;
                } else {
                    worker.clearPath();
                    world.finishOrder(worker);
                    return;
                }
            }
            if (path.result() == PathFinder.Result.FOUND && path.length() == 0
                    && worker.order() == Unit.Order.PATROL
                    && worker.battleNetAiBehavior() != 2
                    && world.ais.containsKey(worker.player())) {
                // A map-authored patrol owned by an AI player is not an AI
                // force march. Once its compact route is exhausted, native
                // ends action 5 at the last residual pixel and constructs
                // Still; it does not run the behavior-two long-route rescue.
                // XHuman 7 juggernaught 1573 settles at 24,26 on fixture 59
                // with the old 22,27 order point retained.
                resetDisplacement(worker);
                worker.clearPath();
                worker.setRouteSpent(false);
                worker.setWaitCycles(0);
                world.finishOrder(worker);
                worker.setActionBeforeQueued(null);
                if (world.battleNetSequence != null) {
                    worker.setBattleNetSequenceOffset(
                            world.idle.battleNetStillSequenceStart(worker));
                    worker.setBattleNetAnimationTimer(1);
                    // Empty-FOUND finishes Patrol before the visit returns.
                    // The authenticated XHuman 7 async ledger records
                    // juggernaught 1573 advancing its naval idle byte while
                    // its raw cursor remains on Still 2955/timer 1: the zero
                    // byte pays AE30 at fixture 59, while the live byte merely
                    // decrements on the replacement arrival at fixture 103.
                    // Advancing the sequence here would consume the next
                    // Still OP0 a cycle early and promote the fixture-99
                    // replacement Patrol too soon.
                    if (worker.type().moveType()
                            != UnitType.Movement.LAND) {
                        world.idle.advanceBattleNetActiveOrderIdleRandom(worker);
                    }
                }
                return;
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
        return walkTowards(worker, building, false);
    }


    boolean walkTowards(Unit worker, Unit building,
            boolean activeOrderIdleRandomAlreadyPaid) {
        boolean depotDestArm = false;
        if (worker.order() == Unit.Order.HARVEST) {
            // DoActionMove increments PathFinderOutput::Cycles on every call,
            // including a cached step, a spent-route wait and an unreachable
            // answer. COrder_Resource consults this counter after the return
            // leg to decide whether the trip was long enough to seek a less
            // congested depot.
            worker.countResourceMoveCycle();
        }
        // A route-index-20 visit must consult the collision view from the
        // start of the native unit pass. Java has already visited higher-ID
        // workers, so a full-tile drawing offset is the surviving witness
        // that an ally occupied this leftover when the pass began. Orc 5
        // peasant 1532 must discard W from [SW,W], refill [SW,SW], and take
        // the fresh SW on this same visit after peasant 1529 moves west.
        if (building != null && building.type() != null
                && building.type().givesResource() == UnitType.Resource.GOLD
                && !worker.returningToDepot()
                && !worker.isMoving() && worker.stepDrained()
                && worker.battleNetWoodRouteIndex20()
                && worker.pathLength() == 2
                && Direction.isDiagonal(worker.peekHeading())) {
            int nextX = worker.tileX()
                    + Direction.deltaX(worker.peekHeading());
            int nextY = worker.tileY()
                    + Direction.deltaY(worker.peekHeading());
            if (world.unitAt(nextX, nextY) == null
                    && battleNetWorkerAllyJustVacated(
                            worker, nextX, nextY)) {
                worker.setBattleNetWoodRouteIndex20(false);
                worker.clearPath();
                worker.setRouteSpent(false);
                worker.setWaitCycles(0);
                worker.setBattleNetOrderDelay(0);
            }
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
        // A doubled laden tanker can finish the residual pixels of its last
        // accepted stride with one cached heading still behind the cursor.
        // MoveToDepot judges that boundary against the stored 0x41f430 order
        // point before NextPathElement may consume the leftover: throughout
        // the sealed campaign corpus, every residual settle at Chebyshev one
        // or two promotes action 24 to action 25 and parks route_index 20
        // (105 arrivals, including Human 7 tanker 1491 at fixture 252), while
        // every settle at three or farther remains action 24 (19 controls).
        // Letting stepMove see the remaining W in Human 7 moved the tanker
        // from (76,76) to (74,76) on that same visit instead of staging beside
        // the refinery.  Drain the pixels first, then expose an empty spent
        // route to depotRingAction25 below; do not pre-clear on earlier
        // residual visits because native retains the raw cursor until the
        // exact settle cycle.
        int resourceStride = world.battleNetMovementStride(worker);
        int depotOrderCheb = Math.max(
                Math.abs(worker.orderTargetX() - worker.tileX()),
                Math.abs(worker.orderTargetY() - worker.tileY()));
        boolean ladenTankerResidualAtDepotRing = worker.order()
                == Unit.Order.HARVEST
                && worker.returningToDepot() && worker.carried() > 0
                && resourceStride > 1
                && ("unit-human-oil-tanker".equals(worker.type().ident())
                        || "unit-orc-oil-tanker".equals(
                                worker.type().ident()))
                && worker.pathLength() > 0 && worker.isMoving()
                && depotOrderCheb <= resourceStride;
        if (ladenTankerResidualAtDepotRing) {
            int parkedHeading = worker.peekHeading();
            walkPixels(worker);
            if (worker.isMoving()) {
                return true;
            }
            worker.clearPath();
            worker.setRouteSpent(true);
            worker.setBattleNetSpentHeading(parkedHeading);
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
                    if (!Direction.isDiagonal(leftover)) {
                        // A long free prefix does not universally soft-hold its
                        // final progressive byte.  When that byte is cardinal,
                        // the occupied face owns a fresh native refusal
                        // generation: XHuman 10 peon 1438 drains the sixth
                        // heading at (17,114), parks S behind peon 1433 on
                        // fixture 115, and raises unit+0x1d's collision nibble
                        // from zero to one.  On the next visit retail refills a
                        // one-byte SE route and lets the bounded collision
                        // ladder arbitrate it.  Retaining S here instead made
                        // Java take the old cell the instant it cleared on
                        // fixture 117, twenty cycles before native.
                        int collision = worker.battleNetCollisionCounter() + 1;
                        battleNetRefuse(worker);
                        worker.setRouteSpent(false);
                        worker.setBattleNetCollisionCounter(
                                collision > 14 ? 0 : collision);
                        // setPath normally clears collision one for a fresh
                        // one-heading route.  The next route is instead the
                        // second generation of this same native refusal, so
                        // carry the nibble through that single refill.
                        worker.setBattleNetGoldCardinalTailRefusal(true);
                        worker.setBattleNetOrderDelay(0);
                        return true;
                    }
                    return true;
                }
            }
        }
        if (worker.battleNetGoldCardinalTailRefusal()
                && worker.battleNetRefusals() >= 8
                && worker.pathLength() == 0 && !worker.isMoving()
                && worker.waitCycles() == 0
                && worker.battleNetOrderDelay() == 0
                && building != null && building.type() != null
                && building.type().givesResource() == UnitType.Resource.GOLD
                && !worker.returningToDepot()) {
            // The complete refusal band wakes by retrying the original direct
            // approach face, not by handing the now-open position to another
            // wall-follow generation.  XHuman 10 slot 1438 therefore stores S
            // and steps to (17,115) on fixture 137; a fresh wall search chose
            // the equally free SW face and changed the mine queue's topology.
            int[] approach = world.battleNetApproachPoint(worker, building);
            int dx = Integer.signum(approach[0] - worker.tileX());
            int dy = Integer.signum(approach[1] - worker.tileY());
            if ((dx == 0) != (dy == 0)) {
                int direct = Direction.fromDelta(dx, dy);
                int directX = worker.tileX() + Direction.deltaX(direct);
                int directY = worker.tileY() + Direction.deltaY(direct);
                if (world.harvest.canEnterBattleNetResourceTarget(
                        worker, directX, directY)) {
                    worker.setPath(new PathFinder.Path(
                            PathFinder.Result.FOUND, new int[] {direct}));
                    worker.setBattleNetGoldCardinalTailRefusal(false);
                }
            }
        }
        if (worker.pathLength() == 0 && !worker.isMoving()) {
            boolean stageSpentBattleNetGold = false;
            boolean goldFreePrefixReplan = false;
            if (depotRingAction25(worker, building)) {
                // Leftover dest-arm landed on the MoveToDepot ring.
                // Native pays action 25 (land visit plus delay 2) then
                // dest-arms onto 0x41f430. PF_WAIT 10 entered Orc 1 at 65.
                worker.setRouteSpent(false);
                worker.setBattleNetOrderDelay(2);
                worker.setBattleNetResourceApproachStaged(true);
                // Every authenticated action-25 arrival starts its retained
                // movement sheet at delay three (105/105 corpus witnesses).
                worker.setBattleNetAnimationTimer(3);
                return true;
            }
            if (depotRingDestArm(worker, building)) {
                int[] entry = worker.carried() > 0
                        ? world.battleNetDepotPathPoint(worker, building)
                        : world.battleNetDepotEntryPoint(worker, building);
                int heading = Direction.fromDelta(
                        Integer.signum(entry[0] - worker.tileX()),
                        Integer.signum(entry[1] - worker.tileY()));
                worker.setPath(new PathFinder.Path(
                        PathFinder.Result.FOUND, new int[] {heading}));
                depotDestArm = true;
            } else if (worker.order() == Unit.Order.HARVEST && !worker.returningToDepot()
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
            // Most spent borrowed routes are served before the next ask:
            // NextPathElement answers PF_WAIT for the emptied buffer and
            // DoActionMove sleeps the ten. Oil action 23 has the authenticated
            // immediate-refill exception below.
            boolean outboundOilBufferRefill = worker.routeSpent()
                    && worker.order() == Unit.Order.HARVEST
                    && !worker.returningToDepot()
                    && building.type().givesResource()
                            == UnitType.Resource.OIL
                    && !world.harvest.atBattleNetResourceApproach(
                            worker, building);
            if (outboundOilBufferRefill) {
                // Oil action 23 asks 0x44fbd0 again on the visit that the
                // cached route exhausts; it does not enter the generic
                // PF_WAIT band used by an ordinary point walk. Orc 14 tanker
                // 1515 consumes SW,S,SE through fixture 71, finishes those
                // pixels on 103, then refills S,SE,S... and commits S in that
                // same callback. The old ten-count pause left a live tanker
                // visibly frozen at sea until fixture 114. Keep depot and
                // resource-skirt arrivals out: their actions 24/25 own those
                // boundaries.
                worker.setRouteSpent(false);
                worker.setWaitCycles(0);
            }
            int returnStride = world.battleNetMovementStride(worker);
            boolean ladenLandReturnBufferRefill = worker.routeSpent()
                    && worker.order() == Unit.Order.HARVEST
                    && worker.returningToDepot() && worker.carried() > 0
                    && returnStride == 1
                    && worker.type().landUnit()
                    && worker.distanceTo(building) > 1;
            boolean ladenTankerReturnBufferRefill = worker.routeSpent()
                    && worker.order() == Unit.Order.HARVEST
                    && worker.returningToDepot() && worker.carried() > 0
                    && returnStride > 1
                    && ("unit-human-oil-tanker".equals(worker.type().ident())
                            || "unit-orc-oil-tanker".equals(
                                    worker.type().ident()))
                    && worker.distanceTo(building) > returnStride;
            if (ladenLandReturnBufferRefill
                    || ladenTankerReturnBufferRefill) {
                // Action 24 refills an exhausted land-return buffer on the
                // residual-settle visit while the worker is still outside the
                // depot skirt. XHuman 10 peon 1596 drains its fourth heading
                // at (55,6), writes SW,SE, and commits SW to (54,7) on fixture
                // 310. Treating Chebyshev two from the depot path point as
                // action 25 inserted two quiet visits; serving PF_WAIT would
                // be later still. Doubled tankers use the same action-24
                // refill outside their wider depot skirt: Orc 7 tanker 1532
                // exhausts its one-byte NW buffer on fixtures 219 and 1029,
                // then refills and commits NW on the residual-settle visits
                // 251 and 1061. The one-stride tanker skirt is already owned
                // by depotRingAction25 above and remains action 25.
                worker.setRouteSpent(false);
                worker.setWaitCycles(0);
            }
            if (!depotDestArm && !stageSpentBattleNetGold && spendTheEmptyRoute(worker)) {
                return true;
            }
            if (!depotDestArm) {
                worker.setBattleNetEmptyDepotDirectReturnRoute(false);
                PathFinder.Path path = world.construction.findBattleNetBuildingPath(
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
            if (path.length() == 0
                    && world.ais().containsKey(worker.player())
                    && worker.order() == Unit.Order.HARVEST
                    && !worker.returningToDepot()
                    && building.type().givesResource()
                            == UnitType.Resource.GOLD
                    && worker.distanceTo(building) > 1
                    && !world.harvest.atBattleNetResourceApproach(
                            worker, building)) {
                // A freshly calculated all-0xff output is the same empty
                // route boundary for a borrowed resource walk as it is for a
                // plain Move.  Native FUN_004376c0 promotes Still and runs the
                // replacement Still marker in this unit visit.  That marker's
                // ready callback is important AI recovery, not presentation:
                // XHuman 12 peon 1365 cannot find a gold-mine route from
                // (9,88), becomes ready on fixture 90, and is reassigned to
                // the forest at (13,89).  Re-asking for the empty gold route
                // each cycle left the worker frozen forever.
                //
                // Do not take this boundary beside the resource or at its
                // exact legal approach.  An empty route there means the
                // resource state has arrived and must enter its gather/board
                // transition instead (XHuman 12 peon 1491's action-25
                // diagonal onto the mine footprint is the near witness).
                battleNetEmptyResourceRouteReady(worker, building);
                return false;
            }
            if (path.length() == 0
                    && worker.order() == Unit.Order.HARVEST
                    && worker.returningToDepot() && worker.carried() > 0
                    && worker.type().landUnit()
                    && world.battleNetMovementStride(worker) == 1
                    && !worker.isMoving()
                    && !worker.battleNetResourceHitRestoreIdle()
                    && worker.distanceTo(building) > 1) {
                // A laden hauler whose whole plan came back empty because an
                // ally parks on the direct next square used to install that
                // empty route and poll: it stepped onto the square the same
                // cycle the ally left. Orc 8's queue outside the mine is the
                // sealed witness -- native plans the refused face, counts a
                // refusal generation each blocked retry (Move-start/timer one,
                // route cursor parked at twenty), and from the eighth refusal
                // serves the fourteen-visit cooperative band to expiry, so
                // peasant 1504 takes the exit square its neighbour vacated at
                // fixture 253 only on 255. Handing the attempt a one-heading
                // route routes the outcome through that same ladder instead
                // of bypassing it.
                //
                // A Still cursor restored by the last call of a temporary
                // resource-hit Move is different. Human 8 peasant 1536 owns
                // action 24's empty-route idle callback there; turning it
                // into refusal generation one stole the following draw from
                // critter 1492. Explicit restore provenance distinguishes
                // that transaction from an ordinary first empty route, whose
                // counters and cursor are otherwise identical here.
                int faceX = Integer.signum(
                        worker.orderTargetX() - worker.tileX());
                int faceY = Integer.signum(
                        worker.orderTargetY() - worker.tileY());
                int heading = Direction.fromDelta(faceX, faceY);
                Unit blocker = world.unitAt(
                        worker.tileX() + Direction.deltaX(heading),
                        worker.tileY() + Direction.deltaY(heading));
                if (blocker != null && blocker.isOnMap()
                        && !blocker.isDying() && blocker != building
                        && world.isAllied(worker.player(), blocker.player())) {
                    path = new PathFinder.Path(
                            PathFinder.Result.FOUND, new int[] {heading});
                    worker.setBattleNetEmptyDepotDirectReturnRoute(true);
                }
            }
            if (world.harvest.beginBattleNetEmptyDepotRouteIdleBand(
                    worker, building, path,
                    activeOrderIdleRandomAlreadyPaid)) {
                return true;
            }
            worker.setBattleNetResourceHitRestoreIdle(false);
            boolean preserveCardinalTailCollision =
                    worker.battleNetGoldCardinalTailRefusal();
            int cardinalTailCollision = worker.battleNetCollisionCounter();
            worker.setPath(path);
            if (preserveCardinalTailCollision) {
                worker.setBattleNetCollisionCounter(cardinalTailCollision);
            }
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
        }
        Unit.Order saved = worker.order();
        worker.setBattleNetBorrowedMoveForStep(true);
        worker.setOrder(Unit.Order.MOVE);
        if (depotDestArm) {
            world.setMovementFieldFlags(building, false);
        }
        try {
            stepMove(worker, true, depotDestArm ? building : null);
        } finally {
            if (depotDestArm) {
                world.setMovementFieldFlags(building, true);
            }
            worker.setBattleNetBorrowedMoveForStep(false);
        }
        if (worker.order() != Unit.Order.DYING) {
            int retainedSequence = worker.battleNetSequenceOffset();
            int retainedTimer = worker.battleNetAnimationTimer();
            worker.setOrder(saved);
            // Restoring HARVEST after its borrowed Move normally clears the
            // action cursor. A refused laden route has just installed the
            // authoritative Move cursor, which remains the native action-24
            // program after the order enum returns to Resource. A cooperative
            // refusal keeps Move-start/15 throughout its quiet band (XHuman
            // 10 peon 1588: 2600/15 on fixture 270); a hard refusal exposes
            // Move-start/1 with route index twenty until the next action visit
            // redraws (peon 1584 at fixture 290). Keep only those live refusal
            // cursors; ordinary successful resource strides continue to use
            // the separate residual-pace projection.
            if (saved == Unit.Order.HARVEST
                    && worker.returningToDepot() && worker.carried() > 0
                    && worker.battleNetCollisionCounter() > 0
                    && retainedSequence >= 0
                    && (worker.pathLength() > 0
                            && worker.battleNetOrderDelay() > 0
                            || worker.pathLength() == 0
                                    && (worker.battleNetOrderDelay() > 0
                                            && worker.battleNetRefusals() >= 8
                                        || worker.battleNetOrderDelay() == 0
                                            && retainedTimer == 1))) {
                worker.setBattleNetSequenceOffset(retainedSequence);
                worker.setBattleNetAnimationTimer(retainedTimer);
            }
        }
        return true;
    }


    /** Completes a blocked resource walk and runs its ready assignment. */
    private void battleNetEmptyResourceRouteReady(
            Unit worker, Unit failedResource) {
        resetDisplacement(worker);
        worker.clearPath();
        worker.setBattleNetPlayerCommandMove(false);
        world.finishOrder(worker);
        worker.setActionBeforeQueued(null);
        worker.setOrderTarget(worker.tileX(), worker.tileY());

        // The borrowed resource executor goes straight from the failed walk
        // to UnitReady.  It does not pass through FUN_0040ad50's ordinary
        // standing-unit facing draw: XHuman 12's next tower hit proves the
        // async stream is untouched at the peon's fixture-90 reassignment.
        UnitType.Resource failedResourceKind = worker.carrying();
        boolean failedGold = failedResourceKind == UnitType.Resource.GOLD;
        if (failedGold) {
            // UnitReady's failed-gold terrain lookup supplies y-1 to the
            // square-ring walker. Mark that call before it searches; a
            // normal AI lumber assignment remains anchor-centred.
            worker.setBattleNetWoodReadyPathRequired(true);
        }
        world.battleNetUnitReadyAfterResourceFailure(worker, failedResource);
        if (failedGold
                && worker.order() == Unit.Order.HARVEST
                && worker.carrying() == UnitType.Resource.WOOD) {
            // A failed gold path re-enters UnitReady, whose terrain assignment
            // owns action 23 rather than the standing-adjacent chop shortcut.
            // Human 5 peon 1567 receives nearby wood at fixture 104 but does
            // not claim or draw; it retries the terrain route and first-steps
            // east on 122. Starting work in place consumed SyncRand on 107.
            // assignHarvester retains this marker only when the ordinary
            // terrain result was adjacent and native selected its path-gated
            // fallback. Distant wood routes remain ordinary harvest walks.
        } else if (failedGold) {
            worker.setBattleNetWoodReadyPathRequired(false);
        }
        if (world.battleNetSequence == null) {
            return;
        }
        if (worker.order() == Unit.Order.HARVEST
                && worker.carrying() == UnitType.Resource.WOOD) {
            int gatherStart = world.idle.battleNetSequenceStart(
                    worker, BattleNetSequence.ATTACK_ANIMATION);
            if (gatherStart >= 0) {
                worker.setBattleNetSequenceOffset(gatherStart);
                worker.setBattleNetAnimationTimer(3);
            }
        } else if (worker.order() == Unit.Order.STILL) {
            worker.setBattleNetSequenceOffset(
                    world.idle.battleNetStillSequenceStart(worker));
            worker.setBattleNetAnimationTimer(1);
        }
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
            // A ready-marker regroup exposes the Move program immediately,
            // before its first route is calculated. Native XHuman 12 grunt
            // 1363 is at Move start 2477/timer 3 on fixture 199; retaining
            // the old Still cursor also kept its occupancy solid to the wood
            // planner that runs later in the same scheduler pass.
            // The ordinary ready-marker callback constructs Move animation
            // here. A UNIT.Data guard reaches the recurring behavior-one
            // callback through a different native arm: its Still action
            // marker has already constructed the next Still body before Move
            // becomes current. Human 13 ogre 1501 therefore exposes Move with
            // Still 581/timer 3 at fixture 249, not Move 586.
            int constructedStart = unit.battleNetReadySuppressed()
                    ? world.idle.battleNetStillSequenceStart(unit)
                    : world.idle.battleNetSequenceStart(unit,
                            BattleNetSequence.MOVE_ANIMATION);
            if (constructedStart >= 0) {
                unit.setBattleNetSequenceOffset(constructedStart);
                unit.setBattleNetAnimationTimer(3);
            }
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
    /**
     * Empty send-home leftover-landed one or two tiles from 0x41f430.
     * Native pays action 25 (this visit plus delay 2) then dest-arms
     * onto that point. PF_WAIT 10 entered Orc 1 at 65 instead of 75.
     * A laden doorstep still serves the empty ten.
     */
    boolean depotRingAction25(Unit worker, Unit building) {
        return depotRingReady(worker, building, true);
    }

    /**
     * After action 25, leftover dest-arm onto 0x41f430 rather than
     * asking a new route. A replan used to overwrite that heading.
     */
    boolean depotRingDestArm(Unit worker, Unit building) {
        return depotRingReady(worker, building, false);
    }

    /** Moving action-25 depot body that is transparent to the final dest-arm. */
    private Unit battleNetDrainingDepotEntryBlocker(
            Unit worker, Unit depot, int entryX, int entryY) {
        if (worker == null || depot == null || worker.type() == null
                || !worker.type().landUnit()
                || world.battleNetMovementStride(worker) != 1
                || !worker.returningToDepot() || worker.carried() <= 0
                || !worker.battleNetResourceApproachStaged()
                || worker.pathLength() != 1 || worker.isMoving()) {
            return null;
        }
        // The depot building remains first in UnitCache even while its field
        // bit is temporarily clear, so blockerOnLayer cannot identify the
        // mobile body sharing the footprint. Walk the live units and select
        // the one whose actual footprint covers this exact entry anchor.
        for (Unit blocker : world.unitsSnapshot()) {
            if (blocker == worker || !blocker.isAlive()
                    || !blocker.isOnMap() || blocker.isDying()
                    || blocker.type() == null || blocker.type().building()
                    || !blocker.type().landUnit()
                    || entryX < blocker.tileX()
                    || entryY < blocker.tileY()
                    || entryX >= blocker.tileX()
                            + Math.max(1, blocker.type().tileWidth())
                    || entryY >= blocker.tileY()
                            + Math.max(1, blocker.type().tileHeight())
                    || blocker.order() != Unit.Order.HARVEST
                    || !blocker.returningToDepot() || blocker.carried() <= 0
                    || blocker.returnDepotGoal() != depot
                    || !blocker.battleNetResourceApproachStaged()
                    || !blocker.isMoving() || blocker.pathLength() != 0
                    || !blocker.routeSpent()
                    || blocker.battleNetCollisionCounter() != 0
                    || !world.isAllied(worker.player(), blocker.player())) {
                continue;
            }
            // This is action 25's entry transaction, not ordinary Move-body
            // soft-clear. Orc 12 slot 1507 keeps refusal generation nine
            // (unit+0x1d == 0x90) while its spent final-entry pixels drain,
            // yet slot 1502 commits onto the same hall point at fixture 383.
            // The strict resource/depot/stage predicates above identify that
            // body without making refusal-marked allies generally passable.
            return blocker;
        }
        return null;
    }

    private boolean depotRingReady(Unit worker, Unit building, boolean action25) {
        if (worker == null || building == null || !worker.returningToDepot()
                || worker.pathLength() > 0
                || worker.isMoving()) {
            return false;
        }
        if (action25) {
            boolean landedLadenRoute = worker.carried() > 0
                    && worker.stepDrained();
            if ((!worker.routeSpent() && !landedLadenRoute)
                    || worker.battleNetResourceApproachStaged()) {
                return false;
            }
        } else if (worker.routeSpent() || worker.battleNetOrderDelay() > 0
                || !worker.battleNetResourceApproachStaged()) {
            return false;
        }
        int[] entry = worker.carried() > 0
                ? world.battleNetDepotPathPoint(worker, building)
                : world.battleNetDepotEntryPoint(worker, building);
        if (worker.tileX() == entry[0] && worker.tileY() == entry[1]) {
            return false;
        }
        int cheb = Math.max(Math.abs(entry[0] - worker.tileX()),
                Math.abs(entry[1] - worker.tileY()));
        // An empty Return Goods order can leftover-land two anchors from its
        // exact entry point and then dest-arm (Orc 1/2). A laden land worker
        // remains in action 24 at that outer point and refills its route; its
        // action-25 skirt begins one anchor from the contracted depot point
        // (XHuman 10 peon 1596: (55,6) continues, (54,7) stages). Doubled
        // tankers retain their wider hull-specific arm below.
        boolean ladenLandOutsideFootprintRange = worker.carried() > 0
                && world.battleNetMovementStride(worker) == 1
                && worker.distanceTo(building) > 1;
        if (cheb >= 1 && cheb <= 2
                && !ladenLandOutsideFootprintRange) {
            return true;
        }
        // A doubled laden route can finish one anchor beyond that point ring
        // while the mover's 2x2 hull is already within one doubled movement
        // stride of the depot footprint. XHuman 8 tanker 1538 settles at
        // (60,56) for the refinery at (56,57): its contracted path point is
        // (57,57), Chebyshev three, but
        // native promotes action 24 to action 25 on that settle visit and
        // banks three visits later. Serving the generic empty-route ten here
        // left the tanker visibly parked outside a usable refinery.
        return action25 && worker.carried() > 0
                && world.battleNetMovementStride(worker) > 1
                && worker.stepDrained()
                && worker.distanceTo(building)
                        <= world.battleNetMovementStride(worker);
    }

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

    /**
     * Whether leftover dest-arm landed one or two passable tiles short
     * of a player Move click.
     *
     * <p>That remaining heading is not an intermediate empty buffer.
     * Native batch-1/26 takes the last tile when leftover lands one
     * short. batch-1/31 leftover-lands two short of 75,91 and dest-arms
     * through 74,91 onto the click; a PF_WAIT 10 there is still Move at
     * the window. A farther spent prefix still pays the empty ten so a
     * repath can form.
     */
    /**
     * GiveOrder 17 leftover-land that still has one heading is Attack
     * Ground, not the remaining Move leftover. Native peon 1594 lands
     * 26,17 at 24, pays timer 3, then dest-arms E; continuing the leftover
     * as Move Still'd at 40 dest 28,18.
     */
    boolean leftoverLandedBesideForest(Unit unit, int destX, int destY) {
        if (unit == null || world.canEnter(unit, destX, destY)) {
            return false;
        }
        Unit occupant = world.unitAt(destX, destY);
        if (occupant != null && occupant.type() != null
                && occupant.type().building()) {
            return false;
        }
        return !world.battleNetTerrainPassable(unit, destX, destY);
    }

    /**
     * An Attack leftover borrows MOVE with attackGoal on the enemy and
     * orderTarget unset. Promoting that used to steal attack-1/00 into
     * Attack Ground at leftover-land. Only a player Move dest that is
     * still short of a different attackGoal is GiveOrder 17.
     */
    boolean promoteGiveOrderAttackGroundAfterLeftover(Unit unit) {
        if (unit == null || !unit.battleNetPlayerCommandMove()
                || !unit.battleNetAttackGroundMove()
                || unit.order() != Unit.Order.MOVE
                || unit.pathLength() != 1) {
            return false;
        }
        int destX = unit.orderTargetX();
        int destY = unit.orderTargetY();
        if (destX < 0 || destY < 0) {
            return false;
        }
        int goalX = unit.attackGoalX();
        int goalY = unit.attackGoalY();
        if (goalX < 0 || goalY < 0
                || (goalX == destX && goalY == destY)) {
            return false;
        }
        unit.setOrder(Unit.Order.ATTACK_GROUND);
        unit.setOrderTarget(goalX, goalY);
        // Leftover-land visit already stands. Two remaining quiet visits
        // match native timer 3,2,1 then dest-arm at 27.
        unit.setBattleNetOrderDelay(2);
        return true;
    }

    /**
     * Whether a spent 20-byte route should ask {@code 0x44fbd0} now.
     *
     * <p>Native {@code 0x44fab0} fails when {@code unit+0x7e >= 20}.
     * {@code 0x437c80} then pathfinds on that visit unless {@code 0x4374a0}
     * says the order point is already in range. Critters keep the empty
     * Still path at {@code 0x4376c0}.
     */
    private boolean battleNetEmptyRouteRefillsImmediately(Unit unit) {
        if (!unit.routeSpent() || battleNetCommandPointReached(unit)) {
            return false;
        }
        if (unit.type() != null && "unit-critter".equals(unit.type().ident())) {
            return false;
        }
        return world.map.contains(unit.orderTargetX(), unit.orderTargetY());
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

    /** Whether an occupied doubled-grid side belongs to a friendly sea hull. */
    private boolean battleNetAlliedNavalHull(Unit unit, Unit blocker) {
        return blocker != null && blocker != unit
                && blocker.isAlive() && blocker.isOnMap()
                && blocker.type() != null && blocker.type().seaUnit()
                && !blocker.type().building()
                && world.isAllied(unit.player(), blocker.player());
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
        int refusedHeading = unit.pathLength() > 0
                ? unit.peekHeading() : -1;
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
        boolean meleeAttackChase = unit.target() != null
                && unit.target().isAlive()
                && unit.chasing()
                && !World.battleNetRangedChaseUnit(unit)
                && (unit.order() == Unit.Order.ATTACK
                        || unit.order() == Unit.Order.ATTACK_MOVE
                        || unit.battleNetBorrowedMoveForStep());
        if (meleeAttackChase && refusals < 15) {
            // FUN_004379e0 returns every parked Attack approach to Move OP0;
            // it does not let the live animation cursor advance while the
            // same occupied route head is retried. Callers which add a full
            // cooperative band may overwrite the timer with fifteen, but the
            // cursor owner is common to every melee chase refusal.
            // Attack dispatch owns the paid delay too. A generic Unit.Wait is
            // drained before stepAttack and therefore cannot mirror the live
            // Move 15..1 cursor or reach the stage-four Attack handoff.
            unit.setWaitCycles(0);
            unit.setBattleNetOrderDelay(refusals >= 8 ? 14 : 0);
            int moveStart = world.idle.battleNetSequenceStart(unit,
                    BattleNetSequence.MOVE_ANIMATION);
            if (moveStart >= 0) {
                unit.setBattleNetSequenceOffset(moveStart);
                unit.setBattleNetAnimationTimer(refusals >= 8 ? 15 : 1);
                unit.setBattleNetChaseStepReady(false);
            }
        }
        if (refusals == 8 && meleeAttackChase
                && unit.battleNetCollisionCounter() == 0) {
            // The eighth refusal of a melee Attack approach does not wake
            // directly into another route probe.  Its complete Move band
            // returns through active-order Attack construction 3,2,1 first.
            // Keep that ownership with the native refusal primitive itself:
            // several route-park callers reach FUN_004379e0 outside the main
            // multi-step hard-refusal arm, but the recovery rule is identical.
            // Collision-zero is the parked-residual form: the byte itself
            // remains behind native route index twenty for the complete paid
            // band. Preserve it separately from Java's empty-path projection
            // so Attack construction's later Move probes retry this exact
            // face instead of drawing a fresh wall route. XHuman 10 slot 1475
            // keeps blocked E through fixture 210; a fresh draw selects the
            // free SE face. Ordinary collided approaches do not retain it:
            // XHuman 4 grunt 1505 enters refusal eight with collision one and
            // redraws until its north route frees on fixture 54.
            if (refusedHeading >= 0 && refusedHeading < Direction.COUNT) {
                unit.setBattleNetParkedRefusalHeading(refusedHeading);
            }
            unit.setBattleNetAttackRefusalRecoveryStage(4);
        }
        return refusals;
    }

    /** Whether a settled blocked suffix still belongs to one paid Attack tail. */
    private boolean paidAttackTailGenerationPark(Unit unit) {
        return unit.battleNetAttackWrapDestArmPending()
                && unit.battleNetTailWrapRouteTarget() == unit.target()
                && unit.battleNetCollisionCounter() > 0
                && unit.battleNetRefusals() == 0
                && unit.battleNetPathStepsTaken() > 0
                && unit.stepDrained() && !unit.isMoving()
                && unit.target() != null
                && unit.target().isAlive()
                && unit.target().type() != null
                && !unit.target().type().building()
                && !World.battleNetRangedChaseUnit(unit);
    }

    /** Parks one completed collision generation of a retained Attack tail. */
    private void parkPaidAttackTailGeneration(Unit unit, int heading) {
        // The cached suffix remains part of the paid Attack-tail route after
        // each complete collision band. A blocked wake advances the packed
        // collision generation and parks the cursor at RI20 instead of
        // spending a free cardinal component of the refused diagonal. The
        // first generation retains its blocked face for the same wall
        // transaction; later generations have already consumed that wall and
        // hand the next Move OP0 a cold route buffer. XHuman 4 slot 1518 is
        // 0x10/RI1 at fixture 311, 0x20/RI20 at 312, then redraws
        // N,NE,E,E,SE,SW on 313. Its next blocked NE advances 0x20 to 0x30 at
        // fixture 329 without stepping N, then cold-redraws and consumes SE on
        // fixture 330.
        int previousCollision = unit.battleNetCollisionCounter();
        int collision = previousCollision + 1;
        int parkedHeading = previousCollision == 1 ? heading : -1;
        int parkedSteps = unit.battleNetPathStepsTaken();
        battleNetRefuse(unit);
        unit.setBattleNetCollisionCounter(collision > 14 ? 0 : collision);
        unit.setBattleNetRefusals(0);
        unit.setRouteSpent(false);
        unit.setWaitCycles(0);
        unit.setBattleNetOrderDelay(0);
        unit.setBattleNetChaseEmptyRouteReplan(true);
        unit.setBattleNetRetargetResidualParkRefill(true);
        unit.setBattleNetRetargetResidualParkSteps(parkedSteps);
        unit.setBattleNetParkedRefusalHeading(parkedHeading);
        unit.markBattleNetLongPaidWrapParkedRoute();
        world.causalTrace.event(world.cycle,
                "path.paid-attack-tail-generation-park",
                unit.id(), "target", unit.target().id(),
                "collision", unit.battleNetCollisionCounter());
    }

    /**
     * Applies the native refusal ladder to a behavior-six small-warship map
     * Patrol through its terminal ninth-refusal band.
     *
     * <p>XHuman 7 destroyer 1570 first records two refusals against 1562,
     * replans west, and then both hulls finish that stride against destroyer
     * 1573. The earlier count synchronizes their refusal eight on fixture 42;
     * refusal nine owns the 15-count band at 57. When that band expires at
     * fixture 72, Patrol's empty-route handler selects Still while the native
     * refusal nibble remains nine. The launched behavior-two assault path is
     * excluded: those ships must keep recovering toward their combat home.</p>
     *
     * <p>The near-goal bound and route provenance separate this terminal
     * Patrol refusal from a body encountered on a longer route. XOrc 8
     * destroyer 1431 is still seventeen tiles from its map point when
     * submarine 1432 blocks its leftover northwest heading. Submarine 1433 is
     * already within four tiles of its map point, but has consumed six bytes
     * of the same cached route when submarine 1434 blocks byte seven. Native
     * gives both residuals the ordinary fifteen-count and then continues the
     * remaining route. XHuman 7's terminal pair has consumed only its opening
     * west stride when this helper takes over, so that short terminal route
     * remains part of the ladder.</p>
     */
    private boolean refuseBattleNetNavalMapPatrol(Unit unit) {
        if (unit == null || unit.type() == null
                || !unit.type().seaUnit()
                || World.isBattleNetCapitalShip(unit.type().ident())
                || unit.battleNetAiBehavior() == 2
                || unit.target() != null
                || unit.patrolX() < 0 || unit.patrolY() < 0
                || Math.max(Math.abs(unit.tileX() - unit.orderTargetX()),
                        Math.abs(unit.tileY() - unit.orderTargetY())) > 4
                || unit.isMoving()
                || unit.pathLength() != 1
                || unit.battleNetPathStepsTaken() > 1) {
            return false;
        }
        battleNetRefuse(unit);
        unit.setRouteSpent(false);
        unit.setBattleNetOrderDelay(0);
        return true;
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
                || !(unit.order() == Unit.Order.ATTACK
                        || unit.order() == Unit.Order.ATTACK_MOVE
                        || unit.chasing())) {
            return;
        }
        boolean executingMove = executingBattleNetMoveProgram(unit);
        // Ranged chases ask for the next route byte directly from Move OP0.
        // A melee residual can expose the same ready bit while retaining its
        // live Move body: XHuman 12 grunt 1503 must keep that cursor, mark its
        // one-byte route spent, and replan east on the next visit. Rewinding
        // every ready unit instead put the grunt into a fifteen-count wait
        // and let an axethrower steal its square at fixture 39.
        boolean refusedOnActionMarker = unit.battleNetChaseStepReady()
                && (World.battleNetRangedChaseUnit(unit)
                        || unit.battleNetMovingQuarryResidual());
        // An ordinary refusal inside an unfinished Move body keeps its live
        // cursor. A refusal on the OP0 visit is different: the interpreter
        // has already exposed ChaseStepReady and advanced past Move start,
        // but native leaves the cursor on Move start for the entire refusal
        // band. XHuman 10 axethrower 1496 is 830/1 before its occupied east
        // probe and 830/15 after it; allowing Java to retain 833/1 made that
        // thrower finish its movement program nineteen visits early and fire
        // an axe while native was still waiting.
        //
        // A settled multi-step residual is the wrap boundary itself:
        // native parks the Move start, refills the rewritten route on the
        // next visit, and then pays timer 15. XHuman 10 grunt 1490 reaches
        // that boundary at fixture 41. Letting Java's old Move cursor wrap
        // through OP0 while the logical refusal delay counted down retargeted
        // it at 53 and armed a second hold; native remains on Move start and
        // steps east at 57 after one band.
        //
        // A settled moving-quarry melee route is the other OP0-owned refusal,
        // even though ordinary melee residuals keep their live body cursor.
        // Human 8 attack-peasant 1520 refreshes its stale quarry point and
        // refuses the cached north-east head on fixture 180; native rewinds
        // directly to Move 2600/15. Leaving Java on 2603/1 drained two body
        // visits before its logical delay began and shifted the wake to 197.
        boolean settledMultiResidual = settledMultiResidualRefusal(unit);
        boolean stagedMobileResidualRefill = settledMultiResidual
                && world.actionMoveWalked
                && (unit.battleNetRefusals() > 0
                        || unit.battleNetCollisionCounter() > 1);
        boolean retainedBuildingResidual = settledMultiResidual
                && unit.target() != null && unit.target().type() != null
                && unit.target().type().building();
        boolean postRetargetParkRefill =
                unit.battleNetRetargetResidualParkRefill();
        if (executingMove && !refusedOnActionMarker
                && !settledMultiResidual
                && !postRetargetParkRefill) {
            return;
        }
        int moveStart = world.idle.battleNetSequenceStart(unit,
                BattleNetSequence.MOVE_ANIMATION);
        if (moveStart < 0) {
            return;
        }
        unit.setBattleNetSequenceOffset(moveStart);
        // A residual which settles on this callback while carrying hard-
        // refusal provenance first leaves native at Move-start/1 with its
        // route spent. The following scheduler visit refills the route and
        // writes 15; orderDelay mirrors that delayed write through
        // syncBattleNetAttackRefusalTimer. A later refusal visit with no step
        // settling is already inside FUN_004379e0 and writes 15 immediately.
        //
        // The first cooperative refusal of a retained residual is different:
        // its route cursor remains live and Move/15 is written on the settle
        // visit itself. Authenticated XHuman 12 grunt 1463 keeps route index
        // two and its five remaining headings while changing 2534/1 directly
        // to 2482/15 at fixture 123. Treating every two-step residual as a
        // staged refill delayed each later tile by one callback.
        // A stable building goal retains the already-approved route buffer;
        // it has no moving-quarry refill visit to stage. Its refusal enters
        // timer fifteen immediately, while mobile-target residuals first
        // expose Move-start/1 and refill on the following callback.
        unit.setBattleNetAnimationTimer(
                stagedMobileResidualRefill && !retainedBuildingResidual
                        ? 1 : 15);
        unit.setBattleNetChaseStepReady(false);
        // The Move refusal band does not hand an attacking unit straight
        // back to a walk. Native returns through Attack-start construction,
        // then gives Move one route-plan-only visit before the first heading.
        // Remember ownership here; battleNetOrderDelay alone loses the
        // provenance as soon as its final count is spent.
        if (!executingMove && unit.battleNetPersonSplashHelpAttack()) {
            unit.setBattleNetAttackRefusalRecoveryStage(1);
        }
        if (World.BNE_PEND_TRACE
                && unit.battleNetAttackRefusalRecoveryStage() == 1) {
            System.err.printf("JBNEREFUSALRECOVERY cycle=%d unit=%d "
                            + "arm stage=1 delay=%d seq=%d/%d%n",
                    world.cycle, unit.id(), unit.battleNetOrderDelay(),
                    unit.battleNetSequenceOffset(),
                    unit.battleNetAnimationTimer());
        }
    }

    private boolean settledMultiResidualRefusal(Unit unit) {
        return unit != null && unit.type() != null
                // The native witness and both route-index counterexamples
                // are grunt Move programs. Workers and ogres reach similar
                // Java surrogate state through different action seams; do
                // not infer this route-index transfer for those types.
                && "unit-grunt".equals(unit.type().ident())
                && unit.stepDrained()
                && !unit.isMoving() && unit.pathLength() > 1
                // Route index is part of the native branch. A refusal after
                // the first heading keeps the live cache and writes 15 on
                // that visit (XHuman 10 grunt 1477). After two headings,
                // grunt 1490 first parks index 20 at timer 1, then refills
                // and writes 15 on the following visit.
                && unit.battleNetPathStepsTaken() >= 2;
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

    /** Mirrors a surrogate order hold in the native action-program timer. */
    void syncBattleNetAttackRefusalTimer(Unit unit) {
        if (world.battleNetSequence == null || unit == null
                || unit.battleNetOrderDelay() <= 0) {
            return;
        }
        int moveStart = world.idle.battleNetSequenceStart(unit,
                BattleNetSequence.MOVE_ANIMATION);
        boolean retainedStageSixCardinalBand =
                unit.battleNetAttackRefusalRecoveryStage() == 4
                && unit.battleNetStageSixCardinalProbePark();
        if (moveStart >= 0
                && (unit.battleNetSequenceOffset() == moveStart
                        || retainedStageSixCardinalBand)) {
            // The retained route-index-twenty probe can enter its paid band
            // after the Move interpreter has already exposed the next body
            // offset. Its stage-six provenance is the authority to pin the
            // native cursor back to Move-start for the whole 15..1 band.
            unit.setBattleNetSequenceOffset(moveStart);
            unit.setBattleNetAnimationTimer(unit.battleNetOrderDelay());
            return;
        }
        int attackStart = world.idle.battleNetSequenceStart(unit,
                BattleNetSequence.ATTACK_ANIMATION);
        int timer = unit.battleNetAnimationTimer();
        Unit target = unit.target();
        if (attackStart >= 0
                && unit.battleNetSequenceOffset() == attackStart
                && unit.battleNetRetargetResidualRoutePark()
                && (target == null
                        || !world.targets.validAttackTarget(unit, target))
                // Only the cold Attack constructor is 3,2,1. Attack OP0
                // body holds can share the start offset with much larger
                // timers and are owned by the sequence interpreter itself.
                && timer >= 1 && timer <= 3
                && unit.battleNetOrderDelay() < timer) {
            // A replan residual whose quarry has entered Die still completes
            // the two quiet visits after installing Attack-start/3. Native
            // exposes the sequence timer as 2 then 1 before the order is
            // destroyed. Keep live-quarry route/refusal ownership unchanged;
            // its timer lifecycle has additional formation witnesses.
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
     * Attack at the arrival band belonging to that action. COrder_Attack,
     * including the temporary Java MOVE seam used by MoveToTarget, owns every
     * borrowed pixel: XHuman 9's skeleton drains ten through fixture 46,
     * Orc 1's commanded grunt drains ten through fixture 204, and Human 1's
     * post-swing refill drains through 417.
     */
    private boolean arriveMeleeLeftoverOnOccupiedQuarry(Unit unit) {
        if (unit == null || unit.type() == null
                || unit.type().maxAttackRange() != 1
                || (unit.pathLength() != 1
                        && !(unit.battleNetAttackWaitRefillResidual()
                                && unit.pathLength() == 0))
                || unit.target() == null
                || World.battleNetRangedChaseUnit(unit)
                || (unit.order() != Unit.Order.ATTACK
                        && unit.order() != Unit.Order.ATTACK_MOVE
                        && !unit.chasing())) {
            return false;
        }
        // A quarry that is still draining its own committed step keeps this
        // Attack-owned residual alive even if the outer action is temporarily
        // represented as MOVE. Human 8's harvesting peasant settles while
        // its pursuer still owes seven pixels; retail pays all seven before
        // entering Attack, rather than taking the borrowed-Move eight-pixel
        // arrival band.
        if (world.battleNetSequence != null
                && unit.target().isMoving() && unit.isMoving()
                && unit.target().order() == Unit.Order.HARVEST) {
            unit.setBattleNetMovingQuarryResidual(true);
        }
        int debt = Math.max(Math.abs(unit.offsetX()), Math.abs(unit.offsetY()));
        // A surrogate Move body chasing a quarry uses MoveToTarget's
        // occupied-square band. The real Attack order and a SetAutoTarget
        // rename do not, and an exhausted refill has explicit ownership even
        // when that borrowed Move body has already replaced the outer label.
        boolean attackOrderOwnsResidual = unit.order() == Unit.Order.ATTACK
                || unit.autoTargeting()
                || (unit.chasing() && unit.battleNetBorrowedMoveForStep());
        boolean ownsEveryPixel = unit.battleNetAttackWaitRefillResidual()
                || unit.battleNetMovingQuarryResidual()
                || attackOrderOwnsResidual;
        if (debt > (ownsEveryPixel ? 0 : 8)) {
            return false;
        }
        int heading = unit.pathLength() == 1
                ? unit.peekHeading() : unit.lastStepHeading();
        int nextX = unit.tileX() + Direction.deltaX(heading);
        int nextY = unit.tileY() + Direction.deltaY(heading);
        Unit quarry = world.unitAt(nextX, nextY);
        if (quarry == null || quarry != unit.target()
                || (unit.battleNetMovingQuarryResidual()
                        && quarry.isMoving())
                || !world.targets.inAttackRange(unit, quarry)) {
            return false;
        }
        unit.clearPath();
        unit.setRouteSpent(false);
        unit.setBattleNetCollisionCounter(0);
        unit.setBattleNetChaseEmptyRouteReplan(false);
        unit.setBattleNetAttackWaitRefillResidual(false);
        unit.setBattleNetMovingQuarryResidual(false);
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
        stepMove(unit, replanOnExhaustion, null);
    }

    private void stepMove(Unit unit, boolean replanOnExhaustion,
            Unit depotDestArmGoal) {
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
                    + " curranim-is-move=%d wait=%d anim=%d ix=%d iy=%d dir=%d path=%d peek=%d"
                    + " fast=%d residual=%d,%d drained=%d chasing=%d pathgoal=%d,%d"
                    + " delay=%d free-detour=%d land-patrol-route=%d"
                    + " empty-replan=%d wrap-dest=%d target=%d@%d,%d%n",
                    world.cycle, unit.id(), unit.walkHolding() ? 1 : 0,
                    unit.animation().unbreakable() ? 1 : 0,
                    unit.animation().current() == dbgMove ? 1 : 0,
                    unit.animation().waitCycles(), unit.animation().index(),
                    unit.offsetX(), unit.offsetY(), unit.direction(),
                    unit.pathLength(), unit.pathLength() == 0
                            ? -1 : unit.peekHeading(),
                    unit.pathWaitBudget(),
                    unit.residualX(), unit.residualY(),
                    unit.stepDrained() ? 1 : 0, unit.chasing() ? 1 : 0,
                    unit.pathGoalX(), unit.pathGoalY(),
                    unit.battleNetOrderDelay(),
                    unit.battleNetNearlyFullFreeDetour() ? 1 : 0,
                    unit.battleNetLandPatrolAttackRoutePending() ? 1 : 0,
                    unit.battleNetChaseEmptyRouteReplan() ? 1 : 0,
                    unit.battleNetAttackWrapDestArmPending() ? 1 : 0,
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
        boolean navalPatrolAttackHandoff =
                unit.battleNetNavalPatrolAttackConstruction()
                && unit.battleNetNavalPatrolAttackTimerOneReady();
        boolean mayDecide = navalPatrolAttackHandoff
                || (chaseMoveSequence
                        ? unit.battleNetChaseStepReady()
                        : !unit.walkHolding() && atMoveBoundary(unit));
        if (mayDecide && walkedThisCycle && unit.stepDrained()
                && !unit.isMoving()
                && unit.battleNetBorrowedMoveForStep()
                && unit.returningToDepot() && unit.carried() > 0
                && unit.type() != null && unit.type().landUnit()
                && world.battleNetMovementStride(unit) == 1
                && unit.pathLength() > 0
                && unit.returnDepotGoal() != null) {
            int[] refreshedDepotEdge = world.battleNetDepotEntryPoint(
                    unit, unit.returnDepotGoal());
            int north = Direction.fromDelta(0, -1);
            int northwest = Direction.fromDelta(-1, -1);
            boolean staleLateralDepotReaimTail =
                    (unit.pathLength() == 1
                            && unit.peekHeading() == northwest)
                    || (unit.pathLength() == 2
                            && unit.peekHeading() == north
                            && unit.peekHeadingAtDepth(1) == northwest);
            boolean lateralDepotReaim =
                    unit.battleNetCollisionCounter() == 0
                    && unit.lastStepHeading()
                            == Direction.fromDelta(1, -1)
                    && refreshedDepotEdge[1] == unit.orderTargetY()
                    && Math.abs(refreshedDepotEdge[0]
                            - unit.orderTargetX()) == 2;
            if (lateralDepotReaim) {
                unit.setOrderTarget(
                        refreshedDepotEdge[0], refreshedDepotEdge[1]);
            }
            if (lateralDepotReaim && staleLateralDepotReaimTail) {
                // This is the retained-tail footprint handoff proved by the
                // sealed XHuman 12 route, not a rule for every nearest-edge
                // change. The NE residual crosses a four-wide hall's lateral
                // midpoint with the cached NW tail still live; native
                // publishes the opposite edge, parks that tail at cursor
                // twenty with collision one, and redraws on the following
                // visit. The two-byte N,NW form is retained for callers that
                // have not passed through the convoy-transparent route view.
                // A duplicate N,N tail remains live while the edge point
                // changes: XHuman 11 slot 1495 refreshes (18,84) to (20,84)
                // and consumes N on fixture 320.
                // Other gold carriers keep their cached route when their edge
                // drifts by one cell or under a different residual shape.
                int collision = unit.battleNetCollisionCounter() + 1;
                unit.setBattleNetCollisionCounter(
                        collision > 14 ? 0 : collision);
                unit.clearPath();
                unit.setRouteSpent(false);
                unit.setWaitCycles(0);
                unit.setBattleNetOrderDelay(0);
                int moveStart = world.idle.battleNetSequenceStart(
                        unit, BattleNetSequence.MOVE_ANIMATION);
                if (moveStart >= 0) {
                    unit.setBattleNetSequenceOffset(moveStart);
                    unit.setBattleNetAnimationTimer(1);
                    unit.setBattleNetChaseStepReady(false);
                }
                return;
            }
            int returnHeading = unit.peekHeading();
            int returnStride = world.battleNetMovementStride(unit);
            int returnX = unit.tileX()
                    + Direction.deltaX(returnHeading) * returnStride;
            int returnY = unit.tileY()
                    + Direction.deltaY(returnHeading) * returnStride;
            Unit returnBlocker = world.blockerOnLayer(
                    unit, returnX, returnY);
            int returnDeltaX = Direction.deltaX(returnHeading);
            int returnDeltaY = Direction.deltaY(returnHeading);
            boolean shiftedDepotEdge =
                    (refreshedDepotEdge[0] != unit.orderTargetX()
                            || refreshedDepotEdge[1] != unit.orderTargetY())
                    && Math.max(
                            Math.abs(refreshedDepotEdge[0]
                                    - unit.orderTargetX()),
                            Math.abs(refreshedDepotEdge[1]
                                    - unit.orderTargetY())) == 1;
            boolean freshDepotQueueHead =
                    unit.battleNetCollisionCounter() == 0
                    && unit.pathLength() == 1
                    && unit.battleNetPathStepsTaken() > 0
                    && !Direction.isDiagonal(returnHeading)
                    && shiftedDepotEdge
                    && refreshedDepotEdge[0] == returnX + returnDeltaX
                    && refreshedDepotEdge[1] == returnY + returnDeltaY
                    && returnBlocker != null && returnBlocker != unit
                    && returnBlocker.order() == Unit.Order.HARVEST
                    && returnBlocker.returningToDepot()
                    && returnBlocker.carried() > 0
                    && returnBlocker.returnDepotGoal()
                            == unit.returnDepotGoal()
                    && returnBlocker.battleNetResourceApproachStaged()
                    && !returnBlocker.isMoving()
                    && returnBlocker.pathLength() == 0
                    && !returnBlocker.routeSpent()
                    && returnBlocker.battleNetOrderDelay() == 2
                    && returnBlocker.battleNetCollisionCounter() == 0
                    && returnBlocker.battleNetRefusals() == 0
                    && returnBlocker.distanceTo(unit.returnDepotGoal()) <= 1
                    && world.isAllied(
                            unit.player(), returnBlocker.player());
            if (freshDepotQueueHead) {
                // The lower pool slot has just drained its spent route into
                // action 25. When this follower tests the last cached ray in
                // the same pass, retail refreshes the edge, parks the
                // consumed byte with collision one, and redraws around that
                // newly staged leader on the following visit (XOrc 12 slots
                // 1337/1342, fixtures 373..374). Match only the fresh
                // two-delay, zero-collision queue head one cell outside the
                // refreshed edge; ordinary one-byte depot rays retain their
                // refusal ladder below.
                unit.setOrderTarget(
                        refreshedDepotEdge[0], refreshedDepotEdge[1]);
                unit.setBattleNetCollisionCounter(1);
                unit.clearPath();
                unit.setRouteSpent(false);
                unit.setWaitCycles(0);
                unit.setBattleNetOrderDelay(0);
                int moveStart = world.idle.battleNetSequenceStart(
                        unit, BattleNetSequence.MOVE_ANIMATION);
                if (moveStart >= 0) {
                    unit.setBattleNetSequenceOffset(moveStart);
                    unit.setBattleNetAnimationTimer(1);
                    unit.setBattleNetChaseStepReady(false);
                }
                return;
            }
            boolean pressuredLadenConvoyTail =
                    unit.battleNetCollisionCounter() == 0
                    && unit.battleNetPathStepsTaken() > 0
                    && returnBlocker != null && returnBlocker != unit
                    && returnBlocker.isMoving()
                    && returnBlocker.returningToDepot()
                    && returnBlocker.carried() > 0
                    && returnBlocker.returnDepotGoal()
                            == unit.returnDepotGoal()
                    && world.isAllied(
                            unit.player(), returnBlocker.player())
                    // Native keeps this convoy body solid when unit+0x1d's
                    // collision nibble is set. Java may carry that raw owner
                    // only in its separate refusal proxy after the body has
                    // spent its visible route, so accept either projection.
                    && (returnBlocker.battleNetCollisionCounter() > 0
                            || returnBlocker.battleNetRefusals() > 0);
            if (pressuredLadenConvoyTail) {
                // A collision-marked returner is no longer the clean moving
                // convoy for which FUN_004379e0 preserves a cached heading
                // through Move 15..1. The consumed route parks for this one
                // visit and the resource order redraws on the next. XHuman 12
                // slot 1552 drains its NW residual at fixture 302 while raw
                // collision-one slot 1561 occupies the cached north square;
                // native writes RI20/collision one, then redraws NE,NW and
                // commits NE on fixture 303.
                unit.setBattleNetCollisionCounter(1);
                unit.clearPath();
                unit.setRouteSpent(false);
                unit.setWaitCycles(0);
                unit.setBattleNetOrderDelay(0);
                int moveStart = world.idle.battleNetSequenceStart(
                        unit, BattleNetSequence.MOVE_ANIMATION);
                if (moveStart >= 0) {
                    unit.setBattleNetSequenceOffset(moveStart);
                    unit.setBattleNetAnimationTimer(1);
                    unit.setBattleNetChaseStepReady(false);
                }
                return;
            }
        }
        if ((walkedThisCycle || world.actionMoveWalked)
                && unit.stepDrained()
                && !unit.isMoving()
                && unit.battleNetLandPatrolAttackRoutePending()) {
            // The first route generated after a land Patrol -> Attack pop is
            // allowed to commit its opening heading. When that heading's
            // pixels finish, native parks the remaining route at index 20
            // and returns through active-order idle dispatch instead of
            // consuming the Java pathfinder's longer cached tail. That idle
            // callback owns one asynchronous draw and re-arms Attack 3,2,1.
            //
            // This Patrol owner is itself the native decision boundary; it
            // does not wait for Java's generic Move OP0 gate. Keep the cached
            // tail while the first stride is in flight: it remains
            // cooperative movement authority for nearby walkers. Hardening
            // the opening path itself changed collision timing for an
            // unrelated builder beside XHuman 12's ogre 1356. Parking only
            // here preserves both the authentic NE stride and the native
            // construction callback at fixture 88.
            unit.setBattleNetLandPatrolAttackRoutePending(false);
            unit.clearPath();
            unit.setRouteSpent(false);
            unit.setBattleNetCollisionCounter(0);
            unit.setBattleNetRefusals(0);
            unit.setBattleNetChaseStepReady(false);
            world.combat.rearmBattleNetHardRefusalAttack(unit);
            return;
        }
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
        //   - XHuman 10 peon 1584: a refused route replans NE,NW,N with
        //     collision one; after NE drains, the free N shortcut behind NW
        //     still writes route_index 20 and increments collision to two at
        //     fixture 54, then commits at 55.
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
                    if (unit.battleNetPathStepsTaken() == 1) {
                        // First residual of the route: XHuman 4 peon 1570
                        // waits two fifteen-count bands behind peon 1578
                        // before taking its second E.
                        unit.setBattleNetOrderDelay(29);
                        mayDecide = false;
                    } else if (unit.battleNetPathStepsTaken() >= 2) {
                        // A later residual owns a different native branch.
                        // XHuman 7 peon 1458 has already settled SE then E
                        // when its remaining E,E meets peon 1451. Retail
                        // parks route index 20 on fixture 53, then refills
                        // SE,NE and takes SE on 54 instead of waiting for the
                        // old east cell. Keep this visit as the route park;
                        // the resource order lays the replacement next visit.
                        unit.clearPath();
                        unit.setRouteSpent(false);
                        unit.setWaitCycles(0);
                        unit.setBattleNetOrderDelay(0);
                        return;
                    }
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
                // A route freshly replacing a refusal retains that refusal
                // nibble. When its first diagonal drains with a free
                // cardinal shortcut behind the reverse diagonal, native
                // still passes through
                // FUN_004379e0: XHuman 10 peon 1584 changes 0x1d 0x10→0x20
                // and route_index 1→20 at fixture 54, then takes N on 55.
                // Do not apply this without the two-heading shortcut. Peon
                // 1437's ally-blocked S is itself refused on fixture 38 and
                // replans SE on 39; an extra park before that refusal made
                // the whole route one cycle late.
                int residualShortcut = unit.pathLength() == 2
                        ? BattleNetPathFinder.twoHeadingShortcut(
                                unit.lastStepHeading(), unit.peekHeading())
                        : -1;
                int residualStride = world.battleNetMovementStride(unit);
                boolean refusedFreeRemainder =
                        unit.battleNetCollisionCounter() > 0
                        && unit.battleNetPathStepsTaken() == 1
                        && residualShortcut >= 0
                        && !Direction.isDiagonal(residualShortcut)
                        && world.canEnter(unit,
                                unit.tileX()
                                        + Direction.deltaX(residualShortcut)
                                                * residualStride,
                                unit.tileY()
                                        + Direction.deltaY(residualShortcut)
                                                * residualStride);
                // Orc 5's peasant 1529 pays a quiet visit on its second
                // residual settle when a diagonal leftover holds an ally.
                boolean orcFiveSeam = settles >= 2
                        && unit.pathLength() == 2
                        && Direction.isDiagonal(unit.peekHeading());
                if (refusedFreeRemainder) {
                    unit.setBattleNetCollisionCounter(
                            unit.battleNetCollisionCounter() + 1);
                    unit.setBattleNetWoodRouteIndex20(true);
                    mayDecide = false;
                } else if (ally && orcFiveSeam) {
                    unit.setBattleNetWoodRouteIndex20(true);
                    mayDecide = false;
                }
            }
        }
        if (mayDecide && !goldMidRoute && !chaseMoveSequence
                && unit.stepDrained() && !unit.isMoving()
                && !unit.returningToDepot() && unit.resourceUnit() == null
                && unit.resourceTileX() >= 0 && unit.resourceTileY() >= 0
                && unit.pathLength() == 2
                && unit.battleNetPathInitialLength() > unit.pathLength()
                && unit.battleNetPathStepsTaken() > 0) {
            int heading = unit.peekHeading();
            int stride = world.battleNetMovementStride(unit);
            Unit blocker = world.blockerOnLayer(unit,
                    unit.tileX() + Direction.deltaX(heading) * stride,
                    unit.tileY() + Direction.deltaY(heading) * stride);
            boolean alliedWorker = blocker != null && blocker != unit
                    && blocker.isOnMap() && !blocker.isDying()
                    && blocker.type() != null
                    && ("unit-peon".equals(blocker.type().ident())
                            || "unit-peasant".equals(
                                    blocker.type().ident()))
                    && world.isAllied(unit.player(), blocker.player());
            if (alliedWorker) {
                // A terrain worker whose committed residual lands behind a
                // two-byte allied-worker tail keeps that tail under the
                // complete FUN_004379e0 Move band. The cursor is not parked
                // for a shortcut or an action-23 redraw. XHuman 12 peons 1385
                // and 1360 independently retain SE,NE and E,SE respectively;
                // their collision generations differ, so the two-byte
                // residual transaction rather than a particular nibble owns
                // the band. Six-byte shortcut and four-byte terminal-
                // construction routes keep their separate handlers below.
                int collision = unit.battleNetCollisionCounter() + 1;
                unit.setBattleNetCollisionCounter(
                        collision > 14 ? 0 : collision);
                unit.setBattleNetRefusals(0);
                unit.setRouteSpent(false);
                unit.setWaitCycles(0);
                unit.setBattleNetOrderDelay(14);
                unit.setBattleNetRefusalHold(true);
                int moveStart = world.idle.battleNetSequenceStart(
                        unit, BattleNetSequence.MOVE_ANIMATION);
                if (moveStart >= 0) {
                    unit.setBattleNetSequenceOffset(moveStart);
                }
                unit.setBattleNetAnimationTimer(15);
                return;
            }
        }
        if (mayDecide && unit.battleNetNearlyFullFreeDetour()
                && unit.stepDrained() && !unit.isMoving()) {
            // A detached detour can itself be the complete native route. If
            // it lands beside a standing occupant on the exact Move point,
            // consume that one-heading route and execute replacement Still
            // on the settle visit instead of gliding along the old tail.
            if (unit.order() == Unit.Order.MOVE
                    && unit.pathLength() == 0 && unit.routeSpent()
                    && battleNetTerminalOccupiedPointFrom(
                            unit, unit.tileX(), unit.tileY())) {
                unit.setBattleNetNearlyFullFreeDetour(false);
                finishBattleNetMoveAtTarget(unit, 1);
                int replacementPhase = unit.battleNetIdlePhase();
                unit.setBattleNetIdlePhase(replacementPhase + 1);
                world.idle.dispatchBattleNetIdleMarker(unit,
                        PudUnitTypes.code(unit.type().ident()),
                        replacementPhase);
                world.battleNetEmptyRouteIdled = unit;
                return;
            }
            // A free-compass heading detached from a refused route is not
            // permission to consume the old route underneath it.
            // Once that detour drains, native parks route_index 20 on the Move
            // opening OP0 and replans on the following visit. XHuman 12 grunt
            // 1494 therefore yields on fixture 53 and steps fresh SE on 54;
            // pure-Move ogre 1527 yields on 59 and steps fresh E on 60.
            int parkedCollision = unit.battleNetCollisionCounter();
            unit.setBattleNetNearlyFullFreeDetour(false);
            unit.clearPath();
            unit.setRouteSpent(false);
            unit.setWaitCycles(0);
            unit.setBattleNetOrderDelay(0);
            // A paid bounded collision prefix retains its native high nibble
            // while the approved heading drains, then increments it on the
            // route-index-20 park. XHuman 12 grunt 1520 records 0x20 through
            // its S residual and 0x30 on fixture 74. Detached free-compass
            // Move detours have no such provenance and still clear here.
            unit.setBattleNetCollisionCounter(parkedCollision >= 2
                    ? parkedCollision + 1 : 0);
            int moveStart = world.idle.battleNetSequenceStart(unit,
                    BattleNetSequence.MOVE_ANIMATION);
            if (moveStart >= 0) {
                unit.setBattleNetSequenceOffset(moveStart);
                unit.setBattleNetAnimationTimer(1);
                unit.setBattleNetChaseStepReady(false);
            }
            return;
        }
        boolean stepped = false;
        if (mayDecide && promoteGiveOrderAttackGroundAfterLeftover(unit)) {
            return;
        }
        if (mayDecide) {
            if (chaseMoveSequence && unit.walkHolding()) {
                unit.setWalkHolding(false);
                resetDisplacement(unit);
            }
            if (unit.pathLength() == 0) {
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
                    // A residual can empty one stored route while the Move
                    // still has a usable continuation. Native asks 0x44fbd0
                    // for that continuation before it promotes the mover to
                    // Still and performs target acquisition. XHuman 2's
                    // opening ogres drain route index seven at fixture 93,
                    // refill a north-east course, and remain Move even with
                    // hostiles in react range. Scanning first made both ogres
                    // freeze on their previous tiles and start attacking.
                    if (!"unit-critter".equals(unit.type().ident())
                            && !unit.chasing()
                            && unit.attackMoveX() < 0
                            && unit.attackMoveY() < 0
                            && battleNetEmptyRouteRefillsImmediately(unit)) {
                        unit.setRouteSpent(false);
                        stepMoveOrder(unit);
                        return;
                    }
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
                if (unit.battleNetPlayerCommandMove()
                        && !battleNetCommandPointReached(unit)) {
                    // A fresh player Move can replace a resource order while
                    // its committed stride still owes pixels. That inherited
                    // residual has no route for the new click; once it drains,
                    // retail asks for the replacement route instead of
                    // promoting Still. Human 5 peasant 1512 is clicked away
                    // from its harvest at fixture five and continues to
                    // 34,103 after completing the old stride.
                    if (moveOrderDepth > 1) {
                        // Already asked once this visit. Asking again used
                        // to recurse until the stack died on NerzyvsHTOSGOW
                        // after a two-short leftover dest-arm.
                        return;
                    }
                    if (promoteGiveOrderAttackGroundAfterLeftover(unit)) {
                        return;
                    }
                    stepMoveOrder(unit);
                    return;
                }
                if (unit.order() == Unit.Order.MOVE
                        && !unit.battleNetPlayerCommandMove()
                        && !unit.battleNetBorrowedMoveForStep()
                        && !unit.chasing()
                        && unit.attackMoveX() < 0
                        && unit.attackMoveY() < 0
                        && !chaseMoveSequence
                        && battleNetEmptyRouteRefillsImmediately(unit)) {
                    // Non-aggressive movers do not enter the target-scan arm
                    // above, but they own the same immediate route refill.
                    // XOrc 12 mage 1368 drains its fourth heading at fixture
                    // 77 and commits the first heading of the replacement
                    // route on that visit. Falling through to PF_WAIT 10 kept
                    // it parked on 19,82 until fixture 88.
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
            // A complete native route buffer is authoritative too. XHuman 7
            // submarine 1511 retains S after four SE headings on fixture 266
            // even though free SE is strictly closer to its patrol point.
            // Orc 13 tanker 1454 independently retains free E after four SE
            // headings on fixture 687 while free NE is strictly closer. The
            // XOrc 11 destroyer correction above owns a short four-byte
            // wall-follow residual, not either full-buffer route. A longer
            // paid patrol route is authoritative as well: XOrc 8 destroyer
            // 1431 retains W with six of seven bytes left at fixture 311 even
            // though free N is closer to its odd patrol point.
            boolean capitalDoubleStep = unit.battleNetDoubleStep()
                    && ("unit-battleship".equals(unit.type().ident())
                    || "unit-ogre-juggernaught".equals(unit.type().ident()));
            if (unit.battleNetDoubleStep()
                    && !capitalDoubleStep
                    && unit.type().seaUnit()
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
                    // A Patrol-origin unit may already have promoted to
                    // Attack while retaining the old patrol coordinates.
                    // MoveToTarget borrows this method under a temporary MOVE
                    // label, so the live quarry—not that stale patrol point—
                    // owns its freshly generated wall route. XOrc 11
                    // destroyer 1519 receives native NW,W,SW at fixture 127;
                    // the patrol residual correction rewrote the NW head to N.
                    && unit.target() == null
                    && !unit.chasing()
                    && unit.resourceUnit() == null
                    && unit.pathLength() > 1
                    && unit.battleNetPathInitialLength() <= 4
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

            // A residual-settled sea patrol has already selected and cached
            // its attack route, but native keeps that route parked while
            // Attack construction counts 3,2,1. Do not treat the occupied
            // first heading as a refusal and erase it during those quiet
            // visits; XOrc 11 destroyer 1542 keeps SW toward the dragon.
            if (unit.battleNetNavalPatrolAttackConstruction()
                    && !unit.battleNetNavalPatrolAttackTimerOneReady()) {
                return;
            }

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
            Unit loadedTankerHorizontalSide = null;
            Unit loadedTankerVerticalSide = null;
            if (canTakeStep
                    && unit.type().gathering().containsKey(
                            UnitType.Resource.OIL)
                    && unit.returningToDepot() && unit.carried() > 0
                    && unit.carrying() == UnitType.Resource.OIL
                    && unit.battleNetDoubleStep()
                    && unit.stepDrained() && !unit.isMoving()
                    && unit.battleNetPathStepsTaken() > 0
                    && Direction.isDiagonal(heading)) {
                loadedTankerHorizontalSide = world.blockerOnLayer(unit,
                        unit.tileX() + Direction.deltaX(heading) * stride,
                        unit.tileY());
                loadedTankerVerticalSide = world.blockerOnLayer(unit,
                        unit.tileX(),
                        unit.tileY() + Direction.deltaY(heading) * stride);
            }
            boolean loadedTankerSqueezedDiagonal =
                    battleNetAlliedNavalHull(
                            unit, loadedTankerHorizontalSide)
                    && battleNetAlliedNavalHull(
                            unit, loadedTankerVerticalSide)
                    && loadedTankerHorizontalSide
                            != loadedTankerVerticalSide;
            if (loadedTankerSqueezedDiagonal) {
                // A doubled action-24 hull cannot sweep diagonally between
                // two occupied cardinal side anchors even when the diagonal
                // anchor itself is free. Orc 8 tanker 1478 settles N at
                // (84,104) with cached NW between the returning tanker at
                // (84,102) and destroyer at (82,104). Native raises the
                // sticky refusal 11 -> 12, parks the route, pays Move 15..1,
                // then replans N after the tanker has vacated. Testing only
                // the destination anchor committed NW on fixture 289.
                battleNetRefuse(unit);
                unit.setRouteSpent(false);
                unit.setBattleNetOrderDelay(0);
                world.causalTrace.event(world.cycle,
                        "path.loaded-tanker-squeezed-diagonal", unit.id(),
                        "heading", heading,
                        "horizontal_blocker",
                                loadedTankerHorizontalSide.id(),
                        "vertical_blocker", loadedTankerVerticalSide.id(),
                        "refusals", unit.battleNetRefusals());
                return;
            }
            if (!canTakeStep && depotDestArmGoal != null
                    && battleNetDrainingDepotEntryBlocker(
                            unit, depotDestArmGoal, nextX, nextY) != null) {
                // The resource order's final dest-arm owns a different
                // occupancy decision from ordinary action 24. Retail lets it
                // commit behind a worker whose own action-25 entry pixels are
                // still draining, briefly stacking both workers on the depot
                // point. XOrc 12 slots 1394/1396 do this at fixture 264;
                // XHuman 7 slots 1458/1446 repeat it at 330; Orc 12 slots
                // 1502/1507 prove that a retained refusal generation does not
                // change this final-entry transaction at fixture 383.
                canTakeStep = true;
            }
            boolean paidParkedRouteReplay =
                    unit.hasBattleNetLongPaidWrapParkedRoute()
                    && unit.pathLength() > 0
                    && unit.stepDrained() && !unit.isMoving()
                    && unit.target() != null
                    && !World.battleNetRangedChaseUnit(unit);
            if (!canTakeStep && paidParkedRouteReplay) {
                Unit paidReplayBlocker = world.unitAt(nextX, nextY);
                if (battleNetPaidParkedRouteWillVacate(
                        unit, paidReplayBlocker)) {
                    // Native visits the blocker's lower pool slot first. Its
                    // paid redraw vacates the cell before this retained route
                    // spends its first byte. Java's id order is the reverse,
                    // so permit the brief cache overlap which that later visit
                    // removes (XHuman 12 slots 1490 then 1517, fixture 263).
                    canTakeStep = true;
                }
            }
            if (paidParkedRouteReplay) {
                unit.clearBattleNetLongPaidWrapParkedRoute();
            }
            if (!canTakeStep
                    && unit.battleNetNavalPatrolAttackConstruction()
                    && unit.battleNetNavalPatrolAttackTimerOneReady()) {
                Unit patrolBlocker = world.blockerOnLayer(unit, nextX, nextY);
                boolean sameCyclePatrolVacate = patrolBlocker != null
                        && patrolBlocker != unit
                        && world.isAllied(unit.player(), patrolBlocker.player())
                        && patrolBlocker.type() != null
                        && patrolBlocker.type().seaUnit()
                        && patrolBlocker.battleNetDoubleStep()
                        && patrolBlocker.order() == Unit.Order.PATROL
                        && world.battleNetMoveAnimation(patrolBlocker)
                        && (patrolBlocker.isMoving()
                                || patrolBlocker.stepDrained());
                if (sameCyclePatrolVacate) {
                    // Retail visits the battleship's higher Java-id native
                    // slot first, so it has vacated 8,26 before the destroyer
                    // first-steps SW. Java's opposite creation order sees the
                    // old occupancy here; the cache permits this brief overlap
                    // and the later patrol visit removes it in the same tick.
                    canTakeStep = true;
                }
            }
            if (unit.battleNetNavalPatrolAttackConstruction()
                    && unit.battleNetNavalPatrolAttackTimerOneReady()) {
                unit.setBattleNetNavalPatrolAttackConstruction(false);
                unit.setBattleNetNavalPatrolAttackTimerOneReady(false);
            }
            // An empty-route retarget residual returns from Attack-four on
            // the same native visit that a higher-slot cooperative ally
            // vacates the replacement route's first cell. Java visits this
            // grunt first, so its cache still contains that ally even though
            // native has already let the ally move. The unit cache supports
            // the brief overlap and rebuilds occupancy when the ally leaves;
            // admit only this authenticated post-hold OP0 step, and only for
            // a cooperative blocker that is already moving elsewhere.
            // XHuman 12 grunt 1507 therefore commits E onto 28,38 at fixture
            // 55 instead of soft-waiting one extra visit.
            if (!canTakeStep
                    && chaseMoveSequence
                    && unit.battleNetChaseStepReady()
                    && unit.battleNetChaseEmptyRouteReplan()
                    && unit.battleNetChaseReplanResidualHold()) {
                Unit postHoldBlocker = world.unitAt(nextX, nextY);
                if (world.battleNetCooperativeBlocker(
                        unit, postHoldBlocker)) {
                    canTakeStep = true;
                }
            }
            // A live route installed by a melee retarget owns one more native
            // boundary after its first step and Attack-four residual hold.
            // When its cached next square refuses, 0x450ad0 parks the old
            // cursor on this visit; the following visit lays the replacement
            // route and may then enter FUN_004379e0's cooperative wait. Losing
            // that provenance made Java wait on the stale heading and later
            // walk into the traffic it should have routed around (XHuman 12
            // grunt 1492: park c41, replacement SW + timer 15 c42).
            if (unit.battleNetRetargetResidualRoutePark()
                    && unit.stepDrained() && !unit.isMoving()
                    && unit.pathLength() > 0
                    && unit.target() != null
                    && !World.battleNetRangedChaseUnit(unit)
                    && (unit.order() == Unit.Order.ATTACK
                            || unit.order() == Unit.Order.ATTACK_MOVE
                            || unit.chasing())) {
                Unit residualParkBlocker = !canTakeStep
                        ? world.unitAt(nextX, nextY) : null;
                boolean paidConstructionCooperativeBlocker =
                        unit.battleNetAttackRefusalRecoveryStage() == 3
                        && residualParkBlocker != null
                        && world.isAllied(unit.player(),
                                residualParkBlocker.player())
                        && battleNetRefusalBandSoftClearMoveAlly(
                                residualParkBlocker);
                boolean saturatedPaidRouteParkCandidate = canTakeStep
                        && unit.battleNetAttackRefusalRecoveryStage() == 3
                        && unit.pathLength()
                                >= BattleNetPathFinder.MAX_PATH - 1
                        && unit.battleNetPathStepsTaken() <= 1;
                boolean saturatedPaidRoutePark =
                        saturatedPaidRouteParkCandidate
                        && !unit.battleNetAttackWrapDestArmPending()
                        && (unit.battleNetRefusalHold()
                                || unit.battleNetCollisionCounter() > 0);
                boolean completedLongPaidWrapRouteBand = canTakeStep
                        && (unit.battleNetAttackRefusalRecoveryStage() == 3
                                || unit.battleNetLongPaidWrapTimerOneSeen())
                        && unit.battleNetAttackWrapDestArmPending()
                        && unit.battleNetPathInitialLength() == 5
                        && unit.pathLength() == 4
                        && unit.battleNetPathStepsTaken() == 1
                        && unit.battleNetCollisionCounter() == 0
                        && unit.battleNetRefusals() == 0;
                boolean saturatedBuildingRetargetFirstRetry =
                        !canTakeStep
                        && unit.battleNetRetargetResidualParkRefill()
                        && unit.battleNetRetargetResidualParkSteps() == 1
                        && unit.battleNetCollisionCounter() == 1
                        && unit.battleNetRefusals() == 0
                        && unit.battleNetPathInitialLength()
                                == BattleNetPathFinder.MAX_PATH
                        && unit.pathLength() == BattleNetPathFinder.MAX_PATH
                        && unit.target().type() != null
                        && unit.target().type().building()
                        && residualParkBlocker != null
                        && residualParkBlocker != unit
                        && world.isAllied(unit.player(),
                                residualParkBlocker.player());
                boolean consumedNearSaturatedRetargetFirstRefusal =
                        !canTakeStep
                        && unit.battleNetPathInitialLength()
                                == BattleNetPathFinder.MAX_PATH - 1
                        && unit.pathLength()
                                == BattleNetPathFinder.MAX_PATH - 2
                        && unit.battleNetPathStepsTaken() == 1
                        && unit.battleNetCollisionCounter() == 0
                        && unit.battleNetRefusals() == 0;
                if (saturatedBuildingRetargetFirstRetry) {
                    // Attack construction has completed, but its first direct
                    // building-footprint byte still belongs to the saturated
                    // route transaction which armed it. Preserve the consumed
                    // one-step provenance when the generic residual-route park
                    // records the first naked refusal; otherwise the following
                    // visits cold-plan around the formation. XHuman 12 grunt
                    // 1492 writes RI20 on fixture 235 and retries southwest.
                    unit.setBattleNetRetargetResidualRoutePark(false);
                    unit.setBattleNetCollisionCounter(2);
                    unit.clearPath();
                    unit.setRouteSpent(false);
                    unit.setWaitCycles(0);
                    unit.setBattleNetOrderDelay(0);
                    unit.setBattleNetRetargetResidualParkRefill(true);
                    unit.setBattleNetRetargetResidualParkSteps(1);
                    int moveStart = world.idle.battleNetSequenceStart(unit,
                            BattleNetSequence.MOVE_ANIMATION);
                    if (moveStart >= 0) {
                        unit.setBattleNetSequenceOffset(moveStart);
                        unit.setBattleNetAnimationTimer(1);
                        unit.setBattleNetChaseStepReady(false);
                    }
                    return;
                }
                if (completedLongPaidWrapRouteBand
                        && !unit.battleNetLongPaidWrapTimerOneSeen()) {
                    // Timer one exposes Move's action marker but does not run
                    // NewPath on that same visit. Preserve the route for this
                    // callback; the marker distinguishes the following RI-20
                    // park from the just-completed fifteen-count band.
                    unit.setBattleNetLongPaidWrapTimerOneSeen(true);
                    return;
                }
                unit.setBattleNetRetargetResidualRoutePark(false);
                if (!canTakeStep
                        && (unit.battleNetSaturatedRetargetRouteBand()
                                || consumedNearSaturatedRetargetFirstRefusal)) {
                    // A collision-saturated retarget, or a nineteen-heading
                    // retarget after its first committed step, already paid
                    // for this replacement buffer. Keep its approved tail
                    // through the post-construction Move band instead of
                    // parking and cold-replanning around the formation.
                    // XHuman 12 grunt 1496 keeps eighteen headings and arms
                    // Move 15 at fixture 256; discarding them let Java step
                    // southeast on 257 while retail remained on 36,39.
                    boolean continuedParkedFace =
                            unit.battleNetParkedRefusalHeading() >= 0
                            && unit.battleNetParkedRefusalHeading()
                                    < Direction.COUNT;
                    unit.setBattleNetSaturatedRetargetRouteBand(true);
                    int collision =
                            unit.battleNetCollisionCounter() + 1;
                    unit.setBattleNetCollisionCounter(
                            collision > 14 ? 0 : collision);
                    unit.setWaitCycles(0);
                    unit.setBattleNetOrderDelay(14);
                    unit.setBattleNetRefusalHold(true);
                    int moveStart = world.idle.battleNetSequenceStart(unit,
                            BattleNetSequence.MOVE_ANIMATION);
                    if (moveStart >= 0) {
                        unit.setBattleNetSequenceOffset(moveStart);
                        unit.setBattleNetAnimationTimer(15);
                        unit.setBattleNetChaseStepReady(false);
                    }
                    if (continuedParkedFace) {
                        // The continued face now owns its complete paid Move
                        // band. Retire the one-refill provenance so its later
                        // wake follows the ordinary cached-route lifecycle.
                        unit.setBattleNetSaturatedRetargetRouteBand(false);
                        unit.setBattleNetParkedRefusalHeading(-1);
                    }
                    return;
                }
                unit.setBattleNetSaturatedRetargetRouteBand(false);
                if ((saturatedPaidRoutePark
                                || completedLongPaidWrapRouteBand
                                || !canTakeStep)
                        && !paidConstructionCooperativeBlocker) {
                    // A saturated route is parked by the completed Attack
                    // constructor even when its next square has opened. The
                    // old free-head test made XHuman 12's native slots 1479
                    // and 1481 leave formation one visit early at fixture
                    // 166. Retail advances their collision bytes 0->1 and
                    // 1->2, parks route index 1/0 at 20, and only lets the
                    // following NewPath visit spend a replacement heading.
                    // The same route-cursor transaction follows a retained
                    // four-byte wrap tail after its complete paid Move band:
                    // XHuman 12 grunt 1517 keeps timer one on fixture 261,
                    // parks E,SE,SE,SW at RI 20 on 262, then redraws and
                    // spends SE on 263. Spending the free stale E here moves
                    // it a full tile before retail.
                    int parkedSteps = unit.battleNetPathStepsTaken();
                    int collision = unit.battleNetCollisionCounter() + 1;
                    unit.setBattleNetCollisionCounter(
                            collision > 14 ? 0 : collision);
                    if (completedLongPaidWrapRouteBand) {
                        unit.parkBattleNetLongPaidWrapTail();
                    }
                    unit.clearPath();
                    unit.setRouteSpent(false);
                    unit.setWaitCycles(0);
                    unit.setBattleNetOrderDelay(0);
                    unit.setBattleNetRetargetResidualParkRefill(true);
                    unit.setBattleNetRetargetResidualParkSteps(parkedSteps);
                    int moveStart = world.idle.battleNetSequenceStart(unit,
                            BattleNetSequence.MOVE_ANIMATION);
                    if (moveStart >= 0) {
                        unit.setBattleNetSequenceOffset(moveStart);
                        unit.setBattleNetAnimationTimer(1);
                        unit.setBattleNetChaseStepReady(false);
                    }
                    return;
                }
            }
            if (unit.battleNetRetargetResidualRoutePark()
                    && unit.stepDrained() && !unit.isMoving()
                    && unit.pathLength() > 0
                    && unit.target() != null
                    && World.battleNetRangedChaseUnit(unit)
                    && (unit.order() == Unit.Order.ATTACK
                            || unit.order() == Unit.Order.ATTACK_MOVE
                            || unit.chasing())) {
                // A ranged retarget parks its replacement tail after the first
                // committed leg and the Attack-four handoff, even when the next
                // cached square is free. The path was drawn before that leg and
                // is stale at this boundary; retail writes route_index 20 for
                // one visit, then refills and commits a fresh heading on the
                // following visit (XHuman 12 axe 1523: park 107, SE 108).
                world.causalTrace.event(world.cycle,
                        "path.ranged-retarget-tail-park", unit.id(),
                        "path_length", unit.pathLength(),
                        "heading", unit.peekHeading(),
                        "target", unit.target().id());
                unit.setBattleNetRetargetResidualRoutePark(false);
                unit.clearPath();
                unit.setRouteSpent(false);
                unit.setWaitCycles(0);
                unit.setBattleNetOrderDelay(0);
                return;
            }
            // Armed air Patrol does not put its first cardinal route behind
            // the generic fifteen-count body wait. When an allied flyer
            // occupies that anchor, retail rewrites the route to the two
            // adjacent diagonals and commits the first in this visit. XOrc 8
            // gryphon 1560 is released south from (0,6) at fixture 60, sees
            // gryphon 1550 on (0,8), and stores SE,SW: SE lands now and SW
            // carries it back to the original south line on the next stride.
            if (!canTakeStep && unit.battleNetDoubleStep()
                    && !Direction.isDiagonal(heading)
                    && unit.type().moveType() == UnitType.Movement.FLY
                    && unit.type().canAttack()
                    && unit.battleNetBorrowedMoveForStep()
                    && unit.patrolX() >= 0
                    && unit.stepDrained() && !unit.isMoving()
                    && unit.pathLength() > 0) {
                Unit airBlocker = world.blockerOnLayer(unit, nextX, nextY);
                boolean alliedFlyer = airBlocker != null
                        && airBlocker != unit && airBlocker.type() != null
                        && !airBlocker.type().building()
                        && world.isAllied(
                                unit.player(), airBlocker.player())
                        && airBlocker.type().moveType()
                                == UnitType.Movement.FLY;
                if (alliedFlyer) {
                    int left = Math.floorMod(
                            heading - 1, Direction.COUNT);
                    int right = Math.floorMod(
                            heading + 1, Direction.COUNT);
                    int[] detours = {left, right};
                    for (int detour : detours) {
                        int returnHeading = detour == left ? right : left;
                        int detourX = unit.tileX()
                                + Direction.deltaX(detour) * stride;
                        int detourY = unit.tileY()
                                + Direction.deltaY(detour) * stride;
                        int returnX = detourX
                                + Direction.deltaX(returnHeading) * stride;
                        int returnY = detourY
                                + Direction.deltaY(returnHeading) * stride;
                        if (!world.canEnterBattleNetTransportAnchor(
                                unit, detourX, detourY)
                                || !world.canEnterBattleNetTransportAnchor(
                                        unit, returnX, returnY)) {
                            continue;
                        }
                        // A recurring behaviour-four scout leg is finished as
                        // soon as the detour itself lands within one doubled
                        // stride of its point. Native XOrc 8 slot 1550 stores
                        // only SE at fixture 108 for (0,12): (2,10) is already
                        // the flyer's reachable point skirt. The same rewrite
                        // keeps SE,SW for slot 1560's much farther (0,17) leg.
                        // Retaining the return heading in the near case made
                        // the rider glide on to (0,12) after native stood down.
                        boolean detourFinishesScout =
                                unit.battleNetScoutPatrol()
                                && unit.battleNetFlyerScoutExhausted()
                                && Math.max(Math.abs(
                                                unit.orderTargetX() - detourX),
                                        Math.abs(unit.orderTargetY() - detourY))
                                        <= stride;
                        int[] rewritten = detourFinishesScout
                                ? new int[] {detour}
                                : new int[] {returnHeading, detour};
                        unit.clearPath();
                        unit.setPath(new PathFinder.Path(
                                PathFinder.Result.FOUND, rewritten));
                        heading = detour;
                        nextX = detourX;
                        nextY = detourY;
                        canTakeStep = true;
                        break;
                    }
                }
            }
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
            if (!canTakeStep
                    && unit.battleNetAttackRefusalRecoveryStage() == 6
                    && unit.battleNetChaseEmptyRouteReplan()
                    && unit.pathLength() == 2
                    && unit.battleNetPathStepsTaken() == 0
                    && world.battleNetPaidEmptySharedWallBlocker(
                            unit, unit.target(), world.unitAt(nextX, nextY))) {
                // The two failed native wall faces share their route buffer.
                // Its retained counterclockwise byte may enter the silhouette
                // of a saturated formation mate already inside the same paid
                // empty-probe loop. This is a narrow single-probe overlap;
                // the remaining direct byte stays cached and ordinary
                // occupancy is rebuilt as soon as either fighter leaves.
                canTakeStep = true;
            }
            if (!canTakeStep) {
                Unit stageSixBlocker = world.unitAt(nextX, nextY);
                Unit saturatedWoodBlocker = world.blockerOnLayer(
                        unit, nextX, nextY);
                boolean saturatedWoodConstructionRefusal =
                        unit.battleNetSaturatedWoodConstructionRoute()
                        && (unit.order() == Unit.Order.HARVEST
                                || unit.battleNetBorrowedMoveForStep())
                        && !unit.returningToDepot()
                        && unit.resourceUnit() == null
                        && unit.stepDrained() && !unit.isMoving()
                        && unit.battleNetPathStepsTaken() == 0
                        && unit.pathLength() > 0
                        && unit.battleNetCollisionCounter() < 8
                        && saturatedWoodBlocker != null
                        && saturatedWoodBlocker != unit
                        && saturatedWoodBlocker.order()
                                == Unit.Order.HARVEST
                        && !saturatedWoodBlocker.returningToDepot()
                        && saturatedWoodBlocker.resourceUnit() == null
                        && saturatedWoodBlocker.isMoving()
                        && saturatedWoodBlocker.routeSpent()
                        && saturatedWoodBlocker.battleNetGoldFreePrefix()
                        && saturatedWoodBlocker
                                .battleNetGoldFreePrefixLength() > 0
                        && saturatedWoodBlocker
                                .battleNetGoldFreePrefixLength() < 3
                        && world.battleNetMoveAnimation(
                                saturatedWoodBlocker)
                        && world.isAllied(unit.player(),
                                saturatedWoodBlocker.player());
                if (saturatedWoodConstructionRefusal) {
                    // The action-23 constructor completes before the packed
                    // collision lifetime begins. Count every naked refusal:
                    // XHuman 12 peon 1385 is generation one on fixture 285,
                    // generation three on 287, and generation eight/Move 15
                    // on 292 before its fresh NE step on fixture 307. Folding
                    // the first three raw generations into Java's first
                    // visible callback armed the band two visits early.
                    int collision = unit.battleNetCollisionCounter() == 0
                            ? 1 : unit.battleNetCollisionCounter() + 1;
                    unit.setBattleNetCollisionCounter(collision);
                    unit.setBattleNetRefusals(0);
                    unit.clearPath();
                    unit.setRouteSpent(false);
                    unit.setWaitCycles(0);
                    unit.setBattleNetOrderDelay(collision >= 8 ? 14 : 0);
                    int moveStart = world.idle.battleNetSequenceStart(unit,
                            BattleNetSequence.MOVE_ANIMATION);
                    if (moveStart >= 0) {
                        unit.setBattleNetSequenceOffset(moveStart);
                        unit.setBattleNetAnimationTimer(
                                collision >= 8 ? 15 : 1);
                        unit.setBattleNetChaseStepReady(false);
                    }
                    return;
                }
                int capitalPatrolCollision =
                        unit.battleNetCollisionCounter();
                boolean behaviorSixCapitalPatrolAlliedHullRefusal =
                        unit.battleNetBorrowedMoveForStep()
                        && unit.battleNetAiBehavior() == 6
                        && unit.patrolX() >= 0
                        && unit.type() != null
                        && World.isBattleNetCapitalShip(unit.type().ident())
                        && unit.battleNetPathStepsTaken() == 0
                        && capitalPatrolCollision < 14
                        && unit.battleNetRefusals() == 0
                        && stageSixBlocker != null
                        && stageSixBlocker != unit
                        && stageSixBlocker.type() != null
                        && stageSixBlocker.type().seaUnit()
                        && world.isAllied(unit.player(),
                                stageSixBlocker.player())
                        && (capitalPatrolCollision > 0
                                || stageSixBlocker.isMoving());
                if (behaviorSixCapitalPatrolAlliedHullRefusal) {
                    // The point writer has already drawn through the moving
                    // hull, but Move consumes that route under restored live
                    // occupancy. Retail retains the complete buffer and pays
                    // FUN_004379e0's first collision generation. XHuman 7
                    // slot 1573 therefore keeps seven headings toward the
                    // completed platform at fixture 258 instead of clearing
                    // them and drawing a northeast bypass on the next visit.
                    // Each later timer-one wake advances the same packed
                    // generation while that retained head remains occupied;
                    // the blocker need no longer be mid-stride once the live
                    // route owns that provenance. Leave generation fifteen
                    // to the ordinary saturation path below.
                    unit.setBattleNetCollisionCounter(
                            capitalPatrolCollision + 1);
                    unit.setRouteSpent(false);
                    unit.setWaitCycles(0);
                    unit.setBattleNetOrderDelay(14);
                    unit.setBattleNetRefusalHold(true);
                    int moveStart = world.idle.battleNetSequenceStart(unit,
                            BattleNetSequence.MOVE_ANIMATION);
                    if (moveStart >= 0) {
                        unit.setBattleNetSequenceOffset(moveStart);
                        unit.setBattleNetAnimationTimer(15);
                        unit.setBattleNetChaseStepReady(false);
                    }
                    return;
                }
                boolean assaultPatrolWorkerRefusal =
                        unit.battleNetBorrowedMoveForStep()
                        && unit.battleNetAiBehavior() == 2
                        && unit.patrolX() >= 0
                        && unit.type() != null
                        && unit.type().moveType() == UnitType.Movement.LAND
                        && unit.battleNetPathStepsTaken() == 0
                        && unit.battleNetCollisionCounter() == 0
                        && unit.battleNetRefusals() == 0
                        && stageSixBlocker != null
                        && stageSixBlocker != unit
                        && stageSixBlocker.isMoving()
                        && stageSixBlocker.order() == Unit.Order.HARVEST
                        && stageSixBlocker.type() != null
                        && stageSixBlocker.type().moveType()
                                == UnitType.Movement.LAND
                        && Math.max(Math.abs(stageSixBlocker.offsetX()),
                                Math.abs(stageSixBlocker.offsetY())) < 32
                        && world.isAllied(unit.player(),
                                stageSixBlocker.player());
                if (assaultPatrolWorkerRefusal) {
                    // Behaviour-two land Patrol uses the same cooperative
                    // occupancy handoff as its point writer: a mid-stride
                    // allied harvester is transparent while the route is
                    // drawn, then live occupancy owns the first Move visit.
                    // Retail keeps the complete route and pays one collision
                    // generation plus a fifteen-count Move band. XHuman 12
                    // ogre 1356 therefore retains NW,NE at fixture 255 and
                    // consumes NW when peon 1386 drains at fixture 270.
                    unit.setBattleNetCollisionCounter(1);
                    unit.setBattleNetRefusals(0);
                    unit.setRouteSpent(false);
                    unit.setWaitCycles(0);
                    unit.setBattleNetOrderDelay(14);
                    int moveStart = world.idle.battleNetSequenceStart(unit,
                            BattleNetSequence.MOVE_ANIMATION);
                    if (moveStart >= 0) {
                        unit.setBattleNetSequenceOffset(moveStart);
                        unit.setBattleNetAnimationTimer(15);
                        unit.setBattleNetChaseStepReady(false);
                    }
                    return;
                }
                boolean recurringRegroupWorkerRefusal =
                        unit.order() == Unit.Order.MOVE
                        && !unit.battleNetPlayerCommandMove()
                        && unit.battleNetAiBehavior() == 1
                        && unit.hasBattleNetAiHome()
                        && unit.orderTargetX() == unit.battleNetAiHomeX()
                        && unit.orderTargetY() == unit.battleNetAiHomeY()
                        && unit.stepDrained() && !unit.isMoving()
                        && unit.battleNetPathStepsTaken() == 0
                        && unit.battleNetCollisionCounter() == 0
                        && stageSixBlocker != null
                        && stageSixBlocker != unit
                        && stageSixBlocker.isMoving()
                        && stageSixBlocker.order() == Unit.Order.HARVEST
                        && stageSixBlocker.type() != null
                        && stageSixBlocker.type().moveType()
                                == UnitType.Movement.LAND
                        && world.isAllied(unit.player(),
                                stageSixBlocker.player());
                if (recurringRegroupWorkerRefusal) {
                    // The recurring behaviour-one regroup writer plans through
                    // a worker whose Move body is in flight, but the following
                    // NextPathElement still tests the live occupied square.
                    // That is a cooperative refusal, not an empty point route:
                    // XHuman 12 axethrower 1359 writes N,NW,SE,E,E on fixture
                    // 252, retains route index zero, raises unit+0x1d to 0x10,
                    // and owns Move 830/15 while the north peon drains.  Java
                    // used to harden the worker during planning, obtain an
                    // empty route, and promote the regroup order to Still.
                    unit.setBattleNetCollisionCounter(1);
                    unit.setBattleNetRefusals(0);
                    unit.setRouteSpent(false);
                    unit.setWaitCycles(0);
                    unit.setBattleNetOrderDelay(14);
                    int moveStart = world.idle.battleNetSequenceStart(unit,
                            BattleNetSequence.MOVE_ANIMATION);
                    if (moveStart >= 0) {
                        unit.setBattleNetSequenceOffset(moveStart);
                        unit.setBattleNetAnimationTimer(15);
                        unit.setBattleNetChaseStepReady(false);
                    }
                    return;
                }
                boolean collidedRegroupWorkerRefusal =
                        unit.order() == Unit.Order.MOVE
                        && !unit.battleNetPlayerCommandMove()
                        && unit.battleNetAiBehavior() == 1
                        && unit.hasBattleNetAiHome()
                        && unit.orderTargetX() == unit.battleNetAiHomeX()
                        && unit.orderTargetY() == unit.battleNetAiHomeY()
                        && unit.stepDrained() && !unit.isMoving()
                        && unit.battleNetPathStepsTaken() == 0
                        && unit.battleNetCollisionCounter() > 0
                        && stageSixBlocker != null
                        && stageSixBlocker != unit
                        && stageSixBlocker.isMoving()
                        && stageSixBlocker.order() == Unit.Order.HARVEST
                        && stageSixBlocker.type() != null
                        && stageSixBlocker.type().moveType()
                                == UnitType.Movement.LAND
                        && stageSixBlocker.battleNetCollisionCounter() > 0
                        && world.isAllied(unit.player(),
                                stageSixBlocker.player());
                if (collidedRegroupWorkerRefusal) {
                    // The cached cooperative route can wake behind a
                    // different worker. If that worker already owns a native
                    // collision nibble, FUN_004379e0 parks the route at index
                    // twenty and advances the mover's packed generation
                    // without entering the fifteen-refusal band. XHuman 12
                    // axethrower 1359 is 0x10 -> 0x20 at fixture 267 while
                    // collision-four peasant 1385 occupies its north byte.
                    // On the next visit NewPath sees that worker as solid,
                    // returns an empty route, and the ordinary Move handler
                    // promotes Still at fixture 268.
                    int collision = unit.battleNetCollisionCounter() + 1;
                    unit.setBattleNetCollisionCounter(
                            collision > 14 ? 0 : collision);
                    unit.setBattleNetRefusals(0);
                    unit.clearPath();
                    unit.setRouteSpent(false);
                    unit.setWaitCycles(0);
                    unit.setBattleNetOrderDelay(0);
                    int moveStart = world.idle.battleNetSequenceStart(unit,
                            BattleNetSequence.MOVE_ANIMATION);
                    if (moveStart >= 0) {
                        unit.setBattleNetSequenceOffset(moveStart);
                        unit.setBattleNetAnimationTimer(1);
                        unit.setBattleNetChaseStepReady(false);
                    }
                    return;
                }
                boolean paidRecoveryResidualTailPark =
                        unit.battleNetPaidRefusalRecoveryApproach()
                        && world.actionMoveWalked
                        && unit.stepDrained() && !unit.isMoving()
                        // A direct three-byte recovery ray has exactly two
                        // cached bytes behind its granted first probe. Long
                        // formation routes retain their own paid-pressure
                        // lifecycle and must not be collapsed here.
                        && unit.pathLength() == 2
                        && unit.battleNetPathStepsTaken() == 1
                        && unit.target() != null
                        && unit.target().isAlive()
                        && !world.targets.inAttackRange(
                                unit, unit.target())
                        && !World.battleNetRangedChaseUnit(unit);
                if (paidRecoveryResidualTailPark) {
                    // Attack construction granted the route's first heading
                    // as its single Move probe. If the residual settles onto
                    // a blocked cached tail, native parks the remaining bytes
                    // at route index twenty on this visit and enters the
                    // active-order callback on the next one. Trying the tail
                    // immediately converts that callback into ordinary path
                    // refusals and drops its asynchronous idle draw.
                    world.causalTrace.event(world.cycle,
                            "path.paid-recovery-residual-tail-park", unit.id(),
                            "path_length", unit.pathLength(),
                            "heading", heading,
                            "blocker", stageSixBlocker == null
                                    ? -1 : stageSixBlocker.id(),
                            "target", unit.target().id());
                    unit.clearPath();
                    unit.setRouteSpent(false);
                    unit.setWaitCycles(0);
                    unit.setBattleNetOrderDelay(0);
                    unit.setBattleNetChaseEmptyRouteReplan(true);
                    unit.setBattleNetPaidRefusalRecoveryApproach(false);
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
                Unit cleanDestinationArmBlocker =
                        world.blockerOnLayer(unit, nextX, nextY);
                Unit cleanDestinationArmTarget = unit.target();
                boolean cleanNearMeleeDestinationArmRefusal =
                        unit.battleNetAttackWrapDestArmPending()
                        && !unit.battleNetChaseEmptyRouteReplan()
                        && unit.battleNetCollisionCounter() == 0
                        && unit.battleNetRefusals() == 0
                        && unit.battleNetPathStepsTaken() == 0
                        && unit.pathLength() == 2
                        && unit.stepDrained() && !unit.isMoving()
                        && unit.chasing()
                        && (unit.order() == Unit.Order.ATTACK
                                || unit.order() == Unit.Order.ATTACK_MOVE
                                || unit.battleNetBorrowedMoveForStep())
                        && unit.type() != null
                        && unit.type().maxAttackRange() <= 1
                        && world.battleNetMovementStride(unit) == 1
                        && cleanDestinationArmTarget != null
                        && cleanDestinationArmTarget.isAlive()
                        && cleanDestinationArmTarget.type() != null
                        && !cleanDestinationArmTarget.type().building()
                        && unit.battleNetRouteOffer()
                                == cleanDestinationArmTarget
                        && unit.tileX()
                                != cleanDestinationArmTarget.tileX()
                        && unit.tileY()
                                != cleanDestinationArmTarget.tileY()
                        && Math.max(Math.abs(unit.tileX()
                                                - cleanDestinationArmTarget
                                                        .tileX()),
                                        Math.abs(unit.tileY()
                                                - cleanDestinationArmTarget
                                                        .tileY())) == 2
                        && heading == World.battleNetFirstBresenhamHeading(
                                unit.tileX(), unit.tileY(),
                                cleanDestinationArmTarget.tileX(),
                                cleanDestinationArmTarget.tileY())
                        && cleanDestinationArmBlocker != null
                        && cleanDestinationArmBlocker != unit
                        && cleanDestinationArmBlocker.isOnMap()
                        && !cleanDestinationArmBlocker.isDying()
                        && cleanDestinationArmBlocker.type() != null
                        && !cleanDestinationArmBlocker.type().building()
                        && world.isAllied(unit.player(),
                                cleanDestinationArmBlocker.player());
                if (cleanNearMeleeDestinationArmRefusal) {
                    // A clean Attack-tail destination arm writes its direct
                    // two-byte target-skirt ray with mobile bodies absent.
                    // Move then restores real occupancy before consuming the
                    // head. If an allied fighter owns that square, retail
                    // retains both bytes and enters FUN_004379e0's complete
                    // refusal band immediately: collision one and Move 15.
                    // Parking the ray after a one-count probe redraws around
                    // the formation on the next visit and loses the native
                    // target-skirt contract.
                    world.causalTrace.event(world.cycle,
                            "path.clean-destination-arm-refusal", unit.id(),
                            "path_length", unit.pathLength(),
                            "heading", heading,
                            "blocker", cleanDestinationArmBlocker.id(),
                            "target", cleanDestinationArmTarget.id());
                    unit.setBattleNetCollisionCounter(1);
                    unit.setRouteSpent(false);
                    unit.setWaitCycles(0);
                    unit.setBattleNetOrderDelay(14);
                    unit.setBattleNetRefusalHold(true);
                    int moveStart = world.idle.battleNetSequenceStart(unit,
                            BattleNetSequence.MOVE_ANIMATION);
                    if (moveStart >= 0) {
                        unit.setBattleNetSequenceOffset(moveStart);
                        unit.setBattleNetAnimationTimer(15);
                        unit.setBattleNetChaseStepReady(false);
                    }
                    return;
                }
                boolean retainedPaidRefusalFace =
                        unit.battleNetAttackRefusalRecoveryStage() == 6
                        && unit.battleNetChaseEmptyRouteReplan()
                        && unit.pathLength() == 1
                        && unit.battleNetPathStepsTaken() == 0
                        && unit.battleNetParkedRefusalHeading() == heading
                        && unit.target() != null
                        && unit.target().isAlive()
                        && unit.chasing();
                if (retainedPaidRefusalFace) {
                    // Attack 3,2,1 exposes exactly one probe of the route byte
                    // parked by refusal eight. It is not a cooperative-body
                    // wait and it must not rotate around the blocker: another
                    // refusal returns directly to Attack construction while
                    // retaining the same native byte for the next probe.
                    unit.clearPath();
                    unit.setRouteSpent(false);
                    unit.setWaitCycles(0);
                    unit.setBattleNetOrderDelay(0);
                    if (!world.combat
                            .rearmBattleNetHardRefusalAttack(unit)) {
                        unit.setBattleNetAttackRefusalRecoveryStage(0);
                    }
                    return;
                }
                boolean tailReachabilityProbe =
                        unit.battleNetAttackWrapDestArmPending()
                        && unit.battleNetChaseEmptyRouteReplan()
                        && unit.pathLength() == 1
                        && unit.target() != null
                        && unit.chasing()
                        && !World.battleNetRangedChaseUnit(unit)
                        && (unit.order() == Unit.Order.ATTACK
                                || unit.order() == Unit.Order.ATTACK_MOVE
                                || unit.battleNetBorrowedMoveForStep());
                if (tailReachabilityProbe) {
                    // A failed one-byte route inherited from an Attack-tail
                    // retarget is a reachability probe, not a cooperative
                    // fifteen-count movement refusal. Retail retains the byte
                    // and revisits Move OP0 on every scheduler call, advancing
                    // the collision nibble while AutoSelectTarget's six-beat
                    // clock decides whether any target remains reachable.
                    // Turning this into PF_WAIT or Attack reconstruction is a
                    // direct frozen-combat loop: the scan clock never expires.
                    int collision = unit.battleNetCollisionCounter() + 1;
                    unit.setBattleNetCollisionCounter(
                            collision > 14 ? 0 : collision);
                    unit.setRouteSpent(false);
                    unit.setWaitCycles(0);
                    unit.setBattleNetOrderDelay(0);
                    int moveStart = world.idle.battleNetSequenceStart(unit,
                            BattleNetSequence.MOVE_ANIMATION);
                    if (moveStart >= 0) {
                        unit.setBattleNetSequenceOffset(moveStart);
                        unit.setBattleNetAnimationTimer(1);
                        unit.setBattleNetChaseStepReady(false);
                    }
                    if (World.TRACE_MOVING != null
                            && unit.id() == World.TRACE_MOVING_ID) {
                        System.err.printf("JMOVEREACHPROBE cycle=%d unit=%d "
                                        + "collision=%d seq=%d/%d%n",
                                world.cycle, unit.id(),
                                unit.battleNetCollisionCounter(),
                                unit.battleNetSequenceOffset(),
                                unit.battleNetAnimationTimer());
                    }
                    return;
                }
                // Attack construction grants one Move probe. A real route
                // whose first square contains an allied body already walking
                // away belongs to FUN_004379e0's cooperative wait; it is not
                // an empty construction failure that should re-arm Attack.
                // Re-arming here inserts a phantom idle draw and leaves the
                // engaged follower visibly frozen for three more callbacks.
                boolean paidStageSixCooperativeRoute =
                        unit.battleNetAttackRefusalRecoveryStage() == 6
                        && world.battleNetCooperativeBlocker(
                                unit, stageSixBlocker);
                boolean duplicateCardinalStageSixProbe =
                        paidStageSixCooperativeRoute
                        && unit.battleNetChaseEmptyRouteReplan()
                        && unit.pathLength() == 2
                        && unit.battleNetPathStepsTaken() == 0
                        && unit.battleNetCollisionCounter() <= 1
                        && unit.battleNetRefusals() == 0
                        && !Direction.isDiagonal(heading)
                        && unit.peekHeadingAtDepth(1) == heading
                        && unit.target() != null
                        && unit.target().isAlive()
                        && unit.type() != null
                        && unit.type().maxAttackRange() <= 1
                        && world.battleNetMovementStride(unit) == 1
                        && unit.target().tileX() == unit.tileX()
                                + Direction.deltaX(heading) * 2
                        && unit.target().tileY() == unit.tileY()
                                + Direction.deltaY(heading) * 2;
                if (duplicateCardinalStageSixProbe) {
                    // Attack construction's final Move probe receives E,E
                    // from Java's ordinary path buffer here. Retail writes
                    // only the direct E byte and parks its cursor at twenty:
                    // XHuman 12 slot 1434 holds byte 02 at (24,60) from
                    // fixture 189 onward while slot 1447 vacates (25,60).
                    // Keeping the second E lets the generic collision writer
                    // rotate E->S->W and walk away at fixture 191.
                    unit.clearPath();
                    unit.setPath(new PathFinder.Path(
                            PathFinder.Result.FOUND,
                            new int[] {heading}));
                    int collision = unit.battleNetCollisionCounter() + 1;
                    unit.setBattleNetCollisionCounter(
                            collision > 14 ? 0 : collision);
                    unit.setBattleNetStageSixCardinalProbePark(true);
                    unit.setRouteSpent(false);
                    unit.setWaitCycles(0);
                    unit.setBattleNetOrderDelay(0);
                    int moveStart = world.idle.battleNetSequenceStart(unit,
                            BattleNetSequence.MOVE_ANIMATION);
                    if (moveStart >= 0) {
                        unit.setBattleNetSequenceOffset(moveStart);
                        unit.setBattleNetAnimationTimer(1);
                        unit.setBattleNetChaseStepReady(false);
                    }
                    return;
                }
                boolean retainedCardinalStageSixProbe =
                        unit.battleNetStageSixCardinalProbePark()
                        && unit.battleNetAttackRefusalRecoveryStage() == 6
                        && unit.battleNetChaseEmptyRouteReplan()
                        && unit.pathLength() == 1
                        && unit.battleNetPathStepsTaken() == 0
                        && !Direction.isDiagonal(heading)
                        && unit.target() != null
                        && unit.target().isAlive();
                if (retainedCardinalStageSixProbe) {
                    // Route index twenty makes this a naked collision visit,
                    // not another cooperative wait or a wall-follow search.
                    // The eighth visit still owns the common native refusal
                    // transition: generation eight writes a complete Move
                    // 15..1 band, whose wake clears the generation and enters
                    // Attack construction 3..1. This is visible across the
                    // sealed corpus, including independent cardinal probes in
                    // XHuman 4, XHuman 10, and XHuman 12. Letting this retained
                    // cursor wrap through generations eight to fifteen skips
                    // the active-order idle callback and shifts every later
                    // asynchronous consumer.
                    int collision = unit.battleNetCollisionCounter() + 1;
                    unit.setBattleNetCollisionCounter(collision);
                    unit.setRouteSpent(false);
                    unit.setWaitCycles(0);
                    if (collision >= 8) {
                        unit.setBattleNetAttackRefusalRecoveryStage(4);
                        unit.setBattleNetOrderDelay(14);
                        // A paid generation-eight band is indivisible even
                        // when the cooperative blocker vacates meanwhile.
                        // Without the sticky refusal owner, the generic chase
                        // free-wake spends this retained byte on the very next
                        // visit and skips the native timer band.
                        unit.setBattleNetRefusalHold(true);
                    } else {
                        unit.setBattleNetOrderDelay(0);
                    }
                    int moveStart = world.idle.battleNetSequenceStart(unit,
                            BattleNetSequence.MOVE_ANIMATION);
                    if (moveStart >= 0) {
                        unit.setBattleNetSequenceOffset(moveStart);
                        unit.setBattleNetAnimationTimer(
                                collision >= 8 ? 15 : 1);
                        unit.setBattleNetChaseStepReady(false);
                    }
                    return;
                }
                unit.setBattleNetStageSixCardinalProbePark(false);
                boolean parkedStageSixSingleHeading =
                        unit.battleNetAttackRefusalRecoveryStage() == 6
                        && unit.battleNetChaseEmptyRouteReplan()
                        && unit.pathLength() == 1
                        && unit.battleNetPathStepsTaken() == 0
                        && unit.battleNetCollisionCounter() == 0
                        && unit.battleNetRefusals() == 0
                        && unit.offeredTarget() == null
                        && stageSixBlocker != null
                        && stageSixBlocker.isOnMap()
                        && !stageSixBlocker.isDying()
                        && world.isAllied(unit.player(),
                                stageSixBlocker.player());
                if (parkedStageSixSingleHeading) {
                    // This stage-six route byte is physically written behind
                    // native's parked cursor. It starts a collision generation
                    // instead of returning through active-order Still. XHuman
                    // 12 slot 1457 has no banked hit offer, writes
                    // E/collision one at fixture 166, and
                    // reaches the complete Move band at fixture 173. Re-arming
                    // Attack here burned two extra asynchronous draws before
                    // fixture 171's melee damage. A banked aggressor retains
                    // the active-order Still callback instead; it is not this
                    // Patrol-owned probe.
                    unit.clearPath();
                    unit.setRouteSpent(false);
                    unit.setWaitCycles(0);
                    unit.setBattleNetOrderDelay(0);
                    unit.setBattleNetCollisionCounter(1);
                    unit.setBattleNetRefusalHold(true);
                    unit.setBattleNetLandPatrolAttackRoutePending(true);
                    int moveStart = world.idle.battleNetSequenceStart(unit,
                            BattleNetSequence.MOVE_ANIMATION);
                    if (moveStart >= 0) {
                        unit.setBattleNetSequenceOffset(moveStart);
                        unit.setBattleNetAnimationTimer(1);
                        unit.setBattleNetChaseStepReady(false);
                    }
                    return;
                }
                // Attack construction 3,2,1 grants Move exactly one probe.
                // A refusal on that probe returns directly through the
                // active-order idle dispatcher and re-arms Attack; it must
                // dominate every classification of the blocker below. In
                // particular, an allied moving body is ordinarily eligible
                // for the cooperative fifteen-count band. Letting that band
                // intercept stage six left crowded melee units parked on a
                // fresh Java route instead of retrying every three visits,
                // and stole the native idle draw which orders later damage
                // and projectile randomness (XHuman 12 grunt 1504 @ c90).
                if (unit.battleNetAttackRefusalRecoveryStage() == 6
                        && unit.target() != null
                        && unit.chasing()
                        && !paidStageSixCooperativeRoute) {
                    unit.clearPath();
                    unit.setRouteSpent(false);
                    unit.setWaitCycles(0);
                    unit.setBattleNetOrderDelay(0);
                    if (!world.combat
                            .rearmBattleNetHardRefusalAttack(unit)) {
                        unit.setBattleNetAttackRefusalRecoveryStage(0);
                    }
                    return;
                }
                Unit settledMeleeBlocker = world.unitAt(nextX, nextY);
                boolean settledMeleeAlly = settledMeleeBlocker != null
                        && settledMeleeBlocker != unit
                        && settledMeleeBlocker.isOnMap()
                        && !settledMeleeBlocker.isDying()
                        && world.isAllied(unit.player(),
                                settledMeleeBlocker.player());
                boolean settledMeleeChase = world.actionMoveWalked
                        && unit.stepDrained() && !unit.isMoving()
                        && unit.target() != null
                        && !World.battleNetRangedChaseUnit(unit)
                        && (unit.order() == Unit.Order.ATTACK
                                || unit.order() == Unit.Order.ATTACK_MOVE
                                || unit.chasing());
                if (settledMeleeChase && settledMeleeAlly
                        && unit.battleNetPathStepsTaken() == 1
                        && unit.battleNetRefusals() == 0
                        && unit.pathLength() > 5
                        && Direction.isDiagonal(heading)) {
                    int previousCollision =
                            unit.battleNetCollisionCounter();
                    if (previousCollision >= 4) {
                        Unit formationTarget = unit.target();
                        int currentDistance = formationTarget == null
                                ? Integer.MAX_VALUE
                                : Math.max(Math.abs(unit.tileX()
                                                - formationTarget.tileX()),
                                        Math.abs(unit.tileY()
                                                - formationTarget.tileY()));
                        int nextDistance = formationTarget == null
                                ? Integer.MAX_VALUE
                                : Math.max(Math.abs(nextX
                                                - formationTarget.tileX()),
                                        Math.abs(nextY
                                                - formationTarget.tileY()));
                        if (nextDistance < currentDistance
                                || unit.battleNetAttackWrapDestArmPending()) {
                            // A saturated cached diagonal which still closes
                            // on the quarry terminates the old route instead
                            // of charging another cooperative wait. Native
                            // advances the collision generation, exposes an
                            // empty Move-start/1 buffer, then redraws on the
                            // following callback. Rear formation members can
                            // therefore keep advancing rather than freezing
                            // behind a route which has already done its job.
                            // A completed Attack-tail wrap owns the same park
                            // even when its stale diagonal points away from the
                            // quarry: XHuman 12 slot 1504 parks NE/collision
                            // four on fixture 262, then redraws S,S on 263.
                            int collision = previousCollision + 1;
                            unit.setBattleNetCollisionCounter(
                                    collision > 14 ? 0 : collision);
                            unit.clearPath();
                            unit.setRouteSpent(false);
                            unit.setStepDrained(false);
                            unit.setWaitCycles(0);
                            unit.setBattleNetOrderDelay(0);
                            unit.setBattleNetChaseEmptyRouteReplan(true);
                            if (unit.battleNetAttackWrapDestArmPending()) {
                                // Preserve that this empty RI-20 buffer came
                                // from a completed Attack-tail wrap. Its next
                                // writer is the short direct blocked prefix,
                                // not the ordinary long wall optimizer.
                                unit.markBattleNetLongPaidWrapParkedRoute();
                            }
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
                        // A saturated first leg does not discard its cached
                        // ray when the next diagonal still meets the assault
                        // line. Native keeps route index one and starts the
                        // complete Move refusal band immediately (XHuman 12
                        // grunt 1492: collision four -> five and Move 15 on
                        // fixture 89). Clearing and rebuilding stepped north
                        // on fixture 90 instead of holding the formation.
                        int collision = previousCollision + 1;
                        unit.setBattleNetCollisionCounter(
                                collision > 14 ? 0 : collision);
                        unit.setWaitCycles(0);
                        unit.setBattleNetOrderDelay(14);
                        int moveStart = world.idle.battleNetSequenceStart(unit,
                                BattleNetSequence.MOVE_ANIMATION);
                        if (moveStart >= 0) {
                            unit.setBattleNetSequenceOffset(moveStart);
                            unit.setBattleNetAnimationTimer(15);
                            unit.setBattleNetChaseStepReady(false);
                        }
                        return;
                    }
                    if (previousCollision == 0
                            && unit.pathLength() == 6) {
                        // A fresh six-byte ray parks on the settle visit and
                        // is replaced on the next one. Native grunt 1513
                        // therefore writes route index 20 at fixture 89 and
                        // consumes the new route's north head at 90.
                        unit.setBattleNetCollisionCounter(1);
                        unit.clearPath();
                        unit.setRouteSpent(false);
                        unit.setWaitCycles(0);
                        unit.setBattleNetOrderDelay(0);
                        return;
                    }
                }
                if (settledMeleeChase && settledMeleeAlly
                        && unit.pathLength() == 1
                        && unit.type() != null
                        && "unit-ogre".equals(unit.type().ident())
                        && unit.battleNetCollisionCounter() == 0
                        && !battleNetHasStrictlyCloserFreeNeighbour(
                                unit, unit.target())) {
                    // The final cached heading has met a standing formation
                    // and no free neighbour improves weapon distance. Native
                    // parks the route, then re-enters active-order Still on
                    // the following callback instead of sliding sideways.
                    // XHuman 12 ogre 1394 holds (11,85), pays its idle draw,
                    // and repeats Attack construction every three visits.
                    int collision = unit.battleNetCollisionCounter() + 1;
                    unit.setBattleNetCollisionCounter(
                            collision > 14 ? 0 : collision);
                    unit.clearPath();
                    unit.setRouteSpent(false);
                    unit.setBattleNetChaseEmptyRouteReplan(true);
                    unit.setBattleNetResidualEmptyApproachIdlePending(true);
                    unit.setWaitCycles(0);
                    unit.setBattleNetOrderDelay(0);
                    return;
                }
                // A settled attack route which has already paid its complete
                // cooperative refusal band gets one retry of the retained
                // head. If that square is still occupied, FUN_004379e0 bumps
                // the collision nibble, parks route_index at 20, and leaves
                // the following action visit to draw a fresh route. Re-arming
                // another fifteen-count here was an unbounded engagement
                // freeze: XHuman 12 grunt 1510 stayed at (34,40) while retail
                // parks E at fixture 72 and replans N at 73. A head which has
                // become free bypasses this arm and clears RefusalHold in the
                // successful-step path below (XHuman 10 grunt 1486). A route
                // carrying hard-refusal provenance uses RefusalHold only to
                // suppress an early free detour; if it is still blocked it
                // keeps the established refusal lifecycle (XHuman 12 grunt
                // 1506), hence the zero-hard-refusals guard.
                if (unit.battleNetRefusalHold()
                        && unit.battleNetRefusals() == 0
                        && unit.battleNetCollisionCounter() >= 3
                        && unit.stepDrained() && !unit.isMoving()
                        && unit.pathLength() > 0
                        && unit.target() != null
                        && !World.battleNetRangedChaseUnit(unit)
                        && (unit.order() == Unit.Order.ATTACK
                                || unit.order() == Unit.Order.ATTACK_MOVE
                                || unit.chasing())) {
                    boolean saturatedColdRouteWake =
                            unit.battleNetCollisionCounter() >= 10
                            && unit.battleNetPathStepsTaken() == 0
                            && unit.pathLength() < 20;
                    if (saturatedColdRouteWake) {
                        // The packed collision nibble saturates at 0xa for a
                        // cold retained chase route. Its next paid-band wake
                        // clears the buffer and returns through active-order
                        // Still instead of manufacturing collision 0xb and a
                        // fourth Move band. That Still callback owns one idle
                        // draw and opens Attack 3,2,1; subsequent refused
                        // probes repeat the same three-visit construction.
                        // XHuman 12 slot 1457 is the sealed witness: Move
                        // bands begin at fixtures 173, 188 and 203, then the
                        // 0xa wake becomes Attack at fixture 218.
                        unit.clearPath();
                        unit.setRouteSpent(false);
                        unit.setWaitCycles(0);
                        unit.setBattleNetOrderDelay(0);
                        unit.setBattleNetRefusalHold(false);
                        unit.setBattleNetCollisionCounter(0);
                        unit.setBattleNetChaseEmptyRouteReplan(true);
                        world.causalTrace.event(world.cycle,
                                "path.saturated-cold-route-still-wake",
                                unit.id());
                        if (!world.combat
                                .rearmBattleNetHardRefusalAttack(unit)) {
                            unit.setBattleNetAttackRefusalRecoveryStage(0);
                        }
                        return;
                    }
                    int counter = unit.battleNetCollisionCounter() + 1;
                    unit.setBattleNetCollisionCounter(
                            counter > 14 ? 0 : counter);
                    if (unit.battleNetPathStepsTaken() == 0
                            && unit.pathLength() < 20) {
                        // A cold route which has not spent a single compass
                        // byte remains the active refusal input. On the wake
                        // probe native raises the collision nibble and starts
                        // the next complete fifteen-count band immediately;
                        // it does not park the cursor and spend an additional
                        // route-plan visit. That extra Java visit made every
                        // repeated formation wait one cycle longer and is a
                        // direct source of visibly frozen engagements.
                        // This continuation is specifically an unsaturated
                        // route; a completely full twenty-byte buffer
                        // has no preserved terminator and must be parked
                        // (slot 1520/Java 108). XHuman 12 slot 1479 keeps its fourteen-byte route
                        // headed N at fixture 84 and takes N on 99. A route
                        // that already spent a byte still uses the park below
                        // (slot 1510 parks at 72 and replans at 73).
                        world.causalTrace.event(world.cycle,
                                "path.cold-route-reband", unit.id(),
                                "path_length", unit.pathLength(),
                                "heading", unit.peekHeading(),
                                "collision",
                                        unit.battleNetCollisionCounter(),
                                "refusals", unit.battleNetRefusals());
                        unit.setWaitCycles(0);
                        unit.setBattleNetOrderDelay(14);
                        int moveStart = world.idle
                                .battleNetSequenceStart(unit,
                                        BattleNetSequence.MOVE_ANIMATION);
                        if (moveStart >= 0) {
                            unit.setBattleNetSequenceOffset(moveStart);
                            unit.setBattleNetAnimationTimer(15);
                            unit.setBattleNetChaseStepReady(false);
                        }
                        unit.setBattleNetRefusalHold(true);
                        return;
                    }
                    unit.clearPath();
                    unit.setRouteSpent(false);
                    unit.setWaitCycles(0);
                    unit.setBattleNetRefusalHold(false);
                    return;
                }
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
                    boolean pressuredTemporaryBody = temporaryBody
                            && (blocker.battleNetCollisionCounter() > 0
                                    || blocker.battleNetRefusals() > 0);
                    if (pressuredTemporaryBody && unit.target() == null
                            && !unit.isMoving()
                            && unit.pathLength() > 0
                            && unit.battleNetPathStepsTaken() > 0) {
                        // A hull which has already entered its own collision
                        // ladder is no longer a cooperative mover expected to
                        // vacate this exact cached heading. Retail parks the
                        // stale route, advances the router's refusal nibble,
                        // and redraws. XOrc 8 supplies three route shapes:
                        // submarine 1432 parks a three-byte head behind
                        // destroyer 1430's first collision on fixture 267,
                        // while submarine 1434 parks a consumed two-byte tail
                        // behind destroyer 1435's ninth. Both redraw on 268.
                        // Destroyer 1431 parks its one-step consumed northwest
                        // tail behind the pressured submarine 1432 on fixture
                        // 264, but that shortest provenance still owns the
                        // complete Move 15..1 band before it redraws southwest
                        // on 279. The unpressured control is the same destroyer
                        // at fixture 38: submarine 1432 still has collision zero,
                        // so the cached northwest tail remains live through the
                        // band and commits when that blocker leaves on 53.
                        boolean paidConsumedTail =
                                unit.battleNetPathStepsTaken() == 1;
                        boolean terminalPaidConsumedTail =
                                paidConsumedTail && unit.pathLength() == 1;
                        int parkedHeading = terminalPaidConsumedTail
                                ? unit.peekHeading() : -1;
                        battleNetRefuse(unit);
                        unit.setRouteSpent(false);
                        unit.setWaitCycles(0);
                        unit.setBattleNetOrderDelay(
                                paidConsumedTail ? 14 : 0);
                        unit.setBattleNetNavalPaidParkedRoute(
                                terminalPaidConsumedTail);
                        if (paidConsumedTail) {
                            // A terminal cached byte continues the wall face
                            // which produced it: destroyer 1431's refused NW
                            // therefore redraws SW on fixture 279. A longer
                            // route exposes a newly blocked head after the
                            // prior stride settles; native parks that route
                            // but starts the paid redraw cold. Destroyer 1435
                            // rejects E with six bytes left on fixture 303 and
                            // the fixture-318 redraw begins SE, not the
                            // opposite face produced by continuing from E.
                            unit.setBattleNetParkedRefusalHeading(
                                    parkedHeading);
                        }
                        if (paidConsumedTail
                                && world.battleNetSequence != null
                                && world.battleNetMoveAnimation(unit)) {
                            int moveStart = world.idle
                                    .battleNetSequenceStart(unit,
                                            BattleNetSequence.MOVE_ANIMATION);
                            if (moveStart >= 0) {
                                unit.setBattleNetSequenceOffset(moveStart);
                            }
                            unit.setBattleNetAnimationTimer(15);
                        }
                        return;
                    }
                    if (temporaryBody
                            && refuseBattleNetNavalMapPatrol(unit)) {
                        return;
                    }
                    if (temporaryBody && unit.target() == null
                            && !unit.isMoving()
                            && unit.pathLength() > 0) {
                        boolean loadedTankerReturn =
                                unit.type().gathering().containsKey(
                                        UnitType.Resource.OIL)
                                && unit.returningToDepot()
                                && unit.carried() > 0
                                && unit.battleNetPathStepsTaken() == 0
                                && blocker.isMoving()
                                && blocker.returningToDepot()
                                && blocker.carried() > 0;
                        if (loadedTankerReturn) {
                            // Action 24 does not use the ordinary fresh naval
                            // refusal ladder. A laden tanker whose new doubled
                            // route opens on another moving laden return raises
                            // the packed collision generation and pays the
                            // complete Move band immediately, retaining byte
                            // zero until the convoy neighbour has had time to
                            // sail away. Two sealed
                            // witnesses carry the same construction: XOrc 7
                            // slot 1587 writes collision 1 / timer 15 at
                            // fixture 252, and Orc 10 slot 1533 does so at
                            // fixture 253. Orc 10 slot 1541 is the clear-route
                            // control: it commits N immediately at fixture
                            // 222 and never enters this branch. Orc 8 slot
                            // 1478 supplies the inert-body control: a freshly
                            // surfaced Still tanker blocks N, so native parks
                            // the cursor and climbs naked collision 1..7 before
                            // paying its first full band at collision 8.
                            int collision =
                                    unit.battleNetCollisionCounter() + 1;
                            unit.setBattleNetCollisionCounter(
                                    collision > 14 ? 0 : collision);
                            unit.setBattleNetRefusals(0);
                            unit.setWaitCycles(0);
                            unit.setBattleNetOrderDelay(14);
                            pickUpMoveAnimation(unit);
                            if (world.battleNetSequence != null) {
                                int moveStart = world.idle
                                        .battleNetSequenceStart(unit,
                                                BattleNetSequence.MOVE_ANIMATION);
                                if (moveStart >= 0) {
                                    unit.setBattleNetSequenceOffset(moveStart);
                                }
                                unit.setBattleNetAnimationTimer(15);
                            }
                            world.causalTrace.event(world.cycle,
                                    "path.loaded-tanker-return-band",
                                    unit.id(), "collision",
                                    unit.battleNetCollisionCounter(),
                                    "path_length", unit.pathLength(),
                                    "heading", unit.peekHeading(),
                                    "blocker", blocker.id(),
                                    "blocker_order", blocker.order(),
                                    "blocker_moving", blocker.isMoving(),
                                    "blocker_returning",
                                            blocker.returningToDepot(),
                                    "blocker_carried", blocker.carried());
                            return;
                        }
                        // Cooperative naval refusals retain both the route and
                        // FUN_004379e0's sticky high nibble. A cached leftover
                        // heading (this route already spent a step) enters the
                        // fifteen-count band immediately. A freshly planned
                        // multi-heading route instead retries once per visit
                        // while counts one through seven accumulate, then
                        // enters that same band at eight. The count survives a
                        // later successful stride. XOrc 8 destroyer 1431 is the
                        // authenticated combined witness: refusal one at c38,
                        // successful NW at c53, fresh-route refusals two through
                        // eight at c85..91, timer 15..1, then NW at c106.
                        int refusals = unit.battleNetRefusals() + 1;
                        if (refusals >= 15) {
                            unit.setBattleNetRefusals(0);
                            unit.clearPath();
                            unit.setBattleNetOrderDelay(14);
                            unit.setWaitCycles(0);
                            if (world.battleNetSequence != null
                                    && world.battleNetMoveAnimation(unit)) {
                                int moveStart = world.idle
                                        .battleNetSequenceStart(unit,
                                                BattleNetSequence.MOVE_ANIMATION);
                                if (moveStart >= 0) {
                                    unit.setBattleNetSequenceOffset(moveStart);
                                }
                                unit.setBattleNetAnimationTimer(15);
                            }
                            return;
                        }
                        unit.setBattleNetRefusals(refusals);
                        unit.setWaitCycles(0);
                        boolean cachedLeftover =
                                unit.battleNetPathStepsTaken() > 0;
                        if (!cachedLeftover && refusals < 8) {
                            unit.setBattleNetOrderDelay(0);
                            int moveStart = world.idle.battleNetSequenceStart(unit,
                                    BattleNetSequence.MOVE_ANIMATION);
                            if (moveStart >= 0) {
                                unit.setBattleNetSequenceOffset(moveStart);
                                unit.setBattleNetAnimationTimer(1);
                            }
                            return;
                        }
                        unit.setBattleNetOrderDelay(14);
                        if (world.battleNetSequence != null
                                && world.battleNetMoveAnimation(unit)) {
                            int moveStart = world.idle
                                    .battleNetSequenceStart(unit,
                                            BattleNetSequence.MOVE_ANIMATION);
                            if (moveStart >= 0) {
                                unit.setBattleNetSequenceOffset(moveStart);
                            }
                            unit.setBattleNetAnimationTimer(15);
                        }
                        return;
                    }
                    // Combat chases do not inherit Patrol's promise that the
                    // blocked body will eventually vacate this exact route.
                    // Native parks the stale leftover on the settle visit and
                    // asks the sea pathfinder again on the next visit. XOrc
                    // 11 destroyer 1521 settles at (8,36) with cached SW onto
                    // an allied firing destroyer at (6,34): it exposes route
                    // index 20 on fixture 173, then replans and steps N on
                    // 174. Giving that Attack chase Patrol's fifteen-count
                    // hold made an engaged warship appear frozen.
                    if (unit.target() != null && unit.target().isAlive()
                            && (unit.order() == Unit.Order.ATTACK
                                    || unit.order() == Unit.Order.ATTACK_MOVE
                                    || unit.chasing())
                            && world.combat
                                    .armBattleNetNavalBlockedChaseConstruction(
                                            unit)) {
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
                // Collision refusal is a movement-layer query, not a click or
                // resource lookup. A transient dead-vision marker can share
                // the committed square with the live mover and wins unitAt's
                // pointable roster walk. Native UnitOnMapTile asks for this
                // unit's move type, so select the live same-layer occupant.
                Unit movingBlocker = world.blockerOnLayer(
                        unit, nextX, nextY);
                boolean postRetargetParkRefill =
                        unit.battleNetRetargetResidualParkRefill();
                boolean paidWrapShortResidualPark =
                        postRetargetParkRefill
                        && unit.battleNetAttackWrapDestArmPending()
                        && unit.stepDrained() && !unit.isMoving()
                        && unit.battleNetPathInitialLength() == 3
                        && unit.pathLength() == 2
                        && unit.battleNetPathStepsTaken() == 1
                        && unit.battleNetCollisionCounter() == 1
                        && unit.battleNetRefusals() == 0
                        && unit.target() != null
                        && unit.target().isAlive()
                        && unit.target().type() != null
                        && !unit.target().type().building()
                        && !World.battleNetRangedChaseUnit(unit);
                boolean saturatedBuildingRetargetFormationRetry =
                        postRetargetParkRefill
                        && unit.battleNetRetargetResidualParkSteps() == 1
                        && unit.battleNetCollisionCounter() >= 1
                        && unit.battleNetCollisionCounter() <= 6
                        && unit.battleNetRefusals() == 0
                        && unit.target() != null
                        && unit.target().type() != null
                        && unit.target().type().building()
                        && unit.battleNetPathInitialLength()
                                == BattleNetPathFinder.MAX_PATH
                        && unit.pathLength() == BattleNetPathFinder.MAX_PATH
                        && movingBlocker != null
                        && movingBlocker != unit
                        && world.isAllied(
                                unit.player(), movingBlocker.player());
                if (saturatedBuildingRetargetFormationRetry) {
                    int collision = unit.battleNetCollisionCounter() + 1;
                    unit.setBattleNetCollisionCounter(collision);
                    unit.setWaitCycles(0);
                    if (collision <= 6) {
                        // The first byte of a saturated mobile-to-building
                        // replacement is retried naked while the allied wall is
                        // still resolving. Retail keeps Move-start/1 and writes
                        // route index twenty on each visit; it does not route
                        // around the formation's free west side. XHuman 12
                        // grunt 1492 repeats the southwest tower-footprint face
                        // on fixtures 235..239.
                        unit.clearPath();
                        unit.setRouteSpent(false);
                        unit.setBattleNetOrderDelay(0);
                        int moveStart = world.idle.battleNetSequenceStart(unit,
                                BattleNetSequence.MOVE_ANIMATION);
                        if (moveStart >= 0) {
                            unit.setBattleNetSequenceOffset(moveStart);
                            unit.setBattleNetAnimationTimer(1);
                            unit.setBattleNetChaseStepReady(false);
                        }
                        return;
                    }
                    // The fifth naked retry promotes the retained face to the
                    // ordinary paid movement band. Preserve the freshly drawn
                    // route so the first southwest byte executes exactly after
                    // Move 15..1 (fixture 240 through 255).
                    unit.setBattleNetOrderDelay(14);
                    unit.setBattleNetRefusalHold(true);
                    unit.setBattleNetRetargetResidualParkRefill(false);
                    int moveStart = world.idle.battleNetSequenceStart(unit,
                            BattleNetSequence.MOVE_ANIMATION);
                    if (moveStart >= 0) {
                        unit.setBattleNetSequenceOffset(moveStart);
                        unit.setBattleNetAnimationTimer(15);
                        unit.setBattleNetChaseStepReady(false);
                    }
                    return;
                }
                boolean firstPaidFullRouteResidualPark =
                        unit.battleNetCollisionCounter() == 2
                        && unit.battleNetRefusals() == 1
                        && unit.battleNetPathInitialLength()
                                == BattleNetPathFinder.MAX_PATH
                        && unit.pathLength()
                                == BattleNetPathFinder.MAX_PATH - 1;
                // Once the first leg of a long chase route has fully settled,
                // an already-collided next leg is a route-park boundary even
                // when the allied blocker is still visibly moving. Retail
                // parks the old cursor, replans on the following visit, and
                // lets that replacement route buy the cooperative band
                // (XHuman 12 grunt 1506: park 55, refill/timer 15 on 56).
                boolean settledOneStepLongResidualPark =
                        world.actionMoveWalked
                        && unit.stepDrained() && !unit.isMoving()
                        && unit.battleNetPathStepsTaken() == 1
                        && unit.battleNetCollisionCounter() == 1
                        && unit.pathLength() > 5
                        // A fresh collision proves the diagonal two-axis
                        // terminator. Sticky hard-refusal history is equivalent
                        // ownership for a cardinal face: XHuman 12 slot 1503
                        // settles its first E, parks the blocked second E at
                        // c123 (RI20, collision 1 -> 2), then redraws NE on
                        // c124.
                        && (Direction.isDiagonal(heading)
                                || unit.battleNetRefusals() > 0)
                        && unit.target() != null
                        && !World.battleNetRangedChaseUnit(unit);
                // A replacement ray installed after the settled retarget's
                // Attack-four handoff enters FUN_004379e0 even when its
                // allied first-square blocker is between Move opcodes. The ordinary
                // cooperative predicate deliberately requires a live Move
                // program, but that is one visit too narrow here: XHuman 10
                // grunt 1486 changes knight targets at fixture 72, writes
                // NE,E, and finds the NE grunt standing between strides.
                // Retail retains the ray and exposes Move-start/15; treating
                // that body as a hard refusal parked the cursor after five
                // visits, re-opened Attack, and stole an async RNG draw from
                // the following footman projectile.
                boolean immediateReplacementBody =
                        world.actionSettledMeleeReplacementAfterPaidBand
                        && movingBlocker != null
                        && movingBlocker != unit
                        && movingBlocker.isOnMap()
                        && !movingBlocker.isDying()
                        && movingBlocker.type() != null
                        && !movingBlocker.type().building()
                        && world.isAllied(
                                unit.player(), movingBlocker.player());
                boolean settledReplacementBlocker =
                        immediateReplacementBody
                        || ((world.actionSettledMeleeReplacementBroadRoute
                                || unit.battleNetAttackRefusalRecoveryStage()
                                        == 3)
                            && movingBlocker != null
                            && world.isAllied(
                                    unit.player(), movingBlocker.player())
                            && battleNetRefusalBandSoftClearMoveAlly(
                                    movingBlocker));
                boolean goldApproach = unit.resourceUnit() != null
                        && !unit.returningToDepot()
                        && unit.resourceUnit().type() != null
                        && unit.resourceUnit().type().givesResource()
                                == UnitType.Resource.GOLD;
                if (firstPaidFullRouteResidualPark
                        && world.battleNetCooperativeBlocker(
                                unit, movingBlocker)) {
                    // This first sticky generation ends the complete route;
                    // it does not enter either the cooperative wait or the
                    // later saturated-face retry ladder. Retail advances the
                    // collision projection, parks the cursor, and lets the
                    // following Move-start visit draw immediately. XHuman 12
                    // slot 1480 is 0x20/route-index 1 on fixture 166,
                    // 0x30/route-index 20 on 167, and commits the replacement
                    // SE byte on 168. Charging the ordinary cooperative band
                    // froze it; routing through the saturated retry inserted
                    // two empty collision visits which native does not own.
                    int collision = unit.battleNetCollisionCounter() + 1;
                    unit.setBattleNetCollisionCounter(
                            collision > 14 ? 0 : collision);
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
                if (!goldApproach
                        && !settledOneStepLongResidualPark
                        && (world.battleNetCooperativeBlocker(
                                        unit, movingBlocker)
                                || settledReplacementBlocker)) {
                    // Empty-route residual rebuild whose first heading lands
                    // on a soft-cleared ally: take the first free compass
                    // neighbour now (native N for XHuman 12 grunt 1507 at
                    // fixture 36). Mid-route leftovers still wait fourteen.
                    if (!paidStageSixCooperativeRoute
                            && unit.battleNetChaseEmptyRouteReplan()
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
                    // with a nearly-full leftover free-compass detours on the
                    // next movement visit. This is the Java scheduling bridge
                    // for native's park-at-36, replan-and-N-at-37 lifecycle.
                    // Preserve the collision increment from that native park:
                    // resetting it after choosing N left grunt 1494 at zero
                    // instead of two, so the adjacent grunt's fixture-42 path
                    // search incorrectly crossed the crowded north-east side.
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
                            unit.setBattleNetNearlyFullFreeDetour(true);
                            int collision = unit.battleNetCollisionCounter() + 1;
                            unit.setBattleNetCollisionCounter(
                                    collision > 14 ? 0 : collision);
                            heading = freeHeading;
                            nextX = unit.tileX()
                                    + Direction.deltaX(heading) * strideDetour;
                            nextY = unit.tileY()
                                    + Direction.deltaY(heading) * strideDetour;
                            canTakeStep = true;
                        }
                    }
                    if (!canTakeStep) {
                        boolean wrappedCollisionRetryPark =
                                unit.battleNetWrappedCollisionRetryPark()
                                && unit.battleNetCollisionCounter() <= 1
                                && unit.battleNetRefusals() > 0
                                && unit.pathLength() == 1
                                && unit.battleNetPathStepsTaken() == 0
                                && unit.stepDrained() && !unit.isMoving()
                                && unit.target() != null
                                && !World.battleNetRangedChaseUnit(unit);
                        if (wrappedCollisionRetryPark) {
                            int moveStart = world.idle
                                    .battleNetSequenceStart(unit,
                                            BattleNetSequence.MOVE_ANIMATION);
                            if (moveStart >= 0
                                    && unit.battleNetCollisionCounter() == 0
                                    && unit.battleNetSequenceOffset()
                                            != moveStart) {
                                // The replacement byte is first exposed under
                                // Move timer two. One quiet callback reduces it
                                // to one before the cursor is parked.
                                unit.setBattleNetCollisionCounter(1);
                                unit.setBattleNetOrderDelay(1);
                                unit.setBattleNetSequenceOffset(moveStart);
                                unit.setBattleNetAnimationTimer(2);
                                unit.setBattleNetChaseStepReady(false);
                                return;
                            }
                            // Timer one owns the route-index-twenty visit. The
                            // next Attack callback performs the proved idle RNG
                            // draw and opens fresh Attack construction.
                            unit.setBattleNetWrappedCollisionRetryPark(false);
                            unit.clearPath();
                            unit.setRouteSpent(false);
                            unit.setWaitCycles(0);
                            unit.setBattleNetOrderDelay(0);
                            unit.setBattleNetResidualEmptyApproachIdlePending(
                                    true);
                            if (moveStart >= 0) {
                                unit.setBattleNetSequenceOffset(moveStart);
                                unit.setBattleNetAnimationTimer(1);
                                unit.setBattleNetChaseStepReady(false);
                            }
                            return;
                        }
                        boolean boundaryForestTerminalResidual =
                                unit.battleNetBorrowedMoveForStep()
                                && unit.resourceUnit() == null
                                && unit.resourceTileX() >= 0
                                && unit.resourceTileY() >= 0
                                && world.map.field(unit.resourceTileX(),
                                        unit.resourceTileY()).isForest()
                                && unit.stepDrained() && !unit.isMoving()
                                && unit.pathLength() == 1
                                && unit.battleNetGoldFreePrefix()
                                && !Direction.isDiagonal(heading)
                                && (unit.battleNetWoodOrderX() <= 1
                                        || unit.battleNetWoodOrderY() <= 1
                                        || unit.battleNetWoodOrderX()
                                                >= world.map.width() - 2
                                        || unit.battleNetWoodOrderY()
                                                >= world.map.height() - 2);
                        if (boundaryForestTerminalResidual) {
                            // A map-edge forest prefix's interior axial tail
                            // is a one-visit route-index-20 collision, not a
                            // cooperative fifteen-count wait. Human 12 peon
                            // 1571 parks occupied E at fixture 228 and redraws
                            // the now-reachable NE boundary corner on 229.
                            int collision =
                                    unit.battleNetCollisionCounter() + 1;
                            unit.setBattleNetCollisionCounter(
                                    collision > 14 ? 0 : collision);
                            unit.clearPath();
                            unit.setRouteSpent(false);
                            unit.setWaitCycles(0);
                            unit.setBattleNetOrderDelay(0);
                            return;
                        }
                        boolean saturatedFreshLadenReturnPark =
                                unit.battleNetBorrowedMoveForStep()
                                && unit.returningToDepot()
                                && unit.carried() > 0
                                && unit.type() != null
                                && unit.type().moveType()
                                        == UnitType.Movement.LAND
                                && unit.battleNetRefusals() >= 8
                                && unit.battleNetPathStepsTaken() == 0
                                && unit.stepDrained() && !unit.isMoving()
                                && unit.pathLength() > 0
                                && world.battleNetCooperativeBlocker(
                                        unit, movingBlocker);
                        int counter = unit.battleNetCollisionCounter() + 1;
                        unit.setBattleNetCollisionCounter(
                                counter > 14 ? 0 : counter);
                        if (saturatedFreshLadenReturnPark) {
                            // Once a laden land return has completed the
                            // eight-refusal ladder, an occupied first byte of
                            // its fresh replacement is parked even when the
                            // body would ordinarily earn a cooperative cached-
                            // route wait. The full Move band still belongs to
                            // this visit. XOrc 6 peasant 1516 independently
                            // parks west- and north-led replacements; XHuman
                            // 12 peon 1550 supplies an independent member of
                            // the same saturated fresh-route rule. A route
                            // with a consumed prefix remains live, as witnessed
                            // by XHuman 10 peon 1588 and Human 14 peon 1539.
                            battleNetRefuse(unit);
                            unit.setRouteSpent(false);
                            unit.setWaitCycles(0);
                            unit.setBattleNetOrderDelay(14);
                            unit.setBattleNetRefusalHold(false);
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
                        // This first cooperative wait is a real visit to
                        // FUN_004379e0, so it also owns the sticky refusal
                        // nibble.  Keep the local collision count separate --
                        // it is reset by several Java route-generation seams --
                        // but seed the native count for an initial multi-step
                        // melee chase.  XHuman 4 grunt 1505 plans W,NW,W,W,W
                        // through a moving axe at fixture 9: retail records
                        // refusal 1 while preserving that route for fifteen,
                        // then its hard parks at fixtures 24..30 reach refusal
                        // 8.  Missing this seed moved its recovery N one cycle
                        // late (fixture 55 instead of 54).
                        boolean initialMeleeChaseRefusal = counter == 1
                                && unit.battleNetRefusals() == 0
                                && !unit.stepDrained()
                                && unit.pathLength() > 1
                                && unit.target() != null
                                && !World.battleNetRangedChaseUnit(unit)
                                && (unit.order() == Unit.Order.ATTACK
                                        || unit.order()
                                                == Unit.Order.ATTACK_MOVE
                                        || unit.chasing());
                        if (initialMeleeChaseRefusal) {
                            unit.setBattleNetRefusals(1);
                        }
                        boolean initialPatrolRefusal = counter == 1
                                && unit.battleNetRefusals() == 0
                                && (unit.order() == Unit.Order.PATROL
                                        || (unit.battleNetBorrowedMoveForStep()
                                                && unit.patrolX() >= 0))
                                && unit.pathLength() > 1;
                        if (initialPatrolRefusal) {
                            // The same FUN_004379e0 visit owns the sticky
                            // high nibble for a point Patrol. Orc 11 archer
                            // 1563 records nibble one while retaining its
                            // twenty-byte NE-led ray at fixture 111.
                            unit.setBattleNetRefusals(1);
                        }
                        // Residual-settled one-heading leftover blocked on the
                        // settle visit: native route_index 20 then replan
                        // (XHuman 12 grunt 1514: residual of E onto 28,38,
                        // SE onto ally, RI 1→20 at fixture 41, N@42). Soft-
                        // waiting fourteen left Java until fixture 52. Mid-
                        // route pathn1 soft-wait is not residual-settled
                        // (grunt 1503 holds E with walked=0) and still uses
                        // the second-refuse replan below.
                        boolean residualSettledPathn1Base =
                                world.actionMoveWalked
                                && unit.stepDrained()
                                && !unit.isMoving()
                                && unit.pathLength() == 1
                                && unit.target() != null
                                && !World.battleNetRangedChaseUnit(unit)
                                && (unit.order() == Unit.Order.ATTACK
                                        || unit.order()
                                                == Unit.Order.ATTACK_MOVE
                                        || unit.chasing());
                        // Route-index 20 is the no-progress answer. If another
                        // free compass cell still closes on the quarry, the
                        // cooperative blocker owns the retained one-byte route
                        // and its complete Move timer instead. Human 13 ogre
                        // 1510 keeps SE under timer 15 while free S remains a
                        // closer alternative; Java used to park SE and consume
                        // S on the following visit.
                        boolean residualSettledPathn1 =
                                residualSettledPathn1Base
                                && !battleNetHasStrictlyCloserFreeNeighbour(
                                        unit, unit.target());
                        boolean retainedProgressPathn1 =
                                residualSettledPathn1Base
                                && !residualSettledPathn1;
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
                                && !unit.battleNetMovingQuarryResidual()
                                && (unit.order() == Unit.Order.ATTACK
                                            || unit.order()
                                                    == Unit.Order.ATTACK_MOVE
                                            || unit.chasing())) {
                            if (!residualSettledPathn1 && counter > 14) {
                                // This retry completed the native collision
                                // generation. Its next one-byte route owns a
                                // short Move 2,1 / route-index-20 handoff, not
                                // another complete cooperative refusal band.
                                // XHuman 12 ogre 1453 is the authenticated
                                // witness at fixtures 180..183.
                                unit.setBattleNetWrappedCollisionRetryPark(true);
                            }
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
                                && !postRetargetParkRefill
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
                                    unit.clearPath();
                                    // The compass result is the approved first
                                    // byte of a replacement buffer, not a
                                    // complete one-byte route. Retail 1510
                                    // consumes SE at fixture 41 but retains
                                    // E,E,NE...; that E is what enters the
                                    // fifteen-count refusal when the residual
                                    // settles at fixture 57. Losing the tail
                                    // made Java empty-route replan NW at 58.
                                    PathFinder.Result refill = world.planTowards(
                                            unit, unit.target(), true);
                                    if (refill == PathFinder.Result.FOUND
                                            && unit.pathLength() > 0) {
                                        unit.replacePeekHeading(freeHeading);
                                    } else {
                                        unit.setPath(new PathFinder.Path(
                                                PathFinder.Result.FOUND,
                                                new int[] {freeHeading}));
                                    }
                                    if (keptPathn > 1 && keptColl >= 1) {
                                        unit.setBattleNetCollisionCounter(
                                                keptColl);
                                    }
                                    unit.setBattleNetChaseEmptyRouteReplan(
                                            false);
                                    // A full replacement retains its next
                                    // Move decision; the one-byte fallback
                                    // needs the old Attack-four delay/free-
                                    // wake bridge. Native parks on this visit
                                    // and may consume the approved head on the
                                    // following one.
                                    unit.setBattleNetOrderDelay(
                                            unit.pathLength() > 1 ? 0 : 3);
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
                            boolean immediateReplacementRefusal = replanHold
                                    && world.actionSettledMeleeReplacementRoute;
                            if (replanHold
                                    && !immediateReplacementRefusal) {
                                unit.setBattleNetChaseReplanResidualHold(
                                        false);
                            }
                            int quiet = World.battleNetCooperativeRefuseQuietVisits(
                                    replanHold
                                            && !immediateReplacementRefusal);
                            // Nearly-full leftover residual-settled: one extra
                            // quiet visit so free-compass after this wait lands
                            // at fixture 37 not 36 (XHuman 12 grunt 1494).
                            // This is a melee chase exception, not a generic
                            // path-length rule. XHuman 12's builder peon 1376
                            // also has a six-heading free prefix when traffic
                            // refuses its east step; giving that BUILD walk the
                            // combat visit moved its third tile at fixture 56
                            // instead of retail's 55.
                            if (unit.stepDrained() && !unit.isMoving()
                                    && unit.pathLength() == 6
                                    && unit.target() != null
                                    && !World.battleNetRangedChaseUnit(unit)
                                    && (unit.order() == Unit.Order.ATTACK
                                            || unit.order()
                                                    == Unit.Order.ATTACK_MOVE
                                            || unit.chasing())) {
                                quiet = Math.max(quiet, 15);
                            }
                            boolean fullRefusalBand =
                                    settledMultiResidualRefusal(unit);
                            boolean stagedMobileResidualRefill =
                                    fullRefusalBand
                                    && world.actionMoveWalked
                                    && (unit.battleNetRefusals() > 0
                                            || unit.battleNetCollisionCounter()
                                                    > 1);
                            boolean retainedBuildingResidual =
                                    fullRefusalBand
                                    && unit.target() != null
                                    && unit.target().type() != null
                                    && unit.target().type().building();
                            // A residual-settle callback parks at Move-start/1
                            // before the following refusal callback writes 15.
                            // Direct refusal-handler visits write 15 now, so
                            // only the settling wrap boundary owns the extra
                            // logical visit.
                            unit.setBattleNetOrderDelay(
                                    quiet + (stagedMobileResidualRefill
                                            && !retainedBuildingResidual
                                                    ? 1 : 0));
                            armBattleNetAttackRefusalMove(unit);
                            if (unit.returningToDepot()
                                    && unit.carried() > 0
                                    && unit.pathLength() > 0) {
                                // Resource orders borrow MOVE only for the
                                // step, so the attack-only helper above has no
                                // live quarry from which to arm the refusal
                                // program. A cooperative laden return owns the
                                // same full Move-start/15 band: XHuman 10 peon
                                // 1588 retains S,S,S,SW behind clean moving
                                // peon 1584 at fixtures 270 and 285.
                                int moveStart = world.idle
                                        .battleNetSequenceStart(unit,
                                                BattleNetSequence.MOVE_ANIMATION);
                                if (moveStart >= 0) {
                                    unit.setBattleNetSequenceOffset(moveStart);
                                    unit.setBattleNetAnimationTimer(15);
                                    unit.setBattleNetChaseStepReady(false);
                                }
                            }
                            if (retainedProgressPathn1) {
                                // This is a residual Move-body refusal, so the
                                // native program restarts at its opening with
                                // timer fifteen. The generic helper preserves
                                // an executing Move cursor; that is correct for
                                // ordinary mid-body refusals but left Human 13
                                // at 589 instead of the sealed 586/15 state.
                                int moveStart = world.idle
                                        .battleNetSequenceStart(unit,
                                                BattleNetSequence.MOVE_ANIMATION);
                                if (moveStart >= 0) {
                                    unit.setBattleNetSequenceOffset(moveStart);
                                    unit.setBattleNetAnimationTimer(15);
                                    // stepBattleNetAttackSequence advances the
                                    // restarted Move cursor before the logical
                                    // order-delay arm runs on the next visit.
                                    // Retain that visit in the Java countdown
                                    // so the exposed native timer falls 15→14,
                                    // not 15→13.
                                    unit.setBattleNetOrderDelay(
                                            unit.battleNetMovingQuarryResidual()
                                                    ? quiet : 15);
                                    unit.setBattleNetChaseStepReady(false);
                                }
                            }
                            if ((unit.order() == Unit.Order.PATROL
                                    || (unit.battleNetBorrowedMoveForStep()
                                            && unit.patrolX() >= 0))
                                    && unit.pathLength() > 0) {
                                // A cooperative point-Patrol refusal owns the
                                // same Move-start/15 program as combat, but it
                                // has no target for the attack-only helper.
                                // Native Orc 11 archer 1563 is 1982/15 on the
                                // first refused NE visit, not Still/1.
                                int moveStart = world.idle
                                        .battleNetSequenceStart(unit,
                                                BattleNetSequence.MOVE_ANIMATION);
                                if (moveStart >= 0) {
                                    unit.setBattleNetSequenceOffset(moveStart);
                                    unit.setBattleNetAnimationTimer(15);
                                    unit.setBattleNetChaseStepReady(false);
                                }
                            }
                            boolean countedAttackRefusalBand =
                                    unit.battleNetRefusals() == 1
                                    && unit.battleNetCollisionCounter() == 1
                                    && unit.pathLength() == 1
                                    && unit.battleNetPathStepsTaken() == 0
                                    && unit.stepDrained()
                                    && !unit.isMoving()
                                    && unit.target() != null
                                    && !World.battleNetRangedChaseUnit(unit)
                                    && (unit.order() == Unit.Order.ATTACK
                                            || unit.order()
                                                    == Unit.Order.ATTACK_MOVE
                                            || unit.chasing());
                            if (countedAttackRefusalBand) {
                                // A route rebuilt after an already-counted
                                // Attack refusal owns the complete native
                                // Move timer even if its cooperative blocker
                                // vacates early. Resetting only the logical
                                // delay let the live Move bytecode cursor run
                                // forward and the one-heading free-wake arm
                                // cancel the band on its next visit. The unit
                                // then churned to refusal eight and slept in
                                // Java's generic wait, bypassing BNE's later
                                // active-order idle/Attack retry (XHuman 12
                                // grunt 1441, native fixtures 71..90).
                                int moveStart = world.idle
                                        .battleNetSequenceStart(unit,
                                                BattleNetSequence.MOVE_ANIMATION);
                                if (moveStart >= 0) {
                                    unit.setBattleNetSequenceOffset(moveStart);
                                    unit.setBattleNetAnimationTimer(15);
                                    unit.setBattleNetChaseStepReady(false);
                                }
                                unit.setBattleNetRefusalHold(true);
                            }
                            unit.setBattleNetRetargetResidualParkRefill(false);
                            if (immediateReplacementRefusal) {
                                // The replacement ray was refused on the same
                                // callback which installed it. Retail starts
                                // Move at timer fifteen immediately, then the
                                // normal wake bridge opens Attack 3,2,1. An
                                // already-running Java Move cursor otherwise
                                // advanced one opcode and hid that bridge.
                                int moveStart = world.idle
                                        .battleNetSequenceStart(unit,
                                                BattleNetSequence.MOVE_ANIMATION);
                                if (moveStart >= 0) {
                                    unit.setBattleNetSequenceOffset(moveStart);
                                    unit.setBattleNetAnimationTimer(15);
                                    unit.setBattleNetChaseStepReady(false);
                                }
                            }
                            if (fullRefusalBand
                                    && !retainedBuildingResidual) {
                                // Once the settled route has been parked,
                                // retail pays the complete refusal band even
                                // if the planned cell frees in the meantime.
                                unit.setBattleNetRefusalHold(true);
                            }
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
                                if (unit.returningToDepot()
                                        && unit.carried() > 0) {
                                    // The wake belongs to FUN_004379e0's
                                    // route-park visit; the resource order
                                    // does not plan again until its following
                                    // visit. Store that additional quiet turn
                                    // here (XHuman 8: park c271, W c272).
                                    unit.setBattleNetOrderDelay(quiet + 1);
                                }
                            }
                            return;
                        }
                    }
                }
                if (postRetargetParkRefill) {
                    // A non-cooperative refusal has its own hard-refusal
                    // lifecycle below. The marker describes only this first
                    // replacement probe and must not leak into that retry.
                    unit.setBattleNetRetargetResidualParkRefill(false);
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
                    if (paidAttackTailGenerationPark(unit)) {
                        parkPaidAttackTailGeneration(unit, heading);
                        return;
                    }
                    boolean completedCardinalPressureBand =
                            unit.stepDrained() && !unit.isMoving()
                            && unit.pathLength() >= 16
                            && unit.battleNetPathStepsTaken() == 1
                            && unit.battleNetCollisionCounter() > 1
                            && unit.battleNetRefusals() >= 8
                            && !Direction.isDiagonal(heading)
                            && settledMeleeAlly;
                    if (completedCardinalPressureBand) {
                        // The eighth-refusal band has already paid for this
                        // face. Its first settled cardinal pressure parks the
                        // long route for one active-order reconstruction
                        // instead of charging another Move band.
                        int collision = unit.battleNetCollisionCounter() + 1;
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
                    boolean retainedCardinalPressureBand =
                            unit.stepDrained() && !unit.isMoving()
                            && unit.pathLength() >= 16
                            && unit.battleNetPathStepsTaken() == 1
                            && unit.battleNetCollisionCounter() > 1
                            && unit.battleNetRefusals() > 0
                            && !Direction.isDiagonal(heading)
                            && settledMeleeAlly;
                    if (retainedCardinalPressureBand) {
                        // A sticky refusal generation has already selected
                        // this nearly-full cardinal face. Native advances its
                        // collision generation and pays the complete Move
                        // band while retaining the route buffer, so the unit
                        // resumes through the battle line instead of escaping
                        // diagonally from a cold replan.
                        int collision = unit.battleNetCollisionCounter() + 1;
                        unit.setBattleNetCollisionCounter(
                                collision > 14 ? 0 : collision);
                        unit.setWaitCycles(0);
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
                            // A one-step long residual with an existing
                            // collision is the route-park arm, not the axis-
                            // rescue arm. XHuman 12 grunt 1517 carries eight
                            // headings after its first SE, settles against an
                            // allied blocker at fixture 58, parks there, and
                            // refills E on 59. Same-cycle E let it steal the
                            // square one visit early. Fresh long routes
                            // (pathStepsTaken zero) still need this rescue.
                            && !(unit.battleNetPathStepsTaken() == 1
                                    && unit.battleNetCollisionCounter() > 0
                                    && unit.pathLength() > 5
                                    && (unit.battleNetRefusals() == 0
                                            // A sticky refusal can still
                                            // authorize the component of a
                                            // compact prefix. Once the first
                                            // leg leaves a nearly full native
                                            // buffer, however, that pressure
                                            // owns a route park before any
                                            // component is spent. XHuman 12
                                            // slot 1495 parks nineteen-byte
                                            // NW at fixture 102; taking its
                                            // free N component walked a whole
                                            // tile early. The compact
                                            // fourteen-byte slot 1517 prefix
                                            // retains its authenticated free
                                            // component behavior.
                                            || unit.pathLength() >= 16))
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
                            boolean reverseComponents =
                                    unit.battleNetCollisionCounter() >= 3;
                            for (int scan = 0; scan < Direction.COUNT; scan++) {
                                int dir = reverseComponents
                                        ? Direction.COUNT - 1 - scan : scan;
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
                                boolean paidBoundedPrefix = unit
                                        .battleNetMoveFreeDetourPending()
                                        || unit.battleNetCollisionCounter()
                                                >= 3;
                                boolean rewriteLongResidualBuffer =
                                        !paidBoundedPrefix
                                        && unit.pathLength() >= 16
                                        && unit.battleNetPathStepsTaken() >= 3
                                        && unit.battleNetRefusals() == 0
                                        && unit.target() != null;
                                if (rewriteLongResidualBuffer) {
                                    // After several cached legs, the second
                                    // visit to a blocked diagonal runs the
                                    // full route writer and then installs the
                                    // free cardinal component at its head. It
                                    // is not an in-place edit of the old
                                    // buffer. XHuman 12 slot 1500 rewrites
                                    // NE,SE... to N,NE,SE... at fixture 178;
                                    // changing only NE to N discarded the NE
                                    // byte which native consumes at 194.
                                    unit.clearPath();
                                    PathFinder.Result rewritten =
                                            world.planTowards(
                                                    unit, unit.target(), true);
                                    if (rewritten == PathFinder.Result.FOUND
                                            && unit.pathLength() > 0) {
                                        int redrawnHeading =
                                                unit.peekHeading();
                                        int redrawnX = unit.tileX()
                                                + Direction.deltaX(
                                                        redrawnHeading)
                                                * strideDetour;
                                        int redrawnY = unit.tileY()
                                                + Direction.deltaY(
                                                        redrawnHeading)
                                                * strideDetour;
                                        if (world.canEnter(
                                                unit, redrawnX, redrawnY)) {
                                            // A complete redraw which already
                                            // opens on a free square owns that
                                            // byte. Replacing it with an axis
                                            // component of the stale blocked
                                            // diagonal sent XHuman 12 slot
                                            // 1470 north at fixture 243 even
                                            // though both native and the fresh
                                            // route writer chose free south.
                                            freeHeading = redrawnHeading;
                                        } else {
                                            unit.replacePeekHeading(
                                                    freeHeading);
                                        }
                                    } else {
                                        unit.setPath(new PathFinder.Path(
                                                PathFinder.Result.FOUND,
                                                new int[] {freeHeading}));
                                    }
                                } else {
                                    unit.replacePeekHeading(freeHeading);
                                }
                                if (!paidBoundedPrefix) {
                                    unit.setBattleNetCollisionCounter(0);
                                }
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
                        boolean saturatedWallFacePairSettle =
                                unit.battleNetSaturatedWallFacePairHeading()
                                        == heading
                                && !unit.battleNetSaturatedWallFacePairParked()
                                && unit.stepDrained() && !unit.isMoving()
                                && unit.pathLength() == 1
                                && unit.battleNetPathStepsTaken() == 1
                                && unit.battleNetCollisionCounter() >= 5
                                && unit.battleNetRefusals() >= 2;
                        if (saturatedWallFacePairSettle) {
                            int collision =
                                    unit.battleNetCollisionCounter() + 1;
                            unit.setBattleNetCollisionCounter(
                                    collision > 14 ? 0 : collision);
                            unit.clearPath();
                            unit.setRouteSpent(false);
                            unit.setWaitCycles(0);
                            unit.setBattleNetOrderDelay(0);
                            unit.setBattleNetSaturatedWallFacePairParked(true);
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
                        boolean saturatedResidualFacePark =
                                unit.stepDrained() && !unit.isMoving()
                                && unit.pathLength() >= 16
                                && unit.battleNetPathStepsTaken() == 1
                                && unit.battleNetRefusals() > 0
                                && unit.target() != null
                                && !Direction.isDiagonal(
                                        World.battleNetFirstBresenhamHeading(
                                                unit.tileX(), unit.tileY(),
                                                unit.target().tileX(),
                                                unit.target().tileY()));
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
                                && unit.pathLength() >= 4
                                && world.unitAt(nextX, nextY) == null) {
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
                        // The residual-settle entry adds the native collision
                        // nibble before parking the route. A standing hard
                        // refusal that did not just drain uses the separate
                        // sticky ladder below; incrementing both advanced its
                        // fifteenth-refusal wrap one cycle early (XHuman 12
                        // grunt 1503). Native grunt 1476 is the residual arm:
                        // collision one becomes two on fixture 40.
                        boolean saturatedStickyResidualBand =
                                unit.stepDrained() && !unit.isMoving()
                                && unit.battleNetPathStepsTaken() == 1
                                && unit.pathLength() > 1
                                && unit.battleNetCollisionCounter() >= 8
                                && unit.battleNetRefusals() >= 3
                                && unit.target() != null
                                && !World.battleNetRangedChaseUnit(unit)
                                && (unit.order() == Unit.Order.ATTACK
                                        || unit.order()
                                                == Unit.Order.ATTACK_MOVE
                                        || unit.chasing());
                        if (saturatedStickyResidualBand) {
                            // This residual is already inside the saturated
                            // formation generation. Retail parks the retained
                            // buffer and writes the complete Move timer on the
                            // settle visit; it does not spend four more naked
                            // refusal callbacks to rediscover refusal eight.
                            // XHuman 12 slot 1506 is collision eight/refusal
                            // three while its NW stride settles at fixture
                            // 184, remains Move 15..1, then redraws and commits
                            // east at 199. Counting refusals four through eight
                            // first delayed the band to 189 and incorrectly
                            // returned through Attack construction after it.
                            int retainedHead = unit.peekHeading();
                            unit.clearPath();
                            unit.setRouteSpent(false);
                            unit.setWaitCycles(0);
                            unit.setBattleNetOrderDelay(14);
                            unit.setBattleNetRefusalHold(true);
                            unit.setBattleNetChaseEmptyRouteReplan(true);
                            int moveStart = world.idle
                                    .battleNetSequenceStart(unit,
                                            BattleNetSequence.MOVE_ANIMATION);
                            if (moveStart >= 0) {
                                unit.setBattleNetSequenceOffset(moveStart);
                                unit.setBattleNetAnimationTimer(15);
                                unit.setBattleNetChaseStepReady(false);
                            }
                            world.causalTrace.event(world.cycle,
                                    "path.saturated-sticky-residual-band",
                                    unit.id(),
                                    "heading", retainedHead,
                                    "collision",
                                            unit.battleNetCollisionCounter(),
                                    "refusals", unit.battleNetRefusals());
                            return;
                        }
                        boolean saturatedLongResidualConstruction =
                                postRetargetParkRefill
                                && unit.stepDrained() && !unit.isMoving()
                                && unit.battleNetPathInitialLength()
                                        == BattleNetPathFinder.MAX_PATH
                                && unit.pathLength() >= 16
                                && unit.battleNetPathStepsTaken() >= 3
                                && unit.battleNetCollisionCounter() == 2
                                && unit.battleNetRefusals() == 0
                                && unit.target() != null
                                && unit.target().isAlive()
                                && unit.target().type() != null
                                && !unit.target().type().building()
                                && !World.battleNetRangedChaseUnit(unit);
                        boolean paidLongResidualPark =
                                unit.stepDrained() && !unit.isMoving()
                                && unit.battleNetPathInitialLength()
                                        == BattleNetPathFinder.MAX_PATH
                                && unit.battleNetPathStepsTaken() == 1
                                && unit.battleNetCollisionCounter() == 3
                                && unit.battleNetRefusals() == 0
                                && unit.target() != null
                                && unit.target().isAlive()
                                && unit.target().type() != null
                                && !unit.target().type().building()
                                && !World.battleNetRangedChaseUnit(unit);
                        boolean paidFourByteParkRedraw =
                                postRetargetParkRefill
                                && unit.stepDrained() && !unit.isMoving()
                                && unit.battleNetPathInitialLength() == 4
                                && unit.pathLength() == 3
                                && unit.battleNetPathStepsTaken() == 1
                                && unit.battleNetCollisionCounter() == 0
                                && unit.battleNetRefusals() == 0
                                && unit.target() != null
                                && unit.target().isAlive()
                                && unit.target().type() != null
                                && !unit.target().type().building()
                                && !World.battleNetRangedChaseUnit(unit);
                        boolean firstSaturatedResidualProgressiveRefill =
                                !postRetargetParkRefill
                                && unit.stepDrained() && !unit.isMoving()
                                && unit.battleNetPathInitialLength()
                                        == BattleNetPathFinder.MAX_PATH
                                && unit.battleNetPathStepsTaken() >= 3
                                && unit.battleNetCollisionCounter() == 0
                                && unit.battleNetRefusals() == 0
                                && unit.target() != null
                                && unit.target().isAlive()
                                && unit.target().type() != null
                                && !unit.target().type().building()
                                && !World.battleNetRangedChaseUnit(unit);
                        if (unit.stepDrained() && !unit.isMoving()) {
                            int collision =
                                    unit.battleNetCollisionCounter() + 1;
                            unit.setBattleNetCollisionCounter(
                                    collision > 14 ? 0 : collision);
                        }
                        if (postRetargetParkRefill
                                && heading >= 0
                                && heading < Direction.COUNT) {
                            // This hard clear projects native route index 20
                            // as an empty Java path. Preserve the refused byte
                            // for the one refill callback which continues the
                            // same wall face.
                            unit.setBattleNetParkedRefusalHeading(heading);
                        }
                        if (unit.battleNetSaturatedRetargetRouteBand()
                                && heading >= 0
                                && heading < Direction.COUNT) {
                            // The first paid band ends by parking its retained
                            // head. Native's following NewPath continues from
                            // that compass face instead of starting a cold
                            // opposite-side wall search.
                            unit.setBattleNetParkedRefusalHeading(heading);
                        }
                        int refusals = battleNetRefuse(unit);
                        if (paidWrapShortResidualPark) {
                            // The compact tail retained behind a completed
                            // Attack wrap remains one paid route transaction.
                            // When its first cached byte is occupied as the
                            // committed stride settles, native advances the
                            // collision generation and parks RI 20, but does
                            // not start a new hard-refusal ladder. The next
                            // Move OP0 owns a fresh full wall writer. XHuman
                            // 12 slot 1517 parks S on fixture 279, then writes
                            // SE,SE,E,NE... and commits SE on fixture 280.
                            unit.setBattleNetRefusals(0);
                            unit.setBattleNetChaseEmptyRouteReplan(true);
                            unit.setBattleNetRetargetResidualParkRefill(true);
                            unit.setBattleNetRetargetResidualParkSteps(1);
                            unit.markBattleNetLongPaidWrapParkedRoute();
                            unit.setBattleNetParkedRefusalHeading(-1);
                            refusals = 0;
                        }
                        if (firstSaturatedResidualProgressiveRefill
                                && refusals == 1) {
                            // A saturated collision-free chase which has
                            // already consumed several bytes owns a distinct
                            // first-refusal refill. Native XHuman 12 slot 1481
                            // parks its blocked NE tail at fixture 267, then
                            // retains the free progressive SW face as a
                            // complete one-byte route instead of cold-
                            // searching the opposite NW wall. Carry only the
                            // provenance; the next target-route draw still
                            // proves whether the direct face is free and
                            // strictly closer.
                            unit
                                    .setBattleNetFirstSaturatedResidualProgressiveRefill(
                                            true);
                        }
                        if (paidFourByteParkRedraw) {
                            // The one-step paid route is parked with its raw
                            // bytes intact, but NewPath owns a fresh buffer on
                            // the next visit. Do not carry Java's rejected head
                            // clockwise into that buffer. Slot 1490 redraws
                            // SE,SW,W and vacates (31,38) on fixture 263.
                            unit.markBattleNetLongPaidWrapParkedRoute();
                        }
                        if (paidLongResidualPark) {
                            // The fourth mobile-formation generation is stored
                            // only in FUN_004379e0's high nibble. It is not the
                            // first visit in a new hard-refusal band. XHuman 12
                            // grunt 1504 drains N and refuses the next NE byte
                            // on fixture 245: native writes 0x30 -> 0x40 and
                            // parks RI20, then the paid wall writer opens NW on
                            // 246. Keep Java's duplicate refusal projection out
                            // of this boundary and mark the empty route for the
                            // already-paid refill.
                            unit.setBattleNetRefusals(0);
                            unit.setBattleNetChaseEmptyRouteReplan(true);
                            unit.setBattleNetPaidLongResidualRefill(true);
                            unit.setBattleNetParkedRefusalHeading(heading);
                            refusals = 0;
                        }
                        if (saturatedLongResidualConstruction
                                && refusals == 1) {
                            // A saturated twenty-byte chase which has already
                            // committed three headings does not fall back to
                            // the cold empty-route/free-neighbour retry after
                            // its next head is refused. Retail parks RI 20,
                            // advances collision two to three, then pays the
                            // existing Attack 3,2,1 replacement constructor.
                            // XHuman 12 grunt 1481 is the authenticated
                            // witness: park fixture 215, construction 216..218,
                            // fresh east route byte on 219.
                            unit.setBattleNetChaseReplanResidualHold(true);
                        }
                        if (saturatedResidualFacePark) {
                            unit.setBattleNetSaturatedResidualFaceRetry(true);
                        }
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
                        // A hard-refused attack route owns Move-start rather
                        // than allowing the bytecode cursor to walk forward
                        // while the unit remains stationary. Refusals two
                        // through seven park at timer one; refusal eight
                        // writes fifteen and hands the eventual wake to the
                        // Attack retry state machine above.
                        // stepMoveTowardsTarget temporarily labels this same
                        // native Attack execution as MOVE while it calls the
                        // movement subsystem.  Chasing + a live target is the
                        // ownership proof here; testing the surrogate order
                        // label silently skipped every real attack chase.
                        if (unit.target() != null && unit.chasing()) {
                            int moveStart = world.idle
                                    .battleNetSequenceStart(unit,
                                            BattleNetSequence.MOVE_ANIMATION);
                            if (moveStart >= 0) {
                                unit.setBattleNetSequenceOffset(moveStart);
                                unit.setBattleNetAnimationTimer(
                                        refusals >= 8 ? 15 : 1);
                                unit.setBattleNetChaseStepReady(false);
                            }
                            if (refusals == 8) {
                                unit.setBattleNetAttackRefusalRecoveryStage(4);
                            }
                        }
                        return;
                    }
                }
                if (canTakeStep) {
                    if (unit.battleNetSaturatedRetargetRouteBand()) {
                        unit.setBattleNetSaturatedRetargetRouteBand(false);
                        unit.setBattleNetParkedRefusalHeading(-1);
                    }
                    if (unit.battleNetAttackRefusalRecoveryStage() == 6
                            && unit.battleNetParkedRefusalHeading()
                                    == heading) {
                        // The parked byte's lifetime ends when its paid probe
                        // is finally accepted. Later route generations must
                        // choose their own wall face.
                        unit.setBattleNetParkedRefusalHeading(-1);
                    }
                    unit.clearBattleNetWoodCornerRefusal();
                    unit.setBattleNetChaseEmptyRouteReplan(false);
                    unit.setBattleNetRetargetResidualParkRefill(false);
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
                        boolean fullFreePrefixConstruction =
                                unit.battleNetGoldFreePrefix()
                                && unit.battleNetGoldFreePrefixLength() == 6
                                && unit.pathLength() == 4
                                && unit.battleNetCollisionCounter() == 2
                                && shortcut >= 0
                                && !Direction.isDiagonal(shortcut);
                        if (fullFreePrefixConstruction) {
                            int[] redrawnOrder = world.harvest
                                    .battleNetWoodOrderPoint(unit,
                                            unit.resourceTileX(),
                                            unit.resourceTileY());
                            boolean orderChanged = redrawnOrder[0]
                                    != unit.battleNetWoodOrderX()
                                    || redrawnOrder[1]
                                            != unit.battleNetWoodOrderY();
                            if (orderChanged) {
                                // The six-byte forest ray has reached its
                                // occupied skirt, but its two-heading shortcut
                                // is not another movement byte. Retail retires
                                // the route, recomputes unit+0x84 from the
                                // reverse resource ray and enters action 23's
                                // 2657/3,2,1 constructor on this visit. XHuman
                                // 12 slot 1364 therefore stays at (10,89) on
                                // fixture 263, rewrites (14,89) to the wall at
                                // (13,89), then redraws SE,E on fixture 266.
                                unit.clearPath();
                                unit.setRouteSpent(false);
                                unit.setBattleNetWoodTerminalRefusalHeading(
                                        heading);
                                unit.setBattleNetWoodOrder(
                                        redrawnOrder[0], redrawnOrder[1]);
                                unit.setBattleNetCollisionCounter(0);
                                unit.setBattleNetRefusals(0);
                                unit.setWaitCycles(0);
                                unit.setBattleNetOrderDelay(0);
                                return;
                            }
                        }
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
                            int woodOrderX = unit.battleNetWoodOrderX() >= 0
                                    ? unit.battleNetWoodOrderX()
                                    : unit.resourceTileX();
                            int woodOrderY = unit.battleNetWoodOrderY() >= 0
                                    ? unit.battleNetWoodOrderY()
                                    : unit.resourceTileY();
                            int woodOrderDistance = Math.max(
                                    Math.abs(woodOrderX - unit.tileX()),
                                    Math.abs(woodOrderY - unit.tileY()));
                            boolean freshFarCardinalWoodRouteHold =
                                    unit.pathLength() > 1
                                    && unit.battleNetCollisionCounter() == 0
                                    && unit.battleNetRefusals() == 0
                                    && Direction.isDiagonal(
                                            unit.lastStepHeading())
                                    && !Direction.isDiagonal(heading)
                                    && woodOrderDistance >= 3
                                    && woodBlocker.isMoving()
                                    && !walkedThisCycle;
                            if (freshFarCardinalWoodRouteHold) {
                                // A fresh terrain route can be planned through
                                // a moving ally, then meet that body at the
                                // execution probe after its preceding diagonal
                                // residual settles. This first refusal keeps the
                                // progressive cardinal tail and owns a complete
                                // Move 15..1 band. XHuman 12 peon 1386 retains
                                // E,E,NE,NE,E,NE,N,N,N at (10,88) on fixture
                                // 316, raises native unit+0x1d from zero to one,
                                // and takes the same E only on fixture 331.
                                // Parking the route cold-redrew N,NE instead.
                                unit.setBattleNetCollisionCounter(1);
                                unit.setBattleNetRefusals(0);
                                unit.setRouteSpent(false);
                                unit.setWaitCycles(0);
                                unit.setBattleNetOrderDelay(14);
                                unit.setBattleNetRefusalHold(true);
                                int moveStart = world.idle
                                        .battleNetSequenceStart(unit,
                                                BattleNetSequence.MOVE_ANIMATION);
                                if (moveStart >= 0) {
                                    unit.setBattleNetSequenceOffset(moveStart);
                                    unit.setBattleNetAnimationTimer(15);
                                }
                                return;
                            }
                            boolean saturatedWoodCornerLadder = unit
                                    .battleNetSaturatedWoodCornerLadder()
                                    && unit.pathLength() >= 4
                                    && unit.battleNetCollisionCounter() >= 1
                                    && unit.battleNetRefusals() == 0
                                    && Direction.isDiagonal(heading)
                                    && !Direction.isDiagonal(
                                            unit.lastStepHeading())
                                    && !walkedThisCycle;
                            if (saturatedWoodCornerLadder) {
                                int parkedHeading = unit
                                        .battleNetWoodCornerRefusalHeading();
                                int parkedVisits = parkedHeading == heading
                                        ? unit.battleNetWoodCornerRefusalVisits()
                                        : 0;
                                parkedVisits++;
                                unit.setBattleNetWoodCornerRefusalHeading(
                                        heading);
                                unit.setBattleNetWoodCornerRefusalVisits(
                                        parkedVisits);
                                int collision =
                                        unit.battleNetCollisionCounter() + 1;
                                unit.setBattleNetCollisionCounter(
                                        collision > 14 ? 0 : collision);
                                unit.setRouteSpent(false);
                                unit.setWaitCycles(0);
                                unit.setBattleNetOrderDelay(0);
                                if (parkedVisits >= 4) {
                                    // Four visits retain the newly written
                                    // SW,SE,E,E bytes behind native RI 20.
                                    // Collision five releases them for a
                                    // hard-corner redraw on the next visit.
                                    unit.clearPath();
                                }
                                return;
                            }
                            boolean parkedWoodCorner = unit.pathLength() == 1
                                    && unit.battleNetCollisionCounter()
                                            == unit.battleNetWoodCornerRefusalVisits()
                                    && unit.battleNetRefusals() > 0
                                    && Direction.isDiagonal(heading)
                                    && !walkedThisCycle;
                            if (parkedWoodCorner) {
                                int parkedHeading = unit
                                        .battleNetWoodCornerRefusalHeading();
                                int parkedVisits = parkedHeading == heading
                                        ? unit.battleNetWoodCornerRefusalVisits()
                                        : 0;
                                unit.setBattleNetWoodCornerRefusalHeading(
                                        heading);
                                parkedVisits++;
                                unit.setBattleNetWoodCornerRefusalVisits(
                                        parkedVisits);
                                int collision =
                                        unit.battleNetCollisionCounter() + 1;
                                unit.setBattleNetCollisionCounter(
                                        collision > 14 ? 0 : collision);
                                unit.setRouteSpent(false);
                                unit.setWaitCycles(0);
                                unit.setBattleNetOrderDelay(0);
                                if (parkedVisits >= 3) {
                                    // Retail leaves the stale bytes behind its
                                    // route-index-20 cursor for three Move/1
                                    // visits while the packed collision nibble
                                    // advances one, two, three. The following
                                    // resource callback redraws with this
                                    // occupied face restored to the pathfinder
                                    // and may first-step the replacement route
                                    // immediately.
                                    unit.clearPath();
                                }
                                return;
                            }
                            boolean terminalWoodResidual =
                                    unit.pathLength() == 1
                                    && unit.battleNetCollisionCounter() > 0
                                    && woodOrderDistance <= 2;
                            if (terminalWoodResidual) {
                                if (Direction.isDiagonal(heading)) {
                                    // A diagonal terminal byte is still a
                                    // path collision, not the resource
                                    // action's construction handoff. Native
                                    // XHuman 11 slot 1588 settles its sixth
                                    // SE at fixture 210, refuses the final SE
                                    // against a worker, raises collision one
                                    // to two and parks RI20. The next resource
                                    // visit redraws E,SE and commits E. The
                                    // cardinal XHuman 12 slot 1364 contrast
                                    // below instead rewrites its stored wall
                                    // face and pays 2657/3,2,1.
                                    int collision = unit
                                            .battleNetCollisionCounter() + 1;
                                    unit.setBattleNetCollisionCounter(
                                            collision > 14 ? 0 : collision);
                                    unit.clearPath();
                                    unit.setRouteSpent(false);
                                    unit.setWaitCycles(0);
                                    unit.setBattleNetOrderDelay(0);
                                    return;
                                }
                                // A refused final terrain-harvest byte near
                                // the stored order point belongs to the
                                // resource action, not another empty-route
                                // wait. Park the route and preserve its wall
                                // face for the action's 3,2,1 construction.
                                int collision =
                                        unit.battleNetCollisionCounter() + 1;
                                unit.setBattleNetCollisionCounter(
                                        collision > 14 ? 0 : collision);
                                unit.clearPath();
                                unit.setRouteSpent(false);
                                unit.setBattleNetWoodTerminalRefusalHeading(
                                        heading);
                                unit.setBattleNetWoodOrder(
                                        unit.tileX()
                                                + Direction.deltaX(heading) * 2,
                                        unit.tileY()
                                                + Direction.deltaY(heading) * 2);
                                unit.setWaitCycles(0);
                                unit.setBattleNetOrderDelay(0);
                                int moveStart = world.idle
                                        .battleNetSequenceStart(unit,
                                                BattleNetSequence.MOVE_ANIMATION);
                                if (moveStart >= 0) {
                                    unit.setBattleNetSequenceOffset(moveStart);
                                    unit.setBattleNetAnimationTimer(1);
                                }
                                return;
                            }
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
                        if (unit.battleNetGoldCardinalTailRefusal()) {
                            // This is one continuous FUN_004379e0 generation,
                            // even though the resource path writer lays a new
                            // one-byte route between visits.  Native XHuman 10
                            // slot 1438 advances collision/refusal 1..8 without
                            // entering the near-approach six-count free-window
                            // seam; refusal eight alone leaves timer fifteen.
                            int generation = battleNetRefuse(unit);
                            unit.setBattleNetCollisionCounter(counter);
                            unit.setBattleNetOrderDelay(0);
                            if (generation >= 15) {
                                unit.setBattleNetGoldCardinalTailRefusal(false);
                            }
                            return;
                        }
                        // A gold route which arrived at this residual with a
                        // refusal already banked parks immediately on its
                        // second allied refusal. XHuman 4 peon 1567 finishes
                        // E at 118,14 with coll 1 and cached E,NE,E; peon 1573
                        // still occupies the next E cell. Native raises coll
                        // to 2 and writes route_index 20 on fixture 55, then
                        // replans NE and commits it on 56. Keeping the cached
                        // E until the ally vacated lost that diagonal. The
                        // Orc 12 coll-band witness enters its residual with
                        // coll 0; after arming below, its later refusals have
                        // walkedThisCycle false and continue to count to 8.
                        if (walkedThisCycle && unit.stepDrained()
                                && counter > 1
                                && unit.pathLength() >= 3
                                && world.battleNetCooperativeBlocker(
                                        unit, allyBlocker)) {
                            unit.clearPath();
                            unit.setRouteSpent(false);
                            unit.setBattleNetFarMultiStepResidualRefuse(false);
                            unit.setBattleNetOrderDelay(0);
                            return;
                        }
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
                    if (allyHard && unit.target().type() != null
                            && unit.target().type().building()) {
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
                    // Residual-settled pathn1 against a standing ally while
                    // chasing a mobile quarry is always a cursor park. A free
                    // compass component is authoritative only for a static
                    // building assault, where it keeps closing on the same
                    // footprint (grunt 1375 SE). Against a moving quarry the
                    // target scan owns the next face: XHuman 12 grunt 1496
                    // parks SW at fixture 106 and redraws NE at 107. Reusing
                    // the building free-progress arm sent it W on the stale
                    // face instead. With no free building progress, the same
                    // RI20/replan rule remains the witness for grunt 1514.
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
                        if (postRetargetParkRefill) {
                            // This path was laid by the native NewPath callback
                            // which owns the just-settled residual's route park.
                            // Its occupied first byte is therefore refused now,
                            // not parked for another Java action and refused on
                            // the following one. XHuman 10 slot 1475 settles NW,
                            // writes the blocked SE byte and raises refusal one
                            // on fixture 182 in a single Move OP0 callback.
                            unit.setBattleNetRetargetResidualParkRefill(false);
                            unit.setBattleNetCollisionCounter(0);
                            int refusal = battleNetRefuse(unit);
                            unit.setRouteSpent(false);
                            unit.setWaitCycles(0);
                            unit.setBattleNetOrderDelay(
                                    refusal >= 8 ? 14 : 0);
                            int moveStart = world.idle
                                    .battleNetSequenceStart(unit,
                                            BattleNetSequence.MOVE_ANIMATION);
                            if (moveStart >= 0) {
                                unit.setBattleNetSequenceOffset(moveStart);
                                unit.setBattleNetAnimationTimer(
                                        refusal >= 8 ? 15 : 1);
                                unit.setBattleNetChaseStepReady(false);
                            }
                            unit.setBattleNetChaseEmptyRouteReplan(true);
                            return;
                        }
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
                        // RI20 parks advance the native collision generation
                        // for every refused residual, not only after the high
                        // nibble reaches four. XHuman 12 grunt 1504 is the
                        // sealed low-band witness: its raw byte is 0x10 at the
                        // fixture-194 park, then 0x20, 0x30 and 0x40 at the
                        // corresponding parks on 211, 228 and 245. Erasing
                        // generations one through three loses the wall face
                        // which opens NW on fixture 246. Java's separate hard
                        // refusal projection does not survive this cursor-park
                        // callback; the raw native generation is represented
                        // by the collision counter alone.
                        int parkedCollision =
                                unit.battleNetCollisionCounter();
                        unit.setBattleNetCollisionCounter(
                                parkedCollision >= 14
                                        ? 0 : parkedCollision + 1);
                        unit.setBattleNetRefusals(0);
                        unit.setBattleNetChaseEmptyRouteReplan(true);
                        unit.setBattleNetOrderDelay(0);
                        if (unit.battleNetAttackWrapDestArmPending()
                                && parkedCollision >= 5
                                && !Direction.isDiagonal(heading)) {
                            // The saturated last cardinal is the end of the
                            // completed Attack wrap, not another paid wall
                            // refill. This RI-20 visit hands the next callback
                            // to active-order Still, which clears pressure and
                            // opens Attack construction. XHuman 12 slot 1504
                            // parks S on fixture 279 and exposes Attack 3 on
                            // fixture 280.
                            unit.setBattleNetSaturatedResidualFaceRetry(true);
                            unit.setBattleNetResidualEmptyApproachIdlePending(
                                    true);
                            unit.setStepDrained(false);
                        }
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
                        if (walkedThisCycle) {
                            // NextPathElement discovers the occupied terminal
                            // square on the same callback that drains the
                            // previous stride. Native leaves Move visible for
                            // this checkpoint and promotes Still on the next
                            // HandleUnitAction visit.
                            unit.setBattleNetStopAfterLeftover(true);
                            return;
                        }
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
                        boolean coldBehaviorOneChaseReturn =
                                unit.battleNetAiBehavior() == 1
                                && unit.hasBattleNetAiHome()
                                && unit.orderTargetX()
                                        == unit.battleNetAiHomeX()
                                && unit.orderTargetY()
                                        == unit.battleNetAiHomeY()
                                && unit.battleNetChaseLegOpensCold()
                                && unit.battleNetAttackOp0OutOfRange()
                                && !unit.battleNetPlainMoveDirectLine();
                        if (coldBehaviorOneChaseReturn) {
                            // A cold Attack chase which has returned to its
                            // behavior-one home remains in the native packed
                            // collision ladder. Its occupied cached head parks
                            // at route index twenty; it does not take the free
                            // compass detour used by an ordinary occupancy-
                            // planned Move. Human 13 slot 1511 advances raw
                            // collision 0x10 -> 0x20 on fixture 280, then its
                            // replacement NW head owns 0x30 and Move 15.
                            battleNetRefuse(unit);
                            unit.setBattleNetRefusals(0);
                            int collision =
                                    unit.battleNetCollisionCounter() + 1;
                            unit.setBattleNetCollisionCounter(
                                    collision > 14 ? 0 : collision);
                            unit.setBattleNetPlainMoveRefusalReplacement(true);
                            unit.setRouteSpent(false);
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
                        if (unit.battleNetPlainMoveDirectLine()) {
                            // The terrain-only plain-Move writer deliberately
                            // leaves mobile bodies in later bytes. A refusal
                            // parks that buffer and redraws on the next visit;
                            // it must not enter the residual free-compass arm
                            // that belongs to an occupancy-planned detour.
                            battleNetRefuse(unit);
                            unit.setBattleNetPlainMoveRefusalReplacement(true);
                            unit.setRouteSpent(false);
                            unit.setWaitCycles(0);
                            unit.setBattleNetOrderDelay(0);
                            return;
                        }
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
                            // The rebuilt native route can contain only this
                            // heading when it lands beside an occupied point
                            // goal (ogre 1498: [NE,ff...] through fixture
                            // 132). Farther from the point, the surrogate tail
                            // remains parked after the detour (ogre 1527) and
                            // is replanned on the following visit.
                            int freeX = unit.tileX()
                                    + Direction.deltaX(freeHeading) * strideDetour;
                            int freeY = unit.tileY()
                                    + Direction.deltaY(freeHeading) * strideDetour;
                            if (battleNetTerminalOccupiedPointFrom(
                                    unit, freeX, freeY)) {
                                unit.clearPath();
                                unit.setPath(new PathFinder.Path(
                                        PathFinder.Result.FOUND,
                                        new int[] {freeHeading}));
                            } else {
                                unit.replacePeekHeading(freeHeading);
                            }
                            // This branch returns before consuming the
                            // approved heading. Arm the route park only when
                            // that heading is actually taken; arming it here
                            // would mistake the old residual for the detour
                            // and park one visit too soon.
                            unit.setBattleNetMoveFreeDetourPending(true);
                            unit.setBattleNetCollisionCounter(0);
                            unit.setWaitCycles(0);
                            return;
                        }
                    }
                }
                // Retail parks the route cursor on a hard refusal but leaves
                // cooperative handling to the moving-blocker arm above. A
                // laden land return therefore has three authenticated forms.
                // On the depot skirt it revisits the cached byte at Move/1
                // through refusal seven; refusal eight parks that byte while
                // buying the complete band (XHuman 8 peon 1575, fixtures
                // 228..250). Farther out, a clean moving blocker
                // retains the bytes under Move/15 (XHuman 10 peon 1588 at
                // fixture 270). A blocker already carrying a collision
                // generation is hard: peon 1584 parks S at fixture 290 under
                // Move/1, replans, and commits SE on fixture 291.
                PathFinder.Path ladenReturnRoute = null;
                Unit returnDepot = unit.returnDepotGoal();
                boolean ladenLandReturn =
                        world.battleNetMovementStride(unit) == 1
                        && unit.type().landUnit()
                        && unit.returningToDepot() && unit.carried() > 0
                        && returnDepot != null && unit.pathLength() > 0;
                if (ladenLandReturn && unit.distanceTo(returnDepot) > 2) {
                    boolean directReturnRay = unit.pathLength() == 1;
                    boolean saturatedFreshReturnRoute =
                            unit.battleNetRefusals() >= 8
                            && unit.battleNetPathStepsTaken() == 0;
                    Unit queuedReturnBlocker = world.blockerOnLayer(
                            unit, nextX, nextY);
                    boolean queuedReturnHead =
                            world.battleNetQueuedLandReturnBlocker(
                                    unit, returnDepot, queuedReturnBlocker);
                    int collision = unit.battleNetCollisionCounter() + 1;
                    int refusals = battleNetRefuse(unit);
                    if (refusals >= 15) {
                        unit.setBattleNetCollisionCounter(0);
                        unit.setBattleNetOrderDelay(0);
                        return;
                    }
                    unit.setBattleNetCollisionCounter(
                            collision > 14 ? 0 : collision);
                    unit.setRouteSpent(false);
                    unit.setWaitCycles(0);
                    // A standing body on a one-heading direct return ray, or
                    // a queued loaded sibling on the direct head, raises all
                    // eight native refusal generations and then the eighth
                    // owns the complete Move 15..1 band. XHuman 9 peon 1596
                    // proves the one-heading form. XHuman 12 peon 1550 has a
                    // longer cached route but reaches refusal ten against
                    // queued peon 1557 on (6,28); native pays one complete
                    // band and first-steps north exactly as that sibling
                    // vacates. Other multi-heading hard blockers still redraw.
                    boolean directReturnRefusalBand = refusals >= 8
                            && (directReturnRay || queuedReturnHead
                                    || saturatedFreshReturnRoute);
                    if (directReturnRefusalBand && directReturnRay
                            && unit.battleNetEmptyDepotDirectReturnRoute()) {
                        // FUN_004379e0 parks the logical cursor at index twenty,
                        // but does not erase the direct byte already stored in
                        // the route buffer. Orc 8 peasant 1494 keeps its south
                        // byte throughout Move 15..1 at fixtures 305..319 and
                        // consumes that byte on the timer-one wake at 320. A
                        // fresh occupancy-aware draw also begins south there,
                        // but its following south-west byte changes the third
                        // visible step at fixture 364. Keep the parked byte out
                        // of Java's live path until the resource action wakes.
                        unit.setBattleNetParkedRefusalHeading(heading);
                    }
                    unit.setBattleNetOrderDelay(
                            directReturnRefusalBand ? 14 : 0);
                    unit.setBattleNetRefusalHold(false);
                    int moveStart = world.idle.battleNetSequenceStart(unit,
                            BattleNetSequence.MOVE_ANIMATION);
                    if (moveStart >= 0) {
                        unit.setBattleNetSequenceOffset(moveStart);
                        unit.setBattleNetAnimationTimer(
                                directReturnRefusalBand ? 15 : 1);
                        unit.setBattleNetChaseStepReady(false);
                    }
                    return;
                }
                if (ladenLandReturn && unit.distanceTo(returnDepot) <= 2) {
                    int remaining = unit.pathLength();
                    boolean consumedDuplicateCardinalTail =
                            consumedDuplicateCardinalTail(unit, remaining);
                    if (!consumedDuplicateCardinalTail) {
                        int[] headings = new int[remaining];
                        for (int depth = 0; depth < remaining; depth++) {
                            headings[remaining - 1 - depth] =
                                    unit.peekHeadingAtDepth(depth);
                        }
                        ladenReturnRoute = new PathFinder.Path(
                                PathFinder.Result.FOUND, headings);
                    }
                    // A consumed N,N or S,S depot tail is stale once its head
                    // refuses. Retail parks it and lets the next resource
                    // visit redraw the two-diagonal bypass: independent peons
                    // in XHuman 11 and XHuman 5 take NE,NW and SW,SE. Restoring
                    // the old tail makes Java retry after the blocker has
                    // vacated and miss the native diagonal. Fresh/direct
                    // two-byte routes, one-byte depot rays, and nonduplicate
                    // tails retain the established refusal ladder only below
                    // its saturated route-park boundary.
                }
                if (paidAttackTailGenerationPark(unit)) {
                    parkPaidAttackTailGeneration(unit, heading);
                    return;
                }
                int refusals = battleNetRefuse(unit);
                // FUN_004379e0 increments the packed high nibble before
                // comparing it with 0x8000 at 0x00437ab4. Generations one
                // through seven may therefore revisit this cached depot-skirt
                // route, but generation eight and above leave route index 20
                // for the wake to redraw. Orc 12 peon 1507 reaches nine with
                // a stale S tail at fixture 327 and redraws SE,E on 342.
                if (ladenReturnRoute != null && refusals < 8) {
                    unit.setPath(ladenReturnRoute);
                }
                return;
                }
            }

            // The way was clear, so the patience resets: upstream's
            // "if (result != PF_WAIT) output.Fast = 0".
            unit.setPathWaitBudget(0);
            // FUN_004379e0's collision nibble survives the complete refusal
            // band and its timer-one wake.  A successful probe clears an
            // ordinary paid collision, but not the saturated formation band:
            // XHuman 10 slot 1486 is native 0x30 -> 0x00 on fixture 53, while
            // XHuman 12 slot 1496 steps SE with 0x40 still installed on
            // fixture 90 and later parks at 0x50.  Keeping that saturated
            // provenance makes the departing unit remain solid to the other
            // members of its crowded attack formation. A first-generation
            // cached route whose quarry also became its live attack-back
            // offer retains the same ownership: XHuman 12 slot 1512 is hit
            // while waiting on its twelve-byte knight route, commits NE when
            // the head opens, and remains raw 0x10. A non-offered twenty-byte
            // wake in the same fixture clears normally, as does XHuman 10's
            // collision-two paid wake.
            if (unit.battleNetRefusalHold()) {
                boolean offeredCollisionOneWake =
                        unit.battleNetCollisionCounter() == 1
                        && unit.target() != null
                        && unit.battleNetRouteOffer() == unit.target();
                boolean retainedGenerationThreePaidWake =
                        unit.battleNetCollisionCounter() == 3
                        && unit.battleNetRefusals() == 1
                        && unit.battleNetRetargetResidualParkSteps() == 4
                        && unit.battleNetPathInitialLength()
                                == BattleNetPathFinder.MAX_PATH - 1
                        // The accepted byte is popped immediately below, so
                        // this gate observes the paid route at cursor zero.
                        && unit.battleNetPathStepsTaken() == 0
                        && unit.target() != null
                        && unit.target().type() != null
                        && !unit.target().type().building()
                        && !World.battleNetRangedChaseUnit(unit);
                if (!offeredCollisionOneWake
                        && !retainedGenerationThreePaidWake
                        && unit.battleNetCollisionCounter() < 4) {
                    unit.setBattleNetCollisionCounter(0);
                }
                if (retainedGenerationThreePaidWake) {
                    unit.setBattleNetFourStepPaidCollisionRefill(true);
                }
                unit.setBattleNetRefusals(0);
                unit.setBattleNetRefusalHold(false);
            }
            boolean fourStepPaidCollisionFourRefill =
                    unit.battleNetFourStepPaidCollisionRefill()
                    && unit.battleNetCollisionCounter() == 4
                    && unit.battleNetRefusals() <= 1
                    && unit.target() != null
                    && unit.target().type() != null
                    && !unit.target().type().building()
                    && !World.battleNetRangedChaseUnit(unit);
            if (fourStepPaidCollisionFourRefill) {
                // The generation participates in the already-paid wall draw,
                // then NewPath clears it as the replacement byte commits.
                // XHuman 12 slot 1496 is raw 0x40 at RI20 on fixture 303
                // and 0x00 after its replacement N step on fixture 304.
                unit.setBattleNetCollisionCounter(0);
                unit.setBattleNetRefusals(0);
                unit.setBattleNetFourStepPaidCollisionRefill(false);
            }
            if (unit.battleNetSaturatedNearRecoveryFullRoute()
                    && !unit.battleNetDirectRefusalRecoveryProbe()
                    && unit.battleNetAttackRefusalRecoveryStage() == 6) {
                // The full wall route written after the one permitted direct
                // retry starts a fresh collision generation as soon as its
                // first byte commits. Keep only the route provenance; stale
                // refusal counters would make the accepted mover remain solid
                // to its own formation and recreate the jam downstream.
                unit.setBattleNetCollisionCounter(0);
                unit.setBattleNetRefusals(0);
            }
            stepped = true;
            unit.popHeading();
            // Approach damage belongs to the short arrival envelope on which
            // it was taken, not every later element in a long cached route.
            // Keep the marker through the final heading and one retained tail:
            // XHuman 10 grunt 1477 is hit while settling, takes its one-step
            // replacement, and native still owns the Attack-OP0 body hold.
            // Two or more surviving headings prove the unit has advanced into
            // a later route generation and clear it: XHuman 12 slot 1509 is
            // hit on fixture 20 with eight headings still ahead, then attacks
            // the guard tower normally on fixture 177. Carrying that old hit
            // through all eight legs parked Java at Attack start for 23 cycles
            // and made a visibly engaged grunt never land its blow.
            if (unit.battleNetAttackOp0Damaged()
                    && unit.pathLength() > 1) {
                unit.setBattleNetAttackOp0Damaged(false);
            }
            if (unit.battleNetMoveFreeDetourPending()) {
                unit.setBattleNetMoveFreeDetourPending(false);
                unit.setBattleNetNearlyFullFreeDetour(true);
            }
            int priorX = unit.tileX();
            int priorY = unit.tileY();
            world.markOccupancy(unit, false);
            world.markSight(unit, false);
            unit.setTile(nextX, nextY);
            world.markOccupancy(unit, true);
            world.unitCountSeen(unit);
            world.markSight(unit, true);
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
            unit.setLastStepHeading(heading);
            unit.turnTo(heading);
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
            // quiet calls later, not a second immediate step. The same
            // script.bin body owns the residual pixel pace: XHuman 9
            // skeleton 1431 reaches the logical tile on fixture 26 but
            // drains 32 borrowed pixels through fixture 46 before Attack
            // takes ownership and lands its first blow on fixture 55.
            // ChonkCraft's presentation script drained that debt on fixture
            // 41 and made the blow five cycles early. The type-level native
            // pace arm below carries those pixels; the action cursor here
            // continues to own OP0 decisions.
            if (world.battleNetSequence != null && unit.chasing()) {
                world.combat.armBattleNetChaseMoveBody(unit);
            }
            // Residual pixel pace for 2x2 movers, critters, and slow siege:
            // script.bin Move waits, not ChonkCraft Move. XOrc 8 submarine
            // 1433 double-stepped 102,88→100,86 at fixture 44 while retail
            // held residual two for one more cycle and stepped at 45 --
            // ChonkCraft submarine Move has irregular wait-1 stretches that
            // skip two native holds. Catapult/ballista Move opens
            // "if-var R >= 60 turn" and used to freeze leftover -32 for
            // thirty cycles; native Orc 8 1576 dest-arms 2px/2cycles from
            // fixture 13 with no turn stall.
            if (world.battleNetSequence != null
                    && usesBattleNetMovePace(unit)) {
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
        if (world.combat.openBattleNetRetainedDyingRangedConstruction(unit)) {
            return;
        }
        if (finishLeftoverReplacement(unit)) {
            return;
        }

        // And not on the cycle the step was taken. Upstream ends a walk on
        // the answer NextPathElement gives, and the call that takes a step
        // answers PF_MOVE; PF_REACHED can only come from a later one. A unit
        // whose animation carries it a whole tile in a single cycle would
        // otherwise arrive and finish in the same breath.
        if (mayDecide && !walkedThisCycle && !stepped
                && !unit.isMoving() && unit.pathLength() == 0) {
            if (finishLeftoverReplacement(unit)) {
                return;
            }
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

    static boolean consumedDuplicateCardinalTail(Unit unit, int remaining) {
        if (remaining != 2
                || unit.battleNetPathInitialLength() <= remaining
                || unit.battleNetPathStepsTaken() <= 0) {
            return false;
        }
        int head = unit.peekHeading();
        return !Direction.isDiagonal(head)
                && unit.peekHeadingAtDepth(1) == head;
    }

    /**
     * Preserves native's collision view when Java has visited an ally first.
     *
     * <p>Native Orc 5 peasant 1532 sees peasant 1529 on 32,101 while parking
     * its second gold residual at fixture 38. Java visits 1529 first and
     * commits its west step before 1532 asks, so {@link World#unitAt(int,
     * int)} already reports the cell free. A freshly committed step still
     * carries a full tile of drawing offset back to its old cell; treating
     * that old cell as occupied for this visit restores the native snapshot
     * without delaying genuinely free residuals.</p>
     */
    boolean battleNetWorkerAllyJustVacated(Unit mover, int x, int y) {
        for (Unit candidate : world.unitsSnapshot()) {
            if (candidate == mover || !candidate.isAlive()
                    || !candidate.isOnMap() || candidate.isDying()
                    || candidate.type() == null
                    || !world.isAllied(mover.player(), candidate.player())) {
                continue;
            }
            String ident = candidate.type().ident();
            if (ident == null || (!ident.contains("peon")
                    && !ident.contains("peasant"))) {
                continue;
            }
            int ox = candidate.offsetX();
            int oy = candidate.offsetY();
            if (Math.max(Math.abs(ox), Math.abs(oy)) != Unit.TILE_PIXELS) {
                continue;
            }
            int oldX = candidate.tileX() + Integer.signum(ox);
            int oldY = candidate.tileY() + Integer.signum(oy);
            if (oldX == x && oldY == y) {
                return true;
            }
        }
        return false;
    }

    /**
     * Generic form of the beginning-of-pass old-square projection.
     *
     * <p>A unit that has just committed a tile still carries one full tile of
     * drawing offset back to its previous square. Native's later point-route
     * query in the same unit pass continues to see that square as occupied.
     * This is separate from the worker-only resource-path witness above.</p>
     */
    boolean battleNetAllyJustVacated(Unit mover, int x, int y) {
        for (Unit candidate : world.unitsSnapshot()) {
            if (candidate == mover || !candidate.isAlive()
                    || !candidate.isOnMap() || candidate.isDying()
                    || candidate.type() == null
                    || !world.isAllied(mover.player(), candidate.player())) {
                continue;
            }
            int ox = candidate.offsetX();
            int oy = candidate.offsetY();
            // The first Move opcode may already have reduced a freshly
            // committed tile's drawing debt from 32 to 29 before a later slot
            // asks for its route. Native still indexes the old square on that
            // pass (XHuman 12 grunt 1363 before ogre 1356 at fixture 204).
            if (Math.max(Math.abs(ox), Math.abs(oy))
                    < Unit.TILE_PIXELS - 3) {
                continue;
            }
            int stride = world.battleNetMovementStride(candidate);
            int oldX = candidate.tileX() + Integer.signum(ox) * stride;
            int oldY = candidate.tileY() + Integer.signum(oy) * stride;
            if (oldX == x && oldY == y) {
                return true;
            }
        }
        return false;
    }


    /** Whether one passable compass step strictly improves BNE range. */
    boolean battleNetHasStrictlyCloserFreeNeighbour(
            Unit unit, Unit target) {
        if (unit == null || target == null || target.type() == null) {
            return false;
        }
        int current = world.battleNetDistance(unit, target);
        int stride = world.battleNetMovementStride(unit);
        int targetWidth = target.type().building()
                ? Math.max(1, target.type().tileWidth()) : 1;
        int targetHeight = target.type().building()
                ? Math.max(1, target.type().tileHeight()) : 1;
        for (int heading = 0; heading < Direction.COUNT; heading++) {
            int x = unit.tileX() + Direction.deltaX(heading) * stride;
            int y = unit.tileY() + Direction.deltaY(heading) * stride;
            if (!world.canEnter(unit, x, y)) {
                continue;
            }
            int targetX = World.battleNetNearFootprintCoordinate(
                    x, target.tileX(), targetWidth);
            int targetY = World.battleNetNearFootprintCoordinate(
                    y, target.tileY(), targetHeight);
            int distance = Math.max(Math.abs(targetX - x),
                    Math.abs(targetY - y));
            if (distance < current) {
                return true;
            }
        }
        return false;
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
     * Drains a committed pixel step that was orphaned on a Still order.
     *
     * <p>{@code COrder_Still} never owns {@code unit.Moving}; retail finishes
     * the step in {@code MoveToTarget} before the attack order can be
     * replaced.  Saves from older ChonkCraft builds nevertheless contain
     * this combination.  Advancing the Move presentation here brings the
     * sprite onto its logical tile instead of either teleporting it or
     * leaving it frozen at a permanent half-tile offset.</p>
     *
     * @return whether an orphaned step owned this visit
     */
    boolean settleOrphanedStillStep(Unit unit) {
        if (unit == null || unit.order() != Unit.Order.STILL
                || !unit.walkHolding()) {
            return false;
        }
        if (unit.offsetX() != 0 || unit.offsetY() != 0
                || unit.residualX() != 0 || unit.residualY() != 0) {
            walkPixels(unit);
        } else {
            unit.setWalkHolding(false);
        }
        if (!unit.walkHolding()) {
            resetDisplacement(unit);
            unit.clearPath();
            if (world.battleNetSequence != null && world.idle != null) {
                int stillStart = world.idle.battleNetStillSequenceStart(unit);
                if (stillStart >= 0) {
                    unit.setBattleNetSequenceOffset(stillStart);
                    unit.setBattleNetAnimationTimer(3);
                }
            }
        }
        return true;
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
     * ChonkCraft Move remains selected so {@code atMoveBoundary} keeps its
     * existing shape. Laden resource returns also draw the frame selected by
     * the native program; other pace users retain their established
     * presentation until their frames have their own retail proof.
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
     * Whether leftover dest-arm uses {@code script.bin} Move waits.
     *
     * <p>Double-step ships, repair strides, and critters already did. Slow
     * siege engines join them because ChonkCraft Move's turn branch is not
     * how retail dest-arms a committed leftover heading. Skeletons use it
     * because their ChonkCraft Move collapses the last ten diagonal pixels
     * into one visit while script.bin spends them over fixtures 41..46 in the
     * authenticated XHuman 9 chase.
     */
    boolean usesBattleNetMovePace(Unit unit) {
        if (unit == null || unit.type() == null) {
            return false;
        }
        boolean landPatrolAttackPace =
                unit.type().moveType() == UnitType.Movement.LAND
                && unit.battleNetAiBehavior() == 2
                && unit.pendingAttack() != null
                && unit.pendingAttackFrom() == Unit.Order.PATROL;
        return unit.battleNetDoubleStep()
                || unit.battleNetRepairStride()
                || unit.returningToDepot() && unit.carried() > 0
                // A fresh behavior-two land Patrol enters the retail Move
                // program before its first scan. If that OP0 banks direct
                // Attack, the program keeps owning residual pixels until the
                // pop. Orc 11 archers 1560 and 1559 prove the two phases.
                || unit.battleNetLandPatrolMoveBody()
                || landPatrolAttackPace
                || "unit-critter".equals(unit.type().ident())
                || "unit-skeleton".equals(unit.type().ident())
                || siegeUsesScriptBinMovePace(unit);
    }

    /**
     * Ballista and catapult are the only shipped types with a slow turn.
     * Their ChonkCraft Move asks {@code if-var R >= 60} and waits thirty
     * when it does. Retail leftover dest-arm does not.
     */
    boolean siegeUsesScriptBinMovePace(Unit unit) {
        if (unit == null || unit.type() == null) {
            return false;
        }
        int rotation = unit.type().rotationSpeed();
        return rotation > 0 && rotation < 128;
    }

    /**
     * Arms the retail Move body that paces residual drain for double-step
     * ships, land critters, and slow siege engines.
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
        if (open.frame() >= 0
                && unit.returningToDepot() && unit.carried() > 0) {
            unit.setFrame(open.frame());
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
     * sum to thirty-two for a one-tile step and keep the raw argument. A laden
     * return also keeps the native frame beside that pace: peasants and peons
     * walk through their loaded sheet while tankers' one-frame Move remains
     * still.
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
        if (tick.frame() >= 0
                && unit.returningToDepot() && unit.carried() > 0) {
            unit.setFrame(tick.frame());
        }
        int timer = tick.timer();
        if (tick.inclusiveMovementWait()
                && unit.returningToDepot() && unit.carried() > 0) {
            // Retail's laden peon return uses opcode 12 as an inclusive wait:
            // op5 3 / op12 2 ends at timer 3, then counts 2,1 before the next
            // movement beat. Other residual-pace users retain the ordinary
            // opcode timer used by their authenticated repair/ship/siege
            // journeys.
            timer = (timer + 1) & 0xff;
        }
        unit.setBattleNetMovePaceOffset(tick.offset());
        unit.setBattleNetMovePaceTimer(timer);
        int pixels = tick.pixels();
        if (pixels > 0 && unit.battleNetDoubleStep()) {
            // script.bin ships emit op13 1 for each residual beat; the doubled
            // table walks two pixels per beat (64px for a two-tile stride).
            pixels *= 2;
        }
        return pixels;
    }
}
