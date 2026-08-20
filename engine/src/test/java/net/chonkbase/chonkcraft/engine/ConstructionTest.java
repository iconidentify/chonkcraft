package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.unit.UnitType.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for putting up buildings and training units. */
class ConstructionTest {

    /**
     * The building standing on a square, ignoring anyone standing on it.
     *
     * <p>A builder ends up on its own site rather than beside it: upstream
     * hands the pathfinder the building's whole footprint with a range of
     * nought, so a peasant raising a farm walks onto the farm's ground and is
     * swallowed by it. Asking the
     * square what is on it therefore answers "the peasant" for the cycles
     * between its arrival and the foundation going down, and these fixtures
     * used to read that as the farm.
     */
    private static Unit buildingAt(World world, int x, int y) {
        Unit at = world.unitAt(x, y);
        return at != null && at.type() != null && at.type().building() ? at : null;
    }

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
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

    /** A yielding building program, required by native-paced production. */
    private static AnimationSet building() {
        AnimationSet set = new AnimationSet("building");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.TRAIN,
                Animation.parse("train", List.of("frame 0", "wait 1")));
        return set;
    }

    private static UnitType peasant() {
        UnitType type = new UnitType("unit-peasant");
        type.setTileSize(1, 1);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setDemand(1);
        type.setAnimationSet(walker());
        type.costs().put(Resource.TIME, 1);
        type.costs().put(Resource.GOLD, 400);
        return type;
    }

    /** A farm costing one time unit, so it finishes in 600 cycles. */
    private static UnitType farm() {
        UnitType type = new UnitType("unit-farm");
        type.setTileSize(2, 2);
        type.setHitPoints(400);
        type.setBuilding(true);
        type.setSupply(4);
        type.setAnimationSet(building());
        type.costs().put(Resource.TIME, 1);
        type.costs().put(Resource.GOLD, 500);
        type.costs().put(Resource.WOOD, 250);
        return type;
    }

    /**
     * A farm that takes twenty time units, so it is up for 120 cycles.
     *
     * <p>The ordinary fixture farm costs one, which at a hundred progress a
     * cycle against a goal of six hundred is six cycles from foundation to
     * roof -- no room to shoot at it and watch what happens next.
     */
    private static UnitType slowFarm() {
        UnitType type = farm();
        type.costs().put(Resource.TIME, 20);
        return type;
    }

    private static UnitType barracks() {
        UnitType type = new UnitType("unit-human-barracks");
        type.setTileSize(3, 3);
        type.setHitPoints(800);
        type.setBuilding(true);
        type.costs().put(Resource.TIME, 1);
        type.costs().put(Resource.GOLD, 700);
        return type;
    }

    private static World richWorld(int size) {
        World world = new World(grass(size));
        world.player(0).set(Resource.GOLD, 5000);
        world.player(0).set(Resource.WOOD, 5000);
        return world;
    }

    // ---------------------------------------------------------- construction

    @Test
    @DisplayName("BNE keeps the foundation and exact path goal separate")
    void battleNetBuildGoalUsesTheApproachingFootprintEdge() {
        World world = richWorld(30);
        Unit worker = world.createUnit(peasant(), 0, 12, 9);

        assertTrue(world.orderBuild(worker, farm(), 5, 5));

        assertEquals(5, worker.buildTileX());
        assertEquals(5, worker.buildTileY());
        assertEquals(6, worker.buildGoalX(),
                "a worker east of the farm aims at its east square");
        assertEquals(6, worker.buildGoalY(),
                "a worker south of the farm aims at its south square");
    }

    @Test
    @DisplayName("an inside builder continues to the fixed BNE footprint point")
    void insideBuilderContinuesToTheFixedFootprintPoint() {
        World world = richWorld(40);
        Unit worker = world.createUnit(peasant(), 0, 16, 28);
        UnitType farm = farm();

        assertTrue(world.orderBuild(worker, farm, 16, 15));
        assertEquals(16, worker.buildGoalX());
        assertEquals(16, worker.buildGoalY(),
                "a worker south of a two-tile footprint aims at its south edge");

        // XHuman 6's short free-prefix lands on the other square inside the
        // same footprint.  Being inside is not arrival: native still lays S.
        world.markOccupancy(worker, false);
        worker.setTile(16, 15);
        world.markOccupancy(worker, true);
        worker.setBuildWalked(true);
        worker.setRouteSpent(true);
        worker.setBattleNetGoldFreePrefix(true);
        worker.setWaitCycles(0);

        world.tick();

        assertEquals(0, worker.waitCycles(),
                "the wrong footprint square must not arm the arrival PF wait");
        assertTrue(worker.pathLength() > 0
                        || worker.tileY() == worker.buildGoalY(),
                "the builder must retain or take the final south heading");
        assertEquals(Unit.Order.BUILD, worker.order());
    }

    @Test
    void aWorkerWalksToTheSiteAndPutsUpABuilding() {
        World world = richWorld(30);
        Unit worker = world.createUnit(peasant(), 0, 3, 3);

        assertTrue(world.orderBuild(worker, farm(), 10, 10));
        assertEquals(Unit.Order.BUILD, worker.order());
        // Nothing is paid for the order itself. The cost goes out when the
        // foundation goes down, which is where COrder_Build::StartBuilding
        // does it -- "unit.Player->SubUnitType(type)",
        // and the worker has a walk
        // ahead of it first. This used to be charged at the order and the
        // comment here used to say so.
        assertEquals(5000, world.player(0).get(Resource.GOLD),
                "the order should cost nothing until the building is started");
        assertEquals(5000, world.player(0).get(Resource.WOOD),
                "the order should cost nothing until the building is started");

        Unit farm = null;
        for (int cycle = 0; cycle < 3000 && farm == null; cycle++) {
            world.tick();
            farm = buildingAt(world, 10, 10);
        }
        assertNotNull(farm, "the farm was never started");
        // And paid for now that it has been.
        assertEquals(4500, world.player(0).get(Resource.GOLD),
                "the foundation is down, so it should have been paid for");
        assertEquals(4750, world.player(0).get(Resource.WOOD),
                "the foundation is down, so it should have been paid for");
        assertEquals(Unit.Order.UNDER_CONSTRUCTION, farm.order());
        // The worker is inside and off the map while it works.
        assertFalse(worker.isOnMap());

        for (int cycle = 0; cycle < 1000 && farm.order() == Unit.Order.UNDER_CONSTRUCTION; cycle++) {
            world.tick();
        }
        assertEquals(Unit.Order.STILL, farm.order(), "the farm never finished");
        assertEquals(400, farm.hitPoints(), "a finished building should be at full health");
        // And the worker steps back out beside it.
        assertTrue(worker.isOnMap(), "the builder should reappear");
        assertEquals(Unit.Order.BUILD, worker.order(),
                "the completed build order survives the cycle that released its worker");
        assertTrue(worker.orderFinished(),
                "the released builder should carry a finished-order latch");
        assertEquals(farm.type(), worker.pendingBuild(),
                "the finished order should retain its cost-bearing target through the cycle");

        world.tick();
        assertEquals(Unit.Order.STILL, worker.order(),
                "HandleUnitAction should pop the completed build on the next cycle");
        assertFalse(worker.orderFinished());
        assertEquals(null, worker.pendingBuild());
    }

    @Test
    void theSameWorkerPaysForEachSuccessiveBuilding() {
        World world = richWorld(30);
        Unit worker = world.createUnit(peasant(), 0, 3, 3);
        UnitType farm = farm();

        assertTrue(world.orderBuild(worker, farm, 8, 8));
        Unit first = null;
        for (int cycle = 0; cycle < 3000 && first == null; cycle++) {
            world.tick();
            first = buildingAt(world, 8, 8);
        }
        assertNotNull(first);
        for (int cycle = 0;
                cycle < 1000 && first.order() == Unit.Order.UNDER_CONSTRUCTION;
                cycle++) {
            world.tick();
        }
        assertTrue(worker.isOnMap());
        world.tick();
        assertEquals(Unit.Order.STILL, worker.order(),
                "the first completed build must pop before the next direct order");

        assertTrue(world.orderBuild(worker, farm, 14, 8));
        Unit second = null;
        for (int cycle = 0; cycle < 3000 && second == null; cycle++) {
            world.tick();
            second = buildingAt(world, 14, 8);
        }

        assertNotNull(second);
        assertEquals(4000, world.player(0).get(Resource.GOLD),
                "BuildPaid belongs to one COrder_Build; the completed order"
                        + " must not make the next foundation free");
        assertEquals(4500, world.player(0).get(Resource.WOOD));
    }

    @Test
    void aBuildingGainsHitPointsAsItGoesUp() {
        World world = richWorld(30);
        Unit worker = world.createUnit(peasant(), 0, 9, 9);

        // A longer build than the other tests use, so there is a ramp to
        // watch. 20 time units is 12000 progress at 100 a cycle, so 120
        // cycles: four seconds.
        UnitType slowFarm = farm();
        slowFarm.costs().put(Resource.TIME, 20);
        world.orderBuild(worker, slowFarm, 11, 11);

        Unit farm = null;
        for (int cycle = 0; cycle < 3000 && farm == null; cycle++) {
            world.tick();
            farm = buildingAt(world, 11, 11);
        }
        assertNotNull(farm);

        // A half-built structure is half as tough, which is what makes
        // rushing a construction site worth doing.
        int early = farm.hitPoints();
        for (int cycle = 0; cycle < 40; cycle++) {
            world.tick();
        }
        assertTrue(farm.hitPoints() > early,
                "hit points should climb: " + early + " then " + farm.hitPoints());
        assertTrue(farm.hitPoints() < 400, "it should not be finished yet");
    }

    @Test
    void buildingTakesTheCostUpFront() {
        World world = new World(grass(30));
        world.player(0).set(Resource.GOLD, 400);
        world.player(0).set(Resource.WOOD, 400);
        Unit worker = world.createUnit(peasant(), 0, 3, 3);

        // A farm needs 500 gold and only 400 is banked.
        assertFalse(world.orderBuild(worker, farm(), 10, 10));
        assertEquals(Unit.Order.STILL, worker.order());
        assertEquals(400, world.player(0).get(Resource.GOLD), "nothing should be deducted");
    }

    @Test
    void aBlockedSiteIsRejectedByPlacementButLatchedByTheRetailCommand() {
        World world = richWorld(30);
        world.createUnit(barracks(), 0, 10, 10);
        Unit worker = world.createUnit(peasant(), 0, 3, 3);

        // The barracks origin is 10,10. A farm whose footprint only covers
        // the barracks body is still legal -- Garden of War's blacksmith at
        // 90,6 sits on the hall body at 89,5. The origin tile itself is not.
        assertTrue(world.canPlaceBuilding(farm(), 11, 11),
                "a farm that only overlaps a barracks body was refused");
        assertFalse(world.canPlaceBuilding(farm(), 10, 10),
                "a farm on the barracks origin was accepted");
        assertTrue(world.orderBuild(worker, farm(), 11, 11),
                "retail queues the synchronized build command and checks the site on arrival");
        assertEquals(Unit.Order.BUILD, worker.order());
        // Clear ground beside it is fine.
        assertTrue(world.canPlaceBuilding(farm(), 15, 15));
    }

    @Test
    @DisplayName("a soldier told to mend a hall walks there")
    void aSoldierToldToMendAHallWalksThere() {
        World world = richWorld(30);
        Unit hall = world.createUnit(barracks(), 0, 14, 10);
        Unit soldier = world.createUnit(peasant(), 0, 3, 10);
        soldier.type().setRepairRange(0);

        assertTrue(world.orderRepair(soldier, hall),
                "GiveOrder 27 on a grunt mending a hall was refused");
        assertEquals(Unit.Order.REPAIR, soldier.order(),
                "GiveOrder 27 keeps Repair even when the actor cannot mend");
        int startX = soldier.tileX();
        for (int cycle = 0; cycle < 200 && soldier.tileX() == startX; cycle++) {
            world.tick();
        }
        assertTrue(soldier.tileX() > startX,
                "the soldier stayed at " + soldier.tileX() + "," + soldier.tileY()
                        + " instead of walking toward the hall at 14,10");
    }

    @Test
    @DisplayName("a builder walks onto a barracks body and founds there")
    void aBuilderWalksOntoABarracksBodyAndFoundsThere() {
        World world = richWorld(30);
        world.createUnit(barracks(), 0, 10, 10);
        Unit worker = world.createUnit(peasant(), 0, 8, 10);

        assertTrue(world.orderBuild(worker, farm(), 11, 11),
                "retail queues a farm whose footprint only covers a barracks body");

        Unit farm = null;
        for (int cycle = 0; cycle < 800 && farm == null; cycle++) {
            world.tick();
            for (Unit unit : world.unitsSnapshot()) {
                if (unit.isAlive() && unit.type() != null
                        && "unit-farm".equals(unit.type().ident())
                        && unit.tileX() == 11 && unit.tileY() == 11) {
                    farm = unit;
                    break;
                }
            }
            if (worker.order() == Unit.Order.STILL && worker.pendingBuild() == null) {
                break;
            }
        }
        assertNotNull(farm,
                "the farm never founded on the barracks body; worker "
                        + worker.id() + " at " + worker.tileX() + ","
                        + worker.tileY() + " order=" + worker.order());
    }

    @Test
    void aSiteTakenWhileWalkingRefundsTheCost() {
        World world = richWorld(40);
        Unit worker = world.createUnit(peasant(), 0, 2, 2);
        world.orderBuild(worker, farm(), 20, 20);
        // Not charged yet: the cost goes out when the foundation goes down,
        // and this worker never gets to put one down.
        assertEquals(5000, world.player(0).get(Resource.GOLD));

        // Something else lands on the site before the worker arrives.
        world.createUnit(barracks(), 0, 20, 20);

        for (int cycle = 0; cycle < 3000 && worker.order() == Unit.Order.BUILD; cycle++) {
            world.tick();
        }
        assertEquals(Unit.Order.STILL, worker.order());
        assertEquals(5000, world.player(0).get(Resource.GOLD), "the gold should come back");
        assertEquals(5000, world.player(0).get(Resource.WOOD), "the wood should come back");
    }

    @Test
    void stoppingAWorkerRefundsItsUnstartedBuilding() {
        World world = richWorld(30);
        Unit worker = world.createUnit(peasant(), 0, 3, 3);

        assertTrue(world.orderBuild(worker, farm(), 20, 20));
        world.orderStop(worker);
        for (int cycle = 0; cycle < 10; cycle++) {
            world.tick();
        }

        assertEquals(5000, world.player(0).get(Resource.GOLD));
        assertEquals(5000, world.player(0).get(Resource.WOOD));
        assertEquals(0, count(world, "unit-farm"),
                "an interrupted build order must not become a training job");
    }

    @Test
    void movingAWorkerRefundsItsUnstartedBuilding() {
        World world = richWorld(30);
        Unit worker = world.createUnit(peasant(), 0, 3, 3);

        assertTrue(world.orderBuild(worker, farm(), 20, 20));
        assertTrue(world.orderMove(worker, 6, 3));
        for (int cycle = 0; cycle < 500; cycle++) {
            world.tick();
        }

        assertEquals(5000, world.player(0).get(Resource.GOLD));
        assertEquals(5000, world.player(0).get(Resource.WOOD));
        assertEquals(0, count(world, "unit-farm"));
    }

    @Test
    void anUnreachableBuildingSiteIsRefunded() {
        World world = richWorld(30);
        for (int y = 0; y < 30; y++) {
            world.map().field(15, y).setFlags(TileFlag.LAND_ALLOWED | TileFlag.UNPASSABLE);
        }
        Unit worker = world.createUnit(peasant(), 0, 3, 3);

        assertTrue(world.orderBuild(worker, farm(), 20, 20),
                "the site itself is clear even though no route reaches it");
        // Not on the first refusal: the walk retries an unreachable site
        // every quarter second, ten asks in all, before the job is handed
        // back
        // -- so the worker is still on the order well past the first ask,
        // and the refund arrives only when the tenth has failed.
        for (int cycle = 0; cycle < 10; cycle++) {
            world.tick();
        }
        assertEquals(Unit.Order.BUILD, worker.order(),
                "an unreachable site is retried for a quarter-second beat, not"
                        + " abandoned on the first refusal");
        for (int cycle = 0; cycle < 120 && !worker.orderFinished(); cycle++) {
            world.tick();
        }

        assertTrue(worker.orderFinished(), "the tenth unreachable ask never finished");
        assertEquals(Unit.Order.BUILD, worker.order(),
                "a finished build order keeps its label through the failure cycle");
        world.tick();
        assertEquals(Unit.Order.STILL, worker.order(),
                "the finished build order must pop on the following cycle");
        assertEquals(5000, world.player(0).get(Resource.GOLD));
        assertEquals(5000, world.player(0).get(Resource.WOOD));
        assertEquals(0, count(world, "unit-farm"));
    }

    @Test
    void aWorkerBuildsAtEachQueuedSiteInOrder() {
        World world = richWorld(40);
        UnitType workerType = peasant();
        UnitType farmType = farm();
        Unit worker = world.createUnit(workerType, 0, 3, 3);
        CommandApplier commands = new CommandApplier(world, List.of(workerType, farmType));

        commands.apply(GameCommand.build(0, worker.id(), 1, 8, 8));
        commands.apply(GameCommand.build(0, worker.id(), 1, 20, 20).withQueued(true));

        for (int cycle = 0; cycle < 10_000 && count(world, "unit-farm") < 2; cycle++) {
            world.tick();
        }

        assertEquals(2, count(world, "unit-farm"));
        assertNotNull(world.unitAt(8, 8), "the first site was replaced by the shifted order");
        assertNotNull(world.unitAt(20, 20), "the queued site was never started");
    }

    private static long count(World world, String ident) {
        return world.units().stream()
                .filter(unit -> unit.type() != null && ident.equals(unit.type().ident()))
                .count();
    }

    @Test
    void buildingsCannotBuild() {
        World world = richWorld(30);
        Unit barracks = world.createUnit(barracks(), 0, 5, 5);
        assertFalse(world.orderBuild(barracks, farm(), 15, 15));
    }

    // -------------------------------------------------------------- training

    @Test
    void aBuildingTrainsAUnit() {
        World world = richWorld(30);
        Unit hall = world.createUnit(farm(), 0, 5, 5);
        int before = world.units().size();

        assertTrue(world.orderTrain(hall, peasant()));
        assertEquals(4600, world.player(0).get(Resource.GOLD), "the cost is taken up front");

        for (int cycle = 0; cycle < 1000 && world.units().size() == before; cycle++) {
            world.tick();
        }
        assertEquals(before + 1, world.units().size(), "no unit appeared");

        Unit trained = world.units().get(world.units().size() - 1);
        assertEquals("unit-peasant", trained.type().ident());
        assertEquals(0, trained.player());
        // It appears beside its building, not inside it.
        assertTrue(trained.tileX() < 5 || trained.tileX() > 6
                        || trained.tileY() < 5 || trained.tileY() > 6,
                "the trainee should stand outside the building");
    }

    @Test
    void aSecondTrainingOrderWaitsBehindTheFirst() {
        World world = richWorld(30);
        world.setTrainingQueueEnabled(true);
        Unit hall = world.createUnit(farm(), 0, 5, 5);
        world.recalculateSupply();
        int before = world.units().size();

        assertTrue(world.orderTrain(hall, peasant()));
        assertTrue(world.orderTrain(hall, peasant()));
        assertEquals(4200, world.player(0).get(Resource.GOLD),
                "each accepted job is paid for exactly once");

        for (int cycle = 0; cycle < 3000 && world.units().size() < before + 2; cycle++) {
            world.tick();
        }

        assertEquals(before + 2, world.units().size(),
                "the second order overwrote the first instead of waiting");
    }

    @Test
    void theTrainingQueueRefusesASeventhJobBeforeCharging() {
        World world = richWorld(30);
        world.setTrainingQueueEnabled(true);
        Unit hall = world.createUnit(farm(), 0, 5, 5);
        world.createUnit(farm(), 0, 10, 10);
        world.recalculateSupply();

        for (int job = 0; job < Unit.MAX_TRAINING_JOBS; job++) {
            assertTrue(world.orderTrain(hall, peasant()), "job " + job + " was refused");
        }
        int afterSix = world.player(0).get(Resource.GOLD);

        assertFalse(world.orderTrain(hall, peasant()));
        assertEquals(Unit.MAX_TRAINING_JOBS, hall.trainingJobCount());
        assertEquals(afterSix, world.player(0).get(Resource.GOLD),
                "the refused seventh job was charged");
    }

    @Test
    void trainingIsRefusedWithoutSupply() {
        World world = richWorld(30);
        // A building providing no supply at all.
        UnitType barracksType = barracks();
        Unit barracks = world.createUnit(barracksType, 0, 5, 5);
        world.recalculateSupply();

        assertEquals(0, world.player(0).supply());
        assertFalse(world.orderTrain(barracks, peasant()), "no farms, no peasants");
        assertEquals(5000, world.player(0).get(Resource.GOLD), "nothing should be deducted");
    }

    @Test
    void supplyFromFarmsAllowsTraining() {
        World world = richWorld(30);
        world.createUnit(farm(), 0, 5, 5);
        Unit trainer = world.createUnit(farm(), 0, 10, 10);
        world.recalculateSupply();

        assertEquals(8, world.player(0).supply(), "two farms should give eight supply");
        assertTrue(world.orderTrain(trainer, peasant()));
    }

    @Test
    void aFarmFrameSuppliesFoodOnlyAfterItIsFinished() {
        World world = richWorld(30);
        Unit frame = world.createUnit(farm(), 0, 5, 5);
        frame.setOrder(Unit.Order.UNDER_CONSTRUCTION);

        world.recalculateSupply();
        assertEquals(0, world.player(0).supply(),
                "AssignToPlayer counts a foundation, but UpdateForNewUnit supplies it");

        frame.setOrder(Unit.Order.STILL);
        world.recalculateSupply();
        assertEquals(4, world.player(0).supply(),
                "the completed farm should begin supplying its four food");
    }

    @Test
    void aFullFarmFrameWaitsForTheFinalBuiltPulseBeforeSupplyingFood() {
        World world = richWorld(30);
        Unit frame = world.createUnit(farm(), 0, 5, 5);
        frame.setOrder(Unit.Order.UNDER_CONSTRUCTION);
        frame.setProgressGoal(World.PROGRESS_PER_TIME_UNIT * 2);
        frame.setProgress(World.PROGRESS_PER_TIME_UNIT);
        frame.setHitPoints(frame.type().hitPoints());
        frame.setBattleNetOrderDelay(0);

        world.tick();
        assertEquals(frame.progressGoal(), frame.progress());
        assertEquals(Unit.Order.UNDER_CONSTRUCTION, frame.order(),
                "filling the counter is not retail's final Built pulse");
        assertEquals(0, world.player(0).supply());

        for (int cycle = 0;
                cycle < World.BATTLE_NET_CONSTRUCTION_BOOST_PERIOD - 1; cycle++) {
            world.tick();
            assertEquals(Unit.Order.UNDER_CONSTRUCTION, frame.order());
            assertEquals(0, world.player(0).supply(),
                    "a full frame is not a completed BNE building yet");
        }

        world.tick();
        assertEquals(Unit.Order.STILL, frame.order());
        assertEquals(4, world.player(0).supply(),
                "the final Built pulse should grant the farm's food");
    }

    @Test
    void trainingIsRefusedWithoutGold() {
        World world = new World(grass(30));
        world.player(0).set(Resource.GOLD, 100);
        Unit hall = world.createUnit(farm(), 0, 5, 5);
        assertFalse(world.orderTrain(hall, peasant()));
    }

    // ------------------------------------------------------- the whole loop

    @Test
    void gatherThenBuildThenTrain() {
        // The core Warcraft II loop, end to end.
        World world = new World(grass(40));
        world.player(0).set(Resource.GOLD, 1000);
        world.player(0).set(Resource.WOOD, 1000);

        Unit worker = world.createUnit(peasant(), 0, 3, 3);
        assertTrue(world.orderBuild(worker, farm(), 8, 8), "could not start the farm");

        Unit farm = null;
        for (int cycle = 0; cycle < 5000; cycle++) {
            world.tick();
            if (farm == null) {
                farm = buildingAt(world, 8, 8);
            }
            if (farm != null && farm.order() == Unit.Order.STILL) {
                break;
            }
        }
        assertNotNull(farm);
        assertEquals(Unit.Order.STILL, farm.order(), "the farm never finished");

        world.recalculateSupply();
        assertEquals(4, world.player(0).supply(), "the farm should feed four");

        assertTrue(world.orderTrain(farm, peasant()), "could not train from the farm");
        int before = world.units().size();
        for (int cycle = 0; cycle < 1000 && world.units().size() == before; cycle++) {
            world.tick();
        }
        assertEquals(before + 1, world.units().size(), "the peasant never appeared");
    }

    @Test
    @DisplayName("a builder does not block the site it is standing in")
    void aBuilderIsTakenOffTheMapWhileItsOwnSiteIsChecked() {
        World world = richWorld(40);
        Unit worker = world.createUnit(peasant(), 0, 10, 10);

        // CanBuildUnitType takes the worker off the map before it walks the
        // footprint -- "Remove unit that is building!",
        // UnmarkUnitFieldFlags(*unit) -- and puts it
        // back afterwards. It has to: a builder is aimed at its site's own
        // ground and is standing in it by the time it gets there.
        assertTrue(world.canPlaceBuilding(worker, farm(), 10, 10),
                "the peasant standing at 10,10 refused its own farm at 10,10");
        assertTrue(world.canPlaceBuilding(worker, farm(), 9, 9),
                "the same, for a footprint the peasant is in the corner of");

        // And anybody else standing there does block it, or the check above
        // would pass against an engine that had stopped looking at occupancy.
        Unit bystander = world.createUnit(peasant(), 0, 21, 21);
        assertFalse(world.canPlaceBuilding(worker, farm(), 21, 21),
                "somebody else's square is still somebody else's");
        assertTrue(bystander.isOnMap(), "the fixture's bystander should still be standing");
        // The worker is back on the map with its square marked again.
        assertFalse(world.canPlaceBuilding(bystander, farm(), 10, 10),
                "the builder was left off the map after its own site was checked");
    }

    @Test
    @DisplayName("a builder already standing on its site does not wait to start")
    void aBuilderThatNeverWalkedStartsAtOnce() {
        World world = richWorld(40);
        Unit worker = world.createUnit(peasant(), 0, 10, 10);
        // Its own square, so there is nowhere to walk to.
        assertTrue(world.orderBuild(worker, farm(), 10, 10), "the order was refused");

        // NextPathElement returns PF_WAIT for one reason only: the square the
        // path wants next is refused. A builder that
        // never moved never had one refused, so NewPath answers PF_REACHED on
        // the first ask and StartBuilding runs in that same action. A builder
        // that walked pays ten cycles for the block plus the rest of the step
        // it was in, which is the twenty-seven cycles upstream takes on
        // (2)2-players and (2)x-marks-the-spot.
        Unit farm = null;
        int cycles = 0;
        for (; cycles < 40 && farm == null; cycles++) {
            world.tick();
            farm = buildingAt(world, 10, 10);
        }
        assertNotNull(farm, "the farm was never started");
        assertTrue(cycles <= 2,
                "the peasant was already standing on its site and still took " + cycles
                        + " cycles to put a foundation down. Upstream's peon on"
                        + " (2)mysterious-dragon-isle is told to build at 13,119 while standing"
                        + " at 14,121, inside the footprint, and the great hall is there on the"
                        + " same cycle");
    }

    @Test
    @DisplayName("a construction site that has been shot at stays hurt as it goes up")
    void damageToASiteIsCarriedRatherThanHealedAway() {
        World world = richWorld(40);
        Unit worker = world.createUnit(peasant(), 0, 9, 9);
        world.orderBuild(worker, slowFarm(), 10, 10);

        Unit farm = null;
        for (int cycle = 0; cycle < 3000 && farm == null; cycle++) {
            world.tick();
            farm = buildingAt(world, 10, 10);
        }
        assertNotNull(farm, "the farm was never started");

        for (int cycle = 0; cycle < 30; cycle++) {
            world.tick();
        }
        int sound = farm.hitPoints();
        assertTrue(sound > 1, "the fixture needs a site that has grown, not a foundation");

        // Somebody puts an arrow in it.
        int wound = 20;
        farm.setHitPoints(sound - wound);
        for (int cycle = 0; cycle < 30; cycle++) {
            world.tick();
        }
        assertEquals(Unit.Order.UNDER_CONSTRUCTION, farm.order(),
                "the site must still be going up, or this measures a finished building");

        // COrder_Built::Boost works out the damage the site is carrying,
        // raises the value, and puts the damage back -- "Keep the same level of
        // damage while increasing Value". A site that
        // recomputed its hit points from its progress instead would have healed
        // the wound off by itself somewhere in those hundred and twenty cycles.
        World twin = richWorld(40);
        Unit twinWorker = twin.createUnit(peasant(), 0, 9, 9);
        twin.orderBuild(twinWorker, slowFarm(), 10, 10);
        Unit unhurt = null;
        for (int cycle = 0; cycle < 3000 && unhurt == null; cycle++) {
            twin.tick();
            unhurt = buildingAt(twin, 10, 10);
        }
        assertNotNull(unhurt);
        for (int cycle = 0; cycle < 60; cycle++) {
            twin.tick();
        }
        assertEquals(unhurt.hitPoints() - wound, farm.hitPoints(),
                "the wound should still be exactly " + wound + " hit points deep. The site"
                        + " that was never shot at is on " + unhurt.hitPoints() + " and this"
                        + " one is on " + farm.hitPoints());
    }

    @Test
    @DisplayName("a BNE foundation holds ten percent HP through ten no-op Built cycles")
    void bneFoundationHoldsTenPercentHpThroughTenNoOpBuiltCycles() {
        // retail-xorc-10-idle farm 1426: founded at fixture c22 with HP 40 of
        // 400, then holds 40 through c24 while order_x/timer count no-ops.
        // Java used to Boost on the first cycle after founding (40→41 at c24).
        World world = richWorld(40);
        UnitType farmType = slowFarm();
        farmType.setHitPoints(400);
        Unit worker = world.createUnit(peasant(), 0, 9, 9);
        world.orderBuild(worker, farmType, 10, 10);
        Unit site = null;
        for (int cycle = 0; cycle < 3000 && site == null; cycle++) {
            world.tick();
            site = buildingAt(world, 10, 10);
        }
        assertNotNull(site, "the farm foundation must place");
        assertEquals(40, site.hitPoints(),
                "BNE foundation starts at one tenth of completed hit points");
        // Founding cycle may already have consumed one delay slot when the
        // new site is walked in the same tick; hold through the remaining
        // no-ops so the first Boost cannot land inside the next ten cycles.
        for (int cycle = 0; cycle < 10; cycle++) {
            world.tick();
            assertEquals(40, site.hitPoints(),
                    "no-op Built cycle " + cycle
                            + " must not Boost the foundation yet");
        }
    }

    @Test
    @DisplayName("a BNE farm climbs three hit points on its first construction boost")
    void bneFarmClimbsThreeHitPointsOnItsFirstConstructionBoost() {
        // retail-xorc-10-idle farm 1426: foundation HP 40 through fixture
        // c32, then 40→43 at c33. LegacyEngine's 100-progress drip used to add
        // zero then one; native pays the (full-foundation)/buildTime
        // accumulator (+3, then +4 twelve cycles later).
        World world = richWorld(40);
        UnitType farmType = slowFarm();
        farmType.setHitPoints(400);
        // time 100 matches unit-farm Costs so the accumulator denominator
        // is the sealed retail value.
        farmType.costs().put(Resource.TIME, 100);
        Unit worker = world.createUnit(peasant(), 0, 9, 9);
        world.orderBuild(worker, farmType, 10, 10);
        Unit site = null;
        for (int cycle = 0; cycle < 3000 && site == null; cycle++) {
            world.tick();
            site = buildingAt(world, 10, 10);
        }
        assertNotNull(site, "the farm foundation must place");
        assertEquals(40, site.hitPoints(),
                "BNE foundation starts at one tenth of completed hit points");
        // Ten quiet Built visits after the founding cycle (site acts first
        // on the next tick) leave the first boost on the eleventh action.
        for (int cycle = 0; cycle < 10; cycle++) {
            world.tick();
            assertEquals(40, site.hitPoints(),
                    "no-op Built cycle " + cycle
                            + " must hold foundation hit points");
        }
        world.tick();
        assertEquals(43, site.hitPoints(),
                "first construction boost pays three hit points "
                        + "(XOrc 10 farm 40→43 at fixture 33)");
        // Twelve-cycle cadence: eleven more quiet visits, then +4 → 47.
        for (int cycle = 0; cycle < 11; cycle++) {
            world.tick();
            assertEquals(43, site.hitPoints(),
                    "between-boost quiet cycle " + cycle
                            + " must hold the first climb");
        }
        world.tick();
        assertEquals(47, site.hitPoints(),
                "second construction boost pays four hit points "
                        + "(XOrc 10 farm 43→47 at fixture 45)");
    }
}
