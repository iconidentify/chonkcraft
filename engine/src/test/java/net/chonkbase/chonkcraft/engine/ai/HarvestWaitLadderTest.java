package net.chonkbase.chonkcraft.engine.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.engine.World;
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
 * A computer worker whose harvest walk keeps waiting shoves the body in its
 * way on the fifth wait, and a banked load starts the count over.
 *
 * <p>The {@code PF_WAIT} arms of {@code COrder_Resource}'s walks share the
 * order's otherwise unused {@code Range} as a counter: every wait the walk
 * answers steps it up, and the fifth resets it and calls
 * {@code AiCanNotMove}, whose
 * {@code AiMoveUnitInTheWay} spends one draw per candidate blocker and one
 * for the pick. And the counter does not live
 * for the round trip: a finished delivery sends the order back through
 * {@code SUB_START_RESOURCE}, whose
 * {@code ActionResourceInit} zeroes it with everything else.
 *
 * <p>Measured on campaigns/orc/level08o: the enemy oil tanker 116, stuck
 * behind its fellow 120, climbs waits at cycles 710, 721, 732 and 743 and
 * shoves on 754 -- two draws, and 120 sails off under a plain move. The
 * tanker had banked at 582, which is why the climb starts there and not at
 * its rung from cycle 470; a port that carried the old rungs across the
 * visit shoved at 732, and one with no ladder at all never shoved, and
 * either way every number drawn after 754 belonged to a different game.
 */
class HarvestWaitLadderTest {

    /**
     * A field with a one-square corridor down x=10 between the mine in the
     * north and open ground in the south, so a worker's walk has exactly one
     * way through and a body in the corridor is a genuine jam. One niche is
     * carved beside the corridor's mouth at 11,5, a square out of the walk's
     * way that a bystander can stand in.
     */
    private static GameMap corridor() {
        GameMap map = new GameMap(24, 24, new Tileset());
        for (int y = 0; y < 24; y++) {
            for (int x = 0; x < 24; x++) {
                map.field(x, y).setFlags(y <= 13 ? TileFlag.UNPASSABLE : TileFlag.LAND_ALLOWED);
            }
        }
        for (int y = 5; y <= 13; y++) {
            map.field(10, y).setFlags(TileFlag.LAND_ALLOWED);
        }
        map.field(11, 5).setFlags(TileFlag.LAND_ALLOWED);
        for (int y = 2; y <= 4; y++) {
            for (int x = 9; x <= 11; x++) {
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

    /** A peon that mines gold and is quick about the stays at either end. */
    private static UnitType peon() {
        UnitType type = new UnitType("unit-peon");
        type.setTileSize(1, 1);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setSightRange(12);
        type.setAnimationSet(walker());
        ResourceInfo gold = new ResourceInfo(Resource.GOLD);
        gold.setCapacity(100);
        gold.setWaitAtResource(2);
        gold.setWaitAtDepot(2);
        type.gathering().put(Resource.GOLD, gold);
        return type;
    }

    private static UnitType greatHall() {
        UnitType type = new UnitType("unit-great-hall");
        type.setTileSize(4, 4);
        type.setHitPoints(1200);
        type.setBuilding(true);
        type.stores().add(Resource.GOLD);
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

    /**
     * The gap between one refused walk and the next: the wait is ten cycles
     * and the consult that answers it is the eleventh.
     */
    private static final int BEAT = 11;

    /** One draw to aim the shove's search, one to pick who is moved. */
    private static final int DRAWS_PER_SHOVE = 2;

    @Test
    @DisplayName("the fifth wait of a jammed walk shoves the body out of the corridor, at two numbers")
    void theFifthWaitShovesTheBlocker() {
        World world = new World(corridor());
        world.fog().revealAll(0);
        world.enableAi(0);
        Unit mine = world.createUnit(goldMine(), World.NEUTRAL_PLAYER, 9, 2);
        mine.setResourcesHeld(50_000);
        Unit worker = world.createUnit(peon(), 0, 10, 16);
        assertTrue(world.orderHarvest(worker, mine.tileX(), mine.tileY()),
                "the mine must take the worker, or nothing here measures");
        // The walk lays its course up the corridor first; the jam arrives
        // after, as a fellow stopping mid-corridor does, because a course can
        // only be laid through ground no one is standing on.
        world.tick();
        Unit blocker = world.createUnit(peon(), 0, 10, 8);
        world.orderStandGround(blocker);

        // Walk into the jam, and find the first refused walk: the worker
        // stops a square short of the body and sleeps its ten.
        int firstWait = -1;
        for (int cycle = 2; cycle <= 60 && firstWait < 0; cycle++) {
            world.tick();
            if (worker.distanceTo(blocker) == 1 && worker.waitCycles() > 0) {
                firstWait = cycle;
            }
        }
        assertTrue(firstWait > 0, "the worker never reached the jam and waited behind it");

        // Four more waits climb the ladder, eleven cycles apart, with nobody
        // moved; the fifth buys the shove. A shoved unit is flushed -- it
        // abandons its stand-ground for the move it was handed -- so the
        // order flip is the mark of it.
        int shoveCycle = firstWait + 4 * BEAT;
        boolean shoved = false;
        long draws = 0;
        for (int cycle = firstWait; cycle < shoveCycle + BEAT && !shoved; cycle++) {
            long before = world.randomDraws();
            world.tick();
            if (blocker.order() != Unit.Order.STAND_GROUND) {
                shoved = true;
                draws = world.randomDraws() - before;
                assertEquals(shoveCycle, cycle + 1,
                        "the body was shoved on cycle " + (cycle + 1) + "; five waits, eleven"
                                + " cycles apart from the first at " + firstWait
                                + ", put the shove on " + shoveCycle);
            }
        }
        assertTrue(shoved,
                "five refused walks came and went and the body in the corridor was never"
                        + " shoved; the fifth wait buys AiCanNotMove and the jam stands for"
                        + " ever without it");
        assertEquals(DRAWS_PER_SHOVE, draws,
                "the shove spent " + draws + " numbers where upstream spends two -- one to"
                        + " aim the search for a free square and one to pick who is moved");
    }

    @Test
    @DisplayName("a banked load starts the climb over rather than carrying old rungs")
    void aDeliveryResetsTheClimb() {
        World world = new World(corridor());
        world.enableAi(0);
        Unit mine = world.createUnit(goldMine(), World.NEUTRAL_PLAYER, 9, 2);
        mine.setResourcesHeld(50_000);
        world.createUnit(greatHall(), 0, 13, 15);
        Unit worker = world.createUnit(peon(), 0, 10, 16);
        assertTrue(world.orderHarvest(worker, mine.tileX(), mine.tileY()),
                "the mine must take the worker, or nothing here measures");

        // The first trip runs clear: up the corridor, into the mine, back
        // down, and the hundred banked at the hall. The walk's own route-end
        // waits -- the arrival at the mine, the doorstep at the hall -- climb
        // the ladder as every wait does, and the delivery must hand it back.
        boolean entered = false;
        for (int cycle = 0; cycle < 300 && !entered; cycle++) {
            world.tick();
            entered = !worker.isOnMap();
        }
        assertTrue(entered, "the worker never got into the mine");
        int banked = 0;
        for (int cycle = 0; cycle < 400 && banked == 0; cycle++) {
            world.tick();
            banked = world.player(0).get(Resource.GOLD);
        }
        assertEquals(100, banked, "the worker's hundred gold never reached the hall");
        boolean outAgain = false;
        for (int cycle = 0; cycle < 100 && !outAgain; cycle++) {
            world.tick();
            outAgain = worker.isOnMap();
        }
        assertTrue(outAgain, "the worker never came back out of the hall");

        // The second trip lays its course, and then the corridor is jammed
        // behind it. If the delivery handed the ladder back, this jam is
        // five fresh waits from a shove; if the first trip's rungs were
        // carried across the bank, it is two or three, and the body is
        // thrown aside a whole wait-beat early.
        boolean planned = false;
        for (int cycle = 0; cycle < 100 && !planned; cycle++) {
            world.tick();
            planned = worker.pathLength() > 0;
        }
        assertTrue(planned, "the worker never set off on its second trip");
        Unit blocker = world.createUnit(peon(), 0, 10, 8);
        world.orderStandGround(blocker);
        int nextWait = -1;
        for (int cycle = 0; cycle < 60 && nextWait < 0; cycle++) {
            world.tick();
            assertTrue(blocker.order() == Unit.Order.STAND_GROUND,
                    "the fresh jam was shoved before the second trip had waited even once;"
                            + " the banked load should have started the climb over");
            if (worker.distanceTo(blocker) == 1 && worker.waitCycles() > 0) {
                nextWait = cycle;
            }
        }
        assertTrue(nextWait > 0, "the worker never walked its second trip into the jam");
        int shoveCycle = nextWait + 4 * BEAT;
        boolean shoved = false;
        for (int cycle = nextWait; cycle < shoveCycle + BEAT && !shoved; cycle++) {
            world.tick();
            if (blocker.order() != Unit.Order.STAND_GROUND) {
                shoved = true;
                assertEquals(shoveCycle, cycle + 1,
                        "the second trip's jam was shoved on cycle " + (cycle + 1)
                                + " counting from the trip's first wait at " + nextWait
                                + "; a ladder handed back at the bank owes five fresh waits,"
                                + " which lands the shove on " + shoveCycle
                                + ", and an earlier one is the round trip's rungs leaking"
                                + " across the delivery");
            }
        }
        assertTrue(shoved,
                "the second trip's jam was never shoved at all; the fifth fresh wait"
                        + " should have bought it");
    }
}
