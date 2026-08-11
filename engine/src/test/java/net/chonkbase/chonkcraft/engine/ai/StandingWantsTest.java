package net.chonkbase.chonkcraft.engine.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the computer wants stands; what it asks for is the shortfall.
 *
 * <p>{@code AiNeed} and {@code AiSet} write standing wants --
 * {@code PlayerAi::UnitTypeRequests} -- and every thought
 * {@code AiCheckUnits} measures them against the roster, equivalents
 * included, before the resource manager spends a coin. The implementation used to
 * translate the script calls into one-shot build requests counted at script
 * time, and every consequence was a divergence something on
 * campaigns/orc-exp/levelx04o paid for: a second lumber mill beside the one
 * the map placed, a force footman trained one second late, a peasant
 * trained into food the player did not have.
 */
class StandingWantsTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    /** A world whose slot 0 is a human computer, so the AI thinks for it. */
    private static World world(int size) {
        PudMap.PlayerType[] slots = new PudMap.PlayerType[16];
        java.util.Arrays.fill(slots, PudMap.PlayerType.NOBODY);
        slots[0] = PudMap.PlayerType.COMPUTER;
        slots[15] = PudMap.PlayerType.NEUTRAL;
        PudMap.Race[] races = new PudMap.Race[16];
        java.util.Arrays.fill(races, PudMap.Race.NEUTRAL);
        races[0] = PudMap.Race.HUMAN;
        Player[] players = new Player[16];
        for (int i = 0; i < 16; i++) {
            players[i] = new Player(i, slots[i], races[i]);
        }
        return new World(grass(size), players);
    }

    private static UnitType building(String ident) {
        UnitType type = new UnitType(ident);
        type.setTileSize(3, 3);
        type.setBoxSize(95, 95);
        type.setHitPoints(800);
        type.setBuilding(true);
        return type;
    }

    private static UnitType footman() {
        UnitType type = new UnitType("unit-footman");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        return type;
    }

    private static UnitType peasant() {
        UnitType type = new UnitType("unit-peasant");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setDemand(1);
        // A builder is a gatherer in the AI's eyes -- startBuilding asks
        // canGather before it asks the button table.
        net.chonkbase.chonkcraft.engine.unit.ResourceInfo gold =
                new net.chonkbase.chonkcraft.engine.unit.ResourceInfo(UnitType.Resource.GOLD);
        gold.setCapacity(100);
        type.gathering().put(UnitType.Resource.GOLD, gold);
        return type;
    }

    private static AiPlayer ai(World world) {
        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        return ai;
    }

    @Test
    @DisplayName("a want the roster already answers requests nothing")
    void aSatisfiedWantRequestsNothing() {
        World world = world(30);
        // The equivalence table resolves through the world's type registry,
        // as GameData supplies it -- an unregistered stand-in silently
        // resolves to nothing, and this test's castle did exactly that until
        // the queue stopped erasing entries at start and the wrongly
        // requested hall became visible.
        UnitType castle = building("unit-castle");
        UnitType hall = building("unit-town-hall");
        world.setUnitTypes(java.util.Map.of(
                "unit-castle", castle, "unit-town-hall", hall));
        world.setAiEquivalents(java.util.Map.of(
                "unit-town-hall", java.util.List.of("unit-keep", "unit-castle")));
        world.createUnit(castle, 0, 5, 5);
        world.player(0).add(UnitType.Resource.GOLD, 5000);
        world.player(0).add(UnitType.Resource.WOOD, 5000);
        AiPlayer ai = ai(world);
        ai.insertUnitTypeRequest(hall, 1);

        ai.think(world);

        assertTrue(ai.requests().isEmpty(),
                "the AI asked for a town hall in the shadow of its own castle."
                        + " AiCheckUnits counts AiHelpers.Equiv()'s stand-ins into the"
                        + " roster before it requests anything; levelx04o's player 1"
                        + " founded a second lumber mill this way");
    }

    @Test
    @DisplayName("a force is shopped for on the thought it was declared")
    void aForceIsShoppedForAtOnce() {
        World world = world(30);
        UnitType soldier = footman();
        Unit barracks = world.createUnit(building("unit-human-barracks"), 0, 5, 5);
        world.player(0).add(UnitType.Resource.GOLD, 5000);
        AiPlayer ai = ai(world);
        ai.force(1).want(soldier, 3);

        ai.think(world);

        assertNotNull(barracks.producing(),
                "the barracks is idle after the thought that declared the force."
                        + " AiCheckUnits runs between the script and the resource"
                        + " manager, so the first footman starts on the same thought;"
                        + " raising the request from the force manager, which runs"
                        + " after the bank is spent, trained it one second late --"
                        + " cycle 38 against upstream's 8 on levelx04o");
        assertEquals(3, ai.requests().getOrDefault(soldier, 0),
                "and the whole shortfall stands in the queue at once, not one unit"
                        + " a second -- the started one included, because an entry"
                        + " holds its job from request to delivery, AiBuildQueue's"
                        + " Want against Made, not merely to the start of work");
    }

    @Test
    @DisplayName("a busy building's request waits unpaid")
    void aBusyBuildingsRequestWaits() {
        World world = world(30);
        world.setTrainingQueueEnabled(true);
        UnitType soldier = footman();
        Unit barracks = world.createUnit(building("unit-human-barracks"), 0, 5, 5);
        world.player(0).add(UnitType.Resource.GOLD, 5000);
        assertTrue(world.orderTrain(barracks, soldier), "the fixture could not even begin");
        world.tick();
        assertNotNull(barracks.producing(), "the first training never started");

        AiPlayer ai = ai(world);
        ai.need(soldier, 1);
        int gold = world.player(0).get(UnitType.Resource.GOLD);
        ai.think(world);

        assertEquals(gold, world.player(0).get(UnitType.Resource.GOLD),
                "the AI paid a second footman into a working barracks' queue."
                        + " AiTrainUnit takes the first building whose IsIdle() answers"
                        + " yes and otherwise leaves the request standing; the human"
                        + " training queue is not the computer's. Upstream's castle on"
                        + " levelx04o trains at cycles 8, 278 and 548, each start"
                        + " waiting for the last; this port had paid three by 68");
        assertTrue(barracks.trainingQueue().isEmpty(), "and the queue itself took the job");
        assertEquals(1, ai.requests().getOrDefault(soldier, 0),
                "the request should still be standing for the next thought");
    }

    @Test
    @DisplayName("phantom requests do not eat supply")
    void requestsInLineDoNotEatSupply() {
        World world = world(30);
        UnitType worker = peasant();
        UnitType hall = building("unit-town-hall");
        hall.setSupply(2);
        Unit townHall = world.createUnit(hall, 0, 5, 5);
        world.player(0).add(UnitType.Resource.GOLD, 5000);
        world.recalculateSupply();
        AiPlayer ai = ai(world);
        ai.insertUnitTypeRequest(worker, 6);

        ai.think(world);

        assertNotNull(townHall.producing(),
                "the hall is idle: six standing requests were counted as six mouths."
                        + " AiCheckSupply reads queue.Made -- the work in progress --"
                        + " and ignores what merely stands in line; levelx04o's player 1"
                        + " sent its one peasant to raise a phantom-driven farm at"
                        + " cycle 38 for exactly this");
        assertFalse(ai.requests().containsKey(hall),
                "and no supply building was asked for on the phantoms' behalf");
    }

    @Test
    @DisplayName("removed is not dead: the peasant down the mine still counts and eats")
    void removedUnitsStillCountAndEat() {
        World world = world(30);
        UnitType worker = peasant();
        Unit digger = world.createUnit(worker, 0, 5, 5);
        world.recalculateSupply();
        assertEquals(1, world.player(0).demand(), "the fixture's peasant eats nothing");

        digger.setRemoved(true);
        world.recalculateSupply();

        assertEquals(1, world.player(0).demand(),
                "a peasant inside a mine or a building frame stopped eating."
                        + " Supply and Demand move at creation, death and change of"
                        + " owner -- CUnit::Remove touches neither -- and player 3 on"
                        + " levelx04o trained a peasant into that free mouth the moment"
                        + " its builder stepped inside the barracks frame");
        assertEquals(1, world.unitTypesCount(0, "unit-peasant"),
                "and UnitTypesCount forgot it too, so the AI would ask for a worker"
                        + " it already owns");
    }

    @Test
    @DisplayName("a builder already on the road carries its building's whole cost")
    void promisedBuildingsSpendTheBankFirst() {
        World world = world(30);
        UnitType worker = peasant();
        UnitType smithy = building("unit-human-blacksmith");
        smithy.costs().put(UnitType.Resource.GOLD, 700);
        UnitType tower = building("unit-human-watch-tower");
        tower.setTileSize(2, 2);
        tower.costs().put(UnitType.Resource.GOLD, 550);
        world.createUnit(worker, 0, 5, 5);
        world.createUnit(worker, 0, 20, 20);
        world.player(0).add(UnitType.Resource.GOLD, 1000);
        AiPlayer ai = ai(world);
        ai.need(smithy, 1);
        ai.need(tower, 1);

        ai.think(world);

        int walking = 0;
        for (var unit : world.unitsSnapshot()) {
            if (unit.pendingBuild() != null) {
                walking++;
            }
        }
        assertEquals(1, walking,
                "a thousand gold dispatched builders for twelve hundred and fifty"
                        + " gold of buildings. AiCheckCosts counts every building"
                        + " under a Build order as spent before the next request is"
                        + " weighed -- the bank does not move until a builder"
                        + " arrives, and without the ledger levelx04h's first thought"
                        + " sent out one worker per request and drew a pick for each,"
                        + " where upstream refuses the second tower outright");
    }

    @Test
    @DisplayName("a build waiting behind an unbreakable order already bills the bank")
    void latchedBuildsSpendTheBankBeforeTheyBecomeCurrent() {
        World world = world(30);
        UnitType worker = peasant();
        UnitType tower = building("unit-orc-watch-tower");
        tower.setTileSize(2, 2);
        tower.costs().put(UnitType.Resource.GOLD, 550);
        Unit committed = world.createUnit(worker, 0, 5, 5);
        world.createUnit(worker, 0, 20, 20);
        world.player(0).add(UnitType.Resource.GOLD, 1000);

        // Shape left by orderBuild when the old harvest animation is still
        // unbreakable: CurrentAction and the installed order remain Resource,
        // while the Build replacement waits behind it in Orders[1].
        committed.setOrder(Unit.Order.HARVEST);
        committed.setPendingBuild(tower);
        committed.setBuildLatchedFrom(Unit.Order.HARVEST);

        AiPlayer ai = ai(world);
        ai.need(tower, 1);
        ai.think(world);

        int promised = 0;
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.pendingBuild() == tower) {
                promised++;
            }
        }
        assertEquals(1, promised,
                "the queued 550-gold tower was invisible until it became CurrentAction:"
                        + " AiCheckCosts scans every order, so only 450 of the 1000 gold"
                        + " remains and the second tower must be refused. This is the"
                        + " levelx10h cycle-643 boundary");
    }

    @Test
    @DisplayName("a dead unit's standing want is asked for again")
    void theStandingWantOutlivesTheUnit() {
        World world = world(30);
        UnitType soldier = footman();
        Unit barracks = world.createUnit(building("unit-human-barracks"), 0, 5, 5);
        Unit veteran = world.createUnit(soldier, 0, 10, 10);
        world.player(0).add(UnitType.Resource.GOLD, 5000);
        AiPlayer ai = ai(world);
        ai.insertUnitTypeRequest(soldier, 1);

        ai.think(world);
        assertTrue(ai.requests().isEmpty(), "the living footman should have answered the want");
        assertNull(barracks.producing(), "and nothing should have been trained for it");

        veteran.setHitPoints(0);
        ai.think(world);
        assertNotNull(barracks.producing(),
                "the want did not stand: a one-shot request dies with the unit it"
                        + " bought, a standing want notices the gap every thought and"
                        + " replaces the dead");
    }

    @Test
    @DisplayName("an upgrade the AI cannot pay for sends extra workers after the missing resource")
    void anUnpaidUpgradeLeansTheWorkforce() {
        World world = world(30);
        // A wood the peasants can reach, and a mine, so both assignments can
        // actually land.
        for (int y = 3; y <= 12; y++) {
            world.map().field(17, y).setFlags(
                    TileFlag.LAND_ALLOWED | TileFlag.FOREST);
        }
        UnitType mineType = building("unit-gold-mine");
        mineType.setGivesResource(UnitType.Resource.GOLD);
        mineType.setCanHarvest(true);
        Unit mine = world.createUnit(mineType, 15, 10, 3);
        mine.setResourcesHeld(25000);

        UnitType worker = peasant();
        net.chonkbase.chonkcraft.engine.unit.ResourceInfo wood =
                new net.chonkbase.chonkcraft.engine.unit.ResourceInfo(UnitType.Resource.WOOD);
        wood.setCapacity(100);
        wood.setTerrainHarvester(true);
        worker.gathering().put(UnitType.Resource.WOOD, wood);
        for (int i = 0; i < 4; i++) {
            world.createUnit(worker, 0, 6 + i, 8);
        }

        UnitType hall = building("unit-town-hall");
        Unit upgradingHall = world.createUnit(hall, 0, 4, 12);
        UnitType keep = building("unit-keep");
        keep.costs().put(UnitType.Resource.GOLD, 200);
        keep.costs().put(UnitType.Resource.WOOD, 5000);
        world.player(0).add(UnitType.Resource.GOLD, 5000);

        AiPlayer ai = ai(world);
        ai.upgradeTo(hall, keep);
        // The standing request is still costed while the one hall is already
        // becoming a keep. AiCheckUnits calls AiAddUpgradeToRequest every
        // thought until the wanted type exists, and that routine runs
        // AiCheckUnitTypeCosts before it looks for an idle source building.
        upgradingHall.setUpgradingTo(keep);
        ai.think(world);

        int chopping = 0;
        int mining = 0;
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.order() == Unit.Order.HARVEST) {
                if (unit.carrying() == UnitType.Resource.WOOD) {
                    chopping++;
                } else if (unit.carrying() == UnitType.Resource.GOLD) {
                    mining++;
                }
            }
        }
        assertEquals(3, chopping,
                "the workforce ignored the bill it could not pay. AiAddUpgradeToRequest"
                        + " opens with AiCheckCosts and a failure raises the missing"
                        + " resources' bits in NeededMask before anything else happens,"
                        + " and AiCollectResources doubles those resources' share on the"
                        + " same thought: wood's fifty becomes a hundred against gold's"
                        + " fifty, and four peasants split three to the trees and one to"
                        + " the mine. On campaigns/human-exp/levelx03h the orc player's"
                        + " unaffordable fortress is the only thing that puts two of its"
                        + " four peons on wood");
        assertEquals(1, mining, "and the mine keeps the one peasant gold's share still buys");
    }

    @Test
    @DisplayName("the peons down the mine still hold gold's share of the workforce")
    void minersUndergroundKeepTheirShare() {
        World world = world(30);
        for (int y = 3; y <= 12; y++) {
            world.map().field(17, y).setFlags(
                    TileFlag.LAND_ALLOWED | TileFlag.FOREST);
        }
        UnitType mineType = building("unit-gold-mine");
        mineType.setGivesResource(UnitType.Resource.GOLD);
        mineType.setCanHarvest(true);
        Unit mine = world.createUnit(mineType, 15, 8, 3);
        mine.setResourcesHeld(25000);

        UnitType worker = peasant();
        net.chonkbase.chonkcraft.engine.unit.ResourceInfo gold =
                worker.gathering().get(UnitType.Resource.GOLD);
        gold.setWaitAtResource(150);
        net.chonkbase.chonkcraft.engine.unit.ResourceInfo wood =
                new net.chonkbase.chonkcraft.engine.unit.ResourceInfo(UnitType.Resource.WOOD);
        wood.setCapacity(100);
        wood.setTerrainHarvester(true);
        worker.gathering().put(UnitType.Resource.WOOD, wood);

        Unit miner1 = world.createUnit(worker, 0, 7, 4);
        Unit miner2 = world.createUnit(worker, 0, 11, 4);
        Unit chopper1 = world.createUnit(worker, 0, 16, 5);
        Unit chopper2 = world.createUnit(worker, 0, 16, 8);
        world.orderHarvest(miner1, 8, 3);
        world.orderHarvest(miner2, 8, 3);
        world.orderHarvest(chopper1, 17, 5);
        world.orderHarvest(chopper2, 17, 8);
        int walked = 0;
        while ((miner1.isOnMap() || miner2.isOnMap()) && walked++ < 25) {
            world.tick();
        }
        assertFalse(miner1.isOnMap() || miner2.isOnMap(),
                "the fixture's miners never went into the mine");

        AiPlayer ai = ai(world);
        ai.think(world);

        assertEquals(UnitType.Resource.WOOD, chopper1.carrying(),
                "a chopper was stolen for the mine. AiCollectResources' census walks"
                        + " Player->GetUnits(), removed units included, so the two peons"
                        + " underground count as gold's two and the exchange rule --"
                        + " steal only when the source resource holds at least two more"
                        + " workers -- moves nobody. A census that could not see them"
                        + " read gold as empty: on campaigns/human-exp/levelx03h at"
                        + " cycle 131 it took a chopper mid-swing, carried=8, where"
                        + " upstream moved nobody");
        assertEquals(UnitType.Resource.WOOD, chopper2.carrying(),
                "and the second chopper stays on its tree too");
        assertFalse(miner1.isOnMap() || miner2.isOnMap(),
                "and nothing dragged the miners back above ground");
    }

    @Test
    @DisplayName("the harvest donor scan follows the owner's roster after a global release")
    void harvestDonorsUsePlayerUnitOrderRatherThanTheActionTable() {
        World world = world(40);
        UnitType doomedType = building("unit-doomed-neutral-marker");
        Unit doomed = world.createUnit(doomedType, World.NEUTRAL_PLAYER, 30, 30);

        UnitType mineType = building("unit-gold-mine");
        mineType.setGivesResource(UnitType.Resource.GOLD);
        mineType.setCanHarvest(true);
        Unit mine = world.createUnit(mineType, World.NEUTRAL_PLAYER, 20, 20);
        mine.setResourcesHeld(25_000);

        UnitType worker = peasant();
        net.chonkbase.chonkcraft.engine.unit.ResourceInfo wood =
                new net.chonkbase.chonkcraft.engine.unit.ResourceInfo(UnitType.Resource.WOOD);
        wood.setCapacity(100);
        wood.setTerrainHarvester(true);
        worker.gathering().put(UnitType.Resource.WOOD, wood);
        for (int y = 4; y < 12; y++) {
            world.map().field(25, y).setFlags(TileFlag.LAND_ALLOWED | TileFlag.FOREST);
        }

        // Choppers are created before the four equal-load miners, so miner4
        // is the final global unit and the final entry in player 0's roster.
        Unit chopper1 = world.createUnit(worker, 0, 23, 5);
        Unit chopper2 = world.createUnit(worker, 0, 23, 8);
        Unit miner1 = world.createUnit(worker, 0, 17, 18);
        Unit miner2 = world.createUnit(worker, 0, 18, 17);
        Unit miner3 = world.createUnit(worker, 0, 19, 17);
        Unit miner4 = world.createUnit(worker, 0, 17, 19);

        // Releasing another owner's first global slot moves miner4 to the
        // front of UnitActions, but it does not mutate player 0's roster.
        world.kill(doomed);
        world.tick();
        assertFalse(world.units().contains(doomed), "the fixture did not release its hole");
        assertEquals(miner4, world.units().get(0),
                "the fixture did not separate global and per-player order");

        assertTrue(world.orderHarvest(chopper1, 25, 5));
        assertTrue(world.orderHarvest(chopper2, 25, 8));
        assertTrue(world.orderHarvest(miner1, mine.tileX(), mine.tileY()));
        assertTrue(world.orderHarvest(miner2, mine.tileX(), mine.tileY()));
        assertTrue(world.orderHarvest(miner3, mine.tileX(), mine.tileY()));
        assertTrue(world.orderHarvest(miner4, mine.tileX(), mine.tileY()));

        AiPlayer ai = ai(world);
        ai.think(world);

        assertEquals(UnitType.Resource.WOOD, miner4.carrying(),
                "AiCollectResources scanned the global action-table permutation");
        assertEquals(UnitType.Resource.GOLD, miner3.carrying(),
                "the owner's final equal-load miner was not chosen as the donor");
    }

    @Test
    @DisplayName("an ogre mound before its stronghold starts nobody walking")
    void theTechTreeGatesTheBuildQueue() {
        World world = world(30);
        net.chonkbase.chonkcraft.engine.upgrade.DependencyRules rules =
                new net.chonkbase.chonkcraft.engine.upgrade.DependencyRules();
        rules.define("unit-ogre-mound", java.util.List.of(
                java.util.List.of("unit-stronghold")));
        world.setDependencies(rules);
        world.createUnit(peasant(), 0, 5, 5);
        world.player(0).add(UnitType.Resource.GOLD, 5000);
        world.player(0).add(UnitType.Resource.WOOD, 5000);
        UnitType mound = building("unit-ogre-mound");
        mound.costs().put(UnitType.Resource.GOLD, 1000);
        AiPlayer ai = ai(world);
        ai.need(mound, 1);
        ai.think(world);

        int walking = 0;
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.pendingBuild() != null) {
                walking++;
            }
        }
        assertEquals(0, walking,
                "the AI founded a building its tech tree refuses. AiMakeUnit's"
                        + " equivalence walk erases every type CheckDependByIdent"
                        + " rejects (ai_force.cpp:253-267); on levelx12h this port's"
                        + " first thought raised an ogre mound, an alchemist and an"
                        + " altar before any stronghold stood, and drew a worker pick"
                        + " for each");
        assertEquals(1, ai.requests().getOrDefault(mound, 0),
                "and the request stands for the day the stronghold rises");
    }

    @Test
    @DisplayName("three separate asks for a tower start three builders in one thought")
    void separateEntriesDispatchTogether() {
        World world = world(30);
        for (int i = 0; i < 4; i++) {
            world.createUnit(peasant(), 0, 5 + i * 3, 5);
        }
        world.player(0).add(UnitType.Resource.GOLD, 9000);
        world.player(0).add(UnitType.Resource.WOOD, 9000);
        UnitType tower = building("unit-orc-watch-tower");
        tower.setTileSize(2, 2);
        tower.costs().put(UnitType.Resource.GOLD, 550);
        AiPlayer ai = ai(world);
        ai.need(tower, 1);
        ai.need(tower, 1);
        ai.need(tower, 1);
        ai.think(world);

        int walking = 0;
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.pendingBuild() != null) {
                walking++;
            }
        }
        assertEquals(3, walking,
                "three one-tower entries started " + walking + " builders. The build"
                        + " queue is entries, not a tally: AiCheckingWork advances each"
                        + " entry once a thought, so a script's three AiNeeds walk"
                        + " three builders together -- upstream's first thought on"
                        + " levelx12h sends exactly three towers out at cycle 7 --"
                        + " where a want=3 tally starts one a second");
    }

    @Test
    @DisplayName("the keep buys before the spellbook when one bank must choose")
    void theUpgradeToAsksBeforeTheResearch() {
        World world = world(30);
        Unit hall = world.createUnit(building("unit-town-hall"), 0, 4, 4);
        world.createUnit(building("unit-mage-tower"), 0, 12, 4);
        UnitType keep = building("unit-keep");
        keep.costs().put(UnitType.Resource.TIME, 1);
        keep.costs().put(UnitType.Resource.GOLD, 2000);
        var upgrades = new net.chonkbase.chonkcraft.engine.upgrade.UpgradeSet();
        var polymorph = upgrades.getOrCreate("upgrade-polymorph");
        polymorph.costs().put(UnitType.Resource.TIME, 1);
        polymorph.costs().put(UnitType.Resource.GOLD, 2000);
        world.setUpgrades(upgrades);
        world.player(0).set(UnitType.Resource.GOLD, 2000);

        AiPlayer ai = ai(world);
        ai.upgradeTo(hall.type(), keep);
        ai.research("upgrade-polymorph");
        ai.think(world);

        // AiCheckUnits walks the standing wants in a fixed order: the unit
        // requests, then the upgrade-tos, then the researches
        // One bank, two asks of 2000: the keep buys
        // and the spellbook waits. On campaigns/orc-exp/levelx12o this
        // port's polymorph took the keep's gold on player 3's first
        // thought, and the keep was never begun.
        assertEquals(keep, hall.upgradingTo(),
                "the hall is not becoming the keep: the upgrade-to must ask the"
                        + " bank before any research does");
        int researching = 0;
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.researching() != null) {
                researching++;
            }
        }
        assertEquals(0, researching,
                "the spellbook was paid with the keep's money");
    }

    @Test
    @DisplayName("the script's own collect split is obeyed: all wood means all axes")
    void theScriptsCollectSplitIsObeyed() {
        World world = world(30);
        for (int y = 3; y <= 12; y++) {
            world.map().field(17, y).setFlags(
                    TileFlag.LAND_ALLOWED | TileFlag.FOREST);
        }
        UnitType mineType = building("unit-gold-mine");
        mineType.setGivesResource(UnitType.Resource.GOLD);
        mineType.setCanHarvest(true);
        Unit mine = world.createUnit(mineType, 15, 8, 3);
        mine.setResourcesHeld(25000);
        UnitType worker = peasant();
        net.chonkbase.chonkcraft.engine.unit.ResourceInfo wood =
                new net.chonkbase.chonkcraft.engine.unit.ResourceInfo(UnitType.Resource.WOOD);
        wood.setCapacity(100);
        wood.setTerrainHarvester(true);
        worker.gathering().put(UnitType.Resource.WOOD, wood);
        for (int i = 0; i < 4; i++) {
            world.createUnit(worker, 0, 6 + i * 3, 8);
        }

        AiPlayer ai = ai(world);
        // AiSetCollect({0, 0, 100, 0...}): everything to the trees.
        // level13h's hum-13 asks an oil-heavy split the same way, and a port
        // that kept AiInit's constant sent its peons by the default 50/50
        // whatever the script said.
        ai.setCollect(0, 100, 0);
        ai.think(world);

        int chopping = 0;
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.order() == Unit.Order.HARVEST
                    && unit.carrying() == UnitType.Resource.WOOD) {
                chopping++;
            }
        }
        assertEquals(4, chopping,
                "a hundred-percent wood split put " + chopping + " of four peasants on"
                        + " the trees. AiSetCollectResources rewrites the Collect table"
                        + " the census divides by, and the campaign personalities call"
                        + " it -- level13h's hum-13 asks {0, 50, 50, 100}");
    }

    @Test
    @DisplayName("AiSet rewrites the first entry; AiNeed always appends")
    void setRewritesAndNeedAppends() {
        World world = world(30);
        UnitType soldier = footman();
        AiPlayer ai = ai(world);

        ai.insertUnitTypeRequest(soldier, 1);
        ai.insertUnitTypeRequest(soldier, 1);
        assertEquals(2, ai.unitTypeRequests().size(),
                "two AiNeeds are two entries -- a script's second"
                        + " AiNeed(AiBarracks()) is how it asks for a second barracks");

        AiPlayer.StandingRequest first = ai.findUnitTypeRequest(soldier);
        assertNotNull(first);
        first.setCount(5);
        assertEquals(5, ai.unitTypeRequests().get(0).count(),
                "FindInUnitTypeRequests answers the first entry, which is the one"
                        + " AiSet rewrites");
        assertNull(ai.findUnitTypeRequest(new UnitType("unit-ballista")),
                "an unrequested type has no standing entry to find");
    }
}
