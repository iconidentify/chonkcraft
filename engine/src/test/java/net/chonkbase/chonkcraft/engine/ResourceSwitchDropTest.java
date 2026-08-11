package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A chopper sent to gold loses its part-felled wood at the mine door.
 *
 * <p>{@code COrder_Resource::StartGathering} compares the order's resource
 * against what the unit has in hand and, on a change, drops the old load
 * whole -- {@code DropResource}, {@code 611-614} -- before the mine fills the room that is left,
 * {@code addload = ResourceCapacity - unit.ResourcesHeld} then
 * {@code unit.ResourcesHeld += addload}.
 * So the reassigned worker walks out with a full hundred, the mine is a full
 * hundred lighter, and the wood is simply gone.
 *
 * <p>campaigns/orc/level12o is the measurement: the red peon that had six
 * wood in hand when the AI moved it to gold banked 94 at cycle 425 in this
 * port -- the room left beside wood it never dropped -- where upstream's
 * banked the hundred, and that six-coin ledger gap was the map's first
 * divergence.
 */
class ResourceSwitchDropTest {

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
        AnimationSet set = new AnimationSet("worker");
        set.put(AnimationSet.State.STILL, Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move",
                List.of("frame 0", "move 16", "wait 1", "frame 5", "move 16", "wait 1")));
        return set;
    }

    /** A peasant that fells wood two at a swing and carries gold whole. */
    private static UnitType peasant() {
        UnitType type = new UnitType("unit-peasant");
        type.setTileSize(1, 1);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setAnimationSet(walker());

        ResourceInfo gold = new ResourceInfo(Resource.GOLD);
        gold.setCapacity(100);
        gold.setWaitAtResource(2);
        gold.setWaitAtDepot(2);
        type.gathering().put(Resource.GOLD, gold);

        ResourceInfo wood = new ResourceInfo(Resource.WOOD);
        wood.setCapacity(100);
        wood.setWaitAtResource(2);
        wood.setWaitAtDepot(2);
        wood.setStep(2);
        wood.setTerrainHarvester(true);
        type.gathering().put(Resource.WOOD, wood);
        return type;
    }

    private static UnitType townHall() {
        UnitType type = new UnitType("unit-town-hall");
        type.setTileSize(4, 4);
        type.setHitPoints(1200);
        type.setBuilding(true);
        type.stores().add(Resource.GOLD);
        type.stores().add(Resource.WOOD);
        return type;
    }

    private static UnitType goldMine() {
        UnitType type = new UnitType("unit-gold-mine");
        type.setTileSize(3, 3);
        type.setHitPoints(25500);
        type.setBuilding(true);
        type.setGivesResource(Resource.GOLD);
        type.setCanHarvest(true);
        return type;
    }

    @Test
    @DisplayName("a chopper sent to gold banks the full hundred and the wood is gone")
    void aChopperSentToGoldBanksTheFullHundred() {
        World world = new World(grass(30));
        world.createUnit(townHall(), 0, 2, 2);
        Unit mine = world.createUnit(goldMine(), World.NEUTRAL_PLAYER, 20, 8);
        mine.setResourcesHeld(50_000);
        Unit worker = world.createUnit(peasant(), 0, 8, 8);

        world.map().field(12, 8).addFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
        assertTrue(world.orderHarvest(worker, 12, 8),
                "the stand of forest must be choppable, or nothing here measures");

        // Swing until something is actually in hand -- a part-load, well short
        // of the hundred a full trip home would take.
        boolean partial = false;
        for (int cycle = 0; cycle < 600 && !partial; cycle++) {
            world.tick();
            partial = worker.carried() > 0 && worker.carried() < 100;
        }
        assertTrue(partial, "the chopper never got a part-load in hand to lose");

        // The owner changes its mind, as level12o's AI does at cycle 366.
        assertTrue(world.orderHarvest(worker, mine.tileX(), mine.tileY()),
                "the mine must take the reassigned worker");
        for (int cycle = 0; cycle < 2000 && world.player(0).get(Resource.GOLD) == 0; cycle++) {
            world.tick();
        }

        assertEquals(100, world.player(0).get(Resource.GOLD),
                "the load banked must be the mine's full hundred: the wood in"
                        + " hand is dropped the moment gathering starts on the"
                        + " other resource, not kept as a head start on the fill");
        assertEquals(0, world.player(0).get(Resource.WOOD),
                "the part-felled wood never reaches any ledger -- it is dropped"
                        + " at the mine door, not banked alongside the gold");
        assertEquals(50_000 - 100, mine.resourcesHeld(),
                "and the mine pays out a full hundred, not merely the room the"
                        + " undropped wood left beside itself");
    }

    @Test
    @DisplayName("return goods copies the cargo kind, not an interrupted order's target")
    void anInterruptedGoldOrderDoesNotTurnHeldWoodIntoGold() {
        World world = new World(grass(30));
        world.createUnit(townHall(), 0, 2, 2);
        Unit worker = world.createUnit(peasant(), 0, 8, 8);
        // COrder_Resource::CurrentResource has changed to the new gold
        // destination, but CUnit::CurrentResource still describes the load.
        worker.setCarrying(Resource.GOLD);
        worker.setHeldResource(Resource.WOOD);
        worker.setCarried(28);

        assertTrue(world.orderReturnGoods(worker));
        assertEquals(Resource.WOOD, worker.carrying(),
                "NewActionReturnGoods copied the interrupted order's gold target");

        for (int cycle = 0; cycle < 600
                && world.player(0).get(Resource.WOOD) == 0; cycle++) {
            world.tick();
        }

        assertEquals(28, world.player(0).get(Resource.WOOD));
        assertEquals(0, world.player(0).get(Resource.GOLD));
    }
}
