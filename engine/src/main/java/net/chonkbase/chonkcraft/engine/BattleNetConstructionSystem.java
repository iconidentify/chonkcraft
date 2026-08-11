package net.chonkbase.chonkcraft.engine;

import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudUnitTypes;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.map.MapField;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.pathfinder.BattleNetPathFinder;
import net.chonkbase.chonkcraft.engine.pathfinder.PathFinder;
import java.util.ArrayList;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;

/**
 * Putting a building up, and keeping it up.
 *
 * <p>Implements retail BNE construction rules: the foundation that starts on
 * a tenth of the finished type's hit
 * points, the ten-visit cadence between construction boosts, the mutable
 * no-build square at map flag {@code 0x400} and the skewed row it paints,
 * and the walk to the site that founds on the order goal exactly.
 */
final class BattleNetConstructionSystem {

    private final World world;

    BattleNetConstructionSystem(World world) {
        this.world = world;
    }

    /**
     * The answer {@code CanBuildHere} gives: no, yes, or yes on top of this.
     *
     * <p>Upstream returns {@code std::optional<CUnit *>} and overloads all
     * three meanings onto it -- empty for no, the builder for a plain yes, and
     * the parent unit for an on-top site. This says the same three things
     * without the reader having to know that convention.
     */
    record BuildSite(Unit onTop, boolean allowed) {
        static final BuildSite REFUSED = new BuildSite(null, false);
        static final BuildSite PLAIN = new BuildSite(null, true);
    }


    /**
     * Abandons a building that is going up.
     *
     * <p>The site is destroyed outright rather than left standing: an
     * unfinished building is a construction order, not a structure. The builder
     * is put back on the map beside it, which is the only way it ever comes out
     * again.
     */
    boolean cancelConstruction(Unit site) {
        if (site.order() != Unit.Order.UNDER_CONSTRUCTION) {
            return false;
        }
        Unit worker = site.worksite();
        site.setWorksite(null);
        if (worker != null) {
            worker.setWorksite(null);
            // The game puts an abandoned builder out west.
            int[] spot = world.dropOutOnSide(worker.type(), World.LOOKING_WEST, site,
                    worker.tileX(), worker.tileY());
            if (spot != null) {
                worker.setTile(spot[0], spot[1]);
                worker.setRemoved(false);
                world.markOccupancy(worker, true);
                world.unitCountSeen(worker);
                world.markSight(worker, true);
            }
            worker.setOrder(Unit.Order.STILL);
        }
        world.refund(site.player(), site.type().costs(), World.CANCEL_BUILDING_REFUND);
        world.kill(site);
        world.recalculateSupply();
        return true;
    }


    /**
     * Discards async draws that the restart-at-Still-start constructor path
     * used to spend on non-wander markers, so an impassable-wander resume
     * does not shift later critters off the stream.
     */
    void burnBattleNetConstructorStream(Unit unit) {
        if (unit.battleNetConstructorStreamBurns() <= 0
                || world.cycle < unit.battleNetConstructorBurnAfterCycle()) {
            return;
        }
        if (world.battleNetEmptyRouteBurnSubstituted) {
            // Free-empty OP0 already drew; only retire the burn slot.
            world.battleNetEmptyRouteBurnSubstituted = false;
        } else {
            world.battleNetRand();
        }
        unit.setBattleNetConstructorStreamBurns(
                unit.battleNetConstructorStreamBurns() - 1,
                unit.battleNetConstructorBurnAfterCycle());
        if (World.BNE_IDLE_TRACE) {
            System.err.printf("JBNEBURN cycle=%d unit=%d burns-left=%d seed=%s%n",
                    world.cycle, unit.id(), unit.battleNetConstructorStreamBurns(),
                    Integer.toUnsignedString(world.battleNetRandomSeed));
        }
    }


    /**
     * Minimum Chebyshev distance from a square to any building footprint.
     * Open ground returns a large constant so Bresenham can break ties.
     */
    int battleNetApproachBuildingClearance(int x, int y) {
        int best = 64;
        for (Unit candidate : world.units) {
            if (!candidate.isAlive() || !candidate.isOnMap()
                    || !candidate.type().building()) {
                continue;
            }
            int left = candidate.tileX();
            int top = candidate.tileY();
            int right = left + Math.max(1, candidate.type().tileWidth()) - 1;
            int bottom = top + Math.max(1, candidate.type().tileHeight()) - 1;
            int dx = 0;
            if (x < left) {
                dx = left - x;
            } else if (x > right) {
                dx = x - right;
            }
            int dy = 0;
            if (y < top) {
                dy = top - y;
            } else if (y > bottom) {
                dy = y - bottom;
            }
            int distance = Math.max(dx, dy);
            if (distance < best) {
                best = distance;
            }
        }
        return best;
    }


    /**
     * BNE's build predicate, including its mutable map-square {@code 0x400}.
     *
     * <p>Unlike LegacyEngine {@code CanBuildUnitType}, the retail lattice search
     * does not lift the builder off the map before testing the footprint.
     * Soft-clearing the worker used to accept XOrc 10's farm at 109,5 -- the
     * peasant already stood on that rectangle -- so the foundation went down
     * without the north walk native records (order point 110,4, site 109,3).
     * Leaving the builder as occupancy forces the spiral past that site onto
     * the free 109,3 that matches the fixture.
     */
    boolean canPlaceBattleNetBuilding(Unit builder, UnitType what,
            int tileX, int tileY) {
        if (!canPlaceBuilding(null, what, tileX, tileY)) {
            traceBattleNetBuildRejection(builder, what, tileX, tileY,
                    "base-predicate");
            return false;
        }
        if (what.stores().contains(UnitType.Resource.GOLD)
                && !world.harvest.hasBattleNetGoldMineClearance(what, tileX, tileY)) {
            traceBattleNetBuildRejection(builder, what, tileX, tileY,
                    "gold-clearance");
            return false;
        }
        int right = tileX + Math.max(1, what.tileWidth()) - 1;
        int bottom = tileY + Math.max(1, what.tileHeight()) - 1;

        for (int y = tileY; y <= bottom; y++) {
            for (int x = tileX; x <= right; x++) {
                if (world.battleNetNoBuild[x + y * world.map.width()]) {
                    traceBattleNetBuildRejection(builder, what, tileX, tileY,
                            "native-no-build@" + x + "," + y);
                    return false;
                }
            }
        }
        return true;
    }


    /** Paints the type-specific exclusion created with an AI-owned building. */
    void markBattleNetExistingBuildingReservation(Unit unit) {
        if (unit == null || unit.type() == null || !unit.type().building()) {
            return;
        }
        Player owner = world.player(unit.player());
        if (owner == null || owner.type()
                != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER) {
            return;
        }
        int code = PudUnitTypes.code(unit.type().ident());
        if (code == 72 || code == 73 || code == 78 || code == 79
                || code == 84 || code == 85) {
            // FUN_0043a6d0: shore buildings reserve only native coast-bit
            // squares in a clipped 13-by-13 box around their top-left.
            int left = unit.tileX() - 6;
            int top = unit.tileY() - 6;
            int spanX = 13;
            int spanY = 13;
            if (left < 0) {
                spanX = unit.tileX() + 7;
                left = 0;
            }
            if (top < 0) {
                spanY = unit.tileY() + 7;
                top = 0;
            }
            if (world.map.width() <= left + spanX) {
                spanX = world.map.width() - left - 1;
            }
            if (world.map.height() <= top + spanY) {
                spanY = world.map.height() - top - 1;
            }
            for (int y = top; y < top + Math.max(0, spanY); y++) {
                for (int x = left; x < left + Math.max(0, spanX); x++) {
                    if (world.map.field(x, y).hasFlag(TileFlag.COAST_ALLOWED)) {
                        world.battleNetNoBuild[x + y * world.map.width()] = true;
                    }
                }
            }
            return;
        }

        // PTR_LAB_004a1260 dispatches only these ordinary type codes through
        // FUN_0043a680. Farms and all four tower families are deliberate
        // no-ops; deposits, oil platforms and scenery are no-ops as well.
        boolean ordinary = switch (code) {
            case 60, 61, 62, 63,
                    66, 67, 68, 69, 70, 71,
                    74, 75, 76, 77,
                    80, 81, 82, 83,
                    88, 89, 90, 91 -> true;
            default -> false;
        };
        if (ordinary) {
            setBattleNetNoBuildExpanded(unit.type(), unit.tileX(), unit.tileY());
        }
    }


    /** Paints the reservation installed with native raw build action 28. */
    void markBattleNetPendingBuildReservation(UnitType type,
            int tileX, int tileY) {
        int code = PudUnitTypes.code(type.ident());
        if (code == 58 || code == 59) {
            paintBattleNetNoBuildSquare(type, tileX, tileY);
        } else {
            setBattleNetNoBuildExpanded(type, tileX, tileY);
        }
    }


    /** Native {@code 0x438e70}: one tile beyond every side, except at zero. */
    void setBattleNetNoBuildExpanded(UnitType type,
            int tileX, int tileY) {
        int x = tileX == 0 ? 0 : tileX - 1;
        int y = tileY == 0 ? 0 : tileY - 1;
        int span = Math.max(1, type.tileWidth()) + 2;
        paintBattleNetNoBuildSquare(x, y, span);
    }


    /** Native {@code 0x438560}: paints the supplied square span. */
    void paintBattleNetNoBuildSquare(UnitType type,
            int tileX, int tileY) {
        paintBattleNetNoBuildSquare(tileX, tileY,
                Math.max(1, type.tileWidth()));
    }


    /**
     * Native square-marker clipping, including its right-edge row-stride bug.
     *
     * <p>{@code FUN_00438560} clips a span which reaches the far edge by one
     * square, but still advances the bitmap pointer using the original span.
     * Each following row therefore starts farther left. Retail Orc mission 8
     * depends on the resulting diagonal opening beside its edge-map hall.</p>
     */
    void paintBattleNetNoBuildSquare(int tileX, int tileY, int span) {
        int width = span;
        int height = span;
        if (world.map.width() <= tileX + width) {
            width = world.map.width() - tileX - 1;
        }
        if (world.map.height() <= tileY + height) {
            height = world.map.height() - tileY - 1;
        }
        int index = tileX + tileY * world.map.width();
        for (int row = 0; row < Math.max(0, height); row++) {
            for (int column = 0; column < Math.max(0, width); column++) {
                if (index >= 0 && index < world.battleNetNoBuild.length) {
                    world.battleNetNoBuild[index] = true;
                }
                index++;
            }
            index += world.map.width() - span;
        }
    }


    /** Native {@code 0x438610}: clears the exact, normally clipped footprint. */
    void clearBattleNetNoBuildFootprint(UnitType type,
            int tileX, int tileY) {
        int span = Math.max(1, type.tileWidth());
        int width = Math.min(span, world.map.width() - tileX);
        int height = Math.min(span, world.map.height() - tileY);
        for (int y = tileY; y < tileY + Math.max(0, height); y++) {
            for (int x = tileX; x < tileX + Math.max(0, width); x++) {
                if (world.map.contains(x, y)) {
                    world.battleNetNoBuild[x + y * world.map.width()] = false;
                }
            }
        }
    }


    void traceBattleNetBuildRejection(Unit builder, UnitType what,
            int tileX, int tileY, String reason) {
        if (System.getenv("CHONKCRAFT_TRACE_AIBUILD_CANDIDATES") != null) {
            System.err.printf("JBNPLACEREJECT worker=%d type=%s at=%d,%d reason=%s%n",
                    builder.id(), what.ident(), tileX, tileY, reason);
        }
    }


    /**
     * Finds BNE's unit-sized route to a building target.
     *
     * <p>{@code 0x41f5f0} replaces an order's target rectangle with an exact
     * point before the legacy pathfinder runs. {@link
     * #battleNetApproachPoint} preserves the separate map-object and building
     * rules that choose that point.</p>
     *
     * <p>The chosen point is inside the target's footprint. Native routine
     * {@code 0x4508f0} leaves the footprint blocked and marks the one-tile
     * ring around the whole target. Its wall follower stops on that ring,
     * which is observably different from making the target enterable.</p>
     */
    PathFinder.Path findBattleNetBuildingPath(Unit worker, Unit target) {
        return findBattleNetBuildingPath(worker, target, false);
    }


    /**
     * @param goldFreePrefixReplan when true, residual/short-leftover allies
     *     stay solid on a gold free-prefix replan so the blocked-goal
     *     wall-follow matches native (Orc 5 1534 SW vs pure W)
     */
    PathFinder.Path findBattleNetBuildingPath(Unit worker, Unit target,
            boolean goldFreePrefixReplan) {
        int[] point = world.battleNetApproachPoint(worker, target);
        if (target.type().givesResource() != null) {
            java.util.List<Unit> softBlockers = new ArrayList<>();
            boolean goldResource = target.type().givesResource()
                    == UnitType.Resource.GOLD;
            for (Unit candidate : world.units) {
                if (candidate == worker || !candidate.isOnMap()
                        || candidate.isDying()
                        || !world.isAllied(worker.player(), candidate.player())) {
                    continue;
                }
                // Native 0x4500f0 soft-clears allies only when their action
                // byte at CUnit+8 is Move (3). A HARVEST order alone is not
                // enough: Orc 14 tanker 1576 is stationary at 120,4 when
                // tanker 1566 plans, so it remains solid and turns the free
                // NE,E,NE ray into retail's NE,NE,SE wall-follow route.
                // Gold free-prefix mid-journey replans soft-clear residual
                // only on the pure major-axis slab toward the approach:
                // parallel-row corridor holds stay solid so Orc 5 1534 keeps
                // wall-follow SW,SW,W (soft-clear rewrote pure-major W,W,SW).
                // On-axis residual still soft-clears (Human 5 / XHuman 7
                // free-prefix). First gold path plans soft-clear all residual.
                boolean softClear;
                if (goldResource) {
                    // The Move body, not the pixel offset. Retail's test at
                    // 0x004507b5 is the action-state byte at record offset 8
                    // reading 3, and a unit keeps that byte through a whole
                    // sub-order: 0x004961b4 rests harvest sub-order 25 at 3,
                    // so a peon on it reads Move-body while it stands still.
                    // XHuman 7's 1447 is the witness. It shifts native order
                    // 23 to 25 on fixture 41 and its residual drains here on
                    // the same cycle, so this branch went from standing it
                    // aside to walling it, and peon 1446's fourth plan came
                    // back west where its first three came back south-west.
                    // Retail plans south-west six times and takes 109,106 the
                    // cycle after 1447 leaves it; this implementation left three cycles
                    // early for a square retail never uses.
                    //
                    // The shared soft clear was widened this way first and it
                    // did nothing, because this branch does not call it --
                    // which is E8's finding a second time, in the same method.
                    boolean inMoveBody = candidate.isMoving()
                            || (world.movement.onMoveAnimation(candidate)
                                    && candidate.pathLength() > 0);
                    if (!inMoveBody) {
                        softClear = false;
                    } else if (candidate.battleNetCollisionCounter() > 0) {
                        // 0x4501bc keeps a square occupied when the blocker's
                        // high nibble at 0x1d is set, whatever its animation
                        // says, and this branch was the one place that did not
                        // ask. XOrc 2's peon 1563 replans from 85,36 on
                        // fixture 42 with peon 1561 standing on 86,37 carrying
                        // nibble 1 and pixels still owed: native leaves the
                        // square walled and stores S,SE, and this implementation stood
                        // 1561 aside, drew SE first, and could not take it.
                        // Six refusals and a fifteen-cycle sleep followed.
                        softClear = false;
                    } else if (goldFreePrefixReplan
                            && candidate.pathLength() <= 1
                            && battleNetGoldParallelCorridorHold(
                                    worker, candidate, point[0], point[1])) {
                        // Pure-major approach with residual off the axis
                        // (Orc 5 y=101 holds while walker/approach share
                        // y=100): keep solid. Other free-prefix residual
                        // still soft-clears (XHuman 7 / XOrc 12).
                        softClear = false;
                    } else {
                        softClear = true;
                    }
                } else {
                    softClear = world.movement.battleNetSoftClearMoveAlly(
                            candidate);
                }
                if (!softClear) {
                    continue;
                }
                world.setMovementFieldFlags(candidate, false);
                softBlockers.add(candidate);
            }
            world.setMovementFieldFlags(worker, false);
            try {
                int targetLeft = target.tileX();
                int targetTop = target.tileY();
                int targetRight = targetLeft
                        + Math.max(1, target.type().tileWidth()) - 1;
                int targetBottom = targetTop
                        + Math.max(1, target.type().tileHeight()) - 1;
                BattleNetPathFinder.Passability traversalPassability =
                        world.battleNetTraversalPassability(worker);
                BattleNetPathFinder.Passability optimizationPassability =
                        (x, y) -> traversalPassability.canEnter(x, y)
                                && !world.battleNetUnitOccupies(
                                        softBlockers, x, y);
                int stride = world.battleNetMovementStride(worker);
                PathFinder.Path path = BattleNetPathFinder.find(
                        worker.tileX(), worker.tileY(), point[0], point[1],
                        stride,
                        // Native 0x450690 tests the route-grid anchor only.
                        // Large ships move on the even grid in two-tile
                        // steps, but the path search does not repeat the
                        // test across their 2-by-2 visual footprint.
                        traversalPassability,
                        optimizationPassability,
                        (x, y) -> x >= targetLeft - 1
                                && x <= targetRight + 1
                                && y >= targetTop - 1
                                && y <= targetBottom + 1,
                        false,
                        false,
                        // Gold free-prefix pure-major keep (Orc 7 peon 1582).
                        target.type().givesResource()
                                == UnitType.Resource.GOLD);
                world.traceBattleNetPath(worker, point[0], point[1], path);
                return path;
            } finally {
                world.setMovementFieldFlags(worker, true);
                for (Unit candidate : softBlockers) {
                    world.setMovementFieldFlags(candidate, true);
                }
            }
        }
        // A doubled BNE ship must receive headings drawn on its two-tile
        // anchor grid. The legacy ChonkCraft pathfinder below emits one-tile
        // headings; executing those with stride two made a tanker skip every
        // other route cell and sail onto coast on its second step home. The
        // retail target planner already marks the whole building skirt and
        // searches with battleNetMovementStride, which is the same question
        // MoveToDepot asks. Ordinary one-tile workers keep the established
        // legacy route until their native building-target path is transcribed.
        if (world.battleNetMovementStride(worker) > 1) {
            return world.findBattleNetTargetPath(worker, target);
        }
        world.setMovementFieldFlags(target, false);
        try {
            return world.findMovementPath(worker,
                    PathFinder.Goal.square(point[0], point[1]));
        } finally {
            world.setMovementFieldFlags(target, true);
        }
    }


    /**
     * Residual ally on a row/col parallel to a pure-major gold approach.
     *
     * <p>Worker and approach share a row (or column); the ally is off that
     * axis at Chebyshev 2+ toward the approach. Orc 5's y=101 corridor holds
     * from a walker at 34,100 toward 30,100 are the witness.
     */
    private static boolean battleNetGoldParallelCorridorHold(
            Unit worker, Unit candidate, int approachX, int approachY) {
        int wx = worker.tileX();
        int wy = worker.tileY();
        int cx = candidate.tileX();
        int cy = candidate.tileY();
        int allyCheb = Math.max(Math.abs(cx - wx), Math.abs(cy - wy));
        if (allyCheb < 2) {
            return false;
        }
        if (wy == approachY && cy != wy) {
            // Pure-major west/east: ally on a parallel row, not behind.
            if (Integer.signum(cx - wx) != Integer.signum(approachX - wx)
                    && cx != approachX && cx != wx) {
                return false;
            }
            return Math.abs(cx - wx) <= Math.max(1, Math.abs(approachX - wx))
                    || cx == wx;
        }
        if (wx == approachX && cx != wx) {
            if (Integer.signum(cy - wy) != Integer.signum(approachY - wy)
                    && cy != approachY && cy != wy) {
                return false;
            }
            return Math.abs(cy - wy) <= Math.max(1, Math.abs(approachY - wy))
                    || cy == wy;
        }
        return false;
    }





    /**
     * One cycle of walking up to a building, under some other order.
     *
     * <p>The same as walking to a tile except that the destination is the
     * building's whole footprint rather than the one square its tile position
     * names. {@link #findRouteToOrBeside} can only offer the eight neighbours
     * of that square, and for anything bigger than one square by one, three of
     * those eight are <em>inside</em> the building: a four by four Great Hall
     * has sixteen squares of perimeter and only five of them are ever tried.
     * Pack a couple of farms against its north-west corner -- which is what an
     * orc base looks like -- and a worker carrying a full load is told there is
     * no way to a hall eleven squares away, gives up, and is put back to work
     * by its owner's AI without ever banking the gold.
     *
     * <p>The pathfinder has taken a footprint and a range since it was written
     * and {@code moveTowards} already hands it one for an attack. This does the
     * same for a resource trip: end anywhere touching the building.
     */
    /**
     * How close a builder has to get to its site, and how close it may get.
     *
     * <p>Upstream asks the pathfinder for the building's whole footprint as
     * the goal, with a minimum range of one square only when the builder stays
     * outside what it is putting up, and a maximum range chosen when the order
     * was made: the builder's own repair range for a building it stays outside
     * of, one square for a shore building a land unit is raising -- "Peon
     * won't dive" -- and nought otherwise, which means standing on the site's
     * own ground and being swallowed by it.
     *
     * <p>The route and the arrival test have to be asked the same question.
     * Aiming beside the site and then insisting the builder stand on it leaves
     * it walking to a square it has already reached, for ever.
     */
    int siteMinRange(UnitType what) {
        return what.builderOutside() ? 1 : 0;
    }


    int siteMaxRange(Unit worker, UnitType what) {
        if (what.builderOutside()) {
            return Math.max(siteMinRange(what), worker.type().repairRange());
        }
        if (what.shoreBuilding() && worker.type().landUnit()) {
            return 1;
        }
        return 0;
    }


    /** Steps a builder along a route aimed at its site. See {@link #siteMaxRange}. */
    void walkToSite(Unit worker, int siteX, int siteY, int w, int h, int minRange, int maxRange) {
        // Drain a spent free-prefix residual before the empty-route gate so
        // the settle visit can replan without PF_WAIT 10 (XHuman 10 peon
        // 1551: free SE tip onto 20,56 while site goal is 40,79).
        if (worker.pathLength() == 0 && worker.isMoving()) {
            world.movement.walkPixels(worker);
            if (worker.isMoving()) {
                return;
            }
        }
        if (worker.pathLength() == 0 && !worker.isMoving()) {
            // Free-prefix mid-journey: skip emptied-buffer PF_WAIT when the
            // tip still sits short of the site (same rule as gold free-
            // prefix tips). Full segments still pay the ten.
            // cheb > maxRange (not max(1, maxRange)): Orc 10 peon 1583 free
            // tip at 43,3 with build goal 44,4 is cheb 1; requiring >1 left
            // it on PF_WAIT 10 while native residual-settled and stepped SE
            // onto 44,4 at fixture 35.
            boolean skipEmptyWait = false;
            if (worker.routeSpent() && worker.battleNetGoldFreePrefix()) {
                int goalX = worker.buildGoalX() >= 0
                        ? worker.buildGoalX() : siteX;
                int goalY = worker.buildGoalY() >= 0
                        ? worker.buildGoalY() : siteY;
                int cheb = Math.max(Math.abs(goalX - worker.tileX()),
                        Math.abs(goalY - worker.tileY()));
                if (World.BNE_IDLE_TRACE) {
                    System.err.printf("JBNBUILDWALK cycle=%d unit=%d at=%d,%d "
                                    + "goal=%d,%d cheb=%d range=%d free=%d%n",
                            world.cycle, worker.id(), worker.tileX(),
                            worker.tileY(), goalX, goalY, cheb, maxRange,
                            worker.battleNetGoldFreePrefix() ? 1 : 0);
                }
                if (cheb > maxRange) {
                    worker.setRouteSpent(false);
                    worker.setBattleNetGoldFreePrefix(false);
                    skipEmptyWait = true;
                }
            }
            // The spent route is served before the fresh ask here exactly as
            // in walkTowards and walkToWood: a refusal pops the route's last
            // element and the next attempt answers PF_WAIT for ten more from
            // the phantom element past the buffer (NextPathElement,
            // The game ). This walk was the one that never paid
            // it: on campaigns/human-exp/levelx06h p5's builder peon at
            // 75,102, its route to the farm site at 71,102 emptied by
            // refusals, re-asked at 62 and stepped where upstream serves the
            // ten and asks at 73 -- the map's first divergence.
            if (!skipEmptyWait && world.movement.spendTheEmptyRoute(worker)) {
                return;
            }
            PathFinder.Path path;
            if (worker.buildGoalX() >= 0 && worker.buildGoalY() >= 0) {
                int goalX = worker.buildGoalX();
                int goalY = worker.buildGoalY();
                path = world.findBattleNetPointPath(worker, goalX, goalY);
                // BNE's point finder returns FOUND with a local escape when
                // the goal sits behind a wall or under a building, never
                // UNREACHABLE. A segment that does not reduce Chebyshev
                // distance to the goal is the same refusal LegacyEngine's
                // footprint search would call PF_UNREACHABLE -- without
                // this, a farm behind a full-column wall (or a site taken
                // by a barracks) walks the wall forever and never hands
                // the job back after ten quarter-second asks.
                if (path.result() == PathFinder.Result.FOUND
                        && !battleNetBuildPathProgresses(worker, path,
                                goalX, goalY)) {
                    path = new PathFinder.Path(
                            PathFinder.Result.UNREACHABLE, new int[0]);
                }
            } else {
                path = world.pathFinder.find(worker.tileX(), worker.tileY(),
                        new PathFinder.Goal(siteX, siteY, w, h,
                                minRange, maxRange), world.moverFor(worker));
            }
            if (path.result() == PathFinder.Result.UNREACHABLE) {
                // An unreachable site is retried, not abandoned -- and for a
                // computer player every unreachable answer runs the shove
                // first, dice and all: DoActionMove's PF_UNREACHABLE case
                // calls AiCanNotMove before the order's arm ever sees the -2
                // and MoveToLocation then waits a
                // quarter second and asks again, ten asks in all, before
                // AiCanNotReach hands the job back ("Some tries to reach the
                // goal",). This implementation abandoned on
                // the first refusal: on campaigns/human-exp/levelx06h p5's
                // peon 129, boxed in beside its farm site, stood down where
                // upstream's retried and shoved peon 130 out of its way at
                // cycle 116 -- a drawn number and a flushed move this implementation
                // never made.
                world.aiCanNotMove(worker, siteX, siteY, w, h);
                worker.setBuildRouteTries(worker.buildRouteTries() + 1);
                if (worker.buildRouteTries() < World.BUILD_ROUTE_TRIES) {
                    worker.setWaitCycles(World.BUILD_RETRY_WAIT);
                    return;
                }
                aiHandBackBuild(worker);
                abandonPendingBuild(worker);
                // AiCanNotReach marks this COrder_Build finished. Its Build
                // label remains current for the rest of this cycle and the
                // dispatcher pops it to Still on the next, just like the
                // occupied-site failure below. levelx06h's boxed builder
                // exhausts its tenth ask at 188: upstream is Build at 188
                // and Still at 189, where an immediate stand-down made this
                // map's first divergence.
                worker.setOrderFinished(true);
                return;
            }
            if (path.result() != PathFinder.Result.FOUND) {
                worker.setOrder(Unit.Order.STILL);
                world.idle.stepStill(worker);
                return;
            }
            worker.setPath(path);
            // No path goal: like the other two forms, this order re-plans for
            // itself when the route runs out.
            worker.setPathGoal(-1, -1);
            int goalX = worker.buildGoalX() >= 0
                    ? worker.buildGoalX() : siteX;
            int goalY = worker.buildGoalY() >= 0
                    ? worker.buildGoalY() : siteY;
            world.markBattleNetPointFreePrefix(worker, path, goalX, goalY);
        }
        Unit.Order saved = worker.order();
        worker.setOrder(Unit.Order.MOVE);
        world.movement.stepMove(worker);
        if (worker.order() != Unit.Order.DYING) {
            worker.setOrder(saved);
        }
    }


    /**
     * Whether a BNE build route moves the worker closer to its point goal.
     *
     * <p>Headings are stored in reverse order (next step last), matching
     * {@link PathFinder.Path}. A full route that lands on the goal counts
     * as progress even when length is short; a partial segment that cuts
     * Chebyshev distance (including a {@code MAX_PATH} chunk of a long
     * open walk) also counts. A side-step that ends no closer is the
     * wall-escape FOUND the point finder invents for an unreachable goal.
     */
    static boolean battleNetBuildPathProgresses(Unit worker,
            PathFinder.Path path, int goalX, int goalY) {
        if (path.length() == 0) {
            return false;
        }
        int x = worker.tileX();
        int y = worker.tileY();
        int startDist = Math.max(Math.abs(x - goalX), Math.abs(y - goalY));
        int[] headings = path.headings();
        for (int i = headings.length - 1; i >= 0; i--) {
            x += Direction.deltaX(headings[i]);
            y += Direction.deltaY(headings[i]);
        }
        if (x == goalX && y == goalY) {
            return true;
        }
        int endDist = Math.max(Math.abs(x - goalX), Math.abs(y - goalY));
        return endDist < startDist;
    }


    /**
     * Sends a worker to put up a building.
     *
     * <p>This implementation reserves the cost when the order is given. Until a site is
     * actually created, replacing, stopping or failing to reach the order
     * refunds that reservation in full; once construction starts, the
     * construction-cancellation percentage applies.
     *
     * @return whether the order was accepted
     */
    boolean orderBuild(Unit worker, UnitType what, int tileX, int tileY) {
        if (worker.type().building() || !what.building() || !worker.isAlive()) {
            return false;
        }
        if (!mayBuild(worker.type(), what)) {
            return false;
        }
        // On behalf of this worker, not of nobody. An on-top rule refuses a
        // site with anything of the parent's own movement kind standing on it,
        // and a tanker sent to an oil patch is usually standing on the patch by
        // the time the order is given -- upstream skips the builder for exactly
        // that reason.
        if (!canPlaceBuilding(worker, what, tileX, tileY)) {
            return false;
        }
        // The order is given, not bought. {@code CommandBuildBuilding} makes
        // the order and takes nothing; the cost is subtracted by
        // {@code COrder_Build::StartBuilding} -- "unit.Player->SubUnitType
        // (type)" -- when the worker has
        // walked to the site and the foundation actually goes down. Paying
        // here instead moved the whole bank the moment the order was issued:
        // on maps/skirmish/(2)2-players this implementation's computer dropped from
        // 2,000 gold and 1,000 wood to 800 and 200 on cycle 13, where
        // upstream's does it on cycle 89, which is how long the peasant takes
        // to get there.
        //
        // Affordability is still asked, because an order for something the
        // bank cannot cover is refused rather than begun: that is
        // {@code player.CheckUnitType(type)} on the same path

        if (!world.players[worker.player()].canAfford(what.costs())) {
            return false;
        }
        // Counted as doing what it was doing until the queue is popped. The
        // command leaves the order it interrupts in place and marked finished
        // and puts the new one behind it, so
        // {@code CurrentAction} still answers with the old one for the rest of
        // this cycle -- and a build order is always given from outside the
        // unit's own step, by the AI's thought or by a player's command, so
        // this is every build order.
        Unit.Order before = worker.order();
        worker.setPendingBuild(what);
        worker.setBuildReached(false);
        worker.setBuildWalked(false);
        worker.setBuildTile(tileX, tileY);
        // 0x4513d0 stores an AI hall candidate directly in +0x84 with a
        // null +0x88 target. Do not pass that point through the unit-
        // target footprint clamp: Orc 5's native hall route is five SW
        // headings to 109,48, whereas clamping it to 112,48 bends the
        // second heading south. Ordinary placement still uses the older
        // approximation below; applying the raw-point rule to every
        // building exposes separate candidate-search discrepancies.
        boolean hall = what.stores().contains(UnitType.Resource.GOLD);
        worker.setBuildGoal(
                hall ? tileX : world.battleNetFootprintGoal(worker.tileX(), tileX,
                        Math.max(1, what.tileWidth())),
                hall ? tileY : world.battleNetFootprintGoal(worker.tileY(), tileY,
                        Math.max(1, what.tileHeight())));
        if (World.BNE_IDLE_TRACE) {
            System.err.printf(
                    "JBNBUILDORDER cycle=%d unit=%d at=%d,%d site=%d,%d size=%dx%d goal=%d,%d type=%s%n",
                    world.cycle, worker.id(), worker.tileX(), worker.tileY(),
                    tileX, tileY, what.tileWidth(), what.tileHeight(),
                    worker.buildGoalX(), worker.buildGoalY(), what.ident());
        }
        // A command does not break an unbreakable animation. A worker
        // mid-step keeps its old order -- still current, as upstream's
        // finished-but-unpopped Orders[0] is -- until the animation lets
        // go, and the build starts fresh from wherever the step landed.
        // level08h's peon 93 is told at cycle 37 while walking to its
        // mine: upstream shows resource through 39 and founds the farm on
        // 40, and this implementation used to flip on the spot, bill the build order
        // for the step still in flight, and serve a walked builder's
        // ten-cycle arrival pause a builder that never walked does not owe.
        if (worker.animation().unbreakable()) {
            worker.setBuildLatchedFrom(before);
            return true;
        }
        worker.setOrder(Unit.Order.BUILD);
        worker.rememberActionBeforeQueued(before);
        worker.clearPath();
        return true;
    }


    /**
     * Issues a retail BNE computer-player build order and reserves its cost.
     *
     * <p>The DOS/LegacyEngine command path represented by {@link #orderBuild}
     * pays only when the foundation is placed. Retail BNE's native AI ready
     * callback is different: after installing raw action 28 it calls its
     * resource-subtraction routine immediately. Cycle-one campaign snapshots
     * therefore show both the walking builder and the reduced bank. The paid
     * bit lets the ordinary construction path recognize that reservation and
     * prevents it charging the building a second time.</p>
     */
    boolean orderBattleNetAiBuild(Unit worker, UnitType what,
            int tileX, int tileY) {
        // A worker still serving the stand-down it was given when it handed a
        // job back cannot be given another one. XHuman 2 peon 1560 hands back
        // on 52 with three cycles on its timer and retail does not order it to
        // build again until 55, on ground that was free the whole time.
        if (world.battleNetStandingDownFromBuild(worker)) {
            return false;
        }
        if (!orderBuild(worker, what, tileX, tileY)) {
            return false;
        }
        // orderBuild made the same affordability check immediately above, and
        // no other actor can alter this single-threaded world between the two
        // calls. Keep a defensive rollback for hand-built test Players whose
        // resource implementation may be replaced later.
        if (!world.players[worker.player()].pay(what.costs())) {
            abandonPendingBuild(worker);
            worker.setActionBeforeQueued(null);
            worker.setOrder(Unit.Order.STILL);
            return false;
        }
        worker.setBuildPaid(true);
        markBattleNetPendingBuildReservation(what, tileX, tileY);
        world.battleNetAiBuildReservations.add(worker);
        return true;
    }


    /**
     * Whether a worker of this type may raise this building.
     *
     * <p>An oil tanker is a gatherer and gathers; the only thing it may build
     * is an oil platform, and only over an oil patch. Nothing but the button
     * table said so, so an AI that could issue a build order sent tankers to
     * put pig farms on dry land.
     *
     * <p>A world assembled without a button table -- every hand-built fixture,
     * and the map editor -- has no relation to consult, and refusing every
     * build order there would leave it unable to build anything at all. So an
     * empty table means the question was never asked rather than answered no.
     * {@code GameData.loadMission} always supplies one.
     */
    boolean mayBuild(UnitType worker, UnitType what) {
        if (world.builders.isEmpty() || worker == null || what == null) {
            return true;
        }
        java.util.Set<String> allowed = world.builders.get(what.ident());
        return allowed != null && allowed.contains(worker.ident());
    }


    /**
     * Hands a failed build back to its owner's queue as merely wanted.
     *
     * <p>{@code AiCanNotBuild} and {@code AiCanNotReach} both end in
     * {@code AiReduceMadeInBuilt}: the entry's
     * made count drops and the next walk starts the job again. A command
     * that merely replaces the order is not a failure -- upstream's flush
     * runs {@code COrder_Build::Cancel}, which reduces nothing -- so this
     * sits beside the failure exits only, never inside the refund below.
     */
    void aiHandBackBuild(Unit worker) {
        UnitType what = worker.pendingBuild();
        net.chonkbase.chonkcraft.engine.ai.AiPlayer ai = world.ais().get(worker.player());
        if (ai != null && what != null) {
            if (System.getenv("CHONKCRAFT_TRACE_AICOLLECT") != null) {
                System.err.println("JHANDBACK " + world.cycle + " unit=" + worker.id()
                        + " at " + worker.tileX() + "," + worker.tileY()
                        + " gives back " + what.ident());
            }
            ai.reduceMade(world, what);
        }
    }


    /** Refunds work that never reached the point of creating a building. */
    void abandonPendingBuild(Unit worker) {
        UnitType what = worker.pendingBuild();
        if (what == null) {
            return;
        }
        if (world.battleNetAiBuildReservations.remove(worker)) {
            // FUN_00438610 clears only the type's exact footprint at the
            // stored top-left. It leaves the one-tile enlargement painted
            // when the job was installed and may erase an older reservation
            // on overlapping squares: the native bitmap has no refcounts.
            clearBattleNetNoBuildFootprint(what, worker.buildTileX(),
                    worker.buildTileY());
        }
        // Nothing to give back unless the foundation was actually paid for.
        // Upstream has the same asymmetry for the same reason: the cost goes
        // out in StartBuilding, so an order abandoned on the way there never
        // cost anything.
        if (worker.buildPaid()) {
            world.refund(worker.player(), what.costs(), 100);
        }
        worker.setBuildPaid(false);
        worker.setBuildReached(false);
        worker.setBuildWalked(false);
        worker.setPendingBuild(null);
        worker.setBuildTile(-1, -1);
        worker.setBuildGoal(-1, -1);
    }


    /**
     * Whether a building's whole footprint is clear buildable ground.
     *
     * <p>The terrain a building will accept comes from its own type rather
     * than being assumed to be land: a shipyard or a refinery stands on the
     * coast, and this asked every building for {@code LAND_ALLOWED}, so no
     * shore building could be placed anywhere at all.
     *
     * <p>And on top of the footprint test, {@code CanBuildHere}
     * The game requires a shore building to cover at least one
     * coast square -- {@code HasAtLeastOneCoastTile}. Without that half the
     * terrain rule alone would let a shipyard be founded in the middle of the
     * ocean, since open water breaks none of it.
     *
     * <p>The builderless form, for a placement cursor and for the computer's
     * site search. Upstream takes the same call with a null unit -- its
     * {@code CanBuildHere(nullptr...)} path -- and answers about the ground
     * alone.
     */
    boolean canPlaceBuilding(UnitType what, int tileX, int tileY) {
        return canPlaceBuilding(null, what, tileX, tileY);
    }


    /**
     * The same question asked on behalf of a particular worker.
     *
     * <p>Implements {@code CanBuildUnitType}. The order of its two halves is
     * the whole of what was missing.
     * {@code CanBuildHere} runs first and its answer can be a <em>unit</em>
     * rather than a yes: when a building rule says this type is founded on top
     * of something, the thing underneath is returned and the terrain is never
     * looked at. Upstream says so in a comment on the first line of the
     * function -- "Terrain Flags don't matter if building on top of a unit".
     *
     * <p>Without that, an oil platform could not be built anywhere in the
     * game. It is founded on an oil patch, the patch reserves its nine squares
     * against building on ({@code MapFieldNoBuilding}), and the footprint test
     * below refuses every one of them. Measured across the campaign: 105
     * patches on 29 of the 52 maps, and
     * {@code orderBuild(tanker, unit-human-oil-platform, patch)} answered false
     * on all of them. Oil is the third resource and the missions that carry it
     * are the naval half of both campaigns.
     *
     * @param builder the worker that would raise it, or {@code null}
     */
    boolean canPlaceBuilding(Unit builder, UnitType what, int tileX, int tileY) {
        BuildSite site = canBuildHere(builder, what, tileX, tileY);
        if (!site.allowed()) {
            return false;
        }
        if (site.onTop() != null) {
            return true;
        }
        // The builder does not block its own site. {@code CanBuildUnitType}
        // takes the worker off the map before it walks the footprint --
        // "Remove unit that is building!", {@code UnmarkUnitFieldFlags(*unit)}
        // -- and puts it back at the end
        // It has to: a builder is usually
        // standing in the ground it is about to build on, and upstream aims it
        // there deliberately.
        //
        // The build itself already knew this and the AI's search for a site
        // did not, so the AI ruled out every square its own worker was
        // standing in. On (2)mysterious-dragon-isle that cost it the site
        // upstream picks: a peon at 14,121 refused 13,119, the great hall
        // upstream founds on the spot at cycle 13, and took 13,122 instead.
        boolean marked = builder != null && builder.isOnMap();
        if (marked) {
            world.markOccupancy(builder, false);
        }
        try {
            return footprintIsClear(what, tileX, tileY);
        } finally {
            if (marked) {
                world.markOccupancy(builder, true);
            }
        }
    }


    /**
     * Runs a type's {@code BuildingRules} against a site.
     *
     * <p>Implements {@code CanBuildHere}. The outer
     * list is an or and each entry is an and: the loop returns on the first
     * whole and-list that passes, and refuses the site when a type has rules
     * and none of them does.
     *
     * <p>The shore-building coast test comes before the rules because upstream
     * puts it there and labels it "Must be checked before oil!".
     */
    BuildSite canBuildHere(Unit builder, UnitType what, int tileX, int tileY) {
        int width = Math.max(1, what.tileWidth());
        int height = Math.max(1, what.tileHeight());
        if (!world.map.contains(tileX, tileY)
                || !world.map.contains(tileX + width - 1, tileY + height - 1)) {
            return BuildSite.REFUSED;
        }
        if (what.shoreBuilding() && !world.hasAtLeastOneCoastTile(what, tileX, tileY)) {
            return BuildSite.REFUSED;
        }
        if (what.buildingRules().isEmpty()) {
            return BuildSite.PLAIN;
        }
        for (java.util.List<net.chonkbase.chonkcraft.engine.unit.BuildRestriction> andList
                : what.buildingRules()) {
            Unit onTop = null;
            boolean passed = true;
            for (net.chonkbase.chonkcraft.engine.unit.BuildRestriction rule : andList) {
                switch (rule) {
                    case net.chonkbase.chonkcraft.engine.unit.BuildRestriction.OnTop top -> {
                        onTop = world.onTopTarget(builder, top, tileX, tileY);
                        passed = onTop != null;
                    }
                    case net.chonkbase.chonkcraft.engine.unit.BuildRestriction.Distance far ->
                        passed = world.passesDistanceRule(builder, what, far, tileX, tileY);
                    case net.chonkbase.chonkcraft.engine.unit.BuildRestriction.Unsupported ignored ->
                        passed = false;
                }
                if (!passed) {
                    break;
                }
            }
            if (passed) {
                return onTop == null ? BuildSite.PLAIN : new BuildSite(onTop, true);
            }
        }
        return BuildSite.REFUSED;
    }


    /**
     * The terrain half of {@code CanBuildUnitType}: every square of the
     * footprint has to be ground this type accepts and hold nothing.
     */
    boolean footprintIsClear(UnitType what, int tileX, int tileY) {
        int width = Math.max(1, what.tileWidth());
        int height = Math.max(1, what.tileHeight());
        long allowed = Unit.movementMaskFor(what);
        for (int dy = 0; dy < height; dy++) {
            for (int dx = 0; dx < width; dx++) {
                MapField field = world.map.fieldOrNull(tileX + dx, tileY + dy);
                if (field == null
                        || !field.hasFlag(allowed)
                        || field.hasFlag(TileFlag.UNPASSABLE)
                        || field.hasFlag(TileFlag.NO_BUILDING)
                        || field.isOccupied()) {
                    return false;
                }
            }
        }
        return true;
    }


    /** Walks a builder to its site and starts the work. */
    void stepWalkToSite(Unit worker) {
        if (worker.battleNetOrderDelay() > 0) {
            worker.setBattleNetOrderDelay(worker.battleNetOrderDelay() - 1);
            return;
        }
        // The give-up latch: a build that failed last cycle kept its label
        // through the cycle it failed on -- upstream only marks the order
        // Finished, and HandleUnitAction pops it to a fresh Still on the
        // next, whose first execute runs at once. On
        // campaigns/human-exp/levelx05h p7's peon exhausts its nine
        // blocked-ground tries during cycle 103 and upstream still reads
        // build there, still at 104; this implementation used to read still a cycle
        // early, the map's one finding in nine hundred cycles.
        if (worker.orderFinished()) {
            worker.setOrderFinished(false);
            worker.setPendingBuild(null);
            worker.setOrder(Unit.Order.STILL);
            world.idle.stepStill(worker);
            return;
        }
        UnitType what = worker.pendingBuild();
        if (what == null) {
            worker.setOrder(Unit.Order.STILL);
            return;
        }
        int siteX = worker.buildTileX();
        int siteY = worker.buildTileY();

        // The site is the whole footprint, not its top-left square. Upstream
        // hands the pathfinder {@code input.SetGoal(this->goalPos, tileSize)}
        // and a minimum range of one only when the builder stays outside
        // so a peasant that will be
        // swallowed by its own hall has to be standing on the hall's ground.
        // Measuring to the corner instead sends it walking across a site it
        // has already reached: on 2-players ours took a step to 80,83 on cycle
        // 78 that upstream's peasant, sitting on the same footprint at 81,83,
        // never took.
        int siteW = Math.max(1, what.tileWidth());
        int siteH = Math.max(1, what.tileHeight());
        int workerW = Math.max(1, worker.type().tileWidth());
        int workerH = Math.max(1, worker.type().tileHeight());
        // Measure the two occupied rectangles, not merely their top-left
        // anchors. The BNE oil tanker is 2x2: at 47,9 its hull is already one
        // square from the 3x3 patch at 49,11. The pathfinder therefore returns
        // FOUND with an empty route, while the old anchor-only check called it
        // two squares away and asked the same empty route forever. One-square
        // land builders retain exactly the old arithmetic.
        int workerRight = worker.tileX() + workerW - 1;
        int workerBottom = worker.tileY() + workerH - 1;
        int siteRight = siteX + siteW - 1;
        int siteBottom = siteY + siteH - 1;
        int awayX = Math.max(0,
                Math.max(siteX - workerRight, worker.tileX() - siteRight));
        int awayY = Math.max(0,
                Math.max(siteY - workerBottom, worker.tileY() - siteBottom));
        int away = Math.max(awayX, awayY);
        int nearest = siteMinRange(what);
        // A building that goes up on top of another -- an oil platform on a
        // patch -- has its ground held by the parent until the moment it is
        // replaced, so nothing can stand on it and its builder aims beside it
        // instead. Upstream reaches these through the "ontop" unit
        // CanBuildUnitType hands back; what
        // its pathfinder does with a goal rectangle the mover cannot enter is
        // not transcribed here yet.
        int reach = what.onTopRule() != null ? 1 : siteMaxRange(worker, what);
        // A step already in flight has to land before any of this counts.
        // Upstream's builder is Moving through the last of it -- on 2-players
        // it is on its site from cycle 62 and still Moving until 76 -- and it
        // is the cycle it stops on that the path runs out under it.
        // Retail's build order walks to the fixed point chosen when the
        // order is installed, not merely to any square in the footprint.
        // The rectangle remains the placement test, but an inside-builder
        // is not at the end of its walk until it reaches that point.  In
        // XHuman 6 the pig-farm peon entered the top-left footprint square
        // (16,85) while its fixed point was the south edge (16,86); native
        // laid one final S route and founded five cycles later.  Treating the
        // first footprint square as arrival founded immediately and made the
        // AI look as though it had issued the build early.
        boolean exactPointArrival = !what.builderOutside()
                && what.onTopRule() == null
                && worker.buildGoalX() >= 0 && worker.buildGoalY() >= 0;
        boolean atSite = exactPointArrival
                ? worker.tileX() == worker.buildGoalX()
                        && worker.tileY() == worker.buildGoalY()
                : away <= reach && away >= nearest;
        // Upstream does not measure whether it has arrived; it asks.
        // {@code COrder_Build}'s walk goes through {@code DoActionMove} like
        // any other, so the cycle a builder's route runs out costs the usual
        // PF_WAIT and ten cycles, and what ends the walk is the search on the
        // call after that. On {@code maps/skirmish/(3)critter-attack} upstream's
        // peasant reaches 65,78 on cycle 42, waits, and asks on 53.
        //
        // Asking is the point rather than the answer, which this implementation had
        // right by measurement anyway. A builder standing on its own site is
        // inside the goal rectangle, which is the one case
        // {@code AStarFindSimplePath} refuses outright -- so the question goes
        // to the full search, and {@code AStarCleanUp} empties
        // {@code CostMoveToCache} on the way past. That memo is what every
        // one-square step in the game is judged by, so a search not run is a
        // memo not emptied: a critter at 66,76 read a cost from cycle 40 on
        // cycle 62 and stepped at a square that had stopped being free.
        // The pause this implementation already serves through {@code buildReached} is
        // the PF_WAIT the last refused square costs; the search is the call
        // after it, which is why this is asked once that pause is behind the
        // builder rather than on the cycle it began.
        // Once, and once only. The search is the call that ends the walk;
        // everything after it is {@code COrder_Build} retrying
        // {@code CheckCanBuild} on its own ten-cycle clock, and that retry
        // touches no pathfinder at all. Asking again on every retry empties the cost memo every
        // eleven cycles, which is the opposite fault to the one this fixed:
        // on {@code (3)critter-attack} upstream runs no search whatever
        // between cycles 64 and 107, and a critter at 64,24 is still stepping
        // at 65,23 on a number from before 64 because of it.
        if (atSite && worker.buildWalked() && worker.buildReached()
                && worker.buildTries() == 0 && !worker.isMoving()) {
            world.pathFinder.find(worker.tileX(), worker.tileY(),
                    new PathFinder.Goal(siteX, siteY, siteW, siteH, nearest, reach),
                    world.moverFor(worker));
        }
        if (!atSite) {
            worker.setBuildWalked(true);
            if (what.onTopRule() != null) {
                // Aimed beside, not at: a route into the parent's own squares
                // is a route to ground nothing can stand on, and asking for one
                // gets no route at all and an order abandoned on its first
                // cycle. findRouteToOrBeside is the form that knows this.
                world.movement.walkTowards(worker, siteX, siteY);
            } else {
                walkToSite(worker, siteX, siteY, siteW, siteH, nearest, reach);
            }
            if (worker.order() != Unit.Order.BUILD) {
                aiHandBackBuild(worker);
                abandonPendingBuild(worker);
            }
            return;
        }
        // On the footprint with residual still draining: drain only. Walking
        // through walkToSite re-opens the empty-route PF_WAIT and leaves the
        // builder outside StartBuilding (XOrc 10 peasant 1573: native founds
        // the farm at fixture 22 on the settle cycle; cold-commit kept isMoving
        // true and never reached buildReached). Fall through once residual
        // clears so battleNetPointReached can StartBuilding the same visit.
        if (worker.isMoving()) {
            worker.setBuildWalked(true);
            world.movement.walkPixels(worker);
            if (worker.isMoving()) {
                return;
            }
        }

        // Standing on the site is not yet arriving at it -- for a builder that
        // had to walk. Somewhere in the last step DoActionMove finds the square
        // it wants refused and reports PF_WAIT, which is the one thing
        // NextPathElement returns it for, and
        // "No path, wait" sets unit.Wait = 10;
        // only the action after that sleep lets StartBuilding run. Upstream's
        // walkers show the gap identically on two maps: on x-marks-the-spot the
        // peasant is standing on its site at cycle 25 and the hall appears at
        // 52, on 2-players it is standing at 62 and the hall appears at 89.
        //
        // A builder that never moved never had a square refused. NewPath
        // reports PF_REACHED on the first ask and the foundation goes down in
        // the same action the order arrived in: on mysterious-dragon-isle the
        // peon is already inside the great hall's footprint at 14,121 when it
        // is told to build at 13,119, and upstream's hall is there on cycle 13.
        if (!worker.buildReached()) {
            worker.setBuildReached(true);
            boolean battleNetPointReached = worker.buildGoalX() == worker.tileX()
                    && worker.buildGoalY() == worker.tileY();
            if (worker.buildWalked() && !battleNetPointReached) {
                worker.setWaitCycles(Math.max(worker.waitCycles(), World.MAX_PATH_WAIT));
                return;
            }
            if (battleNetPointReached) {
                // BNE's fixed point path checks the exact order goal before
                // consuming the route terminator. XOrc 10's peasant drains
                // its north step at (110,4) and founds the farm at (109,3)
                // on the same fixture cycle; the generic ChonkCraft builder walk
                // instead pays the empty-route ten before StartBuilding.
                worker.setRouteSpent(false);
            }
        }

        // The builder goes inside the building, so it must not count against
        // its own site. It will usually be standing on it: walking adjacent to
        // a site's top-left corner routinely lands inside the footprint of
        // anything bigger than one square.
        world.markOccupancy(worker, false);
        BuildSite where = canBuildHere(worker, what, siteX, siteY);
        boolean clear = where.allowed()
                && (where.onTop() != null || footprintIsClear(what, siteX, siteY));
        if (!clear) {
            world.markOccupancy(worker, true);
        }

        // Someone else may have taken the ground while the worker walked --
        // and a worker does not give the job up over that. {@code COrder_Build}
        // counts the refusals in its own State: the order arrives at
        // State_NearOfLocation, 11, every refusal from CheckCanBuild does
        // {@code this->State++} with {@code unit.Wait = 10} -- "to keep the
        // load low, retry each 10 cycles" --
        // and it gives up only at State_StartBuilding_Failed, 20. Nine tries
        // ninety cycles apart for the ground to clear.
        //
        // This implementation abandoned on the first refusal. On
        // maps/skirmish/(3)critter-attack that is cycle 53: an orc peasant at
        // 65,78 with a town hall to put down at 62,78 finds a critter wandering
        // through the footprint, and upstream's is still building at cycle 62
        // where this implementation's had gone back to standing.
        if (!clear) {
            // Retail's own computer player does not wait for the ground. Its
            // build is paid when the job is installed, and when the peon gets
            // there and finds the footprint occupied it gives the whole job
            // back on that cycle: XHuman 2's peon 1560 is on its pig-farm site
            // at 65,57 with peon 1564 standing at 66,57, inside the footprint,
            // and at fixture 52 p5's bank goes back up from 300,300 to 800,550
            // and the peon reads Still. 1564 steps off to 67,58 and the job is
            // installed again at 55, bank down to 300,300 and the peon back on
            // Build. The retry above is the DOS COrder_Build state machine and
            // was fitted to a skirmish map, not to this AI, so it applies only
            // where retail is not the one holding the reservation.
            // The stand-down is on this cycle too, not the next: retail reads
            // Still at 52, the same cycle the money comes back, where the
            // route-failure path below deliberately keeps its Build label for
            // the rest of the cycle and pops it on the one after.
            if (world.battleNetAiBuildReservations.contains(worker)) {
                aiHandBackBuild(worker);
                abandonPendingBuild(worker);
                worker.setOrder(Unit.Order.STILL);
                // Retail's peon stands for three cycles before it takes
                // another build order, and the wait is its own rather than the
                // AI's. XHuman 2 peon 1560 hands back on 52 with its timer at
                // unit+0x07 set to three; it reads 3, 2, 1 across 52, 53 and
                // 54, and on 55 the timer reloads and the order becomes Build
                // again. The two-by-two footprint it then claims -- 65,51 to
                // 66,52 -- is plain free land for all three of those cycles,
                // so nothing was waiting for the ground.
                //
                // This implementation used to take the new order on 53, which spent the
                // 500 gold and 250 wood two cycles early and left the peon
                // reading Build where retail has it Still.
                worker.setBattleNetBuildHandBackCycle(world.cycle);
                worker.setOrderFinished(true);
                return;
            }
            worker.setBuildTries(worker.buildTries() + 1);
            if (worker.buildTries() < World.BUILD_TRIES) {
                worker.setWaitCycles(Math.max(worker.waitCycles(), World.UNREACHABLE_WAIT));
                return;
            }
            aiHandBackBuild(worker);
            abandonPendingBuild(worker);
            // The hand-back lands now; the label survives the cycle and the
            // latch above pops it. State_StartBuilding_Failed only marks the
            // order Finished.
            worker.setOrderFinished(true);
            return;
        }

        // And now it is paid for, which is where StartBuilding does it. A bank
        // that will not cover it any more waits and asks again rather than
        // giving up: "To keep the load low, retry each 10 cycles" with
        // unit.Wait = 10.
        if (!worker.buildPaid()) {
            if (!world.players[worker.player()].pay(what.costs())) {
                world.markOccupancy(worker, true);
                worker.setWaitCycles(Math.max(worker.waitCycles(), World.UNREACHABLE_WAIT));
                return;
            }
            worker.setBuildPaid(true);
        }

        // What is underneath goes now, before the platform is put down, or the
        // patch's own nine squares of MapFieldNoBuilding would still be set
        // when createUnit marks the platform's. {@code COrder_Build::
        // StartBuilding} does the same in
        // the same order, and takes the parent's stock across first: the
        // platform is worth exactly the oil the patch had left, and a platform
        // that started on its own type's figure would be a well of 650.
        Unit under = where.onTop();
        int inherited = -1;
        if (under != null && what.onTopRule() != null && what.onTopRule().replaceOnBuild()) {
            inherited = under.resourcesHeld();
            world.removeReplacedParent(under);
        }

        Unit site = world.createUnit(what, worker.player(), siteX, siteY);
        if (site == null) {
            world.markOccupancy(worker, true);
            aiHandBackBuild(worker);
            abandonPendingBuild(worker);
            worker.setOrder(Unit.Order.STILL);
            return;
        }
        if (inherited >= 0) {
            site.setResourcesHeld(inherited);
        }
        site.setOrder(Unit.Order.UNDER_CONSTRUCTION);
        site.setProgress(0);
        site.setProgressGoal(what.costs().getOrDefault(UnitType.Resource.TIME, 1) * World.PROGRESS_PER_TIME_UNIT);
        // A building starts as a frame and gains its hit points as it goes up.
        // Retail BNE gives a fresh foundation one tenth of the completed
        // type's hit points (XOrc 10 farm: 40 of 400 at its founding cycle),
        // where the later LegacyEngine construction model began at one.
        site.setHitPoints(Math.max(1, what.hitPoints() / 10));
        // The foundation call itself is cadence slot one. Ten following
        // no-op Built executions (plus this cycle when the site is
        // processed after create) put the first Boost on fixture c33
        // after the XOrc 10 farm was founded at c22. Delay 9 climbed
        // too early (41 at c33 while native jumped 40→43); delay 10
        // without same-cycle process left Java at 40 on c33.
        site.setBattleNetOrderDelay(10);
        site.setWorksite(worker);

        // One last breath on the doorstep. StartBuilding plays a step of the
        // still animation right before it removes the worker --
        // {@code UnitShowAnimation(unit, &unit.Type->Animations->Still)},
        // and when that step lands on the
        // animation's random-goto it costs a draw off the shared stream.
        // Plain, not the wait overlay: the step is not backed out when the
        // worker emerges. On campaigns/orc-exp/levelx04o this is cycle 10
        // exactly: player 2's peasant breathes as it steps inside its new
        // barracks, 44 draws that cycle against this implementation's 43, and every
        // number either engine drew afterwards was a different number.
        Animation doorstep = world.stillAnimation(worker);
        if (doorstep != null) {
            worker.animation().switchTo(doorstep);
            world.advance(worker);
        }

        // The worker goes inside and off the map for the duration. Its
        // occupancy is already cleared by the placement check above.
        world.markSight(worker, false);
        worker.setRemoved(true);
        worker.setWorksite(site);
        worker.clearPath();
        world.battleNetAiBuildReservations.remove(worker);
        worker.setPendingBuild(null);
        worker.setBuildTile(-1, -1);
        worker.setBuildGoal(-1, -1);
        // Inside means standing where the building stands, and still building.
        // {@code CUnit::Remove(host)} ends with
        // {@code UnitInXY(*this, host->tilePos)}, and the builder keeps its
        // build order for the whole job: upstream reports its peasant at the
        // hall's own square under action 17 from the cycle the foundation goes
        // down until the roof is on, where this implementation left it standing on the
        // square it walked to with nothing to do.
        worker.setTile(site.tileX(), site.tileY());
        worker.setOrder(Unit.Order.BUILD);
    }


    /** Advances a building that is going up. */
    void stepConstruction(Unit site) {
        // Retail BNE runs no-op Built executions after the foundation frame
        // before the first Boost (XOrc 10 farm founded at fixture c22 with
        // HP 40, first climb at c33 to 43). Without this delay Java climbed
        // 40→41 on c24 while native held 40.
        if (site.battleNetOrderDelay() > 0) {
            site.setBattleNetOrderDelay(site.battleNetOrderDelay() - 1);
            world.stepWorkAnimation(site, AnimationSet.State.BUILD);
            return;
        }
        world.stepWorkAnimation(site, AnimationSet.State.BUILD);
        int full = site.type().hitPoints();
        int before = site.progress();
        // BNE does not drip LegacyEngine's 100-progress / ~1 HP per cycle.
        // Sealed farm 1426 climbs in sparse boosts: accumulate
        // (full - foundation) each boost and pay pool/buildTime hit
        // points (40→43→47→50…), then sleep for the rest of the twelve-
        // cycle construction cadence. Progress still advances one time
        // unit per boost so the roof lands after buildTime boosts.
        int foundation = Math.max(1, full / 10);
        int buildTime = Math.max(1, site.type().costs()
                .getOrDefault(UnitType.Resource.TIME, 1));
        int pool = site.battleNetConstructionHpPool()
                + (full - foundation);
        int gained = pool / buildTime;
        site.setBattleNetConstructionHpPool(pool % buildTime);
        if (gained > 0) {
            site.setHitPoints(Math.min(full, site.hitPoints() + gained));
        }
        site.setProgress(before + World.PROGRESS_PER_TIME_UNIT);
        // This boost cycle counts as one of the twelve; eleven quiet
        // Built visits follow before the next climb.
        site.setBattleNetOrderDelay(
                World.BATTLE_NET_CONSTRUCTION_BOOST_PERIOD - 1);

        if (site.progress() < site.progressGoal()) {
            return;
        }
        // "Check if building ready" is gated on the animation letting go:
        // COrder_Built finishes only when !unit.Anim.Unbreakable holds
        // beside the filled counter, so a frame
        // whose construction animation is mid-flight stands one more beat.
        // level06h's pig farm fills its counter with the animation held and
        // reads built at 670, still from 671; this implementation finished the moment
        // the counter filled.
        if (site.animation().unbreakable()) {
            return;
        }
        site.setHitPoints(full);
        // Finish runs whole on the fill cycle -- hit points, the builder's
        // release, the announcement -- but the order is only marked, and
        // the site reads built through the cycle it completed, still from
        // the next: level06h's farm fills at 670 and shows built there,
        // still at 671, its builder's own label popping beside it. The
        // one-cycle report convention carries both.
        site.setOrder(Unit.Order.STILL);
        site.rememberActionBeforeQueued(Unit.Order.UNDER_CONSTRUCTION);
        // AiWorkComplete -> AiRemoveFromBuilt: the roof
        // is on, and the queue entry that held the job lets go.
        net.chonkbase.chonkcraft.engine.ai.AiPlayer siteAi = world.ais().get(site.player());
        if (siteAi != null) {
            siteAi.workComplete(world, site.type());
        }
        // Who reports the roof is on. This used to be the building, always,
        // and upstream is explicit twice that it is not: sound.h:80 annotates
        // the event "only worker, work completed", and:
        // 190-198 is a three-way -- the building's own Ready if it has one,
        // else the worker's WorkCompleted, else the generic construction
        // noise.
        //
        // What a player lost by it is one line, and it is the line ChonkCraft
        // wrote a comment to protect. Of all 143 unit types exactly one
        // declares a work-complete of its own -- the human oil tanker, which
        // says "basic human voices research complete" because, per
        // human/units.legacy-declaration:549, "the oil tankers do not use the nasal 'work's
        // done' peasant sound for completing buildings". A tanker builds oil
        // platforms, so every platform in the game reported in the peasant's
        // voice. Everything else declares none and falls back to the race's
        // game sound either way, which is why asking the wrong unit was
        // invisible for 142 of the 143.
        Unit worker = site.worksite();
        if (site.type() != null && site.type().sounds().get("ready") != null) {
            world.announce(site, "ready");
        } else if (worker != null) {
            world.announce(worker, "work-complete");
        } else {
            world.announce(site, "building-construction");
        }

        // Put the builder back out beside its finished work.
        site.setWorksite(null);
        if (worker != null) {
            if (beginOilHaulFromCompletedPlatform(site, worker)) {
                world.recalculateSupply();
                return;
            }
            worker.setWorksite(null);
            // The paid bit stands in for state owned by this COrder_Build,
            // which remains the worker's current order while it is inside
            // the site. The roof completes that order, so its successor must
            // begin unpaid. level08h's same peon raises a second pig farm at
            // cycle 696; retaining the first farm's bit made the second free.
            worker.setBuildPaid(false);
            // ClearAction only marks the builder's COrder_Build Finished.
            // HandleUnitAction does not pop that order to Still until the
            // builder's next turn. This is observable by the AI, which runs
            // after UnitActions: on campaigns/orc/level09o the released peon
            // is still already working during player 0's cycle-667 think and
            // cannot be stolen for the next farm. Exposing a real Still here
            // let the AI reassign it immediately and made it step at 668.
            worker.setOrder(Unit.Order.BUILD);
            // The finished COrder_Build still owns its target type until
            // HandleUnitAction removes the order next cycle. AiCheckCosts
            // walks that order vector after UnitActions and therefore bills
            // the just-finished farm once more. level09o's cycle-667 bank is
            // the visible consequence: that promised 500 gold/250 wood makes
            // both the peasant and tanker asks wait where this implementation paid for
            // them immediately.
            worker.setPendingBuild(site.type());
            worker.setOrderFinished(true);
            // BNE walks the action table in reverse creation order, so a
            // foundation created after its builder is processed first. When
            // that foundation finishes mid-pass and drops the builder, the
            // builder's own turn still lies ahead in the same UnitActions
            // sweep and would pop the finished Build to Still before the
            // AI (or the next cycle) could see it -- level09o's cycle-667
            // reassignment and the one-cycle finished-order latch both
            // require the label to survive the rest of this pass. A single
            // wait holds the released builder through the remainder of the
            // cycle; the next cycle clears the wait and pops as usual.
            if (worker.waitCycles() < 1) {
                worker.setWaitCycles(1);
            }
            // The game puts the finished builder out west.
            int[] spot = world.dropOutOnSide(worker.type(), World.LOOKING_WEST, site,
                    worker.tileX(), worker.tileY());
            if (spot != null) {
                worker.setTile(spot[0], spot[1]);
                worker.setRemoved(false);
                world.markOccupancy(worker, true);
                world.unitCountSeen(worker);
                world.markSight(worker, true);
            }
        }
        world.recalculateSupply();
    }


    /**
     * Turns a completed platform's contained builder directly into its first
     * oil trip.
     *
     * <p>The authenticated 4,500-cycle Human 14 capture shows both AI tanker
     * builders remain removed in the new platform when construction ends and
     * immediately report resource action 23. Tanker 1406 is BUILD through
     * cycle 3068, HARVEST while removed from 3069 through 3219, and becomes
     * visible with its load on 3220. Dropping the builder west and waiting for
     * a later order loses that entire retail transition.</p>
     */
    private boolean beginOilHaulFromCompletedPlatform(Unit site, Unit worker) {
        if (site.type() == null
                || site.type().givesResource() != UnitType.Resource.OIL
                || site.resourcesHeld() <= 0
                || worker.type() == null) {
            return false;
        }
        var info = worker.type().gathering().get(UnitType.Resource.OIL);
        if (info == null) {
            return false;
        }

        worker.setWorksite(site);
        worker.setBuildPaid(false);
        worker.setPendingBuild(null);
        worker.setOrderFinished(false);
        worker.setOrder(Unit.Order.HARVEST);
        worker.setResourceUnit(site);
        worker.setResourceTile(site.tileX(), site.tileY());
        worker.setCarrying(UnitType.Resource.OIL);
        worker.setHeldResource(UnitType.Resource.OIL);
        worker.setCarried(0);
        worker.setReturningToDepot(false);
        worker.setResourceDepot(null);
        worker.setReturnDepotGoal(null);
        worker.clearPath();
        worker.setBattleNetOilStartedAdjacent(true);
        worker.setBattleNetOilAction(Unit.BattleNetOilAction.TO_RESOURCE);
        worker.setBattleNetOilActionTicks(0);
        worker.setWaitCycles(Math.max(0, info.waitAtResource()));
        return true;
    }


    void stepRepair(Unit unit) {
        Unit target = unit.target();
        if (target == null || !target.isAlive()
                || target.hitPoints() >= target.type().hitPoints()) {
            unit.setTarget(null);
            unit.setOrder(Unit.Order.STILL);
            return;
        }
        if (unit.distanceTo(target) > 1) {
            world.movement.walkTowards(unit, target.tileX(), target.tileY());
            return;
        }
        world.stepWorkAnimation(unit, AnimationSet.State.REPAIR);
        // Once a second, so the rate is the same on any machine.
        if (world.cycle % World.CYCLES_PER_SECOND != 0) {
            return;
        }
        Player owner = world.player(unit.player());
        if (owner != null && !target.type().repairCosts().isEmpty()
                && !owner.pay(target.type().repairCosts())) {
            unit.setOrder(Unit.Order.STILL);
            unit.setTarget(null);
            return;
        }
        int restored = Math.max(1, target.type().repairHp());
        target.setHitPoints(Math.min(target.type().hitPoints(),
                target.hitPoints() + restored));
    }


    /**
     * Sends a worker to mend something.
     *
     * <p>The target may be an ally's: {@code DoRightButton}'s repair branch
     * is {@code dest->Player == unit.Player || unit.IsAllied(*dest)}
     * This refused everything but the worker's
     * own player, so the right click on an allied building was issued by the
     * interface -- RightClickTableTest pins the command -- and dropped here,
     * and a campaign ally's burning hall could not be helped.
     */
    boolean orderRepair(Unit unit, Unit target) {
        if (unit == null || target == null || !unit.isAlive() || !target.isAlive()) {
            return false;
        }
        if (unit.type().repairRange() <= 0
                || (target.player() != unit.player()
                        && !world.isAllied(unit.player(), target.player()))) {
            return false;
        }
        if (target.hitPoints() >= target.type().hitPoints()) {
            return false;
        }
        unit.clearPath();
        unit.setTarget(target);
        unit.setOrder(Unit.Order.REPAIR);
        return true;
    }
}
