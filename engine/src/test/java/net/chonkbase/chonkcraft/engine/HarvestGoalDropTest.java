package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudMap;
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
 * A harvester lets go of a mine its owner has never seen.
 *
 * <p>{@code MoveToResource_Unit}'s default arm answers an early "reached"
 * when the walk's animation is breakable and the goal fails
 * {@code IsVisibleAsGoal};
 * {@code StartGathering}'s dead-goal arm then looks for another mine within
 * fifteen of the worker or finishes the order
 * The drop can fire only in a step's
 * breakable tail -- the cycle a step commits, the animation is already
 * unbreakable again -- so the walk always sets off before it can give up.
 *
 * <p>A computer player never drops: {@code IsVisibleAsGoal} answers yes for
 * every uncloaked unit before the fog is consulted. The players that do are
 * persons and rescue slots, and campaigns/human/level08h is the measurement:
 * its rescue-active slot's drafted peasant is sent to the empty mine at
 * 21,28 across the map, walks one step, and lets go in that step's tail at
 * cycle 147 -- both engines then walk to the crowded near mine instead.
 */
class HarvestGoalDropTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    /** The real peasant's move shape: unbreakable through the last move,
     * one breakable tail wait -- the window every drop uses. */
    private static AnimationSet walker() {
        AnimationSet set = new AnimationSet("w");
        set.put(AnimationSet.State.STILL, Animation.parse("s", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("m", List.of(
                "unbreakable begin", "frame 0", "move 8", "wait 1",
                "frame 5", "move 8", "wait 1", "frame 10", "move 8", "wait 1",
                "frame 15", "move 8", "unbreakable end", "wait 1")));
        set.put(AnimationSet.State.DEATH, Animation.parse("d", List.of("frame 50", "wait 1")));
        return set;
    }

    private static UnitType peasant() {
        UnitType type = new UnitType("unit-peasant");
        type.setTileSize(1, 1);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setSightRange(4);
        type.setAnimationSet(walker());
        ResourceInfo gold = new ResourceInfo(Resource.GOLD);
        gold.setCapacity(100);
        gold.setWaitAtResource(2);
        gold.setWaitAtDepot(2);
        type.gathering().put(Resource.GOLD, gold);
        return type;
    }

    private static UnitType goldMine() {
        UnitType type = new UnitType("unit-gold-mine");
        type.setTileSize(3, 3);
        type.setHitPoints(100_000);
        type.setBuilding(true);
        type.setGivesResource(Resource.GOLD);
        type.setCanHarvest(true);
        return type;
    }

    private static World world(PudMap.PlayerType zero) {
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            PudMap.PlayerType kind = switch (i) {
                case 0 -> zero;
                case World.NEUTRAL_PLAYER -> PudMap.PlayerType.NEUTRAL;
                default -> PudMap.PlayerType.NOBODY;
            };
            players[i] = new Player(i, kind, PudMap.Race.HUMAN);
        }
        World world = new World(grass(48), players);
        world.establishDiplomacy();
        return world;
    }

    @Test
    @DisplayName("a person's harvester walks one step, then gives up the unseen mine")
    void aPersonsHarvesterDropsAMineItsOwnerNeverSaw() {
        World world = world(PudMap.PlayerType.PERSON);
        Unit mine = world.createUnit(goldMine(), World.NEUTRAL_PLAYER, 40, 5);
        mine.setResourcesHeld(50_000);
        Unit worker = world.createUnit(peasant(), 0, 5, 5);

        assertTrue(world.orderHarvest(worker, mine.tileX(), mine.tileY()),
                "the harvest order at the unseen mine must be accepted -- refusing"
                        + " it at command time is not the game's behaviour");

        int startX = worker.tileX();
        for (int cycle = 0; cycle < 60; cycle++) {
            world.tick();
        }

        assertEquals(Unit.Order.STILL, worker.order(),
                "the walk must give the unseen mine up in a step's breakable tail"
                        + " and finish -- no other mine stands within fifteen -- not"
                        + " march across the map to a goal its owner cannot see");
        assertTrue(worker.tileX() - startX >= 1 && worker.tileX() - startX <= 3,
                "and the walk must genuinely set off first: the drop only fires"
                        + " after a step, at " + worker.tileX() + "," + worker.tileY()
                        + " from " + startX + ",5");
    }

    @Test
    @DisplayName("rediscovering the same unseen mine keeps the buffered route")
    void rediscoveringTheSameUnseenMineKeepsTheBufferedRoute() {
        World world = world(PudMap.PlayerType.PERSON);
        Unit mine = world.createUnit(goldMine(), World.NEUTRAL_PLAYER, 15, 5);
        mine.setResourcesHeld(50_000);
        Unit worker = world.createUnit(peasant(), 0, 5, 5);

        assertTrue(world.orderHarvest(worker, mine.tileX(), mine.tileY()));
        int startX = worker.tileX();
        for (int cycle = 0; cycle < 60 && worker.tileX() == startX; cycle++) {
            world.tick();
        }
        while (worker.animation().unbreakable()) {
            world.tick();
        }

        assertEquals(mine, worker.resourceUnit(),
                "the local finder must rediscover the same usable mine");
        assertTrue(worker.pathLength() > 0,
                "SetGoal with the same CUnitPtr does not invalidate the"
                        + " PathfinderOutput: the next resource turn consumes"
                        + " its already-buffered heading");
    }

    /** The control: the computer's map-wide eye never lets go. */
    @Test
    @DisplayName("a computer's harvester marches to the same unseen mine without a doubt")
    void aComputersHarvesterKeepsTheUnseenMine() {
        World world = world(PudMap.PlayerType.COMPUTER);
        Unit mine = world.createUnit(goldMine(), World.NEUTRAL_PLAYER, 40, 5);
        mine.setResourcesHeld(50_000);
        Unit worker = world.createUnit(peasant(), 0, 5, 5);

        assertTrue(world.orderHarvest(worker, mine.tileX(), mine.tileY()),
                "the harvest order must be accepted");

        for (int cycle = 0; cycle < 60; cycle++) {
            world.tick();
        }

        assertEquals(Unit.Order.HARVEST, worker.order(),
                "a computer sees every uncloaked unit as a goal --"
                        + " IsVisibleAsGoal's middle clause -- so its walk must"
                        + " never drop the mine");
        assertEquals(mine, worker.resourceUnit(),
                "and it must still be bound to the mine it was sent to");
    }
}
