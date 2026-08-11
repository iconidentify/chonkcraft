package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.pathfinder.PathFinder;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A worker sent to a gold mine has to come back with the gold.
 *
 * <p>Written from a player's report of an enemy orc that "pops his head out of
 * a building and goes back in", on a wasteland map where the building in
 * question is a gold mine. A worker is off the map entirely while it is inside
 * a mine, so a worker that goes in, comes straight out and goes back in is
 * invisible, then visible, then invisible, at the cadence of
 * {@code WaitAtResource} -- which is precisely the picture that was described,
 * and it can run for the whole game because nothing about it is an error the
 * engine notices.
 *
 * <p>So the property is not "the worker looks busy", it is "the gold arrives".
 * A round trip that never banks anything is the bug however plausible each
 * step of it looks on its own.
 */
class HarvestRoundTripTest {

    private static GameData data() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install configured. Set -Dchonkcraft.pack or wc2.install.dir.");
        return new GameData(assets);
    }

    /** A bare world on a real map, with a clear patch to build the scene on. */
    private record Scene(World world, GameData data, int x, int y) {}

    private static Scene scene(GameData data) {
        PudMap pud = data.campaignMap("campaigns/human/level12h");
        Assumptions.assumeTrue(pud != null, "no wasteland campaign map available");
        var tileset = data.loadTileset(pud.tileset());
        World world = new World(GameMap.from(pud, tileset.tileset()), Player.from(pud));
        world.setUnitTypes(data.unitTypes().types());
        world.setUpgrades(data.upgrades().upgrades());
        world.setMissileTypes(data.missiles().types());
        GameMap map = world.map();
        for (int y = 4; y < map.height() - 16; y++) {
            for (int x = 4; x < map.width() - 16; x++) {
                if (isClear(map, x, y, 14, 14)) {
                    return new Scene(world, data, x, y);
                }
            }
        }
        Assumptions.abort("no clear fourteen-square patch on the map");
        return null;
    }

    private static boolean isClear(GameMap map, int x, int y, int width, int height) {
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                if (!map.field(x + i, y + j).isLandPassable()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Every cycle at which the worker went out of sight or came back. */
    private static List<Integer> runAndRecord(World world, Unit worker, int cycles,
            List<Boolean> states) {
        List<Integer> transitions = new ArrayList<>();
        boolean was = worker.isOnMap();
        for (int cycle = 0; cycle < cycles; cycle++) {
            world.tick();
            boolean now = worker.isOnMap();
            if (now != was) {
                transitions.add(cycle);
                states.add(now);
                was = now;
            }
        }
        return transitions;
    }

    @Test
    @DisplayName("an adjacent worker stages the retail mine approach without legacy A-star")
    void arrivingAtAMineUsesTheRetailStagedApproach() {
        GameData data = data();
        Scene scene = scene(data);
        World world = scene.world();
        UnitType mineType = data.unitTypes().types().get("unit-gold-mine");
        UnitType peonType = data.unitTypes().types().get("unit-peon");
        assertNotNull(mineType);
        assertNotNull(peonType);

        Unit mine = world.createUnit(
                mineType, World.NEUTRAL_PLAYER, scene.x(), scene.y());
        Unit peon = world.createUnit(
                peonType, 1, scene.x() + mineType.tileWidth(), scene.y() + 1);
        assertNotNull(mine);
        assertNotNull(peon);
        assertTrue(world.orderHarvest(peon, mine.tileX(), mine.tileY()));

        long before = PathFinder.searchesRun;
        int entryCycle = 0;
        while (!peon.removed() && entryCycle++ < 30) {
            world.tick();
        }

        assertTrue(peon.removed(),
                "the adjacent worker never entered the mine in thirty cycles");
        assertTrue(entryCycle > 1,
                "BNE stages the adjacent resource approach; the test must not"
                        + " bypass its action-25 delay by assuming one-tick entry");
        assertEquals(before, PathFinder.searchesRun,
                "the BNE resource path is the retail direction-buffer router, not"
                        + " LegacyEngine AStarFindPath; an adjacent mine approach must not"
                        + " revive the legacy global CostMoveTo cache");
    }

    @Test
    @DisplayName("a returning worker retains its chosen depot for the whole walk")
    void aReturnLegDoesNotFindItsDepotAgainEveryCycle() {
        GameData data = data();
        Scene scene = scene(data);
        World world = scene.world();
        UnitType hallType = data.unitTypes().types().get("unit-great-hall");
        UnitType peonType = data.unitTypes().types().get("unit-peon");
        assertNotNull(hallType);
        assertNotNull(peonType);

        Unit hall = world.createUnit(hallType, 1, scene.x(), scene.y());
        Unit peon = world.createUnit(
                peonType, 1, scene.x() + 10, scene.y() + 10);
        assertNotNull(hall);
        assertNotNull(peon);
        peon.setCarrying(UnitType.Resource.GOLD);
        peon.setHeldResource(UnitType.Resource.GOLD);
        peon.setCarried(100);
        peon.setReturningToDepot(true);
        peon.setResourceDepot(hall);
        peon.setReturnDepotGoal(hall);
        peon.setOrder(Unit.Order.HARVEST);

        long before = PathFinder.searchesRun;
        world.tick();

        assertTrue(peon.pathLength() > 0 || peon.isMoving(),
                "the return leg never planned its actual walk");
        assertTrue(PathFinder.searchesRun - before <= 1,
                "COrder_Resource stores FindDeposit's result as its goal. The return leg"
                        + " should spend one route search, not search once to choose the same"
                        + " depot again and then once more to walk toward it");
    }

    @Test
    @DisplayName("Entering a depot clears the worker's current animation")
    void enteringADepotClearsTheCurrentAnimation() {
        GameData data = data();
        Scene scene = scene(data);
        World world = scene.world();
        var types = data.unitTypes().types();
        UnitType hallType = types.get("unit-great-hall");
        UnitType peonType = types.get("unit-peon");
        assertNotNull(hallType);
        assertNotNull(peonType);

        Unit hall = world.createUnit(hallType, 1, scene.x(), scene.y());
        Unit peon = world.createUnit(peonType, 1,
                scene.x() + hallType.tileWidth(), scene.y() + 1);
        assertNotNull(hall);
        assertNotNull(peon);

        // MoveToDepot clears Anim.CurrAnim immediately after Remove. This is
        // observable on the following waiting cycle: UnitShowAnimation must
        // start Still at instruction zero instead of resuming the Still that
        // happened to be running before the worker went inside.
        peon.animation().switchTo(
                peonType.animationSet().getOrStill(AnimationSet.State.STILL));
        assertNotNull(peon.animation().current());
        peon.setCarrying(UnitType.Resource.GOLD);
        peon.setHeldResource(UnitType.Resource.GOLD);
        peon.setCarried(100);
        peon.setReturningToDepot(true);
        peon.setOrder(Unit.Order.HARVEST);

        world.tick();

        assertTrue(peon.removed(), "the adjacent worker did not enter its depot");
        assertNull(peon.animation().current(),
                "MoveToDepot must clear Anim.CurrAnim when the worker is removed");
    }

    @Test
    @DisplayName("A peon sent to a mine banks its gold rather than cycling in and out")
    void aWorkerDeliversItsLoad() {
        GameData data = data();
        Scene scene = scene(data);
        World world = scene.world();
        var types = data.unitTypes().types();
        UnitType mineType = types.get("unit-gold-mine");
        UnitType hallType = types.get("unit-great-hall");
        UnitType peonType = types.get("unit-peon");
        assertNotNull(mineType);
        assertNotNull(hallType);
        assertNotNull(peonType);

        // The mine north-west, the hall six squares south-east of it, and the
        // worker between them: an ordinary base, laid out so the trip is a
        // real walk rather than a step.
        Unit mine = world.createUnit(mineType, World.NEUTRAL_PLAYER, scene.x(), scene.y());
        assertNotNull(mine);
        Unit hall = world.createUnit(hallType, 1, scene.x() + 8, scene.y() + 8);
        assertNotNull(hall);
        Unit peon = world.createUnit(peonType, 1, scene.x() + 5, scene.y() + 5);
        assertNotNull(peon);

        int before = world.player(1).get(UnitType.Resource.GOLD);
        assertTrue(world.orderHarvest(peon, mine.tileX(), mine.tileY()),
                "the mine would not accept a harvest order");

        List<Boolean> states = new ArrayList<>();
        List<Integer> transitions = runAndRecord(world, peon, 3000, states);
        int gained = world.player(1).get(UnitType.Resource.GOLD) - before;

        assertTrue(gained > 0,
                "the worker banked no gold in 3000 cycles. It went out of sight and came back "
                        + transitions.size() + " times, at cycles "
                        + transitions.subList(0, Math.min(20, transitions.size()))
                        + "; mine holds " + mine.resourcesHeld()
                        + ", worker carries " + peon.carried()
                        + ", order " + peon.order()
                        + ", returning " + peon.returningToDepot()
                        + ", on map " + peon.isOnMap());
    }

    /**
     * The same scene with the mine already empty.
     *
     * <p>This is the shape the report describes. A mine with nothing left in
     * it is still a live building that still answers "yes" to "do you provide
     * gold", so a worker walks to it, goes inside, is handed nothing, comes
     * out, asks for the nearest gold, is given the same empty mine, and starts
     * again -- for ever, and invisible for most of each lap.
     */
    @Test
    @DisplayName("An empty mine does not trap a worker in a loop of entering it")
    void anEmptyMineDoesNotTrapAWorker() {
        GameData data = data();
        Scene scene = scene(data);
        World world = scene.world();
        var types = data.unitTypes().types();
        Unit mine = world.createUnit(types.get("unit-gold-mine"),
                World.NEUTRAL_PLAYER, scene.x(), scene.y());
        Unit hall = world.createUnit(types.get("unit-great-hall"), 1,
                scene.x() + 8, scene.y() + 8);
        Unit peon = world.createUnit(types.get("unit-peon"), 1, scene.x() + 5, scene.y() + 5);
        assertNotNull(mine);
        assertNotNull(hall);
        assertNotNull(peon);
        mine.setResourcesHeld(0);

        world.orderHarvest(peon, mine.tileX(), mine.tileY());
        List<Boolean> states = new ArrayList<>();
        List<Integer> transitions = runAndRecord(world, peon, 3000, states);

        assertTrue(transitions.size() <= 4,
                "the worker went in and out of the empty mine " + transitions.size()
                        + " times in 3000 cycles, at " + transitions.subList(0,
                                Math.min(20, transitions.size()))
                        + ". A worker that a mine cannot pay should give up, not keep queueing.");
    }

    /**
     * A worker holding a full load must not walk back into the mine.
     *
     * <p>The field case, reduced. There is a mine and no depot to be had, so
     * the worker fills up and then has nowhere to take it, and its owner's AI
     * keeps finding an idle worker and sending it back to the gold -- which is
     * modelled here by simply re-issuing the order whenever it falls idle.
     * Every one of those orders used to buy another hundred and fifty cycles
     * inside the mine for nothing, and the mine is where a worker is invisible.
     */
    @Test
    @DisplayName("A worker with a full load does not go back inside the mine")
    void aLoadedWorkerDoesNotReenterTheMine() {
        GameData data = data();
        Scene scene = scene(data);
        World world = scene.world();
        var types = data.unitTypes().types();
        Unit mine = world.createUnit(types.get("unit-gold-mine"),
                World.NEUTRAL_PLAYER, scene.x(), scene.y());
        Unit peon = world.createUnit(types.get("unit-peon"), 1, scene.x() + 4, scene.y() + 4);
        assertNotNull(mine);
        assertNotNull(peon);

        world.orderHarvest(peon, mine.tileX(), mine.tileY());
        int entries = 0;
        boolean was = peon.isOnMap();
        for (int cycle = 0; cycle < 3000; cycle++) {
            world.tick();
            boolean on = peon.isOnMap();
            if (was && !on) {
                entries++;
            }
            was = on;
            // What an owner's AI does with an idle worker.
            if (on && peon.order() == Unit.Order.STILL) {
                world.orderHarvest(peon, mine.tileX(), mine.tileY());
            }
        }
        assertTrue(peon.carried() > 0, "the worker never picked anything up");
        assertTrue(entries <= 2,
                "the worker went inside the mine " + entries + " times in 3000 cycles while"
                        + " already carrying " + peon.carried()
                        + ". Once it is full there is nothing in there for it.");
    }

    /**
     * A base whose workers go mining has to actually bank the gold.
     *
     * <p>{@code level13h} is where this was found: two orc bases whose peons
     * spent the whole game stepping in and out of a mine eleven squares from
     * a Great Hall they were told they could not reach, and whose treasuries
     * never moved off the figure the map started them with. The property is
     * the round trip, not the motion: a worker that takes mining orders and
     * banks nothing is the bug however busy it looks.
     *
     * <p>This used to demand that <em>every</em> base with workers and a hall
     * mine, and that is not a promise upstream makes. {@code UnitFindResource}
     * floods outward from the box one square around the depot
     * ({@code TerrainTraversal::PushUnitPosAndNeighboor}), and the fortress at
     * 81,2 on this map has forest along its west and north and its open ground
     * exactly on the far row and column that box never seeds -- so upstream
     * answers "no mine" for every worker it owns, and that player chops wood
     * for the whole mission with a 100,000-gold mine seven squares away. Its
     * bank cannot buy the hall that would reseed the flood, so the deadlock is
     * complete, and it is upstream's own: the straight-line finder this implementation
     * used to have handed those workers the mine across ground the flood
     * cannot reach, which is how the old assertion ever held.
     */
    @Test
    @DisplayName("a base whose workers take mining orders banks gold, and somebody mines")
    void theCampaignBasesMine() {
        GameData data = data();
        PudMap pud = data.campaignMap("campaigns/human/level13h");
        Assumptions.assumeTrue(pud != null, "no level13h available");
        var tileset = data.loadTileset(pud.tileset());
        World world = new World(GameMap.from(pud, tileset.tileset()), Player.from(pud));
        world.setUnitTypes(data.unitTypes().types());
        world.setUpgrades(data.upgrades().upgrades());
        world.setMissileTypes(data.missiles().types());
        data.populate(world, pud);
        world.recalculateSupply();
        world.enableAiForComputerPlayers();
        data.attachRetailAi(world, pud, java.util.Map.of());

        int[] before = new int[8];
        int[] previous = new int[8];
        int[] deposited = new int[8];
        for (int player = 0; player < 8; player++) {
            before[player] = world.player(player) == null
                    ? 0 : world.player(player).get(UnitType.Resource.GOLD);
            previous[player] = before[player];
        }
        boolean[] mined = new boolean[8];
        for (int cycle = 0; cycle < 12000; cycle++) {
            world.tick();
            // AI spending runs before units act, so a load deposited later in
            // this tick is visible at the boundary even when the computer
            // buys something on its next visit. Net bank at the end is not a
            // harvest measure: a healthy opponent is expected to spend it.
            for (int player = 0; player < 8; player++) {
                if (world.player(player) == null) {
                    continue;
                }
                int current = world.player(player).get(UnitType.Resource.GOLD);
                deposited[player] += Math.max(0, current - previous[player]);
                previous[player] = current;
            }
            // Sampled once a second: a worker inside the mine is off the map,
            // so the order is read while it walks there and back.
            if (cycle % 30 == 0) {
                for (Unit unit : world.unitsSnapshot()) {
                    if (unit.isAlive() && unit.player() >= 0 && unit.player() < 8
                            && unit.order() == Unit.Order.HARVEST
                            && unit.carrying() == UnitType.Resource.GOLD) {
                        mined[unit.player()] = true;
                    }
                }
            }
        }
        List<String> stuck = new ArrayList<>();
        int minersSeen = 0;
        int banked = 0;
        for (int player = 0; player < 8; player++) {
            // Only the computers: a person's workers stand where they were put
            // until the person tells them to do something, so a human slot
            // banking nothing is the game working, not a stuck worker.
            if (world.player(player) == null
                    || world.player(player).type() != PudMap.PlayerType.COMPUTER) {
                continue;
            }
            if (!mined[player]) {
                continue;
            }
            minersSeen++;
            int gained = world.player(player).get(UnitType.Resource.GOLD) - before[player];
            if (deposited[player] > 0) {
                banked++;
            } else {
                stuck.add("player " + player + " sent workers mining and banked "
                        + deposited[player] + " gold (net " + gained + ")");
            }
        }
        // The map has two computer slots: player 4, whose hall's flood reaches
        // its mine, and player 0, the sealed fortress described above. One
        // miner is therefore the full population, and nought means the sweep
        // sampled nothing and proves nothing.
        assertTrue(minersSeen >= 1,
                "no computer base ever sent a worker at gold; player 4's hall can reach"
                        + " its mine, so the sweep sampled nothing and this run proves"
                        + " nothing");
        assertTrue(stuck.isEmpty(),
                "bases that went mining and banked nothing in 12000 cycles: " + stuck);
        assertTrue(banked > 0, "nobody banked any gold at all in 12000 cycles");
    }
}
