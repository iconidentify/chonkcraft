package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.unit.UnitType.Resource;
import net.chonkbase.chonkcraft.engine.upgrade.Upgrade;
import net.chonkbase.chonkcraft.engine.upgrade.UpgradeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cancelling training, research and construction.
 *
 * <p>The refund percentages are upstream's: {@code CancelTrainingCostsFactor}
 * and {@code CancelResearchCostsFactor} are 100, {@code CancelBuildingCostsFactor}
 * is 75. Only the building loses anything, and that quarter is the whole reason
 * misplacing a keep is a real mistake rather than a free undo.
 */
class CancelOrdersTest {

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

    private static UnitType townHall() {
        UnitType type = new UnitType("unit-town-hall");
        type.setTileSize(4, 4);
        type.setHitPoints(1200);
        type.setBuilding(true);
        type.setSupply(20);
        type.costs().put(Resource.TIME, 1);
        return type;
    }

    private static UnitType barracks() {
        UnitType type = new UnitType("unit-human-barracks");
        type.setTileSize(3, 3);
        type.setHitPoints(800);
        type.setBuilding(true);
        type.costs().put(Resource.TIME, 1);
        type.costs().put(Resource.GOLD, 700);
        type.costs().put(Resource.WOOD, 450);
        return type;
    }

    private static World richWorld() {
        World world = new World(grass(30));
        world.player(0).set(Resource.GOLD, 5000);
        world.player(0).set(Resource.WOOD, 5000);
        return world;
    }

    @Test
    @DisplayName("cancelling training gives every coin back")
    void trainingRefundsInFull() {
        World world = richWorld();
        Unit hall = world.createUnit(townHall(), 0, 4, 4);
        world.recalculateSupply();

        assertTrue(world.orderTrain(hall, peasant()));
        assertEquals(4600, world.player(0).get(Resource.GOLD),
                "the cost is taken when the order is given, not when it finishes");

        assertTrue(world.cancelTraining(hall));
        assertEquals(5000, world.player(0).get(Resource.GOLD));
        assertNull(hall.producing());
        assertEquals(0, hall.progress());
    }

    @Test
    @DisplayName("cancelling training refunds the whole queued line")
    void queuedTrainingRefundsInFull() {
        World world = richWorld();
        world.setTrainingQueueEnabled(true);
        Unit hall = world.createUnit(townHall(), 0, 4, 4);
        world.recalculateSupply();

        assertTrue(world.orderTrain(hall, peasant()));
        assertTrue(world.orderTrain(hall, peasant()));
        assertEquals(4200, world.player(0).get(Resource.GOLD));

        assertTrue(world.cancelTraining(hall));

        assertEquals(5000, world.player(0).get(Resource.GOLD));
        assertNull(hall.producing());
        assertTrue(hall.trainingQueue().isEmpty());
    }

    @Test
    @DisplayName("there is nothing to cancel at an idle building")
    void cancellingNothingIsRefused() {
        World world = richWorld();
        Unit hall = world.createUnit(townHall(), 0, 4, 4);
        assertFalse(world.cancelTraining(hall));
        assertFalse(world.cancelResearch(hall));
        assertFalse(world.cancelConstruction(hall));
        assertEquals(5000, world.player(0).get(Resource.GOLD), "and nothing is paid out");
    }

    @Test
    @DisplayName("cancelling research gives every coin back and leaves it unresearched")
    void researchRefundsInFull() {
        World world = richWorld();
        UpgradeSet upgrades = new UpgradeSet();
        Upgrade sword = upgrades.getOrCreate("upgrade-sword1");
        sword.costs().put(Resource.GOLD, 800);
        sword.costs().put(Resource.TIME, 200);
        world.setUpgrades(upgrades);

        UnitType smithType = barracks();
        Unit smith = world.createUnit(smithType, 0, 4, 4);

        assertTrue(world.orderResearch(smith, "upgrade-sword1"));
        assertEquals(4200, world.player(0).get(Resource.GOLD));

        assertTrue(world.cancelResearch(smith));
        assertEquals(5000, world.player(0).get(Resource.GOLD));
        assertNull(smith.researching());
        assertFalse(world.upgrades(0).has("upgrade-sword1"),
                "a cancelled upgrade must not count as researched");
    }

    @Test
    @DisplayName("cancelling a building keeps a quarter of the cost and returns the worker")
    void constructionRefundsThreeQuarters() {
        World world = richWorld();
        Unit worker = world.createUnit(peasant(), 0, 3, 3);

        assertTrue(world.orderBuild(worker, barracks(), 10, 10));
        // The order costs nothing; the foundation does. StartBuilding is where
        // upstream subtracts it.
        assertEquals(5000, world.player(0).get(Resource.GOLD));
        assertEquals(5000, world.player(0).get(Resource.WOOD));

        Unit site = null;
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 60 && site == null; cycle++) {
            world.tick();
            for (Unit unit : world.unitsSnapshot()) {
                if (unit.order() == Unit.Order.UNDER_CONSTRUCTION) {
                    site = unit;
                }
            }
        }
        assertNotNull(site, "the peasant never started the barracks");
        assertTrue(worker.removed(), "the builder works from inside its site");
        assertEquals(4300, world.player(0).get(Resource.GOLD),
                "the site is down, so the barracks should have been paid for");
        assertEquals(4550, world.player(0).get(Resource.WOOD),
                "the site is down, so the barracks should have been paid for");

        assertTrue(world.cancelConstruction(site));
        // Three quarters of 700 gold and 450 wood, rounded down as upstream's
        // integer arithmetic does.
        assertEquals(4300 + 525, world.player(0).get(Resource.GOLD));
        assertEquals(4550 + 337, world.player(0).get(Resource.WOOD));
        assertFalse(site.isAlive(), "the half-built barracks must not survive the cancel");
        assertFalse(worker.removed(), "the builder has to come back out");
        assertTrue(worker.isAlive());
    }

    @Test
    @DisplayName("stopping a building site cancels it and returns the worker")
    void stopCancelsConstructionRatherThanCompletingIt() {
        World world = richWorld();
        Unit worker = world.createUnit(peasant(), 0, 3, 3);
        assertTrue(world.orderBuild(worker, barracks(), 10, 10));

        Unit site = null;
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 60 && site == null; cycle++) {
            world.tick();
            for (Unit unit : world.unitsSnapshot()) {
                if (unit.order() == Unit.Order.UNDER_CONSTRUCTION) {
                    site = unit;
                }
            }
        }
        assertNotNull(site);
        assertTrue(worker.removed());

        world.orderStop(site);

        assertFalse(site.isAlive(), "Stop must not turn scaffolding into a finished building");
        assertFalse(worker.removed(), "the builder must be dropped out of the cancelled site");
        assertTrue(worker.isOnMap());
        assertEquals(4825, world.player(0).get(Resource.GOLD));
        assertEquals(4887, world.player(0).get(Resource.WOOD));
    }
}
