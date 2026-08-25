package net.chonkbase.chonkcraft.engine.pathfinder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.chonkbase.chonkcraft.engine.map.Direction;

/**
 * Retail Battle.net Edition's short, direction-buffered route generator.
 *
 * <p>BNE 2.02 does not use LegacyEngine's A*. Function {@code 0x44fbd0} first
 * draws a Bresenham line and stores at most twenty headings in the unit's
 * route buffer. When the line meets an obstacle, it traces both sides, runs
 * native {@code 0x450350}'s repeated two-heading simplifier with the original
 * {@code DAT_00490e88} turn table, and keeps the route that makes the better
 * total progress toward the destination. A measured short-prefix fallback is
 * retained for a wall trace that cannot rejoin the ray.</p>
 */
public final class BattleNetPathFinder {

    /** Number of route bytes in the retail unit record. */
    public static final int MAX_PATH = 20;

    /** Native {@code DAT_00490e88}, indexed by two consecutive headings. */
    private static final int[][] TURN_OPTIMIZATION = {
        {0x08, 0x09, 0x01, 0x02, 0x40, 0x06, 0x07, 0x09},
        {0x08, 0x08, 0x08, 0x82, 0x02, 0x40, 0x00, 0x80},
        {0x01, 0x09, 0x08, 0x09, 0x03, 0x04, 0x40, 0x00},
        {0x02, 0x82, 0x08, 0x08, 0x08, 0x84, 0x04, 0x40},
        {0x40, 0x02, 0x03, 0x09, 0x08, 0x09, 0x05, 0x06},
        {0x06, 0x40, 0x04, 0x84, 0x08, 0x08, 0x08, 0x86},
        {0x07, 0x00, 0x40, 0x04, 0x05, 0x09, 0x08, 0x09},
        {0x08, 0x80, 0x00, 0x40, 0x06, 0x86, 0x08, 0x08},
    };

    /**
     * One DAT_00490e88 shortcut rewrite for two consecutive headings.
     *
     * <p>Returns the replacement heading when the table marks a free-cell
     * shortcut ({@code 0x80} bit), else {@code -1}. Used when a residual-
     * settled wood step is blocked and native rewrites the route head via
     * {@code 0x450350} (XHuman 11 peon 1584: NE+SE → E onto 11,6).</p>
     */
    public static int twoHeadingShortcut(int first, int second) {
        if (first < 0 || first >= Direction.COUNT
                || second < 0 || second >= Direction.COUNT) {
            return -1;
        }
        int replacement = TURN_OPTIMIZATION[first][second];
        if ((replacement & 0x80) != 0) {
            return replacement & 0x7f;
        }
        return -1;
    }

    /**
     * Whether a mover may occupy one map position.
     *
     * <p>Wall-follow at native {@code 0x4500f0} treats out-of-bounds as fatal
     * to the whole face, while ordinary blocked terrain only rotates the
     * heading. The default keeps the older canEnter-only lambdas working for
     * unit tests that never leave a synthetic open plane.</p>
     */
    @FunctionalInterface
    public interface Passability {
        boolean canEnter(int x, int y);

        /**
         * Whether the terrain and fixed occupants admit this square when
         * mobile-unit occupancy is ignored.
         *
         * <p>Native's marked-target wall follower may finish on a target-ring
         * square occupied by another mover. It still refuses the intrinsically
         * blocked target body, trees, rocks and buildings. The default keeps
         * synthetic callers conservative unless they can distinguish those
         * two causes of a failed {@link #canEnter(int, int)} test.</p>
         */
        default boolean canEnterIgnoringMobileOccupancy(int x, int y) {
            return canEnter(x, y);
        }

        /**
         * Outside the map. Native fails the entire wall when the candidate
         * step is out of bounds ({@code cmp} against map size at
         * {@code 0x45015c}/{@code 0x45016a}); blocked terrain only rotates.
         */
        default boolean isOutOfBounds(int x, int y) {
            return false;
        }
    }

    /** Whether one map position carries native {@code 0x8000} goal marking. */
    @FunctionalInterface
    public interface GoalMarker {
        boolean contains(int x, int y);
    }

    private BattleNetPathFinder() {
    }

    /** Finds one native-sized route prefix to an exact BNE goal point. */
    public static PathFinder.Path find(int fromX, int fromY, int toX, int toY,
            Passability passability) {
        return find(fromX, fromY, toX, toY, 1, passability);
    }

    /**
     * Finds a route for a normal or doubled-delta mover.
     *
     * <p>Native units carrying the large-mover bit use the tables at
     * {@code 0x4945d0}: the same stored headings, but deltas of two tiles.
     * Their destination is rounded down to the corresponding even grid before
     * the line is drawn.</p>
     */
    public static PathFinder.Path find(int fromX, int fromY, int toX, int toY,
            int stride, Passability passability) {
        return find(fromX, fromY, toX, toY, stride, passability, null);
    }

    /**
     * Finds a route with an explicit native target marker.
     *
     * <p>{@code 0x4508f0} marks the passable ring around a target while
     * leaving the target's ordinary movement flags intact. The wall follower
     * stops when it enters that ring, or when the square beside it is marked.
     * A null marker retains the point-goal behavior used by existing callers.
     * </p>
     */
    public static PathFinder.Path find(int fromX, int fromY, int toX, int toY,
            int stride, Passability passability, GoalMarker goalMarker) {
        return find(fromX, fromY, toX, toY, stride,
                passability, passability, goalMarker);
    }

    /**
     * Finds a route with native's separate traversal and optimizer views.
     *
     * <p>{@code 0x4500f0} may ignore the occupancy bit of a moving friendly
     * unit while it follows a wall. Later, {@code 0x450350} tests a proposed
     * shortcut against the unmodified map flags. Consequently a friendly
     * mover can be crossed by the wall trace without also permitting the
     * optimizer to pull a diagonal through its occupied square.</p>
     */
    public static PathFinder.Path find(int fromX, int fromY, int toX, int toY,
            int stride, Passability passability,
            Passability optimizationPassability, GoalMarker goalMarker) {
        return find(fromX, fromY, toX, toY, stride, passability,
                optimizationPassability, goalMarker, false);
    }

    /**
     * Finds a route with control over BNE's empty marked-target result.
     *
     * @param preserveEmptyFailure true for live unit targets, whose failed
     *     route remains an active empty attack route; false for resource and
     *     point approaches with independently captured escape prefixes
     */
    public static PathFinder.Path find(int fromX, int fromY, int toX, int toY,
            int stride, Passability passability,
            Passability optimizationPassability, GoalMarker goalMarker,
            boolean preserveEmptyFailure) {
        return find(fromX, fromY, toX, toY, stride, passability,
                optimizationPassability, goalMarker, preserveEmptyFailure,
                false);
    }

    /** Finds a route with the terrain-resource blocked-goal convention. */
    public static PathFinder.Path find(int fromX, int fromY, int toX, int toY,
            int stride, Passability passability,
            Passability optimizationPassability, GoalMarker goalMarker,
            boolean preserveEmptyFailure, boolean preserveBlockedGoalPrefix) {
        return find(fromX, fromY, toX, toY, stride, passability,
                optimizationPassability, goalMarker, preserveEmptyFailure,
                preserveBlockedGoalPrefix, false);
    }

    /**
     * Finds a route with optional pure-major free-prefix preference for gold
     * resource approaches.
     *
     * @param preferPureMajorFreePrefix when true, a pure-cardinal free ray of
     *     two or more steps is kept over a wall-follow that opens with the
     *     same first step but ends no closer than the free tip (Orc 7 peon
     *     1582 WW vs wall-follow W,SW). Combat routes leave this false.
     */
    public static PathFinder.Path find(int fromX, int fromY, int toX, int toY,
            int stride, Passability passability,
            Passability optimizationPassability, GoalMarker goalMarker,
            boolean preserveEmptyFailure, boolean preserveBlockedGoalPrefix,
            boolean preferPureMajorFreePrefix) {
        return find(fromX, fromY, toX, toY, stride, passability,
                optimizationPassability, goalMarker, preserveEmptyFailure,
                preserveBlockedGoalPrefix, preferPureMajorFreePrefix, false);
    }

    /**
     * Finds a route with the authenticated large-target wall-tie convention.
     *
     * @param preferMarkedWallOnTie keep a marked target's wall-follow when a
     *     doubled free prefix opens with the same first-step gain
     */
    public static PathFinder.Path find(int fromX, int fromY, int toX, int toY,
            int stride, Passability passability,
            Passability optimizationPassability, GoalMarker goalMarker,
            boolean preserveEmptyFailure, boolean preserveBlockedGoalPrefix,
            boolean preferPureMajorFreePrefix,
            boolean preferMarkedWallOnTie) {
        return find(fromX, fromY, toX, toY, stride, passability,
                optimizationPassability, goalMarker, preserveEmptyFailure,
                preserveBlockedGoalPrefix, preferPureMajorFreePrefix,
                preferMarkedWallOnTie, false);
    }

    /**
     * Finds a route with the native shared wall buffer enabled when the caller
     * has authenticated carry-over from an earlier collision face.
     *
     * <p>Retail traces both rotations through one twenty-byte buffer. Most
     * cold searches are currently kept on the independently compensated path,
     * but saturated residual recovery has concrete buffer carry-over and must
     * let the first face's writes influence the second.</p>
     */
    public static PathFinder.Path find(int fromX, int fromY, int toX, int toY,
            int stride, Passability passability,
            Passability optimizationPassability, GoalMarker goalMarker,
            boolean preserveEmptyFailure, boolean preserveBlockedGoalPrefix,
            boolean preferPureMajorFreePrefix,
            boolean preferMarkedWallOnTie,
            boolean shareWallBufferBetweenFaces) {
        return find(fromX, fromY, toX, toY, stride, passability,
                optimizationPassability, goalMarker, preserveEmptyFailure,
                preserveBlockedGoalPrefix, preferPureMajorFreePrefix,
                preferMarkedWallOnTie, shareWallBufferBetweenFaces,
                shareWallBufferBetweenFaces, false);
    }

    /**
     * Finds a route with independent control over wall-face order and buffer
     * carry-over. Retail can enter the opposite rotation first without yet
     * retaining the failed face's writes; saturated recovery needs both.
     */
    public static PathFinder.Path find(int fromX, int fromY, int toX, int toY,
            int stride, Passability passability,
            Passability optimizationPassability, GoalMarker goalMarker,
            boolean preserveEmptyFailure, boolean preserveBlockedGoalPrefix,
            boolean preferPureMajorFreePrefix,
            boolean preferMarkedWallOnTie,
            boolean shareWallBufferBetweenFaces,
            boolean reverseWallFaceOrder,
            boolean retainFirstWallFace) {
        return find(fromX, fromY, toX, toY, stride, passability,
                optimizationPassability, goalMarker, preserveEmptyFailure,
                preserveBlockedGoalPrefix, preferPureMajorFreePrefix,
                preferMarkedWallOnTie, shareWallBufferBetweenFaces,
                reverseWallFaceOrder, retainFirstWallFace, false);
    }

    /**
     * Finds a route with an independently authenticated direct-ray view.
     *
     * @param hardDirectRay use the optimizer's unmodified occupancy view for
     *     the direct writer while retaining the softened wall traversal view
     */
    public static PathFinder.Path find(int fromX, int fromY, int toX, int toY,
            int stride, Passability passability,
            Passability optimizationPassability, GoalMarker goalMarker,
            boolean preserveEmptyFailure, boolean preserveBlockedGoalPrefix,
            boolean preferPureMajorFreePrefix,
            boolean preferMarkedWallOnTie,
            boolean shareWallBufferBetweenFaces,
            boolean reverseWallFaceOrder,
            boolean retainFirstWallFace,
            boolean hardDirectRay) {
        if (stride != 1 && stride != 2) {
            throw new IllegalArgumentException("BNE movement stride must be 1 or 2");
        }
        // 0x004509d0 asks 0x0041f5f0 for the goal afresh and gets it
        // unrounded, while the start's reference distances at 0x0044fcf6 are
        // taken against the rounded one. For a one-tile mover the rounding is
        // a no-op and the difference never shows; for a double-step mover it
        // decides whether a join counts as progress at all.
        int scoreToX = toX;
        int scoreToY = toY;
        if (stride == 2) {
            toX &= ~1;
            toY &= ~1;
        }
        if (fromX == toX && fromY == toY) {
            return new PathFinder.Path(PathFinder.Result.REACHED, new int[0]);
        }

        List<Integer> direct = new ArrayList<>(MAX_PATH);
        Line line = new Line(fromX, fromY, toX, toY);
        Passability directPassability = hardDirectRay
                ? optimizationPassability : passability;
        int x = fromX;
        int y = fromY;
        while (direct.size() < MAX_PATH && (x != toX || y != toY)) {
            int heading = line.next();
            int nx = x + Direction.deltaX(heading) * stride;
            int ny = y + Direction.deltaY(heading) * stride;
            if (!directPassability.canEnter(nx, ny)) {
                // A terrain-resource order aimed at its intrinsically
                // blocked square keeps the clear part of its ray. Human 13
                // captures 7,ff... from 80,7 toward the tree at 78,5: the
                // first north-west step is retained and the order asks again
                // once it reaches that adjacent square. Building and combat
                // target rectangles still use their wall follower below.
                //
                // Human 8 peasant 1507 also hits a blocked tree (85,83) after
                // free tip 3333433, but native stores wall-follow
                // 333222223544. That rewrite is applied after pathfind when
                // the free tip is already on the goal skirt and a wall face
                // rewrites the free ray (see World.battleNetForestWallPrefer).
                // Restoring an unconditional free prefix here keeps XHuman
                // 11/12 and XOrc 12 wood peons; taking wall on every equal-
                // gain rewrite REGd those maps at fixture 12-44.
                if (preserveBlockedGoalPrefix && nx == toX && ny == toY
                        && !direct.isEmpty()) {
                    return found(direct);
                }
                Set<Long> laterLine = new HashSet<>();
                laterLine.add(point(nx, ny));
                int lineX = nx;
                int lineY = ny;
                // Bounded like the ray above it, and for the same reason.
                // This walks the rest of the very same line, so it cannot
                // want more steps than the line can hold -- and without the
                // bound it does not always reach the goal at all. A
                // double-step mover standing on an odd tile steps in twos
                // from an odd coordinate while the goal was rounded down to
                // an even one at the top of this method, so the two parities
                // never meet and the walk runs until the heap is gone: a 2x2
                // destroyer on 9,26 ordered to attack-move at a 2x2 dragon on
                // 5,31 takes nine gigabytes in three seconds and kills the
                // process. It is the whole of AttackMoveTest never running,
                // and it would hang a real game the same way.
                int laterSteps = 0;
                while ((lineX != toX || lineY != toY)
                        && laterSteps < MAX_PATH) {
                    laterSteps++;
                    int laterHeading = line.next();
                    lineX += Direction.deltaX(laterHeading) * stride;
                    lineY += Direction.deltaY(laterHeading) * stride;
                    laterLine.add(point(lineX, lineY));
                }
                // Blocked exact goal with a free ray prefix: prefer that
                // prefix when wall-follow's first step is worse. XHuman 12
                // peon 1497 at 76,38 aims at mine face 74,40; the SW prefix
                // onto 75,39 is free but wall-follow invented pure south.
                // Western-mine pure-S rays still lose when wall-follow's
                // first step closes more Chebyshev distance (SE from 4,26).
                //
                // Equal first-step gain with the same first heading keeps a
                // multi-step free Bresenham prefix on double-step point routes:
                // wall-follow's 0x450350 rewrite of a long open-water ray onto a unit-
                // occupied goal rewrote SE,E,SE,E... into SE,SE,E,E... so
                // XOrc 8 destroyer 1426 stepped 62,102→64,104 while native
                // stored 03 02... and stepped to 64,102. Stride-1 land paths
                // keep the strict greater-gain rule (XHuman 12 peon/grunt
                // wall-follow ties must not prefer the ray). Marked target
                // skirts keep the wall route on that tie: XHuman 8 tanker 1538
                // returns to its refinery as W,NW,W instead of the blocked-goal
                // ray W,W,W. A one-step prefix is different: Human 13 daemon
                // 1556 keeps the wall's second NE stride around its occupied
                // point, route 01 01.
                if (nx == toX && ny == toY && !direct.isEmpty()) {
                    PathFinder.Path prefix = found(direct);
                    PathFinder.Path escaped = escapeObstacle(direct, x, y,
                            heading, toX, toY, stride, laterLine, passability,
                            optimizationPassability, goalMarker,
                            scoreToX, scoreToY,
                            shareWallBufferBetweenFaces,
                            reverseWallFaceOrder, retainFirstWallFace);
                    if (escaped.length() == 0) {
                        return prefix;
                    }
                    int startX = xFromPrefix(direct, x, stride);
                    int startY = yFromPrefix(direct, y, stride);
                    int startDist = chebyshev(startX, startY, toX, toY);
                    int prefixHeading = prefix.headings()[prefix.length() - 1];
                    int wallHeading = escaped.headings()[escaped.length() - 1];
                    int prefixGain = startDist - chebyshev(
                            startX + Direction.deltaX(prefixHeading) * stride,
                            startY + Direction.deltaY(prefixHeading) * stride,
                            toX, toY);
                    int wallGain = startDist - chebyshev(
                            startX + Direction.deltaX(wallHeading) * stride,
                            startY + Direction.deltaY(wallHeading) * stride,
                            toX, toY);
                    if (prefixGain > wallGain
                            || (!preferMarkedWallOnTie
                                    && stride == 2
                                    && prefix.length() > 1
                                    && prefixGain == wallGain
                                    && prefixHeading == wallHeading)) {
                        return prefix;
                    }
                    return escaped;
                }
                PathFinder.Path escaped = escapeObstacle(direct, x, y, heading,
                        toX, toY, stride, laterLine, passability,
                        optimizationPassability, goalMarker,
                        scoreToX, scoreToY,
                        shareWallBufferBetweenFaces,
                        reverseWallFaceOrder, retainFirstWallFace);
                // 0x4508f0 marks the target skirt and the target itself. When
                // the ray is stopped by a marked square that is not the goal
                // point, wall-follow can still invent a long detour whose first
                // step walks away from the goal. XHuman 12 grunt 1476's ray
                // reaches 23,43 against the tower wall and must keep the
                // north-east prefix; the south wall-follow (22,44→22,45) is
                // longer and non-improving. Neighbours 1463/1468 still need
                // wall-follow (or the two-heading simplifier on the ray) so
                // their first step is south-east. Prefer the optimised ray
                // when its first step closes more Chebyshev distance than
                // wall-follow's; on a tie keep wall-follow (1463 SE needs
                // the detour; equality must not steal it for pure-E).
                //
                // Grunt 1470 at 19,46 toward the 24,50 tower is the other
                // edge: the SE ray gains one Chebyshev tile on the first step
                // while the native north wall-follow first-steps NE (gain 0)
                // and then walks the long route that starts 01 02... Prefer
                // the ray only when its endpoint already sits on the marked
                // skirt (1476 ends on 23,43); a dead-end SE prefix that stops
                // at 22,49 outside the skirt must yield to wall-follow.
                if (goalMarker != null
                        && goalMarker.contains(nx, ny)
                        && (nx != toX || ny != toY)
                        && !direct.isEmpty()) {
                    PathFinder.Path ray = simplifiedPrefix(direct, x, y,
                            stride, optimizationPassability);
                    if (ray.length() > 0 && escaped.length() > 0) {
                        int startX = xFromPrefix(direct, x, stride);
                        int startY = yFromPrefix(direct, y, stride);
                        int startDist = chebyshev(startX, startY, toX, toY);
                        int rayHeading = ray.headings()[ray.length() - 1];
                        int wallHeading = escaped.headings()[
                                escaped.length() - 1];
                        int rayGain = startDist - chebyshev(
                                startX + Direction.deltaX(rayHeading) * stride,
                                startY + Direction.deltaY(rayHeading) * stride,
                                toX, toY);
                        int wallGain = startDist - chebyshev(
                                startX + Direction.deltaX(wallHeading)
                                        * stride,
                                startY + Direction.deltaY(wallHeading)
                                        * stride,
                                toX, toY);
                        // Combat/critter preserveEmptyFailure: only prefer a
                        // higher-gain ray that already ends on the marked
                        // skirt (1476). Resource rays
                        // (!preserveEmptyFailure) keep the gain rule alone
                        // (XHuman 12 peon wood SW onto 75,39).
                        if (rayGain > wallGain
                                && (!preserveEmptyFailure
                                        || rayEndsInGoalMarker(ray, startX,
                                                startY, stride, goalMarker))) {
                            return ray;
                        }
                    } else if (ray.length() > 0
                            && escaped.length() == 0) {
                        return ray;
                    }
                }
                // One free ray step toward an impassable goal, then a
                // wall-follow second step that does not improve Chebyshev:
                // keep only the free prefix. Orc 7 peasant 1567 at (41,9)
                // toward tree (32,2) must store NW onto (40,8); wall-follow
                // path=73 appended SE back to (41,9). Naval detours toward
                // free water goals are unaffected (!canEnter goal is false).
                // Improving wall steps (gold S,SE; tower skirts) keep escaped.
                if (direct.size() == 1 && escaped.length() == 2
                        && !passability.canEnter(toX, toY)) {
                    int freeFirst = direct.get(0);
                    int escFirst = escaped.headings()[escaped.length() - 1];
                    int escSecond = escaped.headings()[escaped.length() - 2];
                    if (escFirst == freeFirst) {
                        int tipDist = chebyshev(x, y, toX, toY);
                        int wallX = x + Direction.deltaX(escSecond) * stride;
                        int wallY = y + Direction.deltaY(escSecond) * stride;
                        if (chebyshev(wallX, wallY, toX, toY) >= tipDist) {
                            return found(direct);
                        }
                    }
                }
                // Gold resource approaches only (preferPureMajorFreePrefix).
                // Orc 7 peon 1582 free WW onto (42,3); wall-follow path=65 was
                // W,SW onto (42,4). x,y is the free tip; xFromPrefix rewinds
                // to the ray start for the wall walk.
                if (preferPureMajorFreePrefix
                        && pureMajorAxisPrefix(direct)
                        && escaped.length() >= 2
                        && !passability.canEnter(toX, toY)) {
                    int freeFirst = direct.get(0);
                    int escFirst = escaped.headings()[escaped.length() - 1];
                    if (escFirst == freeFirst) {
                        int tipDist = chebyshev(x, y, toX, toY);
                        int wallX = xFromPrefix(direct, x, stride);
                        int wallY = yFromPrefix(direct, y, stride);
                        for (int i = escaped.length() - 1; i >= 0; i--) {
                            wallX += Direction.deltaX(escaped.headings()[i])
                                    * stride;
                            wallY += Direction.deltaY(escaped.headings()[i])
                                    * stride;
                        }
                        if (chebyshev(wallX, wallY, toX, toY) >= tipDist) {
                            return found(direct);
                        }
                    }
                }
                // Native redraws the ray at 0x0044ffc0 when both faces fail
                // and stores however far it got, always, without consulting
                // the caller. Making this unconditional scores identically on
                // the 808 sealed routes -- this implementation's wall follow never
                // returns empty on any of them -- so it is left alone until
                // the follow itself is faithful and the case can arise.
                if (preserveBlockedGoalPrefix && !direct.isEmpty()
                        && escaped.length() == 0) {
                    return found(direct);
                }
                return escaped;
            }
            direct.add(heading);
            x = nx;
            y = ny;
        }
        return found(direct);
    }

    /** Traces both sides of a blocked ray and applies BNE's route optimizer. */
    private static PathFinder.Path escapeObstacle(List<Integer> prefix,
            int x, int y, int blockedHeading, int toX, int toY,
            int stride, Set<Long> laterLine, Passability passability,
            Passability optimizationPassability, GoalMarker goalMarker,
            int scoreToX, int scoreToY,
            boolean shareWallBufferBetweenFaces,
            boolean reverseWallFaceOrder,
            boolean retainFirstWallFace) {
        Map<Long, Integer> directRoute = routeFromPrefix(prefix, x, y, stride);
        Map<Long, Integer> sharedRoute = new HashMap<>(directRoute);
        List<Integer> failedSharedFace = null;
        Candidate best = null;
        // progressFrom and the optimizer both need the route's true starting
        // point. Reconstruct it from the prefix once rather than exposing more
        // state to callers.
        int startX = xFromPrefix(prefix, x, stride);
        int startY = yFromPrefix(prefix, y, stride);
        int[] turns = reverseWallFaceOrder
                ? new int[] {1, -1} : new int[] {-1, 1};
        for (int turn : turns) {
            // A deviation, and a measured one. Native keeps a single route
            // array at 0x4ad64c for the whole search: the second rotation runs
            // on whatever the first left off the ray line, because 0x0044ff33
            // only redraws the ray and only a rotation that failed is erased
            // first. Giving each rotation its own copy is therefore wrong, but
            // being faithful here costs four of the 808 sealed routes, 743
            // down to 739 -- so something else compensates for it today and
            // the copy stays until that is found. focused tests has the measurement.
            Map<Long, Integer> route = shareWallBufferBetweenFaces
                    ? sharedRoute : new HashMap<>(directRoute);
            if (shareWallBufferBetweenFaces) {
                // 0x44ff33 redraws the direct ray into the same buffer. It does
                // not clear successful off-ray writes left by the first face.
                route.putAll(directRoute);
            }
            int[] join = traceWall(route, x, y, blockedHeading, turn,
                    toX, toY, stride, laterLine, passability, goalMarker);
            if (join == null) {
                if (shareWallBufferBetweenFaces) {
                    List<Integer> partial = partialHeadings(route,
                            startX, startY, stride);
                    if (failedSharedFace == null && !partial.isEmpty()) {
                        failedSharedFace = partial;
                    }
                    if (System.getenv("CHONKCRAFT_TRACE_BNE_WALL") != null) {
                        System.err.printf("JBNEWALL from=%d,%d goal=%d,%d "
                                        + "turn=%d failed route=%s shared=1%n",
                                startX, startY, toX, toY, turn, partial);
                    }
                    // A failed face is the one case retail erases before trying
                    // the other rotation.
                    sharedRoute = new HashMap<>(directRoute);
                }
                continue;
            }
            int remaining = progressFrom(join[0], join[1],
                    startX, startY, toX, toY, scoreToX, scoreToY);
            int routeLength = optimize(route, startX, startY,
                    join[0], join[1], stride, optimizationPassability);
            if (routeLength <= 0) {
                continue;
            }
            List<Integer> steps = headings(route, startX, startY,
                    routeLength, stride);
            if (steps == null || steps.isEmpty()) {
                continue;
            }
            // 0x4509d0 returns zero for a join that made no progress and
            // 0x0044fe58 erases the route and moves to the other rotation,
            // with no rescue -- transcribed and verified instruction by
            // instruction. Dropping the face here regardless is nevertheless
            // wrong for XHuman 7's juggernaught 1573, which native walks two
            // tiles south on a join that ties on Chebyshev with a minor axis
            // of zero. Something upstream of the score is different for a
            // double-step mover, and until it is found this keeps the face on
            // the strength of its first step.
            if (remaining == 0) {
                int first = steps.get(0);
                int stepX = startX + Direction.deltaX(first) * stride;
                int stepY = startY + Direction.deltaY(first) * stride;
                if (chebyshev(stepX, stepY, toX, toY)
                        >= chebyshev(startX, startY, toX, toY)) {
                    continue;
                }
                remaining = 1;
            }
            List<Integer> headings = steps;
            Candidate candidate = new Candidate(headings,
                    routeLength + remaining);
            if (System.getenv("CHONKCRAFT_TRACE_BNE_WALL") != null) {
                System.err.printf("JBNEWALL from=%d,%d goal=%d,%d turn=%d "
                                + "join=%d,%d route=%s len=%d remaining=%d "
                                + "score=%d shared=%d%n",
                        startX, startY, toX, toY, turn,
                        join[0], join[1], headings, routeLength, remaining,
                        candidate.distance(),
                        shareWallBufferBetweenFaces ? 1 : 0);
            }
            if (best == null) {
                best = candidate;
            } else if (candidate.distance() < best.distance()) {
                // XOrc 11 destroyer 1558 (stride 2): east corridor scores
                // dist 16 and south scores 15 to the same join (8,22). The
                // one-shorter south face first-steps to 4,20; native steps
                // east to 6,18 (route 02 03). When the gap is a single point
                // on a double-step route and the longer side's first step
                // rides the goal's major axis while the shorter does not,
                // keep the major-axis side. Stride-1 land paths (XHuman 6
                // peon 1483) must keep the strict shorter-distance pick.
                int gap = best.distance() - candidate.distance();
                if (stride == 2 && gap <= 1
                        && firstStepOnMajorAxis(best.headings().get(0),
                                startX, startY, toX, toY)
                        && !firstStepOnMajorAxis(candidate.headings().get(0),
                                startX, startY, toX, toY)) {
                    // keep best
                } else {
                    best = candidate;
                }
            }
            if (best == candidate && candidate.distance() < 8) {
                break;
            }
            if (retainFirstWallFace && best != null) {
                // A route rebuilt after a fully paid cooperative refusal band
                // keeps the first successful rotation in the native buffer;
                // the ordinary shortest-face comparison belongs to a cold
                // search. XHuman 12 slot 1508 records the entire first face
                // NE,NE,E,SE... at fixture 72 even though the opposite face
                // joins in nine steps and scores ten versus nineteen.
                break;
            }
        }
        if (shareWallBufferBetweenFaces && failedSharedFace != null) {
            // Saturated recovery has already failed the direct face for two
            // action visits. Retail's shared writer leaves the opening byte of
            // the opposite failed wall in the unit buffer even when the other
            // face can produce a complete route. Preserve that authenticated
            // byte as the bounded recovery prefix; the following residual
            // boundary will ask for the continuation against then-current
            // formation occupancy.
            return found(List.of(failedSharedFace.get(0)));
        }
        if (best != null) {
            // XOrc 10 destroyer 1483 (stride 2) at (124,74) toward oil
            // (99,79): south land forces wall-follow, and the optimised north
            // face first-steps pure north (gain 0) while pure west is free
            // open water and closes two Chebyshev tiles. Native first-steps
            // west to (122,74). Prefer a free major-axis stride when it out-
            // gains the wall-follow's first step. Stride-1 land paths must
            // keep wall-follow's first step -- forcing major-axis stole Orc
            // 9/14 and XHuman 12 peasant/grunt diagonals at cycles 3-4.
            // Equal-gain major preference is intentionally not used: it
            // steals XOrc 11 battleship west detours and Orc 3 tanker
            // approaches even when the pure major corridor looks open.
            if (stride == 2) {
                int wallFirst = best.headings().get(0);
                int wallGain = chebyshev(startX, startY, toX, toY)
                        - chebyshev(
                                startX + Direction.deltaX(wallFirst) * stride,
                                startY + Direction.deltaY(wallFirst) * stride,
                                toX, toY);
                int majorHeading = majorAxisHeading(startX, startY, toX, toY);
                if (majorHeading >= 0) {
                    int majorX = startX
                            + Direction.deltaX(majorHeading) * stride;
                    int majorY = startY
                            + Direction.deltaY(majorHeading) * stride;
                    if (passability.canEnter(majorX, majorY)) {
                        int majorGain = chebyshev(startX, startY, toX, toY)
                                - chebyshev(majorX, majorY, toX, toY);
                        if (majorGain > wallGain) {
                            List<Integer> majorPath = new ArrayList<>(prefix);
                            majorPath.add(majorHeading);
                            return found(majorPath);
                        }
                    }
                }
            }
            // Point goals only: when a pure major-axis free ray of two or more
            // steps hits a mid-ray hard block, 0x450350 may keep the first
            // free heading but rewrite the rest of that pure run into a
            // wall-follow detour. XHuman 12 grunt 1358 captures east,east
            // toward (26,87) before the tower wall at column 13; the
            // optimised wall path became east,north,north-east so the second
            // step went to (11,89) while native continued east to (12,90).
            // Keep the free pure-major prefix only when the wall route opens
            // with that same first heading yet fails to preserve the whole
            // pure run -- combat/resource goals (goalMarker set) and pure
            // runs the wall route extends unchanged still take wall-follow.
            // A broader "any free prefix" keep regressed early campaign maps.
            return found(best.headings());
        }
        if (failedSharedFace != null) {
            return found(failedSharedFace);
        }
        // A unit-target route owns 0x4508f0's marked target skirt. When both
        // wall traces fail, native 0x44fbd0 preserves only the direct prefix;
        // it does not invent a locally passable side step. XHuman 4 captures
        // this as an all-0xff route for two axethrowers boxed behind their
        // formation. The older escape below remains for point goals, where
        // it represents independently captured mine-approach prefixes --
        // except a pure major-axis free run of two or more steps before a
        // hard mid-ray block must stay that prefix when both wall faces
        // fail. XHuman 12 grunt 1358's east,east toward (26,87) is the
        // witness: both wall faces fail and fallbackEscape invented
        // east,north,north-east (path 201), while native stores route 02 02
        // and continues east to (12,90). Keeping pure-major prefixes when
        // wall-follow succeeds rewrote early peon paths on the same map
        // (peon 1364 @4).
        // Native does this for every route, not for some of them. At
        // 0x0044ffc0 it redraws the ray and copies [0x4be21c] headings -- the
        // prefix, exactly as drawn -- and there is no side step anywhere in
        // 0x0044fbd0 to invent one with. The conditions above grew because
        // fallbackEscape's invention had to be kept away from the cases where
        // it was visibly wrong; with the prefix returned unconditionally there
        // is nothing to keep away from. Measured on the 808 sealed routes with
        // the occupancy the game uses, twenty-two of them are this.
        return prefix.isEmpty()
                ? new PathFinder.Path(PathFinder.Result.FOUND, new int[0])
                : found(prefix);
    }

    /** Whether {@code prefix} is two or more identical major-axis headings. */
    private static boolean pureMajorAxisPrefix(List<Integer> prefix) {
        if (prefix.size() < 2) {
            return false;
        }
        int major = prefix.get(0);
        for (int heading : prefix) {
            if (heading != major) {
                return false;
            }
        }
        return true;
    }

    /**
     * Pure cardinal along the goal's major axis, or {@code -1} when the
     * goal shares the start row and column on both axes equally as zero.
     */
    private static int majorAxisHeading(int fromX, int fromY, int toX,
            int toY) {
        int dx = toX - fromX;
        int dy = toY - fromY;
        if (dx == 0 && dy == 0) {
            return -1;
        }
        if (Math.abs(dx) >= Math.abs(dy)) {
            if (dx == 0) {
                return -1;
            }
            return Direction.fromDelta(Integer.signum(dx), 0);
        }
        return Direction.fromDelta(0, Integer.signum(dy));
    }


    private static Map<Long, Integer> routeFromPrefix(List<Integer> prefix,
            int anchorX, int anchorY, int stride) {
        int startX = xFromPrefix(prefix, anchorX, stride);
        int startY = yFromPrefix(prefix, anchorY, stride);
        Map<Long, Integer> route = new HashMap<>();
        int x = startX;
        int y = startY;
        for (int heading : prefix) {
            route.put(point(x, y), heading);
            x += Direction.deltaX(heading) * stride;
            y += Direction.deltaY(heading) * stride;
        }
        return route;
    }

    private static int xFromPrefix(List<Integer> prefix, int anchor, int stride) {
        int result = anchor;
        for (int heading : prefix) {
            result -= Direction.deltaX(heading) * stride;
        }
        return result;
    }

    private static int yFromPrefix(List<Integer> prefix, int anchor, int stride) {
        int result = anchor;
        for (int heading : prefix) {
            result -= Direction.deltaY(heading) * stride;
        }
        return result;
    }

    /** Exact wall follower at native {@code 0x4500f0}. */
    private static int[] traceWall(Map<Long, Integer> route,
            int anchorX, int anchorY, int blockedHeading, int turn,
            int toX, int toY, int stride, Set<Long> laterLine,
            Passability passability, GoalMarker goalMarker) {
        String tracedWallStart = System.getenv(
                "CHONKCRAFT_TRACE_BNE_WALL_STEPS");
        boolean traceSteps = tracedWallStart != null
                && (tracedWallStart.trim().equals(anchorX + "," + anchorY)
                        || tracedWallStart.trim().equals(
                                "goal:" + toX + "," + toY));
        int x = anchorX;
        int y = anchorY;
        int heading = blockedHeading;
        // Native increments after each free step and fails at 50
        // (cmp ecx, 0x32; jb). At most fifty successful steps.
        for (int steps = 0; steps < 50; steps++) {
            int first = Math.floorMod(heading + turn, Direction.COUNT);
            int chosen = first;
            int nx;
            int ny;
            while (true) {
                nx = x + Direction.deltaX(chosen) * stride;
                ny = y + Direction.deltaY(chosen) * stride;
                // OOB fails the whole face without rotating (0x45015c/0x45016a).
                if (passability.isOutOfBounds(nx, ny)) {
                    if (traceSteps) {
                        System.err.printf("JBNEWALLSTEP from=%d,%d turn=%d "
                                        + "step=%d at=%d,%d try=%d next=%d,%d "
                                        + "result=oob%n",
                                anchorX, anchorY, turn, steps, x, y, chosen,
                                nx, ny);
                    }
                    return null;
                }
                boolean enterable = passability.canEnter(nx, ny);
                boolean markedSkirtIgnoringMover = !enterable
                        && goalMarker != null
                        && goalMarker.contains(nx, ny)
                        && passability.canEnterIgnoringMobileOccupancy(nx, ny);
                if (enterable || markedSkirtIgnoringMover) {
                    break;
                }
                if (traceSteps) {
                    System.err.printf("JBNEWALLSTEP from=%d,%d turn=%d "
                                    + "step=%d at=%d,%d try=%d next=%d,%d "
                                    + "result=blocked%n",
                            anchorX, anchorY, turn, steps, x, y, chosen,
                            nx, ny);
                }
                // Blocked terrain only: rotate until the first heading returns.
                chosen = Math.floorMod(chosen + turn, Direction.COUNT);
                if (chosen == first) {
                    if (traceSteps) {
                        System.err.printf("JBNEWALLSTEP from=%d,%d turn=%d "
                                        + "step=%d at=%d,%d result=boxed%n",
                                anchorX, anchorY, turn, steps, x, y);
                    }
                    return null;
                }
            }
            // Native fails the face on a free square carrying map bit 0x2000
            // (0x00450203), and this position test is that bit exactly rather
            // than a stand-in for it. 0x00450868 in the ray is the only
            // instruction in the whole of .text that sets 0x2000 on the flags
            // at 0x4ad610, and it sets it on one square: the anchor the ray
            // backs up to at 0x00450810. The search draws the ray three more
            // times, but none of the eight movement masks at 0x496ca0 -- 09ce,
            // 0200, 0903, 0901, 0100, 0200, 0100, 0100 -- contains 0x2000 or
            // 0x8000, so a mark cannot change where a later ray stops, and the
            // wall follower writes no flags of its own. Every draw therefore
            // stops on the same square and re-marks the same anchor, so one
            // square carries the bit and it is this one. Erasing the route
            // chain below does not replace this: taking the test out costs
            // Human 5 forty-nine cycles of parity, 57 down to 8.
            if (nx == anchorX && ny == anchorY) {
                if (traceSteps) {
                    System.err.printf("JBNEWALLSTEP from=%d,%d turn=%d "
                                    + "step=%d at=%d,%d choose=%d next=%d,%d "
                                    + "result=anchor%n",
                            anchorX, anchorY, turn, steps, x, y, chosen,
                            nx, ny);
                }
                return null;
            }
            // Native 0x0045020f erases whatever route this square already
            // carried, chain and all, before the new heading goes down. A
            // follower that walks back onto its own path used to keep the
            // loop: XHuman 12 grunt 1494 at 27,40 went round thirty-three
            // squares of open ground where native lays eleven.
            int staleX = x;
            int staleY = y;
            Integer stale;
            while ((stale = route.remove(point(staleX, staleY))) != null) {
                staleX += Direction.deltaX(stale) * stride;
                staleY += Direction.deltaY(stale) * stride;
            }
            route.put(point(x, y), chosen);
            boolean landingMarked = laterLine.contains(point(nx, ny))
                    || (goalMarker != null
                            ? goalMarker.contains(nx, ny)
                            : nearGoal(nx, ny, toX, toY, stride));
            if (traceSteps) {
                System.err.printf("JBNEWALLSTEP from=%d,%d turn=%d "
                                + "step=%d at=%d,%d choose=%d next=%d,%d "
                                + "marked=%d%n",
                        anchorX, anchorY, turn, steps, x, y, chosen,
                        nx, ny, landingMarked ? 1 : 0);
            }
            if (landingMarked) {
                return new int[] {nx, ny};
            }
            int beside = Math.floorMod(chosen + turn, Direction.COUNT);
            int besideX = x + Direction.deltaX(beside) * stride;
            int besideY = y + Direction.deltaY(beside) * stride;
            boolean besideMarked = laterLine.contains(point(besideX, besideY))
                    || (goalMarker != null
                            ? goalMarker.contains(besideX, besideY)
                            : nearGoal(besideX, besideY, toX, toY, stride));
            if (besideMarked) {
                if (traceSteps) {
                    System.err.printf("JBNEWALLSTEP from=%d,%d turn=%d "
                                    + "step=%d at=%d,%d result=beside-marked%n",
                            anchorX, anchorY, turn, steps, x, y);
                }
                return new int[] {x, y};
            }
            heading = Math.floorMod(chosen - 3 * turn, Direction.COUNT);
            x = nx;
            y = ny;
        }
        if (traceSteps) {
            System.err.printf("JBNEWALLSTEP from=%d,%d turn=%d result=limit%n",
                    anchorX, anchorY, turn);
        }
        return null;
    }

    /** Native {@code 0x4508f0}'s one-square marker around a point goal. */
    private static boolean nearGoal(int x, int y, int goalX, int goalY,
            int stride) {
        return Math.abs(x - goalX) <= stride
                && Math.abs(y - goalY) <= stride;
    }

    /** Native {@code 0x4509d0}: remaining direct-line progress at a join. */
    private static int progressFrom(int x, int y, int startX, int startY,
            int goalX, int goalY, int scoreGoalX, int scoreGoalY) {
        if (x == startX && y == startY) {
            return 0;
        }
        // The join is measured against the goal as 0x0041f5f0 reports it; the
        // start against the rounded one 0x0044fcf6 stored.
        int dx = Math.abs(scoreGoalX - x);
        int dy = Math.abs(scoreGoalY - y);
        int originalX = Math.abs(goalX - startX);
        int originalY = Math.abs(goalY - startY);
        int distance = Math.max(dx, dy);
        int original = Math.max(originalX, originalY);
        if (distance < original) {
            return distance;
        }
        if (distance > original) {
            return 0;
        }
        int minor = Math.min(dx, dy);
        int originalMinor = Math.min(originalX, originalY);
        return minor < originalMinor ? minor : 0;
    }

    /** Exact repeated two-heading simplifier at native {@code 0x450350}. */
    private static int optimize(Map<Long, Integer> route,
            int startX, int startY, int joinX, int joinY, int stride,
            Passability passability) {
        while (true) {
            boolean changed = false;
            int length = 0;
            int x = startX;
            int y = startY;
            int guard = 0;
            while (x != joinX || y != joinY) {
                if (++guard > 256) {
                    return -1;
                }
                Integer first = route.get(point(x, y));
                if (first == null) {
                    return -1;
                }
                length++;
                int nextX = x + Direction.deltaX(first) * stride;
                int nextY = y + Direction.deltaY(first) * stride;
                if (nextX == joinX && nextY == joinY) {
                    x = nextX;
                    y = nextY;
                    break;
                }
                Integer second = route.get(point(nextX, nextY));
                if (second == null) {
                    return -1;
                }
                int replacement = TURN_OPTIMIZATION[first][second];
                if ((replacement & 0x40) != 0) {
                    return -1;
                }
                int landingX = nextX;
                int landingY = nextY;
                boolean rewritten = false;
                if ((replacement & 0x80) != 0) {
                    replacement &= 0x7f;
                    int shortcutX = x + Direction.deltaX(replacement) * stride;
                    int shortcutY = y + Direction.deltaY(replacement) * stride;
                    if (passability.canEnter(shortcutX, shortcutY)
                            && !route.containsKey(point(shortcutX, shortcutY))) {
                        route.put(point(x, y), replacement);
                        route.put(point(shortcutX, shortcutY), replacement);
                        route.remove(point(nextX, nextY));
                        landingX = shortcutX;
                        landingY = shortcutY;
                        rewritten = true;
                    }
                } else if (replacement == 9) {
                    int shortcutX = x + Direction.deltaX(second) * stride;
                    int shortcutY = y + Direction.deltaY(second) * stride;
                    if (passability.canEnter(shortcutX, shortcutY)
                            && !route.containsKey(point(shortcutX, shortcutY))) {
                        route.put(point(x, y), second);
                        route.put(point(shortcutX, shortcutY), first);
                        route.remove(point(nextX, nextY));
                        landingX = shortcutX;
                        landingY = shortcutY;
                        rewritten = true;
                    }
                } else if (replacement < Direction.COUNT) {
                    route.put(point(x, y), replacement);
                    route.remove(point(nextX, nextY));
                    landingX = nextX + Direction.deltaX(second) * stride;
                    landingY = nextY + Direction.deltaY(second) * stride;
                    rewritten = true;
                }
                changed |= rewritten;
                x = landingX;
                y = landingY;
            }
            if (!changed) {
                return length;
            }
        }
    }

    private static List<Integer> headings(Map<Long, Integer> route,
            int startX, int startY, int length, int stride) {
        List<Integer> result = new ArrayList<>(Math.min(length, MAX_PATH));
        int x = startX;
        int y = startY;
        for (int i = 0; i < length && i < MAX_PATH; i++) {
            Integer heading = route.get(point(x, y));
            if (heading == null) {
                return null;
            }
            result.add(heading);
            x += Direction.deltaX(heading) * stride;
            y += Direction.deltaY(heading) * stride;
        }
        return result;
    }

    /** Follows whatever bounded prefix remains in the shared native buffer. */
    private static List<Integer> partialHeadings(Map<Long, Integer> route,
            int startX, int startY, int stride) {
        List<Integer> result = new ArrayList<>(MAX_PATH);
        Set<Long> seen = new HashSet<>();
        int x = startX;
        int y = startY;
        while (result.size() < MAX_PATH && seen.add(point(x, y))) {
            Integer heading = route.get(point(x, y));
            if (heading == null) {
                break;
            }
            result.add(heading);
            x += Direction.deltaX(heading) * stride;
            y += Direction.deltaY(heading) * stride;
        }
        return result;
    }

    private static long point(int x, int y) {
        return ((long) x << 32) ^ (y & 0xffff_ffffL);
    }

    /** Converts BNE's forward route buffer into {@link PathFinder}'s stack. */
    private static PathFinder.Path found(List<Integer> forward) {
        if (forward.isEmpty()) {
            return new PathFinder.Path(
                    PathFinder.Result.UNREACHABLE, new int[0]);
        }
        int[] reversed = new int[forward.size()];
        for (int i = 0; i < forward.size(); i++) {
            reversed[forward.size() - 1 - i] = forward.get(i);
        }
        return new PathFinder.Path(PathFinder.Result.FOUND, reversed);
    }

    /**
     * Applies {@code 0x450350} to a clear ray prefix and returns the stack
     * form. Falls back to the raw prefix when the simplifier rejects it.
     */
    private static PathFinder.Path simplifiedPrefix(List<Integer> prefix,
            int endX, int endY, int stride, Passability passability) {
        Map<Long, Integer> route = routeFromPrefix(prefix, endX, endY, stride);
        int startX = xFromPrefix(prefix, endX, stride);
        int startY = yFromPrefix(prefix, endY, stride);
        int routeLength = optimize(route, startX, startY, endX, endY,
                stride, passability);
        if (routeLength > 0) {
            List<Integer> simplified = headings(route, startX, startY,
                    routeLength, stride);
            if (simplified != null && !simplified.isEmpty()) {
                return found(simplified);
            }
        }
        return found(prefix);
    }

    private static int chebyshev(int x1, int y1, int x2, int y2) {
        return Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
    }

    /**
     * Whether walking {@code ray} from {@code start} lands on a marked goal
     * skirt cell. {@link #found} stores headings as a stack (last index is the
     * first step), matching {@link Unit#nextPathHeading}.
     */
    private static boolean rayEndsInGoalMarker(PathFinder.Path ray,
            int startX, int startY, int stride, GoalMarker goalMarker) {
        int x = startX;
        int y = startY;
        for (int i = ray.length() - 1; i >= 0; i--) {
            int heading = ray.headings()[i];
            x += Direction.deltaX(heading) * stride;
            y += Direction.deltaY(heading) * stride;
        }
        return goalMarker.contains(x, y);
    }

    /**
     * Whether {@code wall} keeps every free-ray heading in order and only
     * appends after the tip.
     *
     * <p>Headings are stack-stored (last element first). Human 13 wall
     * {@code [6,7]} extends free {@code [7]}. Human 8 wall
     * {@code 333222223544} rewrites free {@code 3333433} at the fourth
     * step.</p>
     */
    public static boolean wallExtendsFreePrefix(PathFinder.Path free,
            PathFinder.Path wall) {
        if (wall.length() < free.length()) {
            return false;
        }
        int freeLen = free.length();
        int wallLen = wall.length();
        for (int i = 0; i < freeLen; i++) {
            // First free step is free[freeLen-1]; first wall step is
            // wall[wallLen-1]. Compare the free-length prefix of the walk.
            int freeHeading = free.headings()[freeLen - 1 - i];
            int wallHeading = wall.headings()[wallLen - 1 - i];
            if (freeHeading != wallHeading) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code heading} advances only along the goal's major axis
     * (pure E/W when {@code |dx| >= |dy|}, pure N/S otherwise).
     */
    private static boolean firstStepOnMajorAxis(int heading, int fromX,
            int fromY, int toX, int toY) {
        int dx = toX - fromX;
        int dy = toY - fromY;
        boolean xMajor = Math.abs(dx) >= Math.abs(dy);
        int stepX = Direction.deltaX(heading);
        int stepY = Direction.deltaY(heading);
        if (xMajor) {
            return stepX == Integer.signum(dx) && stepY == 0;
        }
        return stepY == Integer.signum(dy) && stepX == 0;
    }

    private record Candidate(List<Integer> headings, int distance) {
    }

    /**
     * The first square on the BNE line that the mover cannot enter.
     *
     * <p>A player move clicked into forest or water is stored as that first
     * blocked cell, not the clicked one. The Orc 1 commanded peon at 25,18
     * told to walk to 30,18 keeps order point 28,18 -- the first tree on the
     * east ray -- and then approaches that tree.
     */
    public static int[] firstBlockedToward(int fromX, int fromY, int toX, int toY,
            Passability passability) {
        if (fromX == toX && fromY == toY) {
            return new int[] {toX, toY};
        }
        Line line = new Line(fromX, fromY, toX, toY);
        int x = fromX;
        int y = fromY;
        while (x != toX || y != toY) {
            int heading = line.next();
            x += Direction.deltaX(heading);
            y += Direction.deltaY(heading);
            if (!passability.canEnter(x, y)) {
                return new int[] {x, y};
            }
        }
        return new int[] {toX, toY};
    }

    /** Exact transcription of {@code 0x429f10}/{@code 0x429fa0}. */
    private static final class Line {
        private final int major;
        private final int minor;
        private final int majorSign;
        private final int minorSign;
        private final boolean xMajor;
        private int error;

        Line(int fromX, int fromY, int toX, int toY) {
            int dx = toX - fromX;
            int dy = toY - fromY;
            int absoluteX = Math.abs(dx);
            int absoluteY = Math.abs(dy);
            xMajor = absoluteX >= absoluteY;
            major = xMajor ? absoluteX : absoluteY;
            minor = xMajor ? absoluteY : absoluteX;
            majorSign = xMajor ? Integer.signum(dx) : Integer.signum(dy);
            minorSign = xMajor ? Integer.signum(dy) : Integer.signum(dx);
            error = major >> 1;
            if (error == 0) {
                error = 1;
            }
        }

        int next() {
            int minorStep = 0;
            error -= minor;
            if (error < 1) {
                minorStep = minorSign;
                error += major;
            }
            int dx = xMajor ? majorSign : minorStep;
            int dy = xMajor ? minorStep : majorSign;
            return Direction.fromDelta(dx, dy);
        }
    }
}
