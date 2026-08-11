package net.chonkbase.chonkcraft.engine.pathfinder;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.PriorityQueue;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.MapField;
import net.chonkbase.chonkcraft.engine.map.TileFlag;

/**
 * A* over the tile grid.
 *
 * <p>Implements the search, keeping the
 * pieces that change behaviour: eight-way movement, the same heading
 * numbering, a heuristic that never overestimates, and the crossing costs
 * that make a unit prefer open ground to squeezing past another unit rather
 * than treating an occupied square as a wall.
 *
 * <p>Paths come back as a sequence of headings in reverse order, which is how
 * the move action consumes them: it pops the last element each step.
 */
public final class PathFinder {

    /**
     * What a moving unit in the way costs to path through.
     *
     * <p>Twenty, and the number is the data's, not the engine's:
     * The game declares a default of five, and
     * {@code scripts/legacyEngine.legacy-declaration:307} replaces it --
     * {@code AStar("fixed-unit-cost", 1000, "moving-unit-cost", 20,
     * "know-unseen-terrain", "unseen-terrain-cost", 2)} -- so every shipped
     * game charges twenty against a nine-a-square ground. Bisected the hard
     * way: bringing this to the engine default flipped demo03's footman off
     * upstream's route on the map's second cycle.
     */
    public static final int MOVING_UNIT_CROSSING_COST = 20;

    /**
     * What crossing a square held by an enemy that can be attacked costs.
     *
     * <p>Twice the moving cost, as upstream charges
     * The game a route through an enemy is a route through
     * a fight, which for an aggressive unit is sometimes exactly what was
     * wanted. Forty follows from the data's twenty above.
     */
    public static final int ENEMY_UNIT_CROSSING_COST = 40;

    /**
     * What is standing on a square, as the searching unit sees it.
     *
     * <p>The tile flags alone cannot answer this. They say a unit is there;
     * they do not say whether it is walking, whether it is an enemy, or
     * whether it is the very unit doing the searching -- and all three change
     * the answer. Without them a standing unit cost a twentieth of a step, so
     * routes were planned straight through crowds, and the walker then stopped
     * dead at the first body and re-planned the identical route.
     */
    public interface Occupancy {
        /** Nothing in the way, or the searcher's own square. */
        int CLEAR = 0;
        /** Somebody walking, who will probably have moved on. */
        int MOVING = 1;
        /** Somebody standing still: a wall, for planning purposes. */
        int STATIONARY = 2;
        /** An enemy standing still, which may be worth going through. */
        int STATIONARY_ENEMY = 3;

        int at(int x, int y);
    }

    /** Used when a caller has no unit context, e.g. a bare reachability test. */
    private static final Occupancy NOTHING_KNOWN = (x, y) -> Occupancy.CLEAR;

    /**
     * Everything about the unit the search is for.
     *
     * <p>Upstream passes the unit itself and reads what it needs off it. This
     * port cannot -- the pathfinder does not know about units -- so it carries
     * the same four answers instead.
     *
     * <p>{@code blocking} is the important one, and it is what the search used
     * to get wrong. It used to decide for itself that a building stops you and
     * another body slows you down, which is true of a footman and the reverse
     * of a gryphon: a flyer crosses buildings and ground troops without
     * noticing and is stopped only by another flyer. The mover has always
     * asked this question properly, through {@code Unit.blockingFlags}, so the
     * two disagreed about every air unit on the map -- the planner sent them
     * the long way round a keep and straight through each other.
     *
     * @param terrain  ground the unit can be on, any one flag of several
     * @param blocking occupancy that stops it, its {@code blockingFlags}
     * @param tileWidth  footprint width
     * @param tileHeight footprint height
     * @param occupancy  what it makes of who is standing where
     */
    public record Mover(long terrain, long blocking, int tileWidth, int tileHeight,
            Occupancy occupancy) {

        public Mover {
            tileWidth = Math.max(1, tileWidth);
            tileHeight = Math.max(1, tileHeight);
        }

        /**
         * A ground unit's default view, for reachability tests with no unit
         * to hand.
         */
        public static Mover of(long terrain, int tileWidth, int tileHeight) {
            return new Mover(terrain,
                    TileFlag.BUILDING | TileFlag.LAND_UNIT | TileFlag.SEA_UNIT,
                    tileWidth, tileHeight, NOTHING_KNOWN);
        }

        /**
         * Whether terrain that stops a walker does not stop this one.
         *
         * <p>A mask naming both land and water belongs to something in the
         * air, which forest and mountain do not reach.
         */
        boolean flying() {
            return (terrain & TileFlag.WATER_ALLOWED) != 0
                    && (terrain & TileFlag.LAND_ALLOWED) != 0;
        }
    }

    /**
     * What one step costs before terrain, whichever of the eight ways it goes.
     *
     * <p>{@code AStarFindPath} charges {@code new_cost++} for a square and
     * nothing for the direction: a
     * diagonal costs a footman exactly what a sideways step costs it. This
     * port charged 14 against 10, the usual root-two approximation, and of two
     * routes the same length that difference alone decided which came back --
     * a unit sent at something below and to its right stepped sideways here
     * and cut the corner upstream.
     */
    private static final int STEP_COST = 1;

    /**
     * How hard the estimate pulls towards the goal.
     *
     * <p>{@code AStarCosts} is {@code (|dx| + |dy|) << 3}
     * and upstream says why in its own
     * comment: the base cost underestimates because terrain and units add to
     * it, "but we want to be pretty greedy anyway, so we multiply by a
     * constant factor". It is deliberately not admissible, so the route is not
     * guaranteed shortest -- which is the point, because an admissible octile
     * estimate answered with a different one of the equally short routes.
     */
    private static final int GREED = 8;

    /** How the result should be read. */
    public enum Result {
        /** A path was found. */
        FOUND,
        /** The unit is already at the goal. */
        REACHED,
        /** No route exists. */
        UNREACHABLE
    }

    /**
     * A search result.
     *
     * @param result   how it went
     * @param headings the route, as headings in reverse order: the next step
     *                 is the last element
     */
    public record Path(Result result, int[] headings) {

        /** How many steps remain. */
        public int length() {
            return headings.length;
        }

        static Path of(Result result) {
            return new Path(result, new int[0]);
        }
    }

    /**
     * How many squares every search on this JVM has expanded, ever.
     *
     * <p>Observability, not state: nothing in the game reads it and the
     * simulation does not branch on it. It is here because the cost of route
     * finding is what went wrong on {@code levelx12h}, and wall time is a bad
     * way to watch it -- a timing test measures the machine and the state of
     * the JIT as much as the engine, and the first one written against this
     * very bug passed in a cold JVM and failed in a warm one. Squares expanded
     * is the work itself: it came out identical across runs on the three
     * missions it is checked against, and it is the same number on any
     * machine.
     *
     * <p>Not synchronised, because the simulation is single-threaded and a
     * diagnostic counter is not worth a memory barrier in the innermost loop
     * of the pathfinder.
     */
    public static long nodesExpanded;

    /**
     * How many full searches every search on this JVM has run, ever.
     *
     * <p>Observability, as {@link #nodesExpanded} is, and for one particular
     * reason: a full search is what empties the cost memo
     * ({@code AStarCleanUp} is {@code CostMoveToCacheCleanUp}), and that memo
     * decides every one-square step in the game. "How many searches" is
     * therefore a question about behaviour and not only about cost -- a
     * search this implementation does not run is an answer some unit elsewhere on the
     * map keeps for longer than upstream would.
     */
    public static long searchesRun;

    private final GameMap map;

    /** Cap on discovered or improved nodes, so a hopeless search cannot stall a tick. */
    private final int maxNodes;

    public PathFinder(GameMap map) {
        // {@code AStarMaxSearchIterations = 1024 * 5},
        // counted per node taken off the open set, as {@link #find}'s
        // {@code expanded} counts them. The size is behaviour, not tuning:
        // on campaigns/human-exp/levelx03h the walls-mode region reachable
        // from the south-west pocket runs to a little over four thousand
        // tiles, so a 4,096-node budget died mid-region and its best-effort
        // answer read as "reachable" -- the whole of why this implementation shipped
        // a peon eighty squares to a depot upstream's completed search
        // rightly calls unreachable.
        this(map, 5120);
    }

    public PathFinder(GameMap map, int maxNodes) {
        this.map = map;
        this.maxNodes = maxNodes;
    }

    /**
     * Finds a route.
     *
     * @param fromX      start column
     * @param fromY      start row
     * @param toX        goal column
     * @param toY        goal row
     * @param mask       terrain the unit needs, {@link TileFlag#LAND_ALLOWED}
     *                   or {@link TileFlag#WATER_ALLOWED}
     * @param tileWidth  the unit's footprint width
     * @param tileHeight the unit's footprint height
     */
    public Path find(int fromX, int fromY, int toX, int toY, long mask,
            int tileWidth, int tileHeight) {
        return find(fromX, fromY, toX, toY, Mover.of(mask, tileWidth, tileHeight));
    }

    /**
     * Finds a route for a particular unit.
     *
     * @param mover what the searching unit is and what stops it
     */
    public Path find(int fromX, int fromY, int toX, int toY, Mover mover) {
        return find(fromX, fromY, new Goal(toX, toY, 1, 1, 0, 0), mover);
    }

    /**
     * What a route is aiming at.
     *
     * <p>Implements {@code PathFinderInput}'s goal: not a square but a
     * rectangle -- the target's own footprint -- together with the range the
     * mover wants to end up at. {@code COrder_Attack::UpdatePathFinderData}
     * fills exactly these in, with {@code SetGoal(goal->tilePos, tileSize)},
     * {@code SetMaxRange(this->Range)} and {@code SetMinRange(this->MinRange)}.
     *
     * <p>Chasing anything needs this and cannot be faked with a point. A
     * target's own square is occupied by the target, so a route to it can only
     * ever end on top of somebody; the walk then stops short, the attack order
     * sees itself still out of range, asks for the same route again, and the
     * unit jogs on the spot forever without ever swinging. Aiming at "any
     * square from which I could hit that" is what upstream does and what makes
     * a chaser arrive.
     *
     * @param x        the goal rectangle's left column
     * @param y        its top row
     * @param width    its width in squares, at least one
     * @param height   its height in squares, at least one
     * @param minRange nearest the mover may end up, for siege engines that
     *                 cannot fire at their feet
     * @param maxRange furthest the mover may end up and still be finished
     */
    public record Goal(int x, int y, int width, int height, int minRange, int maxRange) {

        /** A single square, which is what an ordinary move order aims at. */
        public static Goal square(int x, int y) {
            return new Goal(x, y, 1, 1, 0, 0);
        }
    }

    /**
     * Whether every ask and answer is printed, for the parity harness.
     *
     * <p>The implementation-side twin of the {@code LEGACY_ENGINE_TRACE_PATH} hook in
     * {@code tools/legacyEngine-trace.patch}: set {@code CHONKCRAFT_TRACE_PATH} and
     * every {@code find} prints its request rectangle, ranges, verdict and
     * heading string to stderr, diffable against upstream's PATHDBG lines by
     * origin and goal. Off in every ordinary run, and behaviour-neutral when
     * on.
     */
    private static final boolean TRACE_PATH = System.getenv("CHONKCRAFT_TRACE_PATH") != null;

    /**
     * The simulation cycle the traced asks belong to.
     *
     * <p>Upstream's PATHDBG stamps {@code GameCycle} on every line and the
     * lines here had no counterpart, which made "which ask is this" a
     * correlation puzzle exactly when the harness needed it not to be --
     * levelx04h's footman fork at cycle 88 was read from four unstamped asks.
     * The world writes it once per tick, and only while tracing.
     */
    private long traceCycle;

    /** @see #traceCycle */
    public void setTraceCycle(long cycle) {
        this.traceCycle = cycle;
    }

    /**
     * The unit whose asks are being traced, stamped like {@link #traceCycle}.
     *
     * <p>Upstream's PATHDBG prints {@code UnitNumber(unit)} on every line;
     * without the twin field two footmen asking from adjacent squares in the
     * same cycle cannot be told apart -- levelx04h's fork at 88 was
     * misattributed twice for exactly that reason.
     */
    private int traceUnit = -1;

    /** @see #traceUnit */
    public void setTraceUnit(int unit) {
        this.traceUnit = unit;
    }

    /** Whether the parity harness wants {@link #setTraceCycle} called. */
    public static boolean tracingAsks() {
        return TRACE_PATH;
    }

    /**
     * Finds a route to anywhere the goal is satisfied from.
     *
     * @param mover what the searching unit is and what stops it
     */
    public Path find(int fromX, int fromY, Goal target, Mover mover) {
        if (!TRACE_PATH) {
            return findInner(fromX, fromY, target, mover);
        }
        Path path = findInner(fromX, fromY, target, mover);
        StringBuilder headings = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            headings.append(path.headings()[i]);
        }
        System.err.printf("JPATHDBG cycle=%d unit=%d from %d,%d goal %d,%d size %dx%d range %d-%d -> %s %d path=%s%n",
                traceCycle, traceUnit, fromX, fromY, target.x(), target.y(),
                Math.max(1, target.width()), Math.max(1, target.height()),
                target.minRange(), target.maxRange(),
                path.result(), path.length(), headings);
        return path;
    }

    private Path findInner(int fromX, int fromY, Goal target, Mover mover) {
        int toX = target.x();
        int toY = target.y();
        if (!map.contains(fromX, fromY) || !map.contains(toX, toY)) {
            return Path.of(Result.UNREACHABLE);
        }
        boolean ranged = target.maxRange() > 0 || target.minRange() > 0
                || target.width() > 1 || target.height() > 1;
        boolean inside = fromX >= toX && fromX <= toX + Math.max(1, target.width()) - 1
                && fromY >= toY && fromY <= toY + Math.max(1, target.height()) - 1;
        // "At exact destination point already", which is the one answer
        // {@code AStarFindSimplePath} gives before it refuses

        if (fromX == toX && fromY == toY && target.minRange() == 0) {
            return Path.of(Result.REACHED);
        }
        // And then, in this order:
        //
        //   // Don't allow unit inside destination area
        //   if (goal.x <= startPos.x && startPos.x <= goal.x + gw - 1
        //       && goal.y <= startPos.y && startPos.y <= goal.y + gh - 1) {
        //       return PF_FAILED;
        //   }
        //
        // PF_FAILED is the shortcut declining to answer, and everything it
        // declines goes to the full search. So a unit standing inside a goal
        // rectangle -- a peasant on the ground its town hall is going up on --
        // gets the whole of {@code AStarFindPath}: {@code AStarCleanUp},
        // {@code AStarMarkGoal}, and then PF_REACHED from the goal test on the
        // start square. The answer is the same
        // and the cleanup is not: {@code AStarCleanUp} is what empties
        // {@code CostMoveToCache}, and that memo is what every one-square step
        // in the game is judged by.
        //
        // On {@code maps/skirmish/(3)critter-attack} that one search is the
        // difference. Upstream's orc peasant asks on cycle 53 whether it has
        // reached its site at 62,78 and empties the memo doing it; this implementation
        // answered "you are standing in it" without looking, so a critter at
        // 66,76 twenty squares away read a cost from cycle 40 on cycle 62 and
        // stepped at a square that had stopped being free.
        if (!inside) {
            int dx = toX - fromX;
            int dy = toY - fromY;
            // "Within range of destination": the
            // straight line from the start square to the goal's own
            // top-left corner, against the two ranges, and no cost test at
            // all -- not the footprint gap, not whether the asker could
            // stand anywhere. The askers this decides for are the ones that
            // cannot move: a guard tower's bill for a grunt six squares out
            // is PF_REACHED here, and this implementation used to demand the tower be
            // able to enter its own square first, so on
            // campaigns/human-exp/levelx12h every one of its bills read
            // unreachable and it shot the one target upstream prices
            // lowest.
            int distance = isqrt(dx * dx + dy * dy);
            if (target.minRange() <= distance && distance <= target.maxRange()) {
                return Path.of(Result.REACHED);
            }
            // The one-square case never reaches the search at all.
            // {@code AStarFindPath} opens with {@code AStarFindSimplePath}
            // whose last arm is
            //
            //   if (abs(diff.x) <= 1 && abs(diff.y) <= 1) {
            //       if (CostMoveTo(GetIndex(goal.x, goal.y), unit) == -1)
            //           return PF_UNREACHABLE;
            //       path[0] = XY2Heading[diff.x + 1][diff.y + 1];
            //       return 1;
            //   }
            //
            // and it runs *before* {@code AStarCleanUp}, which is the whole
            // of its interest here: {@code CostMoveTo} reads a cache that
            // only a full search clears, so a step onto the square next
            // door is judged by whatever the last full search left there.
            // See {@link #cachedEnterCost}.
            if (Math.abs(dx) <= 1 && Math.abs(dy) <= 1) {
                if (cachedEnterCost(toX, toY, mover) < 0) {
                    return Path.of(Result.UNREACHABLE);
                }
                return new Path(Result.FOUND, new int[] {Direction.fromDelta(dx, dy)});
            }
        }

        // Everything below is the full search, and upstream's opens by
        // emptying that cache: {@code AStarCleanUp} calls
        // {@code CostMoveToCacheCleanUp}.
        clearCostCache();
        if (System.getenv("CHONKCRAFT_TRACE_CLEAN") != null) {
            System.err.printf(
                    "JPATHCLEAN cycle=%d unit=%d from=%d,%d goal=%d,%d"
                            + " size=%dx%d range=%d-%d%n",
                    traceCycle, traceUnit, fromX, fromY, target.x(), target.y(),
                    target.width(), target.height(),
                    target.minRange(), target.maxRange());
        }
        searchesRun++;

        // AStarMarkGoal runs before the first node is expanded. Besides
        // identifying the destination region it calls CostMoveTo on every
        // square in that region, populating the process-wide memo later
        // one-square shortcuts read. Testing membership only as nodes were
        // expanded missed the unvisited side of a range ring: levelx12h's
        // cycle-386 reachability probe cached 20,59 as blocked upstream, then
        // the cycle-389 restored march read it; this implementation recomputed it as
        // open and stepped.
        if (!markGoalCosts(target, mover)) {
            return Path.of(Result.UNREACHABLE);
        }

        // The goal test on the start square, which upstream reaches only after
        // the cleanup and the marking. This is
        // where a unit standing inside its goal rectangle is finally told so.
        if (ranged ? satisfies(fromX, fromY, target, mover)
                : (fromX == toX && fromY == toY)) {
            return Path.of(Result.REACHED);
        }

        int width = map.width();
        int size = width * map.height();
        int[] cameFrom = scratchCameFrom(size);
        int[] costSoFar = scratchCostSoFar(size);
        int[] stamp = scratchStamp(size);
        int mark = nextGeneration();

        int[] openFrontier = scratchOpenFrontier(size);
        int[] arrival = scratchArrival(size);
        int arrivals = 0;

        int start = fromY * width + fromX;
        int goal = toY * width + toX;
        stamp[start] = mark;
        cameFrom[start] = -1;
        costSoFar[start] = 0;

        // Ordered by estimated total cost, and then by the order the squares
        // arrived. {@code AStarAddNode} keeps the open set as an array sorted
        // by three keys and the second of them is
        // written {@code const int costToGoal = costs;} -- the *new* node's
        // whole estimate, compared against the stored node's distance-to-goal
        // alone. One is the other plus the cost from the start, so the new
        // node always sorts as the greater, always lands ahead of the squares
        // it ties with, and -- {@code AStarFindMinimum()} being
        // {@code (OpenSetSize - 1)}, the last slot -- always comes out after
        // them. A tie is settled by which square was reached first.
        //
        // That is also deterministic, which lockstep multiplayer needs: two
        // machines must choose the same path from the same state.
        PriorityQueue<long[]> open = new PriorityQueue<>((a, b) -> {
            if (a[0] != b[0]) {
                return Long.compare(a[0], b[0]);
            }
            return Long.compare(a[2], b[2]);
        });
        long firstF = heuristic(fromX, fromY, toX, toY, target);
        openFrontier[start] = (int) firstF;
        arrival[start] = arrivals++;
        open.add(new long[] {firstF, start, arrival[start]});

        // AStarMaxSearchIterations is charged when a successor is first
        // discovered or a cheaper way to one is found, not when a node is
        // taken from the open set.  Once it is spent,
        // upstream saves a path to the next minimum node it popped.  That is
        // deliberately not "the closest Manhattan square so far": a unit
        // boxed two tiles from its destination can have to walk away and all
        // the way around a crowd, so no intermediate square is closer than
        // the start. human/level08h's cycle-1689 peasant is exactly that
        // shape; upstream returns the 64-step partial escape while the old
        // closest-only transcription returned unreachable.
        int remaining = maxNodes;
        while (!open.isEmpty()) {
            long[] current = open.poll();
            int index = (int) current[1];
            // Upstream's open set holds one entry per square -- an improvement
            // is {@code AStarReplaceNode}, which lifts the entry out and puts
            // it back rather than leaving a second one behind. A heap cannot
            // reach into its own middle, so the stale entry is left and
            // recognised here by its arrival number.
            if (arrival[index] != current[2]) {
                continue;
            }
            stamp[index] = mark;
            String tracedSearchCycle = System.getenv("CHONKCRAFT_TRACE_SEARCH_CYCLE");
            if (tracedSearchCycle != null
                    && traceCycle == Long.parseLong(tracedSearchCycle)) {
                System.err.printf("JSEARCHDBG cycle=%d unit=%d at=%d,%d"
                                + " cost=%d estimate=%d arrival=%d%n",
                        traceCycle, traceUnit, index % width, index / width,
                        costSoFar[index], current[0], current[2]);
            }
            if (ranged
                    ? satisfies(index % width, index / width, target, mover)
                    : index == goal) {
                return new Path(Result.FOUND, reconstruct(cameFrom, start, index, width));
            }
            nodesExpanded++;
            if (remaining <= 0) {
                // Out of budget, not out of map. AStarSavePath is given this
                // just-popped minimum node, whether it improved the straight
                // line distance or not.
                return new Path(Result.FOUND,
                        reconstruct(cameFrom, start, index, width));
            }

            int x = index % width;
            int y = index / width;
            // Where this square was reached from, which upstream skips:
            // "Don't check the tile we came from, it's not going to be better"
            // Without a closed set that skip is
            // the only thing stopping every expansion from walking straight
            // back the way it came.
            int cameX = -1;
            int cameY = -1;
            if (cameFrom[index] >= 0) {
                cameX = cameFrom[index] % width;
                cameY = cameFrom[index] / width;
            }
            for (int heading = 0; heading < Direction.COUNT; heading++) {
                int nx = x + Direction.deltaX(heading);
                int ny = y + Direction.deltaY(heading);
                if (nx == cameX && ny == cameY) {
                    continue;
                }
                if (!map.contains(nx, ny)) {
                    continue;
                }
                int next = ny * width + nx;

                // The goal square is the one tile whose cost depends on more
                // than its position, so it is the one tile not worth caching.
                int enter = next == goal
                        ? enterCost(nx, ny, mover, true)
                        : cachedEnterCost(nx, ny, mover);
                if (enter < 0) {
                    continue;
                }
                // No corner rule. The C++ has none -- all eight headings are
                // tried unconditionally -- and the comment that used to sit
                // here claiming otherwise was simply wrong. Refusing the
                // diagonal gap between two trees is what sent units the long
                // way round a forest.

                int step = STEP_COST + enter;
                int cost = costSoFar[index] + step;
                // A square this search has not touched yet carries whatever the
                // last search left in it, so the stamp -- not the contents --
                // is what says "unvisited". That is the whole trick that lets
                // the three arrays be reused instead of reallocated and
                // refilled on every call.
                boolean fresh = stamp[next] != mark && stamp[next] != mark + 1;
                if (fresh) {
                    remaining--;
                    stamp[next] = mark + 1;
                    costSoFar[next] = cost;
                    cameFrom[next] = index;
                    openFrontier[next] = (int) (cost + heuristic(nx, ny, toX, toY, target));
                    arrival[next] = arrivals++;
                    open.add(new long[] {openFrontier[next], next, arrival[next]});
                } else if (cost < costSoFar[next]) {
                    remaining--;
                    // A square this search has already handed out can still be
                    // improved on, and upstream lets it be: there is no closed
                    // set in {@code AStarFindPath} at all, only the two arms
                    // "not visited" and "visited, but this is cheaper"
                    // With a heuristic eight
                    // times the true step cost that is not a nicety -- the
                    // search reaches squares by the greedy route first and
                    // corrects them afterwards, and a port that refuses to
                    // reconsider keeps the greedy answer.
                    costSoFar[next] = cost;
                    cameFrom[next] = index;
                    if (stamp[next] != mark + 1) {
                        // Already handed out: it goes back in at what it now
                        // costs.
                        stamp[next] = mark + 1;
                        openFrontier[next] = (int) (cost + heuristic(nx, ny, toX, toY, target));
                    }
                    // Still waiting, and then it keeps the estimate it went in
                    // with. {@code AStarReplaceNode} is written to re-add the
                    // node "with the new cost" and passes {@code node.GetCosts()}
                    // -- the old one. All the move
                    // does is put the square behind the ones it ties with.
                    arrival[next] = arrivals++;
                    open.add(new long[] {openFrontier[next], next, arrival[next]});
                }
            }
        }
        // Nowhere left to look: there is genuinely no route. Upstream draws
        // the same distinction -- an exhausted open set is unreachable, and
        // only an exhausted *budget* returns the best it managed. Blurring the
        // two would make "you cannot get there" indistinguishable from "this
        // is taking a while", and a land unit ordered across water would
        // trudge to the shoreline instead of refusing.
        return Path.of(Result.UNREACHABLE);
    }

    /**
     * Populates CostMoveToCache for AStarMarkGoal's complete destination
     * region and reports whether at least one of its squares can be entered.
     *
     * <p>{@link #inMarkedRegion} is the same five-band visitor expressed as a
     * membership test. The bounded rectangle keeps this proportional to the
     * actual range instead of walking the whole map for ordinary goals; a
     * range large enough to cover the map deliberately visits the map, as
     * upstream's visitor does.
     */
    private boolean markGoalCosts(Goal target, Mover mover) {
        // A point goal with no range gives AStarMarkGoal exactly one tile to
        // mark.  Do not expand it by the mover's footprint: that expansion is
        // for the positions whose footprint touches a ranged/area target.  A
        // 2x2 tanker otherwise makes a blocked destination appear markable
        // because some neighbouring top-left position is clear, after which
        // the goal-only obstacle exception lets the search enter the tile.
        boolean ranged = target.maxRange() > 0 || target.minRange() > 0
                || target.width() > 1 || target.height() > 1;
        if (!ranged) {
            return cachedEnterCost(target.x(), target.y(), mover) >= 0;
        }

        int goalWidth = Math.max(1, target.width());
        int goalHeight = Math.max(1, target.height());
        int extraX = Math.max(1, mover.tileWidth()) - 1;
        int extraY = Math.max(1, mover.tileHeight()) - 1;
        int reach = target.maxRange();

        int left = Math.max(0, target.x() - reach - extraX);
        int top = Math.max(0, target.y() - reach - extraY);
        int right = Math.min(map.width() - 1 - extraX,
                target.x() + goalWidth - 1 + reach);
        int bottom = Math.min(map.height() - 1 - extraY,
                target.y() + goalHeight - 1 + reach);

        boolean marked = false;
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                if (!inMarkedRegion(x, y, target, mover,
                        map.width(), map.height())) {
                    continue;
                }
                if (cachedEnterCost(x, y, mover) >= 0) {
                    marked = true;
                }
            }
        }
        return marked;
    }

    /**
     * Whether the goal has no square beside it that the mover could stand on.
     *
     * <p>A route to an exact square has to arrive from one of its eight
     * neighbours. If the mover can stand on none of them the goal is
     * unreachable, and no amount of searching will discover anything else --
     * A* will expand every square it can get to, run out of budget, and hand
     * back the closest it managed, which the caller then reads as "reachable".
     *
     * <p>That is not a hypothetical. Two guard towers on {@code levelx12h} are
     * built into rock with impassable terrain on all eight sides, and the orc
     * army parked next to them re-asked this question once a cycle for the
     * whole mission: three hundred and fifty-four searches over nine hundred
     * cycles, four thousand squares each, a little over half of every route
     * search the map performed. The towers can be shot at, which is why the
     * map is built that way, but no footman will ever walk up to one.
     *
     * <p>The searcher's own square counts as open whatever is standing on it.
     * It is already there, so it can certainly leave it, and
     * {@link #enterCost} would otherwise refuse a unit boxed in by its own
     * fellows the one route it actually has.
     */
    private boolean isSealed(int fromX, int fromY, Goal target, boolean ranged, Mover mover) {
        if (!ranged) {
            // The goal square itself, which is upstream's own question:
            // {@code AStarMarkGoal} marks a square only where
            // {@code CostMoveTo >= 0}, and {@code AStarFindPath} returns
            // PF_UNREACHABLE without searching when nothing was marked
            // A point goal has one square to mark, so
            // a destination somebody is standing on is refused before the
            // search begins.
            //
            // Asked of the eight squares around it instead, this let such an
            // order start a search that could not succeed: on levelx11h that
            // was a unit two squares from its destination expanding four
            // thousand of them to find out, eight hundred times over.
            if ((fromX != target.x() || fromY != target.y())
                    && enterCost(target.x(), target.y(), mover, false) < 0) {
                return true;
            }
            for (int heading = 0; heading < Direction.COUNT; heading++) {
                int nx = target.x() + Direction.deltaX(heading);
                int ny = target.y() + Direction.deltaY(heading);
                if (!map.contains(nx, ny)) {
                    continue;
                }
                if ((nx == fromX && ny == fromY) || enterCost(nx, ny, mover, false) >= 0) {
                    return false;
                }
            }
            return true;
        }

        // A goal with a range is satisfied from anywhere in a box around the
        // target's footprint, so the same question is asked of that box. Only
        // worth asking while the box is smaller than the search it replaces:
        // an AI probe with a range of 255 covers more squares than the node
        // budget, and checking them all would cost more than giving up.
        //
        // The squares scanned are corner positions, and a wide mover's corner
        // stands up to its width less one outside the ring its footprint
        // touches, so the box grows by the mover's size on the low sides. A
        // two-by-two oil tanker whose nose touches the refinery's ring from
        // 84,46 has its corner outside the one-by-one box, and a scan that
        // missed it called the whole errand sealed -- levelx03h's tanker was
        // told no road to a refinery six squares of open water away.
        int reach = target.maxRange();
        int lowX = target.x() - reach - (Math.max(1, mover.tileWidth()) - 1);
        int lowY = target.y() - reach - (Math.max(1, mover.tileHeight()) - 1);
        int boxWidth = target.width() + 2 * reach + Math.max(1, mover.tileWidth()) - 1;
        int boxHeight = target.height() + 2 * reach + Math.max(1, mover.tileHeight()) - 1;
        if ((long) boxWidth * boxHeight > maxNodes) {
            return false;
        }
        for (int y = lowY; y < target.y() + target.height() + reach; y++) {
            for (int x = lowX; x < target.x() + target.width() + reach; x++) {
                if (!map.contains(x, y)) {
                    continue;
                }
                if ((x == fromX && y == fromY) || satisfies(x, y, target, mover)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * What it costs to enter a square, remembered across searches.
     *
     * <p>{@code CostMoveTo} is a
     * memoised {@code CostMoveToCallBack_Default}, and the memo is only
     * emptied by {@code AStarCleanUp} -- which a full search calls and the
     * one-square shortcut above does not. So an answer written by one search
     * is read by shortcuts for as long as no full search runs in between,
     * however many cycles that is and whoever has walked where in the
     * meantime.
     *
     * <p>That is not tidy and it is not incidental. On
     * {@code maps/skirmish/(3)critter-attack} a critter at 69,23 asks three
     * times to step onto 69,22 and the answers are, printed from the running
     * binary, {@code cached=9 fresh=28} on cycle 2, {@code cached=-1 fresh=28}
     * on cycle 24 -- unset, so 28 is computed and kept -- and
     * {@code cached=29 fresh=-1} on cycle 46. By 46 the critter standing on
     * 69,22 has stopped moving and the square is genuinely refused, and
     * upstream steps anyway on a number twenty-two cycles old. The move then
     * answers PF_WAIT because {@code UnitCanBeAt} is asked separately and does
     * not consult the cache at all.
     */
    private int cachedEnterCost(int x, int y, Mover mover) {
        int size = map.width() * map.height();
        if (costCache.length != size) {
            costCache = new int[size];
            java.util.Arrays.fill(costCache, COST_UNSET);
        }
        int index = y * map.width() + x;
        if (costCache[index] != COST_UNSET) {
            if (System.getenv("CHONKCRAFT_TRACE_COST_TILE") != null
                    && x == 20 && y == 59) {
                System.err.printf("JCOSTTILE cycle=%d unit=%d tile=20,59 cached=%d%n",
                        traceCycle, traceUnit, costCache[index]);
            }
            return costCache[index];
        }
        int cost = enterCost(x, y, mover, false);
        costCache[index] = cost;
        if (System.getenv("CHONKCRAFT_TRACE_COST_TILE") != null
                && x == 20 && y == 59) {
            System.err.printf("JCOSTTILE cycle=%d unit=%d tile=20,59 fresh=%d%n",
                    traceCycle, traceUnit, cost);
        }
        return cost;
    }

    private void clearCostCache() {
        if (costCache.length != 0) {
            java.util.Arrays.fill(costCache, COST_UNSET);
        }
    }

    /** No answer yet, which is upstream's {@code CacheNotSet}. */
    private static final int COST_UNSET = Integer.MIN_VALUE;

    private int[] costCache = new int[0];

    // ------------------------------------------------------------- scratch
    //
    // A* needs three arrays the size of the map, and allocating and filling
    // them per call made the cost of a search proportional to the map rather
    // than to the route: a six-square hop on a 96x96 map paid for 9216
    // squares three times over. Reusing them across calls costs one int array
    // more and takes the allocation to nothing. They are instance state, not
    // static, so two PathFinders over two maps cannot tread on each other; a
    // single World has one, and ticks are single-threaded.

    private int[] cameFromScratch = new int[0];
    private int[] costSoFarScratch = new int[0];
    private int[] stampScratch = new int[0];
    private int[] openFrontierScratch = new int[0];
    private int[] arrivalScratch = new int[0];

    /**
     * Which search a stamp belongs to.
     *
     * <p>Advanced by two per search because a square has three states and one
     * array holds them: {@code mark} means seen, {@code mark + 1} means
     * closed, and anything else means this search has never looked at it.
     */
    private int generation;

    private int nextGeneration() {
        generation += 2;
        if (generation < 0) {
            // Two thousand million searches later. Wiping the stamps makes
            // every square unvisited again, which is exactly what a fresh
            // array would have said.
            generation = 2;
            Arrays.fill(stampScratch, 0);
        }
        return generation;
    }

    private int[] scratchCameFrom(int size) {
        if (cameFromScratch.length < size) {
            cameFromScratch = new int[size];
        }
        return cameFromScratch;
    }

    private int[] scratchCostSoFar(int size) {
        if (costSoFarScratch.length < size) {
            costSoFarScratch = new int[size];
        }
        return costSoFarScratch;
    }

    private int[] scratchStamp(int size) {
        if (stampScratch.length < size) {
            stampScratch = new int[size];
        }
        return stampScratch;
    }

    /** What each waiting square went into the open set costing. */
    private int[] scratchOpenFrontier(int size) {
        if (openFrontierScratch.length < size) {
            openFrontierScratch = new int[size];
        }
        return openFrontierScratch;
    }

    /** Which entry for a square is the live one, and how ties are settled. */
    private int[] scratchArrival(int size) {
        if (arrivalScratch.length < size) {
            arrivalScratch = new int[size];
        }
        return arrivalScratch;
    }

    /**
     * Extra cost of entering a square, or {@code -1} if it cannot be entered.
     *
     * <p>The goal square is allowed to be occupied: a move order aimed at a
     * spot someone is standing on should still produce a route to it, and the
     * move action stops short when it arrives.
     */
    private int enterCost(int x, int y, Mover mover, boolean isGoal) {
        String traceCost = System.getenv("CHONKCRAFT_TRACE_COST_COORD");
        boolean traceThisCost = traceCost != null && traceCost.equals(x + "," + y);
        if (traceThisCost) {
            System.err.printf("JCOSTCOORD cycle=%d unit=%d at=%d,%d footprint=%dx%d"
                            + " terrain=%x blocking=%x goal=%d%n",
                    traceCycle, traceUnit, x, y, mover.tileWidth(), mover.tileHeight(),
                    mover.terrain(), mover.blocking(), isGoal ? 1 : 0);
        }
        // Occupancy is read over the whole footprint, not just the top-left
        // corner. A transport is two tiles by two, and checking one of the
        // four meant the planner and the mover disagreed about three quarters
        // of every ship's destination: the route was planned through other
        // ships and then refused a step at a time.
        boolean flying = mover.flying();
        // Summed across the footprint and divided by its area at the end, as
        // {@code CostMoveToCallBack_Default} does
        // The game the crossing costs of whatever is in
        // the way, plus the tile's own movement cost, per square.
        int total = 0;
        for (int dy = 0; dy < mover.tileHeight(); dy++) {
            for (int dx = 0; dx < mover.tileWidth(); dx++) {
                MapField field = map.fieldOrNull(x + dx, y + dy);
                if (traceThisCost) {
                    System.err.printf("JCOSTCOORD tile=%d,%d flags=%x%n",
                            x + dx, y + dy, field == null ? -1L : field.flags());
                }
                // A mask may name several kinds of ground: an air unit's
                // covers land, water and coast, and any one of them will do.
                // hasFlag already tests for any bit, which is what makes a
                // multi-flag mask work.
                if (field == null || !field.hasFlag(mover.terrain())) {
                    return -1;
                }
                // Forest and mountain stop a footman, not a gryphon.
                if (!flying && field.hasFlag(TileFlag.UNPASSABLE)) {
                    return -1;
                }

                // What is on the square, of the things that stop *this* unit.
                // Asking the mover rather than deciding here is the whole
                // point: a building stops a footman and not a gryphon, and a
                // gryphon is stopped by a thing a footman walks straight
                // under. Upstream reads the same one mask off the unit.
                long blocked = field.flags() & mover.blocking();
                if (blocked == 0) {
                    continue;
                }
                if ((blocked & ~UNIT_OCCUPANCY) != 0) {
                    // A building or a wall: not something that will move.
                    // Allowed as the goal so that an order aimed at one still
                    // sets off, and the walk stops short on arrival.
                    return isGoal ? 0 : -1;
                }
                switch (mover.occupancy().at(x + dx, y + dy)) {
                    case Occupancy.MOVING -> total += MOVING_UNIT_CROSSING_COST;
                    case Occupancy.STATIONARY_ENEMY -> total += ENEMY_UNIT_CROSSING_COST;
                    case Occupancy.STATIONARY -> {
                        // A settled unit refuses the square, goal or not:
                        // CostMoveToCallBack_Default has no goal exception
                        // for units -- "for non moving unit Always Fail" --
                        // and the refusal is what turns a wander at an
                        // occupied square into UNREACHABLE and the widen
                        // loop's last round. On level11h a second critter
                        // settles on the wanderer's goal mid-loop: upstream's
                        // cycle-168 ask answers unreachable and serves one
                        // more round, and a port that still allowed the goal
                        // square answered a route and finished a cycle early,
                        // with a wander draw upstream had not made yet.
                        return -1;
                    }
                    default -> {
                        // The searcher's own square, or a unit it does not
                        // know about: no charge.
                    }
                }
            }
        }
        // And the ground itself, which this implementation charged nothing for. Upstream
        // ends the same walk with {@code cost += mf->getMoveCost()}, and
        // {@code CMapField} sets that to {@code 1 << (flag & MapFieldSpeedMask)}
        // Every ChonkCraft tile carries the whole
        // of that mask, because the flag word starts at three and no tileset
        // names a speed, so ordinary ground costs eight -- printed from the
        // running binary, every passable square around a footman on demo03
        // costs exactly that.
        //
        // It decides how greedy the search is rather than which routes exist.
        // Upstream's cost-from-start grows by nine a step against a heuristic
        // of eight a square; charging nothing for the ground made this implementation's
        // grow by one, a search eight or nine times greedier, and greed is
        // what chooses between two routes of the same length.
        for (int dy = 0; dy < mover.tileHeight(); dy++) {
            for (int dx = 0; dx < mover.tileWidth(); dx++) {
                MapField field = map.fieldOrNull(x + dx, y + dy);
                if (field != null) {
                    total += 1 << (field.flags() & TileFlag.SPEED_MASK);
                }
            }
        }
        return total / Math.max(1, mover.tileWidth() * mover.tileHeight());
    }

    /** Occupancy that belongs to a unit, which may yet move out of the way. */
    private static final long UNIT_OCCUPANCY =
            TileFlag.LAND_UNIT | TileFlag.AIR_UNIT | TileFlag.SEA_UNIT;

    /**
     * Manhattan distance, multiplied up: {@code AStarCosts} transcribed.
     *
     * <p>No allowance is made for a goal the walk may stop short of. Upstream
     * makes none either: the range is settled by the goal test, and the
     * estimate only says which square to look at next.
     */
    private static int heuristic(int x, int y, int toX, int toY, Goal target) {
        return GREED * (Math.abs(x - toX) + Math.abs(y - toY));
    }

    /**
     * Whether a mover standing at a square has arrived.
     *
     * <p>The shape is {@code MinMaxRangeVisitor}'s, transcribed as a
     * membership test: {@code AStarMarkGoal} walks five bands around the
     * goal box -- a hemicycle above, a min-range ring above, the centre, and
     * their mirrors below -- and each row's half-width is
     * {@code isqrt(square(range + 1) - square(dy) - 1)} where {@code dy} is
     * measured corner to corner, top of mover to top of goal above and
     * bottom to bottom below, with the mover's extra tiles folded into the
     * band bounds rather than the distance.
     *
     * <p>This used to measure footprint-to-footprint with
     * {@code MapDistanceBetweenTypes}' rounding, and the two disagree one
     * ring out: on campaigns/human/level09h a two-by-two destroyer at 29,37
     * reads footprint distance four to the balloon's box at 32,42 -- in
     * reach -- while upstream's row window at that height is
     * {@code isqrt(square(5) - square(5) - 1)}, nought, and the tile is no
     * goal. The implementation's ship ended its chase there, one square and one
     * spent-route pause off upstream's stop at 29,38 on cycle 18.
     */
    private boolean satisfies(int x, int y, Goal target, Mover mover) {
        if (enterCost(x, y, mover, false) < 0) {
            return false;
        }
        return inMarkedRegion(x, y, target, mover, map.width(), map.height());
    }

    /**
     * The five bands of {@code MinMaxRangeVisitor}, asked of one square.
     *
     * <p>A mover whose body would overhang the map's far edge is never
     * marked, which is the visitor's own clamp of every band's maximum to
     * {@code MapWidth - 1 - unitExtraTileSize.x}.
     */
    private static boolean inMarkedRegion(int x, int y, Goal target, Mover mover,
            int mapWidth, int mapHeight) {
        int gw = Math.max(1, target.width());
        int gh = Math.max(1, target.height());
        int gx1 = target.x();
        int gy1 = target.y();
        int gx2 = gx1 + gw - 1;
        int gy2 = gy1 + gh - 1;
        int ex = Math.max(1, mover.tileWidth()) - 1;
        int ey = Math.max(1, mover.tileHeight()) - 1;
        int min = target.minRange();
        int max = target.maxRange();

        if (x > mapWidth - 1 - ex || y > mapHeight - 1 - ey) {
            return false;
        }
        if (y <= gy1 - 1 - ey) {
            // Above the box. The outer hemicycle's rows, then -- with a
            // minimum range -- the ring rows nearer in.
            if (y < gy1 - max - ey) {
                return false;
            }
            int dy = y - gy1;
            if (min > 0 && y > gy1 - min - ey) {
                int offMax = maxOffsetX(dy, max);
                int offMin = maxOffsetX(dy, min - 1) + 1;
                return (x >= gx1 - offMax - ex && x <= gx1 - offMin - ex)
                        || (x >= gx2 + offMin && x <= gx2 + offMax);
            }
            int off = maxOffsetX(dy, max);
            return x >= gx1 - off - ex && x <= gx2 + off;
        }
        if (y <= gy2) {
            // The centre band, level with the box.
            if (min == 0) {
                return x >= gx1 - max - ex && x <= gx2 + max;
            }
            return (x >= gx1 - max - ex && x <= gx1 - min - ex)
                    || (x >= gx2 + min && x <= gx2 + max);
        }
        // Below the box, mirrored.
        if (y > gy2 + max) {
            return false;
        }
        int dy = y - gy2;
        if (min > 0 && y < gy2 + min) {
            int offMax = maxOffsetX(dy, max);
            int offMin = maxOffsetX(dy, min - 1) + 1;
            return (x >= gx1 - offMax - ex && x <= gx1 - offMin - ex)
                    || (x >= gx2 + offMin && x <= gx2 + offMax);
        }
        int off = maxOffsetX(dy, max);
        return x >= gx1 - off - ex && x <= gx2 + off;
    }

    /** {@code GetMaxOffsetX}: the row's half-width on upstream's circle. */
    private static int maxOffsetX(int dy, int range) {
        return isqrt((range + 1) * (range + 1) - dy * dy - 1);
    }

    /** Integer square root, as upstream's {@code isqrt}. */
    private static int isqrt(int value) {
        if (value <= 0) {
            return 0;
        }
        int root = (int) Math.sqrt(value);
        while (root * root > value) {
            root--;
        }
        while ((root + 1) * (root + 1) <= value) {
            root++;
        }
        return root;
    }

    /** Walks the parent links back, producing headings in reverse order. */
    private static int[] reconstruct(int[] cameFrom, int start, int goal, int width) {
        Deque<Integer> headings = new ArrayDeque<>();
        int index = goal;
        while (index != start) {
            int previous = cameFrom[index];
            int dx = (index % width) - (previous % width);
            int dy = (index / width) - (previous / width);
            // Pushed last-first, so element 0 ends up being the final step and
            // the move action can pop from the end.
            headings.addLast(Direction.fromDelta(dx, dy));
            index = previous;
        }
        int[] path = new int[headings.size()];
        int i = 0;
        for (int heading : headings) {
            path[i++] = heading;
        }
        return path;
    }
}
