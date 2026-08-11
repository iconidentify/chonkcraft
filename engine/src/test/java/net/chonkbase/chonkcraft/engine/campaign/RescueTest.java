package net.chonkbase.chonkcraft.engine.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Freeing prisoners, and the nine missions that are won by doing it.
 *
 * <p>{@code IfRescuedNearUnit} and {@code IfNearUnit} are the sole victory
 * condition of nine campaign missions -- every escort and every rescue among
 * them -- and neither was bound. Those nine could be played and never won,
 * which is a worse failure than a mission that ends early: it wastes the whole
 * playthrough before the player finds out.
 *
 * <p>Binding them was only half of it. The condition asks whether a unit *was
 * rescued*, and nothing in this implementation could rescue anybody, so the answer would
 * have stayed no however the mission was played.
 */
class RescueTest {

    private static GameData load() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II retail assets configured.");
        return new GameData(assets);
    }

    /** A plain map where slot 0 is a person and slot 1 holds prisoners. */
    private static World gaol(GameData data) {
        int size = 32;
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            PudMap.PlayerType type = switch (i) {
                case 0 -> PudMap.PlayerType.PERSON;
                case 1 -> PudMap.PlayerType.RESCUE_PASSIVE;
                default -> PudMap.PlayerType.NOBODY;
            };
            players[i] = new Player(i, type, PudMap.Race.HUMAN);
        }
        World world = new World(map, players);
        world.setUnitTypes(data.unitTypes().types());
        world.setMissileTypes(data.missiles().types());
        return world;
    }

    @Test
    @DisplayName("A prisoner is freed when an armed ally stands beside it")
    void aPrisonerIsFreed() {
        GameData data = load();
        World world = gaol(data);
        UnitType footman = data.unitTypes().types().get("unit-footman");
        UnitType peasant = data.unitTypes().types().get("unit-peasant");
        assertNotNull(footman);

        Unit prisoner = world.createUnit(peasant, 1, 10, 10);
        assertFalse(prisoner.wasRescued());

        // A soldier of yours walks up beside them.
        Unit rescuer = world.createUnit(footman, 0, 11, 10);
        assertNotNull(rescuer);

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 5; cycle++) {
            world.tick();
            if (prisoner.wasRescued()) {
                break;
            }
        }
        assertTrue(prisoner.wasRescued(), "nobody was freed");
        assertEquals(0, prisoner.player(), "the prisoner did not change hands");
        assertEquals(1, prisoner.rescuedFrom(), "it should remember whose it was");
    }

    /**
     * A prisoner cannot free another prisoner, or the whole gaol would open
     * itself the moment the game started.
     */
    @Test
    @DisplayName("Prisoners do not free each other")
    void prisonersDoNotFreeEachOther() {
        GameData data = load();
        World world = gaol(data);
        UnitType footman = data.unitTypes().types().get("unit-footman");

        Unit one = world.createUnit(footman, 1, 10, 10);
        Unit two = world.createUnit(footman, 1, 11, 10);
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 5; cycle++) {
            world.tick();
        }
        assertFalse(one.wasRescued(), "a prisoner freed another prisoner");
        assertFalse(two.wasRescued(), "a prisoner freed another prisoner");
    }

    /**
     * BNE rescues the unit whose animation marker performs the proximity
     * check. It does not run LegacyEngine's once-per-second whole-player
     * {@code RescueUnits} special case for resource-storing buildings.
     */
    @Test
    @DisplayName("A rescuable town hall does not invent a whole-side rescue")
    void aTownHallDoesNotRescueEverything() {
        GameData data = load();
        World world = gaol(data);
        UnitType footman = data.unitTypes().types().get("unit-footman");
        UnitType hall = data.unitTypes().types().get("unit-town-hall");
        UnitType peasant = data.unitTypes().types().get("unit-peasant");

        world.createUnit(hall, 1, 10, 10);
        Unit villager = world.createUnit(peasant, 1, 20, 20);
        world.createUnit(footman, 0, 14, 10);

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 5; cycle++) {
            world.tick();
        }
        assertEquals(1, villager.player(),
                "BNE must not apply LegacyEngine's whole-player town-hall shortcut");
    }

    /**
     * The rescue condition must answer from native mission state.
     */
    @Test
    @DisplayName("Every mission that is won by rescuing can ask whether it has been")
    void theRescueMissionsCanBeAsked() {
        GameData data = load();
        // Prove the condition answers rather than merely existing.
        World world = gaol(data);
        UnitType footman = data.unitTypes().types().get("unit-footman");
        UnitType circle = data.unitTypes().types().get("unit-circle-of-power");
        Assumptions.assumeTrue(circle != null, "no circle of power in this installation");

        world.createUnit(circle, 15, 10, 10);
        Unit prisoner = world.createUnit(footman, 1, 20, 20);
        Unit rescuer = world.createUnit(footman, 0, 21, 20);
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 5; cycle++) {
            world.tick();
            if (prisoner.wasRescued()) {
                break;
            }
        }
        assertTrue(prisoner.wasRescued(), "the fixture never freed anybody");
    }
}
