package net.chonkbase.chonkcraft.engine.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.unit.UnitType.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A computer player that has money and a build order actually spends it.
 *
 * <p>Two faults kept a scripted AI standing on a full bank, and both are the
 * same shape: a manager that gave up rather than skipping.
 *
 * <p>The first is the queue. {@code AiPlayer.resourceManager} read one request
 * -- the head -- and returned if it could not be started, so anything the AI
 * could not begin froze everything behind it however affordable. Measured over
 * the campaign: {@code hum-exp-6a} sat on 2000 gold and a rising pile of wood
 * for five simulated minutes behind a great hall its site search could not
 * place, with three forces it could never complete; {@code orc-14-green} sat on
 * 37,856 gold behind a request for a peasant it had no town hall to train.
 * Upstream's {@code AiCheckingWork} walks the whole list and skips what it
 * cannot start.
 *
 * <p>The second is food. {@code World.orderTrain} refuses on supply, which is
 * right, and nothing then asked for a farm: the only code that ever did was the
 * built-in plan, and the plan is switched off the moment a script is attached,
 * which is every campaign AI in the game. There was no port of
 * {@code AiRequestSupply} at all. A supply capped personality asked for the
 * same peon every second for the rest of the mission and was refused every
 * second, and from outside it looked like an AI that had decided to stop.
 *
 * <p>Nothing here checks that a value was parsed. Each test drives the AI's own
 * once-a-second thinking through {@code World.tick} and looks at what came out:
 * whether a farm went up, whether the gold went down.
 */
class AiBuildQueueTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static AnimationSet worker() {
        AnimationSet set = new AnimationSet("w");
        set.put(AnimationSet.State.STILL, Animation.parse("s", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("m",
                List.of("frame 0", "move 16", "wait 1", "frame 5", "move 16", "wait 1")));
        set.put(AnimationSet.State.DEATH, Animation.parse("d", List.of("frame 50", "wait 1")));
        return set;
    }

    private static UnitType peasant() {
        UnitType type = new UnitType("unit-peasant");
        type.setTileSize(1, 1);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setDemand(1);
        type.setSightRange(4);
        type.setAnimationSet(worker());
        type.costs().put(Resource.TIME, 1);
        type.costs().put(Resource.GOLD, 400);
        type.gathering().put(Resource.GOLD,
                new net.chonkbase.chonkcraft.engine.unit.ResourceInfo(Resource.GOLD));
        return type;
    }

    private static UnitType townHall() {
        UnitType type = new UnitType("unit-town-hall");
        type.setTileSize(4, 4);
        type.setHitPoints(1200);
        type.setBuilding(true);
        type.setSupply(1);
        type.setSightRange(4);
        type.stores().add(Resource.GOLD);
        type.costs().put(Resource.TIME, 1);
        type.costs().put(Resource.GOLD, 1200);
        return type;
    }

    private static UnitType trainingHall(String ident) {
        UnitType type = townHall();
        UnitType named = new UnitType(ident);
        named.setTileSize(type.tileWidth(), type.tileHeight());
        named.setHitPoints(type.hitPoints());
        named.setBuilding(true);
        named.setSupply(5);
        named.costs().putAll(type.costs());
        return named;
    }

    private static UnitType farm() {
        UnitType type = new UnitType("unit-farm");
        type.setTileSize(2, 2);
        type.setHitPoints(400);
        type.setBuilding(true);
        type.setSupply(4);
        // Slow on purpose, near the real farm's hundred. The timing tests
        // below read "no builder yet" at a cycle boundary, and a farm that
        // finishes in six cycles lets a wrongly-early dispatch complete and
        // vanish before the boundary is read -- the assert passes without
        // measuring anything.
        type.costs().put(Resource.TIME, 200);
        type.costs().put(Resource.GOLD, 500);
        return type;
    }

    /** A building nothing in these fixtures can train from. */
    private static UnitType shipyard() {
        UnitType type = new UnitType("unit-human-shipyard");
        type.setTileSize(3, 3);
        type.setHitPoints(1100);
        type.setBuilding(true);
        type.costs().put(Resource.TIME, 1);
        type.costs().put(Resource.GOLD, 800);
        return type;
    }

    private static Player[] onePlayer() {
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i == 0 ? PudMap.PlayerType.COMPUTER : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        return players;
    }

    private static int count(World world, String ident) {
        int found = 0;
        for (Unit unit : world.units()) {
            if (unit.isAlive() && unit.type().ident().equals(ident)) {
                found++;
            }
        }
        return found;
    }

    @Test
    @DisplayName("a harvester carrying its trip home is not a free AI builder")
    void aReturningHarvesterHasAlreadyStartedGathering() {
        World world = new World(grass(40), onePlayer());
        Unit worker = world.createUnit(peasant(), 0, 10, 10);
        worker.setOrder(Unit.Order.HARVEST);
        worker.setReturningToDepot(true);

        assertTrue(world.isAlreadyWorking(worker),
                "COrder_Resource::IsGatheringStarted remains true through"
                        + " SUB_MOVE_TO_DEPOT, but the returning worker was offered to"
                        + " AiBuildBuilding as free");
    }

    @Test
    @DisplayName("a computer player with no food left builds a farm instead of asking forever")
    void aSupplyCappedAiBuildsAFarm() {
        World world = new World(grass(40), onePlayer());
        world.player(0).set(Resource.GOLD, 5000);
        world.createUnit(townHall(), 0, 2, 2);
        world.createUnit(peasant(), 0, 10, 10);
        // A farm it already owns, so it knows what its race feeds itself with,
        // which is what AiHelpers tells upstream.
        world.createUnit(farm(), 0, 20, 20);
        // Eight peasants standing about, against a supply of one hall and one
        // farm: five. Nothing more can be trained until a farm goes up.
        for (int i = 0; i < 8; i++) {
            world.createUnit(peasant(), 0, 12 + i, 30);
        }
        world.recalculateSupply();

        AiPlayer ai = world.enableAi(0);
        // Scripted, in the sense that matters here: the built-in plan is off,
        // which is what setScript does for all fifty-two campaign missions.
        ai.setUsePlan(false);
        ai.need(peasant(), 1);

        assertTrue(world.player(0).demand() > world.player(0).supply(),
                "the fixture must start over its supply or it proves nothing: demand "
                        + world.player(0).demand() + " against supply " + world.player(0).supply());
        int farmsAtStart = count(world, "unit-farm");
        assertEquals(1, farmsAtStart, "the fixture starts with exactly one farm");

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 90; cycle++) {
            world.tick();
        }

        assertTrue(count(world, "unit-farm") > farmsAtStart,
                "a supply-capped AI never asked for a farm: it went on requesting a peasant"
                        + " that orderTrain refuses, which is what left hum-07 doing nothing"
                        + " for five simulated minutes. There is no port of AiRequestSupply.");
    }

    @Test
    @DisplayName("the AI knows a registered farm even when the map starts without one")
    void aSupplyBuildingDoesNotHaveToBePreplacedOnTheMap() {
        World world = new World(grass(40), onePlayer());
        UnitType hall = townHall();
        UnitType food = farm();
        UnitType workerType = peasant();
        world.setUnitTypes(Map.of(
                hall.ident(), hall,
                food.ident(), food,
                workerType.ident(), workerType));
        world.player(0).set(Resource.GOLD, 800);
        world.createUnit(hall, 0, 2, 2);
        Unit builder = world.createUnit(workerType, 0, 10, 10);
        world.recalculateSupply();

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        ai.need(workerType, 1);

        assertEquals(world.player(0).supply(), world.player(0).demand(),
                "the requested worker must raise the hard-cap supply flag");
        assertEquals(0, count(world, "unit-farm"),
                "the regression requires that no farm is already on the map");

        ai.think(world);
        ai.think(world);

        assertEquals(food, builder.pendingBuild(),
                "AiHelpers discovers supply types from the registry; choosing only among"
                        + " placed buildings cached the unaffordable town hall and forgot"
                        + " that a farm could be built");
    }

    @Test
    @DisplayName("one request it cannot start does not freeze the ones behind it")
    void aRequestThatCannotBeStartedIsSkipped() {
        World world = new World(grass(40), onePlayer());
        world.player(0).set(Resource.GOLD, 20000);
        world.createUnit(townHall(), 0, 2, 2);
        world.createUnit(peasant(), 0, 10, 10);
        world.recalculateSupply();

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        // A shipyard first. Nothing in this fixture builds on water and the
        // request is affordable, so it will be attempted and will fail; what
        // matters is the farm behind it.
        UnitType unbuildable = shipyard();
        unbuildable.setShoreBuilding(true);
        unbuildable.costs().put(Resource.OIL, 100_000);
        ai.need(unbuildable, 1);
        ai.need(farm(), 1);

        assertFalse(world.player(0).canAfford(unbuildable.costs()),
                "the head of the queue must be one the AI cannot start, or this proves nothing");

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 90; cycle++) {
            world.tick();
        }

        assertEquals(1, count(world, "unit-farm"),
                "the farm behind an unstartable request was never built: the resource manager"
                        + " read the head of the queue and returned. That is orc-14-green sitting"
                        + " on 37,856 gold and hum-exp-6a on 2000 with three finished forces.");
        assertTrue(world.player(0).get(Resource.GOLD) < 20000,
                "and nothing was ever paid for");
    }

    /**
     * The farm starts in the same thought that noticed the shortage.
     *
     * <p>{@code AiRequestSupply} does not queue the farm to wait its turn: the
     * affordable candidate goes straight to {@code AiMakeUnit} and enters the
     * queue already made. level08h's player 0 flags
     * food at its thought on cycle 37 -- room for the peon its hall is already
     * training, none for the one it wants next -- and its pig farm's builder
     * is walking by 40; a port that queued the farm and looked at it a thought
     * later ran one second behind upstream with every draw after shifted.
     */
    @Test
    @DisplayName("a farm flagged by a training hall starts the same second, not the next")
    void aFarmFlaggedMidTrainingStartsTheSameThought() {
        World world = new World(grass(40), onePlayer());
        world.player(0).set(Resource.GOLD, 5000);
        world.player(0).set(Resource.WOOD, 5000);
        world.createUnit(townHall(), 0, 2, 2);
        world.createUnit(farm(), 0, 20, 20);
        // Four standing peasants against supply five: room for exactly one
        // more. The first thought trains it; the second finds the hall busy,
        // counts what it trains, and is short.
        UnitType worker = peasant();
        worker.costs().put(Resource.TIME, 200);
        for (int i = 0; i < 4; i++) {
            world.createUnit(worker, 0, 12 + i, 30);
        }
        world.recalculateSupply();

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        ai.need(worker, 2);

        assertEquals(world.player(0).supply(), world.player(0).demand() + 1,
                "the fixture must have room for exactly one more, or the second thought"
                        + " is not the training-subtraction case");

        // Through the first thought: the hall starts training, no farm yet.
        while (world.cycle() < 36) {
            world.tick();
        }
        assertEquals(0, buildersBuilding(world),
                "no farm may start before the shortage is even flagged, or the fixture"
                        + " is not measuring the flagging thought");

        // The second thought, and the two cycles a fresh order needs to show.
        while (world.cycle() < 40) {
            world.tick();
        }
        assertTrue(buildersBuilding(world) > 0,
                "the farm flagged at the second thought must have its builder walking"
                        + " within that same second: upstream's AiRequestSupply hands the"
                        + " candidate straight to AiMakeUnit, it does not queue it for the"
                        + " next thought");
    }

    /**
     * The hard-capped entry waits a thought; the flag carries it.
     *
     * <p>{@code CheckLimits}' InsufficientSupply arm reads bare demand against
     * bare supply and its skip also skips the same-thought farm request, so
     * the flag raised beside it is consumed by the next walk's opening retry
     * level08h's player 1 sits at
     * the cap at its cycle-8 thought and upstream's farm starts at 38, one
     * thought later -- while player 0, with room for one, gets its farm at 37
     * in the flagging thought itself.
     */
    @Test
    @DisplayName("a hard-capped player's farm waits for the next thought")
    void aHardCappedPlayersFarmComesOneThoughtLater() {
        World world = new World(grass(40), onePlayer());
        world.player(0).set(Resource.GOLD, 5000);
        world.player(0).set(Resource.WOOD, 5000);
        world.createUnit(townHall(), 0, 2, 2);
        world.createUnit(farm(), 0, 20, 20);
        // Five standing peasants against supply five: no room at all.
        for (int i = 0; i < 5; i++) {
            world.createUnit(peasant(), 0, 12 + i, 30);
        }
        world.recalculateSupply();

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        ai.need(peasant(), 1);

        assertEquals(world.player(0).supply(), world.player(0).demand(),
                "the fixture must sit exactly at the cap, or the limits arm is not"
                        + " the thing refusing the peasant");

        // Through the first thought and its aftermath: flagged, not built.
        while (world.cycle() < 36) {
            world.tick();
        }
        assertEquals(0, buildersBuilding(world),
                "a hard-capped entry is skipped at the limits check before the farm"
                        + " request fires, so no builder may walk in the flagging thought:"
                        + " level08h's player 1 flags at 8 and builds at 38");

        while (world.cycle() < 40) {
            world.tick();
        }
        assertTrue(buildersBuilding(world) > 0,
                "the flag raised in the first thought must be consumed by the next"
                        + " walk's opening retry and the farm begun there");
    }

    @Test
    @DisplayName("a walking farm satisfies the supply ledger in its dispatch thought")
    void aWalkingFarmDoesNotRaiseANewSupplyFlag() {
        World world = new World(grass(40), onePlayer());
        world.player(0).set(Resource.GOLD, 5000);
        world.player(0).set(Resource.WOOD, 5000);
        world.createUnit(townHall(), 0, 2, 2);
        UnitType food = farm();
        world.createUnit(food, 0, 20, 20);
        UnitType workerType = peasant();
        Unit builder = null;
        for (int i = 0; i < 5; i++) {
            Unit worker = world.createUnit(workerType, 0, 8 + i, 30);
            if (builder == null) {
                builder = worker;
            }
        }
        world.recalculateSupply();
        assertEquals(world.player(0).supply(), world.player(0).demand());
        assertTrue(world.orderBuild(builder, food, 30, 5));

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        ai.need(workerType, 1);
        ai.think(world);
        ai.think(world);

        assertEquals(1, buildersBuilding(world),
                "the walking farm was read through the training slot instead of"
                        + " pendingBuild, so the first queue walk raised NeedSupply"
                        + " and the second dispatched a duplicate farm");
    }

    @Test
    @DisplayName("trainer types follow the button vector before roster order")
    void theButtonVectorChoosesTheTrainerType() {
        World world = new World(grass(40), onePlayer());
        UnitType fortressType = trainingHall("unit-fortress");
        UnitType hallType = trainingHall("unit-great-hall");
        Unit fortress = world.createUnit(fortressType, 0, 2, 2);
        Unit hall = world.createUnit(hallType, 0, 12, 2);
        UnitType worker = peasant();
        java.util.LinkedHashSet<String> order = new java.util.LinkedHashSet<>();
        order.add("unit-great-hall");
        order.add("unit-stronghold");
        order.add("unit-fortress");
        java.util.Map<String, java.util.Set<String>> trainers =
                new java.util.LinkedHashMap<>();
        trainers.put(worker.ident(), order);
        world.setTrainers(trainers);
        world.recalculateSupply();
        world.player(0).set(Resource.GOLD, 1000);

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        ai.need(worker, 1);
        ai.think(world);

        assertEquals(worker, hall.producing(),
                "AiHelpers.Train lists the great hall first, so roster creation"
                        + " order must not hand the peon to the fortress");
        assertEquals(null, fortress.producing());
    }

    /**
     * A farm in advance: food exactly full, nothing refused yet.
     *
     * <p>{@code AiResourceManager}'s own tail: "Look if we can build a farm in
     * advance" -- supply equal to demand with no shortage flagged asks for the
     * next farm before any request has been refused for food
     *
     */
    @Test
    @DisplayName("food exactly full asks for the next farm before anything is refused")
    void aFullTableAsksForTheNextFarmInAdvance() {
        World world = new World(grass(40), onePlayer());
        world.player(0).set(Resource.GOLD, 5000);
        world.player(0).set(Resource.WOOD, 5000);
        world.createUnit(townHall(), 0, 2, 2);
        UnitType food = farm();
        world.createUnit(food, 0, 20, 20);
        for (int i = 0; i < 5; i++) {
            world.createUnit(peasant(), 0, 12 + i, 30);
        }
        world.recalculateSupply();

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        // No request at all: the advance farm is not an answer to a refusal.

        assertEquals(world.player(0).supply(), world.player(0).demand(),
                "the fixture must sit exactly at the cap, or the advance rule"
                        + " never fires");

        while (world.cycle() < 10) {
            world.tick();
        }
        assertTrue(buildersBuilding(world) > 0,
                "an AI whose food is exactly full must start its next farm in its"
                        + " first thought with nothing queued at all -- AiResourceManager's"
                        + " farm-in-advance tail, not a reaction to any refusal");
        assertEquals(1, ai.requests().getOrDefault(food, 0),
                "AiRequestSupply inserts the successfully started farm at the head"
                        + " of UnitTypeBuilt with Want=1 and Made=1, where its costs"
                        + " continue billing the needed-resource mask until completion");
    }

    /** How many workers are under a build order or inside a rising frame. */
    private static int buildersBuilding(World world) {
        int found = 0;
        for (Unit unit : world.units()) {
            if (!unit.type().building()
                    && (unit.order() == Unit.Order.BUILD
                            || unit.buildLatchedFrom() != null)) {
                found++;
            }
        }
        return found;
    }

    @Test
    @DisplayName("a building goes up near the town hall when the only worker has wandered off")
    void aSiteIsFoundRoundTheBaseAndNotOnlyRoundTheWorker() {
        // Ground the worker is standing in the middle of will not take a
        // building, so the old search -- radius two to eleven around the first
        // gatherer and nowhere else -- has nothing to offer. The base does.
        //
        // NO_BUILDING and not UNPASSABLE, which is the difference between
        // "cannot build here" and "cannot be here". This fixture used to make
        // the ground unpassable, which walled the worker onto its single
        // square: it could never reach the base, so the only way a farm could
        // appear was the free-building bug -- a worker whose pending build was
        // held in the same field a barracks uses for its training job, with a
        // goal of zero meaning already finished. That bug is fixed, and the
        // test was resting on it. The site search is still the thing under
        // test; the worker now has to walk to the site it finds.
        World world = new World(grass(60), onePlayer());
        world.player(0).set(Resource.GOLD, 20000);
        world.createUnit(townHall(), 0, 2, 2);
        Unit worker = world.createUnit(peasant(), 0, 45, 45);
        for (int y = 30; y < 60; y++) {
            for (int x = 30; x < 60; x++) {
                world.map().field(x, y).setFlags(TileFlag.LAND_ALLOWED | TileFlag.NO_BUILDING);
            }
        }
        world.recalculateSupply();

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        ai.need(farm(), 1);

        assertFalse(world.canPlaceBuilding(farm(), worker.tileX() + 5, worker.tileY() + 5),
                "the ground round the worker must refuse a farm or this proves nothing");

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 120; cycle++) {
            world.tick();
        }

        assertEquals(1, count(world, "unit-farm"),
                "the site search never left the worker's own box, so a computer player whose"
                        + " one peasant had wandered onto bad ground could not put up a building"
                        + " anywhere on the map");
    }
}
