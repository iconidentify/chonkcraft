package net.chonkbase.chonkcraft.engine;

import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.animation.BattleNetSequence;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.MapField;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.missile.Missile;
import net.chonkbase.chonkcraft.engine.pathfinder.BattleNetPathFinder;
import net.chonkbase.chonkcraft.engine.pathfinder.PathFinder;
import net.chonkbase.chonkcraft.engine.unit.ResourceInfo;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;

/**
 * Gathering a resource and carrying it home.
 *
 * <p>Implements the retail BNE
 * paths beside it: the gold mine's approach square and its free prefix, the
 * wood claim that stops two peasants felling the same tree, and the oil
 * tanker's board seat beside a platform entry. This is where a worker's
 * whole round trip lives -- walk out, wait, take the load, walk back, bank
 * it -- so the cadence of that trip can be read in one place.
 */
final class BattleNetHarvestSystem {

    private final World world;

    BattleNetHarvestSystem(World world) {
        this.world = world;
    }


    /**
     * Sends a worker to gather.
     *
     * <p>The target is either a resource building, such as a gold mine, or a
     * terrain square, such as forest. Which one is decided by the resource's
     * own definition: wood is flagged {@code terrain-harvester} because it is
     * chopped out of the map rather than carried out of a building.
     *
     * @return whether the worker can gather there
     */
    boolean orderHarvest(Unit worker, int tileX, int tileY) {
        boolean liveContainedWorker = world.battleNetDepotReadyDispatching()
                && worker.hitPoints() > 0 && worker.order() != Unit.Order.DYING;
        if (!worker.type().canGather()
                || (!worker.isAlive() && !liveContainedWorker)) {
            return false;
        }
        // A command onto a unit inside an unbreakable stretch waits for the
        // stretch to end: the flush marks the old order finished, but the
        // pop -- with the whole of HandleUnitAction -- sits behind
        // "if (!unit.Anim.Unbreakable)", and the finished
        // order keeps executing. level12o's chopper, stolen for gold at
        // 157, swings on and takes its tree's wood once more at 170 before
        // it walks at 173 -- a port that re-ordered it on the spot left a
        // take out of the tree and marched fourteen cycles early.
        if (worker.animation().unbreakable() && worker.order() == Unit.Order.HARVEST) {
            worker.setPendingHarvest(tileX, tileY);
            return true;
        }
        worker.setPendingHarvest(-1, -1);
        // Queued like every other command, so the worker is counted as doing
        // what it was doing until the order is popped on the next cycle. This
        // one is always given from outside the worker's own step -- by the
        // AI's thought or by a player's click -- and on
        // maps/skirmish/(2)2-players it is the AI's first act of the game, so
        // it decides what that peasant is reported as doing on cycle 13.
        Unit.Order beforeHarvest = worker.order();
        Unit resourceBuilding = world.unitAt(tileX, tileY);
        if (resourceBuilding != null && resourceBuilding != worker) {
            for (ResourceInfo info : worker.type().gathering().values()) {
                if (!info.terrainHarvester() && providesResource(resourceBuilding, info.resource())) {
                    if (world.battleNetDepotReadyDispatching()) {
                        return queueDepotHarvest(worker, info, resourceBuilding,
                                tileX, tileY);
                    }
                    if (beginHarvest(worker, info, resourceBuilding, tileX, tileY)) {
                        worker.rememberActionBeforeQueued(beforeHarvest);
                        return true;
                    }
                    return false;
                }
            }
        }
        MapField field = world.map.fieldOrNull(tileX, tileY);
        if (field != null && field.isForest()) {
            ResourceInfo wood = worker.type().gathering().get(UnitType.Resource.WOOD);
            if (wood != null) {
                if (world.battleNetDepotReadyDispatching()) {
                    return queueDepotHarvest(worker, wood, null, tileX, tileY);
                }
                if (beginHarvest(worker, wood, null, tileX, tileY)) {
                    worker.rememberActionBeforeQueued(beforeHarvest);
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    /**
     * Applies an already-authored action-23 wire command.
     *
     * <p>The command kind records what the original click meant. Retail
     * dest-spreads its coordinates only after choosing Harvest, so a compact
     * formation can legitimately send action 23 to bare ground beside the
     * clicked trees. MoveToResource_Terrain then finds the forest around that
     * point. Revalidating the spread coordinate as though it were a new click
     * rejected two of XHuman 2's five authenticated group wires.</p>
     */
    boolean orderHarvestCommand(Unit worker, int tileX, int tileY) {
        if (worker == null || worker.type() == null) {
            return false;
        }
        Unit.Order beforeHarvest = worker.order();
        if (orderHarvest(worker, tileX, tileY)) {
            return true;
        }
        if (!worker.type().canGather() || !worker.isAlive()
                || world.map.fieldOrNull(tileX, tileY) == null) {
            return false;
        }
        ResourceInfo wood = worker.type().gathering().get(UnitType.Resource.WOOD);
        if (wood == null) {
            return false;
        }
        if (world.battleNetDepotReadyDispatching()) {
            return queueDepotHarvest(worker, wood, null, tileX, tileY);
        }
        if (!beginHarvest(worker, wood, null, tileX, tileY)) {
            return false;
        }
        worker.rememberActionBeforeQueued(beforeHarvest);
        return true;
    }


    /**
     * Sends a worker to a resource unit already selected by the AI finder.
     *
     * <p>The finder returns the unit pointer, not merely a map square. That
     * distinction matters at crowded harbours: another ship can overlap an
     * oil platform's footprint, so resolving the platform's origin back
     * through {@link #unitAt(int, int)} can select the ship and discard a
     * perfectly valid tanker order. Player commands remain coordinate based;
     * this overload preserves the native AI's selected resource object.</p>
     */
    boolean orderHarvest(Unit worker, Unit resourceBuilding) {
        boolean liveContainedWorker = worker != null
                && world.battleNetDepotReadyDispatching()
                && worker.hitPoints() > 0 && worker.order() != Unit.Order.DYING;
        if (worker == null || resourceBuilding == null
                || !worker.type().canGather()
                || (!worker.isAlive() && !liveContainedWorker)
                || !resourceBuilding.isAlive() || !resourceBuilding.isOnMap()) {
            return false;
        }
        Unit.Order beforeHarvest = worker.order();
        for (ResourceInfo info : worker.type().gathering().values()) {
            if (!info.terrainHarvester()
                    && providesResource(resourceBuilding, info.resource())) {
                if (world.battleNetDepotReadyDispatching()) {
                    return queueDepotHarvest(worker, info, resourceBuilding,
                            resourceBuilding.tileX(), resourceBuilding.tileY());
                }
                if (beginHarvest(worker, info, resourceBuilding,
                        resourceBuilding.tileX(), resourceBuilding.tileY())) {
                    worker.rememberActionBeforeQueued(beforeHarvest);
                    return true;
                }
                return false;
            }
        }
        return false;
    }


    /** Installs a depot-ready resource job behind native's timed Still head. */
    private boolean queueDepotHarvest(Unit worker, ResourceInfo info,
            Unit building, int tileX, int tileY) {
        if (!beginHarvest(worker, info, building, tileX, tileY)) {
            return false;
        }
        worker.setOrder(Unit.Order.STILL);
        worker.setActionBeforeQueued(null);
        worker.enqueueOrder(new Unit.QueuedOrder(
                Unit.QueuedOrderKind.HARVEST, tileX, tileY,
                building, null, null));
        worker.setQueuedReplacementPending(true);
        return true;
    }


    /**
     * Whether a worker could gather from a square.
     *
     * <p>Asked before an order is issued rather than after, because a
     * networked order is issued now and carried out several cycles later: the
     * click has to decide what it meant while the player can still see what
     * they clicked.
     */
    boolean canHarvestAt(Unit worker, int tileX, int tileY) {
        if (worker == null || worker.type() == null || !worker.type().canGather()) {
            return false;
        }
        Unit under = world.unitAt(tileX, tileY);
        if (under != null && under != worker) {
            for (ResourceInfo info : worker.type().gathering().values()) {
                if (!info.terrainHarvester() && providesResource(under, info.resource())) {
                    return true;
                }
            }
        }
        MapField field = world.map.fieldOrNull(tileX, tileY);
        return field != null && field.isForest()
                && worker.type().gathering().containsKey(UnitType.Resource.WOOD);
    }


    /**
     * Whether a building holds a given resource.
     *
     * <p>{@code GivesResource} and {@code CanHarvest} together, which is how
     * upstream asks it everywhere -- is
     * {@code mine.Type->BoolFlag[CANHARVEST_INDEX].value && mine.ResourcesHeld},
     * and pairs the two before it decides a right click
     * on a building means "go and gather". The pair is what separates the two
     * halves of the oil economy: an oil patch and an oil platform both declare
     * {@code GivesResource = "oil"} and only the platform declares
     * {@code CanHarvest}, so a tanker sent at a patch is being told to build
     * and one sent at a platform is being told to load.
     *
     * <p>This used to read the identifier -- {@code contains("gold-mine")} and
     * {@code contains("oil-platform")} -- which gives the right answer on the
     * four shipped sources and on nothing anybody else writes.
     */
    static boolean providesResource(Unit building, UnitType.Resource resource) {
        UnitType type = building.type();
        return type != null && type.canHarvest() && type.givesResource() == resource;
    }


    boolean beginHarvest(Unit worker, ResourceInfo info, Unit building, int tileX, int tileY) {
        worker.setBattleNetResourceHitRestoreIdle(false);
        worker.setGatherClockStarted(false);
        worker.setBattleNetWoodReadyPathRequired(false);
        worker.setBattleNetSaturatedWoodCornerLadder(false);
        worker.setBattleNetSaturatedWoodConstructionRoute(false);
        worker.setBattleNetSaturatedWoodConstructionRedraw(false);
        worker.setBattleNetSaturatedWoodClaimedReplacement(false);
        // Default off; gold free-prefix forest re-aim and range-one leftover
        // routes arm the walk claim themselves. A plain adjacent wood order
        // only draws at work 2660 (standing start).
        if (!(info != null && info.terrainHarvester() && building == null && worker.battleNetWoodWalkClaim())) {
            worker.setBattleNetWoodWalkClaim(false);
        }
        worker.setChopDone(false);
        // A fresh order starts at the bottom of its wait ladder:
        // ActionResourceInit's "this->Range = 0".
        worker.setResourceWaitLadder(0);
        worker.setResourceUnreachableTries(0);
        worker.setResourceMoveCycles(0);
        boolean unmatchedPaidCollisionOwner =
                worker.battleNetCollisionCounter()
                        > worker.battleNetRefusals();
        if (unmatchedPaidCollisionOwner) {
            // A fresh COrder_Resource retires the packed collision owner from
            // the failed order. Equal Java collision/refusal pairs still carry
            // route-construction provenance used by the replacement planner;
            // an unmatched paid owner does not. XHuman 12 peon 1386 reaches
            // UnitReady with collision/refusal 2/1 after its gold walk, while
            // native clears unit+0x1d from 0x20 to zero as the fixture-168 wood
            // order is constructed. Carrying that owner into the sixteen-byte
            // wood route made its occupied east head look like a stale
            // residual at fixture 316, discard the route, and redraw north.
            worker.setBattleNetCollisionCounter(0);
            worker.setBattleNetRefusals(0);
            worker.setBattleNetRefusalHold(false);
        }
        worker.setCarrying(info.resource());
        worker.setResourceUnit(building);
        worker.setResourceDepot(null);
        worker.setReturnDepotGoal(null);
        worker.setResourceTile(tileX, tileY);
        worker.setReturningToDepot(false);
        worker.setOrder(Unit.Order.HARVEST);
        // Finished belongs to the order being replaced, not to the unit.
        // A tanker which has just completed an on-top platform carries the
        // build order's finished latch into the player's immediate harvest
        // click. Native installs a fresh COrder_Resource with Finished clear;
        // retaining the old bit made the first resource visit cancel the new
        // command. An AI happened to reissue it, hiding the player-facing
        // failure.
        worker.setOrderFinished(false);
        worker.clearPath();
        // Orc 14 human transports leave construction Still into harvest before
        // their first AE30 draw. Mark the warmup draw consumed so the later
        // post-harvest Still re-arm does not invent two 0040AE30 draws at
        // world 21 (native order-32 Still never re-draws after fixture 6).
        if (worker.type() != null && worker.type().canTransport()) {
            worker.setBattleNetTransportFlyDrawn(true);
        }
        if (info.resource() == UnitType.Resource.OIL && building != null) {
            boolean adjacent = worker.distanceTo(building) <= 1;
            worker.setBattleNetOilStartedAdjacent(adjacent);
            worker.setBattleNetOilAction(adjacent
                    ? Unit.BattleNetOilAction.FINAL_APPROACH
                    : Unit.BattleNetOilAction.TO_RESOURCE);
            worker.setBattleNetOilActionTicks(adjacent ? 3 : 0);
        }
        return true;
    }


    /**
     * Advances a gathering worker.
     *
     * <p>The loop is: walk to the resource, stand there for the type's
     * {@code wait-at-resource} cycles while filling up, walk to the nearest
     * depot that accepts the load, stand there for {@code wait-at-depot},
     * bank it, and go back. Warcraft II's economy is that round trip, and its
     * timing is what makes the distance between a hall and its mine matter.
     */
    void stepHarvest(Unit worker) {
        stepHarvest(worker, false);
    }


    private void stepHarvest(Unit worker,
            boolean activeOrderIdleRandomAlreadyPaid) {
        ResourceInfo info = worker.type().gathering().get(worker.carrying());
        // Raw retail action 24 is the authoritative homeward oil state. Older
        // schema-2 saves persisted that action but not this navigation
        // projection, producing a full TO_DEPOT tanker that sailed back and
        // forth between its platform and shipyard.
        if (info != null && info.resource() == UnitType.Resource.OIL
                && worker.battleNetOilAction() == Unit.BattleNetOilAction.TO_DEPOT
                && worker.carried() > 0) {
            worker.setReturningToDepot(true);
            // Old saves may resume a wide tanker off BNE's absolute even
            // lattice. Clear the doubled bit once for this return leg; do not
            // dynamically flip it back mid-route when one coordinate happens
            // to become even.
            if (((worker.tileX() | worker.tileY()) & 1) != 0) {
                worker.setBattleNetDoubleStep(false);
            }
        }
        if (stepBattleNetWoodTerminalRefusal(worker, info)) {
            return;
        }
        if (worker.battleNetOrderDelay() > 0) {
            // Gold-mine approach soft-wait free-wake: XHuman 7 peon 1446
            // residual-settles at 110,105 with native route_index 20 while
            // ally 1447 occupies SW (109,106). Native steps SW on the first
            // visit where that cell is free at action time (fixture 45). A
            // blind fourteen-count slept until fixture 54. Free-wake clears
            // the delay and falls through so the step is taken this visit.
            // Only soft-waits of 6 or 14 (cooperative gold refuse bands) free-
            // wake; shorter harvest delays (2/3/5) must not.
            if (tryBattleNetGoldSoftWaitFreeWake(worker)) {
                // delay cleared; continue into harvest walk below
            } else {
                boolean parkCooperativeReturn = worker.battleNetRefusalHold()
                        && worker.returningToDepot() && worker.carried() > 0;
                boolean parkSaturatedWoodRoute =
                        worker.battleNetRefusalHold()
                        && info != null && info.terrainHarvester()
                        && worker.resourceUnit() == null
                        && worker.pathLength() > 0;
                boolean saturatedWoodConstructionBand =
                        worker.battleNetSaturatedWoodConstructionRoute()
                        && info != null && info.terrainHarvester()
                        && worker.resourceUnit() == null
                        && worker.pathLength() == 0
                        && worker.battleNetCollisionCounter() >= 8;
                int left = worker.battleNetOrderDelay() - 1;
                worker.setBattleNetOrderDelay(left);
                boolean parkedReturnMoveTimerStillCounting = false;
                // A queued mine-exit Return Goods promotion starts action
                // 24 on the worker's three-call Still body.  Java projects
                // that action onto HARVEST after the promotion visit, but
                // the native cursor keeps counting 3,2,1 while the order's
                // two quiet constructor visits are served.  Human 8 peon
                // 1536 is Still-sequence 2595/timers 3,2,1 on fixtures
                // 280..282 before its first empty depot-route ask.
                if (isBattleNetEmptyDepotRouteIdleCursor(worker)
                        && worker.battleNetAnimationTimer() > 1) {
                    worker.setBattleNetAnimationTimer(
                            worker.battleNetAnimationTimer() - 1);
                }
                // A laden return counts the native Move program itself rather
                // than sleeping through a generic Still animation. XHuman 10
                // peon 1588 is 2600/15 on its fixture-270 cooperative refusal,
                // 2600/14 on 271, and reaches timer one on 284 before refusing
                // S again on 285. A refusalHold return counts the same program,
                // then parks its stale route when this delay expires (1498
                // below).
                if (worker.returningToDepot() && worker.carried() > 0
                        && worker.battleNetCollisionCounter() > 0
                        && (worker.pathLength() > 0
                                || worker.battleNetRefusals() >= 8)
                        && world.battleNetSequence != null) {
                    int moveStart = world.idle.battleNetSequenceStart(
                            worker, BattleNetSequence.MOVE_ANIMATION);
                    if (moveStart >= 0
                            && worker.battleNetSequenceOffset() == moveStart
                            && worker.battleNetAnimationTimer() > 1) {
                        worker.setBattleNetAnimationTimer(
                                worker.battleNetAnimationTimer() - 1);
                        parkedReturnMoveTimerStillCounting =
                                parkCooperativeReturn && left == 0;
                    }
                }
                if (parkedReturnMoveTimerStillCounting) {
                    // The logical delay cannot execute the route wake on the
                    // callback which merely changes Move timer two to one.
                    // Native exposes that timer-one state for the whole visit;
                    // the following action callback owns either the retained
                    // step or the route-index-twenty park. Human 14 peon 1539
                    // is still RI5/collision two at fixture 405, then parks the
                    // occupied south tail as RI20/collision three at 406. Free
                    // wake controls already enter this block at timer one and
                    // continue to act immediately (XHuman 7 / Orc 5).
                    worker.setBattleNetOrderDelay(1);
                    worker.setBattleNetRefusalHold(true);
                    return;
                }
                // A terrain resource whose exhausted route could not yet be
                // rebuilt stays in action 23's three-call construction body.
                // Keep the raw cursor in step with the same 3,2,1 cadence as
                // the order delay: XHuman 12 peon 1360 is 2657/3 on fixture
                // 197, 2657/2 on 198 and 2657/1 on 199 before its successful
                // retry on 200.
                if (info != null && info.terrainHarvester()
                        && worker.resourceUnit() == null
                        && worker.pathLength() == 0
                        && world.battleNetSequence != null) {
                    int gatherStart = world.idle.battleNetSequenceStart(
                            worker, BattleNetSequence.ATTACK_ANIMATION);
                    if (worker.battleNetSequenceOffset() == gatherStart
                            && worker.battleNetAnimationTimer() > 1) {
                        worker.setBattleNetAnimationTimer(
                                worker.battleNetAnimationTimer() - 1);
                    }
                }
                // A building-resource continuation popped from a depot-ready
                // Still head owns the same action-23 construction body.
                // Its route is still empty and the mine approach is not yet
                // underfoot; count 2657/3,2,1 while the two quiet visits run.
                if (info != null
                        && info.resource() == UnitType.Resource.GOLD
                        && worker.resourceUnit() != null
                        && !worker.returningToDepot()
                        && worker.pathLength() == 0
                        && !atBattleNetResourceApproach(
                                worker, worker.resourceUnit())
                        && world.battleNetSequence != null) {
                    int gatherStart = world.idle.battleNetSequenceStart(
                            worker, BattleNetSequence.ATTACK_ANIMATION);
                    if (worker.battleNetSequenceOffset() == gatherStart
                            && worker.battleNetAnimationTimer() > 1) {
                        worker.setBattleNetAnimationTimer(
                                worker.battleNetAnimationTimer() - 1);
                    }
                }
                // Retained terrain-wall refusals are not part of the empty
                // action-23 construction body above. They own Move 15..1
                // instead. XHuman 12 peon 1385 keeps SE,NE at collision four
                // through fixtures 235..249, while peon 1360 independently
                // keeps E,SE at collision one from fixture 264. Route shape,
                // not a particular collision generation, owns the cursor.
                if (parkSaturatedWoodRoute
                        && world.battleNetSequence != null) {
                    int moveStart = world.idle.battleNetSequenceStart(
                            worker, BattleNetSequence.MOVE_ANIMATION);
                    if (moveStart >= 0) {
                        // orderDelay is the number of quiet callbacks which
                        // remain after this one; the native timer displayed on
                        // the current callback is therefore exactly left + 1.
                        // Re-pin the cursor as timer two expires: the general
                        // sequence player otherwise advances it before this
                        // action owns native's final Move/1 visit.
                        worker.setBattleNetSequenceOffset(moveStart);
                        worker.setBattleNetAnimationTimer(left + 1);
                    }
                    if (left == 0) {
                        // setBattleNetOrderDelay(0) normally retires a refusal
                        // owner. This cached route still needs that provenance
                        // on its timer-one wake so the accepted saturated step
                        // keeps collision four.
                        worker.setBattleNetRefusalHold(true);
                    }
                }
                if (saturatedWoodConstructionBand
                        && world.battleNetSequence != null) {
                    int moveStart = world.idle.battleNetSequenceStart(
                            worker, BattleNetSequence.MOVE_ANIMATION);
                    if (moveStart >= 0) {
                        worker.setBattleNetSequenceOffset(moveStart);
                        worker.setBattleNetAnimationTimer(left + 1);
                    }
                    if (left == 0) {
                        // The paid timer-one callback retires the empty
                        // construction loop, but not its packed collision
                        // generation. XHuman 12 peon 1385 keeps collision
                        // eight while its next resource visit redraws and
                        // commits NE on fixture 307; the repeated cached NE
                        // advances that same generation to nine on 323.
                        worker.setBattleNetSaturatedWoodConstructionRoute(
                                false);
                        worker.setBattleNetSaturatedWoodConstructionRedraw(
                                true);
                    }
                }
                boolean resumeFreeCooperativeReturn = false;
                if (left == 0 && parkCooperativeReturn) {
                    int heading = worker.pathLength() > 0
                            ? worker.peekHeading() : -1;
                    int stride = world.battleNetMovementStride(worker);
                    int nextX = heading >= 0 && heading < Direction.COUNT
                            ? worker.tileX()
                                    + Direction.deltaX(heading) * stride
                            : worker.tileX();
                    int nextY = heading >= 0 && heading < Direction.COUNT
                            ? worker.tileY()
                                    + Direction.deltaY(heading) * stride
                            : worker.tileY();
                    Unit blocker = heading >= 0 && heading < Direction.COUNT
                            ? world.blockerOnLayer(worker, nextX, nextY)
                            : null;
                    boolean ladenConvoyStillBlocks = blocker != null
                            && blocker.returningToDepot()
                            && blocker.carried() > 0
                            && world.battleNetCooperativeBlocker(
                                    worker, blocker);
                    boolean cachedHeadNowFree = heading >= 0
                            && heading < Direction.COUNT
                            && world.canEnter(worker, nextX, nextY);
                    if (cachedHeadNowFree) {
                        // Timer one is itself the retry visit. When the cached
                        // return head has opened, native consumes it without
                        // parking route index twenty or advancing the collision
                        // generation: XHuman 7 peon 1451 keeps RI1/collision 1
                        // through fixture 285 and takes NE on 286; Orc 5
                        // peasant 1529 independently takes its retained SE on
                        // 289. Returning here made both one callback late.
                        resumeFreeCooperativeReturn = true;
                    } else {
                        int counter = worker.battleNetCollisionCounter() + 1;
                        worker.setBattleNetCollisionCounter(
                                counter > 14 ? 0 : counter);
                    }
                    if (!cachedHeadNowFree && ladenConvoyStillBlocks) {
                        // A paid return route retries the same cached byte
                        // when the clean moving convoy which earned its first
                        // refusal is still there at timer one. Independent
                        // Human 14 and XHuman 10 workers both restart Move
                        // 15; a vacated square and a collision-marked blocker
                        // retain the park-and-redraw behavior below.
                        worker.setBattleNetRefusals(0);
                        worker.setWaitCycles(0);
                        worker.setBattleNetOrderDelay(14);
                        worker.setBattleNetRefusalHold(true);
                        if (world.battleNetSequence != null) {
                            int moveStart = world.idle
                                    .battleNetSequenceStart(worker,
                                            BattleNetSequence.MOVE_ANIMATION);
                            if (moveStart >= 0) {
                                worker.setBattleNetSequenceOffset(moveStart);
                                worker.setBattleNetAnimationTimer(15);
                            }
                        }
                        return;
                    }
                    if (!cachedHeadNowFree) {
                        // A laden worker whose buffered heading is still
                        // refused when the fifteen-count expires parks the
                        // route cursor at 20 and lets the next action visit
                        // plan around that body. XHuman 8 peon 1498 holds NW
                        // through fixture 270, parks on 271, then replans and
                        // steps W on 272.
                        worker.clearPath();
                        worker.setRouteSpent(false);
                        worker.setBattleNetRefusalHold(false);
                    }
                }
                if (left > 0) {
                    return;
                }
                if (!resumeFreeCooperativeReturn) {
                    return;
                }
            }
        }
        int parkedReturnHeading = worker.battleNetParkedRefusalHeading();
        Unit parkedReturnDepot = worker.returnDepotGoal();
        if (worker.returningToDepot() && worker.carried() > 0
                && parkedReturnDepot != null
                && worker.distanceTo(parkedReturnDepot) > 2
                && worker.pathLength() == 0
                && worker.battleNetOrderDelay() == 0
                && worker.battleNetRefusals() >= 8
                && worker.battleNetEmptyDepotDirectReturnRoute()
                && parkedReturnHeading >= 0
                && parkedReturnHeading < Direction.COUNT) {
            // Java exposes native route-index twenty as an empty path. The
            // direct byte beneath that parked cursor is nevertheless the byte
            // NextPathElement consumes on the complete refusal band's wake.
            // Reconstitute it only on that action visit, after all fourteen
            // quiet callbacks, so neither pathfinding nor an earlier movement
            // visit can replace it. setPath normally clears a first-generation
            // collision for a one-byte route; this is generation eight, whose
            // separate collision projection must survive the reconstruction.
            int collision = worker.battleNetCollisionCounter();
            worker.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                    new int[] {parkedReturnHeading}));
            worker.setBattleNetCollisionCounter(collision);
            worker.setBattleNetParkedRefusalHeading(-1);
            worker.setBattleNetEmptyDepotDirectReturnRoute(false);
        }
        // An empty action-24 depot route does not become a synthetic Still
        // order and it does not poll the pathfinder every visit.  Its active
        // resource order owns the rest of the same three-call Still body;
        // only timer one asks for another route.  The restart and its one
        // land-idle draw are installed by beginBattleNetEmptyDepotRouteIdleBand
        // after the failed route construction below.
        if (isBattleNetEmptyDepotRouteIdleCursor(worker)
                && worker.battleNetAnimationTimer() > 1) {
            worker.setBattleNetAnimationTimer(
                    worker.battleNetAnimationTimer() - 1);
            return;
        }
        if (info == null && worker.type().canTransport() && worker.target() != null) {
            // Retail's startup transport action is in the resource family,
            // but its only job is to sail as close as the water permits to
            // the owner's nearest hall and then finish.
            world.stepBattleNetTransportToHall(worker, worker.target());
            return;
        }
        String resourceStateTrace = System.getenv("CHONKCRAFT_TRACE_RESOURCESTATE");
        if (resourceStateTrace != null
                && worker.id() == Integer.parseInt(resourceStateTrace)) {
            Unit tracedResource = worker.resourceUnit();
            Unit tracedDepot = worker.returnDepotGoal();
            System.err.printf("JRESOURCESTATE cycle=%d unit=%d returning=%d"
                            + " resource=%d alive=%d at=%d,%d tile=%d,%d depot=%d@%d,%d"
                            + " remembered=%d carried=%d worksite=%d finished=%d"
                            + " order_delay=%d wait=%d path=%d moving=%d stepping=%d"
                            + " spent=%d drained=%d collision=%d free_prefix=%d"
                            + " free_prefix_length=%d ready_wood_path=%d%n",
                    world.cycle, worker.id(), worker.returningToDepot() ? 1 : 0,
                    tracedResource == null ? -1 : tracedResource.id(),
                    tracedResource != null && tracedResource.isAlive() ? 1 : 0,
                    worker.resourceTileX(), worker.resourceTileY(),
                    worker.tileX(), worker.tileY(),
                    tracedDepot == null ? -1 : tracedDepot.id(),
                    tracedDepot == null ? -1 : tracedDepot.tileX(),
                    tracedDepot == null ? -1 : tracedDepot.tileY(),
                    worker.resourceDepot() == null ? -1 : worker.resourceDepot().id(),
                    worker.carried(), worker.worksite() == null ? -1 : worker.worksite().id(),
                    worker.orderFinished() ? 1 : 0, worker.battleNetOrderDelay(),
                    worker.waitCycles(), worker.pathLength(),
                    worker.isMoving() ? 1 : 0,
                    world.movement.isStepping(worker) ? 1 : 0,
                    worker.routeSpent() ? 1 : 0,
                    worker.stepDrained() ? 1 : 0,
                    worker.battleNetCollisionCounter(),
                    worker.battleNetGoldFreePrefix() ? 1 : 0,
                    worker.battleNetGoldFreePrefixLength(),
                    worker.battleNetWoodReadyPathRequired() ? 1 : 0);
        }
        if (info == null) {
            // GiveOrder 24 on a soldier still walks FindDeposit. Native
            // grunt 1592 enters the hall at 79; treating a missing gather
            // table as Still left it on 18,23.
            if (worker.returningToDepot()) {
                info = emptySendHomeGoldWait();
            } else {
                worker.setOrder(Unit.Order.STILL);
                return;
            }
        }

        // HandleUnitAction removes a finished COrder_Resource before its
        // state machine is consulted, whichever leg it finished on.
        if (worker.orderFinished()) {
            worker.setOrderFinished(false);
            worker.setResourceUnit(null);
            worker.setOrder(Unit.Order.STILL);
            world.idle.stepStill(worker);
            return;
        }

        if (worker.returningToDepot()) {
            // Standing inside the depot with the wait run out: come back out
            // on the face pointing at whatever it is going back to.
            if (worker.removed() && worker.worksite() != null) {
                leaveDepot(worker, info);
                return;
            }
            // StopGathering's FindDeposit result is the resource order's
            // weak goal for the entire return leg. Re-running FindDeposit
            // here every cycle is not merely expensive: each candidate
            // reachability probe is a full A* search and therefore clears
            // CostMoveToCache, shared by every later one-square shortcut.
            Unit depot = worker.returnDepotGoal();
            if (depot == null || !depot.isAlive() || depot.isDying()
                    || !world.targets.isVisibleAsGoal(worker.player(), depot)) {
                Unit previousDepot = depot;
                depot = bestDepotByTravel(worker, info.resource(), 1000);
                worker.setReturnDepotGoal(depot);
                if (depot != previousDepot) {
                    // The order's cached headings belong to its weak goal.
                    // If that refinery dies, retaining its tail makes the
                    // laden tanker keep sailing toward the ruins before it
                    // ever consults the replacement selected above.
                    worker.clearPath();
                    worker.setRouteSpent(false);
                    if (depot != null) {
                        // SpreadUnit's stored point belongs to that same weak
                        // goal.  A doubled tanker otherwise mistakes the dead
                        // refinery's point for an exceptional shoreline point
                        // of the replacement and completes the resource order
                        // at the ruins instead of routing to the live depot.
                        int[] entry = world.battleNetDepotEntryPoint(
                                worker, depot);
                        worker.setOrderTarget(entry[0], entry[1]);
                    }
                }
                if (worker.resourceDepot() == null) {
                    worker.setResourceDepot(depot);
                }
            }
            if (depot == null) {
                // Nowhere to unload: StopGathering marks COrder_Resource
                // Finished, but HandleUnitAction does not erase a freshly
                // finished current order until this unit's next turn.  Keep
                // the resource label for the rest of this cycle; level08h's
                // chopper 89 lets its final swing go during 1335 and still
                // traces Resource there, then becomes Still during 1336.
                if (info.terrainHarvester()) {
                    worker.setOrderFinished(true);
                } else {
                    // Indoor gathering's dropout is represented a cycle
                    // later in this state machine than upstream's
                    // StopGathering transition.  Advancing straight to Still
                    // here preserves the already-observed miner cadence (the
                    // levelx03h stranded peon becomes Still at 186).
                    worker.setOrder(Unit.Order.STILL);
                    world.idle.stepStill(worker);
                }
                return;
            }
            // A resource route ends when its cached anchor has reached the
            // depot's marked skirt. That is PF_REACHED to COrder_Resource,
            // not a movement collision. Land routes can still contain a
            // heading along that skirt: XOrc 12 peasant 1434 reaches (75,52)
            // beside the keep with N buffered, but native enters action 25
            // there instead of walking north. Clearing that leftover also
            // preserves the action-25/refusal cadence when the skirt entry is
            // temporarily occupied (XHuman 8 peon 1575 reaches (20,9) on
            // fixture 250, not three cycles late).
            //
            // The doubled naval pathfinder works on the ship's anchor grid,
            // so an odd-anchor tanker can instead retain a final heading into
            // the footprint after its first single-lattice correction. If it
            // is handed to generic movement, the solid depot refuses that
            // heading forever: the user-save witness sat at (46,15), beside
            // the shipyard at (43,16), retrying SW into (45,16) every fifteen
            // cycles. Consume both route boundaries here and preserve the
            // normal arrival staging before the load is banked.
            if (worker.distanceTo(depot) <= 1 && worker.pathLength() > 0
                    && !worker.battleNetResourceApproachStaged()) {
                int heading = worker.peekHeading();
                int stride = world.battleNetMovementStride(worker);
                int nextX = worker.tileX() + Direction.deltaX(heading) * stride;
                int nextY = worker.tileY() + Direction.deltaY(heading) * stride;
                int nextDistance = Unit.distanceBetween(worker.type(), nextX, nextY,
                        depot.type(), depot.tileX(), depot.tileY());
                boolean landRouteAlreadyReachedSkirt = stride == 1
                        && worker.type().landUnit()
                        && nextDistance <= 1;
                if (nextDistance == 0 || landRouteAlreadyReachedSkirt) {
                    worker.clearPath();
                    worker.setRouteSpent(true);
                    // Retail parks the cursor but leaves the heading bytes in
                    // the unit record. A worker behind this one asks
                    // 0x0044fa20 where the blocker is going; retaining the
                    // first parked heading lets that predicate recognize a
                    // cooperative mover instead of treating it as a standing
                    // wall (XHuman 8 peons 1501 and 1498 at fixture 256).
                    worker.setBattleNetSpentHeading(heading);
                }
            }
            int depotDistance = worker.distanceTo(depot);
            int movementStride = world.battleNetMovementStride(worker);
            boolean doubledDepotSkirtArrived =
                    worker.battleNetResourceApproachStaged()
                            && movementStride > 1
                            && depotDistance <= movementStride;
            if ((!doubledDepotSkirtArrived && depotDistance > 1)
                    || worker.pathLength() > 0) {
                // The walk runs its route out even once the doorstep is in
                // reach: upstream's MoveToDepot ends on the route's own
                // REACHED, not on a live distance check, so a tanker whose
                // ring tile lies a step further sails that step and serves
                // the spent route before it banks. level10h's tanker banked
                // at 774 from mid-route where upstream's docks after 790.
                boolean pathWait = world.movement.walkTowards(
                        worker, depot, activeOrderIdleRandomAlreadyPaid);
                // Every wait the walk answered climbs the shove ladder --
                // MoveToDepot's PF_WAIT arm.
                if (pathWait && worker.order() == Unit.Order.HARVEST
                        && worker.waitCycles() > 0) {
                    resourceWalkWaited(worker, depot.tileX(), depot.tileY(),
                            depot.type().tileWidth(), depot.type().tileHeight());
                }
                return;
            }
            // The walk is paid out before the arrival counts, at the hall
            // exactly as at the mine below: the move animation finishes, the
            // spent route serves its ten cycles, and only the wake after
            // that answers REACHED and banks. On levelx03h the peon beside
            // the hall at cycle 205 banks during 216 upstream, and this implementation
            // used to take its gold at 206 -- a hundred gold in the ledger
            // ten cycles before the other engine had it.
            if (world.movement.isStepping(worker) || worker.isMoving()) {
                world.movement.walkPixels(worker);
                if (world.movement.isStepping(worker) || worker.isMoving()) {
                    return;
                }
                // A laden return that settles on the depot ring enters action
                // 25 on this same visit. Orc 1 peon 1594 settles at fixture
                // 369, waits 3/2/1, and dest-arms at 372; deferring merely
                // because cargo remained advanced that whole state a cycle.
            }
            if (world.movement.depotRingAction25(worker, depot)) {
                // Empty leftover-land on the hall ring. Native stands
                // action 25 for this visit plus delay 2, then dest-arms
                // onto 0x41f430. PF_WAIT 10 entered Orc 1 at 65.
                worker.setRouteSpent(false);
                worker.setBattleNetOrderDelay(2);
                worker.setBattleNetResourceApproachStaged(true);
                worker.setBattleNetAnimationTimer(3);
                return;
            }
            // A doubled tanker is already at BNE's legal outer anchor when
            // the depot lies within one naval stride. Dest-arming it again
            // produces a heading into the refinery's land footprint; the
            // move is refused and this early return repeats forever, leaving
            // a full tanker visibly frozen beside home. Land workers still
            // need the ordinary one-tile arm onto 0x41f430.
            if (!doubledDepotSkirtArrived
                    && world.movement.depotRingDestArm(worker, depot)) {
                boolean pathWait = world.movement.walkTowards(
                        worker, depot, activeOrderIdleRandomAlreadyPaid);
                if (pathWait && worker.order() == Unit.Order.HARVEST
                        && worker.waitCycles() > 0) {
                    resourceWalkWaited(worker, depot.tileX(), depot.tileY(),
                            depot.type().tileWidth(), depot.type().tileHeight());
                }
                return;
            }
            if (!worker.battleNetResourceApproachStaged()
                    && world.movement.spendTheEmptyRoute(worker)) {
                // The route-end wait is a PF_WAIT like any other to
                // MoveToDepot's arm, and it climbs the same ladder.
                resourceWalkWaited(worker, depot.tileX(), depot.tileY(),
                        depot.type().tileWidth(), depot.type().tileHeight());
                return;
            }
            // Dest-arm leftover-land on 0x41f430 is PF_REACHED. Asking
            // confirm from inside the hall used to store an escape path
            // and leave Orc 1 standing on 25,22 after fixture 75.
            // Land action 25 dest-arms onto the footprint before arrival.
            // A doubled tanker instead enters from its marked outer anchor:
            // XHuman 8's tanker remains at (60,56) through action-25 cycles
            // 382..384, then banks into the refinery at (56,57) on 385 without
            // another visible tile step. MapDistanceBetweenTypes reports that
            // outer 2x2/3x3 footprint separation as two, one naval stride.
            boolean destArmArrived = worker.battleNetResourceApproachStaged()
                    && (depotDistance == 0 || doubledDepotSkirtArrived);
            if (!destArmArrived && !confirmResourceWalkArrival(worker, depot)) {
                return;
            }
            // At the depot. The path that brought it here ends inside the
            // building, which it can never stand in, so drop the route first.
            worker.clearPath();
            // MoveToDepot: the load is banked the
            // moment it arrives and the worker goes *inside* for the wait
            // rather than standing outside it. That is why a hall with four
            // peasants working shows none of them for most of the cycle, and
            // it is what lets the next leg start from the depot's own square.
            // What the load pays is the player's income for it, not its raw
            // size: "ChangeResource(rindex, (unit.ResourcesHeld *
            // player.Incomes[rindex]) / 100)".
            // On campaigns/human-exp/levelx03h the tanker's first hundred
            // oil lands at cycle 258 as 125 in upstream's bank -- the
            // refinery's ImproveProduction -- and this implementation banked the
            // hundred it carried.
            world.players[worker.player()].add(info.resource(),
                    worker.carried() * world.players[worker.player()].income(info.resource()) / 100);
            worker.setCarried(0);
            worker.setBattleNetResourceHitRestoreIdle(false);
            // Both halves of the load go: "unit.ResourcesHeld = 0;
            // unit.CurrentResource = 0", so
            // the next gathering start is a fresh one whatever it is for.
            worker.setHeldResource(null);
            // MoveToDepot clears its weak current goal as soon as it arrives.
            // COrder_Resource::Depot is a separate remembered pointer and
            // survives for StopGathering's indoor dropout fallback.
            worker.setReturnDepotGoal(null);
            enterResource(worker, depot);
            worker.setBattleNetResourceApproachStaged(false);
            // One less than the data's number here too: MoveToDepot docks
            // the freshly set wait immediately -- "if (unit.Wait)
            // unit.Wait--" -- so a
            // hundred-cycle bank visit is ninety-nine served waits and
            // ninety-nine chances to breathe, not a hundred of each.
            worker.setWaitCycles(Math.max(0, info.waitAtDepot() - 1));
            return;
        }

        Unit resource = worker.resourceUnit();
        if (resource != null && (!resource.isAlive() || resource.isDying())) {
            worker.setResourceUnit(null);
            worker.clearPath();
            if (worker.carried() > 0) {
                if (!pauseOilForReadyDispatch(worker, info)) {
                    beginReturnToDepot(worker, info);
                }
            } else if (!findAnotherResource(worker, info)) {
                worker.setOrder(Unit.Order.STILL);
            }
            return;
        }
        int targetX = resource != null ? resource.tileX() : worker.resourceTileX();
        int targetY = resource != null ? resource.tileY() : worker.resourceTileY();

        // Beside the resource, measured against the whole of it. A gold mine
        // is three squares by three and a worker that walks up to its southern
        // face is three squares from the corner the tile position names, so
        // comparing against that corner says "not there yet" for two of the
        // mine's four sides and sends the worker round the building looking
        // for a way in that it is already standing at.
        // Standing beside a tree is not the same as having walked to it: the
        // chop starts when the route answers its own REACHED, never on a live
        // adjacency test while steps are still in hand. On
        // campaigns/human/level12h three choppers share the corner tree at
        // 105,0 whose ring holds three squares; the third's last step is into
        // a square a stalled fellow already fills, so upstream's peon is
        // still officially walking -- NextPathElement's ten-cycle bump-wait
        // -- when its AI steals it for gold at cycle 727. This implementation's peon
        // declared itself arrived from the square before, chopped, and was
        // never stolen.
        // A mine and a platform hold the same rule as the tree: the walk owns
        // the arrival, and a route still in hand keeps walking. On
        // campaigns/orc/level08o the shoved tanker's fresh route back to the
        // platform runs three steps and touches the platform's ring on its
        // first, at 88,101 -- upstream sails on and steps again at 895 where
        // this implementation, reading the adjacency alone, dropped the route and
        // vanished into the platform.
        // Wood: free-prefix arrival or leftover heading into forest is
        // native range-one. Orc 7 peasant 1567 holds (40,8) after NW from
        // (41,9) and on fixture c24 re-aims to adjacent tree (40,7), clears
        // the route, and starts the first chop SyncRand. Wall-follow SE or
        // re-planning NW into forest left the seed at 41c67ea6.
        if (resource == null && info.terrainHarvester()
                && !worker.battleNetWoodReadyPathRequired()
                && !world.movement.isStepping(worker)
                && !worker.isMoving() && worker.battleNetOrderDelay() == 0) {
            int[] found = null;
            if (worker.pathLength() > 0) {
                int woodHeading = worker.peekHeading();
                int woodNextX = worker.tileX()
                        + Direction.deltaX(woodHeading);
                int woodNextY = worker.tileY()
                        + Direction.deltaY(woodHeading);
                MapField woodNext = world.map.fieldOrNull(woodNextX, woodNextY);
                if (woodNext != null && woodNext.isForest()) {
                    found = findAdjacentForest(worker.tileX(),
                            worker.tileY());
                }
            } else if (worker.pathLength() == 0
                    && Math.max(Math.abs(worker.tileX() - targetX),
                            Math.abs(worker.tileY() - targetY)) > 1) {
                found = findAdjacentForest(worker.tileX(), worker.tileY());
            }
            if (found != null) {
                worker.setResourceTile(found[0], found[1]);
                targetX = found[0];
                targetY = found[1];
                worker.clearPath();
                worker.setRouteSpent(false);
            }
        }
        // Wood range-one: on the chopping square even while the final leg's
        // pixels drain, and even with a leftover cached heading (XHuman 2
        // [NW,N] discards N; XHuman 9 peon 1550 must not spend a second N
        // from 109,24 onto 109,23). pathLength==0 alone was too strict under
        // cold-commit and let the leftover fire after the drain.
        boolean woodRangeOne = info.terrainHarvester()
                && resource == null
                && (!worker.battleNetWoodReadyPathRequired()
                        || worker.routeSpent())
                && Math.max(Math.abs(worker.tileX() - targetX),
                        Math.abs(worker.tileY() - targetY)) <= 1;
        // Gold on the approach square must also count as at-resource while
        // residual pixels of the staged action-25 step still drain. Requiring
        // !isMoving or !isStepping (Move animation still unbreakable under
        // cold-commit) sent XOrc 12 peasant 1396 back through walkTowards
        // once underfoot; empty-route PF_WAIT then left it outside the mine
        // at fixture 21 while native UNLOAD-removed into the footprint. The
        // directBattleNetGoldArrival arm already drains then enters -- it
        // just never ran when isMoving/isStepping blocked atResource.
        boolean goldApproachArrival = resource != null
                && info.resource() == UnitType.Resource.GOLD
                && atBattleNetResourceApproach(worker, resource)
                && worker.pathLength() == 0;
        boolean atResource = resource != null
                ? (goldApproachArrival
                        || (atBattleNetResourceApproach(worker, resource)
                        && worker.pathLength() == 0
                        && !world.movement.isStepping(worker) && !worker.isMoving()))
                : woodRangeOne
                        || (Math.max(Math.abs(worker.tileX() - targetX),
                                Math.abs(worker.tileY() - targetY)) <= 1
                        && (!worker.battleNetWoodReadyPathRequired()
                                || worker.routeSpent())
                        // Native NextPathElement rechecks the resource
                        // order's range before consuming another cached
                        // heading.  A terrain worker whose completed pixel
                        // step has put it beside the tree therefore receives
                        // PF_REACHED even when its cached route still has an
                        // extra direction.  XHuman 2 slot 1588 reaches
                        // (13,11) with route [NW,N]; native discards N and
                        // starts chopping, while consuming it moved Java to
                        // (13,10) and skipped the first work-swing RNG draw.
                        && worker.pathLength() == 0
                        && !world.movement.isStepping(worker) && !worker.isMoving());
        if (!atResource) {
            if (resource != null) {
                // Spent one-tile gold approach with a blocked pure-cardinal
                // face: stage action 25 toward the mine centre diagonal.
                // XHuman 12 peon 1550 sits at (5,27) with approach (5,28)
                // occupied by ally 1549; native promotes BOARD on c19 and
                // steps south-west onto (4,28). Only routeSpent empty paths
                // at Chebyshev 1 (not leftover pathLength==1 mid-walks) so
                // early Orc 12 / XHuman 7 gold rays stay clean.
                if (info.resource() == UnitType.Resource.GOLD && !worker.isMoving()
                        && !world.movement.isStepping(worker) && worker.battleNetOrderDelay() == 0
                        && worker.pathLength() == 0 && worker.routeSpent()) {
                    int[] approach = world.battleNetApproachPoint(worker, resource);
                    int chebyshev = Math.max(
                            Math.abs(approach[0] - worker.tileX()),
                            Math.abs(approach[1] - worker.tileY()));
                    if (chebyshev == 1) {
                        int dx = Integer.signum(approach[0] - worker.tileX());
                        int dy = Integer.signum(approach[1] - worker.tileY());
                        boolean pureCardinal = dx == 0 || dy == 0;
                        boolean approachBlocked = !world.map.isFootprintFree(
                                approach[0], approach[1], 1, 1,
                                worker.movementMask(), worker.blockingFlags());
                        if (pureCardinal && approachBlocked) {
                            int centerX = resource.tileX()
                                    + Math.max(1, resource.type().tileWidth())
                                    / 2;
                            int centerY = resource.tileY()
                                    + Math.max(1, resource.type().tileHeight())
                                    / 2;
                            int altDx = Integer.signum(
                                    centerX - worker.tileX());
                            int altDy = Integer.signum(
                                    centerY - worker.tileY());
                            if (altDx != 0 || altDy != 0) {
                                dx = altDx;
                                dy = altDy;
                            }
                        }
                        if (dx != 0 || dy != 0) {
                            worker.setRouteSpent(false);
                            worker.setPath(new PathFinder.Path(
                                    PathFinder.Result.FOUND,
                                    new int[] {Direction.fromDelta(dx, dy)}));
                            worker.setBattleNetResourceApproachRedirect(true);
                            worker.setBattleNetOrderDelay(2);
                            return;
                        }
                    }
                }
                // Gold action 23 → 25 for an inherited long-ray tail only.
                if (info.resource() == UnitType.Resource.GOLD
                        && worker.battleNetGoldLongApproach() && !worker.isMoving()
                        && !world.movement.isStepping(worker) && worker.pathLength() == 1
                        && worker.battleNetOrderDelay() == 0) {
                    int[] approach = world.battleNetApproachPoint(worker, resource);
                    int chebyshev = Math.max(
                            Math.abs(approach[0] - worker.tileX()),
                            Math.abs(approach[1] - worker.tileY()));
                    if (chebyshev == 1) {
                        int heading = worker.peekHeading();
                        int nextX = worker.tileX()
                                + Direction.deltaX(heading);
                        int nextY = worker.tileY()
                                + Direction.deltaY(heading);
                        if (nextX != approach[0] || nextY != approach[1]) {
                            // Free wrong leftovers stage action 25 (XHuman 9
                            // 1550). Wrong leftovers occupied by another unit
                            // also stage: XHuman 5 peon 1536's leftover N onto
                            // (50,103) holds ally peon 65, and native promotes
                            // order 25 then steps NE onto (51,103). Terrain-
                            // blocked pure-cardinal leftovers onto the approach
                            // itself (Orc 12 peon 1511 at 58,47 → 58,46) are
                            // the approach cell, so this branch does not run.
                            boolean nextFree = world.map.isFootprintFree(
                                    nextX, nextY, 1, 1,
                                    worker.movementMask(),
                                    worker.blockingFlags());
                            Unit nextOccupant = world.unitAt(nextX, nextY);
                            // Mobile ally/body only -- a mine footprint on a
                            // wrong leftover is terrain for Orc 12 peon 1511
                            // (walkTowards diagonal), not action-25 staging.
                            boolean mobileBlocksWrongLeftover = nextOccupant
                                    != null && nextOccupant != worker
                                    && nextOccupant.isOnMap()
                                    && !nextOccupant.isDying()
                                    && nextOccupant.type() != null
                                    && !nextOccupant.type().building();
                            if (nextFree || mobileBlocksWrongLeftover) {
                                // Same pure-cardinal / blocked-approach rule
                                // as the empty-route arm below: the SW mine
                                // corner is solid, so action 25 must stage the
                                // centre-facing diagonal (XOrc 12 peasant
                                // 1394 at 32,74 steps NE onto 33,73) rather
                                // than pure east onto 33,74.
                                int dx = Integer.signum(
                                        approach[0] - worker.tileX());
                                int dy = Integer.signum(
                                        approach[1] - worker.tileY());
                                boolean pureCardinal = dx == 0 || dy == 0;
                                boolean approachBlocked = !world.map.isFootprintFree(
                                        approach[0], approach[1], 1, 1,
                                        worker.movementMask(),
                                        worker.blockingFlags());
                                if (pureCardinal && approachBlocked) {
                                    int centerX = resource.tileX()
                                            + Math.max(1,
                                                    resource.type().tileWidth())
                                            / 2;
                                    int centerY = resource.tileY()
                                            + Math.max(1,
                                                    resource.type().tileHeight())
                                            / 2;
                                    int altDx = Integer.signum(
                                            centerX - worker.tileX());
                                    int altDy = Integer.signum(
                                            centerY - worker.tileY());
                                    if (altDx != 0 || altDy != 0) {
                                        dx = altDx;
                                        dy = altDy;
                                    }
                                }
                                int finalHeading = Direction.fromDelta(dx, dy);
                                worker.setPath(new PathFinder.Path(
                                        PathFinder.Result.FOUND,
                                        new int[] {finalHeading}));
                                worker.setBattleNetGoldLongApproach(false);
                                worker.setBattleNetOrderDelay(2);
                                return;
                            }
                        }
                    }
                }
                // Oil uses the raw retail resource substates directly. A
                // distant tanker remains in action 23 for 32 visits at its
                // boarding seat, promotes to action 25 for three visits, and
                // then becomes hidden action 26. An order issued while the
                // tanker is already adjacent starts at action 25.
                if (info.resource() == UnitType.Resource.OIL && resource != null
                        && worker.distanceTo(resource) <= 1
                        && battleNetOilTankerReachedApproach(worker, resource)) {
                    worker.clearPath();
                    worker.setRouteSpent(false);
                    worker.setWalkHolding(false);
                    if (worker.battleNetOilAction()
                            == Unit.BattleNetOilAction.TO_RESOURCE) {
                        if (worker.battleNetOilActionTicks() == 0) {
                            worker.setBattleNetOilActionTicks(32);
                        }
                        int left = worker.battleNetOilActionTicks() - 1;
                        worker.setBattleNetOilActionTicks(left);
                        if (left == 0) {
                            worker.setBattleNetOilAction(
                                    Unit.BattleNetOilAction.FINAL_APPROACH);
                            // The transition visit is the first recorded
                            // action-25 cycle. Two further waits make the
                            // three BOARD records native exposes before 26.
                            worker.setBattleNetOilActionTicks(2);
                        }
                        return;
                    }
                    if (worker.battleNetOilAction()
                            == Unit.BattleNetOilAction.FINAL_APPROACH) {
                        if (worker.battleNetOilActionTicks() > 0) {
                            worker.setBattleNetOilActionTicks(
                                    worker.battleNetOilActionTicks() - 1);
                            return;
                        }
                        enterBattleNetOilPlatform(worker, info, resource);
                        return;
                    }
                }
                boolean pathWait = world.movement.walkTowards(worker, resource);
                // Every wait the walk answered climbs the shove ladder --
                // MoveToResource_Unit's PF_WAIT arm,
                // The game before the visibility test
                // below, as upstream's fall-through orders it.
                if (pathWait && worker.order() == Unit.Order.HARVEST
                        && worker.waitCycles() > 0) {
                    resourceWalkWaited(worker, resource.tileX(), resource.tileY(),
                            resource.type().tileWidth(), resource.type().tileHeight());
                }
                // A goal the owner cannot see is dropped in the one
                // breakable cycle at each step's end. DoActionMove is asked
                // every cycle of the walk and its answer only reaches the
                // visibility test with the animation let go
                // (MoveToResource_Unit's default arm,
                // The game ): the cycle a step commits it
                // is already unbreakable again, so the drop can never
                // pre-empt the first step -- the walk always sets off --
                // and it fires exactly in the step's tail wait, where the
                // early "reached" sends StartGathering's dead-goal arm
                // looking for another mine within fifteen, or finishes the
                // order. A computer player's
                // walks never drop -- it sees everything -- but level08h's
                // p4 is a rescue-active slot, and its drafted peasant lets
                // go of the never-seen far mine in its first step's tail,
                // cycle 147, and walks to the crowded near one instead; p2's
                // five do the same dance together at 85.
                if (worker.order() == Unit.Order.HARVEST
                        && !world.movement.isStepping(worker) && !worker.isMoving()
                        && !worker.animation().unbreakable()
                        && !world.targets.isVisibleAsGoal(worker.player(), resource)) {
                    Unit another = findAnotherMine(worker, worker.carrying(), 15);
                    if (another != null) {
                        boolean changedGoal = another != resource;
                        worker.setResourceUnit(another);
                        worker.setResourceTile(another.tileX(), another.tileY());
                        // SetGoal invalidates PathfinderOutput only when its
                        // CUnitPtr changes. level08h's p2 peasant rediscovers
                        // the very same unseen mine in cycle 85, so upstream
                        // consumes the cached north-east heading at 86. The
                        // rescue peasant at 147 really changes from the far
                        // mine to the near one and upstream does ask A* again
                        // at 148. Clearing unconditionally got the first case
                        // wrong; never clearing got the second one wrong.
                        if (changedGoal) {
                            worker.clearPath();
                        }
                        // The switch runs through SUB_START_RESOURCE
                        // so the wait ladder
                        // starts over with the new goal.
                        worker.setResourceWaitLadder(0);
                    } else {
                        worker.setOrderFinished(true);
                    }
                }
            } else if (info.terrainHarvester()) {
                walkToWood(worker);
                // The terrain walk's waits climb the same ladder -- both the
                // PF_WAIT and PF_UNREACHABLE arms of MoveToResource_Terrain
                // count -- against the tree the
                // walk was aimed at before any re-aim, which is the goal the
                // pathfinder input still holds when upstream reads it.
                if (worker.order() == Unit.Order.HARVEST && worker.waitCycles() > 0) {
                    resourceWalkWaited(worker, targetX, targetY, 1, 1);
                }
            } else {
                world.movement.walkTowards(worker, targetX, targetY);
                if (worker.order() == Unit.Order.HARVEST && worker.waitCycles() > 0) {
                    resourceWalkWaited(worker, targetX, targetY, 1, 1);
                }
            }
            return;
        }

        if (info.terrainHarvester() && resource == null) {
            // The range-one verdict precedes native's cached-route consume.
            // It therefore wins over both a remaining heading (XHuman 2)
            // and the Java adapter's routeSpent marker after a one-heading
            // route (XOrc 12).  In either shape native sets cursor=20 and
            // proceeds directly to StartGathering, with no PF_WAIT ten.
            worker.clearPath();
            worker.setBattleNetWoodReadyPathRequired(false);
        }
        // Gold action 25 keeps native route cursor 20 once the approach
        // square is underfoot. The Java adapter may still hold a spent
        // one-heading path from the staged step onto that square; serving
        // its empty-route PF_WAIT of ten left XOrc 12 peasant 1396 outside
        // the mine at fixture c21 while native was already UNLOAD inside.
        // Skipping confirmResourceWalkArrival is also required: the mine
        // footprint is blocked, so a fresh A* from the approach never
        // answers REACHED and instead installs a new path forever.
        boolean directBattleNetGoldArrival = resource != null
                && info.resource() == UnitType.Resource.GOLD
                && atBattleNetResourceApproach(worker, resource);
        if (directBattleNetGoldArrival) {
            worker.clearPath();
        }

        // Already inside, and the wait has run out: fill up and come out. A
        // unit with waiting cycles left is skipped before it ever reaches
        // here, which is what keeps it out of sight for the duration.
        if (worker.removed() && worker.worksite() != null) {
            // Inside a mine the whole capacity comes at once, after the one
            // long wait: gold declares no ResourceStep.
            int room = info.capacity() - worker.carried();
            if (room <= 0) {
                // GatherResource sees the already-full load as done, not as
                // an exhausted source. It proceeds through StopGathering,
                // including that method's remembered-Depot dropout fallback.
                stopGatheringInside(worker, info);
                return;
            }
            int filled = takeResource(worker, info, targetX, targetY, room);
            if (filled <= 0) {
                // LoseResource. A worker with a
                // load leaves by the face pointing at the depot it is about to
                // walk to; one with nothing to carry just leaves by the west.
                // The depot is FindDeposit's -- nearest by the walked route,
                // unreachable ones excluded -- not the straight line's.
                Unit depot = worker.carried() > 0
                        ? bestDepotByTravel(worker, info.resource(), 1000) : null;
                if (!leaveResource(worker, depot)) {
                    return;
                }
                if (worker.carried() > 0) {
                    if (!pauseOilForReadyDispatch(worker, info)) {
                        beginReturnToDepot(worker, info);
                    }
                } else if (!findAnotherResource(worker, info)) {
                    worker.setOrder(Unit.Order.STILL);
                }
                return;
            }
            // Added to what is in hand, not stored over it: upstream fills
            // the room that is left -- "addload = ResourceCapacity -
            // unit.ResourcesHeld" then "unit.ResourcesHeld += addload"
            // so a worker that walks in
            // with a part-load walks out with the sum, never the topping-up
            // alone.
            worker.setCarried(worker.carried() + filled);
            stopGatheringInside(worker, info);
            return;
        }

        // The walk is paid out before the arrival counts. Upstream's worker
        // finishes its move animation -- Moving holds it -- serves the spent
        // route's ten cycles, and only the wake after that answers REACHED
        // and advances the gathering state: on campaigns/human/level05h the
        // peasant beside the mine at cycle 8 vanishes into it during 35, and
        // this implementation had it inside at 9, mid-pixels, twenty-six cycles early.
        //
        // Wood range-one drains its last pixels here, then falls through to
        // StartGathering the cycle they clear. Returning while isMoving was
        // true at the top of the call pushed the first chop one cycle late
        // under cold-commit (Human 13 peon 50: fixture 20 instead of 19).
        if (worker.isMoving()) {
            world.movement.walkPixels(worker);
            if (worker.isMoving()) {
                return;
            }
        } else if (world.movement.isStepping(worker)
                && !directBattleNetGoldArrival
                && !(info.terrainHarvester()
                        && resource == null
                        && Math.max(
                                Math.abs(worker.tileX() - targetX),
                                Math.abs(worker.tileY() - targetY))
                                <= 1)) {
            world.movement.walkPixels(worker);
            return;
        }
        if (!directBattleNetGoldArrival && world.movement.spendTheEmptyRoute(worker)) {
            // The route-end wait before the arrival counts is a PF_WAIT to
            // MoveToResource's arm, and it climbs the shove ladder too.
            if (resource != null) {
                resourceWalkWaited(worker, resource.tileX(), resource.tileY(),
                        resource.type().tileWidth(), resource.type().tileHeight());
            } else {
                resourceWalkWaited(worker, targetX, targetY, 1, 1);
            }
            return;
        }
        if (!directBattleNetGoldArrival) {
            boolean arrived = resource == null
                    ? confirmResourceWalkArrival(worker, targetX, targetY, 1, 1)
                    : confirmResourceWalkArrival(worker, resource);
            if (!arrived) {
                return;
            }
        }

        // Just arrived. The approach path ends on the resource's own square,
        // which a worker cannot stand on, so it goes before anything else.
        worker.clearPath();

        // Into the mine, and out of sight until the wait expires. Whether a
        // worker goes in is the resource's own HarvestFromOutside flag rather
        // than anything about the building: gold says go in, and a tree is
        // terrain, which is worked standing in the open.
        if (resource != null && !info.terrainHarvester() && !info.harvestFromOutside()) {
            // Gathering starts here, and starting on a different resource
            // costs the old load: StartGathering compares the order's
            // resource against what the unit has in hand and drops the lot
            // on a change. A
            // chopper reassigned to gold
            // used to keep its part-felled wood and turn it into a short
            // load -- on campaigns/orc/level12o the peon that had six wood
            // in hand banked 94 gold at cycle 425 where upstream's, its
            // wood dropped at the mine door, banked the full hundred.
            if (worker.heldResource() != info.resource()) {
                worker.setCarried(0);
                worker.setHeldResource(info.resource());
            }
            // But not with a full load. Going in costs the whole of
            // WaitAtResource -- a hundred and fifty cycles for gold -- spent
            // off the map entirely, and a worker with no room comes back out
            // holding exactly what it went in with.
            //
            // That is not a wasted trip, it is a trap. The worker leaves the
            // mine bound for a depot; if it cannot get to one it goes idle
            // still carrying its load, the owner's AI finds an idle worker and
            // sends it to the nearest gold, and beginHarvest clears the
            // returning-to-depot flag, so it walks the one square back and
            // goes in again. Round it goes, for the rest of the game, visible
            // for the few cycles between the mine spitting it out and the AI
            // pushing it back in. A player watching an enemy peon do this sees
            // it appear and vanish on a fixed beat and cannot understand why
            // -- which is exactly the report this was found from.
            //
            // Upstream cannot reach the same state: a loaded worker is in
            // SUB_MOVE_TO_DEPOT and StartGathering is only ever entered from
            // SUB_START_RESOURCE.
            if (info.capacity() - worker.carried() <= 0) {
                beginReturnToDepot(worker, info);
                return;
            }
            // Nor into one that has nothing left. A mine normally dies the
            // moment it is emptied, so this is the case a map or a mission
            // script makes: a source that is standing, answers "yes, I hold
            // gold", and hands out none. Without this the worker queues up for
            // it again on the very next cycle, which is the same disappearing
            // act by another road.
            if (resource.resourcesHeld() <= 0) {
                if (worker.carried() > 0) {
                    beginReturnToDepot(worker, info);
                } else if (!findAnotherResource(worker, info)) {
                    worker.setOrder(Unit.Order.STILL);
                }
                return;
            }
            enterResource(worker, resource);
            // One less than the data's number: StartGathering sets
            // TimeToHarvest and GatherResource runs -- and decrements -- on
            // that same cycle, so a
            // 150-cycle stay entered during cycle 35 ends during 185. This
            // port's wait only starts counting the cycle after it is set,
            // and serving the full number kept the peon underground one
            // cycle longer than upstream let it stay.
            worker.setWaitCycles(Math.max(0, info.waitAtResource() - 1));
            return;
        }

        // Working in the open: chop, wait, chop again. Not one grab.
        if (info.terrainHarvester()) {
            if (chopInPlace(worker, info, targetX, targetY)) {
                rescueBattleNetHarvestWorker(worker);
            }
        } else {
            gatherInPlace(worker, info, targetX, targetY);
        }
    }


    /**
     * Serves the terrain-resource RI20 -> action construction -> wall-face
     * handoff after a terminal residual is refused by an allied worker.
     */
    private boolean stepBattleNetWoodTerminalRefusal(
            Unit worker, ResourceInfo info) {
        int refused = worker.battleNetWoodTerminalRefusalHeading();
        if (refused < 0) {
            return false;
        }
        if (info == null || !info.terrainHarvester()
                || worker.resourceUnit() != null || worker.returningToDepot()) {
            worker.setBattleNetWoodTerminalRefusalHeading(-1);
            return false;
        }
        int gatherStart = world.battleNetSequence == null ? -1
                : world.idle.battleNetSequenceStart(
                        worker, BattleNetSequence.ATTACK_ANIMATION);
        if (gatherStart < 0) {
            worker.setBattleNetWoodTerminalRefusalHeading(-1);
            return false;
        }
        if (worker.battleNetSequenceOffset() != gatherStart) {
            // The parked Move visit is followed by the resource action's
            // three construction callbacks, with a fresh collision lifetime.
            worker.setBattleNetCollisionCounter(0);
            worker.setBattleNetRefusals(0);
            worker.setWaitCycles(0);
            worker.setBattleNetOrderDelay(0);
            worker.setBattleNetSequenceOffset(gatherStart);
            worker.setBattleNetAnimationTimer(3);
            AnimationSet set = worker.type().animationSet();
            Animation attack = set == null ? null
                    : set.get(AnimationSet.State.ATTACK);
            if (attack != null && worker.animation().current() != attack) {
                worker.animation().switchTo(attack);
            }
            return true;
        }
        if (worker.battleNetAnimationTimer() > 1) {
            worker.setBattleNetAnimationTimer(
                    worker.battleNetAnimationTimer() - 1);
            return true;
        }

        int goalX = worker.battleNetWoodOrderX();
        int goalY = worker.battleNetWoodOrderY();
        int constructionDistance = Math.max(
                Math.abs(goalX - worker.tileX()),
                Math.abs(goalY - worker.tileY()));
        if (constructionDistance >= 3) {
            BattleNetPathFinder.GoalMarker constructionMarker =
                    (x, y) -> Math.max(Math.abs(x - goalX),
                            Math.abs(y - goalY)) <= 1;
            PathFinder.Path construction = world.findBattleNetPointPath(
                    worker, goalX, goalY, constructionMarker, true);
            if (construction.result() == PathFinder.Result.FOUND
                    && construction.length() > 0) {
                // A full forest-prefix construction redraws a real wall route,
                // rather than synthesising only its first free compass byte.
                // XHuman 12 slot 1364 gets SE,E here and consumes SE in this
                // same fixture, retaining E behind route index one.
                worker.setBattleNetWoodTerminalRefusalHeading(-1);
                worker.setBattleNetWoodReadyPathRequired(false);
                worker.setPath(construction);
                worker.setPathGoal(-1, -1);
                return false;
            }
        }
        int currentDistance = Math.max(
                Math.abs(goalX - worker.tileX()),
                Math.abs(goalY - worker.tileY()));
        int admitted = -1;
        for (int turn = 0; turn < Direction.COUNT; turn++) {
            int heading = Math.floorMod(refused + turn, Direction.COUNT);
            int nextX = worker.tileX() + Direction.deltaX(heading);
            int nextY = worker.tileY() + Direction.deltaY(heading);
            int nextDistance = Math.max(
                    Math.abs(goalX - nextX), Math.abs(goalY - nextY));
            if (nextDistance < currentDistance
                    && world.canEnter(worker, nextX, nextY)) {
                admitted = heading;
                break;
            }
        }
        if (admitted < 0) {
            // Stay in the bounded construction cadence while the formation
            // remains closed; do not degrade to an every-cycle path retry.
            worker.setBattleNetAnimationTimer(3);
            return true;
        }
        worker.setBattleNetWoodTerminalRefusalHeading(-1);
        worker.setBattleNetWoodReadyPathRequired(false);
        worker.setPath(new PathFinder.Path(
                PathFinder.Result.FOUND, new int[] {admitted}));
        worker.setPathGoal(-1, -1);
        return false;
    }


    /**
     * Pays the final {@code DoActionMove} ask which says a resource walk has
     * reached its range-one goal.
     *
     * <p>Adjacency is the answer, but measuring it is not the operation.
     * {@code COrder_Resource::MoveToResource} and {@code MoveToDepot} both
     * reach here through {@code DoActionMove}; once its cached route and
     * PF_WAIT have been spent, {@code NextPathElement} calls
     * {@code AStarFindPath} once more. For a worker beside a three-square mine
     * the simple-path shortcut declines, the full search answers
     * {@code PF_REACHED}, and {@code AStarCleanUp} empties the process-wide
     * movement-cost memo on the way through.
     *
     * <p>That otherwise invisible cleanup is shared simulation state. On
     * human level 11 it occurs at cycle 167; a critter's one-square shortcut
     * on 168 must therefore see the critter which has since settled on its
     * goal and answer unreachable.
     */
    boolean confirmResourceWalkArrival(
            Unit worker, int goalX, int goalY, int goalWidth, int goalHeight) {
        // This is DoActionMove, not PlaceReachable. The distinction matters
        // when a tanker is sitting over an oil patch: DoActionMove unmarks
        // the tanker's SeaUnit field bit around NextPathElement, so the
        // zero-hit-point patch cannot trap it; the depot-selection
        // UnitReachable ask deliberately leaves that bit marked and does see
        // the patch as the first naval cache entry.
        PathFinder.Path path = world.findMovementPath(worker,
                new PathFinder.Goal(goalX, goalY,
                        Math.max(1, goalWidth), Math.max(1, goalHeight), 0, 1));
        if (path.result() == PathFinder.Result.REACHED) {
            return true;
        }
        if (path.result() == PathFinder.Result.FOUND) {
            worker.setPath(path);
            worker.setPathGoal(-1, -1);
            return false;
        }
        // MoveToResource's PF_UNREACHABLE arm retries after five cycles.
        // An adjacent, on-map start normally cannot take this branch because
        // its own square is in the marked range-one goal; retain the literal
        // fallback for a dynamically invalidated goal.
        worker.setWaitCycles(5);
        return false;
    }


    /**
     * Confirms arrival at a unit goal with that goal omitted from occupancy.
     *
     * <p>The goal is not an obstacle to its own marked arrival ring. This is
     * especially visible for a 2x2 tanker: its top-left anchor may be outside
     * a shipyard while the rest of its hull overlaps the marked skirt. Leaving
     * the shipyard's SEA_UNIT/BUILDING bits set makes AStarMarkGoal reject that
     * valid anchor and return a one-step route away from the depot; the next
     * ask routes straight back and the tanker oscillates forever.</p>
     */
    boolean confirmResourceWalkArrival(Unit worker, Unit goal) {
        if (world.battleNetMovementStride(worker) > 1) {
            // The caller reaches this confirmation only after the cached
            // route and its empty-route wait have both been consumed.  At
            // that point native's range-one check is footprint based.  The
            // generic marked-goal probe is anchor based and could route a
            // two-by-two tanker away even though its hull was already on the
            // refinery skirt (most visibly after a platform was depleted).
            if (worker.distanceTo(goal) <= 1) {
                return true;
            }
            PathFinder.Path path = world.findBattleNetTargetPath(worker, goal);
            if (path.result() == PathFinder.Result.REACHED
                    || (path.result() == PathFinder.Result.FOUND
                            && path.length() == 0)) {
                return true;
            }
            if (path.result() == PathFinder.Result.FOUND) {
                worker.setPath(path);
                worker.setPathGoal(-1, -1);
                return false;
            }
            worker.setWaitCycles(5);
            return false;
        }
        world.setMovementFieldFlags(goal, false);
        try {
            return confirmResourceWalkArrival(worker, goal.tileX(), goal.tileY(),
                    goal.type().tileWidth(), goal.type().tileHeight());
        } finally {
            world.setMovementFieldFlags(goal, true);
        }
    }


    /** Moves a resource order into BNE's raw action 24 homeward substate. */
    private void beginReturnToDepot(Unit worker, ResourceInfo info) {
        worker.setReturningToDepot(true);
        if (info.resource() == UnitType.Resource.OIL) {
            worker.setBattleNetDoubleStep(
                    ((worker.tileX() | worker.tileY()) & 1) == 0);
            worker.setBattleNetOilAction(Unit.BattleNetOilAction.TO_DEPOT);
            worker.setBattleNetOilActionTicks(0);
        }
    }


    /**
     * Lets a computer tanker pass through BNE's real Still/ready boundary.
     *
     * <p>Retail Orc 14 does not change directly from hidden action 26 to
     * action 24. It surfaces Still for 25 cycles and the naval ready marker
     * constructs 24. Player-issued resource orders keep their own continuous
     * loop; this boundary is the computer ready callback at {@code 0x439280}.</p>
     */
    private boolean pauseOilForReadyDispatch(Unit worker, ResourceInfo info,
            int[] returnPoint) {
        if (info.resource() != UnitType.Resource.OIL) {
            return false;
        }
        if (!pauseComputerForReadyDispatch(worker)) {
            return false;
        }
        // A loaded platform exit owns the same timed Still head as a gold
        // mine exit. XHuman 8 tanker 1538 surfaces on fixture 258 with raw
        // action 2, next action 24 and timer 25; it stays Still through 282,
        // promotes Return Goods on 283, and takes its first doubled stride on
        // 286. Merely changing the current order to Still lets its very next
        // idle marker call 0x439280 and reconstruct action 24 on fixture 259.
        Unit home = worker.returnDepotGoal();
        if (worker.carried() > 0 && home != null
                && world.battleNetSequence != null) {
            int goalX = returnPoint == null ? home.tileX() : returnPoint[0];
            int goalY = returnPoint == null ? home.tileY() : returnPoint[1];
            worker.clearPath();
            worker.setReturningToDepot(true);
            worker.setOrderTarget(goalX, goalY);
            worker.enqueueOrder(new Unit.QueuedOrder(
                    Unit.QueuedOrderKind.RETURN_GOODS,
                    goalX, goalY, home, null, null));
            worker.setQueuedReplacementPending(true);
            // This unit tick decrements the queue once after StopGathering,
            // leaving the authenticated timer 25 at the cycle boundary.
            worker.setBattleNetOrderDelay(26);
            world.idle.armBattleNetDepotReadyHold(worker);
        }
        worker.setBattleNetOilAction(Unit.BattleNetOilAction.IDLE);
        worker.setBattleNetOilActionTicks(0);
        return true;
    }

    private boolean pauseOilForReadyDispatch(Unit worker, ResourceInfo info) {
        return pauseOilForReadyDispatch(worker, info, null);
    }

    private boolean pauseComputerForReadyDispatch(Unit worker) {
        Player owner = world.player(worker.player());
        boolean computer = owner != null
                && (owner.type()
                        == net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER
                    || owner.type()
                        == net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.RESCUE_ACTIVE);
        if (!computer || !world.ais.containsKey(worker.player())) {
            return false;
        }
        worker.setReturningToDepot(false);
        worker.setOrder(Unit.Order.STILL);
        return true;
    }


    /**
     * Stops an indoor gathering stay and starts its homeward leg.
     *
     * <p>StopGathering carries a historical compatibility fallback: when the
     * fresh FindDeposit traversal answers none, a contained worker is still
     * dropped towards the order's previous {@code Depot} if it is alive. The
     * pointer is cleared immediately afterwards and the order still gives up;
     * the fallback only chooses the face of the mine. levelx12h's peon 47
     * hits this at cycle 553 and surfaces south towards its old Great Hall
     * rather than on the generic west face.
     */
    void stopGatheringInside(Unit worker, ResourceInfo info) {
        Unit home = bestDepotByTravel(worker, info.resource(), 1000);
        Unit rememberedDepot = worker.resourceDepot();
        Unit dropTowards = home;
        int returnGoalX = home == null ? -1 : home.tileX();
        int returnGoalY = home == null ? -1 : home.tileY();
        boolean verticalDoubledTankerSpread = false;
        if (home != null && home.type() != null
                && info.resource() == UnitType.Resource.OIL
                && worker.type() != null && worker.type().seaUnit()
                && world.battleNetMovementStride(worker) > 1
                && worker.carried() > 0) {
            // A doubled tanker feeds SpreadUnit the closest point of the
            // depot rectangle, rather than its top-left anchor. The reverse
            // ray may still store an occupied shoreline point beyond that
            // face. Orc 10's south-east approach to the 3x3 shipyard thereby
            // stores 11,23; tankers whose nearest refinery corner is already
            // reachable retain that authenticated corner instead.
            int depotRight = home.tileX()
                    + Math.max(1, home.type().tileWidth()) - 1;
            int depotBottom = home.tileY()
                    + Math.max(1, home.type().tileHeight()) - 1;
            returnGoalX = Math.max(home.tileX(),
                    Math.min(worker.tileX(), depotRight));
            returnGoalY = Math.max(home.tileY(),
                    Math.min(worker.tileY(), depotBottom));
            boolean verticalMajor = Math.abs(worker.tileY() - returnGoalY)
                    > Math.abs(worker.tileX() - returnGoalX);
            verticalDoubledTankerSpread = verticalMajor;
            // The captured midpoint projection belongs to the vertical
            // shoreline form: Orc 10 turns corner 12,23 into 11,23. The
            // horizontal form retains its nearest corner; XOrc 8 feeds
            // 89,71 to SpreadUnit and stores native 97,65. Applying the
            // midpoint symmetrically changed that to the unauthenticated
            // 96,67.
            if (verticalMajor) {
                returnGoalX = home.tileX()
                        + Math.max(1, home.type().tileWidth()) / 2;
            }
        }
        boolean doubledTankerSpread = home != null
                && info.resource() == UnitType.Resource.OIL
                && worker.type() != null && worker.type().seaUnit()
                && world.battleNetMovementStride(worker) > 1
                && worker.carried() > 0;
        int[] dropPoint = home != null && world.battleNetSequence != null
                ? world.battleNetSpreadUnitGoal(
                        worker, home.tileX(), home.tileY())
                : null;
        int[] returnPoint = doubledTankerSpread
                ? world.battleNetSpreadUnitGoal(worker,
                        returnGoalX, returnGoalY)
                : dropPoint;
        String resourceTrace = System.getenv("CHONKCRAFT_TRACE_RESOURCE");
        if (resourceTrace != null
                && worker.id() == Integer.parseInt(resourceTrace)) {
            System.err.printf("JRESOURCE cycle=%d unit=%d home=%d remembered=%d"
                            + " carried=%d mine=%d%n",
                    world.cycle, worker.id(), home == null ? -1 : home.id(),
                    rememberedDepot == null ? -1 : rememberedDepot.id(),
                    worker.carried(), worker.worksite() == null
                            ? -1 : worker.worksite().id());
        }
        if (dropTowards == null && rememberedDepot != null
                && rememberedDepot.isAlive() && !rememberedDepot.isDying()) {
            dropTowards = rememberedDepot;
        }
        boolean leftResource = dropPoint != null
                ? leaveResource(worker, dropPoint[0], dropPoint[1])
                : leaveResource(worker, dropTowards);
        if (!leftResource) {
            return;
        }
        worker.setResourceDepot(home);
        worker.setReturnDepotGoal(home);
        if (returnPoint != null) {
            // The native order constructor authors and spreads unit+0x84
            // before the timed Still head and eventual queue pop. Doubled
            // tankers expose a distinct dropout face hint above, while this
            // major-axis footprint point remains the queued return goal.
            worker.setOrderTarget(returnPoint[0], returnPoint[1]);
        }

        if (pauseOilForReadyDispatch(worker, info, returnPoint)) {
            return;
        }
        if (pauseGoldMinerForReturnDispatch(
                worker, info, home, returnPoint)) {
            return;
        }
        beginReturnToDepot(worker, info);

        // Execute falls through from SUB_STOP_GATHERING into MoveToDepot on
        // the same call. A worker that surfaces with a real depot therefore
        // begins its first step home immediately. With no fresh depot there
        // is no walk: the fallback above has already done all it is allowed
        // to do.
        if (home != null && worker.isOnMap()) {
            stepHarvest(worker);
        }
    }

    /**
     * Preserves the ready animation between a player's mine exit and its
     * automatic walk home.
     *
     * <p>Retail does not turn the hidden gold action 26 directly into action
     * 24. Authenticated {@code return-goods-1/02} surfaces player peon 1594
     * at fixture 209 as Still with Return-Goods queued and timer 25, promotes
     * action 24 at 234, and takes its first step at 237. Computer miners cross
     * the same boundary: XHuman 8 slot 1571 and XOrc 12 slot 1396 surface
     * Still with next action 24 and timer 25. That ready window also lets the
     * AI replace the queued return with construction when required. Starting
     * the depot walk from the mine-exit call skips the physical ready boundary
     * and advances the worker an extra tile immediately.</p>
     */
    private boolean pauseGoldMinerForReturnDispatch(Unit worker,
            ResourceInfo info, Unit home, int[] returnPoint) {
        if (info.resource() != UnitType.Resource.GOLD
                || home == null || world.battleNetSequence == null) {
            return false;
        }
        beginReturnToDepot(worker, info);
        worker.clearPath();
        worker.setOrder(Unit.Order.STILL);
        int goalX = returnPoint == null ? home.tileX() : returnPoint[0];
        int goalY = returnPoint == null ? home.tileY() : returnPoint[1];
        worker.setOrderTarget(goalX, goalY);
        worker.enqueueOrder(new Unit.QueuedOrder(
                Unit.QueuedOrderKind.RETURN_GOODS,
                goalX, goalY, home, null, null));
        worker.setQueuedReplacementPending(true);
        // The queue is visited once more at the bottom of this same unit
        // tick, so store 26 to leave the authenticated end-of-cycle 25.
        worker.setBattleNetOrderDelay(26);
        world.idle.armBattleNetDepotReadyHold(worker);
        return true;
    }


    /**
     * One cycle of standing at a tree, on upstream's own clock.
     *
     * <p>Implements the {@code TerrainHarvester} half of
     * {@code COrder_Resource::GatherResource}
     * The game with
     * {@code StartGathering}'s wind-up. Three rules the wait-dance this
     * replaces got wrong, each measured on campaigns/orc-exp/levelx12o's
     * take-streams:
     *
     * <p>First, nothing is taken on arrival: the clock is wound to the wait
     * and the first load lands a whole period later -- first wood at cycle
     * 63, not 39. Second, the rewind is {@code TimeToHarvest +=} with no
     * fencepost, so swings come exactly a period apart -- 24, not 25.
     * Third, the swing animation itself runs and loops every cycle
     * ({@code AnimateActionHarvest}), so the unbreakable span the shipped
     * animation declares -- twenty-four cycles of axe, one breathable tail
     * -- is what every queued command and every leave waits on, drifting
     * one cycle against the take clock per loop because the loop is
     * twenty-five long.
     */
    boolean chopInPlace(Unit worker, ResourceInfo info, int targetX, int targetY) {
        int period = Math.max(1, info.waitAtResource());
        String woodDetailTrace = System.getenv("CHONKCRAFT_TRACE_WOODDETAIL");
        if (woodDetailTrace != null
                && worker.id() == Integer.parseInt(woodDetailTrace)) {
            MapField tracedStand = world.map.fieldOrNull(targetX, targetY);
            System.err.printf("JWOODDETAIL cycle=%d unit=%d goal=%d,%d forest=%d value=%d"
                            + " started=%d done=%d clock=%d carried=%d unbreak=%d%n",
                    world.cycle, worker.id(), targetX, targetY,
                    tracedStand != null && tracedStand.isForest() ? 1 : 0,
                    tracedStand == null ? -1 : tracedStand.value(),
                    worker.gatherClockStarted() ? 1 : 0,
                    worker.chopDone() ? 1 : 0, worker.timeToHarvest(), worker.carried(),
                    worker.animation().unbreakable() ? 1 : 0);
        }
        boolean startingGatherAnimation = !worker.gatherClockStarted();
        if (startingGatherAnimation) {
            int targetIndex = targetX + targetY * world.map.width();
            Unit claimant = world.battleNetClaimedWood.get(targetIndex);
            if (claimant != null && !isActiveWoodClaim(claimant, targetX, targetY)) {
                // FUN_0044df10 restores a claimed forest square when its
                // gathering order is destroyed. A player Move/Attack/Build
                // replaces that order before another unit reaches the tree.
                // Keeping the Java-side reservation after the owner left made
                // every later peon rediscover the same still-forest square,
                // reject it as claimed, wait, and repeat forever. Validate at
                // the reservation boundary as well as at normal harvest exits
                // so every command kind gets the native release semantics.
                world.battleNetClaimedWood.remove(targetIndex);
                claimant = null;
            }
            if (claimant != null && claimant != worker) {
                // Native StartGathering first requires square code -2.
                // The preceding worker in low-to-high pool order has
                // already changed this tree to -4, so FindTerrainType
                // searches fifteen squares from the worker and installs
                // that result without starting a swing or drawing RNG.
                // XHuman 2 slot 1589 is the cycle-19 witness: re-aim on
                // c19, standing tail wait 3/2/1, first swing on c22.
                int[] found = findClaimedWoodReplacement(worker);
                if (found != null) {
                    world.combat.aimAt(worker, found);
                    worker.setBattleNetOrderDelay(2);
                } else {
                    worker.setOrder(Unit.Order.STILL);
                }
                return false;
            }
            // BNE 2.02b computes DirectionToHeading(unit position,
            // resource point) at 0x00424221 and writes the returned byte to
            // unit+0x0a before terrain work starts. The resource point is the
            // tree, not the square the pathfinder happened to approach from.
            worker.setDirection(Missile.directionToHeading(
                    targetX - worker.tileX(), targetY - worker.tileY()));
            world.battleNetClaimedWood.put(targetIndex, worker);
            worker.setGatherClockStarted(true);
            worker.setChopDone(false);
            // Retail 0x423550 seeds unit+0xb from SyncRand on terrain-harvest
            // work. Two call sites share that function:
            //
            // 1. StartGathering when a walker claims the tree (sequence
            //    2657). Standing peons skip this; they were never on a path
            //    at range-one. Orc 7 peon 1567 is the cycle-24 claim witness.
            // 2. Every entry to work opcode 2660 -- the first swing after the
            //    three-cycle 2657 staging wait, and every later animation
            //    loop (twenty-five cycles of Harvest_wood). Orc 7 peon 1576
            //    draws at fixture 6 then 31; walker 1567 draws claim at 24,
            //    first 2660 at 27, then loops.
            //
            // Gold never enters chopInPlace. A prior experiment that drew on
            // every resource-take wrap (period 24) fired one cycle early and
            // desynced XOrc 12; the arm is the 25-cycle animation loop, not
            // WaitAtResource.
            boolean walkClaim = worker.battleNetWoodWalkClaim();
            worker.setBattleNetWoodWalkClaim(false);
            world.syncRand();
            // Walker / free-prefix claim: draw now, first 2660 in three.
            // Standing start: this draw IS the first 2660; next loop in 25.
            worker.setBattleNetWoodSyncRemaining(walkClaim ? 3 : 25);
            if (System.getenv("CHONKCRAFT_TRACE_WOOD") != null) {
                System.err.println("JWOODSTART " + world.cycle + " unit=" + worker.id()
                        + " walkClaim=" + (walkClaim ? 1 : 0)
                        + " arm=" + worker.battleNetWoodSyncRemaining()
                        + " at " + worker.tileX() + "," + worker.tileY()
                        + " goal " + targetX + "," + targetY);
            }
            worker.animation().switchTo(harvestAnimation(worker));
            startBattleNetRescueHarvestAnimation(worker);
            worker.setTimeToHarvest(period);
            // StartGathering's drop-on-change, before any wood moves.
            if (worker.heldResource() != info.resource()) {
                worker.setCarried(0);
                worker.setHeldResource(info.resource());
            }
            // GatherResource runs on the starting cycle too: Execute falls
            // straight through, which is why the first take is the wait
            // after the start and not the wait plus one.
        } else if (worker.battleNetWoodSyncRemaining() > 0) {
            int left = worker.battleNetWoodSyncRemaining() - 1;
            if (left == 0) {
                // Work opcode 2660 (first swing or animation loop).
                world.syncRand();
                left = 25;
                if (System.getenv("CHONKCRAFT_TRACE_WOOD") != null) {
                    System.err.println("JWOODSYNC " + world.cycle + " unit=" + worker.id()
                            + " at " + worker.tileX() + "," + worker.tileY());
                }
            }
            worker.setBattleNetWoodSyncRemaining(left);
        }
        boolean rescueOnActionMarker = !startingGatherAnimation
                && stepBattleNetRescueHarvestAnimation(worker);
        world.advance(worker);
        if (System.getenv("CHONKCRAFT_TRACE_WOODANIM") != null) {
            System.err.println("JWOODANIM " + world.cycle + " unit=" + worker.id()
                    + " unbreak=" + (worker.animation().unbreakable() ? 1 : 0));
        }
        worker.setTimeToHarvest(worker.timeToHarvest() - 1);

        // Full, and only waiting for the swing to finish: DoneHarvesting
        // holds the chopper through the unbreakable stretch and the leave
        // happens on the first breathable cycle.
        if (worker.chopDone()) {
            if (!worker.animation().unbreakable()) {
                worker.setChopDone(false);
                worker.setGatherClockStarted(false);
                releaseBattleNetWoodClaim(worker, targetX, targetY);
                worker.animation().switchTo(world.stillAnimation(worker));
                // "Store resource position": what a woodcutter remembers is
                // where it STOOD, not the tree it felled -- StopGathering's
                // terrain arm keeps unit.tilePos (:
                // 953-956), and the depot exit's ten-square wood hunt
                // centres on that memory. On level05o the peasant that
                // stood at 31,99 hunts from there and finds the tree at
                // 30,98, dropping out at 33,98; a port that remembered the
                // felled square 31,98 found 31,97 instead and stepped out
                // one square north of upstream at cycle 884.
                worker.setResourceTile(worker.tileX(), worker.tileY());
                beginReturnToDepot(worker, info);
                // And straight into the walk: Execute falls through from
                // SUB_STOP_GATHERING into MoveToDepot on the same call
                // so the feller that lets
                // go during cycle 805 of levelx12o ends that cycle already
                // stepping onto 79,43 -- this implementation used to spend 806
                // standing and stepped at 807.
                stepHarvest(worker);
            }
            return rescueOnActionMarker;
        }

        // "Target gone?" -- the tree fell under someone else's axe. The
        // chopper keeps whatever it holds and walks to more wood, not home:
        // upstream merely re-enters SUB_MOVE_TO_RESOURCE when the swing
        // lets go, and the walk's own
        // lost-wood arm finds the next stand within sixteen. The walk
        // starts on the letting-go cycle itself.
        MapField stand = world.map.fieldOrNull(targetX, targetY);
        if (stand == null || !stand.isForest()) {
            if (!worker.animation().unbreakable()) {
                worker.setGatherClockStarted(false);
                releaseBattleNetWoodClaim(worker, targetX, targetY);
                worker.animation().switchTo(world.stillAnimation(worker));
                // Only the state flips on the letting-go cycle -- upstream
                // returns to SUB_MOVE_TO_RESOURCE and the walk's own
                // lost-wood arm runs on the next call, so the step lands a
                // cycle after the swing breaks: u170's axe lets go during
                // 813 of levelx12o and its step onto the felled square
                // shows at 814, where a port that walked in the same
                // breath showed 813. The re-aim itself happens now, or the
                // next cycle's adjacency test would wind the clock over
                // the stump.
                int[] found = world.findTerrainType(worker, targetX, targetY,
                        World.LOST_WOOD_RANGE);
                if (found != null) {
                    world.combat.aimAt(worker, found);
                } else if (worker.carried() > 0) {
                    // The same stand-square memory as the full-load leave.
                    worker.setResourceTile(worker.tileX(), worker.tileY());
                    beginReturnToDepot(worker, info);
                } else {
                    worker.setOrder(Unit.Order.STILL);
                }
            }
            return rescueOnActionMarker;
        }

        while (worker.timeToHarvest() < 0) {
            worker.setTimeToHarvest(worker.timeToHarvest() + period);
            int room = info.capacity() - worker.carried();
            int wanted = info.step() > 0 ? Math.min(info.step(), room) : room;
            int taken = wanted <= 0 ? 0 : takeResource(worker, info, targetX, targetY, wanted);
            if (taken > 0) {
                worker.setCarried(worker.carried() + taken);
            }
            // The feller is done the moment its square empties -- home with
            // whatever partial it holds -- and so is a full load:
            // "if (mf->Value == 0) { Map.ClearTile(...); DoneHarvesting =
            // true; }" beside the capacity check (:
            // 793-799). levelx12o's u277 fells its tree on the 801 take
            // holding twelve and walks for the hall, not for more wood.
            if (worker.carried() >= info.capacity() || !stand.isForest()) {
                worker.setChopDone(true);
                return rescueOnActionMarker;
            }
            if (taken <= 0) {
                return rescueOnActionMarker;
            }
        }
        return rescueOnActionMarker;
    }


    /** Starts the native Attack loop used by a rescuable terrain worker. */
    private void startBattleNetRescueHarvestAnimation(Unit worker) {
        if (!isBattleNetRescueWorker(worker) || world.battleNetSequence == null) {
            return;
        }
        int attackStart = world.idle.battleNetSequenceStart(
                worker, BattleNetSequence.ATTACK_ANIMATION);
        if (attackStart < 0) {
            return;
        }
        BattleNetSequence.Tick opening = world.battleNetSequence.tick(attackStart, 1);
        if (opening.valid()) {
            worker.setBattleNetSequenceOffset(opening.offset());
            worker.setBattleNetAnimationTimer(opening.timer());
        }
    }


    /** Keeps that native Attack loop aligned while the worker chops in place. */
    private boolean stepBattleNetRescueHarvestAnimation(Unit worker) {
        if (!isBattleNetRescueWorker(worker) || world.battleNetSequence == null
                || worker.battleNetSequenceOffset() < 0) {
            return false;
        }
        BattleNetSequence.Tick tick = world.battleNetSequence.tick(
                worker.battleNetSequenceOffset(),
                worker.battleNetAnimationTimer());
        if (!tick.valid()) {
            return false;
        }
        worker.setBattleNetSequenceOffset(tick.offset());
        worker.setBattleNetAnimationTimer(tick.timer());
        return tick.actionMarker();
    }


    private boolean isBattleNetRescueWorker(Unit worker) {
        Player owner = world.player(worker.player());
        return owner != null && World.isRescuable(owner.type());
    }


    /** Publishes action 23's OP0 after the Harvest visit has completed. */
    private void rescueBattleNetHarvestWorker(Unit worker) {
        int priorOwner = worker.player();
        world.rescueBattleNetUnit(worker);
        if (worker.player() == priorOwner) {
            return;
        }
        // AssignToPlayer returns a working prisoner to Still. Human 8's
        // peasant changes from action 23/Attack to action 3/Still at c142.
        worker.setOrder(Unit.Order.STILL);
        worker.animation().switchTo(world.stillAnimation(worker));
        int stillStart = world.idle.battleNetStillSequenceStart(worker);
        if (stillStart >= 0) {
            worker.setBattleNetSequenceOffset(stillStart);
            worker.setBattleNetAnimationTimer(3);
        }
    }


    /**
     * One period of gathering from outside, as {@code GatherResource} does it.
     *
     * <p>The load goes on in steps. A peasant takes two wood every twenty-four
     * cycles until it is carrying a hundred, which is fifty swings and forty
     * seconds -- and the square it is working loses the same two each time, so
     * the tree comes down as it is felled rather than the instant it is
     * touched. Both numbers are the unit's own {@code ResourceStep} and
     * {@code WaitAtResource}, which the data has carried since the first day
     * and nothing read: gathering was modelled as a trip rather than as a
     * repeated action, so there was nowhere for a period to go.
     *
     * <p>A step of zero means the whole capacity at once, which is how a gold
     * mine works: one long wait inside, then out with a full load.
     */
    void gatherInPlace(Unit worker, ResourceInfo info, int targetX, int targetY) {
        // The same drop-on-change StartGathering runs at a mine's door
        // The game a worker sent from one resource to
        // another loses the old part-load the moment the new work starts.
        // Idempotent once the first period has run, so it sits here rather
        // than in a start-only path.
        if (worker.heldResource() != info.resource()) {
            worker.setCarried(0);
            worker.setHeldResource(info.resource());
        }
        worker.setWaitCycles(Math.max(1, info.waitAtResource()));
        worker.animation().switchTo(harvestAnimation(worker));

        int room = info.capacity() - worker.carried();
        int wanted = info.step() > 0 ? Math.min(info.step(), room) : room;
        int taken = wanted <= 0 ? 0 : takeResource(worker, info, targetX, targetY, wanted);
        if (taken > 0) {
            worker.setCarried(worker.carried() + taken);
        }

        // Full, or there is nothing left to take. Either ends the visit.
        if (worker.carried() >= info.capacity() || taken <= 0) {
            worker.animation().switchTo(world.stillAnimation(worker));
            if (worker.carried() > 0) {
                beginReturnToDepot(worker, info);
                return;
            }
            // Nothing here and nothing to carry home. Look for more of the
            // same before giving up: a worker that fells its tree, banks the
            // load, walks back to bare ground and then stands there is a
            // worker the player has to nurse all game.
            if (!findAnotherResource(worker, info)) {
                worker.setOrder(Unit.Order.STILL);
            }
        }
    }


    /**
     * One cycle of a woodcutter walking to its tree.
     *
     * <p>{@code COrder_Resource::MoveToResource_Terrain},
     * Three things happen there that
     * walking to a mine does not need, and this implementation had none of them.
     *
     * <p>First, "wood gone, look somewhere else": before every step, if the
     * square being walked to is no longer forest, the worker searches out from
     * it for one that is. Two peasants on one stand fell each other's targets
     * all the time, and without this the second one walks the whole way to
     * bare ground before it notices.
     *
     * <p>Second, and this is the one a player reported: a tree with no
     * walkable square beside it cannot be routed to, and upstream answers that
     * by searching out from where the worker is standing and going to the
     * nearest tree it can reach. Click the middle of a wood -- any tree more
     * than one square in -- and this implementation used to accept the order, fail to
     * find a route, drop to STILL, and leave the peasant standing where it was
     * for the rest of the game, carrying nothing and never told why.
     *
     * <p>Only when a search comes back empty is there really no wood, and only
     * then does the order end. Failing to plan a route is never enough on its
     * own: that is usually another worker standing in the way, and workers
     * move, so it waits ten cycles and plans again.
     */
    void walkToWood(Unit worker) {
        // A walking chopper's period clock is not running; the next arrival
        // winds it afresh.
        worker.setGatherClockStarted(false);
        MapField field = world.map.fieldOrNull(worker.resourceTileX(), worker.resourceTileY());
        if (field == null || !field.isForest()) {
            int[] found = world.findTerrainType(worker, worker.resourceTileX(), worker.resourceTileY(),
                    World.LOST_WOOD_RANGE);
            if (found == null) {
                worker.setOrder(Unit.Order.STILL);
                world.idle.stepStill(worker);
                return;
            }
            if (world.combat.aimAt(worker, found)) {
                return;
            }
        }
        // After the last heading is spent the peon is already on the range-one
        // square with routeSpent set, draining the final leg's pixels. Calling
        // stepMove there re-opens the decide gate once the drain clears Moving
        // (the cold-commit walk-before-gate model) and spendTheEmptyRoute arms
        // PF_WAIT 10 -- which is how Human 13 peon 50 lost the first chop
        // SyncRand at fixture 19. Drain only; when this visit finishes the
        // residual, fall through so the spent-route action-23 delay arms on
        // the settle cycle (XHuman 7 slot1545: timer 3/2/1 on c19-c21, east
        // heading at c22 -- returning one cycle late pushed the replan to 25).
        // A leftover that still walks closer to the stored order point
        // dest-arms the cycle the pixels land. Treating every leftover
        // beside the tree as action 23 used to park those dest-arms for
        // three extra visits. A leftover that misses the order point
        // parks for action 23's three-call start -- Human 12 peon 1565
        // residual-settles on 103,1 with leftover NE onto 104,0 while the
        // order point is still 104,1, then dest-arms 104,0 at 230.
        if (worker.isMoving()) {
            world.movement.walkPixels(worker);
            if (worker.isMoving()) {
                return;
            }
            if (world.movement.finishLeftoverReplacement(worker)) {
                return;
            }
            if (worker.pathLength() == 1
                    && worker.resourceTileX() >= 0
                    && worker.resourceTileY() >= 0) {
                int heading = worker.peekHeading();
                int nextX = worker.tileX() + Direction.deltaX(heading);
                int nextY = worker.tileY() + Direction.deltaY(heading);
                int orderX = worker.battleNetWoodOrderX();
                int orderY = worker.battleNetWoodOrderY();
                if (orderX < 0 || orderY < 0) {
                    orderX = worker.resourceTileX();
                    orderY = worker.resourceTileY();
                }
                // Leftover dest-arm compares to the stored order point
                // (native unit+0x84). A leftover that still walks closer
                // dest-arms immediately. A leftover that misses it parks
                // for action 23.
                int now = Math.max(
                        Math.abs(worker.tileX() - orderX),
                        Math.abs(worker.tileY() - orderY));
                int then = Math.max(
                        Math.abs(nextX - orderX),
                        Math.abs(nextY - orderY));
                if (then <= 1 && then >= now) {
                    worker.setBattleNetOrderDelay(2);
                    return;
                }
            }
            // A terrain worker's residual is drained in this outer harvest
            // action before stepMove sees the route. Preserve the fact that
            // this was the settle visit for a later-refusal leftover: native
            // parks route_index at 20 and returns once while the allied body
            // still holds the corner. The corner bytes survive so 0x450350
            // can rewrite them, and that rewrite arms one later route-park:
            // its first residual is discarded for a fresh route next visit.
            // Collision 1 is the earlier invalidation/replan seam; this quiet
            // residual family starts only after that history reached 2.
            // XHuman 11 peon 1584 reaches pixel 320,192 beside ally 1588 at
            // fixture 37 and stays logically on 10,6; NE+SE becomes E and
            // commits 11,6 only at fixture 38. Letting stepMove run here lost
            // the outer drain and took that shortcut one cycle early.
            if (worker.stepDrained() && worker.pathLength() > 0
                    && worker.battleNetCollisionCounter() >= 2) {
                int heading = worker.peekHeading();
                int nextX = worker.tileX() + Direction.deltaX(heading);
                int nextY = worker.tileY() + Direction.deltaY(heading);
                Unit blocker = world.unitAt(nextX, nextY);
                boolean alliedBlocker = blocker != null && blocker != worker
                        && blocker.isOnMap() && !blocker.isDying()
                        && world.isAllied(worker.player(), blocker.player());
                Unit saturatedConstructionBlocker =
                        worker.battleNetSaturatedWoodConstructionRedraw()
                                ? world.blockerOnLayer(worker, nextX, nextY)
                                : null;
                boolean saturatedConstructionRedrawRouteHold =
                        saturatedConstructionBlocker != null
                        && saturatedConstructionBlocker != worker
                        && saturatedConstructionBlocker.isOnMap()
                        && !saturatedConstructionBlocker.isDying()
                        && world.isAllied(worker.player(),
                                saturatedConstructionBlocker.player())
                        && worker.pathLength() >= 2
                        && worker.battleNetCollisionCounter() >= 8
                        && worker.lastStepHeading() == heading
                        && Direction.isDiagonal(heading);
                if (saturatedConstructionRedrawRouteHold) {
                    // A route merged after the paid construction band still
                    // belongs to that collision generation. Native XHuman 12
                    // peon 1385 commits the first NE with collision eight,
                    // drains it, then retains the second occupied NE and
                    // advances 8 -> 9 on fixture 323. The guide is still
                    // moving through this square, so query its live footprint
                    // rather than only its logical tile. Treating the visit as
                    // an ordinary repeated diagonal discarded the tail and
                    // free-compassed east on 324.
                    int collision = worker.battleNetCollisionCounter() + 1;
                    worker.setBattleNetCollisionCounter(
                            collision > 14 ? 0 : collision);
                    worker.setRouteSpent(false);
                    worker.setWaitCycles(0);
                    worker.setBattleNetOrderDelay(14);
                    worker.setBattleNetRefusalHold(true);
                    int moveStart = world.idle.battleNetSequenceStart(
                            worker, BattleNetSequence.MOVE_ANIMATION);
                    if (moveStart >= 0) {
                        worker.setBattleNetSequenceOffset(moveStart);
                        worker.setBattleNetAnimationTimer(15);
                    }
                    return;
                }
                if (alliedBlocker) {
                    int shortcut = BattleNetPathFinder.twoHeadingShortcut(
                            worker.lastStepHeading(), heading);
                    if (shortcut >= 0) {
                        int shortcutX = worker.tileX()
                                + Direction.deltaX(shortcut);
                        int shortcutY = worker.tileY()
                                + Direction.deltaY(shortcut);
                        if (world.canEnter(worker, shortcutX, shortcutY)) {
                            worker.setBattleNetWoodRouteIndex20(true);
                            return;
                        }
                    }
                    int woodOrderX = worker.battleNetWoodOrderX() >= 0
                            ? worker.battleNetWoodOrderX()
                            : worker.resourceTileX();
                    int woodOrderY = worker.battleNetWoodOrderY() >= 0
                            ? worker.battleNetWoodOrderY()
                            : worker.resourceTileY();
                    int woodOrderDistance = Math.max(
                            Math.abs(woodOrderX - worker.tileX()),
                            Math.abs(woodOrderY - worker.tileY()));
                    boolean saturatedWoodRouteHold =
                            worker.pathLength() >= 2
                            && worker.battleNetCollisionCounter() >= 3
                            && !Direction.isDiagonal(worker.lastStepHeading())
                            && Direction.isDiagonal(heading)
                            && woodOrderDistance >= 3;
                    if (saturatedWoodRouteHold) {
                        // A terrain wall which already paid three occupied
                        // probes keeps its residual bytes on the fourth.
                        // XHuman 12 peon 1385 retains SE,NE behind RI2 at
                        // fixture 235 and advances 3 -> 4; peon 1376 repeats
                        // the same native handler at fixture 236 and advances
                        // 5 -> 6. Both count a complete Move 15..1 band, then
                        // consume the cached diagonal after the allied peon
                        // vacates it. The lower-collision XHuman 11 terminal
                        // and corner-redraw families remain outside this band.
                        int collision =
                                worker.battleNetCollisionCounter() + 1;
                        worker.setBattleNetCollisionCounter(
                                collision > 14 ? 0 : collision);
                        worker.setRouteSpent(false);
                        worker.setWaitCycles(0);
                        worker.setBattleNetOrderDelay(14);
                        worker.setBattleNetRefusalHold(true);
                        int moveStart = world.idle.battleNetSequenceStart(
                                worker, BattleNetSequence.MOVE_ANIMATION);
                        if (moveStart >= 0) {
                            worker.setBattleNetSequenceOffset(moveStart);
                            worker.setBattleNetAnimationTimer(15);
                        }
                        return;
                    }
                    boolean saturatedRepeatedCardinalResidual = shortcut < 0
                            && worker.pathLength() == 3
                            && worker.battleNetPathInitialLength() == 4
                            && worker.battleNetPathStepsTaken() == 1
                            && worker.lastStepHeading() == heading
                            && !Direction.isDiagonal(heading)
                            && worker.battleNetCollisionCounter() == 2
                            && worker.battleNetRefusals() == 2
                            && woodOrderDistance >= 3;
                    if (saturatedRepeatedCardinalResidual) {
                        // The two refusals belong to the retired approach,
                        // not the blocked corner which follows this residual.
                        // Retail starts the new generation at collision one
                        // when it parks route index twenty on fixture 215.
                        worker.setBattleNetCollisionCounter(1);
                        worker.setBattleNetRefusals(0);
                        worker.setBattleNetSaturatedWoodCornerLadder(true);
                    }
                    // A repeated diagonal has no two-heading shortcut. Native
                    // parks that stale tail at route index twenty on the
                    // residual-settle visit, so the next resource callback
                    // draws against current occupancy instead of spending a
                    // visit refusing the cached square. XHuman 11 peon 1584
                    // settles SE at fixture 215 with another SE occupied,
                    // then redraws east and commits it at 216.
                    worker.clearPath();
                    worker.setRouteSpent(false);
                    worker.setWaitCycles(0);
                    worker.setBattleNetOrderDelay(0);
                    return;
                }
                if (worker.battleNetWoodRouteIndex20()) {
                    worker.clearPath();
                    worker.setRouteSpent(false);
                    return;
                }
            }
        }
        if (worker.pathLength() == 0 && !worker.isMoving()) {
            if (worker.routeSpent()) {
                // Terrain resource action 23 owns a fresh three-call start
                // transition when a short free-prefix segment ends (XHuman 7
                // slot1545: length-1 NW tip, timer 3/2/1, then east) or when
                // the tip is already beside forest (Orc 7 1567 re-aim before
                // claim). A longer free-prefix mid-journey replan must not
                // pay that delay -- it held XHuman 2 peon 1530 at 92,100
                // through fixture 52 while native stepped NW at 50.
                int freeLen = worker.battleNetGoldFreePrefixLength();
                if (System.getenv("CHONKCRAFT_TRACE_WOOD") != null) {
                    System.err.printf("JWOODROUTESPENT %d unit=%d"
                                    + " free=%d marked=%d at=%d,%d"
                                    + " tree=%d,%d%n",
                            world.cycle, worker.id(), freeLen,
                            worker.battleNetGoldFreePrefix() ? 1 : 0,
                            worker.tileX(), worker.tileY(),
                            worker.resourceTileX(), worker.resourceTileY());
                }
                worker.setRouteSpent(false);
                boolean shortFreePrefix = freeLen > 0 && freeLen < 3;
                boolean saturatedFullPrefix = freeLen >= 3
                        && worker.battleNetCollisionCounter() >= 3;
                if (saturatedFullPrefix) {
                    // A full terrain prefix normally replans immediately, but
                    // a collision-saturated prefix has returned to action 23
                    // itself. Its construction callbacks own a fresh packed
                    // collision lifetime. XHuman 12 peon 1385 drains its
                    // four-byte prefix at (12,88), clears collision four,
                    // pays 2657/3,2,1, and only then draws through the moving
                    // peon to its north. The collision-free XHuman 2
                    // three-byte mid-journey prefix still replans now.
                    worker.setBattleNetCollisionCounter(0);
                    worker.setBattleNetRefusals(0);
                    worker.setBattleNetSaturatedWoodConstructionRoute(true);
                    worker.setWaitCycles(0);
                    worker.setBattleNetOrderDelay(2);
                    worker.setBattleNetWoodOrder(
                            worker.resourceTileX(), worker.resourceTileY());
                    if (world.battleNetSequence != null) {
                        int gatherStart = world.idle.battleNetSequenceStart(
                                worker, BattleNetSequence.ATTACK_ANIMATION);
                        if (gatherStart >= 0) {
                            worker.setBattleNetSequenceOffset(gatherStart);
                            worker.setBattleNetAnimationTimer(3);
                        }
                    }
                    return;
                }
                // Only the short free-tip family re-aims to a newly adjacent
                // tree. A full direct prefix can finish beside intervening
                // forest while still owning its original order point: Human
                // 13 peon 1467 exhausts five headings at (50,48), retains the
                // tree at (50,46), and immediately replans NE,N on fixture 85.
                // Re-aiming that full prefix to (50,47) parked the peon in an
                // invented action-23 delay and made it look frozen.
                if (shortFreePrefix || freeLen == 0) {
                    int[] localTree = null;
                    boolean claimedReplacement = false;
                    int targetX = worker.resourceTileX();
                    int targetY = worker.resourceTileY();
                    if (world.map.contains(targetX, targetY)) {
                        int targetIndex = targetX
                                + targetY * world.map.width();
                        Unit claimant = world.battleNetClaimedWood
                                .get(targetIndex);
                        if (claimant != null
                                && !isActiveWoodClaim(claimant,
                                        targetX, targetY)) {
                            world.battleNetClaimedWood.remove(targetIndex);
                            claimant = null;
                        }
                        if (claimant != null && claimant != worker) {
                            // The completed prefix re-enters action 23 after
                            // another worker has changed this tree from -2 to
                            // -4. Use the same fifteen-square replacement as
                            // StartGathering: XHuman 12 peon 1376 settles its
                            // one-byte wall prefix while peon 1387 owns
                            // (14,89), then selects (15,89) and redraws
                            // NE,E,SE,S to its north face.
                            localTree = findClaimedWoodReplacement(worker);
                            claimedReplacement = localTree != null;
                        }
                    }
                    if (localTree == null) {
                        localTree = findAdjacentForest(worker.tileX(),
                                worker.tileY());
                    }
                    if (localTree == null) {
                        localTree = world.findTerrainType(worker,
                                worker.tileX(), worker.tileY(), 1);
                    }
                    if (localTree != null) {
                        worker.setResourceTile(localTree[0], localTree[1]);
                        if (claimedReplacement) {
                            // The replacement constructor writes its new
                            // forest point immediately, alongside RI20 and
                            // timer three; route bytes are redrawn only after
                            // the following two timer visits.
                            worker.setBattleNetWoodOrder(
                                    localTree[0], localTree[1]);
                        }
                    }
                    boolean saturatedClaimedShortPrefix =
                            claimedReplacement && freeLen == 1
                            && worker.battleNetCollisionCounter() >= 3;
                    worker.setBattleNetSaturatedWoodClaimedReplacement(
                            saturatedClaimedShortPrefix);
                    worker.setBattleNetOrderDelay(2);
                    return;
                }
                // freeLen >= 3 mid-journey: fall through to immediate replan.
            }
            // The spent route is served before the fresh ask here exactly as
            // in walkTowards: a refusal pops the route's last element, and
            // upstream's next attempt reads the phantom element past the
            // buffer and answers PF_WAIT for another ten whatever square the
            // stray byte points at (NextPathElement,
            // The game ). On campaigns/orc-exp/levelx12o the
            // chopper bound for 36,76 is refused its last step at 103, and
            // upstream steps around the blocker at 125 -- refusal ten, then
            // the phantom's ten -- where this implementation re-asked at 114 straight
            // off the refusal and walked a map-first divergence.
            if (world.movement.spendTheEmptyRoute(worker)) {
                return;
            }
            // One ranged search, not a search per neighbour.
            // {@code COrder_Resource::UpdatePathFinderData} hands the planner
            // the tree's own square at {@code SetMinRange(0)},
            // {@code SetMaxRange(1)},
            // and which side of the tree the worker chops from falls out of
            // which ring square the one search reaches first. This used to
            // route to each free neighbour separately and keep the shortest,
            // which broke ties its own way: on {@code campaigns/human/level05h}
            // a peasant at 34,105 sent to the tree at 29,106 stepped west
            // where upstream's stepped south-west, and that one square was
            // the whole map's first divergence.
            PathFinder.Path path;
            int treeX = worker.resourceTileX();
            int treeY = worker.resourceTileY();
            boolean cornerRefusalReplan =
                    worker.battleNetWoodCornerRefusalHeading() >= 0
                    && worker.battleNetWoodCornerRefusalVisits() >= 3;
            // When the only thing stopping the ray to the tree is the tree,
            // native aims at the tree and lets the wall follower run from the
            // square in front of it -- XHuman 8's peon 1511 at 2,67 stores
            // NE,N,N,N,N,N, which is exactly what that gives. When something
            // else is in the way first, it does not: Human 5's peasant 1512
            // has a farm between it and its tree and aims at the farm.
            int terminalHeading = worker.lastStepHeading();
            int terminalX = terminalHeading >= 0
                    && terminalHeading < Direction.COUNT
                            ? worker.tileX()
                                    + Direction.deltaX(terminalHeading)
                            : worker.tileX();
            int terminalY = terminalHeading >= 0
                    && terminalHeading < Direction.COUNT
                            ? worker.tileY()
                                    + Direction.deltaY(terminalHeading)
                            : worker.tileY();
            Unit terminalBlocker = world.unitAt(terminalX, terminalY);
            boolean diagonalTerminalRedraw = worker.pathLength() == 0
                    && worker.stepDrained()
                    && worker.battleNetCollisionCounter() == 2
                    && Direction.isDiagonal(terminalHeading)
                    && Math.max(Math.abs(treeX - worker.tileX()),
                            Math.abs(treeY - worker.tileY())) <= 2
                    && terminalBlocker != null
                    && terminalBlocker != worker
                    && terminalBlocker.isOnMap()
                    && !terminalBlocker.isDying()
                    && world.isAllied(worker.player(),
                            terminalBlocker.player());
            boolean saturatedClaimedReplacementRedraw =
                    worker.battleNetSaturatedWoodClaimedReplacement();
            boolean aimAtResource =
                    world.battleNetRayReachesResource(worker, treeX, treeY)
                    // The blocked diagonal which ended the previous direct
                    // forest ray remains part of that ray's collision
                    // generation. XHuman 11 slot 1588 parks RI20/collision
                    // two at fixture 210 while peon 1586 holds SE. Its next
                    // visit must still aim at tree 20,18, producing E,SE
                    // around the blocker; recomputing an intermediate order
                    // point chooses a one-byte south route instead.
                    || diagonalTerminalRedraw
                    // The collision-saturated one-byte prefix has already
                    // selected its replacement tree in action 23. Native
                    // keeps that exact forest point for the constructor's
                    // first redraw even when an occupied square interrupts
                    // the direct ray. XHuman 12 peon 1376 therefore routes
                    // NE,E,SE,S toward tree (15,89), rather than accepting
                    // the intervening (14,88) square as a one-byte goal.
                    || saturatedClaimedReplacementRedraw;
            int[] orderPoint = aimAtResource
                    ? new int[] {treeX, treeY}
                    : battleNetWoodOrderPoint(worker, treeX, treeY);
            int goalX = orderPoint[0];
            int goalY = orderPoint[1];
            BattleNetPathFinder.GoalMarker woodMarker =
                    (x, y) -> Math.max(Math.abs(x - goalX),
                            Math.abs(y - goalY)) <= 1;
            path = aimAtResource
                    ? world.findBattleNetPointPath(
                            worker, goalX, goalY, woodMarker,
                            false, false, false)
                    : world.findBattleNetPointPath(
                            worker, goalX, goalY, woodMarker, true);
            if (saturatedClaimedReplacementRedraw) {
                worker.setBattleNetSaturatedWoodClaimedReplacement(false);
            }
            if (path.result() == PathFinder.Result.FOUND
                    && path.length() == 1) {
                int first = path.headings()[path.length() - 1];
                int firstX = worker.tileX() + Direction.deltaX(first);
                int firstY = worker.tileY() + Direction.deltaY(first);
                Unit skirtAlly = world.unitAt(firstX, firstY);
                boolean softClearedSkirtAlly = skirtAlly != null
                        && skirtAlly != worker
                        && skirtAlly.isOnMap() && !skirtAlly.isDying()
                        && world.isAllied(worker.player(), skirtAlly.player())
                        && (world.movement.battleNetSoftClearMoveAlly(skirtAlly)
                                || world.movement
                                        .battleNetPendingLandAssaultYieldsToWood(
                                                worker, skirtAlly));
                if (softClearedSkirtAlly) {
                    // 0x4500f0 may cross the departing body, but its occupied
                    // square does not finish this resource wall. Re-run the
                    // same ray with only that premature skirt cell unmarked;
                    // the resulting multi-byte prefix is then refused by the
                    // movement consumer and redrawn on the following visit.
                    BattleNetPathFinder.GoalMarker continuedWoodMarker =
                            (x, y) -> (x != firstX || y != firstY)
                                    && woodMarker.contains(x, y);
                    PathFinder.Path continued = aimAtResource
                            ? world.findBattleNetPointPath(
                                    worker, goalX, goalY, continuedWoodMarker,
                                    false, false, false)
                            : world.findBattleNetPointPath(
                                    worker, goalX, goalY, continuedWoodMarker,
                                    true);
                    if (continued.result() == PathFinder.Result.FOUND
                            && continued.length() > 1) {
                        path = continued;
                    }
                }
            }
            // Blocked forest goals capture a free ray prefix that stops
            // one tile short of the tree. Native packs diagonals on that
            // open prefix (XHuman 8 peon 1510: NE,NE,NE onto 7,64);
            // projectile Bresenham alone yields NE,E and drifts pure
            // east. Free reverse-free goals (peon 1511 → 4,62) keep the
            // Bresenham path unchanged. Building-blocked reverse-free
            // order points (Human 5 1512: farm cell 31,106) use the same
            // diagonal pack so the free prefix is SW,SW onto 32,107, not
            // Bresenham 56 stepping west onto 32,106.
            //
            // Human 8 peasant 1507 free tip 3333433 ends on the west face
            // of tree 85,83; native stores wall-follow 333222223544 onto
            // the east face. A second probe without the automatic forest
            // free-prefix keeps wall-follow on equal first-step gain when
            // the wall rewrites the free ray onto a different skirt cell.
            // XHuman 11/12 wood peons whose wall only extends or lands on
            // the same tip keep the free prefix.
            if (!aimAtResource
                    && path.result() == PathFinder.Result.FOUND
                    && path.length() >= 1
                    && !world.map.isFootprintFree(goalX, goalY, 1, 1,
                            worker.movementMask(),
                            worker.blockingFlags())) {
                MapField goalField = world.map.fieldOrNull(goalX, goalY);
                boolean forestGoal = goalField != null
                        && goalField.isForest();
                boolean boundaryForestOrderPoint =
                        goalX <= 1 || goalY <= 1
                                || goalX >= world.map.width() - 2
                                || goalY >= world.map.height() - 2;
                if (forestGoal) {
                    PathFinder.Path wall = world.findBattleNetPointPath(
                            worker, goalX, goalY, woodMarker,
                            false, false, false);
                    if (world.battleNetPreferForestWallOverFree(
                            path, wall, worker, goalX, goalY)) {
                        path = wall;
                    }
                }
                // Building-blocked reverse-free order points (Human 5
                // farm 31,106) pick the free tip within range 1 that
                // maximises diagonals (SW,SW onto 32,107). Forest goals
                // whose free-ray already ends beside the tree upgrade to
                // the tip with the most diagonal steps (XHuman 8 peon
                // 1510: three NE onto 7,64 rather than endpoint pack
                // NE,NE,E,E onto 7,65). Distant free rays keep the
                // endpoint pack so XHuman 2 path 707 is not shortened.
                // Wall detours longer than their Chebyshev span skip tip
                // upgrade so Human 8's east face is not rewritten to SE.
                // A reverse-free order point beside the map boundary is
                // static terrain rather than the tree itself, but it retains
                // forest tip semantics. Human 12 peon 1571 therefore keeps
                // the free interior face before trying the top-edge corner.
                path = forestGoal || boundaryForestOrderPoint
                        ? world.battleNetForestDiagonalPrefer(
                                worker, path, goalX, goalY)
                        : world.battleNetDiagonalPreferPath(
                                worker, path, goalX, goalY);
            }
            if (cornerRefusalReplan) {
                worker.clearBattleNetWoodCornerRefusal();
                worker.setBattleNetSaturatedWoodCornerLadder(false);
            }
            if (path.result() == PathFinder.Result.REACHED) {
                // Already beside it; the chop notices for itself.
                return;
            }
            if (path.result() == PathFinder.Result.FOUND
                    && path.length() == 0
                    && (worker.battleNetWoodReadyPathRequired()
                            || worker.stepDrained())) {
                // Both UnitReady terrain assignments and an ordinary wood
                // route which has drained its last step retry an empty
                // approach on action 23's three-call 2657 cadence. Human 5
                // peon 1567 asks on fixtures 107,110,...122. XHuman 12 peon
                // 1360 drains at fixture 137 and asks 137,140,...200, when a
                // queued patrol finally opens its wall route. Asking every
                // visit observes transient formation gaps retail never sees.
                worker.setBattleNetOrderDelay(2);
                if (world.battleNetSequence != null) {
                    int gatherStart = world.idle.battleNetSequenceStart(
                            worker, BattleNetSequence.ATTACK_ANIMATION);
                    if (gatherStart >= 0) {
                        worker.setBattleNetSequenceOffset(gatherStart);
                        worker.setBattleNetAnimationTimer(3);
                    }
                }
                return;
            }
            if (path.result() == PathFinder.Result.UNREACHABLE) {
                // PF_UNREACHABLE. Wait ten cycles, re-aim, and try again next
                // time round: "unit.Wait = 10", then goalPos and "return 0",
                // which is "still on the way" and not "give up".
                //
                // DoActionMove calls AiCanNotMove before this resource-order
                // arm sees the unreachable verdict.
                // That call is independent of the resource wait ladder's
                // fifth-rung call below. On levelx11h cycle 203, peon 107's
                // route to a tree is unreachable behind peon 114; upstream
                // spends two draws and shoves 114, while omitting this call
                // left every unit state equal but forked the shared seed.
                world.aiCanNotMove(worker, worker.resourceTileX(),
                        worker.resourceTileY(), 1, 1);
                //
                // Standing the worker down here instead is what a player saw
                // as peasants that never started. Four peasants come out of a
                // hall into the one column of open ground beside it, all four
                // are told to chop, and only the one at the open end of the
                // column can plan a route -- the other three are each boxed in
                // by the one in front, for the two or three seconds it takes
                // that one to walk away. Measured on campaigns/human/level04h:
                // three of the four dropped to STILL on cycle 1 and were still
                // standing on the same square, having taken not one step, half
                // an hour later. Ordered back to front instead, so that the
                // square in front was already clear, the same two peasants
                // from the same squares brought in 43 and 45 loads. Nothing
                // was ever wrong with the ground; the order simply had no way
                // back once it had been given up on.
                worker.setWaitCycles(World.UNREACHABLE_WAIT);
                int[] found = world.findTerrainType(worker, worker.tileX(), worker.tileY(),
                        World.ANY_WOOD_RANGE);
                if (found == null) {
                    // Nothing anywhere it could walk to. This is the -1 that
                    // ends the order upstream.
                    worker.setOrder(Unit.Order.STILL);
                    return;
                }
                world.combat.aimAt(worker, found);
                return;
            }
            worker.setPath(path);
            worker.setBattleNetWoodOrder(goalX, goalY);
            // Mark free-prefix so residual settle of a mid-journey tip
            // (length 3+) replans immediately, while short tips keep the
            // action-23 delay (XHuman 2 peon 1530 vs XHuman 7 slot1545).
            world.markBattleNetPointFreePrefix(worker, path, goalX, goalY);
            // No path goal, as the other resource walks do: this order
            // re-plans for itself rather than letting stepMove aim at the tree
            // itself, which no route can ever reach.
            worker.setPathGoal(-1, -1);
        }
        Unit.Order saved = worker.order();
        worker.setBattleNetBorrowedMoveForStep(true);
        worker.setOrder(Unit.Order.MOVE);
        try {
            world.movement.stepMove(worker);
        } finally {
            worker.setBattleNetBorrowedMoveForStep(false);
        }
        if (worker.order() != Unit.Order.DYING) {
            worker.setOrder(saved);
        }
        if (worker.battleNetWoodTerminalRefusalHeading() >= 0
                && worker.battleNetCollisionCounter() == 0
                && worker.pathLength() == 0) {
            // stepMove runs under the borrowed MOVE projection. Finish the
            // terminal forest transition only after restoring HARVEST, so the
            // native sequence selector exposes action 23's 2657 start on the
            // same scheduler visit.
            int gatherStart = world.idle.battleNetSequenceStart(
                    worker, BattleNetSequence.ATTACK_ANIMATION);
            if (gatherStart >= 0) {
                AnimationSet set = worker.type().animationSet();
                Animation attack = set == null ? null
                        : set.get(AnimationSet.State.ATTACK);
                if (attack != null
                        && worker.animation().current() != attack) {
                    worker.animation().switchTo(attack);
                }
                // Animation.switchTo clears the raw sequence projection.
                worker.setBattleNetSequenceOffset(gatherStart);
                worker.setBattleNetAnimationTimer(3);
            }
        }
    }


    /**
     * Chooses the wood path goal for BNE harvest.
     *
     * <p>The resource finder stores the forest square. When the worker is
     * already one step from a free neighbour of that tree, native picks among
     * those one-step approaches by (1) rejecting squares that sit against a
     * building footprint (Chebyshev clearance &lt; 2) when a clearer option
     * exists and (2) preferring the pure Bresenham first step toward the
     * tree among the remaining options. The pathfinder is then aimed at that
     * free neighbour.</p>
     *
     * <p>XOrc 12's peasant at (16,28) bound for (14,29) has two one-step
     * approaches: (15,28) west and (15,29) south-west. The south-west square
     * sits against the elven lumber mill at (14,30) (clearance 1) while west
     * is clear (clearance 2), so west wins. XHuman 2's peon at (15,12) bound
     * for (14,10) has (14,11) and (15,11) both clear of the distant troll
     * mill at (9,11); Bresenham therefore keeps north-west. Maximising raw
     * clearance alone would wrongly prefer (15,11) farther from that mill.
     * </p>
     *
     * <p>When no one-step approach exists, native stores the forest square as
     * order X/Y ({@code unit+0x84}). The pathfinder still needs a free goal
     * square. Walking the reverse Bresenham of {@code 0x429f10} from the
     * forest toward the worker yields the first in-component free square on
     * that ray. XHuman 8 peon 1511 at (2,67) with tree (4,61) needs that
     * reverse-free goal so the first step is north-east; aiming at the tree
     * itself first-steps pure north onto (2,66).</p>
     *
     * <p>The reverse-free square is wrong when it collapses a shallow
     * diagonal into a pure cardinal. XHuman 8 peon 1510 at (4,67) with tree
     * (9,65) has reverse-free (8,66) whose first step is east to (5,67), but
     * native orderXY stays 9,65 and the first step is north-east to (5,66).
     * When reverse-free's first step is pure cardinal while the tree's first
     * step is diagonal, keep the tree so preserveBlockedGoalPrefix / the
     * goal marker can consume the diagonal ray.</p>
     */
    /**
     * Nearest unclaimed forest tile in the eight-neighbour ring, or null.
     *
     * <p>Used when a wood free-prefix ends already adjacent to the mass so
     * the harvest order can re-aim without a ranged search that may miss
     * retail forest flag combinations.</p>
     */
    int[] findAdjacentForest(int x, int y) {
        for (int heading = 0; heading < Direction.COUNT; heading++) {
            int nx = x + Direction.deltaX(heading);
            int ny = y + Direction.deltaY(heading);
            MapField field = world.map.fieldOrNull(nx, ny);
            int index = nx + ny * world.map.width();
            Unit claimant = field == null ? null
                    : world.battleNetClaimedWood.get(index);
            if (claimant != null && !isActiveWoodClaim(claimant, nx, ny)) {
                // Java keeps the terrain flag and the native -2 -> -4 claim
                // projection separately. Mirror FUN_0044df10 here as well as
                // at StartGathering so an abandoned reservation does not hide
                // a valid adjacent tree forever.
                world.battleNetClaimedWood.remove(index);
                claimant = null;
            }
            if (field != null && field.isForest() && claimant == null) {
                return new int[] {nx, ny};
            }
        }
        return null;
    }


    /**
     * Gold free-prefix that ends beside forest far from the mine becomes a
     * wood chop on the adjacent tree.
     *
     * <p>retail-orc-07-idle peon 1567 is gold-assigned, free-prefix NW onto
     * (40,8) toward approach (32,2), then re-aims to (40,7) and draws the
     * first chop SyncRand on fixture cycle 24. The Java port kept Moving
     * true while residual pixels drained, served PF_WAIT ten, and never
     * drew. Convert as soon as the tile has settled with a spent empty
     * route, even mid-pixel-walk.</p>
     *
     * @return whether the order was rewritten to wood
     */
    boolean tryBattleNetGoldFreePrefixForestReaim(
            Unit worker, ResourceInfo info, Unit resource) {
        if (worker == null || info == null || resource == null
                || info.resource() != UnitType.Resource.GOLD || info.terrainHarvester()
                || worker.order() != Unit.Order.HARVEST || worker.returningToDepot()
                || !worker.routeSpent() || worker.pathLength() > 0
                || !worker.battleNetGoldFreePrefix()
                || worker.battleNetGoldFreePrefixLength() < 1
                || worker.battleNetGoldFreePrefixLength() > 2) {
            return false;
        }
        int[] approach = world.battleNetApproachPoint(worker, resource);
        int approachChebyshev = Math.max(
                Math.abs(approach[0] - worker.tileX()),
                Math.abs(approach[1] - worker.tileY()));
        if (approachChebyshev <= 1) {
            return false;
        }
        int[] localTree = findAdjacentForest(worker.tileX(), worker.tileY());
        ResourceInfo wood = worker.type().gathering()
                .get(UnitType.Resource.WOOD);
        if (localTree == null || wood == null) {
            return false;
        }
        // Pixel residual has already settled (!isMoving). Convert to wood and
        // re-enter the harvest step so the first chop SyncRand lands on this
        // same call (fixture cycle 24). An extra order-delay pushed it to 27.
        // Retail still stages 2657 for three cycles then work 2660: claim
        // draw now, second SyncRand three cycles later (fixture 27). Arm the
        // walk-claim so chopInPlace schedules that +3 work swing.
        worker.setWalkHolding(false);
        worker.setWaitCycles(0);
        worker.clearPath();
        beginHarvest(worker, wood, null, localTree[0], localTree[1]);
        worker.setBattleNetWoodWalkClaim(true);
        worker.setRouteSpent(false);
        stepHarvest(worker);
        return true;
    }


    int[] battleNetWoodOrderPoint(
            Unit worker, int treeX, int treeY) {
        boolean[] component = world.battleNetConnectivityCell(worker);
        int workerX = worker.tileX();
        int workerY = worker.tileY();
        int[] bresenhamStep = World.battleNetBresenhamFirstStep(
                workerX, workerY, treeX, treeY);
        if (System.getenv("CHONKCRAFT_TRACE_BNE_PATH") != null) {
            String filter = System.getenv("CHONKCRAFT_TRACE_BNE_PATH").trim();
            if (filter.isEmpty()
                    || worker.id() == Integer.parseInt(filter)) {
                System.err.printf("JWOODGOAL cycle=%d unit=%d worker=%d,%d "
                                + "tree=%d,%d%n",
                        world.cycle, worker.id(), workerX, workerY, treeX, treeY);
            }
        }

        int bestX = -1;
        int bestY = -1;
        int bestClearance = Integer.MIN_VALUE;
        boolean bestIsBresenham = false;
        boolean bestClearOfBuildings = false;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                int ax = treeX + dx;
                int ay = treeY + dy;
                if (!world.map.contains(ax, ay)
                        || !component[ax + ay * world.map.width()]
                        || !world.map.isFootprintFree(ax, ay, 1, 1,
                                worker.movementMask(), worker.blockingFlags())) {
                    continue;
                }
                if (!worker.battleNetWoodReadyPathRequired()
                        && Math.max(Math.abs(ax - workerX),
                                Math.abs(ay - workerY)) != 1) {
                    continue;
                }
                int clearance = world.construction.battleNetApproachBuildingClearance(ax, ay);
                // Clearance 0 is on/in a building; 1 is hard against one.
                // Prefer any option with clearance >= 2 when available.
                boolean clearOfBuildings = clearance >= 2;
                boolean isBresenham = ax == bresenhamStep[0]
                        && ay == bresenhamStep[1];
                boolean better = bestX < 0;
                if (!better && clearOfBuildings && !bestClearOfBuildings) {
                    better = true;
                } else if (!better && clearOfBuildings == bestClearOfBuildings) {
                    if (isBresenham && !bestIsBresenham) {
                        better = true;
                    } else if (isBresenham == bestIsBresenham
                            && clearance > bestClearance) {
                        better = true;
                    }
                }
                if (better) {
                    bestX = ax;
                    bestY = ay;
                    bestClearance = clearance;
                    bestIsBresenham = isBresenham;
                    bestClearOfBuildings = clearOfBuildings;
                }
            }
        }
        if (bestX >= 0) {
            return new int[] {bestX, bestY};
        }

        // Longer walks: first free square on the reverse resource line, unless
        // that clip turns a diagonal first step into a pure cardinal. When the
        // reverse ray crosses building-occupied land first (Human 5 farm at
        // 30,106), continue to the first footprint-free open cell and store
        // the previous building cell as orderXY (native 31,106). Free-only
        // reverse free 32,106 collapsed first steps pure west; tree order
        // packed free-prefix 5556 past 32,107 and cold-committed SW at 35.
        int x = treeX;
        int y = treeY;
        int rawDx = treeX - workerX;
        int rawDy = treeY - workerY;
        int absoluteX = Math.abs(rawDx);
        int absoluteY = Math.abs(rawDy);
        boolean xMajor = absoluteX >= absoluteY;
        int major = xMajor ? absoluteX : absoluteY;
        int minor = xMajor ? absoluteY : absoluteX;
        int majorSign = Integer.signum(xMajor ? rawDx : rawDy);
        int minorSign = Integer.signum(xMajor ? rawDy : rawDx);
        int error = major >> 1;
        if (error == 0) {
            error = 1;
        }
        int freeX = treeX;
        int freeY = treeY;
        boolean foundFree = false;
        int blockedX = treeX;
        int blockedY = treeY;
        boolean sawBlocked = false;
        boolean traceWoodRay = false;
        StringBuilder woodRay = null;
        String tracePath = System.getenv("CHONKCRAFT_TRACE_BNE_PATH");
        if (tracePath != null && (tracePath.isBlank()
                || worker.id() == Integer.parseInt(tracePath.trim()))) {
            traceWoodRay = true;
            woodRay = new StringBuilder();
        }
        while (x != workerX || y != workerY) {
            // Native orderXY on Human 5 peasant 1512 is 31,106 -- a square
            // under the farm footprint at 30,106 -- not the first free
            // neighbour 32,106. The reverse resource line therefore accepts
            // land-connected squares even when a building occupies them;
            // preserveBlockedGoalPrefix then consumes the diagonal ray that
            // free-only goals collapse into pure west (Java 32,106 vs native
            // SW onto 32,107).
            boolean inMap = world.map.contains(x, y);
            boolean inComponent = inMap
                    && component[x + y * world.map.width()];
            if (traceWoodRay) {
                MapField tracedField = world.map.fieldOrNull(x, y);
                woodRay.append(woodRay.isEmpty() ? "" : ",")
                        .append(x).append(':').append(y)
                        .append(":component=").append(inComponent ? 1 : 0)
                        .append(":forest=").append(tracedField != null
                                && tracedField.isForest() ? 1 : 0)
                        .append(":flags=").append(tracedField == null ? "-"
                                : Long.toHexString(tracedField.flags()));
            }
            if (inMap) {
                // Terrain only: unit occupancy must not push reverse-free past
                // the real open tip (XHuman 2 peon corridor). Building tiles
                // on the ray (Human 5 farm) are remembered and yield orderXY
                // as the last blocked cell before open land. The same native
                // boundary includes static terrain. XHuman 12 peon 1365's
                // ray leaves tree (14,89), crosses wall (13,89), then reaches
                // open (12,88); retail stores the wall square as orderXY.
                MapField rayField = world.map.field(x, y);
                boolean resourceTerrain = rayField.isForest();
                boolean blockedTerrain = rayField.hasFlag(TileFlag.BUILDING)
                        || (!inComponent && !resourceTerrain);
                if (inComponent && !blockedTerrain) {
                    if (sawBlocked) {
                        freeX = blockedX;
                        freeY = blockedY;
                    } else {
                        freeX = x;
                        freeY = y;
                    }
                    foundFree = true;
                    break;
                }
                // Do not count the selected tree (or contiguous forest) as
                // an intervening obstacle. Otherwise ordinary tree→open rays
                // would return the tree rather than the measured free skirt.
                if (blockedTerrain) {
                    blockedX = x;
                    blockedY = y;
                    sawBlocked = true;
                }
            }
            int minorStep = 0;
            error -= minor;
            if (error < 1) {
                minorStep = minorSign;
                error += major;
            }
            int stepDx = xMajor ? majorSign : minorStep;
            int stepDy = xMajor ? minorStep : majorSign;
            x -= stepDx;
            y -= stepDy;
        }
        if (traceWoodRay) {
            System.err.printf("JWOODRAY cycle=%d unit=%d ray=%s"
                            + " found=%d free=%d,%d blocked=%d,%d%n",
                    world.cycle, worker.id(), woodRay,
                    foundFree ? 1 : 0, freeX, freeY, blockedX, blockedY);
        }
        if (!foundFree || (freeX == treeX && freeY == treeY)
                || (freeX == workerX && freeY == workerY)) {
            // Free-prefix tip re-aim: standing on the free tip after a farm
            // segment must target the tree again (Human 5 at 32,107).
            return new int[] {treeX, treeY};
        }
        int[] freeStep = World.battleNetBresenhamFirstStep(
                workerX, workerY, freeX, freeY);
        int[] treeStep = World.battleNetBresenhamFirstStep(
                workerX, workerY, treeX, treeY);
        boolean freeDiagonal = freeStep[0] != workerX && freeStep[1] != workerY;
        boolean treeDiagonal = treeStep[0] != workerX && treeStep[1] != workerY;
        // An open reverse-free east (4,67→8,66) must not beat the tree's
        // north-east (4,67→9,65). A static blocker remembered as native
        // orderXY is different: XHuman 12 peon 1376 stores wall square
        // (13,88), whose one-step wall face is NE, rather than expanding the
        // tree-directed route to NE,E,E,SE. That one-byte prefix settles and
        // gives action 23 a fresh three-call redraw on the opposite face.
        // Reverse-free north-east (2,67→4,62) must keep winning over the
        // tree's pure north (2,67→4,61).
        if (!sawBlocked && !freeDiagonal && treeDiagonal) {
            return new int[] {treeX, treeY};
        }
        // Human 8 peasant 1499: tree 85,83 and reverse-free 86,82 both take a
        // diagonal first step, but native stores the forest square. Preferring
        // free there collapses the path to pure south. Only when reverse-free
        // is a walkable one-tile skirt AND both first steps stay diagonal;
        // XHuman 8's reverse-free north-east vs pure-north tree must still
        // keep free.
        if (freeDiagonal && treeDiagonal
                && Math.max(Math.abs(freeX - treeX),
                        Math.abs(freeY - treeY)) <= 1
                && world.map.isFootprintFree(freeX, freeY, 1, 1,
                        worker.movementMask(), worker.blockingFlags())) {
            return new int[] {treeX, treeY};
        }
        return new int[] {freeX, freeY};
    }


    /**
     * Sends a worker to the next patch of what it was gathering.
     *
     * <p>Implements {@code COrder_Resource::FindAnotherResource}. Without it a
     * worker gathers until the square it was told about is spent and then
     * stops for good -- which for wood is every single tree, because felling
     * one is the normal end of a trip. The player ends up re-issuing the order
     * after every load, which is most of the reason a worker exists.
     *
     * @return whether it found somewhere to carry on
     */
    boolean findAnotherResource(Unit worker, ResourceInfo info) {
        if (info.terrainHarvester()) {
            int[] found = world.findTerrainType(worker, worker.tileX(), worker.tileY(),
                    World.ANOTHER_RESOURCE_RANGE);
            if (found == null) {
                return false;
            }
            worker.setResourceUnit(null);
            worker.setResourceTile(found[0], found[1]);
            worker.clearPath();
            worker.setOrder(Unit.Order.HARVEST);
            return true;
        }

        Unit nearest = null;
        int nearestDistance = Integer.MAX_VALUE;
        for (Unit candidate : world.units) {
            if (!candidate.isAlive() || !candidate.isOnMap()
                    || !providesResource(candidate, info.resource())
                    // A source with nothing in it is not somewhere to carry
                    // on: handing it back is how a worker ends up walking into
                    // the same empty mine for the rest of the game.
                    || candidate.resourcesHeld() <= 0) {
                continue;
            }
            int distance = worker.distanceTo(candidate);
            if (distance <= World.ANOTHER_RESOURCE_RANGE && distance < nearestDistance) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }
        if (nearest == null) {
            return false;
        }
        worker.setResourceUnit(nearest);
        worker.setResourceTile(nearest.tileX(), nearest.tileY());
        worker.clearPath();
        worker.setOrder(Unit.Order.HARVEST);
        if (info.resource() == UnitType.Resource.OIL) {
            boolean adjacent = worker.distanceTo(nearest) <= 1;
            worker.setBattleNetOilStartedAdjacent(adjacent);
            worker.setBattleNetOilAction(adjacent
                    ? Unit.BattleNetOilAction.FINAL_APPROACH
                    : Unit.BattleNetOilAction.TO_RESOURCE);
            worker.setBattleNetOilActionTicks(adjacent ? 3 : 0);
        }
        return true;
    }


    /**
     * Restores a BNE forest claim ({@code -4} → {@code -2}) when the owner
     * stops swinging on that square. Ports the map-side of {@code FUN_0044df10}.
     */
    void releaseBattleNetWoodClaim(Unit worker, int x, int y) {
        if (worker == null || !world.map.contains(x, y)) {
            return;
        }
        int index = x + y * world.map.width();
        Unit owner = world.battleNetClaimedWood.get(index);
        if (owner == worker) {
            world.battleNetClaimedWood.remove(index);
        }
    }

    /** Whether a native-style forest reservation still has a live owner. */
    private boolean isActiveWoodClaim(Unit worker, int x, int y) {
        return worker != null
                && worker.isAlive()
                && worker.isOnMap()
                && worker.gatherClockStarted()
                // A flush-on command waits behind an unbreakable axe swing in
                // retail. Keep the claim through that committed swing even
                // if Java has already installed the replacement order.
                && (worker.order() == Unit.Order.HARVEST
                        || worker.animation().unbreakable())
                && worker.resourceUnit() == null
                && worker.resourceTileX() == x
                && worker.resourceTileY() == y;
    }


    /**
     * Finds the tree selected by the active engine's AI resource assignment.
     *
     * <p>Retail BNE's {@code 0x44e0f0} does not use ChonkCraft's terrain flood.
     * It calls the clockwise square-ring walker at {@code 0x443cd0}, scanning
     * east, south, west and north from the north-west corner of each growing
     * ring. Its callback {@code 0x44e150} accepts the first forest square
     * touching the worker's fixed terrain component. Human mission 13 is the
     * small distinguishing fixture: from (55,53), the flood picks (50,52),
     * while the native northbound final edge reaches (50,55) first.</p>
     */
    int[] findAiWood(Unit worker, int range) {
        if (worker == null || !worker.isOnMap()) {
            return null;
        }

        boolean[] connected = world.battleNetConnectivityCell(worker);
        boolean failedGoldReady = worker.battleNetWoodReadyPathRequired();
        // This flag is also the durable action-23 retry marker. During the
        // synchronous UnitReady lookup it is only a context bit; consume it
        // now, and let assignHarvester re-arm it only for the adjacent-tree
        // branch that actually needs the native path gate.
        worker.setBattleNetWoodReadyPathRequired(false);
        int maximum = (world.map.width() * 3) >> 2;
        int[] ordinary = findBattleNetWoodFromCenter(
                worker, connected, worker.tileX(), worker.tileY(), maximum,
                false);
        if (!failedGoldReady || ordinary == null
                || Math.max(Math.abs(ordinary[0] - worker.tileX()),
                        Math.abs(ordinary[1] - worker.tileY())) > 1) {
            return ordinary;
        }

        // UnitReady after a served gold walk whose ordinary result is already
        // adjacent cannot take the standing chop shortcut: native supplies
        // the row immediately north to its terrain-action square ring and
        // keeps action 23 path-gated. Human 5 peon 1567 at (105,48) thereby
        // chooses (104,46), not the adjacent southern tree (104,47). A fresh
        // zero-step constructor never sets this context (Human 13 peon 1393),
        // while distant fallbacks such as XHuman 12 retain the ordinary
        // anchor-centred result and route.
        int[] shifted = findBattleNetWoodFromCenter(
                worker, connected, worker.tileX(), worker.tileY() - 1,
                maximum, false);
        if (shifted == null) {
            shifted = ordinary;
        }
        return new int[] {shifted[0], shifted[1], 1};
    }

    /**
     * Native {@code 0x44e230}: replacement search after StartGathering finds
     * its terrain square already changed from -2 to -4 by another worker.
     * The constructor passes the worker position and literal range 15 to the
     * same {@code 0x443cd0}/{@code 0x44e150} clockwise ring used by AI wood.
     */
    int[] findClaimedWoodReplacement(Unit worker) {
        if (worker == null || !worker.isOnMap()) {
            return null;
        }
        return findBattleNetWoodFromCenter(worker,
                world.battleNetConnectivityCell(worker),
                worker.tileX(), worker.tileY(), 15, true);
    }


    private int[] findBattleNetWoodFromCenter(Unit worker,
            boolean[] connected, int x, int y, int maximum,
            boolean skipClaimed) {
        int sideLength = 3;
        int[] dx = {1, 0, -1, 0};
        int[] dy = {0, 1, 0, -1};
        while (sideLength < maximum) {
            x--;
            y--;
            boolean ringTouchesComponent = false;
            for (int direction = 0; direction < 4; direction++) {
                for (int travelled = 0; travelled < sideLength; travelled++) {
                    // The native ring walker suppresses the repeated corner
                    // at the start of sides two through four.
                    if ((direction == 0 || travelled != 0) && world.map.contains(x, y)) {
                        int index = x + y * world.map.width();
                        if (connected[index]) {
                            ringTouchesComponent = true;
                        } else if (world.map.field(x, y).isForest()
                                // Native changes a claimed forest component
                                // from -2 to -4, which 0x44e150 rejects.
                                && (!skipClaimed
                                        || !world.battleNetClaimedWood
                                                .containsKey(index))
                                && battleNetWoodTouchesComponent(
                                        worker, x, y, connected,
                                        skipClaimed)) {
                            return new int[] {x, y};
                        }
                    }
                    x += dx[direction];
                    y += dy[direction];
                }
                x -= dx[direction];
                y -= dy[direction];
            }
            if (!ringTouchesComponent) {
                return null;
            }
            sideLength += 2;
        }
        return null;
    }


    /** The 3-by-3 component and terrain test in BNE callback 0x44e150. */
    boolean battleNetWoodTouchesComponent(Unit worker, int x, int y,
            boolean[] connected, boolean requireUnoccupiedFace) {
        for (int ny = y - 1; ny <= y + 1; ny++) {
            for (int nx = x - 1; nx <= x + 1; nx++) {
                if (!world.map.contains(nx, ny)) {
                    continue;
                }
                int index = nx + ny * world.map.width();
                if (connected[index]
                        && world.battleNetTerrainPassable(worker, nx, ny)
                        // The claimed-tree constructor at native 0x44e230
                        // reaches 0x44e150 with the live 0x09ce square mask.
                        // Its 0x0100 land-body bit means an occupied connected
                        // face cannot make the replacement tree eligible.
                        // Keep Java's older UnitReady/AI reconstruction on its
                        // terrain-component view: those callers synthesize
                        // native pre-search state separately (including the
                        // failed-gold north-row shift) and are not this
                        // StartGathering replacement boundary.
                        && (!requireUnoccupiedFace
                                || !world.map.field(nx, ny)
                                        .hasFlag(worker.blockingFlags()))) {
                    return true;
                }
            }
        }
        return false;
    }


    /**
     * BNE 2.02's native three-tile hall-to-mine clearance.
     *
     * <p>{@code FUN_00416c40} sends every type carrying native flag
     * {@code 0x1000} through {@code FUN_00416fa0(type, x, y, 3, 0x400000)}.
     * The latter does not measure the Euclidean footprint distance used by
     * modern LegacyEngine. It expands the proposed and existing rectangles by
     * three along each axis and rejects the site when those rectangles
     * overlap. On {@code XHuman10}, that is the exact difference between the
     * tempting diagonal hall site at 34,67 (native result 3) and the accepted
     * site at 40,79 (native result 0).</p>
     */
    boolean hasBattleNetGoldMineClearance(UnitType what,
            int tileX, int tileY) {
        int width = Math.max(1, what.tileWidth());
        int height = Math.max(1, what.tileHeight());
        int clearance = 3;
        for (Unit existing : world.units) {
            if (!existing.isOnMap() || existing.isDying() || existing.type() == null
                    || !"unit-gold-mine".equals(existing.type().ident())) {
                continue;
            }
            int existingWidth = Math.max(1, existing.type().tileWidth());
            int existingHeight = Math.max(1, existing.type().tileHeight());
            boolean overlapsX = existing.tileX() < tileX + width + clearance
                    && tileX < existing.tileX() + existingWidth + clearance;
            boolean overlapsY = existing.tileY() < tileY + height + clearance
                    && tileY < existing.tileY() + existingHeight + clearance;
            if (overlapsX && overlapsY) {
                return false;
            }
        }
        return true;
    }


    /** Whether the mover has reached BNE's exact point inside a resource. */
    boolean atBattleNetResourceApproach(Unit worker, Unit target) {
        int[] point = world.battleNetApproachPoint(worker, target);
        return worker.tileX() == point[0] && worker.tileY() == point[1];
    }


    /**
     * Enters an oil platform after the cover wait and action-25 BOARD delay.
     */
    void enterBattleNetOilPlatform(Unit worker, ResourceInfo info,
            Unit resource) {
        if (worker == null || resource == null || info == null) {
            return;
        }
        int[] boardingPoint = world.battleNetApproachPoint(worker, resource);
        // COrder_Resource retains its final platform order point while the
        // tanker is hidden in action 26. StopGathering later measures the
        // exit face from this point, not from the platform's top-left tile.
        // Human 7 slot 1504 boards from (56,74), stores (57,74), and uses
        // that exact southeast vector to leave east at (60,74).
        worker.setOrderTarget(boardingPoint[0], boardingPoint[1]);
        worker.clearPath();
        worker.setWalkHolding(false);
        world.movement.resetDisplacement(worker);
        if (worker.heldResource() != info.resource()) {
            worker.setCarried(0);
            worker.setHeldResource(info.resource());
        }
        if (System.getenv("CHONKCRAFT_TRACE_BNE_OIL") != null) {
            System.err.printf("JBNEOIL enter cycle=%d unit=%d at=%d,%d "
                            + "platform=%d,%d%n",
                    world.cycle, worker.id(), worker.tileX(), worker.tileY(),
                    resource.tileX(), resource.tileY());
        }
        enterResource(worker, resource);
        worker.setBattleNetOilAction(Unit.BattleNetOilAction.INSIDE_RESOURCE);
        worker.setBattleNetOilActionTicks(0);
        worker.setWaitCycles(Math.max(0, info.waitAtResource() - 1));
    }


    /** Whether a large BNE tanker anchor now covers its platform entry point. */
    boolean battleNetOilTankerCoversApproach(Unit worker, Unit target) {
        if (!worker.type().canGather()
                || !worker.type().gathering().containsKey(UnitType.Resource.OIL)
                || worker.returningToDepot() || worker.removed()) {
            return false;
        }
        int[] point = world.battleNetApproachPoint(worker, target);
        int width = Math.max(1, worker.type().tileWidth());
        int height = Math.max(1, worker.type().tileHeight());
        return point[0] >= worker.tileX()
                && point[0] < worker.tileX() + width
                && point[1] >= worker.tileY()
                && point[1] < worker.tileY() + height;
    }


    /**
     * Board seat one Chebyshev step from the approach point (Orc 14 1565 at
     * 6,6 / 1575 at 6,4 with approach 5,5) -- native stages BOARD without
     * footprint cover of the entry. Residual path is allowed: requiring
     * pathLength==0 walked tankers off the seat toward 5,5.
     */
    boolean battleNetOilTankerBoardSeat(Unit worker, Unit target) {
        if (!worker.type().canGather()
                || !worker.type().gathering().containsKey(UnitType.Resource.OIL)
                || worker.returningToDepot() || worker.removed()) {
            return false;
        }
        if (battleNetOilTankerCoversApproach(worker, target)) {
            return false;
        }
        int[] point = world.battleNetApproachPoint(worker, target);
        int dx = Math.abs(worker.tileX() - point[0]);
        int dy = Math.abs(worker.tileY() - point[1]);
        return Math.max(dx, dy) == 1;
    }


    /**
     * Whether a large BNE tanker is ready to stage platform BOARD.
     *
     * <p>Footprint cover of the approach point is the XOrc 8 path. Orc 14
     * tankers also stage BOARD from a seat one Chebyshev step off the
     * approach point.
     */
    boolean battleNetOilTankerReachedApproach(Unit worker, Unit target) {
        // The resource order's range is measured between footprints.  A
        // two-by-two tanker stopped beside the west or north edge can be one
        // footprint tile from the platform while its top-left anchor is two
        // coordinates from the projected entry point.  Testing only that
        // anchor stranded a second tanker forever when another hull occupied
        // the last cached step: movement correctly answered REACHED, but the
        // resource action refused to promote 23 -> 25.  Retail's range-one
        // order accepts every point on this footprint ring; the cover and
        // named board-seat predicates below document two witnessed members
        // of the same set.
        return worker.distanceTo(target) <= 1
                || battleNetOilTankerCoversApproach(worker, target)
                || battleNetOilTankerBoardSeat(worker, target);
    }


    /**
     * The nearest mine a worker could actually walk to, scored against its
     * depot.
     *
     * <p>Implements {@code UnitFindResource} and {@code ResourceUnitFinder}, on the shape the computer
     * players call it: find the nearest depot first, seed
     * the flood around the depot when there is one and around the worker when
     * there is not, and walk outwards over ground the worker can cross. A mine
     * is noticed on any square the flood visits -- its own ground is
     * impassable, so the test comes before the square is allowed to spread --
     * and the one that comes back is the nearest by reachable terrain, scored
     * by its distance to the depot, never by the straight line.
     *
     * <p>The seeding is why the answer moves when a building goes up, and it
     * is the whole of what was wrong with the straight line this replaced. On
     * {@code maps/demo/demo02} an oil platform stands at 12,22, two squares
     * from the tanker at 14,20 -- and no water connects it to the shipyard the
     * flood starts from, so upstream answers "no mine" and sends the tanker
     * exploring. Probed from the real binary: {@code HARVEST} reports
     * {@code depot=shipyard mine=none} at cycles 38 through 758, and
     * {@code depot=refinery mine=oil-platform} at 878, the AI having built a
     * refinery whose water does reach it. A straight-line finder answers the
     * platform from the first ask, which takes that map's first divergence
     * from cycle 879 back to 9.
     *
     * <p>The census asks with {@code check_usage} on, and the cost is a
     * triple compared in order -- waiting workers, distance to the depot,
     * assigned workers -- with the walk ending early only on a perfect
     * nought of all three ({@code ResourceUnitFinder_Cost}). The assigned
     * count is what makes a computer player skip a crowded mine: on
     * campaigns/human/level08h the drafted siege peasant stands ten squares
     * from a mine five rival peons already work, and upstream sends it
     * across the map to the empty one at 21,28 -- cost 0/0/0 beating 0/0/5
     * -- where this implementation used to take the near one and diverge at 132. The
     * assigned number is upstream's {@code AssignedWorkers.size() -
     * MaxOnBoard} in unsigned arithmetic, transcribed underflow and all,
     * though no mine in the shipped data carries a {@code MaxOnBoard}.
     *
     * <p>The waiting count is derived as nought: it counts harvesters
     * standing at the door of a mine that is full or still being built
     * ({@code StartGathering}'s two ten-cycle wait arms), and this implementation does
     * not model either queue. The bound is an oil platform under
     * construction with a tanker already sent to it. The depot itself is
     * the straight-line {@link #nearestDepot} rather than
     * {@code FindDeposit}'s travel-distance refinement, which can differ only
     * when two depots' straight-line and walked orders disagree.
     */
    Unit findResourceUnit(Unit worker, UnitType.Resource resource, int range) {
        ResourceInfo info = worker.type().gathering().get(resource);
        if (info == null) {
            return null;
        }
        Unit depot = bestDepotByTravel(worker, resource, range);
        return findResourceUnit(worker, info, depot != null ? depot : worker, depot, resource,
                range);
    }


    /**
     * Finds a resource by flooding from the gatherer rather than from a depot.
     *
     * <p>Retail BNE's per-unit tanker-ready callback uses the tanker's water
     * region as its seed. The later ChonkCraft AI census intentionally uses
     * {@link #findResourceUnit(Unit, UnitType.Resource, int)} and its depot
     * seed, so these are separate public questions rather than a profile flag
     * hidden inside the shared finder.</p>
     */
    Unit findResourceUnitFromWorker(
            Unit worker, UnitType.Resource resource, int range) {
        ResourceInfo info = worker.type().gathering().get(resource);
        if (info == null) {
            return null;
        }
        return findResourceUnit(worker, info, worker, null, resource, range);
    }


    /**
     * Selects the gold mine used by BNE's per-unit ready callback.
     *
     * <p>{@code 0x43934e-0x4394b4} does not call the ordinary AI resource
     * finder. It first walks the owner's unit list for the nearest completed
     * gold depot on the worker's terrain component. It then walks the gold
     * mine list and minimizes {@code depotDistance + workerDistance / 2},
     * retaining the first equal-cost mine. In particular, neither leg asks
     * A* whether the worker can get around units and buildings currently in
     * its way. Human 13 begins with a peon boxed into its fortress crowd: the
     * ordinary finder rejects that fortress as unreachable and falls back to
     * wood, while retail assigns the nearby mine.</p>
     *
     * <p>The same finder is called from hidden depot action 26 before the
     * worker is placed back on the map. Java's broad {@link Unit#isAlive()}
     * includes {@code !removed}, but a healthy worker with a valid worksite is
     * precisely the contained native state: Human 13 slot 1547 selects mine
     * 1544 and its east-face dropout during fixture 523. Detached or dying
     * off-map units remain ineligible.</p>
     */
    Unit findBattleNetReadyGoldMine(Unit worker) {
        boolean contained = worker != null && !worker.isOnMap()
                && worker.worksite() != null && worker.hitPoints() > 0
                && worker.order() != Unit.Order.DYING;
        if (worker == null || (!worker.isAlive() && !contained)) {
            return null;
        }
        boolean[] component = world.battleNetConnectivityCell(worker);
        Unit depot = null;
        int depotDistance = Integer.MAX_VALUE;
        for (Unit candidate : world.playerUnits(worker.player())) {
            if (!candidate.isAlive() || !candidate.isOnMap()
                    || candidate.order() == Unit.Order.UNDER_CONSTRUCTION
                    || !candidate.type().storesResource(UnitType.Resource.GOLD)
                    || !component[candidate.tileX()
                            + candidate.tileY() * world.map.width()]) {
                continue;
            }
            int distance = world.battleNetDistance(worker, candidate);
            // FUN_004384c0 replaces the current depot on equality.
            if (distance <= depotDistance) {
                depot = candidate;
                depotDistance = distance;
            }
        }
        if (depot == null) {
            return null;
        }

        Unit best = null;
        int bestCost = 0xffff;
        for (Unit candidate : world.units) {
            if (!candidate.isAlive() || !candidate.isOnMap()
                    || candidate.type().givesResource() != UnitType.Resource.GOLD
                    || candidate.resourcesHeld() == 0
                    || !component[candidate.tileX()
                            + candidate.tileY() * world.map.width()]) {
                continue;
            }
            int cost = world.battleNetDistance(depot, candidate)
                    + world.battleNetDistance(worker, candidate) / 2;
            if (cost < bestCost) {
                best = candidate;
                bestCost = cost;
            }
        }
        if (System.getenv("CHONKCRAFT_TRACE_FINDRES") != null) {
            System.err.printf("JBNEREADYGOLD cycle=%d worker=%d depot=%d"
                            + " mine=%d cost=%d%n",
                    world.cycle, worker.id(), depot.id(), best == null ? -1 : best.id(),
                    bestCost);
        }
        return best;
    }


    /**
     * Selects the platform used by BNE's constructor-ready tanker callback.
     *
     * <p>This is not {@link #findResourceUnitFromWorker}. Native
     * {@code FUN_00438f40} first chooses the nearest reachable shipyard or
     * refinery. The scan at {@code 0x4393f7} then weighs each oil patch by
     * its distance to that base plus half its distance to the tanker. An
     * existing platform overlays the chosen patch, so the subsequent
     * resource-order lookup resolves it to the platform unit. Human mission
     * 7 is the distinguishing case: the platform beside the tanker is not
     * the one beside the refinery, and retail chooses the latter. The ready
     * callback also runs while a live tanker is still contained in its depot;
     * expansion Human mission 5 proves that the selected platform owns the
     * exit face before the tanker is placed back on the map. The scan is
     * owner-local, not merely alliance-local: {@code 0x439b1f} opens the
     * tanker's owner's roster and {@code 0x439b4c} rejects a candidate whose
     * owner byte differs. Expansion Human 8 distinguishes that test because
     * player three and player seven are allied computers, but only player
     * seven owns the otherwise reachable platform.</p>
     */
    Unit findBattleNetReadyOilPlatform(Unit tanker) {
        boolean liveContainedTanker = tanker != null
                && world.battleNetDepotReadyDispatching()
                && tanker.hitPoints() > 0 && tanker.order() != Unit.Order.DYING;
        if (tanker == null || (!tanker.isAlive() && !liveContainedTanker)
                || (!tanker.isOnMap() && !liveContainedTanker)) {
            return null;
        }
        boolean[] component = world.battleNetConnectivityCell(tanker);
        Unit base = null;
        int baseDistance = Integer.MAX_VALUE;
        for (Unit candidate : world.playerUnits(tanker.player())) {
            if (!candidate.isAlive() || !candidate.isOnMap()
                    || !World.isBattleNetNavalBase(candidate.type().ident())
                    || !world.battleNetFootprintTouchesComponent(candidate, component)) {
                continue;
            }
            int distance = candidate.distanceTo(tanker.tileX(), tanker.tileY());
            // Native replaces on equality while walking the owner's list.
            if (distance <= baseDistance) {
                base = candidate;
                baseDistance = distance;
            }
        }
        if (base == null) {
            return null;
        }

        Unit best = null;
        int bestCost = 0xffff;
        for (Unit candidate : world.units) {
            if (!candidate.isAlive() || !candidate.isOnMap()
                    || candidate.player() != tanker.player()
                    || !isBattleNetOilPlatform(candidate.type().ident())
                    || !world.map.contains(candidate.tileX(), candidate.tileY())
                    || !component[candidate.tileX()
                            + candidate.tileY() * world.map.width()]) {
                continue;
            }
            int cost = candidate.distanceTo(base.tileX(), base.tileY())
                    + tanker.distanceTo(candidate.tileX(), candidate.tileY()) / 2;
            // The oil-patch walk retains the first equal-cost candidate.
            if (cost < bestCost) {
                best = candidate;
                bestCost = cost;
            }
        }
        if (System.getenv("CHONKCRAFT_TRACE_FINDRES") != null) {
            System.err.printf("JBNEREADYOIL cycle=%d worker=%d base=%d "
                            + "platform=%d cost=%d%n",
                    world.cycle, tanker.id(), base.id(), best == null ? -1 : best.id(),
                    bestCost);
        }
        return best;
    }


    /**
     * The oil patch 0x439bb6 founds a platform on when the harvest walk
     * found none.
     *
     * <p>Native finds the shipyard/refinery with {@code 0x438f40}, then
     * walks the global list for type {@code 0x5d} (oil-patch), keeps the
     * strictly nearer patch to that depot's origin, and if {@code 0x40dcd0}
     * says the bank covers an oil platform, issues action 28
     * ({@code 0x436a80}) on the patch. Human 14's refinery at 93,71
     * therefore sends the first tanker to 105,49, not the western patch
     * at 15,57. A tanker that already has a reachable platform never
     * reaches this walk.
     */
    Unit findBattleNetReadyOilPatch(Unit tanker) {
        if (tanker == null || !tanker.isAlive() || !tanker.isOnMap()) {
            return null;
        }
        boolean[] component = world.battleNetConnectivityCell(tanker);
        Unit base = null;
        int baseDistance = Integer.MAX_VALUE;
        for (Unit candidate : world.playerUnits(tanker.player())) {
            if (!candidate.isAlive() || !candidate.isOnMap()
                    || !World.isBattleNetNavalBase(candidate.type().ident())
                    || !world.battleNetFootprintTouchesComponent(
                            candidate, component)) {
                continue;
            }
            int distance = candidate.distanceTo(tanker.tileX(), tanker.tileY());
            if (distance <= baseDistance) {
                base = candidate;
                baseDistance = distance;
            }
        }
        if (base == null) {
            return null;
        }
        Unit best = null;
        int bestDistance = 0xffff;
        for (Unit candidate : world.units) {
            if (!candidate.isAlive() || !candidate.isOnMap()
                    || !"unit-oil-patch".equals(candidate.type().ident())) {
                continue;
            }
            int distance = candidate.distanceTo(base.tileX(), base.tileY());
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }


    /**
     * The resource order's own look for another mine.
     *
     * <p>{@code UnitFindResource(unit, unit, 15, resource, AiEnabled)}: seeded at the worker, no depot in the
     * cost, so the triple degenerates to the assigned count and the wave's
     * own order.
     */
    Unit findAnotherMine(Unit worker, UnitType.Resource resource, int range) {
        ResourceInfo info = worker.type().gathering().get(resource);
        if (info == null) {
            return null;
        }
        return findResourceUnit(worker, info, worker, null, resource, range);
    }


    Unit findResourceUnit(Unit worker, ResourceInfo info, Unit seedUnit, Unit depot,
            UnitType.Resource resource, int range) {

        long mask = worker.movementMask();
        // The three unit-occupancy bits are cleared out of the mask, as the
        // finder's own constructor does: somebody standing in the way is a
        // reason to walk round, not a reason to call a mine unreachable.
        long blocking = worker.blockingFlags()
                & ~(TileFlag.LAND_UNIT | TileFlag.AIR_UNIT | TileFlag.SEA_UNIT);

        int width = world.map.width();
        int[] steps = new int[width * world.map.height()];
        int[] queue = new int[steps.length];
        int head = 0;
        int tail = 0;
        // TerrainTraversal::PushUnitPosAndNeighboor: a box from one square
        // before the unit's corner, stopping on != one past its far corner, so
        // the far row and column are never seeded and are reached the ordinary
        // way. The asymmetry is upstream's own.
        int seedWidth = Math.max(1, seedUnit.type().tileWidth());
        int seedHeight = Math.max(1, seedUnit.type().tileHeight());
        for (int y = seedUnit.tileY() - 1; y != seedUnit.tileY() + seedHeight; y++) {
            for (int x = seedUnit.tileX() - 1; x != seedUnit.tileX() + seedWidth; x++) {
                if (world.map.contains(x, y) && steps[x + y * width] == 0) {
                    steps[x + y * width] = 1;
                    queue[tail++] = x + y * width;
                }
            }
        }

        Unit best = null;
        long bestDistance = Long.MAX_VALUE;
        long bestAssigned = Long.MAX_VALUE;
        while (head < tail) {
            int at = queue[head++];
            int x = at % width;
            int y = at / width;
            Unit mine = resourceUnitOn(x, y, resource);
            if (mine != null && mine != best && mineIsUsable(worker, info, mine)) {
                long distance = depot != null ? mine.distanceTo(depot) : 0;
                long assigned = (world.countAssignedWorkers(mine)
                        - mine.type().maxOnBoard()) & 0xFFFFFFFFL;
                String findResourceTrace = System.getenv("CHONKCRAFT_TRACE_FINDRES");
                if (findResourceTrace != null
                        && worker.id() == Integer.parseInt(findResourceTrace)) {
                    System.err.printf("JFINDRES cycle=%d worker=%d mine=%d at=%d,%d"
                                    + " cost=0/%d/%d best=%d/%d%n",
                            world.cycle, worker.id(), mine.id(), mine.tileX(), mine.tileY(),
                            distance, assigned, bestDistance, bestAssigned);
                }
                if (distance < bestDistance
                        || (distance == bestDistance && assigned < bestAssigned)) {
                    best = mine;
                    // IsMin ends the walk where it stands; anything short of
                    // perfect keeps the wave rolling for a better mine.
                    if (distance == 0 && assigned == 0) {
                        return best;
                    }
                    bestDistance = distance;
                    bestAssigned = assigned;
                }
            }
            if (!world.map.isFootprintFree(x, y, 1, 1, mask, blocking) || steps[at] > range) {
                continue;
            }
            for (int i = 0; i < World.FILL_NEIGHBOURS.length; i += 2) {
                int nx = x + World.FILL_NEIGHBOURS[i];
                int ny = y + World.FILL_NEIGHBOURS[i + 1];
                if (!world.map.contains(nx, ny) || steps[nx + ny * width] != 0) {
                    continue;
                }
                steps[nx + ny * width] = steps[at] + 1;
                queue[tail++] = nx + ny * width;
            }
        }
        return best;
    }


    /**
     * The depot selected by BNE's {@code FindDeposit} at {@code 0x438770}.
     *
     * <p>The candidate must share the worker's fixed terrain component. Sea
     * workers use {@code 0x416980}, which accepts any square of the depot's
     * footprint; land workers compare the component word at the depot's
     * origin. Among those candidates, both native arms call {@code 0x416b10}
     * and keep the later owner-roster entry on equality. That distance is the
     * footprint-aware Chebyshev measure, not the length of a route and not
     * ChonkCraft's Euclidean {@link Unit#distanceTo(Unit)}.
     *
     * <p>This distinction is visible between two loaded tankers. XOrc 8's
     * refinery is genuinely nearer than its shipyard under {@code 0x416b10},
     * while XHuman 6's south-west shipyard is nearer than its refinery. A
     * route-cost refinement selected the refinery in both missions and sent
     * the latter tanker north on fixture 344 instead of north-west.</p>
     */
    Unit bestDepotByTravel(Unit worker, UnitType.Resource resource, int range) {
        // FindDeposit reads the contained worker at its container's recorded
        // tile. Using the worksite also preserves that contract for restored
        // Java state whose hidden worker anchor has not yet been repaired.
        Unit measureFrom = !worker.isOnMap() && worker.worksite() != null
                ? worker.worksite() : worker;
        // The hidden tanker keeps its sea type while its recorded x/y name
        // the platform. Seeding from the platform object would instead apply
        // a building's land mask and reject every reachable naval depot.
        boolean[] component = world.battleNetConnectivityCell(worker);
        boolean seaWorker = worker.type() != null && worker.type().seaUnit();
        Unit best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Unit candidate : world.playerUnits(worker.player())) {
            if (!candidate.isAlive() || !candidate.isOnMap()
                    || !candidate.type().storesResource(resource)
                    || candidate.order() == Unit.Order.UNDER_CONSTRUCTION) {
                continue;
            }
            boolean sameComponent = seaWorker
                    ? world.battleNetFootprintTouchesComponent(
                            candidate, component)
                    : component[candidate.tileX()
                            + candidate.tileY() * world.map.width()];
            if (!sameComponent) {
                continue;
            }
            int distance = world.battleNetDistance(measureFrom, candidate);
            if (distance <= range && distance <= bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }


    /**
     * The mine standing on a square, if one is.
     *
     * <p>{@code CResourceFinder} with {@code mine_on_top} set
     * The game gives the resource, still holds some,
     * can be harvested -- which is what separates a platform from the patch
     * under it -- and is alive on the map, construction sites allowed.
     */
    Unit resourceUnitOn(int x, int y, UnitType.Resource resource) {
        List<Unit> cached = world.unitCache.get(x + y * world.map.width());
        if (cached == null) {
            return null;
        }
        for (Unit candidate : cached) {
            UnitType type = candidate.type();
            if (type != null && type.givesResource() == resource
                    && candidate.resourcesHeld() != 0 && type.canHarvest()
                    && candidate.isAlive() && candidate.isOnMap()) {
                return candidate;
            }
        }
        return null;
    }


    /**
     * Whether this worker may take from this mine.
     *
     * <p>{@code ResourceUnitFinder::MineIsUsable}:
     * anybody's, if the resource is worked from outside; otherwise the
     * neutral player's, the worker's own, or a mutual ally's.
     */
    boolean mineIsUsable(Unit worker, ResourceInfo info, Unit mine) {
        return info.harvestFromOutside()
                || mine.player() == World.NEUTRAL_PLAYER
                || mine.player() == worker.player()
                || (world.isAllied(worker.player(), mine.player())
                        && world.isAllied(mine.player(), worker.player()));
    }


    /** Whether a unit is mid-swing at a resource it is working in the open. */
    boolean isChopping(Unit unit) {
        if (unit.order() != Unit.Order.HARVEST || !unit.isOnMap() || unit.type() == null) {
            return false;
        }
        AnimationSet set = unit.type().animationSet();
        Animation harvest = set == null ? null : set.get(AnimationSet.State.HARVEST);
        return harvest != null && unit.animation().current() == harvest;
    }


    /** The chopping or mining animation, or the still one if there is none. */
    Animation harvestAnimation(Unit unit) {
        AnimationSet set = unit.type() == null ? null : unit.type().animationSet();
        if (set == null) {
            return null;
        }
        Animation harvest = set.get(AnimationSet.State.HARVEST);
        return harvest != null ? harvest : set.getOrStill(AnimationSet.State.STILL);
    }


    /**
     * Puts a worker inside the thing it is working.
     *
     * <p>Off the map entirely: it stops blocking the square, stops being
     * shot at, and stops being drawn, all of which is what going inside a
     * mine means.
     */
    void enterResource(Unit worker, Unit resource) {
        world.markOccupancy(worker, false);
        world.markSight(worker, false);
        worker.setWorksite(resource);
        worker.setRemoved(true);
        // Both GatherResource and MoveToDepot clear Anim.CurrAnim after
        // Remove. In particular, the depot's waiting
        // Still must start from instruction zero instead of resuming the
        // worker's pre-entry Still phase and consuming RandomGoto early.
        worker.animation().clearCurrent();
        // CUnit::Remove(host) ends "UnitInXY(*this, host->tilePos)": a
        // contained unit stands where its container stands. Without it a
        // worker inside a hall still measures distances from the square it
        // walked in off, so the next mine it picks is chosen from the wrong
        // place.
        worker.setTile(resource.tileX(), resource.tileY());
    }


    /**
     * Brings a worker back out beside what it was working.
     *
     * <p>If there is nowhere free it stays inside for now rather than being
     * lost or stacked on another unit, and tries again next cycle.
     *
     * @param towards what it is heading for next, so it comes out of the face
     *                nearest that, or {@code null} to leave by the west
     */
    boolean leaveResource(Unit worker, Unit towards) {
        Unit resource = worker.worksite();
        if (resource == null) {
            return false;
        }
        int[] spot = world.placeResourceBeside(worker, resource, towards);
        return finishLeaveResource(worker, spot);
    }

    /** Leaves a resource toward an already-authored native order point. */
    boolean leaveResource(Unit worker, int goalX, int goalY) {
        Unit resource = worker.worksite();
        if (resource == null) {
            return false;
        }
        int[] spot = world.placeResourceBesidePoint(
                worker, resource, goalX, goalY);
        return finishLeaveResource(worker, spot);
    }

    private boolean finishLeaveResource(Unit worker, int[] spot) {
        if (spot == null) {
            return false;
        }
        worker.setTile(spot[0], spot[1]);
        worker.setRemoved(false);
        worker.setWorksite(null);
        // DropOutNearest starts the worker's outward walk in a fresh collision
        // generation. The mine-approach nibble is not part of the carried
        // return route: XHuman 10 slot 1584 is collision two while inside the
        // mine, then zero from its fixture-240 drop-out until the first return
        // refusal at fixture 290. Letting that stale generation escape the
        // mine also made other returners classify this worker as collided and
        // redraw paths which retail retains.
        worker.setBattleNetCollisionCounter(0);
        worker.setBattleNetRefusals(0);
        world.markOccupancy(worker, true);
        world.unitCountSeen(worker);
        world.markSight(worker, true);
        return true;
    }


    /**
     * Sends a worker back out of the depot it banked its load in.
     *
     * <p>{@code COrder_Resource::WaitInDepot},
     * The worker is standing inside the
     * hall; where it comes out is decided by where it is going, which for gold
     * is the mine it is going back to and for wood is the nearest square that
     * still has a tree on it. Coming out of a fixed corner is what put
     * peasants on the wrong side of the Town Hall: on a base laid out with the
     * mine to the south-east, every returning peasant appeared at the hall's
     * north-west corner and then walked the length of two sides of a four by
     * four building to get going again.
     *
     * <p>If there is nowhere at all it stays inside and tries again, as the
     * game does when a hall is boxed in.
     */
    void leaveDepot(Unit worker, ResourceInfo info) {
        Unit depot = worker.worksite();
        if (depot == null) {
            worker.setReturningToDepot(false);
            if (info.resource() == UnitType.Resource.OIL) {
                worker.setBattleNetOilAction(Unit.BattleNetOilAction.TO_RESOURCE);
                worker.setBattleNetOilActionTicks(0);
            }
            return;
        }

        // Where it is going next. A dead mine means looking for another,
        // which is what UnitFindResource does before the drop-out.
        Unit mine = worker.resourceUnit();
        if (mine != null && !mine.isAlive()) {
            worker.setResourceUnit(null);
            mine = null;
        }
        // AI workers reconsider the layout after an exceptionally long trip
        // or when too many order pointers refer to this depot. A fresh Return
        // Goods order has no remembered mine by construction; that alone does
        // not invoke AiGetSuitableDepot. Human 7 tanker 1491's first depot
        // visit is uncongested, so its empty mine pointer falls through to
        // UnitFindResource at refinery (72,72), retaining platform (79,77).
        // Reassigning merely because the pointer was empty instead searched
        // from refinery (64,81) and incorrectly selected platform (57,73).
        // This happens before the ordinary mine fallback.
        boolean longWay = worker.resourceMoveCycles() > 500;
        if (!info.terrainHarvester() && world.ais.containsKey(worker.player())
                && (longWay || world.approximateUnitRefs(depot) > 15)) {
            BattleNetBuildingPlacement.SuitableDepot suitable =
                    world.placement.aiSuitableDepot(worker, depot, info);
            if (suitable != null) {
                mine = suitable.mine();
            }
        }
        if (mine == null && !info.terrainHarvester()) {
            // WaitInDepot does not give up merely because Resource.Mine's
            // weak pointer is empty. It runs UnitFindResource from the depot,
            // with that depot also supplied to the mine-cost comparison,
            // before it chooses a dropout side. Doing this afterwards finds
            // the same mine but is visibly too late: levelx12h's peon 47
            // left its Great Hall west at cycle 553 instead of south towards
            // the gold mine the search then selected.
            mine = findResourceUnit(worker, info, depot, depot,
                    info.resource(), 1000);
        }
        if (mine != null && worker.resourceUnit() != mine) {
            worker.setResourceUnit(mine);
            worker.setResourceTile(mine.tileX(), mine.tileY());
        }

        // Hidden action 26 invokes the AI ready callback while the worker is
        // still contained. Its queued task therefore selects either the new
        // resource goal or construction's no-resource west exit. XHuman 8
        // peon 1571 pays for its watch tower on fixture 420, leaves the Great
        // Hall at 20,8, and remains Still with Build queued through 444.
        // XHuman 5 tanker 1557 likewise selects platform 1558 while it is
        // still inside the shipyard, so that point owns both its east-face
        // absolute-even exit and the queued action 23.
        boolean depotReadyBoundary = (info.resource() == UnitType.Resource.GOLD
                || info.resource() == UnitType.Resource.WOOD
                || info.resource() == UnitType.Resource.OIL)
                && pauseComputerForReadyDispatch(worker);
        boolean depotReadyAssigned = depotReadyBoundary
                && world.battleNetDepotUnitReady(worker);

        // The callback may deliberately keep the current resource job. It is
        // still stored as next action 23 behind the same timed Still head:
        // XHuman 8 peon 1501 surfaces at fixture 440 with timer 25 even though
        // 0x439280 leaves its remembered mine unchanged.
        if (world.battleNetSequence != null
                && depotReadyBoundary && !depotReadyAssigned
                && !worker.hasQueuedOrders() && mine != null) {
            queueDepotHarvestContinuation(worker, mine,
                    mine.tileX(), mine.tileY());
        }

        boolean assignedBuild = depotReadyAssigned
                && worker.pendingBuild() != null;
        int[] assignedGoal = null;
        if (depotReadyAssigned && !assignedBuild
                && worker.resourceUnit() != null
                && worker.resourceUnit().isAlive()) {
            assignedGoal = new int[] {
                    worker.resourceUnit().tileX(), worker.resourceUnit().tileY()};
        } else if (depotReadyAssigned && !assignedBuild
                && world.map.contains(
                        worker.resourceTileX(), worker.resourceTileY())) {
            assignedGoal = new int[] {
                    worker.resourceTileX(), worker.resourceTileY()};
        }

        int[] spot;
        boolean noWoodLeft = false;
        if (assignedBuild) {
            // A construction dispatch uses WaitInDepot's no-resource west
            // exit, not the build point as a DropOutNearest goal. All five
            // action-26-to-Build transitions in the sealed campaign corpus
            // agree: the worker takes the first legal west traversal square,
            // falling around later faces only when that face is blocked.
            spot = world.dropOutOnSide(worker.type(), World.LOOKING_WEST,
                    depot, worker.tileX(), worker.tileY());
        } else if (assignedGoal != null) {
            // The ready callback has already authored the next order. Native
            // uses that exact point and its unrounded direction to select a
            // face, rather than admitting a geometrically nearer corner.
            spot = world.placeResourceBesidePoint(worker, depot,
                    assignedGoal[0], assignedGoal[1]);
        } else if (mine != null) {
            spot = world.placeResourceBesidePoint(worker, depot,
                    mine.tileX(), mine.tileY());
        } else if (info.terrainHarvester()) {
            // FindTerrainType out from the square it last worked, then
            // DropOutNearest to whatever that found: a lumberjack leaves by
            // the face pointing at the wood.
            //
            // The search is the part that used to be missing. This handed
            // dropOutNearest the remembered square itself, and the square a
            // woodcutter remembers when it walks into the hall is the one it
            // has just felled -- a full load is exactly one tree. So every
            // trip after the first came out of the hall aimed at bare ground,
            // walked the whole way back to it, and only looked for another
            // tree once it was standing in the stumps: on three peasants
            // working one wood on level04h, 77 return trips out of 77, up to
            // four squares from the nearest tree. The player sees a peasant
            // wander into the middle of the clearing it made and swing at
            // nothing, and that is the report this was found from.
            // A normal harvest order records the square the worker stood on
            // when it finished chopping. NewActionReturnGoods is a fresh
            // COrder_Resource, however, whose Resource.Pos remains {-1,-1}.
            // TerrainTraversal does not turn that invalid origin into the
            // depot's tile: its search simply finds nothing, so the worker
            // leaves by the west and the order finishes. Falling back to the
            // worker's current (contained) tile here resurrected the prior
            // wood errand after an AI send-home; levelx12h's peon 49 left its
            // upgrading hall southward at cycle 492 instead of westward.
            int[] wood = world.map.contains(worker.resourceTileX(), worker.resourceTileY())
                    ? world.findTerrainType(worker, worker.resourceTileX(), worker.resourceTileY(),
                            World.DEPOT_WOOD_RANGE)
                    : null;
            if (System.getenv("CHONKCRAFT_TRACE_WOOD") != null) {
                System.err.println("JDROPDBG " + world.cycle + " unit=" + worker.id()
                        + " centre " + worker.resourceTileX() + "," + worker.resourceTileY()
                        + " found " + (wood == null ? "-" : wood[0] + "," + wood[1]));
            }
            if (wood != null) {
                worker.setResourceTile(wood[0], wood[1]);
                if (world.battleNetSequence != null
                        && depotReadyBoundary && !worker.hasQueuedOrders()) {
                    queueDepotHarvestContinuation(worker, null,
                            wood[0], wood[1]);
                }
                spot = world.placeResourceBesidePoint(worker, depot,
                        wood[0], wood[1]);
            } else {
                // Nothing within ten squares of where it was working. Out of
                // the west face and stand down, which is the Finished branch
                // upstream takes here.
                spot = world.dropOutOnSide(worker.type(), World.LOOKING_WEST, depot,
                        worker.tileX(), worker.tileY());
                noWoodLeft = true;
            }
        } else {
            spot = world.dropOutOnSide(worker.type(), World.LOOKING_WEST, depot,
                    worker.tileX(), worker.tileY());
        }
        if (spot == null) {
            return;
        }

        worker.setTile(spot[0], spot[1]);
        worker.setRemoved(false);
        worker.setWorksite(null);
        world.markOccupancy(worker, true);
        world.unitCountSeen(worker);
        world.markSight(worker, true);
        worker.setReturningToDepot(false);
        if (info.resource() == UnitType.Resource.OIL) {
            worker.setBattleNetDoubleStep(
                    ((worker.tileX() | worker.tileY()) & 1) == 0);
            worker.setBattleNetOilAction(Unit.BattleNetOilAction.TO_RESOURCE);
            worker.setBattleNetOilActionTicks(0);
            worker.setBattleNetOilStartedAdjacent(false);
        }
        worker.clearPath();
        // The finished visit sends the order back through its start:
        // Execute's SUB_RETURN_RESOURCE arm sets SUB_START_RESOURCE on
        // WaitInDepot's yes, and the next
        // cycle's ActionResourceInit zeroes the wait ladder with everything
        // else. On level08o every tanker's delivery resets its climb --
        // upstream's u116 leaves the refinery at 582 and counts 710, 721,
        // 732, 743, 754 afresh; a port that carried the old rungs across
        // the visit shoved at 732, twenty-two cycles early.
        worker.setResourceWaitLadder(0);
        // Native WaitInDepot surfaces Still after the hall visit so
        // 0x439280 can assign the next job. A computer peasant used to
        // stay on Harvest, walk straight back to the mine, and never
        // spend a ready marker on a farm: Human 11 player 4 kept 720
        // gold through 1399 while retail founded 72,12 and rewrote the
        // land box. Player-issued resource orders keep their loop.
        boolean oilReadyBoundary = pauseOilForReadyDispatch(worker, info);
        if (oilReadyBoundary) {
            // An empty AI tanker retains its remembered platform as raw next
            // action 23 behind the same 25-cycle Still head as land workers.
            // Orc 10 tanker 1547 surfaces on fixture 439, promotes Resource
            // on 464, and first strides on 467; Orc 7 tanker 1532 repeats the
            // independent sequence at 596, 621 and 624. Without the queued
            // continuation the next idle marker immediately reconstructed
            // Harvest, one cycle after each depot exit. A tanker with no live
            // platform deliberately keeps an empty queue so the ready callback
            // can search or found one instead.
            if (world.battleNetSequence != null
                    && !worker.hasQueuedOrders()
                    && mine != null && mine.isAlive()) {
                queueDepotHarvestContinuation(worker, mine,
                        mine.tileX(), mine.tileY());
            }
            return;
        }
        if (depotReadyBoundary) {
            return;
        }
        if (noWoodLeft
                || (mine == null && !info.terrainHarvester()
                        && !findAnotherResource(worker, info))) {
            // WaitInDepot marks the COrder_Resource finished but it remains
            // current through the cycle that dropped the worker out. The
            // action dispatcher removes it at the top of the worker's next
            // turn. Keeping that one-cycle seam is visible in both the trace
            // and the Still animation: levelx12h's peon 49 is resource at
            // cycle 492 on the west side of its hall, then Still at 493.
            worker.setOrderFinished(true);
        }
    }


    /** Keeps an unchanged resource loop behind a depot-ready Still head. */
    private void queueDepotHarvestContinuation(Unit worker, Unit resource,
            int tileX, int tileY) {
        worker.enqueueOrder(new Unit.QueuedOrder(
                Unit.QueuedOrderKind.HARVEST, tileX, tileY,
                resource, null, null));
        worker.setQueuedReplacementPending(true);
        // The queue is visited once at the bottom of this unit tick.
        worker.setBattleNetOrderDelay(26);
        world.idle.armBattleNetDepotReadyHold(worker);
    }


    /**
     * Removes a load from the source.
     *
     * <p>A mine holds a finite amount and is exhausted when it runs out; a
     * forest square holds one load and becomes the tileset's cleared tile
     * when chopped, which is why the tileset knows its removed-tree index.
     */
    /**
     * Takes up to {@code wanted} from a square or a mine.
     *
     * <p>A forest square holds a hundred wood and gives it up a couple at a
     * time; only when it is spent does the tree come down and the square open
     * up. Clearing it on the first swing was what made felling a tree
     * instantaneous, and it also handed a peasant a full load for two wood's
     * worth of work.
     *
     * @return how much was actually taken, which is zero when the source is
     *         spent
     */
    int takeResource(Unit worker, ResourceInfo info, int tileX, int tileY, int wanted) {
        if (wanted <= 0) {
            return 0;
        }
        if (info.terrainHarvester()) {
            MapField field = world.map.fieldOrNull(tileX, tileY);
            if (field == null || !field.isForest()) {
                return 0;
            }
            int left = field.value() > 0 ? field.value() : GameMap.WOOD_PER_FOREST_TILE;
            int taken = Math.min(wanted, left);
            if (System.getenv("CHONKCRAFT_TRACE_WOOD") != null) {
                System.err.println("JWOODTAKE " + world.cycle + " unit=" + worker.id()
                        + " at " + tileX + "," + tileY + " take=" + taken
                        + " left=" + (left - taken));
            }
            field.setValue(left - taken);
            if (field.value() <= 0) {
                fellTree(field, tileX, tileY);
            }
            return taken;
        }

        Unit mine = worker.resourceUnit();
        if (mine == null || !mine.isAlive()) {
            return 0;
        }
        int available = Math.min(wanted, mine.resourcesHeld());
        if (available <= 0) {
            return 0;
        }
        mine.setResourcesHeld(mine.resourcesHeld() - available);
        if (mine.resourcesHeld() <= 0) {
            world.killDepletedResource(mine, worker.player());
        }
        return available;
    }


    /**
     * Turns a spent forest square into open ground.
     *
     * <p>{@code CMap::ClearWoodTile} does the square and its eight neighbours
     * together, because taking one tile out of a wood changes how the tiles
     * round it have to be drawn. See {@link GameMap#clearWoodTile}.
     */
    void fellTree(MapField field, int tileX, int tileY) {
        world.map.clearWoodTile(tileX, tileY);
        world.reachable.clear();
    }


    /**
     * Classifies a gold approach path for free-prefix replan vs segment wait.
     *
     * <p>A free-prefix ends short of the mine approach because the ray hit the
     * blocked footprint (Orc 12 peon 1525: two steps onto 85,41 while the
     * approach is 83,41). Full finder segments that fill the buffer still pay
     * PF_WAIT 10 between asks.
     */
    void markBattleNetGoldPathKind(Unit worker, Unit building,
            PathFinder.Path path) {
        if (path == null || path.length() == 0 || building == null) {
            worker.setBattleNetGoldFreePrefix(false);
            worker.setBattleNetGoldLongApproach(false);
            return;
        }
        int[] approach = world.battleNetApproachPoint(worker, building);
        world.markBattleNetPointFreePrefix(worker, path, approach[0], approach[1]);
        // Multi-step gold approach: if the last leftover heading later misses
        // the approach point, action 23 → 25 must stage rather than walk that
        // leftover (XHuman 9 1550).
        worker.setBattleNetGoldLongApproach(path.length() > 1);
    }


    /** Restores the state a harvesting worker needs to resume inside a source. */
    void restoreHarvestState(Unit worker, Unit resource, int tileX, int tileY,
            boolean returningToDepot, int waitCycles) {
        if (worker == null) {
            return;
        }
        worker.setResourceUnit(resource);
        worker.setResourceTile(tileX, tileY);
        worker.setReturningToDepot(returningToDepot);
        worker.setWaitCycles(waitCycles);
        ResourceInfo info = worker.type() == null || worker.carrying() == null
                ? null : worker.type().gathering().get(worker.carrying());
        if (info != null && info.resource() == UnitType.Resource.OIL) {
            worker.setBattleNetOilAction(returningToDepot
                    ? Unit.BattleNetOilAction.TO_DEPOT
                    : worker.removed()
                            ? Unit.BattleNetOilAction.INSIDE_RESOURCE
                            : Unit.BattleNetOilAction.TO_RESOURCE);
            worker.setBattleNetOilActionTicks(0);
        }
    }


    /**
     * Rejoins the outer resource order to an authoritative restored oil substate.
     *
     * <p>One released build of the mission-four tanker reached action 24 but
     * saved no {@code SetHarvestState} because the Java outer order had become
     * Still. The authoritative native action and its Java order projection
     * had split. Retail cannot own action 24 without {@code COrder_Resource};
     * reconstruct that order once after every saved unit and reference has
     * been restored.</p>
     */
    void repairRestoredOilOrders() {
        for (Unit worker : world.units) {
            if (worker.type() == null
                    || !worker.type().gathering().containsKey(UnitType.Resource.OIL)
                    || worker.battleNetOilAction() != Unit.BattleNetOilAction.TO_DEPOT
                    || worker.carried() <= 0
                    || worker.order() != Unit.Order.STILL
                    || !worker.isAlive()) {
                continue;
            }
            worker.clearPath();
            worker.setReturningToDepot(true);
            Unit depot = bestDepotByTravel(worker, UnitType.Resource.OIL, 1000);
            world.repairRestoredOilAnchor(worker, depot);
            // Relocating a route-dead legacy anchor can change which depot is
            // the nearest reachable one.
            depot = bestDepotByTravel(worker, UnitType.Resource.OIL, 1000);
            worker.setResourceDepot(depot);
            worker.setReturnDepotGoal(depot);
            worker.setOrderFinished(false);
            worker.setOrder(Unit.Order.HARVEST);
            // A repaired anchor rejoins the normal doubled lattice. If no
            // legal relocation was possible, retain the existing one-tile
            // compatibility route for this leg.
            worker.setBattleNetDoubleStep(
                    ((worker.tileX() | worker.tileY()) & 1) == 0);
        }
    }


    static boolean isBattleNetOilPlatform(String ident) {
        return "unit-human-oil-platform".equals(ident)
                || "unit-orc-oil-platform".equals(ident);
    }


    /**
     * Gold wait-at-depot for an empty send-home that has no gather table.
     * Native grunt 1592 reads timer 150 inside the hall -- the same stay
     * GeneratedUnitRoster writes for peasant gold.
     */
    private static ResourceInfo emptySendHomeGoldWait() {
        ResourceInfo gold = new ResourceInfo(UnitType.Resource.GOLD);
        gold.setWaitAtDepot(150);
        return gold;
    }

    /** Carries whatever is held back to the nearest depot. */
    void stepReturnGoods(Unit unit) {
        // NewActionReturnGoods still walks when the hand is empty: FindDeposit
        // names the hall, and a zero load enters it the same way a full one
        // does. Used to Still here, which left an empty send-home on the
        // spawn tile while native was already on the hall doorstep.
        UnitType.Resource resource = unit.carrying() != null
                ? unit.carrying() : UnitType.Resource.GOLD;
        // The return order is COrder_Resource in its going-home states, and
        // its depot is FindDeposit's: nearest by the walked route,
        // unreachable ones excluded (NewActionReturnGoods,
        // The game ).
        // A queued mine/platform exit has already selected its weak depot
        // goal before dropout. Keep that choice through the ready boundary;
        // only a missing or invalidated goal needs a fresh FindDeposit.
        Unit depot = unit.returnDepotGoal();
        if (depot == null || !depot.isAlive() || !depot.isOnMap()
                || depot.player() != unit.player() || depot.type() == null
                || !depot.type().storesResource(resource)
                || depot.order() == Unit.Order.UNDER_CONSTRUCTION) {
            depot = bestDepotByTravel(unit, resource, 1000);
        }
        if (depot == null) {
            // Born in SUB_UNREACHABLE_DEPOT: the first cycle's Execute is
            // ResourceGiveUp, which keeps the load -- a goalless order has
            // nothing for DropResource to drop -- and merely marks the order
            // finished, still current to the end of that cycle. The next
            // cycle's advance replaces it with Still, which breathes at
            // once. On campaigns/human-exp/levelx03h the stranded peon's
            // send-home at 247 reads resource at 248 and still from 249 in
            // both engines, with the Still loop's draw landing on 253.
            if (unit.orderFinished()) {
                unit.setOrderFinished(false);
                unit.setOrder(Unit.Order.STILL);
                world.idle.stepStill(unit);
            } else {
                unit.setOrderFinished(true);
            }
            return;
        }
        // With somewhere to go this is the harvest loop's own going-home
        // leg -- doorstep wait, the stay inside, and the walk back out --
        // not a bank-through-the-wall shortcut.
        unit.setReturningToDepot(true);
        if (unit.carrying() == UnitType.Resource.OIL) {
            unit.setBattleNetDoubleStep(
                    ((unit.tileX() | unit.tileY()) & 1) == 0);
            unit.setBattleNetOilAction(Unit.BattleNetOilAction.TO_DEPOT);
            unit.setBattleNetOilActionTicks(0);
        }
        unit.setResourceDepot(depot);
        unit.setReturnDepotGoal(depot);
        int activeIdleSequence = unit.battleNetSequenceOffset();
        int activeIdleTimer = unit.battleNetAnimationTimer();
        unit.setOrder(Unit.Order.HARVEST);
        // RETURN_GOODS and HARVEST are two Java projections of the same
        // native COrder_Resource.  Preserve action 24's active Still cursor
        // across that projection change; Unit.setOrder deliberately clears
        // animation cursors for unrelated order replacements.
        int stillStart = world.idle.battleNetStillSequenceStart(unit);
        if (activeIdleSequence == stillStart && activeIdleTimer > 0) {
            unit.setBattleNetSequenceOffset(activeIdleSequence);
            unit.setBattleNetAnimationTimer(activeIdleTimer);
        }
        stepHarvest(unit);
    }


    /**
     * Starts one native action-24 idle band after a depot route returned an
     * empty FOUND prefix.
     *
     * <p>The order stays active and asks again every third visit.  Each
     * restart runs the random-facing half of {@code FUN_0040ad30}, but not
     * Still's autonomous-order dispatcher.  In Human 8, laden peon 1536 owns
     * the missing draw at fixtures 283, 286 and 289; without it critter 1539
     * consumes the peon's value and wanders on 283.</p>
     */
    boolean beginBattleNetEmptyDepotRouteIdleBand(Unit worker, Unit depot,
            PathFinder.Path emptyPath) {
        return beginBattleNetEmptyDepotRouteIdleBand(
                worker, depot, emptyPath, false);
    }


    boolean beginBattleNetEmptyDepotRouteIdleBand(Unit worker, Unit depot,
            PathFinder.Path emptyPath,
            boolean activeOrderIdleRandomAlreadyPaid) {
        if (worker == null || depot == null || emptyPath == null
                || emptyPath.result() != PathFinder.Result.FOUND
                || emptyPath.length() != 0
                || worker.order() != Unit.Order.HARVEST
                || !worker.returningToDepot() || worker.carried() <= 0
                || !worker.type().landUnit()
                || world.battleNetMovementStride(worker) != 1
                || worker.isMoving() || worker.distanceTo(depot) <= 1
                || world.battleNetSequence == null) {
            return false;
        }
        int stillStart = world.idle.battleNetStillSequenceStart(worker);
        if (stillStart < 0) {
            return false;
        }
        worker.setPath(emptyPath);
        worker.setPathGoal(-1, -1);
        worker.setBattleNetSequenceOffset(stillStart);
        worker.setBattleNetAnimationTimer(3);
        if (!activeOrderIdleRandomAlreadyPaid) {
            world.idle.advanceBattleNetActiveOrderIdleRandom(worker);
        }
        beginBattleNetStrandedResourceHitFlee(worker);
        return true;
    }


    /**
     * Lets a stranded resource order answer the aggressor retained at +0x54.
     *
     * <p>The empty-route resource retry runs the common active-order idle
     * callback. If a non-aggressive worker was struck since its preceding
     * retry, retail's following {@code FUN_0040a5e0} visit reaches
     * {@code FUN_0040a670}: it consumes two asynchronous draws, authors a
     * short escape point from the aggressor's facing, and temporarily exposes
     * action 3 over the resource order's three-call Still body. Human 8 peasant
     * 1536 is the sealed witness. Ogre 1538 hits it on fixture 295; its next
     * empty depot-route retry changes action 24 to action 3 at point (83,62)
     * on fixture 298, then restores action 24 on fixture 301. Omitting the two
     * point draws also hands critter 1539 the worker's values and makes it
     * wander on fixture 298.</p>
     */
    private void beginBattleNetStrandedResourceHitFlee(Unit worker) {
        beginBattleNetStrandedResourceHitFlee(worker, false);
    }


    /** Restarts a temporary resource-hit Move for a blow retained in flight. */
    boolean restartBattleNetStrandedResourceHitFlee(Unit worker) {
        return beginBattleNetStrandedResourceHitFlee(worker, true);
    }


    private boolean beginBattleNetStrandedResourceHitFlee(Unit worker,
            boolean retainingSavedResourceOrder) {
        Unit aggressor = worker == null ? null : worker.offeredTarget();
        if (worker == null || aggressor == null
                || !aggressor.isAlive() || aggressor.isDying()
                || !aggressor.isOnMap() || worker.isAggressive()
                || (retainingSavedResourceOrder
                        ? worker.savedOrder() != Unit.Order.HARVEST
                        : worker.savedOrder() != null)
                || world.battleNetSequence == null) {
            return false;
        }
        if (retainingSavedResourceOrder) {
            // The settle visit runs FUN_0040ad30 for this worker before
            // FUN_0040a5e0 re-enters the flee constructor. Human 8's second
            // reaction consumes 0x3290 there, leaving 0x6ddf/0x6d76 for the
            // native point (89,60). This is the same active-order idle draw
            // the empty-route caller owns before the first reaction.
            world.idle.advanceBattleNetActiveOrderIdleRandom(worker);
        }
        if (!retainingSavedResourceOrder) {
            worker.setSavedOrder(Unit.Order.HARVEST);
        }
        return authorBattleNetHitFlee(worker, aggressor);
    }


    /** Lets a standing native special unit consume its hit-owned offer. */
    boolean beginBattleNetStandingHitFlee(Unit unit) {
        Unit aggressor = unit == null ? null : unit.offeredTarget();
        if (unit == null || aggressor == null
                || !aggressor.isAlive() || aggressor.isDying()
                || !aggressor.isOnMap() || unit.order() != Unit.Order.STILL
                || unit.savedOrder() != null
                || world.battleNetSequence == null) {
            return false;
        }
        unit.setSavedOrder(Unit.Order.STILL);
        return authorBattleNetHitFlee(unit, aggressor);
    }


    /** Ports the shared native escape-point constructor at 0x0040a670. */
    private boolean authorBattleNetHitFlee(Unit worker, Unit aggressor) {
        int face = aggressor.heading();
        int x = worker.tileX() + Direction.deltaX(face) * 4
                + (world.battleNetRand() & 7) - 2;
        int y = worker.tileY() + Direction.deltaY(face) * 4
                + (world.battleNetRand() & 7) - 2;
        x = Math.max(0, Math.min(world.map.width() - 1, x));
        y = Math.max(0, Math.min(world.map.height() - 1, y));
        int authoredX = x;
        int authoredY = y;
        int[] normalized = world.battleNetSpreadUnitGoal(worker, x, y);
        x = normalized[0];
        y = normalized[1];

        worker.clearPath();
        worker.setRouteSpent(false);
        // The constructor band preserves the authored point here until its
        // last call. Its difference from OrderX/Y is the semantic proof that
        // SpreadUnit stored an occupied approach edge rather than accepting
        // the free authored point. Both coordinates are already serialized.
        worker.setPathGoal(authoredX, authoredY);
        worker.setOrderTarget(x, y);
        worker.setWaitCycles(0);
        worker.setBattleNetOrderDelay(0);
        worker.setOfferedTarget(null);
        worker.setOrder(Unit.Order.MOVE);
        worker.setBattleNetSequenceOffset(
                world.idle.battleNetStillSequenceStart(worker));
        worker.setBattleNetAnimationTimer(3);
        return true;
    }


    /** Counts down a standing hit-flee constructor before normal Move. */
    boolean stepBattleNetStandingHitFlee(Unit unit) {
        if (unit == null || unit.order() != Unit.Order.MOVE
                || unit.savedOrder() != Unit.Order.STILL
                || unit.isMoving() || unit.pathLength() != 0
                || world.battleNetSequence == null) {
            return false;
        }
        int stillStart = world.idle.battleNetStillSequenceStart(unit);
        if (stillStart < 0
                || unit.battleNetSequenceOffset() != stillStart
                || unit.battleNetAnimationTimer() <= 0) {
            return false;
        }
        if (unit.battleNetAnimationTimer() > 1) {
            unit.setBattleNetAnimationTimer(
                    unit.battleNetAnimationTimer() - 1);
            return true;
        }

        int moveStart = world.idle.battleNetSequenceStart(
                unit, BattleNetSequence.MOVE_ANIMATION);
        if (moveStart >= 0) {
            BattleNetSequence.Tick open =
                    world.battleNetSequence.tick(moveStart, 1);
            unit.setBattleNetSequenceOffset(
                    open.valid() ? open.offset() : moveStart);
            unit.setBattleNetAnimationTimer(open.valid() ? open.timer() : 1);
        }
        return false;
    }


    /** Counts and either starts or restores the temporary resource-hit move. */
    boolean stepBattleNetStrandedResourceHitFlee(Unit worker) {
        if (worker == null || worker.order() != Unit.Order.MOVE
                || worker.savedOrder() != Unit.Order.HARVEST
                || !worker.returningToDepot() || worker.carried() <= 0
                || worker.isMoving() || worker.pathLength() != 0
                || world.battleNetSequence == null) {
            return false;
        }
        int stillStart = world.idle.battleNetStillSequenceStart(worker);
        if (stillStart < 0
                || worker.battleNetSequenceOffset() != stillStart
                || worker.battleNetAnimationTimer() <= 0) {
            return false;
        }
        if (worker.battleNetAnimationTimer() > 1) {
            worker.setBattleNetAnimationTimer(
                    worker.battleNetAnimationTimer() - 1);
            return true;
        }

        // SpreadUnit may retain an occupied edge point immediately beyond
        // the first admissible square. That form proceeds through the normal
        // Move handler when the three-call Still constructor expires: Human
        // 8 peasant 1536 stores 79,59 and commits NE on fixture 325. A free
        // authored point owns only the reaction body and then restores the
        // resource action (peasants 1536 at fixture 301 and 1533 at 316).
        boolean spreadApproachEdge = worker.pathGoalX()
                != worker.orderTargetX()
                || worker.pathGoalY() != worker.orderTargetY();
        if (spreadApproachEdge) {
            worker.setBattleNetResourceHitRestoreIdle(false);
            worker.setPathGoal(worker.orderTargetX(), worker.orderTargetY());
            int moveStart = world.idle.battleNetSequenceStart(
                    worker, BattleNetSequence.MOVE_ANIMATION);
            if (moveStart >= 0) {
                BattleNetSequence.Tick open =
                        world.battleNetSequence.tick(moveStart, 1);
                worker.setBattleNetSequenceOffset(
                        open.valid() ? open.offset() : moveStart);
                worker.setBattleNetAnimationTimer(
                        open.valid() ? open.timer() : 1);
            }
            return false;
        }

        // The last call of the temporary Move body still owns FUN_0040ad30.
        // Pay its marker before RestoreOrder, regardless of whether the
        // resumed resource route is empty. If that route is empty, pass the
        // ownership through the synchronous retry so it does not pay the same
        // marker again. Human 8 peasants 1536/1533 retain one draw on their
        // fixture-301/316 controls, while 1536 gains the missing fixture-350
        // draw before critter 1492's fixture-358 wander.
        world.idle.advanceBattleNetActiveOrderIdleRandom(worker);
        worker.takeSavedOrder();
        worker.setOrder(Unit.Order.HARVEST);
        worker.setOrderTarget(-1, -1);
        worker.setPathGoal(-1, -1);
        worker.setBattleNetSequenceOffset(stillStart);
        worker.setBattleNetAnimationTimer(1);
        worker.setBattleNetResourceHitRestoreIdle(true);
        stepHarvest(worker, true);
        return true;
    }


    /** Whether this laden return is serving action 24's empty-route cursor. */
    boolean isBattleNetEmptyDepotRouteIdleCursor(Unit worker) {
        if (worker == null || !worker.returningToDepot()
                || worker.carried() <= 0 || !worker.type().landUnit()
                || world.battleNetMovementStride(worker) != 1
                || worker.pathLength() != 0 || worker.isMoving()
                || world.battleNetSequence == null) {
            return false;
        }
        int stillStart = world.idle.battleNetStillSequenceStart(worker);
        return stillStart >= 0
                && worker.battleNetSequenceOffset() == stillStart
                && worker.battleNetAnimationTimer() > 0;
    }


    /**
     * One wait answered by a computer worker's harvest walk, and the shove
     * every fifth one buys.
     *
     * <p>The {@code PF_WAIT} arms of {@code COrder_Resource}'s three walks --
     * {@code MoveToResource_Terrain}, {@code MoveToResource_Unit} and
     * {@code MoveToDepot}, with the terrain walk's {@code PF_UNREACHABLE} arm counting
     * too -- share the order's otherwise unused {@code Range} as a counter:
     * every wait the walk answers steps it up, and the fifth resets it and
     * asks {@code AiCanNotMove} whether somebody standing in the way should
     * be shoved. A blocked walker beats every eleventh cycle, so a genuine
     * jam shoves about once per fifty-five stuck cycles -- but the route-end
     * and refusal waits of an ordinary trip count as well, so the ladder
     * climbs a rung per route segment too, and which cycle the fifth rung
     * lands on is the whole of what this buys.
     *
     * <p>On {@code campaigns/orc/level08o} the enemy oil tanker 116, stuck
     * behind its fellow 120 off the platform at 85,103, climbs rungs at
     * cycles 710, 721, 732 and 743 and shoves on 754: two draws, and 120 --
     * mid-harvest, flush and all -- is sent to 89,100 under a plain move.
     * This implementation used to sail on, shove nobody, and every number either
     * engine drew after 754 belonged to a different game.
     */
    void resourceWalkWaited(Unit worker, int goalX, int goalY,
            int goalWidth, int goalHeight) {
        if (!world.ais.containsKey(worker.player())) {
            return;
        }
        worker.setResourceWaitLadder(worker.resourceWaitLadder() + 1);
        if (System.getenv("CHONKCRAFT_TRACE_SHOVE") != null) {
            System.err.println("JLADDER " + world.cycle + " unit=" + worker.id()
                    + " range=" + worker.resourceWaitLadder());
        }
        if (worker.resourceWaitLadder() >= World.RESOURCE_WAIT_SHOVE) {
            worker.setResourceWaitLadder(0);
            world.aiCanNotMove(worker, goalX, goalY, goalWidth, goalHeight);
        }
    }


    /**
     * Clears a gold-approach soft-wait when the planned next cell frees.
     *
     * <p>XHuman 7 peon 1446 holds route_index 20 at 110,105 while ally 1447
     * occupies SW (109,106), then steps SW the cycle after that ally leaves
     * (fixture 45). Soft-wait used to count fourteen quiet visits without
     * re-checking occupancy, so Java stepped only at fixture 54. Free-wake
     * zeros the remaining delay so the caller can fall through into the
     * harvest walk and take the step this visit.</p>
     *
     * @return {@code true} when the delay was cleared for a free next cell
     */
    private boolean tryBattleNetGoldSoftWaitFreeWake(Unit worker) {
        // Residual-settle soft-wait free-wake only (flag armed when delay 14
        // is set on a drained residual refuse). Mid-path soft-waits and the
        // fixed free-window delay 6 count out fully.
        if (!worker.battleNetGoldSoftWaitFreeWake()
                || worker.battleNetOrderDelay() <= 0
                || worker.resourceUnit() == null
                || !worker.resourceUnit().isAlive()
                || worker.resourceUnit().type() == null
                || worker.resourceUnit().type().givesResource()
                        != UnitType.Resource.GOLD
                || worker.returningToDepot()
                || worker.pathLength() <= 0
                || worker.isMoving()) {
            return false;
        }
        int heading = worker.peekHeading();
        if (heading < 0 || heading >= Direction.COUNT) {
            return false;
        }
        int nextX = worker.tileX() + Direction.deltaX(heading)
                * world.battleNetMovementStride(worker);
        int nextY = worker.tileY() + Direction.deltaY(heading)
                * world.battleNetMovementStride(worker);
        if (!canEnterBattleNetResourceTarget(worker, nextX, nextY)) {
            return false;
        }
        worker.setBattleNetOrderDelay(0);
        worker.setBattleNetGoldSoftWaitFreeWake(false);
        return true;
    }


    /** Lets BNE's resource walk take its final step into the target footprint. */
    boolean canEnterBattleNetResourceTarget(Unit unit, int x, int y) {
        Unit resource = unit.resourceUnit();
        if (resource == null || !resource.isAlive() || !resource.isOnMap()) {
            return world.canEnter(unit, x, y);
        }
        int left = resource.tileX();
        int top = resource.tileY();
        int right = left + Math.max(1, resource.type().tileWidth()) - 1;
        int bottom = top + Math.max(1, resource.type().tileHeight()) - 1;
        int[] approach = world.battleNetApproachPoint(unit, resource);
        boolean onApproachOrFootprint = (x == approach[0] && y == approach[1])
                || (x >= left && x <= right && y >= top && y <= bottom);
        // Soft-clear the resource always. Soft-clear same-resource allies on
        // this destination only when the step is onto the approach point or
        // inside the footprint (XHuman 8 peons 1571/1575 stack on 17,10).
        // Corridor steps keep solid allies so mid-path routing is unchanged
        // (xh12@3 regressed when every same-resource step could pass through).
        java.util.List<Unit> cleared = new ArrayList<>();
        cleared.add(resource);
        if (onApproachOrFootprint) {
            for (Unit candidate : world.units) {
                if (candidate == unit || candidate == resource
                        || !candidate.isOnMap() || candidate.isDying()
                        || !world.isAllied(unit.player(), candidate.player())
                        || candidate.resourceUnit() != resource
                        || candidate.tileX() != x || candidate.tileY() != y) {
                    continue;
                }
                if (candidate.order() != Unit.Order.HARVEST
                        && candidate.order() != Unit.Order.RETURN_GOODS) {
                    continue;
                }
                cleared.add(candidate);
            }
        }
        for (Unit candidate : cleared) {
            world.setMovementFieldFlags(candidate, false);
        }
        for (Unit candidate : cleared) {
            long flag = candidate.occupancyFlag();
            int w = Math.max(1, candidate.type().tileWidth());
            int h = Math.max(1, candidate.type().tileHeight());
            for (int dy = 0; dy < h; dy++) {
                for (int dx = 0; dx < w; dx++) {
                    MapField field = world.map.fieldOrNull(
                            candidate.tileX() + dx, candidate.tileY() + dy);
                    if (field != null) {
                        field.removeFlags(flag);
                    }
                }
            }
        }
        try {
            // Large naval resource movers use the same native anchor grid as
            // every other doubled ship action. Human 7 tanker 1533's cached
            // SW step lands its anchor on water at (80,52), while the visual
            // 2x2 hull overlaps coast at (81,53). Native accepts the step;
            // applying the land-worker footprint test here rejected it and
            // trapped the tanker in a one-cycle replan loop. The resource is
            // already soft-cleared above, so this also preserves the final
            // platform-entry exception without widening ordinary workers.
            if (unit.battleNetDoubleStep() && unit.type() != null
                    && unit.type().seaUnit()) {
                return world.canEnterBattleNetTransportAnchor(unit, x, y);
            }
            return world.canEnter(unit, x, y);
        } finally {
            for (Unit candidate : cleared) {
                world.setMovementFieldFlags(candidate, true);
            }
        }
    }
}
