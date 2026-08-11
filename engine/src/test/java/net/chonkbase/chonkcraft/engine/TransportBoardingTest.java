package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Getting a soldier onto a boat.
 *
 * <p>{@code World.board} was written, documented and had no callers. The one
 * method that could put a unit on a transport could not be reached from the
 * game at all, so a right click on a boat was read as an order to walk into
 * the sea and the unit stopped at the water's edge. Nothing failed and nothing
 * said anything; it simply was not possible.
 *
 * <p>These start from the order rather than from {@code board}, because the
 * order is the part that was missing. A test calling {@code board} directly
 * would have passed throughout.
 */
class TransportBoardingTest {

    /** A coast: land on the left half, water on the right. */
    private static World coastline(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(x < size / 2
                        ? TileFlag.LAND_ALLOWED
                        : TileFlag.COAST_ALLOWED | TileFlag.WATER_ALLOWED);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i < 2 ? PudMap.PlayerType.PERSON : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        return new World(map, players);
    }

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    /** Runs the world until the condition holds or the patience runs out. */
    private static boolean runUntil(World world, int cycles,
            java.util.function.BooleanSupplier done) {
        for (int i = 0; i < cycles; i++) {
            world.tick();
            if (done.getAsBoolean()) {
                return true;
            }
        }
        return done.getAsBoolean();
    }

    @Test
    @DisplayName("A soldier ordered onto a transport walks to it and gets aboard")
    void aSoldierBoards() {
        GameData data = load();
        var types = data.unitTypes().types();
        UnitType footman = types.get("unit-footman");
        UnitType transport = types.get("unit-human-transport");
        assertNotNull(footman);
        assertNotNull(transport);

        World world = coastline(32);
        world.setUnitTypes(types);
        // The boat sits on the water just past the shoreline; the soldier is
        // several squares inland, so the walk is a real one.
        Unit boat = world.createUnit(transport, 0, 16, 10);
        Unit soldier = world.createUnit(footman, 0, 10, 10);
        assertNotNull(boat);
        assertNotNull(soldier);

        assertTrue(world.orderBoard(soldier, boat), "the order was refused");
        assertTrue(runUntil(world, World.CYCLES_PER_SECOND * 30, soldier::isAboard),
                "the soldier never got aboard; it stopped at "
                        + soldier.tileX() + "," + soldier.tileY()
                        + " with the boat at " + boat.tileX() + "," + boat.tileY());
        assertEquals(1, boat.cargo().size());
        assertTrue(boat.cargo().contains(soldier));
        assertFalse(soldier.isOnMap(), "a boarded unit leaves the map entirely");
    }

    @Test
    @DisplayName("A full transport takes nobody else")
    void aFullBoatRefuses() {
        GameData data = load();
        var types = data.unitTypes().types();
        UnitType footman = types.get("unit-footman");
        UnitType transport = types.get("unit-human-transport");

        World world = coastline(32);
        world.setUnitTypes(types);
        Unit boat = world.createUnit(transport, 0, 16, 10);
        int capacity = transport.maxOnBoard();
        assertTrue(capacity > 0, "a transport that carries nobody is not a transport");

        // Each one stands on the shore square beside the boat in turn. A unit
        // that boards leaves the map, so the square is free for the next.
        for (int i = 0; i < capacity; i++) {
            Unit aboard = world.createUnit(footman, 0, 15, 10);
            assertTrue(world.board(aboard, boat),
                    "could not fill the boat; stopped after " + i);
        }
        assertFalse(boat.hasRoom());

        Unit late = world.createUnit(footman, 0, 10, 10);
        world.orderBoard(late, boat);
        runUntil(world, World.CYCLES_PER_SECOND * 15, () -> false);
        assertFalse(late.isAboard(), "somebody boarded a full boat");
        assertEquals(capacity, boat.cargo().size());
    }

    @Test
    @DisplayName("A boat does not carry what it is not meant to")
    void aBoatRefusesWhatItCannotCarry() {
        GameData data = load();
        var types = data.unitTypes().types();
        UnitType transport = types.get("unit-human-transport");
        UnitType building = types.get("unit-farm");

        World world = coastline(32);
        world.setUnitTypes(types);
        Unit boat = world.createUnit(transport, 0, 16, 10);
        Unit farm = world.createUnit(building, 0, 8, 8);
        assertFalse(world.orderBoard(farm, boat), "a farm was ordered onto a boat");
        assertFalse(world.board(farm, boat));
    }

    @Test
    @DisplayName("Passengers come ashore again")
    void passengersLandAgain() {
        GameData data = load();
        var types = data.unitTypes().types();
        UnitType footman = types.get("unit-footman");
        UnitType transport = types.get("unit-human-transport");

        World world = coastline(32);
        world.setUnitTypes(types);
        Unit boat = world.createUnit(transport, 0, 16, 10);
        Unit soldier = world.createUnit(footman, 0, 10, 10);

        world.orderBoard(soldier, boat);
        assertTrue(runUntil(world, World.CYCLES_PER_SECOND * 30, soldier::isAboard),
                "never boarded, so landing cannot be tested");

        assertTrue(world.unloadOne(boat, soldier), "the soldier would not come ashore");
        assertFalse(soldier.isAboard());
        assertTrue(soldier.isOnMap(), "a landed unit is back on the map");
        assertTrue(boat.cargo().isEmpty());
    }
}
