package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Locks retail BNE's hall train clock: TIME*2 yields, one per animation
 * yield, not the old (TIME-1)*6+1 Still drip.
 *
 * <p>Native {@code 0x40dcd0} stores TIME*2 at the production goal. Order 37
 * ({@code 0x40e1e0}) adds one only when {@code 0x402440} returns 1 -- the
 * animation yield after a wait. Orc 1 player peons issued at cycles 5 and
 * 12 both spend 463 cycles in order 37. The old drip finished a TIME-45
 * peon around 265 cycles.
 */
class BattleNetTrainDurationTest {

    @Test
    @DisplayName("a forty-five-time worker exits 468 cycles after payment")
    void aFortyFiveTimeWorkerUsesTheNativePaymentToDropoutClock() {
        World world = new World(grass(24));
        UnitType hallType = hall("unit-great-hall");
        UnitType peonType = worker("unit-peon", 45);
        world.setTrainers(Map.of("unit-peon", Set.of("unit-great-hall")));
        Unit hall = world.createUnit(hallType, 0, 4, 4);
        world.player(0).set(UnitType.Resource.GOLD, 1000);
        assertTrue(world.orderTrain(hall, peonType),
                "the hall refused a paid peon");

        int cycles = 0;
        while (!hasLiveWorkerBesidesHall(world, hall) && cycles < 600) {
            world.tick();
            cycles++;
        }

        assertEquals(468, cycles,
                "sealed Human 4, Orc 4, and XOrc 4 recordings all keep the "
                        + "paid worker inside for eighteen cycles longer than "
                        + "the old 450-cycle animation loop");
    }

    @Test
    @DisplayName("a forty-five-time peon is still in the hall after three hundred cycles")
    void aFortyFiveTimePeonIsStillInTheHallAfterThreeHundredCycles() {
        World world = new World(grass(24));
        UnitType hallType = hall("unit-great-hall");
        UnitType peonType = worker("unit-peon", 45);
        world.setTrainers(Map.of("unit-peon", Set.of("unit-great-hall")));
        Unit hall = world.createUnit(hallType, 0, 4, 4);
        world.player(0).set(UnitType.Resource.GOLD, 1000);
        assertTrue(world.orderTrain(hall, peonType),
                "the hall refused a paid peon");

        for (int i = 0; i < 300; i++) {
            world.tick();
        }
        assertTrue(hall.producing() != null,
                "a TIME-45 peon used to walk out around cycle 265; retail "
                        + "keeps him in the hall through three hundred cycles");
        assertFalse(hasLiveWorkerBesidesHall(world, hall),
                "the peon walked out before three hundred cycles");
    }

    @Test
    @DisplayName("a shorter train walks out while a longer train is still in the hall")
    void aShorterTrainWalksOutWhileALongerTrainIsStillInTheHall() {
        World world = new World(grass(32));
        UnitType hallType = hall("unit-great-hall");
        UnitType barracksType = hall("unit-human-barracks");
        barracksType.setTileSize(3, 3);
        UnitType peonType = worker("unit-peon", 45);
        UnitType fastType = worker("unit-attack-peon", 20);
        world.setTrainers(Map.of(
                "unit-peon", Set.of("unit-great-hall"),
                "unit-attack-peon", Set.of("unit-human-barracks")));
        Unit hall = world.createUnit(hallType, 0, 2, 2);
        Unit barracks = world.createUnit(barracksType, 0, 10, 2);
        world.player(0).set(UnitType.Resource.GOLD, 2000);
        assertTrue(world.orderTrain(hall, peonType),
                "the hall refused the long peon");
        assertTrue(world.orderTrain(barracks, fastType),
                "the barracks refused the short trainee");

        for (int i = 0; i < 150; i++) {
            world.tick();
        }
        assertTrue(barracks.producing() != null,
                "a TIME-20 trainee used to walk out around cycle 115; retail "
                        + "is still on TIME*2 yields at one hundred and fifty");

        boolean shortOut = false;
        boolean longStillIn = false;
        for (int i = 0; i < 550; i++) {
            world.tick();
            boolean shortLive = liveOfType(world, "unit-attack-peon") > 0;
            boolean longLive = liveOfType(world, "unit-peon") > 0;
            if (shortLive && barracks.producing() == null && hall.producing() != null
                    && !longLive) {
                shortOut = true;
                longStillIn = true;
                break;
            }
        }
        assertTrue(shortOut && longStillIn,
                "a TIME-20 trainee must walk out while a TIME-45 peon is "
                        + "still in the hall -- TIME*2 yields, not a shared delay");
        assertNotNull(hall.producing(),
                "the long peon must still be training when the short one walks out");
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

    private static UnitType hall(String ident) {
        UnitType type = new UnitType(ident);
        type.setTileSize(4, 4);
        type.setHitPoints(1200);
        type.setBuilding(true);
        type.setLandUnit(true);
        type.setSupply(5);
        type.stores().add(UnitType.Resource.GOLD);
        type.setAnimationSet(buildingTrainSet(ident));
        return type;
    }

    private static UnitType worker(String ident, int time) {
        UnitType type = new UnitType(ident);
        type.setTileSize(1, 1);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setDemand(1);
        type.costs().put(UnitType.Resource.GOLD, 400);
        type.costs().put(UnitType.Resource.TIME, time);
        return type;
    }

    /**
     * The shipped building Train program: wait 4 then wait 1. Native
     * {@code 0x402440} opcode 0 is that wait-1 yield.
     */
    private static AnimationSet buildingTrainSet(String name) {
        AnimationSet set = new AnimationSet(name);
        Animation train = new Animation(name + ".Train", List.of(
                new Animation.Instruction(Animation.Kind.FRAME, 0, "0"),
                new Animation.Instruction(Animation.Kind.WAIT, 4, "4"),
                new Animation.Instruction(Animation.Kind.FRAME, 0, "0"),
                new Animation.Instruction(Animation.Kind.WAIT, 1, "1")));
        set.put(AnimationSet.State.TRAIN, train);
        set.put(AnimationSet.State.STILL, train);
        return set;
    }

    private static boolean hasLiveWorkerBesidesHall(World world, Unit hall) {
        for (Unit unit : world.units()) {
            if (unit != hall && unit.isAlive() && !unit.type().building()) {
                return true;
            }
        }
        return false;
    }

    private static int liveOfType(World world, String ident) {
        int count = 0;
        for (Unit unit : world.units()) {
            if (unit.isAlive() && ident.equals(unit.type().ident())) {
                count++;
            }
        }
        return count;
    }
}
