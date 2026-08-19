package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.ResourceInfo;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.unit.UnitType.Resource;
import org.junit.jupiter.api.Test;

/**
 * Where a unit ends up when it comes out of something.
 *
 * <p>Upstream has two placements and they are not interchangeable.
 * {@code DropOutOnSide} spirals from a named facing and takes the first free
 * square; {@code DropOutNearest} walks the same ring but keeps whichever
 * square is closest to a point it is given. Training, a builder emerging and a
 * transport unloading use the first; every single resource emergence uses the
 * second, which is why a peasant leaves a mine on the side its Town Hall is on
 * and leaves the hall on the side its mine is on.
 *
 * <p>The implementation had one placement -- row-major from the north-west corner, first
 * free wins -- so trained units appeared at a corner and a worker always left
 * by the same square whatever direction it was going.
 */
class DropOutTest {

    private static GameMap grass(int size) {
        Tileset tileset = new Tileset();
        tileset.setTile(1, new Tileset.Tile(1, TileFlag.LAND_ALLOWED, 1, 0));
        tileset.setSpecial("removed-tree", 1);

        GameMap map = new GameMap(size, size, tileset);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static AnimationSet walker() {
        AnimationSet set = new AnimationSet("walker");
        set.put(AnimationSet.State.STILL, Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move",
                List.of("frame 0", "move 16", "wait 1", "frame 5", "move 16", "wait 1")));
        return set;
    }

    private static UnitType peasant() {
        UnitType type = new UnitType("unit-peasant");
        type.setTileSize(1, 1);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setAnimationSet(walker());

        ResourceInfo gold = new ResourceInfo(Resource.GOLD);
        gold.setCapacity(100);
        gold.setWaitAtResource(4);
        gold.setWaitAtDepot(6);
        type.gathering().put(Resource.GOLD, gold);
        return type;
    }

    private static UnitType footman() {
        UnitType type = new UnitType("unit-footman");
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setAnimationSet(walker());
        return type;
    }

    private static UnitType barracks() {
        UnitType type = new UnitType("unit-human-barracks");
        type.setTileSize(3, 3);
        type.setHitPoints(800);
        type.setBuilding(true);
        return type;
    }

    private static UnitType townHall() {
        UnitType type = new UnitType("unit-town-hall");
        type.setTileSize(4, 4);
        type.setHitPoints(1200);
        type.setBuilding(true);
        type.stores().add(Resource.GOLD);
        return type;
    }

    private static UnitType goldMine() {
        UnitType type = new UnitType("unit-gold-mine");
        type.setTileSize(3, 3);
        type.setHitPoints(50_000);
        type.setBuilding(true);
        // What makes it a mine, said the way the data says it:
        // GivesResource = "gold", CanHarvest = true (scripts/units.legacy-declaration:288).
        // The engine used to decide from the identifier, so a fixture that was
        // merely named after a mine was one.
        type.setGivesResource(UnitType.Resource.GOLD);
        type.setCanHarvest(true);
        return type;
    }

    // -------------------------------------------------------- DropOutOnSide

    /**
     * The game passes {@code LookingW}, and the west leg of
     * the ring runs north to south down the column just outside the footprint.
     * For a three by three barracks at (10,10) that is (9,10) -- the middle of
     * the west face's northern end, and a square that touches the building.
     *
     * <p>Row-major from the north-west gave (9,9), which touches nothing but
     * the corner. Every unit the game has ever trained came out of that
     * diagonal square.
     */
    @Test
    void aTrainedUnitComesOutOfTheWestFaceNotTheCorner() {
        World world = new World(grass(30));
        Unit hall = world.createUnit(barracks(), 0, 10, 10);
        world.player(0).set(Resource.GOLD, 5000);

        assertTrue(world.orderTrain(hall, footman()));
        for (int cycle = 0; cycle < 10_000 && hall.producing() != null; cycle++) {
            world.tick();
        }

        Unit trained = world.units().stream()
                .filter(u -> u.type().ident().equals("unit-footman"))
                .findFirst().orElse(null);
        assertNotNull(trained, "nothing was trained");
        assertEquals(9, trained.tileX(), "west column, one square outside the footprint");
        assertEquals(10, trained.tileY(), "the north end of the west column, not the corner");
    }

    /**
     * The ring grows rather than giving up. With the whole west column walled
     * off the search carries on round the building instead of returning
     * nothing, which is what {@code DropOutOnSide}'s endless loop does.
     */
    @Test
    void aBlockedFaceMovesOnRoundTheRing() {
        World world = new World(grass(30));
        for (int y = 9; y <= 13; y++) {
            world.map().field(9, y).addFlags(TileFlag.UNPASSABLE);
        }
        Unit hall = world.createUnit(barracks(), 0, 10, 10);
        world.player(0).set(Resource.GOLD, 5000);

        assertTrue(world.orderTrain(hall, footman()));
        for (int cycle = 0; cycle < 10_000 && hall.producing() != null; cycle++) {
            world.tick();
        }
        Unit trained = world.units().stream()
                .filter(u -> u.type().ident().equals("unit-footman"))
                .findFirst().orElse(null);
        assertNotNull(trained, "the ring should have grown past the wall");
        // South row, walked west to east: the first free square is (10,13).
        assertEquals(10, trained.tileX());
        assertEquals(13, trained.tileY());
    }

    // ------------------------------------------------------- DropOutNearest

    /** Runs a worker until it comes back out of the mine carrying a load. */
    private static Unit mineOnce(World world, Unit worker, Unit mine) {
        world.orderHarvest(worker, mine.tileX(), mine.tileY());
        boolean wentIn = false;
        for (int cycle = 0; cycle < 4000; cycle++) {
            world.tick();
            if (worker.removed() && worker.worksite() == mine) {
                wentIn = true;
            } else if (wentIn && !worker.removed()) {
                return worker;
            }
        }
        return null;
    }

    /**
     * A worker leaves a mine by the face pointing at the hall it is carrying
     * to. Same mine, same worker, two halls in opposite directions: if the
     * emergence ignored the goal both runs would end on the same square.
     */
    @Test
    void aLoadedWorkerLeavesTheMineOnTheHallsSide() {
        World westward = new World(grass(30));
        westward.createUnit(townHall(), 0, 2, 2);
        Unit westMine = westward.createUnit(goldMine(), 15, 12, 2);
        Unit westWorker = westward.createUnit(peasant(), 0, 9, 3);
        assertNotNull(mineOnce(westward, westWorker, westMine), "never came back out");

        World eastward = new World(grass(30));
        eastward.createUnit(townHall(), 0, 20, 2);
        Unit eastMine = eastward.createUnit(goldMine(), 15, 12, 2);
        Unit eastWorker = eastward.createUnit(peasant(), 0, 9, 3);
        assertNotNull(mineOnce(eastward, eastWorker, eastMine), "never came back out");

        // The mine spans x 12..14. West of it is 11, east of it is 15 -- and
        // the cycle that surfaces the worker also starts the walk home
        // (Execute falls through from SUB_STOP_GATHERING into MoveToDepot,
        // The game ), so the first tick that shows it
        // above ground already shows the first step taken: 10 heading west,
        // 16 heading east.
        assertEquals(10, westWorker.tileX(), "hall to the west: leave by the west face");
        assertEquals(16, eastWorker.tileX(), "hall to the east: leave by the east face");
        assertNotEquals(westWorker.tileX(), eastWorker.tileX(),
                "the side it leaves by has to depend on where it is going");
    }

    @Test
    void anUnreachableDepotStillChoosesTheRememberedDepotsSide() {
        World world = new World(grass(40));
        Unit mine = world.createUnit(goldMine(), World.NEUTRAL_PLAYER, 12, 12);
        Unit hall = world.createUnit(townHall(), 0, 12, 22);
        Unit worker = world.createUnit(peasant(), 0, 16, 13);
        mine.setResourcesHeld(10_000);

        // FindDeposit ignores a depot under construction, while
        // StopGathering's compatibility fallback only asks whether the
        // order's previously remembered Depot is still alive. This isolates
        // the odd but observable path: surface towards the old hall, then
        // finish because there is no currently usable hall to walk to.
        hall.setOrder(Unit.Order.UNDER_CONSTRUCTION);
        hall.setProgressGoal(10_000);
        world.restoreContained(worker, mine, false, Unit.Order.HARVEST);
        world.restoreHarvestState(worker, mine, mine.tileX(), mine.tileY(),
                false, 0);
        worker.setCarrying(Resource.GOLD);
        worker.setHeldResource(Resource.GOLD);
        worker.setCarried(100);
        worker.setResourceDepot(hall);

        world.tick();

        assertFalse(worker.removed(), "the full worker never surfaced");
        assertEquals(15, worker.tileY(),
                "the failed fresh depot search forgot COrder_Resource::Depot and used the"
                        + " west-side fallback instead of the old hall to the south");
        assertTrue(worker.tileX() >= 12 && worker.tileX() <= 15,
                "the worker did not emerge along the mine's south face");
    }

    @Test
    void aBlockedPreferredResourceFaceUsesTheFallbackFacesFirstFreeSquare() {
        World world = new World(grass(30));
        Unit hall = world.createUnit(townHall(), 0, 2, 2);
        Unit mine = world.createUnit(goldMine(), World.NEUTRAL_PLAYER, 7, 8);
        Unit worker = world.createUnit(peasant(), 0, 15, 15);
        assertNotNull(hall);
        assertNotNull(mine);
        assertNotNull(worker);

        // The hall selects the mine's west face. Block all of west and the
        // following south face, leaving the east face open. Its traversal
        // begins at the bottom (10,10), while a second distance score against
        // the north-west hall would incorrectly prefer the top at (10,8).
        for (int y = 8; y <= 10; y++) {
            world.map().field(6, y).setFlags(TileFlag.WATER_ALLOWED);
        }
        for (int x = 6; x <= 10; x++) {
            world.map().field(x, 11).setFlags(TileFlag.WATER_ALLOWED);
        }

        int[] spot = world.placeResourceBeside(worker, mine, hall);

        assertNotNull(spot);
        assertEquals(10, spot[0]);
        assertEquals(10, spot[1],
                "fallback faces retain DropOutOnSide traversal order");
    }

    @Test
    void aShallowSouthEastResourceVectorKeepsTheNativeEastFace() {
        World world = new World(grass(100));
        Unit hall = world.createUnit(townHall(), 0, 53, 66);
        Unit mine = world.createUnit(goldMine(), World.NEUTRAL_PLAYER, 60, 70);
        Unit worker = world.createUnit(peasant(), 0, 40, 40);
        assertNotNull(hall);
        assertNotNull(mine);
        assertNotNull(worker);

        int[] spot = world.placeResourceBesidePoint(worker, hall,
                mine.tileX(), mine.tileY());

        assertNotNull(spot);
        assertEquals(57, spot[0]);
        assertEquals(69, spot[1],
                "the unrounded (+7,+4) angle is east even though its sprite faces SE");
    }

    /**
     * The other half of the round trip, {@code :1145}. The worker is <em>removed into</em> the depot for the
     * wait-at-depot pause -- it is off the map, not standing outside -- and
     * comes back out of the face nearest the mine it is returning to.
     *
     * <p>The implementation banked in place and never went inside, so a returning
     * peasant stood wherever its path happened to end and then set off from
     * there. With a hall at (2,2) and a mine to the south-east, that is two
     * sides of a four by four building's worth of walking on every trip, which
     * is exactly what the player reported seeing.
     */
    @Test
    void aWorkerGoesInsideTheDepotAndComesOutFacingItsMine() {
        World world = new World(grass(40));
        Unit hall = world.createUnit(townHall(), 0, 2, 2);
        Unit mine = world.createUnit(goldMine(), 15, 20, 10);
        Unit worker = world.createUnit(peasant(), 0, 8, 8);
        world.orderHarvest(worker, mine.tileX(), mine.tileY());

        boolean wentInside = false;
        int[] cameOutAt = null;
        for (int cycle = 0; cycle < 8000; cycle++) {
            world.tick();
            if (worker.removed() && worker.worksite() == hall) {
                wentInside = true;
            } else if (wentInside && !worker.removed()) {
                cameOutAt = new int[] {worker.tileX(), worker.tileY()};
                break;
            }
        }

        assertTrue(wentInside, "the worker never went into the hall to unload");
        assertEquals(100, world.player(0).get(Resource.GOLD), "the load should have been banked");
        assertNotNull(cameOutAt, "the worker never came back out of the hall");
        // The hall spans (2..5, 2..5). The mine's exact order point (20,10)
        // remains inside retail's east angular band, so the worker takes the
        // closest square on that face rather than the diagonal corner.
        assertEquals(6, cameOutAt[0], "come out of the side the mine is on");
        assertEquals(5, cameOutAt[1], "come out of the side the mine is on");
    }

    @Test
    void aDepotVisitWithNoRememberedMineFindsOneBeforeDroppingOut() {
        World world = new World(grass(40));
        Unit hall = world.createUnit(townHall(), 0, 10, 10);
        Unit mine = world.createUnit(goldMine(), World.NEUTRAL_PLAYER, 10, 20);
        Unit worker = world.createUnit(peasant(), 0, 14, 11);
        mine.setResourcesHeld(10_000);

        world.restoreContained(worker, hall, false, Unit.Order.HARVEST);
        world.restoreHarvestState(worker, null, -1, -1, true, 0);
        worker.setCarrying(Resource.GOLD);

        world.tick();

        assertFalse(worker.removed(), "the worker never left the depot");
        assertEquals(mine, worker.resourceUnit(),
                "WaitInDepot did not run UnitFindResource after its weak Mine vanished");
        assertEquals(14, worker.tileY(),
                "the replacement mine was found only after the worker had already dropped"
                        + " out of the hall's west face");
        assertTrue(worker.tileX() >= 10 && worker.tileX() <= 13,
                "the worker did not emerge on the south face towards the mine");
    }

    /**
     * While it is inside the depot the worker is off the map: it does not hold
     * a square and it cannot be shot. That is what {@code unit.Remove(&goal)}
     * buys, and it is why a busy hall looks empty.
     */
    @Test
    void aWorkerInsideTheDepotHoldsNoGround() {
        World world = new World(grass(40));
        Unit hall = world.createUnit(townHall(), 0, 2, 2);
        Unit mine = world.createUnit(goldMine(), 15, 20, 10);
        Unit worker = world.createUnit(peasant(), 0, 8, 8);
        world.orderHarvest(worker, mine.tileX(), mine.tileY());

        for (int cycle = 0; cycle < 8000; cycle++) {
            world.tick();
            if (worker.removed() && worker.worksite() == hall) {
                assertFalse(worker.isOnMap(), "a unit inside a building is not on the map");
                // It stands where its container stands, as UnitInXY leaves it.
                assertEquals(hall.tileX(), worker.tileX());
                assertEquals(hall.tileY(), worker.tileY());
                return;
            }
        }
        throw new AssertionError("the worker never went into the hall");
    }
}
