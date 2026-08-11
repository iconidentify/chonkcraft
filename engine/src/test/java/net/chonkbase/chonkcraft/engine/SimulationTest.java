package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.pathfinder.PathFinder;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Test;

/** Tests for the map grid, the pathfinder, and unit movement. */
class SimulationTest {

    /** An open grass map of the given size. */
    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static void wall(GameMap map, int x, int y) {
        map.field(x, y).setFlags(TileFlag.UNPASSABLE);
    }

    /** A one-tile walker. */
    private static UnitType footman() {
        UnitType type = new UnitType("unit-footman");
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        return type;
    }

    private static UnitType townHall() {
        UnitType type = new UnitType("unit-town-hall");
        type.setTileSize(4, 4);
        type.setHitPoints(1200);
        type.setBuilding(true);
        return type;
    }

    /** Runs the world until a unit stops, or gives up. */
    private static int runUntilStill(World world, Unit unit, int maxCycles) {
        for (int cycle = 0; cycle < maxCycles; cycle++) {
            world.tick();
            if (unit.order() == Unit.Order.STILL && !unit.isMoving()) {
                return cycle + 1;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------ directions

    @Test
    void headingTablesMatchTheCppNumbering() {
        // Heading 0 is north and the sequence runs clockwise, as.
        assertEquals(0, Direction.deltaX(0));
        assertEquals(-1, Direction.deltaY(0));
        assertEquals(1, Direction.deltaX(2));
        assertEquals(0, Direction.deltaY(2));
        assertEquals(0, Direction.deltaX(4));
        assertEquals(1, Direction.deltaY(4));
        assertEquals(-1, Direction.deltaX(6));
        assertEquals(0, Direction.deltaY(6));
    }

    @Test
    void deltasRoundTripThroughHeadings() {
        for (int heading = 0; heading < Direction.COUNT; heading++) {
            int dx = Direction.deltaX(heading);
            int dy = Direction.deltaY(heading);
            assertEquals(heading, Direction.fromDelta(dx, dy), "heading " + heading);
        }
        assertEquals(Direction.NONE, Direction.fromDelta(0, 0));
    }

    // ----------------------------------------------------------- pathfinding

    @Test
    void findsAStraightPathAcrossOpenGround() {
        PathFinder finder = new PathFinder(grass(10));
        PathFinder.Path path = finder.find(0, 0, 5, 0, TileFlag.LAND_ALLOWED, 1, 1);

        assertEquals(PathFinder.Result.FOUND, path.result());
        assertEquals(5, path.length());
    }

    @Test
    void prefersDiagonalsWhenTheyAreShorter() {
        PathFinder finder = new PathFinder(grass(10));
        // Five diagonal steps beat five across then five down.
        assertEquals(5, finder.find(0, 0, 5, 5, TileFlag.LAND_ALLOWED, 1, 1).length());
    }

    @Test
    void reportsReachedWhenAlreadyAtTheGoal() {
        PathFinder finder = new PathFinder(grass(10));
        assertEquals(PathFinder.Result.REACHED,
                finder.find(3, 3, 3, 3, TileFlag.LAND_ALLOWED, 1, 1).result());
    }

    @Test
    void routesAroundAWall() {
        GameMap map = grass(10);
        // A wall across the middle with one gap at the bottom.
        for (int y = 0; y < 9; y++) {
            wall(map, 5, y);
        }
        PathFinder.Path path = new PathFinder(map).find(0, 0, 9, 0, TileFlag.LAND_ALLOWED, 1, 1);

        assertEquals(PathFinder.Result.FOUND, path.result());
        // Straight across would be nine steps; going round the gap is longer.
        assertTrue(path.length() > 9, "expected a detour, got " + path.length() + " steps");
    }

    @Test
    void reportsUnreachableWhenWalledIn() {
        GameMap map = grass(10);
        for (int y = 0; y < 10; y++) {
            wall(map, 5, y);
        }
        assertEquals(PathFinder.Result.UNREACHABLE,
                new PathFinder(map).find(0, 0, 9, 0, TileFlag.LAND_ALLOWED, 1, 1).result());
    }

    @Test
    void willCutTheCornerBetweenTwoBlockedSquares() {
        GameMap map = grass(5);
        // Block the two orthogonal neighbours of the diagonal step from 0,0.
        wall(map, 1, 0);
        wall(map, 0, 1);
        PathFinder.Path path = new PathFinder(map).find(0, 0, 1, 1, TileFlag.LAND_ALLOWED, 1, 1);
        // Warcraft II squeezes through. The loop over headings in
        // AStarFindPath tries all eight unconditionally and asks nothing
        // about the two squares either side, so a unit walks the diagonal
        // gap between two trees. This implementation used to refuse it, which is what
        // sent units the long way round a forest.
        assertEquals(PathFinder.Result.FOUND, path.result());
        assertEquals(1, path.length(), "one diagonal step, not a detour");
    }

    @Test
    void aLandPathWillNotCrossWater() {
        GameMap map = grass(10);
        for (int y = 0; y < 10; y++) {
            map.field(5, y).setFlags(TileFlag.WATER_ALLOWED);
        }
        assertEquals(PathFinder.Result.UNREACHABLE,
                new PathFinder(map).find(0, 0, 9, 0, TileFlag.LAND_ALLOWED, 1, 1).result());
        // The same map is crossable by a boat, in the other direction.
        assertEquals(PathFinder.Result.FOUND,
                new PathFinder(map).find(5, 0, 5, 9, TileFlag.WATER_ALLOWED, 1, 1).result());
    }

    // -------------------------------------------------------- unit placement

    @Test
    void placingAUnitMarksItsFootprint() {
        World world = new World(grass(20));
        Unit hall = world.createUnit(townHall(), 0, 4, 4);

        // All sixteen squares of a 4x4 building are marked.
        for (int y = 4; y < 8; y++) {
            for (int x = 4; x < 8; x++) {
                assertTrue(world.map().field(x, y).hasFlag(TileFlag.BUILDING),
                        "square " + x + "," + y + " should be occupied");
            }
        }
        assertFalse(world.map().field(8, 8).hasFlag(TileFlag.BUILDING));
        assertSame(hall, world.unitAt(6, 6));
        assertNull(world.unitAt(9, 9));
    }

    @Test
    void aPathWillNotRunThroughABuilding() {
        World world = new World(grass(20));
        world.createUnit(townHall(), 0, 4, 0);
        // The hall spans columns 4 to 7 of rows 0 to 3.
        PathFinder.Path path = new PathFinder(world.map())
                .find(0, 1, 12, 1, TileFlag.LAND_ALLOWED, 1, 1);
        assertEquals(PathFinder.Result.FOUND, path.result());

        // Not a step-count assertion: with diagonals the detour costs the same
        // number of steps as going straight, since a diagonal advances x too.
        // What matters is that the route never enters the building.
        int x = 0;
        int y = 1;
        for (int i = path.length() - 1; i >= 0; i--) {
            int heading = path.headings()[i];
            x += Direction.deltaX(heading);
            y += Direction.deltaY(heading);
            assertFalse(x >= 4 && x < 8 && y >= 0 && y < 4,
                    "the path enters the hall at " + x + "," + y);
        }
        assertEquals(12, x);
        assertEquals(1, y);
    }

    // -------------------------------------------------------------- movement

    @Test
    void aUnitWalksToItsDestination() {
        World world = new World(grass(20));
        Unit footman = world.createUnit(footman(), 0, 2, 2);

        assertTrue(world.orderMove(footman, 8, 2));
        assertEquals(Unit.Order.MOVE, footman.order());

        int cycles = runUntilStill(world, footman, 2000);
        assertTrue(cycles > 0, "the unit never stopped");
        assertEquals(8, footman.tileX());
        assertEquals(2, footman.tileY());
        assertEquals(0, footman.offsetX());
        assertEquals(0, footman.offsetY());
    }

    @Test
    void aUnitIsLogicallyAtItsNextTileButDrawnBehindIt() {
        // This is the model LegacyEngine uses, and it is what reserves the
        // destination square for the whole of a step.
        World world = new World(grass(20));
        Unit footman = world.createUnit(footman(), 0, 2, 2);
        world.orderMove(footman, 6, 2);
        world.tick();

        assertEquals(3, footman.tileX(), "the unit should already own the next tile");
        assertTrue(footman.offsetX() < 0, "and should be drawn back towards the old one");
        // Its drawn position is still short of the new tile.
        assertTrue(footman.pixelX() < 3 * Unit.TILE_PIXELS);
        // The square it left is free again.
        assertFalse(world.map().field(2, 2).hasFlag(TileFlag.LAND_UNIT));
        assertTrue(world.map().field(3, 2).hasFlag(TileFlag.LAND_UNIT));
    }

    @Test
    void movementFacesTheDirectionOfTravel() {
        World world = new World(grass(20));
        Unit footman = world.createUnit(footman(), 0, 5, 5);

        world.orderMove(footman, 5, 9);
        world.tick();
        assertEquals(Direction.fromDelta(0, 1), footman.heading(), "should face south");
    }

    @Test
    void aUnitFinishesItsCurrentStepBeforeTurning() {
        // LegacyEngine guards the start of a step on the unit not already being
        // between tiles, so a new order does not teleport it off a half-step.
        World world = new World(grass(20));
        Unit footman = world.createUnit(footman(), 0, 5, 5);

        world.orderMove(footman, 5, 9);
        world.tick();
        assertTrue(footman.isMoving(), "should be mid-step");
        int southbound = footman.heading();

        world.orderStop(footman);
        world.orderMove(footman, 1, 5);
        world.tick();
        assertEquals(southbound, footman.heading(), "should still be finishing the southward step");

        // Once the step completes it takes up the new heading.
        int turned = -1;
        for (int cycle = 0; cycle < 100; cycle++) {
            world.tick();
            if (footman.heading() == Direction.fromDelta(-1, 0)) {
                turned = cycle;
                break;
            }
        }
        assertTrue(turned >= 0, "the unit never turned west");
    }

    @Test
    void twoUnitsDoNotOccupyTheSameSquare() {
        World world = new World(grass(20));
        Unit first = world.createUnit(footman(), 0, 2, 2);
        Unit second = world.createUnit(footman(), 0, 6, 2);

        // Send them at each other along the same row.
        world.orderMove(first, 6, 2);
        world.orderMove(second, 2, 2);

        for (int cycle = 0; cycle < 600; cycle++) {
            world.tick();
            assertFalse(first.tileX() == second.tileX() && first.tileY() == second.tileY(),
                    "units collided at cycle " + cycle);
        }
    }

    @Test
    void aUnitOrderedNowhereStaysPut() {
        World world = new World(grass(20));
        Unit footman = world.createUnit(footman(), 0, 3, 3);

        // Taken, then finished on the first action rather than at the click.
        // CommandMove writes the order and asks nothing; COrder_Move::Execute
        // is where DoActionMove answers PF_REACHED and sets Finished.
        assertTrue(world.orderMove(footman, 3, 3));
        world.tick();
        assertEquals(Unit.Order.STILL, footman.order(),
                "the order should have finished on its first action");
        assertEquals(3, footman.tileX());
        assertEquals(3, footman.tileY());
    }

    @Test
    void anUnreachableOrderIsTakenAndGoesNowhere() {
        GameMap map = grass(10);
        for (int y = 0; y < 10; y++) {
            wall(map, 5, y);
        }
        World world = new World(map);
        Unit footman = world.createUnit(footman(), 0, 1, 1);

        // Upstream takes every move order: CommandMove never consults the
        // pathfinder, and COrder_Move::Execute widens its Range on
        // PF_UNREACHABLE until the goal covers ground the unit can stand on --
        // which, for a destination behind a wall, is where it already is. So
        // the order is accepted, the walk gets nowhere, and it ends of its own
        // accord. This used to assert the refusal, which reads better on a
        // click and is not what the game does.
        assertTrue(world.orderMove(footman, 9, 1), "the order is taken, as upstream takes it");
        for (int cycle = 0; cycle < 200 && footman.order() != Unit.Order.STILL; cycle++) {
            world.tick();
        }
        assertEquals(Unit.Order.STILL, footman.order(), "the walk never gave up");
        assertTrue(footman.tileX() < 5, "the footman got through a solid wall");
    }

    @Test
    void buildingsCannotBeOrderedToMove() {
        World world = new World(grass(20));
        Unit hall = world.createUnit(townHall(), 0, 4, 4);
        assertFalse(world.orderMove(hall, 10, 10));
    }

    @Test
    void aFasterUnitArrivesSooner() {
        UnitType slow = footman();
        slow.setSpeed(5);
        UnitType fast = footman();
        fast.setSpeed(20);

        World world = new World(grass(30));
        Unit slowUnit = world.createUnit(slow, 0, 1, 1);
        Unit fastUnit = world.createUnit(fast, 0, 1, 5);
        world.orderMove(slowUnit, 20, 1);
        world.orderMove(fastUnit, 20, 5);

        int slowCycles = -1;
        int fastCycles = -1;
        for (int cycle = 1; cycle <= 4000 && (slowCycles < 0 || fastCycles < 0); cycle++) {
            world.tick();
            if (slowCycles < 0 && slowUnit.order() == Unit.Order.STILL && !slowUnit.isMoving()) {
                slowCycles = cycle;
            }
            if (fastCycles < 0 && fastUnit.order() == Unit.Order.STILL && !fastUnit.isMoving()) {
                fastCycles = cycle;
            }
        }
        assertTrue(fastCycles > 0 && slowCycles > 0, "both units should arrive");
        assertTrue(fastCycles < slowCycles,
                "the faster unit took " + fastCycles + " cycles, the slower " + slowCycles);
    }

    @Test
    void theCycleCounterAdvances() {
        World world = new World(grass(5));
        assertEquals(0, world.cycle());
        world.tick();
        world.tick();
        assertEquals(2, world.cycle());
        // The rate is part of the game's behaviour, not a display choice.
        assertEquals(30, World.CYCLES_PER_SECOND);
    }

    @Test
    void pathsAreDeterministic() {
        // Lockstep multiplayer needs the same route on every machine.
        GameMap map = grass(20);
        wall(map, 10, 5);
        wall(map, 10, 6);
        PathFinder finder = new PathFinder(map);

        PathFinder.Path first = finder.find(1, 5, 18, 6, TileFlag.LAND_ALLOWED, 1, 1);
        for (int repeat = 0; repeat < 5; repeat++) {
            PathFinder.Path again = finder.find(1, 5, 18, 6, TileFlag.LAND_ALLOWED, 1, 1);
            assertEquals(first.length(), again.length());
            for (int i = 0; i < first.length(); i++) {
                assertEquals(first.headings()[i], again.headings()[i], "step " + i);
            }
        }
        assertNotEquals(0, first.length());
    }
}
