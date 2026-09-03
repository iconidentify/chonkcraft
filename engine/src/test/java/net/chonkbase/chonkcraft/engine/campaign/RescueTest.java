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

        Unit rescuedHall = world.createUnit(hall, 1, 10, 10);
        Unit villager = world.createUnit(peasant, 1, 20, 20);
        world.createUnit(footman, 0, 14, 10);

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 5; cycle++) {
            world.tick();
        }
        assertEquals(0, rescuedHall.player(),
                "the rescuable building itself must change hands");
        assertEquals(1, villager.player(),
                "BNE must not apply LegacyEngine's whole-player town-hall shortcut");
    }

    /**
     * Human 8, the first mission of Act III, starts the person with soldiers
     * but no workers. The village's peasants are already harvesting, so a
     * rescue check confined to the Still order makes the mission impossible.
     */
    @Test
    @DisplayName("Human 8's working village peasant joins the player's side")
    void humanEightWorkingPeasantCanBeRescued() {
        GameData data = load();
        PudMap source = data.campaignMap("campaigns/human/level08h");
        Mission mission = data.loadMission("campaigns/human/level08h",
                GameData.personIn(source), 1);
        Assumptions.assumeTrue(mission != null, "Human 8 did not load");
        World world = mission.world();

        int person = GameData.personIn(mission.source());
        Unit worker = unitAt(world, "unit-peasant", 2, 88, 78);
        UnitType footman = data.unitTypes().types().get("unit-footman");

        // The corpus numbers its first cycle after two untimed startup calls.
        mission.tick();
        mission.tick();
        for (int fixtureCycle = 1; fixtureCycle <= 142; fixtureCycle++) {
            if (fixtureCycle == 137) {
                world.createUnit(footman, person, 87, 83);
            }
            mission.tick();
            if (fixtureCycle < 142) {
                assertEquals(2, worker.player(),
                        "the worker transferred before native cycle " + fixtureCycle);
            }
            if (fixtureCycle == 141) {
                assertEquals(2686, worker.battleNetSequenceOffset(),
                        "the worker must be parked on action 23's pending OP0");
                assertEquals(1, worker.battleNetAnimationTimer());
            }
        }

        assertEquals(person, worker.player(),
                "a working prisoner must not wait for a Still order to be rescued");
        assertEquals(2, worker.rescuedFrom());
        assertEquals(Unit.Order.STILL, worker.order(),
                "native AssignToPlayer returns the rescued worker to Still");
        assertEquals(2595, worker.battleNetSequenceOffset());
        assertEquals(3, worker.battleNetAnimationTimer());
    }

    /** A building transfers by itself; rescuing one does not transfer its slot. */
    @Test
    @DisplayName("Human 8's farm transfers without transferring the whole village")
    void humanEightFarmRescueIsPerUnit() {
        GameData data = load();
        PudMap source = data.campaignMap("campaigns/human/level08h");
        Mission mission = data.loadMission("campaigns/human/level08h",
                GameData.personIn(source), 1);
        Assumptions.assumeTrue(mission != null, "Human 8 did not load");
        World world = mission.world();

        int person = GameData.personIn(mission.source());
        Unit farm = unitAt(world, "unit-farm", 2, 89, 68);
        Unit distantFarm = unitAt(world, "unit-farm", 2, 58, 76);
        UnitType footman = data.unitTypes().types().get("unit-footman");

        mission.tick();
        mission.tick();
        for (int fixtureCycle = 1; fixtureCycle <= 348; fixtureCycle++) {
            if (fixtureCycle == 345) {
                world.createUnit(footman, person, 91, 70);
            }
            mission.tick();
            if (fixtureCycle < 348) {
                assertEquals(2, farm.player(),
                        "the farm transferred before native cycle " + fixtureCycle);
            }
        }

        assertEquals(person, farm.player(), "the adjacent farm did not change hands");
        assertEquals(2, farm.rescuedFrom());
        assertEquals(2, distantFarm.player(),
                "rescuing one building must not invent a whole-slot handoff");
        assertFalse(distantFarm.wasRescued());
    }

    /** Presentation yields must not move native rescue earlier than OP0. */
    @Test
    @DisplayName("A nearby prisoner waits for its native rescue marker")
    void rescueDoesNotRunOnPresentationYields() {
        GameData data = load();
        PudMap source = data.campaignMap("campaigns/human/level09h");
        Mission mission = data.loadMission("campaigns/human/level09h",
                GameData.personIn(source), 1);
        Assumptions.assumeTrue(mission != null, "Human 9 did not load");
        World world = mission.world();

        Unit prisoner = unitAt(world, "unit-man-of-light", 3, 12, 6);
        int person = GameData.personIn(mission.source());

        mission.tick();
        mission.tick();
        for (int fixtureCycle = 1; fixtureCycle <= 6; fixtureCycle++) {
            mission.tick();
            assertEquals(fixtureCycle < 6 ? 3 : person, prisoner.player(),
                    "ownership diverged at native fixture cycle " + fixtureCycle);
        }
        assertEquals(3, prisoner.rescuedFrom());
    }

    private static Unit unitAt(World world, String ident, int player, int x, int y) {
        return world.unitsSnapshot().stream()
                .filter(unit -> unit.player() == player
                        && ident.equals(unit.type().ident())
                        && unit.tileX() == x && unit.tileY() == y)
                .findFirst().orElseThrow();
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
