package net.chonkbase.chonkcraft.engine;

import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.engine.map.MapField;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.unit.ResourceInfo;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;

/**
 * Where the computer decides to put a building.
 *
 * <p>Implements retail BNE's own
 * placement routines at {@code 0x43a380}, {@code 0x43a420} and
 * {@code 0x439de0}. It reads the map and the unit tables and answers with a
 * tile; it changes nothing, which is why it is the one part of the AI that
 * can be read without the rest of the simulation in view.
 */
final class BattleNetBuildingPlacement {

    private final World world;

    BattleNetBuildingPlacement(World world) {
        this.world = world;
    }

    /** The pair returned by upstream's {@code AiGetSuitableDepot}. */
    record SuitableDepot(Unit depot, Unit mine) {
    }


    /**
     * Where the computer puts a building.
     *
     * <p>Implements {@code AiFindBuildingPlace}
     * and the three finders behind it,
     * each a flood fill over ground the worker can cross. A depot goes to a
     * hall finder that floods for a usable mine and then places around the
     * mine; a depot for a terrain-harvested resource -- the lumber mill --
     * floods for the nearest wood and places beside it; a building that
     * gives a refined resource goes to a mining-place search; and everything
     * else is placed by the plain finder flooding out from the worker
     * itself. From the worker, not from a depot: this implementation used to grow
     * ordinary buildings around its town centres, and on
     * campaigns/orc-exp/levelx04o that put player 2's barracks somewhere
     * upstream's -- flooded from the one free peasant at 23,13 and landed
     * one square west of it -- was never going to be.
     *
     * @param nearX preferred start, or any off-map value to start at the
     *              worker, which is where every script-raised request starts
     *              ({@code AiBuildQueue.Pos} is only ever set by the plans)
     */
    int[] aiFindBuildingPlace(Unit worker, UnitType type, int nearX, int nearY) {
        int startX = worker.tileX();
        int startY = worker.tileY();
        if (world.map.contains(nearX, nearY)) {
            startX = nearX;
            startY = nearY;
        }
        // "Mines and Depots", walked in cost order: gold, wood, oil.
        UnitType.Resource[] kinds = {
            UnitType.Resource.GOLD, UnitType.Resource.WOOD, UnitType.Resource.OIL,
        };
        for (UnitType.Resource kind : kinds) {
            net.chonkbase.chonkcraft.engine.unit.ResourceInfo info =
                    worker.type().gathering().get(kind);
            if (type.stores().contains(kind)) {
                if (info != null && info.terrainHarvester()) {
                    return aiLumberMillPlace(worker, type, startX, startY);
                }
                return aiHallPlace(worker, type, startX, startY, kind);
            }
            if (type.givesResource() == kind) {
                // A mine on top of its resource -- the oil platform -- goes
                // through BuildingPlaceFinder with checkSurround false, and
                // that combination cannot succeed: Visit only answers
                // Finished when checkSurround is true, and Run's exhaustion
                // is "not found" whatever backup position was noted on the
                // way. The AI's platforms are placed by the plans upstream,
                // not by this path, and this path's honest answer is none.
                if (info != null && info.refineryHarvester()) {
                    return aiBuildingPlaceFill(worker, type,
                            java.util.List.of(new int[] {startX, startY}), false);
                }
                return aiBuildingPlaceFill(worker, type,
                        java.util.List.of(new int[] {startX, startY}), true);
            }
        }
        return aiBuildingPlaceFill(worker, type,
                java.util.List.of(new int[] {startX, startY}), true);
    }


    /**
     * Where retail Battle.net Edition puts an ordinary AI building.
     *
     * <p>This is the generic dispatcher at {@code 0x43a380} and its shared
     * search at {@code 0x439de0} in the English 2.02 executable. It is quite
     * deliberately not the ChonkCraft flood fill above. BNE first chooses the
     * nearest owned gold depot whose top-left square has the same connectivity
     * cell as the worker's square, then walks expanding square rings on a
     * six-tile lattice. The depot test is runtime type-flag {@code 0x1000},
     * the same flag BNE consults when a hall is created or transformed. BNE's
     * array at {@code 0x4ad650} is a map-component
     * label map, not the PUD's graphic tile array: {@code 0x438510} compares
     * two units by reading those labels. Only the candidate top-left has to
     * retain that component; the ordinary build predicate validates the
     * complete footprint.
     *
     * <p>The native ring begins one lattice step north-west of the anchor.
     * Each side tests three points on the first ring, advances after every
     * test, then backs up that final advance before turning.  The repeated
     * corner is therefore intentional.  Omitting the backup makes the walk
     * drift south-east on every side: Orc 8 then places its first farm at
     * (121,103), while raw BNE action 28 records (125,101).
     */
    int[] aiFindBattleNetBuildingPlace(Unit worker, UnitType type) {
        return aiFindBattleNetBuildingPlace(worker, type, 6);
    }


    /**
     * Where retail BNE puts a farm or pig farm.
     *
     * <p>Building types {@code 0x3a} and {@code 0x3b} dispatch through
     * {@code 0x43a420}, the farm-specific twin of the generic routine above.
     * Its anchor selection is identical, but it passes a two-tile lattice to
     * the shared search instead of the ordinary six-tile lattice.</p>
     */
    int[] aiFindBattleNetFoodPlace(Unit worker, UnitType type) {
        return aiFindBattleNetBuildingPlace(worker, type, 2);
    }


    int[] aiFindBattleNetBuildingPlace(Unit worker, UnitType type, int step) {
        if (worker == null || type == null
                || (!worker.isOnMap()
                    && !world.battleNetDepotReadyDispatching())) {
            return null;
        }

        int anchorX = worker.tileX();
        int anchorY = worker.tileY();
        boolean[] workerCell = world.battleNetConnectivityCell(worker);
        int bestDistance = 0xffff;
        for (Unit candidate : world.units) {
            if (candidate.player() != worker.player() || !candidate.isAlive()
                    || !candidate.isOnMap() || candidate.type() == null
                    || !candidate.type().stores().contains(UnitType.Resource.GOLD)
                    || !workerCell[candidate.tileX()
                            + candidate.tileY() * world.map.width()]) {
                continue;
            }
            int candidateRight = candidate.tileX()
                    + Math.max(1, candidate.type().tileWidth()) - 1;
            int candidateBottom = candidate.tileY()
                    + Math.max(1, candidate.type().tileHeight()) - 1;
            int dx = worker.tileX() < candidate.tileX()
                    ? candidate.tileX() - worker.tileX()
                    : Math.max(0, worker.tileX() - candidateRight);
            int dy = worker.tileY() < candidate.tileY()
                    ? candidate.tileY() - worker.tileY()
                    : Math.max(0, worker.tileY() - candidateBottom);
            int distance = Math.max(dx, dy);
            if (distance < bestDistance) {
                bestDistance = distance;
                anchorX = candidate.tileX();
                anchorY = candidate.tileY();
            }
        }

        return aiFindBattleNetBuildingPlaceAround(
                worker, type, anchorX, anchorY, workerCell, step);
    }


    /**
     * Where retail BNE places a new base hall.
     *
     * <p>The base-hall entry is the one exception to the ordinary build-list
     * dispatcher. Function {@code 0x43a230} walks the gold-mine list, rejects
     * a mine that has fewer than 50 resources or already has any gold depot
     * in its 13-by-13 neighbourhood, and runs the ordinary lattice search
     * around the mine. Only a result within ten tiles of the mine is usable;
     * the closest result wins. There is no fallback beside the worker. That
     * last distinction is visible at BNE startup: XHuman 6 can establish a
     * second base, while XHuman 8's mine is already served by its stronghold
     * and its build scan correctly advances to a lumber mill.</p>
     */
    int[] aiFindBattleNetHallPlace(Unit worker, UnitType type) {
        if (worker == null || type == null
                || (!worker.isOnMap()
                    && !world.battleNetDepotReadyDispatching())) {
            return null;
        }
        boolean[] workerCell = world.battleNetConnectivityCell(worker);
        Unit bestMine = null;
        int bestDistance = 0xffff;
        for (Unit mine : world.units) {
            boolean depotNear = mine.type() != null
                    && mine.type().givesResource() == UnitType.Resource.GOLD
                    && world.battleNetGoldDepotNear(mine);
            if (!mine.isAlive() || !mine.isOnMap() || mine.type() == null
                    || mine.type().givesResource() != UnitType.Resource.GOLD
                    || mine.resourcesHeld() < 50 || depotNear) {
                if (System.getenv("CHONKCRAFT_TRACE_AIBUILD") != null
                        && mine.type() != null
                        && mine.type().givesResource() == UnitType.Resource.GOLD) {
                    System.err.printf("JBNHALL p%d mine=%d at=%d,%d resources=%d"
                                    + " depot=%d skip=1%n",
                            worker.player(), mine.id(), mine.tileX(), mine.tileY(),
                            mine.resourcesHeld(), depotNear ? 1 : 0);
                }
                continue;
            }
            int[] site = aiFindBattleNetBuildingPlaceAround(worker, type,
                    mine.tileX(), mine.tileY(), workerCell, 1);
            if (System.getenv("CHONKCRAFT_TRACE_AIBUILD") != null) {
                System.err.printf("JBNHALL p%d mine=%d at=%d,%d resources=%d"
                                + " depot=0 site=%s%n",
                        worker.player(), mine.id(), mine.tileX(), mine.tileY(),
                        mine.resourcesHeld(), site == null ? "none"
                                : site[0] + "," + site[1]);
            }
            if (site == null) {
                continue;
            }
            int distance = Math.max(Math.abs(site[0] - mine.tileX()),
                    Math.abs(site[1] - mine.tileY()));
            if (distance <= 10 && distance < bestDistance) {
                bestDistance = distance;
                bestMine = mine;
            }
        }
        return bestMine == null ? null : aiFindBattleNetBuildingPlaceAround(
                worker, type, bestMine.tileX(), bestMine.tileY(), workerCell, 1);
    }


    int[] aiFindBattleNetBuildingPlaceAround(Unit worker, UnitType type,
            int anchorX, int anchorY, boolean[] workerCell, int step) {

        if (System.getenv("CHONKCRAFT_TRACE_AIBUILD") != null) {
            System.err.printf("JBNPLACE worker=%d type=%s anchor=%d,%d step=%d%n",
                    worker.id(), type.ident(), anchorX, anchorY, step);
        }

        int maximum = Math.max(Math.max(world.map.width() - anchorX, anchorX),
                Math.max(world.map.width() - anchorY, anchorY)) - 1;
        int sideLength = step * 3;
        if (sideLength >= maximum) {
            return null;
        }

        int x = anchorX - step;
        int y = anchorY - step;
        int[] dx = {1, 0, -1, 0};
        int[] dy = {0, 1, 0, -1};
        while (sideLength < maximum) {
            for (int direction = 0; direction < 4; direction++) {
                for (int travelled = 0; travelled < sideLength; travelled += step) {
                    if (world.map.contains(x, y)) {
                        boolean sameCell = workerCell[x + y * world.map.width()];
                        boolean place = sameCell && world.construction.canPlaceBattleNetBuilding(
                                worker, type, x, y);
                        if (System.getenv("CHONKCRAFT_TRACE_AIBUILD_CANDIDATES") != null) {
                            StringBuilder flags = new StringBuilder();
                            for (int fy = 0; fy < Math.max(1, type.tileHeight()); fy++) {
                                for (int fx = 0; fx < Math.max(1, type.tileWidth()); fx++) {
                                    MapField field = world.map.fieldOrNull(x + fx, y + fy);
                                    flags.append(flags.isEmpty() ? "" : "/")
                                            .append(field == null ? "-"
                                                    : Long.toHexString(field.flags()));
                                }
                            }
                            System.err.printf("JBNPLACECAND worker=%d type=%s at=%d,%d"
                                            + " cell=%d place=%d flags=%s%n",
                                    worker.id(), type.ident(), x, y,
                                    sameCell ? 1 : 0,
                                    place ? 1 : 0, flags);
                        }
                        if (place) {
                            return new int[] {x, y};
                        }
                    }
                    x += dx[direction] * step;
                    y += dy[direction] * step;
                }
                // FUN_00439de0 removes the advance performed after the last
                // candidate before it rotates to the next side.
                x -= dx[direction] * step;
                y -= dy[direction] * step;
            }
            x -= step;
            y -= step;
            sideLength += step * 2;
        }
        return null;
    }


    /**
     * The plain finder: the nearest square the building fits, walking out
     * from the seeds.
     *
     * <p>{@code BuildingPlaceFinder}.
     * A square is a candidate when the building can go there and no enemy
     * stands within eight squares of it -- seen or unseen; the AI does not
     * scout before it builds -- and it is taken when its whole perimeter is
     * free. The fill spreads over ground the worker could walk, units not
     * counting as walls, and a square it cannot cross is still tested: a
     * site does not need a walkable centre, only a reachable rim.
     *
     * <p>{@code AiCheckSurrounding}'s backup answer -- a rim with fewer than
     * five obstacles, remembered in passing -- is dead code upstream:
     * {@code TerrainTraversal::Run} answers false when the queue drains, and
     * the caller turns false into "no place found" whatever the finder
     * noted. Reproduced by not keeping one, and it is why
     * {@code checkSurround} false can never succeed.
     */
    int[] aiBuildingPlaceFill(Unit worker, UnitType type,
            java.util.List<int[]> seeds, boolean checkSurround) {
        long mask = worker.movementMask();
        long blocking = worker.blockingFlags()
                & ~(TileFlag.LAND_UNIT | TileFlag.AIR_UNIT | TileFlag.SEA_UNIT
                        | (type.shoreBuilding() ? TileFlag.COAST_ALLOWED : 0));
        int width = world.map.width();
        boolean[] visited = new boolean[width * world.map.height()];
        int[] queue = new int[visited.length];
        int head = 0;
        int tail = 0;
        for (int[] seed : seeds) {
            if (!world.map.contains(seed[0], seed[1])) {
                continue;
            }
            int at = seed[0] + seed[1] * width;
            if (!visited[at]) {
                visited[at] = true;
                queue[tail++] = at;
            }
        }
        while (head < tail) {
            int at = queue[head++];
            int x = at % width;
            int y = at / width;
            if (checkSurround && world.construction.canPlaceBuilding(worker, type, x, y)
                    && !aiEnemyNear(worker.player(), x, y)
                    && aiSurroundingObstacles(worker, type, x, y) == 0) {
                return new int[] {x, y};
            }
            if (!world.map.isFootprintFree(x, y, 1, 1, mask, blocking)) {
                continue;
            }
            for (int i = 0; i < World.FILL_NEIGHBOURS.length; i += 2) {
                int nx = x + World.FILL_NEIGHBOURS[i];
                int ny = y + World.FILL_NEIGHBOURS[i + 1];
                if (!world.map.contains(nx, ny) || visited[nx + ny * width]) {
                    continue;
                }
                visited[nx + ny * width] = true;
                queue[tail++] = nx + ny * width;
            }
        }
        return null;
    }


    /**
     * The hall finder: flood for a usable mine, then place around the mine.
     *
     * <p>{@code HallPlaceFinder}. The
     * outer fill walks from the start position until it crosses a mine
     * giving the stored resource; a usable one -- no enemy in its
     * five-square skirt, no depot for the resource already there, fewer than
     * two other buildings -- has the building placed by an inner plain fill
     * seeded around the mine's own footprint. When no mine anywhere
     * qualifies, the plain finder from the start position is the answer.
     */
    int[] aiHallPlace(Unit worker, UnitType type, int startX, int startY,
            UnitType.Resource kind) {
        long mask = worker.movementMask();
        long blocking = worker.blockingFlags()
                & ~(TileFlag.LAND_UNIT | TileFlag.AIR_UNIT | TileFlag.SEA_UNIT
                        | (type.shoreBuilding() ? TileFlag.COAST_ALLOWED : 0));
        int width = world.map.width();
        boolean[] visited = new boolean[width * world.map.height()];
        int[] queue = new int[visited.length];
        int head = 0;
        int tail = 0;
        if (world.map.contains(startX, startY)) {
            visited[startX + startY * width] = true;
            queue[tail++] = startX + startY * width;
        }
        while (head < tail) {
            int at = queue[head++];
            int x = at % width;
            int y = at / width;
            Unit mine = world.harvest.resourceUnitOn(x, y, kind);
            if (mine != null && aiUsableMine(mine, worker.player(), kind)) {
                int[] found = aiBuildingPlaceFill(worker, type, aiMineSeeds(mine), true);
                if (found != null) {
                    return found;
                }
            }
            if (!world.map.isFootprintFree(x, y, 1, 1, mask, blocking)) {
                continue;
            }
            for (int i = 0; i < World.FILL_NEIGHBOURS.length; i += 2) {
                int nx = x + World.FILL_NEIGHBOURS[i];
                int ny = y + World.FILL_NEIGHBOURS[i + 1];
                if (!world.map.contains(nx, ny) || visited[nx + ny * width]) {
                    continue;
                }
                visited[nx + ny * width] = true;
                queue[tail++] = nx + ny * width;
            }
        }
        return aiBuildingPlaceFill(worker, type,
                java.util.List.of(new int[] {startX, startY}), true);
    }


    /**
     * The mill finder: flood to the nearest wood, place beside it.
     *
     * <p>{@code LumberMillPlaceFinder}.
     * The outer fill stops at the first square carrying forest, and the
     * inner plain fill is seeded not on the trees but on the square the
     * fill stepped from -- {@code from}, the walkable ground in front of
     * them.
     */
    int[] aiLumberMillPlace(Unit worker, UnitType type, int startX, int startY) {
        long mask = worker.movementMask();
        long blocking = worker.blockingFlags()
                & ~(TileFlag.LAND_UNIT | TileFlag.AIR_UNIT | TileFlag.SEA_UNIT);
        int width = world.map.width();
        int[] from = new int[width * world.map.height()];
        int[] queue = new int[from.length];
        int head = 0;
        int tail = 0;
        if (!world.map.contains(startX, startY)) {
            return null;
        }
        int seed = startX + startY * width;
        from[seed] = seed + 1;
        queue[tail++] = seed;
        while (head < tail) {
            int at = queue[head++];
            int x = at % width;
            int y = at / width;
            if (world.map.field(x, y).isForest()) {
                int parent = from[at] - 1;
                int[] found = aiBuildingPlaceFill(worker, type,
                        java.util.List.of(new int[] {parent % width, parent / width}), true);
                if (found != null) {
                    return found;
                }
            }
            if (!world.map.isFootprintFree(x, y, 1, 1, mask, blocking)) {
                continue;
            }
            for (int i = 0; i < World.FILL_NEIGHBOURS.length; i += 2) {
                int nx = x + World.FILL_NEIGHBOURS[i];
                int ny = y + World.FILL_NEIGHBOURS[i + 1];
                if (!world.map.contains(nx, ny) || from[nx + ny * width] != 0) {
                    continue;
                }
                from[nx + ny * width] = at + 1;
                queue[tail++] = nx + ny * width;
            }
        }
        return null;
    }


    /**
     * The square in front of and around a mine, as the hall finder seeds it.
     *
     * <p>{@code TerrainTraversal::PushUnitPosAndNeighboor}
     * off-by-one and all: the
     * loops run while {@code it != end} with {@code end} exclusive, so the
     * box misses the border row below the mine and the border column to its
     * right. The five gold mines a game usually has make that asymmetry part
     * of where every hall lands.
     */
    java.util.List<int[]> aiMineSeeds(Unit mine) {
        java.util.List<int[]> seeds = new java.util.ArrayList<>();
        int endX = mine.tileX() + Math.max(1, mine.type().tileWidth());
        int endY = mine.tileY() + Math.max(1, mine.type().tileHeight());
        for (int y = mine.tileY() - 1; y != endY; y++) {
            for (int x = mine.tileX() - 1; x != endX; x++) {
                seeds.add(new int[] {x, y});
            }
        }
        return seeds;
    }


    /**
     * Whether a mine is worth building a depot at.
     *
     * <p>{@code HallPlaceFinder::IsAUsableMine}
     * The game everything within five squares
     * of the mine's footprint is examined, an enemy or a depot already
     * storing the resource disqualifies it outright, and two other
     * buildings are read as somebody else's base.
     */
    boolean aiUsableMine(Unit mine, int player, UnitType.Resource kind) {
        int reach = 5;
        int minX = mine.tileX() - reach;
        int minY = mine.tileY() - reach;
        int maxX = mine.tileX() + Math.max(1, mine.type().tileWidth()) - 1 + reach;
        int maxY = mine.tileY() + Math.max(1, mine.type().tileHeight()) - 1 + reach;
        int buildings = 0;
        for (Unit unit : world.units) {
            if (!unit.isAlive() || unit.type() == null || !unit.isOnMap()) {
                continue;
            }
            int right = unit.tileX() + Math.max(1, unit.type().tileWidth()) - 1;
            int bottom = unit.tileY() + Math.max(1, unit.type().tileHeight()) - 1;
            if (right < minX || unit.tileX() > maxX || bottom < minY || unit.tileY() > maxY) {
                continue;
            }
            if (world.isEnemyPlayer(player, unit.player())) {
                return false;
            }
            if (unit.type().stores().contains(kind)) {
                return false;
            }
            if (unit.type().building() && unit.type().givesResource() != kind) {
                if (++buildings == 2) {
                    return false;
                }
            }
        }
        return true;
    }


    /**
     * Whether any enemy, seen or not, stands within eight squares.
     *
     * <p>{@code AiEnemyUnitsInDistance(player, nullptr, pos, 8)}
     * with the visibility test
     * switched off as the building finder's call switches it off: the
     * computer does not scout a site before refusing it.
     */
    boolean aiEnemyNear(int player, int x, int y) {
        int range = 8;
        for (Unit other : world.units) {
            if (!other.isAlive() || !other.isOnMap()) {
                continue;
            }
            if (!world.isEnemyPlayer(player, other.player())) {
                continue;
            }
            int right = other.tileX() + Math.max(1, other.type().tileWidth()) - 1;
            int bottom = other.tileY() + Math.max(1, other.type().tileHeight()) - 1;
            if (right >= x - range && other.tileX() <= x + range
                    && bottom >= y - range && other.tileY() <= y + range) {
                return true;
            }
        }
        return false;
    }


    /**
     * How many separate obstacles ring a proposed site.
     *
     * <p>{@code AiCheckSurrounding},
     * walking the one-square rim clockwise from the top-left corner and
     * counting the blocked-to-free transitions, the wrap from last square
     * back to first included. Nought means nothing touches the rim at all;
     * the building blocks no way past itself.
     */
    int aiSurroundingObstacles(Unit worker, UnitType type, int posX, int posY) {
        int topX = posX - 1;
        int topY = posY - 1;
        int rightX = posX + Math.max(1, type.tileWidth());
        int bottomY = posY + Math.max(1, type.tileHeight());
        int x = topX;
        int y = topY;
        boolean firstFree = aiPosFree(x, y, worker);
        boolean lastFree = firstFree;
        int obstacles = 0;
        for (++x; x < rightX; ++x) {
            boolean free = aiPosFree(x, y, worker);
            if (free && !lastFree) {
                ++obstacles;
            }
            lastFree = free;
        }
        for (; y < bottomY; ++y) {
            boolean free = aiPosFree(x, y, worker);
            if (free && !lastFree) {
                ++obstacles;
            }
            lastFree = free;
        }
        for (; topX < x; --x) {
            boolean free = aiPosFree(x, y, worker);
            if (free && !lastFree) {
                ++obstacles;
            }
            lastFree = free;
        }
        for (; topY < y; --y) {
            boolean free = aiPosFree(x, y, worker);
            if (free && !lastFree) {
                ++obstacles;
            }
            lastFree = free;
        }
        if (firstFree && !lastFree) {
            ++obstacles;
        }
        return obstacles;
    }


    /**
     * Whether a rim square counts as free around a proposed site.
     *
     * <p>{@code IsPosFree}. The worker
     * itself is not an obstacle -- it is about to walk into the frame --
     * and neither is any other unit: only the terrain flags block, walls
     * and rock and wood and standing buildings.
     */
    boolean aiPosFree(int x, int y, Unit worker) {
        if (!world.map.contains(x, y)) {
            return false;
        }
        if (worker.isOnMap()
                && x >= worker.tileX()
                && x < worker.tileX() + Math.max(1, worker.type().tileWidth())
                && y >= worker.tileY()
                && y < worker.tileY() + Math.max(1, worker.type().tileHeight())) {
            return true;
        }
        MapField field = world.map.field(x, y);
        long blocked = TileFlag.UNPASSABLE | TileFlag.WALL | TileFlag.ROCKS
                | TileFlag.FOREST | TileFlag.BUILDING;
        if (field.hasFlag(blocked)) {
            return false;
        }
        long passable = TileFlag.WATER_ALLOWED | TileFlag.COAST_ALLOWED
                | TileFlag.LAND_ALLOWED;
        return field.hasFlag(passable);
    }


    /**
     * Finds a less congested depot and a resource near it for an AI worker.
     *
     * <p>{@code AiGetSuitableDepot} walks every finished depot in the owning
     * player's roster, nearest to the contained worker first. A candidate is
     * useful only when it is not itself overloaded, no enemy is within
     * fifteen tiles of the worker, and {@code UnitFindResource} can reach a
     * mine within fifteen tiles when seeded at that candidate. The chosen
     * depot is only the search centre: WaitInDepot sends the worker straight
     * to the returned mine and the ordinary homeward search chooses its next
     * depot after the load is gathered.
     */
    SuitableDepot aiSuitableDepot(Unit worker, Unit oldDepot,
            ResourceInfo info) {
        List<Unit> depots = new ArrayList<>();
        for (Unit candidate : world.playerUnits(worker.player())) {
            if (candidate.isAlive() && candidate.isOnMap()
                    && candidate.order() != Unit.Order.UNDER_CONSTRUCTION
                    && candidate.type().storesResource(info.resource())) {
                depots.add(candidate);
            }
        }
        if (depots.size() < 2) {
            return null;
        }
        depots.sort(java.util.Comparator.comparingInt(candidate -> candidate.distanceTo(worker)));
        // AiEnemyUnitsInDistance is invariant across the candidate loop: its
        // argument is the worker, not the depot currently being considered.
        if (world.targets.enemyWithin(worker.player(), worker.tileX(), worker.tileY(), 15)) {
            return null;
        }
        for (Unit candidate : depots) {
            if (candidate == oldDepot || world.approximateUnitRefs(candidate) > 15) {
                continue;
            }
            Unit mine = world.harvest.findResourceUnit(worker, info, candidate, candidate,
                    info.resource(), 15);
            if (mine != null) {
                return new SuitableDepot(candidate, mine);
            }
        }
        return null;
    }
}
