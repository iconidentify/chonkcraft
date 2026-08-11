package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Whatever was inside a unit when it died comes back out.
 *
 * <p>Destroying a half-built farm used to strand its peasant in limbo: still in
 * the world list, still holding its thirty hit points, off the map forever, no
 * body, no dying cry, its food never released. It was neither alive nor dead --
 * {@code Unit.isAlive()} answers false for anything removed, which is the only
 * reason it looked dead at all.
 *
 * <p>Upstream cannot lose it. {@code COrder_Built::~COrder_Built} calls
 * {@code CancelBuilt}, which does {@code DropOutOnSide(*worker, LookingW,
 * unit)}, and the order is destroyed by {@code UnitClearOrders} inside
 * {@code LetUnitDie}.
 */
class ContainerDeathTest {

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
        return set;
    }

    private static UnitType peasant() {
        UnitType type = new UnitType("unit-peasant");
        type.setName("peasant");
        type.setTileSize(1, 1);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setAnimationSet(walker());
        return type;
    }

    private static UnitType farm() {
        UnitType type = new UnitType("unit-farm");
        type.setName("farm");
        type.setTileSize(2, 2);
        type.setHitPoints(400);
        type.setBuilding(true);
        type.setSpeed(0);
        type.setAnimationSet(new AnimationSet("building"));
        return type;
    }

    @Test
    @DisplayName("destroying a building under construction puts the builder back on the map")
    void theBuilderIsNotLostWithItsSite() {
        World world = new World(grass(30));
        UnitType peasantType = peasant();
        UnitType farmType = farm();
        world.setUnitTypes(Map.of("unit-peasant", peasantType, "unit-farm", farmType));

        world.player(0).set(UnitType.Resource.GOLD, 10_000);
        world.player(0).set(UnitType.Resource.WOOD, 10_000);
        Unit worker = world.createUnit(peasantType, 0, 8, 8);
        assertNotNull(worker);
        assertTrue(world.orderBuild(worker, farmType, 10, 10), "the build order was refused");

        Unit site = null;
        for (int cycle = 0; cycle < 400 && site == null; cycle++) {
            world.tick();
            for (Unit unit : world.unitsSnapshot()) {
                if (unit.order() == Unit.Order.UNDER_CONSTRUCTION) {
                    site = unit;
                }
            }
        }
        assertNotNull(site, "the construction site never appeared");
        assertTrue(worker.removed(), "the builder should be inside its site");
        assertEquals(site, worker.worksite());

        world.kill(site);
        world.tick();

        assertFalse(worker.removed(),
                "the builder is still off the map: World.kill releases cargo() and never "
                        + "touched worksite()");
        assertTrue(worker.isAlive(), "and it is alive, not stuck between the two");
        assertNull(worker.worksite(), "with no link left to the ruins");
        assertEquals(Unit.Order.STILL, worker.order(),
                "CancelBuilt leaves an abandoned builder idle beside the site");
    }

    @Test
    @DisplayName("a harvester inside a depot that is destroyed walks out with its load")
    void aHarvesterIsNotLostWithItsDepot() {
        World world = new World(grass(30));
        UnitType peasantType = peasant();
        UnitType hallType = new UnitType("unit-town-hall");
        hallType.setName("hall");
        hallType.setTileSize(4, 4);
        hallType.setHitPoints(1200);
        hallType.setBuilding(true);
        hallType.setSpeed(0);
        hallType.setAnimationSet(new AnimationSet("building"));

        Unit hall = world.createUnit(hallType, 0, 4, 4);
        Unit worker = world.createUnit(peasantType, 0, 10, 10);
        assertNotNull(hall);
        assertNotNull(worker);

        // Put it inside by hand: what the harvest step does when a worker
        // reaches a mine or banks a load. The relationship is the same one the
        // construction site uses and it was released by neither.
        worker.setWorksite(hall);
        worker.setRemoved(true);
        worker.setCarried(100);
        worker.setTile(hall.tileX(), hall.tileY());

        world.kill(hall);
        world.tick();

        assertFalse(worker.removed(), "the harvester was stranded inside the ruins");
        assertNull(worker.worksite());
        assertEquals(100, worker.carried(), "and it did not drop what it was carrying");
    }

    @Test
    @DisplayName("an occupant with nowhere at all to stand dies with its container")
    void anOccupantWithNoWayOutDies() {
        World world = new World(grass(7));
        UnitType hut = new UnitType("unit-hut");
        hut.setTileSize(1, 1);
        hut.setHitPoints(100);
        hut.setBuilding(true);
        hut.setSpeed(0);
        hut.setAnimationSet(new AnimationSet("building"));

        Unit building = world.createUnit(hut, 0, 3, 3);
        Unit worker = world.createUnit(peasant(), 0, 0, 0);
        assertNotNull(building);
        assertNotNull(worker);

        // Wall the hut in on every side after the fact: nothing off its own
        // square is standable, so DropOutOnSide has nowhere to report.
        for (int y = 0; y < 7; y++) {
            for (int x = 0; x < 7; x++) {
                if (x != 3 || y != 3) {
                    world.map().field(x, y).setFlags(TileFlag.UNPASSABLE);
                }
            }
        }
        worker.setRemoved(true);
        worker.setWorksite(building);
        building.setWorksite(worker);

        world.kill(building);
        assertEquals(Unit.Order.DYING, worker.order(),
                "an occupant that cannot be put down must die rather than linger off-map");
    }
}
