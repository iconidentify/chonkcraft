package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * A building only counts once it is built.
 *
 * <p>Upstream is unusually explicit about this, because it is a special case
 * rather than a consequence of anything. Starting a building runs
 * {@code build->Player->UnitTypesCount[type.Slot]--} under the comment "HACK:
 * the building is not ready yet", and finishing it
 * runs the matching {@code ++} under "HACK: the building is ready now"
 * The scaffolding is a unit in every other
 * respect -- it stands on the ground, it can be attacked, it shows in
 * {@code TotalNumUnits} -- and it does not count towards having one.
 *
 * <p>Counting it declares victory early. The first human mission is won by
 * {@code UnitTypesCount(..., "unit-human-barracks") >= 1}, so a port that
 * counts the site says "mission accomplished" the moment a peasant starts
 * digging the foundations. Nine other missions have a "build one of these"
 * objective and end the same way, and the AI scripts read the same figure to
 * decide what they still need, so an AI that counts its own foundations stops
 * building.
 */
class UnitTypesCountTest {

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    private static World plain(GameData data) {
        int size = 48;
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i < 2 ? PudMap.PlayerType.PERSON : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        World world = new World(map, players);
        world.setUnitTypes(data.unitTypes().types());
        world.setUpgrades(data.upgrades().upgrades());
        world.setMissileTypes(data.missiles().types());
        return world;
    }

    @Test
    @DisplayName("A building under construction does not count as one")
    void scaffoldingDoesNotCount() {
        GameData data = load();
        World world = plain(data);
        UnitType barracks = data.unitTypes().types().get("unit-human-barracks");
        assertNotNull(barracks);

        Unit site = world.createUnit(barracks, 0, 10, 10);
        assertNotNull(site);
        site.setOrder(Unit.Order.UNDER_CONSTRUCTION);
        site.setProgress(0);
        site.setProgressGoal(10_000);

        assertEquals(0, world.unitTypesCount(0, "unit-human-barracks"),
                "a barracks that is still a hole in the ground counts as a barracks, so"
                        + " every mission whose objective is to build one is won the moment"
                        + " the peasant starts digging");
    }

    @Test
    @DisplayName("It counts once it is finished")
    void aFinishedBuildingCounts() {
        GameData data = load();
        World world = plain(data);
        UnitType barracks = data.unitTypes().types().get("unit-human-barracks");

        Unit site = world.createUnit(barracks, 0, 10, 10);
        site.setOrder(Unit.Order.UNDER_CONSTRUCTION);
        site.setProgressGoal(10_000);
        assertEquals(0, world.unitTypesCount(0, "unit-human-barracks"));

        site.setOrder(Unit.Order.STILL);
        assertEquals(1, world.unitTypesCount(0, "unit-human-barracks"),
                "a finished barracks does not count, so the mission can never be won");
    }

    /**
     * Only {@code UnitTypesCount} gets the special case. {@code TotalNumUnits}
     * counts everything, which is what the defeat condition -- "you have
     * nothing left" -- depends on: a player whose last possession is a
     * half-built farm has not lost yet.
     */
    @Test
    @DisplayName("The total still counts a building site")
    void theTotalCountsEverything() {
        GameData data = load();
        World world = plain(data);
        UnitType barracks = data.unitTypes().types().get("unit-human-barracks");

        Unit site = world.createUnit(barracks, 0, 10, 10);
        site.setOrder(Unit.Order.UNDER_CONSTRUCTION);
        site.setProgressGoal(10_000);

        int total = 0;
        for (Unit unit : world.units()) {
            if (unit.player() == 0 && unit.isAlive()) {
                total++;
            }
        }
        assertEquals(1, total,
                "a player whose only possession is a building site should not read as having"
                        + " nothing, or they lose the moment they start their first building");
    }

    /** Somebody else's building is not yours however finished it is. */
    @Test
    @DisplayName("The count is per player")
    void theCountIsPerPlayer() {
        GameData data = load();
        World world = plain(data);
        UnitType barracks = data.unitTypes().types().get("unit-human-barracks");

        world.createUnit(barracks, 1, 10, 10);
        assertEquals(0, world.unitTypesCount(0, "unit-human-barracks"));
        assertEquals(1, world.unitTypesCount(1, "unit-human-barracks"));
    }

    /**
     * The mission this was found in, end to end: four farms and a barracks
     * wins the first human mission, and a barracks still going up must not.
     */
    @Test
    @DisplayName("The first human mission is not won by starting a barracks")
    void theFirstMissionIsNotWonByStarting() {
        GameData data = load();
        var mission = data.loadMission("campaigns/human/level01h");
        Assumptions.assumeTrue(mission != null, "the first human mission is not available");
        World world = mission.world();
        int player = GameData.personIn(mission.source());

        UnitType farm = data.unitTypes().types().get("unit-farm");
        UnitType barracks = data.unitTypes().types().get("unit-human-barracks");
        assertNotNull(farm);
        assertNotNull(barracks);

        int placed = 0;
        for (int x = 2; x < 40 && placed < 4; x += 3) {
            if (world.createUnit(farm, player, x, 2) != null) {
                placed++;
            }
        }
        Assumptions.assumeTrue(placed == 4, "nowhere to put four farms");

        Unit site = null;
        for (int x = 2; x < 40 && site == null; x += 4) {
            site = world.createUnit(barracks, player, x, 6);
        }
        Assumptions.assumeTrue(site != null, "nowhere to put a barracks");
        site.setOrder(Unit.Order.UNDER_CONSTRUCTION);
        site.setProgress(0);
        site.setProgressGoal(10_000);

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 3; cycle++) {
            mission.tick();
        }
        assertTrue(
                mission.outcome()
                        != net.chonkbase.chonkcraft.engine.trigger.TriggerSystem.Outcome.VICTORY,
                "the mission was won while the barracks was still scaffolding");

        // And it is won once the thing is actually built.
        site.setOrder(Unit.Order.STILL);
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 3; cycle++) {
            mission.tick();
        }
        assertEquals(net.chonkbase.chonkcraft.engine.trigger.TriggerSystem.Outcome.VICTORY,
                mission.outcome(),
                "four farms and a finished barracks should win the first human mission");
    }
}
