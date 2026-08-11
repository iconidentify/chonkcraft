package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
 * What a unit does while its order is asleep.
 *
 * <p>Nothing, this implementation used to answer: a unit with cycles left on its wait
 * was skipped where it stood. Upstream stands it up and lets it breathe.
 * {@code COrder::IsWaiting} puts the
 * unit's own animation aside, runs {@code UnitShowAnimation(unit,
 * &unit.Type->Animations->Still)}, and decrements the wait; every order's
 * {@code Execute} opens with it, and with {@code StopWaiting} to put the
 * animation back.
 *
 * <p>That is not decoration, because of what ChonkCraft builds Still out of:
 * {@code "frame 0", "wait 4", "random-goto 99 no-rotate", "random-rotate 1",
 * "label no-rotate", "wait 1"} ({@code scripts/anim.legacy-declaration:31}). The loop is five
 * cycles long and takes one draw from the shared random stream each time
 * round, so a sleeping unit is spending the number both machines have to agree
 * on. A port that skipped it drew fewer times than upstream and every draw
 * either engine made afterwards was a different number.
 *
 * <p>Found that way, too, rather than by looking: on {@code (2)2-players} the
 * two engines' seeds parted company at cycle 83 with no unit out of place,
 * which is a peasant asleep for the ten cycles its blocked path had cost it,
 * breathing at cycles 83 and 88 in one engine and not the other.
 */
class SleepingUnitTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    /** ChonkCraft's own UnitStill, which is the whole point of the fixture. */
    private static AnimationSet breathing() {
        AnimationSet set = new AnimationSet("breathing");
        set.put(AnimationSet.State.STILL, Animation.parse("still", List.of(
                "frame 0", "wait 4", "random-goto 99 no-rotate",
                "random-rotate 1", "label no-rotate", "wait 1")));
        return set;
    }

    private static UnitType peasant() {
        UnitType type = new UnitType("unit-peasant");
        type.setTileSize(1, 1);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setNumDirections(8);
        type.setAnimationSet(breathing());
        return type;
    }

    /** One unit and nobody else, so every draw in the run belongs to it. */
    private static World world() {
        return new World(grass(16));
    }

    @Test
    @DisplayName("a unit asleep on its order still breathes, and the breathing costs a draw")
    void aSleepingUnitSpendsTheSharedNumber() {
        World world = world();
        Unit unit = world.createUnit(peasant(), 0, 5, 5);
        unit.setWaitCycles(16);

        int before = world.randomSeed();
        for (int cycle = 0; cycle < 16; cycle++) {
            world.tick();
        }

        assertNotEquals(before, world.randomSeed(),
                "the unit slept through sixteen cycles without drawing once. A unit whose"
                        + " order is waiting is not frozen: COrder::IsWaiting plays its Still"
                        + " animation over the top, and ChonkCraft's Still takes a random-goto"
                        + " every five cycles");
    }

    @Test
    @DisplayName("it breathes once every five cycles, which is how long the Still loop is")
    void theBreathingKeepsTheFiveCyclePeriod() {
        World world = world();
        Unit unit = world.createUnit(peasant(), 0, 5, 5);
        unit.setWaitCycles(16);

        // "wait 4" and "wait 1" either side of the random-goto: five cycles a
        // lap, one draw a lap. Counting the laps rather than just noticing the
        // seed moved is what says the period is right, and the period is what
        // decides whether this implementation's draws land on upstream's cycles.
        int draws = 0;
        int seed = world.randomSeed();
        for (int cycle = 0; cycle < 16; cycle++) {
            world.tick();
            if (world.randomSeed() != seed) {
                draws++;
                seed = world.randomSeed();
            }
        }

        assertEquals(3, draws,
                "sixteen cycles of a five-cycle loop is three draws, and this made " + draws);
    }

    private static UnitType goldMine() {
        UnitType type = new UnitType("unit-gold-mine");
        type.setTileSize(3, 3);
        type.setHitPoints(25500);
        type.setBuilding(true);
        type.setGivesResource(UnitType.Resource.GOLD);
        type.setCanHarvest(true);
        return type;
    }

    private static UnitType townHall() {
        UnitType type = new UnitType("unit-town-hall");
        type.setTileSize(4, 4);
        type.setHitPoints(1200);
        type.setBuilding(true);
        type.stores().add(UnitType.Resource.GOLD);
        return type;
    }

    /** A digger: the breathing peasant, taught to mine gold. */
    private static UnitType digger(int waitAtResource) {
        UnitType type = peasant();
        net.chonkbase.chonkcraft.engine.unit.ResourceInfo gold =
                new net.chonkbase.chonkcraft.engine.unit.ResourceInfo(UnitType.Resource.GOLD);
        gold.setCapacity(100);
        gold.setWaitAtResource(waitAtResource);
        gold.setWaitAtDepot(45);
        type.gathering().put(UnitType.Resource.GOLD, gold);
        return type;
    }

    @Test
    @DisplayName("the mine swallows the breathing: a peon inside it draws nothing at all")
    void theMineStayIsSilent() {
        World world = world();
        Unit mine = world.createUnit(goldMine(), 15, 8, 4);
        mine.setResourcesHeld(25000);
        Unit peon = world.createUnit(digger(150), 0, 7, 5);
        world.orderHarvest(peon, 8, 4);

        int walked = 0;
        while (peon.isOnMap() && walked++ < 30) {
            world.tick();
        }
        assertFalse(peon.isOnMap(), "the fixture's peon never went into the mine");

        int before = world.randomSeed();
        for (int cycle = 0; cycle < 15; cycle++) {
            world.tick();
        }
        assertEquals(before, world.randomSeed(),
                "a peon down the mine spent a number from the shared stream. The stay"
                        + " inside is not a wait on the unit: GatherResource nulls the"
                        + " animation for a harvester that went in"
                        + " (action_resource.cpp:713-717) and counts TimeToHarvest with"
                        + " nothing breathing, where the ten-cycle waits outside run the"
                        + " Still loop. On campaigns/human-exp/levelx03h two peons enter"
                        + " their mines during cycle 35, and this port's each used to"
                        + " wiggle unseen on cycle 40 -- two draws upstream never made");
    }

    @Test
    @DisplayName("the stay underground is the data's number less one, and the trip home starts on the surfacing cycle")
    void theStayEndsOnTheDataNumberAndTheWalkStartsAtOnce() {
        World world = world();
        world.createUnit(townHall(), 0, 2, 2);
        Unit mine = world.createUnit(goldMine(), 15, 9, 3);
        mine.setResourcesHeld(25000);
        Unit peon = world.createUnit(digger(10), 0, 8, 5);
        world.orderHarvest(peon, 9, 3);

        int walked = 0;
        while (peon.isOnMap() && walked++ < 30) {
            world.tick();
        }
        assertFalse(peon.isOnMap(), "the fixture's peon never went into the mine");

        // StartGathering sets TimeToHarvest and GatherResource decrements it
        // on that same cycle, so a ten-cycle wait is ten invisible end-of-
        // cycle states, not eleven: the levelx03h peons that went under
        // during cycle 35 with a hundred and fifty to serve surface during
        // 185, and this implementation used to hold them one cycle longer.
        int under = 0;
        while (!peon.isOnMap() && under++ < 30) {
            world.tick();
        }
        assertEquals(10, under,
                "a ten-cycle mine stay kept the peon underground for " + under
                        + " cycles. TimeToHarvest starts counting on the entry cycle"
                        + " (action_resource.cpp:607-611, 719)");

        // Execute falls straight through from SUB_STOP_GATHERING into
        // MoveToDepot, so the cycle that surfaces the peon also takes its
        // first step home: upstream's peon ends cycle 185 already on the
        // next tile.
        assertTrue(peon.isMoving() || peon.pathLength() > 0,
                "the peon surfaced and then stood a cycle at the mine's foot; upstream"
                        + " is already walking to the hall on the cycle it comes out"
                        + " (action_resource.cpp:1355-1376)");
    }

    @Test
    @DisplayName("the hall door costs the spent route its ten cycles before the gold counts")
    void theArrivalWaitsOutTheSpentRouteBeforeBanking() {
        World world = world();
        Unit hall = world.createUnit(townHall(), 0, 2, 2);
        Unit mine = world.createUnit(goldMine(), 15, 9, 3);
        mine.setResourcesHeld(25000);
        Unit peon = world.createUnit(digger(10), 0, 8, 5);
        world.orderHarvest(peon, 9, 3);

        // The walk is paid out before the arrival counts, at the hall as at
        // the mine: the peon that stands at the door with its load serves
        // the spent route's ten cycles before the wake that banks. On
        // levelx03h the peon beside the hall at 205 banks during 216
        // upstream, and this implementation used to have the gold in the ledger at
        // 206.
        int atTheDoor = 0;
        int ticks = 0;
        while (ticks++ < 200 && !(peon.carried() == 0 && !peon.isOnMap()
                && peon.worksite() == hall)) {
            world.tick();
            if (peon.isOnMap() && peon.carried() > 0 && !peon.isMoving()
                    && peon.distanceTo(hall) <= 1) {
                atTheDoor++;
            }
        }
        assertFalse(peon.isOnMap(), "the fixture's peon never banked its load");
        // Twelve, not ten: the ten owed by the spent route, plus the two
        // cycles the finished move animation takes to settle before the
        // route reads as spent at all. Without the wait the whole count is
        // nought -- the load banks the tick the peon reaches the door.
        assertEquals(12, atTheDoor,
                "the peon stood at the hall's door for " + atTheDoor + " cycles;"
                        + " the spent route owes ten before PF_REACHED answers and the"
                        + " load is banked");
    }

    @Test
    @DisplayName("a peon with gold and no hall anywhere starts breathing the cycle its trip dies")
    void aStrandedTripDiesIntoBreathingAtOnce() {
        World world = world();
        Unit mine = world.createUnit(goldMine(), 15, 9, 3);
        mine.setResourcesHeld(25000);
        Unit peon = world.createUnit(digger(10), 0, 8, 5);
        world.orderHarvest(peon, 9, 3);

        int walked = 0;
        while (peon.isOnMap() && walked++ < 30) {
            world.tick();
        }
        assertFalse(peon.isOnMap(), "the fixture's peon never went into the mine");
        while (!peon.isOnMap() && walked++ < 60) {
            world.tick();
        }
        assertTrue(peon.isOnMap(), "the fixture's peon never came back out");
        assertEquals(100, peon.carried(), "and it should be holding the load it cannot bank");

        // The next cycle ends the harvest -- StopGathering finds no deposit
        // and marks the order finished -- and upstream's next-order advance
        // runs the new Still on that same cycle, so the Still loop's first
        // draw lands four cycles later: frame 0 and "wait 4", then the
        // random-goto. On levelx03h the stranded south-west peon surfaces
        // during 185 and breathes at 190; a port that spent the transition
        // cycle only changing orders breathed at 191, one draw late forever.
        int draws = 0;
        int firstDrawAfter = -1;
        int seed = world.randomSeed();
        for (int cycle = 1; cycle <= 6; cycle++) {
            world.tick();
            if (world.randomSeed() != seed) {
                draws++;
                seed = world.randomSeed();
                if (firstDrawAfter < 0) {
                    firstDrawAfter = cycle;
                }
            }
        }
        assertEquals(1, draws, "one lap of the Still loop fits in six cycles");
        assertEquals(5, firstDrawAfter,
                "the first breath after surfacing came on the wrong cycle: the give-up"
                        + " cycle itself must run the Still animation it becomes");
    }

    @Test
    @DisplayName("a send-home with nowhere to go lives one visible cycle, then breathes as Still")
    void aGoallessSendHomeDiesOnUpstreamsCadence() {
        World world = world();
        Unit mine = world.createUnit(goldMine(), 15, 9, 3);
        mine.setResourcesHeld(25000);
        Unit peon = world.createUnit(digger(10), 0, 8, 5);
        world.orderHarvest(peon, 9, 3);
        int walked = 0;
        while (walked++ < 90 && peon.order() != Unit.Order.STILL) {
            world.tick();
        }
        assertEquals(100, peon.carried(),
                "the fixture wants a stranded peon standing with a full load");

        // Anchor on the breathing itself: the Still loop draws every five
        // cycles, and the send-home must not restart it -- only still it for
        // exactly the one cycle the dead errand occupies.
        int seed = world.randomSeed();
        int anchor = 0;
        while (anchor++ < 6 && world.randomSeed() == seed) {
            world.tick();
            if (world.randomSeed() != seed) {
                break;
            }
        }
        seed = world.randomSeed();

        // The AI's census sends it home anyway, every collect think. The
        // order is born already given up -- NewActionReturnGoods finds no
        // deposit and starts in SUB_UNREACHABLE_DEPOT
        // but it keeps the load, stays the
        // unit's visible order to the end of the cycle that executes it, and
        // only the next cycle's advance replaces it with Still. On
        // campaigns/human-exp/levelx03h the send-home at 247 reads resource
        // at 248 and still from 249.
        assertTrue(world.orderReturnGoods(peon), "the send-home was refused");
        world.tick();
        assertEquals(Unit.Order.RETURN_GOODS, peon.order(),
                "the dead-on-arrival errand should still be the visible order for"
                        + " the cycle that executed it");
        world.tick();
        assertEquals(Unit.Order.STILL, peon.order(),
                "and the next cycle's advance replaces it with Still");
        assertEquals(100, peon.carried(),
                "a goalless give-up has nothing for DropResource to drop");

        // The errand's one cycle ran no animation at all -- upstream's
        // Execute goes straight to ResourceGiveUp and returns -- so the
        // loop's next draw lands six cycles after the anchor draw instead
        // of five: stilled for exactly one cycle, never restarted.
        int draws = 0;
        int firstDrawAfter = -1;
        for (int cycle = 3; cycle <= 7; cycle++) {
            world.tick();
            if (world.randomSeed() != seed) {
                draws++;
                seed = world.randomSeed();
                if (firstDrawAfter < 0) {
                    firstDrawAfter = cycle;
                }
            }
        }
        assertEquals(1, draws, "one lap of the Still loop fits in the window");
        assertEquals(6, firstDrawAfter,
                "the next breath after the send-home came on the wrong cycle: the"
                        + " dead errand stills the loop for its one cycle and must not"
                        + " restart it");
    }

    @Test
    @DisplayName("the hall does not: a peon banking its load breathes on inside the depot")
    void theDepotStayStillBreathes() {
        World world = world();
        world.createUnit(townHall(), 0, 2, 2);
        Unit mine = world.createUnit(goldMine(), 15, 9, 3);
        mine.setResourcesHeld(25000);
        // Five cycles in the mine, so the trip fits the test: in, out with a
        // hundred gold, and into the hall for the forty-five cycle bank wait.
        Unit peon = world.createUnit(digger(5), 0, 8, 5);
        world.orderHarvest(peon, 9, 3);

        int walked = 0;
        while (walked++ < 90
                && !(peon.isOnMap() == false && peon.returningToDepot() && peon.carried() == 0)) {
            world.tick();
        }
        assertFalse(peon.isOnMap(),
                "the fixture's peon never got inside the hall with its load banked");

        int draws = 0;
        int seed = world.randomSeed();
        for (int cycle = 0; cycle < 12; cycle++) {
            world.tick();
            if (world.randomSeed() != seed) {
                draws++;
                seed = world.randomSeed();
            }
        }
        assertTrue(draws >= 2,
                "a peon inside the depot went quiet. That stay is unit.Wait, and"
                        + " COrder::IsWaiting plays the Still loop over any wait"
                        + " (actions.cpp:137-149) -- removed or not -- so the bank visit"
                        + " breathes once every five cycles like any other sleep, and"
                        + " silencing it desyncs every map with a working economy");
    }
}
